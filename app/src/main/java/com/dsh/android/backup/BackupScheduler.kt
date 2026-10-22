package com.dsh.android.backup

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import org.json.JSONObject
import java.io.File

/**
 * **自动备份调度器** —— 「每次成功进入 A 后安排一版备份」。
 *
 * ## 设计说明 §5.3.4 的核心：用 eventId 去重
 *
 * > 用「成功进入 A 的**启动事件 ID**」去重…每个新的正常进入事件安排一版 A 自动备份。
 *
 * > 页面刷新、旋转、短暂断连/重连、普通回前台**不新建事件**；
 * > 一次备份为取得停写点而暂停/重启 A，属于同一事件的**维护续启**，
 * > **绝不再次触发自动备份形成循环**。
 *
 * ## ⚠️ 最容易做出「备份风暴」的地方
 *
 * 备份需要停写 → 停 A → 快照 → 续启 A。而「续启 A」本身又是一次「成功进入」。
 * 如果去重键是「每次成功进入」，那么：
 *
 * ```
 * 备份 → 停 A → 续启 A（成功进入）→ 触发备份 → 停 A → 续启 A → …
 * ```
 *
 * **无限循环**，且每次都在写 SAF —— 用户磁盘会被填满。
 *
 * 所以去重键必须是「**用户发起的进入事件**」（origin=user-entry），
 * 维护续启用 `origin=maintenance-resume` 且**继承原 eventId**，不新建请求。
 *
 * ## 状态机（文档给的）
 *
 * ```
 * 正常 A 激活(eventId, origin=user-entry)
 *   → 未配置：待设密/目录（提示，不标成功）
 *   → 已配置：PENDING → WAITING_FOR_QUIESCE → SNAPSHOT → RESUME_A
 *            → ENCRYPT → VERIFY → PUBLISH → ROTATE → SUCCEEDED
 *   任一安全条件不满足：FAILED / BLOCKED（保留旧包并显示原因）
 * ```
 */
object BackupScheduler {

    private const val TAG = "DSH"

    private const val STATE_FILE = "backup-scheduler.json"

    /**
     * 备份请求的来源。
     *
     * ⚠️ 这两个值的区别是**整个调度器的核心**（见类 KDoc 的循环分析）。
     */
    enum class Origin(val id: String) {
        /** 用户真的打开了一次 App。**只有这个会触发新备份**。 */
        USER_ENTRY("user-entry"),

        /** 备份流程自己为取快照而重启 A 后产生的进入。**继承原 eventId，不新建**。 */
        MAINTENANCE_RESUME("maintenance-resume"),
        ;

        companion object {
            fun of(id: String?): Origin = entries.firstOrNull { it.id == id } ?: USER_ENTRY
        }
    }

    /**
     * 请求状态。
     *
     * ⚠️ `PENDING` 与 `RUNNING` 必须分开：崩溃恢复时，
     * `RUNNING` 说明「上次做到一半」，`PENDING` 说明「还没开始」——
     * 前者不能直接重跑（可能已经在 SAF 上留了半包），后者可以。
     */
    enum class Status(val id: String) {
        /** 已登记，未开始。 */
        PENDING("pending"),

        /** 正在做。 */
        RUNNING("running"),

        /** 成功发布。 */
        SUCCEEDED("succeeded"),

        /** 失败（有明确原因）。 */
        FAILED("failed"),

        /** 受阻（无法安全取得一致点，如写者停不下来）。 */
        BLOCKED("blocked"),

        /** 用户没配密码/目录 —— **明确标为未完成，不伪报成功**。 */
        UNCONFIGURED("unconfigured"),
        ;
    }

    /** 一条备份请求。 */
    data class Request(
        val eventId: String,
        val origin: Origin,
        val status: Status,
        val createdAt: Long,
        val updatedAt: Long,
        val reason: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("eventId", eventId)
            put("origin", origin.id)
            put("status", status.id)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("reason", reason)
        }

