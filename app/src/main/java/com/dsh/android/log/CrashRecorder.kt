package com.dsh.android.log

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import com.dsh.android.DshApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 崩溃现场捕获 —— 四类「进程非正常消失」各留一份带上下文的现场。
 *
 * ## 为什么必须自己抓
 *
 * 本 App 的进程**很容易非正常消失**：内置引擎吃内存（node + 一个 WebView/GV 内核），
 * 国产 ROM 又会主动冻结/杀后台。而用户报上来的永远是同一句话：「它自己退了」。
 * 没有现场文件，那句话之外什么都查不了。
 *
 * ## 分别抓，因为「谁死了」决定了「怎么查」
 *
 * 现场文件统一命名 `yyyyMMdd-HHmmss-<kind>.txt`，`<kind>` 的**全部取值**如下
 * （这是权威清单，对着 [writeScene] 的每个调用点逐个抄的）：
 *
 * | 类型 | 捕获点 | `<kind>` | 等级 | 硬崩溃？ |
 * |---|---|---|---|---|
 * | 未捕获异常 | `Thread.setDefaultUncaughtExceptionHandler` | `uncaught-exception` | E | 是 |
 * | 渲染进程崩溃 | `WebHostCallbacks.onRendererGone`（`didCrash = true`） | `renderer-crash` | E | 是 |
 * | 渲染进程被回收 | 同上（`didCrash = false`） | `renderer-reclaimed` | W | 否 |
 * | 引擎进程意外退出 | `EngineManager` 的 stdout EOF | `engine-exit` | W | 否 |
 * | 引擎进程被我们停掉 | 同上（`expected = true`） | `engine-exit-expected` | I | 否 |
 * | 主线程卡死 | 自建 watchdog | `main-thread-stall` | E | 是 |
 * | 整个进程被冻结 | 自建 watchdog（超过上界） | `process-freeze` | W | 否 |
 * | 引擎起不来 | `EngineManager` 启动超时 | `engine-start-failed` | E | 否 |
 *
 * ⚠️ **这张表原先写的四个文件名（`<ts>-uncaught.txt` / `-renderer.txt` /
 *   `-engine-exit.txt` / `-anr.txt`）一个都不存在**（2026-10-14 自检发现）——
 *   它们既不是 [writeScene] 产出的名字，也不在 `crash/` 目录里出现过。照着它去
 *   `crash/` 找「ANR 现场」的分析者会认定「这台机器没有过 ANR」，而真机
 *   `main-thread-stall` 现场是指纹之外另有其人。这正是本项目记录的第 15 次
 *   同族缺陷：**文档描述的总体与实际的总体不是同一个**。
 *   写文件名的表必须对着调用点抄，不能凭「应该叫什么」推。
 *
 * ⚠️ **不能合并成一种**：WebView 渲染进程崩溃时**本进程还活着**，能继续跑、
 * 能弹提示、能重载（`MainActivity` 已有这套恢复逻辑）。把它记成「App 崩溃」会
 * 让分析者去查一个根本不存在的进程崩溃。
 *
 * ## 每份现场固定包含四段（缺一段都会让排查卡住）
 *
 * 1. **元信息** —— 时间、类型、`bootId`、设备、App 版本、线程名
 * 2. **异常/原因** —— 有堆栈就写堆栈
 * 3. **最后 [LogStore.CRASH_TAIL_LINES] 行日志** —— ⚠️ 这是**最关键的一段**：
 *    文件日志可能因异步缓冲丢了最后几条，而内存环形缓冲正好补上。真机上
 *    「崩之前最后做的那件事」永远比堆栈本身更有信息量
 * 4. **正在跑的阶段** —— 从 [BootState] / [EventLog] 取，回答「崩在哪一步」
 *
 * ## 三条必须守住的实现约束
 *
 * ① **`tryLock` 而不是 `lock`。** 崩溃可能发生在**正持有日志锁**的线程上
 *    （比如就在 `LogStore.writeApp` 里抛了异常）。硬等锁会死锁 —— 而崩溃处理器
 *    死锁的后果是把「闪退」变成「永久卡死」，**比崩溃更糟**。拿不到锁就只写
 *    现场文件（那条路径不碰 `app.log` 的锁）。
 * ② **写现场用独立文件、独立句柄。** 不复用 `app.log` 的 writer —— 它可能正被
 *    死掉的线程持有。
 * ③ **处理完必须把异常交回默认处理器。** 不交回的话进程会「吞掉」崩溃并卡在
 *    半死状态（ANR 而不是退出），用户体验从「闪退」变成「卡住」——更糟。
 */
object CrashRecorder {

    private const val TAG = "DSH"

    private val fmt = SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 文件名时间戳。不能含冒号（文件系统不允许）。 */
    private val fileFmt = SafeDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 现场文件名：`yyyyMMdd-HHmmss-<kind>.txt`（见 [writeScene] 的命名）。 */
    private val sceneNamePattern = Regex("^\\d{8}-\\d{6}-(.+)\\.txt$")

    /**
     * 拿日志锁的最长等待。见类注释约束①。
     *
     * 200ms 的取值依据：写一条日志是微秒级，200ms 足够任何正常持有的锁释放；
     * 而崩溃路径总共只有几秒预算（之后进程就没了），不能把预算耗在等锁上。
     */
    private const val LOCK_WAIT_MS = 200L

    /** 主线程 watchdog 的 ping 间隔与判定阈值。 */
    private const val WATCHDOG_PING_MS = 5_000L
    private const val WATCHDOG_STALL_MS = 15_000L

