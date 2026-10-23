package com.dsh.android.device

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import com.dsh.android.log.EventLog

/**
 * **相机能力** —— 能力发现、拍照、资源释放。
 *
 * ## 为什么用 Camera2 而不是 CameraX
 *
 * 文档要求「必要 Android 平台判断及兼容依赖按第 4.8 节**最小补丁**流程处理」。
 *
 * CameraX 会引入约 3 MB 的依赖（camera-core + camera-camera2 + camera-lifecycle
 * + camera-view），而我们需要的能力只有「拍照 + 格式发现」——
 * Camera2 是系统 API，**零新增依赖**。
 *
 * 代价是 Camera2 的 API 冗长且回调状态多。对一个「基座能力」来说，
 * 代码长一点换零依赖是值得的。
 *
 * ## ⚠️ 文档要求的四条异常路径（§4.14.1a）
 *
 * > 相机 | 能力/格式发现、拍照、预览、录像，输出受管句柄 |
 * > **占用、拒权、撤权、旋转、后台限制、释放镜头**；不能用空数据通过
 *
 * 本对象覆盖「占用 / 拒权 / 释放镜头」三条。
 * 「旋转」由拍照结果的 `Orientation` 处理（见 [takePhoto] 的说明）。
 *
 * ## ⚠️ 镜头必须显式释放
 *
 * Camera2 的 `CameraDevice` 是**独占**资源。不释放的话：
 * - 其他 App 打不开相机（用户会以为是别的 App 坏了）
 * - 本应用自己下次也打不开（`ERROR_CAMERA_IN_USE`）
 *
 * 而且系统在某些情况下**不会**因为进程退出而回收 —— 必须显式 `close()`。
 */
object CameraCapability {

    private const val TAG = "DSH"

    /**
     * 相机能力信息。
     *
     * @param cameraId 相机 ID（"0" 通常是后置）
     * @param facing 朝向（`LENS_FACING_BACK` / `FRONT`）
     * @param maxPhotoSize 最大拍照分辨率
     * @param supportedFormats 支持的输出格式
     */
    data class Info(
        val cameraId: String,
        val facing: Int,
        val maxPhotoSize: Size?,
        val supportedFormats: List<Int>,
    ) {
        val isBack: Boolean get() = facing == CameraCharacteristics.LENS_FACING_BACK
        val isFront: Boolean get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    }

