# ⑫.5 ui 工具

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑫.5 `ui` 工具 —— 让**模型自己**看和点这个 App 的界面

⑫.3 把「改悬浮窗」接到了模型的工具列表里，但它只能改**悬浮球**。
界面本身（官方 DSH Web GUI 那个聊天页）仍然是模型碰不到的：

| 谁 | 能不能操作界面 | 为什么 |
|---|---|---|
| 页面侧插件（`client.js`） | ✅ | 浏览器里直接摸到 DOM |
| **模型（AI）** | ❌ | 工具跑在 **Node 进程**里，没有 `window`、没有 `document` |

于是「让 AI 自己测这个 App」在能力上是空的 —— 它既看不见界面，也动不了界面。
⑫.5 补的就是这一段：注册一个独立工具 `ui`，把**枚举可交互元素（带屏幕精确坐标）
/ 点击 / 输入 / 滚动 / 读文本 / 等元素出现**整套能力交给模型。

```
模型 ──调用 ui 工具──> dsh-android-uiagent（Node 插件，跑在引擎进程）
                            │ 写 $DSH_HOME/ui-cmd.json
                            ↓
                UiProbeBridge（原生，120ms 轮询）
                            │ WebHost.evaluateJavascript( (<ui-probe.js 源码>)(cmd) )
                            ↓
                页面世界 window.__DSH_UI_PROBE__.run(cmd)  ← React 渲染的真实 DOM
                            │ JSON 结果
                            ↑
                原生把 CSS 坐标换算成屏幕物理坐标
                            │ 写 $DSH_HOME/ui-result.json
                            ↑
模型 <──工具返回────────────┘
```

| 件 | 位置 |
|---|---|
| 页面探针 | [runtime/scripts/ui-probe.js](../../runtime/scripts/ui-probe.js)（APK 资产名仍为 `ui-probe.js`，**不走转译**，见下） |
| 插件源码 | `mobile-ui-plugin/dsh-android-uiagent/` |
| 打包产物 | `assets/uiagent-tool.zip`（解到 `$DSH_HOME/uiagent-tool/dsh-android-uiagent/`） |
| overlay | `$DSH_HOME/uiagent-tool.patch.yml`（`ensureUiTool()` + `writeUiToolPatch()`） |
| 原生通道 | `UiProbeBridge.kt`（`MainActivity.installUiProbeBridge()`） |

⚠️ **信箱文件与 `bubble` 工具完全分开**（`ui-cmd.json` / `ui-result.json` vs
`bubble-cmd.json` / `bubble-result.json`）。两者各自从 `id=1` 自增，共用文件会让
工具调用收到对方的回执。理由与 `bubble-state.json` 独立成文件完全一致。

#### ⚠️ 模型的 `action` 与原生内部 `control` 是**两个名字，必须都认**

原生最初只认 `control` 字段（内部旁路信号），而工具对外暴露的是统一的 `action` ——
模型不可能知道原生内部另有一个字段名。只认 `control` 的后果是**静默降级**：

| 调用 | 只认 `control` 时 | 修复后 |
|---|---|---|
| `action:"wait"` | 落进 else 分支，**只查一次**就报「没出现」 | 原生按 250ms 循环查，最多 10s |
| `action:"reload"` | 探针不认识这个 action → 报「未知 action: reload」 | 走原生 `reload()` 分支 |

两者都不报错到能看出根因。`UiProbeBridge.handle()` 现在写成
`control.ifBlank { action }`，**只有 `wait`/`reload` 会被拦下**，其余动作行为不变。

#### ⚠️ 探针**不经过任何转译**，必须守 ES5

`ui-probe.js` 是运行时被 `evaluateJavascript` 直接 eval 的源码，**不经过
`build-webview-compat.ps1` 那套 esbuild 降级**。用了 ES6+ 语法（`?.`、`??`、
`=>`、模板串）在老 WebView 上会**整段语法错误、探针静默全废** ——
而页面里看不到任何症状，只有原生侧每次调用都超时。

用 esbuild 验（包装成表达式再降级，因为探针本身是**匿名函数表达式**，
直接喂给 esbuild 会报 `Expected identifier but found "("`，那是语法形态不是兼容性问题）：

```powershell
$esbuild = "$env:TEMP\dsh-esbuild-tool\node_modules\.bin\esbuild.cmd"
$src = Get-Content runtime\scripts\ui-probe.js -Raw
Set-Content "$env:TEMP\wrapped.js" -Value "var __probe = ($src);" -Encoding UTF8
& $esbuild "$env:TEMP\wrapped.js" --target=chrome74 --outfile="$env:TEMP\o74.js"
# 实测 chrome74 / chrome69 / chrome64 三档全部 exit=0、零警告
```

`node --check` **对它无效**（匿名函数表达式不是独立语句，必报
`Function statements require a function name`）—— 别拿它的失败当问题。

#### 坐标系（「精确」二字的落点）

探针只返回 **CSS 视口坐标**（`getBoundingClientRect`）+ 视口尺寸；换算全在原生侧，
因为页面里拿不到 WebView 在屏幕上的位置（`window.screenX` 在 WebView 内恒为 0）：

```
scale     = webView物理宽度 / 页面视口CSS宽度
屏幕物理X = webView屏幕X + cssX * scale
```

回执里因此有**两套坐标**，每个元素都带：

| 字段 | 含义 | 用在哪 |
|---|---|---|
| `center` / `rect` | CSS 视口坐标 | 页面内的判断 |
| `screenCenter` / `screenRect` | **屏幕物理坐标** | 要驱动真机触控（`input tap`）时**只能用这个** |

⚠️ `ScreenGeometry` 是通过**取值函数**（`provider` lambda）现取的，不是启动时存一份 View
引用 —— Activity 重建（旋转、从后台回来）会换掉整个 WebView，持旧引用会把 JS 打进
一个已销毁的内核，症状是「AI 说点过了，但界面没动」。

#### ⚠️ 受控输入：`el.value = x` 是**无效**的

React 在元素**实例**上定义 `value` 的 get/set 拦截器，把「上一轮的值」存在闭包里，
靠 `tracked !== node.value` 判断要不要触发 `onChange`。直接 `el.value = x` 会**同时**
更新拦截器里的记录 ⇒ React 认为没变化 ⇒ `onChange` 不触发 ⇒ 输入框看着有字、
但组件状态是空的（发送出去还是空消息）。

正解是走**原型上的原始 setter**，再派发冒泡的 `input` 事件：

```js
Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, text);
el.dispatchEvent(new Event('input', { bubbles: true }));
```

`clickEl()` 同理要**按顺序**发 `pointerdown → mousedown → pointerup → mouseup → click`：
React 18 用根级事件委托，缺前置事件时某些组件的状态机不推进，表现是「click 发了但界面没变」。

