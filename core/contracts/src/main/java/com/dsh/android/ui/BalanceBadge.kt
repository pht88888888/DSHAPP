package com.dsh.android.ui

/**
 * 余额徽标：贴在悬浮球旁边、常驻显示的一小块信息。
 *
 * ## 设计目标：让 AI 能「设计」，而不只是「开关」
 *
 * 用户的原话是「不仅是开关，而是设计，就是他自己想显示在哪个地方，以什么形式显示」。
 * 所以这里**刻意做成一组正交的旋钮**，而不是几个预设模板：
 *
 * | 维度 | 字段 |
 * |---|---|
 * | 显示在哪 | [anchor]（相对球的位置） + [gapDp]（离多远） |
 * | 长什么样 | [bg] [fg] [border] [radiusDp] [fontSizeSp] [paddingHDp] [paddingVDp] |
 * | 写什么 | [template]（带 `{balance}` 等占位符） |
 * | 什么时候出现 | [enabled] + [hideWhenIdle] |
 *
 * 预设只能在"常见组合"上省事，一旦用户想要「球左上角、半透明、只显示本次花费」，
 * 预设就全都对不上。旋钮能覆盖任意组合，且 AI 一次能全改。
 *
 * ## 刷新策略也是可设计的
 *
 * [refreshOnTurnEnd] / [refreshEverySec] / [refreshOnTap] / [refreshOnDoubleTap]
 * 是**互相独立**的多个开关，可以全开也可以全关。用户要的「点一下刷新 / 点两下刷新 /
 * 每次请求后刷新」正好对应其中几个。
 *
 * @property enabled 总开关。关掉时徽标完全不创建（不是透明，是不占位）。
 * @property anchor 相对球的方位，取值见 [ANCHORS]。
 * @property gapDp 徽标与球边缘的间距（dp）。负数会让它压到球上。
 * @property bg 背景色（`#RRGGBB` / `#AARRGGBB`）。空串 = 跟随主题卡片色。
 * @property fg 文字色。空串 = 跟随主题主文字色。
 * @property border 描边色。空串 = 不画描边（不是"跟随主题"——这里语义不同，
 *   因为"没有描边"本身是常见且合理的设计意图）。
 * @property borderWidthDp 描边宽度（dp），0 = 不画。
 * @property radiusDp 圆角（dp）。给一个大值（如 99）就是胶囊形。
 * @property fontSizeSp 字号（sp）。
 * @property paddingHDp 水平内边距（dp）。
 * @property paddingVDp 垂直内边距（dp）。
 * @property maxWidthDp 最大宽度（dp），超出省略号。0 = 不限。
 * @property template 显示内容模板。支持占位符：
 *   `{balance}` 可用余额（充值+赠送合计）、`{recharge}` 充值余额、
 *   `{bonus}` 赠送余额、`{currency}` 币种符号、`{cost}` 本次花费、
 *   `{costSession}` 本会话累计花费。
 * @property showCostFlash 花费发生变化时，让徽标"跳"一下（缩放动画）。
 * @property hideWhenIdle 只在有花费时显示（平时隐藏）。
 * @property refreshOnTurnEnd 每轮对话结束后自动刷新余额。
 * @property refreshEverySec 定时刷新间隔（秒）。0 = 不定时刷新。
 * @property refreshOnTap 单击球时刷新（仅在球没配 `click` 池时生效，因为单击
 *   被"逗宠物"占用时不冲突；这一条由原生判定，见 FloatingBubble）。
 * @property tapRefreshMinIntervalSec 两次点击刷新之间的最小间隔（秒），防止
 *   连点把官方接口打爆。
 */
