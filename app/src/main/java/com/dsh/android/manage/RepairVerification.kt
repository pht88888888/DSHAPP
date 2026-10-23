package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **修复结果核验** —— 修完之后确认「真的修好了吗」。
 *
 * ## 为什么需要它
 *
 * 没有核验的话，修复流程是：
 *
 * ```
 * 执行修复 → 返回"成功" → 结束
 * ```
 *
 * 而「执行成功」**不等于**「问题解决」：
 *
 * | 场景 | 执行 | 实际结果 |
 * |---|---|---|
 * | 重解包引擎 | 成功删了旧目录、解了新目录 | 资产本身损坏 ⇒ 新解包也坏 |
 * | 删日志目录 | 成功删除 | 磁盘满 ⇒ 新日志立刻写失败 |
 * | 隔离插件 | 成功改名 | 还有**另一个**坏插件 ⇒ 仍然起不来 |
 *
 * 所以必须**重新跑一次诊断**，对比修复前后的结论。
 *
 * ## ⚠️ 三种结论（不能只有两种）
 *
 * | 结论 | 含义 |
 * |---|---|
 * | [Verdict.Fixed] | 问题项消失了 |
 * | [Verdict.Improved] | 变好但没完全好（如 fail → warn） |
 * | [Verdict.Unchanged] | 没变化 |
 * | [Verdict.Worsened] | 变差了（修复引入了新问题） |
 *
 * ⚠️ **必须有 `Worsened`**：修复本身可能出错（比如重解包时磁盘满了），
 *   不检测的话用户会以为「越修越坏」而不知道为什么。
 *
 * ## ⚠️ 与「不假报已撤销」的关系
 *
 * `Worsened` 时必须**如实报告**，不能因为「我们的修复逻辑看起来没问题」
 * 就假设是自己的误判。
 */
object RepairVerification {

    private const val TAG = "DSH"

    /**
     * 核验结论。
     */
    enum class Verdict(val id: String, val label: String) {
        /** 问题项消失了。 */
        FIXED("fixed", "已修复"),

        /** 变好但没完全好（fail → warn，或问题数减少但未归零）。 */
        IMPROVED("improved", "有改善但未完全修复"),

        /** 没变化。 */
        UNCHANGED("unchanged", "无变化"),

        /** 变差了（修复引入了新问题）。 */
        WORSENED("worsened", "变差了"),

        /** 无法判断（诊断本身跑不起来）。 */
        UNKNOWN("unknown", "无法核验"),
        ;
    }

    /**
     * 核验结果。
     *
     * @param verdict 结论
     * @param before 修复前的问题项
     * @param after 修复后的问题项
     * @param newProblems 修复后**新出现**的问题（`Worsened` 时非空）
     * @param resolvedProblems 修复后消失的问题
     */
    data class Result(
        val verdict: Verdict,
        val before: List<DiagnosisToRepair.Finding>,
        val after: List<DiagnosisToRepair.Finding>,
        val newProblems: List<DiagnosisToRepair.Finding> = emptyList(),
        val resolvedProblems: List<DiagnosisToRepair.Finding> = emptyList(),
    ) {
        /** 人可读的一句话结论。 */
        fun summary(): String = when (verdict) {
            Verdict.FIXED -> "已修复：${resolvedProblems.size} 项问题消失"
            Verdict.IMPROVED ->
                "有改善：${resolvedProblems.size} 项消失，" +
                    "${after.size} 项仍存在"
            Verdict.UNCHANGED -> "无变化：${after.size} 项问题仍存在"
            Verdict.WORSENED ->
                "⚠️ 变差了：新出现 ${newProblems.size} 项问题" +
                    "（${newProblems.take(2).joinToString("、") { it.id }}）"
            Verdict.UNKNOWN -> "无法核验（诊断本身跑不起来）"
        }
    }

