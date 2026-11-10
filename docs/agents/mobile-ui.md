# 移动端 UI 插件

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 当前职责与构建

基础布局位于 `dsh-android-mobile`，设置、宿主状态连接、余额分别由 `dsh-android-mobile-settings`、`dsh-android-host-bridge`、`dsh-android-balance` 负责。布局不等待账户服务；各功能独立注册并释放资源，特权 HTTP 路由归设置宿主。

插件统一从组件清单生成 zip 与摘要 manifest，客户端目标为 Chrome 73，随 APK 出厂并通过市场直接更新。不要手工压缩或重跑旧 mobileui 覆盖流程，命令和状态归属见 [内置插件与市场更新](plugin-updates.md)。

## 历史 overlay 路径与既有布局约束

以下保留旧版驱逐问题和布局适配的排障依据；其中手工打包、file overlay 与旧安装路径已由标准 bundle 迁移替代。

源码在 `mobile-ui-plugin/dsh-android-mobile/`，打包成 `assets/mobile-ui.zip`。
`EngineManager.ensureMobileUi()` 解包到 `$DSH_HOME/mobile-ui/dsh-android-mobile/`，
`writeMobileUiPatch()` 生成 overlay（`name` 用 `file://` 绝对 URL）并作为 `--patch`
传给引擎。

```powershell
# 改完源码重新打包（注意：条目必须是 lib/client.js 这种相对路径）
Compress-Archive -Path mobile-ui-plugin\dsh-android-mobile\* `
                 -DestinationPath app\src\main\assets\mobile-ui.zip -Force
# 然后改 EngineManager.MOBILE_UI_VERSION
```

⚠️ `ensureMobileUi()` 按 `entry.name` 直接解到目标目录。若压缩时把顶层目录也带进去
（变成 `dsh-android-mobile/lib/client.js`），会解成嵌套两层，插件找不到 → 静默退回桌面布局。

### ⚠️ 为什么解到 `$DSH_HOME/mobile-ui/` 而不是 profile 的 `node_modules`（踩过的大坑）

**症状**：界面看起来"插件装了一半"——侧栏不再是抽屉，而是常驻 56px 图标条（正文只剩
304px），工具卡片/执行过程里的长文本**左右两端都被裁出屏幕**，但左上角汉堡按钮还在、
DOM 里也有 `.mp-nav-btn` / `.mp-drawer-mask`。**没有任何报错。**

**根因**：插件原先解到 `profiles/web/node_modules/dsh-android-mobile`，而该包**不在**
profile 的 `dependencies` 里（写进去会让 DSH 去 registry 拉这个本地包）。于是
**用户每用插件管理器装一次插件，pnpm 就把它当"多余依赖"挪进 `node_modules/.ignored/`**。
包一挪走，overlay 里按包名解析的 `name` 找不到模块 → 客户端半不编入浏览器 →
`injectStyles()` 注入的 `<style>` 随插件卸载被移除 → **全部 53 条 `[data-mobile]`
规则一起消失**，静默退回桌面布局。

**为什么难查**：`[data-mobile]` 属性还在（`mpSyncMobileAttr` 照常跑），残留的 DOM
元素也在，只有 CSS 没了——所有 `position` 都退回 `static`。用 CDP 数一下
`document.styleSheets` 里 `[data-mobile]` 规则的条数就能一眼看出（正常 53，坏掉 0）：

```js
let n=0; for (const sh of document.styleSheets){let rs;try{rs=sh.cssRules}catch(e){continue}
  const w=a=>{for(const x of a){if(x.cssRules&&!x.selectorText){w(x.cssRules);continue}
    if(x.selectorText&&/\[data-mobile\]/.test(x.selectorText))n++}};w(rs)} n  // 期望 53
```

**修法**：把包放到 pnpm 管不到的地方（`$DSH_HOME/mobile-ui/`），overlay 里用
`file://` 绝对 URL 挂载 —— `dsh-client-modules.locatePkgJson()` 显式支持 pathLike
说明符（`.` / `file:` / 绝对路径），会从模块位置向上找最近的 `package.json` 认作该包。
`ensureMobileUi()` 同时会清掉 `node_modules` 下的旧位置。

**为什么走 `--patch` 而不改 profile 的 `cordis.patch.yml`**：后者归 DSH 初始化并可能承载
用户配置，覆写会丢东西；`--patch` 层在 profile 层之后应用、优先级更高，且完全由本 App 拥有。

