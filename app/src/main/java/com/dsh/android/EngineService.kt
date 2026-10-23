package com.dsh.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.dsh.android.log.DshLog as Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * 前台服务：让引擎进程在 App 切到后台后继续存活。
 *
 * 没有它，系统会在内存紧张时回收整个进程，正在进行的对话就断了。
 *
 * 这里承担三件事：
 *
 * 1. **前台服务 + 常驻通知** —— 把进程的 oom_adj 钉在前台档位，系统不会因内存压力
 *    回收它。这是「App 在后台还活着」的前提。
 * 2. **PARTIAL_WAKE_LOCK** —— 只保活进程是不够的：锁屏后 CPU 会进 suspend，
 *    node 的事件循环、定时器、网络回调全被冻住，长任务照样断。PARTIAL 只保 CPU
 *    不保屏幕，正好是引擎需要的。
 * 3. **实时状态通知** —— 通知正文跟着 AI 当前动作走（见 [EngineStatus]），
 *    用户不进 App 也能一眼看出引擎在干活还是闲着。
 *
 * ⚠️ 光有这些在国产 ROM 上仍可能被「电池优化 / 后台冻结」杀掉，那属于系统策略，
 * 只能由用户把本 App 加进电池优化白名单（见 `MainActivity.ensureBatteryExempt()`）。
 */
class EngineService : Service() {

    /** 只在服务存续期间持有；进程被杀时内核会自动释放，不会漏。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alive = true
        // 服务重建时清掉上一轮的状态，避免通知里残留旧会话的文案
        lastStatus = null
        try {
            startForeground(NOTIFICATION_ID, buildNotification(this, null))
        } catch (t: Throwable) {
            // Android 12+ 在后台启动前台服务可能抛异常；此时退化为普通服务
            Log.w(DshApplication.TAG, "startForeground 失败: ${t.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        releaseWakeLock()
        super.onDestroy()
    }

    /** 点通知回到 App（引擎还活着，MainActivity 会直接复用已有的 WebView 状态）。 */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 拿一把 PARTIAL_WAKE_LOCK。
     *
     * 刻意**不带超时**：超时到期会静默释放，长任务跑到一半 CPU 就被允许休眠了。
     * 生命周期由服务自己管（`onDestroy` 释放），服务在则锁在。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            try {
                acquire()
                Log.i(DshApplication.TAG, "已持有 PARTIAL_WAKE_LOCK，锁屏后引擎继续运行")
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "获取 WakeLock 失败: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
            Log.i(DshApplication.TAG, "已释放 WakeLock")
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dsh-engine"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "dsh:engine"

        /**
         * 各段文本的字符数上限 —— **通知卡片高度的实际控制点**。
         *
         * 通知卡的高度由文本行数决定，而助手输出长度不受控（思考段落动辄几百字），
         * 所以必须在这里设上界，否则卡片会吃掉大半个通知栏。
         *
         * 取值依据：360dp 宽的手机上正文一行约 20 个中文字符，展开态可见约 5 行。
         * 100 字符 ≈ 5 行，正好填满展开态而不溢出。
         */
        private const val TITLE_MAX_CHARS = 40
        private const val SUBTITLE_MAX_CHARS = 30
        private const val BODY_MAX_CHARS = 100

        private val WHITESPACE = Regex("\\s+")

        /** 服务是否存活。为 false 时不更新通知（否则会凭空造出一条普通通知）。 */
        @Volatile
        private var alive = false

        /** 最近一次收到的状态；null 表示页面还没上报（引擎启动早期）。 */
        @Volatile
        private var lastStatus: EngineStatus? = null

        fun start(context: Context) {
            val intent = Intent(context, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }

        /**
         * 最近一次状态快照；null 表示页面还没上报过。
         *
         * 给悬浮气泡用：气泡是在 App 退到后台之后才创建的，创建那一刻拿不到
         * "上一次推送"（推的时候它还不存在），所以必须能主动拉一次当前值。
         */
        fun currentStatus(): EngineStatus? = lastStatus

        /**
         * 用 WebView 上报的状态刷新通知。
         *
         * 由 `MainActivity` 的 JS 桥调用，**运行在 WebView 的 JS 线程**上，
         * 所以这里不做任何 UI 操作，只走 NotificationManager（线程安全）。
         *
         * 状态没变化时直接返回：通知栏不闪、不耗电。
         */
        fun updateStatus(context: Context, status: EngineStatus) {
            if (!alive) return
            // ⚠️ `bg` 只影响窗口底色（由 MainActivity 单独应用），**不参与通知去重**：
            //   主题切换时通知内容一个字都没变，不该因此白发一次通知。
            if (lastStatus?.copy(bg = "") == status.copy(bg = "")) return
            lastStatus = status
            // 顺带喂给悬浮气泡。**放在去重之后**：气泡和通知一样只需要变化时刷新，
            // 而且气泡自己会在展开时主动拉一次当前状态，不怕错过。
            FloatingBubble.onStatus(status)
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(context, status))
            } catch (t: Throwable) {
                Log.w(DshApplication.TAG, "刷新状态通知失败: ${t.message}")
            }
        }