#### `onTarget` 的判据是「手指会不会点到目标」

`elementFromPoint` 的结果如实报告，与「我们把事件发给了谁」分开：

- 事件**总是发给目标元素**（意图是「点它」，有装饰层浮在上面也不该丢掉意图）；
- `onTarget:false` 表示**有无关元素压在上面**（遮罩、蒙层），此时 `hitTopmost` 会告诉
  你实际接收点击的是谁。

⚠️ 第一版把这个判据写成 `target === el`，而 `target` 只在「命中元素与目标有祖先/后代
关系」时才被改掉 —— 于是无关遮罩压上来时 `target` 仍是 `el`，**`onTarget` 恒为 true、
这个字段等于没有**，而工具文档还写着「onTarget:false 说明被遮挡」。判据必须直接看
命中元素本身。

#### ⚠️⚠️ 「看得见」不等于「点得动」—— 遮挡识别是这套工具最要紧的一环

**这是「AI 说点过了但界面没动」的头号成因**，实测在官方前端上撞到：

全新 profile 首次打开会弹**「预览版说明」对话框**（`z-index:1000` + 一层覆盖全屏的
`_mask_`），关掉它紧接着又是**「API 密钥」**向导。这两个弹窗盖着的时候，底下 16 个按钮
在 DOM 里**看着完全正常** —— 有尺寸、`opacity` 是 1、`visibility` 是 visible、
`pointer-events` 是 auto。第一版 `isVisible()` 只查元素**自身**样式，于是把它们全部
报成可用；模型挨个 `tap`、界面纹丝不动，而拿到的只有 `onTarget:false` 一句，**完全
不知道是谁挡着、下一步该干嘛**。

所以「精确」不只是坐标对，还必须包含**此刻它能不能被点到**。回执里三处都带上：

| 字段 | 在哪 | 含义 |
|---|---|---|
| `covered` / `blockedBy` | 每个元素 | 它现在被谁盖住（`blockedBy` 直接给出遮挡者的选择器/类名） |
| `overlay` / `blocked` / `warning` | `dump` 顶层 | 有没有全屏遮挡层、多少元素点不动、一句可读的警告 |
| `present` / `layer` / `clickable` | `overlay` 动作 | 遮挡层是谁 + **此刻真正能点的是哪几个** |

判据与 `clickEl()` 里的 `onTarget` **同源**（都走 `blocker()`）—— 否则「dump 说能用」
与「tap 说没点到」会互相矛盾，模型无从判断该信哪个。

⚠️ **遮挡层要往上找壳**。遮挡层几乎总是「一个覆盖全屏的容器 + 里面的实际内容」，
采样点命中的往往是**内容**（弹窗里的一段文字），它自己的 rect 很小 —— 直接拿命中元素
判面积会得出「没有遮挡」。`fullScreenAncestor()` 就是干这个的。

⚠️ **必须多点采样**。第一版只取视口中心一个点，结果出现
**`overlay` 报「没有遮挡层」而同一时刻 `dump` 报「15 个元素被遮挡」**的自相矛盾 ——
两个字段对同一件事给出相反答案，比缺字段更糟。现在取 9 个点（中心 + 四角内缩 + 四边
中点），**过半数**被同一层挡住才认定是遮挡层。

⚠️ **不要试图回答「遮挡层里面有哪些按钮」**。实测官方弹窗的结构是
`_root_ > [_mask_（全屏空遮罩）, _dialog_（真内容）]` —— **按钮是遮罩的兄弟节点，
不是后代**。按后代找会得到「0 个可点元素」，而屏幕上明明有个「继续」能点。
模型真正要问的是「**现在我能点什么**」，`overlay` 因此直接列**当前没有被遮挡**的
可交互元素，这在任何 DOM 结构下都成立。

#### ⚠️⚠️ 「屏幕外」是第二类「点不到」—— 与遮挡并列，处置办法**相反**

遮挡解决的是「谁把事件吃掉了」；屏幕外解决的是「**屏幕上根本没有可点的坐标**」。
两者的表现一模一样（界面纹丝不动、`ok` 还是 `true`），但**下一步该做什么正好相反**：

| | 判据 | 现象 | 该怎么办 |
|---|---|---|---|
| 被遮挡 | `covered: true` | 事件被上层元素接收，`onTarget:false` | **先关掉挡在前面的东西**（overlay → 点它列出的那个） |
| 屏幕外 | `offscreen: true` | 事件**确实发到了目标元素上**，`onTarget:true` | **先把界面切到它所在的状态**（例如先点开抽屉） |

**真机实测的现场**：抽屉收起后，侧栏那些按钮滑到 `left:-312`。它们仍然是可见的、
没有任何东西盖着，`tap` 也老老实实把事件发到了它们身上（元素的处理器真的被调用了一次）
—— 但手指在屏幕上**没有那个坐标**，所以界面不会有任何反应。此前探针报
`onTarget:true`，模型据此认为点成功了，然后继续往下走，错得毫无痕迹。

⚠️ **`position:fixed` 的元素救不回来，别指望 `scrollIntoView`**。`clickEl()` 里本来就调了
它，但 fixed 元素不参与滚动 —— 抽屉里的侧栏按钮正是这种。所以必须如实报出来。

⚠️ **判据是「中心点」在不在视口内，不是「有没有任意一部分露着」**。两个字段都得留：

| 字段 | 问的是什么 | 谁在用 |
|---|---|---|
| `inViewport` | 有没有任意一部分露在屏幕里 | 滚动列表里「这项滚出去了」的标记 |
| `offscreen` | **中心点**在屏幕外 ⇒ 真手指点不到 | 「能不能点」的判据 |

合并成一个字段会把「滚出屏幕的列表项」与「滑出视口的侧栏」混为一谈。

⚠️ **三处判据必须一起改，漏一处就自相矛盾**（踩过，与遮挡那次同一类）：

1. `collect({clickableOnly:true})` 要同时排除 `covered` 与 `offscreen` ——
   `overlay` 的 `clickable` 用的就是这个列表，漏排会让模型去点一个不在屏幕上的按钮；
2. `tap` 要报 `offscreen:true` + `hint`，而且**必须独立成支排在遮挡判断之前** ——
   它的 `onTarget` 是 `true`，走不进 `onTarget === false` 那个分支；
3. `wait {clickable:true}` 要一并判 `offscreen` —— 否则「等到了」紧接着的 `tap` 必然空点。

⚠️ **渲染层不能把 `offscreen` 套进遮挡那套话术**。模型看到「被上层元素接收」会去查
`overlay`，方向就错了。元素行上的标记也是两个：`被遮挡←<谁>` 与 `屏幕外·点不到`。

