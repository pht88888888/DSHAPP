package com.dsh.android.engine

import com.dsh.android.log.EventLog
import com.dsh.android.log.RescueAutoRetry
import com.dsh.android.log.RescueBoot

/**
 * 本次启动该用哪个 profile —— 含「无视过期救援层级、回正常 profile 试一次」的自愈决策。
 *
 * ## 为什么需要它（真机事实，2026-11-12，CDY-TN90）
 *
 * `targetProfile` 只看 `RescueBoot.tier()`；而 `tier()` 只在「用**正常** profile
 * 起成功」或用户点「退出救援模式」时复位。一旦写进 `RESCUE`，之后每次启动用的都是
 * 救援 profile —— 那条复位路径**永远走不到**，层级于是永远粘着。真机现场：一次
 * 前端慢导致的误判让 App 连续两次启动都停在救援态，`onUiReady` 一次都没再出现。
 *
 * 额度、冷却与上限在 [RescueAutoRetry]；这里只负责把它翻成「用哪个 profile」。
 *
 * ## ⚠️ [retryNormal] 必须在整轮启动里可见
 *
 * 自愈重试的那一轮，`RescueBoot.tier()` 里躺着的是**过期**的 RESCUE。凡是按 tier
 * 决定要不要另作处理的地方都得先看这个标记，否则会立刻把重试推回救援：
 *
 *   · [EngineManager.maybeIsolateBadBundles] 的入口闸门（tier != NORMAL 就跳过）；
 *   · 启动前那次「L1 可能刚把层级改成 RESCUE ⇒ 不发车，改走救援 profile」的重判。
 *
 * 真机现场 16:08：自愈重试刚拉起 `--profile web`，250 毫秒后就被那条重判杀掉、
 * 改回 `--profile rescue`，2 次额度在 5 秒内被消耗光，自愈**完全无效**。
 */
internal object BootProfilePlan {

    /** 本次启动是否是自愈重试（见类注释）。由 [resolve] 每次启动重算，别处不改。 */
    @Volatile
    var retryNormal: Boolean = false
        private set

    /**
     * 解析本次要用的 profile。
     *
     * @param normalProfile 正常 profile 名（`web`）。
     * @param rescueProfile 救援 profile 名（`rescue`）。
     * @return 应当传给引擎的 `--profile`。
     */
    fun resolve(normalProfile: String, rescueProfile: String): String {
        retryNormal = RescueAutoRetry.shouldRetry()
        if (retryNormal) {
            EventLog.record("rescue.auto-retry", mapOf("profile" to normalProfile))
            return normalProfile
        }
        return if (RescueBoot.tier().tier == RescueBoot.Tier.RESCUE) rescueProfile else normalProfile
    }

    /**
     * L1 隔离的入口闸门该不该放行。
     *
     * NORMAL / QUARANTINED 放行是原有语义（QUARANTINED 表示「上一轮禁了一个坏插件、
     * 还没验证成功」，是**进行中**不是终点，挡掉会让用户卡在「每次启动禁同一个插件、
     * 每次起不来」的死循环里）。自愈重试那一轮的 RESCUE 是**过期**值，也必须放行 ——
     * 否则「跳过隔离 ⇒ 又把层级当成救援 ⇒ 掉回救援 profile」成为死循环。
     */
    fun isolationAllowed(tier: RescueBoot.Tier): Boolean = when {
        tier == RescueBoot.Tier.NORMAL -> true
        tier == RescueBoot.Tier.QUARANTINED -> true
        retryNormal && tier == RescueBoot.Tier.RESCUE -> true
        else -> false
    }
}
