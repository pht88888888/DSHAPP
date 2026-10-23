package com.dsh.android.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **位置能力** —— 单次定位与显式持续订阅。
 *
 * ## 设计说明 §4.14.1a 的要求
 *
 * > 位置 | **单次定位**，用户**显式启动**的持续订阅及停止 |
 * > **定位关闭、超时、精度/来源说明、版本后台限制**；
 * > **持续订阅不等于允许后台定位**
 *
 * ## ⚠️ 最要紧的一条：「持续订阅 ≠ 后台定位」
 *
 * 用户点了「开始持续定位」，含义是「在我看着的时候给我位置」，
 * **不是**「允许你在后台一直定位」。
 *
 * 两者在 Android 上是**不同的权限**：
 *
 * | 权限 | 含义 |
 * |---|---|
 * | `ACCESS_FINE_LOCATION` | 前台定位 |
 * | `ACCESS_BACKGROUND_LOCATION` | 后台定位（API 29+，需单独申请） |
 *
 * 本实现**刻意不申请后台定位** —— 文档要求「按需发现和授权」，
 * 而后台定位是**极高敏感度**的权限（用户看到会警惕）。
 *
 * 所以 [ContinuousSession] 在检测到 App 退到后台时**自动暂停**，
 * 回前台再恢复。这不是「功能缺失」，而是文档要求的行为。
 *
 * ## ⚠️ 为什么用 LocationManager 而不是 FusedLocation
 *
 * FusedLocation 需要 `play-services-location`（约 1 MB + 依赖 Google Play 服务）。
 * 而本项目 targetSdk=28、侧载分发，不假设 Play 服务可用。
 * `LocationManager` 是系统 API，**零依赖**。
 *
 * 代价是精度不如 Fused（它融合了 Wi-Fi/基站），但对「知道自己在哪」够用。
 */
object LocationCapability {

    private const val TAG = "DSH"

    /**
     * 单次定位的超时（毫秒）。
     *
     * ⚠️ 必须有超时：室内/GPS 弱信号时 `requestSingleUpdate` 可能**永不回调**。
     *   不设超时的话调用方会一直等，UI 上表现为「一直转圈」。
     *
     * 30 秒是 GPS 冷启动的典型上限。
     */
    const val SINGLE_TIMEOUT_MS = 30_000L

    /** 持续订阅的最小间隔（毫秒）。 */
    const val CONTINUOUS_MIN_INTERVAL_MS = 5_000L

    /** 持续订阅的最小位移（米）。0 = 不按位移过滤。 */
    const val CONTINUOUS_MIN_DISTANCE_M = 10f

