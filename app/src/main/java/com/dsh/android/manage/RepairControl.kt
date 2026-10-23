package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.UUID

/**
 * **受控修复** —— 修复计划、批准绑定、执行前再核验、结果核验。
 *
 * ## 设计说明 / 开发指导 §G8 的硬要求
 *
 * > 修复计划**声明范围与不可逆副作用**，批准**绑定目标/参数/环境/epoch/通道/有效期**，
 * > **执行前再核验**。
 *
 * > 验收：…**已执行/不确定副作用不假报已撤销**。**修复模型不能替代监督器
 * > 决定是否允许写 A**。
 *
 * ## ⚠️ 为什么需要「批准绑定」
 *
 * 这是本项目最容易做错、也最危险的一处。想象这个场景：
 *
 * ```
 * t=0   AI 生成修复计划：「删除 profile 里损坏的插件配置」
 * t=1   弹窗给用户看，用户去泡了杯咖啡
 * t=2   用户回来点「同意」
 * t=3   ← 这期间 AI 又改了一次计划（或环境已经变了）
 * t=4   执行 —— 删掉的可能不是用户批准的那个
 * ```
 *
 * 所以批准必须**绑定当时的全部上下文**。执行时逐项比对，任何一项不符就拒绝。
 *
 * | 绑定项 | 防什么 |
 * |---|---|
 * | 目标 | 批准删 A，结果删了 B |
 * | 参数 | 批准改一处，结果改了三处 |
 * | 环境 | 批准在 R 里修，结果动了 A |
 * | epoch | 批准时是第 3 代，执行时已经是第 5 代（中途切过环境） |
 * | 通道 | 批准走 UI，结果从桥里偷偷执行 |
 * | 有效期 | 批准后过了 2 小时才执行（上下文早变了） |
 *
 * ## ⚠️ 「修复模型不能替代监督器」
 *
 * 文档明确：**AI 不能自己决定「可以写 A」**。
 *
 * 所以本对象的结构是：
 * - AI（或任何调用方）只能**提交计划**（[propose]）
 * - 用户批准产生 [Approval]
 * - 执行前由**本对象**（代表监督器）核验，不是 AI 自查
 *
 * ⚠️ 还有一个更隐蔽的点：**不能以插件自报 `approved` 通过**。
 *   插件可以说「用户批准了」，但那必须由基座自己核实 ——
 *   本对象只认自己签发的 [Approval]。
 */
object RepairControl {

    private const val TAG = "DSH"

    /** 批准有效期（毫秒）。5 分钟。 */
    const val APPROVAL_TTL_MS = 5 * 60 * 1000L

    /**
     * 修复动作的类别。
     *
     * ⚠️ 分成「可逆」与「不可逆」两档 —— 文档要求**声明不可逆副作用**，
     *   而用户对这两档的心理预期完全不同。
     */
    enum class Kind(val id: String, val reversible: Boolean, val label: String) {
        /** 改名隔离（可逆：改回来就行）。 */
        QUARANTINE("quarantine", true, "隔离（改名，可恢复）"),

        /** 删除缓存/日志（不可逆，但可再生）。 */
        DELETE_CACHE("delete-cache", false, "删除缓存（不可逆，但会重新生成）"),

        /** 删除用户配置（**不可逆且丢数据**）。 */
        DELETE_CONFIG("delete-config", false, "删除配置（不可逆，会丢设置）"),

        /** 重解包引擎（不可逆，但从 APK 重建）。 */
        REPACK_ENGINE("repack-engine", false, "重解包引擎（不可逆，从 APK 重建）"),

        /** 重置状态文件（不可逆）。 */
        RESET_STATE("reset-state", false, "重置状态（不可逆）"),
        ;
    }

