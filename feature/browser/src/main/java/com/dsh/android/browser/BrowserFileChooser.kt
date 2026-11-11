package com.dsh.android.browser

import android.net.Uri
import android.webkit.ValueCallback

/** 单个浏览器的文件选择事务；回调绑定发起标签，迟到结果不能结算新请求。 */
internal class BrowserFileChooser(private val sessions: BrowserTabSessionStore) {
    private class Request(
        val callback: ValueCallback<Array<Uri>>,
        var detach: () -> Unit = {},
    )

    private var pending: Request? = null
    private var closed = false

    fun open(
        tab: BrowserTabSessionStore.Tab?,
        callback: ValueCallback<Array<Uri>>?,
        picker: ((ValueCallback<Array<Uri>>) -> Unit)?,
    ) {
        pending?.let { complete(it, null) }
        if (callback == null) return
        if (closed || tab == null || !sessions.contains(tab) || picker == null) {
            callback.onReceiveValue(null)
            return
        }
        val request = Request(callback)
        pending = request
        request.detach = sessions.onClose(tab) { complete(request, null) }
        try {
            picker(ValueCallback { result -> complete(request, result) })
        } catch (error: Throwable) {
            complete(request, null)
            android.util.Log.w("AiBrowser", "文件选择失败: ${error.message}")
        }
    }

    fun close() {
        closed = true
        pending?.let { complete(it, null) }
    }

    private fun complete(request: Request, result: Array<Uri>?) {
        if (pending !== request) return
        pending = null
        request.detach()
        try {
            request.callback.onReceiveValue(result)
        } catch (error: Throwable) {
            android.util.Log.w("AiBrowser", "文件选择回调失败: ${error.message}")
        }
    }
}
