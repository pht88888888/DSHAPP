package com.dsh.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import com.dsh.android.log.DshLog as Log
import android.view.View
import com.dsh.android.DshApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * `gecko` flavor：用**内置**的 GeckoView 承载界面，不依赖设备上的系统 WebView。
 *
 * ## 它解决了什么
 *
 * system 版把界面的渲染完全押在设备的系统 WebView 上，于是有两个绕不开的后果：
 *
 * 1. **旧内核白屏**：官方前端用了 Chromium 94 的 `class static block`，内核 83
 *    这类设备在**解析阶段**就失败，整份 chunk 一行都不执行 ⇒ 一片白屏。
 *    现在的对策是把 bundle 按 chrome74 降级重编（见 AGENTS.md ⑯），代价是
 *    `:is()` / `:where()` / `:has()` / `color-mix()` / `dvh` 这些**CSS** 在旧内核上
 *    是未知语法、整条规则被丢弃 ⇒ 「能打开但界面乱」。
 * 2. **用户没得选**：内核版本由设备/ROM 决定，App 无法升级它。
 *
 * 内置 GeckoView 后这两条都不存在：内核随 APK 分发，版本确定（见 [engineMajor]），
 * 前端跑的是**未降级**的官方 bundle，CSS 也不用降级。
 *
 * ## 代价（必须知情）
 *
 * `libxul.so` 单文件 136.8 MB，APK 从 138 MB 涨到 **222.9 MB**。这是 Gecko 引擎
 * 的固有体积，没有裁剪余地（能裁的话就不是「官方预编译产物」了）。
 *
 * ## 三个结构性差异（与 system 版对比）
 *
 * | | system | gecko |
 * |---|---|---|
 * | JS 执行 | `evaluateJavascript(js, cb)` | **无等价 API** ⇒ 走扩展，见 [evaluateJavascript] |
 * | 原生桥 | `addJavascriptInterface`（同步） | native messaging（异步）⇒ 见 [com.dsh.android.web.PageBridge] |
 * | 首帧 | `postVisualStateCallback` | `ContentDelegate.onFirstComposite` |
 * | 控制台 | `WebChromeClient.onConsoleMessage` | **无回调** ⇒ content script 里包 console |
 * | 下载 | `setDownloadListener` | **无回调** ⇒ content script 里包 `HTMLAnchorElement.click` |
 * | 文件选择 | `onShowFileChooser` | `PromptDelegate.onFilePrompt` |
 */
