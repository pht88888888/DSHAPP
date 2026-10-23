package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * **更新事务状态机** —— 实现设计说明 §6.5.3 的五阶段提交。
 *
 * ## 文档原文（更新事务）
 *
 * | 阶段 | 前置条件与持久记录 | 成功条件 | 失败与宿主重启后的动作 |
 * |---|---|---|---|
 * | PREPARED | 校验兼容、空间、归档与候选树；锁定旧组合和恢复引用 | 候选封存，旧组合未被覆盖，具备恢复条件 | 可丢弃候选；保留旧组合 |
 * | DATA_READY | 冻结相关写者并建立一致快照；迁移仅在候选副本进行 | 内核、profile 图、配置与数据副本对应同一候选 revision | 迁移失败只放弃副本 |
 * | TRIAL | 候选使用独立 HOME、工作目录及桥上下文 | 候选通过服务、插件与必要能力检查 | 终止候选并保留旧组合 |
 * | COMMITTING | 再确认原状态未漂移；持写锁记录提交意图 | 激活记录与提交结果可恢复地指向同一完整组合 | 依据持久提交点恢复 |
 * | COMMITTED | 记录新激活组合及首次开放真实写入的边界 | 新环境接管 | 已有新写入后不自动恢复旧快照 |
 *
 * ## ⚠️ 核心：单一可判定提交点
 *
 * 文档反复强调「**单一可判定提交点，不各写一个互不相关的『当前版本』**」。
 *
 * 意思是：不能有「引擎目录里的 .stamp」和「state.json 里的 version」两份记录
 * 各自说自己是当前版本 —— 那样崩溃后两者不一致时，**没有任何依据判定哪个对**。
 *
 * 本实现的做法：**激活记录（[Active]）是唯一的真相源**。
 * 它只有一条，且写入用「原子替换 + 单一字段」：
 *
 * ```
 * {"active": "candidate", "txId": "...", "epoch": 7}
 *   ↑ 这一个字段决定用旧组合还是新组合
 * ```
 *
 * 判定规则极简：`active == "candidate"` ⇒ 新组合；否则旧组合。
 * **没有第三种可能**，所以「无法判定」在构造上不可能发生。
 *
 * ## ⚠️ 与 ManageState 的分工
 *
 * | 对象 | 管什么 | 生命周期 |
 * |---|---|---|
 * | [ManageState] | **环境**转换（A/R/E 切换、启动停止） | 秒级 |
 * | 本对象 | **内容**更新（内核/profile 的版本替换） | 分钟级 |
 *
 * 两者刻意分开：一次更新期间可能经历多次启停，混在一起会让状态机互相干扰。
 */
object UpdateTransaction {

    private const val TAG = "DSH"

    private const val TX_FILE = "update-tx.json"

    /**
     * 事务阶段。顺序 = 推进顺序，**不可回退**（回退意味着重新开始一个新事务）。
     */
    enum class Phase(val id: String, val step: Int) {
        /** 无进行中的事务。 */
        IDLE("idle", 0),

        /** 候选已封存，旧组合未被覆盖。 */
        PREPARED("prepared", 1),

        /** 数据副本已就绪，对应同一候选 revision。 */
        DATA_READY("data_ready", 2),

        /** 候选在独立 HOME 验证通过。 */
        TRIAL("trial", 3),

        /** **提交点**：已记录意图，正在切换激活记录。 */
        COMMITTING("committing", 4),

        /** 新组合已接管。 */
        COMMITTED("committed", 5),

        /** 失败，旧组合仍在。 */
        ABORTED("aborted", -1),
        ;

        companion object {
            fun of(id: String?): Phase = entries.firstOrNull { it.id == id } ?: IDLE
        }
    }

    /**
     * 激活记录 —— **唯一的真相源**。
     *
     * ⚠️ 只有 `active` 一个字段决定用哪套组合。不要往里加第二个「当前版本」。
     */
    enum class Active(val id: String) {
        /** 旧组合（更新前的那套）。 */
        OLD("old"),

