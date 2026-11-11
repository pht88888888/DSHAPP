package com.dsh.android.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.net.URLDecoder

/** 系统下载适配器；不持有浏览器 View，沿用 App 专属下载目录。 */
internal class BrowserDownloadAdapter(
    private val context: Context,
    private val toast: (String) -> Unit,
) {
    private companion object { const val TAG = "AiBrowser" }

    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val name = guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                userAgent?.let { addRequestHeader("User-Agent", it) }
                mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                setTitle(name)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, name,
                )
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("开始下载：$name")
            Log.i(TAG, "下载已入队: $url → Download/$name")
        } catch (t: Throwable) {
            Log.w(TAG, "下载失败: ${t.message}")
            toast("下载失败：${t.message}")
        }
    }

    /** 从 `Content-Disposition` 或 URL 末段猜文件名，并去掉路径分隔符。 */
    private fun guessFileName(url: String, cd: String?, mime: String?): String {
        var raw = ""
        if (!cd.isNullOrBlank()) {
            val m = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(cd)
            raw = m?.groupValues?.getOrNull(1).orEmpty()
            if (raw.isNotEmpty()) {
                raw = try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (t: Throwable) {
                    raw
                }
            }
        }
        if (raw.isBlank()) {
            raw = Uri.parse(url).lastPathSegment.orEmpty()
        }
        raw = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimStart('.')
        if (raw.isBlank()) {
            raw = "download-" + System.currentTimeMillis() +
                when {
                    mime?.contains("png") == true -> ".png"
                    mime?.contains("jpeg") == true -> ".jpg"
                    mime?.contains("pdf") == true -> ".pdf"
                    else -> ""
                }
        }
        return raw.take(80)
    }

}
