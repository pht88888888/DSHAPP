package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **受管作业账本** —— 记录「我启动了什么」，供崩溃后对账。
 *
 * ## 为什么需要它
 *
 * 设计说明 §6.5.2：
 *
 * > 用受管作业账本、启动身份（含可用的进程出生标识）、PTY/进程组与权限代理
 * > 停止回执对账；覆盖 setsid、双重 fork、宿主死亡与 PID 复用。
 * > **不能核验的进程不得按名称误杀。**
 *
 * 没有账本的话，「引擎有没有停干净」这个问题只能靠猜：
 *
 * | 猜法 | 问题 |
 * |---|---|
 * | `ps \| grep node` | 误杀用户的 node（比如 Termux 里别的项目） |
 * | 记住 PID | PID 会被复用，杀错进程 |
 * | 什么都不查 | 修复时旧进程还在写文件 |
 *
 * 账本的作用是：**只对我记录过的 PID 做判断**，且用「出生标识」确认它没被复用。
 *
 * ## ⚠️ 核心设计：出生标识（starttime）
 *
 * PID 复用是真实风险 —— Android 上尤其快。所以每条记录除了 PID，还存
 * `/proc/<pid>/stat` 的 starttime。判断「这个 PID 还是不是我启动的那个」时，
 * **两个都要对上**。
 *
 * 这与 [ManageState.Snapshot.isSameProcess] 是同一套手法，但对象不同：
 * - 那里判断「写状态的是不是当前进程」
 * - 这里判断「账本里的 PID 还是不是当初那个作业」
 *
 * ## ⚠️ 另一个关键约束：不按名称杀
 *
 * 文档明确「不能核验的进程不得按名称误杀」。所以本类：
 * - **只提供判断，不提供 kill**
 * - 核验失败（PID 复用、/proc 读不到）时如实报告「无法确认」
 * - 调用方拿到「无法确认」时必须走只读恢复，而不是猜一个去杀
 */
object ManageLedger {

    private const val TAG = "DSH"

    private const val LEDGER_FILE = "manage-ledger.json"

    /** 账本最多记多少条。超了淘汰最老的 —— 防止无限增长。 */
    private const val MAX_ENTRIES = 64

    /**
     * 作业类型。
     *
     * ⚠️ 只列**当前真实存在**的类型。设计说明提到 PTY/代理作业，
     * 但当前没有独立管理它们，所以不列 —— 列了没有对应代码会误导。
     */
    enum class Kind(val id: String) {
        /** 引擎主进程（`node bin.js`）。 */
        ENGINE("engine"),

        /** 引擎拉起的子进程（bash / pnpm 等）。 */
        CHILD("child"),
        ;

        companion object {
            fun of(id: String?): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 一条作业记录。
     *
     * @param pid 启动时的 PID
     * @param startMs 出生标识（`/proc/<pid>/stat` 的 starttime 换算成毫秒）。
     *   `0` = 读不到 —— 此时**无法核验**，调用方不能据此杀进程。
     * @param startedAt 记录时刻（毫秒）
     * @param label 人可读的标签（日志与诊断用）
     */
    data class Entry(
        val pid: Int,
        val startMs: Long,
        val kind: Kind,
        val label: String,
        val startedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("pid", pid)
            put("startMs", startMs)
            put("kind", kind.id)
            put("label", label)
            put("startedAt", startedAt)
        }

        companion object {
            fun from(o: JSONObject): Entry? {
                val pid = o.optInt("pid", 0)
                if (pid <= 0) return null
                return Entry(
                    pid = pid,
                    startMs = o.optLong("startMs", 0),
                    kind = Kind.of(o.optString("kind")) ?: Kind.CHILD,
                    label = o.optString("label", ""),
                    startedAt = o.optLong("startedAt", 0),
                )
            }
        }
    }

    /**
     * 核验结果。
     *
     * ⚠️ 三态而不是布尔：「无法确认」与「确认已死」的处置完全不同。
     * 把两者合并成一个 `false` 会导致「查不到就当成死了」—— 而文档禁止这种猜测。
     */
    enum class Liveness(val id: String) {
        /** 确认还活着（PID 与出生标识都对上）。 */
        ALIVE("alive"),

        /** 确认已死（PID 不存在，或出生标识不符 = PID 被复用）。 */
        DEAD("dead"),

        /** **无法确认**（记录里没有出生标识，或 `/proc` 不可读）。 */
        UNKNOWN("unknown"),
        ;
    }

    private fun ledgerFile(context: Context): File =
        File(File(context.filesDir, "manage"), LEDGER_FILE)

    /**
     * 从 [Process] 取出 PID。
     *
     * ## 为什么必须反射
     *
     * `Process.pid()` 是 **Java 9** 才加的 API，而 Android 的
     * `java.lang.Process` 实现（`java.lang.ProcessImpl`）在 API 35 上仍然
     * 没有公开的 `pid()` —— 直接调用编译能过（编译用的是 JDK 17 的接口），
     * 但运行时会 `NoSuchMethodError`。
     *
     * 所以走反射读私有字段 `pid`。读不到返回 `-1`。
     *
     * ⚠️ **返回 -1 时不要登记账本** —— 一条没有 PID 的记录毫无用处，
     *   反而会让「账本里有它」这件事看起来像已知状态。
     */
    fun pidOf(process: Process): Int {
        // 先试 Java 9+ 的公开 API（某些 ROM 上可能真的可用）
        runCatching {
            val m = Process::class.java.getMethod("pid")
            val v = m.invoke(process) as? Long
            if (v != null && v > 0) return v.toInt()
        }
        // 退回反射读私有字段
        return runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            val v = f.get(process)
            when (v) {
                is Int -> if (v > 0) v else -1
                is Long -> if (v > 0) v.toInt() else -1
                else -> -1
            }
        }.getOrDefault(-1)
    }

