package com.dsh.android.bubble

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/** 内容层的动画资源。只接受目标 View 与不可变样式，不参与窗口和动作优先级。 */
internal class BubbleContentAnimator(private val physics: BubblePhysics) {
    data class Style(val spin: Int, val pulse: Boolean, val scale: Float, val flipX: Boolean)

    private var avatarView: View? = null
    private var videoHost: View? = null
    private var style = Style(0, false, 1f, false)
    private var spinning = false
    private var pulsing = false
    private var spinAnimator: ObjectAnimator? = null
    private var spinDuration = 0L
    private var spinTo = 0f
    private var pulseAnimator: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null
    private var pulseVideoX: ObjectAnimator? = null
    private var pulseVideoY: ObjectAnimator? = null
    private var pulseBase = 0f
    private var squashAnim: ValueAnimator? = null

    /** attach 之前也可能有点击/落地，目标由宿主显式绑定。 */
    fun bind(avatar: View?, video: View?, style: Style) {
        if (avatar !== avatarView || video !== videoHost) release()
        avatarView = avatar
        videoHost = video
        this.style = style
    }

    fun release() {
        squashAnim?.cancel()
        squashAnim = null
        spinAnimator?.cancel()
        pulseAnimator?.cancel()
        pulseAnimatorY?.cancel()
        pulseVideoX?.cancel()
        pulseVideoY?.cancel()
        spinAnimator = null
        pulseAnimator = null
        pulseAnimatorY = null
        pulseVideoX = null
        pulseVideoY = null
        spinDuration = 0L
        spinTo = 0f
        pulseBase = 0f
        spinning = false
        pulsing = false
        avatarView = null
        videoHost = null
    }

    /**
     * 动效层：旋转与呼吸。
     *
     * ⚠️ 两者都作用在 `avatarView`（内容层）而不是整个球：转整个球会把形状层
     *   也转起来，那对非圆形状（星形/心形）是灾难。
     *
     * ⚠️ **必须用两个独立的 `ObjectAnimator`，不能用 `view.animate()`。**
     *   `ViewPropertyAnimator` 对同一个 View 是**单例**，后一次 `.start()` 会
     *   取消前一次 —— 于是「旋转 + 呼吸」同时打开时只有一个生效，而且看不出
     *   是谁把谁取消了。`ObjectAnimator` 是独立对象，两个不同属性可以并行。
     *
     * ⚠️ 旋转用 `repeatCount = INFINITE` 而不是 `withEndAction` 递归：递归那种
     *   写法在"动画已在跑"的守卫下会转完一圈就停（守卫把它自己挡住了）。
     */
    fun apply(avatar: View?, video: View?, style: Style) {
        if (avatar !== avatarView || video !== videoHost) {
            release()
            avatarView = avatar
            videoHost = video
        }
        this.style = style
        val iv = avatarView ?: return

        // ⚠️ **视频层要跟着一起做动效。**
        //
        //   原实现只对 `avatarView`（图片层）设 spin/pulse，视频层 `videoHost`
        //   完全不动。而桌宠的主流素材是视频 —— 于是「旋转」「呼吸」这两个
        //   开关对视频宠物**完全无效**，且两个内容层互斥显示时观感不一致
        //   （图片球会呼吸，切成视频后突然静止）。
        //
        //   这里只同步**静态部分**（基础缩放与旋转角）；呼吸是 `ObjectAnimator`，
        //   动画器要各自建一个（见下面 pulse 分支里的 `pulseVideoX/Y`）。
        (videoHost as? View)?.let { vh ->
            vh.scaleY = style.scale
            vh.scaleX = if (style.flipX) -style.scale
                        else style.scale
            vh.rotation = if (style.spin != 0) vh.rotation else 0f
        }

        // ── 旋转 ──────────────────────────────────────────────────────
        if (style.spin != 0) {
            // 每 360° 的时长 = 360 / 每秒角度。负角度即逆时针。
            val degPerSec = abs(style.spin).coerceAtLeast(1)
            val duration = (360f / degPerSec * 1000f).toLong().coerceAtLeast(200L)
            val to = if (style.spin > 0) 360f else -360f
            val changed = spinAnimator == null || spinDuration != duration || spinTo != to
            if (changed) {
                spinAnimator?.cancel()
                // 从 0 转到 ±360：`repeatMode = RESTART` 会把每轮重置回 0，
                // 所以终值必须正好是一整圈，否则每轮之间会跳一下。
                spinAnimator = ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, to).apply {
                    this.duration = duration
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = LinearInterpolator()
                    start()
                }
                spinDuration = duration
                spinTo = to
                spinning = true
            }
        } else if (spinning) {
            spinAnimator?.cancel()
            spinAnimator = null
            spinDuration = 0L
            spinTo = 0f
            spinning = false
            iv.rotation = 0f
        }

