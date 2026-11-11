// 在**真实官方前端**上跑 ui-probe.js，产出「按钮位置地图」。
//
// 为什么需要它：真机装不上本仓库的包（设备上是别处签名的 APK，
// `install -r` 撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE，而卸载会清掉
// filesDir 下的整个 Termux 环境与 $DSH_HOME），所以 `ui` 工具链在
// 真机上跑不起来。但探针本身是**纯页面 JS**，只要有一份真实的官方
// 前端 DOM，就能验证它在真前端上到底看见了什么。
//
// ⚠️ 真机上另有一条路也走不通：`uiautomator dump` 在 WebView 上**只给一个
//    `android.webkit.WebView` 节点**（实测华为 CDY-TN90：3137 字节的树里
//    只有 5 个 FrameLayout + 1 个 WebView，页面内的按钮一个都不在无障碍树里）。
//    所以按钮坐标的唯一可靠来源就是本脚本 —— 在真实前端里直接量 CSS 坐标。
//
// 用法：
//   1) 起一个独立实例（独立 DSH_HOME / 端口，别碰用户正在用的那个）：
//        $env:DSH_HOME = "<隔离目录>"
//        dsh web --no-open --port 31999
//   2) node tools/ui-map-real-frontend.mjs "http://127.0.0.1:31999/?token=..."
//
// ## ⚠️ 两种采集目标，结论差别很大 —— 优先用真机
//
// 默认（不给 `UIMAP_CDP`）= 在 PC 上开 headless Chrome 采。**这条路有系统性偏差**：
//
// | 维度 | PC 隔离实例 | 真机 | 后果 |
// |---|---|---|---|
// | 视口 | 360×780（脚本强设） | 360×764 | 底部元素坐标偏 |
// | CSS Modules 类名 | `_2H3hWW_newSession` | `hHd-Xa_newSession` | **100% 对不上**（构建 hash 不同） |
// | 布局 | 默认设置、默认模型 | 用户自己的配置 | 条目数与间距都不同 |
//
// 实测（真机审计 17 屏）：`els` 的**定位文本**里有用户数据（模型名、推理等级）——
// 真机是 `Agnes` / `Default`，PC 上是 `DeepSeek-V41-Flash` / `High`。
// **照 PC 采的表去真机点按钮，会直接点空。**
//
// 所以**要 `els` 精确，就得多花几分钟在真机上采**：
//
//     adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>
//     $env:UIMAP_CDP = "http://127.0.0.1:19222"
//     node tools/ui-map-real-frontend.mjs "<真机当前 url（带 token）>"
//
// 真机模式与 PC 模式的差别（都在下面代码里有对应分支）：
//   · **不 spawn Chrome**，直接连设备上 WebView 的 CDP；
//   · **不设视口**（`Emulation.setDeviceMetricsOverride` 会把设备视口改掉，
//     采到的就不是真机布局了）—— 视口由设备说了算；
//   · 每屏重来时要**还原 localStorage 快照**（真机上的状态残留比 PC 严重得多，
//     详见 `resetToBase` 里的注释）；
//   · **跳过末尾的「交互实测」**—— 那会真的发一条消息，污染用户正在用的会话。
//
// 输出：控制台报告 + tools/ui-map-real-frontend.json
//
// 依赖：PC 模式需要 headless Chrome（`--headless=new`）+ CDP；
//       真机模式只需要一条 `adb forward`。Node 24 自带 WebSocket。

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROBE = join(__dirname, '..', 'runtime', 'scripts', 'ui-probe.js');

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  join(process.env.LOCALAPPDATA ?? '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

// ── 只跑指定屏（调试用）─────────────────────────────────────────────
//
// ⚠️ 真机跑满 17 屏要十几分钟，中途一屏卡住就白等。`UIMAP_ONLY=hero,terminal`
//    只跑那几屏。
//
// ⚠️⚠️ **此时绝不写回 `ui-map-real-frontend.json`** —— 那会把完整图谱覆盖成
//    残缺的几屏，而 `build-uiagent-screens.mjs` 会照它重建内建表，
//    真机上的 `goto` 就只剩那几屏能用了。所以局部跑的输出另存一份，
//    要落地就**必须跑全量**。
const ONLY = (process.env.UIMAP_ONLY ?? '').split(',').map((s) => s.trim()).filter(Boolean);
const PARTIAL = ONLY.length > 0;

const url = process.argv[2];
if (!url) {
  console.error('用法: node tools/ui-map-real-frontend.mjs "<带 token 的 URL>"');
  process.exit(2);
}

// ── 采集目标：真机（`UIMAP_CDP`）还是 PC 上的 headless Chrome ──────────
//
// ⚠️ 见文件头那张对照表：两者的类名与布局都不一样，**要精确就必须在真机上采**。
const EXTERNAL_CDP = process.env.UIMAP_CDP ?? '';
const ON_DEVICE = EXTERNAL_CDP !== '';

const chrome = ON_DEVICE ? '（真机模式，不用 Chrome）' : CHROME_CANDIDATES.find((p) => {
  try { return readFileSync(p) !== null; } catch { return false; }
});
if (!ON_DEVICE && !chrome) {
  console.error('找不到 Chrome/Edge');
  process.exit(2);
}

// ── 屏幕清单 ──────────────────────────────────────────────────────
//
// 「屏幕」= 一组**进入步骤**唯一确定的界面状态。进入步骤用的定位参数与 `ui`
// 工具的 `tap` 完全同构（`text` / `aria` / `sel`），所以图谱里记下的每一步
// 都能**原样**喂给 `ui tap` —— 这是这份图谱的价值所在：它既是坐标表，
// 也是「怎么走到那一屏」的路线。
//
// ⚠️ 每屏都从**基态**（Hero，页面刚加载完）重新进入，而不是从上一屏接着走。
//    这样屏与屏之间没有状态污染，某一屏的步骤写错了也不会连带毁掉后面的屏。
//
// ⚠️⚠️ **但「重载」并不足以回到基态 —— 这个前端会持久化好几样东西**，本轮
//    在这上面连栽三次，症状全都是「找不到要点击的元素」（看着像定位写错了）：
//
//    | 被持久化的 | 后果 | 对策 |
//    |---|---|---|
//    | 右侧边栏的**开关状态** | 重载后它自己又开着，入口按钮的 aria 从「打开右侧边栏」变成「收起右侧边栏」 | 每屏重置后先 `collapsePanels()` 收掉 |
//    | 右侧边栏的**内容页** | 点过「新建终端」后，再打开直接是终端页，启动页的「新建终端」不再出现 | 启动页那一屏排在终端屏**之前** |
//    | **当前会话** | 种过会话就回不到空会话态，「选择新任务使用的 Agent 预设」再也不渲染 | 只在空态存在的屏排在 `drawer` 之前 |
//
//    共同点是**单向**：一旦进入就回不去。所以屏的顺序本身是有语义的，
//    不是随手排的 —— 改顺序前先想清楚哪一屏会把状态推过去。
//
// `expect` 是「这一屏真的进去了吗」的判据 —— 只看 `tap` 的 `ok` 不够：
// 点了没生效时 `ok` 照样是 true（那正是遮挡/屏幕外两类问题的表现）。
const SCREENS = [
  {
    id: 'hero',
    title: '空会话（Hero）',
    enter: [],
    // ⚠️⚠️ **这一屏没有「独有元素」，判据不能只看文本 —— 这是实测结论。**
    //
    //    最初写的是 `{text:'打开导航'}`，注释里还写着「只在这一屏出现」。
    //    那是**错的**：它是 mobile-ui 塞在 `body > button` 上的常驻悬浮按钮，
    //    实测 **17 屏全都有**（抽屉那屏只是滑到了 `left:-111`）。用它当判据
    //    等于没判 —— 任何一屏都满足，`goto('hero')` 会永远报「到了」。
    //
    //    其余候选同样不行：「选择新任务使用的 Agent 预设」「搜索会话」这些底座
    //    元素在 `hero`…`preset-picker` 共 12 屏都在（面板是**盖在** Hero 之上的，
    //    底层 DOM 一直没被卸载）。
    //
    //    所以判据要靠**「可点」这个附加条件**区分：这个占位符在 `hero` 可点，
    //    在另外 11 屏都存在但**被覆盖层挡住**（`clickable:true` 把「被遮挡」与
    //    「屏幕外」都算不成立）；其余 5 屏（会话/侧栏那几屏）它根本不存在。
    //
    //    ⚠️ 必须配 `exact:true`：只给短名会子串命中那 11 屏的同一个元素，
    //       判据就变成「覆盖层还在不在」而不是「是不是 Hero」—— 含义不再单一。
    expect: { text: '描述你想要构建的内容, / 调用指令, @ 文件或对话', exact: true, clickable: true },
  },
  {
    id: 'settings',
    title: '设置面板',
    enter: [{ text: '设置' }],
    expect: { text: '通用设置' },
  },
  {
    id: 'settings-general',
    title: '设置 › 通用设置',
    enter: [{ text: '设置' }, { text: '通用设置' }],
    // ⚠️ 判据挑**这一屏独有**的：字号那对按钮只有通用设置里有
    //    （「返回」「关闭」「中文」在别的设置页也有，不能用）
    expect: { text: '增大字号' },
  },
  {
    id: 'settings-group',
    title: '设置 › 交流群',
    enter: [{ text: '设置' }, { text: '交流群' }],
    expect: { text: '复制群号' },
  },
  {
    id: 'settings-plugins',
    title: '设置 › 内置插件',
    enter: [{ text: '设置' }, { text: '内置插件' }],
    expect: { text: '会话插件' },
  },
  {
    id: 'settings-model',
    title: '设置 › 模型',
    enter: [{ text: '设置' }, { text: '模型' }],
    // ⚠️ 判据要挑**这一屏独有**的元素：「返回」「关闭」这类在好几屏都有，
    //    拿它当判据等于没判（任何面板屏都能满足）。「添加模型」只在这一屏出现。
    expect: { text: '添加模型' },
  },
  {
    id: 'settings-preset',
    title: '设置 › Agent 预设',
    enter: [{ text: '设置' }, { text: 'Agent 预设' }],
    expect: { text: '新任务默认: 标准模式' },
  },
  {
    id: 'settings-priv',
    title: '设置 › 特权权限',
    enter: [{ text: '设置' }, { text: '特权权限' }],
    // ⚠️⚠️ **「重新检测」不行**：实测它在 `settings-bubble`（悬浮气泡）那屏
    //    同样存在（那屏也有个「重新检测」按钮），两屏都成立 ⇒ 等于没判。
    //    改用只有本屏才有的引导文案（它把 Android 版本写进了按钮名里）。
    expect: { text: '开启引导（Android 10 用这条）', exact: true },
  },
  {
    id: 'settings-bubble',
    title: '设置 › 悬浮气泡',
    enter: [{ text: '设置' }, { text: '悬浮气泡' }],
    expect: { text: '怎么用' },
  },
  {
    id: 'plugins-panel',
    title: '插件面板',
    enter: [{ text: '插件' }],
    expect: { text: '添加插件' },
  },
  {
    id: 'model-picker',
    title: '模型选择器',
    // ⚠️ 用 `text` 而不是 `aria`：这个按钮**没有** `aria-label`（实测
    //    `{aria:'选择模型，当前…'}` 报「找不到要点击的元素」）。同排的
    //    「访问模式」「Agent 预设」两个倒是有 aria-label —— 同一行里三个按钮
    //    的取值口径都不一样，所以**别照着邻居抄**，先 `dump` 看它报的是哪个字段。
    enter: [{ text: '选择模型' }],
    // ⚠️⚠️⚠️ **别拿面板里的「值」当判据 —— 那是用户数据，不是结构。**
    //
    //    这里原来写的是 `{text:'推理等级 High', exact:true}`，理由是「精确相等
    //    就能避开每屏都在的那句长文案」。**那个理由只在生成图谱的那台机器上成立**：
    //    它靠的是「High」这个**配置值**。
    //
    //    真机实测（用户设备）：这一屏的两行是
    //        「模型 agnes-3.0-flash」  「推理等级 Default」
    //    —— 用户把默认模型换成了 Agnes、推理等级是 Default。于是整串「推理等级 High」
    //    **永远匹配不上**，`goto('model-picker')` 报「没等到」，而那一屏明明开着。
    //
    //    **教训（本图谱最重要的一条）**：判据里不能出现**随用户配置变化的值**
    //    （模型名、推理等级、权限、主题、字号…）。图谱是**结构地图**，不是快照。
    //    同类错误还有一处：`terminal` 的 `cmd.exe`（随 Shell 名变，见那一屏）。
    //
    //    改用稳定前缀「模型 」（注意**带尾空格**）走**前缀匹配** `prefix`：
    //      · 命中面板里那行「模型 agnes-3.0-flash」（前缀不随模型名变）；
    //      · **不会**命中每屏都在的触发按钮「选择模型，当前 agnes-3.0-flash，…」
    //        —— 它以「选择模型」开头，「模型 」不是它的前缀。这个尾空格就是要害。
    //
    //    ⚠️ 为什么不能用 `text`（哪怕带尾空格）：`byText` 内部做
    //    `String(text).trim()`，尾空格被吃掉，「模型 」退化成「模型」，
    //    于是把 16 屏的长文案全命中 —— 与判据审计器实测的结果完全一致。
    //    为此给探针加了 `prefix` 入口（`byPrefix`，**不 trim**）。
    expect: { prefix: '模型 ' },
  },
  {
    id: 'access-mode',
    title: '访问模式选择器',
    // ⚠️⚠️ **不能用 `aria` 定位这个按钮 —— 它的 aria 是「动态」的。**
    //
    //    原来写的是 `{aria:'访问模式，当前：工作区内修改'}`，在生成器当时的
    //    环境里能通（那时权限就是「工作区内修改」）。但这个 aria 的文案是
    //    「访问模式，当前：<当前值>」——**当前值一变，整串就对不上了**。
    //
    //    真机实测（用户设备权限是「完全权限」）：`locate()` 里 `aria` 走的是
    //    **精确相等**（`[aria-label="..."]`），于是永远失配，报「找不到要点击的
    //    元素」，而那个按钮明明就在 dump 列表里。三屏（hero / access-mode /
    //    model-picker）因此连带失效。
    //
    //    改用 `text:'访问模式'`：`byText` 的兜底是**子串包含**，稳定前缀能命中，
    //    且不依赖当前值。实测真机 `tap text:"访问模式"` → `ok=true onTarget=true`，
    //    面板正常打开。
    //
    //    **教训**：凡是 dump 报出来的文案里带「当前 / 已选 / 第 N 个」这类
    //    **随状态变化的片段**，就不能整串拿来当定位条件 —— 只能用它的稳定前缀。
    enter: [{ text: '访问模式' }],
    // ⚠️⚠️ **判据不能取「当前那一项」，必须取一个与当前状态无关的枚举项。**
    //
    //    原来写的是 `{text:'完全权限'}`，理由是「三个选项里它最不可能出现在别处」。
    //    这个理由**被实测推翻**了：`完全权限` 恰好是用户**当前的权限值**，于是
    //    composer 上那个按钮的 `labelOf`（aria 优先）成了
    //    `访问模式，当前：完全权限`，`byText` 的**子串**兜底把它算了进去 ——
    //    审计实测这一条判据在 **16 屏**成立（除 access-mode 外全中）。
    //
    //    换成 `仅可查看` 也不行：它同样是三个枚举值之一，用户哪天把权限调成
    //    「仅可查看」，composer 的 aria 就变成 `访问模式，当前：仅可查看`，
    //    子串照样撞上。
    //
    //    真正的解法是 **`exact: true`**：菜单项的 `text` 恰好等于 `仅可查看`，
    //    而 composer 那个按钮的 `text` 永远是 `访问模式，当前：<任意值>` 这种
    //    长串 —— 精确相等**永远**不成立。于是判据与「当前权限是什么」彻底解耦，
    //    无论用户选哪一档都只在这一屏成立（实测 exact 命中 1）。
    //
    //    **教训**：枚举型面板的判据要同时满足两条 —— ①取枚举项而不是当前值；
    //    ②`exact:true`，免得被「前缀 + 当前值」形态的常驻 aria 子串吞掉。
    expect: { text: '仅可查看', exact: true },
  },
  {
    id: 'preset-picker',
    title: 'Agent 预设选择器',
    enter: [{ aria: '选择新任务使用的 Agent 预设' }],
    // 这一屏的选项文本很长（带整段说明），所以判据用**子串**：
    // `byText` 先找完全相等、找不到才退回子串包含，所以给短名即可命中。
    //
    // ⚠️⚠️ **但「极简模式」这个短名会同时命中 `settings › Agent 预设` 屏**
    //    （那屏有「设为新任务默认: 极简模式」「模式说明: 极简模式」等四个按钮），
    //    两屏都成立 ⇒ 等于没判。改用**选项自身的完整文案 + 精确相等** ——
    //    它只在这一屏出现（那屏的按钮文案都是「动词: 极简模式」的形状）。
    expect: { text: '极简模式 Agent 仅使用终端工具完成任务，适合测试和对比其基础表现。', exact: true },
  },
  // ⚠️⚠️ **顺序是有语义的：`drawer` 必须排在最后几个。**
  //
  //    「种会话」是**单向**的 —— 前端会把当前会话持久化，重载也回不到空会话态。
  //    实测：seed 之后「选择新任务使用的 Agent 预设」这个按钮**再也不出现**
  //    （它在 Hero 空态才渲染），于是 `preset-picker` 屏永远进不去，
  //    而且症状是「找不到要点击的元素」—— 看着像探针定位错了，其实是页面已经不是那一屏。
  //
  //    所以所有**只在空会话态存在**的屏都必须排在 `drawer` 之前。
  //    这条与「每屏都从基态重来」是配套的：重载能回到基态，但**回不到空会话态**。
  {
    id: 'drawer',
    title: '会话抽屉（左侧栏展开）',
    // ⚠️ **必须先进入「有会话」的页面**，否则抽屉根本开不出来：空会话（Hero）页
    //    上官方侧栏还没渲染（`.pI_x6G_sidebarCol` 不存在），mobile-ui 的
    //    `mpToggleDrawer()` 里 `mpOfficialToggle()` 返回 null、`if (btn) btn.click()`
    //    不执行 —— 点「打开导航」本来就没反应（那是 mobile-ui 在 Hero 页的缺陷，
    //    探针如实报 `onTarget:true` 并没有说谎）。
    //    实测：空会话下点「打开导航」后标签数仍是 15、没有「收起侧边栏」；
    //    先发一条消息（页面进入会话态）再点，就能开出 36 个标签的抽屉。
    seed: true,
    enter: [{ aria: '打开导航' }],
    // ⚠️ 这里必须用 `text` 不能用 `aria`：抽屉里的「收起侧边栏」**没有**
    //    `aria-label`，文案在文本节点里。而 `locate()` 的 `aria` 分支只查
    //    `[aria-label="…"]`（现已回落到 `title`，但抽屉这个连 `title` 也没有），
    //    **不回落**到文本 —— 用 `aria` 会「找不到要点击的元素」，
    //    而那个按钮明明就在屏幕上（实测踩过：抽屉明明开了，校验却说没进去）。
    //    判据是 dump 里的字段：`aria` 有值才用 `aria`，否则用 `text`。
    expect: { text: '收起侧边栏' },
    // 抽屉要点两次（见下面的重试注释），所以给它多一次机会
    enterAttempts: 3,
  },
  {
    id: 'right-sidebar',
    title: '右侧边栏',
    enter: [{ aria: '打开右侧边栏' }],
    expect: { text: '新建终端' },
  },
  // ⚠️ 「工作区文件」与「终端」都是右侧边栏里的**内容页**，而内容页是**持久化**的
  //    （见上面「持久化状态」那段）：点过其中一个，再打开侧边栏就直接落在它上面，
  //    启动页（有「新建终端」/「工作区文件」两个入口的那一屏）不再出现。
  //    所以这两屏都只能从启动页进，**排在一起、各跑各的**：每屏都会先
  //    `collapsePanels()` 把侧边栏收掉，下一次打开才重新回到启动页。
  {
    id: 'workfiles',
    title: '右侧边栏 › 工作区文件',
    enter: [{ aria: '打开右侧边栏' }, { text: '工作区文件' }],
    // 「重新读取」是这一屏特有的（终端屏没有），用它当判据
    expect: { text: '重新读取' },
  },
  {
    id: 'terminal',
    title: '右侧边栏 › 终端',
    enter: [{ aria: '打开右侧边栏' }, { text: '新建终端' }],
    // ⚠️⚠️ 判据**不能**用「新标签页」—— 工作区文件屏也有它，于是「其实没进去」
    //    也会被判成 ✅（实测踩过：`tap「新建终端」ok=false` 却报已进入）。
    //
    // 原来用的是 `{text:'cmd.exe'}`（当前 Shell 的标签名）。**那是个用户/平台数据**：
    //    真机实测标签名是 **`login`**（Android 上默认 Shell 不是 Windows 的 cmd），
    //    于是 `goto('terminal')` 在真机上永远报「没等到」，而终端明明开着。
    //    ⚠️ 这与 `model-picker` 是**同一类错误**：判据里写了会变的值。
    //    **凡是「标签名 / 模型名 / 权限 / 主题 / 字号」都属此类，不能进判据。**
    //
    // 改用**结构标记**：终端屏里挂着 xterm.js 的 DOM，它的类名是**第三方库的
    // 固定类名**（不是 CSS Modules 的构建 hash），实测真机上是
    //    `terminal xterm xterm-dom-renderer-owner-1` / `xterm-viewport` /
    //    `xterm-screen` / `xterm-rows` …
    // 这些名字跨构建、跨平台、跨用户配置都不变 —— 而且 `.xterm-screen` 只在
    // 「终端页签处于激活态」时才挂上，所以它同时表达「在终端屏」和「终端已渲染」。
    //
    // ⚠️ 用 `sel` 而不是文本：`xterm-screen` 是个 `<div>`，**不在** `INTERACTIVE`
    //    候选集里（没有 role、不是 button/a/input），按文本根本 dump 不到它。
    //    `sel` 走 `document.querySelector`，不受候选集限制。
    expect: { sel: '.xterm-screen' },
  },
];

// ── CDP 最小客户端 ────────────────────────────────────────────────
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      const p = this.pending.get(msg.id);
      if (p) { this.pending.delete(msg.id); msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result); }
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => { if (this.pending.delete(id)) reject(new Error(`CDP 超时: ${method}`)); }, 60000);
    });
  }
  /** 在页面里 eval 一个表达式，返回其值（awaitPromise）。 */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? '页面异常');
    return r.result.value;
  }
}

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true });
    ws.addEventListener('error', rej, { once: true });
  });
  return new Cdp(ws);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 主流程 ───────────────────────────────────────────────────────
