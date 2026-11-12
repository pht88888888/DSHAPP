package com.dsh.android.ui

/*
 * ─────────────────────────────────────────────────────────────────────────
 *  MainActivity —— 宿主壳：WebView、启动层、权限、下载、AI 浏览器
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  本类只管「把引擎的 Web GUI 显示出来，并把 Android 的能力接给它」。
 *  聊天界面、会话、模型全部在网页里，这里**不碰**业务逻辑。
 *
 *  按你要做的事找入口：
 *
 *    WebView 装配        setupWebView()        设置项、内核回调、JS 桥注入
 *    启动层与超时        BootOverlayPresenter  两级提示：12 秒中性、27 秒红字
 *    真首帧与展示兜底    FirstFrameAwaiter     真实首帧与超时分别记账
 *    内核恢复            WebRecoveryController 去重、退避、重试与停止
 *    键盘预判            ImeLayoutController   高度学习、朝向缓存和交接
 *    页面返回            PageBackNavigation    单次探针、超时与迟到隔离
 *    系统栏帧序列        SystemBarFlashSequence 只负责帧与圈数
 *    浏览器操作提示      BrowserActivityPill   自有视图、动画与计时
 *    权限                 requestRuntimePermission()  统一入口，串行排队
 *                         （页面要什么能力由 WebCapability 定义）
 *    下载                handleDownload()      授权快照交下载事务
 *                         handleDownloadStream()  gecko 内核代发的响应
 *    AI 浏览器           ensureAiBrowser()     内置浏览器，window.open 的落点
 *    面板与标注状态      BrowserPaneCoordinator 模式、标注身份与画布世代
 *    页面桥装配          NativePageBridgeDispatcher 领域提供者与宿主动作
 *                         BubblePageBridge / PrivilegePageBridge
 *    引擎                engine（EngineManager）
 *
 *  ## 改这里之前必须知道的三件事
 *
 *  1. **内核回调默认全是「静默拒绝」。** 文件选择、下载、媒体权限、JS 对话框、
 *     地理位置 —— 不实现就不报错，页面只拿到一个含糊的失败。新增能力必须
 *     同时改三处：清单声明、内核回调、运行时申请。判定收敛在 WebCapability。
 *  2. **权限申请必须串行。** 系统同一时刻只处理一个对话框，并发 launch 会
 *     丢掉后一个。队列见 RuntimePermissionQueue。
 *  3. **启动层的收起信号来自页面**（桥的 onUiReady），不是 onPageFinished。
 *     页面加载完 ≠ 移动端插件装配完，两者真机实测差 1.4~1.7 秒。
 */
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.dsh.android.log.DshLog as Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dsh.android.backup.BackupDialog
import com.dsh.android.databinding.ActivityMainBinding
import com.dsh.android.log.BootState
import com.dsh.android.log.VersionInfo
import com.dsh.android.log.CrashRecorder
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogStore
import com.dsh.android.log.LogUploader
import com.dsh.android.log.RescueBoot
import com.dsh.android.log.SafeDateFormat
import com.dsh.android.web.CapabilityChannel
import com.dsh.android.web.RuntimePermissionCapability
import com.dsh.android.web.WebCapability
import com.dsh.android.web.DownloadRequest
import com.dsh.android.web.DownloadStream
import com.dsh.android.web.PageBridge
import com.dsh.android.web.WebHost
import com.dsh.android.web.WebHostCallbacks
import com.dsh.android.web.createWebHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.Locale
import rikka.shizuku.Shizuku
import com.dsh.android.bubble.BubbleA11yService
import com.dsh.android.bubble.BubbleAppearance
import com.dsh.android.bubble.FloatingBubble
import com.dsh.android.bubble.BubbleImePreferences
import com.dsh.android.bubble.BubbleStatusSource
import com.dsh.android.bubble.BubblePanelActions
import com.dsh.android.command.UiProbeBridge
import com.dsh.android.core.DshApplication
import com.dsh.android.engine.EngineManager
import com.dsh.android.engine.EngineService
import com.dsh.android.engine.NativeUploader
import com.dsh.android.privilege.AdbPairingNotifier
import com.dsh.android.privilege.RootAccess
import com.dsh.android.privilege.ShellPrivilege
import com.dsh.android.privilege.ShizukuChannel
import com.dsh.android.R
import com.dsh.android.engine.EngineStatus

