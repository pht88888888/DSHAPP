package com.dsh.android.bubble
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮球的**抛掷物理** —— 把 dsh-pet 那套手感搬到 Android。
 *
 * ## 为什么要有这一层
 *
 * 原来的悬浮球松手只有两种结局：吸附到左右边缘、或拖出屏幕贴边。那是"窗口管理"
 * 的语义，不是"宠物"的语义。dsh-pet 的球是**可以扔的**：甩出去会飞、撞边会弹、
 * 落地会压扁、滚几下停住 —— 这一层补的就是这个。
 *
 * ## 常量来源
 *
 * 全部照抄 dsh-pet 的 `src/shared/physics.ts`（两边数值必须一致，否则"一模一样"
 * 就只是视觉上像）。⚠️ 唯一不同的是**尺度**：dsh-pet 跑在桌面浏览器里，单位是
 * CSS px；这里跑在 Android 上，单位是物理 px。桌面 CSS px ≈ Android dp 的视觉
 * 尺度，所以 [forDensity] 按 density 把线速度与加速度放大，让不同 DPI 的设备
 * 手感一致 —— 不缩放的话，同一串常量在 xxhdpi 上会显得"重力特别小、飞得特别远"。
 *
 * ## 纯函数
 *
 * 本类**不碰任何 View / Handler**：输入是状态与时间步长，输出是新状态。这样它
 * 可以被单元测试，也可以在真机上对着日志调参，而不必反复装 APK。
 *
 * @param density 设备密度（`displayMetrics.density`）。
 */
class BubblePhysics(private val density: Float) {

    companion object {
        /** 重力加速度（CSS px/s²）。 */
        const val GRAVITY = 1400f

        /** 落地反弹系数。0.78 ≈ 弹两三次就停，不会一直蹦。 */
        const val RESTITUTION = 0.78f

        /** 地面摩擦（每秒衰减倍率）。 */
        const val GROUND_FRICTION = 2.5f

        /** 撞墙（左右边缘）衰减。 */
        const val WALL_DAMPING = 0.82f

        /**
         * 抛掷速度上限（CSS px/s）。
         *
         * ⚠️ 用**软上限** `MAX·(1-e^(-s/MAX))` 而不是硬截断：硬截断会让"甩得越快
         *   飞得越远"在手速超过阈值后突然失效，用户感觉是"用力甩反而没用"。
         */
        const val MAX_THROW_SPEED = 3600f

        /**
         * 死区（CSS px/s）：低于这个速度的抛掷当作"只是松手"，不产生飞行。
         *
         * 取 500 是因为手指轻微抖动也能算出几百 px/s，不设死区的话"把球拖到某处
         * 放好"会变成"每次都把它甩出去"。
         */
        const val DEAD_ZONE_SPEED = 500f

        /** 判定静止的竖直速度阈值（CSS px/s）。 */
        const val REST_VY = 40f

        /** 判定静止的水平速度阈值（CSS px/s）。 */
        const val REST_VX = 15f

        /** 单步最大时间步长（s）。防止掉帧后一帧飞出屏幕。 */
        const val MAX_STEP_DT = 0.05f

        /** 一次弹跳的挤压动画时长（ms）。 */
        const val SQUASH_MS = 220L

        /** 落地时最大竖直压缩比例（压到原高的 55%）。 */
        const val SQUASH_MIN_SCALE = 0.55f

        /** 触发挤压的最小下落速度（CSS px/s）。轻轻落下不该压扁。 */
        const val SQUASH_MIN_IMPACT = 300f

        /**
         * **点击回应**的下压幅度（压到原高的 55%）。
         *
         * ⚠️ 与落地挤压共用 [SQUASH_MIN_SCALE] 的数值但**语义不同**，刻意分开写：
         *   落地那个是"撞击越猛压得越扁"的下限，这个是点击的**固定**深度。
         *   两者数值相同是巧合（都取自 dsh-pet 的 `SQ_SQUASH` / `SQ_MAX_SQUASH`），
         *   将来单独调其中一个时不该互相牵连。
         */
        const val SQ_CLICK_DEPTH = 0.55f

        /** 挤压曲线的"下压段"占比：0~0.45 压下去，0.45~1 回弹。 */
        const val SQ_DOWN_RATIO = 0.45f

        /** 回弹过冲上限（电脑版是 1.12，即回弹时略微拉长 12%）。 */
        const val SQ_OVERSHOOT_MAX = 1.12f

        /** 轨迹保留时长（ms）—— 用来估抛掷速度。 */
        const val TRAIL_KEEP_MS = 200L

        /** 估速用的窗口（ms）：只看松手前这一段。 */
        const val RELEASE_WINDOW_MS = 150L

        /** 轨迹太旧就不算数（ms）。 */
        const val RELEASE_STALE_MS = 150L

        /** 窗口内至少要有这么长的时间跨度才算得准（ms）。 */
        const val MIN_SPAN_MS = 20L

        /** 相邻采样点至少隔这么久才计入（ms），滤掉同一帧的重复点。 */
        const val SEG_MIN_DT_MS = 8L

        /** 峰值加权系数：末段速度与峰值速度的混合比例。 */
        const val PEAK_WEIGHT = 0.5f

        /** 加速度增益的参考值（CSS px/s²）。 */
        const val ACCEL_REF = 8000f

        /** 加速度增益上限。 */
        const val ACCEL_GAIN_MAX = 0.6f
    }

