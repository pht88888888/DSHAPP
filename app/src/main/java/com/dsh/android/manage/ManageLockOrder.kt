package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **锁顺序管理器** —— 强制「监督锁 → 环境锁 → 发布锁」的固定获取顺序。
 *
 * ## 开发指导 §G3 任务 1 的原文
 *
 * > 定义**监督锁 → 环境管理锁 → 资源/备份发布锁**的固定顺序，
 * > **禁止边持锁等待用户无限输入**；批准后再取锁并复核条件。
 *
 * ## ⚠️ 为什么必须有固定顺序（死锁分析）
 *
 * 三层锁如果允许任意顺序获取，经典死锁立刻成立：
 *
 * ```
 * 线程 T1: 拿监督锁 → 等环境锁
 * 线程 T2: 拿环境锁 → 等监督锁      ← 双方永远等下去
 * ```
 *
 * 固定顺序（只能从小到大拿）在结构上排除这种可能：
 *
 * ```
 * Level.SUPERVISOR (1) → Level.ENV (2) → Level.PUBLISH (3)
 * ```
 *
 * ## ⚠️ 第二条规则：「禁止边持锁等待用户无限输入」
 *
 * 这是比死锁更隐蔽的问题：**持锁弹一个对话框**，用户去吃饭了，
 * 整个 App 的管理功能全部卡住 —— 而且没有任何报错，看起来像卡死。
 *
 * 所以本类提供 [checkNotHoldingWhileWaiting]：调用方在弹对话框/等用户输入前
 * 必须问一句，持锁时**不允许**等。
 *
 * ## ⚠️ 与 [ManageLock] 的关系
 *
 * [ManageLock] 是**跨进程**的监督锁（`FileChannel.tryLock`）。
 * 本对象是**进程内**的层级协调 —— 它管的是「同一进程里多条管理路径
 * 别互相踩」，而不是「两个进程别同时改文件」。
 *
 * 两者都需要：
 * - 跨进程互斥靠 [ManageLock]（防两个 App 实例）
 * - 进程内顺序靠本对象（防同一实例内的并发路径死锁）
 */
object ManageLockOrder {

    private const val TAG = "DSH"

    /**
     * 锁层级。**数字小的必须先获取**。
     *
     * ⚠️ 顺序不可调整：它不是随意排的，而是按「谁包含谁」排的 ——
     *   监督锁保护整个管理面（包含环境），环境锁保护一个环境（包含发布）。
     *   反过来排会让「拿环境锁时还要拿监督锁」成为必然，而死锁就成立。
     */
    enum class Level(val rank: Int, val label: String) {
        /** 第一层：监督锁（整个管理面的互斥）。 */
        SUPERVISOR(1, "监督锁"),

        /** 第二层：环境管理锁（某个环境的写互斥）。 */
        ENV(2, "环境锁"),

        /** 第三层：发布锁（备份发布、包落盘这类 IO）。 */
        PUBLISH(3, "发布锁"),
        ;
    }

    /** 当前线程已持有的层级（按获取顺序）。 */
    // ⚠️ **不用 `ThreadLocal.withInitial`**：它的 `Supplier<T>` 在 Android 的
    //   平台上签名返回可空 `T?`，于是 `stack()` 的类型是
    //   `MutableList<Level>?` —— 40+ 处「可空接收者上的不安全调用」警告，
    //   而且真的可能在运行时 NPE。
    //
    //   显式 `ThreadLocal<MutableList<Level>>` 标注也压不住（平台签名如此）。
    //
    //   改用**普通 ThreadLocal + 显式判空**：语义完全等价（第一次访问时创建），
    //   但类型干净、没有 NPE 风险，也不需要任何警告抑制。
    private val held = ThreadLocal<MutableList<Level>>()

    /** 取当前线程的持锁栈（首次访问时创建）。 */
    private fun stack(): MutableList<Level> {
        var s = held.get()
        if (s == null) {
            s = mutableListOf()
            held.set(s)
        }
        return s
    }

    /**
     * 检查能否获取某一层。
     *
     * @return null = 可以；非 null = 拒绝原因
     *
     * ⚠️ 拒绝条件：**已持有更高或同层的锁**。
     *   同层重复获取也是错误 —— 它意味着同一段逻辑被重入了，
     *   而那通常是设计问题（而不是需要可重入锁）。
     */
    fun checkAcquire(level: Level): String? {
        val cur = stack()
        val highest = cur.maxByOrNull { it.rank }
        if (highest != null && highest.rank >= level.rank) {
            return "已持有「${highest.label}」，不能再获取「${level.label}」" +
                "（违反顺序 ${Level.entries.joinToString(" → ") { it.label }}）"
        }
        return null
    }

