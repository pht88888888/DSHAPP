package com.dsh.android.ui
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.dsh.android.bubble.FloatingBubble
import com.dsh.android.R

/**
 * 余额徽标：贴在悬浮球旁边、由 [BalanceBadge] 完全控制外观的一个小 TextView。
 *
 * ## 为什么用 TextView 而不是自绘
 *
 * 徽标要显示的是**用户/模板决定的文字**（`¥12.34`、`本次 ¥0.03`、`剩 ¥8.20 +3`…），
 * 自绘要自己排版、截断、字体度量；TextView 这些都免费且与系统一致。
 * 真正的"设计自由度"来自 [BalanceBadge] 那 19 个旋钮，不来自要不要自绘。
 *
 * ## 与球的父子关系：**平级，不是子 View**
 *
 * 悬浮球是一个独立窗口（`TYPE_APPLICATION_OVERLAY`），它的尺寸由 `layoutParams`
 * 决定。把徽标塞进球里会让球被迫变大 —— 那会连带改变拖拽边界、点击热区、
 * 抛掷落点，是牵一发动全身的改动。
 *
 * 所以徽标**自己是一个窗口**，位置由 [applyAnchor] 按球的实时位置算。代价是要跟着
 * 球一起移动（球拖动/飞行时逐帧同步），这由 FloatingBubble 负责。
 */
class BalanceBadgeView(context: Context) : TextView(context) {

    init {
        // 单行 + 末尾省略：模板可能很长（`剩 ¥8.20 · 本次 ¥0.03`），
        // 换行会让徽标变成一块方块，比截断难看得多。
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        // 徽标是纯展示层：**不能吃掉触摸**，否则用户想拖球时会点到徽标上。
        // 这一条是硬约束 —— 悬浮窗里任何可见 View 默认都会接收触摸。
        isClickable = false
        isFocusable = false
        setPadding(0, 0, 0, 0)
    }

    /** 当前用于判重的文字（避免同文案反复 setText 触发重排）。 */
    private var lastText = ""

