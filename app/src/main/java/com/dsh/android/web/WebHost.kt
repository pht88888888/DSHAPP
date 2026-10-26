package com.dsh.android.web

import android.net.Uri
import android.view.View
import java.io.InputStream

/**
 * 「Web 内核」的宿主抽象 —— 两个 flavor 各实现一份。
 *
 * | flavor | 实现 | 内核 |
 * |---|---|---|
 * | `system` | [com.dsh.android.web.SystemWebHost] | 设备上的系统 WebView |
 * | `gecko` | [com.dsh.android.web.GeckoWebHost] | APK 内置的 GeckoView |
 *
 * [com.dsh.android.MainActivity] 只对着这个接口写一次，两个版本共用同一份启动、
 * 键盘预判、返回键、启动层逻辑。
 *
 * ## 为什么值得抽这一层
 *
 * 两个内核的差异**不是「换个控件」那么小**，而是三个结构性差异：
 *
 * 1. **JS 执行**：WebView 有 `evaluateJavascript`（带回调）；GeckoView **没有**
 *    任何等价 API，官方明确要求走 WebExtension。见 [evaluateJavascript]。
 * 2. **原生桥**：WebView 用 `addJavascriptInterface`（**同步**返回）；GeckoView
 *    只能走 native messaging（**异步**）。见 [com.dsh.android.web.PageBridge]。
 * 3. **首帧**：WebView 是 `postVisualStateCallback`；GeckoView 是
 *    `ContentDelegate.onFirstComposite`。见 [awaitFirstFrame]。
 *
 * ⚠️ 所有方法**必须在主线程调用**（两个内核的 View 操作都是 `@UiThread`）。
 */
interface WebHost {

    /**
     * 承载网页的 View，由宿主加进布局容器。
     *
     * 调用方只用它做两件与内核无关的事：改 `layoutParams`（键盘预判缩矮）、
     * `setBackgroundColor`（消除键盘升起时的异色闪块）。
     */
    val view: View

    /** 加载 URL。 */
    fun loadUrl(url: String)

    /**
     * 重新加载 [currentUrl]。
     *
     * ## 为什么需要它（这是「渲染进程崩溃后白屏」的恢复路径）
     *
     * 两个内核都**不会**自己从渲染进程崩溃里恢复：
     *
     * - WebView：渲染进程死掉后，`WebView` 变成一个画不出任何东西的空壳，
     *   页面内容不会自动重来。宿主若不处理，用户看到的是一块白（或黑）屏，
     *   而且**没有任何提示** —— 退回桌面再进来也一样，因为 Activity 没重建。
     * - GeckoView：内容进程崩溃后 session 同样停在坏状态，必须重新加载。
     *
     * 崩溃本身无法阻止（那是内核进程，宿主进程管不到），但**恢复是可以的**：
     * 引擎还在跑，URL 还有效，`loadUrl` 一次就能把界面带回来。
     *
     * ⚠️ 实现方**不应**在这里切换 `currentUrl` 的语义 —— 它仍是「最近一次**发起**
     *   的 URL」，重载不改它。
     * ⚠️ 由 [com.dsh.android.MainActivity] 在收到 `onRendererGone` 之后调用，
     *   调用时机由宿主的退避策略决定（见那里的注释）。
     */
    fun reload()

    /**
     * 当前 URL；还没加载过任何页面时为 null。
     *
     * ⚠️ 语义是「最近一次发起加载的 URL」，不是内核内部的重定向结果 ——
     *   宿主只用它做「与上次相同就不重复加载」的去重判断（见 `observeEngine`）。
     */
    val currentUrl: String?

