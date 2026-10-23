package com.dsh.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dsh.android.log.DshLog as Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无线调试配对的**通知栏输入**交互。
 *
 * ## 为什么配对码要用通知栏输入，而不是 App 里的输入框
 *
 * 配对码显示在**系统**的「使用配对码配对设备」界面上。用户要抄它，就必须离开
 * 我们的 App。而通知栏的 `RemoteInput`（直接回复）让用户**不用切回 App** 就能
 * 把码敲进去。Shizuku 用的就是这个办法（`AdbPairingService`）。
 *
 * ## 交互时序
 *
 * 1. 用户在设置页点「配对无线调试」；
 * 2. 本对象发一条**常驻**通知，提示去系统里打开「使用配对码配对设备」；
 * 3. 后台**一直**监听 `_adb-tls-pairing._tcp`，直到用户取消 —— 见下面那条 ⚠️；
 * 4. 端口一出现就把通知换成带输入框的，用户直接在通知栏回复 6 位码；
 * 5. [AdbPairingReceiver] 收到 → 调 [WirelessDebugging.pair]（内置 adb 的
 *    `adb pair`，SPAKE2 + TLS-PSK）→ 结果写回通知。
 *
 * ## ⚠️ 为什么必须「一直监听」，而不是「发现一次，超时就放弃」
 *
 * 这是**真机反馈修掉的缺陷**：原实现是「点配对 → 一次性发现 8 秒 → 没找到就报
 * 没找到」。但用户此刻**正在系统设置里翻找**那个无线调试界面 —— 翻到「开发者选项」
 * 再进「无线调试」再点「使用配对码配对设备」，远超 8 秒。于是：
 *
 * ```
 * 用户刚找到那个按钮 → 通知栏已经显示「没找到端口」→ 只能回 App 重按一次配对
 * ```
 *
 * 而重按一次**还是同样的结果**，因为端口本来就还没出现。这个来回毫无意义。
 *
 * 现在改成：注册**一次**常驻 mDNS 发现，端口一出现立刻反应；用户开着那个界面多久
 * 都行。**唯一的结束条件是用户主动取消**（设置页的「取消配对」，或通知上的取消）。
 *
 * ⚠️ **配对码必须由用户输入，无法自动化**：SPAKE2 用那个码当共享密钥，
 * 设备端只显示给人看。这不是实现偷懒，是协议本身的要求。
 */
object AdbPairingNotifier {

    /** `RemoteInput` 的结果 key，发送端与接收端必须一致。 */
    const val EXTRA_REPLY = "com.dsh.android.pairing.code"

    /** 接收配对码的动作。 */
    const val ACTION_REPLY = "com.dsh.android.action.PAIRING_REPLY"

    /** 取消配对的动作（通知栏上的取消按钮）。 */
    const val ACTION_CANCEL = "com.dsh.android.action.PAIRING_CANCEL"

    /** 通知里带上配对端口，供接收端使用。 */
    const val EXTRA_PORT = "com.dsh.android.pairing.port"

    private const val CHANNEL_ID = "dsh-adb-pairing"
    private const val NOTIFICATION_ID = 1002

    /**
     * 「有一次配对没走完」的持久化标记。
     *
     * ⚠️ 为什么需要落盘：`watchJob` 与 `pairingGeneration` 都只在**进程内**有效。
     *   真机反馈的现象是——配对把 App 拖到 ANR、进程被系统杀掉，用户重新打开 App，
     *   通知栏**还挂着「正在配对」**（那是被杀之前发的常驻通知，新进程无从知晓）。
     *   而 `setOngoing(true)` 让用户连划都划不掉，只能去强停应用。
     *
     *   所以在进程启动时读一次这个标记：有残留就撤掉通知并告诉用户「请重发配对码」。
     */
    private const val PREFS = "dsh-adb-pairing"
    private const val KEY_PENDING = "pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记下「配对进行中」，崩溃/被杀后下次启动能发现残留。 */
    private fun markPending(context: Context, pending: Boolean) {
        runCatching { prefs(context).edit().putBoolean(KEY_PENDING, pending).apply() }
    }

