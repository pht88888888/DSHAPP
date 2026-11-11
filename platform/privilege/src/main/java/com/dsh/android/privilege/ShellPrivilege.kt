package com.dsh.android.privilege
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
 * [status] 只读状态，不触发探测或新授权，适合设置页与配置解析。
 * [exec] 仅供后台键盘探测：root 仅在缓存已授权后沿用 su -c；若授权已撤销或是
 * 一次性授权，su 管理器仍可能要求重新确认，状态读取不会进入这条执行路径。
 */
object ShellPrivilege {

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
            val NONE = Status(false, "未接入特殊权限", null)
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
        val shizukuReason = if (installed) {
            val binder = runCatching { ShizukuChannel.isBinderAlive() }.getOrDefault(false)
            if (binder) {
                if (runCatching { ShizukuChannel.hasPermission() }.getOrDefault(false)) {
                    return Status(true, null, "shizuku")
                }
                "Shizuku 已启动但未授权，去「设置 → 特权权限」点授权"
            } else "Shizuku 装了但服务没启动，去「设置 → 特权权限」启动它"
        } else null

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
            shizukuReason ?: "需要 Shizuku 或 root 才能读取键盘状态（普通应用拿不到 DUMP 权限）",
            null,
        )
    }

    /** 实际探测使用与 status 相同的通道顺序；不得用于设置页状态读取。 */
    fun exec(context: Context, command: String): String? = when (status(context).via) {
        "shizuku" -> ShizukuShell.exec(command)
            ?: if (RootAccess.cached(context)?.granted == true) execRoot(command) else null
        "root" -> execRoot(command)
        else -> null
    }

    /** 沿用 root 工具的 su -c 与同次 uid 校验；只在已有授权缓存时进入。 */
    private fun execRoot(command: String): String? {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/magisk/.core/bin/su").firstOrNull { File(it).exists() } ?: return null
        val process = runCatching {
            ProcessBuilder(su, "-c", "[ \"\$(id -u)\" = 0 ] || exit 78; $command").start()
        }.getOrNull() ?: return null
        val output = ByteArrayOutputStream()
        // 两路均持续排空，stdout 最多保留 1 MB，避免 dumpsys 填满管道。
        val reader = thread(isDaemon = true) {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        val take = minOf(n, 1024 * 1024 - output.size())
                        if (take > 0) output.write(buffer, 0, take)
                    }
                }
            }
        }
        val errors = thread(isDaemon = true) {
            runCatching { process.errorStream.use { input ->
                val buffer = ByteArray(8192)
                while (input.read(buffer) >= 0) { /* 排空 stderr */ }
            } }
        }
        return try {
            if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) return null
            reader.join(1500)
            errors.join(1500)
            if (process.exitValue() == 0 && !reader.isAlive) output.toString("UTF-8") else null
        } catch (_: Exception) {
            null
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
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
