package com.dsh.android.ui

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** 下载事务。调用者提供已授权请求快照或内核响应流，平台目录和提示仍归宿主。 */
internal class SessionDownloadService {
    data class Request(val url: String, val cookie: String, val userAgent: String?)

    suspend fun download(request: Request, target: File, filename: String, checkActive: () -> Unit = {}): File =
        execute(null) { transfer ->
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                if (request.cookie.isNotEmpty()) setRequestProperty("Cookie", request.cookie)
                request.userAgent?.takeIf(String::isNotEmpty)?.let { setRequestProperty("User-Agent", it) }
                setRequestProperty("Referer", "http://127.0.0.1:3080/")
            }
            transfer.connection.set(connection)
            transfer.checkActive()
            checkActive()
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            transfer.input.set(connection.inputStream)
            saveOwned(transfer, target, filename, checkActive)
        }

    suspend fun save(input: InputStream, target: File, filename: String, checkActive: () -> Unit = {}): File =
        execute(input) { saveOwned(it, target, filename, checkActive) }

    /** 每事务独立临时文件；只有完整读取和关闭成功后才原子替换，失败不删除旧成品。 */
    private fun saveOwned(transfer: Transfer, target: File, filename: String, checkActive: () -> Unit): File {
        transfer.checkActive()
        val file = File(target, sanitizeName(filename))
        val part = File.createTempFile("dsh-download-", ".part", target)
        try {
            val source = checkNotNull(transfer.input.get())
            part.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    transfer.checkActive()
                    checkActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            transfer.closeInput()
            transfer.checkActive()
            checkActive()
            transfer.beginCommit()
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return file
        } finally {
            Files.deleteIfExists(part.toPath())
        }
    }

    /** 取消同步撤销提交权；关闭在 IO。不能中断的读取仍由本作用域等待原超时/返回并释放。 */
    private suspend fun execute(input: InputStream?, action: (Transfer) -> File): File = coroutineScope {
        val transfer = Transfer(input)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                transfer.cancel()
                withContext(NonCancellable + Dispatchers.IO) { transfer.close() }
            }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                try { action(transfer) } finally { transfer.close() }
            }
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            withContext(NonCancellable) { cancellation.cancelAndJoin() }
        }
    }

    private class Transfer(body: InputStream?) {
        val connection = AtomicReference<HttpURLConnection?>()
        val input = AtomicReference(body)
        private val state = AtomicInteger(0) // 0 = 可提交，1 = 已进入原子提交，2 = 已取消

        fun checkActive() {
            if (state.get() == 2) throw CancellationException("下载已取消")
        }

        fun beginCommit() {
            if (!state.compareAndSet(0, 1)) throw CancellationException("下载已取消")
        }

        fun cancel() { state.compareAndSet(0, 2) }

        fun closeInput() { input.getAndSet(null)?.close() }

        fun close() {
            runCatching { connection.getAndSet(null)?.disconnect() }
            runCatching { closeInput() }
        }
    }

    companion object {
        fun sanitizeName(raw: String, fallback: String = "dsh-download"): String {
            val cleaned = raw.replace('\\', '/').substringAfterLast('/')
                .replace("\u0000", "").filter { it != '/' && it != '\\' }.trim().trimStart('.')
            return if (cleaned.isEmpty()) fallback else cleaned.take(200)
        }
    }
}
