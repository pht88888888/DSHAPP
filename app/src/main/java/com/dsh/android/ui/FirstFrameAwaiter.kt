package com.dsh.android.ui

/** 首帧交接：等待过久只提示，实际绘制回调才完成；回调按页面世代隔离。 */
internal class FirstFrameAwaiter(
    private val scheduler: UiTaskScheduler,
    private val requestFrame: (() -> Unit) -> Unit,
    private val onPainted: () -> Unit,
    private val onTimeout: () -> Unit,
) {
    private var ready = false
    private var pending = false
    private var generation = 0L
    private var timeout: Runnable? = null
    private var closed = false

    fun await(action: () -> Unit) {
        if (closed) return
        if (ready) { action(); return }
        if (pending) return
        pending = true
        val owner = generation
        timeout = Runnable {
            if (closed || generation != owner || ready) return@Runnable
            timeout = null
            onTimeout()
        }.also { scheduler.postDelayed(it, 4000L) }
        requestFrame {
            if (!closed && generation == owner && !ready) {
                ready = true
                pending = false
                timeout?.let(scheduler::remove)
                timeout = null
                onPainted()
                action()
            }
        }
    }

    fun reset() {
        generation++
        timeout?.let(scheduler::remove)
        timeout = null
        pending = false
        ready = false
    }

    fun close() {
        closed = true
        reset()
    }
}
