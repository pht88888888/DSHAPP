package com.dsh.android.log

import com.dsh.android.DshApplication
import com.dsh.android.R
import android.util.Log as AndroidLog
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 诊断包上传 —— 把「用户报问题」从「来回问三轮」压成「一次上传」。
 *
 * ## 三条上传路径（对着调用点逐个抄的）
 *
 * | # | 触发 | 入口 | 做什么 |
 * |---|---|---|---|
 * | ① | 每次启动 | [collectCrashScenes] | 把 `crash/` 里**尚未打包**的现场打包 → 入队 `pending/` |
 * | ② | 每次启动（紧接 ①） | [retryPending] | 把 `pending/` 里的包逐个传出去 |
 * | ③ | 用户点「上传」 | [upload]（由 `LogViewerActivity` 调） | 现打一个新鲜包 → 入队 → 立刻传 |
 *
 * ⚠️ **① 与 ② 是同一次启动里的两步，不是两种时机**（2026-10-14 自检发现）。
 *   原表把「崩溃后 · 下次启动时扫 `pending/` 自动补传」与「重试 · 启动时扫
 *   `pending/`」并列成两行 —— 同一个 `retryPending` 调用被数成了两种时机，
 *   而真正的第①步（崩溃现场补打包，「崩溃自动上传」这条链路能不能成立全看它）
 *   反而没有单独列出来，只在第一行的机制描述里被含糊带过。照原表读会以为
 *   「崩溃后」走的是与「重试」不同的代码路径，去 `pending/` 找崩溃包即可 ——
 *   而崩溃当场根本不入队，`pending/` 里那个包是**下次启动**由 [collectCrashScenes]
 *   现打的（[DiagnosticBundle.build] 带 `reason = "crash"`）。
 *   两者都在 `DshApplication.uploadPendingBundles` 里顺序执行，本身就是铁证。
 *
 * ## ⚠️ 崩溃时为什么不能当场上传（这是平台限制，不是实现偷懒）
 *
 * 崩溃处理器跑在**即将死掉的进程**里，只有几秒预算，而上传要：
 * 建连接（可能几秒）→ 传 8MB（移动网络下几十秒）→ 等响应。
 * 在这期间进程就被系统收走了，而且 Android 不允许在崩溃路径上可靠地起后台任务
 * （`WorkManager` 也要等下次启动）。
 *
 * 所以崩溃时的正确做法是**只落盘 + 标记待传**，真正的上传交给**下次启动**。
 *
 * ⚠️ **诚实说明它的边界**：如果用户崩溃后再也不打开 App，这个包就传不出去。
 * 没有任何绕过的办法（除非有常驻的独立进程 —— 那远超本功能的收益）。
 * 所以界面上的手动上传入口**不是可选项**，是必需的补充。
 *
 * ## 与服务器解耦
 *
 * 端点与令牌都可由调用方设置（[configure]）。未配置时所有上传请求**立即返回
 * 明确失败**（`NOT_CONFIGURED`），而不是静默不做事 —— 静默失败会让用户以为
 * 「点了没反应」，那是最难查的一类反馈。
 */
object LogUploader {

    private const val TAG = "DSH/LogUploader"

    /** 待传目录（`logs/pending`）。 */
    private const val PENDING_DIR = "pending"

    /**
     * 补传用的**跨进程**锁文件（`logs/pending/.retry.lock`）。
     *
     * ⚠️ **必须以 `.lock` 结尾**：[pendingCount] 与 `retryPendingLocked` 都只把
     *   `.zip` 当待传包，用别的后缀（尤其是 `.zip`）会让锁文件自己被当成一个
     *   永远传不出去的包，每次启动都白传一次。
     */
    private const val PENDING_LOCK_FILE = ".retry.lock"

    /** 手动导出目录（`logs/export`）。 */
    private const val EXPORT_DIR = "export"

    /**
     * 已打包现场集合的标记文件（`logs/crash/.queued`）。
     *
     * ⚠️ **不能以 `.txt` 结尾** —— `CrashRecorder.listScenes()` 与
     *   [collectCrashScenes] 都按 `.txt` 认现场文件，用 .txt 会让标记文件
     *   把自己算进集合，签名永远在变、每次启动都重复打包。
     */
    private const val QUEUED_MARKER = ".queued"

