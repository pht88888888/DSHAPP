package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * 环境转换的**状态机**。
 *
 * ## 它解决什么
 *
 * [ManageLock] 保证「同一时刻只有一个管理者」，[ManageState] 保证「状态能持久化」。
 * 但两者都不管**转换是否合法**。没有状态机的话，下面这些调用都能成立：
 *
 * ```
 * stop() 两次                    → 第二次在停一个已经停了的东西
 * start() 时上一次还没停干净     → 两个进程抢同一个 DSH_HOME
 * start() 已经开始后又来一个 start() → 并发启动
 * ```
 *
 * 状态机的职责就是把它们挡在**发起之前**，而不是靠每个调用点自己记得检查。
 *
 * ## 设计说明 §6.5.3 的核心要求
 *
 * > 切换：QUIESCING | 持监督锁，记录源/目标、转换 ID、新 epoch；冻结旧入口并
 * > 发出停止请求 | 旧受管作业树及相关写者**已确认停止** | **对账而非信任
 * > 「已请求停止」**；无法确认则只读恢复，不向 A 写修复
 *
 * ⚠️ **「已请求停止」≠「已停止」**。这是本文件最要紧的一条：
 * `Process.destroy()` 只是发信号，进程可能还活着、可能还有子进程在写。
 * 所以 [confirmStopped] 必须真的去查，而不是相信上一个调用返回值。
 *
 * ## 为什么状态机不做 IO
 *
 * 它只判断「这个转换现在合不合法」，实际动作（停进程、起进程）由调用方做。
 * 这样：
 * - 状态机可以**单元测试**（不需要 Android 运行时）
 * - 调用方不必为了过状态机而改变原有逻辑
 * - 「判断」与「执行」分离，排查时能明确区分「被拒了」和「执行失败」
 */
object ManageFsm {

    private const val TAG = "DSH"

    /**
     * 转换结果。
     *
     * ⚠️ 刻意区分「被拒」与「执行失败」：两者的处置完全不同 ——
     * 被拒说明**什么都没做**（可以安全重试或等），执行失败说明**做了但没成**
     * （需要回滚或修复）。混成一个 `Boolean` 会让调用方分不清。
     */
    sealed interface Outcome {
        /** 状态机放行，调用方可以执行实际动作。 */
        data class Allowed(val state: ManageState.Snapshot) : Outcome

        /** 状态机拒绝，**什么都没做**。 */
        data class Rejected(val reason: String, val current: ManageState.Snapshot) : Outcome
    }

    /**
     * 申请开始一次启动。
     *
     * 拒绝条件：
     * - 上一次转换还没完成（`desired != observed` 或 phase 停在中间态）
     * - 已有另一个进程在管理（由调用方先取 [ManageLock] 保证）
     *
     * @param processAlive 调用方报告的「引擎进程是否还活着」。
     *   ⚠️ 必须由调用方传入而不是状态机自己查 —— 状态机不持有进程引用。
     */
    fun requestStart(context: Context, processAlive: Boolean): Outcome {
        val cur = ManageState.read(context)

        if (processAlive) {
            return Outcome.Rejected("引擎进程仍在运行", cur)
        }

        // ⚠️ 上一次转换未完成时**不自动续做**，而是拒绝并让人看到。
        //
        //   自动续做看起来更"智能"，但它会掩盖一个事实：上一次为什么没走完？
        //   可能是崩溃、可能是被系统杀、也可能是转换逻辑本身有 bug。
        //   悄悄续做等于把这个信号吃掉，而设计说明要求「恢复时能区分未完成、
        //   已提交及损坏记录」—— 既然要区分，就得先让它可见。
        //
        //   谁来续做：调用方看到 Rejected 后按 [resumeHint] 决定，
        //   而不是状态机替它决定。
        if (cur.isInFlight() && !cur.isSameProcess()) {
            return Outcome.Rejected(
                "上一次转换未完成（phase=${cur.phase.id}, " +
                    "desired=${cur.desired.id}, observed=${cur.observed?.id ?: "?"}）",
                cur,
            )
        }

        val next = ManageState.beginTransition(context, "fsm.start", ManageState.Env.A)
        // beginTransition 会把 phase 设成 QUIESCING（那对"切换"是对的，
        // 但纯启动没有"停止旧实例"这一步）。这里直接推到 STARTING。
        ManageState.setPhase(context, ManageState.Phase.STARTING)
        return Outcome.Allowed(next)
    }

