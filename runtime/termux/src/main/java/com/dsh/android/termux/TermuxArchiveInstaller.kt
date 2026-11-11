package com.dsh.android.termux

import android.system.Os
import com.dsh.android.log.DshLog as Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Bootstrap 归档与软链事务；目录重建、标记和调用锁由 TermuxBootstrap 拥有。 */
internal class TermuxArchiveInstaller(
    private val prefixDir: File,
    private val openAsset: (String) -> InputStream,
) {
    fun extract(zipName: String) {
        openAsset(zipName).use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    /*
                     * ⚠️ **必须校验解包路径不越界（zip-slip）。**
                     *
                     * `File(prefixDir, name)` 对含 `..` 或绝对路径的 name
                     * **不做任何限制**：
                     *   · name = "../../../data/data/com.dsh.android/shared_prefs/x"
                     *     会写到 prefix 之外；
                     *   · name = "/data/local/tmp/x"（绝对路径）在 Java 侧虽然
                     *     会被当成相对路径处理，但 `..` 一定会生效。
                     *
                     * 本项目的 bootstrap zip 是自己打包的，正常不会有这种条目；
                     * 但这条路径解的是**应用资产里的压缩包**，一旦打包环节出问题、
                     * 或将来换成可下载的 bootstrap，这就是一条写到沙箱任意位置的通道。
                     * 校验成本只有一次 canonicalPath 比较，值得加。
                     *
                     * 与 `EngineManager.ensureMobileUi()` 里已有的同名校验保持一致的写法。
                     */
                    val outFile = File(prefixDir, name)
                    val canonicalTarget = outFile.canonicalPath
                    val canonicalRoot = prefixDir.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalRoot + File.separator) &&
                        canonicalTarget != canonicalRoot
                    ) {
                        Log.w(TAG, "跳过越界的解包条目: $name")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (name == "SYMLINKS.txt") {
                        outFile.outputStream().use { zis.copyTo(it) }
                    } else {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zis.copyTo(it) }
                            if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                                name.startsWith("lib/apt/methods/")) {
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (symlinksFile.exists()) {
            processSymlinks(symlinksFile, prefixDir)
            symlinksFile.delete()
        }

    }

    private fun processSymlinks(symlinksFile: File, prefixDir: File) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath
        symlinksFile.readLines().forEach { line ->
            val parts = line.split("←")
            if (parts.size == 2) {
                val target = parts[0].replace(oldPrefix, newPrefix)
                try {
                    Os.symlink(target, File(prefixDir, parts[1]).absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "symlink ${parts[1]} -> $target: ${e.message}")
                }
            }
        }
    }

    private companion object { const val TAG = "TermuxBootstrap" }
}
