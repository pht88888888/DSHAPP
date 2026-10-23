package com.dsh.android.bubble

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **插件竞争仲裁** —— 多个插件抢悬浮窗控制权时由谁说了算。
 *
 * ## 文档要求
 *
 * §4.11：
 *
 * > 当前激活环境持有有效控制租约；**同一窗口多插件竞争由基座仲裁**，
 * > 不采用**无规则的最后写入覆盖**。
 *
 * > **用户手动设置优先级和重置语义明确**，插件重启**不能无条件覆盖**
 * > 用户后续修改。
 *
 * 验收矩阵第 16 条：
 *
 * > A/R/E 切换、插件停止/卸载后，旧悬浮控制租约与订阅失效，清除旧任务状态；
 * > **用户设置不被插件启动覆盖**，保留外观须遵循明确策略。
 *
 * ## ⚠️ 要解决的真实问题
 *
 * 现在 `applyAppearance()` 是「最后写入者赢」。两个插件同时想改悬浮窗时：
 *
 * ```
 * 插件 A（用户装的主题插件）设置红色
 * 插件 B（桌宠）设置蓝色
 * → 谁后写谁赢，用户看到的是随机的
 * ```
 *
 * 更糟的是**用户手动改的设置会被插件覆盖**：
 *
 * ```
 * 用户在设置页把球改成红色
 * 桌宠插件重启 → 重新下发它保存的外观 → 用户的红色没了
 * ```
 *
 * 文档明确禁止后者：「插件重启**不能无条件覆盖用户后续修改**」。
 *
 * ## 仲裁规则（本次明确）
 *
 * | 优先级 | 来源 | 说明 |
 * |---|---|---|
 * | 最高 | **用户手动** | 用户改过的字段，插件改不动 |
 * | 中 | 已注册插件（按注册顺序） | 先注册的赢（不是后写赢） |
 * | 最低 | 未注册来源 | 兼容旧调用方，允许但不参与竞争 |
 *
 * ## ⚠️ 为什么是「先注册的赢」而不是「后注册的赢」
 *
 * 「后注册赢」等价于「最后写入覆盖」，而文档明确禁止。
 *
 * 「先注册赢」的语义是：**第一个声明要控制悬浮窗的插件拿到控制权**，
 * 后来者不会把它挤掉。用户如果想让后来的赢，就去设置页手动改
 * （用户手动优先级最高）—— 那是**显式动作**。
 */
object PluginArbiter {

    private const val TAG = "DSH"

    /**
     * 控制权持有者。
     *
     * @param pluginId 插件标识（包名或 `user`）
     * @param acquiredAt 获取时刻
     * @param isUser 是否用户手动（用户永远优先）
     */
    data class Owner(
        val pluginId: String,
        val acquiredAt: Long,
        val isUser: Boolean,
    )

    /** 用户手动的固定 ID。 */
    const val USER_ID = "__user__"

    private const val PREFS = "dsh_bubble_arbiter"
    private const val KEY_LOCKED_FIELDS = "user_locked_fields"

