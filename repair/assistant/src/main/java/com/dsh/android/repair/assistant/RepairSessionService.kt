package com.dsh.android.repair.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.dsh.android.R
import com.dsh.android.repair.contract.RepairProtocol
import java.util.UUID
import java.util.concurrent.Executors

/** User-started, notification-backed session. A cached process or timer is not activity. */
class RepairSessionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client by lazy { RepairClient(this) }
    private var session: Session? = null
    private var lastSent: Long? = null
    private var inFlight = false
    private val tick = Runnable { heartbeat() }

    private data class Session(val target: String, val grant: String, val id: String = UUID.randomUUID().toString())
    private data class ActivityMark(val grant: String, val time: Long)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val active = session
            if (active != null && active.id == intent.getStringExtra(EXTRA_SESSION)) {
                RepairAgentBridge.stop(active.target, active.grant)
                closeSession()
            } else if (active == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val pkg = intent?.getStringExtra(EXTRA_TARGET)
        val grant = intent?.getStringExtra(RepairProtocol.EXTRA_GRANT_ID)
        if (intent?.action != ACTION_START || pkg == null || pkg !in RepairProtocol.TARGET_PACKAGES ||
            grant.isNullOrBlank() || grant.length > 64) {
            if (session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Recreated Activities rebind the dispatcher; never reset the idle lease/session notification.
        if (session?.let { it.target == pkg && it.grant == grant } == true) return START_NOT_STICKY
        val next = Session(pkg, grant)
        session?.takeIf { it.target != next.target || it.grant != next.grant }?.let {
            RepairAgentBridge.stop(it.target, it.grant)
        }
        // Own the new binding before notification APIs: rejection must close this bridge too.
        session = next
        try {
            ensureChannel(this)
            startForeground(NOTIFICATION_ID, notification(next))
            if (!canNotify(getSystemService(NotificationManager::class.java))) {
                closeSession()
                return START_NOT_STICKY
            }
        } catch (_: RuntimeException) {
            closeSession()
            return START_NOT_STICKY
        }
        lastSent = null
        noteInteraction(pkg, grant)
        heartbeat()
        return START_NOT_STICKY
    }

    private fun heartbeat() {
        main.removeCallbacks(tick)
        val active = session ?: return
        val last = marks[active.target]?.takeIf { it.grant == active.grant }
        val remaining = (last?.time ?: 0) + RepairProtocol.IDLE_TIMEOUT_MS - SystemClock.elapsedRealtime()
        if (last == null || remaining <= 0) {
            closeSession("expired", "修复连接因长时间未操作已到期，请重新连接并授权。")
            return
        }
        if (!canNotify(getSystemService(NotificationManager::class.java))) {
            closeSession("error", "常驻通知已被禁用，修复连接已结束。请允许通知后重新连接。")
            return
        }
        main.postDelayed(tick, minOf(remaining, HEARTBEAT_MS))
        if (inFlight || lastSent == last.time) return
        inFlight = true
        lastSent = last.time
        worker.execute {
            val ok = runCatching { client.heartbeat(active.target, active.grant) }.getOrDefault(false)
            main.post {
                inFlight = false
                if (session == active && !ok) closeSession("expired", "目标维护授权已失效，修复连接已结束。请重新连接。")
            }
        }
    }

    private fun closeSession(phase: String = "disconnected", message: String = "修复连接已结束。普通 DSH 对话不受影响。") {
        session?.let {
            RepairAgentBridge.stop(it.target, it.grant, phase, message)
            revokeTask(it)
        }
        session = null
        main.removeCallbacks(tick)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun revokeTask(active: Session) {
        // Cleanup is target-owned; expiry/notification loss/service destruction also end the task.
        Thread({ runCatching { client.revoke(active.target, active.grant) } }, "repair-session-revoke")
            .apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        session?.let { RepairAgentBridge.stop(it.target, it.grant); revokeTask(it) }
        session = null
        main.removeCallbacks(tick)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun notification(active: Session): Notification {
        val stop = Intent(this, RepairSessionService::class.java).setAction(ACTION_STOP)
            .setData(Uri.fromParts("dsh-repair-session", active.id, null))
            .putExtra(EXTRA_SESSION, active.id)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_text))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.session_notification_stop), PendingIntent.getService(this, 0,
                    stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.dsh.fixhelp.action.START_REPAIR_SESSION"
        const val ACTION_STOP = "com.dsh.fixhelp.action.STOP_REPAIR_SESSION"
        const val EXTRA_TARGET = "target"
        private const val EXTRA_SESSION = "session"
        private const val CHANNEL_ID = "repair-session"
        private const val NOTIFICATION_ID = 7001
        private const val HEARTBEAT_MS = 30_000L
        private val marks = mutableMapOf<String, ActivityMark>() // Only accessed on the app main thread.

        fun noteInteraction(target: String, grant: String?) {
            if (grant != null) marks[target] = ActivityMark(grant, SystemClock.elapsedRealtime())
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                context.getString(R.string.session_notification_title), NotificationManager.IMPORTANCE_LOW))
        }

        fun canNotify(manager: NotificationManager): Boolean = manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_NONE) >
            NotificationManager.IMPORTANCE_NONE
    }
}