        /** 状态未就绪时退回到最初的「引擎正在运行」文案。 */
        private fun buildNotification(context: Context, status: EngineStatus?): Notification {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openAppIntent(context))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // ⚠️ 必须 onlyAlertOnce + silent：状态每秒都可能更新，
                //    否则每次刷新都会重新提醒一次（响铃/亮屏）。
                .setOnlyAlertOnce(true)
                .setSilent(true)

            if (status == null) {
                return builder
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notification_engine_running))
                    .build()
            }

            // 标题 = AI 当前动作（运行中用官方的「正在运行命令」这类文案，
            // 并把 liveToolDetail 提上来的参数一并带上）。
            // 前缀圆点是"还活着"的最直观标记：实心=在跑，空心=空闲。
            val headline = when {
                status.text.isNotEmpty() -> status.text
                status.running -> context.getString(R.string.notification_thinking)
                else -> context.getString(R.string.notification_idle)
            }
            val title = (if (status.running) "● " else "○ ") + headline

            builder.setContentTitle(truncateHead(title, TITLE_MAX_CHARS))
            // 副标题放会话标题：同时开多个会话时能分清是哪个在跑。
            if (status.title.isNotEmpty()) {
                builder.setSubText(truncateHead(status.title, SUBTITLE_MAX_CHARS))
            }
            // ⚠️ 三段都必须截断，否则卡片高度会随内容浮动：
            //   `BigTextStyle` 的展开态是按正文**实际行数**撑高的，而助手输出动辄几百字，
            //   不截断时通知卡会吃掉大半个通知栏（用户实测反馈）。
            //   截断后每段行数有上界 ⇒ 卡片高度固定，且更新时不会忽高忽低地跳。
            //
            // ⚠️ 正文用 truncateTail 而不是 truncateHead：助手消息是**流式追加**的，
            //   最新进展在末尾。保留头部会把通知栏钉死在几分钟前那句话上，
            //   反而看不出"现在在干什么"。
            val body = truncateTail(status.body, BODY_MAX_CHARS)
            builder.setContentText(body.ifEmpty { context.getString(R.string.notification_no_content) })
            if (body.isNotEmpty()) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
            return builder.build()
        }

        /**
         * 压成单行、按 code point 截断并保留**头部**（标题、副标题这类从头读的文本）。
         *
         * 按 code point 切，避免把一个 emoji / 代理对截成半个字符（那会显示成乱码方块）。
         */
        private fun truncateHead(text: String, maxChars: Int): String {
            val cps = flatten(text).codePoints().toArray()
            if (cps.size <= maxChars) return String(cps, 0, cps.size)
            return String(cps, 0, maxChars - 1).trimEnd() + "…"
        }

        /** 同 [truncateHead]，但保留**尾部** —— 流式输出的最新内容在末尾。 */
        private fun truncateTail(text: String, maxChars: Int): String {
            val cps = flatten(text).codePoints().toArray()
            if (cps.size <= maxChars) return String(cps, 0, cps.size)
            return "…" + String(cps, cps.size - (maxChars - 1), maxChars - 1).trimStart()
        }

        /** 折叠所有空白成单空格：通知栏不会渲染换行，多行文本会挤成难读的一长条。 */
        private fun flatten(text: String): String = text.replace(WHITESPACE, " ").trim()

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

