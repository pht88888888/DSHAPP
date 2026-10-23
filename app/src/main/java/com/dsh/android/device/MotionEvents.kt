package com.dsh.android.device

import android.hardware.SensorManager
import android.util.Log
import com.dsh.android.log.EventLog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * **语义事件检测器** —— 从原始加速度/重力数据识别「翻转、抬腕、静止」。
 *
 * ## 设计说明 §4.14
 *
 * > 动作与传感器 | **摇动、姿态、旋转等语义事件**；受限原始采样流。
 * > 活动识别与普通运动传感器**分别判断**系统权限、硬件支持和版本限制。
 *
 * ## ⚠️ 为什么需要语义层（插件不该自己解析原始数据）
 *
 * 原始加速度计给的是 `[x, y, z]`（m/s²）。从它推出「用户翻转了手机」
 * 需要：低通滤波 → 求重力方向 → 判断象限变化 → 去抖。
 *
 * 每个插件都自己写一遍的话：
 * - 阈值各不相同 → 用户体验不一致
 * - 大多数会漏掉「去抖」→ 稍微晃一下就触发
 * - 少数会用错坐标系 → 在横屏时判反
 *
 * 所以语义层由基座统一实现，插件只订阅「翻转了」这个事件。
 *
 * ## ⚠️ 三个必须做对的点
 *
 * **① 必须用重力（或低通滤波后的加速度），不能用原始加速度**
 *
 * 原始加速度包含**运动分量**（用户走路、摇动）。直接用会让「走两步」
 * 被识别成「翻转」。所以要么用 `TYPE_GRAVITY` 传感器（系统已滤波），
 * 要么自己对加速度做低通滤波。
 *
 * **② 必须去抖（滞回）**
 *
 * 判断「屏幕朝下」用阈值 `z < -0.7g`。但手抖会让 z 在 -0.69 与 -0.71
 * 之间抖动 → 反复触发。所以要**滞回**：进入用 -0.7，退出用 -0.5。
 *
 * **③ 必须防抖（时间窗）**
 *
 * 即使有滞回，用户快速翻来翻去也会连续触发。所以要一个最小间隔。
 */
object MotionEvents {

    private const val TAG = "DSH"

    /** 重力加速度（m/s²）。 */
    private const val G = 9.81f

    /**
     * 「屏幕朝下」的进入阈值（z 轴重力分量 / g）。
     *
     * ⚠️ 用 -0.7 而不是 -1.0：手机很少完全水平朝下，
     *   拿在手里通常有 20~40° 的倾斜。要求 -1.0 的话永远触发不了。
     */
    private const val FLIP_ENTER = -0.7f

    /**
     * 「屏幕朝下」的退出阈值（滞回）。
     *
     * ⚠️ 必须与 [FLIP_ENTER] 有间隔：两者相同的话，z 在阈值附近抖动
     *   会反复触发（手抖一下就是一个事件）。
     */
    private const val FLIP_EXIT = -0.5f

    /** 两次同类事件的最小间隔（毫秒）。 */
    private const val DEBOUNCE_MS = 800L

    /** 判定「静止」的加速度变化阈值（m/s²）。 */
    private const val STILL_THRESHOLD = 0.15f

    /** 判定「静止」需要持续多久（毫秒）。 */
    private const val STILL_DURATION_MS = 1_500L

    /**
     * 「抬腕」的角度阈值（度）。
     *
     * 抬腕的几何定义：手机从「屏幕朝下/侧放」变成「屏幕朝上且顶部朝上」。
     * 用 y 轴（竖直方向）与 z 轴（屏幕法线）的夹角判断。
     */
    private const val LIFT_ANGLE_DEG = 45f

    /**
     * 一个语义事件。
     *
     * @param event 事件类型
     * @param atMs 发生时刻
     * @param detail 附加信息（诊断用）
     */
    data class Occurrence(
        val event: SensorCapability.Event,
        val atMs: Long,
        val detail: String = "",
    )

