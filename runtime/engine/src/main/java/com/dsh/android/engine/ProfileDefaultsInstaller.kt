package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** 拥有用户配置补缺与兼容迁移；已有用户设置按原规则保留。 */
internal class ProfileDefaultsInstaller(private val dshHome: File) {

    /**
     * 删掉 `$DSH_HOME/.credentials.yaml` 里那条**老用户手填的**内置号池密钥。
     *
     * ## 症状
     *
     * 升级后界面显示「API 密钥无效」，而 API 地址已经是网关地址。设置页里密钥
     * 输入框提示「留空使用已保存凭据」，**看不到**「已检测到环境变量」。
     *
     * ## 根因
     *
     * `dsh-credentials-local` 的 `describe()` 优先级是：
     *
     * ```
     * values（.credentials.yaml）→ inherited（父进程环境变量）→ dotenv 兜底
     * ```
     *
     * 老版本要求用户在设置页里手填 Agnes 真密钥，那份值被写进 `.credentials.yaml`。
     * 新版本改用环境变量 `AGNES_CREDENTIAL`（值是网关令牌），但**文件层优先**，
     * 于是 `Authorization` 头带的仍是用户当年手填的**旧真密钥** ——
     * 打到现在指向的网关地址上，网关不认识它，回 401 ⇒ 界面「API 密钥无效」。
     *
     * 这正是「留空使用已保存凭据」与「已检测到环境变量」互斥的原因：
     * 前者说明 `source === "file"`，后者说明 `source === "env"`。老用户是前者。
     *
     * ## 为什么可以安全删除
     *
     * `AGNES_CREDENTIAL` 是**内置号池专用的引用名**（见 [KEY_ENV]），
     * 用户自己的第三方 provider 用各自的引用名，不受影响。
     * 而且网关令牌永远由 [start] 注入成环境变量 —— 文件里那条只会造成遮蔽。
     *
     * ⚠️ **只删这一条**。整份文档原样保留（注释、其它条目、格式都不动）：
     * 用户可能同时配了别的 provider，粗暴重写等于毁他数据。
     *
     * ⚠️ 只认「值不是网关令牌」的条目。若用户手动填的就是网关令牌，留着无害。
     */
    fun dropStaleBuiltinCredential() {
        val file = File(dshHome, ".credentials.yaml")
        if (!file.isFile) return
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            Log.w(TAG, "读取 .credentials.yaml 失败: ${t.message}")
            return
        }

        // 找 `AGNES_CREDENTIAL:` 那一行（顶层键，允许引号/注释/空行）。
        // 只处理顶层缩进为 0 的行，避免误伤嵌套结构。
        val lines = text.lines().toMutableList()
        val idx = lines.indexOfFirst { line ->
            val t = line.trimStart()
            line.indexOfFirst { it != ' ' } == 0 &&
                t.startsWith("$KEY_ENV:") &&
                !t.startsWith("#")
        }
        if (idx < 0) return

        val raw = lines[idx].substringAfter("$KEY_ENV:").trim()
        // 去掉行尾注释与引号，取出真正的值
        val value = raw
            .substringBefore(" #")
            .trim()
            .trim('\'', '"')
        if (value.isEmpty()) return
        if (value == KEY_PLACEHOLDER) return   // 已经是网关令牌，无需处理

