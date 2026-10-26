package com.dsh.android

import com.dsh.android.log.DshLog as Log
import com.dsh.android.log.RescueBoot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 救援命令**信箱** —— 让救援会话里的 AI 能真正验证「改完能不能起来」。
 *
 * ## 为什么需要它
 *
 * 救援模式（L2）的目标是「App 打得开，AI 帮你修坏插件」。AI 手上有日志、
 * 有出问题的 `profiles/web/package.json`，但它**没法验证自己的修改** ——
 * 改完配置之后需要一次真实启动才知道对不对，而 AI 跑在引擎进程里，
 * 不可能重启自己（重启就把自己杀了）。
 *
 * 让用户手动重启 App 也不可行：每轮 20 秒 + 人工操作，修复循环要走十几轮。
 *
 * 所以照本项目已经用了四次的**文件信箱**范式（[BubbleCommandBridge] /
 * [UiProbeBridge] / [LogCommandBridge] / [SystemBarCommandBridge]）再加一条：
 * AI 写 `$DSH_HOME/rescue-cmd.json`，App 读走执行，结果写
 * `$DSH_HOME/rescue-result.json`。
 *
 * ## 动作
 *
 * | action | 作用 | 副作用 |
 * |---|---|---|
 * | `try-boot` | 用指定 profile **真跑一次**引擎，看能不能到打印 URL | 写日志、可能写崩溃现场（带 `probe-` 前缀） |
 * | `switch-rescue` | 把启动层级置为救援，重启界面 | 改 `rescue/state.json` |
 * | `switch-back` | 切回正常 profile，重启界面 | 同上 |
 * | `status` | 读当前层级与上次失败原因 | 无 |
 *
 * ## ⚠️ 四条不变量（与现有三个桥刻意保持一致）
 *
 * ① **`id` 必须是整数**，且处理过的最大 id 要持久化 —— 幂等靠它。
 *    现有信箱踩过这个坑：`id` 类型写错时**静默丢弃**，工具调用就一直等不到回执。
 *    本桥对「id 不是整数」的情形**明确报错**（写回 `ok:false` + 说明），
 *    而不是静默丢弃 —— 静默是那类 bug 最难查的形态。
 * ② **先写回执、再删请求。** 反过来会丢回执：插件那边「没看到回执」与
 *    「原生还没处理」无法区分，只能超时重试。
 * ③ **请求文件损坏（写到一半）不是错误**：下一轮再读，不刷日志。
 * ④ **`try-boot` 有配额**：一次救援会话最多 [PROBE_QUOTA] 次。它是真实启动，
 *    每次都要花十几秒并写日志，无配额的话 AI 可能陷在循环里把设备拖垮。
 *
 * ⚠️ 轮询间隔 200ms：`try-boot` 本身要十几秒，但轮询只负责**取走请求**，
 *   耗时的部分在后台线程里跑（见 [pollOnce]），所以间隔与耗时无关。
 */
object RescueCommandBridge {

    private const val TAG = DshApplication.TAG

    /** 请求文件名。**必须与救援 skill 里教给 AI 的名字一致。** */
    const val CMD_FILE = "rescue-cmd.json"

    /** 回执文件名。同上。 */
    const val RESULT_FILE = "rescue-result.json"

    /** 轮询间隔。 */
    private const val POLL_MS = 200L

