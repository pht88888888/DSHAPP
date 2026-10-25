package com.dsh.android.log

import android.content.Context
import android.os.Build
import android.util.Log as AndroidLog
import com.dsh.android.BuildConfig
import com.dsh.android.R
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动 / 干净退出标记 —— Android 10 上判定「上次为什么没了」的**唯一**办法。
 *
 * ## 为什么不能靠系统
 *
 * API 30 才有 `ApplicationExitInfo`（系统给的退出原因 + 堆栈）。本工程
 * `targetSdk = 28`（硬约束②，见 AGENTS.md），`minSdk = 26` —— 真机跑在
 * Android 10 上时**拿不到**这个 API。所以只能自己记。
 *
 * ## 判据
 *
 * 启动时写 `clean = false`；正常退出时写 `clean = true`。
 * 下次启动若读到 `clean == false`，就说明上次**没走到正常退出** ——
 * 崩溃、被 LMK 回收、被国产 ROM 冻结、断电、用户强停，都会留下这个标记。
 *
 * ## ⚠️ 诚实分级：这不是「崩溃」的同义词
 *
 * 「非正常结束」**不等于**崩溃。它可能是：
 *
 * - 真崩溃（那种情况 [CrashRecorder] 会另有一份带堆栈的文件）
 * - 低内存被系统回收（正常行为，尤其 App 在后台时）
 * - 国产 ROM 的电池优化把进程冻结/杀掉
 * - 用户从最近任务划掉（这条 `onTaskRemoved` 能补上，但国产 ROM 常不给回调）
 * - 断电 / 用户强停
 *
 * 所以诊断包里**如实写成「上次非正常结束（无堆栈）」**，绝不谎称崩溃。
 * 分析者据此知道「有异常，但没有堆栈」，而不是「这里有个崩溃我找不到堆栈」。
 *
 * ## 连续计数为什么要单独记
 *
 * 一次非正常结束是噪声（后台被回收很正常）。**连续**多次才说明是启动即崩的
 * 死循环 —— 那正是最需要立刻告诉用户的场景（App 打不开，用户以为彻底坏了）。
 *
 * ## ⚠️ 但「非正常结束」的次数**不是**「启动即崩」的次数（踩过，很严重）
 *
 * 计数原先每遇到一次 `clean == false` 就 +1，于是把两类**完全不同**的情况
 * 混成了一串数字：
 *
 * | 实际情况 | 特征 | 该不该算「启动即崩」 |
 * |---|---|---|
 * | 启动即崩的死循环 | 本次**从未**加载出界面 | ✅ 该算 |
 * | 正常用了一阵，返回键退到后台 / 被 LMK 回收 / 断电 | 本次**成功**加载过界面 | ❌ 不该算 |
 *
 * 而第二类恰恰是**绝大多数**：`MainActivity` 用返回键退到后台走的是
 * `moveTaskToBack(true)`，**不 finish Activity** ⇒ 永远走不到 `onDestroy` 的
 * `markCleanExit` ⇒ 每次正常离开都被记成一次「非正常结束」。
 *
 * 真机实测（53 个诊断包）：计数一路涨到 **18**，`last-exit` 因此判 fail
 * 「疑似启动即崩的死循环」；而同一份 `app.log` 里，10 个会话段有 **8 个**
 * 明明「引擎就绪 + 界面加载完成」，`crashes/` 也只有 5 份 `main-thread-stall`。
 * 也就是说**这条结论是错的**，而它正是用户/分析者最先看到的那一行。
 *
 * 所以判据改成：**只有「上次启动从头到尾没能加载出界面」才累加计数**。
 * 加载成功过 ⇒ 计数归零（`clean=false` 这个事实本身仍由 `last-exit` 的
 * warn 分支独立如实上报，不受影响）。
 */
object BootState {

    private const val FILE_NAME = "boot-state.json"

    /** 连续非正常结束多少次后，界面应当明确提示用户。 */
    const val UNSTABLE_THRESHOLD = 3

    private val fmt = SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var state: State = State()