    /**
     * 对比修复前后的诊断结论。
     *
     * ## ⚠️ 判据用「问题项集合」而不是「数量」
     *
     * 只看数量会漏掉「修好 A 但弄坏了 B」：总数不变，但情况完全不同。
     * 所以按 `id` 做集合运算。
     *
     * ⚠️ 同一个 `id` 的 status 可能变化（`fail` → `warn`），
     *   那算「改善」而不是「消失」。
     */
    fun compare(
        before: List<DiagnosisToRepair.Finding>,
        after: List<DiagnosisToRepair.Finding>,
    ): Result {
        val beforeProblems = before.filter { it.isProblem }
        val afterProblems = after.filter { it.isProblem }

        val beforeIds = beforeProblems.map { it.id }.toSet()
        val afterIds = afterProblems.map { it.id }.toSet()

        val resolvedIds = beforeIds - afterIds
        val newIds = afterIds - beforeIds

        val resolved = beforeProblems.filter { it.id in resolvedIds }
        val newProblems = afterProblems.filter { it.id in newIds }

        val verdict = when {
            // ⚠️ 先判 Worsened：新问题比"没修好"严重得多
            newProblems.isNotEmpty() -> Verdict.WORSENED
            afterProblems.isEmpty() -> Verdict.FIXED
            resolved.isNotEmpty() -> Verdict.IMPROVED
            // 数量不变但 status 有改善（如 fail → warn）
            hasStatusImprovement(beforeProblems, afterProblems) -> Verdict.IMPROVED
            else -> Verdict.UNCHANGED
        }

        EventLog.record(
            "repair.verify.result",
            mapOf(
                "verdict" to verdict.id,
                "before" to beforeProblems.size.toString(),
                "after" to afterProblems.size.toString(),
                "new" to newProblems.size.toString(),
            ),
        )
        Log.i(TAG, "修复核验: ${verdict.id}（前 ${beforeProblems.size} → 后 ${afterProblems.size}）")

        return Result(verdict, beforeProblems, afterProblems, newProblems, resolved)
    }

    /**
     * 是否有 status 改善（`fail` → `warn`）。
     *
     * ⚠️ 只比较**同一个 id** 的前后状态。
     */
    private fun hasStatusImprovement(
        before: List<DiagnosisToRepair.Finding>,
        after: List<DiagnosisToRepair.Finding>,
    ): Boolean {
        val beforeMap = before.associateBy { it.id }
        return after.any { a ->
            val b = beforeMap[a.id] ?: return@any false
            b.status == "fail" && a.status == "warn"
        }
    }

    /**
     * 完整闭环：执行修复 → 重新诊断 → 核验。
     *
     * ## ⚠️ 为什么放在这里而不是 `RepairControl.run`
     *
     * `RepairControl` 不知道「怎么诊断」—— 那需要 `DiagnosticBundle`
     * （在 `log` 包，而 `log` 依赖 `manage`，反向引用会成环）。
     *
     * 所以本对象接受一个 `diagnose` 回调，由调用方注入。
     *
     * @param diagnose 跑一次诊断，返回当前的问题项列表。
     *   ⚠️ 返回 `null` = 诊断本身跑不起来（那就 [Verdict.UNKNOWN]）
     */
    fun runAndVerify(
        context: Context,
        plan: RepairControl.Plan,
        approval: RepairControl.Approval?,
        channel: String,
        before: List<DiagnosisToRepair.Finding>,
        execute: (RepairControl.Step) -> Boolean,
        diagnose: () -> List<DiagnosisToRepair.Finding>?,
    ): Pair<RepairControl.Result, Result> {
        // ① 执行
        val execResult = RepairControl.run(context, plan, approval, channel, execute)

        // ② 只有真的执行了才核验
        //
        // ⚠️ 被拒绝时**不核验**：什么都没做，核验结果必然是「无变化」，
        //   那会误导用户以为「修了但没用」。
        if (execResult !is RepairControl.Result.Executed) {
            return execResult to Result(
                Verdict.UNKNOWN, before, before,
            )
        }

        // ③ 重新诊断
        val after = try {
            diagnose()
        } catch (t: Throwable) {
            Log.w(TAG, "修复后诊断失败: ${t.message}")
            null
        }
        if (after == null) {
            EventLog.record("repair.verify.diagnoseFailed")
            return execResult to Result(Verdict.UNKNOWN, before, before)
        }

        // ④ 对比
        return execResult to compare(before, after)
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：集合式对比（不只看数量）、四种结论（含 Worsened）、
     *          status 改善检测、诊断失败时如实报 UNKNOWN、
     *          被拒绝时不核验
     * ❌ 未做：
     *   - **自动重试**（`IMPROVED` 时要不要再修一次 —— 那是策略，
     *     应由调用方决定，本对象只报事实）
     *   - 「同一问题换了表现形式」的检测（如 `disk-space` 从「不足」
     *     变成「极不足」—— 当前都算同一个 id，不区分程度）
     *   - 核验的时间窗口（有些问题需要**等一会**才显现，如内存泄漏 ——
     *     当前是立即核验）
     */
}
