# 生成 WebView 兼容资产（webview-compat.zip）
#
# ── 为什么需要它 ──────────────────────────────────────────────────────
#
# DSH 官方前端的 bundle 是按较新的 Chromium 编译的，而**语法**（不是 API）
# 补不了 —— 一旦内核版本不够，整份 chunk 在解析阶段就失败、一行都不执行。
# 表现是「HTML 与 CSS 都正常，`#root` 永远是空的」，用户只看到白屏，
# 宿主侧连一个错误回调都收不到。
#
# 真机实测（用户反馈，vivo Y52s / Android 10，WebView 内核 **83**）：
#   index  : class static block ×2（需 94）、逻辑赋值 ??= ×25（需 85）
#   vendor : private method ×3（需 84）、逻辑赋值 ×7（需 85）
#   langs  : 逻辑赋值 ×2
#   CSS    : inset ×18（需 87）
#
# 其中 class static block 是**纯语法**，`webview-polyfill.js` 那种运行时补丁
# 完全无能为力 —— 必须在构建期降级编译。
#
# ── 做法 ──────────────────────────────────────────────────────────────
#
# 用 esbuild 把 `engine.bin` 里的前端 dist 降到 `--target=chrome74`，
# **只把内容真的变了的文件**打进 zip（实测只有 4 个 js + 1 个 css）。
# App 侧 `EngineManager.ensureWebViewCompatAssets()` 把它解到 dist 上覆盖。
#
# ── ⚠️ 为什么还降级「插件的 client bundle」（v2 起的重点）──────────────
#
# 只降 dist 是**不够的**，这是线上真实踩到的坑（用户反馈
# `groups.toSorted is not a function`，模型选择器整块打不开；同一份缺口的
# `dsh-client-ui-settings-models` 则让「模型」设置页**空白**）。
#
# 前端分两部分下发，各自独立编译：
#   dist/                     官方 shell（v1 已覆盖）
#   node_modules/@deepseek-ai/*/lib/client.js   71 个插件的客户端 bundle
#                              ← v1 完全没管，实测 68/85 个在 chrome74 下有语法缺口
# 外加 App 自己注入的 mobile-ui 插件（在 `$DSH_HOME`，不在 engine.bin 里）。
#
# 只补运行时 API（`webview-polyfill.js`）补不了**语法**：语法不达标时整份 chunk
# 在**解析阶段**就死，`window.__ModuleLoader__.load()` 永远不执行，表现为
# 「某个插件静默不生效」或「某块 UI 永远空白」，比白屏更难查。
#
# zip 里的条目带前缀标记要落到哪棵树，App 侧按前缀分派：
#   dist/<rel>                    → engine/node_modules/@deepseek-ai/dsh-web-frontend/dist/
#   engine/<pkg>/lib/<file>       → engine/node_modules/@deepseek-ai/<pkg>/lib/
#   mobileui/<rel>                → $DSH_HOME/mobile-ui/dsh-android-mobile/
#
# ⚠️ **换了 engine.bin 必须重跑本脚本**，并把产物一起提交。
#    文件名带内容 hash（`index-5SrrfWpU.js`），引擎升级后必然对不上。
#
# ⚠️ esbuild 只是**构建期工具**，不进 APK、设备上也不需要它。
#
# ⚠️ 降级产物必须**语法复检**（本脚本第 5 步会做，用
#    `tools/check-frontend-syntax.mjs`）。「esbuild exit=0」不代表产物在目标内核下
#    能解析 —— 实测它连 pdf.js 的 RegExp `v` 标志（chrome112）都能降干净，
#    但这是**实测结论**而不是保证，换个引擎版本就可能变，所以复检不能省。
#
# 用法：
#   .\tools\build-webview-compat.ps1
#   .\tools\build-webview-compat.ps1 -Target chrome83

