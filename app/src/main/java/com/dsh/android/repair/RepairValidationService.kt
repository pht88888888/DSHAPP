package com.dsh.android.repair

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import com.dsh.android.core.DshApplication
import com.dsh.android.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import java.io.File

/** Non-sticky native validation host; never opens an Activity or Web GUI in the background. */
class RepairValidationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val task = intent?.getStringExtra(RepairRuntimeGate.EXTRA_TASK).orEmpty()
        val attempt = intent?.getStringExtra(RepairRuntimeGate.EXTRA_ATTEMPT).orEmpty()
        if (intent?.action != RepairRuntimeGate.ACTION_ENGINE || !RepairRuntimeGate.validId(task) || !RepairRuntimeGate.validId(attempt)) {
            stopSelf(startId); return START_NOT_STICKY
        }
        // Receipt is evidence of native service delivery, NOT permission to run the engine.
        // Even a rejected permit must leave a terminal attempt instead of silently waiting 180s.
        if (!writeAttempt(task, attempt, "starting", "starting")) {
            stopSelf(startId); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "DSH 后台验证 / Validation", NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("DSH 后台验证 / Validation")
                .setContentText("仅验证本次维修；不会打开界面")
                .setOngoing(true).setSilent(true).build())
        } catch (_: RuntimeException) {
            writeAttempt(task, attempt, "failed", "starting", "service_foreground_failed", stopped = true)
            stopSelf(startId); return START_NOT_STICKY
        }
        running = true
        scope.launch {
            var engine: EngineManager? = null
            var active: RepairRuntimeGate.Permit? = null
            var outcome = "engine_failed"
            var error = "permit_rejected"
            var stage = "starting"
            fun progress(next: String) {
                stage = next
                check(writeAttempt(task, attempt, "running", next)) { "Cannot record validation progress" }
            }
            try {
                // Covers bootstrap as well as engine startup. Synchronous Binder/OS operations
                // are not interruptible: :repair separately watches handshake/death/deadline.
                withTimeout(300000L) {
                    progress("starting")
                    active = RepairRuntimeGate.acquire(this@RepairValidationService, "engine", task, attempt)
                    val accepted = active ?: return@withTimeout
                    progress("preparing_runtime")
                    check((application as DshApplication).ensureRuntimeInitialized(accepted))
                    engine = EngineManager.get(this@RepairValidationService)
                    val host = checkNotNull(engine)
                    // A stale Ready from another attempt is never validation evidence.
                    check(host.state.value == EngineManager.State.Idle) { "Engine is not quiescent" }
                    host.resetUiStage()
                    withTimeout((accepted.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                        check(RepairRuntimeGate.valid(this@RepairValidationService, accepted))
                        progress("extracting_engine")
                        check(host.ensureExtracted(ASSET_STAMP))
                        check(RepairRuntimeGate.valid(this@RepairValidationService, accepted))
                        progress("starting_engine")
                        host.start(startupTimeoutMs = (accepted.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                        check(RepairRuntimeGate.valid(this@RepairValidationService, accepted))
                        val ready = host.state.value as? EngineManager.State.Ready ?: error("Engine not ready")
                        progress("checking_http")
                        val url = java.net.URL(ready.url)
                        check(url.protocol == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.connectTimeout = 5000; connection.readTimeout = 5000
                        connection.instanceFollowRedirects = false
                        try { check(connection.responseCode == 200) } finally { connection.disconnect() }
                        // Probe only the target's own loopback endpoint; never log the credential URL.
                        progress("stabilizing")
                        kotlinx.coroutines.delay(1500)
                        check(RepairRuntimeGate.valid(this@RepairValidationService, accepted))
                        check(host.state.value == ready)
                    }
                    outcome = "engine_ready"; error = ""
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { error = "validation_timeout" }
              catch (_: kotlinx.coroutines.CancellationException) { error = "validation_cancelled" }
              catch (_: Exception) { error = "engine_start_failed" }
            finally {
                withContext(NonCancellable) {
                    val failedStage = stage
                    writeAttempt(task, attempt, "running", "stopping_engine")
                    val stopped = runCatching { engine?.stopForRepair() ?: true }.getOrDefault(false)
                    if (!stopped) { outcome = "engine_failed"; error = "engine_stop_failed" }
                    val accepted = active
                    // This native-only receipt writer grants no execution authority. It also
                    // records permit rejection, before the permit/logging systems exist.
                    if (!writeAttempt(task, attempt, if (outcome == "engine_ready") "passed" else "failed",
                            if (outcome == "engine_ready") "complete" else failedStage, error, stopped, accepted != null)) {
                        outcome = "engine_failed"; error = "evidence_write_failed"
                    }
                    if (accepted != null) {
                        RepairRuntimeGate.report(this@RepairValidationService, accepted, outcome, error)
                        if (stopped) RepairRuntimeGate.report(this@RepairValidationService, accepted, "stopped")
                        RepairRuntimeGate.clear(accepted)
                    }
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun writeAttempt(task: String, attempt: String, status: String, stage: String,
        error: String = "", stopped: Boolean = false, includeEvidence: Boolean = false): Boolean {
        return try {
            check(RepairRuntimeGate.validId(task) && RepairRuntimeGate.validId(attempt))
            val directory = File(filesDir, "repair-control/validation")
            check(directory.mkdirs() || directory.isDirectory)
            val atomic = AtomicFile(File(directory, "$attempt.json"))
            val report = JSONObject().put("task_id", task).put("attempt_id", attempt)
                .put("level", "engine").put("status", status).put("stage", stage)
                .put("engine_ready", status == "passed").put("ui_ready", false).put("frame_ready", false)
                .put("error_code", error).put("stopped", stopped)
                .put("updated_elapsed", SystemClock.elapsedRealtime())
            if (status in setOf("passed", "failed")) report.put("completed_elapsed", SystemClock.elapsedRealtime())
            if (includeEvidence) {
                report.put("evidence", RepairDiagnostics.evidenceFromLogs(File(directory, "log-$attempt")))
                report.put("boot_id", com.dsh.android.log.BootState.bootId)
            }
            val output = atomic.startWrite()
            try { output.write(report.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            true
        } catch (_: Exception) { false }
    }
    override fun onDestroy() {
        // Cancellation enters the coroutine finally; cleanup is cooperative and may fail closed.
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val ASSET_STAMP = "0.2.2-rc.5-3"
        private const val CHANNEL = "dsh-target-validation"
        private const val NOTIFICATION = 7014
    }
}