    /** 端口轮询间隔。发现是异步的，300ms 足够及时又不费电。 */
    private const val POLL_INTERVAL_MS = 300L

    /**
     * 一次配对的总预算。
     *
     * ⚠️ **必须有限**，这是真机反馈修掉的缺陷：原来这一路没有任何超时，用户输入配对码
     * 之后通知栏就一直停在「正在配对」，直到系统弹「DSH 没有响应」——**永远没有结论**。
     *
     * 预算取 75 秒的依据：`pair(port, code)` 自身（TLS-PSK 握手 + 内置 adb 启动）通常
     * 几秒，之后 `pairWirelessDebugging` 还要补切固定端口 5555，而
     * `WirelessDebugging.ensureTcpPort` 的发现预算（3 轮 × 3 秒 + 间隔 ≈ 13 秒）与
     * `switchToTcp` 的握手确认预算（20 秒）相加最坏约 35 秒。正常路径远快于此，
     * 75 秒是「慢机 + adbd 正在重启」仍能跑完、而用户不会觉得卡死的上限。
     */
    private const val PAIR_BUDGET_MS = 75_000L

    /**
     * 本次配对的代际号。
     *
     * ⚠️ 超时后那个在跑的配对任务**可能仍会返回**（`Process.waitFor()` 不响应中断），
     *   若它晚到还去改通知，就会出现「刚告诉用户超时了、随即又变回正在配对」。
     *   所以每次发起配对自增一次，回来时代际不匹配就**丢弃结果**。
     */
    private val pairingGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 当前常驻监听任务；null 表示没有在等。
     *
     * ⚠️ 它是**唯一**的结束开关：只有 [cancel]（用户主动取消）与配对成功会清掉它。
     * 不要给它加超时 —— 那正是本次要修掉的缺陷。
     */
    @Volatile
    private var watchJob: Job? = null

