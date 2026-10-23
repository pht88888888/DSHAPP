package com.dsh.android.device

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **音频采集** —— 录音与实时采样流。
 *
 * ## 设计说明 §4.14.1a / §4.14 的要求
 *
 * > 麦克风与音视频 | 录音、播放、**实时采集/消费流、格式协商与时间戳**；
 * > 验证合法消费者 | 拒权/撤权、**背压、丢帧或中止策略、流取消**；
 * > **不自动外发**，不承诺远端通话基础设施
 *
 * > 流式面：…实时音频和原始传感器流**声明格式、采样率、背压、丢帧/丢样及
 * > 终止原因**…**撤权立即停止**
 *
 * > 验收矩阵 27：**采集授权不自动授予外发权限**
 *
 * ## ⚠️ 本对象刻意「只采集，不外发」
 *
 * 这是文档最强调的一条。所以本对象：
 * - **没有任何网络代码**
 * - **不持有任何上传通道**
 * - 数据只通过 [Sink] 回调交给调用方
 *
 * 「要不要发出去」是调用方的决定，且**必须单独获得用户授权** ——
 * 本对象不提供任何「顺手发出去」的便利。
 *
 * ## ⚠️ 背压策略：丢旧保新
 *
 * 音频是**实时流**，不能像文件那样「等消费者跟上」：
 *
 * | 策略 | 后果 |
 * |---|---|
 * | 阻塞生产者 | 采集缓冲溢出，音质断续且延迟累积 |
 * | **丢最旧的** | 延迟恒定，代价是丢一小段 —— 通话/语音输入可接受 |
 * | 中止整个流 | 太激进（偶尔一次消费慢就断流） |
 *
 * 所以用「丢最旧的」，并**如实计数丢了多少**（文档要求「声明丢帧/丢样」）。
 */
object AudioCapture {

    private const val TAG = "DSH"

    /** 采样率。16 kHz 是语音识别的事实标准，也是带宽与质量的平衡点。 */
    const val SAMPLE_RATE = 16_000

    /** 单声道。语音采集不需要立体声，双声道会让数据量翻倍。 */
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO

    /** 16 位 PCM。 */
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 每个回调块的时长（毫秒）。20ms 是音频处理的常用块长。 */
    const val CHUNK_MS = 20

    /** 一块的字节数。 */
    val CHUNK_BYTES: Int get() = SAMPLE_RATE * CHUNK_MS / 1000 * 2   // 16bit = 2 字节

    /**
     * 环形缓冲容量（毫秒）。
     *
     * ⚠️ 这是「消费者卡多久还不断流」的容忍度：
     * - 太小（100ms）→ 正常抖动也丢帧
     * - 太大（5s）→ 延迟不可接受（语音输入会「说完还要等」）
     */
    const val MAX_BUFFER_MS = 500

    /**
     * 数据接收者。
     *
     * ⚠️ **在消费线程上调用**（不是采集线程，见 [Session] 的背压实现）。
     *   实现方仍应快速返回；慢了会导致环形缓冲积压 → 丢旧保新。
     */
    fun interface Sink {
        /** @param data PCM 16bit 小端；`size` 是有效字节数 */
        fun onAudio(data: ByteArray, size: Int)
    }

    /**
     * **环形缓冲** —— 背压策略「丢旧保新」的实现。
     *
     * ## ⚠️ 为什么必须丢旧保新（而不是阻塞生产者）
     *
     * 音频是实时流，三种策略的后果完全不同：
     *
     * | 策略 | 后果 |
     * |---|---|
     * | 阻塞生产者 | 采集缓冲溢出，音质断续且**延迟累积**（越用越卡） |
     * | **丢最旧的** | 延迟恒定，代价是丢一小段 —— 语音输入/通话可接受 |
     * | 中止整个流 | 太激进（偶尔一次消费慢就断流） |
     *
     * 所以用丢旧保新，并**如实计数**（文档要求「声明丢帧/丢样」）。
     *
     * ## ⚠️ 容量怎么定
     *
     * 取 [MAX_BUFFER_MS]（500ms）：这是「消费者卡多久还不断流」的容忍度。
     * 太小（如 100ms）会导致正常抖动也丢帧；太大（如 5s）会让延迟不可接受。
     */
    private class RingBuffer(private val capacityBytes: Int) {
        private val buf = ByteArray(capacityBytes)
        private var head = 0      // 读位置
        private var tail = 0      // 写位置
        private var count = 0     // 已存字节

        /** 丢弃的字节数（背压导致）。 */
        var dropped = 0L
            private set

