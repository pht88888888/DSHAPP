package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **管理入口的冻结闸门** —— 维护窗口的入口控制。
 *
 * ## 它解决什么
 *
 * 设计说明 §6.5.2：
 *
 * > 切换前**冻结新管理写入**、使旧桥租约与请求 epoch 失效，再请求停止。
 *
 * > 在线协调不足时采用维护窗口：**冻结新入口**，等待或经用户确认停止相关任务，
 * > 停 A 及写者，取得一致性点并对账。
 *
 * 现实场景：用户点了「备份」或「修复」，此时 AI 正在跑一个长任务、插件正在
 * 写文件。如果直接开始快照，拿到的就是**边写边读的不一致状态**。
 *
 * 冻结的作用是：**先把新请求挡在门外，让在飞的写完**，再取快照。
 *
 * ## 三层语义（必须分清）
 *
 * | 层 | 管什么 | 不管什么 |
 * |---|---|---|
 * | 冻结闸门 | **新的**管理请求（备份/修复/更新） | 用户正在跑的 Agent 任务 |
 * | 停写确认 | 引擎进程与受管写者 | 插件自己的异步回调 |
 * | 快照 | 冻结那一刻的文件状态 | 冻结之后的外部改动 |
 *
 * ⚠️ **冻结不等于停止**。设计说明明确「A 在准备期可以继续运行」——
 * 冻结只挡新请求，不打断用户正在用的会话。停不停由后续阶段决定。
 *
 * ## ⚠️ 一个必须如实承认的边界
 *
 * 设计说明 §6.5.2 说得很清楚：
 *
 * > epoch 仅约束**经过网关的请求**，**不阻止仍在运行的任意代码直接写文件**。
 *
 * 也就是说：这个闸门只能挡住「通过本类发起的操作」。
 * AI 通过 `bash` 工具直接 `echo > file` 是**拦不住**的 ——
 * 那需要文件系统层面的强制，而 Android 上没有这个能力（同 UID 内无隔离）。
 *
 * 所以本类的定位是**合作式约束**，不是安全边界。这与项目对 sandbox 的
 * 表述一致（见 `docs/agents/sandbox.md`：不把 LD_PRELOAD 报告为完整沙箱）。
 */
object ManageGate {

    private const val TAG = "DSH"

    /**
     * 冻结原因。写进日志与 UI，让用户知道「为什么现在不能备份」。
     */
    enum class Reason(val id: String, val label: String) {
        /** 无冻结，正常可用。 */
        NONE("none", "可用"),

        /** 正在取快照（备份/修复前的准备）。 */
        SNAPSHOT("snapshot", "正在准备快照"),

        /** 正在切换环境（A/R/E）。 */
        SWITCHING("switching", "正在切换环境"),

        /** 正在更新内核或基座。 */
        UPDATING("updating", "正在更新"),

        /** 正在修复。 */
        REPAIRING("repairing", "正在修复"),
        ;
    }

    /**
     * 冻结状态（进程内）。
     *
     * ⚠️ **刻意不持久化**：冻结是**运行时**概念 —— 进程死了冻结自然失效，
     * 而"冻结中"这个状态跨进程恢复没有意义（下次启动本来就该是干净的）。
     * 持久化它反而会制造「上次冻结没解除」的幽灵状态。
     *
     * 与之相对，[ManageState] 的转换状态**必须**持久化 —— 那个跨重启有意义。
     */
    @Volatile
    private var frozen: Reason = Reason.NONE

    /** 冻结开始的时刻（`0` = 未冻结）。 */
    @Volatile
    private var frozenAtMs: Long = 0

    /** 冻结时记录的 epoch，用于识别「冻结期间状态被别处改了」。 */
    @Volatile
    private var frozenEpoch: Long = -1

    /** 冻结期间的被拒请求数（诊断用，能看出「冻结挡了多少」）。 */
    @Volatile
    private var rejectedCount: Int = 0

    /**
     * 尝试进入冻结。
     *
     * @return true = 成功冻结；false = **已被别的流程冻结**（不能抢占）
     *
     * ⚠️ **不可重入、不可抢占**：已经有冻结时不覆盖。
     * 覆盖会让先发起的流程失去保护 —— 它以为还冻结着，实际已经被别人接管，
     * 于是两个流程同时操作同一批文件。
     */
    fun freeze(context: Context, reason: Reason): Boolean {
        if (reason == Reason.NONE) return false

        synchronized(this) {
            if (frozen != Reason.NONE) {
                Log.w(TAG, "冻结被拒：已有 ${frozen.label} 在进行")
                EventLog.record(
                    "manage.gate.freeze.busy",
                    mapOf("want" to reason.id, "current" to frozen.id),
                )
                return false
            }
            frozen = reason
            frozenAtMs = System.currentTimeMillis()
            frozenEpoch = ManageState.read(context).epoch
            rejectedCount = 0
            Log.i(TAG, "已冻结管理入口: ${reason.label}")
            EventLog.record(
                "manage.gate.freeze",
                mapOf("reason" to reason.id, "epoch" to frozenEpoch.toString()),
            )
            return true
        }
    }

