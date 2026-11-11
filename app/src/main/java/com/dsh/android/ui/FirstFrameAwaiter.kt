package com.dsh.android.ui

/** 真首帧与展示兜底分别记账，旧页面的回调不能完成新页面的等待。 */
internal class FirstFrameAwaiter(
    private val scheduler: UiTaskScheduler,
    private val requestFrame: (() -> Unit) -> Unit,
    private val onPainted: () -> Unit,
    private val onFallback: () -> Unit,
) {
    private var ready = false
    private var pending = false
    private var generation = 0L
    private var timeout: Runnable? = null
    private var closed = false
    var reallyArrived = false
        private set

    fun await(action: () -> Unit) {
        if (closed) return
        if (ready) { action(); return }
        if (pending) return
        pending = true
        val owner = generation
        timeout = Runnable {
            timeout = null
            if (closed || generation != owner || ready) return@Runnable
            ready = true
            pending = false
            onFallback()
        }.also { scheduler.postDelayed(it, 4000L) }
        requestFrame {
            if (!closed && generation == owner) {
                reallyArrived = true
                if (!ready) {
                    ready = true
                    pending = false
                    timeout?.let(scheduler::remove)
                    timeout = null
                    onPainted()
                    action()
                }
            }
        }
    }

    fun reset(clearActual: Boolean = false) {
        generation++
        timeout?.let(scheduler::remove)
        timeout = null
        pending = false
        ready = false
        if (clearActual) reallyArrived = false
    }

    fun close() {
        closed = true
        reset(clearActual = true)
    }
}
