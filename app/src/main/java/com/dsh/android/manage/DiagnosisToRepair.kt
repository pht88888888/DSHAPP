package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **诊断 → 修复计划** —— 把诊断结论变成可执行、可批准的修复步骤。
 *
 * ## 在整条链路里的位置
 *
 * ```
 * DiagnosticBundle（发现"哪里坏了"）
 *        ↓
 *   【本对象】（映射成修复动作）
 *        ↓
 * RepairControl.propose（提交计划）
 *        ↓
 *   用户批准 → RepairControl.run（受控执行）
 * ```
 *
 * ## ⚠️ 本对象**只做映射，不做决策**
 *
 * 它不判断「该不该修」「用户想不想修」—— 那由用户通过批准决定。
 * 它的唯一职责是：给定一条诊断结论，说出「如果要修，应该做什么」。
 *
 * ## ⚠️ 三条设计纪律
 *
 * **① 没有对应修复时如实说「没有」，不硬凑**
 *
 * 有些问题（如 `heap` 内存不足）没有「一键修复」——
 * 硬凑一个「清理缓存」只会让用户以为修好了，而实际问题仍在。
 * 这种情况返回空列表，让上层显示「需要人工处理」。
 *
 * **② 修复动作必须**精确到目标**
 *
 * 「重解包引擎」这类动作的目标是整个 `engine/`；
 * 而「删除某个坏插件」必须精确到那个插件目录。
 * 含糊的「清理一下」既无法绑定批准，也无法核验结果。
 *
 * **③ 不可逆动作必须显式标注**
 *
 * 删除类动作会丢数据，必须在计划里声明副作用（[RepairControl.Plan.irreversibleSideEffects]），
 * 让用户在批准前看到。
 */
object DiagnosisToRepair {

    private const val TAG = "DSH"

    /**
     * 一条诊断结论（与 `DiagnosticBundle.Check` 同构，但**不依赖它**）。
     *
     * ⚠️ 刻意不直接引用 `DiagnosticBundle.Check`：
     *   那会让本对象依赖 `log` 包，而 `log` 包已经在依赖 `manage`
     *   （`ManageState` 等），形成循环依赖。
     */
    data class Finding(
        val id: String,
        val status: String,   // ok / warn / fail
        val detail: String,
    ) {
        val isProblem: Boolean get() = status == "warn" || status == "fail"
    }

    /**
     * 映射结果。
     *
     * @param steps 建议的修复步骤（空 = 没有可用修复）
     * @param reason 说明（为什么有/没有修复）
     */
    data class Suggestion(
        val steps: List<RepairControl.Step>,
        val reason: String,
        /** 该问题**没有**自动修复，需要人工处理。 */
        val needsManual: Boolean = false,
    )

