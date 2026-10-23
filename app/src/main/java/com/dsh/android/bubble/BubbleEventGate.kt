package com.dsh.android.bubble

import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **悬浮事件的有效期与序号** —— 防「旧状态迟到覆盖新状态」。
 *
 * ## 设计说明的要求
 *
 * §4.11：
 *
 * > 当前激活环境持有有效控制租约；同一窗口多插件竞争由基座仲裁，
 * > **不采用无规则的最后写入覆盖**。
 *
 * > 切换环境时**撤销旧租约、事件订阅和排队控制**，清除旧任务状态；
 * > 可以保留已缓存外观，但**不得继续显示旧任务正在运行**。
 *
 * 验收矩阵第 18 条：
 *
 * > 多会话事件有明确跟随/聚合规则，**乱序或过期事件不覆盖当前状态**。
 *
 * ## ⚠️ 要解决的真实问题
 *
 * 悬浮窗的状态由**多个来源**更新：
 *
 * | 来源 | 例子 |
 * |---|---|
 * | 页面桥 | 用户点了一下球 |
 * | 引擎插件 | AI 改了外观 |
 * | 物理 tick | 抛掷后落位 |
 * | 状态轮询 | 任务从「运行中」变「空闲」 |
 *
 * 这些是**并发**的。没有序号时会出现：
 *
 * ```
 * t=0  t1 读到「运行中」         → 显示转圈
 * t=1  t2 读到「已空闲」         → 显示静止
 * t=2  t1 的 UI 更新才真正执行   → 又变回转圈（错！）
 * ```
 *
 * 用户看到的是「任务明明结束了，球还在转」。
 *
 * ## 解法：单调递增序号 + 只接受更新的
 *
 * 每次状态变化带一个 `seq`，[accept] 只接受**严格大于**当前序号的。
 * 这样迟到的旧事件自然被丢弃，不需要知道它为什么迟到。
 *
 * ## ⚠️ 为什么还要「有效期」
 *
 * 序号解决「同一环境内的乱序」，但解决不了「**跨环境**的迟到」：
 *
 * ```
 * 环境 A 发出 seq=5
 * 切到环境 R（租约换了）
 * 环境 A 的 seq=5 迟到到达
 * ```
 *
 * 这时 seq=5 可能**大于** R 环境的当前序号（R 刚从 0 开始），
 * 于是被接受 —— 但它来自已经失效的环境。
 *
 * 所以序号必须**与租约绑定**：租约失效后，旧租约的序号一律丢弃。
 */
object BubbleEventGate {

    private const val TAG = "DSH"

    /**
     * 一次事件的来源（用于诊断「谁在改我的球」）。
     */
    enum class Source(val id: String) {
        PAGE_BRIDGE("page"),
        ENGINE_PLUGIN("plugin"),
        PHYSICS("physics"),
        POLLER("poller"),
        USER("user"),
        ;
    }

    /**
     * 事件的受理结果。
     */
    sealed interface Verdict {
        /** 接受。 */
        data object Accept : Verdict

        /** 拒绝：序号不比当前新（迟到的旧事件）。 */
        data class StaleSeq(val got: Long, val current: Long) : Verdict

        /** 拒绝：租约已失效（来自旧环境）。 */
        data class StaleLease(val lease: Long, val current: Long) : Verdict
    }

    /** 当前租约 ID（每次环境切换 +1）。 */
    @Volatile
    private var leaseId: Long = 0

    /** 当前已受理的最大序号（**按租约独立**，换租约时归零）。 */
    @Volatile
    private var seq: Long = 0

    /** 被拒绝的计数（诊断用，能看出「有多少旧事件在迟到」）。 */
    @Volatile
    private var staleSeqCount: Int = 0
    @Volatile
    private var staleLeaseCount: Int = 0

    /**
     * 检查一个事件能否被受理。
     *
     * @param eventLease 事件发出时的租约
     * @param eventSeq 事件序号（同一租约内单调递增）
     */
    fun check(eventLease: Long, eventSeq: Long, source: Source): Verdict {
        // ① 租约必须匹配
        if (eventLease != leaseId) {
            staleLeaseCount++
            EventLog.record(
                "bubble.event.staleLease",
                mapOf(
                    "from" to source.id,
                    "eventLease" to eventLease.toString(),
                    "currentLease" to leaseId.toString(),
                ),
            )
            return Verdict.StaleLease(eventLease, leaseId)
        }

        // ② 序号必须**严格大于**当前
        //
        // ⚠️ 用 `>` 而不是 `>=`：同一序号重复应用通常是缺陷（两条路径
        //   用了同一个序号），静默接受会让它看起来「正常工作」。
        if (eventSeq <= seq) {
            staleSeqCount++
            return Verdict.StaleSeq(eventSeq, seq)
        }

        seq = eventSeq
        return Verdict.Accept
    }

    /**
     * 换租约（环境切换时调用）。
     *
     * ## ⚠️ 文档明确要求
     *
     * > 切换环境时**撤销旧租约、事件订阅和排队控制**，清除旧任务状态；
     * > 可以保留已缓存外观，但**不得继续显示旧任务正在运行**。
     *
     * 所以这里做三件事：
     * 1. 租约 +1（旧租约的事件全部失效）
     * 2. 序号归零（新租约从 0 开始）
     * 3. 记事件（诊断时能看到「什么时候切的」）
     *
     * @return 新的租约 ID
     */
    fun rotateLease(reason: String): Long {
        leaseId++
        seq = 0
        val staleS = staleSeqCount
        val staleL = staleLeaseCount
        staleSeqCount = 0
        staleLeaseCount = 0
        Log.i(TAG, "悬浮租约轮换 → $leaseId（$reason；上次周期丢弃 ${staleS} 个过期序号、${staleL} 个旧租约事件）")
        EventLog.record(
            "bubble.lease.rotate",
            mapOf(
                "lease" to leaseId.toString(),
                "reason" to reason,
                "droppedStaleSeq" to staleS.toString(),
                "droppedStaleLease" to staleL.toString(),
            ),
        )
        return leaseId
    }

    /** 当前租约。 */
    fun currentLease(): Long = leaseId

    /** 当前序号（诊断用）。 */
    fun currentSeq(): Long = seq

    /**
     * 生成一个**下一个**序号（给发事件的一方用）。
     *
     * ⚠️ 只在同一进程内生成时才安全：跨进程需要各自的号段，
     *   否则两个进程可能生成同一个序号。
     *   当前悬浮窗只在主进程，所以够用 —— 但**如果将来跨进程，
     *   必须换成「进程号 + 本地序号」的复合键**。
     */
    fun nextSeq(): Long = seq + 1

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：序号单调性检查、租约绑定、迟到事件丢弃、诊断计数
     * ❌ 未做：
     *   - **接入实际的悬浮窗更新路径**（FloatingBubble 还没调 check/nextSeq）
     *   - 「会话跟随/聚合规则」（多会话时跟哪个、怎么合并 —— 那是另一套逻辑）
     *   - 「多插件竞争由基座仲裁」（需要插件优先级与用户设置，属后续）
     *   - 跨进程序号（见 nextSeq 的说明）
     */
}