    /**
     * 本次启动的 id。
     *
     * ⚠️ 它是把「崩溃现场」与「那一次启动的日志」对上的唯一线索：一次会话里
     * 崩溃文件带 `bootId`，诊断包的 `manifest.json` 也带，分析者才能确定
     * 「这份崩溃属于这次启动」而不是上次残留的。
     */
    @Volatile
    var bootId: String = ""
        private set

    /**
     * 读取上次状态并开始记录本次启动。
     *
     * ⚠️ 必须在 `attachBaseContext()` 里、[LogStore.init] 之后**立刻**调用 ——
     * 晚于任何一次崩溃就没意义了。
     *
     * @return 上一次的结束情况，供界面/诊断包使用。
     */
    fun begin(logDir: File): Previous {
        file = File(logDir, FILE_NAME)
        val prev = read()
        val now = System.currentTimeMillis()
        bootId = "b-$now-" + Integer.toHexString((now xor (now ushr 32)).toInt() and 0xffff)

        // 判定上一次
        val unclean = prev != null && !prev.clean
        // ⚠️ **只有「上次压根没加载出界面」才累加**（见类注释里的踩坑说明）。
        //   上次加载成功过 ⇒ 那是「用了一阵才离开」，不是「启动即崩」⇒ 归零。
        //   老状态文件里没有 `uiLoaded` 字段（默认 false）⇒ 一律按「没加载过」
        //   处理，与旧行为一致，不会把已有的计数洗白。
        val consecutive = when {
            !unclean -> 0
            prev!!.uiLoaded -> 0
            else -> prev.consecutiveUncleanCount + 1
        }

        state = State(
            bootId = bootId,
            startedAt = now,
            clean = false,
            consecutiveUncleanCount = consecutive,
            lastExit = prev?.let {
                LastExit(
                    // ⚠️ **这个字段的语义取决于 `clean`，必须按它分流取。**
                    //
                    //   `prev` 是**上次**的 State：
                    //   - `prev.clean == true` ⇒ 上次真的调过 [markCleanExit]，它把
                    //     `prev.lastExit.at` 覆盖成了**上次退出时刻** ⇒ 取它。
                    //   - `prev.clean == false` ⇒ 上次**没**调过 [markCleanExit]，
                    //     `prev.lastExit` 还是上次 `begin()` 写的那个值（即**上上次**
                    //     的东西，与上次无关）⇒ 只能用 `prev.startedAt`（上次启动时刻）。
                    //
                    //   原来这里无条件写 `at = it.startedAt`，于是正常退出的那一刻被
                    //   整个丢掉 —— 下次 `previous()` 读回 `it.at` 拿到的是**上次启动**
                    //   时刻，而 `describe()` 的 clean 分支把它当退出时刻报出来。
                    //   分析者按这个时间去日志里对齐，对齐到的是启动那一行。
                    at = if (it.clean) (it.lastExit?.at ?: it.startedAt) else it.startedAt,
                    clean = it.clean,
                    bootId = it.bootId,
                    reason = if (it.clean) "clean" else "unclean",
                )
            },
            versionName = VersionInfo.versionName,
            versionCode = VersionInfo.versionCode,
            flavor = VersionInfo.flavor,
        )
        write()

        val previous = Previous(
            known = prev != null,
            clean = prev?.clean ?: true,
            consecutiveUncleanCount = consecutive,
            bootId = prev?.bootId,
            startedAt = prev?.startedAt ?: 0L,
        )

        if (unclean) {
            // 记一条 Warn：这是「上次为什么没了」在日志里的落点。
            //
            // ⚠️ **两种情形必须分开说，不能共用「连续第 N 次」。** `unclean` 看的是
            //   `prev.clean`，而 `consecutive` 在 `prev.uiLoaded` 为真时归零 ——
            //   两者是不同的维度。合在一句里就会打出「上次启动未正常结束…连续第 0 次」
            //   这种自相矛盾的话，而它恰恰是排查者最先看到的一行。
            val detail = if (consecutive == 0) {
                // 上次界面加载成功过 ⇒ 是「用了一阵才离开」，不是「启动即崩」。
                "界面曾加载成功，不计入连续计数"
            } else {
                "连续第 $consecutive 次" +
                    if (consecutive >= UNSTABLE_THRESHOLD) " —— 已达不稳定阈值，界面应提示用户" else ""
            }
            LogStore.app('W', "Boot", "上次启动未正常结束（bootId=${prev!!.bootId}，$detail）")
            EventLog.record("boot.previous-unclean", mapOf("count" to consecutive.toString()))
        } else {
            EventLog.record("boot.start", mapOf("clean" to "true"))
        }
        return previous
    }

