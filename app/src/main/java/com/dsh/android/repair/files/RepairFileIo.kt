package com.dsh.android.repair.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** OS boundary. Content operations are streaming; limits apply to messages, never target size. */
internal interface RepairFileIo {
    data class Stat(val directory: Boolean, val regular: Boolean, val mode: Int,
        val size: Long = 0, val identity: String = "")
    fun statNoFollow(file: File): Stat?
    fun <T> input(file: File, action: (InputStream) -> T): T
    fun write(file: File, mode: Int, expectedSha256: String? = null, action: (OutputStream) -> Unit)
    fun mkdir(file: File, mode: Int = 448)
    fun remove(file: File, expectedSha256: String)
    fun rmdir(file: File, identity: String)
    fun children(directory: File): List<String>
    fun availableBytes(directory: File): Long
    /** Emulated external storage exposes platform-managed permissions, not durable POSIX modes. */
    fun platformManagedMode(file: File): Boolean = false
    fun read(file: File, limit: Int): ByteArray = input(file) { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(minOf(32768, limit + 1))
        while (true) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) reject("file_too_large", "File exceeds bounded read; use offset/length")
        }
        out.toByteArray()
    }
    fun replace(file: File, bytes: ByteArray, mode: Int, expectedSha256: String? = null) =
        write(file, mode, expectedSha256) { it.write(bytes) }
}

internal const val ABSENT = "absent"
internal data class FileDigest(val sha: String, val size: Long)
internal fun RepairFileIo.digest(file: File): FileDigest = input(file) { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32768)
    var size = 0L
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n); size = Math.addExact(size, n.toLong())
    }
    FileDigest(digest.digest().joinToString("") { "%02x".format(it) }, size)
}
/** Only constant operation tags and errno cross the model boundary, never raw OS error paths. */
internal class RepairIoException(val operation: String, val errno: Int) : Exception("Target filesystem call failed")
internal class RepairFileException(val code: String, override val message: String) : Exception(message)
internal fun reject(code: String, message: String): Nothing = throw RepairFileException(code, message)
