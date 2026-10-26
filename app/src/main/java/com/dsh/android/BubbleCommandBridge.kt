package com.dsh.android

import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 悬浮球的**命令信箱** —— 让 Node 进程里的模型工具能改到原生这个球。
 *
 * ## 为什么需要它
 *
 * 悬浮球的外观接口原先只有页面侧插件够得着（`window.DSHAndroid.bubbleApply()`），
 * 而模型跑在 **Node 进程**里，那里没有 `window`。于是「让 AI 改悬浮窗」在
 * 可发现性上是空的：模型既不知道有这个能力，也没有任何途径触达它。
 *
 * 本类补的就是这一段：`dsh-android-bubble` 插件把命令写进 `$DSH_HOME/bubble-cmd.json`，
 * 这里轮询读走、交给 [FloatingBubble.applyAppearance]，再把结果写回
 * `$DSH_HOME/bubble-result.json`。
 *
 * ## 为什么是文件而不是 socket / Binder
 *
 * 引擎 node 是 App 进程的**子进程**，两者同域、共享 app 私有目录 —— 文件是最短的
 * 那条路。本项目已有同构先例（`adb-wireless-port`：Kotlin 写、Node 读），这里只是
 * 把方向反过来。不选 AF_UNIX 抽象命名空间：Android 的 app 域对命名管道的策略
 * 随版本变（见 AGENTS.md 的沙箱说明），而文件在两个方向上都已经验过。
 *
 * ## 三条不变量
 *
 * ① **请求带自增 `id`，回执按 id 配对。** 没有它，「两个工具调用并发、后一个的
 *    回执被前一个读走」会让模型拿到张冠李戴的结果。
 * ② **先写结果、再删请求。** 反过来会丢回执：插件那边「没看到回执」与「原生还没处理」
 *    无法区分，只能超时重试。
 * ③ **命令文件损坏（写到一半）不是错误。** 插件用「写临时文件 + rename」发布，
 *    这里读到解析失败就下一轮再读，不要记错误日志刷屏。
 *
 * ⚠️ 轮询间隔 250ms 是**故意粗**的：这是给人按需改外观的通道（一天可能用几次），
 * 不值得为它做 inotify。真机实测一次改动的往返（含此间隔）在 300ms 内。
 */
object BubbleCommandBridge {

    private const val TAG = DshApplication.TAG

    /** 请求文件名。**必须与 `mobile-ui-plugin/dsh-android-bubble/lib/index.js` 一致。** */
    private const val CMD_FILE = "bubble-cmd.json"

    /** 回执文件名。同上，两边必须一起改。 */
    private const val RESULT_FILE = "bubble-result.json"

    /**
     * **状态播报**文件（单向、无回执）。
     *
     * 由常驻状态桥插件（`dsh-android-pet`）写：它订阅引擎的 `session/event`，
     * 每有进展就把"现在是什么运行状态"放进来，这里读走、**消费掉（删除）**。
     *
     * ## 为什么不让它复用 [CMD_FILE]
     *
     * 两条理由，任一条都足以否决复用：
     *
     * ① **id 空间会撞。** 回执是按 `id` 配对的，而本桥与那个插件各自从 1 开始
     *    自增（两个独立进程里的两个独立计数器）。同一个 `id=5` 同时存在时，
     *    工具调用会收到状态播报的回执 —— 表现为"AI 说改了悬浮球，但没生效"，
     *    极难归因。
     * ② **会互相覆盖。** 工具调用写完命令要等最多 4 秒回执，这期间状态播报若是
     *    覆盖了同一个文件，工具调用必然超时。
     *
     * 而状态播报**本来就不需要回执**（它是"我现在在干什么"，不是"请你做什么"），
     * 所以独立文件、读完即删，是最贴合语义的做法。
     */
    private const val STATE_FILE = "bubble-state.json"