    /**
     * 一次救援会话的 `try-boot` 配额。
     *
     * 20 次 ≈ 6~7 分钟（每次十几秒）。取这个数是因为：40 个插件的二分定位
     * 上限是 ⌈log2(40)⌉ = 6 轮，AI 手工分组验证通常也要 5~15 轮，
     * 20 次够用且有明确上限。
     */
    private const val PROBE_QUOTA = 20

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-rescue-cmd").apply { isDaemon = true }
    }

    /** `probe` 要跑十几秒，不能占着轮询线程 —— 它单独有一条。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-rescue-probe").apply { isDaemon = true }
    }

    private var task: ScheduledFuture<*>? = null

    @Volatile
    private var home: File? = null

    /** 处理过的最大 id（幂等）。⚠️ 只在内存里 —— 见 [loadHandledId] 的说明。 */
    @Volatile
    private var handledId: Long = -1L

    /** 本次会话已用掉的 probe 次数。 */
    @Volatile
    private var probesUsed = 0

    /**
     * 启动轮询。由 `EngineManager` 在引擎启动前调用一次（幂等）。
     *
     * ⚠️ **必须在两个层级下都启动**（正常与救援）。救援态当然要它；
     *   正常态也要 —— 否则 AI 在正常会话里想验证一次修改就没有手段，
     *   而"修好了没有"这件事恰恰要在正常态确认。
     */
    @Synchronized
    fun start(dshHome: File) {
        home = dshHome
        handledId = loadHandledId(dshHome)
        if (task != null) return
        task = scheduler.scheduleWithFixedDelay(::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "救援命令信箱已启动: ${File(dshHome, CMD_FILE).absolutePath}")
    }

    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    /**
     * 从 `rescue/probe-count.json` 读回「处理过的最大 id」。
     *
     * ⚠️ **为什么必须持久化**：App 可能在 AI 发过请求之后被杀（救援态下这很常见 ——
     *   引擎刚崩过）。重启后如果从 -1 重新开始，同一条请求会被**再执行一次**，
     *   而 `probe` 是有副作用的（真启动一次、写日志）。
     *   现有 `LogCommandBridge` 用的就是这个模式。
     *
     * 读不出来（首次运行/文件坏了）时返回 -1，即"什么都没处理过"。
     */
    private fun loadHandledId(dshHome: File): Long {
        val f = File(dshHome, "${RescueBoot.DIR_NAME}/probe-count.json")
        if (!f.isFile) return -1L
        return try {
            val o = JSONObject(f.readText())
            probesUsed = o.optInt("used", 0)
            o.optLong("handledId", -1L)
        } catch (t: Throwable) {
            // 坏了就当没处理过：宁可多执行一次，也不要因为一个坏文件而永久卡死信箱。
            Log.w(TAG, "读取救援信箱进度失败（按未处理过继续）: ${t.message}")
            -1L
        }
    }

    /** 落盘进度（处理过的最大 id + 已用配额）。失败不阻塞 —— 见 [loadHandledId]。 */
    private fun saveHandledId(id: Long) {
        val dir = home ?: return
        try {
            File(dir, RescueBoot.DIR_NAME).mkdirs()
            val f = File(dir, "${RescueBoot.DIR_NAME}/probe-count.json")
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(
                JSONObject()
                    .put("handledId", id)
                    .put("used", probesUsed)
                    .put("quota", PROBE_QUOTA)
                    .toString(),
            )
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入救援信箱进度失败: ${t.message}")
        }
    }

    /** 读一次信箱。跑在 `dsh-rescue-cmd` 线程上。 */
    /**
     * 轮询一次信箱。
     *
     * ⚠️ **整个方法体必须被 try/catch 包住，不能有异常漏出去。**
     *
     * 它由 `scheduler.scheduleWithFixedDelay(::pollOnce, …)` 驱动，而
     * `ScheduledExecutorService` 的语义是：**周期任务一旦抛出未捕获异常，
     * 该任务就被永久终止**（后续周期不再执行，也没有任何回调通知）。
     *
     * 这条通道偏偏是「App 起不来时的唯一出路」—— 一旦它被静默终止，
     * 用户和 AI 都再也发不进救援命令，而日志里只会在崩溃那一刻留一行堆栈，
     * 之后**完全静默**：看着像「救援命令没人处理」，实际是轮询线程已经死了。
     *
     * 这里能抛的点不少，且都跟外部输入有关：读文件、JSON 解析（已在局部兜了）、
     * `handleFast` 里各种系统调用、`writeResult` 落盘。
     * 所以即便内层已经兜了几处，外层仍必须有这一层 —— 漏一处就是永久失效。
     */
    private fun pollOnce() {
        try {
            pollOnceInner()
        } catch (t: Throwable) {
            // 吞掉但**必须留痕**：静默吞等于把问题藏起来。
            Log.w(TAG, "救援信箱轮询异常（已兜住，下个周期继续）: ${t.message}", t)
        }
    }

    private fun pollOnceInner() {
        val dir = home ?: return
        val cmd = File(dir, CMD_FILE)
        if (!cmd.isFile) return

        val text = try {
            cmd.readText()
        } catch (t: Throwable) {
            return
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            // ③：写到一半，下一轮再读
            return
        }

        // ⚠️ `optLong` 对字符串 `"7"` 会**静默返回默认值**（不是 7），
        //   于是「id 写成字符串」表现为「请求被无声忽略」—— 现有信箱踩过。
        //   这里显式检查类型，把这类请求**明确报错**而不是丢掉。
        val rawId = json.opt("id")
        if (rawId !is Number) {
            Log.w(TAG, "救援命令的 id 不是整数（实际 ${rawId?.javaClass?.simpleName ?: "缺失"}），已明确拒绝")
            writeResult(dir, -1L, false, "id 必须是整数（收到 ${rawId ?: "缺失"}）", null)
            cmd.delete()
            return
        }
        val id = rawId.toLong()

        // ①：幂等。已处理过的 id 直接丢弃请求（回执早就写过了）。
        if (id <= handledId) {
            Log.i(TAG, "救援命令 id=$id 已处理过，忽略")
            cmd.delete()
            return
        }

        val action = json.optString("action", "status")
        // ⚠️ 先认领 id（并把请求删掉），再执行 —— 否则执行期间同一个请求
        //   会被下一轮轮询**再取一次**，probe 就会跑两遍。
        handledId = id
        saveHandledId(id)
        cmd.delete()

        // ⚠️ `try-boot` 要十几秒，**不能在这个线程上跑**：轮询线程被占住，
        //   后续请求（比如用户等不及发的 switch-back）就全堵住了。
        if (action == "try-boot") {
            val profile = json.optString("profile", EngineManager.PROFILE_WEB)
            // `bundleFilter` 是可选参数：不给就是"用磁盘上的配置原样试一次"，
            // 给了就临时只加载这些 bundle（探测完还原，见 probeWithBundleFilter）。
            val filter = json.optJSONArray("bundleFilter")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            }
            // ⚠️ 这个 lambda 跑在独立线程池上，**没有 catch 出口**：
            //   未捕获异常虽然不会杀进程（ExecutorService 会捕获后交给
            //   线程的 UncaughtExceptionHandler），但 `try-boot` 就**没有任何回执**了
            //   —— AI 那边等的是一个永远不会来的结果，只能干等到超时。
            //   所以这里必须自己兜住，失败也要写回执。
            worker.execute {
                try {
                    val result = runTryBoot(dir, id, profile, filter)
                    writeResult(dir, id, result.first, result.second, result.third)
                } catch (t: Throwable) {
                    Log.w(TAG, "try-boot 执行异常: ${t.message}", t)
                    runCatching {
                        writeResult(
                            dir, id, false,
                            "try-boot 执行时抛异常: ${t.javaClass.simpleName}: ${t.message}",
                            null,
                        )
                    }
                }
            }
        } else {
            val result = handleFast(dir, id, action, json)
            writeResult(dir, id, result.first, result.second, result.third)
        }
    }

    /**
     * 立刻能返回的动作（不启进程）。
     *
     * @return (ok, message, extraJson)
     */
    private fun handleFast(
        dir: File,
        id: Long,
        action: String,
        json: JSONObject,
    ): Triple<Boolean, String, JSONObject?> = try {
        val engine = EngineManager.get(DshApplication.instance)
        when (action) {
            "status" -> {
                val st = RescueBoot.tier()
                Triple(
                    true,
                    "当前层级：${st.tier.label}（自 ${RescueBoot.stateSinceText() ?: "未知"}）",
                    JSONObject()
                        .put("tier", st.tier.id)
                        .put("tierLabel", st.tier.label)
                        .put("sinceText", RescueBoot.stateSinceText() ?: JSONObject.NULL)
                        .put("reason", st.reason)
                        .put("removedBundles", JSONArray(st.removedBundles))
                        .put("rescueProfileAvailable", engine.rescueAvailable)
                        .put("probesUsed", probesUsed)
                        .put("probeQuota", PROBE_QUOTA)
                        .put("lastFailure", RescueBoot.lastFailure()?.let { f ->
                            JSONObject()
                                .put("bootId", f.bootId)
                                .put("requiredDidNotActivate", f.requiredDidNotActivate ?: JSONObject.NULL)
                                .put("failedPlugins", JSONArray(f.failedPlugins))
                                .put("claimedFailedPlugins", f.claimedFailedPlugins ?: JSONObject.NULL)
                                .put("failedCountMismatch", f.failedCountMismatch ?: JSONObject.NULL)
                                // ⚠️ **救援 AI 修这个故障唯一拿得到的线索，不能漏。**
                                //   真机那次引擎是「成功启动」的（`requiredDidNotActivate`
                                //   为 null、`failedPlugins` 为空），AI 若只看上面那几个
                                //   字段，会以为「没有失败记录」而无从下手。
                                //   非空即表示「引擎起来了但 typert gateway 下线」。
                                .put("gatewayCulprit", f.gatewayCulprit ?: JSONObject.NULL)
                                .put("atText", com.dsh.android.log.SafeDateFormat(
                                    "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US,
                                ).format(java.util.Date(f.at)))
                        } ?: JSONObject.NULL),
                )
            }
            "switch-rescue" -> {
                RescueBoot.setTier(
                    RescueBoot.Tier.RESCUE,
                    "AI 请求切回救援模式",
                )
                engine.requestRestart("AI 请求切回救援模式")
                Triple(true, "已切到救援模式，界面即将重启", null)
            }
            "switch-back" -> {
                // ⚠️ 复位到 NORMAL 而不是"删掉 state.json"：文件留着，
                //   历史原因（`since`/`reason`）还读得到，诊断包也还能说清
                //   "什么时候从救援模式回来的"。
                RescueBoot.setTier(RescueBoot.Tier.NORMAL, "AI 请求切回正常模式")
                engine.requestRestart("AI 请求切回正常模式")
                Triple(true, "已切回正常模式，界面即将重启", null)
            }
            else -> Triple(false, "未知 action: $action", null)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "执行救援命令失败: ${t.message}")
        Triple(false, "原生侧执行失败: ${t.message}", null)
    }

    /**
     * `try-boot`：真跑一次引擎，看能不能到打印 URL。
     *
     * ⚠️ **这是本方案里唯一能回答「改完能起来了吗」的手段**（不变量 I6）。
     *   它跑的是真实启动，不是 `--dump-config`。
     *
     * @param filter 非空时临时只加载这些 bundle（探测完无条件还原）。
     * @return (ok, message, extraJson)
     */
    private fun runTryBoot(
        dir: File,
        id: Long,
        profile: String,
        filter: List<String>?,
    ): Triple<Boolean, String, JSONObject?> {
        if (probesUsed >= PROBE_QUOTA) {
            // §7.3 第 16 条：配额用尽必须**明确说**，不能静默失败。
            Log.w(TAG, "试启动配额已用尽（$PROBE_QUOTA 次）")
            return Triple(
                false,
                "试启动配额已用尽（本会话已用 $probesUsed/$PROBE_QUOTA 次）。" +
                    "请改用其它方式验证，或让用户重启 App 以重置配额。",
                JSONObject().put("quotaExhausted", true).put("probesUsed", probesUsed),
            )
        }
        probesUsed++
        saveHandledId(handledId)

        val engine = EngineManager.get(DshApplication.instance)
        val result = engine.probeBootForBridge(profile, filter)
        return if (result == null) {
            Triple(
                false,
                "试启动根本跑不起来（node 或引擎入口缺失）—— 这不是插件问题",
                JSONObject().put("started", false),
            )
        } else {
            val extra = JSONObject()
                .put("ready", result.ready)
                .put("exitCode", result.exitCode ?: JSONObject.NULL)
                .put("requiredDidNotActivate", result.requiredDidNotActivate ?: JSONObject.NULL)
                .put("tail", JSONArray(result.tail))
                .put("probesUsed", probesUsed)
                .put("probeQuota", PROBE_QUOTA)
            Triple(
                result.ready,
                if (result.ready) {
                    "试启动成功：引擎打印了 URL（profile=$profile）"
                } else if (result.looksLikePluginProblem) {
                    "试启动失败：${result.requiredDidNotActivate} 个 required 插件未激活（插件问题）"
                } else {
                    "试启动失败，但引擎没有报 required 插件未激活 —— 多半不是插件问题"
                },
                extra,
            )
        }
    }

    /**
     * 写回执。失败**不重试** —— 与现有三个桥一致：AI 会在超时后自己用
     * `action:"status"` 复查，而那正是想要的语义（宁可让它多查一次，
     * 也不要它拿一个假成功）。
     */
    private fun writeResult(dir: File, id: Long, ok: Boolean, message: String, extra: JSONObject?) {
        val out = JSONObject().apply {
            put("id", id)
            put("ok", ok)
            put("message", message)
            if (extra != null) put("data", extra)
        }
        try {
            val target = File(dir, RESULT_FILE)
            // 原子发布：AI 可能正好读到这里，半个 JSON 会让它那一轮白等。
            val tmp = File(dir, "$RESULT_FILE.tmp")
            tmp.writeText(out.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(out.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入救援命令回执失败: ${t.message}")
        }
    }
}
