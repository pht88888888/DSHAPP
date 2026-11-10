package com.dsh.android.engine.constants

import android.content.Context
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import com.dsh.android.engine.BuiltinPluginSpec

/**
 * 引擎常量与无状态配置数据。
 *
 * 从 [EngineManager] 的 companion object 中抽出：全部为编译期常量或
 * 只读数据，不依赖任何运行期状态（实例、日志缓冲都不在这）。
 *
 * 保持为 `internal object` 而非常量顶层：这样调用方在工程内部可以
 * `import com.dsh.android.engine.EngineConstants.*` 之后继续裸用常量名，
 * 外部（引擎插件、测试）也能通过 `EngineConstants.X` 显式引用。
 */
const val TAG = "DSH"

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
        // 注意：不能用 .tar.gz 作后缀 —— AGP 会把它当作 gzip 资源自动解压并改名为 .tar，
        // 导致运行时按原名找不到。用中性后缀避免 AGP 的格式推断。
const val ASSET_NAME = "engine.bin"
const val READY_TIMEOUT_MS = 120_000L
const val MAX_LOG_LINES = 400

        /**
         * 堆水位监控的检查间隔。
         *
         * 取 3 分钟：泄漏速率实测约 130 MB/小时 ≈ 2.2 MB/分钟，3 分钟只涨
         * 约 6.5 MB —— 相对水位余量（见 [heapRestartRatio]）完全可以忽略，
         * 而检查一次只是读一个 `/proc/<pid>/status` 里的一行，代价可忽略。
         * 不要调得更勤：那对判据没有帮助，只是白唤醒。
         */
const val HEAP_WATCH_INTERVAL_MS = 3 * 60_000L

        /**
         * 触发主动重启的水位（占堆上限的比例）。
         *
         * 为什么是 0.85：
         * · 从判定到重启完成，引擎还在按 2.2 MB/分钟继续涨。`requestRestart`
         *   要走「停 → 起 → 等 ready」的完整状态机，真机上实测 ready 要
         *   10–29 秒，也就是约 0.5–1 MB，相对余量（15% of 3072 ≈ 460 MB）绰绰有余；
         * · 不能取 0.95 以上：V8 在逼近上限时 GC 会退化成近乎停顿（真机日志里
         *   `Mark-Compact 1903.46 / 0.00 ms`，单次 GC 近 2 秒），界面先卡死
         *   再重启，体验反而更差；
         * · 不要取 0.7 以下：那会让 3 GB 的机器每隔几小时就重启一次，太激进。
         */