#### ⚠️⚠️ 全屏的「面板」不是遮挡层 —— 第三类误判，处置办法**相反**

这是**同一个 `overlay()` 判据**引出的第三种错。遮挡、屏幕外、面板三者的表现都是
「点了没反应」，但**该做什么完全相反**：

| | 判据 | 该怎么办 |
|---|---|---|
| 空遮罩 | `kind: "mask"` | **关掉它**（点它列出的那个按钮） |
| 屏幕外 | `offscreen: true` | **先切到它所在的状态** |
| 面板 | `kind: "panel"` | **别关它 —— 它就是你的屏幕**，点它**里面**的按钮 |

**根因**：设置面板、右侧边栏、会话抽屉都是 `position:fixed` 的**全屏容器**，
DOM 上与弹窗遮罩**完全同构**。探针早期只看「是否覆盖视口大部分区域」，
于是把这三屏一律报成「遮挡层」，`dump` 紧接着打出一句
「先关掉它（通常是弹窗里的某个按钮）再操作」—— 模型照着去点「返回」，
**那三屏就永远进不去了**。实测现场：图谱脚本点「工作区文件」→ 被引去点「关闭」，
来回绕了两圈才回到基态。

**判别器 = 层里有没有「自己人」**。遮罩是个**空壳**，它的按钮是它的**兄弟**节点：

| 全屏层 | `interactiveInside(layer)` |
|---|---|
| `_mask_o6lrb_18`（弹窗遮罩） | **0**（按钮在 `_root_ > [_mask_, _dialog_]` 里，是遮罩的兄弟） |
| `wCInkW_panel`（设置面板） | **8** |
| `OUqwTW_panel`（右侧边栏） | **7** |

于是 `overlay()` 的返回契约从「一个布尔」扩成**三个各管一件事的字段**：

| 字段 | 问的是什么 | 谁在用 |
|---|---|---|
| `present` | 有没有一个覆盖全屏的层（**面板也算**） | 想知道「屏幕上是不是多了东西」 |
| `kind` | `"mask"`（要关）还是 `"panel"`（别关） | **调用方必须按它分支** |
| `blocking` | 它是不是真的在挡你（只有空遮罩是 `true`） | 想知道「要不要先清场」 |

⚠️ **`present` 曾经被当成「有遮挡」，代价很大**：改成面板报 `present:false` 之后，
收尾逻辑里那个「循环到 `!present`」再也关不掉右侧边栏，下一步 `tap 发送` 报
`onTarget:false` —— 而消息**其实发出去了**。所以三个字段各司其职，**不许合并**，
调用方一律**先看 `kind`**，不要拿 `present` 当遮挡判据。

⚠️ **`tap` 的 `onTarget:false` 也分两种**：`reply.panel` 有值 ⇒ 元素在当前面板
**下面**（它不在你现在的屏幕上，提示语不能说「被上层元素接收」）；否则才是真的被挡。
两种提示引向的下一步相反，混了就会把模型引去关掉本该停留的那一屏。

⚠️ **`aria` 定位必须回落查 `title`**。`dump` 的名字来自 `labelOf()`，取值口径是
`aria-label || title`；而 `locate()` 的 `aria` 分支早期只查 `[aria-label="…"]`。
于是「只有 `title`」的按钮会被 dump 报出名字、模型照着回传却**定位不到**
（症状：`找不到要点击的元素`，而它在 dump 列表里明明有）。真机上 DSH 界面
大量图标按钮只有 `title`，所以这条回落是必需的 —— **dump 报得出的名字，就要点得到**。

⚠️ **不是所有屏幕都能一次点进去**。实测会话抽屉要点**两次**「打开导航」：第一次点完
侧栏从 `x:-137` 滑到 `x:-340`（更外面，仍是关着的），第二次才真正展开
（`class` 加上 `mp-drawer-open`、`x:0`、「收起侧边栏」出现）。根因是 mobile-ui 的
时序竞态 —— 会话刚建立时官方侧栏组件还没渲染完，`mpToggleDrawer()` 那一下切换落空了。
正确姿势就是真机上该做的那套：**点 → `wait` 等特征元素 → 没等到就原地再点一次**。
⚠️ **重试必须是原地重点**：重置回基态再点一次会撞上同一个竞态，永远进不去（试过，三次全失败）。

#### ⚠️⚠️ 前端**持久化**状态 —— 「重载」不等于「回到基态」

这一条是操作法里最容易栽的：连续几屏走下来，症状全是**「找不到要点击的元素」**
（看着像探针定位写错了），实际是**页面已经不是你以为的那一屏**。三处实测：

| 被持久化的 | 后果 | 对策 |
|---|---|---|
| 右侧边栏的**开关状态** | 重载后它自己又开着，入口按钮的 `aria` 从「打开右侧边栏」变成「收起右侧边栏」 | 每屏重置后先把它**收掉**（点面板自己的关闭按钮） |
| 右侧边栏的**内容页** | 点过「工作区文件」/「新建终端」之后，再打开直接落在那个内容页上，启动页那两个入口**不再出现** | 每屏重置时点**内容页自己的「关闭」**（见下），并让启动页那一屏排在内容页各屏**之前** |
| **当前会话** | 种过会话就回不到空会话态，「选择新任务使用的 Agent 预设」**再也不渲染** | 只在空态存在的屏排在 `drawer` 之前 |

⚠️ **三者都是单向的**：一旦进入就回不去。所以**屏的顺序本身是有语义的**，
不是随手排的 —— 改顺序前先想清楚哪一屏会把状态推过去。

⚠️⚠️ **「收起右侧边栏」不重置内容页 —— 这一点踩过，是本轮图谱里最隐蔽的坑。**
实测：进了「工作区文件」之后点「收起右侧边栏」再点「打开右侧边栏」，**还是「工作区文件」**，
启动页的「新建终端」/「工作区文件」两个入口都不在，于是下一屏
`tap「新建终端」` 直接报 `ok:false 找不到要点击的元素`（看着像定位写错，其实是屏幕不对）。
**内容页自己的「关闭」才重置内容页** —— 点它侧边栏整个收起，再打开就回到启动页。

⚠️ 所以关闭按钮的优先级是 **内容页的「关闭」→ 启动页的「收起右侧边栏」→ 泛化「关闭/返回」**，
**顺序反了就会带着上一屏的内容页跑下一屏**。两条路都覆盖到了：启动页的侧边栏
**没有**「关闭」（只有分栏 / 退出全屏 / 收起右侧边栏），所以「关闭」找不到时自然落到兜底。