    /**
     * 执行一段 JS，并把结果回给 [callback]。
     *
     * ⚠️ **两个内核的结果编码必须一致**，否则宿主的解析会错。约定：
     *   结果是 **JSON 编码后的文本**（与 WebView 的 `evaluateJavascript` 相同）。
     *   于是 `"true"` / `"false"` / `"NO_INPUT"` 这些页面侧返回值，宿主用
     *   `result.trim('"')` 或 `contains(...)` 都能正确判定。
     *
     * ⚠️ GeckoView 侧这条通道**是异步的、且比 WebView 慢**（要过一次扩展的
     *   IPC）。调用方不应假设它「立刻」回来 —— 但也不该为它加超时：拿不到结果
     *   时的行为已由各调用点自己的兜底分支定义（例如返回键的「桥不存在则退出」）。
     *
     * @param callback 可为 null（不需要结果时）。**不保证一定被调用** ——
     *   GeckoView 的扩展还没就绪、页面已销毁时不会回调，调用方必须有兜底。
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)

    /**
     * 等内核把当前内容**真正提交了一帧**，然后执行 [action]。
     *
     * 「DOM 就绪」与「像素上屏」之间有落差（真机实测约 1.6 秒），启动层必须等
     * 后者才收起，否则用户会看到「动画结束后闪过几帧白屏」。
     *
     * ⚠️ **不保证一定触发**（渲染进程崩溃、View 被销毁时不会来），调用方必须
     *   自备兜底超时 —— 启动层永久盖住界面是最糟的失败方式。
     * ⚠️ 已经提交过首帧之后再调用，应当**立刻**执行 [action]。
     */
    fun awaitFirstFrame(action: () -> Unit)

    /**
     * 清掉「已提交过首帧」的记忆，让下一次 [awaitFirstFrame] 重新等**新的一帧**。
     *
     * ## 🔴 为什么必须有这个方法：`reloadWithRetry` 的成功判据一度是假的
     *
     * [awaitFirstFrame] 的语义是「**当前内容**提交了一帧」，已提交过就立刻回调 ——
     * 这对启动层是对的（界面早就在了，不该再等）。但
     * `MainActivity.reloadWithRetry()` 需要的是另一个问题：「**这次 reload 之后**
     * 有没有拿到新帧」—— 而 `painted` 在渲染进程崩溃前**早就是 true**，
     * 于是 `awaitFirstFrame { ... }` 里的动作**同步立即执行**，
     * 「界面已恢复」在 `web.reload()` **还没发生**时就打出来了。
     *
     * 真机证据（`20261014-010259-8fbc8515.zip`，`onRendererGone` 之后的恢复流程）：
     * ```
     * 01:02:21.060  重载界面（第 1 次尝试）
     * 01:02:21.061  Web 内核已提交首帧，收起启动动画     ← 1 ms 后
     * 01:02:21.061  界面已恢复（第 1 次尝试拿到首帧）    ← 判据被瞬时满足
     * 01:02:21.061  重载界面: http://127.0.0.1:42023/   ← reload 这才发生
     * ```
     * 三行同 1 毫秒，而真正 `reload()` 排在它们**后面**。
     *
     * 后果有两层，都很隐蔽：
     * 1. 「已恢复」是假的 —— 渲染进程要是真没救回来，日志照样报成功；
     * 2. `rendererHandler.postDelayed` 里那句 `if (webViewPainted) return` 永远命中，
     *    于是**重试机制永久失效**（真机全量 53 包：`界面未恢复，再试一次` 0 次、
     *    `界面重载 N 次仍未恢复` 0 次，`重载界面（第` 仅 1 次）。
     *
     * ⚠️ 调用方在 `reload()` **之前**调它，顺序不能反。
     */
    fun resetFirstFrame()

    /** 释放内核资源。 */
    fun destroy()

    /** 内核主版本号；0 = 读不到（此时宿主不做版本拦截）。 */
    val engineMajor: Int

    /** 内核描述（厂商 + 版本），只用于日志。 */
    val engineLabel: String
}

/**
 * 文件选择通道 —— 内核发出请求，宿主拉起系统选择器，再把结果交回内核。
 *
 * ⚠️ **三条路径都必须回调**（成功 / 取消 / 拉起失败），漏掉任何一条都会让页面
 *   永久卡在「等待选择」状态。取消必须回 `null` 而不是空数组 —— 空数组会被
 *   页面解读成「选了 0 个文件」并触发一次空提交。
 */
