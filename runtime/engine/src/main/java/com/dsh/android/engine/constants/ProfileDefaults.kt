package com.dsh.android.engine.constants

/** 正常与救援 profile 名称、overlay 路径和用户层默认配置。 */
        /**
         * 旧版内置号池 overlay 的文件名。**只用于删除**：那个写法（`- id: llm-pi-ai`
         * 的 `--patch`）会把用户新建的 provider 一起盖掉，导致 GUI 一加第三方 API
         * 就报 `Configuration for "llm-pi-ai" is overridden by a home patch or
         * command-line overlay`。老安装里残留的这份必须删掉，否则它仍会生效。
         */
const val KEYINJECT_PATCH = "keyinject.patch.yml"

const val SANDBOX_PATCH = "sandbox.patch.yml"

        /** 默认工作区 overlay：补上引擎缺的 android 平台分支。见 [writeWorkspacePatch]。 */
const val WORKSPACE_PATCH = "workspace.patch.yml"

        /** 插件安装源 overlay：让对话框同时列出镜像与官方源。见 [writePluginManagerPatch]。 */
const val PLUGIN_MANAGER_PATCH = "plugin-manager.patch.yml"

        /**
         * 插件安装源。URL 必须与官方常量逐字一致（含尾斜杠），否则去重键不同、
         * 会多出一项重复的源。出处：`@deepseek-ai/dsh-plugin-manager` 的
         * `NPMMIRROR_REGISTRY`，以及 npm 官方源。
         */
const val NPM_MIRROR_REGISTRY = "https://registry.npmmirror.com/"
const val NPM_OFFICIAL_REGISTRY = "https://registry.npmjs.org/"

        // ── 出厂默认设置 ─────────────────────────────────────────────
        //
        // 只在首次安装时播种进 profile 的**用户 patch 层**，之后永不覆盖。
        // 为什么不能用 `--patch` overlay、为什么不会被引擎覆盖，见 seedDefaultSettings()。

        /**
         * 引擎启动时用的 profile 名（`--profile web`）。
         *
         * ⚠️ **`internal` 而不是 `private`**：`RescueCommandBridge` 的 `probe`
         *   默认就探测这个 profile。让它自己写 `"web"` 字面量等于把这个名字
         *   复制一份，将来改名必漏一处。
         */
        const val PROFILE_WEB = "web"

        /** [PROFILE_WEB] 的私有别名，保持文件内既有引用不动。 */
const val PROFILE_NAME = PROFILE_WEB

        /**
         * 救援 profile 名（`--profile rescue`）。
         *
         * ⚠️ **不能用引擎内置模板名**：`PROFILE_TEMPLATES` 只有
         * `acp` / `web` / `headless` / `sdk` / `sdk-minimal` 五个，
         * 传别的名字引擎直接抛
         * `dsh: profile "rescue" does not exist; create it with 'dsh plugin --profile rescue add <package>'`
         * （`dsh-app-boot/lib/index.js` L973-974）。
         *
         * 真机实测过（2026-10-14，CDY-TN90 / Android 10）：
         * 手工写好 `profiles/rescue/package.json`（bundles =
         * `["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"]`）之后，
         * `--profile rescue --dump-config` 打出完整条目表、
         * `--profile rescue --no-open --port 0` 打出
         * `dsh web: http://127.0.0.1:44057/?token=…` —— 完全可用。
         *
         * ⇒ 所以 App **自己写这个 profile 的 `package.json`**，不指望引擎初始化它。
         * 见 [ensureRescueProfile]。
         */
const val RESCUE_PROFILE_NAME = "rescue"

        /**
         * 救援 profile 的 bundle 清单。
         *
         * 逐字取自引擎自己的 `PROFILE_TEMPLATES.web.bundles`
         * （`dsh-app-boot/lib/index.js` L531），**不是**从用户的
         * `profiles/web/package.json` 推导的 —— 那份文件正是可能已经坏掉的东西
         * （用户装了 40 个插件、其中某个拖挂了核心插件）。用它当模板等于
         * 把病灶复制进救援环境，救援就一起起不来了。
         *
         * ⚠️ 只放 `@deepseek-ai/…` 核心 bundle ⇒ 天然满足不变量 I1
         * （救援启动必须比正常启动简单，不能成为第二个失败源）。
         */
val RESCUE_BUNDLES = listOf(
            "@deepseek-ai/dsh-base",
            "@deepseek-ai/dsh-web-app",
        )

        /** profile 的用户 patch 层文件名（引擎的 `PROFILE_PATCH_FILENAME`）。 */
const val PROFILE_PATCH_FILENAME = "cordis.patch.yml"

/** 预设注册表那一行的 entry id / 包名（要覆盖它的 `config.default`，id 必须逐字一致）。 */
const val PRESET_REGISTRY_NS = "agent-preset-registry"
const val PRESET_REGISTRY_PACKAGE = "@deepseek-ai/dsh-agent-preset-registry"

        /**
         * 默认模型那一行的 entry id。
         *
         * 出处是 bundle 层 `@deepseek-ai/dsh-base/cordis.patch.yml`（`- id: agent-default-model`）。
         * 我们要覆盖的是它的 `config.provider`/`config.model`，所以 **id 必须逐字一致**
         * 才能命中同一行。
         */
const val DEFAULT_MODEL_NS = "agent-default-model"

        /** 该 entry 的 `name`（= 引擎里提供 `agentDefaultModel` 服务的包名）。 */