    /**
     * 检测器 —— 喂原始采样，吐语义事件。
     *
     * ⚠️ **有状态**：每次使用要 new 一个（或用 [reset]），
     *   因为滞回与去抖都依赖上一帧的状态。
     */
    class Detector(
        private val onEvent: (Occurrence) -> Unit,
    ) {
        /** 上次 z 分量（用于滞回）。 */
        private var lastZ = 0f
        /** 是否处于「朝下」状态（滞回的另一半）。 */
        private var facingDown = false
        /**
         * 上次同类事件时刻（去抖）。
         *
         * ⚠️ **初始值用 `Long.MIN_VALUE / 2` 而不是 0**：
         *   用 0 的话，第一帧（`nowMs` 可能只有几百毫秒，如刚开机）
         *   会被判成「距离上次不到 800ms」而**吞掉第一个事件**。
         *
         *   实测暴露过这个问题：喂 8 帧、第一帧就在 200ms 处的翻转
         *   被静默丢弃，看起来像「翻转检测不工作」。
         */
        private var lastFlipAt = Long.MIN_VALUE / 2
        private var lastLiftAt = Long.MIN_VALUE / 2

        /** 静止判定的起点时刻（0 = 未开始判定）。 */
        private var stillSince = 0L
        /** 上一帧的加速度模长（用于算变化量）。 */
        private var lastMagnitude = -1f
        /** 是否已经报过「静止」（避免持续重复报）。 */
        private var stillReported = false

        /**
         * 喂一帧**重力/低通滤波后**的加速度。
         *
         * @param x/y/z 加速度分量（m/s²）。**必须是已去运动分量的**——
         *   用 `TYPE_GRAVITY` 或自己对 `TYPE_ACCELEROMETER` 做低通滤波。
         * @param nowMs 当前时刻
         *
         * ⚠️ 传原始加速度进来的话，「走路」会被识别成「翻转」。
         */
        fun feed(x: Float, y: Float, z: Float, nowMs: Long) {
            // 归一化成「g 的倍数」，便于跨设备统一阈值
            val nx = x / G
            val ny = y / G
            val nz = z / G

            detectFlip(nz, nowMs)
            detectLift(nx, ny, nz, nowMs)
            detectStill(x, y, z, nowMs)

            lastZ = nz
        }

        /**
         * 翻转检测（屏幕朝下 ↔ 朝上）。
         *
         * ⚠️ 用**滞回**：进入 -0.7、退出 -0.5。
         *   两者相同的话，z 在阈值附近抖动会反复触发。
         */
        private fun detectFlip(nz: Float, nowMs: Long) {
            if (!facingDown && nz < FLIP_ENTER) {
                // 进入「朝下」
                facingDown = true
                if (nowMs - lastFlipAt >= DEBOUNCE_MS) {
                    lastFlipAt = nowMs
                    emit(SensorCapability.Event.FLIP, nowMs, "屏幕朝下（z=${fmt(nz)}）")
                }
            } else if (facingDown && nz > FLIP_EXIT) {
                // 退出「朝下」—— **不报事件**（翻转是「朝下」这个动作，
                // 翻回来不算一次新的翻转；否则一次翻转会报两次）
                facingDown = false
            }
        }

        /**
         * 抬腕检测。
         *
         * ## 几何定义
         *
         * 「抬腕」= 手机从**水平或朝下**变成**屏幕朝上且顶部朝上**。
         *
         * 判据：z 分量 > cos(45°) ≈ 0.7（屏幕大致朝上），
         * 且 y 分量 < 0（顶部朝上；Android 坐标系里 y 正方向是屏幕顶部）。
         *
         * ⚠️ 为什么还要 y：只判 z 的话，「平放在桌上」也会触发 ——
         *   而那不是「抬腕」（用户没抬起来看）。
         */
        private fun detectLift(nx: Float, ny: Float, nz: Float, nowMs: Long) {
            if (nowMs - lastLiftAt < DEBOUNCE_MS) return
            val cosThreshold = kotlin.math.cos(Math.toRadians(LIFT_ANGLE_DEG.toDouble())).toFloat()
            // z 足够大（屏幕朝上）+ y 为负（顶部朝上）+ 之前不是这个姿态
            val isLifted = nz > cosThreshold && ny < -0.3f
            val wasLifted = lastZ > cosThreshold && false   // 上一帧是否已抬起
            if (isLifted && !wasLifted && lastZ < 0f) {
                // ⚠️ 额外要求 `lastZ < 0`（之前屏幕朝下/侧放）：
                //   否则「一直拿在手里」会每 800ms 报一次抬腕。
                lastLiftAt = nowMs
                emit(
                    SensorCapability.Event.LIFT, nowMs,
                    "抬起（z=${fmt(nz)}, y=${fmt(ny)}）",
                )
            }
        }

        /**
         * 静止检测。
         *
         * ⚠️ 需要**持续** [STILL_DURATION_MS] 才报：
         *   单帧加速度接近重力是正常的（匀速运动时也是）。
         *   只有持续「没有变化」才是真静止。
         */
        private fun detectStill(x: Float, y: Float, z: Float, nowMs: Long) {
            val mag = sqrt(x * x + y * y + z * z)
            if (lastMagnitude < 0) {
                lastMagnitude = mag
                return
            }
            val delta = abs(mag - lastMagnitude)
            lastMagnitude = mag

            if (delta < STILL_THRESHOLD) {
                // 变化小 ⇒ 可能静止
                if (stillSince == 0L) stillSince = nowMs
                if (!stillReported && nowMs - stillSince >= STILL_DURATION_MS) {
                    stillReported = true
                    emit(SensorCapability.Event.STILL, nowMs, "静止 ${(nowMs - stillSince)}ms")
                }
            } else {
                // 有变化 ⇒ 重置
                stillSince = 0L
                if (stillReported) {
                    stillReported = false
                    // ⚠️ 不报「开始移动」事件：`Event` 里没有这个类型，
                    //   而凭空加一个会让订阅方措手不及。调用方靠「没有 STILL
                    //   事件了」自行推断。
                }
            }
        }

        private fun emit(event: SensorCapability.Event, atMs: Long, detail: String) {
            Log.d(TAG, "语义事件: ${event.id}（$detail）")
            EventLog.record(
                "device.sensor.event",
                mapOf("event" to event.id, "detail" to detail),
            )
            runCatching { onEvent(Occurrence(event, atMs, detail)) }
        }

        /** 重置状态（重新订阅时调）。 */
        fun reset() {
            lastZ = 0f
            facingDown = false
            // ⚠️ 与字段初始化一致：不能是 0（见字段的说明）
            lastFlipAt = Long.MIN_VALUE / 2
            lastLiftAt = Long.MIN_VALUE / 2
            stillSince = 0L
            lastMagnitude = -1f
            stillReported = false
        }

        private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
    }

