package com.dsh.android.repair.assistant

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.dsh.android.repair.contract.RepairProtocol as Protocol
import com.dsh.android.repair.contract.RepairJsonLimits as ProtocolLimits
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Private native dispatcher. Target, grant and approval tokens never come from model arguments. */
class RepairAgentBridge private constructor(
    activity: Activity, val target: String, val grant: String, private val directory: File,
) : Closeable {
    @Volatile private var activity: Activity? = activity
    private val status = RepairConnectionState(directory.parentFile!!)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val client = RepairClient(checkNotNull(activity))
    private val requests = File(directory, "request").apply { check(mkdirs() || isDirectory) }
    private val responses = File(directory, "response").apply { check(mkdirs() || isDirectory) }
    val binding = java.util.UUID.randomUUID().toString()
    private val bindingFile = File(directory, "binding.json")
    @Volatile private var lastNativeAction: JSONObject? = null
    private var lastGrantCheck = 0L
    private var lastTaskCheck = 0L
    private val uiPending = AtomicBoolean(false)
    private val activityPending = AtomicBoolean(false)
    private var lastActivitySent = 0L
    private val poll = object : Runnable {
        override fun run() {
            if (closed.get()) return
            submit { processRequests() }
        }
    }

    init {
        // Reject late results/request files from any prior epoch at this fixed mailbox.
        Files.deleteIfExists(File(directory, "launch-baseline.json").toPath())
        val temporary = File(directory, "binding-${java.util.UUID.randomUUID()}.tmp")
        temporary.writeText(JSONObject().put("binding", binding).toString(), Charsets.UTF_8)
        Files.move(temporary.toPath(), bindingFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        synchronized(live) { live.add(this) }
        main.post(poll)
    }

    fun markActive() {
        if (closed.get()) return
        RepairSessionService.noteInteraction(target, grant)
        if (android.os.SystemClock.elapsedRealtime() - lastActivitySent < 5_000L || !activityPending.compareAndSet(false, true)) return
        submit {
            try {
                if (!closed.get()) {
                    lastActivitySent = android.os.SystemClock.elapsedRealtime()
                    runCatching { client.heartbeat(target, grant) }
                }
            } finally { activityPending.set(false) }
        }
    }

    private fun processRequests() {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastGrantCheck >= 30_000L) {
                lastGrantCheck = now
                val state = client.query(target, Protocol.METHOD_STATUS)
                if (state !is RepairClient.Reply.Status || !state.state.authorized || state.state.grantId != grant) {
                    close("expired", "目标授权已结束，请通过连接设置重新授权。")
                    return
                }
            }
            // Evidence observation never extends either the target or foreground-service lease.
            if (now - lastTaskCheck >= 1_500L) {
                lastTaskCheck = now
                runCatching { status.project(binding, JSONObject(client.operation(target, grant, "verify_target", "{}"))) }
            }
            for (file in requests.listFiles().orEmpty().sortedBy { it.name }.take(8)) {
                if (closed.get()) return
                val id = file.name.removeSuffix(".json")
                if (!file.name.endsWith(".json") || !ID.matches(id)) continue
                val result = runCatching {
                    val request = JSONObject(readBounded(file))
                    require(request.optString("id") == id)
                    require(request.keys().asSequence().toSet() == setOf("id", "method", "args", "binding"))
                    require(request.getString("binding") == binding) { "Stale repair session" }
                    val method = request.getString("method")
                    require(method in METHODS)
                    val args = request.getJSONObject("args")
                    dispatch(method, args)
                }.fold(
                    onSuccess = { JSONObject().put("id", id).put("ok", true).put("result", it) },
                    onFailure = { JSONObject().put("id", id).put("ok", false).put("error", "NATIVE_REQUEST_REJECTED") },
                )
                val text = result.toString()
                require(text.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_JSON_BYTES)
                val temp = File(responses, "$id.tmp")
                check(!temp.exists() && !Files.isSymbolicLink(temp.toPath()))
                temp.writeText(text, Charsets.UTF_8)
                Files.move(temp.toPath(), File(responses, "$id.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
                check(file.canonicalFile == File(requests.canonicalFile, file.name))
                Files.deleteIfExists(file.toPath())
            }
        } catch (_: Exception) {
            // Never echo raw errors/paths into model output or log credentials.
        } finally {
            if (!closed.get()) main.postDelayed(poll, 100L)
        }
    }

    private fun dispatch(method: String, args: JSONObject): JSONObject {
        if (method == "activity") {
            require(args.length() == 0)
            check(client.heartbeat(target, grant)) { "Expired authorization" }
            main.post { RepairSessionService.noteInteraction(target, grant) }
            return JSONObject().put("ok", true)
        }
        if (method == "launch_target") {
            require(args.length() == 0)
            return JSONObject().put("ok", false).put("status", "user_action_required")
                .put("message", "请回到原 DSH 点「重启并验证」；模型不会自动切换前台。")
        }
        if (method == "validate_target") {
            require(args.keys().asSequence().toSet() == setOf("level"))
            require(args.getString("level") in setOf("syntax", "engine"))
        }
        val result = JSONObject(client.operation(target, grant, method, args.toString()))
        status.project(binding, result)
        if (method != "status" && method != "verify_target") {
            check(client.heartbeat(target, grant))
            main.post { RepairSessionService.noteInteraction(target, grant) }
        }
        if (method == "list_changes") lastNativeAction?.let { result.put("last_native_action", it) }
        return result
    }

    /** Only native handling of a zero-argument user UI action may enter here. */
    fun validateUi() {
        val owner = activity ?: return
        if (closed.get() || !uiPending.compareAndSet(false, true)) return
        markActive()
        submit {
            try {
                val prepared = JSONObject(client.operation(target, grant, "prepare_ui", "{}"))
                status.project(binding, prepared)
                if (!prepared.optBoolean("ok")) return@submit
                val taskId = prepared.getString("task_id")
                val attemptId = prepared.getString("attempt_id")
                require(ID.matches(taskId) && ID.matches(attemptId))
                main.post {
                    // Never launch after the initiating Activity left the foreground.
                    if (!closed.get() && activity === owner && !owner.isFinishing) {
                        val intent = Intent().setComponent(ComponentName(target, "com.dsh.android.ui.MainActivity"))
                            .setAction("com.dsh.android.repair.VALIDATE_UI")
                            .putExtra("task_id", taskId).putExtra("attempt_id", attemptId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        runCatching { owner.startActivity(intent) }
                    }
                }
            } finally { uiPending.set(false) }
        }
    }

    /** The per-change native approval dialog is gone; the one-time grant covers publication. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = false

    private fun readBounded(file: File): String {
        require(file.canonicalFile == File(requests.canonicalFile, file.name))
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        return try {
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..Protocol.MAX_JSON_BYTES.toLong())
            val bytes = ByteArray(stat.st_size.toInt())
            var total = 0
            while (total < bytes.size) {
                val n = Os.read(fd, bytes, total, bytes.size - total)
                require(n > 0)
                total += n
            }
            String(bytes, Charsets.UTF_8).also { require(ProtocolLimits.accepts(it)) }
        } finally { Os.close(fd) }
    }

    private fun submit(action: () -> Unit) {
        if (closed.get()) return
        try { worker.execute(action) } catch (_: RejectedExecutionException) {
            // Approval result, UI activity and notification-stop may race executor shutdown.
        }
    }

    fun isOpen(): Boolean = !closed.get()

    fun detach(owner: Activity) { if (activity === owner) activity = null }
    fun rebind(owner: Activity) { check(!closed.get()); activity = owner }

    override fun close() = close("disconnected", "修复连接已结束。DSH 对话不受影响。")

    fun close(phase: String, message: String) {
        if (!closed.compareAndSet(false, true)) return
        activity = null
        runCatching { status.ended(binding, target, phase, message) }
        synchronized(live) { live.remove(this) }
        main.removeCallbacks(poll)
        worker.shutdownNow()
        runCatching {
            if (bindingFile.isFile && JSONObject(bindingFile.readText()).optString("binding") == binding) {
                Files.deleteIfExists(bindingFile.toPath())
            }
        }
    }

    companion object {
        private val live = mutableSetOf<RepairAgentBridge>()
        fun current(): RepairAgentBridge? = synchronized(live) { live.singleOrNull { it.isOpen() } }
        fun stop(target: String, grant: String, phase: String = "disconnected", message: String = "修复连接已结束。DSH 对话不受影响。") {
            val stopping = synchronized(live) { live.filter { it.target == target && it.grant == grant } }
            stopping.forEach { bridge ->
                bridge.close(phase, message)
            }
        }
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val METHODS = setOf("activity", "status", "evidence", "list_files", "read_file", "stage_edit", "stage_patch", "stage_file", "list_changes",
            "apply_changes", "rollback", "launch_target", "validate_target", "verify_target")

        fun attach(activity: Activity, target: String, grant: String, path: String): RepairAgentBridge {
            require(target in Protocol.TARGET_PACKAGES && grant.isNotBlank())
            check(current() == null) { "A repair binding is already active" }
            val root = File(activity.filesDir, "repair-mailboxes").apply { check(mkdirs() || isDirectory) }.canonicalFile
            val directory = File(path)
            require(directory.absoluteFile == directory.canonicalFile && directory.parentFile?.canonicalFile == root)
            require(directory.name.matches(Regex("[A-Za-z0-9-]{1,80}")))
            check(directory.mkdirs() || directory.isDirectory)
            return RepairAgentBridge(activity, target, grant, directory)
        }
    }
}
