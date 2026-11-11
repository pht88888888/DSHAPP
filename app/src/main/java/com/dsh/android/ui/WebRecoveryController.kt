package com.dsh.android.ui

/** 渲染恢复策略与重试时序。实际 WebHost、授权 URL 和首帧注册始终归宿主。 */
internal class WebRecoveryController(
    private val scheduler: UiTaskScheduler,
    private val now: () -> Long,
    private val reload: (Int) -> Boolean,
    private val hasFirstFrame: () -> Boolean,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        data class RendererGone(val crashed: Boolean, val count: Int) : Event()
        data class CrashLimitReached(val crashed: Boolean, val count: Int) : Event()
        data class ReloadExhausted(val attempts: Int) : Event()
    }

    private var restarts = 0
    private var lastGoneAt = 0L
    private var queued: Runnable? = null
    private var timeout: Runnable? = null
    private var generation = 0L
    private var closed = false
    var gaveUp = false
        private set

    fun rendererGone(crashed: Boolean) {
        if (closed || gaveUp || queued != null) return
        val time = now()
        if (time - lastGoneAt > 90_000L) restarts = 0
        lastGoneAt = time
        restarts++
        onEvent(Event.RendererGone(crashed, restarts))
        if (closed || gaveUp) return
        if (restarts > 3) {
            abandon()
            onEvent(Event.CrashLimitReached(crashed, restarts))
            return
        }
        cancelPending()
        val owner = generation
        queued = Runnable {
            queued = null
            if (current(owner)) attempt(1, owner)
        }.also { scheduler.postDelayed(it, 400L) }
    }

    fun requestReload(): Boolean {
        if (closed || gaveUp) return false
        cancelPending()
        return attempt(1, generation)
    }

    fun close() {
        closed = true
        cancelPending()
    }

    private fun attempt(number: Int, owner: Long): Boolean {
        if (!current(owner) || !reload(number)) return false
        if (!current(owner)) return false
        timeout = Runnable {
            timeout = null
            if (!current(owner) || hasFirstFrame()) return@Runnable
            if (number < 3) {
                attempt(number + 1, owner)
            } else {
                abandon()
                onEvent(Event.ReloadExhausted(number))
            }
        }.also { scheduler.postDelayed(it, 16_000L + (number - 1) * 6_000L) }
        return true
    }

    private fun current(owner: Long) = !closed && !gaveUp && owner == generation

    private fun abandon() {
        gaveUp = true
        cancelPending()
    }

    private fun cancelPending() {
        generation++
        queued?.let(scheduler::remove)
        timeout?.let(scheduler::remove)
        queued = null
        timeout = null
    }
}
