package com.dsh.android

import com.dsh.android.log.DshLog as Log
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * UI 调试工具的**原生侧那一半** —— 把「模型想看/点界面」变成一次真实的内核调用。
 *
 * ## 它在整条链路上的位置
 *
 * ```
 * 模型(Node 进程)                原生(App 进程)                页面(WebView 内核)
 *   ui 工具  ──写 ui-cmd.json──▶  本类轮询
 *                                   └─ WebHost.evaluateJavascript ──▶  __DSH_UI_PROBE__.run(cmd)
 *   ui 工具  ◀──读 ui-result.json── 本类写回执  ◀──探针的 JSON 结果──────┘
 * ```
 *
 * 探针源码在 `assets/ui-probe.js`（页面世界的 JS），本类只负责：读命令、
 * 拼成 `(<探针源码>)(<cmd JSON>)`、丢给内核执行、把结果写回回执文件。
 *
 * ## 为什么坐标换算在这边做
 *
 * 探针只能给出 **CSS 视口坐标**（`getBoundingClientRect()`）。要落到屏幕物理
 * 像素上还需要 WebView 在屏幕上的位置与缩放比，而这两样**页面里拿不到** ——
 * `window.screenX` 在 WebView 里恒为 0。所以换算必须在原生侧：
 *
 * ```
 * scale        = webView宽度(物理px) / 视口宽度(CSS px)
 * 屏幕物理X     = webView屏幕X + cssX * scale
 * ```
 *
 * 这一步是「位置精确」的落点：AI 想用 `input tap X Y` 真机点击时，需要的就是
 * 屏幕物理坐标，而不是 CSS 坐标。
 *
 * ## 与 [BubbleCommandBridge] 的关系
 *
 * 同构（同一个文件信箱模式、同样的轮询与原子写），但**两个独立信箱**：
 * 命令是并发发起的，共用一个文件会让「两个调用互相覆盖请求」——
 * 那种失败是偶发的、最难查。理由与 `bubble-state.json` 独立成文件一致。
 *
 * ## 三条不变量（与 bubble 桥完全相同，刻意保持一致）
 *
 * ① 请求带自增 `id`，回执按 id 配对。
 * ② **先写结果、再删请求** —— 反过来会丢回执。
 * ③ 命令文件损坏不是错误，下一轮再读，不刷日志。
 *
 * ⚠️ 本类跑在 `dsh-ui-cmd` 后台线程上，**不能直接碰 View**。真正干活的是
 *   [WebHost.evaluateJavascript]，它自己保证在主线程执行（两个 flavor 都如此）。
 */
object UiProbeBridge {

    private const val TAG = DshApplication.TAG

    /** 请求文件名。**必须与 `mobile-ui-plugin/dsh-android-uiagent/lib/index.js` 一致。** */
    private const val CMD_FILE = "ui-cmd.json"

    /** 回执文件名。同上，两边必须一起改。 */
    private const val RESULT_FILE = "ui-result.json"

    /** 轮询间隔。见 [BubbleCommandBridge] 里为什么可以粗。 */
    private const val POLL_MS = 120L

    /**
     * 一次命令的总超时。
     *
     * ⚠️ 比 bubble 桥的 4 秒长得多，因为这里可能真的要等：`wait` 动作要轮询
     *   等界面出现、`tap` 之后界面可能还在加载。12 秒是「够长但仍会让模型
     *   及时得到失败反馈」的折中 —— 再长会让一轮对话白等太久。
     */
    private const val TIMEOUT_MS = 12_000L

