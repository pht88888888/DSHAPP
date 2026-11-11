package com.dsh.android.browser

import com.dsh.android.bridge.transport.AtomicMailboxFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Properties

/**
 * browser 域独占的文件事务；Node 仍只写 browser-cmd.json、读 browser-result.json。
 * 原子领件保留请求实体，结果提交后仅删除该领件，绝不 unlink 后来到达的命令。
 * 回执先留内存、再落日志、再发布。重启遇到有回执的领件只重发，无回执则交上层明确报中断。
 */
internal class BrowserCommandMailbox(
    private val directory: File,
    private val report: (String, Throwable) -> Unit,
) {
    class Request internal constructor(val raw: String, val recovered: Boolean)
    class Completed internal constructor(val id: Long, val raw: String, val response: String)

    private val command = File(directory, "browser-cmd.json")
    private val processing = File(directory, "browser-cmd.processing.json")
    private val result = File(directory, "browser-result.json")
    private val receipt = File(directory, "browser-cmd.receipt")
    private var inFlight: Request? = null
    private var completed: Completed? = loadReceipt()

    /** 坏命令原地保留供生产方下一次原子覆盖；已领件在本进程内保有同一身份。 */
    fun claim(isValid: (String) -> Boolean): Request? {
        inFlight?.let { return it }
        return try {
            if (processing.isFile) {
                val raw = processing.readText()
                if (!isValid(raw)) {
                    // 损坏的旧领件不能挡住随后新命令；仅删除已确认不可解析的私有槽。
                    check(processing.delete()) { "无法清理损坏的 browser 领件" }
                    return null
                }
                Request(raw, recovered = true).also { inFlight = it }
            } else {
                if (!command.isFile || !isValid(command.readText())) return null
                Files.move(command.toPath(), processing.toPath(), ATOMIC_MOVE)
                val raw = processing.readText()
                if (!isValid(raw)) {
                    check(processing.delete()) { "无法清理损坏的 browser 领件" }
                    return null
                }
                Request(raw, recovered = false).also { inFlight = it }
            }
        } catch (error: Throwable) {
            report("领取 browser 请求失败", error)
            null
        }
    }

    /** id 是命令 ABI 的幂等键；最近已完成请求的重复投递只重发结果。 */
    fun completed(id: Long): Completed? = completed?.takeIf { it.id == id }

    /** 写入失败不删除领件，已执行结果留内存供下一轮重试，不重跑网页动作。 */
    fun complete(request: Request, id: Long, response: String): Boolean {
        check(inFlight === request) { "回执不属于当前 browser 领件" }
        completed = Completed(id, request.raw, response)
        return try {
            val properties = Properties().apply {
                setProperty("id", id.toString())
                setProperty("request", request.raw)
                setProperty("response", response)
            }
            val journal = ByteArrayOutputStream().use { bytes ->
                properties.store(bytes, "browser completed request")
                bytes.toByteArray()
            }
            AtomicMailboxFiles.publish(receipt, journal)
            AtomicMailboxFiles.publishText(result, response)
            // 领件是私有槽；即使外部意外替换它，也只删除仍匹配本次内容的请求。
            check(processing.readText() == request.raw) { "browser 领件已改变，保留请求" }
            check(processing.delete()) { "browser 领件删除失败，保留已完成回执" }
            inFlight = null
            true
        } catch (error: Throwable) {
            report("提交 browser 回执失败（请求与结果已保留，下一轮重试）", error)
            false
        }
    }

    private fun loadReceipt(): Completed? = try {
        if (!receipt.isFile) null
        else Properties().apply { receipt.inputStream().use(::load) }.let {
            Completed(it.getProperty("id").toLong(), it.getProperty("request"), it.getProperty("response"))
        }
    } catch (error: Throwable) {
        report("读取 browser 已完成回执失败，将按中断请求恢复", error)
        null
    }
}