⚠️ **收面板必须点面板自己的关闭按钮，不是去「关掉面板」**（面板是屏幕不是遮挡，
见上一节）。而且按钮要**按优先级挑**：右侧边栏里同时有「退出全屏」「收起右侧边栏」
「关闭」，拿第一个正则命中的就用会点到「退出全屏」—— 它只切换全屏态，面板照旧开着，
下一轮又拿到同一个面板，**绕圈**。

⚠️ **`expect` 必须是该屏独有的元素 —— 否则「其实没进去」会被判成通过。**
实测：「终端」屏原来拿「新标签页」当判据，可**工作区文件屏也有它**，于是
`tap「新建终端」` 明明报 `ok:false`（根本没点着），复核却因为「新标签页在」而显示 ✅。
终端屏独有的标签是当前 Shell 的名字（默认 `cmd.exe`）。
**通用判据**：返回 / 关闭 / 新标签页 这类多屏共有的词一律不能当 `expect`。

⚠️ **收起是带动画的**（侧栏滑出），动画没落定就点「打开右侧边栏」，按钮还在过渡态
（`aria` 仍是「收起右侧边栏」）→ 又是「找不到要点击的元素」。实测「右侧边栏」屏
就是这么挂的，等 1.6 s 才稳。

#### ⚠️⚠️ `execCommand` 写 contenteditable 是**异步生效**的

官方 composer 是 **Lexical**。实测时间线（这是量了两轮才搞清的）：

```
t=0ms    调用 execCommand('insertText') → 返回 true
t=0ms    同步读 textContent / innerText → **都还是空的**
t=700ms  再读 → 内容已落地（<p><span data-lexical-text>…</span></p>）
```

所以**同步读必然为空**，拿「同步读 == 期望值」当成功判据会**每一次都误判失败**，
然后走 `el.textContent = s` 兜底 —— 而那个兜底会把 Lexical 精心维护的 `<p><span>`
结构拍成裸文本，破坏编辑器内部模型。表面上「内容进去了」，实际是两条路在写同一个元素。

正确做法：**`execCommand` 返回 true 就相信它**，不做同步覆盖；`textContent` 只作
「`execCommand` 不可用/抛异常」时的兜底。

`type` 的 `applied` 字段因此是**三态**，别当布尔用：

| 值 | 含义 |
|---|---|
| `true` | 同步已确认写入 |
| `null` | 写入已发出、**异步生效中**（contenteditable 就是这样）—— **不是失败** |
| `false` | 真的没写进去 |

⚠️ 校验与赋值必须用**同一个量**：赋值走 `textContent`、校验却用 `innerText`
（布局相关，元素未布局时返回空串）会出现「刚写进去、校验说不相等」的假失败。
判据用「**包含**」而不是严格相等 —— 富文本会把文本包进 `<p><span>`。

#### ⚠️ 验收判据必须是「业务状态真的变了」

拿错判据会让人去修一个**根本没坏**的东西。两个反例都实测踩过：

| 错误判据 | 为什么无效 |
|---|---|
| `document.querySelectorAll('*').length` 变化 | 抽屉靠 CSS `transform`/`opacity` 显隐，**节点数一个都不增** |
| `.mp-drawer-mask` 存在/可见 | 它是 `mpMask()` **懒建且常驻**的，建完就不删；存在 ≠ 抽屉开着 |

而「点汉堡展开抽屉」在**空会话页（Hero）根本不是有效判据**：此时官方侧栏尚未渲染
（`.pI_x6G_sidebarCol` 不存在），`mpToggleDrawer()` 里 `mpOfficialToggle()` 返回
`null`、`if (btn) btn.click()` 不执行 → 点汉堡本来就没反应。那是 mobile-ui 插件在
Hero 页的缺陷（见 `client.js` 修复 31 的注释），探针如实报 `onTarget:true` **没有说谎**。

**有效判据**（`tools/ui-map-real-frontend.mjs` 用的就是这条）：走 composer 全链路 ——
`type` 写入 → **发送按钮从 `disabled:true` 变 `false`**（证明 React 真的认账了）→
`tap` 发送 → **消息文本出现在界面上**。三步任何一步失败都说明探针没真的驱动到应用。

#### 怎么用（模型的正确姿势）

**先查图谱，别现猜坐标。** `tools\ui-map-real-frontend.json` 里 17 屏的每个按钮都
记了 CSS 中心坐标与 `sel`，这是「按钮在哪」的权威答案；真机触控要的屏幕物理坐标
由探针按当前视口现算（见「坐标系」一节），别拿图谱里的 CSS 值直接去 `input tap`。

**第 0 步永远是「清场」**：`{"action":"overlay"}` 看有没有遮挡层。有就先点掉它，
再 `overlay` 一次确认没了 —— 官方首启向导是**多步**的，只关一次会停在第二个弹窗上。

```
overlay → kind:'mask' 吗？→ 是就点「此刻能点的」里那个按钮 → 再看 overlay → 直到没有 mask
```

⚠️ **循环的终止条件是 `kind !== 'mask'`，不是 `present:false`。** `present` 的语义是
「有没有全屏层」，**面板也算**（设置面板、右侧边栏、会话抽屉都是全屏层）。照
`present:false` 收尾会去关掉一个**本来就是你当前屏幕**的面板，那几屏就再也进不去了
（见上一节「全屏的『面板』不是遮挡层」）。

然后才是正常流程：

1. `{"action":"dump"}` 先看界面上有什么（返回每个元素的位置、文本、选择器、`covered`）；
2. 用 `sel`（首选，从刚才的 dump 里复制）或 `text` / `aria` / `testid` 定位；
   ⚠️ `aria` 会自动回落到 `title`，所以 dump 报得出的名字就点得到；
3. `{"action":"tap","text":"发送"}`；**`covered:true` 的元素别点**，先处理遮挡；
   `offscreen:true` 的也别点 —— 它没有屏幕坐标（处置办法与遮挡相反，见「屏幕外」一节）；
4. 界面变化是异步的 —— 接着 `{"action":"wait","text":"确认","clickable":true}` 等面板出现；
5. **永远先看 `ok` 字段**，`ok:false` 要如实报失败，不能假设点成功了。
   ⚠️ 但 `ok:true` **不等于**点到了东西 —— 点了个没反应的按钮同样返回 `ok:true`，
   所以每步之后要**用一个只属于目标屏的特征元素做判据**（`wait` 它出现），
   这就是图谱脚本里 `expect` 字段的作用。

其它动作：`type`（写输入框，`enter:true` 附带回车）、`key`（发按键）、
`scroll`（`to:"bottom"` 跳到底）、`text`（读可见文本）、`info`（一眼看当前界面）、
`find` / `html`（看单个元素）、`ping`（探针是否活着）、`reload`（界面白了就重载）。
`dump` 可加 `clickableOnly:true` 只看现在真能点的。

