package com.dsh.android.log

import android.util.Log as AndroidLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

/**
 * 分层日志存储 —— 整个日志系统的地基。
 *
 * ## 它解决的问题
 *
 * 改造前全工程的日志**只写 logcat**：进程一死（崩溃、被 LMK 回收、被国产 ROM 冻结、
 * 断电）现场全部消失，而用户能提供的只剩一句转述。诊断包、上传、AI 排查全都无从谈起。
 *
 * ## 三个文件各管一段，不是一个文件切三段
 *
 * | 文件 | 来源 | 写入 | 为什么 |
 * |---|---|---|---|
 * | `app.log` | 476 处 `DshLog.*` | **同步 + 每行 flush** | 崩溃处理器要在进程死掉前拿到完整现场；异步队列在崩溃路径上会丢最后几条，而那几条通常正是最关键的。引擎每轮只打几十行，代价可接受 |
 * | `engine.log` | 引擎 stdout+stderr | **异步缓冲** | 引擎会**高频**吐日志（每 token、每次工具调用），同步 flush 会拖慢回合。可容忍丢最后几条 |
 * | `web.log` | 网页 console | **异步 + 限流** | 页面脚本可能循环打日志，不限流会瞬间冲爆磁盘 |
 *
 * 分开而不是混在一个文件里：三者体量差几个数量级，混在一起时引擎的高频输出会把
 * 应用日志挤掉轮转，排查时最想看的「App 自己说了什么」反而最先丢。
 *
 * ## 三条不变量
 *
 * ① **写日志绝不能把 App 搞崩。** 任何异常都被吞掉并计数，连续失败就彻底关掉落盘 ——
 *    与改造前的行为一致。因为写日志失败而崩溃是灾难，而且那时崩溃现场也写不出来。
 *
 *    ⚠️ **「关掉落盘」只针对本类管的三个文件**（`app.log`/`engine.log`/`web.log`）。
 *    `events.jsonl` 由 [EventLog] 持自己的目录、`crash/` 里的现场文件由
 *    [CrashRecorder] 持自己的目录，**两者都不看本类的 `dir`**，所以这时它们照常落盘。
 *    别把这种状态叫「纯内存」—— 2026-10-14 真机实测：把 `logs/app.log` 换成同名目录逼
 *    [init] 失败后，`events.jsonl` 仍在长（6 行属于当次启动）、`crash/` 里那份
 *    13,777 B 的现场也还在，而三路文件日志确实零增长。
 * ② **崩溃路径用 `tryLock`。** 崩溃可能发生在**正持有日志锁**的线程上，硬等锁会
 *    死锁、把「闪退」变成「卡死」。拿不到锁就只写 crash 文件（见 [CrashRecorder]）。
 * ③ **轮转只保一代。** `app.log` → `app.log.1`，更老的不留 —— 否则长期使用会
 *    无限增长。`crash/` 是例外，见下。
 *
 * ## `crash/` 为什么不参与轮转
 *
 * 崩溃现场（堆栈 + 最后 200 行日志）比普通日志更值得保留：普通日志是「过程」，
 * 崩溃现场是「结论」。所以它单独配额、单独目录、按时间戳命名（一次崩溃一个文件，
 * 不会互相覆盖），只受总配额约束。
 */
object LogStore {

    /**
     * 日志目录，由 [init] 注入。
     *
     * `null` = 本类管的三个文件（`app.log`/`engine.log`/`web.log`）**不落盘**；
     * ⚠️ 这**不等于**「本次运行什么都没落盘」—— `events.jsonl` 与 `crash/`
     * 由 [EventLog] / [CrashRecorder] 各自持目录，与本字段无关。用
     * [logDirAvailable] 判断「目录挂上没」，用 [appLogEnabled] 判断
     * 「`app.log` 句柄还在不在」，别用本字段下「有没有日志」的结论。
     */
    @Volatile
    private var dir: File? = null

    /** 进程内单调递增序号。用于把「同一条日志」在多个文件间对上，也给排查提供顺序。 */
    private val seq = AtomicLong(0)

    /**
     * 内存环形缓冲 —— 给日志界面看 + 崩溃现场取「最后 N 行」。
     *
     * ⚠️ **不只是为了界面**：崩溃时文件写入可能因为异步缓冲丢了最后几条，
     * 而内存里的这几条正好补上。所以它的大小要够覆盖一次典型崩溃前的活动量。
     */
    private val ring = ArrayDeque<Entry>()

    /**
     * 内存缓冲条数上限。
     *
     * ⚠️ 刻意是 `public`：`LogCommandBridge` 给模型的 `summary` 里要说明
     *   「缓冲只覆盖最近这么多条」—— 不写清楚，模型会把「缓冲里没有」当成
     *   「历史上没发生」。这个数字是那句免责声明的一部分，不能让两处各写一个。
     */
    const val RING_MAX = 4000

    /** 单文件上限。到了就轮转。 */
    private const val MAX_FILE_BYTES = 1L * 1024L * 1024L

    /**
     * `app.log` 因连续写失败被关掉后，隔多久允许重开一次。
     *
     * 60 秒的依据：那三次失败通常是同一个瞬时原因造成的（磁盘满、只读抖动），
     * 一分钟足以让它过去；而一分钟只多一次尝试，完全不会回到「每行都抛异常」
     * 的老问题上。取得太短（几秒）意义不大 —— 磁盘满不会几秒就好。
     */
    private const val APP_REOPEN_COOLDOWN_MS = 60_000L

    /** 轮转代数：`x.log` + `x.log.1`，更老的不留。 */
    private const val KEEP_GENERATIONS = 1

    /**
     * 日志目录总配额。
     *
     * ⚠️ 超了**删最老的**而不是拒绝写入：宁可少一段历史，也不要「日志系统自己把
     * 磁盘占满」—— 那会让引擎写会话失败，问题从「查不到」升级成「用不了」。
     */
    private const val TOTAL_QUOTA_BYTES = 32L * 1024L * 1024L

    /** 崩溃现场配额。独立于总配额之外单独把关，见类注释。 */
    private const val CRASH_QUOTA_BYTES = 8L * 1024L * 1024L
    private const val CRASH_MAX_FILES = 20

    /** 崩溃现场文件里附带的「最后 N 行日志」。 */
    const val CRASH_TAIL_LINES = 200

    /**
     * 同类重复日志的限流窗口与上限。
     *
     * ⚠️ 这是**防日志炸弹**的：页面脚本被循环调用时（本项目真机遇到过 console
     * 每帧打一行），一分钟能产出几十 MB。限流后只留首条 + 一条汇总计数。
     */
    private const val THROTTLE_WINDOW_MS = 1000L
    private const val THROTTLE_MAX_REPEAT = 20

    private val fmt = SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 紧凑时间戳（文件名用，不能含冒号）。 */
    private val fileFmt = SafeDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ── 写入句柄 ──────────────────────────────────────────────────────────────

    /** `app.log`：同步写，崩溃路径也要能用。 */
    @Volatile
    private var appWriter: BufferedWriter? = null
    @Volatile
    private var appBytes = 0L

