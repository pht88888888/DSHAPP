package com.dsh.android

import android.content.Context
import com.dsh.android.log.DshLog as Log
import java.io.File

/**
 * 无线调试（Android 11+）的 Kotlin 侧支持：**端口发现**与**配对**。
 *
 * ## 为什么端口发现必须在 Kotlin 侧
 *
 * 无线调试的端口是随机高位端口，只能靠 mDNS 发现。而 Node 侧（`untrusted_app`
 * 域）**发不出 UDP 组播** —— 真机实测 `EPERM send EPERM 224.0.0.251:5353`。
 * `NsdManager` 是系统服务，请求经 Binder 交给系统域的 `mdnsd` 代发，不受该限制。
 *
 * 所以分工是：
 *
 * | 侧 | 做什么 |
 * |---|---|
 * | Kotlin（本文件） | `NsdManager` 发现端口 → 写 `$DSH_HOME/adb-wireless-port` |
 * | Node（`dsh-android-shell.mjs`） | 读端口文件 → 用内置 adb 连上去 |
 *
 * ⚠️ 真机实测（OPPO PHY110 / Android 15）：发现耗时 **81 毫秒**。
 * 这也是为什么**不扫端口** —— 最初的实现扫 32768–60999 要 20 秒，而端口每次
 * 重启/重开都会变，等于每条特权命令前都可能干等 20 秒。
 *
 * ## 为什么要常驻监听
 *
 * 端口会变（用户重开无线调试、重启、切换网络）。只在启动时发现一次的话，端口一变
 * 就永久失效，用户必须重启 App。所以引擎运行期间保持一个 `NsdManager` 监听，
 * 解析到新端口就更新文件 —— 监听本身由系统服务承担，开销可忽略。
 */
object WirelessDebugging {

    /** 端口文件名，相对 `$DSH_HOME`。Node 侧按同名读取。 */
    const val PORT_FILE = "adb-wireless-port"

    /** 切过去的固定 TCP 端口，与 `dsh-android-shell.mjs` 的 `PORT` 必须一致。 */
    const val DEFAULT_TCP_PORT = 5555

    /**
     * 启动自愈发现无线调试端口的**总预算**（ms）。
     *
     * 重启手机后 adbd 起得比一轮发现慢是常态，所以要允许多轮重试。
     * 取 15 秒：足够覆盖冷启动，又不会让启动线程长时间挂着（它本来就在后台线程）。
     */
    private const val TCP_PORT_DISCOVER_BUDGET_MS = 15_000L

    private const val TAG = DshApplication.TAG

    /**
     * 跑子进程的**硬上限**：超时就 `destroyForcibly()`。
     *
     * ⚠️ 为什么必须有：`Process.waitFor()` **不响应线程中断**，所以上层任何
     *    `withTimeoutOrNull` / `Future.cancel(true)` 都拦不住它。一旦子进程卡在
     *    某个不可中断的状态里（真机遇到过 ANR 那次），后果是**闸门永久为 true** ——
     *    [switching] 在 `finally` 里才 `set(false)`，而 `finally` 永远等不到；
     *    此后每一轮固定端口自愈都会立刻返回「已有一次切换正在进行中」，
     *    特权通道的自动恢复**静默失效**，界面还显示得像是"在忙"。
     *
     *    这是引入「配对超时」时必须一起补的另一半：超时让用户先拿到结论，
     *    而这里保证被抛下的那个任务**确实会结束**、闸门确实会释放。
     */
    private const val SWITCH_PROCESS_TIMEOUT_MS = 90_000L

    /**
     * 进程退出后，留给读流线程收尾的静默窗口。
     *
     * 进程已退出 ⇒ 管道里的数据早已到达（真机实测三行输出全部在进程退出前就打完了），
     * 这里只是给读线程一个被调度到的机会。**不能设长**：管道可能永远不 EOF
     * （adb server 常驻），设长就等于把刚修好的卡死换个地方复现。
     */
    private const val DRAIN_GRACE_MS = 500L