#### ⚡ 一键到屏：`map` / `where` / `goto`（模型的正确姿势）

**别再手点着导航 —— 用三个内建动作。** 图谱（`tools\ui-map-real-frontend.json`）已经
被**编成插件的一部分**（`lib/screens.js`，由 `tools\build-uiagent-screens.mjs` 从图谱
生成），所以模型不需要读那个 400 KB 的 JSON，也不需要猜自己在哪：

| 动作 | 干什么 |
|---|---|
| `{"action":"map"}` | 列出 17 屏的 id、标题、路线。加 `detail:true` 连每屏的按钮一起列 |
| `{"action":"where"}` | **回答「我现在在哪一屏」** —— 拿 17 屏的判据逐个比对实时界面，命中即为答案 |
| `{"action":"goto","screen":"特权权限"}` | 一条命令开到那一屏，返回过程与是否到达 |

`screen` 接受屏 id（`settings-priv`）、完整标题、或标题子串（`特权权限`）。

标准循环：

```
{action:"where"} → {action:"goto",screen:"..."} → {action:"dump"} → {action:"tap",...}
先弄清在哪          再开到目标屏              读实时位置          动手
```

⚠️ **`goto` 之所以必要，是因为这个前端「进去了就回不去」。** 它持久化三样**单向**的
状态：会话抽屉的开关、右侧边栏的开关**及其内容页**、以及当前会话。一旦点过
`新建终端` 或种过会话，纯 `tap` 序列**永远回不到**空会话屏（见「前端持久化状态」一节）。
`goto` 的第一件事是 `reload` 回基态，那是唯一可靠的回退手段。

`goto` 的内部顺序（照抄图谱生成器的策略，一字不差）：

```
reload → 等探针重新装填 → 关遮挡层（kind=='mask'）→ 收面板（kind=='panel'）→ 沿路线 tap → 用判据确认
```

两处**不能改**的细节：

- **清场按 `kind` 判，不是 `present`** —— `present` 把面板也算成覆盖层，照它循环会
  关掉**当前屏幕本身**（与「清场」那一节的 ⚠️ 同源）。
- **等不到判据时是原地重点，不是重载重来** —— 抽屉要点两次是 mobile-ui 的时序竞态，
  重载再点会撞上同一个竞态（图谱生成器实测三次全失败）。重试上限 3 次。

第三个动作 `where` 有一条性能上的硬约束：**它用 `find` 探测，不能用 `wait`。**
原生 `runWait()` 会一直轮询到 **10 秒**上限、且**忽略**调用方传的 `timeout`
（`UiProbeBridge.kt:390` 只用 `WAIT_MAX_MS`）。17 屏 × 10 秒 = 170 秒，而 `find` 是
单次检查、秒级完成。代价是 `wait` 代劳的 `clickable` 过滤要**在插件侧自己做**
（`describe()` 回传的 `covered`/`offscreen` 就是判据，照 `ui-probe.js:1159` 那行写）——
漏掉这一层，`hero` 的判据会在**任何**一屏「成立」，因为那个 composer 占位符 12 屏都有、
只是其中 11 屏被盖住了。

⚠️ **三处判据曾静默失效，已修并加了机械审计。** 判据（`expect`）有两个用途：图谱
生成时校验「真的进去了」，运行时 `where`/`goto` 判断在哪。撞车时**两个用途同时失效，
且症状都是静默的** —— 图谱那屏「假通过」，`goto`「假到达」（模型于是对着错误界面点
按钮）。本轮实测抓到 4 处：

| 屏 | 原判据 | 为什么撞车 |
|---|---|---|
| `hero` | `打开导航` | mobile-ui 的常驻悬浮按钮，**17 屏全有** |
| `model-picker` | `推理等级` | 子串命中了每屏都在的长文案「…，推理等级 High」 |
| `settings-priv` | `重新检测` | `设置 › 悬浮气泡` 那屏也有一个「重新检测」 |
| `preset-picker` | `极简模式` | 子串命中了 `设置 › Agent 预设` 的「设为新任务默认: 极简模式」 |

**光靠肉眼审不出来**（本轮就先看漏了两处）。所以固化成：

```powershell
node tools\ui-map-expect-audit.mjs    # 17 屏判据唯一性，退出码非零 = 有撞车
```

它**复刻探针的匹配语义**（先精确、再子串取第一个、候选集是**全部**元素不过滤遮挡、
`clickable:true` 才附加遮挡/屏幕外条件），并对**全部元素**跑一遍 —— 与探针口径不一致
的话结论会反。改完判据要**重跑图谱**让 JSON 跟上，再跑
`node tools\build-uiagent-screens.mjs` 让插件的内建表跟上。

##### ⚠️⚠️ 判据里**绝不能出现用户数据** —— 三类根因必须分开治

真机审计 17 屏时报出 29 处失败。逐条查下来，它们是**三种性质完全不同**的东西，
混在一起报会得出错误结论（第一版审计就把三类混成「85 个坐标偏移」）：

| 类 | 现象 | 是缺陷吗 | 治法 |
|---|---|---|---|
| ① **用户数据** | 表里记着 `推理等级 High`，真机上是 `Default` | **不是** —— 表**本来就不该记** | 换掉判据，只留稳定前缀 |
| ② **动态文案** | `已选 3 个`、`第 2 个`、`当前：完全权限` | **是** | 只用不含状态词的前缀 |
| ③ **布局随内容变** | 会话标题长短不同 ⇒ 行高、中心坐标不同 | **不是** —— 坐标只是参考 | 从坐标断言里排除 |

**核心结论：图谱是「结构地图」，不是「快照」。** 凡是「这台机器上恰好是什么值」
的东西（模型名、推理等级、权限、主题、字号、会话标题）一律不能进 `expect`，
也不能进 `els`。

本轮抓到并修掉的两处**真例**（都是「判据只在生成时那台机器上成立」）：

| 屏 | 原判据 | 为什么坏 | 改成 |
|---|---|---|---|
| `model-picker` | `{text:'推理等级 High', exact:true}` | 那是**生成机器上**的配置；真机是 `Default` | `{prefix:'模型 '}` |
| `terminal` | `{text:'cmd.exe'}` | 生成机器是 Windows，真机是 Android 的 `login` | `{sel:'.xterm-screen'}` |

`terminal` 那处顺带立了一条可复用的判据：**xterm.js 这类第三方库的类名
（`xterm-screen` / `xterm-viewport` / `xterm-rows`）跨构建、跨平台、跨用户都稳定**，
而 DSH 自己的 CSS Modules 类名（`_2H3hWW_newSession` vs `hHd-Xa_newSession`）
**每次构建都变**。所以非交互的结构锚点优先用第三方类名，别用自家 hash。

