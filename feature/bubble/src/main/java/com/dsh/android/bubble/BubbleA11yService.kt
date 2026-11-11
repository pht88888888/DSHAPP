package com.dsh.android.bubble
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.dsh.android.log.DshLog
import com.dsh.android.log.EventLog

/**
 * 键盘哨兵：**App 在后台时**也能知道输入法有没有弹出来、弹多高。
 *
 * ## 为什么需要它
 *
 * 悬浮球展开态要避开键盘，现在靠的是「输入框拿到焦点 ⇒ 键盘即将弹出」这个**等价条件**
 * （见 `FloatingBubble.applyImeHeight`），因为悬浮窗拿不到 IME insets。但收起态
 * 完全不知道键盘在不在 —— 球停在屏幕底部时，别的 App 一弹键盘它就**被盖住**，
 * 用户想拖回来还得先关掉键盘。
 *
 * 本服务用系统给的 `getWindows()` 直接看**输入法窗口**的真实边界：
 *
 * ```
 * AccessibilityWindowInfo.getType() == TYPE_INPUT_METHOD
 *         └─ getBoundsInScreen(rect)   → rect.top 就是键盘顶，rect.height() 就是键盘高度
 * ```
 *
 * 这条信息**不受前台/后台限制** —— 无障碍服务常驻，别的 App 弹键盘同样看得到。
 *
 * ## 为什么不能不用无障碍
 *
 * - 普通应用没有「全局键盘可见性」API：`InputMethodManager` 只有 `show/hide`，
 *   `WindowInsets.Type.ime()` 只属于**自己获得焦点**的那扇窗。
 * - `dumpsys input_method` 能读，但那要 uid 2000（Shizuku/无线调试/root）在线，
 *   而且每次都要起进程轮询 —— 见 `dsh-android-shell.mjs` 开头的身份对照表。
 * - 所以**无障碍是唯一零特权、零轮询进程**的路：只要用户在设置里开一次。
 *
 * ## 怎么开
 *
 * 系统设置 → 无障碍（或「辅助功能」）→ 已下载的服务 → **DSH 键盘哨兵**。
 * 也可以用特权通道一条命令打开（`com.dsh.android` / gecko 版是 `com.dsh.webview`）：
 *
 * ```
 * settings put secure enabled_accessibility_services <包名>/com.dsh.android.BubbleA11yService
 * settings put secure accessibility_enabled 1
 * ```
 *
 * ⚠️ 上面那条会**覆盖**其它已启用的无障碍服务，已有的话要把原值接在后面、
 *    用 `:` 连接后再写。
 *
 * ## 代价与边界
 *
 * - 只索取 `flagRetrieveInteractiveWindows`：**读窗口列表，不读窗口内容**
 *   （`canRetrieveWindowContent=false` 也不影响拿 bounds，这里设成 true 是为了
 *    部分 ROM 不返回窗口列表；本服务**不调用** `getRootInActiveWindow()`，不碰任何控件文本）。
 * - 只用两类事件：`TYPE_WINDOWS_CHANGED` / `TYPE_WINDOW_STATE_CHANGED`，
 *   外加「悬浮球显示期间」的 1 秒兜底轮询 —— 个别 ROM 不发窗口变化事件，
 *   纯事件驱动会漏掉键盘。
 * - 服务被用户关掉后一切回到旧行为（球不再避让键盘），不报错、不降级失败。
 */
class BubbleA11yService : AccessibilityService() {

    /** 事件节流：`TYPE_WINDOWS_CHANGED` 在切换应用时会连发好几条。 */
    private var lastPollMs = 0L

    /**
     * 加速窗口的截止时刻（`uptimeMillis`）。
     *
     * 收到任何事件（尤其是输入框聚焦）后的 [TURBO_MS] 内，轮询间隔降到
     * [POLL_TURBO_MS] —— 键盘**就在这段时间里弹出来**，把轮询踩在这个窗口上，
     * 就能把"键盘已出现 → 弹射开始"压到个位数帧。平时退回 [POLL_ACTIVE_MS]，
     * 不为一个还没发生的事件长期烧电。
     */
    @Volatile
    private var turboUntilMs = 0L

    private fun inTurbo(): Boolean = SystemClock.uptimeMillis() < turboUntilMs

    /** 事件日志节流：用来在真机上确认「这台 ROM 到底发不发 windows-changed」。 */
    private var lastEventLogMs = 0L