    // ── 写 ──────────────────────────────────────────────────────────────

    /**
     * 登记一个作业。
     *
     * ⚠️ **启动后立刻调用**：进程如果在登记之前就崩了，账本里没有它，
     * 崩溃后对账就看不到这个作业。宁可多记一条（后面核验会发现它已死），
     * 也不要漏记。
     */
    fun record(context: Context, pid: Int, kind: Kind, label: String) {
        if (pid <= 0) return
        val entries = readAll(context).toMutableList()

        // 同一个 PID 重复登记时替换（重启后 PID 可能相同，但那已是新作业）
        entries.removeAll { it.pid == pid }
        entries.add(
            Entry(
                pid = pid,
                startMs = processStartMs(pid),
                kind = kind,
                label = label,
                startedAt = System.currentTimeMillis(),
            ),
        )

        // 淘汰最老的
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)

        writeAll(context, entries)
        EventLog.record(
            "manage.ledger.record",
            mapOf("pid" to pid.toString(), "kind" to kind.id, "label" to label),
        )
    }

    /** 移除一条（进程正常退出后调用）。 */
    fun forget(context: Context, pid: Int) {
        val entries = readAll(context).toMutableList()
        if (entries.removeAll { it.pid == pid }) {
            writeAll(context, entries)
        }
    }

    /** 清空账本（对账完成后，确认干净时调用）。 */
    fun clear(context: Context) {
        writeAll(context, emptyList())
    }

    // ── 读 / 核验 ───────────────────────────────────────────────────────

    fun readAll(context: Context): List<Entry> {
        val f = ledgerFile(context)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Entry.from(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "作业账本损坏，按空处理: ${t.message}")
            EventLog.record("manage.ledger.corrupt", mapOf("reason" to (t.message ?: "?")))
            emptyList()
        }
    }

    /**
     * 核验一个作业现在是否还活着。
     *
     * ⚠️ **这是本类唯一该被用来判断「能不能杀」的入口**。
     * 调用方拿到 [Liveness.UNKNOWN] 时必须走只读恢复，不能猜。
     */
    fun check(entry: Entry): Liveness {
        if (entry.startMs == 0L) {
            // 记录时就读不到出生标识 —— 无法核验
            return Liveness.UNKNOWN
        }
        val nowStart = processStartMs(entry.pid)
        if (nowStart == 0L) {
            // 现在读不到：要么进程没了，要么 /proc 不可读
            // ⚠️ 区分这两者需要能读 /proc —— 读不到就如实说「不知道」
            return if (File("/proc/${entry.pid}").exists()) Liveness.UNKNOWN else Liveness.DEAD
        }
        return if (nowStart == entry.startMs) Liveness.ALIVE else Liveness.DEAD
    }

    /**
     * 核验全部作业。
     *
     * @return 每条记录的核验结果。调用方按结果决定：全 DEAD 才算干净；
     *   有任何 ALIVE 或 UNKNOWN 都不能进写修复。
     */
    fun checkAll(context: Context): List<Pair<Entry, Liveness>> =
        readAll(context).map { it to check(it) }

    // ── 进程出生标识 ────────────────────────────────────────────────────

    /**
     * 读某个 PID 的出生标识（`/proc/<pid>/stat` 第 22 项 starttime 换算成毫秒）。
     *
     * 读不到返回 0 —— **不猜**。
     *
     * ⚠️ 与 [ManageState] 里同名函数逻辑一致，但这里按 **pid 参数**读，
     * 那边读的是当前进程。刻意不抽公共函数：两处的错误处置不同
     * （那边「读不到=不是同一进程」，这边「读不到=无法核验」）。
     */
    private fun processStartMs(pid: Int): Long {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            // 字段以 comm 结尾的右括号为界 —— comm 里可能有空格，直接 split 会错位
            val afterComm = stat.substring(stat.lastIndexOf(')') + 1).trim()
            val fields = afterComm.split(' ')
            // afterComm 的第 0 项是 state，starttime 是原第 22 项 ⇒ 索引 19
            val startTicks = fields.getOrNull(19)?.toLongOrNull() ?: return 0L

            val btime = File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("btime ") }
                    ?.removePrefix("btime ")?.trim()?.toLongOrNull()
            } ?: return 0L

            val hz = 100L   // Linux USER_HZ 恒为 100
            btime * 1000 + (startTicks * 1000 / hz)
        } catch (t: Throwable) {
            0L
        }
    }

    // ── 持久化 ──────────────────────────────────────────────────────────

    private fun writeAll(context: Context, entries: List<Entry>) {
        val f = ledgerFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        try {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            tmp.writeText(arr.toString())
            runCatching {
                java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
            }
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写作业账本失败: ${t.message}")
        }
    }

    /*
     * ⚠️ **本对象只记录，不执行停止**。
     *
     * 刻意不提供 `kill(pid)`：
     * - 文档要求「不能核验的进程不得按名称误杀」
     * - 提供 kill 会诱导调用方「先杀再说」，而这正是要防的
     * - 真正的停止应该走进程自己那套（Process.destroy），账本只负责核验
     *
     * 未实现（依赖 G2 后续）：
     * - PTY / 代理作业的独立跟踪（当前只有 ENGINE / CHILD 两类）
     * - 进程组与 setsid 场景（当前只记单个 PID，不记进程组）
     * - 双重 fork 的后代（当前不跟踪孙进程）
     * - 「只读恢复的活性出口」（提示用户强制停止 App）—— 那是 UI 层的事
     */
}
