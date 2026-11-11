package com.dsh.android.browser

import android.view.View
import android.widget.TextView

/** 动作胶囊独占最短展示时长、淡出和呼吸动画；宿主仅提供三个具体控件。 */
internal class BrowserActionIndicator(
    private val actionPill: View,
    private val actionDot: View,
    private val actionText: TextView,
) {
    private var closed = false
    private var showing = false
    private var suppressionCount = 0

    /** 整个比帧事务只隐藏一次；嵌套截图使用引用计数，释放不复活过期提示。 */
    fun suppress(): () -> Unit {
        if (closed) return {}
        suppressionCount++
        if (showing) actionPill.visibility = View.INVISIBLE
        var released = false
        return {
            if (!released) {
                released = true
                suppressionCount--
                if (!closed && suppressionCount == 0 && showing) actionPill.visibility = View.VISIBLE
            }
        }
    }

    fun close() {
        if (closed) return
        hideNow()
        closed = true
        actionPill.animate().setListener(null)
        actionDot.animate().setListener(null)
    }

    /** 胶囊当前这轮文字的起始时刻（`SystemClock.elapsedRealtime()`）。 */
    private var pillShownAt = 0L

    /** 最早可以收走的时刻。新状态进来会重置它。 */
    private var pillDueAt = 0L

    /** 这轮操作是否已经结束（结束后才允许按 5 秒下限收走）。 */
    private var pillOpDone = false

    /** 胶囊最短停留时长（用户要求 5 秒）。 */
    private val PILL_MIN_MS = 5_000L

    /** 到点后淡出的时长。 */
    private val PILL_FADE_MS = 260L

    /**
     * 显示/更新「AI 正在做什么」胶囊。
     *
     * 相同文案刷新本轮最短期限，但不重复播放入场动画。
     */
    fun show(text: String) {
        if (closed) return
        val t = text.trim()
        if (t.isEmpty()) {
            hideNow()
            return
        }
        val pill = actionPill
        val now = android.os.SystemClock.elapsedRealtime()
        if (showing && actionText.text.toString() == t) {
            // 同一句描述：只是新一轮操作，重新给满 5 秒，但不重新播入场动画。
            pillOpDone = false
            pillDueAt = now + PILL_MIN_MS
            return
        }
        showing = true
        pill.removeCallbacks(pillSweep)
        pill.animate().cancel()
        actionText.text = t
        pillOpDone = false
        pillShownAt = now
        pillDueAt = now + PILL_MIN_MS
        if (pill.visibility != View.VISIBLE) {
            pill.alpha = 0f
            pill.visibility = if (suppressionCount == 0) View.VISIBLE else View.INVISIBLE
        }
        // 入场：轻微放大 + 淡入。比纯淡入「灵动」一点，又不至于晃眼。
        pill.scaleX = 0.92f
        pill.scaleY = 0.92f
        pill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        startPulse()
    }

    /**
     * 圆点呼吸：透明度 1 → 0.35 → 1，1.1s 一轮，无限循环。
     *
     * 为什么值得加：胶囊本来是**静止**的一段文字，看起来像贴在页面上的标签。
     * 有了呼吸，它读起来是「正在发生」而不是「一条过期的通知」。animation
     * 走 ViewPropertyAnimator 而不是 ValueAnimator，省掉手动 cancel 的样板。
     */
    private fun startPulse() {
        if (closed || !showing) return
        val dot = actionDot
        dot.animate().cancel()
        dot.alpha = 1f
        dot.animate()
            .alpha(0.35f)
            .setDuration(1_100L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                dot.animate()
                    .alpha(1f)
                    .setDuration(1_100L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction { startPulse() }
                    .start()
            }
            .start()
    }

    /** 停掉呼吸（收胶囊时用，否则动画会在隐藏的 View 上空转）。 */
    private fun stopPulse() {
        actionDot.animate().cancel()
        actionDot.alpha = 1f
    }

    /**
     * 操作结束：**不立刻收**，交给 [pillSweep] 按 5 秒下限处理。
     *
     * 注意这里已经**没有**延迟参数了：收不收由「至少显示 5 秒」这条规则决定，
     * 调用方不需要（也不应该）自己算时长。
     */
    fun finishSoon() {
        if (closed) return
        pillOpDone = true
        scheduleSweep()
    }

    /** 立刻收走胶囊（超时、异常等「操作没正常结束」的路径用）。 */
    fun hideNow() {
        if (closed) return
        val pill = actionPill
        stopPulse()
        pill.removeCallbacks(pillSweep)
        pill.animate().cancel()
        showing = false
        pill.visibility = View.GONE
        pill.alpha = 0f
        pillOpDone = false
        pillDueAt = 0L
    }

    /** 安排一次到点检查：不早于 `pillDueAt`。 */
    private fun scheduleSweep() {
        if (closed) return
        val pill = actionPill
        pill.removeCallbacks(pillSweep)
        if (!pillOpDone) return
        val wait = (pillDueAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        pill.postDelayed(pillSweep, wait)
    }

    /**
     * 到点检查：真的到点了才淡出。
     *
     * ⚠️ 必须**再核一次**时间，不能直接 fade：`postDelayed` 可能因为主线程
     *   忙（页面正在加载）而延后，也可能反过来被提前调度。以时间戳为准。
     */
    private val pillSweep = object : Runnable {
        override fun run() {
            val pill = actionPill
            if (closed || !showing) return
            if (!pillOpDone) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < pillDueAt) {
                pill.postDelayed(this, pillDueAt - now)
                return
            }
            pill.animate().cancel()
            pill.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(PILL_FADE_MS)
                .withEndAction {
                    if (closed || !pillOpDone) return@withEndAction
                    showing = false
                    pill.visibility = View.GONE
                    pill.scaleX = 1f
                    pill.scaleY = 1f
                    stopPulse()
                }
                .start()
        }
    }

}