@SuppressLint("SetJavaScriptEnabled")
class GeckoWebHost(
    private val context: Context,
    private val callbacks: WebHostCallbacks,
) : WebHost {

    private val geckoView = GeckoView(context)
    private val session = GeckoSession()

    /**
     * 进程级 Gecko Runtime（Gecko 的主进程连接、WebExtension 控制器都挂在它上面）。
     *
     * ⚠️ **必须是进程级单例**：`GeckoRuntime.create()` 每次都新建一个，而 Activity
     *   重建（转屏、深浅色切换）会重跑本类的 init —— 不共用的话每转一次屏就泄漏
     *   一个 runtime 及其整个内容进程。`getDefault()` 在首次 create 之后就返回同一实例。
     */
    private val runtime: GeckoRuntime =
        GeckoRuntime.getDefault(context) ?: createRuntime(context)

    /** 最近一次发起加载的 URL（见 [WebHost.currentUrl] 的语义说明）。 */
    private var lastLoadedUrl: String? = null

    /** 是否已经提交过首帧（见 [awaitFirstFrame]）。 */
    private var painted = false

    /** 已等在首帧上的动作。 */
    private var pendingPaintAction: (() -> Unit)? = null

    /** 原生 → 扩展 的端口；扩展握手成功后才有值。 */
    private var port: WebExtension.Port? = null

    /** 已经发出、还没收到结果的 eval 请求：id → 回调。 */
    private val pendingEvals = mutableMapOf<Long, (String?) -> Unit>()

    /** eval 请求号，单调递增。 */
    private var nextEvalId = 1L

    /** 等待扩展握手的动作（端口没通之前发消息会丢，必须排队）。 */
    private val pendingUntilReady = ArrayDeque<() -> Unit>()

    override val view: View get() = geckoView

    override val currentUrl: String? get() = lastLoadedUrl

    /**
     * 内核主版本号。
     *
     * ⚠️ **与 system 版的语义不同**：那里读的是设备上装了什么内核（可能 83），
     *   这里读的是**打进 APK 的 GeckoView 版本**（编译期确定，实测 140）。
     *   所以 gecko 版永远不可能「内核过旧」—— 宿主里的版本拦截分支不会触发，
     *   这是刻意的（见 `MainActivity.observeEngine`）。
     */
    override val engineMajor: Int = GECKO_MAJOR

    override val engineLabel: String = "GeckoView $GECKO_VERSION（内置）"

    init {
        geckoView.setBackgroundColor(Color.parseColor("#0F1115"))

        session.settings.apply {
            setAllowJavascript(true)
            setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            setSuspendMediaWhenInactive(true)
        }

        // 本地引擎的 URL 是 http://127.0.0.1，不是 https。
        //
        // ⚠️ 这个开关在 **Runtime** 上而不是 session 上（`GeckoSessionSettings` 里
        //   根本没有它 —— 写了会 Unresolved reference）。之所以不只在
        //   [createRuntime] 的 builder 里设：`getDefault()` 命中的是**已存在**的
        //   runtime，它的 builder 早就跑完了，只有在这里改 pref 才生效。
        runtime.settings.setAllowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                // ⚠️ 与 system 版的 `onPageFinished` 对齐：只在**成功**时上报。
                //   失败时页面上什么都不会有，上报「DOM 就绪」会把启动层引向
                //   错误的方向（它之后会等 onUiReady，而那个永远不来）。
                //
                // ⚠️ 回调签名里没有 URL 参数（WebView 的 onPageFinished 有），
                //   用 [lastLoadedUrl] —— 本 App 只加载一个地址，且它就是
                //   `observeEngine` 里刚设过的那个。
                if (success) {
                    armTokenRedirectFollower()
                    callbacks.onPageFinished(lastLoadedUrl.orEmpty())
                }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            /**
             * 首帧提交 —— 与 WebView 的 `postVisualStateCallback` 同一个语义，
             * 也是「像素真的可以显示了」的唯一可靠信号。
             *
             * ⚠️ 启动层必须等它才收起：DOM 就绪到像素上屏之间有约 1.6 秒落差
             *   （system 版真机实测），提前收会让用户看到闪白。
             */
            override fun onFirstComposite(session: GeckoSession) {
                if (painted) return
                painted = true
                val action = pendingPaintAction
                pendingPaintAction = null
                action?.invoke()
            }

            /**
             * 内容进程**崩溃**了（与 WebView 的 `onRenderProcessGone(didCrash=true)` 对应）。
             *
             * ⚠️ 与 system 版那处不同：这里的默认实现**不会**杀宿主进程，它只是
             *   什么都不做 —— 于是 session 停在坏状态、界面画不出东西，而用户
             *   得不到任何提示。所以同样必须处理（见 `MainActivity.onRendererGone`）。
             */
            override fun onCrash(session: GeckoSession) {
                Log.w(DshApplication.TAG, "Gecko 内容进程已崩溃")
                callbacks.onRendererGone(true)
            }

            /**
             * 内容进程被系统**回收**（低内存时的正常资源管理，不是错误）。
             *
             * 与 [onCrash] 的区别只在日志措辞：两者都要重载界面才会回来。
             */
            override fun onKill(session: GeckoSession) {
                Log.w(DshApplication.TAG, "Gecko 内容进程已被系统回收")
                callbacks.onRendererGone(false)
            }
        }

        /**
         * 页面请求麦克风 —— **必须覆写**。
         *
         * ⚠️ 默认实现是 `GeckoResult.deny()`：不弹框、不打日志。页面拿到
         *   `NotAllowedError`，前端把那个翻译成 `RecordingError("permission")`，
         *   界面永远显示「麦克风权限被拒绝」—— 即使 Android 权限早就授过了。
         *   与 system 版的 `onPermissionRequest` 是**同一个缺口**（见
         *   [MicrophoneChannel]）。
         *
         * ## 与 system 版的两处结构差异
         *
         * 1. **只有这一个回调**：GeckoView 把「设备级权限」（相机/麦克风，走
         *    [onMediaPermissionRequest]）与「站点级权限」（地理位置/通知，走
         *    `onContentPermissionRequest`）分开了，而 WebView 只有笼统的一个
         *    `onPermissionRequest`。所以这里只需覆写前者。
         * 2. **返回类型是 `Unit`**（不是 `GeckoResult`）：媒体权限靠
         *    `MediaCallback.grant()` / `reject()` 异步回应，是"回调式"而非"返回式"。
         *
         * ## 只放行麦克风
         *
         * `audio` 里出现 `SOURCE_MICROPHONE` 才放行；摄像头（`video`）一律
         * `reject()` —— 本 App 用不到，收紧比放行安全。用 `reject()` 而不是
         * 什么都不做：不回就是「页面永远停在等待授权」，录音按钮点了毫无反应。
         */
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                val wantsMic = audio?.any {
                    it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE
                } == true
                if (!wantsMic) {
                    Log.i(DshApplication.TAG, "页面请求媒体权限（非麦克风），拒绝: $uri")
                    callback.reject()
                    return
                }
                Log.i(DshApplication.TAG, "页面请求麦克风，转交宿主申请 Android 权限")
                callbacks.microphone.request { granted ->
                    // ⚠️ 必须在主线程回应（Gecko 的 delegate 回调契约要求）。
                    geckoView.post {
                        if (granted) {
                            Log.i(DshApplication.TAG, "麦克风授权：允许")
                            // ⚠️ 用 `grant(audio, null)` 这个重载（单个 MediaSource
                            //   版本）：传 `null` 表示"不授权任何视频源"。传
                            //   `MediaSource[]` 数组的那个重载在这里更啰嗦，
                            //   而且必须自己保证数组非空。
                            val mic = audio.first {
                                it.source ==
                                    GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE
                            }
                            callback.grant(mic, null)
                        } else {
                            Log.w(DshApplication.TAG, "麦克风授权：用户拒绝")
                            callback.reject()
                        }
                    }
                }
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            /**
             * 外链交给系统浏览器。
             *
             * ⚠️ 与 system 版 `shouldOverrideUrlLoading` 的策略一致：只有本地引擎
             *   的 URL 留在内核里。
             *
             * ⚠️ 返回类型是 `GeckoResult<AllowOrDeny>` —— **不是** LoadRequest。
             *   `null` 表示「不接管，按默认放行」；拦下来要显式回
             *   `AllowOrDeny.DENY`（回 ALLOW 与回 null 等效）。
             *   注意 `LoadRequest` 上没有 `dispose()`，也不该在回调里改它。
             */
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                val url = request.uri
                if (url.startsWith(LOCAL_ENGINE_PREFIX) || url.startsWith("about:")) return null
                // ⚠️ `blob:` / `data:` 不能当外链交给系统浏览器 —— 它们只在**创建它的
                //   那个页面上下文**里有效，系统里没有任何应用能处理这两个协议，
                //   `Intent.ACTION_VIEW` 必然 ActivityNotFoundException 且**静默失败**。
                //   真机证据见 SystemWebHost 同处的注释（同一会话内连续 29 次）。
                //   回 null = 不接管，按内核默认行为走。
                if (url.startsWith("blob:") || url.startsWith("data:")) return null
                callbacks.onExternalUrl(url)
                // 已交给系统浏览器，内核这边必须拒绝这次导航，
                // 否则页面会被替换成外链（然后就再也回不来了）。
                return GeckoResult.deny()
            }
        }

        /**
         * 文件选择。
         *
         * ⚠️ 与 system 版 `onShowFileChooser` 是**同一个缺口**：不实现它，页面里
         *   点「+」→「文件」静默无反应。
         * ⚠️ 必须回 `null` 表示取消，**不能**回空数组 —— 空数组会被页面解读成
         *   「选了 0 个文件」并触发一次空提交。
         *
         * ## ⚠️⚠️ 必须返回一个**未完成**的 `GeckoResult`（这里踩过一次）
         *
         * `PromptDelegate` 的契约是「返回 `GeckoResult<PromptResponse>`，异步里
         * `complete` 它」。**返回 `null` 不是"稍后再说"，而是"本 delegate 不处理
         * 这个 prompt"** —— GeckoView 随即按默认行为把 prompt 收尾（对文件选择就是
         * 取消），此后 `prompt.confirm(...)` 生成的 `PromptResponse` **再也送不回
         * Gecko**（`isComplete()` 已是 true，且没有任何通道）。
         *
         * 症状极具迷惑性：**系统选择器照常弹出**（那是我们自己 `launch` 的
         * Intent，与 prompt 无关），用户也能正常选中文件，但选中后文件永远进不了
         * 页面 —— 没有任何报错，日志也干净。第一版就是这么写的。
         *
         * ## ⚠️⚠️ `confirm()` **只吃 `file://`**，`content://` 必须自己落盘
         *
         * 这是第二处坑，比第一处隐蔽得多。`FilePrompt.confirm(Context, Uri[])` 内部
         * 对每个 URI 调 `getFile()`，而它**只认 `file://`**；拿到 `content://` 就
         * 返回 null，只在 Gecko 侧的日志里留一行 WARN：
         *
         * ```
         * W GeckoViewFilePickerDelegate: Error resolving files from file picker:
         *   [Exception... "File error: Unrecognized path"
         *    nsresult: "0x80520001 (NS_ERROR_FILE_UNRECOGNIZED_PATH)"
         *    location: "JS frame :: resource://gre/modules/FilePickerDelegate.sys.mjs"
         * ```
         *
         * 而 `ACTION_GET_CONTENT` 回的**恰恰就是** `content://`
         * （`content://com.android.providers.media.documents/document/image%3A888`）。
         *
         * ⚠️ 这是 GeckoView 的**已知未实现项**，不是我们用错了：WebView/Chromium 会
         *   走 `ContentResolver.openFileDescriptor` 读 content URI，GeckoView 的
         *   file picker 这条路**没有**做（Mozilla bug 1591640 / 1875732）。它对
         *   `content://` 的支持只加在**导航与 PDF** 上（content 协议处理器），
         *   文件选择器这条路径没被覆盖。
         *
         * 所以这里自己把内容复制进 `cacheDir` 再用 `file://` 交回去。`cacheDir`
         * 是 app 私有目录，Gecko 内容进程读得到（同 uid）。
         */
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            /**
             * `window.open` 请求新窗口。
             *
             * ⚠️ **GeckoView 走的是 `PromptDelegate.onPopupPrompt`，不是
             *   `ContentDelegate.onCreateWindow`**（后者在 GeckoView 里根本不存在，
             *   写上去编译期就报 `overrides nothing`）。而且契约与 WebView 差别很大：
             *   它的 `PopupPrompt.confirm(...)` **只接受 `AllowOrDeny`**，
             *   `GeckoSession.TargetWindow` 是个**注解**（不是 WebView 那种能把新
             *   WebView 塞进去的 `WebViewTransport`）—— 也就是说宿主**无法**在这里
             *   提供自己的会话，放行后由 GeckoView 自己建，而它建出来的窗口没有
             *   任何容器承载（本 App 只有一个 `GeckoView`）。
             *
             * 因此这里的策略是：**把授权页交给系统浏览器**，然后 `deny`。
             *
             * - `deny` 让 GeckoView 别建那个没人承载的窗口（否则是白窗口/泄漏）；
             * - `prompt.targetUri` 里有目标地址，交给系统浏览器打开，用户照样能完成
             *   登录 —— 这是 GeckoView 上唯一能真正跑通的路。
             *
             * ⚠️ 不实现本方法的症状与 system 版一致：默认 deny 且**不做任何跳转**，
             *   于是页面 `window.open` 拿到 null，官方登录流程里
             *   `popup.location.replace(...)` 作用在 null 上静默失败 ——
             *   用户看到「点登录什么都没发生」。
             */
            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val uri = prompt.targetUri
                // 本地引擎自己的弹窗按内核语义处理（可能只是 SPA 内部的空 shell）。
                if (uri.isNullOrEmpty()) {
                    Log.i(DshApplication.TAG, "window.open 无目标地址，交内核处理")
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
                }
                if (uri.startsWith(LOCAL_ENGINE_PREFIX)) {
                    Log.i(DshApplication.TAG, "window.open 指向本地引擎，交内核处理: ${uri.take(80)}")
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
                }
                Log.i(DshApplication.TAG, "window.open 转系统浏览器（Gecko）: ${uri.take(120)}")
                callbacks.onExternalUrl(uri)
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                Log.i(DshApplication.TAG, "文件选择请求（type=${prompt.type}，多选=${prompt.type == FILE_PROMPT_MULTIPLE}）")
                // ⚠️ 挂起返回：prompt 一直等这个 result，直到用户选完或取消。
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                callbacks.fileChooser.launch(prompt.type == FILE_PROMPT_MULTIPLE) { uris ->
                    try {
                        if (uris == null || uris.isEmpty()) {
                            Log.i(DshApplication.TAG, "文件选择已取消")
                            result.complete(prompt.dismiss())
                        } else {
                            // ⚠️ 复制是 IO，必须离开主线程：`fileChooser.launch` 的
                            //   回调在主线程（Activity result），复制大图会 ANR。
                            // ⚠️ 写成 `Thread({ ... }, "name")` 而不是 `Thread { ... }`：
                            //   Kotlin 的尾随 lambda 语法会把 `Thread { }` 当成整个调用，
                            //   后面的 `, "name"` 就成了语法错误（编译实测）。
                            Thread({
                                val local = uris.mapNotNull { toLocalFile(it) }
                                geckoView.post {
                                    try {
                                        if (local.isEmpty()) {
                                            // 一个都没复制成功 ⇒ 当取消处理。回空数组会被
                                            // 页面解读成「选了 0 个文件」并触发空提交。
                                            Log.w(DshApplication.TAG, "文件选择：全部复制失败，按取消处理")
                                            result.complete(prompt.dismiss())
                                            return@post
                                        }
                                        Log.i(DshApplication.TAG, "文件选择完成：${local.size} 个（已落盘到本地）")
                                        result.complete(prompt.confirm(context, local.toTypedArray()))
                                    } catch (t: Throwable) {
                                        Log.e(DshApplication.TAG, "文件选择结果回传失败", t)
                                        runCatching { result.completeExceptionally(t) }
                                    }
                                }
                            }, "dsh-file-picker-copy").start()
                        }
                    } catch (t: Throwable) {
                        Log.e(DshApplication.TAG, "文件选择结果回传失败", t)
                        runCatching { result.completeExceptionally(t) }
                    }
                }
                return result
            }
        }

        session.open(runtime)
        geckoView.setSession(session)

        installBridgeExtension()
    }

    /**
     * 把选择器回传的 URI 变成 Gecko 认得的 `file://` 文件。
     *
     * ⚠️ **必须这么做**：`FilePrompt.confirm()` 内部的 `getFile()` 只支持 `file://`，
     *   而 `ACTION_GET_CONTENT` 回的是 `content://`。详见 [onFilePrompt] 的说明。
     *
     * 返回值约定：`null` = 这个文件没法用（调用方据此决定是否整体按取消处理）。
     *
     * ⚠️ 复制到 `cacheDir` 而不是 `filesDir`：这些是**一次性的上传中转文件**，
     *   放缓存目录才能让系统在空间不足时回收，也不必自己管清理。文件名保留原扩展名
     *   （页面与引擎都会按扩展名判类型），前缀加随机串避免同名覆盖。
     *
     * ⚠️ 不能把 `content://` 直接拼成 `file://` —— 那不是"转换"，只是换个 scheme，
     *   Gecko 依然打不开（DocumentsProvider 背后根本没有对应的路径）。
     */
    private fun toLocalFile(uri: Uri): Uri? {
        return try {
            // file:// 原样放行（部分选择器会回 file URI）。
            if (uri.scheme == "file") return uri

            val name = displayName(uri) ?: "picked"
            val dir = File(context.cacheDir, FILE_PICKER_DIR).apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}-${name.hashCode().toString(16)}-$name")

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.w(DshApplication.TAG, "打不开所选文件的输入流: $uri")
                return null
            }
            Log.i(DshApplication.TAG, "所选文件已落盘: ${target.name}（${target.length()} 字节，源 $uri）")
            Uri.fromFile(target)
        } catch (t: Throwable) {
            // 单个文件失败不该把整次选择作废（多选时其余仍可用）。
            Log.w(DshApplication.TAG, "复制所选文件失败: $uri", t)
            null
        }
    }

    /** 取 URI 的显示名（用于给临时文件保留扩展名）。 */
    private fun displayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                // ⚠️ 显示名可能含路径分隔符（恶意或异常的 provider），只取最后一段。
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 正在接收的下载：页面世界 fetch 完，经桥分块传回来。
     *
     * ⚠️ 为什么不在原生侧发起请求：见 content.js 的说明（宿主拿不到 cookie ⇒ 401；
     *   内核导航 ⇒ 真机 403 且错误页覆盖界面）。页面世界 fetch 与 GUI 平时调
     *   「/api/」完全同源，是唯一必然通过准入检查的通路。
     *
     * ⚠️ 分块是必须的：native messaging 只能传 JSON，二进制走 base64。
     */
    private class IncomingDownload(val url: String, val filename: String) : Closeable {
        val sink: OutputStream = ByteArrayOutputStream()
        override fun close() {
            (sink as? ByteArrayOutputStream)?.close()
        }
    }

    private var incomingDownload: IncomingDownload? = null

    /**
     * 收一次分块，返回是否全部收完。
     *
     * ⚠️ 落盘交给宿主（`callbacks.onDownloadStream`）：`DownloadStream.body` 是
     *   `InputStream`，宿主在 IO 线程读它并写文件，内核这边不碰文件系统。
     */
    private fun appendDownloadChunk(base64: String) {
        val dl = incomingDownload
        if (dl == null) {
            Log.w(DshApplication.TAG, "收到下载分块但没有进行中的下载，已丢弃")
            return
        }
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "下载分块 base64 解码失败", t)
            return
        }
        dl.sink.write(bytes)
    }

    /** 从 URL 末段猜文件名（页面没给 `download` 属性时的兜底）。 */
    private fun urlFilename(url: String): String {
        // ⚠️ URL 末段可能带 query（`?sessionId=...&includeDescendants=true`），
        //   直接 substringAfterLast('/') 会把 query 一起吃进文件名。
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "dsh-download"
    }

    /** 下载收尾：把攒好的字节交给宿主落盘。 */
    private fun finishDownload() {
        val dl = incomingDownload ?: return
        incomingDownload = null
        val body = ByteArrayInputStream((dl.sink as ByteArrayOutputStream).toByteArray())
        callbacks.onDownloadStream(
            DownloadStream(url = dl.url, filename = dl.filename, body = body)
        )
    }

    /**
     * 装桥扩展。
     *
     * ⚠️ 必须用 `session.webExtensionController.setMessageDelegate(...)`
     *   而不是 `extension.setMessageDelegate(...)`：**content script 发出的消息
     *   只认前者**（后者只接 background 的消息）。这是 mozilla/geckoview#220
     *   里官方给的绕法，写错了表现为「content script 的消息永远收不到」。
     */
    private fun installBridgeExtension() {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { extension ->
                    // ⚠️ `ensureBuiltIn` 的成功回调参数在 Java 侧是 `WebExtension`，
                    //   但 Kotlin 按平台类型推断成可空 —— 真到这一步它一定非空，
                    //   拿不到就只能算失败，别硬传 null 进去（会 NPE 在 Gecko 内部）。
                    if (extension == null) {
                        Log.e(DshApplication.TAG, "GeckoView 桥扩展返回空引用")
                        return@accept
                    }
                    session.webExtensionController.setMessageDelegate(extension, messageDelegate, NATIVE_APP)
                    Log.i(DshApplication.TAG, "GeckoView 桥扩展已安装: ${extension.metaData?.version}")
                    // ⚠️ 这里**不能**把 pendingUntilReady 放出去：委托挂上只代表
                    //   「原生能收消息了」，端口要等 content script 页面加载后自己
                    //   connectNative 才会来（见 onConnect）。这里只记录日志。
                    Log.i(DshApplication.TAG, "GeckoView 桥等待 content script 连接…")
                },
                { error ->
                    // 扩展装不上 ⇒ 桥不存在 ⇒ 页面侧全部走「无桥」降级分支。
                    // 这是**功能性**失败而不是致命失败：界面能开，但设置页里的
                    // 气泡/root/无线调试三块不可用。必须明确记日志。
                    Log.e(DshApplication.TAG, "GeckoView 桥扩展安装失败", error)
                },
            )
    }

    /** 端口就绪后，把排队等端口的动作放出去。 */
    private fun flushPendingUntilReady() {
        val queued = pendingUntilReady.toList()
        pendingUntilReady.clear()
        queued.forEach { it() }
    }

    /**
     * 原生 ↔ 扩展 的消息通道。
     *
     * ⚠️ **content script 直连原生**（不是经 background 中转）：GeckoView 没有实现
     *   `browser.tabs`，background 无法把消息发回 content script，而「原生 → 页面」
     *   的两个方向（推快照、[evaluateJavascript]）恰恰都要走那条路。所以
     *   content script 自己 `connectNative`，原生侧用
     *   `session.webExtensionController.setMessageDelegate` 接 —— **不能**用
     *   `extension.setMessageDelegate`，后者只接 background 的消息
     *   （mozilla/geckoview#220）。
     *
     * 消息类型（与 `assets/dsh-bridge/content.js` 一一对应）：
     * - `hello`       content script 报到（收到它才把首份快照推过去）
     * - `invoke`      页面动作 → 原生（[PageBridge] 的 16 个方法）
     * - `evalResult`  页面执行完 JS 的结果
     */
    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(connectedPort: WebExtension.Port) {
            // ⚠️ 端口可能被**换掉**（页面刷新、扩展重载都会重连）：这里直接覆盖，
            //   旧端口的 onDisconnect 里再判断"是不是当前那个"。
            port = connectedPort
            connectedPort.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {
                    handleExtensionMessage(message)
                }

                override fun onDisconnect(p: WebExtension.Port) {
                    if (port === p) port = null
                    Log.w(DshApplication.TAG, "GeckoView 桥端口已断开，等待扩展重连")
                    // ⚠️ **不要在这里清 `pendingEvals`**（2026-10-15 核对后放弃的一个改动）。
                    //   断连期间 `postToExtension` 会把 eval 消息排进 `pendingUntilReady`，
                    //   重连时 `flushPendingUntilReady()` 重放它们，结果回来再按 id 兑现
                    //   —— 这是一条**完整且正确**的恢复路径，清掉 map 反而会
                    //   让重放回来的结果被丢弃（`pendingEvals.remove(id)` 拿到 null）。
                    //   全量实测 617 次建立 / 479 次断开，断连是常态、且确实会重连，
                    //   所以「断开后永不重连导致 map 泄漏」这个假设不成立。
                    //   Activity 销毁那条兜底在 [destroy] 里（`pendingEvals.clear()`）。
                }
            })
            Log.i(DshApplication.TAG, "GeckoView 桥端口已建立")
            // ⚠️ 立刻推一次快照：content script 是**先**装桥**再** connectNative 的，
            //   它 connect 之前页面侧读到的还是初始空值。这里推一份让页面尽快拿到真值。
            pushSnapshot()
            // 端口通了，把之前排队等端口的动作放出去（`evaluateJavascript` 在页面
            // 加载完成前被调用时会排在这里）。
            flushPendingUntilReady()
        }
    }

    /** 处理扩展发来的消息。 */
    private fun handleExtensionMessage(message: Any) {
        val json = message as? JSONObject ?: return
        when (json.optString("type")) {
            "hello" -> {
                // content script 报到：把快照推过去，页面随后的同步读取才有值。
                Log.i(DshApplication.TAG, "GeckoView 桥已握手（content script 报到）")
                pushSnapshot()
            }
            "invoke" -> {
                val call = json.optString("call")
                val args = json.optJSONArray("args") ?: JSONArray()
                dispatchInvoke(call, args)
            }
            "evalResult" -> {
                val id = json.optLong("id", -1L)
                val result = if (json.isNull("result")) null else json.optString("result", "null")
                pendingEvals.remove(id)?.invoke(result)
            }
            "log" -> {
                // ⚠️ content script 的 console **不进 logcat**（见 content.js 的 log()），
                //   所以它把诊断经端口回传，由原生代打。没有这条通道，「桥没装上」
                //   这类功能性失败在日志里是完全静默的。
                Log.i(DshApplication.TAG, json.optString("text"))
            }
        }
    }

    /**
     * 把页面动作派发给 [PageBridge]。
     *
     * ⚠️ 全部切到主线程执行：这些动作会碰 View、发通知、读写 SharedPreferences，
     *   而 `onPortMessage` 跑在 Gecko 的线程上。切线程也顺带避免了「JS 线程被
     *   阻塞」这类在 system 版里必须小心处理的问题。
     */
    private fun dispatchInvoke(call: String, args: JSONArray) {
        val b = callbacks.bridge
        geckoView.post {
            try {
                when (call) {
                    "onStatus" -> b.onStatus(args.optString(0))
                    "onImeWillShow" -> b.onImeWillShow()
                    "startWirelessPairing" -> b.startWirelessPairing()
                    "cancelWirelessPairing" -> b.cancelWirelessPairing()
                    "onUiReady" -> b.onUiReady()
                    "reportWebError" -> b.reportWebError(args.optString(0))
                    "switchToTcpMode" -> b.switchToTcpMode()
                    "bubbleSetEnabled" -> b.bubbleSetEnabled(args.optBoolean(0))
                    "bubbleRequestOverlay" -> b.bubbleRequestOverlay()
                    // AI 浏览器（官方右侧边栏的「AI 浏览器」面板）。
                    // ⚠️ 与 system 版逐条对齐：漏了就「点了没反应」。
                    "openAiBrowser" -> b.openAiBrowser()
                    // AI 浏览器嵌入定位：前端量出面板矩形后高频上报（ResizeObserver）。
                    "aiBrowserLayout" -> b.aiBrowserLayout(args.optString(0, ""))
                    "aiAnnotateResult" -> b.aiAnnotateResult()
                    "aiAnnotateReport" -> b.aiAnnotateReport(args.optString(0, ""))
                    "aiBrowserDetach" -> b.aiBrowserDetach()
                    "aiBrowserState" -> b.aiBrowserState()
                    // 甩动手感三开关（见「悬浮气泡」设置页）。
                    "bubbleSetThrow" -> b.bubbleSetThrow(args.optBoolean(0))
                    "bubbleSetShake" -> b.bubbleSetShake(args.optBoolean(0))
                    "bubbleSetImeFling" -> b.bubbleSetImeFling(args.optBoolean(0))
                    // 键盘避让（见「悬浮气泡 → 键盘避让」卡片）。
                    // ⚠️ PR #3 漏了这份白名单，症状是「开了无障碍，开关还是打不开」：
                    //   页面侧 `window.DSHAndroid.keyboardAvoidState` 是 undefined，
                    //   前端走进 noBridge 分支把开关置灰，原生日志却一切正常。
                    //   `keyboardAvoidState` 走快照同步读（见 pushSnapshot），
                    //   这里只处理写入与跳转。
                    "keyboardAvoidSetEnabled" -> b.keyboardAvoidSetEnabled(args.optBoolean(0))
                    "keyboardAvoidRequestPermission" -> b.keyboardAvoidRequestPermission()
                    "keyboardAvoidState" -> pushSnapshot()
                    // 余额快照：页面侧拉取、原生渲染（见 PageBridge.bubbleReportBalance）。
                    // ⚠️ 参数是 JSON 字符串，用 optString 而不是 optBoolean。
                    "bubbleReportBalance" -> b.bubbleReportBalance(args.optString(0, ""))
                    // 徽标刷新配置 + 点球计数（见 PageBridge.bubbleBadgeConfig /
                    // bubbleTapRefreshSeq）。⚠️ 与 system 版逐条对齐。
                    "bubbleBadgeConfig" -> b.bubbleBadgeConfig()
                    "bubbleTapRefreshSeq" -> b.bubbleTapRefreshSeq()
                    // 剪贴板（登录页「复制登录链接」）。⚠️ 与 system 版逐条对齐。
                    "writeClipboard" -> b.writeClipboard(args.optString(0, ""))
                    "pageDiag" -> b.pageDiag(args.optString(0, ""))
                    "rootRecheck" -> b.rootRecheck()
                    // Shizuku（uid 2000 的第二条来源，见 ShizukuChannel）。
                    // ⚠️ 与 system 版白名单必须逐条对齐，理由见下面那段的说明。
                    "shizukuRequestPermission" -> b.shizukuRequestPermission()
                    "shizukuOpenManager" -> b.shizukuOpenManager()
                    "shizukuOpenDownload" -> b.shizukuOpenDownload()
                    // 日志与诊断（见 ⑫.6）。⚠️ 与 system 版的 `SystemPageBridge`
                    // 白名单**必须逐条对齐** —— 漏一条的症状是设置页那一块显示成
                    // 「没有原生桥」，不报错、不崩溃，只是静默失效。
                    "openLogs" -> b.openLogs()
                    // 原生上传兜底通道（见 NativeUploader 的类注释）。
                    // ⚠️ 与 system 版 `SystemPageBridge` 白名单**必须逐条对齐**。
                    //   这里比 system 版多一层坑：native messaging 只能异步，
                    //   所以 `begin` / `write` 的返回值**送不回去**（content.js
                    //   那边乐观返回 true）。真正的成败一律走
                    //   `__dshNativeUploadDone` 回调 —— polyfill 侧还挂了超时兜底。
                    "uploadNativeBegin" -> b.uploadNativeBegin(
                        args.optString(0), args.optString(1), args.optString(2),
                    )
                    "uploadNativeWrite" -> b.uploadNativeWrite(
                        args.optString(0), args.optString(1),
                    )
                    "uploadNativeFinish" -> b.uploadNativeFinish(args.optString(0))
                    "uploadNativeAbort" -> b.uploadNativeAbort(args.optString(0))
                    // `logSummary` 页面侧走快照同步读，这里只处理「请重推一份」。
                    // ⚠️ 必须重推：用户从日志界面（另一个 Activity）清空/上传后
                    //   回到设置页，缓存里还是旧数字，不重推就永远不刷新。
                    "logsRefresh" -> pushSnapshot()
                    // 会话转录：与 onStatus 同一条路，只是体量大得多（见 ChatTranscript）。
                    "onTranscript" -> b.onTranscript(args.optString(0))
                    // 插件下发悬浮球外观。**改完必须重推快照** —— 页面侧读的是
                    // 缓存（native messaging 只能异步），不重推的话插件调完
                    // bubbleApply 再读 bubbleAppearance 会拿到旧值。
                    "bubbleApply" -> {
                        b.bubbleApply(args.optString(0))
                        pushSnapshot()
                    }
                    // 页面侧不会主动调这两个（它们走快照推送），但留个兜底：
                    // 收到就当「页面想要最新值」，推一次快照。
                    "bubbleState", "bubbleAppearance", "rootState", "shizukuState" -> pushSnapshot()
                    // console / download 是 content script 自己发起的，见下。
                    "console" -> callbacks.onConsole(args.optString(0))
                    // 下载：页面世界 fetch → base64 分块 → 这里攒起来 → 宿主落盘。
                    //
                    // ⚠️ **不要**在这条路上用 GeckoSession.Loader 起导航：真机实测
                    //   引擎的准入检查会回 403，而**任何非 attachment 的响应都会被
                    //   内核当主文档渲染**，一次失败就把整个 GUI 顶掉。页面世界
                    //   fetch 与 GUI 平时调 API 同源同头，是唯一稳的通路。
                    //
                    // ⚠️ 每条分块都带 url 与文件名，**不依赖单独的「开始」消息**：
                    //   那条开场消息被内核的 messaging 校验拒过一次（logcat 只有
                    //   Invalid event data property args），结果是分块到了却没有
                    //   进行中的下载、整批被丢弃。元信息随第一条分块到达，
                    //   这条链路就没有「必须先收到某条消息」的隐含依赖。
                    "downloadChunk" -> {
                        if (incomingDownload == null) {
                            val url = args.optString(1)
                            val name = args.optString(2).takeIf { it.isNotBlank() }
                                ?: urlFilename(url)
                            incomingDownload = IncomingDownload(url, name)
                            Log.i(DshApplication.TAG, "收到下载开始: $name ($url)")
                        }
                        appendDownloadChunk(args.optString(0))
                    }
                    "downloadEnd" -> finishDownload()
                    "downloadError" -> {
                        // 页面侧拿不到数据（401/403/网络）。必须明确记日志：
                        // 这条路径失败了页面什么都不会显示，不记就完全无痕。
                        Log.w(DshApplication.TAG, "下载失败: ${args.optString(0)} —— ${args.optString(1)}")
                        incomingDownload?.close()
                        incomingDownload = null
                    }
                    else -> Log.w(DshApplication.TAG, "未知的桥调用: $call")
                }
            } catch (t: Throwable) {
                // ⚠️ 桥的异常绝不能冒泡到 Gecko 的消息循环：那会让端口断掉，
                //   页面侧此后全部走「无桥」分支。
                Log.w(DshApplication.TAG, "处理桥调用 $call 失败", t)
            }
        }
    }

    /**
     * 把只读快照推给页面。
     *
     * 这是页面侧**同步**读取（`bridge.rootState()`）的基石：native messaging 只能
     * 异步，所以值必须先缓存到页面侧，读取才能是同步的。见 content.js 的说明。
     *
     * 推送时机：端口建立时、扩展每次重新握手时、以及任何可能改变这三个值的
     * 动作之后（由 [refreshSnapshot] 触发）。
     */
    private fun pushSnapshot() {
        val msg = JSONObject()
            .put("type", "snapshot")
            .put("root", callbacks.bridge.rootState())
            .put("bubble", callbacks.bridge.bubbleState())
            // 悬浮球外观也要进快照：页面侧 `bubbleAppearance()` 是**同步**读取，
            // 而 native messaging 只能异步 —— 与 root/bubble 同一套缓存语义。
            .put("appearance", callbacks.bridge.bubbleAppearance())
            .put("pairingEnabled", callbacks.bridge.pairingNotificationsEnabled())
            // 日志摘要（JSON 文本）。页面侧 `logSummary()` 是**同步**读取，所以
            // 只能经快照缓存 —— 与 root/bubble 完全同一套语义。
            .put("logSummary", callbacks.bridge.logSummary())
            // Shizuku 状态（JSON 文本）。同样是页面侧**同步**读取
            // （`bridge.shizukuState()`），只能经快照缓存。
            // ⚠️ 它必须进快照还有一个更硬的理由：Shizuku 的授权是**异步**回来的
            //   （用户在 Shizuku 自己的确认界面上点「允许」），页面轮询时读的
            //   就是这个缓存 —— 不推的话用户授权完，设置页永远停在「未授权」。
            .put("shizuku", callbacks.bridge.shizukuState())
            // 键盘避让状态（JSON 文本）。同样是页面侧**同步**读取
            // （`bridge.keyboardAvoidState()`），只能经快照缓存。
            // ⚠️ 必须进快照：无障碍授权是在**另一个 Activity** 里给的，用户授权完
            //   回到设置页，页面拿到的若还是旧缓存，就会一直显示「还差一步：去开权限」。
            .put("keyboardAvoid", callbacks.bridge.keyboardAvoidState())
        postToExtension(msg)
    }

    /** 往扩展发一条消息；端口没通时排队等握手（快照丢了页面就会读到空值）。 */
    private fun postToExtension(msg: JSONObject) {
        val p = port
        if (p == null) {
            pendingUntilReady.addLast { postToExtension(msg) }
            return
        }
        try {
            p.postMessage(msg)
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "向桥扩展发送消息失败", t)
        }
    }

    override fun loadUrl(url: String) {
        lastLoadedUrl = url
        session.loadUri(url)
    }

    /**
     * 让页面自己完成「`?token=` → cookie」这一步（GeckoView 不跟随引擎那个 303）。
     *
     * ## 为什么需要它（真机实测）
     *
     * 引擎打印的 URL 带一次性 token（`http://127.0.0.1:<port>/?token=…`），它对主文档
     * 回 **303 See Other + `Location: ./` + `Set-Cookie`**，靠这次跳转把 token 换成
     * cookie，之后才是真正的首页请求。
     *
     * 系统 WebView 和系统浏览器都会跟随这个 303（**同一个 URL 在华为浏览器里能正常
     * 渲染 GUI**），但 **GeckoView 不会**：logcat 里 `onLoadRequest` 只出现一次
     * （带 token 那一次），紧接着 `onPageStop success=true` —— 页面就**停在 303 的
     * 空响应上**。页面世界实测：`documentURI` 仍是带 token 的地址、`#root` 不存在、
     * `document.scripts` 为空 ⇒ 永久白屏，且没有任何 JS 错误可查。
     *
     * ## 修法
     *
     * 不改引擎：在页面世界 `fetch` 一次带 token 的地址 —— fetch 会跟随 303 并把
     * `Set-Cookie` 落进 cookie jar，随后 `location.replace('./')` 就能拿到真正的首页。
     *
     * ⚠️ 必须在**页面世界**跑（走桥的 eval）：宿主既读不到 cookie，也发不出同源请求。
     * ⚠️ 桥握手可能晚于 `onPageStop`（实测 0.35s 就 stop 了），所以先等再注入。
     * ⚠️ 只对「地址里还带 token」的页面动手；正常渲染后（`#root` 已出现）脚本自己退出。
     */
    private fun armTokenRedirectFollower() {
        if (!lastLoadedUrl.orEmpty().contains("token=")) return
        Handler(Looper.getMainLooper()).postDelayed({
            evaluateJavascript(TOKEN_FOLLOW_JS) { r ->
                Log.i(DshApplication.TAG, "[nav] token 跟随脚本已注入: $r")
            }
        }, TOKEN_FOLLOW_DELAY_MS)
    }

    /**
     * 重新加载当前 URL（内容进程崩溃 / 被回收后的恢复路径）。
     *
     * ⚠️ `painted` 必须复位：新内容进程的第一帧还没提交，沿用旧的 `true`
     *   会让启动层立刻收尾、露出白屏。
     * ⚠️ 与 system 版同构：没有 URL 就没法恢复，静默返回。
     */
    override fun reload() {
        val url = lastLoadedUrl
        if (url.isNullOrEmpty()) {
            Log.w(DshApplication.TAG, "内容进程已死但没有可用的 URL，无法重载")
            return
        }
        Log.i(DshApplication.TAG, "重载界面: $url")
        painted = false
        pendingPaintAction = null
        session.loadUri(url)
    }

    /**
     * 在页面里执行 JS。
     *
     * ## 为什么这么绕（这是与 system 版最大的结构性差异）
     *
     * GeckoView **没有** `evaluateJavascript`。官方文档明确说：
     * 「The GeckoView API, however, doesn't offer such an API」，要求改用
     * WebExtension。所以路径是：
     *
     * ```
     *   原生 ──port.postMessage({type:'eval'})──▶ background.js
     *        ──tabs.sendMessage──▶ content.js ──window.eval──▶ 结果原路返回
     * ```
     *
     * ⚠️ **必须用 `window.eval` 而不是 `eval`**：content script 在隔离世界里，
     *   普通 `eval` 拿不到页面脚本定义的东西（`window.__DSH_MOBILE_BACK__` 就
     *   是页面侧的插件挂的），会永远返回「桥不存在」。
     *
     * ⚠️ 结果编码与 WebView 对齐为 **JSON 文本**，宿主侧解析逻辑两边共用。
     *
     * ⚠️ 回调**不保证被调用**（端口断了、页面已销毁）。契约与 [WebHost.evaluateJavascript]
     *   一致，调用方必须有兜底分支。
     */
    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val id = nextEvalId++
        if (callback != null) pendingEvals[id] = callback
        postToExtension(
            JSONObject()
                .put("type", "eval")
                .put("id", id)
                .put("script", script)
        )
    }

    override fun awaitFirstFrame(action: () -> Unit) {
        if (painted) { action(); return }
        pendingPaintAction = action
    }

    override fun resetFirstFrame() {
        // GeckoView 走 `ContentDelegate.onFirstComposite`，它是**每次合成**都会回调的，
        // 不存在 WebView 那种「注册的回调被旧视觉状态立刻满足」的问题 ——
        // 清掉标志即可让下一次 awaitFirstFrame 重新等新的一帧。
        painted = false
        pendingPaintAction = null
    }

    override fun destroy() {
        pendingPaintAction = null
        pendingEvals.clear()
        pendingUntilReady.clear()
        port = null
        runCatching { session.close() }
        geckoView.releaseSession()
    }

    private companion object {
        /** 本地引擎的 URL 前缀；其余一律当外链。 */
        const val LOCAL_ENGINE_PREFIX = "http://127.0.0.1"

        /** 注入 token 跟随脚本前的等待（ms）：等桥扩展握手完成。 */
        const val TOKEN_FOLLOW_DELAY_MS = 2500L

        /**
         * 页面世界的 token 跟随脚本，见 [armTokenRedirectFollower]。
         *
         * ⚠️ 整段包 try/catch：这段跑的时机很早（页面刚 stop），任何异常都不该让
         *   `evaluateJavascript` 拿到 `null` 而无从判断。
         */
        val TOKEN_FOLLOW_JS = """
            (function () {
              try {
                if (location.search.indexOf('token=') < 0) return 'skip-no-token';
                if (document.getElementById('root')) return 'skip-rendered';
                var tries = 0;
                var timer = setInterval(function () {
                  tries += 1;
                  if (document.getElementById('root')) { clearInterval(timer); return; }
                  if (tries > 24) { clearInterval(timer); return; }
                  if (tries % 2 !== 0) { return; }
                  fetch(location.href, { credentials: 'same-origin' })
                    .then(function () { location.replace('./'); })
                    .catch(function () {});
                }, 800);
                return 'armed';
              } catch (e) { return 'err:' + (e && e.message); }
            })()
        """.trimIndent()

        /** 桥扩展在 APK assets 里的位置（`resource://android/` 指向 APK 根）。 */
        const val EXTENSION_LOCATION = "resource://android/assets/dsh-bridge/"

        /**
         * 文件选择的「多选」类型。
         *
         * ⚠️ `FilePrompt.Type` 是个**纯常量持有类**（构造函数 protected），所以
         *   从 Kotlin 写 `FilePrompt.Type.MULTIPLE` 会因「无法实例化」而报
         *   `None of the following candidates is applicable` —— 直接把值抄在这里。
         *   与 javap 出的 `MULTIPLE = 2` / `SINGLE = 1` / `FOLDER = 3` 对齐。
         */
        const val FILE_PROMPT_MULTIPLE = 2

        /** 扩展 id，必须与 `assets/dsh-bridge/manifest.json` 里的一致。 */
        const val EXTENSION_ID = "dsh-bridge@dsh.android"

        /** 原生应用标识，必须与 background.js 里 `connectNative` 的参数一致。 */
        const val NATIVE_APP = "browser"

        /**
         * 打进 APK 的 GeckoView 版本。
         *
         * ⚠️ 必须与 `app/build.gradle.kts` 的 `geckoImplementation` 依赖版本一致。
         *   写在这里是为了让 [engineMajor] / [engineLabel] 不依赖运行时反射
         *   （GeckoView 没有公开的「读自己版本」API）。改依赖版本时同步改这两处。
         */
        const val GECKO_VERSION = "140.0.20250707120347"
        const val GECKO_MAJOR = 140

        /**
         * 文件选择的临时中转目录（`cacheDir` 下）。
         *
         * ⚠️ 必须落在 app 私有目录：Gecko 的内容进程与宿主同 uid，读得到；放外部
         *   存储则要额外处理权限与作用域存储，没必要。
         */
        const val FILE_PICKER_DIR = "file-picker"
    }
}

