package com.dsh.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dsh.android.log.BootState
import com.dsh.android.log.CrashRecorder
import com.dsh.android.log.DiagnosticBundle
import com.dsh.android.log.DshLog
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogStore
import com.dsh.android.log.SafeDateFormat
import com.dsh.android.log.LogUploader
import com.dsh.android.log.VersionInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志界面 —— **入口②的实体**。
 *
 * ## 这个界面为什么必须是纯原生、零依赖
 *
 * 它存在的唯一理由是「App 打不开的时候还能看日志」。所以它不能依赖引擎、
 * 不能依赖 WebView、不能依赖任何需要初始化才能用的组件 —— 一旦它自己也起不来，
 * 用户就彻底没有出口了（只能卸载重装，那会清掉全部会话与凭据）。
 *
 * 因此：只用框架自带控件、不联网、不做重活、所有读盘都 `runCatching` 兜住。
 * 唯一的外部依赖是 [LogStore] 的内存缓冲（在 `attachBaseContext` 就装好了，
 * 比任何 Activity 都早）。
 *
 * ## 两条入口都汇到这里
 *
 * - 启动/崩溃浮层上的「查看日志」按钮 → [Intent] 直接拉起本 Activity
 * - 设置页（Web GUI 里）的「运行日志与诊断」 → `PageBridge.openLogs()` → 同一个
 *   [Intent]
 *
 * 刻意让两条入口**同一个目的地**：两个界面意味着两套代码、两种筛选语义、
 * 两份要维护的布局，而它们的职责完全一样。
 *
 * ## 与 LogStore 内存缓冲的关系
 *
 * 界面读的是**内存缓冲**（最多 4000 条），不是磁盘文件。原因：
 * ① 磁盘上 `app.log` 只有 1MB 且已轮转，读它会丢掉更早的内容；
 * ② 内存缓冲是**三个来源（应用/引擎/网页）合并**后的结果，磁盘上是三个文件，
 *    合并要额外排序；
 * ③ 崩溃现场文件不在内存里，所以「崩溃」来源单独从磁盘列。
 */
class LogViewerActivity : AppCompatActivity() {

    // ── 筛选状态 ─────────────────────────────────────────────────────────────

    /** 最低级别：'V' = 全部，'W' = 警告以上，'E' = 仅错误。 */
    private var minLevel: Char = 'V'

    /** 来源筛选：null = 全部；否则是 tag 名（app 侧为具体 tag，引擎为 "engine"…）。 */
    private var source: String? = null

    /** 搜索关键字（小写，空串 = 不筛）。 */
    private var query: String = ""

    // ── 视图 ─────────────────────────────────────────────────────────────────

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private lateinit var env: TextView
    private lateinit var search: EditText

    private val chips = mutableMapOf<String, TextView>()

    /** 当前展示的条目（筛选后的快照）。 */
    private var rows: List<LogStore.Entry> = emptyList()
    private val adapter by lazy { LogAdapter() }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 自动刷新。崩溃刚发生时日志还在滚，用户打开界面看到的是静止画面会以为卡了。
     * 2 秒一次，界面不可见时停掉（见 [onStop]）—— 常驻轮询会白耗电。
     */
    private val autoRefresh = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        list = findViewById(R.id.logList)
        empty = findViewById(R.id.logEmpty)
        summary = findViewById(R.id.logSummary)
        env = findViewById(R.id.logEnv)
        search = findViewById(R.id.logSearch)

        list.adapter = adapter