    /**
     * 根据诊断结论生成修复建议。
     *
     * @param env 在哪个环境修
     */
    fun suggest(
        context: Context,
        findings: List<Finding>,
        env: ManageEnv.Env,
    ): Suggestion {
        val steps = mutableListOf<RepairControl.Step>()
        val notes = mutableListOf<String>()
        var needsManual = false

        for (f in findings) {
            if (!f.isProblem) continue
            when (f.id) {
                // ── 引擎目录损坏 ⇒ 重解包 ──────────────────────────────
                //
                // ⚠️ 这是**唯一**能修「engine/ 里的文件坏了」的动作。
                //   代价：15 秒重解包 + 会抹掉我们在 engine/ 里打的补丁
                //   （补丁会在下次启动时重新打，但中间那一刻是"裸"的）。
                "engine-start-failed" -> {
                    steps.add(
                        RepairControl.Step(
                            kind = RepairControl.Kind.REPACK_ENGINE,
                            target = ManageEnv.engineDir(context).absolutePath,
                            description = "重解包引擎（从 APK 资产重建 engine/）",
                        ),
                    )
                    notes.add("重解包会抹掉 engine/ 里的运行时补丁（下次启动自动重打）")
                }

                // ── 日志写不了 ⇒ 删日志目录 ────────────────────────────
                "file-logging" -> {
                    steps.add(
                        RepairControl.Step(
                            kind = RepairControl.Kind.DELETE_CACHE,
                            target = java.io.File(context.filesDir, "logs").absolutePath,
                            description = "清理日志目录（日志会重新生成）",
                        ),
                    )
                    notes.add("清理后历史日志会丢失，但新日志会重新开始记")
                }

                // ── 日志不完整 ⇒ 清导出缓存 ────────────────────────────
                "log-completeness" -> {
                    steps.add(
                        RepairControl.Step(
                            kind = RepairControl.Kind.DELETE_CACHE,
                            target = java.io.File(context.filesDir, "logs/export").absolutePath,
                            description = "清理诊断包导出缓存（可重新生成）",
                        ),
                    )
                }

                // ── 磁盘空间不足 ⇒ **没有自动修复** ────────────────────
                //
                // ⚠️ 刻意不提供「自动删东西腾空间」：
                //   删什么？删用户数据？那是不可接受的。
                //   正确做法是告诉用户「去清理」，由他自己决定删什么。
                "disk-space" -> {
                    needsManual = true
                    notes.add("磁盘空间不足：请手动清理手机存储（不建议自动删用户数据）")
                }

                // ── 内存不足 ⇒ **没有自动修复** ────────────────────────
                "heap" -> {
                    needsManual = true
                    notes.add("内存不足：请关闭其他应用后重试")
                }

                // ── 启动层级降级 ⇒ 提供"退出救援模式"的建议 ────────────
                "boot-tier" -> {
                    // ⚠️ 这里**不**自动生成「重置层级」的步骤：
                    //   降级是**结果**不是**原因**。直接重置会让用户
                    //   再次陷入同一个问题，而且丢掉了排查线索。
                    needsManual = true
                    notes.add("当前处于降级模式：建议先导出诊断包，再由 AI 分析具体原因")
                }

                // ── 崩溃现场 ⇒ 没有修复，只有诊断 ──────────────────────
                "crash-scenes" -> {
                    notes.add("存在崩溃现场：建议导出诊断包供分析（不是可自动修复的问题）")
                }

                // ── 上次退出异常 ⇒ 信息性 ──────────────────────────────
                "last-exit" -> {
                    notes.add("上次异常退出：如反复出现，请导出诊断包")
                }

                else -> {
                    Log.d(TAG, "未知诊断项（无对应修复）: ${f.id}")
                }
            }
        }

        val reason = when {
            steps.isNotEmpty() && notes.isNotEmpty() -> notes.joinToString("；")
            steps.isNotEmpty() -> "有 ${steps.size} 项可自动修复"
            needsManual -> notes.joinToString("；")
            notes.isNotEmpty() -> notes.joinToString("；")
            else -> "未发现需要修复的问题"
        }

        if (steps.isNotEmpty()) {
            EventLog.record(
                "repair.suggest",
                mapOf(
                    "steps" to steps.size.toString(),
                    "env" to env.id,
                    "manual" to needsManual.toString(),
                ),
            )
        }

        return Suggestion(steps, reason, needsManual)
    }

    /**
     * 生成一份**完整的修复计划**（含不可逆副作用声明）。
     *
     * ⚠️ 不可逆副作用从 [RepairControl.Kind.reversible] **自动推导** ——
     *   不让调用方手写，避免「写漏了某个不可逆动作」。
     */
    fun buildPlan(
        context: Context,
        suggestion: Suggestion,
        env: ManageEnv.Env,
        declaredScope: String,
    ): RepairControl.Plan? {
        if (suggestion.steps.isEmpty()) return null

        // 自动推导不可逆副作用
        val irreversible = suggestion.steps
            .filterNot { it.kind.reversible }
            .map { step ->
                "${step.kind.label}：${step.target}" +
                    if (step.description.isNotEmpty()) "（${step.description}）" else ""
            }
            .distinct()

        return RepairControl.propose(
            context = context,
            steps = suggestion.steps,
            env = env,
            declaredScope = declaredScope,
            irreversibleSideEffects = irreversible,
        )
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：7 类诊断结论的映射、无修复时如实说「需人工」、
     *          不可逆副作用自动推导、不硬凑修复动作
     * ❌ 未做：
     *   - **「某个插件坏了」的精确映射**（需要知道具体是哪个插件，
     *     而诊断结论里 `boot-tier` 只说「已隔离 N 个」——
     *     具体名单在 `rescue/state.json` 的 `removedBundles` 里，
     *     接入它才能生成「隔离这个插件」的精确步骤）
     *   - **修复后的结果核验**（`RepairControl.run` 只报执行成功/失败，
     *     没有「修完再跑一次诊断确认好了」的闭环）
     *   - 「按优先级排序」（多个问题时先修哪个 —— 当前按诊断顺序）
     */
}