    /**
     * `app.log` 被 [noteFailure] 关掉之后，**允许重新尝试打开**的时刻
     * （`SystemClock.elapsedRealtime()`；0 表示当前没在冷却期）。
     *
     * ## 为什么需要它
     *
     * [noteFailure] 连续 3 次写失败就把 `appWriter` 置 null，之后 [writeApp]
     * 第一行 `?: return`，**连重开的机会都没有** —— 这个取舍本身是对的
     * （反复抛 IOException + 栈展开会拖慢整个回合），但它有一个被忽视的后果：
     * **本次进程内此后所有应用日志都不再落盘，哪怕磁盘早就恢复了。**
     *
     * 真机上完全可能发生：存储一时写满 / 被别的进程占满 / 只读挂载抖一下，
     * 三次失败之后磁盘恢复正常，但用户的 app.log 永远停在那一刻 ——
     * 而 App 是长期驻留的（前台服务 START_STICKY 保活），
     * 可能几天都不会重启，等于**这几天一条应用日志都没有**。
     * 等真出问题时去要日志，恰好缺的就是这一段。
     *
     * 所以加一个冷却期：关掉之后过 [APP_REOPEN_COOLDOWN_MS] 允许试着重开一次。
     * 重开失败不会抛到调用方（[openApp] 外面有 try），下次再进冷却期，
     * 不是每行都重试 —— 不会回到「反复抛异常拖慢回合」那个老问题。
     */
    @Volatile
    private var appReopenAt = 0L

    /** `engine.log` / `web.log`：异步写。 */
    private val queue = ArrayBlockingQueue<QueuedLine>(4096)
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var engineWriter: BufferedWriter? = null
    @Volatile
    private var engineBytes = 0L
    @Volatile
    private var webWriter: BufferedWriter? = null
    @Volatile
    private var webBytes = 0L

    @Volatile
    private var writeFailures = 0

    /** 上一次限流判据（level + tag + 正文 相同即视为同类）。 */
    private var lastThrottleKey = ""
    private var lastThrottleAt = 0L

    /**
     * 本轮（同一 key、同一窗口）已出现的**重复条数** —— 含被放行的那些。
     * 用来判断有没有超过 [THROTTLE_MAX_REPEAT]。
     */
    private var throttleSeen = 0

    /**
     * 本轮**真正被丢弃**的条数。汇总行与 [throttledTotal] 都只认这个数。
     *
     * ⚠️ **不能拿 [throttleSeen] 当丢弃数用** —— 前 [THROTTLE_MAX_REPEAT] 条
     *   是**放行**的（它们原样进了日志），只有超出部分才真的丢。
     */
    private var throttleDropped = 0

    /** 被限流丢弃的总条数（写进诊断包，让分析者知道日志被抽过稀）。 */
    @Volatile
    var throttledTotal = 0L
        private set

    /**
     * 落盘是否可用。false 时只有内存缓冲。
     *
     * = [logDirAvailable] && [appLogEnabled]。
     */
    val fileLoggingEnabled: Boolean get() = dir != null && appWriter != null

    /**
     * 日志**目录**是否可用（`LogStore.init` 有没有成功挂上目录）。
     *
     * ⚠️ 必须与 [fileLoggingEnabled] 分开看（2026-10-14 自检发现）：
     *   落盘失效有**两种完全不同的形态**，而 `fileLoggingEnabled` 把它们压成了一个
     *   布尔，于是诊断包只能笼统报「文件日志不可用，只有内存缓冲」—— 这句话在
     *   第二种形态下是**假的**：
     *   ① 目录没了（`init` 失败）⇒ 真·什么都没有，所有日志只在内存 ring 里；
     *   ② 目录还在、只是 `app.log` 的句柄被 [noteFailure] 关了（连续 3 次写失败）
     *      ⇒ `engine.log` / `web.log` 照旧在写（它们走 [engineWriterOf] /
     *      [webWriterOf]，与 `appWriter` 无关），`events.jsonl` 也在写。
     *   把 ② 报成「只有内存缓冲」，会让分析者得出结论「这个包里没有磁盘日志」，
     *   从而**不去看**实际上存在的 `engine.log` —— 而引擎的报错只在 `engine.log` 里。
     */
    val logDirAvailable: Boolean get() = dir != null

    /**
     * `app.log` 的句柄是否还开着。
     *
     * ⚠️ 关了就**不会再自动重开**：[noteFailure] 把 `appWriter` 置 null 之后，
     *   [writeApp] 第一行就 `?: return`，连重开的机会都没有（这正是「连续」二字
     *   必须配 `writeFailures = 0` 的原因，见那里）。所以这个值为 false 表示
     *   **此后本次进程的全部应用日志都不落盘**，哪怕磁盘早就恢复了。
     */
    val appLogEnabled: Boolean get() = appWriter != null

    /** 日志目录（诊断包用）。 */
    fun dirOrNull(): File? = dir

    /** 崩溃现场目录。 */
    fun crashDirOrNull(): File? = dir?.let { File(it, "crash") }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    /**
     * 挂上日志目录并开始落盘。
     *
     * ⚠️ 必须在 `Application.attachBaseContext()` 里调用（比 `onCreate` 还早），
     * 否则启动阶段的崩溃抓不到。这里**只做建目录 + 开文件**，不做任何耗时的事
     * —— 它挡在 App 启动路径上。
     *
     * @return 落盘是否可用。
     */
    fun init(logDir: File): Boolean {
        return try {
            if (!logDir.exists() && !logDir.mkdirs()) {
                AndroidLog.e(TAG, "日志目录创建失败: ${logDir.absolutePath}")
                return false
            }
            dir = logDir
            migrateLegacy(logDir)
            openApp(append = true)
            File(logDir, "crash").mkdirs()
            startWorker()
            enforceQuota()
            true
        } catch (t: Throwable) {
            // 落盘不可用就退回纯内存。**不要**在这里用 DshLog —— 它还没接上。
            dir = null
            // ⚠️ **不能只说「退回纯内存」**：这是排查者最先看到的一行，而它字面上
            //   会让人以为「这台机器本次没有日志可查」。真机实测（2026-10-14）相反：
            //   events.jsonl 与 crash/ 各有自己的目录，这时仍在落盘。把范围写清楚。
            AndroidLog.e(
                TAG,
                "LogStore.init 失败：app.log/engine.log/web.log 本次不落盘（events.jsonl 与 crash/ 仍照常落盘）: ${t.message}",
            )
            false
        }
    }

    /**
     * 把老版本的 `boo.log` 挪成 `app.log.1`。
     *
     * ⚠️ **不能删**：老日志里可能有用户上次反馈问题时的那一段现场，而升级后的
     * 第一次排查恰恰需要它。改名保留一代是最小代价。
     */
    private fun migrateLegacy(logDir: File) {
        val old = File(logDir, "boo.log")
        if (!old.isFile) return
        try {
            val target = File(logDir, "app.log.1")
            if (target.exists()) target.delete()
            if (old.renameTo(target)) {
                AndroidLog.i(TAG, "已把旧的 boo.log 归档为 app.log.1")
            }
        } catch (_: Throwable) {
        }
        // 老的 last-crash.txt 一并归档到新位置，保持「崩溃现场独立留存」的语义
        val oldCrash = File(logDir, "last-crash.txt")
        if (oldCrash.isFile) {
            try {
                val crashDir = File(logDir, "crash").apply { mkdirs() }
                val target = File(crashDir, "${fileFmt.format(Date())}-legacy.txt")
                oldCrash.renameTo(target)
            } catch (_: Throwable) {
            }
        }
    }

