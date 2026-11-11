package com.dsh.android.browser

import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView

/** UI 线程独占的标签会话。移除先使身份失效，再结算回调和释放 WebView。 */
internal class BrowserTabSessionStore(private val maxTabs: Int) {
    class Tab internal constructor(val webView: WebView) {
        var title: String = ""
        internal var closed = false
        internal var navigationGeneration = 0L
        internal val loadWaiters = ArrayDeque<(Boolean) -> Unit>()
        internal val closeListeners = ArrayList<() -> Unit>()
    }

    class Selection internal constructor(
        val tab: Tab,
        internal val selectionGeneration: Long,
        internal val navigationGeneration: Long,
    )

    private var selectionGeneration = 0L
    private val entries = ArrayList<Tab>()
    val tabs: List<Tab> get() = entries.toList()
    var activeIndex: Int = -1
        private set
    private var closed = false

    fun current(): Tab? = entries.getOrNull(activeIndex)
    fun selection(): Selection? = current()?.let { Selection(it, selectionGeneration, it.navigationGeneration) }
    fun isCurrent(selection: Selection): Boolean = contains(selection.tab) &&
        current() === selection.tab && selectionGeneration == selection.selectionGeneration &&
        selection.tab.navigationGeneration == selection.navigationGeneration

    fun navigationStarted(tab: Tab) {
        checkUiThread()
        if (contains(tab)) tab.navigationGeneration++
    }
    fun find(view: WebView?): Tab? = entries.firstOrNull { it.webView === view }
    fun contains(tab: Tab): Boolean = !closed && !tab.closed && tab in entries

    fun open(createWebView: () -> WebView): Tab? {
        checkUiThread()
        if (closed || entries.size >= maxTabs) return null
        return Tab(createWebView()).also {
            entries.add(it)
            activeIndex = entries.lastIndex
            selectionGeneration++
        }
    }

    fun select(index: Int): Boolean {
        checkUiThread()
        if (closed || index !in entries.indices || index == activeIndex) return false
        activeIndex = index
        selectionGeneration++
        return true
    }

    fun close(tab: Tab) {
        checkUiThread()
        val index = entries.indexOf(tab)
        if (index < 0) return
        val previous = current()
        entries.removeAt(index)
        activeIndex = when {
            entries.isEmpty() -> -1
            activeIndex >= entries.size -> entries.lastIndex
            index < activeIndex -> activeIndex - 1
            else -> activeIndex
        }
        if (current() !== previous) selectionGeneration++
        tab.closed = true
        val listeners = tab.closeListeners.toList()
        tab.closeListeners.clear()
        listeners.forEach { listener -> safely("标签关闭回调") { listener() } }
        completeLoad(tab, false)
        // 每项分别清理，某个回调或 ROM WebView 抛异常不能阻断后续释放。
        safely("移除标签视图") { (tab.webView.parent as? ViewGroup)?.removeView(tab.webView) }
        safely("停止标签加载") { tab.webView.stopLoading() }
        safely("释放标签") { tab.webView.destroy() }
    }

    /** JS 结果绑定标签和页面世代；标签关闭时立即结束等待，迟到结果不二次派发。 */
    fun evaluate(selection: Selection?, script: String, callback: (String?) -> Unit) {
        checkUiThread()
        if (selection == null || !isCurrent(selection)) {
            callback(null)
            return
        }
        var completed = false
        var detach: () -> Unit = {}
        fun finish(value: String?) {
            if (completed) return
            completed = true
            detach()
            safely("页面脚本回调") { callback(value) }
        }
        detach = onClose(selection.tab) { finish(null) }
        try {
            selection.tab.webView.evaluateJavascript(script) { result ->
                finish(if (isCurrent(selection)) result else null)
            }
        } catch (error: Throwable) {
            finish(null)
            Log.w("AiBrowser", "页面脚本执行失败: ${error.message}")
        }
    }

    fun awaitLoad(tab: Tab?, callback: (Boolean) -> Unit) {
        checkUiThread()
        if (tab == null || !contains(tab)) callback(false)
        else tab.loadWaiters.addLast(callback)
    }

    fun completeLoad(tab: Tab, ok: Boolean) {
        checkUiThread()
        // 先取快照，重入注册的等待者属于下一轮，不能在本轮被反复消费。
        val callbacks = tab.loadWaiters.toList()
        tab.loadWaiters.clear()
        callbacks.forEach { callback -> safely("加载回调") { callback(ok && !tab.closed) } }
    }

    /** 返回解绑函数；关闭当前标签只通知它自己的订阅者。 */
    fun onClose(tab: Tab, listener: () -> Unit): () -> Unit {
        checkUiThread()
        if (!contains(tab)) {
            listener()
            return {}
        }
        tab.closeListeners.add(listener)
        return { tab.closeListeners.remove(listener); Unit }
    }

    fun destroy() {
        checkUiThread()
        if (closed) return
        closed = true
        entries.toList().forEach(::close)
    }

    private fun checkUiThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "浏览器标签必须在 UI 线程操作" }
    }

    private inline fun safely(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            Log.w("AiBrowser", "$operation 失败: ${error.message}")
        }
    }
}
