package com.dsh.android.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.android.feature.bubble.R
import com.dsh.android.ui.ChatTranscript
import kotlin.math.roundToInt

/** 转录的内容、行缓存和滚动任务。窗口和展开状态由宿主拥有。 */
internal class BubbleTranscriptRenderer(private val context: Context) {
    data class Style(val textColor: Int, val accentColor: Int, val panelWidthPx: Int)

    private var container: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var pendingScroll: Runnable? = null
    private var lastTranscript: ChatTranscript? = null
    var isDirty = true
        private set
    private var transcriptFingerprint: String? = null
    private var renderedRoles: List<String> = emptyList()
    private var style = Style(0xFF1A1D21.toInt(), 0xFF3B6FE0.toInt(), 0)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    fun attach(container: LinearLayout?, scroll: ScrollView?) {
        detach()
        this.container = container
        this.scroll = scroll
    }

    fun submit(transcript: ChatTranscript) {
        lastTranscript = transcript
        isDirty = true
    }

    /** 同时清指纹与 role 缓存，样式改变时必须重新创建行。 */
    fun invalidateStyle() {
        transcriptFingerprint = null
        renderedRoles = emptyList()
        isDirty = true
    }

    fun detach() {
        cancelScroll()
        container = null
        scroll = null
        invalidateStyle()
    }

    private fun cancelScroll() {
        pendingScroll?.let { scroll?.removeCallbacks(it) }
        pendingScroll = null
    }

