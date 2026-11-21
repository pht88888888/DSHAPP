package com.dsh.android.repair

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.dsh.android.BuildConfig
import com.dsh.android.repair.contract.RepairProtocol
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Persistent task state is a fence, NEVER an authorization. Every execution asks :repair. */
object RepairRuntimeGate {
    const val ACTION_ENGINE = "com.dsh.android.repair.VALIDATE_ENGINE"
    const val ACTION_UI = "com.dsh.android.repair.VALIDATE_UI"
    const val ACTION_STOP = "com.dsh.android.repair.STOP_RUNTIME"
    const val EXTRA_TASK = "task_id"
    const val EXTRA_ATTEMPT = "attempt_id"
    private const val MAX_STATE_BYTES = 65536
    data class Permit(val taskId: String, val attemptId: String, val kind: String,
        val id: String, val deadlineElapsed: Long)
    private val current = AtomicReference<Permit?>()

    fun blocked(context: Context): Boolean {
        if (BuildConfig.IS_REPAIR_ASSISTANT) return false
        return try {
            val state = state(context) ?: return false
            state.optString("phase") != "complete"
        } catch (_: Exception) { true }
    }
    private fun state(context: Context): JSONObject? {
        val file = File(context.filesDir, "repair-control/task.json")
        check(file.parentFile!!.canonicalFile == File(context.filesDir.canonicalFile, "repair-control"))
        // Do not follow a model-created link or accept unbounded/corrupt state as no task.
        val s = try { Os.lstat(file.path) } catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.ENOENT) {
                check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
                return null
            } else throw e
        }
        check(OsConstants.S_ISREG(s.st_mode) && s.st_nlink == 1L && s.st_size in 1..MAX_STATE_BYTES.toLong())
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            val actual = Os.fstat(fd)
            check(actual.st_dev == s.st_dev && actual.st_ino == s.st_ino)
            val data = ByteArray(actual.st_size.toInt()); var at = 0
            while (at < data.size) { val n = Os.read(fd, data, at, data.size - at); check(n > 0); at += n }
            return JSONObject(String(data, Charsets.UTF_8))
        } finally { Os.close(fd) }
    }
    private fun call(context: Context, method: String, extras: Bundle): Bundle? = try {
        context.contentResolver.call(Uri.parse("content://${RepairProtocol.authority(context.packageName)}"), method, null, extras)
    } catch (_: Exception) { null }
    fun acquire(context: Context, kind: String, task: String, attempt: String): Permit? {
        if (kind !in setOf("engine", "ui") || !validId(task) || !validId(attempt)) return null
        val result = call(context, "repair_runtime_permit", Bundle().apply {
            putString("kind", kind); putString(EXTRA_TASK, task); putString(EXTRA_ATTEMPT, attempt)
        }) ?: return null
        val id = result.getString("permit_id") ?: return null
        val end = result.getLong("deadline_elapsed", 0)
        if (!result.getBoolean("allowed") || result.getString(EXTRA_TASK) != task ||
            result.getString(EXTRA_ATTEMPT) != attempt || result.getString("kind") != kind ||
            !validId(id) || end <= SystemClock.elapsedRealtime()) return null
        return Permit(task, attempt, kind, id, end).also { current.set(it) }
    }
    fun valid(context: Context, permit: Permit): Boolean {
        if (current.get() != permit || SystemClock.elapsedRealtime() >= permit.deadlineElapsed) return false
        val result = call(context, "repair_runtime_permit", Bundle().apply {
            putString("kind", permit.kind); putString(EXTRA_TASK, permit.taskId)
            putString(EXTRA_ATTEMPT, permit.attemptId); putString("permit_id", permit.id)
        }) ?: return false
        return result.getBoolean("allowed") && result.getString("permit_id") == permit.id &&
            result.getString(EXTRA_TASK) == permit.taskId && result.getString(EXTRA_ATTEMPT) == permit.attemptId &&
            result.getString("kind") == permit.kind && result.getLong("deadline_elapsed", 0) > SystemClock.elapsedRealtime()
    }
    fun canRun(context: Context): Boolean {
        if (!blocked(context)) return true
        val permit = current.get() ?: return false
        return valid(context, permit)
    }
    /** Observe the exact granted main-process identity; never signal or trust a reused PID. */
    fun runtimeAlive(context: Context, pid: Int, start: String): Boolean = try {
        if (pid <= 0 || !start.matches(Regex("[0-9]+"))) false else {
            val entry = File("/proc/$pid")
            val first = File(entry, "stat").readText().let(RepairEngineQuiescence.ProcessPolicy::startTime)
            val name = File(entry, "cmdline").inputStream().use { input ->
                val bytes = ByteArray(8192); val count = input.read(bytes)
                check(count > 0)
                String(bytes, 0, count, Charsets.UTF_8).substringBefore('\u0000')
            }
            val uid = Os.stat(entry.path).st_uid
            val second = File(entry, "stat").readText().let(RepairEngineQuiescence.ProcessPolicy::startTime)
            first == start && second == start && uid == context.applicationInfo.uid && name == context.packageName
        }
    } catch (_: Exception) { false }
    fun activePermit(): Permit? = current.get()
    fun clear(permit: Permit) { current.compareAndSet(permit, null) }
    fun report(context: Context, permit: Permit, event: String, error: String = ""): Boolean {
        if (event !in setOf("engine_ready", "engine_failed", "ui_ready", "frame_ready", "validation_failed", "stopped")) return false
        return call(context, "repair_runtime_report", Bundle().apply {
            putString(EXTRA_TASK, permit.taskId); putString(EXTRA_ATTEMPT, permit.attemptId)
            putString("permit_id", permit.id); putString("event", event)
            putString("boot_id", runCatching { com.dsh.android.log.BootState.bootId }.getOrDefault(""))
            putString("error_code", error.takeIf { it.matches(Regex("[a-z_]{0,64}")) } ?: "validation_failed")
        })?.getBoolean("accepted", false) == true
    }
    fun launchEngine(context: Context, taskId: String, attemptId: String): Boolean = try {
        require(validId(taskId) && validId(attemptId))
        context.startForegroundService(Intent(context, RepairValidationService::class.java).setAction(ACTION_ENGINE)
            .putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_ATTEMPT, attemptId)) != null
    } catch (_: Exception) { false }
    fun uiIntent(context: Context, taskId: String, attemptId: String): Intent {
        require(validId(taskId) && validId(attemptId))
        return Intent().setClassName(context.packageName, "com.dsh.android.ui.MainActivity").setAction(ACTION_UI)
            .putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_ATTEMPT, attemptId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    fun stopValidation(context: Context): Boolean = try {
        // Stopping the Service invokes cooperative cleanup, never a sticky restart.
        context.stopService(Intent(context, RepairValidationService::class.java))
        true
    } catch (_: Exception) { false }
    fun stopTarget(context: Context): Boolean {
        val stopped = stopValidation(context)
        runCatching { com.dsh.android.engine.EngineService.stop(context) }
        runCatching { context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName), RepairProtocol.permission(context.packageName)) }
        return stopped
    }
    fun readAttempt(context: Context, attemptId: String): JSONObject? = try {
        require(validId(attemptId))
        val directory = File(context.filesDir, "repair-control/validation")
        check(directory.canonicalFile == File(context.filesDir.canonicalFile, "repair-control/validation"))
        val file = File(directory, "$attemptId.json")
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            val stat = Os.fstat(fd)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L && stat.st_size in 1..MAX_STATE_BYTES.toLong())
            val bytes = ByteArray(stat.st_size.toInt()); var at = 0
            while (at < bytes.size) { val n = Os.read(fd, bytes, at, bytes.size - at); check(n > 0); at += n }
            JSONObject(String(bytes, Charsets.UTF_8)).also { check(it.getString("attempt_id") == attemptId) }
        } finally { Os.close(fd) }
    } catch (_: Exception) { null }
    internal fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
}