    /**
     * 标记为干净退出。
     *
     * ⚠️ **只在真的能确认时调用**：`MainActivity.onDestroy()` 且 `isFinishing`
     * （用户主动退出），以及 `onTaskRemoved`（从最近任务划掉）。**不要**在
     * `onStop()` 里调 —— 那只是切到后台，进程还活着，下一次进前台不该被当成
     * 「上次结束了」。
     *
     * ⚠️ 国产 ROM 经常不给 `onTaskRemoved` 回调（直接杀进程），所以「用户划掉」
     * 这条路径**不能保证**记上 —— 那种情况会退化成「非正常结束」。这是平台
     * 限制，如实记录即可，不要试图猜。
     */
    fun markCleanExit(reason: String) {
        if (state.clean) return
        state = state.copy(
            clean = true,
            consecutiveUncleanCount = 0,
            lastExit = LastExit(
                at = System.currentTimeMillis(),
                clean = true,
                bootId = bootId,
                reason = reason,
            ),
        )
        write()
    }

    /**
     * 记下「本次启动成功加载出过界面」。
     *
     * ⚠️ **必须在每次启动里都调用**（[begin] 会重置它）。它是 [State.uiLoaded]
     * 的唯一写入点，用途只有一个：让下一次启动能分清
     *
     * - 「上次启动从头到尾没出界面」= 启动即崩的死循环 ⇒ 计数 +1
     * - 「上次出过界面，之后才被回收/断电/退到后台」⇒ 计数归零
     *
     * 没有它的时候，返回键退到后台（走 `moveTaskToBack`，**不 finish**，
     * 永远到不了 `markCleanExit`）会被反复记成「非正常结束」，真机实测累积到
     * 18 次，于是诊断包给出「疑似启动即崩的死循环」这个**错误结论**。
     *
     * ⚠️ 判据取「Web 界面加载完成」而不是「引擎就绪」：引擎起来了但页面白屏
     * 同样是用户眼里的「打不开」，那种情况不该被算成「成功启动」。
     *
     * ⚠️ 只在**第一次**落盘（`uiLoaded` 已是 true 就直接返回）：这个方法会在
     * 每次页面加载完成时被调用（含内核被回收后的重载），没必要反复写文件。
     */
    fun markUiLoaded() {
        if (state.uiLoaded) return
        state = state.copy(uiLoaded = true)
        write()
    }

    /** 当前状态快照（诊断包用）。 */
    fun current(): State = state

    /** 上一次启动的情况（界面提示用）。 */
    fun previous(): Previous = state.lastExit?.let {
        Previous(
            known = true,
            clean = it.clean,
            consecutiveUncleanCount = state.consecutiveUncleanCount,
            bootId = it.bootId,
            startedAt = it.at,
        )
    } ?: Previous(known = false, clean = true, consecutiveUncleanCount = 0, bootId = null, startedAt = 0L)

    /** 启动至今的毫秒数（事件时间线用）。 */
    fun uptimeMs(): Long = System.currentTimeMillis() - state.startedAt

    // ── 落盘 ──────────────────────────────────────────────────────────────────

