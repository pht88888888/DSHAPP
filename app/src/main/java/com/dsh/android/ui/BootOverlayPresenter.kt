package com.dsh.android.ui

/** 启动层显示状态和两级等待提示。视图、动画和点击行为仍由 Activity 渲染。 */
internal class BootOverlayPresenter(
    private val scheduler: UiTaskScheduler,
    private val pageError: () -> String,
    private val render: (State) -> Unit,
    private val onTimeout: () -> Unit,
) {
    enum class Action { RetryEngine, ReloadPage }

    data class State(
        val text: String,
        val hint: String?,
        val progress: Float?,
        val isError: Boolean,
        val action: Action? = null,
    )
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
            if (closed || dismissed || owner != generation) return@Runnable
            timeout = null
            onTimeout()
            val error = pageError().takeIf { it.isNotBlank() }?.let { "页面曾报告：$it" }
            val hint = if (soft) "仍在等待页面就绪。" else "已等 27 秒，仍未收到界面就绪回调。"
            show(State(
                if (soft) "界面加载中" else "界面尚未就绪",
                listOfNotNull(hint, error).joinToString("\n"),
                progress = null,
                isError = !soft,
                action = if (soft) null else Action.ReloadPage,
            ))
            if (soft && !closed && !dismissed && owner == generation) schedule(owner, false, 15_000L)
        }.also { scheduler.postDelayed(it, delayMs) }
    }
}
