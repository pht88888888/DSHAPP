package com.dsh.android.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **传感器能力** —— 类型/频率发现、原始采样、姿态与摇动语义事件。
 *
 * ## 设计说明 §4.14.1a 的要求
 *
 * > 传感器 | **类型/频率发现**，原始采样订阅、**姿态/摇动等语义事件** |
 * > 设备无硬件、**频率上限**、撤订阅、**背压**、**后台停止**或另行授权
 *
 * > §4.14（流式面）：原始传感器流**声明格式、采样率、背压、丢帧/丢样及
 * > 终止原因**…显示与媒体绘制随可见性暂停…**撤权立即停止**
 *
 * ## ⚠️ 三层抽象（不要混在一起）
 *
 * | 层 | 用途 | 频率 |
 * |---|---|---|
 * | 类型发现 | 「这台机器有什么传感器」 | 一次性 |
 * | **原始采样流** | 插件要自己做算法 | 高（50~200Hz） |
 * | **语义事件** | 摇动、翻转、抬腕 | 低（事件驱动） |
 *
 * 混在一起的后果：想拿「摇了一下」的插件被 200Hz 原始数据淹没，
 * 而想做算法的插件拿不到原始值。
 *
 * ## ⚠️ 频率上限（文档明确要求）
 *
 * 传感器可以给到 200Hz+，但**不代表我们该要**：
 *
 * | 频率 | 用途 | 耗电 |
 * |---|---|---|
 * | `SENSOR_DELAY_GAME` (50Hz) | 摇动/姿态 | 中 |
 * | `SENSOR_DELAY_UI` (16Hz) | 界面反馈 | 低 |
 * | `SENSOR_DELAY_FASTEST` (~200Hz) | 原始算法 | **高** |
 *
 * 所以 [Rate] 是**显式枚举**而不是「随便传个数字」——
 * 让调用方看到「我选的是最耗电那档」。
 *
 * ## ⚠️ 后台必须停（文档明确要求）
 *
 * Android 9+ 对后台传感器有硬限制（`SENSOR_SERVICE` 在后台不回调）。
 * 但**不能依赖系统**：不同版本行为不同，且系统限制生效前已经耗了电。
 *
 * 所以提供 [Session.onAppBackground] / [Session.onAppForeground]，
 * 由调用方显式控制 —— 与 [LocationCapability.ContinuousSession] 同一套思路。
 */
object SensorCapability {

    private const val TAG = "DSH"

    /**
     * 采样频率档位。
     *
     * ⚠️ 用枚举而不是裸数字：让调用方**看到**「我选的是最耗电那档」。
     */
    enum class Rate(val id: String, val delayUs: Int, val approxHz: Int, val label: String) {
        /** 界面反馈（约 16Hz）。 */
        UI("ui", SensorManager.SENSOR_DELAY_UI, 16, "界面（省电）"),

        /** 普通（约 50Hz）—— 摇动/姿态的默认。 */
        GAME("game", SensorManager.SENSOR_DELAY_GAME, 50, "普通（摇动/姿态）"),

        /** 最快（约 200Hz）—— **最耗电**，只在真需要原始算法时用。 */
        FASTEST("fastest", SensorManager.SENSOR_DELAY_FASTEST, 200, "最快（最耗电）"),
        ;

        companion object {
            fun of(id: String?): Rate = entries.firstOrNull { it.id == id } ?: GAME
        }
    }

    /**
     * 一个传感器的信息。
     */
    data class Info(
        val type: Int,
        val name: String,
        val vendor: String,
        /** 最大量程。 */
        val maxRange: Float,
        /** 分辨率（越小越精细）。 */
        val resolution: Float,
        /** 功耗（mA）。 */
        val powerMa: Float,
        /** 最小延迟（微秒）。越小说明硬件越快。 */
        val minDelayUs: Int,
    ) {
        /** 该传感器能给的最高频率（Hz）。0 = 不支持连续采样。 */
        val maxHz: Int get() = if (minDelayUs > 0) 1_000_000 / minDelayUs else 0
    }

    /**
     * 一次采样。
     *
     * @param values 传感器原始值（含义随 type 变）
     * @param timestampNs 硬件时间戳（纳秒）。**比 `System.currentTimeMillis()` 准**，
     *   因为它来自传感器时钟而非软件时钟。
     */
    data class Sample(
        val type: Int,
        val values: FloatArray,
        val timestampNs: Long,
        val accuracy: Int,
    )

