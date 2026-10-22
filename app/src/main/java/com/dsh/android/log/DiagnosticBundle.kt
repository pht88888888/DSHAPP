package com.dsh.android.log

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包 —— **本日志系统的最终交付物**。
 *
 * ## 为什么不是「把日志文件打包发过来」
 *
 * 裸日志的问题不是「不够全」而是「定位不了」：几万行里混着正常输出、引擎的
 * 逐 token 输出、网页 console，而决定性的事实（版本戳对不对、插件激活了没有、
 * 上次为什么退出）**没有任何一处集中说明**。分析者要自己拼，用户也不懂该提供什么。
 *
 * 所以交付物是**带结论的包**：日志 + 环境快照 + 崩溃现场 + **`diagnosis.json`
 * （自动跑的检查项与结论）**。目标是「拿到包就知道问题在哪」，而不是「拿到包
 * 才开始查」。
 *
 * ## 包内结构（分析者只需看前三个）
 *
 * ```
 * manifest.json       包元信息 + 覆盖范围（日志从哪到哪、有没有被截断）
 * diagnosis.json      ★ 自动结论：逐项检查 + 汇总
 * environment.json    设备 / 系统 / App / 内存 / 磁盘 / 权限
 * timeline.jsonl      事件时间线（回答「卡在哪一步、耗时多少」）
 * app.log[.1]         应用日志（476 处 Log.*）
 * engine.log          引擎 stdout+stderr
 * web.log             网页 console
 * crashes/            崩溃现场（堆栈 + 最后 200 行日志）
 * files.json          关键文件的存在性/大小（回答「资产解包了没有」）
 * ```
 *
 * ⚠️ 上面那段里**绝不能出现裸露的注释起始符号**（例如把「crashes/」与
 * 「星号.txt」直接连写）—— Kotlin 的块注释会**嵌套**，多出的那一个会把 KDoc
 * 提前闭合、吞掉文件后半段，症状是一连串莫名其妙的 `Unresolved reference`
 * 加 `Unclosed comment`。这是 AGENTS.md 里记过的陷阱，本项目已经踩过两次
 * （第二次就是这段说明自己写的）。
 *
 * ## 体积上限与「诚实截断」
 *
 * 上限 8 MB。超了就**从最不重要的开始丢**（web.log → 旧代日志 → app.log 头部），
 * 并且在 `manifest.completeness` 里**如实标注丢了什么**。
 *
 * ⚠️ 这一条不能省：一个被截断的包如果不说明截断，分析者会得出「日志里没有
 * 这条错误，所以没发生过」的**错误结论**。宁可让包难看，也不能让它骗人。
 */
object DiagnosticBundle {

    /** 诊断包体积上限。超过就按优先级丢内容，见 [build]。 */
    private const val MAX_BUNDLE_BYTES = 8L * 1024L * 1024L

    /**
     * 最多收几份引擎启动诊断（`startup-*.log`）。
     *
     * 引擎每次启动失败写一份，攒久了上百份；最近 5 份足以覆盖「最近为什么起不来」。
     */
    private const val MAX_STARTUP_LOGS = 5

    /**
     * 读崩溃现场的 `bootId:` 时最多看几行。
     *
     * 格式见 [CrashRecorder.writeScene]：`time:` / `kind:` / `bootId:` 三行，
     * 所以第 3 行就该命中。给到 8 行是留余量（万一将来头部加了字段），
     * 同时避免为了一个 id 把整个现场文件（真机实测最大 28 KB）读进内存。
     */
    private const val HEADER_LINES_FOR_BOOT_ID = 8

    /**
     * `rescue/attempts/` 最多带几份（L1 二分逐步记录）。
     *
     * 与 [MAX_STARTUP_LOGS] 同一取舍：试探次数随隔离轮次增长，最近几份足以
     * 还原「摘到哪一个 bundle 时好了」。按修改时间取最近的。
     */
    private const val MAX_RESCUE_ATTEMPTS = 8

