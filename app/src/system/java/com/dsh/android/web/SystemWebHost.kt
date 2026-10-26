package com.dsh.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.dsh.android.log.DshLog as Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dsh.android.DshApplication

/**
 * `system` flavor：用设备上的系统 WebView 承载界面。
 *
 * 这一份是把 [com.dsh.android.MainActivity] 里原本直接写在 `setupWebView()` 中的
 * 逻辑原样搬过来，**行为一字不改** —— 原有的两个能力缺口（`onShowFileChooser`
 * 与 `setDownloadListener` 的默认实现静默失败）的修法也一并保留。
 *
 * 唯一的结构性变化是：文件选择与下载不再由本类自己 `startActivity`，而是通过
 * [WebHostCallbacks] 交回宿主 —— 因为 gecko 侧走的是完全不同的机制，而宿主的
 * 处理逻辑（cookie 鉴权下载、一次性回调收尾）两边必须共用一份。
 */
@SuppressLint("SetJavaScriptEnabled")
class SystemWebHost(
    context: Context,
    private val callbacks: WebHostCallbacks,
) : WebHost {

    private val webView = WebView(context)

    /**
     * 当前挂起的文件选择回调；null 表示没有等待中的选择。
     *
     * ⚠️ 回调是**一次性**的：必须在调用前取出并置空，否则第二次点击不会响应
     *   （WebView 会一直挂着等这个 callback）。
     */
    private var filePathCallbackRef: ValueCallback<Array<Uri>>? = null

    /** 最近一次发起加载的 URL（见 [WebHost.currentUrl] 的语义说明）。 */
    private var lastLoadedUrl: String? = null

    /** 是否已经提交过至少一帧（见 [awaitFirstFrame]）。 */
    private var painted = false

    /** 是否已有一个等待首帧的回调在飞，避免重复注册。 */
    private var paintPending = false

    /** `postVisualStateCallback` 的请求号，单调递增即可（它只是个标识）。 */
    private var paintRequestId = 0L

    /**
     * 已经作废到哪个 requestId（含）。`resetFirstFrame()` 会把它推到当前值，
     * 于是所有**重载前**注册的回调都会被 `onComplete` 丢弃（见那里的门控）。
     */
    private var staleRequestId = 0L

    /** 已经等在首帧上的动作（首帧到达时执行）。 */
    private var pendingPaintAction: (() -> Unit)? = null

    override val view: View get() = webView

    override val currentUrl: String? get() = lastLoadedUrl

    override val engineMajor: Int
        get() = runCatching { WebView.getCurrentWebViewPackage() }
            .getOrNull()?.versionName.orEmpty()
            .substringBefore('.').toIntOrNull() ?: 0

    override val engineLabel: String
        get() = runCatching { WebView.getCurrentWebViewPackage() }
            .getOrNull()?.let { "${it.packageName} ${it.versionName}" }
            .orEmpty()

    init {
        // ── 远程调试通道（给 UI 调试工具用，对用户不可见）────────────────
        //
        // 打开它，`webview_devtools_remote_<pid>` 这个抽象 socket 才会出现，
        // 于是**同一个页面世界**可以经 CDP 被读和点 —— 也就是 `ui` 工具那条
        // 信箱链路的另一半（原生 → 探针）可以在真机上被独立验证。
        //
        // ⚠️ 这不改变任何界面行为：它只多开一个**只监听本机**的调试 socket，
        //   不开任何端口、不监听网络，页面与用户看到的东西一模一样。
        //   Android 官方的定位就是「开发期开关」，此处常开是因为这个 App
        //   本身就是自用调试机 —— 而 `ui` 工具在没有它的时候无法被验证。
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 引擎跑在 http://127.0.0.1，属于非安全上下文，
            // 需要允许混合内容，否则部分 API 被浏览器策略拦掉。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
            // ⚠️ **必须开多窗口**，否则页面里的 `window.open` 被内核直接丢弃。
            //
            // 根因链（三处叠加，缺一不可）：
            //   1. 官方 `dsh-client-ui-chat` 的 `linkOpening` 默认值是 `"sidebar"`；
            //   2. 但侧栏浏览器插件 `@deepseek-ai/dsh-client-ui-sidebar-browser` 在
            //      `dsh-web-app/cordis.patch.yml` 里被 `disabled: !!js
            //      "ctx.get('profileContext')?.name !== 'desktop'"` 关掉（本 App 是
            //      web profile，不是 desktop）；
            //   3. 于是 `openExternalLink` 走 else 分支 —— 源码原文
            //      `window.open(url, "_blank", "noopener,noreferrer")`。
            //
            // 而 WebView 在 `setSupportMultipleWindows(false)`（**默认值**）时，
            // `window.open` 会**静默失败**：不报错、不打日志、不触发
            // `shouldOverrideUrlLoading`。用户看到的就是「AI 给的链接点不动，
            // 只能复制到浏览器」。
            //
            // ⚠️ 只开这一项**不够** —— 开了之后 `window.open` 会走
            //   `WebChromeClient.onCreateWindow`，而那个方法的**默认实现同样返回
            //   false 表示"不创建新窗口"**。两个必须配对改，见下面的 onCreateWindow。
            setSupportMultipleWindows(true)
            // ⚠️⚠️ **单开上面那项不够，这一项才是「登录」按钮打不开的真根因。**
            //
            // 真机实测日志（`WebView 多窗口:` 那行）在只设 `setSupportMultipleWindows(true)`
            // 时是 `javaScriptCanOpenWindows=false` —— 此时内核**直接吞掉**
            // `window.open`，返回 null，而且**连 `onCreateWindow` 都不回调**，
            // 所以原生侧加多少日志都看不到，极易误判成"回调没实现"。
            //
            // 官方登录（`dsh-client-ui-settings-account` 的 `BrowserLogin.begin`）恰好
            // 就是 `window.open("about:blank", "_blank")`，于是：
            // `popup` 为 null ⇒ 后面 `popup.location.replace(authorizeUrl)` 静默失败
            // ⇒ 用户点了「登录」什么都不会发生，只剩对话框里那个「复制登录链接」。
            javaScriptCanOpenWindowsAutomatically = true
            Log.i(
                DshApplication.TAG,
                "WebView 多窗口: supportMultipleWindows=${supportMultipleWindows()} " +
                    "javaScriptCanOpenWindows=$javaScriptCanOpenWindowsAutomatically " +
                    "内核=${runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()}",
            )
        }
        webView.setBackgroundColor(Color.parseColor("#0F1115"))

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                // 只允许留在本地引擎里；外链交给系统浏览器
                if (url.startsWith(LOCAL_ENGINE_PREFIX)) return false
                // ⚠️ `blob:` / `data:` 也是**内核自己才能解析**的 URL，不能当外链丢出去。
                //
                // blob URL 只在**创建它的那个页面上下文**里有效（由内核维护一张
                // blob 注册表），系统里没有任何应用能处理 `blob:` 协议 —— 交给
                // `Intent.ACTION_VIEW` 必然 `ActivityNotFoundException`，而且是
                // **静默失败**：用户点一次没反应、再点一次还是没反应。
                //
                // 真机证据：`20261014-002816-785b5eec.zip` 里同一个会话内
                // **连续 29 次** `无法打开外部链接: blob:http://127.0.0.1:3080/<uuid>`，
                // 时间跨 23:36 → 00:11（35 分钟），用户显然在反复点击同一个东西。
                // 那些 URL 前缀是 `blob:http://127.0.0.1:3080/…` —— 页面**自己的**
                // 同源 blob，本该由内核处理。
                //
                // 回 false = 不接管，让内核按默认行为走（`<a download>` 会走到
                // 我们的 DownloadListener，普通导航由内核自己渲染）。
                if (url.startsWith("blob:") || url.startsWith("data:")) return false
                callbacks.onExternalUrl(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                callbacks.onPageFinished(url)
            }

            /**
             * 渲染进程死了。
             *
             * ## ⚠️ 不覆写这个方法，默认行为是**杀掉整个 App**
             *
             * 官方文档写得很直白：`onRenderProcessGone` 的默认实现返回 `false`，
             * 于是框架把**宿主应用的整个进程**一并终止。用户看到的就是「App 突然
             * 自己退了」，且没有任何日志能指向真正的死因（进程都没了）。
             *
             * 覆写并返回 `true` = 「这个 WebView 的坏状态我认了，我自己收拾」，
             * 框架就不会杀进程。
             *
             * ## 两种触发，都不是 App 的 bug
             *
             * - `didCrash = true`：渲染进程自己崩了（内核 bug、OOM）。
             * - `didCrash = false`：**被系统回收**。低内存时系统会主动收掉后台
             *   WebView 的渲染进程以省内存，这是正常的资源管理，不是错误。
             *
             * 两种情况下这个 WebView 都已经画不出东西了，界面必须重载才会回来
             * （见 `MainActivity.onRendererGone`）。
             *
             * ⚠️ 返回 `true` 之后**必须**用 `destroy()` 或重载来收拾这个 WebView：
             *   官方要求「不再使用它」。这里走重载 —— 同一个 `WebView` 实例在
             *   `onRenderProcessGone` 之后重新 `loadUrl` 是可行的，内核会为它
             *   拉起一个新的渲染进程（这正是「恢复」的实现方式）。
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                Log.w(
                    DshApplication.TAG,
                    "WebView 渲染进程已终止（didCrash=${detail.didCrash()}，" +
                        "rendererPriorityAtExit=${detail.rendererPriorityAtExit()}）",
                )
                callbacks.onRendererGone(detail.didCrash())
                // true = 已处理，别杀我的进程（见上面的 ⚠️）
                return true
            }
        }

        // 插件用它把「AI 当前在干什么」推给通知栏（见 EngineService.updateStatus）。
        webView.addJavascriptInterface(SystemPageBridge(callbacks.bridge), "DSHAndroid")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                callbacks.onConsole("${msg.message()} @${msg.lineNumber()}")
                return true
            }

            /**
             * 页面请求麦克风 —— **必须覆写**。
             *
             * ⚠️ 默认实现直接 `deny()`：不弹框、不打日志、页面只拿到
             *   `NotAllowedError`。前端把那个错误翻译成 `RecordingError("permission")`，
             *   界面上永远显示「麦克风权限被拒绝」—— **即使 Android 权限早就授过了**。
             *   这是本项目第三处「默认实现静默失败」（前两处是 ⑦ 的文件选择器、
             *   ⑦.5 的下载器）。
             *
             * ## 只放行音频，别的照旧拒绝
             *
             * 本 App 只有一个页面、只用到录音。摄像头、地理位置、通知这些内核
             * 会来问的权限**一个都不放行** —— 收紧到「只开需要的那一个」，
             * 比"反正都是自己人、全放行"安全得多，而且以后加权限时也必须
             * 显式写在这里，不会静默变成放行。
             *
             * ⚠️ **`grant()` 必须在 Android 权限真的拿到之后才调**（见
             *   [MicrophoneChannel]）：先放行、后申请的话，内核认为已经批准、
             *   音频却录不到，症状变成「录出来是静音」—— 那比直接拒绝难查得多。
             */
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsMic = request.resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (!wantsMic) {
                    Log.i(
                        DshApplication.TAG,
                        "页面请求内核权限 ${request.resources.joinToString()}，不属于麦克风，拒绝",
                    )
                    request.deny()
                    return
                }
                Log.i(DshApplication.TAG, "页面请求麦克风，转交宿主申请 Android 权限")
                callbacks.microphone.request { granted ->
                    // ⚠️ 回调可能在任意线程（宿主那边走了权限 launcher 的线程）
                    webView.post {
                        if (granted) {
                            Log.i(DshApplication.TAG, "麦克风授权：允许")
                            // ⚠️ `grant()` 吃的是**要放行的资源列表**，不是 origin
                            //   也不是布尔值 —— 签名是 `grant(String[] resources)`。
                            //   把 `request.resources` 原样回传即可（上游就是这么做
                            //   的，见 WebView 文档的 onPermissionRequest 示例）。
                            request.grant(request.resources)
                        } else {
                            Log.w(DshApplication.TAG, "麦克风授权：用户拒绝")
                            request.deny()
                        }
                    }
                }
            }

            /**
             * 文件选择器接管。
             *
             * ⚠️ **必须实现**：WebView 的默认实现直接 `callback.onReceiveValue(null)`，
             * 页面侧完全无感 —— 症状就是用户反馈的「点『文件』静默一点反应都没有」。
             * WebView 自身不弹任何选择器，必须由宿主 App 拉起。
             */
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // 上一次还没回调完就再来一次时，必须先把它收掉，
                // 否则 WebView 会一直挂着等这个 callback（后续选择器全部失效）。
                filePathCallbackRef?.onReceiveValue(null)
                filePathCallbackRef = filePathCallback

                callbacks.fileChooser.launch(
                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                ) { uris ->
                    val cb = filePathCallbackRef ?: return@launch
                    filePathCallbackRef = null
                    cb.onReceiveValue(uris)
                }
                return true
            }

            /**
             * 接管 `window.open` —— 官方前端打开外链走的就是这条路。
             *
             * ## 为什么需要它
             *
             * `setSupportMultipleWindows(true)` 只解决「内核不再丢弃这次调用」，接下来
             * 内核会回调本方法，而**默认实现返回 false = 不创建新窗口**，页面依然什么
             * 都看不到。两处必须配对（见 `settings` 里的注释）。
             *
             * ## 做法：一律交给系统浏览器，**不在应用内开第二个窗口**
             *
             * 本 App 只有一个 WebView、一个 `WebHostCallbacks`，再开一个窗口意味着
             * 要管理第二个内核实例的生命周期、第二个 `DSHAndroid` 桥、返回键归属 ——
             * 而用户想要的只是「点开链接看到内容」，系统浏览器本来就干得更好。
             *
             * ⚠️ **必须从 `WebView.HitTestResult` 里取目标 URL**，不能用最后一个
             *   参数 `resultMsg` —— 那条路给的是 `Handler` 消息（可能带一个空
             *   `WebView`），拿不到 URL。标准做法就是读 `view.hitTestResult.extra`。
             *
             * ⚠️ **拿到 URL 后必须回 `false`**（不创建窗口），否则内核会认为我们接管了，
             *   等着我们往那个空 `WebView` 里 `loadUrl`，结果是一个白窗口。
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean {
                // 目标 URL 只能从 hitTestResult 里拿（见上面的 ⚠️）。
                val url = view.hitTestResult?.extra
                if (url.isNullOrEmpty()) {
                    // ⚠️⚠️ **这里原本一律 return false，导致「登录」按钮完全打不开。**
                    //
                    // 官方登录流程（`dsh-client-ui-settings-account` 的 `BrowserLogin`）
                    // 是这么做的：
                    //
                    //     this.popup = window.open("about:blank", "_blank");   // 预开
                    //     … 拿到 authorizeUrl 后 …
                    //     this.popup.location.replace(authorizeUrl);           // 再导航
                    //
                    // 也就是说 `window.open` 的目标是 **`about:blank`**，而它
                    // **不来自 `hitTestResult`**（那是点击命中的链接，这里是 JS 调用），
                    // 所以 `extra` 是空 —— 旧实现回 false ⇒ 内核不建窗口 ⇒
                    // `window.open` 返回 null ⇒ 后面 `popup.location.replace` 作用在
                    // null 上静默失败。用户看到的就是「点登录，什么都没发生」。
                    //
                    // 正确做法：**为 `about:blank` 建一个真正的 WebView 窗口**，
                    // 把它塞进 `resultMsg.obj` 的 `WebView.WebViewTransport`，
                    // 再回 true 让内核把渲染目标接上去。URL 由页面自己随后导航。
                    //
                    // 其余"拿不到 URL"的情形（页面异步调用、没有 transport 等）仍然
                    // 回 false —— 绝不能对任何无 URL 的请求都建窗口，那会留下白窗口。
                    return createBlankPopup(view, resultMsg)
                }
                // 本地引擎的 URL 不该走这里（那是 SPA 内部导航）。真出现时回 false
                // 让内核丢弃这次新窗口请求，页面自身的导航不受影响。
                if (url.startsWith(LOCAL_ENGINE_PREFIX)) {
                    Log.i(DshApplication.TAG, "window.open 指向本地引擎，忽略: $url")
                    return false
                }
                // 同 shouldOverrideUrlLoading：`blob:` / `data:` 只有内核自己解析得了，
                // 丢给系统浏览器必然静默失败。回 false 让内核丢弃这次新窗口请求。
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    Log.i(DshApplication.TAG, "window.open 指向内核内 URL（blob/data），忽略: ${url.take(80)}")
                    return false
                }
                Log.i(DshApplication.TAG, "window.open 外链转系统浏览器: $url")
                callbacks.onExternalUrl(url)
                return false
            }
        }


        /**
         * 下载接管。
         *
         * ⚠️ **必须实现**：与 `onShowFileChooser` 同一类缺口 —— WebView 自己不会
         * 下载，默认实现直接丢弃，页面侧完全无感。症状是「下载 Session 日志」弹窗
         * 显示「浏览器正在下载 Session ZIP 文件」，但文件根本没落地。
         */
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            callbacks.onDownload(
                DownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                )
            )
        }
    }
        /**
         * 为一个 **URL 未知**（`about:blank` 预开）的 `window.open` 建窗口。
         *
         * ## 为什么不能复用主 WebView
         *
         * 主 WebView 正跑着整个 App 的 UI，把登录页导航进去等于把界面冲掉；而
         * `window.open` 语义上要一个**独立**的渲染目标。所以这里新建一个最小
         * WebView（只带必要的 `WebViewClient`，不注入任何 JS 桥）。
         *
         * ## 内存与生命周期
         *
         * 登录成功后官方会 `popup.close()`，内核随即销毁该窗口的渲染进程；
         * 我们持有的这个 `WebView` 只是宿主对象，随 Dialog 一起被回收 ——
         * 不 `addView`，不需要手动 `destroy()`。
         *
         * ## 安全
         *
         * ⚠️ **不注入 `addJavascriptInterface`**：这是纯粹的 OAuth 授权页，
         *   第三方页面 + 原生桥 = 给授权页一把你家钥匙。它只需要能跳转。
         *
         * @return true = 已把新 WebView 交给内核；false = 放弃（不建窗口）。
         */
        private fun createBlankPopup(parent: WebView, resultMsg: android.os.Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport
            if (transport === null) {
                Log.w(DshApplication.TAG, "window.open 无可用的 WebViewTransport，忽略")
                return false
            }
            return try {
                // ── 优先交给宿主（= 内置浏览器的一个标签）──────────────────
                // ⚠️⚠️ **这里原来新建一个裸 `WebView` 却从不 `addView`**，
                //    所以它根本没有可见的宿主 —— 内核往里渲染、页面也确实在跑，
                //    但用户屏幕上什么都没有。症状就是「点登录，一片空白」。
                //    现在改成让宿主给一个**已经挂在可见容器里**的 WebView。
                val hosted = callbacks.onCreatePopup?.invoke()
                if (hosted != null) {
                    transport.webView = hosted
                    // ⚠️⚠️ **这一行才是内核真正拿到新窗口的时刻。**
                    //
                    // `onCreateWindow` 的正确协议是：宿主把 WebView 塞进
                    // `transport`，**然后 `sendToTarget()` 投递那条消息**，内核在
                    // 消息到达时才把渲染目标接上去。
                    //
                    // 之前只有 `transport.webView = hosted` + `return true`，没有
                    // `sendToTarget()` —— 于是内核永远不知道窗口在哪：我们这边
                    // 标签页看得见（那是自己 addView 的），内容却**整片空白**，
                    // 地址栏停在 `about:blank`。用户截图正是这个症状。
                    resultMsg.sendToTarget()
                    Log.i(DshApplication.TAG, "window.open 已交给内置浏览器承载并投递")
                    return true
                }
                // ── 兜底：宿主没接（理论上不会发生）──────────────────────
                val popup = WebView(parent.context)
                popup.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // 授权页可能弹 cookie 同意/第三方跳转，保持与主视图一致。
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                // 授权过程会依次跳到 DeepSeek 的多个域，最后回到本地引擎完成登录。
                // 在这里**不拦 URL**（拦了就会把授权链打断），只做跳转放行。
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val u = request.url.toString()
                        // 回到本地引擎 = 授权回调已经完成，关掉这个窗口免得留在后台。
                        if (u.startsWith(LOCAL_ENGINE_PREFIX)) {
                            Log.i(DshApplication.TAG, "登录授权已回到本地引擎，关闭弹窗")
                            (v.parent as? android.view.ViewGroup)?.removeView(v)
                            v.destroy()
                            return true
                        }
                        Log.i(DshApplication.TAG, "登录弹窗导航: ${u.take(100)}")
                        return false
                    }
                }
                transport.webView = popup
                // ⚠️ 与上面同理：`transport.webView` 赋值**之后**必须投递消息，
                //   内核收到消息才会去读它。少了这一句就是白窗口。
                resultMsg.sendToTarget()
                Log.w(DshApplication.TAG, "window.open 用兜底窗口（不可见！宿主未接 onCreatePopup）")
                true
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "建 window.open 窗口失败: ${t.message}")
                false
            }
        }


    override fun loadUrl(url: String) {
        lastLoadedUrl = url
        webView.loadUrl(url)
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script) { result -> callback?.invoke(result) }
    }

    override fun awaitFirstFrame(action: () -> Unit) {
        if (painted) { action(); return }
        if (paintPending) return          // 已经在等了，避免重复注册
        paintPending = true
        pendingPaintAction = action

        // ⚠️ 第一个参数是 requestId，回调会原样带回 —— 它只是个标识，
        //   不是 layerType（写成 LAYER_TYPE_* 会让人误以为有语义）。
        paintRequestId++
        webView.postVisualStateCallback(
            paintRequestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    // ⚠️ 被 resetFirstFrame() 作废的旧回调：它代表的是**重载前**那一帧，
                    //   不能算「重载成功了」。
                    if (requestId <= staleRequestId) return
                    if (painted) return
                    painted = true
                    paintPending = false
                    val a = pendingPaintAction
                    pendingPaintAction = null
                    a?.invoke()
                }
            },
        )
    }

    /**
     * 重新加载当前 URL（渲染进程崩溃后的恢复路径）。
     *
     * ⚠️ **不接受「没有 URL」这一步**：`lastLoadedUrl` 为空说明还没加载过任何
     *   页面，那时崩溃是无从恢复的（引擎都还没起来），静默返回即可 ——
     *   硬 `reload()` 一个空白 WebView 没有任何意义。
     *
     * ⚠️ 重载后要把 `painted` 复位：新渲染进程的第一帧还没提交，启动层的
     *   「等首帧」逻辑若沿用旧的 `painted = true` 会立刻收层，用户就看到白屏。
     */
    override fun reload() {
        val url = lastLoadedUrl
        if (url.isNullOrEmpty()) {
            Log.w(DshApplication.TAG, "渲染进程已死但没有可用的 URL，无法重载")
            return
        }
        Log.i(DshApplication.TAG, "重载界面: $url")
        painted = false
        paintPending = false
        pendingPaintAction = null
        webView.loadUrl(url)
    }

    override fun resetFirstFrame() {
        // ⚠️ 只清标志**不够**：`postVisualStateCallback` 是排队到「当前视觉状态」的，
        //   重载前注册的那个回调在视觉状态已提交时**可能立刻触发**，把
        //   `painted` 又置回 true —— 那是**旧内容**的帧，照样是假成功。
        //   所以必须按 requestId 作废所有在飞的旧回调（见 onComplete 里的门控）。
        staleRequestId = paintRequestId
        painted = false
        paintPending = false
        pendingPaintAction = null
    }

    override fun destroy() {
        pendingPaintAction = null
        webView.destroy()
    }

    /** 供宿主读 cookie 用（下载要带 HttpOnly cookie，见 `MainActivity.handleDownload`）。 */
    fun cookieFor(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

    private companion object {
        /** 本地引擎的 URL 前缀；其余一律当外链。 */
        const val LOCAL_ENGINE_PREFIX = "http://127.0.0.1"
    }
}

