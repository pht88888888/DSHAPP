package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
/** 旧号池 profile 的纯文本补缺规则，与落盘事务分离。 */
internal object KeyPoolProfileConfig {
    /**
     * 把内置号池那一行并进 profile 用户配置文本（纯函数，便于离线验证）。
     *
     * 判据是「`llm-pi-ai` 那一行的 `providers:` 下有没有 `agnes:` 键」：
     *
     * - 整行不存在 ⇒ 追加完整的一行；
     * - 有行但没有 `providers:` ⇒ 在该行块内补出 `config.providers`；
     * - 有 `providers:` 但没有 `agnes:` ⇒ 在 `providers:` 之后插入内置块；
     * - 已有 `agnes:` ⇒ **一个字节都不动**（用户/引擎写下的值原样保留）。
     *
     * ⚠️ 为什么只做「补缺」而不整块重写：这一行是 `dsh-config-editor` 实际读写
     * 的那份文档，用户每次在 GUI 里改模型设置都会被它整体重写。我们只在缺失时
     * 补一笔，才既能保证内置号池始终在，又不会覆盖用户自己加的 provider。
     */
    fun mergeKeyPoolProvider(body: String): String {
        val lines = body.lines().toMutableList()
        val idIndex = lines.indexOfFirst { isEntryIdLine(it, KEY_POOL_NS) }

        if (idIndex < 0) {
            // 这一行还不存在。⚠️ 必须先摘掉空模板的 `[]` —— 留着它会变成
            // `[]` 后面又跟一个 entry，是**非法 YAML**（一个文档不能既是流式
            // 空数组又是块序列）。注释原样保留，YAML 允许注释出现在数组之前。
            // 只认**顶层**的 `[]`（引擎模板就长这样），免得误删别处的空数组值。
            lines.indexOfFirst { it.isNotBlank() && it.indexOfFirst { c -> c != ' ' } == 0 && it.trim() == "[]" }
                .takeIf { it >= 0 }?.let { lines.removeAt(it) }
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty()) lines.add("")
            lines.addAll(keyPoolEntryYaml(0).trimEnd('\n').lines())
            return lines.joinToString("\n") + "\n"
        }

        // 缩进不写死：GUI（js-yaml）与用户手写的缩进都照各自的来。
        val idIndent = lines[idIndex].indexOfFirst { it != ' ' }.coerceAtLeast(0)
        val child = idIndent + 2