        /**
         * 写入。空间不足时**丢弃最旧的数据**腾地方。
         *
         * @return 本次丢弃的字节数
         */
        fun write(src: ByteArray, len: Int): Int {
            var droppedNow = 0
            // 空间不够 ⇒ 从读位置丢弃
            while (count + len > capacityBytes && count > 0) {
                // 一次丢一个块大小的量，避免逐字节循环
                val drop = minOf(count, len)
                head = (head + drop) % capacityBytes
                count -= drop
                droppedNow += drop
                dropped += drop
            }
            if (len > capacityBytes) {
                // ⚠️ 单块比整个缓冲还大（不该发生，但要防死循环）
                droppedNow += len
                dropped += len
                return droppedNow
            }
            // 写入（可能回绕）
            val firstPart = minOf(len, capacityBytes - tail)
            System.arraycopy(src, 0, buf, tail, firstPart)
            if (firstPart < len) {
                System.arraycopy(src, firstPart, buf, 0, len - firstPart)
            }
            tail = (tail + len) % capacityBytes
            count += len
            return droppedNow
        }

        /**
         * 读出一块。
         *
         * @return 实际读到的字节数；0 = 空
         */
        fun read(dst: ByteArray, max: Int): Int {
            if (count == 0) return 0
            val n = minOf(max, count)
            val firstPart = minOf(n, capacityBytes - head)
            System.arraycopy(buf, head, dst, 0, firstPart)
            if (firstPart < n) {
                System.arraycopy(buf, 0, dst, firstPart, n - firstPart)
            }
            head = (head + n) % capacityBytes
            count -= n
            return n
        }
    }