[CmdletBinding()]
param(
    # 降级目标。chrome74 = 项目已知最低实测内核（83）之下再留一档余量。
    # 调高会让产物变大但更接近源码；调低可能触发 esbuild 无法降级的语法。
    [string]$Target = "chrome74",

    # 兼容资产版本号。改了降级逻辑/目标就 +1 —— App 侧靠它判断要不要重新覆盖。
    # v2：降级范围从「只 dist」扩到「dist + 71 个插件 client bundle + mobile-ui 插件」。
    #     ⚠️ 必须 bump：v1 的戳已经落在老设备的 dist 里，不 bump 会命中
    #     「戳一致 → 跳过」，插件那部分**永远不生效**（静默，最难查的那种）。
    # v3：加了第 3.6 步运行时 API 审计门禁（从产物反向扫 API，不再靠人工名单）。
    #     降级产物本身与 v2 相同（多补的是 polyfill，那份每次启动都覆盖写、不吃戳），
    #     但 bump 一下无害且更清晰：将来审计查出新缺口、要重新生成 zip 时不会漏。
    # v4：mobile-ui 插件改了设置页图标（悬浮气泡/交流群的自定义 SVG），
    #     `mobileui/lib/client.js` 的降级产物随之变化。**必须 bump** ——
    #     否则已装 v3 的设备会命中「两戳都一致 → 整体跳过」，
    #     mobile-ui 那棵树继续用**旧**的降级产物覆盖源文件，图标改动静默失效。
    # v5：修复 68 真正生效（去掉凭据输入框的 `-webkit-text-security: disc` 遮蔽）。
    #     同 v4 的道理：改的是 mobile-ui 源码，降级产物变了就必须 bump，
    #     否则 mobileui 那棵树的戳对得上、会被跳过，插件继续是旧的。
    # v6：修复 68 补上真正生效的那一层（原型层拦截 `type=password`）。
    #     仍然必须 bump —— 改的还是 mobile-ui 源码。
    # v7：悬浮球升级 —— mobile-ui 新增转录采集（mpCollectTranscript/mpReportTranscript）。
    #     同 v4/v5/v6 的道理：改的是 mobile-ui 源码，`mobileui/lib/client.js` 的降级
    #     产物随之变化，不 bump 会命中「两戳都一致 → 整体跳过」，
    #     那棵树继续被**旧**降级产物覆盖 ⇒ 悬浮窗拿不到转录，展开面板永远是空的。
    # v8：转录文本剔除官方时间戳（mpTranscriptVisibleText 去掉 `[data-clock]` 子树）。
    #     同 v7：改的还是 mobile-ui 源码，不 bump 的话设备上那棵树不会被覆盖，
    #     用户消息尾部会一直粘着「01:14」。
    # v9：转录选择器补上工具行 —— 判据由 `[data-tool]` 改成
    #     `[data-chat-flow-kind="tool-call"]`（前者在本页面恒为 0，工具调用一条都采不到），
    #     并补上 `steering`。同 v7/v8：改的还是 mobile-ui 源码，不 bump 的话
    #     设备上那棵树继续被旧降级产物覆盖 ⇒ 面板里永远看不到「工具调用」行。
    # v10：转录改为近流式 + 思考与正文分项。改了 `mpScheduleTranscript` 的节流
    #     （1500ms → 250ms）与 `mpCollectTranscript`（按 `data-chat-group-part`
    #     把 reasoning 发成独立的 `reasoning` 角色）。同 v7/v8/v9：改的还是
    #     mobile-ui 源码，不 bump 的话设备上那棵树不会被覆盖 ⇒
    #     面板仍是一坨一坨地出、思考与正文仍糊在一起。
    # v11：uid 2000 特权通道全面修复 —— mobile-ui 的 client.js 改了「下一步」的
    #     推导（按 runner 的 `诊断: kind=…` 分类给不同动作/文案）。同 v4~v10 的
    #     道理：改的是 mobile-ui 源码，`mobileui/lib/client.js` 的降级产物随之变化，
    #     不 bump 会命中「两戳都一致 → 整体跳过」，设备上那棵树继续被**旧**降级产物
    #     覆盖 ⇒ 设置页永远只显示改动前的通用引导。
    # v12：`mpPageBackground()` 改为读主题 token `--dsw-alias-bg-base`（取不到才退回
    #     `backgroundColor`）。同 v4~v11 的道理：改的是 mobile-ui 源码，
    #     `mobileui/lib/client.js` 的降级产物随之变化，不 bump 会命中
    #     「两戳都一致 → 整体跳过」，设备上那棵树继续被旧产物覆盖 ⇒
    #     系统栏跟随的仍是旧投影色，"跟随主题色"这层改动静默失效。
    # v13：`fullscreen` 让位修复尝试一 —— `mobileui/lib/client.js` 给内容列
    #     `.pI_x6G_centerCol` 加了一条 `padding-top: var(--dsh-android-system-top)`
    #     CSS 规则。**这一版是错的**：同属性上已有修复 66 加的
    #     `padding-top: var(--mp-float-nav-h) !important`，被它压掉，整条规则
    #     完全不生效（实测 getComputedStyle 仍是 0px）。
    # v14：改用 JS —— 让 `mpSyncFloatNavGap()` 在算 `--mp-float-nav-h` 时把
    #     状态栏高度也纳入（JS 的 Math.max 不受内核版本限制）。
    #     不能在 CSS 里写 max()：那是 Chrome 79 才有的，本工程目标内核
    #     **chrome74**，老内核上整条声明会失效 ⇒ 全屏下顶部被啃掉且只在老设备复现。
    #     ⚠️ mobile-ui 源码改了就必须 bump：不 bump 会命中「两戳都一致 → 整体跳过」，
    #     设备上那棵树继续被旧降级产物覆盖 ⇒ "开了全面屏，顶上一行被时间盖住"，
    #     而原生侧日志看起来一切正常。
    # v15：`fullscreen` 让位修复尝试二 —— 改用 JS（见上）。
    # v16：设置页新增「运行日志与诊断」分节（`mpLogsSection` +
    #     `mpRegisterLogsSection`，拉起原生 `LogViewerActivity`）。
    #     ⚠️ 这一版**看起来**只是加了个设置项、跟"降级编译"无关，但**必须 bump**：
    #     `mobileui/lib/client.js` 是降级编译的**输入之一**（见本文件头部 v4 起的
    #     全部先例），改它 ⇒ 降级产物变化 ⇒ 不 bump 会命中「两戳都一致 → 整体跳过」
    #     ⇒ 设备上那棵树继续被**旧**产物覆盖 ⇒ 设置页里根本看不到这个分节，
    #     而原生侧日志（`已安装移动端 UI 插件`）看起来一切正常。
    #     这个失败方式本项目已经踩过至少 8 次（v4~v15 每一版都在记同一件事）。
    # v17：日志分节补读一次（`mobileui/lib/client.js` 的 `refreshRef`）——
    #      改的是 mobile-ui 源码，降级产物随之变化，不 bump 会命中
    #      「戳一致 → 跳过」，插件继续是旧的（静默）。
    # v18：**沉浸式下汉堡整行下移**的真修复在原生
    #      （`MainActivity.pushSystemBarInset` 把物理像素 ÷ density 换算成 CSS 像素，
    #      原先直推 108 而页面只该让位 36，让位量被放大 3 倍）。
    #      本版 `mobileui/lib/client.js` 只改了两处**注释**（把"该变量恒 0"这句
    #      在修复 58 之后就已过时的话更正）—— 严格说没有行为变化，本可不 bump。
    #      但注释变了、降级产物字节就变，两个戳只要都对得上设备就会整棵树跳过，
    #      于是源码与设备长期不一致。宁可多 bump 一次，也不留这种中间态。
    # v19：**特殊权限界面布局重做 + 新增 Shizuku 通道卡片** ——
    #      `mobileui/lib/client.js` 大改：新增 Shizuku 状态卡（四态徽章）、
    #      三条路径统一成 `pathCard` 同一结构、顺序固定为
    #      Shizuku → 无线调试 → 电脑引导（不再按系统版本翻转）、
    #      首屏「下一步」的决策树把 Shizuku 排到 adb 那两条路之前。
    #      这是**真实的行为变化**，必须 bump —— 不 bump 会命中
    #      「两戳都一致 → 整棵树跳过」，设备上继续跑旧布局，
    #      而原生日志（`已安装移动端 UI 插件 1.0.0-63`）看起来一切正常。
    #      本次真机实测正是这个形态：磁盘上 client.js 停在 369105 字节的旧产物。
    # v20：**特殊权限界面改成标签页布局** —— `mobileui/lib/client.js` 把原来
    #      「三条路径 + root 卡全部平铺」改成 ADB / Shizuku / root 三个标签页，
    #      标签上带状态圆点，点标签切到该通道的二级界面（步骤、命令、明细都在那儿）。
    #      同时无线调试在 Android 10 上**恢复渲染**为一张纯说明卡
    #      （上一版是整张不渲染，用户看不到还有这条路）。
    #      这是**真实的行为变化**，必须 bump —— 不 bump 会命中
    #      「两戳都一致 → 整棵树跳过」，设备上继续跑平铺的旧布局，
    #      而原生日志（`已安装移动端 UI 插件 1.0.0-64`）看起来一切正常。
    # v21：修 v20 里 `rootIntro` 的 Markdown 标记（`**互不影响**` 会原样显示 ——
    #      本页是纯文本渲染）。**内容变了一字节也要 bump**：这一版的戳若仍是
    #      `chrome74-v20`，它会与设备上已有的戳字符串**完全相等**，
    #      于是「两戳都一致 → 跳过」照旧命中，修正版上不了机。
    #      判据是**字符串相等**，不是「这次跑过脚本」——重跑脚本不改版本号等于没跑。
    # v22：设置页「交流群」换群 —— 新群二维码（720x720 WebP，52,764 B）+ 群号
    #      1127652833 → 1109733098。`mobileui/lib/client.js` 是降级编译的输入之一，
    #      所以**必须 bump**：只重打 `mobile-ui.zip` 而不动这里，设备上那棵树会命中
    #      原有的戳字符串 `chrome74-v21`（**相等 ⇒ 跳过**），继续跑旧群的二维码。
    #      同 v21：判据是字符串相等，不是「这次跑过脚本」。
    # v23：mobile-ui 三处修复都要进产物 ——
    #      ① 修复 64：底栏模型名被截断（逐层放宽 flex + 干掉官方 max-width）；
    #      ② 修复 65：底部遥测栏挤成两行/溢出被裁（nowrap + 降档判据改写）；
    #      ③ 修复 66：推理等级 High 被误藏（改回显示，字号 10px/opacity 0.65）。
    #      三条都改在 `mobileui/lib/client.js` 里 ⇒ 本戳必须 22 → 23，
    #      否则设备命中「字符串相等 ⇒ 整棵树跳过」，跑的还是旧布局（同 v22 的坑）。
    # v34：合并 PR #3（悬浮球键盘避让）—— 悬浮气泡分节新增「键盘避让」卡片
    #      （46×26 开关 + 无障碍权限检测 + 独立「去开启权限」按钮）。
    #      改的仍是 `mobileui/lib/client.js`，降级产物随之变化，必须 bump。
    #      ⚠️ PR #3 原作把本戳写成 24、把 `MOBILE_UI_VERSION` 写成 1.0.0-68，
    #      那是基于旧的 22/1.0.0-66 算的。合并时**必须取本地已推进的值再 +1**
    #      （33 → 34 / 1.0.0-99 → -100），否则会回退，设备上静默跑回旧 UI。
    # v40：`onUiReady` 三处根因修复（10-31-a）。**本版必须在 Windows 与 Linux 两侧
    #      都取 40** —— 线上事故的原因正是漏跑本脚本：`mobile-ui.zip` 换了新版而
    #      `webview-compat.zip` 的 `mobileui/lib/client.js` 还停在 10-29 的旧降级产物，
    #      设备上先解出新插件、随后被旧产物整份覆盖（`build=2026-10-29-a` 照旧，
    #      旧版里的 `diag is not defined` 还会炸掉整条 client bundle）。
    [string]$CompatVersion = "48",

    [string]$EngineBin,
    [string]$OutZip
)

