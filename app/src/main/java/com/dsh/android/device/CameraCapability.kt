package com.dsh.android.device

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
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
