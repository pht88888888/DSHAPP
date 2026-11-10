# ⑯ 旧 WebView 内核白屏

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑯ 旧 WebView 内核白屏 —— 前端 bundle 降级编译（**不是** polyfill）

> 当前统一目标为 Chrome 73。引擎兼容资产只包含官方 dist/engine 两棵树，Android 插件由组件构建器独立降级；不再输出 mobileui，以免覆盖市场版本。Node 构建器是唯一实现，PowerShell 仅转调。戳自动包含输入摘要，manifest 校验逐文件内容；详见 [内置插件与市场更新](plugin-updates.md)。
>
> 下文为历史故障与解析器判据，旧三棵树、Chrome 74 与手工递增戳说明不再作为当前构建规则。

**症状**（用户反馈的 45 秒录屏，vivo Y52s / V2057A / Android 10 / Funtouch OS 10.5）：
启动层走完「正在启动引擎…」→「正在准备界面…」，然后**一片纯白**，永远不出来。

**这一处的要害：白屏与「缺 API」是两回事。** 必须先分清，否则会一直在补 API：

| | 缺**运行时 API** | 缺**语法** |
|---|---|---|
| 例子 | `Object.hasOwn`、`.at()`、`Promise.withResolvers` | `static {}`、`??=`、`#m()` |
| 失败时机 | 执行到那一行才抛 TypeError | **解析整份 chunk 时就失败** |
| 后果 | 前面的代码已经跑过 | **整份 chunk 一行都不执行** |
| 能否用 polyfill 补 | **能** | **不能**（垫片自己也在那份 chunk 里/依赖它） |

官方前端 bundle 用的是新语法，所以命中右边一列 —— `webview-polyfill.js` 那种
运行时垫片**完全无能为力**。这就是为什么 0.1.4 只加日志、界面照样白。

**真机定位过程（可复现）**：

1. 逐帧量状态栏像素：全程停在主题色 `#0F1115`（RGB 15,16,20），内容区 `255,255,255`。
   状态栏颜色由页面的 `onStatus` 上报驱动 ⇒ **页面 JS 一行都没执行**。
   而 HTML/CSS 明显已加载（白底来自官方前端的 CSS）⇒ 排除网络与资源 404。
2. 读内核版本：`WebView.getCurrentWebViewPackage()` → **83**。
3. 扫 bundle 找 83 解析不了的语法，得到下面这张表。

**实测缺口（engine.bin 里的官方 bundle）**：

| 语法 | 最低内核 | index | vendor |
|---|---|---|---|
| `class static block`（`static {}`） | **94** | 2 | — |
| 逻辑赋值 `\|\|=` `&&=` `??=` | **85** | 25 | 7 |
| class 私有方法（`#m(){}`） | **84** | — | 1 |
| 可选链 `?.` | 80 | 231 | 96 |
| 空值合并 `??` | 80 | 156 | 29 |

内核 83 同时踩中 94 / 85 / 84 三道 ⇒ 与白屏完全吻合。

**做法**：`tools/build-webview-compat.ps1` 用 esbuild 把 dist 降到
`--target=chrome74`，只把**真有语法缺口**的文件打成 `assets/webview-compat.zip`
（实测只有 **3 个**：`index-5SrrfWpU.js`、`vendor-CCJJTK99.js`、`index-BPHePDI_.css`，
**438 KB**）。App 侧 `EngineManager.ensureWebViewCompat()` 解到 dist 覆盖。

⚠️ **判据不能是「输入 hash ≠ 输出 hash」**：esbuild 即使不降级也会重新格式化，
那样 61 个文件全被判为「变了」，资产从 0.44 MB 涨到 0.84 MB。
判据取「同一输入，`--target=esnext` 与 `--target=$Target` 的输出是否相同」——
输出相同 ⇔ 该文件没有缺口。

⚠️ **判据也不能是正则扫 `??=`**：正则字面量里就有这些字符。实测
`langs/make-CHLpvVh8.js` 里那处 `??=` 位于 `([+:?]??=)?` 之内，是正则文本不是运算符。
交给 esbuild 自己判，它认语法不认字符串。

**验收用的是独立解析器，不是 esbuild 自己**（用 esbuild 验 esbuild 是循环论证）：
`tools/check-frontend-syntax.mjs` 用 acorn 建 AST，**遍历节点**按「特性 → 最低 Chrome 版本」
表判定。

