package com.dsh.android.ui

/** 单次页面返回探针：防重入、超时放行，并作废迟到的 JS 回调。 */
internal class PageBackNavigation(
    private val scheduler: UiTaskScheduler,
    private val query: ((String?) -> Unit) -> Unit,
    private val onUnhandled: (timedOut: Boolean) -> Unit,
) {
    private var generation = 0L
    private var pending = false
    private var closed = false
    private var timeout: Runnable? = null

    fun request() {
        if (closed || pending) return
        pending = true
        val owner = ++generation
        fun complete(result: String?, timedOut: Boolean) {
            if (closed || generation != owner) return
            generation++
            pending = false
            timeout?.let(scheduler::remove)
            timeout = null
            if (result?.trim('"') != "true") onUnhandled(timedOut)
        }
        timeout = Runnable { complete(null, true) }
            .also { scheduler.postDelayed(it, 1200L) }
        query { complete(it, false) }
    }

    fun close() {
        closed = true
        generation++
        pending = false
        timeout?.let(scheduler::remove)
        timeout = null
    }
}