    /**
     * 发起配对：发常驻通知 + 后台一直监听配对端口，直到用户取消。
     *
     * ⚠️ 立即返回，**不阻塞调用方**。这是从 WebView 的 JS 桥直接调用的，
     * 绝不能卡住 JS 线程。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        // 幂等：用户可能连点几次。已经在等就不要再起一个 —— 两个任务会互相
        // 覆盖通知，取消时也只能停掉其中一个，留下一个永远退不出的监听。
        if (watchJob?.isActive == true) {
            Log.i(DshApplication.TAG, "配对监听已在进行中，忽略重复请求")
            return
        }
        // ⚠️ 先查通知权限再起监听：没有通知就没有任何交互出口（见 runWatch 的注释）。
        //   在这里拦下来的好处是**设置页能立刻告诉用户原因**，而不是让用户
        //   对着一句「已开始配对」发呆。
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            Log.w(DshApplication.TAG, "拒绝开始配对：本应用的通知被系统关闭，配对流程无法交互")
            return
        }
        watchJob = DshApplication.applicationScope.launch {
            runWatch(app)
        }
    }

    /**
     * 通知是否可用（设置页据此提示用户去授权）。
     *
     * 与 [start] 的拦截图的是同一件事，但这个是**只读查询**，供 JS 桥随时问。
     */
    fun notificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)

    /**
     * 常驻监听配对端口，直到被取消。
     *
     * 只在**端口发生变化**时重发通知（而不是每 300ms 刷一次），避免通知栏闪烁。
     */
    private suspend fun runWatch(app: Context) {
        // ⚠️ **首条通知发不出去就必须收摊**，不能继续常驻监听。
        //
        //   常驻通知是这套流程**唯一**的用户出口：它既提示用户去打开「使用配对码
        //   配对设备」，又带着输入配对码的输入框、还带着「取消」按钮。通知发不出去
        //   （最常见的成因是 Android 13+ 没给通知权限，而本 App 的 `targetSdk=28`
        //   拿不到那个运行时授权），用户就**什么都看不到**：不知道要做什么，
        //   更没有任何东西可点。
        //
        //   此时若还留着监听，结果是：后台一直跑 mDNS、用户毫无察觉，而唯一能停下
        //   它的入口在设置页（用户根本不知道要去点）。净效果是一个静默泄漏的后台任务。
        //   所以这里直接返回 —— 什么都没发生，好过留下一个用户看不见也关不掉的监听。
        val posted = notify(
            app,
            app.getString(R.string.pairing_title),
            app.getString(R.string.pairing_waiting_open),
            null,
            true,
        )
        if (!posted) {
            Log.w(
                DshApplication.TAG,
                "配对通知无法显示（多半是未授予通知权限），已放弃常驻监听 —— " +
                    "请在系统设置里允许「DSH」发送通知后重试",
            )
            return
        }

        // ⚠️ 用**一个常驻的** AdbMdns 实例，而不是反复调 `AdbMdns.discover(timeout)`：
        //   后者每次都要重新注册 discovery（`NsdManager` 同一 listener 不能重复注册，
        //   所以每次都新建对象），中间存在「上一次已停、下一次未注册」的空窗期，
        //   端口恰好在空窗期出现就会错过，要再等一轮。常驻注册没有空窗。
        var mdns = AdbMdns(app, AdbMdns.TLS_PAIRING)
        mdns.start()

        var lastPort = -1
        try {
            while (currentCoroutineContext().isActive) {
                val port = mdns.port
                if (port > 0 && port != lastPort) {
                    lastPort = port
                    Log.i(DshApplication.TAG, "配对端口 $port，等待用户输入配对码")
                    notify(
                        app,
                        app.getString(R.string.pairing_title),
                        app.getString(R.string.pairing_waiting),
                        port,
                        true,
                    )
                }
                // 看门狗：`NsdManager` 在系统重启 mdnsd 等情况下会回调
                // onStartDiscoveryFailed 把发现静默掐掉。常驻等待必须能自己恢复，
                // 否则用户按提示开了无线调试，这边却早已不再监听。
                if (!mdns.isRunning) {
                    Log.i(DshApplication.TAG, "[mdns] 配对发现已停止，重建监听")
                    mdns.stop()
                    mdns = AdbMdns(app, AdbMdns.TLS_PAIRING)
                    mdns.start()
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            mdns.stop()
        }
    }

    /**
     * 停止监听并撤掉配对通知（用户在设置页点「取消配对」，或配对已成功）。
     *
     * ⚠️ 这是**唯一**能让常驻监听停下来的用户动作（另加配对成功）。
     */
    fun cancel(context: Context) {
        // ⚠️ 必须先让代际失效：超时计时协程是**独立**在跑的，用户点了取消之后
        //   它仍会在到点时醒来。不拦的话，用户明明取消了，75 秒后通知栏又冒出
        //   一条「配对超时」—— 比不修还糟。计时协程开头就校验代际，这里自增即拦住。
        pairingGeneration.incrementAndGet()
        stopWatch()
        markPending(context, false)
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * 进程启动时清理上一次没走完的配对状态。
     *
     * ⚠️ 这是真机反馈修掉的缺陷的直接对策：配对把 App 拖到 ANR、进程被系统杀掉后，
     *   用户重新打开 App 时通知栏**还挂着「正在配对」**，而权限其实已经开好了 ——
     *   那条通知既没有意义（那个进程已经死了）、又划不掉（`setOngoing(true)`），
     *   用户以为还在配对，于是一直等。
     *
     *   判据用持久化标记而不是「通知在不在」：从通知栏反查需要 `NotificationManager`
     *   的 activeNotifications（API 23+ 且需权限），而标记是现成的、无副作用。
     *
     * 由 [DshApplication.onCreate] 调用，只做一次。
     */
    fun clearStaleOnStartup(context: Context) {
        val app = context.applicationContext
        val pending = runCatching { prefs(app).getBoolean(KEY_PENDING, false) }.getOrDefault(false)
        if (!pending) return
        Log.w(DshApplication.TAG, "发现上一次未走完的配对（进程曾被杀），已清理残留通知")
        markPending(app, false)
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
        // ⚠️ 只在**确实有残留**时才提示用户重发配对码 —— 不要在每次正常启动时打扰。
        //   这里刻意复用那条输入框还在的失败通知语义：用户上次的配对码已经失效了。
        notify(
            app,
            app.getString(R.string.pairing_failed_title),
            app.getString(R.string.pairing_stale_cleared),
            null,
            false,
        )
    }

    /** 只停监听，不动通知（配对成功后要留着那条成功提示）。 */
    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * 收到配对码后执行配对。**立即返回**，全程不阻塞调用方。
     *
     * ⚠️ 由 [AdbPairingReceiver] 在 `goAsync()` 的宽限期内调用，而那个宽限期只有约
     *    10 秒 —— 所以本方法**必须马上返回**，重活交给 [DshApplication.applicationScope]。
     *
     * ## 这里的三条硬约束（都是真机反馈修出来的）
     *
     * 1. **必须有界**。原实现由广播接收器起一个 `Thread` 同步跑完整条配对链
     *    （`pairWirelessDebugging` 里含发现端口 ≈13 秒 + 切换固定端口 ≈20 秒 +
     *    内置 adb 启动），远超 `goAsync()` 的宽限期 ⇒ 系统判 ANR 弹
     *    「DSH 没有响应」，而通知栏**永远**停在「正在配对」。
     *    现在整条链跑在 `applicationScope` 里，**调用方一个字节都不等**。
     * 2. **必须有兜底**。任何异常（含 `Error`、含 `CancellationException`）都要落成
     *    一条**终态通知**，否则那条 `setOngoing(true)` 的常驻通知就成了幽灵：
     *    App 重启后它还在，而用户连划都划不掉。
     * 3. **结果回来时必须校验代际**。超时后原任务可能仍在跑并稍后返回，
     *    不校验就会把「已超时」的通知又改回「正在配对」。
     */
    internal fun pairWithCode(context: Context, port: Int, code: String) {
        val app = context.applicationContext
        // ⚠️ **先落盘再发通知**：顺序反过来的话，若进程恰好在两者之间被杀，
        //   通知已发出而标记没写上 —— 下次启动就发现不了这条残留通知，
        //   正是要修的那个「重开后通知还挂着正在配对」。
        markPending(app, true)
        notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_working), null, true)

        val generation = pairingGeneration.incrementAndGet()

        // ⚠️ **不能用 `withTimeoutOrNull`**（第一版就是这么写的，是错的）：
        //   `EngineManager.pairWirelessDebugging()` 是**普通阻塞函数**，内部卡在
        //   `Process.waitFor()` 上，而协程超时是**协作式**的 —— 阻塞中的代码根本
        //   不检查取消标志，那个 75 秒的 `withTimeoutOrNull` 到点也**不会**返回。
        //   结果就是超时形同虚设，用户依旧永远等不到结论（正是要修的那个缺陷）。
        //
        //   所以改成**两条独立协程赛跑**：谁先到谁写终态，用 `settled` 仲裁。
        //   - 结果协程：跑完那条阻塞链（可能几分钟都不回来）；
        //   - 计时协程：`delay` 到点直接写「超时」终态，**不等**结果协程。
        //   超时后结果协程若晚到，`settled` 已被占，它的结果被丢弃（代际号同理）。
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)

        val timer = DshApplication.applicationScope.launch {
            delay(PAIR_BUDGET_MS)
            if (!settled.compareAndSet(false, true)) return@launch
            if (pairingGeneration.get() != generation) return@launch
            Log.w(DshApplication.TAG, "配对超过 ${PAIR_BUDGET_MS / 1000} 秒未返回，判定超时")
            // ⚠️ 超时也**要留标记**：那条常驻通知此刻还在，进程若被杀，
            //   下次启动要靠这个标记撤掉它（与失败分支同理）。
            markPending(app, true)
            notify(
                app,
                app.getString(R.string.pairing_failed_title),
                app.getString(R.string.pairing_timeout, (PAIR_BUDGET_MS / 1000).toInt()),
                port,
                true,
            )
        }

        DshApplication.applicationScope.launch {
            val result: WirelessDebugging.Result? = try {
                EngineManager.get(app).pairWirelessDebugging(code, port)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "配对流程异常", t)
                WirelessDebugging.Result(false, t.message
                    ?: app.getString(R.string.pairing_failed_generic))
            }

            // 抢不到 `settled` 说明计时协程已经判过超时了，这次结果作废。
            if (!settled.compareAndSet(false, true)) {
                Log.i(DshApplication.TAG, "配对结果迟到（已判超时），丢弃")
                return@launch
            }
            timer.cancel()

            // 代际不匹配说明这次的结果已过时（用户重发了一次码）。
            if (pairingGeneration.get() != generation) {
                Log.i(DshApplication.TAG, "丢弃过期的配对结果（代际 $generation 已被取代）")
                return@launch
            }

            if (result != null && result.ok) {
                Log.i(DshApplication.TAG, "配对结果 ok=true msg=${result.message}")
                // 成功了就没什么可等的了。停掉监听，否则那条常驻通知会一直挂着。
                markPending(app, false)
                stopWatch()
                notify(app, app.getString(R.string.pairing_title), app.getString(R.string.pairing_ok), null, false)
            } else {
                val reason = result?.message
                    ?: app.getString(R.string.pairing_timeout, (PAIR_BUDGET_MS / 1000).toInt())
                Log.i(DshApplication.TAG, "配对结果 ok=false msg=$reason")
                // ⚠️ 失败**要留标记**：此时监听与输入框都还开着（等用户重发），
                //   若进程在这期间被杀，下次启动要能撤掉这条常驻通知。
                markPending(app, true)
                // 失败时保留输入框让用户重试 —— 最常见的失败是码抄错，
                // 让用户重开一遍系统界面代价太大。
                //
                // ⚠️ 监听本身**不停**：那个「使用配对码配对设备」界面可能还开着，
                //   端口还在，用户改一位数字重发就行。
                notify(app, app.getString(R.string.pairing_failed_title), reason, port, true)
            }
        }
    }

    /**
     * 确保配对通知渠道已存在，并**借此让系统补弹通知权限框**。
     *
     * ## ⚠️ 为什么必须由 App 启动时主动调（真机反馈修掉的缺陷）
     *
     * 本 App 的 `targetSdk = 28`（硬约束②，不能升），在 Android 13+ 上属于
     * **旧应用**：系统不会把 `POST_NOTIFICATIONS` 当成运行时权限去要，而是走
     * 「**创建第一个通知渠道时补弹一次**」的兼容路径。于是：
     *
     * - 原实现的渠道只在用户点「配对无线调试」时才建（[start] 里）——
     *   也就是说用户**第一次打开 App 时压根没有渠道**，那个补弹根本没机会发生；
     * - 用户必须退出重进（此时渠道已存在）才会被问，正是真机反馈的现象。
     *
     * ⚠️ 这也解释了为什么**判权限位没有用**：旧应用的 `POST_NOTIFICATIONS` 是
     *   **隐式已授予**，`checkSelfPermission` 与 `areNotificationsEnabled()`
     *   都返回 `true`（真机实测 appops 为 `Default mode: allow`），任何基于它们
     *   的早返回都会让权限永远没人申请。
     *
     * 所以正确做法是**在启动早期就把渠道建出来**，把「要不要问用户」这件事
     * 交回系统自己按平台规则决定。
     */
    fun ensureChannel(context: Context) {
        runCatching { createChannel(context.applicationContext) }
            .onFailure { Log.w(DshApplication.TAG, "创建配对通知渠道失败", it) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            existing.name = context.getString(R.string.pairing_channel_name)
            existing.description = context.getString(R.string.pairing_channel_desc)
            manager.createNotificationChannel(existing)
            return
        }
        // ⚠️ 必须 HIGH：配对是**需要用户立刻操作**的一次性交互，
        //    用引擎那条 IMPORTANCE_LOW 的渠道会静默躺在通知栏里没人看见。
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pairing_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.pairing_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发/更新配对通知。
     *
     * @param port 配对端口；非 null 时带输入框。
     * @param ongoing 是否常驻（划不掉）。等待端口与等待输入码两种状态都必须常驻 ——
     *   前者是「正在等你操作」的指引，被划掉用户就不知道该去哪开无线调试了；
     *   后者更不用说。终态（成功）才让它自己走掉。
     */
    private fun notify(context: Context, title: String, text: String, port: Int?, ongoing: Boolean): Boolean {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)

        if (port != null) {
            builder.addAction(replyAction(context, port))
        }
        if (ongoing) {
            builder.setOngoing(true)
            // ⚠️ 常驻通知**划不掉**，所以必须给一个取消出口：万一进程被系统回收，
            //   监听已经没了，那条通知却还挂着，用户就只能去强制停止应用。
            builder.addAction(cancelAction(context))
        }

        return try {
            val manager = NotificationManagerCompat.from(context)
            // ⚠️ 权限被禁时 `notify()` **不抛异常**，只是静默什么都不做（Android 13+
            //   未授权 POST_NOTIFICATIONS 就是这样）。只看 try/catch 会把「用户
            //   什么也没看到」误判成「已成功显示」，于是监听照常挂起、而用户界面
            //   上找不到任何出口。所以这里要**显式查一次权限**。
            if (!manager.areNotificationsEnabled()) {
                Log.w(DshApplication.TAG, "配对通知未显示：本应用的通知被系统关闭")
                return false
            }
            manager.notify(NOTIFICATION_ID, builder.build())
            true
        } catch (t: Throwable) {
            // 通知发不出去不该让配对流程崩掉（个别 ROM 仍会抛 SecurityException）
            Log.w(DshApplication.TAG, "配对通知发送失败: ${t.message}")
            false
        }
    }

    /** 带「直接回复」输入框的通知动作。 */
    private fun replyAction(context: Context, port: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(EXTRA_REPLY)
            .setLabel(context.getString(R.string.pairing_input_label))
            .build()

        val intent = Intent(context, AdbPairingReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_PORT, port)
        }
        // ⚠️ **必须 MUTABLE**：RemoteInput 的结果是系统在投递时**写进 Intent** 的，
        //    immutable 的 PendingIntent 会让系统无法回填，表现是「输入框能打字，
        //    但回复后什么都没发生」。API 31 起这是硬性要求。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, port, intent, flags)

        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.pairing_reply),
            pending,
        ).addRemoteInput(remoteInput).build()
    }

    /** 「取消」动作：停掉常驻监听并撤掉通知。 */
    private fun cancelAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, AdbPairingReceiver::class.java).apply {
            action = ACTION_CANCEL
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.pairing_stop), pending).build()
    }
}

