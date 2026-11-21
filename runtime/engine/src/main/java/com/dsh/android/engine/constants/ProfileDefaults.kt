package com.dsh.android.engine.constants

/** Web profile、overlay 路径和用户层默认配置。 */
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

/** 正常引擎启动使用的 profile。 */
const val PROFILE_NAME = "web"

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
