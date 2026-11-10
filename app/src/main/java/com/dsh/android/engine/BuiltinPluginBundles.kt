package com.dsh.android.engine

import android.content.Context
import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal data class BuiltinPluginSpec(
    val id: String,
    val packageName: String,
    val asset: String,
    val legacyDir: String? = null,
    val legacyPatch: String? = null,
    val legacyMarker: String? = null,
    val preferenceId: String = id,
    val entryId: String = packageName,
)

/** 出厂包位于 profile workspace 之外；用户的 registry 依赖和开关始终优先。 */
internal class BuiltinPluginBundles(
    private val context: Context,
    private val engineDir: File,
    private val home: File,
    private val node: File,
    private val prefix: File,
) {
    private val stateDir = File(home, ".builtin-plugins")

    fun initializeProfile(profile: String): File? = try {
        runScript("init", profile)
        File(home, "profiles/$profile").takeIf { File(it, "package.json").isFile }
    } catch (error: Exception) {
        Log.w(TAG, "初始化 $profile profile 失败: ${error.message}")
        null
    }

    fun ensure(): Boolean = try {
        stateDir.mkdirs()
        val output = File(stateDir, "plan.json")
        runScript("plan", PROFILE_NAME, output)
        val plan = JSONArray(output.readText())
        for (index in 0 until plan.length()) {
            val item = plan.getJSONObject(index)
            try {
                install(item)
            } catch (error: Exception) {
                // 安装失败不登记半成品；已完整安装的旧版本仍可被本次注册采用。
                Log.w(TAG, "安装出厂 bundle ${item.getString("packageName")} 失败: ${error.message}")
                val old = File(item.getString("directory"))
                check(File(old, "package.json").isFile && File(old, "cordis.patch.yml").isFile) {
                    "出厂 bundle 缺失且无法安装: ${item.getString("packageName")}: ${error.message}"
                }
            }
        }
        runScript("register", PROFILE_NAME)
        true
    } catch (error: Exception) {
        Log.w(TAG, "内置 bundle 登记失败: ${error.message}")
        false
    }

    private fun runScript(phase: String, profile: String, output: File? = null) {
        stateDir.mkdirs()
        val request = File(stateDir, "request.json")
        val plugins = JSONArray()
        for (spec in BUILTIN_PLUGIN_BUNDLES) {
            plugins.put(JSONObject().apply {
                put("id", spec.id)
                put("packageName", spec.packageName)
                put("entryId", spec.entryId)
                put("legacyPatch", spec.legacyPatch)
                put("legacyMarker", spec.legacyMarker)
                put("enabled", EngineManager.isBuiltinPluginEnabled(context, spec.preferenceId))
            })
        }
        request.writeText(JSONObject().apply {
            put("engineDir", engineDir.absolutePath)
            put("home", home.absolutePath)
            put("profile", profile)
            put("phase", phase)
            put("plugins", plugins)
            output?.let { put("output", it.absolutePath) }
        }.toString())
        val process = ProcessBuilder(node.absolutePath, "--max-old-space-size=256", "-e", BuiltinPluginBootstrap.script, request.absolutePath)
            .directory(engineDir)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("PREFIX", prefix.absolutePath)
                    put("HOME", home.parentFile!!.absolutePath)
                    put("DSH_HOME", home.absolutePath)
                    put("PATH", "${prefix.absolutePath}/bin:/system/bin")
                    remove("NODE_OPTIONS")
                }
            }.start()
        val diagnostics = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine {
                    if (diagnostics.length < 16_384) diagnostics.appendLine(it)
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            throw java.io.IOException("bundle bootstrap $phase 超时")
        }
        reader.join(1000)
        check(process.exitValue() == 0) { "bundle bootstrap $phase: $diagnostics" }
    }

    private fun install(item: JSONObject) {
        val id = item.getString("id")
        val name = item.getString("packageName")
        val spec = BUILTIN_PLUGIN_BUNDLES.single { it.id == id && it.packageName == name }
        val target = File(home, "node_modules/$name")
        check(target.absolutePath == item.getString("directory")) { "bundle 目标不一致" }
        val metadata = context.assets.open("$id.manifest.json").use { JSONObject(it.bufferedReader().readText()) }
        check(metadata.getString("id") == id && metadata.getString("package") == name && metadata.getString("asset") == spec.asset) { "bundle manifest 身份不一致: $id" }
        val revision = metadata.getString("revision")
        val stamp = File(target, ".builtin-revision")
        val backup = File(target.parentFile, ".$name.builtin-backup")
        val staging = File(target.parentFile, ".$name.builtin-staging")
        val base = File(home, "node_modules").canonicalFile
        for (directory in listOf(target, backup, staging)) {
            check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == base) { "出厂 bundle 路径越界或为用户链接: ${directory.name}" }
        }
        // 上次进程在换目录的窗口退出：先恢复完整旧包，再决定是否需要新包。
        if (!target.exists() && backup.isDirectory) Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        if (stamp.isFile && stamp.readText().trim() == revision && File(target, "cordis.patch.yml").isFile) return
        // 这两个目录名固定在已校验的 node_modules 内，不接受来自 zip 的路径。
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val zipHash = context.assets.open(spec.asset).use { digest(it) }
            check(zipHash == metadata.getString("assetSha256")) { "bundle zip SHA256 不符: $id" }
            ZipInputStream(context.assets.open(spec.asset).buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(staging, entry.name)
                    check(out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "bundle zip 路径越界: ${entry.name}" }
                    if (!entry.isDirectory) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val files = metadata.getJSONArray("files")
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val extracted = File(staging, file.getString("path"))
                check(extracted.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "bundle manifest 路径越界" }
                check(extracted.isFile && extracted.length() == file.getLong("size") && extracted.inputStream().use { digest(it) } == file.getString("sha256")) { "bundle 文件不完整: $id/${file.getString("path")}" }
            }
            val pkg = JSONObject(File(staging, "package.json").readText())
            check(pkg.getString("name") == name && pkg.getJSONObject("dsh").getJSONObject("android").getString("builtinId") == id) { "bundle 包身份不一致" }
            val patch = pkg.getJSONObject("dsh").getJSONObject("bundle").getString("patch")
            val patchFile = File(staging, patch)
            check(patchFile.canonicalPath.startsWith(staging.canonicalPath + File.separator) && patchFile.isFile) { "bundle patch 缺失" }
            File(staging, ".builtin-revision").writeText(revision)
            backup.deleteRecursively()
            if (target.exists()) Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (error: Exception) {
                if (backup.isDirectory && !target.exists()) Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw error
            }
            backup.deleteRecursively()
            Log.i(TAG, "出厂 bundle 已就位: $name ${metadata.getString("version")} ($revision)")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(8192)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
