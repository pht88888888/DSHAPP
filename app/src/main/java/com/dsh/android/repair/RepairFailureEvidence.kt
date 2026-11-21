package com.dsh.android.repair

import org.json.JSONArray
import org.json.JSONObject

/** Current-boot bounded semantic errors from engine/native/Web events, never stdout or source. */
internal object RepairFailureEvidence {
    fun extract(boot: String?, events: String?): JSONObject {
        val state = parse(boot)
        val bootId = (state?.opt("bootId") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
        val rows = ArrayDeque<JSONObject>()
        // Native reader already caps bytes. Keep the standalone API bounded too, including rows.
        for (line in events.orEmpty().takeLast(64 * 1024).lineSequence()) {
            val row = parse(line) ?: continue
            if (bootId == null || row.opt("bootId") != bootId) continue
            if (rows.size == 128) rows.removeFirst()
            rows.addLast(row)
        }
        val engineNames = setOf("engine.ready", "engine.failed", "engine.exit", "engine.start")
        val lastEngine = rows.lastOrNull { it.optString("name") in engineNames }
        val latestFailure = lastEngine?.optString("name") == "engine.failed"
        val result = JSONObject().put("ok", true).put("boot_id", bootId ?: JSONObject.NULL)
            .put("ui_loaded", state?.optBoolean("uiLoaded", false) ?: false)
            .put("engine_state", lastEngine?.optString("name") ?: "unknown")
            .put("error_locations", JSONArray())
        if (latestFailure) {
            val error = RepairEvidenceRedaction.summary(lastEngine?.optJSONObject("fields"), "engine.failed")
            if (error.has("error_class")) result.put("error_class", error.getString("error_class"))
            if (error.has("error_message")) result.put("error_message", error.getString("error_message"))
            result.put("error_code", error.optString("detail_code", error.getString("code")))
                .put("error_summary", error.getString("summary"))
            error.optJSONArray("safe_stack")?.let { result.put("safe_stack", it).put("error_locations", it) }
        }
        val relevant = rows.filter { row ->
            val name = row.optString("name")
            RepairEvidenceRedaction.supported(name) && when (name) {
                "engine.failed" -> latestFailure && row === lastEngine
                "engine.exit" -> row === lastEngine
                else -> true
            }
        }.takeLast(8)
        val errors = JSONArray()
        relevant.forEach { row ->
            val name = row.optString("name")
            errors.put(RepairEvidenceRedaction.summary(row.optJSONObject("fields"), name).put("event", name))
        }
        result.put("error_events", errors)
        // Separate name avoids RepairDiagnostics.evidence's existing local lifecycle 'summary'.
        if (!result.has("error_summary") && errors.length() > 0) {
            val last = errors.getJSONObject(errors.length() - 1)
            result.put("error_summary", last.getString("summary")).put("error_code", last.getString("code"))
        }
        return result
    }

    private fun parse(text: String?): JSONObject? = text?.takeIf { it.length <= 64 * 1024 &&
        it.count { ch -> ch == '{' || ch == '[' } <= 128 }?.let { runCatching { JSONObject(it) }.getOrNull() }
}
