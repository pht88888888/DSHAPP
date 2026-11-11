package com.dsh.android.ui

/** UI 时序接缝；生产由主线程 Handler 驱动，测试可推进虚拟时间。 */
internal interface UiTaskScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/** 只拥有闪烁帧序列和计时，不持有 Window、Activity 或配置存储。 */
internal class SystemBarFlashSequence(
    private val scheduler: UiTaskScheduler,
    private val onFrame: (SystemBarAppearance, String?, String?) -> Unit,
    private val onComplete: (Int) -> Unit,
) {
    private var config: SystemBarAppearance? = null
    private var frame = 0
    private var loopsDone = 0
    private val tick = Runnable { step() }
    val running: Boolean get() = config != null

    fun start(snapshot: SystemBarAppearance) {
        stop()
        config = snapshot.copy(
            statusBarFlash = snapshot.statusBarFlash.toList(),
            navBarFlash = snapshot.navBarFlash.toList(),
            flashDurations = snapshot.flashDurations.toList(),
        )
        step()
    }

    fun stop() {
        scheduler.remove(tick)
        config = null
        frame = 0
        loopsDone = 0
    }

    private fun step() {
        val snapshot = config ?: return
        val status = snapshot.statusBarFlash
        val navigation = snapshot.navBarFlash
        onFrame(snapshot, status.getOrNull(frame % status.size.coerceAtLeast(1)),
            navigation.getOrNull(frame % navigation.size.coerceAtLeast(1)))
        // 最长一条序列走完才算一圈；帧延迟沿用下一帧下标的既有语义。
        frame++
        if (frame >= maxOf(status.size, navigation.size).coerceAtLeast(1)) {
            frame = 0
            loopsDone++
            if (snapshot.flashLoops >= 0 && loopsDone >= snapshot.flashLoops) {
                val completed = loopsDone
                stop()
                onComplete(completed)
                return
            }
        }
        scheduler.postDelayed(tick, snapshot.frameDelayMs(frame))
    }
}