/**
 * 把 [PageBridge] 包成 `@JavascriptInterface` 能认的形态。
 *
 * ⚠️ 之所以要包一层而不是给接口本身打注解：注解只能落在**实现类的方法**上，
 *   而 [PageBridge] 是接口，两个 flavor 的实现类位置不同。包一层还能保证
 *   `window.DSHAndroid` 上暴露的方法名**恰好**是页面侧用的那 16 个，
 *   不多不少 —— 接口上将来加方法不会静默泄漏到页面里。
 *
 * ⚠️ 方法运行在 WebView 的 JS 线程上，**不能碰 View**；切主线程由 [PageBridge]
 *   的实现负责。
 */
private class SystemPageBridge(private val impl: PageBridge) {

    @android.webkit.JavascriptInterface
    fun onStatus(json: String) = impl.onStatus(json)

    @android.webkit.JavascriptInterface
    fun onImeWillShow() = impl.onImeWillShow()

    @android.webkit.JavascriptInterface
    fun startWirelessPairing() = impl.startWirelessPairing()

    @android.webkit.JavascriptInterface
    fun cancelWirelessPairing() = impl.cancelWirelessPairing()

    @android.webkit.JavascriptInterface
    fun pairingNotificationsEnabled(): Boolean = impl.pairingNotificationsEnabled()

