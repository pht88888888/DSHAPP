package com.dsh.android.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** 每标签的 Web 内核回调。页面事件只携带具体会话，不持有浏览器宿主。 */
internal class BrowserWebCallbacks(
    private val sessions: BrowserTabSessionStore,
    private val files: BrowserFileChooser,
    private val desktopViewportScript: String,
    private val desktopMode: () -> Boolean,
    private val picker: () -> ((ValueCallback<Array<Uri>>) -> Unit)?,
    private val events: Events,
) {
    interface Events {
        fun pageStarted(tab: BrowserTabSessionStore.Tab)
        fun pageFinished(tab: BrowserTabSessionStore.Tab)
        fun titleChanged(tab: BrowserTabSessionStore.Tab)
        fun progressChanged(tab: BrowserTabSessionStore.Tab, value: Int)
        fun rendererGone(tab: BrowserTabSessionStore.Tab)
    }

    fun install(tab: BrowserTabSessionStore.Tab) {
        val webView = tab.webView
        val handler = Handler(Looper.getMainLooper())
        val flush = Runnable {
            if (sessions.contains(tab)) runCatching { CookieManager.getInstance().flush() }
        }
        sessions.onClose(tab) { handler.removeCallbacks(flush) }
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (sessions.contains(tab)) {
                    Log.w("AiBrowser", "AI 浏览器的渲染进程${if (detail?.didCrash() == true) "崩溃" else "被系统回收"}，关闭该标签（不影响主界面）")
                    events.rendererGone(tab)
                }
                // 返回 false 会让系统终止整个应用进程。
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!sessions.contains(tab)) return
                sessions.navigationStarted(tab)
                events.pageStarted(tab)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!sessions.contains(tab)) return
                CookieManager.getInstance().flush()
                if (desktopMode()) webView.evaluateJavascript(desktopViewportScript, null)
                // AJAX 登录可能在 onPageFinished 后写入 Cookie，保持延迟 flush。
                handler.removeCallbacks(flush)
                handler.postDelayed(flush, 2_500L)
                tab.title = webView.title.orEmpty()
                sessions.completeLoad(tab, true)
                if (sessions.contains(tab)) events.pageFinished(tab)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (sessions.contains(tab)) events.progressChanged(tab, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!sessions.contains(tab)) return
                tab.title = title.orEmpty()
                events.titleChanged(tab)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                files.open(tab, filePathCallback, picker())
                return true
            }
        }
    }
}
