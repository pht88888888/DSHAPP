package com.dsh.android.core
import android.app.Application
import android.content.Context
import android.os.Build
import com.dsh.android.log.BootState
import com.dsh.android.log.CrashRecorder
import com.dsh.android.log.DshLog
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogContext
import com.dsh.android.log.LogStore
import com.dsh.android.log.LogUploader
import com.dsh.android.log.RescueBoot
import com.dsh.android.termux.TermuxBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import com.dsh.android.bubble.BubbleA11yService
import com.dsh.android.engine.EngineManager
import com.dsh.android.engine.EngineService
import com.dsh.android.privilege.AdbPairingNotifier

/**
 * 应用入口：在后台把 Termux 环境准备好。
 *
 * 为什么放在 Application 而不是 Activity：bootstrap 首次要解压 23 MB（约 1–2 秒），
 * 放在 Activity 里会随转屏重建反复触发。这里用 applicationScope 起一次，进程内共享。
 *
 * ⚠️ 三条都必须调用，且 [TermuxBootstrap.ensurePathsRewritten] /
 * [TermuxBootstrap.ensureMirrorsConfigured] **每次启动都要调**：
 * `install()` 有 `.bootstrap_done` 标记，升级安装的机器不会再进，新增的修复对老装机
 * 永远不生效。这是 kit 的 README §3.4 特意记下的坑。
 */
class DshApplication : Application() {

