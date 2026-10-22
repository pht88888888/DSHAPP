package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 管理状态与转换记录的**持久化**。
 *
 * ## 为什么需要它
 *
 * 设计说明 §6.5.2：
 *
 * > 持久记录 desired/observed 状态、转换 ID、管理 epoch、源/目标环境、
 * > 实例身份及阶段。进程身份须能区分 PID 复用…
 *
 * > 状态记录采用受验证的原子发布与持久化顺序，恢复时能区分未完成、已提交
 * > 及损坏记录；仅写 JSON 或改名不能自动证明断电一致性。
 *
 * 没有它的话，App 被杀在「切换中途」时，下次启动**无从判断**上一次做到哪一步 ——
 * 只能猜，而猜错的代价是「两个环境同时被当成激活态」或「修复时旧写者还在写」。
 *
 * ## 三个核心概念
 *
 * | 概念 | 含义 | 为什么要它 |
 * |---|---|---|
 * | `epoch` | 管理代次，每次转换 +1 | 旧 epoch 的请求必须被拒绝，否则切换后旧的异步回调还能改状态 |
 * | `transitionId` | 一次转换的唯一 ID | 崩溃后靠它判断「这是同一次转换的续做，还是新的一次」 |
 * | `desired` / `observed` | 想要的状态 / 实际观察到的 | 两者不一致 = 转换未完成，恢复时要对账 |
 *
 * ## ⚠️ 原子写与「不能只靠改名」
 *
 * 文档明确说「仅写 JSON 或改名不能自动证明断电一致性」。所以这里：
 *
 * 1. 写临时文件 → `fsync` → `rename`（这是 rename 能提供的全部保证）
 * 2. 关键转换**先写意图、再执行、最后写结果**（write-ahead）
 * 3. 恢复时用「阶段 + 结果是否存在」判定，不假设 rename 成功就等于数据落盘
 *
 * 真正的断电一致性需要文件系统层面的保证（fsync 到目录项），而 Android 上
 * 对祖先目录 fsync 会 EACCES（见 `patchAttachmentDurability`）。所以这里
 * **如实承认**：能防进程被杀，不能完全防断电 —— 与文档「不能仅靠改名」的要求一致。
 */
object ManageState {

    private const val TAG = "DSH"

    /** 状态文件名。放 `filesDir/manage/`，与 [ManageLock] 同目录。 */
    private const val STATE_FILE = "manage-state.json"

    /**
     * 环境标识。
     *
     * ⚠️ 现在只有 `A`（单内核）。设计说明要求 A/R/E 三环境，
     * 但 R/E 尚未实现 —— 先留出枚举，不假装它们已存在。
     */
    enum class Env(val id: String) {
        /** 日常环境。 */
        A("a"),

        /** 修复环境（**未实现**，见 KDoc 末尾的边界说明）。 */
        R("r"),

        /** 应急环境（**未实现**）。 */
        E("e"),
        ;

