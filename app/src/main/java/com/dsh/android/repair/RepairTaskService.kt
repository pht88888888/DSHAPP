package com.dsh.android.repair

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dsh.android.R

/** Native target-side lifetime owner. Provider calls enqueue; never rely on a cached Provider. */
class RepairTaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "DSH 维修任务 / Maintenance", NotificationManager.IMPORTANCE_LOW))
        val stop = Intent(this, RepairTaskService::class.java).setAction(STOP)
        if (intent?.action == STOP) {
            RepairTaskCoordinator.end(this)
            return START_NOT_STICKY
        }
        val open = Intent().setClassName(packageName, "com.dsh.android.ui.MainActivity")
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("原 DSH 维修中 / Maintenance")
            .setContentText("自动修改仅限本次授权；可随时结束维修")
            .setOngoing(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 7012, open, PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "结束维修 / Stop", PendingIntent.getService(this, 7013, stop, PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { startForeground(NOTIFICATION, notification) }
        catch (_: RuntimeException) { RepairTaskCoordinator.end(this); stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }
    companion object {
        private const val CHANNEL = "dsh-target-repair"
        private const val NOTIFICATION = 7011
        private const val STOP = "com.dsh.android.repair.END_TASK"
        fun start(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "DSH 维修任务 / Maintenance", NotificationManager.IMPORTANCE_LOW))
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE)
            context.startForegroundService(Intent(context, RepairTaskService::class.java))
            true
        }.getOrDefault(false)
        fun stop(context: Context) { context.stopService(Intent(context, RepairTaskService::class.java)) }
    }
}