⚠️ **不能直接看 `ecmaVersion`**：`acorn.parse({ecmaVersion:2019})` 会拒绝 class 私有字段
（标准里属 ES2022），但 **Chrome 74 早就实现了它**。
**ECMAScript 版本号 ≠ Chrome 版本号** —— 实测降级产物在 ES2019 下报「需要 ES2022」，
真因只是私有字段，会把人误导回「降级失败」。
同理，`top-level await` 的标注必须**遇到函数边界就停**（函数体内的 await 是 async 的一部分，
从 ES2017 就合法），否则误报 44 处；`numeric separator` 只看数值字面量，
字符串的 `raw` 含引号内容，`"a1_2"` 会被误判。

**对照结果**（这才是证据）：

```
原始 index-5SrrfWpU.js  → FAIL: static block ×2(94)、||= ×25(85)、?? ×156、?. ×231
原始 vendor-CCJJTK99.js → FAIL: #m() ×1(84)、||= ×7(85)、?? ×29、?. ×96
降级后两个文件          → OK: 无任何 chrome74 以上语法
webview-polyfill.js 自身 → OK
检测器自检（--target 94）→ 原始 bundle 放行（证明不是无脑报错）
```

**门槛不再写死 94**：`MainActivity.effectiveMinWebViewMajor` 按
`EngineManager.webViewCompatApplied` 动态取值 —— 降级生效 → 74，没生效 → 退回 94。
⚠️ 判定放在 `observeEngine` 的 `Ready` 分支而不是 `detectWebViewVersion()`：
后者在 `onCreate` 跑，那时引擎还没启动、降级还没落盘，会把门槛误报成 94。

**polyfill 同步补齐降级后仍缺的 API**（esbuild 只降语法、不管 API）：
`.at()`（92，index ×14 + vendor ×12）、`Object.hasOwn`（93，×7）、
`replaceAll`（85，×5）、`reportError`（95，×2）、`AggregateError`（85，×1）、
`WeakRef`（84，×1）。

⚠️ **`WeakRef` 只能近似**：真弱引用语义在旧内核上无法用 JS 复刻，这里用强引用顶替
（`.deref()` 恒返回原对象），代价是该对象不被回收。比 `new WeakRef()` 直接抛
ReferenceError 让整个 chunk 挂掉好。
⚠️ **`replaceAll` 的字符串分支不能写成 `split/join`** —— 那样 `$&`、`$$` 就不按标准展开。
转义成字面量正则后交给原生 `replace`（core-js 同做法）。
⚠️ **polyfill 自己是普通 `<script>`，不经过 esbuild**，所以它自身的语法也必须能被 74 解析
（实测 OK：没用 `?.` / `??`，只用 `async` / `for await`）。

**CSS 只降掉一半，这是已知边界**：esbuild 与 lightningcss 都只把 `inset:` 从 18 处降下来，
`:is()` 88 / `:where()` 88 / `:has()` 105 / `color-mix()` 111 / `dvh` 108 / flex `gap` 84
**一处没动**。这些在 chrome74 上是**未知语法 ⇒ 整条规则被丢弃**（不是解析失败），
所以**不会白屏，但布局/配色会错乱**（实测 33 条规则受影响，其中 9 条涉及布局，
`_markdown_*` 排版占多数）。lightningcss 也解决不了，**不要再引入它**（已试过并删除）。
真机复测时若发现「能打开但界面乱」，根因在这里。

**验收日志**（真机实测，CDY-TN90 上跑 0.1.5 debug）：

```
WebView 内核: com.huawei.webview 114.0.5.302（官方 bundle 门槛 94，降级后 74）
已应用 WebView 降级前端: 3 个文件（chrome74-1）        ← 首次
WebView 降级前端已就位，跳过（chrome74-1）             ← 之后每次
```

⚠️ 幂等戳写在 **dist 里**（`.webview-compat-stamp`）而不是别处：引擎升级会重新解包 dist，
戳跟着一起没 ⇒ 自动重打，正是想要的。实测删掉戳后下次启动会重新应用。
⚠️ 文件名带内容 hash（`index-5SrrfWpU.js`），**换 `engine.bin` 后必然对不上，必须重跑脚本**。
⚠️ 降级资产**打不上时不能放行**：门槛会退回 94，宁可提示用户升级内核，也不要放进一片白屏。

#### v2：只降 `dist` 是不够的 —— 插件 bundle 也要降（**线上真实踩到**）

**症状**（粉丝反馈，小米 13 / MIUI 14，内核版本未知但 < 110）：
composer 里点模型选择器弹红字 `groups.toSorted is not a function`，点「重试」无效；
设置页的「模型」标签页**整页空白**。

