package com.dsh.android

import com.dsh.android.log.BootState
import com.dsh.android.log.CrashRecorder
import com.dsh.android.log.DiagnosticBundle
import com.dsh.android.log.DshLog as Log
import com.dsh.android.log.LogContext
import com.dsh.android.log.LogStore
import com.dsh.android.log.LogUploader
import com.dsh.android.log.SafeDateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 日志的**命令信箱** —— 让 Node 进程里的模型工具能读到原生侧的日志。
 *
 * ## 为什么需要它
 *
 * 日志系统的两个入口（设置页分节、启动/崩溃浮层按钮）都是给**人**的。而模型跑在
 * **Node 进程**里：它看不到原生侧的内存环形缓冲，也看不到 `filesDir/logs` 下的
 * `app.log` / 崩溃现场。于是「你看看日志」这句话对模型是空的，它只能猜。
 *
 * 本类补的就是这一段：`dsh-android-logs` 插件把请求写进 `$DSH_HOME/logs-cmd.json`，
 * 这里轮询读走、执行、把结果写回 `$DSH_HOME/logs-result.json`。
 *
 * ## 与 [BubbleCommandBridge] 的关系：同构，但**刻意不共用文件**
 *
 * 协议逐条照抄（自增 id 配对、原子发布、读后即删、粗粒度轮询），因为那套已经被
 * 真机验过，重造没有收益。但两个信箱的**文件必须分开**：
 *
 * 回执是按自增 `id` 配对的，而两个插件跑在两个独立的进程/模块里，各自从 1 开始
 * 计数。共用一对文件时，同一个 `id=5` 会同时存在两条语义不同的请求 ——
 * 工具调用收到的可能是另一个的回执。这类错配的表现是
 * **「AI 说日志里没有这条错误」，而它看起来像真的查过了** —— 比报错危险得多。
 *
 * ## 三条不变量（与 bubble 桥一致）
 *
 * ① **请求带自增 `id`，回执按 id 配对。**
 * ② **先写结果、再删请求。** 反过来会丢回执。
 * ③ **命令文件损坏（写到一半）不是错误。** 插件用「临时文件 + rename」发布，
 *    这里解析失败就下一轮再读，不记错误日志刷屏。
 *
 * ## 轮询间隔为什么是 200ms（比 bubble 的 250ms 略快）
 *
 * 这条通道的调用频率比 bubble 高（模型排查问题时会连读几次），而 200ms 的代价
 * 可以忽略。**不做得更激进**：真要做 inotify 得先确认 app 域能不能 watch
 * 自己的私有目录，为这点延迟不值得。
 *
 * ⚠️ **执行体跑在轮询线程上，不能碰视图。** 这里的四个动作全是文件/内存操作，
 *   唯一碰 `Context` 的是 `export` 里的环境快照，它内部自己兜了异常。
 */
object LogCommandBridge {

    private const val TAG = DshApplication.TAG

    /** 请求文件名。**必须与 `mobile-ui-plugin/dsh-android-logs/lib/index.js` 一致。** */
    private const val CMD_FILE = "logs-cmd.json"

    /** 回执文件名。同上，两边必须一起改。 */
    private const val RESULT_FILE = "logs-result.json"

    /** 轮询间隔。见类注释。 */
    private const val POLL_MS = 200L

    /** 单次 `read` 的返回行数上限。与插件侧的 `MAX_LINES` 一致。 */
    private const val MAX_LINES = 500

    /** 崩溃现场全文的字符上限。与插件侧的 `MAX_CRASH_CHARS` 一致。 */
    private const val MAX_CRASH_CHARS = 40000

