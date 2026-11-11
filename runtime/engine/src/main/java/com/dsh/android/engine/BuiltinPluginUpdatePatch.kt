package com.dsh.android.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 定向扩展随 APK 出厂的本地 bundle；不改变官方 registry 更新和安装器。 */
internal object BuiltinPluginUpdatePatch {
    private const val MARKER = "// DSH_ANDROID_BUILTIN_UPDATE_V1 "

    /** 白名单来自 BUILTIN_PLUGIN_BUNDLES（组件 id -> 包名）；锚点不匹配时抛错，由启动层报告。 */
    fun apply(
        engineDir: File,
        dshHome: File,
        builtinPackages: Map<String, String>,
        log: (String) -> Unit,
    ): Boolean {
        require(builtinPackages.isNotEmpty()) { "Builtin plugin update whitelist is empty" }
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js")
        check(entry.isFile) { "Builtin plugin update patch: official plugin-manager index.js is missing" }
        val config = "{\"home\":${jsString(dshHome.absolutePath)},\"packages\":{" +
            builtinPackages.toSortedMap().entries.joinToString(",") { (id, name) ->
                require(id.matches(Regex("[a-z0-9-]+")) &&
                    name.matches(Regex("(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*"))) {
                    "Invalid builtin component: $id -> $name"
                }
                "${jsString(id)}:${jsString(name)}"
            } + "}}"
        val original = entry.readText()
        val edits = patches(config)
        var patched = original
        if (original.contains(MARKER)) {
            val marker = uniqueIndex(original, MARKER)
            val end = original.indexOf('\n', marker)
            check(end > marker) { "Builtin plugin update patch: incomplete config marker" }
            val oldConfig = original.substring(marker + MARKER.length, end)
            val oldEdits = patches(oldConfig)
            for ((_, replacement) in oldEdits) uniqueIndex(original, replacement)
            if (oldConfig == config) return false
            val oldHeader = oldEdits.first().second
            patched = patched.replaceRange(
                uniqueIndex(patched, oldHeader),
                uniqueIndex(patched, oldHeader) + oldHeader.length,
                edits.first().second,
            )
        } else {
            for ((anchor, replacement) in edits) {
                val first = uniqueIndex(patched, anchor)
                patched = patched.replaceRange(first, first + anchor.length, replacement)
            }
        }
        val temporary = Files.createTempFile(checkNotNull(entry.parentFile).toPath(), ".builtin-update-", ".js")
        try {
            Files.write(temporary, patched.toByteArray(Charsets.UTF_8))
            Files.move(temporary, entry.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        log("已适配官方 plugin-manager 的可信出厂 bundle 更新（${builtinPackages.size} 个组件）")
        return true
    }

    private fun uniqueIndex(source: String, anchor: String): Int {
        val first = source.indexOf(anchor)
        check(first >= 0 && source.indexOf(anchor, first + anchor.length) < 0) {
            "Builtin plugin update patch: expected one official anchor: ${anchor.lineSequence().first()}"
        }
        return first
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        for (char in value) when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (char.code < 32 || char == '\u2028' || char == '\u2029') {
                append("\\u" + char.code.toString(16).padStart(4, '0'))
            } else append(char)
        }
        append('"')
    }

    private fun patches(config: String): List<Pair<String, String>> = listOf(
        "function updateRefusal(bundle, name) {" to """
// DSH_ANDROID_BUILTIN_UPDATE_V1 ${'$'}config
const dshAndroidBuiltinUpdate = ${'$'}config;
function dshAndroidBuiltinLink(bundle, profile) {
    if (profile === void 0 || typeof bundle.source !== "string" || !bundle.source.startsWith("link:")) return void 0;
    try {
        const expected = join(dshAndroidBuiltinUpdate.home, "node_modules", bundle.name);
        if (bundle.source !== `link:${'$'}{expected}`) return void 0;
        const manifest = readProfileManifest("dsh", profile.dir);
        if (manifest.dependencies?.[bundle.name] !== bundle.source) return void 0;
        const actual = realpathSync(expected);
        if (actual !== join(realpathSync(dshAndroidBuiltinUpdate.home), "node_modules", bundle.name)) return void 0;
        if (realpathSync(resolveBundleDir("dsh", bundle.name, profile.installAnchor, profile.dir)) !== actual) return void 0;
        const info = JSON.parse(readFileSync(join(actual, "package.json"), "utf8"));
        const id = info.dsh?.android?.builtinId;
        if (typeof id !== "string" || !Object.hasOwn(dshAndroidBuiltinUpdate.packages, id) ||
            dshAndroidBuiltinUpdate.packages[id] !== bundle.name || info.name !== bundle.name ||
            info.version !== bundle.version || info.dsh?.bundle === void 0) return void 0;
        return bundle.source;
    } catch {
        return void 0;
    }
}
function updateRefusal(bundle, name, profile) {
""".trimIndent().replace("${'$'}config", config),
        "\tconst parsed = parsedForRegistry(bundle.source);" to """
	const builtinLink = dshAndroidBuiltinLink(bundle, profile);
	const parsed = builtinLink === void 0 ? parsedForRegistry(bundle.source) : { kind: "registry", spec: bundle.name, name: bundle.name };
""".trimIndent(),
        "\t\tinstalled,\n\t\tparsed\n\t};\n}" to "\t\tinstalled,\n\t\tparsed,\n\t\t...builtinLink === void 0 ? {} : { builtinLink }\n\t};\n}",
        "updateRefusal(bundle, bundle.name)" to "updateRefusal(bundle, bundle.name, this.profile)",
        "updateRefusal((await this.listBundles()).find((bundle) => bundle.name === name), name)" to
            "updateRefusal((await this.listBundles()).find((bundle) => bundle.name === name), name, this.profile)",
        "updateRefusal((await this.listBundles()).find((item) => item.name === name), name)" to
            "updateRefusal((await this.listBundles()).find((item) => item.name === name), name, this.profile)",
        "\t\t\tconst { enabled } = bundle;" to """
			const { enabled } = bundle;
            // A failed removal can have deselected a still-installed local link.
            const restoreBuiltin = () => {
                if (readProfileManifest("dsh", this.profile.dir).dependencies?.[name] !== target.builtinLink) {
                    return this.installBundle(target.builtinLink, { saveExact: true, enabled });
                }
                return this.change((result) => this.configure(async () => {
                    await this.selectBundle(name, enabled);
                    Object.assign(result, await this.publishAndReload(enabled ? this.bundleRows(name).map((row) => row.id) : []));
                }), { stage: "install", target: target.builtinLink, enabled }, "install");
            };
""".trimIndent(),
        "\t\t\tif (removal.application === \"failed\" || removal.application === \"cancelled\") return {\n\t\t\t\tfrom,\n\t\t\t\tto: version,\n\t\t\t\tstep: \"remove\",\n\t\t\t\tresult: removal\n\t\t\t};" to """
            if (removal.application === "failed" || removal.application === "cancelled") return {
                from,
                to: version,
                step: "remove",
                result: removal,
                ...target.builtinLink === void 0 ? {} : {
                    restore: await settled("install", target.builtinLink, restoreBuiltin())
                }
            };
""".trimIndent(),
        "\t\t\tconst previous = `${'$'}{name}@${'$'}{from}`;" to
            "\t\t\tconst previous = target.builtinLink ?? `${'$'}{name}@${'$'}{from}`;",
        "\t\t\t\trestore: await settled(\"install\", previous, this.installBundle(previous, {" to
            "\t\t\t\trestore: await settled(\"install\", previous, target.builtinLink !== void 0 ? restoreBuiltin() : this.installBundle(previous, {",
    )
}
