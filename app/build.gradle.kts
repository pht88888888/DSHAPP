import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发行版签名凭据从仓库根的 keystore.properties 读（不入库，见 .gitignore）。
// 文件不存在时不配 signingConfig —— 这样别人 clone 后仍能出 unsigned 包，
// 且 assembleDebug 完全不受影响。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    // ⚠️ 必须是恰好 15 字符，且与 TermuxBootstrap.ELF_ROOT_REPLACEMENT 完全一致。
    // 原因：Termux 官方二进制把 "/data/data/com.termux/files"（27 字节）烤进 ELF 的
    // .dynstr，而 patchElfInPlace() 只能做等长原地覆盖，替换串形如
    // "/data/data/<包名>/" = 12 + N 字节，等于 27 ⇒ N = 15。
    // 改错不会编译报错，只会在真机上以「dpkg Permission denied」的形式炸掉。
    // 改完请跑 termux-kit 的 tools/verify-kit.ps1 校验。
    namespace = "com.dsh.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.android"
        minSdk = 26
        // 🔴 必须是 28，不能升到 29+。Android 10（API 29）起，targetSdk ≥ 29 的
        // 应用不允许 execve() 自身私有目录里的文件（W^X 限制，SELinux
        // untrusted_app 域的 app_data_file 禁止 execute_no_trans）。而 Termux 的
        // 全部包（node / bash / dpkg / python）都装在 filesDir 下的 $PREFIX 里，
        // 一升到 29 就会全线 Permission denied。Termux 官方 App 同样停在 28。
        targetSdk = 28
        versionCode = 46
        // 后缀语义：`credperm` = 凭据文件权限修复（0.2.9 首发）；
        // `tooliso` = 工具插件注册失败的隔离（可选工具注册失败不再拖垮引擎启动）；
        // `rescue` = 坏插件不再让 App 打不开（四级降级阶梯 + AI 自助修复通道）。
        // ⚠️ versionCode 20 与 19 都是 versionName `0.2.9-rescue`（同一个包名的两版），
        //    靠 versionCode 区分。19 的缺陷是「开全面屏后顶栏整行下移」——
        //    原生把状态栏高度按物理像素直推给页面（108 而非 36），让位量放大 3 倍。
        //
        // ⚠️ 21（本次）修的是一条**此前完全没有判据**的故障：引擎**起来了**
        //    （打印了 URL、界面正常加载），但 typert gateway 因其某个 contributor
        //    注册失败而整体下线 —— `session/control` / `session/modelCatalog` /
        //    `directoryPicker/list` 全部报 `its strict definition was withdrawn
        //    and SRC fallback is forbidden`。用户看到的是「选不了工作区、发不出
        //    消息、插件面板打不开」，而界面上**没有任何按钮**：救援阶梯的四道门
        //    全都要求「启动失败」，这一次启动是成功的。
        //    现在由 `RescueBoot.parseGatewayFailure` 认引擎自己那句话，命中即
        //    自动降级到救援模式并重启（零操作）。versionName 不变，靠 versionCode
        //    区分（20 已作为 `0.2.9-immersive` 发出，**不能**倒退或重发同号）。
        //
        // ⚠️ 22（本次）是 21 之后的一整夜修复。21 已于 10-14 23:45 作为
        //    `DSH0.2.9-bubble.apk` 发出（提交 `555bc39`，23:36 设的 vc21），
        //    而其后 6 次提交（`e671f58` 跨进程补传互斥、`fa8b6a5` ELF 用运行时
        //    真实包名、`097211f` 救援日志落盘 + rescue/ 进诊断包、`05ec03e`
        //    L1 隔离过期快照、`3cea637` boot-tier 文案）**全部落在 vc21 上**。
        //    不 bump 就会有两个内容不同的 APK 都自称 `0.2.9-rescue (21)` ——
        //    正是本项目缺陷族「结论与它描述的对象不是同一个」在发布版上的翻版：
        //    诊断包里的 `0.2.9-rescue (21)` 再也无法指向唯一一份代码。
        //    versionName 仍不变，靠 versionCode 区分（既有惯例，见上）。
        //
        // ⚠️ 23（本次）补的是**第五种故障形态**，也是此前唯一「用户完全没救」
        //    的一种：引擎**完全健康**（`dsh-gateway-ui: 已激活`、供应商注册成功），
        //    但某个插件的 **client 半**有语法错误 —— 而前端把所有插件的 client 半
        //    **合并成一批**下发（`/plugins/??a/client.js,b/client.js,…`），
        //    一个段坏就让**整批**解析失败 ⇒ 界面变成 `Failed to load plugins`
        //    整页错误。真机现场（OPPO PHY110）：用户写插件写到一半退出 App。
        //
        //    此前四道门（L1 隔离 / L2 救援 / L3 安全模式 / 21 的 gateway 判据）
        //    **一道都进不去**：它们的判据全部读引擎 stdout，而引擎这次没说错话；
        //    唯一能自救的信箱 `rescue-cmd.json` 又只有 AI 能写，而 AI 要进对话
        //    才能写 —— 死锁。当时只能靠电脑上的 adb 改 `profiles/web/package.json`
        //    再重启，普通用户只能卸载重装（清掉全部会话与凭据）。
        //
        //    现在把那个动作做成了 App 内的按钮：日志界面（**唯一在界面打不开时
        //    仍然可达的原生界面**，入口是启动层上任何状态都显示的「查看日志」）
        //    新增「插件」动作 —— 列出第三方 bundle，可摘掉/装回，然后重启。
        //    官方 bundle（「@deepseek-ai/」开头）不列出、也拒绝摘除。
        //    versionName 仍不变，靠 versionCode 区分（既有惯例，见上）。
        //
        // ⚠️ 24（本次）**换 versionName：0.2.9-rescue → 0.3.0**。这是自 0.2.6
        //    引入 flavor 之后第一次改前两位版本号，所以特别记一下判据：
        //    之前十几版都靠 versionCode 区分同名的 `0.2.9-xxx`，是因为那些版本
        //    彼此只差缺陷修复、对外还是「同一版」。而 0.3.0 有**新产品能力**：
        //      ① 特殊权限新增 **Shizuku 通道** —— 不需要 adbd 在 5555、不需要
        //         adbkey 被设备信任、重启后依然有效。对「Android 10 且只有有线
        //         adb、不支持无线调试」那批用户，此前是**结构性死锁**
        //         （`persist.adb.tcp.port` 写不进去 ⇒ 每次重启都要插电脑重跑
        //         `adb tcpip 5555`），现在有解了；
        //      ② 特殊权限界面重做成 ADB / Shizuku / root 三个标签页
        //         （原来三条路径 + root 卡全部平铺，一屏滑不到底、也不知道该点哪张）。
        //    对外可见的能力变化 ⇒ 该换 minor 号，不该再往 `0.2.9-*` 上叠。
        //    ⚠️ versionCode 必须**继续递增**（23 → 24），不能因为是新名字就重置：
        //    同一 applicationId 的包靠 versionCode 比大小判升级，重置成 1 会让
        //    老用户的 `0.2.9-rescue (23)` 变成「比新版还新」，装不上更新。
        // ⚠️ 25（本次）**versionName 不变（0.3.0），靠 versionCode 区分**。
        //    内容是缺陷修复，没有对外可见的新能力，所以按惯例不换名：
        //      ① **白屏真根因（431）**：引擎每次启动都用「?token= → 303 + Set-Cookie」
        //         换一个**新名字**的鉴权 cookie（`dsh-auth-<随机后缀>`，30 天有效），
        //         永不覆盖也永不回收。累积到一定数量后请求头超过服务器上限，引擎
        //         对首页回 **431 且响应体为空** ⇒ 永久白屏，且没有任何 JS 报错可查
        //         （`#root` 与 `document.scripts` 全空、console 一条不输出）。
        //         两个 flavor 都中招（gecko 存在 cookies.sqlite，system 存在 WebView
        //         的 cookie jar），所以两边都在建宿主时清一次。
        //      ② **GeckoView 不跟随那个 303**：`onLoadRequest` 只调一次、0.35s 后
        //         `onPageStop success=true`，页面停在 303 的空响应上（同一 URL 在
        //         系统浏览器里正常渲染）。修法是在页面世界 `fetch` 完成 token→cookie
        //         交换后 `location.replace('./')`。
        //      ③ **救援判据漏报**：坏 client bundle 让**整批** `/plugins/??` 失败时，
        //         console 只有 `[web] error: {}`（Error 序列化成空对象）与
        //         `首帧正文="HARNESS Loading plugins…"`，原两个判据都不匹配 ⇒
        //         永不降级 ⇒ 用户点「进入安全模式」也没用。补第三类判据。
        //      ④ 「进入安全模式」现在会真的把第三方包**挪出** node_modules
        //         （此前只改 manifest，手装的包仍留在原地，坏 client bundle 照旧被
        //         合并进前端）。
        // ⚠️ 26（本次）**versionName 仍然不变（0.3.0），靠 versionCode 区分**。
        //      ① **「快准狠模式」改成可开关的插件**：从 `--patch` overlay 挪到 profile
        //         bundle（与 dsh-core-memory 同一套），出现在「插件 → 已安装插件」里，
        //         默认开启、用户可自行关掉，关掉后**重启不复活**。
        //      ② **修 `{{cwd}}` 崩溃**：0.2.1 不再注册这个模板变量，而创造模式注入的
        //         persona 正文里还留着「你的工作目录是 {{cwd}}。」⇒ 该预设一选中就
        //         整轮失败（`unknown prompt variable "{{cwd}}" ... registered
        //         variables: provider, model`）。上一轮只删了锚点没删正文。
        //      ③ 内置预设源码换成 npm 上已发布的 dsh-preset-razor@1.0.3。
        // ⚠️ 27（本次）**换 versionName：0.3.0 → 0.3.1**。
        //      ① **修余额功能整条链路**：`getBalance` 的参数结构原来自己编的
        //         （`clientVersion/locale/utcOffsetSec`），网关直接拒
        //         `wire field "client" failed boundary validation`，余额永远拉不到。
        //         改成官方的 `{version, locale, timezoneOffsetSeconds}`。
        //      ② **球不在时缓存余额快照**：原来球隐藏期间拿到的余额被直接丢弃，
        //         而余额恰好是在 App 前台（球隐藏）时刷新的 ⇒ 回桌面永远没徽标。
        //      ③ **点球刷新的限流跟随配置**：页面里写死 20 秒，设置里是 10 秒，
        //         导致连点毫无反应。
        //      ④ **球的默认底色改成全透明**：原来是纯白，头像外面那圈永远是
        //         一块白底，与设置页显示的「透明」不一致。
        //      ⑤ 余额徽标底色默认也改成透明（原来是主题白底黑字）。
        //      ⑥ 设置页「显示内容」改成可视化编辑器：成品预览 + 可删胶囊 +
        //         已用词置灰 + 恢复默认，不再把 `{balance}` 这种占位符摊给用户。
        //      ⑦ 晃动手感重做成**惯性撞边**：手机快速移动时，球被对应那侧的
        //         边框撞飞（原来只是"晃过阈值给一次速度"）。
        //      ⑧ 删掉顶栏与小分节里重复的「拖拽抛掷 / 晃动甩飞 / 键盘顶飞」开关 ——
        //         上面那份只存盘不推活球，表现是"开关状态不马上刷新"。
        //      ⑨ 面板底色「自动」= 透明（同时抑制描边、给文字加阴影）。
        // 0.3.2（versionCode 28）：移动端 UI 对比度全线达标。
        //      用 WCAG 对比度公式把整个界面扫了一遍（不再靠肉眼判断 —— 之前
        //      被截图误导过 6 次），揪出 4 处不达标的（AA 正文要求 4.5:1）：
        //        · 底栏推理等级「默认」亮 2.18 / 暗 2.42
        //        · 输入框占位符        亮 2.13 / 暗 3.76
        //        · 抽屉分组标题+展开按钮 3.55
        //        · 设置页 8 个说明副标题 3.71
        //      核心教训：同一颜色不可能亮暗两边都达标（亮底要深色、暗底要浅色），
        //      必须写两套；且占位符这类"故意淡"的文字不能拉满对比，会失去语义。
        // 0.3.3（versionCode 29）：悬浮球键盘避让（无障碍）。
        //      ① 合并 PR #3：新增 `BubbleA11yService`（无障碍哨兵），用
        //         `getWindows()` 读 `TYPE_INPUT_METHOD` 窗口的真实 bounds ——
        //         这是普通应用唯一能拿到「全局键盘可见性 + 键盘顶在哪」的路。
        //         `WindowInsets.Type.ime()` 只属于自己获得焦点的那扇窗，悬浮窗拿不到。
        //      ② 键盘弹起时按 v=sqrt(2*g*rise) 给球向上初速弹射，落回键盘上方；
        //         键盘收起时贴键盘顶的球交给重力落下。
        //      ③ **两套检测严格互斥**（`KeyboardAvoid.Source`）：A11Y（事件驱动
        //         + ≤120ms）优先，SHELL（`dumpsys input_method` 轮询，2s 起）兜底，
        //         都没有则 NONE —— 此时设置页开关置灰、原生拒绝打开。
        //         不做并行：同一个事实两个来源会互相打架（无障碍说收起了、ADB 那条
        //         晚 2 秒还认为弹着，球的落点在两个结论之间来回跳）。
        //      ④ `BubbleAppearance` 的能力闸门改用 `KeyboardAvoid.isAvailable()`：
        //         原来只判 `ShellPrivilege`，会把「开了无障碍（更快那条）但没
        //         Shizuku」的用户误判成不可用、强制置灰。
        //      ⑤ 修 0.3.3 自身的回归：`keyboardAvoid*` 三个方法只加进了 `PageBridge`
        //         接口与 `MainActivity`，**两个 flavor 的 JS 桥白名单都漏了** ⇒
        //         页面侧 `window.DSHAndroid.keyboardAvoidState` 是 undefined ⇒
        //         走进 noBridge 分支把开关置灰，而原生日志一切正常（静默失败）。
        //         两份白名单都是手工维护的，加 `PageBridge` 方法必须同步加进去。
        //      ⚠️ 版本戳同步 bump：`MOBILE_UI_VERSION` 1.0.0-99 → -100，
        //         `build-webview-compat.ps1 -CompatVersion` 33 → 34。
        //         两个都是**字符串相等**判据，不 bump 会命中「戳一致 ⇒ 整棵树跳过」，
        //         设备上静默跑回旧 UI（本项目已踩过 20 次以上）。
        //
        // ⚠️ 30（本次）**换 versionName：0.3.3 → 0.3.4**。
        //   0.3.4 的主题是**用户数据可自救**：引擎挂掉时也能把会话/配置/工作区
        //   导出来，然后卸载重装再导回去 —— 在此之前唯一的出路是卸载，而卸载
        //   会清空整个私有目录，用户因此宁可忍着也不用重装。
        //     ① 启动层加「导出数据」「导入数据」两个按钮，**任何状态都显示**
        //        （与「查看日志」同一套兜底理由，但更要紧：设置页在 Web GUI 里，
        //        而 Web GUI 恰恰可能就是打不开的那一环）。
        //        纯 Kotlin 实现，不依赖引擎 —— 引擎完全起不来时也能用。
        //     ② 导出范围只含**用户独有、重建不出来**的部分（sessions / profiles /
        //        storages / skills / deskpet / workspace / .credentials.yaml），
        //        不含 engine/ 与 usr/（300+ MB，能从 APK 重新解包），
        //        于是包体积从 300+ MB 降到几 MB。
        //     ③ 导入先 `inspect()` 解析包内实际内容再让用户勾选，不假定七项都在；
        //        落地前把现有数据改名备份（`backup-before-import-<时间戳>/`），
        //        与安全模式同一套「改名不删」语义。
        //     ④ **跨 flavor 互通**：两个包名不同（com.dsh.android / com.dsh.webview），
        //        备份包内部用相对路径所以天然通用，但用户层配置里的
        //        `file:///data/user/0/<包名>/...` 绝对路径要在导入时改写，
        //        否则插件指向另一个 App 的私有目录、必然加载失败。
        //     ⑤ 修初版一个真机 bug：导入的文件选择用了 `AlertDialog.setItems()`，
        //        深色主题下白底白字、整行看不见，用户以为点不动。改成自绘卡片。
        //
        // ⚠️ 31（本次）**换 versionName：0.3.4 → 0.4.0**。
        //   0.4.0 的主题是**AI 拥有一台属于自己的手机浏览器**。
        //     ① 新增 `AiBrowserView`：一个**独立于官方界面**的原生 WebView。
        //        引擎自带的侧栏浏览器是 iframe，而 GitHub（`X-Frame-Options: deny`）、
        //        Google（CSP）拒绝被嵌入 ⇒ 那个方案连登录页都加载不出来，
        //        而「让 AI 登录网站」恰恰是需求核心。原生 WebView 没有这个限制，
        //        渲染在本地、零延迟、Cookie 天然持久，且**不消耗任何服务器资源**
        //        （每个用户跑在自己手机上）。
        //     ② 带完整浏览器壳：前进/后退/刷新/首页/地址栏/加载进度/多标签（上限 6）/
        //        文件下载（落 App 专属外部 Download 目录，不需要存储权限）/
        //        文件上传（走宿主已有的系统选择器）/ 视口截图落盘。
        //     ③ **控制权一次只有一方**：AI 操作时吃掉所有触摸（否则用户随手一点
        //        就会让 AI 正在填的表单失焦），顶部状态 chip 显示「AI 操作中」，
        //        用户点一下即可接管 —— 共享同一个浏览器，冲突靠锁而不是靠谦让。
        //     ④ 新增 `browser` 工具（`mobile-ui-plugin/dsh-android-browser` →
        //        `assets/browser-tool.zip` → `$DSH_HOME/browser-tool/`），经
        //        `BrowserBridge` 的文件信箱把「开网页/读页面/点按钮/填表单/截图」
        //        接到模型的工具列表里。读页面回的是**可交互元素清单**（带稳定
        //        编号），因为让模型猜 CSS 选择器在真实站点上十次有八次落空。
        //     ⑤ 模型第一次调用 `browser` 时浏览器**按需创建并自动显示**，
        //        不需要用户先手动打开。
        //     ⑥ 用户入口：右边缘常驻「浏览」把手（启动层收起后出现），
        //        打开浏览器时自动隐藏；返回键优先归浏览器（后退 → 关标签 → 收起）。
        //     ⑦ 修一个初版的静默 bug：工具栏按钮用 `ImageButton` 装 `TextView`
        //        子视图，而 `ImageButton` 继承 `ImageView`、**不是 `ViewGroup`** ——
        //        Kotlin 的隐式接收者解析把它变成了外层 `FrameLayout.addView`，
        //        文字被加到根布局左上角（被页面盖住），按钮本身是空的 ⇒ 工具栏
        //        什么都不显示。改为纯 `TextView` 按钮。
        //     ⑧ 同批修掉 `type` 动作 JS 里把 `var` 写成 `val` 的致命笔误
        //        （会让所有输入操作抛 `SyntaxError`），已用 node --check 逐段验证
        //        拼接后的五段 JS。
        // ⚠️ 32（本次）**换 versionName：0.4.0 → 0.4.1**。
        //   0.4.0 的 AI 浏览器入口是**右边缘贴边把手**，不是用户要的位置 ——
        //   他明确要求「从应用右侧边栏进入」。0.4.1 把它做到官方右侧边栏里。
        //     ① 新增**客户端插件** `dsh-android-browser-pane`（独立包，8 KB）：
        //        经 `sidebarRightTabs.register` 注册一种 tab + 经
        //        `slots.register("sidebar.right.pane.tab")` 提供正文。
        //        两个接缝缺一不可：少了前者侧栏列表里没有这一项，少了后者
        //        tab 能开出来但一片空白。
        //     ② 点开即调原生 `openAiBrowser()`（`PageBridge` 新增方法），
        //        **两份 flavor 白名单都已同步**（SystemWebHost 的
        //        `@JavascriptInterface` 与 GeckoWebHost 的 `dispatchInvoke`）——
        //        0.3.3 的 keyboardAvoid 就是漏了这份白名单，症状是「点了没反应」。
        //     ③ **为什么不并进 `dsh-android-mobile`**：那个包的 `lib/client.js`
        //        在 dev / main 两条分支上差 17 万字节，而 `assets/mobile-ui.zip`
        //        是**跨工作树共享的符号链接资产** —— 重打它会把另一条分支的
        //        移动端修复整个回退掉。独立成包互不干扰。
        //     ④ 右边缘把手**暂时保留**：侧栏入口尚无真机验证，留一条退路；
        //        确认侧栏可用后可以删（见 `ensureBrowserHandle` 的注释）。
        // ⚠️ 33 —— **调试版，不是发行版**。versionName 显式带 `-debug`，
        //   避免再被当成正式发布：0.4.0/0.4.1 那两个包曾被误传到夸克「当前最新版」，
        //   已全部撤回（见 git 记录）。今后调试包不改前两位版本号、不进夸克。
        //   本次改动：删掉 0.4.0 的右边缘「浏览」把手（它挡住了右侧栏内容），
        //   入口只剩官方右侧边栏那一项。
        versionName = "0.4.1-debug"

        // 只支持 arm64-v8a：内置的引擎资产与 Termux bootstrap 都是 arm64 架构
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Web 内核是不是**随 APK 打包**的（gecko flavor 覆盖成 true）。
        // 用途只有一个但很关键：`EngineManager.ensureWebViewCompat()` 是给**设备上
        // 的系统 WebView 太旧**准备的降级（按 chrome74 重编译前端 bundle）。内置内核
        // 的版本在编译期就定了（GeckoView 140），永远不可能"过旧"，跑降级只会白白把
        // 前端换成语法受限的版本 —— 所以那一整套在 gecko 版必须整个跳过。
        buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "false")
    }

    // ══ 两个 Web 内核版本共存 ══════════════════════════════════════════
    //
    // system = 系统 WebView 内核（原有版本，行为与 0.2.6 完全一致）
    // gecko  = 内置 GeckoView 内核（不依赖设备上的系统 WebView）
    //
    // ⚠️ **代价**：一旦有 flavor，构建任务就带上 flavor 名了 ——
    //   `:app:assembleDebug` 变成 `:app:assembleSystemDebug` / `:app:assembleGeckoDebug`。
    //
    // ⚠️ **两个 flavor 的 applicationId 必须都恰好 15 字符**，原因见 defaultConfig
    //   里那条注释（Termux ELF 的等长原地重写）。`com.dsh.android` 与
    //   `com.dsh.webview` 都是 15 字符，对应 `/data/data/<包名>/` = 27 字节。
    //   `TermuxBootstrap.ELF_ROOT_REPLACEMENT` 已改为从 `BuildConfig.APPLICATION_ID`
    //   派生，改这里不需要再同步改 Kotlin 常量。
    flavorDimensions += "webengine"
    productFlavors {
        create("system") {
            dimension = "webengine"
            // applicationId 继承 defaultConfig，产物与原有版本逐字节同源。
        }
        create("gecko") {
            dimension = "webengine"
            applicationId = "com.dsh.webview"
            // 两个包会同时存在于设备上（applicationId 不同 ⇒ 可共存），
            // 版本号带后缀才能在系统「应用信息」里分辨出装的是哪一个。
            versionNameSuffix = "-gecko"
            // 内核随 APK 分发 ⇒ 不需要也不应该跑「按 chrome74 降级前端」那一套。
            buildConfigField("boolean", "BUILT_IN_WEB_ENGINE", "true")
        }
    }

    /**
     * 内核实现放在 flavor 源集里，不是 `main`。
     *
     * ⚠️ 这是**必须**的：`GeckoWebHost` 依赖 `org.mozilla.geckoview.*`，而那个
     *   依赖只挂在 `geckoImplementation` 上。放进 `main` 会让 system flavor 也去
     *   编译它 ⇒ `Unresolved reference: geckoview`，两个包一起构建失败。
     *
     * 布局（AGP 按约定自动收集 `src/<flavor>/java` 与 `src/<flavor>/assets`，
     * 不需要显式声明 sourceSets）：
     *
     *   main/java/.../web/   → WebHost.kt、PageBridge.kt（纯接口，无内核依赖）
     *   system/java/.../web/ → SystemWebHost.kt、WebHostFactory.kt
     *   gecko/java/.../web/  → GeckoWebHost.kt、WebHostFactory.kt
     *   gecko/assets/        → dsh-bridge 扩展（只有 gecko 需要）
     */

    signingConfigs {
        // ⚠️ 这把钥匙是发行版的终身身份，与 debug 签名**互相独立**。
        // 一旦发出去就不能换：换签名 = 老用户撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
        // 只能卸载重装，而卸载会清掉 filesDir 下的整个 Termux 环境与 $DSH_HOME。
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // targetSdk=28 是本项目的硬约束（原因见 defaultConfig 里的注释），而
        // lintVitalRelease 的 ExpiredTargetSdkVersion 会直接判 release 构建失败。
        // 本 App 只能侧载、不上架 Google Play，该检查在这里无意义，故只关掉这一条，
        // 其余 lint 检查保持生效。
        disable += "ExpiredTargetSdkVersion"
    }

    // 三个资产都用 assets.open() 读取，压缩与否对调用方透明，所以**不需要**
    // noCompress 白名单（加上反而会让 APK 从 ~60MB 涨到 ~108MB）。
    // 唯一要防的是 AGP 按扩展名做格式推断：它会把 .tar.gz 自动解压并改名成 .tar，
    // 于是运行期 openFd 按原名找不到。规避方式是资产名一律用中性后缀
    // （engine.bin / node-runtime.bin / bootstrap-aarch64.zip），不靠白名单。

    // Node 改为 Termux 官方构建（随 assets 分发、解到 app 私有 $PREFIX），
    // 不再往 jniLibs 塞可执行文件，因此无需 useLegacyPackaging / extractNativeLibs。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // TermuxBootstrap.ELF_ROOT_REPLACEMENT 从 BuildConfig.APPLICATION_ID 派生 ——
        // 两个 flavor 的包名不同（com.dsh.android / com.dsh.webview），而 ELF 里的
        // 替换串必须**恰好 27 字节**且与真实包名一致，写死一个就会让另一个 flavor
        // 静默跳过路径重写（症状是 dpkg/node 全线 Permission denied）。
        // AGP 8.0 起 buildConfig 默认为 false，必须显式打开。
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── Shizuku：uid 2000 特权通道的第二条来源 ──────────────────────────
    //
    // 原有的 uid 2000 通道**全部**要求「设备上的 adbd 能被我们连上」：
    // legacy 5555（PC 跑过 `adb tcpip` 且 adbkey 被信任）或 Android 11+ 的无线调试。
    // 这对两类用户根本不成立：Android 10 及以下（没有无线调试，且每次重启都要
    // PC 再来一次），以及做不成 `adb tcpip` 的设备。而 Shizuku 只要用户装过、
    // 启动过，我们就能以 uid 2000 执行 —— 不需要 5555、不需要 adbkey。
    //
    // 三个坐标缺一不可，各自的用途不同：
    //   api      → `rikka.shizuku.Shizuku`（binder 状态、权限、getUid）
    //   aidl     → `moe.shizuku.server.IShizukuService` / `IRemoteProcess`
    //              （真正执行命令的接口；`Shizuku.newProcess` 是 private，
    //                必须经这个接口自己调，见 ShizukuChannel.kt）
    //   provider → `rikka.shizuku.ShizukuProvider`，清单里声明它才能收到 binder
    //
    // ⚠️ 版本锁在 **13.1.5**：`newProcess` 在 Shizuku API **14 计划被移除**
    //   （见其源码该方法的注释），升上去这条通道会直接编译不过。
    //   要升就必须同时改 ShizukuChannel 的执行方式，不是改个版本号的事。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 内置内核版：GeckoView（Mozilla 自维护的 Gecko 引擎，随 APK 分发、不依赖
    // 设备上的系统 WebView）。只有 gecko flavor 才需要它。
    //
    // ⚠️ 必须用 `-arm64-v8a` 那个变体而不是 `geckoview`：前者只含 arm64 的
    //   libxul.so（86 MB），后者是全 ABI 的 omni 包（230 MB），而本工程
    //   `abiFilters` 只有 arm64-v8a —— 引 omni 会把用不到的几个 ABI 一起打进 APK。
    // ⚠️ 仓库在 settings.gradle.kts 里单独声明（google()/mavenCentral() 都没有）。
    // ⚠️ 版本选择不是「越新越好」，是**被本工程的构建工具链锁死的**：
    //   157（当前 release）硬依赖 `androidx.core:core:1.19.0`（要求 AGP 9.1.0+）
    //   与 `androidx.media3:*:1.11.0`（要求 compileSdk 36）；146 依赖 core 1.17.0
    //   （要求 AGP 8.9.1+）。本工程是 AGP 8.8.2 + compileSdk 35，三者都装不下：
    //     Dependency 'androidx.core:core:1.17.0' requires Android Gradle plugin 8.9.1 or higher
    //   140 依赖 core 1.16.0（minAGP 8.6.0 / minCompileSdk 35，均满足），是能在
    //   当前工具链下直接构建的最高版本。要再往上就得先升 AGP，那是另一件事。
    "geckoImplementation"("org.mozilla.geckoview:geckoview-arm64-v8a:140.0.20250707120347")
}