        // 还有别的条目就只删这一行，否则整个文件只剩这一条 ⇒ 删文件更干净
        val remaining = lines.filterIndexed { i, line ->
            i != idx && !(line.isBlank() || line.trimStart().startsWith("#"))
        }
        try {
            if (remaining.isEmpty()) {
                if (file.delete()) Log.i(TAG, "已删除只含旧号池密钥的 .credentials.yaml")
            } else {
                val body = lines.filterIndexed { i, _ -> i != idx }.joinToString("\n")
                file.writeText(body.trimEnd('\n') + "\n")
                Log.i(TAG, "已从 .credentials.yaml 移除旧的 $KEY_ENV 条目")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "清理 .credentials.yaml 失败: ${t.message}")
        }
    }

    /**
     * 把内置号池并入 profile 的用户配置层（`profiles/web/cordis.patch.yml`）。
     *
     * **只有一个供应商**，但底下的 14 把钥匙分属两个站点。合并靠的是
     * `keyinject.c` 在 TLS 层当小路由：选中哪把钥匙，就把 Host 头改成那把钥匙
     * 所属站点的域名（两站同 IP、同一张通配证书，服务器按 Host 头路由）。
     *
     * 所以这里只要配**一个**路由，`baseURL` 取较长的国际站域名 —— 等长改写
     * 只能从长的往短的改（见 [KEY_BASE_URL]）。
     *
     * ## 为什么 `apiKeyEnv` 指向的是**占位符**
     *
     * `apiKeyEnv` 是**凭据引用**（credential reference），不是密钥本身：引擎每次
     * 请求都通过凭据 seam 把它解析成实际字符串，再放进 `Authorization` 头。所以
     * 这里只写引用名，实际值由 [start] 注入成环境变量 —— 而那个值是占位符。
     *
     * 占位符随请求进入 TLS 缓冲区后，由 `libkeyinject.so` 在加密前换成真密钥。
     * **整条链路上密钥只存在于 C 库的内存里**，这是本方案的全部要害。
     *
     * ## ⚠️ 为什么落在用户层，而不是 `--patch` overlay（踩过，就是本次要修的 bug）
     *
     * 老写法是 `- id: llm-pi-ai` 的 `--patch` overlay。patch 语义是**整块替换该行
     * 的 config**（不是深合并），而 overlay 在层序**最顶层** ⇒ 用户在 GUI 里新建的
     * provider 会被内置号池那一份 config 整个盖掉。于是
     * `@deepseek-ai/dsh-config-editor/lib/index.js` 的写入校验（它会把刚写下的值
     * 经**完整层序含 overlay**重新 compose 一遍再比对）直接抛：
     *
     * ```
     * Configuration for "llm-pi-ai" is overridden by a home patch or command-line overlay
     * ```
     *
     * 用户看到的就是「一添加第三方 API 就报错」。所以内置值必须落在**用户层** ——
     * 那正是 GUI 自己写入的那份文件，格式一致、语义一致（同 [seedDefaultSettings]）。
     *
     * ⚠️ 也不能给号池另开一行同名插件：`dsh-llm-pi-ai` 的 `apply()` 会**无条件**
     * 声明 pi-ai 内置目录的 41 个 provider，第二个实例必然撞
     * `DUPLICATE_DIRECTORY`（`configurable provider "anthropic" is already declared`）
     * ⇒ 整个目录注册失败、模型列表空掉。详见 [KEY_POOL_NS]。
     *
     * ⚠️ 与 [seedDefaultSettings] 的「只播种一次」**刻意不同**：内置号是产品能力
     * 的一部分，用户删掉它就等于弄丢了号池，所以每次启动都要确保它在。但**只补缺**
     * （见 [mergeKeyPoolProvider]），已有 `agnes:` 时一个字节都不动。
     */

    fun ensureKeyPoolProvider(profile: String = PROFILE_NAME): Boolean {
        // 老版本把内置号池写成 --patch overlay（keyinject.patch.yml）。那个写法会
        // 在最顶层整块替换 `llm-pi-ai` 那行，把用户自己的 provider 一起盖掉 ——
        // 见 [KEY_POOL_NS]。这份残留**必须无条件删掉**（与号池资产在不在无关）：
        // 它本身就是那个 bug，留着它「一加第三方 API 就报错」就还在。
        File(dshHome, KEYINJECT_PATCH).takeIf { it.isFile }?.let {
            if (it.delete()) Log.i(TAG, "已删除旧的内置号池 overlay: ${it.name}")
        }
        // ⚠️ 号池改为服务端网关后，不再依赖本地注入库，所以这里**不再**有
        // 「没有 libkeyinject.so 就跳过」的短路。旧逻辑的因果已经不存在了 ——
        // 现在只要网关可用，号池就该在（原参数 `inject: File?` 已随之删除）。

        val patch = File(dshHome, "profiles/$profile/$PROFILE_PATCH_FILENAME")
        val existing = try {
            if (patch.isFile) patch.readText() else ""
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile 用户配置失败: ${t.message}")
            return false
        }
        val updated = KeyPoolProfileConfig.mergeKeyPoolProvider(existing)
        if (updated == existing) {
            Log.i(TAG, "内置号池已在 profile 用户配置里: ${patch.absolutePath}")
            return true
        }
        return try {
            patch.parentFile?.mkdirs()
            patch.writeText(updated)
            Log.i(TAG, "已把内置号池并入 profile 用户配置: ${patch.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "写入内置号池失败: ${t.message}")
            false
        }
    }


    /**
     * 确保 profile 的用户配置层里写着「默认模型 = 内置 Agnes」（老安装补缺用）。
     *
     * [DEFAULT_SETTINGS_PATCH] 里虽然已经带了这一项，但那份只在「用户从未动过设置」
     * 时播种一次 —— 而 [ensureKeyPoolProvider] 会往同一个文件里补内置号池，于是
     * **任何装过一次的机器**都不再是空模板，播种被跳过、永远拿不到这个默认值。
     * 已发出去的 0.1.x / 0.2.0 全是这种状态，所以这一支是**必须**的。
     *
     * 判据与 [ensureKeyPoolProvider] 一致：**只补缺，不覆盖**。行已存在就一个字节
     * 都不动 —— 那意味着用户在设置页里选过模型，属于用户意图。
     */
    fun ensureDefaultModel(profile: String = PROFILE_NAME) {
        val patch = File(dshHome, "profiles/$profile/$PROFILE_PATCH_FILENAME")
        val existing = try {
            if (patch.isFile) patch.readText() else ""
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile 用户配置失败: ${t.message}")
            return
        }
        val updated = KeyPoolProfileConfig.mergeDefaultModel(existing)
        if (updated == existing) {
            Log.i(TAG, "默认模型已在 profile 用户配置里（或用户已自选）: ${patch.absolutePath}")
            return
        }
        try {
            patch.parentFile?.mkdirs()
            patch.writeText(updated)
            Log.i(TAG, "已把默认模型设为内置 $KEY_PROVIDER: ${patch.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "写入默认模型失败: ${t.message}")
        }
    }

    /**
     * 给已有 profile 补上「新任务默认预设 = razor」（幂等，只补缺）。
     *
     * [DEFAULT_SETTINGS_PATCH] 里带了这一项，但那份只在 profile 的 patch 文件
     * **不存在**时播种一次。已装过的设备文件早已存在，播种被跳过，所以老用户
     * 永远拿不到这个默认值 —— 与当初 [ensureDefaultModel] 完全同一个理由。
     *
     * ⚠️ 只补缺，不覆盖：`agent-preset-registry` 这一行在就原样保留，用户自己
     *   在设置页里改过默认预设属于用户意图。
     *
     * ⚠️ razor 是**可被用户关掉的 bundle**。真被关掉时 registry 查不到它，
     *   `resolve()` 会抛 `Unknown agent preset: razor`。所以这里先确认 bundle
     *   仍在 profile 的 bundles 名单里，再写默认值。
     */
    fun ensureDefaultPreset(profile: String = PROFILE_NAME) {
        val dir = File(dshHome, "profiles/$profile")
        val patch = File(dir, PROFILE_PATCH_FILENAME)
        val pkg = File(dir, "package.json")
        val bundles = try {
            if (pkg.isFile) org.json.JSONObject(pkg.readText()) else null
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile package.json 失败: ${t.message}")
            return
        }
        val list = bundles?.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")
        val razorOn = list != null && (0 until list.length()).any { list.optString(it) == PRESET_RAZOR_PACKAGE }
        if (!razorOn) {
            Log.i(TAG, "预设 $PRESET_RAZOR_PACKAGE 不在 bundles 名单里（用户关掉了），不设默认预设")
            return
        }
        val existing = try {
            if (patch.isFile) patch.readText() else ""
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile 用户配置失败: ${t.message}")
            return
        }
        if (existing.lines().any { KeyPoolProfileConfig.isEntryIdLine(it, PRESET_REGISTRY_NS) }) {
            Log.i(TAG, "默认预设已在 profile 用户配置里（或用户已自选）: ${patch.absolutePath}")
            return
        }
        val updated = mergeDefaultPreset(existing)
        try {
            patch.parentFile?.mkdirs()
            patch.writeText(updated)
            Log.i(TAG, "已把新任务默认预设设为 razor: ${patch.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "写入默认预设失败: ${t.message}")
        }
    }

    /** 把 `agent-preset-registry` 那一条并进 profile 用户配置文本（纯函数）。 */
    internal fun mergeDefaultPreset(body: String): String {
        val lines = body.lines().toMutableList()
        if (lines.any { KeyPoolProfileConfig.isEntryIdLine(it, PRESET_REGISTRY_NS) }) return body
        lines.indexOfFirst { it.isNotBlank() && it.indexOfFirst { c -> c != ' ' } == 0 && it.trim() == "[]" }
            .takeIf { it >= 0 }?.let { lines.removeAt(it) }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (lines.isNotEmpty()) lines.add("")
        lines.addAll(defaultPresetEntryYaml(0).trimEnd('\n').lines())
        return lines.joinToString("\n") + "\n"
    }

    /** 默认预设那一行的 YAML。 */
    private fun defaultPresetEntryYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}- id: $PRESET_REGISTRY_NS")
            appendLine("$pad  name: '$PRESET_REGISTRY_PACKAGE'")
            appendLine("$pad  config:")
            appendLine("$pad    default: razor")
        }
    }

    /**
     * 清掉 profile 用户配置里「内置 Agnes 供应商」的残留（幂等）。
     *
     * 早期版本把内置号池作为 `llm-pi-ai` 的一个 provider 写进用户层，并把默认
     * 模型指向它。用户要求把内置供应商整块删掉之后，这些残留会指向一个不再
     * 存在的凭据 —— 首次对话直接路由失败。所以每次启动清一遍。
     *
     * 只删**内置**那一份：`baseURL` 命中 agnes 网关的 provider，以及 `provider:
     * agnes` 的默认模型行。用户自己配的其他 provider 与自选模型一律不动。
     */
    fun stripBuiltinAgnesProfile(profile: String = PROFILE_NAME) {
        val patch = File(dshHome, "profiles/$profile/$PROFILE_PATCH_FILENAME")
        val existing = try {
            if (patch.isFile) patch.readText() else return
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile 用户配置失败: ${t.message}")
            return
        }
        val updated = stripBuiltinAgnesText(existing)
        if (updated == existing) return
        try {
            patch.writeText(updated)
            Log.i(TAG, "已清掉内置 Agnes 供应商残留: ${patch.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "清理内置 Agnes 供应商失败: ${t.message}")
        }
    }

    /** [stripBuiltinAgnesProfile] 的纯函数部分（便于离线验证）。 */
    internal fun stripBuiltinAgnesText(body: String): String {
        val lines = body.lines()
        // ⚠️ 只认**顶层**的 `- id:`（缩进 0）。`models:` 下面也有 `- id: agnes-3.0-flash`
        //   这类行，缩进不为 0 —— 若一并当成段起点，llm-pi-ai 段会被从中间切断，
        //   摘掉 `agnes:` 之后留下一段孤立的 `- id: ...`，整个 providers 从映射变成
        //   序列，引擎直接报 `invalid config`（真机实测踩到过）。
        val starts = lines.indices.filter { i ->
            val raw = lines[i]
            if (raw.isEmpty() || raw[0] == ' ' || raw[0] == '\t') return@filter false
            val t = raw.trimStart()
            t.startsWith("- ") && t.removePrefix("- ").trimStart().startsWith("id:")
        }
        if (starts.isEmpty()) return body
        val out = mutableListOf<String>()
        var cursor = 0
        for ((idx, from) in starts.withIndex()) {
            val to = if (idx + 1 < starts.size) starts[idx + 1] else lines.size
            out.addAll(lines.subList(cursor, from))
            val seg = lines.subList(from, to)
            val idLine = seg.first()
            val segText = seg.joinToString("\n")
            val isModel = idLine.contains(DEFAULT_MODEL_NS)
            val isLlm = idLine.contains("llm-pi-ai")
            when {
                // 默认模型指向内置 agnes ⇒ 整段删掉，让引擎回落到官方默认模型
                isModel && segText.contains("provider: $KEY_PROVIDER") -> Unit
                // llm-pi-ai：只摘掉内置 agnes 那个 provider，用户自建的照留
                isLlm && segText.contains(AGNES_GATEWAY_MARK) ->
                    out.addAll(dropYamlBlock(seg.map { it }, "agnes:", 6))
                else -> out.addAll(seg)
            }
            cursor = to
        }
        if (cursor < lines.size) out.addAll(lines.subList(cursor, lines.size))
        return out.joinToString("\n")
    }

    /** 从 [seg] 里摘掉缩进为 [indent] 的 `key:` 及其子块；其余原样保留。 */
    private fun dropYamlBlock(seg: List<String>, key: String, indent: Int): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < seg.size) {
            val line = seg[i]
            val lead = line.length - line.trimStart().length
            if (line.trimStart() == key && lead == indent) {
                i++
                while (i < seg.size) {
                    val next = seg[i]
                    if (next.isNotBlank() && (next.length - next.trimStart().length) <= indent) break
                    i++
                }
                continue
            }
            out.add(line)
            i++
        }
        return out
    }









    /**
     * 把内置号池接到 Agnes 多模态插件上（写 `$DSH_HOME/ag-multimodal.json`）。
     *
     * 插件调 `readAccounts()` 拿账号池，每条形如 `{endpoint, key}`，然后直接
     * `Authorization: Bearer <key>` 发出去。
     *
     * ## 号池改为服务端网关后的变化
     *
     * 以前这里写的 `key` 是占位符 `sk-ANON…`，由 `libkeyinject.so` 在 `SSL_write`
     * 里换成真钥匙；`endpoint` 必须用国际站域名，因为要靠等长改写 Host 头做
     * 两站路由。**那条链路已整体下线**。
     *
     * 现在：
     * - `endpoint` = 网关地址 [KEY_BASE_URL]
     * - `key`      = 网关令牌 [KEY_PLACEHOLDER]
     *
     * 真密钥、两站路由、429 换号重试**全部由服务器负责**，客户端不需要知道
     * 底下有几个站点、几把钥匙。
     *
     * ## ⚠️ 内置号是「不可删除、不可更改」的，所以每次启动都要修复
     *
     * 与 [seedDefaultSettings] 那种「只播种一次、之后不碰」的策略**刻意不同**。
     * 内置号是 App 提供的免费号池，属于产品能力的一部分，不是用户数据：用户删掉它
     * 就等于把内置号池弄丢了。所以这里每次都确保它存在且是权威值。
     *
     * 三重保护，缺一不可：
     *
     * 1. **服务端强制**（插件 `lib/index.js` 的 `set` 分支）：传回去的内置条目
     *    一律丢弃、用权威值重建 —— 绕过 UI 直接调 API 也改不动。
     * 2. **UI 只读**（插件 `lib/client.js`）：内置行没有地区切换、没有可编辑的
     *    输入框、没有「移除」按钮。
     * 3. **每次启动修复**（本方法）：兜住「用户改了磁盘上的文件」这种情况。
     *
     * ⚠️ 只**补回/纠正**内置条目，绝不触碰用户自己加的行，也不改它们的顺序 ——
     * 用户的号必须保持排在前面（优先级更高）。
     */
    fun seedAgnesAccounts() {
        val state = File(dshHome, AGNES_MM_STATE)
        try {
            val root = if (state.isFile) {
                // 解析失败（手改坏了 / 空文件）时退回空对象，下面会重建出内置号。
                JSONObject(state.readText().trim().ifEmpty { "{}" })
            } else {
                JSONObject()
            }

            val accounts = root.optJSONArray("accounts") ?: JSONArray()
            val next = JSONArray()
            // 用户行原样保留、保持原有顺序（它们排在前面 ⇒ 优先级更高）。
            for (i in 0 until accounts.length()) {
                val row = accounts.optJSONObject(i) ?: continue
                // 内置行一律跳过，下面统一追加权威值。认三种标记：
                //   - `builtin` 布尔标记（当前写法）
                //   - 老版本的 TLS 注入占位符 `sk-ANON…`（升级前写下的）
                //   - 当前网关令牌（用户手改后被纠正）
                val key = row.optString("key")
                if (row.optBoolean("builtin") || key == LEGACY_KEY_PLACEHOLDER || key == KEY_PLACEHOLDER) continue
                if (key.isBlank()) continue
                next.put(JSONObject().put("endpoint", row.optString("endpoint")).put("key", key))
            }
            // 内置号恒在最后一位（池尾兜底），带 builtin 标记。
            next.put(
                JSONObject()
                    .put("endpoint", KEY_BASE_URL)
                    .put("key", KEY_PLACEHOLDER)
                    .put("builtin", true)
            )
            root.put("accounts", next)

            val body = root.toString() + "\n"
            // 内容一致就不写盘：避免每次启动都动文件（也少一次 rename）。
            if (!state.isFile || state.readText() != body) {
                state.writeText(body)
                Log.i(TAG, "已把内置号池接入 Agnes 多模态插件: ${state.absolutePath}")
            }
        } catch (t: Throwable) {
            // 修复失败不阻断启动：插件会回落到它自带的默认账号
            Log.w(TAG, "写入 Agnes 账号池失败（不影响启动）: ${t.message}")
        }
    }

    /**
     * 首次安装时把本 App 的推荐设置播种进 profile 的用户 patch 层（幂等）。
     *
     * ## 为什么落在这里，而不是 `--patch` overlay
     *
     * 引擎的 patch 层序（`@deepseek-ai/dsh/lib/profile-boot-*.js` 的 `composeProfile`
     * 注释是权威定义）：
     *
     * ```
     * bundle 层 → profiles/web/cordis.patch.yml（用户）→ $DSH_HOME/cordis.patch.yml → --patch overlay（本 App）
     * ```
     *
     * `--patch` 在**最顶层**，放进去就盖住用户；而且
     * `@deepseek-ai/dsh-config-editor/lib/index.js` 在用户改这项设置时会直接抛
     * `Configuration for "<id>" is overridden by a home patch or command-line overlay` ——
     * 也就是「锁死」，与「用户可覆盖」的产品要求正好相反。
     *
     * 所以内置值必须落在**用户层**（`cordis.patch.yml`）：它就是 GUI 设置页
     * （`POST api/settings/mutate`）实际写入的那份文件，格式是 `id` + `name` + `config`。
     * 本方法只在用户**从未改过设置**时写一次，之后无论用户怎么改都不再碰
     * （判据见 [isEmptyProfilePatch]）。
     *
     * ## 为什么不会被引擎覆盖
     *
     * `@deepseek-ai/dsh-app-boot` 的 `initProfile()` 里是：
     *
     * ```js
     * const patchPath = join(dir, PROFILE_PATCH_FILENAME);
     * if (!existsSync(patchPath)) writeFileSync(patchPath, PROFILE_PATCH_TEMPLATE);
     * ```
     *
     * 只在缺失时写模板 `[]`，已有文件**一个字节都不动**（同函数上方的注释也写明
     * "Existing files are never touched"）。所以本方法必须跑在引擎启动**之前**。
     *
     * ## 各项取值的出处
     *
     * 全部由真机实测得到（Huawei CDY-TN90 / Android 10），不是猜的：在设置页里
     * 改一项，hook `window.fetch` 抓到 `settings/mutate` 的 payload 与落盘结果，
     * 再用 `settings/describe` 核对 schema 里的可选项与 `base`/`user` 分层。
     */
    fun seedDefaultSettings(profile: String = PROFILE_NAME) {
        val profileDir = File(dshHome, "profiles/$profile")
        val patch = File(profileDir, PROFILE_PATCH_FILENAME)
        if (patch.exists() && !isEmptyProfilePatch(patch)) {
            Log.i(TAG, "profile 用户配置已存在，保留用户设置: ${patch.absolutePath}")
            return
        }
        try {
            profileDir.mkdirs()
            // ⚠️ 走原子写入：写坏一半的话，下次启动 isEmptyProfilePatch 会判定
            //   「不是空模板」从而不再播种，而引擎拿到的是一个语法坏掉的 YAML ——
            //   用户设置等于永久损坏，且不会自愈。
            if (!writeTextAtomically(patch, DEFAULT_SETTINGS_PATCH)) {
                Log.w(TAG, "默认设置未能落盘（引擎会写空模板，不影响启动）")
                return
            }
            Log.i(TAG, "已播种默认设置: ${patch.absolutePath}")
        } catch (t: Throwable) {
            // 播种失败不能挡住启动：引擎会自己写一份空的模板，只是没有默认值
            Log.w(TAG, "播种默认设置失败（不影响启动）: ${t.message}")
        }
    }

    /**
     * 判断 profile patch 是否还是引擎生成的那份**空模板**（即用户从未改过任何设置）。
     *
     * 为什么需要这一条：引擎的 `initProfile()` 会给缺失的 patch 文件补一份
     * `PROFILE_PATCH_TEMPLATE`（注释 + 一个 `[]`）。所以「跑过一次引擎但没动过
     * 设置」的安装，文件是**存在**的 —— 只看 `exists()` 会让这些用户永远拿不到
     * 出厂默认值。
     *
     * 而空模板里没有任何用户意图（全是引擎自己写的注释和空数组），把它当作
     * 「未设置过」覆盖掉是安全的；只要用户动过任何一项设置，内容就不再是空模板，
     * 本方法返回 false，用户配置被完整保留。
     *
     * 判据取「去掉注释行与空行后只剩 `[]`」，而不是比对整段文本 —— 引擎改注释
     * 文案（甚至只是换行）时不会误判成「用户改过」。
     */
    private fun isEmptyProfilePatch(patch: File): Boolean {
        val body = try {
            patch.readText()
        } catch (t: Throwable) {
            // 读不出来（权限/编码）时按「有用户内容」处理，宁可不播种也不覆盖
            Log.w(TAG, "读取 profile patch 失败: ${t.message}")
            return false
        }
        val meaningful = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("")
        return meaningful == "[]"
    }

    /**
     * 原子写入文本文件：先写同目录的临时文件，再 `renameTo` 顶上。
     *
     * ## 为什么不能直接 `writeText`
     *
     * `File.writeText()` 是「truncate 成 0 字节 → 写内容」。这两步之间**有窗口**，
     * 且在真机上真的会被打断：`start()` 跑在 `Dispatchers.IO`，用户随手把 App
     * 切走、系统因内存压力杀进程，或者写一半没空间了 —— 结果是文件只剩前一半，
     * 或者干脆 0 字节。
     *
     * 用户设置写坏后，下次启动会被判定为非空配置，无法重新播种；
     * 因此在同目录写临时文件，再改名发布完整内容。
     *
     * ## 为什么临时文件必须同目录
     *
     * `renameTo` 跨文件系统会失败（返回 false）而不是抛异常。同目录保证同一个
     * 文件系统，改名是原子操作。
     *
     * @return 成功与否。**调用方必须处理 false** —— 它意味着磁盘上仍是旧内容。
     */
    private fun writeTextAtomically(target: File, text: String): Boolean = try {
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory) dir.mkdirs()
        // 临时文件用固定后缀而不是随机名：同名残留下次会被直接覆盖，不会越积越多。
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        // 先把临时文件的内容刷到磁盘，再改名 —— 否则断电时可能改完名但内容还在页缓存里。
        // 这里刻意不 fsync：这些文件在切换到后台时本来就是「下次启动重写」的语义，
        // 而 fsync 在 Android 上会引入明显的掉帧（主线程调用时）。原子性靠 rename 保证。
        if (tmp.renameTo(target)) {
            true
        } else {
            // rename 失败（极少见，如跨挂载点）：退化成直接写。
            // 此时原子性没了，但至少内容是对的 —— 比留一个损坏的旧文件强。
            Log.w(TAG, "原子改名失败，退化为直接写入: ${target.name}")
            target.writeText(text)
            tmp.delete()
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "原子写入失败: ${target.name}: ${t.message}")
        false
    }
}