    /**
     * 发现所有传感器。
     *
     * ⚠️ 用 `getSensorList(TYPE_ALL)` 一次拿全，而不是逐个 type 查 ——
     *   逐个查要调十几次，而一次拿全只是一次系统调用。
     */
    fun discover(context: Context): List<Info> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return emptyList()
        return try {
            sm.getSensorList(Sensor.TYPE_ALL).map { s ->
                Info(
                    type = s.type,
                    name = s.name,
                    vendor = s.vendor,
                    maxRange = s.maximumRange,
                    resolution = s.resolution,
                    powerMa = s.power,
                    minDelayUs = s.minDelay,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "传感器发现失败: ${t.message}")
            EventLog.record("device.sensor.discoverFailed", mapOf("reason" to (t.message ?: "?")))
            emptyList()
        }
    }

    /**
     * 是否有某个类型。
     */
    fun hasType(context: Context, type: Int): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(type) != null
    }

    /**
     * 语义事件类型（给插件用，避免它自己解析原始数据）。
     */
    enum class Event(val id: String, val label: String) {
        /** 摇动（快速来回移动）。 */
        SHAKE("shake", "摇动"),

        /** 手机翻转（屏幕朝下/朝上）。 */
        FLIP("flip", "翻转"),

        /** 抬腕（屏幕朝上且倾斜）。 */
        LIFT("lift", "抬起"),

        /** 静止。 */
        STILL("still", "静止"),
    }

    /**
     * **原始采样会话**。
     *
     * ## ⚠️ 背压：丢旧保新
     *
     * 传感器回调在**主线程**（或调用方指定的 Looper）上。消费者慢的话：
     *
     * | 策略 | 后果 |
     * |---|---|
     * | 阻塞回调 | **卡住主线程** → ANR |
     * | 同步交给 sink | 同上（sink 慢就是主线程慢） |
     * | **丢旧保新** | 回调立即返回，数据积压时丢最旧的 |
     *
     * 所以用环形缓冲 + 独立消费线程（与 [AudioCapture] 同一套思路）。
     */
    class Session internal constructor(
        private val context: Context,
        private val types: List<Int>,
        private val rate: Rate,
        private val onSample: (Sample) -> Unit,
        private val onEnd: (EndReason) -> Unit,
    ) {
        private var sm: SensorManager? = null
        private var listener: SensorEventListener? = null
        private val running = AtomicBoolean(false)
        private val pausedByBackground = AtomicBoolean(false)

        /** 已采集的采样数。 */
        @Volatile var sampleCount: Long = 0
            private set

        /** 因背压丢弃的采样数。 */
        val droppedCount = AtomicLong(0)

        /**
         * 环形缓冲：回调线程写、消费线程读。
         *
         * 容量 64 个采样：50Hz 下约 1.3 秒 —— 足够吸收正常抖动，
         * 又不至于积压到「数据过时」。
         */
        private val ring = SampleRing(64)

        private var consumer: Thread? = null

        /**
         * 启动订阅。
         *
         * @return null = 成功；非 null = 失败原因
         */
        fun start(): String? {
            if (running.get()) return "已在订阅中"
            val mgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return "本机无传感器服务"

            val sensors = types.mapNotNull { mgr.getDefaultSensor(it) }
            if (sensors.isEmpty()) return "本机没有所请求的传感器"

            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!running.get() || pausedByBackground.get()) return
                    sampleCount++
                    // ⚠️ **复制 values**：SensorEvent 对象是复用的，
                    //   不复制的话环形缓冲里存的全是最后一次的值 ——
                    //   这是 Android 传感器最经典的坑。
                    val copy = event.values.copyOf()
                    val dropped = ring.write(
                        Sample(
                            type = event.sensor.type,
                            values = copy,
                            timestampNs = event.timestamp,
                            accuracy = 0,
                        ),
                    )
                    if (dropped > 0) droppedCount.addAndGet(dropped.toLong())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    Log.d(TAG, "传感器精度变化: ${sensor?.name} → $accuracy")
                }
            }

            return try {
                var ok = false
                for (s in sensors) {
                    if (mgr.registerListener(l, s, rate.delayUs)) ok = true
                }
                if (!ok) return "注册传感器失败（可能被系统限制）"

                sm = mgr
                listener = l
                running.set(true)

                // 消费线程
                consumer = Thread({ consumeLoop() }, "dsh-sensor-consume").apply {
                    isDaemon = true
                    start()
                }

                EventLog.record(
                    "device.sensor.start",
                    mapOf(
                        "types" to types.joinToString(","),
                        "rate" to rate.id,
                        "approxHz" to rate.approxHz.toString(),
                    ),
                )
                null
            } catch (t: SecurityException) {
                "没有传感器权限"
            } catch (t: Throwable) {
                t.message ?: "启动失败"
            }
        }

        /** 消费循环。 */
        private fun consumeLoop() {
            while (true) {
                val s = ring.read()
                if (s == null) {
                    if (!running.get()) break
                    try { Thread.sleep(5) } catch (e: InterruptedException) { break }
                    continue
                }
                // ⚠️ 回调异常不能让消费线程死掉
                try {
                    onSample(s)
                } catch (t: Throwable) {
                    Log.w(TAG, "传感器消费者抛异常（已忽略）: ${t.message}")
                }
            }
        }

        /**
         * App 退到后台：**暂停**（文档：后台停止）。
         *
         * ⚠️ 必须真 `unregisterListener` 而不是只忽略回调 ——
         *   后者传感器仍在工作，耗电且系统可能报「后台传感器使用」。
         */
        fun onAppBackground() {
            if (!running.get() || pausedByBackground.getAndSet(true)) return
            runCatching { listener?.let { sm?.unregisterListener(it) } }
            EventLog.record("device.sensor.paused")
            Log.i(TAG, "传感器：退后台，已暂停")
        }

        /** App 回到前台：恢复。 */
        fun onAppForeground() {
            if (!running.get() || !pausedByBackground.getAndSet(false)) return
            val mgr = sm ?: return
            val l = listener ?: return
            runCatching {
                for (type in types) {
                    val s = mgr.getDefaultSensor(type) ?: continue
                    mgr.registerListener(l, s, rate.delayUs)
                }
            }
            EventLog.record("device.sensor.resumed")
            Log.i(TAG, "传感器：回前台，已恢复")
        }

        /** 主动停止。 */
        fun stop() {
            if (!running.getAndSet(false)) return
            runCatching { listener?.let { sm?.unregisterListener(it) } }
            listener = null
            sm = null
            EventLog.record(
                "device.sensor.stop",
                mapOf(
                    "samples" to sampleCount.toString(),
                    "dropped" to droppedCount.get().toString(),
                ),
            )
            onEnd(EndReason.Stopped)
        }

        fun isRunning(): Boolean = running.get()
        fun isPausedByBackground(): Boolean = pausedByBackground.get()
    }

    /** 订阅结束原因。 */
    sealed interface EndReason {
        val id: String
        data object Stopped : EndReason { override val id = "stopped" }
        data object Error : EndReason { override val id = "error" }
    }

    /**
     * 采样环形缓冲（丢旧保新）。
     *
     * ⚠️ 与 [AudioCapture] 的环形缓冲同构，但存的是对象而非字节 ——
     *   所以不能用 `System.arraycopy`，需要逐个挪。
     */
    private class SampleRing(private val capacity: Int) {
        private val buf = arrayOfNulls<Sample>(capacity)
        private var head = 0
        private var count = 0
        var dropped = 0L
            private set

        /** @return 本次丢弃的条数 */
        fun write(s: Sample): Int {
            var droppedNow = 0
            while (count == capacity) {
                // 满了 ⇒ 丢最旧的
                buf[head] = null
                head = (head + 1) % capacity
                count--
                droppedNow++
                dropped++
            }
            buf[(head + count) % capacity] = s
            count++
            return droppedNow
        }

        fun read(): Sample? {
            if (count == 0) return null
            val s = buf[head]
            buf[head] = null
            head = (head + 1) % capacity
            count--
            return s
        }
    }

    /**
     * 开一次原始采样订阅。
     */
    fun subscribe(
        context: Context,
        types: List<Int>,
        rate: Rate = Rate.GAME,
        onSample: (Sample) -> Unit,
        onEnd: (EndReason) -> Unit = {},
    ): Session {
        val s = Session(context, types, rate, onSample, onEnd)
        val err = s.start()
        if (err != null) {
            Log.w(TAG, "传感器订阅启动失败: $err")
            onEnd(EndReason.Error)
        }
        return s
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：类型/频率发现、三档频率显式选择、原始采样订阅、
     *          丢旧保新背压、退后台暂停、`values` 复制（防经典坑）、
     *          硬件时间戳
     * ❌ 未做：
     *   - **语义事件的实际算法**（摇动已有 [com.dsh.android.ShakeDetector]，
     *     但翻转/抬腕/静止的判定未实现 —— 见 [Event] 枚举只有声明）
     *   - 「频率上限」的运行时校验（当前只提供三档，不做「你要 500Hz
     *     但硬件只支持 200Hz」的降级提示）
     *   - 传感器功耗统计（`Info.powerMa` 有值但没做累计）
     *   - 「另行授权」路径（文档提到后台采集可单独授权 —— 当前一律后台停）
     */
}