        companion object {
            fun from(o: JSONObject): Request? {
                val id = o.optString("eventId", "")
                if (id.isEmpty()) return null
                return Request(
                    eventId = id,
                    origin = Origin.of(o.optString("origin")),
                    status = Status.entries.firstOrNull { it.id == o.optString("status") }
                        ?: Status.PENDING,
                    createdAt = o.optLong("createdAt", 0),
                    updatedAt = o.optLong("updatedAt", 0),
                    reason = o.optString("reason", ""),
                )
            }
        }
    }

    private fun stateFile(context: Context): File =
        File(File(context.filesDir, "backup"), STATE_FILE)

    // ── 登记 ─────────────────────────────────────────────────────────────

    /**
     * 记录一次「成功进入 A」。
     *
     * ## ⚠️ 这是整个调度器唯一的入口，也是唯一能防住备份风暴的地方
     *
     * @param eventId 本次进入事件的 ID
     * @param origin 来源。**只有 [Origin.USER_ENTRY] 会新建备份请求**
     * @return 是否新建了请求（`false` = 不触发，原因见 [reason]）
     */
    fun onEnteredA(context: Context, eventId: String, origin: Origin): Pair<Boolean, String> {
        // ① 维护续启：**永不新建请求**
        //
        // 这是防循环的关键。备份流程自己重启 A 后也会走到这里，
        // 如果它也触发备份，就会形成「备份→重启→备份」的无限循环。
        if (origin == Origin.MAINTENANCE_RESUME) {
            EventLog.record(
                "backup.sched.skip",
                mapOf("why" to "maintenance-resume", "eventId" to eventId),
            )
            return false to "维护续启，不触发备份（防止循环）"
        }

        val state = readState(context)

        // ② 同一个 eventId 只安排一次
        //
        // 文档：「页面刷新、旋转、短暂断连/重连、普通回前台不新建事件」——
        // 那些场景 eventId 不变，所以这里天然挡住。
        val existing = state[eventId]
        if (existing != null && existing.status != Status.FAILED) {
            return false to "该进入事件已处理过（${existing.status.id}）"
        }

        // ③ 登记新请求
        val now = System.currentTimeMillis()
        state[eventId] = Request(
            eventId = eventId,
            origin = origin,
            status = Status.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        writeState(context, state)

        EventLog.record("backup.sched.pending", mapOf("eventId" to eventId))
        return true to "已登记备份请求"
    }

    /** 更新请求状态。 */
    fun setStatus(
        context: Context,
        eventId: String,
        status: Status,
        reason: String = "",
    ) {
        val state = readState(context)
        val cur = state[eventId] ?: return
        state[eventId] = cur.copy(
            status = status,
            updatedAt = System.currentTimeMillis(),
            reason = reason,
        )
        writeState(context, state)

        EventLog.record(
            "backup.sched.${status.id}",
            mapOf("eventId" to eventId, "reason" to reason),
        )
    }

    /**
     * 取出下一个待做的请求。
     *
     * ⚠️ 返回 `RUNNING` 的请求时**不自动重跑** —— 那可能是「上次做到一半」，
     * 直接重跑会在 SAF 上留下重复的包。调用方应先做对账（[reconcile]）。
     */
    fun nextPending(context: Context): Request? {
        val state = readState(context)
        return state.values
            .filter { it.status == Status.PENDING || it.status == Status.RUNNING }
            .minByOrNull { it.createdAt }
    }

    /**
     * 崩溃后对账。
     *
     * 文档：「崩溃后先对账，**不重复发布**」。
     *
     * ⚠️ `RUNNING` 的请求**转成 FAILED 而不是 PENDING**：
     * 上次可能已经在 SAF 上留了半包，重跑会产生重复。
     * 标成 FAILED 让人看到「这次没做成」，比悄悄重试安全。
     *
     * @return 被处理的请求数
     */
    fun reconcile(context: Context): Int {
        val state = readState(context)
        var n = 0
        for ((id, req) in state.toList()) {
            if (req.status == Status.RUNNING) {
                state[id] = req.copy(
                    status = Status.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    reason = "上次备份未完成（进程被杀），已标记失败；请手动重试",
                )
                n++
                EventLog.record("backup.sched.reconciled", mapOf("eventId" to id))
            }
        }
        if (n > 0) writeState(context, state)
        return n
    }

    /** 最近一次成功的时间（UI 显示用）。 */
    fun lastSuccessAt(context: Context): Long =
        readState(context).values
            .filter { it.status == Status.SUCCEEDED }
            .maxOfOrNull { it.updatedAt } ?: 0

    /**
     * 清理太老的记录（保留最近 50 条）。
     *
     * ⚠️ 不清 `PENDING`/`RUNNING` —— 那些是待办，删了就永远不会做了。
     */
    fun prune(context: Context) {
        val state = readState(context)
        if (state.size <= 50) return
        val keep = state.entries
            .sortedByDescending { it.value.updatedAt }
            .take(50)
            .filter { it.value.status == Status.PENDING || it.value.status == Status.RUNNING }
            .map { it.key }
            .toSet() + state.entries
            .sortedByDescending { it.value.updatedAt }
            .take(50)
            .map { it.key }
            .toSet()
        val pruned = state.filterKeys { it in keep }
        if (pruned.size != state.size) writeState(context, pruned)
    }

    // ── 持久化 ───────────────────────────────────────────────────────────

    private fun readState(context: Context): MutableMap<String, Request> {
        val f = stateFile(context)
        if (!f.isFile) return mutableMapOf()
        return try {
            val o = JSONObject(f.readText())
            val out = mutableMapOf<String, Request>()
            o.keys().forEach { k ->
                o.optJSONObject(k)?.let { Request.from(it)?.let { r -> out[k] = r } }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "备份调度状态损坏: ${t.message}")
            EventLog.record("backup.sched.corrupt", mapOf("reason" to (t.message ?: "?")))
            mutableMapOf()
        }
    }

    private fun writeState(context: Context, state: Map<String, Request>) {
        val f = stateFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        try {
            val o = JSONObject()
            state.forEach { (k, v) -> o.put(k, v.toJson()) }
            tmp.writeText(o.toString())
            runCatching { java.io.FileOutputStream(tmp, true).use { it.fd.sync() } }
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写备份调度状态失败: ${t.message}")
        }
    }

    /*
     * ⚠️ **本对象只做调度，不做实际的备份动作**。
     *
     * 它回答三个问题：
     *   ① 这次进入该不该备份（onEnteredA）
     *   ② 有没有待做的请求（nextPending）
     *   ③ 崩溃后哪些请求要标失败（reconcile）
     *
     * 实际流程（冻结 → 停写 → 快照 → 加密 → 发布 → 轮换）由调用方串起来，
     * 因为那需要 EngineManager / ManageGate / BackupCrypto 等一堆依赖。
     *
     * 未实现：
     * - 「等待有界且可见」的写者等待（需要任务状态查询）
     * - 「空间预检」（需要 provider 可用空间）
     * - 与 ManageGate 的完整串联（当前各模块独立，接线在下一轮）
     */
}
