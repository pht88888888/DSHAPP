package com.dsh.android.termux

import com.dsh.android.log.DshLog as Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 文本路径与等长 ELF 改写。调用者必须持有进程锁和 prefix 文件锁。 */
internal class TermuxPathRewriter(
    private val dataDir: String,
    private val elfRootReplacement: String,
) {
    fun rewriteLocked(prefixDir: File) {
        val textNew = dataDir
        if (OLD_TERMUX_ROOT == textNew) return

        val elfOld = OLD_TERMUX_ROOT.toByteArray(Charsets.ISO_8859_1)
        val elfNew = elfRootReplacement.toByteArray(Charsets.ISO_8859_1)
        var patched = 0

        prefixDir.walkTopDown()
            // Do not descend into directory symlinks: the upstream bootstrap
            // ships links such as `bin/applets`, and following one would let us
            // walk (and now *write*) outside the prefix.
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                if (!file.isFile) return@forEach
                // Never patch *through* a file symlink either — see onEnter.
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                try {
                    if (isElf(file)) {
                        if (file.length() <= MAX_ELF_BYTES) {
                            patched += patchElfInPlace(file, elfOld, elfNew)
                        }
                    } else {
                        if (file.length() > MAX_TEXT_BYTES) return@forEach
                        val text = file.readText()
                        if (OLD_TERMUX_ROOT in text) {
                            // Preserve executable bit: writeText creates a new
                            // file which resets permissions to 644, but scripts
                            // in bin/ must stay executable or exec() fails.
                            val wasExec = file.canExecute()
                            file.writeText(text.replace(OLD_TERMUX_ROOT, textNew))
                            if (wasExec) file.setExecutable(true, false)
                        }
                    }
                } catch (_: Exception) { /* skip unreadable files silently */ }
            }
        if (patched > 0) Log.i(TAG, "patched $patched ELF path reference(s)")
    }

    private fun isElf(file: File): Boolean = try {
        FileInputStream(file).use { `in` ->
            val magic = ByteArray(4)
            val n = `in`.read(magic, 0, 4)
            n == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Replace every occurrence of [old] with [new] inside an ELF file, in place.
     *
     * Requires `old.size == new.size` ([new] must be a pure overwrite). Returns
     * the number of substitutions. Uses only Java 8 APIs and a manual read loop
     * — see the project's rule against Java 9+ APIs, which compile fine on the
     * host JDK and then throw `NoSuchMethodError` on device.
     *
     * ## 🔴 写回为什么必须是「临时文件 + 改名」
     *
     * 原来是 `FileOutputStream(file).write(bytes)`：**直接截断并原地重写**。
     * node 是 47 MB，这个窗口有几十毫秒，期间磁盘上是一个**半截文件** ——
     * 任何在这个窗口里 exec 它、或读到一半的进程，都会拿到「大小对得上、
     * 内容已经坏掉」的二进制。
     *
     * 临时文件 + `Files.move` 让写回变成全有或全无：目标要么是改前的完整文件，
     * 要么是改后的完整文件，**没有中间态**。同目录内的改名在同一文件系统上，
     * 不涉及拷贝。
     */
    private fun patchElfInPlace(file: File, old: ByteArray, new: ByteArray): Int {
        if (old.size != new.size) return 0
        val size = file.length().toInt()
        if (size <= 0 || size < old.size) return 0

        val bytes = ByteArray(size)
        FileInputStream(file).use { `in` ->
            var off = 0
            while (off < size) {
                val n = `in`.read(bytes, off, size - off)
                if (n <= 0) break
                off += n
            }
            // Short read ⇒ the file changed under us. Bail rather than write a
            // buffer with a zeroed tail over a live binary.
            if (off != size) return 0
        }

        var hits = 0
        var i = 0
        while (i <= size - old.size) {
            if (bytes[i] != old[0]) { i++; continue }
            var k = 1
            while (k < old.size && bytes[i + k] == old[k]) k++
            if (k == old.size) {
                System.arraycopy(new, 0, bytes, i, new.size)
                hits++
                i += old.size
            } else {
                i++
            }
        }
        if (hits == 0) return 0

        val wasExec = file.canExecute()
        val dir = file.absoluteFile.parentFile ?: return 0
        var tmp: File? = null
        try {
            // 临时名必须唯一：固定名字会让并发/残留进程写到同一个文件上。
            tmp = File.createTempFile(".${file.name}.", ".patched", dir)
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.flush()
                // fsync：close() 只把数据交给 OS，掉电会留下一个大小正确但内容全零的文件 ——
                // 对一个可执行文件来说与写坏无异。
                it.fd.sync()
            }
            if (wasExec) tmp.setExecutable(true, false)
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            tmp = null
        } catch (t: Throwable) {
            // 保留改前的完整文件，这是本函数这层保护的全部意义。
            Log.w("TERMUX", "重写 ${file.name} 失败，保留原文件: ${t.message}")
            return 0
        } finally {
            tmp?.delete()
        }
        return hits
    }

    private companion object {
        const val TAG = "TermuxBootstrap"
        const val OLD_TERMUX_ROOT = "/data/data/com.termux/files"
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        const val MAX_ELF_BYTES = 64L * 1024 * 1024
    }
}