/**
 * 插件从页面里读出来的一份状态快照。
 *
 * 字段由 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` 的 `mpCollectStatus()`
 * 产生，两边必须一起改。
 */
data class EngineStatus(
    /** AI 是否正在跑（页面主按钮是「停止生成」）。 */
    val running: Boolean,
    /** 当前活动标题，如「正在运行命令 · sleep 10」；空闲时是上一组的「执行了命令」。 */
    val text: String,

    /**
     * 上报这份状态的**会话 ID**（拿不到时为空串）。
     *
     * ## 为什么需要它
     *
     * 设计说明 §4.11 / 验收矩阵第 18 条要求「多会话事件有明确跟随/聚合规则」。
     * 引擎可同时跑多个会话，而悬浮窗只有一个显示位 —— 没有 sessionId 时，
     * `SessionAggregator` 无法区分「这是哪个会话在报状态」，
     * 多个会话的状态会互相覆盖。
     *
     * ⚠️ 空串 = 页面拿不到当前会话（首帧、官方 DOM 变了、或非 App 环境）。
     *   此时聚合器会把它当成「无归属状态」处理，**不影响主显示**。
     */
    val sessionId: String = "",
    /** 最后一条助手消息（运行中含正在流式的思考内容）。 */
    val body: String,
    /** 会话标题，用作通知副标题。 */
    val title: String,
    /**
     * 页面实际背景色（`#RRGGBB`），用于对齐 WebView / 容器 / 窗口的底色。
     *
     * 只影响窗口底色，不影响通知内容 —— 所以 `updateStatus()` 比较时把它排除。
     */
    val bg: String,
    /**
     * 页面给的**粗粒度活动**（`data-process-activity` 属性），如 `thinking`
     * / `tool` / 空串。
     *
     * ⚠️ 这条通道原先一直是**通着的但没接**：`mpCollectStatus()` 一直在上报
     *   `activity`，而这里的 [parse] 把它丢掉了 —— 于是"页面已经知道 AI 在
     *   干什么"这一层信息到不了原生。桌面宠物按状态换动作就用它当**粗粒度**依据
     *   （细粒度那一路是 `session/event`，见 `dsh-android-pet` 插件）。
     *
     * ⚠️ 粒度就是粗的：页面每 1 秒才采一次（`mpScheduleStatus` 的节流），
     *   短于一秒的动作会整个漏掉。要精确就用插件那条通道。
     */
    val activity: String,
) {
    companion object {
        /**
         * 解析插件发来的 JSON。
         *
         * ⚠️ 任何字段缺失/类型不对都退化成空串而不是抛异常 —— 通知是锦上添花，
         * 不能因为页面改了个字段就把引擎进程搞崩。
         */
        fun parse(json: String): EngineStatus? = try {
            val o = JSONObject(json)
            EngineStatus(
                running = o.optBoolean("running", false),
                text = o.optString("text", ""),
                // 多会话聚合用（设计说明 §4.11 / 验收矩阵第 18 条）。
                // ⚠️ 旧版页面不报这个字段 ⇒ 空串 ⇒ 聚合器当「无归属状态」处理，
                //   不破坏兼容。
                sessionId = o.optString("sessionId", ""),
                body = o.optString("body", ""),
                title = o.optString("title", ""),
                bg = o.optString("bg", ""),
                activity = o.optString("activity", ""),
            )
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "状态上报解析失败: ${t.message}")
            null
        }
    }
}