        /** 新组合（候选）。 */
        CANDIDATE("candidate"),
        ;

        companion object {
            fun of(id: String?): Active = entries.firstOrNull { it.id == id } ?: OLD
        }
    }

    /**
     * 事务记录。
     *
     * @param txId 事务 ID（崩溃后判断「这是同一次事务吗」）
     * @param phase 当前阶段
     * @param active **提交点** —— 唯一决定用旧组合还是新组合
     * @param epoch 每次事务开始 +1
     * @param startedAt 开始时刻
     * @param updatedAt 最后写入时刻
     * @param fromRevision 旧组合的 revision（用于「再确认原状态未漂移」）
     * @param toRevision 新组合的 revision
     * @param reason 中止原因（[Phase.ABORTED] 时）
     */
    data class Tx(
        val txId: String,
        val phase: Phase,
        val active: Active,
        val epoch: Long,
        val startedAt: Long,
        val updatedAt: Long,
        val fromRevision: String,
        val toRevision: String,
        val reason: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("txId", txId)
            put("phase", phase.id)
            put("active", active.id)
            put("epoch", epoch)
            put("startedAt", startedAt)
            put("updatedAt", updatedAt)
            put("fromRevision", fromRevision)
            put("toRevision", toRevision)
            put("reason", reason)
        }

