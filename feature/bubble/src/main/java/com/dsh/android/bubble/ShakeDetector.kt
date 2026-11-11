package com.dsh.android.bubble
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把手机本身的移动变成悬浮球的相对运动 —— 球像装在一个**透明的盒子里**。
 *
 * ## 用户要的手感（原话）
 *
 * 「我们屏幕不是有 4 个边框吗，比如我手机快速往上移动的时候，那我底部的边框不就
 * 会撞到我的悬浮球吗？然后悬浮球应该就会被底部撞飞，而且速度极快。上下左右边框，
 * 道理都一样。」
 *
 * 这句话定义的不是「摇一摇」，而是**惯性**：
 *
 * - 手机快速往**上**移 ⇒ 屏幕这个盒子往上走 ⇒ 球被**底部边框**从下方撞上 ⇒ 球往**上**飞；
 * - 手机快速往**下**移 ⇒ 球被**顶部边框**撞 ⇒ 球往**下**飞；
 * - 左右同理。
 *
 * 也就是说：**球的世界方向速度 = −手机速度**（球因为惯性留在原地，盒子撞它）。
 * 这正是旧实现的根本错误 —— 旧版把系数写成了 `vx = -ax`（一次取反）又额外
 * 取反了一次手感，而且只在"晃过阈值"时给一次速度，完全没有"边框撞"的概念。
 *
 * ## 为什么用加速度计做「积分」而不是直接用瞬时加速度
 *
 * 要得到「手机此刻往哪边走、走多快」，必须把**线性加速度对时间积分**成速度。
 * 直接用瞬时加速度当速度方向，会变成"往右加速的瞬间球往左飞"——手感对，
 * 但速度量级完全错（加速结束的那一刻手机还在高速移动，球却停了）。
 *
 * 所以这里维护一个 `velocity`（手机自身速度，m/s），每个传感器采样点：
 *
 * ```
 * velocity += linearAccel * dt      // 积分
 * velocity *= DAMPING               // 衰减，防止漂移
 * ```
 *
 * ⚠️ **必须衰减**：真实积分会漂移（传感器零偏、手机静止时 `velocity` 不为 0），
 *   越积越离谱。`DAMPING` 让"匀速移动"时速度慢慢归零 —— 这在体感上是对的，
 *   因为人手持续匀速移动本来就少见，而爆炸性的"顿一下"才是会撞飞球的动作。
 *
 * ## 触发条件
 *
 * 手机速度超过 [MIN_PHONE_SPEED] 才把球甩出去。日常拿手机、走路都在这个值以下。
 * 甩出去之后进入 [COOLDOWN_MS] 冷却，避免一次拨动连发好几下。
 */
