package com.dsh.android

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生上传通道 —— 绕开 Web 内核网络栈的最后一道兜底。
 *
 * ## 为什么必须有它
 *
 * 真机取证（nubia NX809J / Android 16 / WebView 143，v64 与 v65 两次）：
 * 非图片附件（mp4）上传时 XHR 立刻 `onerror`（`status=0`）、fetch 随后
 * `Failed to fetch`，而文件大小是**正确**的（1336030 / 1933873 字节）。
 *
 * v65 已经排除了「Blob 内容读不出来」：polyfill 先 `arrayBuffer()` 把字节
 * 握在手里，再用真实 `Uint8Array` 发送 —— 依然两条通道全失败。
 * 也就是说 **JS 层取数据完全正常，失败发生在「把 body 交给内核网络栈」这一步**。
 *
 * 交叉验证把服务端也排除了：
 * - 同一 `127.0.0.1:3080` 上普通接口（聊天、会话导出）全部正常；
 * - 用 curl 直接 POST 这两个体积，返回 **HTTP 200**。
 *
 * 结论：这是**该内核处理大 body POST 的能力缺陷**，成因随 ROM/内核而变，
 * JS 层无法绕过。唯一健壮的做法就是**不走内核网络栈** ——
 * 原生 [HttpURLConnection] 到 `127.0.0.1` 已在同一台设备的「下载会话导出」
 * 上长期验证可用（见 `MainActivity.downloadSessionExport`），两条路互不相干。
 *
 * ## 为什么是「分块写入」而不是一次性传完
 *
 * 字节可能几十 MB。一次性经 JS 桥塞进来有两个代价：JS 线程被这次跨语言
 * 调用长时间占住（界面卡死），且 base64 解码时多一份等量堆峰值。
 * 所以拆成 begin → write* → finish，由页面侧按 512 KB 原始字节分块喂。
 *
 * ## 线程模型
 *
 * `begin` / `write` / `abort` 都在 **JS 线程**上被调用（`@JavascriptInterface`
 * 的语义），必须立即返回；只有 [finish] 会起线程做真正的网络 IO。
 * 结果经 [postToPage] 回投页面，调用方负责切主线程。
 *
 * ⚠️ 任务表是 [ConcurrentHashMap]：`write` 在 JS 线程、`finish` 的清理在
 *   网络线程，两边并发访问。
 */
