package com.dsh.android.engine.constants

import com.dsh.android.engine.BuiltinPluginSpec

/** 出厂 bundle、历史组件和 skill 的安装与迁移元数据。 */
        /**
         * 内置插件开关的存储位置。
         *
         * ## 为什么需要这个开关（用户 2026-10-27 反馈）
         *
         * 用户原话（荣耀 CRT-AN00）：
         *
         * > 当前 Android 客户端每次启动会自动挂载许多内置插件（如 bubble 悬浮球、
         * > shell、statusbar、ui、logs、generate_image/video 等）。其中 bubble 和
         * > ui 的描述极其巨大，光一个 bubble 就占用几千 token，不仅浪费免费 API
         * > 额度，还严重挤占上下文空间。希望能在设置里加一个插件管理面板，允许
         * > 用户按需开启/关闭这些内置插件。
         *
         * **这个数字是真的。** 实测各内置插件的工具 `description` 合计：
         *
         * | 插件 | 工具数 | 描述字符 |
         * |---|---|---|
         * | bubble-tool | 29 | 3248 |
         * | agnes-multimodal | 16 | 2095 |
         * | statusbar-tool | 10 | 1139 |
         * | core-memory | 6 | 630 |
         * | browser-tool | 6 | 612 |
         * | uiagent-tool | 6 | 489 |
         * | shell-tool | 4 | 397 |
         * | root-tool | 2 | 209 |
         *
         * 合计 8819 字符 ≈ **3000+ token**，而且是**每一次请求**都要带上。
         * 对用免费额度的用户，这不是小数目。
         *
         * ## 只有 `false` 才是关
         *
         * 默认值必须让所有插件保持现状（挂载），所以判据写成
         * `getBoolean(key, true)` —— 用户没动过的插件行为与升级前完全一致。
         * ⚠️ 只有显式存了 `false` 才跳过挂载，**不能反着写**，否则升级会把
         * 用户本来能用的工具全部关掉。
         */
const val PLUGIN_PREFS = "dsh_builtin_plugins"

        /**
         * 某个内置插件是否启用。默认 `true`（见 [PLUGIN_PREFS] 的注释）。
         *
         * @param context - 任意 context（内部用 application context 的 prefs）。
         * @param id - 插件 id，与 overlay 里的 `- id:` 一致，例如 `bubble-tool`。
         */
        val TOGGLEABLE_PLUGINS: List<Pair<String, String>> = listOf(
            "bubble-tool" to "悬浮球控制（29 个工具，3248 字符描述）",
            "agnes-multimodal" to "多模态图片/视频生成（16 个工具，2095 字符描述）",
            "statusbar-tool" to "状态栏配色（10 个工具，1139 字符描述）",
            "ui-tool" to "UI 调试工具（6 个工具，489 字符描述）",
            "browser-tool" to "浏览器工具（6 个工具，612 字符描述）",
            "logs-tool" to "日志工具",
            "browser-pane" to "右侧栏 AI 浏览器",
        )
        /** 移动端 UI 适配插件：包名、资产名，以及挂载它的 overlay 文件名。 */
const val MOBILE_UI_ASSET = "mobile-ui.zip"
const val MOBILE_UI_PACKAGE = "dsh-android-mobile"
const val MOBILE_UI_PATCH = "mobile-ui.patch.yml"

        /**
         * 插件解包目录，相对 `$DSH_HOME`。
         *
         * ⚠️ **刻意不放在 profile 的 `node_modules` 下** —— 那里归 pnpm 管，而本包
         * 不在 profile 的 `dependencies` 里（写了会去 registry 拉），于是**用户每装一次
         * 插件，pnpm 就把它挪进 `node_modules/.ignored/`**，`--patch` 里的包名随即解析
         * 失败 → 客户端半不编入 → 界面静默退回桌面布局。详见 ensureMobileUi()。
         */
const val MOBILE_UI_DIR = "mobile-ui"
const val MOBILE_SETTINGS_ASSET = "mobile-settings.zip"
const val HOST_BRIDGE_ASSET = "host-bridge.zip"
const val BALANCE_ASSET = "balance.zip"

const val ANDROID_BUNDLE_ASSET = "android-bundle.zip"
const val ANDROID_BUNDLE_PACKAGE = "dsh-android-bundle"