    /**
     * 发现所有相机。
     *
     * ⚠️ **不打开相机**：`CameraManager.getCameraIdList()` 只查列表，
     *   不会占用镜头。发现阶段占用镜头会让用户看到「相机被占用」的通知，
     *   而用户还没做任何操作 —— 那是很差的体验。
     *
     * @return 空列表 = 无相机（或查询失败）
     */
    fun discover(context: Context): List<Info> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return try {
            cm.cameraIdList.mapNotNull { id ->
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                    Info(
                        cameraId = id,
                        facing = chars.get(CameraCharacteristics.LENS_FACING)
                            ?: CameraCharacteristics.LENS_FACING_BACK,
                        maxPhotoSize = sizes.maxByOrNull { it.width * it.height },
                        supportedFormats = map?.outputFormats?.toList() ?: emptyList(),
                    )
                } catch (t: Throwable) {
                    // ⚠️ 单个相机查不到不该让整体失败：多摄设备上某个
                    //   逻辑相机可能被系统隐藏，其他仍可用。
                    Log.w(TAG, "相机 $id 查询失败: ${t.message}")
                    null
                }
            }
        } catch (t: CameraAccessException) {
            Log.w(TAG, "相机列表查询失败（可能被策略禁用）: ${t.message}")
            EventLog.record("device.camera.discoverFailed", mapOf("reason" to (t.message ?: "?")))
            emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "相机发现异常: ${t.message}")
            emptyList()
        }
    }

    /**
     * **受管拍照输出目录**（相对 `filesDir`）。
     *
     * ## ⚠️ 为什么必须是受管目录
     *
     * 文档 §4.13：「插件素材先经基座检查并**导入受管资源区**，使用资源句柄
     * 引用，**不允许播放器按任意外部路径读取文件**」。
     *
     * 所以拍照输出**不落到 `/sdcard/DCIM`**，而是落到应用私有目录 ——
     * 那里只有我们能写，插件只能通过「资源句柄」引用，不能拿路径直接读。
     */
    private const val PHOTO_DIR = "camera-out"

    /** 受管拍照目录。 */
    fun photoDir(context: Context): java.io.File =
        java.io.File(context.filesDir, PHOTO_DIR).apply { mkdirs() }

    /**
     * 一张拍好的照片。
     *
     * @param file 文件（在受管目录内）
     * @param sha256 内容哈希（前 32 位十六进制）。
     *   ⚠️ 文档：「**真实文件内容哈希**用于校验和去重，**不用字节数代替内容指纹**」
     * @param width / height 像素尺寸
     * @param orientationDegrees 需要旋转的角度（0/90/180/270）
     */
    data class Photo(
        val file: java.io.File,
        val sha256: String,
        val width: Int,
        val height: Int,
        val orientationDegrees: Int,
        val takenAtMs: Long,
    )

    /**
     * **真正拍照**（输出到受管目录）。
     *
     * ## 与 [takePhoto] 的区别
     *
     * | 方法 | 做什么 |
     * |---|---|
     * | [takePhoto] | 只验证「能打开相机」，不开会话、不产出图片 |
     * | 本方法 | 建 CaptureSession → ImageReader → 拿到 JPEG 字节 |
     *
     * ## ⚠️ 五个必须处理的点
     *
     * 1. **会话配置失败**：`createCaptureSession` 在镜头被占用时会抛
     *    `CameraAccessException`，而 `onConfigureFailed` 只给一个笼统回调
     * 2. **超时**：`onImageAvailable` 可能永不回调（镜头故障/对焦失败）
     * 3. **旋转**：JPEG 里带 EXIF Orientation，**不要**自己转像素
     *    （转像素会重编码，损失质量；EXIF 是标准做法）
     * 4. **镜头必须释放**：`close()`，否则其他 App 打不开相机
     * 5. **输出必须在受管目录**：见 [photoDir]
     *
     * @param onDone 回调（主线程）。**无论成败都会调一次**。
     */
    fun capturePhoto(
        context: Context,
        cameraId: String? = null,
        onDone: (CaptureResult) -> Unit,
    ) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            onDone(CaptureResult.NoHardware)
            return
        }
        val all = discover(context)
        if (all.isEmpty()) {
            onDone(CaptureResult.NoHardware)
            return
        }
        val target = cameraId ?: (all.firstOrNull { it.isBack } ?: all.first()).cameraId
        val info = all.firstOrNull { it.cameraId == target } ?: all.first()

        // 用最大 JPEG 尺寸（文档要求「能力/格式发现」，这里取已发现的最大值）
        val size = info.maxPhotoSize ?: run {
            onDone(CaptureResult.Failed("该相机不支持 JPEG 输出"))
            return
        }

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        // 超时兜底：onImageAvailable 可能永不回调
        val timeout = Runnable {
            if (!done.compareAndSet(false, true)) return@Runnable
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            Log.w(TAG, "拍照超时")
            EventLog.record("device.camera.captureTimeout")
            onDone(CaptureResult.Timeout)
        }
        main.postDelayed(timeout, CAPTURE_TIMEOUT_MS)

        fun cleanup() {
            main.removeCallbacks(timeout)
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
        }

        try {
            val r = ImageReader.newInstance(
                size.width, size.height,
                android.graphics.ImageFormat.JPEG, 1,
            )
            reader = r

            r.setOnImageAvailableListener({ ir ->
                if (!done.compareAndSet(false, true)) return@setOnImageAvailableListener
                try {
                    val image = ir.acquireNextImage()
                    if (image == null) {
                        cleanup()
                        main.post { onDone(CaptureResult.Failed("拿不到图像")) }
                        return@setOnImageAvailableListener
                    }
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    image.close()

                    // 校验非空（文档：「不能用空数据通过」）
                    if (bytes.isEmpty()) {
                        cleanup()
                        main.post { onDone(CaptureResult.Failed("拍到空数据")) }
                        return@setOnImageAvailableListener
                    }

                    // 落盘 + 算哈希
                    val photo = savePhoto(context, bytes, size.width, size.height, info)
                    cleanup()
                    EventLog.record(
                        "device.camera.captured",
                        mapOf("bytes" to bytes.size.toString(), "hash" to photo.sha256.take(8)),
                    )
                    main.post { onDone(CaptureResult.Ok(photo)) }
                } catch (t: Throwable) {
                    cleanup()
                    Log.w(TAG, "处理照片失败: ${t.message}")
                    main.post { onDone(CaptureResult.Failed(t.message ?: "处理失败")) }
                }
            }, main)

            cm.openCamera(info.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    try {
                        val surface = r.surface
                        // ⚠️ 用 TEMPLATE_STILL_CAPTURE：相机会针对静态照片优化
                        //   （曝光、对焦策略与预览不同）。
                        d.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = d.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE,
                                        ).apply {
                                            addTarget(surface)
                                            // 自动对焦 + 自动曝光
                                            set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                            )
                                            set(
                                                CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON,
                                            )
                                        }.build()
                                        s.capture(req, null, null)
                                    } catch (t: Throwable) {
                                        if (!done.compareAndSet(false, true)) return
                                        cleanup()
                                        main.post { onDone(CaptureResult.Failed("发起拍照失败: ${t.message}")) }
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    if (!done.compareAndSet(false, true)) return
                                    cleanup()
                                    main.post { onDone(CaptureResult.Failed("会话配置失败（可能被占用）")) }
                                }
                            },
                            main,
                        )
                    } catch (t: Throwable) {
                        if (!done.compareAndSet(false, true)) return
                        cleanup()
                        main.post { onDone(CaptureResult.Failed("建会话失败: ${t.message}")) }
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (!done.compareAndSet(false, true)) return
                    cleanup()
                    main.post { onDone(CaptureResult.Busy) }
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (!done.compareAndSet(false, true)) return
                    cleanup()
                    main.post { onDone(CaptureResult.Failed("相机错误码 $error")) }
                }
            }, main)
        } catch (t: SecurityException) {
            cleanup()
            main.removeCallbacks(timeout)
            if (done.compareAndSet(false, true)) onDone(CaptureResult.NoPermission)
        } catch (t: Throwable) {
            cleanup()
            main.removeCallbacks(timeout)
            if (done.compareAndSet(false, true)) {
                onDone(CaptureResult.Failed(t.message ?: "拍照异常"))
            }
        }
    }

    /**
     * 把 JPEG 字节落盘到受管目录，并算内容哈希。
     *
     * ⚠️ 文件名用**哈希前缀**而不是时间戳：
     *   文档要求「真实文件内容哈希用于**校验和去重**」——
     *   用哈希做文件名，同一张照片重复保存会自然去重。
     */
    private fun savePhoto(
        context: Context,
        bytes: ByteArray,
        width: Int,
        height: Int,
        info: Info,
    ): Photo {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val f = java.io.File(photoDir(context), "${hash.take(32)}.jpg")
        // 已存在 ⇒ 内容相同，不重写（去重）
        if (!f.isFile) f.writeBytes(bytes)

        // EXIF Orientation：**不要**自己转像素（会重编码损失质量）
        val orientation = readExifOrientation(bytes)
        return Photo(
            file = f,
            sha256 = hash,
            width = width,
            height = height,
            orientationDegrees = orientation,
            takenAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * 从 JPEG 字节读 EXIF Orientation → 角度。
     *
     * ⚠️ 用 `android.media.ExifInterface`（API 24+ 支持 InputStream），
     *   而不是自己解析 EXIF —— 手写解析器在遇到厂商私有段时会错位。
     *
     * @return 0/90/180/270；读不到时返回 0
     */
    private fun readExifOrientation(bytes: ByteArray): Int = try {
        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (t: Throwable) {
        Log.d(TAG, "读 EXIF 失败（按 0 度处理）: ${t.message}")
        0
    }

    // ── 录像 ────────────────────────────────────────────────────────────

    /**
     * 录像参数。
     *
     * ⚠️ 文档 §4.13 要求「**限制类型、单文件/总量**、解码尺寸、并发播放器
     * 和资源占用」—— 录像是最容易失控的一项（长时间录制会耗尽磁盘），
     * 所以参数里**强制**有上限。
     */
    data class RecordConfig(
        /** 分辨率。默认 1280×720（1080p 在低端机上可能掉帧）。 */
        val width: Int = 1280,
        val height: Int = 720,
        /** 帧率。30fps 是通用值。 */
        val fps: Int = 30,
        /** 码率（bps）。默认 5 Mbps ≈ 720p30 的合理值。 */
        val bitRate: Int = 5_000_000,
        /**
         * **最长录制时长（毫秒）**。
         *
         * ⚠️ 必须有上限：不设的话用户忘了停，一次录满磁盘 ——
         *   而写满磁盘会让整个 App（含引擎）无法工作，不只是录像失败。
         */
        val maxDurationMs: Long = 60_000L,
        /** 是否录声音。默认 false（相机能力的录像默认不涉及麦克风授权）。 */
        val withAudio: Boolean = false,
    )

    /**
     * 一段录好的视频。
     */
    data class Video(
        val file: java.io.File,
        val sha256: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    )

    /**
     * 录像结果。
     */
    sealed interface RecordResult {
        data class Ok(val video: Video) : RecordResult
        data object NoHardware : RecordResult
        data object NoPermission : RecordResult
        data object Busy : RecordResult
        /** 达到时长上限，**已自动停止并保存**。 */
        data class MaxDurationReached(val video: Video) : RecordResult
        /** 磁盘空间不足（开始前就拒绝）。 */
        data class NoSpace(val freeMb: Long) : RecordResult
        data class Failed(val reason: String) : RecordResult
    }

    /**
     * 磁盘安全水位（字节）。
     *
     * ⚠️ 留 200 MB：录像本身可能写几十 MB，但**写满磁盘会让整个 App 失效**
     *   （引擎、日志、会话都写不了）。所以宁可在快满时拒绝录像，
     *   也不能把设备写死。
     */
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024

    /** 视频受管目录（与照片并列）。 */
    private const val VIDEO_DIR = "camera-out-video"

    /** 受管视频目录。 */
    fun videoDir(context: Context): java.io.File =
        java.io.File(context.filesDir, VIDEO_DIR).apply { mkdirs() }

    /**
     * **录像**。
     *
     * ## 完整链路
     *
     * ```
     * prepare(MediaRecorder) → openCamera → createCaptureSession(recorder.surface)
     *   → recorder.start() → (等待/到时) → recorder.stop() → 落盘
     * ```
     *
     * ## ⚠️ 五个必须处理的点
     *
     * 1. **磁盘空间**：开始前检查，不足直接拒绝（见 [MIN_FREE_BYTES]）
     * 2. **时长上限**：到时**自动停止并保存**，不是丢弃
     * 3. **`recorder.stop()` 可能抛异常**：录制时长过短（<1 秒）时
     *    MediaRecorder 会抛 `RuntimeException`，而文件仍是**有效的但很短**
     * 4. **镜头必须释放**：`close()`，否则其他 App 打不开相机
     * 5. **输出必须在受管目录**：同拍照
     *
     * @param onDone 回调（主线程）。**无论成败都会调一次**。
     * @param onStarted 录制真正开始后回调（用于更新 UI 状态）
     */
    fun recordVideo(
        context: Context,
        config: RecordConfig = RecordConfig(),
        cameraId: String? = null,
        onStarted: () -> Unit = {},
        onDone: (RecordResult) -> Unit,
    ) {
        // ① 磁盘空间
        val free = try { context.filesDir.usableSpace } catch (t: Throwable) { -1L }
        if (free in 1 until MIN_FREE_BYTES) {
            EventLog.record("device.camera.noSpace", mapOf("freeMb" to (free / 1024 / 1024).toString()))
            onDone(RecordResult.NoSpace(free / 1024 / 1024))
            return
        }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) { onDone(RecordResult.NoHardware); return }
        val all = discover(context)
        if (all.isEmpty()) { onDone(RecordResult.NoHardware); return }
        val target = cameraId ?: (all.firstOrNull { it.isBack } ?: all.first()).cameraId
        val info = all.firstOrNull { it.cameraId == target } ?: all.first()

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: android.media.MediaRecorder? = null
        val outFile = java.io.File(
            videoDir(context),
            "rec-${System.currentTimeMillis()}.mp4",
        )
        val startAt = System.currentTimeMillis()
        var maxDurationTimer: Runnable? = null

        fun cleanup(keepFile: Boolean) {
            maxDurationTimer?.let { main.removeCallbacks(it) }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { recorder?.release() }
            if (!keepFile) runCatching { outFile.delete() }
        }

        /** 停止录制并产出结果。 */
        fun finish(reason: String) {
            if (!done.compareAndSet(false, true)) return
            val duration = System.currentTimeMillis() - startAt
            val r = recorder
            var stopError: String? = null
            if (r != null) {
                try {
                    r.stop()
                } catch (t: Throwable) {
                    // ⚠️ 录制过短时 MediaRecorder.stop() 会抛 RuntimeException，
                    //   而**文件仍然有效**（只是很短）。不能因为异常就删掉它 ——
                    //   那样用户会「明明录了却什么都没有」。
                    stopError = t.message
                    Log.w(TAG, "recorder.stop() 抛异常（文件可能仍有效）: ${t.message}")
                }
            }
            cleanup(keepFile = outFile.isFile && outFile.length() > 0)

            if (!outFile.isFile || outFile.length() == 0L) {
                EventLog.record("device.camera.recordEmpty", mapOf("reason" to reason))
                main.post { onDone(RecordResult.Failed(stopError ?: "录像文件为空")) }
                return
            }

            val hash = sha256Of(outFile)
            val video = Video(
                file = outFile,
                sha256 = hash,
                durationMs = duration,
                width = config.width,
                height = config.height,
                sizeBytes = outFile.length(),
            )
            EventLog.record(
                "device.camera.recorded",
                mapOf(
                    "ms" to duration.toString(),
                    "bytes" to video.sizeBytes.toString(),
                    "hash" to hash.take(8),
                ),
            )
            main.post {
                onDone(
                    if (reason == "max-duration") RecordResult.MaxDurationReached(video)
                    else RecordResult.Ok(video),
                )
            }
        }

        try {
            val r = android.media.MediaRecorder()
            recorder = r

            // ② 配置
            if (config.withAudio) {
                r.setAudioSource(android.media.MediaRecorder.AudioSource.CAMCORDER)
            }
            r.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(outFile.absolutePath)
            r.setVideoEncodingBitRate(config.bitRate)
            r.setVideoFrameRate(config.fps)
            r.setVideoSize(config.width, config.height)
            r.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            if (config.withAudio) {
                r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            // ③ 时长上限（MediaRecorder 自己也能限，但双保险：
            //   它到时会发 MAX_DURATION_REACHED 信息，我们还要主动 stop）
            r.setMaxDuration(config.maxDurationMs.toInt())
            r.prepare()

            cm.openCamera(info.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    try {
                        val surface = r.surface
                        d.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = d.createCaptureRequest(
                                            CameraDevice.TEMPLATE_RECORD,
                                        ).apply { addTarget(surface) }.build()
                                        s.setRepeatingRequest(req, null, main)
                                        r.start()
                                        EventLog.record("device.camera.recordStart")
                                        onStarted()

                                        // 主动超时（不依赖 MediaRecorder 的信息回调）
                                        val timer = Runnable { finish("max-duration") }
                                        maxDurationTimer = timer
                                        main.postDelayed(timer, config.maxDurationMs)
                                    } catch (t: Throwable) {
                                        if (!done.compareAndSet(false, true)) return
                                        cleanup(keepFile = false)
                                        main.post { onDone(RecordResult.Failed("启动录制失败: ${t.message}")) }
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    if (!done.compareAndSet(false, true)) return
                                    cleanup(keepFile = false)
                                    main.post { onDone(RecordResult.Failed("会话配置失败（可能被占用）")) }
                                }
                            },
                            main,
                        )
                    } catch (t: Throwable) {
                        if (!done.compareAndSet(false, true)) return
                        cleanup(keepFile = false)
                        main.post { onDone(RecordResult.Failed("建会话失败: ${t.message}")) }
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    finish("disconnected")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (!done.compareAndSet(false, true)) return
                    cleanup(keepFile = false)
                    main.post { onDone(RecordResult.Failed("相机错误码 $error")) }
                }
            }, main)
        } catch (t: SecurityException) {
            cleanup(keepFile = false)
            if (done.compareAndSet(false, true)) onDone(RecordResult.NoPermission)
        } catch (t: Throwable) {
            cleanup(keepFile = false)
            if (done.compareAndSet(false, true)) {
                onDone(RecordResult.Failed(t.message ?: "录像异常"))
            }
        }
    }

    /** 算文件内容哈希。 */
    private fun sha256Of(f: java.io.File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }

    /**
     * 真正拍照的结果。
     */
    sealed interface CaptureResult {
        data class Ok(val photo: Photo) : CaptureResult
        data object NoHardware : CaptureResult
        data object NoPermission : CaptureResult
        data object Busy : CaptureResult
        data object Timeout : CaptureResult
        data class Failed(val reason: String) : CaptureResult
    }

    /**
     * 拍照超时（毫秒）。
     *
     * ⚠️ 必须有：`onImageAvailable` 在镜头故障/对焦失败时可能**永不回调**。
     */
    const val CAPTURE_TIMEOUT_MS = 10_000L

    /**
     * 拍照。
     *
     * ## ⚠️ 三条必须处理的异常
     *
     * | 情况 | 表现 | 处理 |
     * |---|---|---|
     * | 镜头被占用 | `CameraAccessException.CAMERA_IN_USE` | 返回 [Result.Busy] |
     * | 没授权 | `SecurityException` | 返回 [Result.NoPermission] |
     * | 设备无相机 | `getCameraIdList()` 空 | 返回 [Result.NoHardware] |
     *
     * 文档要求「**不能用空数据通过**」—— 所以每一种失败都返回明确的
     * 失败类型，而不是「返回 null 让调用方猜」。
     *
     * @param cameraId 用哪个相机；null = 自动选后置（没有再选前置）
     * @param onDone 回调（在主线程）。**无论成功失败都会调** —— 调用方靠它
     *   知道「这次拍照结束了」，从而释放 UI 上的 loading 状态。
     */
    fun takePhoto(
        context: Context,
        cameraId: String? = null,
        onDone: (Result) -> Unit,
    ) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            onDone(Result.NoHardware)
            return
        }

        val all = discover(context)
        if (all.isEmpty()) {
            EventLog.record("device.camera.noHardware")
            onDone(Result.NoHardware)
            return
        }
        val target = cameraId ?: (all.firstOrNull { it.isBack } ?: all.first()).cameraId
        val info = all.firstOrNull { it.cameraId == target } ?: all.first()

        // ⚠️ 打开相机必须在 try 里：SecurityException（无权限）与
        //   CameraAccessException（占用）都可能从 openCamera 抛出。
        try {
            cm.openCamera(info.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    // ⚠️ **立刻关闭**：本实现只做「能力验证 + 单张拍照」，
                    //   不保持长连接。保持打开会一直占着镜头与耗电，
                    //   而且用户看不到任何东西（没有预览）。
                    //
                    //   真正的「预览 + 录像」需要 Surface + 会话配置，
                    //   那是独立的、更大的功能（见本对象边界说明）。
                    try {
                        device.close()
                        EventLog.record("device.camera.opened")
                        onDone(Result.Ok(info))
                    } catch (t: Throwable) {
                        Log.w(TAG, "相机打开后关闭失败: ${t.message}")
                        onDone(Result.Failed("关闭镜头失败: ${t.message}"))
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    // 被别的 App 抢走了（或用户拔了外接相机）
                    device.close()
                    Log.w(TAG, "相机被断开")
                    EventLog.record("device.camera.disconnected")
                    onDone(Result.Busy)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    val msg = when (error) {
                        ERROR_CAMERA_IN_USE -> "镜头被占用"
                        ERROR_MAX_CAMERAS_IN_USE -> "同时打开的相机数超限"
                        ERROR_CAMERA_DISABLED -> "相机被系统策略禁用"
                        ERROR_CAMERA_DEVICE -> "相机硬件错误"
                        ERROR_CAMERA_SERVICE -> "相机服务错误"
                        else -> "未知错误 $error"
                    }
                    Log.w(TAG, "相机错误: $msg")
                    EventLog.record("device.camera.error", mapOf("msg" to msg))
                    onDone(Result.Failed(msg))
                }
            }, null)
        } catch (t: SecurityException) {
            Log.w(TAG, "相机无权限: ${t.message}")
            EventLog.record("device.camera.noPermission")
            onDone(Result.NoPermission)
        } catch (t: CameraAccessException) {
            val busy = t.reason == CameraAccessException.CAMERA_IN_USE
            Log.w(TAG, "相机访问异常: ${t.message}")
            onDone(if (busy) Result.Busy else Result.Failed(t.message ?: "访问失败"))
        } catch (t: Throwable) {
            Log.w(TAG, "相机打开异常: ${t.message}")
            onDone(Result.Failed(t.message ?: "打开失败"))
        }
    }

    /**
     * 拍照结果。
     *
     * ⚠️ **不用 null 表示失败**：文档要求「不能用空数据通过」，
     *   而 null 无法区分「没硬件」「没权限」「被占用」——
     *   那三种的用户可操作性完全不同。
     */
    sealed interface Result {
        /** 相机可用且已成功打开（本轮验证通过）。 */
        data class Ok(val info: Info) : Result

        /** 设备没有相机。 */
        data object NoHardware : Result

        /** 没有相机权限。 */
        data object NoPermission : Result

        /** 镜头被占用（别的 App 在用）。 */
        data object Busy : Result

        /** 其他失败。 */
        data class Failed(val reason: String) : Result
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：相机发现（不占用镜头）、打开验证、四类失败明确区分、
     *          镜头立即释放、按 API 处理占用/拒权/断开
     * ❌ 未做：
     *   - **预览**（需要 Surface + CaptureSession，属 UI 层功能）
     *   - **录像**（需要 MediaRecorder + 会话配置）
     *   - **实际输出图片**（当前只验证「能打开」，没接 ImageReader）
     *   - 「旋转」处理（需要读 JPEG 的 EXIF Orientation）
     *   - 「后台限制」（后台不能开相机 —— 需在调用方判前后台）
     *
     * ⚠️ 这三项（预览/录像/输出）是**相机功能的主体**，不是「顺带能做」的。
     *   本对象只建立了「能力发现 + 资源安全释放」这一层 ——
     *   那是所有相机功能的前提，也是文档强调的「释放镜头」所在。
     */
}
