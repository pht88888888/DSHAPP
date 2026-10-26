package com.dsh.android.web

/**
 * 页面 → 原生 的状态通道，页面侧看到的是 `window.DSHAndroid`。
 *
 * ## 为什么要有这个接口
 *
 * 两个 Web 内核版本（`system` = 系统 WebView、`gecko` = 内置 GeckoView）都要把
 * **同一组能力**暴露给页面，但暴露的机制完全不同：
 *
 * | | system | gecko |
 * |---|---|---|
 * | 注入方式 | `addJavascriptInterface(obj, "DSHAndroid")` | WebExtension content script 自己造 `window.DSHAndroid` |
 * | 调用语义 | **同步**（JS 直接拿到返回值） | 只能异步（native messaging） |
 *
 * 所以把「能力」抽成这个接口，两个 flavor 各自实现注入，[MainActivity] 只实现
 * 一次方法体。
 *
 * ⚠️ **方法签名必须与页面侧 `mobile-ui-plugin/dsh-android-mobile/lib/client.js`
 *   里 `window.DSHAndroid` 的用法逐字一致**（返回 String 的、返回 Boolean 的
 *   各有其用），改签名等于改协议。页面侧的具体调用点见各方法注释。
 *
 * ⚠️ 返回 String 的方法（[bubbleState] / [rootState]）返回的都是 **JSON 文本**，
 *   不是对象：`@JavascriptInterface` 只支持基本类型与 String，返回自定义对象
 *   会被序列化成 `undefined`（静默，不报错）。gecko 侧走 JSON 序列化同样返回
 *   文本，两边一致。
 */
interface PageBridge {

    /**
     * 页面状态上报（AI 在干什么 + 当前底色），驱动通知栏与系统栏配色。
     *
     * ⚠️ 运行在内核的 JS 线程上，**不能碰 View** —— 实现里必须自己切主线程。
     */
    fun onStatus(json: String)

    /**
     * composer 的输入框即将获得焦点 —— 比键盘 insets 早约 200ms 的信号。
     *
     * 页面侧在 `focusin` 里发（只在 `[data-composer-input]` 上），原生据此
     * 提前把 Web 视图缩矮，抵消「窗口变矮滞后约 200ms」造成的遮挡。
     */
    fun onImeWillShow()

    /** 设置页点「配对无线调试」。立即返回，真正的发现在后台协程里做。 */
    fun startWirelessPairing()

    /** 设置页取消配对（撤掉那条带输入框的通知）。 */
    fun cancelWirelessPairing()

    /**
     * 配对需要的通知是否可用。
     *
     * ⚠️ 这是**唯一一个同步返回值的非 String 方法**，而 gecko 侧的 native
     * messaging 只能异步 —— 所以 gecko 实现走「原生主动推送 + content script
     * 缓存」来维持同步语义，见 `GeckoPageBridge` 的注释。
     */
    fun pairingNotificationsEnabled(): Boolean

    /** 移动端 UI 插件激活完成 —— 界面真正可用的时刻，启动动画据此收尾。 */
    fun onUiReady()

    /** 页面侧脚本错误上报，用于把真实原因摆到启动层上。只记第一条。 */
    fun reportWebError(message: String)

    /** 设置页「切到固定端口」：把 adbd 切到 `adb tcpip 5555`（阻塞，需后台线程）。 */
    fun switchToTcpMode()

    /** 读悬浮气泡状态。返回 JSON：`{"enabled":bool,"granted":bool, …物理开关…}`。 */
    fun bubbleState(): String

    /** 开关悬浮气泡。关掉时立刻收起已显示的窗口。 */
    fun bubbleSetEnabled(enabled: Boolean)

    /**
     * 设置「键盘顶飞」开关。
     *
     * ⚠️ 没有特权通道时会被**拒绝**（判据见 `ShellPrivilege`）：这条路径读的是
     *   `dumpsys input_method`，普通应用 uid 拿不到 `DUMP` 权限。与其让用户打开一个
     *   静默无效的开关，不如拒绝并保持原值 —— 设置页用 `imeFlingAvailable` 先置灰。
     */
    fun bubbleSetImeFling(enabled: Boolean)