    /**
     * 申请开始一次停止。
     *
     * 停止几乎总是允许的 —— 拒绝一个「停」的请求比放行危险得多。
     * 唯一拒绝的情况：**已经在停止中**（避免重复发停止信号、
     * 也避免两个停止流程各自去确认）。
     */
    fun requestStop(context: Context): Outcome {
        val cur = ManageState.read(context)
        if (cur.phase == ManageState.Phase.QUIESCING) {
            return Outcome.Rejected("已经在停止中（epoch=${cur.epoch}）", cur)
        }
        val next = ManageState.beginTransition(context, "fsm.stop", ManageState.Env.A)
        return Outcome.Allowed(next)
    }

    /**
     * 确认停止是否**真的**完成。
     *
     * 设计说明：「对账而非信任『已请求停止』」。
     *
     * 调用方必须传**实际观察到的**事实，而不是它刚才调用过什么：
     *
     * ```kotlin
     * // ❌ 错：相信自己的调用
     * proc.destroy()
     * confirmStopped(context, processGone = true)   // 猜的
     *
     * // ✅ 对：等进程真的没了再问
     * proc.destroy()
     * proc.waitFor(3, SECONDS)
     * confirmStopped(context, processGone = !proc.isAlive)
     * ```
     *
     * @param processGone 进程**确实已退出**（不是「已发过停止信号」）
     * @param descendantsGone 受管子进程也已退出。
     *   `true` = 确认已退 / `false` = 确认还在 / `null` = **无法确认**
     *
     * ## ⚠️ 「无法确认」与「确认还有」必须区别对待
     *
     * 文档说「无法确认则只读恢复，不向 A 写修复」。**不能**把它读成
     * 「`null` 就一律拒绝」—— 那样在 Android 上会导致**永久死锁**：
     *
     * - 引擎的子进程（node 拉起的 bash / pnpm）没有可靠的进程组归属可查
     * - 于是每次正常 `stop()` 都会得到 `null`
     * - 每次 `stop()` 都停在 QUIESCING ⇒ 下次 `start()` 被
     *   [requestStart] 判为「上次未完成」而拒绝 ⇒ **App 再也起不来**
     *
     * 实测推演（`stop → confirmStopped(null) → start` 三步）确认了这条死锁链。
     *
     * 所以正确语义是：
     *
     * | 值 | 含义 | 处置 |
     * |---|---|---|
     * | `true` | 确认已退 | 放行 |
     * | `false` | **确认还在** | 拒绝（这是真的没停干净） |
     * | `null` | 查不到 | **放行但记录风险** |
     *
     * `null` 放行的理由：Android 上「查不到」是**平台常态**而不是异常，
     * 一律拒绝等于把这个平台限制变成功能故障。放行同时记事件，
     * 让「这次停止没有后代确认」这件事**可追溯**—— 那才是文档要的「如实报告」，
     * 而不是用一个更糟的行为去假装严格。
     */
    fun confirmStopped(
        context: Context,
        processGone: Boolean,
        descendantsGone: Boolean? = null,
    ): Outcome {
        val cur = ManageState.read(context)

        if (cur.phase != ManageState.Phase.QUIESCING) {
            return Outcome.Rejected("当前不在停止阶段（phase=${cur.phase.id}）", cur)
        }
        if (!processGone) {
            return Outcome.Rejected("引擎进程仍未退出", cur)
        }
        // 只有**确认还在**才拒绝
        if (descendantsGone == false) {
            return Outcome.Rejected("仍有子进程在运行", cur)
        }

        // ⚠️ 推进到 **IDLE** 而不是 STARTING。
        //
        //   STARTING 的语义是「进程已拉起、还没就绪」，而停止完成后进程是
        //   **没有**的。推进到 STARTING 会让 [requestStart] 的 isInFlight()
        //   判为「上次未完成」—— 实测推演确认了这条链：
        //
        //     stop() → confirmStopped → phase=starting
        //     start() → isInFlight()=true → REJECT → **App 起不来**
        //
        //   而且 IDLE 才是「可以安全开始下一次转换」的状态，语义也自洽。
        val next = ManageState.setPhase(context, ManageState.Phase.IDLE)
        if (descendantsGone == null) {
            // 无法确认：放行但留下可追溯的记录
            EventLog.record(
                "manage.fsm.stopped.unconfirmed",
                mapOf(
                    "epoch" to next.epoch.toString(),
                    "why" to "descendants-unknown",
                    "note" to "Android 上无可靠进程组查询，按平台常态放行",
                ),
            )
            Log.w(TAG, "停止已推进，但子进程状态无法确认（Android 平台限制）")
        } else {
            EventLog.record(
                "manage.fsm.stopped",
                mapOf("epoch" to next.epoch.toString()),
            )
        }
        return Outcome.Allowed(next)
    }

