package com.dsh.android.ui

import android.os.Handler

/**
 * 「页面 load 之后多久还没拿到健康证据」的看门狗 —— 卡死检测的**计时那一半**。
 *
 * 判据本身在 [WebClientHealth]（纯逻辑、可 JVM 测试），这里只管三件事：
 * 什么时候起算、什么时候撤销、到期怎么喊人。
 *
 * ## 为什么要单独一个类，而不是几个字段塞进 Activity
 *
 * ① **Activity 有生产源码体积基线**（见 `tools/check-architecture.sh` 第 2 段），
 *    而这段逻辑带动了约 80 行论证注释 —— 塞进去会把 `MainActivity.kt` 顶过
 *    历史基线。架构守卫明文禁止「为了让检查通过而抬高基线」，所以挪出来。
 * ② 它自己的状态机（在飞 / 到期时刻 / 本轮是否已健康）跟页面、View、引擎
 *    都没有耦合，独立出来才能被单独读、单独改。
 *
 * ## 判据是怎么来的（真机事故，2026-11-12，CDY-TN90）
 *
 * 旧实现靠**抓某一段文案**（官方启动页的 `HARNESS`）判断卡死。两个方向的错都犯过：
 *
 * · **假阳性**：`15:13:55` 那次，`onUiReady` 已在 `.750` 成功上报，`.870` 那条
 *   console 行仍带着 `首帧正文="HARNESS …"`（那是 `polls=1` 的快照，不是当前状态），
 *   于是 **120 毫秒内**把一次本来健康的启动降级到救援模式。
 * · **假阴性**：用户换掉启动动画 / 主题 / 加载文案之后，那个词永远不出现 ——
 *   真正的卡死反而**永远检测不到**。文案属于前端，不属于我们，拿它当契约就是错的。
 *
 * 现在只看**结果**：从 `UiStage.PageLoaded` 起算 [delayMs] 内一条健康证据都没来
 * ⇒ 判 client 半坏。插件真坏掉时它自然一句话都说不出，于是必然走到这里。
 *
 * ## 为什么必须能被撤销
 *
 * 慢设备冷启动真机实测 `PageLoaded → onUiReady` 最长 6.1 秒（清缓存首启），而
 * **任何**固定窗口都会在更慢的设备上被突破。所以判据不是「窗口内没看到就算坏」，
 * 而是「窗口内没看到**而且**到现在还没看到」—— 健康证据一到就 [disarm]。
 * 误判的代价于是只剩「晚 30 秒才降级」，而不是「设备慢就降级」。
 *
 * @param handler 用来投递计时任务的 Handler（调用方给主线程那个）。
 * @param isNormalTier 是否处于 NORMAL 层级。救援 profile 不加载移动端插件，
 *   健康证据永远不会来，武装了只会每 30 秒刷一次无用日志。
 * @param onExpired 到期时喊人（参数是本次窗口的毫秒数与原因）。
 */
class WebClientWatchdog(
    private val handler: Handler,
    private val isNormalTier: () -> Boolean,
    private val logI: (String) -> Unit,
    private val logE: (String) -> Unit,
    private val onExpired: (delayMs: Long, why: String) -> Unit,
) {

    private var task: Runnable? = null
    private var pending = false
    private var deadlineAt = 0L
    private var healthy = false

    /** 本轮是否已经拿到过健康证据（拿到之后本轮不再降级）。 */
    val isHealthy: Boolean get() = healthy

    /**
     * 武装（或**提前**已有的）待判窗口。
     *
     * ⚠️ 规则是「**允许提前，不允许推迟**」：
     * 弱信号（插件自报超时兜底 / 结构锚点为 0）应该让降级更早发生，所以新期限更早时
     * 要重排；而重复的弱信号**不能**把期限一路往后推 —— 否则前端反复自报就能无限
     * 推迟降级，等于这条判据不存在。
     */
    fun arm(delayMs: Long, why: String) {
        if (!isNormalTier()) return
        if (healthy) return
        val now = System.currentTimeMillis()
        if (pending) {
            if (deadlineAt > 0 && now + delayMs >= deadlineAt) return
            task?.let { handler.removeCallbacks(it) }
            task = null
        }
        pending = true
        deadlineAt = now + delayMs
        val runnable = Runnable {
            pending = false
            deadlineAt = 0L
            if (healthy) return@Runnable
            logE("[web-rescue] 页面 load 后 ${delayMs}ms 内没有任何健康证据（$why）—— 判 client 半坏")
            onExpired(delayMs, why)
        }
        task = runnable
        handler.postDelayed(runnable, delayMs)
        logI("[web-rescue] 待判窗口已开启 ${delayMs}ms（$why）")
    }

    /** 健康证据到了 ⇒ 撤销待判。真实 UI 出现之后，本轮任何一次都不该再降级。 */
    fun disarm(why: String) {
        healthy = true
        val current = task ?: return
        handler.removeCallbacks(current)
        task = null
        if (pending) logI("[web-rescue] 待判已撤销（$why）")
        pending = false
        deadlineAt = 0L
    }

    /**
     * 新一轮页面加载：健康标记与待判都要归零。
     *
     * ⚠️ 健康标记**必须**一起清 —— 否则上一轮的正常启动会掩盖这一轮的坏，
     * 变成「只要成功启动过一次，以后永远不降级」。
     */
    fun reset() {
        task?.let { handler.removeCallbacks(it) }
        task = null
        pending = false
        deadlineAt = 0L
        healthy = false
    }
}
