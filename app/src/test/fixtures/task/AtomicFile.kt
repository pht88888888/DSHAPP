package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Disk fixture supplies persistence only; Android AtomicFile crash durability is not tested. */
class AtomicFile(private val file: File) {
    fun startWrite(): FileOutputStream { file.parentFile!!.mkdirs(); return FileOutputStream(file) }
    fun finishWrite(stream: FileOutputStream) { stream.close() }
    fun failWrite(stream: FileOutputStream) { stream.close() }
    fun openRead() = FileInputStream(file)
}