    /**
     * 一次定位结果。
     *
     * ⚠️ 文档要求「**精度/来源说明**」—— 所以除了经纬度，
     *   还必须带上精度与来源，否则调用方无法判断「这个位置可信吗」。
     */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** 水平精度（米）。越小越准。 */
        val accuracyM: Float,
        /** 来源：`gps` / `network` / `passive` / `fused` */
        val provider: String,
        /** 时间戳（毫秒）。 */
        val timeMs: Long,
        /** 海拔（米）；不支持时为 null。 */
        val altitudeM: Double? = null,
        /** 速度（米/秒）；不支持时为 null。 */
        val speedMps: Float? = null,
    ) {
        /** 人可读的来源说明（给 UI 用）。 */
        fun sourceLabel(): String = when (provider) {
            LocationManager.GPS_PROVIDER -> "GPS（较准，室外更快）"
            LocationManager.NETWORK_PROVIDER -> "网络（室内可用，精度较低）"
            LocationManager.PASSIVE_PROVIDER -> "被动（借其他应用的结果）"
            else -> provider
        }

        /** 精度分级（给 UI 用）。 */
        fun accuracyLabel(): String = when {
            accuracyM <= 10 -> "高精度（±${accuracyM.toInt()}m）"
            accuracyM <= 50 -> "中等（±${accuracyM.toInt()}m）"
            accuracyM <= 200 -> "较低（±${accuracyM.toInt()}m）"
            else -> "很低（±${accuracyM.toInt()}m，可能来自基站）"
        }
    }

    /**
     * 单次定位的结果。
     */
    sealed interface SingleResult {
        data class Ok(val fix: Fix) : SingleResult

        /** 没有位置权限。 */
        data object NoPermission : SingleResult

        /** 系统定位服务被关闭（用户关了「位置信息」）。 */
        data object LocationDisabled : SingleResult

        /** 超时（信号弱 / 室内）。 */
        data object Timeout : SingleResult

        /** 其他失败。 */
        data class Failed(val reason: String) : SingleResult
    }

    /**
     * 单次定位。
     *
     * ## ⚠️ 三件事必须做
     *
     * 1. **先查定位服务是否开着** —— 关着时 `requestSingleUpdate` 不回调，
     *    调用方会白等 30 秒
     * 2. **必须有超时** —— 见 [SINGLE_TIMEOUT_MS]
     * 3. **拿到结果立刻移除监听** —— 不移除的话 LocationManager 会一直
     *    给我们回调（耗电），而调用方只想要一次
     *
     * @param granted 权限判定（由调用方提供，便于纯逻辑测试）
     * @param onResult 回调（主线程）。**无论成功失败都会调一次**。
     */
    fun requestSingle(
        context: Context,
        granted: (String) -> Boolean,
        onResult: (SingleResult) -> Unit,
    ) {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            EventLog.record("device.location.noPermission")
            onResult(SingleResult.NoPermission)
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onResult(SingleResult.Failed("本机无定位服务"))
            return
        }

        // ① 定位服务是否开着
        if (!isLocationEnabled(lm)) {
            EventLog.record("device.location.disabled")
            onResult(SingleResult.LocationDisabled)
            return
        }

        val main = android.os.Handler(Looper.getMainLooper())
        // ⚠️ 用数组而不是 var 捕获：Kotlin 的 lambda 不能改外层局部变量，
        //   而这里需要在两个回调之间共享「是否已完成」的状态。
        val done = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!done.compareAndSet(false, true)) return
                runCatching { lm.removeUpdates(this) }
                EventLog.record(
                    "device.location.ok",
                    mapOf(
                        "provider" to (location.provider ?: "unknown"),
                        "acc" to location.accuracy.toInt().toString(),
                    ),
                )
                main.post { onResult(SingleResult.Ok(location.toFix())) }
            }

            // API 28 及以下需要实现这三个（否则编译报错）
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                // ⚠️ 定位在等待期间被关掉 —— 立即返回，不等到超时
                if (!done.compareAndSet(false, true)) return
                runCatching { lm.removeUpdates(this) }
                main.post { onResult(SingleResult.LocationDisabled) }
            }
        }

        // ② 超时兜底
        val timeout = Runnable {
            if (!done.compareAndSet(false, true)) return@Runnable
            runCatching { lm.removeUpdates(listener) }
            Log.w(TAG, "单次定位超时（${SINGLE_TIMEOUT_MS}ms）")
            EventLog.record("device.location.timeout")
            onResult(SingleResult.Timeout)
        }
        main.postDelayed(timeout, SINGLE_TIMEOUT_MS)

        // ③ 发起请求
        try {
            // ⚠️ 优先用 GPS（更准），但它室内可能永远不回调；
            //   Network 更快但精度低。两个都请求，谁先来用谁。
            var requested = false
            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )) {
                if (!lm.isProviderEnabled(provider)) continue
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    requested = true
                } catch (t: SecurityException) {
                    // 某个 provider 没权限（比如只有 COARSE 时 GPS 不可用）
                    Log.d(TAG, "provider $provider 无权限: ${t.message}")
                } catch (t: Throwable) {
                    Log.d(TAG, "provider $provider 请求失败: ${t.message}")
                }
            }
            if (!requested) {
                if (!done.compareAndSet(false, true)) return
                main.removeCallbacks(timeout)
                onResult(SingleResult.Failed("没有可用的定位源（GPS 与网络都不可用）"))
            }
        } catch (t: SecurityException) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            EventLog.record("device.location.noPermission")
            onResult(SingleResult.NoPermission)
        } catch (t: Throwable) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            onResult(SingleResult.Failed(t.message ?: "请求失败"))
        }
    }

    /**
     * 持续订阅会话。
     *
     * ## ⚠️ 文档核心要求：「持续订阅 ≠ 后台定位」
     *
     * 用户点了「开始」，含义是「在我看着的时候给我位置」，
     * **不是**「允许你在后台一直定位」。
     *
     * 所以本类提供 [onAppBackground] / [onAppForeground]：
     * 退后台**自动暂停**，回前台**自动恢复**。
     *
     * ⚠️ 这不是「功能缺失」—— 后台定位是 `ACCESS_BACKGROUND_LOCATION`
     *   权限（API 29+），本应用**刻意不申请**（文档「按需发现和授权」）。
     */
    class ContinuousSession internal constructor(
        private val context: Context,
        private val onFix: (Fix) -> Unit,
        private val onEnd: (EndReason) -> Unit,
    ) {
        private var lm: LocationManager? = null
        private var listener: LocationListener? = null
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private val pausedByBackground = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 已收到的位置数。 */
        @Volatile var fixCount: Long = 0
            private set

        /**
         * 启动订阅。
         *
         * @return null = 成功；非 null = 失败原因
         */
        fun start(): String? {
            if (running.get()) return "已在订阅中"

            val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return "本机无定位服务"
            if (!isLocationEnabled(mgr)) return "定位服务已关闭"

            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!running.get() || pausedByBackground.get()) return
                    fixCount++
                    runCatching { onFix(location.toFix()) }
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    // 定位被关掉 ⇒ 立即结束，而不是静默地不再回调
                    // （后者会让调用方以为「用户移动很少」）
                    if (!running.getAndSet(false)) return
                    runCatching { mgr.removeUpdates(this) }
                    EventLog.record("device.location.continuous.disabled")
                    onEnd(EndReason.LocationDisabled)
                }
            }

            return try {
                var requested = false
                for (provider in listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                )) {
                    if (!mgr.isProviderEnabled(provider)) continue
                    try {
                        mgr.requestLocationUpdates(
                            provider,
                            CONTINUOUS_MIN_INTERVAL_MS,
                            CONTINUOUS_MIN_DISTANCE_M,
                            l,
                            Looper.getMainLooper(),
                        )
                        requested = true
                    } catch (t: SecurityException) {
                        Log.d(TAG, "持续订阅 provider $provider 无权限")
                    }
                }
                if (!requested) return "没有可用的定位源"

                lm = mgr
                listener = l
                running.set(true)
                EventLog.record(
                    "device.location.continuous.start",
                    mapOf(
                        "intervalMs" to CONTINUOUS_MIN_INTERVAL_MS.toString(),
                        "minDistM" to CONTINUOUS_MIN_DISTANCE_M.toString(),
                    ),
                )
                null
            } catch (t: SecurityException) {
                "没有定位权限"
            } catch (t: Throwable) {
                t.message ?: "启动失败"
            }
        }

        /**
         * App 退到后台：**暂停**（文档：持续订阅 ≠ 后台定位）。
         *
         * ⚠️ 用 `removeUpdates` 真正停掉，而不是只忽略回调 ——
         *   后者仍会让系统持续定位，耗电且用户会看到定位图标。
         */
        fun onAppBackground() {
            if (!running.get() || pausedByBackground.getAndSet(true)) return
            runCatching { listener?.let { lm?.removeUpdates(it) } }
            EventLog.record("device.location.continuous.paused")
            Log.i(TAG, "持续定位：退后台，已暂停")
        }

        /** App 回到前台：恢复。 */
        fun onAppForeground() {
            if (!running.get() || !pausedByBackground.getAndSet(false)) return
            val mgr = lm ?: return
            val l = listener ?: return
            runCatching {
                for (provider in listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                )) {
                    if (!mgr.isProviderEnabled(provider)) continue
                    mgr.requestLocationUpdates(
                        provider, CONTINUOUS_MIN_INTERVAL_MS, CONTINUOUS_MIN_DISTANCE_M, l,
                        Looper.getMainLooper(),
                    )
                }
            }
            EventLog.record("device.location.continuous.resumed")
            Log.i(TAG, "持续定位：回前台，已恢复")
        }

        /** 主动停止（用户显式动作）。 */
        fun stop() {
            if (!running.getAndSet(false)) return
            runCatching { listener?.let { lm?.removeUpdates(it) } }
            listener = null
            lm = null
            EventLog.record(
                "device.location.continuous.stop",
                mapOf("fixCount" to fixCount.toString()),
            )
            onEnd(EndReason.Stopped)
        }

        fun isRunning(): Boolean = running.get()
        fun isPausedByBackground(): Boolean = pausedByBackground.get()
    }

    /** 订阅结束原因。 */
    sealed interface EndReason {
        val id: String

        /** 用户主动停止。 */
        data object Stopped : EndReason { override val id = "stopped" }

        /** 系统定位被关闭。 */
        data object LocationDisabled : EndReason { override val id = "location-disabled" }
    }

    /**
     * 系统定位服务是否开启。
     *
     * ⚠️ 必须单独判：`requestLocationUpdates` 在定位关闭时**不抛错也不回调**，
     *   调用方会白等到超时。提前判能立即给出明确结论。
     */
    fun isLocationEnabled(lm: LocationManager): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            // API 26-27：没有 isLocationEnabled，只能查 provider
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (t: Throwable) {
        false
    }

    /** `Location` → [Fix]。 */
    private fun Location.toFix(): Fix = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else -1f,
        provider = provider ?: "unknown",
        timeMs = time,
        altitudeM = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed else null,
    )

    /**
     * 当前权限状态（给 UI 用）。
     *
     * ⚠️ 区分「精确」与「粗略」：用户可能只给了粗略位置，
     *   那时 GPS 用不了，只能拿网络定位 —— 精度差很多，
     *   必须在 UI 上说明，否则用户会以为「定位坏了」。
     */
    enum class PermissionLevel(val id: String, val label: String) {
        /** 精确位置（GPS 可用）。 */
        FINE("fine", "精确位置"),

        /** 仅粗略位置（只有网络定位）。 */
        COARSE("coarse", "仅大致位置"),

        /** 无权限。 */
        NONE("none", "无定位权限"),
        ;
    }

    fun permissionLevel(granted: (String) -> Boolean): PermissionLevel = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionLevel.FINE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionLevel.COARSE
        else -> PermissionLevel.NONE
    }

    /**
     * 后台定位是否已授权。
     *
     * ⚠️ 本应用**不申请**这个权限（文档「按需发现和授权」），
     *   但提供查询是为了**如实告知用户**「本应用不做后台定位」——
     *   而不是让用户去猜。
     */
    fun hasBackgroundLocation(granted: (String) -> Boolean): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // API < 29 没有独立的后台定位权限（前台权限即涵盖）
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：单次定位（含超时/定位关闭/无权限）、持续订阅（显式启停）、
     *          退后台自动暂停（持续订阅 ≠ 后台定位）、精度与来源说明、
     *          权限分级（精确/粗略/无）
     * ❌ 未做：
     *   - **`getLastKnownLocation` 快速路径**（有缓存时能立即返回，省掉等待）
     *   - **地理围栏**（Geofencing）
     *   - **位置数据的存储与回溯**（当前只回调，不落盘）
     *   - 「版本后台限制」的完整适配（Android 12+ 的后台定位限制更严，
     *     本实现靠「退后台暂停」绕开，不依赖系统策略）
     */
}