    /**
     * 解除冻结。
     *
     * @param reason 解除者声称的原因。**与当前冻结原因不符时只告警不阻止** ——
     *   强行要求匹配会在异常路径下（如 finally 里统一 release）解不开，
     *   而解不开的冻结比解错更危险（用户永远不能备份了）。
     */
    fun unfreeze(reason: Reason) {
        synchronized(this) {
            if (frozen == Reason.NONE) return
            val held = frozen
            val heldMs = System.currentTimeMillis() - frozenAtMs
            frozen = Reason.NONE
            frozenAtMs = 0
            frozenEpoch = -1

            if (held != reason) {
                Log.w(TAG, "解除冻结时原因不符：持有 ${held.id}，解除方声称 ${reason.id}")
            }
            Log.i(TAG, "已解除冻结（持有 ${heldMs}ms，期间拒绝 $rejectedCount 次）")
            EventLog.record(
                "manage.gate.unfreeze",
                mapOf(
                    "held" to held.id,
                    "by" to reason.id,
                    "heldMs" to heldMs.toString(),
                    "rejected" to rejectedCount.toString(),
                ),
            )
            rejectedCount = 0
        }
    }

    /**
     * 检查现在能不能做某个管理操作。
     *
     * 调用方在**真正动手之前**问一句。被拒时给出人能看懂的原因。
     */
    data class Check(val allowed: Boolean, val reason: String)

    fun canProceed(): Check {
        val f = frozen
        if (f == Reason.NONE) return Check(true, "")
        rejectedCount += 1
        val heldSec = (System.currentTimeMillis() - frozenAtMs) / 1000
        return Check(
            false,
            "${f.label}（已 ${heldSec} 秒）。请等它完成，或先停止正在进行的任务。",
        )
    }

    /**
     * 冻结期间执行一段操作。
     *
     * 拿不到冻结时**不执行** —— 与 [ManageLock.withLock] 同样的语义：
     * 调用方必须能区分「执行了但失败」与「根本没执行」。
     *
     * @return [block] 的返回值；没拿到冻结时返回 null
     */
    fun <T> withFreeze(context: Context, reason: Reason, block: () -> T): T? {
        if (!freeze(context, reason)) return null
        return try {
            block()
        } finally {
            // ⚠️ 必须在 finally 里解冻：block 抛异常时不解冻的话，
            //   这个进程剩下的生命周期里用户都不能备份了 —— 而且没有报错，
            //   只会看到「正在准备快照」一直挂着。
            unfreeze(reason)
        }
    }

    /** 当前是否冻结中。 */
    fun isFrozen(): Boolean = frozen != Reason.NONE

    /** 当前冻结原因（UI 显示用）。 */
    fun currentReason(): Reason = frozen

    /**
     * 冻结期间状态是否漂移过。
     *
     * 设计说明：「检测到 profile/数据 revision 漂移时重新准备，不用旧副本覆盖新变更」。
     *
     * ⚠️ 这里只能检测**管理状态**（epoch）的漂移，检测不到文件层面的改动
     * （那需要逐文件哈希，代价太高）。所以返回值 `false` **不等于**「没漂移」，
     * 只等于「管理状态没变」。如实标注，不夸大。
     */
    fun driftedSinceFreeze(context: Context): Boolean {
        if (frozen == Reason.NONE) return false
        return ManageState.read(context).epoch != frozenEpoch
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 挡得住：通过 canProceed() / withFreeze() 发起的管理请求
     * ❌ 挡不住：AI 通过 bash 直接写文件、插件自己的异步回调、
     *            系统或其他 App 的写入
     *
     * 这是**合作式约束**，与项目对 sandbox 的定位一致。设计说明也明确
     * 「epoch 仅约束经过网关的请求，不阻止仍在运行的任意代码直接写文件」。
     *
     * 未实现：
     * - 跨进程冻结（当前是进程内 @Volatile，另一个进程看不到）
     * - 与网关层的请求 epoch 联动（需要网关配合，当前没有）
     * - 「等待在飞任务写完」的主动等待（当前只拒绝新请求，不等旧的）
     */
}