/**
 * 接收用户在通知栏输入的配对码，以及通知上的取消动作。
 *
 * ⚠️ 必须在 manifest 里声明（`exported="false"`）：通知可能在 App 进程已被回收时
 * 仍留在通知栏，动态注册的接收器那时收不到。
 */
class AdbPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdbPairingNotifier.ACTION_CANCEL -> {
                Log.i(DshApplication.TAG, "用户在通知栏取消了配对")
                AdbPairingNotifier.cancel(context)
                return
            }
            AdbPairingNotifier.ACTION_REPLY -> Unit
            else -> return
        }

        val results = RemoteInput.getResultsFromIntent(intent)
        val code = results?.getCharSequence(AdbPairingNotifier.EXTRA_REPLY)?.toString()?.trim().orEmpty()
        val port = intent.getIntExtra(AdbPairingNotifier.EXTRA_PORT, -1)

        if (code.isEmpty()) {
            Log.w(DshApplication.TAG, "配对码为空，忽略这次回复")
            return
        }
        if (port <= 0) {
            Log.w(DshApplication.TAG, "配对通知没带端口，无法配对")
            return
        }

        // ⚠️ 只做**投递**，不等结果 —— 这是 ANR 修复的关键一环。
        //
        //   原来这里 `goAsync()` 之后起一个 Thread 跑完整条配对链，并**在结束时**才
        //   `pending.finish()`。但配对最坏要几十秒，而 `goAsync()` 的宽限期只有约 10 秒
        //   （前台广播）/ 数十秒，超时系统就判 ANR 弹「DSH 没有响应」——
        //   用户看到的现象正是「一直显示正在配对 → 程序挂了」。
        //
        //   现在 `pairWithCode` 内部把重活交给自己的受管线程池并**立即返回**，
        //   广播接收器随即 finish，与配对耗时彻底解耦。
        val pending = goAsync()
        try {
            AdbPairingNotifier.pairWithCode(context, port, code)
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "配对接管失败", t)
        } finally {
            pending.finish()
        }
    }
}