**机械判据（已固化进 `build-uiagent-screens.mjs`，违反即拒绝打包）**：

- `els` 里含**中文引号 `“”`** 的元素一律丢掉 —— 那是前端嵌用户数据的模板
  （`会话“<标题>”的操作`、`在“<工作区名>”中新建会话`）。已全图谱扫过**无反例**。
- `expect` / `enter` 里含中文引号 ⇒ **直接报错退出**（比 `els` 里含更严重：
  判据失效会让 `goto` 永远进不去那一屏）。
- 丢掉的条数会**打印出来** —— 静默丢弃等于看不见的损失。

##### ⚠️⚠️ 真机采集：两个会**改动用户状态**的坑（都已堵住）

在真机上跑采集/审计时，「清一个 localStorage 键」**不是无害操作** ——
前端有兜底行为，清了 A 会生出 B。两个坑都实际踩到过：

**坑一：清 `dsh.sessions.current` ⇒ 前端自己新建一个空会话。**

`dsh-client-ui-workspace/lib/client.js` 的 `reuseOrCreateBlank()`（L800）先找
「blank 且 cwd 匹配且未归档」的会话复用（L806），**一个都找不到就 L808
`this.sessions.create(...)` 新建一个**。所以「清掉 → 重载」得到的不一定是空态，
而是「前端挑一个 / 造一个」。实测后果：跑完全量审计后用户的 `current` 从他自己
的会话变成了一个新建的空会话（`blank:true` / `turns:0`），**界面停在新会话页上**。

三道防护（缺一不可，都在 `ui-map-real-frontend.mjs`）：
1. 每屏重置时**先把 `current` 写回原始值**再动别的键（让「造一个」的前提不成立）；
2. 空会话态那三屏（`hero`/`access-mode`/`preset-picker`）**跑完立刻**写回 `current`；
3. 收尾**逐键回读核对** + 拿「跑之前的会话 id 集合」做差集，把**本次新增的 blank
   会话**逐个 `workspace/archiveSession` 归档掉（归档是官方隐藏手段，不删数据）。

⚠️ 归档的 RPC 路径是 **`/api/workspace/archiveSession`**，**不是** `session/archive`
—— 归档属于 workspace 命名空间。路径猜错时引擎返回的是**纯文本 `not found`**
（路由兜底）而非 JSON 错误，症状是 `Unexpected token 'o', "not found" is not valid JSON`。
⚠️ `payload` 外面必须再套一层 `args`（`payload:{args:{request:{…}}}`）；少这层
`session/list` 会返回**空列表**（不报错），实测踩到过。

**坑二：`seedSession()` 会真发一条消息进用户的会话日志。**

`drawer` 屏需要「有会话」才开得出来，PC 上的做法是发一条「图谱种会话」。
但真机上用户的 `current` **本来就指向活跃会话**，`resetToBase()` 一还原就是会话态，
那条消息纯属多余 —— 而它会**永久留在用户的会话记录里**，用户翻会话就能看到。
真机改用 `ensureSessionState()`：**只断言在会话态，不写任何东西**。

**判据**：任何采集/审计脚本，跑完必须能回答「用户解锁手机后看到的是不是原样」。
回答不了就是没做完 —— 这不是可选的收尾。

##### ⚠️ `offscreen` 是**警告**，不是失败条件

图谱生成器曾把 `tap` 的 `offscreen === true` 也算进入失败，结果真机上**10 屏全卡**
（9 个设置屏的第一步「设置」+ `plugins-panel` 的「插件」）。但实测证据是反的：

```
tap「设置」 ok=true onTarget=true offscreen=true
校验: ✅ 已进入（找 「通用设置」）        ← find 判据成立
共 49 个可交互元素 … 返回/通用设置/模型/内置插件/…   ← dump 里面板元素全在
```

**面板真的开了。** 根因：**探针的 `tap` 是程序化派发**（`el.click()`），不是真手指。
官方侧栏收起时被 translate 到 `x:-312`，里面那个「设置」的中心点落在视口外
（`offscreen:true`），但它在 DOM 里、`onClick` 照常触发。

所以 `offscreen` 只说明「**真手指**点不到」，而本工具链从来不靠真手指。
**判定权交给 `verified`（探针 `find` 复核）** —— 离屏 + 判据成立 = 这条路走得通，照记；
离屏 + 判据不成立 = 交给重试逻辑。

⚠️ 但 `offscreen` 这个事实要**记进图谱**（`step.offscreen`）：读的人得知道这一步
不是「屏幕上看得见再点」，真机手动操作时得先把它滚进视口。

⚠️ 这条与「真机审计只点 `enter`、不点 `els`」是配套的：`els` 里那些离屏按钮
**模型不该去点**（点了就改用户状态），它们不进内建表；而 `enter` 是**路线**，
必须能走通，所以判据要放宽到「程序化点击是否生效」。

#### PC 侧自动化验收（不需要真机）

设备上装的是别处签名的包、无法 `install -r`（会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
而卸载会清掉 `filesDir` 下的整个 Termux 环境与 `$DSH_HOME`。所以验收走
**真实 Chrome + 真实 DOM**（`--headless=new` + CDP，Node 24 自带 `WebSocket`，零依赖）：

```powershell
node tools\ui-probe-verify.mjs            # 探针行为，107 项断言（真实 DOM，含 React 受控输入模拟）
node tools\uiagent-bridge-verify.mjs      # 插件↔原生信箱契约，120 项断言（真插件代码 + 假原生对端）
node tools\ui-map-expect-audit.mjs        # 17 屏判据唯一性（判据撞车 = goto 假到达）
node tools\ui-map-real-frontend.mjs "<带 token 的 URL>"   # 真实官方前端端到端，17 屏图谱 + 交互实测
node tools\build-uiagent-screens.mjs      # 图谱 → 插件内建屏幕表 lib/screens.js
```

⚠️ **`uiagent-bridge-verify.mjs` 的 §⑪ 用引擎自己的断言函数验注册契约。**
`UIAGENT_ENGINE_DIR` 指到引擎的 `node_modules\@deepseek-ai\dsh-tools` 时，
它 import 引擎导出的 `assertSupportedJsonSchema` / `validateJsonSchemaValue`
来判 `output.schema`，而不是自己再写一份 —— 自己写的那份会把引擎的约束
写松，于是「本机绿、真机炸」。未设该变量时跳过并打印提示。

这条是**血换来的**：`ui` 工具曾经**从未注册成功过**，而当时 111 条断言全绿。

##### ⚠️⚠️ 工具注册的硬校验：`output` 声明（缺了整条工具是死的）

