package com.dsh.android.repair

import com.dsh.android.repair.contract.RepairProtocol
import org.json.JSONObject

/** Strict projection: no raw event fields or boot identifiers leave this object. */
internal object RepairDiagnosticSummary {
    private const val MAX_EVENTS = 12
    private const val MAX_ROW_CHARS = 8192
    private val phaseLabels = mapOf(
        "boot.start" to "启动记录已创建",
        "boot.previous-unclean" to "上一进程未标记正常退出（不等于崩溃）",
        "app.onCreate" to "进入应用初始化",
        "termux.done" to "Termux 引导结束",
        "termux.failed" to "Termux 引导报告失败",
        "engine.start" to "请求启动引擎",
        "engine.wait-bootstrap" to "等待运行环境",
        "engine.ready" to "引擎报告就绪",
        "engine.failed" to "引擎报告启动失败",
        "engine.exit" to "引擎已退出",
        "renderer.gone" to "Web 渲染进程退出",
    )

    internal fun summarize(boot: String?, events: String?): String {
        val state = boot?.let(::parse)
        val id = (state?.opt("bootId") as? String)?.takeIf { it.isNotBlank() }
        val recent = ArrayDeque<String>()
        if (id != null && events != null) for (line in events.lineSequence()) {
            val event = parse(line) ?: continue
            if (event.opt("bootId") != id) continue
            val label = phaseLabels[event.opt("name") as? String ?: continue] ?: continue
            if (recent.size == MAX_EVENTS) recent.removeFirst()
            recent.addLast(label)
        }
        return buildString {
            appendLine("最近一次记录的 DSH 启动（仅本地摘要）")
            if (id == null) {
                appendLine("未找到可读的启动记录；无法把事件归到一次启动。")
            } else {
                appendLine(when (state?.opt("uiLoaded")) {
                    true -> "正式界面：曾标记为已加载，不代表当前仍可用"
                    false -> "正式界面：此轮未记录已加载"
                    else -> "正式界面：无记录"
                })
                if (recent.isEmpty()) appendLine("此轮没有可用的近期诊断事件。")
                else recent.forEach { appendLine("• $it") }
            }
        }.trimEnd().take(RepairProtocol.MAX_REPORT_CHARS)
    }

    private fun parse(value: String): JSONObject? {
        if (value.isBlank() || value.length > MAX_ROW_CHARS) return null
        // Bound nesting before entering the platform JSON parser. Standard records are shallow.
        if (value.count { it == '{' || it == '[' } > 128) return null
        return runCatching { JSONObject(value) }.getOrNull()
    }

}
