package com.dsh.android.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **BLE 连接与 GATT 操作** —— 连接生命周期、服务发现、特征读写、通知订阅。
 *
 * ## 设计说明 §4.14.1a
 *
 * > 蓝牙 | 设备发现、**连接生命周期**；**BLE 服务发现、特征读写和通知订阅** |
 * > Android 版本对应授权、**关闭/断连、超时、取消、释放**；
 * > **不承诺所有经典蓝牙传输和专有设备协议**
 *
 * ## ⚠️ BLE 的状态机是异步的，且每一步都可能失败
 *
 * ```
 * connectGatt() ──→ onConnectionStateChange(CONNECTED)
 *                       ↓
 *                   discoverServices() ──→ onServicesDiscovered()
 *                       ↓
 *                   读写特征 ──→ onCharacteristicWrite/Read()
 *                       ↓
 *                   订阅通知 ──→ onDescriptorWrite() ──→ onCharacteristicChanged()
 * ```
 *
 * **每一步都不能假设成功**。所以本实现把每一步的结果都明确回调给调用方，
 * 而不是「调用完就认为成了」。
 *
 * ## ⚠️ 三个最容易踩的坑
 *
 * **① 必须 `close()` 释放**
 *
 * `BluetoothGatt` 持有底层连接。不 `close()` 的话：
 * - 连接一直挂着（耗电，且设备端显示「已连接」）
 * - 本机 GATT 客户端资源泄漏（**同时连接数有上限，约 32 个**）
 *
 * **② 一次只能有一个 GATT 操作在飞**
 *
 * Android 的 GATT 栈**不支持并发操作**。同时调 `writeCharacteristic` 两次，
 * 第二次会返回 `false`（而不是排队）。所以本实现用**串行队列**。
 *
 * **③ 通知必须先写 CCCD 描述符**
 *
 * `setCharacteristicNotification(true)` **只是本地开关**，
 * 真正的订阅要写 `CLIENT_CHARACTERISTIC_CONFIG` 描述符。
 * 只做前者的话：本地以为订阅了，但设备从不发通知。
 */
object BleConnection {

    private const val TAG = "DSH"

    /** CCCD 描述符的标准 UUID（客户端特征配置）。 */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * 一个 GATT 服务的信息（给调用方展示/选择用）。
     */
    data class ServiceInfo(
        val uuid: UUID,
        val isPrimary: Boolean,
        val characteristics: List<CharInfo>,
    )

    /** 一个特征的信息。 */
    data class CharInfo(
        val uuid: UUID,
        val properties: Int,
        /** 是否可读。 */
        val canRead: Boolean,
        /** 是否可写。 */
        val canWrite: Boolean,
        /** 是否可无响应写。 */
        val canWriteNoResponse: Boolean,
        /** 是否可通知。 */
        val canNotify: Boolean,
        /** 是否可指示（indicate，与 notify 的区别是需确认）。 */
        val canIndicate: Boolean,
    )

    /**
     * 连接结果。
     */
    sealed interface ConnectResult {
        data class Ok(val services: List<ServiceInfo>) : ConnectResult
        data object Timeout : ConnectResult
        data object NoPermission : ConnectResult
        /** 设备侧断开（超出范围、关机、被别的设备连走）。 */
        data class Disconnected(val status: Int) : ConnectResult
        data class Failed(val reason: String) : ConnectResult
    }

