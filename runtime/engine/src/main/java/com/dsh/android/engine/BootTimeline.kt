package com.dsh.android.engine

import com.dsh.android.log.DshLog as Log

/**
 * 引擎启动阶段的计时线（只写日志，不参与任何判断）。
 *
 * ## 为什么留着它
 *
 * 「启动很慢」这类反馈没法靠猜：真机冷启动里 `engine.start()` 这一段原本要 6.3 秒，
 * 其中 `--dump-config` 体检 ~2 秒、降级前端 ~1.5 秒、profile 登记 ~1.4 秒 —— 全是
 * **每次启动都重复做、结果必然相同**的事。把每一段的累计与增量打在日志里，才能一眼看出
 * 谁在吃时间（真机 logcat 里是 `启动阶段 体检: 累计 200ms (+59ms)` 这种行）。
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