//
// ⚠️ 真机模式**不 spawn Chrome**、也**不建临时 profile** —— 那两样都是
//    「在 PC 上造一个前端」用的。真机上已经有真前端，我们连上去量即可。
const profile = ON_DEVICE ? null : mkdtempSync(join(tmpdir(), 'dsh-uimap-'));
const port = 9500 + Math.floor(Math.random() * 400);

const child = ON_DEVICE ? null : spawn(chrome, [
  '--headless=new',
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check',
  '--disable-gpu', '--hide-scrollbars',
  // 手机视口：与 DSH Android 上 WebView 的实际尺寸同量级（1080×2340 物理 / 3.0 dpr）
  '--window-size=393,851',
  '--force-device-scale-factor=3',
  'about:blank',
], { stdio: 'ignore' });

/** 把一屏的 `expect` 描述成人话，给日志用。 */
function fmtExpect(ex) {
  if (!ex) return '(无)';
  const head = ex.text ?? ex.prefix ?? ex.aria ?? ex.sel;
  const tags = [];
  if (ex.exact) tags.push('精确');
  if (ex.clickable) tags.push('需可点');
  return `「${head}」${tags.length ? `（${tags.join('+')}）` : ''}`;
}

let cdp;
try {
  let targets = null;
  if (ON_DEVICE) {
    // 真机：`adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>`
    // 已经把设备的 CDP 端口映射到本机，直接读它的目标列表。
    for (let i = 0; i < 10 && !targets; i++) {
      try {
        const r = await fetch(`${EXTERNAL_CDP}/json/list`);
        if (r.ok) targets = await r.json();
      } catch { await sleep(500); }
    }
    if (!targets) {
      throw new Error(`连不上真机 CDP（${EXTERNAL_CDP}）。先确认 adb forward 还在：\n` +
        `  adb shell pidof com.dsh.android\n` +
        `  adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>`);
    }
  } else {
    // 等 CDP 端口起来
    for (let i = 0; i < 60 && !targets; i++) {
      await sleep(250);
      try {
        const r = await fetch(`http://127.0.0.1:${port}/json/list`);
        if (r.ok) targets = await r.json();
      } catch { /* 还没起来 */ }
    }
    if (!targets) throw new Error('CDP 没起来');
  }

  // ⚠️ `/json/list` 里混着**扩展的 background 页**（type=background_page /
  //    service_worker），它的 innerWidth 是 0、枚举出 0 个元素 —— 第一版就是
  //    连到了它，表现为「探针 ping 正常但 dump 出 0 个元素」，很容易误判成
  //    探针坏了。必须挑真正的页面目标。
  //
  //    ⚠️ 真机上这一条更要紧：设备的 `/json/list` 里还有
  //    `webview_devtools_remote_<别的应用>` 之类别的目标（实测
  //    `com.dsharnessmobile.shell` 就占着一个），挑错就会量到别人的页面。
  //    所以这里**优先挑 URL 与前缀匹配的那个**。
  const page = targets.find((t) => t.type === 'page' && (t.url ?? '').startsWith(url.slice(0, url.indexOf('/', 8)) || url))
    ?? targets.find((t) => t.type === 'page' && /^https?:/.test(t.url ?? ''))
    ?? targets.find((t) => t.type === 'page');
  if (!page) throw new Error(`没有可用的 page 目标: ${targets.map((t) => t.type).join(',')}`);
  console.log(`连上 ${ON_DEVICE ? '真机' : 'headless Chrome'} 目标: ${(page.url ?? '').slice(0, 70)}`);

  cdp = await connect(page.webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  await cdp.send('Page.enable');

  // ⚠️ **PC 模式必须用 Emulation 精确设视口，不能靠 `--window-size`。**
  //    实测 `--window-size=393,851` 在 headless 下拿到的是 486×753（窗口装饰与
  //    缩放的差异），而 mobile-ui 的窄屏适配是按宽度触发的 —— 视口错一点，
  //    拿到的就是**桌面布局的坐标**，整张地图作废。
  //    手机上 WebView 的实际尺寸：1080 物理宽 / dpr 3.0 = 360 CSS 宽。
  //
  // ⚠️⚠️ **真机模式绝不能设 Emulation**——那会把设备 WebView 的视口**改掉**，
  //    采到的就不是真机布局了（而「真机布局」正是这条路唯一的理由）。
  //    真机的视口由设备说了算：实测 360×764 / dpr 3，比 PC 模式的 360×780
  //    矮 16 px。下面这一整段在真机模式下整段跳过。
  const VW = Number(process.env.UIMAP_W ?? 360);
  const VH = Number(process.env.UIMAP_H ?? 780);
  const DPR = Number(process.env.UIMAP_DPR ?? 3);
  if (ON_DEVICE) {
    const vp = await cdp.eval(`({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})`);
    console.log(`真机视口: ${vp.w}×${vp.h} dpr=${vp.dpr}（不设 Emulation，以设备为准）`);
  } else {
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: VW, height: VH, deviceScaleFactor: DPR, mobile: true,
    });
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  }

  const source = readFileSync(PROBE, 'utf8');
  const probeVersion = Number(/var VERSION = (\d+)/.exec(source)?.[1] ?? 0);

  // ── 真机模式的现场快照 ────────────────────────────────────────────
  //
  // ⚠️⚠️ **这是真机与 PC 最要紧的一处差别：真机上跑的是用户正在用的前端。**
  //
  //   1. **不要碰持久化状态**。真机前端把当前会话、右侧边栏内容页、工作区
  //      视图都存在 localStorage 里。采图谱要一屏一屏地「点进去」，那些点击
  //      **会真的推进这些状态**（例如点开右侧边栏的「工作区文件」后，那个
  //      内容页是**单向**的，再打开侧边栏就直接落在文件页上）。跑完必须还原，
  //      否则用户醒来会发现界面被改过 —— 这正是任务约束里「不能被用户看出」。
  //
  //   2. **每屏之间也要隔离**。实测 P C 上没这问题，真机上很严重：`terminal`
  //      屏单跑 7/7 全对，混在 17 屏里跑就失败，因为前序的 `workfiles` 屏
  //      把它推走了。所以每屏都从**跑之前的原始快照**起步。
  //
  //   3. **三屏只在空会话态存在**（hero / access-mode / preset-picker）。
  //      用户设备上 `dsh.sessions.current` 是有值的，不清掉它们永远进不去。
  //      清掉之后**必须还原**。
  //
  //   ⚠️⚠️⚠️ **清 `dsh.sessions.current` 不是无害操作 —— 前端会自己「造一个」补上。**
  //
  //      实测踩到（2026-10-13）：跑完全量审计后，用户的 `dsh.sessions.current`
  //      从他自己的会话变成了一个**新建的空会话**（`blank:true`、`turns:0`）。
  //      用户在列表里的真实会话一直没丢，但**界面停在了新会话页上** ——
  //      这正是任务约束「不能被用户看出」要防的事。
  //
  //      根因在 `@deepseek-ai/dsh-client-ui-workspace/lib/client.js`：
  //        · L761 `selection = createSnapshotStore({}, {persist:{name:'dsh.sessions.current'}})`
  //        · L800 `reuseOrCreateBlank(workspace)`：先找「blank 且 cwd 匹配且未归档」
  //          的会话复用（L806）；**一个都找不到就 L808 `this.sessions.create(...)`
  //          新建一个**。
  //
  //      所以「清掉 → 重载」得到的不一定是空态，而是「前端挑一个 / 造一个」。
  //      对策有三条，**缺一不可**：
  //        a. 每屏重置时，**先把 current 写回原始值**再动别的键 —— 让「造一个」
  //           的前提不成立（原始 current 指向的会话存在且非 blank，会被复用）；
  //        b. 空会话态那三屏**跑完立刻**把 current 写回去（见下面的 finally 段），
  //           不能等到全跑完；
  //        c. 末尾**逐键回读核对**（只写回不核对等于没还原，见文件末的还原段）。
  const LS_DUMP = `(() => {
    var o = {};
    for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); o[k] = localStorage.getItem(k); }
    return JSON.stringify(o);
  })()`;
  const ORIGINAL_LS = ON_DEVICE ? JSON.parse(await cdp.eval(LS_DUMP)) : null;
  /** 用户原本所在的会话 id —— 空会话态跑完要把它写回去。 */
  const ORIGINAL_CURRENT = ON_DEVICE
    ? (() => {
      try { return JSON.parse(ORIGINAL_LS['dsh.sessions.current'] ?? '{}').sessionId ?? null; }
      catch { return null; }
    })()
    : null;
  if (ON_DEVICE) {
    console.log(`真机 localStorage 快照: ${Object.keys(ORIGINAL_LS).length} 个键` +
      `（跑完会原样写回）: ${Object.keys(ORIGINAL_LS).join(', ')}`);
    console.log(`  用户当前会话: ${ORIGINAL_CURRENT ?? '(无)'}`);
  }

  /** 把 current 写回用户原本的会话（空会话态跑完之后调用）。 */
  const restoreCurrentSession = async (tag) => {
    if (!ON_DEVICE || !ORIGINAL_CURRENT) return;
    await cdp.eval(`localStorage.setItem('dsh.sessions.current', ${JSON.stringify(JSON.stringify({ sessionId: ORIGINAL_CURRENT }))}); 1`);
    console.log(`  ${tag}已把 current 写回用户会话 ${ORIGINAL_CURRENT}`);
  };

  /**
   * 跑之前**已存在**的会话 id 集合。
   *
   * ⚠️ 收尾时靠它做差集，把本次跑出来的 blank 会话归档掉（见文件末的还原段）。
   *    不记这个集合的话，就只能「看到 blank 就归档」，那会误伤用户自己留着的
   *    空白会话 —— 用户可能正打算用它。
   */
  const ORIGINAL_SESSION_IDS = ON_DEVICE
    ? await (async () => {
      await cdp.eval(`(() => { window.__rpc0 = null;
        fetch('/api/session/list', { method:'POST', headers:{'content-type':'application/json'},
          body: JSON.stringify({ type:'client-request', rpcId:'q0', method:'session/list', payload:{ args:{ _request:{} } } }) })
          .then(r=>r.text()).then(t=>{ window.__rpc0=t; }).catch(e=>{ window.__rpc0='ERR '+e; });
        return 1; })()`);
      for (let i = 0; i < 40; i++) {
        await sleep(250);
        const v = await cdp.eval('window.__rpc0');
        if (v) {
          try {
            const ids = (JSON.parse(v)?.result?.value?.items ?? []).map((x) => x.sessionId);
            console.log(`跑之前的会话 id（${ids.length} 个）: ${ids.join(', ') || '(无)'}`);
            return new Set(ids);
          } catch { console.log('⚠️ 会话列表没读到，收尾将跳过归档'); return null; }
        }
      }
      return null;
    })()
    : null;

  /** 只在空会话态才存在的屏 —— 参见 SCREENS 里那段「顺序是有语义的」注释。 */
  const EMPTY_SESSION_ONLY = new Set(['hero', 'access-mode', 'preset-picker']);

  // 打开前端 + 装探针 + 关掉首启向导，收敛到**基态**（Hero）。
  //
  // ⚠️ 每屏都从这里重来，所以这段被抽成函数而不是内联一次。
  async function resetToBase(emptySession = false) {
    if (ON_DEVICE) {
      // 先把 localStorage 还原成原始快照（含「跑上几屏时新产生的键」一起清掉），
      // 再按需清掉 `dsh.sessions.current` 回到空会话态，最后才重载。
      //
      // ⚠️ 这里**故意先把 current 写回原始值**（`keep` 里就带着它），再按需删 ——
      //    顺序反了（先清后补）在真机上会踩到「前端自己造一个新会话」，
      //    详见上面那段 ⚠️⚠️⚠️ 的注释。
      await cdp.eval(`(() => {
        var keep = ${JSON.stringify(ORIGINAL_LS ?? {})};
        for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
        for (var k in keep) localStorage.setItem(k, keep[k]);
        ${emptySession ? "localStorage.removeItem('dsh.sessions.current');" : ''}
        return 1;
      })()`);
    }
    await cdp.send('Page.navigate', { url });
    let ready = false;
    for (let i = 0; i < 80; i++) {
      await sleep(500);
      try {
        const info = await cdp.eval(`(() => ({
          title: document.title,
          nodes: document.querySelectorAll('*').length,
          text: (document.body && document.body.innerText || '').slice(0, 120)
        }))()`);
        if (info.nodes > 50) { ready = true; return info; }
      } catch { /* 导航中 */ }
    }
    if (!ready) console.warn('  ⚠️ 前端似乎没挂载起来，继续尝试');
    return null;
  }

  // ── 先处理遮挡层（这是「操作方法」的第 0 步）───────────────────────
  //
  // ⚠️ 全新 profile 首次打开会弹「预览版说明」对话框（`z-index:1000` + 覆盖全屏
  //    的 `_mask_`）。它会盖住底下**全部**按钮 —— 而底下的按钮在 DOM 里看着
  //    完全正常（有尺寸、`opacity` 为 1），第一版探针因此把它们全报成可用，
  //    模型挨个点、界面纹丝不动且拿不到任何提示。
  //
  //    现在探针会自己发现它：`overlay` 动作报出遮挡层与它**里面**的按钮，
  //    `dump` 也会带上 `overlay` + `warning`。下面这段就是照着那个输出走的，
  //    与真机上模型要走的路径**完全一致** —— 不是测试专用的特判。
  // ⚠️ 要**循环**：全新 profile 的首启向导是**多步**的 —— 关掉「预览版说明」
  //    之后立刻又弹「API 密钥」（稍后配置 / 保存并继续）。只关一次会停在
  //    第二个弹窗上，拿到的坐标照样点不动。这正是模型要学的那套操作法：
  //    `overlay` → 点此刻唯一能点的东西 → 再看 `overlay`，直到 present:false。
  const dismissOverlays = async (tag) => {
    for (let round = 1; round <= 5; round++) {
      const ov = await cdp.eval(`(${source})({action:"overlay"})`);
      // ⚠️ **必须按 `kind` 判，不能按 `present` 判。** `present` 的语义是
      //    「有没有覆盖全屏的层」（面板也算），只有 `kind === 'mask'` 才是
      //    「要关掉的那个空遮罩」。照 `present` 判会把设置面板 / 右侧边栏
      //    也当遮挡层去「关」，实测把右侧边栏点开又点关，绕两圈才回到原位。
      if (!ov?.present || ov.kind !== 'mask') {
        console.log(`  ${tag}遮挡层: 没有了 ✅（此刻能点 ${ov?.count ?? 0} 个）` +
          (ov?.kind === 'panel' ? `（当前屏幕是面板 ${ov.panel?.cls || ov.panel?.tag}，不是遮挡）` : ''));
        return true;
      }
      console.log(`  ${tag}遮挡层 ${round}: ${ov.layer.cls || ov.layer.tag}（${ov.layer.rect.w}×${ov.layer.rect.h}，` +
        `${ov.layer.hits}/${ov.layer.samples} 个采样点被它挡住）`);
      console.log(`    此刻真正能点的有 ${ov.clickable.length} 个: ` +
        ov.clickable.map((b) => `「${b.text || b.sel}」`).join(', '));
      // ⚠️ 探针**如实**报出此刻能点的一切（可能是输入框 + 若干按钮），但**不替
      //    模型猜语义** —— 关弹窗该点哪个是业务判断。这里照模型的思路来：
      //    先挑「跳过/稍后/继续/知道了」这类**不要求填内容**的按钮，其次挑普通
      //    button，最后才退到任意元素。盲选第一个会选中输入框，点了弹窗照旧
      //    （踩过一次：连点 5 轮「API 密钥」输入框，弹窗纹丝不动）。
      const SKIP = /稍后|跳过|以后|知道了|我知道了|继续|确定|关闭|取消|好$/;
      const closer =
        ov.clickable.find((b) => b.tag === 'button' && SKIP.test(b.text || '')) ??
        ov.clickable.filter((b) => b.tag === 'button').pop() ??
        ov.clickable[0];
      if (!closer) { console.log('    ⚠️ 没有可点的东西，可能是纯加载蒙层，退出'); return false; }
      const closed = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', sel: closer.sel })})`);
      console.log(`    点「${closer.text || closer.sel}」: ok=${closed?.ok} onTarget=${closed?.onTarget}`);
      if (closed?.onTarget === false) console.log(`      hint: ${closed.hint}`);
      await sleep(800);
    }
    return false;
  };

  // ── 把面板收起来（重载**不等于**回到基态）─────────────────────────
  //
  // ⚠️ 这是本轮踩到的第 5 个坑，也是最阴的一个：**前端会把右侧边栏的打开状态
  //    持久化**（大概是 localStorage / 服务端 profile）。所以 `Page.navigate`
  //    重载之后，右侧边栏**自己又开着了**，那个按钮的 aria 也从
  //    「打开右侧边栏」变成了「收起右侧边栏」—— 于是照 `{aria:'打开右侧边栏'}`
  //    去点会报「找不到要点击的元素」，而屏幕上明明有个侧边栏。
  //
  //    症状有迷惑性：图谱里「右侧边栏」那一屏单独跑是 ✅，排在「终端」屏**之后**
  //    跑就 ❌ —— 因为终端屏把侧边栏打开了，下一屏重载后它还在。
  //    **屏与屏之间有状态残留**，这正是「每屏都从基态重来」这条设计要防的事，
  //    但重载并不足以回到基态。
  //
  //    所以基态 = 重载 **且** 把面板收掉。收的时候用面板自己的关闭按钮
  //    （「收起右侧边栏」/「关闭」），**不是**去关掉面板本身（它是屏幕不是遮挡）。
  //
  // ⚠️⚠️ 但「收起右侧边栏」**不重置内容页**！右侧边栏的内容页也是持久化的，
  //    实测：进了「工作区文件」之后收起再打开，**还是「工作区文件」**，启动页
  //    那两个入口（新建终端 / 工作区文件）不再出现 —— 于是下一屏 `tap「新建终端」`
  //    报 `ok:false 找不到要点击的元素`。
  //    **内容页自己的「关闭」才重置内容页**（点了它侧边栏整个收起，再打开就回到
  //    启动页）。所以关闭按钮的**首选**是「关闭」，「收起右侧边栏」只作兜底 ——
  //    顺序反了就会带着上一屏的内容页跑下一屏。
  //    ⚠️ 启动页的侧边栏**没有**「关闭」（只有分栏 / 退出全屏 / 收起右侧边栏），
  //    所以「关闭」找不到时自然会落到兜底上，两条路都覆盖到了。
  const collapsePanels = async (tag) => {
    for (let i = 0; i < 3; i++) {
      const ov = await cdp.eval(`(${source})({action:"overlay"})`);
      if (ov?.kind !== 'panel') return;
      const pool = ov.clickable ?? [];
      // ⚠️ 关闭按钮要**按优先级挑**，两个坑叠在一起：
      //    ① 「退出全屏」排在前面，拿第一个正则命中的就用会点到它 —— 面板照旧
      //       开着（它只切换全屏态），下一轮又拿到同一个面板，**绕圈**（实测踩过）；
      //    ② 「收起右侧边栏」虽然能收起面板，但**不重置内容页**（见上）。
      //    所以顺序是：内容页的「关闭」→ 启动页的「收起右侧边栏」→ 泛化「关闭/返回」。
      const closer =
        pool.find((b) => /^关闭$/.test((b.text || '').trim())) ??
        pool.find((b) => /收起.*边栏|收起侧边栏|收起面板/.test(b.text || '')) ??
        pool.find((b) => /^返回$/.test((b.text || '').trim())) ??
        pool.find((b) => /关闭|返回/.test(b.text || ''));
      if (!closer) {
        console.log(`  ${tag}⚠️ 面板 ${ov.panel?.cls || ov.panel?.tag} 上没有关闭按钮，收不掉`);
        return;
      }
      console.log(`  ${tag}收起面板「${closer.text}」`);
      await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', sel: closer.sel })})`);
      // ⚠️ 收起是**带动画的**（侧栏滑出）。动画没落定就点「打开右侧边栏」，
      //    按钮还处在过渡态（aria 仍是「收起右侧边栏」），于是报
      //    「找不到要点击的元素」—— 实测「右侧边栏」屏就是这么挂的。
      await sleep(1600);
    }
  };

  console.log(`打开真实官方前端: ${url.slice(0, 60)}…`);
  const boot = await resetToBase();
  if (boot) console.log(`  已挂载：${boot.nodes} 个节点，title=${boot.title}`);
  await sleep(2500); // 给懒加载 chunk 一点时间

  // 装探针（与原生侧完全相同的调用方式：函数表达式直接调用）
  const installed = await cdp.eval(`(${source})({action:"ping"})`);
  console.log(`探针 ping: ${JSON.stringify(installed)}  (VERSION=${probeVersion})`);

  // ⚠️ 「界面加载出来了」不等于「mobile-ui 适配生效了」。
  //    mobile-ui 的客户端半靠 `dsh-client-modules` 扫 `dsh.client` 声明编入，
  //    挂载失败时**不报错**：宿主半照常 apply，页面照常渲染，只是那些
  //    `[data-mobile]` 规则没了、界面静默退回桌面布局。所以这里显式量一下
  //    适配是否生效 —— 否则会拿着一张桌面布局的坐标当地图用。
  const adapt = await cdp.eval(`(() => {
    const all = document.querySelectorAll('*');
    let marked = 0;
    for (const el of all) if (el.hasAttribute('data-mobile')) marked++;
    const styles = [...document.querySelectorAll('style')].map(s => s.textContent || '').join('');
    return {
      dataMobileNodes: marked,
      hasMobileCss: styles.includes('[data-mobile]'),
      mobileCssRules: (styles.match(/\\[data-mobile\\]/g) || []).length,
      viewport: innerWidth + 'x' + innerHeight,
    };
  })()`);
  console.log(`mobile-ui 适配: data-mobile 节点=${adapt.dataMobileNodes}, ` +
    `CSS 规则=${adapt.mobileCssRules} 条, 视口=${adapt.viewport}` +
    (adapt.mobileCssRules === 0 ? '  ⚠️ 适配未生效（拿到的是桌面布局坐标）' : '  ✅'));

  console.log('\n── 第 0 步：清掉遮挡层 ──');
  await dismissOverlays('');

  const viewport = await cdp.eval(`({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})`);

  // ⚠️ 编辑器的定位**不能靠 placeholder 文案**：空会话（Hero）页是「描述你想要构建的
  //    内容…」，一旦有了会话就变成「发消息或创建任务…」—— 照文案找会「找不到
  //    composer」，而 composer 明明就在那儿（实测踩过两次）。
  //    直接查 `[contenteditable="true"]`，与 `ui` 工具链在真机上用的是同一个抓手。
  const EDITOR_SEL = '[contenteditable="true"]';
  const findEditor = async () => {
    const r = await cdp.eval(`(${source})(${JSON.stringify({ action: 'find', sel: EDITOR_SEL })})`);
    return r?.ok === true ? r.element : null;
  };

  // ── 「种」一个会话：某些屏幕只在有会话时才存在 ────────────────────
  //
  // ⚠️ 会话抽屉就是这种：Hero 页上官方侧栏还没渲染，点「打开导航」没反应。
  //    发一条消息让页面进入会话态，抽屉才开得出来。见 SCREENS 里 drawer 的注释。
  //
  // ⚠️⚠️ **真机上绝不走这条路。** 真机上用户的 `dsh.sessions.current` 本来就指向
  //      一个活跃会话，`resetToBase()` 一还原就是会话态 —— 页面已经在抽屉能开出来的
  //      状态了。而 `seedSession()` 会**真发一条消息进用户的会话日志**
  //      （「图谱种会话」），那是用户醒来能在会话里翻到的痕迹，违反「不能被用户看出」。
  //      所以真机走 `ensureSessionState()`：只**断言**在会话态，不写任何东西。
  const seedSession = async () => {
    const editor = await findEditor();
    if (!editor) { console.log('  ⚠️ 找不到 composer，无法种会话'); return false; }
    const seedText = '图谱种会话';
    await cdp.eval(`(${source})(${JSON.stringify({ action: 'type', sel: editor.sel, value: seedText })})`);
    await sleep(900); // execCommand 异步生效，见 setValue 的注释
    await cdp.eval(`(${source})({action:"tap", aria:"发送消息"})`);
    await sleep(3500); // 等消息落地、侧栏渲染出来
    console.log(`  种会话完成（composer 已提交）`);
    return true;
  };

  /** 真机版「种会话」：**不写任何东西**，只确认还原后确实落在用户会话上。 */
  const ensureSessionState = async () => {
    const cur = await cdp.eval(`(function(){ try { return JSON.parse(localStorage.getItem('dsh.sessions.current')||'{}').sessionId || null; } catch(e){ return null; } })()`);
    const ok = ORIGINAL_CURRENT !== null && cur === ORIGINAL_CURRENT;
    console.log(ok
      ? `  真机复用用户会话 ${cur}（未发送任何消息）`
      : `  ⚠️ 当前会话是 ${cur}，与用户的 ${ORIGINAL_CURRENT} 不符 —— 抽屉可能开不出来`);
    return ok;
  };

  // ── 逐屏采集 ──────────────────────────────────────────────────────
  const screens = [];
  const TO_RUN = PARTIAL ? SCREENS.filter((s) => ONLY.includes(s.id)) : SCREENS;
  if (PARTIAL) {
    console.log(`\n⚠️ 只跑 ${TO_RUN.length} 屏（UIMAP_ONLY=${ONLY.join(',')}）——` +
      ` 本次结果不会覆盖 ui-map-real-frontend.json`);
    if (TO_RUN.length === 0) {
      console.error(`UIMAP_ONLY 里的屏一个都不认识。可用: ${SCREENS.map((s) => s.id).join(', ')}`);
      process.exit(2);
    }
  }
  for (const spec of TO_RUN) {
    console.log(`\n══ 屏幕「${spec.title}」(${spec.id}) ══`);

    // 非首屏才需要重置：首屏已经在基态上了，重载一次纯属浪费。
    //
    // ⚠️ 真机上这个「省一次」不成立：首屏 `hero` 的 `enter` 是**空的**（它本来
    //    就是基态），但用户在设备上**有活跃会话**，不清 `dsh.sessions.current`
    //    它根本不是 Hero 页。所以真机模式下 `EMPTY_SESSION_ONLY` 的屏一律重置。
    const needReset = spec.enter.length > 0 || (ON_DEVICE && EMPTY_SESSION_ONLY.has(spec.id));
    if (needReset) {
      const empty = EMPTY_SESSION_ONLY.has(spec.id);
      await resetToBase(empty);
      if (empty) console.log('  这一屏只在空会话态存在 —— 已清 dsh.sessions.current 并重载');
      await sleep(ON_DEVICE ? 3500 : 2500);
      await cdp.eval(`(${source})({action:"ping"})`);
      await dismissOverlays('  ');
      // ⚠️ **重载不等于回到基态**：前端会持久化右侧边栏的打开状态，重载后它
      //    自己又开着，入口按钮的 aria 也随之从「打开右侧边栏」变成「收起右侧边栏」。
      //    不收掉的话，这一屏的 `enter` 第一步就会报「找不到要点击的元素」。
      //    见 `collapsePanels` 的注释。
      await collapsePanels('  ');
    }
    // `seed:true` 的屏要先进入会话态（见 seedSession 的注释）
    //
    // ⚠️ 真机**不能**走 `seedSession()`（它会真发一条消息进用户会话日志），
    //    改用 `ensureSessionState()` 只做断言 —— 真机上用户本来就在会话里。
    if (spec.seed) await (ON_DEVICE ? ensureSessionState() : seedSession());
    // ⚠️ 种会话之后页面刚重渲染过，等它稳定再点 —— 紧接着就点「打开导航」会点到
    //    还没挂载的按钮上（`tap` 返回 `ok:false` 找不到元素，或点了个空壳）。
    if (spec.seed && !ON_DEVICE) await sleep(2000);

    // ── 进入 + 校验：没等到特征元素就**重点一次** ──────────────────
    //
    // ⚠️ **不是所有屏幕都能一次点进去。** 实测会话抽屉要点**两次**「打开导航」：
    //    第一次点完侧栏从 `x:-137` 滑到 `x:-340`（更外面，仍是关着的），第二次才
    //    真正展开（`class` 加上 `mp-drawer-open`、`x:0`、「收起侧边栏」出现）。
    //    根因是 mobile-ui 的时序竞态 —— 会话刚建立时官方侧栏组件还没渲染完，
    //    `mpToggleDrawer()` 那一下切换落空了。
    //
    //    这正是 `ui` 工具链在真机上**必须**走的路径：点 → `wait` 等特征元素 →
    //    没等到就**再点一次**（不是重载重来）。所以这里照做。
    //
    //    ⚠️ 重试必须是**原地重点**：重置回基态再点一次会撞上同一个竞态，永远
    //    进不去（试过，三次全失败）。
    const steps = [];
    let entered = true;
    for (const step of spec.enter) {
      const r = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', ...step })})`);
      const label = step.text || step.aria || step.sel;
      // ⚠️⚠️ **`offscreen` 不是失败条件，只是警告** —— 这一条是实测改过来的。
      //
      //    原判据把 `offscreen === true` 也算失败，真机上直接把 **10 屏**卡死了
      //    （9 个设置屏的第一步「设置」+ `plugins-panel` 的「插件」）。
      //    但实测证据相反：那一步 `ok=true onTarget=true offscreen=true`，
      //    紧接着 `find` 判据「通用设置」**找得到**，dump 里设置面板的元素
      //    （返回/通用设置/模型/内置插件/…）全在 —— 面板真的开了。
      //
      //    原因是**探针的 tap 是程序化派发**（`el.click()`），不是真手指。
      //    官方侧栏收起时被 translate 到 `x:-312`，里面那个「设置」中心点
      //    落在视口外（`offscreen:true`），但它在 DOM 里、`onClick` 照常触发。
      //    所以「离屏」只说明**真手指点不到**，而本工具链从来不靠真手指。
      //
      //    判定权交给 `verified`（下面那段 `find` 复核）：它才是「到底进没进去」
      //    的判据。离屏 + 判据成立 = 这条路走得通，照记；离屏 + 判据不成立 =
      //    重试逻辑接管（见下面 `enterAttempts` 那段）。
      const stepOk = r?.ok === true && r?.onTarget !== false;
      const off = r?.offscreen === true;
      console.log(`  tap「${label}」 ok=${r?.ok} onTarget=${r?.onTarget} offscreen=${r?.offscreen ?? false}` +
        (stepOk ? (off ? '  ℹ️ 元素在视口外，但程序化点击有效（判据为准）' : '') : `  ⚠️ ${r?.error ?? r?.hint ?? ''}`));
      steps.push({
        target: label, spec: step, ok: stepOk,
        // 离屏这个事实**记进图谱**：读的人要知道这一步不是「屏幕上看得到再点」。
        offscreen: off || undefined,
        error: r?.error ?? null, hint: r?.hint ?? null,
      });
      if (!stepOk) entered = false;
      await sleep(900);
    }

    let verified = null;
    if (spec.expect && entered) {
      const key = spec.expect.text ?? spec.expect.prefix ?? spec.expect.aria ?? spec.expect.sel;
      const tries = spec.enterAttempts ?? 2;
      for (let attempt = 1; attempt <= tries; attempt++) {
        const w = await cdp.eval(`(${source})(${JSON.stringify({
          action: 'wait', ...spec.expect, timeout: 2500,
        })})`);
        verified = w?.satisfied === true;
        console.log(`  等「${key}」: ${verified ? '✅ 等到了' : '✗ 没等到'}` +
          (verified ? '' : `（第 ${attempt}/${tries} 次）`));
        if (verified) break;
        if (attempt === tries) break;
        // 原地重点最后一步 —— 与真机上「没等到就再点一次」完全一致
        const last = spec.enter[spec.enter.length - 1];
        const again = await cdp.eval(`(${source})(${JSON.stringify({ action: 'tap', ...last })})`);
        console.log(`  ↻ 重点「${last.text || last.aria}」 ok=${again?.ok} onTarget=${again?.onTarget}`);
        steps.push({ target: (last.text || last.aria) + '（重试）', spec: last, ok: again?.ok === true, retry: true });
        await sleep(1200);
      }
    }

    const dump = await cdp.eval(`(${source})({action:"dump", limit:400})`);
    if (!dump || dump.ok !== true) throw new Error(`dump 失败: ${JSON.stringify(dump)}`);
    const elements = dump.elements ?? [];
    const labelled = elements.filter((e) => e.text || e.testid || e.aria);

    // 「真的进去了吗」——最终以**探针的实际查找结果**为准。
    // 只看 tap 的 ok 不够：点了没生效时 ok 照样是 true（遮挡/屏幕外就是这样）。
    // 循环里那次 `wait` 决定「要不要再试一次」，这里决定「记进图谱的是什么」。
    //
    // ⚠️ 复核**走探针的 `find`**，而不是在 `dump` 的元素列表里自己比对。
    //    原因有两层：
    //
    //    ① **口径必须与 `wait` 一致**（这一条原来就有）。`wait` 内部就是
    //       `locate(cmd)`，所以直接用同一个 `find` 是最彻底的「同口径」——
    //       自己复刻一套匹配规则，迟早会在某个 flag 上对不齐。
    //
    //    ② **`dump` 根本表达不了 `sel` 判据**（这一条是本节修 bug 时发现的）。
    //       `dump` 只收 `INTERACTIVE` 候选集（button/a/input/[role]…），而
    //       `terminal` 屏的判据 `.xterm-screen` 是一个**普通 `<div>`**，
    //       不在候选集里 ⇒ 自己比对必然「找不到」，把 ✅ 覆盖成 ❌。
    //       （原实现比的是 `e.sel === ex.sel`，而 `e.sel` 是完整 cssPath
    //         `html>body>div>…`，与 `.xterm-screen` 这种简短选择器永不相等。）
    //
    //    ⚠️ `clickable:true` 的语义在 `find` 的返回里是 `covered` / `offscreen`
    //       两个字段，与 `dump` 同名，照抄即可。
    if (spec.expect) {
      const ex = spec.expect;
      const r = await cdp.eval(`(${source})(${JSON.stringify({ action: 'find', ...ex })})`);
      const el = r?.ok === true ? r.element : null;
      const byDump = !!el && !(ex.clickable === true && (el.covered || el.offscreen));
      if (byDump !== verified) {
        console.log(`  ⚠️ 复核与 wait 不一致（wait=${verified} find=${byDump}），以复核为准`);
      }
      verified = byDump;
      console.log(`  校验: ${verified ? '✅ 已进入' : '❌ 没进去'}（找 ${fmtExpect(ex)}）`);
    }
    if (dump.overlay) console.log(`  ⚠️ 该屏仍有遮挡层: ${dump.warning}`);

    console.log(`  共 ${elements.length} 个可交互元素（有标签 ${labelled.length}，` +
      `被遮挡 ${dump.blocked ?? 0}，屏幕外 ${dump.offscreen ?? 0}）`);
    for (const e of labelled.slice(0, 60)) {
      const c = e.center ? `${Math.round(e.center.x)},${Math.round(e.center.y)}` : '-';
      const rr = e.rect ? `${Math.round(e.rect.w)}×${Math.round(e.rect.h)}` : '-';
      console.log(`    ${String(e.text || e.aria || e.testid).slice(0, 30).padEnd(32)} ${e.tag.padEnd(9)} ` +
        `CSS(${c.padEnd(9)}) ${rr.padEnd(9)}` +
        (e.covered ? ' ⛔被遮挡' : '') + (e.offscreen ? ' 📴屏幕外' : '') + `  ${e.sel}`);
    }

    screens.push({
      id: spec.id,
      title: spec.title,
      // ⚠️ `expect` **必须落进 JSON**，不能只活在生成器里。它是「这一屏真的进去了吗」
      //   的判据，而 `tap.ok` 单独用不了：点一个点不动的东西仍返回 `ok:true`
      //   （那正是遮挡/屏幕外两种症状），多屏共有的文案更会让从没进过的屏假通过。
      //   `tools/build-uiagent-screens.mjs` 把这份判据内建进插件 —— 真机上的模型
      //   就是靠它知道「我到底在哪一屏」，丢了它 `goto` 就成了盲开车。
      expect: spec.expect ?? null,
      enter: steps,
      entered,
      verified,
      total: elements.length,
      labelled: labelled.length,
      blocked: dump.blocked ?? 0,
      offscreen: dump.offscreen ?? 0,
      elements: labelled,
    });

    // ⚠️ **空会话态跑完立刻把 current 写回去**，不能攒到最后。
    //
    //    真机实测：清掉 `dsh.sessions.current` 之后，前端（`dsh-client-ui-workspace`
    //    的 `reuseOrCreateBlank`）会自己挑一个或**新建一个 blank 会话**并写回这个键。
    //    不立刻还原的话，下一屏 `resetToBase()` 拿 `ORIGINAL_LS` 一写，看起来是
    //    「还原了」，但那一屏的 `enter` 又会把它清掉 —— 而最终收尾时写回去的
    //    `current` 一旦被前端判定为「不可复用」，它就会再造一个新的，
    //    用户的界面就永久停在新会话页上了（实测踩过一次）。
    if (ON_DEVICE && EMPTY_SESSION_ONLY.has(spec.id)) {
      await restoreCurrentSession('  ');
    }
  }

  // ── 回到基态，做交互实测 ──────────────────────────────────────────
  //
  // ⚠️ 判据必须是「**业务状态真的变了**」，节点数不是判据 —— 两个反例都踩过：
  //    ① 抽屉靠 CSS transform/opacity 显隐，DOM 节点数一个都不增；
  //    ② `.mp-drawer-mask` 是**懒建且常驻**的（`mpMask()` 建完就不删），
  //       所以「它存在」也不代表抽屉开着。
  //
  //    而「点汉堡展开抽屉」本身在空会话页（Hero）**根本不是有效判据**：
  //    此时官方侧栏尚未渲染（`.pI_x6G_sidebarCol` 不存在），
  //    `mpToggleDrawer()` 里 `mpOfficialToggle()` 返回 null、
  //    `if (btn) btn.click()` 不执行 → 点汉堡本来就没反应。那是 mobile-ui
  //    插件在 Hero 页的缺陷，探针如实报 `onTarget:true` 并没有说谎。
  //
  //    所以用 composer 走一遍**真正会改变界面**的链路：
  //    type 写入 → 发送按钮从禁用变可用（证明 React 认账）→ tap 发送 →
  //    消息出现在界面上。这三步任何一步失败都说明探针没真的驱动到应用。
  //
  // ⚠️ **重载一次是必须的，不能只靠「关掉面板」**：上一屏（右侧边栏）留下的
  //    面板会盖住 composer —— 实测就是它导致「找不到 composer 编辑器」。
  //    重载把整个页面状态清干净，比逐个去关面板可靠得多。
  //
  // ⚠️⚠️ **真机模式整段跳过** —— 这段会**真的发一条消息**：
  //    `tap 发送` 之后 composer 里的文本就作为一条用户消息提交出去了。
  //    在 PC 的临时实例上那无所谓；在**用户正在用的设备**上，那是往他的会话里
  //    塞一条他说没说过的话 —— 而且会话日志是持久的，删不掉。
  //    这与「不能被用户看出」直接冲突，所以真机模式**不做**这一步。
  //
  //    代价：真机模式不再有「探针确实驱动了应用」的正面证据。那个证据由
  //    `tools/uiagent-device-write.mjs` 单独负责（它点的是可逆的界面按钮，
  //    并且跑完会还原）。
  let typed = { ok: false };
  let mid = { sendDisabled: null };
  let sentOk = false;
  if (ON_DEVICE) {
    console.log('\n══ 交互实测：真机模式已跳过 ══');
    console.log('  （这段会真发一条消息、污染用户会话，交给 uiagent-device-write.mjs 做）');
  } else {
  console.log('\n══ 交互实测（探针真的驱动了应用）══');
  await resetToBase();
  await sleep(2500);
  await cdp.eval(`(${source})({action:"ping"})`);
  await dismissOverlays('  ');
  await collapsePanels('  ');

  const COMPOSER = `(() => {
    const ce = document.querySelector('[contenteditable="true"]');
    const ta = document.querySelector('textarea');
    const input = ce || ta;
    const sendBtn = [...document.querySelectorAll('button')].find(b =>
      /发送|send/i.test((b.getAttribute('aria-label') || '') + (b.textContent || '')));
    return {
      editorKind: ce ? 'contenteditable' : (ta ? 'textarea' : null),
      value: input ? (input.value !== undefined ? input.value : input.innerText) : null,
      sendDisabled: sendBtn ? (sendBtn.disabled === true || sendBtn.getAttribute('aria-disabled') === 'true') : null,
    };
  })()`;

  const before = await cdp.eval(COMPOSER);
  console.log(`输入前: 编辑器=${before.editorKind} 值=${JSON.stringify(before.value)} 发送键禁用=${before.sendDisabled}`);

  const editor = await findEditor();
  if (!editor) throw new Error('找不到 composer 编辑器（' + EDITOR_SEL + '）');

  const TEXT = '探针自检：这条消息由 ui-probe 写入并发送';
  typed = await cdp.eval(`(${source})(${JSON.stringify({ action: 'type', sel: editor.sel, value: TEXT })})`);
  // ⚠️ 必须**等一下再读**：`execCommand` 对富文本编辑器（官方 composer 是
  //    Lexical）是**异步生效**的，同步读必然为空。立即断言会把成功误判成失败。
  await sleep(900);
  mid = await cdp.eval(COMPOSER);
  console.log(`type 写入: ok=${typed.ok} 方式=${typed.how} applied=${typed.applied}`);
  console.log(`  输入框已填入: ${String(mid.value).length > 0 ? '✅' : '❌'}`);
  console.log(`  发送键 禁用→可用: ${mid.sendDisabled === false ? '✅（React 真的认账了）' : `❌（sendDisabled=${mid.sendDisabled}）`}`);

  if (mid.sendDisabled === false) {
    const nodesBefore = await cdp.eval(`document.querySelectorAll('*').length`);
    const sent = await cdp.eval(`(${source})({action:"tap", aria:"发送消息"})`);
    console.log(`tap 发送: ok=${sent?.ok} onTarget=${sent?.onTarget}`);
    // ⚠️ `onTarget:false` 时**必须**把命中者打出来：只报「没点到」而不报「点到
    //    了谁」，就无从判断是探针误报还是真被挡住了 —— 而这两种情况的处置
    //    完全相反（前者要修判据，后者要关遮挡层）。
    if (sent?.onTarget === false) {
      console.log(`  ⚠️ 点击没落到目标上，实际命中的是: ` +
        (sent.hitTopmost ? `${sent.hitTopmost.tag}.${sent.hitTopmost.cls || ''} ` +
          `CSS(${Math.round(sent.hitTopmost.center?.x ?? 0)},${Math.round(sent.hitTopmost.center?.y ?? 0)}) ` +
          `文本=「${sent.hitTopmost.text || ''}」` : '(取不到)'));
      console.log(`  目标元素: ${sent.element?.tag} CSS(${Math.round(sent.element?.center?.x ?? 0)},` +
        `${Math.round(sent.element?.center?.y ?? 0)}) 文本=「${sent.element?.text || ''}」`);
    }
    await sleep(2500);
    const post = await cdp.eval(`(() => {
      const ce = document.querySelector('[contenteditable="true"]');
      return {
        nodes: document.querySelectorAll('*').length,
        editor: ce ? (ce.innerText || '') : null,
        hasMsg: document.body.innerText.includes(${JSON.stringify(TEXT)}),
      };
    })()`);
    console.log(`  输入框已清空: ${post.editor === '' ? '✅' : `❌（${JSON.stringify(String(post.editor).slice(0, 30))}）`}`);
    console.log(`  消息出现在界面上: ${post.hasMsg ? '✅ 探针真的把消息发出去了' : '❌'}`);
    console.log(`  节点数 ${nodesBefore} → ${post.nodes}（仅作参考，不是判据）`);
    sentOk = post.hasMsg === true;
  }
  } // ← 关闭「非真机」的交互实测分支

  // ── 真机：还原现场 ────────────────────────────────────────────────
  //
  // ⚠️ 这一步是**任务约束**（「不能被用户看出」）的落地点，不是可选的收尾：
  //    采集过程点开过各种面板，前端把其中一部分**持久化**了。不还原的话，
  //    用户解锁手机时会看到界面停在一个他没打开过的面板上。
  //
  //    还原 = 把跑之前的 localStorage 快照原样写回 + 重载一次，**然后回读核对**。
  //    「写了」不等于「写对了」，所以末尾 MUST 回读比对。
  //
  // ⚠️⚠️ **还原 localStorage 还不够 —— 还要归档掉「空会话态」跑出来的那个 blank 会话。**
  //
  //    实测踩到（2026-10-13）：跑完全量审计后，用户会话列表里多了一条「新会话」。
  //    它是**服务端持久化**的，localStorage 还原干净了它也还在，用户在列表里
  //    照样看得见 —— 这正是「不能被用户看出」要防的。
  //
  //    来源：`EMPTY_SESSION_ONLY` 那三屏清掉 `dsh.sessions.current` 后，
  //    `dsh-client-ui-workspace` 的 `reuseOrCreateBlank()` 找不到可复用的 blank
  //    会话就**新建一个**（`lib/client.js` L808）。它一落盘就是用户列表里的一条。
  //
  //    所以收尾要拿「跑之前的会话 id 集合」做差集，把**新增的 blank 会话**
  //    逐个归档（`workspace/archiveSession`）—— 归档是官方提供的隐藏手段，
  //    不删数据、可撤销，比硬删安全。
  if (ON_DEVICE) {
    console.log('\n══ 还原真机现场 ══');
    await cdp.eval(`(() => {
      var keep = ${JSON.stringify(ORIGINAL_LS ?? {})};
      for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
      for (var k in keep) localStorage.setItem(k, keep[k]);
      return 1;
    })()`);
    const after = JSON.parse(await cdp.eval(LS_DUMP));
    await cdp.send('Page.navigate', { url });
    await sleep(6000);
    const expectKeys = Object.keys(ORIGINAL_LS ?? {}).sort();
    const gotKeys = Object.keys(after).sort();
    const same = JSON.stringify(expectKeys) === JSON.stringify(gotKeys) &&
      expectKeys.every((k) => (ORIGINAL_LS ?? {})[k] === after[k]);
    console.log(`  localStorage 回读核对: ${same ? '✅ 与跑之前逐键一致' : '❌ 不一致'}`);
    if (!same) {
      console.log(`    跑之前: ${expectKeys.join(', ')}`);
      console.log(`    还原后: ${gotKeys.join(', ')}`);
    }

    // 归档「本次跑出来的 blank 会话」
    if (ORIGINAL_SESSION_IDS) {
      const post = async (method, payload) => {
        await cdp.eval(`(() => { window.__rpc = null;
          fetch('/api/${method}', { method:'POST', headers:{'content-type':'application/json'},
            body: JSON.stringify({ type:'client-request', rpcId:'r'+Date.now(),
              method:${JSON.stringify(method)}, payload:${JSON.stringify(payload)} }) })
            .then(r=>r.text()).then(t=>{ window.__rpc=t; }).catch(e=>{ window.__rpc='ERR '+e; });
          return 1; })()`);
        for (let i = 0; i < 40; i++) {
          await sleep(250);
          const v = await cdp.eval('window.__rpc');
          if (v) { try { return JSON.parse(v); } catch { return { raw: v }; } }
        }
        return null;
      };
      const listRes = await post('session/list', { args: { _request: {} } });
      const items = listRes?.result?.value?.items ?? [];
      const fresh = items.filter((it) => !ORIGINAL_SESSION_IDS.has(it.sessionId));
      const blanks = fresh.filter((it) => it.blank === true);
      console.log(`  会话数: 跑之前 ${ORIGINAL_SESSION_IDS.size} → 现在 ${items.length}` +
        `（新增 ${fresh.length}，其中空会话 ${blanks.length}）`);
      for (const it of blanks) {
        const arc = await post('workspace/archiveSession', { args: { request: { sessionId: it.sessionId } } });
        const okArc = arc?.result?.ok === true;
        console.log(`  ${okArc ? '✅' : '❌'} 归档本次跑出的空会话 ${it.sessionId}`);
      }
      // 非空的新会话**不碰** —— 那可能是用户自己开的，宁可留着也不能误删。
      const nonBlank = fresh.filter((it) => it.blank !== true);
      if (nonBlank.length > 0) {
        console.log(`  ℹ️ 另有 ${nonBlank.length} 个新增的**非空**会话，未动（可能是用户自己建的）:`);
        for (const it of nonBlank) console.log(`     ${it.sessionId}`);
      }
    }
  }

  const hero = screens.find((s) => s.id === 'hero') ?? screens[0];
  const report = {
    url: url.replace(/token=[^&]+/, 'token=***'),
    viewport,
    dpr: viewport.dpr,
    probeVersion,
    // ⚠️ **表是从哪采的** —— 这个字段决定「坐标偏移算不算缺陷」。
    //
    //    `device` = 真机采的：表里的坐标就是那台设备的 CSS 坐标，
    //               真机审计时偏移应当接近 0，偏了就是真缺陷。
    //    `pc`     = PC 隔离实例采的：视口（360×780）、类名（构建 hash）、
    //               用户配置全都与真机不同 —— 偏移是**预期**的，不是缺陷。
    //
    //    少了这个字段，审计脚本没法区分「表烂了」与「表和真机本来就不同源」，
    //    只能一律报成失败（这正是第一版审计报出 85 个偏移那次）。
    source: ON_DEVICE ? 'device' : 'pc',
    screens,
    // ⚠️ 顶层这几个字段指向 **hero 屏**，是首版单屏脚本的语义，保留下来是为了
    //    不打断已经依赖它们的用法。要看别的屏请走 `screens[]`。
    total: hero.total,
    blocked: hero.blocked,
    offscreen: hero.offscreen,
    labelled: hero.labelled,
    elements: hero.elements,
    interaction: {
      typed: typed.ok === true,
      reactAccepted: mid.sendDisabled === false,
      sent: sentOk,
    },
  };
  const out = PARTIAL
    ? join(__dirname, 'ui-map-real-frontend.partial.json')
    : join(__dirname, 'ui-map-real-frontend.json');
  writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
  console.log(`\n已写出 ${out}`);
  console.log(`视口: ${JSON.stringify(report.viewport)}`);
  console.log(`\n══ 图谱总览（${screens.length} 屏）══`);
  for (const s of screens) {
    console.log(`  ${s.verified === false ? '❌' : '✅'} ${s.title.padEnd(24)} ` +
      `标签 ${String(s.labelled).padStart(3)} 个  被遮挡 ${String(s.blocked).padStart(2)}  屏幕外 ${String(s.offscreen).padStart(2)}` +
      (s.enter.length ? `  路径: ${s.enter.map((x) => x.target).join(' → ')}` : '  路径: （基态）'));
  }
} finally {
  // 真机模式这两样都不存在（没 spawn Chrome、没建临时 profile）
  if (child) { try { child.kill(); } catch { /* ignore */ } }
  if (profile) { try { rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ } }
  // ⚠️ 必须显式关掉 CDP 的 WebSocket：真机模式下没有 Chrome 子进程可杀，
  //    这条 socket 会把 node 的事件循环一直撑住 —— 脚本早已打完最后一行日志、
  //    结果也写进了 JSON，进程却永不退出（实测挂了一个多小时）。后果不只是
  //    "多一个僵尸进程"：它的 stdout 重定向还占着日志文件，下一次采集直接
  //    在 `*> tools\_uimap-run.log` 这一步就失败，看起来像"采集没跑"。
  if (cdp?.ws) { try { cdp.ws.close(); } catch { /* ignore */ } }
}