    // ── 写入入口 ──────────────────────────────────────────────────────────────

    /**
     * 记一条**应用日志**（476 处 `DshLog.*` 的落点）。同步写，崩溃安全。
     *
     * @param level 级别字符 `V/D/I/W/E`（`wtf` 归一成 `E`）。
     */
    fun app(level: Char, tag: String, msg: String) {
        val safe = safeRedact(msg)
        val n = seq.incrementAndGet()
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        remember(Entry(now, level, tag, safe, n, th))
        if (throttled(level, tag, safe, now)) return
        writeApp(format(now, level, tag, safe, n, th))
    }

    /**
     * 记一条**引擎日志**（stdout/stderr）。异步。
     *
     * ⚠️ 引擎输出里带 token 的那行 URL 会被 [Redactor] 抹掉 —— 见
     * `urlSecretPattern` 的注释。
     */
    fun engine(line: String) {
        val safe = safeRedact(line)
        val n = seq.incrementAndGet()
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        remember(Entry(now, 'I', "engine", safe, n, th))
        enqueue(QueuedLine.Target.ENGINE, format(now, 'I', "engine", safe, n, th))
    }

    /** 记一条**网页 console 日志**。异步 + 限流。 */
    fun web(line: String) {
        val safe = safeRedact(line)
        val n = seq.incrementAndGet()
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        remember(Entry(now, 'I', "web", safe, n, th))
        if (throttled('I', "web", safe, now)) return
        enqueue(QueuedLine.Target.WEB, format(now, 'I', "web", safe, n, th))
    }

    /** 统一行格式：`时间 [级别] [tag] #seq 线程 正文`。 */
    private fun format(t: Long, level: Char, tag: String, msg: String, n: Long, thread: String): String =
        "${fmt.format(Date(t))} [$level] [$tag] #$n $thread $msg"

    // ── 内存缓冲 ──────────────────────────────────────────────────────────────

    private fun remember(e: Entry) {
        synchronized(ring) {
            if (ring.size >= RING_MAX) ring.removeFirst()
            ring.addLast(e)
        }
    }

    /** 全部内存日志（新 → 旧顺序无关，界面自己排）。 */
    fun entries(): List<Entry> = synchronized(ring) { ring.toList() }

    /** 最近 [n] 行，格式化成文本。崩溃现场与 AI 工具都用它。 */
    fun tail(n: Int, minLevel: Char? = null): List<String> {
        val list = synchronized(ring) { ring.toList() }
        val filtered = if (minLevel == null) list else list.filter { severity(it.level) >= severity(minLevel) }
        return filtered.takeLast(n.coerceAtLeast(0))
            .map { format(it.t, it.level, it.tag, it.msg, it.seq, it.thread) }
    }

    /** 级别严重度排序，供筛选比较。 */
    fun severity(level: Char): Int = when (level) {
        'E' -> 4
        'W' -> 3
        'I' -> 2
        'D' -> 1
        else -> 0
    }

    /** 清空内存缓冲（**不删文件** —— 见 [clearInMemory]）。 */
    private fun clearRing() {
        synchronized(ring) { ring.clear() }
    }

    /**
     * 用户点「清空」。
     *
     * ⚠️ **只清内存、不删文件**：文件是「用户点清空」之前的历史，正是排查时要的。
     * 若一并删掉，「先清空再复现」就永远看不到清空前的现场。
     */
    fun clearInMemory() {
        clearRing()
        app('I', "Log", "内存日志已由用户清空（文件保留）")
    }

    /**
     * 用户点「清空」——**连文件一起删**。
     *
     * ⚠️ 与 [clearInMemory] 是两个不同的语义，别混用：
     * - [clearInMemory]：只丢内存缓冲，文件保留。给「AI 想清掉噪音再复现」用 ——
     *   那时磁盘上的历史恰恰是证据。
     * - [clearAll]：文件也删。给**界面上那个按钮**用 —— 用户点了「清空」却发现
     *   重启后日志又回来了（因为文件还在、启动时会被重新读入），会认为功能坏了。
     *
     * 删的范围：`app.log` 及其轮转代、`engine.log`、`web.log`、崩溃现场、
     * 事件时间线、`export/` 与 `pending/`（都是本系统自己生成的，删掉不伤业务）。
     *
     * ⚠️ **不删 `device-id`**：那是安装期生成一次的匿名指纹，删了会让这台机器
     * 在服务器侧变成另一台设备，同一台机器的多次上报无法关联。
     *
     * @return 删除的文件数（界面用它给用户一个具体的回执）。
     */
    fun clearAll(logDir: File): Int {
        var n = 0
        // 先清内存，避免界面在这之后还从缓冲里读到旧内容。
        clearRing()
        val targets = listOf(
            APP_LOG, "$APP_LOG.1",
            ENGINE_LOG, "$ENGINE_LOG.1",
            WEB_LOG, "$WEB_LOG.1",
            // ⚠️ **`.1` 必须成对写**（2026-10-14 对抗式审查查出）：`EventLog.rotate()`
            //   产生的正是 `events.jsonl.1`，而 `EventLog.readAll()` 读的也是它
            //   （读取列表是 `["$FILE_NAME.1", FILE_NAME]`）。只删当前代的话，
            //   用户点「清空日志」后界面看不到旧事件了，但**下一次导出诊断包时
            //   `timeline.jsonl` 仍会包含清空前的全部事件** —— 用户以为清干净了
            //   并把包发出去。上面三对都写全了，唯独 events 少了，不像有意为之。
            EventLog.FILE_NAME, "$(EventLog.FILE_NAME).1",
            "last-crash.txt",
        )
        for (name in targets) {
            runCatching { if (File(logDir, name).delete()) n++ }
        }
        for (sub in listOf("crash", "export", "pending", "failed")) {
            runCatching {
                File(logDir, sub).listFiles()?.forEach { f ->
                    if (f.isFile && f.delete()) n++
                }
            }
        }
        // 清完之后立刻重开 app.log：不重开的话，writer 还指向已被删除的 inode，
        // 后续日志会写进一个「已经没有名字」的文件，界面上看不到任何新日志。
        //
        // ⚠️ **`engine.log` / `web.log` 必须一起重开**（踩过，代价是整段日志凭空消失）：
        //   这两者走 [engineWriterOf] / [webWriterOf] 的惰性创建，而它们的第一行是
        //   `engineWriter?.let { return it }` —— 只要引用非空就直接返回**旧** writer，
        //   永远不会自己发现文件已被删。于是此后的引擎输出全部写进那个已被 unlink
        //   的 inode（没有名字、进程一死就彻底消失）。
        //   真机证据：`20261013-232433-ee417cc3` 等 6 个包在 23:00:41 点了「清空」，
        //   之后引擎又输出了 32~97 行，而 `engine.log` 里**一行都没有**（0 字节）；
        //   同时 `app.log` 里那 32~97 行 `[engine]` 副本完好无损 —— 两份日志的分叉
        //   正好卡在清空那一刻，这就是判据。
        //   置空引用即可：下次写引擎/网页日志时惰性重开，落到新建的文件上。
        runCatching {
            lock.withLock {
                appWriter?.close()
                appWriter = null
                openApp(append = false)

                engineWriter?.close()
                engineWriter = null
                engineBytes = 0L

                webWriter?.close()
                webWriter = null
                webBytes = 0L
            }
        }
        // ⚠️ `events.jsonl` 也在这份删除清单里，所以 EventLog 的 writer 同样必须重开 ——
        //   它与 engine/web 是同一个失效模式（引用非空即写旧 writer），只是没有惰性
        //   创建、需要显式重开。见 [EventLog.reopen]。
        runCatching { EventLog.reopen(logDir) }
        app('I', "Log", "日志已由用户清空（删除 $n 个文件）")
        return n
    }