fun interface FileChooserChannel {
    fun launch(multiple: Boolean, onResult: (Array<Uri>?) -> Unit)
}

/**
 * 麦克风授权通道 —— 页面要录音，内核来问宿主同不同意。
 *
 * ## 为什么必须存在这一层（本项目第三处「默认实现静默失败」）
 *
 * 页面的语音输入（`dsh-experimental-client-ui-voice-input`）调
 * `navigator.mediaDevices.getUserMedia({audio: true})`。这一句在 Android 的
 * WebView / GeckoView 里**不是**只看 Android 权限就放行的，而是要经过内核的
 * 授权回调：
 *
 * | 内核 | 回调 | 默认行为 |
 * |---|---|---|
 * | system | `WebChromeClient.onPermissionRequest` | **deny**（不弹框、不报错） |
 * | gecko | `PermissionDelegate.onMediaPermissionRequest` | **deny** |
 *
 * 两边的默认实现都是拒绝，且**不留任何日志**。页面侧于是拿到
 * `NotAllowedError`，前端把它翻译成 `RecordingError("permission")` ——
 * 界面文案永远是「麦克风权限被拒绝」，**哪怕 Android 权限早就授过了**。
 * 这正是本项目在文件选择器（⑦）与下载器（⑦.5）上踩过的同一种坑。
 *
 * ## 契约
 *
 * ⚠️ **必须回调**，且**必须在拿到结果之后回调**：内核的授权请求是挂起的，
 *   不回就是「页面永远停在等待授权的状态」，录音按钮点了没有任何反应。
 * ⚠️ 用户拒绝时回 `false`（不是抛异常、也不是不回）—— 页面会把它当正常拒绝
 *   处理，弹一句「权限被拒绝」，这是一条**用户可理解**的失败路径。
 * ⚠️ 宿主应当在回调里**先**确保 Android 运行时权限（`RECORD_AUDIO`）已授予，
 *   再回 `true`；否则内核放行了、录音却拿不到音频，症状是「录出来是静音」——
 *   比直接拒绝难查得多。
 */
fun interface MicrophoneChannel {
    /**
     * 请求麦克风使用权。
     *
     * @param onResult true = 允许，false = 拒绝。
     */
    fun request(onResult: (Boolean) -> Unit)
}

/**
 * 一个下载请求。
 *
 * ⚠️ 两个内核**触发下载的机制完全不同**，所以这个类只描述「要下什么」，不描述
 *   「谁触发的」：
 *   - WebView：`setDownloadListener`（`<a download>` 会落到它上面）
 *   - GeckoView：**没有** DownloadListener，改由 content script 拦 `<a download>`
 *     的点击、经原生桥转成一条 [DownloadRequest]
 *
 * 两条路的落点都是 `MainActivity.handleDownload()`，那里已验过 cookie 鉴权
 * （`/api/session.export` 是纯 cookie 鉴权，用系统 DownloadManager 拿不到）。
 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
)

/**
 * 一次**内核已经代发**的下载响应（gecko 专用）。
 *
 * ⚠️ 与 [DownloadRequest] 的分工是**本质的**，不是风格差异：
 *   - [DownloadRequest]（system）：内核只给 URL，**宿主自己发 HTTP** ⇒ 宿主必须
 *     自己带上鉴权 cookie。
 *   - [DownloadStream]（gecko）：**内核已经把这次请求发完了**（cookie 由内核自动
 *     带上），宿主拿到的就是响应体流 ⇒ 宿主不需要、也**拿不到** cookie。
 *
 * 后者存在的唯一原因：GeckoView **没有任何读取 cookie 的 API** ——
 * `GeckoSession` / `GeckoRuntime` / `StorageController` 上只有 cookie-banner
 * 相关方法（javap 逐个查过），而 GeckoView 的 cookie 存在自己的
 * `files/mozilla/<profile>/cookies.sqlite` 里、**不写进** WebView 的 cookie jar。
 * 于是「宿主代发 + 自己带 cookie」这条路在 gecko 上根本走不通
 * （实测 gecko 版连 `app_webview/` 目录都不存在，WebView 从未初始化），
 * 只能反过来：**让内核自己发**，宿主只负责落盘。
 */