**根因**：`dsh-client-ui-model-selection/lib/client.js:475` 的
`orderModelProviders(groups)` 直接调 `groups.toSorted(...)`（**Chrome 110+**），
`dsh-client-ui-settings-models/lib/client.js:929` 是 `rows.toSorted(...)`（同一个坑）。
`toSorted` 是**运行时 API** 而不是语法，所以 esbuild 降级**碰不到它**
（`--target` 只管语法）—— 必须由 polyfill 补。而 v1 的 polyfill 清单是
**只扫 `dsh-web-frontend/dist` 数出来的**，`toSorted` 那一族在 dist 里**一次都没出现**，
于是整族被漏掉。

**要害：前端是分两部分下发的，各自独立编译。** 只降 dist ≈ 覆盖率不到 5%：

| 部分 | 位置 | v1 覆盖 |
|---|---|---|
| 官方 shell | `node_modules/@deepseek-ai/dsh-web-frontend/dist/` | ✅ |
| **71 个插件**的客户端 bundle | `node_modules/@deepseek-ai/*/lib/client.js` | ❌ |
| App 自己注入的移动端 UI 插件 | `$DSH_HOME/mobile-ui/dsh-android-mobile/` | ❌ |

实测在 `--target=chrome74` 下扫描：**dist 61 个文件里 2 个**有缺口，
而**插件 85 个 `client*.js` 里 68 个**有缺口（`dsh-api-remotes` 一个包就有 ×340 逻辑赋值）。

⚠️ **插件缺语法时的症状比白屏更隐蔽**：dist 挂掉是整页白屏（一眼可见），
而插件挂掉是**「这一个插件静默不生效」或「这一块 UI 永远空白」** ——
`window.__ModuleLoader__.load()` 根本没执行，宿主与页面都收不到完整报错。
本次的 `toSorted` 只是其中一类（API 类），语法类症状与它同构。

**做法（v2）**：脚本的降级范围扩到三棵，zip 条目**带树前缀**，App 侧按前缀分派：

| zip 条目前缀 | 解到哪棵树 |
|---|---|
| `dist/` | `engine/node_modules/@deepseek-ai/dsh-web-frontend/dist/` |
| `engine/` | `engine/node_modules/@deepseek-ai/` |
| `mobileui/` | `$DSH_HOME/mobile-ui/dsh-android-mobile/` |

实测产物：**72 个文件**（dist 3 + engine 68 + mobileui 1），**9.95 MB**。

⚠️ **`-CompatVersion` 必须 bump 到 2**：v1 的戳（`chrome74-1`）已经落在老设备的
dist 里，不 bump 会命中「戳一致 → 跳过」，于是**新纳入的插件部分永远不生效** ——
静默，且症状与没修一模一样。现在戳是 `chrome74-v2`（`$Target-v$CompatVersion`）。

⚠️ **mobile-ui 那棵树必须晚于 `ensureMobileUi()`**：那一支在版本戳不一致时会
`target.deleteRecursively()` 整个重解包 mobile-ui 目录，**会把降级产物连同它的戳一起抹掉**。
所以 `start()` 里的顺序是 `ensureMobileUi()` → `ensureWebViewCompat()`
（dist / engine 两棵树与顺序无关）。

⚠️ **两棵树各判各的戳**：只按 dist 的戳判定「已就位」整体跳过的话，上面那次重解包
之后的 mobile-ui 就是**没降级的原版**，老内核上静默退回桌面布局。

⚠️ **写 `client*.js` 到磁盘对运行时真的生效**（已核实，不是推测）：
`dsh-client-modules` 的 `/plugins` 路由是 `readFileSync(record.meta.clientPath)`
（`lib/index.js` 的 613/815/946 行），**每次请求都读盘**；
浏览器缓存也不会成为障碍 —— 缓存键里的 `rev` 来自
`artifactRevision(baseline) = framedHash("plugin-artifact", [mtimeMs, ctimeMs, size])`，
覆盖文件后 `rev` 自动变化。另外 `initialBundleSnapshot()` 在**激活时**读，
所以必须在**引擎 spawn 之前**覆盖好 —— 当前调用顺序满足。

⚠️ **验收要独立解析器，不能用 esbuild 自己验自己**：脚本第 3.5 步对**每个降级 js 产物**
调 `tools/check-frontend-syntax.mjs` 复检。实测 71 个 js 全部通过。

⚠️ **复检的两个坑（都踩过）**：
- **不能把 `.css` 喂给 acorn** —— 它必然报 `Unexpected character '@'`，
  那是解析器不适用而不是 CSS 有问题。只复检 `*.js`。
