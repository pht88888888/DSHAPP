package com.dsh.android.log

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 环境快照 —— 诊断包里回答「这是台什么机器、什么版本、什么状态」的那一份。
 *
 * ## 为什么必须有
 *
 * 本项目大量故障是**设备相关**的：Android 版本决定硬约束②（`targetSdk ≤ 28`）
 * 能否满足、`filesDir` 能否 `execve`；WebView 内核版本决定前端 bundle 能不能跑；
 * 剩余空间决定解包会不会失败；内存决定会不会被 LMK 杀。
 *
 * 而用户报问题时**永远不会提供这些**（他们只会说「打不开」）。所以必须在诊断包里
 * 自动带上 —— 这是把「来回问三轮」变成「一次到位」的关键。
 *
 * ## 全部字段都允许失败
 *
 * 这个类在**启动极早期**、甚至**崩溃路径**上都可能被调用。任何一项取不到都
 * 记成 `null` 或 `"unknown"`，绝不抛异常 —— 少一个字段的诊断包有用，
 * 生成失败的诊断包没用。
 */
object EnvironmentSnapshot {

    /**
     * 采集快照。
     *
     * @param context 应用上下文；为 null 时只采集不依赖 Context 的部分
     *   （崩溃路径上 `Application` 可能还没 attach 完）。
     */
    fun collect(context: Context?): JSONObject {
        val o = JSONObject()

        // ── App ──
        runCatching {
            o.put(
                "app",
                JSONObject().apply {
                    put("versionName", VersionInfo.versionName)
                    put("versionCode", VersionInfo.versionCode)
                    put("flavor", VersionInfo.flavor)
                    put("applicationId", VersionInfo.applicationId)
                    put("debuggable", isDebuggable())
                },
            )
        }

        // ── 设备 / 系统 ──
        runCatching {
            o.put(
                "device",
                JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("brand", Build.BRAND)
                    put("model", Build.MODEL)
                    put("device", Build.DEVICE)
                    put("hardware", Build.HARDWARE)
                    put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    put("release", Build.VERSION.RELEASE)
                    put("sdkInt", Build.VERSION.SDK_INT)
                    put("previewSdk", Build.VERSION.PREVIEW_SDK_INT)
                    put("securityPatch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "")
                    // ⚠️ 内核版本是硬约束②的判据之一（execve 能否用取决于内核+SELinux）
                    //   读不到时**显式记原因**，别让空串被误读成「采集逻辑漏了这个字段」。
                    val kv = kernelVersion()
                    put("kernel", kv)
                    // ⚠️ 措辞别写死成 "read-denied"：现在是 uname(2) 优先，
                    //   三条路都失败的原因可能是 API 不可用/属性为空/文件被拒，
                    //   "unavailable" 才与「我实际掌握的信息」相符。
                    if (kv.isEmpty()) put("kernelUnavailable", "unavailable")
                    put("locale", java.util.Locale.getDefault().toString())
                    put("timeZone", java.util.TimeZone.getDefault().id)
                },
            )
        }

        // ── 屏幕（本项目大量 UI 修复与尺寸/DPI 有关）──
        //
        // ⚠️ `@Suppress("DEPRECATION")` 必须罩住**整个块**，不能只罩 `getMetrics`：
        //   `DisplayMetrics.scaledDensity`（下面 L100 附近）在 API 34 起同样被废弃，
        //   原来只标了前面那一行，于是构建一直带一条
        //   `'field scaledDensity: Float' is deprecated` 警告（增量编译没重编本文件时
        //   看不到，全量构建才露出来）—— 「零警告」这个验收信号因此是假的。
        @Suppress("DEPRECATION")
        runCatching {
            val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val dm = DisplayMetrics()
            wm?.defaultDisplay?.getMetrics(dm)
            o.put(
                "display",
                JSONObject().apply {
                    put("widthPx", dm.widthPixels)
                    put("heightPx", dm.heightPixels)
                    put("densityDpi", dm.densityDpi)
                    put("density", dm.density.toDouble())
                    put("scaledDensity", dm.scaledDensity.toDouble())
                },
            )
        }

        // ── 内存（LMK 杀进程的判据）──
        runCatching {
            val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            o.put(
                "memory",
                JSONObject().apply {
                    put("totalBytes", mi.totalMem)
                    put("availBytes", mi.availMem)
                    put("thresholdBytes", mi.threshold)
                    put("lowMemory", mi.lowMemory)
                    put("appMaxHeapBytes", am?.memoryClass?.toLong()?.times(1024L * 1024L) ?: 0L)
                },
            )
        }

        // ── 磁盘（解包失败的常见原因）──
        runCatching {
            val files = context?.filesDir
            o.put(
                "storage",
                JSONObject().apply {
                    if (files != null) {
                        val fs = StatFs(files.absolutePath)
                        put("dataFreeBytes", fs.availableBytes)
                        put("dataTotalBytes", fs.totalBytes)
                    }
                    runCatching {
                        val ext = Environment.getExternalStorageDirectory()
                        val fs = StatFs(ext.absolutePath)
                        put("externalFreeBytes", fs.availableBytes)
                        put("externalTotalBytes", fs.totalBytes)
                    }
                },
            )
        }

        // ── 权限（决定哪些功能会「静默失效」）──
        runCatching {
            if (context != null) {
                o.put(
                    "permissions",
                    JSONObject().apply {
                        put("notifications", hasPermission(context, "android.permission.POST_NOTIFICATIONS"))
                        put("storage", hasPermission(context, "android.permission.WRITE_EXTERNAL_STORAGE"))
                        put("microphone", hasPermission(context, "android.permission.RECORD_AUDIO"))
                        // 悬浮窗是特殊权限，必须查系统设置
                        put("overlay", hasOverlay(context))
                        put("batteryExempt", isBatteryExempt(context))
                    },
                )
            }
        }

        // ── 运行环境（引擎那一侧）──
        runCatching {
            o.put(
                "runtime",
                JSONObject().apply {
                    put("bootId", BootState.bootId)
                    put("uptimeMs", BootState.uptimeMs())
                    // ⚠️ `logFileLogging` 是 `dir != null && appWriter != null`，把两种
                    //   失效形态压成了一个布尔。单独看 false **推不出**「这个包里没有
                    //   磁盘日志」：目录还在、只是 app.log 句柄被 noteFailure 关掉时，
                    //   engine.log / web.log / events.jsonl 照旧在写。
                    //   所以后两个字段必须一并给出，读的人（或 AI）才能分辨。
                    //   详见 LogStore.logDirAvailable / appLogEnabled 的 KDoc。
                    put("logFileLogging", LogStore.fileLoggingEnabled)
                    put("logDirAvailable", LogStore.logDirAvailable)
                    put("logAppLogEnabled", LogStore.appLogEnabled)
                    put("logThrottled", LogStore.throttledTotal)
                    put("logDroppedAsync", LogStore.droppedAsync)
                    put("javaVersion", System.getProperty("java.vm.version") ?: "")
                    put("availableProcessors", Runtime.getRuntime().availableProcessors())
                    put("maxMemoryBytes", Runtime.getRuntime().maxMemory())
                },
            )
        }

        return o
    }

