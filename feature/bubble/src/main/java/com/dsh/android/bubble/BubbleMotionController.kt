package com.dsh.android.bubble

/**
 * 一次抛掷的积分状态与时间。窗口的实际坐标仍归宿主；本类只输出下一帧位置。
 * 调用者提供当前屏幕/IME 边界，保持键盘下方起飞时不立即被夹到地面的语义。
 */
internal class BubbleMotionController(private val physics: BubblePhysics) {
    data class Bounds(val maxX: Float, val minY: Float, val floorY: Float)
    data class Frame(
        val x: Float,
        val y: Float,
        val landed: Boolean,
        val impactSpeed: Float,
        val resting: Boolean,
    )

    private var state: BubblePhysics.State? = null
    private var lastFrameNs = 0L
    private var floorOverride: Float? = null
    val isRunning: Boolean get() = state != null

    fun start(x: Float, y: Float, vx: Float, vy: Float, nowNs: Long, floorY: Float?) {
        state = BubblePhysics.State(x, y, vx, vy)
        lastFrameNs = nowNs
        floorOverride = floorY
    }

    fun step(nowNs: Long, bounds: Bounds): Frame? {
        val st = state ?: return null
        val dt = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, BubblePhysics.MAX_STEP_DT)
        lastFrameNs = nowNs
        val floor = (floorOverride ?: bounds.floorY).coerceAtLeast(bounds.minY)
        val risingOutOfFloor = st.y > floor && st.vy < 0f
        val result = physics.step(
            st, dt,
            minX = 0f,
            minY = bounds.minY,
            maxX = bounds.maxX,
            maxY = if (risingOutOfFloor) Float.POSITIVE_INFINITY else floor,
        )
        if (result.resting) stop()
        return Frame(st.x, st.y, result.landed, result.impactSpeed, result.resting)
    }

    fun stop() {
        state = null
        floorOverride = null
        lastFrameNs = 0L
    }
}
