package com.dsh.android.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 截图标注画布 —— 「圈出要改的地方，直接问 AI」。
 *
 * ## 它解决什么
 *
 * 用户看到网页上某处不对，原本的流程是：手机截图 → 进相册 → 编辑 → 手绘圈 →
 * 保存 → 回 DSH → 从相册选图 → 打字提问 → 发送。七八步，其中一半是
 * 系统相册和文件选择器的来回。
 *
 * 这里把整条链压成一步：点「标注」→ 截图定格 → 圈 → 打字 → 发送。
 *
 * ## 为什么是「定格截图」而不是「浮在网页上实时画」
 *
 * 因为网页是活的：它会被惯性滚动、会自己重排、会弹广告。浮在上面画到一半，
 * 页面动一下，圈就套在别的地方了 —— 而且触摸事件还要和网页自己的手势
 * （滚动、缩放、长按选词）抢，抢输抢赢都不对。
 *
 * 定格之后画布是纯静态位图：所见即所得，画错了撤销，不受页面影响。
 *
 * ## 坐标约定
 *
 * 画布内一切尺寸用 **dp**（手绘笔迹在不同屏幕上观感一致），落盘时才按
 * 截图原始像素缩放合成。见 [exportBitmap]。
 */
@SuppressLint("ViewConstructor")
class AnnotateView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "Annotate"

        /** 笔迹颜色。红黄蓝绿白黑 —— 覆盖深浅两种页面背景。 */
        private val COLORS = intArrayOf(
            0xFFE5484D.toInt(), // 红：最常用，指「这里有问题」
            0xFFF5A524.toInt(), // 橙黄：在浅色页面上比纯黄看得清
            0xFF30A46C.toInt(), // 绿：表示「这里是对的」
            0xFF0090FF.toInt(), // 蓝：中性标注
            0xFFFFFFFF.toInt(), // 白：深色页面
            0xFF1A1A1A.toInt(), // 近黑：浅色页面
        )

        /** 笔画粗细档位（dp）。 */
        private val WIDTHS = floatArrayOf(2.5f, 5f, 9f)
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    /** 当前工具。 */
    private enum class Tool { Pen, Rect, Arrow, Text, Mosaic }

    private var tool = Tool.Pen
    private var color = COLORS[0]
    private var strokeDp = WIDTHS[1]

    // ── 数据 ────────────────────────────────────────────────────────────

    /** 一笔。`points` 是画布 dp 坐标序列；矩形/箭头只用首尾两点。 */
    private class Stroke(
        val tool: Tool,
        val color: Int,
        val widthDp: Float,
        val points: MutableList<Pair<Float, Float>> = mutableListOf(),
        /** 文字工具的内容（其余工具为 null）。 */
        val text: String? = null,
    )

    /** 已完成的笔画，按绘制顺序。撤销就是从这里弹。 */
    private val strokes = mutableListOf<Stroke>()

    /** 重做栈。任何新笔画都会清空它（标准撤销语义）。 */
    private val redoStack = mutableListOf<Stroke>()

    /** 正在画的那一笔（手指还没抬起）。 */
    private var drawing: Stroke? = null

    /** 底图：浏览器截图。 */
    private var base: Bitmap? = null

    // ── 视图 ────────────────────────────────────────────────────────────

    private lateinit var inkView: InkView
    private lateinit var toolRow: LinearLayout
    private lateinit var colorRow: LinearLayout
    private lateinit var widthRow: LinearLayout
    private lateinit var inputBar: EditText
    private lateinit var sendBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var palette: AiBrowserView.Palette

    /** 中间那块画布容器（顶栏和底栏各占自己的空间，不覆盖它）。 */
    private lateinit var canvasHost: FrameLayout

    /** 顶部悬浮胶囊容器（取消/撤销/重做）。 */
    private lateinit var toolBarBg: LinearLayout

    /** 输入条背景容器。 */
    private lateinit var inputBarBg: LinearLayout

    /** 「当前颜色」圆点按钮：点开样式气泡。 */
    private lateinit var colorDotBtn: FrameLayout

    /** 圆点里那层实心色（换颜色时重画它）。 */
    private lateinit var colorDotFill: View

    /** 样式气泡：颜色 + 粗细。 */
    private lateinit var styleBubble: LinearLayout

    /**
     * 尺寸**精确等于图片显示矩形**的容器。
     *
     * 它是「笔迹坐标能对上图片」的关键：笔迹层铺满本容器，容器又刚好
     * 等于图片在屏幕上的那块矩形，所以一个笔迹坐标 ↔ 一个图片像素，
     * 中间没有留白偏移。导出时只需按 `底图尺寸 / 本容器尺寸` 缩放。
     */
    private lateinit var imageBox: ImageBox

    /** 画布滚动容器。截图比可视区高时靠它上下看。 */
    private lateinit var canvasScroll: ScrollView

    /** 顶部控制带。 */
    private lateinit var toolbar: LinearLayout

    /** 底部控制带。 */
    private lateinit var toolbarBottom: LinearLayout

    private var dark = true

    // ── 对外回调 ────────────────────────────────────────────────────────

    /** 用户取消标注。宿主应丢弃本视图、把浏览器恢复出来。 */
    var onCancel: (() -> Unit)? = null

    /**
     * 用户点了发送。
     *
     * @param png 合成好的图片（底图 + 笔迹），**已是原始截图分辨率**。
     * @param question 输入框里的文字（可能为空串）。
     */
    var onSend: ((png: File, question: String) -> Unit)? = null

    init {
        palette = if (isDarkNow()) AiBrowserView.DARK_PALETTE else AiBrowserView.LIGHT_PALETTE
        setBackgroundColor(0xF2000000.toInt())
        build()
    }

    /** 跟随 DSH 主题。宿主在面板上报矩形时会调用。 */
    fun applyTheme(isDark: Boolean) {
        if (isDark == dark) return
        dark = isDark
        palette = if (isDark) AiBrowserView.DARK_PALETTE else AiBrowserView.LIGHT_PALETTE
        applyPalette()
    }

    private fun isDarkNow(): Boolean = true

    // ══════════════════════════════════════════════════════════════════
    // 构建
    // ══════════════════════════════════════════════════════════════════

    private fun build() {
        // ══ 画布满屏 + 悬浮胶囊控制 ═════════════════════════════════════
        //
        // 交互取向（v59 重做）：**画面优先，控件让路**。
        //
        // 旧版是「顶栏 / 画布 / 底栏」三段式纵向排布，好处是控件绝不遮挡
        // 截图；代价是画布被上下两条灰带挤掉一大块，加上工具、颜色、粗细、
        // 输入四行平铺，整屏看起来像个后台管理面板 —— 用户的原话是
        // 「打开画笔之后的界面太丑了……弄成那种弹出气泡的形式不挺好的吗」。
        //
        // 现在改成：截图满屏铺开（1:1 不缩放），控件全部做成**悬浮胶囊**，
        // 压在画面边缘。颜色与粗细不再平铺成两行，收进「当前颜色圆点」点开
        // 的弹出气泡里 —— 不点开时只占一个 34dp 的圆点。
        //
        // ⚠️ 悬浮必然遮挡，所以：顶部胶囊只占右上角、底部胶囊只占最下面
        //   一条，且都做了半透明 + 圆角，中间整块画面**始终无遮挡**，
        //   圈选、看内容都不受影响。导出时只合成底图与笔迹，控件不参与。

        // ── 画布：满屏，1:1 不缩放，超出上下滚动 ───────────────────────
        canvasHost = FrameLayout(context).apply {
            setBackgroundColor(0xFF0B0D10.toInt())
        }
        // ⚠️ **不用 ImageView**：底图和笔迹必须是同一个 View 的两层绘制，
        //    两个兄弟 View 只要矩形有一点差异（测量顺序、layout 时机）就会
        //    错位 —— 用户看到的正是「对话/工作标签下半截被切掉」。
        inkView = InkView(context)
        imageBox = ImageBox(context).apply {
            addView(inkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        canvasScroll = ScrollView(context).apply {
            // ⚠️ 必须 false：为 true 时内容比视口矮会把子 View 拉伸填满，
            //    截图就被竖直压扁 —— 用户说的「标签被压缩」就是这个。
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(imageBox, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        canvasScroll.setOnTouchListener { _, _ -> false }
        inkView.panner = canvasScroll
        canvasHost.addView(canvasScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(canvasHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── 顶部悬浮胶囊：取消 / 撤销 / 重做 ───────────────────────────
        //
        // 四个小圆钮并成**一个**胶囊而不是四个散点：散点看起来像弹窗按钮
        // 堆在画面上，一个胶囊更像工具栏，也更省遮挡面积。
        val topPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        toolbar = topPill
        val cancelBtn = glyphButton("✕") { onCancel?.invoke() }
        undoBtn = glyphButton("↶") { undo() }
        redoBtn = glyphButton("↷") { redo() }
        topPill.addView(cancelBtn, glyphLp())
        topPill.addView(undoBtn, glyphLp())
        topPill.addView(redoBtn, glyphLp())
        addView(
            topPill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(12)
                rightMargin = dp(12)
            },
        )

        // ── 底部悬浮区（自下而上叠：输入条 / 工具胶囊）──────────────────
        //
        // ⚠️ 用一个纵向 LinearLayout 从底部堆，而不是各自算 bottomMargin：
        //    输入条会随键盘弹起而改变高度，手算偏移量必然错位。
        val bottomDock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        toolbarBottom = bottomDock

        // 工具胶囊行：五个工具 + 当前颜色圆点（点开是弹出气泡）
        val toolPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        toolBarBg = toolPill
        toolRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((t, label) in listOf(
            Tool.Pen to "✎",
            Tool.Rect to "▢",
            Tool.Arrow to "↗",
            Tool.Text to "T",
            Tool.Mosaic to "▨",
        )) {
            val b = toolButton(label, t)
            toolRow.addView(b, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(2) })
        }
        toolPill.addView(toolRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // 分隔线：把「工具」和「样式」视觉上分开，但只占 1px。
        toolPill.addView(View(context).apply {
            setBackgroundColor(palette.stroke)
        }, LinearLayout.LayoutParams(dp(1), dp(20)).apply { leftMargin = dp(4); rightMargin = dp(6) })
        // 当前颜色圆点：点开弹出气泡（颜色 + 粗细都在里面）
        colorDotBtn = FrameLayout(context).apply {
            setOnClickListener { toggleStyleBubble() }
        }
        colorDotFill = View(context).apply { background = circle(color, false) }
        colorDotBtn.addView(colorDotFill, LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER })
        toolPill.addView(colorDotBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(2) })
        bottomDock.addView(
            toolPill,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            },
        )

        // 输入条：输入框 + 发送，合成一条宽胶囊
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        inputBarBg = inputRow
        inputBar = EditText(context).apply {
            hint = "想问什么？"
            setTextColor(palette.text)
            setHintTextColor(palette.textDim)
            textSize = 14f
            maxLines = 3
            isSingleLine = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            setPadding(dp(12), dp(9), dp(8), dp(9))
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                if (enter) { doSend(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refreshSend()
            })
        }
        inputRow.addView(
            inputBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        sendBtn = TextView(context).apply {
            text = "↑"
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { doSend() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) })
        bottomDock.addView(
            inputRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(12)
            },
        )
        addView(
            bottomDock,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )

        // ── 样式气泡：颜色 + 粗细（默认隐藏，点颜色圆点才弹）──────────
        styleBubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (c in COLORS) {
            val dot = View(context).apply {
                background = circle(c, c == color)
                setOnClickListener { color = c; refreshColors() }
            }
            colorRow.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(9) })
        }
        styleBubble.addView(colorRow)
        // 粗细：三条不同粗细的短横线，比三个圆点更直观
        widthRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        for (w in WIDTHS) {
            val b = widthButton(w)
            widthRow.addView(b, LinearLayout.LayoutParams(dp(46), dp(30)).apply { rightMargin = dp(6) })
        }
        styleBubble.addView(widthRow)
        addView(
            styleBubble,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(20)
                bottomMargin = dp(74)
            },
        )

        applyPalette()
        refreshTools()
        refreshColors()
        refreshSend()
    }
    // ══════════════════════════════════════════════════════════════════
    // 主题
    // ══════════════════════════════════════════════════════════════════

    private fun applyPalette() {
        // 悬浮胶囊：**半透明磨砂感**，压在画面上也不刺眼。
        // 用 0xEE 而不是全不透明：全不透明会像贴上去的色块，太生硬。
        canvasHost.setBackgroundColor(0xFF0B0D10.toInt())

        toolbar.background = floatPill(if (dark) 0xEE1C1F25.toInt() else 0xEEFFFFFF.toInt())
        toolBarBg.background = floatPill(if (dark) 0xEE1C1F25.toInt() else 0xEEFFFFFF.toInt())
        inputBarBg.background = floatPill(if (dark) 0xEE1C1F25.toInt() else 0xEEFFFFFF.toInt())
        styleBubble.background = floatPill(if (dark) 0xF21C1F25.toInt() else 0xF2FFFFFF.toInt())

        inputBar.setTextColor(palette.text)
        inputBar.setHintTextColor(palette.textDim)

        for (b in listOf(undoBtn, redoBtn)) {
            b.setTextColor(palette.text)
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
            }
        }
        sendBtn.setTextColor(0xFFFFFFFF.toInt())
        sendBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accent)
        }
        refreshTools()
        refreshColors()
    }

    /** 悬浮胶囊底：圆角足够大 ⇒ 药丸形，带 1px 边让它在浅色页面上也立得住。 */
    private fun floatPill(bg: Int): GradientDrawable = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (dark) 0x33FFFFFF else 0x14000000)
    }

    // ══════════════════════════════════════════════════════════════════
    // 小部件
    // ══════════════════════════════════════════════════════════════════

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun space(): View = View(context).also {
        it.layoutParams = LinearLayout.LayoutParams(dp(6), 1)
    }

    /** 悬浮胶囊上的圆形图标按钮。 */
    private fun glyphButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            setOnClickListener { onClick() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
            }
        }

    private fun glyphLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(2) }

    /** 开/关样式气泡（颜色 + 粗细）。 */
    private fun toggleStyleBubble() {
        if (!::styleBubble.isInitialized) return
        val show = styleBubble.visibility != View.VISIBLE
        styleBubble.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            styleBubble.alpha = 0f
            styleBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120L).start()
            styleBubble.scaleX = 0.9f
            styleBubble.scaleY = 0.9f
        }
    }

    private fun pillButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pillBg()
            setOnClickListener { onClick() }
        }

    private fun pillBg(): GradientDrawable = GradientDrawable().apply {
        setColor(palette.field)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1).coerceAtLeast(1), palette.stroke)
    }

    private fun fieldBg(): GradientDrawable = GradientDrawable().apply {
        setColor(palette.field)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1).coerceAtLeast(1), palette.stroke)
    }

    private fun circle(c: Int, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(c)
        if (selected) setStroke(dp(3).coerceAtLeast(1), if (dark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
        else setStroke(dp(1).coerceAtLeast(1), palette.stroke)
    }

    private fun toolButton(label: String, t: Tool): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
            tag = t
            setOnClickListener { tool = t; refreshTools() }
        }

    /**
     * 粗细档按钮：**外框是选中指示，里面那个实心圆才是真实粗细**。
     *
     * 原来用「同一个 OVAL 背景 + 不同内边距」想模拟粗细，但内边距只影响
     * 内容区，背景仍然画满整个控件 —— 结果三档看起来一样粗，反而把
     * 控件撑得互相重叠。现在用两层 drawable：外层描边做选中态，内层
     * 圆点直径 = 笔画粗细 × 2.2，视觉上直接对应画出来的线。
     */
    /**
     * 粗细档按钮：画一条**真实粗细的短横线**。
     *
     * 圆点看不出「画出来会有多粗」，横线一眼就是笔迹本身的样子。
     */
    private fun widthButton(w: Float): FrameLayout =
        FrameLayout(context).apply {
            tag = w
            setOnClickListener { strokeDp = w; refreshColors() }
            val line = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(if (dark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
                    cornerRadius = dp(w).toFloat()
                }
            }
            addView(
                line,
                LayoutParams(dp(26), dp(w).toInt().coerceAtLeast(dp(2))).apply { gravity = Gravity.CENTER },
            )
        }

    private fun refreshTools() {
        for (i in 0 until toolRow.childCount) {
            val v = toolRow.getChildAt(i)
            if (v !is TextView || v.tag !is Tool) continue
            val selected = v.tag == tool
            // 选中态：实心强调色圆片；未选中：全透明，只留文字 ——
            // 五个方块填满会显得很吵，留白更像「胶囊里一排图标」。
            v.setTextColor(if (selected) 0xFFFFFFFF.toInt() else palette.text)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected) palette.accent else 0x00000000)
            }
        }
        // 文字工具才需要软键盘；其他工具收起，免得挡住画布。
        if (tool != Tool.Text) hideIme()
    }

    private fun refreshColors() {
        // 底部工具条上那个「当前颜色」圆点
        if (::colorDotFill.isInitialized) colorDotFill.background = circle(color, false)
        // 气泡里的颜色圆点：按顺序重画选中态
        for (i in 0 until colorRow.childCount) {
            val v = colorRow.getChildAt(i) ?: continue
            val c = COLORS.getOrNull(i) ?: continue
            v.background = circle(c, c == color)
        }
        // 粗细档：选中那个画一圈强调色描边
        for (j in 0 until widthRow.childCount) {
            val wb = widthRow.getChildAt(j) as? FrameLayout ?: continue
            val w = wb.tag as? Float ?: continue
            val sel = abs(w - strokeDp) < 0.01f
            wb.background = GradientDrawable().apply {
                setColor(if (sel) (if (dark) 0x22FFFFFF else 0x0F000000) else 0x00000000)
                cornerRadius = dp(9).toFloat()
            }
        }
    }

    private fun refreshSend() {
        val ok = strokes.isNotEmpty() || inputBar.text.toString().trim().isNotEmpty()
        sendBtn.isEnabled = ok
        sendBtn.alpha = if (ok) 1f else 0.45f
    }

    private fun hideIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ══════════════════════════════════════════════════════════════════
    // 装载截图
    // ══════════════════════════════════════════════════════════════════

    /** 装入底图。必须在 [show] 之前调用。 */
    fun setBase(bmp: Bitmap) {
        base = bmp
        // 底图换了 ⇒ ImageBox 的尺寸要重算（它按底图宽高比量自己）。
        imageBox.requestLayout()
        strokes.clear()
        redoStack.clear()
        inkView.invalidate()
        refreshSend()
    }

    // ══════════════════════════════════════════════════════════════════
    // 撤销 / 重做
    // ══════════════════════════════════════════════════════════════════

    private fun undo() {
        val s = strokes.removeLastOrNull() ?: return
        redoStack.add(s)
        inkView.invalidate()
        refreshSend()
    }

    private fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        strokes.add(s)
        inkView.invalidate()
        refreshSend()
    }

    // ══════════════════════════════════════════════════════════════════
    // 发送
    // ══════════════════════════════════════════════════════════════════

    private fun doSend() {
        val question = inputBar.text.toString().trim()
        if (strokes.isEmpty() && question.isEmpty()) return
        hideIme()
        val png = exportBitmap()
        if (png == null) {
            Log.w(TAG, "导出图片失败")
            return
        }
        onSend?.invoke(png, question)
    }

    /**
     * 把底图 + 笔迹合成为一张 PNG 并落盘。
     *
     * 关键：画布上的坐标是**显示尺寸**，底图可能是更高分辨率的原始截图。
     * 直接按显示尺寸合成会丢掉清晰度（AI 看圈内小字会糊），所以这里按
     * `base.width / inkView.width` 的比例把笔迹放大到原始分辨率。
     */
    private fun exportBitmap(): File? {
        val bmp = base ?: return null
        return try {
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            // 基准是 imageBox（== 图片显示矩形 == 笔迹层尺寸），不是 canvasHost：
            // canvasHost 含四周留白，拿它当基准会让笔迹整体偏移。
            val sx = if (imageBox.width > 0) bmp.width.toFloat() / imageBox.width else 1f
            val sy = if (imageBox.height > 0) bmp.height.toFloat() / imageBox.height else 1f
            canvas.save()
            canvas.scale(sx, sy)
            drawStrokes(canvas, strokes)
            canvas.restore()

            val dir = File(context.filesDir, "browser-shots").apply { if (!exists()) mkdirs() }
            val f = File(dir, "mark-${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
            Log.i(TAG, "标注图已保存: ${f.absolutePath} (${bmp.width}x${bmp.height})")
            f
        } catch (t: Throwable) {
            Log.w(TAG, "导出失败: ${t.message}")
            null
        }
    }

    /** 把一组笔画画到 canvas 上（画布坐标系 = inkView 的 dp 坐标）。 */
    private fun drawStrokes(canvas: Canvas, list: List<Stroke>) {
        for (s in list) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                style = Paint.Style.STROKE
                strokeWidth = dp(s.widthDp)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            when (s.tool) {
                Tool.Pen -> {
                    if (s.points.size == 1) {
                        val (x, y) = s.points[0]
                        canvas.drawCircle(x, y, dp(s.widthDp) / 2f, p.apply { style = Paint.Style.FILL })
                    } else {
                        val path = Path()
                        val (x0, y0) = s.points[0]
                        path.moveTo(x0, y0)
                        for (i in 1 until s.points.size) {
                            val (x, y) = s.points[i]
                            path.lineTo(x, y)
                        }
                        canvas.drawPath(path, p)
                    }
                }
                Tool.Rect -> {
                    if (s.points.size >= 2) {
                        val (x0, y0) = s.points.first()
                        val (x1, y1) = s.points.last()
                        canvas.drawRoundRect(
                            RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)),
                            dp(4f), dp(4f), p,
                        )
                    }
                }
                Tool.Arrow -> {
                    if (s.points.size >= 2) drawArrow(canvas, s, p)
                }
                Tool.Text -> {
                    s.text?.let { txt ->
                        if (s.points.isNotEmpty()) {
                            val (x, y) = s.points[0]
                            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = s.color
                                textSize = dp(16f)
                                style = Paint.Style.FILL
                            }
                            // 文字带深色描边，保证在任何背景上都读得出
                            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = 0xCC000000.toInt()
                                style = Paint.Style.FILL
                            }
                            val w = tp.measureText(txt)
                            canvas.drawRoundRect(
                                RectF(x - dp(4f), y - dp(18f), x + w + dp(4f), y + dp(6f)),
                                dp(4f), dp(4f), bg,
                            )
                            canvas.drawText(txt, x, y, tp)
                        }
                    }
                }
                Tool.Mosaic -> drawMosaic(canvas, s)
            }
        }
    }

    private fun drawArrow(canvas: Canvas, s: Stroke, p: Paint) {
        val (x0, y0) = s.points.first()
        val (x1, y1) = s.points.last()
        canvas.drawLine(x0, y0, x1, y1, p)
        // 箭头两翼：长度随线宽走，太短看不出来，太长像乱线
        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val len = dp(12f) + dp(s.widthDp) * 1.5f
        val spread = Math.PI / 7
        for (sgn in listOf(-1, 1)) {
            val a = angle + sgn * spread
            canvas.drawLine(
                x1, y1,
                x1 - (len * cos(a)).toFloat(),
                y1 - (len * sin(a)).toFloat(),
                p,
            )
        }
    }

    /**
     * 马赛克：把覆盖区域按格子采样后放大。
     *
     * 采样自**底图**（`base`），因为笔迹层本身是空的 —— 对着空白取色会得到
     * 一片透明。格子大小跟着笔画粗细走，粗笔=更糊。
     */
    private fun drawMosaic(canvas: Canvas, s: Stroke) {
        val bmp = base ?: return
        // ⚠️ 这里**不能**再乘一遍缩放比。
        //
        // `drawStrokes` 的入参 canvas 已经处于「显示坐标」系：预览时就是
        // 屏幕坐标；导出时 `exportBitmap` 先 `canvas.scale(sx, sy)` 把它
        // 变成图片像素坐标。所以本函数只要按传入坐标系画，缩放由上层统一
        // 负责 —— 再乘一次会让马赛克整块偏移（初版就是这个 bug，导出后
        // 糊错地方，预览却看不出来，因为预览的缩放比恰好是 1）。
        val cell = (dp(s.widthDp) * 2.2f).coerceAtLeast(dp(6f))
        // 显示坐标 → 底图像素：只用来**采样取色**，不影响绘制位置。
        val sx = if (imageBox.width > 0) bmp.width.toFloat() / imageBox.width else 1f
        val sy = if (imageBox.height > 0) bmp.height.toFloat() / imageBox.height else 1f
        val paint = Paint().apply { isFilterBitmap = false }

        for ((px, py) in s.points) {
            val half = cell / 2f
            // 采样区域（底图像素坐标，需夹紧到图内）
            val sl = (px * sx - cell * sx / 2f).toInt().coerceIn(0, bmp.width - 1)
            val st = (py * sy - cell * sy / 2f).toInt().coerceIn(0, bmp.height - 1)
            val sr = (px * sx + cell * sx / 2f).toInt().coerceIn(sl + 1, bmp.width)
            val sb = (py * sy + cell * sy / 2f).toInt().coerceIn(st + 1, bmp.height)
            // 取该区域平均色：1x1 缩放采样，比逐像素平均快得多
            val one = Bitmap.createScaledBitmap(
                Bitmap.createBitmap(bmp, sl, st, sr - sl, sb - st),
                1, 1, true,
            )
            val avg = one.getPixel(0, 0)
            one.recycle()
            paint.color = avg
            // 绘制位置用**传入坐标系**（显示坐标），交给上层缩放。
            canvas.drawRect(
                RectF(px - half, py - half, px + half, py + half),
                paint,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 笔迹层
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把自己量成「**满宽、按底图比例算高**」的矩形。
     *
     * ## 为什么是满宽而不是「缩到刚好放得下」
     *
     * 缩到放得下会让截图整体变小、左右留黑边，用户会觉得「画面变样了」。
     * 满宽则保证**宽度方向和浏览器里一模一样**（同样的字、同样的布局），
     * 高度超出就让 [canvasScroll] 滚动去看。
     *
     * 尺寸精确 = 图片显示矩形 = 笔迹层尺寸，三者严格重合，所以一个笔迹
     * 坐标就是一个图片坐标，导出时只按 `底图宽 / 本宽` 缩放即可。
     */
    private inner class ImageBox(context: Context) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availW = MeasureSpec.getSize(widthMeasureSpec)
            val bmp = base
            // ⚠️ 不能把 `availH <= 0` 当成「没准备好」：外层是 ScrollView，
            // 它会用 UNSPECIFIED 来量高度，`getSize()` 这时返回 0。高度本来
            // 也不该受容器约束（超了就滚动），只按宽度算。
            if (bmp == null || availW <= 0) {
                setMeasuredDimension(
                    availW.coerceAtLeast(0),
                    MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0),
                )
                return
            }
            // ⚠️ **不能一律拉满宽度**：截图现在是**侧栏面板宽度**（窄图），
            //    硬拉到屏幕满宽会放大两三倍，笔迹与像素的对应关系就虚了，
            //    看起来也糊。规则是「最多满宽，绝不放大」：
            //    截图比屏幕窄 ⇒ 1:1 原尺寸居中；比屏幕宽 ⇒ 缩到满宽。
            val w = min(availW, bmp.width)
            val h = (bmp.height.toFloat() * availW / bmp.width).toInt().coerceAtLeast(1)
            setMeasuredDimension(w, h)
            // 子视图（ImageView + 笔迹层）按最终尺寸量一遍。
            val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        }
    }

    /**
     * 接收触摸并记录笔画的透明层。
     *
     * 放在 ImageView 上面，铺满整块。它必须**吃掉所有触摸**（返回 true），
     * 否则事件会漏到下面的底图/宿主，出现「画着画着页面滚了」。
     */
    private inner class InkView(context: Context) : View(context) {

        /**
         * 允许「双指拖动 = 滚动画面」的外层滚动容器。
         *
         * 单指永远留给画笔（画着画着页面跟着滚会很崩溃），所以滚动只认
         * 双指。这是手机绘图类应用的通行做法。
         */
        var panner: ScrollView? = null

        /** 本次手势是否已判定为「双指滚动」。 */
        private var panning = false
        private var panStartY = 0f
        private var panScrollY = 0

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 每一帧都声明「我处理了这个手势」，阻止 ScrollView 半路抢走。
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 第二根手指落下 ⇒ 放弃当前笔画，转入滚动模式。
                    if (drawing != null) {
                        drawing = null
                        invalidate()
                    }
                    panning = true
                    panStartY = event.getY(0)
                    panScrollY = panner?.scrollY ?: 0
                    return true
                }
                MotionEvent.ACTION_DOWN -> {
                    panning = false
                    // 文字工具：点一下就地输入
                    if (tool == Tool.Text) {
                        promptText(x, y)
                        return true
                    }
                    drawing = Stroke(tool, color, strokeDp).also {
                        it.points.add(x to y)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (panning) {
                        // 手指往上拖 ⇒ 内容跟着往上 ⇒ scrollY 增大
                        val dy = (panStartY - event.getY(0)).toInt()
                        panner?.scrollTo(0, (panScrollY + dy).coerceAtLeast(0))
                        return true
                    }
                    val d = drawing ?: return true
                    if (tool == Tool.Pen || tool == Tool.Mosaic) {
                        // 采样：离上一个点太近就丢，省内存也让线条平滑
                        val last = d.points.lastOrNull()
                        if (last == null || hypot(x - last.first, y - last.second) > dp(2f)) {
                            d.points.add(x to y)
                        }
                    } else {
                        // 矩形/箭头：只需要首尾两点，末点持续更新
                        if (d.points.size < 2) d.points.add(x to y) else d.points[1] = x to y
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (panning) {
                        panning = false
                        return true
                    }
                    val d = drawing ?: return true
                    if (d.points.size >= 1) {
                        strokes.add(d)
                        redoStack.clear()
                    }
                    drawing = null
                    invalidate()
                    refreshSend()
                    return true
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 底图铺满整个 View（宽高比已由 ImageBox 保证，不会变形）。
            base?.let { bmp ->
                canvas.drawBitmap(
                    bmp,
                    null,
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            drawStrokes(canvas, strokes)
            drawing?.let { drawStrokes(canvas, listOf(it)) }
        }
    }

    /** 文字工具：弹一个输入框，确认后在点击位置落一条文字笔画。 */
    private fun promptText(x: Float, y: Float) {
        val edit = EditText(context).apply {
            hint = "输入标注文字"
            setTextColor(palette.text)
            setHintTextColor(palette.textDim)
            textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = fieldBg()
        }
        val dlg = android.app.AlertDialog.Builder(context)
            .setTitle("添加文字")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val txt = edit.text.toString().trim()
                if (txt.isNotEmpty()) {
                    strokes.add(Stroke(Tool.Text, color, strokeDp, mutableListOf(x to y), txt))
                    redoStack.clear()
                    inkView.invalidate()
                    refreshSend()
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
    }

    /** 释放底图，避免宿主反复开关时堆内存。 */
    fun release() {
        base?.recycle()
        base = null
        inkView.invalidate()
    }
}