    private val fmt = SafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SafeDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 生成诊断包。
     *
     * @param logDir 日志目录（`filesDir/logs`）。
     * @param outDir 输出目录（`logs/export` 或 `logs/pending`）。
     * @param reason 为什么生成：`crash` / `manual` / `startup-retry`。会写进 manifest，
     *   服务器据此分流。
     * @param checks 额外的检查项（由 `EngineManager` 提供，它知道资产/补丁的状态）。
     *   本类自己也会跑一批通用检查（见 [runChecks]）。
     * @return 生成的 zip；失败返回 null（调用方负责提示用户）。
     */
    fun build(
        logDir: File,
        outDir: File,
        reason: String,
        extraFiles: List<File> = emptyList(),
        checks: List<Check> = emptyList(),
    ): File? {
        return try {
            // ⚠️ **打包前必须先把异步日志刷下去**（踩过，代价是 19 份真机诊断包全部缺
            //   `engine.log` / `web.log`）。
            //
            //   `engine.log` / `web.log` 是**异步缓冲**写的（见 `LogStore.writeAsync`：
            //   「异步档**攒着不 flush**」），它们只在累积到 1MB 时才 flush 一次。
            //   而 `build()` 是**进程内即时读文件**的：此刻这两份文件在磁盘上要么
            //   还不存在，要么长度是 0 —— 于是 `writeLogFile` 第 186/188 行的
            //   `if (!f.isFile) return 0L` / `if (size <= 0) return 0L` **静默跳过**，
            //   包里连条目都没有，`completeness` 里也一个字都不记。
            //
            //   症状极具误导性：`app.log` 里明明有几百行 `[engine] …`（那是
            //   `Log.i(TAG, "[engine] $line")` 打给 logcat 的副本，同步落盘），
            //   让人以为引擎日志已经收进来了，而真正带完整上下文的那份从来没进过包。
            //
            //   放在 `build()` 入口而不是各个调用点，是因为调用点有三个
            //   （`LogViewerActivity` 两处、`LogCommandBridge`、`LogUploader`），
            //   逐个加必然漏 —— 这里加一次就全覆盖。
            LogStore.flushAsync()

            if (!outDir.exists()) outDir.mkdirs()
            val now = System.currentTimeMillis()
            val name = "diagnostic-${fileFmt.format(Date(now))}-${BootState.bootId}.zip"
            val out = File(outDir, name)

            val completeness = JSONObject()
            val entriesWritten = mutableListOf<String>()
            // 脱敏自检：命中条目名 + **实际查过的条目名**（见 [writeEntry]）。
            val redactionFindings = mutableListOf<String>()
            val redactionChecked = mutableListOf<String>()
            var budget = MAX_BUNDLE_BYTES

            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                // ── ① 先写体积小、价值高的（保证它们一定进包）──
                budget -= writeEntry(zos, "environment.json", EnvironmentSnapshot.collect(LogContext.get()).toString(2), entriesWritten, redactionFindings, redactionChecked)

                val diagnosis = runChecks(logDir, checks)
                budget -= writeEntry(zos, "diagnosis.json", diagnosis.toString(2), entriesWritten, redactionFindings, redactionChecked)

                val timeline = buildTimeline(logDir, completeness)
                budget -= writeEntry(zos, "timeline.jsonl", timeline, entriesWritten, redactionFindings, redactionChecked)

                // ── ② 崩溃现场（价值高、体量中等）──
                budget -= writeCrashes(zos, logDir, entriesWritten, completeness)

                // ── ②.5 救援阶梯状态（小而关键：`diagnosis.json` 的 boot-tier 项
                //    明确指向 `rescue/state.json`，此前那个目录从未进过包）──
                budget -= writeRescueState(zos, entriesWritten, completeness)

                // ── ③ 关键文件状态 ──
                if (extraFiles.isNotEmpty()) {
                    budget -= writeEntry(
                        zos, "files.json",
                        EnvironmentSnapshot.collectFileStates(extraFiles).toString(2),
                        entriesWritten, redactionFindings, redactionChecked,
                    )
                }

                // ── ④ 日志（体量最大，按重要性从低到高丢）──
                // 顺序刻意是 web → 旧代 → app：web.log 最吵、信息密度最低。
                budget -= writeLogFile(zos, logDir, LogStore.WEB_LOG, budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, "${LogStore.WEB_LOG}.1", budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, "${LogStore.ENGINE_LOG}.1", budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, LogStore.ENGINE_LOG, budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, "${LogStore.APP_LOG}.1", budget, entriesWritten, completeness)
                budget -= writeLogFile(zos, logDir, LogStore.APP_LOG, budget, entriesWritten, completeness)

                // ── ④.5 引擎自己写的启动诊断 ──
                budget -= writeEngineStartupLogs(zos, entriesWritten, completeness)

                // ── ⑤ manifest 最后写（此时才知道完整情况）──
                val manifest = JSONObject().apply {
                    put("schemaVersion", SCHEMA_VERSION)
                    put("generatedAt", now)
                    put("generatedAtText", fmt.format(Date(now)))
                    put("reason", reason)
                    put("bootId", BootState.bootId)
                    put("app", VersionInfo.summary())
                    put("device", VersionInfo.deviceSummary())
                    put("entries", JSONArray(entriesWritten))
                    put("completeness", completeness)
                    // ⚠️ **脱敏自检结论，口径必须写在结论旁边**（本项目第 15 类
                    //   「结论与它描述的总体不符」正是这样产生的）。
                    //   不能只写一个 `redactionOk: true` —— 那会让分析者以为
                    //   「整包干净」，而实际只查了新构造的那 4 个文本条目。
                    put("redaction", JSONObject().apply {
                        put("checked", JSONArray(redactionChecked))
                        put("unredactedEntries", JSONArray(redactionFindings))
                        put("clean", redactionFindings.isEmpty())
                        put(
                            "scope",
                            "只覆盖 checked 列出的条目（本次实际走过自检的那些，都是新构造的文本）。" +
                                "**manifest.json 自身不在其中** —— 它承载的就是这份自检结论，" +
                                "不能自引用（先有结论才能写它）。" +
                                "流式写的 crashes/ 与 *.log 由各自的写入方在写入时脱敏" +
                                "（app/engine/web 三路走 LogStore.safeRedact，事件走 EventLog.record），" +
                                "**未在打包时复检**；" +
                                // ⚠️ **engine-startup/ 必须单独说，不能和前两者并成一句**
                                //   （2026-10-14 自检发现，同族第 21 例）。
                                //   原来这句是「crashes/ · *.log · engine-startup/ 由各自的
                                //   写入方（LogStore.safeRedact / EventLog.record）在写入时脱敏」
                                //   —— 而 `engine-startup/startup-*.log` 是**引擎进程自己写的**
                                //   （见本文件 [writeEngineStartupLogs] 的说明：
                                //   `[engine] Full diagnostics: <$DSH_HOME>/logs/startup-…log`）。
                                //   引擎是个独立的 Node 进程，**根本不经过任何 Kotlin 函数**，
                                //   那两条 `safeRedact` / `record` 对它一点作用都没有。
                                //   所以这句话把一个**从未脱敏**的来源报成了「已脱敏」——
                                //   方向是假报安全，比漏报更糟：分析者据此可以放心地把包外发。
                                "engine-startup/ 例外：那是**引擎自己写的**（独立 Node 进程，" +
                                "不经过本 App 的任何脱敏函数），本包对它**未做任何脱敏、也未复检**。" +
                                "因此 clean=true 的含义是" +
                                "「新构造的文本没有漏网」，不等于「包内每个字节都已复检」。",
                        )
                    })
                    put("limits", JSONObject().apply {
                        put("maxBundleBytes", MAX_BUNDLE_BYTES)
                        put("crashTailLines", LogStore.CRASH_TAIL_LINES)
                    })
                }
                // ⚠️ manifest 必须**第一个**被解压出来看（分析者的入口），
                //    zip 里顺序无所谓，但内容要在最前面写进注释说明。
                writeEntry(zos, "manifest.json", manifest.toString(2), entriesWritten)
            }

            out
        } catch (t: Throwable) {
            // 生成失败不抛：调用方会提示用户，而不是让日志界面崩掉。
            android.util.Log.e("DSH/Diagnostic", "诊断包生成失败: ${t.message}", t)
            // ⚠️ **必须落盘，不能只打 logcat**（2026-10-14 自检发现）。
            //   logcat 是易失的：用户点了「导出」看到失败提示、重启 App 之后再想
            //   反馈，这条原因就没了。而本方法返回的只是一个 `null` —— 调用方
            //   （`LogViewerActivity.exportBundle`）拿不到原因，于是它**自己编了一个**
            //   「写入失败或磁盘空间不足」贴在界面上（见那里）。那正是本文件
            //   `runChecks` 里明写的原则「只报事实，不猜原因」被违反的地方。
            //   在这里把真实异常落盘，界面上那句话才有据可查。
            LogStore.app(
                'E', "Diagnostic",
                "诊断包生成失败（本次导出未产出文件）: ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        }
    }

    // ── 各项写入 ──────────────────────────────────────────────────────────────

    /**
     * 写一个 zip 条目，返回写入的字节数。
     *
     * ⚠️ **本函数不做体积预算检查，KDoc 原来那句「单个条目超预算就整个跳过并在
     *   completeness 里记一笔」是假的。** 签名里既没有 `budget` 也没有
     *   `completeness`，函数体无条件写入 —— 承诺了一个它做不到的保护。这与刚删掉的
     *   `LogStore.enforceDirQuota(skipDirs)` 完全同构（那个也是「KDoc 自称有保证、
     *   实现里根本没有」），属本项目已记录的「死代码造成的虚构保证」类。
     *
     * ⚠️ **这五个条目不设预算是有意为之，不是遗漏**：`environment.json` /
     *   `diagnosis.json` / `timeline.jsonl` / `files.json` 都是**小且价值最高**的
     *   （写它们时 budget 基本没被消耗），且是分析者的入口。宁可它们把 budget
     *   扣成负数、让后面的大日志被整跳，也不能缺 —— 顺序即优先级（见 [build]）。
     *   但既然「不做保护」是设计，就不能在文档里说做了保护。
     *
     * @return 写入的字节数（UTF-8）。调用方用它递减 budget。
     */
    private fun writeEntry(
        zos: ZipOutputStream,
        name: String,
        content: String,
        written: MutableList<String>,
        redactionFindings: MutableList<String>? = null,
        redactionChecked: MutableList<String>? = null,
    ): Long {
        // ⚠️ **脱敏自检就挂在这里**，因为这是「新构造的文本」进包的唯一出口。
        //   见 [Redactor.looksUnredacted] 的 KDoc —— 那个函数原来零调用点，
        //   属本项目已记录的「死代码造成的虚构保证」类；现在接上了。
        //
        //   ⚠️ **只记条目名，绝不记命中片段**：片段本身就是密钥，把它写进
        //   `manifest.completeness` 等于自检自己制造了泄漏。分析者拿到包后
        //   打开那个条目自己看即可。
        //
        //   ⚠️ **`redactionChecked` 必须在这里记，不能在调用方写死一份名单。**
        //   第一版就是写死的（`environment.json / diagnosis.json / timeline.jsonl /
        //   files.json` 四个名字），而 `files.json` 只在 `extraFiles` 非空时才写 ——
        //   于是「export 路径」出的包里**没有** `files.json`，`checked` 却声称查了它。
        //   那正是本项目已记录 14 次的缺陷族：**结论与它描述的总体不符**。
        //   自检自己犯这个错尤其难看，所以改成「查到哪条记哪条」。
        if (redactionChecked != null) redactionChecked.add(name)
        if (redactionFindings != null && Redactor.looksUnredacted(content)) {
            redactionFindings.add(name)
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
        written.add(name)
        return bytes.size.toLong()
    }

    /**
     * 写一个日志文件，**超预算则截断**。
     *
     * ⚠️ 截断的是**头部**（保留尾部）：日志最有价值的是「最后发生了什么」，
     * 而不是开机时的初始化。这与 `EngineService.truncateTail` 的取舍一致。
     */
    private fun writeLogFile(
        zos: ZipOutputStream,
        logDir: File,
        name: String,
        budget: Long,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val f = File(logDir, name)
        // ⚠️ **缺文件必须留痕**，不能静默 return（踩过）：19 份真机诊断包全都没有
        //   `engine.log` / `web.log`，而 `completeness` 里一个字都没记，导致
        //   「包里没有」和「日志系统没记」两种情况**从产物上无法区分**，
        //   白烧了一轮排查。现在把「不存在 / 空 / 跳过」三种情况都写进 completeness，
        //   分析者一眼能看出是哪一种。
        if (!f.isFile) {
            // ⚠️ **必须区分「不存在」与「存在但不是文件」**（2026-10-14 自检发现）。
            //   `!f.isFile` 对**目录**同样为真，于是 `logs/app.log` 被换成目录时
            //   这里写的是 `app.log_absent: "文件不存在"` —— **假话**：它明明存在，
            //   而且它存在这件事本身就是根因（`LogStore.init` 会因 `EISDIR` 失败）。
            //   分析者读到「不存在」会去查「谁把 app.log 删了」，而正确的问题是
            //   「谁在那儿建了个同名目录」。同族缺陷第 15 次的又一实例。
            if (f.exists()) {
                completeness.put(
                    "${name}_notAFile",
                    "路径存在但不是普通文件（isDirectory=${f.isDirectory}）—— 日志写不进去就是这个原因",
                )
            } else {
                completeness.put("${name}_absent", "文件不存在")
            }
            return 0L
        }
        val size = f.length()
        if (size <= 0) {
            completeness.put("${name}_empty", "文件存在但长度为 0")
            return 0L
        }

        return try {
            if (size <= budget) {
                // ⚠️ **先取流、再建条目**：反过来时读失败（EACCES 等）会在包里留下
                //   一个 0 字节僵尸条目，紧接着 catch 又写 `${name}_failed` 说
                //   「读取失败」—— 两句话互相矛盾，分析者会以为日志本身是空的。
                val ins = f.inputStream()
                zos.putNextEntry(ZipEntry(name))
                ins.use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(name)
                recordRange(completeness, name, f, truncated = false, includedBytes = size)
                size
            } else if (budget > 64 * 1024) {
                // ⚠️ **必须按字节切，不能按字符切**（2026-10-14 对抗式审查查出）。
                //   原来写的是 `text.takeLast(budget)` —— 而 `budget` 是**字节**预算
                //   （调用方 L113 从 `MAX_BUNDLE_BYTES = 8 MiB` 递减，且会写进
                //   manifest 的 `limits.maxBundleBytes`），`takeLast` 切的是 **UTF-16
                //   字符**。UTF-8 下一个中文字符占 3 字节，于是：
                //
                //   | 内容 | 声明预算 | 实际写入 |
                //   |---|---|---|
                //   | 纯 ASCII | 8 MiB | 8.0 MiB（1.00x） |
                //   | 真机 app.log 字符分布（1.20 B/char） | 8 MiB | 9.6 MiB（1.20x） |
                //   | 纯中文 | 8 MiB | **24.0 MiB（3.00x）** |
                //
                //   中文日志是本项目的常态，所以这不是理论问题。manifest 里那个
                //   `maxBundleBytes` 是对分析者的**承诺**，超发 3 倍时承诺失效；
                //   而 `completeness.truncated` 的口径写着「false ⟺ 逐字节完整」，
                //   也跟着失真（分析者会以为「没超限 ⇒ 内容完整」）。
                //
                //   现在从尾部按 **code point** 反向累计 UTF-8 字节数，保证保留的
                //   尾部 ≤ `keep` 字节，且不会把代理对（emoji）从中间劈开。
                val keep = budget
                val text = f.readText()
                var acc = 0L
                var i = text.length
                while (i > 0) {
                    val cp = text.codePointBefore(i)
                    val n = utf8Len(cp).toLong()
                    if (acc + n > keep) break
                    acc += n
                    i -= Character.charCount(cp)
                }
                val cut = text.substring(i)
                // ⚠️ 截断标记本身也要占几十字节，所以最终 `writtenBytes` 会比 `keep`
                //   略大一点。这是**刻意的取舍**：标记必须留下（否则分析者不知道
                //   头部被砍了），而那几十字节相对 64KB 起步的预算可忽略。
                //   `recordRange` 记的是**真实**写入字节数，所以配额递减是准的。
                val content = "…（本文件前 ${text.length - cut.length} 字符因体积上限被截断）…\n$cut"
                zos.putNextEntry(ZipEntry(name))
                val bytes = content.toByteArray(Charsets.UTF_8)
                zos.write(bytes)
                zos.closeEntry()
                written.add(name)
                recordRange(completeness, name, f, truncated = true, includedBytes = bytes.size.toLong())
                bytes.size.toLong()
            } else {
                // ⚠️ **整个跳过时必须与「截断」区分开。** 这条分支一个字都没进包，
                //   而上面那条只砍了头部。两者原来都写 `truncated = true`，
                //   分析者看到 true 会以为「包里有这个文件的一部分」，
                //   于是去包里找、找不到，再怀疑是打包坏了 —— 而同一份 JSON 里的
                //   `${name}_skipped` 明明写着「已跳过」。两个字段互相矛盾。
                //
                //   现在的口径：`truncated` = **未完整进包**（false ⟺ 逐字节完整），
                //   再用 `includedBytes` 区分「部分（>0）」与「一条都没有（0）」，
                //   并由 `skipped: true` 点明是整跳。三个字段各管一件事，不重叠。
                completeness.put("${name}_skipped", "体积预算不足，已跳过")
                recordRange(completeness, name, f, truncated = true, includedBytes = 0L, skipped = true)
                0L
            }
        } catch (t: Throwable) {
            // ⚠️ **不能静默吞**（原来是 `catch (_: Throwable) { 0L }`）：那样这个
            //   日志文件会**从包里消失且 completeness 里一个字都没有**，
            //   分析者看到的现象与「这个文件本来就不存在」完全一样 ——
            //   又是一次「包在骗人」。现在把异常类型记进 completeness。
            completeness.put("${name}_failed", "读取失败: ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /**
     * 一个 code point 编成 UTF-8 后占几字节。
     *
     * ⚠️ 存在的理由：`writeLogFile` 的预算是**字节**，而字符串按**字符**切 ——
     *   两者之间的换算必须显式做，不能拿 `String.length` 当字节数（一个中文
     *   字符 3 字节，一个 emoji 4 字节）。见 [writeLogFile] 里那张实测表。
     */
    private fun utf8Len(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /**
     * 收**引擎自己写的**启动诊断（`$DSH_HOME/logs/startup-*.log`）。
     *
     * ## 🔴 为什么必须收：这是「引擎起不来」唯一的一手证据
     *
     * 引擎启动失败时，它自己在 stderr 最后一行打出：
     *
     * ```
     * [engine] Full diagnostics: <$DSH_HOME>/logs/startup-2026-10-13T15-51-43.032Z-<uuid>.log
     * ```
     *
     * 那份文件里是引擎的**完整**自检（哪些插件激活了、哪些在等哪个服务、
     * 哪个 provider 注册失败）。而我们包里的 `engine.log` 只有 stdout/stderr
     * 的**前若干行**，恰好在那行 `Full diagnostics` 之后就断了 ——
     * 也就是说 80 场 `engine-start-failed` 里，**决定性的那份文件从来没被收进来过**。
     *
     * ## 为什么要单独写，不能复用 [writeLogFile]
     *
     * [writeLogFile] 的入参是 `logDir` 下的固定文件名；而这一批在**另一个目录**
     * （`$DSH_HOME/logs`，不是 `logsDir`），文件名还带时间戳与 uuid。
     *
     * ## 只收最近 N 份
     *
     * 引擎**每次启动失败**都写一份，攒久了会有几百份。收最近 [MAX_STARTUP_LOGS]
     * 份即可覆盖「最近几次为什么起不来」，再多只是让包变大。
     * 按 mtime 倒序取，并在 `completeness` 里如实记下「一共几份、收了几份」——
     * 不能让分析者以为「包里只有 3 份 = 只失败过 3 次」。
     */
    private fun writeEngineStartupLogs(
        zos: ZipOutputStream,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val ctx = LogContext.get()
        if (ctx == null) {
            completeness.put("engineStartupLogs_absent", "LogContext 未装，取不到 filesDir")
            return 0L
        }
        val dir = File(ctx.filesDir, "home/.dsh/logs")
        if (!dir.isDirectory) {
            completeness.put("engineStartupLogs_absent", "目录不存在（引擎从未写过启动诊断）")
            return 0L
        }
        val all = dir.listFiles { f: File -> f.isFile && f.name.startsWith("startup-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (all.isEmpty()) {
            completeness.put("engineStartupLogs_empty", "目录存在但没有任何 startup-*.log")
            return 0L
        }

        val picked = all.take(MAX_STARTUP_LOGS)
        var total = 0L
        var n = 0
        val failures = JSONArray()
        for (f in picked) {
            // ⚠️ **失败必须留痕**（与 [writeCrashes] 同族的第三份）。这里比
            //   [writeCrashes] 稍好：`totalOnDevice` 与 `included` 两个数并排，
            //   少一份能从 `included < totalOnDevice` 看出来。但**看不出为什么少** ——
            //   是文件读不了、还是条目写坏了。而这份目录恰恰是「引擎起不来」
            //   唯一的一手证据（见上方类注释），漏掉哪一份都很可能是关键那份。
            //   ⚠️ **先取流、再建条目**（与 [writeCrashes] 同）：反过来会在读失败时
            //   在包里留下一个 0 字节僵尸条目，与「没收进来」的结论矛盾。
            runCatching {
                val ins = f.inputStream()
                val entryName = "engine-startup/${f.name}"
                zos.putNextEntry(ZipEntry(entryName))
                ins.use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(entryName)
                total += f.length()
                n++
            }.onFailure { t ->
                failures.put(
                    JSONObject().apply {
                        put("file", f.name)
                        put("error", "${t.javaClass.simpleName}: ${t.message}")
                    },
                )
            }
        }
        // ⚠️ 两个数都要记：只记「收了几份」会让「一共失败过 200 次」看起来像 3 次。
        completeness.put("engineStartupLogs", JSONObject().apply {
            put("totalOnDevice", all.size)
            put("included", n)
            if (failures.length() > 0) put("failedToWrite", failures)
            put("newest", fmt.format(Date(picked.first().lastModified())))
            put("oldestIncluded", fmt.format(Date(picked.last().lastModified())))
        })
        return total
    }

    /**
     * 记录一个日志文件的覆盖范围。
     *
     * ⚠️ 这是 `completeness` 里最重要的信息：分析者必须能回答「这份日志覆盖了
     * 哪段时间」。否则「日志里没有这条错误」可能只是**那段时间不在包里**。
     *
     * ⚠️ **`sizeBytes` 是源文件在设备上的大小，不是包里的字节数。**
     *   截断时两者能差出几个数量级（源 8MB、包里 0 字节）。原来只有这一个字段，
     *   分析者拿它当「包里有这么多」，于是会以为日志完整 —— 明明 `truncated`
     *   就写着 true。所以补一个 [includedBytes]（真正写进包的字节数），
     *   两者一比即可看出被砍掉了多少。
     *
     * @param truncated **未完整进包**。false ⟺ 逐字节完整。
     * @param includedBytes 真正写进 zip 的字节数（整跳时为 0）。
     * @param skipped 整个文件都没进包（与「部分保留」区分）。
     */
    private fun recordRange(
        completeness: JSONObject,
        name: String,
        f: File,
        truncated: Boolean,
        includedBytes: Long,
        skipped: Boolean = false,
    ) {
        runCatching {
            completeness.put(
                name,
                JSONObject().apply {
                    put("sizeBytes", f.length())
                    put("includedBytes", includedBytes)
                    put("truncated", truncated)
                    if (skipped) put("skipped", true)
                    put("lastModified", f.lastModified())
                    put("lastModifiedText", fmt.format(Date(f.lastModified())))
                    // ⚠️ 行数同样是**源文件**的（截断时包里的行数少得多）。
                    //   键名带上 `onDevice` 以免被当成「包里有多少行」。
                    put("linesOnDevice", countLines(f))
                },
            )
        }
    }

    private fun countLines(f: File): Long = runCatching {
        f.bufferedReader().useLines { it.count().toLong() }
    }.getOrDefault(-1L)

    /** 写全部崩溃现场。 */
    private fun writeCrashes(
        zos: ZipOutputStream,
        logDir: File,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val scenes = CrashRecorder.listScenes()
        var total = 0L
        val index = JSONArray()
        val failures = JSONArray()
        for (f in scenes) {
            // ⚠️ **失败必须留痕**，不能裸 `runCatching {}` 静默吞（踩过同构的坑）：
            //   原来这里失败时 `index` / `written` / `total` 各少一项，而
            //   completeness 只写 `crashScenes = index.length()`（**成功数**）——
            //   于是「本来只有 2 份现场」与「3 份里 1 份读失败」在产物上**完全一样**。
            //   崩溃现场是排查的唯一入口，漏掉一份等于把最关键那次的证据藏起来。
            //   同文件的 writeLogFile 用 `${name}_failed` 记失败、writeEngineStartupLogs
            //   用 totalOnDevice/included 对照 —— 三处现在口径一致（都带 failedToWrite）。
            //   ⚠️ **顺序要紧：先取流，再建条目。** 反过来（`putNextEntry` →
            //   `f.inputStream()`）会在读失败时留下一个**已打开但没写内容的 zip 条目**，
            //   包解出来是个 0 字节文件，而 completeness 又写着「没收进来」——
            //   产物自相矛盾：`included` 说 4 份、包里有 5 条。分析者看到那个
            //   0 字节文件会以为「引擎写了个空日志」，而实际是 EACCES。
            //   先取流则失败发生在建条目之前，包是干净的。
            runCatching {
                val ins = f.inputStream()
                val entryName = "crashes/${f.name}"
                zos.putNextEntry(ZipEntry(entryName))
                ins.use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(entryName)
                total += f.length()
                index.put(
                    JSONObject().apply {
                        put("file", entryName)
                        put("sizeBytes", f.length())
                        put("at", f.lastModified())
                        put("atText", fmt.format(Date(f.lastModified())))
                        put("hardCrash", CrashRecorder.isHardCrash(f))
                    },
                )
            }.onFailure { t ->
                failures.put(
                    JSONObject().apply {
                        put("file", f.name)
                        put("error", "${t.javaClass.simpleName}: ${t.message}")
                    },
                )
            }
        }
        if (index.length() > 0) {
            completeness.put("crashScenes", index.length())
        }
        // ⚠️ **总数必须一起记**，否则「收了几份」这个数没有对照。
        completeness.put(
            "crashScenesOnDevice",
            JSONObject().apply {
                put("totalOnDevice", scenes.size)
                put("included", index.length())
                if (failures.length() > 0) put("failedToWrite", failures)
            },
        )
        return total
    }

    /**
     * 把救援阶梯的状态文件打进包（`$DSH_HOME/rescue/`）。
     *
     * ## 为什么必须有
     *
     * `runChecks()` 的 `boot-tier` 检查项对降级态明确写着「已摘除的 bundle 见
     * rescue/state.json」「详见 rescue/state.json」，而**那个目录此前从未进过
     * 诊断包** —— 服务器侧 567 个包里，`rescue/` 条目数为 0，`last-boot-failure.json`
     * 一次都没出现过。分析者被结论指向一个拿不到的文件，于是「引擎为什么起不来」
     * 这件事在产物里**无从判断**：`state.json` 说当前是 normal/rescue，
     * `last-boot-failure.json` 说连续失败几次、哪几个 required 插件没激活 ——
     * 这两份正是 L1/L2/L3 每一次判断的**输入**，缺了它们，救援阶梯的行为
     * 在事后完全不可复盘。
     *
     * ⚠️ 与 `crash/` 同理：这三份文件都是**小且价值最高**的，放在 ② 段写，
     *   保证不会被日志挤掉预算。
     * ⚠️ `attempts/` 是 L1 二分的逐步记录（`0001.json`…），数量随试探次数增长，
     *   这里按修改时间只带最近 [MAX_RESCUE_ATTEMPTS] 份 —— 与
     *   [MAX_STARTUP_LOGS] 同一取舍。
     */
    private fun writeRescueState(
        zos: ZipOutputStream,
        written: MutableList<String>,
        completeness: JSONObject,
    ): Long {
        val dir = RescueBoot.dirOrNull()
        if (dir == null) {
            completeness.put("rescue_absent", "RescueBoot 未安装记录目录")
            return 0L
        }
        if (!dir.isDirectory) {
            // ⚠️ 区分「目录不存在」与「存在但没有文件」：前者是「从未降级过」，
            //   后者是「降级过但状态没写下去」—— 两者对排查的意义完全相反。
            completeness.put("rescue_absent", "目录不存在（从未进入过降级/救援）")
            return 0L
        }
        val all = dir.listFiles { f: File -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        val attemptsDir = File(dir, "attempts")
        val attempts = attemptsDir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_RESCUE_ATTEMPTS)
            ?: emptyList()
        if (all.isEmpty() && attempts.isEmpty()) {
            completeness.put("rescue_empty", "目录存在但没有任何状态文件（从未降级过）")
            return 0L
        }

        var total = 0L
        val index = JSONArray()
        val failures = JSONArray()
        for (f in all + attempts) {
            val entryName = if (f.parentFile == attemptsDir) "rescue/attempts/${f.name}" else "rescue/${f.name}"
            // ⚠️ 先取流、再建条目（与 [writeCrashes] / [writeEngineStartupLogs] 同口径）：
            //   反过来会在读失败时留下 0 字节僵尸条目，与「没收进来」的结论矛盾。
            runCatching {
                val ins = f.inputStream()
                zos.putNextEntry(ZipEntry(entryName))
                ins.use { it.copyTo(zos) }
                zos.closeEntry()
                written.add(entryName)
                total += f.length()
                index.put(
                    JSONObject().apply {
                        put("file", entryName)
                        put("sizeBytes", f.length())
                        put("at", f.lastModified())
                        put("atText", fmt.format(Date(f.lastModified())))
                    },
                )
            }.onFailure { t ->
                failures.put(
                    JSONObject().apply {
                        put("file", entryName)
                        put("error", "${t.javaClass.simpleName}: ${t.message}")
                    },
                )
            }
        }
        val attemptsAll = attemptsDir.listFiles { f: File -> f.isFile } ?: emptyArray()
        completeness.put(
            "rescueFiles",
            JSONObject().apply {
                put("totalOnDevice", all.size + attemptsAll.size)
                put("included", index.length())
                // ⚠️ 当前层级一并写进 completeness：它是「此刻是不是降级态」这个
                //   问题最直接的答案，与 `boot-tier` 那条检查互相印证。
                put("tier", RescueBoot.tier().tier.id)
                if (failures.length() > 0) put("failedToWrite", failures)
            },
        )
        return total
    }

    private fun buildTimeline(logDir: File, completeness: JSONObject): String {
        val events = EventLog.readAll(logDir)
        completeness.put("eventCount", events.size)
        return events.joinToString("\n") { it.toString() }
    }

    // ── 自动结论 ──────────────────────────────────────────────────────────────

    /** 一项检查的结论。`status` 只有三种：ok / warn / fail。 */
    data class Check(val id: String, val status: String, val detail: String) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("status", status)
            put("detail", detail)
        }
    }

    /**
     * 跑通用检查 + 调用方给的检查，产出 `diagnosis.json`。
     *
     * ## 设计原则：只报**事实**，不猜原因
     *
     * 例如「1 个插件未激活: dsh-green-theme」是事实；「因为插件代码有语法错误」
     * 是猜测。自动结论里只写前者 —— 后者交给分析者（或 AI）看日志判断。
     *
     * ⚠️ 编造原因比不给结论更糟：它会把人引到错误的方向，而且看起来很可信。
     */
    /**
     * 把一批现场文件按类别汇总成 `engine-start-failed×70, engine-exit×16` 这样的短语。
     *
     * 实现在 [CrashRecorder.summarizeKinds]（`logs` 工具也要用同一套口径）。
     */
    private fun sceneKindSummary(scenes: List<File>): String = CrashRecorder.summarizeKinds(scenes)

    /**
     * 读一份崩溃现场里的 `bootId:`，用来判断它属于哪一次启动。
     *
     * ## 为什么要读文件
     *
     * 现场文件是**跨运行累积**的，只看「有没有这类文件」分不清「现在还是坏的」
     * 与「早就修好了」。而每份现场里 `[CrashRecorder.writeScene]` 都写了
     * `bootId:`（第 3 行，紧跟 `time:`/`kind:`），拿它与
     * `BootState.previous().bootId` 对齐，就能确定「最近一次启动是否失败」。
     *
     * ⚠️ **只读头部若干行，不整份读**：现场文件里带着最后 200 行日志，
     *   真机实测单份可达 28 KB；而这个字段在第 3 行。用 `bufferedReader`
     *   逐行找、找到就停，避免为了一个 id 把几十 KB 读进内存 —— 这段代码
     *   跑在**崩溃路径与导出路径**上（铁律①：那里不做无谓的重活）。
     *
     * ⚠️ 读不到就返回 `null`，**不编一个**：`null` 在比较时天然不等于任何
     *   真实 bootId，于是「读不出来」不会被误判成「最近一次启动失败了」。
     *   反过来编一个占位符则可能撞上真值（本缺陷族的典型形态）。
     */
    private fun sceneBootId(f: File): String? = runCatching {
        f.bufferedReader().use { r ->
            repeat(HEADER_LINES_FOR_BOOT_ID) {
                val line = r.readLine() ?: return@use null
                if (line.startsWith("bootId:")) {
                    return@use line.substring("bootId:".length).trim().ifEmpty { null }
                }
            }
            null
        }
    }.getOrNull()

    private fun runChecks(logDir: File, extra: List<Check>): JSONObject {
        val checks = mutableListOf<Check>()

        // ⚠️ **跨 check 共用的事实只算一遍**：「最近一次启动的引擎起没起来」。
        //
        //   两处需要它，而它们的**判据来源不同**，这正是问题所在：
        //     · ② `crash-scenes` —— 判据就是它，决定 fail 还是 warn；
        //     · ⑦ `boot-tier`   —— 判据是**层级**，却顺手在文案里断言了**插件加载情况**。
        //
        //   原先只有 ② 算这件事（且算在 `runCatching` 块内、作用域出不来），
        //   于是 ⑦ 只能凭 `tier == NORMAL` 写出「插件全部按配置加载」——
        //   那是**推断，不是它检查过的事实**。引擎自己因版本不兼容禁用插件行时
        //   （`dsh: disabling profile plugin row "…"`），tier 仍是 normal，
        //   插件却实实在在少了一批。
        //
        //   真机现场（`20261014-210912-87475efa`，vivo V2232A / Android 15 /
        //   v21 system / `debuggable:false`）**同一份** `diagnosis.json` 里两条
        //   check 直接打架：
        //     · `crash-scenes`: fail「最近一次启动的引擎**没能起来**（界面打不开）」
        //     · `boot-tier`  : ok  「正常启动（未降级）：插件全部按配置加载」
        //   而包里的 `engine.log` 是引擎自己写的：
        //     `disabling profile plugin row "llm": …incompatible with dsh 0.2.0-rc.2`
        //     `disabling profile plugin row "settings": …`
        //     `agent-loop (required)      llm`
        //     `dsh: startup failed: 1 required plugin did not activate`
        //   插件**没有**全部加载，引擎**没有**起来。
        //
        //   实测 564 个包里 **24 个**（全部 `debuggable:false` 的真实用户）
        //   同时出现上面那两句。
        //
        //   这是本项目缺陷族「结论与它描述的对象不是同一个」的又一例：
        //   文案描述的是**插件加载情况**，而判据查的是**层级**。
        //   修法是把两者对齐 —— 层级照旧由 tier 判（那确实是它的职责），
        //   但文案不许替一个没查过的事实作保。
        //
        //   ⚠️ 现场里的 bootId **永远不会**等于 `BootState.bootId`（本次启动）：
        //     失败发生在**上一次**启动里，而诊断包是用户重开 App 之后才导出的。
        //     真机核对过 40 个包，无一例外，所以只能跟 `previous()` 比。
        val latestStartFailedCount = runCatching {
            val prevBoot = BootState.previous().bootId
            CrashRecorder.listScenes().count {
                CrashRecorder.kindOf(it) == "engine-start-failed" && sceneBootId(it) == prevBoot
            }
        }.getOrDefault(0)

        // ① 上次退出类型
        runCatching {
            val prev = BootState.previous()
            checks.add(
                when {
                    !prev.known -> Check("last-exit", "ok", "首次启动，无历史记录")
                    prev.clean -> Check("last-exit", "ok", "上次正常退出")
                    prev.unstable -> Check(
                        "last-exit", "fail",
                        "连续 ${prev.consecutiveUncleanCount} 次未正常结束 —— 疑似启动即崩的死循环",
                    )
                    else -> Check("last-exit", "warn", "上次非正常结束（无堆栈；可能是崩溃/被系统回收/被 ROM 冻结/断电）")
                },
            )
        }

        // ② 崩溃现场
        //
        // ## 🔴 为什么「引擎起不来」必须单独判 fail（2026-10-14 自检发现，同族第 26 例）
        //
        // `engine-start-failed` 刻意**不算** hard crash（见 [CrashRecorder.isHardCrash]
        // 的论证：它没崩溃过，算进去会让「崩溃现场」这个计数失去意义）—— 那个论证
        // 成立，本处不改它。
        //
        // 但由此产生了一个后果：**App 完全打不开**这件事，在 6 个检查里
        // **没有任何一个**判 fail。`EngineManager` 自己的注释写着它是
        // 「本 App 最严重的一类故障（界面直接不可用）」，而诊断包给出的是
        // `fail: 0` + headline「发现 N 项需要注意的情况」。
        //
        // 真机实测（服务器 40 个含 `engine-start-failed` 的包）：**14 个**是
        // `fail=0`。最典型的一台是 某小米机型 / Android 16：用户装了 40 个
        // 第三方插件，引擎核心插件 `typert-loader` 被拖挂 ⇒ 连续 2 次启动失败 ⇒
        // **App 打不开**；而它导出的诊断包写的是「发现 2 项需要注意的情况」、
        // `crash-scenes: warn`、「有 2 份**非崩溃**现场」。分析者据此会跳过它。
        //
        // ## 判据必须按 bootId 对齐，不能是「有现场就报 fail」
        //
        // 现场文件是**跨运行累积**的（`crash/` 不随启动清空，只按配额淘汰），
        // 所以「存在 `engine-start-failed` 文件」只说明**历史上**发生过。
        // 实测这 40 个包里 **9 个**含超过 1 小时的旧现场、最旧 7.8 小时 ——
        // 一有现场就报 fail，会把「早就修好了」说成「现在还是坏的」，
        // 那正是本缺陷族的另一种形态（拿一个总体/时刻的结论去描述另一个）。
        //
        // 正确判据：**「最近一次启动」是否产出过 `engine-start-failed` 现场**。
        // 对齐链已经现成：
        //   - 每份现场里都写着 `bootId:`（[CrashRecorder.writeScene] 写的）；
        //   - `BootState.previous().bootId` 给出「上一次启动」的 id。
        // 两者相等 ⇒ 那次启动的引擎没起来 ⇒ 用户此刻面对的就是「打不开」。
        //
        // ⚠️ 现场里的 bootId **永远不会**等于 `BootState.bootId`（本次启动）：
        //   失败发生在**上一次**启动里，而诊断包是在用户重开 App 之后才导出的。
        //   真机核对过 40 个包，无一例外。所以只能跟 `previous()` 比。
        //
        // ⚠️ 这件事**已经在上面算过了**（`latestStartFailedCount`），因为 ⑦
        //   `boot-tier` 也要用同一份事实。这里直接取，**不要再算一遍** ——
        //   同一个事实量两遍，两处就可能因为时序不同而给出不同答案。
        runCatching {
            val scenes = CrashRecorder.listScenes()
            val hard = scenes.count { CrashRecorder.isHardCrash(it) }
            val startFailed = scenes.filter { CrashRecorder.kindOf(it) == "engine-start-failed" }
            checks.add(
                when {
                    // 「最近一次启动的引擎起不来」= 界面直接不可用 ⇒ 必须 fail。
                    latestStartFailedCount > 0 -> Check(
                        "crash-scenes", "fail",
                        "最近一次启动的引擎**没能起来**（界面打不开）：$latestStartFailedCount 份 " +
                            "engine-start-failed 现场属于该次启动，详见 crashes/",
                    )
                    scenes.isEmpty() -> Check("crash-scenes", "ok", "没有崩溃现场文件")
                    hard > 0 -> Check("crash-scenes", "fail", "有 $hard 份崩溃现场（共 ${scenes.size} 份），详见 crashes/")
                    // ⚠️ **必须列出实际类别，不能举例子。** 原来这里写的是
                    //   「（渲染进程被回收/引擎正常退出等）」—— 而真机 22 个 warn 包
                    //   里那两个例子出现 **0** 次，实际全是 `engine-start-failed`(70)
                    //   与 `engine-exit`(16)。也就是说文案把「引擎起不来」这种
                    //   `EngineManager` 自己注释成「本 App 最严重的一类故障」的事，
                    //   报告成了「引擎正常退出等」—— 分析者据此会直接跳过它。
                    //   例子是猜测，类别是事实；这里只报事实。
                    //
                    // ⚠️ 走到这一支时 `engine-start-failed` 只可能是**历史**的
                    //   （最近一次启动没失败），所以文案必须把「何时」说清楚，
                    //   否则又会把历史说成当前。
                    else -> Check(
                        "crash-scenes", "warn",
                        if (startFailed.isEmpty()) {
                            "有 ${scenes.size} 份非崩溃现场（${sceneKindSummary(scenes)}），详见 crashes/"
                        } else {
                            "有 ${scenes.size} 份非崩溃现场（${sceneKindSummary(scenes)}），" +
                                "其中 engine-start-failed×${startFailed.size} 属于**更早的启动**" +
                                "（最近一次启动的引擎起来了），详见 crashes/"
                        },
                    )
                },
            )
        }

        // ③ 日志被抽稀情况 —— 分析者必须知道
        //
        // ⚠️ **两个计数器都是进程内的，所以结论必须限定「本次运行」。**
        //   `throttledTotal` / `droppedAsync` 都是 `LogStore` 的进程内字段，
        //   进程一起来就是 0；而同一个包里还带着 `app.log`，那是**跨多次运行
        //   累积**的（`app.log` 不随启动清空，只按 1MB 轮转）。
        //   于是出现过这种自相矛盾：`log-completeness` 报「日志未发生限流或丢弃」，
        //   而包里 `app.log` 里明明躺着「…上一条同类日志在 1000ms 内被抑制 N 次」。
        //   实测 65 个包里 **9 个**是这样。
        //   原文案「日志未发生限流或丢弃」把「本次运行没限流」说成了「日志完整」，
        //   分析者据此会认为 app.log 是逐条完整的 —— 而它被抽过稀。
        runCatching {
            val throttled = LogStore.throttledTotal
            val dropped = LogStore.droppedAsync
            if (throttled > 0 || dropped > 0) {
                checks.add(
                    Check(
                        "log-completeness", "warn",
                        "本次运行日志被抽稀：限流丢弃 $throttled 条，队列满丢弃 $dropped 条" +
                            "（说明有高频重复日志）。⚠️ 更早的运行没有计数，只能看 app.log 里的抑制行。",
                    ),
                )
            } else {
                checks.add(
                    Check(
                        "log-completeness", "ok",
                        "本次运行日志未发生限流或丢弃。" +
                            "⚠️ 这不等于 app.log 逐条完整：它是跨多次运行累积的，" +
                            "更早的运行若被抽过稀，只会在里面留下「…被抑制 N 次」的行。",
                    ),
                )
            }
        }

        // ④ 落盘是否可用
        //
        // ⚠️ **必须区分两种失效形态**（2026-10-14 真机复现后写死）。`fileLoggingEnabled`
        //   是 `dir != null && appWriter != null`，把两件不同的事压成了一个布尔：
        //   ① 目录没挂上（`LogStore.init` 失败）⇒ `app.log`/`engine.log`/`web.log`
        //      三路都不写；
        //   ② 目录在、只是 `app.log` 的句柄被 `noteFailure` 关了 ⇒ `engine.log`
        //      / `web.log` 照旧在写。
        //   **但两种形态下都有一批文件照旧落盘** —— 真机把 `logs/app.log` 换成一个
        //   目录（`init` 报 `EISDIR` 失败、`LogStore.dir = null`）后实测：
        //   `events.jsonl` 仍在长（其中 **6 行属于本次启动**，bootId 对得上）、
        //   `crash/` 里的现场文件照旧存在。因为 `EventLog.init` 与
        //   `CrashRecorder.install` 各自持有**自己的** `logDir`，不光看 `LogStore.dir`。
        //   原文案只有一句「文件日志不可用（磁盘满/只读/权限），只有内存缓冲」，
        //   三重错误：
        //   - 事实错①：形态①下 `events.jsonl` / `crash/` 仍在落盘；
        //   - 事实错②：形态②下 `engine.log` / `web.log` 也在落盘；
        //   - 原因错：本文件 L601 的设计原则明写「只报事实，不猜原因」，
        //     而括号里那三个原因一个都没被本方法检查过，是凭空断言。
        //   后果很具体：读者读到「只有内存缓冲」，就不去翻 `engine.log` 与
        //   `events.jsonl` —— 而引擎的报错**只**在 `engine.log` 里，
        //   「崩在哪一步」**只**在 `events.jsonl` 里。这条 check 把有用的证据藏了。
        runCatching {
            when {
                LogStore.logDirAvailable && LogStore.appLogEnabled -> checks.add(
                    Check("file-logging", "ok", "文件日志可用（app.log 与 engine.log/web.log 都在写）"),
                )
                !LogStore.logDirAvailable -> checks.add(
                    Check(
                        "file-logging", "fail",
                        "日志目录未挂上（LogStore.init 失败）：本次运行 app.log / engine.log / web.log 都不写。" +
                            "⚠️ 包内若仍有 engine.log / web.log，那是**上一次**运行留下的残卷（文件不随启动清空），" +
                            "别把它们当成本次运行的日志读；本次的时间线看 timeline.jsonl、崩溃现场看 crashes/。" +
                            "events.jsonl 与 crash/ 由 EventLog / CrashRecorder 各自持目录，本次仍照常落盘。",
                    ),
                )
                else -> checks.add(
                    Check(
                        "file-logging", "warn",
                        "app.log 句柄已关闭（连续 3 次写失败后由 LogStore.noteFailure 关掉），此后本次运行不再写 app.log；" +
                            "engine.log / web.log / events.jsonl / crash/ 仍照常落盘 —— 引擎的报错在 engine.log 里，别跳过它。",
                    ),
                )
            }
        }

        // ⑤ 磁盘空间 —— 解包失败的常见原因
        //
        // ⚠️ **这是「打包那一刻」的瞬时值，不是「出故障时」的值。**
        //   崩溃类诊断包是**下次启动后**才打的（见 `LogUploader.collectCrashScenes`：
        //   崩溃当场只有几秒预算，打不了包），所以包里的磁盘/内存读数来自一个
        //   **崩溃之后新起的进程**。实测 `20261013-230025-0dedc366`：包生成于
        //   23:00:23，而包里 8 份现场的时间戳是 21:46~22:45 —— 相差最久 1 小时 14 分。
        //   若不写明，分析者会把「剩余 79901MB」「堆占用 12MB」当成故障现场的内存
        //   与磁盘状况，从而排除掉「磁盘满/内存不足」这两个真正可能的原因。
        runCatching {
            val ctx = LogContext.get()
            if (ctx != null) {
                val fs = android.os.StatFs(ctx.filesDir.absolutePath)
                val freeMb = fs.availableBytes / (1024 * 1024)
                checks.add(
                    if (freeMb < 200) {
                        Check(
                            "disk-space", "warn",
                            "打包时数据分区剩余 ${freeMb}MB，低于 200MB —— 解包可能失败。" +
                                "⚠️ 这是**打包时刻**的读数，不是故障发生时的。",
                        )
                    } else {
                        Check("disk-space", "ok", "打包时数据分区剩余 ${freeMb}MB（打包时刻的瞬时值，非故障时刻）")
                    },
                )
            }
        }

        // ⑥ 内存
        //
        // ⚠️ 同上：这是**打包那一刻**的堆占用。崩溃包是重启后打的，所以它反映的是
        //   「重启后这个新进程占了多少」，**不是**崩溃进程死前的占用 ——
        //   拿它排除 OOM 会得到相反结论（实测崩溃包里的值普遍只有 3~13MB，
        //   而含 8 份现场的包写的是 159MB，两者都不是崩溃进程的真实占用）。
        runCatching {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            checks.add(
                if (usedMb * 100 / maxMb > 85) {
                    Check(
                        "heap", "warn",
                        "打包时堆占用 ${usedMb}MB / ${maxMb}MB（超过 85%），有 OOM 风险。" +
                            "⚠️ 这是**打包时刻**的读数，不是故障发生时的。",
                    )
                } else {
                    Check(
                        "heap", "ok",
                        "打包时堆占用 ${usedMb}MB / ${maxMb}MB" +
                            "（打包时刻的瞬时值，不是故障进程死前的占用 —— 崩溃包是重启后才打的）",
                    )
                },
            )
        }

        // ⑦ 救援层级（P5）
        //
        // ## 🔴 为什么必须有这一项
        //
        // 救援机制（见 `docs/RESCUE-BOOT-PLAN.md`）让 App 在插件坏掉时**降级启动**：
        // 「已隔离可疑插件」/「救援模式」/「安全模式」。降级本身是好事，
        // 但它会引入一种**新的静默**：用户此刻看到的是**功能不全**的界面，
        // 而诊断包里没有任何一项能说明这件事 —— 6 个检查全 ok、headline
        // 写「未发现异常」，而用户其实少了一批插件。
        //
        // 这正是本项目已确认 28 例的缺陷族（「结论与它描述的对象不是同一个」）：
        // 「未发现异常」描述的是**日志**，而它会被读成**App 状态正常**。
        // 加了本项之后，降级状态第一次在报告里**可见**。
        //
        // ⚠️ 判据是「**当前**处于哪个层级」，不是「历史上降级过几次」：
        //   `state.json` 是**当前状态**（成功启动会把它清回 normal），
        //   所以它天然对齐「此刻」—— 与 ② 里那条「必须按 bootId 对齐、
        //   不能拿历史现场当现状」是同一个道理。
        //
        // 🔴 **但「层级是 normal」不等于「插件全部加载了」。**（2026-10-15 修，同族第 29 例）
        //
        //   层级只说明**救援阶梯没介入**（`RescueBoot` 没降级、没隔离、没摘 bundle）。
        //   插件**仍然可以因为别的原因没加载** —— 最常见的是引擎自己判版本不兼容而
        //   整行禁用：
        //
        //   ```
        //   dsh: disabling profile plugin row "llm": Plugin @deepseek-ai/dsh-llm@0.1.0-rc.6
        //        is incompatible with dsh 0.2.0-rc.2 …
        //   dsh: disabling profile plugin row "settings": …
        //   agent-loop (required)      llm
        //   dsh: startup failed: 1 required plugin did not activate
        //   ```
        //
        //   此时 tier 是 normal、`state.json` 里 `removedBundles: []`、
        //   `RescueBoot` 一次都没记过失败 —— 而用户面对的是**界面打不开**。
        //   原文案「正常启动（未降级）：插件全部按配置加载」把「阶梯没介入」
        //   说成了「插件都加载了」，是**替一个本项没查过的事实作保**。
        //
        //   真机现场（`20261014-210912-87475efa`，vivo V2232A / Android 15 /
        //   v21 system / `debuggable:false`）**同一份** `diagnosis.json` 里：
        //     · `crash-scenes`: fail「最近一次启动的引擎**没能起来**（界面打不开）」
        //     · `boot-tier`  : ok  「正常启动（未降级）：插件全部按配置加载」
        //   两条直接打架。实测 564 个包里 **24 个**（全部 `debuggable:false`
        //   的真实用户）同时出现这两句。
        //
        //   修法：层级照旧由 tier 判（那确实是本项的职责），但文案**只陈述
        //   它真正查过的东西**；「引擎这次有没有起来」另有 `crash-scenes` 负责，
        //   这里不再替它说话。
        runCatching {
            // ⚠️ `RescueBoot.tier()` 返回的是 **State**（含 tier/since/reason/…），
            //   不是 Tier 本身 —— 名字短，容易读错。这里取字段。
            val st = RescueBoot.tier()
            val since = RescueBoot.stateSinceText()
            checks.add(
                when (st.tier) {
                    RescueBoot.Tier.NORMAL -> Check(
                        "boot-tier", "ok",
                        "未进入救援阶梯（层级 normal）：没有摘除任何插件、" +
                            "没有切换到 rescue profile。⚠️ 这只说明**阶梯没介入**，" +
                            "不等于插件全部加载 —— 引擎自己判版本不兼容而禁用插件行时" +
                            "层级仍是 normal。本次引擎是否真的起来了看 crash-scenes 那一项。",
                    )
                    // 降级了但界面能用 ⇒ 不是故障，但**必须让人看见**
                    RescueBoot.Tier.QUARANTINED -> Check(
                        "boot-tier", "warn",
                        "以「已定向隔离坏插件」启动${since?.let { "（自 $it）" } ?: ""}：" +
                            "App 能打开，但被禁用的插件条目**没有加载**。" +
                            "已禁用的条目见 rescue/state.json 的 quarantinedNames，" +
                            "执行层是 quarantine.patch.yml 的 disabled: true（文件没删）",
                    )
                    RescueBoot.Tier.ISOLATED -> Check(
                        "boot-tier", "warn",
                        "以「已隔离可疑插件」启动${since?.let { "（自 $it）" } ?: ""}：" +
                            "App 能打开，但被隔离的插件**没有加载**。" +
                            "已摘除的 bundle 见 rescue/state.json 的 removedBundles",
                    )
                    // 救援/安全模式：第三方插件全部不加载 —— 用户面对的是"能打开但不完整"
                    RescueBoot.Tier.RESCUE, RescueBoot.Tier.SAFE_MODE -> Check(
                        "boot-tier", "fail",
                        "以「${st.tier.label}」启动${since?.let { "（自 $it）" } ?: ""}：" +
                            "**第三方插件全部未加载**（App 刻意只加载核心 bundle 以保住「打得开」）。" +
                            "这不是日志问题而是当前功能状态 —— 详见 rescue/state.json",
                    )
                    // ⚠️ 应急模式与上面两者的**性质不同**，不能合并成一条：
                    //   上面是「同一套内核换了配置」，这里是「**换了内核**」。
                    //   排查时方向完全相反 —— 前者去查插件配置，后者要查一号内核
                    //   （engine/ 或 engine.bin 资产）本身。
                    RescueBoot.Tier.EMERGENCY -> Check(
                        "boot-tier", "fail",
                        "以「${st.tier.label}」启动${since?.let { "（自 $it）" } ?: ""}：" +
                            "**已改用二号内核的独立依赖树（engine-e/）**。" +
                            "一号内核（engine/ 或 engine.bin 资产）可能已损坏 —— " +
                            "重解包也救不回来才会走到这里。数据未丢失，" +
                            "但当前是旧版本环境，一号修好后应尽快切回。",
                    )
                },
            )
        }

        // ⑧ 调用方给的检查（资产版本戳、补丁锚点、插件激活 —— EngineManager 知道这些）
        checks.addAll(extra)

        val ok = checks.count { it.status == "ok" }
        val warn = checks.count { it.status == "warn" }
        val fail = checks.count { it.status == "fail" }

        return JSONObject().apply {
            put("generatedAt", System.currentTimeMillis())
            put("generatedAtText", fmt.format(Date()))
            put("bootId", BootState.bootId)
            put("summary", JSONObject().apply {
                put("ok", ok)
                put("warn", warn)
                put("fail", fail)
                // 一句话结论，给不看细节的人
                put(
                    "headline",
                    when {
                        fail > 0 -> "发现 $fail 项严重问题，详见 checks"
                        warn > 0 -> "发现 $warn 项需要注意的情况"
                        else -> "未发现异常"
                    },
                )
            })
            put("checks", JSONArray(checks.map { it.toJson() }))
        }
    }

    /**
     * 从 `diagnosis.json` 里提炼一行人类可读的摘要。
     *
     * 给日志界面顶部与上传成功提示用 —— 用户看到「发现 2 项严重问题」比看到
     * 文件名有用得多。
     */
    fun summarize(diagnosisJson: String): String = runCatching {
        val o = JSONObject(diagnosisJson)
        val s = o.optJSONObject("summary") ?: return@runCatching ""
        "ok=${s.optInt("ok")} warn=${s.optInt("warn")} fail=${s.optInt("fail")} —— ${s.optString("headline")}"
    }.getOrDefault("")

    /** 诊断包格式版本。将来改结构时服务器靠它分流。 */
    const val SCHEMA_VERSION = 1
}
