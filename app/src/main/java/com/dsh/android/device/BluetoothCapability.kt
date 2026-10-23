package com.dsh.android.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **蓝牙能力** —— 适配层、设备发现、BLE 连接与特征操作。
 *
 * ## 设计说明 §4.14.1a 的要求
 *
 * > 蓝牙 | **设备发现、连接生命周期**；**BLE 服务发现、特征读写和通知订阅** |
 * > **Android 版本对应授权、关闭/断连、超时、取消、释放**；
 * > **不承诺所有经典蓝牙传输和专有设备协议**
 *
 * ## ⚠️ 最后半句很重要
 *
 * 「**不承诺所有经典蓝牙传输和专有设备协议**」—— 所以本实现：
 *
 * | 做什么 | 不做什么 |
 * |---|---|
 * | BLE 扫描、连接、服务/特征读写、通知订阅 | 经典蓝牙的 SPP/A2DP 等**传输协议** |
 * | 通用的 GATT 操作 | 各厂商的**专有协议**（如某些手环的自定义帧格式） |
 *
 * 后者需要具体设备的协议文档，是**插件**该做的事，不是基座能力。
 *
 * ## ⚠️ API 31 的权限分水岭（最容易写错的地方）
 *
 * | API | 扫描权限 | 连接权限 |
 * |---|---|---|
 * | ≤ 30 | `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` | `BLUETOOTH` |
 * | ≥ 31 | `BLUETOOTH_SCAN` | `BLUETOOTH_CONNECT` |
 *
 * ⚠️ **旧版要求位置权限**是因为「扫描 BLE 能推断位置」——
 *   所以旧版上不授予位置权限就**扫不到任何设备**，而错误信息不会告诉你
 *   是缺位置权限（只报 `SecurityException` 或无回调）。
 *
 * 我们的 manifest 里 `BLUETOOTH_SCAN` 加了 `neverForLocation`，
 * 所以 API 31+ 不需要位置权限 —— 但**旧版仍需要**。
 */
object BluetoothCapability {

    private const val TAG = "DSH"

    /** 扫描默认超时（毫秒）。 */
    const val SCAN_TIMEOUT_MS = 15_000L

    /** 连接超时（毫秒）。 */
    const val CONNECT_TIMEOUT_MS = 15_000L

    /**
     * 发现的一个设备。
     */
    data class Device(
        /** MAC 地址（API 31+ 上未授权 CONNECT 时可能拿不到，见 [addressAvailable]）。 */
        val address: String,
        /** 设备名（可能为空）。 */
        val name: String?,
        /** 信号强度（dBm）。越小越近。 */
        val rssi: Int,
        /** 是否支持 BLE。 */
        val isLe: Boolean,
        /** 是否已配对（经典蓝牙）。 */
        val isBonded: Boolean,
        /** 最近一次广播时间（毫秒）。 */
        val lastSeenMs: Long,
    ) {
        /**
         * 地址是否可用。
         *
         * ⚠️ API 31+ 上没授 `BLUETOOTH_CONNECT` 时，`BluetoothDevice.getAddress()`
         *   返回 `02:00:00:00:00:00`（占位符）—— 而它**看起来像一个合法地址**。
         *   不判的话调用方会拿它去连接，然后得到一个莫名其妙的失败。
         */
        val addressAvailable: Boolean
            get() = address.isNotEmpty() && address != "02:00:00:00:00:00"
    }

    /**
     * 适配器状态。
     */
    enum class AdapterState(val id: String, val label: String) {
        /** 可用。 */
        READY("ready", "可用"),

        /** 蓝牙关闭。 */
        OFF("off", "蓝牙已关闭"),

        /** 本机无蓝牙硬件。 */
        NO_HARDWARE("no-hardware", "本机无蓝牙"),

        /** 没有权限。 */
        NO_PERMISSION("no-permission", "缺少蓝牙权限"),
        ;
    }

