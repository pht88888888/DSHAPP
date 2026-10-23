package com.dsh.android.layout

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **LayoutAdapter** —— 布局决策的**唯一执行者**。
 *
 * ## 设计说明 §4.9 的原文
 *
 * > 采用「原生窗口处理 + **单一布局适配器** + 局部界面增强」的三层结构，
 * > 不通过旋转重启 DSH 或重新创建会话。
 *
 * > | 基座配套 LayoutAdapter | **唯一决定实际布局**，发布布局标志，
 * >   进入/退出时执行可撤销的前端适配；不是另一套原生业务界面。 |
 *
 * > **自动模式使用应用窗口宽高比 r**，而不是物理屏幕方向或鼠标类型：
 *
 * > | 条件 | 实际布局 |
 * > |---|---|
 * > | 手动 mobile / official | 锁定指定布局，不随窗口变化切换 |
 * > | auto 且 r > 4/3 | official |
 * > | auto 且 r < 3/4 | mobile |
 * > | auto 且 **3/4 ≤ r ≤ 4/3** | **保留上次实际布局；初次无历史时为 mobile** |
 *
 * ## ⚠️ 为什么必须用「宽高比 + 滞回」而不是「宽度阈值」
 *
 * 现有实现（`client.js` 的 `mpIsNarrow()`）用的是 `window.innerWidth < 1024`
 * 加「非桌面指针」判断。这有三个问题，文档逐条否定了：
 *
 * | 现有做法 | 文档要求 | 后果 |
 * |---|---|---|
 * | 宽度阈值 1024 | **宽高比 r** | 分屏时窗口变窄但仍是宽屏比例，会误判成手机布局 |
 * | 无滞回 | **3/4~4/3 保留上次** | 窗口在阈值附近抖动时布局反复横跳 |
 * | 看指针类型 | **不看鼠标类型** | 平板接鼠标会突然变桌面布局 |
 *
 * **滞回（hysteresis）是关键**：没有它，窗口从 4/3 缩到 3.99/3 再回 4/3，
 * 布局会来回切两次，每次都重建前端适配 —— 用户看到闪烁，草稿可能丢。
 *
 * ## ⚠️ 「不兼容 DOM 时关闭增强并回官方界面」
 *
 * 文档 §4.9 与 G5 任务：
 *
 * > 不兼容 DOM 的局部输入区/顶栏增强**关闭并回官方界面**；清理样式、
 * > 监听、观察器与借用控件。
 *
 * 所以本对象除了「决定布局」，还要能判「这套布局的前端增强能不能安全应用」。
 * 见 [Compatibility]。
 */
object LayoutAdapter {

    private const val TAG = "DSH"

    /** 官方布局。 */
    const val OFFICIAL = "official"

    /** 手机布局。 */
    const val MOBILE = "mobile"

    /** 自动。 */
    const val AUTO = "auto"

    /**
     * 阈值：r > 4/3 ⇒ official。
     *
     * ⚠️ 用整数比较（`w * 3 > h * 4`）而不是浮点除法 —— 避免精度问题
     * 导致边界值判定漂移。文档写的是精确分数，代码也应精确。
     */
    private const val R_OFFICIAL_NUM = 4
    private const val R_OFFICIAL_DEN = 3

    /** 阈值：r < 3/4 ⇒ mobile。 */
    private const val R_MOBILE_NUM = 3
    private const val R_MOBILE_DEN = 4

    /**
     * 布局决策结果。
     *
     * @param layout 实际生效的布局（[MOBILE] 或 [OFFICIAL]，**不会是 [AUTO]**）
     * @param reason 人能看懂的原因（诊断与日志用）
     * @param changed 与上次相比是否变了（变了才需要重建前端适配）
     */
    data class Decision(val layout: String, val reason: String, val changed: Boolean)

