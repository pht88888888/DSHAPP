package android.system

import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile

class ErrnoException(val functionName: String, val errno: Int): Exception(functionName)
class StructStat(val st_uid: Int = 0, val st_mode: Int = 32768, val st_size: Long = 1)
object OsConstants {
    const val O_RDONLY = 0; const val O_NOFOLLOW = 0x20000; const val O_NONBLOCK = 0x800
    const val ENOENT = 2
    fun S_ISREG(mode: Int) = mode and 0xf000 == 32768
}
object Os {
    private val opened = mutableMapOf<FileDescriptor, RandomAccessFile>()
    // /proc is deliberately unrelated to fixture UID; real PID identity is not simulated.
    fun stat(path: String): StructStat { if (!File(path).exists()) throw ErrnoException("stat", 2); return StructStat() }
    fun open(path: String, flags: Int, mode: Int): FileDescriptor = synchronized(opened) {
        val file = RandomAccessFile(path, "r"); file.fd.also { opened[it] = file }
    }
    fun fstat(fd: FileDescriptor): StructStat = synchronized(opened) { StructStat(st_size = opened.getValue(fd).length()) }
    fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, size: Int): Int = synchronized(opened) { opened.getValue(fd).read(bytes, offset, size) }
    fun close(fd: FileDescriptor) { synchronized(opened) { opened.remove(fd)?.close() } }
}
