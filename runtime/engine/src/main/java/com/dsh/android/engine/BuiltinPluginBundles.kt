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

    /**
     * 建 profile（幂等）。
     *
     * ## 为什么要按「引擎版本戳 + profile 名」短路
     *
     * 这一步是**再起一个 node 进程**跑 `initProfile()`，真机冷启动实测 0.6~0.7 秒；
     * 而它真正会做的事只有一件 —— 按**本次 APK 那份**官方引擎的模板补 profile 清单
     * （`profiles/<name>/package.json` 里的 `dsh.profile.bundles`）。引擎没换，补出来的
     * 结果与上次一模一样，于是每次都白烧一次进程启动。
     *
     * 短路条件（缺一不可）：profile 的 `package.json` 在盘上、且记账里的
     * 「引擎版本戳 + profile 名」与本次一致。引擎一升级（`.stamp` 变）就照旧真跑。
     */
    fun initializeProfile(profile: String): File? = try {
        val dir = File(home, "profiles/$profile")
        val marker = File(stateDir, "init-$profile.stamp")
        val key = "$profile@${engineStamp()}"
        val fresh = dir.isDirectory && File(dir, "package.json").isFile &&
            runCatching { marker.readText().trim() }.getOrNull() == key
        if (!fresh) {
            runScript("init", profile)
            runCatching {
                stateDir.mkdirs()
                marker.writeText(key)
            }
        }
        dir.takeIf { File(it, "package.json").isFile }
    } catch (error: Exception) {
        Log.w(TAG, "初始化 $profile profile 失败: ${error.message}")
        null
    }

    /**
     * 装好并登记出厂 bundle（幂等）。
     *
     * ## 为什么也做「输入未变就不重复跑」的短路
     *
     * 这一步在真机上要起**两个** node 进程（`plan` 与 `register`），实测合计
     * 0.6~0.8 秒。而它的输入只有三样：本次 APK 的引擎（`.stamp`）、用户对
     * 14 个组件的开关状态、以及 profile 层的两个文件（`package.json` /
     * `cordis.patch.yml`）。三者都没变时，跑出来的结果必然与上次逐字节相同。
     *
     * 记账里的 key 是**跑完之后**算的（见 [ensureKey]）：这样用户在界面里关掉一个
     * 插件、或自己改坏了 patch 文件，下次启动的 `key` 与记账不相等，登记动作照旧
     * 真跑一遍 —— 缓存只吃掉「什么都没变」的那部分启动。
     */
    fun ensure(): Boolean {
        try {
            ensureLocked()
            return true
        } catch (error: Exception) {
            Log.w(TAG, "内置 bundle 登记失败: ${error.message}")
            return false
        }
    }

    private fun ensureLocked() {
        stateDir.mkdirs()
        val marker = File(stateDir, "ensure.stamp")
        if (marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == ensureKey()) return
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
        runCatching { marker.writeText(ensureKey()) }
    }

    /** 引擎版本戳：APK 里那份引擎的标识，换了引擎就必须重跑全部登记动作。 */
    private fun engineStamp(): String =
        runCatching { File(engineDir, ".stamp").readText().trim() }.getOrDefault("-")

    /**
     * 登记动作的输入指纹（见 [ensure]）。
     *
     * 覆盖：引擎版本戳、14 个组件的开关状态、profile 的两个配置文件内容。
     * 任何一项变了都会让缓存失效 —— 宁可多重跑一次登记，也不要漏掉用户的开关。
     */
    private fun ensureKey(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun feed(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        feed("v1")
        feed(engineStamp())
        for (spec in BUILTIN_PLUGIN_BUNDLES) {
            feed("${spec.id}=${EngineManager.isBuiltinPluginEnabled(context, spec.preferenceId)}")
        }
        for (name in listOf("package.json", "cordis.patch.yml")) {
            val file = File(home, "profiles/$PROFILE_NAME/$name")
            feed("$name:${file.length()}@${file.lastModified()}")
            feed(runCatching { file.readText() }.getOrDefault(""))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun runScript(phase: String, profile: String, output: File? = null) {
        stateDir.mkdirs()
        val request = File(stateDir, "request.json")
        fun describe(spec: BuiltinPluginSpec) = JSONObject().apply {
            put("id", spec.id)
            put("packageName", spec.packageName)
            put("entryId", spec.entryId)
            put("legacyDir", spec.legacyDir)
            put("legacyPatch", spec.legacyPatch)
            put("legacyMarker", spec.legacyMarker)
            put("preferenceId", spec.preferenceId)
            put("enabled", EngineManager.isBuiltinPluginEnabled(context, spec.preferenceId))
        }
        val plugins = JSONArray()
        for (spec in BUILTIN_PLUGIN_BUNDLES) plugins.put(describe(spec))
        val legacyComponents = JSONArray()
        for (spec in LEGACY_ANDROID_PLUGIN_COMPONENTS) legacyComponents.put(describe(spec))
        request.writeText(JSONObject().apply {
            put("engineDir", engineDir.absolutePath)
            put("home", home.absolutePath)
            put("profile", profile)
            put("phase", phase)
            put("plugins", plugins)
            put("legacyComponents", legacyComponents)
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
