package com.dsh.android.bubble

import android.view.View

/** 帧游标、循环次数和下一帧任务；动作选择及位图解码仍由各自所有者处理。 */
internal class BubbleFramePlayer {
    var index = 0
        private set
    private var loops = 0
    private var timerHost: View? = null
    private var task: Runnable? = null

    fun reset() {
        stop()
        index = 0
        loops = 0
    }

    fun start(
        host: View,
        count: Int,
        durations: List<Int>,
        loop: Int,
        intervalMs: Int,
        canRun: () -> Boolean,
        showFrame: (Int) -> Unit,
    ) {
        stop()
        if (count <= 1) return
        timerHost = host
        val step = object : Runnable {
            override fun run() {
                if (task !== this || !canRun()) {
                    stop()
                    return
                }
                val next = index + 1
                if (next >= count) {
                    loops++
                    if (loop in 0 until loops) {
                        stop()
                        return
                    }
                }
                index = next % count
                showFrame(index)
                // showFrame 可同步触发换动作或 hide；只允许本世代继续排队。
                if (task === this) host.postDelayed(this, delay(durations, intervalMs))
            }
        }
        task = step
        host.postDelayed(step, delay(durations, intervalMs))
    }

    private fun delay(durations: List<Int>, intervalMs: Int): Long =
        (durations.getOrNull(index) ?: intervalMs).toLong().coerceAtLeast(20L)

    /** 保留游标，改颜色和短暂停止播放时不回到第一帧。 */
    fun stop() {
        task?.let { timerHost?.removeCallbacks(it) }
        task = null
        timerHost = null
    }
}
