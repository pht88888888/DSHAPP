package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import org.json.JSONObject
import java.nio.file.Files

/** 拥有平台兼容产物及其安装状态，不参与引擎起停决定。 */
internal class EngineCompatibilityInstaller(
    private val assets: android.content.res.AssetManager,
    private val filesDir: File,
    private val engineDir: File,
    private val builtInWebEngine: Boolean,
) {
    @Volatile
    var fetchGuardPath: File? = null
        private set
    @Volatile
    var webViewCompatApplied = false
        private set

    /**
     * 给 DSH 自带前端打兼容补丁（幂等）。
     *
     * DSH 前端按较新的 Chromium 编写，而设备上的 WebView 可能是旧版（本机是 114），
     * 缺 `Promise.withResolvers` / `AbortSignal.any` 等标准 API 时会直接抛 TypeError，
     * 界面起不来。这里在 `index.html` 的模块脚本之前插入一段 polyfill。
     *
     * 之所以改运行时目录而不是打包时改资产：引擎资产每次升级都会重新解包覆盖，
     * 放在这里可以跟着资产版本自动重打，不必重新制作 engine.bin。
     */
    fun ensureWebCompat() {
        val dist = File(engineDir, "node_modules/@deepseek-ai/dsh-web-frontend/dist")
        val indexHtml = File(dist, "index.html")
        if (!indexHtml.isFile) {
            Log.w(TAG, "未找到前端入口，跳过 WebView 兼容补丁: ${indexHtml.absolutePath}")
            return
        }

        try {
            // polyfill 脚本本身每次都覆盖写：它随 APK 升级而变，代价只有几 KB
            val polyfill = File(dist, POLYFILL_FILE)
            assets.open(POLYFILL_FILE).use { input ->
                polyfill.outputStream().use { output -> input.copyTo(output) }
            }

            val html = indexHtml.readText()
            if (html.contains(POLYFILL_TAG)) {
                return
            }

            // 必须在 type="module" 的入口脚本之前执行：模块脚本是延迟执行的，
            // 但 polyfill 要保证在那之前就已挂好。
            val anchor = """<script type="module""""
            val idx = html.indexOf(anchor)
            if (idx < 0) {
                Log.w(TAG, "前端入口结构与预期不符，跳过 WebView 兼容补丁")
                return
            }
            val patched = html.substring(0, idx) + POLYFILL_TAG + "\n    " + html.substring(idx)
            indexHtml.writeText(patched)
            Log.i(TAG, "已注入 WebView 兼容补丁: $POLYFILL_FILE")
        } catch (t: Throwable) {
            // 补丁失败不阻断启动：新版 WebView 本来就不需要它
            Log.w(TAG, "注入 WebView 兼容补丁失败: ${t.message}")
        }
    }

    /**
     * 落盘引擎侧的 HTTP header 净化垫片，返回它的绝对路径（失败返回 null）。
     *
     * ## 为什么必须有这一层
     *
     * 用户的真实报错（2026-10-25 真机截图）：
     *
     * ```
     * 本轮运行失败
     * Cannot convert argument to a ByteString because the character at index 21
     * has a value of 27185 which is greater than 255.
     * ```
     *
     * 这是 **undici 的 `Headers` 构造器**抛的：HTTP header 值必须是 ByteString
     * （每字符 ≤ 0xFF），而某个值里混进了中文（27185 = U+6A31）。用户看到的是
     * 一串英文 + 一个数字，**完全不知道该改哪里**，而且消息直接发不出去。
     *
     * 排查结论：**全链路只有两处**会把 header 交给 undici —— 引擎自己的
     * `new Headers(...)`，以及各家 SDK 内部的 `fetch`。两处最终都落在同一个
     * 全局对象上，所以在引擎进程里兜一层，等于覆盖了**所有**调用方，
     * 包括未来引擎升级后新增的那些。
     *
     * ## 为什么用 `--import` 而不是改引擎源码
     *
     * 本类里已有 `patchLlmPiAiToolCallId` 那种**直接改引擎文件**的先例，但那种
     * 做法依赖源码锚点：引擎一升级、结构一变，补丁就静默跳过（那里确实会打日志
     * 然后 return）。`--import` 是 Node 的**官方预加载机制**，只依赖
     * 「进程启动时要加载这个模块」，不依赖任何引擎内部结构，抗升级。
     *
     * 每次都覆盖写：模块本身只有几 KB，跟着 APK 升级自动生效，不需要版本戳
     * （与 [ensureWebCompat] 对 polyfill 的处理同理）。
     */
    fun ensureFetchGuard(): File? {
        return try {
            val target = File(filesDir, FETCH_GUARD_FILE)
            val temp = File(filesDir, "$FETCH_GUARD_FILE.tmp")

            // ⚠️ **必须先写临时文件再原子 rename**，不能直接往 target 上覆盖写。
            //
            // 这个文件是 `--import` 的加载目标，而 Node 对预加载模块的**语法错误**
            // 是解析阶段就炸的（实测：文件里混进一行非法语法 ⇒
            // `SyntaxError: Unexpected identifier` ⇒ 进程根本不启动），
            // 垫片内部的 try/catch **救不了**（它在模块求值之后才生效）。
            //
            // 直接覆盖写的话，只要某次写入中途失败（磁盘满、进程被杀、电量断），
            // target 就永久停在「半个文件」上，之后**每次启动引擎都起不来**。
            // 先写 .tmp 再 rename，target 要么是旧的完整版、要么是新的完整版，
            // 不存在中间态。
            assets.open(FETCH_GUARD_ASSET).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            // 落盘后自检：内容必须与资产一致，且看起来像一份完整的 ES 模块。
            // 不一致就放弃替换，继续用上一次的（或干脆不启用）。
            val expected = assets.open(FETCH_GUARD_ASSET).use { it.readBytes() }
            val actual = temp.readBytes()
            if (!expected.contentEquals(actual)) {
                Log.w(TAG, "净化垫片写入不完整（${actual.size}/${expected.size} 字节），放弃替换")
                temp.delete()
                return fetchGuardPath?.takeIf { it.isFile }
            }
            if (!actual.toString(Charsets.UTF_8).contains("export function install()")) {
                Log.w(TAG, "净化垫片内容异常（缺少 install 导出），放弃替换")
                temp.delete()
                return fetchGuardPath?.takeIf { it.isFile }
            }

            if (!temp.renameTo(target)) {
                // rename 失败时退回覆盖写（极罕见：同目录 rename 一般不会失败）
                Log.w(TAG, "净化垫片 rename 失败，退回直接覆盖写")
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setReadable(true, false)
            fetchGuardPath = target
            Log.i(TAG, "已解出 HTTP header 净化垫片 ${target.name} (${target.length()} 字节)")
            target
        } catch (t: Throwable) {
            // 垫片缺失不阻断启动：它只影响「配置里混进中文」这一种异常场景
            fetchGuardPath = null
            Log.w(TAG, "解出 HTTP header 净化垫片失败: ${t.message}")
            null
        }
    }

    /** 兼容包只覆盖引擎 dist/engine；出厂和市场插件由各自构建保证语法兼容。 */
    /**
     * @param webViewMajor 宿主探测到的内核主版本，0 = 未知。
     *
     * 内核已经够新（≥ [OFFICIAL_MIN_WEBVIEW_MAJOR]）时**整段跳过**：官方 bundle 本来
     * 就能被解析，降级产物只会让界面跑旧语法 + polyfill。跳过省掉的是真机上实测
     * 1.5 秒左右的开销 —— 正是「全量 sha256(engine.bin) + 把 7.5 MB 的 zip 整个
     * 解压两遍（一遍找 manifest、一遍找 stamp）」，而这两步在每次冷启动都做一遍。
     */
    fun ensureWebViewCompat(webViewMajor: Int = 0) {
        if (builtInWebEngine) return
        val dist = File(engineDir, "node_modules/@deepseek-ai/dsh-web-frontend/dist")
        if (!dist.isDirectory) return
        if (webViewMajor >= OFFICIAL_MIN_WEBVIEW_MAJOR) {
            webViewCompatApplied = false
            if (File(dist, WEBVIEW_COMPAT_STAMP).isFile) {
                // 曾经为旧内核装过降级产物：内核升级后它还在 dist 里，需要一次重解包才换回官方件。
                Log.w(TAG, "内核已升级到 $webViewMajor，但 dist 里仍是降级前端（重解包 engine 可换回官方件）")
            }
            Log.i(TAG, "内核 $webViewMajor ≥ $OFFICIAL_MIN_WEBVIEW_MAJOR，跳过降级前端（省下 engine.bin 全量摘要与 zip 解压）")
            return
        }
        try {
            var manifest: JSONObject? = null
            var stamp = ""
            java.util.zip.ZipInputStream(assets.open(WEBVIEW_COMPAT_ASSET).buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        ".compat-manifest.json" -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        WEBVIEW_COMPAT_STAMP_ENTRY -> stamp = zip.readBytes().toString(Charsets.UTF_8).trim()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val metadata = requireNotNull(manifest) { "compat manifest 缺失" }
            check(metadata.getInt("schemaVersion") == 1 && metadata.getString("target") == "chrome73") { "compat manifest 不兼容" }
            check(assets.open(ASSET_NAME).use { sha256(it) } == metadata.getString("engineDigest")) { "compat 来源 engine.bin 不一致" }
            check(assets.open(POLYFILL_FILE).use { sha256(it) } == metadata.getString("polyfillDigest")) { "compat polyfill 来源不一致" }
            check(stamp.isNotEmpty()) { "compat stamp 缺失" }
            val hashes = metadata.getJSONObject("files")
            val outputs = LinkedHashMap<String, File>()
            for (name in hashes.keys()) {
                val root = when (name.substringBefore('/')) {
                    COMPAT_PREFIX_DIST -> dist
                    COMPAT_PREFIX_ENGINE -> File(engineDir, "node_modules/@deepseek-ai")
                    else -> error("compat 禁止覆盖该前缀: $name")
                }
                val output = File(root, name.substringAfter('/'))
                check(output.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "compat 路径越界: $name" }
                outputs[name] = output
            }
            val installedStamp = File(dist, WEBVIEW_COMPAT_STAMP)
            if (installedStamp.isFile && installedStamp.readText().trim() == stamp &&
                outputs.all { (name, file) -> file.isFile && file.inputStream().use { sha256(it) } == hashes.getString(name) }) {
                webViewCompatApplied = true
                return
            }
            val written = HashSet<String>()
            java.util.zip.ZipInputStream(assets.open(WEBVIEW_COMPAT_ASSET).buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name != WEBVIEW_COMPAT_STAMP_ENTRY && entry.name != ".compat-manifest.json") {
                        val output = requireNotNull(outputs[entry.name]) { "compat 未登记条目: ${entry.name}" }
                        check(written.add(entry.name)) { "compat 重复条目: ${entry.name}" }
                        output.parentFile?.mkdirs()
                        val temporary = File(output.parentFile, output.name + ".compat-tmp")
                        try {
                            temporary.outputStream().use { zip.copyTo(it) }
                            check(temporary.inputStream().use { sha256(it) } == hashes.getString(entry.name)) { "compat 摘要不符: ${entry.name}" }
                            Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        } finally { temporary.delete() }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            check(written.size == outputs.size) { "compat 文件不完整: ${written.size}/${outputs.size}" }
            installedStamp.writeText(stamp)
            webViewCompatApplied = true
            Log.i(TAG, "已应用 WebView 降级前端: ${written.size} 个文件（$stamp）")
        } catch (error: Exception) {
            webViewCompatApplied = false
            Log.w(TAG, "应用 WebView 降级前端失败: ${error.message}")
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 把 link()/linkat() 的语义转译层解到真实文件路径（幂等），返回其路径。
     *
     * 为什么需要：SELinux 的 `untrusted_app(_27)` 域对 `app_data_file` **没有
     * `link` 权限**（AOSP 为防沙箱数据外泄主动移除），所以 node 进程里任何
     * `link()` 都返回 EACCES。而 DSH 上游用硬链接做独占原子发布，并靠 EEXIST
     * 判定「已被别人发布」：
     *
     * ```
     * try { await link(staged, currentPath); }
     * catch (e) { if (e.code === 'EEXIST') return false; throw e; }  // ← EACCES 走这里
     * ```
     *
     * 表现就是每轮回复都以「本轮运行失败 EACCES: permission denied, link」告终。
     *
     * 策略改不了，但**不需要让 link 成功，只需要让调用方不依赖它**：在 libc 边界
     * 把 link 转译成「复制 + renameat2(RENAME_NOREPLACE) 发布」，同时保住独占
     * （目标存在→EEXIST）与保源（真 link 不消耗源文件）两个语义。详见
     * `app/src/main/jni/linkcompat.c` 的头部注释。
     *
     * 为什么解到文件而不是走 jniLibs：`LD_PRELOAD` 只接受真实文件路径，而
     * AGP 默认 `extractNativeLibs=false` 时 `nativeLibraryDir` 是空目录。本工程
     * 虽设了 `extractNativeLibs=true`，但资产化能避免 AGP 对 native 库做 strip /
     * 改名，也与 `engine.bin` / `node-runtime.bin` 的处理方式一致。
     */
    fun ensureLinkCompat(): File? {
        val dest = File(filesDir, LINKCOMPAT_FILE)
        return try {
            // 每次都覆盖写：库只有 10KB，而它随 APK 升级而变，比对哈希不值当
            assets.open(LINKCOMPAT_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            Log.i(TAG, "已解出 link 兼容层 $LINKCOMPAT_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "解出 link 兼容层失败: ${t.message}")
            null
        }
    }

    /**
     * 清理旧版本号池注入库，恒返回 null。
     * 号池已迁移到服务端网关；当前 APK 不再提供或加载 keyinject 资产。
     */
    fun ensureKeyInject(): File? {
        // ⚠️ 号池改为服务端网关后，本方法**不再解出注入库**，恒返回 null。
        //
        // 保留这个函数（而不是删掉调用点）有两个原因：
        //   1. 调用点有三处（start / rescue / LD_PRELOAD 拼接），删函数要连带
        //      改多处控制流，风险大于收益；
        //   2. 老安装的 `filesDir/libkeyinject.so` 需要顺手清掉 —— 留着它虽然
        //      不会再被 LD_PRELOAD 加载，但会让人误以为注入层还在。
        //
        // 真正删掉的是：assets/keyinject.bin（不再打进 APK）、
        // app/src/main/jni/keyinject.c、tools/build-keyinject.ps1 的产物链路。
        File(filesDir, KEYINJECT_FILE).takeIf { it.isFile }?.let {
            if (it.delete()) Log.i(TAG, "已删除旧的号池注入库: ${it.name}")
        }
        return null
    }
}
