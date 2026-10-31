package com.dsh.android.ui
import com.dsh.android.log.DshLog as Log
import org.json.JSONArray
import org.json.JSONObject
import com.dsh.android.core.DshApplication
import com.dsh.android.engine.EngineStatus

/**
 * 会话转录 —— 悬浮窗展开态要显示的**完整**对话内容。
 *
 * ## 为什么要单独开一条通道（而不是塞进 [EngineStatus]）
 *
 * [EngineStatus] 每 5 秒推一次、且通知栏也在用，它的 `body` 被刻意压成 400 字。
 * 而这里要的是「微博里能看到的内容全部都要有」—— 用户消息、助手完整回复、
 * 每一次工具调用的名称与参数。两者体量与用途都不同，混在一条通道里会让
 * 通知栏的更新成本被转录的体量拖累。所以：
 *
 * | 通道 | 频率 | 体量 | 消费者 |
 * |---|---|---|---|
 * | [EngineStatus] | 变化即推 | ≤400 字 | 通知栏 + 气泡状态横条 |
 * | [ChatTranscript]（本类） | 内容摘要变化才推 | 上限 [MAX_ITEMS] 条 × 各 2000 字 | 气泡展开面板 |
 *
 * ## 与页面侧的契约
 *
 * 由 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` 的 `mpCollectTranscript()`
 * 产生，**两边必须一起改**。
 *
 * ⚠️ 解析**永不抛异常**：气泡是锦上添花，页面改个字段不能把引擎进程搞崩。
 *   任何一项解析失败就跳过那一项，其余照常显示。
 */
data class ChatTranscript(
    /** 会话标题。空串表示页面还没拿到（新建会话早期）。 */
    val title: String,
    /** 是否正在跑（与 [EngineStatus.running] 同源，这里冗余一份供面板头部显示）。 */
    val running: Boolean,
    /** 按时间顺序排列的条目。 */
    val items: List<Item>,
) {

    /**
     * 一条转录内容。
     *
     * @param role 见 [Role] 的常量；未知值一律退化成 [Role.OTHER]。
     * @param text 已经压成单行的展示文本（页面侧做过压缩，原生侧只负责排版）。
     * @param running 这一条是否还在进行中（工具调用进行中要显示转圈感）。
     */
    data class Item(
        val role: String,
        val text: String,
        val running: Boolean = false,
    )

    /** 条目角色。页面侧发的是这些字符串，原生侧按它们决定气泡的左右与配色。 */
    object Role {
        /** 用户自己发的消息 —— 右对齐。 */
        const val USER = "user"

        /** 助手回复（含 reasoning 与正文） —— 左对齐。 */
        const val ASSISTANT = "assistant"

        /**
         * 助手的**思考内容**（思维链） —— 左对齐、灰底、弱化显示。
         *
         * ⚠️ 与 [ASSISTANT] 必须分开：官方 DOM 里思考与正文是两个并列节点
         *   （`data-chat-group-part` = `reasoning` / `response`），不分开的话面板上
         *   两条都标「AI」、样式也一样，用户看到的就是"思考和正文糊在一起"。
         */
        const val REASONING = "reasoning"

        /** 工具调用（bash / read / write / shell / root …） —— 左对齐窄条。 */
        const val TOOL = "tool"

        /** 这一轮出错（API 错误、工具失败） —— 左对齐红条。 */
        const val ERROR = "error"

        /** 兜底。页面加了新角色而原生还没跟上时走这里。 */
        const val OTHER = "other"
    }

    companion object {
        /** 条目数上限。转录是历史累积的，不设上限会让面板越开越慢。 */
        const val MAX_ITEMS = 80

        /** 单条文本长度上限（code point）。 */
        const val MAX_TEXT_CHARS = 2000

        /**
         * 解析页面发来的 JSON。
         *
         * ⚠️ 返回 null 只表示「整个 JSON 不可解析」；单项解析失败是跳过那一项。
         */
        fun parse(json: String): ChatTranscript? = try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Item>(minOf(arr.length(), MAX_ITEMS))
            // 只保留**最后** MAX_ITEMS 条：用户要看的是当前进展，早期历史可以丢。
            val start = (arr.length() - MAX_ITEMS).coerceAtLeast(0)
            for (i in start until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val text = e.optString("text", "").trim()
                if (text.isEmpty()) continue
                items += Item(
                    role = normalizeRole(e.optString("role", "")),
                    text = clamp(text, MAX_TEXT_CHARS),
                    running = e.optBoolean("running", false),
                )
            }
            ChatTranscript(
                title = o.optString("title", ""),
                running = o.optBoolean("running", false),
                items = items,
            )
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "转录上报解析失败: ${t.message}")
            null
        }

        /** 未知角色归到 [Role.OTHER]，让原生侧的排版逻辑只需处理有限几种。 */
        private fun normalizeRole(raw: String): String = when (raw) {
            Role.USER, Role.ASSISTANT, Role.REASONING, Role.TOOL, Role.ERROR -> raw
            else -> Role.OTHER
        }

        /** 按 code point 截断，避免把一个 emoji / 代理对切成半个字符。 */
        private fun clamp(text: String, maxChars: Int): String {
            val cps = text.codePoints().toArray()
            if (cps.size <= maxChars) return text
            return String(cps, 0, maxChars - 1) + "…"
        }
    }
}