    /**
     * 按配置刷新外观。
     *
     * @param badge 徽标配置。
     * @param text 要显示的文字；空串时**隐藏自己**（`View.GONE`，不占位）。
     */
    fun render(badge: BalanceBadge, text: String) {
        if (!badge.enabled || text.isEmpty()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

        if (text != lastText) {
            lastText = text
            this.text = text
        }

        setTextSize(TypedValue.COMPLEX_UNIT_SP, badge.fontSizeSp.toFloat())
        setPadding(
            dp(badge.paddingHDp),
            dp(badge.paddingVDp),
            dp(badge.paddingHDp),
            dp(badge.paddingVDp),
        )
        if (badge.maxWidthDp > 0) {
            maxWidth = dp(badge.maxWidthDp)
        } else {
            maxWidth = Int.MAX_VALUE
        }

        // 文字色：空串 = 跟随主题。
        //
        // ⚠️ 底色透明时不能再用"系统主文字色"（浅色主题下是**黑**）——
        //   黑字压在深色壁纸上等于看不见。透明底默认用**白字 + 深色阴影**，
        //   这是所有"浮在桌面上的小挂件"的通用做法。
        val transparentBg = parseColor(badge.bg) == null
        val fg = parseColor(badge.fg) ?: if (transparentBg) {
            Color.WHITE
        } else {
            resolveThemeColor(android.R.attr.textColorPrimary, Color.BLACK)
        }
        setTextColor(fg)

        background = GradientDrawable().apply {
            cornerRadius = dp(badge.radiusDp).toFloat()
            // ## 背景：空串 = **透明**
            //
            // 用户反复要求（原话：「所以为什么设置是透明 实际是白色，说了多少次了」）。
            // 这里原来留空会回退到主题卡片色 —— 真机上就是**一块白底黑字**，
            // 而设置页里那一行显示的却是「跟随主题」，用户根本找不到哪里能改。
            //
            // 现在留空 = 全透明，只留文字本身浮在桌面上。可读性由下面那段
            // 描边阴影保底，不再靠一块不透明的板子。
            val bgColor = parseColor(badge.bg) ?: Color.TRANSPARENT
            setColor(bgColor)

            // 底色透明时补一圈淡淡的白色描边：深色壁纸上没有它，白字会糊成一片。
            // 用户**显式配了描边**就按用户的来，不抢。
            val bw = dp(badge.borderWidthDp)
            if (bw > 0) {
                // 描边色留空时**不画描边**（不是"跟随主题"）——
                // 见 BalanceBadge.border 的注释：无描边本身是合理设计。
                val bc = parseColor(badge.border)
                if (bc != null) setStroke(bw, bc)
            } else if (parseColor(badge.bg) == null) {
                // 只有"用户没指定底色"时才兜这一圈，显式选了颜色的不受影响。
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        // 透明底下的可读性保底：给文字加一层柔和描边阴影。
        // ⚠️ 有底色时必须清掉，否则文字边缘会脏。
        if (parseColor(badge.bg) == null) {
            setShadowLayer(3f, 0f, 0f, 0xCC000000.toInt())
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    /**
     * 花费变化时的"跳一下"。
     *
     * ⚠️ 用 `animate()` 而不是 `ObjectAnimator`：这个 View 挂在悬浮窗里、
     *   生命周期跟着球走，`animate()` 会随 View detach 自动取消，不用手动清理。
     */
    fun flash() {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        animate()
            .scaleX(1.18f).scaleY(1.18f).setDuration(90)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            .start()
    }

    /**
     * 把徽标摆到球的旁边。
     *
     * @param bubbleX 球窗口左边（px）
     * @param bubbleY 球窗口上边（px）
     * @param bubbleW 球宽（px）
     * @param bubbleH 球高（px）
     * @param badgeW 徽标宽（px，测量后）
     * @param badgeH 徽标高（px）
     * @param screenW 屏幕宽（px）
     * @param screenH 屏幕高（px）
     * @return 徽标窗口左上角坐标（px）。已做屏幕内夹紧。
     */
    fun applyAnchor(
        badge: BalanceBadge,
        bubbleX: Int, bubbleY: Int, bubbleW: Int, bubbleH: Int,
        badgeW: Int, badgeH: Int,
        screenW: Int, screenH: Int,
    ): Pair<Int, Int> {
        val gap = dp(badge.gapDp)
        var x: Int
        var y: Int
        when (badge.anchor) {
            BalanceBadge.ANCHOR_TOP_LEFT -> {
                x = bubbleX - badgeW - gap
                y = bubbleY
            }
            BalanceBadge.ANCHOR_TOP_RIGHT -> {
                x = bubbleX + bubbleW + gap
                y = bubbleY
            }
            BalanceBadge.ANCHOR_BOTTOM_LEFT -> {
                x = bubbleX - badgeW - gap
                y = bubbleY + bubbleH - badgeH
            }
            BalanceBadge.ANCHOR_BOTTOM_RIGHT -> {
                x = bubbleX + bubbleW + gap
                y = bubbleY + bubbleH - badgeH
            }
            BalanceBadge.ANCHOR_ABOVE -> {
                // 水平居中在球上
                x = bubbleX + (bubbleW - badgeW) / 2
                y = bubbleY - badgeH - gap
            }
            BalanceBadge.ANCHOR_BELOW -> {
                x = bubbleX + (bubbleW - badgeW) / 2
                y = bubbleY + bubbleH + gap
            }
            else -> {
                x = bubbleX + bubbleW + gap
                y = bubbleY
            }
        }

        // 左右越界翻转：球贴着右边缘时，"右上"会跑到屏幕外 —— 那种情况下
        // 徽标应该自动翻到左边，而不是被夹在边缘、压在球上。
        if (x + badgeW > screenW) {
            val flipped = bubbleX - badgeW - gap
            if (flipped >= 0) x = flipped else x = (screenW - badgeW).coerceAtLeast(0)
        }
        if (x < 0) x = 0

        // 上下越界同理。
        if (y + badgeH > screenH) {
            val up = bubbleY - badgeH - gap
            y = if (up >= 0) up else (screenH - badgeH).coerceAtLeast(0)
        }
        if (y < 0) y = 0

        return x to y
    }

    /** dp → px。 */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 解析颜色。
     *
     * @returns `null` 表示"没配"（空串或解析失败），调用方据此走主题兜底。
     */
    private fun parseColor(raw: String): Int? {
        if (raw.isBlank()) return null
        return runCatching { Color.parseColor(raw.trim()) }.getOrNull()
    }

    /**
     * 取主题色。取不到时用 [fallback]。
     *
     * ⚠️ 悬浮窗的 Context **不带 Activity 主题**（是 Application context 或
     *   一个 `ContextThemeWrapper`）。直接 `obtainStyledAttributes` 常常拿到
     *   全 0 —— 那样徽标会变成透明背景 + 黑色文字，在深色模式下直接看不见。
     *   所以这里必须给可见的兜底值。
     */
    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        return runCatching {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, fallback)
            ta.recycle()
            if (c == 0) fallback else c
        }.getOrDefault(fallback)
    }
}