internal class NativeUploader(
    private val postToPage: (String) -> Unit,
) {

    private companion object {
        const val TAG = DshApplication.TAG

        /**
         * 单个上传允许累积的最大字节数。
         *
         * 不是协议限制，纯粹是防 OOM：`ByteArrayOutputStream` 会翻倍扩容，
         * 256 MB 的数据实际可能占 512 MB 堆，已经是低端机的极限。
         * 正常附件远小于这个线（真机样本 1.3–1.9 MB）。
         */
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

        /** 只允许打本机回环 —— 见 [begin] 的注释。 */
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        /**
         * 由 [HttpURLConnection] 自己管理、**不允许**页面覆盖的请求头。
         *
         * `Content-Length` 尤其危险：[finish] 用的是
         * `setFixedLengthStreamingMode(bytes.size)`，此时再 `setRequestProperty`
         * 一个不一致的长度会让内核按错长度发，服务端读到截断的 body。
         */
        val RESERVED_HEADERS = setOf(
            "content-length", "host", "connection", "transfer-encoding",
        )
    }

    private class Task(val url: String, val headers: Map<String, String>) {
        val buffer = ByteArrayOutputStream(1 shl 16)

        @Volatile
        var aborted = false
    }

    private val tasks = ConcurrentHashMap<String, Task>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 开始一次上传。
     *
     * ⚠️ **只接受本机回环地址**：这条通道存在的理由就是绕开回环的内核缺陷，
     *   它不是通用 HTTP 客户端。放开任意 URL 等于把 SSRF 面直接开给页面
     *   （页面里的任何脚本都能借原生身份去访问内网 / 公网）。
     *
     * @return false = 参数非法（URL 不合规 / taskId 重复 / 已有同名任务）。
     */
    fun begin(taskId: String, url: String, headersJson: String): Boolean {
        if (taskId.isEmpty()) return false
        val parsed = runCatching { URL(url) }.getOrNull() ?: run {
            Log.w(TAG, "原生上传：URL 无法解析，拒绝任务 $taskId")
            fail(taskId, "上传地址无法解析")
            return false
        }
        if (parsed.protocol != "http" && parsed.protocol != "https") {
            Log.w(TAG, "原生上传：只允许 http(s)，拒绝 ${parsed.protocol}")
            fail(taskId, "上传地址协议不被允许（${parsed.protocol}）")
            return false
        }
        if (parsed.host !in LOOPBACK_HOSTS) {
            Log.w(TAG, "原生上传：只允许本机回环，拒绝 ${parsed.host}")
            fail(taskId, "上传地址不是本机（${parsed.host}）")
            return false
        }
        val headers = parseHeaders(headersJson)
        if (tasks.containsKey(taskId)) {
            Log.w(TAG, "原生上传：任务 $taskId 已存在，拒绝重复 begin")
            fail(taskId, "上传任务重复")
            return false
        }
        tasks[taskId] = Task(url, headers)
        Log.i(TAG, "原生上传开始: $taskId → ${parsed.path}")
        return true
    }

    /**
     * 追加一块 base64 数据。
     *
     * @return false = 任务不存在 / 已取消 / 超出上限。
     */
    fun write(taskId: String, base64: String): Boolean {
        val task = tasks[taskId] ?: run {
            Log.w(TAG, "原生上传：任务 $taskId 不存在，write 被忽略")
            return false
        }
        if (task.aborted) return false
        val chunk = runCatching { Base64.decode(base64, Base64.NO_WRAP) }.getOrNull() ?: run {
            Log.w(TAG, "原生上传：base64 解码失败，任务 $taskId")
            return false
        }
        if (task.buffer.size().toLong() + chunk.size > MAX_TOTAL_BYTES) {
            Log.w(TAG, "原生上传：任务 $taskId 超过 ${MAX_TOTAL_BYTES} 字节上限")
            fail(taskId, "文件超过原生通道上限（${MAX_TOTAL_BYTES / 1024 / 1024} MB）")
            return false
        }
        task.buffer.write(chunk)
        return true
    }

    /**
     * 数据喂完，真正发出去。立即返回，结果经 `__dshNativeUploadDone` 回投。
     */
    fun finish(taskId: String) {
        val task = tasks[taskId] ?: run {
            Log.w(TAG, "原生上传：任务 $taskId 不存在，finish 被忽略")
            return
        }
        if (task.aborted) return
        val bytes = task.buffer.toByteArray()
        val url = task.url
        val headers = task.headers
        Log.i(TAG, "原生上传发送: $taskId，${bytes.size} 字节")

        Thread({
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    // 本机回环，但 1.9 MB 也走了几百毫秒；给足余量避免误判超时。
                    readTimeout = 120_000
                    doOutput = true
                    // 已知长度：让内核直接按 Content-Length 发，不用 chunked。
                    setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("Content-Type", "application/octet-stream")
                    // ⚠️ cookie 必须带：上传接口要求会话鉴权，而原生侧与 WebView
                    //   共享同一个 CookieManager（下载会话导出就是靠这个拿到 200）。
                    cookieFor(url)?.let { setRequestProperty("Cookie", it) }
                    setRequestProperty("Referer", "http://127.0.0.1:3080/")
                    for ((name, value) in headers) {
                        setRequestProperty(name, value)
                    }
                }
                if (task.aborted) return@Thread
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (task.aborted) return@Thread
                emit(taskId, true, code, text, "")
            } catch (e: Throwable) {
                if (!task.aborted) {
                    Log.w(TAG, "原生上传失败: $taskId", e)
                    emit(taskId, false, 0, "", e.message ?: e.toString())
                }
            } finally {
                runCatching { conn?.disconnect() }
                tasks.remove(taskId)
            }
        }, "dsh-native-upload").start()
    }

    /** 页面侧取消（用户点取消 / 组件卸载）。幂等。 */
    fun abort(taskId: String) {
        tasks.remove(taskId)?.let {
            it.aborted = true
            Log.i(TAG, "原生上传已取消: $taskId")
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun parseHeaders(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) {
                    // 大小写不敏感地挡掉内核自管的那几个（见 RESERVED_HEADERS）。
                    if (key.lowercase() in RESERVED_HEADERS) continue
                    put(key, obj.optString(key))
                }
            }
        }.getOrElse {
            Log.w(TAG, "原生上传：请求头 JSON 解析失败，忽略: ${it.message}")
            emptyMap()
        }
    }

    private fun cookieFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun fail(taskId: String, message: String) {
        tasks.remove(taskId)?.aborted = true
        emit(taskId, false, 0, "", message)
    }

    /**
     * 把结果投回页面。
     *
     * ⚠️ 页面侧必须先挂好 `window.__dshNativeUploadDone` 再调 [finish]，
     *   否则结果无处可去 —— 这里只能留一条日志（手机上等于没有）。
     *   调用方 `webview-polyfill.js` 保证了这个顺序。
     */
    private fun emit(taskId: String, ok: Boolean, status: Int, body: String, error: String) {
        val script = "window.__dshNativeUploadDone&&window.__dshNativeUploadDone(" +
            "${JSONObject.quote(taskId)},$ok,$status," +
            "${JSONObject.quote(body)},${JSONObject.quote(error)})"
        mainHandler.post {
            runCatching { postToPage(script) }
                .onFailure { Log.w(TAG, "原生上传结果回投失败: $taskId", it) }
        }
    }
}
