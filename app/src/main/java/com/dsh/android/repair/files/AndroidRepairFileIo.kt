package com.dsh.android.repair.files

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Trusted Context directories are opened directly, never their public parents. Descendants
 * are walked using pinned procfd parents + NOFOLLOW. No SDK-private O_DIRECTORY constant. */
internal class AndroidRepairFileIo private constructor(private val roots: List<File>, private val externalRoots: List<File>) : RepairFileIo {
    constructor(filesDir: File) : this(listOf(filesDir.absoluteFile), emptyList())
    constructor(filesDir: File, roots: Map<String, File>) : this((roots.values + filesDir).map { it.absoluteFile }.distinctBy { it.path },
        roots.filterKeys { it.startsWith("external_") }.values.map { it.absoluteFile })
    override fun platformManagedMode(file: File): Boolean = externalRoots.any { file.absolutePath == it.path || file.absolutePath.startsWith(it.path + "/") }
    private val openFlags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK

    override fun statNoFollow(file: File): RepairFileIo.Stat? = ioOperation("stat") {
        val (root, parts) = anchor(file)
        if (parts.isEmpty()) withDirectory(root) { stat(Os.fstat(it.fileDescriptor)) }
        else withParent(file) { path -> try { stat(Os.lstat(path)) } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) null else throw e
        } }
    }
    private fun stat(s: android.system.StructStat) = RepairFileIo.Stat(OsConstants.S_ISDIR(s.st_mode),
        OsConstants.S_ISREG(s.st_mode) && s.st_nlink == 1L, s.st_mode and 511, s.st_size, "${s.st_dev}:${s.st_ino}")

    override fun <T> input(file: File, action: (InputStream) -> T): T = ioOperation("input") { withParent(file) { path ->
        val fd = Os.open(path, openFlags, 0)
        try {
            val s = Os.fstat(fd)
            if (!OsConstants.S_ISREG(s.st_mode) || s.st_nlink != 1L) reject("unsafe_file", "Expected a regular file with one link")
            val stream = object : InputStream() {
                override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val n = Os.read(fd, b, off, len); return if (n == 0) -1 else n
                }
                override fun close() {} // Descriptor lifetime belongs to this scope.
            }
            action(stream)
        } finally { Os.close(fd) }
    } }

    override fun write(file: File, mode: Int, expectedSha256: String?, action: (OutputStream) -> Unit) = ioOperation("write") { withParent(file) { path ->
        val parent = path.substringBeforeLast('/')
        val temporary = "$parent/.repair-${UUID.randomUUID()}.tmp"
        var temporaryExists = false
        try {
            val fd = Os.open(temporary, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 384)
            temporaryExists = true
            try {
                action(object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        var done = 0
                        while (done < len) {
                            val n = Os.write(fd, b, off + done, len - done)
                            if (n <= 0) reject("io_error", "Short write")
                            done += n
                        }
                    }
                    override fun close() {}
                })
                if (!platformManagedMode(file)) Os.fchmod(fd, mode and 511)
                Os.fsync(fd)
            } finally { Os.close(fd) }
            checkExpected(file, expectedSha256)
            if (expectedSha256 == ABSENT) {
                // link is atomic and fails if a concurrent creator won; rename would clobber it.
                try {
                    Os.link(temporary, path)
                } catch (e: ErrnoException) {
                    if (e.errno !in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.EOPNOTSUPP, OsConstants.ENOSYS)) throw e
                    // Trusted private storage policies and emulated storage may refuse links.
                    // Reserve with O_EXCL, then replace only our still-empty reservation.
                    // EEXIST and unexpected I/O errors never enter this fallback. A crash is conflict,
                    // not an assertion that creation completed; no pre-existing data is lost.
                    val reservation = Os.open(path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 384)
                    val identity = try { stat(Os.fstat(reservation)).identity } finally { Os.close(reservation) }
                    val current = stat(Os.lstat(path))
                    if (!current.regular || current.size != 0L || current.identity != identity) reject("hash_conflict", "Creation reservation changed")
                    try {
                        Os.rename(temporary, path); temporaryExists = false
                    } catch (failure: Exception) {
                        val leftover = try { stat(Os.lstat(path)) } catch (_: Exception) { null }
                        if (leftover?.identity == identity && leftover.regular && leftover.size == 0L) try { Os.remove(path) } catch (_: Exception) {}
                        throw failure
                    }
                }
                if (temporaryExists) { Os.remove(temporary); temporaryExists = false }
            } else {
                Os.rename(temporary, path); temporaryExists = false
            }
            syncParent(parent)
        } finally {
            if (temporaryExists) try { Os.remove(temporary) } catch (_: Exception) { /* preserve original failure */ }
        }
    } }
    private fun checkExpected(file: File, expected: String?) {
        if (expected == null) return
        val current = statNoFollow(file)
        if (expected == ABSENT) {
            if (current != null) reject("hash_conflict", "Destination was created concurrently")
        } else if (current?.regular != true || digest(file).sha != expected) {
            reject("hash_conflict", "Target changed before publication; nothing was overwritten")
        }
    }
    override fun mkdir(file: File, mode: Int) = ioOperation("mkdir") { withParent(file) { path ->
        Os.mkdir(path, mode and 511); syncParent(path.substringBeforeLast('/'))
    } }
    override fun remove(file: File, expectedSha256: String) = ioOperation("remove") { withParent(file) { path ->
        checkExpected(file, expectedSha256)
        Os.remove(path); syncParent(path.substringBeforeLast('/'))
    } }
    override fun rmdir(file: File, identity: String) = ioOperation("rmdir") { withParent(file) { path ->
        val s = statNoFollow(file)
        if (s?.directory != true || s.identity != identity) reject("hash_conflict", "Directory identity changed")
        Os.remove(path); syncParent(path.substringBeforeLast('/'))
    } }
    override fun children(directory: File): List<String> = ioOperation("children") { withDirectory(directory) { fd ->
        File("/proc/self/fd/${fd.fd}").list()?.toList() ?: reject("io_error", "Cannot list directory")
    } }
    override fun availableBytes(directory: File): Long = ioOperation("space_query") { withDirectory(directory) { fd ->
        val s = Os.fstatvfs(fd.fileDescriptor)
        if (s.f_frsize <= 0 || s.f_bavail < 0) reject("storage_unavailable", "Cannot determine free space")
        if (s.f_bavail > Long.MAX_VALUE / s.f_frsize) Long.MAX_VALUE else s.f_bavail * s.f_frsize
    } }
    private inline fun <T> ioOperation(operation: String, action: () -> T): T = try { action() }
        catch (e: ErrnoException) { throw RepairIoException(operation, e.errno) }

    private fun syncParent(path: String) {
        val fd = Os.open("$path/.", openFlags, 0)
        try {
            try { Os.fsync(fd) } catch (e: ErrnoException) {
                if (e.errno in setOf(OsConstants.EINVAL, OsConstants.EPERM, OsConstants.EOPNOTSUPP))
                    reject("unsupported_durability", "Filesystem refused directory fsync; inspect transaction recovery state before retry")
                throw e
            }
        } finally { Os.close(fd) }
    }
    private fun anchor(file: File): Pair<File, List<String>> {
        val path = file.absolutePath
        if (path.indexOf('\u0000') >= 0 || path.split('/').any { it == "." || it == ".." }) reject("unsafe_path", "Non-canonical path")
        val root = roots.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
            ?: reject("unsafe_path", "Path is outside trusted anchors")
        if (path == root.path) return root to emptyList()
        return root to path.removePrefix(root.path + "/").split('/').also {
            if (it.any(String::isEmpty)) reject("unsafe_path", "Empty path component")
        }
    }
    private fun <T> withParent(file: File, action: (String) -> T): T {
        if (anchor(file).second.isEmpty()) reject("unsafe_path", "Anchor cannot be changed as a file")
        return withDirectory(file.absoluteFile.parentFile!!) { action("/proc/self/fd/${it.fd}/${file.name}") }
    }
    private fun <T> withDirectory(directory: File, action: (ParcelFileDescriptor) -> T): T {
        val (root, parts) = anchor(directory)
        var current = directoryDescriptor(root.path)
        try {
            for (part in parts) {
                val next = directoryDescriptor("/proc/self/fd/${current.fd}/$part")
                try { current.close() } catch (e: Exception) { next.close(); throw e }
                current = next
            }
            return action(current)
        } finally { current.close() }
    }
    private fun directoryDescriptor(path: String): ParcelFileDescriptor {
        val fd = Os.open(path, openFlags, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) reject("unsafe_path", "Ancestor is not a directory")
            return ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }
}
