package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 引擎源码文件补丁；不拥有 Android、引擎启动或日志状态。 */
internal object EngineFilePatches {
    enum class Status { APPLIED, ALREADY, MISSING, MISMATCH, FAILURE }
    data class Result(val id: String, val status: Status, val targets: List<String>, val detail: String)
    data class Edit(val before: String, val after: String, val count: Int = 1)
    data class Target(val path: String, val edits: List<Edit>, val prefix: String? = null)
    data class Spec(val id: String, val targets: List<Target>, val imageMime: Boolean = false)

    private fun module(name: String) = "node_modules/@deepseek-ai/$name/lib/index.js"
    private fun one(id: String, file: String, vararg edits: Edit) = Spec(id, listOf(Target(file, edits.toList())))

    val registry: List<Spec> = listOf(
        one("flock-platform", "node_modules/@deepseek-ai/node-addon-system/lib/flock.js", Edit(FLOCK_PLATFORM_GUARD, FLOCK_ANDROID_ANCHOR)),
        Spec("process-inspector", listOf(Target("node_modules/@deepseek-ai/dsh-subprocess-local/lib", listOf(Edit(INSPECTOR_GUARD, INSPECTOR_ANDROID_ANCHOR)), "runner-launch-"))),
        one("terminal-shell", module("dsh-terminal-bash"), Edit(TERMINAL_SHELL_GUARD, TERMINAL_SHELL_ANCHOR)),
        one("attachment-durability", module("dsh-attachment-local"), Edit(ATTACH_SYNC_GUARD, ATTACH_SYNC_ANCHOR), Edit(ATTACH_FSYNC_GUARD, ATTACH_FSYNC_ANCHOR)),
        Spec("image-mime", listOf(Target("node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js", listOf(Edit(IMG_TYPE_GUARD, IMG_TYPE_ANCHOR)))), imageMime = true),
        Spec("fail-loud", listOf(
            Target(module("dsh-app-boot"), listOf(Edit(FAIL_LOUD_EXIT_GUARD, FAIL_LOUD_ANDROID_ANCHOR, 2))),
            Target("node_modules/@deepseek-ai/dsh/lib", listOf(Edit(FAIL_LOUD_RELEASE_GUARD, FAIL_LOUD_RELEASE_ANCHOR)), "profile-boot-"),
        )),
        one("deepseek-tool-order", module("dsh-llm-deepseek"), Edit(TOOL_ORDER_GUARD_HEAD, TOOL_ORDER_ANCHOR_HEAD), Edit(TOOL_ORDER_GUARD_TAIL, TOOL_ORDER_ANCHOR_TAIL)),
        one("pi-ai-tool-id", module("dsh-llm-pi-ai"), Edit(PI_AI_TOOLID_GUARD_START, PI_AI_TOOLID_ANCHOR_START), Edit(PI_AI_TOOLID_GUARD_END, PI_AI_TOOLID_ANCHOR_END)),
        one("llm-tool-id", module("dsh-llm"), Edit(LLM_TOOLID_GUARD, LLM_TOOLID_ANCHOR)),
        one("directory-picker-restore", module("dsh-host-directory-picker-browse"), Edit(PICKER_START_ANCHOR, PICKER_START_GUARD)),
        one("preset-cordis-persona", PRESET_CORDIS_PRESET_PATH, Edit(PRESET_CORDIS_PERSONA_GUARD, PRESET_CORDIS_PERSONA_PATCH)),
    )

    fun apply(engineDir: File, id: String): Result = apply(engineDir, registry.single { it.id == id })

    /** 先对整组文件生成替换计划；任意锚点失配时整组不落盘。每个文件原子替换。 */
    fun apply(engineDir: File, spec: Spec): Result {
        val plans = linkedMapOf<File, String>()
        val changed = mutableListOf<String>()
        try {
            for (target in spec.targets) {
                val base = File(engineDir, target.path)
                val candidates = if (target.prefix == null) listOf(base).filter { it.isFile } else {
                    base.listFiles { file -> file.isFile && file.name.startsWith(target.prefix) && file.name.endsWith(".js") }?.toList().orEmpty()
                }
                if (candidates.isEmpty()) return Result(spec.id, Status.MISSING, listOf(target.path), "target file is missing")
                val sources = candidates.associateWith { it.readText() }
                val matching = if (target.prefix == null) candidates else sources.filterValues { source ->
                    target.edits.any { source.contains(it.before) || source.contains(it.after) }
                }.keys.toList()
                if (matching.size != 1) return Result(spec.id, Status.MISMATCH, candidates.map { it.relativeTo(engineDir).path }, "expected one matching chunk, found ${matching.size}")
                val file = matching.single()
                val source = sources.getValue(file)
                val patched = if (spec.imageMime) image(source, target.edits.single()) else literal(source, target.edits)
                if (patched == null) return Result(spec.id, Status.MISMATCH, listOf(file.relativeTo(engineDir).path), "anchor count or applied body differs")
                plans[file] = patched
            }
            for ((file, patched) in plans) {
                if (file.readText() == patched) continue
                val temporary = Files.createTempFile(checkNotNull(file.parentFile).toPath(), ".engine-patch-", ".tmp")
                try {
                    Files.write(temporary, patched.toByteArray(Charsets.UTF_8))
                    Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
                changed += file.relativeTo(engineDir).path
            }
            return Result(spec.id, if (changed.isEmpty()) Status.ALREADY else Status.APPLIED, plans.keys.map { it.relativeTo(engineDir).path }, if (changed.isEmpty()) "complete applied bodies present" else "updated ${changed.size} file(s)")
        } catch (error: Exception) {
            return Result(spec.id, Status.FAILURE, changed, "${error.javaClass.simpleName}: ${error.message}; changed files=${changed.size}")
        }
    }

    private fun hits(source: String, part: String): Int {
        var next = 0
        var count = 0
        while (true) {
            val index = source.indexOf(part, next)
            if (index < 0) return count
            count++
            next = index + part.length
        }
    }

    private fun literal(source: String, edits: List<Edit>): String? {
        var patched = source
        for (edit in edits) {
            // before 可能是 after 子串；先移除完整 after 再数未应用的原文。
            val applied = hits(patched, edit.after)
            val remaining = hits(patched.replace(edit.after, ""), edit.before)
            if (applied == edit.count && remaining == 0) continue
            if (applied != 0 || remaining != edit.count) return null
            patched = patched.replace(edit.before, edit.after)
        }
        return patched
    }

    private fun image(source: String, edit: Edit): String? {
        val lines = source.split('\n').toMutableList()
        val body = edit.after.split('\n')
        val applied = lines.indices.filter { start ->
            val indent = lines[start].takeWhile { it == ' ' || it == '\t' }
            start + body.size <= lines.size && body.indices.all { offset -> lines[start + offset] == indent + body[offset] }
        }
        if (applied.size == 1) return source
        if (applied.isNotEmpty() || source.contains("return value.startsWith(\"image/\")")) return null
        val exact = lines.indices.filter { lines[it].trimEnd().endsWith(edit.before.trimEnd()) }
        val matching = if (exact.isNotEmpty()) exact else lines.indices.filter {
            lines[it].contains("return") && lines[it].contains("\"image/png\"") && lines[it].contains("\"image/gif\"")
        }
        if (matching.size != 1) return null
        val index = matching.single()
        val indent = lines[index].takeWhile { it == ' ' || it == '\t' }
        lines[index] = body.joinToString("\n") { indent + it }
        return lines.joinToString("\n")
    }
}
