package com.dsh.android.termux.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把文本**原子地**写到文件：先写同目录临时文件 → fsync → 改名覆盖。
 *
 * ## 🔴 为什么需要它（2026-09-26 走查发现）
 *
 * `BooViewModel.writeSessionTo` 原本是这么写的：
 *
 * ```kotlin
 * try {
 *     file.bufferedWriter().use { w -> ...逐条写消息... }
 * } catch (_: Exception) {}          // ← 异常被完全吞掉
 * ```
 *
 * 两个缺陷叠在一起，后果很重：
 *
 * 1. **`bufferedWriter()` 会先 TRUNCATE 目标文件。** 写到一半失败（磁盘满、
 *    进程被杀、序列化异常），文件里只剩**前几条消息**——原本完整的会话历史
 *    已经被截掉了，**不可恢复**。这正是"写盘时断电"会毁掉整个会话的原因。
 * 2. **异常被 `catch (_: Exception) {}` 吞掉。** 调用点拿不到任何信号，于是
 *    `newSession()` 紧接着执行 `messages.clear()` —— 内存里的对话也一起没了。
 *    用户看到的是"新建会话后历史全消失"，没有任何报错。
 *
 * 临时文件 + 改名让写入变成**全有或全无**：任何一步失败，目标文件保持原样。
 *
 * ## 与 `engine/fileutil/FileUtil.atomicWriteFile` 的关系
 *
 * 引擎侧已有一份更完整的实现，但它面向 POSIX 权限位（要传 `perm`、会按
 * `perm` 反推目录权限），且被 oracle 金标钉住。会话文件是 app 私有目录下的
 * 普通文本，不需要权限位；引它会让 app 层多一个引擎耦合点。所以这里保留一份
 * 精简实现，**并把差异用测试钉住**（`AtomicTextFileTest`）。
 */
object AtomicTextFile {

    /** 临时文件前缀。以 `.` 开头，避免被会话列表当成会话文件扫进去。 */
    private const val TMP_PREFIX = ".tmp-"

    /**
     * 原子写入 [text]（UTF-8）到 [target]。
     *
     * @return 成功为 `true`；失败为 `false`，**且此时 [target] 的内容未被改动**。
     *   不抛异常 —— 调用方在写盘失败后往往还要继续用旧数据，抛异常会让
     *   「保留旧会话」这条路径本身也需要 try/catch。
     */
    fun write(target: File, text: String): Boolean {
        val dir = target.absoluteFile.parentFile
            ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false

        var tmp: File? = null
        try {
            tmp = File.createTempFile(TMP_PREFIX, ".tmp", dir)
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // fsync：`close()` 只把数据交给 OS，掉电仍可能留下空文件。
                out.fd.sync()
            }
            replace(tmp, target)
            return true
        } catch (_: Throwable) {
            // 目标文件必须保持原样，这是本函数的全部意义。
            return false
        } finally {
            // 成功时 replace 已把临时文件移走；失败时这里清掉残留。
            // `delete()` 对不存在的文件返回 false，无需先判断。
            tmp?.delete()
        }
    }

    /**
     * 把 [tmp] 改名到 [dest]，必要时覆盖 [dest]。
     *
     * ⚠️ `File.renameTo` **不保证覆盖已存在的目标**（Android/POSIX 上通常可以，
     * 但它在失败时只返回 false 而不抛异常），所以优先用 `Files.move` +
     * `ATOMIC_MOVE`；不支持原子改名的文件系统回退到普通覆盖。
     */
    private fun replace(tmp: File, dest: File) {
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: IOException) {
            // ATOMIC_MOVE 不被支持，或跨设备；回退到普通覆盖。
        } catch (_: UnsupportedOperationException) {
            // 同上。
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * 清理 [dir] 里遗留的临时文件。
     *
     * 上一次写入被强杀（进程被 OOM killer 干掉、用户强退）会留下 `.tmp-*`。
     * 它们不会被当成会话文件（前缀是 `.`），但会白占空间。
     *
     * @return 删掉的个数。只删本对象自己的前缀，不碰别的文件。
     */
    fun sweepTemp(dir: File): Int {
        val stale = dir.listFiles { f -> f.isFile && f.name.startsWith(TMP_PREFIX) }
            ?: return 0
        return stale.count { it.delete() }
    }
}
