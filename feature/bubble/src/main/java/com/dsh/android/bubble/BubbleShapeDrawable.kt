package com.dsh.android.bubble
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import com.dsh.android.log.DshLog as Log
import androidx.core.graphics.PathParser

/**
 * 用 **SVG path 的 `d` 属性**描述形状的球底 Drawable。
 *
 * ## 为什么要有它
 *
 * 原来球的形状是 `circle` / `square` / `rounded` 三选一（见 [BubbleAppearance]），
 * 那是**枚举** —— AI 永远只能在这三个里挑。而用户要的是「不管提什么要求都能做到」：
 * 星形、心形、盾牌、云朵、六边形、某个 logo 的轮廓，枚举一个都给不了。
 *
 * 于是形状改由一条 SVG path 描述，本类负责整条链路：解析 → 按 viewBox 缩放居中 →
 * 填充（纯色或竖直渐变）→ 描边 → 提供 outline（让 elevation 阴影跟着形状走）。
 *
 * ## 坐标与缩放
 *
 * [viewBox] 用 SVG 的 `minX minY width height` 记法（默认 `0 0 100 100`），
 * path 就在这个坐标系里写。绘制时**等比缩放并居中**到球的方框内 —— 不拉伸，
 * 因为拉伸会把精心画的形状压扁；形状与球比例不符时留白，由调用方调 viewBox 解决。
 *
 * ## 三条不变量
 *
 * ① **path 解析失败不抛异常**，退回一个圆 —— 球绝不能因为一条坏 path 而消失，
 *    那是最糟的失败形态（用户什么都看不到，也没有报错）。
 * ② path 只在构造时解析一次；之后每次尺寸变化只重算矩阵，不重新解析。
 * ③ alpha 是**乘性**的：颜色自带的 alpha 与 [setAlpha] 相乘（见 [applyAlpha]）。
 */
class BubbleShapeDrawable(
    private val pathData: String,
    private val viewBox: String,
    private val fillStart: Int,
    private val fillEnd: Int?,
    private val strokeColor: Int,
    private val strokeWidth: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@BubbleShapeDrawable.strokeWidth
    }

    /** 解析出来的原始 path，坐标在 [viewBox] 空间里。 */
    private val source = Path()

    /** 缩放居中之后的 path，坐标在视图空间里。每次 bounds 变化重算。 */
    private val shaped = Path()

    private val srcRect = RectF()
    private val dstRect = RectF()

    private var drawableAlpha = 255

    init {
        try {
            val p = PathParser.createPathFromPathData(pathData)
            if (p != null && !p.isEmpty) source.set(p)
        } catch (t: Throwable) {
            Log.w(TAG, "SVG 形状 path 解析失败，退回圆形: ${t.message}")
        }
        if (source.isEmpty) Log.w(TAG, "SVG 形状 path 为空或无法解析，退回圆形")
        srcRect.set(parseViewBox(viewBox))
        applyAlpha()
    }

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    /** 把 [source] 按 [srcRect] → [dstRect] 等比缩放居中到 [shaped]。 */
    private fun rebuild() {
        shaped.reset()
        val b = bounds
        if (b.isEmpty) return

        // 描边有一半画在路径外侧，留出这半个线宽免得被裁掉。
        val inset = if (strokeWidth > 0f) strokeWidth / 2f else 0f
        dstRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (dstRect.width() <= 0f || dstRect.height() <= 0f) return

        val m = Matrix()
        val sw = srcRect.width()
        val sh = srcRect.height()
        if (sw > 0f && sh > 0f) {
            val s = minOf(dstRect.width() / sw, dstRect.height() / sh)
            m.setScale(s, s)
            m.postTranslate(
                dstRect.centerX() - srcRect.centerX() * s,
                dstRect.centerY() - srcRect.centerY() * s,
            )
        }
        if (source.isEmpty) {
            Path().apply { addOval(srcRect, Path.Direction.CW) }.transform(m, shaped)
        } else {
            source.transform(m, shaped)
        }
    }

    override fun draw(canvas: Canvas) {
        if (shaped.isEmpty) return
        if (fillEnd != null) {
            fillPaint.shader = LinearGradient(
                0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
                fillStart, fillEnd, Shader.TileMode.CLAMP,
            )
        } else {
            fillPaint.shader = null
        }
        canvas.drawPath(shaped, fillPaint)
        if (strokeWidth > 0f) canvas.drawPath(shaped, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        if (a == drawableAlpha) return
        drawableAlpha = a
        applyAlpha()
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    /**
     * 恒为半透明。
     *
     * ⚠️ 不能按 `fillStart` 的 alpha 返回 `TRANSPARENT`/`OPAQUE`：形状层是**分层**
     *   模型的一层，它的 alpha 会随 `bgOpacity` 变，而 `getOpacity` 是"最坏情况"
     *   声明，用来让父容器决定要不要开混合。声明成半透明永远安全（最多多一次
     *   离屏合成），声明成不透明则背景透明时会画出黑块。
     */
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    /**
     * 让 `elevation` 的阴影跟着形状走。
     *
     * ⚠️ `Outline.setPath` 要 API 30；API 29 及以下只有 `setConvexPath`，非凸形状
     *   （星形、心形）会失败并退回矩形 —— 那是**优雅降级**：阴影变方，形状本身不变。
     *
     * ⚠️ `setConvexPath` 返回 **void**（不是 boolean），所以判断"成没成"只能看
     *   outline 是否仍为空 —— 直接写 `if (!outline.setConvexPath(...))` 编译不过。
     */
    override fun getOutline(outline: Outline) {
        if (shaped.isEmpty) {
            outline.setRect(bounds)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                outline.setPath(shaped)
            } else {
                outline.setConvexPath(shaped)
                // 非凸 path 在 API 29 及以下会被静默忽略，此时 outline 仍为空。
                if (outline.isEmpty) outline.setRect(bounds)
            }
        } catch (t: Throwable) {
            outline.setRect(bounds)
        }
    }

    /**
     * 把 [drawableAlpha] 与颜色自带的 alpha 乘在一起。
     *
     * ⚠️ 渐变那一支**不**做这个乘法：Skia 里 shader 的 alpha 与 paint 的 alpha
     *   本来就是相乘的，再乘一次会变成双重衰减（`#80` 的渐变会淡到看不见）。
     */
    private fun applyAlpha() {
        if (fillEnd != null) {
            fillPaint.alpha = drawableAlpha
        } else {
            fillPaint.color = fillStart
            fillPaint.alpha = combineAlpha(Color.alpha(fillStart), drawableAlpha)
        }
        strokePaint.color = strokeColor
        strokePaint.alpha = combineAlpha(Color.alpha(strokeColor), drawableAlpha)
    }

    companion object {
        private const val TAG = Log.TAG

        /** 默认 viewBox。与给模型的文档、示例保持一致。 */
        const val DEFAULT_VIEW_BOX = "0 0 100 100"

        private fun combineAlpha(colorAlpha: Int, drawableAlpha: Int): Int =
            (colorAlpha * drawableAlpha / 255).coerceIn(0, 255)

        /** 解析 `minX minY width height`。非法的按默认 viewBox 处理。 */
        private fun parseViewBox(spec: String): RectF {
            val parts = spec.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) {
                return RectF(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
            }
            return RectF(0f, 0f, 100f, 100f)
        }
    }
}
