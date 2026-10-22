package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File

/**
 * **启动时的状态对账**。
 *
 * ## 它解决什么
 *
 * 设计说明 §6.5.2：
 *
 * > 宿主重建后**先取得监督权，再对账磁盘记录、实际进程、事务与激活组合**。
 * > 进程状态不明、锁无法确认或停止超时时，进入只读恢复，不盲目杀进程或
 * > 启动第二个可写实例。
 *
 * 现实场景：用户点「停止」，进程刚 `destroy()` 但还没确认，此时 App 被系统杀。
 * 下次启动时磁盘上留下的是：
 *
 * ```
 * phase = QUIESCING        ← 停止还没完成
 * desired = A, observed = A
 * pid = <已死的进程>
 * ```
 *
 * 没有对账的话，App 会带着这个「未完成」状态继续跑 —— 而
 * [ManageFsm.requestStart] 会因此拒绝启动，用户看到的是「App 打不开」。
 *
 * ## 对账做什么
 *
 * 1. **读磁盘状态**（[ManageState.read]）
 * 2. **看进程是否还活着**（由调用方传入，本类不持有进程引用）
 * 3. **判定该走哪条路**：继续 / 重置 / 只读恢复
 *
 * ## ⚠️ 三条不能违反的原则
 *
 * **① 不盲目杀进程**
 *
 * 文档明确「不盲目杀进程或启动第二个可写实例」。看到一个 PID 就 `kill -9`
 * 是危险的 —— PID 可能已被复用，杀掉的可能是系统进程。
 * 所以这里只**判断**，不动手。
 *
 * **② 进程状态不明时进只读恢复**
 *
 * 「不明」包括：PID 存在但启动时刻对不上（复用）、`/proc` 读不到。
 * 这时**不写任何东西**，让调用方走只读路径。
 *
 * **③ 对账结果必须落盘**
 *
 * 否则每次启动都要重新对账、每次都得到同一个「未完成」状态 ——
 * 永远卡在那里。
 */
object ManageReconcile {

    private const val TAG = "DSH"

    /**
     * 对账结论。
     *
     * ⚠️ 刻意不叫 `Success/Failure`：这里描述的是「**该做什么**」，
     * 不是「做成了没有」。调用方按 [action] 分派。
     */
    enum class Action(val id: String) {
        /** 状态干净，无需处理。 */
        CLEAN("clean"),

        /** 上一次转换由**本进程**写下且未完成 —— 可以安全重置后继续。 */
        RESET_STALE("reset-stale"),

        /** 上一次转换由**另一个已死的进程**写下 —— 重置但记事件。 */
        RESET_ORPHAN("reset-orphan"),

        /** 进程状态不明（PID 复用 / /proc 不可读）—— **只读恢复**，不写。 */
        READ_ONLY("read-only"),
        ;
    }

    /**
     * 对账结果。
     *
     * @param action 该做什么
     * @param before 对账前的状态（诊断用）
     * @param after 对账后的状态（[Action.READ_ONLY] 时等于 [before]）
     * @param detail 人可读的说明
     */
    data class Result(
        val action: Action,
        val before: ManageState.Snapshot,
        val after: ManageState.Snapshot,
        val detail: String,
    )

    /**
     * 执行对账。
     *
     * @param engineProcessAlive 引擎进程**当前**是否活着。
     *   ⚠️ 由调用方传，本类不持有 `Process` 引用 —— 那会让本类难以测试，
     *   也会把「判断」与「执行」耦合在一起。
     */
    fun reconcile(context: Context, engineProcessAlive: Boolean): Result {
        val before = ManageState.read(context)

        // ── 情况 1：状态干净，无需处理 ──────────────────────────────
        if (!before.isInFlight()) {
            return Result(Action.CLEAN, before, before, "状态干净")
        }

        // ── 情况 2：引擎进程还活着 ──────────────────────────────────
        //
        // 有未完成转换但进程活着 —— 这可能是：
        //   ① 上一次转换真的在进行中（多进程场景）
        //   ② 上一次转换失败但进程没退
        //
        // ⚠️ **不重置**：重置会抹掉「有转换正在进行」这个事实，
        //   让另一个正在做事的进程失去保护。交给调用方判断（通常是等或只读）。
        if (engineProcessAlive) {
            EventLog.record(
                "manage.reconcile.busy",
                mapOf("phase" to before.phase.id, "epoch" to before.epoch.toString()),
            )
            return Result(
                Action.READ_ONLY, before, before,
                "有未完成转换（phase=${before.phase.id}）且引擎进程仍在运行 —— 不重置，走只读",
            )
        }

        // ── 情况 3：进程不在，判断是谁写的 ──────────────────────────
        //
        // ⚠️ `isSameProcess()` 比的是 PID **加启动时刻**。只看 PID 会把
        //   「PID 被复用给别的进程」误判成「还是我自己」。
        val sameProc = before.isSameProcess()

        val detail = when {
            sameProc ->
                "上一次转换由本进程写下且未完成（phase=${before.phase.id}）—— 可安全重置"

            before.pid == 0 ->
                "状态里没有进程信息（旧格式或初始态）—— 按孤儿处理"

            else ->
                "上一次转换由另一进程（pid=${before.pid}）写下，该进程已不存在 —— 按孤儿处理"
        }

        // 重置到干净态：observed 追平 desired，phase 回 IDLE
        //
        // ⚠️ 用 `finishTransition` 而不是直接写字段：它会记事件，
        //   让「这次重置」在时间线上可追溯。手动对账最怕的就是「静默改了状态」。
        val after = ManageState.finishTransition(context, before.desired)

        EventLog.record(
            "manage.reconcile.reset",
            mapOf(
                "from" to before.phase.id,
                "epoch" to before.epoch.toString(),
                "sameProc" to sameProc.toString(),
            ),
        )
        Log.i(TAG, "对账重置：$detail")

        return Result(
            if (sameProc) Action.RESET_STALE else Action.RESET_ORPHAN,
            before, after, detail,
        )
    }

    /**
     * 检查是否有**遗留的临时文件**需要清理。
     *
     * [ManageState] 写状态时用 `xxx.tmp` → `rename`。进程如果死在两步之间，
     * 会留下 `.tmp` 文件。它不影响读取（读取只认正式文件），但会一直堆积。
     *
     * ⚠️ **只清 `.tmp`，不清正式文件**：正式文件哪怕内容是坏的也是证据。
     */
    fun cleanStaleTemp(context: Context): Int {
        val dir = File(context.filesDir, "manage")
        if (!dir.isDirectory) return 0
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") && f.isFile) {
                if (f.delete()) n++
            }
        }
        if (n > 0) {
            Log.i(TAG, "清理了 $n 个遗留的临时状态文件")
            EventLog.record("manage.reconcile.tmpCleaned", mapOf("count" to n.toString()))
        }
        return n
    }

    /*
     * ⚠️ **本对象只做 A 环境的对账**。
     *
     * 未实现（依赖 G2 后续）：
     * - 受管作业账本的对账（当前没有作业账本）
     * - 实际进程树核对（只信调用方传的布尔值）
     * - 激活组合对账（当前只有单一 A 环境）
     * - 事务记录对账（当前只有状态，没有独立的事务日志）
     *
     * 设计说明 §6.5.2 还要求「跟踪受管后代、后台任务与外部权限代理作业；
     * 不能只按进程名或父 PID 猜测」—— 这些都需要先有作业账本。
     */
}
