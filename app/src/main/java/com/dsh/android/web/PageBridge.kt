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

    // ── 备份（设计说明 §5.3：通过「安卓基座」设置入口提供）──────────────
    //
    // ⚠️ 这一组全部**同步返回 JSON String**（同 [bubbleState]）：
    //   `@JavascriptInterface` 只支持基本类型与 String，返回对象会静默变成
    //   `undefined` —— 而那会让设置页显示空白，排查时看不出原因。
    //
    // ⚠️ 备份本身是**长操作**（加密几百 MB 可能几十秒）。所以：
    //   - 查询类（status）同步返回
    //   - 执行类（runNow / setPassword / chooseDir）**立即返回**，
    //     真正的进度通过 [onStatus] 或后续 status 查询推送

    /**
     * 备份配置与最近状态。
     *
     * ```json
     * {"hasPassword":true,"hasDir":true,"autoEnabled":true,
     *  "lastSuccessAt":1792686000000,"pending":0,"running":false}
     * ```
     *
     * ⚠️ **绝不返回密码本身**，只返回「设没设过」。
     */
    fun backupStatus(): String

    /**
     * 设置备份密码（Keystore 包裹后存，不存明文）。
     *
     * @param password 明文密码。**JS 侧传完应立即清空输入框**
     * @return `{"ok":true}` 或 `{"ok":false,"error":"..."}`
     *
     * ⚠️ 密码**不写日志**（文档：「不由 AI 保管，不写日志」）。
     *   本方法内部记事件时也只记「成功/失败」，不记内容。
     */
    fun backupSetPassword(password: String): String

    /**
     * 打开系统目录选择器（SAF）让用户选备份目录。
     *
     * ⚠️ 立即返回：真正的选择在原生 Activity 里做，
     *   选完通过 [onStatus] 推一个事件回去。
     */
    fun backupChooseDir()

    /**
     * 开启/关闭自动备份。
     *
     * @return `{"ok":true}`；未设密码或目录时 `{"ok":false,"error":"..."}`
     */
    fun backupSetAutoEnabled(enabled: Boolean): String

    /**
     * 立即备份一次。
     *
     * @return `{"ok":true,"started":true}` —— **不代表备份成功**，
     *   只代表已受理。成功与否看后续 [backupStatus] 的 lastSuccessAt。
     *
     * ⚠️ 这个区分很重要：把「已受理」说成「成功」会让用户以为有备份，
     *   而实际可能失败在加密阶段。
     */
    fun backupRunNow(): String

    /**
     * 打开系统文件选择器，让用户选一个备份包来导入。
     *
     * ⚠️ 立即返回：真正的选择在原生 Activity 里做。
     *   选完会通过 [backupInspect] 的返回值体现（用户需要再点「检查」）。
     */
    fun backupChooseImportFile()

    /**
     * 检查一个备份包（**只读，不改任何东西**）。
     *
     * @param password 用户输入的密码
     * @return JSON：
     * ```json
     * {"ok":true,"createdAt":1792686000000,"appVersion":"0.3.3",
     *  "sourceEnv":"a","complete":true,"entries":42,
     *  "warnings":["这份备份来自 App 0.3.2，当前是 0.3.3"],
     *  "willReplace":["sessions","skills"]}
     * ```
     * 失败时 `{"ok":false,"error":"密码错误或文件损坏"}` ——
     * ⚠️ **刻意不区分两者**：区分会泄露「密码对不对」。
     */
    fun backupInspect(password: String): String

    /**
     * 执行导入（用户已确认）。
     *
     * @param password 密码（与 inspect 时同一个）
     * @return `{"ok":true,"started":true}` —— **不代表完成**，
     *   结果通过 [backupImportResult] 查询。
     *
     * ⚠️ 导入是破坏性操作（会替换 sessions/skills 等）。
     *   本方法内部会建恢复点，失败自动回滚，但调用方仍应先让用户确认。
     */
    fun backupImport(password: String): String

    /**
     * 查询导入进度/结果。
     *
     * ```json
     * {"phase":"idle"} / {"phase":"staging"} / {"phase":"applying"}
     * {"phase":"done","restored":["sessions","skills"]}
     * {"phase":"failed","error":"...","rolledBack":true}
     * ```
     */
    fun backupImportResult(): String

    // ── 布局（设计说明 §4.9：LayoutAdapter 是唯一执行者）────────────────

    /**
     * 查询当前布局。
     *
     * ```json
     * {"layout":"mobile","mode":"auto","reason":"宽高比 0.56 < 3/4"}
     * ```
     *
     * ⚠️ **为什么需要查询而不是只靠事件推送**：
     *   页面加载完成时，原生可能**已经**算过布局并推送过一次 ——
     *   那次推送发生在页面还没有监听器的时候，等于丢了。
     *   所以前端初始化时必须主动拉一次。
     *
     * ⚠️ 返回的 `layout` **只会是 mobile / official**，不会是 auto
     *   （auto 是设置，不是实际布局）。
     */
    /**
     * 查询用户锁定的悬浮字段。
     *
     * ```json
     * {"locked":["avatar","edgeImage"],"owner":"__user__"}
     * ```
     *
     * ⚠️ 文档要求「用户手动设置优先级和**重置语义明确**」——
     *   重置的前提是能查到当前锁了什么。
     */
    fun bubbleLockedFields(): String

    /**
     * 锁定/解锁一个悬浮字段。
     *
     * @param field 字段名（与 `BubbleAppearance` 的 JSON 键一致）
     * @param locked true = 锁定（插件改不动），false = 解锁
     * @return `{"ok":true,"locked":[...]}`
     *
     * ⚠️ 锁定后**立即持久化**：不持久化的话重启就丢，
     *   而插件在重启后也会重新下发外观，等于用户设置被覆盖。
     */
    fun bubbleSetFieldLocked(field: String, locked: Boolean): String

    fun layoutState(): String

    /**
     * 设置布局模式。
     *
     * @param mode `auto` / `mobile` / `official`
     * @return `{"ok":true,"layout":"mobile"}` 或 `{"ok":false,"error":"..."}`
     *
     * ⚠️ 设置后会**立即重算**并推送新布局 —— 不能让用户等下一次旋转才生效。
     */
    fun layoutSetMode(mode: String): String

    // ── 设备能力（设计说明 §4.14.1：能力发现 + 按需授权）──────────────
    //
    // ⚠️ 这一组刻意**只做「发现」与「状态查询」**，不做实际采集 ——
    //   采集是「用户主动动作」（拍照/录音），不该由页面脚本单方面发起。
    //   页面能做的只是「问能不能用」和「把结果告诉用户」。

    /**
     * 发现全部设备能力。
     *
     * ```json
     * [{"cap":"camera","label":"相机","availability":"ready","detail":""},
     *  {"cap":"mic","label":"麦克风","availability":"need-permission",
     *   "missing":["android.permission.RECORD_AUDIO"]}]
     * ```
     *
     * ⚠️ 返回**三态**（ready / need-permission / no-hardware）而不是布尔：
     *   用户对这三种状态的操作完全不同。
     */
    fun deviceCapabilities(): String

    /**
     * 申请某项能力的权限。
     *
     * @param cap 能力 id（`camera` / `mic` / `location` / `bluetooth` / `sensor`）
     * @return `{"ok":true,"requested":["..."]}` —— **不保证授予**，
     *   结果要再查 [deviceCapabilities]
     *
     * ⚠️ 只是**发起**申请（会弹系统框）。真正的结果异步回来，
     *   所以这里不返回「是否授予」—— 那会是假的。
     */
    fun deviceRequestPermission(cap: String): String

    /**
     * 当前进行中的能力请求数（配额状态）。
     *
     * ```json
     * {"active":2,"camera":{"used":1,"limit":2}}
     * ```
     */
    fun deviceRequestStatus(): String

    /**
     * 取消某类能力的全部请求（撤权/用户取消时调）。
     *
     * @return `{"ok":true,"cancelled":3}`
     */
    fun deviceCancelAll(cap: String): String

    // ── 受管媒体资源（设计说明 §4.13：资源句柄）──────────────────────

    /**
     * 列出受管资源区的素材。
     *
     * ```json
     * {"total":1234567,"items":[{"handle":"res:abc…","bytes":12345}]}
     * ```
     *
     * ⚠️ **只返回句柄，不返回路径**：插件拿不到真实文件路径。
     */
    fun mediaList(): String

    /**
     * 导入一份素材（base64）。
     *
     * @param base64 素材内容
     * @return `{"ok":true,"handle":"res:abc…","kind":"png","bytes":1234,"deduped":false}`
     *   或 `{"ok":false,"error":"...","kind":"unsupported"}` 等
     *
     * ⚠️ 类型由**文件头魔数**判定，不信扩展名。
     */
    fun mediaImport(base64: String): String

    /** 删除一份素材（按句柄）。 */
    fun mediaRemove(handle: String): String

    // ── 磁盘清理（设计说明 §6.5.4：低水位）──────────────────────────

    /**
     * 磁盘状态与建议。
     *
     * ```json
     * {"freeMb":150,"lowWater":true,"limitMb":200}
     * ```
     */
    fun diskStatus(): String

    /**
     * 按次序清理（用户主动点「清理」时调）。
     *
     * @return `{"ok":true,"freedMb":80,"deleted":12,"skipped":2}`
     *
     * ⚠️ 只清**可再生**内容（缓存/旧日志/备份临时产物），
     *   **不删用户数据** —— 那需要用户自己决定。
     */
    fun diskCleanup(): String

    // ── 实际采集动作（设计说明 §4.14.1a：正向闭环）────────────────────
    //
    // ⚠️ 这些会**真的占用硬件**（相机/麦克风），所以必须显式调用，
    //   且每项都有超时与资源释放。

    /**
     * 拍一张照片。
     *
     * @return `{"ok":true,"handle":"res:…","width":1280,"height":720,"orientation":90}`
     *
     * ⚠️ 异步：真正的结果通过 [onStatus] 或后续 `mediaList()` 体现。
     *   本方法返回 `{"started":true}` 表示**已受理**，不代表拍到了。
     */
    fun cameraCapture(): String

    /**
     * 录一段视频。
     *
     * @param durationMs 录制时长（上限 60000）
     * @return `{"started":true,"handle":null}` —— 完成后再查 `mediaList()`
     */
    fun cameraRecord(durationMs: Int): String

    /**
     * 开始录音（语音输入用）。
     *
     * @return `{"started":true}` —— 数据通过 `audioPoll()` 拉取
     */
    fun audioStart(): String

    /** 停止录音并返回累积的采样数。 */
    fun audioStop(): String

    /**
     * 播放一段 PCM（base64）。
     *
     * @return `{"ok":true,"bytes":N}`
     */
    fun audioPlay(base64: String): String

    /**
     * 单次定位。
     *
     * @return `{"started":true}` —— 结果通过后续查询或事件获取
     */
    fun locationOnce(): String

    /** 开始持续定位（退后台自动暂停）。 */
    fun locationStart(): String

    /** 停止持续定位。 */
    fun locationStop(): String

    /**
     * 开始语义事件检测（摇动之外的翻转/抬腕/静止）。
     *
     * ⚠️ 摇动已有独立实现（`ShakeDetector`），这里补的是**姿态类**事件。
     *   文档把两者都归为「语义事件」但算法不同：
     *   摇动看**运动**（积分速度），翻转/抬腕看**姿态**（重力方向）。
     */
    fun motionStart(): String

    /** 停止语义事件检测。 */
    fun motionStop(): String

    // ── 修复流程（设计说明 §6.4 / G8：受控修复）──────────────────────
    //
    // ⚠️ 刻意分成两步：plan（只读，生成计划）与 execute（有副作用）。
    //   中间**必须有用户批准** —— 文档：「修复模型不能替代监督器决定是否允许写 A」。

    /**
     * 诊断并生成修复计划（**不执行任何修改**）。
     *
     * ```json
     * {"problems":2,"steps":1,"manual":true,
     *  "reason":"重解包会抹掉运行时补丁",
     *  "irreversible":["repack-engine：/files/engine/"],
     *  "scope":"engine/"}
     * ```
     *
     * ⚠️ `planId` 是后续批准与执行的凭据，必须保留。
     */
    fun repairPlan(): String

    /**
     * 批准一份修复计划（模拟用户点「同意」）。
     *
     * @param planId 计划 ID
     * @param acknowledgedIrreversible 用户是否确认了不可逆副作用
     *
     * @return `{"ok":true,"approval":"…","expiresAt":…}`
     *
     * ⚠️ 批准**绑定当前环境/epoch/通道/有效期**，任何一项变化都会让批准失效。
     */
    fun repairApprove(planId: String, acknowledgedIrreversible: Boolean): String

    /**
     * 执行已批准的修复并核验。
     *
     * @return `{"executed":2,"failed":0,"verdict":"fixed","summary":"…"}`
     */
    fun repairExecute(planId: String): String

    /**
     * 回滚一次修复（用快照）。
     *
     * @return `{"restored":3,"failed":0,"irreversible":["…"]}`
     *
     * ⚠️ 有不可逆动作时**如实报告**，不说「已恢复到原状」。
     */
    fun repairRollback(planId: String): String
}