    /** 取适配器。 */
    fun adapter(context: Context): BluetoothAdapter? = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (t: Throwable) {
        Log.w(TAG, "取蓝牙适配器失败: ${t.message}")
        null
    }

    /**
     * 当前状态。
     *
     * @param granted 权限判定（由调用方提供，便于纯逻辑测试）
     */
    fun state(context: Context, granted: (String) -> Boolean): AdapterState {
        val a = adapter(context) ?: return AdapterState.NO_HARDWARE
        // ① 先判权限：没权限时 `isEnabled` 在 API 31+ 会抛 SecurityException
        if (!hasScanPermission(granted)) return AdapterState.NO_PERMISSION
        // ② 再判开关
        val enabled = try {
            a.isEnabled
        } catch (t: SecurityException) {
            return AdapterState.NO_PERMISSION
        }
        return if (enabled) AdapterState.READY else AdapterState.OFF
    }

    /**
     * 是否具备**扫描**权限（按 API 版本算）。
     *
     * ⚠️ 与 [DeviceCapability.requiredPerms] 同源，但这里单独判是因为
     *   扫描的权限要求与连接**不完全相同**（旧版扫描要位置权限，连接不要）。
     */
    fun hasScanPermission(granted: (String) -> Boolean): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(Manifest.permission.BLUETOOTH_ADMIN) &&
                granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 是否具备**连接**权限。 */
    fun hasConnectPermission(granted: (String) -> Boolean): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.BLUETOOTH)
        }

    /**
     * 扫描会话。
     *
     * ## ⚠️ 三个必须处理的异常
     *
     * | 情况 | 表现 | 处理 |
     * |---|---|---|
     * | 蓝牙关闭 | `startScan` 静默无回调 | 先判 [state]，不依赖回调 |
     * | 无权限 | `SecurityException` | 返回 [EndReason.NoPermission] |
     * | 扫描太频繁 | `startScan` 返回 false | 返回 [EndReason.Failed] |
     *
     * ⚠️ **必须有超时**：BLE 扫描是持续回调的，不设超时的话会一直扫下去 ——
     *   耗电且用户不知道为什么在扫。
     */
    class ScanSession internal constructor(
        private val context: Context,
        private val onDevice: (Device) -> Unit,
        private val onEnd: (EndReason) -> Unit,
    ) {
        private val running = AtomicBoolean(false)
        private var callback: ScanCallback? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        /** 已发现的设备（地址 → 设备，去重）。 */
        private val found = java.util.concurrent.ConcurrentHashMap<String, Device>()

        /** 已发现的不同设备数。 */
        fun deviceCount(): Int = found.size

        /**
         * 开始扫描。
         *
         * @return null = 成功；非 null = 失败原因
         */
        @SuppressLint("MissingPermission")
        fun start(timeoutMs: Long = SCAN_TIMEOUT_MS): String? {
            if (running.get()) return "已在扫描中"

            val a = adapter(context) ?: return "本机无蓝牙"
            val enabled = try { a.isEnabled } catch (t: SecurityException) { return "缺少蓝牙权限" }
            if (!enabled) return "蓝牙已关闭"

            val scanner = a.bluetoothLeScanner
                ?: return "BLE 扫描不可用（可能本机不支持 BLE）"

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    if (result == null || !running.get()) return
                    val dev = result.device ?: return
                    val addr = try { dev.address } catch (t: SecurityException) { "" }
                    // ⚠️ 地址不可用时用「名称+rssi」做键，否则所有设备会被
                    //   当成同一个（空字符串键）。
                    val key = if (addr.isEmpty()) "unknown-${result.rssi}-${dev.name}" else addr
                    val d = Device(
                        address = addr,
                        name = try { dev.name } catch (t: SecurityException) { null },
                        rssi = result.rssi,
                        isLe = true,
                        isBonded = try { dev.bondState == BluetoothDevice.BOND_BONDED }
                        catch (t: SecurityException) { false },
                        lastSeenMs = System.currentTimeMillis(),
                    )
                    found[key] = d
                    runCatching { onDevice(d) }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    val msg = when (errorCode) {
                        SCAN_FAILED_ALREADY_STARTED -> "扫描已在进行"
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                        SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> "本机不支持该扫描特性"
                        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "硬件资源不足（同时扫描太多）"
                        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描过于频繁（系统限制）"
                        else -> "扫描失败（$errorCode）"
                    }
                    Log.w(TAG, "BLE 扫描失败: $msg")
                    stop(EndReason.Failed(msg))
                }
            }

            return try {
                // ⚠️ 用 LOW_LATENCY 还是 BALANCED？
                //   前者扫得更快但更耗电。发现阶段用 BALANCED 足够，
                //   而且系统对 LOW_LATENCY 有更严格的频率限制。
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build()
                scanner.startScan(emptyList<ScanFilter>(), settings, cb)

                callback = cb
                running.set(true)

                // 超时兜底
                val tr = Runnable { stop(EndReason.Timeout) }
                timeoutRunnable = tr
                handler.postDelayed(tr, timeoutMs)

                EventLog.record(
                    "device.bt.scan.start",
                    mapOf("timeoutMs" to timeoutMs.toString()),
                )
                null
            } catch (t: SecurityException) {
                EventLog.record("device.bt.scan.noPermission")
                "缺少蓝牙扫描权限"
            } catch (t: Throwable) {
                Log.w(TAG, "启动扫描失败: ${t.message}")
                t.message ?: "启动失败"
            }
        }

        /**
         * 停止扫描。
         *
         * ⚠️ **必须真的 `stopScan`**：不停的话扫描会持续耗电，
         *   且系统会累积「扫描过于频繁」的惩罚（后续扫描被拒）。
         */
        fun stop(reason: EndReason = EndReason.Stopped) {
            if (!running.getAndSet(false)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null

            val a = adapter(context)
            val scanner = try { a?.bluetoothLeScanner } catch (t: SecurityException) { null }
            val cb = callback
            if (scanner != null && cb != null) {
                runCatching { scanner.stopScan(cb) }
                    .onFailure { Log.w(TAG, "停止扫描失败: ${it.message}") }
            }
            callback = null

            EventLog.record(
                "device.bt.scan.end",
                mapOf("reason" to reason.id, "found" to found.size.toString()),
            )
            onEnd(reason)
        }

        fun isRunning(): Boolean = running.get()
    }

    /** 扫描/连接结束原因。 */
    sealed interface EndReason {
        val id: String
        data object Stopped : EndReason { override val id = "stopped" }
        data object Timeout : EndReason { override val id = "timeout" }
        data object NoPermission : EndReason { override val id = "no-permission" }
        data class Failed(val reason: String) : EndReason { override val id = "failed" }
    }

    /**
     * 开始扫描。
     */
    fun scan(
        context: Context,
        onDevice: (Device) -> Unit,
        onEnd: (EndReason) -> Unit = {},
        timeoutMs: Long = SCAN_TIMEOUT_MS,
    ): ScanSession {
        val s = ScanSession(context, onDevice, onEnd)
        val err = s.start(timeoutMs)
        if (err != null) {
            Log.w(TAG, "蓝牙扫描启动失败: $err")
            onEnd(EndReason.Failed(err))
        }
        return s
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：API 版本对应的权限判定、适配器状态、BLE 扫描（去重/超时/取消）、
     *          扫描失败码的完整映射、地址不可用检测
     * ❌ 未做：
     *   - **BLE 连接**（`BluetoothGatt` 的连接生命周期 —— 见下一步）
     *   - **服务发现与特征读写**（GATT 操作）
     *   - **通知订阅**（`setCharacteristicNotification` + `onCharacteristicChanged`）
     *   - 经典蓝牙（文档明确「不承诺」）
     *
     * ⚠️ 连接/GATT 是蓝牙能力的**主体**，不是「顺带能做」的。
     *   本对象建立的是「发现」这一层 —— 它是连接的前提。
     */
}