    /** 按密度缩放后的重力（px/s²）。 */
    val gravity: Float = GRAVITY * density

    /** 按密度缩放后的速度上限（px/s）。 */
    val maxThrowSpeed: Float = MAX_THROW_SPEED * density

    /** 按密度缩放后的死区（px/s）。 */
    val deadZoneSpeed: Float = DEAD_ZONE_SPEED * density

    /** 按密度缩放后的落地挤压触发阈值（px/s）。 */
    private val squashMinImpact: Float = SQUASH_MIN_IMPACT * density

    /** 运动状态。坐标是**窗口左上角**的屏幕 px。 */
    class State(
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
    )

    /** 一步积分的结果。 */
    data class Step(
        /** 这一步是否发生了落地撞击（用来触发挤压动画）。 */
        val landed: Boolean,
        /** 撞击时的下落速度（px/s），用于决定挤压幅度。 */
        val impactSpeed: Float,
        /** 这一步是否已经静止（可以停止物理循环）。 */
        val resting: Boolean,
    )

    /**
     * 推进一个时间步。
     *
     * @param s 状态，**原地修改**。
     * @param dt 时间步长（s）。
     * @param minX 左边界（px）。
     * @param minY 上边界（px）。
     * @param maxX 右边界（窗口左上角的最大值，px）。
     * @param maxY 下边界（同上）。
     * @param bounce 是否允许落地反弹（关掉就是"掉下去停住"）。
     */
    fun step(
        s: State,
        dt: Float,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        bounce: Boolean = true,
    ): Step {
        val d = dt.coerceIn(0f, MAX_STEP_DT)

        // 重力积分（半隐式欧拉：先加速度再更新位置，比显式稳定）。
        s.vy += gravity * d
        s.x += s.vx * d
        s.y += s.vy * d

        var landed = false
        var impact = 0f

        // ── 左右墙 ────────────────────────────────────────────────────
        if (s.x < minX) {
            s.x = minX
            s.vx = -s.vx * WALL_DAMPING
        } else if (s.x > maxX) {
            s.x = maxX
            s.vx = -s.vx * WALL_DAMPING
        }

        // ── 天花板 ────────────────────────────────────────────────────
        if (s.y < minY) {
            s.y = minY
            s.vy = -s.vy * WALL_DAMPING
        }

        // ── 地面 ──────────────────────────────────────────────────────
        if (s.y > maxY) {
            s.y = maxY
            impact = abs(s.vy)
            landed = impact >= squashMinImpact
            s.vy = if (bounce && impact >= squashMinImpact) {
                -s.vy * RESTITUTION
            } else {
                0f
            }
            // 地面摩擦：按指数衰减而不是线性扣减，帧率无关。
            s.vx *= exp(-GROUND_FRICTION * d)
        }

        // ── 静止判定 ──────────────────────────────────────────────────
        // ⚠️ 必须同时看"是否贴着地面"：空中速度为 0 只是抛物线的顶点，
        //   那里停住的话球会挂在半空。
        val onGround = s.y >= maxY - 0.5f
        val resting = onGround && abs(s.vy) < REST_VY * density && abs(s.vx) < REST_VX * density
        if (resting) {
            s.vy = 0f
            s.vx = 0f
        }
        return Step(landed = landed, impactSpeed = impact, resting = resting)
    }

