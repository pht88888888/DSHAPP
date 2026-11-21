package com.dsh.android.engine

import com.dsh.android.log.DshLog as Log

/**
 * 引擎启动阶段的计时线（只写日志，不参与任何判断）。
 *
 * ## 为什么留着它
 *
 * 将原生插件、资产和补丁等各阶段的累计与增量耗时记入日志，用于定位启动开销。
 *
 * 定位：`EngineManager.start()` 只在这几个分界点各调一次，开销是读一次
 * `currentTimeMillis()`，不进关键路径。
 */
internal class BootTimeline(private val tag: String) {

    private val startMs = System.currentTimeMillis()
    private var lastMs = startMs

    /** 记一段：`阶段名: 累计 Nms (+增量ms)`，`累计` 从 `start()` 进入算起。 */
    fun mark(stage: String) {
        val now = System.currentTimeMillis()
        Log.i(tag, "启动阶段 $stage: 累计 ${now - startMs}ms (+${now - lastMs}ms)")
        lastMs = now
    }
}