    /**
     * 抓主线程栈时最多等多久（ms）。见 [mainThreadStack]。
     *
     * 1 秒的依据：主线程正常时抓栈是**微秒级**（走到一次 safepoint 就拍完），
     * 1 秒是三个数量级的余量；而卡在 native 里的主线程**永远**等不到，多等只是
     * 让现场文件晚落盘 —— 崩溃路径总共只有几秒预算，不能再多。
     */
    private const val MAIN_STACK_WAIT_MS = 1_000L

    /**
     * 「主线程卡死」与「整个进程被冻结」的分界。
     *
     * ⚠️ **这不只是措辞问题，是本判据能不能信的问题**（真机 44 份现场实测过）。
     *
     * ## 为什么必须有这条线
     *
     * watchdog 是**每 [WATCHDOG_PING_MS] 醒一次**再看 `lastPing` 的。所以：
     *
     * - 若真的是**主线程**卡住、watchdog 线程照常跑 ⇒ `gap` 只可能落在
     *   `(WATCHDOG_STALL_MS, WATCHDOG_STALL_MS + WATCHDOG_PING_MS]`
     *   = **15～20 秒**这个窗口里。它必然 >15 秒才发现，又必然 ≤20 秒，
     *   因为下一次醒来（+5 秒）就会记录。**这是硬上界，不是经验值。**
     * - 若 `gap` **远超** 20 秒 ⇒ 连 watchdog 线程自己都没在跑 ⇒ 那是**整个进程
     *   被系统冻住**（后台/锁屏被冻结、被 swap 出去），主线程根本没「卡」。
     *
     * 实测 44 份现场里 36 份的 `gap` 是 60 秒～888 秒（最大 14.8 分钟），
     * 而每一份的窗口内**整个进程一条日志都没有** —— 与「整进程冻结」完全吻合，
     * 与「主线程卡死」完全矛盾（真卡死时 watchdog 线程还会继续写 `app.log`）。
     *
     * 判错的代价是双向的：把冻结记成卡死 ⇒ 现场里一半是假警报，真问题被淹掉；
     * 而按「冻结」记下来正好解释了用户口中的「它自己卡住了」。
     *
     * ⚠️ **上界就是 [WATCHDOG_STALL_MS] + [WATCHDOG_PING_MS]，不是再乘 2**
     *   （2026-10-14 对抗式审查查出）。上面那段的推理是：watchdog 每
     *   [WATCHDOG_PING_MS] 醒一次，第一次发现 `gap > WATCHDOG_STALL_MS` 时，
     *   这次醒来距上次醒来至多 [WATCHDOG_PING_MS] —— 所以 `gap` 必然 ≤
     *   `WATCHDOG_STALL_MS + WATCHDOG_PING_MS` = **20 秒**。原值 `+ PING * 2`
     *   = 25 秒，把 `(20s, 25s]` 这一段判成了 `main-thread-stall`（`level='E'`，
     *   且被 `isHardCrash()` 算作真崩溃、界面标红）—— 而按本 KDoc 自己的论证，
     *   这个区间**不可能**是主线程卡死。也就是说 KDoc 说「硬上界」，代码给了
     *   5 秒的额外余量，恰好把要避免的「假警报标红」放回来了。
     *
     *   影响窗口很窄（只有 `(20s, 25s]`）：真机 44 份现场里 36 份的 `gap` 是
     *   60~888 秒，那些仍走 freeze 分支，不受影响。
     */
    private const val WATCHDOG_FREEZE_MS = WATCHDOG_STALL_MS + WATCHDOG_PING_MS

    /**
     * 系统 Web 内核抛出的未捕获异常，在 `reason` 前加的归因前缀。
     *
     * 见 [isWebViewKernelCrash]。写在 reason 里而不是另立 kind，是为了不动
     * `isHardCrash` / `crash-scenes` 那套判据（内核崩了，界面确实没了，仍算硬崩溃）。
     */
    private const val WEBVIEW_KERNEL_NOTE = "【系统 Web 内核崩溃，非本 App】"

    @Volatile
    private var crashDir: File? = null

    /** 未捕获异常处理器是否已装（幂等判据）。 */
    @Volatile
    private var handlerInstalled = false

    /** watchdog 是否已启动（幂等判据）。 */
    @Volatile
    private var watchdogStarted = false

    /** 主线程最后一次回 ping 的时间。由主线程更新，watchdog 读。 */
    @Volatile
    private var lastPing = 0L

    /** 本次启动是否已经写过 ANR 现场 —— 卡死是持续状态，不要每 15 秒写一个文件。 */
    @Volatile
    private var anrRecordedThisBoot = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pingRunnable = Runnable { lastPing = System.currentTimeMillis() }

    // ── 安装 ──────────────────────────────────────────────────────────────────

    /**
     * 装上未捕获异常处理器与主线程 watchdog。
     *
     * ⚠️ 必须在 `attachBaseContext()` 里调用，**越早越好** —— 晚于第一次崩溃
     * 就等于没装。
     */
    fun install(logDir: File) {
        crashDir = File(logDir, "crash").apply { runCatching { mkdirs() } }
        installUncaughtHandler()
        startWatchdog()
    }