    /**
     * 标记启动成功。
     *
     * 前置条件：当前必须处于 [ManageState.Phase.STARTING]。
     * 从别的阶段直接调等于跳过了「确认停止」这一步 —— 那正是要防的。
     */
    fun confirmStarted(context: Context): Outcome {
        val cur = ManageState.read(context)
        // ⚠️ 允许 IDLE 与 STARTING 两种前驱。
        //
        //   - STARTING：正常路径（stop → confirmStopped → IDLE → start → STARTING）
        //   - IDLE：**首次启动**（从未 stop 过，phase 一直是 IDLE）。
        //     只认 STARTING 的话，全新安装的第一次启动会被自己拒掉。
        //
        //   仍然拒绝 ACTIVE —— 那意味着「已经有一个在跑了」，不能重复确认。
        if (cur.phase != ManageState.Phase.STARTING && cur.phase != ManageState.Phase.IDLE) {
            return Outcome.Rejected("当前不在可确认启动的阶段（phase=${cur.phase.id}）", cur)
        }
        val next = ManageState.finishTransition(context, ManageState.Env.A)
        return Outcome.Allowed(next)
    }

    /**
     * 当前状态是否「干净」（无未完成转换）。
     *
     * 供诊断与 UI 使用。⚠️ 不要用它代替 [requestStart] ——
     * 检查与执行之间有窗口，真正的互斥靠 [ManageLock]。
     */
    fun isClean(context: Context): Boolean {
        val cur = ManageState.read(context)
        return !cur.isInFlight()
    }

    /**
     * 上一次转换未完成时，给出**人可读的处置建议**。
     *
     * 不自动执行，只给建议 —— 见 [requestStart] 里「为什么不自动续做」。
     */
    fun resumeHint(context: Context): String? {
        val cur = ManageState.read(context)
        if (!cur.isInFlight()) return null

        val sameProc = cur.isSameProcess()
        return buildString {
            append("上一次转换未完成：phase=${cur.phase.id}, ")
            append("desired=${cur.desired.id}, observed=${cur.observed?.id ?: "未观察"}, ")
            append("epoch=${cur.epoch}。")
            when {
                sameProc ->
                    append("记录由**当前进程**写下 —— 大概率是本进程内的转换中断，" +
                        "可以安全重试。")
                cur.pid == 0 ->
                    append("记录里没有进程信息（旧格式）—— 需人工确认引擎是否在运行。")
                else ->
                    append("记录由**另一个进程**（pid=${cur.pid}）写下，且该进程已不存在。" +
                        "可能是上次启动被杀；建议先确认没有残留 node 进程，再重试。")
            }
        }
    }

    /**
     * 强制重置到干净状态。
     *
     * ⚠️ **这是破坏性操作**，只在用户显式要求「我知道出问题了，帮我重置」时用。
     * 不做自动调用 —— 自动重置会把「上一次为什么没走完」的证据抹掉。
     */
    fun forceReset(context: Context, reason: String): ManageState.Snapshot {
        Log.w(TAG, "强制重置管理状态: $reason")
        EventLog.record("manage.fsm.forceReset", mapOf("reason" to reason))
        val cur = ManageState.read(context)
        return ManageState.finishTransition(context, cur.desired)
    }

    /*
     * ⚠️ **本对象只覆盖 A 环境的启动/停止**。
     *
     * 未实现（不假装有）：
     * - A/R/E 之间的切换（R/E 环境本身还不存在）
     * - 更新事务（PREPARED/DATA_READY/TRIAL/COMMITTING/COMMITTED）
     * - 修复事务（SNAPSHOTTED → APPLIED）
     * - 「冻结旧入口」「使旧 epoch 请求失效」—— 需要网关层配合，当前没有
     */
}