$ErrorActionPreference = "Stop"

# ⚠️ 默认路径必须在 param 块**之外**算：`$PSScriptRoot` 在 param 默认值里还是空的
#    （Windows PowerShell 5.1 下同样如此），放进去会直接报「Path 为空字符串」。
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $EngineBin) { $EngineBin = Join-Path $repoRoot "app\src\main\assets\engine.bin" }
if (-not $OutZip) { $OutZip = Join-Path $repoRoot "app\src\main\assets\webview-compat.zip" }

# ── 1. 准备 esbuild（临时目录，不进仓库）────────────────────────────────
#
# ⚠️ esbuild 装在**独立的持久目录**里，不能放进下面每次都要清空的 `$work`。
# 经代理装一次 esbuild 实测要 **3 分 26 秒**（206 s，2 个包），而它只是构建期
# 工具、与本次要降级的文件无关。原来 `$work` 每次运行都 `Remove-Item -Recurse`
# 再重装，等于每跑一次白等三分半 —— 这也是「这个脚本怎么这么慢」的主因。
$esbuildTool = Join-Path $env:TEMP "dsh-esbuild-tool"

# `$work` 仍然每次清空：extract 出来的 dist 必须干净，免得旧文件混进产物。
$work = Join-Path $env:TEMP "dsh-webview-compat"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null