    @android.webkit.JavascriptInterface
    fun onUiReady() = impl.onUiReady()

    @android.webkit.JavascriptInterface
    fun reportWebError(message: String) = impl.reportWebError(message)

    @android.webkit.JavascriptInterface
    fun switchToTcpMode() = impl.switchToTcpMode()

    // ⋯ 菜单「刷新」的安全重载（见 PageBridge.reloadWebUi 的 KDoc：
    // 页面侧 location.reload() 会丢启动 token ⇒ 401）。
    @android.webkit.JavascriptInterface
    fun reloadWebUi() = impl.reloadWebUi()

    @android.webkit.JavascriptInterface
    fun bubbleState(): String = impl.bubbleState()

    @android.webkit.JavascriptInterface
    fun bubbleSetEnabled(enabled: Boolean) = impl.bubbleSetEnabled(enabled)

    @android.webkit.JavascriptInterface
    fun bubbleRequestOverlay() = impl.bubbleRequestOverlay()

    // ── AI 浏览器（官方右侧边栏的「AI 浏览器」面板）──────────────────────
    // ⚠️ 与 gecko 版白名单必须逐条对齐 —— 少一条就是「点了没反应」，而原生
    //   日志一切正常（页面侧 `window.DSHAndroid.openAiBrowser` 是 undefined）。
    @android.webkit.JavascriptInterface
    fun openAiBrowser() = impl.openAiBrowser()