    /**
     * 从轨迹估抛掷速度。
     *
     * 算法与 dsh-pet 一致，分三步：
     * 1. 只取 [RELEASE_WINDOW_MS] 窗口内的点，且时间跨度要够（[MIN_SPAN_MS]）；
     * 2. 算末段速度与峰值速度，按 [PEAK_WEIGHT] 混合 —— **单看末段会被"松手前
     *    手指已经停下"骗到**（甩出去后手指常常有个自然的减速），峰值更能代表意图；
     * 3. 叠加一个加速度增益：甩得越急（加速度越大）给一点额外速度，封顶
     *    [ACCEL_GAIN_MAX]。
     *
     * 最后套软上限并过死区。
     *
     * @param trail 采样点 `(x, y, tMillis)`，按时间递增。
     * @param now 松手时刻（ms）。
     * @return `vx to vy`（px/s）；不足以估速时返回 null（表示"不抛掷"）。
     */
    fun estimateThrow(trail: List<Triple<Float, Float, Long>>, now: Long): Pair<Float, Float>? {
        if (trail.size < 2) return null
        val fresh = trail.filter { now - it.third <= RELEASE_WINDOW_MS }
        if (fresh.size < 2) return null

        val first = fresh.first()
        val last = fresh.last()
        val span = last.third - first.third
        if (span < MIN_SPAN_MS) return null
        if (now - last.third > RELEASE_STALE_MS) return null

        // 末段速度：窗口首尾之间的平均速度。
        var vx = (last.first - first.first) / (span / 1000f)
        var vy = (last.second - first.second) / (span / 1000f)

        // 峰值速度：相邻点里最快的那一段。
        var peakVx = vx
        var peakVy = vy
        var peakSpeed = 0f
        for (i in 1 until fresh.size) {
            val a = fresh[i - 1]
            val b = fresh[i]
            val dtMs = b.third - a.third
            if (dtMs < SEG_MIN_DT_MS) continue
            val sx = (b.first - a.first) / (dtMs / 1000f)
            val sy = (b.second - a.second) / (dtMs / 1000f)
            val sp = kotlin.math.hypot(sx, sy)
            if (sp > peakSpeed) {
                peakSpeed = sp
                peakVx = sx
                peakVy = sy
            }
        }
        vx = vx * (1f - PEAK_WEIGHT) + peakVx * PEAK_WEIGHT
        vy = vy * (1f - PEAK_WEIGHT) + peakVy * PEAK_WEIGHT

        // 加速度增益：把"这一段里速度涨了多少"折算成一个 0~0.6 的放大系数。
        val rawSpeed = kotlin.math.hypot(vx, vy)
        if (rawSpeed > 1f && peakSpeed > rawSpeed) {
            val accel = (peakSpeed - rawSpeed) / (span / 1000f)
            val gain = min(ACCEL_GAIN_MAX, max(0f, accel / ACCEL_REF))
            vx *= 1f + gain
            vy *= 1f + gain
        }

        // 软上限 + 死区。
        val speed = kotlin.math.hypot(vx, vy)
        if (speed < deadZoneSpeed) return null
        if (speed > maxThrowSpeed) {
            val soft = maxThrowSpeed * (1f - exp(-speed / maxThrowSpeed))
            val k = soft / speed
            vx *= k
            vy *= k
        }
        return vx to vy
    }