/** 只用于旧出厂组件迁移，不再独立登记到运行时。 */
internal val LEGACY_ANDROID_PLUGIN_COMPONENTS = listOf(
    BuiltinPluginSpec("mobile-ui", MOBILE_UI_PACKAGE, MOBILE_UI_ASSET, MOBILE_UI_DIR, MOBILE_UI_PATCH),
    BuiltinPluginSpec("mobile-settings", "dsh-android-mobile-settings", MOBILE_SETTINGS_ASSET),
    BuiltinPluginSpec("host-bridge", "dsh-android-host-bridge", HOST_BRIDGE_ASSET),
    BuiltinPluginSpec("balance", "dsh-android-balance", BALANCE_ASSET),
    BuiltinPluginSpec("browser-pane", BROWSER_PANE_PACKAGE, BROWSER_PANE_ASSET, BROWSER_PANE_DIR, BROWSER_PANE_PATCH),
    BuiltinPluginSpec("market", MARKET_PACKAGE, MARKET_ASSET, MARKET_DIR, MARKET_PATCH),
    BuiltinPluginSpec("shell-tool", SHELL_TOOL_PACKAGE, SHELL_TOOL_ASSET, SHELL_TOOL_DIR, SHELL_TOOL_PATCH),
    BuiltinPluginSpec("root-tool", ROOT_TOOL_PACKAGE, ROOT_TOOL_ASSET, ROOT_TOOL_DIR, ROOT_TOOL_PATCH),
    BuiltinPluginSpec("bubble-tool", BUBBLE_TOOL_PACKAGE, BUBBLE_TOOL_ASSET, BUBBLE_TOOL_DIR, BUBBLE_TOOL_PATCH),
    BuiltinPluginSpec("statusbar-tool", STATUSBAR_TOOL_PACKAGE, STATUSBAR_TOOL_ASSET, STATUSBAR_TOOL_DIR, STATUSBAR_TOOL_PATCH),
    BuiltinPluginSpec("uiagent-tool", UI_TOOL_PACKAGE, UI_TOOL_ASSET, UI_TOOL_DIR, UI_TOOL_PATCH, preferenceId = "ui-tool"),
    BuiltinPluginSpec("browser-tool", BROWSER_TOOL_PACKAGE, BROWSER_TOOL_ASSET, BROWSER_TOOL_DIR, BROWSER_TOOL_PATCH),
    BuiltinPluginSpec("logs-tool", LOGS_TOOL_PACKAGE, LOGS_TOOL_ASSET, LOGS_TOOL_DIR, LOGS_TOOL_PATCH),
    BuiltinPluginSpec("pet-bridge", PET_BRIDGE_PACKAGE, PET_BRIDGE_ASSET, PET_BRIDGE_DIR, PET_BRIDGE_PATCH),
)

/** 出厂 bundle 白名单；Android 能力在官方管理器中只有一个顶层包。 */
internal val BUILTIN_PLUGIN_BUNDLES = listOf(
    BuiltinPluginSpec("android-bundle", ANDROID_BUNDLE_PACKAGE, ANDROID_BUNDLE_ASSET),
    BuiltinPluginSpec("core-memory", CORE_MEMORY_PACKAGE, CORE_MEMORY_ASSET, CORE_MEMORY_LEGACY_DIR, CORE_MEMORY_PATCH, CORE_MEMORY_MARKER),
    BuiltinPluginSpec("preset-razor", PRESET_RAZOR_PACKAGE, PRESET_RAZOR_ASSET, PRESET_RAZOR_LEGACY_DIR, PRESET_RAZOR_PATCH, PRESET_RAZOR_MARKER, entryId = "preset-razor"),
)

        /** Agnes 多模态插件（dsh-agnes-multimodal）：资产名、包名、overlay 文件名。 */
const val AGNES_MM_ASSET = "agnes-multimodal.zip"
const val AGNES_MM_PACKAGE = "dsh-agnes-multimodal"
const val AGNES_MM_PATCH = "agnes-multimodal.patch.yml"

        /** 解包目录，相对 `$DSH_HOME`；与 [MOBILE_UI_DIR] 同理，刻意在 pnpm 管辖之外。 */
const val AGNES_MM_DIR = "agnes-multimodal"

        /** 插件内容版本戳：改了 `agnes-multimodal.zip` 里任何东西都必须改这里。 */
const val AGNES_MM_VERSION = "1.0.0-4"
const val AGNES_MM_STAMP = ".agnes_mm_version"

        /** 插件自己的账号池状态文件（`lib/index.js` 的 `readAccounts()` 读的就是它）。 */
const val AGNES_MM_STATE = "ag-multimodal.json"

        /**
         * 内置预设 dsh-preset-razor（「快准狠模式（通用）」）：资产名、包名、旧 overlay 文件名。
         *
         * ⚠️ 本包与其它插件不同：它**没有任何 JS 代码**，`cordis.patch.yml` 里就一行
         * `- insert:`，挂的是官方包 `@deepseek-ai/dsh-agent-preset`，携带的是一份
         * **预设定义**（工具组装 + 系统提示词）。
         *
         * 它和 dsh-core-memory 一样走 **profile bundle**（包放在 `$DSH_HOME/node_modules/`），
         * 而不是 `--patch` overlay —— 这样用户才能在「插件 → 已安装插件」里自己关掉。
         */
const val PRESET_RAZOR_ASSET = "preset-razor.zip"
const val PRESET_RAZOR_PACKAGE = "dsh-preset-razor"

        /**
         * 旧版本用它走主机 overlay；改造为 profile bundle 后只用于**清理残留**。
         * 老设备上这份文件已经存在，不删会被 `--patch` 再次挂上 ⇒ 同一个预设挂两遍，
         * registry 直接报 `Duplicate agent preset: razor`。
         */
