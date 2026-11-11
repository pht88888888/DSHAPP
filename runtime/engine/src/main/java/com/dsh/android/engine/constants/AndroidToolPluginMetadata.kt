package com.dsh.android.engine.constants

/** Android 内置能力组件的历史包名与迁移路径。 */
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

