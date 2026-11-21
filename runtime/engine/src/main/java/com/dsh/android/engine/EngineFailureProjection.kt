package com.dsh.android.engine

/** Source-owned error templates and relative locations only. Never copies a log/source line.
 * This is deliberately not a general-purpose log redactor: unknown prose becomes a fixed code. */
object EngineFailureProjection {
    private const val MAX_INPUT = 32768
    private val classes = Regex("^(SyntaxError|TypeError|ReferenceError|RangeError|Error)(?: \\[[A-Z_]{1,64}\\])?:")
    private val location = Regex("(?:file://)?(?:/[^\\s:?#]+)*/files/(engine/[A-Za-z0-9_@.+/-]{1,512}):(\\d{1,9})(?::(\\d{1,9}))?(?=[)\\s]|$)")
    private val relative = Regex("^(engine/[A-Za-z0-9_@.+/-]{1,512}):(\\d{1,9})(?::(\\d{1,9}))?$")
    private val sensitive = Regex("(?i)(token|bearer|password|passwd|secret|credential|api[-_]?key|private[-_]?key|authorization|cookie)")
    private val ansi = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    private val quotedModule = Regex("(?:Cannot find (?:module|package)|Failed to (?:load|resolve) (?:module|package)) ['\"]([^'\"\\r\\n]{1,160})['\"]", RegexOption.IGNORE_CASE)
    private val plugin = Regex("(?:failed to (?:load|start|activate) plugins?|plugin (?:load|startup|activation) failed)", RegexOption.IGNORE_CASE)

    /** Accepts only a relative engine location, no URL, query, source text or private path. */
    fun safeLocation(value: String): String? {
        val match = relative.matchEntire(value) ?: return null
        val path = match.groupValues[1]
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." || it.length > 96 ||
                Regex("(?i)(?:sk-|ghp_|github_pat_|AKIA|eyJ)[A-Za-z0-9_-]{8,}").containsMatchIn(it) } || sensitive.containsMatchIn(path)) return null
        return path + ":" + match.groupValues[2] + match.groupValues[3].takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()
    }

    private fun module(value: String): String? {
        if (!value.matches(Regex("(?:@[a-zA-Z0-9_.-]{1,48}/)?[a-zA-Z0-9_.-]{1,64}(?:/[a-zA-Z0-9_.-]{1,64}){0,5}"))) return null
        if (value.startsWith('.') || value.split('/').any { it == ".." || it == "." } || sensitive.containsMatchIn(value)) return null
        // Long opaque components are commonly credentials, not actionable module identities.
        if (value.split('/').any { Regex("[A-Za-z0-9_-]{32,}").containsMatchIn(it) }) return null
        return value.take(160)
    }

    fun fields(output: String): Map<String, String> {
        val text = ansi.replace(output.takeLast(MAX_INPUT), "")
        val lines = text.lineSequence().take(256).filter { it.length <= 2048 }.toList()
        val result = linkedMapOf<String, String>()
        val errorLines = lines.mapNotNull { line -> val value = line.trim(); classes.find(value)?.let { it.groupValues[1] to value.substring(it.range.last + 1).trim() } }
        val error = errorLines.firstOrNull()
        val message = error?.second.orEmpty()
        error?.first?.let { result["error_class"] = it }
        val frames = linkedSetOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            // Location-only headers and stack frames, never arbitrary source/string-literal lines.
            if (!(trimmed.startsWith("at ") || trimmed.startsWith("file://") || trimmed.startsWith('/'))) continue
            for (match in location.findAll(trimmed).take(8)) {
                safeLocation(match.groupValues[1] + ":" + match.groupValues[2] + match.groupValues[3].takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty())?.let { frames.add(it) }
                if (frames.size == 8) break
            }
            if (frames.size == 8) break
        }
        frames.firstOrNull()?.let {
            result["error_path"] = it.substringBefore(':')
            result["error_line"] = it.substringAfter(':').substringBefore(':')
        }
        if (frames.isNotEmpty()) result["safe_stack"] = frames.joinToString("\n")
        val diagnosticLines = errorLines.map { it.second } + lines.map { it.trim() }.filter {
            Regex("(?i)^(?:failed to (?:load|start|activate) plugins?|plugin (?:load|startup|activation) failed)\\b").containsMatchIn(it)
        }
        val missing = diagnosticLines.firstNotNullOfOrNull { quotedModule.find(it)?.groupValues?.get(1) }
        val pair = when {
            missing != null -> "module_not_found" to (module(missing)?.let { "Cannot find module '$it'" } ?: "Cannot find module [redacted]")
            message == "Cannot find module [redacted]" -> "module_not_found" to "Cannot find module [redacted]"
            diagnosticLines.any { plugin.containsMatchIn(it) } || message == "Plugin startup or activation failed" -> "plugin_start_failed" to "Plugin startup or activation failed"
            message.contains("Invalid or unexpected token") -> "syntax_invalid_token" to "Invalid or unexpected token"
            message.startsWith("Unexpected end of input") || message.startsWith("Unexpected end of JSON input") -> "syntax_unexpected_end" to "Unexpected end of input"
            message.startsWith("Unexpected token") -> "syntax_unexpected_token" to "Unexpected token [redacted]"
            message.startsWith("Cannot use import statement outside a module") -> "syntax_module_format" to "Cannot use import statement outside a module"
            message.startsWith("Cannot read properties of undefined") -> "type_undefined_property" to "Cannot read properties of undefined [redacted]"
            message.startsWith("Cannot read properties of null") -> "type_null_property" to "Cannot read properties of null [redacted]"
            error?.first == "ReferenceError" && message.contains("is not defined") -> "reference_not_defined" to "Reference is not defined [redacted]"
            error?.first == "TypeError" && message.contains("is not a function") -> "type_not_function" to "Value is not a function [redacted]"
            error?.first == "RangeError" && message.startsWith("Maximum call stack size exceeded") -> "range_stack_overflow" to "Maximum call stack size exceeded"
            lines.any { it.contains("ERR_MODULE_NOT_FOUND") || it.contains("MODULE_NOT_FOUND") } -> "module_not_found" to "Cannot find module [redacted]"
            error != null -> "engine_error" to "Engine reported an error"
            else -> "unknown_failure" to "Failure details withheld"
        }
        result["error_code"] = pair.first
        result["error_summary"] = pair.second
        if (error != null || pair.first != "unknown_failure") result["error_message"] = pair.second
        return result
    }
}