const val PRESET_RAZOR_PATCH = "preset-razor.patch.yml"

        /** 改造前的解包目录，相对 `$DSH_HOME`；现在只用于清理残留。 */
const val PRESET_RAZOR_LEGACY_DIR = "preset-razor"

        /**
         * 「本 App 曾经把这个 bundle 登记进 profile」的一次性标记。
         *
         * ⚠️ 判据是「标记在不在」，不是「版本变没变」—— 它**不该**随版本 bump。
         * 一旦用户关掉这个预设，App 重启时绝不能把它加回去，否则就是「关不掉」。
         */
const val PRESET_RAZOR_MARKER = ".preset_razor_bundle_registered"

        /** 内容版本戳：改了 `preset-razor.zip` 里任何东西都必须改这里。 */
const val PRESET_RAZOR_VERSION = "1.0.4"
const val PRESET_RAZOR_STAMP = ".preset_razor_version"

        /**
         * 内置**中文 skill**（`dsh-mobile-theme` / `dsh-desktop-pet` 等）。
         *
         * 落在 `$DSH_HOME/skills/`（`USER_DSH_RANK=400` 的全局发现根），
         * 每个预设都会扫到，因此无需改任何 `cordis.patch.yml`。见 [ensurePresetSkills]。
         */
const val PRESET_SKILLS_ASSET = "preset-skills.zip"

        /** 内容版本戳：改了 `preset-skills.zip` 里任何东西都必须改这里。 */
const val PRESET_SKILLS_VERSION = "1.0.0-3"
const val PRESET_SKILLS_STAMP = ".preset_skills_version"

        /**
         * 本 App 管的 skill 子目录名 —— [ensurePresetSkills] 只删这几个再重解包。
         *
         * ⚠️ **必须与 zip 里的一级目录名逐字一致**，且**不得包含用户可能自己写的名字**：
         * `$DSH_HOME/skills/` 是用户自己的目录，删错就是删用户的东西。
         * 新增一份内置 skill 时，这里和 zip 内容要同时改。
         */
val PRESET_SKILL_NAMES = listOf("dsh-mobile-theme", "dsh-desktop-pet")

        /** 跨会话持久记忆插件（dsh-core-memory）：资产名、包名、旧 overlay 文件名。 */
const val CORE_MEMORY_ASSET = "core-memory.zip"
const val CORE_MEMORY_PACKAGE = "dsh-core-memory"

        /**
         * 旧版本用它走主机 overlay；改造为 profile bundle 后只用于**清理残留**。
         * 老设备上这份文件已经存在，不删会被 `--patch` 再次挂上 ⇒ 同一个包挂两遍。
         */
const val CORE_MEMORY_PATCH = "core-memory.patch.yml"

        /**
         * 「本 App 曾经把这个 bundle 登记进 profile」的一次性标记。
         *
         * ⚠️ 它存在的唯一理由是**别把用户关掉的插件又打开**：`dsh.profile.bundles`
         * 是用户与 App 共写的，若每次启动都「不在就补」，用户在插件页关掉、重启 App
         * 就又回来了 —— 那等于关不掉。有了这个标记，App 只在**从没见过它**时补一次。
         *
         * 不随版本变化：它不是内容版本戳，删掉会导致 App 重新「补一次」。
         */
const val CORE_MEMORY_MARKER = ".core_memory_bundle_registered"

        /**
         * 改造前的解包目录（相对 `$DSH_HOME`），只用于清理残留。
         * ⚠️ 别把它和 `$DSH_HOME/memory/` 混淆：那个是用户的记忆数据，永远不删。
         */
const val CORE_MEMORY_LEGACY_DIR = "core-memory"

        /** 内容版本戳：改了 `core-memory.zip` 里任何东西都必须改这里。 */
const val CORE_MEMORY_VERSION = "1.0.0-2"
const val CORE_MEMORY_STAMP = ".core_memory_version"

        /** 插件市场（dsh-android-market）：资产名、包名、overlay 文件名。 */
const val MARKET_ASSET = "market-plugin.zip"
const val MARKET_PACKAGE = "dsh-android-market"
const val MARKET_PATCH = "market.patch.yml"

        /** 解包目录，相对 `$DSH_HOME`；与 [MOBILE_UI_DIR] 同理，刻意在 pnpm 管辖之外。 */
const val MARKET_DIR = "market"

        /**
         * 内容版本戳：改了 `market-plugin.zip` 里任何东西都必须改这里。
         *
         * ⚠️ 尤其**重跑 `tools/market-collect.mjs` 更新了 `lib/seed.json` 之后**
         * 也要改 —— 否则老设备会继续用包里那份旧种子。
         */
const val MARKET_VERSION = "1.0.0-6"
const val MARKET_STAMP = ".market_version"