    /** `wait` 动作的轮询间隔与上限。 */
    private const val WAIT_POLL_MS = 250L
    private const val WAIT_MAX_MS = 10_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-ui-cmd").apply { isDaemon = true }
    }

    private var task: ScheduledFuture<*>? = null

    /** `$DSH_HOME`，由 [start] 传入。 */
    @Volatile
    private var home: File? = null

    /**
     * 取内核的**当前实例**。
     *
     * ⚠️ 必须是「取的时候现拿」而不是启动时存一份：Activity 重建（旋转屏幕、
     *   从后台回来）会换掉整个 WebView，持旧引用会把 JS 打进一个已经销毁的
     *   内核里 —— 静默无结果。用一个取值函数就绕开了这个生命周期纠葛。
     */
    @Volatile
    private var hostProvider: (() -> UiProbeHost?)? = null

    /** 探针源码的懒加载缓存（`assets/ui-probe.js` 每次启动读一次就够）。 */
    @Volatile
    private var probeSource: String? = null

    /**
     * 原生侧需要从内核拿到的两样东西。
     *
     * 之所以不直接依赖 [com.dsh.android.web.WebHost]，是因为这个 object 在
     * `main` 源集里，而 `WebHost` 也在 `main` —— 依赖本身没问题，但把「执行 JS」
     * 与「问屏幕几何」两件事单独抽出来，可以让本类**不持有 View**，
     * 也就不必担心线程问题（两个 flavor 的 View 操作都要求主线程）。
     */
    interface UiProbeHost {
        /** 执行 JS 并把 JSON 文本结果交给回调。回调不保证被调用（契约见 WebHost）。 */
        fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?)

        /** WebView 在屏幕上的几何（物理像素）。取不到时返回 null。 */
        fun screenGeometry(): ScreenGeometry?

        /** 触发一次强制的界面重载（探针发现页面已坏时用）。 */
        fun reload()
    }

    /**
     * 内核 View 的屏幕几何 —— 把 CSS 坐标换算成屏幕物理坐标所需的全部输入。
     *
     * @param screenX View 左上角在屏幕上的 X（物理像素）
     * @param screenY 同上 Y
     * @param widthPx View 的宽度（物理像素）
     * @param heightPx View 的高度（物理像素）
     * @param density 屏幕密度（`resources.displayMetrics.density`）
     * @param viewportWidthCss 页面视口宽度（CSS 像素），由探针回报
     * @param viewportHeightCss 页面视口高度（CSS 像素），由探针回报
     */
    data class ScreenGeometry(
        val screenX: Int,
        val screenY: Int,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val viewportWidthCss: Double,
        val viewportHeightCss: Double,
    ) {
        /** 缩放比：物理像素 / CSS 像素。 */
        val scale: Double
            get() = if (viewportWidthCss > 0) widthPx / viewportWidthCss else density.toDouble()

        /** CSS 视口坐标 → 屏幕物理坐标。 */
        fun toScreen(cssX: Double, cssY: Double): Pair<Int, Int> {
            val s = scale
            return Pair(
                (screenX + cssX * s).toInt(),
                (screenY + cssY * s).toInt(),
            )
        }
    }

    /**
     * 启动轮询。由 `MainActivity` 在 Web 内核就绪后调用一次（幂等）。
     *
     * @param dshHome `$DSH_HOME`。目录不存在时仍然启动 —— 引擎初始化会建它。
     * @param provider 取内核实例的函数，见 [hostProvider] 为什么是函数。
     * @param assets 读探针源码用（传 `context.assets`）。
     */
    @Synchronized
    fun start(dshHome: File, assets: android.content.res.AssetManager, provider: () -> UiProbeHost?) {
        home = dshHome
        hostProvider = provider
        if (probeSource == null) {
            probeSource = try {
                assets.open(PROBE_ASSET).bufferedReader().use { it.readText() }
                    .also { Log.i(TAG, "UI 探针已装载 (${it.length} 字节)") }
            } catch (t: Throwable) {
                Log.w(TAG, "读取 UI 探针失败，ui 工具将不可用: ${t.message}")
                null
            }
        }
        if (task != null) return
        task = scheduler.scheduleWithFixedDelay(::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "UI 调试信箱已启动: ${File(dshHome, CMD_FILE).absolutePath}")
    }

    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    /** 探针是否可用（设置页/自检可以据此提示）。 */
    val probeReady: Boolean get() = probeSource != null && home != null

    // ────────────────────────────────────────────────────────────────────
    // 轮询
    // ────────────────────────────────────────────────────────────────────

    /**
     * 轮询一次 UI 调试命令信箱。
     *
     * ⚠️ **整个方法体必须被 try/catch 包住，不能有异常漏出去。**
     *
     * 它由 `scheduler.scheduleWithFixedDelay(::pollOnce, …)` 驱动，而
     * `ScheduledExecutorService` 的语义是：**周期任务一旦抛出未捕获异常，
     * 该任务就被永久终止** —— 后续周期不再执行，也没有任何通知。
     *
     * 于是症状是：这个信箱在某个瞬间之后再无响应，而日志里只有崩溃那一刻
     * 的一行堆栈，之后完全静默。用户看到的是「UI 调试命令突然不管用了」，
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
            Log.w(TAG, "UI 调试命令信箱轮询异常（已兜住，下个周期继续）: ${t.message}", t)
        }
    }

    private fun pollOnceInner() {
        val dir = home ?: return
        val cmd = File(dir, CMD_FILE)
        if (!cmd.isFile) return

        val text = try {
            cmd.readText()
        } catch (t: Throwable) {
            return // 下一轮再看
        }
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return // ③：写到一半，下一轮再读
        }

        val id = json.optLong("id", -1L)
        if (id < 0L) {
            Log.w(TAG, "UI 命令缺少 id，已丢弃")
            cmd.delete()
            return
        }

        // 先删请求再做：本命令可能耗时数秒（wait/tap 之后等界面），
        // 期间轮询线程被占住，不能让同一个文件被反复读到。
        cmd.delete()

        try {
            handle(dir, id, json)
        } catch (t: Throwable) {
            Log.w(TAG, "执行 UI 命令失败: ${t.message}")
            writeResult(dir, id, JSONObject().apply {
                put("ok", false)
                put("error", "原生侧执行失败: ${t.message}")
            })
        }
    }

    /**
     * 执行一条命令并把结果写回。
     *
     * 命令的 `action` 直接透传给页面探针 —— 原生只做三件探针做不到的事：
     * ① 坐标换算；② `wait` 的重复轮询；③ 页面已坏时的重载。
     */
    private fun handle(dir: File, id: Long, json: JSONObject) {
        val host = hostProvider?.invoke()
        if (host == null) {
            writeResult(dir, id, JSONObject().apply {
                put("ok", false)
                put("error", "Web 内核尚未就绪（界面还没加载）")
            })
            return
        }
        val source = probeSource
        if (source == null) {
            writeResult(dir, id, JSONObject().apply {
                put("ok", false)
                put("error", "UI 探针源码不可用（assets/ui-probe.js 读取失败）")
            })
            return
        }

        // 探针命令体：除了 control 字段，其余原样透传。
        val cmd = JSONObject(json.toString())
        cmd.remove("id")

        // ⚠️ `control` 有两个来源名，**两个都必须认**。
        //
        // 本桥最初只有 `control` 这个内部字段；但 `ui` 工具（模型侧）对外暴露的是
        // 统一的 `action` —— 它不可能知道原生内部另有一个字段名。只认 `control`
        // 的后果是**静默降级**：`action:"wait"` 落进下面的 else 分支只跑一次探针，
        // 「等元素出现」变成「立刻看一眼就说没出现」；`action:"reload"` 更直接，
        // 探针不认识这个 action，整次调用报「未知 action: reload」。
        // 两者都不报错到能看出根因，所以这里把 `action` 也当作 `control` 的来源。
        //
        // ⚠️ 只有 `wait` / `reload` 会被拦下来走原生分支；其余动作的 `control`
        //   等于动作名，落到 else 分支 —— 也就是**行为完全不变**。
        val control = cmd.optString("control", "")
            .ifBlank { cmd.optString("action", "") }
        cmd.remove("control")
        // `action` **必须留给探针**：它才是探针的分派依据（`control` 只是原生
        // 自己的旁路信号）。上面之所以只在 wait/reload 两条分支上用它，正是这个原因。

        val result = when (control) {
            "wait" -> runWait(host, source, cmd)
            "reload" -> {
                host.reload()
                JSONObject().apply {
                    put("ok", true)
                    put("reloaded", true)
                    put("note", "已触发界面重载；等 2–5 秒后界面才可用")
                }
            }
            else -> evalOnce(host, source, cmd, TIMEOUT_MS)
        }

        // 坐标换算：只在结果里**已经有**元素几何时做，且失败不影响主结果。
        if (result.optBoolean("ok", false)) {
            try {
                attachScreenCoords(host, result)
            } catch (t: Throwable) {
                Log.w(TAG, "坐标换算失败（结果仍返回）: ${t.message}")
            }
        }
        writeResult(dir, id, result)
    }

    /** 跑一次探针，同步等结果。超时返回一个明确的失败对象。 */
    private fun evalOnce(
        host: UiProbeHost,
        source: String,
        cmd: JSONObject,
        timeoutMs: Long,
    ): JSONObject {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)

        // ⚠️ 探针源码是 `function (CMD) {...}` 的**函数表达式**，所以这里用
        //   括号包起来直接调用。cmd 经 JSON 编码进来，没有字符串注入面。
        val script = "($source)(${cmd.toString()})"
        host.evaluateJavascript(script) { raw ->
            holder[0] = raw
            latch.countDown()
        }

        // ⚠️ 必须等：gecko 侧这条通道要过一次扩展 IPC，**不保证**回调会来
        //   （端口断了、页面已销毁）。超时给一个模型能理解的失败，而不是让它
        //   无限等下去。
        val arrived = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!arrived || holder[0] == null) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "等待页面探针超时（${timeoutMs}ms）—— 界面可能还在加载，" +
                    "或内核的 JS 通道未就绪")
            }
        }
        // `holder[0]` 上面刚判过非 null，但 Kotlin 看不到「赋值发生在别的线程」
        // 这件事，所以这里显式取一次局部量并做智能转换的兜底。
        val raw = holder[0]
            ?: return JSONObject().apply {
                put("ok", false)
                put("error", "页面探针没有返回任何内容")
            }
        return parseProbeResult(raw)
    }

    /**
     * 把内核回传的 JSON 文本解析成对象。
     *
     * ⚠️ 两处坑，都必须处理否则整次调用废掉：
     * ① **system 侧会返回被 JSON 二次编码的字符串**（`evaluateJavascript` 对
     *    String 返回值再编码一次）⇒ 结果是 `"{\"ok\":true…}"` 这种带引号的形态。
     * ② **gecko 侧探针抛错时**返回的是 `{"__dshError": "..."}` 而不是我们的形状。
     */
    private fun parseProbeResult(raw: String): JSONObject {
        var text = raw.trim()
        // ② gecko 的错误包装
        if (text.contains("__dshError")) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "页面探针抛错: $text")
            }
        }
        // ① 去掉可能存在的外层引号与转义
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            text = try {
                JSONObject().put("_", text).getString("_")
            } catch (t: Throwable) {
                text.substring(1, text.length - 1)
            }
        }
        return try {
            JSONObject(text)
        } catch (t: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", "页面返回的不是 JSON: " + text.take(200))
            }
        }
    }

    /**
     * `wait`：反复问「那个元素出现了吗」，直到出现或到点。
     *
     * 这是**唯一一个原生侧要循环的动作** —— 探针每次都只做单次检查（它必须是
     * 无状态的），循环放在这边才不会把「等 10 秒」这个语义塞进页面。
     */
    private fun runWait(host: UiProbeHost, source: String, cmd: JSONObject): JSONObject {
        val deadline = System.currentTimeMillis() + WAIT_MAX_MS
        var last: JSONObject? = null
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            val got = evalOnce(host, source, cmd, 3000L)
            last = got
            if (!got.optBoolean("ok", false)) {
                // 探针本身都没跑起来（页面在导航）—— 继续试，别急着失败
                Thread.sleep(WAIT_POLL_MS)
                continue
            }
            if (got.optBoolean("satisfied", false)) {
                got.put("attempts", attempts)
                return got
            }
            Thread.sleep(WAIT_POLL_MS)
        }
        return JSONObject().apply {
            put("ok", true)
            put("satisfied", false)
            put("attempts", attempts)
            put("error", "等待超时（${WAIT_MAX_MS}ms）—— 条件始终没有成立")
            put("last", last)
        }
    }

    /**
     * 给结果里的元素补上**屏幕物理坐标** —— 这是「AI 能自己点真机」的关键一步。
     *
     * 会处理三种形状：`element`（单个）、`elements`（dump 列表）、`hitTopmost`。
     * 拿不到 View 几何时只加一个 `screenAvailable:false`，**不让主结果失败** ——
     * 坐标只是附加信息，元素本身的可读信息仍然有用。
     */
    private fun attachScreenCoords(host: UiProbeHost, result: JSONObject) {
        val vp = result.optJSONObject("viewport")
        val geo = host.screenGeometry()
        if (geo == null || vp == null) {
            result.put("screenAvailable", false)
            return
        }
        val vw = vp.optDouble("w", geo.viewportWidthCss)
        val vh = vp.optDouble("h", geo.viewportHeightCss)

        val effective = geo.copy(
            viewportWidthCss = if (vw > 0) vw else geo.viewportWidthCss,
            viewportHeightCss = if (vh > 0) vh else geo.viewportHeightCss,
        )

        result.put("screenAvailable", true)
        result.put("screen", JSONObject().apply {
            put("x", effective.screenX)
            put("y", effective.screenY)
            put("w", effective.widthPx)
            put("h", effective.heightPx)
            put("scale", Math.round(effective.scale * 10000) / 10000.0)
            put("density", effective.density.toDouble())
            put("viewportCssW", effective.viewportWidthCss)
            put("viewportCssH", effective.viewportHeightCss)
        })

        fun convert(el: JSONObject?) {
            if (el == null) return
            val c = el.optJSONObject("center") ?: return
            val (sx, sy) = effective.toScreen(
                c.optDouble("x", 0.0),
                c.optDouble("y", 0.0),
            )
            el.put("screenCenter", JSONObject().apply {
                put("x", sx)
                put("y", sy)
            })
            val r = el.optJSONObject("rect")
            if (r != null) {
                val (l, t) = effective.toScreen(r.optDouble("x", 0.0), r.optDouble("y", 0.0))
                val (rr, b) = effective.toScreen(
                    r.optDouble("x", 0.0) + r.optDouble("w", 0.0),
                    r.optDouble("y", 0.0) + r.optDouble("h", 0.0),
                )
                el.put("screenRect", JSONObject().apply {
                    put("left", l); put("top", t); put("right", rr); put("bottom", b)
                })
            }
        }

        convert(result.optJSONObject("element"))
        convert(result.optJSONObject("focused"))
        convert(result.optJSONObject("hitTopmost"))
        val list = result.optJSONArray("elements")
        if (list != null) for (i in 0 until list.length()) convert(list.optJSONObject(i))
        val top = result.optJSONArray("top")
        if (top != null) for (i in 0 until top.length()) convert(top.optJSONObject(i))
    }

    /**
     * 写回执。**先临时文件再 rename** —— 插件可能正好读到这里，半个 JSON
     * 会让它那一轮白等（与 [BubbleCommandBridge] 同样的处理）。
     */
    private fun writeResult(dir: File, id: Long, payload: JSONObject) {
        val out = JSONObject().apply {
            put("id", id)
            for (key in payload.keys()) put(key, payload.get(key))
        }
        try {
            val target = File(dir, RESULT_FILE)
            val tmp = File(dir, "$RESULT_FILE.tmp")
            tmp.writeText(out.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(out.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入 UI 命令回执失败: ${t.message}")
        }
    }

    /** 探针在 assets 里的位置。 */
    private const val PROBE_ASSET = "ui-probe.js"
}