$esbuild = Join-Path $esbuildTool "node_modules\.bin\esbuild.cmd"
if (-not (Test-Path $esbuild)) {
    Write-Host "安装 esbuild（构建期工具，不进 APK；仅首次需要，约 3 分钟）…"
    New-Item -ItemType Directory -Path $esbuildTool -Force | Out-Null
    # 走代理：本机直连 npm 会超时（与 gradle.properties 里同一个代理）
    $env:HTTPS_PROXY = "http://127.0.0.1:7897"
    $env:HTTP_PROXY = "http://127.0.0.1:7897"
    & "npm.cmd" install esbuild@0.25.0 --prefix $esbuildTool --no-audit --no-fund --loglevel=error
    if ($LASTEXITCODE -ne 0) { throw "esbuild 安装失败" }
} else {
    Write-Host "复用已装的 esbuild: $esbuildTool"
}

# ── 2. 从 engine.bin 解出「前端 dist」与「插件的 client bundle」──────────
$extract = Join-Path $work "extract"
New-Item -ItemType Directory -Path $extract -Force | Out-Null
$tgz = Join-Path $work "engine.tar.gz"
Copy-Item $EngineBin $tgz -Force

Write-Host "解出前端 dist 与插件 client bundle…"
Push-Location $extract
# 两处通配符解包走**同一次调用**（bsdtar 原生支持 `*`，不需要 --wildcards，
# 加了反而报「Option --wildcards is not supported」）。
# `client*.js` 覆盖 client.js 与三个 lazy chunk（client.pdf/excel/terminal.js）。
& tar -xzf $tgz "node_modules/@deepseek-ai/dsh-web-frontend/dist" "node_modules/@deepseek-ai/*/lib/client*.js"
if ($LASTEXITCODE -ne 0) { throw "从 engine.bin 解包失败" }
Pop-Location