const val DEFAULT_MODEL_PACKAGE = "@deepseek-ai/dsh-agent-default-model"

        /**
         * 出厂默认设置。格式与 GUI 设置页写出的完全一致（`id` + `name` + `config`），
         * 所以用户在设置页里改任何一项，都只是往同一份文件里追加/覆盖对应的 `config`。
         *
         * 四项取值均由真机实测确定（Huawei CDY-TN90 / Android 10）：
         *
         * | 设置页文案 | ns | 取值 | 为什么是这个值 |
         * |---|---|---|---|
         * | 权限 = 完全权限 | `permission` | `defaultPreset: danger-full-access` | 手机上没有桌面那种"逐条审批"的交互条件 |
         * | 字号大小 = 15 | `ui-theme` | `fontSize: 15` | 引擎默认 14 在手机上偏小 |
         * | 工作步骤展示 = 标准 | `ui-chat` | `transcriptView: standard` | 引擎默认 `detailed`，手机上太占屏 |
         * | 繁忙时的发送行为 = 插话发送 | `ui-conversation` | `busyEnter: steer` | 引擎默认 `queue`（排队），插话更符合"打断纠正"的直觉 |
         * | 默认模型 = 内置 Agnes | `agent-default-model` | `provider: agnes` | 见下 |
         *
         * ## 为什么默认模型必须显式播种
         *
         * 引擎的出厂默认写在 bundle 层（`@deepseek-ai/dsh-base/cordis.patch.yml`）：
         *
         * ```yaml
         * - id: agent-default-model
         *   name: '@deepseek-ai/dsh-agent-default-model'
         *   config:
         *     provider: deepseek-official
         *     model: deepseek-flash
         * ```
         *
         * 也就是**官方模型**。而本 App 的用户大多是冲着内置号池来的 —— 他们没配过
         * 任何第三方 API，`deepseek-official` 对他们来说是个**必然失败**的路由
         * （没有凭据）。表现就是「第一次打开就报错，得自己去模型选择器里换一个」。
         *
         * ⚠️ **不能靠改 bundle 层**：那是引擎的包内文件，引擎升级就被覆盖。
         * ⚠️ **也不能只写进 [DEFAULT_SETTINGS_PATCH]**：那份只在「用户从未动过设置」
         *   时播种一次。而 `ensureKeyPoolProvider()` 会往同一个文件里补内置号池，
         *   于是**任何已装过的设备**都不再是空模板 ⇒ 播种被跳过 ⇒ 老用户永远
         *   拿不到这个默认值。所以老安装要由 [ensureDefaultModel] 单独补缺。
         *
         * ⚠️ `permission` 这项 GUI 会连同 `presets` 整块一起写出（实测），
         * 这里照抄同一形态，保证"用户之后在设置页改这一项"时 diff 最小。
         * ⚠️ 各项的 `name` 必须与引擎里该 entry 的包名一致，否则 patch 匹配不上。
         */
val DEFAULT_SETTINGS_PATCH = """
            # 由 DSH Android 客户端播种的出厂默认设置（仅首次安装时写入一次）。
            # 这是**你的**配置层：此后在设置页里的任何改动都写在这里，App 不再覆盖。
            # 想恢复出厂值：删掉本文件后重启 App。
            - id: ui-theme
              name: "@deepseek-ai/dsh-client-ui-theme"
              config:
                fontSize: 15
            - id: permission
              name: "@deepseek-ai/dsh-permission-presets"
              config:
                presets:
                  read-only:
                    sandbox: read-only
                    approval: ask
                  workspace-write:
                    sandbox: workspace-write
                    approval: ask
                  danger-full-access:
                    sandbox: danger-full-access
                    approval: never
                defaultPreset: danger-full-access
            - id: ui-chat
              name: "@deepseek-ai/dsh-client-ui-chat"
              config:
                transcriptView: standard
            - id: ui-conversation
              name: "@deepseek-ai/dsh-client-ui-conversation"
              config:
                busyEnter: steer
        """.trimIndent() + "\n"

        // ── 定向隔离层（L1.5） ────────────────────────────────────────
        //
        // 一个**纯禁用层**：只含 `- id: <条目 id>` + `disabled: true`，
        // 由 `RescueBoot.quarantinedNames()` 驱动，每次启动整体重写。
        //
        // ⚠️ 为什么用 `disabled` 而不是删文件/改 package.json：
        //   `cordis-plugin-include` 的 `applyEntryPatches` 把除
        //   `id`/`insert`/`name` 之外的键**原样赋给目标条目**
        //   （`for (const [key, value] of Object.entries(overrides)) target[key] = value`），
        //   而 `disabled` 正是 Cordis 条目的标准字段；`auditStartupEntries` 的注释也写明
        //   「disabled required entries are ignored」。所以这是**官方语义**，
        //   不需要改用户的任何文件，且随时可撤销（删掉本 overlay 即可）。
        //
        // ⚠️ 它必须是 overlay 列表的**最后一项**：`--patch` 按顺序应用，
        //   禁用一个由前面 `- insert:` 插入的条目，必须排在那条 insert 之后。
const val QUARANTINE_PATCH = "quarantine.patch.yml"

/**
     * 安全模式把第三方插件包挪到这里（profile 目录下，**改名不删**）。
     *
     * ⚠️ 必须是点开头的名字：`node_modules` 的扫描与 pnpm 的簿记都约定忽略点目录，
     * 万一将来有人把 `node_modules` 整个交给包管理器，它也不会被当成一个插件包。
     */
const val SAFE_MODE_PLUGIN_DIR = ".safe-mode-plugins"

