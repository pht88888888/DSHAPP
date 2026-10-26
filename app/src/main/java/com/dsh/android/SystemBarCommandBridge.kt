package com.dsh.android

import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 系统栏（状态栏 / 导航栏）配色的**命令信箱** —— 让 Node 进程里的模型工具
 * 能改到原生这两条栏。
 *
 * ## 为什么需要它
 *
 * 与 [BubbleCommandBridge] 完全同构：配色配置原先只有 App 自己（页面底色驱动）
 * 够得着，而模型跑在 **Node 进程**里，那里既没有 `window` 也没有 Activity。
 * 本类把 `dsh-android-statusbar` 插件写进 `$DSH_HOME/statusbar-cmd.json` 的命令
 * 读走、交给 [SystemBarAppearance]，再把结果写回 `$DSH_HOME/statusbar-result.json`。
 *
 * ## 为什么是文件而不是 socket / Binder
 *
 * 引擎 node 是 App 进程的**子进程**，两者同域、共享 app 私有目录 —— 文件是最短的
 * 那条路。本项目已有两处同构先例（[BubbleCommandBridge] / [UiProbeBridge]）。
 *
 * ## 三条不变量（与 bubble 桥完全相同，刻意保持一致）
 *
 * ① **请求带自增 `id`，回执按 id 配对。** 没有它，「两个工具调用并发、后一个的
 *    回执被前一个读走」会让模型拿到张冠李戴的结果。
 * ② **先写结果、再删请求。** 反过来会丢回执：插件那边「没看到回执」与「原生还没处理」
 *    无法区分，只能超时重试。
 * ③ **命令文件损坏（写到一半）不是错误。** 插件用「写临时文件 + rename」发布，
 *    这里读到解析失败就下一轮再读，不要记错误日志刷屏。
 *
 * ⚠️ **信箱文件名与 bubble / ui 桥都不同**：三条通道各自从 `id=1` 自增，
 *   共用文件会让工具调用收到别人的回执。理由见 [BubbleCommandBridge.STATE_FILE]。
 *
 * ⚠️ 轮询间隔 250ms 与 bubble 桥一致 —— 这是给人按需改配色的通道，不值得为它做
 *   inotify。真机实测一次改动的往返（含此间隔）在 300ms 内。
 */
object SystemBarCommandBridge {

    private const val TAG = DshApplication.TAG

    /** 请求文件名。**必须与 `mobile-ui-plugin/dsh-android-statusbar/lib/index.js` 一致。** */
    private const val CMD_FILE = "statusbar-cmd.json"

    /** 回执文件名。同上，两边必须一起改。 */
    private const val RESULT_FILE = "statusbar-result.json"

    /** 轮询间隔。见类注释里为什么是粗粒度。 */
    private const val POLL_MS = 250L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-statusbar-cmd").apply { isDaemon = true }
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
        Log.i(TAG, "系统栏命令信箱已启动: ${File(dshHome, CMD_FILE).absolutePath}")
    }

    /** 停掉轮询（给测试/重启留一个干净的入口）。 */
    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    /**
     * 读一次信箱。跑在 `dsh-statusbar-cmd` 线程上。
     *
     * ⚠️ **不能在这里碰 View**：[SystemBarAppearance.apply] 内部会经弱引用回到
     *   Activity，而 `MainActivity.onSystemBarConfigChanged()` 自己切主线程。
     *   配置的落盘与读取只用 application context。
     */
    /**
     * 轮询一次系统栏命令信箱。
     *
     * ⚠️ **整个方法体必须被 try/catch 包住，不能有异常漏出去。**
     *
     * 它由 `scheduler.scheduleWithFixedDelay(::pollOnce, …)` 驱动，而
     * `ScheduledExecutorService` 的语义是：**周期任务一旦抛出未捕获异常，
     * 该任务就被永久终止** —— 后续周期不再执行，也没有任何通知。
     *
     * 于是症状是：这个信箱在某个瞬间之后再无响应，而日志里只有崩溃那一刻
     * 的一行堆栈，之后完全静默。用户看到的是「系统栏命令突然不管用了」，
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
            Log.w(TAG, "系统栏命令信箱轮询异常（已兜住，下个周期继续）: ${t.message}", t)
        }
    }

    private fun pollOnceInner() {
        val dir = home ?: return
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
            Log.w(TAG, "系统栏命令缺少 id，已丢弃")
            cmd.delete()
            return
        }

        val action = json.optString("action", "set")
        val ok: Boolean
        val message: String
        var appearance = ""
        try {
            // ⚠️ 用 Application 单例而不是存一份 context：本桥的线程活到进程结束，
            //   存 Activity 会泄漏、存 application context 只是多一处状态。
            //   DshApplication 是 lateinit，但轮询启动点在 EngineManager 里，
            //   那时 Application.onCreate 早已跑完，访问是安全的。
            val context = DshApplication.instance
            if (action == "get") {
                appearance = SystemBarAppearance.currentJson(context)
                ok = true
                message = ""
            } else if (action == "reset") {
                SystemBarAppearance.reset(context)
                appearance = SystemBarAppearance.currentJson(context)
                ok = true
                message = ""
            } else if (action == "stopflash") {
                // 只停闪、保留钦定色：与 reset 是两种不同意图，见 SystemBarAppearance.stopFlash。
                SystemBarAppearance.stopFlash(context)
                appearance = SystemBarAppearance.currentJson(context)
                ok = true
                message = ""
            } else {
                // 合并语义在 SystemBarAppearance.merge 内部（它会把字段并到当前配置上），
                // 所以这里只管把 fields 序列化过去。
                val fields = json.optJSONObject("fields") ?: JSONObject()
                SystemBarAppearance.apply(context, fields.toString())
                appearance = SystemBarAppearance.currentJson(context)
                ok = true
                message = ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "执行系统栏命令失败: ${t.message}")
            writeResult(dir, id, false, "原生侧执行失败: ${t.message}", "")
            cmd.delete()
            return
        }

        // ②：先写回执，再删请求。反过来的话插件会超时。
        writeResult(dir, id, ok, message, appearance)
        cmd.delete()
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
            Log.w(TAG, "写入系统栏命令回执失败: ${t.message}")
        }
    }
}
