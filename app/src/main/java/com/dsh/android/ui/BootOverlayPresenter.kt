package com.dsh.android.ui

/** 启动层显示状态和两级等待提示。视图、动画和点击行为仍由 Activity 渲染。 */
internal class BootOverlayPresenter(
    private val scheduler: UiTaskScheduler,
    private val pageError: () -> String,
    private val render: (State) -> Unit,
    private val onTimeout: () -> Unit,
) {
    data class State(val text: String, val hint: String?, val progress: Float?, val isError: Boolean)
    var dismissed = false
        private set
    var state: State? = null
        private set
    private var generation = 0L
    private var timeout: Runnable? = null
    private var closed = false

    fun show(state: State) {
        if (closed || (dismissed && !state.isError)) return
        this.state = state
        render(state)
    }

    fun dismiss(): Boolean {
        if (closed || dismissed) return false
        dismissed = true
        invalidate()
        return true
    }

    /** 宿主决定何时重新显示；不会继承上一轮页面的超时。 */
    fun reopen() {
        if (closed) return
        invalidate()
        dismissed = false
    }

    fun arm() {
        invalidate()
        if (closed) return
        schedule(generation, soft = true, delayMs = 12_000L)
    }

    fun invalidate() {
        generation++
        timeout?.let(scheduler::remove)
        timeout = null
    }

    fun close() {
        closed = true
        invalidate()
    }

    private fun schedule(owner: Long, soft: Boolean, delayMs: Long) {
        timeout = Runnable {
            timeout = null
            if (closed || dismissed || owner != generation) return@Runnable
            onTimeout()
            val error = pageError()
            when {
                error.isNotEmpty() -> show(State("界面加载失败", "$error（点一下这里可继续）", null, true))
                soft -> {
                    show(State("界面加载中", "页面还在解析，通常再等几秒就好，不用做任何操作。", null, false))
                    if (!closed && !dismissed && owner == generation) schedule(owner, false, 15_000L)
                }
                else -> show(State("界面加载较慢", "已等 27 秒仍未就绪，页面可能还在解析。稍等一下；点一下这里可继续", null, true))
            }
        }.also { scheduler.postDelayed(it, delayMs) }
    }
}