    @android.webkit.JavascriptInterface
    fun aiBrowserLayout(json: String) = impl.aiBrowserLayout(json)

    @android.webkit.JavascriptInterface
    fun aiAnnotateResult(): String = impl.aiAnnotateResult()

    @android.webkit.JavascriptInterface
    fun aiAnnotateReport(message: String) = impl.aiAnnotateReport(message)

    @android.webkit.JavascriptInterface
    fun aiBrowserDetach() = impl.aiBrowserDetach()

    @android.webkit.JavascriptInterface
    fun aiBrowserState(): String = impl.aiBrowserState()

    // ── 甩动手感三开关（见「悬浮气泡」设置页）────────────────────────────
    // ⚠️ 与 gecko 版白名单必须逐条对齐，两边方法名少一个就是「点了没反应」。
    @android.webkit.JavascriptInterface
    fun bubbleSetThrow(enabled: Boolean) = impl.bubbleSetThrow(enabled)

    @android.webkit.JavascriptInterface
    fun bubbleSetShake(enabled: Boolean) = impl.bubbleSetShake(enabled)

    @android.webkit.JavascriptInterface
    fun bubbleSetImeFling(enabled: Boolean) = impl.bubbleSetImeFling(enabled)

    // ── 键盘避让（见「悬浮气泡 → 键盘避让」卡片）──────────────────────────
    // ⚠️ 这三个方法 PR #3 只加进了 `PageBridge` 接口与 `MainActivity` 实现，
    //   **漏了这份白名单** —— 症状正是「开了无障碍，开关还是打不开」：
    //   页面侧 `window.DSHAndroid.keyboardAvoidState` 是 undefined，前端据此
    //   走进 `noBridge` 分支，开关被置灰，而原生日志一切正常（静默失败）。
    //   这份白名单是**手工维护**的，往 `PageBridge` 加方法必须同步加到这里。
    @android.webkit.JavascriptInterface
    fun keyboardAvoidState(): String = impl.keyboardAvoidState()