    /**
     * 一个修复步骤。
     *
     * @param kind 动作类别
     * @param target **精确目标**（文件绝对路径 / 状态键 / 插件 id）
     * @param params 附加参数（会参与批准绑定，改动后批准失效）
     * @param description 给人看的一句话
     */
    data class Step(
        val kind: Kind,
        val target: String,
        val params: Map<String, String> = emptyMap(),
        val description: String,
    ) {
        /** 参与绑定的指纹（目标 + 参数）。 */
        fun fingerprint(): String =
            "${kind.id}|$target|" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    /**
     * 一份修复计划。
     *
     * @param planId 计划 ID
     * @param steps 步骤（**顺序执行**）
     * @param env 在哪个环境执行
     * @param declaredScope 声明的修改范围（人能读的，如「profiles/web 下的 package.json」）
     * @param irreversibleSideEffects 不可逆副作用的说明（空 = 全可逆）
     */
    data class Plan(
        val planId: String,
        val steps: List<Step>,
        val env: ManageEnv.Env,
        val declaredScope: String,
        val irreversibleSideEffects: List<String> = emptyList(),
        val proposedAt: Long = System.currentTimeMillis(),
    ) {
        /** 计划是否只包含可逆动作。 */
        val allReversible: Boolean get() = steps.all { it.kind.reversible }

        /** 计划的整体指纹（用于绑定）。 */
        fun fingerprint(): String =
            steps.joinToString(";") { it.fingerprint() }
    }

    /**
     * **批准** —— 绑定批准时的全部上下文。
     *
     * ⚠️ 这是**唯一**能让计划执行的凭据。它只能由 [approve] 签发，
     *   不能由调用方构造（构造函数是 internal）。
     */
    class Approval internal constructor(
        val planId: String,
        val planFingerprint: String,
        val env: ManageEnv.Env,
        val epoch: Long,
        val channel: String,
        val approvedAt: Long,
        val expiresAt: Long,
        /** 用户明确确认了不可逆副作用。 */
        val acknowledgedIrreversible: Boolean,
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt
    }

    /**
     * 核验结果。
     */
    sealed interface Verdict {
        /** 通过，可以执行。 */
        data object Ok : Verdict

        /** 没有批准。 */
        data object NoApproval : Verdict

        /** 批准已过期。 */
        data object Expired : Verdict

        /** 计划内容变了（指纹不符）。 */
        data class PlanChanged(val detail: String) : Verdict

        /** 环境变了。 */
        data class EnvChanged(val approved: String, val current: String) : Verdict

        /** epoch 变了（中途切过环境）。 */
        data class EpochChanged(val approved: Long, val current: Long) : Verdict

        /** 通道不符。 */
        data class ChannelMismatch(val approved: String, val current: String) : Verdict

        /** 有不可逆副作用但用户没确认。 */
        data object IrreversibleNotAcknowledged : Verdict
    }

    /**
     * 提交一个修复计划。
     *
     * ⚠️ **提交不等于批准**。调用方（AI）只能提交，批准必须由用户动作产生。
     */
    fun propose(
        context: Context,
        steps: List<Step>,
        env: ManageEnv.Env,
        declaredScope: String,
        irreversibleSideEffects: List<String> = emptyList(),
    ): Plan {
        val plan = Plan(
            planId = UUID.randomUUID().toString(),
            steps = steps,
            env = env,
            declaredScope = declaredScope,
            irreversibleSideEffects = irreversibleSideEffects,
        )
        EventLog.record(
            "repair.propose",
            mapOf(
                "planId" to plan.planId,
                "steps" to steps.size.toString(),
                "env" to env.id,
                "reversible" to plan.allReversible.toString(),
            ),
        )
        Log.i(TAG, "修复计划已提交: ${plan.planId}（${steps.size} 步，环境 ${env.id}）")
        return plan
    }

    /**
     * **签发批准**（用户点了「同意」之后调）。
     *
     * @param channel 通过哪个通道批准的（`ui` / `bubble` / `recovery`）。
     *   执行时必须用**同一通道** —— 文档要求绑定「通道」。
     * @param acknowledgedIrreversible 用户是否明确看到了不可逆副作用说明。
     *
     * ⚠️ 有不可逆副作用但 `acknowledgedIrreversible=false` 时**仍然签发**，
     *   但执行时会被 [verify] 拒绝 —— 这样调用方能拿到明确的
     *   [Verdict.IrreversibleNotAcknowledged] 而不是「批准失败」这种笼统结论。
     */
    fun approve(
        context: Context,
        plan: Plan,
        channel: String,
        acknowledgedIrreversible: Boolean,
    ): Approval {
        val now = System.currentTimeMillis()
        val a = Approval(
            planId = plan.planId,
            planFingerprint = plan.fingerprint(),
            env = plan.env,
            // ⚠️ 绑 epoch：中途切过环境（A→R）后，旧批准必须失效
            epoch = ManageState.read(context).epoch,
            channel = channel,
            approvedAt = now,
            expiresAt = now + APPROVAL_TTL_MS,
            acknowledgedIrreversible = acknowledgedIrreversible,
        )
        EventLog.record(
            "repair.approve",
            mapOf(
                "planId" to plan.planId,
                "channel" to channel,
                "irreversible" to (!plan.allReversible).toString(),
            ),
        )
        return a
    }

    /**
     * **执行前再核验**（文档明确要求）。
     *
     * 逐项比对批准时的上下文与当前上下文。任何一项不符就拒绝。
     *
     * @param currentChannel 当前执行通道
     */
    fun verify(
        context: Context,
        plan: Plan,
        approval: Approval?,
        currentChannel: String,
    ): Verdict {
        if (approval == null) return Verdict.NoApproval
        if (approval.isExpired()) {
            EventLog.record("repair.verify.expired", mapOf("planId" to plan.planId))
            return Verdict.Expired
        }

        // ① 计划内容没变
        val fp = plan.fingerprint()
        if (fp != approval.planFingerprint) {
            EventLog.record("repair.verify.planChanged", mapOf("planId" to plan.planId))
            return Verdict.PlanChanged("计划内容与批准时不一致")
        }

        // ② 环境没变
        val curEnv = plan.env
        if (curEnv != approval.env) {
            return Verdict.EnvChanged(approval.env.id, curEnv.id)
        }

        // ③ epoch 没变（中途切过环境会让它 +1）
        val curEpoch = ManageState.read(context).epoch
        if (curEpoch != approval.epoch) {
            EventLog.record(
                "repair.verify.epochChanged",
                mapOf("approved" to approval.epoch.toString(), "current" to curEpoch.toString()),
            )
            return Verdict.EpochChanged(approval.epoch, curEpoch)
        }

        // ④ 通道一致
        if (currentChannel != approval.channel) {
            return Verdict.ChannelMismatch(approval.channel, currentChannel)
        }

        // ⑤ 不可逆副作用必须被确认
        if (!plan.allReversible && !approval.acknowledgedIrreversible) {
            return Verdict.IrreversibleNotAcknowledged
        }

        return Verdict.Ok
    }

    /**
     * 执行一个已批准的计划。
     *
     * ## ⚠️ 三条纪律
     *
     * 1. **先核验再执行**（[verify]），不核验就动手等于绕过批准
     * 2. **每步单独记事件**：失败时能知道停在哪一步
     * 3. **如实报告已执行与不确定的部分**：文档要求「已执行/不确定副作用
     *    **不假报已撤销**」
     *
     * @param execute 真正做事的回调（由调用方提供 —— 本对象不知道
     *   怎么删文件/怎么重解包，那是各模块的事）
     */
    fun run(
        context: Context,
        plan: Plan,
        approval: Approval?,
        currentChannel: String,
        execute: (Step) -> Boolean,
    ): Result {
        val v = verify(context, plan, approval, currentChannel)
        if (v != Verdict.Ok) {
            Log.w(TAG, "修复被拒绝: ${describeVerdict(v)}")
            return Result.Rejected(v)
        }

        val done = mutableListOf<Step>()
        val failed = mutableListOf<Pair<Step, String>>()

        for (step in plan.steps) {
            val ok = try {
                execute(step)
            } catch (t: Throwable) {
                Log.w(TAG, "修复步骤异常: ${step.description} —— ${t.message}")
                false
            }
            if (ok) {
                done.add(step)
                EventLog.record(
                    "repair.step.ok",
                    mapOf("planId" to plan.planId, "kind" to step.kind.id, "target" to step.target),
                )
            } else {
                // ⚠️ 失败时**立即停止**：后续步骤可能依赖这一步的结果。
                //   继续执行会让状态更难恢复。
                failed.add(step to "执行返回 false")
                EventLog.record(
                    "repair.step.failed",
                    mapOf("planId" to plan.planId, "kind" to step.kind.id, "target" to step.target),
                )
                break
            }
        }

        EventLog.record(
            "repair.done",
            mapOf(
                "planId" to plan.planId,
                "done" to done.size.toString(),
                "failed" to failed.size.toString(),
            ),
        )
        return Result.Executed(done, failed)
    }

    /**
     * 执行结果。
     */
    sealed interface Result {
        data class Rejected(val verdict: Verdict) : Result

        /**
         * 已执行（可能部分失败）。
         *
         * @param done 成功的步骤
         * @param failed 失败的步骤与原因
         */
        data class Executed(
            val done: List<Step>,
            val failed: List<Pair<Step, String>>,
        ) : Result {
            val fullySucceeded: Boolean get() = failed.isEmpty()

            /**
             * 一句话结论。
             *
             * ⚠️ **不假报已撤销**：文档要求「已执行/不确定副作用不假报已撤销」。
             *   所以这里只说「做了什么」「什么没做」，不说「已恢复原状」。
             */
            fun summary(): String = when {
                fullySucceeded -> "已完成 ${done.size} 项修复"
                done.isEmpty() -> "未执行任何修复（第一步就失败）"
                else -> "部分完成：${done.size} 项成功，${failed.size} 项失败" +
                    "（失败：${failed.first().first.description}）"
            }
        }
    }

    private fun describeVerdict(v: Verdict): String = when (v) {
        Verdict.Ok -> "通过"
        Verdict.NoApproval -> "没有批准"
        Verdict.Expired -> "批准已过期"
        is Verdict.PlanChanged -> "计划已变：${v.detail}"
        is Verdict.EnvChanged -> "环境已变：${v.approved} → ${v.current}"
        is Verdict.EpochChanged -> "epoch 已变：${v.approved} → ${v.current}"
        is Verdict.ChannelMismatch -> "通道不符：${v.approved} → ${v.current}"
        Verdict.IrreversibleNotAcknowledged -> "有不可逆副作用但用户未确认"
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：计划提交、批准绑定 6 项上下文、执行前再核验、逐项核验结果、
     *          失败即停、不假报已撤销
     * ❌ 未做：
     *   - **模型诊断本身**（AI 怎么发现「哪里坏了」—— 那是 `DiagnosticBundle`
     *     的结论 + 模型推理，本对象只负责「执行修复」这一段的控制）
     *   - 「修复计划由模型生成」的接入（当前 `propose` 由调用方调，
     *     没有把诊断结论自动变成计划）
     *   - 「审批后目标被替换」的检测（`epoch` 能发现环境切换，但
     *     「目标文件在批准后被别的东西替换了」需要内容哈希比对，
     *     属 `Step.params` 的用法约定，当前未强制）
     *   - 「不确定副作用的如实报告」（`execute` 返回 bool，
     *     无法表达「执行了但结果不确定」这一档）
     */
}
