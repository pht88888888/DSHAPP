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
                    throw java.io.IOException(DshApplication.instance.getString(R.string.archive_entry_outside, fullName))
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
                                if (read <= 0) throw java.io.IOException(DshApplication.instance.getString(R.string.archive_data_truncated, fullName))
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
                if (padding > 0) skipFully(stream, padding)
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
        if (off < buf.size) throw java.io.IOException(DshApplication.instance.getString(R.string.archive_header_truncated, off, buf.size))
        return true
    }

    private fun skipFully(stream: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) return
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
