package com.dsh.android.bubble

import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **多会话状态聚合器** —— 决定悬浮窗该显示哪个会话的状态。
 *
 * ## 文档要求
 *
 * 验收矩阵第 18 条：
 *
 * > **多会话事件有明确跟随/聚合规则**，乱序或过期事件不覆盖当前状态
 *
 * ## ⚠️ 要解决的真实问题
 *
 * 引擎可以同时跑多个会话（子代理、后台任务）。每个会话都有自己的
 * `running / text / activity`。而悬浮窗只有**一个**显示位。
 *
 * 现有实现（`FloatingBubble.deriveRunState(status)`）接受**单一**状态 ——
 * 于是多个会话的状态会互相覆盖，最后写入者赢。
 *
 * 具体症状：
 *
 * ```
 * 会话 A 在跑工具     → 球显示「干活中」
 * 会话 B 在等确认     → 球显示「等你确认」
 * 会话 A 的下一个事件 → 球又变回「干活中」
 * ```
 *
 * 用户看到球在两种状态间乱跳，而**实际上 B 一直在等他确认**——
 * 那是最需要被看见的状态。
 *
 * ## 聚合规则（本次明确，文档要求「规则明确」）
 *
 * 优先级从高到低：
 *
 * | 优先级 | 状态 | 为什么 |
 * |---|---|---|
 * | 1 | **等待确认** | 用户不动它就一直卡着，必须最显眼 |
 * | 2 | 干活中（工具/命令） | 正在消耗资源，用户想知道 |
 * | 3 | 思考中 | 有进展但无副作用 |
 * | 4 | 空闲 | 最低 |
 *
 * ⚠️ **同优先级时按「最近活跃」选**，而不是按会话 ID 排序 ——
 *   用户关心的是「刚刚在动的那个」，不是「字母序靠前的那个」。
 *
 * ## ⚠️ 与 [BubbleEventGate] 的分工
 *
 * | 对象 | 管什么 |
 * |---|---|
 * | [BubbleEventGate] | **时序**：这条事件该不该受理（迟到/旧租约） |
 * | 本对象 | **来源**：多个会话都在报状态时显示哪个 |
 *
 * 两者串联：先过闸门（时序合法），再过聚合（选出代表）。
 */
object SessionAggregator {

    private const val TAG = "DSH"

    /** 一个会话的运行状态。 */
    data class SessionState(
        /** 会话 ID（引擎的 sessionId）。 */
        val sessionId: String,
        /** 是否正在跑。 */
        val running: Boolean,
        /** 运行类别：`waiting` / `working` / `thinking` / `idle`。 */
        val kind: String,
        /** 展示文案。 */
        val text: String,
        /** 最近活跃时刻（用于同优先级排序）。 */
        val lastActiveAt: Long,
    ) {
        /** 优先级数字越小越优先。 */
        val priority: Int
            get() = when {
                !running -> 4
                kind == KIND_WAITING -> 1
                kind == KIND_WORKING -> 2
                else -> 3
            }
    }

    const val KIND_WAITING = "waiting"
    const val KIND_WORKING = "working"
    const val KIND_THINKING = "thinking"
    const val KIND_IDLE = "idle"

    /** 最多跟踪多少会话。超了淘汰最久不活跃的。 */
    private const val MAX_SESSIONS = 32

    /** 当前所有会话的状态（sessionId → 状态）。 */
    private val sessions = LinkedHashMap<String, SessionState>()

    /**
     * 更新一个会话的状态。
     *
     * @param kind 见 [KIND_WAITING] 等常量
     */
    fun update(sessionId: String, running: Boolean, kind: String, text: String) {
        if (sessionId.isEmpty()) {
            // ⚠️ 没有会话 ID 的状态**不丢弃**，但也无法参与聚合。
            //   调用方应该保证有 ID —— 这里只记一次告警，避免刷屏。
            Log.w(TAG, "会话聚合：收到无 sessionId 的状态更新（已忽略）")
            return
        }
        synchronized(sessions) {
            sessions[sessionId] = SessionState(
                sessionId = sessionId,
                running = running,
                kind = if (running) kind else KIND_IDLE,
                text = text,
                lastActiveAt = System.currentTimeMillis(),
            )
            // 淘汰最久不活跃的
            while (sessions.size > MAX_SESSIONS) {
                val oldest = sessions.values.minByOrNull { it.lastActiveAt } ?: break
                sessions.remove(oldest.sessionId)
                Log.d(TAG, "会话聚合：淘汰久不活跃的 ${oldest.sessionId}")
            }
        }
    }

    /**
     * 移除一个会话（会话结束/删除时）。
     *
     * ⚠️ 必须显式移除而不是等淘汰：一个已结束的会话如果还留着，
     *   它的「运行中」状态会**永远**参与聚合，让球一直转。
     */
    fun remove(sessionId: String) {
        synchronized(sessions) { sessions.remove(sessionId) }
    }

    /**
     * 选出该显示的代表状态。
     *
     * @return `null` = 没有任何会话在跑（全部空闲）
     */
    fun representative(): SessionState? {
        val snapshot = synchronized(sessions) { sessions.values.toList() }
        if (snapshot.isEmpty()) return null

        // 只在**运行中**的会话里选：全部空闲时返回 null（让调用方显示 idle）
        val active = snapshot.filter { it.running }
        if (active.isEmpty()) return null

        // 排序：优先级 → 最近活跃
        return active.minWithOrNull(
            compareBy<SessionState> { it.priority }
                .thenByDescending { it.lastActiveAt },
        )
    }

    /**
     * 汇总摘要（诊断与「N 个任务」这类文案用）。
     */
    fun summary(): Summary {
        val snapshot = synchronized(sessions) { sessions.values.toList() }
        return Summary(
            total = snapshot.size,
            running = snapshot.count { it.running },
            waiting = snapshot.count { it.running && it.kind == KIND_WAITING },
        )
    }

    data class Summary(val total: Int, val running: Int, val waiting: Int)

    /**
     * 清空（环境切换时）。
     *
     * ## ⚠️ 文档要求
     *
     * §4.11：「切换环境时撤销旧租约、事件订阅和排队控制，**清除旧任务状态**；
     * 可以保留已缓存外观，但**不得继续显示旧任务正在运行**」。
     *
     * 所以换环境时必须清空 —— 否则旧环境的「运行中」会让球一直转，
     * 而那个任务早就不存在了。
     */
    fun clear(reason: String) {
        val n = synchronized(sessions) {
            val c = sessions.size
            sessions.clear()
            c
        }
        if (n > 0) {
            Log.i(TAG, "会话聚合：清空 $n 个会话状态（$reason）")
            EventLog.record(
                "bubble.aggregate.cleared",
                mapOf("count" to n.toString(), "reason" to reason),
            )
        }
    }

    /** 当前跟踪的会话数（诊断用）。 */
    fun size(): Int = synchronized(sessions) { sessions.size }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：多会话状态跟踪、优先级聚合、同优先级按最近活跃、
     *          显式移除、容量上限、换环境清空
     * ❌ 未做：
     *   - **接入 FloatingBubble**（现有 deriveRunState 还没改成用它）
     *   - 会话 ID 的来源（现有 EngineStatus 没有 sessionId 字段，
     *     需要从页面桥或插件侧补 —— 那是接入工作）
     *   - 「插件竞争仲裁」（那是另一层：多个**插件**抢控制权，不是多会话）
     *   - 会话跟随（用户手动锁定「只看某个会话」）
     */
}