    /** 设置「晃动甩飞」开关。加速度计是普通传感器，无需任何权限，直接生效。 */
    fun bubbleSetShake(enabled: Boolean)

    /**
     * 上报余额快照（页面侧拉取 → 原生显示）。
     *
     * ## 为什么余额要从页面侧拉
     *
     * 引擎插件层**拿不到** `ctx.remote`（实测：`inject` 了会 `pending` 等一个
     * 永远不来的服务，不 `inject` 读取直接抛 `cannot get property "remote"`，
     * 且同层的 `tools`/`agents`/`sessions` 全都是不可达）。而页面侧插件
     * 用 `ctx.inject(["remote"], scope => …)` 是**可以的**（官方
     * `dsh-client-ui-settings-account` 就靠这条路显示余额）。
     *
     * 所以分工是：**页面取数 → 本桥回传 → 原生渲染**。原生不碰网络，
     * 页面不碰 View，各做各擅长的事。
     *
     * @param json 载荷，结构见 `dsh-android-balance` 的 `flush()`：
     *   `{ wallets:[{currency,balance}], bonusWallets:[…], costLast, costSession, error }`。
     *   传 `{}` 或空串表示"清空"（徽标随即隐藏）。
     * @return 是否被接受（球未显示时为 false，属正常）。
     */
    fun bubbleReportBalance(json: String)

    /**
     * 读徽标的刷新配置（页面侧据此决定定时/轮末/点击是否刷新）。
     *
     * @return JSON 字符串 `{refreshEverySec, refreshOnTurnEnd, refreshOnTap, tapRefreshMinIntervalSec}`。
     *   永不返回 null —— 页面侧把它当"永远成功"来用，省掉一次判空。
     */
    fun bubbleBadgeConfig(): String

    /**
     * 点球刷新计数。页面侧每 300ms 读一次，发现涨了就拉一次余额。
     *
     * ## 为什么用"计数 + 轮询"而不是原生反向调 JS
     *
     * 原生没有可靠的方式在自己的进程里主动执行页面里的 JS 函数
     * （`evaluateJavascript` 必须回到 WebView 线程、且页面可能正在导航）。
     * 读一个数字天然幂等、可重入、页面刷新后自动归零 —— 比反向调用稳得多，
     * 代价只是每 300ms 读一次 int（可忽略）。
     *
     * @return 单调递增的计数（进程重启归零）。
     */
    fun bubbleTapRefreshSeq(): Int

    /**
     * 把文本写进系统剪贴板，供页面侧的「复制登录链接」使用。
     *
     * ## 为什么必须由原生写
     *
     * 官方「登录」对话框的「复制登录链接」调的是
     * `navigator.clipboard.writeText(...)`。**真机实测必然失败**：
     * `NotAllowedError: Write permission denied.`
     *
     * 原因是 WebView 的剪贴板写权限由**原生侧的 `WebChromeClient.onPermissionRequest`**
     * 授予，本 App 没有实现它，因此内核一律拒绝 —— 页面侧看到的是
     * `navigator.clipboard` **存在、`isSecureContext` 也是 true**（因为
     * `http://127.0.0.1` 属于潜在可信来源），**但一调用就抛**。
     * 这种"API 在但用不了"的形态最容易误判成前端 bug。
     *
     * ⚠️ 本方法只做写入，**不读剪贴板**。读会牵出"应用能偷看剪贴板"的隐私面，
     *   而需求只有复制。
     *
     * @param text 要写入的文本。
     * @return 是否写入成功（失败时页面侧可回退提示）。
     */
    fun writeClipboard(text: String): Boolean

    /**
     * 页面诊断：把一句话写进 `app.log`。
     *
     * 页面的 `console.log` 只到 WebView 控制台，真机上读不到；排查"余额拿不到"
     * 这类问题必须先知道 `remote.account` 到底有没有、接口返回了什么。
     * 走这个通道就能在 `logs/app.log` 里按 `DSH` 标签直接读到。
     *
     * ⚠️ 诊断专用，正常路径不要调。
     */
    fun pageDiag(text: String)

