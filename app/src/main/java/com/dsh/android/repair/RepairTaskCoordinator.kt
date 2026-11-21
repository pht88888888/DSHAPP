package com.dsh.android.repair

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.dsh.android.engine.EngineRepairLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Target :repair owns maintenance state. Durable fencing is never a durable execution grant. */
internal object RepairTaskCoordinator {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "repair-task").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "repair-lease").apply { isDaemon = true } }
    private var state: JSONObject? = null
    private var taskGrant: String? = null // deliberately never persisted
    private var ending = false
    private var permit: Permit? = null
    private var monitoring = false
    private var activeFileOperations = 0
    private data class Permit(val id: String, val task: String, val attempt: String, val kind: String,
        val pid: Int, val start: String, val deadline: Long)
    private const val TOTAL_VALIDATION_MS = 300_000L
    private val validationStages = mapOf(
        "starting" to "已收到后台验证请求，正在确认许可",
        "preparing_runtime" to "正在准备目标运行环境",
        "extracting_engine" to "正在检查目标引擎文件",
        "starting_engine" to "正在启动本轮目标引擎",
        "checking_http" to "正在检查本轮引擎本地服务",
        "stabilizing" to "引擎已响应，正在短暂观察稳定性",
        "stopping_engine" to "正在停止本轮验证引擎并确认退出",
    )
    private const val SCHEMA = 1
    private val fileMethods = setOf("list_files", "read_file", "stage_edit", "stage_patch", "stage_file", "list_changes")

    fun snapshot(context: Context): JSONObject = synchronized(lock) {
        load(context)
        JSONObject(state?.toString() ?: "{\"phase\":\"complete\",\"revision\":0,\"change_revision\":0,\"evidence_seq\":0}")
    }

    fun begin(context: Context, grant: String): JSONObject {
        val app = context.applicationContext
        synchronized(lock) {
            load(app)
            if (RepairAuthorization(app).currentGrant() != grant) return failure("AUTHORIZATION_REQUIRED")
            if (state?.optString("phase") != null && state?.optString("phase") != "complete") {
                if (taskGrant == grant && !ending) return okTask(app, "already_active")
                return failure("RECOVERY_REQUIRED").put("task", snapshot(app))
            }
            taskGrant = grant
            ending = false
            permit = null
            state = JSONObject().put("schema", SCHEMA).put("task_id", id()).put("phase", "preparing")
                .put("revision", 0).put("change_revision", 0).put("evidence_seq", 1)
                .put("message", "正在暂停原 DSH 并检查文件是否静止")
                .put("last_operation", JSONObject.NULL).put("last_attempt", JSONObject.NULL)
            persist(app)
            if (!RepairTaskService.start(app)) {
                change(app, "recovery_required", "维修通知无法启动；尚未进入可编辑状态")
                return failure("TASK_SERVICE_UNAVAILABLE").put("task", snapshot(app))
            }
            ensureWatchdog(app)
        }
        worker.execute {
            val stopped = stopAndCheck(app)
            synchronized(lock) {
                if (!ending && taskGrant == grant) {
                    if (stopped && authorized(app, grant)) change(app, "editable", "原 DSH 已进入维修状态，可连续修改")
                    else change(app, "recovery_required", "无法确认原应用完全静止；未允许提交，请查看阻塞原因")
                }
            }
        }
        return okTask(app, "preparing")
    }

    /** Native exit is available even after grant loss; it cannot read or modify user files. */
    fun end(context: Context): JSONObject {
        val app = context.applicationContext
        synchronized(lock) {
            load(app)
            if (state == null || state?.optString("phase") == "complete") {
                taskGrant?.let { RepairAuthorization(app).revoke(it) }; taskGrant = null
                return okTask(app, "complete")
            }
            if (ending) return okTask(app, "ending")
            ending = true
            permit = null
            change(app, "ending", "正在停止验证并核实文件事务，完成后恢复正常启动")
        }
        RepairRuntimeGate.stopValidation(app)
        worker.execute {
            val stopped = stopAndCheck(app)
            var complete = false
            // No state/grant lock while recovering large retained file journals.
            val journals = if (stopped) runCatching { RepairFileAccess.broker(app).reconcileForShutdown() }.getOrNull() else null
            val unresolved = journals == null || !journals.optBoolean("ok") || journals.optInt("unresolved", 1) != 0
            synchronized(lock) {
                val grant = taskGrant
                if (stopped && !unresolved) {
                    grant?.let { RepairAuthorization(app).revoke(it) }
                    taskGrant = null
                    change(app, "complete", "维修已结束，正常启动已恢复；已应用的文件修改保留")
                    complete = true
                } else {
                    change(app, "recovery_required", if (unresolved) "存在未决文件事务，保留维修闸门，未覆盖任何冲突内容" else "目标仍有活动进程或锁；保留维修闸门，请重试结束维修")
                }
                ending = false
            }
            if (complete) RepairTaskService.stop(app)
        }
        return okTask(app, "ending")
    }

    fun execute(context: Context, grant: String, method: String, args: JSONObject, approval: String?): JSONObject {
        val app = context.applicationContext
        if (method in setOf("status", "evidence", "verify_target")) {
            require(args.length() == 0)
            if (RepairAuthorization(app).currentGrant() != grant) return failure("AUTHORIZATION_REQUIRED")
            val task = snapshot(app)
            val attemptEvidence = task.optJSONObject("last_attempt")?.optJSONObject("evidence")
            val evidence = (attemptEvidence?.let { JSONObject(it.toString()) } ?: RepairDiagnostics.evidence(app.filesDir))
                .put("scope", if (attemptEvidence == null) "latest_recorded_boot_not_attempt_proof" else "current_validation_attempt")
            task.optJSONObject("last_attempt")?.let { attempt ->
                evidence.put("attempt_id", attempt.optString("attempt_id"))
                evidence.put("attempt_status", attempt.optString("status"))
                evidence.put("attempt_summary", attempt.optString("summary"))
                evidence.put("attempt_boot_matches", !attempt.isNull("boot_id") && attempt.optString("boot_id") == evidence.optString("boot_id"))
            }
            return JSONObject().put("ok", true).put("authorized", true).put("target", app.packageName)
                .put("file_broker_revision", "app_files_050").put("task", task).put("evidence", evidence)
                .put("attempt", task.opt("last_attempt") ?: JSONObject.NULL)
                .put("verified", task.optJSONObject("last_attempt")?.let { it.optString("level") == "ui" && it.optBoolean("verified") && it.optLong("change_revision") == task.optLong("change_revision") } ?: false)
                .apply { if (method == "evidence") evidence.keys().forEach { key -> put(key, evidence.get(key)) } }
        }
        // Potentially unbounded hashing/copying never holds the state or grant lock.
        // Revocation can invalidate authority immediately while an old read/stage completes;
        // its result is discarded and it never authorizes publication.
        synchronized(lock) {
            load(app)
            if (!authorized(app, grant)) return failure("TASK_AUTHORIZATION_REQUIRED").put("task", snapshot(app))
            if (method in fileMethods) {
                if (state?.optString("phase") !in setOf("editable", "awaiting_ui", "complete")) return failure("TASK_BUSY")
                if (method.startsWith("stage_") && state?.optString("phase") == "complete") return failure("TASK_COMPLETE")
                activeFileOperations++
            }
        }
        if (method in fileMethods) {
            try {
                val result = RepairFileAccess.broker(app).dispatch(method, args, grant)
                return if (authorized(app, grant)) result.put("task", snapshot(app)) else failure("AUTHORIZATION_REVOKED")
            } finally { synchronized(lock) { activeFileOperations-- } }
        }
        val preview = if (method == "apply_changes" || method == "rollback") {
            require(args.keys().asSequence().toSet() == setOf("change_id"))
            RepairFileAccess.broker(app).previewChange(args.getString("change_id"), grant)
        } else null
        val digestRevision = synchronized(lock) { state?.optLong("change_revision") ?: -1L }
        val digest = if (method in setOf("validate_target", "prepare_ui")) changeDigest(app, grant) else null
        synchronized(lock) {
            if (digest != null && state?.optLong("change_revision") != digestRevision) return failure("CHANGE_SET_CHANGED_RETRY")
            if (!authorized(app, grant)) return failure("TASK_AUTHORIZATION_REQUIRED").put("task", snapshot(app))
            if (method == "apply_changes" || method == "rollback") {
                require(args.keys().asSequence().toSet() == setOf("change_id"))
                if (state?.optString("phase") !in setOf("editable", "awaiting_ui") || activeFileOperations != 0) return failure("TASK_BUSY")
                val changeId = args.getString("change_id")
                val checkedPreview = checkNotNull(preview)
                if (!checkedPreview.optBoolean("ok")) return checkedPreview
                // The one-time task grant covers ordinary and sensitive publication; the user can
                // revoke at any time and every applied transaction keeps its original backup.
                val operation = operation(app, method)
                change(app, "committing", "正在校验摘要并提交单文件事务")
                worker.execute { commit(app, grant, method, args, operation) }
                return okTask(app, "queued").put("operation_id", operation)
            }
            if (method == "validate_target" || method == "prepare_ui") {
                require(if (method == "prepare_ui") args.length() == 0 else args.keys().asSequence().toSet() == setOf("level"))
                val level = if (method == "prepare_ui") "ui" else args.getString("level")
                require(if (method == "prepare_ui") level == "ui" else level in setOf("syntax", "engine"))
                if (state?.optString("phase") !in setOf("editable", "awaiting_ui") || activeFileOperations != 0) return failure("TASK_BUSY")
                if (level == "ui" && state?.optJSONObject("last_attempt")?.let { it.optString("level") == "engine" && it.optString("status") == "passed" && it.optLong("change_revision") == state?.optLong("change_revision") } != true) return failure("ENGINE_VALIDATION_REQUIRED")
                val operation = operation(app, method)
                val attempt = JSONObject().put("attempt_id", id()).put("operation_id", operation).put("level", level)
                    .put("status", "queued").put("change_revision", state!!.optLong("change_revision"))
                    .put("change_set_sha256", digest).put("verified", false)
                    .put("engine_ready", false).put("ui_ready", false).put("frame_ready", false)
                    .put("deadline_elapsed", SystemClock.elapsedRealtime() + TOTAL_VALIDATION_MS)
                state!!.put("last_attempt", attempt)
                permit = null
                change(app, if (level == "ui") "ui_validating" else "validating", if (level == "ui") "等待本轮正式界面验证" else "正在后台检查；不切换界面")
                if (level != "ui") worker.execute { validate(app, grant, level, attempt.getString("attempt_id")) }
                else worker.execute { watchUi(app, grant, attempt.getString("attempt_id")) }
                return okTask(app, "queued").put("operation_id", operation).put("task_id", state!!.getString("task_id")).put("attempt_id", attempt.getString("attempt_id"))
            }
            return failure("UNSUPPORTED_METHOD")
        }
    }

    /** Only Provider's verified exact main PID may claim or revalidate a memory-only permit. */
    fun runtimePermit(context: Context, pid: Int, start: String, input: Bundle): Bundle = synchronized(lock) {
        load(context)
        val denied = Bundle().apply { putBoolean("allowed", false) }
        val task = state ?: return@synchronized denied
        val attempt = task.optJSONObject("last_attempt") ?: return@synchronized denied
        val kind = input.getString("kind") ?: return@synchronized denied
        if (kind !in setOf("engine", "ui") || kind != attempt.optString("level") ||
            input.getString("task_id") != task.optString("task_id") || input.getString("attempt_id") != attempt.optString("attempt_id") ||
            task.optString("phase") !in setOf("validating", "ui_validating") || ending || taskGrant?.let { authorized(context, it) } != true ||
            attempt.optString("status") !in setOf("queued", "running") || SystemClock.elapsedRealtime() >= attempt.optLong("deadline_elapsed")) return@synchronized denied
        val previous = permit
        val requested = input.getString("permit_id")
        if (previous != null && (previous.pid != pid || previous.start != start || previous.attempt != attempt.optString("attempt_id") || requested != null && requested != previous.id)) return@synchronized denied
        if (previous == null && requested != null) return@synchronized denied
        val next = previous ?: Permit(id(), task.getString("task_id"), attempt.getString("attempt_id"), kind, pid, start, attempt.getLong("deadline_elapsed")).also { permit = it }
        Bundle().apply {
            putBoolean("allowed", true); putString("permit_id", next.id); putString("task_id", next.task)
            putString("attempt_id", next.attempt); putString("kind", next.kind); putLong("deadline_elapsed", next.deadline)
        }
    }

    fun runtimeReport(context: Context, pid: Int, start: String, input: Bundle): Bundle = synchronized(lock) {
        val current = permit
        val accepted = current != null && current.pid == pid && current.start == start && input.getString("permit_id") == current.id &&
            input.getString("task_id") == current.task && input.getString("attempt_id") == current.attempt && !ending &&
            taskGrant?.let { authorized(context, it) } == true && SystemClock.elapsedRealtime() < current.deadline
        if (!accepted) return@synchronized Bundle().apply { putBoolean("accepted", false) }
        val task = state!!
        val attempt = task.getJSONObject("last_attempt")
        val event = input.getString("event")
        if (attempt.optString("status") in setOf("failed", "cancelled", "passed") && event != "stopped") return@synchronized Bundle().apply { putBoolean("accepted", false) }
        when (event) {
            "engine_ready" -> attempt.put("engine_ready", true).put("status", "running")
            "ui_ready" -> attempt.put("ui_ready", true).put("status", "running")
            "frame_ready" -> attempt.put("frame_ready", true).put("status", "running")
            "engine_failed", "validation_failed" -> attempt.put("status", "failed").put("error", safeCode(input.getString("error_code")))
            "stopped" -> attempt.put("stopped", true)
            else -> return@synchronized Bundle().apply { putBoolean("accepted", false) }
        }
        input.getString("boot_id")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,80}")) }?.let { attempt.put("boot_id", it) }
        if (attempt.optString("level") == "ui" && attempt.optBoolean("engine_ready") && attempt.optBoolean("ui_ready") && attempt.optBoolean("frame_ready") && attempt.optString("status") != "failed") {
            attempt.put("status", "passed").put("verified", true).put("summary", "本轮正式引擎及界面就绪，首帧已显示；未代表全部业务功能均测试")
            task.put("evidence_seq", task.optLong("evidence_seq") + 1)
        }
        persist(context)
        Bundle().apply { putBoolean("accepted", true) }
    }

    private fun commit(context: Context, grant: String, method: String, args: JSONObject, operationId: String) {
        var result: JSONObject = failure("TARGET_NOT_QUIESCENT")
        try {
            if (!ending && stopAndCheck(context)) {
                val exclusion = EngineRepairLock.tryAcquire(context.filesDir)
                if (exclusion != null) exclusion.use {
                    if (!ending && applicationQuiet(context)) result = RepairAuthorization(context).withGrant(grant) {
                        RepairFileAccess.broker(context).dispatch(method, args, grant)
                    }
                }
            }
        } catch (_: Exception) { result = failure("COMMIT_FAILED_QUERY_CHANGES") }
        synchronized(lock) {
            if (state?.optJSONObject("last_operation")?.optString("operation_id") != operationId) return
            state!!.getJSONObject("last_operation").put("status", if (result.optBoolean("ok")) "passed" else "failed").put("result", result)
            if (result.optBoolean("ok")) {
                state!!.put("change_revision", state!!.optLong("change_revision") + 1)
                state!!.optJSONObject("last_attempt")?.put("verified", false)?.put("stale", true)
            }
            state!!.put("evidence_seq", state!!.optLong("evidence_seq") + 1)
            if (!ending) change(context, if (applicationQuiet(context)) "editable" else "recovery_required", if (result.optBoolean("ok")) "文件修改已应用，尚未验证" else "修改未确认成功，请查看事务结果，不要盲目重试") else persist(context)
        }
    }

    private fun validate(context: Context, grant: String, level: String, attemptId: String) {
        var outcome = "failed"
        var summary = "后台验证未完成"
        var error = "VALIDATION_FAILED"
        try {
            if (!stopAndCheck(context) || !authorized(context, grant)) error = "TARGET_NOT_QUIESCENT"
            else {
                synchronized(lock) { matching(attemptId)?.put("status", "running"); persist(context) }
                if (level == "syntax") {
                    val exclusion = EngineRepairLock.tryAcquire(context.filesDir)
                    val result = if (exclusion == null) failure("TARGET_ENGINE_RUNNING") else exclusion.use { syntax(context, grant, synchronized(lock) { matching(attemptId)?.optLong("deadline_elapsed") ?: 0 }) }
                    outcome = if (result.optBoolean("ok")) "passed" else "failed"
                    summary = result.optString("summary")
                    error = result.optString("error")
                    synchronized(lock) { matching(attemptId)?.put("checks", result.optJSONArray("checks") ?: JSONArray()) }
                } else if (!RepairRuntimeGate.launchEngine(context, snapshot(context).getString("task_id"), attemptId)) error = "BACKGROUND_START_REJECTED"
                else {
                    val deadline = synchronized(lock) { matching(attemptId)?.optLong("deadline_elapsed") ?: 0 }
                    val taskId = snapshot(context).getString("task_id")
                    var handshaken = false
                    while (SystemClock.elapsedRealtime() < deadline && !ending && authorized(context, grant)) {
                        val report = RepairRuntimeGate.readAttempt(context, attemptId)?.takeIf {
                            it.optString("task_id") == taskId && it.optString("attempt_id") == attemptId
                        }
                        val runtime = synchronized(lock) { permit?.takeIf { it.task == taskId && it.attempt == attemptId } }
                        handshaken = handshaken || runtime != null
                        val attempt = synchronized(lock) { matching(attemptId)?.let { JSONObject(it.toString()) } }
                        if (report != null) synchronized(lock) {
                            matching(attemptId)?.apply {
                                report.optJSONObject("evidence")?.let { put("evidence", it) }
                                if (report.has("boot_id")) put("boot_id", report.optString("boot_id"))
                                val stage = report.optString("stage")
                                if (!ending && stage in validationStages && optString("stage") != stage) {
                                    put("stage", stage)
                                    change(context, "validating", checkNotNull(validationStages[stage]))
                                }
                            }
                        }
                        val status = report?.optString("status").orEmpty()
                        if (attempt?.optString("status") == "failed" || status == "failed") {
                            error = safeCode(report?.optString("error_code")?.takeIf { it.isNotEmpty() } ?: attempt?.optString("error"))
                            summary = "本轮后台验证失败，已返回当前尝试的诊断；不再等待总超时"
                            break
                        }
                        if (runtime != null && !RepairRuntimeGate.runtimeAlive(context, runtime.pid, runtime.start)) {
                            error = "VALIDATION_RUNTIME_EXITED"
                            summary = "本轮后台验证主进程已退出，未得到完整检查结果"
                            break
                        }
                        if (handshaken && report?.optBoolean("stopped") == true && (status == "passed" || report.optBoolean("passed"))) {
                            outcome = "passed"; summary = "本轮后台引擎启动及健康检查通过；正式界面尚未验证"; error = ""; break
                        }
                        if (attempt?.optBoolean("stopped") == true && attempt.optBoolean("engine_ready")) {
                            outcome = "passed"; summary = "本轮后台引擎启动及健康检查通过；正式界面尚未验证"; error = ""; break
                        }
                        Thread.sleep(150)
                    }
                    if (outcome != "passed" && error == "VALIDATION_FAILED") {
                        error = "VALIDATION_TIMEOUT_OR_CANCELLED"
                        summary = "本轮验证在总等待上限内未完成，不能据此认定引擎已启动或无故障"
                    }
                }
            }
        } catch (_: Exception) { error = "VALIDATION_EXCEPTION" }
        // Permission revocation is immediate, but cleanup is still allowed and must be verified.
        RepairRuntimeGate.stopValidation(context)
        val stopped = stopAndCheck(context)
        synchronized(lock) {
            val attempt = matching(attemptId) ?: return
            permit = null
            if (!stopped) { outcome = "failed"; error = "VALIDATION_NOT_STOPPED"; summary = "验证进程尚未确认退出，不能继续编辑" }
            if (ending || !authorized(context, grant)) { outcome = "cancelled"; summary = "验证已取消，尚未确认修复成功" }
            attempt.put("status", outcome).put("verified", false).put("summary", summary).put("stopped", stopped)
            if (error.isNotEmpty()) attempt.put("error", error)
            state!!.put("evidence_seq", state!!.optLong("evidence_seq") + 1)
            finishOperation(outcome)
            if (!ending) change(context, if (!stopped) "recovery_required" else if (level == "engine" && outcome == "passed") "awaiting_ui" else "editable", summary) else persist(context)
        }
    }

    private fun watchUi(context: Context, grant: String, attemptId: String) {
        val deadline = synchronized(lock) { matching(attemptId)?.optLong("deadline_elapsed") ?: 0 }
        while (!ending && authorized(context, grant) && SystemClock.elapsedRealtime() < deadline) {
            val status = synchronized(lock) { matching(attemptId)?.optString("status") }
            if (status == "passed" || status == "failed") break
            Thread.sleep(150)
        }
        val passed = synchronized(lock) {
            val accepted = matching(attemptId)?.optBoolean("verified") == true && !ending && authorized(context, grant)
            if (accepted) {
                permit = null
                matching(attemptId)?.put("status", "passed")
                finishOperation("passed")
                // Release the fence only after successful real UI. Keep foreground app running.
                change(context, "complete", "本轮启动及正式界面验证通过，维修已结束")
                // Keep the existing read grant briefly available so the assistant can collect
                // this final result. Phase complete prevents further writes/permits.
            }
            accepted
        }
        if (passed) {
            RepairTaskService.stop(context)
        } else {
            RepairRuntimeGate.stopValidation(context)
            val stopped = stopAndCheck(context)
            synchronized(lock) {
                permit = null
                matching(attemptId)?.put("status", if (ending) "cancelled" else "failed")?.put("verified", false)
                    ?.put("summary", "正式界面未通过本轮验证，结果已反馈助手")?.put("stopped", stopped)
                state?.put("evidence_seq", state!!.optLong("evidence_seq") + 1)
                finishOperation("failed")
                if (!ending) change(context, if (stopped) "editable" else "recovery_required", "界面验证失败；保留维修现场") else persist(context)
            }
        }
    }

    private fun syntax(context: Context, grant: String, deadline: Long): JSONObject {
        val rows = RepairFileAccess.broker(context).dispatch("list_changes", JSONObject(), grant).optJSONArray("changes") ?: JSONArray()
        val checks = JSONArray()
        var supported = 0
        var failed = false
        for (index in 0 until rows.length()) {
            if (ending || !authorized(context, grant) || SystemClock.elapsedRealtime() >= deadline) return failure("VALIDATION_CANCELLED_OR_TIMEOUT")
            val row = rows.getJSONObject(index)
            if (row.optString("status") != "applied_pending_verification") continue
            val path = row.getString("path")
            val real = resolved(context, path) ?: continue
            if (row.optString("operation") in setOf("delete", "rmdir", "mkdir")) { checks.put(JSONObject().put("path", path).put("status", "not_applicable")); continue }
            val kind = when { path.endsWith(".json") -> "json"; path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs") -> "js"; else -> "unsupported" }
            if (kind == "unsupported") { checks.put(JSONObject().put("path", path).put("status", "unsupported")); continue }
            supported++
            val good = if (kind == "json") runCatching {
                require(real.length() <= 2 * 1024 * 1024)
                val tokener = org.json.JSONTokener(real.readText())
                val value = tokener.nextValue()
                require((value is JSONObject || value is JSONArray) && tokener.nextClean() == '\u0000')
            }.isSuccess else runCatching {
                // No shell, eval, import, require or execution of the target module.
                val node = File(context.filesDir, "termux/usr/bin/node")
                val builder = ProcessBuilder(node.path, "--check", real.path).redirectErrorStream(true)
                builder.environment().apply {
                    val prefix = File(context.filesDir, "termux/usr")
                    put("PREFIX", prefix.path); put("HOME", File(context.filesDir, "home").path)
                    put("DSH_HOME", File(context.filesDir, "home/.dsh").path)
                    put("TMPDIR", context.cacheDir.path)
                    put("PATH", File(prefix, "bin").path + ":" + File(prefix, "bin/applets").path + ":/system/bin:/system/xbin")
                    remove("LD_LIBRARY_PATH"); remove("NODE_COMPILE_CACHE")
                    // Never inherit injectable loader flags when inspecting a candidate.
                    remove("NODE_OPTIONS"); remove("NODE_PATH"); remove("LD_PRELOAD")
                }
                val p = builder.start()
                // Drain bounded output concurrently, discard possible source/secret text.
                val drainer = Thread { p.inputStream.use { input -> val b = ByteArray(4096); while (input.read(b) != -1) { } } }.apply { isDaemon = true; start() }
                val remaining = minOf(15_000L, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                val exited = p.waitFor(remaining, TimeUnit.MILLISECONDS)
                if (!exited) { p.destroy(); p.waitFor(3, TimeUnit.SECONDS) }
                drainer.join(250)
                return@runCatching exited && p.exitValue() == 0
            }.getOrDefault(false)
            checks.put(JSONObject().put("path", path).put("status", if (good) "passed" else "failed"))
            failed = failed || !good
        }
        return JSONObject().put("ok", supported > 0 && !failed).put("checks", checks)
            .put("error", if (failed) "SYNTAX_CHECK_FAILED" else if (supported == 0) "NO_SUPPORTED_SYNTAX_CHECKS" else "")
            .put("summary", if (supported == 0) "没有可执行的受支持静态检查；不等于检查通过" else if (failed) "静态检查发现错误，未启动正式界面" else "支持的静态检查通过；未支持类型未检查，尚未验证引擎或界面")
    }

    private fun resolved(context: Context, path: String): File? {
        val parts = if (path.startsWith("engine/")) ("app/files/" + path).split('/') else path.split('/')
        val root = RepairFileAccess.roots(context)[parts.first()] ?: return null
        if (parts.drop(1).any { it.isEmpty() || it == "." || it == ".." }) return null
        val file = parts.drop(1).fold(root) { parent, child -> File(parent, child) }
        return runCatching { file.takeIf { it.canonicalFile.path == it.absolutePath && it.isFile } }.getOrNull()
    }

    private fun stopAndCheck(context: Context): Boolean = runCatching {
        RepairRuntimeGate.stopTarget(context)
        if (!RepairEngineQuiescence.stopKnownTarget(context)) return false
        val exclusion = EngineRepairLock.tryAcquire(context.filesDir) ?: return false
        exclusion.use { applicationQuiet(context) }
    }.getOrDefault(false)

    private fun applicationQuiet(context: Context): Boolean {
        if (!RepairEngineQuiescence.canModify(context)) return false
        // Extra app processes can hold SQLite/preferences/browser writers. Unknown ones block;
        // never broaden the process signalling policy into a UID-wide kill.
        val processes = File("/proc").listFiles() ?: return false
        for (entry in processes) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == Process.myPid()) continue
            try {
                if (Os.stat(entry.path).st_uid != Process.myUid()) continue
                val name = File(entry, "cmdline").inputStream().use { input ->
                    val bytes = ByteArray(8192); val n = input.read(bytes); if (n <= 0) return false
                    String(bytes, 0, n, Charsets.UTF_8).substringBefore('\u0000')
                }
                if (name.startsWith(context.packageName + ":")) return false
            } catch (error: android.system.ErrnoException) { if (error.errno != OsConstants.ENOENT) return false }
            catch (_: Exception) { if (entry.exists()) return false }
        }
        return true
    }

    /** Preview material bound into a native approval ticket; retained for the optional confirmation UI. */
    fun approvalBinding(preview: JSONObject, task: JSONObject): String {
        val material = listOf("task_id", "change_revision").map { task.optString(it) } +
            listOf("change_id", "path", "operation", "original_sha256", "candidate_sha256", "original_mode", "candidate_mode").map { preview.optString(it) }
        return MessageDigest.getInstance("SHA-256").digest(material.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun changeDigest(context: Context, grant: String): String {
        val rows = RepairFileAccess.broker(context).dispatch("list_changes", JSONObject(), grant).optJSONArray("changes") ?: JSONArray()
        val canonical = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.optString("change_id") }
            .joinToString("\n") { "${it.optString("change_id")}:${it.optString("path")}:${it.optString("candidate_sha256")}:${it.optString("status")}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun operation(context: Context, kind: String): String = id().also {
        state!!.put("last_operation", JSONObject().put("operation_id", it).put("kind", kind).put("status", "queued")); persist(context)
    }
    private fun finishOperation(status: String) { state?.optJSONObject("last_operation")?.put("status", status) }
    private fun matching(attemptId: String): JSONObject? = state?.optJSONObject("last_attempt")?.takeIf { it.optString("attempt_id") == attemptId }
    private fun authorized(context: Context, grant: String): Boolean = synchronized(lock) { !ending && taskGrant == grant && RepairAuthorization(context).currentGrant() == grant }
    private fun safeCode(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,96}")) } ?: "TARGET_VALIDATION_FAILED"
    private fun id() = UUID.randomUUID().toString()
    private fun failure(error: String) = JSONObject().put("ok", false).put("error", error)
    private fun okTask(context: Context, status: String) = JSONObject().put("ok", true).put("status", status).put("task", snapshot(context))
    private fun change(context: Context, phase: String, message: String) { state!!.put("phase", phase).put("message", message); persist(context) }
    private fun load(context: Context) {
        if (state != null) return
        val path = File(context.filesDir, "repair-control/task.json")
        if (!path.exists() && !File(path.path + ".bak").exists() && !File(path.path + ".new").exists()) return
        // AtomicFile restores interrupted prior snapshots before parsing. The startup fence
        // also treats sidecars as blocked, so a missing base is never permission to boot.
        runCatching { AtomicFile(path).openRead().close() }
        state = runCatching {
            val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            try {
                val stat = Os.fstat(fd); require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..65536)
                val bytes = ByteArray(stat.st_size.toInt()); var offset = 0
                while (offset < bytes.size) { val n = Os.read(fd, bytes, offset, bytes.size - offset); require(n > 0); offset += n }
                JSONObject(String(bytes, Charsets.UTF_8)).also { require(it.optInt("schema") == SCHEMA) }
            } finally { Os.close(fd) }
        }.getOrElse { JSONObject().put("schema", SCHEMA).put("task_id", id()).put("phase", "recovery_required") }
        if (state!!.optString("phase") != "complete") {
            state!!.put("phase", "recovery_required").put("message", "维修进程已重建；旧执行许可失效，请结束维修并核实安全恢复")
            persist(context)
        }
    }
    private fun persist(context: Context) {
        val value = state ?: return
        value.put("revision", value.optLong("revision") + 1)
        val root = File(context.filesDir, "repair-control")
        check(root.mkdirs() || root.isDirectory)
        check(root.canonicalFile == root.absoluteFile)
        val file = AtomicFile(File(root, "task.json"))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    private fun ensureWatchdog(context: Context) {
        if (monitoring) return
        monitoring = true
        watchdog.scheduleWithFixedDelay({
            val invalid = synchronized(lock) {
                state?.optString("phase") !in setOf(null, "complete", "ending", "recovery_required") &&
                    (taskGrant == null || RepairAuthorization(context).currentGrant() != taskGrant)
            }
            if (invalid) end(context)
        }, 1, 1, TimeUnit.SECONDS)
    }
}