    private fun installUncaughtHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val stack = AndroidLog.getStackTraceString(err)
                // ⚠️ 先分清「这个异常是谁抛的」。见 [isWebViewKernelCrash] ——
                //   不标出来的后果是把**系统内核的 bug**当成我们自己的 bug 去查。
                val reason = if (isWebViewKernelCrash(err, stack)) {
                    "$WEBVIEW_KERNEL_NOTE${err.javaClass.name}: ${err.message}"
                } else {
                    "${err.javaClass.name}: ${err.message}"
                }
                // ① 先落盘（同步）—— 顺序很重要：先写现场文件，再写 app.log。
                //    因为 app.log 那条可能因为拿不到锁而跳过，现场文件是主力。
                writeScene(
                    kind = "uncaught-exception",
                    reason = reason,
                    stack = stack,
                    threadName = thread.name,
                )
                // ② 再把堆栈喂给 app.log（拿得到锁才有）。这条让「崩溃」出现在
                //    正常的日志流里，用户按时间顺序翻日志时不会漏掉它。
                withLogLock {
                    // ⚠️ 只取 reason 的**首行**：完整栈紧接着由下一行写进 app.log，而
                    //   `UncaughtExceptionException` 的 message 里**已经含**整段 native 栈
                    //   （chromium 构造时就是 `"Native stack trace:" + 换行 + nativeStackTrace`），
                    //   内联进这一行会让同一条崩溃在 app.log 里出现两遍、且把这一行撑成几百行。
                    val head = reason.substringBefore('\n').trim()
                    LogStore.app('E', "Crash", "未捕获异常 on ${thread.name}: $head")
                    LogStore.app('E', "Crash", stack)
                }
                // ③ 刷盘。异步缓冲里还压着引擎/网页的最后几行。
                LogStore.flushAsync()
                EventLog.flushAndClose()
                LogStore.flushAndClose()
            } catch (_: Throwable) {
                // 崩溃处理器自己再崩就彻底没救了 —— 静默，交给默认处理器。
            }
            // 约束③：必须交回，否则从「闪退」变「卡住」。
            prev?.uncaughtException(thread, err)
        }
    }

    /**
     * 判断这个未捕获异常是不是**系统 Web 内核自己**抛的（不是本 App 的 bug）。
     *
     * ## 为什么必须区分
     *
     * 真机证据（62 个诊断包，3 份现场）：`JniAndroid$UncaughtExceptionException` 冒到
     * 默认处理器，被记成 `uncaught-exception` ⇒ `isHardCrash` 判真 ⇒ 诊断包的
     * `crash-scenes` 报 **fail**。可它的 native 栈 **14 帧全部**落在
     * `/my_product/app/TrichromeLibrary64CN/TrichromeLibrary64CN.apk`（OPPO 的系统内核），
     * Java 侧入口是 `ImeAdapterImpl.updateState` → `JniAndroid.handleException` ——
     * 也就是**输入法状态更新时内核自己 native 崩了**，与本工程一行代码无关。
     *
     * 后果是分析方向被带偏：`crash-scenes=fail` 会让人去查我们的 UI/输入处理，
     * 而真凶是 ROM 自带内核。所以**不改判据**（它确实崩了，如实记），
     * 只在 `reason` 前加一句归因，让读日志的人一眼看到「这不是我们的栈」。
     *
     * ## 判据为什么用两个特征
     *
     * - `JniAndroid$UncaughtExceptionException`：chromium 把 native 崩溃转成 Java
     *   异常的**唯一**载体类。
     * - `TrichromeLibrary` / `libmonochrome`：native 栈里的**内核库名** —— 这才是
     *   「崩在内核里」的直接证据。
     *
     * 两个都不出现才判为本 App 的异常 —— 宁可漏判也不能把自家崩溃误标成内核的。
     *
     * ⚠️ **不要再把 `stack.contains("JniAndroid.handleException")` 加回判据里。**
     *   曾经加过，它是**恒真**的：`UncaughtExceptionException` 正是由
     *   `JniAndroid.handleException` 构造的（chromium 源码里
     *   `new UncaughtExceptionException(nativeStackTrace, throwable)` 就在该方法体内），
     *   所以它必然出现在自己的栈里。恒真的条件会把整个判据短路成「只看类名」，
     *   而 chromium 的 `CheckException(env)` 是 **jni_zero 的通用 JNI 边界检查** ——
     *   native 调用**任何** Java 方法返回时若有未决异常都会触发，不限于内核自己崩。
     *   本工程这些回调都是被 WebView native 经 JNI 调用的（`onPageFinished`、
     *   `onStatus`、`onUiReady`、`bubbleApply` …），任一处抛未捕获异常就会被
     *   **误标成「系统 Web 内核崩溃，非本 App」** —— 与本节意图正好相反。
     *   要加特征就往「native 栈里出现内核库名」这个方向加。
     *
     * ⚠️ 与 [isHardCrash] 的关系：这里**只改文案不改等级**。内核崩了照样是
     * `E` 级硬崩溃现场（对用户而言界面确实没了），只是原因归属不同。
     */
    private fun isWebViewKernelCrash(err: Throwable, stack: String): Boolean {
        val name = err.javaClass.name
        if (!name.contains("JniAndroid")) return false
        // ⚠️ 只认 native 栈里的**内核库名**。见上面那条 ⚠️：类名单独不足以定性。
        return stack.contains("TrichromeLibrary") || stack.contains("libmonochrome")
    }

    // ── 四类现场的写入入口 ────────────────────────────────────────────────────
    /**
     * 记录**渲染进程崩溃**（WebView 的 `onRenderProcessGone` / GeckoView 的 `onCrash`）。
     *
     * ⚠️ **本进程还活着**，所以这不是「App 崩溃」。区分 `didCrash` 很重要：
     * `false` 表示被系统回收（低内存时的正常行为），不该当成异常。
     */
    fun recordRendererGone(didCrash: Boolean, detail: String) {
        writeScene(
            kind = if (didCrash) "renderer-crash" else "renderer-reclaimed",
            reason = detail,
            stack = null,
            threadName = "ui",
            level = if (didCrash) 'E' else 'W',
        )
    }

    /** 记录**引擎进程退出**。`expected` = 是不是我们主动停的。 */
    /**
     * 记录引擎进程退出。
     *
     * ## ⚠️ `engineTail` 这个参数是 2026-10-27 真机审查补上的
     *
     * 在此之前这个方法**不接收引擎自己的输出**，于是现场文件里只有 App 侧日志
     * （`--- last 200 log lines ---` 来自 `LogStore.tail()`，那是我们自己的 app.log），
     * **引擎进程说过的话一个字都没有**。
     *
     * 真机证据（HUAWEI CDY-TN90，`20261026-183957-engine-exit.txt`）：
     * ```
     * reason: exitCode=null 进程已退出
     * --- last 200 log lines ---   ← 全是 mdns / 固定端口守护的 App 日志
     * --- recent events ---        ← engine.exit code=134 …
     * ```
     * 只有 `recent events` 里那一行 `engine.exit code=134` 透露了真相 ——
     * **134 = 128 + 6 = SIGABRT**，在 Node 里几乎总是
     * `FATAL ERROR: Reached heap limit` / `Assertion failed` / 未捕获异常。
     * 而 V8 打这行 FATAL 时是往 **stdout/stderr** 写的，正好落在
     * `EngineManager.logLines` 那个 400 行环形缓冲里 —— 但没人把它写进现场。
     *
     * 结果：用户每天撞 2–3 次这个崩溃（真机上连续 6 天都是这个节奏），
     * 每次自动重启要 27 秒，而**每次的根因证据都随进程消失**。
     *
     * @param engineTail 引擎 stdout/stderr 的尾部行；由调用方从
     *   `EngineManager.logLines` 快照而来。传空列表等价于修复前的行为。
     */
    fun recordEngineExit(
        exitCode: Int?,
        expected: Boolean,
        detail: String,
        engineTail: List<String> = emptyList(),
    ) {
        writeScene(
            kind = if (expected) "engine-exit-expected" else "engine-exit",
            reason = "exitCode=$exitCode $detail",
            stack = null,
            threadName = "engine-reader",
            level = if (expected) 'I' else 'W',
            engineTail = engineTail,
        )
    }

    /**
     * 记录**主线程卡死**（ANR 形态）。
     *
     * ⚠️ 拿不到系统 ANR 的完整堆栈（`ApplicationExitInfo` 需 API 30，本工程
     * `targetSdk = 28`），所以这里记的是「主线程超过 N 秒没响应」这个**事实**，
     * 外加**自建 watchdog 抓的一次快照**（主线程 Java 栈 + Looper 消息队列，
     * 见 [mainThreadStack]）。不要伪造成「系统 ANR 堆栈」—— 那是编造证据；
     * 快照里每一段都如实标注了它是谁抓的、以及抓不到时意味着什么。
     *
     * ⚠️ **超过 [WATCHDOG_FREEZE_MS] 的不要走这里**，那是整进程被冻结，
     * 见 [recordProcessFreeze] 的注释。分错类会让「真卡死」被假警报淹掉。
     */
    fun recordMainThreadStall(stalledMs: Long) {
        if (stalledMs > WATCHDOG_FREEZE_MS) {
            recordProcessFreeze(stalledMs)
            return
        }
        writeScene(
            kind = "main-thread-stall",
            reason = "主线程超过 ${stalledMs}ms 未响应（自建 watchdog 判定，非系统 ANR）",
            stack = mainThreadStack(),
            threadName = "main",
            level = 'E',
        )
    }

    /**
     * 抓**主线程此刻在哪**——卡死现场里唯一能定位的那段。
     *
     * ## 为什么必须补这一段
     *
     * 原先 `recordMainThreadStall` 传的是 `stack = null`，于是「主线程卡死」这类
     * 现场**只有**元信息 + 最后 200 行日志 + 时间线。而这三段在卡死场景下几乎
     * 没有信息量：卡死期间主线程**本来就不打日志**，所以「最后 200 行」停在卡住
     * 之前的那一刻，只能证明「它是在这里之后卡住的」，说不出卡在哪。
     *
     * 真机实测（服务器侧真实用户包，`debuggable:false`，v21）：4 次独立的
     * `main-thread-stall`（`20261014-225014` / `-225538` / `20261015-010934` /
     * `-012538`），`gap` 分别是 15070 / 19248 / 19622ms —— **全部落在
     * `(WATCHDOG_STALL_MS, WATCHDOG_FREEZE_MS]` 这个硬上界窗口里**，即
     * watchdog 线程自己照常每 5 秒醒来（否则会走 `process-freeze` 分支）。
     * 也就是说**主线程真的卡了 15~19 秒**，可 4 份现场一份堆栈都没有，
     * 定位只能靠猜。补这一段就是为了让下一次有据可查。
     *
     * ## 为什么是这两样，以及它们的诚实边界
     *
     * ① **主线程 Java 栈**：`Thread.getStackTrace()` 对**另一个**线程调用时，
     *    VM 需要等目标线程走到 safepoint 才能拍栈。主线程若卡在 native 里
     *    （本 App 大量回调都是 WebView/GV 经 JNI 进来的），它可能**永远**
     *    到不了 safepoint ⇒ 抓栈调用自己会无限阻塞。所以这里**放到独立线程里
     *    并限时 [MAIN_STACK_WAIT_MS]**，超时就如实写「超时」——宁可没有，
     *    也不能让 watchdog 线程陪着一起卡死（watchdog 死了就再也发现不了卡死）。
     * ② **主线程 Looper 的消息队列**：`Looper.dump()` 就是系统 ANR trace 用的
     *    同一套机制，它会打印出队列里**正在分派**的那条消息
     *    （`>>>>> Dispatching to Handler (…) {…}`）。它不依赖主线程配合，
     *    所以即便 ① 超时，这一段通常仍能回答「卡在哪条消息上」。
     *
     *    ⚠️ **两处坑，都踩过**：
     *    - `Looper.dump(Printer, String)` 在 SDK 里是 **`@hide`**，编译期直接
     *      引用会 `Unresolved reference`。必须走反射。
     *    - 它内部要拿 `MessageQueue` 的**监视器锁**。主线程若正好卡在那把锁上
     *      （本 App 主线程大量 `post`/`removeCallbacks` 都碰它），反射调用会
     *      把 watchdog 线程一起卡死 —— 而 watchdog 死了就再也发现不了卡死。
     *      所以这一段**同样放到独立线程里并限时**，与 ① 一个规格。
     *
     * ⚠️ **这不是系统 ANR 堆栈，不要伪造成那个样子**。它是「自建 watchdog 抓的
     *   一次快照」，与 `reason` 里那句「非系统 ANR」是同一个诚实口径。
     * ⚠️ 栈里的字符串同样要过脱敏网 —— 它可能含用户路径、`?token=`。
     *   `writeScene` 已经对 `stack` 统一脱敏（见那里的注释），这里不必重复。
     */
    private fun mainThreadStack(): String {
        val sb = StringBuilder()

        // ① 主线程 Java 栈 —— 限时，见上面 ①。
        sb.append("--- main thread stack (watchdog snapshot) ---\n")
        sb.append(boundedDump("dsh-main-stack-dump") { out ->
            val t = Looper.getMainLooper().thread
            out.append("main thread state: ").append(t.state).append('\n')
            val st = t.stackTrace
            if (st.isEmpty()) {
                out.append("(主线程栈为空 —— 它多半正阻塞在 native 里，")
                    .append("或还没走到任何 Java 帧)\n")
            } else {
                st.forEach { out.append("    at ").append(it).append('\n') }
            }
        })

        // ② Looper 消息队列 —— 见上面 ②（反射 + 限时）。
        sb.append("\n--- main looper queue ---\n")
        sb.append(boundedDump("dsh-main-looper-dump") { out ->
            val dump = Looper::class.java.getMethod(
                "dump", android.util.Printer::class.java, String::class.java,
            )
            val printer = android.util.Printer { line -> out.append(line).append('\n') }
            dump.invoke(Looper.getMainLooper(), printer, "")
        })

        return sb.toString()
    }

    /**
     * 在独立线程里跑 [body]，最多等 [MAIN_STACK_WAIT_MS]；超时就返回一句**如实的
     * 结论**而不是空串。
     *
     * 抽出来是因为抓栈与抓 Looper 队列**有同一个规格**：都可能无限阻塞，都
     * 不能连累 watchdog 线程，超时的措辞也应当一致。见 [mainThreadStack]。
     *
     * ⚠️ 超时的文案必须说清「这意味着什么」——「抓栈超时」本身对读日志的人没有
     *   意义，而「主线程到不了 safepoint」把「卡在 Java 代码里」这一整类**排除**
     *   掉了，那是可用的结论。
     */
    private fun boundedDump(tag: String, body: (StringBuilder) -> Unit): String {
        val out = StringBuilder()
        val worker = Thread({
            runCatching { body(out) }
                .onFailure { out.append("($tag 失败: ${it.javaClass.simpleName}: ${it.message})\n") }
        }, tag).apply { isDaemon = true }
        worker.start()
        // ⚠️ `join` 而不是轮询：join 有 happens-before 保证，`out` 在这里读到的
        //   一定是子线程写完的内容（或者它压根没写完 —— 那种情况下面如实标注）。
        runCatching { worker.join(MAIN_STACK_WAIT_MS) }
        return if (worker.isAlive) {
            "(抓取超时 ${MAIN_STACK_WAIT_MS}ms —— 主线程到不了 safepoint，" +
                "通常意味着它阻塞在内核/native 调用或 MessageQueue 锁上)\n"
        } else {
            out.toString()
        }
    }

    /**
     * 记录**整个进程被系统冻结**（不是主线程卡死）。
     *
     * 判据见 [WATCHDOG_FREEZE_MS]：`gap` 超过 watchdog 心跳能解释的上界，
     * 就只能是 watchdog 线程自己也被挂起了 —— 即进程整体失去 CPU，
     * 而不是主线程在某个同步调用里转不出来。
     *
     * ⚠️ **等级记 `W` 而不是 `E`**，且**不算硬崩溃**（见 [isHardCrash]）：
     * 被冻结是 Android 对后台进程的**正常治理**，不是我们的 bug。把它标红会让
     * 用户看到一片「崩溃」，而真崩溃反而找不到。
     *
     * ⚠️ 但仍要记下来，因为它**解释了用户抱怨的那个现象**（「它自己卡住了、
     * 回来就白屏」）。不记的话这类投诉在日志里完全无迹可寻。
     */
    fun recordProcessFreeze(frozenMs: Long) {
        writeScene(
            kind = "process-freeze",
            reason = "进程被系统冻结约 ${frozenMs}ms（watchdog 线程自身也停跑了，故非主线程卡死；" +
                "常见于后台被冻结或整机休眠）",
            stack = null,
            threadName = "dsh-log-watchdog",
            level = 'W',
        )
        // 也进时间线：现场文件按时间戳单独命名，翻时间线时看不到「这里冻过一次」，
        // 而冻结正是「切回来白屏/要重进」这类现象的直接解释。
        runCatching { EventLog.record("process.freeze", mapOf("ms" to frozenMs.toString())) }
    }

    /**
     * 通用入口：记一次「本进程之外的问题」。
     *
     * 给 `EngineManager` 的启动前体检、资产解包失败等**不至于崩溃但需要现场**的
     * 场景用。写出来的文件与崩溃现场同构，分析方式一致。
     */
    fun recordAnomaly(kind: String, reason: String, level: Char = 'W') {
        writeScene(kind = kind, reason = reason, stack = null, threadName = Thread.currentThread().name, level = level)
    }

    // ── 现场文件的写法 ────────────────────────────────────────────────────────

    /**
     * 写一份现场文件。**这是本类唯一的写入口**，四类都走它，格式因此完全一致。
     *
     * ⚠️ 全程不能抛（调用方可能正在崩溃路径上），但**也不能无声地失败** ——
     *   见下方的 `onFailure`：原来这里是裸 `runCatching {}`，于是「现场写不进去」
     *   （磁盘满 / `crash/` 建不出来、`crashDir` 为 null）与「这里压根没崩过」
     *   在产物上**完全一样**：`crash/` 目录空着，诊断包的 `crash-scenes` 报「无现场」，
     *   分析者据此判定「本次运行正常」。而崩溃现场正是本系统唯一的排查入口。
     *   与 `DiagnosticBundle.writeCrashes` 是同一个失效模式（裸 runCatching 吞掉）。
     */
    private fun writeScene(
        kind: String,
        reason: String,
        stack: String?,
        threadName: String,
        level: Char = 'E',
        /** 引擎进程自己的 stdout/stderr 尾部；空则**不写**这一段（保持旧格式）。 */
        engineTail: List<String> = emptyList(),
    ) {
        runCatching {
            val dir = crashDir
            if (dir == null) {
                // ⚠️ 这一支过去是 `?: return@runCatching` —— 静默丢弃。它发生在
                //   `install()` 还没跑（启动极早期崩溃）或 mkdirs 失败时。
                reportSceneFailure(kind, "crashDir 未初始化（install() 尚未执行或 crash/ 建不出来）")
                return@runCatching
            }
            if (!dir.exists()) dir.mkdirs()
            val now = System.currentTimeMillis()
            val name = "${fileFmt.format(Date(now))}-$kind.txt"
            val f = File(dir, name)

            val sb = StringBuilder()
            // ⚠️ **`reason` 与 `stack` 必须过脱敏网**（2026-10-14 对抗式审查查出）。
            //   本文件是**唯一**不过 `LogStore.safeRedact` 的出口：它的内容最终由
            //   `DiagnosticBundle.writeCrashes()` **逐字节**拷进诊断包，而 `reason`
            //   直接来自异常 message（见 [install] 里 `"${err.javaClass.name}: ${err.message}"`）
            //   —— 一条含 `?token=` 或 `sk-…` 的 message 就会原样出设备。
            //
            //   同一份文件里另外两段其实**已经**是脱敏过的，容易误以为整份都安全：
            //   - `--- last 200 log lines ---` 来自 `LogStore.tail()`（写 app.log 时已抹）；
            //   - `--- recent events ---` 来自 `EventLog.readAll()`（[EventLog.record]
            //     现在已抹）。
            //   只有 `reason` 与 `stack` 是原样进来的。所以只处理这两个字段，
            //   不整份重扫 —— 崩溃路径上不做无谓的重活。
            //
            //   ⚠️ 用 `runCatching` 兜底：这里跑在崩溃路径上，脱敏失败绝不能让它抛
            //   （铁律 ①）。失败时退化为原文，与修复前行为一致 —— 即「脱敏没成功」
            //   不会比「从未尝试」更糟。
            val safeReason = runCatching { Redactor.redact(reason) }.getOrDefault(reason)
            val safeStack = stack?.let { runCatching { Redactor.redact(it) }.getOrDefault(it) }

            sb.append("time: ").append(fmt.format(Date(now))).append('\n')
            sb.append("kind: ").append(kind).append('\n')
            sb.append("bootId: ").append(BootState.bootId).append('\n')
            sb.append("uptimeMs: ").append(runCatching { BootState.uptimeMs() }.getOrDefault(0L)).append('\n')
            sb.append("device: ").append(VersionInfo.deviceSummary()).append('\n')
            sb.append("app: ").append(VersionInfo.summary()).append('\n')
            sb.append("thread: ").append(threadName).append('\n')
            // ⚠️ 换行必须折叠：本文件是 `key: value` 逐行格式，而 `reason` 里可能带整段
            //   native 栈（chromium 的 `UncaughtExceptionException` 构造时就把
            //   `"Native stack trace:" + lineSeparator + nativeStackTrace` 塞进了 message）。
            //   直接内联会让 `reason:` 那一行后面跟几百行，破坏格式、也把
            //   `LogViewerActivity` 的 12 行预览挤出 `--- stack trace ---` 之后的内容。
            //   折叠成 `\n` 字面量只是**显示**处理，栈全文仍在下方的 stack 段里完整保留。
            sb.append("reason: ").append(safeReason.replace("\r\n", "\\n").replace("\n", "\\n")).append('\n')

            if (safeStack != null) {
                sb.append("\n--- stack trace ---\n").append(safeStack).append('\n')
            }

            // ⚠️ 最后 200 行：这是整个现场里信息量最大的一段。
            sb.append("\n--- last ${LogStore.CRASH_TAIL_LINES} log lines ---\n")
            runCatching {
                LogStore.tail(LogStore.CRASH_TAIL_LINES).forEach { sb.append(it).append('\n') }
            }.onFailure { sb.append("(内存日志不可读: ${it.message})\n") }

            /*
             * 引擎自己的输出（stdout/stderr）。
             *
             * ⚠️ 这一段与上面的 `--- last 200 log lines ---` **不是一回事**：
             *   那段是 App 的 app.log，这段是**引擎进程自己说的话**。
             *   引擎崩溃时唯一的根因证据就在这段里（V8 的 FATAL、Node 的
             *   uncaught exception、插件 apply() 抛的栈）—— 它们直接写到
             *   进程的 stdout/stderr，从来不会经过 `LogStore`。
             *
             * 只在非空时写：正常停止（`expected = true`）时没有可写的输出，
             * 凭空多一个空标题段只会让读的人以为"有内容但读不到"。
             */
            if (engineTail.isNotEmpty()) {
                sb.append("\n--- engine output (tail ${engineTail.size} lines) ---\n")
                engineTail.forEach { sb.append(it).append('\n') }
            }

            // 时间线尾部：回答「崩在哪一步」
            sb.append("\n--- recent events ---\n")
            // ⚠️ **不能用 `LogStore.dirOrNull()`**（2026-10-14 自检发现，同族第 20 例）。
            //   `EventLog.init(logDir)` 持的是**自己的**目录，与 `LogStore.dir` 无关；
            //   而 `LogStore.init` 失败时 `dir = null` —— 于是这一整段在那种状态下
            //   静默消失：`if (d != null)` 不成立，连下面那句
            //   「(事件时间线不可读)」都不会打（那不是异常，是空分支）。
            //   现场文件里就凭空少了一整段，而 `--- recent events ---` 这个标题
            //   还在、下面空着，读的人只会以为「这次没有事件」。
            //   本类的 `crashDir` 就是同一个 `logDir` 下的 `crash/`，用它拼回父目录
            //   最可靠（不依赖任何别处的状态）。
            runCatching {
                val d = crashDir?.parentFile ?: LogContext.filesDirOrNull()?.let { File(it, DshApplication.LOG_DIR) }
                if (d != null) {
                    // ⚠️ **用 `describeAll` 而不是逐条 `describe`**（2026-10-14 自检发现，
                    //   同族第 25 例）。`EventLog.readAll` 读的是 `events.jsonl` 全量，
                    //   它**跨运行累积**；`takeLast(20)` 一旦跨过运行边界，`[+Nms]`
                    //   就会在中途突然变小，而逐条 `describe` 不会提示这一点 ——
                    //   读的人会把多次运行当成一次。
                    //
                    //   真机实测（服务器 209 份现场）：**171 份（82%）** 跨运行边界。
                    //   最典型的是 `termux.done` 在一个现场里出现多次，看起来像
                    //   「一次运行里引导了好几遍」，实际是设备启动过好几次、每次一遍。
                    //
                    //   `describeAll` 在边界处插一行 `── 运行边界：… ──`，把这件事
                    //   写成显式事实。分段本身仍由 bootId 决定（每条事件都记着）。
                    sb.append(EventLog.describeAll(EventLog.readAll(d).takeLast(20)))
                } else {
                    sb.append("(取不到日志目录：LogContext 未安装，事件时间线不可读)\n")
                }
            }.onFailure { sb.append("(事件时间线不可读: ${it.message})\n") }

            f.writeText(sb.toString())
            // 现场文件写完后，在 app.log 里留一条索引（拿得到锁才有）。
            // ⚠️ 让用户在日志界面顺着时间翻也能看到「这里发生过什么」。
            withLogLock {
                LogStore.app(level, "Crash", "已记录现场: crash/$name —— $reason")
            }
        }.onFailure { t ->
            reportSceneFailure(kind, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 现场文件**没写成**时的唯一留痕出口。
     *
     * ⚠️ 存在的意义：`writeScene` 是全类唯一写入口，它静默失败 ⇒ 「这次没崩」与
     *   「现场丢了」不可区分。这里至少把损失说出来，并**明确写进 `app.log`**
     *   （`AndroidLog` 只在 logcat 里，离线诊断包拿不到 logcat）。
     *
     * ⚠️ 本函数自身也必须 fail-safe：它跑在崩溃路径上，再抛一次就是二次崩溃。
     *   所以整体 `runCatching`，且不用 `LogStore.app` 的锁语义 —— 用
     *   [withLogLock]（超时即跳过），拿不到锁时不阻塞、不抛。
     */
    private fun reportSceneFailure(kind: String, detail: String) {
        runCatching {
            AndroidLog.e(TAG, "现场文件写入失败（$kind）: $detail")
            withLogLock {
                LogStore.app('E', "Crash", "⚠️ 现场文件写入失败（$kind）：$detail —— 本次崩溃无现场可查")
            }
        }
    }

    /**
     * 在**有限时间内**拿到日志锁再执行 [block]；拿不到就跳过。
     *
     * 见类注释约束①。这里用 `synchronized` + 超时做不到（Kotlin 的 `synchronized`
     * 不可超时），所以借 `ReentrantLock.tryLock`。
     *
     * ⚠️ 锁对象必须与 `LogStore` 用的是**同一个** —— 所以 `LogStore` 暴露
     * [LogStore.withLockOrSkip]。本方法只是它的语义化封装，便于崩溃路径读起来
     * 意图明确。
     */
    private fun withLogLock(block: () -> Unit) {
        runCatching { LogStore.withLockOrSkip(LOCK_WAIT_MS, block) }
    }

    // ── 主线程 watchdog ───────────────────────────────────────────────────────

    /**
     * 主线程 watchdog。
     *
     * ## 为什么需要（本项目特有）
     *
     * 本 App 的主线程要干不少同步活：WebView 的 `evaluateJavascript` 回包、
     * 引擎状态回调、`FloatingBubble` 的窗口操作、SharedPreferences 读写。
     * 任何一处卡住，界面就整个冻住 —— 而用户描述成「卡死不动」时，我们手上
     * 什么都没有（logcat 里没有、文件里也没有）。
     *
     * ## 判定方式与它的诚实边界
     *
     * 每 [WATCHDOG_PING_MS] 让主线程回一个 ping；超过 [WATCHDOG_STALL_MS] 没回
     * 就判定卡死并写现场。
     *
     * ⚠️ **这是「主线程没响应」，不是系统的 ANR**。系统的 ANR 有完整堆栈
     * （在 `/data/anr/traces.txt`，需要 root 或 `READ_LOGS`），本工程 `targetSdk=28`
     * 拿不到。所以现场里如实写成「自建 watchdog 判定，非系统 ANR」——
     * **不伪造证据**。分析者据此知道「这里有个卡死，但堆栈要另想办法」。
     *
     * ⚠️ 一次启动只记一次：卡死是**持续状态**，不记的话每 15 秒产出一个文件，
     * 一分钟就把 `crash/` 配额撑满、把真正的崩溃现场挤掉。
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        lastPing = System.currentTimeMillis()

        val t = Thread({
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_PING_MS)
                    // 让主线程回 ping。post 成功不代表它跑了 —— 那正是我们要测的。
                    mainHandler.post(pingRunnable)
                    val gap = System.currentTimeMillis() - lastPing
                    if (gap > WATCHDOG_STALL_MS && !anrRecordedThisBoot) {
                        anrRecordedThisBoot = true
                        recordMainThreadStall(gap)
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // watchdog 自己绝不能死 —— 死了就再也发现不了卡死。
                }
            }
        }, "dsh-log-watchdog").apply { isDaemon = true }
        t.start()
    }

    /**
     * 等主线程回一次 ping。**测试与自检用**，业务路径不调。
     *
     * 返回 true = 主线程在 [timeoutMs] 内响应了。
     */
    fun pingMainThread(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        mainHandler.post { latch.countDown() }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
    }

    // ── 读取 ──────────────────────────────────────────────────────────────────

    /**
     * 列出全部崩溃现场，**新的在前**。
     *
     * 给日志界面与诊断包用。返回的是文件对象，调用方自己读内容 ——
     * 一次崩溃现场可能上百 KB，全读进内存没必要。
     */
    fun listScenes(): List<File> {
        val dir = crashDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 崩溃现场数量（界面摘要用）。 */
    fun sceneCount(): Int = listScenes().size

    /**
     * 判断某个现场文件是不是「真崩溃」（界面要标红的那几类）。
     *
     * 判据是**三个 kind**：`uncaught-exception`、`renderer-crash`、`main-thread-stall`
     * —— 都用 [kindOf] 会认的名字（这里用 `contains` 而不是相等，因为文件名前面
     * 还有时间戳）。
     *
     * ⚠️ **KDoc 原先写的是「有堆栈的那两类」，那是错的**（2026-10-14 自检发现）：
     *   它描述的是一个**不存在的总体**。三类里只有 `uncaught-exception` 带
     *   **真正的异常栈** —— [recordRendererGone] 把 `stack` 显式传 `null`，
     *   而 [recordMainThreadStall] 传的是 [mainThreadStack] 抓的**快照**
     *   （`--- main thread stack (watchdog snapshot) ---` + Looper 队列），
     *   它不是异常栈、也没有「谁抛了什么」。
     *   照「有堆栈」去理解，分析者会以为 `renderer-crash` 里有内核栈可读，翻遍
     *   现场文件找不到，进而怀疑现场写坏了。实测 `20261014-054414-renderer-crash.txt`
     *   （13,031 字节）确实**没有** `--- stack trace ---` 段。
     *   更要紧的是：**判据是 kind，不是有没有堆栈** —— 有没有堆栈是记录能力问题，
     *   是不是「真崩溃」是分类问题，两者独立。写 KDoc 时把实现里那个 `||` 表达式
     *   如实描述出来即可，不要替它编一条更好懂的理由。
     *
     * 界面据此决定标红还是标黄 —— 渲染进程被回收、引擎（正常/意外）退出、
     * 进程被冻结、引擎起不来都不该是红色。
     *
     * ⚠️ `process-freeze` **刻意不在此列**：进程被系统冻结是后台治理的正常结果，
     * 不是本 App 的崩溃。它由 watchdog 记下来是为了解释「回来就白屏」这类现象，
     * 但标红会把真崩溃的比例冲淡（实测 44 份里 36 份是冻结，标红后满屏红）。
     *
     * ⚠️ `engine-start-failed` **也刻意不在此列**：它确实是本 App 最严重的故障
     * （界面直接不可用，见 [com.dsh.android.EngineManager]），但它是**启动失败、
     * 没有崩溃过**。标红会让「崩溃现场」这个计数失去意义 —— 用户看到的是
     * 「有 70 份崩溃」而非「引擎反复起不来」。
     */
    fun isHardCrash(f: File): Boolean =
        f.name.contains("uncaught-exception") || f.name.contains("renderer-crash") ||
            f.name.contains("main-thread-stall")

    /**
     * 从现场文件名里取出类别（`kind`），取不到就返回 `"unknown"`。
     *
     * 命名契约见 [writeScene]：`yyyyMMdd-HHmmss-<kind>.txt`。**必须是这个正则**，
     * 不能用 `substringAfter('-')` —— 时间戳本身自带一个 `-`（`20261013-214701`），
     * 那样取出来的是 `214701-engine-start-failed`。
     */
    fun kindOf(f: File): String =
        sceneNamePattern.matchEntire(f.name)?.groupValues?.get(1) ?: "unknown"

    /**
     * 把一批现场按类别汇总成 `engine-start-failed×70, engine-exit×16` 这样的短语。
     *
     * 按**数量降序**排（多的在前），同数量按名字排 —— 让最占多数的那类一眼可见。
     * 只报类别与数量，不解释原因。
     *
     * ⚠️ **存在的意义是替代「举例子」**。诊断包与 `logs` 工具的文案里原来写的是
     *   「渲染进程被回收/引擎正常退出等」，而真机 22 个 warn 包里那两个例子
     *   出现 **0** 次，实际全是 `engine-start-failed`(70) 与 `engine-exit`(16) ——
     *   等于把「引擎起不来」（[com.dsh.android.EngineManager] 自己注释成
     *   「本 App 最严重的一类故障」）报成了「引擎正常退出等」。
     *   例子是猜测，类别是事实。
     */
    fun summarizeKinds(scenes: List<File>): String =
        scenes.groupingBy { kindOf(it) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key}×${it.value}" }
}