    /** 兜底轮询：只在悬浮球显示期间真的去读窗口（见 [ImeWindowState.keepPolling]）。 */
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            // ⚠️ **必须无条件续期**。原先写成「不 keepPolling 就 return」，于是球第一次
            //   显示之后再也没人启动这个心跳 —— 键盘一次都不会被兜底轮询到，
            //   而事件驱动在部分 ROM 上并不发 TYPE_WINDOWS_CHANGED。
            val polling = ImeWindowState.keepPolling
            if (polling) poll(force = false)
            val next = when {
                !polling -> POLL_IDLE_MS
                inTurbo() -> POLL_TURBO_MS
                else -> POLL_ACTIVE_MS
            }
            main.postDelayed(this, next)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = this
        // XML 里已声明；这里再设一次，因为部分 ROM 会在服务重启后把 flags 重置。
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = POLL_MIN_MS
        }
        DshLog.i(TAG, "键盘哨兵已连接（无障碍）")
        EventLog.record("a11y.connected")
        poll(force = true)
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, POLL_IDLE_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            return
        }
        // 任何一条相关事件都开启一小段加速窗口：键盘通常紧随焦点变化出现。
        turboUntilMs = SystemClock.uptimeMillis() + TURBO_MS
        logEventType(type)
        poll(force = false)
    }

    /** 事件类型日志（限流）—— 真机上用它确认事件到底有没有送达。 */
    private fun logEventType(type: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEventLogMs < EVENT_LOG_MIN_MS) return
        lastEventLogMs = now
        DshLog.i(TAG, "无障碍事件: ${AccessibilityEvent.eventTypeToString(type)}（已加速轮询 ${TURBO_MS}ms）")
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown("服务解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown("服务销毁")
        super.onDestroy()
    }

    private fun teardown(reason: String) {
        if (alive === this) alive = null
        main.removeCallbacks(ticker)
        ImeWindowState.publish(visible = false, topPx = 0, heightPx = 0)
        DshLog.i(TAG, "键盘哨兵已断开：$reason")
    }

    /**
     * 读一次输入法窗口。
     *
     * @param force 跳过节流（服务刚连接时必须立刻给一个初值）。
     */
    private fun poll(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPollMs < POLL_MIN_MS) return
        lastPollMs = now

        val screenH = resources.displayMetrics.heightPixels
        val bounds = imeWindowBounds()
        if (bounds == null || bounds.height() <= 0 || bounds.top >= screenH) {
            ImeWindowState.publish(visible = false, topPx = 0, heightPx = 0)
            return
        }
        val height = (screenH - bounds.top).coerceAtLeast(bounds.height())
        ImeWindowState.publish(visible = true, topPx = bounds.top, heightPx = height)
    }

    /**
     * 当前输入法窗口在屏幕上的边界；没有则 null。
     *
     * ⚠️ 键盘**收起时也可能有一个零高度/屏幕外的窗口**在列表里，所以调用方必须
     *   同时判 `height() > 0` 与 `top < 屏幕高` —— 只看「列表里有没有」会得到
     *   「键盘永远开着」。
     */
    private fun imeWindowBounds(): Rect? {
        val list = try {
            windows
        } catch (t: Throwable) {
            DshLog.w(TAG, "读取无障碍窗口列表失败: ${t.message}")
            null
        } ?: return null

        var best: Rect? = null
        val r = Rect()
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            w.getBoundsInScreen(r)
            if (r.height() <= 0) continue
            if (best == null || r.top < best.top) best = Rect(r)
        }
        return best
    }

    companion object {
        private const val TAG = "DSH"

        /** 事件节流下限（ms）。 */
        private const val POLL_MIN_MS = 40L

        /**
         * 悬浮球显示期间的兜底轮询间隔（ms）。
         *
         * 120ms ≈ 每秒 8 次 `getWindows()`：跨进程调用本身很轻，代价可接受；
         * 真机反馈 200ms 时"延迟变短了但仍有明显感觉"，所以再压一档。
         */
        private const val POLL_ACTIVE_MS = 120L

        /**
         * 加速窗口内的轮询间隔（ms）。
         *
         * 收到任何相关事件（焦点变化 / 窗口变化）后的 [TURBO_MS] 内用这个节奏：
         * 键盘就是在这段时间里弹出来的，把采样踩在这里，检测延迟就只剩
         * 「键盘窗口进入窗口列表」本身的那点时间。
         */
        private const val POLL_TURBO_MS = 50L

        /** 加速窗口持续时长（ms）—— 覆盖"点输入框 → 键盘动画完成"这一段。 */
        private const val TURBO_MS = 1500L

        /** 事件类型日志的最小间隔（ms），防止窗口切换时刷屏。 */
        private const val EVENT_LOG_MIN_MS = 400L

        /** 悬浮球不在场时的兜底间隔（ms）—— 只为状态不卡死，不需要快。 */
        private const val POLL_IDLE_MS = 1000L

        @Volatile
        private var alive: BubbleA11yService? = null

        /** 服务是否正在运行（与「用户是否启用」不同：启用但进程被杀时为 false）。 */
        val isConnected: Boolean get() = alive != null

        /**
         * 立刻读一次窗口（主线程）。
         *
         * 悬浮球刚显示时调它：心跳的节奏是"上一轮"决定的，球刚出现那一刻可能
         * 还排在 1 秒后的队尾，用户如果马上弹键盘就会觉得慢半拍。
         */
        fun requestPollNow() {
            val svc = alive ?: return
            svc.main.post { svc.poll(force = true) }
        }

        fun component(context: Context): ComponentName =
            ComponentName(context.packageName, BubbleA11yService::class.java.name)

        /**
         * 用户是否在系统设置里启用了本服务。
         *
         * 判据是 `ENABLED_ACCESSIBILITY_SERVICES` 里有没有我们的组件名 ——
         * 这是系统唯一的"已授权"事实来源，别用"服务在跑"代替：用户刚打开开关时
         * 组件已经写进去、服务可能还没连上。
         */
        fun isEnabled(context: Context): Boolean {
            val expected = component(context)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            while (splitter.hasNext()) {
                val name = splitter.next()
                if (name.equals(expected.flattenToString(), true) ||
                    name.equals(expected.flattenToShortString(), true)
                ) {
                    return true
                }
            }
            return false
        }

        /** 打开系统的无障碍设置页（用户在这里启用本服务）。 */
        fun openSettings(context: Context) {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
                .onFailure { DshLog.w(TAG, "打开无障碍设置页失败: ${it.message}") }
        }

        /** 启动时打一条状态日志（不弹窗、不阻塞）。 */
        fun logState(context: Context) {
            val enabled = isEnabled(context)
            val running = isConnected
            val hint = when {
                running -> "已连接，键盘弹起时悬浮球会避让"
                enabled -> "已启用但尚未连接（系统会在合适的时机拉起）"
                else -> "未启用 —— 键盘弹起时悬浮球不会避让。启用：系统设置 → 无障碍 → DSH 键盘哨兵"
            }
            DshLog.i(TAG, "键盘哨兵：$hint")
        }
    }
}