    private val fmt = SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-logs-cmd").apply { isDaemon = true }
    }

    private var task: ScheduledFuture<*>? = null

    /** `$DSH_HOME`，由 [start] 传入；未启动时为 null。 */
    @Volatile
    private var home: File? = null

    /**
     * 启动轮询。由 `EngineManager` 在引擎启动前调用一次（幂等）。
     *
     * @param dshHome `$DSH_HOME`（`<filesDir>/home/.dsh`）。不存在时仍然启动 ——
     *   目录会在引擎初始化时被创建，早启动只是多几次空轮询。
     */
    @Synchronized
    fun start(dshHome: File) {
        home = dshHome
        if (task != null) return
        task = scheduler.scheduleWithFixedDelay(::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "日志命令信箱已启动: ${File(dshHome, CMD_FILE).absolutePath}")
    }

    /** 停掉轮询（给测试/重启留一个干净的入口）。 */
    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    /**
     * 读一次信箱。
     *
     * ⚠️ `export` 会在这里**同步**做完整打包（可能几秒）。`scheduleWithFixedDelay`
     *   保证同一时刻只有一个 `pollOnce` 在跑，所以不会堆积；代价是打包期间
     *   `read` 会晚一拍 —— 那可以接受（模型要么在读、要么在导，不会同时）。
     */
    /**
     * 轮询一次日志命令信箱。
     *
     * ⚠️ **整个方法体必须被 try/catch 包住，不能有异常漏出去。**
     *
     * 它由 `scheduler.scheduleWithFixedDelay(::pollOnce, …)` 驱动，而
     * `ScheduledExecutorService` 的语义是：**周期任务一旦抛出未捕获异常，
     * 该任务就被永久终止** —— 后续周期不再执行，也没有任何通知。
     *
     * 于是症状是：这个信箱在某个瞬间之后再无响应，而日志里只有崩溃那一刻
     * 的一行堆栈，之后完全静默。用户看到的是「日志命令突然不管用了」，
     * 实际是轮询线程已经死了，重启 App 才能恢复。
     *
     * 能抛的点都在外部输入路径上（读文件、JSON 解析、落盘、系统调用），
     * 内层即便已经兜了几处，外层仍必须有这一层 —— 漏一处就是永久失效。
     */
    private fun pollOnce() {
        try {
            pollOnceInner()
        } catch (t: Throwable) {
            // 吞掉但**必须留痕**：静默吞等于把问题藏起来。
            Log.w(TAG, "日志命令信箱轮询异常（已兜住，下个周期继续）: ${t.message}", t)
        }
    }

    private fun pollOnceInner() {
        val dir = home ?: return
        val cmd = File(dir, CMD_FILE)
        if (!cmd.isFile) return

        val text = try {
            cmd.readText()
        } catch (t: Throwable) {
            // 读失败（权限/被删）：下一轮再看，不刷日志
            return
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            // ③：写到一半或坏了，下一轮再读
            return
        }

        val id = json.optLong("id", -1L)
        if (id < 0L) {
            // 没有 id 的请求无法配对回执，直接丢弃（否则会卡住一个永远等不到答案的调用）
            Log.w(TAG, "日志命令缺少 id，已丢弃")
            cmd.delete()
            return
        }

        val action = json.optString("action", "summary")
        val out = JSONObject().apply { put("id", id) }
        try {
            when (action) {
                "read" -> doRead(json, out)
                "crashes" -> doCrashes(json, out)
                "export" -> doExport(out)
                else -> doSummary(out)
            }
            out.put("ok", true)
        } catch (t: Throwable) {
            // 单个动作失败**不能**让整条通道哑掉：把原因回给模型，它才能自己改参数。
            Log.w(TAG, "日志命令 $action 执行失败: ${t.message}")
            out.put("ok", false)
            out.put("message", "原生侧执行 $action 失败: ${t.javaClass.simpleName} ${t.message ?: ""}")
        }

        // ②：先写结果，再删请求。
        writeResult(dir, out)
        cmd.delete()
    }

    // ── 四个动作 ──────────────────────────────────────────────────────────────

    /**
     * 读最近日志。
     *
     * ⚠️ **读内存环形缓冲，不读磁盘文件**：磁盘上的 `engine.log` 是异步刷的，
     *   最后几行可能还没落盘 —— 而模型要查的恰恰经常是"刚刚发生的那一条"。
     *   缓冲是同步入队的，最新。代价是最多只有 [LogStore.RING_MAX] 条。
     */
    private fun doRead(req: JSONObject, out: JSONObject) {
        val level = req.optString("level", "all")
        val source = req.optString("source", "all")
        val query = req.optString("query", "")
        val lines = req.optInt("lines", 120).coerceIn(1, MAX_LINES)

        val minLevel = when (level) {
            "error" -> 'E'
            "warn" -> 'W'
            else -> null
        }
        val needle = query.lowercase(Locale.US)

        val matched = LogStore.entries().filter { e ->
            (minLevel == null || LogStore.severity(e.level) >= LogStore.severity(minLevel)) &&
                matchesSource(source, e.tag) &&
                (needle.isEmpty() || e.tag.lowercase(Locale.US).contains(needle) ||
                    e.msg.lowercase(Locale.US).contains(needle))
        }

        val taken = matched.takeLast(lines)
        // ⚠️ 线程名要带上，与 `app.log` 的 `时间 [级别] [tag] #seq 线程 正文` 逐字对齐。
        //   排查「谁卡住了/谁在刷屏」时线程是第一依据；漏掉它，模型只能靠正文猜。
        val text = taken.joinToString("\n") { e ->
            "${fmt.format(Date(e.t))} [${e.level}] [${e.tag}] #${e.seq} ${e.thread} ${e.msg}"
        }

        out.put("source", source)
        out.put("level", level)
        out.put("returned", taken.size)
        out.put("logText", text)

        // ⚠️ 截断必须**说出来**。静默只给最后 N 行，模型会把「没看到」当成「没有」。
        val notes = mutableListOf<String>()
        // ⚠️ **`${matched.size}` 的花括号不能省**（2026-10-14 真机验证查出）。
        //   Kotlin 字符串模板里 `"$matched.size"` 解析成 `$matched`（即列表的
        //   `toString()`）后面跟**字面量** `.size` —— 不是属性访问。
        //   真机实测后果有两层，都不是小事：
        //   ① 回执里那一行变成 `匹配 [Entry(t=…), Entry(t=…), …].size 条`，
        //      把**整个匹配列表的 toString** 倒了进去（几百条 Entry ⇒ 几 KB），
        //      而真正的数字一个都没有 —— 模型据此完全无法判断截断了多少；
        //   ② 这正是本项目记录 15 次的缺陷族：**结论与它描述的总体不符**。
        //   全仓库扫过一遍（`$ident.属性` 形态），**只有这一处**。
        if (matched.size > taken.size) {
            notes += "匹配 ${matched.size} 条，只返回最近 ${taken.size} 条（上限 $lines）。" +
                "要更早的请加 query 或收紧 level/source —— 不要靠加大 lines。"
        }
        if (taken.isEmpty()) {
            notes += "当前缓冲里没有匹配的行。**这不等于没发生过**：日志可能已轮转、" +
                "被限流折叠，或该故障根本没留下日志（例如进程被系统直接杀掉）。" +
                "结论请写成「日志里没找到」。"
        }
        if (LogStore.throttledTotal > 0) {
            notes += "本次运行已折叠 ${LogStore.throttledTotal} 条重复行（1 秒内同 tag 同内容超过 20 次）。" +
                "高频重复的报错只留了前 20 条。"
        }
        // ⚠️ **`droppedAsync` 必须一起说**：它是**另一个**丢失渠道 —— 异步队列
        //   （engine/web 输出）满时直接丢，与上面的限流折叠成因不同、数字也不同。
        //   `DiagnosticBundle` 的 `log-completeness` 一直是两者都报，而这里
        //   只报限流 ⇒ 同一次运行在两条通道上给出不同结论。
        //   漏报的后果与上一段同：模型会把「缓冲里没有」当成「没发生过」，
        //   而实际是队列满丢的（engine.stdout 刷屏时最容易发生）。
        if (LogStore.droppedAsync > 0) {
            notes += "本次运行另有 ${LogStore.droppedAsync} 条因异步队列满被直接丢弃" +
                "（引擎/网页输出刷屏时发生，与上面的限流折叠是两回事）。"
        }
        // ⚠️ **`source:"crash"` 必须显式纠正，不能默默按 `all` 处理**（2026-10-14 自检查出）。
        //   插件侧 `SOURCES` 收了 `'crash'`，schema 的 description 也写着
        //   `"crash" = crash scenes`（`mobile-ui-plugin/dsh-android-logs/lib/index.js`）。
        //   但**本动作读的是内存 ring，里面根本没有现场** —— [matchesSource] 让
        //   `"crash"` 落进 `else -> true`，与 `"all"` 完全等价。
        //   于是模型按插件文档选了 `source:"crash"`，拿回来的是**全部日志**，
        //   而它以为自己在看现场 ⇒ 会得出「现场里没有这个错误」这类**凭空结论**。
        //   这正是本项目已记录 15 次的缺陷族：结论与它描述的总体不符。
        //
        //   两条修法里选了「如实纠正」而不是「改插件枚举」：把 `'crash'` 从枚举里
        //   删掉会让老调用直接报参数非法（那是个**断崖**），而纠正只加一句话，
        //   既保留兼容、又把事实说清楚 —— 并且顺手把模型引到真正能读现场的那条路
        //   （`action:"crashes"` + `crashFile`，那里给的是**全文**）。
        if (source == "crash") {
            notes += "⚠️ `source:\"crash\"` **不返回崩溃现场**：本动作读的是内存缓冲，" +
                "现场是独立文件、不在缓冲里，所以这里的筛选与 `\"all\"` 等价，" +
                "返回的是全部来源的日志。要看现场请用 `action:\"crashes\"`" +
                "（先不带 crashFile 列一份，再带文件名读全文）。"
        }
        if (notes.isNotEmpty()) out.put("truncatedNote", notes.joinToString("\n"))
    }

    /** 来源筛选。`crash` 不在缓冲里（现场是独立文件），由 [doCrashes] 负责。 */
    private fun matchesSource(source: String, tag: String): Boolean = when (source) {
        "engine" -> tag == "engine"
        "web" -> tag == "web"
        "app" -> tag != "engine" && tag != "web"
        // "crash" 与 "all" 在这里等价：崩溃现场是独立文件，`read` 不虚拟它们。
        // 想让模型看现场就让它用 action:"crashes" —— 那才是能给出全文的那条路。
        else -> true
    }

    /** 列出崩溃现场，或读某一份的全文。 */
    private fun doCrashes(req: JSONObject, out: JSONObject) {
        val scenes = CrashRecorder.listScenes()
        val arr = JSONArray()
        for (f in scenes) {
            arr.put(
                JSONObject().apply {
                    put("file", f.name)
                    put("hardCrash", CrashRecorder.isHardCrash(f))
                    put("sizeBytes", f.length())
                    put("atText", fmt.format(Date(f.lastModified())))
                },
            )
        }
        out.put("scenes", arr)

        val want = req.optString("crashFile", "").trim()
        if (want.isEmpty()) return

        // ⚠️ 只认文件名，不认路径。这个值来自模型，而下面要拿它拼 `crash/<name>`；
        //   放行 `../` 就等于给模型一个任意文件读取口。
        if (want.contains('/') || want.contains('\\') || want.contains("..")) {
            throw IllegalArgumentException("crashFile 必须是纯文件名: $want")
        }
        val file = scenes.firstOrNull { it.name == want }
            ?: throw IllegalArgumentException("找不到现场文件 $want（先不带 crashFile 列一次）")

        val text = try {
            file.readText()
        } catch (t: Throwable) {
            throw IllegalStateException("读取现场失败: ${t.message}")
        }
        out.put(
            "sceneText",
            if (text.length > MAX_CRASH_CHARS) {
                text.substring(0, MAX_CRASH_CHARS) +
                    "\n\n…（现场超过 ${MAX_CRASH_CHARS / 1000} K 字符，已截断）"
            } else {
                text
            },
        )
    }

    /**
     * 一眼结论。**便宜**（只读内存缓冲 + 几个文件的元信息），所以模型可以每次都先调它。
     *
     * ⚠️ 措辞必须与 [BootState.Previous.describe] 的分级一致：
     *   **「非正常结束」不是「崩溃」的同义词** —— 可能是 LMK 回收、ROM 冻结、
     *   用户强停、断电。这里绝不能替它们下「崩溃」的结论。
     */
    private fun doSummary(out: JSONObject) {
        val entries = LogStore.entries()
        val warn = entries.count { it.level == 'W' }
        val error = entries.count { it.level == 'E' }
        val scenes = CrashRecorder.listScenes()
        val hardCrashes = scenes.count { CrashRecorder.isHardCrash(it) }
        val logDir = LogStore.dirOrNull()
        // ⚠️ **`pending` 计数不能只认 `LogStore.dirOrNull()`**（2026-10-14 自检发现）。
        //   `LogStore.init` 失败（`dir = null`）时它返回 null，于是上面那行会把
        //   `pending` 报成 **0** —— 而 `pending/` 目录和里面的 zip 是**磁盘上的东西**，
        //   与 `LogStore.dir` 毫无关系。真机复现形态① 时 `logs/pending/` 明明还在。
        //   报 0 的后果很具体：模型读到「待上传 0」就不会去追问那批包，
        //   而它们正卡在磁盘上等着（`LogUploader.configure` 单独持有 logDir）。
        //   退回用 `LogContext.filesDirOrNull()` 拼路径 —— 它由更早的
        //   `LogContext.install` 装上，不依赖 `LogStore.init` 成不成功。
        val dirForDirs = logDir ?: LogContext.filesDirOrNull()?.let { File(it, DshApplication.LOG_DIR) }
        val pending = dirForDirs?.let { LogUploader.pendingCount(it) } ?: 0

        val prev = BootState.previous()
        val lastExit = prev.describe()

        out.put(
            "counts",
            JSONObject().apply {
                put("total", entries.size)
                put("warn", warn)
                put("error", error)
                put("crash", scenes.size)
                put("pending", pending)
                // ⚠️ **这个对象里混了两种总体，必须自己说清楚。**
                //   `total`/`warn`/`error` 数的是内存 ring（[LogStore.entries]），
                //   只覆盖**本次运行**；`crash`/`pending` 数的是磁盘目录
                //   （`crash/`、`pending/`），是**跨多次运行累积**的。
                //   不标注的话，读者会把 `warn: 0` 理解成「这台机器从来没告警过」，
                //   而它其实只说明「这次启动以来没告警」。`headline` 里那一级
                //   （`error > 0`）是带限定的，这里原来没有 —— 同一个数两种口径，
                //   与 `throttleNote`、`log-completeness` 是同族缺陷。
                put(
                    "scope",
                    "total/warn/error = 内存缓冲，仅本次运行（更早的看 app.log）；" +
                        "crash/pending = 磁盘目录，跨多次运行累积",
                )
            },
        )
        out.put("lastExit", lastExit)

        // headline 的优先级：**硬崩溃 > 非硬崩溃现场 > 非正常结束 > 错误数 > 干净**。
        // 崩溃最具体、最能直接指向下一步，所以排最前。
        //
        // ⚠️ **「有现场但不是硬崩溃」必须单独占一级**。原来这一级不存在，于是
        //   现场全是 `engine-start-failed`（非硬崩溃）时会一路落到最后的 else，
        //   报出「本次运行未发现错误，也无崩溃现场」—— 而 `crash` 计数明明是
        //   非零、`crashes/` 里明明有文件。模型据此会直接跳过排查。
        //   真机 22 个 warn 包的现场 100% 是这一类。
        val newest = scenes.firstOrNull { CrashRecorder.isHardCrash(it) }
        out.put(
            "headline",
            when {
                newest != null -> "有 $hardCrashes 份崩溃现场，最近一份 ${newest.name}（${fmt.format(Date(newest.lastModified()))}）"
                scenes.isNotEmpty() -> "有 ${scenes.size} 份非崩溃现场（${CrashRecorder.summarizeKinds(scenes)}），" +
                    "最近一份 ${scenes.first().name}（${fmt.format(Date(scenes.first().lastModified()))}）"
                prev.unclean -> lastExit
                error > 0 -> "内存缓冲里有 $error 条错误（缓冲只覆盖本次运行，更早的请看 app.log）"
                else -> "本次运行未发现错误，也无崩溃现场"
            },
        )

        val notes = mutableListOf<String>()
        if (LogStore.throttledTotal > 0) {
            // ⚠️ **必须限定「本次运行」** —— `throttledTotal` 是 `LogStore` 的进程内
            //   计数器，进程一起来就是 0；而 `app.log` 是跨多次运行累积的。不限定的话
            //   读者会把这个数当成「整个包 / 整份 app.log 被折叠的条数」，从而以为
            //   日志被抽稀的程度就这么点。同一文件的 [doRead] 里那句话是有这个限定的
            //   （「本次运行已折叠 …」），这里原来漏了，两句口径不一致。
            notes += "本次运行已折叠 ${LogStore.throttledTotal} 条重复行（1 秒内同 tag 同内容超过 20 次）" +
                "—— 不要据此断言「某错误从未发生」。⚠️ 这个数只覆盖本次运行；app.log 里" +
                "更早的运行若也被折叠过，只会在其中留下「…被抑制 N 次」的行。"
        }
        // ⚠️ **`droppedAsync` 是另一个丢失渠道，summary 同样不能说漏。**
        //   [doRead] 与 `DiagnosticBundle` 的 `log-completeness` 都报它，
        //   只有 summary 原来不提 ⇒ 模型看 summary 会以为「缓冲 4000 条就是全部」，
        //   而实际可能有一批被队列满丢了（引擎 stdout 刷屏时）。
        if (LogStore.droppedAsync > 0) {
            notes += "本次运行另有 ${LogStore.droppedAsync} 条因异步队列满被直接丢弃" +
                "（引擎/网页输出刷屏时发生，与限流折叠是两回事）—— 它们不在缓冲里。"
        }
        if (pending > 0) {
            notes += "有 $pending 个诊断包等待上传（网络恢复或下次启动时自动重试）。"
        }
        if (notes.isNotEmpty()) out.put("throttleNote", notes.joinToString(" "))
        // ⚠️ **这句话原先在两种失效形态下都是错的**（2026-10-14 真机实验证伪）。
        //   `LogStore.logDirAvailable` = `dir != null`，即 `LogStore.init` 有没有
        //   成功挂上目录；而"落盘不可用、仅内存"这个结论只在**目录也没挂上**时成立。
        //   真机复现（把 `logs/app.log` 换成一个目录 ⇒ `init` 抛 EISDIR 失败）：
        //   此时 `LogStore.dir` 是 null、`app.log`/`engine.log`/`web.log` 确实不写，
        //   但**磁盘上照旧在长东西** —— 同一时刻实测：
        //   - `events.jsonl` 3,593 B，其中 **6 行属于本次启动**（bootId 对得上）；
        //   - `crash/` 里 13,777 B 的 `20261014-061130-renderer-crash.txt`；
        //   因为 `EventLog.init` 与 `CrashRecorder.install` 各自持有 **自己的**
        //   `logDir`，根本不看 `LogStore.dir`。
        //   而这句话会说"落盘不可用、仅内存"⇒ 读者不去看 `events.jsonl` 与 `crash/`，
        //   恰恰丢掉唯一还完整的证据（时间线 + 现场文件）。
        out.put(
            "message",
            buildString {
                append("日志目录: ")
                append(if (LogStore.logDirAvailable) logDir?.absolutePath ?: "?" else "（未挂上，app.log/engine.log/web.log 本次不落盘）")
                append("；缓冲 ${entries.size} 条（上限 ${LogStore.RING_MAX}）")
                if (!LogStore.logDirAvailable) {
                    append("。⚠️ 「未挂上」只针对 app.log/engine.log/web.log —— events.jsonl 与 crash/ " +
                        "由 EventLog / CrashRecorder 各自持有目录，仍照常落盘，别跳过它们")
                } else if (!LogStore.appLogEnabled) {
                    append("。⚠️ app.log 句柄已关闭（连续 3 次写失败），本次此后不再写 app.log；" +
                        "engine.log / web.log / events.jsonl / crash/ 仍照常落盘")
                }
                append("。")
            },
        )
    }

    /**
     * 生成诊断包。
     *
     * ⚠️ `reason` 固定 `"manual"`：这条路的触发者是模型/用户，不是崩溃路径。
     *   崩溃路径的包由 [com.dsh.android.log.LogUploader.collectCrashScenes] 在
     *   **下次启动时**补打包（崩溃进程只有几秒预算，做不了打包这种重活）。
     */
    private fun doExport(out: JSONObject) {
        // ⚠️ **不能只认 `LogStore.dirOrNull()`**（2026-10-14 真机复现后修）。
        //   `LogStore.init` 失败（`dir = null`）时它返回 null，于是这里直接抛
        //   「日志落盘不可用，无法生成诊断包」、**一个包都不打** —— 而真机实测
        //   那个状态下磁盘上明明有料：`events.jsonl` 里 6 行属于本次启动、
        //   `crash/` 里躺着 13,777 B 的现场文件。打一个包出来完全做得到，
        //   只是里面没有 `app.log`/`engine.log`/`web.log` 三样。
        //   拒绝生成等于「最需要取证的时候恰好不给取证」，而且理由是**假的**
        //   （落盘并非不可用）。所以退到 `LogContext.filesDirOrNull()` 拼路径 ——
        //   它由更早的 `LogContext.install` 装上，不依赖 `LogStore.init` 成不成功。
        val logDir = LogStore.dirOrNull()
            ?: LogContext.filesDirOrNull()?.let { File(it, DshApplication.LOG_DIR) }
            ?: throw IllegalStateException("拿不到日志目录（LogContext 未安装），无法生成诊断包")
        val outDir = File(logDir, "export")
        val bundle = DiagnosticBundle.build(logDir, outDir, "manual")
            ?: throw IllegalStateException("诊断包生成失败（见 app.log）")

        out.put("bundlePath", bundle.absolutePath)
        out.put("bundleBytes", bundle.length())

        // ⚠️ 用 `LogStore.logDirAvailable`（而不是 `logDir == null`）如实告诉模型
        //   这个包**少了哪几样**。不写的话，模型拿到一个「能打开但 app.log 是空的」
        //   的包，会以为「这台机器没报过错」—— 而实际是那三路本次根本没落盘。
        if (!LogStore.logDirAvailable) {
            out.put(
                "message",
                "⚠️ 本包是在「LogStore 目录未挂上」的状态下打的：不含 app.log / engine.log / web.log" +
                    "（本次运行这三路都没落盘）。events.jsonl（时间线）与 crash/（崩溃现场）仍在包内。",
            )
        }

        // ⚠️ 额外复制一份到公共目录并把路径告诉模型：私有目录里的包**用户拿不到**
        //   （文件管理器进不去 `/data/data/`），而这条路存在的意义就是让用户能把
        //   包发出来。模型拿到这个路径就能直接告诉用户去哪儿找。
        runCatching {
            val pubDir = LogContext.publicExportDirOrNull()
            if (pubDir != null && pubDir.absolutePath != bundle.parentFile?.absolutePath) {
                val target = File(pubDir, bundle.name)
                bundle.copyTo(target, overwrite = true)
                out.put("sharedPath", target.absolutePath)
            }
        }

        // 把包内的自动结论摘要给模型 —— 让它不必再拆一次 zip 才知道结论。
        // 失败不影响导出本身（包已经在了），所以单独兜住。
        runCatching {
            ZipFile(bundle).use { zip ->
                val entry = zip.getEntry("diagnosis.json") ?: return@use
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                out.put("diagnosisHeadline", DiagnosticBundle.summarize(text))
            }
        }
    }

    // ── 回执 ──────────────────────────────────────────────────────────────────

    /**
     * 写回执。失败**不重试** —— 插件超时后会自己用 `action:"summary"` 复查，
     * 而那正是我们想要的语义（宁可让模型多查一次，也不要它拿一个假成功）。
     */
    private fun writeResult(dir: File, out: JSONObject) {
        try {
            val target = File(dir, RESULT_FILE)
            // 原子发布：插件可能正好读到这里，半个 JSON 会让它那一轮白等。
            val tmp = File(dir, "$RESULT_FILE.tmp")
            val text = out.toString()
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入日志命令回执失败: ${t.message}")
        }
    }
}