$dist = Join-Path $extract "node_modules\@deepseek-ai\dsh-web-frontend\dist"
if (-not (Test-Path $dist)) { throw "engine.bin 里没找到前端 dist" }

# 插件的客户端 bundle（不含 dsh-web-frontend —— 那个是 shell，走 dist 那条路）
$pluginRoot = Join-Path $extract "node_modules\@deepseek-ai"
$pluginFiles = @(
    Get-ChildItem $pluginRoot -Directory |
        Where-Object { $_.Name -ne "dsh-web-frontend" } |
        ForEach-Object { Get-ChildItem (Join-Path $_.FullName "lib") -File -Filter "client*.js" -ErrorAction SilentlyContinue }
)
if ($pluginFiles.Count -eq 0) { throw "engine.bin 里没解出任何插件 client bundle，解包通配符可能失效了" }
Write-Host "  → 插件 client bundle $($pluginFiles.Count) 个"

# ── 3. 逐个降级，只留真有语法缺口的 ────────────────────────────────────
#
# 判据是「同一份输入，--target=esnext 与 --target=$Target 的输出是否相同」：
# esbuild 只在**确实需要降级**时才会改写代码，所以输出相同 ⇔ 该文件没有缺口。
#
# ⚠️ **不能拿「输入 hash ≠ 输出 hash」当判据**：esbuild 即使不做任何降级也会
#    重新格式化（去缩进、统一引号），输出必然不同，那会把 61 个文件全收进来
#    （实测资产从 0.8 MB 涨到 64 个条目）。
#
# ⚠️ **也不能拿正则扫 `??=` / `static {` 当判据**：正则字面量里就可能有这些字符。
#    实测 `langs/make-CHLpvVh8.js` 里那处 `??=` 位于 `([+:?]??=)?` 之内，是
#    正则的文本而不是运算符 —— 用正则判会把 59 个 langs 全误收进来。
#    交给 esbuild 自己判，它认语法、不认字符串。