    /**
     * 计算实际布局。
     *
     * @param mode 用户设置：`auto` / `mobile` / `official`
     * @param widthPx 应用窗口宽度（**不是物理屏幕宽度**）
     * @param heightPx 应用窗口高度（**不含系统栏**，含 IME 时要小心，见 KDoc 末尾）
     * @param lastLayout 上次的实际布局；`null` = 无历史
     *
     * ⚠️ **`widthPx`/`heightPx` 必须是「实际应用窗口」的尺寸**，
     *   不是 `DisplayMetrics` 的物理尺寸 —— 分屏、浮窗、折叠屏下两者差别很大，
     *   用物理尺寸会把分屏误判成全屏。
     */
    fun decide(
        mode: String,
        widthPx: Int,
        heightPx: Int,
        lastLayout: String?,
    ): Decision {
        // ── 手动模式：锁定，不随窗口变化 ──────────────────────────────
        if (mode == MOBILE || mode == OFFICIAL) {
            val changed = lastLayout != null && lastLayout != mode
            return Decision(mode, "手动锁定为 $mode", changed)
        }

        // ── auto：按宽高比 ────────────────────────────────────────────
        if (widthPx <= 0 || heightPx <= 0) {
            // 尺寸无效时用上次的（或默认 mobile）
            val fallback = lastLayout ?: MOBILE
            return Decision(fallback, "窗口尺寸无效（${widthPx}x$heightPx），沿用 $fallback", false)
        }

        // ⚠️ 整数交叉相乘，避免浮点：
        //   r > 4/3  ⇔  w/h > 4/3  ⇔  w*3 > h*4
        val isWide = widthPx * R_OFFICIAL_DEN > heightPx * R_OFFICIAL_NUM
        //   r < 3/4  ⇔  w/h < 3/4  ⇔  w*4 < h*3
        val isTall = widthPx * R_MOBILE_DEN < heightPx * R_MOBILE_NUM

        return when {
            isWide -> {
                val changed = lastLayout != null && lastLayout != OFFICIAL
                Decision(OFFICIAL, "宽高比 ${ratio(widthPx, heightPx)} > 4/3", changed)
            }
            isTall -> {
                val changed = lastLayout != null && lastLayout != MOBILE
                Decision(MOBILE, "宽高比 ${ratio(widthPx, heightPx)} < 3/4", changed)
            }
            else -> {
                // ── 滞回区间：保留上次 ────────────────────────────────
                //
                // ⚠️ 这是滞回的核心。没有它，窗口在 4/3 附近抖动会让布局
                //   反复横跳，每次都要重建前端适配 —— 用户看到闪烁。
                if (lastLayout == MOBILE || lastLayout == OFFICIAL) {
                    Decision(
                        lastLayout,
                        "宽高比 ${ratio(widthPx, heightPx)} 在 3/4~4/3 之间，保留上次的 $lastLayout",
                        false,
                    )
                } else {
                    // 初次无历史 ⇒ mobile（文档明确）
                    Decision(
                        MOBILE,
                        "宽高比 ${ratio(widthPx, heightPx)} 在 3/4~4/3 之间且无历史，默认 mobile",
                        false,
                    )
                }
            }
        }
    }

    /**
     * 前端增强的兼容性结论。
     *
     * 文档 G5：「不兼容 DOM 的局部输入区/顶栏增强**关闭并回官方界面**；
     * 清理样式、监听、观察器与借用控件」。
     *
     * @param hasInputArea 页面里找得到输入区（`[contenteditable]` / `textarea`）
     * @param hasTopBar 找得到官方顶栏
     * @param layoutApplied 布局适配本身是否已应用
     */
    data class Compatibility(
        val canEnhanceInput: Boolean,
        val canEnhanceTopBar: Boolean,
        val reason: String,
    )