    /**
     * 采集「关键文件状态」—— 回答「资产到底解包了没有」。
     *
     * ## 为什么这一项特别重要
     *
     * 本项目最常见的故障是**资产没解包/解包不全**（版本戳不一致 → 整体跳过、
     * 磁盘满 → 解包到一半、被用户清数据 → 全没了）。这些在日志里表现为
     * 「某功能静默不工作」，而看日志的人很难判断「到底哪个文件该在而没在」。
     *
     * 所以这里主动把**该存在的文件**列出来，逐个报「存在/大小/时间」——
     * 一眼就能看出缺哪个。文件清单由调用方传入（`EngineManager` 知道该有哪些），
     * 本类不猜。
     */
    fun collectFileStates(files: List<File>): JSONObject {
        val arr = JSONArray()
        for (f in files) {
            runCatching {
                arr.put(
                    JSONObject().apply {
                        // ⚠️ 路径里含用户目录名，但都是 app 私有目录下的相对结构，
                        //    不含用户名/账号信息，可以进包（见 Redactor 的说明）。
                        put("path", f.absolutePath)
                        put("exists", f.exists())
                        put("isDirectory", f.isDirectory)
                        put("sizeBytes", if (f.exists()) f.length() else 0L)
                        put("lastModified", if (f.exists()) f.lastModified() else 0L)
                    },
                )
            }
        }
        return JSONObject().apply { put("files", arr) }
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────

    private fun isDebuggable(): Boolean = runCatching {
        (LogContext.get()?.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0
    }.getOrDefault(false)

    /**
     * 内核版本。
     *
     * ## 为什么不能只读 `/proc/version`（真机实测，2026-10-14）
     *
     * 原先只做 `File("/proc/version").readText()`，`runCatching` 一兜底就返回 `""`。
     * 而 **Android 10（API 29）起 SELinux 把 `untrusted_app` 读 `/proc/version`
     * 也拒了** —— 于是这个字段在 53 个诊断包里 **41 个是空串**，而它恰恰是硬约束②
     * （`execve` 能否用取决于内核 + SELinux）排查时要看的第一行。
     *
     * 真机证据（CDY-TN90 / Android 10 / `Enforcing`）。**最硬的一条是内核自己的
     * avc 记录**，它直接落在 `untrusted_app` 域上：
     * ```
     * avc: denied { read } for pid=25826 comm="sofire_pool_thr" name="version"
     *   dev="proc" ino=4026532165 scontext=u:r:untrusted_app:s0:c84,c256,c512,c768
     *   tcontext=u:object_r:proc_version:s0 tclass=file permissive=0
     * ```
     * 辅助对照（⚠️ `run-as` 跑的是 `u:r:runas_app`，**不是** `untrusted_app`，
     * 所以它只能当旁证，不能当同一域的证明 —— 上面那条 avc 才是）：
     * ```
     * $ run-as com.dsh.android cat /proc/version
     * cat: /proc/version: Permission denied
     * $ adb shell cat /proc/version                   ← shell(uid 2000) 能读
     * Linux version 4.14.116 (HarmonyOS@localhost) …
     * ```
     * 同批实测：`/proc/sys/kernel/osrelease`、`/proc/sys/kernel/version`、
     * `/system/build.prop` 也全部 DENIED，而 `/proc/cpuinfo`、`/proc/self/status` 可读。
     * 关键对照：**同一进程内**读 `/proc/version` 被拒，而 `System.getProperty("os.version")`
     * 拿到 `4.14.116` —— 后者走 `uname(2)`，不走文件系统。
     *
     * ⚠️ **失败是设备/版本相关的，不是「老设备才这样」**：41 个空串里
     * SDK 35 的 20 个**全部**为空、SDK 28/31 的反而有值。所以判据只能是「读得到就读」。
     *
     * ## 取值顺序（**先走 JDK/API，最后才读文件**）
     *
     * 1. `android.system.Os.uname()`（API 21+）—— 直接是 `uname(2)` **系统调用**，
     *    不碰文件系统，SELinux 管不到；`release` + `version` + `machine` 拼起来
     *    与 `uname -a` 等价，且是**结构化**的（不用解析字符串）。
     * 2. `System.getProperty("os.version")` —— 同样是 `uname(2)`：AOSP libcore 的
     *    `System.initUnchangeableSystemProperties()` 里就是
     *    `p.put("os.version", Libcore.os.uname().release)`，进程启动时填好，零成本。
     * 3. `/proc/version` —— 最完整（含编译器与构建时间），但**在 Android 10+ 上
     *    大概率读不到**（见上），所以只当兜底。
     *
     * ⚠️ **不要再用 `ProcessBuilder("/system/bin/uname")`。** 曾经用过，两个真实缺陷：
     *    ① `redirectErrorStream(true)` 会把 stderr 并进结果 —— toybox 的 uname
     *       在参数出错时把错误写 stderr，那串会被当成内核版本写进诊断包；
     *    ② 先 `readText()` 再 `waitFor(3, SECONDS)` 是**死代码** —— `readText()`
     *       要等管道关闭（子进程已退出）才返回，所以执行到 `waitFor` 时它必然
     *       已 true，`destroyForcibly()` 永不执行，「3 秒超时」形同虚设，
     *       实际是无界阻塞。而这段代码跑在**崩溃补打包**路径上，阻塞的代价不划算。
     *    `uname(2)` 这两条都天然不存在：无子进程、无流、无超时需求。
     *
     * 三条都失败才返回 `""`，此时**额外记一个 `kernelUnavailable` 字段**说明原因 ——
     * 静默的空串让人以为是采集逻辑漏了，而真因是权限/平台，必须能区分。
     */
    private fun kernelVersion(): String {
        // 1) uname(2) 结构化取值：release + version + machine
        runCatching {
            val u = android.system.Os.uname()
            val parts = listOf(u.release, u.version, u.machine)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            if (parts.isNotEmpty()) return parts.take(200)
        }

        // 2) 同一条 uname(2)，但由 libcore 在进程启动时填好，零成本
        val prop = runCatching { System.getProperty("os.version") }.getOrNull()?.trim()
        if (!prop.isNullOrEmpty()) return prop.take(200)

        // 3) 兜底：/proc/version（Android 10+ 多为 Permission denied，故排在最后）
        val direct = runCatching { File("/proc/version").readText().trim() }.getOrNull()
        if (!direct.isNullOrEmpty()) return direct.take(200)

        return ""
    }

    private fun hasPermission(context: Context, perm: String): Boolean = runCatching {
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun hasOverlay(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.provider.Settings.canDrawOverlays(context) else true
    }.getOrDefault(false)

    private fun isBatteryExempt(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}