    /**
     * ⚠️ **日志系统必须在这里装，不能等到 `onCreate`。**
     *
     * `attachBaseContext` 比 `onCreate` 早一整个阶段，而本 App 的故障有相当一部分
     * 发生在启动早期（Termux 环境损坏、资产缺失、插件把启动路径搞崩）。
     * 装在 `onCreate` 里会漏掉这些 —— 而它们恰恰是最需要现场的那一类。
     *
     * ⚠️ 这里**只做最少的事**：建目录、开文件、装崩溃处理器。任何耗时操作
     * （解包、扫描、网络）都会拖慢启动，必须放到 [bootstrapTermux] 那条异步路径上。
     *
     * 顺序也不能换：
     *   ① [LogContext]  —— 后面所有组件都要靠它拿 `filesDir`
     *   ② [LogStore]    —— 建目录、开 `app.log`（此后日志才开始落盘）
     *   ③ [DshLog]      —— 把 `DshLog` 接到 `LogStore`（此后 476 处 `DshLog.*` 才落盘）
     *   ④ [EventLog]    —— 事件时间线
     *   ⑤ [BootState]   —— 读上次退出状态（要在任何崩溃可能发生之前）
     *   ⑥ [CrashRecorder] —— 装未捕获处理器与 watchdog
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // ⚠️ 非主进程直接返回，一行日志系统都不装 —— 理由见 [isMainProcess]。
        if (!isMainProcess(base)) {
            android.util.Log.i(TAG, "非主进程启动，跳过日志系统与 Termux 引导")
            return
        }
        try {
            val ctx = base ?: return
            LogContext.install(ctx)

            val logDir = File(ctx.filesDir, LOG_DIR)
            LogStore.init(logDir)

            // ⚠️ 顺序要紧：DshLog 必须在 LogStore.init 之后接，否则接了个空出口。
            // 反过来（先接再 init）也能工作，但 init 之前的那几条会丢。
            DshLog.install { level, tag, msg -> LogStore.app(level, tag, msg) }

            EventLog.init(logDir)
            BootState.begin(logDir)
            CrashRecorder.install(logDir)
            // ⚠️ 救援启动的目录装在 `$DSH_HOME/rescue/`，而不是 logs/ ——
            //   它是**关于启动本身**的状态（哪个层级、上次为什么起不来），
            //   与 logs/ 下那堆日志文件的生命周期不同：清日志不该清掉它
            //   （清了就等于忘记"上次是救援模式起来的"）。
            //   位置必须在 BootState.begin 之后：它要读 BootState.bootId 对齐。
            RescueBoot.installUnder(File(ctx.filesDir, DSH_HOME_REL))

            LogUploader.configure(LOG_UPLOAD_ENDPOINT, LOG_UPLOAD_TOKEN, logDir)

            // 走到这里日志系统就完整可用了 —— 用 DshLog 而不是 Log，
            // 这一条必须落进文件（它是「日志系统已就绪」的唯一证据）。
            DshLog.i(TAG, "日志系统就绪：${logDir.absolutePath}（${BootState.previous().describe()}）")
            EventLog.record("DshLog.ready", mapOf("dir" to logDir.absolutePath))
        } catch (t: Throwable) {
            // ⚠️ 日志系统装不上**绝不能让 App 起不来**。退回「只有 logcat」的
            // 旧行为，与改造前一致。
            DshLog.e(TAG, "日志系统初始化失败（退回仅 logcat）", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess(this)) return
        instance = this
        DshLog.i(TAG, "DSH application created")
        EventLog.record("app.onCreate")
        // ⚠️ 必须在**任何通知发出之前**清理残留（见该方法注释）：上一次配对把进程拖到
        //   ANR 被杀后，那条常驻的「正在配对」通知会留在通知栏且划不掉，
        //   用户重新打开 App 会以为还在配对。放在 onCreate 最前面是为了抢在
        //   EngineService 起前台通知之前 —— 它们用的是不同的通知 id，互不覆盖，
        //   但都该在启动早期就确定状态。
        runCatching { AdbPairingNotifier.clearStaleOnStartup(this) }
            .onFailure { DshLog.w(TAG, "清理残留配对状态失败", it) }
        // ⚠️ 启动早期就建出配对通知渠道。本 App `targetSdk = 28`，在 Android 13+
        //   上走「旧应用」兼容路径 —— 系统只在**创建第一个通知渠道**时补弹一次
        //   通知权限框，错过就再也不问。原实现渠道要等用户点「配对」才建，
        //   于是首次打开 App 永远拿不到那个弹框，必须退出重进（见方法注释）。
        AdbPairingNotifier.ensureChannel(this)
        // 键盘哨兵（无障碍）是可选能力：用户开过就记一条"已连接"，没开就提示路径。
        // 只写日志、不弹窗 —— 没开它 App 照样能用，只是悬浮球不会避让键盘。
        runCatching { BubbleA11yService.logState(this) }
            .onFailure { DshLog.w(TAG, "检查键盘哨兵状态失败", it) }
        bootstrapTermux()
        // 崩溃后留下的待传诊断包，在这里补传（不阻塞启动，见该方法注释）。
        uploadPendingBundles()
    }

    /**
     * 补传上次崩溃留下的诊断包。
     *
     * ⚠️ **必须异步**：它要发网络请求（8MB 在移动网络下几十秒），放在启动路径上
     * 会拖慢界面出来 —— 而用户此刻正等着看 App 能不能打开。
     *
     * ⚠️ **崩溃当场传不出去**（进程马上要死，来不及建连接），所以只能落盘 +
     * 标记待传、下次启动补传。若用户崩溃后再也不打开 App，这个包就传不出去 ——
     * 这是平台限制，不是实现缺陷。所以日志界面的**手动上传入口是必需的补充**。
     *
     * ⚠️ **先补打包再补传**：崩溃当场只写了现场文件，诊断包是**在这里**打的
     *   （打包是重活，崩溃进程只有几秒预算）。见 [LogUploader.collectCrashScenes]
     *   —— 原来 `CrashRecorder` 从来没调用过 `stashForCrash()`，所以
     *   「崩溃自动上传」这条链路此前从未生效。
     */
    private fun uploadPendingBundles() {
        applicationScope.launch {
            runCatching {
                val logDir = File(filesDir, LOG_DIR)
                if (!LogUploader.configured) return@runCatching
                // ① 把上次崩溃留下的现场打成包并入队（幂等，无新现场则跳过）。
                LogUploader.collectCrashScenes(logDir)
                // ② 把 pending/ 里的包传出去。
                val n = LogUploader.pendingCount(logDir)
                if (n == 0) return@runCatching
                DshLog.i(TAG, "发现 $n 份待上传的诊断包，开始补传")
                LogUploader.retryPending(logDir) { f, ok, msg ->
                    DshLog.i(TAG, "待传诊断包 ${f.name}: ${if (ok) "成功" else "失败"} $msg")
                }
            }.onFailure { DshLog.w(TAG, "补传诊断包失败", it) }
        }
    }