    /**
     * 判断哪些增强可以安全应用。
     *
     * ⚠️ **判据用「元素是否存在」而不是「选择器是否匹配过」**：
     *   官方前端升级后选择器可能变，但「输入区还在不在」这个事实更稳。
     *   用选择器判会导致「官方改了个 class 名，我们就以为输入区没了」。
     *
     * ⚠️ 不兼容时**不是「跳过增强」而是「回官方界面」** —— 两者不同：
     *   前者会留下半套样式（比如改了背景色但没改布局），比完全不管更难看。
     */
    fun checkCompatibility(
        hasInputArea: Boolean,
        hasTopBar: Boolean,
        layoutApplied: Boolean,
    ): Compatibility = when {
        !layoutApplied -> Compatibility(
            canEnhanceInput = false,
            canEnhanceTopBar = false,
            reason = "布局适配未应用 —— 不增强任何东西",
        )
        !hasInputArea -> Compatibility(
            canEnhanceInput = false,
            canEnhanceTopBar = hasTopBar,
            reason = "找不到输入区（官方 DOM 可能已变）—— 关闭输入区增强，回官方界面",
        )
        !hasTopBar -> Compatibility(
            canEnhanceInput = true,
            canEnhanceTopBar = false,
            reason = "找不到官方顶栏 —— 关闭顶栏增强，输入区增强可用",
        )
        else -> Compatibility(
            canEnhanceInput = true,
            canEnhanceTopBar = true,
            reason = "输入区与顶栏都在，增强可应用",
        )
    }

    // ── 持久化 ───────────────────────────────────────────────────────────

    private const val PREFS = "dsh_layout"
    private const val KEY_MODE = "layout_mode"
    private const val KEY_LAST = "last_layout"

    /** 读用户设置的模式（默认 auto）。 */
    fun mode(context: Context): String {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AUTO)
        return if (v == MOBILE || v == OFFICIAL) v else AUTO
    }

    /** 保存用户设置的模式。 */
    fun setMode(context: Context, mode: String) {
        if (mode != AUTO && mode != MOBILE && mode != OFFICIAL) {
            Log.w(TAG, "忽略非法的布局模式: $mode")
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
        EventLog.record("layout.mode", mapOf("mode" to mode))
    }

    /** 读上次实际布局（`null` = 无历史）。 */
    fun lastLayout(context: Context): String? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)
        return if (v == MOBILE || v == OFFICIAL) v else null
    }

    /** 保存实际布局（**只在变化时写**，减少 IO 与日志噪音）。 */
    fun setLastLayout(context: Context, layout: String) {
        if (layout != MOBILE && layout != OFFICIAL) return
        if (lastLayout(context) == layout) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, layout).apply()
        EventLog.record("layout.applied", mapOf("layout" to layout))
    }

    /**
     * 便捷入口：读设置 → 决策 → 保存结果。
     *
     * ⚠️ 尺寸必须由调用方传入**实际应用窗口**尺寸（见 [decide] 的说明）。
     */
    fun apply(context: Context, widthPx: Int, heightPx: Int): Decision {
        val d = decide(mode(context), widthPx, heightPx, lastLayout(context))
        if (d.changed) {
            setLastLayout(context, d.layout)
            Log.i(TAG, "布局切换: ${d.layout}（${d.reason}）")
        }
        return d
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun ratio(w: Int, h: Int): String =
        String.format(java.util.Locale.US, "%.2f", w.toDouble() / h)

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：宽高比决策、精确阈值（整数比较）、滞回、手动锁定、
     *          持久化、兼容性判断
     * ❌ 未做：
     *   - **实际的前端适配执行**（本对象只决策，`client.js` 负责应用）
     *   - 「原生窗口提供稳定的实际应用尺寸与 IME 信息」—— 那需要
     *     `WindowInsets` 集成，属 MainActivity 的工作
     *   - 「清理样式、监听、观察器与借用控件」—— 那是前端侧的清理逻辑
     *   - 与 `client.js` 的接线（当前是独立决策器，前端还没消费它）
     *
     * ⚠️ **IME 的一个坑（必须记下来）**：
     *   键盘弹出时 `visualViewport.height` 会变小，用它算宽高比会让
     *   「手机横屏 + 键盘」被误判成 official。文档明确要求
     *   「**不用键盘缩小后的 visualViewport 误判方向**」。
     *   所以调用方必须传**不含 IME 的**窗口高度。
     */
}
