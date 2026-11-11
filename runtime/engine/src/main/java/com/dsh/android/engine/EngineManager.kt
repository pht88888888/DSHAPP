package com.dsh.android.engine

/*
 * EngineManager —— 引擎装配顺序、进程生命周期、健康与故障恢复。
 *
 * 按职责找入口：
 *   归档与平台兼容     EngineAssetInstaller / EngineCompatibilityInstaller
 *   运行依赖与工具     EngineRuntimeDependencies / EngineToolInstaller / EngineFilePolicy
 *   出厂插件与登记     BuiltinPluginBundles / BuiltinPluginBootstrap
 *   引擎文件补丁       EngineFilePatches（锚点与替换），本类保留调用顺序与结果日志
 *   配置文件与补缺     ProfileOverlayWriter / ProfileDefaultsInstaller / KeyPoolProfileConfig
 *   起停与状态         start() / stop() / State / UiStage
 *   启动快照与探测     EngineLaunchSpec / EngineProbeRunner
 *   健康与降级决定     checkHealth() / preflight() / maybeIsolateBadBundles()
 *   隔离文件事务       PluginIsolationTransaction / PluginSyntaxPreflight
 *   内存采样线程       EngineMemoryMonitor
 *   常量与配置         engine.constants（EngineConstants.kt）
 *
 * 新的资产安装职责放入专职组件；进程、救援与降级状态由本类持有。
 * 安装依赖失败仍沿用各组件现有日志和降级行为，不改变启动顺序。
 */
import android.app.ActivityManager
import android.content.Context
import com.dsh.android.log.DshLog as Log
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogStore
import com.dsh.android.log.CrashRecorder
import com.dsh.android.log.BootState
import com.dsh.android.log.RescueBoot
import com.dsh.android.engine.constants.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.dsh.android.privilege.AdbMdns
import com.dsh.android.privilege.AdbPairingNotifier
import com.dsh.android.privilege.RootAccess
import com.dsh.android.privilege.ShizukuChannel
import com.dsh.android.privilege.WirelessDebugging
import com.dsh.android.runtime.RuntimePaths

/**
 * 管理内置 DSH 引擎的生命周期。
 *
 * 架构：
 *   $PREFIX/bin/node（Termux 官方 arm64 构建，随 APK 分发并由 TermuxBootstrap 铺入）
 *     └─ engine/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --no-open
 *          └─ 本地 HTTP 服务，打印带 token 的 URL
 *
 * 引擎通过子进程运行，App 解析其 stdout 得到 URL 后交给 WebView 加载。
 *
 * ## 为什么 Node 用 Termux 的构建而不是自编译
 *
 * node 与它的全部依赖（ICU / OpenSSL / libc++ …）都由 Termux 官方仓库针对 Android
 * bionic 构建，随 APK 内置即可离线可用。自编译需要交叉编译整个 V8，且要处理
 * `import.meta.main`（需 Node ≥ 24.2）等版本约束，成本与风险都高得多。
 *
 * ⚠️ 这条路线要求 `targetSdk ≤ 28`：Android 10 起 targetSdk ≥ 29 的应用不允许
 * `execve()` 自身私有目录里的文件（W^X / SELinux app_data_file 禁止 execute_no_trans），
 * 而 `$PREFIX/bin/node` 正落在私有目录里。详见 app/build.gradle.kts 的注释。
 */