        // ── 呼吸 ──────────────────────────────────────────────────────
        if (style.pulse) {
            val base = style.scale
            // ⚠️ **呼吸的 X 缩放要带上朝向符号**：这个动画器独占 `SCALE_X`，
            //   直接给正值会把转身（`scaleX = -s`）覆盖掉 —— 表现为"呼吸一开，
            //   宠物立刻转回正面，而且再也不转身"。
            val sx = if (style.flipX) -base else base
            val changed = pulseAnimator == null || pulseBase != sx
            if (changed) {
                pulseAnimator?.cancel()
                pulseAnimator = ObjectAnimator.ofFloat(iv, View.SCALE_X, sx, sx * 1.12f).apply {
                    this.duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                // 两个属性（X/Y）必须同步，否则会拉成椭圆。共用一个进度源最简单：
                // 再建一个只驱动 SCALE_Y 的、时长与相位完全一致的动画。
                pulseAnimatorY?.cancel()
                pulseAnimatorY = ObjectAnimator.ofFloat(iv, View.SCALE_Y, base, base * 1.12f).apply {
                    this.duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                // 视频层用同一组参数再建一对，保证两个内容层呼吸节奏一致
                (videoHost as? View)?.let { vh ->
                    pulseVideoX?.cancel()
                    pulseVideoY?.cancel()
                    pulseVideoX = ObjectAnimator.ofFloat(vh, View.SCALE_X, sx, sx * 1.12f).apply {
                        this.duration = 700
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                    pulseVideoY = ObjectAnimator.ofFloat(vh, View.SCALE_Y, base, base * 1.12f).apply {
                        this.duration = 700
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
                pulseBase = sx
                pulsing = true
            }
        } else if (pulsing) {
            pulseAnimator?.cancel()
            pulseAnimatorY?.cancel()
            pulseVideoX?.cancel()
            pulseVideoY?.cancel()
            pulseAnimator = null
            pulseAnimatorY = null
            pulseVideoX = null
            pulseVideoY = null
            pulseBase = 0f
            pulsing = false
            // ⚠️ 用 [facingScaleX] 而不是 `avatarScale`：这里在"关掉呼吸"时把缩放
            //   复位，直接写 avatarScale 会把朝向一起抹掉 —— 表现为"关掉呼吸后
            //   宠物突然转回正面"。
            iv.scaleX = if (style.flipX) -style.scale else style.scale
            iv.scaleY = style.scale
            (videoHost as? View)?.let { vh ->
                vh.scaleX = if (style.flipX) -style.scale else style.scale
                vh.scaleY = style.scale
            }
        }
    }

    fun squash(depth: Float) {
        val targets = listOfNotNull(avatarView, videoHost as? View).filter { it.visibility == View.VISIBLE }
        if (targets.isEmpty()) return
        val base = style.scale
        val d = depth.coerceIn(0f, 1f)
        squashAnim?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = BubblePhysics.SQUASH_MS
        // ⚠️ 不给插值器：曲线自己带 ease-in 下压 + easeOutBack 回弹过冲，
        //   再叠一层系统插值器会把过冲抹平（那正是"Q 弹"的来源）。
        anim.interpolator = null
        anim.addUpdateListener { a ->
            val s = physics.squashCurve(a.animatedFraction, d)
            for (v in targets) {
                if (v.pivotY != v.height.toFloat()) v.pivotY = v.height.toFloat()
                v.scaleY = base * s
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (squashAnim !== animation) return
                squashAnim = null
                // 收尾必须回到基准值：过冲段的 1.12 若留在视图上，
                // 宠物会一直比正常高一点。
                for (v in targets) v.scaleY = base
            }
        })
        squashAnim = anim
        anim.start()
    }

}