    // ── 落盘 ──────────────────────────────────────────────────────────────────

    private fun openApp(append: Boolean) {
        val d = dir ?: return
        val f = File(d, APP_LOG)
        appWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, append), Charsets.UTF_8))
        appBytes = if (append) f.length() else 0L
    }

    /**
     * 同步追加一行到 `app.log`。
     *
     * ⚠️ `flush()` **每行都调**：这就是「崩溃时日志完整」的代价与保障。
     * 不 flush 的话最后几行留在用户态缓冲里，进程一死就没了 —— 而那几行
     * 恰恰是崩溃前发生的事。
     */
    private fun writeApp(line: String) {
        // 句柄被 [noteFailure] 关掉过、且冷却期已到 → 试着重开一次。
        // 见 [appReopenAt] 的说明：不这么做的话，磁盘恢复后本次进程会永远
        // 丢掉全部应用日志。
        if (appWriter == null) {
            val at = appReopenAt
            if (at == 0L || android.os.SystemClock.elapsedRealtime() < at) return
            // 先清冷却标记再重开：重开若又失败，[noteFailure] 会重新设它，
            // 不会因为这次尝试而陷入每行都重试。
            appReopenAt = 0L
            try {
                lock.withLock {
                    if (appWriter == null) {
                        openApp(append = true)
                        writeFailures = 0
                        AndroidLog.i(TAG, "LogStore: app.log 已重新打开（磁盘恢复后自愈）")
                    }
                }
            } catch (t: Throwable) {
                noteFailure("app.log(重开)")
                return
            }
        }
        val w = appWriter ?: return
        try {
            lock.withLock {
                /*
                 * ⚠️ **锁内必须再核一次**：锁外取到的 `w` 可能已经在本线程
                 *    取到它之后、拿到锁之前，被 [flushAndClose] 关掉了。
                 *    核一次引用有没有变，没变才写 —— 这样"取引用→写"是原子的。
                 */
                if (w !== appWriter) return
                w.write(line)
                w.write("\n")
                w.flush()
                // ⚠️ **按真实字节数计**，不能拿 `line.length`（那是 UTF-16 字符数）。
                //   真机 `app.log` 实测 344,103 字节 / 286,637 字符 = 1.202 B/char，
                //   即声明的 1 MB 轮转实际在 ~1.2 MB 才触发；日志全中文时到 3x。
                //   同源问题也修在 `EventLog.record`（那里原来用的是 `JSONObject.length()`，
                //   低估 20 倍）。见 2026-10-14 对抗式审查报告。
                appBytes += line.toByteArray(Charsets.UTF_8).size.toLong() + 1
                // ⚠️ **写成功必须清零，否则「连续」二字是假的。** [noteFailure] 的
                //   注释与它写进 app.log 的文案都是「**连续**写入失败 N 次」，
                //   而原来这个计数器只增不减 —— 于是三次**相隔几小时**的瞬时失败
                //   （磁盘一时满、被别的进程占满、只读挂载抖动）会被算成「连续」，
                //   日志系统就此**永久**关掉落盘：此后 `appWriter` 一直是 null，
                //   [writeApp] 直接 return，连重开的机会都没有，所有日志只留在内存
                //   ring 里，进程一死全没 —— 而磁盘其实早就恢复了。
                //   清零之后，判据才真正等于「连续」，注释给的理由（磁盘满时每条都
                //   抛一次 IOException + 栈展开，拖慢整个回合）也才成立。
                writeFailures = 0
                if (appBytes >= MAX_FILE_BYTES) rotateApp()
            }
        } catch (t: Throwable) {
            noteFailure("app.log")
        }
    }

    private fun rotateApp() {
        try {
            appWriter?.close()
        } catch (_: Throwable) {
        }
        appWriter = null
        val d = dir ?: return
        // ⚠️ 这里**故意不检查 rotateFiles 的返回值**：紧接着的 openApp(append = false)
        //   会以 truncate 模式重开 app.log，无论 rename 成没成，文件都会被清空、
        //   appBytes 也随 f.length() 归零。所以「rename 失败」在 app.log 这条路上
        //   只意味着丢了一代历史，不会退化成「每行都重试轮转」的循环 ——
        //   与 [rotateAsync] 的处境不同，那边必须检查。
        rotateFiles(File(d, APP_LOG))
        openApp(append = false)
    }

    /**
     * 通用的「当前 → .1」轮转。调用方已关闭 writer。
     *
     * @return **是否真的把 `cur` 挪走了**。调用方必须看这个返回值 ——
     *   `File.renameTo()` 失败时只返回 `false`、**不抛异常**，不检查就等于
     *   把「轮转没做成」当成「轮转做成了」（缺陷族第 ix 条：静默失败）。
     *   两种调用方的后果不同，见各自的注释。
     */
    private fun rotateFiles(cur: File): Boolean {
        var ok = true
        for (gen in KEEP_GENERATIONS downTo 1) {
            val src = if (gen == 1) cur else File(cur.parentFile, "${cur.name}.${gen - 1}")
            val dst = File(cur.parentFile, "${cur.name}.$gen")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                if (!src.renameTo(dst)) ok = false
            }
        }
        return ok
    }

    /**
     * 写日志失败计数。连续 3 次就彻底关掉 `app.log` 的落盘。
     *
     * ⚠️ 为什么是「关掉」而不是「继续重试」：磁盘满 / 只读时每条日志都会抛一次
     * 异常，那会**拖慢整个回合**（每条日志一次 IOException + 栈展开）。而日志
     * 本身是辅助功能，不该拖垮主流程。
     *
     * ⚠️ **关掉的只有 `app.log` 一个文件**：这里只置空 `appWriter`，而 `engine.log`/
     * `web.log` 走异步队列（`enqueue` → worker），`events.jsonl` 与 `crash/` 更是
     * 另有自己的目录。所以这条日志的措辞不能是「已关掉落盘」——那是**形态②**
     * 的失真：读者会以为本次运行什么都没有了，实际只有 `app.log` 停了。
     * （形态② 无法从外部制造：已打开的 fd 不受文件替换与权限变更影响，只有
     * 真·磁盘满能触发，所以这条文案此前没被真机证伪过 —— 按逻辑改准。）
     */
    private fun noteFailure(what: String) {
        writeFailures++
        if (writeFailures >= 3) {
            try {
                appWriter?.close()
            } catch (_: Throwable) {
            }
            appWriter = null
            // 安排一次冷却期后的重开尝试（见 [appReopenAt]）。
            appReopenAt = android.os.SystemClock.elapsedRealtime() + APP_REOPEN_COOLDOWN_MS
            AndroidLog.e(
                TAG,
                "LogStore: $what 连续写入失败 $writeFailures 次，已关闭 app.log 的写入" +
                    "（engine.log / web.log / events.jsonl / crash/ 不受影响，仍在写）",
            )
        }
    }

    /**
     * 投进异步队列。
     *
     * ⚠️ **队列满时丢弃并计数，绝不阻塞调用方。** 调用方是引擎的 stdout 读取线程
     * （见 `EngineManager` 里那段「不能用 use{} 读完 URL 就关流」的注释）：阻塞它
     * 会让引擎的 stdout 管道（64KB）写满，引擎**卡死在 write 上**。丢日志远好过
     * 卡引擎 —— 这是本方法最要紧的一条。
     */
    private fun enqueue(target: QueuedLine.Target, line: String) {
        if (!queue.offer(QueuedLine(target, line))) {
            droppedAsync++
        }
    }

    /** 因队列满而丢弃的异步日志条数（写进诊断包）。 */
    @Volatile
    var droppedAsync = 0L
        private set

    // ── 异步工作线程（engine / web） ──────────────────────────────────────────

    private class QueuedLine(val target: Target, val line: String) {
        enum class Target { ENGINE, WEB }
    }

    /**
     * 异步写入线程。
     *
     * ⚠️ 队列满时**丢弃并计数**，不阻塞调用方：调用方是引擎的输出读取线程，
     * 阻塞它会让引擎的 stdout 管道写满、进而卡死整个引擎（见 `EngineManager`
     * 里那段关于 64KB 管道缓冲的注释）。丢日志远好过卡引擎。
     */
    private fun startWorker() {
        if (worker != null) return
        val t = Thread({
            while (true) {
                try {
                    val item = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    val needRotate = when (item.target) {
                        QueuedLine.Target.ENGINE -> writeAsync(engineWriterOf(), item.line, { n -> engineBytes = n }, { engineBytes })
                        QueuedLine.Target.WEB -> writeAsync(webWriterOf(), item.line, { n -> webBytes = n }, { webBytes })
                    }
                    // 轮转在 [writeAsync] 的锁**之外**做（它自己要 close/rename/重开）。
                    if (needRotate) rotateAsync(item.target)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // 铁律 ①：工作线程绝不能因为写日志失败而死掉，否则此后所有
                    // 引擎日志都静默丢失，症状是「日志看着正常，但引擎那部分没了」。
                }
            }
        }, "dsh-log-writer").apply { isDaemon = true }
        worker = t
        t.start()
    }

    /**
     * 惰性开文件 —— 引擎/网页日志第一次真的出现时才建，避免空文件。
     *
     * ⚠️ `xxxBytes` 在这里取的是 `f.length()`，即**文件的真实大小**，不是进程内累加值。
     *   所以只要有人（本进程的 [rotateAsync] 失败分支、或外部）动过这个文件，
     *   调这个方法就是「让计数与文件同步」的唯一手段。调用前必须先 `writer = null`，
     *   否则会被上面那行早退挡住。
     */
    private fun engineWriterOf(): BufferedWriter? {
        engineWriter?.let { return it }
        lock.withLock {
            engineWriter?.let { return it }
            val d = dir ?: return null
            val f = File(d, ENGINE_LOG)
            engineWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            engineBytes = f.length()
            return engineWriter
        }
    }

    private fun webWriterOf(): BufferedWriter? {
        webWriter?.let { return it }
        lock.withLock {
            webWriter?.let { return it }
            val d = dir ?: return null
            val f = File(d, WEB_LOG)
            webWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            webBytes = f.length()
            return webWriter
        }
    }

    /**
     * 写一行异步日志。**返回值 = 这一行之后是否需要轮转**（由调用方在锁外执行
     * [rotateAsync]，原因见那里的注释）。
     */
    private fun writeAsync(
        w: BufferedWriter?,
        line: String,
        setBytes: (Long) -> Unit,
        getBytes: () -> Long,
    ): Boolean {
        if (w == null) return false
        return try {
            lock.withLock {
                /*
                 * ⚠️ **锁内必须再核一次**：这个 `w` 是调用方在锁外通过
                 *    [engineWriterOf] / [webWriterOf] 取到的，而从"取到"到
                 *    "拿到锁"之间，[flushAndClose] 可能已经把它 close 并置空了。
                 *
                 * 对着已关闭的流写会抛 IOException，被下面的 catch 静默吞掉 ——
                 * 不会崩，但**这一行日志就悄悄丢了**，而调用方拿到的 `false`
                 * 会被当成"缓冲区暂时写不进去"，没人知道真发生了什么。
                 *
                 * 核一次引用有没有变，变了就说明这条流已经作废，直接放弃这一行。
                 * 与 [writeApp] 里同样的守卫保持一致。
                 */
                if (w !== engineWriter && w !== webWriter) return false
                w.write(line)
                w.write("\n")
                // 异步档**攒着不 flush**：靠 BufferedWriter 自己的缓冲 + 阈值触发。
                // 这正是它与 app.log 的区别所在。
                val next = getBytes() + line.toByteArray(Charsets.UTF_8).size.toLong() + 1
                setBytes(next)
                if (next >= MAX_FILE_BYTES) {
                    w.flush()
                    return@withLock true
                }
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 异步档（`engine.log` / `web.log`）的轮转。
     *
     * ## 🔴 为什么必须补上（2026-10-14 对抗式审查查出）
     *
     * 原来这两路**根本没有轮转**：`rotateFiles()` 在全仓库只有 `rotateApp()` 一个
     * 调用点，所以 [MAX_FILE_BYTES] 对它们唯一的实际效果就是「到 1 MB 就 flush 一次」。
     * 而 [enforceDirQuota] 又把这两个文件名排除在删除之外（理由正当：writer 会写到
     * 已 unlink 的 inode 上）—— 于是它们是**唯一既不长轮转、又不受配额管**的文件。
     *
     * 后果是一条闭环的坏链：引擎 stdout / 网页 console 刷屏（正是最容易刷屏的两路）
     * ⇒ `engine.log`/`web.log` 无上限增长 ⇒ 撑爆 32 MB 总配额 ⇒ [enforceDirQuota]
     * 把其它文件（含 `device-id`、`events.jsonl`）删光 ⇒ **仍降不回配额**，
     * 因为超标的正是这两个不能删的文件。
     *
     * 这与 `AGENTS.md` 的三处明文（「1MB 轮转，留一代」）以及插件描述
     * （`mobile-ui-plugin/dsh-android-logs/lib/index.js`：All three are capped at
     * ~1 MB per file with one rotated generation）都不符 —— 那三处说的是**三个**
     * 文件都轮转，实现里只有一个。
     *
     * ## 为什么放在 `writeAsync` 之外
     *
     * [writeAsync] 在 `lock.withLock` 里被调用，而轮转要 close + rename + 重开
     * writer，不能在同一把锁内做（重开走 `engineWriterOf()`，它自己也要拿这把锁
     * —— `ReentrantLock` 虽然可重入，但在这里做重活会把 worker 卡住）。
     * 所以由调用方在写完之后判断，与 `app.log` 的 `rotateApp()` 结构一致。
     *
     * @param target 轮转哪一路（决定重开哪个 writer、以及哪两个字节计数要清零）。
     */
    private fun rotateAsync(target: QueuedLine.Target) {
        try {
            lock.withLock {
                val cur: File? = when (target) {
                    QueuedLine.Target.ENGINE -> {
                        runCatching { engineWriter?.close() }
                        engineWriter = null
                        dir?.let { File(it, ENGINE_LOG) }
                    }
                    QueuedLine.Target.WEB -> {
                        runCatching { webWriter?.close() }
                        webWriter = null
                        dir?.let { File(it, WEB_LOG) }
                    }
                }
                if (cur == null) return

                val moved = rotateFiles(cur)
                if (moved) {
                    // 正常路径：文件已挪走，计数清零，下一行惰性重开就是新文件。
                    when (target) {
                        QueuedLine.Target.ENGINE -> engineBytes = 0L
                        QueuedLine.Target.WEB -> webBytes = 0L
                    }
                } else {
                    // 🔴 **轮转失败时必须让计数从头开始，而不是与文件长度同步。**
                    //
                    // ⚠️ 第一版修法是错的，真机当场证伪（2026-10-14 05:48）：那时我把
                    //   `engineBytes` 重算成 `f.length()` 的真实值（1,410,707）。看起来
                    //   「计数与文件一致」很对，但 [writeAsync] 的判据是
                    //   `next >= MAX_FILE_BYTES` —— 文件本身已经 1.41 MB > 1 MB，
                    //   于是**下一行照样超标**，仍是每行触发一次
                    //   「close + rename(必败) + 重开」。真机 logcat 里三条警告相隔
                    //   **3 毫秒**、字节数只差 150，就是这个坏循环的直接证据。
                    //
                    //   正确的语义是「**自上次轮转尝试以来**写入了多少」：失败后归零，
                    //   于是要再写满 1 MB（文件长到 ~2.41 MB）才会重试一次轮转。
                    //   既不会每行重试刷屏，也不会永远不再重试。
                    //
                    //   不这么做的另一个后果：writer 被置 null 后下一行会走
                    //   [engineWriterOf] 以 **append** 模式重开（不像 `rotateApp` 那样
                    //   truncate），所以文件不会被清空、会继续无上限增长 —— 正是这次
                    //   补轮转要解决的那个问题换了个形态回来。
                    //
                    //   触发形态（真机可造）：`engine.log.1` 恰好是个目录，或磁盘满、
                    //   权限异常 —— `renameTo` 返回 false 而不抛异常。
                    when (target) {
                        QueuedLine.Target.ENGINE -> {
                            // 先重开 writer（这一步会把计数设成 f.length()），再归零覆盖。
                            // 顺序不能反：不重开的话下一行的惰性重开会把计数设回超标值。
                            engineWriter = null
                            engineWriterOf()
                            engineBytes = 0L
                        }
                        QueuedLine.Target.WEB -> {
                            webWriter = null
                            webWriterOf()
                            webBytes = 0L
                        }
                    }
                    AndroidLog.w(
                        TAG,
                        "LogStore: ${cur.name} 轮转失败（rename 未成功），" +
                            "已保持 append 续写；文件已达 ${cur.length()} 字节，" +
                            "多半是 ${cur.name}.1 被占用（目录/权限/磁盘满）",
                    )
                }
            }
        } catch (_: Throwable) {
            // 轮转失败不该让日志线程挂掉：writer 已置空，下一行会走惰性重开。
        }
    }

    /**
     * 把异步缓冲刷下去。**崩溃路径必须调用它**（见 [CrashRecorder]）——
     * 否则引擎日志的最后几行永远留在用户态缓冲里。
     *
     * ⚠️ 光 flush writer 是**不够**的：`enqueue()` 只是把行放进 `queue`，由
     * `dsh-log-writer` 线程慢慢取。调用它的线程与那个 worker 之间没有任何同步，
     * 所以此刻队列里可能还压着一批**根本没写进 writer** 的行 —— 它们不在
     * BufferedWriter 里，flush 自然刷不出来。
     *
     * 这里先自己把队列排干（`writeAsync` 内部有 `lock`，与 worker 并发是安全的），
     * 再 flush。排干的是「本方法被调用这一刻已经入队的行」，之后新入队的行照旧
     * 走 worker —— 打包这种一次性动作要的正是「此刻为止的完整快照」。
     */
    fun flushAsync() {
        try {
            // ⚠️ **排干时越过 1 MB 的那一行，很可能就是本方法自己写的** ——
            //   所以不能在锁内轮转（`rotateAsync` 要拿同一把锁并做 close/rename），
            //   必须把「需要轮转」这个事实带出锁外再执行。原来这里直接忽略返回值，
            //   靠「worker 下一行会轮转」兜底：如果此后**再没有新行**（打包完就退出、
            //   或引擎安静了），文件就永远停在超限状态，而 `engine.log` 恰好是
            //   唯一不受 `enforceDirQuota` 管的那两个之一（见 [rotateAsync]）。
            //
            // ⚠️ **必须收成一个集合，不能只留最后一个。** 第一版用单个
            //   `var rotateTarget: Target?`，超限时 `rotateTarget = item.target` ——
            //   两路**同时**超限时前者会被后者覆盖。而下面注释原本写着「下一轮再处理
            //   另一路」，这句是不成立的：下一轮是 worker 取下一条消息，它只看**那一条**
            //   的目标；若此后不再有 ENGINE 的新行（引擎安静了），`engine.log` 就永远
            //   停在超限状态 —— 而它恰好是唯一不受 `enforceDirQuota` 管的两个之一。
            //   这与本方法要解决的正是同一个问题，不能留这个缺口。
            val rotateTargets = mutableSetOf<QueuedLine.Target>()
            lock.withLock {
                // ① 排干队列：直接自己写，不等 worker 调度。
                while (true) {
                    val item = queue.poll() ?: break
                    val need = when (item.target) {
                        QueuedLine.Target.ENGINE ->
                            writeAsync(engineWriterOf(), item.line, { n -> engineBytes = n }, { engineBytes })
                        QueuedLine.Target.WEB ->
                            writeAsync(webWriterOf(), item.line, { n -> webBytes = n }, { webBytes })
                    }
                    if (need) rotateTargets.add(item.target)
                }
                // ② 再 flush 已经攒在 BufferedWriter 里的。
                engineWriter?.flush()
                webWriter?.flush()
            }
            // ③ 锁外轮转（两路都可能超限，逐路处理）。
            rotateTargets.forEach { rotateAsync(it) }
        } catch (_: Throwable) {
        }
    }

    /**
     * 关掉全部写入并落盘。崩溃处理器的最后一步。
     *
     * ⚠️ 只 flush + close，**不删任何东西**。
     */
    fun flushAndClose() {
        try {
            lock.withLock {
                appWriter?.flush()
                appWriter?.close()
                engineWriter?.flush()
                engineWriter?.close()
                webWriter?.flush()
                webWriter?.close()
                /*
                 * ⚠️ **置空必须在锁内**，不能像原来那样放到锁外。
                 *
                 * [writeApp] 的写法是「锁外取引用 → 锁内写」：
                 *
                 *     val w = appWriter ?: return      // ← 锁外读
                 *     lock.withLock { w.write(line) }  // ← 锁内写
                 *
                 * 如果置空在锁外，就会出现：writeApp 刚取到非空引用 w，
                 * 本函数拿到锁把这条流 close 掉、释放锁、然后才置空 ——
                 * 紧接着 writeApp 拿到锁，对着**已关闭的流**写。
                 *
                 * 那个 IOException 会被外层 catch 住、不会崩，但它会喂给
                 * [noteFailure] 计"连续写入失败" —— 攒到 3 次就把日志落盘
                 * 整个关掉（`appWriter` 永久为 null 直到冷却期）。也就是说
                 * **一次正常的"清空日志/退出关闭"操作，可能连带把日志系统关死。**
                 *
                 * 移进锁内后，writeApp 的「取引用」与「写」之间不会再插入 close。
                 */
                appWriter = null
                engineWriter = null
                webWriter = null
            }
        } catch (_: Throwable) {
            // close 过程中抛了也要保证引用归零，否则下次写会对着坏流写。
            runCatching { lock.withLock { appWriter = null; engineWriter = null; webWriter = null } }
        }
    }

    // ── 崩溃路径专用的加锁入口 ────────────────────────────────────────────────

    /**
     * 日志锁 —— **本文件里所有写入路径共用的一把**。
     *
     * ⚠️ 用 `ReentrantLock` 而不是 `synchronized(this)`，唯一理由是崩溃路径需要
     * `tryLock`：Kotlin 的 `synchronized` 块**不可超时**，而崩溃路径上硬等锁会
     * 死锁（见 [CrashRecorder] 类注释约束①）。同一把锁必须被**所有**写入路径
     * 使用（`lock.withLock { }`），否则崩溃路径与正常路径会互相穿透 ——
     * 那样「拿不到锁」这个判据就是假的，现场文件会与 `app.log` 的写入打架。
     *
     * ⚠️ 读取路径（[entries] / [tail]）用的是**另一把**锁（`ring` 的监视器），
     * 这是刻意的：环形缓冲的读写与文件写入互不相干，共用一把会让「读日志」
     * 阻塞「写日志」—— 而日志界面恰恰是在出问题时打开的，那时写入最频繁。
     */
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * 在 [timeoutMs] 内拿到日志锁则执行 [block]，否则**跳过并返回 false**。
     *
     * ⚠️ 返回 false 是**正常情况**，不是错误：崩溃恰好发生在持有日志锁的线程上
     * 时必然拿不到。调用方（[CrashRecorder]）对此已有设计 —— 现场文件那条路径
     * 不碰这把锁，所以信息不会丢。
     *
     * ⚠️ 不要在正常路径用这个方法（会引入不必要的超时语义）；正常路径直接
     *    `lock.withLock { }`。
     */
    fun withLockOrSkip(timeoutMs: Long, block: () -> Unit): Boolean {
        var acquired = false
        return try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
            if (acquired) {
                block()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            if (acquired) {
                runCatching { lock.unlock() }
            }
        }
    }

    // ── 限流 ──────────────────────────────────────────────────────────────────

    /**
     * 同类日志限流。返回 true 表示**这条应当被丢弃**。
     *
     * 判据是 `level + tag + 正文`完全相同（不是只比 tag）：同一个 tag 下正常会
     * 打很多不同的话，只比 tag 会把正常日志也限掉。
     *
     * ⚠️ 被丢弃的条数会累积到 [throttledTotal] 并写进诊断包 —— 分析者必须知道
     * 「这份日志被抽过稀」，否则会误判「这件事只发生了一次」。
     *
     * ⚠️ **「出现了几条」与「丢了几条」是两个数，必须分开记。** 同一窗口内前
     *   [THROTTLE_MAX_REPEAT] + 1 条（首条 + 20 条重复）是**放行**的，只有超出
     *   部分才真的丢。原来只用一个 `throttleCount` 同时干这两件事，于是汇总行与
     *   `throttledTotal` 报的是「出现次数」而不是「丢弃次数」—— **凭空多报**：
     *   实测同一 key 连发 5 条（全部放行、一条没丢），日志里却写「…被抑制 4 次」，
     *   `throttledTotal` 也加了 4；连发 21 条（全部放行）写「被抑制 20 次」。
     *   两个方向都有害：分析者会以为日志被抽稀过（其实逐条完整），并据此怀疑
     *   自己看到的不是全部。
     */
    private fun throttled(level: Char, tag: String, msg: String, now: Long): Boolean {
        val key = "$level|$tag|$msg"
        lock.withLock {
            if (key != lastThrottleKey || now - lastThrottleAt > THROTTLE_WINDOW_MS) {
                // 上一轮若**真的丢过**，补一条汇总 —— 否则日志里会凭空少一截。
                // ⚠️ 判据是 throttleDropped 而不是 throttleSeen：没丢就不该写这一行。
                if (throttleDropped > 0) {
                    val note = "… 上一条同类日志在 ${THROTTLE_WINDOW_MS}ms 内被抑制 $throttleDropped 次"
                    throttledTotal += throttleDropped
                    throttleDropped = 0
                    writeAppThrottleNote(note)
                }
                throttleSeen = 0
                lastThrottleKey = key
                lastThrottleAt = now
                return false
            }
            throttleSeen++
            return if (throttleSeen > THROTTLE_MAX_REPEAT) {
                throttleDropped++
                true
            } else {
                false
            }
        }
    }

    private fun writeAppThrottleNote(note: String) {
        try {
            writeApp(
                format(
                    System.currentTimeMillis(), 'W', "Log", note, seq.incrementAndGet(),
                    Thread.currentThread().name,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    // ── 配额 ──────────────────────────────────────────────────────────────────

    /**
     * 执行总配额与崩溃现场配额。**启动时调一次**即可 —— 运行期靠单文件轮转兜住，
     * 不必每条日志都 stat 整个目录（那才是真的拖慢）。
     */
    fun enforceQuota() {
        val d = dir ?: return
        try {
            enforceDirQuota(d, TOTAL_QUOTA_BYTES)
            crashDirOrNull()?.let { enforceCrashQuota(it) }
        } catch (_: Throwable) {
        }
    }

    /**
     * 顶层日志文件超配额就删最老的，直到降回配额以内。
     *
     * ⚠️ **只管顶层文件，不管任何子目录。** `candidates` 被 `it.isFile` 滤过，
     *   而 `crash/`、`pending/`、`export/`、`failed/` 都是目录 —— 于是它们
     *   **一个都不在清理范围内**。这正好是我们想要的结果（删了 `pending/` 就永远
     *   传不出去，删了 `export/` 等于骗用户），但它是 `it.isFile` 的**副产品**，
     *   不是靠某个白名单实现的。
     *
     * ⚠️ 原来的签名带一个 `skipDirs: Set<String>` 参数，注释写「`skipDirs` 里的
     *   子目录整个跳过」—— 那是**虚构的保证**：该参数从头到尾没参与过任何判断，
     *   唯一的用处是一句 `skipDirs.forEach { }`（空操作）。读者会以为「不在
     *   `skipDirs` 里的子目录会被清理」，从而以为 `failed/` 有配额兜底 ——
     *   实际上它和别的子目录一样完全不受管，上传持续失败时会一直涨。
     *   **已删掉这个死参数**，如实写在这里。
     *
     * ⚠️ 崩溃现场另有 [enforceCrashQuota] 单独把关（8MB / 20 份）；
     *   `pending/` 靠「上传成功即出队」收敛；**`failed/` 目前没有任何上限**。
     *
     * ⚠️ **`device-id` 必须豁免，否则它必然第一个被删**（2026-10-14 对抗式审查查出）：
     *   本函数按 `lastModified()` **升序**删，而 `device-id` 是**安装期写一次、
     *   此后再不 touch** 的文件 —— 它的 mtime 永远是全目录最老的。真机 `adb stat`
     *   实测它比第二名早 **5994 秒**（`device-id` mtime=1791919580，
     *   `web.log`=1791925574）。也就是说只要配额触发，**第一个被删的就是它**，
     *   而本文件 L333 的 KDoc 明明写着「不删 `device-id`」—— 文档与实现直接分叉。
     *   后果：服务器侧同一台机器变成 N 台设备，崩溃上报无法关联。
     *   代价极低（36 字节，删它省不下任何配额），所以直接豁免。
     *
     * ⚠️ **降不回配额时要留痕。** `app.log`/`engine.log`/`web.log` 三个正在被写的
     *   文件不能删（见下），若它们单独就超过 [TOTAL_QUOTA_BYTES]，循环会把**其它
     *   所有文件删光**也降不回配额，然后静默退出。原来那种「删完就走」的写法让
     *   分析者只看到「文件莫名少了一堆」，看不出是配额收敛失败。
     */
    private fun enforceDirQuota(d: File, quota: Long) {
        val files = d.listFiles() ?: return
        val candidates = files.filter { it.isFile }.sortedBy { it.lastModified() }
        // `total` 含全部文件（配额确实是目录配额），但删除只对可删的那些动手。
        var total = candidates.sumOf { it.length() }
        if (total <= quota) return
        val before = total
        for (f in candidates) {
            if (total <= quota) break
            // 匿名指纹：删了它这台机器在服务器侧就变成另一台设备（见上面的 ⚠️）。
            if (f.name == DEVICE_ID_FILE) continue
            // 内存里正在写的那三个文件不能删 —— 删了 writer 会写到已 unlink 的
            // inode 上，日志从此静默消失（文件系统里看不到，但也不报错）。
            if (f.name == APP_LOG || f.name == ENGINE_LOG || f.name == WEB_LOG) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
        if (total > quota) {
            // 走到了这里说明「可删的都删了还是超标」—— 只可能是那三个活文件自己就超了。
            // 必须说出来：否则现象是「日志文件莫名少了一堆，配额却还是超」。
            AndroidLog.w(
                TAG,
                "日志配额降不回（${before / 1024}KB → ${total / 1024}KB，配额 ${quota / 1024}KB）：" +
                    "可删的文件已删光，剩下的 ${APP_LOG}/${ENGINE_LOG}/${WEB_LOG} 正在被写入、不能删",
            )
        }
    }

    /**
     * 崩溃现场配额：超过 [CRASH_MAX_FILES] 个或 [CRASH_QUOTA_BYTES] 字节时删最老的。
     *
     * ⚠️ 保留**最近**的而不是最早的：一次崩溃循环会产出几十个文件，最新那几个
     * 才是当前问题的现场。
     */
    private fun enforceCrashQuota(crashDir: File) {
        val files = crashDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (f in files) {
            if (total <= CRASH_QUOTA_BYTES && count <= CRASH_MAX_FILES) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                count--
            }
        }
    }

    // ── 脱敏 ──────────────────────────────────────────────────────────────────

    /**
     * 脱敏包装。**所有写入路径都必须过它**（[app] / [engine] / [web] 各自都调了）。
     *
     * ⚠️ 异常时返回原文而不是抛异常：脱敏正则理论上不会失败，但真失败了也
     * 不能让日志把自己搞崩（铁律 ①）。
     */
    private fun safeRedact(s: String): String = try {
        Redactor.redact(s)
    } catch (_: Throwable) {
        s
    }

    // ── 数据结构 ──────────────────────────────────────────────────────────────

    /**
     * 一条日志。`seq` 是进程内序号，用于跨文件对齐与排序。
     *
     * ⚠️ **`thread` 必须在写入时定格，不能在读取时取**（真机踩过，伪造了证据）。
     *   在 `214`/`215` 版之前本类没有这个字段，`tail()` 重新格式化时调的是
     *   `Thread.currentThread().name` —— 于是崩溃现场里那 200 行的线程名全是
     *   **读取者**（`dsh-log-watchdog`），而不是**写入者**。
     *
     *   铁证（同一时间戳、同一 `#1`，两份文件对比）：
     *   - `app.log`（写入时定格）：`... [W] [Boot] #1 main 上次启动未正常结束…`
     *   - 现场文件（读取时取）：`... [W] [Boot] #1 dsh-log-watchdog 上次启动未正常结束…`
     *
     *   后果很具体：崩溃现场的第 3 段是**信息量最大**的一段（「崩之前最后做的那件事」），
     *   而线程名正是判断「谁卡住了」的第一依据。把它写成读取者，等于让分析者
     *   顺着一个**从头到尾没参与过那些日志**的线程去查。
     */
    data class Entry(
        val t: Long,
        val level: Char,
        val tag: String,
        val msg: String,
        val seq: Long,
        val thread: String,
    )

    // ── 文件名（诊断包与工具都按这些名字找） ──────────────────────────────────

    const val APP_LOG = "app.log"
    const val ENGINE_LOG = "engine.log"
    const val WEB_LOG = "web.log"

    /**
     * 安装期生成一次的匿名设备指纹。
     *
     * ⚠️ **这个文件受 [enforceDirQuota] 豁免**（见其注释）—— 它是最老的，
     *   不豁免就必然第一个被删。[LogUploader] 也读它，所以名字只在这里定义一次。
     */
    const val DEVICE_ID_FILE = "device-id"

    private const val TAG = "DSH/LogStore"
}