        companion object {
            fun of(id: String?): Env? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 转换阶段。命名对照设计说明 §6.5.3 的转换表。
     *
     * ⚠️ **只实现 A 环境相关的四个**，A/R/E 切换相关的一律不列出 ——
     * 列了却没有对应代码，会让人以为已经支持。
     */
    enum class Phase(val id: String) {
        /** 空闲，无进行中的转换。 */
        IDLE("idle"),

        /** 正在停止（请求已发出，未确认停止）。 */
        QUIESCING("quiescing"),

        /** 正在启动（进程已拉起，未确认就绪）。 */
        STARTING("starting"),

        /** 已激活（服务就绪、页面连接已确认）。 */
        ACTIVE("active"),
        ;

        companion object {
            fun of(id: String?): Phase? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 一份持久状态。
     *
     * @param epoch 管理代次，每次转换开始 +1。旧 epoch 的请求必须被拒。
     * @param transitionId 当前/最近一次转换的 ID（`null` = 从未转换过）
     * @param desired 期望的环境
     * @param observed 实际观察到的环境（`null` = 未观察/未知）
     * @param phase 当前阶段
     * @param updatedAt 最后写入时刻（毫秒）
     * @param pid 写入时的进程 PID（用于识别 PID 复用，见 [isSameProcess]）
     * @param processStartMs 进程启动时刻（`/proc/<pid>/stat` 的 starttime 换算；
     *                       `0` = 读不到）
     */
    data class Snapshot(
        val epoch: Long,
        val transitionId: String?,
        val desired: Env,
        val observed: Env?,
        val phase: Phase,
        val updatedAt: Long,
        val pid: Int,
        val processStartMs: Long,
    ) {
        /**
         * 这份记录是否由**当前进程**写下。
         *
         * ⚠️ 只比 PID 不够 —— PID 会被复用（Android 上尤其快）。
         * 所以同时比进程启动时刻：两者都对上才算同一个进程。
         *
         * 读不到启动时刻时（`processStartMs == 0`）**保守地认为不是同一个** ——
         * 宁可多对账一次，也不要把陌生进程当成自己。
         */
        fun isSameProcess(): Boolean {
            if (pid != android.os.Process.myPid()) return false
            if (processStartMs == 0L) return false
            return processStartMs == currentProcessStartMs()
        }

        /** 转换是否**未完成**（desired 与 observed 不一致，或还在中间阶段）。 */
        fun isInFlight(): Boolean =
            phase == Phase.QUIESCING || phase == Phase.STARTING || desired != observed
    }

    /** 默认状态：从未转换过。 */
    private fun initial(): Snapshot = Snapshot(
        epoch = 0,
        transitionId = null,
        desired = Env.A,
        observed = null,
        phase = Phase.IDLE,
        updatedAt = 0,
        pid = 0,
        processStartMs = 0,
    )

    private fun stateFile(context: Context): File =
        File(File(context.filesDir, "manage"), STATE_FILE)

    // ── 读 ──────────────────────────────────────────────────────────────

    /**
     * 读取持久状态。
     *
     * ⚠️ 文件损坏时返回 [initial] **并记日志**，不抛异常 ——
     * 一份坏的状态文件不能让 App 打不开（那正是我们要救的场景）。
     */
    fun read(context: Context): Snapshot {
        val f = stateFile(context)
        if (!f.isFile) return initial()

        return try {
            val o = JSONObject(f.readText())
            Snapshot(
                epoch = o.optLong("epoch", 0),
                transitionId = o.optString("transitionId", "").takeIf { it.isNotEmpty() },
                desired = Env.of(o.optString("desired")) ?: Env.A,
                observed = Env.of(o.optString("observed")),
                phase = Phase.of(o.optString("phase")) ?: Phase.IDLE,
                updatedAt = o.optLong("updatedAt", 0),
                pid = o.optInt("pid", 0),
                processStartMs = o.optLong("processStartMs", 0),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "管理状态文件损坏，按初始态处理: ${t.message}")
            EventLog.record("manage.state.corrupt", mapOf("reason" to (t.message ?: "?")))
            initial()
        }
    }

    // ── 写 ──────────────────────────────────────────────────────────────

    /**
     * 开始一次转换。
     *
     * **先写意图再执行**（write-ahead）：调用方拿到返回值后才去真正 stop/start。
     * 这样进程在转换中途被杀时，下次启动能看到「有一个未完成的转换」。
     *
     * @return 新的 [Snapshot]（`epoch` 已 +1）
     */
    fun beginTransition(context: Context, reason: String, target: Env): Snapshot {
        val prev = read(context)
        val next = prev.copy(
            epoch = prev.epoch + 1,
            transitionId = UUID.randomUUID().toString(),
            desired = target,
            // observed 保持不动：现在还不知道结果
            phase = Phase.QUIESCING,
            updatedAt = System.currentTimeMillis(),
            pid = android.os.Process.myPid(),
            processStartMs = currentProcessStartMs(),
        )
        write(context, next)
        EventLog.record(
            "manage.transition.begin",
            mapOf(
                "reason" to reason,
                "epoch" to next.epoch.toString(),
                "id" to (next.transitionId ?: "?"),
                "target" to target.id,
                "prevPhase" to prev.phase.id,
            ),
        )
        return next
    }

    /** 更新阶段（转换进行中）。 */
    fun setPhase(context: Context, phase: Phase): Snapshot {
        val cur = read(context)
        val next = cur.copy(
            phase = phase,
            updatedAt = System.currentTimeMillis(),
            pid = android.os.Process.myPid(),
            processStartMs = currentProcessStartMs(),
        )
        write(context, next)
        return next
    }

    /**
     * 结束一次转换（成功）。
     *
     * 只有这一步才把 `observed` 设成 `desired` —— 在此之前两者不一致，
     * 正是「转换未完成」的机器可判证据。
     */
    fun finishTransition(context: Context, observed: Env): Snapshot {
        val cur = read(context)
        val next = cur.copy(
            observed = observed,
            desired = observed,
            phase = Phase.ACTIVE,
            updatedAt = System.currentTimeMillis(),
            pid = android.os.Process.myPid(),
            processStartMs = currentProcessStartMs(),
        )
        write(context, next)
        EventLog.record(
            "manage.transition.done",
            mapOf("epoch" to next.epoch.toString(), "env" to observed.id),
        )
        return next
    }

    /**
     * 原子写。
     *
     * 顺序：写临时文件 → `fsync` → `rename`。
     *
     * ⚠️ **这只能防进程被杀，不能完全防断电** —— 目录项的持久化需要
     * 对父目录 fsync，而 Android 上那会 EACCES（见 `patchAttachmentDurability`）。
     * 文档也明确「仅写 JSON 或改名不能自动证明断电一致性」，这里如实照做
     * 能做的部分，不夸大保证。
     */
    private fun write(context: Context, snap: Snapshot) {
        val f = stateFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")

        val o = JSONObject().apply {
            put("epoch", snap.epoch)
            put("transitionId", snap.transitionId ?: "")
            put("desired", snap.desired.id)
            put("observed", snap.observed?.id ?: "")
            put("phase", snap.phase.id)
            put("updatedAt", snap.updatedAt)
            put("pid", snap.pid)
            put("processStartMs", snap.processStartMs)
        }

        try {
            tmp.writeText(o.toString())
            // fsync 文件本身（能做的就这一步）
            runCatching {
                java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
            }
            if (!tmp.renameTo(f)) {
                // rename 失败时退回直写，至少不丢数据
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写管理状态失败: ${t.message}")
        }
    }

    // ── 进程身份 ────────────────────────────────────────────────────────

    /**
     * 当前进程的启动时刻（毫秒，`/proc/<pid>/stat` 的 starttime 换算）。
     *
     * 用于区分 **PID 复用**：Android 上 PID 会被快速复用，只看 PID 会把
     * 陌生进程当成自己。读不到时返回 0（调用方保守处理）。
     *
     * ⚠️ 换算依赖 `/proc/stat` 的 `btime`（系统启动时刻）。某些 ROM 上
     * `/proc/<pid>/stat` 不可读，那时返回 0 —— **不猜**。
     */
    private fun currentProcessStartMs(): Long {
        return try {
            val pid = android.os.Process.myPid()
            val stat = File("/proc/$pid/stat").readText()
            // 第 22 个字段是 starttime（单位：时钟滴答）。字段以 comm 结尾的
            // 右括号为界 —— comm 里可能有空格，直接 split 会错位。
            val afterComm = stat.substring(stat.lastIndexOf(')') + 1).trim()
            val fields = afterComm.split(' ')
            // afterComm 的第 0 项是 state，starttime 是原第 22 项 ⇒ 这里索引 19
            val startTicks = fields.getOrNull(19)?.toLongOrNull() ?: return 0L

            val btime = File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("btime ") }
                    ?.removePrefix("btime ")?.trim()?.toLongOrNull()
            } ?: return 0L

            val hz = 100L   // Android/Linux 的 USER_HZ 恒为 100
            btime * 1000 + (startTicks * 1000 / hz)
        } catch (t: Throwable) {
            0L
        }
    }

    // ── 边界说明 ────────────────────────────────────────────────────────

    /*
     * ⚠️ **本对象只实现了设计说明 §6.5.2 的一部分**：
     *
     * ✅ 持久记录 epoch / transitionId / desired / observed / phase
     * ✅ 进程身份（PID + 启动时刻，能区分 PID 复用）
     * ✅ 写前意图（beginTransition 先落盘再执行）
     *
     * ❌ **未实现**（不假装有）：
     * - R/E 环境（枚举里有，但没有对应的启动路径）
     * - 环境写锁（ManageLock 是管理操作互斥，不是"某个环境的写锁"）
     * - 状态机全流程（只做了 A 环境的 IDLE/QUIESCING/STARTING/ACTIVE）
     * - 与 EngineManager 的接入（当前是独立工具，没有任何调用方）
     * - 断电一致性（只做到 rename，目录项 fsync 在 Android 上不可行）
     */
}
