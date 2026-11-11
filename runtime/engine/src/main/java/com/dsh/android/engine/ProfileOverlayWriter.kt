package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import com.dsh.android.log.RescueBoot

/** 拥有 App 生成的 overlay 和说明片段；不拥有用户 profile 或恢复决定。 */
internal class ProfileOverlayWriter(
    private val dshHome: File,
    private val workspaceDir: File,
    private val isPluginEnabled: (String) -> Boolean,
) {

    /**
     * 写沙箱 overlay，把 `sandbox` 行的 `runnerCommand` 指向上面的 runner（幂等）。
     *
     * 该 overlay 作为 `--patch` 传给引擎，在 profile 层之后应用，因此能覆盖
     * `dsh-base/cordis.patch.yml` 里的 `sandbox` 行（patch 是整行替换 config，
     * 不是合并）。
     *
     * `runnerFailureSignatures` **不能留空**。provider 构造函数里有一对硬校验：
     *
     * ```js
     * if (runner.length > 0 && runnerFailureSignatures.length === 0)
     *   throw new Error("sandbox-local: runnerCommand requires at least one runnerFailureSignatures entry");
     * ```
     *
     * 它映射到 `ConfinedArgv.runnerFailureRules[].fatalSignatures` —— 判定
     * 「runner 自己挂了、命令根本没跑起来」的 stderr 子串（与 bwrap 的 `"bwrap: "`、
     * landlock 的 `"landlock-launcher: "` 同构）。填错/留空的后果不是降级而是
     * **整个 sandbox 服务注册失败**：`4 entries did not activate`，然后
     * `bash-sandbox` → `permission` → `ptc-runtime` 全部 pending，
     * 表现为新建会话失败：
     *
     * ```
     * agent-preset/invalid: tool-bash: waiting for shell workflow-ptc:
     *   waiting for ptcRuntime tool-workflow: waiting for workflowEngine
     * ```
     *
     * `boo-sandbox-run.c` 的每条 fail-closed 路径都用 `boo-sandbox-run: ` 前缀
     * （与 bwrap 同名同格式），所以这里就填它。**改 runner 的输出前缀必须同步改这里。**
     *
     * 沙箱「拒绝」与「runner 失败」是两回事：前者靠 provider 内置的
     * `DENIAL_SIGNATURES.runnerCommand = ["read-only file system",
     * "permission denied"]` 识别，而 `libboosandbox.so` 的 deny 输出正好含
     * `permission denied`。
     *
     * 为什么不用 profile 自己的 `cordis.patch.yml`：那层归 DSH 初始化、也可能
     * 承载用户改动（本机上 AI 就往里插过自己的插件）。`--patch` 层完全由本 App
     * 拥有，且优先级更高。
     */
    fun writeSandboxPatch(runner: File?): File? {
        if (runner == null) return null
        val file = File(dshHome, SANDBOX_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# Android 没有内核级沙箱后端（无 bwrap / Landlock 需 5.13+，设备为 4.14），")
            appendLine("# 故用自带 runner + LD_PRELOAD 写拦截器保住 workspace-write 的写边界。")
            appendLine("# 详见 app/src/main/jni/boo-sandbox-run.c。")
            appendLine("- id: sandbox")
            appendLine("  name: '@deepseek-ai/dsh-sandbox-local'")
            appendLine("  config:")
            appendLine("    runnerCommand:")
            appendLine("      - '${runner.absolutePath}'")
            appendLine("    runnerFailureSignatures:")
            appendLine("      - 'boo-sandbox-run: '")
        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入沙箱 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入沙箱 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 给 workspace controller 指定「文档目录」，让首次使用时能自动建出默认工作区。
     *
     * ## 为什么需要
     *
     * 全新安装（无工作区、无会话）时，前端会调 `workspaces.initializeDefault()`
     * 自动建一个默认工作区；失败就弹「无法创建默认工作区，请通过"选择工作区"选择文件夹」。
     *
     * host 侧 `@deepseek-ai/dsh-api-workspace-controller/lib/types/default-directory.js`
     * 的平台分支只认三平台：
     *
     * ```js
     * switch (platform) {
     *   case 'darwin': ... osascript ...
     *   case 'win32':  ... powershell ...
     *   case 'linux':  ... xdg-user-dir ...
     *   default: throw new Error(`system Documents directory is unavailable on ${platform}`);
     * }
     * ```
     *
     * Node 在 Android 上 `process.platform === 'android'`，直接落进 `default:` 抛错。
     * 这是本项目第 ⑫ 处「官方只认 linux/darwin/win32」的同款问题（与 ripgrep / sharp /
     * node-pty / flock 同类），**不是本 App 的 bug，但必须由本 App 补上**。
     *
     * ## 为什么用 `documentsDirectory` 而不是改引擎源码
     *
     * 上游 README 明确写了这个口子：「不具备该条件的 Host 必须配置 `documentsDirectory`
     * 或使用文件夹选择器」。它是 `Config` 里的 `z.string()`，非空时**跳过整个平台探测**
     * （`let directory = documentsDirectory; if (directory === undefined) { ...switch... }`），
     * 且构造时会 `validateDocumentsDirectory()` 要求绝对路径。所以只要给一个绝对路径，
     * Android 就完全绕开那个 switch。
     *
     * ## 为什么指向 app 私有目录下的 workspace
     *
     * 最终工作区是 `<documentsDirectory>/deepseek-harness/default-workspace`。
     * 用 `filesDir/workspace`（即 [workspaceDir]）而不是 `/sdcard`：
     *
     * - app 私有目录无需任何存储权限，Android 10+ 的分区存储下 `/sdcard` 反而更麻烦；
     * - 引擎进程的 cwd 本来就是 [workspaceDir]，默认工作区落在同一棵树下，
     *   agent 的文件操作与沙箱的写边界（`--bind <workspaceRoot>`）天然一致；
     * - `/sdcard` 在沙箱里是**被拒**的（见 AGENTS.md ④ 的验收项）。
     *
     * 注意这里指向 [workspaceDir] 本身，而实际建出来的是它下面的
     * `deepseek-harness/default-workspace`，不是把 [workspaceDir] 直接当工作区。
     *
     * ## 为什么用 `--patch` 而不是用户层
     *
     * 这一项**不是 GUI 设置项**（`settings/describe` 里没有它），用户无从覆盖，
     * 所以不存在「锁死用户配置」的问题；而它是 Host 部署事实（本机文档目录在哪），
     * 本来就该由 App 拥有。这与 [writeSandboxPatch] 同类，每次启动整体覆盖。
     */
    fun writeWorkspacePatch(): File? {
        val file = File(dshHome, WORKSPACE_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# Android 落不进引擎的平台分支（只认 darwin/win32/linux），")
            appendLine("# 必须显式给 documentsDirectory，否则首次使用建不出默认工作区。")
            appendLine("# 详见 EngineManager.writeWorkspacePatch() 的注释。")
            appendLine("- id: workspace-controller")
            appendLine("  name: '@deepseek-ai/dsh-api-workspace-controller'")
            appendLine("  config:")
            appendLine("    documentsDirectory: '${workspaceDir.absolutePath}'")

        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入工作区 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入工作区 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 让「添加插件」对话框同时列出镜像源与 npm 官方源（幂等）。
     *
     * ## 症状
     *
     * 真机对话框的「安装源」下拉只有**一项**（中国大陆镜像源）+ 自定义地址，
     * 而桌面版有两项（npm 官方源 + 中国大陆镜像源）+ 自定义地址。功能不算坏
     * （选「自定义地址」填 `https://registry.npmjs.org/` 等效），但用户看到的是
     * 「手机端少了官方源」。
     *
     * ## 根因：不是少配了，而是**被去重合并掉了**
     *
     * 列表由官方前端按 Host 返回的源**动态去重**生成
     * （`dsh-client-ui-plugin-manager/lib/client.js` 的 `offeredRegistries()`），
     * 遍历三处、按 URL 归一化后去重：
     *
     * ```
     * [config.registry] + [config.fallbackRegistries] + [pnpm 自身配置(~/.npmrc) 指的源]
     * ```
     *
     * 而官方 `fallbackRegistries` 的默认值**恰好就是** `NPMMIRROR_REGISTRY`。
     * 本机 [MirrorProbe] 测速后把 `registry=https://registry.npmmirror.com` 写进了
     * `~/.npmrc`，于是上面三项归一化后**全是同一个 URL** → 去重后只剩一项。
     * 桌面版 `.npmrc` 指的是官方源，与 fallback 的 npmmirror 不同 → 两项都留下。
     *
     * ## 为什么显式写 `registry` 且 fallback 里**也要**放镜像
     *
     * 两处都写，是因为 `registryPlan()` 的 `ownIsPublic` 判定：
     *
     * ```js
     * const ownIsPublic = own !== null && (own === "https://registry.npmjs.org/" || fallbacks.includes(own));
     * ```
     *
     * `own` 是 pnpm 自己报的源（本机 = npmmirror）。若 fallback 里不放它，
     * `ownIsPublic` 为 false，于是下面这条会让安装**只试一个源、没有回退**：
     *
     * ```js
     * if ((first === null || firstKey === own) && !ownIsPublic) return [normalizedFirst];
     * ```
     *
     * 把 npmmirror 也放进 fallback ⇒ `ownIsPublic` 为 true ⇒
     * 列表是 `[npmmirror, npmjs]`（镜像在前，与桌面版顺序一致），
     * 安装时先走镜像、失败自动回退官方源（`registryPlan` 的 known 序列）。
     *
     * ⚠️ URL 必须**逐字**与官方常量一致（含**尾斜杠**）。少了斜杠去重键不同，
     * 对话框会多出一项看起来重复的源。出处：`dsh-plugin-manager/lib/index.js`
     * 的 `NPMMIRROR_REGISTRY`。
     *
     * ## 为什么用 `--patch` 而不是用户层
     *
     * `plugin-manager` 的 `inject` 里**没有 `settings`**，这两项也就不是 GUI 设置项
     * （`settings/describe` 里没有它们），用户无从覆盖 —— 不存在「锁死用户配置」的
     * 问题。它们是 Host 部署事实（本机走哪个源），与 [writeSandboxPatch] /
     * [writeWorkspacePatch] 同类，每次启动整体覆盖。
     *
     * ⚠️ `name` 必须与 base 层逐字一致，否则 `applyEntryPatches()` 会
     * `name mismatch ... skipping` 整条跳过（静默，只在日志里 warn）。
     * 只写 `config:` 不会动 base 层那行的 `disabled: !!js ...`
     * —— patch 是**逐字段覆盖**（`target[key] = value`），不是整行替换。
     */
    fun writePluginManagerPatch(): File? {
        val file = File(dshHome, PLUGIN_MANAGER_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# 插件安装源：首选国内镜像，回退 npm 官方源。")
            appendLine("# 官方 fallbackRegistries 的默认值只有镜像源，而本机 ~/.npmrc 指的也是")
            appendLine("# 镜像源，两者被前端按 URL 去重合并 ⇒ 对话框只剩一项。显式写出两项即可。")
            appendLine("# 详见 EngineManager.writePluginManagerPatch() 的注释。")
            appendLine("- id: plugin-manager")
            appendLine("  name: '@deepseek-ai/dsh-plugin-manager'")
            appendLine("  config:")
            appendLine("    registry: '$NPM_MIRROR_REGISTRY'")
            appendLine("    fallbackRegistries:")
            appendLine("      - '$NPM_MIRROR_REGISTRY'")
            appendLine("      - '$NPM_OFFICIAL_REGISTRY'")
        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入插件源 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入插件源 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 写出挂载 Agnes 多模态插件的 overlay，返回其路径；不可用时返回 null。
     *
     * 与 [writeMobileUiPatch] 同构：`--patch` 在 profile 层**之后**应用，优先级
     * 更高，且完全由本 App 拥有，升级时整体覆盖即可。`name` 用 `file://` 绝对
     * URL，因为包在 pnpm 管辖之外、按裸包名解析不到。
     */
    fun writeAgnesMultimodalPatch(): File? {
        val patch = File(dshHome, AGNES_MM_PATCH)
        // 用户关掉了这个插件 ⇒ 不挂载，并删掉可能存在的旧 overlay
        //（否则上一次启动写的那个会继续生效，用户会以为开关没起作用）。
        if (!isPluginEnabled("agnes-multimodal")) {
            if (patch.isFile) patch.delete()
            Log.i(TAG, "Agnes 多模态插件已被用户关闭，跳过挂载")
            return null
        }
        val entry = File(dshHome, "$AGNES_MM_DIR/$AGNES_MM_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "Agnes 多模态插件入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载 Agnes 多模态插件；插件包随 APK 分发，已解到 .dsh/${AGNES_MM_DIR}。
                # 用 file:// 绝对路径挂载：包刻意放在 profile 的 node_modules 之外，
                # 否则用户每次用插件管理器装东西都会被 pnpm 挪进 .ignored/。
                - insert:
                    - id: $AGNES_MM_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入 Agnes 多模态 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 写出**定向隔离层** `$DSH_HOME/quarantine.patch.yml`（L1.5 的执行端）。
     *
     * 内容 = [RescueBoot.tier] 的 `quarantinedNames` 里每个条目一条
     * `- id: <id>` + `disabled: true`。**没有隔离项时删掉该文件并返回 null** ——
     * 留一个空层会让 `--patch` 多传一个空文件，且下次排查时「有文件」会被误读成
     * 「隔离过东西」。
     *
     * 幂等：每次启动整体覆盖，与其它 overlay 同构。
     *
     * ⚠️ **不能**把这里做成「扫描并删掉坏插件」：删文件是不可逆的，
     * 而用户要的是「App 能开」而不是「我的插件没了」。禁用层可一键撤销
     * （[RescueBoot.clearQuarantine]）。
     *
     * @return 写好的文件；无隔离项或写入失败时 null（失败时**不**挂这一层，
     *   退化成「本轮没隔离」，下次启动重试 —— 绝不让写文件失败变成新的启动失败点）。
     */
    fun writeQuarantinePatch(names: Collection<String>): File? {
        val patch = File(dshHome, QUARANTINE_PATCH)
        if (names.isEmpty()) {
            if (patch.isFile) patch.delete()
            return null
        }
        return try {
            val body = buildString {
                appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
                appendLine("# 定向隔离：这些插件条目上次把引擎弄到起不来，本轮禁用它们。")
                appendLine("# 文件一个没删 —— 想放回来见 rescue/state.json 的 quarantinedNames。")
                for (n in names) {
                    appendLine("- id: $n")
                    appendLine("  disabled: true")
                }
            }
            patch.writeText(body)
            Log.i(TAG, "已写入定向隔离 overlay: ${patch.name}（${names.size} 项: ${names.joinToString()}）")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入定向隔离 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 往 `$DSH_HOME/AGENTS.md` 写入特权身份说明（幂等）。
     *
     * ## 为什么写这个文件
     *
     * `@deepseek-ai/dsh-agent-instructions` 会自动读 `$DSH_HOME/AGENTS.md`
     * （默认候选名 `AGENTS.md` / `CLAUDE.md`）并注入系统提示词 —— 这是**零代码**
     * 的提示词通道，不需要改引擎、也不需要挂 overlay。
     *
     * ⚠️ 该文件归 DSH 的指令机制所有，用户也可能自己往里写东西。所以本方法
     * **只管理自己那一段**（`BEGIN`/`END` 标记之间），标记之外的内容原样保留。
     *
     * ## 为什么必须让模型知道
     *
     * 实测（见 `assets/dsh-android-shell.mjs` 头部对照表）app 域与 uid 2000 的
     * 差异**不体现在退出码上**：`input keyevent` 在 app 域返回 **rc=0 但屏幕不动**，
     * `dumpsys battery` 在 app 域返回 **rc=0 但输出 `Can't find service`**。
     * 模型若不知道自己在哪个身份下，会基于假前提继续推理、越走越错。
     *
     * @param available 特权工具是否**真的挂上了**（overlay 写入成功，即
     *   [shellToolMounted]），而不是「runner 文件解出来了没有」。
     *
     *   ⚠️ 这两个判据差一层，而差的正是结果：runner 解出成功、但插件入口文件没解出来
     *   （或 overlay 写失败）时，`shell` 工具**根本没注册**。若此时仍告诉模型「你有
     *   uid 2000 通道，需要设备级操作就用它」，模型会去调一个不存在的工具 ——
     *   表现为它反复尝试、或者干脆改用 `bash` 去做注定失败的设备级操作，而**没有任何
     *   一条报错指向真正的原因**。判据取「挂载结果」而不是「前置产物」才是诚实的。
     */
    fun ensurePrivilegedPrompt(available: Boolean) {
        // ⚠️ 单独打一行「通道可用=…」：AGENTS.md 的验收手法查的就是这条。
        //   它与下面 `writePromptSection` 的日志**互为补充** —— 后者只在文本
        //   真的变了时才打（幂等），所以稳态下看不到；而「这一轮到底可不可用」
        //   是每次启动都必须能查的事实。
        Log.i(TAG, "特权身份说明（uid 2000）: 通道可用=$available")
        writePromptSection(
            begin = PRIVILEGED_PROMPT_BEGIN,
            end = PRIVILEGED_PROMPT_END,
            body = if (available) PRIVILEGED_PROMPT_ENABLED else PRIVILEGED_PROMPT_DISABLED,
            label = "特权身份说明（uid 2000）",
        )
    }

    /**
     * 把「你正处于降级态、可以这样修」写进 agent 指令（方案 §4.4 的 P4）。
     *
     * ## 为什么是条件注入
     *
     * 正常态下这段整个不写（`writePromptSection` 不能删段落，所以正常态写一段
     * **空的**替代文本 —— 见下）。理由有两条：
     *
     * ① 它描述的是一个**当下成立的事实**（"你的插件被隔离了"）。正常态下这句话
     *    是假的，而把一个假前提塞进系统提示词会让模型做出错误判断 —— 那正是
     *    本项目缺陷族的形态（"结论与它描述的对象不符"）。
     * ② 它不短（约 2KB）。降级态下是值得的，正常态下纯属浪费上下文。
     *
     * ⚠️ 不能用「从没写过」来表示正常态：那样从降级态恢复后，旧文本会**永远
     *    留在 `AGENTS.md` 里**，模型此后一直以为自己处在救援模式。
     *    所以正常态要写一段**显式的否定**。
     */
    fun ensureRescuePrompt(tier: RescueBoot.Tier) {
        val degraded = tier != RescueBoot.Tier.NORMAL
        // 单独打一行：AGENTS.md 的验收手法查的就是这条（同 [ensurePrivilegedPrompt]）。
        Log.i(TAG, "降级态说明: 层级=${tier.id}，注入=${degraded}")
        writePromptSection(
            begin = RESCUE_PROMPT_BEGIN,
            end = RESCUE_PROMPT_END,
            body = if (degraded) {
                RESCUE_PROMPT
            } else {
                """
                ## 启动状态：正常

                本机**没有**处于降级启动模式，用户的插件配置完好。
                若用户说他打不开 App、或有插件没生效，读 `${'$'}DSH_HOME/rescue/state.json`
                与 `${'$'}DSH_HOME/rescue/last-boot-failure.json` 看有没有历史记录。
                """.trimIndent()
            },
            label = "降级态说明",
        )
    }

    /**
     * 把「这台设备有没有 root」写进 agent 指令。
     *
     * 与 [ensurePrivilegedPrompt] 同一个理由：模型必须知道自己**有没有**这个能力。
     * 不说的话它会去试 `su`（然后拿到 77 号退出码一脸茫然），或者反过来以为
     * 自己没有 root 而放弃本来做得到的事。
     *
     * ⚠️ 与 uid 2000 那段的差别：**没有 root 是常态**，所以不可用时那段文本要
     * 明确写「不要尝试绕过」，而不是像 shell 那样给一套引导步骤（root 只能由
     * 用户自己在设备上装/授权，App 帮不上忙）。
     *
     * @param available root 工具是否已注册（= 探测确认能拿到 uid 0 且 overlay 已挂）。
     */
    fun ensureRootPrompt(available: Boolean) {
        writePromptSection(
            begin = ROOT_PROMPT_BEGIN,
            end = ROOT_PROMPT_END,
            body = if (available) ROOT_PROMPT_ENABLED else ROOT_PROMPT_DISABLED,
            label = "root 身份说明",
        )
    }

    /**
     * 把一段由本 App 拥有的说明写进 `$DSH_HOME/AGENTS.md` 的 `BEGIN`/`END` 标记之间。
     *
     * ⚠️ 该文件归 DSH 的指令机制所有，用户也可能自己往里写东西。所以**只管理
     * 自己那一段**，标记之外的内容原样保留。标记不存在时追加到末尾。
     *
     * @param begin 段落起始标记（必须逐字唯一，用作幂等判据）
     * @param end 段落结束标记
     * @param body 段落正文
     * @param label 日志里用的名字
     */
    private fun writePromptSection(begin: String, end: String, body: String, label: String) {
        val file = File(dshHome, INSTRUCTIONS_FILE)
        try {
            val existing = if (file.isFile) file.readText() else ""
            val section = buildString {
                appendLine(begin)
                appendLine(body.trimEnd())
                appendLine(end)
            }

            val at = existing.indexOf(begin)
            val atEnd = existing.indexOf(end)
            val updated = if (at >= 0 && atEnd > at) {
                // 已有本段：整体替换，保留前后用户内容
                existing.substring(0, at) + section + existing.substring(atEnd + end.length).trimStart('\n', '\r')
            } else if (existing.isBlank()) {
                section
            } else {
                existing.trimEnd() + "\n\n" + section
            }

            if (updated != existing) {
                dshHome.mkdirs()
                file.writeText(updated)
                Log.i(TAG, "已写入${label}: ${file.name}")
            }
        } catch (t: Throwable) {
            // ⚠️ 必须写 `${label}` 而不是 `$label`：中文是合法的 Kotlin 标识符字符，
            //   `"写入$label失败"` 会被解析成标识符 `label失败` → Unresolved reference。
            Log.w(TAG, "写入${label}失败: ${t.message}")
        }
    }
}