    /**
     * 把转录渲染成消息行列表。
     *
     * ## 为什么要按指纹跳过
     *
     * 状态通道每 5 秒推一次，转录通道在 DOM 变化时推得更密。如果每次都清空重建
     * 整个 View 树，用户会看到消息在闪、滚动位置也守不住。指纹相同就直接返回。
     *
     * ## 滚动位置
     *
     * 重建后如果用户**本来就在底部**，自动滚到底（流式输出时这是他要的）；
     * 如果他往上翻了在看历史，就**不要**动 —— 强行拽回底部是最恼人的行为。
     */
    fun render(style: Style) {
        if (this.style != style) {
            this.style = style
            invalidateStyle()
        }
        val container = container ?: return
        val t = lastTranscript ?: return
        isDirty = false

        val fp = t.items.joinToString("\u0001") { "${it.role}|${it.running}|${it.text}" }
        if (fp == transcriptFingerprint) return
        transcriptFingerprint = fp

        val scroll = scroll
        // 判定"重建前是否贴着底部"，重建后再决定要不要跟到底
        val wasAtBottom = scroll?.let { s ->
            val child = s.getChildAt(0)
            child == null || s.scrollY + s.height >= child.height - dp(24)
        } ?: true

        // ── 增量更新 ──
        //
        // 流式输出期间这条路径每秒要走好几次（页面侧节流 250ms），全量
        // `removeAllViews()` + 重建会让整块内容闪一下，正好把"流式"的观感毁掉。
        // 所以：**前缀 role 序列一致就复用已有 view，只改文本**；role 序列变了
        // （用户插话、条目整体前移）才退回全量重建。
        //
        // 复用之所以安全：同一个 role ⇒ 同一套样式（对齐 / 底色 / 字号），
        // 复用时不需要也不应该重设样式。
        val reuseCount = if (
            t.items.isNotEmpty() &&
            container.childCount == renderedRoles.size &&
            renderedRoles.size <= t.items.size &&
            t.items.take(renderedRoles.size).map { it.role } == renderedRoles
        ) renderedRoles.size else -1

        if (reuseCount < 0) {
            container.removeAllViews()
            renderedRoles = emptyList()
        }

        val inflater = LayoutInflater.from(context)
        for (i in t.items.indices) {
            val item = t.items[i]
            if (i < reuseCount) {
                updateItemText(container.getChildAt(i), item)
            } else {
                container.addView(renderItem(inflater, item))
            }
        }
        if (t.items.isEmpty() && container.childCount == 0) {
            container.addView(
                makeEmptyHint(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        renderedRoles = t.items.map { it.role }

        if (wasAtBottom) {
            cancelScroll()
            if (scroll != null) {
                val task = Runnable {
                    pendingScroll = null
                    if (this.scroll === scroll) scroll.fullScroll(View.FOCUS_DOWN)
                }
                pendingScroll = task
                scroll.post(task)
            }
        }
    }

    /**
     * 复用已有行时只更新文本（见 [renderTranscript] 的增量分支）。
     *
     * ⚠️ 标签走 [roleLabel]，与 [renderItem] 同源 —— 两处各写一份的话，
     *   改文案时必然漂移成"新建的行和复用的行长得不一样"。
     */
    private fun updateItemText(row: View, item: ChatTranscript.Item) {
        val roleView = row.findViewById<TextView>(R.id.msgRole) ?: return
        val bodyView = row.findViewById<TextView>(R.id.msgBody) ?: return
        if (bodyView.text?.toString() != item.text) bodyView.text = item.text
        val label = roleLabel(item)
        if (roleView.text?.toString() != label) roleView.text = label
    }

    /** 一条消息的角色标签。用户消息不显示（蓝色气泡本身就是标志）。 */
    private fun roleLabel(item: ChatTranscript.Item): String = when (item.role) {
        ChatTranscript.Role.TOOL -> if (item.running) "工具调用 · 进行中" else "工具调用"
        ChatTranscript.Role.ERROR -> "错误"
        ChatTranscript.Role.REASONING -> if (item.running) "AI 思考 · 进行中" else "AI 思考"
        ChatTranscript.Role.ASSISTANT -> if (item.running) "AI · 正在输出" else "AI"
        else -> item.role
    }

    /** 转录为空时的提示。空面板会让人以为界面坏了。 */
    private fun makeEmptyHint(): TextView = TextView(context).apply {
        text = "（这个会话还没有内容）"
        textSize = 12f
        setTextColor(style.textColor and 0x00FFFFFF or 0x66000000)
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
    }

    /**
     * 渲染一条消息。
     *
     * 五种角色的排版：
     *
     * | role | 对齐 | 底色 | 文字 | 角色标签 |
     * |---|---|---|---|---|
     * | user | 右 | 强调色 | 白 | 不显示（蓝泡本身就是标志） |
     * | assistant | 左 | 白 | 深 | 「AI」 |
     * | tool | 左 | 浅灰 | 深灰 | 「工具调用」+ 运行中标记 |
     * | error | 左 | 浅红 | 深红 | 「错误」 |
     * | other | 左 | 白 | 深 | 原始 role |
     *
     * ⚠️ 左右对齐靠**行容器 gravity**，不是 margin：两者共用同一个 XML 模板，
     *   只有 gravity 与背景不同。
     * ⚠️ 正文 **不设 maxLines**：这里就是要看完整内容，行数多靠外层 ScrollView 滚。
     */
    private fun renderItem(inflater: LayoutInflater, item: ChatTranscript.Item): View {
        val row = inflater.inflate(R.layout.bubble_msg, container, false) as LinearLayout
        val roleView = row.findViewById<TextView>(R.id.msgRole)
        val bodyView = row.findViewById<TextView>(R.id.msgBody)

        val accent = style.accentColor
        val textColor = style.textColor

        // 气泡宽度上限：面板宽度的 84%，留出左右各自的边距 —— 否则用户消息
        // （右对齐）会一直顶到面板最左边，跟助手消息糊在一起分不清。
        val maxBubble = (style.panelWidthPx * 0.84f).roundToInt()

        when (item.role) {
            ChatTranscript.Role.USER -> {
                row.gravity = Gravity.END
                roleView.visibility = View.GONE
                bodyView.background = bubbleShape(accent, dp(14))
                bodyView.setTextColor(Color.WHITE)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.TOOL -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFEDEFF2.toInt(), dp(10))
                bodyView.setTextColor(0xFF3A4048.toInt())
                bodyView.maxWidth = maxBubble
                bodyView.textSize = 11.5f
                bodyView.text = item.text
            }

            ChatTranscript.Role.ERROR -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFFE5484D.toInt())
                bodyView.background = bubbleShape(0xFFFDECEC.toInt(), dp(10))
                bodyView.setTextColor(0xFF8A1F22.toInt())
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.ASSISTANT -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFFFFFFF.toInt(), dp(12))
                bodyView.setTextColor(textColor)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.REASONING -> {
                // 思考：灰底 + 深灰字 + 小一号，与正文（白底深字）一眼可分。
                // 不隐藏 —— 用户明确要求「微博里能看到的回复内容全部都要有」。
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFF2F3F5.toInt(), dp(10))
                bodyView.setTextColor(0xFF5A6068.toInt())
                bodyView.textSize = 12f
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            else -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = item.role
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFFFFFFF.toInt(), dp(12))
                bodyView.setTextColor(textColor)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }
        }
        return row
    }

    /** 消息气泡的底：纯色 + 圆角。 */
    private fun bubbleShape(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
    }

}
