package com.dsh.android.repair

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Conservative same-UID process check. Unknown state is never permission to alter executable files. */
internal object RepairEngineQuiescence {
    fun canModify(context: Context): Boolean {
        val processes = File("/proc").listFiles() ?: return false
        for (entry in processes) {
            if (entry.name.toIntOrNull() == null || entry.name.toIntOrNull() == Process.myPid()) continue
            val stat = try { Os.stat(entry.path) } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) continue
                return false
            }
            if (stat.st_uid != Process.myUid()) continue
            val argv = command(entry)?.let(ProcessPolicy::argv) ?: return false
            // A remaining main process could extract assets; an orphan Node can outlive it.
            if (ProcessPolicy.relevant(context.packageName, argv)) return false
        }
        return true
    }

    /** Provider Binder thread only, after consuming a one-use native approval explicitly
     * explaining that current target tasks will be interrupted. Never accept a caller PID. */
    fun stopKnownTarget(context: Context): Boolean {
        val policy = try {
            ProcessPolicy(context.packageName, Process.myUid(), Process.myPid(),
                File(context.filesDir, "termux/usr/bin/node").canonicalPath,
                File(context.filesDir, "engine").canonicalPath)
        } catch (_: Exception) { return false }
        val processes = File("/proc").listFiles() ?: return false
        val selected = mutableListOf<ProcessPolicy.Identity>()
        // Validate the entire set first. An unrelated same-UID Node must never be signalled.
        for (entry in processes) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid <= 0 || pid == policy.selfPid) continue
            val uid = try { Os.stat(entry.path).st_uid } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) continue
                return false
            }
            if (uid != policy.uid) continue
            val raw = command(entry) ?: return false
            val argv = ProcessPolicy.argv(raw) ?: return false
            if (!ProcessPolicy.relevant(policy.packageName, argv)) continue
            val snapshot = identity(entry, uid) ?: return false
            when (policy.select(snapshot)) {
                ProcessPolicy.Selection.STOP -> selected.add(snapshot)
                ProcessPolicy.Selection.BLOCK -> return false
                ProcessPolicy.Selection.IGNORE -> Unit
            }
        }
        val planned = policy.plan(selected) ?: return false
        for (before in planned) {
            val entry = File("/proc/${before.pid}")
            val uid = try { Os.stat(entry.path).st_uid } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) continue
                return false
            }
            val now = identity(entry, uid) ?: return false
            if (!policy.sameForSignal(before, now)) return false
            try { Os.kill(now.pid, OsConstants.SIGTERM) }
            catch (error: ErrnoException) { if (error.errno != OsConstants.ESRCH) return false }
        }
        // Bounded cooperative shutdown only. No SIGKILL, force-stop, shell, or UID-wide kill.
        val deadline = System.nanoTime() + 2_000_000_000L
        do {
            if (canModify(context)) return true
            if (System.nanoTime() >= deadline) return false
            try { Thread.sleep(10) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (true)
    }

    private fun identity(entry: File, uid: Int): ProcessPolicy.Identity? {
      return try {
        val first = bounded(entry, "stat", 4096)?.let(ProcessPolicy::startTime) ?: return null
        val argv = command(entry)?.let(ProcessPolicy::argv) ?: return null
        val executable = File(Os.readlink(File(entry, "exe").path)).canonicalPath
        val normalized = argv.map { value ->
            // Only canonicalize absolute argv paths; options and process titles stay exact.
            if (value.startsWith('/')) File(value).canonicalPath else value
        }
        val second = bounded(entry, "stat", 4096)?.let(ProcessPolicy::startTime) ?: return null
        if (first != second || Os.stat(entry.path).st_uid != uid) return null
        ProcessPolicy.Identity(entry.name.toInt(), uid, first, normalized, executable)
      } catch (_: Exception) { null }
    }

    private fun command(entry: File): String? = bounded(entry, "cmdline", 8192)?.takeIf {
        it.isNotEmpty() && it.last() == '\u0000'
    }

    private fun bounded(entry: File, name: String, maximum: Int): String? {
        val fd = try { Os.open(File(entry, name).path,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        } catch (_: Exception) { return null }
        return try {
            val bytes = ByteArray(maximum + 1)
            var count = 0
            while (count < bytes.size) {
                val n = Os.read(fd, bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            if (count == 0 || count > maximum) null else Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        } catch (_: Exception) { null } finally { Os.close(fd) }
    }

    /** Pure selection and PID-reuse protection; exercised against production Kotlin in host tests. */
    internal class ProcessPolicy(val packageName: String, val uid: Int, val selfPid: Int,
        private val nodeExecutable: String, private val engineRoot: String) {
        data class Identity(val pid: Int, val uid: Int, val startTime: String,
            val argv: List<String>, val executable: String)
        enum class Selection { IGNORE, STOP, BLOCK }

        fun select(identity: Identity): Selection {
            if (identity.pid == selfPid || identity.uid != uid) return Selection.IGNORE
            if (identity.pid <= 0 || !identity.startTime.matches(Regex("[0-9]+")) || identity.argv.isEmpty()) return Selection.BLOCK
            if (identity.argv.first() == packageName) return Selection.STOP
            if (!relevant(packageName, identity.argv)) return Selection.IGNORE
            val script = identity.argv.getOrNull(1)?.let(::trustedAlias)
            return if (trustedAlias(identity.executable) == nodeExecutable && trustedAlias(identity.argv.first()) == nodeExecutable &&
                script != null && script.startsWith("$engineRoot/") && normalized(script)) Selection.STOP
            else Selection.BLOCK
        }

        fun plan(identities: List<Identity>): List<Identity>? {
            if (identities.any { select(it) == Selection.BLOCK }) return null
            return identities.filter { select(it) == Selection.STOP }
                .sortedBy { if (it.argv.firstOrNull() == packageName) 0 else 1 }
        }

        // The two Android per-user paths are accepted only for this exact target package,
        // and only if filesDir was canonicalized to the corresponding known trusted root.
        private fun trustedAlias(path: String): String {
            val canonicalFiles = engineRoot.removeSuffix("/engine")
            val data = "/data/data/$packageName/files"
            val user = "/data/user/0/$packageName/files"
            if (canonicalFiles != data && canonicalFiles != user) return path
            for (alias in listOf(data, user)) {
                if (path.startsWith("$alias/")) return canonicalFiles + path.removePrefix(alias)
            }
            return path
        }

        fun sameForSignal(before: Identity, now: Identity): Boolean =
            before == now && select(now) == Selection.STOP

        companion object {
            fun relevant(packageName: String, argv: List<String>): Boolean =
                argv.firstOrNull() == packageName || argv.firstOrNull()?.substringAfterLast('/') == "node" ||
                    argv.any { it.contains("/engine/") || it.contains("/node_modules/@deepseek-ai/dsh/") }

            fun argv(raw: String): List<String>? {
                if (raw.isEmpty() || raw.last() != '\u0000') return null
                val values = raw.dropLast(1).split('\u0000')
                return values.takeIf { it.isNotEmpty() && it.first().isNotEmpty() }
            }

            // Linux comm is parenthesized and can contain spaces and ')'; fields begin after
            // the final ') '. starttime is field22, index19 after comm (field2).
            fun startTime(raw: String): String? {
                val close = raw.lastIndexOf(") ")
                val open = raw.indexOf('(')
                if (open < 1 || close <= open || raw.substring(0, open).trim().toIntOrNull() == null) return null
                val fields = raw.substring(close + 2).trim().split(Regex("\\s+"))
                return fields.getOrNull(19)?.takeIf { it.matches(Regex("[0-9]+")) }
            }

            private fun normalized(path: String): Boolean = path.startsWith('/') &&
                path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }
        }
    }
}