引擎的 `ctx.tools.register` 有一道**硬校验**（`dsh-tools/lib/index.js:2881`）：
`output` 必须是对象、`render` 必须是函数，随后还要过 `assertSupportedJsonSchema`。
**缺这一块不是降级而是抛错**：

```
[engine] dsh-android-uiagent (.../lib/index.js): TypeError:
         tool "ui" must declare output { schema, render, presentationMeta? }
[engine] dsh: warning: 1 entry did not activate
```

后果是**工具从未出现在模型的工具列表里** —— 模型看不到、调不到，而插件本身
「加载成功」的日志照常打印。`ui` 工具就是这样哑了整整一段时间，日志里从来没有
`uiagent` / `ui-probe` 的任何一行，原因就在这里。

⚠️ **为什么 111 条 PC 断言全绿却漏了它**：那些断言直接调 `tool.execute()`，
**绕过了 `register`**。凡是「引擎在注册期校验的东西」，直接调 `execute` 都验不到。
所以 §⑪ 改为 import **引擎自己的** `assertSupportedJsonSchema` /
`validateJsonSchemaValue` 来判，而不是自己再写一份（自己写的会把约束写松）。

⚠️ `output.schema` 里 **`details` 必须留空 schema**：它的形状随 action 变
（`dump` 是数组、`tap` 是元素对象、`wait` 带 `satisfied`…），收窄成固定形状会在
真机上以 `ToolOutputError` 的形式炸在渲染之前。引擎接受的子集是
`type/oneOf/properties/required/additionalProperties/items/enum/const` + 注解，
**空 schema `{}` 是合法的**，正是为这种「形状不定」准备的。

⚠️ **为什么本插件手工构造 `output` 而不用 `defineTool`**：`bubble-tool` /
`root-tool` / `shell-tool` / 本插件都住在 `$DSH_HOME/<tool>/` 下，那里**没有
`node_modules`**，`import { defineTool }` 解析不到。手工构造是这个布局的必然选择。

⚠️ **`uiagent-bridge-verify.mjs` 的假原生必须「会演」**：它维护一个「当前在哪一屏」，
`find`/`wait` **只在当前那一屏**上判判据。第一版写成了「逐屏比对」（拿每一屏自己的
判据比对传来的判据），于是**每一屏都命中** —— `where` 报「17 屏同时成立」、`goto` 的
「是否已在该屏」永远为真而直接从不到重载那步，8 条断言集体失败。真机上的探针只在
**当前 DOM** 上查，所以「当前屏」才是正确的模型形状。

图谱结果落在 `tools\ui-map-real-frontend.json`：每屏列出每个按钮的 **CSS 中心坐标 +
`sel`**，外加 `⛔被遮挡` / `📴屏幕外` 标记。**「按钮在哪」不用猜，查这个文件。**

⚠️ **图谱是快照，会随官方前端更新漂**。前端升级后重新跑一遍即可，不用手工维护。

**这一跳为什么单列一个脚本验**：整条链路是三跳的 ——

```
模型 ──▶ 插件 ──ui-cmd.json──▶ 原生 Kotlin ──▶ 页面探针
          ◀──ui-result.json──┘
```

页面探针有 `ui-probe-verify.mjs`、真机有 logcat，**只有中间这一跳既不在页面里、
也不需要真机** —— 纯 Node + 文件系统，所以能在本机**用真的插件代码**驱动，只需
一个假的原生对端（只实现「读 ui-cmd → 写 ui-result」那一侧）。

它验的是四件**在真机上只表现为「偶发、看起来随机」**的事，这类 bug 最难查：

| 验什么 | 为什么必须验 |
|---|---|
| `id` 配对 | 上一轮遗留的回执会被当成本轮答案（`sendCommand` 开头 unlink 旧回执就是防这个） |
| 串行化 | 原生只有一个轮询线程 + 一个命令文件；并发写会互相覆盖 ⇒ 前一条**从未执行**，界面没动而模型以为点了 |
| 超时不许返回 ok | 超时被当成成功同样让模型以为点了（这条与遮挡识别是同一类错误的不同来源） |
| 原子写 | 原生读到写了一半的 JSON（`writeJsonAtomic` 先临时文件再 rename） |

另外顺带钉住渲染层的两条**契约**（都是踩过的）：

- **遮挡警告必须排在元素清单之前** —— 模型是逐行扫清单、扫到就开始点的；
  警告跟在后面等于没有。断言直接比两个子串的下标先后。
- **`type` 的 `applied` 是三态**，断言必须分别验 `true`/`null`/`false` 三种渲染 ——
  按布尔断言会把 `null`（异步生效，**正常路径**）误报成失败。

⚠️ 没装 `DSH_HOME` 时**不该注册工具**（前置条件不满足还给模型一个永远失败的工具，
比没有这个工具更糟），这条也在断言里。

`ui-map-real-frontend.mjs` 需要一份**独立实例**（别碰用户正在用的那个 GUI）：

```powershell
# ⚠️ --patch 是**启动器**参数，必须在子命令**之前**；且必须配 --profile
dsh.cmd --profile web --patch "<隔离目录>\mobile-ui.patch.yml" --no-open --port 31999
node tools\ui-map-real-frontend.mjs "http://127.0.0.1:31999/?token=..."
```

| 覆盖 | 判据 |
|---|---|
| `dump` / `find` | 找到 React 的 `role=button`、CSS 中心坐标、`includeHidden`、`filter`、`limit` |
| `tap` | 五个事件按序派发 + **页面计数器真的 +1** + 遮挡识别 |
| **遮挡识别** | `covered`/`blockedBy`、`dump` 顶层 `overlay`+`warning`+`blocked`、`clickableOnly` 过滤、`overlay` 动作的 `present`/`layer`/`clickable`、`wait clickable:true` 不把被遮挡的算作等到了、遮罩移除后全部复位 |
| `type` | **React 的 onChange 真的被触发、拿到的值正确**（模拟实例级 value 拦截器） |
| `scroll` / `key` / `text` / `info` / `html` | 容器真的滚动、按键送达、文本可读 |
| 健壮性 | 未知 action 不崩、返回值可 JSON 序列化、**不污染页面**、幂等装填 |

⚠️ 这套 PC 侧验收的价值已经**三次**兑现：`onTarget` 判据恒为 true、`overlay` 单点采样
自相矛盾、`execCommand` 异步生效被误判成失败 —— 三个都是**真机上只会表现为
「AI 说点过了但界面没动」**的缺陷，在这里被断言抓出来。

#### ⚠️ 并发调用必须串行化

原生只有**一个**轮询线程 + **一个**命令文件。两条命令同时写进去，后一条**覆盖**前一条：