    private fun read(): State? {
        val f = file ?: return null
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            State(
                bootId = o.optString("bootId", ""),
                startedAt = o.optLong("startedAt", 0L),
                clean = o.optBoolean("clean", true),
                consecutiveUncleanCount = o.optInt("consecutiveUncleanCount", 0),
                lastExit = o.optJSONObject("lastExit")?.let { e ->
                    LastExit(
                        at = e.optLong("at", 0L),
                        clean = e.optBoolean("clean", true),
                        bootId = e.optString("bootId", ""),
                        reason = e.optString("reason", ""),
                    )
                },
                versionName = o.optString("versionName", ""),
                versionCode = o.optInt("versionCode", 0),
                flavor = o.optString("flavor", ""),
                uiLoaded = o.optBoolean("uiLoaded", false),
            )
        } catch (t: Throwable) {
            // 坏文件不是错误：当成「没有历史」。不删它 —— 万一要人工看。
            AndroidLog.w(TAG, "读取 boot-state.json 失败（当作无历史）: ${t.message}")
            null
        }
    }

    /**
     * 原子写。
     *
     * ⚠️ **必须原子**：这个文件写的时刻恰恰是「马上要崩溃/被杀了」的时刻，
     * 半个 JSON 会让下次启动读不出来，于是「上次非正常结束」这条最关键的
     * 线索就丢了。先写临时文件再 rename。
     */
    private fun write() {
        val f = file ?: return
        try {
            val o = JSONObject().apply {
                put("bootId", state.bootId)
                put("startedAt", state.startedAt)
                put("clean", state.clean)
                put("consecutiveUncleanCount", state.consecutiveUncleanCount)
                put("versionName", state.versionName)
                put("versionCode", state.versionCode)
                put("flavor", state.flavor)
                put("uiLoaded", state.uiLoaded)
                state.lastExit?.let { e ->
                    put(
                        "lastExit",
                        JSONObject().apply {
                            put("at", e.at)
                            put("clean", e.clean)
                            put("bootId", e.bootId)
                            put("reason", e.reason)
                        },
                    )
                }
                put("updatedAt", System.currentTimeMillis())
            }
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            AndroidLog.w(TAG, "写入 boot-state.json 失败: ${t.message}")
        }
    }

    // ── 数据结构 ──────────────────────────────────────────────────────────────

    data class State(
        val bootId: String = "",
        val startedAt: Long = 0L,
        val clean: Boolean = true,
        val consecutiveUncleanCount: Int = 0,
        val lastExit: LastExit? = null,
        val versionName: String = "",
        val versionCode: Int = 0,
        val flavor: String = "",
        /**
         * 本次启动**是否成功加载出过界面**。
         *
         * ⚠️ 它是区分「启动即崩」与「用了一阵才离开」的唯一依据 —— 见类注释。
         * 老状态文件里没有这个字段 ⇒ 读回 false ⇒ 按旧行为处理。
         */
        val uiLoaded: Boolean = false,
    )

    data class LastExit(
        val at: Long,
        val clean: Boolean,
        val bootId: String,
        val reason: String,
    )

    /** 上一次启动的情况，给界面用。 */
    data class Previous(
        val known: Boolean,
        val clean: Boolean,
        val consecutiveUncleanCount: Int,
        val bootId: String?,
        /**
         * ⚠️ **这个字段有两种语义，取决于 [clean]**，不是「上次结束的时刻」：
         *
         * - `clean == true`（走 [markCleanExit]）⇒ **退出时刻**
         * - `clean == false`（走 [begin]，从上次的 `startedAt` 抄来）⇒ **上次启动的时刻**
         *
         * 非正常结束时**根本不知道进程是什么时候没的**（被杀/断电没有回调），
         * 所以只能退而给出「它是什么时候起来的」。把两者混为一谈会让分析者拿
         * 一个启动时刻去日志里找崩溃，找到的是启动那一行 —— 见 [describe]。
         */
        val startedAt: Long,
    ) {
        /** 上次非正常结束。⚠️ **不等于崩溃**，见类注释的诚实分级。 */
        val unclean: Boolean get() = known && !clean

        /** 连续非正常结束已达阈值 —— 界面该明确提示。 */
        val unstable: Boolean get() = unclean && consecutiveUncleanCount >= UNSTABLE_THRESHOLD

        /** Localized UI summary; describe() retains the diagnostic wording. */
        fun describe(context: Context): String = when {
            !known -> context.getString(R.string.boot_history_first)
            clean -> context.getString(R.string.boot_history_clean, fmt.format(Date(startedAt)))
            unstable -> context.getString(R.string.boot_history_unstable, consecutiveUncleanCount)
            else -> context.getString(R.string.boot_history_unclean, fmt.format(Date(startedAt)))
        }

        /** 给界面的一句话（中文）。 */
        fun describe(): String = when {
            !known -> "首次启动，无历史记录"
            // 这一支的 startedAt 是**退出时刻**（markCleanExit 写的），说「退出」是对的。
            clean -> "上次正常退出（${fmt.format(Date(startedAt))}）"
            unstable -> "连续 $consecutiveUncleanCount 次未正常结束，建议查看日志"
            // ⚠️ **必须说成「上次启动于」**：这一支的 startedAt 是上次**启动**的时刻。
            //   原来写「上次未正常结束（<时刻>，无堆栈）」，读者会把它当成故障发生
            //   的时刻，去日志里按这个时间去对齐 —— 而它其实是启动那一行的时间。
            //   真机实测：lastExit.at = 04:04:44.898（上次启动），而本次启动是
            //   04:07:01.997，真正的消失发生在两者之间，日志里根本没有那个时刻。
            else -> "上次未正常结束（上次启动于 ${fmt.format(Date(startedAt))}，无堆栈）"
        }
    }

    private const val TAG = "DSH/BootState"
}