# 待降级清单。Entry 是 zip 内部路径，前缀决定 App 侧解到哪棵树：
#   dist/      → engine/node_modules/@deepseek-ai/dsh-web-frontend/dist/
#   engine/    → engine/node_modules/@deepseek-ai/
#   mobileui/  → $DSH_HOME/mobile-ui/dsh-android-mobile/
$targets = @()

foreach ($f in (Get-ChildItem $dist -Recurse -File -Include *.js, *.css)) {
    $rel = $f.FullName.Substring($dist.Length + 1) -replace '\\', '/'
    $targets += @{ Src = $f.FullName; Entry = "dist/$rel"; Label = "dist" }
}

foreach ($f in $pluginFiles) {
    # <pkg>/lib/client*.js —— pkg 是 lib 的父目录名
    $pkg = $f.Directory.Parent.Name
    $targets += @{ Src = $f.FullName; Entry = "engine/$pkg/lib/$($f.Name)"; Label = $pkg }
}

# App 自己注入的移动端 UI 插件。它**不在 engine.bin 里**（源码在仓库里、
# 由 mobile-ui.zip 下发），但它同样是浏览器要解析的 bundle，实测在 chrome74 下
# 有 ×10 optional chaining + ×6 nullish —— 一并降级，否则老内核上会静默退回桌面布局。
$mobileUiSrc = Join-Path $repoRoot "mobile-ui-plugin\dsh-android-mobile"
if (Test-Path $mobileUiSrc) {
    foreach ($f in (Get-ChildItem $mobileUiSrc -Recurse -File -Filter "client*.js")) {
        $rel = $f.FullName.Substring($mobileUiSrc.Length + 1) -replace '\\', '/'
        $targets += @{ Src = $f.FullName; Entry = "mobileui/$rel"; Label = "mobile-ui" }
    }
} else {
    Write-Warning "找不到 $mobileUiSrc，跳过移动端 UI 插件的降级"
}

$out = Join-Path $work "out"
New-Item -ItemType Directory -Path $out | Out-Null
$probe = Join-Path $work "probe"
New-Item -ItemType Directory -Path $probe -Force | Out-Null

$changed = @()
Write-Host "扫描 $($targets.Count) 个 js/css（目标 $Target）…"

