package com.dsh.android

/**
 * 从 APK assets 中解出 tar.gz 归档。
 *
 * 之所以自己解析 tar 而不用第三方库：Android 平台自带 GZIPInputStream，
 * 而 tar 格式（ustar）头部结构极其简单，手写解析可以完全避免额外依赖。
 */
internal object TarGz {

    private const val BLOCK = 512

    /**
     * 解压 [input]（tar.gz 流）到 [destDir]。
     *
     * @param onProgress 已写出字节数回调，用于展示进度。
     */
    fun extract(
        input: java.io.InputStream,
        destDir: java.io.File,
        onProgress: (Long) -> Unit = {},
    ) {
        val gz = java.util.zip.GZIPInputStream(input, 64 * 1024)
        gz.use { stream ->
            val header = ByteArray(BLOCK)
            var written = 0L

            while (true) {
                if (!readFully(stream, header)) break
                if (isZeroBlock(header)) break // 归档结束标记

                val name = readString(header, 0, 100)
                val prefix = readString(header, 345, 155)
                // ustar 用 prefix(155) + name(100) 拼出最长 255 字符的路径。
                // 本归档最长路径 162 字符，正是靠 prefix 承载的。
                val fullName = (if (prefix.isEmpty()) name else "$prefix/$name").trimEnd('/')
                val size = readOctal(header, 124, 12)
                val typeFlag = header[156].toInt().toChar()

                if (fullName.isEmpty()) break

                val target = java.io.File(destDir, fullName)
                // 防御路径穿越（归档里出现 ../ 时拒绝）
                if (!target.canonicalPath.startsWith(destDir.canonicalPath + java.io.File.separator) &&
                    target.canonicalPath != destDir.canonicalPath
                ) {
                    throw java.io.IOException("tar 条目越界: $fullName")
                }

                when (typeFlag) {
                    '5' -> { // 目录
                        target.mkdirs()
                    }
                    '0', '\u0000' -> { // 普通文件
                        target.parentFile?.mkdirs()
                        java.io.FileOutputStream(target).use { out ->
                            var remaining = size
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val want = minOf(remaining, buf.size.toLong()).toInt()
                                val read = stream.read(buf, 0, want)
                                if (read <= 0) throw java.io.IOException("tar 数据截断: $fullName")
                                out.write(buf, 0, read)
                                remaining -= read
                                written += read
                                onProgress(written)
                            }
                        }
                    }
                    else -> { // 其他类型（符号链接等）只跳过数据
                    }
                }

                // tar 每个条目按 512 字节对齐
                val padding = (BLOCK - (size % BLOCK)) % BLOCK
                if (padding > 0) skipFully(stream, padding, fullName)
            }
        }
    }

    /**
     * 读满一整块返回 true；读不到任何字节（归档正常结束）返回 false。
     * 合法 tar 的块总是 512 的整数倍，因此部分读取只可能出现在文件被截断时。
     */
    private fun readFully(stream: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        if (off == 0) return false
        if (off < buf.size) throw java.io.IOException("tar 头部被截断：只读到 $off/${buf.size} 字节")
        return true
    }

    /**
     * 跳过 [count] 个字节（tar 的 512 字节对齐 padding）。
     *
     * ⚠️ **流提前结束必须抛异常，不能静默 return。**
     *
     * 原来这里是 `if (stream.read() < 0) return` —— 也就是「到了 EOF 就悄悄收工」。
     * 它的后果比看上去严重得多，因为**调用点就在主循环里**：
     *
     *   文件数据段写完 → skipFully 跳过 padding → 流已截断（EOF）
     *   → 静默 return → 回到主循环 → readFully 读到 0 字节（off == 0）
     *   → 它把「EOF」当成**归档正常结束标志** → break
     *   → `extract()` **正常返回**，一个异常都不抛
     *
     * 于是上层看到的是：
     *
     *   `Log.i(TAG, "引擎资产解包完成：…")` → 版本戳落盘 → 之后永远跳过重解包
     *
     * 而磁盘上是**不完整的引擎**。表现是引擎起来就报插件加载失败、
     * 或者 node 找不到模块 —— 排查方向会完全指向「引擎本身有问题」，
     * 而真因是「上次解包被截断了，且被记成了成功」。
     *
     * 归档被截断在真机上不是理论场景：APK 边下边装（部分 ROM 的 split 安装）、
     * 存储在解包过程中被卸载、或用户强制停止 —— 都会产生「读到一半没了」。
     *
     * 所以这里改成：skip 不足就抛 IOException，让 [extract] 的调用方
     * （`ensureExtracted` 的 catch）把它当失败处理，**不要落版本戳**。
     */
    private fun skipFully(stream: java.io.InputStream, count: Long, name: String) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // skip 返回 0 表示「暂时跳不动」，用一个 read 推进一步；
                // read 返回 -1 才是真的 EOF —— 此时数据确实不全。
                if (stream.read() < 0) {
                    throw java.io.IOException(
                        "tar 归档在 padding 处被截断（还差 $remaining 字节，条目: $name）",
                    )
                }
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun isZeroBlock(buf: ByteArray): Boolean {
        for (b in buf) if (b.toInt() != 0) return false
        return true
    }

    /** tar 字符串字段：以 NUL 结尾，可能含尾部垃圾。 */
    private fun readString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    /** tar 数字字段：八进制 ASCII，可能以 NUL 或空格结尾。 */
    private fun readOctal(buf: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var i = offset
        val limit = offset + length
        // 跳过前导空格
        while (i < limit && (buf[i].toInt() == 0x20 || buf[i].toInt() == 0)) i++
        while (i < limit) {
            val c = buf[i].toInt()
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
            i++
        }
        return value
    }
}