    /** 重试上限。超过就移到 `failed/`，不再自动重试（避免无限占用流量）。 */
    private const val MAX_ATTEMPTS = 3

    /** 单次上传的超时。移动网络下 8MB 需要的时间。 */
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    /** 上传端点。null = 未配置。 */
    @Volatile
    private var endpoint: String? = null

    /** 鉴权令牌。null = 不带。 */
    @Volatile
    private var token: String? = null

    /** 匿名设备指纹 —— 用来把同一台机器的多次上报关联起来。 */
    @Volatile
    private var deviceId: String = ""

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-log-upload").apply { isDaemon = true }
    }

    /** 是否正在上传（界面据此禁按钮，避免重复点）。 */
    private val uploading = AtomicBoolean(false)

    val isUploading: Boolean get() = uploading.get()

    /**
     * 配置上传参数。
     *
     * @param endpoint 服务器地址；空串或 null = 未配置。
     * @param token 鉴权令牌；可空。
     */
    fun configure(endpoint: String?, token: String?, logDir: File) {
        this.endpoint = endpoint?.takeIf { it.isNotBlank() }
        this.token = token?.takeIf { it.isNotBlank() }
        // 设备指纹：**不是** IMEI/序列号这类需要权限的标识（本工程 targetSdk=28，
        // 也不该收集）。用「安装时生成一次的随机 UUID」—— 它能区分设备，
        // 但无法反查到人，且卸载重装即变。
        deviceId = loadOrCreateDeviceId(logDir)
    }

    private fun loadOrCreateDeviceId(logDir: File): String = runCatching {
        // 名字取自 [LogStore.DEVICE_ID_FILE]，别在这里另写一份字面量 ——
        // `enforceDirQuota` 靠同一个名字豁免它，两处漂移就会把它删掉（见那里的注释）。
        val f = File(logDir, LogStore.DEVICE_ID_FILE)
        if (f.isFile) {
            f.readText().trim().takeIf { it.isNotEmpty() }?.let { return@runCatching it }
        }
        val id = UUID.randomUUID().toString()
        runCatching { f.writeText(id) }
        id
    }.getOrDefault("")

    /** 上传是否可用（端点已配置）。 */
    val configured: Boolean get() = !endpoint.isNullOrBlank()

    /** 待传包数量（界面显示「N 份待上传」）。 */
    fun pendingCount(logDir: File): Int =
        File(logDir, PENDING_DIR).listFiles()?.count { it.isFile && it.name.endsWith(".zip") } ?: 0

    /**
     * 把诊断包放进待传队列。
     *
     * ⚠️ **不是复制而是移动**：包已经在 `export/` 里放着，再复制一份会双倍占用
     * 磁盘。移动失败（跨文件系统）时才退回复制。
     */
    fun enqueue(bundle: File, logDir: File): File? = runCatching {
        val dir = File(logDir, PENDING_DIR).apply { mkdirs() }
        val target = File(dir, bundle.name)
        if (target.absolutePath == bundle.absolutePath) return@runCatching target
        if (bundle.renameTo(target)) return@runCatching target
        bundle.copyTo(target, overwrite = true)
        bundle.delete()
        target
    }.getOrNull()

    /**
     * 上传一个包。**回调保证被调用一次**（成功或失败都调）。
     *
     * @param onResult (成功?, 给用户看的说明)
     */
    fun upload(bundle: File, reason: String, onResult: (Boolean, String) -> Unit) {
        val ep = endpoint
        if (ep.isNullOrBlank()) {
            // 未配置 → 明确失败，不静默。见类注释。
            onResult(false, DshApplication.instance.getString(R.string.logs_server_unconfigured))
            return
        }
        if (!uploading.compareAndSet(false, true)) {
            onResult(false, DshApplication.instance.getString(R.string.logs_upload_busy))
            return
        }
        executor.execute {
            try {
                val (ok, msg) = doUpload(ep, bundle, reason)
                // ⚠️ 成功后**必须删掉 pending/ 里的那一份**，否则下次启动
                //   [retryPending] 会把同一个包再传一遍。
                //
                //   这个坑真踩过（服务器上留了铁证）：两份包的 sha256 完全相同，
                //   一份 reason=manual、一份 reason=startup-retry，间隔 2 分钟 ——
                //   同一份诊断包被传了两次，白白浪费用户流量，也让服务端多一份重复。
                //   手动上传与自动补传必须共用「成功即出队」这一个语义。
                if (ok) runCatching { bundle.delete() }
                onResult(ok, msg)
            } catch (t: Throwable) {
                onResult(false, DshApplication.instance.getString(R.string.logs_upload_error, t.message))
            } finally {
                uploading.set(false)
            }
        }
    }

    /**
     * 启动时补传：把 `pending/` 里的包逐个传出去，成功的删掉。
     *
     * ⚠️ **必须异步、必须在引擎启动之外**：它会发网络请求，放在启动路径上会
     * 拖慢启动（而用户此刻正等着界面出来）。调用方自己起线程或用
     * [com.dsh.android.DshApplication.applicationScope]。
     *
     * ⚠️ 失败**不删除**：留着下次再试。只有重试次数超限才移到 `failed/`。
     */
    fun retryPending(logDir: File, onEach: ((File, Boolean, String) -> Unit)? = null) {
        if (!configured) return
        val dir = File(logDir, PENDING_DIR)
        // ⚠️ **补传必须跨进程互斥，只有 `uploading` 是不够的。**
        //
        //   `uploading` 是 `AtomicBoolean`，**只在进程内有效**。而崩溃循环里
        //   会有**多个主进程实例在时间上重叠**，各自跑一遍
        //   `uploadPendingBundles()` → 各扫一遍同一个 `pending/`，
        //   于是同一个包被两个进程同时处理。
        //
        //   服务器上有铁证（`20261014-125152-8e063967`，gecko flavor）：
        //   同一个 `diagnostic-20261014-122723-…-3a7c.zip`
        //     12:27:23.321  [Upload] #15 worker-3  诊断包上传成功 code=200
        //     12:27:23.335  [Upload] #8  worker-1  诊断包上传异常 … open failed: ENOENT
        //   相隔 14 ms。两条日志的 `#seq` 分属**两条独立的序号流**（`#15` 之后
        //   又出现 `#8`），而 `LogStore.seq` 是**进程内**计数 —— 同进程内它只增
        //   不减，所以这必然是**两个进程**。
        //
        //   是「多个主进程实例重叠」而不是「GeckoView 的内容进程」：
        //   `DSH application created` 打在 `onCreate` 里、且**位于
        //   `if (!isMainProcess(this)) return` 之后**，同一份 app.log 里它出现
        //   19 次 ⇒ 19 次都是主进程。再对着时序看，前一个实例还没走完
        //   （`#15` 上传成功）后一个实例已经在跑（`#8` 上传异常），
        //   即**新旧主进程实例在时间上重叠** —— 崩溃循环里系统会重新拉起进程，
        //   而上一个还没死透。
        //   ⚠️ 别拿「日志里没有『非主进程启动』」当反证：那一行走的是
        //      `android.util.Log.i`，只进 logcat，**不落 app.log**，
        //      在诊断包里必然是 0 次，证明不了任何事。
        //
        //   判别性很强：**全量 554 个包里含该异常的 51 个全部是 gecko，
        //   system flavor 一个都没有**（gecko 启动更慢，重叠窗口更大）。
        //
        //   危害有三条，都不只是噪音：
        //   ① 报出「上传失败」但那个包其实**已经传成功了**（结论与事实相反）；
        //   ② 该包已不存在，`bumpAttempts()` 却仍在 `pending/` 里给它写一个孤儿
        //      `.attempts` 文件，越积越多；
        //   ③ 白打一条误导性日志，把真正的问题（进程重叠）掩盖成「网络/IO 抖动」。
        //
        //   机制照抄本项目既有的 `TermuxBootstrap.withPrefixFileLock`（那里用同一
        //   手法解决多进程并发引导 prefix，KDoc 写了「进程内锁保证同进程
        //   串行、文件锁保证跨进程串行，两层缺一不可」）：`FileChannel.lock()` 在
        //   Android 上落到 `fcntl(F_SETLKW)`，**随进程退出自动释放**，不会因为
        //   持锁进程被系统杀掉而留下死锁 —— 这一点对崩溃循环尤其要紧。
        //
        //   ⚠️ 用 `tryLock()` 而不是 `lock()`：这是**启动路径**上的补传，让第二个
        //      进程在这里阻塞等几十秒（8MB 在移动网络下的耗时）会拖慢它的启动，
        //      而用户此刻正等着界面出来。拿不到就如实跳过，下次启动再试。
        //   ⚠️ 锁文件放在 `pending/` 里、以 `.lock` 结尾：`pendingCount()` 与
        //      `retryPendingLocked` 都只认 `.zip`，不会被它算成待传包。
        val lockFile = File(dir, PENDING_LOCK_FILE)
        lockFile.parentFile?.mkdirs()
        val raf = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull() ?: return
        raf.use { handle ->
            val lock = runCatching { handle.channel.tryLock() }.getOrNull()
            if (lock == null) {
                // ⚠️ 文案**不能**写死「另一个进程」：同一个进程内重复进这里时，
                //   `FileChannel.tryLock()` 会抛 `OverlappingFileLockException`
                //   （同一 JVM 不允许对同一文件重复加锁），也会走到这个分支。
                //   两种情形都如实概括，别把其中一种说成另一种 —— 本项目的
                //   缺陷族里「结论与它描述的对象不是同一个」占了大半。
                LogStore.app(
                    'W', "Upload",
                    "另一个上传正在进行（可能是另一进程的补传），本次跳过" +
                        "（待传 ${pendingCount(logDir)} 份，下次启动会再试）",
                )
                return
            }
            try {
                retryPendingLocked(dir, onEach)
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /**
     * [retryPending] 的实现体，**必须已持有跨进程锁**。
     *
     * ⚠️ 单独抽出来只是为了把锁的获取/释放收在 [retryPending] 一个出口，
     *   不要在别处调用它。
     */
    private fun retryPendingLocked(dir: File, onEach: ((File, Boolean, String) -> Unit)?) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: return
        if (files.isEmpty()) return
        // 按时间从旧到新：先传早的，让服务器按顺序看到问题演变。
        for (f in files.sortedBy { it.lastModified() }) {
            // ⚠️ **被别的上传占着时要说话，不能静默放弃整批。**
            //   原来这里是 `return`：手动导出正在上传时，整批补传一份都不做，
            //   而且 `onEach` 不触发、一行日志都没有 —— 调用方
            //   （`DshApplication.uploadPendingBundles`）打完「发现 N 份待上传」
            //   之后就再无输出，看起来像「传完了」或「本来就没事」。
            //   现在记一条 Warn 说明原因，并把这次放弃如实说出来。
            if (!uploading.compareAndSet(false, true)) {
                LogStore.app(
                    'W', "Upload",
                    "已有上传在进行中，本次补传跳过（待传 ${files.size} 份，下次启动会再试）",
                )
                return
            }
            try {
                // ⚠️ 判据是「文件还在不在」，不是「能不能打开」。跨进程锁已经
                //   保证不会有两个补传同时跑，但**手动上传**（`upload()`，用户点
                //   日志界面的按钮）走的是另一条路、不持这把锁，它完全可能在
                //   这里两行之间把包传完删掉。少这一句就会在同一个地方报出
                //   一模一样的假 ENOENT。
                if (!f.isFile) {
                    LogStore.app('I', "Upload", "待传包已被别的上传处理掉，跳过: ${f.name}")
                    continue
                }
                val (ok, msg) = doUpload(endpoint!!, f, "startup-retry")
                onEach?.invoke(f, ok, msg)
                if (ok) {
                    f.delete()
                } else {
                    val attempts = bumpAttempts(f)
                    if (attempts >= MAX_ATTEMPTS) {
                        val failedDir = File(dir.parentFile, "failed").apply { mkdirs() }
                        // ⚠️ **必须看 renameTo 的返回值**（2026-10-14 自检查出）。
                        //   `File.renameTo()` 失败时只返回 false、不抛异常，而这里的
                        //   日志原文写死了「已移入 failed/」—— 于是移动失败时：
                        //   ① 包仍留在 `pending/`，下次启动还会被当成待传（与注释
                        //      「不再自动重试」矛盾）；② 日志却报告已经移走，
                        //      分析者据此以为这批包已经归档，实际还在反复占流量。
                        //   两条都是「结论与事实不符」，只是方向相反。
                        val moved = runCatching { f.renameTo(File(failedDir, f.name)) }
                            .getOrDefault(false)
                        if (moved) {
                            LogStore.app(
                                'W', "Upload",
                                "待传包重试 $attempts 次仍失败，已移入 failed/: ${f.name}",
                            )
                        } else {
                            LogStore.app(
                                'W', "Upload",
                                "待传包重试 $attempts 次仍失败，且移入 failed/ 未成功" +
                                    "（rename 返回 false，多半是磁盘满/权限）：${f.name} 仍留在 pending/，" +
                                    "下次启动会再试一次",
                            )
                        }
                    }
                }
            } finally {
                uploading.set(false)
            }
        }
    }

    /**
     * 用同名 `.attempts` 文件记重试次数（不改动 zip 本身）。
     *
     * ⚠️ **写失败时不能返回 0。** 调用处是 `if (attempts >= MAX_ATTEMPTS)`，返回 0
     *   会让这个判据**恒假**，于是 `MAX_ATTEMPTS` 与 `failed/` 永不生效 ——
     *   每个启动都把全部 pending 重试一遍，无限耗流量，且这条失败无任何日志。
     *   而 `.attempts` 写不进去的典型原因正是**磁盘满**，也就是本系统要处理的核心
     *   故障场景 —— 最需要它生效的时候它恰好失效。
     *
     *   修法：计数先自增、再尝试落盘；**落盘失败也把自增后的值返回**（内存里的判断
     *   依然正确），并记一条 Warn 让分析者知道持久化没成功（下次启动会从头计）。
     */
    private fun bumpAttempts(bundle: File): Int = runCatching {
        val f = File(bundle.parentFile, "${bundle.name}.attempts")
        val n = (f.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        runCatching { f.writeText(n.toString()) }
            .onFailure { t ->
                LogStore.app(
                    'W', "Upload",
                    "重试计数无法落盘（${t.javaClass.simpleName}: ${t.message}），" +
                        "本次按第 $n 次处理；下次启动会从头计。",
                )
            }
        n
    }.getOrDefault(MAX_ATTEMPTS)

    /**
     * 启动时把**尚未打包**的崩溃现场打成诊断包并入队。
     *
     * ## 为什么必须在这里做，而不是在崩溃处理器里
     *
     * 崩溃处理器只有几秒预算，而打一个包要读几 MB 日志、压缩、写盘 —— 是重活。
     * 原来的设计里 `CrashRecorder` **从来没有调用过** [stashForCrash]（那个方法
     * 定义了却无人调用），于是 `pending/` 永远空着，`retryPending` 什么都传不出去：
     * **「崩溃自动上传」这条链路从来没有生效过**。服务器上收到的包 `reason` 只有
     * `manual` 与 `startup-retry`，没有任何一个 `crash`，就是铁证。
     *
     * 所以改成：崩溃当场只写现场文件（快、可靠），**下次启动**在这里补打包 + 入队。
     * 与 [retryPending] 的注释所述边界一致 —— 用户崩溃后再也不打开 App，
     * 这个包就传不出去，那是平台限制。
     *
     * ## 幂等判据
     *
     * `crash/.queued` 里记着**已打包过的现场文件名**（排序后）。集合没变就跳过，
     * 于是同一批现场不会被反复打包。有新现场（或清空后又有）才重新打包。
     *
     * ⚠️ **返回 null 有两种含义，必须区分**：①「没有新现场」（正常，常态）；
     *   ②「有新现场但打包/入队失败」（异常，**必须留痕**）。原来这两者都只是
     *   一个 `return@runCatching null`，而唯一的调用方
     *   （`DshApplication.uploadPendingBundles`）**忽略返回值** —— 于是磁盘满 /
     *   `export/` 建不出来时，那批崩溃现场**永远不会被上传，也一个字都不说**，
     *   表现与「本来就没有现场」完全相同。崩溃现场是这个系统的核心数据，
     *   悄悄不传等于把用户的求助信号丢掉。
     *   ②现在各记一条 Warn（`W` 而不是 `E`：不传不等于崩，但用户/分析者必须知道）。
     *
     * @return 入队的包；没有新现场时返回 null（正常）；失败时也返回 null，但会记日志。
     */
    fun collectCrashScenes(logDir: File): File? = runCatching {
        val crashDir = File(logDir, "crash")
        // ⚠️ 只认 .txt：标记文件本身不能是 .txt，否则会被自己算进「现场集合」。
        val scenes = crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: return@runCatching null
        if (scenes.isEmpty()) return@runCatching null

        val marker = File(crashDir, QUEUED_MARKER)
        val signature = scenes.joinToString("\n") { it.name }
        val prev = runCatching { marker.takeIf { it.isFile }?.readText() }.getOrNull()
        if (prev == signature) return@runCatching null // 没有新现场（常态）

        val outDir = File(logDir, EXPORT_DIR).apply { mkdirs() }
        val bundle = DiagnosticBundle.build(logDir, outDir, "crash")
        if (bundle == null) {
            LogStore.app('W', "Upload", "崩溃现场打包失败，本次 ${scenes.size} 份现场不会上传（下次启动会重试）")
            return@runCatching null
        }
        val queued = enqueue(bundle, logDir)
        if (queued == null) {
            LogStore.app('W', "Upload", "诊断包入队失败（${bundle.name}），本次 ${scenes.size} 份现场不会上传")
            return@runCatching null
        }
        // ⚠️ 标记写在**入队成功之后**：反过来会「标记了但没入队」，那批现场就
        //   永远不会再被尝试。
        runCatching { marker.writeText(signature) }
        LogStore.app('I', "Upload", "崩溃现场已打包入队: ${queued.name}（${scenes.size} 份现场）")
        queued
    }.onFailure { t ->
        // 外层兜底：上面任何一步意外抛异常（`listFiles` 权限、OOM…）也要留痕。
        LogStore.app('W', "Upload", "收集崩溃现场时异常（本次不会上传）: ${t.javaClass.simpleName}: ${t.message}")
    }.getOrNull()

    // ── 真正的上传 ────────────────────────────────────────────────────────────

    /**
     * 发一次 multipart/form-data 请求。
     *
     * ## 为什么手写 multipart 而不用现成库
     *
     * 本工程**没有任何网络库依赖**（连 OkHttp 都没有，全工程用 `HttpURLConnection`，
     * 见 `MainActivity.handleDownload`）。为一个上传功能引入依赖不划算，而
     * multipart 的格式很简单。照抄项目既有写法。
     *
     * @return (成功?, 给用户看的说明)
     */
    private fun doUpload(ep: String, bundle: File, reason: String): Pair<Boolean, String> {
        val boundary = "----DSHLog" + UUID.randomUUID().toString().replace("-", "")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }

            conn.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                field("device", deviceId)
                field("version", VersionInfo.versionName)
                field("versionCode", VersionInfo.versionCode.toString())
                field("flavor", VersionInfo.flavor)
                field("reason", reason)
                field("schema", DiagnosticBundle.SCHEMA_VERSION.toString())
                field("bootId", BootState.bootId)

                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    ("Content-Disposition: form-data; name=\"file\"; filename=\"${bundle.name}\"\r\n" +
                        "Content-Type: application/zip\r\n\r\n").toByteArray(),
                )
                bundle.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val body = runCatching { conn.inputStream.bufferedReader().readText().take(500) }.getOrDefault("")
                // 服务器若回了 id，把它显示给用户 —— 反馈问题时报这个编号最快。
                val id = runCatching { JSONObject(body).optString("id", "") }.getOrDefault("")
                LogStore.app('I', "Upload", "诊断包上传成功: ${bundle.name} code=$code id=$id")
                Pair(true, if (id.isNotEmpty()) DshApplication.instance.getString(R.string.logs_upload_success_id, id) else DshApplication.instance.getString(R.string.logs_upload_success_http, code))
            } else {
                // ⚠️ `errorStream` 本身可能为 null（连接就断了），readText 的结果也是可空的，
                //   所以这里必须补一个 `?: ""`，否则拼接时拿到的是 null 字面量。
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText()?.take(300) }
                    .getOrNull().orEmpty()
                LogStore.app('W', "Upload", "诊断包上传失败: ${bundle.name} code=$code $err")
                Pair(false, DshApplication.instance.getString(R.string.logs_upload_http_error, code, err.take(120)))
            }
        } catch (t: Throwable) {
            LogStore.app('W', "Upload", "诊断包上传异常: ${bundle.name} ${t.message}")
            Pair(false, DshApplication.instance.getString(R.string.logs_upload_retry, t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