**为什么不写进 profile 的 `package.json` dependencies**：那会让 DSH 试图从 registry 拉这个
本地包。桌面端同样刻意不写。

**插件依赖的 CSS Module hash 与引擎版本强绑定**（如 `pI_x6G`=layout、`wSkVaW`=conversation）。
插件内置「别名桥接」在运行时从稳定局部名反推当前 hash，升级 DSH 后多数规则能自动复活，
但**不是所有**——见下。

### 别名桥接的判据（改过，别退回去）

`mpDeriveAliasMap()` 只认**命中 ≥2** 的映射（`if (hit < 2) continue`）。

原先有一条 `|| locals.length === 1` 豁免（单局部名的旧 hash 凭 1 票即可采纳），它在
`Nqubda: ["panel"]` 上炸了：`panel` 太通用，官方右侧栏的 dockkit 面板（`P3OORG_panel`）
也用它，于是桥接把 `Nqubda_panel` 补到了**那个面板**上，而审批浮层规则
`[data-mobile] .Nqubda_panel` 随即把它变成 `position:fixed` 浮层盖住上半屏——用户看到
半屏白块，连「添加一个 API Key」对话框都被压住。

同类隐患还有 `zGbnIq: ["title"]`。**加豁免前先确认该局部名是否唯一。**

### ⚠️ 别名桥接会误认领无关 hash（改过两次，别退回去）

**症状**：「执行了命令」这类**分组头被居中**（本该左对齐）。真机实测 53 个
`.O_Ebla_root` 里有 2 个中招、其余正常 —— **同一页面上有的错有的对**。

**根因**：`MP_LEGACY_HASHES` 是手写的「旧 hash → 局部名」表，但它**没有随引擎升级同步**。
`bOPqQW`（composer 遥测药丸）既登记在表里，**又是官方当前仍在用的 hash** ——
官方 `dsh-client-ui-chat/lib/client.js` 里 `.bOPqQW_root` 与 `.O_Ebla_root`
**同时存在**，属于两个不同组件。旧逻辑无条件把登记过的 hash 从候选池剔除，于是
`bOPqQW` 丢掉了自己的 4 票证据，转而去认领了恰好共享 `label` + `root` 两个局部名的
**无关** hash `O_Ebla`（2 票，刚好够门槛）。而 `bOPqQW_root` 官方定义带
`justify-content: center`：

```css
.bOPqQW_root{…;justify-content:center;gap:12px;display:flex}   /* 遥测药丸栏 */
.O_Ebla_root{min-width:0}                                        /* 分组头 */
.O_Ebla_title{…;text-align:left;…}                               /* 明确要求左对齐 */
```

**判据演进（三次都踩了坑，最终版才是对的）**：

| 版本 | 判据 | 为什么不够 |
|---|---|---|
| v1 | 无条件跳过登记过的 hash | `bOPqQW` 丢掉自己的证据 → 认领 `O_Ebla` |
| v2 | 当下样式表里有没有该 hash | CSS Module **懒注入**，同一 hash 时有时无 → 同页有的中招有的正常 |
| v3 | 累积证据 `mpOfficialSeen`（只增不减） | 早期样式表未注入时仍会误判 `YDXeBa→oz9t_a` |
| **v4（现行）** | 累积证据 **+ 重合率 ≥ 60%** | ✅ |

**最终判据**（`mpDeriveAliasMap`，三条缺一不可）：

1. **`mpOfficialSeen.has(oldHash) || cur[oldHash]` ⇒ 跳过**。前者是「官方样式表
   **曾经**出现过」，后者是「当下 DOM 里有（且已排除桥接自己补的）」。
   ⚠️ 必须**累积**：官方 CSS Module 按需注入，未渲染组件的规则此刻不在表里，
   用快照会让判据抖动。真机实测官方样式表里出现过 114 个 hash。
2. **重合率 `hit / min(旧局部名数, 新局部名数) ≥ 0.6`**。光有 2 票远不够 ——
   真机实测 `YDXeBa→oz9t_a` 只因两者偶然共享 `time` + `chevron` 两个**通用**局部名
   就达成了 2 票。真正的「过期」是**同一组件换 hash**，局部名集合基本不变（接近 100%）。
   `YDXeBa`(10) vs `oz9t_a`(10)，命中 2 → 20% ⇒ 拒绝。
3. **目标 hash 自身也登记在旧表里 ⇒ 不许被认领**（它属于另一个组件）。

另外两条必须同时满足：

