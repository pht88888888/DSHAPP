package com.dsh.android.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Cross-process start/repair exclusion. Held before ProcessBuilder.start and until child exit. */
object EngineRepairLock {
    private val held = mutableSetOf<String>()

    @Synchronized fun tryAcquire(filesDir: File): Closeable? {
        val path = File(filesDir.canonicalFile, ".engine-repair.lock").path
        // POSIX record locks are per-process: closing ANY fd for this inode releases them.
        // Do not even open a second descriptor while this JVM owns the lock.
        if (path in held) return null
        val file = RandomAccessFile(path, "rw")
        val lock: FileLock = try { file.channel.tryLock() ?: run { file.close(); return null } }
            catch (_: Exception) { file.close(); return null }
        held.add(path)
        var released = false
        return Closeable {
            synchronized(this) {
                if (!released) {
                    released = true
                    try { lock.release() } finally { try { file.close() } finally { held.remove(path) } }
                }
            }
        }
    }
}
