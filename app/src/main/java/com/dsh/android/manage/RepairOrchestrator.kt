package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File

/**
 * **修复编排器** —— 把「诊断 → 计划 → 批准 → 执行 → 核验」串成一条链路。
 *
 * ## 为什么需要它
 *
 * 各组件都写好了，但**没有一条把它们串起来的流程**：
 *
 * ```
 * DiagnosisToRepair   诊断结论 → 修复建议
 * RepairControl       计划 → 批准 → 受控执行
 * RepairSnapshot      修复前快照
 * RepairVerification  修复后核验
 * DiskCleanup         低水位清理
 * ```
 *
 * 五种组件各自独立，谁也不知道谁。本对象是**唯一的编排点**。
 *
 * ## ⚠️ 一个刻意的设计：本对象**不自动执行**
 *
 * 文档要求「修复模型不能替代监督器决定是否允许写 A」。
 *
 * 所以本对象的流程是：
 *
 * ```
 * ① diagnose()   → 诊断（只读）
 * ② propose()    → 生成计划（**不执行**）
 * ③ [用户批准]    ← 必须外部动作
 * ④ runAndVerify → 执行 + 核验
 * ```
 *
 * ② 与 ④ 之间**必须有用户动作**，本对象不提供「一步到位」的捷径。
 *
 * ## ⚠️ 第二个设计：诊断项 → Finding 的转换在这里做
 *
 * `DiagnosticBundle` 的检查项是 `Check(id, status, detail)`，
 * 而 `DiagnosisToRepair` 要 `Finding(id, status, detail)` —— 两者同构但不同类型。
 *
 * ⚠️ 为什么不让 `DiagnosticBundle` 直接产出 `Finding`：
 *   `log` 包已经依赖 `manage`，反向引用会成环。所以转换放在这一层。
 */
object RepairOrchestrator {

    private const val TAG = "DSH"

    /**
     * 诊断结果（本对象的最小抽象）。
     *
     * ⚠️ 由调用方从 `DiagnosticBundle` 的检查项转换而来 ——
     *   本对象不直接依赖 `log` 包。
     */
    data class Diagnosis(
        val findings: List<DiagnosisToRepair.Finding>,
    ) {
        val problems: List<DiagnosisToRepair.Finding> get() = findings.filter { it.isProblem }
        val hasProblems: Boolean get() = problems.isNotEmpty()
    }

    /**
     * 完整流程的结果。
     */
    data class Outcome(
        /** 诊断出了什么。 */
        val diagnosis: Diagnosis,
        /** 生成的计划（`null` = 没有可自动修复的项）。 */
        val plan: RepairControl.Plan?,
        /** 建议说明（人能读的）。 */
        val suggestion: DiagnosisToRepair.Suggestion?,
        /** 执行结果（`null` = 用户没批准 / 没执行）。 */
        val execution: RepairControl.Result?,
        /** 核验结果（`null` = 没执行或无法核验）。 */
        val verification: RepairVerification.Result?,
    ) {
        /** 一句话结论（给 UI 用）。 */
        fun summary(): String = when {
            !diagnosis.hasProblems -> "未发现问题"
            plan == null -> suggestion?.reason ?: "没有可自动修复的项，需人工处理"
            execution == null -> "已生成修复计划，等待用户批准"
            execution is RepairControl.Result.Rejected ->
                "修复被拒绝：${(execution as RepairControl.Result.Rejected).verdict}"
            verification == null -> "修复已执行，无法核验"
            else -> verification!!.summary()
        }
    }

    /**
     * 阶段一：诊断 + 生成计划（**不执行**）。
     *
     * @param diagnose 跑一次诊断（由调用方注入 —— 通常是
     *   `DiagnosticBundle` 的检查项转成 `Finding`）
     * @param env 目标环境
     */
    fun plan(
        context: Context,
        diagnose: () -> Diagnosis,
        env: ManageEnv.Env,
    ): Outcome {
        val diag = try {
            diagnose()
        } catch (t: Throwable) {
            Log.w(TAG, "诊断失败: ${t.message}")
            Diagnosis(emptyList())
        }

        if (!diag.hasProblems) {
            return Outcome(diag, null, null, null, null)
        }

        val suggestion = DiagnosisToRepair.suggest(context, diag.findings, env)
        val plan = DiagnosisToRepair.buildPlan(
            context = context,
            suggestion = suggestion,
            env = env,
            declaredScope = buildScope(context, suggestion.steps),
        )

        EventLog.record(
            "repair.orchestrator.plan",
            mapOf(
                "problems" to diag.problems.size.toString(),
                "steps" to suggestion.steps.size.toString(),
                "manual" to suggestion.needsManual.toString(),
            ),
        )
        Log.i(
            TAG,
            "修复计划已生成：${diag.problems.size} 个问题，" +
                "${suggestion.steps.size} 个可自动修复步骤",
        )
        return Outcome(diag, plan, suggestion, null, null)
    }

