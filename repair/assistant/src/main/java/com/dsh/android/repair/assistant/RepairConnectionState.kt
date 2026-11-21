package com.dsh.android.repair.assistant

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Public-to-the-local-UI projection only. Never persist grants, approvals or raw exceptions here. */
internal class RepairConnectionState(private val root: File) {
    private val file = File(root, "connection-state.json")

    fun publish(phase: String, target: String?, message: String, binding: String? = null) = synchronized(lock) {
        require(phase in PHASES)
        require(target == null || target in com.dsh.android.repair.contract.RepairProtocol.TARGET_PACKAGES)
        require(message.length <= 512)
        require(binding == null || binding.matches(Regex("[A-Za-z0-9-]{1,80}")))
        check(root.mkdirs() || root.isDirectory)
        val previous = read()
        val value = JSONObject().put("phase", phase).put("target", target ?: JSONObject.NULL)
            .put("label", when (target) { "com.dsh.android" -> "System"; "com.dsh.webview" -> "Gecko"; else -> "" })
            .put("message", message).put("revision", (previous?.optLong("revision", 0) ?: 0) + 1)
        if (binding != null) value.put("binding", binding)
        val temporary = File(root, "connection-state-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(value.toString(), Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    /** Retain durable feedback across recreation; process death cannot retain a live grant. */
    fun initializeDisconnected() = synchronized(lock) {
        val previous = read()
        if (previous == null || previous.optString("phase") !in setOf("disconnected", "error", "expired")) {
            publish("disconnected", null, "尚未连接目标。普通 DSH 对话可正常使用。")
        }
    }

    /** A late old dispatcher/service must not overwrite the newer connection's status. */
    fun ended(binding: String, target: String, phase: String, message: String) = synchronized(lock) {
        if (read()?.optString("binding") == binding) publish(phase, target, message)
    }

    /** Bounded read-only task evidence, never a grant/permit. Ignore stale binding/revisions. */
    fun project(binding: String, result: JSONObject) = synchronized(lock) {
        val current = read() ?: return@synchronized
        if (current.optString("binding") != binding || current.optString("phase") != "connected") return@synchronized
        val source = result.optJSONObject("task") ?: return@synchronized
        val previousTask = current.optJSONObject("task")
        if (previousTask != null && previousTask.optString("task_id") == source.optString("task_id") &&
            previousTask.optLong("revision") > source.optLong("revision")) return@synchronized
        val task = JSONObject()
        for (key in listOf("task_id", "phase", "revision", "change_revision", "evidence_seq")) {
            if (source.has(key)) task.put(key, source.get(key))
        }
        source.optString("message").takeIf { it.isNotEmpty() }?.let { task.put("message", it.take(1024)) }
        for (key in listOf("last_operation", "last_attempt")) {
            val row = source.optJSONObject(key) ?: continue
            val safe = JSONObject()
            for (name in listOf("attempt_id", "operation_id", "kind", "level", "status", "phase", "change_revision", "verified", "summary", "message")) {
                when (val field = row.opt(name)) {
                    is String -> safe.put(name, field.take(1024))
                    is Boolean, is Number -> safe.put(name, field)
                }
            }
            task.put(key, safe)
        }
        if (task.toString().toByteArray(Charsets.UTF_8).size > 8192) return@synchronized
        // Diagnostics may describe an older ordinary boot. Automatic notices include only
        // evidence explicitly linked to the current attempt; the evidence tool remains available.
        val attemptId = source.optJSONObject("last_attempt")?.optString("attempt_id")
        val evidence = result.optJSONObject("evidence")?.takeIf {
            (attemptId.isNullOrBlank() && source.optLong("change_revision") == 0L) ||
                (!attemptId.isNullOrBlank() && it.optString("attempt_id") == attemptId) ||
                (!attemptId.isNullOrBlank() && it.optString("scope") == "current_validation_attempt")
        }
        val safeEvidence = JSONObject()
        for (key in listOf("summary", "message", "error_class", "error_summary", "error_code", "safe_stack", "scope")) {
            evidence?.optString(key)?.takeIf { it.isNotBlank() }?.let { safeEvidence.put(key, it.take(1024)) }
        }
        if (current.optJSONObject("task")?.toString() == task.toString() &&
            current.optJSONObject("evidence")?.toString() == safeEvidence.toString()) return@synchronized
        current.put("task", task).put("evidence", safeEvidence)
            .put("revision", current.optLong("revision") + 1)
        atomicWrite(current)
    }

    fun themeSnapshot(): String? = runCatching {
        val themeFile = File(root, "theme-snapshot.json")
        if (!themeFile.isFile || themeFile.length() !in 1..2048 || Files.isSymbolicLink(themeFile.toPath())) return@runCatching null
        val text = themeFile.readText(Charsets.UTF_8)
        val theme = JSONObject(text)
        require(theme.keys().asSequence().toSet() == setOf("dark", "colors") && theme.get("dark") is Boolean)
        val colors = theme.getJSONObject("colors")
        require(colors.keys().asSequence().toSet() == setOf("background", "surface", "text", "primary"))
        require(colors.keys().asSequence().all { colors.get(it) is String && Regex("#[0-9a-fA-F]{6}").matches(colors.getString(it)) })
        text
    }.getOrNull()

    private fun atomicWrite(value: JSONObject) {
        val temporary = File(root, "connection-state-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(value.toString(), Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private fun read(): JSONObject? = runCatching {
        if (!file.isFile || file.length() > 16384 || Files.isSymbolicLink(file.toPath())) null else JSONObject(file.readText())
    }.getOrNull()

    private companion object {
        val lock = Any()
        val PHASES = setOf("disconnected", "checking", "authorizing", "connecting", "connected", "disconnecting", "error", "expired")
    }
}