    /** 返回 Boolean 让页面知道有没有被拒（无检测来源时原生拒绝打开）。 */
    @android.webkit.JavascriptInterface
    fun keyboardAvoidSetEnabled(enabled: Boolean): Boolean = impl.keyboardAvoidSetEnabled(enabled)

    @android.webkit.JavascriptInterface
    fun keyboardAvoidRequestPermission() = impl.keyboardAvoidRequestPermission()

    @android.webkit.JavascriptInterface
    fun bubbleReportBalance(json: String) = impl.bubbleReportBalance(json)

    @android.webkit.JavascriptInterface
    fun bubbleBadgeConfig(): String = impl.bubbleBadgeConfig()

    @android.webkit.JavascriptInterface
    fun bubbleTapRefreshSeq(): Int = impl.bubbleTapRefreshSeq()

    @android.webkit.JavascriptInterface
    fun writeClipboard(text: String): Boolean = impl.writeClipboard(text)

    /** 页面诊断（写进 app.log，见 `PageBridge.pageDiag` 的说明）。 */
    @android.webkit.JavascriptInterface
    fun pageDiag(text: String) = impl.pageDiag(text)

    /**
     * 会话转录上报。
     *
     * ⚠️ 运行在 WebView 的 JS 线程上 —— `FloatingBubble.onTranscript()` 内部切主线程，
     *   这里不能碰 View。
     */
    @android.webkit.JavascriptInterface
    fun onTranscript(json: String) = impl.onTranscript(json)

