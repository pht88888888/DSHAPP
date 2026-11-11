package com.dsh.android.bridge.transport

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * 只负责完整文件的原子发布；请求领件、去重、执行、重试和结果 JSON 都由各领域持有。
 * 不支持原子替换或写入失败时抛回调用方，绝不退回直接写目标文件。
 */
object AtomicMailboxFiles {
    fun publishText(target: File, text: String) = publish(target, text.toByteArray(Charsets.UTF_8))

    fun publish(target: File, bytes: ByteArray) {
        val directory = checkNotNull(target.absoluteFile.parentFile).canonicalFile
        val destination = File(directory, target.name).toPath()
        val temporary = Files.createTempFile(directory.toPath(), ".${target.name}-", ".tmp")
        var failure: Throwable? = null
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                // createTempFile 的真实父目录固定，清理只针对本次发布的唯一临时文件。
                check(temporary.parent == directory.toPath())
                Files.deleteIfExists(temporary)
            } catch (cleanupError: Throwable) {
                if (failure != null) failure.addSuppressed(cleanupError)
                else throw cleanupError
            }
        }
    }
}