/**
 * GeckoView 的 cookie 库文件名（连同 WAL/SHM 一起清，见 [purgeAuthCookies]）。
 *
 * ⚠️ 放在**顶层**而不是 `GeckoWebHost` 的 companion 里：`purgeAuthCookies` 与
 *    `createRuntime` 都是文件级函数，companion 的成员它们看不到（编译报
 *    `Unresolved reference`）。
 */
private val AUTH_COOKIE_FILES = listOf("cookies.sqlite", "cookies.sqlite-wal", "cookies.sqlite-shm")

/**
 * 清掉 GeckoView 累积的鉴权 cookie。
 *
 * ## 为什么必须清（真机实测，2026-10-17）
 *
 * 引擎打印的 URL 带一次性 token，它对主文档回 **303 + `Set-Cookie`** 完成
 * token→cookie 交换，而那个 cookie 的名字**每次都不一样**
 * （`dsh-auth-<随机后缀>`，`Max-Age=2592000` 即 30 天）。GeckoView 把它持久化在
 * `files/mozilla/<profile>/cookies.sqlite` 里 —— 于是**每启动一次就多一个**，
 * 且 30 天内不会被回收。
 *
 * 累积到一定数量后，请求头里的 `Cookie` 超过服务器上限，引擎对首页直接回
 * **431 Request Header Fields Too Large 且响应体为空**：
 *
 * ```
 * [fe-diag] probe: "431|len=0|"   ← 页面世界自己 fetch '/' 拿到的结果
 * ```
 *
 * 症状是**永久白屏**，且极具迷惑性 —— 页面地址正确、没有任何 JS 报错、
 * `document.scripts` 与 `#root` 全空、console 一条不输出。实测累积到
 * **142 个** cookie 时稳定复现；清掉 cookies 库后同一份代码立刻恢复渲染。
 *
 * ## 为什么整体删而不是只删过期的
 *
 * 本 App 的 GeckoView 里**只有**引擎的鉴权 cookie（外链一律交系统浏览器，
 * 见 `onLoadRequest`），而每次启动都会重新交换出一个新的，所以整个库都是
 * 可丢的。只删过期项要解析 SQLite，收益为零。
 *
 * ⚠️ **必须在 `GeckoRuntime.create()` 之前做** —— Runtime 一起来就打开并锁住
 *    该库，此后再删只会失败（且 Gecko 仍用着内存里的旧数据）。
 * ⚠️ 目录名带随机 profile 前缀（如 `wyzddpw3.default`），所以按目录扫，不写死。
 */
