package com.dsh.android.web

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.dsh.android.core.DshApplication
import com.dsh.android.ui.MainActivity

/**
 * 网页可请求的媒体/系统能力。
 *
 * 每一个枚举项对应三件事，缺一样该能力就不可用：
 *
 * 1. `Manifest.permission.*` —— 清单里声明；
 * 2. 内核授权回调里的放行判定（system 的 `onPermissionRequest` /
 *    gecko 的 `onMediaPermissionRequest`）；
 * 3. 运行时的 `requestPermissions` 申请。
 *
 * 本项目历史上三次「功能坏了但没有任何报错」都是因为只做了第 1 步：
 * 文件选择器、下载器、录音。三种症状完全相同 —— 页面拿到的是内核的
 * 默认拒绝，用户看到「权限被拒绝」，而 Android 权限其实早就授予了。
 *
 * 把三者收敛到这一个枚举上，是为了让「新增一个能力」变成**加一个枚举项**，
 * 而不是在三四个文件里各改一处、漏一处就静默失效。
 */
enum class WebCapability(
    /** 需要向系统申请的运行时权限；`null` 表示该能力不需要运行时权限。 */
    val runtimePermission: String?,
    /** 内核回调里用来判定「这次请求要不要接」的资源名，仅用于日志。 */
    val label: String,
) {
    /** 麦克风：语音输入、录音。 */
    Microphone(Manifest.permission.RECORD_AUDIO, "microphone"),

    /** 摄像头：拍照上传、扫码、视频。 */
    Camera(Manifest.permission.CAMERA, "camera"),
}

/**
 * 页面能力授权通道 —— 内核来问宿主同不同意，宿主负责把 Android 权限也拿到。
 *
 * ## 为什么必须存在这一层
 *
 * `navigator.mediaDevices.getUserMedia()` 在 Android 的 WebView / GeckoView
 * 里**不是**只看 Android 权限就放行的，还要经过内核的授权回调：
 *
 * | 内核 | 回调 | 默认行为 |
 * |---|---|---|
 * | system | `WebChromeClient.onPermissionRequest` | **deny**（不弹框、不报错） |
 * | gecko | `PermissionDelegate.onMediaPermissionRequest` | **deny** |
 *
 * 两边默认都拒绝且不写日志。页面于是拿到 `NotAllowedError`，前端翻译成
 * 「权限被拒绝」—— 哪怕 Android 权限早就授过了。这个坑本项目踩了三次。
 *
 * ## 契约
 *
 * - **必须回调**，且必须在拿到结果之后回调。内核的授权请求是挂起的，
 *   不回调就是「页面永远停在等待授权」，按钮点了毫无反应。
 * - 用户拒绝时回 `false`（不抛异常、不静默）。页面会把它当正常拒绝处理，
 *   弹一句可理解的提示 —— 这是一条**用户能看懂**的失败路径。
 * - 宿主必须**先**确保 Android 运行时权限已授予再回 `true`。反过来的话
 *   内核放行了，音频/画面却拿不到，症状是「录出来是静音」或「黑屏」，
 *   比直接拒绝难查得多。
 */
fun interface CapabilityChannel {

    /**
     * 请求一项能力。
     *
     * @param capability 要申请的能力。
     * @param onResult true = 允许，false = 拒绝。
     */
    fun request(capability: WebCapability, onResult: (Boolean) -> Unit)
}

/**
 * [CapabilityChannel] 的默认实现：走 Android 运行时权限，拿不到就拒绝。
 *
 * ## 为什么要在宿主里再查一遍 Android 权限
 *
 * 内核只关心「这次要不要放行」，它不知道 Android 侧的授权状态。如果内核
 * 放行而 Android 权限没给，媒体流会建不起来 —— 而 WebView 在这种情况下的
 * 表现是**静默返回一个没有数据的轨道**，不是报错。用户看到的是录音时长在
 * 走、回放是静音。所以放行之前必须把 Android 权限确认掉。
 *
 * ## 与 `Activity` 的耦合
 *
 * 运行时权限要 `Activity` 才能申请。这里刻意只依赖一个 `Activity` 引用和
 * 一个「去要权限」的回调，不 import 宿主 Activity 的具体类型 —— 这样
 * `MainActivity` 拆分之后本类不需要跟着改。
 */
class RuntimePermissionCapability(
    private val activity: Activity?,
    /**
     * 去申请一项运行时权限，结果经 [onResult] 回来。
     *
     * 宿主负责把它接到自己的 `registerForActivityResult(RequestPermission())`
     * 上 —— 权限请求必须由 Activity 发起，`Context` 做不到。
     */
    private val requestPermission: (String, (Boolean) -> Unit) -> Unit,
) : CapabilityChannel {

    override fun request(capability: WebCapability, onResult: (Boolean) -> Unit) {
        val permission = capability.runtimePermission
        if (permission == null) {
            // 不需要运行时权限的能力：清单声明即可用。
            onResult(true)
            return
        }
        val ctx = activity
        if (ctx == null) {
            refuse(capability, onResult, "没有 Activity")
            return
        }
        val granted = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(DshApplication.TAG, "能力 ${capability.label}：Android 权限已授予，放行")
            onResult(true)
            return
        }
        Log.i(DshApplication.TAG, "能力 ${capability.label}：申请运行时权限 $permission")
        requestPermission(permission) { ok ->
            Log.i(
                DshApplication.TAG,
                "能力 ${capability.label}：运行时权限申请结果 $ok",
            )
            onResult(ok)
        }
    }

    private fun refuse(capability: WebCapability, onResult: (Boolean) -> Unit, why: String) {
        Log.w(DshApplication.TAG, "能力 ${capability.label}：拒绝（$why）")
        onResult(false)
    }
}