- **必须区分退出码**：`1` = 真有语法缺口，`2` = 校验工具自己跑不起来
  （本仓库没有 `package.json`，`acorn` 常缺 —— 脚本会自己装一份到临时目录）。
  早先只判 `-ne 0`，于是 `Cannot find module 'acorn'` 被当成「72 个产物全部降级失败」，
  真因一个都没有。

**polyfill 同步补齐 v2 新暴露的缺口**（都在用户主路径上）：

| API | 最低内核 | 调用方 |
|---|---|---|
| `structuredClone` | **98** | `-ui-settings`（`mutate()`）、`-ui-settings-models`（读模型配置）、`client.pdf.js` ×4 |
| `Intl.Segmenter` | **87** | `-ui-chat`（**模块顶层**构造！）、`client.pdf.js` ×2 |

⚠️ **`Intl.Segmenter` 比 `structuredClone` 更急**：`-ui-chat` 在**模块顶层**
`const LIVE_TOOL_DETAIL_SEGMENTER = new Intl.Segmenter(...)`，缺了会在**加载插件那一刻**
就抛 ⇒ **整个 chat 插件的 client bundle 注册失败**。而 chat 是主界面。
pdf.js 用到 `segments.containing(index)`，必须实现这个方法。
⚠️ 它的兜底切分粒度不如原生（UAX #29 完整实现不现实），但调用场景是
「给 UI 详情做 160 字符截断」和 pdf 里的词边界判断，**截断点略偏不会造成错误结果**。
⚠️ 该段的正则**必须用 `new RegExp` 运行时构造、不能写成字面量**：
`\p{Extended_Pictographic}` 若不被某老内核认识，抛的是 **SyntaxError**，
那会让**整个 polyfill 文件**解析失败、连带上面所有补丁一起失效。
用 try/catch 包住并退化成「按码点切」。

#### v19：降级目标 74 → **73**（真实用户卡在 73，差一级界面永远打不开）

**症状**（服务器上的真实用户包，三星 `y2q`，`com.android.webview 73.0.3683.120`）：
该设备 **4 个包**（含最新的 `0.2.9-rescue (21)`）全是同一个结局 ——

```
Web 内核: com.android.webview 73.0.3683.120（主版本 73；官方 bundle 门槛 94，降级后 74）
已应用 WebView 降级前端: dist=3, engine=68, mobileui=1 个文件（chrome74-v18）
WebView 内核 73 < 门槛 74（降级前端已生效），前端无法解析，跳过加载
```

降级前端**明明已经装好**（`chrome74-v18` 就位），却因为门槛是 74 而**一次都没被用上** ——
启动层只显示「系统 WebView 内核过旧」，界面永远打不开。**差一级卡死一台设备**。

**根因**：`MIN_WEBVIEW_MAJOR_COMPAT = 74` 与降级目标 `chrome74` 是同一个数，
两者都取自「反馈设备是 83，往下留一档」。但真实世界里存在 **73**（三星那批 ROM
的 WebView 长期停在 73），而 73 恰好还能被降级产物救回来。

**做法**：`-Target chrome73` 重新生成 + `MIN_WEBVIEW_MAJOR_COMPAT = 73`，`-CompatVersion` bump 到 19。

⚠️ **为什么 73 能收、而且只能收到 73**：脚本的两道门禁已经替我们验过 ——

| 门禁 | chrome73 结果 |
|---|---|
| 3.5 语法复检（`check-frontend-syntax.mjs`，独立解析器） | **71 个 js 产物全部通过** |
| 3.6 运行时 API 审计（对照 polyfill） | **缺口 0 个**，跳过 3 个 |

而**再往下不行**：跳过名单里的 `queueMicrotask` 门槛是 **Chrome 71**，跳过理由正是
「Chrome 71 ≤ 目标内核，原生支持」。它是宿主调度原语、纯 JS 补不了，所以 73 是
「再降无益」的位置 —— 降到 71 以下时这条豁免的前提就不成立了。

⚠️ **`-CompatVersion` 必须 bump（18 → 19）**，理由同 v2 起的所有先例：老设备的
dist 里已经落了 `chrome74-v18` 的戳，`$Target` 变了、戳字符串也变了，
但**不 bump 就没法区分「目标变了」与「同一目标的又一次生成」** ⇒
将来若要在同一目标上重新生成，会命中「戳一致 → 跳过」。现在戳是 `chrome73-v19`。

实测产物：**73 个条目**（dist 3 + engine 68 + mobileui 1 + 戳 1），**10,515,574 字节（10.03 MB）**。