data class BalanceBadge(
    val enabled: Boolean = false,
    val anchor: String = ANCHOR_TOP_RIGHT,
    val gapDp: Int = 6,
    val bg: String = "",
    val fg: String = "",
    val border: String = "",
    val borderWidthDp: Int = 0,
    val radiusDp: Int = 10,
    val fontSizeSp: Int = 11,
    val paddingHDp: Int = 7,
    val paddingVDp: Int = 3,
    val maxWidthDp: Int = 140,
    val template: String = DEFAULT_TEMPLATE,
    val showCostFlash: Boolean = true,
    val hideWhenIdle: Boolean = false,
    val refreshOnTurnEnd: Boolean = true,
    val refreshEverySec: Int = 0,
    val refreshOnTap: Boolean = false,
    val tapRefreshMinIntervalSec: Int = 10,
) {
    companion object {
        /** 默认模板：只显示余额，最不吵。 */
        const val DEFAULT_TEMPLATE = "{currency}{balance}"

        const val ANCHOR_TOP_RIGHT = "top-right"
        const val ANCHOR_TOP_LEFT = "top-left"
        const val ANCHOR_BOTTOM_RIGHT = "bottom-right"
        const val ANCHOR_BOTTOM_LEFT = "bottom-left"
        const val ANCHOR_ABOVE = "above"
        const val ANCHOR_BELOW = "below"

        /**
         * 合法锚点。**顺序即偏好顺序**：同样的配置下，靠前的更容易被判定为可用
         * （见 FloatingBubble 里的贴边避让逻辑）。
         */
        val ANCHORS = listOf(
            ANCHOR_TOP_RIGHT, ANCHOR_TOP_LEFT,
            ANCHOR_BOTTOM_RIGHT, ANCHOR_BOTTOM_LEFT,
            ANCHOR_ABOVE, ANCHOR_BELOW,
        )

        /** 模板里支持的全部占位符（写错时要能提示出来）。 */
        val TEMPLATE_TOKENS = listOf(
            "balance", "recharge", "bonus", "currency", "cost", "costSession",
        )

        /**
         * 从 JSON 解析。**缺省即保持原值**（与 [BubbleAppearance.merge] 同一套语义），
         * 非法值退回原值 —— 永不抛异常。
         *
         * @param o 下发对象。
         * @param base 基线（通常是当前配置）。
         */
        fun merge(o: org.json.JSONObject?, base: BalanceBadge): BalanceBadge {
            if (o == null) return base
            val anchor = o.optString("anchor", "").trim().lowercase()
            return base.copy(
                enabled = if (o.has("enabled")) o.optBoolean("enabled", base.enabled) else base.enabled,
                anchor = if (ANCHORS.contains(anchor)) anchor else base.anchor,
                gapDp = clampInt(o, "gapDp", base.gapDp, -60, 120),
                bg = if (o.has("bg")) o.optString("bg", base.bg).trim() else base.bg,
                fg = if (o.has("fg")) o.optString("fg", base.fg).trim() else base.fg,
                border = if (o.has("border")) o.optString("border", base.border).trim() else base.border,
                borderWidthDp = clampInt(o, "borderWidthDp", base.borderWidthDp, 0, 8),
                radiusDp = clampInt(o, "radiusDp", base.radiusDp, 0, 200),
                fontSizeSp = clampInt(o, "fontSizeSp", base.fontSizeSp, 7, 40),
                paddingHDp = clampInt(o, "paddingHDp", base.paddingHDp, 0, 40),
                paddingVDp = clampInt(o, "paddingVDp", base.paddingVDp, 0, 40),
                maxWidthDp = clampInt(o, "maxWidthDp", base.maxWidthDp, 0, 600),
                template = if (o.has("template")) {
                    normalizeTemplate(o.optString("template", base.template))
                } else base.template,
                showCostFlash = if (o.has("showCostFlash")) o.optBoolean("showCostFlash", base.showCostFlash) else base.showCostFlash,
                hideWhenIdle = if (o.has("hideWhenIdle")) o.optBoolean("hideWhenIdle", base.hideWhenIdle) else base.hideWhenIdle,
                refreshOnTurnEnd = if (o.has("refreshOnTurnEnd")) o.optBoolean("refreshOnTurnEnd", base.refreshOnTurnEnd) else base.refreshOnTurnEnd,
                refreshEverySec = clampInt(o, "refreshEverySec", base.refreshEverySec, 0, 3600),
                refreshOnTap = if (o.has("refreshOnTap")) o.optBoolean("refreshOnTap", base.refreshOnTap) else base.refreshOnTap,
                tapRefreshMinIntervalSec = clampInt(o, "tapRefreshMinIntervalSec", base.tapRefreshMinIntervalSec, 1, 600),
            )
        }

        /** 整数解析并夹紧。与 [BubbleAppearance] 里的同名工具语义一致。 */
        private fun clampInt(o: org.json.JSONObject, k: String, base: Int, lo: Int, hi: Int): Int {
            if (!o.has(k)) return base
            return o.optInt(k, base).coerceIn(lo, hi)
        }

        /**
         * 清洗模板串：**去掉连续重复的同一个 token**，空串退回默认。
         *
         * ## 为什么需要它
         *
         * 真机上出现过 `剩 {balance}{balance}{balance}…（共 12 个）`。成因是设置页
         * 早期那个 chip **点了看不出来有没有生效**（输入框不重渲染），用户以为
         * 没反应就连点了十几下 —— 每一下都真的追加了一次。
         *
         * 界面侧已经改成"已用过的词置灰不可点"从源头挡住，但**存量数据必须在这里
         * 兜一次**：用户手机里那份脏模板不会自己变干净，而它渲染出来是一串
         * `¥138.56¥138.56¥138.56…`，看着像坏了。
         *
         * ⚠️ 只折叠**紧邻**的重复，不做全局去重：`{recharge}…{balance}` 里两个
         *   不同 token 中间隔着文字是合法的，全局去重会破坏用户意图。
         */
        private fun normalizeTemplate(raw: String): String {
            if (raw.isBlank()) return DEFAULT_TEMPLATE
            val re = Regex("\\{([A-Za-z0-9_]+)\\}")
            val sb = StringBuilder()
            var cursor = 0          // 已复制到原文的哪个位置
            var prevTok: String? = null
            for (m in re.findAll(raw)) {
                val between = raw.substring(cursor, m.range.first)
                val tok = m.groupValues[1]
                // ⚠️ `cursor` 必须**无条件**前进到当前匹配之后，哪怕这一项被丢弃。
                //   踩过：只在 append 分支里更新 cursor，被 `continue` 跳过的那次
                //   没推进，于是下一轮 `between` 又把同一段文字算进来 —— 结果一个
                //   重复都没去掉（真机验证：12 个 {balance} 原样保留）。
                cursor = m.range.last + 1
                // 前一个 token 与当前相同，且两者之间没有任何文字 ⇒ 纯重复，跳过
                if (tok == prevTok && between.isEmpty()) continue
                sb.append(between).append(m.value)
                prevTok = tok
            }
            sb.append(raw.substring(cursor))
            val out = sb.toString()
            return if (out.isBlank()) DEFAULT_TEMPLATE else out
        }
    }
}