- **排除插件自己的样式表**：插件 CSS 大量使用旧类名（如 `[data-mobile] .bOPqQW_root`），
  算进去的话所有旧 hash 都会被判为未过期，**桥接彻底失效**。
  ⚠️ 判据必须**全等**比较 `dataset.pluginCss === "dsh-android-mobile/main.css"` ——
  官方 DSH 自己也给每个 CSS Module 的 `<style>` 打 `dataset.pluginCss`（值是模块路径），
  只判该属性是否存在会误伤 140 个官方样式表（真机实测 143 张表里 140 张被误判）。
- **防自我强化**：桥接补上去的类名记进 `mpAliasAdded`，DOM 采集时跳过它们 ——
  否则下一轮推导会拿自己补的类名当官方证据，越套越偏。

⚠️ **误加的类名不会自己消失**：判据修好后，DOM 上残留的旧类名照旧触发官方规则
（`justify-content: center` 依然生效），用户看到的错位不会恢复。所以有
`mpPruneStaleAliases()` —— 映射变化时主动撤掉「已补上但当前映射里不再成立」的类名。

⚠️ **别再往 `MP_LEGACY_HASHES` 里加官方当前仍在用的 hash。** 加之前先在
`engine/node_modules/@deepseek-ai/dsh-client-ui-*/lib/client.js` 里
`Select-String -SimpleMatch "<hash>_"` 搜一遍：**搜得到就说明它没过期，不该进这张表**。
（实测：这张表里 14 个 hash 在当前引擎 `0.2.0-rc.2` 下**全部没过期**，
所以桥接在当前版本下本就无事可做，任何「认领」都是误伤。）

**验收手法**（数值证据，别靠截图猜）：

```js
// 期望：O_EblaWithBOPqQW = 0，justify = "normal"，dataMobileRules ≈ 57
({
  O_Ebla_root: document.querySelectorAll('.O_Ebla_root').length,
  O_EblaWithBOPqQW: document.querySelectorAll('.O_Ebla_root.bOPqQW_root').length,
  justify: getComputedStyle(document.querySelector('.O_Ebla_root')).justifyContent,
})
```

`dataMobileRules` = `document.styleSheets` 里 `[data-mobile]` 规则条数，
正常 **57**；**0 表示插件 CSS 整体丢失**（退回桌面布局，见上一节）。
另：logcat 里**不应出现** `[mobile-polish] CSSModule 别名桥接:` 那行 ——
它一旦打出就说明有 hash 被认领了，在当前引擎版本下即为误伤。

### 「+」菜单与键盘的时序（改过，别退回去）

官方「+」的指令菜单**只在编辑区仍持有 DOM 焦点时才会渲染**，而官方
`onToggleCommandMenu` 是「先把焦点还给编辑区、再 toggle 菜单」
（`dsh-client-ui-conversation/lib/client.js:17390`）。同时官方 `keepDraftFocus`
还会在 mousedown 里再 focus 一次。

而插件的两个守卫（修复 29 的 `onFocusIn`、修复 38b 的 `arm`）都会**主动把焦点从编辑区
拿走**（键盘被手动收起后，只要还有可编辑元素持焦点，Android 就会把输入法挂回来）。
于是「谁先拿走焦点」直接决定菜单能不能出现。真机三种时序：

| 拿走焦点的时机 | 菜单 | 键盘 |
|---|---|---|
| 菜单渲染前（旧行为） | ❌ 不开 | 不弹 |
| 一直不拿 | ✅ 开 | ❌ 弹回 |
| **菜单出现之后再拿** | ✅ 开 | ✅ 不弹 |

所以有一条**共享闸门** `mpDeferBlur()` / `mpFlushPendingBlur()`（在
`setupComposerKeyboardGuard` 上方）：把「拿焦点」挂到 `[data-trigger-menu]` 出现的
那一刻，并带 700ms 兜底（官方改了渲染方式时也必须把焦点还回去，否则键盘一直挂着）。

⚠️ 闸门**必须对两条守卫都生效** —— 只改 `arm` 无效，`onFocusIn` 会先一步把焦点抢走
（真机实测：只有 `focusout`，菜单从未进过 DOM）。
⚠️ **只对 `button[aria-haspopup="listbox"]`（即「+」）延迟**；发送 / 停止 / 模型选择等
不弹这个菜单，必须维持**立即 blur**，否则白等一个兜底周期，那段时间足够键盘被挂回来。
⚠️ 不要退回「立即 blur」，也不要改成「在 keepFocus 里 blur」—— 后者同样抢在 React
渲染菜单之前。

