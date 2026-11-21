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
 *   启动参数           EngineLaunchSpec
 *   常量与配置         engine.constants（EngineConstants.kt）
 *
 * 新的资产安装职责放入专职组件；进程和启动状态由本类持有。
 * 安装依赖失败仍沿用各组件现有日志和降级行为，不改变启动顺序。
 */
import android.app.ActivityManager
import android.content.Context
import com.dsh.android.log.DshLog as Log
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogStore
import com.dsh.android.log.CrashRecorder
import com.dsh.android.engine.constants.*
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
    private val builtInWebEngine: Boolean,
    private val launchAdditions: EngineLaunchAdditions?,
) {

    sealed interface State {
        data object Idle : State
        data class Extracting(val percent: Int) : State
        data object Starting : State
        data class Ready(val url: String) : State
        data class Failed(val message: String) : State

        /**
         * 运行期退出（曾经就绪）与 [Failed]（未能启动）分别报告。
         */
        data class Dead(val message: String) : State

        /** 主动重启请求；宿主应重新启动引擎并在就绪时重载页面。 */
        data class RestartRequested(val message: String) : State
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

    @Volatile var webViewMajorHint = 0

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
     * `null` = 还没解出来 / 解失败。
     */
    private val fetchGuardPath: File? get() = compatibilityInstaller.fetchGuardPath

    /** 引擎运行所需的可写 HOME（DSH 在此存放配置、凭据、会话）。 */
    private val homeDir get() = File(context.filesDir, "home")

    /** DSH_HOME：DSH 自己的数据目录。 */
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

    /** 安装引擎资产并映射安装进度。 */
    suspend fun ensureExtracted(versionStamp: String): Boolean = withContext(Dispatchers.IO) {
        executionGate.allowed() && assetInstaller.ensureExtracted(versionStamp,
            progress = { _state.value = State.Extracting(it) },
            failure = { _state.value = State.Failed(it) },
        )
    }

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
     * 出现了 10 次**；进程退出后界面没有后端，必须等下一次启动就绪。
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
     * 才重解包（见 [ensureExtracted]），已经打过补丁的安装里那行
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
     * ```
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
    suspend fun start(startupTimeoutMs: Long = READY_TIMEOUT_MS) = withContext(Dispatchers.IO) {
        if (!executionGate.allowed()) {
            _state.value = State.Failed("维修期间禁止未经许可的引擎启动")
            return@withContext
        }
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

        val repairExclusion = EngineRepairLock.tryAcquire(context.filesDir)
        if (repairExclusion == null) {
            _state.value = State.Failed("维修事务正在修改引擎文件，或上一引擎进程尚未退出。请稍后重试。")
            return@withContext
        }
        var exclusionTransferred = false
        try {
        _state.value = State.Starting
        synchronized(logLines) { logLines.clear() }
        // 启动计时锚点。后面「引擎就绪」事件用它算出总耗时 ——
        // 「打开 App 慢」这类反馈只有靠这个数才能定位到具体阶段。
        val startMs = System.currentTimeMillis()
        val timeline = BootTimeline(TAG)
        EventLog.record("engine.start")
        // ⚠️ 每次启动都清掉「主动停止」标记，否则 stop() 之后再也起不来
        //   （EOF 会被当成用户停止而不再上报死亡，这里反过来：新一轮启动了，
        //   旧标记已经没有意义）。
        userStopped = false

        homeDir.mkdirs()
        dshHome.mkdirs()
        workspaceDir.mkdirs()

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

        runtimeDependencies.ensureNativeAddon()
        runtimeDependencies.ensureSystemAddon(::patchFlockPlatform)
        timeline.mark("原生插件")
        val builtinBundles = BuiltinPluginBundles(context, engineDir, dshHome, nodeBin, bootstrap.prefixDir)
        if (builtinBundles.initializeProfile() == null || !builtinBundles.ensure()) {
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
        // 前端补丁要在 WebView 首次加载前落盘，所以放在启动引擎这一步里
        compatibilityInstaller.ensureWebCompat()
        // HTTP header 净化垫片：配置里混进中文时，undici 会抛一句只带字符下标的
        // 英文错误、消息直接发不出去。必须在引擎进程启动前落盘，否则
        // `NODE_OPTIONS=--import` 拿不到文件（见 ensureFetchGuard）。
        compatibilityInstaller.ensureFetchGuard()
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
        // Android 兼容处理：避免插件的未捕获运行期异常直接退出整个进程。
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
        compatibilityInstaller.ensureWebViewCompat(webViewMajorHint)
        timeline.mark("资产与补丁")
        // 兼容产物会覆盖会话 UI 源码，发图格式补丁必须在覆盖后应用。
        patchImageMediaType()
        // 出厂默认设置：只写 profile 的用户 patch 层，且只在它不存在时写一次。
        // 必须早于引擎启动 —— 引擎的 initProfile() 会给缺失的 patch 文件补一份空模板。
        profileDefaults.seedDefaultSettings()
        // ── 已移除：多模态配置 + Agnes 内置号池（用户要求整块删掉）──────────
        // 不再解包 agnes-multimodal、不再播种账号池、不再注入 `agnes` 供应商；老 profile
        // 里的残留由 profileDefaults.stripBuiltinAgnesProfile() 清掉。
        // 内置**中文 skill** 落到 $DSH_HOME/skills（全局发现根，每个预设都扫得到）。
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
        shellToolMounted = privRunner != null && isBundleEnabled(SHELL_TOOL_PACKAGE)
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

        // 让模型知道「有特权身份可选、什么时候该用、用了会失去什么」。
        // 判据同时包含 profile bundle 开关与 runner 是否就位。
        profileOverlays.ensurePrivilegedPrompt(shellToolMounted)

        // root（uid 0）通道：**先端到端探测**再决定挂不挂。
        // ⚠️ 顺序是硬性的 —— 探测要起 su 进程（可能弹授权框），必须早于引擎进程
        //   启动，否则用户会在界面已经可用之后才看到那个框。见 [RootAccess]。
        // ⚠️ 探测结果有 24h 缓存，所以稳态下这里只是一次文件读。
        val rootRunner = toolInstaller.ensureRootShell()
        val rootState = RootAccess.detect(context)
        rootToolMounted = rootRunner != null && rootState.granted && isBundleEnabled(ROOT_TOOL_PACKAGE)
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
        val additions = launchAdditions?.prepare(engineDir) ?: EngineLaunchExtension()
        val overlays = listOfNotNull(
            sandboxPatch,
            workspacePatch,
            pluginManagerPatch,
        ) + additions.patches
        val port = resolveWebPort()
        val cmd = EngineLaunchSpec.buildEngineArgs(nodeBin, binJs, PROFILE_NAME, overlays, port)
        timeline.mark("起进程前")

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
            // V8 编译缓存（node ≥ 22 支持）：把模块编译产物落到磁盘，冷启动少编译一遍。
            // 实测引擎要加载 1000+ 个模块，这一项直接决定「启动引擎 → 引擎就绪」那 8 秒。
            put("NODE_COMPILE_CACHE", File(context.cacheDir, "node-compile-cache").absolutePath)
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

        // 无线调试端口由 Kotlin 侧发现（Node 侧发不出 UDP 组播，见 WirelessDebugging）。
        // 常驻监听：端口会随用户重开无线调试/重启/切网络而变，只在启动时发现一次的话
        // 一变就永久失效。监听本身由系统 mdnsd 承担，开销可忽略。
        resumePrivilegedChannel()

        pb.environment().putAll(additions.environment)
        val proc = try {
            check(executionGate.allowed()) { "Repair execution permit ended before process launch" }
            pb.start()
        } catch (t: Throwable) {
            stopEngineResources()
            _state.value = State.Failed("引擎进程启动失败: ${t.message}")
            return@withContext
        }
        process = proc
        exclusionTransferred = true
        val readyLatch = java.util.concurrent.CountDownLatch(1)
        var readyUrl: String? = null
        // waitFor, not stdout EOF: descendants can retain a pipe after the Node child exits.
        // Wake startup immediately even when those descendants still own the stdout pipe.
        Thread({
            try { proc.waitFor() } finally { readyLatch.countDown(); repairExclusion.close() }
        }, "dsh-engine-repair-exclusion").apply { isDaemon = true; start() }

        // 只用一个后台线程从头到尾持续消费 stdout。
        // 不能用 use{} 读完 URL 就关流：引擎之后仍在写日志，64KB 管道缓冲写满后
        // 会阻塞在 write 上导致引擎彻底卡死；重复打开同一 inputStream 也会失败。

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
                        if (readyUrl == null) {
                            val m = URL_PATTERN.find(line)
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

        val got = readyLatch.await(startupTimeoutMs.coerceIn(1, 300_000L), TimeUnit.MILLISECONDS)
        val url = readyUrl

        if (userStopped || process !== proc) {
            // 主动停止或已被下一次启动接管；旧进程不应覆盖宿主正在使用的状态。
            Log.i(TAG, "启动等待被主动停止打断，不算启动失败")
            return@withContext
        }
        if (got && url != null) {
            _state.value = State.Ready(url)
            Log.i(TAG, "引擎就绪: $url")
            // 事件时间线里记一笔「从起进程到就绪花了多久」。
            // ⚠️ 这是「打开 App 慢」这类模糊反馈唯一能定量的地方：没有它，
            //   用户说「卡」，我们只能猜是引擎慢、解包慢还是网络慢。
            EventLog.record(
                "engine.ready",
                mapOf("ms" to (System.currentTimeMillis() - startMs).toString(), "port" to portOf(url)),
            )
        } else {
            val tail = synchronized(logLines) { logLines.joinToString("\n") }
            // await 返回 false 才是超时；EOF 会提前唤醒，并不保证进程已被回收。
            val waited = System.currentTimeMillis() - startMs
            val reason = if (!got) {
                "等待 ${waited / 1000} 秒仍未打印 URL（确为超时）"
            } else {
                "引擎进程在打印 URL 之前就结束了（等了 ${waited} ms）"
            }
            // 超时后进程可能仍存活；先释放它，让显式重试能真正启动新进程。
            stopEngineResources()
            _state.value = State.Failed("引擎启动失败（$reason）。最后输出:\n$tail")
            EventLog.record("engine.failed", mapOf("reason" to reason) + EngineFailureProjection.fields(tail))
            CrashRecorder.recordAnomaly(
                "engine-start-failed",
                "引擎启动失败（$reason），已等 $waited ms",
                'E',
            )
        }
        } finally {
            if (!exclusionTransferred) repairExclusion.close()
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

    fun stop() {
        if (stopEngineResources()) _state.value = State.Idle
        else _state.value = State.Failed("引擎尚未确认退出，不能报告停止成功")
    }

    /** Repair cleanup is cooperative: never escalates SIGTERM to destroyForcibly/SIGKILL. */
    suspend fun stopForRepair(): Boolean = withContext(Dispatchers.IO) {
        val child = process
        userStopped = true
        if (child != null && child.isAlive) {
            child.destroy()
            if (!child.waitFor(3, TimeUnit.SECONDS)) return@withContext false
        }
        if (process === child) process = null
        ShizukuChannel.stop()
        WirelessDebugging.stopWatcher()
        WirelessDebugging.stopFixedPortGuard()
        _state.value = State.Idle
        true
    }

    private fun stopEngineResources(): Boolean {
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
                // Validation/maintenance never escalates: retain the live process and its lock.
                if (!executionGate.allowForceStop()) return false
                p.destroyForcibly()
                if (!p.waitFor(3, TimeUnit.SECONDS)) return false
            }
        }
        process = null
        // 引擎停了就别再占着 mDNS 监听（NsdManager 的 listener 注册是全局资源）
        WirelessDebugging.stopWatcher()
        // 守护线程同理：引擎都停了，再去切 adbd 没有意义，还白耗电
        WirelessDebugging.stopFixedPortGuard()
        return true
    }

    /** 停止当前进程，通知宿主主动重启，并在新进程就绪后重新加载 Web GUI。 */
    fun requestRestart(reason: String) {
        Log.i(TAG, "请求界面重启引擎：$reason")
        EventLog.record("engine.restart-requested", mapOf("reason" to reason))
        if (!stopEngineResources()) {
            _state.value = State.Failed("引擎尚未确认退出，不能重新启动")
            return
        }
        _state.value = State.RestartRequested(reason)
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
        // 取退出码和写现场时可能已经主动停止或替换了进程。
        if (!userStopped && process === proc) _state.value = State.Dead(detail)
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

        @Volatile
        private var instance: EngineManager? = null

        private var commandMailboxes: EngineCommandMailboxes? = null
        private var builtInWebEngine = false
        private var launchAdditions: EngineLaunchAdditions? = null
        private var executionGate: EngineExecutionGate = EngineExecutionGate { true }

        @Synchronized
        fun initialize(
            mailboxes: EngineCommandMailboxes,
            builtInWebEngine: Boolean,
            additions: EngineLaunchAdditions? = null,
            gate: EngineExecutionGate = EngineExecutionGate { true },
        ) {
            check(commandMailboxes == null && instance == null) { "EngineManager already initialized" }
            commandMailboxes = mailboxes
            this.builtInWebEngine = builtInWebEngine
            launchAdditions = additions
            executionGate = gate
        }

        fun get(context: Context): EngineManager =
            instance ?: synchronized(this) {
                instance ?: EngineManager(
                    context.applicationContext,
                    checkNotNull(commandMailboxes) { "EngineManager.initialize must run in Application.onCreate" },
                    builtInWebEngine,
                    launchAdditions,
                ).also { instance = it }
            }
    }
}