internal class ShakeDetector(
    private val context: Context,
) : SensorEventListener {

    companion object {
        private const val TAG = "DSH"

        /** 触发阈值：手机自身平移速度（m/s）。约 0.35 m/s ≈ 一次果断的挥手。 */
        const val MIN_PHONE_SPEED = 0.35f

        /** 两次触发之间的最短间隔（ms）。 */
        const val COOLDOWN_MS = 260L

        /**
         * 手机速度 → 球初速度的增益（px/s per m/s）。
         *
         * 用户要「速度极快」，所以要给得比 1:1 大得多：0.6 m/s 的手机速度
         * 乘 2600 就是 1560 px/s，屏幕上 1080px 宽的球会一路撞到对面边框。
         */
        const val GAIN = 2600f

        /** 球初速度上限（px/s），与 [BubblePhysics.MAX_THROW_SPEED] 同量级。 */
        const val MAX_SPEED = 3600f

        /**
         * 手机速度的衰减系数（每采样点）。
         *
         * `SENSOR_DELAY_GAME` 约 50Hz，0.90 意味着约 0.4 秒衰减到 1/10 ——
         * 对应"顿一下之后手机就不动了"的日常情形。
         */
        const val DAMPING = 0.90f

        /** 认为"手机静止"的速度下限（m/s），低于它直接清零，防止长期漂移。 */
        const val ZERO_SNAP = 0.05f

        /** 单次采样允许的最大 dt（秒）。防止 GC 停顿后一次性积出一个巨大速度。 */
        const val MAX_DT = 0.1f
    }

    /** 甩飞回调：(vx, vy) px/s，屏幕坐标系。已在主线程。 */
    var onShake: ((Float, Float) -> Unit)? = null

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var registered = false
    private var lastTriggerAt = 0L
    private var lastSampleNs = 0L

    /** 低通滤波后的重力分量（从读数里扣掉它得到线性加速度）。 */
    private val gravity = FloatArray(3)

    /**
     * 手机自身速度（m/s，设备坐标系）。每个采样点积分一次。
     *
     * ⚠️ 设备坐标系：竖屏时 x 向右、y 向上（屏幕坐标 y 向下，见下面的换算）。
     */
    private var phoneVx = 0f
    private var phoneVy = 0f

    /** 是否有可用传感器。false 时调用方直接跳过这个功能。 */
    fun isAvailable(): Boolean =
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** 开始监听。幂等。 */
    fun start() {
        if (registered) return
        val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        registered = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        if (registered) {
            // 用当前读数初始化重力估计，避免第一拍把重力当成加速度突变。
            gravity[0] = 0f; gravity[1] = 0f; gravity[2] = 9.8f
            phoneVx = 0f; phoneVy = 0f
            lastSampleNs = 0L
            Log.i(TAG, "惯性撞边已启动（触发速度 $MIN_PHONE_SPEED m/s，增益 $GAIN）")
        }
    }

    /** 停止监听。幂等。 */
    fun stop() {
        if (!registered) return
        sm?.unregisterListener(this)
        registered = false
    }

    /** 彻底释放。 */
    fun release() {
        stop()
        onShake = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val a = event.values

        // ── 1. 分离重力，得到线性加速度 ──────────────────────────────
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * a[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * a[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * a[2]

        val lx = a[0] - gravity[0]
        val ly = a[1] - gravity[1]
        // z（前后推）在屏幕上没有对应方向，不参与。

        // ── 2. 积分成手机速度 ────────────────────────────────────────
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastSampleNs == 0L) 0f
        else (((now - lastSampleNs).toFloat() / 1000f)).coerceIn(0f, MAX_DT)
        lastSampleNs = now

        if (dt > 0f) {
            phoneVx += lx * dt
            phoneVy += ly * dt
        }
        phoneVx *= DAMPING
        phoneVy *= DAMPING

        // 静止清零：不这么做，零偏会让速度一直挂着一点点，球会在"什么都没做"时乱飘。
        val phoneSpeed = sqrt(phoneVx * phoneVx + phoneVy * phoneVy)
        if (phoneSpeed < ZERO_SNAP) {
            phoneVx = 0f
            phoneVy = 0f
            return
        }

        if (phoneSpeed < MIN_PHONE_SPEED) return
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now

        // ── 3. 换算成球的初速度 ──────────────────────────────────────
        //
        // ## 方向推导（别凭直觉，凭推导）
        //
        // 物理图像：球在世界里基本**静止**（它有惯性），盒子在动。盒子往哪边走，
        // 就是它**那一侧的壁**撞上球，把球朝**同方向**推走。
        //
        // 类比：车突然往前加速，你被**靠背**（后壁）撞，人朝**前**飞。同向。
        //
        // 用用户给的例子验算：
        //   手机快速往**上**移 ⇒ 是**底部边框**从下往上撞球 ⇒ 球往**上**飞。
        //   与原话完全一致："我手机快速往上移动的时候，那我底部的边框不就会撞到
        //   我的悬浮球吗？然后悬浮球应该就会被底部撞飞。"
        //
        // ## 两个坐标系的换号
        //
        // 设备坐标系（加速度计）：x 向右、**y 向上**。
        // 屏幕坐标系（`moveTo`）：x 向右、**y 向下**。
        //
        // - x：两者同向 ⇒ `屏幕 vx = +phoneVx * GAIN`
        // - y：差一个负号 ⇒ `屏幕 vy = -phoneVy * GAIN`
        //
        // 验算 y：手机往上 ⇒ 设备 phoneVy > 0 ⇒ 屏幕 vy < 0 = 向上飞 ✓
        // 验算 x：手机往右 ⇒ 设备 phoneVx > 0 ⇒ 屏幕 vx > 0 = 向右飞 ✓
        //
        // ⚠️ 旧版写的是 `vx = -lx`、`vy = ly` —— x 那次取反是**错的**，
        //   会让"手机往右甩，球往左飞"，与用户的直觉相反。
        var vx = phoneVx * GAIN
        var vy = -phoneVy * GAIN

        // 限幅
        val speed = sqrt(vx * vx + vy * vy)
        if (speed > MAX_SPEED) {
            val k = MAX_SPEED / speed
            vx *= k
            vy *= k
        }

        Log.i(
            TAG,
            "惯性撞边: 手机速度=(${phoneVx.roundTo2()}, ${phoneVy.roundTo2()}) m/s " +
                "⇒ 球 v=(${vx.roundToInt()}, ${vy.roundToInt()}) px/s",
        )
        onShake?.invoke(vx, vy)

        // 触发后清零：一次"顿挫"只对应一次撞飞。不清零的话，速度会在衰减过程里
        // 持续高过阈值，冷却一结束又撞一次，球会连着飞。
        phoneVx = 0f
        phoneVy = 0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun Float.roundTo2(): String =
        ((this * 100).toInt() / 100f).toString()
}