    /**
     * **低通滤波**（当设备没有 `TYPE_GRAVITY` 时用）。
     *
     * ## ⚠️ 为什么需要它
     *
     * `TYPE_GRAVITY` 是**可选**传感器 —— 有些低端设备没有。
     * 那时只能自己对 `TYPE_ACCELEROMETER` 滤波：加速度 = 重力 + 运动，
     * 低通后运动分量被滤掉，剩下的近似重力。
     *
     * ⚠️ 系数 [alpha] 越小越平滑但延迟越大：
     * - 0.8：延迟小，但运动分量滤不干净（走路会被当成翻转）
     * - 0.98：很平滑，但翻转要 1 秒后才反应过来
     *
     * 取 0.9 是平衡点（约 0.2 秒响应）。
     */
    class GravityFilter(private val alpha: Float = 0.9f) {
        private var gx = 0f
        private var gy = 0f
        private var gz = 0f
        private var primed = false

        /**
         * 喂一帧原始加速度，返回滤波后的重力估计。
         *
         * @return `[gx, gy, gz]`
         */
        fun feed(x: Float, y: Float, z: Float): FloatArray {
            if (!primed) {
                // 第一帧直接当作重力（避免从 0 慢慢爬升）
                gx = x; gy = y; gz = z
                primed = true
                return floatArrayOf(gx, gy, gz)
            }
            gx = alpha * gx + (1 - alpha) * x
            gy = alpha * gy + (1 - alpha) * y
            gz = alpha * gz + (1 - alpha) * z
            return floatArrayOf(gx, gy, gz)
        }

        fun reset() {
            gx = 0f; gy = 0f; gz = 0f; primed = false
        }
    }

    /**
     * 推荐的传感器组合。
     *
     * ⚠️ 优先 `TYPE_GRAVITY`（系统已滤波，更准），没有则退回
     *   `TYPE_ACCELEROMETER` + [GravityFilter]。
     *
     * 文档要求「活动识别与普通运动传感器**分别判断**系统权限、硬件支持」——
     * 所以这里返回「实际可用的组合」而不是「想要的组合」。
     */
    fun recommendedSensors(context: android.content.Context): List<Int> {
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? SensorManager ?: return emptyList()
        val hasGravity = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) != null
        return if (hasGravity) {
            listOf(android.hardware.Sensor.TYPE_GRAVITY)
        } else if (sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null) {
            listOf(android.hardware.Sensor.TYPE_ACCELEROMETER)
        } else {
            emptyList()
        }
    }

    /** 本机是否支持语义事件检测（有重力或加速度计）。 */
    fun isSupported(context: android.content.Context): Boolean =
        recommendedSensors(context).isNotEmpty()

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：翻转（滞回）、抬腕（几何判据）、静止（持续判定）、
     *          去抖、低通滤波（无重力传感器时）、可用传感器探测
     * ❌ 未做：
     *   - **摇动**（已有独立实现 [com.dsh.android.ShakeDetector]，
     *     它用的是「积分速度」而非「重力方向」，两套算法适用场景不同：
     *     摇动看**运动**，翻转/抬腕看**姿态**）
     *   - 「旋转」（文档提到，但需要陀螺仪，且语义定义模糊）
     *   - 「活动识别」（走路/跑步/骑车 —— 需要 `ActivityRecognition` API
     *     或 ML 模型，文档说「分别判断系统权限」暗示它是独立能力）
     *   - 屏幕方向补偿（横屏时 x/y 轴含义会变，当前按竖屏算）
     */
}