        // 该 entry 的块范围：到下一个同级或更浅的项为止。
        var end = lines.size
        for (i in idIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.indexOfFirst { it != ' ' } <= idIndent) {
                end = i
                break
            }
        }

        /**
         * 块内缩进恰好为 [indent] 的 `key:` 行下标，找不到给 -1。
         *
         * ⚠️ **必须容忍「行内值」形态**（`key: {}` / `key: []`），不能只认裸 `key:`。
         *
         *   踩过的坑（真机崩溃，见下）：引擎把 schema 的默认值**序列化进文件**，
         *   `dsh-llm-pi-ai` 的 `Config` 是
         *   `providers: z.dict(profile).default({})` —— 于是用户只要在 GUI 设置页
         *   碰过一次「模型」，写下的那份 `cordis.patch.yml` 里就是 `providers: {}`
         *   而不是裸 `providers:`。
         *
         *   旧实现用 `line.trim() == key` 精确相等 ⇒ **看不见 `providers: {}`** ⇒
         *   判定「没有 providers」⇒ 再插一个 `providers:` ⇒ 同一个 mapping 里出现
         *   两个 `providers` 键 ⇒ 引擎 `failed to parse overlay` 抛在顶层、
         *   **启动即崩**（`YAMLException: duplicated mapping key`）。
         *
         *   这条路径特别毒：全新安装的机器上文件是空的、插进去就是对的，
         *   只有**已经用过设置页的老用户**才会踩到 —— 典型的「新装测不出」。
         *
         * `key:` 之后必须跟空白（或行尾），免得 `providers:` 把 `providersExtra:`
         * 这种更长的键名也命中。
         */
        /**
         * 本次调用有没有真的改过 [lines]。
         *
         * ⚠️ 只有它能区分「一个字节都没动」与「动过但结果恰好等于按原样处理」。
         *   踩过的坑：自愈分支（见 [dropDuplicateEmptyKeys]）先把重复键删了，
         *   随后走到「`agnes` 已有 retryPolicy」那一支，那里 `return body` 返回的
         *   是**入参原文**，于是刚删掉的重复键又被原样吐回去 —— 自愈白做，
         *   用户的砖机照旧崩。所以「无需再补」时也必须返回 [lines]（若它被改过）。
         */
        var dirty = false

        fun findKey(key: String, indent: Int, from: Int): Int {
            for (i in from until end) {
                val line = lines[i]
                if (line.isBlank()) continue
                val trimmed = line.trim()
                if (line.indexOfFirst { it != ' ' } == indent &&
                    (trimmed == key || trimmed.startsWith("$key "))
                ) {
                    return i
                }
            }
            return -1
        }

        /**
         * 把 `key: <行内值>` 收敛成裸 `key:`，返回「这一行现在能不能挂子键」。
         *
         * ⚠️ 光让 [findKey] 认出 `providers: {}` 还不够：`{}` 是**流式空映射**，
         *   在它下面缩进写 `agnes:` 同样是非法 YAML（标量值后面不能接块内容）。
         *   所以认出来之后必须把行内值摘掉，把这一行还原成「块映射的开头」。
         *
         * 语义等价：`providers: {}` 就是「空映射」，摘成 `providers:` 再挂上
         * `agnes:` 得到的就是「只含 agnes 的映射」—— 正是我们要的结果，
         * 而 `{}` 里本来就没有任何东西可以丢。
         *
         * ⚠️ **但只对「空集合」等价的写法做这件事**（`{}` / `[]` / `null` / `~`）。
         *   非空行内映射（`providers: {deepseek: {...}}`，用户手写或别的工具写的）
         *   摘掉就是**把用户的 provider 配置整行抹掉** —— 那是数据丢失，
         *   比「号池这一版没并进去」严重得多。这种情况返回 false，
         *   由调用方**放弃本次插入**（宁可不动，也不毁用户数据）。
         *
         * 已经是裸 `key:` 时返回 true 且一个字节都不动。
         */
        fun openKeyBlock(index: Int, key: String, indent: Int): Boolean {
            val trimmed = lines[index].trim()
            if (trimmed == key) return true
            val inline = trimmed.removePrefix(key).trim()
            if (inline !in EMPTY_INLINE_VALUES) return false
            lines[index] = " ".repeat(indent) + key
            dirty = true
            return true
        }

        fun insert(at: Int, text: String) {
            val added = text.trimEnd('\n').lines()
            lines.addAll(at, added)
            // ⚠️ [end] 是「该 entry 的块范围」，插入点一定在 [at] <= end 之内，
            //   所以块范围只是往后平移，不能直接写成 lines.size（那会把范围
            //   扩到整个文件末尾，后续 findKey 会越界到别的 entry 里去）。
            end += added.size
            dirty = true
        }

        /**
         * 删掉同一缩进下**多余的、且值写成行内空集合**的同名键行 —— 自愈旧版写坏的文件。
         *
         * ⚠️ 为什么非有不可：上面那个 `findKey` 盲区在 0.2.8 里**已经把一批用户写成了砖**。
         *   他们文件里现在是
         *   ```
         *   config:
         *     providers:        ← 旧版插的（含 agnes）
         *       agnes: {...}
         *     providers: {}     ← 引擎原本写的，重复键
         *   ```
         *   光修 `findKey` 只能防住**新**的写入；对这份已坏文件，`findKey` 会命中
         *   **第一个** `providers:`（它下面确实有 agnes）⇒ 判定「已经并好了」⇒
         *   `return body` ⇒ 重复键原样留着 ⇒ **引擎每次启动照样崩**。
         *   也就是说：不写这段自愈，老用户的砖机永远修不好，只能卸载重装（清空
         *   整个 `$DSH_HOME`：会话、凭据、工作区全没）。这正是最不能接受的结局。
         *
         * ⚠️ **只删「行内空集合」形态的行**（`{}` / `[]` / `null` / `~`）。
         *   非空值的重复键一个都不碰 —— 那种文件本来就是我们判断不了的用户内容，
         *   宁可留着让它继续报错（用户至少能从报错里看见自己的东西），也不静默乱删。
         *   我们实际写坏的那一行恰好就是 `{}`（引擎的默认值序列化），所以够用。
         *
         * 从后往前删，[keep] 之前的索引不受影响；[end] 同步收缩。
         */
        fun dropDuplicateEmptyKeys(key: String, indent: Int, keep: Int) {
            var removed = 0
            for (i in end - 1 downTo keep + 1) {
                if (i >= lines.size) continue
                val line = lines[i]
                if (line.isBlank() || line.indexOfFirst { it != ' ' } != indent) continue
                val trimmed = line.trim()
                if (!trimmed.startsWith("$key ")) continue
                if (trimmed.removePrefix(key).trim() !in EMPTY_INLINE_VALUES) continue
                lines.removeAt(i)
                removed++
            }
            // 删掉的都在块范围内，所以块范围等量收缩（同样不能写成 lines.size）。
            end -= removed
            if (removed > 0) dirty = true
        }

        /** 无需再补时的返回：改过就必须给出 [lines]，绝不能把入参原文吐回去。 */
        fun unchangedOrHealed(): String =
            if (dirty) lines.joinToString("\n") else body

        val configIndex = findKey("config:", child, idIndex + 1)
        if (configIndex < 0) {
            // 行在、但没有 config（只有 id + name）：只补 config 子树。
            // ⚠️ 不能再输出一遍 `name:` —— YAML 重复键是非法文档（踩过）。
            insert(end, keyPoolConfigBody(child))
            return lines.joinToString("\n")
        }
        // ⚠️ 从这里开始会改 [lines]。三处 [openKeyBlock] 里任何一处判定「不能安全收敛」
        //   （碰到非空行内映射，摘了就是抹用户配置）都必须**整份回滚**：
        //   只收敛了 `config:` 却没插成 `providers:`，会把用户文件从 `config: {}`
        //   改成 `config:`（空块）—— 语义未必等价，而 KDoc 承诺「不动就一个字节不动」。
        //   宁可这一版不并号池，也不能留下半成品改动。
        val snapshot = lines.toList()
        fun abandon(): String {
            lines.clear()
            lines.addAll(snapshot)
            dirty = false
            return body
        }

        // 同样是行内值盲区：`config: {}` 也得先收敛成块开头，
        // 否则下面挂 `providers:` 是非法 YAML。见 [findKey] / [openKeyBlock]。
        if (!openKeyBlock(configIndex, "config:", child)) return abandon()
        val providersIndex = findKey("providers:", child + 2, configIndex + 1)
        if (providersIndex < 0) {
            insert(configIndex + 1, " ".repeat(child + 2) + "providers:")
            insert(configIndex + 2, keyPoolAgnesYaml(child + 4))
            return lines.joinToString("\n")
        }
        // 引擎会把默认值序列化成 `providers: {}`。识别出来之后必须把 `{}` 摘掉，
        // 否则在它下面缩进写 `agnes:` 仍是非法 YAML（标量后不能接块）。
        // 见 [findKey] 与 [openKeyBlock] 的注释。
        if (!openKeyBlock(providersIndex, "providers:", child + 2)) return abandon()
        // 自愈旧版写坏的文件：同一层里重复的 `providers: {}` 要删掉，否则引擎照样崩。
        dropDuplicateEmptyKeys("providers:", child + 2, providersIndex)
        // 已有内置号池：默认一个字节都不动（用户/引擎写下的值原样保留）。
        //
        // ⚠️ **但有一个例外：`retryPolicy` 缺失时必须补进去**（踩过）。
        //   只做「有 agnes: 就整块跳过」的话，**所有老安装永远拿不到新策略** ——
        //   它们的 `agnes:` 块在上一版就写好了，`mergeKeyPoolProvider` 一看存在
        //   就直接 return body，于是「抽到坏钥匙自动换一把」这个修复对存量用户
        //   **完全不生效**，只对全新安装生效。这是最容易漏的一类 bug：代码看着对、
        //   新装的机器上测也对，就是老用户没反应。
        //
        //   补的范围**严格限定在 `retryPolicy:` 这一个键**：只在它整个子树缺失时
        //   插一笔，已有则一个字节都不动（用户手改过的值照样保留）。
        val agnesIndex = findKey(KEY_PROVIDER + ":", child + 4, providersIndex + 1)
        if (agnesIndex < 0) {
            insert(providersIndex + 1, keyPoolAgnesYaml(child + 4))
            return lines.joinToString("\n")
        }
        // `agnes: {}` 同理（行内值盲区）：不收敛成块开头就没法往下挂 `retryPolicy:`。
        // 非空行内映射（用户手写的 `agnes: {...}`）则放弃整次改动，绝不抹掉它。
        if (!openKeyBlock(agnesIndex, "$KEY_PROVIDER:", child + 4)) return abandon()
        // 同一盲区在 agnes 层同样写过重复键（文件里有 `agnes: {}` 时旧版会再插一个）。
        dropDuplicateEmptyKeys("$KEY_PROVIDER:", child + 4, agnesIndex)

        // `agnes:` 块的范围（到下一个同级或更浅的键为止），用于在它内部找键。
        var agnesEnd = end
        for (i in agnesIndex + 1 until end) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.indexOfFirst { it != ' ' } <= child + 4) {
                agnesEnd = i
                break
            }
        }
        // 判据同样必须容忍行内值：`retryPolicy: {}` 也算「已经有」——
        // 用精确相等会把已有的认成缺失，再插一个就是重复键（同一个坑的第三次）。
        val hasRetryPolicy = (agnesIndex + 1 until agnesEnd).any { i ->
            val line = lines[i]
            val trimmed = line.trim()
            line.isNotBlank() && line.indexOfFirst { it != ' ' } == child + 6 &&
                (trimmed == "retryPolicy:" || trimmed.startsWith("retryPolicy: "))
        }
        // ── baseURL 迁移：老安装必须改到网关地址 ──────────────────────────
        //
        // ⚠️ 与上面 `retryPolicy` 完全同一类 bug，只是这次漏的是 baseURL。
        //
        //   号池从「客户端 TLS 注入 + 直连上游」改成「服务端网关」后，
        //   `agnes:` 块里的 `baseURL` 必须从 `api.agnes-ai.cn` /
        //   `apihub.agnes-ai.cn` 换成 [KEY_BASE_URL]。
        //
        //   但 `mergeKeyPoolProvider` 的既定语义是「已有 agnes: 就一个字节都不动」，
        //   于是**所有老安装升级后仍然指向旧域名**：注入库已经删了，占位符不再被
        //   替换成真密钥，请求带着 `sk-agnes-…` 令牌直连 Agnes 上游 ⇒ 上游回 401
        //   ⇒ 客户端显示「API 密钥无效」。全新安装反而是对的，典型的
        //   「新装测不出、老用户全炸」。
        //
        //   纠偏范围**严格限定在 baseURL 这一个键**，且**只认已知的旧值**：
        //   - 命中旧直连域名 ⇒ 改成网关地址（这是本次迁移的唯一目的）
        //   - 已是网关地址   ⇒ 不动
        //   - 其它任意值     ⇒ 不动（用户自己填的第三方地址，绝不可覆盖）
        var baseUrlFixed = false
        val baseUrlIndent = child + 6
        for (i in agnesIndex + 1 until agnesEnd) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.indexOfFirst { it != ' ' } != baseUrlIndent) continue
            val trimmed = line.trim()
            if (!trimmed.startsWith("baseURL:")) continue
            val value = trimmed.removePrefix("baseURL:").trim().trim('\'', '"')
            if (value in LEGACY_KEY_BASE_URLS) {
                lines[i] = " ".repeat(baseUrlIndent) + "baseURL: '$KEY_BASE_URL'"
                dirty = true
                baseUrlFixed = true
                Log.i(TAG, "内置号池 baseURL 迁移: $value -> $KEY_BASE_URL")
            }
            break
        }
        // ⚠️ 不能在这里 `return`：baseURL 改了之后，retryPolicy 可能**同样缺失**
        //    （老安装两样都缺）。早退会让「换号重试」这个修复对老用户失效，
        //    与上面 retryPolicy 注释里踩过的坑完全一样。继续往下走。
        if (hasRetryPolicy) {
            return if (baseUrlFixed) lines.joinToString("\n") else unchangedOrHealed()
        }

        insert(agnesIndex + 1, keyPoolRetryPolicyYaml(child + 6))
        return lines.joinToString("\n")
    }

    /**
     * 把「默认模型」那一行并进 profile 用户配置文本（纯函数，便于离线验证）。
     *
     * ⚠️ 判据比 [mergeKeyPoolProvider] 简单得多：**只看那一行在不在**。
     *   因为 profile 的用户 patch 层里本来就没有这一行（引擎的出厂值在 bundle 层，
     *   不在这个文件里），所以「行存在」⇔「用户或本 App 写过它」。
     *   行在就原样保留 —— 用户改过的模型选择必须留住。
     */
    fun mergeDefaultModel(body: String): String {
        val lines = body.lines().toMutableList()
        if (lines.any { isEntryIdLine(it, DEFAULT_MODEL_NS) }) return body

        // 与 mergeKeyPoolProvider 同一处理：先摘掉空模板的顶层 `[]`，否则会变成
        // `[]` 后面又跟一个 entry —— **非法 YAML**（一个文档不能既是流式空数组
        // 又是块序列）。注释原样保留，YAML 允许注释出现在数组之前。
        lines.indexOfFirst { it.isNotBlank() && it.indexOfFirst { c -> c != ' ' } == 0 && it.trim() == "[]" }
            .takeIf { it >= 0 }?.let { lines.removeAt(it) }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (lines.isNotEmpty()) lines.add("")
        lines.addAll(defaultModelEntryYaml(0).trimEnd('\n').lines())
        return lines.joinToString("\n") + "\n"
    }

    /** 默认模型那一行的 YAML，[indent] 为整行基准缩进。 */
    private fun defaultModelEntryYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}- id: $DEFAULT_MODEL_NS")
            appendLine("$pad  name: '$DEFAULT_MODEL_PACKAGE'")
            appendLine("$pad  config:")
            appendLine("$pad    provider: $KEY_PROVIDER")
            appendLine("$pad    model: ${KEY_MODELS.first()}")
        }
    }

    /** [mergeKeyPoolProvider] 的判据：这一行是不是 `- id: <ns>`。 */
    fun isEntryIdLine(line: String, ns: String): Boolean {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("-")) return false
        val rest = trimmed.removePrefix("-").trimStart()
        if (!rest.startsWith("id:")) return false
        return rest.removePrefix("id:").trim().trim('\'', '"') == ns
    }

    /** 完整的一行（`- id:` + 内容），[indent] 为整行基准缩进。 */
    private fun keyPoolEntryYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            append(pad)
            appendLine("- id: $KEY_POOL_NS")
            append(keyPoolEntryBody(indent + 2))
        }
    }

    /** 一行 entry 除 `- id:` 之外的部分（`name` + `config`），[indent] 为 `name:` 的缩进。 */
    private fun keyPoolEntryBody(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}name: '@deepseek-ai/dsh-llm-pi-ai'")
            append(keyPoolConfigBody(indent))
        }
    }

    /** 只有 `config` 子树（用于「行已存在但没写 config」的情况），[indent] 为 `config:` 的缩进。 */
    private fun keyPoolConfigBody(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}config:")
            appendLine("${pad}  providers:")
            append(keyPoolAgnesYaml(indent + 4))
        }
    }

    /** 内置号池那一个 provider 的 YAML 块，[indent] 为 `agnes:` 的缩进。 */
    private fun keyPoolAgnesYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}$KEY_PROVIDER:")
            appendLine("$pad  displayName: '$KEY_PROVIDER_NAME'")
            appendLine("$pad  api: openai-completions")
            appendLine("$pad  baseURL: '$KEY_BASE_URL'")
            appendLine("$pad  apiKeyEnv: $KEY_ENV")
            append(keyPoolRetryPolicyYaml(indent + 2))
            appendLine("$pad  models:")
            for (m in KEY_MODELS) {
                appendLine("$pad    - id: $m")
                // 声明输入模态，否则默认 ["text"] ⇒ 图片在附加前就被拒（见 [KEY_MODEL_INPUT]）。
                appendLine("$pad      input:")
                for (modality in KEY_MODEL_INPUT) {
                    appendLine("$pad        - $modality")
                }
                // ⚠️ 刻意**不声明** `off` 档，理由见 [KEY_EFFORT_LEVELS]。
                appendLine("$pad      reasoningEfforts:")
                for (e in KEY_EFFORT_LEVELS) {
                    appendLine("$pad        '$e': $e")
                }
            }
        }
    }

    /**
     * 号池的重试策略块，[indent] 为 `retryPolicy:` 的缩进。
     *
     * ⚠️ **这是「抽到坏钥匙自动换一把」的全部实现**，别删。
     *
     * 号池每次请求都在 TLS 层随机换一把钥匙（见 `keyinject.c` 的 `SSL_write`），
     * 而池子里**确实有死钥匙**（实测 `cn.87` 稳定返回 403「检测到账号存在异常
     * 活动」）。没有这份策略时，那一次请求的 403 会被引擎归类成 `AUTH`
     * （`dsh-llm-pi-ai` 的 `classifyPiAiError`：`/\b(?:401|403)\b/` → `"AUTH"`），
     * 而 `AUTH` **不在** `DEFAULT_RETRYABLE_CODES` 里
     * （默认只有 EMPTY_RESPONSE / RATE_LIMIT / SERVER / TIMEOUT / TRANSPORT），
     * 于是 `dsh-llm-retry` 直接放行不重试 ⇒ **报错砸到用户脸上**。
     * 概率是 1/池子大小，池子越大越容易撞上，属于「偶尔抽风」的体验杀手。
     *
     * 配上这份策略后：403 → `AUTH` ∈ `retryableCodes` ⇒ 重发请求 ⇒ `SSL_write`
     * 重新随机抽一把钥匙 ⇒ 绝大多数情况第二次就通了，**用户完全无感**。
     *
     * ⚠️ `retryableCodes` 是**整体替换**默认值而不是追加
     * （`resolveRetryPolicy`：`config.retryableCodes ?? [...DEFAULT_RETRYABLE_CODES]`），
     * 所以默认那五个必须**原样再列一遍**，漏掉哪个就等于把那类错误的重试关掉了。
     *
     * ⚠️ 只对号池这个 provider 生效（写在 `agnes:` 块里），用户自己加的 provider
     * 一个字节都不受影响。
     *
     * ⚠️ `mode: normal` + `maxRetries: 3`：真撞上一整片死钥匙时最多重试 3 次就
     * 如实报错，不会无限重发。
     */
    private fun keyPoolRetryPolicyYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}retryPolicy:")
            appendLine("$pad  mode: normal")
            appendLine("$pad  maxRetries: 3")
            appendLine("$pad  retryableCodes:")
            for (code in KEY_RETRYABLE_CODES) {
                appendLine("$pad    - $code")
            }
        }
    }
}