    /**
     * 获取一层锁（**只做记账**，真正的锁由调用方持有）。
     *
     * ⚠️ 本对象不提供实际的互斥原语：那需要按场景选
     *   （跨进程用 `FileChannel`、进程内用 `synchronized`）。
     *   这里只保证**顺序**这个容易写错的部分。
     *
     * @return 记账句柄；违反顺序时返回 null 并记事件
     */
    fun acquire(level: Level): Handle? {
        val problem = checkAcquire(level)
        if (problem != null) {
            Log.w(TAG, "锁顺序违规: $problem")
            EventLog.record(
                "manage.lockorder.violation",
                mapOf("want" to level.label, "problem" to problem),
            )
            return null
        }
        stack().add(level)
        return Handle(level)
    }

    /**
     * 释放记账。
     *
     * ⚠️ 必须按**逆序**释放（最后获取的最先释放）。
     *   顺序释放本身不会死锁，但会让「当前持有层级」的记录错乱，
     *   从而让后续的顺序检查失效。
     */
    class Handle internal constructor(private val level: Level) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            val cur = stack()
            val idx = cur.lastIndexOf(level)
            if (idx >= 0) cur.removeAt(idx)
        }
    }

    /**
     * 检查「持锁时是否在等用户输入」。
     *
     * 开发指导明确「**禁止边持锁等待用户无限输入**」。
     *
     * 调用方在弹对话框 / 等用户确认之前必须问一句：
     *
     * ```kotlin
     * // ❌ 错：持锁弹框
     * val h = ManageLockOrder.acquire(Level.ENV)
     * showDialogAndWait()          // 用户去吃饭了，锁永远不放
     *
     * // ✅ 对：先批准、再取锁
     * val approved = showDialogAndWait()
     * if (!approved) return
     * val h = ManageLockOrder.acquire(Level.ENV)
     * recheckConditions()          // 复核：等用户期间条件可能变了
     * ```
     *
     * @return null = 没持锁，可以等；非 null = 持着锁，**不能等**
     */
    fun checkNotHoldingWhileWaiting(): String? {
        val cur = stack()
        if (cur.isEmpty()) return null
        return "当前持有「${cur.joinToString("、") { it.label }}」，不能在此等待用户输入"
    }

    /** 当前线程持有的层级（诊断用）。 */
    fun currentLevels(): List<Level> = stack().toList()

    /** 清空（仅测试与异常兜底用）。 */
    fun reset() {
        stack().clear()
    }

    /**
     * 复核条件 —— 用户批准之后、真正动手之前必须调。
     *
     * ## 为什么需要它
     *
     * 开发指导：「批准后再取锁并**复核条件**」。
     *
     * 因为「用户看到对话框 → 点确认」之间可能过了几分钟，而这期间：
     * - 环境可能被别处改了（revision 漂移）
     * - 空间可能被占满了
     * - 另一个流程可能已经做完了同样的事
     *
     * 不复核就直接动手，等于用几分钟前的判断操作现在的系统。
     *
     * @param recheck 复核逻辑，返回 null = 条件仍成立；非 null = 已失效的原因
     * @return null = 可以继续；非 null = 应中止的原因
     */
    fun recheckAfterApproval(recheck: () -> String?): String? {
        val problem = recheck()
        if (problem != null) {
            EventLog.record("manage.lockorder.recheckFailed", mapOf("reason" to problem))
        }
        return problem
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：层级顺序强制、逆序释放、持锁等待检查、批准后复核入口
     * ❌ 未做：
     *   - **实际的锁原语**（跨进程靠 ManageLock，进程内靠调用方的 synchronized）
     *   - 超时与取消规则（文档要求「锁顺序与取消规则在实现前明确」）
     *   - 跨线程的持有状态（当前是 ThreadLocal，只看当前线程）
     *   - 「不无差别阻塞全部普通任务」（文档要求环境锁只挡同环境，
     *     当前本对象不区分环境 —— 那需要按 env 分桶，属后续工作）
     */
}
