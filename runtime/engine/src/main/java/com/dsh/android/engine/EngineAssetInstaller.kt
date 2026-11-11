package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 拥有引擎归档的 staging 目录与解包事务；状态展示由调用者映射。 */
internal class EngineAssetInstaller(
    private val assets: AssetManager,
    private val filesDir: File,
    private val apkFile: File,
    private val engineDir: File,
) {
    private val stampFile get() = File(engineDir, ".stamp")

    /**
     * 确保引擎资产已解包（幂等）。
     *
     * @param versionStamp 资产版本标识；与已解包版本一致时直接跳过。
     */
    suspend fun ensureExtracted(versionStamp: String, progress: (Int) -> Unit, failure: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (stampFile.isFile && stampFile.readText().trim() == versionStamp) {
            Log.i(TAG, "引擎资产已是最新（$versionStamp），跳过解包")
            return@withContext
        }

        progress(0)
        // 先解到临时目录，成功后再原子替换，避免中途失败留下半成品
        val staging = File(filesDir, "engine.staging")
        staging.deleteRecursively()

        /*
         * 空间预检：不够就**立刻**报一句人话，不要先花十几秒解到一半再抛 IOException。
         *
         * ⚠️ 真机上这条路径很容易被走到：引擎解压后约 **262 MB**，而用户把手机
         *   装满是常态。不做预检时的症状是「正在解包引擎 47%」卡住 → 抛
         *   `IOException: No space left on device` → 界面显示
         *   「引擎解包失败: ...」，用户完全不知道是自己空间不够、也不知道要清多少。
         *
         * 余量给 1.25 倍：解压过程需要的峰值略高于最终大小（tar 条目 + 块对齐）。
         * ⚠️ getUsableSpace() 是**建议值**，会随系统压力变化，所以它只能用来
         *   「提前劝退明显不够的情况」，**不能**代替写失败的处理 —— 下面的 catch
         *   依然要保留（写一半空间被别的进程吃掉照样会发生）。
         */
        run {
            val need = estimateExtractBytes()
            if (need > 0L) {
                val free = staging.parentFile?.usableSpace ?: 0L
                val required = (need * 5L) / 4L
                if (free in 1 until required) {
                    val msg = "存储空间不足：解包引擎需要约 " +
                        "${required / (1024 * 1024)} MB，当前可用仅 ${free / (1024 * 1024)} MB"
                    Log.e(TAG, msg)
                    failure(msg)
                    return@withContext
                }
            }
        }

        staging.mkdirs()

        try {
            // 进度基准：**tar.gz 压缩流的总字节数**（约 54 MB）。
            //
            // ⚠️ 这里原先用 `assets.openFd(ASSET_NAME).length`，而它**必然失败**：
            //   openFd 只对**未压缩**（stored）的资产有效，AGP 对 engine.bin 做了
            //   deflate，于是抛 FileNotFoundException → total=0 → 下面的
            //   `if (total > 0)` 恒假 → **进度永远停在 0%**。
            //   真机实测确认：首次启动那 15 秒里文案一直是「正在解包引擎 0%」。
            //
            // 改用 `ZipFile` 直接读 APK 自己的 zip 目录（`sourceDir` 就是 APK 路径），
            //   那里有权威的未压缩大小。
            //
            // ⚠️ 曾经想过读 gzip 尾部的 ISIZE 字段（那是解压后总字节数，实测精确），
            //   但那要**完整 inflate 一遍 262 MB** 才能摸到最后 4 字节 —— 白白让首次
            //   启动多花几秒。这里改成「基准 = 压缩流总长 + 实时统计已消费的压缩字节」，
            //   量纲天然一致，零额外成本。
            val total = archiveLength() ?: 0L
            if (total <= 0L) {
                Log.w(TAG, "读不到归档长度，本次解包不显示百分比")
            }
            var lastPercent = -1
            assets.open(ASSET_NAME).use { raw ->
                val counting = CountingInputStream(raw)
                TarGz.extract(counting, staging) {
                    if (total > 0L) {
                        val pct = ((counting.count * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            progress(pct)
                        }
                    }
                }
            }
            File(staging, ".stamp").writeText(versionStamp)

            engineDir.deleteRecursively()
            if (!staging.renameTo(engineDir)) {
                throw java.io.IOException("无法将解包目录重命名为 $engineDir")
            }
            progress(100)
            Log.i(TAG, "引擎资产解包完成：${engineDir.absolutePath}")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            failure("引擎解包失败: ${t.message}")
            throw t
        }
    }

    /**
     * 读 `assets/engine.bin` 在 APK 里的**未压缩大小**（约 54 MB）。
     *
     * ⚠️ 为什么不用 `assets.openFd().length`：那只对未压缩资产有效，而 AGP 对
     *   engine.bin 做了 deflate，必然抛 FileNotFoundException。见调用点的注释。
     *
     * `context.applicationInfo.sourceDir` 就是 APK 的绝对路径，用 `ZipFile` 打开它
     * 即可拿到权威的 zip 目录项大小 —— 只读中央目录，不解压、不落盘，开销可忽略。
     *
     * ⚠️ 失败一律返回 null，**绝不能因此让解包失败** —— 没有百分比只是体验差，
     *   解包失败是功能不可用。
     */
    private fun archiveLength(): Long? = try {
        val apk = apkFile
        java.util.zip.ZipFile(apk).use { zip ->
            // ⚠️ zip 里的条目全名带 `assets/` 前缀，直接用 ASSET_NAME 查会得到 null
            //   （且**不抛异常**，只是静默返回 null）。这里踩过一次。
            val entry = zip.getEntry("assets/$ASSET_NAME")
            val len = entry?.size ?: 0L
            Log.i(TAG, "engine.bin 归档长度 = $len 字节（entry=${entry?.name}）")
            len.takeIf { it > 0L }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读归档长度失败: ${t.message}")
        null
    }

    /**
     * 估算解包引擎**需要多少磁盘空间**（字节）。估不出来时返回 0（调用方跳过预检）。
     *
     * ## 为什么不用 `archiveLength()`
     *
     * 那个返回的是 **APK 里 engine.bin 这个 zip 条目的压缩后大小**（约 55 MB），
     * 而 tar.gz 解出来的内容约 **262 MB** —— 差了近 5 倍。拿它做空间判断会
     * 让「明明装得下」的用户被误拒，属于比不做预检更糟的行为。
     *
     * ## 这里怎么估
     *
     * 要拿到准确值只有两条路，都不合适：
     *   · 读完 gzip 尾部的 ISIZE 字段 —— 需要完整 inflate 一遍 262 MB（首次启动多花几秒）；
     *   · 逐条遍历 tar 头累加 size —— 也要把整个压缩流解一遍。
     *
     * 所以用**压缩比的保守上界**推：tar.gz 里绝大多数是 JS 文本，实测压缩比约 4.7:1。
     * 取 4 倍作为估计（比实测小）→ 给出 220 MB 左右的下界，再乘 1.25 的峰值余量。
     * 宁可估小一点：估大了会把本来装得下的用户挡在门外，那不可接受；
     * 估小了只是这次预检没拦住，后面写失败的 catch 仍然兜着。
     */
    private fun estimateExtractBytes(): Long {
        val archived = archiveLength() ?: return 0L
        return archived * 4L
    }

    /**
     * 只统计「从底层流读走了多少字节」的包装流。
     *
     * 用于把解包进度换算成**压缩流**的消费比例 —— 与 [archiveLength] 同一量纲。
     * 不改变任何读写语义，纯粹是个计数器。
     */
    private class CountingInputStream(
        private val delegate: java.io.InputStream,
    ) : java.io.InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }
}
