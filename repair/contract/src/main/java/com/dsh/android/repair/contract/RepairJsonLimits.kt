package com.dsh.android.repair.contract

/** Bound JSON container depth while ignoring brackets inside quoted source-code strings. */
object RepairJsonLimits {
    fun accepts(text: String, maxDepth: Int = 16): Boolean {
        var quoted = false
        var escaped = false
        var depth = 0
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > maxDepth) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return !quoted && !escaped && depth == 0
    }
}