    /** 设置「拖拽抛掷」开关（手甩球松手后飞不飞）。纯本地物理，无权限要求。 */
    fun bubbleSetThrow(enabled: Boolean)

    /** 拉起系统的「显示在其他应用上层」授权页。 */
    fun bubbleRequestOverlay()

    /**
     * 打开 **AI 浏览器**（官方右侧边栏里的「AI 浏览器」面板调用）。
     *
     * 没有返回值是**刻意**的：gecko 版的桥走 `postMessage` 异步派发，回传值要多
     * 绕一层；而页面侧不需要知道结果（浏览器要么弹出来、要么不弹，用户看得见）。
     * 保持 void 让两个 flavor 的白名单能逐条对齐。
     *
     * ⚠️ 往这里加方法**必须同步加进两份白名单**（`SystemWebHost` 的
     *   `@JavascriptInterface` 与 `GeckoWebHost.dispatchInvoke`），否则页面侧
     *   `window.DSHAndroid.openAiBrowser` 是 undefined，表现为「点了没反应」，
     *   而原生日志一切正常。0.3.3 的 keyboardAvoid 就是这么漏的。
     */
    fun openAiBrowser()

    /**
     * 把 AI 浏览器摆到官方右侧边栏面板的内容区上。
     *
     * `json` 是前端量出来的矩形：
     * `{"x":0,"y":120,"w":411,"h":700,"vw":411,"vh":880}`
     * —— `x/y/w/h` 是 `getBoundingClientRect()` 的 **CSS 视口坐标/尺寸**，
     * `vw/vh` 是 `window.innerWidth/innerHeight`（用来算 CSS→物理的缩放比）。
     *
     * ⚠️ 换算必须在原生侧做：页面里拿不到自己在屏幕上的位置（`window.screenX`
     *   在 WebView 里恒为 0），只有原生能 `getLocationOnScreen()`。
     *
     * ⚠️ 这个方法会被高频调用（ResizeObserver），原生侧必须做「矩形没变就
     *   直接返回」的短路，否则每次都会触发一轮 layout。
     */
    fun aiBrowserLayout(json: String)

    /**
     * 面板卸载 / tab 关掉时调用：把 AI 浏览器收起来。
     *
     * 只隐藏，**不销毁** —— 页面状态与登录态留在内存里，下次开 tab 还是原样。
     */
    fun aiBrowserDetach()

    /**
     * 查询 AI 浏览器当前可见性，返回 `"visible"` 或 `"hidden"`。
     *
     * 面板挂载时要先对一次状态：AI 可能已经用工具把网页打开了，用户这时才点
     * 开侧栏 tab —— 面板的本地 state 是「没开」，不查询就会显示成一个打开按钮，
     * 而浏览器其实正盖在屏幕上。
     */
    fun aiBrowserState(): String

    /**
     * 前端取走「标注发送」的结果（截图 + 提问），取一次就清空。
     *
     * 返回 JSON 串，字段：
     * - `png`   — 合成图的 **base64**（不带 `data:` 前缀）
     * - `text`  — 用户输入的提问，可能为空串
     *
     * ## 为什么用 base64 而不是给文件路径
     *
     * 主 WebView 的 `allowFileAccess = false`（`SystemWebHost` 里刻意关掉的，
     * 为的是不让页面随便读沙盒文件）。所以前端 `fetch("file://…")` 一定失败。
     *
     * 要放开这个开关就得把整个页面变成能读 filesDir 的 —— 为了一个功能扩大
     * 攻击面不值当。改成原生读字节、base64 编码后一次交给前端，前端直接
     * `atob` 还原成 Blob。代价是字符串大一点（约 1.33 倍），可接受。
     *
     * @return JSON；没有待发送内容时返回空串。
     */
    fun aiAnnotateResult(): String