foreach ($t in $targets) {
    # 产物按 zip 条目路径摆放，打 zip 时可直接用
    $dst = Join-Path $out ($t.Entry -replace '/', '\')
    New-Item -ItemType Directory -Path (Split-Path $dst -Parent) -Force | Out-Null

    # 基准：不降级，只做同样的格式化
    $baseline = Join-Path $probe "baseline"
    & $esbuild $t.Src --target=esnext "--outfile=$baseline" --log-level=error 2>&1 |
        ForEach-Object { if ("$_".Trim()) { Write-Warning "esbuild: $_" } }
    if ($LASTEXITCODE -ne 0) { throw "esbuild 基准转换失败: $($t.Entry)" }

    & $esbuild $t.Src "--target=$Target" "--outfile=$dst" --log-level=error 2>&1 |
        ForEach-Object { if ("$_".Trim()) { Write-Warning "esbuild: $_" } }
    if ($LASTEXITCODE -ne 0) { throw "esbuild 降级失败: $($t.Entry)" }

    if ((Get-FileHash $baseline).Hash -ne (Get-FileHash $dst).Hash) {
        $changed += $t
    } else {
        Remove-Item $dst -Force
    }
}

if ($changed.Count -eq 0) {
    Write-Warning "没有任何文件需要降级 —— 前端可能已按低版本编译，或 $Target 给高了"
}

# 按树分组打印，便于一眼看出「哪棵树完全没被覆盖」（那正是 v1 的病）
Write-Host "需要降级的文件（$($changed.Count) 个）:"
foreach ($g in ($changed | Group-Object { $_.Entry.Split('/')[0] })) {
    Write-Host "  [$($g.Name)] $($g.Count) 个"
    $g.Group | ForEach-Object { Write-Host "      $($_.Entry)" }
}

# ── 3.5 语法复检：esbuild 说"降完了"不等于产物真能在目标内核下解析 ───────
#
# ⚠️ 这一步不能省。「esbuild exit=0」只说明它没报错，而解析门槛是**产物**的属性。
#    实测它能连 pdf.js 的 RegExp `v` 标志（chrome112）一起降干净，但那是实测结论、
#    不是 API 保证 —— 换个 esbuild/引擎版本就可能变。
#
# 复用 `tools/check-frontend-syntax.mjs`（acorn 遍历 AST 按特性查 Chrome 版本）。
$checker = Join-Path $repoRoot "tools\check-frontend-syntax.mjs"
$targetMajor = [int]($Target -replace '\D', '')
if ((Test-Path $checker) -and $changed.Count -gt 0) {
    Write-Host "语法复检（目标 chrome$targetMajor）…"
    $bad = @()
    $toolBroke = $null
    $checked = 0
    foreach ($t in $changed) {
        # ⚠️ **只复检 .js。** `check-frontend-syntax.mjs` 是 acorn（JS 解析器），
        #    喂 .css 进去必然报 `Unexpected character '@'` —— 那是解析器不适用，
        #    不是 CSS 有问题。降级产物里那个 css（`index-*.css`，inset 属性）由
        #    esbuild 的 `--target` 负责，它的门槛与 JS 不同，这里不重复判定。
        if ($t.Entry -notlike "*.js") { continue }
        $checked++
        $dst = Join-Path $out ($t.Entry -replace '/', '\')
        # ⚠️ 必须看退出码：1 = 真有语法缺口，2 = 校验工具自己跑不起来（如 acorn 装不上）。
        #    早先只判 `-ne 0` 就把「acorn 找不到」也当成语法不合格，于是 72 个产物
        #    全被报成失败、真因一个都没有 —— 环境问题绝不能伪装成语法问题。
        $verdict = & node $checker --target $targetMajor $dst 2>&1
        $rc = $LASTEXITCODE
        if ($rc -eq 2) {
            $toolBroke = ($verdict | Select-Object -Last 2) -join "`n"
            break
        }
        if ($rc -ne 0) { $bad += "$($t.Entry)`n$($verdict -join "`n")" }
    }
    if ($toolBroke) {
        Write-Warning "语法复检未能执行（校验工具本身的问题，不代表产物有问题）：`n$toolBroke"
        Write-Warning "请手工确认产物，或修好 tools/check-frontend-syntax.mjs 后重跑"
    } elseif ($bad.Count -gt 0) {
        Write-Host ($bad -join "`n")
        throw "降级产物仍有 chrome$targetMajor 以上语法，见上方清单 —— 换更高的 -Target 或升级 esbuild"
    } else {
        Write-Host "  → $checked 个 js 产物全部通过"
    }
}

# ── 3.6 运行时 API 审计：语法过关 ≠ 能跑 ────────────────────────────────
#
# ⚠️ **这一步是「完整修复」的地基，别删。**
#
# 语法复检（3.5）只能证明产物在目标内核下**能解析**，证明不了它**能跑**：
# `toSorted`（Chrome 110）与 `AbortSignal.prototype.throwIfAborted`（100）
# 都是**合法语法**，esbuild 也降不了（它们是运行时 API、不是语法），
# 但在老内核上就是 `xxx is not a function`，整块 UI 直接打不开。
#
# 这两次线上事故的共同根因是**同一个**：polyfill 该补哪些 API 是**人工列**的，
# 而人工名单必然会漏 —— 第一次漏了实例方法（只收了 `AbortSignal.any`，
# 没收 `throwIfAborted`），第二次漏了一整个家族（只扫了 dist，没扫插件）。
#
# 所以改成**从产物反向扫描**：把三棵树的 js 都过一遍 AST，抽出真正用到的
# 标准库成员，再和 polyfill 做差集，有缺口就**构建失败**。
# 新增的 API 只会让这一步报错，不会再静默漏到用户手上。
$auditor = Join-Path $repoRoot "tools\audit-runtime-apis.mjs"
$polyfill = Join-Path $repoRoot "app\src\main\assets\webview-polyfill.js"
if (Test-Path $auditor) {
    Write-Host "运行时 API 审计（对照 $([System.IO.Path]::GetFileName($polyfill))）…"
    # ⚠️ **三棵树都要扫**，少一棵就会有盲区：
    #    $dist       官方 shell（dist 里的 js/css 同样是浏览器要解析的）
    #    $pluginRoot 71+ 个插件的 client bundle
    #    $mobileUiSrc App 自己注入的插件（不在 engine.bin 里）
    #    ⚠️ 曾经只扫 dist —— 那正是 `toSorted` 漏掉的原因（dist 恰好一处都没用到）。
    #    审计**源文件**而不是 $out 里的降级产物：$out 只含「有语法缺口」的文件，
    #    而「语法本来就 OK、但用了新 API」的文件不在里面 —— 它们照样会被加载，
    #    漏扫它们等于留一个盲区。
    $auditOut = & node $auditor --polyfill $polyfill --target "chrome$targetMajor" $dist $pluginRoot $mobileUiSrc 2>&1
    $auditRc = $LASTEXITCODE
    # 只打印结论段，完整清单太长
    $auditOut | Select-Object -Last 22 | ForEach-Object { Write-Host "  $_" }
    if ($auditRc -eq 2) {
        Write-Warning "运行时 API 审计未能执行（工具自身问题，不代表产物有问题）"
    } elseif ($auditRc -ne 0) {
        throw "存在未被 polyfill 覆盖的运行时 API（见上方 ❌ 清单）—— 在 webview-polyfill.js 里补齐后重跑"
    } else {
        Write-Host "  → 无缺口"
    }
} else {
    Write-Warning "找不到 $auditor，跳过运行时 API 审计（缺口将静默漏到用户手上）"
}

# ── 4. 打 zip（条目为相对路径带树前缀 —— App 侧按前缀分派目的一棵树）──
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $OutZip) { Remove-Item $OutZip -Force }
$zip = [System.IO.Compression.ZipFile]::Open($OutZip, 'Create')
try {
    foreach ($t in $changed) {
        $src = Join-Path $out ($t.Entry -replace '/', '\')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $src, $t.Entry) | Out-Null
    }
    # 版本戳：App 侧读它决定要不要重新覆盖
    $stampTmp = Join-Path $work "stamp.txt"
    Set-Content -Path $stampTmp -Value "$Target-v$CompatVersion" -NoNewline -Encoding ascii
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $stampTmp, ".compat-stamp") | Out-Null
} finally {
    $zip.Dispose()
}

$size = (Get-Item $OutZip).Length
Write-Host ""
Write-Host "已生成: $OutZip"
Write-Host ("  条目 {0} 个，{1:N0} 字节（{2:N2} MB）" -f ($changed.Count + 1), $size, ($size / 1MB))
Write-Host ""
Write-Host "⚠️ 戳是 $Target-v$CompatVersion（App 侧从 zip 里读，无需改 Kotlin 常量）"
Write-Host "⚠️ 确认 MainActivity.MIN_WEBVIEW_MAJOR_COMPAT = $targetMajor 与本次 -Target 一致"
