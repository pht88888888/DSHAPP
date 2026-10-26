package com.dsh.android.log

import android.util.Log as AndroidLog
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 结构化事件时间线 —— 把「人肉翻日志」变成「一眼看出卡在哪一步」。
 *
 * ## 为什么光有日志不够
 *
 * 几千行日志里真正决定性的只有十几条：「Termux 引导开始/完成」「引擎启动/就绪/退出」
 * 「WebView 开始加载/加载完成/插件激活」。其余都是细节。而这些里程碑现在**散落在
 * 各处**、格式各异、有的还是英文，分析者要自己拼时间线。
 *
 * 本类把这些里程碑抽成**结构化的、机器可读的**一行一个 JSON —— 诊断包里的
 * `timeline.jsonl` 就是它。于是「引擎起来了但界面没加载」这种结论可以直接从
 * 时间线读出来，不必看日志正文。
 *
 * ## 与日志的分工（刻意分开，不是重复）
 *
 * | | 日志（app.log 等） | 事件（timeline.jsonl） |
 * |---|---|---|
 * | 内容 | 过程细节，给人看 | 里程碑，给机器+人看 |
 * | 体量 | 几万行 | 十几行 |
 * | 格式 | 文本 | JSON |
 * | 用途 | 查「具体发生了什么」 | 查「卡在哪一步、耗时多少」 |
 *
 * ## 为什么带 `elapsedMs`
 *
 * 本项目的故障有一大类是**慢**而不是**错**：「启动要 40 秒」「界面加载不出来」
 * 实际是某个步骤卡住了。时间线里带上「距启动多少毫秒」，慢在哪一步一目了然 ——
 * 只看绝对时间戳是看不出这个的。
 */
object EventLog {

    /** 事件文件名。**公开**给 `LogStore.clearAll` 用 —— 清空日志时要一并删掉它。 */
    const val FILE_NAME = "events.jsonl"

    /** 单个事件文件上限。事件很少，1 MB 够存几万条。 */
    private const val MAX_BYTES = 1L * 1024L * 1024L

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var bytes = 0L

    @Volatile
    private var file: File? = null

    /** 落盘是否可用。不可用时事件只进 app.log（降级但信息不丢）。 */
    private val enabled: Boolean get() = writer != null

    /**
     * 打开事件文件。在 [LogStore.init] 之后调用。
     *
     * ⚠️ 追加而不是覆盖：一次崩溃重启后，上一次启动的时间线正是要看的 ——
     * 覆盖掉就永远看不到「上次卡在哪一步」。
     */
    fun init(logDir: File) {
        try {
            if (!logDir.exists()) logDir.mkdirs()
            val f = File(logDir, FILE_NAME)
            file = f
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
            bytes = f.length()
        } catch (t: Throwable) {
            AndroidLog.w(TAG, "EventLog.init 失败（事件只进 app.log）: ${t.message}")
            writer = null
        }
    }