### API 密钥输入框弹安全键盘（修复 68，**改过两次才对**）

**症状**：设置 → 模型 → 添加模型提供商，点 API 密钥输入框弹出「华为安全键盘」，
**没有粘贴入口**。API key 是 51 字符的 `sk-...`，手抄不现实 ⇒ 功能实际不可用。

**根因（第一版没找对，第二版才定论）**：官方把凭据框渲染成 `<input type="password">`
（`dsh-client-ui-settings-models/lib/client.js` L1692 与 L2552），Android WebView 据此
把 `inputType` 定成 `TYPE_TEXT_VARIATION_WEB_PASSWORD`，输入法切安全键盘（按设计屏蔽剪贴板）。

**两次踩坑，都要记住：**

**坑一：用 CSS 补圆点 ⇒ 修复完全失效。**
第一版摘掉 `type=password` 后，又写了
`input[data-mp-key="1"] { -webkit-text-security: disc !important; }` 把圆点补回来。
而 blink 判定「密码字段」用的是 `Element::ShouldObscureValue()`，它检查的正是
**`computedStyle.textSecurity != none`** —— `type=password` 只是经 UA 样式表拿到同一条声明。
摘掉属性、再手写同一条声明 ＝ 原样把密码语义装了回去。
⚠️ **「普通键盘」与「圆点遮蔽」在 Android WebView 上不可兼得**，要剪贴板就必须放弃遮蔽。

**坑二（更隐蔽）：事后改属性永远追不上 —— WebView 不为已建立的输入连接重算 `inputType`。**
官方那两个框带 **`autoFocus: true`**（L1699、L2552）。React 把元素插入 DOM 的**同一帧**
就以 `type=password` 聚焦，WebView 在那时建立了密码型连接。MutationObserver 是**之后**
才改属性的，**太晚了**。实测 `blur() + focus()` 也刷不动 —— 确认是缓存的。

**决定性对照实验**（真机 CDY-TN90 / Android 10，读 `dumpsys input_method` 的实时值）：

| 聚焦的元素 | DOM | IME `inputType` |
|---|---|---|
| 手工造的纯净 `<input type=text>` | `type=text` | `0x0` |
| 同样纯净 + `data-mp-key="1"` | `type=text` | `0x0` |
| 官方凭据框（**只做事后改属性**） | `type=text`、`ts=none` | **`0xe1`** ✗ |
| 官方凭据框（**原型层拦截**） | `type=text`、`ts=none` | **`0x80a1`** ✓ |

`0xe1` = `TYPE_CLASS_TEXT(0x1)` \| `TYPE_TEXT_VARIATION_WEB_PASSWORD(0xe0)`
`0x80a1` = `TYPE_CLASS_TEXT(0x1)` \| `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD(0x10)` \| `NO_SUGGESTIONS`

**做法**：`installPasswordTypeShim()` 在 `HTMLInputElement.prototype` 层重定义 `type`
访问器与 `setAttribute`，把写入的 `"password"` 就地换成 `"text"` ——
**让 DOM 里从头到尾没出现过 password**，输入连接从建立的第一刻就是普通文本框。
React 完全无感（它只认自己的 props，不读 DOM 属性），受控逻辑不受影响。
MutationObserver 保留作兜底 + 摘 `autocomplete="new-password"`。

**顺带排除掉的嫌疑（都实测过，别再往这些方向查）**：
- `data-mp-key` 标记本身 —— 带它的纯净 input 仍是 `0x0`
- CSS `-webkit-text-security` —— 已确认 `getComputedStyle().webkitTextSecurity` 可读，
  焦点框实测 `ts=none`，证明遮蔽确实清掉了
- `blur() + focus()` 重建连接 —— 实测刷不动 `inputType`

**验收**：`0xe1` → `0x80a1`，键盘顶部「华为安全键盘」标识消失，键盘出现剪贴板入口。

⚠️ 改的是 `mobile-ui.zip` 内容 ⇒ **必须同时改两处版本戳**，否则老设备静默不更新：
`EngineManager.MOBILE_UI_VERSION`（→ `1.0.0-49`）与
`tools/build-webview-compat.ps1` 的 `-CompatVersion`（→ `9`，并重跑脚本）。
降级产物会**覆盖**源文件，这是「改了没生效」最常见的陷阱（见 ⑯）。