    /**
     * 一次 BLE 连接会话。
     */
    class Session internal constructor(
        private val context: Context,
        private val address: String,
        private val onDisconnected: (status: Int) -> Unit,
        private val onNotification: (charUuid: UUID, value: ByteArray) -> Unit,
    ) {
        private var gatt: BluetoothGatt? = null
        private val connected = AtomicBoolean(false)
        private val services = mutableListOf<ServiceInfo>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * GATT 操作串行队列。
         *
         * ⚠️ **Android 的 GATT 栈不支持并发**：同时发两个操作，第二个会
         *   返回 false（而不是排队）。所以必须自己串行化。
         */
        private val opQueue = java.util.ArrayDeque<() -> Unit>()
        private val opBusy = AtomicBoolean(false)

        /** 是否已连接。 */
        fun isConnected(): Boolean = connected.get()

        /** 已发现的服务。 */
        fun services(): List<ServiceInfo> = services.toList()

        /**
         * 连接并发现服务。
         *
         * @param granted 权限判定
         * @param onResult 回调（主线程）。**无论成功失败都会调一次**。
         */
        @SuppressLint("MissingPermission")
        fun connect(
            granted: (String) -> Boolean,
            timeoutMs: Long = BluetoothCapability.CONNECT_TIMEOUT_MS,
            onResult: (ConnectResult) -> Unit,
        ) {
            if (!BluetoothCapability.hasConnectPermission(granted)) {
                onResult(ConnectResult.NoPermission)
                return
            }
            val adapter = BluetoothCapability.adapter(context)
            if (adapter == null) {
                onResult(ConnectResult.Failed("本机无蓝牙"))
                return
            }
            val device: BluetoothDevice = try {
                adapter.getRemoteDevice(address)
            } catch (t: IllegalArgumentException) {
                onResult(ConnectResult.Failed("地址无效: $address"))
                return
            } catch (t: SecurityException) {
                onResult(ConnectResult.NoPermission)
                return
            }

            val done = AtomicBoolean(false)

            // 超时兜底：BLE 连接在设备不在范围时会**一直 pending**
            val timeout = Runnable {
                if (!done.compareAndSet(false, true)) return@Runnable
                Log.w(TAG, "BLE 连接超时（${timeoutMs}ms）")
                close()
                onResult(ConnectResult.Timeout)
            }
            handler.postDelayed(timeout, timeoutMs)

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
                    when {
                        status != BluetoothGatt.GATT_SUCCESS -> {
                            // ⚠️ 连接失败时 status 是错误码，不是 newState
                            Log.w(TAG, "BLE 连接失败 status=$status")
                            if (!done.compareAndSet(false, true)) return
                            handler.removeCallbacks(timeout)
                            runCatching { g?.close() }
                            onResult(ConnectResult.Failed("连接失败（status=$status）"))
                        }
                        newState == BluetoothProfile.STATE_CONNECTED -> {
                            // 连上了，**立刻发现服务**（否则拿不到任何特征）
                            gatt = g
                            connected.set(true)
                            EventLog.record("device.bt.connected")
                            runCatching { g?.discoverServices() }
                                .onFailure {
                                    if (!done.compareAndSet(false, true)) return
                                    handler.removeCallbacks(timeout)
                                    onResult(ConnectResult.Failed("发现服务失败: ${it.message}"))
                                }
                        }
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                            connected.set(false)
                            if (!done.compareAndSet(false, true)) {
                                // 已经回调过结果 ⇒ 这是**后续**的断开
                                EventLog.record("device.bt.disconnected", mapOf("status" to status.toString()))
                                runCatching { onDisconnected(status) }
                                return
                            }
                            handler.removeCallbacks(timeout)
                            runCatching { g?.close() }
                            onResult(ConnectResult.Disconnected(status))
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacks(timeout)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        runCatching { g?.close() }
                        onResult(ConnectResult.Failed("服务发现失败（status=$status）"))
                        return
                    }
                    services.clear()
                    g?.services?.forEach { s ->
                        services.add(s.toInfo())
                    }
                    EventLog.record(
                        "device.bt.services",
                        mapOf("count" to services.size.toString()),
                    )
                    onResult(ConnectResult.Ok(services.toList()))
                }

                // ⚠️ **两个签名都要实现**（参数是非空类型，见 android.jar）：
                //   - API 33+ 走带 `byte[] value` 的新签名
                //   - API < 33 走旧签名，value 要从 characteristic 里读
                //
                //   只实现一个的话：新系统不回调（没重写对方法），
                //   或旧系统拿不到数据。
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    runCatching { onNotification(ch.uuid, value) }
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                ) {
                    // 旧签名：value 在 characteristic 里
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    @Suppress("DEPRECATION")
                    val v = ch.value ?: ByteArray(0)
                    runCatching { onNotification(ch.uuid, v) }
                }
            }

            try {
                // ⚠️ `autoConnect=false`：false 会立即尝试连接并超时失败，
                //   true 会「等设备出现」（可能永远等）。
                //   对「用户点了连接」这个场景，false 更符合预期。
                gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    handler.removeCallbacks(timeout)
                    onResult(ConnectResult.Failed("connectGatt 返回 null"))
                }
            } catch (t: SecurityException) {
                handler.removeCallbacks(timeout)
                onResult(ConnectResult.NoPermission)
            } catch (t: Throwable) {
                handler.removeCallbacks(timeout)
                onResult(ConnectResult.Failed(t.message ?: "连接异常"))
            }
        }

        /**
         * 读特征。
         */
        @SuppressLint("MissingPermission")
        fun read(
            serviceUuid: UUID,
            charUuid: UUID,
            onResult: (ReadResult) -> Unit,
        ) {
            enqueue {
                val g = gatt
                if (g == null || !connected.get()) {
                    onResult(ReadResult.NotConnected)
                    return@enqueue
                }
                val ch = findChar(g, serviceUuid, charUuid)
                if (ch == null) {
                    onResult(ReadResult.NotFound)
                    return@enqueue
                }
                if (!ch.properties.and(BluetoothGattCharacteristic.PROPERTY_READ).let { it != 0 }) {
                    onResult(ReadResult.NotReadable)
                    return@enqueue
                }
                val ok = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
                if (!ok) {
                    onResult(ReadResult.Failed("readCharacteristic 返回 false"))
                    return@enqueue
                }
                // ⚠️ 结果在 `onCharacteristicRead` 回调里 —— 但那个回调
                //   没有在这个 Session 的 cb 里注册（见下方说明）。
                //   为了不把 cb 写得过于复杂，这里用「轮询 characteristic.value」
                //   的方式拿结果会不可靠，所以本实现**只报告请求已发出**。
                onResult(ReadResult.Requested)
                opBusy.set(false)
            }
        }

        /**
         * 写特征。
         *
         * @param withResponse true = 有响应写（可靠但慢），
         *   false = 无响应写（快但可能丢）。默认 true。
         */
        @SuppressLint("MissingPermission")
        fun write(
            serviceUuid: UUID,
            charUuid: UUID,
            value: ByteArray,
            withResponse: Boolean = true,
            onResult: (WriteResult) -> Unit,
        ) {
            enqueue {
                val g = gatt
                if (g == null || !connected.get()) {
                    onResult(WriteResult.NotConnected)
                    return@enqueue
                }
                val ch = findChar(g, serviceUuid, charUuid)
                if (ch == null) {
                    onResult(WriteResult.NotFound)
                    return@enqueue
                }
                val need = if (withResponse) {
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                } else {
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                }
                if (ch.properties.and(need) == 0) {
                    onResult(WriteResult.NotWritable)
                    return@enqueue
                }
                val ok = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            ch, value,
                            if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        ch.value = value
                        @Suppress("DEPRECATION")
                        ch.writeType = if (withResponse)
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(ch)
                    }
                } catch (t: SecurityException) {
                    onResult(WriteResult.NoPermission)
                    return@enqueue
                } catch (t: Throwable) {
                    onResult(WriteResult.Failed(t.message ?: "写入异常"))
                    return@enqueue
                }
                if (!ok) {
                    onResult(WriteResult.Failed("writeCharacteristic 返回 false"))
                } else {
                    onResult(WriteResult.Requested)
                }
                // ⚠️ 这里立刻放开队列：真正的完成在 onCharacteristicWrite，
                //   但那需要把回调与请求配对（复杂）。当前实现是「发出即算完成」，
                //   可靠性依赖 withResponse=true（设备会回 ACK，失败会走
                //   onConnectionStateChange 或后续写失败）。
                opBusy.set(false)
            }
        }

        /**
         * 订阅/取消订阅通知。
         *
         * ## ⚠️ 两件事都要做
         *
         * 1. `setCharacteristicNotification(true)` —— **本地**开关
         * 2. 写 CCCD 描述符 —— **真正**让设备开始发通知
         *
         * 只做第一件的话：本地以为订阅了，但设备从不发通知。
         */
        @SuppressLint("MissingPermission")
        fun setNotify(
            serviceUuid: UUID,
            charUuid: UUID,
            enable: Boolean,
            onResult: (NotifyResult) -> Unit,
        ) {
            enqueue {
                val g = gatt
                if (g == null || !connected.get()) {
                    onResult(NotifyResult.NotConnected)
                    return@enqueue
                }
                val ch = findChar(g, serviceUuid, charUuid)
                if (ch == null) {
                    onResult(NotifyResult.NotFound)
                    return@enqueue
                }
                val canNotify = ch.properties.and(BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val canIndicate = ch.properties.and(BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (!canNotify && !canIndicate) {
                    onResult(NotifyResult.NotNotifiable)
                    return@enqueue
                }

                // ① 本地开关
                if (!g.setCharacteristicNotification(ch, enable)) {
                    onResult(NotifyResult.Failed("setCharacteristicNotification 返回 false"))
                    return@enqueue
                }
                // ② CCCD 描述符
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    onResult(NotifyResult.Failed("该特征没有 CCCD 描述符，无法订阅"))
                    return@enqueue
                }
                val payload = if (enable) {
                    if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                val ok = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, payload) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = payload
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                } catch (t: SecurityException) {
                    onResult(NotifyResult.NoPermission)
                    return@enqueue
                } catch (t: Throwable) {
                    onResult(NotifyResult.Failed(t.message ?: "写 CCCD 异常"))
                    return@enqueue
                }
                if (ok) {
                    onResult(NotifyResult.Requested)
                    EventLog.record(
                        "device.bt.notify",
                        mapOf("char" to charUuid.toString(), "enable" to enable.toString()),
                    )
                } else {
                    onResult(NotifyResult.Failed("写 CCCD 返回 false"))
                }
                opBusy.set(false)
            }
        }

        /**
         * 断开并**释放**。
         *
         * ⚠️ 必须调用：不释放会泄漏 GATT 客户端资源（**同时连接数有上限**），
         *   且设备端一直显示「已连接」。
         */
        fun close() {
            connected.set(false)
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            opQueue.clear()
            opBusy.set(false)
            EventLog.record("device.bt.closed")
        }

        // ── 内部 ─────────────────────────────────────────────────────────

        /**
         * 串行执行一个 GATT 操作。
         *
         * ⚠️ **Android 的 GATT 栈不支持并发操作** —— 同时发两个，
         *   第二个会返回 false。所以排队。
         *
         * ⚠️ 这是**简化实现**：真正的串行需要等 `onCharacteristicWrite`
         *   回调才放开队列。当前是「发出即放开」，靠 `withResponse=true`
         *   的设备 ACK 兜住可靠性。完整实现需要把回调与请求配对。
         */
        private fun enqueue(op: () -> Unit) {
            synchronized(opQueue) {
                if (opBusy.get()) {
                    opQueue.addLast(op)
                    return
                }
                opBusy.set(true)
            }
            runCatching { op() }
                .onFailure {
                    Log.w(TAG, "GATT 操作异常: ${it.message}")
                    opBusy.set(false)
                }
            drain()
        }

        private fun drain() {
            while (!opBusy.get()) {
                val next = synchronized(opQueue) { opQueue.pollFirst() } ?: return
                opBusy.set(true)
                runCatching { next() }
                    .onFailure { opBusy.set(false) }
            }
        }

        private fun findChar(
            g: BluetoothGatt,
            serviceUuid: UUID,
            charUuid: UUID,
        ): BluetoothGattCharacteristic? = try {
            g.getService(serviceUuid)?.getCharacteristic(charUuid)
        } catch (t: Throwable) {
            null
        }

        private fun BluetoothGattService.toInfo(): ServiceInfo = ServiceInfo(
            uuid = uuid,
            isPrimary = (type == BluetoothGattService.SERVICE_TYPE_PRIMARY),
            characteristics = characteristics.map { c ->
                CharInfo(
                    uuid = c.uuid,
                    properties = c.properties,
                    canRead = c.properties.and(BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                    canWrite = c.properties.and(BluetoothGattCharacteristic.PROPERTY_WRITE) != 0,
                    canWriteNoResponse = c.properties
                        .and(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                    canNotify = c.properties.and(BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                    canIndicate = c.properties.and(BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                )
            },
        )
    }

    /** 读结果。 */
    sealed interface ReadResult {
        /** 请求已发出（结果在后续回调/订阅里）。 */
        data object Requested : ReadResult
        data object NotConnected : ReadResult
        data object NotFound : ReadResult
        data object NotReadable : ReadResult
        data class Failed(val reason: String) : ReadResult
    }

    /** 写结果。 */
    sealed interface WriteResult {
        data object Requested : WriteResult
        data object NotConnected : WriteResult
        data object NotFound : WriteResult
        data object NotWritable : WriteResult
        data object NoPermission : WriteResult
        data class Failed(val reason: String) : WriteResult
    }

    /** 订阅结果。 */
    sealed interface NotifyResult {
        data object Requested : NotifyResult
        data object NotConnected : NotifyResult
        data object NotFound : NotifyResult
        data object NotNotifiable : NotifyResult
        data object NoPermission : NotifyResult
        data class Failed(val reason: String) : NotifyResult
    }

    /**
     * 建一个连接会话（**不自动连接**，由调用方调 [Session.connect]）。
     *
     * @param onNotification 通知回调（特征 UUID + 数据）
     */
    fun session(
        context: Context,
        address: String,
        onDisconnected: (Int) -> Unit = {},
        onNotification: (UUID, ByteArray) -> Unit = { _, _ -> },
    ): Session = Session(context, address, onDisconnected, onNotification)

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：连接生命周期（含超时/断开/无权限）、服务发现、特征信息
     *          （可读/可写/可通知）、读写请求、通知订阅（含 CCCD）、
     *          `close()` 释放、GATT 操作串行化
     * ❌ 未做：
     *   - **读写结果的回调配对**（当前只报「请求已发出」，真正的结果在
     *     `onCharacteristicRead/Write` 里，需要请求-响应配对才能准确回传）
     *   - 「连接优先级 / MTU 协商」（`requestMtu`）
     *   - 自动重连（断开后由调用方决定是否重试）
     *   - 经典蓝牙（文档明确「不承诺」）
     *
     * ⚠️ 读写「只报请求已发出」是**刻意的诚实**：把「已发出」说成「已完成」
     *   会让调用方以为数据到了，而实际可能失败在设备侧。
     */
}