/**
 * 主界面：启动内置引擎，然后把 DSH 自带的 Web GUI 加载进 Web 视图。
 *
 * 刻意不自绘聊天 UI —— 直接复用 DSH 官方前端，保证功能与桌面端一致。
 *
 * ⚠️ 承载界面的是 [WebHost]，**不是写死的 WebView**：本 App 出两个版本 ——
 *   `system`（系统 WebView 内核）与 `gecko`（内置 GeckoView 内核）。两者的差异
 *   （JS 执行、原生桥、首帧、控制台、下载、文件选择）全部收在 [WebHost] 后面，
 *   本类只对着接口写一次。选哪个由 flavor 的 `WebHostFactory` 决定。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: EngineManager

    /**
     * 当前 flavor 的 Web 内核宿主（system = 系统 WebView / gecko = 内置 GeckoView）。
     *
     * ⚠️ `lateinit` 而不是 `by lazy`：它必须在 `onCreate` 里、`setupWebView()`
     *   之前建好（那时 `binding` 已就绪），而 `by lazy` 会在第一次访问时才建，
     *   时序上不可控。
     */
    private lateinit var web: WebHost

    /** 资产版本戳：改了引擎内容就改这里，App 会重新解包。 */
    private val assetStamp = "0.2.2-rc.1-1"

    /**
     * 原生上传兜底通道。
     *
     * 页面侧的大 body POST 在个别内核上发不出去（见 [NativeUploader] 类注释），
     * 这里给一条**不经过内核网络栈**的备用通道：`HttpURLConnection` 打
     * `127.0.0.1` —— 与「下载会话导出」用的是同一套，已验证可用。
     *
     * ⚠️ `by lazy` 在这里是安全的：它只在 [ActivityPageBridge] 的方法里被访问，
     *   而那些方法要等页面加载完、JS 调用桥时才会进来，远晚于 `web` 的赋值。
     *   且 `postToPage` 是 lambda，捕获的是 `this`，真正取值时才碰 `web`。
     */
    private val nativeUploader: NativeUploader by lazy {
        NativeUploader { script -> web.evaluateJavascript(script, null) }
    }

    /**
     * 最近一次已应用的页面底色。
     *
     * ⚠️ 由 `ActivityPageBridge`（WebView 的 JS 线程）读写，必须 `@Volatile`；
     * 用 String 而不是 Int 是为了能区分"还没收到过"（空串）和"收到过黑色"。
     */
    @Volatile
    private var appliedBgHex = ""

    // ══ 键盘升起时输入框被盖住 ══════════════════════════════════
    // 现象：点输入框后键盘从底部升起，但输入框要等约 200ms 才跳上去，
    //      中间几帧被键盘压住。
    //
    // 真机实测（Huawei CDY-TN90 / Android 10，各 2~3 轮一致）：
    //   · focus → 窗口变矮 = 180~220ms
    //   · 期间窗口高度 764 → 475，**只有 1 个 resize、无中间帧**
    //   · visualViewport 与 innerHeight 同一时刻变化；没有更早的页面信号
    //   · navigator.virtualKeyboard.boundingRect 恒为 0
    // → 这 200ms 内页面拿不到键盘高度，只能干等窗口变矮。
    //   逐帧 insets 回调要 API 30 的 WindowInsetsAnimation，本机（API 29）没有。
    //
    // 修法：页面在 `focusin`（点击那一刻，必然早于 insets）通知原生，
    //   原生按**上次实测的键盘高度**先把 WebView 变矮；真 insets 到达后校正并
    //   顺手把这次的实测高度记下来。所以是「预判 + 自学习」：
    //   高度因机器/输入法而异，不写死；第一次没有缓存值，仍会闪一下。

    private val imeHandler = Handler(Looper.getMainLooper())
    private val imeLayout by lazy {
        ImeLayoutController(
            scheduler = object : UiTaskScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) { imeHandler.postDelayed(task, delayMs) }
                override fun remove(task: Runnable) { imeHandler.removeCallbacks(task) }
            },
            loadHeight = { BubbleImePreferences.learnedHeight(this) },
            saveHeight = { px ->
                BubbleImePreferences.saveHeight(this, px)
                Log.i(DshApplication.TAG, "键盘高度已学习并保存: ${px}px")
            },
            applyMargin = { px, reason -> setWebViewBottomMargin(px); Log.i(DshApplication.TAG, reason) },
        )
    }

    /** 启动层超时的 handler 与 runnable（见 [armOverlayTimeout]）。 */
    private val overlayHandler = Handler(Looper.getMainLooper())

    private val bootOverlay by lazy {
        BootOverlayPresenter(
            scheduler = object : UiTaskScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) { overlayHandler.postDelayed(task, delayMs) }
                override fun remove(task: Runnable) { overlayHandler.removeCallbacks(task) }
            },
            pageError = { webError },
            render = ::renderOverlay,
            onTimeout = {
                Log.w(DshApplication.TAG, "界面就绪回调超时（WebView 内核 $webViewMajor，页面错误: ${webError.ifEmpty { "无" }}）")
            },
        )
    }
    private val overlayDismissed: Boolean get() = bootOverlay.dismissed

    /** 当前 WebView 内核的主版本号；0 = 读不到（此时不做版本拦截）。 */
    private var webViewMajor = 0

    /**
     * 内核低于**当前实际生效的门槛** —— 前端 bundle 的语法它解析不了。
     *
     * 门槛不是固定的：`EngineManager.ensureWebViewCompat()` 会把官方 bundle 换成
     * 按 `chrome73` 降级编译的版本，换成功之后 83 这种旧内核也能解析。
     * 所以这里按「降级到底生效了没有」动态判定，见 [effectiveMinWebViewMajor]。
     *
     * 为 true 时不再 `loadUrl`（那只会在 12 秒后露出一片白屏），
     * 改成在启动层上直接说明原因（见 `observeEngine` 的 `Ready` 分支）。
     */
    private var webViewTooOld = false

    /**
     * 当前生效的内核门槛。
     *
     * - 降级前端已就位 → [MIN_WEBVIEW_MAJOR_COMPAT]（chrome73，实测 83 也跑得动）
     * - 降级没打上（资产缺失/解包失败）→ [MIN_WEBVIEW_MAJOR]（官方 bundle 的 94）
     *
     * ⚠️ 降级**失败**时必须退到 94：那时跑的就是官方 bundle，语法缺口原样存在。
     *   宁可提示用户升级内核，也不要放进一片白屏。
     */
    private val effectiveMinWebViewMajor: Int
        get() = if (engine.webViewCompatApplied) MIN_WEBVIEW_MAJOR_COMPAT else MIN_WEBVIEW_MAJOR

    /**
     * 页面侧上报的第一条脚本错误（见 `webview-polyfill.js` 的错误上报）。
     *
     * ⚠️ 由 `ActivityPageBridge`（WebView 的 JS 线程）写、主线程读，必须 `@Volatile`。
     * 空串 = 页面没报过错。
     */
    @Volatile
    private var webError = ""

    private val firstFrame by lazy {
        FirstFrameAwaiter(
            scheduler = object : UiTaskScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) { overlayHandler.postDelayed(task, delayMs) }
                override fun remove(task: Runnable) { overlayHandler.removeCallbacks(task) }
            },
            requestFrame = { done -> web.awaitFirstFrame { done() } },
            onPainted = { Log.i(DshApplication.TAG, "Web 内核已提交首帧，收起启动动画") },
            onFallback = {
                Log.w(DshApplication.TAG, "等待 Web 内核首帧超时，仍收起启动动画")
                hideOverlay()
            },
        )
    }

    private val rendererHandler = Handler(Looper.getMainLooper())
    private val webRecovery by lazy {
        WebRecoveryController(
            scheduler = object : UiTaskScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) { rendererHandler.postDelayed(task, delayMs) }
                override fun remove(task: Runnable) { rendererHandler.removeCallbacks(task) }
            },
            now = System::currentTimeMillis,
            reload = ::reloadWebHost,
            hasFirstFrame = { firstFrame.reallyArrived },
            onEvent = ::onWebRecoveryEvent,
        )
    }

    /**
     * 引擎重启后，即使 URL 与上次相同也**必须**重新加载页面。
     *
     * ## 为什么需要它
     *
     * `observeEngine` 的 `Ready` 分支用 `web.currentUrl != state.url` 做去重
     * （避免同一次加载重复触发）。而 `currentUrl` 的语义是「最近一次**发起**的
     * URL」—— 引擎重启后若端口与 token 都没变，两者**相等**，去重就会命中，
     * 页面于是**不会**重新加载。
     *
     * 后果很严重：页面还活着，但它连的是**已经死掉的那个引擎端口**。用户看到
     * 界面完好无损，实际每一条消息都发不出去 —— 这正是 [EngineManager.State.Dead]
     * 要解决的那个「静默失效」，只是换了一层。
     *
     * 所以引擎重启这条路径必须显式声明「这次就是要强制重载」。
     */
    private var forceReloadOnReady = false

    /**
     * 已经为「引擎意外退出」自动重启过几次。
     *
     * ⚠️ **必须有上界**：如果引擎一起来就死（比如某个插件配置让 node 立刻 OOM），
     *   不加限制就变成「启动 → 死 → 再启动」的无尽循环，界面永远在闪启动层，
     *   用户既用不了也看不出原因。超过上界后改为把问题摆出来、让用户决定
     *   （那时「安全模式」才是合适的出口 —— 反复死亡说明配置真的有问题，
     *   与单次死亡不同）。
     */
    private var engineRestarts = 0

    /** 上一次引擎意外退出的时刻（见 [engineRestarts] 的归零规则）。 */
    private var lastEngineDeadAt = 0L

    /**
     * 自动进安全模式是否正在进行（P6 的重入锁）。
     *
     * ⚠️ 必须有：`State.Failed` 分支**每次失败都会进**，而重解包要 15 秒 ——
     *   这期间再来一次 Failed 会叠起第二次重解包，两次 `stop()`/`start()`
     *   交错，最终可能两个都没起来。
     *
     * ⚠️ 与判据里的幂等（`tier` 被记成 SAFE_MODE 后 `== RESCUE` 不再成立）**不是
     *   一回事**：那个防的是「下一轮启动」，这个防的是「同一轮里的并发」。
     *   两者都要有。
     */
    private var autoSafeModeInFlight = false

    /**
     * 「浏览器侧插件加载失败 ⇒ 降级救援」是否正在进行（[maybeRescueFromWebFailure] 的重入锁）。
     *
     * ⚠️ 必须有：前端加载失败后会**重复**打印 `Failed to load plugins`（它自己会重试
     *   拉那批合并 bundle），而每次降级都是一次 `requestRestart` —— 不判重会叠起
     *   多次重启，两次 `stop()`/`start()` 交错，最终可能两个都没起来。
     *
     * ⚠️ 用 `AtomicBoolean` 而不是普通 `var`：`onConsole` 由内核在自己的线程上回调，
     *   与主线程上的降级流程不是同一个线程。
     */
    private val webRescueInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 卡死检测的计时那一半；判据本体在 [WebClientHealth]，状态机在 [WebClientWatchdog]。 */
    private val webClientWatchdog by lazy {
        WebClientWatchdog(
            handler = rendererHandler,
            isNormalTier = { RescueBoot.tier().tier == RescueBoot.Tier.NORMAL },
            logI = { Log.i(DshApplication.TAG, it) },
            logE = { Log.e(DshApplication.TAG, it) },
        ) { delayMs, why ->
            degradeToRescue(
                reason = "前端 ${delayMs / 1000} 秒内没有任何健康证据（$why）",
                eventName = "web.client-silent",
                extra = mapOf("deadlineMs" to delayMs.toString()),
            )
        }
    }

    /** 内核回调可来自 Gecko 线程，恢复策略只在主线程推进。 */
    private fun onRendererGone(didCrash: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            rendererHandler.post { onRendererGone(didCrash) }
            return
        }
        webRecovery.rendererGone(didCrash)
    }

    private fun onWebRecoveryEvent(event: WebRecoveryController.Event) {
        when (event) {
            is WebRecoveryController.Event.RendererGone -> {
                val reason = if (event.crashed) "崩溃" else "被系统回收"
                Log.w(DshApplication.TAG, "界面内核$reason，准备重载（第 ${event.count} 次）")
                CrashRecorder.recordRendererGone(event.crashed, "第 ${event.count} 次重载")
                EventLog.record("renderer.gone", mapOf("crash" to event.crashed.toString(), "n" to event.count.toString()))
                resetFirstFrameAwait()
            }
            is WebRecoveryController.Event.CrashLimitReached -> {
                val reason = if (event.crashed) "崩溃" else "被系统回收"
                Log.e(DshApplication.TAG, "界面内核反复$reason，停止自动重载")
                invalidateOverlayTimeout()
                bootOverlay.reopen()
                showOverlay("界面反复出错",
                    hint = "Web 内核$reason 了 ${event.count} 次，已停止自动恢复。" +
                        "点一下这里收起提示；若界面仍然空白，请重开 App", isError = true)
            }
            is WebRecoveryController.Event.ReloadExhausted -> {
                Log.e(DshApplication.TAG, "界面重载 ${event.attempts} 次仍未恢复为可用状态")
                invalidateOverlayTimeout()
                bootOverlay.reopen()
                if (!degradeToRescue(
                        reason = "界面重载 ${event.attempts} 次仍无首帧（前端起不来）",
                        eventName = "web.frame-giveup", extra = mapOf("attempts" to event.attempts.toString()),
                    )) {
                    showOverlay("界面没能恢复",
                        hint = "已自动重载 ${event.attempts} 次，Web 内核仍未提交首帧。" +
                            "点一下这里收起提示；若界面仍然空白，请重开 App", isError = true)
                }
            }
        }
    }

    /** 页面菜单与 UI 探针共用入口。重试控制器不持有 WebHost 或授权 URL。 */
    private fun requestSafeReload(): Boolean {
        if (isFinishing || isDestroyed || !::web.isInitialized) return false
        if (webRecovery.gaveUp) {
            Toast.makeText(this, "界面此前反复出错，已停止自动恢复。请重开 App。", Toast.LENGTH_LONG).show()
            return false
        }
        if (web.currentUrl.isNullOrEmpty()) return false
        return webRecovery.requestReload()
    }

    /** 重置首帧 → reload → 注册新首帧，顺序不能交换。 */
    private fun reloadWebHost(attempt: Int): Boolean {
        if (isFinishing || isDestroyed || !::web.isInitialized || web.currentUrl.isNullOrEmpty()) return false
        Log.i(DshApplication.TAG, "重载界面（第 $attempt 次尝试）")
        firstFrame.reset(clearActual = true)
        web.resetFirstFrame()
        web.reload()
        awaitWebViewPainted {
            Log.i(DshApplication.TAG, "界面已恢复（第 $attempt 次尝试拿到首帧）")
        }
        return true
    }

    /** 页面比 insets 更早通知输入焦点；实际布局修改仍在主线程。 */
    private fun onImeWillShow() {
        runOnUiThread { imeLayout.willShow(binding.root.height) }
    }

    private fun setWebViewBottomMargin(px: Int) {
        val lp = web.view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != px) {
            lp.bottomMargin = px
            web.view.layoutParams = lp
        }
    }

    private fun observeImeInsets() {
        binding.root.addOnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
            imeLayout.layout(view.width, bottom - top, oldBottom - oldTop)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = EngineManager.get(this)
        imeLayout.load()

        // 失败浮层上的唯一出路。⚠️ Button 是 clickable 的子 View，点击不会冒泡到
        // overlay 自己的监听器（那个只负责"点一下收起浮层"），两者不冲突。
        binding.overlaySafeMode.setOnClickListener { enterSafeMode() }
        // 启动层上的日志入口 —— **任何状态都可用**（见布局里的说明）。
        // ⚠️ 同样要拦冒泡：不拦的话点它会先触发 overlay 自己的「点一下收起浮层」，
        //   于是启动层被收掉、白屏露出来，而日志界面还没打开。
        binding.overlayLogs.setOnClickListener { openLogViewer("overlay") }

        // 导出 / 导入数据 —— 与「查看日志」同一套兜底理由，但更要紧：
        // 引擎挂了、Web GUI 打不开时，设置页进不去，而卸载重装会清掉整个
        // 私有目录。这两个是**唯一**不丢数据的自救路径。
        //
        // ⚠️ 同样不拦冒泡：Dialog 自己会消费触摸，不会落到 overlay 上。
        //   但两者都刻意**不依赖引擎**（纯 Kotlin 文件操作，见 UserDataExporter），
        //   所以引擎起没起来都能用。
        binding.overlayExport.setOnClickListener {
            BackupDialog.showExport(this) { file ->
                // ⚠️ LENGTH_LONG：路径很长，SHORT 会让用户来不及看
                Toast.makeText(this, BackupDialog.exportResultText(this, file), Toast.LENGTH_LONG).show()
            }
        }
        binding.overlayImport.setOnClickListener {
            BackupDialog.showImport(this) { result ->
                Toast.makeText(this, BackupDialog.importResultText(this, result), Toast.LENGTH_LONG).show()
            }
        }

        // ⚠️ 启动层上的「AI 浏览器」按钮**已删除**（0.4.1 正式版）。
        //   它原本只是侧栏入口跑通前的临时测试入口，样式与其它三个 chip 不齐
        //   （文字折成两行），用户明确说「太丑了」。
        //   入口现在只有官方右侧边栏那一项（见 `dsh-android-browser-pane`），
        //   那条路径已验证可用，不需要这个兜底。
        //   注：`openAiBrowser()` 本身**保留** —— 侧栏插件经 `PageBridge` 调它。

        // 降级态常驻横幅（不变量 I4）。**入口必须在 onCreate 就装好**，不能等到
        // 引擎就绪 —— 救援态恰恰可能在引擎起来之前用户就想退出（比如他确信
        // 自己刚卸载了那个坏插件，想直接回正常模式试一次）。
        binding.rescueBannerExit.setOnClickListener { exitDegradedMode() }
        binding.rescueBannerDetail.setOnClickListener { showDegradedDetail() }

        setupWebView()
        detectWebViewVersion()
        setupBackNavigation()
        installBubbleBridge()
        installUiProbeBridge()
        installBrowserBridge()
        // ⚠️ 必须在这里就绪：插件可能**在 App 前台**下发悬浮球外观，而那时
        //   onAppBackground 还没跑过。不提前记 context 的话 bubbleApply 会静默返回
        //   false（真机实测踩到），表现为"插件改了没反应"。
        FloatingBubble.attach(this, BubbleStatusSource { EngineService.currentStatus() })
        // 同上：AI 可能在 App 刚起、还没走到 onResume 时下发系统栏配色，
        // 不提前登记窗口宿主的话这一轮就只能落盘、看不到效果。
        SystemBarAppearance.attach(this)
        observeEngine()
        observeImeInsets()   // 键盘高度自学习 + 预判交接
        // 前台服务要在引擎启动前拉起：它同时是「进程不被回收」和 WakeLock 的持有者，
        // 晚一步的话，引擎刚起就切后台仍可能被系统盯上。
        EngineService.start(this)
        ensureBatteryExempt()
        ensureStorageAccess()
        setupShizukuPermissionListener()
        // 清一次历史截图：`ShotStore.prune` 只在**写新图时**触发，所以升级前
        // 已经攒下来的那批不会自己消失 —— 而它们正是最占地方的（老版本没有
        // 任何上限，有人可能已经攒了几十上百张全屏 PNG）。
        // 放后台线程：`listFiles` + 删几个文件不该占启动路径。
        Thread {
            runCatching {
                com.dsh.android.browser.ShotStore.prune(
                    java.io.File(workspaceDir(), ".browser-shots"),
                )
                com.dsh.android.browser.ShotStore.prune(
                    com.dsh.android.browser.ShotStore.dir(filesDir),
                )
            }.onFailure {
                Log.w(DshApplication.TAG, "启动清理历史截图失败: ${it.message}")
            }
        }.start()
        bootEngine()
    }

    /**
     * 登记 Shizuku 授权结果的监听。
     *
     * ## 为什么必须在这里登记
     *
     * Shizuku 的授权流程是**异步**的：我们调 `requestPermission()` 之后，
     * Shizuku 服务端拉起**它自己的**确认界面，用户在那边点「允许」，
     * 结果才经这个监听器回来。不登记的话用户点完授权，App 侧**永远不知道**，
     * 设置页会一直显示「未授权」—— 而实际已经授权了，用户只能靠重启 App 才发现
     * （典型的「结论与事实不符」）。
     *
     * ⚠️ **结果只用来打日志，不改任何本地状态**：Shizuku 的授权事实存在它自己那边，
     * 我们这边每次都是现读（`Shizuku.checkSelfPermission()`，见
     * [ShizukuChannel.hasPermission]）。再存一份副本就会有两份可能不一致的事实。
     * 设置页靠轮询 [PageBridge.shizukuState] 拿最新结论。
     */
    private fun setupShizukuPermissionListener() {
        runCatching {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Log.i(
                    DshApplication.TAG,
                    "Shizuku 授权结果: requestCode=$requestCode " +
                        "granted=${grantResult == PackageManager.PERMISSION_GRANTED}",
                )
            }
        }.onFailure { t ->
            // 登记失败只是少了条日志，不影响授权本身（Shizuku 那边照常记）
            Log.w(DshApplication.TAG, "登记 Shizuku 授权监听失败: ${t.message}")
        }
    }

    /**
     * 外部存储读写权限的结果。
     *
     * 只记日志。拒绝不阻塞任何功能：`bash` 仍走 app 身份、`shell` 仍走 uid 2000，
     * 只是两者之间传文件要绕道 app 自己的外部目录
     * （`/sdcard/Android/data/com.dsh.android/files/`，那里的属主是 app 自己）。
     * 所以不做二次追问。
     */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        Log.i(DshApplication.TAG, "外部存储权限结果: $granted")
        // ⚠️ 存储权限的结果回来后**接着**申请通知权限，而不是另起一次并行申请。
        //
        //   真机反馈的现象：首次打开 App 只弹了存储权限，通知权限要**退出重开**才弹。
        //   根因有两层：
    //     ① 本工程从未在代码里申请过 `POST_NOTIFICATIONS`（只在清单里声明了，
    //        靠系统在「创建第一个通知渠道」时自动补弹）—— 而那条自动弹框的时机
    //        由系统定，本 App 的 `targetSdk = 28` 在 Android 13+ 上属于「旧应用」
    //        路径，系统只在渠道创建时补问一次，错过就再也不会问；
    //     ② 于是首次启动时那一次自动弹框**被存储权限的框挤掉了**（同一时刻只能
    //        显示一个权限对话框），用户只看到存储权限。
        //   两者叠加的后果正是用户说的：没有通知权限 ⇒ 配对引导横幅根本发不出来
        //   ⇒ 用户想设置特权权限却无处下手（横幅是唯一的交互出口）。
        //
        //   现在显式申请、且**串行**接在存储权限之后，保证首次打开就能拿到结论。
        ensureNotificationAccess()
    }

    /**
     * 申请通知权限（Android 13+）。
     *
     * 只在系统确实需要运行时授权时申请：`POST_NOTIFICATIONS` 是 API 33 才引入的
     * 运行时权限，低版本声明了它也没有这回事（`checkSelfPermission` 恒为
     * `PERMISSION_GRANTED`），所以先用 `SDK_INT` 判一次，避免在旧机上发一次
     * 无意义的请求。
     *
     * ⚠️ **为什么这一个权限值得单独申请**：配对无线调试的整条交互（提示去打开
     *   「使用配对码配对设备」、输入 6 位码、取消）**全部**走通知栏的 RemoteInput，
     *   没有通知权限就等于没有出口。`AdbPairingNotifier.start()` 会在此情况下
     *   直接拒绝开始配对并记日志，设置页据此提示用户 —— 但用户首次打开 App 时
     *   本该一次性把权限拿齐，而不是先撞一次墙。
     */
    private fun ensureNotificationAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13 以下没有这个运行时权限，通知默认可用。
            return
        }
        // ⚠️ **这里刻意不做任何「已经授权就早返回」的判断 —— 那正是原缺陷的成因。**
        //
        //   本 App 的 `targetSdk = 28`（硬约束②，不能升），在 Android 13+ 上属于
        //   「旧应用」。这一档的权限语义有两个**互相矛盾**的读数，而它们都不可靠：
        //
        //   | 读数 | 对 `targetSdk < 33` 的行为 |
        //   |---|---|
        //   | `checkSelfPermission(POST_NOTIFICATIONS)` | 恒为 `GRANTED`（系统把该权限当作隐式已授予）|
        //   | `areNotificationsEnabled()` | 真机实测 appops 为 `Default mode: allow` ⇒ 也返回 `true` |
        //
        //   所以**任何**基于这两个读数的早返回都会让这一句永远走不到，
        //   通知权限也就永远没人申请 —— 上一版用 `checkSelfPermission` 时就是这样，
        //   换成 `areNotificationsEnabled()` 只是换了个同样恒真的判据。
        //
        //   真正的机制是**创建第一个通知渠道时由系统补弹**（旧应用的兼容路径），
        //   那条路由 `AdbPairingNotifier.ensureChannel()` 在启动时负责，与这里互补。
        //
        //   而这里的显式申请**无条件发出**：若系统认为已授予，它立刻回调 granted=true、
        //   不弹任何框（零代价）；若系统确实需要授权，它就给出那个框（正是要的）。
        //   也就是说这一句在两种解释下都正确 —— 比挑一个判据去赌要好。
        Log.i(DshApplication.TAG, "尝试申请通知权限（配对引导横幅依赖它）")
        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure { Log.w(DshApplication.TAG, "发起通知权限申请失败", it) }
    }

    /**
     * 通知权限的结果。只记日志 —— 拒绝了也不阻塞任何功能，只是配对引导会走
     * 设置页那条「请先允许通知」的提示路径（见 `AdbPairingNotifier.notificationsEnabled`）。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(DshApplication.TAG, "通知权限结果: $granted")
    }

    // ══ 麦克风（语音输入的第三层：内核授权回调 → 这里申请 Android 权限）══════
    //
    // ⚠️ **一次只能有一个权限请求在飞**：`registerForActivityResult` 的 launcher
    //   是「启动即等待」的语义，同时发两个会互相顶掉（本项目在存储/通知那两处
    //   踩过，见 storagePermissionLauncher 的注释）。所以这里用「结果队列」而不是
    //   每来一个请求就 launcher.launch 一次。

    /**
     * 正在等待结果的运行时权限请求队列。
     *
     * 内核可能同时为多个能力发起授权（例如 `getUserMedia({audio, video})`），
     * 而系统同一时刻只处理一个权限对话框 —— 并发 `launch` 会丢掉后一个。
     * 所以统一排队，串行发起。
     */
    private val permissionQueue: RuntimePermissionQueue by lazy {
        RuntimePermissionQueue(
            isGranted = { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            launch = {
                Log.i(DshApplication.TAG, "申请运行时权限 $it")
                runtimePermissionLauncher.launch(it)
            },
            onFailure = { permission, failure -> Log.w(DshApplication.TAG, "权限请求/回调失败 $permission", failure) },
        )
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(DshApplication.TAG, "运行时权限结果 ${permissionQueue.activePermission}: $granted")
        permissionQueue.finish(granted)
    }

    /**
     * 内核在问「页面能不能用某项能力」—— 走这条通道（见 [CapabilityChannel]）。
     *
     * 本方法**可能在非主线程**被调用（Gecko 的 delegate 回调在 Gecko 线程），
     * 而 `registerForActivityResult` 的 launcher 必须在主线程 launch。
     */
    private fun requestRuntimePermission(permission: String, onResult: (Boolean) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            overlayHandler.post { requestRuntimePermission(permission, onResult) }
            return
        }
        if (isFinishing || isDestroyed) { onResult(false); return }
        permissionQueue.request(permission, onResult)
    }

    /**
     * 申请外部存储读写。
     *
     * **只为一件事**：让 app 身份读得到 uid 2000（`shell` 工具）写进 `/sdcard` 的文件。
     *
     * uid 2000 在 `/sdcard` 上建的文件属主是 `root:sdcard_rw`、模式 `640` —— 这是
     * sdcardfs 的固定行为，`chmod 666` 改不动（真机实测）。而 app 进程默认的附加组里
     * 没有 `sdcard_rw`（实测引擎进程 `Groups: 3003 9997 20324 50324`），于是 `shell`
     * 截的图 `bash` 读不了，报 Permission denied。
     *
     * ⚠️ 机制**不是**「授权后进程多一个 GID」—— 实测同一个 pid 在授权前后 `Groups`
     * 一个字节都没变。真正变的是 **sdcardfs 按权限把 `/storage/emulated` 绑到不同的
     * runtime 视图**（真机 A/B，同一个进程）：
     *
     *     未授权 -> /storage/emulated ... gid=1015(sdcard_rw), mask=6   ← default 视图
     *     已授权 -> /storage/emulated ... gid=9997(everybody),  mask=7   ← write 视图
     *     对照：launcher（无写权限）-> gid=9997, mask=23
     *
     * 进程自带 9997 组、**不带** 1015 —— 未授权时它落在 gid=1015 的 default 视图里，
     * 组对不上，读不了；授权后切到 gid=9997 的 write 视图，组对上了，再配合
     * `derive_gid`（底层文件的 gid 1015 被派生映射成视图的 gid 9997），就凭自带的
     * 9997 组读得到那个 `root:sdcard_rw 640` 的文件。
     *
     * targetSdk = 28，系统仍走 legacy 存储模型（无分区存储），这两个权限在
     * Android 10 上照旧生效，不需要 MANAGE_EXTERNAL_STORAGE。
     */
    private fun ensureStorageAccess() {
        val needed = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            Log.i(DshApplication.TAG, "外部存储权限已就绪")
            // ⚠️ **这条早返回路径必须也申请通知权限。** 否则「存储权限已就绪」的机器
            //   （例如重装后、或用户先前已给过存储权限）永远走不到那个 launcher 回调，
            //   通知权限就再也没人申请 —— 正是「必须退出重开才弹通知权限」的另一半成因。
            ensureNotificationAccess()
            return
        }
        Log.i(DshApplication.TAG, "申请外部存储权限: ${needed.joinToString()}")
        storagePermissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * 把本 App 加进「忽略电池优化」白名单。
     *
     * 前台服务 + WakeLock 只解决 Android 原生框架的回收，**国产 ROM 另有一套后台冻结**
     * （华为的「应用启动管理」、OPPO 的「耗电保护」），息屏几分钟就把进程冻住，
     * 长任务照样断。这个白名单是唯一由系统认可的免冻结标记，且必须由用户手动确认。
     *
     * 只在**尚未豁免**时弹一次，已豁免则静默返回，不反复打扰。
     */
    private fun ensureBatteryExempt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(DshApplication.TAG, "已在电池优化白名单中")
            return
        }
        // 没有该 Activity 的 ROM（部分定制系统）会抛 ActivityNotFoundException
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            Log.i(DshApplication.TAG, "已请求加入电池优化白名单")
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "无法请求电池优化白名单: ${t.message}")
        }
    }

    /**
     * 读当前 WebView 内核版本，判断它跑不跑得动前端 bundle。
     *
     * **为什么需要这一步**：官方前端 bundle 用了 Chromium 94 才有的语法
     * （class static block），旧内核在**解析阶段**就失败 —— 不是缺某个 API，
     * 而是整份 chunk 一行都不执行。这种失败在页面上完全无声：HTML 和 CSS 都正常，
     * `#root` 却永远是空的，用户只看到白屏，连线索都没有。
     *
     * 这里只负责「读版本 + 记日志 + 置标志」，怎么呈现交给 `observeEngine`。
     *
     * ⚠️ **不能在这里定 `webViewTooOld`**：此刻引擎还没启动，降级 bundle 也就
     *   还没落盘，[effectiveMinWebViewMajor] 会误报成 94。判定挪到
     *   `observeEngine` 的 `Ready` 分支（引擎就绪 = 降级已经打过了）。
     *
     * ⚠️ 读不到版本时**不做拦截**（`webViewMajor = 0`）：宁可维持原行为，
     *   也不能因为一次读取失败就把本来能用的设备挡在门外。
     */
    private fun detectWebViewVersion() {
        // ⚠️ 在 gecko flavor 里读到的**不是**设备上装的内核，而是打进 APK 的
        //   GeckoView 版本（编译期确定）。所以那里永远不可能「内核过旧」——
        //   版本拦截分支不会触发，这正是内置内核要解决的问题本身。
        webViewMajor = web.engineMajor
        Log.i(
            DshApplication.TAG,
            "Web 内核: ${web.engineLabel}（主版本 $webViewMajor；" +
                "官方 bundle 门槛 $MIN_WEBVIEW_MAJOR，降级后 $MIN_WEBVIEW_MAJOR_COMPAT）",
        )
    }

    /**
     * 建 Web 内核宿主并装进布局。
     *
     * 内核相关的全部装配（JS 开关、桥、外链策略、文件选择、下载、控制台）
     * 都在 [WebHost] 的实现里；这里只负责把它挂到容器上，并把**与内核无关**的
     * 宿主行为（DOM 就绪上报、外链交给系统、控制台转发到同一个 TAG）接起来。
     */
    private fun setupWebView() {
        web = createWebHost(
            this,
            WebHostCallbacks(
                bridge = NativePageBridgeDispatcher(
                    activity = ActivityPageBridge(),
                    bubble = BubblePageBridge(applicationContext),
                    privilege = PrivilegePageBridge(applicationContext) { engine.rootToolMounted },
                ),
                onPageFinished = { url ->
                    Log.i(DshApplication.TAG, "Web 界面加载完成: $url")
                    // ⚠️ 记下「本次启动成功出过界面」。下一次启动靠它区分
                    //   「启动即崩」与「用了一阵才离开」—— 后者（返回键退到后台、
                    //   被 LMK 回收、断电）走不到 onDestroy，不记的话会被反复
                    //   算成「非正常结束」，真机实测累积到 18 次并让诊断包给出
                    //   「疑似启动即崩的死循环」这个错误结论。见 [BootState.markUiLoaded]。
                    BootState.markUiLoaded()
                    // ⚠️ **必须在这里补推让位变量**：applyFullscreen 有状态门（配置没变
                    //   就不再写 DOM），而变量是写在页面 documentElement 的**行内样式**上，
                    //   文档一重载就没了 —— 只靠配置变化那一次推，重启 App 后
                    //   （配置早已是 fullscreen:true）页面就永远拿不到这个值，
                    //   症状是"全面屏开着但顶部被状态栏压住"。
                    pushSystemBarInset(SystemBarAppearance.load(this).fullscreen)
                    // DOM 到了，但移动端 UI 插件仍在激活（实测还有约 1.7s）——
                    // 这段宿主本来完全感知不到，上报给启动层，动画才不会提前收尾。
                    engine.notifyPageLoaded()
                },
                onConsole = { line ->
                    // ⚠️ 网页 console 走**独立的 `web.log`**，理由同引擎输出：
                    //   前端会打大量逐条渲染/请求日志，混进 1MB 的 `app.log`
                    //   会把原生侧关键日志冲掉。
                    LogStore.web(line)
                    // ⚠️ **logcat 必须绕开 `DshLog` 的 sink**（2026-10-14 自检发现，
                    //   同族第 23 例）。原来这里是 `Log.i(...)`，本文件顶部是
                    //   `import com.dsh.android.log.DshLog as Log`，而 `DshLog.i`
                    //   除转发 logcat 外还会**再落一次盘** —— 上面 `LogStore.web(line)`
                    //   已经落过了，于是同一条网页 console 在 `app.log`/内存缓冲里
                    //   各存两份（tag 分别是 `DSH` 与 `web`）。
                    //   与 `EngineManager` 里引擎输出的那处是同一个缺陷，一并修掉：
                    //   真机 72/74 个包的 `app.log` 里混着这类重复行。
                    //   落盘已由 `LogStore.web` 负责，这里只要 logcat，所以用全限定名。
                    android.util.Log.i(DshApplication.TAG, "[web] $line")
                    // ⚠️ **第四种故障形态：引擎健康，但浏览器侧有一个插件的
                    //   client 半加载不了。** 真机现场（OPPO PHY110）：用户在写
                    //   插件的过程中退出 App，留下的半成品 client.js 里有一个多余
                    //   的 `)`，于是**整批合并 bundle 解析失败** —— 一个坏段让
                    //   `Failed to load plugins` 整页顶掉 GUI，用户进不去对话。
                    //
                    // ⚠️ 引擎那条路径（[RescueBoot.parseGatewayFailure]）**盖不到
                    //   这一种**：它的判据是引擎 stdout 里的
                    //   `typert contributor(s) failed to register`，而这次的引擎
                    //   一切正常（`dsh-gateway-ui: 已激活`、供应商也注册上了），
                    //   坏的是发给浏览器的 JS。所以判据必须落在浏览器 console 上。
                    //
                    // ⚠️ 判据用**前端自己打印的那句话**，与其它三处同一原则：
                    //   `Failed to load plugins` 只在 client 半加载失败时出现。
                    //   `import failed` / `did not activate` 单独都不够 ——
                    //   `did not activate` 在正常告警里也会出现。
                    maybeRescueFromWebFailure(line)
                    // ⚠️ 兜底错误码的**真实原因**必须让用户在手机上看见。
                    //   见 [surfaceWebErrorReason] 的注释。
                    surfaceWebErrorReason(line)
                },
                onExternalUrl = { url ->
                    // ── http/https 一律用**内置**浏览器打开 ────────────────
                    // 用户明确要求：点 App 内任何链接都别跳出去，用我们自己的
                    // 浏览器。原生 WebView 能过 `X-Frame-Options` / CSP，而
                    // 官方侧栏那个 iframe 方案打不开 GitHub、Google。
                    //
                    // ⚠️ **只接管 http/https**：`mailto:` / `tel:` / `market:`
                    //    这类协议塞进 WebView 只会显示「无法加载」，必须留给
                    //    系统去拉起对应的 App。
                    val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
                    if (scheme == "http" || scheme == "https") {
                        Log.i(DshApplication.TAG, "链接转内置浏览器: $url")
                        openAiBrowser(url)
                    } else {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure { Log.w(DshApplication.TAG, "无法打开外部链接: $url", it) }
                    }
                },
                fileChooser = { multiple, onResult -> launchFileChooser(multiple, onResult) },
                onDownload = { req -> handleDownload(req) },
                onDownloadStream = { stream -> handleDownloadStream(stream) },
                // 内核侧的功能降级/中止提示。必须弹出来 —— 手机上看不到日志。
                onNotice = { msg ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                },
                // ⚠️ 必须接：不接的话 WebView 的默认实现会**直接杀掉本 App 进程**
                //（见 WebHostCallbacks.onRendererGone 的说明）。
                onRendererGone = { didCrash -> onRendererGone(didCrash) },
                // ⚠️ 必须接：不接的话两个内核的默认实现都是**拒绝麦克风**，
                //   页面的语音输入永远报「权限被拒绝」（见 MicrophoneChannel）。
                capability = RuntimePermissionCapability(this) { perm, cb -> requestRuntimePermission(perm, cb) },
                // ── window.open 的渲染目标 ────────────────────────────────
                // 官方登录用 `window.open("about:blank")` 预开窗口，再往里
                // `location.replace(authorizeUrl)`。内核要一个真实可见的
                // WebView —— 交给内置浏览器新开一个标签。
                //
                // ⚠️ 必须 `runOnUiThread` + 同步等结果：内核在
                //    `onCreateWindow` 返回 true 的**那一刻**就会读
                //    `transport.webView`，异步赋值必然拿到 null ⇒ 白窗口。
                onCreatePopup = {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        ensureAiBrowser()?.let { b ->
                            /*
                             * ## ⚠️⚠️ AI 操作期间**不许抢屏**
                             *
                             * 用户反馈（2026-10-28）：「我刚让他打开豆包网页版，
                             * 然后他执行 open 命令的时候，侧边栏会自动弹出；然后
                             * 我让他点击某个按键执行 click 指令的时候，它又弹出来」。
                             *
                             * 这就是那个"又弹出来"的来源：豆包这类站点一打开就会
                             * `window.open`（登录框、引导页、统计埋点），而这里原来
                             * 无条件 `requestBrowserPane()` + `bringToFront()` ——
                             * 于是 AI 每 open 一次、每点一个会开新窗口的按钮，
                             * 侧栏就被顶到面前一次。
                             *
                             * 现在按 [aiBrowserQuiet] 分流：
                             *   · AI 正在用（静默） ⇒ 只建窗口、不 reveal、不抬 z 序，
                             *     弹窗页仍然可以加载和操作，用户完全无感；
                             *   · 用户主动打开浏览器（aiBrowserQuiet == false） ⇒
                             *     保持原行为，登录授权页该切过来还是切过来。
                             */
                            if (aiBrowserQuiet) {
                                /*
                                 * ⚠️ **这里也不能设 VISIBLE**（2026-10-29 再修）。
                                 *
                                 * 上一版把 `requestBrowserPane()` 拿掉了，但留下了
                                 * `b.visibility = View.VISIBLE` —— 而本视图是
                                 * `MATCH_PARENT` 铺满根布局的，可见却没被
                                 * `applyEmbedRect` 摆进面板时，它就是一张**全屏
                                 * 网页**盖在对话上面。用户说的"霸占屏幕"，
                                 * 一半来自侧栏弹出，另一半就来自这里。
                                 *
                                 * `createPopupTarget()` 只负责建一个新的
                                 * WebView 标签并交给内核（`onCreateWindow`
                                 * 要求返回一个真实 WebView），**不依赖父视图可见**。
                                 */
                                b.createPopupTarget()
                            } else {
                                // 登录弹窗走同一条路：先把侧栏切过来，让授权页落在
                                // 面板里，而不是整屏糊上来。
                                requestBrowserPane()
                                b.visibility = View.VISIBLE
                                syncAiBrowserVisible()
                                b.bringToFront()
                                notifyBrowserPane(visible = true)
                                b.createPopupTarget()
                            }
                        }
                    } else {
                        // onCreateWindow 理论上就在主线程，真走到这里说明内核
                        // 换了线程模型 —— 记一笔，别静默失败。
                        Log.w(DshApplication.TAG, "onCreatePopup 不在主线程，拒绝建窗口")
                        null
                    }
                },
            ),
        )
        binding.webContainer.addView(
            web.view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // AI 浏览器
    // ══════════════════════════════════════════════════════════════════

    /**
     * AI 浏览器实例。**懒创建** —— 大多数会话用不到它，白占 100~200 MB。
     *
     * ⚠️ 与官方界面的 WebView 是**两个独立实例**：各自有独立的浏览历史、
     *   独立的页面状态。它们共享同一个 WebView 数据目录（Android 默认行为），
     *   所以 Cookie 是共享的 —— 目前可接受，见 [AiBrowserView] 的类注释。
     */
    private var aiBrowser: com.dsh.android.browser.AiBrowserView? = null

    // ══════════════════════════════════════════════════════════════════
    // AI 操作状态胶囊（对话页）
    // ══════════════════════════════════════════════════════════════════

    private var activityPill: BrowserActivityPill? = null

    private fun showActivityPill(text: String) {
        if (isBrowserPaneOpen()) return
        val message = text.trim()
        if (message.isEmpty()) { hideActivityPillSoon(); return }
        runOnUiThread { ensureActivityPill()?.showAction(message) }
    }

    private fun hideActivityPillSoon() {
        runOnUiThread { activityPill?.hideSoon() }
    }

    /** Activity 只决定胶囊在根布局的挂载位置。 */
    private fun ensureActivityPill(): BrowserActivityPill? {
        activityPill?.let { return it }
        if (isFinishing || isDestroyed) return null
        val pill = BrowserActivityPill(this)
        binding.root.addView(pill, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = statusBarHeightPx() + (10 * resources.displayMetrics.density).toInt()
        })
        activityPill = pill
        return pill
    }

    /**
     * 这台浏览器当前是不是**由 AI 驱动**的。
     *
     * `true` ⇒ AI 正在用它（经 BrowserBridge 调用工具）：
     *   · [openAiBrowserQuietly] 走内嵌面板，浏览器只出现在侧栏标签页里；
     *   · [onCreatePopup]（网页里的 `window.open`）**不**抢焦点 ——
     *     豆包这类站点一打开就弹登录框，若每次都把面板顶到前面，用户会被
     *     反复打断（真实反馈）。弹窗页照样能加载和操作，只是不抢屏；
     *   · 对话页会补一条状态胶囊（面板没展开时）。
     *
     * `false` ⇒ 用户自己打开的：登录授权页该切过来就切过来，一切照旧。
     *
     * 用户从侧栏点开「AI 浏览器」tab 时由 [openAiBrowser] 复位成 `false`。
     */
    private val browserPane = BrowserPaneCoordinator()
    private val aiBrowserQuiet: Boolean get() = browserPane.state.mode == BrowserPaneCoordinator.Mode.TOOL



    /** 状态胶囊的延时收起等用途的主线程 Handler。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 拿到（必要时创建）AI 浏览器实例。**必须在主线程调用。**
     *
     * 拆出来是为了给 [installBrowserBridge] 用：模型说「打开某个网址」时
     * 浏览器可能还不存在，需要按需建出来。
     */
    private fun ensureAiBrowser(): com.dsh.android.browser.AiBrowserView? {
        aiBrowser?.let { return it }
        if (isFinishing || isDestroyed) return null
        val v = com.dsh.android.browser.AiBrowserView(this)
        v.visibility = View.GONE
        // ⚠️ 挂在**根布局**上，不是 `webContainer`：启动层（overlay）是
        //    webContainer 的兄弟节点且盖在它上面，挂进 webContainer 的视图
        //    会被启动层挡住。根布局的最上层子视图永远在最前。
        // ⚠️ 显式用 FrameLayout.LayoutParams：后面 `applyEmbedRect` 要改
        //   leftMargin/topMargin 把它摆到侧栏面板上，父容器是 FrameLayout，
        //   margin 才会被尊重。
        binding.root.addView(
            v,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // ✕ 只是隐藏，页面状态与登录态都留在内存里。
        // ✕ / 关到最后一个标签 = 用户主动关掉浏览器 ⇒ 连侧栏 tab 一起关。
        // （返回键那条路径仍然只隐藏，见 onBackPressed。）
        v.onClose = { closeAiBrowser(closeTab = true) }
        // ⌄ = 临时收起：只隐藏，**不动侧栏 tab**，随时能原样调回来。
        v.onCollapse = { closeAiBrowser(closeTab = false) }
        // ✎ = 截图定格后进标注画布。
        v.onAnnotate = { showAnnotate() }
        /*
         * 请求把浏览器收进侧栏面板 —— **只有用户主动路径会走到**。
         *
         * 用途：用户点工具栏截图按钮时，他想截的是"面板里那个画面"，
         * 所以先请前端把面板展开、`applyEmbedRect` 摆好，再截才一致。
         *
         * ⚠️ AI 调 screenshot **不走这里**：`AiBrowserView.silentForTool`
         *   为真时 `awaitEmbedded` 直接跳过 `onRequestEmbed`（见那边的注释）。
         *   否则 AI 每截一次图就把侧栏弹出来一次，正是用户 2026-10-29 报障。
         */
        v.onRequestEmbed = { requestBrowserPane() }
        // 截图落在 workspace 下的隐藏目录，模型可以直接 read 那张图。
        v.shotDir = java.io.File(workspaceDir(), ".browser-shots")
        // 文件上传走宿主已有的系统选择器（三条收尾路径由 launchFileChooser 保证）
        v.onPickFile = { cb ->
            launchFileChooser(multiple = false) { uris -> cb.onReceiveValue(uris) }
        }
        // 顶部安全区：宿主开了 FLAG_LAYOUT_NO_LIMITS，内容铺到状态栏底下。
        // 不预留的话工具栏上的后退键和 ✕ 会被时间/电量压住 —— 看得见但点不准，
        // 用户的感受就是「进了浏览器出不来」。
        v.setSafeTop(if (fullscreenApplied) statusBarHeightPx() else 0)
        aiBrowser = v
        Log.i(DshApplication.TAG, "AI 浏览器实例已创建")
        return v
    }

    /** 打开 AI 浏览器（覆盖在官方界面之上）。 */
    fun openAiBrowser(url: String? = null) {
        // 用户主动打开 ⇒ 退出静默，侧栏该切过来就切（与 AI 后台用区分开）。
        browserPane.openByUser()
        val view = ensureAiBrowser() ?: return
        /*
         * 用户主动打开 ⇒ 复位「AI 静默」标志。
         *
         * 不复位的话它会一直保持 true（AI 上一次工具调用置的），
         * 导致用户自己点工具栏截图按钮时也不再请求展开面板 ——
         * 截出来的是全屏视口，和他在面板里看到的不一致。
         */
        view.silentForTool = false
        view.visibility = View.VISIBLE
        syncAiBrowserVisible()
        view.bringToFront()
        if (url != null) view.loadUrl(url)
        else if (view.currentUrl().isEmpty()) view.loadUrl(com.dsh.android.browser.AiBrowserView.HOME_URL)
        // ⚠️ **必须请前端把侧栏切过来**，不能只把原生 WebView 显示出来。
        //
        // 侧栏这时可能正停在对话页（用户点了对话里的链接），或者已经开过
        // 浏览器但被切走了。只 `visibility = VISIBLE` 的话，WebView 确实
        // 盖在最上层，可侧栏的标签 UI 还在对话页 —— 用户看到的是「界面没
        // 任何变化」，原话：「点击链接它不会自动打开侧边栏，导致用户以为
        // 没有效果」。`onCreatePopup`（登录弹窗）一直有这一步，普通链接
        // 路径反而漏了。
        requestBrowserPane()
        notifyBrowserPane(visible = true)
        Log.i(DshApplication.TAG, "AI 浏览器已打开: ${view.currentUrl()}")
    }

    /**
     * AI 自己用浏览器时走这条路 —— **不抢用户的屏幕。**
     *
     * ## 为什么不复用 [openAiBrowser]
     *
     * `openAiBrowser()` 里有两句是给「用户主动打开浏览器」用的：
     *
     *     requestBrowserPane()          // 发 `dsh:browser-reveal` 让侧栏切过来
     *     notifyBrowserPane(visible=true)
     *
     * 用户点链接、点侧栏 tab 时这是对的 —— 他要看，就得把面板切出来。
     *
     * 但 AI 每调一次 browser 工具就会走到这里（见 [installBrowserBridge] 的
     * provider）。也就是说 **AI 每动一下浏览器，侧栏就整个弹出来霸占屏幕**。
     * 用户的原话：「每次 AI 在调用浏览器工具的时候，侧边栏都会自动弹出霸占
     * 屏幕，导致用户没有了自己的空间」。
     *
     * ## 现在的语义
     *
     * · WebView 照常建出来、照常加载、照常可被 AI 操作；
     * · 视图设为可见（不可见会让 `PixelCopy` 截到错误内容）；
     * · 发 reveal 事件把侧栏切到「AI 浏览器」标签 —— 浏览器**只出现在
     *   内嵌面板里**，绝不铺满整屏。
     *
     * ## ⚠️ 现在的形态：内嵌侧栏面板，不再有「静默不可见」
     *
     * 历史沿革（别被方法名误导）：这个方法最初是「AI 用浏览器时不弹侧栏」，
     * 试过缩成右侧小窗、试过 `INVISIBLE` 后台跑，都被用户否掉了 ——
     * 前者挡屏幕，后者的截图拿到的是错误内容（`PixelCopy` 抓不到不可见视图）。
     *
     * 用户最终明确要求：「只要浏览器出现，就必须在我们这个内嵌的标签页里」。
     * 所以现在这里做的是：**可见 + 切到内嵌面板**，浏览器只存在于侧栏标签页。
     * 方法名为了少改调用点而保留，语义已经是「AI 主动启用浏览器」。
     */
    private fun openAiBrowserQuietly() {
        browserPane.prepareForTool()
        val view = ensureAiBrowser() ?: return

        /*
         * ## 浏览器在 AI 使用时：**后台准备，绝不抢屏幕**
         *
         * 用户明确要求（2026-10-28）：
         * 「反正我的理想状态就是，只要浏览器出现，就必须在我们这个内嵌的
         *   标签页里」；
         * 又在 2026-10-29 报障：
         * 「AI 每次它调用浏览器工具都会强制打开侧边栏的浏览器」。
         *
         * 两条要求合起来的正确形态就是这个方法现在做的 —— **什么都不显示**：
         *
         *   1. 视图已经建出来、WebView 就绪。`GONE` 状态下 WebView
         *      **照常加载页面、照常执行 JS**（`GONE` 只影响布局与合成，
         *      不影响 WebView 自身的渲染流水线），AI 的 click / type /
         *      read 全部正常；
         *   2. **不设 `VISIBLE`**。这个视图是 `MATCH_PARENT` 铺满根布局的，
         *      一旦可见却没被 `applyEmbedRect` 摆进面板，它就是一张全屏
         *      网页盖在对话上面 —— 正是用户说的「霸占屏幕」；
         *   3. **不调 `requestBrowserPane()`**。它会派发 `dsh:browser-reveal`，
         *      前端 `dsh-android-browser-pane` 的 `onReveal` 收到就
         *      `sidebarRight.openTab(...)` 把侧栏弹出来并切到浏览器标签。
         *      上一版就是漏了这一条，导致旧 bug 回归。
         *
         * ## 那用户什么时候能看到浏览器
         *
         * 两种都是**他主动**的动作：
         *   · 点侧栏里的「AI 浏览器」tab；
         *   · 点对话/登录界面里的链接。
         *
         * 两条都走 [openAiBrowser]，那里才 reveal + `bringToFront`。
         * 面板一挂载就上报矩形，`applyEmbedRect` 把浏览器摆进面板内容区 ——
         * 它**只在那里出现**，不会铺满整屏。
         *
         * ## 截图怎么办
         *
         * `captureVisible()` 里有"借一帧"通道：临时 `VISIBLE` → 等一帧让系统
         * 真合成一次 → `PixelCopy` → 立刻恢复。这是项目里既有且已修过真机
         * bug（v78「截到的是对话界面」）的路径，AI 在后台也能截到正确画面。
         */
        syncAiBrowserVisible()
        if (view.currentUrl().isEmpty()) {
            view.loadUrl(com.dsh.android.browser.AiBrowserView.HOME_URL)
        }
        Log.i(DshApplication.TAG, "AI 浏览器已就绪（后台，不占屏）: ${view.currentUrl()}")
    }

    /** 当前 workspace 目录（截图等落盘用）。 */
    private fun workspaceDir(): java.io.File =
        java.io.File(filesDir, "workspace").apply { if (!exists()) mkdirs() }

    /**
     * 关闭 AI 浏览器（只隐藏，保留页面状态与登录态，下次打开还是原样）。
     *
     * ⚠️ **必须反向通知前端。** 侧栏面板是靠「自己量出矩形 → 上报 → 原生把
     *   WebView 摆过去」显示的，它并不知道原生这边被关了。不通知的话，用户点
     *   ✕ 之后面板会留在一片空白里（浏览器没了，占位区域还在），而且再点也没
     *   反应 —— 他得先关掉整个 tab 再重新打开。
     */
    fun closeAiBrowser(closeTab: Boolean = false) {
        aiBrowser?.visibility = View.GONE
        syncAiBrowserVisible()
        notifyBrowserPane(visible = false, userClosed = closeTab)
        Log.i(
            DshApplication.TAG,
            if (closeTab) "AI 浏览器已关闭（同时关闭侧栏 tab）" else "AI 浏览器已隐藏",
        )
    }

    /**
     * 把浏览器可见性告诉侧栏面板（原生 → 前端）。
     *
     * 走 `window` 上的自定义事件，而不是让面板轮询：面板在 `useEffect` 里挂
     * 一个监听就行，原生这边只有一个 `evaluateJavascript`。
     *
     * ⚠️ 事件名 `dsh:browser-state` 与插件 `lib/client.js` 里的常量必须一致。
     */
    /**
     * 侧栏的「AI 浏览器」面板此刻是否**真的在用户眼前**。
     *
     * 用来决定「对话页要不要补一条状态胶囊」：面板开着时，浏览器自己上方就有
     * 实时胶囊，宿主再飘一个既重复又挡内容。
     *
     * 判据取 [isAiBrowserVisible] 与视图可见性的交集 —— 单看哪一个都不准：
     *   · 只有 `aiBrowserVisible` 为真、但视图是 INVISIBLE（面板被收起）⇒
     *     用户其实看不到，这时**应该**显示宿主胶囊；
     *   · 只有视图可见、但 `aiBrowserVisible` 为假（尚未同步）⇒ 同样当作不可见，
     *     宁可多显示一条反馈，也不要让用户以为 AI 卡住了。
     */
    private fun isBrowserPaneOpen(): Boolean =
        browserPane.state.visible && aiBrowser?.visibility == View.VISIBLE && aiBrowserQuiet

    private fun notifyBrowserPane(visible: Boolean, userClosed: Boolean = false) {
        if (!::web.isInitialized) return
        val script = "window.dispatchEvent(new CustomEvent('dsh:browser-state'," +
            "{detail:{visible:$visible,userClosed:$userClosed}}))"
        runCatching { web.evaluateJavascript(script, null) }
            .onFailure { Log.w(DshApplication.TAG, "通知侧栏面板失败: ${it.message}") }
    }

    /** 正在显示的标注画布。 */
    private var annotateView: com.dsh.android.browser.AnnotateView? = null

    /**
     * 弹出标注画布：把刚截的图定格，让用户圈画 + 提问。
     *
     * ## 层级
     *
     * 画布盖在**整个根布局**上，不是盖在浏览器上。因为用户圈完要打字，
     * 键盘弹起会挤压可用高度 —— 盖在浏览器里会被浏览器自己的工具栏和
     * 标签条挤成一条缝。
     */
    private fun showAnnotate() {
        val bmp = aiBrowser?.takeAnnotateBitmap()
        if (bmp == null) {
            Log.w(DshApplication.TAG, "标注：没有可用的截图")
            return
        }
        annotateView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }

        val canvas = browserPane.beginAnnotation() ?: return
        val v = com.dsh.android.browser.AnnotateView(this)
        v.setBase(bmp)
        v.onCancel = { if (browserPane.cancelAnnotation(canvas)) dismissAnnotate() }
        v.onSend = send@{ png, question ->
            if (!browserPane.submitAnnotation(canvas, png, question)) return@send
            dismissAnnotate()
            // 告诉前端「有东西可取」。前端收到后调 aiAnnotateResult() 拿数据，
            // 再填进当前会话的输入框并发送。
            notifyAnnotateReady()
        }
        binding.root.addView(
            v,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        annotateView = v
        Log.i(DshApplication.TAG, "标注画布已打开")
    }

    /** 关掉标注画布并释放底图。 */
    private fun dismissAnnotate() {
        val v = annotateView ?: return
        annotateView = null
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        v.release()
        Log.i(DshApplication.TAG, "标注画布已关闭")
    }

    /** 通知前端：标注结果可取。 */
    private fun notifyAnnotateReady() {
        if (!::web.isInitialized) return
        val script = "window.dispatchEvent(new CustomEvent('dsh:browser-annotate'))"
        runCatching { web.evaluateJavascript(script, null) }
            .onFailure { Log.w(DshApplication.TAG, "通知标注结果失败: ${it.message}") }
    }

    /**
     * 请求侧栏把面板切到「AI 浏览器」（原生 → 前端）。
     *
     * ## 为什么需要这个
     *
     * 浏览器在**侧栏没打开**时被点链接拉起，只能以全屏覆盖层出现。那时屏幕上
     * 没有任何侧栏 UI —— 用户看不到面板边界，也找不到「把这个东西收起来」的
     * 入口，只能按 ✕ 彻底关掉，而关掉就丢页面。用户的原话：
     * 「点击登录或者对话界面的任何一个链接，它都是全屏弹出，顶部没有带我们
     *  侧边栏的标签页 UI，就会导致我无法临时通过侧边栏关闭按钮来临时移除」。
     *
     * 所以打开浏览器**之前**先请前端把侧栏切过去。面板一旦挂载就会自己上报
     * 矩形，浏览器随之被摆进面板里，UI 也就齐了。
     *
     * ⚠️ **异步、尽力而为**：前端可能还没加载完、侧栏可能被用户锁住。打不开
     *   也绝不能影响浏览器本身 —— 原生那边已经可见了，最差就是全屏形态。
     *   所以这里不等待、不回读结果。
     */
    private fun requestBrowserPane() {
        if (!::web.isInitialized) return
        val script = "window.dispatchEvent(new CustomEvent('dsh:browser-reveal'))"
        runCatching { web.evaluateJavascript(script, null) }
            .onFailure { Log.w(DshApplication.TAG, "请求展开侧栏失败: ${it.message}") }
    }

    /** 只在主线程读取 View，跨线程查询读取协调器的不可变快照。 */
    private fun syncAiBrowserVisible() {
        browserPane.syncVisibility(aiBrowser?.visibility == View.VISIBLE)
    }

    fun isAiBrowserVisible(): Boolean = browserPane.state.visible

    /**
     * 拉起系统的文件选择器，把结果交回内核。
     *
     * ⚠️ 这里是**内核无关**的那一半（两个 flavor 共用）：构造 Intent、处理
     *   「没有应用能响应」、把结果翻译成 `Array<Uri>`。内核相关的那一半
     *   （WebView 的 `onShowFileChooser` / GeckoView 的 `onFilePrompt`）在各自
     *   的 [WebHost] 实现里。
     *
     * ⚠️ **三条路径都必须回调**（成功 / 取消 / 拉起失败），漏掉任何一条都会让
     *   页面永久卡在「等待选择」状态。取消必须回 `null` 而不是空数组 ——
     *   空数组会被页面解读成「选了 0 个文件」并触发一次空提交。
     */
    private fun launchFileChooser(multiple: Boolean, onResult: (Array<Uri>?) -> Unit) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        fileChooserResultSink = onResult
        try {
            fileChooserLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(DshApplication.TAG, "没有可用的文件选择器", e)
            fileChooserResultSink = null
            onResult(null)
        }
    }

    /**
     * 下载引擎导出的文件到本机。
     *
     * ⚠️ **不能用系统 `DownloadManager`**：会话导出接口用 **HttpOnly cookie** 鉴权
     * （`dsh-auth-*`，真机实测带 cookie 200 / 不带 401），而 `DownloadManager` 跑在
     * 系统进程里，既**不共享** WebView 的 cookie jar，也**不允许**应用自己设
     * `Cookie` 请求头（API 28 起系统会剥掉）。两条路都堵死，结果就是下载项静默失败。
     *
     * 所以这里在**本进程内**取字节：`CookieManager` 拿得到 HttpOnly cookie
     * （它本来就是给宿主用的），自家的 `HttpURLConnection` 也没有那个 header 限制。
     */
    /**
     * 把**内核已经代发**的响应体落盘（gecko 版专用，见 [DownloadStream]）。
     *
     * ⚠️ 与 [handleDownload] 的关键差别是**这里不需要、也拿不到 cookie**：
     *   请求已经由 Gecko 发完了，响应体就是授权后的结果。这正是 gecko 版
     *   存在的理由 —— GeckoView 没有任何读取 cookie 的 API，宿主代发这条路
     *   在那里走不通。
     *
     * ⚠️ 读流必须在 IO 线程：`WebResponse.body` 是内核那边挂过来的管道，
     *   在主线程上读会把 UI 卡住（大文件尤其明显）。
     */
    private val sessionDownloads = SessionDownloadService()

    private fun handleDownloadStream(stream: DownloadStream) {
        // ⚠️ 必须清洗：文件名来自页面的 anchor.download，可含 ../ → 路径穿越
        val filename = SessionDownloadService.sanitizeName(stream.filename)
        val target = downloadTargetDir()
        Toast.makeText(this, "开始下载 $filename", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = runCatching {
                sessionDownloads.save(stream.body, target, filename)
            }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { file ->
                        Log.i(DshApplication.TAG, "Session 日志已保存: ${file.absolutePath} (${file.length()} 字节)")
                        Toast.makeText(this@MainActivity, "已保存到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                    .onFailure { e ->
                        Log.w(DshApplication.TAG, "下载失败: ${stream.url}", e)
                        Toast.makeText(this@MainActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun handleDownload(req: DownloadRequest) {
        val url = req.url
        // 只接管本地引擎的下载；外链仍交给系统浏览器（与 shouldOverrideUrlLoading 一致）。
        if (!url.startsWith("http://127.0.0.1")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Log.w(DshApplication.TAG, "无法打开外部下载链接: $url", it) }
            return
        }

        // 优先用 Content-Disposition 里的文件名（引擎给的是 dsh-session-<id>.zip）。
        // ⚠️ 必须清洗：Content-Disposition 是服务端可控的，可含 ../ → 路径穿越
        val filename = SessionDownloadService.sanitizeName(
            URLUtil.guessFileName(url, req.contentDisposition, req.mimeType),
        )
        val cookie = cookieForDownload(url)
        val target = downloadTargetDir()
        Toast.makeText(this, "开始下载 $filename", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = runCatching {
                sessionDownloads.download(
                    SessionDownloadService.Request(url, cookie, req.userAgent), target, filename,
                )
            }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { file ->
                        Log.i(DshApplication.TAG, "Session 日志已保存: ${file.absolutePath} (${file.length()} 字节)")
                        Toast.makeText(this@MainActivity, "已保存到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                    .onFailure { e ->
                        Log.w(DshApplication.TAG, "下载失败: $url", e)
                        Toast.makeText(this@MainActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    /**
     * 取下载要带的 cookie。
     *
     * ⚠️ 走 `CookieManager` 而不是内核无关的 HTTP 客户端：`/api/session.export` 用
     *   **HttpOnly cookie** 鉴权（真机实测带 cookie 200 / 不带 401），而 `CookieManager`
     *   本来就是把 HttpOnly cookie 交给宿主的那个口子。
     *
     * ⚠️ 两个内核共用**同一个** `CookieManager`（Android 的 cookie jar 是进程级、
     *   按 URL 索引的，与渲染内核无关）—— 所以 gecko flavor 里这里照样拿得到
     *   GeckoView 写进去的 cookie。实测依据见真机验证日志。
     */
    private fun cookieForDownload(url: String): String =
        runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")

    /**
     * 下载落地目录。
     *
     * 有存储权限时写公共「下载」目录（用户能在文件管理器里直接找到）；
     * 用户拒绝权限时退回 app 自己的外部目录 —— 那条路**不需要任何权限**，
     * 功能不至于整个不可用（与 `ensureStorageAccess()` 的取舍一致）。
     */
    private fun downloadTargetDir(): File {
        val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            @Suppress("DEPRECATION")
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDir != null && (publicDir.isDirectory || publicDir.mkdirs())) return publicDir
            Log.w(DshApplication.TAG, "公共下载目录不可用，改用 app 外部目录")
        }
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
    }

    /**
     * 当前挂起的文件选择结果接收者；null 表示没有等待中的选择。
     *
     * ⚠️ 由 `launchFileChooser` 装、由 `fileChooserLauncher` 的回调消费，
     *   **一次性**：消费前必须取出并置空，否则第二次点击不会响应。
     */
    private var fileChooserResultSink: ((Array<Uri>?) -> Unit)? = null

    // ══ 系统返回键 ══════════════════════════════════════════════════════
    //
    // 现象：本 App 是一个 WebView 壳，界面里所有的"层级"（设置一级/二级、
    //   各种弹窗、侧栏抽屉、插件面板）**全部活在网页里**，Android 一无所知。
    //   于是系统返回键在**任何**界面下都直接退出 App —— 用户在设置二级页
    //   按返回，期望回一级，结果整个 App 没了。
    //
    // 做法：原生拦下返回键 → 问页面「这一层要不要消费」→ 页面返回 false 才退出。
    //   页面侧的状态机在 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` §12，
    //   它按由内到外的顺序处理：重命名弹窗 → 设置二级 → 设置一级 → 关闭设置
    //   → 官方其它模态弹窗 → composer 菜单 → 侧栏抽屉 → 官方主面板 → 都没有则退出。
    //
    // ⚠️ **不能只靠 `webView.canGoBack()`**：这是个单页应用（SPA），路由不走
    //   history —— 实测从头到尾 `canGoBack()` 恒为 false，用它判等于没做。
    //
    // ⚠️ **回调是异步的**（`evaluateJavascript` 走 WebView 的 JS 线程），所以
    //   不能直接在回调里调 Activity 的复杂操作；这里只在"页面说没得关"时才
    //   收尾，动作单一，安全。
    //
    // ⚠️ **收尾动作是「退到后台」，不是 `finish()`**（0.2.0 起）。本 App 的核心
    //   状态全在 Node 引擎里：`finish()` 会走到 `onDestroy` → 停掉引擎，用户切回来
    //   要重新等引擎启动。而返回键在手机上的语义本来就是「离开这个界面」而不是
    //   「关掉这个应用」—— 启动器、微信、浏览器都是退到后台保留现场；真要退出
    //   可以用最近任务列表划掉。引擎不随 Activity 退后台而停（跑在 EngineService 里）。

    private var lastBackExitAt = 0L
    private val backHandler = Handler(Looper.getMainLooper())
    private val pageBack by lazy {
        PageBackNavigation(
            scheduler = object : UiTaskScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) { backHandler.postDelayed(task, delayMs) }
                override fun remove(task: Runnable) { backHandler.removeCallbacks(task) }
            },
            query = { done -> web.evaluateJavascript(BACK_PROBE_JS, done) },
            onUnhandled = { timedOut ->
                if (timedOut) Log.w(DshApplication.TAG, "返回键探针超时（页面未响应），按无可退层级处理")
                else Log.i(DshApplication.TAG, "返回键：页面已无可退层级，退到后台（不结束进程）")
                if (!interceptBackExit()) moveTaskToBack(true)
            },
        )
    }

    /**
     * 安装返回键处理。
     *
     * 用 `OnBackPressedCallback` 而不是覆写 `onBackPressed()`：
     * `AppCompatActivity` 会把返回键分发给注册的回调，`onBackPressed()` 那条路
     * 在 API 33+ 已被废弃，且与 `OnBackInvokedDispatcher`（Android 13+ 的
     * 预测式返回手势）不互通 —— 用回调注册才对两条路都生效。
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                queryPageBack()
            }
        })
    }

    /** 浏览器标签与历史优先；官方页面由单次探针决定是否退到后台。 */
    private fun queryPageBack() {
        // ── AI 浏览器优先 ──────────────────────────────────────────────
        // 浏览器是整个 App 最上面的一层，返回键先归它：
        //   标签 > 1 且当前页无历史 → 关掉这个标签；
        //   当前页有历史 → 后退；
        //   否则 → 收起浏览器（回到官方界面），**不退出 App**。
        // ⚠️ 必须放在 `PageBackNavigation` 守卫之前：那条路是问页面要
        //    `__DSH_MOBILE_BACK__`，而浏览器盖住页面时页面根本不该被问到。
        if (isAiBrowserVisible()) {
            val b = aiBrowser
            if (b != null) {
                if (b.currentUrl().isNotEmpty() && b.tabsCount() > 1 && !b.canGoBack()) {
                    b.closeCurrentTab()
                    Log.i(DshApplication.TAG, "返回键：关闭浏览器当前标签")
                } else if (b.canGoBack()) {
                    b.goBack()
                    Log.i(DshApplication.TAG, "返回键：浏览器内后退")
                } else {
                    closeAiBrowser()
                    Log.i(DshApplication.TAG, "返回键：收起 AI 浏览器")
                }
            } else {
                closeAiBrowser()
            }
            return
        }

        pageBack.request()
    }

    /** 返回键「再按一次才退」：反馈 `badea0`（vivo）返回键「不小概率直接退出 App」
     *  —— `moveTaskToBack` 在激进清理的后台上与"被杀"没区别。 */
    private fun interceptBackExit(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBackExitAt <= BACK_EXIT_WINDOW_MS) return false
        lastBackExitAt = now
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * 页面 → 原生 的状态通道，暴露成 `window.DSHAndroid`。
     *
     * 只有本地引擎会被加载进这个 Web 视图（外链全丢给系统浏览器），所以不存在
     * "第三方页面拿到这个接口"的风险。
     *
     * ⚠️ **两个 flavor 共用这一份实现**：怎么把它暴露给页面（WebView 的
     *   `addJavascriptInterface` / GeckoView 的 WebExtension）由各自的
     *   [WebHost] 负责，本类只实现能力。
     *
     * ⚠️ 在 system 版里这些方法**运行在 WebView 的 JS 线程**上，不能碰 View；
     *   通知交给 `EngineService.updateStatus()`（内部只走 NotificationManager），
     *   底色则 `runOnUiThread` 切回主线程再设。gecko 版由 [WebHost] 统一切主线程。
     */
    private inner class ActivityPageBridge : ActivityPageBridgeActions {
        override fun onStatus(json: String) {
            val status = EngineStatus.parse(json) ?: return
            EngineService.updateStatus(this@MainActivity, status)
            // 只在底色真变了时才切主线程，避免每次状态刷新都白 post 一个任务
            val bg = status.bg
            if (bg.isNotEmpty() && bg != appliedBgHex) {
                appliedBgHex = bg
                runOnUiThread { applyPageBackground(bg) }
            }
        }

        /**
         * 输入框即将获得焦点 —— 比键盘 insets 早约 200ms 的信号（见说明）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程，`onImeWillShow()` 内部会切主线程。
         */
        override fun onImeWillShow() {
            this@MainActivity.onImeWillShow()
        }







        /**
         * 移动端 UI 插件激活完成 —— 界面真正可用的时刻。
         *
         * 这是启动动画该收尾的信号：`onPageFinished` 只代表 DOM 到了，实测之后
         * 还有约 1.7 秒的插件激活期（前端要解析 5.5MB 的 client.js 并挂载）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程；`notifyUiReady` 只推一个 StateFlow，
         *   真正的收起动作由 `lifecycleScope` 在主线程做。
         */
        override fun onUiReady() {
            Log.i(DshApplication.TAG, "插件已激活（onUiReady）")
            engine.notifyUiReady()
        }

        /**
         * 页面侧脚本错误上报（见 `webview-polyfill.js`）。
         *
         * 这类失败原本在设备上完全无声：页面起不来，但宿主收不到任何回调，
         * 只能靠 12 秒兜底超时收掉启动层，露给用户一片白屏。
         * 有了它，启动层就能把**真实原因**摆出来（见 `BootOverlayPresenter`）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程，只写一个 volatile 字段，不碰 View。
         * ⚠️ 只记第一条：启动失败时后续错误多半是连锁反应。
         */
        override fun reportWebError(message: String) {
            if (webError.isNotEmpty()) return
            webError = message
            Log.w(DshApplication.TAG, "页面脚本错误: $message")
        }

        /**
         * 设置页「切到固定端口」：把 adbd 切到 `adb tcpip 5555`。
         *
         * 切完之后特权通道**不再依赖无线调试开关**（实测关掉开关、关掉 Wi-Fi
         * 都不影响）。这是个阻塞操作（几秒），所以丢到后台线程，结果用 Toast 回。
         *
         * ⚠️ 不能直接在 JS 线程里做：它最多要等约 30 秒（发现端口 + 切换 + 轮询确认
         * 真的拿到 uid 2000），会卡住 WebView 的 JS 线程。
         * ⚠️ 30 秒是**最坏**情况（adbd 要重启且无线调试端口得现发现）。稳态下
         * 5555 已经在听，runner 第一句探测就返回，几百毫秒。
         */
        override fun switchToTcpMode() {
            Log.i(DshApplication.TAG, "设置页请求切到固定 TCP 端口")
            val app = applicationContext
            Thread {
                val engine = EngineManager.get(app)
                val result = engine.switchToTcpMode()
                Log.i(DshApplication.TAG, "切到固定端口结果: ${result.ok} ${result.message}")
                runOnUiThread {
                    Toast.makeText(app, result.message, Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        /**
         * ⋯ 菜单「刷新」—— 安全重载界面。
         *
         * ⚠️⚠️ 页面侧**绝不能**用 `location.reload()`：整页重载请求的是 `./`，
         *   不会再带启动时那条 `?token=`，于是 API / WebSocket 全部 401，
         *   用户看到「连接异常」、会话列表空、回 Hero 页，**只能重启 App**。
         *   正确做法是重载 `lastLoadedUrl`（带 token 的原始地址），
         *   即 [com.dsh.android.web.WebHost.reload]。
         *
         * ⚠️ 走 [WebRecoveryController] 而不是直接 `web.reload()`：这条链路已经带上了
         *   「重置首帧记忆 → 重载 → 等新首帧 → 失败再试一次」的完整判据
         *   （见 [WebHost.resetFirstFrame] 的 KDoc）。绕过它会让「刷新后白屏」
         *   重新变成不可观测的静默失败。
         * ⚠️ 运行在 WebView 的 JS 线程上，必须切主线程 —— `reload()` 会碰 View。
         */
        override fun reloadWebUi() {
            Log.i(DshApplication.TAG, "页面菜单请求刷新界面（安全重载）")
            runOnUiThread { requestSafeReload() }
        }

        // ══ 悬浮气泡：设置页的三个接口 ══════════════════════════════════
        //
        // 走 JS 桥而不是宿主半的 HTTP 路由：开关状态是**原生**的事实（SharedPreferences
        // + Settings.canDrawOverlays），Node 侧读不到；拉起系统授权页更是 Android 的能力。
        // 宿主半那条路（特权权限用的）是为「需要 spawn 进程探测」的场景准备的，这里不需要。













        /**
         * 把页面侧的诊断文字写进 `app.log`。
         *
         * ## 为什么需要
         *
         * 余额不出数的时候，页面的 `console.log` **不进 `app.log`**（它走 WebView
         * 控制台），于是"到底有没有拿到 `remote.account`""接口返回了什么"这些
         * 关键事实在真机上完全看不见，只能靠猜。
         *
         * 有了这个通道，页面侧把一次探测结果拼成一句话发过来，就能在
         * `logs/app.log` 里按 `[DSH]` 标签直接读。
         *
         * ⚠️ 只用于诊断，**不要**在正常路径里频繁调用（会污染日志）。
         */
        override fun pageDiag(text: String) {
            Log.i(DshApplication.TAG, "页面诊断: $text")
        }

        /**
         * 写系统剪贴板（供页面侧「复制登录链接」用）。
         *
         * ⚠️ 运行在 WebView 的 JS 线程上。`ClipboardManager` 是系统服务，
         *   从任意线程调用都安全（内部自己切 binder），不需要 `runOnUiThread`。
         *
         * ⚠️ 失败**不能抛**：抛出去会让 JS 侧收到异常而不是 `false`，
         *   官方那段 `try/catch` 会走到意料之外的路径。
         */
        override fun writeClipboard(text: String): Boolean {
            if (text.isEmpty()) return false
            return try {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH", text))
                Log.i(DshApplication.TAG, "已复制到剪贴板: ${text.length} 字符")
                true
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "写剪贴板失败: ${t.message}")
                false
            }
        }





        /**
         * 拉起系统的「显示在其他应用上层」授权页。
         *
         * ⚠️ `SYSTEM_ALERT_WINDOW` **不能运行时请求**（不是 runtime permission，
         *   `requestPermissions` 对它无效），只能把用户送到这个设置页手动开。
         *   个别 ROM 没有这个 Activity，所以要 catch 后回落到应用详情页。
         */
        override fun openAiBrowser() {
            // 官方右侧边栏的「AI 浏览器」面板点进来。必须在主线程建 View。
            Log.i(DshApplication.TAG, "侧边栏请求打开 AI 浏览器")
            // ⚠️ 必须显式写 this@MainActivity：本类是 inner class，直接调
            //   `openAiBrowser()` 会解析成自己 ⇒ 无限递归（StackOverflow）。
            runOnUiThread { this@MainActivity.openAiBrowser() }
        }

        /**
         * 面板矩形上报 —— 把 AI 浏览器摆到右侧栏面板的内容区上。
         *
         * ## 为什么要换算
         *
         * 前端给的是 `getBoundingClientRect()`：**CSS 视口坐标**。要落到屏幕上
         * 还得两样页面拿不到的东西：WebView 在屏幕上的位置、以及 CSS→物理的
         * 缩放比（`window.screenX` 在 WebView 里恒为 0）。
         *
         * ```
         * scale   = webView宽度(物理px) / 视口宽度(CSS px)
         * 屏幕X    = webView屏幕X + cssX * scale
         * 根布局X  = 屏幕X - 根布局屏幕X
         * ```
         *
         * ⚠️ 每帧都可能来一次（ResizeObserver）。所以：解析失败静默返回、
         *   矩形没变由 `applyEmbedRect` 短路 —— 不能在这里打日志刷屏。
         */
        override fun aiBrowserLayout(json: String) {
            val rect = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
            val x = rect.optDouble("x", Double.NaN)
            val y = rect.optDouble("y", Double.NaN)
            val w = rect.optDouble("w", 0.0)
            val h = rect.optDouble("h", 0.0)
            val vw = rect.optDouble("vw", 0.0)
            // 布局还没稳定（面板高度 0 / 视口宽度未知）时不上报：这时候摆过去
            // 只会得到一条 1px 的缝，等下一次 ResizeObserver 更靠谱。
            if (x.isNaN() || y.isNaN() || w < 8 || h < 8 || vw < 8) return

            // ⚠️ **整段排版计算都必须在主线程里做。**
            //
            //   原来只有最后的 applyEmbedRect 包在 runOnUiThread 里，而前面这些
            //   —— `web.view.width`、`webView.getLocationOnScreen()`、
            //   `binding.root.getLocationOnScreen()` —— 全都是 **View 的方法**，
            //   却直接跑在 JS 线程上（本方法是 @JavascriptInterface）。
            //
            //   Android 规定 View 只能在主线程访问。这里读的是尺寸和坐标，
            //   在多数 ROM 上"碰巧"能跑（读的是字段），但：
            //     · 读到的是**撕裂的中间状态** —— 布局正在变化时 width 与
            //       getLocationOnScreen 来自不同帧，算出来的矩形会偏移，
            //       表现为「侧栏面板位置偶尔偏一下」，用户只会觉得"卡了"；
            //     · 部分 ROM 会直接抛 CalledFromWrongThreadException。
            //   这类症状极难归因，而代价只是把计算搬进 runOnUiThread 里。
            //
            // DSH 当前主题（深/浅 + 背景色）。面板插件顺路带上来，见
            // browser-pane 的 themePayload()：原生这个 WebView 是独立实例，
            // 自己判断不了 DSH 的主题，只能靠前端喂。
            val dark = rect.optBoolean("dark", true)
            val themeBg = rect.optString("bg", "")

            runOnUiThread {
                if (!::web.isInitialized) return@runOnUiThread
                val webView = web.view
                if (webView.width <= 0 || webView.height <= 0) return@runOnUiThread

                val scale = webView.width / vw
                val webLoc = IntArray(2)
                webView.getLocationOnScreen(webLoc)
                val rootLoc = IntArray(2)
                binding.root.getLocationOnScreen(rootLoc)

                val left = (webLoc[0] + x * scale).toInt() - rootLoc[0]
                val top = (webLoc[1] + y * scale).toInt() - rootLoc[1]
                val widthPx = (w * scale).toInt()
                val heightPx = (h * scale).toInt()

                val view = ensureAiBrowser() ?: return@runOnUiThread
                view.applyTheme(dark, themeBg)
                view.applyEmbedRect(left, top, widthPx, heightPx)
                browserPane.embedded()
                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                    syncAiBrowserVisible()
                    view.bringToFront()
                    Log.i(
                        DshApplication.TAG,
                        "AI 浏览器嵌入右侧栏面板: ${widthPx}x${heightPx} @ ($left,$top) scale=$scale",
                    )
                }
            }
        }

        /**
         * 查询 AI 浏览器当前是否显示 —— 面板挂载时用它对齐状态。
         *
         * 面板可能晚于浏览器挂载（AI 先用工具打开了网页，用户才点开侧栏 tab），
         * 这时面板的本地状态是「未打开」，但浏览器其实已经在屏幕上。不查询就
         * 会出现「浏览器盖着网页、侧栏面板却显示一个打开按钮」。
         *
         * @return `"visible"` / `"hidden"`。
         */
        override fun aiBrowserState(): String =
            if (isAiBrowserVisible()) "visible" else "hidden"

        /**
         * 前端取走标注结果。取一次清空 —— 否则同一张图会被反复提交。
         */
        /**
         * 前端回报标注发送结果 —— **必须显示在屏幕上**。
         *
         * 手机上看不到 console.log，静默失败等于「点了没反应」，用户和
         * 开发者都只能靠猜，代价是反复下载 148MB 的安装包。这里直接 Toast，
         * 用户截一张图就能定位问题。
         */
        override fun aiAnnotateReport(message: String) {
            Log.i(DshApplication.TAG, "标注发送结果: $message")
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    message,
                    if (message.startsWith("已发送")) android.widget.Toast.LENGTH_SHORT
                    else android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }

        override fun peekAnnotation(): String = runCatching {
            browserPane.readAnnotation(consume = false) { pending ->
                JSONObject().apply {
                    put("annotationId", pending.id)
                    put("png", android.util.Base64.encodeToString(pending.file.readBytes(), android.util.Base64.NO_WRAP))
                    put("text", pending.question)
                }.toString()
            }
        }.getOrDefault("")

        override fun acknowledgeAnnotation(annotationId: String) = browserPane.acknowledgeAnnotation(annotationId)

        override fun aiAnnotateResult(): String = runCatching {
            browserPane.readAnnotation(consume = true) { pending ->
                JSONObject().apply {
                    put("png", android.util.Base64.encodeToString(pending.file.readBytes(), android.util.Base64.NO_WRAP))
                    put("text", pending.question)
                }.toString()
            }
        }.getOrElse {
            Log.w(DshApplication.TAG, "编码标注图失败: ${it.message}")
            runOnUiThread { Toast.makeText(this@MainActivity,
                "标注图读取失败：${it.message ?: "未知原因"}，请重新圈画", Toast.LENGTH_LONG).show() }
            ""
        }

        override fun aiBrowserDetach() {
            // 只隐藏，不销毁：页面状态与登录态保留，下次开 tab 还是原样。
            runOnUiThread {
                aiBrowser?.let {
                    it.visibility = View.GONE
                    syncAiBrowserVisible()
                    // ⚠️ 必须清掉嵌入矩形：否则面板关掉后 AI 再调工具打开时，
                    //   浏览器会出现在上一次那块（已经不存在的）面板位置。
                    it.clearEmbedRect()
                    browserPane.hide(clearEmbedding = true)
                }
                Log.i(DshApplication.TAG, "AI 浏览器已随面板收起")
            }
        }

        override fun bubbleRequestOverlay() {
            Log.i(DshApplication.TAG, "设置页请求悬浮窗授权")
            runOnUiThread {
                val pkgUri = Uri.parse("package:$packageName")
                val ok = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!ok) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        // ══ 键盘避让（无障碍）：设置页的开关与权限入口 ══════════════════
        //
        // 与气泡开关同一套接缝（JS 桥），理由也一样：开关在 SharedPreferences、
        // 授权在 Settings.Secure，浏览器进程两样都读不到；拉起系统设置页更是
        // Android 的能力。设置页只负责展示，事实全在原生侧。

        override fun keyboardAvoidState(): String = KeyboardAvoid.stateJson(this@MainActivity)

        override fun keyboardAvoidSetEnabled(enabled: Boolean): Boolean {
            Log.i(DshApplication.TAG, "设置页切换键盘避让: $enabled")
            // ⚠️ 这里**不**弹关联权限的提示：键盘避让有自己那张卡片（开关 + 去开启权限），
            //   在"开气泡"这个动作上顺手弹一个无障碍对话框，用户会不知道自己在确认什么。
            // 没有可用检测来源时原生会拒绝打开（返回 false），设置页据 stateJson 置灰。
            return KeyboardAvoid.setEnabled(this@MainActivity, enabled)
        }

        override fun keyboardAvoidRequestPermission() {
            Log.i(DshApplication.TAG, "设置页请求无障碍权限")
            // ⚠️ 运行在 JS 线程，startActivity 必须回主线程。
            runOnUiThread { KeyboardAvoid.requestPermission(this@MainActivity) }
        }

        // ══ 悬浮球：转录通道 + 插件外观接口 ══════════════════════════════
        //
        // 这两个是「把悬浮窗接进 DSH 生态」的接缝：任何页面内的插件都能直接调，
        // 不需要原生侧为它改一行代码。
        //
        // ⚠️ 走 JS 桥而不是宿主半的 HTTP 路由：气泡是**窗口**（WindowManager 的
        //   TYPE_APPLICATION_OVERLAY），只有持有 View 层级的那一侧能操作它；
        //   Node 侧连 View 是什么都看不到。







        // ══ root（uid 0）：设置页的状态显示与重新检测 ══════════════════════
        //
        // 为什么必须显示出来：`root` 工具**只在检测到 root 时才注册**，所以没有
        // root 的设备上，用户在任何地方都看不到它的痕迹 —— 分不清「这台机器没有
        // root」与「有 root 但 App 没检测到」。状态与原因都得可见，否则用户连
        // 该不该去装/授权 Magisk 都无从判断。
        //
        // 走 JS 桥而不是宿主半的 HTTP 路由：探测结论落在 `filesDir`（原生私有目录），
        // 而「重新检测」要起 `su` 进程、可能弹授权框 —— 两件都是 Android 侧的事。







        /**
         * 设置页点「授权」：让 Shizuku 弹它自己的确认界面。
         *
         * ⚠️ **必须在主线程**：Shizuku 用当前 Activity 作为确认界面的宿主。
         * ⚠️ 结果异步回来（用户在 Shizuku 的界面上点「允许」），所以这里立即返回；
         *   界面靠轮询 [shizukuState] 拿结论，与 [rootRecheck] 同构。
         */
        override fun shizukuRequestPermission() {
            Log.i(DshApplication.TAG, "设置页请求 Shizuku 授权")
            runOnUiThread {
                val ok = ShizukuChannel.requestPermission(this@MainActivity, SHIZUKU_REQUEST_CODE)
                if (!ok) {
                    // 没有 binder 时 Shizuku 根本没法弹框（确认界面由**服务端**拉起），
                    // 所以这里要如实告诉用户「先去启动 Shizuku」，而不是静默什么都不发生。
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.shizuku_need_service),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        /**
         * 拉起 Shizuku 管理器应用。
         *
         * ⚠️ 用 `getLaunchIntentForPackage` 而不是写死 Activity 名：Shizuku 换过
         *   主 Activity 的名字，写死会在版本更新后失效（而 `launchIntent` 由系统
         *   从清单里解析，不会）。取不到时回落到应用详情页 —— 那也是用户能手动
         *   打开它的地方。
         */
        override fun shizukuOpenManager() {
            Log.i(DshApplication.TAG, "设置页请求打开 Shizuku")
            runOnUiThread {
                val pm = packageManager
                val launch = pm.getLaunchIntentForPackage(ShizukuChannel.MANAGER_PACKAGE)
                val intent = launch ?: Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ShizukuChannel.MANAGER_PACKAGE}"),
                )
                startActivitySafely(intent)
            }
        }

        /**
         * 打开 Shizuku 的官方发布页。
         *
         * ⚠️ 走浏览器而不是商店：Shizuku 在部分国内 ROM 的应用商店里搜不到，
         *   而它的 GitHub Releases 是权威来源。
         */
        override fun shizukuOpenDownload() {
            Log.i(DshApplication.TAG, "设置页请求下载 Shizuku")
            runOnUiThread {
                startActivitySafely(
                    Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /**
         * 起一个外部 Activity，失败时静默记日志。
         *
         * ⚠️ 不弹「没有应用能处理」：这类失败对用户没有可操作性（他能做的只是
         *   自己去找），弹一个 Toast 反而像是 App 出了问题。
         */
        private fun startActivitySafely(intent: Intent) {
            runCatching {
                if (intent.component == null && intent.action == Intent.ACTION_VIEW) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }.onFailure { t ->
                Log.w(DshApplication.TAG, "拉起外部界面失败: ${t.message}")
            }
        }

        /**
         * 设置页点「运行日志与诊断」—— 拉起原生日志界面（入口①）。
         *
         * ⚠️ `startActivity` 必须在**主线程**：JS 桥的调用线程是内核的 JS 线程
         * （gecko 版是 Gecko 线程），在那里起 Activity 会抛
         * `Calling startActivity() from outside of an Activity context` 之类的错。
         * 所以 post 到主线程，且立即返回不等结果 —— JS 侧不需要回执。
         */
        override fun openLogs() {
            runOnUiThread { openLogViewer("settings") }
        }

        /**
         * 读日志摘要给设置页那一行当副标题。
         *
         * ⚠️ **必须便宜**：设置页每次打开都调。所以只数内存缓冲（4000 条以内，
         * 一次遍历）+ 读 `BootState`（几十字节的 JSON），**不读磁盘日志**。
         * 完整诊断要读几 MB 的文件算行数，只在用户点「导出诊断包」时跑。
         *
         * ⚠️ 全程 `runCatching`：这个方法的返回值直接拼进页面 DOM，抛异常会让
         * JS 桥把错误吞掉、设置页那一行变成空白 —— 用户以为功能没做。
         */
        override fun logSummary(): String = runCatching {
            val all = LogStore.entries()
            val prev = BootState.previous()
            JSONObject().apply {
                put("total", all.size)
                put("warn", all.count { it.level == 'W' })
                put("error", all.count { it.level == 'E' })
                put("crash", CrashRecorder.sceneCount())
                put("pending", LogUploader.pendingCount(File(filesDir, DshApplication.LOG_DIR)))
                put("lastExit", prev.describe())
                put("uploadConfigured", LogUploader.configured)
                put("version", VersionInfo.summary())
            }.toString()
        }.getOrElse {
            Log.w(DshApplication.TAG, "读日志摘要失败", it)
            // 返回一个结构完整的空对象而不是空串：页面侧可以直接读字段，
            // 不用先判空 —— 少一个出错分支。
            """{"total":0,"warn":0,"error":0,"crash":0,"pending":0,""" +
                """"lastExit":"读取失败","uploadConfigured":false,"version":""}"""
        }

        // ── 原生上传兜底通道（见 NativeUploader 的类注释）──────────────────
        //
        // ⚠️ 四个方法都跑在 WebView 的 JS 线程上，**必须立即返回**；
        //   真正的网络 IO 在 finish() 里另起线程。
        // ⚠️ 页面侧的 finish 依赖 `window.__dshNativeUploadDone` 回投结果，
        //   polyfill 保证「先挂函数再调 finish」。

        override fun uploadNativeBegin(taskId: String, url: String, headersJson: String): Boolean =
            nativeUploader.begin(taskId, url, headersJson)

        override fun uploadNativeWrite(taskId: String, base64: String): Boolean =
            nativeUploader.write(taskId, base64)

        override fun uploadNativeFinish(taskId: String) = nativeUploader.finish(taskId)

        override fun uploadNativeAbort(taskId: String) = nativeUploader.abort(taskId)
    }

    /**
     * 把页面实际底色同步到 WebView 与根容器，消除键盘升起时的异色闪块。
     *
     * **为什么会闪**：`adjustResize` 让窗口变矮是逐帧动画的，WebView 跟着变矮，
     * 但页面重排要等下一帧 —— 这个空档里新露出的那条区域还没有内容覆盖，显示的
     * 就是 WebView 自己的背景色。原先写死的 `#0F1115` 与官方白色页面不一致，
     * 于是表现为"点输入框时下半屏先黑一下"。
     *
     * ⚠️ 颜色由页面上报，**不能写死**：官方主题可切换，写死只是把问题换个方向暴露。
     * ⚠️ 必须在主线程调用（`runOnUiThread`）。
     */
    private fun applyPageBackground(bg: String) {
        val color = try {
            Color.parseColor(bg)
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "忽略非法页面底色: $bg")
            return
        }
        web.view.setBackgroundColor(color)
        // 根容器一起改：覆盖层隐藏与 Web 视图首次绘制之间露出的就是它
        binding.root.setBackgroundColor(color)
        // 窗口底色也跟上：DecorView 与 WebView 是两层独立的合成，窗口缩小时
        // 两层各自的 Surface 更新并不同步，只改一层仍可能露出一帧旧色
        window.setBackgroundDrawable(ColorDrawable(color))
        // 系统栏只在启动层收起后才跟随，理由见 applySystemBarColors
        if (overlayDismissed) syncSystemBars()
    }

    /**
     * 把系统栏（**状态栏** + **导航栏**）的底色与图标明暗同步到页面实际底色，
     * 但**两条栏各自优先用钦定色**（[SystemBarAppearance]）。
     *
     * **为什么要做**：这两处原先在 `themes.xml` 里写死 `#0F1115`（深色），
     * 而官方 Web GUI 是浅色的 —— 于是屏幕顶部、底部各留一条黑边，与页面割裂。
     * 页面底色本来就经 `onStatus` 上报（见 [applyPageBackground]），这里把它一并
     * 应用到系统栏，App 主题切深色时也会自动跟上（不写死颜色）。
     *
     * **两条栏是分开决定的**：各自「有钦定色就用钦定色，没有就跟随页面底色」。
     * 所以「只改状态栏、底部继续跟主题」这种组合是天然支持的，不需要额外的开关。
     *
     * ⚠️ **必须在启动层收起之后才调用**：启动页是深色的（`#0B0E18`），
     *   引擎起来之前就把系统栏刷成白色，会在顶部露一条白边（比黑边更刺眼）。
     *   所以 `applyPageBackground` 里用 `overlayDismissed` 拦一道。
     * ⚠️ 底色浅时**必须**把图标切成深色：状态栏图标默认是浅色，白底上会看不见。
     *   图标明暗由实际生效的底色算（不是由钦定色算 —— 那是同一件事）。
     * ⚠️ 必须在主线程调用（`runOnUiThread`）。
     *
     * ⚠️ **正在闪的时候不要用它覆盖**：本方法只画「静态色」（钦定色或页面底色），
     *   而闪烁由 [flashStep] 逐帧驱动。真正的分派在 [syncSystemBars]，它决定
     *   该走哪条路。直接调本方法会把闪的当前帧顶掉。
     *
     * @param pageBg 页面底色 `#RRGGBB`；空串表示还没收到过上报，此时只有钦定色会生效。
     */
    private fun applySystemBarColors(pageBg: String) {
        val config = SystemBarAppearance.load(this)
        val statusHex = config.statusBar.ifEmpty { pageBg }
        val navHex = config.navBar.ifEmpty { pageBg }
        setSystemBarColors(
            parseColorOrNull(statusHex),
            parseColorOrNull(navHex),
        )
    }

    /**
     * 真正落窗口属性的那一层：两条栏各给一个颜色，`null` 表示「这条栏不动」。
     *
     * 抽出来是因为它有**两个调用方**（静态路径 [applySystemBarColors] 与
     * 闪烁路径 [flashStep]），而图标明暗的算法只有这一份 —— 抄成两份迟早分叉，
     * 而分叉的症状是「闪的时候图标看不见了」，很难归因。
     */
    private fun setSystemBarColors(status: Int?, nav: Int?) {
        // ⚠️ **全面屏下什么都不做**：那时两条栏必须是透明的，让页面内容透上来。
        //   这里是所有"画颜色"路径的唯一收口（静态色、闪烁每帧、闪完停在钦定色），
        //   把守卫放在这一层，就不必在三个调用点各写一遍 —— 少写一处就是
        //   "全面屏开着，但顶上突然冒出一条红色"这种间歇性现象。
        if (fullscreenApplied) return
        // 两条都无从决定（既没钦定、页面也还没上报）⇒ 保持 themes.xml 里的深色不动
        if (status == null && nav == null) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        status?.let {
            window.statusBarColor = it
            controller.isAppearanceLightStatusBars = SystemBarAppearance.isLightColor(it)
        }
        nav?.let {
            window.navigationBarColor = it
            controller.isAppearanceLightNavigationBars = SystemBarAppearance.isLightColor(it)
        }
        // 钦定图标明暗（全面屏下"底色"不是纯色、自动算不准，见 SystemBarAppearance 的注释）。
        // ⚠️ 必须在 setSystemBarColors 里也做一次：闪烁每帧走的是这条路径，
        //   不在这里处理的话"全面屏 + 闪烁"会让每帧都把钦定值刷回自动算的值。
        applyIconOverrides(controller, status != null, nav != null)
    }

    /**
     * 把钦定的图标明暗盖到窗口上。`auto` 时什么都不做（保留上一行的自动算结果）。
     *
     * @param touchStatus 本次是否真的动过状态栏。两条栏各自判断 —— 只看状态栏的
     *   钦定值不该去动导航栏的图标。
     */
    private fun applyIconOverrides(controller: WindowInsetsControllerCompat, touchStatus: Boolean, touchNav: Boolean) {
        val config = SystemBarAppearance.load(this)
        if (touchStatus) {
            when (config.statusBarIcons) {
                SystemBarAppearance.ICON_LIGHT -> controller.isAppearanceLightStatusBars = false
                SystemBarAppearance.ICON_DARK -> controller.isAppearanceLightStatusBars = true
            }
        }
        if (touchNav) {
            when (config.navBarIcons) {
                SystemBarAppearance.ICON_LIGHT -> controller.isAppearanceLightNavigationBars = false
                SystemBarAppearance.ICON_DARK -> controller.isAppearanceLightNavigationBars = true
            }
        }
    }

    /**
     * **全面屏**：把内容延展到整屏，两条栏变成"浮在内容之上的透明层"。
     *
     * ## 这是「背景覆盖全屏」的底座，不是给背景图开的特例
     *
     * 改动前窗口是 `LAYOUT_INSET_DECOR`，内容区被系统挤到状态栏**下沿**
     * （真机实测 `content=[0,108][1080,2400]`）—— 顶部那 108px 是块死区，
     * 页面根本画不进去，所以**任何**背景（纯色/渐变/图片）都铺不到屏幕最顶。
     * 打开全面屏后内容从 y=0 开始铺，于是背景自然覆盖全屏，不需要为"背景图"
     * 写任何专门代码 —— 这也是为什么它值得做成一个**开关**而不是图片特例。
     *
     * ## 两个动作
     *
     * ① **两条栏设透明**：`statusBarColor` / `navigationBarColor` 给
     *    `Color.TRANSPARENT`，并把 `LAYOUT_NO_LIMITS` 加上去让内容铺满；
     * ② **把状态栏高度写进页面**（`--dsh-android-system-top`）：页面自己在顶部
     *    留出让位空间，否则标题/汉堡会被时间、电量压住。
     *
     * ## 为什么用 `LAYOUT_NO_LIMITS` 而不是 `setDecorFitsSystemWindows(false)`
     *
     * 后者是 API 30+ 的现代写法，而本机是 **API 29**（硬约束②把 targetSdk 钉在 28，
     * 但设备是 Android 10）。`WindowCompat.setDecorFitsSystemWindows` 在 API 29 上
     * 走的是兼容分支，实测不生效。`LAYOUT_NO_LIMITS` 在 API 14+ 全版本可用，
     * 是这个工程唯一能用的那条路。
     *
     * ⚠️ **关掉时要把 flag 摘干净**：只把颜色改回不透明、不摘 `LAYOUT_NO_LIMITS`
     *   的话，内容仍铺在系统栏下面（标题被状态栏压住），而颜色已不透明 ⇒
     *   用户看到的是"顶部内容被啃掉一块"。所以 `fullscreen` 是**双向**开关，
     *   两个方向都必须完整。
     *
     * ⚠️ **必须在主线程调用**（改窗口 flag 与布局）。
     */
    private fun applyFullscreen(config: SystemBarAppearance, pageBg: String) {
        // ⚠️ **图标明暗必须在状态门之外应用**：全面屏**已经开着**的时候，用户/模型
        //   仍然可以把 statusBarIcons 从 light 改成 auto。若这一步也放进下面的
        //   状态门里（fullscreen 没变就整体 return），那次改动就永远不生效 ——
        //   真机踩过：设成 light（白图标）后再改回 auto，顶部时间和电量一直不可见，
        //   而配置、日志、回执全是"已应用"，完全看不出问题在哪。
        if (config.fullscreen) applyFullscreenIcons(config, pageBg)
        // ⚠️ **窗口状态没变就别再动窗口**：本方法挂在 syncSystemBars 上，而那个入口
        //   每秒都可能被页面底色上报触发。无条件执行的话每秒钟都会写一次
        //   window.attributes（触发一次窗口重布局）再推一次 JS —— 纯浪费，
        //   而且在低端机上能看出掉帧。
        if (config.fullscreen == fullscreenApplied) return
        fullscreenApplied = config.fullscreen
        val lp = window.attributes
        if (config.fullscreen) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            // ⚠️ 透明是"内容能透出来"的前提。不透明色 + NO_LIMITS = 内容被色块盖住，
            //   比改动前的黑边更糟（用户会以为界面坏了）。
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            Log.i(DshApplication.TAG, "已开启全面屏：两条栏透明，内容延展到整屏")
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        }
        window.attributes = lp
        // 页面要让位才知道该留多少 —— 两个方向都推一次（关掉时推 0，页面恢复原样）。
        pushSystemBarInset(config.fullscreen)
        // 内置浏览器同理：全面屏开着时它的工具栏会被状态栏压住，
        // 后退键和 ✕ 就在最左边和最右边 —— 正好是时间/电量所在的位置。
        aiBrowser?.setSafeTop(if (config.fullscreen) statusBarHeightPx() else 0)
    }

    /**
     * 全面屏下的图标明暗（两条栏各自决定）。**每次调用都落窗口**，不受开关状态门约束。
     *
     * 钦定优先；没钦定时按"最可能压在栏下面的颜色"（页面底色或该栏的钦定色）算 ——
     * 拿透明色去算恒为"暗"，会给出浅色图标，在浅色背景上正好看不见。
     *
     * ⚠️ 必须在主线程调用。
     */
    private fun applyFullscreenIcons(config: SystemBarAppearance, pageBg: String) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val statusRef = config.statusBar.ifEmpty { pageBg }
        val navRef = config.navBar.ifEmpty { pageBg }
        when (config.statusBarIcons) {
            SystemBarAppearance.ICON_LIGHT -> controller.isAppearanceLightStatusBars = false
            SystemBarAppearance.ICON_DARK -> controller.isAppearanceLightStatusBars = true
            else -> parseColorOrNull(statusRef)?.let {
                controller.isAppearanceLightStatusBars = SystemBarAppearance.isLightColor(it)
            }
        }
        when (config.navBarIcons) {
            SystemBarAppearance.ICON_LIGHT -> controller.isAppearanceLightNavigationBars = false
            SystemBarAppearance.ICON_DARK -> controller.isAppearanceLightNavigationBars = true
            else -> parseColorOrNull(navRef)?.let {
                controller.isAppearanceLightNavigationBars = SystemBarAppearance.isLightColor(it)
            }
        }
    }

    /**
     * 把状态栏高度写进页面的 `--dsh-android-system-top`。
     *
     * ## 为什么这个变量一直存在却恒为 0
     *
     * 页面侧**早就预留了**这个让位接缝（`[data-mobile] .pI_x6G_sidebarCol` 的
     * `padding-top`、汉堡的 `top` 都在读它），但插件源码里明写着
     * 「该变量在本 App 里**从未被任何代码写入**（恒 0）」——
     * 因为改动前 WebView 自己就被系统挤在状态栏下沿了，页面不需要再让位。
     * 全面屏把这层避让撤掉了，所以现在必须由原生把高度补上。
     *
     * ⚠️ **关掉全面屏时要显式写回 0px**：留着旧高度会让页面白留一条空白
     *   （WebView 已经从状态栏下沿开始了，再留一次就是双倍）。
     *
     * ⚠️ 用 `evaluateJavascript` 注入，**不保证一定被调用**（页面未就绪时不会回），
     *   所以这是"尽力而为"的推值；页面侧自己也有兜底（取不到时默认为 0px）。
     *
     * ## ⚠️ 必须换算成 CSS 像素再推（这里踩过一次，症状是"汉堡整行下移"）
     *
     * 页面是 **CSS 像素**的世界（真机实测视口 360×800 CSS px），而
     * `statusBarHeightPx()` 给的是**物理像素**（同一台机 108px）。两者差一个
     * `density`（该机 3）。直接把物理值当 CSS 值推下去，让位量就**放大 3 倍**：
     *
     * | 变量值 | 汉堡 rect.top | 标题 header top |
     * |---|---|---|
     * | 108（错误，物理值直推） | 124.3 | 108 |
     * | 36（正确，108 ÷ 3） | 52.3 | 36 |
     *
     * 状态栏在 CSS 坐标里只占 36px 高，页面却按 108px 让位 ⇒ 汉堡与整个
     * header 被推到屏幕下方一大截，用户看到的就是"开启沉浸式后顶部那行明显下移"。
     * 因为 `--dsh-android-system-top` 同时被三处消费（汉堡 `top`、内容列
     * `padding-top`、抽屉 `padding-top`），一处错就三处一起错。
     *
     * 换算成 CSS px 后变量语义统一为"CSS 像素"，与页面里写死的 6px 呼吸位同量纲。
     */
    private fun pushSystemBarInset(fullscreen: Boolean) {
        // 物理像素 → CSS 像素（÷ density）。density 为 0 时按 1 处理，避免除零。
        val density = resources.displayMetrics.density.let { if (it > 0f) it else 1f }
        val cssPx = if (fullscreen) Math.round(statusBarHeightPx() / density) else 0
        val js = "(function(){try{document.documentElement.style.setProperty(" +
            "'--dsh-android-system-top','${cssPx}px');}catch(e){}})()"
        web.evaluateJavascript(js, null)
        Log.i(DshApplication.TAG, "页面让位变量 --dsh-android-system-top = ${cssPx}px（CSS 像素）")
    }

    /**
     * 状态栏高度（**物理像素**，不是 CSS 像素 —— 推给页面时必须 ÷ density）。
     *
     * ⚠️ 读系统资源 `status_bar_height` 而不是从 insets 里取：`LAYOUT_NO_LIMITS`
     *   生效后 insets 里的 systemBars 会变成空（内容已经"在栏下面"了），
     *   那时再问 insets 会拿到 0，页面就白留 0px、标题被时间压住。
     *   系统资源是静态的，两种状态下取值一致。
     */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val fromRes = if (id > 0) resources.getDimensionPixelSize(id) else 0
        if (fromRes > 0) return fromRes
        // 兜底：按密度折算 24dp（AOSP 默认值），至少不让页面完全不让位
        return (24 * resources.displayMetrics.density).toInt()
    }

    /** 最近一次已应用到窗口的全面屏状态（见 [applyFullscreen] 的状态门）。 */
    private var fullscreenApplied = false

    /** 解析 `#RRGGBB` / `#AARRGGBB`；空串或非法时返回 null（调用方据此跳过这条栏）。 */
    private fun parseColorOrNull(hex: String): Int? {
        if (hex.isEmpty()) return null
        return try {
            Color.parseColor(hex)
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "忽略非法系统栏底色: $hex")
            null
        }
    }

    // ══ 系统栏闪烁：由原生自驱，不是让模型反复调工具 ══════════════════════
    //
    // 这条设计来自一次真机失败。用户要「7 色不断闪」，模型只能一轮一轮调
    // `statusbar` 工具，而每次调用要走「模型生成 → 写信箱 → 250ms 轮询 → 应用
    // → 回执」——实测帧间隔 7.6~27.9 秒（会话 session-139dd35d 的日志），
    // 根本闪不起来。正解是把「颜色序列」一次性交给原生、这里自己轮转，
    // 与悬浮球的 `spin` / `pulse` 同一个思路。

    private val flashHandler = Handler(Looper.getMainLooper())
    private val flashSequence by lazy {
        SystemBarFlashSequence(
            scheduler = object : UiTaskScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) { flashHandler.postDelayed(task, delayMs) }
                override fun remove(task: Runnable) { flashHandler.removeCallbacks(task) }
            },
            onFrame = { config, status, navigation ->
                setSystemBarColors(
                    status?.let(::parseColorOrNull) ?: parseColorOrNull(config.statusBar.ifEmpty { appliedBgHex }),
                    navigation?.let(::parseColorOrNull) ?: parseColorOrNull(config.navBar.ifEmpty { appliedBgHex }),
                )
            },
            onComplete = { loops ->
                Log.i(DshApplication.TAG, "系统栏闪烁已完成 $loops 圈，停在钦定色上")
                applySystemBarColors(appliedBgHex)
            },
        )
    }

    /**
     * 统一的系统栏刷新入口：**全面屏优先，其次闪，最后静态色**。
     *
     * 四个调用点都走它（配置变了 / 页面底色变了 / 启动层收起 / 全面屏开关），
     * 所以三套逻辑不会互相打架 —— 否则会出现「页面底色一变，闪就停了」
     * 或者「闪的帧被静态色顶掉」这类间歇性现象。
     *
     * ⚠️ **全面屏是排他的，要最先判**：它把两条栏设成透明、内容延展到整屏；
     *   若还继续跑静态色/闪烁，那些颜色会盖在页面内容上 —— 用户看到的就不是
     *   "背景覆盖全屏"，而是"顶上一条色块"。所以全面屏打开时闪烁**刻意不生效**
     *   （回到工具描述里那句话：全面屏与闪烁是两种互斥的展示意图，同时要没有意义）。
     *
     * ⚠️ 必须在主线程调用。
     */
    private fun syncSystemBars() {
        val config = SystemBarAppearance.load(this)
        if (config.fullscreen) {
            stopFlash()
            applyFullscreen(config, appliedBgHex)
            return
        }
        // 从全面屏切回来：摘 flag、把让位变量归零，再走静态路径
        applyFullscreen(config, appliedBgHex)
        if (config.flashRunning && config.hasFlash) {
            startFlash(config)
        } else {
            stopFlash()
            applySystemBarColors(appliedBgHex)
        }
    }

    private fun startFlash(config: SystemBarAppearance) {
        Log.i(DshApplication.TAG, "系统栏开始闪烁: 状态栏 ${config.statusBarFlash.size} 色, 导航栏 ${config.navBarFlash.size} 色, " +
            "${config.flashIntervalMs}ms/帧, 圈数=${config.flashLoops}")
        flashSequence.start(config)
    }

    private fun stopFlash() {
        if (flashSequence.running) Log.i(DshApplication.TAG, "系统栏闪烁已停止")
        flashSequence.stop()
    }

    /**
     * 系统栏钦定配置变了（[SystemBarAppearance.apply] / [reset] / [stopFlash] 后回调）。
     *
     * ⚠️ 可能在**任意线程**被调用（命令信箱轮询线程），内部自己切主线程。
     * ⚠️ 与 `applyPageBackground` 一样受启动层守卫：启动页还在时只落盘不应用，
     *   等 [hideOverlay] 那一枪补上。
     * ⚠️ 走 [syncSystemBars] 而不是直接画静态色：配置里可能带着闪烁序列，
     *   直接画静态色会让「设了序列但一帧都不闪」——那正是上一轮真机失败的样子。
     */
    fun onSystemBarConfigChanged() {
        runOnUiThread {
            if (!overlayDismissed) return@runOnUiThread
            syncSystemBars()
        }
    }

    /**
     * 文件选择结果。`resultCode`/`data` 一律要回给内核 —— **成功、取消、失败
     * 三条路径都必须回调**，漏掉任何一条都会让页面永久卡在等待状态。
     *
     * ⚠️ 结果先落到 [fileChooserResultSink]（由 `launchFileChooser` 装），
     *   因为这个回调本身不知道是哪个内核发起的请求。
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val sink = fileChooserResultSink ?: return@registerForActivityResult
        fileChooserResultSink = null
        sink(parseFileChooserResult(result.resultCode, result.data))
    }

    /**
     * 把 Activity 结果翻译成内核要的 `Array<Uri>`。
     *
     * 取消（`RESULT_CANCELED`）必须回 `null`，而不是空数组 —— 空数组会让页面
     * 以为"选了 0 个文件"并触发一次空提交。
     */
    private fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        // 多选走 clipData，单选走 data。
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is EngineManager.State.Idle -> {
                        showOverlay("准备中…", hint = "正在初始化本地环境")
                    }
                    is EngineManager.State.Extracting -> {
                        // 首次启动唯一有真实百分比的一段（实测约 15.5s）
                        showOverlay(
                            "首次启动，正在解包引擎 ${state.percent}%",
                            hint = "只需这一次，之后启动会快很多",
                            progress = state.percent / 100f,
                        )
                    }
                    is EngineManager.State.Starting -> {
                        showOverlay("正在启动引擎…", hint = "加载 Node 运行时与本地服务")
                    }
                    is EngineManager.State.Ready -> {
                        // 门槛在这里才定：引擎就绪 ⇒ ensureWebViewCompat() 已经跑过，
                        // 降级到底生效了没有此时才准（见 effectiveMinWebViewMajor）。
                        val minMajor = effectiveMinWebViewMajor
                        webViewTooOld = webViewMajor in 1 until minMajor
                        if (webViewTooOld) {
                            Log.w(
                                DshApplication.TAG,
                                "WebView 内核 $webViewMajor < 门槛 $minMajor" +
                                    "（降级前端${if (engine.webViewCompatApplied) "已" else "未"}生效），" +
                                    "前端无法解析，跳过加载",
                            )
                            showOverlay(
                                "系统 WebView 内核过旧",
                                hint = "当前内核 $webViewMajor，需要 $minMajor+；" +
                                    "请在应用商店更新「Android System WebView」后重开 App",
                                isError = true,
                            )
                            return@collect
                        }
                        if (webViewMajor in 1 until MIN_WEBVIEW_MAJOR) {
                            Log.i(
                                DshApplication.TAG,
                                "WebView 内核 $webViewMajor 低于官方门槛 $MIN_WEBVIEW_MAJOR，" +
                                    "但降级前端已生效，继续加载",
                            )
                        }
                        // ⚠️ 这里**不能**收起 overlay：引擎就绪只代表 HTTP 服务起来了，
                        //   页面还没加载、插件更没激活。实测后面还有约 3.8 秒。
                        showOverlay("正在加载界面…", hint = "渲染 Web GUI")
                        // ⚠️ 引擎**重启**过时必须无视 URL 去重（见
                        //   `forceReloadOnReady` 的说明）：端口与 token 没变时
                        //   URL 相等，而页面连的其实是已经死掉的旧端口。
                        if (forceReloadOnReady || web.currentUrl != state.url) {
                            forceReloadOnReady = false
                            Log.i(DshApplication.TAG, "加载 Web GUI: ${state.url}")
                            web.loadUrl(state.url)
                            // ⚠️ 计时必须**从这一刻**起算，不能放在 onCreate。
                            //   引擎启动本身要 7~10 秒（首次还要先解包 15 秒），
                            //   从 onCreate 起算会让兜底在引擎还没起来时就触发，
                            //   把启动层提前收掉 —— 真机实测踩到过（超时 8s 触发于
                            //   引擎就绪前 2.5s）。这里只兜「页面加载 + 插件激活」，
                            //   实测共约 3.8 秒。
                            armOverlayTimeout()
                        }
                    }
                    is EngineManager.State.Failed -> {
                        showOverlay(
                            "引擎启动失败",
                            hint = state.message,
                            isError = true,
                        )
                        // ── P6：连续失败够多次、且 L1/L2 都已试过 ⇒ 自动进安全模式 ──
                        //
                        // ⚠️ 判据全在 [EngineManager.shouldAutoEnterSafeMode] 里
                        //   （见它的 KDoc：三个条件、为什么门槛这么高）。这里只负责
                        //   「判据成立就执行」，不重复判断条件 —— 两处各写一份判据
                        //   迟早漂开，而症状会是「自动触发了但理由是错的」。
                        //
                        // ⚠️ 这个分支会被**重复进入**（每次失败都发一次 Failed），
                        //   所以必须有幂等保护：进安全模式会把 `tier` 记成 SAFE_MODE，
                        //   而判据要求 `tier == RESCUE` ⇒ 第二次进来就自动关上了。
                        //   再加一个本地的 in-flight 标记防重入（重解包要 15 秒，
                        //   这期间再来一次 Failed 会叠起两次重解包）。
                        if (!autoSafeModeInFlight && engine.shouldAutoEnterSafeMode()) {
                            autoSafeModeInFlight = true
                            autoEnterSafeMode()
                        }
                    }
                    is EngineManager.State.Dead -> {
                        // ⚠️ 与 Failed **刻意不同的动作**：引擎本来跑得好好的、
                        //   之后被系统回收或自己 OOM 了，配置是**好的** —— 原样重启
                        //   就是正确动作，绝不该引导用户去点「安全模式」（那会推开
                        //   他辛苦配好的插件配置）。
                        //
                        // 见 `EngineManager.State.Dead` 的 KDoc。
                        //
                        // ⚠️ 计数只统计**短时间内连续**的死亡（见 ENGINE_RESTART_WINDOW_MS）：
                        //   一次长期运行之后的死亡（比如跑了一天被系统回收）是**正常的**，
                        //   把它算进「反复退出」会让用户无端看到「引擎反复停止」。
                        val now = System.currentTimeMillis()
                        if (now - lastEngineDeadAt > ENGINE_RESTART_WINDOW_MS) engineRestarts = 0
                        lastEngineDeadAt = now
                        engineRestarts++
                        Log.w(
                            DshApplication.TAG,
                            "引擎已退出: ${state.message}，准备自动重启（第 $engineRestarts 次）",
                        )
                        // ⚠️ 反复死亡 ⇒ 配置真的有问题，不能再静默重启（见
                        //   `engineRestarts` 的说明）。此时「安全模式」才是正确出口。
                        if (engineRestarts > MAX_ENGINE_RESTARTS) {
                            Log.e(DshApplication.TAG, "引擎反复退出，停止自动重启")
                            invalidateOverlayTimeout()
                            // ⚠️ **同 [onRendererGone] 的 gaveUp 分支**：这是终态，
                            //   刚贴上的「引擎反复停止」是**错误提示**，而在飞的
                            //   [FirstFrameAwaiter] 到点会 `hideOverlay()` 把它收走，
                            //   用户只看到一闪。[invalidateOverlayTimeout] 管的是另一个
                            //   计时器（`BootOverlayPresenter`），管不到这一个。
                            resetFirstFrameAwait()
                            bootOverlay.reopen()
                            showOverlay(
                                "引擎反复停止",
                                hint = "${state.message}（已自动重启 $MAX_ENGINE_RESTARTS 次）。" +
                                    "可点下面的「安全模式」推开损坏的插件配置",
                                isError = true,
                            )
                            return@collect
                        }
                        // ⚠️ 先放开「已收起」守卫，否则提示贴不上（界面早就收起过
                        //   启动层了）。
                        //
                        // ⚠️ **同时作废上一轮遗留的「界面就绪」计时器。** 放开守卫
                        //   等于让上一轮的计时器重新变得能开火 —— 而它描述的是
                        //   **上一次**加载（往往早就成功了），到点会对着刚启动的
                        //   新页面报「界面就绪回调超时」。真机上就是这样盖住了一次
                        //   正常的启动过程（完整证据见 [BootOverlayPresenter] 的 KDoc）。
                        invalidateOverlayTimeout()
                        bootOverlay.reopen()
                        showOverlay("引擎已停止，正在重新启动…", hint = state.message)
                        // ⚠️ 页面此刻是**活的**，但它连的是已经没人监听的端口 ——
                        //   必须等引擎起来后重新加载，否则界面永远连不上。
                        // ⚠️ 清掉 firstFrame 让新一轮的「等首帧」重新生效。
                        //   还要撤销在飞的「首帧兜底超时」—— 它到点会执行
                        //   `hideOverlay()`，把刚贴上的「引擎已停止，正在重新启动…」
                        //   这个**提示**（不是启动动画）收走，用户只看到一闪。
                        //   下面那句 `invalidateOverlayTimeout()` 只管
                        //   `BootOverlayPresenter`，管不到这一个 —— 两个计时器
                        //   都要作废，所以走同一个入口 [resetFirstFrameAwait]。
                        resetFirstFrameAwait()
                        // ⚠️ 还要把页面阶段退回 None：否则 `uiStage` 停在 UiReady，
                        //   而 StateFlow 相同值不重复发射 ⇒ 新一轮的「等首帧再收
                        //   启动层」分支一次都跑不到 ⇒ 启动层永远收不起来。
                        engine.resetUiStage()
                        // ⚠️ 还要声明「这次必须重新加载」：端口与 token 没变时
                        //   URL 与上次相等，去重会命中，页面就不会重新加载 ——
                        //   而它连的是已经死掉的旧端口。
                        forceReloadOnReady = true
                        lifecycleScope.launch {
                            try {
                                engine.start()
                                // 同 bootEngine()：`start()` 内部可能刚降级，
                                // 重启完要重新刷横幅，否则显示的层级是旧的。
                                refreshRescueBanner()
                            } catch (c: CancellationException) {
                                // 同 bootEngine()：Activity 被销毁时 lifecycleScope 取消，
                                // 这不是启动失败，别记成错误、也别弹遮罩。
                                throw c
                            } catch (t: Throwable) {
                                Log.e(DshApplication.TAG, "引擎自动重启失败", t)
                                showOverlay("引擎重启失败", hint = t.message, isError = true)
                            }
                        }
                    }
                }
            }
        }
        // 页面阶段：引擎就绪之后还有两段，宿主原本完全感知不到
        lifecycleScope.launch {
            engine.uiStage.collect { stage ->
                when (stage) {
                    is EngineManager.UiStage.None -> Unit
                    is EngineManager.UiStage.PageLoaded -> {
                        // 实测 onPageFinished 之后还有约 1.7s 插件激活期
                        showOverlay("正在准备界面…", hint = "激活移动端适配插件")
                        // 待判窗口从**页面 load 完成**起算（没 load 完属 [WebRecoveryController]）；
                        // 这是不用任何文案判据的卡死检测，见 [WebClientWatchdog]。
                        webClientWatchdog.reset()
                        webClientWatchdog.arm(
                            delayMs = WebClientHealth.CLIENT_DEADLINE_MS,
                            why = "页面 load 后没有任何健康证据",
                        )
                    }
                    is EngineManager.UiStage.UiReady -> {
                        // ⚠️ 这里**不能**撤销待判窗口：`UiReady` 只证明「插件活着」。
                        //   真机把插件 client.js 加个 `)))` 后它照报 onUiReady，而页面
                        //   始终停在启动页（`锚点=0`）。健康证据只认结构锚点 ≥ 1。
                        // ⚠️ 这里**不能**直接收起 —— DOM 就绪 ≠ 像素上屏。
                        //
                        // 真机实测（CDP `Page.startScreencast` 抓 WebView 内部帧）：
                        //   DOM 变成真实 UI   t=2154ms（body.innerText 已是"探索未至之境"）
                        //   屏幕真的画出 UI   t≈541s 附近，比 DOM 晚了约 1.6 秒
                        //   而旧代码在 onUiReady 里立刻收起 → 那一秒多里屏幕上是空的/启动页
                        //
                        // 所以必须等 WebView **把这一帧真正提交**再收。
                        // `postVisualStateCallback` 正是这个语义：回调触发时，
                        // 这一帧已经可以显示在屏幕上了。
                        awaitWebViewPainted { hideOverlay() }
                    }
                }
            }
        }
    }

    private fun bootEngine() {
        lifecycleScope.launch {
            try {
                engine.ensureExtracted(assetStamp)
                // ⚠️ **启动前先刷一次**：救援/隔离态是**上次**留下的，界面一起来就该
                //   让用户看到（不能等到引擎就绪 —— 救援态恰恰可能起得很慢）。
                refreshRescueBanner()
                engine.start()
                // ⚠️ **启动后再刷一次**：`start()` 内部可能刚刚发生降级（L1 二分没
                //   收敛时会把层级改成 RESCUE，见 EngineManager.maybeIsolateBadBundles），
                //   只刷一次的话横幅会停在旧层级上。
                refreshRescueBanner()
            } catch (c: CancellationException) {
                // ⚠️ **协程取消不是异常，必须原样抛出去。**
                //
                // `lifecycleScope` 绑在 Activity 生命周期上：用户退出、转屏、被系统回收
                // 时这个作用域会被 cancel，正在 `start()` 里挂起的这一刻就抛
                // `JobCancellationException: Job was cancelled; job=SupervisorJobImpl{Cancelling}@…`。
                //
                // 原先这里只有 `catch (t: Throwable)`，于是把「正常取消」记成
                // `[E] 引擎启动异常` 并 `showOverlay(..., isError = true)` ——
                // 明明是我方主动停的，日志里却是一行错误，用户还可能瞥见一块
                // 转瞬即逝的红色错误遮罩。
                // 真机证据：`20261013-233625-59822e09.zip` 的 app.log，
                // `#109 main 引擎启动异常` + `JobCancellationException`，
                // 紧接着 `#111` 引擎自己正常输出 `1 entry did not activate` ——
                // 引擎根本没死，是界面自己走了。
                //
                // 吞掉取消异常还有个更隐蔽的后果：Kotlin 协程靠异常传播来标记
                // 取消状态，吞了它会让「这个协程已被取消」这件事在语义上丢失。
                throw c
            } catch (t: Throwable) {
                Log.e(DshApplication.TAG, "引擎启动异常", t)
                showOverlay("引擎启动异常", hint = t.message, isError = true)
            }
        }
    }

    /** 内核 resetFirstFrame 必须紧挨 reload/loadUrl，由调用点保持顺序。 */
    private fun resetFirstFrameAwait() = firstFrame.reset()

    private fun awaitWebViewPainted(action: () -> Unit) = firstFrame.await(action)

    /**
     * 显示启动层并更新文案。
     *
     * @param text     阶段名（主文案）
     * @param hint     补充说明；null 则隐藏该行
     * @param progress 解包进度 0..1；null 表示当前阶段无真实进度（只显示底环）
     * @param isError  失败态：环收成红色、不显示进度
     */
    private fun showOverlay(
        text: String,
        hint: String? = null,
        progress: Float? = null,
        isError: Boolean = false,
    ) {
        bootOverlay.show(BootOverlayPresenter.State(text, hint, progress, isError))
    }

    private fun renderOverlay(state: BootOverlayPresenter.State) {
        val (text, hint, progress, isError) = state
        binding.overlay.animate().cancel()
        if (binding.overlay.visibility != View.VISIBLE) {
            binding.overlay.alpha = 1f
            binding.overlay.visibility = View.VISIBLE
        }
        binding.overlayText.text = text
        // 只有**错误态**才给点击出口：正常启动期不给 —— 那时界面还没渲染好，
        // 让用户误点一下就把启动层收掉，只会提前露出白屏。
        // 错误态下界面已经确认起不来，点一下至少能让人自己看看（或按返回退出）。
        binding.overlay.setOnClickListener(if (isError) View.OnClickListener { hideOverlay() } else null)
        binding.overlayText.setTextColor(
            if (isError) Color.parseColor("#FF8A8A") else Color.parseColor("#8FA3C0")
        )
        binding.overlayHint.text = hint.orEmpty()
        binding.overlayHint.visibility = if (hint.isNullOrBlank()) View.GONE else View.VISIBLE
        // 安全模式入口只在失败态给：正常启动期界面马上就出来了，多一个按钮只会
        // 诱人误点（而它的代价是重解包 15 秒 + 推开插件配置）。
        binding.overlaySafeMode.visibility = if (isError) View.VISIBLE else View.GONE
        binding.bootAnim.setProgress(if (isError) -1f else (progress ?: -1f))
    }

    /**
     * P6：**自动**进入安全模式（方案 §4.5）。
     *
     * 与用户点击的 [enterSafeMode] 做**同一件事**（同样的推开配置 + 重解包 +
     * 重启），差别只在文案与「谁触发的」这个事实 —— 那件事由
     * [EngineManager.shouldAutoEnterSafeMode] 打 `[rescue-auto]` 标记记进日志。
     *
     * ## 为什么文案必须和用户点的那条不一样
     *
     * 用户点的时候他知道自己在做什么；自动触发时他**什么都没点**，界面却突然
     * 说「正在进入安全模式…」，如果不解释原因，他会以为 App 坏了。
     * 所以文案里必须写清「连续 N 次没能起来，所以自动推开了插件配置」。
     *
     * ⚠️ 必须把「用户的插件配置被推开了」讲出来，而不是含糊地说「正在修复」——
     *   这是**破坏性**动作（虽然不删东西，但插件全部失效），用户有权当场知道。
     */
    private fun autoEnterSafeMode() {
        binding.overlaySafeMode.isEnabled = false
        invalidateOverlayTimeout()
        // ⚠️ **这一轮之后要重新加载界面，首帧等待必须重新武装。** 见
        //   [resetFirstFrameAwait]。少这一步就退化成「新页面 DOM 一就绪就收启动层」，
        //   中间那段白屏（实测约 1.6 秒）又回来了 —— 正是 [awaitWebViewPainted]
        //   存在要解决的那个问题。
        resetFirstFrameAwait()
        bootOverlay.reopen()
        showOverlay(
            "连续多次没能启动，正在自动进入安全模式…",
            hint = "已自动推开可能损坏的插件配置并重新解包引擎（约 15 秒）。" +
                "你的会话、凭据、工作区都不会丢；被推开的配置都改名保留着。",
        )
        lifecycleScope.launch {
            try {
                val changed = engine.enterSafeMode()
                Log.i(DshApplication.TAG, "[rescue-auto] 安全模式：改动=$changed，重启引擎")
                // 同 [enterSafeMode]：必须补一次重解包，否则这次 start() 之前的
                // ensureExtracted 已经跑过去了，引擎还是用旧的坏目录起来。
                engine.ensureExtracted(assetStamp)
                engine.start()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(DshApplication.TAG, "[rescue-auto] 安全模式启动失败", t)
                binding.overlaySafeMode.isEnabled = true
                showOverlay("安全模式启动失败", hint = t.message, isError = true)
            } finally {
                // 放开重入锁：`enterSafeMode` 已把层级记成 SAFE_MODE，判据此后
                // 不会再成立，所以这里放开是安全的（不会导致反复触发）。
                autoSafeModeInFlight = false
            }
        }
    }

    /**
     * 兜底错误码 ⇒ 把**真实原因**显示到界面上。
     *
     * ## 为什么必须有这一层
     *
     * DSH 引擎有一批**兜底错误码**：真正的失败被包成 `session/agent-busy`
     * 这类笼统的码，真因藏在 `details.reason` 里。用户在 App 上只能看到
     *
     * ```
     * prompt rejected (session/agent-busy)
     * ```
     *
     * 而实际原因可能是
     *
     * ```
     * Could not load the "sharp" module using the android-arm64 runtime
     * EACCES: permission denied, open '/data/user/0'
     * ```
     *
     * 两者的修法完全不同，但提示一模一样 ⇒ 用户报「发不了图」时**无法定位**，
     * 只能靠反复下载调试包试错。用户原话：
     * 「你知不知道我每次调试都要下载那个安装包 很浪费钱的」。
     *
     * ## 为什么落在 console 上
     *
     * 引擎的失败最终都会以一条 `console.error` 冒到浏览器侧（那是它唯一能把
     * 错误交出去的通道）。页面里的 `webview-polyfill.js` 已经把这行
     * **补全成「码 + 原因」**，所以这里只要认出来、弹给用户看即可 ——
     * 原生侧不需要理解引擎的错误结构。
     *
     * ## ⚠️ 两条守卫
     *
     * ① **同一条原因只弹一次**（[lastWebErrorToast]）：前端会重复打印同一行，
     *    不判重会连弹好几次 Toast。
     * ② **只在含「原因」时弹**：纯粹的 `prompt rejected (session/agent-busy)`
     *    没有新增信息，弹了只是噪音 —— 那本来就是界面上已经显示的原文。
     */
    private fun surfaceWebErrorReason(line: String) {
        // 只认「兜底错误码 + 补全的原因」这种形态。
        // 判据取 `原因:` 这个由 polyfill 拼进去的标记 —— 没有它就说明
        // polyfill 没能挖到真因，弹出来也没有新信息。
        if (!line.contains("原因:")) return
        val interesting = line.contains("agent-busy") ||
            line.contains("prompt rejected") ||
            line.contains("sharp") ||
            line.contains("EACCES") ||
            // ── 上传失败 ───────────────────────────────────────────────
            // 这条是「个别用户」才踩到的（某内核上 XHR 通道不可用），
            // 而且**只在非图片文件**上出现 —— 图片走的是 base64 JSON 另一条路。
            // 前端只回一句「上传失败，点击重试」，用户在手机上完全无法判断
            // 是网络、体积还是内核问题，只能反复下载调试包试错。
            // polyfill 现在会把「地址 + 字节数 + XHR/fetch 各自的失败原因」
            // 拼进这条日志，弹出来就能直接定位。
            line.contains("上传失败") ||
            line.contains("上传通道不可用")
        // ① 去重：**带时间窗**，不是永久去重。
        //    前端一次失败会连着打印多行同一原因，必须压住（否则一次失败弹
        //    七八个 Toast）；但如果永久记住，用户**第二次**遇到同一个错误就
        //    再也看不到提示了 —— 那是比不去重更坏的 bug（提示彻底失效，
        //    而且没有任何迹象）。所以只压 3 秒内的重复。
        val now = android.os.SystemClock.elapsedRealtime()
        if (line == lastWebErrorToast && now - lastWebErrorAt < 3000L) return
        lastWebErrorToast = line
        lastWebErrorAt = now
        // ② 截断：Toast 太长会直接不显示，保留前 200 字足够定位。
        val text = line.substringAfter("原因:").trim().take(200)
        runOnUiThread {
            Toast.makeText(this, "出错了：$text", Toast.LENGTH_LONG).show()
        }
        Log.i(DshApplication.TAG, "已向用户显示错误真因: $text")
    }

    /**
     * 上一条已弹过的错误真因（[surfaceWebErrorReason] 的去重状态）。
     *
     * ⚠️ 必须 `@Volatile`：只在 [onConsole] 回调里读写，而那是**内核线程**，
     *   不是主线程。少了它，去重判断可能读到过期值 —— 表现为同一错误
     *   连弹多个 Toast（去重失效），或者反过来该弹的不弹。
     */
    @Volatile
    private var lastWebErrorToast: String? = null

    /**
     * 上次弹该原因的单调时钟（毫秒）。与 [lastWebErrorToast] 一起做时间窗去重。
     *
     * ⚠️ 同上，必须 `@Volatile`：单调时钟本身不会回退，但**可见性**需要它。
     */
    @Volatile
    private var lastWebErrorAt: Long = 0L

    /**
     * 浏览器侧插件加载失败 ⇒ 自动降级到救援模式（不加载任何第三方插件）。
     *
     * 这是第四种故障形态，前三处判据（stdout `startup failed`、`typert contributor(s)
     * failed to register`、已降级仍失败）都盖不到：引擎**完全健康**，坏的是前端那半。
     *
     * 真机现场（OPPO PHY110，用户「做插件做到一半退出 App」）：引擎日志一切正常
     * （`dsh-gateway-ui: 已激活`、`已注册供应商 wb-gateway`），但前端整页变成
     * `Failed to load plugins` —— 一个插件的 `client.js` 里多了一个 `)`，而前端把
     * 所有插件的 client 半**合并成一批**下发，一个坏段让整批解析失败。
     * 救援 profile 不含任何第三方 bundle（见 [EngineManager.ensureRescueProfile]），
     * 所以界面能正常渲染、用户进得去对话，正好让 AI 把插件修好。
     *
     * ⚠️ 三条守卫缺一不可：① 只推一次（[webRescueInFlight]，前端会重复打印这句话）；
     * ② 只在 NORMAL 态推（否则横幅 `since` 每启动一次就跳一次）；③ `rescueAvailable`
     * 为 false 时不推（profile 建不出来，推过去下次启动直接失败）。
     * 推完**不设 Ready**、状态打 [EngineManager.State.Dead] 而非 `Failed` —— `Failed`
     * 会把用户引向破坏性的「安全模式」按钮，而这里只需换个 profile 重启。
     */
    private fun maybeRescueFromWebFailure(line: String) {
        // ⚠️ 判据全部在 [WebClientHealth]（2026-11-12 重写）：不再 grep 官方启动页
        //   文案 `HARNESS` —— 那是「polls=1 的快照」而不是当前状态，正常启动也会带上
        //   （真机 15:13:55 因此把健康启动降级成救援态），而用户一旦换掉启动动画 /
        //   主题 / 加载文案，真正的卡死反而永远检测不到。事故全过程与替代判据见
        //   `WebClientHealth` 的类注释与 `docs/agents/rescue-mode.md`。
        //
        //   三类事实：明确失败 ⇒ 立刻降级；健康证据（结构锚点 ≥1 最硬）⇒ 撤销待判；
        //   超时（[WebClientHealth.CLIENT_DEADLINE_MS] 内一条健康证据都没有）⇒ 到期降级。
        when (WebClientHealth.classify(line)) {
            WebClientHealth.Verdict.EXPLICIT_FAILURE -> degradeToRescue(
                reason = "浏览器侧插件加载失败（前端明确报错）",
                eventName = "web.plugin-load-failed",
                extra = mapOf("line" to line.take(200)),
            )

            WebClientHealth.Verdict.GAVE_UP -> {
                // 插件自己说「等了 9 秒仍然没看到可用界面」。这**不等于**坏了
                // （实测有 readyState 卡在 interactive 而界面可用的机型），
                // 所以只把待判窗口缩短，到期仍无健康证据才降级。
                Log.w(DshApplication.TAG, "[web-rescue] 插件自报超时兜底，缩短待判窗口: ${line.take(160)}")
                webClientWatchdog.arm(
                    delayMs = WebClientHealth.GAVE_UP_GRACE_MS,
                    why = "插件自报超时兜底后仍无健康证据",
                )
            }

            WebClientHealth.Verdict.HEALTHY -> webClientWatchdog.disarm("收到健康证据: ${line.take(120)}")

            WebClientHealth.Verdict.NONE -> Unit
        }
    }

    /**
     * 把界面切到救援模式并重启引擎。**两个入口共用**：
     *
     * ① [maybeRescueFromWebFailure] —— 前端 console 自报 / 整批 `/plugins/??` 失败；
     * ② [WebRecoveryController] 耗尽 —— 重载若干次仍拿不到首帧（前端根本没渲染出来）。
     *
     * ② 是 ① 的**兜底**：① 依赖前端把话说出来，而前端整个挂掉时它说不了话。
     * 两条路都判「引擎是好的、坏的是 client 半」，处置完全相同 —— 所以只留一份实现，
     * 免得两条路的守卫（判重/层级/可用性）各写一遍、日后改一处漏一处。
     *
     * ## 为什么救援模式能解决它
     *
     * 救援 profile **不含任何第三方 bundle**（见 [EngineManager.ensureRescueProfile]），
     * 所以那批合并 bundle 里根本不会有坏段 ⇒ 界面正常渲染 ⇒ 用户进得去对话，
     * 而对话里正好可以让 AI 把插件修好（这正是用户要的）。
     *
     * ## ⚠️ 三条守卫，缺一不可
     *
     * ① **只推一次**（[webRescueInFlight]）：前端会**重复**打印那句话（重试加载），
     *    不判重会叠起多次重启。
     * ② **只在 NORMAL 态推**：已是 RESCUE 还推没有意义，且会把横幅的 `since`
     *    刷成每次启动的时刻。
     * ③ **`rescueAvailable == false` 时不推**：那个 profile 建不出来，推过去
     *    下次启动直接失败。
     *
     * ⚠️ 推完**不设 Ready**、状态打 [EngineManager.State.Dead] 而不是 `Failed` ——
     *   `Failed` 会把用户引向**破坏性**的「安全模式」按钮（推开配置 + 重解包 15 秒），
     *   而这里引擎是好的，只需要换个 profile 重启。与 `EngineManager` 里
     *   gateway 那条降级路径的取舍完全一致。
     *
     * @return true = 已经推下降级（调用方不要再贴自己的失败提示，否则会把这轮
     *   「正在进入救援模式…」的层顶掉）。
     */
    private fun degradeToRescue(
        reason: String,
        eventName: String,
        extra: Map<String, String>,
    ): Boolean {
        // ① 判重
        if (!webRescueInFlight.compareAndSet(false, true)) return false
        // ② 只在 NORMAL 态推
        val tierNow = RescueBoot.tier().tier
        if (tierNow != RescueBoot.Tier.NORMAL) {
            Log.w(DshApplication.TAG, "[web-rescue] $reason，但层级已是 ${tierNow.id}，不再降级")
            return false
        }
        // ③ 救援 profile 必须可用
        val engine = EngineManager.get(this)
        if (!engine.rescueAvailable) {
            Log.w(DshApplication.TAG, "[web-rescue] $reason，但救援 profile 不可用，无法降级")
            webRescueInFlight.set(false)
            return false
        }

        Log.e(
            DshApplication.TAG,
            "[web-rescue] $reason（引擎健康，坏的是 client 半）—— 降级到救援模式并重启界面",
        )
        EventLog.record(eventName, extra)

        lifecycleScope.launch {
            try {
                showOverlay(
                    "有插件加载不了，正在进入救援模式…",
                    hint = "本次启动不加载任何第三方插件，界面马上就能用。" +
                        "你的会话、凭据、工作区都不会丢。",
                )
                // ⚠️ 先武装首帧等待 + 清「已收起」标记：这一轮之后要重新加载界面，
                //   同 [autoEnterSafeMode] 的两条论证。
                resetFirstFrameAwait()
                bootOverlay.reopen()
                RescueBoot.setTier(RescueBoot.Tier.RESCUE, reason)
                refreshRescueBanner()
                engine.requestRestart("$reason，切救援模式")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(DshApplication.TAG, "[web-rescue] 降级失败", t)
                webRescueInFlight.set(false)
                showOverlay("进入救援模式失败", hint = t.message, isError = true)
            }
        }
        return true
    }

    /**
     * 用户点了失败浮层上的「安全模式」：推开模型改坏的配置，然后重启引擎。
     *
     * 这是**唯一**不靠卸载重装的出路 —— 见 [EngineManager.enterSafeMode] 的 KDoc。
     * 按钮要先禁掉：重解包要 15 秒，这期间用户再点几下会叠起多次重启。
     */
    private fun enterSafeMode() {
        binding.overlaySafeMode.isEnabled = false
        // ⚠️ 顺序：先作废旧计时器、清掉"已收起"标记，再 showOverlay —— 后者在
        //   overlayDismissed 为真时会直接返回（那是防动画闪回的守卫），提示文案
        //   就贴不上去了。作废计时器是同一件事的另一半：这次也要重新加载界面。
        invalidateOverlayTimeout()
        // ⚠️ 同 [autoEnterSafeMode]：这一轮之后要重新加载界面，首帧等待必须重新武装。
        resetFirstFrameAwait()
        bootOverlay.reopen()
        showOverlay(
            "正在进入安全模式…",
            hint = "推开损坏的插件配置并重新解包引擎（约 15 秒）",
        )
        lifecycleScope.launch {
            try {
                val changed = engine.enterSafeMode()
                Log.i(DshApplication.TAG, "安全模式：改动=$changed，重启引擎")
                // ⚠️ 必须重新解包：enterSafeMode 删掉了版本戳，而这次 start() 之前的
                //   ensureExtracted 已经跑过去了（在 bootEngine 里），不补一次的话
                //   引擎还是用旧的坏目录起来。
                engine.ensureExtracted(assetStamp)
                engine.start()
            } catch (c: CancellationException) {
                // 同 bootEngine()：用户点完「安全模式」就退出/转屏时，
                // 这次取消不是「安全模式启动失败」。
                throw c
            } catch (t: Throwable) {
                Log.e(DshApplication.TAG, "安全模式启动失败", t)
                binding.overlaySafeMode.isEnabled = true
                showOverlay("安全模式启动失败", hint = t.message, isError = true)
            }
        }
    }

    /**
     * 刷新降级态常驻横幅（方案 §4.4.5，不变量 I4）。
     *
     * ## 为什么单独抽一个函数、而不是写进 `showOverlay`
     *
     * 两者的生命周期**不同**：overlay 是启动期的，会淡出；横幅要在用户整个使用
     * 过程里一直在（救援态下用户还要让 AI 慢慢修，可能十分钟）。挂在 overlay
     * 上等于「只在打不开时提示」，而用户最需要知道的恰恰是打开之后的事。
     *
     * ## 三个层级的文案
     *
     * 文案必须说清**两件事**：发生了什么、以及**现在能做什么**。只说
     * "已进入救援模式"而不说"让 AI 帮你修"，用户就只看到一个坏消息。
     *
     * ⚠️ 正常态一律 `gone`（不占一个像素）。
     */
    private fun refreshRescueBanner() {
        val st = RescueBoot.tier()
        val banner = binding.rescueBanner
        if (st.tier == RescueBoot.Tier.NORMAL) {
            banner.visibility = View.GONE
            return
        }
        val when_ = RescueBoot.stateSinceText()?.let { "（$it 起）" }.orEmpty()
        // ⚠️ 把「gateway 下线」这一种单独说出来。它和「起不来」是**两回事**：
        //   引擎起来了、界面也加载了（用户看得见），但会话/模型列表/目录选择全废。
        //   若横幅仍说「上次启动没能起来」，用户会对着一堆打不开的功能想：
        //   「不是起来了吗？」—— 正是本项目缺陷族「结论与它描述的对象不符」。
        val gw = RescueBoot.lastFailure()?.gatewayCulprit
        val gatewayLine = if (gw != null) {
            "\n启动时 typert gateway 整体下线（插件 " + (gw.ifEmpty { "（包名未解析到）" }) +
                " 注册不上），会话、模型列表与目录选择都不可用。"
        } else {
            ""
        }
        val text = when (st.tier) {
            RescueBoot.Tier.RESCUE ->
                // ⚠️ 原因不同说法必须不同：反馈 `dbee25` 的截图里原因是「界面重载 2 次仍无
                //   首帧（前端起不来）」，横幅却说「引擎没能正常启动」—— 用户被引去查引擎。
                "⚠️ 救援模式$when_\n" +
                    (if (st.reason.contains("首帧") || st.reason.contains("界面"))
                        "引擎是好的，是界面没能渲染出来（插件前端加载失败），"
                    else "引擎没能正常启动，或启动后 typert gateway 整体下线，") +
                    "已用最小配置启动（你的插件配置没被删）。$gatewayLine\n" +
                    "AI 可以帮你找出并修好那个插件 —— 直接问它就行。"
            RescueBoot.Tier.ISOLATED ->
                "⚠️ 已隔离可疑插件$when_\n" +
                    "启动时发现 ${st.removedBundles.size} 个插件会让引擎起不来，已把它们改名隔离" +
                    "（没有删除），其余插件照常工作。\n" +
                    "想让 AI 复查或恢复，直接问它就行。"
            RescueBoot.Tier.QUARANTINED ->
                "⚠️ 已隔离坏插件$when_\n" +
                    "启动时定位到 ${st.quarantinedNames.size} 个插件会让引擎起不来，" +
                    "已用官方 patch 把它们禁用（文件一个没删、其余插件照常）。\n" +
                    "想让 AI 复查或放回来，直接问它就行。"
            RescueBoot.Tier.SAFE_MODE ->
                "⚠️ 安全模式$when_\n" +
                    "已推开全部第三方插件配置并重新解包引擎。"
            RescueBoot.Tier.NORMAL -> return
        }
        binding.rescueBannerText.text = text
        banner.visibility = View.VISIBLE
    }

    /**
     * 用户点了横幅上的「退出并重试」：把层级复位成 NORMAL 然后重启引擎。
     *
     * ⚠️ **不删任何东西**（I5）。它只改 `rescue/state.json` 里的层级，于是下次
     *   启动走正常 profile。用户原来的插件配置如果被 L1 摘过，恢复它走的是
     *   `.bak-l1`（见 [showDegradedDetail] 的说明），那是**另一个显式动作** ——
     *   不能顺手做掉，否则用户点一次"退出"就同时丢掉了隔离结论，AI 再也查不到
     *   是哪个插件坏。
     */
    private fun exitDegradedMode() {
        val st = RescueBoot.tier()
        if (st.tier == RescueBoot.Tier.NORMAL) {
            refreshRescueBanner()
            return
        }
        Log.i(DshApplication.TAG, "用户请求退出降级态（当前 ${st.tier.id}）")
        RescueBoot.setTier(RescueBoot.Tier.NORMAL, "用户点了横幅上的「退出并重试」")
        engine.requestRestart("用户请求退出降级态")
    }

    /**
     * 用户点了横幅上的「上次为什么失败」：把失败详情摆出来。
     *
     * 用 `AlertDialog` 而不是浮层：这时界面是**正常可用**的，弹一个全屏浮层会把
     * 整个 GUI 盖掉（用户在救援态里正要用 AI 修，最不需要的就是界面被抢）。
     */
    private fun showDegradedDetail() {
        val st = RescueBoot.tier()
        val f = RescueBoot.lastFailure()
        val sb = StringBuilder()
        sb.append("当前层级：").append(st.tier.label).append('\n')
        RescueBoot.stateSinceText()?.let { sb.append("自 ").append(it).append('\n') }
        if (st.reason.isNotBlank()) sb.append("原因：").append(st.reason).append('\n')
        if (st.removedBundles.isNotEmpty()) {
            sb.append("\n已隔离的插件（").append(st.removedBundles.size).append(" 个）：\n")
            st.removedBundles.forEach { sb.append("  · ").append(it).append('\n') }
        }
        if (st.quarantinedNames.isNotEmpty()) {
            sb.append("\n已定向禁用的插件条目（").append(st.quarantinedNames.size).append(" 个）：\n")
            st.quarantinedNames.forEach { sb.append("  · ").append(it).append('\n') }
            sb.append("  （靠 quarantine.patch.yml 的 disabled: true 生效，")
                .append("文件一个没删）\n")
        }
        st.restorePoint?.let {
            sb.append("\n原始配置备份：").append(it)
            sb.append("（想让 AI 恢复就告诉它这个路径）").append('\n')
        }
        if (f == null) {
            val byWeb = st.reason.contains("首帧") || st.reason.contains("界面")
            sb.append(if (byWeb) "\n（界面没能渲染，引擎本身是好的）" else "\n（没有记录到启动失败详情）")
        } else {
            // ⚠️ 标题按事实分岔，不写死「启动失败」：`gatewayCulprit` 非空表示
            //   这一次引擎**起来了**（只是 gateway 废了），说「启动失败」是错的。
            val gatewayOnly = f.gatewayCulprit != null && (f.requiredDidNotActivate ?: 0) <= 0
            sb.append(if (gatewayOnly) "\n上次启动（引擎已就绪，但 gateway 下线）：\n" else "\n上次启动失败：\n")
            sb.append("  时间：").append(
                SafeDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.at)),
            ).append('\n')
            sb.append("  bootId：").append(f.bootId).append('\n')
            sb.append("  连续失败：").append(f.consecutiveFailures).append(" 次\n")
            f.gatewayCulprit?.let {
                // ⚠️ 措辞必须带「如果为空 = 没解析到」，不能只说「注册不上的插件：」
                //   后面跟一片空白 —— 那会被读成「插件名就是空的」。
                sb.append("  ⚠️ typert gateway 整体下线，注册不上的插件：")
                    .append(it.ifEmpty { "（引擎报了这句话，但包名没解析出来）" })
                    .append('\n')
                sb.append("     （会话、模型列表、目录选择都会报 " +
                    "「its strict definition was withdrawn」）\n")
            }
            f.requiredDidNotActivate?.let {
                sb.append("  引擎报「未激活的必需插件」：").append(it).append(" 个\n")
            }
            f.failedCountMismatch?.let {
                sb.append("  ⚠️ 引擎报的失败数与实际列出条数不一致：").append(it).append('\n')
            }
            if (f.failedPlugins.isNotEmpty()) {
                sb.append("  引擎列出的失败插件（").append(f.failedPlugins.size).append(" 个，").append(
                    "⚠️ 这是引擎列出的，**不一定是元凶** —— 元凶常常不在这个名单里）\n",
                )
            }
        }
        sb.append("\n想修的话，直接问 AI：把上面这些告诉它就行。")
        runOnUiThread {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("启动失败详情")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
            // ⚠️ 「放回来」必须是**独立按钮 + 二次确认**，不能并进「知道了」：
            //   它会让用户**回到那个打不开的状态**，是一个可能把人再坑一次的
            //   动作。现有的「退出并重试」刻意不删任何东西（I5），这里同理 ——
            //   动作与后果都要显式。
            if (st.quarantinedNames.isNotEmpty()) {
                builder.setNeutralButton("放回被禁用的插件") { _, _ ->
                    confirmRestoreQuarantine(st.quarantinedNames.size)
                }
            }
            builder.show()
        }
    }

    /**
     * 二次确认后清空定向隔离列表并重启（把插件**放回来**）。
     *
     * ⚠️ 这是唯一一个「会让用户回到坏状态」的动作，所以：
     *   ① 必须二次确认，且文案要说清**后果**（可能又打不开）；
     *   ② 只清 `quarantinedNames`，**不动** `removedBundles` 与 `.bak-l1`
     *      —— 那是 L1 另一条路径的产物，混在一起清会让两种隔离的恢复语义混淆。
     *   ③ 层级同时回 NORMAL，否则 `maybeIsolateBadBundles` 的闸门①会把
     *      下一次启动挡在 QUARANTINED 里。
     */
    private fun confirmRestoreQuarantine(count: Int) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("放回 $count 个被禁用的插件？")
                .setMessage(
                    "放回后它们会重新加载。如果其中一个正是导致 App 打不开的那个，" +
                        "下次启动可能又会失败（届时会自动重新禁用，但需要再等一轮）。\n\n" +
                        "建议先让 AI 看过日志、确认插件已修好再放回。",
                )
                .setPositiveButton("确认放回") { _, _ ->
                    RescueBoot.clearQuarantine()
                    RescueBoot.setTier(
                        RescueBoot.Tier.NORMAL,
                        "用户显式放回被禁用的插件",
                    )
                    Log.i(DshApplication.TAG, "用户放回被禁用的插件（$count 个），重启引擎")
                    engine.requestRestart("用户放回被禁用的插件")
                    refreshRescueBanner()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 淡出并收起启动层。
     *
     * ⚠️ 必须淡出，不能直接 `GONE`：这一层是不透明的，硬切会让「深色启动页 →
     *   已渲染的 WebView」出现一次明显的闪白/闪黑。淡出同时让 WebView 的
     *   首帧在下面先画好，交接才自然。
     *
     * ⚠️ 淡出期间 `overlayDismissed` 立刻置位，防止后续状态回调（引擎重启、
     *   `uiStage` 重复推送）把动画又弹回来。
     */
    private fun hideOverlay() {
        if (!bootOverlay.dismiss()) return
        // 启动层收起 = 页面底色开始可见，此时才把系统栏交给页面底色
        // （之前一直保持 themes.xml 的深色，与深色启动页一致）。
        // 用最近一次上报的底色；还没收到过就保持主题里的深色不动。
        // ⚠️ 即使还没有页面底色也要调一次：AI 可能已经钦定过颜色，那两条栏
        //   本来就该在启动层收起后立刻显形（applySystemBarColors 内部会判空）。
        // ⚠️ 走 syncSystemBars：配置里可能带着闪烁序列，得从这一枪就开始闪。
        syncSystemBars()
        binding.overlay.animate()
            .alpha(0f)
            .setDuration(OVERLAY_FADE_OUT_MS)
            .withEndAction {
                binding.overlay.visibility = View.GONE
                binding.overlay.alpha = 1f
            }
            .start()
    }

    /**
     * 打开运行日志界面（启动层上的入口）。
     *
     * ## 为什么用 `runCatching` 而不是直接 startActivity
     *
     * 这个按钮存在的场景是「App 已经不正常了」。而 Activity 启动在异常状态下
     * 真的会失败（`ActivityNotFoundException` 极少见，但 `TransactionTooLarge`
     * 与低内存下的启动失败是真实存在的）。失败时**必须给用户回执** ——
     * 「点了没反应」是最难查的一类反馈，比报错还糟。
     *
     * ## 为什么不加 `FLAG_ACTIVITY_NEW_TASK`
     *
     * 本界面与 MainActivity 同进程同任务栈，加 NEW_TASK 会让「关闭」退到桌面
     * 而不是退回启动层 —— 用户从崩溃浮层点进来看完日志，期望是回到浮层，
     * 而浮层上还有「安全模式」这条路要走。
     *
     * @param from 调用来源（`overlay` / `settings`），只进日志，用于排查
     *   「用户到底从哪条路进来的」。
     */
    private fun openLogViewer(from: String) {
        Log.i(DshApplication.TAG, "打开日志界面（来源 $from）")
        EventLog.record("logs.open", mapOf("from" to from))
        runCatching { startActivity(LogViewerActivity.intent(this)) }
            .onFailure {
                Log.e(DshApplication.TAG, "打开日志界面失败", it)
                Toast.makeText(
                    this,
                    "无法打开日志界面：${it.message ?: it.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun armOverlayTimeout() = bootOverlay.arm()

    /** 页面切换或进入终态时作废上一轮等待。 */
    private fun invalidateOverlayTimeout() = bootOverlay.invalidate()

    // ══ 悬浮气泡：注入通道 ══════════════════════════════════════════════
    //
    // 气泡里的输入框是**原生 EditText**，而真正的会话输入框是页面里的 Lexical
    // 编辑器（React 组件）。两者之间只能靠注入 —— 没有别的接缝：
    //   · 引擎的 HTTP API 不接受"替我发一条 prompt"（prompt 走 WebSocket RPC，
    //     还要带会话 id、附件、client-request 外壳，自己拼等于重写一个前端）
    //   · 直接改 React state 拿不到 fiber（跨 bundle，闭包里的 setState 不可达）
    //
    // 所以走 DOM 事件：把文本"打字"进官方 composer，再点它的发送按钮 ——
    // 官方前端完全感知不到区别，后续所有流程（附件、上下文、会话记录）都照旧。

    /** Activity 强持端口，进程控制器仅弱引用；销毁只解除当前 owner 的绑定。 */
    private val bubblePanelActions: BubblePanelActions by lazy {
        object : BubblePanelActions {
            override fun sendText(text: String): Boolean =
                !isFinishing && !isDestroyed && ::web.isInitialized && sendToComposer(text)

            override fun stopRun(): Boolean =
                !isFinishing && !isDestroyed && ::web.isInitialized && stopComposerRun()

            override fun openApp() {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ))
                }
            }
        }
    }

    private fun installBubbleBridge() {
        FloatingBubble.attachPanelActions(this, bubblePanelActions)
    }

    /**
     * 装好 UI 调试工具的原生侧。
     *
     * ## 这是一条什么通道
     *
     * 模型跑在 **Node 进程**里，看不到 DOM，也就没法自己操作这个 App 的界面。
     * 这条通道把「看界面 / 点按钮 / 填输入框」变成模型可调用的工具：
     *
     * ```
     * ui 工具(插件) ──写 ui-cmd.json──▶ UiProbeBridge ──evaluateJavascript──▶ 页面探针
     *          ◀──读 ui-result.json────            ◀────JSON 结果──────────┘
     * ```
     *
     * ## 为什么几何要用 lambda 现取，而不是存一份
     *
     * ⚠️ Activity 重建（转屏、从后台回来）会换掉整个 WebView 与 `binding`。
     *   存一份 `View` 引用会让坐标算在一具"尸体"上 —— 而那种错误的表现是
     *   「AI 点了，但点到了别的地方」，比直接失败难查得多。所以传取值函数。
     *
     * [UiProbeBridge.attach] 替换宿主代际；销毁时按 owner 解除，旧 Activity 不会撤掉新宿主。
     */
    private fun installUiProbeBridge() {
        UiProbeBridge.attach(
            owner = this,
            dshHome = engine.dshHomeDir,
            assets = assets,
            provider = {
                // provider 与宿主操作均由 UiProbeBridge 在主线程按代际调用。
                if (isFinishing || isDestroyed || !::web.isInitialized) null else object : UiProbeBridge.UiProbeHost {
                    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
                        web.evaluateJavascript(script, callback)
                    }

                    override fun screenGeometry(): UiProbeBridge.ScreenGeometry? {
                        val view = if (::web.isInitialized) web.view else return null
                        if (view.width <= 0 || view.height <= 0) return null
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val dm = resources.displayMetrics
                        return UiProbeBridge.ScreenGeometry(
                            screenX = loc[0],
                            screenY = loc[1],
                            widthPx = view.width,
                            heightPx = view.height,
                            density = dm.density,
                            // 视口尺寸由探针在结果里回报；这里给 0 当哨兵，
                            // `attachScreenCoords` 会用结果里的真实值覆盖。
                            viewportWidthCss = 0.0,
                            viewportHeightCss = 0.0,
                        )
                    }

                    override fun reload(): Boolean = requestSafeReload()
                }
            },
        )
    }

    /**
     * 装好 **AI 浏览器**的原生侧。
     *
     * ```
     * browser 工具(插件) ──写 browser-cmd.json──▶ BrowserBridge ──▶ AiBrowserView
     *              ◀──读 browser-result.json────          ◀── 页面结果 ──┘
     * ```
     *
     * 与 [installUiProbeBridge] 同构，但有两点不同：
     * ① 浏览器是**独立 WebView**，不受官方页面状态影响；
     * ② 它可能**还没被创建** —— `browserOrNull()` 会按需建出来并显示，
     *    所以模型说「打开 example.com」时用户会看到浏览器自己弹出来。
     *
     * ⚠️ [com.dsh.android.browser.BrowserBridge.attach] 幂等，Activity 重建时重复调用安全，
     *   且正好把 provider 换成新实例。
     */
    private fun installBrowserBridge() {
        com.dsh.android.browser.BrowserBridge.attach(
            owner = this,
            home = engine.dshHomeDir,
            host = {
                // ⚠️ 未初始化时返回 null 而不是抛 —— 轮询线程抛异常会直接死掉
                if (isFinishing || isDestroyed) null else object : com.dsh.android.browser.BrowserBridge.BrowserHost {
                    override fun browserOrNull(): com.dsh.android.browser.AiBrowserView? {
                        // ⚠️ **AI 调工具时只静默准备，不抢用户的屏幕。**
                        //
                        // 这里原来调 `openAiBrowser()`，它会 `requestBrowserPane()`
                        // + `notifyBrowserPane(true)` ⇒ 每调一次 browser 工具，
                        // 侧栏就整个弹出来盖住对话。用户原话：「每次 AI 在调用
                        // 浏览器工具的时候，侧边栏都会自动弹出霸占屏幕，导致用户
                        // 没有了自己的空间」。
                        //
                        // 换成 `openAiBrowserQuietly()`：视图照常可用（AI 要操作），
                        // 但不发 reveal、不抢 z 序。用户想看时点侧栏 tab 即可，
                        // 那条路仍走 `openAiBrowser()`。
                        openAiBrowserQuietly()
                        /*
                         * 告诉视图「现在是 AI 在用」—— 它据此在截图时
                         * **不请求展开侧栏**（见 `AiBrowserView.silentForTool`）。
                         *
                         * 不置真的话，AI 每调一次 `screenshot` 工具，
                         * `awaitEmbedded` 就会 `onRequestEmbed()` 把侧栏弹出来。
                         */
                        aiBrowser?.silentForTool = true
                        return aiBrowser
                    }

                    override fun isBrowserVisible(): Boolean = isAiBrowserVisible()

                    /**
                     * AI 的一次工具调用结束 ⇒ 安排把静默浏览器收走。
                     *
                     * 不收的话那个 WebView 会一直挂在屏幕上（真机实测过），
                     * 用户切回来看到的还是「右半屏是网页」。
                     */
                    override fun onToolDone() {
                        /*
                         * ⚠️ 这里**不再自动收起浏览器**。
                         *
                         * 之前「AI 用完就隐藏」是因为浏览器曾以独立窗/不可见形态
                         * 存在，留着会挡屏幕。现在浏览器始终内嵌在侧栏面板里
                         * （用户要求），面板什么时候关由用户决定 —— 自动把它
                         * 收掉反而会让用户正看着的页面突然消失。
                         *
                         * 只负责收掉状态胶囊。
                         */
                        hideActivityPillSoon()
                    }

                    /**
                     * AI 正在操作浏览器 ⇒ 在对话页顶部显示状态胶囊。
                     *
                     * 为什么需要这个：静默模式下 WebView 是 INVISIBLE，
                     * [com.dsh.android.browser.AiBrowserView] 内部那个胶囊是
                     * WebView 的子 View，跟着一起看不见了 ⇒ 用户在对话页
                     * **完全没有任何反馈**，只能干等。
                     *
                     * 用户原话：「现在我们的浏览器不是在后台默默运行吗……
                     * 那我们又少了一些反馈，我觉得可以把浏览器内的那个实时
                     * 显示操作状态的悬浮气泡，在我们对话界面也实时显示一下」。
                     */
                    override fun onToolActivity(text: String) = showActivityPill(text)
                }
            },
        )
    }

    /**
     * 把一段文本送进页面的官方 composer，并触发发送。
     *
     * **必须在主线程调用**（`evaluateJavascript` 的要求）—— 气泡的按钮回调本来
     * 就在主线程，这里不额外切。
     *
     * 分两步而不是一步，是因为**页面状态更新是异步的**：`beforeinput` 注入完，
     * 发送按钮的 `disabled` 属性要等 React 下一次渲染才变。实测约 450ms。
     * 同步点按钮会点到 disabled 的那个，表现为"文字进去了但没发出去"。
     */
    private fun sendToComposer(text: String): Boolean {
        val quoted = JSONObject.quote(text)

        web.evaluateJavascript(BUBBLE_INSERT_JS.replace(BUBBLE_TEXT_SLOT, quoted)) { result ->
            Log.i(DshApplication.TAG, "气泡注入结果: $result")
            // ⚠️ 找不到 composer 时必须让用户知道。静默失败的话，气泡会把输入框
            //   清空、看起来"发出去了"，而 App 里其实一个字都没进去。
            if (result != null && result.contains("NO_INPUT")) {
                Toast.makeText(this, "输入框未就绪，请打开 App 重试", Toast.LENGTH_LONG).show()
            }
        }

        bubbleHandler.postDelayed({
            web.evaluateJavascript(BUBBLE_SEND_JS) { result ->
                Log.i(DshApplication.TAG, "气泡发送结果: $result")
            }
        }, BUBBLE_SEND_DELAY_MS)

        return true
    }

    /** 气泡的延时任务（注入后点发送）。独立于 ime/overlay 两个 handler，互不干扰。 */
    private val bubbleHandler = Handler(Looper.getMainLooper())

    /**
     * 中断当前这一轮（气泡里的「停止」）。
     *
     * 页面侧会先验主按钮此刻是不是「停止生成」—— 不是就拒绝，避免把草稿误发出去。
     */
    private fun stopComposerRun(): Boolean {
        web.evaluateJavascript(BUBBLE_STOP_JS) { result ->
            Log.i(DshApplication.TAG, "气泡停止结果: $result")
            if (result != null && result.contains("NOT_RUNNING")) {
                Toast.makeText(this, "AI 当前没在运行", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    override fun onDestroy() {
        // 引擎跟随进程存活，这里不停止，避免转屏/重建时反复冷启动。
        // ⚠️ 先撤掉系统栏配色的窗口宿主引用：留着它，AI 后续下发配置会对着
        //   一个已销毁的 Activity 调 setStatusBarColor（静默无效）。
        SystemBarAppearance.detach(this)
        UiProbeBridge.detach(this)
        com.dsh.android.browser.BrowserBridge.detach(this)
        browserPane.close()
        dismissAnnotate()
        // ⚠️ 闪烁的帧是 postDelayed 排的，不清会在 Activity 销毁后继续打窗口属性
        //   （那些帧还持有旧 window，轻则无效、重则抛异常）。
        flashSequence.stop()
        permissionQueue.close()
        imeLayout.close()
        bootOverlay.close()
        activityPill?.close()
        firstFrame.close()
        // ⚠️ 返回键探针的兜底定时器也要清：留着它会在 Activity 销毁后调
        //   moveTaskToBack（对着已销毁的 Activity）。世代号自增让残留回调失效，
        //   这里再显式移除一次，双保险。
        pageBack.close()
        backHandler.removeCallbacksAndMessages(null)
        // ⚠️ 渲染进程恢复的退避定时器同样要清：留着它会在 Activity 销毁后
        //   继续重载一个已经拆掉的 Web 视图。
        webRecovery.close()
        rendererHandler.removeCallbacksAndMessages(null)
        FloatingBubble.detachPanelActions(this)
        bubbleHandler.removeCallbacksAndMessages(null)
        // ⚠️ 内核的销毁各不一样（WebView 要 destroy()，GeckoView 要关 session、
        //   拆 WebExtension），交给 [WebHost]。必须在 super 之前 ——
        //   super.onDestroy() 之后 binding 里的视图树已经被拆掉。
        if (::web.isInitialized) web.destroy()
        // AI 浏览器是**第二个 WebView 实例**，不归 [WebHost] 管 —— 必须单独销毁，
        // 否则它的渲染进程与 Cookie 存储会一直挂着（每个 WebView 几十 MB）。
        // ⚠️ 放在 web.destroy() 之后：两者共享同一个 WebView 数据目录，
        //   先销毁"非官方"的那个，避免数据目录句柄的释放顺序出问题。
        aiBrowser?.let {
            it.visibility = View.GONE
            it.destroy()
        }
        aiBrowser = null
        // destroy() 之后 WebView 就关了，内存里没落盘的 Cookie 会直接丢。
        // 必须在销毁**之后**再 flush 一次（此时内核已经把最后的 Cookie 写进
        // CookieManager，正是 flush 的时机）。
        runCatching { android.webkit.CookieManager.getInstance().flush() }
        // ⚠️ **正常退出标记**：这是区分「崩溃」与「被系统杀掉」的唯一依据。
        //
        // 本 App 的 `targetSdk = 28`，用不了 API 30 的 `ApplicationExitInfo`
        // （那个 API 能直接告诉你上次为什么死），所以只能自己记：能走到
        // onDestroy 且确实是在收尾（`isFinishing`）就说明是好好关掉的。
        //
        // ⚠️ **这个判据是有漏报的，必须知道**：
        //   用户按 Home 退到后台、随后被 LMK 回收 / 被 ROM 冻结 / 断电 ——
        //   这几种都**不会**走到 onDestroy，于是下次启动会看到「上次非正常结束」。
        //   但那**不是崩溃**。
        //
        //   所以诊断包里那句话刻意写的是「上次非正常结束（无堆栈；可能是崩溃/
        //   被系统回收/被 ROM 冻结/断电）」而不是「上次崩溃」—— 见
        //   [com.dsh.android.log.BootState] 的诚实分级说明。
        //
        //   取舍是**宁可多报也不漏报**：漏掉一次真崩溃，用户拿到的日志就说不出
        //   问题；多报一次「非正常结束」，分析者看一眼有没有崩溃现场文件就能排除。
        if (isFinishing && !isChangingConfigurations) {
            EventLog.record("activity.destroy")
            BootState.markCleanExit("activity-destroyed")
        }
        super.onDestroy()
    }

    // ══ 悬浮气泡：仅在 App 退到后台时显示 ══════════════════════════════
    //
    // 用 onStart/onStop 而不是 onResume/onPause：后者在弹权限对话框、拉下通知栏、
    // 甚至息屏时都会触发，气泡会频繁闪。onStop 才是"用户真的离开了这个界面"。
    //
    // ⚠️ 转屏也会走 onStop→onStart，但那是**配置变更**，不是退到后台 ——
    //   不排除的话气泡会在转屏瞬间闪一下。

    override fun onStart() {
        super.onStart()
        FloatingBubble.onAppForeground()
        // 通知引擎「回来了」：远程插件据此立刻重连 SSE，不必干等退避到期。
        //
        // 用户反馈（两条）：「手机悬浮窗没法对话，必须点开前台消息才能发送出去
        // 并收到回复」。根因是切后台时系统冻结了 JS 定时器，插件的重连等待
        // 永远不会到期 ⇒ 后台期间电脑推来的消息全丢 ⇒ 切回来还要等最多 15 秒。
        //
        // ⚠️ `runCatching` 是必须的：这是附属功能，引擎没就绪/插件没装都属正常，
        //    绝不能因为它让 `onStart` 抛异常。方法内部已把网络请求放到后台线程，
        //    这里不会阻塞主线程。
        runCatching { EngineManager.get(this).notifyForeground() }
    }

    /**
     * 退到后台**之前**先落盘 Cookie。
     *
     * ⚠️ 为什么光有 `onStop` 不够：**覆盖安装（`adb install -r` / 装新 APK）
     *    会把进程直接 SIGKILL**，`onStop` 根本来不及执行 —— 用户实测「装完
     *    v44 登录，升到 v45 就掉了」正是这个原因。`onPause` 比 `onStop` 早，
     *    在切后台、锁屏、下拉通知栏时都会走到，能多抢一次落盘机会。
     *
     * ⚠️ 但 `onPause` 也**不保证**在 SIGKILL 前跑到 —— 所以真正的保障是
     *    「每次操作完就 flush」（见 BrowserBridge），这里只是多一道保险。
     */
    override fun onPause() {
        super.onPause()
        runCatching { android.webkit.CookieManager.getInstance().flush() }
    }

    override fun onStop() {
        super.onStop()
        // ⚠️ **登录态丢失的关键一处**：WebView 的 Cookie 默认只活在内存里，
        //    只有 `CookieManager.flush()` 才落盘。而 AJAX 式登录（DeepSeek、
        //    GitHub 都是）是在 `onPageFinished` **之后**才写 Cookie 的 ——
        //    那个回调里的 flush 早就跑完了。App 一被系统杀掉，这份 Cookie
        //    就没了，用户看到的现象正是「刚才明明登录了，重启后又要登录」。
        //    退到后台是最后一个可靠时机，必须再 flush 一次。
        //    （放在 isChangingConfigurations 判断之前：转屏也要落盘。）
        runCatching { android.webkit.CookieManager.getInstance().flush() }
        if (isChangingConfigurations) return
        FloatingBubble.onAppBackground(this)
    }

    internal companion object {
        /** 键盘高度缓存的 SharedPreferences 文件名。 */
        const val IME_PREFS = BubbleImePreferences.PREFS

        /** 兼容旧宿主 API；唯一计数源由悬浮球进程控制器拥有。 */
        @JvmStatic
        val tapRefreshSeq: Int get() = FloatingBubble.tapRefreshSequence()

        @JvmStatic
        fun bumpTapSeq() = FloatingBubble.recordTapRefresh()

        /**
         * Shizuku 授权请求码。
         *
         * ⚠️ 只用于 Shizuku 自己的回调（`onRequestPermissionResult`），与 Android
         *   运行时的 `requestPermissions` 请求码是**两套独立体系**，不要混用。
         */
        const val SHIZUKU_REQUEST_CODE = 0x5A17

        /** Shizuku 官方发布页。放常量而不是写在方法里：它是要给用户看的 URL。 */
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

        /**
         * 键盘高度缓存的 key（按朝向分）。
         *
         * ⚠️ 悬浮气泡也要读这个值（它拿不到 IME insets，只能靠"输入框聚焦 ⇒
         *   键盘要来"来预留空间），所以做成 `Context` 入参的静态函数两边共用 ——
         *   写死两处的话，将来改 key 必然漏改一边，症状是气泡预判高度永远取不到值。
         */
        fun imePrefsKeyOf(context: Context): String = BubbleImePreferences.keyFor(context)

        /** 启动层淡出时长（ms）。太快像硬切，太慢显得拖沓。 */
        const val OVERLAY_FADE_OUT_MS = 420L

        /**
         * WebView 内核的最低主版本号。
         *
         * DSH 官方前端的入口 bundle 里用了 **class static block**（`static { … }`），
         * 那是 **Chromium 94** 才支持的**语法**。语法补不了 ——
         * `webview-polyfill.js` 只能补运行时 API（缺函数时挂一个），
         * 补不了解析期语法：整份 chunk 在解析阶段直接失败，一行都不执行。
         *
         * 真机现象正是如此（用户反馈的 45 秒录屏，vivo Y52s / Android 10）：
         * HTML 到了（`onPageFinished` 触发，启动层走到「正在准备界面…」）、
         * CSS 也生效了（页面是官方前端的白底），但 `onUiReady` 永不到达
         * —— React 根本没挂载。旧实现曾在 12 秒后收起启动层，
         * 露出来的就是一片白屏。
         *
         * 所以把门槛显式化：低于它就不再静默白屏，而是把原因摆到启动层上。
         * 取 94（static block 的引入版本）而不是更高：内核 94 上还缺的
         * `reportError`（95）是 API，由 polyfill 补（见 `webview-polyfill.js`）。
         */
        const val MIN_WEBVIEW_MAJOR = 94

        /**
         * 降级前端生效后的实际门槛。
         *
         * `EngineManager.ensureWebViewCompat()` 会把 bundle 换成按 **chrome73** 降级
         * 编译的版本（class static block 被展开、逻辑赋值被改写），于是门槛降到 73。
         *
         * ⚠️ **为什么从 74 挪到 73**：服务器上真实用户包里有一台三星（`y2q`，
         *   `com.android.webview 73.0.3683.120`）卡在 **73**，而门槛是 74 ⇒
         *   `前端无法解析，跳过加载` ⇒ 启动层直接报「系统 WebView 内核过旧」，
         *   界面**永远打不开**（该设备 4 个包全是这个结局，含最新的 (21) 包）。
         *   差一级就卡死一个人，而 73 这个目标 esbuild 完全接得住（实测见下）。
         *
         * ⚠️ 73 能收：`tools/build-webview-compat.ps1 -Target chrome73` 的两道门禁
         *   全过 —— 71 个 js 产物语法复检通过，运行时 API 审计**缺口 0**。
         *   再往下就不行了：`queueMicrotask` 的门槛是 **Chrome 71**，而它已在
         *   跳过名单里（理由正是「≤ 目标内核，原生支持」），降到 71 以下时
         *   polyfill 补不了它（它是宿主调度原语）。所以 73 是「再降无益」的位置。
         *
         * ⚠️ **降级范围是「dist + 全部插件的 client bundle」**（v2 起）。只降 dist
         *   是不够的 —— 实测 71 个插件 bundle 里 68 个在 chrome74 下有语法缺口，
         *   而它们与 dist 是**各自独立编译**的。只覆盖 dist 时症状不是白屏，而是
         *   「某个插件静默不生效 / 某块 UI 永远空白」（线上真实反馈：
         *   `groups.toSorted is not a function` 打不开模型选择器、
         *   「模型」设置页整页空白）。
         *
         * ⚠️ 74 之后引入的 **API**（`Object.hasOwn` 93、`.at()` 92、
         *   `reportError` 95、`structuredClone` 98、`Intl.Segmenter` 87 …）
         *   由 `webview-polyfill.js` 兜底，两者配合才完整。
         *
         * ⚠️ 降级**没打上**时不能放行：那时跑的是官方 bundle，
         *   门槛必须退回 [MIN_WEBVIEW_MAJOR]（见 `effectiveMinWebViewMajor`）。
         */
        const val MIN_WEBVIEW_MAJOR_COMPAT = 73

        // ══ 悬浮气泡：注入脚本 ══════════════════════════════════════════

        /** 文本占位符。用 `replace` 而不是字符串拼接，避免文本里的引号破坏 JS 语法。 */
        const val BUBBLE_TEXT_SLOT = "__DSH_BUBBLE_TEXT__"

        /**
         * 问页面「系统返回键该退哪一层」。
         *
         * 页面侧在 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` §12 挂了
         * `window.__DSH_MOBILE_BACK__`，它返回布尔值：true = 已消费（原生不退出）。
         *
         * ⚠️ 桥不存在时返回 `false` ⇒ 原生直接退出。这是**故意的**：
         *   插件没激活时页面里没有任何自建层级，退出正是正确行为；若反过来
         *   当作"已消费"，返回键会彻底失效，用户被困在 App 里。
         *
         * ⚠️ 整段包 try/catch：页面里任何异常都该退化成"没得关"，而不是让
         *   `evaluateJavascript` 拿到 `null` —— 那虽然也走退出分支，但日志里
         *   看不到原因。
         */
        val BACK_PROBE_JS = """
            (function () {
              try {
                var fn = window.__DSH_MOBILE_BACK__;
                if (typeof fn !== 'function') { return false; }
                return fn() === true;
              } catch (e) {
                console.warn('[dsh-android] 返回键处理异常: ' + (e && e.message));
                return false;
              }
            })()
        """.trimIndent()

        /**
         * 注入后等多久再点发送（ms）。
         *
         * ⚠️ 这个延时**不能省**：`beforeinput` 是同步派发的，但 React 要等下一次
         * 渲染才会把发送按钮的 `disabled` 去掉。实测约 450ms，取 500 留余量。
         * 提前点会点到 disabled 的按钮 —— 症状是"文字进去了但没发出去"。
         */
        const val BUBBLE_SEND_DELAY_MS = 500L

        const val BACK_EXIT_WINDOW_MS = 2000L



        /**
         * 允许**自动**重启引擎的次数上界。
         *
         * 超过之后不再静默重启，改为把问题摆到界面上并提示「安全模式」。
         * 理由见 [engineRestarts] 的说明：单次死亡是系统回收（配置没问题，
         * 原样重启就对），反复死亡才说明配置有问题。
         */
        const val MAX_ENGINE_RESTARTS = 2

        /**
         * 「连续死亡」的判定窗口（ms）。
         *
         * 两次死亡间隔超过它 ⇒ 计数归零、重新开始数。依据是「引擎跑了好一会儿
         * 才死」与「刚起来就死」是**两种不同的事」：前者多半是系统在内存压力下
         * 回收（正常，重启即可），后者才说明配置有问题。
         *
         * 取 90 秒：远大于引擎正常启动耗时（实测 7~10 秒，首次解包 15 秒），
         * 所以「起来就死」必定落在窗口内；又远小于用户跑一次长任务的时间。
         */
        const val ENGINE_RESTART_WINDOW_MS = 90_000L

        /**
         * 把文本写进官方 composer。
         *
         * ## 为什么是 `beforeinput` 事件
         *
         * composer 是 **Lexical** 编辑器（`data-lexical-editor="true"`），不是
         * textarea/input —— `value` 属性根本不存在，`innerText` 也只是渲染结果，
         * 直接改它 Lexical 的内部 model 完全不认。
         *
         * 真机逐一实测过五条路，只有这条全绿：
         *
         * | 方法 | 结果 |
         * |---|---|
         * | `execCommand("insertText")` | 单行可以，**换行符被吃掉**，且清不掉旧内容 |
         * | `execCommand("insertLineBreak")` | 文字错乱 |
         * | `execCommand("selectAll")` + `delete` | Lexical 完全不响应 |
         * | 真实 Ctrl+A + Backspace（CDP 键盘事件） | 同样无效 |
         * | 直接构造 Lexical 节点 | `Minified Lexical error #195`（跨 bundle 版本不匹配） |
         * | **`beforeinput` 事件** | **单行/多行/引号/反斜杠/emoji/超长文本全部正确** |
         *
         * Lexical 自己在 `contenteditable` 上监听 `beforeinput`，读到
         * `inputType: "insertText"` + `data` 就照常走它的插入流程 —— 所以这条路径
         * 等价于"用户用输入法打了这些字"，是最接近真实的注入方式。
         *
         * 清空走 `__lexicalEditor.update(() => root.clear())`：这是 Lexical 自己
         * 暴露在 DOM 节点上的编辑器实例，用它做事务是**官方 API**，不是 hack。
         *
         * ⚠️ 返回时读到的 `innerText` 是**上一帧**的内容（Lexical 异步渲染），
         *   别拿它判断成败 —— 那正是把发送拆成两步的原因。
         */
        val BUBBLE_INSERT_JS = """
            (function () {
              var t = $BUBBLE_TEXT_SLOT;
              var ce = document.querySelector('.uV2eYG_input');
              if (!ce) { return 'NO_INPUT'; }
              ce.focus();
              var ed = ce.__lexicalEditor;
              if (ed) {
                try {
                  ed.update(function () {
                    var r = ed.getEditorState()._nodeMap.get('root');
                    if (r && r.clear) { r.clear(); }
                  });
                } catch (e) { /* 清空失败不致命，下面照常插入 */ }
              }
              if (t) {
                ce.dispatchEvent(new InputEvent('beforeinput', {
                  inputType: 'insertText',
                  data: t,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                }));
              }
              var btn = document.querySelector('.uV2eYG_primary');
              return JSON.stringify({
                len: ce.innerText ? ce.innerText.length : 0,
                sendDisabled: btn ? btn.disabled : null
              });
            })()
        """.trimIndent()

        /**
         * 点官方 composer 的发送按钮。
         *
         * ⚠️ 用 `disabled` 先判一次：按钮在无内容时是 `disabled` 的，点它不报错、
         *   也不发送 —— 静默失败最难查。这里如实把状态回报给日志。
         */
        val BUBBLE_SEND_JS = """
            (function () {
              var btn = document.querySelector('.uV2eYG_primary');
              if (!btn) { return 'NO_SEND_BTN'; }
              if (btn.disabled) { return 'SEND_DISABLED'; }
              btn.click();
              return 'SENT';
            })()
        """.trimIndent()

        /**
         * 中断当前这一轮（官方主按钮在运行中会变成「停止生成」）。
         *
         * ⚠️ **必须先验 `aria-label`**：同一个 `.uV2eYG_primary` 元素空闲时是
         *   「发送消息」。盲点它会把用户输入框里的草稿直接发出去 —— 那是破坏性的
         *   误操作，不是"没反应"那么轻。
         */
        val BUBBLE_STOP_JS = """
            (function () {
              var btn = document.querySelector('.uV2eYG_primary');
              if (!btn) { return 'NO_BTN'; }
              var label = btn.getAttribute('aria-label') || '';
              if (label.indexOf('停止') < 0 && !/stop/i.test(label)) {
                return 'NOT_RUNNING';
              }
              btn.click();
              return 'STOPPED';
            })()
        """.trimIndent()
    }
}