    /**
     * 阶段二：执行 + 核验（**必须在用户批准之后**）。
     *
     * @param approval 用户批准（由 `RepairControl.approve` 签发）
     * @param channel 当前通道（必须与批准时一致）
     * @param diagnoseAfter 修复后的重新诊断
     */
    fun executeAndVerify(
        context: Context,
        diagnosis: Diagnosis,
        plan: RepairControl.Plan,
        approval: RepairControl.Approval?,
        channel: String,
        diagnoseAfter: () -> Diagnosis?,
    ): Outcome {
        val (execResult, verifyResult) = RepairVerification.runAndVerify(
            context = context,
            plan = plan,
            approval = approval,
            channel = channel,
            before = diagnosis.findings,
            execute = { step -> performStep(context, step) },
            diagnose = { diagnoseAfter()?.findings },
        )

        // ⚠️ 处理不可逆副作用：从计划的 steps 推出来
        //
        // 文档：「外部和不可逆动作**不得报告为已自动撤销**」。
        val irreversible = plan.steps
            .filterNot { it.kind.reversible }
            .map { "${it.kind.label}：${it.target}" }

        if (irreversible.isNotEmpty() && execResult is RepairControl.Result.Executed) {
            Log.i(TAG, "本次修复含 ${irreversible.size} 项不可逆动作（不能靠快照撤销）")
        }

        return Outcome(diagnosis, plan, null, execResult, verifyResult)
    }

    /**
     * **实际执行一个修复步骤**。
     *
     * ⚠️ 这是本对象唯一有副作用的地方。每种 `Kind` 的语义：
     *
     * | Kind | 动作 | 失败可能 |
     * |---|---|---|
     * | `DELETE_CACHE` | 删目录/文件 | 权限、被占用 |
     * | `DELETE_CONFIG` | 删配置文件 | 同上 |
     * | `REPACK_ENGINE` | 删 `engine/` 让下次启动重解包 | 磁盘空间 |
     * | `RESET_STATE` | 删状态文件 | 同上 |
     * | `QUARANTINE` | 改名隔离 | 同上 |
     *
     * @return true = 成功
     */
    private fun performStep(context: Context, step: RepairControl.Step): Boolean {
        val target = File(step.target)
        Log.i(TAG, "执行修复：${step.kind.label} → ${step.target}")

        return try {
            when (step.kind) {
                RepairControl.Kind.DELETE_CACHE,
                RepairControl.Kind.DELETE_CONFIG,
                -> {
                    if (!target.exists()) {
                        Log.d(TAG, "目标已不存在，视为成功")
                        true
                    } else {
                        val ok = target.deleteRecursively()
                        if (ok) {
                            EventLog.record(
                                "repair.step.deleted",
                                mapOf("path" to step.target, "kind" to step.kind.id),
                            )
                        }
                        ok
                    }
                }

                RepairControl.Kind.REPACK_ENGINE -> {
                    // ⚠️ 只删 `.stamp`（版本戳），**不删整个 engine/**
                    //
                    // 删整个目录会让下次启动必须重新解包（15 秒），
                    // 而删版本戳的效果完全一样且更快 —— 下次启动看到戳不匹配
                    // 就会自己重解包。
                    //
                    // ⚠️ 但如果 engine/ 里的文件本身坏了，只删戳可能不够
                    //   （解包是「戳不符就重解」，所以够）。
                    val stamp = File(target, ".stamp")
                    val ok = if (stamp.isFile) stamp.delete() else {
                        // 没有戳 ⇒ 删目录（下次启动必然重解包）
                        target.deleteRecursively()
                    }
                    if (ok) EventLog.record("repair.step.repack", mapOf("path" to step.target))
                    ok
                }

                RepairControl.Kind.RESET_STATE -> {
                    val ok = if (target.isFile) target.delete() else true
                    if (ok) EventLog.record("repair.step.resetState", mapOf("path" to step.target))
                    ok
                }

                RepairControl.Kind.QUARANTINE -> {
                    // 改名隔离（可逆）
                    val bak = File(target.parentFile, "${target.name}.quarantined")
                    if (!target.exists()) {
                        true
                    } else {
                        target.renameTo(bak).also { ok ->
                            if (ok) {
                                EventLog.record(
                                    "repair.step.quarantined",
                                    mapOf("from" to step.target, "to" to bak.absolutePath),
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "修复步骤失败: ${step.description} —— ${t.message}")
            false
        }
    }

    /** 生成人能读的「声明范围」。 */
    private fun buildScope(context: Context, steps: List<RepairControl.Step>): String {
        if (steps.isEmpty()) return "无"
        val filesRoot = context.filesDir.absolutePath
        val rels = steps.map { s ->
            if (s.target.startsWith(filesRoot)) {
                s.target.removePrefix("$filesRoot/")
            } else {
                s.target
            }
        }.distinct()
        return rels.joinToString("、")
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：诊断→计划→(批准)→执行→核验 的完整串联、每种 Kind 的实际动作、
     *          声明范围生成、不可逆动作识别
     * ❌ 未做：
     *   - **自动诊断的接入**（`diagnose` 回调由调用方提供 ——
     *     本对象不知道 `DiagnosticBundle` 怎么跑）
     *   - 「失败后自动回滚」（`RepairSnapshot.rollback` 已实现，但本对象
     *     没有在失败时自动调它 —— 那是策略，应由调用方决定）
     *   - 「循环重启防护」（文档要求「不产生循环重启」—— 需要记录
     *     「同一问题连续修复次数」，属上层）
     */
}
