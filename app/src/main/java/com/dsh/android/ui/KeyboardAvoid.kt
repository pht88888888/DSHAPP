package com.dsh.android.ui
import android.content.Context
import com.dsh.android.log.DshLog
import org.json.JSONObject
import com.dsh.android.bubble.BubbleA11yService
import com.dsh.android.bubble.BubbleAppearance
import com.dsh.android.bubble.FloatingBubble
import com.dsh.android.privilege.ShellPrivilege

/**
 * 键盘避让的**开关状态**与**无障碍权限**。
 *
 * 这两件事合成一个小对象，因为它们在设置页里是同一块：
 *
 * | 事实 | 存在哪 | 谁改 |
 * |---|---|---|
 * | 用户要不要这个功能 | SharedPreferences | 设置页开关（[setEnabled]） |
 * | 无障碍授权有没有 | `Settings.Secure` | 系统设置页（[requestPermission] 跳过去） |
 *
 * ## 为什么开关默认是开的
 *
 * 它**没有副作用**：没拿到无障碍权限时整个链路是空转（`BubbleA11yService` 收不到
 * 任何窗口信息，`FloatingBubble` 也不会弹）。默认开 = 用户授权完立刻就有行为，
 * 不需要再找一次开关。关掉它是"我知道这个功能，但我不想要"。
 *
 * ⚠️ 开关状态**不参与** `BubbleAppearance` 的 JSON：那个是 AI 可改的外观配置，
 *   而这个开关决定的是「要不要向系统要权限」，属于用户决策，不该让模型改写。
 */
internal object KeyboardAvoid {

    private const val TAG = "DSH"

    /** 与悬浮球共用一份 SharedPreferences（本来就是同一个功能的配置）。 */
    private const val PREFS = BubbleAppearance.PREFS
    private const val KEY_ENABLED = "keyboard_avoid_enabled"

    /**
     * 键盘状态的**检测来源**。两条路互斥，按质量排序：
     *
     * | 来源 | 怎么拿到 | 延迟 | 要求 |
     * |---|---|---|---|
     * | [A11Y] | 无障碍 `getWindows()` 读 IME 窗口 bounds | 事件驱动 + ≤120ms 兜底 | 用户开一次无障碍 |
     * | [SHELL] | `dumpsys input_method` 的 `mInputShown` | **2s 起**（单次命令就要 95–150ms） | Shizuku / root |
     * | [NONE] | —— | —— | 都没有 ⇒ 功能不可开 |
     *
     * ## 为什么不是"两条都跑"
     *
     * 它们是**同一个事实的两个来源**，同时跑只会互相打架：无障碍看到键盘收起、
     * ADB 那条晚 2 秒还认为弹着，球的落点会在两者之间来回跳。所以严格二选一，
     * 无障碍优先 —— 它快一个数量级，且不需要任何特权。
     *
     * ⚠️ 判据用「服务**已连接**」而不是「用户已授权」：刚授权那几秒系统还没
     *   把服务拉起来，那时按 A11Y 算会让球一动不动地等，不如先落到 SHELL。
     */
    enum class Source {
        /** 无障碍哨兵已连接：事件驱动，最快。 */
        A11Y,

        /** 只剩特权通道：走 `dumpsys` 轮询，**有明显延迟**，但比没有强。 */
        SHELL,

        /** 两条路都没有 ⇒ 开关必须置灰。 */
        NONE,
    }

    /**
     * 当前该用哪条路。
     *
     * @param context 任意 context。
     */
    fun source(context: Context): Source = when {
        BubbleA11yService.isConnected -> Source.A11Y
        ShellPrivilege.isAvailable(context) -> Source.SHELL
        else -> Source.NONE
    }

    /** 这个功能现在能不能用（有任何一条检测路径即可）。 */
    fun isAvailable(context: Context): Boolean = source(context) != Source.NONE

    /**
     * 功能开关（默认开）。
     *
     * ⚠️ 「开关开着」不等于「功能生效」：没有可用来源时它照样是空转，
     *   设置页必须用 [isAvailable] 把开关置灰（见 `stateJson` 的 `source`）。
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /**
     * 改功能开关，并把新状态推给**正在显示的**那个悬浮球。
     *
     * ⚠️ 必须推：悬浮球实例里缓存了一份（每帧都要判，不能每帧读盘）。不推的话
     *   用户关掉开关后球仍会避让，直到下次显示才生效 —— 看起来像开关坏了。
     *
     * ⚠️ 没有可用来源时**拒绝打开**并返回 false：让用户打开一个注定不生效的开关，
     *   比直接说明原因更糟（他会以为功能坏了，而不是"还差一个权限"）。
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !isAvailable(context)) {
            DshLog.w(TAG, "键盘避让打不开：无障碍与特权通道都不可用（${source(context)}）")
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        DshLog.i(TAG, "键盘避让开关: $enabled（来源 ${source(context)}）")
        FloatingBubble.onKeyboardAvoidChanged(enabled)
        return true
    }

    /** 无障碍权限是否已授予（用户是否在系统设置里打开了「DSH 键盘哨兵」）。 */
    fun isPermissionGranted(context: Context): Boolean =
        BubbleA11yService.isEnabled(context)

    /** 跳到系统无障碍设置页，让用户打开「DSH 键盘哨兵」。 */
    fun requestPermission(context: Context) {
        DshLog.i(TAG, "设置页请求无障碍权限，跳转系统设置")
        BubbleA11yService.openSettings(context)
    }

    /**
     * 给设置页读的状态（JSON 字符串）。
     *
     * 四个字段是**四件独立的事实**，必须分开报：
     *
     * - `enabled` —— 用户要不要这个功能（SharedPreferences）；
     * - `a11y`    —— 系统有没有给无障碍授权（`Settings.Secure`）；
     * - `a11yOn`  —— 无障碍服务此刻**是否真的连着**（授权了但进程没起来时为 false）；
     * - `source`  —— 最终走哪条路：`a11y` / `shell` / `none`。
     *
     * 设置页据此决定开关能不能点：`source == "none"` 时置灰并说明原因，
     * `source == "shell"` 时给出「能用但有延迟，建议开无障碍」的提示。
     */
    fun stateJson(context: Context): String = try {
        val src = source(context)
        JSONObject()
            .put("enabled", isEnabled(context))
            .put("a11y", isPermissionGranted(context))
            .put("a11yOn", BubbleA11yService.isConnected)
            .put("shell", ShellPrivilege.isAvailable(context))
            .put("source", when (src) {
                Source.A11Y -> "a11y"
                Source.SHELL -> "shell"
                Source.NONE -> "none"
            })
            .toString()
    } catch (t: Throwable) {
        DshLog.w(TAG, "读取键盘避让状态失败: ${t.message}")
        "{}"
    }
}