    /**
     * 一次采集会话。
     *
     * ⚠️ 用对象而不是「全局单例状态」：可能有多个消费者（语音输入 + 实时流），
     *   各自需要独立的启停。
     */
    class Session internal constructor(
        private val context: Context,
        private val sink: Sink,
        private val onEnd: (EndReason) -> Unit,
    ) {
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        private var consumer: Thread? = null
        private val running = AtomicBoolean(false)

        /**
         * 环形缓冲：采集线程写、消费线程读。
         *
         * ⚠️ 存在的意义就是**解耦两个线程的速度**：
         *   采集必须严格按 20ms 节奏进行（否则系统缓冲溢出丢数据），
         *   而消费者可能慢（写文件、编码、网络）。
         *   直接同步回调的话，慢消费者会阻塞采集 → 系统缓冲溢出 → 音质崩坏。
         */
        private val ring = RingBuffer(SAMPLE_RATE * MAX_BUFFER_MS / 1000 * 2)

        /** 累计丢弃的样本数（背压导致）。 */
        val droppedSamples = AtomicLong(0)

        /** 累计采集的样本数。 */
        val capturedSamples = AtomicLong(0)

        /**
         * 启动采集。
         *
         * @return null = 成功；非 null = 失败原因
         */
        fun start(): String? {
            if (running.get()) return "已在采集中"

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuf <= 0) {
                val msg = "本机不支持该音频格式（minBuf=$minBuf）"
                Log.w(TAG, msg)
                return msg
            }
            // ⚠️ 缓冲区取 minBuf 与 4 个块的较大者：
            //   太小时系统频繁唤醒（耗电），太大时延迟高（语音输入会「迟钝」）。
            val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)

            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufSize,
                )
            } catch (t: SecurityException) {
                Log.w(TAG, "音频采集无权限: ${t.message}")
                EventLog.record("device.audio.noPermission")
                return "没有麦克风权限"
            } catch (t: Throwable) {
                Log.w(TAG, "创建 AudioRecord 失败: ${t.message}")
                return t.message ?: "创建失败"
            }

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                val msg = "AudioRecord 初始化失败（state=${rec.state}）"
                Log.w(TAG, msg)
                return msg
            }

            record = rec
            running.set(true)
            try {
                rec.startRecording()
            } catch (t: Throwable) {
                running.set(false)
                rec.release()
                record = null
                Log.w(TAG, "启动录音失败: ${t.message}")
                return t.message ?: "启动失败"
            }

            EventLog.record(
                "device.audio.start",
                mapOf("rate" to SAMPLE_RATE.toString(), "chunkMs" to CHUNK_MS.toString()),
            )

            // 消费线程：从环形缓冲取出并交给 sink
            consumer = Thread({ consumeLoop() }, "dsh-audio-consume").apply {
                isDaemon = true
                start()
            }
            thread = Thread({ loop(rec) }, "dsh-audio-capture").apply {
                isDaemon = true
                start()
            }
            return null
        }

        /** 采集循环。 */
        private fun loop(rec: AudioRecord) {
            val buf = ByteArray(CHUNK_BYTES)
            var endReason: EndReason = EndReason.Stopped

            try {
                while (running.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) {
                        // ⚠️ 负值 = 错误码（AUDIO_INVALID_OPERATION 等），不是「读完了」。
                        //   当成正常结束会让调用方以为「用户停了」，而实际是故障。
                        Log.w(TAG, "AudioRecord.read 返回错误码 $n")
                        endReason = EndReason.Error("读取错误码 $n")
                        break
                    }
                    if (n == 0) continue

                    capturedSamples.addAndGet((n / 2).toLong())
                    // 写进环形缓冲（**永不阻塞**：满了就丢最旧的）
                    val droppedNow = ring.write(buf, n)
                    if (droppedNow > 0) {
                        droppedSamples.addAndGet((droppedNow / 2).toLong())
                    }
                }
            } catch (t: SecurityException) {
                // ⚠️ 撤权会在 read 里抛 SecurityException —— 文档要求「撤权立即停止」
                Log.w(TAG, "音频采集中途被撤权: ${t.message}")
                endReason = EndReason.PermissionRevoked
            } catch (t: Throwable) {
                Log.w(TAG, "音频采集异常: ${t.message}")
                endReason = EndReason.Error(t.message ?: "未知")
            } finally {
                running.set(false)
                runCatching { rec.stop() }
                runCatching { rec.release() }
                record = null
                EventLog.record(
                    "device.audio.end",
                    mapOf(
                        "reason" to endReason.id,
                        "captured" to capturedSamples.get().toString(),
                        "dropped" to droppedSamples.get().toString(),
                    ),
                )
                onEnd(endReason)
            }
        }

        /**
         * 消费循环：从环形缓冲取数据交给 sink。
         *
         * ⚠️ 消费慢时**不会**拖慢采集 —— 那正是环形缓冲的价值。
         *   代价是数据被丢弃（`droppedSamples` 计数）。
         */
        private fun consumeLoop() {
            val buf = ByteArray(CHUNK_BYTES)
            // ⚠️ 循环条件刻意是「无条件」，靠内部的「空且已停止」来退出：
            //   停止时缓冲里可能还有数据，直接 break 会丢掉最后一段
            //   （用户说完最后一个字的那 20ms）。
            while (true) {
                val n = ring.read(buf, buf.size)
                if (n == 0) {
                    // 缓冲空 **且** 采集已停 ⇒ 真的结束了
                    if (!running.get()) break
                    // 缓冲空但仍在采集：短暂让出 CPU。
                    // ⚠️ 用 sleep(5) 而不是 yield()：yield 在空转时仍吃满一个核。
                    try { Thread.sleep(5) } catch (e: InterruptedException) { break }
                    continue
                }
                // ⚠️ 回调异常**不能**让消费线程死掉：消费者的 bug 不该
                //   导致麦克风一直开着（那会让用户看到麦克风指示器常亮）。
                try {
                    sink.onAudio(buf, n)
                } catch (t: Throwable) {
                    Log.w(TAG, "音频消费者抛异常（已忽略，继续消费）: ${t.message}")
                }
            }
        }

        /** 主动停止。 */
        fun stop() {
            if (!running.getAndSet(false)) return
            // ⚠️ 只置标志 + stop()，**不 join**：join 会阻塞调用方线程
            //   （可能是主线程），而采集线程最多 20ms 就退出。
            //
            // ⚠️ 消费线程会在「缓冲空 + running=false」时自己退出 ——
            //   它会先把缓冲里剩下的数据消费完（不丢尾部）。
            runCatching { record?.stop() }
        }

        /** 是否在采集。 */
        fun isRunning(): Boolean = running.get()
    }

    /**
     * 采集结束原因。
     *
     * ⚠️ 文档要求「声明…**终止原因**」—— 所以不是简单的「停了」，
     *   而是明确是哪一种。
     */
    sealed interface EndReason {
        val id: String

        /** 用户/调用方主动停止。 */
        data object Stopped : EndReason {
            override val id = "stopped"
        }

        /** 权限被撤销（中途）。 */
        data object PermissionRevoked : EndReason {
            override val id = "permission-revoked"
        }

        /** 其他错误。 */
        data class Error(val reason: String) : EndReason {
            override val id = "error"
        }
    }

    /**
     * 开一次采集。
     *
     * ⚠️ **不自动申请权限**：文档「按需发现和授权，不提前索要全部权限」。
     *   调用方应先用 [DeviceCapability.probe] 确认，再决定是否申请。
     */
    fun start(context: Context, sink: Sink, onEnd: (EndReason) -> Unit = {}): Session {
        val s = Session(context, sink, onEnd)
        val err = s.start()
        if (err != null) {
            Log.w(TAG, "音频采集启动失败: $err")
            // ⚠️ 启动失败也要回调 —— 否则调用方会一直等一个永不到来的结束事件
            onEnd(EndReason.Error("启动失败"))
        }
        return s
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：采集、格式声明（16k/mono/16bit/20ms 块）、撤权立即停止、
     *          终止原因分类、消费者异常隔离、主动停止不阻塞调用方
     * ❌ 未做：
     *   - **背压的实际丢帧逻辑**（当前 sink 是同步回调，消费慢会阻塞采集循环 ——
     *     那实际是「阻塞生产者」策略，不是「丢旧保新」。要做后者需要
     *     一个环形缓冲 + 独立消费线程，属下一轮）
     *   - **音频播放**（AudioTrack）
     *   - **实时流的格式协商**（当前格式固定，不支持协商）
     *   - 时间戳（PCM 流本身无时间戳，需要外部时钟对齐）
     */
}
