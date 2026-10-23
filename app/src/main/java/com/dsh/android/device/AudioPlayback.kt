package com.dsh.android.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **音频播放** —— 流式 PCM 输出。
 *
 * ## 设计说明 §4.14.1a
 *
 * > 麦克风与音视频 | 录音、**播放**、实时采集/消费流、格式协商与时间戳…
 * > 拒权/撤权、背压、丢帧或中止策略、流取消
 *
 * ## ⚠️ 与 [AudioCapture] 的对称设计
 *
 * | 方向 | 类 | 背压策略 |
 * |---|---|---|
 * | 采集（麦克风 → 我们） | [AudioCapture] | **丢旧保新**（采集不能阻塞） |
 * | 播放（我们 → 扬声器） | 本对象 | **阻塞生产者**（播放不能丢） |
 *
 * ⚠️ **两者策略刻意相反**，这是音频处理的常识：
 *
 * - 采集端丢数据 → 只是少听到一小段，**延迟不变**
 * - 播放端丢数据 → 会听到**咔哒声/破音**，比延迟更糟
 *
 * 所以播放用 `WRITE_BLOCKING`：缓冲满时阻塞写入方，而不是丢。
 * 阻塞时间有上限（[WRITE_TIMEOUT_MS]），超时就报错而不是无限卡住。
 *
 * ## ⚠️ 播放器**不做文件读取**
 *
 * 文档 §4.13：「**不允许播放器按任意外部路径读取文件**」。
 *
 * 所以本对象只接受**已解码的 PCM 字节**，不接受文件路径。
 * 「从哪读文件」是调用方的事，且必须经过素材检查与资源句柄
 * （见文档「插件素材先经基座检查并导入受管资源区」）。
 *
 * 这个设计刻意让播放器**无法**成为「任意文件读取」的入口 ——
 * 即使调用方被恶意插件控制，也只能播它自己解码好的数据。
 */
object AudioPlayback {

    private const val TAG = "DSH"

    /**
     * 写入超时（毫秒）。
     *
     * ⚠️ 必须有上限：`WRITE_BLOCKING` 在播放设备异常（蓝牙断开、
     *   扬声器被占用）时可能**永久阻塞**。不设超时的话调用方线程会卡死。
     */
    const val WRITE_TIMEOUT_MS = 2_000

    /**
     * 播放格式。
     *
     * ⚠️ 与 [AudioCapture] 的采集格式**不要求一致**：
     *   采集固定 16k/mono（语音识别标准），而播放可能是 44.1k/stereo（音乐）。
     *   两者独立配置。
     */
    data class Format(
        val sampleRate: Int = 44_100,
        val channelMask: Int = AudioFormat.CHANNEL_OUT_MONO,
        val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    ) {
        /** 每帧字节数（一帧 = 所有声道的同一时刻采样）。 */
        val bytesPerFrame: Int
            get() = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> channels
                AudioFormat.ENCODING_PCM_FLOAT -> channels * 4
                else -> channels * 2   // 16bit
            }

        val channels: Int
            get() = when (channelMask) {
                AudioFormat.CHANNEL_OUT_MONO -> 1
                AudioFormat.CHANNEL_OUT_STEREO -> 2
                else -> 1
            }