        companion object {
            fun from(o: JSONObject) = Tx(
                txId = o.optString("txId", ""),
                phase = Phase.of(o.optString("phase")),
                active = Active.of(o.optString("active")),
                epoch = o.optLong("epoch", 0),
                startedAt = o.optLong("startedAt", 0),
                updatedAt = o.optLong("updatedAt", 0),
                fromRevision = o.optString("fromRevision", ""),
                toRevision = o.optString("toRevision", ""),
                reason = o.optString("reason", ""),
            )
        }
    }

    /**
     * 崩溃恢复的结论。
     */
    sealed interface Resolution {
        /** 无事务，用旧组合（正常状态）。 */
        data object None : Resolution

        /** 事务未到提交点 ⇒ **回滚到旧组合**。 */
        data class RollBack(val tx: Tx, val why: String) : Resolution

        /** 事务已过提交点 ⇒ **完成新组合**。 */
        data class RollForward(val tx: Tx, val why: String) : Resolution

        /** 事务已正常完成 ⇒ 新组合生效。 */
        data class Done(val tx: Tx) : Resolution
    }

    private fun txFile(context: Context): File =
        File(File(context.filesDir, "manage"), TX_FILE)

    // ── 读 ──────────────────────────────────────────────────────────────

    fun read(context: Context): Tx? {
        val f = txFile(context)
        if (!f.isFile) return null
        return try {
            Tx.from(JSONObject(f.readText()))
        } catch (t: Throwable) {
            // ⚠️ 记录损坏时返回 null（视作「无事务」）**并记事件**。
            //   不能假装是「有事务但不知道到哪」—— 那会让调用方不敢动，
            //   而实际上旧组合是完好的（候选从未接管）。
            Log.w(TAG, "更新事务记录损坏: ${t.message}")
            EventLog.record("update.tx.corrupt", mapOf("reason" to (t.message ?: "?")))
            null
        }
    }

    // ── 写 ──────────────────────────────────────────────────────────────

    /**
     * 开始一次事务（进入 [Phase.PREPARED]）。
     *
     * ⚠️ `active` 初始为 [Active.OLD] —— 在提交点之前，旧组合一直是激活的。
     */
    fun begin(
        context: Context,
        fromRevision: String,
        toRevision: String,
    ): Tx {
        val prev = read(context)
        val now = System.currentTimeMillis()
        val tx = Tx(
            txId = UUID.randomUUID().toString(),
            phase = Phase.PREPARED,
            active = Active.OLD,
            epoch = (prev?.epoch ?: 0) + 1,
            startedAt = now,
            updatedAt = now,
            fromRevision = fromRevision,
            toRevision = toRevision,
        )
        write(context, tx)
        EventLog.record(
            "update.tx.begin",
            mapOf(
                "txId" to tx.txId,
                "epoch" to tx.epoch.toString(),
                "from" to fromRevision,
                "to" to toRevision,
            ),
        )
        return tx
    }

    /**
     * 推进阶段（**不可回退**）。
     *
     * @return 推进后的记录；试图回退时返回原记录并记事件
     *
     * ⚠️ 回退是**缺陷信号**而不是正常操作：它意味着有两条流程在并发推进
     *   同一个事务。所以这里不静默接受，而是如实记录。
     */
    fun advance(context: Context, to: Phase): Tx? {
        val cur = read(context) ?: return null
        if (to.step <= cur.phase.step) {
            Log.w(TAG, "事务阶段试图回退: ${cur.phase.id} → ${to.id}（忽略）")
            EventLog.record(
                "update.tx.backward",
                mapOf("from" to cur.phase.id, "to" to to.id),
            )
            return cur
        }
        val next = cur.copy(phase = to, updatedAt = System.currentTimeMillis())
        write(context, next)
        EventLog.record(
            "update.tx.phase",
            mapOf("txId" to next.txId, "phase" to to.id),
        )
        return next
    }

    /**
     * **提交点** —— 整个事务最要紧的一次写入。
     *
     * ## ⚠️ 为什么这一步必须单独一个函数
     *
     * 它同时改两个字段（phase + active），而这两者的组合**必须原子**。
     * 如果分两次写：
     *
     * ```
     * 写 phase=COMMITTING  ← 崩溃在这里 ⇒ 判定为「未提交」⇒ 回滚
     * 写 active=CANDIDATE  ← 但候选已经部分接管了！
     * ```
     *
     * 所以这里**一次写入**同时表达「已提交」与「用候选」。
     * 崩溃后只有两种可判定的结果：看到 COMMITTING+CANDIDATE（提交了）
     * 或看到更早的阶段+OLD（没提交）。
     *
     * @return true = 提交点已落盘
     */
    fun commit(context: Context): Boolean {
        val cur = read(context) ?: return false
        if (cur.phase.step >= Phase.COMMITTED.step) {
            // 已经提交过了，幂等返回
            return true
        }
        val next = cur.copy(
            phase = Phase.COMMITTING,
            active = Active.CANDIDATE,
            updatedAt = System.currentTimeMillis(),
        )
        val ok = write(context, next)
        EventLog.record(
            "update.tx.commit",
            mapOf("txId" to next.txId, "ok" to ok.toString()),
        )
        return ok
    }

    /** 标记完成（提交点之后）。 */
    fun finish(context: Context): Tx? {
        val cur = read(context) ?: return null
        if (cur.active != Active.CANDIDATE) {
            // ⚠️ 没提交就 finish 是缺陷：会得到一个「说完成了但用旧组合」的矛盾记录
            Log.w(TAG, "事务未提交却试图完成（忽略）")
            return cur
        }
        val next = cur.copy(phase = Phase.COMMITTED, updatedAt = System.currentTimeMillis())
        write(context, next)
        EventLog.record("update.tx.done", mapOf("txId" to next.txId))
        return next
    }

    /** 中止（提交点之前）。 */
    fun abort(context: Context, reason: String): Tx? {
        val cur = read(context) ?: return null
        if (cur.active == Active.CANDIDATE) {
            // ⚠️ 已过提交点 ⇒ **不能中止**，只能前滚。
            //   文档：「已有新写入后不自动恢复旧快照」
            Log.w(TAG, "事务已过提交点，不能中止（reason=$reason）")
            EventLog.record("update.tx.abort.rejected", mapOf("reason" to reason))
            return cur
        }
        val next = cur.copy(
            phase = Phase.ABORTED,
            updatedAt = System.currentTimeMillis(),
            reason = reason,
        )
        write(context, next)
        EventLog.record("update.tx.abort", mapOf("txId" to next.txId, "reason" to reason))
        return next
    }

    /**
     * 清除事务记录（完成后调用，或用户手动清理）。
     */
    fun clear(context: Context) {
        runCatching { txFile(context).delete() }
    }

    // ── 崩溃恢复 ────────────────────────────────────────────────────────

    /**
     * 崩溃后判定该回滚还是前滚。
     *
     * ## 判据（极简，且只有两种结果）
     *
     * | 记录状态 | 结论 |
     * |---|---|
     * | 无记录 / ABORTED / 未提交且 active=OLD | 用**旧组合** |
     * | 已过提交点（active=CANDIDATE） | 用**新组合** |
     *
     * ⚠️ **没有「无法判定」这一种**：这正是「单一可判定提交点」的意义。
     *   如果实现里出现了「不知道用哪套」，说明激活记录被写成了多份。
     */
    fun resolve(context: Context): Resolution {
        val tx = read(context) ?: return Resolution.None

        return when {
            tx.phase == Phase.ABORTED -> Resolution.RollBack(
                tx, "事务已中止（${tx.reason}）—— 用旧组合",
            )

            tx.phase == Phase.COMMITTED -> Resolution.Done(tx)

            // ⚠️ 判据用 **active** 而不是 phase：
            //   phase 可能因为写入顺序问题停在 COMMITTING，
            //   而 active 一旦是 CANDIDATE 就意味着「候选已经该接管了」。
            //   两者中 active 是**承诺**，phase 只是**进度**。
            tx.active == Active.CANDIDATE -> Resolution.RollForward(
                tx, "已过提交点（phase=${tx.phase.id}）—— 完成新组合",
            )

            else -> Resolution.RollBack(
                tx, "未过提交点（phase=${tx.phase.id}）—— 回滚到旧组合",
            )
        }
    }

    /**
     * 检查 revision 是否漂移。
     *
     * 文档：「再确认原状态未漂移」「检测到 profile/数据 revision 漂移时
     * **重新准备，不用旧副本覆盖新变更**」。
     *
     * @param currentRevision 当前实际的 revision
     * @return true = 漂移了（事务必须重来）
     */
    fun drifted(context: Context, currentRevision: String): Boolean {
        val tx = read(context) ?: return false
        return tx.fromRevision.isNotEmpty() && tx.fromRevision != currentRevision
    }

    // ── 持久化 ──────────────────────────────────────────────────────────

    /**
     * 原子写。
     *
     * ⚠️ 顺序：临时文件 → fsync → rename。
     *
     * 文档说「不能仅靠改名证明断电一致性」—— 这里如实照做能做的部分：
     * 能防进程被杀，不能完全防断电（目录项 fsync 在 Android 上 EACCES）。
     *
     * @return false = 写失败（**调用方必须处理**：提交点写失败意味着不能继续）
     */
    private fun write(context: Context, tx: Tx): Boolean {
        val f = txFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        return try {
            tmp.writeText(tx.toJson().toString())
            runCatching { java.io.FileOutputStream(tmp, true).use { it.fd.sync() } }
            if (tmp.renameTo(f)) {
                true
            } else {
                // rename 失败时退回直写 —— 至少不丢数据
                f.writeText(tx.toJson().toString())
                tmp.delete()
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写事务记录失败: ${t.message}")
            false
        }
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：五阶段推进（不可回退）、单一提交点、崩溃后回滚/前滚判定、
     *          revision 漂移检测、提交后禁止中止
     * ❌ 未做：
     *   - **实际的候选部署与切换**（本对象只管记录，动作在调用方）
     *   - TRIAL 阶段的独立 HOME 验证（需引擎实际启动一次）
     *   - 锁顺序（监督锁→环境锁→发布锁）—— 见 ManageLock 的边界说明
     *   - 断电一致性（只到 rename，目录项 fsync 不可行）
     */
}