    /**
     * 从磁盘恢复用户锁定的字段。
     *
     * ## ⚠️ 为什么必须持久化
     *
     * 文档：「**用户手动设置优先级和重置语义明确**」。
     *
     * 「重置语义明确」的前提是**它记得住**。不持久化的话：
     * - 用户锁了「颜色」，重启 App 后锁定没了
     * - 插件立刻把颜色改回去
     * - 用户会觉得「这个设置根本没用」
     *
     * 而且文档说「插件重启**不能无条件覆盖用户后续修改**」——
     * App 重启后插件当然也会重启，那时如果锁定丢了，就等于被覆盖了。
     *
     * ⚠️ **在 App 启动时调用一次**（[clearForEnvSwitch] 不清它，见那里的说明）。
     */
    fun restoreUserLocks(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCKED_FIELDS, null) ?: return
        synchronized(this) {
            userLockedFields.clear()
            raw.split('\u0000').filter { it.isNotEmpty() }.forEach { userLockedFields.add(it) }
            if (userLockedFields.isNotEmpty()) {
                Log.i(TAG, "仲裁：从磁盘恢复 ${userLockedFields.size} 个用户锁定字段")
            }
        }
    }

    /** 把用户锁定字段写盘。 */
    private fun persistUserLocks(context: Context) {
        val joined = synchronized(this) { userLockedFields.joinToString("\u0000") }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOCKED_FIELDS, joined).apply()
    }

    /** 当前持有者；`null` = 无人控制（任何插件都可以拿）。 */
    @Volatile
    private var owner: Owner? = null

    /**
     * 用户锁定过的字段名集合。
     *
     * ⚠️ **按字段而不是整体**：用户可能只想固定「颜色」，
     *   而让插件继续控制「动作」。整体锁定太粗，会废掉插件的主要功能。
     */
    private val userLockedFields = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 申请控制权。
     *
     * @param pluginId 申请者
     * @return true = 拿到/已持有；false = 被别的插件占着
     *
     * ⚠️ 同一插件重复申请返回 true（幂等）：插件重启后重新注册是正常行为，
     *   不该因为「它已经持有」而失败。
     */
    fun acquire(pluginId: String): Boolean {
        if (pluginId.isEmpty()) return false
        synchronized(this) {
            val cur = owner
            // 无人持有 ⇒ 拿到
            if (cur == null) {
                owner = Owner(pluginId, System.currentTimeMillis(), isUser = pluginId == USER_ID)
                EventLog.record("bubble.arbiter.acquire", mapOf("plugin" to pluginId))
                return true
            }
            // 自己已持有 ⇒ 幂等成功
            if (cur.pluginId == pluginId) return true
            // 用户持有 ⇒ 插件抢不到
            if (cur.isUser) {
                Log.i(TAG, "仲裁：$pluginId 申请被拒（用户手动控制中）")
                return false
            }
            // 另一个插件持有 ⇒ 拒绝（先注册赢）
            Log.i(TAG, "仲裁：$pluginId 申请被拒（${cur.pluginId} 已持有）")
            EventLog.record(
                "bubble.arbiter.rejected",
                mapOf("want" to pluginId, "owner" to cur.pluginId),
            )
            return false
        }
    }

    /**
     * 用户手动接管（永远成功）。
     *
     * ⚠️ 这会**踢掉**当前插件持有者。理由：文档说「用户手动设置优先级…
     *   插件重启不能无条件覆盖用户后续修改」—— 用户动作必须最高。
     */
    fun acquireByUser() {
        synchronized(this) {
            val prev = owner?.pluginId
            owner = Owner(USER_ID, System.currentTimeMillis(), isUser = true)
            if (prev != null && prev != USER_ID) {
                Log.i(TAG, "仲裁：用户接管，踢掉 $prev")
                EventLog.record("bubble.arbiter.userTookOver", mapOf("from" to prev))
            }
        }
    }

    /**
     * 释放控制权。
     *
     * @param pluginId 释放者。**不是持有者时什么都不做** ——
     *   一个没拿到控制权的插件不该能把别人的控制权释放掉。
     */
    fun release(pluginId: String) {
        synchronized(this) {
            val cur = owner ?: return
            if (cur.pluginId != pluginId) {
                Log.w(TAG, "仲裁：$pluginId 试图释放 ${cur.pluginId} 的控制权（忽略）")
                return
            }
            owner = null
            EventLog.record("bubble.arbiter.release", mapOf("plugin" to pluginId))
        }
    }

    /**
     * 检查某个插件能否改某个字段。
     *
     * @return true = 可以改
     *
     * ⚠️ 两条判据：
     *   1. 必须持有控制权（或无人持有）
     *   2. 该字段**不能是用户锁定的**
     */
    fun canWrite(pluginId: String, field: String): Boolean {
        // ① 用户锁定的字段，任何插件都改不动
        if (userLockedFields.contains(field)) {
            Log.d(TAG, "仲裁：$pluginId 改 $field 被拒（用户已锁定该字段）")
            return false
        }
        // ② 必须持有控制权
        val cur = owner ?: return true   // 无人持有 ⇒ 放行
        if (cur.isUser) return false     // 用户控制中 ⇒ 插件不能改
        return cur.pluginId == pluginId
    }

    /**
     * 用户锁定一个字段。
     *
     * 文档：「用户手动设置优先级和**重置语义明确**」——
     * 所以这里只有「锁」与「解锁」两个显式动作，没有隐式的自动解锁。
     */
    fun lockFieldByUser(context: Context, field: String) {
        userLockedFields.add(field)
        persistUserLocks(context)
        EventLog.record("bubble.arbiter.lockField", mapOf("field" to field))
    }

    /** 用户解锁一个字段。 */
    fun unlockFieldByUser(context: Context, field: String) {
        userLockedFields.remove(field)
        persistUserLocks(context)
        EventLog.record("bubble.arbiter.unlockField", mapOf("field" to field))
    }

    /**
     * 插件被停用/卸载时的清理。
     *
     * ## 文档要求（§4.11）
     *
     * > **插件停止/卸载时立即撤销运行控制**；已保存外观仅按用户明确的
     * > 保留策略继续使用，否则回退基础入口。
     *
     * ⚠️ **与 [release] 的区别**：`release` 是插件**主动**放弃控制权；
     *   本函数是基座**被动**发现「这个插件已经不在了」。
     *   两者都要做，因为：
     *   - 正常卸载时插件会先调 `release`（礼貌路径）
     *   - 插件崩溃/被强杀时不会调，只能靠基座发现（[sweepDeadOwners]）
     */
    fun onPluginUnloaded(pluginId: String) {
        synchronized(this) {
            val cur = owner ?: return
            if (cur.pluginId != pluginId) return
            owner = null
            Log.i(TAG, "仲裁：插件 $pluginId 已卸载，撤销其控制权")
            EventLog.record("bubble.arbiter.pluginUnloaded", mapOf("plugin" to pluginId))
        }
    }

    /**
     * 清扫「持有者还占着但插件已经不在了」的情况。
     *
     * @param isAlive 调用方提供的存活判定（由它决定怎么查 —— 本对象不依赖
     *   插件管理器，避免形成循环依赖）
     *
     * ⚠️ 用户持有（[USER_ID]）**不清扫**：用户不是一个会「死掉」的插件。
     */
    fun sweepDeadOwners(isAlive: (String) -> Boolean) {
        synchronized(this) {
            val cur = owner ?: return
            if (cur.isUser) return
            if (isAlive(cur.pluginId)) return
            owner = null
            Log.i(TAG, "仲裁：持有者 ${cur.pluginId} 已不存在，撤销控制权")
            EventLog.record("bubble.arbiter.swept", mapOf("plugin" to cur.pluginId))
        }
    }

    /** 该字段是否被用户锁定。 */
    fun isFieldLocked(field: String): Boolean = userLockedFields.contains(field)

    /** 当前持有者（诊断/UI 用）。 */
    fun currentOwner(): Owner? = owner

    /**
     * 环境切换时清空（文档：撤销旧租约）。
     *
     * ⚠️ **保留用户锁定的字段**：那些是用户偏好，与环境无关。
     *   清掉它们会让用户每次换环境都要重新锁一遍。
     */
    fun clearForEnvSwitch(reason: String) {
        synchronized(this) {
            val prev = owner?.pluginId
            owner = null
            if (prev != null) {
                Log.i(TAG, "仲裁：清空控制权（$reason，原持有者 $prev）")
                EventLog.record(
                    "bubble.arbiter.cleared",
                    mapOf("reason" to reason, "prev" to prev),
                )
            }
        }
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：先注册赢、用户永远优先、按字段锁定、非持有者不能释放、
     *          换环境清空但保留用户偏好
     * ❌ 未做：
     *   - **接入 applyAppearance**（现在还没检查 canWrite）
     *   - 「插件停止/卸载时立即撤销」（需要插件生命周期钩子，见下一步）
     *   - 多窗口（当前只有一个悬浮窗，`owner` 是全局单例）
     *   - 用户设置的持久化（`userLockedFields` 只在内存里，重启丢失）
     */
}
