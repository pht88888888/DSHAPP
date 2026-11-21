package com.dsh.android.repair

import com.dsh.android.engine.EngineFailureProjection
import org.json.JSONArray
import org.json.JSONObject

/** Automatic evidence is an allowlisted semantic projection, NOT a reversible log export.
 * Credentials, arbitrary URI/query text, source lines and unknown prose are never copied. */
internal object RepairEvidenceRedaction {
    private val failures = mapOf(
        "termux.failed" to "runtime_bootstrap_failed",
        "renderer.gone" to "web_renderer_gone",
        "web.error" to "web_error", "web.load.failed" to "web_load_failed",
        "web.console.error" to "web_script_error", "web.native.failed" to "web_native_failed",
        "native.failed" to "native_error", "native.crash" to "native_crash", "app.crash" to "native_crash",
        "plugin.failed" to "plugin_start_failed", "plugin.load.failed" to "plugin_start_failed",
        "plugin.start.failed" to "plugin_start_failed",
        "engine.failed" to "engine_error", "engine.exit" to "engine_exit",
    )
    fun supported(event: String): Boolean = event in failures

    /** Public-to-app helper for a source-owned summary, never arbitrary redacted user prose. */
    fun summary(message: String): String = EngineFailureProjection.fields(message)["error_summary"] ?: "Failure details withheld"

    fun summary(fields: JSONObject?, event: String): JSONObject {
        val code = failures[event] ?: return JSONObject().put("code", "unknown_failure").put("summary", "Failure details withheld")
        val result = JSONObject().put("code", code)
        val raw = listOf("reason", "err", "error", "message").firstNotNullOfOrNull { key ->
            (fields?.opt(key) as? String)?.takeIf { it.isNotBlank() }?.take(32768)
        }
        val clazz = (fields?.opt("error_class") as? String)?.takeIf { it in setOf("SyntaxError", "TypeError", "ReferenceError", "RangeError", "Error") }
        val projectedMessage = fields?.opt("error_message") as? String
        val projection = EngineFailureProjection.fields(raw ?: projectedMessage?.take(1024)?.let { "${clazz ?: "Error"}: $it" }.orEmpty())
        val useful = projection["error_code"] != "unknown_failure" && projection["error_code"] != "engine_error"
        val text = if (useful) projection.getValue("error_summary") else when (code) {
            "runtime_bootstrap_failed" -> "Runtime bootstrap failed"
            "web_renderer_gone" -> "Web renderer process exited"
            "web_load_failed" -> "Web page load failed"
            "web_script_error" -> "Web script reported an error"
            "web_error" -> "Web reported an error"
            "web_native_failed" -> "Native Web host failed"
            "native_crash" -> "Native process reported a crash"
            "native_error" -> "Native operation failed"
            "plugin_start_failed" -> "Plugin startup or activation failed"
            "engine_exit" -> "Engine process exited"
            else -> "Engine reported an error"
        }
        result.put("summary", text.take(256))
        if (useful) result.put("detail_code", projection["error_code"])
        (projection["error_class"] ?: clazz)?.let { result.put("error_class", it) }
        if (useful) result.put("error_message", text.take(256))
        val locations = linkedSetOf<String>()
        projection["safe_stack"]?.lineSequence()?.take(8)?.forEach { EngineFailureProjection.safeLocation(it)?.let(locations::add) }
        val path = fields?.opt("error_path") as? String
        val line = (fields?.opt("error_line") as? String)?.takeIf { it.matches(Regex("[0-9]{1,9}")) }
        if (path != null && line != null) EngineFailureProjection.safeLocation("$path:$line")?.let(locations::add)
        (fields?.opt("safe_stack") as? String)?.take(8192)?.lineSequence()?.take(8)?.forEach { EngineFailureProjection.safeLocation(it)?.let(locations::add) }
        val stack = JSONArray()
        locations.take(8).forEach { value ->
            val parts = value.split(':')
            stack.put(JSONObject().put("path", parts[0]).put("line", parts[1].toLong()).put("column", parts.getOrNull(2)?.toLong() ?: 0))
        }
        if (stack.length() > 0) result.put("safe_stack", stack)
        // Only source-owned bounded numeric/bool values survive native and Web events.
        for ((source, target) in mapOf("code" to "exit_code", "errorCode" to "web_error_code", "status" to "http_status")) {
            val number = fields?.opt(source)?.toString()?.takeIf { it.matches(Regex("-?[0-9]{1,6}")) }?.toIntOrNull()
            if (number != null && number in -65535..65535) result.put(target, number)
        }
        for ((source, target) in mapOf("crash" to "crashed", "wasReady" to "was_ready", "isMainFrame" to "main_frame")) {
            when (fields?.opt(source)) {
                true, "true" -> result.put(target, true)
                false, "false" -> result.put(target, false)
            }
        }
        // URLs/hosts, Authorization/Bearer/query/token/key/password/secrets, throwable messages,
        // arbitrary event fields and function names have no pass-through route here.
        return result
    }
}