/**
 * 输入法窗口状态的**进程内**单一真相源。
 *
 * 无障碍服务写、悬浮球读 —— 两边都跑在主进程，不需要 Binder / 文件 / 广播。
 * 发布用 `publish()`，**去重**后再转发给 [FloatingBubble]：`TYPE_WINDOWS_CHANGED`
 * 一次切换会连发十几条，每条都去动一次 physics 的话球会持续抖动。
 */
internal object ImeWindowState {

    @Volatile
    var visible: Boolean = false
        private set

    /** 键盘顶在屏幕上的 y 坐标（px）；不可见时 0。 */
    @Volatile
    var topPx: Int = 0
        private set

    /** 键盘高度（px）；不可见时 0。 */
    @Volatile
    var heightPx: Int = 0
        private set

    /**
     * 悬浮球显示期间置 true，让服务以 1 秒间隔兜底轮询。
     *
     * ⚠️ 不能让服务无条件每秒轮询：无障碍服务是常驻的，用户不开悬浮球时
     *   一次都不该浪费 —— `getWindows()` 每次都要跨进程拿一遍窗口列表。
     */
    @Volatile
    var keepPolling: Boolean = false
        private set

    fun setKeepPolling(on: Boolean) {
        val was = keepPolling
        keepPolling = on
        // 刚变成"要轮询"时补一次立即读取，别等心跳排队。
        if (on && !was) BubbleA11yService.requestPollNow()
    }

    fun publish(visible: Boolean, topPx: Int, heightPx: Int) {
        if (this.visible == visible && this.topPx == topPx && this.heightPx == heightPx) return
        this.visible = visible
        this.topPx = topPx
        this.heightPx = heightPx
        if (visible) {
            DshLog.i("DSH", "键盘窗口：弹出（顶=$topPx px，高=$heightPx px）")
            EventLog.record("ime.shown", mapOf("top" to topPx.toString(), "height" to heightPx.toString()))
        } else {
            DshLog.i("DSH", "键盘窗口：收起")
            EventLog.record("ime.hidden")
        }
        FloatingBubble.onImeWindow(visible, topPx, heightPx)
    }
}