internal fun purgeAuthCookies(context: Context) {
    val dirs = File(context.filesDir, "mozilla").listFiles() ?: return
    var purged = 0
    for (dir in dirs) {
        if (!dir.isDirectory) continue
        for (name in AUTH_COOKIE_FILES) {
            val f = File(dir, name)
            if (f.exists() && f.delete()) purged++
        }
    }
    if (purged > 0) {
        Log.i(DshApplication.TAG, "已清理 GeckoView 累积的鉴权 cookie（$purged 个文件）")
    }
}

/**
 * 建 GeckoRuntime。
 *
 * ⚠️ 用 `GeckoRuntime.getDefault()` 优先，拿不到才 `create()`：Runtime 是**进程级
 *   单例**，每次 Activity 重建都 create 一个会泄漏（它持有 Gecko 主进程的连接）。
 *   `getDefault` 在第一次 `create` 之后就返回同一个实例。
 */
private fun createRuntime(context: Context): GeckoRuntime {
    purgeAuthCookies(context)
    val settings = GeckoRuntimeSettings.Builder()
        // 页面要能打 console（转发由 content script 做，见 content.js）。
        .consoleOutput(true)
        // ── 远程调试通道（给 UI 调试工具用，对用户不可见）──────────────────
        //
        // 与 system flavor 的 `WebView.setWebContentsDebuggingEnabled(true)` 同构：
        // 让 GeckoView 暴露调试 socket，从而 `ui` 工具那条链路的「原生 → 页面」
        // 一半可以在真机上被独立验证。
        //
        // ⚠️ 只监听本机、不改界面行为；不开它的话 gecko flavor 上这条链路
        //   无法被验证（页面世界里没有任何入口能从外部进去）。
        .remoteDebuggingEnabled(true)
        .build()
    return GeckoRuntime.create(context, settings)
}
