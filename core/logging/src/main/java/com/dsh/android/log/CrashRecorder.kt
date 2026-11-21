package com.dsh.android.log

import android.util.Log as AndroidLog
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * 记录未捕获异常、渲染进程退出和引擎异常等现场。
 *
 * ## 为什么必须自己抓
 *
 * 本 App 的进程**很容易非正常消失**：内置引擎吃内存（node + 一个 WebView/GV 内核），
 * 国产 ROM 又会主动冻结/杀后台。而用户报上来的永远是同一句话：「它自己退了」。
 * 没有现场文件，那句话之外什么都查不了。
 *
 * ## 分别抓，因为「谁死了」决定了「怎么查」
 *
 * 现场文件统一命名 `yyyyMMdd-HHmmss-<kind>.txt`，常用类别如下：
 *
 * | 类型 | 捕获点 | `<kind>` | 等级 | 硬崩溃？ |
 * |---|---|---|---|---|
 * | 未捕获异常 | `Thread.setDefaultUncaughtExceptionHandler` | `uncaught-exception` | E | 是 |
 * | 渲染进程崩溃 | `WebHostCallbacks.onRendererGone`（`didCrash = true`） | `renderer-crash` | E | 是 |
 * | 渲染进程被回收 | 同上（`didCrash = false`） | `renderer-reclaimed` | W | 否 |
 * | 引擎进程意外退出 | `EngineManager` 的 stdout EOF | `engine-exit` | W | 否 |
 * | 引擎进程被我们停掉 | 同上（`expected = true`） | `engine-exit-expected` | I | 否 |
 * | 引擎起不来 | `EngineManager` 启动超时 | `engine-start-failed` | E | 否 |
 *
 * ⚠️ **不能合并成一种**：WebView 渲染进程崩溃时**本进程还活着**，能继续跑、
 * 能弹提示、能重载（`MainActivity` 已有这套恢复逻辑）。把它记成「App 崩溃」会
 * 让分析者去查一个根本不存在的进程崩溃。
 *
 * ## 每份现场固定包含四段（缺一段都会让排查卡住）
 *
 * 1. **元信息** —— 时间、类型、`bootId`、设备、App 版本、线程名
 * 2. **异常/原因** —— 有堆栈就写堆栈
 * 3. **最后 [LogStore.CRASH_TAIL_LINES] 行日志** —— ⚠️ 这是**最关键的一段**：
 *    文件日志可能因异步缓冲丢了最后几条，而内存环形缓冲正好补上。真机上
 *    「崩之前最后做的那件事」永远比堆栈本身更有信息量
 * 4. **正在跑的阶段** —— 从 [BootState] / [EventLog] 取，回答「崩在哪一步」
 *
 * ## 三条必须守住的实现约束
 *
 * ① **`tryLock` 而不是 `lock`。** 崩溃可能发生在**正持有日志锁**的线程上
 *    （比如就在 `LogStore.writeApp` 里抛了异常）。硬等锁会死锁 —— 而崩溃处理器
 *    死锁的后果是把「闪退」变成「永久卡死」，**比崩溃更糟**。拿不到锁就只写
 *    现场文件（那条路径不碰 `app.log` 的锁）。
 * ② **写现场用独立文件、独立句柄。** 不复用 `app.log` 的 writer —— 它可能正被
 *    死掉的线程持有。
 * ③ **处理完必须把异常交回默认处理器。** 不交回的话进程会「吞掉」崩溃并卡在
 *    半死状态（ANR 而不是退出），用户体验从「闪退」变成「卡住」——更糟。
 */
object CrashRecorder {

    private const val TAG = "DSH"

    private val fmt = SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 文件名时间戳。不能含冒号（文件系统不允许）。 */
    private val fileFmt = SafeDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 现场文件名：`yyyyMMdd-HHmmss-<kind>.txt`（见 [writeScene] 的命名）。 */
    private val sceneNamePattern = Regex("^\\d{8}-\\d{6}-(.+)\\.txt$")