val URL_PATTERN =
            Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+)""")

        /**
         * 一次试启动（[probeBoot]）的上限。
         *
         * 真机实测失败路径 17~22 秒就返回了，所以 60 秒足够。
         * ⚠️ **刻意比 [READY_TIMEOUT_MS]（120 秒）短**：二分法要跑很多轮，
         *   每轮都按 120 秒算会让总时长失控（上限是 3 分钟，见 §4.3）。
         */
const val PROBE_TIMEOUT_MS = 60_000L

        /**
         * L1 自动隔离的**总时长上限**（方案 §4.3）。
         *
         * 3 分钟的依据：单次失败实测 17~22 秒，基线 1 次 + 二分 ⌈log2(40)⌉ = 6 轮
         * + 收尾 1 次 ≈ 8 次 × 20 秒 ≈ 2.7 分钟。取 3 分钟刚好够走完，
         * 又不会让用户对着"正在启动…"等太久。
         *
         * ⚠️ 超预算必须**优雅放弃**并保留已确认的结论，绝不能无限试下去 ——
         *   那会把「App 打不开」变成「App 一直显示正在启动」，比原来更糟。
         */
const val ISOLATION_BUDGET_MS = 3 * 60 * 1000L

        /**
         * 启动前体检（[preflight]）的超时。
         *
         * 真机实测正常态耗时 0.7~0.8 秒；给到 30 秒是为了容忍慢卡（体检只是
         * 保险，超时即跳过、不影响启动，所以宁可给宽一点）。
         */
const val PREFLIGHT_TIMEOUT_MS = 30_000L

        /**
         * 自动进安全模式所需的**连续真实启动失败**次数（方案 §4.5 的 P6）。
         *
         * 取 3 的依据：1~2 次失败可能是偶发（系统内存紧张导致引擎被 OOM、
         * 端口被上一次残留进程占着），那时推开用户的插件配置是**过度反应** ——
         * 用户会莫名其妙丢掉所有插件。而 3 次连续失败（每次都是完整的启动流程、
         * 都走到「引擎没打印 URL」）已经排除了偶发。
         *
         * ⚠️ 这个数**只是三个条件之一**，见 [shouldAutoEnterSafeMode]：
         *   单独满足它不会触发，还必须 `tier == RESCUE`（= L1/L2 都试过）。
         */
const val AUTO_SAFE_MODE_MIN_FAILURES = 3

        /**
         * `requiredStartupEntryIds` 的副本 —— 与 `dsh-app-boot/lib/index.js` 里的
         * 那个 Set **同源**，少一个引擎就起不来（`auditStartupEntries` 会抛
         * `StartupError` 并以 exit 1 结束）。
         *
         * ⚠️ 这几个 id 不是「重要插件」的意思，而是「缺了整个树就废了」：
         * 真机实测删掉 `dsh-web-app` 的 `cordis.patch.yml` 后，`webserver` /
         * `modules` / `connection` 三个条目全部消失，引擎 boot 时挂死（45 秒超时）。
         * 引擎升级若增删了这个列表，本副本要跟着改 —— 漏改只会让体检**少报**，
         * 不会误伤（体检判据是「缺了才隔离」）。
         */
val REQUIRED_ENTRY_IDS = listOf(
            "agent-loop",
            "tool-executor-native",
            "tool-executor-ptc",
            "webserver",
            "web-runtime",
            "modules",
            "connection",
        )

        /**
         * 从引擎报错行里抠出被指认的文件绝对路径。
         *
         * 覆盖三种官方措辞（真机原文）：
         * - `failed to parse overlay <path>:`
         * - `failed to read overlay <path>:`
         * - `failed to read profile manifest <path>:`
         *
         * 路径一定在 `/` 开头（本 App 的 `$DSH_HOME` 与 `engine/` 都是绝对路径），
         * 所以用 `(/\S+)` 而不是更宽松的匹配 —— 报错行后面还跟着 YAML 片段。
         */
val BLAMED_PATH =
            Regex("""failed to (?:parse|read) (?:overlay|profile manifest) (/\S+?):""")

        /**
         * 引擎 Web GUI 的默认监听端口。
         *
         * 出处：`@deepseek-ai/dsh-web-app/cordis.patch.yml` 的
         * `port: !!js ctx.webStartup.port ?? 3080` —— App 不传 `--port` 时落到的就是它。
         *
         * 被占用时改传 `--port 0` 让内核挑一个空闲端口，见 [isLoopbackPortBusy]。
         */
const val DEFAULT_WEB_PORT = 3080

        /** WebView 兼容补丁：资产名与注入到 index.html 里的标签。 */
const val POLYFILL_FILE = "webview-polyfill.js"
const val POLYFILL_TAG = """<script src="./$POLYFILL_FILE"></script>"""

        /**
         * 引擎侧 HTTP header 净化垫片（见 [ensureFetchGuard]）。
         *
         * 解到 `filesDir` 根下（与 `dsh-android-shell.mjs` 同一层），通过
         * `NODE_OPTIONS=--import <绝对路径>` 预加载进**引擎进程**。
         *
         * ⚠️ 必须用**绝对路径**：`--import` 的裸标识符会走 Node 的模块解析，
         * 而引擎进程的 cwd 是 `engine/`，相对路径会解析到那里去。
         */
const val FETCH_GUARD_ASSET = "dsh-fetch-guard.mjs"
const val FETCH_GUARD_FILE = "dsh-fetch-guard.mjs"

        /**
         * 按旧内核降级编译的前端 bundle（见 [ensureWebViewCompat]）。
         *
         * 由 `tools/build-webview-compat.ps1` 生成，含 dist 与**各插件的 client bundle**
         * 两部分的降级产物（老版本只含 dist，实测漏了 71 个插件里 68 个的语法缺口）。
         * **换了 engine.bin 必须重跑脚本**。
         */
const val WEBVIEW_COMPAT_ASSET = "webview-compat.zip"

        /** 降级资产在 dist 里留下的戳，以及 zip 内对应的条目名。 */
const val WEBVIEW_COMPAT_STAMP = ".webview-compat-stamp"
const val WEBVIEW_COMPAT_STAMP_ENTRY = ".compat-stamp"

        /**
         * 降级 zip 的条目树前缀 —— 决定每个条目解到哪棵树上（见 [ensureWebViewCompat]）。
         *
         * 两个前缀必须与构建工具一致；插件使用组件构建的降级产物，禁止由兼容大包覆盖。
         */
const val COMPAT_PREFIX_DIST = "dist"
const val COMPAT_PREFIX_ENGINE = "engine"

        /** 自编译的 android-arm64 原生插件（官方无此变体）。 */
const val ADDON_ASSET = "node-addon-android-arm64.zip"
const val ADDON_PACKAGE = "node-addon-require-builtin-android-arm64"

        /** system 插件（会话写入锁 flock）：同样是自编译的 android-arm64 变体。 */
const val SYSTEM_ADDON_ASSET = "node-addon-system-android-arm64.zip"
const val SYSTEM_ADDON_PACKAGE = "@deepseek-ai/node-addon-system-android-arm64"

        /**
         * flock.js 里平台白名单的原样文本，以及放行 android 之后的文本。
         * 两者都必须与官方发布的 flock.js 逐字一致；引擎升级后若此处不再匹配，
         * [patchFlockPlatform] 会跳过并在日志里提示，不会让启动失败。
         */
const val FLOCK_PLATFORM_GUARD =
            "if (platform !== 'linux' && platform !== 'darwin') {"
const val FLOCK_ANDROID_ANCHOR =
            "if (platform !== 'linux' && platform !== 'darwin' && platform !== 'android') {"

        /**
         * `dsh-app-boot/lib/index.js` 里 `installFailLoud` 的两处 `proc.exit(1);`，
         * 以及换成「什么也不做」之后的文本。见 [patchFailLoudExit]。
         *
         * 实测该文件全文**只有这 2 处** `proc.exit(1);`，且都在 `installFailLoud`
         * 的 `report()` 里（一条在 `release === void 0` 分支，一条在 release 收尾）。
         * 所以整文件替换是安全的，不会波及别处。
         *
         * 替换成一句块注释而不是删成空串：`report` 里那句 stderr 已经写出了完整的
         * fatal 栈（进 `logs/engine.log`），这里不需要重复记录。
         */
const val FAIL_LOUD_EXIT_GUARD = "proc.exit(1);"
const val FAIL_LOUD_ANDROID_ANCHOR =
            "/* DSH-ANDROID-KEEP-ALIVE: 插件崩溃不再杀死整个引擎 */"

        /** 幂等标记：出现在替换**之后**的文本里，官方原文中不会有。 */
const val FAIL_LOUD_ANDROID_MARK = "DSH-ANDROID-KEEP-ALIVE"

        /**
         * `dsh/lib/profile-boot-BZ2ZjNWi.js` 传给 `installFailLoud` 的 release 回调
         * 原文，以及换成空函数之后的文本。见 [patchFailLoudExit]。
         *
         * ⚠️ **这一处必须和第 1 步一起改**。只改 `proc.exit(1)` 的话，release 仍会把
         *   整个应用 fiber 树 dispose 掉，node 没有活干自然退出 —— 表现为
         *   「退出码从 1 变成 0，但引擎照退」，实测踩过。
         *
         * ⚠️ 文件名带构建 hash（`profile-boot-BZ2ZjNWi.js`），换引擎版本会变。
         *   所以 [patchFailLoudExit] 先按固定名找，找不到再按前缀扫目录兜底。
         */
const val FAIL_LOUD_RELEASE_GUARD =
            "await app.current?.fiber.dispose();"
const val FAIL_LOUD_RELEASE_ANCHOR =
            "/* DSH-ANDROID-KEEP-ALIVE-RELEASE: 插件崩溃不再 dispose 整个应用 */"

        /** 幂等标记：第 2 步的替换结果。 */
const val FAIL_LOUD_RELEASE_MARK = "DSH-ANDROID-KEEP-ALIVE-RELEASE"

        /**
         * `dsh-terminal-bash/lib/index.js` 里 `DEFAULT_BASH_SHELL` 的原样文本，
         * 以及换成 Termux bash 之后的文本。见 [patchTerminalShell]。
         *
         * ⚠️ `/bin/bash` 在 Android 上不存在（`/bin -> /system/bin` 里只有 mksh），
         * 不改这里 `bash` 工具每条命令都会以 `PTY shell exited during startup` 失败。
         * 两者都必须与官方发布件逐字一致；引擎升级后若此处不再匹配，
         * [patchTerminalShell] 会跳过并在日志里提示，不会让启动失败。
         *
         * 替换值用 `process.env.PREFIX` 拼，不写死绝对路径：`PREFIX` 由 [start]
         * 传给引擎，这样不依赖包名或目录布局；取不到时回落 `/bin/sh`（一定存在）。
         */
const val TERMINAL_SHELL_GUARD =
            "const DEFAULT_BASH_SHELL = \"/bin/bash\";"
const val TERMINAL_SHELL_ANCHOR =
            "const DEFAULT_BASH_SHELL = process.env.PREFIX ? process.env.PREFIX + \"/bin/bash\" : \"/bin/sh\";"

        /**
         * `dsh-attachment-local/lib/index.js` 里 `syncDirectory()` 的原样文本，
         * 以及容忍祖先目录打不开之后的文本。见 [patchAttachmentDurability]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
const val ATTACH_SYNC_GUARD =
            "\tconst handle = await open(path, constants.O_RDONLY);"
const val ATTACH_SYNC_ANCHOR =
            "\tlet handle;\n" +
                "\ttry {\n" +
                "\t\thandle = await open(path, constants.O_RDONLY);\n" +
                "\t} catch (err) {\n" +
                "\t\t/* Android: 祖先目录（/data、/data/user/0 等）被 SELinux 拒绝 open，\n" +
                "\t\t   而这些目录项的崩溃持久性在应用沙箱内本来就不可达，跳过即可。 */\n" +
                "\t\tif (err && (err.code === \"EACCES\" || err.code === \"EPERM\")) return;\n" +
                "\t\tthrow err;\n" +
                "\t}"

        /**
         * `handle.sync()` 那段的原样文本与容错版本。见 [patchAttachmentDurability]。
         *
         * **这是「发图报 session/agent-busy」的真正根因，只补 `open` 是不够的。**
         *
         * 因果链：`ensureDurableHome` 把持久化边界算成 `parse(home).root`（恒为 `/`），
         * `ensureDurableDirectory` 于是逐级上溯到 `/`，对只读分区做目录 `fsync`。
         * Android 的 `/` 是 erofs **只读**挂载，内核对只读文件系统上的目录 `fsync`
         * 返回 **`EINVAL`**（这是 Linux 标准行为，不是某台机器的毛病）。
         * 而原版 `sync()` 只有 `try/finally`、**没有 `catch`**，`EINVAL` 直接穿透 →
         * 附件落盘失败 → `session-controller` 兜底成 `session/agent-busy`。
         *
         * 表现上的迷惑点：**纯文字完全正常**。因为 `admitPromptContent` 里
         * `if (content.every((part) => part.type !== "image")) return;` ——
         * 只有图片才走附件落盘。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
const val ATTACH_FSYNC_GUARD =
            "\ttry {\n" +
                "\t\tawait handle.sync();\n" +
                "\t} finally {\n" +
                "\t\tawait handle.close();\n" +
                "\t}"
const val ATTACH_FSYNC_ANCHOR =
            "\ttry {\n" +
                "\t\tawait handle.sync();\n" +
                "\t} catch (err) {\n" +
                "\t\t/* Android: / 是 erofs 只读挂载，对只读文件系统上的目录做 fsync，\n" +
                "\t\t   内核返回 EINVAL（Linux 标准行为）。这类目录项本进程根本改不动，\n" +
                "\t\t   跳过即可 —— 否则整条附件落盘失败，用户看到的是「会话忙」。 */\n" +
                "\t\tif (err && (err.code === \"EINVAL\" || err.code === \"EACCES\" || err.code === \"EPERM\")) return;\n" +
                "\t\tthrow err;\n" +
                "\t} finally {\n" +
                "\t\tawait handle.close();\n" +
                "\t}"

        /**
         * 发图格式白名单的原版判断（精确比较 4 个 MIME）。
         *
         * ⚠️ 缩进是**三个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。见 [patchImageMediaType]。
         */
        /*
         * ⚠️ **锚点里不能带缩进**（2026-10-27 真机取证）。
         *
         * 原来这里是 `"\t\t\treturn value === \"image/png\" || …"` —— 写死 3 个
         * 制表符。而真机（HUAWEI CDY-TN90 / vc 29）上该文件的实际内容是
         * **6 个空格**缩进：
         *
         *     4861:      return value === "image/png" || … ;
         *     实测 `cat -A` → "      return value === … ;$"     ← 6 空格
         *     源码常量        → "^I^I^Ireturn value === … ;"     ← 3 制表符
         *
         * 于是 `source.contains(IMG_TYPE_GUARD)` 恒为 false，补丁**从未生效过**，
         * 每次启动都在 app.log 里留下一条「发图格式判断结构与预期不符，跳过补丁」。
         * 用户侧的表现就是那张表格里的问题原样存在：`.jpg`（`image/jpg`）、
         * iPhone 的 HEIC、部分相册的 BMP/AVIF、剪贴板粘贴的空 type
         * 全被拒，提示「仅支持 PNG、JPG、WebP、GIF」。
         *
         * 压缩产物里缩进本来就不可靠（不同版本的 minifier / 格式化配置都会改），
         * 所以锚点只锚**语句内容**，由 [patchImageMediaType] 用行级方式替换。
         */
const val IMG_TYPE_GUARD =
            "return value === \"image/png\" || value === \"image/jpeg\" || " +
                "value === \"image/webp\" || value === \"image/gif\";"

        /**
         * 放宽后的判断：任何 `image/` 前缀 + `image/jpg` 别名都放行。
         *
         * 解码能力交给下游 sharp 判断 —— 它会给明确错误，
         * 比在入口处一刀切拦住友好（见 [patchImageMediaType] 的表格）。
         */
        /**
         * 补丁要写入的**语句主体**（不含行首缩进）。
         *
         * ⚠️ 缩进由 [patchImageMediaType] 从被替换那一行的真实前缀里取，
         *   不写死 —— 原因见 [IMG_TYPE_GUARD] 的注释（真机上是 6 空格，
         *   而这里原来写的是 3 个制表符）。
         */
const val IMG_TYPE_ANCHOR =
            "if (typeof value !== \"string\") return false;\n" +
                "/* DSH Android: 放宽到任何 image/ 前缀。原版只认 4 个精确值，\n" +
                "   而 image/jpg、image/heic、image/avif、image/bmp 以及剪贴板粘贴\n" +
                "   的空 type 都会被误拒，用户看到的是「仅支持 PNG、JPG、WebP、GIF」。\n" +
                "   真正的解码能力由下游 sharp 判断，那里报错更准确。 */\n" +
                "return value.startsWith(\"image/\");"

        /**
         * 凭据文件名（`dsh-credentials-local` 的 `CREDENTIALS_FILENAME`，写死，
         * 因为它是引擎的公开约定而不是可配置项）。见 [ensureCredentialFileMode]。
         */
const val CREDENTIALS_FILE = ".credentials.yaml"

        /**
         * 凭据文件唯一被接受的权限位：`0600`（仅属主可读写）。
         *
         * 见 [ensureCredentialFileMode]。写入值写死成 `0600` 而不是「把过宽的位
         * 减掉」：引擎自己写这个文件时用的就是 `writeFileAtomic(..., { mode: 0o600 })`，
         * `0600` 才是它认得的规范形状。
         */
val OWNER_ONLY_PERMISSIONS: Set<PosixFilePermission> =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /**
         * 引擎 `assertOwnerOnly()` 的判据位：组 + 其他的全部 9 位，即 `0o077`。
         *
         * `dsh-credentials-local/lib/index.js` 里是
         * `const GROUP_OTHER_BITS = 63;` + `if ((mode & GROUP_OTHER_BITS) === 0) return;`。
         * **只拒绝这 9 位非零**，不关心属主执行位（所以 `0700` 是它接受的文件）。
         * 我们照抄这个判据，免得去改一个引擎本来就接受的文件。
         */
val GROUP_OTHER_BITS: Set<PosixFilePermission> =
            EnumSet.of(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            )

        /**
         * `dsh-llm-deepseek/lib/index.js` 里 `assistant()` 映射的**首尾两行原文**，
         * 以及把 `tool_use` 块收敛到末尾之后的文本。见 [patchLlmDeepseekToolOrder]。
         *
         * 分首尾两处替换（而不是整段替换）：中间是一个完整的 `switch`，整段照抄
         * 容易在引擎升级时因为无关的空白/注释变化而失配，只锚定首尾两行更稳。
         *
         * ⚠️ **重排方向是「置尾」而不是「置首」**：assistant 的 content 里可能有
         * 带签名的 `thinking` 块，而 Messages API 要求 thinking 排在 assistant
         * content 的**最前**。把 `tool_use` 提到最前会让 thinking 落到它后面，
         * 可能触发签名校验失败。收敛到末尾得到的 `[thinking, text, tool_use…]`
         * 才是规范形状。
         *
         * ⚠️ 缩进是**制表符**（首行 1 个、尾行 3/2/1/0 个），与官方发布件逐字一致；
         * 引擎升级后若不再匹配，补丁会跳过并在日志里提示，不会让启动失败。
         *
         * ⚠️ **0.2.1-rc.2 起首行从 `map` 变成了 `flatMap`**（空 text 块返回 `[]`
         *   靠 flatMap 展开）。锚点只认新文本 —— 引擎已固定为 0.2.1，老文本不再需要，
         *   漏改的后果是补丁静默跳过、assistant 的 tool_use 顺序又变回交错，
         *   ⑨.5 那个「会话永久发不出去」的故障会复发。
         */
const val TOOL_ORDER_GUARD_HEAD =
            "\treturn message.content.flatMap((block, index) => {"
const val TOOL_ORDER_ANCHOR_HEAD =
            "\tconst mapped = message.content.flatMap((block, index) => {"

const val TOOL_ORDER_GUARD_TAIL =
            "\t\t\tdefault: return unsupported(`assistant content \${block.type}`);\n" +
                "\t\t}\n" +
                "\t});\n" +
                "}"
const val TOOL_ORDER_ANCHOR_TAIL =
            "\t\t\tdefault: return unsupported(`assistant content \${block.type}`);\n" +
                "\t\t}\n" +
                "\t});\n" +
                "\t/* Android: DeepSeek Messages 要求 assistant 的 tool_use 块连续，且紧后\n" +
                "\t   必须携带全部 tool_result。持久化历史里 assistant 的块是交错的\n" +
                "\t   （text 夹在多个 tool_use 之间），原样发出会被 API 以\n" +
                "\t   \"tool_use ids were found without tool_result blocks\" 拒绝，\n" +
                "\t   且该历史从此永久发不出去。这里把 tool_use 收敛到末尾，\n" +
                "\t   其余块保持原有相对顺序（thinking 必须留在最前）。\n" +
                "\t   只改本次请求体，不改持久化历史，replay 签名已在映射时贴好。 */\n" +
                "\tconst calls = mapped.filter((block) => block.type === \"tool_use\");\n" +
                "\tif (calls.length === 0) return mapped;\n" +
                "\treturn [...mapped.filter((block) => block.type !== \"tool_use\"), ...calls];\n" +
                "}"

        /**
         * `dsh-llm-pi-ai/lib/index.js` 里 tool-call 事件的**首尾两处原文**，
         * 以及给空 id 兜底之后的文本。见 [patchLlmPiAiToolCallId]。
         *
         * ⚠️ 两处都要改，缺一不可：
         * - `toolcall_delta` 显式造空串（`known?.id ?? ""`），它经 `partial.toolCallId`
         *   进入组装器；
         * - `toolcall_end` 直接造整个 block（`id: brandString(event.toolCall.id)`），
         *   经 `partial.block` 短路返回，**绕过组装器的 `??` 兜底**。
         *
         * 兜底值用 `call-<index>` 形状（与 `dsh-llm` 组装器原生的兜底命名一致），
         * 保证 provider 不给 id 时仍有稳定的、非空的调用标识。
         *
         * ⚠️ 缩进是**制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
const val PI_AI_TOOLID_GUARD_START =
            "\t\t\t\tid: brandString(known?.id ?? \"\"),\n"
const val PI_AI_TOOLID_ANCHOR_START =
            "\t\t\t\t/* Android: 第三方路由（实测 agnes/q）在流式响应里可能不给 tool_call id，\n" +
                "\t\t\t\t   原样透出会让 tool-call 块拿到空 id，落盘后该会话永久不可读。\n" +
                "\t\t\t\t   这里补一个稳定的非空兜底。 */\n" +
                "\t\t\t\tid: brandString(known?.id || `call-\${event.contentIndex}`),\n"

const val PI_AI_TOOLID_GUARD_END =
            "\t\t\t\t\tid: brandString(event.toolCall.id),\n"
const val PI_AI_TOOLID_ANCHOR_END =
            "\t\t\t\t\t/* Android: 同 toolcall_delta —— provider 不给 id 时兜底，\n" +
                "\t\t\t\t\t   否则这个 block 会短路返回、绕过组装器的 ?? 兜底。 */\n" +
                "\t\t\t\t\tid: brandString(event.toolCall.id || `call-\${event.contentIndex}`),\n"

        /**
         * `dsh-llm/lib/index.js` 里 `assemble()` 组装 tool-call 块的那一行原文，
         * 以及空串也能兜住的文本。见 [patchLlmToolCallIdFallback]。
         *
         * ⚠️ 原文用 `??`，**只兜 `null`/`undefined`**；而 pi-ai 的 delta 路径
         * 显式产生空串 `""`，`??` 放行 → 空 id 落盘 → 会话永久不可读。
         * 改成 `||` 让空串也走兜底。
         *
         * ⚠️ 缩进是**四个制表符**，与官方发布件逐字一致。
         */
const val LLM_TOOLID_GUARD =
            "\t\t\t\tid: partial.toolCallId ?? brandString(`call-\${index}`),\n"
const val LLM_TOOLID_ANCHOR =
            "\t\t\t\t/* Android: `??` 兜不住空串，而 pi-ai 的 delta 路径会产生 \"\"，\n" +
                "\t\t\t\t   空 id 落盘后整个会话都读不出来。改用 `||` 连空串一起兜。 */\n" +
                "\t\t\t\tid: partial.toolCallId || brandString(`call-\${index}`),\n"

        /**
         * `dsh-host-directory-picker-browse/lib/index.js` 里 `list()` 决定默认起点
         * 的那一行（[PICKER_START_GUARD]），以及曾经把它指向手机存储的文本
         * （[PICKER_START_ANCHOR]，现在只用于**撤回**）。见 [restoreDirectoryPickerStart]。
         *
         * ⚠️ 两个锚点都必须逐字保留：`GUARD` 是官方原文（撤回时写回去的内容），
         * `ANCHOR` 是旧版本打进用户设备的内容（撤回时用来识别）。少一个，
         * 老安装就永远停在 `/sdcard` 起点上。
         *
         * ⚠️ `const home = homedir();` 那一行**从头到尾没被碰过** —— 返回给客户端的
         * `home` 字段靠它，客户端拿 `home` 折叠面包屑。入口链接方案能work，正是
         * 因为起点回到了 `home`。
         *
         * ⚠️ 缩进是**两个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 撤回会跳过并在日志里提示，不会让启动失败。
         */
const val PICKER_START_GUARD =
            "\t\tconst target = resolve(path ?? home);"
const val PICKER_START_ANCHOR =
            "\t\tconst target = resolve(path ?? (process.platform === 'android' ? '/sdcard' : home));"

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

/** 出厂 bundle 白名单；id 同资产 manifest 与 dsh.android.builtinId。 */
internal val BUILTIN_PLUGIN_BUNDLES = listOf(
    BuiltinPluginSpec("mobile-ui", MOBILE_UI_PACKAGE, MOBILE_UI_ASSET, MOBILE_UI_DIR, MOBILE_UI_PATCH),
    BuiltinPluginSpec("mobile-settings", "dsh-android-mobile-settings", MOBILE_SETTINGS_ASSET),
    BuiltinPluginSpec("host-bridge", "dsh-android-host-bridge", HOST_BRIDGE_ASSET),
    BuiltinPluginSpec("balance", "dsh-android-balance", BALANCE_ASSET),
    BuiltinPluginSpec("browser-pane", BROWSER_PANE_PACKAGE, BROWSER_PANE_ASSET, BROWSER_PANE_DIR, BROWSER_PANE_PATCH),
    BuiltinPluginSpec("market", MARKET_PACKAGE, MARKET_ASSET, MARKET_DIR, MARKET_PATCH),
    BuiltinPluginSpec("core-memory", CORE_MEMORY_PACKAGE, CORE_MEMORY_ASSET, CORE_MEMORY_LEGACY_DIR, CORE_MEMORY_PATCH, CORE_MEMORY_MARKER),
    BuiltinPluginSpec("preset-razor", PRESET_RAZOR_PACKAGE, PRESET_RAZOR_ASSET, PRESET_RAZOR_LEGACY_DIR, PRESET_RAZOR_PATCH, PRESET_RAZOR_MARKER, entryId = "preset-razor"),
    BuiltinPluginSpec("shell-tool", SHELL_TOOL_PACKAGE, SHELL_TOOL_ASSET, SHELL_TOOL_DIR, SHELL_TOOL_PATCH),
    BuiltinPluginSpec("root-tool", ROOT_TOOL_PACKAGE, ROOT_TOOL_ASSET, ROOT_TOOL_DIR, ROOT_TOOL_PATCH),
    BuiltinPluginSpec("bubble-tool", BUBBLE_TOOL_PACKAGE, BUBBLE_TOOL_ASSET, BUBBLE_TOOL_DIR, BUBBLE_TOOL_PATCH),
    BuiltinPluginSpec("statusbar-tool", STATUSBAR_TOOL_PACKAGE, STATUSBAR_TOOL_ASSET, STATUSBAR_TOOL_DIR, STATUSBAR_TOOL_PATCH),
    BuiltinPluginSpec("uiagent-tool", UI_TOOL_PACKAGE, UI_TOOL_ASSET, UI_TOOL_DIR, UI_TOOL_PATCH, preferenceId = "ui-tool"),
    BuiltinPluginSpec("browser-tool", BROWSER_TOOL_PACKAGE, BROWSER_TOOL_ASSET, BROWSER_TOOL_DIR, BROWSER_TOOL_PATCH),
    BuiltinPluginSpec("logs-tool", LOGS_TOOL_PACKAGE, LOGS_TOOL_ASSET, LOGS_TOOL_DIR, LOGS_TOOL_PATCH),
    BuiltinPluginSpec("pet-bridge", PET_BRIDGE_PACKAGE, PET_BRIDGE_ASSET, PET_BRIDGE_DIR, PET_BRIDGE_PATCH),
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

        /** 创造模式（`cordis` 预设）的官方文件路径，相对 `engineDir`。 */
const val PRESET_CORDIS_PRESET_PATH =
            "node_modules/@deepseek-ai/dsh-web-app/presets/cordis.patch.yml"

        /**
         * 官方 persona 原文（**锚点**，必须与引擎里的逐字一致才会被替换）。
         *
         * 来自 `dsh-web-app/presets/cordis.patch.yml`：
         *
         * ```yaml
         *           - id: persona
         *             name: '@deepseek-ai/dsh-persona'
         *             config:
         *               prefix: >-
         *                 You are a coding agent powered by the {{model}} model.
         * ```
         *
         * ⚠️ **0.2.1-rc.2 起官方删掉了 `suffix: Your working directory is {{cwd}}.`
         *   这一行**（工作目录改由 `time-context` / 工具描述承载），锚点必须跟着删，
         *   否则整个 GUARD 匹配不上 → 补丁静默跳过 → 「快准狠」提示词不再注入。
         *   实测就是这样：升级后日志出现「创造模式预设的 persona 结构与预期不符」。
         *
         * ⚠️ 但**光删锚点还不够**：`{{cwd}}` 这个模板变量本身在 0.2.1 里也**不再注册**
         *   （报错原文：`unknown prompt variable "{{cwd}}" in section
         *   "deployment:persona-suffix"; registered variables: provider, model`）。
         *   所以补丁正文里的 `suffix` 也不能再写它 —— 否则锚点匹配上了、补丁打上了，
         *   整个预设却一渲染就失败，表现为「快准狠模式一选中就处理失败」。
         *   工作目录由 `time-context` 承载，persona 这边不需要再声明。
         */
const val PRESET_CORDIS_PERSONA_GUARD = """          - id: persona
            name: '@deepseek-ai/dsh-persona'
            config:
              prefix: >-
                You are a coding agent powered by the {{model}} model."""

        /**
         * 打过补丁后的文本特征 —— 幂等判据。
         *
         * 用它而不是戳文件：`engine/` 目录会被 `ensureExtracted()` 在版本戳变化时
         * 整个删掉重解包，任何放在里面的戳都会一起没；而"补丁后文本在不在"
         * 每次都能重新判定，重解包后自然会被重新打上。
         */
const val PRESET_CORDIS_PERSONA_ANCHOR_HEAD = "━━━ 铁律零：指哪打哪，不顺手加活"

        /**
         * 替换后的 persona —— 在官方原句之上并入「快准狠」铁律（用户要求：
         * 「整体不用怎么改，就是把它的提示词改一下，在原有的基础上加入我们快准狠模式的约束」）。
         *
         * 与 `preset-razor` 的 persona **同源**，差别只有两点、都是为了不破坏创造模式的定位：
         * 1. 首句保留官方的"coding agent"口径，再点明「用于定制 DSH 本身」；
         * 2. 铁律五补一句「创造模式特有的规矩」，并显式指向两份中文 skill ——
         *    这正是用户要的「提前在预设里写清楚，不用 AI 再摸一遍项目结构」。
         *
         * ⚠️ YAML 里用的是**块标量**（`|-`），所以每行必须保持缩进，且**不能出现
         *   未转义的 `:` 后跟空格**（那会被解析成映射）。正文里凡是要写冒号的地方，
         *    都用中文全角「：」。
         */
val PRESET_CORDIS_PERSONA_PATCH = buildString {
            append("          - id: persona\n")
            append("            name: '@deepseek-ai/dsh-persona'\n")
            append("            config:\n")
            append("              suffix: |-\n")
            append("                【重申铁律】无论思考（思维链、推理过程）还是回复，一律使用中文，禁止用英文思考。\n")
            append("              prefix: |-\n")
            append("                你是一个由 {{model}} 模型驱动的 DSH 定制 Agent，正在与用户配对，")
            append("通过对话为 DSH 本身扩展能力：编写插件、添加界面、组合工具与提示词、创建新的模式。\n")
            append("                你不假设技术栈、不假设目录结构、不假设构建命令——一切以仓库里的实际情况为准。\n")
            append("\n")
            append("                ━━━ 铁律零：指哪打哪，不顺手加活（最高准则，与其余各条冲突时以此为准）━━━\n")
            append("                任务边界＝用户字面说的范围。用户没要求的步骤，一律不做。典型越界行为：\n")
            append("                装完再试着打开看看、改完再顺手测下游、做完再自检一遍还有没有别的毛病、\n")
            append("                顺手重构一下、顺手优化一下、顺手补个注释/日志/抽象。\n")
            append("                「顺手多做一点」在本模式里是错误行为，不是贴心：它拖慢速度，用户要的时候会自己说。\n")
            append("                确实认为有必要扩展时，只能先问一句「要不要顺便 X」，用户点头才做；严禁先做后报。\n")
            append("                判断标准：这一步是用户要我做的事本身，还是这件事之后可能发生的事？后者不做。\n")
            append("\n")
            append("                ━━━ 铁律一：全程中文 ━━━\n")
            append("                你的思考过程（思维链、推理）和最终回复都必须用中文书写。不要先用英文推理再翻译，\n")
            append("                直接用中文思考。代码、命令、标识符、日志原文保持原样，不要翻译。\n")
            append("\n")
            append("                ━━━ 铁律二：开工先建清单，边做边更新 ━━━\n")
            append("                接到任务后第一件事是调用 todo_write 建立完整清单（拆成 3–8 个可验证步骤），\n")
            append("                之后每完成一步就立刻更新清单（整表重发），让用户随时看得到进展。\n")
            append("                - 同一时刻只允许一个 in_progress。\n")
            append("                - 完成一步立即标 completed，不要攒到最后一起标。\n")
            append("                - 新发现的必要步骤补进清单，跑偏的删掉。\n")
            append("                清单是给用户看的进度条，不是摆设——不许只建一次就再也不更新。\n")
            append("\n")
            append("                ━━━ 铁律三：进度汇报只说三句 ━━━\n")
            append("                每轮汇报压缩成三句：现在做什么 / 已完成什么 / 下一步。\n")
            append("                不复述代码，不贴大段日志，不做无关铺垫。\n")
            append("\n")
            append("                ━━━ 铁律四：快、准、好 ━━━\n")
            append("                - 快：先读代码再动手，但信息足够就立刻停止探索。不要为「更确定」反复读同一个\n")
            append("                  文件、反复跑同一条命令、反复量同一个事实。探到能动手的程度就动手。\n")
            append("                - 准：定位问题要落到具体文件与行，说清根因，不含糊地说「可能是某某」。\n")
            append("                - 好：改动最小化，只改必要部分，不顺手重构、不顺手加抽象、不顺手加依赖。\n")
            append("                - 同一个事实只量一遍；结论一旦成立立即进入下一步，不再重复验证。\n")
            append("                - 脚手架与临时文件用内联命令解决，不要写进仓库，用完即删。\n")
            append("                - 能数值断言就不靠截图；一句话能说清就不写十句。\n")
            append("                - 不要问自己能查到答案的问题（如「你项目用什么框架」——去读代码）。\n")
            append("\n")
            append("                ━━━ 铁律五：先探明本项目的规矩，再动手 ━━━\n")
            append("                每个项目的构建、测试、验收方式都不同，不要套用上一个项目的习惯。\n")
            append("                动手前用最少的读取量摸清这三件事：\n")
            append("                1. 规矩在哪：根目录的 AGENTS.md / CLAUDE.md / README / CONTRIBUTING，\n")
            append("                   以及 Makefile、justfile、package.json scripts、Taskfile、构建脚本。\n")
            append("                   有就照它写的来；没有就自己判断，但不要凭想象编一条命令。\n")
            append("                2. 怎么改：本项目的既有写法（命名、错误处理、分层、测试组织），照抄现成的模式，\n")
            append("                   不要引入本项目没有的技术或依赖。\n")
            append("                3. 怎么验：本项目自己的编译 / 测试 / lint 命令就是唯一验收信号。\n")
            append("                验证只用来确认「我这一步做成了没有」，到此为止，不向外延伸：\n")
            append("                装完了就验到装成功，不去试能不能打开、打开有没有异常；\n")
            append("                改完了就验到改动生效，不去验下游功能还正不正常。\n")
            append("                ⚠️ 构建成功 ≠ 改动生效；测试通过 ≠ 测试真跑了。\n")
            append("                   要用项目自己的方式确认：产物里真的有你的改动、测试确实执行了（看用例数与跳过数）。\n")
            append("                   不要把「退出码为 0」当成「验证过了」。\n")
            append("                   但也不要把验证扩成加活：用户没要求的检查不做（见铁律零）。\n")
            append("\n")
            append("                ━━━ 铁律五之补充：创造模式特有的规矩 ━━━\n")
            append("                你已经装好了两份**中文 skill**，专门解决「每次都要重新摸一遍 DSH 结构」这个低效问题。\n")
            append("                动手前先按需加载，不要凭印象猜（更不要重新去读引擎源码）：\n")
            append("                - `dsh-mobile-theme` —— 做移动端界面主题 / 换肤 / 改配色 / 深色适配时加载。\n")
            append("                  内含 DSH 前端的取色机制、完整的 --dsw-* token 清单、主题插件的最小结构、\n")
            append("                  以及打包并挂到手机 App 上的完整步骤。\n")
            append("                - `dsh-desktop-pet` —— 做悬浮球外观 / 桌面宠物 / 精灵图动画时加载。\n")
            append("                  内含精灵图的精确切片规则（很多人栽在这里）、bubble 工具的全部字段与上限、\n")
            append("                  运行状态到动作的映射，以及最常见的四种错。\n")
            append("                加载方式：调用 `skill` 工具，传 skill 名。它们与其它 skill 一起出现在 skill 目录里。\n")
            append("                用户的需求若落在上述范围，先加载对应 skill 再动手；不在范围内就跳过。\n")
            append("\n")
            append("                ━━━ 铁律六：把需要人判断的节点交棒 ━━━\n")
            append("                - 你能自动验证的（编译、测试、静态检查、数值断言），自己跑完，不要甩给用户。\n")
            append("                  但「能验证」不等于「该验证」：只验证用户明确要求的那件事的结果，\n")
            append("                  用户没要求的检查一律不做（见铁律零）。做完就报，不要自己加验一遍。\n")
            append("                - 到了只有人能判断的节点（视觉外观、交互手感、业务是否合理），把状态准备好，\n")
            append("                  用编号写清「做哪几步 → 应看到什么」，然后停下来等用户确认。\n")
            append("                - 不要用截图 / 录屏循环反复自测来替代人的判断，那只会拖慢节奏。\n")
            append("                - 用户说「没问题了 / 可以了 / 通过」= 验收通过 → 立即收尾，不要再补测一轮，\n")
            append("                  不要追问「要不要再测测别的」。只有用户明确报「不行」时才继续排障。\n")
            append("\n")
            append("                ━━━ 铁律七：诚实收尾 ━━━\n")
            append("                - 没做的验证不要声称做过；跑失败就说失败，把原始报错贴出来。\n")
            append("                - 阻塞就说清卡在哪、需要用户做什么决定，不要装作已完成。\n")
            append("                - 收尾给出三件事：做了什么（文件 + 行）、结果如何、还剩什么没做。\n")
            append("                  验证过就写清怎么验的；没验就不要写、也不要补一句「我没验 X」——用户没要求就不验，\n")
            append("                  不需要交代，用户要知道会自己问。")
        }.trimEnd()

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

        /**
         * link()/linkat() 语义转译层（LD_PRELOAD）。
         * 资产名用中性后缀，避免 AGP 按扩展名做格式推断（同 engine.bin 的坑）。
         */
const val LINKCOMPAT_ASSET = "linkcompat.bin"
const val LINKCOMPAT_FILE = "liblinkcompat.so"

        /**
         * **旧版**号池注入库的文件名，仅用于**清理**老安装的残留。
         *
         * 号池已改为服务端网关，不再需要 `LD_PRELOAD` 注入。这个常量保留下来
         * 只为一件事：`ensureKeyInject()` 要把用户设备上残留的
         * `filesDir/libkeyinject.so` 删掉 —— 留着它虽然不会再被加载，但会让人
         * 误以为注入层还在，排查问题时造成误导。
         *
         * ⚠️ 对应的资产 `assets/keyinject.bin` 与源码 `jni/keyinject.c` **已删除**，
         * 不要再加回来。旧的 `KEYINJECT_ASSET` 常量也一并删掉了。
         */
const val KEYINJECT_FILE = "libkeyinject.so"

        /**
         * 旧版内置号池 overlay 的文件名。**只用于删除**：那个写法（`- id: llm-pi-ai`
         * 的 `--patch`）会把用户新建的 provider 一起盖掉，导致 GUI 一加第三方 API
         * 就报 `Configuration for "llm-pi-ai" is overridden by a home patch or
         * command-line overlay`。老安装里残留的这份必须删掉，否则它仍会生效。
         */
const val KEYINJECT_PATCH = "keyinject.patch.yml"

        /**
         * 内置号池落在哪一行：**必须复用引擎自己的 `llm-pi-ai` entry**，
         * 不能另开一行同名插件。
         *
         * ⚠️ 为什么不能另开一行（踩过）：`dsh-llm-pi-ai` 的 `apply()` 会**无条件**
         * 把 pi-ai 内置目录里的 41 个 provider 全部 `registerConfigurableProviders()`。
         * 挂第二个实例时，第二批声明撞上第一批已经占用的路由，
         * `commit()` 抛 `DUPLICATE_DIRECTORY`（`configurable provider "anthropic"
         * is already declared`）⇒ 整个目录注册失败，模型列表空掉。
         *
         * ⚠️ 也不能用 `--patch` 去改这一行：overlay 在最顶层，会把用户在 GUI 里
         * 新建的 provider 整块盖掉（就是本次要修的 bug）。
         *
         * 所以唯一正确的落点是**同一个 entry、profile 的用户配置层** —— 那正是
         * GUI 自己写入的那份文件，我们只在缺 `agnes:` 时补一笔，绝不覆盖用户内容。
         */
const val KEY_POOL_NS = "llm-pi-ai"

        /**
         * 可以安全摘掉的「行内空值」写法 —— 摘掉它们**不丢任何数据**。
         *
         * ⚠️ 用途是修 [`mergeKeyPoolProvider`] 里的重复键崩溃：引擎把 schema 默认值
         * 序列化进文件时，`providers: z.dict(profile).default({})` 会写成
         * `providers: {}`，而 `{}` 是流式空映射，在它下面缩进写 `agnes:` 是非法
         * YAML，必须先还原成裸 `providers:`。
         *
         * ⚠️ **只认这四种「空」**。非空的 `providers: {deepseek: {...}}` 绝不能摘 ——
         * 那是用户的 provider 配置，摘掉就是数据丢失，见 [openKeyBlock] 的注释。
         *
         * `null` / `~` 是 YAML 的显式空值，语义上等于「没有内容」，摘下同样安全。
         */
val EMPTY_INLINE_VALUES = setOf("{}", "[]", "null", "~")

        /**
         * 网关令牌（替代旧的 TLS 层注入占位符）。
         *
         * ## 为什么从「占位符 + LD_PRELOAD 注入」改成「网关令牌」
         *
         * 旧方案把 132 把真密钥打进 APK，靠 `libkeyinject.so` 在 `SSL_write` 里
         * 把占位符换成真钥匙。虽然做了混淆，但**密钥终究落在设备上** —— 一旦
         * 泄漏就是 132 把一起丢，且换钥匙必须重新发版。
         *
         * 现在改为服务端中转：App 只持有这一个网关令牌，请求发到
         * `agnes.jiulingssh.xyz`，由服务器按严格轮询挑选真密钥并注入。
         * 好处：
         *   - 真密钥永不落设备（`strings` / 环境变量 / 抓包都拿不到）
         *   - 增删密钥只需改服务器上的 `agnes-keys.properties`，**不用发版**
         *   - 429/5xx 由网关自动换号重试，客户端无感
         *
         * ⚠️ 这个令牌**不是秘密**（它随 APK 分发，粉丝能读到），所以网关侧必须
         * 自己做限流与额度控制。令牌泄漏的后果是「额度被白嫖」，而不是「密钥泄漏」。
         *
         * ⚠️ 与旧 `KEY_PLACEHOLDER` 的兼容：老安装的 `ag-multimodal.json` 里可能
         * 还留着占位符行，[seedAgnesAccounts] 认它并覆盖成新令牌。
         */
const val KEY_PLACEHOLDER = "sk-agnes-54a388522b26770c2b45aab2f5a22c0700b6be4c0f325a96"

        /**
         * **旧版**的 TLS 注入占位符，仅用于**识别并覆盖**老安装残留的配置行。
         *
         * 升级到网关方案前，`ag-multimodal.json` 与 provider 配置里写的是这个
         * `sk-ANON…` 串。现在它已经没有意义（没有注入库会去替换它），但它会
         * 留在用户设备上。`seedAgnesAccounts()` 认它并把整行替换成新令牌，
         * 避免出现「用户有一条永远 401 的旧内置号」。
         *
         * ⚠️ **不要删除这个常量**，也不要把它当成有效配置使用。
         */
const val LEGACY_KEY_PLACEHOLDER = "sk-ANON00000000000000000000000000000000000000000000"

        /**
         * **旧版**直连上游的 baseURL，仅用于**识别并迁移**老安装的 `agnes:` 配置块。
         *
         * 号池改为服务端网关后，`baseURL` 必须是 [KEY_BASE_URL]。但
         * [mergeKeyPoolProvider] 的语义是「已有 agnes: 就一个字节都不动」，
         * 老安装升级后仍指向这两个域名 ⇒ 带着网关令牌直连上游 ⇒ 上游 401 ⇒
         * 界面显示「API 密钥无效」。
         *
         * [mergeKeyPoolProvider] 命中这里列出的值时改写成网关地址；
         * **其它任何值都不碰**（用户自填的第三方地址绝不可覆盖）。
         *
         * ⚠️ 不要把它们当成有效配置使用。
         */
val LEGACY_KEY_BASE_URLS = setOf(
            "https://api.agnes-ai.cn/v1",
            "https://apihub.agnes-ai.cn/v1",
            "https://api.agnes-ai.cn",
            "https://apihub.agnes-ai.cn",
        )

        /**
         * 内置号池的 provider 路由名。
         *
         * **只有一个**：两个站点由 `keyinject.c` 在 TLS 层按钥匙所属站点改写
         * Host 头来区分（combo），所以引擎侧看到的就是一个普通供应商，用户
         * 在主界面选模型时也只看到一个「Agnes」。
         *
         * 依据是三个实测事实：两个域名**同一个 IP**、共用**同一张**
         * `*.agnes-ai.cn` 通配证书、服务器按 **Host 头**路由（不是 SNI）。
         */
const val KEY_PROVIDER = "agnes"

/** 内置 Agnes 网关的域名标记：profile 里命中它就说明是**内置**供应商，清理时据此识别。 */
const val AGNES_GATEWAY_MARK = "agnes.jiulingssh.xyz"

        /** 供应商显示名 —— 主界面模型列表里分组用的就是它。 */
const val KEY_PROVIDER_NAME = "Agnes"

        /**
         * 网关地址（替代旧的直连国际站域名）。
         *
         * 旧方案 baseURL 指向 `apihub.agnes-ai.cn`，靠 `keyinject.c` 等长改写
         * Host 头在两个站点间路由 —— 那条链路已随注入层一起下线。
         *
         * 现在指向自建号池网关：两站钥匙的挑选与 Host 路由全部由**服务器**完成，
         * 客户端只看到一普通 OpenAI 兼容端点，不需要知道底下有几个站点、几把钥匙。
         *
         * ⚠️ 保留 `/v1` 后缀：引擎的 `openai-completions` 会拼 `/chat/completions`，
         * 与 OpenAI 官方 SDK 的习惯一致。
         */
const val KEY_BASE_URL = "https://agnes.jiulingssh.xyz/v1"

        /**
         * `apiKeyEnv` 引用的环境变量名。
         *
         * 名字里**刻意不带 KEY/SECRET/TOKEN**：`dsh-subprocess` 的
         * `scrubbedParentEnv()` 会剥掉所有匹配 `SENSITIVE_ENV_PATTERN`
         * （`/KEY|PASSWORD|SECRET|TOKEN/i`）的变量，带这些字样的名字会让子进程
         * 拿不到它。虽然这里装的是占位符、泄露也无所谓，但保持它能被子进程读到
         * 更省事（引擎若在子进程里做模型发现，缺了它会报 `MISSING_CREDENTIAL`）。
         */
const val KEY_ENV = "AGNES_CREDENTIAL"

        /**
         * 号池重试策略里声明的**可重试错误码**。
         *
         * ⚠️ `retryableCodes` 是**整体替换**默认值，不是追加 —— 引擎侧
         * `resolveRetryPolicy()` 写的是 `config.retryableCodes ?? [...DEFAULT_RETRYABLE_CODES]`，
         * 一旦我们提供了这个数组，默认那五个就**全都不再生效**。所以必须把它们
         * **原样再列一遍**（值与 `dsh-llm/lib/types/retry-policy.js` 的
         * `DEFAULT_RETRYABLE_CODES` 逐字一致），漏掉哪个就等于把那类错误的重试关掉。
         *
         * 五个默认值之外**多了一个 `AUTH`**。
         *
         * ⚠️ 这条的**原始动机已随网关改造消失**，但**仍然保留**，理由如下：
         *
         * 旧方案里号池是「随机抽钥匙」，抽到死钥匙就回 401/403，所以要靠引擎
         * 重发请求来换一把。现在网关自己会换号重试（`AGNES_MAX_ATTEMPTS`），
         * 客户端正常情况下**收不到** 401 了。
         *
         * 但保留它仍有两个实际好处：
         * 1. 网关令牌本身被吊销/更换时，客户端回 401 —— 重试虽无用，但能让
         *    错误在界面上表现为「重试若干次后失败」，而不是瞬间中断；
         * 2. 网关若因全池冷却返回 429（`RATE_LIMIT` 已在表里）或临时 5xx，
         *    重试是有意义的。
         *
         * 换言之：`AUTH` 现在大概率是空转，但删掉它不会带来收益，留着无害。
         */
val KEY_RETRYABLE_CODES = listOf(
            "EMPTY_RESPONSE",
            "RATE_LIMIT",
            "SERVER",
            "TIMEOUT",
            "TRANSPORT",
            "AUTH",
        )

        /**
         * 内置号池对外提供的模型清单。
         *
         * ⚠️ 必须显式列出：pi-ai 对手工声明的路由要求 `models` 非空，否则
         * 整个 profile 会被拒绝（"A route pi-ai does not ship needs `api`,
         * `baseURL`, and a non-empty `models` list"）。
         *
         * 只留 `agnes-3.0-flash`：号池是给粉丝免费体验用的，列一长串
         * （图像、视频、各种 pro 变体）只会让模型选择器变乱，而实际额度也不支持
         * 那些模型。要放开时在这里加一行即可。
         */
val KEY_MODELS = listOf("agnes-3.0-flash")

        /**
         * 内置号池模型对外声明的思考强度档位（`reasoningEfforts` 的键）。
         *
         * ## 为什么必须声明
         *
         * 不声明时 `dsh-llm-pi-ai` 的 `resolveModelReasoning()` 走
         * `base?.reasoning ?? false`，而 `agnes` 是自定义路由、pi-ai 内置目录里
         * 没有它，于是 `reasoning` 落到 **false**；`openai-completions` 的两个
         * 分支都要求 `model.reasoning`，**请求里根本不会带 `reasoning_effort`**。
         * Agnes 不传该参数就不返回 `reasoning_content` —— 界面上「思考过程」永远
         * 是空的。声明之后引擎才会带上它，思考内容才会流回来。
         *
         * ## 合法值来自实测，不是抄文档
         *
         * 给 `reasoning_effort: 'off'` 时 Agnes 直接 400，报错原文把合法集合列全了：
         *
         * ```
         * Input should be 'none', 'minimal', 'low', 'medium', 'high', 'xhigh' or 'max'
         * ```
         *
         * ## ⚠️⚠️ 为什么这里**刻意不声明 `off` 档**
         *
         * 这是踩过的坑，改回去会让「思考混进正文」重现，务必读完再动。
         *
         * 发送侧是**硬编码**的（`dsh-llm-pi-ai` 的 `streamSimple`）：
         *
         * ```js
         * const reasoningEffort = clampedReasoning === "off" ? undefined : clampedReasoning;
         * ```
         *
         * 也就是说**只要档位是 `off`，引擎就不发 `reasoning_effort`** —— 无论
         * overlay 里把 `off` 映射成什么（`null` 也一样）都改变不了这一点。
         * 而 Agnes 收不到「已关闭」这个信号时，会**把思考直接写进 `content`**
         * （实测 7 次里中 1 次，且内容是第一人称英文推理，例如
         * "The existing file is just a minimal placeholder…"），
         * 于是界面上思考内容就出现在正文里，而不是 thinking 块里。
         *
         * 更要命的是 `off` **一旦写进 `reasoningEfforts` 反而会变成可选档**：
         *
         * | overlay 里 | `thinkingLevelMap['off']` | `getSupportedThinkingLevels()` |
         * |---|---|---|
         * | 不写 `off` 键 | `null`（`wire === void 0` 分支） | `mapped === null` ⇒ **不可选** ✅ |
         * | 写 `'off': null` | `undefined`（`wire !== null` 跳过赋值） | 不为 null ⇒ **可选** ❌ |
         *
         * 所以唯一可靠的修法是**整档不声明**：off 从选择器里消失，
         * `clampThinkingLevel()` 把默认档落到 `minimal`，而 `minimal` 实测
         * **7/7 都走 `reasoning_content`**，思考正确进 thinking 块。
         *
         * ⚠️ 也别想用 `none` 顶替 `off`：`none` 同样不产生思考，但它会被真的发出去，
         * 与「不发字段」不是同一条路径，且实测出现过指令复述混进 `content` 的情况。
         *
         * ## ⚠️ 实测：这 6 档没有强度梯度
         *
         * 同一道推理题反复测，思考长度在 minimal=479 / low=1593 / medium=589 /
         * high=877 / xhigh=923 / max=1225 之间**随机乱跳**，不随档位递增。
         * 也就是说 Agnes 目前实际只区分「开 / 关」，档位名只是被接受而已。
         * 保留全部 6 档是为了不挡用户，而不是因为选了真会想得更多。
         */
val KEY_EFFORT_LEVELS = listOf("minimal", "low", "medium", "high", "xhigh", "max")

        /**
         * 内置号池模型对外声明的**输入模态**（`input` 字段）。
         *
         * ## 为什么必须声明
         *
         * `dsh-llm-pi-ai` 的 `DEFAULT_INPUT = ["text"]`，且解析顺序是
         *
         * ```js
         * input: declaredInput(entry.input) ?? base?.input ?? [...request.defaultInput]
         * ```
         *
         * `agnes` 是自定义路由、pi-ai 内置目录里没有它 ⇒ `base` 不存在 ⇒
         * 不声明就落到 `["text"]`。后果是**图片在附加前就被拒**：
         * composer 里选好图、点发送，引擎报「该模型不接受图片输入」，
         * 而 Agnes 本身是能识图的 —— 属于「能力被配置挡住」。
         *
         * ## 依据来自实测，不是照抄文档
         *
         * 拿真实 PNG（64×64 红底黄圆）走 OpenAI 兼容的 `image_url` 数据 URI
         * 直连两家站点，**14 把密钥 14/14 都正确答出「红色、黄色」**，
         * 且 `usage.prompt_tokens_details.image_tokens = 64` —— 图片确实进了模型
         * 并按图计费，不是被忽略后靠文本猜的。
         *
         * ⚠️ 手搓的 base64 PNG 会被服务端判成
         * `Could not decode image: broken data stream`（HTTP 400），
         * 那是**图坏了**而不是不支持图片 —— 与 AGENTS.md ⑧ 记的 sharp 那条同一个坑。
         * 看到这个 400 别急着下「不支持」的结论，换一张真图再测。
         *
         * ## ⚠️ over-claim 的代价（所以这里只声明实测过的能力）
         *
         * pi-ai 的注释写得很明确：少声明只是「附加前被拒、并指名模型」，
         * 多声明则是「图进去了、供应商中途拒绝」，而那时消息**已经落盘**，
         * 会话会卡在一条永远发不出去的请求上反复重试。
         * 所以这个列表只放实测通过的模态，不要凭「多模态」这个词顺手加。
         */
val KEY_MODEL_INPUT = listOf("text", "image")

        /** ripgrep：官方 `@vscode/ripgrep` 没有 android 平台包，这里补一个。 */
const val RIPGREP_ASSET = "ripgrep-android-arm64.zip"
const val RIPGREP_PACKAGE = "@vscode/ripgrep-android-arm64"

        /** sharp 的 wasm32 变体（官方兜底，见 `ensureSharp()`）。 */
const val SHARP_ASSET = "sharp-wasm32.zip"
const val SHARP_WASM_PACKAGE = "@img/sharp-wasm32"
const val SHARP_VERSION = "0.35.5"

        /** node-pty：官方 prebuilds 没有 android，自编译一份（见 `ensureNodePty()`）。 */
const val NODE_PTY_ASSET = "node-pty-android-arm64.zip"

        /**
         * `dsh-subprocess-local` 里 `createProcessInspector()` 的平台判断原文，
         * 以及放行 android 之后的文本。见 [patchProcessInspector]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致。
         * ⚠️ 文本里的 `${platform}` 是**引擎源码**里的模板插值，不是 Kotlin 的，
         * 所以 `$` 必须转义成 `\$`，否则 Kotlin 会当成自己的变量去解析。
         */
const val INSPECTOR_GUARD =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"
const val INSPECTOR_ANDROID_ANCHOR =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\t/* Android 就是 Linux 内核，/proc 格式一致，且 SYSCALLS 表里本来就有 arm64。\n" +
                "\t   app 域下 /proc 目录可读，只是别的 uid 的条目会被 SELinux 挡掉 ——\n" +
                "\t   LinuxProcessInspector 对读不到的条目一律跳过，不抛错。\n" +
                "\t   代价是只能看见自己 uid 的进程，而终端 shell 及其子进程全是本 app 的 uid。 */\n" +
                "\tif (platform === \"android\") return new LinuxProcessInspector(arch, internals);\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"

        /** 沙箱：runner（bwrap 方言翻译）+ LD_PRELOAD 写拦截器。 */
const val SANDBOX_RUNNER_ASSET = "boo-sandbox-run.bin"
const val SANDBOX_RUNNER_FILE = "boo-sandbox-run"
const val SANDBOX_LIB_ASSET = "boosandbox.bin"
const val SANDBOX_LIB_FILE = "libboosandbox.so"
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

        /** pnpm：Termux 的 $PREFIX/bin 里没有，插件管理器靠它装 bundle。 */
const val PNPM_ASSET = "pnpm-android-arm64.zip"
const val PNPM_VERSION = "11.28.3"
const val PNPM_STAMP = ".pnpm_version"

        /** pnpm 整包解到 `$PREFIX/lib/pnpm`，`bin/pnpm` 只是个 exec 它的脚本。 */
const val PNPM_DIR = "lib/pnpm"

        /**
         * 插件内容版本：改了 `mobile-ui.zip` 里的任何东西都必须改这里，
         * 否则设备上已解包的旧版不会被覆盖（同 TermuxBootstrap.NODE_VERSION 的道理）。
         *
         * −48：真正生效。第一版把 `type=password` 改成 `text` 之后，
         *      又用 CSS `-webkit-text-security: disc` 补圆点 —— 而 blink 判定
         *      「密码字段」用的正是 `computedStyle.textSecurity != none`，
         *      等于把密码语义原样装了回去，输入法照样切安全键盘、照样屏蔽剪贴板。
         *      本版把这层 CSS 遮蔽彻底移除。
         * −49：的**真正生效点**。去掉 CSS 之后键盘仍是安全键盘 —— 因为
         *      官方那两个凭据框带 `autoFocus: true`，元素插入 DOM 的同一帧就以
         *      `type=password` 聚焦，WebView 在那一刻就把输入连接的 inputType
         *      定成 `TYPE_TEXT_VARIATION_WEB_PASSWORD`(0xe1)，而**它不会为已建立
         *      的连接重算**（实测 blur+focus 也刷不动）。本版改为在
         *      `HTMLInputElement.prototype` 层拦截 `type=password`，让 DOM 里
         *      从头到尾没出现过 password。真机实测 inputType 0xe1 → 0x80a1。
         * −54：悬浮球转录改为**近流式**并把思考与正文分开。两处改动：
         *      ① `mpScheduleTranscript` 节流 1500ms → 250ms（1.5s 会把整段回复
         *         攒成两三坨跳出来，观感就是"没有流式、一次全出"）；
         *      ② `mpCollectTranscript` 按 `data-chat-group-part` 把 `reasoning`
         *         节点发成独立的 `reasoning` 角色（原来与 `response` 都发成
         *         `assistant`，面板上两条标签样式全一样 ⇒ 看着就是"糊在一起"）。
         *      ⚠️ 必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`，
         *      否则设备上那棵降级过的 mobile-ui 树不会被覆盖（见 ⑯）。
         * 1.0.0-57：`mpPageBackground()` 改为**读主题 token** `--dsw-alias-bg-base`
         *      （取不到才退回 `backgroundColor`）。这是"系统栏默认跟随主题色"的实现 ——
         *      `backgroundColor` 只是 token 的投影，纯色主题下两者相等，主题一改成
         *      背景图/渐变就分叉。同 v56 的道理，也必须 bump -CompatVersion。
         * 1.0.0-58：全面屏让位 —— 内容列 `.pI_x6G_centerCol` 补上
         *      `padding-top: var(--dsh-android-system-top)`。原来只有侧栏让位，
         *      内容列靠 WebView 自身避让系统栏活着；全面屏一开那层避让被撤掉
         *      （窗口 content 由 [0,108] 变 [0,0]），状态栏直接压住汉堡与聊天页
         *      header。同 v57 的道理，也必须 bump `-CompatVersion`。
         * 1.0.0-59：上面那条 CSS 规则**压不过的 `!important`**，实测
         *      `getComputedStyle` 仍是 0px、规则完全不生效。改为让
         *      `mpSyncFloatNavGap()` 在算 `--mp-float-nav-h` 时把状态栏高度一并纳入
         *      （JS 的 `Math.max` 不受内核版本限制；CSS 的 `max()` 要 Chrome 79，
         *      而目标内核是 chrome74，老内核上整条声明会静默失效）。
         *      同 v57/v58 的道理，也必须 bump `-CompatVersion`（现 v15）。
         * 1.0.0-60：设置页新增「运行日志与诊断」分节（`mpLogsSection` +
         *      `mpRegisterLogsSection`），拉起原生 `LogViewerActivity`。
         *      这是**纯 JS 新增**，没有改 CSS，所以**不必** bump
         *      `tools/build-webview-compat.ps1` 的 `-CompatVersion` ——
         *      兼容包是"按 chrome74 降级编译前端 bundle"，插件 JS 走的是
         *      引擎自己的加载路径，不经过那个编译。
         *      ⚠️ 但仍然必须 bump 本常量：否则 `.mobile_ui_version` 标记不变，
         *      `ensureMobileUi()` 直接跳过解包，**老设备永远拿不到这个分节**。
         * 1.0.0-62：**沉浸式下汉堡整行下移的根因不在插件，而在原生推错了单位。**
         *      真正的修复在 `MainActivity.pushSystemBarInset()`（把状态栏物理像素
         *      ÷ density 换算成 CSS 像素再推）。本版只改了两处**注释**：
         *      `mpSyncFloatNavGap()` 与 CSS 那条规则原先写着「该变量在本 App 里
         *      从未被任何代码写入（恒 0）」—— 那句话在之后就过时了，
         *      留着会误导下一个来改的人按"变量恒 0"的旧前提动手。
         *      ⚠️ 严格说这一版**没有改任何 JS 行为**，本可以不 bump；但
         *      `mobileui/lib/client.js` 是降级编译的输入之一，注释变了产物字节就变，
         *      而 `.mobile_ui_version` 与 `-CompatVersion` 两戳只要都对得上，
         *      设备上那棵树就会被跳过、继续用旧产物覆盖。为免"源码与设备不一致"
         *      这种最难查的中间态，两个戳一起 bump（见 ⑯ 的 v4~v17 全部先例）。
         * 1.0.0-63：**特殊权限界面布局重做 + 新增 Shizuku 通道。**
         *      界面侧（`mobileui/lib/client.js`）：
         *      ① 新增 Shizuku 卡片，摆在三条路径的第一位 —— 它是**唯一不依赖
         *         adbd** 的那条（不走 5555、不校验 adbkey、重启后依然有效），
         *         对 Android 10 那批「每次重启都要插电脑跑 adb tcpip」的用户是
         *         唯一走得通的路；
         *      ② 三条路径统一成同一个 `pathCard` 结构、顺序**固定**（原来是按
         *         系统版本翻转的，换机/升级后版面全变，用户得重新认一遍）；
         *      ③ 首屏「下一步」的决策树把 Shizuku 排在 adb 两条路之前；
         *      ④ 按钮去重：一屏最多一个主按钮（原来同屏摆了五个同等分量按钮）。
         *      原生侧：新增 `ShizukuChannel`（LocalServerSocket + IShizukuService
         *      .newProcess）、`PageBridge` 四个方法、runner 的 0 号通道。
         *      ⚠️ **必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`
         *      （18 → 19）**：`mobileui/lib/client.js` 是降级编译的输入之一，
         *      不 bump 就会命中「两戳都一致 → 整棵树跳过」，设备上继续跑旧布局，
         *      而 `已安装移动端 UI 插件 1.0.0-63` 这行日志看起来一切正常。
         *      本次真机实测踩到的正是这个形态（设备上 client.js 停在 369105 字节）。
         * 1.0.0-64：**特殊权限界面改成标签页布局**（用户要求：ADB / Shizuku / root
         *      各占一个标签页，点进去是二级界面）。
         *      ① 标签栏替代原来的「三条路径 + root 卡全部平铺」：平铺时一屏里
         *         四五张结构相似的卡纵向排开，用户在 360px 宽的屏上要滑很久，
         *         而且**看完也不知道该点哪张** —— 层级全靠徽章文字区分，太弱；
         *      ② 标签上带状态圆点，不点进去就能看出哪条通道通（圆点色与卡片
         *         徽章色同源，都取 `MP_PRIV_TONE`）；
         *      ③ 标签栏**本身就是导航**，不加「返回」按钮 —— 中途试过「标签栏 +
         *         三行概览卡」，同一组信息在一屏里说两遍，已删；
         *      ④ 默认停在 **Shizuku** 标签（唯一不依赖 adbd 的那条）；
         *      ⑤ 无线调试在 Android 10 上**恢复渲染**：上一版整张卡不渲染，
         *         用户根本看不到还有这条路，只会以为本应用漏做了。现在渲染成
         *         一张纯说明卡（徽章「不可用」、**不给任何按钮**）——
         *         判据是「有没有可点的动作」，不是「这一块在不在」。
         *      ⚠️ **必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`
         *      （19 → 20）**，理由同 v63：`mobileui/lib/client.js` 是降级编译的
         *      输入之一，不 bump 会命中「两戳都一致 → 整棵树跳过」，
         *      设备上继续跑旧布局，而日志看起来一切正常。
         * 1.0.0-65：修正 v64 里 `rootIntro` 的 Markdown 标记。
         *      文案写成「与上面两条通道**互不影响**」，而本页是**纯文本渲染**
         *      （React 直接塞进 DOM），星号会**原样显示**出来 —— 真机截图上
         *      就是「两条通道**互不影响**：没有 root 的设备…」。
         *      ⚠️ **这一版的存在本身就是一条教训，别删**：v64 的标签页布局做完后
         *      真机验过三个标签都正常，随后才发现星号问题；修完只重打了
         *      `mobile-ui.zip` 并重跑 `build-webview-compat.ps1`（仍是 v20），
         *      **忘了再 bump `MOBILE_UI_VERSION`** ⇒ 设备上两戳依旧是
         *      `1.0.0-64` / `chrome74-v20` ⇒ 解包整体跳过 ⇒ 修正版根本没上机，
         *      而 `已安装移动端 UI 插件` 那行日志压根不打印（版本没变），
         *      看起来「什么都没发生」。
         *      判据：改完 mobile-ui 源码，**两个戳都必须是新值**；
         *      只要其中一个还是上次的值，设备就会继续跑旧产物。
         * 1.0.0-102：设置页「交流群」换成**新群**（「DSHAPP交流群3」，群号 1121898890）。
         *      「DSHAPP交流群2」（1109733098）已满，用户给了新的群二维码截图。
         *      ① 群号 `MP_CONTACT_GROUP_NO`：1109733098 → 1121898890；
         *      ② 二维码 data URI 换成新群（700×700 WebP，68,522 B）。
         *         用户给的是**带群名/群号/QQ logo 的整张截图**，用 **zxing-cpp**
         *         直接读出四个角点（`203,695 / 952,695 / 952,1444 / 203,1444`），
         *         按此裁切、每边留 20px 静区、LANCZOS 缩到 700；
         *      ③ 可扫性用 zxing-cpp 验证：WebP 本身、以及模拟
         *         「CSS 240px × dpr3」重采样后**均解码成功**，
         *         内容 `https://qm.qq.com/q/l8uDVdDRXc`。
         *      ⚠️ **两个戳都 bump**：`MOBILE_UI_VERSION` 101 → 102，
         *      降级戳 `chrome74-v34` → `chrome74-v35`。
         *      这次没有 pwsh/esbuild，降级产物 `webview-compat.zip` 里的
         *      `mobileui/lib/client.js` 是**直接对旧产物做等量替换**得到的
         *      （只换群号与二维码 data URI，其余字节不动），
         *      改完用 `tools/check-frontend-syntax.mjs --target 74` 复检通过。
         * 1.0.0-66：设置页「交流群」换成**新群**（「DSHAPP交流群2」，群号 1109733098）。
         *      ① 群号 `MP_CONTACT_GROUP_NO`：1127652833 → 1109733098；
         *      ② 二维码 data URI 换成新群二维码（720×720 WebP，52,764 B，
         *         比旧图小 9,230 B）。用户给的是**带群名/群号/QQ logo 的整张截图**，
         *         所以先用蓝色像素（`B-R>40 且 B>150`）的包围盒定位二维码本体
         *         （415×415，`x 81..495, y 305..719`），裁出来、每边留 12% 白边
         *         （QR 的 quiet zone，缺了会扫不出）、再 LANCZOS 放大到 720；
         *      ③ 可扫性用 **zxing-cpp** 验证，不用 cv2 —— 这类彩色圆点风格二维码
         *         `cv2.QRCodeDetector` 解不出来（见上方 `MP_CONTACT_QR` 的注释）。
         *         实测：原图 / 裁切图 / 最终 PNG / 最终 WebP，以及按真实布局渲染的
         *         `CSS 240px × dpr1|2|3`、`200|160|120px × dpr3` 各档**全部解码成功**，
         *         内容一致为 `https://qm.qq.com/q/CkDhCJH1rW`（余量最大缩到 240px 仍可读）。
         *      ⚠️ **两个戳都要 bump**：`mobileui/lib/client.js` 是降级编译的输入之一，
         *      只重打 `mobile-ui.zip` 而不 bump `-CompatVersion`（21 → 22），
         *      设备上会命中「两戳都一致 → 整棵树跳过」，继续跑旧群的二维码 ——
         *      而日志不会打任何东西（版本没变），看起来像"什么都没发生"（同 v65 的坑）。
          * 1.0.0-88：**底栏模型名在窄屏下被截断**（用户要「真实的视觉感受」时发现）。
          *      症状（360px / dpr3 真机截图）：模型名显示成 "deepseek-v4.1..."，
          *      后面的推理等级 "Default" **宽度直接是 0**、完全看不见。
          *      根因不是文字太长，而是官方 `uV2eYG_row` 用
          *      `justify-content: space-between`，把 41px 剩余空间全推到了
          *      tools 与 trailing **中间**（实测 tools 结束 x=104、trailing 从 x=145
          *      开始），而右边模型名正因为缺 16px 在截断。
          *      修法：逐层放宽（row → flex-start、trailing/standardControls/
          *      _7KE1Ra_root → `1 1 0%`、按钮 `max-width: 139.5px → none`），
          *      把那段空隙让给模型名；推理等级 Default 隐藏（它是默认值，
          *      不显示不丢信息，而一个被压成 "Def..." 的次级信息比不显示更难看）。
          *      实测模型名 144px = 内容 144px，零截断。
          *      ⚠️ 两个戳都 bump（本常量 87 → 88，`-CompatVersion` 21 → 22）：
          *      `mobileui/lib/client.js` 是降级编译的输入之一（同 v65/v87 的坑）。
          *      ⚠️ 另外记一条**踩坑**：CSS 注释里写反引号会把整份 JS 弄坏 ——
          *      那段注释在模板字符串里，一个反引号就提前闭合模板，
          *      esbuild 报 `Expected ";" but found "deepseek"`。CSS 注释里
          *      一律用普通引号，别用反引号。
          * 1.0.0-89：**推理等级被藏掉是错误取舍，本版改回显示。**
          *      v88 为了让模型名完整显示，把 _7KE1Ra_triggerEffort 直接
          *      display:none 了 —— 当时以为它恒为 "Default"（默认值，
          *      藏掉不丢信息）。真机核对 aria-label 才发现实际值是 **"High"**：
          *      选择模型，当前 deepseek-v4.1-flash，推理等级 High
          *      也就是说**用户设过的非默认推理等级被藏起来了**，实打实的
          *      信息丢失，不能接受。
          *      改回显示，并按宽度实测重新分配：模型名 126px（必须完整）
          *      + High 22px + 内边距/箭头 ⇒ 按钮 168px 刚好够。
          *      High 比 Default 短（22 vs 43），所以显示它不会把模型名挤掉
          *      （v88 之前挤掉正是因为 Default 太长）。
          *      字号 10px + opacity 0.65：它是次级信息，不该和模型名抢视觉重心。
          *      ⚠️ 两个戳都 bump（本常量 88 → 89，-CompatVersion 22 → 23）。
          * 1.0.0-90：**思考正文不换行，把整页推出屏幕左侧**。
          *      症状（真机截图，一眼可见）：整个消息列表向左偏出屏幕，
          *      左侧文字被切一半（「已完成」只剩右半、消息操作图标只剩右半边），
          *      右侧反而留白；页面还能被横向拖动。
          *      根因：思考（reasoning）正文的 span 带 white-space: nowrap，
          *      一行到底不换行 —— 实测最长一条 **1197px**（视口 360px）。
          *      它把滚动容器 .wSkVaW_root 的 scrollWidth 从 360 撑到 **550**，
          *      浏览器为显示光标把 scrollLeft 自动设成 **39.67**，
          *      于是整页看起来左移了 40px（.wSkVaW_scrollBody 的 x = **-40**）。
          *      修法两处：① 正文恢复换行（white-space: normal +
          *      overflow-wrap: anywhere 兜住无空格长串）；
          *      ② ._root_1rdzk_1 官方写的 width: max-content 会按内容原始宽度
          *      撑开，改成 width: auto 才服从父级 100% 约束。
          *      实测：最长文本 1197 → 225px，scrollLeft 归 0，bodyX 回到 0，
          *      手势横向拖动无效（已实测 swipe 前后 scrollLeft 都是 339.67）。
          *      ⚠️ 两个戳都 bump（本常量 89 → 90，-CompatVersion 23 → 24）。
          * 1.0.0-91：**模型名在老会话里又被挤掉**——v89 的补丁不完整。
          *      v89 只保证了推理等级为 High（22px）时放得下；但真机打开一个
          *      老会话时等级是 **Default（33px）**，模型名立刻退回
          *      deepseek-v4.1-fl... 逐值实测（按钮固定 168px，模型名要 122px）：
          *        Default 33 → 模型名 115（截断）
          *        Medium  37 → 模型名 111（截断）
          *        Minimal 36 → 模型名 112（截断）
          *        High/Low/Max 19~22 → 126~129（放得下）
          *      结论：光调 flex 治不了根，**必须让文案本身变短**。
          *      新增 mpAbbreviateEffort()：按表把英文等级缩成中文
          *        Default→默认 / Medium→中 / Minimal→最低 / Low→低 /
          *        High→高 / Max→最高 / XHigh→超高
          *      实测缩写后每个都 ≤20px，模型名一律 128~138px，**任何等级都不截断**。
          *      完整英文保留在 title 与 aria-label 上，可访问性不受影响。
          *      挂载点三处：300ms observer 批处理、scheduleStatsSync 微任务、
          *      启动爆发循环（保证首屏不闪一下截断）。
          *      ⚠️ 两个戳都 bump（本常量 90 → 91，-CompatVersion 24 → 25）。
          * 1.0.0-92：**模型名与推理等级粘连**——v91 引入的视觉回归。
          *      v91 把推理等级改成中文后，「deepseek-v4.1-flash默认」**挤成一片**
          *      没有间距。根因：v89 为挤宽度把 _7KE1Ra_trigger 的 gap 压到 1px，
          *      英文 High 有右侧字距留白看着还行，中文字形紧凑，1px 就是粘连。
          *      但直接加大 gap 会让模型名重新截断（实测 gap 3px → 模型名 119 < 122）。
          *      解法：从别处腾 4px —— 官方 uV2eYG_row 的 gap 是 12px，
          *      而 tools 只占 80px，中间那 12px 纯浪费，缩到 8px。
          *      实测：按钮 163 → 167px，模型名 123px（= 内容宽度，零截断），
          *      推理等级间距 3px（不粘连）。
          *      ⚠️ 两个戳都 bump（本常量 91 → 92，-CompatVersion 25 → 26）。
          * 1.0.0-93：**短模型名下出现幽灵间距**。
          *      换到 agnes-3.0-flash 时，「agnes-3.0-flash    默认」中间空出一大块，
          *      而 deepseek-v4.1-flash 完全正常 —— 所以一直没暴露。
          *      根因：官方给 label 设了 text-align: center，而 v89 起我写成
          *      flex: 1 1 auto ⇒ 盒子撑满剩余空间、文字居中、两边各留一半空白。
          *      实测（按钮 172px）：
          *        agnes-3.0-flash  盒子 128 / 文字 94 ⇒ 真实间隙 20px
          *        deepseek-v4.1-flash 盒子 122 / 文字 122 ⇒ 贴满，看不出
          *      ⚠️ 改成 text-align: left 反而更糟（空白全推到右边，间隙 37px）。
          *      正解是让盒子按内容收缩：flex 1 1 auto → **0 1 auto**
          *      （0=不撑满，1=仍可压缩，超长模型名照样出省略号）。
          *      实测：短名 94=94 间隙 3px；长名 122=122 间隙 3px；
          *      超长名 盒子 128 / 文字 287 ⇒ 截断出省略号，布局不破。
          *      ⚠️ 两个戳都 bump（本常量 92 → 93，-CompatVersion 26 → 27）。
          * 1.0.0-94：**整页能横向滚 114px**，点「更多操作」页面就左偏。
          *      现象：点右上角「⋯」后整个界面左移 114px，标题栏按钮从 x=294 跑到 180，
          *      输入框/发送键/遥测栏一起跟着跑（bodyX = -114、root.scrollLeft = 114）。
          *      排查：菜单本身只有 148px 宽不溢出（排除）；全树扫「右边界超视口 2px」
          *      命中数 0（排除）；点「刷新」关菜单后 scrollLeft 自动回 0（非持续偏移）。
          *      真凶是官方一对**宽度探针** .wSkVaW_widthHandle（absolute、width:0、
          *      height:691、left:-184px / left:544px，视觉上完全不可见，官方靠它们量宽），
          *      它们把 body 的 scrollWidth 撑到 550（360 视口 + 184 左 + 6 右），root 跟着变 550。
          *      ⚠️ 阴在：root 本来就是 overflow-x:hidden，手指横滑**没反应**
          *      （实测 339.67 → 339.67 纹丝不动），用户很难自己发现；
          *      但 hidden **只挡手势、不挡程序化滚动** —— 实测 scrollLeft=50 照样成功、
          *      bodyX 立刻 -50。所以任何一次「把元素滚进视口」的动作（点视口外按钮、
          *      键盘补偿滚动、scrollIntoView）都会把页面永久推偏，而用户既滑不回来、
          *      也不知道发生了什么。
          *      修法：body 加 overflow-x: **clip**（不是 hidden —— clip 不产生滚动容器，
          *      程序化滚动也失效）。实测探针不受影响（x 仍 -184/544、h 691、offsetW 0），
          *      root.scrollWidth 550 → 360，scrollIntoView 后 bodyX 恒 0。
          *      ⚠️ 两个戳都 bump（本常量 93 → 94，-CompatVersion 27 → 28）。
          * 1.0.0-95：**宽表格右侧 226px 在手机上永远看不到**。
          *      现象（真机截图一眼可见）：助手回复里的宽表格被右边缘硬切掉，
          *      表头「目标 / p50 / p95 / max」只显示到 p95，max 整列看不到，
          *      p95 的值（25ms / 1709）也被切一半。
          *      实测两个宽表格：容器 294px，内容 512 / 520px ⇒ 藏掉 218 / 226px。
          *      根因：官方给 .md-table-wide 设的是 **overflow-x: hidden**（不是 auto），
          *      本意是桌面端 —— 容器带 tabindex="0"，靠**键盘**左右滚。
          *      但手机上既没键盘、hidden 又**挡掉触摸滑动**：
          *      实测 dispatch touchstart+touchmove 后 scrollLeft 仍是 0。
          *      ⇒ 右侧内容**永久不可达**，这是移动端信息丢失，不是设计取舍。
          *      修法：改回 overflow-x: auto + iOS 惯性滚动。
          *      实测：scrollLeft 可到 218 / 226，四个表头**逐个**都能滚到可见
          *      （scrollIntoView 验证 0 → 86 → 218），max / 一致性列完整显示。
          *      ⚠️ .md-table-wide 是**语义类名**（非构建期哈希），稳定性高。
          *      ⚠️ 两个戳都 bump（本常量 94 → 95，-CompatVersion 28 → 29）。
          * 1.0.0-96：任务卡片半透明 ⇒ 底下的正文**透上来**，主次颠倒。
          *      现象（截图一眼可见）：任务卡片（「任务 5 已完成」+ 绿点清单）浮在
          *      正文上，背景只有 58% 不透明，下面那条消息的文字
          *      （「错误信息是「特权通道不可用」…」）透过卡片显出来，和清单叠成一团。
          *      实测配色正是难看的原因：背后正文 rgb(15,17,21) 近黑，
          *      卡片背景 rgba(248,249,250,0.58) 58% 浅灰，卡片自身文字 rgb(97,102,107)。
          *      混色 0.58×248 + 0.42×15 ≈ 150 ⇒ 透出来的正文约 rgb(150,152,155)，
          *      **比卡片内文字还亮** ⇒ 被遮挡的内容比该读的内容更显眼。
          *      ⚠️ 不是 backdrop-filter 没生效：实测内核是 Chrome **114**（不是降级目标 74），
          *      backdrop-filter 支持且 computed 就是 blur(40px) saturate(1.5)，
          *      祖先链也无 filter/opacity/transform 破坏。纯粹是基础背景太透明。
          *      修法：不透明度提到 0.97（保留毛玻璃质感、不透字），透出率 42% → 3%。
          *      亮色 rgba(248,249,250,0.97)、暗色 rgba(67,69,74,0.97) —— **必须分开写**，
          *      两边底色不同，不能共用一个颜色。
          *      ⚠️ 不改"卡片遮挡正文"本身：卡片在 composerStack 内与 composerSeat 同级，
          *      官方就是做成浮动面板（实测 30%/50%/70%/90% 都有 1~6 条消息被压住，
          *      只有滚到底部才不重叠）。根治要改官方布局，风险远大于收益。
          *      ⚠️ 两个戳都 bump（本常量 95 → 96，-CompatVersion 29 → 30）。
          * 1.0.0-97：两处**对比度不达标**，亮/暗主题下都读不清（+ 75）。
          *      【】底栏推理等级「默认」：实测亮色 **2.18:1**、暗色 **2.42:1**
          *      （WCAG AA 正文要求 4.5:1）。根因是我在里加的 opacity: 0.65 ——
          *      方向对（次级信息不该抢重心）但太狠：实测把 opacity 拉到 1 也只有
          *      3.71~3.76:1，**光调透明度永远不够**，颜色本身 rgb(129,133,140) 就偏浅。
          *      也不能只改一个颜色：亮底(白)要深色、暗底(rgb(44,44,46))要浅色，
          *      实测同一颜色不可能两边达标（提亮 173,178,184 → 暗 6.53 ✅ / 亮 2.13 ❌；
          *      压深 97,102,107 → 亮 5.80 ✅ / 暗 2.40 ❌）。⇒ 必须写两套。
          *      最终：亮 rgb(97,102,107) 5.80:1、暗 rgb(173,178,184) 6.53:1，opacity 一律 1。
          *      【】输入框占位符：亮色 **2.13:1**、暗色 **3.76:1**。这是用户
          *      **每次发消息都会看到**的提示文字。颜色来自官方变量 label-dimmed。
          *      ⚠️ 但不能一把拉满：占位符故意比正文淡是有语义的（区分"还没输入"与
          *      "已输入内容"），拉满会让人误以为已经填好了。⇒ 取**刚过 AA**：
          *      亮 rgb(112,117,122) 4.65:1、暗 rgb(165,170,176) 5.96:1。
          *      顺带把同色系的「代码块」标签与「快准狠模式（通用）」也从 ~3.5 提到 4.65。
          *      ⚠️ 两个戳都 bump（本常量 96 → 97，-CompatVersion 30 → 31）。
          * 1.0.0-98：抽屉里两处次级文字读不清。
          *      用那套对比度扫描把侧边抽屉也过了一遍，命中 6 处 < 4.5:1，
          *      其中两处值得修（都是 rgb(129,133,140) → 3.55:1）：
          *        · .bhn1Oq_sectionLabel         「工作区」分组标题
          *        · .bhn1Oq_sessionOverflowButton「展开其余 2 个会话」
          *      后者尤其要紧：它是**可点击的操作**，文字太淡会让人看不出能点，
          *      直接影响会话列表的可用性。
          *      ⚠️ 只修这两处，没把 6 处全拉高：其余（时间戳、上下文 68%、
          *      「空面板」提示、任务卡「5 已完成」）都是**纯次级信息**，全拉到 4.5
          *      会让抽屉失去层次感、看起来一片同等重要。这两处是"导航结构"和
          *      "可点操作"，性质不同才单独提上来。
          *      取值同亮 rgb(105,110,115) 4.93:1 / 暗 rgb(165,170,176) 7.36:1。
          *      ⚠️ 两个戳都 bump（本常量 97 → 98，-CompatVersion 31 → 32）。
          * 1.0.0-99：设置页所有说明副标题只有 3.71:1。
          *      设置里每项标题下那行灰字（「选择新会话的默认权限模式」
          *      「仅影响会话内容的字号」「选择希望看到多少工具调用细节」）是
          *      **用户理解这个开关干什么的唯一说明**，太淡等于没有说明。
          *      实测 rgb(129,133,140) 叠白底 → 3.71:1。
          *      ⚠️ 用 [class*="_desc"] 属性选择器而不是逐个写类名：这批文字
          *      **至少三个不同类名**（.oY77xG_desc / .bVCLcG_desc / ._2XZxNq_desc
          *      / .Pt1bsG_description / .T1PP_q_desc），各模块各自起名，逐个列既漏
          *      又难维护（官方一改 hash 前缀就全失效）。实测命中 8 个，颜色只有两种：
          *      6 个 3.71（不达标）+ 2 个 5.8（已达标），统一提亮不会误伤后者。
          *      取值亮 rgb(105,110,115) 5.15:1 / 暗 rgb(165,170,176) 5.96:1。
          *      ⚠️ 两个戳都 bump（本常量 98 → 99，-CompatVersion 32 → 33）。
          * 1.0.0-100：合并 PR #3 —— 悬浮气泡分节新增「键盘避让」卡片
          *      （46×26 开关 + 无障碍权限检测 + 独立「去开启权限」按钮）。
          *      改的仍是 `mobileui/lib/client.js`，降级产物随之变化，必须 bump。
          *      ⚠️ PR #3 原作把本常量写成 1.0.0-68、`-CompatVersion` 写成 24，
          *      那是基于旧的 66/22 算的。合并时**必须取本地已推进的值再 +1**
          *      （99 → 100 / 33 → 34），否则会回退，设备上静默跑回没有这张卡的旧 UI。
          *      ⚠️ 两个戳都 bump（本常量 99 → 100，-CompatVersion 33 → 34）。
          * 1.0.0-103：两条移动端修复（用户截图报的两个问题）。
          *      ① —— 输入框点中间落行尾。0.1.5 起官方把输入框从
          *         `<textarea>` 换成 contenteditable div，Android WebView 在这上面
          *         的「像素 → 字符偏移」算得很差（点中间、光标跳行尾）。改为在
          *         `click` 上自己用 caretPositionFromPoint 定光标；只在
          *         「空选区 + 落点明显不同」时动手，不干扰长按选词/扩选/组字。
          *      ② —— 思考正文两行竖直叠在一起糊成一团。流式追加时容器
          *         高度更新落后于文字一帧（叠加 `overflow-wrap: anywhere` 让每行
          *         宽度不可预测），改为给思考正文显式 line-height + contain: layout。
          *      本版是**纯 JS 改动**（只有 client.js），未动 CSS 之外的产物，
          *      但 `mobileui/lib/client.js` 是降级编译输入，字节变了，
          *      所以本常量必须 bump（否则设备上继续跑旧 client.js，两条修复都收不到）。
          * 1.0.0-104：的实现修正（103 那版有缺陷，未发布到设备即被本版取代）。
          *      ① 原实现用 `e.target` 当编辑区容器做 contains 判据 —— 点文字时
          *         target 是 Lexical 渲染的行内 span、点空白处是 root、点 <br> 又是
          *         另一个，判据会漏掉一大半情形。改成先 closest 找 composer 根，
          *         再在根里找 contenteditable 本体。
          *      ② 原实现在 click 里**同步**改 selection，而 Lexical 自己也监听
          *         click 并会把 selection 写回内部 model，随后覆盖我们设的位置 ——
          *         用户看到「光标闪一下又跳回行尾」。改成 setTimeout(0) 延后到
          *         本次事件处理完之后，让浏览器自然派发 selectionchange，
          *         Lexical 跟着同步。不碰它的内部 API。
          *      ③ 同时把的根因注释改成诚实表述：原注释把推断写成了
          *         「实测」，而这一条**没有真机逐帧验证过**，会误导后来人。
          * 1.0.0-105：用户反馈的《移动端 UI 修复报告》里三条**在当前源码中真实存在**
          *      的缺陷（报告其余条目描述的是手机上手工打的补丁，源码里没有）。
          *      ① —— 过程行标题「一直抽搐」。为了治「思考正文不换行
          *         把整页推出屏幕」，把官方 TextShimmer 的 `._root_1rdzk_1`
          *         （`width: max-content`，标题单行的唯一保证）无差别改成了 `auto`。
          *         流式标题因此按可用宽度换行，行数 1→2→3 反复变，容器高度跟着弹。
          *         改为**只在 `.O_Ebla_title`（过程组标题）内部**把 max-content 还回去，
          *         思考正文章节完全不动；顺带把扫光的 `steps(48)` 改成 `linear`。
          *      ② —— 底部统计条字号突然变大。`processStatsLine` 原先直接取
          *         `[data-composer-stat]`（anchor 层）当 label，`textContent` 赋值会
          *         **删掉内部 button.bOPqQW_pill + span.bOPqQW_label**，只剩裸文本，
          *         字号从官方 13px 变成继承 body 的 16px，撑满底栏。
          *         改为先取下钻到 `.bOPqQW_label`，取不到才退回 anchor（保证降档仍触发）。
          *      ③ —— 点「刷新」后 401、会话列表空、回首页。页面侧原来是
          *         整页 `location.reload()`，而重载请求的是 `./`、不再带启动时那条
          *         `?token=`，于是 API / WebSocket 全部 401，只能重启 App 恢复。
          *         改为走原生桥新增的 `reloadWebUi()`：重载 `lastLoadedUrl`
          *         （带 token 的原始地址），并复用 `reloadWithRetry` 那套
          *         「重置首帧 → 重载 → 等新首帧 → 失败重试」判据。
          *         刷新前先 `mpPersistCurrentSession()` 落盘当前会话，避免跳槽。
          *      ⚠️ 本版**动了 Kotlin**（PageBridge / SystemWebHost / GeckoWebHost /
          *         MainActivity / content.js 五处），不只是 client.js。
          *      本常量仍必须 bump：不然设备上的 `.mobile_ui_version` 不变，
          *      mobile-ui.zip 解包被整体跳过，三条修复一条都收不到。
          */
        /*
         * ## 110（2026-10-31）：`onUiReady` 可能永远不来 ⇒ 启动层永久盖住界面
         *
         * 真机现场（20261031-053321，vivo V2231A / gecko 92）：
         *
         *     05:29:46  engine.ready ms=8537 port=3080     ← 引擎完全正常
         *     05:29:58  界面就绪回调超时（内核140，页面错误: 无）
         *     05:30:13  界面就绪回调超时                  ← 27 秒仍未就绪
         *
         * 三处根因，都改在 `mobile-ui-plugin/dsh-android-mobile/lib/client.js`：
         *
         *   ① `mpSignalUiReady()` 原排在 `apply()` **末尾**，前面 30 多个初始化
         *      全无 try/catch。任何一个抛异常 ⇒ 它永不执行 ⇒ `onUiReady` 永不上报。
         *      ⇒ 现已提到 `apply()` 最前面并 try 包裹。
         *   ② 桥不在时 `if (!bridge) return;` 直接退出。而 gecko 的桥由
         *      WebExtension 注入，**早于还是晚于本插件不保证** ⇒ 晚到时永久丢失。
         *      ⇒ 改为轮询等桥（15 秒），等到就照常按判据上报。
         *   ③ `ready()` 要求 `readyState === "complete"`。页面只要有一个资源
         *      挂住（或渲染进程被回收重挂），就永远停在 `interactive`。
         *      ⇒ 外壳（`data-mobile` + `.mp-nav-btn`）就位后再宽限 2.5 秒即交付。
         *
         * ⚠️ 本版**只动了 client.js**（Kotlin 未改），但常量**必须** bump：
         *   否则 `.mobile_ui_version` 不变，新 zip 解包被整体跳过 —— 这个失败是
         *   完全静默的（见 AGENTS.md 三条最容易违反的规矩之一）。
         */
const val MOBILE_UI_VERSION = "1.0.0-130"
const val MOBILE_UI_STAMP = ".mobile_ui_version"

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
        internal const val PROFILE_WEB = "web"

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

        // ── uid 2000 特权通道 ────────────────────────────────────────
        //
        // 把 shell 身份执行注册成一个**独立的 `shell` 工具**（不是改 bash 的行为）。
        // 机制、实测数据与取舍见 assets/dsh-android-shell.mjs 与
        // mobile-ui-plugin/dsh-android-shell/lib/index.js 的头部注释。

        /** uid 2000 执行通道脚本（直连本机 adbd，走 ADB 协议）。 */
const val PRIVILEGED_RUNNER_ASSET = "dsh-android-shell.mjs"
const val PRIVILEGED_RUNNER_FILE = "dsh-android-shell.mjs"

        /** 注册 `shell` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val SHELL_TOOL_ASSET = "shell-tool.zip"
const val SHELL_TOOL_PACKAGE = "dsh-android-shell"
const val SHELL_TOOL_PATCH = "shell-tool.patch.yml"

        /**
         * 插件解包目录，相对 `$DSH_HOME`。
         *
         * ⚠️ **刻意不放在 profile 的 `node_modules` 下** —— 与移动端 UI 插件同一个坑：
         * 那里归 pnpm 管，而本包不在 profile 的 `dependencies` 里，用户每装一次插件
         * 就会被挪进 `node_modules/.ignored/`，overlay 里的包名随即解析失败。
         * 详见 [ensureMobileUi]。
         */
const val SHELL_TOOL_DIR = "shell-tool"

        // ── root（uid 0）特权通道 ────────────────────────────────────
        //
        // 与 uid 2000 通道**同一套做法、同一套取舍**（见上面那段），只是身份档位不同：
        // 注册一个独立的 `root` 工具，仅在**宿主探测到确实能提权**时才挂载。
        // 检测方式与「存在 su ≠ 有 root」的三种假阳性见 [RootAccess]。

        /** root 执行通道脚本（经 `su -c` 以 uid 0 执行）。 */
const val ROOT_RUNNER_ASSET = "dsh-android-root.mjs"
const val ROOT_RUNNER_FILE = "dsh-android-root.mjs"

        /** 注册 `root` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val ROOT_TOOL_ASSET = "root-tool.zip"
const val ROOT_TOOL_PACKAGE = "dsh-android-root"
const val ROOT_TOOL_PATCH = "root-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 与 [SHELL_TOOL_DIR] 同理，刻意在 pnpm 管辖之外。 */
const val ROOT_TOOL_DIR = "root-tool"

        // ── 悬浮球控制工具 ───────────────────────────────────────────
        //
        // 与 shell / root 那两个**同一套做法**（独立工具 + 刻意在 pnpm 管辖之外），
        // 差别只在能力本身：那两个给的是别的身份，这个给的是**改悬浮窗**。
        //
        // 为什么需要它：悬浮球的外观接口只有**页面侧**插件够得着
        // （`window.DSHAndroid.bubbleApply()`），而模型跑在 Node 进程里，那里没有
        // `window` —— 于是「让 AI 改悬浮窗」在可发现性上是空的。这个插件把同一条
        // 能力注册成一个 `bubble` 工具，并靠 [BubbleCommandBridge] 的文件信箱
        // 把命令送回原生。详见 `mobile-ui-plugin/dsh-android-bubble/lib/index.js`。

        /** 注册 `bubble` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val BUBBLE_TOOL_ASSET = "bubble-tool.zip"
const val BUBBLE_TOOL_PACKAGE = "dsh-android-bubble"
const val BUBBLE_TOOL_PATCH = "bubble-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
const val BUBBLE_TOOL_DIR = "bubble-tool"

        // ── 系统栏配色工具 ───────────────────────────────────────────
        //
        // 与 bubble 工具**同一套做法**（独立工具 + 文件信箱 + 刻意在 pnpm 管辖之外），
        // 差别只在能力：那个改悬浮窗，这个改**状态栏（顶部）与导航栏（底部）的配色**。
        //
        // 为什么需要它：那两条栏原先**只由页面底色驱动**（页面侧上报 → 原生一并刷），
        // 没有任何独立入口 —— 用户改不了，模型更够不着。这个插件把同一条能力注册成
        // 一个 `statusbar` 工具，并靠 [SystemBarCommandBridge] 的文件信箱把命令送回
        // 原生。详见 `mobile-ui-plugin/dsh-android-statusbar/lib/index.js`。
        //
        // ⚠️ 与 bubble / ui 工具走**不同的信箱文件**：命令可能并发发起，回执按 `id`
        //   配对，而各插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。

        /** 注册 `statusbar` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val STATUSBAR_TOOL_ASSET = "statusbar-tool.zip"
const val STATUSBAR_TOOL_PACKAGE = "dsh-android-statusbar"
const val STATUSBAR_TOOL_PATCH = "statusbar-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
const val STATUSBAR_TOOL_DIR = "statusbar-tool"

        // ── UI 调试工具 ──────────────────────────────────────────────
        //
        // 与 bubble 工具**同一套做法**（独立工具 + 文件信箱 + 刻意在 pnpm 管辖之外），
        // 差别只在能力：那个改悬浮窗，这个**看和点页面里的界面**。
        //
        // 为什么需要它：界面是官方 DSH Web GUI 跑在 WebView 里，按钮全是 React
        // 渲染的 DOM；而模型跑在 Node 进程里，那里既没有 `window` 也没有 `document`
        // —— 于是「让 AI 自己测试这个 App」在能力上是空的。这个插件把「枚举界面
        // 上的按钮（带屏幕精确坐标）/ 点击 / 输入 / 滚动 / 读文本」注册成一个 `ui`
        // 工具，靠 [UiProbeBridge] 的信箱把命令送到原生、再由原生经
        // `WebHost.evaluateJavascript` 打进页面里的探针（`assets/ui-probe.js`）。
        //
        // ⚠️ 与 bubble 工具**走不同的信箱文件**：命令可能并发发起，回执按 `id`
        //   配对，而两个插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。
        //   理由与 `bubble-state.json` 独立成文件完全一致。

        /** 注册 `ui` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val UI_TOOL_ASSET = "uiagent-tool.zip"
const val UI_TOOL_PACKAGE = "dsh-android-uiagent"
const val UI_TOOL_PATCH = "uiagent-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。同样刻意在 pnpm 管辖之外。 */
const val UI_TOOL_DIR = "uiagent-tool"

        // ⚠️ 这里原本有 `UI_PROBE_ASSET = "ui-probe.js"`，已删除：从未被使用。
        //   探针资产的实际读取点不在本类（资产名请以 `assets/ui-probe.js` 为准，
        //   见 [UiProbeBridge] 与 `EngineManager` 的注释）。

        // ── 日志工具 ─────────────────────────────────────────────────
        //
        // 与 bubble / statusbar / ui 工具**同一套做法**（独立工具 + 文件信箱 +
        // 刻意在 pnpm 管辖之外），差别只在能力：那些改外观/点界面，这个**读日志**。
        //
        // 为什么需要它：日志系统的两个入口（设置页分节、启动/崩溃浮层按钮）都是给
        // **人**的，而模型跑在 Node 进程里 —— 它看不到原生侧的内存缓冲，也看不到
        // `filesDir/logs` 下的 `app.log` 与崩溃现场。于是「你看看日志」这句话对模型
        // 是空的，它只能猜。这个插件把「读日志 / 看崩溃现场 / 生成诊断包」注册成一个
        // `logs` 工具，靠 [LogCommandBridge] 的文件信箱把请求送回原生。
        // 详见 `mobile-ui-plugin/dsh-android-logs/lib/index.js`。
        //
        // ⚠️ 与上面三个工具**走不同的信箱文件**：命令可能并发发起，回执按 `id`
        //   配对，而各插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。
        //   这类错配的表现是「AI 说日志里没有这条错误」，比报错危险得多。
        //
        // ⚠️ **没有「能力探测」这一步**：读日志不需要任何授权。只要插件入口在就挂载，
        //   与 [writeBubbleToolPatch] 同理。

        /** 注册 `logs` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val LOGS_TOOL_ASSET = "logs-tool.zip"
const val LOGS_TOOL_PACKAGE = "dsh-android-logs"
const val LOGS_TOOL_PATCH = "logs-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
const val LOGS_TOOL_DIR = "logs-tool"

        // ── 浏览器工具 ───────────────────────────────────────────────
        //
        // 与 bubble / statusbar / ui / logs **同一套做法**（独立工具 + 文件信箱 +
        // 刻意在 pnpm 管辖之外），差别只在能力：那些操作**这个 App**，这个**上网**。
        //
        // 为什么需要它：引擎自带的侧栏浏览器是 iframe，而 GitHub
        // （`X-Frame-Options: deny`）、Google（CSP）拒绝被嵌入 —— iframe 方案
        // 连登录页都加载不出来。所以换成原生 `AiBrowserView`（真 WebView，
        // 跑在用户手机上），本插件把「开网页/读页面/点按钮/填表单」注册成
        // `browser` 工具，靠 [com.dsh.android.browser.BrowserBridge] 的文件信箱
        // 把命令送回原生。详见 `mobile-ui-plugin/dsh-android-browser/lib/index.js`。
        //
        // ⚠️ **信箱必须是独立文件**：命令可能并发发起，回执按 `id` 配对，
        //   而各插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。
        //
        // ⚠️ **没有「能力探测」这一步**：浏览器不需要任何授权，只要入口在就挂载。
        //   浏览器实例由 [com.dsh.android.ui.MainActivity.ensureAiBrowser] 按需创建。

        /** 注册 `browser` 工具的 host 插件；打包成 zip 随 APK 分发。 */
const val BROWSER_TOOL_ASSET = "browser-tool.zip"
const val BROWSER_TOOL_PACKAGE = "dsh-android-browser"
const val BROWSER_TOOL_PATCH = "browser-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
const val BROWSER_TOOL_DIR = "browser-tool"

        // ── 右侧栏「AI 浏览器」入口（**客户端插件**）──────────────────────
        //
        // 与上面那些**工具插件**不同：这个包不含任何工具，它只在前端注册一个
        // 右侧栏 tab，点开就调原生 `openAiBrowser()`。所以它必须在**引擎启动前**
        // 落盘 —— 引擎启动时才扫描 `dsh.client` 声明并编译前端 bundle。
        //
        // ⚠️ 单独成包、不复用 `mobile-ui.zip`：那个 zip 是**跨工作树共享的符号
        //   链接资产**，dev 分支的 `client.js` 比 main 多 17 万字节；重打它会把
        //   另一条分支的移动端修复整个回退掉。
const val BROWSER_PANE_ASSET = "browser-pane.zip"
const val BROWSER_PANE_PACKAGE = "dsh-android-browser-pane"
const val BROWSER_PANE_PATCH = "browser-pane.patch.yml"
const val BROWSER_PANE_DIR = "browser-pane"

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

        // ── 桌面宠物状态桥 ───────────────────────────────────────────
        //
        // 与悬浮球控制工具**分开两个包**，理由见 [ensurePetBridge] 的注释。
        // 它订阅 `session/event`，把运行状态写进 `$DSH_HOME/bubble-state.json`。
        //
        // ⚠️ 与 `bubble` 工具走**不同的信箱文件**：回执按 `id` 配对，而两者各自
        //   从 1 自增，共用文件会让工具调用收到状态播报的回执。见
        //   `BubbleCommandBridge.STATE_FILE` 的注释。

        /** 状态桥插件；打包成 zip 随 APK 分发。不做版本戳（每次启动覆盖写）。 */
const val PET_BRIDGE_ASSET = "pet-bridge.zip"
const val PET_BRIDGE_PACKAGE = "dsh-android-pet"
const val PET_BRIDGE_PATCH = "pet-bridge.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。同样刻意在 pnpm 管辖之外。 */
const val PET_BRIDGE_DIR = "pet-bridge"

        /**
         * 无线调试（Android 11+）用的 adb 工具链。
         *
         * 取自 Termux 官方 `android-tools` 包的 `adb`（37.0.0），外加它 NEEDED 的
         * 全部 `.so`（`libprotobuf` / `libfmt` / `libabsl_*` / `libbrotli*` /
         * `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`）。
         * 理由与踩过的坑见 [ensureAdbWireless]。
         */
const val ADB_WIRELESS_ASSET = "adb-wireless-arm64.zip"
const val ADB_WIRELESS_DIR = "adbwire"

        /**
         * 无线调试 adb 工具链的**完整性哨兵**（落在 [ADB_WIRELESS_DIR] 内）。
         *
         * 只在 107 个条目全部写完之后才落盘，因此「哨兵存在」= 解包完整走完过。
         * 幂等判据必须带上它，否则一次中断留下的半成品会被永久认定为「已完成」——
         * 详见 `ensureAdbWireless()`。
         */
const val ADB_WIRELESS_STAMP = ".extracted"

        /** 特权身份说明写进这个文件 —— agent-instructions 会自动读它并注入系统提示词。 */
const val INSTRUCTIONS_FILE = "AGENTS.md"

const val PRIVILEGED_PROMPT_BEGIN = "<!-- dsh-android:privileged BEGIN（本段由客户端生成，勿手改）-->"
const val PRIVILEGED_PROMPT_END = "<!-- dsh-android:privileged END -->"

const val ROOT_PROMPT_BEGIN = "<!-- dsh-android:root BEGIN（本段由客户端生成，勿手改）-->"
const val ROOT_PROMPT_END = "<!-- dsh-android:root END -->"

const val RESCUE_PROMPT_BEGIN =
            "<!-- dsh-android:rescue BEGIN（本段由客户端生成，勿手改）-->"
const val RESCUE_PROMPT_END = "<!-- dsh-android:rescue END -->"

        /**
         * root 可用时写给模型的说明。
         *
         * 与 uid 2000 那段同样，重点在**边界**：什么时候用、什么时候别用、
         * 失败长什么样。root 的边界与 shell 不同 —— 它能读到 app 私有目录，
         * 所以「读工作区」不再是禁区；但**沙箱与 link 转译依然不生效**。
         */
val ROOT_PROMPT_ENABLED = """
            ## root 身份（uid 0）

            本机**已确认可以提权**，因此多了一个 `root` 工具：

            | 工具 | 身份 | 能力边界 |
            |---|---|---|
            | `bash`（默认） | 应用身份 | 工作区、沙箱、link 转译全部正常 |
            | `shell` | uid 2000 | 系统级操作；读不到应用私有目录 |
            | `root` | **uid 0** | 内核/分区/SELinux 等最高权限操作 |

            ### 什么时候该用 `root`

            只有 `shell`（uid 2000）也做不到时才用：

            - 读写 `/data` 下非本应用的数据（`/data/local/tmp`、`/data/misc`、别的应用私有目录）
            - `mount` / 重新挂载分区、改 SELinux 策略或标签（`setenforce`、`chcon`）
            - `iptables`、`/proc/sys` 下的内核参数
            - 读改属主是 `root` 且不 world-readable 的文件

            截图、点击、装卸载应用、`settings`、`appops`、`dumpsys` 这类
            **`shell` 就够了，优先用 `shell`** —— 权限越小越安全。

            ### ⚠️ `root` 通道的两个专属退出码

            | 退出码 | 含义 |
            |---|---|
            | **77** | 没拿到 root（没有 `su` / 被拒 / 授权框超时）—— **命令根本没跑** |
            | **78** | `su` 跑了但身份不是 uid 0 —— 命令**没有真正以 root 执行** |

            看到这两个码：**不要重试、不要假设操作成功**，如实告诉用户需要重新授权
            root 权限。它们与命令自己的非零退出码是两回事。

            ### ⚠️ 用了 `root` 会失去什么

            与 `shell` 一样：**没有写沙箱、没有 link 转译**（`LD_PRELOAD` 传不进去）。
            这条通道上的任何写操作都**不会被拦**，误删就是真删。执行前把命令的作用
            写进 `description`，破坏性的操作先跟用户确认。
        """.trimIndent()

        /**
         * root 不可用时写给模型的说明。
         *
         * ⚠️ 这里**不给引导步骤**（与 shell 那段不同）：root 只能由用户自己在设备上
         * 装（Magisk / KernelSU）并授权，App 侧没有任何办法推进。所以只需明确
         * 「没有这个能力」+「不要瞎试」，否则模型会去反复跑 `su` 然后被 77 号码困住。
         */
val ROOT_PROMPT_DISABLED = """
            ## root 身份（uid 0）—— 当前不可用

            本机**没有**可用的 root：探测未发现 `su`，或存在 `su` 但拿不到 uid 0
            （未授权 / 被拒 / 授权框超时）。**`root` 工具因此没有被注册**，
            你只有 `bash` 与（若可用）`shell`。

            在此之前：

            - **不要**尝试用任何方式绕过：`su`、`sudo`、`magisk`、`busybox` 之类的
              调用都不会成功，只会浪费一轮。
            - 读写 `/data` 下别的应用的数据、`mount`、改 SELinux、`iptables` 这类
              操作**当前一律做不到**，如实告诉用户即可，不要反复重试。
            - 需要系统级操作时用 `shell`（uid 2000）—— 它能覆盖截图、输入注入、
              包管理、`settings`、`appops`、`dumpsys` 等绝大多数场景。
        """.trimIndent()

val PRIVILEGED_PROMPT_ENABLED = """
            ## 特权身份（uid 2000 / shell）

            这台设备上你有**两个工具**可跑命令，默认的那个是受限的：

            | 工具 | 身份 | 能力 |
            |---|---|---|
            | `bash`（默认） | 应用身份 | 读写工作区、跑脚本、文件操作；有写沙箱与 link 转译保护 |
            | `shell` | shell 身份（uid 2000） | 系统级操作；**无写沙箱、无 link 转译** |

            ### 什么时候该用 `shell`

            需要**操作设备本身**时。这些在应用身份下**做不到**，而且失败方式很隐蔽：

            | 操作 | 应用身份的真实表现 | `shell` |
            |---|---|---|
            | 截图 `screencap` | rc=1，**stderr 完全为空**，只留下 0 字节文件 | rc=0，真图 |
            | 点击/滑动 `input` | **rc=0 但屏幕纹丝不动** | rc=0，真的生效 |
            | 卸载应用 `pm uninstall` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
            | 读写系统设置 `settings` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
            | 强制停止 `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
            | 查看电池/窗口 `dumpsys` `wm` | rc=**0**，输出 `Can't find service` | 完整状态 |
            | 读输入设备 `getevent` | `/dev/input Permission denied` | 能枚举设备 |
            | 列出全部进程 `ps -A` | rc=0，**只看得见自己 uid 的 6 个进程** | 674 行 |

            ⚠️ **注意退出码骗人**：`input` 在应用身份下返回 0 却什么都没做；
            `dumpsys`/`ps` 返回 0 但结果是残缺的。所以**不要**用「退出码是不是 0」
            来判断命令有没有真正生效——上面的操作如果结果可疑，直接改用 `shell` 重试。

            ### 怎么用

            换工具名即可，**命令原文一字不改**：

            ```
            shell(command="screencap -p /sdcard/s.png",
                  description="截图到共享目录")
            ```

            ### ⚠️ 用了 `shell` 会失去什么

            shell 身份**读不到应用私有目录**（`/data/user/0/com.dsh.android` 是
            `drwx------`），因此：

            - **工作区、`${'$'}PREFIX`、`${'$'}DSH_HOME` 全部不可访问**。不要用 `shell` 跑
              `ls`、`cat`、相对路径这类依赖工作目录的命令——它们会失败或看到空目录。
              （`shell` 的 `workdir` 参数只对 `/sdcard` 这类共享路径有意义。）
            - **写沙箱不生效**：`touch /sdcard/x` 这类命令会真的写下去，不再被拦。
            - **link 转译不生效**：硬链接语义回到系统默认（应用私有目录里会 EACCES）。

            也就是说：**`shell` 换来的是特权，失去的是隔离**。只在该用的时候用。
            纯粹的读文件、写文件、跑脚本、装依赖，一律留在默认的 `bash`。

            ⚠️ 若 `shell` 的输出里出现 `${'$'}[dsh-adb] uid 2000 不可用`，说明特权通道
            此刻不可用，那条命令实际是以应用身份跑的——不要据此认为特权生效了，
            如实告诉用户需要重新引导。
        """.trimIndent()

val PRIVILEGED_PROMPT_DISABLED = """
            ## 特权身份（uid 2000 / shell）—— 当前不可用

            本机的 shell 身份通道**尚未启用**。**`shell` 工具因此没有被注册**，
            你只有 `bash`。

            现在有**三条**可用的开启方式，按对用户的代价从低到高：

            1. **Shizuku（最省事，推荐先说这条）** —— 一次启动就常驻，不需要数据线、
               不需要电脑、重启手机也依然有效。引导用户：装好 Shizuku → 在 Shizuku 里
               启动服务（它自己会提示怎么做，通常要先用电脑跑一次 adb，或直接用 root）
               → 回到本 App 的「设置 → 特权权限」点「授权」→ 点「重新检测」。
            2. **无线调试（仅 Android 11 及以上）** —— 本 App 能自己完成配对，不用电脑。
               在本 App 的设置页点「配对无线调试」，按通知栏提示走。
            3. **电脑引导（所有版本都能用，但每次重启手机后要重做一次）** ——
               用 USB 连上电脑后执行：

               ```
               adb tcpip 5555
               adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey
               ```

               然后回到本 App 点「重新检测」。

            ⚠️ **先说第 1 条**：第 3 条在 Android 10 及以下每次重启手机都要重做一遍
            （`persist.adb.tcp.port` 不可写），这是用户抱怨最多的一点。

            在通道启用之前：

            - **不要**尝试用任何方式绕过应用身份去执行特权命令，那只会回落成应用身份。
            - 截图、点击屏幕、卸载应用、改系统设置、读 `/dev/input` 这类操作
              **当前一律做不到**，如实告诉用户并说明上面三条路，不要反复重试。
        """.trimIndent()

        /**
         * 降级态下写给模型的说明（方案 §4.4 的 P4）。
         *
         * ## 为什么这段必须存在
         *
         * 整个方案的承诺是「App 一定打得开 + AI 帮你修」。前半段由 Kotlin 侧保证，
         * 后半段**只靠这段文本** —— 模型不知道 `rescue-cmd.json` 存在、不知道怎么
         * 验证改动，它就只会说「我建议你禁用那个插件」，然后用户还是打不开。
         *
         * ⚠️ 这段是**条件注入**的（只在降级态时写入），所以正文里可以放心说
         *   「你现在处于 X」。正常态下这段整个不存在，不占一个 token。
         *
         * ⚠️ 三条给模型的硬约束必须写清，否则它会做出有害的动作：
         *   ① 不许删插件（只能改名隔离）—— 用户可能还要它；
         *   ② 必须用 `try-boot` 验证，不能靠 `--dump-config` 猜（那是本方案的地基）；
         *   ③ **日志里的失败插件名不是元凶**（真机实测：引擎列 40 个，元凶不在里面）。
         */
val RESCUE_PROMPT = """
            ## 你正处于降级启动模式（插件把 App 搞崩了）

            上一轮启动时，用户装的第三方插件里有东西让引擎起不来。App 已经**自动
            降级**，用一个最小配置把你启动起来了 —— 所以你现在能用，但用户的插件
            配置处于降级状态。**用户很可能就是来找你修这个的。**

            ### 先看现状，不要猜

            用户在 App 顶部看到一条常驻横幅。你也可以自己查：

            - 当前层级、原因、被摘掉的插件：读 `${'$'}DSH_HOME/rescue/state.json`
            - 上次启动为什么失败：读 `${'$'}DSH_HOME/rescue/last-boot-failure.json`
            - 自动隔离时每一轮试启动的结果：`${'$'}DSH_HOME/rescue/attempts/`
            - 用户的原始插件清单备份：`profiles/web/package.json.bak-l1`

            想知道当前状态又不想读文件，可以发一条命令（见下）。

            ### ⚠️ 三条硬约束（违反会造成真实损害）

            1. **不许删除用户的插件。** 要排除某个插件，只允许**改名**（例如
               `package.json.bak-<时间>`），绝不允许 `rm`。用户可能还要它、可能
               只是版本不兼容、也可能是别的东西拖累了它。
            2. **任何「修好了」的结论都必须用 `try-boot` 真跑一次验证。**
               `--dump-config`、看配置文件、看日志**都不算证据** —— 真机上杀死
               引擎的异常是在插件 `apply()` 阶段抛的，而 `--dump-config` **不执行
               `apply()`**（实测它退出码 0、条目齐全，引擎照样起不来）。
            3. **日志里那份「失败插件」名单不是元凶名单。** 真机实测：引擎打印
               `Failed plugins (40)` 而实际只列了 38 条，且**真正的元凶不在里面** ——
               日志点名的是**受害者**（比如核心的 `typert-loader` 被某个第三方插件
               重复注册端点拖死）。所以不要拿日志里的名字当结论去禁用它们。

            ### 怎么验证你的修改（rescue 信箱）

            你不能重启 App（那会把自己杀掉），但可以让原生侧帮你**真跑一次**：

            1. 把命令写进 `${'$'}DSH_HOME/rescue-cmd.json`（`id` 必须是**整数**且递增）：

               ```json
               {"id": 1, "action": "try-boot"}
               ```

            2. 等 1~25 秒，读 `${'$'}DSH_HOME/rescue-result.json`：

               ```json
               {"id": 1, "ok": true, "message": "试启动成功：引擎打印了 URL（profile=web）",
                "data": {"ready": true, "exitCode": null, "requiredDidNotActivate": null,
                         "tail": ["...引擎输出尾部..."], "probesUsed": 1, "probeQuota": 20}}
               ```

               `data.ready == true` 是**唯一**可信的成功标志。

            #### 不想动用户的配置就验证？用 `bundleFilter`

            加一个 `bundleFilter` 数组，原生侧会**临时**只加载「核心 + 你列的那些」
            试启动，跑完**把用户配置逐字节还原**（你什么都不用清）：

            ```json
            {"id": 2, "action": "try-boot", "bundleFilter": ["@some/pkg-a", "@some/pkg-b"]}
            ```

            ⚠️ 这就是定位坏插件的正道：**二分**。40 个插件最多 6 轮就能定位到 1 个
            （`bundleFilter` 给一半，看能不能起来；能起来说明坏的在你**没**给的那半）。

            #### 其它动作

            | action | 作用 |
            |---|---|
            | `status` | 立刻返回当前层级、被隔离的插件、上次失败详情（不跑进程） |
            | `switch-rescue` | 切到救援模式（最小配置），界面重启 |
            | `switch-back` | **切回正常模式并重启界面** —— 你确信修好了就用它 |

            ⚠️ `try-boot` 有配额（一次会话 20 次），每次十几秒。用尽会明确告诉你，
            不要再试 —— 改用 `bundleFilter` 做更少的轮次，或者把结论交给用户。

            ### 修好之后

            1. 先 `try-boot`（不带 `bundleFilter`，用用户完整配置）确认 `ready: true`；
            2. 再发 `{"id": N, "action": "switch-back"}` 让 App 切回正常模式重启；
            3. 告诉用户你做了什么、动过哪个文件。

            ⚠️ 如果你判断**根本不是插件问题**（全摘之后仍起不来、或引擎报的错与插件
            无关），就**不要**乱动用户的配置 —— 直接如实告诉用户，并建议他点横幅上的
            「退出并重试」，或考虑 App 里的「安全模式」（那会重新解包引擎，能救
            「引擎目录被改坏」这类问题）。
        """.trimIndent()