    /**
     * 插件下发悬浮球外观配置。
     *
     * ⚠️ system 版是**同步**返回 Boolean 的（`addJavascriptInterface` 支持基本类型），
     *   所以插件能立刻知道自己写错了没有。gecko 版只能异步，见那里的注释。
     */
    @android.webkit.JavascriptInterface
    fun bubbleApply(json: String): Boolean = impl.bubbleApply(json)

    @android.webkit.JavascriptInterface
    fun bubbleAppearance(): String = impl.bubbleAppearance()

    @android.webkit.JavascriptInterface
    fun rootState(): String = impl.rootState()

    @android.webkit.JavascriptInterface
    fun rootRecheck() = impl.rootRecheck()

    // Shizuku（uid 2000 的第二条来源，见 ShizukuChannel）。
    // ⚠️ 这个白名单是手工维护的 —— 往 PageBridge 加方法必须同步加到这里，
    //   否则页面侧 `window.DSHAndroid.shizukuState` 是 undefined，
    //   而插件里写的是「没有原生桥」，症状是那一块显示成没有桥，**不报错**。
    //   这个坑真踩过一次（openLogs / logSummary 加接口时漏了这里）。
    @android.webkit.JavascriptInterface
    fun shizukuState(): String = impl.shizukuState()

    @android.webkit.JavascriptInterface
    fun shizukuRequestPermission() = impl.shizukuRequestPermission()

