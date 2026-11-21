package com.dsh.android.repair

import android.system.Os
import android.system.OsConstants
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogContext
import java.io.File

/** Projects fixed evidence fields, never raw log lines, messages, paths or event field values. */
internal object RepairDiagnostics {
    private const val BOOT_FILE = "boot-state.json"
    private const val MAX_BOOT_BYTES = 8 * 1024
    private const val MAX_EVENT_BYTES = 64 * 1024
    fun report(filesDir: File): String {
        val logs = File(filesDir, LogContext.LOG_DIR)
        val boot = read(logs, BOOT_FILE, MAX_BOOT_BYTES, tail = false)
        val events = read(logs, EventLog.FILE_NAME, MAX_EVENT_BYTES, tail = true)
        return RepairDiagnosticSummary.summarize(boot, events)
    }

    fun evidence(filesDir: File): org.json.JSONObject {
        val logs = File(filesDir, LogContext.LOG_DIR)
        return evidenceFromLogs(logs).put("summary", report(filesDir))
    }

    fun evidenceFromLogs(logs: File): org.json.JSONObject {
        return RepairFailureEvidence.extract(
            read(logs, BOOT_FILE, MAX_BOOT_BYTES, tail = false),
            read(logs, EventLog.FILE_NAME, MAX_EVENT_BYTES, tail = true),
        )
    }

    private fun read(directory: File, name: String, limit: Int, tail: Boolean): String? = runCatching {
        // Fixed single-level child names. No traversal, blocking reads of pipes, or final symlink.
        if (directory.canonicalFile != File(directory.parentFile!!.canonicalFile, directory.name)) return null
        val fd = Os.open(File(directory, name).path,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode)) return null
            if (!tail && stat.st_size > limit) return null
            val truncated = tail && stat.st_size > limit
            if (truncated) Os.lseek(fd, stat.st_size - limit, OsConstants.SEEK_SET)
            val data = ByteArray(limit)
            var total = 0
            while (total < data.size) {
                val n = Os.read(fd, data, total, data.size - total)
                if (n <= 0) break
                total += n
            }
            val text = String(data, 0, total, Charsets.UTF_8)
            if (truncated) text.substringAfter('\n', "") else text
        } finally {
            Os.close(fd)
        }
    }.getOrNull()
}