    private fun bootstrapTermux() {
        applicationScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val bootstrap = TermuxBootstrap(this@DshApplication)
                if (!bootstrap.isReady) {
                    bootstrap.install()
                }
                bootstrap.ensurePathsRewritten()
                bootstrap.ensureMirrorsConfigured()
                // Node 运行时单独一层：它在 bootstrap 之后铺入，标记也独立
                val ok = bootstrap.installNodeRuntime()
                bootstrap.ensureNodeRuntimePatched()
                DshLog.i(
                    TAG,
                    "Termux 引导完成（${System.currentTimeMillis() - t0} ms），" +
                        "node=${if (ok) bootstrap.nodeBinary.absolutePath else "不可用"}",
                )
                EventLog.record("termux.done", mapOf("ms" to (System.currentTimeMillis() - t0).toString()))
            } catch (t: Throwable) {
                // 引导失败不在这里崩溃：EngineManager 启动时会再检查一次并给出
                // 带具体路径的报错，UI 上能看到，比进程直接挂掉好定位。
                DshLog.e(TAG, "Termux 引导失败", t)
                EventLog.record("termux.failed", mapOf("err" to (t.message ?: t.javaClass.simpleName)))
            }
        }
    }

    companion object {
        const val TAG = "DSH"

        /**
         * 本进程是不是**主进程**。
         *
         * ## 🔴 为什么必须判：gecko 版有 39 个子进程，全都跑过 Application
         *
         * `geckoview-arm64-v8a` 的 AAR 声明了 **39 个 `android:process` 子进程**
         * （`:tab0`…`:tab39`、`:gpu`、`:media`、`:socket`、`:crashhelper` …）。
         * 每个子进程都会实例化本类，于是 `attachBaseContext` 与 `onCreate` 各跑一遍 ——
         * 也就是说**每个子进程都会**：
         *
         * - 初始化日志系统（多个进程**同时写同一个 `app.log`**，行内容交错）；
         * - 跑 `BootState.begin()`（把 `clean=false` 写进去 → 别的进程读到就以为
         *   「上次没正常退出」，`boot.previous-unclean` 被顶到 8 次，而实际只崩过 1 次）；
         * - 跑 `bootstrapTermux()`（**并发解包同一个 prefix**，见
         *   [com.dsh.android.termux.TermuxBootstrap.install] 的注释）。
         *
         * 真机证据：`20261014-010259-8fbc8515`（gecko）里 `app.onCreate` 出现 **15 次**、
         * `boot.previous-unclean` 在 4 秒内从 1 涨到 4，并伴随
         * `dpkg.cfg: open failed: ENOENT`。判别性很强 —— **system flavor 的 50 个包
         * 没有一次**这种放大（它没有子进程）。
         *
         * ## 为什么不能用 `Application.getProcessName()` 一个 API 了事
         *
         * 它是 **API 28** 才有的，而本 App 的 `minSdk = 26`。API 26/27 退回读
         * `/proc/self/cmdline`（内核给的 `argv[0]` 就是进程名）。
         *
         * ⚠️ **读不到时按主进程处理**（返回 true）：宁可多跑一遍，也不要因为一个
         * 探测失败让真主进程跳过引导 —— 那会让 App 完全起不来。
         */
        private fun isMainProcess(base: Context?): Boolean {
            val ctx = base ?: return false
            val expected = ctx.packageName
            val actual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                runCatching {
                    File("/proc/self/cmdline").readText().substringBefore('\u0000')
                }.getOrNull()
            }
            return actual == null || actual == expected
        }

        /** 日志目录名（`filesDir/logs`）。 */
        const val LOG_DIR = "logs"

        /**
         * `$DSH_HOME` 相对 `filesDir` 的路径（即 `filesDir/home/.dsh`）。
         *
         * ⚠️ **单一真相源**：`attachBaseContext()` 里要装救援启动的记录目录
         * （[com.dsh.android.log.RescueBoot]），而那时 `EngineManager` 实例还不存在
         * （它的 `dshHome` 是实例属性），所以这个路径必须有一份**不依赖实例**的定义。
         *
         * 定义放在这里、并由 `EngineManager.dshHome` 引用同一常量 —— 两边各拼一次
         * `File(filesDir, "home/.dsh")` 的话，改了一处漏一处就是「两个进程看不同目录」，
         * 而症状只是「工具超时」/「状态读不到」，没有任何线索指向路径。
         * 本项目已经吃过这个形态的亏（见 `EngineManager.dshHomeDir` 的 KDoc）。
         */
        const val DSH_HOME_REL = "home/.dsh"

        /**
         * 日志上传服务器端点。
         *
         * 部署在 `39.98.163.161:56789`（沙箱唯一的对外隧道），由 `dsh-mux` 按路径
         * 分流到沙箱内的日志接收服务（127.0.0.1:18900）。
         *
         * ⚠️ 未配置（null）时上传按钮会明确提示「尚未配置日志服务器」，
         * 而不是静默失败（见 [LogUploader] 的类注释）。
         *
         * ⚠️ 只能写 `val` 不能写 `const val` —— Kotlin 的 const 只允许非空的基本类型
         * 与 String，可空类型直接编译不过。
         */
        val LOG_UPLOAD_ENDPOINT: String? = "http://39.98.163.161:56789/logs/upload"

        /**
         * 上传鉴权令牌（Bearer）。服务端校验同一份 token，
         * 存放于服务器 `/workspace/dsh-logs/token`。
         *
         * ⚠️ 这是**写入 APK 的共享密钥**，只能防「路人往接口里灌垃圾」，
         * 不能防逆向。真要换：改服务端 `token` 文件后重启 logserver，
         * 再同步改这里重新出包。同理不能写成 `const val`。
         */
        val LOG_UPLOAD_TOKEN: String? = "BmN46nQ0nl2neoRYC6ajguYlestQnquEEODw1lCD7wE"

        lateinit var instance: DshApplication
            private set

        /**
         * 进程级协程作用域：用于不随任何界面生命周期结束的后台任务。
         * 用 SupervisorJob 让一个子任务失败不影响其它任务。
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