    /**
     * 等子进程结束，超时则强杀。
     *
     * ⚠️ 必须在**读流之前**调用（读流本身也会阻塞），所以它是「先架看门狗、
     *    再读、最后 cancel」的用法，见两个调用点。
     *
     * @return 看门狗，调用方读完流后必须 `cancel()`。
     */
    private fun armProcessWatchdog(proc: Process, label: String): java.util.Timer {
        val timer = java.util.Timer("dsh-$label-watchdog", true)
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                Log.w(
                    TAG,
                    "$label 超过 ${SWITCH_PROCESS_TIMEOUT_MS / 1000} 秒仍未结束，强制结束它" +
                        "（否则切换闸门会被永久占住）",
                )
                runCatching { proc.destroyForcibly() }
            }
        }, SWITCH_PROCESS_TIMEOUT_MS)
        return timer
    }

    /**
     * 跑一次 runner（node + `dsh-android-shell.mjs`），返回「退出码 + 全部输出」。
     *
     * ## ⚠️ 为什么不能「先 `forEachLine` 读完、再 `waitFor()`」（真机踩过，必读）
     *
     * 原实现是那个顺序，结果是**配对明明成功了却永远拿不到结论**。真机日志：
     *
     * ```
     * 17:37:35.320  [pair] Successfully paired to 127.0.0.1:35033
     * 17:37:35.369  [pair] 配对成功，但固定端口 5555 未切成功 —— …
     *               ← 然后卡死 89 秒，一行输出都没有
     * 17:39:04.835  pair 超过 90 秒仍未结束，强制结束它
     * ```
     *
     * 根因：runner 会 `spawn` 内置 **adb server**，那是个**常驻 daemon**，它继承了
     * runner 的 stdout 管道并一直持有写端。于是 node 主进程打完结论退出之后，
     * **管道永远等不到 EOF**，`forEachLine` 就永远不返回 —— 卡在 `waitFor()` 之前，
     * 连退出码都拿不到。输出本身其实早就全部到达了（日志里三行都在）。
     *
     * 正确顺序 = **先 `waitFor()` 拿退出码，读流放在后台线程**：
     * 1. 起一个 daemon 线程持续把流读进 [StringBuilder]（它可能永远读不到 EOF，
     *    所以**不能**在主线程做）；
     * 2. 主线程 `waitFor()`，由看门狗保证有界；
     * 3. 进程结束后再给读线程一个**很短的静默窗口**（数据其实早在管道缓冲里了），
     *    然后 `close()` 流把那个线程放掉。
     *
     * 这不是「少读一点日志」的取舍：进程退出码才是结论，输出只用来给用户看原因。
     * 顺序反了就是「有结论也拿不到」。
     */
    private fun runRunner(
        nodeBin: File,
        runner: File,
        args: List<String>,
        adbHome: File,
        dshHome: File,
        label: String,
    ): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(nodeBin.absolutePath, runner.absolutePath) + args)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("DSH_HOME", dshHome.absolutePath)
            put("DSH_ADB_HOME", adbHome.absolutePath)
            // runner 只用内置 adb 与 Node 内置模块，PATH 给个最小可用集即可
            put("PATH", "/system/bin:/system/xbin")
            put("HOME", dshHome.parentFile?.absolutePath ?: dshHome.absolutePath)
        }
        val proc = pb.start()
        val output = StringBuilder()
        val lock = Any()

        // ⚠️ daemon 线程：管道可能永远不 EOF（adb server 常驻），不能阻塞主线程。
        val drain = Thread({
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lock) { output.append(line).append('\n') }
                    Log.i(TAG, "[$label] $line")
                }
            }
        }, "dsh-$label-drain").apply { isDaemon = true; start() }

        val watchdog = armProcessWatchdog(proc, label)
        val exit = try {
            proc.waitFor()
        } finally {
            watchdog.cancel()
        }

        // 进程已退出 ⇒ 管道里的数据早已到达。给一个很短的窗口让读线程收尾，
        // 再关流释放它 —— 不关的话那个线程会一直挂在 read 上。
        runCatching { drain.join(DRAIN_GRACE_MS) }
        runCatching { proc.inputStream.close() }
        val text = synchronized(lock) { output.toString().trim() }
        return exit to text
    }

    /** 常驻监听器；null 表示未启动。 */
    @Volatile
    private var watcher: AdbMdns? = null
    /**
     * 看门狗线程。见 [startWatcher] 的注释 —— `NsdManager` 会在系统重启 mdnsd
     * 等情况下**静默**掐掉发现，没有看门狗的话端口文件会永久停在旧值上。
     */
    @Volatile
    private var watchdog: Thread? = null

    /** 看门狗是否应继续运行。 */
    @Volatile
    private var watching = false

    /** 看门狗轮询间隔（ms）。发现本身由系统服务承担，这个频率可忽略开销。 */
    private const val WATCHDOG_INTERVAL_MS = 30_000L

    /**
     * 开始常驻监听 `_adb-tls-connect._tcp`，解析到端口就写进 [portFile]。
     *
     * 幂等：**监听仍然有效**时直接返回。
     *
     * ## ⚠️ 幂等判据必须是「监听还活着」，不是一个「对象非空」
     *
     * 原实现是 `if (watcher != null) return`，于是只要曾经 `start()` 过一次，
     * 此后**永远**不会再重建 —— 而 `NsdManager` 会在这些情况下把发现**静默**掐掉：
     *
     * - 系统重启 mdnsd（`onStartDiscoveryFailed` / `onDiscoveryStopped`）；
     * - 本 App 进程被冻结后恢复；
     * - 其它应用把 mDNS 资源抢崩。
     *
     * 掐掉之后 `watcher` 字段仍然非空，`startWatcher()` 每次调用都直接 return，
     * **连引擎重启也修不回来**，端口文件从此不再被更新 —— 用户看到的就是
     * 「无线调试明明开着，App 却一直说没有端口」。
     *
     * 所以判据改成 `watcher?.isRunning == true`（[AdbMdns.isRunning] 在
     * `start()` 里同步置真，在两条「被停掉」的回调里置假，语义正好），
     * 并配一个看门狗周期性复查 —— 与 [AdbPairingNotifier.runWatch] 同构，
     * 那边已经有一个同形的实现可以对照。
     */
    fun startWatcher(context: Context, portFile: File) {
        // ⚠️ 判据是「还活着」，不是「对象非空」。见上面的注释。
        if (watcher?.isRunning == true) {
            ensureWatchdog(context, portFile)
            return
        }
        // 上一轮死掉的监听要先清掉（否则 stop() 会作用在一个已停的对象上，
        // 虽然无害，但留着旧引用会让下面的判断混淆）。
        watcher?.stop()
        // ⚠️ 新监听启动前**先验一次端口文件**：App 进程被杀后重建监听时，
        //    若无线调试此刻已经关着，新实例**从未 found 过任何端口**，
        //    因此永远不会触发 `onLost` —— 旧的端口文件会**原样留在磁盘上**。
        //    后果是 runner 每条命令都先去 connect 一个死端口（拖慢 + 在 adb
        //    server 里留下 offline 记录）。这里主动清一次，判据是「真的连不上」。
        purgeStalePort(context, portFile)
        val mdns = AdbMdns(
            context = context,
            serviceType = AdbMdns.TLS_CONNECT,
            onFound = { port -> writePort(portFile, port) },
            // 服务消失（用户关了无线调试开关）→ 立刻清掉端口文件。
            // 不清的话设置页会一直拿着那个死端口显示绿灯。
            onLost = { writePort(portFile, -1) },
        )
        watcher = mdns
        mdns.start()
        ensureWatchdog(context, portFile)
    }

    /**
     * 端口文件里那个端口是否**已经没人监听**了；是就删掉文件。
     *
     * ⚠️ **判据是「连不上」，不是「读到就说 stale」**。端口文件是常驻监听写的，
     * 无脑删会让正在工作的无线通道失去端口（AGENTS.md 里记过这个缺陷）。
     *
     * ⚠️ 无线调试的 TLS 端口接受 TCP 连接（TLS 握手之后才发生），所以一次成功的
     * `connect` 就足以说明 adbd 还在听 —— 不必做完整握手（那条路要密钥，
     * 而且失败原因会更难区分）。
     */
    private fun purgeStalePort(context: Context, portFile: File) {
        val port = readPort(portFile) ?: return
        val alive = try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 700)
                true
            }
        } catch (_: Throwable) {
            false
        }
        if (!alive) {
            Log.i(TAG, "[mdns] 端口文件里的 $port 已无人监听（多半是上次进程被杀时清不掉），删除它")
            writePort(portFile, -1)
        }
    }

    /** 读端口文件；没有或格式不对给 null。 */
    private fun readPort(portFile: File): Int? = try {
        portFile.readText().trim().toInt().takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    /**
     * 启动看门狗：周期性检查 [watcher] 是否还活着，死了就**重新开始发现**。
     *
     * 幂等：已经有一个在看就返回。
     */
    private fun ensureWatchdog(context: Context, portFile: File) {
        if (watchdog?.isAlive == true) return
        watching = true
        val app = context.applicationContext
        // ⚠️ **先赋字段、再 `start()`**：线程的第一件事就是拿自己与字段比对，
        //    顺序反了会读到上一代的值。理由与 [startFixedPortGuard] 同型 ——
        //    `stopWatcher()` 置 `watching = false` 后若马上又 `startWatcher()`，
        //    字段被置回 true，而上一代看门狗线程醒来看到 `watching == true`
        //    就会继续跑，于是**两个看门狗并存**，各自重建一次 `AdbMdns` 监听，
        //    多出来的那个既不会被 `stopWatcher()` 收走，也一直占着 NsdManager。
        val t = Thread {
            while (watching && watchdog === Thread.currentThread()) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                if (!watching || watchdog !== Thread.currentThread()) return@Thread
                if (watcher?.isRunning == true) continue
                Log.w(TAG, "[mdns] 常驻监听已被系统停掉，重新开始发现无线调试端口")
                // ⚠️ 重建时**不要**递归调用 startWatcher —— 那会再进一次
                //    ensureWatchdog，虽然幂等但语义绕。这里直接重建监听本体。
                val mdns = AdbMdns(
                    context = app,
                    serviceType = AdbMdns.TLS_CONNECT,
                    onFound = { port -> writePort(portFile, port) },
                    onLost = { writePort(portFile, -1) },
                )
                watcher = mdns
                mdns.start()
                // ⚠️ **启动后自校验一次。** 上面「写字段 → 启动」这两行之间若恰好
                //    被 `stopWatcher()` 穿过，它会 stop 掉一个**尚未 start** 的对象
                //    （等于没停），随后本行才把监听真正跑起来 —— 那个监听再也没人
                //    收得走，会一直占着 NsdManager 的 listener 注册。
                //    窗口很窄但后果是常驻泄漏，所以这里补一次代际校验。
                if (!watching || watchdog !== Thread.currentThread()) {
                    mdns.stop()
                    return@Thread
                }
            }
        }
        t.isDaemon = true
        t.name = "dsh-mdns-watchdog"
        watchdog = t
        t.start()
    }

    /** 停止常驻监听（引擎停止时调用，避免泄漏 listener 注册）。 */
    fun stopWatcher() {
        watching = false
        // ⚠️ 先置空再中断，理由见 [stopFixedPortGuard]：置空才是让旧线程退出的
        //    主要手段（它可能正卡在 NsdManager 的回调里，打断不了）。
        val old = watchdog
        watchdog = null
        old?.interrupt()
        watcher?.stop()
        watcher = null
    }

    /**
     * 固定端口守护线程。见 [startFixedPortGuard]。
     */
    @Volatile
    private var guard: Thread? = null

    /** 守护是否应继续运行。 */
    @Volatile
    private var guarding = false

    /**
     * 是否已有一轮 [ensureTcpPort] 在跑。
     *
     * ⚠️ 必需，不是优化：自愈的调用点现在有三个（引擎启动、`start()` 早返回即
     * 「每次打开 App」、周期守护），而一轮 `ensureTcpPort` 最长要 13 秒。
     * 没有这道闸时，用户连续开几次 App 就会叠起几个并发的 node 进程，**每一个都在
     * 往 adbd 发 `tcpip`** —— adbd 被反复重启，反而把本来好好的通道打烂。
     * 这里让后来者直接拿到「已在切换中」而不是排队重来。
     */
    private val switching = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 守护的正常轮询间隔（ms）。 */
    private const val GUARD_INTERVAL_MS = 5 * 60_000L

    /**
     * 连续失败多少次之后退避到 [GUARD_BACKOFF_MS]。
     *
     * ⚠️ 退避是必需的，不是优化：`ensureTcpPort` 在固定端口不通时**会花 13 秒**
     * 做 mDNS 发现。而「无线调试本来就关着」的设备上它**每次都失败** ——
     * 不退避就是每 5 分钟白烧 13 秒 CPU，对电池的影响是实打实的。
     * 退避之后仍然保留恢复能力：用户打开无线调试，最多等 [GUARD_BACKOFF_MS]，
     * 或者直接在设置页点一下「切到固定端口」（那条路径不经过守护）。
     */
    private const val GUARD_FAILURES_BEFORE_BACKOFF = 3

    /** 退避后的轮询间隔（ms）。 */
    private const val GUARD_BACKOFF_MS = 15 * 60_000L

    /**
     * 周期性确认固定端口 5555 **真的还能拿到 uid 2000**，不能就自动切回去。
     *
     * ## 为什么需要它（这是「权限会掉」的第二条根因）
     *
     * 在此之前全工程**没有任何周期性守护**：`ensureTcpPort` 只在引擎启动那一刻跑
     * 一次，之后就再也没人管过 5555。于是这些情况一旦发生就**永久**失效，直到
     * 用户手动点「切到固定端口」或重启 App：
     *
     * - 手机重启（`persist.adb.tcp.port` 写不进去 ⇒ 5555 消失，这是必然的）；
     * - adbd 自己重启（关/开无线调试、系统更新）；
     * - 别的应用抢占了 5555。
     *
     * 用户看到的现象就是「本来好好的，过一阵特权身份就没了」——而且**没有任何
     * 界面提示**，只能靠重新检测才发现。
     *
     * ## 判据
     *
     * 每一轮都走完整的 [ensureTcpPort]，也就是让 runner 用
     * 「握手 + `id -u` == 2000」这条权威判据去验。**不用 Kotlin 侧的 bind 探测**
     * 当判据 —— 那是假成功（见 [ensureTcpPort] 的注释）。稳态下（5555 正常）
     * 这一轮的全部开销 = 启动一次 node + 一次 ADB 握手，几百毫秒。
     *
     * ⚠️ 阻塞式长跑线程，由 [stopFixedPortGuard] 结束。幂等：已在跑就返回。
     */
    fun startFixedPortGuard(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
    ) {
        if (guard?.isAlive == true) return
        guarding = true
        val app = context.applicationContext
        // ⚠️ **先赋字段、再 `start()`**，不能写成 `guard = Thread { … }.also { it.start() }`：
        //    那样线程会在字段被赋值**之前**就跑起来，而线程的第一件事正是拿自己
        //    与字段比对（见下面的判据）。顺序反了会读到上一代的值。
        val t = Thread {
            var failures = 0
            // ⚠️ 循环判据是**「我还是当前这一代守护吗」**，不是共享的 `guarding` 布尔量。
            //    只用 `guarding` 会有一个真实的重入：`stopFixedPortGuard()` 置 false 后，
            //    若用户马上又触发一次 `start()`，字段被置回 true，而**上一代线程**
            //    （它可能正卡在 `ensureTcpPort` 里读外部进程的输出，最长 30 秒，
            //    且 `interrupt()` 对这种阻塞无效）醒来后看到 `guarding == true`
            //    就会继续跑 —— 两个守护并存，各自周期性地往 adbd 发 `tcpip`，
            //    互相把对方刚建立的连接打断。
            //    比对字段则天然只有最后一代能活下来。
            while (guarding && guard === Thread.currentThread()) {
                val wait = if (failures >= GUARD_FAILURES_BEFORE_BACKOFF) {
                    GUARD_BACKOFF_MS
                } else {
                    GUARD_INTERVAL_MS
                }
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                if (!guarding || guard !== Thread.currentThread()) return@Thread
                // ⚠️ 别把「别人正在切」记成一次失败。设置页的按钮、或引擎启动时的
                //    自愈恰好和这一轮撞上时，那**不是**通道有问题 —— 记成失败会让
                //    守护白白退避 15 分钟，而通道可能几秒后就恢复了。
                if (switching.get()) {
                    Log.i(TAG, "固定端口守护：另一次切换正在进行，本轮跳过")
                    continue
                }
                val result = try {
                    ensureTcpPort(app, nodeBin, runner, adbHome, dshHome)
                } catch (t: Throwable) {
                    Result(false, "守护探测异常：${t.message}")
                }
                if (result.ok) {
                    if (failures > 0) {
                        Log.i(TAG, "固定端口守护：已恢复（此前连续失败 $failures 次）")
                    }
                    failures = 0
                } else {
                    failures += 1
                    Log.i(
                        TAG,
                        "固定端口守护：第 $failures 次未能恢复特权通道 —— ${result.message}",
                    )
                }
            }
        }
        t.isDaemon = true
        t.name = "dsh-fixed-port-guard"
        guard = t
        t.start()
        Log.i(TAG, "固定端口守护已启动（每 ${GUARD_INTERVAL_MS / 60_000} 分钟复查一次）")
    }

    /** 停止固定端口守护（引擎停止时调用）。 */
    fun stopFixedPortGuard() {
        guarding = false
        // ⚠️ 先把字段置空（= 宣布这一代作废），再中断旧引用。置空是**主要**手段：
        //    正在跑的线程下一次检查判据时就会自己退出，不依赖 `interrupt()` 能
        //    打断阻塞读 —— 它正卡在外部进程的输出上时是打断不了的。
        val old = guard
        guard = null
        old?.interrupt()
    }

    /**
     * 启动时自愈：5555 不在听、但无线调试可用 → 自动切一次固定端口。
     *
     * ## 为什么需要它
     *
     * `persist.adb.tcp.port` 写不进去（SELinux 拒绝），所以**重启手机后 5555 会消失**。
     * runner 里的「通道 1.5」只在模型调 `shell` 时才自愈 —— 用户打开设置页看到的是
     * 「身份变成 app 身份」，却不知道点一下「切到固定端口」就能修好。
     *
     * 这里在引擎启动时顺手补一次，让「重启后要手动点一下」这个前提消失。
     *
     * ⚠️ **阻塞调用**（最坏约 30 秒：一次 runner 探测 + 13 秒端口发现 + 切换 +
     * 20 秒确认），必须在线程里调。稳态下（5555 已在听）只有几百毫秒 —— 第一轮
     * 的 runner 探测直接返回，走不到后面。
     * 失败不影响任何东西 —— legacy 通道、无线通道、app 身份都照旧。
     *
     * @return 是否真的切成功（已经是固定端口时也算成功）。
     */
    fun ensureTcpPort(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
    ): Result {
        // ⚠️ 同一时刻只允许一轮切换。理由见 [switching] 的注释：并发的 `tcpip`
        //    会互相把 adbd 重启掉，比不切还糟。后来者**如实报「没切」**——
        //    不能报成功：调用方（设置页按钮 / 守护）要据此判断通道到底可不可用。
        if (!switching.compareAndSet(false, true)) {
            return Result(false, context.getString(R.string.adb_switch_busy))
        }
        try {
            return ensureTcpPortLocked(context, nodeBin, runner, adbHome, dshHome)
        } finally {
            switching.set(false)
        }
    }

    /** [ensureTcpPort] 的主体；调用前必须已持有 [switching] 闸。 */
    private fun ensureTcpPortLocked(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
    ): Result {
        val portFile = File(dshHome, PORT_FILE)

        // ── 第一轮：先让 runner 自己决断，**不先做发现** ──────────────────
        //
        // 稳态下（5555 已经在听）runner 的第一句 `fixedPortWorks()` 就返回了，
        // 所以这一轮几乎是零成本。反过来若先花 15 秒做 mDNS 发现才去问 runner，
        // 每次启动都要白等一段。
        //
        // ⚠️ **这里刻意不再用 `isPortListening(5555)` 做提前返回。** 那条判据是
        //    「bind 失败 = 有人在听」，而 5555 是 >1024 的端口，**任何应用都能绑**：
        //    撞上时会得到**假成功** —— 提前返回「固定端口已在监听」，runner 根本
        //    没被调用，而实际连上去的不是 adbd。此后每条特权命令都静默回落成 app
        //    身份，设置页却显示一切正常。真正的判据只有 runner 那条
        //    「握手 + `id -u` == 2000」。
        val first = switchToTcp(nodeBin, runner, adbHome, dshHome, knownPort = -1)
        if (first.ok) return first

        // ── 第二轮：runner 说不行，那才值得花时间发现无线调试端口 ──────────
        //
        // ⚠️ **重试而不是只探一次。** 重启手机后 adbd 往往要好几秒才广播出
        //    `_adb-tls-connect._tcp`，而单次发现在 3 秒内落空是常态 —— 原来的
        //    「一次 3 秒、失败就放弃」等于让「重启后自动恢复」这条路径基本失效，
        //    用户看到的现象就是「重启后特权身份没了」。
        //    这里探 3 轮（每轮 3 秒，中间隔 2 秒），总预算约 13 秒，仍然远小于
        //    任何用户可感知的「卡住」。任一轮拿到端口就立刻往下走。
        val deadline = System.currentTimeMillis() + TCP_PORT_DISCOVER_BUDGET_MS
        var discovered = -1
        var attempt = 0
        while (discovered <= 0 && System.currentTimeMillis() < deadline) {
            attempt += 1
            discovered = discoverConnectPort(context, portFile, timeoutMs = 3000)
            if (discovered <= 0 && System.currentTimeMillis() < deadline) {
                Log.i(TAG, "第 $attempt 次未发现无线调试端口，2 秒后重试")
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        if (discovered <= 0) {
            // ⚠️ 这一条**不再**说「无线调试不可用」就完事 —— 那句话把用户送进死胡同
            //   （他不知道该开什么）。把两条可行的出路都写清楚：要么开无线调试，
            //   要么用电脑切一次。runner 那边第一轮的输出里也有同样的指引。
            Log.w(TAG, "固定端口未就绪，且 $attempt 次发现都没拿到无线调试端口")
            return Result(
                false,
                context.getString(R.string.adb_no_connection, attempt) +
                    context.getString(R.string.adb_enable_wireless),
            )
        }
        // 把**刚发现的新鲜端口**传下去。这一点是必需的：`adb tcpip` 要通过无线
        // 调试那条连接才能发出去，而配对/重启过程中 adbd 会换端口，传旧值必失败。
        return switchToTcp(nodeBin, runner, adbHome, dshHome, knownPort = discovered)
    }

    /**
     * 一次性发现连接端口（设置页「重新检测」用）。
     *
     * @return 端口号；未发现给 -1。
     */
    fun discoverConnectPort(context: Context, portFile: File, timeoutMs: Long = 5000): Int {
        val port = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, timeoutMs)
        // ⚠️ **没发现时不要动端口文件。** 一次「没探到」不等于「端口已失效」：
        //    无线调试刚配对/刚重启 adbd 时会短暂不可见，`NsdManager` 也常先失败
        //    一次再成功。原实现无条件 `writePort(port)`，超时就等于**删掉**常驻
        //    监听刚写好的好端口（见 writePort 的 `port <= 0` 分支），于是紧接着的
        //    补切拿不到端口 → 跳过 → 通道只剩无线调试 → 关 Wi-Fi 就断。
        //    真正确认失效的信号是 `AdbMdns.onServiceLost`，那条路径仍然会清文件。
        if (port > 0) writePort(portFile, port)
        else Log.i(TAG, "[mdns] 本次未发现连接端口（保留已有端口文件，等常驻监听更新）")
        return port
    }

    /**
     * 发现配对端口。
     *
     * ⚠️ **配对端口只在用户打开「使用配对码配对设备」那个界面时才存在**，
     * 界面一关端口就没了。所以顺序必须是：用户先打开那个界面 → 再在 App 里点配对。
     *
     * @return 端口号；未发现给 -1。
     */
    fun discoverPairingPort(context: Context, timeoutMs: Long = 8000): Int =
        AdbMdns.discover(context, AdbMdns.TLS_PAIRING, timeoutMs)

    /**
     * 执行配对：发现配对端口 → 调 runner 的 `--pair` 子命令。
     *
     * 配对走 SPAKE2 + TLS-PSK，Node 的 `tls` 模块实现不了，所以借内置 adb 的
     * `adb pair`（Google 自己的实现）。
     *
     * ⚠️ 这是个**阻塞**调用（发现最多 8 秒 + adb pair 若干秒），**不要在主线调用**。
     *
     * @param code 用户从系统「使用配对码配对设备」界面抄来的 6 位配对码。
     * @param knownPort 通知栏那条通知**已经带着**的配对端口（来自 `AdbMdns` 的发现）。
     *   传 `<= 0` 时本方法自己重新发现一次。
     *
     *   ⚠️ 为什么要让调用方把端口传进来：用户是在**通知栏**回复配对码的，而那个
     *   「使用配对码配对设备」界面在他回复时**多半已经切走或关掉了** —— 端口随之
     *   消失，重新发现必然落空，于是「明明已经找到端口、却报没找到端口」。
     *   通知里带下来的那个端口是**当时确实存在**的，直接用它成功率最高。
     * @return 可展示给用户的结果。
     */
    fun pair(
        context: Context,
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
        code: String,
        knownPort: Int = -1,
    ): Result {
        if (!runner.isFile) return Result(false, context.getString(R.string.adb_runner_missing, runner.absolutePath))
        if (!nodeBin.isFile) return Result(false, context.getString(R.string.adb_node_missing, nodeBin.absolutePath))

        // 优先用通知带下来的端口；没有再自己发现。
        val port = if (knownPort > 0) {
            Log.i(TAG, "使用通知携带的配对端口 $knownPort")
            knownPort
        } else {
            discoverPairingPort(context)
        }
        if (port <= 0) {
            return Result(
                false,
                context.getString(R.string.adb_pairing_port_missing) +
                    context.getString(R.string.adb_pairing_screen_hint),
            )
        }
        Log.i(TAG, "配对端口 $port，开始配对")

        val output = StringBuilder()
        val exit: Int = try {
            // ⚠️ 交给 [runRunner]：它**先 waitFor 拿退出码、读流在后台线程**。
            //   原来这里是自己「先 forEachLine 读完再 waitFor」，而 runner 会 spawn
            //   常驻的 adb server 并继承 stdout ⇒ 管道永不 EOF ⇒ 卡死 89 秒、
            //   配对明明成功却拿不到结论。详见 runRunner 的注释。
            val (code, text) = runRunner(
                nodeBin = nodeBin,
                runner = runner,
                args = listOf("--pair", port.toString(), code),
                adbHome = adbHome,
                dshHome = dshHome,
                label = "pair",
            )
            output.append(text)
            code
        } catch (t: Throwable) {
            Log.w(TAG, "配对失败", t)
            return Result(false, context.getString(R.string.adb_pairing_process_failed, t.message))
        }

        val text = output.toString().trim()
        return if (exit == 0) {
            // ⚠️ 退出码 0 只代表**配对成功**，不代表通道可用。
            //
            //   配对成功后 runner 会立刻尝试切到固定端口 5555，而那一步**可能失败**
            //   （端口还没广播出来、无线调试刚好被关掉等，见 `switchToTcp()`）。
            //   失败时 runner 会往 stderr 打一句明确的警告，且因为 `redirectErrorStream`
            //   它就在 `text` 里。
            //
            //   必须把这两种结果**分开说**：只吞下 `exit == 0` 一律回「配对成功，特权身份
            //   已可用」，用户会看到通知栏宣告成功、而设置页重新检测却报检测不到 ——
            //   正是要根治的那个现象。诚实分流后，失败那种情况会直接告诉用户
            //   「还差一步，去点『切到固定端口』」。
            if (text.contains(SWITCH_TC_FAILED_MARK)) {
                Result(
                    true,
                    context.getString(R.string.adb_paired_switch_pending) +
                        context.getString(R.string.adb_paired_switch_retry),
                )
            } else {
                Result(true, context.getString(R.string.adb_key_trusted))
            }
        } else {
            Result(false, pairingFailureReason(exit, text))
        }
    }

    /**
     * 把 adbd 切到固定的 TCP 端口（`adb tcpip 5555`）。
     *
     * ## 为什么需要它
     *
     * 无线调试的 TLS 端口**只在无线调试开关开着时才存在**，而且实测
     * **关掉开关会立刻掐断已建立的连接**（`adb_wifi_enabled=0` 之后已连上的
     * `_adb-tls-connect` 立刻变 `device offline`）。所以只靠无线调试的话，
     * 用户一关开关通道就断。
     *
     * `adb tcpip 5555` 把 adbd 重启到 TCP 模式，此后**关无线调试、关 Wi-Fi
     * 都不受影响**（走 127.0.0.1 回环）。实测 Android 15 上两者**可以并存** ——
     * `adb tcpip` 不会关闭无线调试，所以这是纯增益动作。
     *
     * ⚠️ 代价：`persist.adb.tcp.port` 实测为空，所以**重启手机后需要再切一次**。
     * 稳态下 `dsh-android-shell.mjs` 的通道级联会在发现 5555 不在听、而无线
     * 调试可用时自动重切，所以用户通常不用手动点。
     *
     * ⚠️ 阻塞调用，**不要在主线调用**。
     *
     * @param knownPort 刚发现的**新鲜**无线调试端口；`<= 0` 表示让 runner 自己去读
     *   端口文件。⚠️ **这个参数是必需的**：`adb tcpip` 只能经无线调试那条连接发出去，
     *   而端口文件里的值随时可能是死的（无线调试刚重启、或 App 进程被杀后没清掉），
     *   runner 自己去读常常读到一个连不上的旧端口 —— 症状就是「明明无线调试开着，
     *   切换却总失败」。
     */
    fun switchToTcp(
        nodeBin: File,
        runner: File,
        adbHome: File,
        dshHome: File,
        knownPort: Int = -1,
    ): Result {
        if (!runner.isFile) return Result(false, DshApplication.instance.getString(R.string.adb_runner_missing, runner.absolutePath))
        if (!nodeBin.isFile) return Result(false, DshApplication.instance.getString(R.string.adb_node_missing, nodeBin.absolutePath))

        val output = StringBuilder()
        val exit: Int = try {
            // ⚠️ 端口作为**第二个参数**传下去。runner 的 `--switch-tcp` 分支读
            //   `argv[1]`；不传时它退回读端口文件（那条路正是上面注释里说的坏情况）。
            // ⚠️ 同 `pair()`：必须走 [runRunner]（先 waitFor、读流在后台），
            //   否则 adb server 继承管道会让读流永远等不到 EOF。
            val args = mutableListOf("--switch-tcp")
            if (knownPort > 0) args += knownPort.toString()
            val (code, text) = runRunner(
                nodeBin = nodeBin,
                runner = runner,
                args = args,
                adbHome = adbHome,
                dshHome = dshHome,
                label = "switch-tcp",
            )
            output.append(text)
            code
        } catch (t: Throwable) {
            Log.w(TAG, "切换 TCP 模式失败", t)
            return Result(false, DshApplication.instance.getString(R.string.adb_switch_process_failed, t.message))
        }

        return if (exit == 0) {
            Result(true, DshApplication.instance.getString(R.string.adb_fixed_port_ready))
        } else {
            Result(false, DshApplication.instance.getString(R.string.adb_switch_failed, exit, output.toString().trim()))
        }
    }

    /** 把 adb 的报错翻译成用户能照着做的说明。 */
    private fun pairingFailureReason(exit: Int, output: String): String = when {
        output.contains("Failed to parse pairing code") || output.contains("invalid pairing code") ->
            DshApplication.instance.getString(R.string.adb_pairing_code_invalid)
        output.contains("Connection refused") ->
            DshApplication.instance.getString(R.string.adb_pairing_port_refused)
        output.contains("already paired") ->
            DshApplication.instance.getString(R.string.adb_already_paired)
        else ->
            DshApplication.instance.getString(R.string.adb_pairing_failed_detail, exit, output)
    }

    /**
     * runner 在「配对成功但没能切到固定端口」时打的 stderr 标记。
     *
     * ⚠️ **必须与 `assets/dsh-android-shell.mjs` 里那句话逐字对应。** 那边改文案
     * 而这边不改，判据就会静默失效 —— 于是又回到「通知栏宣告配对成功、界面却说
     * 检测不到」的状态，而且再也没有任何地方能提示原因。
     * （配对进程用 `redirectErrorStream(true)`，所以 stderr 就在 stdout 里。）
     */
    private const val SWITCH_TC_FAILED_MARK = "配对成功，但固定端口"

    /** 把端口写进文件；`port <= 0` 时**删除**文件（避免留下过期端口误导 Node 侧）。 */
    private fun writePort(portFile: File, port: Int) {
        try {
            if (port > 0) {
                portFile.parentFile?.mkdirs()
                portFile.writeText("$port\n")
                Log.i(TAG, "[mdns] 无线调试端口 $port 已写入 ${portFile.name}")
            } else if (portFile.exists()) {
                portFile.delete()
                Log.i(TAG, "[mdns] 无线调试端口不可用，已清除 ${portFile.name}")
            }
        } catch (t: Throwable) {
            // 写不进去只是无线通道不可用，legacy 与 app 身份都不受影响
            Log.w(TAG, "[mdns] 写端口文件失败: ${t.message}")
        }
    }

    /** 配对结果。 */
    data class Result(val ok: Boolean, val message: String)
}
