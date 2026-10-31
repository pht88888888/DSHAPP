package com.dsh.android.ui
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.dsh.android.R

/**
 * 启动过渡动画。
 *
 * **为什么要有它**：实测后续打开约 11.2 秒、首次打开约 27 秒，其中只有「解包引擎」
 * 一段有真实百分比（首次 15.5s），其余全是黑盒 —— 而这段时间屏幕上只有一行灰字
 * 加一个系统转圈，观感很差。这里把它换成一条**能表达"不确定等待"的动画**。
 *
 * 三层结构（由外到内）：
 *  1. **旋转光环**：一段约 96° 的渐变弧绕圈跑，转速恒定。它表达的是"正在进行"，
 *     不承诺进度 —— 与解包百分比解耦，所以两者不会互相打架。
 *  2. **进度环**：只有解包阶段出现，从 12 点方向顺时针长出来，是**唯一**带真实
 *     比例的元素。其余阶段它退成一条极淡的底环，暗示"在等"，不假装有进度。
 *  3. **头像徽章**：居中圆形头像，外围一圈柔光。呼吸式缩放（±1.5%）让它看起来
 *     "活着"，而不是一张贴上去的静态图。
 *
 * 配色取自应用图标本身（见 AGENTS.md 的图标一节）：
 * 深蓝 `#122B76`、亮蓝 `#3A7BD6`、青白 `#C0F0F0`、粉底 `#FEE6EA`。
 *
 * ⚠️ 全部动画由**一个** `ValueAnimator` 驱动（无限循环、线性插值），
 *   `onDraw` 里只做几何计算 —— 不叠加多个 animator，否则相位会漂移、
 *   而且每个 animator 都要各自 postInvalidate，白白多耗电。
 */
class BootAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ── 配色（与图标同源）────────────────────────────────────────────
    private val cBrightBlue = Color.parseColor("#3A7BD6")
    private val cCyan = Color.parseColor("#C0F0F0")

    /** 进度环底衬（极淡，表达"这里会显示进度"而不制造"卡住了"的错觉）。 */
    private val cTrack = Color.parseColor("#1E2A44")

    private val density = resources.displayMetrics.density

    /** 头像直径。与 `drawable-xxxhdpi/boot_avatar.png` 的基准 dp 一致。 */
    private val avatarD = 112f * density

    /** 环的线宽。 */
    private val strokeW = 3.5f * density

    /** 旋转光环所在半径。 */
    private val ringR = avatarD / 2f + 13f * density

    /** 头像与环之间的柔光半径。 */
    private val glowR = avatarD / 2f + 5f * density

    private val avatar by lazy { ContextCompat.getDrawable(context, R.drawable.boot_avatar) }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
        color = cTrack
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f * density
        color = cBrightBlue
        alpha = 78
        maskFilter = android.graphics.BlurMaskFilter(11f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private val rect = RectF()

    /** 旋转相位，单位度。由 animator 驱动，0→360 循环。 */
    private var spin = 0f

    /** 进度，0..1。负数 = 无进度（只显示底环）。 */
    private var progress = -1f

    /** 呼吸相位，0..1 循环。 */
    private var breath = 0f

    private var animator: ValueAnimator? = null

    /** 光环的渐变刷子；尺寸确定后重建一次。 */
    private var ringShader: Shader? = null

    /**
     * 每帧旋转光环 shader 用的矩阵。
     *
     * ⚠️ 复用同一个对象，**不要每帧 new** —— 这跑在 onDraw 里，
     *   每帧分配会在 GC 时造成可见的掉帧。
     */
    private val ringMatrix = Matrix()

    init {
        // 硬件层：整块是渐变 + 模糊，交给 GPU 合成比软件绘制省电
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 设置解包进度。
     *
     * @param p 0..1；传负数表示"当前阶段没有真实进度"，只显示底环。
     */
    fun setProgress(p: Float) {
        if (progress == p) return
        progress = p
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildRingShader(w, h)
    }

    /**
     * 光环用 `SweepGradient` 做「两端渐隐」——弧的头尾都淡到透明，
     * 这样它转起来不会有硬边，观感是"划过"而不是"跳出来又消失"。
     *
     * ⚠️ **这里曾经有个导致撕裂感的真 bug，改动前务必读这段。**
     *   原实现用 `Matrix().setRotate(-90f, cx, cy)` 把渐变起点挪到 12 点方向，
     *   而 `Canvas.drawArc` 的 startAngle **本来就是从 3 点方向起算的** ——
     *   两者错开 90°。更糟的是 stop 位置被按 `ringSpan/360` 压缩到了 0.21 以内，
     *   于是整条弧（画在渐变坐标 0.21 之外）全部落进 clamp 区，渲染成**纯色**。
     *
     *   真机截图的角向亮度剖面坐实了这一点（`f1.png`，0°=3 点、顺时针）：
     *     264°:255  270°:252  …  12°:235  18°:110  30°:107  36°:47
     *   整条弧从头到尾恒定 255，尾部 20° 内直坠、头部 6° 内从 50 跳到 255
     *   —— 两端都是硬切，根本没有渐隐。
     *
     * 正解：**两个角度基准必须一致**。`drawArc` 从 3 点起算，那渐变也从 3 点起算，
     * 即**不做任何旋转**。stop 的位置按整圈 360° 的比例给出。
     */
    private fun buildRingShader(w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f

        // alpha 沿弧长 t 的分布：两端各渐隐 42%、中间实心。
        // 刻意做成**对称**的 —— 双弧对转没有单一方向，两端对称才不会读出一个假方向。
        val edge = 0.42f
        fun fade(t: Float): Float = when {
            t < edge -> smoothStep(t / edge)
            t > 1f - edge -> smoothStep((1f - t) / edge)
            else -> 1f
        }

        // ⚠️ 一个 shader 同时描述**两段**弧，这样每帧只需旋转一次矩阵。
        //   若改成每段各转一次，就要两次 setLocalMatrix —— 能跑，但没必要。
        //   两段在渐变坐标里分别占 [0, span/360] 与 [0.5, 0.5+span/360]，
        //   中间与末尾都是"全透明"，所以两段之间以及回绕处都不会出现硬边。
        val n = 4                                   // 每段 5 个采样点
        val colors = IntArray((n + 1) * 2)
        val positions = FloatArray((n + 1) * 2)
        var k = 0
        for (seg in 0 until 2) {
            val segStart = seg * ringGap / 360f      // 0 或 0.5
            for (i in 0..n) {
                val t = i.toFloat() / n
                val a = (fade(t) * 255f).toInt().coerceIn(0, 255)
                // 颜色沿弧长：尾部亮蓝 → 头部青白，与图标同源。
                colors[k] = withAlpha(lerpColor(cBrightBlue, cCyan, t), a)
                // ⚠️ stop 的 position 是**整圈 360° 的比例**，不是弧长的比例。
                positions[k] = (segStart + t * ringSpan / 360f).coerceAtMost(0.9999f)
                k++
            }
        }

        // ⚠️ **不在这里旋转**。每帧由 onDraw 按当前 ringHead 设 localMatrix ——
        //   shader 是静止的，而 drawArc 的 startAngle 在变，必须让 shader 跟着转，
        //   否则弧会画到渐变的透明区里（这正是之前撕裂感的来源）。
        ringShader = SweepGradient(cx, cy, colors, positions)
    }

    /** 两个颜色之间线性插值，t ∈ [0,1]。 */
    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * u).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * u).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * u).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * u).toInt()
        return Color.argb(a, r, g, b)
    }

    /** 平滑阶跃 3t²−2t³，让渐隐的两端没有可见的折点。 */
    private fun smoothStep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 给颜色套一个 alpha（0..255）。 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /** 光环的头部角度（度，0 = 3 点方向，顺时针为正）。 */
    private var ringHead = 0f

    /**
     * 单段弧扫过的角度。
     *
     * 双弧对转：两段等长弧相位差 180°。76° 是权衡出来的 ——
     * 再长两段就快连成一圈（失去"两段"的辨识度），再短则显得单薄。
     */
    private val ringSpan = 76f

    /** 双弧相位差。 */
    private val ringGap = 180f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        // 呼吸：±1.5% 的缩放。幅度刻意压得很小 —— 再大就变成"跳"，不是"呼吸"。
        val scale = 1f + 0.015f * sin(breath * 2.0 * Math.PI).toFloat()

        // ── 1. 柔光 ──────────────────────────────────────────────────
        canvas.drawCircle(cx, cy, glowR * scale, glowPaint)

        // ── 2. 头像徽章 ──────────────────────────────────────────────
        val d = (avatarD * scale).toInt()
        avatar?.let {
            it.setBounds((cx - d / 2f).toInt(), (cy - d / 2f).toInt(),
                (cx + d / 2f).toInt(), (cy + d / 2f).toInt())
            it.draw(canvas)
        }

        // ── 3. 进度环（内圈）────────────────────────────────────────
        //
        // ⚠️ 进度环刻意画在**比旋转光环更小的半径**上。两者同半径时会在解包阶段
        //   完全重叠，看起来像一根环在抽搐（真机截图确认）。分开之后：
        //   外圈 = 恒定转速的"正在进行"，内圈 = 真实进度，语义互不干扰。
        if (progress >= 0f) {
            val pr = ringR - 7f * density
            rect.set(cx - pr, cy - pr, cx + pr, cy + pr)
            canvas.drawArc(rect, 0f, 360f, false, trackPaint)
            ringPaint.shader = LinearGradient(
                cx - pr, cy - pr, cx + pr, cy + pr,
                cBrightBlue, cCyan, Shader.TileMode.CLAMP,
            )
            canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, ringPaint)
            ringPaint.shader = null
        }

        // ── 4. 旋转光环（外圈，双弧对转，叠在最上层）────────────────
        //
        // 无进度阶段（引擎启动 / 加载界面 / 插件激活）**只画它**：
        // 那几段没有真实进度，画静态底环会被读成"进度满了"，语义正好相反。
        //
        // ⚠️ 关键：shader 是**静止**的（描述"相对弧起点"的 alpha 分布），
        //   而 drawArc 的 startAngle 每帧在变。所以每帧都要把 shader 按 ringHead
        //   旋转同样的角度，让渐变跟着弧走。**漏掉这一步就是之前撕裂感的来源** ——
        //   弧跑到渐变的透明/clamp 区里，渲染成两端硬切的纯色。
        //
        //   旋转中心用 (cx, cy)，与 SweepGradient 的构造中心一致。
        ringMatrix.setRotate(ringHead, cx, cy)
        ringShader?.setLocalMatrix(ringMatrix)

        // 两段弧共用同一个 shader：shader 里已经分别描述了 [0,span] 与 [0.5,0.5+span]
        // 两段，旋转 ringHead 之后正好与下面两次 drawArc 一一对应。
        rect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        ringPaint.shader = ringShader
        canvas.drawArc(rect, ringHead, ringSpan, false, ringPaint)
        canvas.drawArc(rect, ringHead + ringGap, ringSpan, false, ringPaint)
        ringPaint.shader = null
    }

    /** 每帧的相位推进。由单个 animator 以固定帧率驱动。 */
    private fun advance() {
        // 转速：约 1.35 秒一圈。比系统转圈慢，观感更"从容"，
        // 也更符合"在准备一个较大的东西"这件事。
        spin = (spin + SPIN_PER_TICK) % 360f
        breath = (breath + BREATH_PER_TICK) % 1f
        ringHead = spin
        // 呼吸用正弦，但要让它连续 —— 每帧推进即可，sin 自带平滑
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /**
     * 启动无限循环动画。
     *
     * ⚠️ 用 `ValueAnimator` 的 `INFINITE` 而不是 `Choreographer` 或 `Handler` 轮询：
     *   它跟随系统的动画时钟（会与 vsync 对齐），且**尊重"移除动画"无障碍设置** ——
     *   用户开了那个开关时系统会把动画时长缩到 0，这里能自然跟着停，不会硬转。
     */
    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { advance() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private companion object {
        /**
         * 每帧推进的基准间隔（ms）。
         *
         * 60fps 下每帧约 16.7ms，这里取 16 —— 相位推进按"每帧固定量"算，
         * 与真实帧率无关，所以掉帧时动画会慢一点但**不会跳变**。
         */
        const val TICK_MS = 16L

        /** 每帧旋转角度：360 / (1350ms / 16ms) ≈ 4.27°。 */
        const val SPIN_PER_TICK = 4.27f

        /** 每帧呼吸相位：约 3.4 秒一次完整呼吸。 */
        const val BREATH_PER_TICK = 16f / 3400f
    }
}