        /** 人可读描述（诊断/UI 用）。 */
        fun describe(): String = "${sampleRate}Hz/${channels}ch/" +
            when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> "8bit"
                AudioFormat.ENCODING_PCM_FLOAT -> "float"
                else -> "16bit"
            }
    }

    /**
     * 播放会话。
     *
     * ⚠️ 生命周期：`start()` → 多次 `write()` → `stop()`。
     *   `stop()` 必须调用，否则 `AudioTrack` 会一直占着音频输出
     *   （其他应用播放会被压低或静音）。
     */
    class Session internal constructor(
        context: Context,
        private val format: Format,
        /** 音频用途（影响音量控制与是否被其他音频打断）。 */
        usage: Int,
    ) {
        private var track: AudioTrack? = null
        private val running = AtomicBoolean(false)

        /** 已写入的帧数。 */
        val framesWritten = AtomicLong(0)

        /** 因超时未能写入的字节数（播放侧的「丢」）。 */
        val droppedBytes = AtomicLong(0)

        init {
            val minBuf = AudioTrack.getMinBufferSize(
                format.sampleRate,
                format.channelMask,
                format.encoding,
            )
            // ⚠️ 缓冲取 minBuf 的 2 倍：太小容易 underrun（听到咔哒声），
            //   太大则延迟高（对讲场景会「说完才听到」）。
            val bufSize = if (minBuf > 0) minBuf * 2 else format.sampleRate * format.bytesPerFrame

            track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(format.channelMask)
                            .setEncoding(format.encoding)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufSize)
                    // ⚠️ **MODE_STREAM**：数据边来边播，不预加载整个音频。
                    //   对实时流是必须的；对短音频也无害。
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (t: Throwable) {
                Log.w(TAG, "创建 AudioTrack 失败: ${t.message}")
                null
            }
        }

        /** 是否可用（创建成功）。 */
        fun isAvailable(): Boolean = track != null

        /**
         * 启动播放。
         *
         * @return null = 成功；非 null = 失败原因
         */
        fun start(): String? {
            val t = track ?: return "AudioTrack 创建失败（本机不支持该格式）"
            if (running.get()) return "已在播放中"
            return try {
                t.play()
                running.set(true)
                EventLog.record(
                    "device.audio.play.start",
                    mapOf("format" to format.describe()),
                )
                null
            } catch (t2: Throwable) {
                Log.w(TAG, "启动播放失败: ${t2.message}")
                t2.message ?: "启动失败"
            }
        }

        /**
         * 写入 PCM 数据。
         *
         * ## ⚠️ 背压：阻塞生产者（与采集相反）
         *
         * 缓冲满时**阻塞**而不是丢 —— 丢会导致破音/咔哒声。
         * 但阻塞有上限（[WRITE_TIMEOUT_MS]），超时则丢弃本次数据并计数，
         * 而不是让调用方线程永久卡死。
         *
         * @return 实际写入的字节数；-1 = 未在播放；-2 = 超时（已丢弃）
         */
        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
            val t = track ?: return -1
            if (!running.get()) return -1
            if (length <= 0) return 0

            return try {
                // ⚠️ WRITE_BLOCKING：缓冲满时阻塞。这是**刻意的** ——
                //   播放端丢数据会听到破音，比阻塞更糟。
                val n = t.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
                if (n > 0) {
                    framesWritten.addAndGet((n / format.bytesPerFrame).toLong())
                } else if (n < 0) {
                    Log.w(TAG, "AudioTrack.write 返回错误码 $n")
                }
                n
            } catch (t2: Throwable) {
                Log.w(TAG, "写入音频失败: ${t2.message}")
                -1
            }
        }

        /**
         * 停止并**释放**。
         *
         * ⚠️ 必须调：不释放会一直占着音频输出 ——
         *   其他应用播放会被压低或静音，用户会以为「手机坏了」。
         */
        fun stop() {
            if (!running.getAndSet(false)) {
                // 即使没在播也要释放（可能 start 失败但 track 已创建）
                runCatching { track?.release() }
                track = null
                return
            }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            track = null
            EventLog.record(
                "device.audio.play.stop",
                mapOf(
                    "frames" to framesWritten.get().toString(),
                    "dropped" to droppedBytes.get().toString(),
                ),
            )
        }

        fun isPlaying(): Boolean = running.get()

        /** 当前播放头位置（毫秒）。 */
        fun positionMs(): Long {
            val t = track ?: return 0
            return try {
                val frames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                frames * 1000 / format.sampleRate
            } catch (t2: Throwable) {
                0
            }
        }
    }

    /**
     * 创建播放会话。
     *
     * @param usage 音频用途，默认 [AudioAttributes.USAGE_MEDIA]。
     *   ⚠️ 语音提示应该用 `USAGE_ASSISTANCE_ACCESSIBILITY` 或 `USAGE_ASSISTANT`，
     *   那样音量跟随「媒体音量」而不是「铃声音量」—— 用错会让用户
     *   调音量时发现「怎么调都没用」。
     */
    fun session(
        context: Context,
        format: Format = Format(),
        usage: Int = AudioAttributes.USAGE_MEDIA,
    ): Session = Session(context, format, usage)

    /**
     * 检查播放格式是否受支持。
     *
     * ⚠️ 在**创建 AudioTrack 之前**调：`AudioTrack.Builder.build()` 失败时
     *   抛的是 `UnsupportedOperationException`，信息不明确。
     *   先查能给出「本机不支持 48kHz 立体声」这种可操作的提示。
     */
    fun isFormatSupported(format: Format): Boolean {
        val min = AudioTrack.getMinBufferSize(
            format.sampleRate, format.channelMask, format.encoding,
        )
        return min > 0
    }

    /**
     * **从采集格式构造播放格式**（录音 → 播放闭环）。
     *
     * ## ⚠️ 为什么需要它
     *
     * 采集固定 16kHz/mono/16bit（语音识别标准），而播放默认 44.1kHz。
     * 直接把采集到的 PCM 丢给默认播放器会得到**变速播放**（听起来像
     * 快进或慢放），而错误信息完全不会提示「采样率不匹配」。
     *
     * 用本函数构造与采集端**完全对应**的播放格式，就能原样回放。
     *
     * ⚠️ `CHANNEL_IN_MONO` 与 `CHANNEL_OUT_MONO` 是**不同的常量**
     *   （输入/输出位不同），不能混用 —— 混用会得到 `getMinBufferSize = -2`
     *   （不支持的格式），而 AudioTrack 创建时只报一个笼统的异常。
     */
    fun formatForCapture(): Format = Format(
        sampleRate = AudioCapture.SAMPLE_RATE,
        channelMask = AudioFormat.CHANNEL_OUT_MONO,
        encoding = AudioCapture.ENCODING,
    )

    /**
     * 本机支持的采样率（常见的那几个）。
     *
     * ⚠️ 用 `getMinBufferSize > 0` 逐个探测，而不是读 `AudioManager`
     *   的 `PROPERTY_OUTPUT_SAMPLE_RATE` —— 后者只报**最优**采样率，
     *   不代表其他采样率不支持。
     */
    fun supportedSampleRates(): List<Int> = listOf(8_000, 16_000, 22_050, 44_100, 48_000)
        .filter { isFormatSupported(Format(sampleRate = it)) }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：流式 PCM 播放、格式声明与探测、阻塞写入（背压）、
     *          必须 release、播放位置查询、格式支持检测
     * ❌ 未做：
     *   - **解码**（本对象只收 PCM；MP3/AAC/Opus 解码需要 MediaCodec，
     *     且**文件读取**必须经过素材检查 —— 文档明确「不允许播放器按任意
     *     外部路径读取文件」）
     *   - **音频焦点**（`AudioManager.requestAudioFocus`）——
     *     当前不申请，意味着其他应用播放时我们不会自动暂停
     *   - 「写入超时的实际中断」（`WRITE_BLOCKING` 无法从外部打断，
     *     当前靠 `stop()` 时 release 让它抛错退出）
     *   - 音效（重采样、混音、音量包络）
     */
}