class EngineManager private constructor(
    private val context: Context,
    private val commandMailboxes: EngineCommandMailboxes,
    private val activitySource: EngineActivitySource,
    private val builtInWebEngine: Boolean,
) {

    sealed interface State {
        data object Idle : State
        data class Extracting(val percent: Int) : State
        data object Starting : State
        data class Ready(val url: String) : State
        data class Failed(val message: String) : State

        /**
         * 引擎**曾经就绪过**，之后进程退出 —— 与 [Failed] 是两回事。
         *
         * ## 为什么必须单独一个状态
         *
         * 这个区分不是措辞讲究，而是**恢复动作完全不同**：
         *
         * - [Failed] 是「压根没起来」（Node 缺失、端口被占、插件没激活…）。
         *   重试同一个配置只会再失败一次，用户得走「安全模式」推开坏配置。
         * - [Dead] 是「本来跑得好好的，后来死了」。绝大多数是系统回收
         *   （LMK / 国产 ROM 的后台冻结）或引擎自己 OOM。**配置是好的**，
         *   原样重启就是正确动作，绝不该推开用户的插件配置。
         *
         * ## 症状（用户报的）
         *
         * 原先没有任何地方检测运行期退出：引擎进程一死，`_state` 永远是
         * `Ready(url)`，界面停在上一次渲染好的页面上 —— 用户看着一切正常，
         * 直到发消息才发现「发送失败 / 一直转圈」。而前端的重连逻辑打的是
         * **那个已经没人监听的端口**，于是连错误提示都是误导性的。
         */
        data class Dead(val message: String) : State
    }

    /**
     * 页面阶段 —— 引擎就绪**之后**还要走的两段。
     *
     * 拆成独立的一条流而不是并进 [State]：`State.Ready` 带着 url，而
     * MainActivity 要靠它决定 `loadUrl`；如果把后续阶段也塞进同一个枚举，
     * 状态一推进 url 就丢了，`loadUrl` 的判断也跟着失效。
     *
     * 真机实测（OPPO PHY110 / Android 15，冷启动）各段耗时：
     * ```
     * responseEnd        231ms   ← 引擎已就绪，开始导航
     * domInteractive     450ms
     * domContentLoaded   557ms
     * loadEvent         2084ms   ← onPageFinished，此时前端 bundle 刚解析完
     * 插件激活收尾       3808ms   ← 移动端 UI 插件挂完，界面才真正可用
     * ```
     * 也就是说 `onPageFinished` 之后**还有约 1.7 秒**宿主完全感知不到，
     * 而那段时间屏幕上什么都不会变。加这两段就是为了让动画如实反映它。
     */
    sealed interface UiStage {
        /** 还没开始加载页面。 */
        data object None : UiStage

        /** `onPageFinished`：DOM 到了，但插件仍在激活。 */
        data object PageLoaded : UiStage

        /** 移动端 UI 插件已激活，界面可用 —— 启动动画到此收尾。 */
        data object UiReady : UiStage
    }

    private val _uiStage = MutableStateFlow<UiStage>(UiStage.None)
    val uiStage: StateFlow<UiStage> = _uiStage.asStateFlow()

    /**
     * 页面 DOM 就绪（`WebViewClient.onPageFinished`）。
     *
     * ⚠️ 幂等且**只前进不后退**：`onPageFinished` 会因转屏、iframe、
     *   重定向而多次触发，退回 None 会让动画倒放。
     */
    fun notifyPageLoaded() {
        if (_uiStage.value == UiStage.None) _uiStage.value = UiStage.PageLoaded
    }

    /** 移动端 UI 插件激活完成（由 WebView 的 JS 桥回调）。 */
    fun notifyUiReady() {
        _uiStage.value = UiStage.UiReady
    }

    /**
     * 通知引擎「App 回到前台了」—— 让远程插件的 SSE 立刻重连。
     *
     * ## 为什么需要这一下（用户反馈：「悬浮窗没法对话，必须点开前台」）
     *
     * 切到后台后系统会冻结 JS 定时器（Doze / App Standby）。而远程插件的
     * SSE 重连靠 `setTimeout` 退避 —— 被冻结后那个定时器**永远不会到期**，
     * 于是后台期间电脑推来的事件全部丢失，用户切回前台还要干等最多 15 秒。
     * 表现就是：在悬浮窗里发消息，消息能出去（主动 RPC），但**收不到回复**
     *（回复走 SSE，还没连上），必须点开前台页面等一会儿才出现。
     *
     * 插件侧为此开了一个本机端点 `POST /api/dsh-remote/wake`，收到就把所有
     * 退避中的等待立即结束、并补连已断开的流。
     *
     * ## 三个必须遵守的约束
     *
     * 1. **绝不阻塞主线程**：`onStart` 是 UI 关键路径，网络请求一律丢到
     *    后台线程，失败静默。
     * 2. **绝不抛异常**：这是锦上添花的功能，插件没装 / 引擎没起来 /
     *    端口变了都属正常，不能因此影响 App 启动。
     * 3. **超时要短**：本机回环正常是毫秒级，2 秒还没回就说明引擎没在听，
     *    直接放弃，别挂着。
     *
     * 端口从 [State.Ready.url] 里取 —— 那是引擎自己打出来的真实 URL
     *（`dsh web: http://127.0.0.1:<port>/?token=…`），冷启动可能不是 3080。
     */
    fun notifyForeground() {
        val ready = _state.value as? State.Ready ?: return
        val port = runCatching {
            java.net.URI(ready.url).port
        }.getOrNull()?.takeIf { it in 1..65535 } ?: return

        Thread({
            try {
                val conn = (java.net.URL("http://127.0.0.1:$port/api/dsh-remote/wake")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                }
                try {
                    conn.outputStream.close()
                    val code = conn.responseCode
                    if (code in 200..299) {
                        Log.i(TAG, "已通知引擎回到前台（SSE 立即重连）")
                    } else {
                        // 404 = 插件没装或版本太老，属正常情况，只记一行。
                        Log.d(TAG, "前台通知返回 $code（插件未安装时属正常）")
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }
            } catch (t: Throwable) {
                // 引擎没起来 / 端口关了 —— 都是常态，不进日志噪音。
                Log.d(TAG, "前台通知跳过: ${t.message}")
            }
        }, "dsh-foreground-notify").apply { isDaemon = true }.start()
    }

    /**
     * 把页面阶段退回 [UiStage.None]（引擎重启时用）。
     *
     * ## 为什么必须有这个「后退」
     *
     * [notifyPageLoaded] / [notifyUiReady] 都刻意**只前进不后退**（`onPageFinished`
     * 会因转屏、iframe 重复触发，退回 None 会让启动动画倒放）。
     *
     * 但引擎**重启**是另一回事：整个页面要重新加载，那两段必须能重走一遍。
     * 不复位的话，`_uiStage` 已经停在 `UiReady` —— 而 StateFlow **相同值不会重复
     * 发射**，于是 `MainActivity` 里「等首帧再收启动层」的分支一次都不会跑到：
     * `webViewPainted` 永远停在 false，启动层再也收不起来（界面上就是一直卡在
     * 「正在加载界面…」）。
     *
     * ⚠️ 只在**引擎重启**这条路径上调用，不要挂到普通的页面重载上。
     */
    fun resetUiStage() {
        _uiStage.value = UiStage.None
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 当前引擎进程。
     *
     * ⚠️ `@Volatile`：由启动协程写、由该进程自己的 stdout 消费线程读
     *   （见 [markEngineDeadIfCurrent] 的 `process !== proc` 判据）。读到旧值时
     *   判据会偏向「不动状态」，是安全的一侧；但没有可见性保证时行为不可预期，
     *   所以显式标上。
     */
    @Volatile
    private var process: Process? = null

    /**
     * 是否是**我们自己**在停引擎（[stop]）。
     *
     * ⚠️ 用来区分「引擎意外死亡」与「用户/代码主动停止」。两者都会让 stdout 读到
     *   EOF，若不区分，`stop()` 之后会立刻把状态打成 [State.Dead]（"引擎意外退出"），
     *   盖掉 `stop()` 自己设的 [State.Idle]，界面上就会莫名其妙弹一句失败提示。
     */
    @Volatile
    private var userStopped = false
    private val logLines = ArrayDeque<String>()

    /**
     * 最近一次 [ensureExtracted] 收到的版本戳。
     *
     * 体检（见 [preflight]）发现 `engine/` 目录被改坏时，要拿它强制重解包 ——
     * 版本戳是调用方（MainActivity）传进来的，引擎这一层不能自己编一个。
     */
    private var lastAssetStamp: String? = null

    /**
     * 降级前端是否已就位（见 [ensureWebViewCompat]）。
     *
     * 供 [MainActivity] 决定要不要因内核过旧而拦截加载：降级生效后门槛就降到
     * `chrome73`，不再一律卡 94。
     *
     * ⚠️ 由 IO 线程写、主线程读（`observeEngine` 的 `Ready` 分支），必须 `@Volatile`。
     */
    val webViewCompatApplied: Boolean get() = compatibilityInstaller.webViewCompatApplied

    /**
     * 本次引擎启动时，root 工具是否**真的挂上了**。
     *
     * ## 为什么必须单独记一笔，而不是让界面去看探测结论
     *
     * `root` 工具是作为 `--patch` overlay 挂载的，而 overlay 是**命令行参数** ——
     * 只在引擎进程启动那一刻读一次。所以「本机有 root」与「这个会话里能用 root 工具」
     * 是**两件事**：
     *
     * - 用户在设置页点「重新检测」，拿到 `GRANTED` ⇒ 结论变了；
     * - 但引擎已经在跑，overlay 不会再读 ⇒ **`root` 工具这个会话里根本不存在**。
     *
     * 此时界面若照旧显示「root 工具已注册，AI 可以直接以 uid 0 执行命令」，
     * 就是**在说假话**：模型根本没有这个工具可调。用户会以为可用，然后发现
     * AI 完全不碰 root，只能靠重启 App 才「突然好了」—— 而这没有办法自己发现。
     *
     * 所以：探测结论（缓存，随时可刷新）与**挂载事实**（启动时定死）必须分开暴露，
     * 界面按这两者组合出诚实的文案（见 `client.js` 的 `rootNextOnPendingReload`）。
     *
     * ⚠️ 由启动协程写、WebView 的 JS 线程读（[MainActivity] 的 `rootState()` 桥），
     *   必须 `@Volatile`。
     */
    @Volatile
    var rootToolMounted = false
        private set

    /**
     * 救援 profile 本次启动是否可用（[ensureRescueProfile] 建出来了）。
     *
     * ⚠️ 与 [rootToolMounted] 同构：由启动协程写、主线程/桥读，必须 `@Volatile`。
     *
     * 用途：降级阶梯在决定「要不要退到 L2」之前必须问一句 ——
     * 救援 profile 建不出来时退到 L2 只会**再失败一次**，那比直接退 L3 更糟
     * （用户多等一轮、日志里多一条误导性的失败）。所以这个事实必须能被读到。
     */
    @Volatile
    var rescueAvailable = false
        private set

    /**
     * 本次引擎启动时，uid 2000（`shell`）通道是否**真的挂上了**。
     *
     * 与 [rootToolMounted] 同构：`shell` 工具由 profile bundle 的启用状态决定。
     * 区别在于它的挂载判据用的是产物文件是否存在（见 [writePrivilegedPatch]），
     * 而 root 用的是端到端探测结果。
     */
    @Volatile
    var shellToolMounted = false
        private set

    /** Termux 环境：提供 node 可执行文件与 $PREFIX。 */
    private val bootstrap by lazy { com.dsh.android.termux.TermuxBootstrap(context) }

    /** 引擎解包目录：filesDir/engine */
    private val engineDir get() = File(context.filesDir, "engine")

    private val assetInstaller by lazy {
        EngineAssetInstaller(context.assets, context.filesDir, File(context.applicationInfo.sourceDir), engineDir)
    }
    private val compatibilityInstaller by lazy {
        EngineCompatibilityInstaller(context.assets, context.filesDir, engineDir, builtInWebEngine)
    }
    private val profileOverlays by lazy {
        ProfileOverlayWriter(dshHome, workspaceDir) { isBuiltinPluginEnabled(context, it) }
    }
    private val toolInstaller by lazy { EngineToolInstaller(context.assets, context.filesDir, dshHome) }
    private val filePolicy by lazy { EngineFilePolicy(context.filesDir, homeDir, dshHome) }
    private val profileDefaults by lazy { ProfileDefaultsInstaller(dshHome) }

    private val runtimeDependencies by lazy {
        EngineRuntimeDependencies(context, engineDir, bootstrap.prefixDir, bootstrap.nodeBinary)
    }

    /**
     * 已落盘的 HTTP header 净化垫片路径（见 [ensureFetchGuard]）。
     *
     * `null` = 还没解出来 / 解失败。两个 [ProcessBuilder] 的 env 都要读它，
     * 所以缓存在字段里，避免重复读盘。
     */
    private val fetchGuardPath: File? get() = compatibilityInstaller.fetchGuardPath

    /** 引擎运行所需的可写 HOME（DSH 在此存放配置、凭据、会话）。 */
    private val homeDir get() = File(context.filesDir, "home")

    /** DSH_HOME：DSH 自己的数据目录。
     *
     * ⚠️ 路径字面量来自 [RuntimePaths.DSH_HOME_REL]（**单一真相源**）：
     *   `attachBaseContext()` 里装 [com.dsh.android.log.RescueBoot] 时
     *   `EngineManager` 实例还不存在，只能从常量拼。两边各写一份的话，
     *   改一处漏一处就是两个组件看不同的目录，症状只是「读不到状态」。
     */
    private val dshHome get() = File(context.filesDir, RuntimePaths.DSH_HOME_REL)

    /**
     * `$DSH_HOME` 的**只读**对外视图 —— 给宿主侧的信箱桥用
     * （见 [UiProbeBridge]、[BubbleCommandBridge]）。
     *
     * 那些桥跑在 App 进程里，和模型（Node 进程）靠**同一个目录下的文件**通信，
     * 所以它们都得知道 `$DSH_HOME` 在哪。暴露成 `val` 而不是让各处自己拼
     * `File(filesDir, "home/.dsh")`：拼错一次就是两个进程永远看不到对方的文件，
     * 而症状只是「工具超时」，没有任何线索指向路径。
     */
    val dshHomeDir: File get() = dshHome

    /** 引擎工作目录（agent 的文件操作以此为根）。 */
    private val workspaceDir get() = File(context.filesDir, "workspace")

    /** 记住恢复时使用的资产版本，安装事务与进度映射分别拥有。 */
    suspend fun ensureExtracted(versionStamp: String) {
        lastAssetStamp = versionStamp
        assetInstaller.ensureExtracted(versionStamp,
            progress = { _state.value = State.Extracting(it) },
            failure = { _state.value = State.Failed(it) },
        )
    }

    /** 版本戳：内容变化时触发重新解包。 */
    private val stampFile get() = File(engineDir, ".stamp")

    /**
     * 拼引擎进程的 `NODE_OPTIONS`：堆上限 + （可选）预加载净化垫片。
     *
     * ⚠️ 垫片**没落盘就绝不能出现在这里**：`--import` 指向不存在的文件时
     * Node 会直接拒绝启动（`ERR_MODULE_NOT_FOUND`），整台 App 打不开 ——
     * 那比「配置里混了中文发不出消息」严重得多。所以按 [fetchGuardPath] 决定。
     *
     * 路径含空格也不加引号：`NODE_OPTIONS` 由 Node 自己按空白切分，加了引号
     * 反而会把引号当成路径的一部分。`filesDir` 是
     * `/data/user/0/<包名>/files`，包名恒为 `com.dsh.android`，不含空格。
     */
    private fun nodeOptions(): String {
        val base = "--max-old-space-size=${oldSpaceMb()}"
        val guard = fetchGuardPath ?: return base
        if (!guard.isFile) return base
        return "$base --import ${guard.absolutePath}"
    }

    /**
     * 决定 Node 的老生代堆上限（MB）。
     *
     * ## ⚠️ 为什么不能写死 1024
     *
     * 原来这里是 `--max-old-space-size=1024`，没有任何说明。真机证据
     * （HUAWEI CDY-TN90，Android 10，**物理内存 7.7 GB**）上的 `engine.log`：
     *
     * ```
     * Mark-Compact 1004.6 (1029.1) -> 1001.7 (1029.4) MB
     * FATAL ERROR: Ineffective mark-compacts near heap limit
     * Allocation failed - JavaScript heap out of memory
     * ```
     *
     * 堆一满到 1004 MB 就 SIGABRT（退出码 134），**6 天的崩溃文件里这个 FATAL
     * 出现了 10 次**，每次自动重启要 27 秒（`engine.ready ms=27534`），
     * 期间界面完全没有后端。
     *
     * 关键点：**这不是设备内存不够**。7.7 GB 的机器上 V8 只被允许用 1 GB，
     * 撞上限是必然的 —— 长会话累积上下文、检索、日志都会把堆推上去。
     * 换句话说，原来的 1024 是在一台 7.7 GB 的机器上人为制造瓶颈。
     *
     * ## 取值规则
     *
     * 按**物理内存**分档，并给 App 自身与系统留足余量（V8 的 old space 只是
     * 引擎进程的一部分，外面还有 native、code space、以及 App 自己的 Java 堆）：
     *
     * | 物理内存 | old space | 说明 |
     * |---|---|---|
     * | ≥ 6 GB | 3072 MB | 留 4 GB 以上给系统与本 App，够用且不会把系统逼进 LMK |
     * | ≥ 4 GB | 2048 MB | 中端机常见档 |
     * | ≥ 3 GB | 1536 MB | |
     * | 其它 | 1024 MB | **不低于原值** —— 低端机上贸然加大只会把系统拖进 LMK，比 OOM 更糟 |
     *
     * ⚠️ **下限保持 1024**：本修复的目标是"别在内存充裕的机器上人为卡死"，
     *   不是"所有机器都加大"。内存紧张时 1024 已经会与系统抢，再加只会
     *   让整个 App 被 LMK 杀掉（那比引擎自己 SIGABRT 更难受，因为连日志都留不下）。
     *
     * ⚠️ 拿不到 `totalMem` 时（极罕见）退回 1024，与修复前一致。
     */
    private fun oldSpaceMb(): Int {
        val total = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            mi.totalMem
        }.getOrDefault(0L)
        val gb = total / (1024L * 1024L * 1024L)
        return when {
            gb >= 6L -> 3072
            gb >= 4L -> 2048
            gb >= 3L -> 1536
            else -> 1024
        }
    }

    /**
     * 给 `dsh-subprocess-local` 的进程检查器补 android 分支（幂等）。
     *
     * **症状**：终端面板报
     * `subprocess-local: terminal inspection is unsupported on platform android`。
     *
     * **根因**：`createProcessInspector()` 是 linux/darwin/win32 三选一
     * （`runner-launch-*.js` 的 `createProcessInspector`），android 落不进任何分支
     * 就 `throw`。而 `spawnTerminal()` 第一件事就是调它。
     *
     * **做法**：让 android 复用 **Linux 实现**。这是有依据的，不是将就：
     *
     * - `LinuxProcessInspector` 读的全是 `/proc`，Android 就是 Linux 内核，
     *   格式一致（真机实测 `parseProcStat` 能正常解析）。
     * - 它需要的 syscall 表里**本来就有 arm64**（`SYSCALLS.arm64`），
     *   安卓 arm64 正好命中。
     * - app 域下 `/proc` **目录可读**，只是别的 uid 的进程条目会被 SELinux 挡掉；
     *   而 `LinuxProcessInspector` 对每个读不到的条目都是「跳过」
     *   （`readLinuxStat` 吞异常返回 `undefined`），不会因此抛错。
     *   代价是**只能看见自己 uid 的进程** —— 而终端 shell 及其子进程
     *   恰好全是本 app 的 uid，正是需要观察的那部分。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun applyEngineFilePatch(id: String) {
        val result = EngineFilePatches.apply(engineDir, id)
        when (result.status) {
            EngineFilePatches.Status.APPLIED -> Log.i(TAG, "引擎补丁 ${result.id}: ${result.detail}; ${result.targets}")
            EngineFilePatches.Status.ALREADY -> Unit
            EngineFilePatches.Status.MISSING,
            EngineFilePatches.Status.MISMATCH,
            EngineFilePatches.Status.FAILURE -> {
                Log.w(TAG, "引擎补丁 ${result.id} ${result.status}: ${result.detail}; ${result.targets}")
                if (id == "image-mime" && result.status == EngineFilePatches.Status.MISMATCH) {
                    EventLog.record("image.patch-skipped", mapOf("file" to "client.js"))
                }
            }
        }
    }

    private fun patchProcessInspector() = applyEngineFilePatch("process-inspector")

    /**
     * 把 `terminal-bash` 硬编码的 `/bin/bash` 换成 Termux 的 bash（幂等）。
     *
     * ## 症状
     *
     * `bash` 工具**每一条命令**都失败，报：
     *
     * ```
     * Error: PTY shell exited during startup
     * ```
     *
     * 而**侧栏终端却能正常打开** —— 「一个坏一个好」这个分裂现象是本节的要害，
     * 它排除了「PTY 不可用」这个直觉上最像的解释。
     *
     * ## 根因：`/bin/bash` 在 Android 上根本不存在
     *
     * 抛出点是 `dsh-terminal-bash/lib/index.js` 的 `initialize()`：
     *
     * ```js
     * if (result.waitReason === "session_exit") throw new Error("PTY shell exited during startup");
     * ```
     *
     * `session_exit` = shell 进程**起来后立刻退出**。而它 spawn 的是什么，
     * 由同文件的 `resolveConfig()` 决定：
     *
     * ```js
     * const DEFAULT_BASH_SHELL = "/bin/bash";
     * shellPath: config.shellPath?.length > 0 ? config.shellPath : DEFAULT_BASH_SHELL,
     * ```
     *
     * ⚠️ **引擎没有任何一层配置写过 `shellPath`**（全 engine + `$DSH_HOME` 搜过，
     * 只有类型定义和 GUI 侧栏终端的同名字段），于是必然落到这个硬编码值。
     *
     * 真机实测（uid 2000 与 app 域都测了，排除权限干扰）：
     *
     * | 命令 | 结果 |
     * |---|---|
     * | `ls -l /system/bin/bash` | `No such file or directory` |
     * | `/bin/bash -c "echo OK"` | rc=**127** `inaccessible or not found` |
     * | `/bin/sh -c "echo SH_OK"` | `SH_OK`，rc=0 |
     * | `$PREFIX/bin/bash -c ...` | `5.3.9(1)-release`，rc=0 |
     *
     * `/bin` 在 Android 上是 `-> /system/bin`，那里只有 `sh`（mksh），**没有 bash**。
     * 这与权限无关 —— 把「特殊权限」全开也变不出这个文件来。
     *
     * ⚠️ **`terminal-bash` 不做 PATH 查找**：`spawn()` 直接用 `argv[0]`，
     * 不调 `resolveExecutable`。所以哪怕引擎的 `PATH` 第一项就是 `$PREFIX/bin`
     * （真机确认），它也找不到 bash。这是「为什么不靠 PATH 修」的答案。
     *
     * ## 为什么侧栏终端不受影响
     *
     * 它走的是**另一条路径**（`dsh-api-terminal-controller` 的 `resolveShell()`）：
     *
     * ```js
     * shell = profile(environment.defaultShell ?? (platform === "windows" ? "cmd.exe" : "/bin/sh"));
     * ```
     *
     * 回落的是 `/bin/sh` —— 那个**真实存在**（引擎环境里没有 `SHELL` 变量，
     * 真机确认，所以走的就是这个兜底值）。同一个 App 里两条路径一个默认
     * `/bin/sh`、一个默认 `/bin/bash`，后者在 Android 上必挂。
     *
     * ## 做法
     *
     * 换成 `$PREFIX/bin/bash`（Termux 的 bash 5.3.9）。选它而不是 `/bin/sh`：
     * `terminal-bash` 整个包是按 bash 语义写的（`--noprofile --norc -i`、
     * `stty -echo`、提示符契约），而 `--noprofile` 这种长选项 mksh 不认。
     *
     * ⚠️ 已用真机 PTY 实测（同一份 node-pty 绑定、同款参数、带 link 转译层）：
     * 启动即出提示符 `bash-5.3$`，写入 `echo READY_$((6*7))` 回显 `READY_42`，
     * 进程存活（`exited=null`）。不是推测。
     *
     * 用 `process.env.PREFIX` 而不是硬编码绝对路径：`PREFIX` 由 [start] 显式传给
     * 引擎（真机确认 `PREFIX=/data/user/0/com.dsh.android/files/termux/usr`），
     * 这样不依赖包名或目录布局。取不到时回落 `/bin/sh`（一定存在）—— 虽然会失去
     * bash 语义，也好过必定 rc=127 的 `/bin/bash`。
     *
     * ⚠️ 全 engine 只有**这一处**硬编码 `"/bin/bash"`（全盘搜过），所以改这一处
     * 就覆盖了所有 preset —— 包括用户当前在用的 `minimal`（只有它的
     * `persistent-shell` group 挂 `terminal-bash`）。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchTerminalShell() = applyEngineFilePatch("terminal-shell")

    /**
     * 撤回「把选择工作区对话框的起始目录改成 `/sdcard`」那次改动（幂等）。
     *
     * **症状**：点「选择工作区」后，对话框停在 `files/home`（app 私有目录），
     * 既看不到手机上的任何文件夹，也**走不上去**（面包屑起点就是那里），
     * 于是「选一个手机上的文件夹当工作区」这件事在 App 里根本做不到。
     *
     * **根因**：Android 上引擎把目录选择器判成 `browse` 后端
     * （`dsh-host-directory-picker-auto` 的判定表：非 darwin/win32、非带显示的
     * linux ⇒ `browse`，真机实测 boot 图里挂的正是
     * `@deepseek-ai/dsh-client-ui-directory-picker-browse`）。该后端的 `list()`
     * 在没给路径时用 `homedir()` 当起点：
     *
     * ```js
     * const home = homedir();          // → $HOME，本 App 里是 filesDir/home
     * const target = resolve(path ?? home);
     * ```
     *
     * 而本 App 把引擎的 `HOME` 设成 `filesDir/home`（Termux 习惯，见 [start]），
     * 那是应用私有目录 —— 手机存储根本不在它的祖先链上。
     *
     * **做法（曾经）**：把 android 下的默认起点换成 `/sdcard`。
     *
     * ⚠️ **这个做法已经被推翻，本函数现在负责把它撤回来。** 起点放在 `/sdcard`
     * 虽然能选手机上的文件夹，但用户**再也进不了 app 私有目录** —— 两棵树在
     * Android 上互不可达（见 [ensurePickerRoots] 的实测表），而私有目录正是
     * 默认工作区所在的地方。现在改为「起点留在 HOME + 在 HOME 下建两个入口
     * 链接」，两边的需求同时成立。
     *
     * ⚠️ **必须真的撤回，不能只改代码不还原**：引擎解包目录只在版本戳变化时
     * 才重解包（[ensureExtracted] + [stampFile]），已经打过补丁的安装里那行
     * 仍然是 `/sdcard`，改 `assetStamp` 会让所有用户白重解包 100+ MB。
     *
     * ⚠️ 幂等判据是「打过补丁的文本在不在」：撤回之后它就不存在了，第二次调用
     * 直接返回。官方若改了这一行，两个锚点都不匹配 ⇒ 跳过并打日志，不会让
     * 启动失败（`home` 字段本来就该是 `homedir()`，不改才是对的）。
     */
    private fun restoreDirectoryPickerStart() = applyEngineFilePatch("directory-picker-restore")


    /**
     * 让 `dsh-attachment-local` 的目录 fsync 容忍「祖先目录打不开」（幂等）。
     *
     * **症状**：图片/文件附件**上传成功**（缩略图都在），但点发送报
     * `prompt rejected (session/agent-busy)`。`agent-busy` 又是兜底错误码，
     * `details.reason` 里的真因是：
     *
     * ```
     * Error: EACCES: permission denied, open '/data/user/0'
     * ```
     *
     * **根因**：该包发布附件时要证明「目录项已落盘」，于是从 `$DSH_HOME` **逐级向上**
     * fsync 每一层父目录（`syncDirectory()` = `open(dir, O_RDONLY)` + `handle.sync()`），
     * 而 `ensureDurableHome()` 把 boundary 定为 `parse(home).root` —— 也就是 `/`，
     * 于是这趟遍历一定会走到应用目录之外。
     *
     * 但 **Android 的 SELinux 不允许应用 open 这些祖先目录**（真机实测，连
     * root 的 `/` 也是 EACCES，不是权限位的问题）：
     *
     * | 目录 | 模式 | `open(O_RDONLY)` |
     * |---|---|---|
     * | `/` | 755 | **EACCES** |
     * | `/data` | 771 | **EACCES** |
     * | `/data/user/0` | 771 | **EACCES** |
     * | `/data/data` | 771 | **EACCES** |
     * | `/data/data/com.dsh.android` | 700 | OK |
     *
     * 于是走到 `/data/user/0` 就抛 EACCES，整个附件发布失败。
     *
     * **做法**：只把「open 目录被拒（EACCES/EPERM）」这一种失败改成**跳过该层**
     * （`return`），其余 errno 照旧抛出。遍历本身会继续向上，最终自然收敛。
     *
     * ⚠️ **丢掉的只是「祖先目录项崩溃持久性」这一项加固，不是正确性** ——
     * 附件字节本身的 fsync（临时文件 `handle.sync()`）照旧执行，应用自己目录
     * （`$DSH_HOME` 及其以下，700、可 open）的 fsync 也照旧执行。
     * 而应用目录之外的目录项在 Android 上**本来就不可能**被 fsync，
     * 这不是取舍，是平台事实。
     *
     * ⚠️ 桌面端不受影响：`/`、`/home` 在 Linux 上都是可 open 的 755，
     * 这条 catch 分支根本不会触发。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchAttachmentDurability() = applyEngineFilePatch("attachment-durability")

    /**
     * 放宽「发图格式白名单」—— 原版只认 4 个精确的 MIME 字符串。
     *
     * **症状**：新会话里粘贴/选择图片，直接弹「仅支持 PNG、JPG、WebP、GIF 格式的图片」，
     * 图根本没进输入框。而同一张图在已有会话里能发。
     *
     * **根因**：`dsh-client-ui-conversation` 的 `isImageMediaType()` 是**精确字符串比较**：
     *
     * ```js
     * return value === "image/png" || value === "image/jpeg"
     *     || value === "image/webp" || value === "image/gif";
     * ```
     *
     * 于是下面这些**明明能显示的图片**全被判成非法：
     *
     * | 场景 | `file.type` | 结果 |
     * |---|---|---|
     * | 后缀是 `.jpg`（部分系统/相册） | `image/jpg` | ❌ 被拒（不是 `jpeg`） |
     * | iPhone 拍照 | `image/heic` / `image/heif` | ❌ 被拒 |
     * | 部分安卓相册 | `image/bmp` / `image/avif` | ❌ 被拒 |
     * | 从剪贴板粘贴 | 可能是空串 | ❌ 被拒 |
     *
     * 触发点还叠了 `continuable`（新开会话）这个条件，所以表现成
     * 「新会话一粘图就报错，老会话没事」。
     *
     * **做法**：把精确比较换成前缀 + 别名判断 —— 只要 `image/` 开头就放行，
     * 再补上 `image/jpg` 这个常见别名。
     *
     * ⚠️ **只放宽「能不能选」，不放宽「能不能解码」**：真正的解码在下游
     * （`dsh-attachment-local` 用 sharp），不支持的格式那里会给出明确错误，
     * 比在入口处一刀切拦住友好得多 —— 用户至少能看到「这张图解码失败」，
     * 而不是莫名其妙的格式白名单。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败）。
     */
    private fun patchImageMediaType() = applyEngineFilePatch("image-mime")

    /**
     * 从引擎打印的 URL 里取出端口号，取不到给空串。
     *
     * ⚠️ **不能写成 `url.substringAfter(":").substringBefore("/")`**（踩过，真机全中）：
     *   `substringAfter(":")` 命中的是 `http:` 里那个冒号，于是对
     *   `http://127.0.0.1:3080/?token=...` 得到 `//127.0.0.1:3080/?token=...`，
     *   再 `substringBefore("/")` 就是**空串**。实测 53 份诊断包的时间线里
     *   `engine.ready` 的 `port` 字段**无一例外都是空**，等于白记了一个字段。
     *
     *   后果很具体：「端口撞车」这条分支（3080 空闲就沿用默认值 / 被占用就改由
     *   内核分配空闲端口）在时间线上无法区分，只能去翻 app.log 正文。
     *   而端口撞车恰恰是「引擎起不来」的一大类原因（EADDRINUSE → webserver
     *   激活失败 → 必需插件缺失 → 引擎退出）。
     *
     * 用正则抓 `:<端口>` 而不是按 `:` 切分，天然躲开协议名的冒号。
     */
    private fun portOf(url: String): String =
        Regex(""":(\d{2,5})(?:[/?]|$)""").find(url)?.groupValues?.get(1) ?: ""

    /**
     * 让 DeepSeek Messages 适配器把 assistant 的 `tool_use` 块**前置**（幂等）。
     *
     * **症状（严重，会话级永久损坏）**：某一轮因为 API 错误结束（实测是第三方
     * 路由的 `429 RATE_LIMIT`）之后，**该会话此后的每一条消息都失败**，连
     * 「只回复两个字：通了」这种最简消息也一样：
     *
     * ```
     * 本轮运行失败 messages.7.1: `tool_use` ids were found without
     * `tool_result` blocks immediately after: call_f0dd74e854fd4cb1a0a6ee0a.
     * (INVALID_REQUEST)
     * ```
     *
     * 新建会话不受影响，说明是**历史消息**的问题，不是当前请求的问题。
     *
     * **根因**：`serialize()` 把 assistant 消息的 content 块**原样保序**映射成
     * `tool_use` / `text` / `thinking`，而持久化历史里 assistant 的实际形状是
     * **交错的**（模型先说话、再调工具、再说一句、再调工具）：
     *
     * ```
     * ["tool_use:call_e6d1…", "text(3)", "tool_use:call_a47f…", "tool_use:call_2399…"]
     * ```
     *
     * 而 DeepSeek Messages API 要求 assistant 的 `tool_use` 块**必须连续**，
     * 且紧后必须是携带全部 `tool_result` 的 user 消息。夹在中间的 `text`
     * 直接违约 → 整段历史从此再也发不出去。
     *
     * ⚠️ 引擎自己的自检（`serialize()` 末尾的 `pending` 循环）**只查 id 配对、
     * 不查块顺序**，所以这个历史在本地自检里是「合法」的，只有真发给 API 才炸。
     *
     * ⚠️ 触发它的**不是**用户主动点「停止生成」（实测那条路径正常），而是
     * **API 侧的错误中断** —— 那一轮 assistant 消息已经带上了 `tool_use`，
     * 但永远等不到 `tool_result`，历史就此定形。
     *
     * **做法**：只在 `assistant()` 的 `map` 之后补一次稳定排序 —— 把
     * `tool_use` 抽到最前、其余（`text` / `thinking`）保持原有相对顺序跟在后面。
     *
     * ⚠️ **只动 wire 请求体，不动持久化历史**：`assistant()` 返回的是新数组，
     * `message.content` 本身不被改写，会话日志与 UI 显示都不受影响。
     *
     * ⚠️ **`replay` 的 index 对齐不能破坏**：`readReplay()` 返回的签名数组是
     * **按下标**与 `message.content` 对齐的，而 `assistant()` 在 `map` 里用
     * `replay[index]` 取签名。所以重排必须发生在**映射之后**（对已经带上
     * `signature` 的 wire 块重排），不能在 `map` 之前重排 `message.content`，
     * 否则签名会串位。本补丁插在 `return ... .map(...)` 的外层，正是这个位置。
     *
     * ⚠️ 引擎自己**已经**对 user 消息做了同样的重排（`[...results, ...其余]`），
     * 只是漏了 assistant 这一侧 —— 这个补丁补的就是那处遗漏。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchLlmDeepseekToolOrder() = applyEngineFilePatch("deepseek-tool-order")

    /**
     * 修正 pi-ai 适配器在**第三方路由不返回 tool_call id** 时写出的空 tool-call id（幂等）。
     *
     * **症状（会话级永久损坏，比 ⑨.5 更狠）**：某一轮结束后，该会话从列表里消失，
     * `/export` 报 HTTP 500，**新建会话也跟着失败**：
     *
     * ```
     * format v4 tool/result at seq 19 requires toolCallId matching its tool source
     * tool call id requires a nonempty string
     * 新建会话失败: gateway/internal: failed to create session "session-7d4ab1ab-…":
     *   SessionQueryError: stored session … is corrupt:
     *   SessionFormatError: tool call id requires a nonempty string
     * ```
     *
     * **根因**：`toolcall_end` 分支拿 provider 给的 id 造 tool-call 块，**没有任何校验**：
     *
     * ```js
     * id: brandString(event.toolCall.id),   // brandString 是纯类型断言，return value，零校验
     * ```
     *
     * 对比 DeepSeek 官方适配器，那里有防线
     * （`if (!content.id || !content.name) return malformed("empty tool identity")`），
     * **pi-ai 这条路径漏了**。第三方路由（实测设备上的 `agnes` / `q`）在流式响应里
     * 不给 `toolCall.id` 时，`id` 就是 `""` 或 `undefined`。
     *
     * ⚠️ **`dsh-llm` 的收口兜不住它**（[patchLlmToolCallIdFallback] 是第二道防线）：
     * `assemble()` 里 `id: partial.toolCallId ?? brandString(...)` 的 `??`
     * **只兜 `null`/`undefined`，兜不住空串**；而且 pi-ai 会经 `block-end` 直接把
     * 整个 block 塞进 `partial.block`，`assemble()` 第一行
     * `if (partial.block) return partial.block;` 会**直接返回**，`??` 那行根本不执行。
     *
     * ⚠️ **为什么这会造成整个会话不可读**：`tool/result` 行落盘后，读取侧
     * `dsh-session-format-v3-to-v4` 的 `assertV4ToolResultMessage()` 要求
     * `toolCallId` 是非空字符串且等于 `source.callId`；而
     * `dsh-session-persistence-jsonl` 的 `consumeEventLine()` 对格式错误
     * **只重抛、不恢复**（只有 JSON 解析失败才走 `issue` 恢复路径），
     * `finish()` 还会再跑一遍 `assertReleasedV4Relationships()`。
     * 所以**一行坏数据就让整个会话加载失败** → 从列表消失 → 导出 500 → 新建会话被拖累。
     *
     * ⚠️ 空 id 能一路落盘是因为 `snapshotJsonValue()` 只校验「可无损 JSON 序列化」，
     * 空串是合法 JSON；`undefined` 则会被 JSON 往返整个丢掉字段，两种都逃得过写入侧。
     *
     * ⚠️ 只改**流式事件的 id 兜底**，不改持久化历史、不改正常路径：
     * provider 给了真实 id 时行为完全不变。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchLlmPiAiToolCallId() = applyEngineFilePatch("pi-ai-tool-id")

    /**
     * 修正 `dsh-llm` 组装 tool-call 块时**兜不住空串**的 id 兜底（幂等）。
     *
     * 这是 [patchLlmPiAiToolCallId] 的**第二道防线**，覆盖 pi-ai 之外的 provider：
     * 任何走「只有 delta、没有 block-end」协议的适配器，其空 id 都会从这里出去。
     *
     * ```js
     * id: partial.toolCallId ?? brandString(`call-${index}`),
     * //                      ^^ 只兜 null/undefined，空串照样透出去
     * ```
     *
     * ⚠️ 这是**所有 LLM 适配器共用的收口**（`BlockAssembler`），所以只修 pi-ai
     * 不够 —— 别的 provider 出现同样形状时，坏行会从同一个地方产生。
     *
     * ⚠️ 走 `block-end` 的协议（pi-ai 就是）**不会**执行到这一行：
     * `assemble()` 第一行 `if (partial.block) return partial.block;` 直接返回了
     * block-end 带来的块。所以这两处补丁各管一段路径，都要在。
     *
     * 找不到锚点就跳过。
     */
    private fun patchLlmToolCallIdFallback() = applyEngineFilePatch("llm-tool-id")

    /**
     * 把 `flock.js` 的平台白名单补上 android（幂等）。
     *
     * 只替换那一处判断，不动其它任何逻辑；找不到锚点就跳过（引擎升级后结构变了
     * 也不要因此让启动失败，届时日志里会有明确提示）。
     */
    private fun patchFlockPlatform() = applyEngineFilePatch("flock-platform")

    /**
     * 让引擎**不再因为一个插件的未捕获异常而整个进程退出**（幂等）。
     *
     * 这是「用户自己做插件把 App 搞崩」的**唯一根因**，真机实测确认：
     * 语法写坏、`apply()` 里同步抛错，引擎都只 `warn: N entry did not activate`
     * 然后照常就绪（因为 `requiredStartupEntryIds` 是白名单，用户插件不在其中）。
     * 真正会死的是**运行期回调里抛出的未捕获异常**：
     *
     * ```
     * dsh: fatal uncaught exception: Error: BOOM
     * 引擎已退出: 退出码 1，准备自动重启（第 1 次）
     * … 反复 3 次后「引擎反复停止」，App 再也起不来
     * ```
     *
     * ⚠️ 这条路径下 `rescue/` 目录**始终是空的**，`RescueBoot.recordFailure`
     *   一次都不会被调用 —— 因为 App 走的是「引擎退出→重启」分支，不是
     *   「启动失败」分支，于是 L1/L2/L3 三级降级阶梯**判据全不成立**。
     *   这就是为什么现有的防崩措施对这个场景完全无效。
     *
     * 修法分两步（**两处都必须改，只改一处不行** —— 实测过）：
     *
     * 1. `dsh-app-boot` 的 `installFailLoud` 里两处 `proc.exit(1);` → 空注释。
     * 2. `dsh` 的 `profile-boot` 传给 `installFailLoud` 的 release 回调
     *    `async () => { await app.current?.fiber.dispose(); }` → 空函数。
     *
     * ⚠️ **第 2 步是关键，漏了它整件事白做**：只改第 1 步时进程会以
     *   `退出码 0` 安静退出 —— 因为 release 把整个应用 fiber 树 dispose 掉了，
     *   node 没有活干自然退出。表现为「补丁生效了（1→0）但引擎照退」。
     *
     * 崩溃的那个插件自身的 entry 已由 Loader 标记失败、不参与后续工作，
     * 其余插件的服务照常可用 —— 比「整个 App 打不开」好得多。
     *
     * ⚠️ 只在 Android 上这么干。桌面端「大声失败后退出」是合理语义，
     *   那边没有「用户装第三方插件」这条路径。
     */
    private fun patchFailLoudExit() = applyEngineFilePatch("fail-loud")

    /**
     * 把「快准狠」约束并入**创造模式**（`cordis` 预设）的 persona（幂等）。
     *
     * ## 为什么直接改引擎里的预设文件，而不是走 `--patch` overlay
     *
     * `--patch` 的语义是给 loader **顶层**条目按 `id` 覆盖，而 `applyEntryPatches()`
     * 建索引时只递归 `entry.group && Array.isArray(entry.config)`：
     *
     * ```js
     * const buildMap = (entries) => {
     *   for (const entry of entries) {
     *     if (entry.id) entryMap.set(entry.id, entry);
     *     if (entry.group && Array.isArray(entry.config)) buildMap(entry.config);
     *   }
     * };
     * ```
     *
     * 预设声明行（`id: preset-cordis`）**不是 group** —— 它的 `plugins` 列表挂在
     * `config` 里、每一行也没有 `group: true`。所以 overlay 里写 `- id: persona`
     * **匹配不到东西**（`patch: entry "persona" not found`），那条 persona 永远改不到。
     * 而按 `preset-cordis` 整块覆盖 `config` 又会把整套工具列表一起搬进 Kotlin 常量
     * （补丁是整块替换、不深合并），引擎每次升级都要跟着重写 —— 那是长期负债。
     *
     * 官方文件自己就是真相源（`dsh-web-app/presets/cordis.patch.yml`），
     * 所以这里用一个**字面文本锚点补丁**，与 [patchAttachmentDurability] /
     * [patchLlmDeepseekToolOrder] 同一套做法：只改这一个文件、只改这一个字段。
     *
     * ## ⚠️ 引擎重解包会让补丁消失，所以每次启动都要重打
     *
     * `ensureExtracted()` 在版本戳变化时 `deleteRecursively()` 整个 `engine/`
     * 再重解包 —— 补丁随之被抹掉。因此本函数**不是一次性的**，幂等判据用
     * 「补丁后的文本特征在不在」（[PRESET_CORDIS_PERSONA_ANCHOR_HEAD]），
     * 而不是某个戳文件（戳文件也在 engine 里，会一起没）。
     *
     * ⚠️ 锚点找不到就跳过并打日志，**不让启动失败** —— 引擎升级后官方那句
     * persona 原文若变了，届时按新文本更新 [PRESET_CORDIS_PERSONA_GUARD] 即可。
     */
    private fun patchPresetCordisPersona() = applyEngineFilePatch("preset-cordis-persona")

    internal fun mergeDefaultPreset(body: String): String = profileDefaults.mergeDefaultPreset(body)
    internal fun stripBuiltinAgnesText(body: String): String = profileDefaults.stripBuiltinAgnesText(body)

    private fun isBundleEnabled(name: String): Boolean = try {
        val bundles = JSONObject(File(dshHome, "profiles/$PROFILE_NAME/package.json").readText())
            .getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
        (0 until bundles.length()).any { bundles.optString(it) == name }
    } catch (_: Exception) { false }

    /**
     * 启动前体检：跑一次 `--dump-config`，把「坏到起不来」的 profile 先修好。
     *
     * ## 为什么需要它
     *
     * profile 的 bundle 清单与 patch 层都落在 `$DSH_HOME` 下，而那是**模型可写**
     * 的目录（AI 能直接编辑 `profiles/web/` 与 `engine/`）。一旦改坏，引擎在
     * `loadProfile()` 阶段就抛错并以 `process.exit(1)` 退出，App 只会在启动页上
     * 显示「引擎启动失败」——**没有任何自救路径**，用户只能卸载重装，而卸载会清掉
     * 整个 `filesDir`（Termux 环境、会话、凭据、工作区全没）。
     *
     * ## 为什么用 `--dump-config` 当体检手段
     *
     * 引擎自带这条免 boot 的命令：它走的是**和启动完全同一套** `loadProfile()` +
     * patch 解析（`dump-config-BEDI-dNY.js` 的 `prepareProfile()`），但只解析、
     * 不求值 `!!js`、不起服务。于是：
     * - 判据来自引擎自己的解析器，不会随引擎升级而漂（**不要**用 Kotlin 重写 YAML 校验）；
     * - 真机实测耗时约 **0.7~0.8 秒**（3 次：703 / 780 / 800 ms），相对引擎自身
     *   7~10 秒的启动时间可忽略。
     *
     * ## 三种坏法，判据**不一样**（真机逐条实测，别合并成一条）
     *
     * | 坏在哪 | `dump-config` | 判据 |
     * |---|---|---|
     * | `profiles/web/package.json` 非法 JSON | RC=1 | 退出码 |
     * | `profiles/web/cordis.patch.yml` 语法坏 | RC=1 | 退出码 |
     * | 第三方 bundle 的 patch 坏 | **RC=0** | stderr 的 `skipping profile bundle` |
     * | 核心 bundle（`dsh-web-app`）的 patch 坏 | **RC=0** | **required 条目缺失** |
     *
     * ⚠️ **只看退出码会漏掉后两种**，而第四种正是「AI 改坏 `engine/` 目录」的形态：
     * 真机实测该状态下 `dump-config` 返回 **RC=0**，但真正 boot 会**挂死**
     * （45 秒超时被杀，EXIT=124，日志里 `webserver` 等服务永远 pending）。
     * 所以必须再补一道「required 条目在不在」的检查 —— 那 4 个 id 与
     * `dsh-app-boot` 的 `requiredStartupEntryIds` 同源，缺一个引擎就起不来。
     *
     * ## 隔离方式：改名不删
     *
     * 一律改成 `<原名>.bak-<时间戳>`。模型改坏的东西可能还带着用户想要的内容，
     * 而本方法只负责让 App 能打开，不该替用户销毁数据。
     *
     * @return true = 做过修复（调用方据此提示用户）；false = profile 本来就是好的。
     */
    /**
     * 启动前体检的结论。
     *
     * ⚠️ **为什么不能再用 `Boolean`**：`false` 同时表示「静态干净」和「体检压根没跑成」，
     * 而这两件事的**可信度完全不同** —— 前者至少解析过一遍配置，后者什么都不知道。
     * 调用方（以及读日志的人）看到 `false` 会以为"体检说没问题"，于是
     * 引擎接下来死掉时，第一反应是往别处查。
     *
     * 真机证据（2026-10-14，某小米机型 / Android 16 / v18，8 次连续启动失败）：
     * `--dump-config` 退出码 **0**、4 个 required 条目**全在**，体检因此放行，
     * 引擎照死。**静态干净 ≠ 已验证** —— 这就是新增 [StaticOnly] 的理由。
     *
     * ⚠️ [Clean] / [StaticOnly] 都**不代表引擎能起来**：`--dump-config` 只解析配置，
     * 不执行插件的 `apply()`，而真机上杀死引擎的异常正是在 `apply()` 期抛的。
     * 真正"能起来"的唯一证据是引擎打印了 URL（见 `start()` 里的 `engine.ready`）。
     */
    private enum class PreflightResult(val label: String) {
        /** 静态解析过了，没有可疑文件要隔离。**运行期仍未验证。** */
        Clean("静态干净"),

        /** 体检跑不了（node 缺失、profile 还没建）—— 对配置一无所知。 */
        StaticOnly("未做静态检查"),

        /** 体检发现并隔离/重解包了东西，复检通过。 */
        Repaired("已修复"),
    }

    /**
     * 启动前体检：profile 与 patch 层都是模型可写的，改坏会让引擎直接 exit 1，
     * 而 App 没有任何自救路径（用户只能卸载重装，那会清掉全部会话与凭据）。
     *
     * ⚠️ **本方法看不见插件的 `apply()` 期异常** —— 它跑的是 `--dump-config`，
     * 只解析配置。所以它的结论**不能**用来担保"引擎能起来"（见 [PreflightResult]）。
     *
     * @return 三态结论，见 [PreflightResult]。
     */
    private suspend fun preflight(): PreflightResult {
        val profileDir = File(dshHome, "profiles/$PROFILE_NAME")
        if (!profileDir.isDirectory) {
            // 还没有 profile：引擎首次启动会自己按模板初始化，没什么可体检的。
            // ⚠️ 这不是 Clean —— 一次 dump-config 都没跑过，运行期零验证。
            return PreflightResult.StaticOnly
        }

        val first = runDumpConfig()
        if (first == null) {
            // 跑不起来（node 缺失等）：体检本身失效，放行让引擎报它自己的错。
            // ⚠️ 同样不是 Clean —— 见上面那条。
            Log.w(TAG, "启动前体检无法执行，跳过（不影响启动）")
            return PreflightResult.StaticOnly
        }

        val suspects = LinkedHashSet<File>()
        if (first.exitCode != 0) {
            // 坏法①②：stderr 里有绝对路径，引擎自己指认了是哪个文件
            suspects += parseBlamedFiles(first.stderr)
            if (suspects.isEmpty()) {
                // 指认不出来（比如 package.json 的报错只有 JSON 位置、没有路径）：
                // 退到隔离 profile 自己的两个文件，这是唯一可能抛在这两处的来源
                suspects += File(profileDir, "package.json")
                suspects += File(profileDir, PROFILE_PATCH_FILENAME)
            }
        } else {
            // 坏法④：核心 bundle 被 skip 时 required 条目会消失
            val missing = missingRequiredEntries(first.stdout)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "启动前体检：核心条目缺失 ${missing.joinToString(", ")}")
                // 核心 bundle 来自 engine/，用户配置层动不了它 —— 只能整棵重解包
                return rebuildEngine(missing)
            }
            // 坏法③：退出码是 0，只能看 stderr 的跳过行
            suspects += parseSkippedBundles(first.stderr)
        }

        // 落在 engine/ 下的坏文件隔离没有意义（下次解包就覆盖了，而 `.stamp` 短路
        // 意味着没有"下次"）—— 直接整棵重解包
        val engineSide = suspects.filter { it.absolutePath.startsWith(engineDir.absolutePath) }
        if (engineSide.isNotEmpty()) {
            Log.w(TAG, "启动前体检：engine/ 内的 bundle 损坏 ${engineSide.joinToString(", ")}")
            return rebuildEngine(emptyList())
        }

        if (suspects.isEmpty()) return PreflightResult.Clean

        var quarantined = 0
        for (file in suspects) {
            if (quarantine(file, "启动前体检")) quarantined++
        }
        if (quarantined == 0) return PreflightResult.Clean

        // 复检：隔离完再跑一次，确认真的修好了
        val second = runDumpConfig()
        if (second != null && second.exitCode == 0 && missingRequiredEntries(second.stdout).isEmpty()) {
            Log.i(TAG, "启动前体检：已隔离 $quarantined 个损坏的 profile 文件，复检通过")
            return PreflightResult.Repaired
        }

        // 隔离了还不够：坏的不止那几个文件，退到整棵重解包
        Log.w(TAG, "启动前体检：隔离后复检仍未通过，改走整棵重解包")
        return rebuildEngine(emptyList())
    }

    /**
     * P6：判断「该不该**自动**进安全模式」（方案 §4.5）。
     *
     * ## 为什么要有这道门
     *
     * 安全模式此前**只能用户点**，而真机证据（那台 Xiaomi 的两个诊断包里
     * `安全模式` 出现 **0 次**，同期 `启动前体检` 出现 110 次）说明：用户根本
     * 不会点它 —— 界面在启动失败时只是停在浮层上，用户以为"再等等就好了"。
     * 于是「App 一定打得开」这条承诺在**用户不配合**时就断了。
     *
     * ## 为什么门槛定得这么高（三个条件全满足才自动进）
     *
     * L3 是**破坏性**的：推开用户的插件配置 + 删版本戳重解包（约 15 秒）。
     * 现有的克制（"拿不准就别动"）是对的，不能因为要好用就把它变成默认行为。
     * 所以要求**同时**满足：
     *
     * | # | 条件 | 为什么 |
     * |---|---|---|
     * | ① | `consecutiveFailures >= 3` | 连续 3 次真实启动失败。1~2 次可能是偶发（系统内存紧张、端口占用），不值得推开配置 |
     * | ② | `tier == RESCUE` | **L1 与 L2 都已经试过**。这是最要紧的一条：`tier` 只有在「L1 二分没收敛」时才会被升成 RESCUE（见 [maybeIsolateBadBundles] 末尾），所以 `tier == RESCUE` **本身就是**「L1 试过且失败」的机器可判证据。而在救援 profile 下再失败，等于 L2 也试过了 |
     *
     * ⚠️ 条件②**不能**写成「`tier != NORMAL`」：ISOLATED 态意味着 L1 **成功**
     *   摘掉了致命插件，那时该做的是让用户用着（哪怕少了几个插件），而不是把
     *   他剩下的插件**也**推开。判据必须精确指向「L1 已经没辙了」这个状态。
     *
     * ⚠️ 条件②同时也覆盖了「已经在安全模式里」：`SAFE_MODE != RESCUE`，所以
     *   安全模式下这道门自动关闭，不会每次启动都重解包 15 秒。
     *   **不要再补一条 `tier != SAFE_MODE`** —— 那是永远不成立的死判据
     *   （本族已 30 例：写了一条与它要拦的对象对不上的条件）。
     *
     * ⚠️ 也不能只看 `consecutiveFailures`：一个装了坏插件的用户，L1 每次都能
     *   成功隔离（`tier == ISOLATED`），失败计数会因 `clearFailure` 归零 ——
     *   那种情况**本来就不需要**安全模式。加上条件②才不会误伤。
     *
     * ## 第三种日志标记
     *
     * `[preflight]`（自动修）与用户点击（无前缀，见 [quarantine] 的 `reason`）
     * 已经分得清，本方法必须新增**第三种**，否则事后复盘会把「系统自动推开了
     * 用户的配置」误读成「用户自己点的安全模式」—— 两者对用户的责任完全不同。
     * 标记用 `[rescue-auto]`，与那两种都不撞。
     *
     * @return true = 本次确实自动进了安全模式（调用方应据此提示用户）。
     */
    fun shouldAutoEnterSafeMode(): Boolean {
        val f = RescueBoot.lastFailure()
        val st = RescueBoot.tier()
        val streak = f?.consecutiveFailures ?: 0

        val reasons = ArrayList<String>()
        if (streak < AUTO_SAFE_MODE_MIN_FAILURES) reasons += "连续失败仅 $streak 次（需 ≥$AUTO_SAFE_MODE_MIN_FAILURES）"
        if (st.tier != RescueBoot.Tier.RESCUE) reasons += "层级是 ${st.tier.id} 而不是 rescue（L1/L2 还没试过）"

        if (reasons.isEmpty()) {
            Log.w(TAG, "[rescue-auto] 连续 $streak 次启动失败且 L1/L2 都没能救回来，自动进入安全模式")
            EventLog.record(
                "rescue.degrade.l3",
                mapOf("consecutiveFailures" to streak.toString(), "trigger" to "auto"),
            )
            return true
        }
        Log.i(TAG, "[rescue-auto] 不自动进安全模式：${reasons.joinToString("；")}")
        return false
    }

    /**
     * 安全模式：把「模型能改坏的一切」一次性推开，让 App 必定能开起来。
     *
     * ## 它和 [preflight] 的分工
     *
     * [preflight] 是**自动**的，只在能精确指认坏文件时动手（体检判据 + 复检通过），
     * 拿不准就不碰 —— 免得把好配置误伤。但体检覆盖不到的情况真实存在：
     * 坏法超出上面四类、体检超时、或者坏在体检看不见的地方（比如 profile 的
     * `dependencies` 被改得指向一个不存在的包）。那时 App 依然打不开，而用户
     * 手上没有任何出路 —— 只能卸载重装，那会清掉全部会话与凭据。
     *
     * 本方法就是那个出路：**用户在失败浮层上点一下**，然后 App 一定开得起来。
     * 它是破坏性的、由人显式触发的，所以不需要"拿不准就别动"的克制。
     *
     * ## 推开哪三样（都不碰用户数据）
     *
     * | 推开什么 | 为什么 |
     * |---|---|
     * | profile 用户 patch 层 | 模型最爱改的就是它，且改坏会让引擎 exit 1 |
     * | 第三方 bundle（保留核心的 deepseek-ai 前缀那些） | 插件装坏 / 版本对不上是常见死因 |
     * | `engine/.stamp` | 强制重解包，把 `engine/` 里的改动全部还原 |
     *
     * ⚠️ 上面那条里**不能**写 `@deepseek-ai` 加斜杠星号：Kotlin 的块注释支持嵌套，
     * 那两字符会开始一层嵌套注释，把下面整段代码吞进注释里（编译报
     * `Missing '}'` + `Unclosed comment`，且报错行号指向完全无关的地方）。
     *
     * ⚠️ **会话、凭据、工作区都在 `$DSH_HOME` 与 `workspace/`，不在这三样里**，
     * 所以安全模式不会丢用户数据。代价只是重解包那约 15 秒。
     *
     * ⚠️ 三样都是**改名不删**（见 [quarantine]）：安全模式只负责让 App 能打开，
     * 不该替用户销毁配置 —— 用户想找回改坏前的内容，`.bak-<时间戳>` 还在那儿。
     *
     * @return true = 至少改动了一样（调用方据此提示用户）；false = 三样本来就都干净。
     */
    suspend fun enterSafeMode(): Boolean = withContext(Dispatchers.IO) {
        stop()

        val profileDir = File(dshHome, "profiles/$PROFILE_NAME")
        var changed = false

        // ① 用户配置层：模型改坏它的形态最多（语法坏、patch 目标已消失）
        if (quarantine(File(profileDir, PROFILE_PATCH_FILENAME), "安全模式")) changed = true

        // ② 第三方 bundle：只留 @deepseek-ai/* 的核心 bundle。
        //    改的是 profile 的清单，不是插件包本身 —— 包还在 node_modules 里，
        //    想装回来只要重新勾一次。
        if (stripThirdPartyBundles(profileDir)) changed = true

        // ③ 第三方插件**包本身**：把 node_modules 里非核心的目录整个挪走。
        //    ⚠️ 这一步是 ② 之外**另一道**、而且是更要紧的一道，理由见 [quarantineThirdPartyPackages]：
        //    ② 只改「清单里写没写它」，而包还在原地时，任何一条没被枚举到的加载路径
        //    （patch 层里的 insert、引擎自己的兜底扫描、后续版本新增的入口）都能把它捡回来。
        //    真机现场（OPPO PHY110）：用户点「进入安全模式」后界面**仍旧**停在
        //    `Failed to load plugins` —— 因为坏插件是 AI 手工塞进 node_modules 的，
        //    清单摘了、包还在，前端那一批合并 bundle 里照样有它的坏段。
        if (quarantineThirdPartyPackages(profileDir)) changed = true

        // ④ 引擎目录：删戳 = 下次启动重解包，把 engine/ 下的改动全部还原
        if (stampFile.isFile && stampFile.delete()) {
            Log.w(TAG, "安全模式：已删除引擎版本戳，将重新解包 engine/")
            changed = true
        }

        // ⑤ Node 运行时：上面几样都在 $DSH_HOME / engine/ 里，**都不碰 $PREFIX**，
        //    而 node 损坏会让引擎连一行 JS 都跑不到 —— 真机实测那种坏法报
        //    `Check failed: magic_number_ == SerializedData::kMagicNumber`（V8 反序列化
        //    内嵌启动快照，早于任何 JS）。不带上这一步，用户点了安全模式也照样打不开，
        //    唯一出路是卸载重装，而卸载会清掉整个 $DSH_HOME。
        //    node 可用时这一步是零成本的（一次 node --version）。
        if (bootstrap.repairNodeRuntimeIfNeeded()) {
            Log.w(TAG, "安全模式：内置 Node 运行时已重新解包")
            changed = true
        }

        // ⚠️ 必须把层级记成 SAFE_MODE，不能只做改动不记状态。两个后果都会真实发生：
        //
        //   ① **横幅会说谎**（违反不变量 I4）：`refreshRescueBanner` 读的就是这个
        //      `tier`，不记的话它仍显示「救援模式」而实际跑的是安全模式 ——
        //      又一处「结论与它描述的对象不是同一个」（本族已 30 例）。
        //   ② **P6 的自动触发会重复**：那道门的判据是 `tier == RESCUE`，
        //      不记的话它一直是 RESCUE，下次失败又自动推开一次配置（破坏性动作
        //      重复执行，且每次白等 15 秒重解包）。
        //
        //   位置在最后：`changed == false` 时也记 —— 安全模式**本来就是**「推开
        //   坏配置」，三样都干净只是说明这次没东西可推，但「用户/系统选择了安全
        //   模式」这个事实仍成立，界面必须照实显示。
        RescueBoot.setTier(
            RescueBoot.Tier.SAFE_MODE,
            "安全模式：推开损坏的配置并重新解包引擎（改动=$changed）",
        )

        Log.w(TAG, "安全模式：已推开损坏的配置（改动=$changed），准备重启引擎")
        changed
    }

    /**
     * 把 profile 清单里的**第三方** bundle 摘掉，只留 deepseek-ai 前缀的核心 bundle（幂等）。
     *
     * ⚠️ 用 `JSONObject` 解析再回写，不搞正则替换：profile 清单是 JSON，
     * 而"注释/尾逗号"这类东西在 JSON 里本来就不合法，引擎自己也读不了。
     * 解析失败（清单已经坏了）时**直接隔离整份清单** —— 引擎发现清单缺失会
     * 按模板重建一份核心的，这正是安全模式想要的结果。
     *
     * ⚠️ 回写前先备份原文件（[quarantine]），用户想恢复第三方插件时有据可依。
     *
     * @return true = 确实摘掉了东西。
     */
    private fun stripThirdPartyBundles(profileDir: File): Boolean {
        val manifest = File(profileDir, "package.json")
        if (!manifest.isFile) return false
        val json = try {
            JSONObject(manifest.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "安全模式：profile 清单已损坏（${t.message}），整份隔离")
            return quarantine(manifest, "安全模式")
        }

        val dsh = json.optJSONObject("dsh") ?: return false
        val profile = dsh.optJSONObject("profile") ?: return false
        val bundles = profile.optJSONArray("bundles") ?: return false

        val kept = ArrayList<String>(bundles.length())
        var dropped = 0
        for (i in 0 until bundles.length()) {
            val name = bundles.optString(i, "")
            // 核心 bundle 一律保留：它们来自 engine/，重解包就会还原成好的
            if (name.startsWith("@deepseek-ai/")) kept += name else dropped++
        }

        // ⚠️ `dependencies` **也要一起清**，这是「摘掉全部插件」的另一半。
        //   清单里除了 `dsh.profile.bundles`，`dependencies` 同样列着用户装的包；
        //   引擎启动时会做依赖解析，列着解析不到的包会直接报
        //   `cannot resolve profile bundle` —— 那正是「点了安全模式还进不去」的
        //   另一种成因（[enterSafeMode] 的 KDoc 里点名过这种坏法）。官方桌面端的
        //   「摘掉全部插件并重新加载」就是两处一起动的，这里照同一语义办。
        //   核心包（`@deepseek-ai/`）保留，否则 App 自己的 patch 层依赖会断。
        var depsDropped = 0
        json.optJSONObject("dependencies")?.let { deps ->
            for (key in deps.keys().asSequence().toList()) {
                if (!key.startsWith("@deepseek-ai/")) {
                    deps.remove(key)
                    depsDropped++
                }
            }
        }

        if (dropped == 0 && depsDropped == 0) return false

        // 先备份再写：摘掉的是用户装的插件，配置本身有价值。
        // ⚠️ 备份失败就**不要写** —— 否则会把唯一一份原始清单覆盖掉。
        if (!quarantine(manifest, "安全模式")) {
            Log.w(TAG, "安全模式：备份 profile 清单失败，放弃摘除第三方 bundle")
            return false
        }

        profile.put("bundles", JSONArray(kept))
        dsh.put("profile", profile)
        json.put("dsh", dsh)
        return try {
            // ⚠️ `JSONObject.toString()` 会把 `/` 转义成 `\/`（org.json 防 `</script>`
            // 的历史行为）。那在 JSON 里**完全合法**（引擎实测读得进去），但这份文件
            // 用户和模型都会看，`@deepseek-ai\/dsh-base` 长得就像文件已经坏了 ——
            // 而"配置看起来坏了"正是本方法要消灭的状态。还原回去。
            //
            // 安全性：`\/` 与 `/` 在 JSON 里语义完全相同，还原不会产生歧义 ——
            // 反斜杠本身在 org.json 的输出里是 `\\`，所以 `\\\/`（原串为 `\` + `/`）
            // 还原成 `\\/` 后仍解析为 `\` + `/`，与还原前一致。
            manifest.writeText(json.toString(2).replace("\\/", "/"))
            Log.w(
                TAG,
                "安全模式：已摘掉 $dropped 个第三方 bundle" +
                    (if (depsDropped > 0) " + $depsDropped 个第三方依赖" else "") +
                    "，保留 ${kept.joinToString(", ")}",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "安全模式：回写 profile 清单失败: ${t.message}")
            false
        }
    }

    /**
     * 把 profile 的 `node_modules/` 下**第三方**插件包整体挪进隔离目录（幂等）。
     *
     * ## 为什么摘了清单还要摘包
     *
     * [stripThirdPartyBundles] 改的是「引擎照着清单去不去加载它」，本方法改的是
     * 「它还在不在那儿」。两道都要有：
     *
     * | 只做其中一道 | 后果 |
     * |---|---|
     * | 只摘清单 | 包还在原地，patch 层里的 insert、引擎的兜底扫描、日后新增的加载入口都可能把它捡回来 ⇒ **摘了等于没摘** |
     * | 只挪包 | 清单里还留着名字，引擎解析不到会报 `cannot resolve profile bundle`，也可能被算成致命 |
     *
     * 真机现场（用户「做插件做到一半退出 App」，界面停在 `Failed to load plugins`）：
     * 坏插件是 AI **手工**塞进 `node_modules/` 的（绕过了 pnpm），只摘清单之后界面
     * **仍旧**打不开 —— 因为前端那一批合并 bundle 里照样有它的坏段。
     *
     * ## 挪到哪、能不能找回来
     *
     * 挪进 `profiles/<profile>/.safe-mode-plugins/`，**改名不删**（同 [quarantine]
     * 的约定）：安全模式只负责让 App 能打开，不替用户销毁插件 —— 想装回来重新勾一次，
     * 或者直接从那个目录拷回去都行。
     *
     * ⚠️ 点开头的条目（`.bin` / `.pnpm` / `.ignored` / `.modules.yaml`）一律不动：
     * 它们是包管理器的簿记，不是插件包 —— 尤其 `.ignored` 是 pnpm 刻意驱逐的，
     * 动它反而会让 pnpm 的下一轮安装行为异常。
     *
     * ⚠️ `@deepseek-ai/` 下的核心包一律保留：它们来自 `engine/`，重解包就会还原成好的，
     * 而且 App 自己的 patch 层（mobile-ui 等）依赖它们。
     *
     * @return true = 确实挪走了东西。
     */
    private fun quarantineThirdPartyPackages(profileDir: File): Boolean {
        val nm = File(profileDir, "node_modules")
        if (!nm.isDirectory) return false
        val spare = File(profileDir, SAFE_MODE_PLUGIN_DIR)
        var moved = 0

        // 挪一个实体；目标已存在时加时间戳，绝不覆盖（那是用户上一次的插件）
        fun moveInto(src: File, dest: File): Boolean {
            if (!src.exists()) return false
            val target = if (dest.exists()) {
                File(dest.parentFile, "${dest.name}.bak-${System.currentTimeMillis()}")
            } else {
                dest
            }
            target.parentFile?.mkdirs()
            if (src.renameTo(target)) return true
            // 同卷内 renameTo 正常不会失败；失败（跨卷 / 被占用）时退回拷贝，
            // 宁可慢也不能漏 —— 漏一个就意味着安全模式可能仍然进不去。
            return try {
                src.copyRecursively(target, overwrite = false)
                src.deleteRecursively()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "安全模式：挪走 ${src.name} 失败: ${t.message}")
                false
            }
        }

        val entries = nm.listFiles() ?: return false
        for (entry in entries) {
            val n = entry.name
            if (n.startsWith(".")) continue
            if (n == "@deepseek-ai") continue
            if (n.startsWith("@")) {
                // scoped 包：逐个成员判，只挪非核心的
                val members = entry.listFiles() ?: continue
                for (member in members) {
                    if (member.name == "deepseek-ai") continue
                    if (moveInto(member, File(File(spare, n), member.name))) moved++
                }
                // scope 目录空了就删掉，免得留个空壳给引擎的扫描看
                if (entry.listFiles()?.isEmpty() == true) entry.delete()
            } else if (moveInto(entry, File(spare, n))) {
                moved++
            }
        }

        if (moved > 0) {
            Log.w(
                TAG,
                "安全模式：已挪走 $moved 个第三方插件包 → $SAFE_MODE_PLUGIN_DIR/（想装回来重新勾一次即可）",
            )
        }
        return moved > 0
    }

    /**
     * 体检输出里缺了哪些 required 条目（见 [REQUIRED_ENTRY_IDS]）。
     *
     * ⚠️ 匹配要**行级**：`dump-config` 的输出里每行都是 `- id: xxx`，用
     * `contains("- id: webserver")` 会误命中 `webserver-xxx` 之类的前缀相同项。
     */
    private fun missingRequiredEntries(dumpOutput: String): List<String> {
        val ids = dumpOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- id: ") }
            .map { it.removePrefix("- id: ").trim() }
            .toHashSet()
        return REQUIRED_ENTRY_IDS.filterNot { it in ids }
    }

    /**
     * 强制重解包 `engine/`：删掉版本戳，然后**当场**重解包一次。
     *
     * ⚠️ **这是唯一能修「`engine/` 目录被改坏」的手段**：`ensureExtracted()` 有
     * `.stamp` 短路（见该方法），AI 改了 `engine/` 下的文件之后**永远不会被还原**。
     * 删掉戳之后必须**立刻**重解包，不能等下次启动 —— 本方法在 `start()` 里跑，
     * 而 `MainActivity` 的 `ensureExtracted()` 调用已经过去了，光删戳这一轮仍会用
     * 坏掉的引擎目录起来（真机实测就是这么踩的）。
     *
     * ⚠️ 会话、凭据、工作区都在 `$DSH_HOME` 与 `workspace/`，**不在 `engine/`**，
     * 所以这一下不会丢用户数据；代价只是重新解包那约 15 秒。
     *
     * ⚠️ 调用点在 `start()` 的最前段（早于所有往 `engine/` 写东西的 ensureXxx），
     * 否则重解包会把那些补丁抹掉。见 `start()` 里的顺序注释。
     *
     * @param missing 体检发现缺失的 required 条目 id（仅用于日志）。
     */
    private suspend fun rebuildEngine(missing: List<String>): PreflightResult {
        // 用户配置层也可能是共犯（比如它 patch 的目标行已随 bundle 一起消失）
        quarantine(File(dshHome, "profiles/$PROFILE_NAME/$PROFILE_PATCH_FILENAME"), "启动前体检")

        val stamp = lastAssetStamp
        if (stamp == null) {
            // 没拿到版本戳（ensureExtracted 还没被调用过）：只能删戳，让下次启动重解包
            if (stampFile.delete()) Log.w(TAG, "启动前体检：已删除引擎版本戳，下次启动将重新解包")
            return PreflightResult.Repaired
        }

        Log.w(
            TAG,
            "启动前体检：核心 bundle 损坏（缺 ${missing.joinToString(", ").ifEmpty { "未指认" }}），" +
                "正在重新解包 engine/（约 15 秒）",
        )
        if (stampFile.isFile) stampFile.delete()
        return try {
            ensureExtracted(stamp)
            val check = runDumpConfig()
            val stillMissing = check?.let { missingRequiredEntries(it.stdout) }.orEmpty()
            if (check != null && check.exitCode == 0 && stillMissing.isEmpty()) {
                Log.i(TAG, "启动前体检：重解包完成，复检通过")
                PreflightResult.Repaired
            } else {
                // 重解包都修不好 ⇒ 不是 engine/ 的问题（多半是设备环境），如实报出来
                Log.e(TAG, "启动前体检：重解包后仍未通过（缺 ${stillMissing.joinToString(", ")}）")
                PreflightResult.Repaired
            }
        } catch (t: Throwable) {
            // ensureExtracted 自己会把 State 置成 Failed 并抛出；这里不再吞掉它，
            // 让 start() 的调用方看到真实的解包失败原因
            Log.e(TAG, "启动前体检：重新解包失败", t)
            throw t
        }
    }


    /** [runDumpConfig] 的结果。 */

    /**
     * 一次「试启动」的结果（P1 二分法 / P3 `try-boot` 共用）。
     *
     * ⚠️ **`internal` 而不是 `private`**：`RescueCommandBridge` 要把它转成
     *   JSON 回执给 AI。做成 `private` 的话桥就只能自己重新解析一次引擎输出 ——
     *   那是同一件事的两份实现，正是本项目缺陷族的温床。
     *
     * @param ready 引擎是否打印了带 token 的 URL —— **这是"能起来"的唯一证据**。
     *   不变量 I6：任何静态检查的结论都不能替代它。
     * @param requiredDidNotActivate 引擎自称没激活的 required 插件数；没提到时为 null。
     * @param tail 引擎输出的尾部（已截断），给 AI 与人看。
     */
    class ProbeResult(
        val ready: Boolean,
        val exitCode: Int?,
        val requiredDidNotActivate: Int?,
        val tail: List<String>,
    ) {
        /** 是不是「插件问题导致起不来」——判据见 [RescueBoot.Failure.looksLikePluginProblem]。 */
        val looksLikePluginProblem: Boolean
            get() = requiredDidNotActivate != null && requiredDidNotActivate > 0
    }


    private val isolationTransaction by lazy {
        PluginIsolationTransaction(dshHome, engineDir) { profile, overlays -> probeBoot(profile, overlays) }
    }
    private fun quarantine(file: File, reason: String) = isolationTransaction.quarantine(file, reason)
    private fun preflightPluginSyntax(entry: File) = PluginSyntaxPreflight.preflightPluginSyntax(entry)

    private val probeRunner = EngineProbeRunner()

    private fun probeBoot(profile: String, overlays: List<File>, timeoutMs: Long = PROBE_TIMEOUT_MS): ProbeResult? {
        val baseline = activeLaunchSpec
        if (baseline == null || baseline.environment.isEmpty()) {
            Log.w(TAG, "试启动放弃：尚无真实启动的环境变量基线（activeEnv 为空）")
            return null
        }
        return probeRunner.probeBoot(baseline.forProbe(profile, overlays), timeoutMs)
    }

    /** dump-config 在安装前运行：保留继承宿主环境、无 overlay 的既有行为。 */
    private fun runDumpConfig(): EngineProbeRunner.DumpResult? {
        val node = bootstrap.nodeBinary
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!node.isFile || !entry.isFile) return null
        val prefix = bootstrap.prefixDir.absolutePath
        val builder = ProcessBuilder(node.absolutePath, entry.absolutePath,
            "--profile", PROFILE_NAME, "--dump-config").directory(workspaceDir)
        builder.environment().apply {
            put("PREFIX", prefix)
            put("DSH_HOME", dshHome.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
            put("NODE_OPTIONS", nodeOptions())
        }
        return probeRunner.runDumpConfig(builder)
    }

    @Volatile
    private var activeLaunchSpec: EngineLaunchSpec? = null

    /**
     * 当前生效的 `--patch` overlay 列表（由 [start] 在启动时算好后存下）。
     *
     * ⚠️ **刻意"存下来"而不是"再算一遍"**：`--patch` 列表由 `start()` 里十几个
     *   `ensureXxx`/`writeXxxPatch` 的返回值组成，重算一遍要再调一遍那十几个函数
     *   （它们有副作用：解包、写文件、起进程）。更要紧的是——**重算出来的可能与
     *   真实启动用的不是同一份**，于是「试启动能过、真启动过不了」这种差异
     *   就无法排查了。存下来的那一份与真实启动**逐项相同**（不变量 I1 的另一面：
     *   探测必须与它要预测的那件事同构）。
     *
     * ⚠️ 空列表 = 还没启动过。试启动在这种情况下**不是**"没有 overlay"，
     *   而是"不知道真实启动用了什么"，所以 [probeBoot] 的调用方（救援信箱）
     *   保证只在引擎起来之后才发探测。
     */
    @Volatile
    private var activeOverlays: List<File> = emptyList()

    /**
     * 真实启动**实际用的**那一份环境变量（由 [start] 算好后存下）。
     *
     * ⚠️ **这是"试启动与真实启动同构"的结构性保证**，不是缓存优化。
     *
     * 试启动（[probeBoot]）的结论要用来预测"改完配置之后真实启动能不能过"。
     * 如果两边各自拼一遍环境变量，差异就只能靠人肉比对才能发现，而症状是
     * **「试启动能过、真启动过不了」**——那时人已经不知道该信哪一个了。
     *
     * 真机上会让两边分叉的例子（都是 `start()` 里有、想当然重拼时会漏的）：
     *
     * | 变量 | 漏掉的后果 |
     * |---|---|
     * | `LD_PRELOAD` | link 转译层不生效 ⇒ 试启动报一堆 link EACCES，**指向错误方向** |
     * | `BOO_SANDBOX_LIB` | 沙箱 runner 失效 ⇒ 报沙箱后端不可用，同样与插件无关 |
     * | `DSH_PRIV_RUNNER` / `DSH_ROOT_RUNNER` | 特权和 root 插件读不到通道路径 |
     * | `AGNES_CREDENTIAL` | 号池注入层拿不到凭据引用 |
     * | `TERMUX_PKG_NO_MIRROR_SELECT` 等镜像组 | 探测进程可能去连慢源，**超时被误判成插件问题** |
     *
     * ⚠️ 空 = 还没真实启动过。此时 [probeBoot] **拒绝探测**（返回 null）而不是
     *   用一份"差不多"的环境凑合 —— 那样出来的结论不可信，而不可信的探测
     *   比没有探测更糟：L1 的二分法会据此摘错插件。
     */
    @Volatile
    private var activeEnv: Map<String, String> = emptyMap()

    /**
     * 供 `RescueCommandBridge` 调用的试启动入口。
     *
     * ⚠️ **阻塞十几秒**，只能在后台线程调用（桥已经把 probe 放在
     *   `dsh-rescue-probe` 单线程池里）。
     *
     * @param bundleFilter 非空时：**临时**把 `web` 清单的 bundles 换成
     *   `core + 这些名字`，跑一次试启动，然后**无条件还原**原来的清单。
     *   这就是方案 §4.4.4 的 `bundleFilter`，也是 §8 承诺的
     *   「try-boot 不直接改 web profile」的实现方式 —— 用户磁盘上的配置
     *   在探测前后**逐字节相同**。
     */
    fun probeBootForBridge(
        profile: String,
        bundleFilter: List<String>? = null,
    ): ProbeResult? {
        if (bundleFilter == null) {
            // 探测是**只读**的：不写 [RescueBoot.recordFailure]（见上）。
            return probeBoot(profile = profile, overlays = activeOverlays)
        }
        return isolationTransaction.probeWithBundleFilter(profile, bundleFilter, activeOverlays)
    }


    // ─────────────────────────────────────────────────────────────────────
    // P1：L1 自动隔离（基线 + 二分）
    // ─────────────────────────────────────────────────────────────────────

    /** [isolateBadBundles] 的结果，供写 `rescue/state.json` 与日志用。 */
    internal class IsolationResult(
        /** 最终被摘掉的第三方 bundle（只写**已证实致命**的那些）。 */
        val removed: List<String>,
        /** 给人看的一句话结论。 */
        val reason: String,
        /** 做了几次真实试启动（基线算一次）。 */
        val attempts: Int,
        /** 摘掉之后是否**确实**能起来（收尾确认过）。 */
        val verified: Boolean,
    )

    /**
     * L1 自动隔离的**触发闸门**（真正的算法在 [isolateBadBundles]）。
     *
     * 把「该不该做」与「怎么做」分开，是因为触发条件本身就是本方案最容易写错的
     * 一处：方案原稿写的「同一 bootId 内连续失败 ≥2 次」在真机上**永不成立**，
     * 已由 30 个诊断包实测证伪（见 [RescueBoot.Failure.consecutiveFailures]）。
     *
     * ## 四条闸门，缺一不可
     *
     * ① `tier == NORMAL` **或** `tier == QUARANTINED`：救援态/隔离态下没有可隔离
     *    的对象（救援 profile 压根不含第三方插件）。不判这一条会在救援态反复跑
     *    二分，把界面搅乱。
     *    ⚠️ `QUARANTINED` 必须放行：它是「上一轮禁掉一个、还没验证成功」的
     *    **进行中**状态，挡掉它会让用户卡在死循环里（见方法内注释）。
     * ② `consecutiveFailures >= 2`：单次失败可能是系统回收 —— 与现有
     *    `MAX_ENGINE_RESTARTS = 2` 的口径一致。
     * ③ `looksLikePluginProblem`：引擎**自己**说了「required plugin did not
     *    activate」，**或**失败清单里含非官方包（见 `Failure.hasThirdPartyFailure`）。
     *    ⚠️ 后半条是 2026-10-17 加的：`requiredStartupEntryIds` 是**白名单**，
     *    用户自己写的插件不在里面，它失败时引擎只 warn、不打那句 required 文案。
     *    只有前半条时，L1 二分对**用户插件永远不触发** —— 而那正是本功能的目标。
     *    ⚠️ 不能用「日志里有 Failed plugins 段落」代替 —— 那一段里绝大多数是
     *    正常的 skip，真机上 `Failed plugins (40)` 时引擎仍能起来过。
     * ④ 环境基线（[activeEnv]）就绪：二分靠 [probeBoot] 判真假，而它的结论只有在
     *    与真实启动同构时才可信 —— 同构的载体就是那一份环境变量表。
     *
     *    ⚠️ **不要**在这里判 `activeOverlays.isEmpty()`。空 overlay 列表是**合法**
     *    状态（一个 patch 都没写出来时真实启动也是空的，两边仍然同构），
     *    把它当"未就绪"会让一种正常配置永远无法进入 L1 —— 又一个"判据与对象不符"。
     *
     * @return 做了什么；没触发时返回 null。
     */
    private suspend fun maybeIsolateBadBundles(): IsolationResult? {
        val st = RescueBoot.tier()
        // ⚠️ [RescueBoot.Tier.QUARANTINED] **必须**放行（与 NORMAL 同等待遇）：
        //   它意味着「上一轮已禁掉一个坏插件、但还没验证成功」。若在这里挡掉，
        //   用户就会卡在「每次启动都禁同一个插件、每次都起不来」的死循环里 ——
        //   正是闸门①的注释警告的那种「停在原地、永远到不了下一层」。
        //   QUARANTINED 的语义是**进行中**，不是终点。
        if (st.tier != RescueBoot.Tier.NORMAL && st.tier != RescueBoot.Tier.QUARANTINED) {
            Log.i(TAG, "L1 隔离：当前层级 ${st.tier.label}，跳过")
            return null
        }
        val f = RescueBoot.lastFailure() ?: return null
        if (f.consecutiveFailures < 2) {
            Log.i(TAG, "L1 隔离：连续失败 ${f.consecutiveFailures} 次（<2），本次不触发")
            return null
        }
        if (!f.looksLikePluginProblem) {
            // ⚠️ 这里**必须真的推进到 L2**，不能只打一行日志就返回。
            //
            //   原先的代码正是「日志写『直接交 L2』、代码却只 `return null`」——
            //   于是 tier 停在 NORMAL，没有任何一处会把它改成 RESCUE，结果是
            //   **每次启动都从头再来一遍**（等 20 秒 → 失败 → 计数 +1 → 还是
            //   NORMAL），永远到不了救援模式、也永远到不了 L3。
            //   用户看到的是「App 一直起不来」，而日志里那句「直接交 L2」
            //   让排查的人以为已经交过去了 —— 这正是本项目缺陷族
            //   （结论/日志与它描述的事实不符）的第 31 例。
            //
            //   方案 §4.3 对此有明确要求：「失败 ⇒ 不是插件问题（或不止插件问题），
            //   跳到 L2」。L2 是**非破坏性**的（换一个最小 profile，不动用户配置，
            //   有常驻横幅 + 一键退出），所以即使这次判断偏保守也不会造成损害；
            //   而停在 NORMAL 是**确定**打不开。
            //
            //   ⚠️ 判据仍要求 `consecutiveFailures >= 2`（上面那道门），所以
            //     单次偶发失败不会把用户丢进救援模式。
            Log.w(
                TAG,
                "L1 隔离：连续失败 ${f.consecutiveFailures} 次，但引擎没报 required 插件未激活" +
                    " ⇒ 不是插件问题，降级到 L2 救援模式",
            )
            escalateToRescue(
                "连续 ${f.consecutiveFailures} 次启动失败，且引擎未报 required 插件未激活（不是插件问题）",
            )
            return null
        }
        if (activeEnv.isEmpty()) {
            // 调用点已在 [activeEnv] 赋值之后，所以这条是纯防御（理论上到不了）。
            // 真的到了也不能硬跑：没有同构基线的探测结论会指向错误的方向。
            Log.w(TAG, "L1 隔离：环境基线为空，无法做同构探测，本轮不触发")
            return null
        }

        Log.w(
            TAG,
            "L1 隔离启动：连续 ${f.consecutiveFailures} 次启动失败，" +
                "引擎报 ${f.requiredDidNotActivate} 个 required 插件未激活",
        )
        EventLog.record(
            "rescue.isolate.start",
            mapOf("consecutiveFailures" to f.consecutiveFailures.toString()),
        )

        val r = isolationTransaction.isolateBadBundles(activeOverlays)
        if (r == null) {
            // ⚠️ `isolateBadBundles` 返回 null 意味着「L1 放弃了」：清单缺失、
            //   清单解析失败、或备份失败。**这三种都必须推进到 L2**，理由与上面
            //   那条「不是插件问题」完全相同 —— 不推进的话 tier 停在 NORMAL，
            //   下一次启动又从头跑一遍、又放弃，永远到不了 L2、也永远到不了 L3。
            //
            //   这不是新加的策略，而是本方法**唯一的目的**：只要 L1 没解决问题，
            //   就必须把控制权交给下一层。方案 §4.2 的「一定打得开」正是靠这条
            //   链子成立的（L1 → L2 → L3，每一环都必须真的接上）。
            //
            //   ⚠️ 注意 L2 **不改用户的任何配置**（它只是换一个最小 profile 启动，
            //     有常驻横幅 + 一键退出），所以这里推进不会造成损害；而停在
            //     NORMAL 是**确定**打不开。
            Log.w(TAG, "L1 隔离：未能给出结论（见上面那行）⇒ 降级到 L2 救援模式")
            escalateToRescue("L1 无法给出结论（清单缺失/解析失败/备份失败）")
            return null
        }
        Log.w(TAG, "L1 隔离结论：${r.reason}（试启动 ${r.attempts} 次，已验证=${r.verified}）")
        EventLog.record(
            "rescue.isolate.done",
            mapOf(
                "removed" to r.removed.size.toString(),
                "attempts" to r.attempts.toString(),
                "verified" to r.verified.toString(),
            ),
        )
        // 有插件被摘 ⇒ 记 ISOLATED 态（I4：降级必须常驻可见 + 可一键退出）。
        // ⚠️ 只在**确实摘了东西**时才记：`isolateBadBundles` 在"不是插件问题"时
        //   返回空列表，那种情况下不该让用户看到"有插件被隔离了"。
        if (r.removed.isNotEmpty()) {
            RescueBoot.setTier(
                tier = RescueBoot.Tier.ISOLATED,
                reason = r.reason,
                removedBundles = r.removed,
                restorePoint = "profiles/$PROFILE_NAME/package.json.bak-l1",
            )
            // ⚠️ 半成功（摘了、但收尾没验证通过）时，**额外**把已定位的插件
            //   记进定向隔离层：`package.json` 那条路已经证明不够（还有别的
            //   致命插件或预算耗尽），而 `disabled: true` 层是**下一轮启动就会
            //   生效**的第二道保险 —— 即使 `package.json` 被 pnpm 重写、
            //   被用户改回去、或 `bundles` 列表被重新生成，隔离层依然独立生效。
            //
            //   只在 `!r.verified` 时记：已验证成功的那条路（ISOLATED）语义更轻，
            //   不该让用户看到「已禁用」这种更重的措辞。
            if (!r.verified) {
                r.removed.forEach { RescueBoot.addQuarantine(it) }
                RescueBoot.setTier(
                    tier = RescueBoot.Tier.QUARANTINED,
                    reason = r.reason,
                    removedBundles = r.removed,
                    restorePoint = "profiles/$PROFILE_NAME/package.json.bak-l1",
                )
            }
            return r
        }

        // ── L1 没摘到东西 ⇒ **必须往前推进到 L2** ──────────────────────────
        //
        // ⚠️ 这一步不是可选的。不推进的话 tier 停在 NORMAL，于是**每次启动都
        //   重跑一遍完整的 L1**（基线 + 最多 6 轮二分 ≈ 3 分钟），而结论永远是
        //   "解决不了" —— 用户看到的是「每次打开 App 都要黑屏等三分钟，然后还是
        //   起不来」。那比现在（至少界面能出来）更糟，而且**永远到不了 L2**，
        //   直接违背本方案的唯一硬承诺「一定打得开」。
        //
        // 走到这里只有两种情形，**两种都该进 L2**：
        //   a) 全摘之后仍起不来（`removed` 为空）⇒ 不是插件问题，web profile 这条路
        //      已经证明走不通，只能换干净的 rescue profile。
        //   b) 二分没收敛 / 预算耗尽（`removed` 为空）⇒ 可能是多致命插件或耗时过长，
        //      再花三分钟重跑一遍的期望收益是负的，同样该先进 L2 把界面给用户。
        //
        // ⚠️ 用 `RESCUE` 而不是别的层级：语义就是"这次不用用户配置，用最小配置起"。
        //   代价是用户这次看不到自己的插件 —— 但那正是"一定打得开"的定价，
        //   而且 `state.json` 里记着原因、界面常驻提示、可一键退出（I4）。
        Log.w(TAG, "L1 隔离未能定位致命插件（${r.reason}）⇒ 降级到 L2 救援模式")
        escalateToRescue("${r.reason}（试启动 ${r.attempts} 次）")
        return r
    }

    /**
     * 把层级推进到 L2 救援模式（L1 没解决问题时的**唯一**出口）。
     *
     * ## 为什么单独抽出来
     *
     * L1 有**四条**「我解决不了」的路径（不是插件问题 / 清单缺失 / 清单解析失败 /
     * 备份失败 / 二分不收敛），它们**必须做完全相同的一件事**：把 tier 记成
     * RESCUE，让下一次启动走最小 profile。
     *
     * ⚠️ 早先的写法是每条路径各写一遍，结果是**有的路径忘了写** ——
     *   日志说「直接交 L2」而代码只 `return null`，于是 tier 停在 NORMAL，
     *   每次启动都重跑一遍 L1 然后放弃，**永远到不了 L2**（也就永远到不了 L3）。
     *   四条路径共用一个函数，这类「漏了一条」的缺陷在结构上就不可能再发生。
     *
     * ⚠️ 这里**只改 `rescue/state.json`，不碰用户的任何配置**。L2 是
     *   非破坏性的（换一个最小 profile 启动，原配置原地不动、有常驻横幅、
     *   可一键退出），所以这条路径可以放心走。
     */
    private fun escalateToRescue(reason: String, from: String = "l1") {
        // ⚠️ `from` 不能写死 "l1"：本函数还有第二个调用方 —— [start] 的
        //   「引擎起来了但 gateway 废了」那条路（**没经过 L1 二分**）。
        //   写死会让事件时间线把一次 NORMAL→RESCUE 的直接降级记成 L1 的产物，
        //   排查时按 `from=l1` 找证据会落空（同族缺陷：「结论与它描述的对象不符」）。
        EventLog.record("rescue.degrade.l2", mapOf("from" to from, "reason" to reason))
        RescueBoot.setTier(tier = RescueBoot.Tier.RESCUE, reason = "自动降级：$reason")
    }

    /**
     * 从 stderr 里解析引擎自己指认的坏文件路径。
     *
     * 引擎的报错长这样（真机原文，`--dump-config` 下）：
     * ```
     * Error: dsh: failed to parse overlay /data/.../profiles/web/cordis.patch.yml: YAMLException: ...
     * Error: dsh: failed to read profile manifest /data/.../profiles/web/package.json: ...
     * ```
     * 只接受**落在 `$DSH_HOME` 或 `engine/` 以内**的路径 —— 别的一律不动。
     */
    private fun parseBlamedFiles(stderr: String): List<File> {
        val out = ArrayList<File>()
        for (line in stderr.lineSequence()) {
            val match = BLAMED_PATH.find(line) ?: continue
            val file = File(match.groupValues[1])
            if (isWithinManagedRoots(file)) out += file
        }
        return out
    }

    /**
     * 从 stderr 的 `skipping profile bundle "x": ...` 行里，反查出该 bundle 的目录。
     *
     * 真机原文：`dsh: skipping profile bundle "dsh-ppt-master": Error: ... failed to parse
     * overlay /data/.../node_modules/dsh-ppt-master/cordis.patch.yml: ...`。
     * 报错行里带的那个 overlay 路径就是坏文件，直接隔离它 —— 比按包名去猜目录可靠。
     *
     * ⚠️ 返回值里可能含**落在 `engine/` 下**的路径（核心 bundle 被 skip 时）——
     * 那类文件隔离没有意义（会被重解包覆盖），由调用方据此改走整棵重解包。
     */
    private fun parseSkippedBundles(stderr: String): List<File> {
        val out = ArrayList<File>()
        for (line in stderr.lineSequence()) {
            if (!line.contains("skipping profile bundle")) continue
            Log.w(TAG, "启动前体检：${line.trim()}")
            val match = BLAMED_PATH.find(line) ?: continue
            val file = File(match.groupValues[1])
            if (isWithinManagedRoots(file)) out += file
        }
        return out
    }

    /** [file] 是否落在本 App 管得着的两棵树里（`$DSH_HOME` 与 `engine/`）。 */
    private fun isWithinManagedRoots(file: File): Boolean {
        val path = file.absolutePath
        return path.startsWith(dshHome.absolutePath) || path.startsWith(engineDir.absolutePath)
    }

    /**
     * 决定引擎 Web GUI 的监听端口。
     *
     * 返回 `null` 表示**不传 `--port`**，让引擎落到它自己的默认值
     * （`@deepseek-ai/dsh-web-app/cordis.patch.yml` 的 `port ?? 3080`）。
     * 返回 `0` 表示让内核挑一个空闲端口。
     *
     * ## 为什么需要它
     *
     * `dsh-host-webserver` 的 `listen()` 失败会**直接 reject 插件激活**
     * （`lib/index.js` 的 `server.once("error", reject)`），而它是 required 插件，
     * 于是整个引擎以
     * `dsh: startup failed: N required plugins did not activate` 退出，
     * 界面上就是「引擎启动失败（引擎进程已退出）」+ 一串
     * `waiting for webServer / webRuntime / connection` 的连锁行。
     *
     * 真机反馈的场景：App 上一次的 node 进程没跟着进程一起死（`ProcessBuilder`
     * 起的子进程不会随父进程退出），仍占着 3080，之后每次冷启动都撞 EADDRINUSE ——
     * 用户只能重启手机。
     *
     * ## 为什么「探测空闲就照旧传默认值」而不是一律 `--port 0`
     *
     * 一律 `--port 0` 会让每次启动的端口都变，而移动端 UI 插件与用户的收藏/
     * 书签都可能记着 `127.0.0.1:3080`。只有真被占时才让出端口，正常路径的
     * 行为与改动前**逐字一致**。
     *
     * ⚠️ 探测与真正 `listen` 之间天然存在竞态（探测完端口被别人抢走）。
     * 这个窗口只有毫秒级，且失败后果与改动前完全相同（引擎报错退出），
     * 所以不为此引入重试 —— 真正的占用源是上一个残留进程，它不是并发抢端口。
     */
    private fun resolveWebPort(): Int? {
        if (!isLoopbackPortBusy(DEFAULT_WEB_PORT)) {
            Log.i(TAG, "Web 端口 $DEFAULT_WEB_PORT 空闲，沿用引擎默认值")
            return null
        }
        Log.w(
            TAG,
            "Web 端口 $DEFAULT_WEB_PORT 已被占用（多半是上一次残留的引擎进程），" +
                "改由内核分配空闲端口",
        )
        return 0
    }

    /**
     * `127.0.0.1` 上的 [port] 是否已被占用。
     *
     * ⚠️ 判据与 `AdbMdns.isPortInUse` 相反：那里「bind 失败 = adbd 在听」是**成功**，
     * 这里「bind 失败」就是**真被占用**。两处语义不同，别互相抄。
     *
     * 只探测回环地址，与引擎的 `host: '127.0.0.1'` 对齐 —— 别的进程若绑在
     * `0.0.0.0:3080`，对 `127.0.0.1:3080` 的 bind 同样会失败，判据成立。
     */
    private fun isLoopbackPortBusy(port: Int): Boolean = try {
        ServerSocket().use {
            // 不设 SO_REUSEADDR：Android 上它只影响 TIME_WAIT 复用，不影响
            // 「有人在 listen」的判定，默认行为更贴近引擎自己的 bind。
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
        }
        false
    } catch (_: IOException) {
        true
    }

    /**
     * 清掉上一次遗留的引擎锁文件（`$DSH_HOME/.dsh` **顶层**的 `*.lock`）。
     *
     * ## 为什么需要（2026-11-01 真机反馈）
     *
     * 用户截图上两行提示一起出现：
     *
     *     dsh: startup failed: 1 required plugin did not activate
     *     Failed plugins (1): connection (required)
     *     Error: atomic-write: timed out waiting for the writer lock at
     *            …/home/.dsh/.credentials.yaml.lock
     *
     * `connection` 插件激活期要写凭据，写凭据走
     * `@deepseek-ai/dsh-atomic-write` 的 `withFileLock()`：锁是 `<文件名>.lock`，
     * 内容写的是**持有者 PID**，抢不到就指数退避到超时（默认 2 秒，调用方可放宽）。
     * 超时直接抛错 ⇒ required 插件不激活 ⇒ 引擎在打印 URL 之前退出。
     *
     * 那个库的自愈边界很明确：只有「锁里记录的 PID 已经不存在（ESRCH）」才会
     * 删锁重试；**PID 被别的进程复用**时它照等不误（它的文档原话是
     * "A holder whose PID a live process reused keeps its lock until an operator
     * removes it"）。Android 上 PID 复用很常见，所以这把锁一旦残留就永远过不去 ——
     * 连随后的安全模式重试也撞同一把锁，用户看到的就是「安全模式也起不来」。
     *
     * App 比引擎多知道一件事：**这个 `$DSH_HOME` 只属于本 App，而且此刻引擎
     * 进程确实没在跑**（`start()` 开头的「引擎已在运行」早返回已经把那种情况排除）。
     * 所以顶层的 `.lock` 必然是残骸，删掉即可 —— 不去按 PID 判活，
     * 正好补上「PID 被复用」这个缝隙。
     *
     * ⚠️ 只删 [dshHome] **顶层**：凭据锁与 `all-stores.lock` 都在这一层；
     *   更深处的 `sessions/`、`profiles/` 里不会有锁文件，别递归去碰用户数据。
     */
    private fun clearStaleEngineLocks() {
        val locks = dshHome.listFiles { f -> f.isFile && f.name.endsWith(".lock") } ?: return
        if (locks.isEmpty()) return
        for (lock in locks) {
            val owner = runCatching { lock.readText().trim() }.getOrDefault("")
            if (lock.delete()) {
                Log.w(TAG, "已清理残留的引擎锁: ${lock.name}（记录持有者 pid=${owner.ifEmpty { "?" }}）")
            } else {
                Log.w(TAG, "残留的引擎锁删不掉: ${lock.name}（将交给引擎自己的等待逻辑）")
            }
        }
    }

    /** 启动引擎，等待其打印带 token 的 URL。 */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) {
            Log.i(TAG, "引擎已在运行")
            // ⚠️ **早返回也必须跑特权通道自愈。** 这一段原先直接 return，于是
            //   「引擎还活着」时整块自愈（常驻监听 + 启动自愈 + 固定端口守护）
            //   一次都不跑 —— 而它恰恰是**用户重开 App 时唯一会走到的那条路**：
            //   引擎进程被前台服务（`START_STICKY`）保着，`MainActivity.onCreate`
            //   每次都会调 `start()`，每次都命中这个早返回。
            //   症状就是用户报的那两条：「清后台重开，权限还是没回来」。
            resumePrivilegedChannel()
            return@withContext
        }

        _state.value = State.Starting
        logLines.clear()
        // 启动计时锚点。后面「引擎就绪」事件用它算出总耗时 ——
        // 「打开 App 慢」这类反馈只有靠这个数才能定位到具体阶段。
        val startMs = System.currentTimeMillis()
        EventLog.record("engine.start")
        // ⚠️ 每次启动都清掉「主动停止」标记，否则 stop() 之后再也起不来
        //   （EOF 会被当成用户停止而不再上报死亡，这里反过来：新一轮启动了，
        //   旧标记已经没有意义）。
        userStopped = false

        homeDir.mkdirs()
        dshHome.mkdirs()
        workspaceDir.mkdirs()

        // ⚠️ Termux 环境与启动前体检**必须排在最前面**，早于下面所有往 engine/ 写东西
        // 的 ensureXxx。理由是体检在发现核心 bundle 被改坏时会触发**整棵 engine/
        // 重解包**，而那会把已经打好的补丁与已解出的原生插件全部抹掉 —— 那些
        // ensureXxx 都是幂等的，重解包之后再跑一遍才是正确顺序。
        // 真机实测：正常态体检耗时 0.7~0.8 秒，对 7~10 秒的启动时间可忽略。
        /*
         * ⚠️ **首次启动时 bootstrap 解压与引擎启动是并发的**（前者在
         *   `DshApplication.bootstrapTermux()` 的 `applicationScope.launch` 里），
         *   原来这里不等它。
         *
         * 后果在真机上很确定（反馈 `20261106-215120-1a090a`，HUAWEI PSN-AL00 /
         * Android 16 / 首次安装）：
         *
         * ```
         * 21:48:07  [TERMUX] install start arch=aarch64
         * 21:48:17  启动引擎: …/termux/usr/bin/node …
         * 21:48:17  CANNOT LINK EXECUTABLE "…/usr/bin/node":
         *           library "libz.so.1" not found: needed by main executable
         * ```
         *
         * 原因是 node 的 NEEDED 要 `libz.so.1`，而 APK 里只有
         * `lib/libz.so.1.3.2`，那条 `libz.so.1` 软链由解压流程最后统一建：
         * 解压没跑完时 `targetPath.exists()` 为 false，软链**被跳过**，
         * 而 `installNodeRuntime()` 仍然返回 true（它校验 node 时用的是自带
         * LD_LIBRARY_PATH），于是引擎带着一个链接不上的 node 就这么起了。
         *
         * `install()` 本身幂等、且持有 prefix 文件锁 —— 这里等它跑完即可，
         * 不会重复解压。
         */
        if (!bootstrap.isReady) {
            Log.w(TAG, "Termux 尚未就绪（首次安装或上次未完成），先等引导跑完再启动引擎")
            EventLog.record("engine.wait-bootstrap")
            bootstrap.install()
        }

        if (!bootstrap.installNodeRuntime()) {
            _state.value = State.Failed(
                "Termux 环境未就绪，无法启动引擎。\n" +
                    "prefix=${bootstrap.prefixDir.absolutePath}\n" +
                    "node=${bootstrap.nodeBinary.absolutePath}"
            )
            return@withContext
        }

        val nodeBin = bootstrap.nodeBinary
        if (!nodeBin.isFile) {
            _state.value = State.Failed("找不到 Node 可执行文件: ${nodeBin.absolutePath}")
            return@withContext
        }

        // ⚠️ 救援 profile 必须在**体检之前**建好，且必须建在体检**之外**。
        //
        //   体检（[preflight]）只处理 `web` profile，它的结论不该影响救援 profile
        //   的可用性 —— 恰恰相反：**web 坏掉时救援必须还能起来**，那正是它存在的理由。
        //   所以这里独立建、独立判失败（见 BuiltinPluginBundles.initializeProfile()）。
        //
        //   建不出来 ⇒ 救援这条路不可用。这时**不能**假装没事继续（那会让
        //   L2 的失败变成"救援模式也起不来"这种更糟的表现），而是记下来，
        //   让降级决策直接跳到 L3（安全模式）。见 [rescueAvailable]。
        runtimeDependencies.ensureNativeAddon()
        runtimeDependencies.ensureSystemAddon(::patchFlockPlatform)
        val builtinBundles = BuiltinPluginBundles(context, engineDir, dshHome, nodeBin, bootstrap.prefixDir)
        val rescueProfileDir = builtinBundles.initializeProfile(RESCUE_PROFILE_NAME)
        // web 初始化失败留给既有 preflight 修复；不得阻断独立救援 profile。
        builtinBundles.initializeProfile(PROFILE_NAME)
        rescueAvailable = rescueProfileDir != null
        if (!rescueAvailable) {
            Log.e(TAG, "救援 profile 不可用 —— 降级阶梯将跳过 L2 直接到 L3")
            EventLog.record("rescue.profile.unavailable")
        }

        // 本次用哪个 profile 启动，由**上一次**留下的救援层级决定。
        //
        //   判据只看 RESCUE 一态。SAFE_MODE（L3）与 ISOLATED（L1）**都仍然用
        //   `web` profile** 启动 —— 两者的语义都是「用用户自己的配置，只是少加载
        //   几个 bundle」，与救援 profile（完全不用用户配置）是两套不同的机制
        //   （见 `docs/RESCUE-BOOT-PLAN.md` §4.1 的表）。把它俩也切到 rescue 会
        //   让它们失去原本的作用，那是行为变更，不是本方案要的。
        //
        //   `tier()` 读的是 `$DSH_HOME/rescue/state.json`，由降级决策写入。
        //   ⚠️ 它**不会**因为"这次起来了"而自动复位 —— 复位只在用户点
        //   「退出救援模式」时发生（I4：降级状态必须常驻可见 + 可一键退出）。
        val targetProfile = if (RescueBoot.tier().tier == RescueBoot.Tier.RESCUE) {
            RESCUE_PROFILE_NAME
        } else {
            PROFILE_NAME
        }
        if (targetProfile != PROFILE_NAME) {
            Log.w(TAG, "以救援 profile 启动（--profile $targetProfile）：不加载任何第三方插件")
            EventLog.record("engine.start.rescue", mapOf("profile" to targetProfile))
        }

        // 启动前体检：profile 与 patch 层都是模型可写的，改坏会让引擎直接 exit 1，
        // 而 App 没有任何自救路径（用户只能卸载重装，那会清掉全部会话与凭据）。
        //
        // ⚠️ 结论**只是静态的**：它跑 `--dump-config`，不执行插件 `apply()`。
        //   真机上杀死引擎的异常正是在 `apply()` 期抛的，所以体检放行 ≠ 能起来。
        //   三态如实写进日志与事件时间线（原来 `false` 把两件事混成了一件）。
        //
        // ⚠️ **救援态下跳过体检**（I1：救援启动必须比正常启动简单，不能成为
        //   第二个失败源）。体检会跑 `--dump-config`、可能触发整棵 engine/
        //   重解包（15 秒 + 抹掉已打好的补丁）—— 那些动作在救援场景下全是风险，
        //   而救援 profile 本来就只有核心 bundle，没什么可体检的。
        val pre = if (targetProfile == PROFILE_NAME) {
            preflight()
        } else {
            Log.i(TAG, "救援启动：跳过启动前体检（I1：救援路径不引入新的失败点）")
            PreflightResult.StaticOnly
        }
        Log.i(TAG, "启动前体检结论：${pre.label}")
        EventLog.record("preflight.result", mapOf("result" to pre.label))
        if (pre == PreflightResult.Repaired) {
            Log.i(TAG, "启动前体检已修复损坏的插件配置，继续启动")
        }

        if (targetProfile == PROFILE_NAME) {
            // preflight 可能重解包 engine，先恢复模板导入依赖。
            runtimeDependencies.ensureNativeAddon()
            runtimeDependencies.ensureSystemAddon(::patchFlockPlatform)
            if (builtinBundles.initializeProfile(PROFILE_NAME) == null || !builtinBundles.ensure()) {
                _state.value = State.Failed("内置插件登记失败，详见引擎日志")
                return@withContext
            }
            try {
                BuiltinPluginUpdatePatch.apply(engineDir, dshHome, BUILTIN_PLUGIN_BUNDLES.associate { it.id to it.packageName }) { Log.i(TAG, it) }
            } catch (error: IllegalStateException) {
                Log.e(TAG, "内置插件市场更新适配失败: ${error.message}")
                _state.value = State.Failed("内置插件市场更新适配失败: ${error.message}")
                return@withContext
            }
        }
        // 前端补丁要在 WebView 首次加载前落盘，所以放在启动引擎这一步里
        compatibilityInstaller.ensureWebCompat()
        // HTTP header 净化垫片：配置里混进中文时，undici 会抛一句只带字符下标的
        // 英文错误、消息直接发不出去。必须在引擎进程启动前落盘，否则
        // `NODE_OPTIONS=--import` 拿不到文件（见 ensureFetchGuard）。
        compatibilityInstaller.ensureFetchGuard()
        // 原生插件是引擎启动的前提（DSH 的 profile 解析依赖它），必须早于进程启动
        runtimeDependencies.ensureNativeAddon()
        // system 插件（会话写入锁）：缺了它每轮回复都会以「本轮运行失败」告终
        runtimeDependencies.ensureSystemAddon(::patchFlockPlatform)
        // ripgrep：官方无 android 平台包，缺了 grep/glob 两个工具全废
        runtimeDependencies.ensureRipgrep()
        // sharp：官方无 android 预编译二进制，缺了图片附件发送会以 agent-busy 失败
        runtimeDependencies.ensureSharp()
        // node-pty：官方 prebuilds 无 android，缺了侧栏终端打不开
        runtimeDependencies.ensureNodePty()
        // 终端后端的进程检查器只认 linux/darwin/win32，android 要显式放行
        patchProcessInspector()
        // terminal-bash 硬编码 /bin/bash，而 Android 上只有 /bin/sh（没有 bash）→
        // bash 工具每条命令都报「PTY shell exited during startup」，改用 $PREFIX/bin/bash
        patchTerminalShell()
        // 附件模块的目录 fsync 会走到 /data/user/0（SELinux 拒绝 open）→ 附件发不出去
        patchAttachmentDurability()
        // ⚠️ 用户自己做的插件在运行期抛出未捕获异常时，installFailLoud 会 proc.exit(1)
        //   把整个引擎杀掉 → 反复重启 → App 再也打不开（三级降级阶梯对此无效，
        //   因为这条路径下 RescueBoot.recordFailure 一次都不会被调用）。见其 KDoc。
        patchFailLoudExit()
        // 凭据文件权限过宽（0600 之外）会让 credentials 插件硬抛错 → 9 个插件级联 pending
        // → 整个引擎退出。引擎自己是纯校验不自愈，所以由 App 在启动前收窄。
        filePolicy.ensureCredentialFileMode()
        // DeepSeek Messages 要求 assistant 的 tool_use 块连续且紧后跟 tool_result，
        // 而持久化历史里 text 会夹在多个 tool_use 之间 → 该会话从此永久发不出去
        patchLlmDeepseekToolOrder()
        // pi-ai：第三方路由不给 tool_call id 时，原样透出空 id → 落盘后会话永久不可读。
        // 两处都要改（delta 与 end 各一条路径），见各自的 KDoc。
        patchLlmPiAiToolCallId()
        patchLlmToolCallIdFallback()
        // 目录选择器：撤回「起点改 /sdcard」的旧补丁，改由 HOME 下的入口链接提供
        restoreDirectoryPickerStart()
        // 手机存储与 app 私有目录在 Android 上互不可达，靠 HOME 下两个链接做入口
        filePolicy.ensurePickerRoots()
        // 插件已在统一登记阶段就位；此处只处理引擎自己的兼容产物。
        compatibilityInstaller.ensureWebViewCompat()
        // 兼容产物会覆盖会话 UI 源码，发图格式补丁必须在覆盖后应用。
        patchImageMediaType()
        // 出厂默认设置：只写 profile 的用户 patch 层，且只在它不存在时写一次。
        // 必须早于引擎启动 —— 引擎的 initProfile() 会给缺失的 patch 文件补一份空模板。
        profileDefaults.seedDefaultSettings()
        // ── 已移除：多模态配置 + Agnes 内置号池（用户要求整块删掉）──────────
        // 不再解包 agnes-multimodal 插件（`generate_image` / `generate_video` 与
        // 「多模态配置」设置页一并消失），不再播种账号池，也不再往 profile 注入
        // 内置 `agnes` 供应商。引擎侧回落到官方默认模型（deepseek-official）。
        // 老设备 profile 里已有的 `agnes` 供应商与旧默认模型由 profileDefaults.stripBuiltinAgnesProfile() 清掉。
        // 内置**中文 skill**：落到 $DSH_HOME/skills（全局发现根，每个预设都扫得到），
        // 让 AI 做主题/桌面宠物时不必重新摸一遍 DSH 结构。
        toolInstaller.ensurePresetSkills()
        // 创造模式（cordis 预设）的 persona：在官方原句之上并入「快准狠」铁律。
        // ⚠️ 必须在 ensureExtracted() 之后（改的是 engine/ 里的文件），
        //    且每次启动都要重打 —— 引擎重解包会把补丁抹掉，见该函数的 KDoc。
        patchPresetCordisPersona()
        // ⚠️ 这里**不再**写 `agent-preset-registry` 的 `default: razor`。
        //
        // 改造前为了让新会话默认用 razor，App 会把出厂默认位补进 profile 的用户层。
        // 但 razor 现在是**可被用户关掉的 bundle** —— 一旦关掉，registry 的
        // `resolve()` 就查不到 `razor` 这条定义，直接抛
        // `agent-preset/not-found: Unknown agent preset: razor`，新建会话全部失败。
        //
        // 所以「默认用它」改为靠「默认开启」实现（bundle 在名单里 ⇒ 预设就在选择器里，
        // 且 registry 的出厂默认位本来就会选中它）。用户关掉之后，由用户在预设页自己
        // 选一个默认 —— 那是用户意图，App 不替他决定，也不留一个悬空的 default。

        // 沙箱：Android 无内核级后端，用自带 runner 保住 workspace-write 的写边界
        val sandboxPatch = profileOverlays.writeSandboxPatch(runtimeDependencies.ensureSandbox())
        // 默认工作区：Android 落不进引擎的平台分支，必须显式给 documentsDirectory
        val workspacePatch = profileOverlays.writeWorkspacePatch()
        // 插件安装源：官方 fallback 只有镜像源，与本机 ~/.npmrc 撞成同一项被去重掉
        val pluginManagerPatch = profileOverlays.writePluginManagerPatch()
        // link 兼容层：SELinux 不给 app_data_file 的 link 权限，需在 libc 边界转译
        val linkCompat = compatibilityInstaller.ensureLinkCompat()
        // 内置号池：已改为**服务端网关**，本地不再解出注入库。
        // 这里调用是为了清掉老安装残留的 libkeyinject.so，恒返回 null（结果不使用）。
        compatibilityInstaller.ensureKeyInject()
        // ⚠️ **不能**走 `--patch` overlay：overlay 在最顶层整块替换 `llm-pi-ai`
        // 那一行的 config，会把用户在 GUI 里新建的 provider 一起盖掉，于是
        // `dsh-config-editor` 的写入校验直接抛
        // `Configuration for "llm-pi-ai" is overridden by a home patch or
        // command-line overlay` —— 用户表现为「一添加第三方 API 就报错」。
        // 所以改为把内置号池**补进 profile 的用户配置层**（见 [ensureKeyPoolProvider]）。
        // 内置号池 / 内置供应商已整块移除，这里只做**清理**：
        // 把老设备 profile 里残留的 `agnes` 供应商条目与指向它的默认模型删掉，
        // 否则它们会指向一个不再存在的凭据，首次对话必然路由失败。
        profileDefaults.stripBuiltinAgnesProfile()
        profileDefaults.dropStaleBuiltinCredential()
        // 新任务默认预设 = razor（Fast 模式）。只给已有 profile 补缺，
        // 新装设备由 DEFAULT_SETTINGS_PATCH 直接播种。
        profileDefaults.ensureDefaultPreset()

        // ⚠️ **救援 profile 也必须拿到同一套用户层配置**，否则救援界面是残废的：
        //   没有默认模型 ⇒ AI 一开口就路由失败；没有号池 ⇒ 用户得自己配 key；
        //   没有出厂设置 ⇒ 主题/权限预设全空。而「救援模式里 AI 能帮我修」
        //   正是整个方案的核心承诺（G3），AI 不能用就全盘失效。
        //
        //   这四处写入都是**幂等 + 只补缺**的（各自 KDoc 有论证），所以对 rescue
        //   重复调用是安全的：救援 profile 刚被建出来时它们会播种，之后不再动。
        //
        //   ⚠️ 只对**已建出来**的救援 profile 播种（`rescueProfileDir != null`）：
        //     目录不存在时这几个函数会 `mkdirs()` 把它造出来，而造出来的目录
        //     只有 `cordis.patch.yml`、**没有 `package.json`** —— 那样的 profile
        //     引擎读不了（`profile "rescue" does not exist`），比不建更糟。
        if (rescueProfileDir != null) {
            profileDefaults.seedDefaultSettings(RESCUE_PROFILE_NAME)
            profileDefaults.stripBuiltinAgnesProfile(RESCUE_PROFILE_NAME)
            profileDefaults.ensureDefaultPreset(RESCUE_PROFILE_NAME)
        }
        // uid 2000 通道：截图/点击/卸载/settings 等 app 域做不到的操作要靠它。
        // 未导入密钥时脚本自己回落为本地执行，不影响任何现有功能。
        val privRunner = toolInstaller.ensurePrivilegedShell()
        // ⚠️ **Shizuku 桥必须在引擎启动前起来**，而且**无论 Shizuku 当前可不可用
        //    都要起**：Shizuku 服务可能是用户稍后才启动的（重启手机后要手动开一次），
        //    而 socket 名一旦绑定就固定了 —— 起桥时检查可用性会让「先开 App
        //    再开 Shizuku」永远连不上。见 [ShizukuChannel.start]。
        //
        // 这是 uid 2000 的第二条来源，且**不依赖 adbd**：对 Android 10 及以下
        // （没有无线调试，且 persist.adb.tcp.port 写不进去 ⇒ 每次重启都要 PC
        // 再跑一次 `adb tcpip 5555`）的用户，这往往是唯一可行的路。
        if (ShizukuChannel.start(context)) {
            Log.i(TAG, "Shizuku 桥已就绪: 管理器=${ShizukuChannel.isManagerInstalled(context)} " +
                "binder=${ShizukuChannel.isBinderAlive()} 已授权=${ShizukuChannel.hasPermission()}")
        }
        // 无线调试（Android 11+）用的 adb 工具链：让 uid 2000 通道在
        // 「只开了无线调试、没做 adb tcpip」的设备上也能用。见 toolInstaller.ensureAdbWireless()。
        val adbWireless = toolInstaller.ensureAdbWireless()
        // 把该通道注册成独立的 `shell` 工具（**不是**改 bash 的行为）：
        shellToolMounted = targetProfile == PROFILE_NAME && privRunner != null && isBundleEnabled(SHELL_TOOL_PACKAGE)
        // 定向隔离层（L1.5）：把上次定位到的坏插件条目在 patch 层禁掉。
        // ⚠️ 读的是 `RescueBoot` 的持久化状态，**不依赖**本轮启动的任何中间结果，
        //   所以放在这里（早于引擎启动、晚于所有 `- insert:` overlay 的生成）就够。
        val quarantinePatch = profileOverlays.writeQuarantinePatch(RescueBoot.tier().quarantinedNames)
        // 悬浮球命令信箱：`bubble` 工具把命令写进 `$DSH_HOME/bubble-cmd.json`，
        // 这里轮询读走交给原生。**必须早于引擎启动** —— 否则模型第一次调工具时
        // 信箱还没人守，命令会一直等不到回执。
        commandMailboxes.start(dshHome)
        // 日志命令信箱：`logs` 工具把请求写进 `$DSH_HOME/logs-cmd.json`，
        // 这里轮询读走交给原生。**必须早于引擎启动** —— 否则模型第一次调工具时
        // 信箱还没人守，请求会一直等不到回执。

        // 系统栏配色命令信箱：`statusbar` 工具把命令写进 `$DSH_HOME/statusbar-cmd.json`，
        // 这里轮询读走交给原生。**必须早于引擎启动** —— 否则模型第一次调工具时
        // 信箱还没人守，命令会一直等不到回执。

        // 救援命令信箱：AI 通过插件把 `rescue-cmd.json` 写进 `$DSH_HOME`，这里轮询读走。
        // 三个动作：`status`（当前层级、摘了哪些插件、上次失败详情）、
        // `probe`（拿改动后的配置**真跑一次**引擎验证，配额见 PROBE_QUOTA）、
        // `switch-rescue` / `switch-back`（进出救援态）。
        //
        // ⚠️ **必须早于引擎启动**，理由与上面三条相同；而且对这条尤其要紧 ——
        //   救援场景下引擎可能压根起不来，那时用户唯一的自救手段就是让 AI 走这条路，
        //   所以信箱必须在**引擎启动之前**就已经有人守。

        // 让模型知道「有特权身份可选、什么时候该用、用了会失去什么」。
        // 判据同时包含 profile bundle 开关与 runner 是否就位。
        profileOverlays.ensurePrivilegedPrompt(shellToolMounted)

        // root（uid 0）通道：**先端到端探测**再决定挂不挂。
        // ⚠️ 顺序是硬性的 —— 探测要起 su 进程（可能弹授权框），必须早于引擎进程
        //   启动，否则用户会在界面已经可用之后才看到那个框。见 [RootAccess]。
        // ⚠️ 探测结果有 24h 缓存，所以稳态下这里只是一次文件读。
        val rootRunner = toolInstaller.ensureRootShell()
        val rootState = RootAccess.detect(context)
        rootToolMounted = targetProfile == PROFILE_NAME && rootRunner != null && rootState.granted && isBundleEnabled(ROOT_TOOL_PACKAGE)
        if (rootToolMounted) {
            Log.i(TAG, "root 可用（${rootState.detail}），root bundle 已启用")
        } else {
            Log.i(TAG, "root 不可用（${rootState.state}: ${rootState.detail}），root 工具未挂载")
        }
        // 让模型知道「这台设备有没有 root」，否则它会去猜、去反复试
        profileOverlays.ensureRootPrompt(rootToolMounted)

        // pnpm 要落到 $PREFIX/bin，必须等 Termux 环境就位之后再装
        runtimeDependencies.ensurePnpm()

        val binJs = File(engineDir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!binJs.isFile) {
            _state.value = State.Failed("找不到引擎入口: ${binJs.absolutePath}")
            return@withContext
        }

        // ⚠️ overlay 的顺序是**语义**的一部分（`--patch` 可重复、按顺序应用，
        //   后应用的覆盖先应用的）。这里的顺序与改造前逐项一致，别顺手调。
        val overlays = listOfNotNull(
            sandboxPatch,
            workspacePatch,
            pluginManagerPatch,
            // ⚠️ **必须排在最后**：这一层只做 `disabled: true`，而它要禁用的条目
            //   正是前面那些 `- insert:` 插进来的。`--patch` 按顺序应用，排在
            //   插入之前会让 patch 匹配不到目标 id（官方行为：warn 后跳过，静默失效）。
            quarantinePatch,
        )
        // ⚠️ 内置号池**刻意不在这个列表里**：它并入 profile 用户配置层，
        // 不走 overlay —— 理由见 [ensureKeyPoolProvider]。
        //
        // ⚠️ 存下来给试启动用（见 [activeOverlays]）：试启动必须与真实启动
        //   用**同一份** overlay，否则「探测能过、真实过不了」无法排查。
        activeOverlays = overlays
        val port = resolveWebPort()
        val cmd = EngineLaunchSpec.buildEngineArgs(nodeBin, binJs, targetProfile, overlays, port)

        Log.i(TAG, "启动引擎: ${cmd.joinToString(" ")}")

        // ⚠️ 必须在 spawn **之前**：上一次残留的锁会让 `connection` 插件卡到
        //   锁超时，引擎在打印 URL 之前就退出（见 [clearStaleEngineLocks]）。
        clearStaleEngineLocks()

        val prefix = bootstrap.prefixDir.absolutePath

        val pb = ProcessBuilder(cmd)
            .directory(workspaceDir)
            .redirectErrorStream(true)

        pb.environment().apply {
            // ⚠️ 刻意**不设** LD_LIBRARY_PATH。
            //
            // 这里原先设了 `LD_LIBRARY_PATH=$prefix/lib`，当作「RUNPATH 没覆盖到时
            // 的兜底」。但它对 Termux 二进制**完全无用**（真机全量对比 $PREFIX/bin 下
            // 305 个 ELF，带与不带 LD_LIBRARY_PATH 的输出**零差异** —— Termux 的
            // RUNPATH 已被等长重写成 /data/data/com.dsh.android//usr/lib，自己找得到），
            // 却会**污染系统二进制**：它们本来该去 /system/lib64 找依赖，被这行带去
            // Termux 的 lib 目录，于是 screencap / input / app_process 全部
            // `CANNOT LINK EXECUTABLE "screencap": cannot locate symbol
            //  "CrcGenerateTable" referenced by "/system/lib64/libunwindstack.so"`。
            //
            // 后果不是「少了个功能」而是**误导**：screencap 在 app 域本来就因权限失败，
            // 现在失败得更早、错误信息指向动态库，模型会被带偏去反复试
            // `env -u LD_PRELOAD` 之类的无用方向（真机会话日志里实测发生过）。
            //
            // 去掉后 node 与 4 个原生模块（require-builtin / flock / node-pty /
            // sharp-wasm32）、bash/dpkg/pnpm/rg 全部实测正常。
            // PREFIX/HOME 让 DSH 内部按 Termux 习惯推导路径，与库搜索无关，保留。
            put("PREFIX", prefix)
            put("DSH_HOME", dshHome.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            // $PREFIX/bin 必须排在最前：node 自己 spawn 子进程（如 shell 工具）时
            // 要能找到 Termux 的 sh/bash 而不是系统的。
            put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
            // CA 证书在 Termux 私有 prefix 里，否则 HTTPS 请求会因证书链失败
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
            // 堆上限 + 预加载 HTTP header 净化垫片（见 ensureFetchGuard）
            put("NODE_OPTIONS", nodeOptions())
            // 镜像环境变量必须注入**引擎进程**，不能只靠 shell 工具那条路。
            //
            // ⚠️ 实测漏掉它的后果（2026-10-10，同一台设备）：
            //   `pkg install` 会调 `select_mirror()` 从 60+ 个上游镜像里**加权随机**
            //   重挑一个并覆写 `sources.list`，把 App 刚写好的国内快源冲掉。重置成
            //   USTC 后跑一次 `pkg install zlib`，源立刻变成 grimler.se。
            //   而 `TERMUX_PKG_NO_MIRROR_SELECT=1`（上游自己的开关）实测能挡住这次覆写。
            //
            // ⚠️ [TermuxBootstrap.getShellEnv] 早就带了这组变量，但那条路只覆盖
            //   `runProbeScript` 与 shell 工具；引擎进程自己 spawn 的 bash（模型装依赖
            //   用的就是它）走的是本函数的 environment，于是**恰好缺了这一条**。
            //
            // 代价实测（同一台设备、同一个包）：USTC 源 71 秒 vs 慢源 21 分钟。
            try {
                putAll(com.dsh.android.termux.MirrorProbe.shellEnv(bootstrap.loadMirrors()))
            } catch (t: Throwable) {
                // 读状态失败不影响引擎启动 —— 配置文件那一层仍然生效。
                Log.w(TAG, "注入镜像环境变量失败: ${t.message}")
            }
            // link()/linkat() 语义转译：SELinux 不允许 untrusted_app 对 app_data_file
            // 做硬链接，而 DSH 上游用硬链接做独占原子发布。见 [ensureLinkCompat]。
            //
            // ⚠️ 号池注入库已下线（改为服务端网关），现在这里只剩 linkcompat 一个。
            //    仍用列表拼接而不是覆盖，是为了将来加库时不用改结构。
            //
            // ⚠️ 只加在本进程：LD_PRELOAD 会一并继承给 node spawn 的子进程（如 shell
            // 工具）。linkcompat 只 hook link/linkat 且先试原生，对系统二进制无害。
            val preloads = listOfNotNull(linkCompat).map { it.absolutePath }
            if (preloads.isNotEmpty()) {
                // 空格分隔，与 boo-sandbox-run.c 里追加 LD_PRELOAD 的写法一致
                put("LD_PRELOAD", preloads.joinToString(" "))
            }
            // 内置号池的凭据引用指向**网关令牌**。
            //
            // ⚠️ 与旧逻辑的关键差异：以前这里只在注入库解出成功时才设置，因为
            // 占位符要靠注入库才会被替换 —— 没有库就宁可不设。现在令牌**不需要
            // 任何本地组件**，所以必须无条件设置，否则引擎解析 `apiKeyEnv` 时
            // 拿不到值，会报 `MISSING_CREDENTIAL`，号池直接不可用。
            put(KEY_ENV, KEY_PLACEHOLDER)
            // 沙箱 runner 靠它定位 LD_PRELOAD 拦截器。runner 会把它追加到子进程
            // 的 LD_PRELOAD 里（保留这里已有的 link 兼容层，两个库都生效）。
            put("BOO_SANDBOX_LIB", File(context.filesDir, SANDBOX_LIB_FILE).absolutePath)
            // uid 2000 通道脚本的路径，供特权插件读取（见 [ensurePrivilegedPlugin]）。
            // 经环境变量传递而不是让 Kotlin 拼 JS 字面量：路径里可能有反斜杠与引号。
            privRunner?.let { put("DSH_PRIV_RUNNER", it.absolutePath) }
            // Shizuku 桥的 socket 名，供 runner 走通道 0。
            // ⚠️ 这里传的是**纯名字**（`<包名>.shizuku`），不是带 `\0` 前缀的路径：
            //    envp 是 C 字符串，NUL 就是结束符 —— 传带 NUL 的值会被**静默截断**，
            //    runner 拿到空串、通道 0 整条不生效且毫无报错。`\0` 由 runner 自己补
            //    （见 runViaShizuku）。理由详见 ShizukuChannel.socketPath 的注释。
            // ⚠️ 与 DSH_PRIV_RUNNER 同理：引擎的 scrubbedParentEnv() 会剥掉所有
            //    `DSH_*`，插件必须显式转交（见 dsh-android-shell/lib/index.js）。
            put("DSH_SHIZUKU_SOCKET", ShizukuChannel.socketName(context))
            // root 通道脚本的路径，供 root 插件读取（见 [ensureRootPlugin]）。
            // 同上：走环境变量而不是拼 JS 字面量。
            put("DSH_ROOT_AVAILABLE", if (rootToolMounted) "1" else "")
            rootRunner?.let { put("DSH_ROOT_RUNNER", it.absolutePath) }
            // 无线调试用的 adb 工具链目录，runner 靠它决定要不要启用无线通道。
            // 缺失时 runner 只走 legacy 5555，行为与改动前完全一致。
            adbWireless?.let { put("DSH_ADB_HOME", it.absolutePath) }
        }

        // ⚠️ 存下这一份给试启动用（见 [activeEnv]）：试启动必须与真实启动**逐项相同**，
        //   否则「探测能过、真实过不了」无法排查。**必须在 pb.start() 之前存**，
        //   否则第一次探测可能读到空表而被拒绝。
        val launchSpec = EngineLaunchSpec(nodeBin, binJs, targetProfile, activeOverlays,
            workspaceDir, pb.environment(), port)
        activeLaunchSpec = launchSpec
        activeEnv = launchSpec.environment

        // ── L1 自动隔离（P1）──────────────────────────────────────────────
        //
        // ⚠️ **位置是硬性的：必须在这里**，即 [activeOverlays] 与 [activeEnv] 都已
        //   就绪之后、真正 `pb.start()` 之前。
        //
        //   这两个基线原先是在 `start()` 靠后位置才赋值的，而触发闸门要求它们非空 ——
        //   也就是说，如果按"读起来更顺"的直觉把闸门放在选 profile 那里，闸门**每轮
        //   都会被自己拦下**，L1 永远不触发。这与本方案开头证伪的
        //   `consecutiveFailures` 是**同一个缺陷**：判据描述的时机与它存在的时机不重合。
        //   所以这里不改闸门（改成"没有基线也硬跑"会让探测结论不可信），
        //   而是把调用点挪到基线之后。
        //
        //   触发条件（**不是**「同一 bootId 内连续失败 ≥2 次」—— 那个条件永远不成立，
        //    见 [RescueBoot.Failure.consecutiveFailures] 里的实测证伪）：
        //      连续 ≥2 次真实启动失败，且失败原因**指向插件**，且当前是正常态。
        //
        //   ⚠️ 为什么在 `pb.start()` **之前**：隔离成功之后本次就要用正常 profile
        //     起来（用户的绝大多数插件保住了），而引擎是在 `bin.js` 启动时才读
        //     `package.json` 的 —— 所以在这里改清单，本次启动就生效。
        //
        //   ⚠️ 只在正常态跑：救援态走的是 rescue profile（压根没有第三方 bundle，
        //     没有可隔离的对象）；隔离态已经是上次隔离后的结果了，再跑一遍没有意义
        //     （幂等靠这个判断 + `.bak-l1`）。见 [maybeIsolateBadBundles]。
        //
        //   ⚠️ 隔离之后**不重跑 [preflight]**。L1 只动 `bundles` 里第三方的名字，
        //     而体检判的是核心 bundle 完不完整，判据没变、结论不会变。反过来，
        //     体检在发现核心损坏时会触发**整棵 engine/ 重解包**，那会把此刻已经
        //     就绪的 native addon 与补丁抹掉 —— 白跑一趟还引入新失败点。
        maybeIsolateBadBundles()

        // ⚠️ L1 可能刚刚把层级降级成 RESCUE（二分没收敛时，见 [maybeIsolateBadBundles]
        //   末尾）。但 `targetProfile` 与 `cmd` 是在这**之前**算好的，本次发车仍会用
        //   用户那个已经证明起不来的 web profile —— 白等 20 秒拿到同样的失败。
        //
        //   所以这里重新读一次层级：变了就**不发车**，改走既有的重启路径。
        //   复用 [requestRestart] 而不是自己再拼一遍启动流程，是因为它已经接好了
        //   `MainActivity` 那条 `State.Dead` 分支（清 `webViewPainted`、置
        //   `forceReloadOnReady`、重新 `start()`），多一条自建路径只会多一个失败点。
        //
        //   ⚠️ 条件**只能判 RESCUE**，不能写成 `tier != NORMAL`：ISOLATED 态意味着
        //     L1 **成功**摘掉了致命插件，而那时用用户的 web profile 启动恰恰是
        //     正确动作（绝大多数插件保住了）。写成 `!= NORMAL` 会把成功当成失败，
        //     把用户直接丢进救援模式、白丢全部插件。
        if (RescueBoot.tier().tier == RescueBoot.Tier.RESCUE &&
            targetProfile != RESCUE_PROFILE_NAME
        ) {
            Log.w(TAG, "L1 已把层级改为救援模式，本次不发车，改用救援 profile 重启")
            EventLog.record("engine.restart.for-rescue", mapOf("staleProfile" to targetProfile))
            requestRestart("L1 降级到救援模式")
            return@withContext
        }

        // 无线调试端口由 Kotlin 侧发现（Node 侧发不出 UDP 组播，见 WirelessDebugging）。
        // 常驻监听：端口会随用户重开无线调试/重启/切网络而变，只在启动时发现一次的话
        // 一变就永久失效。监听本身由系统 mdnsd 承担，开销可忽略。
        resumePrivilegedChannel()

        // ── P4：把「你现在处于降级态、可以这样修」写进 agent 指令 ──────────
        //
        // ⚠️ **必须在这里**，即 [maybeIsolateBadBundles] 之后。层级是在 L1 里才
        //   定下来的（它可能刚把 tier 改成 ISOLATED 或 RESCUE），早于它写提示词
        //   就会写进上一次的层级 —— 表现为「AI 被告知处于救援模式，实际不是」。
        //
        // ⚠️ 也**必须早于引擎启动**：`AGENTS.md` 是引擎启动时读的（agent-instructions
        //   插件），启动之后再写，这一轮就注入不进系统提示词了。
        profileOverlays.ensureRescuePrompt(RescueBoot.tier().tier)

        val proc = try {
            launchSpec.processBuilder().start()
        } catch (t: Throwable) {
            _state.value = State.Failed("引擎进程启动失败: ${t.message}")
            return@withContext
        }
        process = proc

        // 只用一个后台线程从头到尾持续消费 stdout。
        // 不能用 use{} 读完 URL 就关流：引擎之后仍在写日志，64KB 管道缓冲写满后
        // 会阻塞在 write 上导致引擎彻底卡死；重复打开同一 inputStream 也会失败。
        // ⚠️ 用共享常量 [URL_PATTERN] 而不是就地写一份：试启动（[probeBoot]）
                        //   也用同一条正则判断"起来了没有"，两处各写一份迟早漂开，
                        //   而症状会是「试启动说成功、真启动说失败」这种无法解释的差异。
                        val urlPattern = URL_PATTERN
        val readyLatch = java.util.concurrent.CountDownLatch(1)
        var readyUrl: String? = null
        // 运行中 gateway 掉线只处理一次（见下面输出循环里的判据）。
        var gatewayDownSeen = false

        Thread {
            try {
                proc.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        // ⚠️ 引擎输出走**独立的 `engine.log`**，不进 `app.log`。
                        //
                        // 理由是容量：引擎会逐 token 打输出，一天能刷出几十 MB，
                        // 而 `app.log` 单文件上限只有 1MB。混在一起的话，原生侧的
                        // 关键日志（启动、插件、补丁）会被引擎刷掉 —— 而排查时
                        // 恰恰是原生侧那几十行最有价值。
                        //
                        // `LogStore.engine` 是异步写的，所以这一行**不会拖慢读流**
                        // （读流一慢，引擎写 stdout 就会阻塞，那是老问题的来源）。
                        LogStore.engine(line)
                        // ⚠️ **logcat 照打，但必须绕开 `DshLog` 的 sink**（2026-10-14
                        //   自检发现，同族第 23 例）。
                        //
                        // 原来这里是 `Log.i(TAG, "[engine] $line")`。本文件顶部是
                        // `import com.dsh.android.log.DshLog as Log`，而 `DshLog.i`
                        // 除了转发给 `android.util.Log` 之外，还会**再落一次盘**
                        // （见 `DshLog.emit`）—— 而上面那行 `LogStore.engine(line)`
                        // 已经落过了。于是同一条引擎输出在 `app.log` 与内存缓冲里
                        // 各存两份（tag 分别是 `DSH` 与 `engine`）。
                        //
                        // 真机证据（服务器上 74 个真实用户包）：**72 个**的 `app.log`
                        // 里混着引擎输出，占 11%~25% 的行；168 个崩溃现场文件里
                        // 「tag=[DSH] 且正文以 [engine] 开头」的行数与「tag=[engine]」
                        // 的行数**比值恒为 1.00** —— 一条不多一条不少，正是重复。
                        //
                        // 后果正是上面那段注释要避免的那个：`app.log` 单文件只有 1MB、
                        // `--- last 200 log lines ---` 只有 200 行预算，两者都被重复行
                        // 占掉一半，而 `engine-start-failed` 的根因（引擎自己打印的
                        // `dsh: startup failed: … plugin did not activate`）就挤在这半边里。
                        //
                        // ⚠️ **不能用 `Log.i`**（那会走回 sink），要用全限定名
                        // `android.util.Log.i` —— 这违反 `DshLog` 类注释里「调用点不出现
                        // 全限定写法」那条，但这里正是**必须**绕开的场合：落盘已经由
                        // `LogStore.engine` 负责，此处只要 logcat。两处都不丢。
                        android.util.Log.i(TAG, "[engine] $line")
                        synchronized(logLines) {
                            logLines.addLast(line)
                            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
                        }
                        /*
                         * **运行中**的 gateway 掉线也要认（反馈 `20261106-125423-f50bba`）。
                         *
                         * 原来的判据只在 `start()` 成功分支里跑一次，看的是**启动那段**
                         * stdout。而用户截图里那句
                         *   `typert gateway: session/create: Active Service
                         *    "sessionController" is unavailable`
                         * 是**运行中**点「新建会话」才冒出来的 —— 此时启动早已成功、
                         * 判据早已跑完，于是既不降级也没有横幅，用户只能对着红色提示
                         * 一直点：工作区打不开、新建也失败。
                         *
                         * ⚠️ `requestRestart` 必须丢到别的线程：这里正在读引擎的
                         *   stdout，重启会等引擎退出，在读取线程里等就是自己等自己。
                         */
                        if (!gatewayDownSeen && readyUrl != null && RescueBoot.looksLikeGatewayDown(line)) {
                            gatewayDownSeen = true
                            val hit = line.trim()
                            Log.e(TAG, "运行中出现 typert gateway 服务不可用：$hit")
                            EventLog.record(
                                "engine.gateway-down-runtime",
                                mapOf("line" to hit.take(200)),
                            )
                            if (RescueBoot.tier().tier == RescueBoot.Tier.NORMAL && rescueAvailable) {
                                escalateToRescue(
                                    "运行中 typert gateway 服务不可用：" + hit.take(120),
                                    from = "gateway",
                                )
                                Thread { requestRestart("运行中 typert gateway 服务不可用") }.start()
                            }
                        }
                        if (readyUrl == null) {
                            val m = urlPattern.find(line)
                            if (m != null) {
                                readyUrl = m.groupValues[1]
                                readyLatch.countDown()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "引擎输出流结束: ${t.message}")
            } finally {
                // 流关闭（引擎退出）时也要唤醒等待方，避免白等到超时
                readyLatch.countDown()
                // ⚠️ **引擎一死就必须把 `engine.log` 的缓冲刷出去**（2026-10-14 自检发现，
                //   同族第 24 例）。
                //
                // `engine.log` / `web.log` 是**异步档**：`LogStore.writeAsync` 攒着
                // 不 flush，只靠 `BufferedWriter` 的 8 KB 缓冲与「超 1 MB 才 flush」
                // 那条阈值（见那里的注释）。引擎**正常**跑时这没问题 —— 它迟早会
                // 攒够 8 KB。
                //
                // 但**引擎起不来时正好相反**：它只来得及打几十行（实测约 5 行、
                // 不足 2 KB）就退出，缓冲**永远到不了阈值** —— 而那几十行恰恰是
                // 唯一能解释「为什么起不来」的证据（实测是
                // `dsh: startup failed: 1 required plugin did not activate` +
                // `credentials-local: .credentials.yaml is readable beyond …`）。
                // 进程一退，缓冲随进程消失，`engine.log` 停在 0 字节。
                //
                // 真机证据（服务器上 73 个真实用户包）：含 `engine-start-failed`
                // 现场的包有 **33 个**，其中包内有 `engine.log` 的只有 **6 个** ——
                // 且那 6 个的时间戳是**上一次运行**留下的残卷（23:53 写的，
                // 包生成于 04:13），不是本次故障的输出。也就是说这个文件在最需要
                // 它的场合恰好是空的。
                //
                // 放在 `finally` 里是因为三条退出路径都要覆盖：引擎启动失败、
                // 运行期崩溃退出、以及正常结束。
                runCatching { LogStore.flushAsync() }
                // ⚠️ **运行期退出检测就在这一行**。
                //
                // stdout 读到 EOF ⇔ 引擎进程真的没了（管道写端全关）。这是唯一
                // 可靠的死亡信号：`process.isAlive` 要靠轮询，而轮询在 App 被
                // 冻结时同样停摆；EOF 是内核主动通知的，不需要我们醒着。
                //
                // 判据用「曾经就绪过」而不是「现在是 Ready」——后者会被
                // 紧随其后的 `_state.value = State.Failed(...)` 覆盖掉顺序
                // （见下面的 got/url 分支），用 readyUrl 更稳。
                //
                // 只读一次 volatile 快照，避免与 start() 的另一次调用赛跑：
                // 这次线程属于**这一个** proc，若 process 已被换成新的进程，
                // 说明有人重启过引擎，此时不该把状态打成 Dead。
                markEngineDeadIfCurrent(proc, readyUrl != null)
            }
        }.apply { isDaemon = true; name = "dsh-engine-output" }.start()

        val got = readyLatch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val url = readyUrl

        if (got && url != null) {
            // ── 引擎**起来了**，但 gateway 可能已经废了（本 App 的第三种故障形态）──
            //
            // ⚠️ **这一段的判据与处置都必须早于 `clearFailure()`**，见下面两条注释。
            //
            // 真机现场（OPPO PKJ110 / v18）：引擎打印了 URL、界面正常加载，但
            // `typert-loader` 的 contributor 注册失败 ⇒ gateway 的严格定义被撤下
            // ⇒ `session/control` / `session/modelCatalog` / `directoryPicker/list`
            // 全部报 `its strict definition was withdrawn and SRC fallback is forbidden`。
            // 用户「选不了工作区、发不出消息、插件面板打不开」，而**界面上没有任何
            // 按钮**——救援阶梯的每一道门都要求「启动失败」，这次启动是成功的。
            //
            // 时序是可靠的：真机 `engine.log` 里 typert 那段报错在 `dsh web:`
            // **之前 21ms**（13:29:30.709 vs .730），而 stdout 循环是先
            // `logLines.addLast(line)` 再 `readyLatch.countDown()`（见上面那段），
            // 所以等到这里时证据必然已经在缓冲里。
            val gatewayTail = synchronized(logLines) { logLines.toList() }
            val gatewayCulprit = RescueBoot.parseGatewayFailure(gatewayTail)
            if (gatewayCulprit != null) {
                Log.e(
                    TAG,
                    "引擎打印了 URL 但 typert gateway 整体下线（涉事=${gatewayCulprit.ifEmpty { "未解析到包名" }}）" +
                        " —— 界面会正常加载，但会话/模型列表/目录选择全部不可用",
                )
                EventLog.record(
                    "engine.gateway-degraded",
                    mapOf("culprit" to gatewayCulprit.ifEmpty { "unknown" }),
                )
                // ⚠️ **先记失败**，再决定降级。这份记录让「连续失败几次」「涉事是谁」
                //   在重启后仍然可查 —— `start()` 成功分支本来会 `clearFailure()`，
                //   而这次的「成功」是假的（见 [RescueBoot.recordGatewayFailure] 的说明）。
                RescueBoot.recordGatewayFailure(
                    bootId = BootState.bootId,
                    tailLines = gatewayTail,
                    culprit = gatewayCulprit,
                )
                // 正常态 ⇒ 降级到 L2 救援模式并**立刻重启**，用户零操作。
                // L2 是**非破坏性**的（不动用户配置、有常驻横幅、可一键退出），
                // 所以这条路径可以放心走（同 [escalateToRescue] 的 KDoc）。
                //
                // ⚠️ 只在 NORMAL 态推。已是 RESCUE 时再推没有意义，而且会把横幅的
                //   `since` 刷成每次启动的时刻（「自 XX 起」就永远显示刚刚）。
                // ⚠️ `rescueAvailable == false` 时**不能**推：那个 profile 建不出来，
                //   推过去下次启动会直接失败（见上面 `ensureRescueProfile` 那段）。
                val tierNow = RescueBoot.tier().tier
                if (tierNow == RescueBoot.Tier.NORMAL && rescueAvailable) {
                    escalateToRescue(
                        "引擎起来了但 typert gateway 整体下线（contributor 注册失败：" +
                            "${gatewayCulprit.ifEmpty { "包名未解析到" }}）",
                        // 这条降级**没有经过 L1 二分**（引擎是「成功」启动的，L1 的闸门
                        // 一个都没开），所以事件里必须如实标成 gateway 检测，不能冒 L1 的名。
                        from = "gateway",
                    )
                    Log.w(TAG, "已降级到救援模式，立刻重启引擎（下次启动不加载任何第三方插件）")
                    // ⚠️ **不设 Ready**：直接走既有重启路径，避免界面先加载再重启闪一下。
                    //   状态打成 [State.Dead] 而不是 [State.Failed] —— 引擎没崩、页面能
                    //   加载，而 Failed 会把用户引向**破坏性**的「安全模式」按钮；
                    //   Dead 的「原样重启」正是这里要的（同 [requestRestart] 的 KDoc）。
                    requestRestart("typert gateway 整体下线，切救援模式")
                    return@withContext
                }
                // 走到这里 = 已经是救援态仍失效，或救援 profile 不可用。
                // 两者都**不是**第三方插件的问题（rescue 不含任何第三方 bundle），
                // 所以不再自动推层级 —— 只保持界面可用，让常驻横幅把事实说出来
                // （不变量 I4：降级状态必须常驻可见 + 可一键退出）。
                Log.w(
                    TAG,
                    "gateway 仍失效（层级=${tierNow.id}，救援可用=$rescueAvailable），不再自动降级",
                )
                EventLog.record(
                    "engine.ready",
                    mapOf(
                        "ms" to (System.currentTimeMillis() - startMs).toString(),
                        "port" to portOf(url),
                        "degraded" to "gateway",
                    ),
                )
                _state.value = State.Ready(url)
                Log.i(TAG, "引擎就绪: $url")
                startHeapWatch(proc)
                return@withContext
            }
            _state.value = State.Ready(url)
            Log.i(TAG, "引擎就绪: $url")
            startHeapWatch(proc)
            // profile 如今一定已存在 ⇒ 补登记内置 bundle（见该函数的 KDoc）。
            // ⚠️ 引擎**真的起来了** ⇒ 清掉上次的失败记录。
            //
            //   见 RescueBoot 的类注释：这份记录是「上次为什么起不来」的唯一线索，
            //   救援会话里的 AI 全靠它。但它在**成功之后必须清掉**，
            //   否则下次再出问题时，AI 看到的是早就不成立的旧失败 ——
            //   又一处「结论与它描述的时刻不是同一个」（同族已 28 例）。
            //
            //   位置要紧：必须放在「确实拿到 URL」这个分支里，不能放在别处。
            //   `got && url != null` 就是「引擎打印过 URL」的严格判据。
            //
            //   ⚠️ 上面那段 gateway 检测已经 `return@withContext` 走了，
            //   所以走到这里就是**真·成功**，可以放心清。
            RescueBoot.clearFailure()
            // ⚠️ **正常 profile 起来了 ⇒ 复位过期的降级层级。**（2026-10-27 修）
            //
            // ## 修的是什么
            //
            // 用户反馈（反馈站 `20261027-112156-019d73`）：「在应用冷启动的时候，
            // 它会把正在加载的插件识别成加载失败」。截图里的现象是：
            // 界面还停在「正在启动引擎…」，顶上已经挂着
            // 「救援模式（2026-10-26 18:13:49.302 起）」的横幅 —— 而那是**昨天**。
            //
            // ## 根因
            //
            // `tier()` 读的是 `$DSH_HOME/rescue/state.json`，一旦写进 RESCUE，
            // **只有两个地方会把它改回 NORMAL**：
            //   ① 用户点横幅上的「退出并重试」；
            //   ② AI 通过救援信箱发 `switch-back`。
            //
            // 「引擎这次正常起来了」这条路径**从来没有复位过它**（本函数上方
            // 7382-7383 行的注释还把这个行为当成设计写下来了）。
            // 后果：用户只要因为任何原因降级过一次，`state.json` 就永久停在
            // RESCUE，此后**每一次冷启动**都会先弹出那条横幅，指着昨天的时刻
            // 说「引擎没能正常启动」。用户看到的是「明明起来了却说我失败」，
            // 于是合理地把它理解成「把正在加载的插件误判成加载失败」。
            //
            // ## 为什么在这里复位是安全的
            //
            // ① 走到这里 ⇒ 引擎用 `--profile web`（[PROFILE_NAME]，即**正常
            //    profile、加载了全部第三方插件**）起来了并打印了 URL。
            //    「降级」的唯一意义就是「这次别加载那些插件」，而这次明明加载
            //    成功了 —— 层级描述的处境已经不成立，留着它就是假结论
            //    （本项目已确认 28 例「结论与它描述的对象不符」，这是第 29 例）。
            // ② 与 [RescueBoot.clearFailure] 同一个判据（`got && url != null`），
            //    同样已经排除了 gateway 下线那条 `return@withContext`。
            // ③ 只动 `state.json` 的层级，**不碰用户的插件配置**（I5 不变）。
            //    `quarantinedNames` 由 [RescueBoot.setTier] 的默认参数原样带过去，
            //    所以「已定向禁用哪些插件条目」这条结论**不会丢** ——
            //    它是对文件内容的记录，不依赖层级。
            //
            // ## 为什么不能无条件复位
            //
            // 救援 profile 起来时**也会**走到 `State.Ready`。那种情况下层级必须
            // 保持 RESCUE，否则横幅消失、用户以为插件都回来了（实际一个都没加载）。
            // 判据就是本次启动用的 profile：`targetProfile == PROFILE_NAME`。
            //
            // ⚠️ `SAFE_MODE` 也不动：安全模式是用户显式选择的处境，
            //   它由用户点「退出」来结束，不该被一次成功启动悄悄抹掉。
            if (targetProfile == PROFILE_NAME) {
                val staleTier = RescueBoot.tier().tier
                if (staleTier == RescueBoot.Tier.RESCUE ||
                    staleTier == RescueBoot.Tier.ISOLATED ||
                    staleTier == RescueBoot.Tier.QUARANTINED
                ) {
                    RescueBoot.setTier(
                        RescueBoot.Tier.NORMAL,
                        "引擎已用正常 profile 成功启动（本次加载了全部第三方插件）⇒ 复位过期层级",
                    )
                    Log.i(
                        TAG,
                        "降级层级已复位：${staleTier.id} → normal（引擎本次正常启动，横幅不再显示旧状态）",
                    )
                    EventLog.record(
                        "rescue.tier.reset",
                        mapOf("from" to staleTier.id, "reason" to "normal-boot-ok"),
                    )
                }
            }
            // 事件时间线里记一笔「从起进程到就绪花了多久」。
            // ⚠️ 这是「打开 App 慢」这类模糊反馈唯一能定量的地方：没有它，
            //   用户说「卡」，我们只能猜是引擎慢、解包慢还是网络慢。
            EventLog.record(
                "engine.ready",
                mapOf("ms" to (System.currentTimeMillis() - startMs).toString(), "port" to portOf(url)),
            )
        } else if (userStopped || process !== proc) {
            // ⚠️ **我们自己停掉的，不是启动失败。**（2026-10-15 修，同族第 25 例）
            //
            // `readyLatch` 有三条唤醒路径，上面那条注释只交代了前两条：
            //   ① 正则匹配到 URL ⇒ 引擎起来了
            //   ② stdout 读到 EOF ⇒ 引擎死了
            //   ③ **`stop()` 主动 destroy 旧进程** —— 也会让 stdout 立刻 EOF
            //
            // 第 ③ 条与「引擎自己崩了」在 `await` 这一侧**完全同形**，于是一路走到
            // 下面那个 `else`，被记成「引擎启动失败」。而 `stop()` 明明已经把状态
            // 设成了 [State.Idle]，紧接着被 `State.Failed` 覆盖 —— 用户看到失败
            // 浮层，更糟的是 `RescueBoot.recordFailure` 把「连续启动失败」+1。
            //
            // 真机现场（服务器 `20261014-173741-e2020a5e`，OPPO PKJ110 / Android 16 /
            // v21 system，`20261014-165820-engine-start-failed.txt`）：
            //
            // ```
            // 16:58:16.174 main                用户请求退出降级态（当前 rescue）
            // 16:58:16.176 main                请求界面重启引擎：用户请求退出降级态
            // 16:58:16.177 main                停止引擎            ← stop()
            // 16:58:16.479 main                引擎已退出: …，准备自动重启（第 2 次）
            // 16:58:16.483 main                [connection] connection lost, retry #1
            // 16:58:17.195 DefaultDispatcher-2 启动引擎: …              ← 新 start()
            // 16:58:18.443 main                界面就绪回调超时（WebView 内核 138）
            // 16:58:20.152 DefaultDispatcher-1 停止引擎            ← enterSafeMode → stop()
            // 16:58:20.153 main                [rescue-auto] 连续失败仅 0 次（需 ≥3）
            // 16:58:20.160 DefaultDispatcher-2 已记录现场: crash/…-engine-start-failed.txt
            //                                  —— 引擎启动失败（引擎进程在打印 URL 之前就结束了（等了 3672 ms））
            // 16:58:20.160 DefaultDispatcher-2 engine.failed reason=引擎进程在打印 URL 之前就结束了（等了 3672 ms）
            // 16:58:23.155 DefaultDispatcher-1 安全模式：已隔离损坏的文件 cordis.patch.yml → …
            // ```
            //
            // 两次 `停止引擎` 都是**主动**的（`requestRestart` 与 `enterSafeMode`），
            // 而那条 3672 ms 的「启动失败」纯粹是第 ③ 条唤醒路径的产物 ——
            // 引擎**从未**失败过，是被人叫停的。
            //
            // ⚠️ 后果不止一行错日志：这次假失败触发了 `enterSafeMode`（推开用户的
            //    `cordis.patch.yml` / `package.json`、摘掉 7 个第三方 bundle、删掉
            //    引擎版本戳），代价是用户重解包 15 秒 + 插件配置全丢。也就是说
            //   这条误判把「切个 profile」变成了**破坏性操作**。
            //
            // 判据与 [markEngineDeadIfCurrent] 完全同源（那里早就在用 `userStopped`），
            // 只是这里漏了：
            //   · `userStopped` ⇒ 是我们自己停的，状态已由 [stop] 设为 [State.Idle]
            //   · `process !== proc` ⇒ 这一轮已被另一次启动接管（stop 之后又 start 了），
            //     这一轮的任何结论都不该落到全局状态上
            // 两者都成立时**必须原样返回**，不能改状态、不能记失败。
            Log.i(
                TAG,
                "启动等待被主动停止打断（已等 ${System.currentTimeMillis() - startMs} ms），不算启动失败",
            )
            return@withContext
        } else {
            val tail = synchronized(logLines) { logLines.joinToString("\n") }
            // ⚠️ **判据是「等满了没有」，不是「进程还活着没有」。**
            //
            // 这里原来写 `if (proc.isAlive) "超时未打印 URL" else "引擎进程已退出"`，
            // 是**错的**，会把最常见的一种情况报成「超时」：
            //
            // `readyLatch` 除了「正则匹配到 URL」之外，还在读 stdout 那条线程的
            // `finally` 里被 countDown（见上面 `流关闭（引擎退出）时也要唤醒等待方`）。
            // 于是引擎一退出，`await` **立刻**返回，而此刻 `proc.isAlive` 往往还是
            // true（`Process.isAlive` 要等子进程被 reap 之后才翻假）。结果：
            // 引擎明明**刚启动就死了**，界面却写「超时未打印 URL」——
            // 把「启动即崩」误导成「起得慢」，排查方向直接带偏。
            //
            // 真机证据（53 个诊断包、80 场 engine-start-failed）：`READY_TIMEOUT_MS`
            // 是 120_000，而全部 9 场「超时未打印 URL」的实际等待只有
            // **6873 / 6911 / 6911 / 6911 / 7356 / 8009 / 10699 ms** ——
            // 没有一场等满过 120 秒，所以它们**全都不是超时**。
            //
            // 改判据后文案才与事实一致，并且把等待时长一并写进原因里
            // （原来只有崩溃现场文件里才有，日志行里看不到）。
            val waited = System.currentTimeMillis() - startMs
            val reason = if (waited >= READY_TIMEOUT_MS) {
                "等待 ${waited / 1000} 秒仍未打印 URL（确为超时）"
            } else {
                "引擎进程在打印 URL 之前就结束了（等了 ${waited} ms）"
            }
            // ⚠️ **顺序是要害：先落盘「为什么失败」，再广播 `State.Failed`。**
            //
            //   这两个动作的**读者是不同线程**，而广播会**同步**唤醒 main 线程上
            //   的收集者（`MainActivity` 的 `collect { }` 里那句
            //   `engine.shouldAutoEnterSafeMode()`），后者立刻去读
            //   `RescueBoot.lastFailure()`。
            //
            //   原先的顺序是反的（先广播、后记录），于是 main 读到的永远是**上一次**
            //   的失败记录 —— 真机现场（`20261015-013148-76dfff48.zip`，
            //   Xiaomi 24117RK2CC / Android 17 / v21 gecko）：
            //
            //   ```
            //   01:31:31.462 [I] #101 main                 [rescue-auto] 连续失败仅 0 次（需 ≥3）
            //   01:31:31.470 [E] #102 DefaultDispatcher-2  已记录现场: engine-start-failed
            //   ```
            //
            //   同一场失败，读记录的线程比写记录的线程**早 8 ms**。后果是
            //   `consecutiveFailures` 每次都被读成比实际小 1 —— 判据里的门
            //   （`>= 3`）**永远差一次**，于是自动进安全模式（L3）这条兜底
            //   整条阶梯**整体推迟一轮**：引擎本该在第 3 次失败时自救，实际要等到
            //   第 4 次。而这一轮里用户面对的是引擎起不来、界面完全不可用，
            //   只能自己反复重开 App 去凑那个数。这正是本项目缺陷族
            //   「结论与它描述的对象不是同一个」在**时序**上的又一例。
            //
            //   ⚠️ 说清界限：这是「晚一轮」而**不是**「永不触发」。`tier` 升到
            //      RESCUE 后 tier 这一条会长期成立，所以下一轮 streak 追上时仍会
            //      触发。别把它写成「永不」—— 那本身又是一次同族失真。
            //
            //   把记录提到广播之前即可根除：广播之后任何时刻读，记录都已就位。
            //   ⚠️ 别改成「广播后再补一次记录」—— 那会让同一场失败被记两次，
            //      计数翻倍，L1/L2/L3 全部提前触发（另一种失真）。
            val tailList = synchronized(logLines) { logLines.toList() }
            RescueBoot.recordFailure(
                bootId = BootState.bootId,
                exitCode = runCatching { proc.exitValue() }.getOrNull(),
                tailLines = tailList,
            )
            _state.value = State.Failed("引擎启动失败（$reason）。最后输出:\n$tail")
            // ⚠️ **P0：把「真实启动失败」变成可读的状态**（见 RescueBoot 的类注释）。
            //
            // 这里传的是**真实启动**的 stdout 尾部 —— 这一点是整个修复的要害：
            // 启动前体检（[preflight]）读的是 `--dump-config` 的**静态输出**，
            // 而它看不见插件在 `apply()` 期抛的异常。真机反例（2026-10-14，
            // 某小米机型 / Android 16 / v18）：`dump-config` 退出码 0、
            // 4 个 required 条目全在，体检因此**放行**，引擎照死 —— 连续 8 次。
            //
            // 所以判据必须换成「引擎自己说了 required plugin did not activate」，
            // 而那行只在真实启动的输出里。
            //
            // ⚠️ 顺序：必须在 CrashRecorder 之前 —— 下面那步要写崩溃现场，
            //   而现场文件里会读事件时间线；先把「为什么失败」的结构化事实落盘，
            //   保证即使现场写入失败也留下了线索。
            //   （记录本身已提到上面 `State.Failed` 之前，见那段说明。）
            // ⚠️ 「引擎起不来」是本 App 最严重的一类故障（界面直接不可用），
            //   所以要写**崩溃现场文件**而不只是日志行：现场文件里带着
            //   最后 200 行日志与设备快照，用户点「导出日志」就能一次性带走。
            CrashRecorder.recordAnomaly(
                "engine-start-failed",
                "引擎启动失败（$reason），已等 $waited ms",
                'E',
            )
            EventLog.record("engine.failed", mapOf("reason" to reason))
        }
    }

    /**
     * 让 uid 2000 特权通道回到「尽力可达」状态：常驻监听 + 一次性自愈 + 周期守护。
     *
     * ## 为什么单独抽出来
     *
     * 这三件事原先只写在 [start] 的**新建进程那条路**上，而 [start] 开头有个
     * 「引擎已在运行 → 直接 return」的早返回 —— 用户「清后台重开 App」时命中的
     * 恰恰是那个早返回（引擎进程被前台服务 `START_STICKY` 保着），于是整块自愈
     * 一次都不跑。这正是用户报的「关掉 WiFi 再清后台重开，权限就没了」里
     * **后半段**的根因。
     *
     * 抽成方法之后两条路都调用它，语义是「无论引擎是刚起还是早就在跑，都把特权
     * 通道重新拉一遍」。幂等：监听与守护都带「已在跑就返回」的判据，自愈本身也幂等
     * （5555 正常时 runner 第一句探测就返回）。
     *
     * ⚠️ **自愈与守护都在后台线程**，本方法本身不阻塞。
     * ⚠️ 依赖的路径参数从字段现取（`bootstrap` / `dshHome` / `filesDir`），
     *   不依赖 [start] 的局部变量 —— 早返回那条路上那些局部变量根本不存在。
     */
    private fun resumePrivilegedChannel() {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        // runner 不存在 = 连脚本都没解出来，后面两件事都无从谈起
        if (!runner.isFile) return
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        val portFile = File(dshHome, WirelessDebugging.PORT_FILE)
        val nodeBin = bootstrap.nodeBinary

        // 1) 常驻监听无线调试端口（端口会变，一变就永久失效）
        WirelessDebugging.startWatcher(context, portFile)

        // 2) 立即自愈一次：重启手机后 5555 必然消失（`persist.adb.tcp.port` 写不进去），
        //    这里顺手切一次，省掉「用户得自己点一下切到固定端口」这一步。
        Thread {
            runCatching {
                WirelessDebugging.ensureTcpPort(
                    context = context,
                    nodeBin = nodeBin,
                    runner = runner,
                    adbHome = adbHome,
                    dshHome = dshHome,
                )
            }.onSuccess {
                // ⚠️ 成功也要打：这条自愈**没有**任何界面反馈，不打日志的话
                //   「到底切没切成功」只能去翻 runner 的 stderr。
                if (it.ok) Log.i(TAG, "特权通道自愈：${it.message}")
                else Log.i(TAG, "特权通道自愈未切换固定端口：${it.message}")
            }.onFailure { Log.w(TAG, "特权通道自动切固定端口失败: ${it.message}") }
        }.start()

        // 3) 周期守护：这是「过一阵权限自己掉了」唯一的自动恢复路径。
        //    ⚠️ 没有它的话，手机重启 / adbd 重启 / 端口被抢都只能靠用户手点。
        if (adbHome.isDirectory) {
            WirelessDebugging.startFixedPortGuard(
                context = context,
                nodeBin = nodeBin,
                runner = runner,
                adbHome = adbHome,
                dshHome = dshHome,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 引擎堆水位监控（防止可预测的 OOM 突然打死界面）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 堆水位监控线程。
     *
     * ## 为什么需要它（真机取证，2026-10-27）
     *
     * 真机 `files/logs/engine.log` 里 **10 次**同样的 FATAL：
     *
     * ```
     * Mark-Compact 1004.4 (1026.1) -> 1004.4 (1026.1) MB, 1903.46 / 0.00 ms
     * FATAL ERROR: Ineffective mark-compacts near heap limit
     * ```
     *
     * 注意 GC 前后都是 **1004.4 MB**、释放量 0.00 —— 一个字节都没回收。
     * 这不是"堆不够大"，是**真实泄漏**（可达对象一直在涨）。
     *
     * 而 `events.jsonl` 里 10 次退出的运行时长是：
     * `11.1h / 7.8h / 7.7h / 7.8h / 7.8h / 7.7h / 7.8h` —— **规整得可怕**，
     * 折算约 130 MB/小时线性增长。也就是说：
     *
     * · 崩溃时刻是**可预测的**（约 7.7 小时）；
     * · 提高堆上限（[oldSpaceMb]）只能把 7.7 小时推成约 24 小时，**治不了本**；
     * · 用户每次撞上的体验都是：正用着 → 界面突然全无响应 → 等 27 秒才恢复
     *   （`engine.ready ms=27534`）。
     *
     * 既然崩溃可预测，就不该"等它崩"，而该**在它崩之前有序重启**。
     *
     * ## 为什么用 /proc 而不是 Node 自报
     *
     * 读 `VmRSS` 不需要在引擎里跑代码、不需要改引擎、不依赖任何协议 ——
     * 引擎进程就是我们自己 spawn 的，pid 一直在手上。
     * `VmRSS` 包含 V8 堆 + native + code space，比 `heapUsed` 更接近
     * "这个进程真的吃了多少"，正是 OOM 的判据。
     *
     * @see HEAP_WATCH_INTERVAL_MS
     * @see heapRestartRatio
     */

    private val memoryMonitor = EngineMemoryMonitor(
        isCurrent = { process === it && !userStopped },
        isBusy = { activitySource.isBusy() },
        requestRestart = { requestRestart("堆水位过高") },
    )

    private fun startHeapWatch(proc: Process) = memoryMonitor.start(proc, oldSpaceMb(), heapRestartRatio())
    private fun stopHeapWatch() = memoryMonitor.stop()

    fun stop() {
        stopHeapWatch()
        // Shizuku 桥随引擎一起收：它的接受线程是 daemon、名字随进程释放，
        // 但显式关掉能让下一次 start() 重新绑定同一个名字而不撞车。
        ShizukuChannel.stop()
        process?.let { p ->
            Log.i(TAG, "停止引擎")
            // ⚠️ 必须在 destroy 之前置位：destroy 之后 stdout 线程会立刻读到 EOF
            //   并进 markEngineDeadIfCurrent，两者是并发的。
            userStopped = true
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
        process = null
        // 引擎停了就别再占着 mDNS 监听（NsdManager 的 listener 注册是全局资源）
        WirelessDebugging.stopWatcher()
        // 守护线程同理：引擎都停了，再去切 adbd 没有意义，还白耗电
        WirelessDebugging.stopFixedPortGuard()
        _state.value = State.Idle
    }

    /**
     * 请求**界面**重启引擎（供救援信箱的 `switch-rescue` / `switch-back` 用）。
     *
     * ## 为什么要拐一道，而不是直接 `stop()` + `start()`
     *
     * 换 profile 之后界面必须**重新加载 Web GUI**：旧页面连的是旧引擎的端口，
     * 而 `--profile rescue` 起来的是**另一个引擎实例**（另一个端口、另一个 token）。
     * 光在后台重启引擎、不重载页面，用户看到的就是一个永远连不上的界面。
     *
     * 重载页面的能力在 `MainActivity`（`forceReloadOnReady` + `loadUrl`），
     * 而 `EngineManager` 不该反向持有 Activity —— 所以这里只发一个**状态信号**，
     * 让 `MainActivity.observeEngine` 走它已有的 `State.Dead` 重启路径
     * （那条路径已经会把 `forceReloadOnReady` 置位并重新 `loadUrl`，
     * 正是这里需要的行为，不必新写一套）。
     *
     * ## 为什么用 `State.Dead` 而不是 `State.Failed`
     *
     * 见 `State.Dead` 的 KDoc：`Failed` 的界面分支会引导用户去点「安全模式」，
     * 而安全模式会**推开用户的插件配置** —— 切 profile 是正常操作，不该诱导
     * 用户做破坏性动作。`Dead` 的语义「原样重启」正好匹配。
     *
     * ⚠️ 必须**先** `stop()`（把 `userStopped` 置位并杀死旧进程），否则
     *   `start()` 会在开头命中「引擎已在运行」的早返回，profile 根本不会切换。
     */
    fun requestRestart(reason: String) {
        Log.i(TAG, "请求界面重启引擎：$reason")
        EventLog.record("engine.restart-requested", mapOf("reason" to reason))
        stop()
        _state.value = State.Dead(reason)
    }

    /**
     * 引擎**运行期**退出时的收口（由 stdout 消费线程在读到 EOF 时调用）。
     *
     * @param proc     读到 EOF 的那个进程实例。
     * @param wasReady 它是否曾经打印过带 token 的 URL。
     *
     * ## 为什么需要它
     *
     * 原先 `_state` 只在「启动期」被写：进程一启动就 `Ready(url)`，此后**再也没有
     * 任何一处**会因进程退出而更新它。于是引擎被系统回收之后，界面仍停在
     * `Ready(url)`、WebView 仍是上一帧渲染好的画面 —— 用户看不出任何异常，
     * 直到发消息失败。更糟的是前端的重连逻辑打的是那个**已经没人监听的端口**，
     * 报出来的错也是误导性的。
     *
     * ## 为什么是 [State.Dead] 而不是 [State.Failed]
     *
     * 见 `State.Dead` 的 KDoc：启动失败要「安全模式」推开配置，运行期死亡只需
     * 原样重启。混在一起会诱导用户去点那个破坏性的按钮。
     *
     * ## 判据
     *
     * - `process !== proc` ⇒ 已经有人重启过引擎（或 stop 过了），本次 EOF 属于
     *   被换掉的旧进程，**不动状态**。
     * - `userStopped` ⇒ 是我们自己停的，状态已由 [stop] 设为 [State.Idle]。
     * - 没 ready 过 ⇒ 是启动失败，那条路径由 `start()` 自己的超时分支负责
     *   （它会给出更准确的 reason + 日志尾巴），这里不抢。
     */
    private fun markEngineDeadIfCurrent(proc: Process, wasReady: Boolean) {
        if (userStopped) return
        if (process !== proc) return
        if (!wasReady) return
        /*
         * ⚠️ `exitValue()` 拿不到时**要短暂重试**，不能直接记 null。
         *
         * 这个方法是被 stdout 读线程在 `finally` 里调的（EOF ⇔ 引擎没了），
         * 而 `Process.exitValue()` 在子进程**还没被 reap** 时会抛
         * `IllegalThreadStateException` —— EOF 与 reap 之间有一个很短但真实的窗口。
         *
         * 真机现场就是这样：`crane ... reason: exitCode=null 进程已退出`。
         * 于是最有价值的那一个数字（134 = SIGABRT）**在每一份现场里都丢了**，
         * 只能从 `recent events` 的 `engine.exit code=134` 里间接看出来。
         *
         * 最多等 300ms（3 × 100ms）：这已经远超 reap 的实际延迟，
         * 又不至于拖住这条线程 —— 它后面还有 flush 和状态置位要做。
         */
        var code = runCatching { proc.exitValue() }.getOrNull()
        if (code == null) {
            for (i in 0 until 3) {
                if (code != null) break
                runCatching { Thread.sleep(100L) }
                code = runCatching { proc.exitValue() }.getOrNull()
            }
        }
        val detail = if (code != null) "退出码 $code" else "进程已退出（未能取到退出码：EOF 后 300ms 内仍未 reap）"
        Log.w(TAG, "引擎在运行期退出（$detail），界面已失去后端")
        /*
         * ⚠️ **必须把引擎自己的输出一起写进现场**（2026-10-27 真机审查补）。
         *
         * 真机上这个崩溃每天来 2–3 次（`20261023` 至 `20261026` 连续 6 天），
         * `recent events` 里每次都写着同一个 `engine.exit code=134`：
         *
         *     134 = 128 + 6 = SIGABRT
         *
         * 在 Node 里它几乎总是这几种之一：`FATAL ERROR: Reached heap limit`、
         * `Assertion failed`、插件 `apply()` 里抛的未捕获异常。**这些消息是
         * V8/Node 直接写到进程 stdout/stderr 的**，从来不经过 `LogStore` ——
         * 也就是说，原来那份现场里 App 侧的 `--- last 200 log lines ---`
         * 完全是 mdns / 固定端口守护之类的无关日志，**引擎说过的话一个字都没有**。
         *
         * 而引擎的输出其实一直躺在 [logLines] 这个 400 行环形缓冲里
         * （stdout 读取线程每读一行就 `addLast`，读线程的 `finally` 在引擎死亡
         * 时正好执行到这里）。**取一行快照的成本可以忽略，换来的却是唯一根因。**
         *
         * 快照必须在这里取（而不是事后）：下一次 `start()` 一进来就会
         * `logLines.clear()`（见 [start] 开头），过期即失效。
         */
        val engineTail = synchronized(logLines) { logLines.toList() }
        if (code == 134) {
            /*
             * 单独提一句 SIGABRT 的含义。
             *
             * ⚠️ 只在这个码上打，不要泛化：`Log.w` 里写"可能 OOM"却对
             *   所有退出码都说，就成了噪音。134 的语义是确定的（abort），
             *   这么写不算猜。
             */
            Log.w(
                TAG,
                "引擎退出码 134 = SIGABRT（V8 堆耗尽 / 断言失败 / 未捕获异常的典型信号），" +
                    "引擎最后输出 ${engineTail.size} 行已并入崩溃现场",
            )
        }
        CrashRecorder.recordEngineExit(
            code,
            expected = false,
            detail = detail,
            engineTail = engineTail,
        )
        EventLog.record("engine.exit", mapOf("code" to (code?.toString() ?: "?"), "wasReady" to "true"))
        _state.value = State.Dead(detail)
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * 用配对码把本 App 的 ADB 密钥授权给设备（无线调试配对，Android 11+）。
     *
     * ⚠️ **阻塞调用**（发现端口 + SPAKE2 握手 + adb 启动，可能十几秒），
     * 必须在后台线程调用。调用方见 [AdbPairingNotifier]（走 `goAsync()`）。
     *
     * @param code 用户从系统「使用配对码配对设备」界面抄来的 6 位码。
     * @param port 配对端口；`<= 0` 时由 [WirelessDebugging.pair] 自己重新发现。
     */
    fun pairWirelessDebugging(code: String, port: Int): WirelessDebugging.Result {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        if (!adbHome.isDirectory) {
            return WirelessDebugging.Result(
                false,
                "内置 adb 工具链未就绪（$ADB_WIRELESS_DIR 不存在），无线调试不可用。",
            )
        }
        val result = WirelessDebugging.pair(
            context = context,
            nodeBin = bootstrap.nodeBinary,
            runner = runner,
            adbHome = adbHome,
            dshHome = dshHome,
            code = code,
            // ⚠️ 把通知带下来的端口**真的传下去**。它一直到这一步都没被用过：
            //    方法签名收下了 port，却没往下传，`WirelessDebugging.pair()` 于是
            //    自己重新发现一次 —— 而用户此刻多半已经从「使用配对码配对设备」
            //    界面切到了通知栏，那个端口早没了，重新发现必然落空。
            //    症状就是「通知栏明明显示了端口，配对却报没找到端口」。
            knownPort = port,
        )
        // 配对成功后立刻发现连接端口并落盘，这样用户不必再等常驻监听
        // 撞上「无线调试已开启」那一刻。
        //
        // ⚠️ 用**带重试的** `ensureTcpPort` 而不是单次 `discoverConnectPort`。
        //   配对过程中无线调试会重启 adbd，端口要过一会儿才广播出来 —— 单次发现
        //   常常落空，而原实现在落空时**静默跳过整个补切**（连一句日志都没有），
        //   于是「配对成功但特权身份还是 app」既没修好、也查不出原因。
        //   现在失败会明确记日志，并且和启动自愈共用同一套重试预算。
        if (result.ok) {
            // runner 在 `--pair` 里已经自己切过一次，但**那一次用的是配对前的
            // 旧端口**：无线调试在配对过程中会重启 adbd，端口随之改变，于是
            // `adb -s 127.0.0.1:<旧端口> tcpip 5555` 必然失败。
            // 这里拿着**重新发现的端口**再补切一次，把那条缝补上。
            // 已经切成 5555 时 `switchToTcp` 是幂等的（先探 legacy，通就返回 true），
            // 所以重复调用没有代价。
            val switched = WirelessDebugging.ensureTcpPort(
                context = context,
                nodeBin = bootstrap.nodeBinary,
                runner = runner,
                adbHome = adbHome,
                dshHome = dshHome,
            )
            if (switched.ok) {
                Log.i(TAG, "配对成功且已切到固定端口: ${switched.message}")
                // ⚠️ 补切成功就要**改掉** runner 那句「还没切到固定端口」的结论。
                //   runner 报「没切成」时用的是旧端口（配对过程中 adbd 重启、端口变了），
                //   这里用新端口补切成功，那句话此刻已经不成立。不改的话通知栏会告诉
                //   用户「请去设置页点『切到固定端口』重试」—— 而实际上什么都不用做，
                //   用户白跑一趟还会以为修复没生效。
                return result.copy(message = "配对成功，并已切到固定端口 5555 —— 现在关掉无线调试、关掉 Wi-Fi 都不影响特权通道。")
            }
            Log.w(TAG, "配对成功但补切固定端口失败: ${switched.message}")
        }
        return result
    }

    /**
     * 把 adbd 切到固定的 TCP 端口（`adb tcpip 5555`）。
     *
     * 切完之后特权通道**不再依赖无线调试开关** —— 实测关掉无线调试、关掉 Wi-Fi
     * 都不影响（走 127.0.0.1 回环）。详见 [WirelessDebugging.switchToTcp]。
     *
     * ⚠️ **阻塞调用**（最坏约 30 秒：发现无线调试端口 + 切换 + 轮询确认真的拿到
     * uid 2000），必须在后台线程调用。稳态下（5555 已在听）只有几百毫秒。
     */
    fun switchToTcpMode(): WirelessDebugging.Result {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        if (!adbHome.isDirectory) {
            return WirelessDebugging.Result(
                false,
                "内置 adb 工具链未就绪（$ADB_WIRELESS_DIR 不存在），无线调试不可用。",
            )
        }
        return WirelessDebugging.switchToTcp(
            nodeBin = bootstrap.nodeBinary,
            runner = runner,
            adbHome = adbHome,
            dshHome = dshHome,
        )
    }

    fun recentLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    companion object {
        fun isBuiltinPluginEnabled(context: Context, id: String): Boolean =
            // ⚠️ 用 `applicationContext`：这是**公开 API**，将来可能从 Activity 里调。
            // SharedPreferences 本身不会因为持有 Activity 而泄漏，但 `getSharedPreferences`
            // 会缓存它内部的 Context 引用 —— 传 Activity 进去等于把 Activity 钉在
            // 单例里。一行 `applicationContext` 就把这条路堵死，没有成本。
            context.applicationContext
                .getSharedPreferences(PLUGIN_PREFS, Context.MODE_PRIVATE)
                .getBoolean(id, true)

        /** 旧版原生开关，仅作为首次 bundle 迁移的默认值保留。 */
        fun setBuiltinPluginEnabled(context: Context, id: String, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(PLUGIN_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(id, enabled).apply()
            Log.i(TAG, "内置插件开关: $id = $enabled")
        }

        /**
         * 所有可开关的内置插件，供设置页渲染。
         *
         * ⚠️ **顺序即设置页显示顺序**，先列开销最大的（用户最想关的）。
         */
        private fun heapRestartRatio(): Double = 0.85

        /**
         * 引擎「起来了」的唯一判据：它打印了带 token 的回环 URL。
         *
         * 真机原文：`dsh web: http://127.0.0.1:3080/?token=xxxxxxxx`。
         *
         * ⚠️ **`start()` 与 [probeBoot] 共用这一条**（原来 `start()` 里就地写了一份）。
         *   两处各写一份的后果是「试启动说成功、真启动说失败」这类无法解释的差异 ——
         *   而验证类工具一旦不可信，整个降级阶梯的判断都跟着不可信。
         */
        @Volatile
        private var instance: EngineManager? = null

        private var commandMailboxes: EngineCommandMailboxes? = null
        private var activitySource: EngineActivitySource? = null
        private var builtInWebEngine = false

        @Synchronized
        fun initialize(
            mailboxes: EngineCommandMailboxes,
            activity: EngineActivitySource,
            builtInWebEngine: Boolean,
        ) {
            check(commandMailboxes == null && instance == null) { "EngineManager already initialized" }
            commandMailboxes = mailboxes
            activitySource = activity
            this.builtInWebEngine = builtInWebEngine
        }

        fun get(context: Context): EngineManager =
            instance ?: synchronized(this) {
                instance ?: EngineManager(
                    context.applicationContext,
                    checkNotNull(commandMailboxes) { "EngineManager.initialize must run in Application.onCreate" },
                    checkNotNull(activitySource),
                    builtInWebEngine,
                ).also { instance = it }
            }
    }
}