    /**
     * **余额信箱**文件名（单向、无回执）。
     *
     * 由 `dsh-android-balance` 插件写：它订阅引擎的 `assistant/message`
     * 拿 usage、按定价折算成本轮花费，并定期拉官方账号余额。
     *
     * ⚠️ 用**独立文件**而不是塞进 [STATE_FILE]：两者的写入频率差一个量级
     *   （状态是每 120ms 一次、余额是每轮/每几分钟一次），混在一起会让
     *   余额的高频覆盖掉一条刚到的状态，或者反过来 —— 表现为"宠物动作
     *   偶尔卡一下"这种没法归因的问题。
     */
    private const val BALANCE_FILE = "bubble-balance.json"

    /** 轮询间隔。见类注释里为什么是粗粒度。 */
    private const val POLL_MS = 250L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-bubble-cmd").apply { isDaemon = true }
    }

    private var task: ScheduledFuture<*>? = null

    /** `$DSH_HOME`，由 [start] 传入；未启动时为 null。 */
    @Volatile
    private var home: File? = null

    /**
     * 启动轮询。由 `EngineManager` 在引擎启动前调用一次（幂等）。
     *
     * @param dshHome `$DSH_HOME`（`<filesDir>/home/.dsh`）。不存在时仍然启动 ——
     *   目录会在引擎初始化时被创建，早启动只是多几次空轮询。
     */
    @Synchronized
    fun start(dshHome: File) {
        home = dshHome
        if (task != null) return
        task = scheduler.scheduleWithFixedDelay(::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "悬浮球命令信箱已启动: ${File(dshHome, CMD_FILE).absolutePath}")
    }

    /** 停掉轮询（App 进程退出时其实无所谓，这里给测试/重启留一个干净的入口）。 */
    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    /**
     * 读一次信箱。跑在 `dsh-bubble-cmd` 线程上，所以**不能**在这里碰视图 ——
     * [FloatingBubble.applyAppearance] 内部自己会切主线程。
     */
    /**
     * 轮询一次悬浮球命令信箱。
     *
     * ⚠️ **整个方法体必须被 try/catch 包住，不能有异常漏出去。**
     *
     * 它由 `scheduler.scheduleWithFixedDelay(::pollOnce, …)` 驱动，而
     * `ScheduledExecutorService` 的语义是：**周期任务一旦抛出未捕获异常，
     * 该任务就被永久终止** —— 后续周期不再执行，也没有任何通知。
     *
     * 于是症状是：这个信箱在某个瞬间之后再无响应，而日志里只有崩溃那一刻
     * 的一行堆栈，之后完全静默。用户看到的是「悬浮球命令突然不管用了」，
     * 实际是轮询线程已经死了，重启 App 才能恢复。
     *
     * 能抛的点都在外部输入路径上（读文件、JSON 解析、落盘、系统调用），
     * 内层即便已经兜了几处，外层仍必须有这一层 —— 漏一处就是永久失效。
     */
    private fun pollOnce() {
        try {
            pollOnceInner()
        } catch (t: Throwable) {
            // 吞掉但**必须留痕**：静默吞等于把问题藏起来。
            Log.w(TAG, "悬浮球命令信箱轮询异常（已兜住，下个周期继续）: ${t.message}", t)
        }
    }

    private fun pollOnceInner() {
        val dir = home ?: return
        // 状态播报先读：它是**穷尽式**的（读到就删），与命令信箱互不影响。
        // ⚠️ 顺序不能反 —— 命令分支在极端情况下会 return（缺 id 时丢弃请求），
        //   放在它后面会让状态播报跟着一起漏读一拍。
        pollState(dir)
        // 余额信箱与状态播报同级：都是单向、读到就删、互不影响。
        // 放在命令分支之前同理 —— 命令分支会 return，不该连累余额刷新。
        pollBalance(dir)
        val cmd = File(dir, CMD_FILE)
        if (!cmd.isFile) return

        val text = try {
            cmd.readText()
        } catch (t: Throwable) {
            // 读失败（权限/被删）：下一轮再看，不刷日志
            return
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            // ③：写到一半或坏了，下一轮再读
            return
        }

        val id = json.optLong("id", -1L)
        if (id < 0L) {
            // 没有 id 的请求无法配对回执，直接丢弃（否则会卡住一个永远等不到答案的工具调用）
            Log.w(TAG, "悬浮球命令缺少 id，已丢弃")
            cmd.delete()
            return
        }

        val action = json.optString("action", "set")
        val ok: Boolean
        val message: String
        var appearance = ""
        try {
            if (action == "get") {
                appearance = FloatingBubble.currentAppearanceJson()
                ok = appearance != "{}"
                message = if (ok) "" else "悬浮球尚未就绪（appContext 未初始化）"
            } else if (action == "notify") {
                // AI 主动往球上方的状态气泡推一条消息。
                //
                // ⚠️ 与 `set` 是**两条独立通道**：`set` 改的是持久外观（会被
                //   SharedPreferences 记住），`notify` 只影响这一次显示，不改配置。
                //   所以这里不调 applyAppearance，也不需要回读 appearance。
                val text = json.optString("text", "")
                val durationMs = json.optLong("durationMs", 0L)
                val sticky = json.optBoolean("sticky", false)
                ok = FloatingBubble.pushStatusToActive(text, durationMs, sticky)
                message = if (ok) "" else "悬浮球尚未显示（没有活动的悬浮球实例）"
            } else if (action == "state") {
                // 走**运行状态**这一路：常驻状态桥插件（见 `dsh-android-pet`）订阅引擎的
                // `session/event`，每有进展就往这里推一次。
                //
                // ⚠️ 与 `set` 刻意分开：`set` 改的是**持久外观**（落盘），而运行状态
                //   每几秒就变一次，走那条路会把 SharedPreferences 刷爆，还会把
                //   AI 自己配好的外观冲掉。
                //
                // ⚠️ **动作名走 `name` 字段，不能用 `action`** —— 本文件的 `action`
                //   已经被占用为「命令名」，拿它当动作名会读到命令名本身，插件那边
                //   看着像"切动作成功"但宠物演的还是默认动作。
                val state = json.optString("state", "").trim()
                // ⚠️ `name` 缺席与空串是**两种意思**，所以不能用 optString：
                //   缺席 = 别动临时钦定的动作（只更新运行状态），
                //   空串 = 清掉临时钦定（回到 actionMap 自动映射）。
                val hasName = json.has("name")
                val act = if (hasName) json.optString("name", "").trim() else null
                if (state.isEmpty() && act == null) {
                    ok = false
                    message = "state 命令至少要带 state 或 name"
                } else {
                    ok = FloatingBubble.applyRunStateToActive(state, act)
                    message = if (ok) "" else "悬浮球尚未显示（没有活动的悬浮球实例）"
                }
            } else {
                // 合并语义在 applyAppearance 内部（它会把字段并到当前配置上），
                // 所以这里只管把 fields 序列化过去。
                val fields = json.optJSONObject("fields") ?: JSONObject()
                ok = FloatingBubble.applyAppearance(fields.toString())
                appearance = FloatingBubble.currentAppearanceJson()
                message = if (ok) "" else "悬浮球尚未就绪（appContext 未初始化）"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "执行悬浮球命令失败: ${t.message}")
            writeResult(dir, id, false, "原生侧执行失败: ${t.message}", "")
            cmd.delete()
            return
        }

        // ②：先写回执，再删请求。反过来的话插件会超时。
        writeResult(dir, id, ok, message, appearance)
        cmd.delete()
    }

    /**
     * 读一次**状态播报**（单向）。
     *
     * ⚠️ 「先读内容、再删文件」的窗口是**故意留的**：播报是幂等的（同一个状态
     *   重复应用没有副作用），所以哪怕这一拍读到、删之前被杀、下一拍再读一次，
     *   结果也完全一样。反过来（先删再读）会真丢一拍 —— 那才是要避免的。
     *
     * ⚠️ 解析失败**不删文件**，下一拍再试。写到一半的 JSON 是正常现象（插件用
     *   「写临时文件 + rename」发布，虽然理论上不会留下半个，但这里不必依赖它）。
     */
    private fun pollState(dir: File) {
        val f = File(dir, STATE_FILE)
        if (!f.isFile) return
        val text = try {
            f.readText()
        } catch (t: Throwable) {
            return
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return
        }
        val state = json.optString("state", "").trim()
        // ⚠️ `action` 同样要能区分"缺席"与"空串"：缺席 = 只更新运行状态，
        //   空串 = 清掉临时钦定、回到 actionMap 自动映射。
        val hasAction = json.has("action")
        val action = if (hasAction) json.optString("action", "").trim() else null
        try {
            FloatingBubble.applyRunStateToActive(state, action)
        } catch (t: Throwable) {
            Log.w(TAG, "应用悬浮球运行状态失败: ${t.message}")
        }
        f.delete()
    }

    /**
     * 读一次**余额信箱**（单向）。
     *
     * 与 [pollState] 同一套规矩：先读内容、解析成功后再删；解析失败不删，下一拍再试。
     *
     * 载荷结构见 `dsh-android-balance/lib/index.js` 的 `flush()`：
     * `{ wallets, bonusWallets, costLast, costSession, at, error }`。
     *
     * ⚠️ 与状态播报不同，这里**必须**把 `wallets` 当数组逐项解析：余额是按币种
     *   分开的，只取第一个会在"有美元余额"的账号上显示错币种的数字。
     */
    private fun pollBalance(dir: File) {
        val f = File(dir, BALANCE_FILE)
        if (!f.isFile) return
        val text = try {
            f.readText()
        } catch (t: Throwable) {
            return
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return
        }
        val wallets = json.optJSONArray("wallets")
        val bonusWallets = json.optJSONArray("bonusWallets")
        // `costLast` / `costSession` 是 Number（Double），用字符串转 BigDecimal
        // 以避开 `0.1+0.2` 那类浮点尾数问题（金额显示两位小数时尤其明显）。
        val costLast = decimalOf(json.opt("costLast"))
        val costSession = decimalOf(json.opt("costSession"))
        val error = json.optString("error", "")

        val snap = BalanceSnapshot.fromWallets(
            rechargeJson = wallets,
            bonusJson = bonusWallets,
            costLast = costLast,
            previous = null,
        ).let { base ->
            // `fromWallets` 会把 costLast 累加进 session；但插件给的 costSession
            // 是**它自己算的权威值**，直接用，避免两边算法漂移。
            base.copy(costSession = costSession, error = error)
        }

        try {
            FloatingBubble.applyBalanceToActive(snap)
        } catch (t: Throwable) {
            Log.w(TAG, "应用余额快照失败: ${t.message}")
        }
        f.delete()
    }

    /**
     * 把 JSON 里的数字/字符串转成 `BigDecimal`。
     *
     * @returns 解析失败时返回 0（金额显示上比抛异常合理）。
     */
    private fun decimalOf(v: Any?): java.math.BigDecimal = when (v) {
        null -> java.math.BigDecimal.ZERO
        is Number -> runCatching { java.math.BigDecimal(v.toString()) }.getOrDefault(java.math.BigDecimal.ZERO)
        is String -> runCatching { java.math.BigDecimal(v.trim()) }.getOrDefault(java.math.BigDecimal.ZERO)
        else -> java.math.BigDecimal.ZERO
    }

    /**
     * 写回执。失败**不重试** —— 插件会在超时后自己用 `action:"get"` 复查，
     * 而那正是我们想要的语义（宁可让模型多查一次，也不要它拿一个假成功）。
     */
    private fun writeResult(dir: File, id: Long, ok: Boolean, message: String, appearance: String) {
        val out = JSONObject().apply {
            put("id", id)
            put("ok", ok)
            put("message", message)
            put("appearance", appearance)
        }
        try {
            val target = File(dir, RESULT_FILE)
            // 原子发布：插件可能正好读到这里，半个 JSON 会让它那一轮白等。
            val tmp = File(dir, "$RESULT_FILE.tmp")
            tmp.writeText(out.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(out.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入悬浮球命令回执失败: ${t.message}")
        }
    }
}