/**
 * 版本信息的**唯一读取点**。
 *
 * ## 为什么要有这一层
 *
 * `BuildConfig` 是**构建期生成**的，`flavor` 字段两个 flavor 不一样
 * （`system` / `gecko`）。日志系统有四五处要写「App 版本」，各写各的迟早会
 * 不一致 —— 而诊断包里「哪个版本出的问题」必须唯一可信。
 *
 * ⚠️ 全部用 `runCatching` 兜住：诊断包在**启动极早期**就要能生成（崩溃路径），
 * 那时 `BuildConfig` 一定可用，但 `PackageManager` 未必 —— 拿不到就退回
 * 空串，绝不因为「查版本号失败」而让整个诊断包生成失败。
 */
object VersionInfo {

    val versionName: String = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("")

    val versionCode: Int = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(0)

    /** 两个 flavor 之一：`system`（系统 WebView）/ `gecko`（内置内核）。 */
    val flavor: String = runCatching {
        if (BuildConfig.BUILT_IN_WEB_ENGINE) "gecko" else "system"
    }.getOrDefault("")

    /**
     * 本 App 的包名 —— **取运行时真实值，不是 `BuildConfig.APPLICATION_ID`**。
     *
     * ## 为什么必须区分这两个
     *
     * `BuildConfig.APPLICATION_ID` 是**编译期**常量（= 构建时 manifest 里声明的
     * 包名）。在**被改过包名**的安装上，它与设备上真实生效的包名会分叉，
     * 而诊断包里两者都出现时就会**自相矛盾**，把真因盖住。
     *
     * 真机铁证（`20261014-144021-fd859918`，Xiaomi 2312DRA50C / Android 16）：
     * 该包 `app.log` 里的路径全是 `/data/user/0/com.dsh.androie/`
     * （`com.dsh.android` 出现 0 次、`com.dsh.androie` 21 次），
     * 而 `environment.json` 的 `applicationId` 却写着 `com.dsh.android` ——
     * 分析者照它去查，会以为「包名没问题」，从而错过真正的根因
     * （ELF 路径被改写成不存在的 `/data/data/com.dsh.android/`，
     * 症状是 `CANNOT LINK EXECUTABLE … library "libz.so.1" not found`）。
     *
     * 取值来源与 `TermuxBootstrap.elfRootReplacement` 保持同一套判据：
     * `filesDir` 的父目录名 = 系统按真实生效包名给出的目录名。
     * 拿不到就退回 `BuildConfig.APPLICATION_ID`（与改造前一致），
     * 绝不让一次探测失败影响诊断包生成。
     *
     * ⚠️ **不要用 `context.packageName`**：它读的是 manifest 的声明值，
     *   与 `BuildConfig.APPLICATION_ID` 同源，在被改过包名的安装上一样分叉。
     */
    val applicationId: String = runCatching {
        LogContext.filesDirOrNull()?.parentFile?.name
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.APPLICATION_ID
    }.getOrDefault("")

    /** `0.2.7 (14) system` 这种一行摘要。 */
    fun summary(): String = "$versionName ($versionCode) $flavor"

    /** 设备与系统的一行摘要。 */
    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) / ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
}