    /**
     * 前端回报「标注发送」的结果，原生用 Toast 显示给用户。
     *
     * ## 为什么要有它
     *
     * 手机上看不到 `console.log`。发送链路一旦静默失败，用户只能看到
     * 「点了没反应」，开发者也只能靠猜。把失败原因**显示在屏幕上**，
     * 用户截一张图就能定位，不必反复下载安装包。
     *
     * @param message - 给用户看的一句话（成功或失败原因）。
     */
    fun aiAnnotateReport(message: String)

    /**
     * 读「键盘避让」状态。返回 JSON：`{"enabled":bool,"a11y":bool}`。
     *
     * 两个字段是**两件独立的事实**，不要合并：
     * - `enabled` —— 用户要不要这个功能（本 App 的 SharedPreferences）；
     * - `a11y`    —— 系统有没有给无障碍授权（`Settings.Secure`，只能去系统设置里开）。
     *
     * 开关开着但没授权时，正确文案是「还差一步：去开权限」，而不是笼统的"不可用"。
     */
    fun keyboardAvoidState(): String

    /**
     * 开关「键盘避让」。关掉后悬浮球不再避让键盘（仍保留手动拖动）。
     *
     * @return true = 已写入；false = 被拒绝（无障碍与特权通道都不可用）。
     *   ⚠️ 必须回传结果：页面据此把开关弹回去。否则用户点了开关、原生静默拒绝、
     *   页面却已经把开关画成"开"，两边状态就不一致了。
     */
    fun keyboardAvoidSetEnabled(enabled: Boolean): Boolean

    /** 拉起系统的无障碍设置页 —— 用户在那里打开「DSH 键盘哨兵」。 */
    fun keyboardAvoidRequestPermission()

    /**
     * 会话转录上报 —— 悬浮窗展开态要显示的**完整**对话内容。
     *
     * 与 [onStatus] 分开的理由见 [com.dsh.android.ChatTranscript] 的头注释：
     * 状态是高频小体量（通知栏也在用），转录是低频大体量（只有展开面板要）。
     *
     * ⚠️ 运行在内核的 JS 线程上，**不能碰 View** —— 实现里必须自己切主线程。
     */
    fun onTranscript(json: String)

    /**
     * 插件下发悬浮球外观配置。返回是否已应用。
     *
     * 这是「让 AI 改悬浮窗，像改自己的 web 界面一样简单」的接缝：任何页面内的
     * 插件都可以直接
     *
     * ```js
     * window.DSHAndroid.bubbleApply(JSON.stringify({ shape: 'square', size: 72 }))
     * ```
     *
     * 字段与语义见 [com.dsh.android.BubbleAppearance]。**未知字段一律忽略**，
     * 非法值退回默认 —— 插件写错不该让悬浮球消失。
     *
     * ⚠️ system 版同步返回 Boolean；gecko 版只能异步，走「乐观返回 true」，
     *   见 `GeckoWebHost` 的注释。
     */
    fun bubbleApply(json: String): Boolean

    /** 读回当前外观配置（JSON 文本）。插件据此做「读取-修改-写回」。 */
    fun bubbleAppearance(): String

    /**
     * 读 root 探测结论。返回 JSON（`known/state/granted/detail/at/mounted`）。
     *
     * ⚠️ **只读缓存、不触发探测** —— 探测最多要等 15 秒，在 JS 线程上做会卡死页面。
     */
    fun rootState(): String

    /** 设置页点「重新检测」：清掉缓存并**在后台**强制重探一次。界面靠轮询 [rootState] 拿结论。 */
    fun rootRecheck()