- 前一条命令从未被执行（模型以为点了，界面没动）；
- 它的 `id` 再也等不到回执，`sendCommand` 一路超时到 20 秒；
- 后一条的结果可能被前一条按 `id` 判为「不是给我的」而丢弃。

症状是「偶发、看起来随机」。插件侧因此有一个 `enqueue()` 串行闸门（模型确实会
同一轮并行发起多个工具调用）。⚠️ 闸门用 `.then(task, task)` 而不是 `.then(task)` ——
前一个任务抛异常时也必须放行，否则一次失败会**永久卡死**后续所有调用。

#### `text` 与 `value` 是两种意思（踩过）

| 字段 | 含义 |
|---|---|
| `text` | **按可见标签定位**元素（tap/find/type/wait 共用） |
| `value` | 往输入框里**写的内容**（仅 `type`） |

⚠️ 第一版在插件里做了 `text` ↔ `value` 的翻译，于是 `tap{text:"发送"}` 的定位条件被
当成「输入内容」处理、**转手就把 `text` 删了** —— 所有按文本定位的 tap/find/wait
全部报「找不到元素」，而报错文案完全指不到真因。现在两边字段名一致、**原样透传**，
中间没有任何翻译层，也就没有再搞错的机会。

#### ⚠️ `ok:true` 不表达「点着了」—— 渲染层必须主动报 `onTarget`

`tap` 有三种「执行成功但没点着」的情形，**三种都返回 `ok:true`**：

| 情形 | 探针字段 | 后果 |
|---|---|---|
| 被别的元素盖住 | `onTarget:false` + `hitTopmost` | 真机手指点到的是那层 |
| 中心点在视口外 | `onTarget:true` + `offscreen:true` | 事件发了，但屏幕上没有可点的坐标 |
| 在面板**下面** | `onTarget:false` + `panel` | 它根本不在当前屏幕上 |

所以 `ok:true` 与「界面会不会动」**没有关系**。渲染层如果只报一句「✓ 已点击」，
模型无从判断该不该信这次点击 —— 真机上就出现过点完直接往下走、而界面纹丝不动。

**三条渲染规则**（都在 `uiagent-bridge-verify.mjs` ⑦.6x 里有断言）：

1. `onTarget === true` **且非 `offscreen`** 时，必须给一句正向确认
   （「事件已送达目标元素本身（onTarget:true）」）。⚠️ 措辞刻意是**送达**而不是
   「点击生效」：事件送达 ≠ 界面响应，后者要靠下一步 `dump` 看，写成「生效」会让
   模型跳过复核。
2. `offscreen` **独立成支且排在最前** —— 它的 `onTarget` 是 `true`，走不进
   `onTarget === false` 的分支，而它恰恰是「点了没反应」的第二种成因。
3. `onTarget:false` 还要再分**两态**：有 `hitTopmost` 才是「被上层接收」；
   `hitTopmost` 为空时**根本没有上层元素**（事件落到了空白处，多半是元素在点击瞬间
   被重渲染掉），说成「被遮挡」会把模型引去查 `overlay` 白费一轮。

#### ⚠️ `clear:true` 对 `contenteditable` **不能**靠写空串实现

官方 composer 是 Lexical（`contenteditable`）。旧实现走 `setValue(inp, '')`，
而**「写入空字符串」这个操作根本不存在**。真机实测（CDY-TN90 / Android 10 /
系统 WebView 114）的候选表：

| 写法 | 结果 |
|---|---|
| `insertText('')` | **no-op**（返回 `true`，内容一字不变） |
| 全选 + `delete` | **no-op** |
| 全选 + `insertText('\n')` | **no-op** |
| 全选 + `insertText(非空)` | ✅ 整体替换 |
| 全选 + 派发 `Backspace` `keydown` | 只删**一个**字符（DOM selection 不被 Lexical 认） |
| ✅ 全选 + `insertText('X')` → 派发 `Backspace` | **清空成功** |

做法是 `clearEditable()`：`selectAllIn()`（每次都要**重建** Range，`execCommand`
会消耗掉它）→ `insertText('X')` → 派发 `Backspace`。失败则退回
`el.textContent = ''` + `input` 事件。回执里带 `cleared` 字段说明走的哪条路，
渲染层要报出来 —— 否则模型分不清「clear 生效了」与「clear 被忽略了」。

⚠️ 症状很有欺骗性：旧内容留下、新内容**追加在后面**，而回执里的 `value` 是一长串
拼接结果，模型完全看不出是自己没清掉。

#### ⚠️ 别用 `text` 定位 composer —— 它的 `text` 是 placeholder

composer 的 `text` 字段是 **placeholder**（「发消息或创建任务, / 调用指令, @ 文件或对话」），
**前端改文案就会失配**（实测已从「描述你想要构建的内容…」变过一次），
报错是「找不到输入元素」，完全指不到真因。

`role:"textbox"` 来自 `role="textbox"`，**稳定**。所以 `locate()` 里加了 `role`
分支，但它排在 `sel`/`testid`/`aria`/`text` **之后** —— 同 role 的元素可能并存，
`sel`/`aria` 必须优先。

#### ⚠️ 验证判据必须取自 **DOM**，不能取自模型的回复

真机写动作端到端（`tools/uiagent-device-write.mjs`）第一版让模型「原样贴出 tap 回执」，
它**只抄了 `fmtElement` 那一行**、把 `onTarget` 那行省了 —— 拿回复当证据会得出
「渲染层没生效」的错误结论，而真相是模型没抄。

工具结果在聊天界面里是**渲染成 DOM** 的，DOM 里的文本就是模型实际收到的 `message`。
所以判据是「在页面世界里找含关键字的最深元素、读它的 `innerText`」。

`uiagent-device-write.mjs` 的分工（三个脚本互补，缺一个都推不出「能操作 App」）：

| 脚本 | 验的是 |
|---|---|
| `ui-probe-device-check.mjs` | 探针在真机内核上能跑（**只读**） |
| `uiagent-device-e2e.mjs` | 插件↔原生信箱通、坐标换算对（**只读 dump**） |
| `uiagent-device-write.mjs` | **写动作**：模型点一下，界面真的变了 |

⚠️ 前两个只调 `dump`，而「让 AI 操作 App」的成败**全在写动作上**，只读全绿推不出
「点得动」。该脚本的**界面判据**是「侧栏「设置」按钮的 CSS x 从 -190 变成 140」
（收起时是负值，滑入后为正）—— 不信回执，直接量界面；且**全程可逆**，
结束时恢复原状并核对（测试不能把用户的界面改掉）。

⚠️ 探针版本号在 `ui-probe-device-check.mjs` 里**从源码正则解析**，不写死。
写死的后果是「改探针必须记得改这里」，而漏改的表现是「脚本报失败、真机一切正常」，
最容易被误判成资产没生效。

