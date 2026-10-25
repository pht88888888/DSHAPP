package com.dsh.android

import android.content.Context
import android.util.Log

/**
 * 「本 App 现在有没有能力以 **uid 2000** 跑一条 shell 命令」—— 给「键盘顶飞」当闸门。
 *
 * ## 为什么需要这个类
 *
 * 「键盘弹出 ⇒ 球飞上去」的唯一可靠信号是 `dumpsys input_method` 的 `mInputShown`。
 * 而 `dumpsys input_method` **要求 `android.permission.DUMP`**，普通应用 uid 拿不到：
 *
 * ```
 * Permission Denial: can't dump InputMethodManagerService from pid=…, uid=10347
 *   due to missing android.permission.DUMP permission
 * ```
 *
 * 所以这个功能**天生依赖特权通道**（Shizuku 授权，或 adb 起的 uid 2000 服务）。
 * 没有特权时它不可能工作 —— 与其让用户打开开关后什么也不发生、以为功能坏了，
 * 不如在开关上就判定出来、直接不让打开并说明原因。
 *
 * ## 判据
 *
 * 与设置页「特权权限」那一栏**同源**，不另立一套：
 *
 * - Shizuku：`isManagerInstalled` ∧ `isBinderAlive` ∧ `hasPermission`
 *   —— 三者缺一不可（装了没启动、启动了没授权，都跑不了命令）。
 * - root：`RootAccess` 已授权。
 *
 * ⚠️ 本类**只读状态、不触发任何探测**：Shizuku 的 binder 由系统在进程启动时送进来，
 *   root 的结论也是别处异步探好缓存的。所以这里可以随时调、开销接近零，适合在
 *   `BubbleAppearance` 解析和设置页渲染时直接问。
 */
internal object ShellPrivilege {

    private const val TAG = "DSH"

    /** 特权通道的可用性结论。 */
    data class Status(
        /** 有没有一条能用的特权通道（Shizuku 或 root）。 */
        val available: Boolean,
        /** 给用户看的一句话原因；可用时为 null。 */
        val reason: String?,
        /** 走的是哪条通道：`shizuku` / `root` / null。 */
        val via: String?,
    ) {
        companion object {
            val NONE = Status(false, DshApplication.instance.getString(R.string.privilege_not_connected), null)
        }
    }

    /**
     * 当前可用性。**纯读取**，不探测、不阻塞。
     *
     * @param context 任意 context（只用它的包名查 Shizuku 管理器是否安装）。
     */
    fun status(context: Context): Status {
        // ── Shizuku 优先 ────────────────────────────────────────────────
        // 三个条件缺一不可，且**要分开判断**才能给出对的提示：
        // 把「装了但没启动」报成「没装」，用户会去卸载重装（见 PageBridge.shizukuState
        // 的注释，那是踩过的坑）。
        val installed = runCatching { ShizukuChannel.isManagerInstalled(context) }.getOrDefault(false)
        if (installed) {
            val binder = runCatching { ShizukuChannel.isBinderAlive() }.getOrDefault(false)
            if (binder) {
                val granted = runCatching { ShizukuChannel.hasPermission() }.getOrDefault(false)
                if (granted) {
                    return Status(true, null, "shizuku")
                }
                return Status(false, context.getString(R.string.privilege_shizuku_authorize), null)
            }
            return Status(false, context.getString(R.string.privilege_shizuku_start), null)
        }

        // ── root 兜底 ──────────────────────────────────────────────────
        // ⚠️ 用 `cached()` 而不是 `detect()`：detect 会**弹 su 授权框**，而本方法会被
        //   设置页渲染、BubbleAppearance 解析反复调用 —— 在那里弹框是不可接受的。
        //   缓存为空时按「没有 root」处理，与「不探测」的设计一致。
        val root = runCatching { RootAccess.cached(context)?.granted == true }.getOrDefault(false)
        if (root) {
            return Status(true, null, "root")
        }

        return Status(
            false,
            context.getString(R.string.privilege_keyboard_unavailable),
            null,
        )
    }

    /** 便捷判断。 */
    fun isAvailable(context: Context): Boolean = status(context).available

    /**
     * 把「用户想开、但开不了」这件事记一条日志。
     *
     * 单独抽出来是因为设置页与原生两条路径都要记，格式得一致 —— 排查时按这一条
     * 就能确认「是用户没特权，不是功能坏了」。
     */
    fun logDenied(feature: String, s: Status) {
        Log.i(TAG, "「$feature」不可用：${s.reason ?: "未知原因"}")
    }
}