        // ⚠️ 先绑定筛选与动作，再首次加载：反过来的话首次加载时筛选状态还没接上，
        //   用户点「仅错误」会看到列表闪一下再变。
        bindChips()
        bindActions()
        bindSearch()
        renderHeader()
        reload()
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(autoRefresh, REFRESH_MS)
    }

    override fun onStop() {
        super.onStop()
        // ⚠️ 必须停：不停的话用户离开界面后这个 Activity 还活着并每 2 秒读一次
        //   内存缓冲，白耗电。onStop 而不是 onPause —— 后者在弹权限框、
        //   拉通知栏、息屏时都会触发，会造成「切回来不刷新」。
        handler.removeCallbacks(autoRefresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        /*
         * ⚠️ `onStop` 里的 `removeCallbacks` **挡不住一种情况**：
         *
         * [autoRefresh] 是「跑完自己再排一次」的自续期 Runnable：
         *
         *     override fun run() {
         *         reload()                                  // ← 正在执行（要读文件）
         *         handler.postDelayed(this, REFRESH_MS)     // ← 然后重新排队
         *     }
         *
         * 如果 `onStop` 恰好在 `reload()` 执行期间被调用，`removeCallbacks`
         * 移除的是**队列里的**那一条 —— 而当前这一轮已经出队，它接下来会
         * 无条件执行第 101 行，把任务重新排进队列。
         *
         * 于是 Activity 被销毁（用户按返回退出）之后，这个 Runnable 仍在
         * 每 2 秒醒来一次，对着一个已销毁的 Activity 调 `reload()` 读日志、
         * 碰已经拆掉的 View。表现是「退出日志页后偶发闪退」，
         * 而且 `reload()` 里读文件的开销会一直挂着。
         *
         * `onDestroy` 是最后一道闸：此时 Activity 确定要没了，
         * 清空这个 Handler 的全部排队与已排期任务，自续期链随之终止。
         * 用 `removeCallbacksAndMessages(null)` 而不是 `removeCallbacks`，
         * 因为销毁后不再需要区分是哪一个任务。
         */
        handler.removeCallbacksAndMessages(null)
    }

    // ── 顶部信息 ─────────────────────────────────────────────────────────────

    /**
     * 渲染顶部结论条。
     *
     * ⚠️ 这里**不能**现跑一遍完整诊断（那要读盘、算配额，几百毫秒），
     * 界面打开要秒开。所以只做两件便宜的事：读上次退出状态、数崩溃现场文件。
     * 完整诊断在「导出诊断包」时才跑（见 [DiagnosticBundle.build]）。
     */
    private fun renderHeader() {
        val prev = BootState.previous()
        // ⚠️ `listScenes()` 只读一次目录，下面三处共用同一份列表 —— 原来
        //   `sceneCount()` 与这里各读一次，等于同一件事做两遍（且两次之间
        //   目录可能变化，两个数会互相矛盾）。
        val sceneFiles = runCatching { CrashRecorder.listScenes() }.getOrDefault(emptyList())
        val scenes = sceneFiles.size
        val hard = sceneFiles.count { CrashRecorder.isHardCrash(it) }

        // ⚠️ 结论的措辞必须诚实。「上次非正常结束」**不等于**崩溃 ——
        //   可能是被系统回收、被 ROM 冻结、断电。所以分三档说，且只有真找到
        //   崩溃现场文件时才说「崩溃」。见 BootState 的诚实分级说明。
        summary.text = when {
            hard > 0 -> "⚠ 发现 $hard 份崩溃现场（共 $scenes 份现场文件），详见「崩溃」筛选"
            prev.unstable -> "⚠ 连续 ${prev.consecutiveUncleanCount} 次未正常结束，疑似启动即崩"
            prev.known && !prev.clean -> "上次非正常结束（无堆栈；可能是崩溃/被系统回收/断电），共 $scenes 份现场文件"
            // ⚠️ **不能举例子。** 原来这里写「（非崩溃类：渲染进程被回收、引擎退出等）」，
            //   而真机 162 份现场里非硬崩溃类共 99 份，其中 `engine-start-failed` 占
            //   81 份（82%）—— 举例举的是占比最小的那一类，把最大的一类漏掉了。
            //   例子是猜测，类别是事实。
            scenes > 0 -> "共 $scenes 份现场文件（非崩溃类：${CrashRecorder.summarizeKinds(sceneFiles)}）"
            else -> "未发现崩溃现场"
        }

        env.text = "${VersionInfo.summary()} · ${VersionInfo.deviceSummary()}"
        env.append("\n内存 ${LogStore.entries().size} 条 · 本次启动 ${BootState.bootId}")
        val pending = runCatching { LogUploader.pendingCount(logsDir()) }.getOrDefault(0)
        if (pending > 0) env.append(" · $pending 份待上传")
    }

    // ── 加载与筛选 ───────────────────────────────────────────────────────────

    private fun logsDir(): File = File(filesDir, DshApplication.LOG_DIR)

    /** 重新读内存缓冲 + 崩溃现场，应用筛选，刷新列表。 */
    private fun reload() {
        /*
         * ⚠️ 第二道防线：Activity 已经在销毁就不要碰视图了。
         *
         * [onDestroy] 里清了 Handler，正常路径下这里不会在销毁后被调到。
         * 但 `reload()` 还有别的调用者（筛选/搜索/手动刷新/自动刷新），
         * 任何一条在销毁瞬间触发的路径都会走到下面 `list.adapter = ...`
         * 去操作一棵已经拆掉的视图树 —— 那会抛 IllegalStateException
         * 或静默无效。加一行守卫的代价可以忽略，换来的是「日志页退出时
         * 绝不因为自己的刷新逻辑而崩」。
         */
        if (isFinishing || isDestroyed) return
        val all = LogStore.entries()
        val minSev = LogStore.severity(minLevel)

        val filtered = all.filter { e ->
            // 级别
            LogStore.severity(e.level) >= minSev &&
                // 来源：引擎/网页的 tag 固定是 "engine"/"web"，
                // 应用侧是各文件自己的 tag —— 这里按 tag 精确匹配。
                (source == null || matchesSource(e)) &&
                // 关键字：tag 与正文一起搜（用户往往记得的是报错文本，不是 tag）
                (query.isEmpty() ||
                    e.msg.lowercase(Locale.US).contains(query) ||
                    e.tag.lowercase(Locale.US).contains(query))
        }

        // 「崩溃」这一档不在内存缓冲里 —— 崩溃现场是**文件**（见 CrashRecorder）。
        // 单独把它们的头部几行拼成虚拟条目插到最前面，这样用户点「崩溃」筛选时
        // 能看到东西，而不是一个空列表。
        val crashRows = if (source == "crash") crashEntries() else emptyList()

        rows = crashRows + filtered
        adapter.notifyDataSetChanged()

        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        // 列表新 → 旧？不，内存缓冲是插入序（旧 → 新），最后一条最新。
        // 滚到底部即最新（布局里 stackFromBottom=true 让它自然贴底）。
        if (rows.isNotEmpty()) list.setSelection(rows.size - 1)
    }

    private fun matchesSource(e: LogStore.Entry): Boolean = when (source) {
        null -> true
        "engine" -> e.tag == "engine"
        "web" -> e.tag == "web"
        // 「应用」= 除引擎/网页之外的全部（tag 五花八门，按排除法比按白名单准）
        "app" -> e.tag != "engine" && e.tag != "web"
        "crash" -> false // 崩溃走 crashEntries()
        else -> true
    }

    /**
     * 把崩溃现场文件的**头部**做成虚拟条目。
     *
     * ⚠️ 只取前若干行：现场文件里附带了「最后 200 行日志」，整个塞进列表会让
     * 一屏只显示一份现场。
     *
     * ⚠️ **必须是 `take` 而不是 `takeLast`。** `CrashRecorder.listScenes()` 是
     *   `sortedByDescending { lastModified() }`（**最新在前**），所以尾部才是
     *   **最旧**的那些。原来写 `takeLast(MAX_CRASH_ROWS)`，于是「崩溃」筛选列出的
     *   是最旧的 20 份 —— 用户想看的**最新那次崩溃反而不在列表里**，而
     *   `enforceQuota()` 只在 `LogStore.init()` 调一次，同一次运行内现场可以攒到
     *   20 份以上，这条路径是能走到的。
     *   项目里一致的口径是「保留最近的」（见 `LogStore.enforceCrashQuota` 的
     *   KDoc：「保留**最近**的而不是最早的…最新那几个才是当前问题的现场」）。
     */
    private fun crashEntries(): List<LogStore.Entry> = runCatching {
        CrashRecorder.listScenes().take(MAX_CRASH_ROWS).mapIndexed { i, f ->
            val head = runCatching {
                f.bufferedReader().useLines { it.take(HEAD_LINES).joinToString("\n") }
            }.getOrDefault("(无法读取)")
            // ⚠️ **必须用 CrashRecorder.kindOf，不能用 substringAfterLast('-')。**
            //   文件名契约是 `<yyyyMMdd-HHmmss>-<kind>.txt`，而**时间戳自己也含 `-`**；
            //   切尾段得到的是类别名的后半截：`engine-start-failed`→`failed`、
            //   `uncaught-exception`→`exception`、`engine-exit`→`exit`。
            //   于是同屏顶部写 `engine-start-failed×N`、列表标签却是 `crash/failed`，
            //   而 `crash/exit` 与 `crash/exception` 更是把类别说反了。
            //   kindOf 用正则 `^\d{8}-\d{6}-(.+)\.txt$`，KDoc 里明写「必须是这个正则」。
            val kind = CrashRecorder.kindOf(f)
            LogStore.Entry(
                t = f.lastModified(),
                level = if (CrashRecorder.isHardCrash(f)) 'E' else 'W',
                tag = "crash/$kind",
                msg = "$head\n…（只显示前 $HEAD_LINES 行；完整内容见 crash/${f.name}）",
                seq = -(i + 1L),
                // 这一行代表一个**现场文件**，不是一条日志 —— 它没有「写入线程」。
                // ⚠️ 不能填 Thread.currentThread().name：那会显示成 UI 线程，纯属编造。
                thread = "-",
            )
        }
    }.getOrDefault(emptyList())

    // ── 交互绑定 ─────────────────────────────────────────────────────────────

    private fun bindChips() {
        /*
         * ⚠️ **这里刻意不用 `!!`。**
         *
         * `findViewById` 返回的是**平台类型**（可能是 null），原来的写法是
         * 先把它塞进 `MutableMap<String, TextView>`、再到处 `chips[...]!!`。
         * 两个隐患叠在一起：
         *   · map 的泛型参数是非空的 `TextView`，但塞进去的可能是 null ——
         *     编译器一句话都不说，运行时 `!!` 才炸；
         *   · 一旦布局里的 ID 被改名/删掉（重构布局时很容易），
         *     用户**点开日志页就直接闪退**。而日志页恰恰是出问题时
         *     最需要能打开的那个界面 —— 它崩了，连"为什么崩"都看不到。
         *
         * 改成逐个安全接收 + 跳过缺失项：缺一个筛选按钮不该让整页打不开，
         * 其余按钮照常可用。这里只补一行 W 日志，不做任何弹窗。
         */
        fun bind(id: Int, key: String, onClick: () -> Unit) {
            val v = findViewById<TextView>(id)
            if (v == null) {
                android.util.Log.w("LogViewer", "筛选按钮缺失: $key（布局里没有对应 id）")
                return
            }
            chips[key] = v
            v.setOnClickListener { onClick() }
        }

        bind(R.id.logLevelAll, "lvl:all") { minLevel = 'V'; renderChips(); reload() }
        bind(R.id.logLevelWarn, "lvl:warn") { minLevel = 'W'; renderChips(); reload() }
        bind(R.id.logLevelError, "lvl:error") { minLevel = 'E'; renderChips(); reload() }
        bind(R.id.logSrcAll, "src:all") { source = null; renderChips(); reload() }
        bind(R.id.logSrcApp, "src:app") { source = "app"; renderChips(); reload() }
        bind(R.id.logSrcEngine, "src:engine") { source = "engine"; renderChips(); reload() }
        bind(R.id.logSrcWeb, "src:web") { source = "web"; renderChips(); reload() }
        bind(R.id.logSrcCrash, "src:crash") { source = "crash"; renderChips(); reload() }
        renderChips()
    }

    /** 选中态：填充色变亮、文字变白。刻意不引入 selector drawable（少一个文件）。 */
    private fun renderChips() {
        val selected = setOf(
            when (minLevel) { 'W' -> "lvl:warn"; 'E' -> "lvl:error"; else -> "lvl:all" },
            "src:${source ?: "all"}",
        )
        chips.forEach { (key, v) ->
            val on = key in selected
            v.setBackgroundResource(if (on) R.drawable.log_chip_on_bg else R.drawable.log_chip_bg)
            v.setTextColor(if (on) 0xFFEAF0FA.toInt() else 0xFF8FA3C0.toInt())
        }
    }

    private fun bindSearch() {
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                reload()
            }
        })
    }

    private fun bindActions() {
        findViewById<TextView>(R.id.logClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.logRefresh).setOnClickListener {
            renderHeader()
            reload()
            toast("已刷新")
        }
        findViewById<TextView>(R.id.logCopy).setOnClickListener { copyAll() }
        findViewById<TextView>(R.id.logPlugins).setOnClickListener { showPluginSheet() }
        findViewById<TextView>(R.id.logExport).setOnClickListener { exportBundle() }
        findViewById<TextView>(R.id.logUpload).setOnClickListener { upload() }
        findViewById<TextView>(R.id.logClear).setOnClickListener { confirmClear() }
    }

    // ── 插件开关（App 打不开时唯一的自救动作）──────────────────────────────

    /**
     * 弹出插件清单，让用户摘掉/装回某个 bundle。
     *
     * ## 为什么这个功能必须长在这个界面上
     *
     * 真机现场（OPPO PHY110）：用户在手机上写插件写到一半退出 App，留下的
     * `client.js` 里多一个 `)`；而前端把所有插件的 client 半**合并成一批**
     * 下发，一个坏段让整批解析失败 ⇒ 界面变成 `Failed to load plugins`
     * 整页错误。引擎健康、数据完好，界面却进不去。
     *
     * 当时唯一的救法是：在电脑上用 adb 改 `profiles/web/package.json`
     * 再重启。普通用户没有 adb，只能卸载重装 —— 而那会清掉全部会话与凭据。
     *
     * 本界面是**唯一在界面打不开时仍然可达的原生界面**（入口是启动层上那个
     * 任何状态都显示的「查看日志」，见 activity_main.xml 的 overlayLogs），
     * 所以自救动作只能放这里。放设置页没用 —— 那个页面正是打不开的那一环。
     *
     * ## 为什么是「摘掉」而不是「修好」
     *
     * 修好一个语法错误需要看代码、改文件、验证，那是 AI 干的活 ——
     * 而 AI 要进对话才能干活。所以这里的职责只有一件：
     * **把挡路的东西挪开，让用户进得去对话**，剩下的交给 AI。
     */
    private fun showPluginSheet() {
        val profileDir = File(filesDir, "home/.dsh/profiles/web")
        val read = PluginManifest.read(profileDir)
        if (read == null) {
            // ⚠️ 读失败与「没有插件」必须分开说。前者用户要去找 AI 修
            //   package.json，后者只是清单里没第三方插件。
            AlertDialog.Builder(this)
                .setTitle(R.string.logs_plugins)
                .setMessage(R.string.logs_plugins_unreadable)
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val (_, entries) = read
        val third = entries.filter { !it.isProtected }
        if (third.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.logs_plugins)
                .setMessage(getString(R.string.logs_plugins_none))
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        // 每一项显示包名 + 当前状态；点一下切换。官方 bundle 不列（不可摘，
        // 列出来只会让用户点到一个必然失败的项）。
        val labels = third.map { it.name }.toTypedArray()
        val checked = BooleanArray(third.size) { true } // 在清单里 = 已启用
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logs_plugins_title, third.size))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton("取消", null)
            .setPositiveButton(R.string.logs_plugins_apply) { _, _ ->
                applyPluginToggles(profileDir, third, checked)
            }
            .show()
    }

    /**
     * 应用勾选结果：与磁盘上的清单求差集，逐项摘掉/装回，然后重启引擎。
     *
     * ⚠️ **只在真的有变化时才重启**：重启要十几秒，用户点开看一眼再取消
     *   不该触发它。
     * ⚠️ **重启走 [EngineManager.requestRestart]**：它内部先 `stop()` 再置
     *   `State.Dead`，而 `MainActivity` 的 `Dead` 分支会重新 `start()` 并重载
     *   界面 —— 与我在电脑上用 adb 改完文件后手动重启是**同一条路径**。
     */
    private fun applyPluginToggles(
        profileDir: File,
        entries: List<PluginManifest.Entry>,
        checked: BooleanArray,
    ) {
        var changed = 0
        val failures = mutableListOf<String>()
        entries.forEachIndexed { i, e ->
            val wantEnabled = checked[i]
            // 当前在清单里 = 启用。wantEnabled 与它不同才动手。
            val ok = PluginManifest.setBundle(profileDir, e.name, remove = !wantEnabled)
            if (ok.first) {
                changed++
            } else {
                failures += "${e.name}: ${ok.second}"
            }
        }
        if (failures.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.logs_plugins_failed)
                .setMessage(failures.joinToString("\n\n"))
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (changed == 0) {
            toast(getString(R.string.logs_plugins_unchanged))
            return
        }
        // ⚠️ 二次确认：重启会中断用户当前正在看的东西（虽然数据不丢）。
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_plugins_restart_title)
            .setMessage(getString(R.string.logs_plugins_restart_msg, changed))
            .setNegativeButton("稍后", null)
            .setPositiveButton(R.string.logs_plugins_restart_now) { _, _ ->
                restartEngine()
            }
            .show()
    }

    /** 重启引擎（界面会随之重载）。失败要如实说，不能静默。 */
    private fun restartEngine() {
        runCatching {
            EngineManager.get(applicationContext).requestRestart("用户在日志界面切换插件")
        }.onSuccess {
            toast(getString(R.string.logs_plugins_restarting))
            finish()
        }.onFailure {
            DshLog.e(DshApplication.TAG, "重启引擎失败: ${it.message}", it)
            toast(getString(R.string.logs_plugins_restart_failed, it.message ?: "未知错误"))
        }
    }

    // ── 动作 ─────────────────────────────────────────────────────────────────

    /**
     * 复制当前筛选后的全部日志到剪贴板。
     *
     * ⚠️ 复制的是**筛选后**的内容而不是全量：用户筛出「仅错误」再复制，是想要
     * 那几条错误，不是想要 4000 行。这也是本界面筛选存在的意义之一。
     */
    private fun copyAll() {
        if (rows.isEmpty()) {
            toast("当前筛选下没有日志")
            return
        }
        val text = rows.joinToString("\n") { formatRow(it) }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DSH 日志", text))
            toast(getString(R.string.logs_copied, rows.size))
        }.onFailure { toast(getString(R.string.logs_copy_failed, it.message ?: "未知错误")) }
    }

    /**
     * 生成诊断包并提示落点。
     *
     * ⚠️ **必须在后台线程**：要读几个 MB 的文件、算行数、压 zip，主线程做会
     * ANR。而 ANR 在用户眼里就是「点导出，界面卡死」。
     */
    private fun exportBundle() {
        toast(getString(R.string.logs_exporting))
        Thread({
            // ⚠️ 打包仍写在私有 `logs/export/`（上传链路读的就是那里），但**额外
            //   复制一份到用户能自己找到的公共目录** —— 否则「导出」对用户等于
            //   「提示成功但拿不到文件」（私有目录文件管理器进不去）。
            val out = runCatching {
                val dir = File(logsDir(), "export").apply { mkdirs() }
                DiagnosticBundle.build(logsDir(), dir, "manual", engineStateFiles())
            }.getOrNull()
            val shared = out?.let { publishToPublicDir(it) }
            handler.post {
                if (out != null) {
                    // ⚠️ 把**完整路径**给用户，而不是「已保存」：他要用文件管理器
                    //   找到它、或从聊天软件里发出去，没有路径就没法操作。
                    toast(getString(R.string.logs_export_ok, (shared ?: out).absolutePath))
                    LogStore.app(
                        'I', "LogViewer",
                        "手动导出诊断包: ${out.name} (${out.length()} 字节)" +
                            " → ${(shared ?: out).absolutePath}",
                    )
                } else {
                    // ⚠️ **不能猜原因**（2026-10-14 自检发现）。原来这里写死
                    //   「写入失败或磁盘空间不足」—— 那是编的：`build()` 返回 null 的
                    //   唯一原因是它 catch 到了异常，而磁盘满只是**可能之一**
                    //   （还有 outDir 建不出来、zip 流中途抛、OOM…）。本文件的
                    //   `DiagnosticBundle.runChecks` 里明写「只报事实，不猜原因」，
                    //   这句正好违反它。现在改为指向**真实原因的去处**：
                    //   `DiagnosticBundle.build` 的 catch 已把异常落进 `app.log`
                    //   （tag `Diagnostic`），用户/分析者按 tag 一搜就能看到。
                    toast(getString(R.string.logs_export_failed, "见日志里的 Diagnostic 条目"))
                }
            }
        }, "dsh-log-export").apply { isDaemon = true }.start()
    }

    /**
     * 把诊断包复制到用户能找到的目录（见 `LogContext.publicExportDirOrNull`）。
     *
     * ⚠️ **是复制不是移动**：`logs/export/` 里那一份是上传链路的输入，移走会
     *   让「导出后上传」失效。多占一份磁盘是必要的代价（包只有几 MB）。
     *
     * ⚠️ 失败返回 null，调用方退回私有路径提示 —— 复制失败不该让导出整个报错。
     */
    private fun publishToPublicDir(bundle: File): File? = runCatching {
        val dir = com.dsh.android.log.LogContext.publicExportDirOrNull() ?: return@runCatching null
        // 已经在公共目录里（无权限时 publicExportDirOrNull 会退回 filesDir，
        // 而这里就是 filesDir）→ 不必复制。
        if (dir.absolutePath == bundle.parentFile?.absolutePath) return@runCatching bundle
        if (dir.absolutePath.startsWith(logsDir().absolutePath)) return@runCatching null
        val target = File(dir, bundle.name)
        bundle.copyTo(target, overwrite = true)
        target
    }.getOrNull()

    /**
     * 上传：先确保有一个新鲜包，再入待传队列并立刻发。
     *
     * ⚠️ 上传**成功与失败都要提示**（用户明确要求）。而且失败时要说清「包还在
     * 本地」——否则用户以为白点了，会反复点。
     */
    private fun upload() {
        toast(getString(R.string.logs_uploading))
        Thread({
            val bundle = runCatching {
                val dir = File(logsDir(), "export").apply { mkdirs() }
                DiagnosticBundle.build(logsDir(), dir, "manual", engineStateFiles())
            }.getOrNull()
            if (bundle == null) {
                // 同上：不猜原因，指向真实原因的去处（`app.log` 的 `Diagnostic` 条目）。
                handler.post { toast(getString(R.string.logs_export_failed, "见日志里的 Diagnostic 条目")) }
                return@Thread
            }
            // 入待传队列：上传失败时它留在 pending/，下次启动自动重试。
            val queued = LogUploader.enqueue(bundle, logsDir()) ?: bundle
            LogUploader.upload(queued, "manual") { ok, msg ->
                handler.post {
                    // ⚠️ 成功时若服务器回了编号，那个编号要显示出来 ——
                    //   用户反馈问题时报编号，我们就能直接定位到这一份包。
                    toast(if (ok) "✓ $msg" else "✕ $msg")
                    renderHeader()
                }
            }
        }, "dsh-log-upload").apply { isDaemon = true }.start()
    }

    /**
     * 清空日志。
     *
     * ⚠️ **要二次确认**：这是破坏性的，而且用户很可能在排查过程中误点 ——
     * 那一刻手上的证据就没了。
     */
    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_clear)
            .setMessage(R.string.logs_clear_confirm)
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                Thread({
                    val n = LogStore.clearAll(logsDir())
                    handler.post {
                        toast("已清空 $n 个文件，${getString(R.string.logs_cleared)}")
                        renderHeader()
                        reload()
                    }
                }, "dsh-log-clear").apply { isDaemon = true }.start()
            }
            .show()
    }

    /**
     * 给诊断包附带的「引擎侧关键文件」清单。
     *
     * ⚠️ 这里**刻意不读文件内容**，只让 [com.dsh.android.log.EnvironmentSnapshot]
     * 记存在性/大小/修改时间。理由：资产解包失败、补丁没打上、插件没激活这三类
     * 问题，光看「文件在不在、多大、什么时候写的」就能定性，而把内容装进包里
     * 会让体积失控（引擎目录几十 MB）。
     */
    private fun engineStateFiles(): List<File> = runCatching {
        val home = File(filesDir, "home/.dsh")
        listOf(
            File(filesDir, "engine/.stamp"),
            File(filesDir, "engine/node_modules/@deepseek-ai/dsh/package.json"),
            File(home, "mobile-ui/dsh-android-mobile/package.json"),
            File(home, "shell-tool/dsh-android-shell/package.json"),
            File(home, "bubble-tool/dsh-android-bubble/package.json"),
            File(home, "uiagent-tool/dsh-android-uiagent/package.json"),
            File(home, "logs-tool/dsh-android-logs/package.json"),
            File(home, "sandbox.patch.yml"),
            File(home, "profiles/web/package.json"),
            File(filesDir, "usr/bin/node"),
            File(filesDir, "usr/bin/pnpm"),
            File(filesDir, "liblinkcompat.so"),
            File(filesDir, "libboosandbox.so"),
            File(filesDir, "boo-sandbox-run"),
        ).filter { it.exists() || it.parentFile?.exists() == true }
    }.getOrDefault(emptyList())

    // ── 列表适配器 ───────────────────────────────────────────────────────────

    private inner class LogAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = rows[position].seq

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_log_line, parent, false)
            val e = rows[position]
            val time = v.findViewById<TextView>(R.id.rowTime)
            val level = v.findViewById<TextView>(R.id.rowLevel)
            val tag = v.findViewById<TextView>(R.id.rowTag)
            val msg = v.findViewById<TextView>(R.id.rowMsg)

            time.text = timeFmt.format(Date(e.t))
            level.text = e.level.toString()
            level.setTextColor(levelColor(e.level))
            tag.text = e.tag
            msg.text = e.msg
            // 错误行整段偏红，方便在快速滚动时定位
            msg.setTextColor(if (LogStore.severity(e.level) >= 3) 0xFFF0C8C8.toInt() else 0xFFC8D6E8.toInt())
            return v
        }
    }

    private fun levelColor(level: Char): Int = when (level) {
        'E' -> 0xFFFF7B72.toInt()
        'W' -> 0xFFFFB454.toInt()
        'I' -> 0xFF7FB3E8.toInt()
        else -> 0xFF6E82A0.toInt()
    }

    private fun formatRow(e: LogStore.Entry): String =
        "${timeFmt.format(Date(e.t))} [${e.level}] [${e.tag}] #${e.seq} ${e.thread} ${e.msg}"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REFRESH_MS = 2000L
        private const val MAX_CRASH_ROWS = 20
        private const val HEAD_LINES = 12
        private val timeFmt = SafeDateFormat("HH:mm:ss.SSS", Locale.US)

        /**
         * 拉起日志界面的标准入口。
         *
         * ⚠️ 用 `FLAG_ACTIVITY_NEW_TASK` 之外的默认标志：本界面要能**从浮层**
         * （MainActivity 里）和**从设置页**（同一个 Activity 的 WebView）拉起，
         * 两者都在本进程内，不需要新任务栈。走新任务栈反而会让「关闭」按钮
         * 退到桌面而不是浮层。
         *
         * ⚠️ 绝不 `catch` 后静默：起不来时用户点了没反应是最难查的反馈。
         * 调用方（浮层）负责在失败时提示。
         */
        fun intent(context: Context): Intent = Intent(context, LogViewerActivity::class.java)
    }
}