    /**
     * 拿日志锁的最长等待。见类注释约束①。
     *
     * 200ms 的取值依据：写一条日志是微秒级，200ms 足够任何正常持有的锁释放；
     * 而崩溃路径总共只有几秒预算（之后进程就没了），不能把预算耗在等锁上。
     */
    private const val LOCK_WAIT_MS = 200L

    /**
     * 系统 Web 内核抛出的未捕获异常，在 `reason` 前加的归因前缀。
     *
     * 见 [isWebViewKernelCrash]。写在 reason 里而不是另立 kind，是为了不动
     * `isHardCrash` / `crash-scenes` 那套判据（内核崩了，界面确实没了，仍算硬崩溃）。
     */
    private const val WEBVIEW_KERNEL_NOTE = "【系统 Web 内核崩溃，非本 App】"

    @Volatile
    private var crashDir: File? = null

    /** 未捕获异常处理器是否已装（幂等判据）。 */
    @Volatile
    private var handlerInstalled = false

    // ── 安装 ──────────────────────────────────────────────────────────────────

    /**
     * 装上未捕获异常处理器。
     *
     * ⚠️ 必须在 `attachBaseContext()` 里调用，**越早越好** —— 晚于第一次崩溃
     * 就等于没装。
     */
    fun install(logDir: File) {
        crashDir = File(logDir, "crash").apply { runCatching { mkdirs() } }
        installUncaughtHandler()
    }