    /**
     * 读 Shizuku 通道状态。返回 JSON 文本。
     *
     * ```json
     * {"installed":true,"binder":true,"granted":true,"uid":2000,"version":13}
     * ```
     *
     * ## 四个字段为什么必须分开报
     *
     * 它们是四种**完全不同的处境**，而用户看到的下一步动作也完全不同：
     *
     * | installed | binder | granted | 处境 | 用户该做什么 |
     * |---|---|---|---|---|
     * | false | — | — | 没装 Shizuku | 去装 |
     * | true | false | — | 装了但服务没启动 | 打开 Shizuku，点「启动服务」 |
     * | true | true | false | 在跑但没给我们授权 | 点「授权」 |
     * | true | true | true | 可用 | 什么都不用做 |
     *
     * ⚠️ 把它们合成一个布尔（"可用/不可用"）正是本工程反复踩的缺陷族：
     * 「结论与它描述的对象不是同一个」—— 用户会照着错的提示去卸载重装 Shizuku，
     * 而其实只需要点一下授权。
     *
     * ⚠️ **同步返回 String**（同 [bubbleState]）：`@JavascriptInterface` 只支持
     * 基本类型与 String，返回对象会静默变成 `undefined`。
     *
     * ⚠️ **只读当前状态、不触发任何探测**：Shizuku 的 binder 由系统在进程启动时
     * 送进来（见 `ShizukuProvider`），随时可读，没有"探测"这回事。
     */
    fun shizukuState(): String

    /**
     * 设置页点「授权」：让 Shizuku 弹出它自己的授权确认界面。
     *
     * ⚠️ 结果是**异步**回来的（Shizuku 服务端拉起它自己的 Activity，用户点完才回），
     * 所以立即返回，界面靠轮询 [shizukuState] 拿结论 —— 与 [rootRecheck] 同构。
     *
     * ⚠️ 必须在**主线程**调：Shizuku 要用当前 Activity 作为确认界面的宿主。
     */
    fun shizukuRequestPermission()

    /**
     * 设置页点「打开 Shizuku」：拉起 Shizuku 管理器应用。
     *
     * ⚠️ 单独开一个方法而不是让用户自己去桌面找：设备上装没装、装的是哪个包
     * （`moe.shizuku.privileged.api`）用户并不知道，而这是**唯一**能让服务
     * 从「没启动」变成「已启动」的地方。
     */
    fun shizukuOpenManager()

    /**
     * 设置页点「安装 Shizuku」：打开 Shizuku 的官网/下载页。
     *
     * ⚠️ 走浏览器而不是应用商店：Shizuku 在部分国内 ROM 的商店里搜不到，
     * 而它的官方发布页（GitHub Releases）是权威来源。
     */
    fun shizukuOpenDownload()

    /**
     * 设置页点「运行日志与诊断」：拉起原生日志界面（**入口①**）。
     *
     * ## 为什么日志界面在原生侧、而不是做成一个页面插件
     *
     * 因为它要能在「页面根本打不开」的时候用。做成页面插件的话，插件没激活、
     * 前端 bundle 挂了、引擎没起来 —— 这三种情况恰好都是最需要看日志的时候，
     * 而那时页面插件自己也活不了。所以这个界面必须独立于 Web 层。
     *
     * 设置页这条入口覆盖的是另一半场景：**界面能开、但某个功能不正常**
     * （回复报错、插件没生效、点了没反应）。那时用户能进设置页，从这里进去
     * 最顺手。加上启动层那条，两条路合起来覆盖「界面能开」与「界面开不了」。
     *
     * ⚠️ 立即返回，不要阻塞 JS 线程：真正的 `startActivity` 在主线程做。
     */
    fun openLogs()

    /**
     * 读日志摘要，给设置页那一行做副标题。
     *
     * 返回 JSON 文本：
     * ```json
     * {"total":1234,"warn":12,"error":3,"crash":2,"pending":1,"lastExit":"上次正常退出"}
     * ```
     *
     * ⚠️ **必须便宜**：设置页每次打开都会调它，所以只读内存缓冲的计数与
     * `BootState`（一个几十字节的 JSON），**不做**完整诊断（那要读几个 MB 的
     * 文件算行数）。完整诊断只在用户点「导出诊断包」时跑。
     *
     * ⚠️ 同步返回 String（同 [bubbleState]）：`@JavascriptInterface` 只支持
     * 基本类型与 String，返回对象会静默变成 `undefined`。
     */
    fun logSummary(): String
}