data class DownloadStream(
    val url: String,
    /** 已从 `Content-Disposition` 解出的文件名；解不出时由宿主兜底。 */
    val filename: String,
    val body: InputStream,
)

/**
 * 内核 → 宿主的回调集合。
 *
 * @property onPageFinished DOM 就绪（`WebViewClient.onPageFinished` /
 *   `ProgressDelegate.onPageStop`）。⚠️ 它**不代表界面可用** —— 实测之后还有约
 *   1.7 秒的插件激活期，那段由 [PageBridge.onUiReady] 收尾。
 * @property onConsole 内核控制台消息，转发到同一个 `DSH` TAG（前缀 `[web]`）。
 * @property onExternalUrl 非本地引擎的 URL（外链），交给系统浏览器。
 * @property onDownload 宿主**自己发 HTTP** 的下载（system 版）。
 * @property onDownloadStream 内核**已经代发**、只剩落盘的下载（gecko 版）。
 *   ⚠️ 两个内核各只会调其中一个 —— 见 [DownloadStream] 的说明。
 * @property onRendererGone 承载页面的**内核进程死了**（WebView 的
 *   `onRenderProcessGone` / GeckoView 的 `onCrash`）。
 *
 *   ⚠️ **必须处理，且默认行为是致命的**：`WebViewClient.onRenderProcessGone`
 *   的默认实现返回 `false`，而文档明确写着 —— 返回 false 时**整个应用进程会被
 *   系统杀掉**。也就是说，用户遇到的「App 突然自己退了/闪退」，很可能根本不是
 *   App 的 bug，而是这个回调没被覆写。
 *
 *   ⚠️ 参数里的 `didCrash` 只是给日志用的分类：`true` = 渲染进程崩了，
 *   `false` = 被系统回收（低内存时系统会主动收掉后台 WebView 的渲染进程，
 *   **这不是异常**，但界面同样需要重载才会回来）。
 */
class WebHostCallbacks(
    val bridge: PageBridge,
    val onPageFinished: (String?) -> Unit,
    val onConsole: (String) -> Unit,
    val onExternalUrl: (String) -> Unit,
    val fileChooser: FileChooserChannel,
    val onDownload: (DownloadRequest) -> Unit,
    val onDownloadStream: (DownloadStream) -> Unit,
    val onRendererGone: (didCrash: Boolean) -> Unit,
    /** 页面要录音时的麦克风授权通道（见 [MicrophoneChannel]）。 */
    val microphone: MicrophoneChannel,
    /**
     * 页面用 `window.open("about:blank")` 预开了一个**还没有 URL** 的窗口
     * （官方登录 `BrowserLogin.begin` 就是这条路径），内核需要宿主给一个
     * 真实的 `WebView` 当渲染目标。
     *
     * 宿主返回一个**已经挂到可见容器里**的 WebView，内核随后会让页面自己
     * 导航过去（`popup.location.replace(authorizeUrl)`）。
     *
     * ⚠️ 返回 `null` = 宿主不管，内核会丢弃这次 `window.open`（`popup` 变
     *   `null`），页面侧那句 `popup.location.replace(...)` 就静默失败 ——
     *   用户看到的是「点登录，什么都没发生」。
     */
    val onCreatePopup: (() -> android.webkit.WebView?)? = null,
    /**
     * 内核侧要给用户看的**一句话提示**（宿主实现成 Toast）。
     *
     * ⚠️ 存在的理由：手机上看不到 `console.log`，而内核在若干「功能降级」的
     *   路径上无法自己弹 UI（GeckoView 的宿主回调里没有 Activity 引用）。
     *   只写日志的结果是「用户觉得功能莫名其妙没了，开发者只能靠他复述」。
     *   这和本项目「错误必须显示在屏幕上」的既定要求一致。
     *
     * 默认 `null` = 不提示（不影响既有的 system 侧构造）。
     */
    val onNotice: ((String) -> Unit)? = null,
)