    private fun installUncaughtHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val stack = AndroidLog.getStackTraceString(err)
                // ⚠️ 先分清「这个异常是谁抛的」。见 [isWebViewKernelCrash] ——
                //   不标出来的后果是把**系统内核的 bug**当成我们自己的 bug 去查。
                val reason = if (isWebViewKernelCrash(err, stack)) {
                    "$WEBVIEW_KERNEL_NOTE${err.javaClass.name}: ${err.message}"
                } else {
                    "${err.javaClass.name}: ${err.message}"
                }
                // ① 先落盘（同步）—— 顺序很重要：先写现场文件，再写 app.log。
                //    因为 app.log 那条可能因为拿不到锁而跳过，现场文件是主力。
                writeScene(
                    kind = "uncaught-exception",
                    reason = reason,
                    stack = stack,
                    threadName = thread.name,
                )
                // ② 再把堆栈喂给 app.log（拿得到锁才有）。这条让「崩溃」出现在
                //    正常的日志流里，用户按时间顺序翻日志时不会漏掉它。
                withLogLock {
                    // ⚠️ 只取 reason 的**首行**：完整栈紧接着由下一行写进 app.log，而
                    //   `UncaughtExceptionException` 的 message 里**已经含**整段 native 栈
                    //   （chromium 构造时就是 `"Native stack trace:" + 换行 + nativeStackTrace`），
                    //   内联进这一行会让同一条崩溃在 app.log 里出现两遍、且把这一行撑成几百行。
                    val head = reason.substringBefore('\n').trim()
                    LogStore.app('E', "Crash", "未捕获异常 on ${thread.name}: $head")
                    LogStore.app('E', "Crash", stack)
                }
                // ③ 刷盘。异步缓冲里还压着引擎/网页的最后几行。
                LogStore.flushAsync()
                EventLog.flushAndClose()
                LogStore.flushAndClose()
            } catch (_: Throwable) {
                // 崩溃处理器自己再崩就彻底没救了 —— 静默，交给默认处理器。
            }
            // 约束③：必须交回，否则从「闪退」变「卡住」。
            prev?.uncaughtException(thread, err)
        }
    }

    /**
     * 判断这个未捕获异常是不是**系统 Web 内核自己**抛的（不是本 App 的 bug）。
     *
     * ## 为什么必须区分
     *
     * 真机证据（62 个诊断包，3 份现场）：`JniAndroid$UncaughtExceptionException` 冒到
     * 默认处理器，被记成 `uncaught-exception` ⇒ `isHardCrash` 判真 ⇒ 诊断包的
     * `crash-scenes` 报 **fail**。可它的 native 栈 **14 帧全部**落在
     * `/my_product/app/TrichromeLibrary64CN/TrichromeLibrary64CN.apk`（OPPO 的系统内核），
     * Java 侧入口是 `ImeAdapterImpl.updateState` → `JniAndroid.handleException` ——
     * 也就是**输入法状态更新时内核自己 native 崩了**，与本工程一行代码无关。
     *
     * 后果是分析方向被带偏：`crash-scenes=fail` 会让人去查我们的 UI/输入处理，
     * 而真凶是 ROM 自带内核。所以**不改判据**（它确实崩了，如实记），
     * 只在 `reason` 前加一句归因，让读日志的人一眼看到「这不是我们的栈」。
     *
     * ## 判据为什么用两个特征
     *
     * - `JniAndroid$UncaughtExceptionException`：chromium 把 native 崩溃转成 Java
     *   异常的**唯一**载体类。
     * - `TrichromeLibrary` / `libmonochrome`：native 栈里的**内核库名** —— 这才是
     *   「崩在内核里」的直接证据。
     *
     * 两个都不出现才判为本 App 的异常 —— 宁可漏判也不能把自家崩溃误标成内核的。
     *
     * ⚠️ **不要再把 `stack.contains("JniAndroid.handleException")` 加回判据里。**
     *   曾经加过，它是**恒真**的：`UncaughtExceptionException` 正是由
     *   `JniAndroid.handleException` 构造的（chromium 源码里
     *   `new UncaughtExceptionException(nativeStackTrace, throwable)` 就在该方法体内），
     *   所以它必然出现在自己的栈里。恒真的条件会把整个判据短路成「只看类名」，
     *   而 chromium 的 `CheckException(env)` 是 **jni_zero 的通用 JNI 边界检查** ——
     *   native 调用**任何** Java 方法返回时若有未决异常都会触发，不限于内核自己崩。
     *   本工程这些回调都是被 WebView native 经 JNI 调用的（`onPageFinished`、
     *   `onStatus`、`onUiReady`、`bubbleApply` …），任一处抛未捕获异常就会被
     *   **误标成「系统 Web 内核崩溃，非本 App」** —— 与本节意图正好相反。
     *   要加特征就往「native 栈里出现内核库名」这个方向加。
     *
     * ⚠️ 与 [isHardCrash] 的关系：这里**只改文案不改等级**。内核崩了照样是
     * `E` 级硬崩溃现场（对用户而言界面确实没了），只是原因归属不同。
     */
    private fun isWebViewKernelCrash(err: Throwable, stack: String): Boolean {
        val name = err.javaClass.name
        if (!name.contains("JniAndroid")) return false
        // ⚠️ 只认 native 栈里的**内核库名**。见上面那条 ⚠️：类名单独不足以定性。
        return stack.contains("TrichromeLibrary") || stack.contains("libmonochrome")
    }

    // ── 现场写入入口 ────────────────────────────────────────────────────
    /**
     * 记录**渲染进程崩溃**（WebView 的 `onRenderProcessGone` / GeckoView 的 `onCrash`）。
     *
     * ⚠️ **本进程还活着**，所以这不是「App 崩溃」。区分 `didCrash` 很重要：
     * `false` 表示被系统回收（低内存时的正常行为），不该当成异常。
     */
    fun recordRendererGone(didCrash: Boolean, detail: String) {
        writeScene(
            kind = if (didCrash) "renderer-crash" else "renderer-reclaimed",
            reason = detail,
            stack = null,
            threadName = "ui",
            level = if (didCrash) 'E' else 'W',
        )
    }

    /** 记录**引擎进程退出**。`expected` = 是不是我们主动停的。 */
    /**
     * 记录引擎进程退出。
     *
     * ## ⚠️ `engineTail` 这个参数是 2026-10-27 真机审查补上的
     *
     * 在此之前这个方法**不接收引擎自己的输出**，于是现场文件里只有 App 侧日志
     * （`--- last 200 log lines ---` 来自 `LogStore.tail()`，那是我们自己的 app.log），
     * **引擎进程说过的话一个字都没有**。
     *
     * 真机证据（HUAWEI CDY-TN90，`20261026-183957-engine-exit.txt`）：
     * ```
     * reason: exitCode=null 进程已退出
     * --- last 200 log lines ---   ← 全是 mdns / 固定端口守护的 App 日志
     * --- recent events ---        ← engine.exit code=134 …
     * ```
     * 只有 `recent events` 里那一行 `engine.exit code=134` 透露了真相 ——
     * **134 = 128 + 6 = SIGABRT**，在 Node 里几乎总是
     * `FATAL ERROR: Reached heap limit` / `Assertion failed` / 未捕获异常。
     * 而 V8 打这行 FATAL 时是往 **stdout/stderr** 写的，正好落在
     * `EngineManager.logLines` 那个 400 行环形缓冲里 —— 但没人把它写进现场。
     *
     * 旧版反复退出时，根因证据会随进程消失；现在把引擎日志尾部一并写入现场。
     *
     * @param engineTail 引擎 stdout/stderr 的尾部行；由调用方从
     *   `EngineManager.logLines` 快照而来。传空列表等价于修复前的行为。
     */
    fun recordEngineExit(
        exitCode: Int?,
        expected: Boolean,
        detail: String,
        engineTail: List<String> = emptyList(),
    ) {
        writeScene(
            kind = if (expected) "engine-exit-expected" else "engine-exit",
            reason = "exitCode=$exitCode $detail",
            stack = null,
            threadName = "engine-reader",
            level = if (expected) 'I' else 'W',
            engineTail = engineTail,
        )
    }

    /**
     * 通用入口：记一次「本进程之外的问题」。
     *
     * 给 `EngineManager` 的启动或资产安装失败等**不至于崩溃但需要现场**的
     * 场景用。写出来的文件与崩溃现场同构，分析方式一致。
     */
    fun recordAnomaly(kind: String, reason: String, level: Char = 'W') {
        writeScene(kind = kind, reason = reason, stack = null, threadName = Thread.currentThread().name, level = level)
    }

    // ── 现场文件的写法 ────────────────────────────────────────────────────────

    /**
     * 写一份现场文件。**这是本类唯一的写入口**，四类都走它，格式因此完全一致。
     *
     * ⚠️ 全程不能抛（调用方可能正在崩溃路径上），但**也不能无声地失败** ——
     *   见下方的 `onFailure`：原来这里是裸 `runCatching {}`，于是「现场写不进去」
     *   （磁盘满 / `crash/` 建不出来、`crashDir` 为 null）与「这里压根没崩过」
     *   在产物上**完全一样**：`crash/` 目录空着，诊断包的 `crash-scenes` 报「无现场」，
     *   分析者据此判定「本次运行正常」。而崩溃现场正是本系统唯一的排查入口。
     *   与 `DiagnosticBundle.writeCrashes` 是同一个失效模式（裸 runCatching 吞掉）。
     */
    private fun writeScene(
        kind: String,
        reason: String,
        stack: String?,
        threadName: String,
        level: Char = 'E',
        /** 引擎进程自己的 stdout/stderr 尾部；空则**不写**这一段（保持旧格式）。 */
        engineTail: List<String> = emptyList(),
    ) {
        runCatching {
            val dir = crashDir
            if (dir == null) {
                // ⚠️ 这一支过去是 `?: return@runCatching` —— 静默丢弃。它发生在
                //   `install()` 还没跑（启动极早期崩溃）或 mkdirs 失败时。
                reportSceneFailure(kind, "crashDir 未初始化（install() 尚未执行或 crash/ 建不出来）")
                return@runCatching
            }
            if (!dir.exists()) dir.mkdirs()
            val now = System.currentTimeMillis()
            val name = "${fileFmt.format(Date(now))}-$kind.txt"
            val f = File(dir, name)

            val sb = StringBuilder()
            // ⚠️ **`reason` 与 `stack` 必须过脱敏网**（2026-10-14 对抗式审查查出）。
            //   本文件是**唯一**不过 `LogStore.safeRedact` 的出口：它的内容最终由
            //   `DiagnosticBundle.writeCrashes()` **逐字节**拷进诊断包，而 `reason`
            //   直接来自异常 message（见 [install] 里 `"${err.javaClass.name}: ${err.message}"`）
            //   —— 一条含 `?token=` 或 `sk-…` 的 message 就会原样出设备。
            //
            //   同一份文件里另外两段其实**已经**是脱敏过的，容易误以为整份都安全：
            //   - `--- last 200 log lines ---` 来自 `LogStore.tail()`（写 app.log 时已抹）；
            //   - `--- recent events ---` 来自 `EventLog.readAll()`（[EventLog.record]
            //     现在已抹）。
            //   只有 `reason` 与 `stack` 是原样进来的。所以只处理这两个字段，
            //   不整份重扫 —— 崩溃路径上不做无谓的重活。
            //
            //   ⚠️ 用 `runCatching` 兜底：这里跑在崩溃路径上，脱敏失败绝不能让它抛
            //   （铁律 ①）。失败时退化为原文，与修复前行为一致 —— 即「脱敏没成功」
            //   不会比「从未尝试」更糟。
            val safeReason = runCatching { Redactor.redact(reason) }.getOrDefault(reason)
            val safeStack = stack?.let { runCatching { Redactor.redact(it) }.getOrDefault(it) }

            sb.append("time: ").append(fmt.format(Date(now))).append('\n')
            sb.append("kind: ").append(kind).append('\n')
            sb.append("bootId: ").append(BootState.bootId).append('\n')
            sb.append("uptimeMs: ").append(runCatching { BootState.uptimeMs() }.getOrDefault(0L)).append('\n')
            sb.append("device: ").append(VersionInfo.deviceSummary()).append('\n')
            sb.append("app: ").append(VersionInfo.summary()).append('\n')
            sb.append("thread: ").append(threadName).append('\n')
            // ⚠️ 换行必须折叠：本文件是 `key: value` 逐行格式，而 `reason` 里可能带整段
            //   native 栈（chromium 的 `UncaughtExceptionException` 构造时就把
            //   `"Native stack trace:" + lineSeparator + nativeStackTrace` 塞进了 message）。
            //   直接内联会让 `reason:` 那一行后面跟几百行，破坏格式、也把
            //   `LogViewerActivity` 的 12 行预览挤出 `--- stack trace ---` 之后的内容。
            //   折叠成 `\n` 字面量只是**显示**处理，栈全文仍在下方的 stack 段里完整保留。
            sb.append("reason: ").append(safeReason.replace("\r\n", "\\n").replace("\n", "\\n")).append('\n')

            if (safeStack != null) {
                sb.append("\n--- stack trace ---\n").append(safeStack).append('\n')
            }

            // ⚠️ 最后 200 行：这是整个现场里信息量最大的一段。
            sb.append("\n--- last ${LogStore.CRASH_TAIL_LINES} log lines ---\n")
            runCatching {
                LogStore.tail(LogStore.CRASH_TAIL_LINES).forEach { sb.append(it).append('\n') }
            }.onFailure { sb.append("(内存日志不可读: ${it.message})\n") }

            /*
             * 引擎自己的输出（stdout/stderr）。
             *
             * ⚠️ 这一段与上面的 `--- last 200 log lines ---` **不是一回事**：
             *   那段是 App 的 app.log，这段是**引擎进程自己说的话**。
             *   引擎崩溃时唯一的根因证据就在这段里（V8 的 FATAL、Node 的
             *   uncaught exception、插件 apply() 抛的栈）—— 它们直接写到
             *   进程的 stdout/stderr，从来不会经过 `LogStore`。
             *
             * 只在非空时写：正常停止（`expected = true`）时没有可写的输出，
             * 凭空多一个空标题段只会让读的人以为"有内容但读不到"。
             */
            if (engineTail.isNotEmpty()) {
                sb.append("\n--- engine output (tail ${engineTail.size} lines) ---\n")
                engineTail.forEach { sb.append(it).append('\n') }
            }

            // 时间线尾部：回答「崩在哪一步」
            sb.append("\n--- recent events ---\n")
            // ⚠️ **不能用 `LogStore.dirOrNull()`**（2026-10-14 自检发现，同族第 20 例）。
            //   `EventLog.init(logDir)` 持的是**自己的**目录，与 `LogStore.dir` 无关；
            //   而 `LogStore.init` 失败时 `dir = null` —— 于是这一整段在那种状态下
            //   静默消失：`if (d != null)` 不成立，连下面那句
            //   「(事件时间线不可读)」都不会打（那不是异常，是空分支）。
            //   现场文件里就凭空少了一整段，而 `--- recent events ---` 这个标题
            //   还在、下面空着，读的人只会以为「这次没有事件」。
            //   本类的 `crashDir` 就是同一个 `logDir` 下的 `crash/`，用它拼回父目录
            //   最可靠（不依赖任何别处的状态）。
            runCatching {
                val d = crashDir?.parentFile ?: LogContext.filesDirOrNull()?.let { File(it, LogContext.LOG_DIR) }
                if (d != null) {
                    // ⚠️ **用 `describeAll` 而不是逐条 `describe`**（2026-10-14 自检发现，
                    //   同族第 25 例）。`EventLog.readAll` 读的是 `events.jsonl` 全量，
                    //   它**跨运行累积**；`takeLast(20)` 一旦跨过运行边界，`[+Nms]`
                    //   就会在中途突然变小，而逐条 `describe` 不会提示这一点 ——
                    //   读的人会把多次运行当成一次。
                    //
                    //   真机实测（服务器 209 份现场）：**171 份（82%）** 跨运行边界。
                    //   最典型的是 `termux.done` 在一个现场里出现多次，看起来像
                    //   「一次运行里引导了好几遍」，实际是设备启动过好几次、每次一遍。
                    //
                    //   `describeAll` 在边界处插一行 `── 运行边界：… ──`，把这件事
                    //   写成显式事实。分段本身仍由 bootId 决定（每条事件都记着）。
                    sb.append(EventLog.describeAll(EventLog.readAll(d).takeLast(20)))
                } else {
                    sb.append("(取不到日志目录：LogContext 未安装，事件时间线不可读)\n")
                }
            }.onFailure { sb.append("(事件时间线不可读: ${it.message})\n") }

            f.writeText(sb.toString())
            // 现场文件写完后，在 app.log 里留一条索引（拿得到锁才有）。
            // ⚠️ 让用户在日志界面顺着时间翻也能看到「这里发生过什么」。
            withLogLock {
                LogStore.app(level, "Crash", "已记录现场: crash/$name —— $reason")
            }
        }.onFailure { t ->
            reportSceneFailure(kind, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 现场文件**没写成**时的唯一留痕出口。
     *
     * ⚠️ 存在的意义：`writeScene` 是全类唯一写入口，它静默失败 ⇒ 「这次没崩」与
     *   「现场丢了」不可区分。这里至少把损失说出来，并**明确写进 `app.log`**
     *   （`AndroidLog` 只在 logcat 里，离线诊断包拿不到 logcat）。
     *
     * ⚠️ 本函数自身也必须 fail-safe：它跑在崩溃路径上，再抛一次就是二次崩溃。
     *   所以整体 `runCatching`，且不用 `LogStore.app` 的锁语义 —— 用
     *   [withLogLock]（超时即跳过），拿不到锁时不阻塞、不抛。
     */
    private fun reportSceneFailure(kind: String, detail: String) {
        runCatching {
            AndroidLog.e(TAG, "现场文件写入失败（$kind）: $detail")
            withLogLock {
                LogStore.app('E', "Crash", "⚠️ 现场文件写入失败（$kind）：$detail —— 本次崩溃无现场可查")
            }
        }
    }

    /**
     * 在**有限时间内**拿到日志锁再执行 [block]；拿不到就跳过。
     *
     * 见类注释约束①。这里用 `synchronized` + 超时做不到（Kotlin 的 `synchronized`
     * 不可超时），所以借 `ReentrantLock.tryLock`。
     *
     * ⚠️ 锁对象必须与 `LogStore` 用的是**同一个** —— 所以 `LogStore` 暴露
     * [LogStore.withLockOrSkip]。本方法只是它的语义化封装，便于崩溃路径读起来
     * 意图明确。
     */
    private fun withLogLock(block: () -> Unit) {
        runCatching { LogStore.withLockOrSkip(LOCK_WAIT_MS, block) }
    }

    // ── 读取 ──────────────────────────────────────────────────────────────────

    /**
     * 列出全部崩溃现场，**新的在前**。
     *
     * 给日志界面与诊断包用。返回的是文件对象，调用方自己读内容 ——
     * 一次崩溃现场可能上百 KB，全读进内存没必要。
     */
    fun listScenes(): List<File> {
        val dir = crashDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 崩溃现场数量（界面摘要用）。 */
    fun sceneCount(): Int = listScenes().size

    /**
     * 按现场类别判定是否需要标红。引擎启动失败有独立诊断，不算硬崩溃。
     * 历史 `main-thread-stall` 文件沿用旧分类；历史 `process-freeze` 文件仅作为普通现场读取。
     */
    fun isHardCrash(f: File): Boolean = when (kindOf(f)) {
        "uncaught-exception", "renderer-crash", "main-thread-stall" -> true
        else -> false
    }

    /**
     * 从现场文件名里取出类别（`kind`），取不到就返回 `"unknown"`。
     *
     * 命名契约见 [writeScene]：`yyyyMMdd-HHmmss-<kind>.txt`。**必须是这个正则**，
     * 不能用 `substringAfter('-')` —— 时间戳本身自带一个 `-`（`20261013-214701`），
     * 那样取出来的是 `214701-engine-start-failed`。
     */
    fun kindOf(f: File): String =
        sceneNamePattern.matchEntire(f.name)?.groupValues?.get(1) ?: "unknown"

    /**
     * 把一批现场按类别汇总成 `engine-start-failed×70, engine-exit×16` 这样的短语。
     *
     * 按**数量降序**排（多的在前），同数量按名字排 —— 让最占多数的那类一眼可见。
     * 只报类别与数量，不解释原因。
     *
     * ⚠️ **存在的意义是替代「举例子」**。诊断包与 `logs` 工具的文案里原来写的是
     *   「渲染进程被回收/引擎正常退出等」，而真机 22 个 warn 包里那两个例子
     *   出现 **0** 次，实际全是 `engine-start-failed`(70) 与 `engine-exit`(16) ——
     *   等于把「引擎起不来」（`引擎宿主` 自己注释成
     *   「本 App 最严重的一类故障」）报成了「引擎正常退出等」。
     *   例子是猜测，类别是事实。
     */
    fun summarizeKinds(scenes: List<File>): String =
        scenes.groupingBy { kindOf(it) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key}×${it.value}" }
}