    /**
     * 撞击时的挤压比例（竖直方向），用于 squash & stretch。
     *
     * @param impactSpeed 撞击速度（px/s）。
     * @return 竖直缩放，[SQUASH_MIN_SCALE] ~ 1.0。
     */
    fun squashScale(impactSpeed: Float): Float {
        if (impactSpeed < squashMinImpact) return 1f
        // 撞击越猛压得越扁，但不会压到 0（那样看着像消失）。
        val t = ((impactSpeed - squashMinImpact) / (maxThrowSpeed - squashMinImpact))
            .coerceIn(0f, 1f)
        return 1f - (1f - SQUASH_MIN_SCALE) * t
    }

    /**
     * **Q 弹挤压曲线**：进度 `u` ∈ [0,1] → 竖直缩放。
     *
     * 逐字移植 dsh-pet 的 `squashScale`（`src/shared/physics.ts`），两端共用同一条
     * 曲线，所以"点击回应"与"落地"的手感严格一致：
     * - 下压段（0 ~ 0.45）：ease-in（`p²`）压到 [squash]；
     * - 回弹段（0.45 ~ 1）：easeOutBack 弹回 1.0，**并过冲**到约 1.12
     *   （先压扁、再略微拉长，最后稳定）—— 这个过冲就是"Q 弹"的来源，
     *   单调弹回的曲线会显得很硬。
     *
     * ⚠️ **必须从 1.0 开始压下去，不能从最扁处往回弹。** 电脑上的点击反馈是
     *   "啪地压扁再弹回来"，而 [playSquash] 原来的写法是拿最扁值当动画起点 ——
     *   少了"压下去"那半段，观感从"被捏了一下"变成"莫名其妙从扁的弹回"。
     *
     * @param u 动画进度（0 = 刚开始，1 = 结束）。
     * @param squash 下压到的最低比例（默认 [SQ_CLICK_DEPTH]）。
     */
    fun squashCurve(u: Float, squash: Float = SQ_CLICK_DEPTH): Float {
        val p = u.coerceIn(0f, 1f)
        if (p < SQ_DOWN_RATIO) {
            val k = p / SQ_DOWN_RATIO
            return 1f - (1f - squash) * k * k
        }
        val k = (p - SQ_DOWN_RATIO) / (1f - SQ_DOWN_RATIO)
        // easeOutBack：c1 是标准的"回弹过冲"系数（1.70158 即过冲约 10%）。
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val f = 1f + c3 * (k - 1f) * (k - 1f) * (k - 1f) + c1 * (k - 1f) * (k - 1f)
        return minOf(SQ_OVERSHOOT_MAX, squash + (1f - squash) * maxOf(f, 0f))
    }

    /**
     * 轨迹点容器：只保留最近的若干个，避免长按拖动时无限增长。
     *
     * ⚠️ 用 `ArrayDeque` 而不是 `List` + 反复 `filter`：拖动时每次 MOVE 都要
     *   采样（一秒上百次），每次都重建列表会产生可观的垃圾。
     */
    class Trail {
        private val points = ArrayDeque<Triple<Float, Float, Long>>()

        fun add(x: Float, y: Float, t: Long) {
            points.addLast(Triple(x, y, t))
            // 丢掉落点太旧的：留 [TRAIL_KEEP_MS] 的两倍余量，避免边界上刚好没点。
            while (points.size > 2 && t - points.first().third > TRAIL_KEEP_MS * 2) {
                points.removeFirst()
            }
            // 硬上限，防止异常情况下无限增长。
            while (points.size > 120) points.removeFirst()
        }

        fun clear() = points.clear()

        fun snapshot(): List<Triple<Float, Float, Long>> = points.toList()

        val size: Int get() = points.size
    }
}
