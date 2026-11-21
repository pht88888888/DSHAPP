package com.dsh.android.repair

import android.content.Context
import com.dsh.android.engine.EngineLaunchExtension
import java.io.File

/** APK-owned additive preset; never replaces the original profile or user configuration. */
internal object RepairAssistantAssets {
    fun prepare(context: Context, engine: File): EngineLaunchExtension {
        val assets = File(context.filesDir, "repair-addon").apply { check(mkdirs() || isDirectory) }.canonicalFile
        fun copy(path: String, destination: File) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
                check(!java.nio.file.Files.isSymbolicLink(destination.toPath()))
                context.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
            } else {
                check(destination.mkdirs() || destination.isDirectory)
                for (child in children) {
                    check(child != "." && child != ".." && !child.contains('/'))
                    copy("$path/$child", File(destination, child))
                }
            }
        }
        copy("repair-agent", assets)
        // rc.5 anchors inserted Host entry names, but not nested preset plugins.
        // Materialize this APK-owned row as a literal name before the patch loader
        // parses it; !!js is not accepted for a plugin's name field. Do not modify
        // the original profile or any user-owned configuration.
        val patch = File(assets, "repair.patch.yml")
        val toolsMarker = "name: ./tools/index.js"
        val template = patch.readText(Charsets.UTF_8)
        check(template.split(toolsMarker).size == 2) { "Unexpected repair tools patch marker" }
        val toolsModule = File(assets, "tools/index.js").canonicalPath
        patch.writeText(template.replace(toolsMarker, "name: ${org.json.JSONObject.quote(toolsModule)}"), Charsets.UTF_8)
        val mailboxes = File(context.filesDir, "repair-mailboxes").apply { check(mkdirs() || isDirectory) }.canonicalFile
        val mailbox = File(mailboxes, "current").apply { check(mkdirs() || isDirectory) }
        for (name in listOf("request", "response")) check(File(mailbox, name).mkdirs() || File(mailbox, name).isDirectory)
        return EngineLaunchExtension(
            patches = listOf(File(assets, "repair.patch.yml")),
            environment = mapOf(
                "DSH_REPAIR_ENGINE_PACKAGE" to File(engine, "node_modules/@deepseek-ai/dsh/package.json").absolutePath,
                "DSH_REPAIR_TOOLS_MODULE" to File(assets, "tools/index.js").absolutePath,
                "DSH_REPAIR_ASSETS" to assets.absolutePath,
                "DSH_REPAIR_MAILBOX" to mailbox.absolutePath,
                "DSH_REPAIR_CONTROL_FILE" to File(mailboxes, "control-request.json").absolutePath,
            ),
        )
    }
}