    @android.webkit.JavascriptInterface
    fun shizukuOpenManager() = impl.shizukuOpenManager()

    @android.webkit.JavascriptInterface
    fun shizukuOpenDownload() = impl.shizukuOpenDownload()

    /**
     * 设置页「运行日志与诊断」→ 拉起原生日志界面。
     *
     * ⚠️ **这个白名单是手工维护的**（见类注释：「恰好是页面侧用的那些，不多不少」）。
     *   往 [PageBridge] 加方法**必须**同步加到这里，否则页面侧
     *   `window.DSHAndroid.openLogs` 是 `undefined`，而插件里写的是
     *   「没有原生桥」—— 症状是设置页那一块显示成没有桥，**不报错**。
     *   这个坑真踩过一次（`openLogs` / `logSummary` 加接口时漏了这里）。
     */
    @android.webkit.JavascriptInterface
    fun openLogs() = impl.openLogs()

    /**
     * 读日志摘要（JSON 文本）。
     *
     * ⚠️ 同上，必须出现在这个白名单里，否则设置页永远显示「没有原生桥」。
     *   必须返回 String：`@JavascriptInterface` 只支持基本类型与 String，
     *   返回自定义对象会静默变成 `undefined`。
     */
    @android.webkit.JavascriptInterface
    fun logSummary(): String = impl.logSummary()

    // ── 原生上传兜底通道（见 NativeUploader 的类注释）──────────────────────
    //
    // ⚠️ 同样必须出现在这个白名单里。漏了的症状比别处更隐蔽：
    //   polyfill 会以为「没有原生桥」，于是只报「XHR 与 fetch 均失败」，
    //   用户看到的现象和没修一样 —— 而原生侧其实完全正常。

    @android.webkit.JavascriptInterface
    fun uploadNativeBegin(taskId: String, url: String, headersJson: String): Boolean =
        impl.uploadNativeBegin(taskId, url, headersJson)

    @android.webkit.JavascriptInterface
    fun uploadNativeWrite(taskId: String, base64: String): Boolean =
        impl.uploadNativeWrite(taskId, base64)

    @android.webkit.JavascriptInterface
    fun uploadNativeFinish(taskId: String) = impl.uploadNativeFinish(taskId)

    @android.webkit.JavascriptInterface
    fun uploadNativeAbort(taskId: String) = impl.uploadNativeAbort(taskId)
}