    /**
     * 记一个事件。
     *
     * @param name 事件名，**点分小写**（`engine.ready` / `web.load.done`）。
     *   刻意用英文而不是中文：它是机器读的键，将来做统计/聚合时不用再翻译。
     *   面向用户的说明在日志里另有一条中文。
     * @param fields 附加字段。**值必须是短字符串**（别塞日志正文）。
     */
    fun record(name: String, fields: Map<String, String> = emptyMap()) {
        val t = System.currentTimeMillis()
        val elapsed = try {
            BootState.uptimeMs()
        } catch (_: Throwable) {
            0L
        }
        // 同时进 app.log：事件文件损坏/丢失时，日志里还有一条中文轨迹。
        // ⚠️ 这是**有意的双写**，不是冗余 —— 两者用途不同（见类注释的分工表）。
        val detail = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        LogStore.app('I', "Event", "$name$detail (t+${elapsed}ms)")

        /*
         * ⚠️ 这个 `w` 是**锁外**取的，所以进锁后必须再核一次引用有没有变。
         *
         * 时序：本线程在锁外取到非空 `w` → 另一线程进 [rotate]（它在
         * `synchronized(this)` 内被调用，可重入）把这条流 close 掉并置空
         * → 本线程拿到锁，对着**已关闭的流**写 ⇒ IOException。
         *
         * 那个异常会被下面的 catch 静默吞掉（事件写不进去不致命），
         * 但**这一条事件就悄悄丢了**，而 `events.jsonl` 是崩溃现场
         * crash 现场文件与诊断包 `timeline.jsonl` 的数据源 ——
         * 丢的往往是崩溃前最后几条，正是最需要的那几条。
         *
         * 与 `LogStore.writeApp` / `writeAsync` 里同样的守卫保持一致。
         */
        val w = writer ?: return
        try {
            val o = JSONObject().apply {
                put("t", t)
                put("elapsedMs", elapsed)
                put("name", name)
                put("bootId", BootState.bootId)
                if (fields.isNotEmpty()) {
                    put("fields", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
                }
            }
            // ⚠️ **事件行也必须过脱敏网**（2026-10-14 对抗式审查查出）。
            //   `Redactor` 的类注释写着「日志系统有多个写入方（LogStore、EventLog、
            //   CrashRecorder、EnvironmentSnapshot）……收敛到一处、由所有写入方共用」，
            //   但在那次审查之前，`Redactor.redact` 在全仓库**只有 `LogStore.safeRedact`
            //   一个调用点** —— 也就是说上面那句承诺里点名的四个写入方，只有第一个
            //   真的调了。现在已补上本处与 `CrashRecorder.writeScene`（reason/stack）。
            //   ⚠️ `EnvironmentSnapshot` 至今**仍未**接入：它收集的是设备/系统/内存
            //   等结构化字段（不含用户文本），风险低，但「四个写入方共用」这句话对它
            //   仍不成立 —— 要改那句话得连它一起改，别只改代码不改文档。
            //
            //   `EventLog` 这条通道的出口有两个，都会把**原文**带出设备：
            //   ① `DiagnosticBundle.buildTimeline()` 把 `readAll()` 的结果原样拼成
            //      包里的 `timeline.jsonl`（见那里的 `joinToString`，零处理）；
            //   ② `CrashRecorder.writeScene()` 把 `readAll().takeLast(20)` 经
            //      `describe()` 写进 `crash/*.txt`，再被 `writeCrashes()` 收进包。
            //   而调用方往里塞的是**异常 message 与任意字符串**（如
            //   `EventLog.record("termux.failed", mapOf("err" to (t.message ?: ...)))`），
            //   一条含 `?token=` 或 `sk-…` 的 message 就会原样进包出设备。
            //
            //   实测：65 个真机诊断包里 `?token=` 出现 692 处、**全部**是
            //   `***REDACTED***`，0 处原文 —— 但那是 `LogStore` 那三条通道
            //   （app/engine/web）挡住的，**不是** EventLog 挡住的；EventLog 只是
            //   恰好还没被塞进过密钥。这是「缺口存在但尚未被数据触发」，
            //   与「没有缺口」是两回事。
            val line = Redactor.redact(o.toString())
            synchronized(this) {
                // 锁内复核：取到 w 之后、拿到锁之前，流可能已被 rotate 关掉。
                if (w !== writer) return
                w.write(line)
                w.write("\n")
                w.flush()
                // ⚠️ **不能用 `o.length()` 当字节数**（2026-10-14 对抗式审查查出）：
                //   `org.json.JSONObject.length()` 返回的是**键的个数**，不是字符串
                //   长度、更不是 UTF-8 字节数。每条事件固定 4~5 个键，于是每条只记
                //   5~6 —— 而真实一行是 100+ 字节。真机 `events.jsonl` 实测：
                //   21,094 字节 / 182 条，代码累计只有 1032，**低估 20.44 倍**
                //   ⇒ 声明的 1 MB 轮转实际要到 **~20.4 MB** 才触发。
                //   后果不只是文件大：它参与 `LogStore.enforceDirQuota` 的 32MB 总配额，
                //   而且 `readAll()` 会把整个文件读进内存再拼成 `timeline.jsonl`。
                //   现在用真实字节数（与 `LogStore.writeApp` 同口径）。
                bytes += line.toByteArray(Charsets.UTF_8).size.toLong() + 1
                if (bytes >= MAX_BYTES) rotate()
            }
        } catch (_: Throwable) {
            // 事件写不进去不致命（app.log 里那条已经落了）
        }
    }

    private fun rotate() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        val f = file ?: return
        try {
            val old = File(f.parentFile, "$FILE_NAME.1")
            if (old.exists()) old.delete()
            // ⚠️ **必须看 renameTo 的返回值**（2026-10-14 自检查出）。
            //   本文件与 `LogStore.rotateFiles` 同构（先删旧代、再改名），区别在于
            //   这里**紧接着就用 `FileOutputStream(f, false)` 以 truncate 重开** ——
            //   与 `rotateApp` 同理：rename 失败只会丢一代历史，不会退化成坏循环，
            //   所以不必像 `rotateAsync` 那样做降级。
            //   但**必须留痕**：`AGENTS.md` 与插件描述都写着 events.jsonl
            //   「1MB 轮转，留一代」，rename 失败时这句承诺就不成立了，而失败
            //   完全静默（`File.renameTo` 只返回 false、不抛异常）。
            if (!f.renameTo(old)) {
                LogStore.app('W', "EventLog", "events.jsonl 轮转失败（rename 未成功），上一代未归档；本次已截断续写")
            }
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, false), Charsets.UTF_8))
            bytes = 0L
        } catch (_: Throwable) {
        }
    }

    /**
     * 读回事件（诊断包与日志界面用）。
     *
     * ⚠️ 坏行**跳过**而不是整体失败：`.jsonl` 的意义就是「一行坏了不影响别的行」。
     * 若因为一行半截 JSON 就让整个时间线读不出来，那才是真丢信息。
     */
    fun readAll(logDir: File): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (name in listOf("$FILE_NAME.1", FILE_NAME)) {
            val f = File(logDir, name)
            if (!f.isFile) continue
            try {
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        out.add(JSONObject(line))
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /**
     * 时间线的一行人类可读摘要。日志界面与诊断包的 `summary` 用。
     *
     * ⚠️ **单条调用时看不出运行边界** —— 需要铺开一串事件时用 [describeAll]。
     */
    fun describe(o: JSONObject): String {
        val elapsed = o.optLong("elapsedMs", 0L)
        val name = o.optString("name", "")
        val fields = o.optJSONObject("fields")
        val detail = if (fields == null) "" else
            fields.keys().asSequence().joinToString(" ") { "$it=${fields.optString(it)}" }
        return "[+${elapsed}ms] $name ${detail}".trim()
    }

    /**
     * 把一串事件铺成人类可读摘要，**并在运行边界处插分隔标记**。
     *
     * ## 🔴 为什么必须补这一处（2026-10-14 自检发现，同族第 25 例）
     *
     * `elapsedMs` 是**相对本次启动**的耗时（[record] 里取 `BootState.uptimeMs()`），
     * 而 [readAll] 读的是 `events.jsonl` **全量** —— 它跨运行累积，从不按启动切分。
     * 于是「取尾部若干条」这种做法一旦跨过运行边界，`[+Nms]` 就会在中途**突然变小**，
     * 而输出里没有任何东西提示「这里换了一次运行」。
     *
     * 后果是读的人会把多次运行**当成一次**：真机上 `termux.done` 在一个包里出现
     * **29 次**，第一眼就是「一次运行里引导了 29 遍」—— 而实际是这台设备启动过
     * 29 次、每次引导一遍（完全正常）。这个误判我自己就犯过，并且据此把它当成了
     * 一个待查缺陷。
     *
     * 真机实测（服务器 77 个包、209 份崩溃现场）：**171 份（82%）** 的
     * `--- recent events ---` 段跨运行边界，**全部无标记**。
     *
     * `bootId` 每条事件都记着（见 [record]），这里只需把它取出来。前缀取短形式
     * （`b-…f094`）而不是完整值：完整 bootId 是 `b-1791931838773-f094` 这种 21 字符，
     * 20 行都带会把表格挤歪；而这一段里只需要「跟上一行是不是同一次运行」这一个
     * 信息，末 4 位足够区分。
     *
     * ⚠️ **只在边界处打印**，不是每行都打 —— 否则 20 行里重复 20 次同一前缀，
     * 噪音盖过内容。边界标记就该出现在边界上。
     *
     * @param events 已按时间顺序排好的事件（通常是 [readAll] 的尾部若干条）。
     */
    fun describeAll(events: List<JSONObject>): String {
        if (events.isEmpty()) return ""
        val sb = StringBuilder()
        var prevBoot: String? = null
        for (e in events) {
            val boot = e.optString("bootId", "")
            if (boot != prevBoot) {
                if (prevBoot == null) {
                    sb.append("── 运行 ").append(shortBootId(boot)).append(" ──\n")
                } else {
                    sb.append("── 运行边界：以上属于 ").append(shortBootId(prevBoot))
                        .append("，以下属于 ").append(shortBootId(boot))
                        .append("（elapsedMs 各自从 0 重新计）──\n")
                }
                prevBoot = boot
            }
            sb.append(describe(e)).append('\n')
        }
        return sb.toString()
    }

    /**
     * `b-1791931838773-f094` → `b-…f094`。
     *
     * ⚠️ 空值时**原样返回空串**，不编一个占位符 —— 编出来的东西会被当成真实
     * 标识读（本族缺陷的典型形态）。
     */
    private fun shortBootId(bootId: String): String =
        if (bootId.length <= 6) bootId else "b-…" + bootId.takeLast(4)

    /**
     * 文件被外部删掉后重开（[LogStore.clearAll] 删了 `events.jsonl` 时调用）。
     *
     * ⚠️ **不重开就等于此后所有事件都写进幽灵文件**（踩过）：[record] 第一行是
     * `val w = writer ?: return` —— 引用非空就直接写**旧** writer，永远不会自己
     * 发现文件已被删。Linux 上那个 inode 已经**没有名字**，写入不报错、进程一死
     * 内容就没了，而 `.jsonl` 时间线恰恰是排查「上次卡在哪一步」的主线索。
     *
     * 与 [LogStore.clearAll] 里重开 engineWriter / webWriter 是同一类修复
     * （那两个走惰性创建，这里是显式重开）——三处必须一起做，漏一处就还有一份
     * 日志在清空后失写。
     */
    fun reopen(logDir: File) {
        try {
            synchronized(this) {
                runCatching { writer?.close() }
                writer = null
                val f = File(logDir, FILE_NAME)
                file = f
                writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
                bytes = f.length()
            }
        } catch (t: Throwable) {
            AndroidLog.w(TAG, "EventLog.reopen 失败（此后事件只进 app.log）: ${t.message}")
            writer = null
        }
    }

    fun flushAndClose() {
        try {
            synchronized(this) { writer?.flush() }
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
    }

    private const val TAG = "DSH/EventLog"
}
