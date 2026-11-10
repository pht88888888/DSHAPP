window.__ModuleLoader__.load({
	id: "dsh-android-mobile",
	factory: (require) => {
		"use strict";
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

				// ── CSS ──────────────────────────────────────────────────────────────
		// Scoped styles injected as one style tag (same pattern as ui-responsive).
		// Class names below are the stable ones observed in the 0.13.0-preview
		// snapshot bundles (wSkVaW_* = conversation root, uV2eYG_* = composer
		// StatsLine, Nqubda_* = cordis panel). Where possible we key off ARIA/data
		// attributes instead so the rules survive minor bundle changes.
		const CSS = `
/* 4. 去掉全局点击/聚焦蓝色闪烁（WebView tap highlight + focus ring） */
[data-mobile] * {
  -webkit-tap-highlight-color: transparent !important;
}

/* 4c. 长按手势选择控制：
     全局禁用 touch-callout + user-select（消灭长按弹出系统菜单和文字选中）。
     例外：输入元素允许原生长按行为。
     非消息区域（header/sidebar/settings等）保持 disabled，体验接近原生 app。 */
[data-mobile] * {
  -webkit-touch-callout: none !important;
  user-select: none !important;
  -webkit-user-select: none !important;
}
[data-mobile] input, [data-mobile] textarea, [data-mobile] [contenteditable] {
  -webkit-touch-callout: default !important;
  user-select: text !important;
  -webkit-user-select: text !important;
}

/* 4b. 隐藏所有滚动条（移动端太丑） */
[data-mobile] *::-webkit-scrollbar {
  display: none !important;
  width: 0 !important;
  height: 0 !important;
}
[data-mobile] * {
  scrollbar-width: none !important;
}

/* 抽屉让出状态栏（官方侧栏顶部 logoRow 需要安全区） */
[data-mobile] .pI_x6G_sidebarCol {
  padding-top: var(--dsh-android-system-top, 0px);
}
/* 内容列的顶部让位**不在 CSS 里做**（修复 58 的第一版就是死在这里，别再走回去）：
   ============================================================
   全面屏打开后内容列必须让出状态栏（窗口 content 由 [0,108] 变 [0,0]，
   状态栏直接压住汉堡与聊天页 header，实测汉堡 rect.top=3.75）。

   第一版在这里加了一条
     [data-mobile] .pI_x6G_centerCol { padding-top: var(--dsh-android-system-top, 0px) }
   结果**完全不生效**：同一个属性上已经有一条修复 66 加的
     [data-mobile] .pI_x6G_centerCol { padding-top: var(--mp-float-nav-h, 0px) !important }
   它带 !important，后加的规则压不过它（实测 getComputedStyle 仍是 0px）。

   也不能在 CSS 里写成 max(两条变量之和 / 取大者)：max() 要 Chrome 79，而本 App
   的目标内核是 **chrome74**（见 ⑯），老内核上整条声明会失效 ⇒ 全屏下顶部被啃掉，
   且只在老设备上复现。

   正解是**并进 --mp-float-nav-h 的语义**：让 mpSyncFloatNavGap() 在算它的时候
   把状态栏高度也纳入（JS 里 Math.max 不受内核版本限制）。那条 !important 规则
   于是不用动，两条让位需求由同一个变量统一表达。 */
/* 只去掉焦点轮廓，**不碰 box-shadow**。
   ⚠️ 曾经的写法是 [tabindex]:focus 上加 box-shadow: none，而官方输入卡片
   .uV2eYG_card 带 tabindex="-1"：发完消息输入框拿到焦点，卡片命中该规则，
   它那圈 box-shadow（边框感的来源）被清成 none —— 屏幕上的表现就是
   「输入框边框消失」，点一下空白处失去焦点才回来。阴影永远归官方管。 */
[data-mobile] button:focus, [data-mobile] button:focus-visible, [data-mobile] a:focus,
[data-mobile] [tabindex]:focus, [data-mobile] [tabindex]:focus-visible,
[data-mobile] input:focus, [data-mobile] textarea:focus, [data-mobile] select:focus {
  outline: none !important;
}

/* 2. 消息操作栏：隐藏点赞/有问题反馈按钮（保留 复制/分支/时间/用时） */
[data-mobile] button[aria-label="好的回答"],
[data-mobile] button[aria-label="有问题的回答"] {
  display: none !important;
}

/* 1. 会话头部：标题长时右侧 actions/utilities 溢出重叠（undo 快照徽章 vs session log 按钮）→ 换行。
   容器是 wSkVaW_headerActions（模式徽章+快照徽章+utilities 并排超出容器宽，flex nowrap 导致重叠）。
   让 headerActions 在 titleCluster 内收缩、换行，避免溢出与 headerUtilities 重叠。 */
/* 配合上面 header 的 pointer-events:none —— header 内部控件（标题、⋯ 菜单、
   各徽章）必须保持可点，否则整个顶栏都变成"看得见摸不着"。 */
/* ══ 设置面板：官方 0.2.2 把 .VOzbGW_panel 从 flex 布局换成了 CSS Grid ══════
   grid-template-columns: 300px 60px / areas "title header" "nav nav"
   而插件的整套改造（backBar、竖向 nav、二级页切换）全是按 flex 写的，
   flex-direction:column 对 grid 容器无效 ⇒ 返回条被挤到右上角 60px 那条缝里、
   官方 navTitle 占着左上角、nav 高度吃掉整屏、options 只剩 24px。
   这里把面板还原成纵向 flex，插件的既有逻辑即可照常工作。 */
[data-mobile] .VOzbGW_panel {
  display: flex !important;
  flex-direction: column !important;
}
/* 官方 navTitle 与插件 backBar 是重复的标题，藏掉官方的那个 */
[data-mobile] .VOzbGW_panel > .VOzbGW_navTitle {
  display: none !important;
}
/* header 在移动端本就无用（官方默认 display:none），显式保底 */
[data-mobile] .VOzbGW_panel > .VOzbGW_header {
  display: none !important;
}

[data-mobile] .wSkVaW_header * {
  pointer-events: auto !important;
}

[data-mobile] .wSkVaW_titleRow {
  flex-wrap: wrap;
  row-gap: 2px;
  align-items: flex-start;
}
[data-mobile] .wSkVaW_titleCluster {
  flex: 1 1 0%;
  min-width: 0;
  overflow: hidden;
}
[data-mobile] .wSkVaW_headerActions {
  flex-wrap: wrap;
  row-gap: 4px;
  flex: 1 1 auto !important;
  min-width: 0 !important;
  width: 100% !important;
}
[data-mobile] .wSkVaW_headerUtilities {
  margin-left: 0 !important;
}
[data-mobile] .wSkVaW_titleRow {
  gap: 2px 4px;
}
[data-mobile] .wSkVaW_headerUtilities {
  margin-left: 0 !important;
}

/* 3. composer StatsLine：隐藏 首token/tok/s 与 输入/输出 tokens 段（JS 按文本处理，
    这里兜底隐藏其分隔符与目标段）。
    3b. 同时收窄左右内边距 32px → 12px，给「轮·步 / LLM·工具 / 缓存」三组缩写文案
    留出余量（可用 334px vs 实测 270.6px），**保持单行不换行**。
    只加 [data-mobile] 前缀 → 桌面端布局完全不受影响。 */

/* 7. 设置页两级化（移动端）：横向滑动导航 → 竖向列表；一级=列表，二级=内容。
   NOTE: 全部用 JS inline style，不用 CSS 规则 —— 实测本 WebView（Chromium 114 +
   ui-responsive 的 html zoom:3）下，任何匹配设置 dialog 子树的 CSS 规则都会把
   dialog 推到屏幕外（x=-301，关闭按钮变 0 尺寸，无法关闭）。 */

/* 5. 审批面板（cordis）：移动端固定到 composer 上方 —— 定位全在 JS（positionApprovalPanel），
   此处不再注入 CSS（同上，避免 media CSS 触发布局 bug）。 */

/* 审批有内容时自动打开的遮罩提示：面板上缘阴影强化层级 */
[data-mobile] .Nqubda_panel[data-mp-approval] {
  box-shadow: var(--dsw-shadow-lv3, 0 8px 30px rgba(0, 0, 0, .25)) !important;
}

/* ══ 8. 移动端外壳（0.1.5 融合版）══════════════════════════════════════════
   0.1.5 官方 ui-layout 的窄屏形态是「把侧栏压成 56px 图标条」，正文只剩 334px；
   官方自带的抽屉/手机顶栏来自已被停用的 @dsh-android/dsh-client-ui-responsive
   （它在新版的槽位校验下会抛异常 → 整页白屏，故弃用）。

   这里改用「保留官方骨架 + 补移动端外壳」的融合方案：
     · 正文占满全宽（frame 侧栏轨道归零）
     · 侧栏（官方渲染的完整 280px 形态）改为固定定位浮层，.mp-drawer-open 时滑入
     · 左上角自建汉堡按钮触发；遮罩点击关闭；点会话行/新建会话自动收起
     · 官方 header（标题/模式/⋯ 菜单/右侧栏按钮）与 对话/轨迹 tabs 原样保留
   开合状态仍由官方 .hHd-Xa_toggle 驱动（不碰 React state）。 */
[data-mobile] .pI_x6G_frame {
  grid-template-columns: 0px minmax(0, 1fr) 0px !important;
}
/* ⚠️ 侧栏一旦 position:fixed 就脱离网格流，其后的正文列会自动落进第 1 条
   （0px）轨道 → 正文宽度变成 0。必须给正文/右栏显式指定轨道列。 */
[data-mobile] .pI_x6G_centerCol {
  grid-column: 2 !important;
}
[data-mobile] .pI_x6G_rightbarCol {
  grid-column: 3 !important;
}
/* ⚠️ 抽屉用 left 做动画，绝不用 transform：本插件/官方的浮层大量使用
   position:fixed，而 fixed 元素若位于带 transform 的祖先内，会以该祖先为
   包含块 → 设置弹窗（fixed 100vw）会被裁进 300px 的抽屉里（实测踩到）。
   left 动画不产生包含块，浮层仍相对视口定位。 */
[data-mobile] .pI_x6G_sidebarCol {
  position: fixed !important;
  top: 0;
  bottom: 0;
  left: calc(-1 * min(300px, 85vw) - 40px);
  width: min(300px, 85vw) !important;
  z-index: 40;
  transition: left .22s var(--ds-ease-in-out, ease);
  box-shadow: 0 0 30px rgba(0, 0, 0, .18);
}
[data-mobile] .pI_x6G_sidebarCol.mp-drawer-open {
  left: 0;
  /* ⚠️ dsh 0.2.2-rc.1 起，官方把侧栏开合从「left 位移」改成了
     「transform: translate(-100%) + visibility: hidden」。
     本插件仍是用 left 把侧栏移出屏幕再做浮层滑入，于是出现**最坏组合**：
       · 我们的 left: 0 生效了（抽屉"以为"自己开了）
       · 官方的 translate(-100%) 又把整个侧栏推到屏幕外
       · 而遮罩（.mp-drawer-mask.mp-drawer-open）已经 opacity:1 盖满全屏
     → 真机上看到的就是「整页蒙一层灰、点哪都没反应」，且点击遮罩
       关不掉抽屉（遮罩可见但抽屉不可见，用户完全无法理解发生了什么）。
     这里把官方的两项一并按住，让 left 重新成为唯一的位置来源。
     关闭时本规则不匹配，官方自己的 translate/visibility 照常收回。 */
  transform: translate(0px) !important;
  visibility: visible !important;
}
/* ⚠️ 修复 26 起**不再**给抽屉加 .mp-drawer-closing —— 规则保留作回滚安全网。
   为什么弃用：visibility **会继承给子元素**，而设置弹窗的 DOM 就在抽屉内部
   （实测 col.contains(dialog) === true）→ 弹窗被连坐隐藏 200ms，露出底层对话界面。
   且它本就是多余的：修复 25 后抽屉是"先滑出屏幕、再点官方 toggle"，
   官方 56px 图标条的 morph 发生在 left=-340 的屏幕外，本来就看不见。 */
[data-mobile] .pI_x6G_sidebarCol.mp-drawer-closing {
  visibility: hidden !important;
  transition: none !important;
}
.mp-drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 39;
  background: var(--dsw-alias-overlay-mask, rgba(0, 0, 0, .35));
  opacity: 0;
  pointer-events: none;
  transition: opacity .22s var(--ds-ease-in-out, ease);
}
/* ⚠️ 修复 39：打开态**必须**限定在 [data-mobile] 下。
   宽屏下 .pI_x6G_sidebarCol 没有浮层化（仍是 static），若遮罩可点可见，
   就会盖住侧栏与整页 → 变暗、点不动、误关抽屉（啾灵 2026-09-16 反馈）。
   基类已是 opacity:0 + pointer-events:none，所以这里加上作用域 = 兜底：
   即便 .mp-drawer-open 被以任何方式误加，宽屏下也不会变暗、不会吃点击。 */
[data-mobile] .mp-drawer-mask.mp-drawer-open {
  opacity: 1;
  pointer-events: auto;
}
/* 修复 25b：关闭时遮罩比抽屉（.22s）淡得更快，让对话区先亮起来，
   抵消"抽屉滑走露出未淡完遮罩"造成的短暂下沉（实测 -16 → 目标 <8）。 */
.mp-drawer-mask.mp-drawer-closing {
  transition: opacity .13s var(--ds-ease-in-out, ease) !important;
}
/* 汉堡按钮：固定左上，浮在正文之上（自建元素，不注入 React 子树） */
.mp-nav-btn {
  /* ⚠️ 默认 display:none，仅 [data-mobile] 下显示（桌面端不受影响）。
     注意不要在同一条规则里再写 display，后写会覆盖前写。 */
  display: none;
  position: fixed;
  left: 6px;
  top: calc(var(--dsh-android-system-top, 0px) + 6px);
  z-index: 38;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--dsw-alias-label-primary, #222);
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
[data-mobile] .mp-nav-btn {
  display: inline-flex;
}
.mp-nav-btn.mp-press {
  background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, .15));
}
/* ══ 修复 30：头部单行化（2026-09-16 啾灵拍板方案）══════════════════════
   官方 0.1.5 头部两行（标题行 + 对话/轨迹 tabs）在手机上占 ~85px。改为：
   ① tabs 行隐藏，「轨迹」入口移入 ⋯ 菜单（mpInjectTraceItem）
   ② 标题居中，模式徽章（创造模式等）显示在标题下方
   ③ 汉堡 / ⋯ / 右侧栏按钮绝对定位于 header 两角
   实测 header 高度 85px → 55px。⚠️ 官方 min-height:76px 写死，必须覆盖。 */
[data-mobile] .wSkVaW_header {
  position: relative !important;
  /* ⭐ 修复 30b：header 必须 self-elevate。修复 30 的 position:relative 让 header
     变成层叠上下文，而消息流容器在 DOM 里排在 header 之后（同层级后画者胜）→
     ⋯ 菜单的 z-index:100 被锁死在 header 上下文内，整个 header 被消息流压住：
     菜单透明（背景被消息文字盖住）且点不动（啾灵真机 2026-09-16 实测）。
     给 header 一个显式 z-index 让它整体高于消息流，菜单即恢复最上层。 */
  z-index: 30 !important;
  /* ⚠️ 2026-11-09（用户真机反馈）：这条 z-index 让 header 盖住了官方侧栏按钮
     所在的 .pI_x6G_leadingSeat（z-index:15）。用真实触摸点上去，命中的是
     header 本身，官方按钮**点不动**。解法只改命中、不改层叠：
     header 容器不吃点击，内部元素照常可点，空出来的左上角就交给官方按钮。
     这不是替代官方按钮，是让它能被点到。 */
  pointer-events: none !important;
  /* 修复 57：左右内边距给两端控件让出真实空间（内容盒，故用 border-box）。
     修复 59：左内边距**不再写死**，改由 mpSyncHeaderCenter() 按右侧控件
     的运行时真实占位反推（左留白 = 右占位 ⇒ 标题簇中心恒落在屏幕中线）。
     这里的 50px 只是 JS 没跑起来时的兜底值。 */
  box-sizing: border-box !important;
  padding-left: var(--mp-hdr-padl, 50px) !important;
  padding-right: 12px !important;
  padding-top: 4px !important;
  padding-bottom: 4px !important;
  min-height: 0 !important;
  height: auto !important;
}

/* ══ 修复 61：顶部标题栏高度 +25% ═══════════════════════════════════════
   需求（用户 2026-10-03）：聊天界面顶部标题栏调高 25%。

   ⚠️ 这里**不要写死像素值**。第一版写死 81.25px（= 修复 57 实测的
      65px × 1.25），但「65px」只是 iPhone 13 一种状态下的实测值：
      官方 header 是内容驱动高度（修复 30 把官方 min-height:76px 覆盖成 0），
      自然高度随「模式徽章有无 / 标题是否换行 / 横竖屏」在 55~65px 浮动，
      写死就会在某些状态下远超 25%（55px 时变成 +48%）——真机反馈「有点高过头」
      就是这么来的。

   所以改为**运行时自校准**：由 mpSyncHeaderHeight() 先量出「未加高」时的
   自然高度，再写 --mp-hdr-h = 自然高度 × MP_HDR_SCALE。任何设备、任何
   方向、任何状态下都精确 +25%。
   下面的 0px 只是 JS 没跑起来时的兜底 —— 等于不加高，行为同官方。

   为什么这样写：
     · 官方 header 是 display:grid，隐式行 auto、align-content 默认
       stretch → 容器给了 min-height 后行会撑满，而 titleRow 自身是
       align-items:center 的 flex，内容自动垂直居中：只加高度，
       不需要动布局，也不影响左右两端（汉堡 / ⋯ / utilities / corner）。
     · 用 min-height 而非 height：标题换行、大字号或横屏时仍能自然变高，
       不会把内容裁掉（配合上面的 height:auto）。
     · 本条必须排在修复 30 的 min-height:0 之后：同为
       [data-mobile] .wSkVaW_header + !important，靠书写顺序取胜。
     · 想调比例只动 JS 里的 MP_HDR_SCALE（1.25 = +25%），不要回来写死像素。 */
[data-mobile] .wSkVaW_header {
  min-height: var(--mp-hdr-h, 0px) !important;
}

/* ══ 修复 62：恢复标题下方的「当前模式 / Agent 预设」徽章（2026-10-03 反馈）═
   现象：手机端聊天页标题下方不再显示当前对话用的是哪个模式（预设名 +
         图标），桌面端却有 —— 用户反应「好像少了点啥」。

   根因（**官方行为，不是本插件引入的**）：
     · 徽章由 @deepseek-ai/dsh-client-ui-agent-preset 注册进
       conversation.session.header.actions 槽位（AgentPresetLabel，order -10），
       渲染为 <span class="SVAs4q_label" title="…">图标 + 预设名</span>；
     · 它自带的样式里有一条容器查询：
         @container (width<=540px){ .SVAs4q_label{ display:none } }
       而官方 .wSkVaW_titleRow 设了 container-type:inline-size ——
       手机竖屏 titleRow 内联尺寸 ≈ 390 − 50(padL) − 12(padR) ≈ 328px，
       必然命中 ≤540px → 官方按「窄屏省略」把它隐藏。桌面端 titleRow
       宽 >540px，所以一直可见，这也解释了为什么只有手机缺。

   为什么放回来是安全的：本插件修复 30 的单行化头部本来就是
   「标题 + 徽章」两层结构，headerActions 就在 titleCluster 这个列容器里、
   被 align-items:center 居中 —— 位置早就留好了，只需要把 display 还原，
   不需要动任何布局。

   ⚠️ SVAs4q_label 是构建期哈希类名，官方升级可能变；所以同时给一条
      结构性兜底（headerActions 里带 title 的 span）。同槽位的其他条目
      （subagent-catalog / job-list）都是按钮，不会被这条误伤。 */
[data-mobile] .wSkVaW_headerActions .SVAs4q_label,
[data-mobile] .wSkVaW_headerActions span[title] {
  display: inline-flex !important;
}
[data-mobile] .wSkVaW_tabs {
  display: none !important;
}

/* ══ 修复 34：轨迹页详情面板底部被输入框挡住（2026-09-16 啾灵反馈）══════════
   现象：轨迹页点开某条数据后，右侧详情面板一直延伸到屏幕最底部，
        最后几行（吞吐量等）被悬浮的输入框压住、看不到也点不到。
   根因：官方详情面板用 absolute + top:0/bottom:0 撑满 .Y0dWHa_split；
        它只在**内层滚动容器**上用了 padding-bottom = --dsh-trajectory-bottom-clearance
        （= composer 高度 + 16px），面板本体没有这个留白。
        ⚠️ 实测该变量在移动端是**正确**的（--dsh-composer-height 为 128px），
        所以不是变量丢失，而是「面板本体不吃这个 padding」。
   修法：在窄屏下给详情面板本体补同样的底部留白，让它停在输入框上方。
        用官方的同一个变量、同一套语义（+16px 呼吸位），不写死像素。 */
[data-mobile] .Y0dWHa_details {
  bottom: var(--dsh-trajectory-bottom-clearance, calc(var(--dsh-composer-height, 152px) + 16px)) !important;
}
/* 概览/详情内部滚动区也补一份，避免内容贴着面板底缘 */
[data-mobile] .Y0dWHa_overviewPreview,
[data-mobile] .Y0dWHa_detailBody {
  padding-bottom: 12px !important;
}
/* ══ 修复 57：长标题压住右上角控件（2026-09-28 啾灵真机反馈）══════════════
   现象（iPhone/窄屏）：会话标题较长时，标题右端压到 header 右侧的
     「用文件资源管理器打开 / 更多打开方式 / ⋯ / 右侧栏」按钮上。
   实测（390px，标题「DroidMind 安装教程与部署」）：
     标题簇 99..291，而 headerUtilities 从 267 开始 → 重叠 24px；
     标题越长越严重（详解v2 → 49px，超长 → 56px）。

   根因（本插件自己引入的，非官方问题）：
     ① 修复 30 把 headerUtilities / headerCorner 改成 position:absolute
        脱离文档流 → 它们不再参与 flex 挤压，标题也就不再被它们「顶住」；
     ② titleCluster 被设成 max-width:72vw(=280px) 且整屏居中，
        而右侧控件实际起点是 267px → 可用区只有 221px，却允许排到 280px。
     官方原本是 flex 流内挤压 + 省略号，根本不会重叠。

   修法（去掉「绝对定位 + 写死像素」这套补丁，回到官方流内语义）：
     ① headerUtilities / headerCorner 恢复 position:static，回到 titleRow
        的 flex 流内 —— 由浏览器自动挤压，右侧宽度不再写死；
     ② header 用 border-box + 左右内边距给两端让位（左给汉堡、右给呼吸位）；
     ③ titleCluster 改为 flex:1 1 0 + min-width:0 + overflow:hidden，
        crumb 链 min-width:0 且 text-overflow:ellipsis →
        标题只在中间可用区里省略，永不越界。

   实测结果（iPhone 13 / 390px，五种标题长度）：
     短「你好」→ 不截断；长标题 → 省略号；重叠 0px；header 高度仍 65px；
     320px 极窄屏同样 0 重叠、无横向溢出。
     桌面端（1440/1024）逐项几何与改动前**完全一致**（data-mobile 未命中）。 */
[data-mobile] .wSkVaW_titleRow {
  display: flex !important;
  justify-content: center !important;
  width: 100% !important;
  margin: 0 !important;
  flex-wrap: nowrap !important;
  box-sizing: border-box !important;
  align-items: center !important;
  gap: 0 4px !important;
  padding: 0 !important;
}
[data-mobile] .wSkVaW_titleCluster {
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
  /* 修复 57：由 max-width:72vw 改为「吃掉剩余空间并可收缩」，
     让两侧控件的真实宽度决定标题的可用区。 */
  flex: 1 1 0% !important;
  min-width: 0 !important;
  max-width: none !important;
  overflow: hidden !important;
}
[data-mobile] .wSkVaW_crumbs {
  min-width: 0 !important;
  max-width: 100% !important;
  overflow: hidden !important;
  justify-content: center !important;
}
[data-mobile] .wSkVaW_crumbSeg {
  min-width: 0 !important;
  overflow: hidden !important;
}
[data-mobile] .wSkVaW_crumb {
  /* 修复 59：标题最大宽度再收一档（用户反馈「标题宽度可以再限定一下」）。
     上限 = min(可用宽, 62vw) → 390px 屏约 242px；长标题更早出现省略号，
     视觉上更「居中成块」，也避免标题贴着右侧按钮。 */
  max-width: min(100%, 62vw) !important;
  min-width: 0 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}
[data-mobile] .wSkVaW_headerActions {
  flex-wrap: nowrap !important;
  width: auto !important;
  flex: 0 0 auto !important;
}
/* 汉堡按钮：由 fixed 改为锚定 header 左上（mpBuildShell 会把它挪进 header）。
   仍用 absolute 是无害的：header 的左内边距已为它留出 50px，
   它不参与挤压，也就不影响标题的省略计算。

   ⚠️ 修复 62：top 用 max(50%, 20px)，不是裸 50%。
   未选工作区的 Hero 页，官方 header 是空壳（wSkVaW_headerBlank
   wSkVaW_headerSessionless），实测高度只有 8px（CDP：rect.h=8、padTop=4px）。
   裸 50% 在那里算成 4px，再被 translateY(-50%) 上移 20px → 按钮 y = -16px，
   整个汉堡跑到 header 上方、顶进状态栏（用户反馈「顶栏一整行上移了，
   选完工作区又恢复」—— 选中后 header 恢复自然高度 ~47px，50% 变正值就"回位"）。
   max() 的下界取汉堡自身高度的一半（40 / 2 = 20px），保证 translateY 之后
   顶边不低于 0；header 高度正常时 50% 大于 20px 取 50%，行为与修复前逐像素一致。
   这个下界只依赖汉堡自己的尺寸，不依赖官方类名语义或 header 自然高度。 */
[data-mobile] .mp-nav-btn.mp-in-header {
  position: absolute !important;
  left: 6px !important;
  top: max(50%, 20px) !important;
  transform: translateY(-50%) !important;
}
/* 修复 57：回到 flex 流内（原先 absolute + right:44px 是重叠的根因）。 */
[data-mobile] .wSkVaW_headerUtilities {
  position: static !important;
  right: auto !important;
  left: auto !important;
  top: auto !important;
  transform: none !important;
  margin-left: 0 !important;
  flex: 0 0 auto !important;
}
[data-mobile] .wSkVaW_headerCorner {
  position: static !important;
  right: auto !important;
  left: auto !important;
  top: auto !important;
  transform: none !important;
  /* 修复 30c：官方自带 margin-right:-16px（配合旧布局把按钮拉出容器缘），
     会让按钮探出屏幕 16px（啾灵真机：右侧栏按钮一半看不见）。归零。 */
  margin-right: 0 !important;
  flex: 0 0 auto !important;
}

/* ══ 修复 58：移动端隐藏「打开方式」分体按钮（2026-09-28 啾灵拍板）════════
   该按钮来自官方插件 @deepseek-ai/dsh-client-ui-open-in-app，注册在
   conversation.session.header.utilities 槽位，作用是**在运行 DSH 的这台电脑上**
   用文件资源管理器打开会话工作目录（并记住选过的程序）。

   为什么移动端隐藏：
     ① 手机上点它调起的仍是电脑上的程序，对手机用户没有意义；
     ② 它占据 header 右侧 43px（实测 259..302），是长标题可用区被压缩的主因。
        隐藏后标题可用区从 209px 增至约 252px，长标题能多显示 3~4 个字；
     ③ 用户明确反馈「标题不居中也太丑了」—— 隐藏后右侧只剩「⋯」+「右侧栏」
        两枚按钮，左右更对称。

   ⚠️ 只隐藏这一个分体按钮，**同容器内的「更多操作 ⋯」菜单必须保留**
      （它是移动端进入「轨迹」等入口的唯一通道，见 mpInjectTraceItem）。

   识别方式：用官方稳定属性 [data-open-target]（值是 directory/file），
     不依赖会随发布变化的 CSSModule hash，也不用 :has()（旧内核可能不支持）。
   桌面端：无 data-mobile → 规则不命中，按钮与布局完全不变。 */
[data-mobile] .wSkVaW_headerUtilities [data-open-target] {
  display: none !important;
}

/* 底栏保留官方文本与点击行为，缩写由按钮属性显示，避免修改 React 文本节点。
   固定辅助字号并关闭 WebView 文本自动放大，运行结束与刷新后保持一致。 */
[data-mobile] [data-composer-dock] {
  min-width: 0 !important;
  max-width: 100%;
  overflow: hidden !important;
  -webkit-text-size-adjust: none;
  text-size-adjust: none;
}
[data-mobile] [data-composer-dock] > * {
  min-width: 0 !important;
}
[data-mobile] [data-composer-dock] [data-composer-stat],
[data-mobile] [data-composer-dock] button,
[data-mobile] [data-composer-dock] button span {
  font-size: 12px !important;
  line-height: 20px !important;
}
[data-mobile] [data-composer-stat] button[data-mp-stat-text] > span {
  display: none !important;
}
[data-mobile] [data-composer-stat] button[data-mp-stat-text]::after {
  content: attr(data-mp-stat-text);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ══ 消息正文/思考正文的横向溢出防护 ══════════════════════════════════
   症状：整个消息列表向左偏出屏幕，左侧文字被切一半，右侧留一片空白，
   而且页面能被**横向拖动**。退出会话重新进来就恢复正常。

   根因：. _text_1rdzk_17 是官方 AssistantMarkdown 的通用文本类，正文与
   思考正文都用它，官方给的是 white-space: nowrap（不换行）。真机实测
   过程面板里有一条文本宽 **411px**，而视口只有 360px，把滚动容器的
   scrollWidth 撑大；浏览器为显示光标位置把 scrollLeft 自动设成非 0，
   于是整页看起来左移。实测数据（CDP 读真实 DOM）：
     · 一条 _text_1rdzk_17 的 getBoundingClientRect().width = 411
     · 官方 . _root_1rdzk_1 的 width 是 max-content，在 flex 里按
       内容原始宽度撑开，不会服从父级的 100% 约束

   修法：两处一起改，缺一个都不行 ——
     ① 文本恢复换行（normal + overflow-wrap: anywhere 兜住没有空格的长串）
     ② 根容器 width: auto + max-width: 100%，让它服从父级宽度

   ⚠️ **必须排除过程面板的单行摘要**（.lcKema_summary / .lcKema_summaryText）：
     那两处官方设计就是 nowrap 单行 + 省略号，父行 .lcKema_row 高度固定
     25px 且 overflow: hidden。若一并改成 normal，摘要会换行到 3~4 行
     （实测 20px 行高 × 4 = 80px），被父行裁掉后屏幕上只剩
     「两行被截断的半个文字」。实测对照：改前 summary h=80、内层 ws=normal；
     改后 h=20、单行省略号、父行不再裁切。
   ⚠️ 判据用 [class*="summary"] 而不是完整哈希类名：CSS Modules 生成的
     类名是 <hash>_<源码变量名>，后缀段来自源码变量名，比哈希前缀稳定。
   ⚠️ 这两个都是官方构建期哈希类名，官方升级可能变；失效后果只是
     「又能横向拖动」，不影响功能。 */
[data-mobile] [class*="_root_1rdzk"] {
  width: auto !important;
  max-width: 100% !important;
}
[data-mobile] [class*="_text_1rdzk"] {
  white-space: normal !important;
  word-break: break-word !important;
  overflow-wrap: anywhere !important;
}
[data-mobile] [data-process-activity] [class*="_root_1rdzk"] {
  min-width: 0 !important;
  flex: 1 1 0%;
  overflow: hidden;
}
[data-mobile] [data-process-activity] [class*="_text_1rdzk"],
[data-mobile] [class*="summary"] [class*="_text_1rdzk"],
[data-mobile] [class*="summaryText"] [class*="_text_1rdzk"] {
  white-space: nowrap !important;
  word-break: normal !important;
  overflow-wrap: normal !important;
}

/* ══ 修复 71：整页能横向滚 114px，点一下「更多操作」页面就左偏 ══════════
   现象（真机复现，两次）：点右上角「⋯」打开菜单后，整个界面**左移 114px** ——
   标题栏按钮从 x=294 跑到 x=180，输入框、发送键、遥测栏一起跟着跑。
   实测 bodyX = -114、root.scrollLeft = 114。

   排查过程（避免后人重复走）：
     · 先怀疑菜单本身超宽 —— 实测菜单只有 148px 宽、right = 194（不溢出），排除。
     · 再全树扫描「右边界超出视口 2px 以上」的元素，**命中数 0**，排除。
     · 点「刷新」关菜单后 scrollLeft 自动回 0，说明不是菜单引起的持续性偏移。
   真凶在 body 的直接子元素里：官方有一对**宽度探针**
     <div class="wSkVaW_widthHandle" data-side="left"  style="left:-184px">
     <div class="wSkVaW_widthHandle" data-side="right" style="left:544px">
   它们 position: absolute、width: 0、height: 691（**视觉上完全不可见**），
   官方靠它们量容器宽度。但它们把 body 的 scrollWidth 撑到 **550**
   （360 视口 + 184 左侧 + 6 右侧），于是 root 也变成 550。

   ⚠️ 为什么这个 bug 特别阴：root 的 overflow-x 本来就是 **hidden**，
      所以**手指横滑没反应**（实测 339.67 → 339.67 纹丝不动），用户很难自己发现；
      但 overflow: hidden **只挡手势、不挡程序化滚动** —— 实测 scrollLeft = 50
      照样成功、bodyX 立刻 -50。于是只要有任何一次「把焦点/元素滚进视口」的动作
      （点视口外的按钮、浏览器自动滚动、键盘弹出后补偿滚动），页面就被永久推偏，
      而用户既滑不回来、也不知道发生了什么。

   修法：给 body 加 overflow-x: clip。
     · clip 与 hidden 的区别正是这里要的：**clip 不产生滚动容器**，
       所以 scrollWidth 不再被算作可滚动范围，程序化滚动也失效（实测滚 50 仍是 0）。
     · 探针元素本身**不受影响**：实测 x 仍是 -184 / 544，height 仍 691，
       offsetWidth/clientWidth 仍是 0 —— 官方量宽照常工作。
     · 实测修复后 root.scrollWidth 550 → **360**，bodyX 恒 0。

   ⚠️ 不用 overflow: hidden（官方已经是了，正是它留下了程序化滚动的口子）。 */
[data-mobile] .wSkVaW_body {
  overflow-x: clip !important;
}

/* ══ 修复 72：宽表格右侧 226px 在手机上**永远看不到** ════════════════════
   现象（真机截图一眼就能看出来）：助手回复里的宽表格被右边缘硬切掉 ——
   表头「目标 / p50 / p95 / max」只显示到 p95，**max 整列看不到**，
   p95 的值（25ms / 1709）也被切了一半。实测两个宽表格：
     容器 294px，内容 512 / 520px ⇒ **218 / 226px 内容被藏掉**。

   根因：官方给 .md-table-wide 设的是 **overflow-x: hidden**（不是 auto）。
   它的本意是桌面端 —— 容器带 tabindex="0"，靠**键盘**左右滚动。
   但手机上既没有键盘、hidden 又**挡掉触摸滑动**：
     实测 dispatch touchstart+touchmove 后 scrollLeft 仍是 0，
     而 overflow 明明是 hidden，用户手指横滑完全没反应。
   ⇒ 结果就是右侧内容**永久不可达**，这不是"设计取舍"，是移动端信息丢失。

   修法：改回 overflow-x: auto（+ iOS 惯性滚动），让它变成正常可横滑的表格。
     实测修复后：scrollLeft 可到 **218**，滚到最右时 max 列完整可见
     （x=194~326），四个表头全部可达。
     overscroll-behavior 官方已设 contain，不会把滑动传染给外层页面。

   ⚠️ 为什么敢改：官方用 hidden 只是想"锁住不让它撑破布局"，
      auto 同样不会撑破（容器宽仍是 294，只是内部可滚）。
      这个类名 .md-table-wide 是**语义类名**（不是构建期哈希），稳定性高。 */
[data-mobile] .md-table-wide {
  overflow-x: auto !important;
  -webkit-overflow-scrolling: touch !important;
}

/* ══ 修复 73：任务卡片半透明 ⇒ 底下的正文**透上来**，主次颠倒 ══════════
   现象（真机截图一眼可见）：任务卡片（「任务 5 已完成」+ 绿点清单）浮在
   正文之上，而它的背景只有 **58% 不透明**，于是下面那条消息的文字
   （「错误信息是「特权通道不可用」…」）**透过卡片显出来**，
   和卡片自己的清单文字叠在一起，糊成一团。

   实测配色（这就是为什么它特别难看）：
     背后正文文字   rgb(15,17,21)   近黑
     卡片背景       rgba(248,249,250,0.58)   58% 浅灰
     卡片自己的文字 rgb(97,102,107)  中灰
   把 58% 浅灰叠在近黑文字上做混色：0.58×248 + 0.42×15 ≈ **150**
   ⇒ 透出来的正文约 rgb(150,152,155)，**比卡片内文字 rgb(97,102,107) 还亮**。
   也就是说"被遮挡的内容"反而比"该读的内容"更显眼，视觉主次完全颠倒。

   ⚠️ 别以为是 backdrop-filter 没生效：实测内核其实是 **Chrome 114**
      （不是降级目标 74），backdrop-filter 支持且 computed 值就是
      "blur(40px) saturate(1.5)"；祖先链上也没有 filter/opacity/transform
      破坏它。问题纯粹是**基础背景太透明**，模糊压不住高对比度的黑字。

   修法：把背景不透明度提到 **0.97**（保留一点点毛玻璃质感，但不透字）。
     · 亮色：rgba(248,249,250,0.58) → 0.97
     · 暗色：rgba(67,69,74,0.45)   → 0.97
     实测背后内容透出率从 42% 降到 **3%**，截图对比：修复前那行字清晰可读地
     压在卡片上，修复后卡片区域干净、只剩清单本身。

   ⚠️ 必须分别写亮/暗两套值：两边的底色不同（248,249,250 vs 67,69,74），
      不能用同一个颜色，也不能只写一套。

   ⚠️ 为什么不去改"卡片不该遮住正文"：卡片在 .wSkVaW_composerStack 内、
      与 composerSeat 同级，官方就是把它做成浮在内容上的浮动面板
      （实测滚到 30%/50%/70%/90% 都有 1~6 条消息被它压住，只有滚到最底部
      才不重叠）。要根治得改官方布局结构，风险远大于收益；而"透字"是纯粹的
      可读性缺陷，提不透明度即可解决，不影响任何行为。 */
[data-mobile] .lXshSW_root {
  background: rgba(248, 249, 250, 0.97) !important;
}
[data-mobile] body[data-ds-dark-theme] .lXshSW_root,
[data-mobile] [data-ds-dark-theme] .lXshSW_root {
  background: rgba(67, 69, 74, 0.97) !important;
}

/* ══ 修复 75：输入框占位符对比度只有 2.13:1，手机上几乎读不出 ═══════════
   现象：输入框里那句「发消息或创建任务, / 调用指令, @ 文件或对话」
   在亮色下淡得像水印，要凑近才认得出 —— 而这是用户**每次发消息都会看到**
   的提示文字，可读性直接影响第一印象。

   实测（WCAG AA 正文要求 4.5:1）：
     亮色：rgb(173,178,184) 叠白底 → **2.13:1**（严重不达标）
     暗色：rgb(129,133,140) 叠 rgb(44,44,46) → **3.76:1**（也不达标）
   颜色来自官方变量 --dsw-alias-label-dimmed，不是我设的。

   ⚠️ 但也不能一把拉到全对比：占位符**故意比正文淡**是有语义的
      （区分"这里还没输入"与"已经输入的内容"）。全拉到 rgb(15,17,21)
      会让它看起来像已经填好的内容，反而误导。
      ⇒ 取**刚过 AA** 的值，既读得清、又保持"这是提示"的层次。

   最终取值（都是刚过 4.5，故意不拉满）：
     亮色 rgb(112,117,122) → **4.65:1** ✅
     暗色 rgb(165,170,176) → **5.96:1** ✅
   同样处理了另外两处同色系的次级文字（都从 ~3.5 提到 4.65）：
     · 代码块左上角的「代码块」标签（._language_1pq26_30）
     · 标题栏下方「快准狠模式（通用）」（.SVAs4q_label） */
[data-mobile] .uV2eYG_placeholder {
  color: rgb(112, 117, 122) !important;
}
[data-mobile] body[data-ds-dark-theme] .uV2eYG_placeholder,
[data-mobile] [data-ds-dark-theme] .uV2eYG_placeholder {
  color: rgb(165, 170, 176) !important;
}
[data-mobile] ._language_1pq26_30,
[data-mobile] .SVAs4q_label {
  /* ⚠️ 这两个用 rgb(105,110,115) 而不是占位符的 rgb(112,117,122)：
     「代码块」标签的实际背景是 rgb(249,250,251)（浅灰），不是纯白。
     实测 112,117,122 在浅灰底上只有 **4.45:1**，差 0.05 不达标；
     压到 105,110,115 后是 **4.93:1** ✅。 */
  color: rgb(105, 110, 115) !important;
}
[data-mobile] body[data-ds-dark-theme] ._language_1pq26_30,
[data-mobile] body[data-ds-dark-theme] .SVAs4q_label,
[data-mobile] [data-ds-dark-theme] ._language_1pq26_30,
[data-mobile] [data-ds-dark-theme] .SVAs4q_label {
  color: rgb(165, 170, 176) !important;
}

/* ══ 修复 76：抽屉里「工作区」分组标题与「展开其余 N 个会话」读不清 ══════
   用修复 75 那套对比度扫描把侧边抽屉也过了一遍，命中 6 处 < 4.5:1，
   其中两处值得修（都是 rgb(129,133,140) → 3.55:1）：
     · .bhn1Oq_sectionLabel        「工作区」分组标题
     · .bhn1Oq_sessionOverflowButton 「展开其余 2 个会话」
   后者尤其要紧：它是**可点击的操作**，文字太淡会让人看不出这里能点，
   直接影响会话列表的可用性。

   ⚠️ 只修这两处，没有把抽屉里 6 处全拉高：其余（时间戳 05:47、
      上下文 68%、「空面板」提示、任务卡「5 已完成」）都是**纯次级信息**，
      全拉到 4.5 会让抽屉失去层次感、看起来一片同等重要。
      这两处是"导航结构"和"可点操作"，性质不同，才单独提上来。

   取值同修复 75：亮 rgb(105,110,115) → 4.93:1 ✅ / 暗 rgb(165,170,176) → 7.36:1 ✅ */
[data-mobile] .bhn1Oq_sectionLabel,
[data-mobile] .bhn1Oq_sessionOverflowButton {
  color: rgb(105, 110, 115) !important;
}
[data-mobile] body[data-ds-dark-theme] .bhn1Oq_sectionLabel,
[data-mobile] body[data-ds-dark-theme] .bhn1Oq_sessionOverflowButton,
[data-mobile] [data-ds-dark-theme] .bhn1Oq_sectionLabel,
[data-mobile] [data-ds-dark-theme] .bhn1Oq_sessionOverflowButton {
  color: rgb(165, 170, 176) !important;
}

/* ══ 修复 77：设置页所有说明副标题只有 3.71:1，读着费劲 ═════════════════
   现象：设置里每一项标题下面那行灰字，比如「选择新会话的默认权限模式」
   「仅影响会话内容的字号」「选择希望看到多少工具调用细节」——
   这些是**用户理解这个开关到底干什么的唯一说明**，太淡就等于没有说明。

   实测：rgb(129,133,140) 叠白底 → **3.71:1**（AA 要 4.5:1）。

   ⚠️ 为什么用 [class*="_desc"] 属性选择器而不是逐个写类名：
      设置页里这批说明文字**至少有三个不同类名**，各模块各自起名：
        .oY77xG_desc  .bVCLcG_desc  ._2XZxNq_desc  .Pt1bsG_description  .T1PP_q_desc
      逐个列既漏又难维护（官方一改 hash 前缀就全失效）。实测页面上
      [class*="_desc"] 命中 8 个，颜色只有两种：6 个 rgb(129,133,140)（不达标）
      + 2 个 rgb(97,102,107)（已达标）。统一提亮不会误伤后者（本来就比目标值深）。

   取值同修复 75/76：亮 rgb(105,110,115) → **5.15:1** ✅ / 暗 rgb(165,170,176) ✅ */
[data-mobile] [class*="_desc"] {
  color: rgb(105, 110, 115) !important;
}
[data-mobile] body[data-ds-dark-theme] [class*="_desc"],
[data-mobile] [data-ds-dark-theme] [class*="_desc"] {
  color: rgb(165, 170, 176) !important;
}


/* 5. 审批面板浮到输入框上方（面板保留在 Cordis layer 内不动，仅重定位）。
   抽屉打开时 transform 为 identity，fixed 相对视口生效；bottom 用 CSS 变量
   承载：React 重算 anchor 覆盖内联 bottom 也压不过 !important。 */
[data-mobile] .Nqubda_panel[data-mp-approval] {
  position: fixed !important;
  left: 12px !important;
  right: 12px !important;
  top: auto !important;
  width: auto !important;
  max-width: calc(100vw - 24px) !important;
  bottom: var(--mp-approval-bottom, 96px) !important;
  z-index: 9999 !important;
}

/* 9. 移动端全局关闭 hover 文字提示（tooltip）：
   桌面端 onMouseEnter/onFocus 显示的气泡在触摸设备上会因焦点残留而
   卡在屏幕上（如侧边栏"收起侧边栏"提示）。触摸设备不需要这类提示，
   直接全部隐藏；桌面 hover 行为不受影响。 */
@media (pointer: coarse) {
  [role="tooltip"] {
    display: none !important;
  }
}

/* 9b. 移动端侧边栏会话/工作区行的 ⋮ 菜单按钮常显：
   官方样式仅 hover 或 menuOpen 时显示 rowActions，桌面靠鼠标悬停，
   移动端没有 hover 导致要先点一下会话行才出现。触摸设备上改为
   始终显示，并像官方 hover 那样隐藏时间戳腾出空间。 */
[data-mobile] .YDXeBa_sessionRow .YDXeBa_rowActions,
[data-mobile] .YDXeBa_projectRow .YDXeBa_rowActions {
  display: inline-flex !important;
}
[data-mobile] .YDXeBa_sessionRow .YDXeBa_time,
[data-mobile] .YDXeBa_projectRow .YDXeBa_time {
  display: none !important;
}

/* 9c. 移动端消灭"粘住的 hover"（触摸端没有鼠标移开，
   手指点一下之后 :hover 会一直保留到点别处，看起来像鼠标悬停没走开）。
   官方把下面这些视觉反馈挂在 :hover 上，触摸端会留下残留：
     - .YDXeBa_projectRow / .YDXeBa_sessionRow   行底色
     - 行首图标：hover 时"文件夹"变"▸ 箭头"
     - 抽屉里各种图标按钮的圆形底色
   处理原则：把 :hover 效果归零到各自的基准值，按压反馈改由 JS 打
   .mp-press 类实现（触摸端 :active 不可靠，实测按住时并不匹配）。
   注意保留两个真实状态：会话行的"当前选中" .YDXeBa_selected
   与 ⋮ 菜单打开态 .YDXeBa_menuOpen（都不是 hover）。
   设置 dialog 子树（VOzbGW_*）一律不碰，避免把 dialog 推出屏幕。 */
[data-mobile] .YDXeBa_projectRow:hover,
[data-mobile] .YDXeBa_sessionRow:hover:not(.YDXeBa_selected),
[data-mobile] .YDXeBa_searchResultRow:hover:not(.YDXeBa_selected),
[data-mobile] [data-mobile] .bhn1Oq_iconButton:hover,
[data-mobile] .bhn1Oq_searchButton:hover,
[data-mobile] .hHd-Xa_iconButton:hover {
  background: transparent !important;
}
[data-mobile] .YDXeBa_projectRow:hover .YDXeBa_chevron {
  display: none !important;
}
[data-mobile] .YDXeBa_projectRow:hover .YDXeBa_folder {
  display: inline-flex !important;
}
[data-mobile] .YDXeBa_iconButton:hover {
  color: var(--dsw-alias-label-tertiary, #888) !important;
}
[data-mobile] .hHd-Xa_newSession:hover {
  background: var(--dsw-alias-button-elevated-fill, #fff) !important;
}

/* 9c-2. 变量级兜底。上面 9c 是逐个类名写死的，只盖住了侧栏那一小撮；
   真机实测主界面还有一批按钮把 hover 底色挂在设计系统变量上 ——
   11 个可点元素里有 9 个是同一个 --dsw-alias-interactive-bg-hover
   (= #2631480f = rgba(38,49,72,0.06)，就是用户看到的"黑印")。
   官方类名是构建期 hash（换版本就变），所以这一层改成从变量拦截，
   一次覆盖全部元素，不依赖任何类名。

   ⚠️ 必须排除 [aria-expanded="true"]。官方把"菜单已展开"的持久高亮和 :hover
   写在同一组规则里、共用同一个变量：
     .pXSMma_workspace:not(:disabled):hover,
     .pXSMma_workspace[aria-expanded="true"] { background: var(--dsw-alias-interactive-bg-hover) }
   连坐清掉会把"当前展开的菜单"高亮一起弄没（真机上唯一带这个属性的是
   home 工作区按钮）。排除后真机实测：收起+hover → 透明；
   展开 → 仍是 rgba(38,49,72,0.06)（持久高亮完好）。

   ⚠️ 只包在 (hover: none) 里：接鼠标的设备保留 hover 反馈。
   测试机 Huawei CDY-TN90 命中该媒体查询（hover:none / pointer:coarse）。 */
@media (hover: none) {
  [data-mobile] button:hover:not([aria-expanded="true"]),
  [data-mobile] [role="button"]:hover:not([aria-expanded="true"]),
  [data-mobile] [role="tab"]:hover:not([aria-expanded="true"]),
  [data-mobile] [role="menuitem"]:hover:not([aria-expanded="true"]),
  [data-mobile] a[href]:hover:not([aria-expanded="true"]),
  [data-mobile] summary:hover:not([aria-expanded="true"]),
  [data-mobile] [tabindex]:hover:not([aria-expanded="true"]) {
    --dsw-alias-interactive-bg-hover: transparent;
  }
  /* composer 的 "+" 按钮走的是 -solid 变量（#f1f3f5），把它也归零会变成全透明，
     比原来的"浅灰→略深灰"更难看，所以这里直接还原成它的静态底色。 */
  [data-mobile] .uV2eYG_add:hover:not(:disabled) {
    background: #f5f6f7 !important;
  }
  /* 侧栏"新会话"按钮：9c 把它 hover 设成白卡（elevated-fill），
     而它的静态底色是透明，点完同样留一块白印，这里一并归零。 */
  [data-mobile] .hHd-Xa_newSession:hover {
    background: transparent !important;
  }
}

/* 9d. 按压反馈（替代触摸端不可用的 hover）：
   JS 在 touchstart/pointerdown 时给按下的行加 .mp-press，抬手即移除。 */
[data-mobile] .YDXeBa_projectRow.mp-press,
[data-mobile] .YDXeBa_sessionRow.mp-press,
[data-mobile] .YDXeBa_searchResultRow.mp-press,
[data-mobile] .bhn1Oq_iconButton.mp-press,
[data-mobile] .bhn1Oq_searchButton.mp-press,
[data-mobile] .hHd-Xa_iconButton.mp-press {
  background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, 0.15)) !important;
}
[data-mobile] .YDXeBa_iconButton.mp-press {
  color: var(--dsw-alias-label-primary, #222) !important;
}

/* 10. 顶栏标题重命名弹窗（移动端自建，兼容手机 WebView） */
.mp-rename-mask {
  position: fixed;
  inset: 0;
  z-index: 99999;
  background: rgba(0, 0, 0, .4);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
}
.mp-rename-box {
  width: 100%;
  max-width: 340px;
  background: var(--dsw-alias-bg-base, #fff);
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .25);
  padding: 20px 18px;
  box-sizing: border-box;
}
.mp-rename-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--dsw-alias-label-primary, #222);
  margin: 0 0 14px;
  text-align: center;
}
.mp-rename-input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--dsw-alias-border-l2, #e0e0e0);
  background: var(--dsw-alias-bg-layer-1, #fff);
  color: var(--dsw-alias-label-primary, #222);
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 15px;
  outline: none;
}
.mp-rename-input:focus {
  border-color: var(--dsw-alias-state-business-primary, #4a7dff);
}
.mp-rename-row {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
.mp-rename-btn {
  flex: 1;
  height: 44px;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  cursor: pointer;
}
.mp-rename-cancel {
  background: var(--dsw-alias-interactive-bg-hover, #f0f0f0);
  color: var(--dsw-alias-label-secondary, #666);
}
.mp-rename-ok {
  background: var(--dsw-alias-state-business-primary, #4a7dff);
  color: #fff;
  font-weight: 600;
}
.mp-rename-error {
  color: var(--dsw-alias-state-error-primary, #e5484d);
  font-size: 12px;
  margin: 10px 2px 0;
  min-height: 16px;
}

/* 12. 插件列表分组（官方 / 用户插件），默认折叠、点标题展开 */
.mp-plugin-group {
  grid-column: 1 / -1;
  list-style: none;
  margin: 6px 0 2px;
}
.mp-plugin-group-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 11px 12px;
  border: 1px solid var(--dsw-alias-border-l2, rgba(0, 0, 0, 0.08));
  border-radius: 10px;
  background: var(--dsw-specific-menu, #fff);
  color: var(--dsw-alias-label-primary, #222);
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  box-sizing: border-box;
}
.mp-plugin-group-count {
  color: var(--dsw-alias-label-tertiary, #888);
  font-size: 12px;
  font-weight: 400;
  background: var(--dsw-alias-interactive-bg-hover, rgba(127, 127, 127, 0.15));
  border-radius: 99px;
  padding: 1px 8px;
  white-space: nowrap;
}
.mp-plugin-group-chevron {
  margin-left: auto;
  opacity: 0.6;
  transition: transform 0.15s ease;
}
.mp-plugin-group-open .mp-plugin-group-chevron {
  transform: rotate(180deg);
}

/* ══ 修复 66：全局面板页（插件 / 定时任务）顶部文字被汉堡压住 ════════════
   现象（2026-10-04 啾灵真机反馈）：
     侧栏点「插件」后主区切到插件页，页面最上方的「插件」大标题与它下面
     的说明文字，和左上角那枚汉堡按钮**叠在一起**（截图实测汉堡 y 145..215、
     标题 y 150..215，重叠约 65px）。

   根因（官方布局 + 本插件的挂载策略共同导致，不是单方面的锅）：
     ① 「.wSkVaW_header」（含 ⋯ 那一行顶栏）属于 **conversation 插件**，
        切到 main panel 时 conversation 整体卸载 → 顶栏连同一整行都没了；
     ② 本插件的汉堡由 mpAnchorBurger() 挂载：有顶栏时进 header（absolute），
        **没有顶栏时回落到 document.body（fixed 左上角浮层）** —— 这一步本身
        是对的（否则插件页就没有入口回不去侧栏了）；
     ③ 而插件页自己的「.X_2TxG_pageHead」只有 padding-top:28px，那是官方给
        **桌面端** --dsh-frame-top-clearance 预留的量（该变量只在
        darwin / windows-titlebar 下定义，Android 上恒为 0）→ 页面从视口顶部
        开始排，正好撞进汉堡的浮层范围。

   修法：**不给插件页写死像素，也不去硬编码它的 hash 类名**。
     汉堡是浮层时，把它真实的 bottom（+ 呼吸位）写成 CSS 变量
     --mp-float-nav-h，由「.pI_x6G_centerCol」（官方 layout 的稳定类名）
     吃成 padding-top；汉堡回到顶栏里时该变量清零，conversation 页逐像素不变。
     这样任何面板页、任何屏幕、任何系统栏高度都自动对齐，且官方换 hash 类名
     也不会失效（我们不依赖 X_2TxG_page 这个构建期哈希）。

   ⚠️ 只动 centerCol 的**上内边距**：它是 flex 列容器、内部只有 main slot
      一个子项，加内边距 = 子项整体下移，不改变子项自身的布局语义。 */
[data-mobile] .pI_x6G_centerCol {
  padding-top: var(--mp-float-nav-h, 0px) !important;
}

/* ⚠️ 修复 68 的教训（第一版就是死在这里，别再走回去）：
 *
 * 第一版摘掉 type="password" 之后，又用下面这条 CSS 把圆点补了回来：
 *
 *   input[data-mp-key="1"] { -webkit-text-security: disc !important; }
 *
 * 这条规则让整个修复**完全失效**，且失效得很隐蔽 —— 属性层面看起来全对：
 * DOM 里确实已是 type="text"、autocomplete 也确实被摘掉了，但输入法
 * **仍然**切到安全键盘、**仍然**屏蔽剪贴板。
 *
 * 真机实测（Huawei CDY-TN90 / Android 10，焦点停在 API key 输入框上）：
 *
 *   JS 层（探针）：INPUT type=text  ac=(空)  data-mp-key=1  pwdSel=0
 *   IME 层（dumpsys input_method）：
 *     mCurrentTextBoxAttribute: inputType=0xe1
 *
 * 0xe1 = TYPE_CLASS_TEXT(0x1) | TYPE_TEXT_VARIATION_WEB_PASSWORD(0xe0)
 * —— 页面里一个 type=password 都不剩了，键盘拿到的却还是「网页密码框」。
 *
 * 根因：blink 判定「这是个密码字段」用的是
 * Element::ShouldObscureValue()，而它检查的是
 * **computedStyle.textSecurity != none** —— 也就是说，
 * -webkit-text-security: disc **自己就是**那个触发条件；
 * type=password 只是通过 UA 样式表拿到同一条声明而已。
 * 摘掉属性、再手写同一条声明 ＝ 原样把密码语义装了回去。
 *
 * 结论：**「普通键盘」与「圆点遮蔽」在 Android WebView 上不可兼得。**
 * 要剪贴板就必须放弃遮蔽 —— 这正是用户明确要的效果（「跟普通输入框一样」）。
 * 所以本插件不再为凭据框做任何遮蔽补偿；API key 在设置页里明文可见。
 * 取舍依据：API key 是用户自己粘贴进去的、本就掌握在自己手里的一串字符，
 * 而安全键盘是**让这个功能彻底不可用**。可接受。
 *
 * ⚠️ 本注释位于 CSS 模板字符串内部：**禁止出现反引号**，那会提前终止字符串。
 *    上面提到的属性名一律不加 markdown 反引号，就是这个原因。 */

/* 官方扫光是 steps(48) 的离散跳变，窄屏上约等于每秒 32 次跳。
   改成线性插值，观感平滑很多（不影响「一明一暗」的扫光本身）。 */
[data-mobile] ._sweep_1rdzk_34,
[data-mobile] ._highlight_1rdzk_54 {
  animation-timing-function: linear !important;
}
`;

		function injectStyles() {
			if (document.querySelector("style[data-plugin-css='dsh-android-mobile/main.css']")) return;
			const tag = document.createElement("style");
			tag.dataset.plugin = "dsh-android-mobile";
			tag.dataset.pluginCss = "dsh-android-mobile/main.css";
			tag.textContent = CSS;
			document.head.appendChild(tag);
		}

		// ── 3. 消息 meta 精简：保留 "22:41 · 用时 3秒"，删 " · 首 token … / … tok/s" ──
		function trimMeta(el) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const m = /^(.+?·\s*用时\s*[^·]*?)(\s*·.*)?$/.exec((el.textContent || "").trim());
			if (!m || !m[2]) return;
			el.textContent = m[1];
		}

		// 缩写读取官方 aria-label；只写自有属性，保留 React 管理的文本和分隔符。
		// 直接覆盖 nodeValue/textContent 会破坏后续更新，出现旧数字或原文回跳。
		function mpCompactStats() {
			if (!mpIsMobile()) return;
			for (const stat of document.querySelectorAll('[data-composer-dock] [data-composer-stat]')) {
				const button = stat.querySelector("button[aria-label]");
				if (!button) continue;
				const raw = button.getAttribute("aria-label") || "";
				let compact = raw;
				if (stat.getAttribute("data-composer-stat") === "activity") {
					compact = raw.replace(/\s*[·•]?\s*[\d.,]+\s*tok\/s\s*$/i, "");
				} else if (stat.getAttribute("data-composer-stat") === "usage") {
					compact = raw.replace(/\s+tok\b/gi, "").replace(/缓存命中/g, "缓存");
				}
				compact = compact.replace(/\s*[·•]\s*/g, " · ").replace(/\s*[·•]\s*$/, "").trim();
				if (button.getAttribute("data-mp-stat-text") !== compact) {
					button.setAttribute("data-mp-stat-text", compact);
				}
			}
		}
		// 数字更新可以只改 aria-label；原全局 observer 不监听属性，会漏掉这条路径。
		const mpStatsObserver = new MutationObserver(mpCompactStats);

		// ── 6. 侧栏点「新会话」或「会话条」后自动收回抽屉 ──
		// ⚠️ 已停用：官方自己的抽屉会在切换会话后处理收起，插件不再代劳。
		function closeDrawerAfterClick() { }
		function onNewSessionClick(e) {
			if (!mpIsMobile()) return; // 修复 40：收抽屉仅移动端有意义
			// 覆盖两种新建会话按钮（中英文界面）：
			//   顶部按钮 aria-label="新建会话" / "New session"，
			//   项目行右侧 ➕ aria-label="在“{name}”中新建会话" / "New session in {name}"。
			const t = e.target.closest('button[aria-label="新建会话"], button[aria-label*="中新建会话"], button[aria-label="New session"], button[aria-label*="New session in"]');
			if (!t) return;
			closeDrawerAfterClick();
		}
		function onSessionRowClick(e) {
			// 仅移动端抽屉场景
			if (!mpIsMobile()) return; // 修复 40：与 CSS 作用域同源
			// 点击会话条主体（非 ⋮ 操作按钮）时选中并收抽屉
			const row = e.target.closest('.YDXeBa_sessionRow');
			if (!row) return;
			if (e.target.closest('.YDXeBa_rowActions')) return;
			closeDrawerAfterClick();
		}
		// ── 6b. 侧栏点「全局面板」条目（插件 / 定时任务）后同样自动收回抽屉 ──
		//
		// 修复 66（2026-10-04 啾灵真机反馈）：
		//   现象：侧栏点「插件」后主区**已经**切到插件页，但侧栏抽屉还开在原地，
		//         要用户再点一次遮罩才收起来。
		//   根因：上面两个监听器只覆盖「新会话」与「会话条」，而全局面板条目是
		//         sidebar.panellist 槽位注册的**另一类**按钮（PanelRow），
		//         它的 onClick 只调 selectPanel(id)，从不经过 closeDrawerAfterClick()
		//         → 主区切了、抽屉没人收。
		//
		//   ⚠️ 判据用官方 PanelRow 的构建期哈希类名 `hHd-Xa_panelRow`，
		//      与本项目既有做法一致（.hHd-Xa_toggle / .hHd-Xa_root 同理）；
		//      再补一条结构性兜底 `hHd-Xa_panelList button`（容器内的按钮），
		//      这样即便官方升级把 panelRow 这个类名换掉，只要槽位容器还在就仍能命中。
		//      两条都不会误伤会话条 / 新建按钮 —— 它们在别的容器里。
		//
		//   ⚠️ 用 closest 而不是 e.target.matches：点击通常落在条目内的图标
		//      span / 文字 span 上，只有向上找到那个 button 才是可点实体。
		//      PanelRow 渲染出来的就是 <button class="hHd-Xa_panelRow">，
		//      所以两条选择器最终命中的是同一个元素。
		function onPanelRowClick(e) {
			if (!mpIsMobile()) return; // 修复 40：收抽屉仅移动端有意义
			const t = e.target.closest('.hHd-Xa_panelRow, .hHd-Xa_panelList button');
			if (!t) return;
			closeDrawerAfterClick();
		}

		// ── 4d. 按压反馈（替代触摸端不可用的 :hover，见 CSS 9c/9d）──
		// 触摸端 :active 实测按住时并不匹配；而官方把行底色的视觉反馈挂在
		// :hover 上，手指点完 :hover 会一直粘着（要再点别处才消失），
		// 看起来就像鼠标悬停没走开。这里改成：按下加 .mp-press，抬手/滑走即移除，
		// 反馈只在按住的瞬间存在——更接近原生列表的手感。
		const MP_PRESS_SELECTOR = [
			'.YDXeBa_projectRow', '.YDXeBa_sessionRow', '.YDXeBa_searchResultRow',
			'.YDXeBa_iconButton', '.bhn1Oq_iconButton', '.bhn1Oq_searchButton',
			'.hHd-Xa_iconButton', '.mp-nav-btn'
		].join(',');
		function setupPressFeedback() {
			let pressed = null;
			let safety = 0;
			const clear = () => {
				if (safety) { window.clearTimeout(safety); safety = 0; }
				if (pressed) { pressed.classList.remove('mp-press'); pressed = null; }
			};
			const onDown = (e) => {
				if (!document.querySelector('[data-mobile]')) return;
				const t = e.target instanceof Element ? e.target.closest(MP_PRESS_SELECTOR) : null;
				if (!t) return;
				clear();
				pressed = t;
				t.classList.add('mp-press');
				// 兜底：万一没收到 up/cancel（例如手指划出屏幕），600ms 后自动清掉
				safety = window.setTimeout(clear, 600);
			};
			const onUp = () => clear();
			document.addEventListener('touchstart', onDown, { capture: true, passive: true });
			document.addEventListener('pointerdown', onDown, { capture: true, passive: true });
			document.addEventListener('touchend', onUp, true);
			document.addEventListener('touchcancel', onUp, true);
			document.addEventListener('pointerup', onUp, true);
			document.addEventListener('pointercancel', onUp, true);
			// 手指按住后滑动（滚动列表）不算点击 → 收起反馈
			document.addEventListener('touchmove', onUp, { capture: true, passive: true });
			return () => {
				clear();
				document.removeEventListener('touchstart', onDown, true);
				document.removeEventListener('pointerdown', onDown, true);
				document.removeEventListener('touchend', onUp, true);
				document.removeEventListener('touchcancel', onUp, true);
				document.removeEventListener('pointerup', onUp, true);
				document.removeEventListener('pointercancel', onUp, true);
				document.removeEventListener('touchmove', onUp, true);
			};
		}

		// ── 5. 审批自动上浮到输入框上方 ──
		let lastApprovalCount = -1;
		function watchApproval() {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const badge = document.querySelector("[data-cordis-approval-badge]");
			if (!badge) return;
			const count = parseInt(badge.getAttribute("data-cordis-approval-badge") || "0", 10);
			if (count === lastApprovalCount) return;
			const prev = lastApprovalCount;
			lastApprovalCount = count;
			if (count > 0) {
				// 有新审批：展开面板 + 打开抽屉（面板保留在 layer 内，useDismiss/React 均正常）
				if (badge.getAttribute("aria-expanded") !== "true") badge.click();
				openDrawer();
				positionApprovalPanel();
			} else if (prev > 0) {
				// 审批已处理：收起面板 + 收起抽屉
				if (badge.getAttribute("aria-expanded") === "true") badge.click();
				closeDrawer();
			}
		}

		function openDrawer() { mpOpenDrawer(); }

		function closeDrawer() { mpCloseDrawer(); }

		function positionApprovalPanel() {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			// 面板渲染是异步的（React setState → re-render）：轮询等面板出现再定位
			let tries = 0;
			const tick = () => {
				const panel = document.querySelector(".Nqubda_panel");
				const seat = document.querySelector("[data-composer-seat]");
				if (panel && seat) {
					const r = seat.getBoundingClientRect();
					panel.dataset.mpApproval = "1";
					panel.style.setProperty("--mp-approval-bottom", Math.max(8, window.innerHeight - r.top + 8) + "px");
				} else if (tries++ < 10) {
					setTimeout(tick, 100);
				}
			};
			tick();
		}

						// ── 7b. 插件列表分组（官方 / 用户插件），默认折叠、点击展开 ──
		// 分类规则（用户选定，包名规则）：
		//   官方 = cordis:include、dshmarket（市场本体）、@deepseek-ai/*
		//   用户 = 其余（@xmanrui/*、@dsh-mobile/*、@dsh-android/*、dsh-* 等用户安装/创建的）
		// 官方列表 UI 是 React 渲染的（remote.pluginInventory.list），本函数只做
		// DOM 层"归一化"：React 每次重渲染会打平列表，MutationObserver 侦测后重新
		// 分组（幂等，结构一致时不再改动，避免循环）。
		// ── 7c. 插件卡片描述 ──
		// 官方接口（@deepseek-ai/dsh-host-plugin-inventory）每条只返回 entryId /
		// moduleName / enabled / fiberPhase，**没有 description 字段**；"/plugins/<pkg>/package.json"
		// 也不对外提供（实测 404）。所以这里内置一份人工整理的中文描述表，按 moduleName
		// （取自 .qSYn7G_cardTitle 的 title 属性）匹配，注入到展开后的 .qSYn7G_cardDetails。
		// 生成方式：读取两个模块根下各包 package.json 的 description 后逐条译写，见
		// /root/ui-design/plugin-descs-zh.json（156 条，覆盖全部 182 个插件条目）。
		const MP_PLUGIN_DESCS = {
			"./plugins/argfix3.js":"cordis 工具参数扁平化补丁（会话预设 cordis-argfix 自带）", "@deepseek-ai/cordis-plugin-hmr":"cordis 的热模块替换（HMR）插件", "@deepseek-ai/cordis-plugin-timer":"为 cordis 提供定时器服务",
			"@deepseek-ai/dsh-agent":"Agent 接口、注册表、发起者作用域与事件词汇", "@deepseek-ai/dsh-agent-default-model":"Agent 各入口共用的默认模型选择", "@deepseek-ai/dsh-agent-instructions":"加载工作区 AGENTS.md/CLAUDE.md 指令文件",
			"@deepseek-ai/dsh-agent-loop":"智能体主循环的具体实现插件", "@deepseek-ai/dsh-agent-presets":"按预设 cordis.yml 文件为每个会话组装智能体", "@deepseek-ai/dsh-api-gateway":"Typert 远程宿主分发器与客户端 API 端点",
			"@deepseek-ai/dsh-api-remotes":"远程 BFF 装配与 Host 端 Agent／Session 查找策略", "@deepseek-ai/dsh-attachment-local":"DSH_HOME 内按内容寻址的私有附件存储", "@deepseek-ai/dsh-bash-sandbox":"消费沙箱的 bash 执行器实现，逐条命令受限并报告结果",
			"@deepseek-ai/dsh-client-connection":"连接消费层：上行 HTTP、下行 WebSocket 与可重连的双流", "@deepseek-ai/dsh-client-hmr":"仅开发用的客户端热重载驱动：SSE 帧重建触发 fiber 交换", "@deepseek-ai/dsh-client-locale":"语言插件：Host 侧中英偏好、浏览器回退与命名空间词典",
			"@deepseek-ai/dsh-client-modules":"客户端模块系统：node 半构建入口图，浏览器半为惰性 CJS 表", "@deepseek-ai/dsh-client-runtime":"客户端核心服务：SlotRegistry 与 SessionRuntime", "@deepseek-ai/dsh-client-ui-agent-preset":"智能体预设界面：后续会话默认值、本会话席位与编排编辑器",
			"@deepseek-ai/dsh-client-ui-attachment":"对话输入与消息图片插槽的动态附件展示插件", "@deepseek-ai/dsh-client-ui-brand-official":"Web 端侧边栏与对话 Hero 插槽的官方品牌内容", "@deepseek-ai/dsh-client-ui-commands":"客户端命令界面：全局目录缓存、/ 来源与 popupSelect 注册表",
			"@deepseek-ai/dsh-client-ui-conversation":"会话域：骨架、有序对话流、带 Host 端忙碌回车偏好的输入框与详情宿主", "@deepseek-ai/dsh-client-ui-cordis":"Cordis 动态插件定义卡片：cordis_define 工具行与运行开关", "@deepseek-ai/dsh-client-ui-deliverables":"Web 端产出文件回合尾部与最终回复的可点击文件引用",
			"@deepseek-ai/dsh-client-ui-directory-picker-browse":"应用内目录浏览界面：渲染宿主列举与创建原语的工作区目录流", "@deepseek-ai/dsh-client-ui-goal":"会话目标界面：停靠输入框上方的 GoalBar，读取目标会话投影", "@deepseek-ai/dsh-client-ui-input-trigger":"输入触发管线：检测 / 与 @、候选菜单、把选择路由到已注册来源",
			"@deepseek-ai/dsh-client-ui-jobs":"会话头部后台任务列表：镜像 session/jobs 帧的实时注册表状态", "@deepseek-ai/dsh-client-ui-layout":"外壳插件：三栏 AppFrame 与 ctx.layout 视图状态", "@deepseek-ai/dsh-client-ui-message-feedback":"助手消息操作栏的逐条反馈控件，由 messageFeedback 支撑",
			"@deepseek-ai/dsh-client-ui-model-selection":"模型选择：session.selectModel 上的 /model 弹窗", "@deepseek-ai/dsh-client-ui-permission-presets":"权限界面：新会话默认值设置与当前会话的 /permission 弹窗", "@deepseek-ai/dsh-client-ui-plan":"计划模式输入控件：plan 席位与 /plan 命令通道",
			"@deepseek-ai/dsh-client-ui-reference":"Web 端统一的 @file 与 @session 引用来源", "@deepseek-ai/dsh-client-ui-renderer":"浏览器 UI 渲染器：ctx.uiRenderer 插槽绑定与应用根装配", "@deepseek-ai/dsh-client-ui-settings":"设置域基础插件：settings 命名空间作用域服务与标准设置插槽契约",
			"@deepseek-ai/dsh-client-ui-settings-general":"通用设置分节、外壳头部内容、设置词典与版本化欢迎提示", "@deepseek-ai/dsh-client-ui-settings-models":"模型设置与共享的产品引导弹窗，复用既有设置与凭据关联", "@deepseek-ai/dsh-client-ui-settings-plugin-inventory":"Web 插件设置中的只读 Cordis Loader 清单标签页",
			"@deepseek-ai/dsh-client-ui-settings-plugins":"插件设置分节：各功能自有的标签页与可配置的宿主平面插件卡片", "@deepseek-ai/dsh-client-ui-sidebar":"侧边栏插件：会话多级树、搜索、分组与状态圆点", "@deepseek-ai/dsh-client-ui-skill":"Web 端 skill 引用与专用的 skill 工具行",
			"@deepseek-ai/dsh-client-ui-subagent":"子智能体会话目录、续接路由 UI 与 @ 引用来源", "@deepseek-ai/dsh-client-ui-theme":"主题插件：亮暗与系统主题状态、--dsw-* 令牌样式", "@deepseek-ai/dsh-client-ui-tool":"客户端工具调用树渲染器与按工具键入的展示插槽",
			"@deepseek-ai/dsh-client-ui-trajectory":"轨迹事件账本与交互式耗时总览：注册进会话 ViewMap 的纯消费插件", "@deepseek-ai/dsh-client-ui-user-questions":"ask_user_question 功能：宿主工具挂载与提问界面", "@deepseek-ai/dsh-client-ui-workflow-run":"Web 端持久化工作流运行的会话节点与嵌套成员展开",
			"@deepseek-ai/dsh-client-ui-workspace":"工作区选择插件：向侧边栏与空状态插槽注册 WorkspacePicker", "@deepseek-ai/dsh-code-runtime-worker-thread":"代码执行抽象层的 worker-thread 实现", "@deepseek-ai/dsh-command-compact":"显式压缩会话的斜杠命令",
			"@deepseek-ai/dsh-command-feedback":"仅记录日志的会话反馈与斜杠命令", "@deepseek-ai/dsh-command-goal":"持久化会话目标的斜杠命令", "@deepseek-ai/dsh-commands":"面向 UI 的命令注册表，由插件提供",
			"@deepseek-ai/dsh-compaction-basic":"基于 token 计量的压缩策略与 LLM 摘要后端", "@deepseek-ai/dsh-compaction-tool-result-pruner":"可重放的免模型工具结果头中尾裁剪", "@deepseek-ai/dsh-cordis-client-runner":"动态双半插件的浏览器半：事件订阅、闭包求值与守卫门面",
			"@deepseek-ai/dsh-cordis-host-runner":"动态包定义注册表、host 半沙箱生命周期与调用处理表", "@deepseek-ai/dsh-credentials-local":"文件存储的凭据提供方（$DSH_HOME/.env）", "@deepseek-ai/dsh-file-reference-local":"本地文件系统 ctx.fileReferences 提供方，带限界模糊索引",
			"@deepseek-ai/dsh-fs-observation-policy":"文件上下文策略：经 fs/* 事件闸门实现观察态、先读后写与版本校验", "@deepseek-ai/dsh-fs-sandbox":"文件系统抽象层的沙箱实现：按调用级沙箱模式拦截写/编辑，读取放行", "@deepseek-ai/dsh-goal":"事件溯源的会话目标状态与生命周期服务",
			"@deepseek-ai/dsh-goal-round-driver":"带竞态防护的会话目标轮次驱动", "@deepseek-ai/dsh-host-apiproxy":"API 网关：ApiProxy 契约与 ctx.apiProxy 提供方", "@deepseek-ai/dsh-host-directory-picker-auto":"目录选择抽象层的自适应选择器：按启动环境挂载原生或浏览后端",
			"@deepseek-ai/dsh-host-directory-picker-browse":"目录选择抽象层的应用内浏览后端：宿主文件系统的列举与创建原语", "@deepseek-ai/dsh-host-plugin-inventory":"当前 Cordis Loader 插件状态的只读远程投影", "@deepseek-ai/dsh-host-webserver":"Web 路由注册插件：HTTP 路由、index 改写与静态资源回退",
			"@deepseek-ai/dsh-jobs-local":"后台任务注册表（ctx.jobs）的进程内实现", "@deepseek-ai/dsh-llm":"提供方中立的 LLM 服务接口", "@deepseek-ai/dsh-llm-deepseek":"为 LLM 抽象层实现 DeepSeek chat-completions 适配",
			"@deepseek-ai/dsh-llm-pi-ai":"基于 pi-ai 的 DeepSeek 适配器，dsh-llm-deepseek 的设计验证孪生", "@deepseek-ai/dsh-llm-retry":"按提供方路由的 LLM 请求重试策略", "@deepseek-ai/dsh-message-feedback":"与生命周期绑定的逐条消息评分与备注",
			"@deepseek-ai/dsh-permission-presets":"面向用户的权限预设：一个 Permissions 下拉，统一沙箱模式与审批策略", "@deepseek-ai/dsh-persona":"由编排定义的部署级人格提示分节", "@deepseek-ai/dsh-plan-mode":"按智能体记录的计划模式，含退出确认",
			"@deepseek-ai/dsh-pwsh-sandbox":"消费沙箱的 PowerShell 执行器实现，逐条命令受限并报告结果", "@deepseek-ai/dsh-repeat-tool-reminder":"重复工具调用防护：智能体循环调用时给出提醒", "@deepseek-ai/dsh-sandbox-local":"沙箱抽象层的本地后端（bwrap、landlock、Seatbelt 等）",
			"@deepseek-ai/dsh-sandbox-policy":"逐次调用的沙箱策略解析器与当前模型上下文", "@deepseek-ai/dsh-session":"基于事件溯源的会话存储", "@deepseek-ai/dsh-session-checkpoint-policy":"模型请求与工具副作用前的会话持久化检查点",
			"@deepseek-ai/dsh-session-log-export":"Web 会话日志导出命令与共用的下载弹窗", "@deepseek-ai/dsh-session-persistence-jsonl":"JSONL 持久化会话存储后端", "@deepseek-ai/dsh-session-projection":"会话投影抽象层：投影类型表、提供方契约与当前值注册表",
			"@deepseek-ai/dsh-session-projection-cache":"持久化投影缓存：逐会话检查点、节流写回与冷读回放", "@deepseek-ai/dsh-session-query-sqlite":"带 SQLite FTS5 全文检索的 ctx.sessionQuery 后端", "@deepseek-ai/dsh-session-reference":"跨会话快照引用与持久化的非可信模型上下文",
			"@deepseek-ai/dsh-session-stats":"全日志会话计数与耗时投影 sessionStats", "@deepseek-ai/dsh-session-telemetry-otel":"遥测抽象层的 OpenTelemetry 后端，会话记录交给 OTel 日志管道", "@deepseek-ai/dsh-session-title":"基于日志的会话标题服务与提供方注册表",
			"@deepseek-ai/dsh-session-title-first-prompt-llm":"用首条消息生成会话标题的 LLM 提供方", "@deepseek-ai/dsh-settings-file":"文件存储的设置提供方（settings.yaml）", "@deepseek-ai/dsh-shell-env":"与工具无关的托管 DSH_* shell 环境注册表",
			"@deepseek-ai/dsh-skill":"智能体 Skill 提供方注册表", "@deepseek-ai/dsh-skill-badge":"内置 dsh badge 徽章技能的提供方", "@deepseek-ai/dsh-skill-filesystem":"本地文件系统的 Skill 提供方",
			"@deepseek-ai/dsh-spill-local":"溢出存储抽象层的本地文件系统实现", "@deepseek-ai/dsh-spill-policy":"工具结果溢出策略：超长结果替换为预览与溢出文件路径", "@deepseek-ai/dsh-storage":"存储中枢 ctx.storage：具名后端注册表与数据形态设施",
			"@deepseek-ai/dsh-storage-domain":"领域数据形态：经 schema 校验、可发事件的 KV 领域", "@deepseek-ai/dsh-storage-json":"存储中枢的 JSON 文件 KV 后端", "@deepseek-ai/dsh-subagent":"子智能体抽象层（ctx.subagents）与提供方注册表",
			"@deepseek-ai/dsh-subagent-fork-in-process":"进程内 fork 子智能体后端：用父日志前缀启动子智能体", "@deepseek-ai/dsh-subagent-spawn-in-process":"进程内 spawn 子智能体后端（ctx.agents）", "@deepseek-ai/dsh-subprocess-local":"子进程抽象层的本地实现",
			"@deepseek-ai/dsh-system-prompt":"系统提示词组装注册表", "@deepseek-ai/dsh-token-meter":"可重放的 token 计量服务（ctx.tokenMeter）", "@deepseek-ai/dsh-tool-ask-user":"基于 ctx.userQuestions 抽象层的提问工具",
			"@deepseek-ai/dsh-tool-bash":"面向模型的 bash 工具，支持后台任务与沙箱提权", "@deepseek-ai/dsh-tool-call-timeout-policy":"工具调用超时策略：按工具设置截止时间并返回超时错误", "@deepseek-ai/dsh-tool-cordis":"自省式 Cordis 工具集：查看运行时状态、挂载与卸载模型编写的插件",
			"@deepseek-ai/dsh-tool-fs":"面向模型的文件读写与编辑工具（ctx.fs）", "@deepseek-ai/dsh-tool-fs-search":"面向模型的文件检索工具（glob/grep，ripgrep 支持）", "@deepseek-ai/dsh-tool-goal":"面向模型的同会话目标工具，带执行期权限校验",
			"@deepseek-ai/dsh-tool-jobs":"面向模型的后台任务工具（job_output、job_list、job_kill）", "@deepseek-ai/dsh-tool-present":"把文件登记为本次任务的交付物", "@deepseek-ai/dsh-tool-pwsh":"基于 bash 执行器抽象层的 pwsh 工具",
			"@deepseek-ai/dsh-tool-ralph":"面向模型的 Ralph 循环：全新智能体多轮迭代", "@deepseek-ai/dsh-tool-skill":"面向模型的 skill 加载工具", "@deepseek-ai/dsh-tool-str-replace-editor":"面向模型的文件查看、创建、替换与插入工具",
			"@deepseek-ai/dsh-tool-subagent":"面向模型的子智能体委派工具（ctx.subagents）", "@deepseek-ai/dsh-tool-subagent-control":"全局命名工具：send_message 等子智能体续接操作", "@deepseek-ai/dsh-tool-subagent-control/list-agents":"全局命名工具：send_message 等子智能体续接操作",
			"@deepseek-ai/dsh-tool-subagent-report":"子智能体作用域的 report 上报工具", "@deepseek-ai/dsh-tool-todo":"面向模型的 todo_write 待办工具", "@deepseek-ai/dsh-tool-web":"面向模型的网络工具：web_search 与 web_fetch",
			"@deepseek-ai/dsh-tool-workflow":"面向模型的 workflow 工具：执行 JS 编排脚本", "@deepseek-ai/dsh-tools":"工具注册表与工具执行流水线", "@deepseek-ai/dsh-typert-loader":"生成的 Typert 包贡献的加载器集成",
			"@deepseek-ai/dsh-typert-registry":"生成包反射与 Zod schema 的运行时注册表", "@deepseek-ai/dsh-user-approval":"用户审批抽象层（ctx.approval）：一次性授权经应答方瀑布派发，默认拒绝", "@deepseek-ai/dsh-user-questions":"agent 运行期间向人提问的抽象层（ctx.userQuestions）",
			"@deepseek-ai/dsh-web":"网络访问抽象层 ctx.web：搜索/抓取提供方注册表与 WebError 分类", "@deepseek-ai/dsh-web-app":"浏览器界面 bundle：dsh-base 的 web 补丁层与运行时粘合插件", "@deepseek-ai/dsh-web-app/startup":"浏览器界面 bundle：dsh-base 的 web 补丁层与运行时粘合插件",
			"@deepseek-ai/dsh-web-search-deepseek":"DeepSeek 搜索提供方：经兼容 API 调用原生 web_search", "@deepseek-ai/dsh-workflow-worker-thread":"worker-thread 工作流引擎：宿主事件循环外执行编排脚本", "@deepseek-ai/dsh-workspace":"工作区实体注册表：持久化记录与经校验的会话挂载",
			"@dsh-android/dsh-client-ui-responsive":"移动端核心响应式布局：窄屏抽屉侧栏、底部弹层、手机顶栏与安全区", "@dsh-external/dsh-thinking-loop-guard":"检测并打断思维链死循环：推理反复重复时自动中止本轮并注入纠正提示", "dsh-android-mobile":"移动端界面打磨：顶栏、消息操作、插件分组、设置页两级化等 24 项修复",
			"@xmanrui/dsh-im":"接入九种 IM 机器人与公网 AI Office，把消息转发给本机 Harness", "cordis:include":"Cordis 组合的 include 指令，把外部插件文件并入当前组合", "dsh-agconfig":"在设置里填写 Agnes AI 密钥，供图片与视频生成两个工具共用",
			"dsh-agimage":"Agnes AI 图片生成工具（generate_image），结果图在会话内直接显示", "dsh-agvideo":"Agnes AI 视频生成工具（generate_video），支持文生、首尾帧、参考图三模式", "dsh-away-nudge":"离开提醒：AI 回复后你久未查看时，自动唤醒会话并发微信提醒",
			"dsh-basic-auth":"给 DSH 网页入口加一道账号密码门卫：凭据由浏览器保管，换设备也不用重新登录", "dsh-client-ui-file-upload":"在「+」菜单加「文件上传」，文件落到会话 uploads/ 目录供 AI 读取", "dsh-client-ui-photo-upload":"在「+」菜单加「照片上传」，手机可直接从相册或相机发图",
			"dsh-file-show":"让 AI 把本地文件展示进对话：图片成图卡，其它类型成可点文件卡", "dsh-im-proactive":"让 AI 主动发消息给你的工具（send_im_message）", "dsh-imgswitch":"设置页「图片输入」开关：按渠道或模型开关图片输入，免手改配置",
			"dsh-memory":"跨会话记忆与回复风格：核心记忆常驻注入，另有 7 个记忆工具与设置页", "dsh-restart-local":"独立自重启控制：设置页按钮与 restart_harness 工具", "dsh-skill-list":"在设置 → 插件里加「Skill 列表」页，列出当前会话可用的 Skill",
			"dshmarket":"DSH 可视化插件市场：浏览、搜索并一键安装社区插件",
		};
		// 注入描述。幂等判据：已注入且 dataset.mpDescFor 与目标文案一致则不动 DOM
		// ——否则自己写 DOM 又会触发 observer，重复重建会拖死页面（见修复 24）。
		// 注意：官方只为**已展开**的卡片渲染 .qSYn7G_cardDetails，所以每次最多处理一两张。
		const applyPluginDescriptions = (section) => {
			if (!section) return;
			section.querySelectorAll("li.qSYn7G_card").forEach((card) => {
				const details = card.querySelector(":scope > .qSYn7G_cardDetails");
				if (!details) return;
				const title = card.querySelector(".qSYn7G_cardTitle");
				const mod = (title && title.getAttribute("title")) || "";
				const desc = MP_PLUGIN_DESCS[mod] || "";
				const marked = details.querySelector(":scope > .mp-plugin-desc");
				if (!desc) {
					if (marked) marked.remove();
					return;
				}
				if (marked && marked.dataset.mpDescFor === desc) return;
				const el = marked || document.createElement("p");
				if (!marked) {
					el.className = "mp-plugin-desc";
					// 设置 dialog 子树内一律用 inline style（注入 CSS 会把 dialog 推出屏幕，见 §4.2）
					el.style.cssText = "margin:0 0 8px;color:var(--dsw-alias-label-secondary,#555);"
						+ "font-size:12px;line-height:17px;overflow-wrap:anywhere;";
				}
				el.dataset.mpDescFor = desc;
				el.textContent = desc;
				if (!marked) {
					const dl = details.querySelector(":scope > .qSYn7G_details");
					if (dl) details.insertBefore(el, dl); else details.appendChild(el);
				}
			});
		};

		function setupPluginGrouping(content) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			if (!content || content.dataset.mpPluginGroup) return;
			content.dataset.mpPluginGroup = "1";
			// ── 0.1.5 结构（2026-09-15 重新适配）──
			// 官方自己已经有**两级**分组：
			//   SECTION.qSYn7G_group[data-plugin-scope="preset"] 「会话插件」  → 28 个（当前 Agent 预设）
			//   SECTION.qSYn7G_group[data-plugin-scope="global"] 「全局插件」  → 171 个（系统与所有会话共用）
			// 我们原来那套「用户插件 / 官方插件」是给旧版 182 个扁平列表用的，
			// 套在「会话插件」（28 个全官方）上只会得到一个空的「用户插件 0」——纯噪音。
			// 新规则：**只在「全局插件」这一级内部**再做「用户 / 官方」二分
			// （171 个里混着 14 个自装 + 155 官方 + 2 内置，混排才是问题）；
			// 「会话插件」保持官方原样，不再插手。
			const isOfficial = (moduleName) => {
				if (!moduleName) return true;
				if (moduleName === "cordis:include" || moduleName === "dshmarket") return true;
				return moduleName.startsWith("@deepseek-ai/");
			};
			const isNarrow = () => mpIsMobile();
			// 全局插件里：自装 14 个默认**展开**（这是用户最关心的），官方 155 个默认**折叠**
			// （否则一屏 171 张卡片，滚半天）。空分组直接隐藏。
			const expanded = { user: true, official: false };
			let ul = null;
			const groupOf = (card) => {
				if (card.dataset.mpGroup) return card.dataset.mpGroup;
				const title = card.querySelector(".qSYn7G_cardTitle");
				const moduleName = (title && (title.getAttribute("title") || title.textContent)) || "";
				const g = isOfficial(moduleName) ? "official" : "user";
				card.dataset.mpGroup = g;
				return g;
			};
			const applyVisibility = () => {
				if (!ul) return;
				const counts = { user: 0, official: 0 };
				ul.querySelectorAll(":scope > .qSYn7G_card").forEach((card) => {
					const g = groupOf(card);
					counts[g] = (counts[g] || 0) + 1;
					card.style.display = expanded[g] ? "" : "none";
				});
				ul.querySelectorAll(":scope > .mp-plugin-group").forEach((h) => {
					const g = h.dataset.group;
					// 空分组隐藏：0.1.5 常出现「用户插件 0」（当前预设未用到自装插件），
					// 留一个点不开的空标题只是噪音。
					const empty = (counts[g] || 0) === 0;
					h.style.display = empty ? "none" : "";
					h.classList.toggle("mp-plugin-group-open", !!expanded[g]);
					const btn = h.querySelector(".mp-plugin-group-btn");
					if (btn) btn.setAttribute("aria-expanded", String(!!expanded[g]));
				});
			};
			const toggle = (g) => {
				expanded[g] = !expanded[g];
				applyVisibility();
			};
			const mkHeader = (g, label, count) => {
				const li = document.createElement("li");
				li.className = "mp-plugin-group";
				li.dataset.group = g;
				const btn = document.createElement("button");
				btn.type = "button";
				btn.className = "mp-plugin-group-btn";
				const lab = document.createElement("span");
				lab.className = "mp-plugin-group-label";
				lab.textContent = label;
				const cnt = document.createElement("span");
				cnt.className = "mp-plugin-group-count";
				cnt.textContent = String(count);
				const chev = document.createElement("span");
				chev.className = "mp-plugin-group-chevron";
				chev.textContent = "▾";
				btn.append(lab, cnt, chev);
				btn.addEventListener("click", () => toggle(g));
				li.appendChild(btn);
				return li;
			};
			// ⚠️ 修复 24（UI 卡死/渲染进程崩溃）：
			// 判据必须用 classList.contains，**绝不能用 className 全等**。
			// 展开分组时 applyVisibility() 会 classList.toggle("mp-plugin-group-open")，
			// 于是 header 的 className 变成 "mp-plugin-group mp-plugin-group-open"，
			// 与全等字面量不再匹配 → groupedSignature 永远返回 false → 每次 mutation
			// 都走 replaceChildren 重建 184 张卡片 → 重建自身又产生 mutation →
			// 无限微任务循环，主线程锁死、WebView renderer 崩溃（用户表现为点卡片后 UI 卡死、
			// 必须重新进入 Web）。折叠态恰好全等所以不复现，只有展开分组后点卡片才触发。
			const isGroupHeader = (el, group) =>
				!!el && el.classList && el.classList.contains("mp-plugin-group") && el.dataset.group === group;
			const groupedSignature = (userCards, officialCards) => {
				const kids = Array.from(ul.children);
				if (kids.length !== userCards.length + officialCards.length + 2) return false;
				if (!isGroupHeader(kids[0], "user")) return false;
				if (!isGroupHeader(kids[1 + userCards.length], "official")) return false;
				for (let i = 0; i < userCards.length; i++) if (kids[1 + i] !== userCards[i]) return false;
				for (let i = 0; i < officialCards.length; i++) if (kids[2 + userCards.length + i] !== officialCards[i]) return false;
				return true;
			};
			// ⚠️ 修复 24 加固：自愈型熔断。
			// 上面"幂等"的成立完全依赖 groupedSignature 判据正确；判据一旦失效
			// （修复 24 正是 className 全等被 -open 类打破），replaceChildren 自身
			// 产生的 mutation 会把 normalize 无限重入下去 → 主线程锁死、renderer 崩溃。
			// 这里做**基于时间窗**的熔断：1 秒内重建超过 10 次即认定不收敛，
			// 暂停分组 2 秒（期间只做 applyVisibility，不再重建结构），然后自动复位重试。
			// 效果：即使判据再次失效，最坏也只是"分组短暂不生效"，绝不会拖死页面。
			let rebuildTimes = [];
			const rebuildAllowed = () => {
				const now = Date.now();
				rebuildTimes = rebuildTimes.filter((t) => now - t < 1000);
				if (rebuildTimes.length >= 10) return false;
				rebuildTimes.push(now);
				return true;
			};
			const normalize = () => {
				if (!isNarrow()) return;
				// 描述注入对两个分区都要做（会话插件也有卡片）
				applyPluginDescriptions(content);
				const section = content.querySelector(".qSYn7G_section");
				if (!section) return;
				// ⚠️ 只接管「全局插件」分区（data-plugin-scope="global"）。
				//     「会话插件」交给官方原样渲染（它只有 28 个官方插件，二分无意义）。
				const globalGroup = section.querySelector('.qSYn7G_group[data-plugin-scope="global"]');
				if (!globalGroup) return;
				const u = globalGroup.querySelector("ul.qSYn7G_cards");
				if (!u) return;            // 折叠态官方不渲染 ul，等展开后再说
				if (u !== ul) ul = u;
				const cards = Array.from(u.querySelectorAll(":scope > .qSYn7G_card"));
				if (cards.length === 0) return;
				const search = section.querySelector("input[type=search]");
				const searching = search && search.value.trim().length > 0;
				if (searching) {
					// 搜索时保持官方扁平过滤列表（React 自己渲染）
					if (u.querySelector(".mp-plugin-group")) {
						u.querySelectorAll(":scope > .mp-plugin-group").forEach((h) => h.remove());
						u.querySelectorAll(":scope > .qSYn7G_card").forEach((c) => { c.style.display = ""; });
					}
					return;
				}
				const userCards = [];
				const officialCards = [];
				cards.forEach((card) => {
					(groupOf(card) === "user" ? userCards : officialCards).push(card);
				});
				if (!groupedSignature(userCards, officialCards) && rebuildAllowed()) {
					u.replaceChildren(
						mkHeader("user", "自装插件", userCards.length),
						...userCards,
						mkHeader("official", "官方插件", officialCards.length),
						...officialCards,
					);
				}
				applyVisibility();
			};
			const observer = new MutationObserver(() => normalize());
			// 同步 normalize：MutationObserver 回调在微任务阶段运行，先于浏览器绘制，
			// 因此 React 渲染完扁平列表后、首帧绘制前就完成分组 → 不闪现旧样式。
			// normalize 幂等：第二次进入时结构已一致，不再改动，不会死循环。
			observer.observe(content, { childList: true, subtree: true });
			normalize();
		}


		// ── 6.5 设置页导航专属图标 ──
		// 官方 navIcon() 只给 models / agent-presets / plugins 三个 id 发专属图标，
		// 其余 id 一律落到 IconSettingsOutline16（齿轮）→ 手机上是一排重复齿轮。
		// 这里按导航项文本匹配，替换 .VOzbGW_navIcon 里的 SVG。
		// 规格对齐官方 Icon*Outline16：viewBox 0 0 16 16、fill:none、stroke:currentColor，
		// 因此暗色模式自动跟随文字色，无需额外适配。
		// ⚠️ 用 stroke 而非 fill，9 个并排才不糊；marker 属性 data-mp-icon 做幂等判据。
		const MP_ICONS = {
			// 通用设置：直接复用官方 IconSettingsOutline16 的原始 SVG（实心 fill）。
			// 自绘齿轮在 16px 下无法做到几何对称（实测自绘版中心偏上 1.8px），
			// 复用官方原始路径既精准又与官方视觉一致。
			general: '<path fill="currentColor" d="M14.0861 5.51366C13.8717 5.0575 13.588 4.58542 13.2889 4.18108C13.208 4.07172 13.1596 4.04373 13.0243 4.03054C12.4277 3.97255 11.8245 4.05527 11.2269 3.9972C10.7224 3.94816 10.3133 3.71661 10.0115 3.30919C9.66986 2.84777 9.43973 2.31343 9.09824 1.85234C9.01771 1.74365 8.96805 1.71589 8.83354 1.70282C8.29432 1.65044 7.70402 1.65061 7.16656 1.70282C7.03205 1.71589 6.98239 1.74365 6.90186 1.85234C6.56067 2.31303 6.33025 2.84774 5.98855 3.30919C5.68681 3.71661 5.27774 3.94816 4.77317 3.9972C4.17564 4.05527 3.57239 3.97255 2.97585 4.03054C2.84046 4.04373 2.79208 4.07172 2.71115 4.18108C2.41212 4.58542 2.12835 5.0575 1.91403 5.51366C1.85299 5.64359 1.85286 5.7018 1.91403 5.8319C2.14865 6.33077 2.49748 6.76892 2.73237 7.26854C2.9594 7.7515 2.96041 8.24717 2.73338 8.73044C2.49837 9.23061 2.14891 9.66837 1.91403 10.1681C1.85291 10.2982 1.85299 10.3564 1.91403 10.4863C2.12856 10.9429 2.41185 11.4142 2.71115 11.8189C2.79208 11.9283 2.84046 11.9563 2.97585 11.9694C3.57239 12.0274 4.17564 11.9447 4.77317 12.0028C5.27774 12.0518 5.68681 12.2834 5.98855 12.6908C6.33024 13.1522 6.56037 13.6866 6.90186 14.1476C6.98239 14.2563 7.03205 14.2841 7.16656 14.2972C7.70402 14.3494 8.29432 14.3495 8.83354 14.2972C8.96805 14.2841 9.01771 14.2563 9.09824 14.1476C9.43944 13.687 9.66985 13.1522 10.0115 12.6908C10.3133 12.2834 10.7224 12.0518 11.2269 12.0028C11.8244 11.9447 12.4271 12.0275 13.0243 11.9694C13.1596 11.9563 13.208 11.9283 13.2889 11.8189C13.5891 11.4131 13.872 10.942 14.0861 10.4863C14.1471 10.3564 14.1472 10.2982 14.0861 10.1681C13.8513 9.66861 13.5017 9.23061 13.2667 8.73044C13.0397 8.24717 13.0407 7.7515 13.2677 7.26854C13.5026 6.7689 13.8513 6.33106 14.0861 5.8319C14.1472 5.7018 14.1471 5.64359 14.0861 5.51366ZM15.3035 6.40373C15.0685 6.90359 14.7188 7.34119 14.4841 7.84037C14.4231 7.97025 14.423 8.02855 14.4841 8.15861C14.7189 8.65833 15.0685 9.09611 15.3035 9.59626C15.5308 10.0801 15.5308 10.5744 15.3035 11.0582C15.052 11.5933 14.7225 12.1426 14.37 12.6191C14.0685 13.0265 13.6581 13.259 13.1536 13.3081C12.5566 13.366 11.9541 13.2835 11.3573 13.3414C11.2228 13.3545 11.1731 13.3823 11.0926 13.491C10.7511 13.9521 10.521 14.4864 10.1793 14.9478C9.87828 15.3542 9.46719 15.5869 8.96387 15.6358C8.34008 15.6964 7.66194 15.6966 7.03623 15.6358C6.53291 15.5869 6.12182 15.3542 5.82084 14.9478C5.47911 14.4863 5.24878 13.9517 4.90753 13.491C4.82701 13.3823 4.77734 13.3545 4.64284 13.3414C4.04647 13.2835 3.44373 13.366 2.84653 13.3081C2.34201 13.259 1.93164 13.0265 1.63013 12.6191C1.27867 12.144 0.948453 11.5941 0.696621 11.0582C0.469315 10.5744 0.469279 10.0801 0.696621 9.59626C0.931628 9.09613 1.2813 8.65807 1.51597 8.15861C1.57708 8.02855 1.57702 7.97025 1.51597 7.84037C1.28117 7.34095 0.931635 6.9036 0.696621 6.40373C0.469213 5.91992 0.469367 5.42562 0.696621 4.94183C0.948441 4.40587 1.27868 3.85598 1.63013 3.38092C1.93164 2.97349 2.34201 2.74095 2.84653 2.6919C3.44353 2.63397 4.04599 2.71649 4.64284 2.65856C4.77734 2.64549 4.82701 2.61774 4.90753 2.50904C5.24905 2.04792 5.47913 1.51362 5.82084 1.05219C6.12182 0.645806 6.53291 0.413119 7.03623 0.364178C7.66002 0.303556 8.33816 0.303369 8.96387 0.364178C9.46719 0.413119 9.87828 0.645806 10.1793 1.05219C10.521 1.51365 10.7513 2.04828 11.0926 2.50904C11.1731 2.61774 11.2228 2.64549 11.3573 2.65856C11.9541 2.71649 12.5566 2.63397 13.1536 2.6919C13.6581 2.74095 14.0685 2.97349 14.37 3.38092C14.7214 3.85598 15.0517 4.40587 15.3035 4.94183C15.5307 5.42562 15.5309 5.91992 15.3035 6.40373Z"/><path fill="currentColor" d="M9.13764 7.99999C9.13764 7.3715 8.62855 6.8624 8.00005 6.8624C7.37155 6.8624 6.86246 7.3715 6.86246 7.99999C6.86246 8.62849 7.37155 9.13759 8.00005 9.13759C8.62855 9.13759 9.13764 8.62849 9.13764 7.99999ZM10.4834 7.99999C10.4834 9.37126 9.37132 10.4833 8.00005 10.4833C6.62878 10.4833 5.51674 9.37126 5.51674 7.99999C5.51674 6.62873 6.62878 5.51669 8.00005 5.51669C9.37132 5.51669 10.4834 6.62873 10.4834 7.99999Z"/>',
			model: '<path d="M2 4.2h4.2M9.4 4.2H14"/><circle cx="7.8" cy="4.2" r="1.6"/><path d="M2 11.8h2.6M7.8 11.8H14"/><circle cx="6.2" cy="11.8" r="1.6"/>',
			image: '<rect x="1.8" y="3" width="12.4" height="10" rx="1.6"/><circle cx="5.6" cy="6.4" r="1.1"/><path d="M2.2 11.2l3-2.6 2.4 2 2.3-2.4 3.7 3.4"/>',
			plugin: '<path d="M6.1 1.6c.9 0 1.6.7 1.6 1.6v1h2.6c.5 0 .9.4.9.9v2.4h1c.9 0 1.6.7 1.6 1.6s-.7 1.6-1.6 1.6h-1v3.1c0 .5-.4.9-.9.9H7.2v-1.1c0-.9-.7-1.6-1.6-1.6s-1.6.7-1.6 1.6v1.1H2.9c-.5 0-.9-.4-.9-.9V9.7h1.1c.9 0 1.6-.7 1.6-1.6S4 6.5 3.1 6.5H2V4.3c0-.5.4-.9.9-.9h3.2z"/>',
			bell: '<path d="M4.3 7.2a3.7 3.7 0 0 1 7.4 0v2.3l1.1 1.9H3.2l1.1-1.9z"/><path d="M6.6 13.2a1.6 1.6 0 0 0 2.8 0"/><path d="M13.4 2.4a6.2 6.2 0 0 1 1.5 2.6M2.6 2.4A6.2 6.2 0 0 0 1.1 5"/>',
			// AI 回复风格：复用官方 IconSparkle16（一大两小凹弧星芒，实心）。
			// AI 的通用视觉标识，比自绘的「气泡+三点」更贴题，且与品牌一致。
			style: '<path fill="currentColor" d="M6.1 3.1Q6.6 7.8 11.3 8.3Q6.6 8.8 6.1 13.5Q5.6 8.8 0.9 8.3Q5.6 7.8 6.1 3.1Z"/><path fill="currentColor" d="M11.9 1Q12.2 3.7 14.9 4Q12.2 4.3 11.9 7Q11.6 4.3 8.9 4Q11.6 3.7 11.9 1Z"/><path fill="currentColor" d="M12.5 9.4Q12.7 11.4 14.7 11.6Q12.7 11.8 12.5 13.8Q12.3 11.8 10.3 11.6Q12.3 11.4 12.5 9.4Z"/>',
			agent: '<rect x="3" y="5.4" width="10" height="8" rx="2"/><path d="M8 5.4V3.2"/><circle cx="8" cy="2.2" r="1"/><circle cx="6.1" cy="9.1" r="1"/><circle cx="9.9" cy="9.1" r="1"/><path d="M6.3 11.6h3.4"/>',
			im: '<rect x="2" y="2.4" width="12" height="8.6" rx="2.2"/><path d="M5.4 14.2l1.6-3.2"/><circle cx="6" cy="6.7" r="0.95"/><circle cx="8" cy="6.7" r="0.95"/><circle cx="10" cy="6.7" r="0.95"/>',
			market: '<path d="M2 6.6V4.8l.9-2.2c.2-.4.5-.6 1-.6h8.2c.5 0 .8.2 1 .6l.9 2.2v1.8"/><path d="M2 6.6h12v1.6c0 1.2-1 2.2-2.2 2.2S9.6 9.4 9.6 8.2c0 1.2-1 2.2-2.2 2.2S5.2 9.4 5.2 8.2C5.2 9.4 4.2 10.4 3 10.4S2 9.4 2 8.2z"/><path d="M3.4 10.6v3.2c0 .5.4.9.9.9h7.4c.5 0 .9-.4.9-.9v-3.2"/><path d="M6.6 14.7v-3h2.8v3"/>',
			// 特权权限（uid 2000）：自绘「盾牌 + 钥匙孔」。
			// 语义是「被管住的提权」—— 盾牌代表边界还在（该身份反而没有沙箱），
			// 钥匙孔代表拿得到特权。用描边风格（同 plugin/model 等），
			// 与「通用设置/AI 回复风格」那两个实心官方图标区分开。
			privileged: '<path d="M8 1.8l5.6 2.1v4.5c0 3.2-2.4 5.6-5.6 6.5-3.2-.9-5.6-3.3-5.6-6.5V3.9z"/><circle cx="8" cy="6.9" r="1.35"/><path d="M8 8.25V10.7"/>',
			// 悬浮气泡：圆（气泡本体）+ 一条底线（屏幕/别的应用）。
			// 语义是「浮在其它应用之上」—— 这也是气泡与「IM机器人」那个
			// 「气泡+三点」的区别所在：这里表达的是**层级**，不是对话。
			bubble: '<circle cx="8" cy="6.5" r="4.3"/><path d="M2.2 13.7h11.6"/>',
			// 交流群：双人。前面一个是完整的头+肩，后面那个只画半侧轮廓，
			// 靠「被挡住」表现多人 —— 16px 下画两个完整人会糊成一团。
			community: '<circle cx="5.9" cy="5.9" r="2.5"/><path d="M1.5 13.4c0-2.4 2-4.4 4.4-4.4s4.4 2 4.4 4.4"/><path d="M10.7 3.8a2.5 2.5 0 0 1 0 4.6"/><path d="M12 9.4c1.4.6 2.3 2 2.3 3.8"/>',
		};
		// 文本 → 图标键。先长后短匹配，避免「插件」抢走「插件市场」。
		const MP_NAV_ICONS = [
			["通用设置", "general"],
			["图片输入", "image"],
			["AI 回复风格", "style"],
			["AI回复风格", "style"],
			["Agent 预设", "agent"],
			["Agent预设", "agent"],
			["IM机器人", "im"],
			["IM 机器人", "im"],
			["插件市场", "market"],
			["模型", "model"],
			["插件", "plugin"],
			["离开提醒", "bell"],
			// 本插件自己注册的分节。⚠️ 必须排在「插件」之后：mpIconKeyOf 是
			// 子串匹配、先到先得，「特权权限」不含「插件」所以不冲突，但顺序仍
			// 保持「长/具体优先」的既有约定。
			["特权权限", "privileged"],
			["Privileged access", "privileged"],
			// 本插件自己注册的另两个分节（悬浮气泡 26 / 交流群 27）。
			// ⚠️ 同样必须排在「插件」之后才不会被它的子串匹配抢先；
			//   这两个词本身不含「插件」，所以只是沿用既有顺序约定。
			["悬浮气泡", "bubble"],
			["Floating bubble", "bubble"],
			["交流群", "community"],
			["Community group", "community"],
		];
		const mpIconKeyOf = (label) => {
			const t = (label || "").trim();
			if (!t) return null;
			for (const [needle, key] of MP_NAV_ICONS) if (t.includes(needle)) return key;
			return null;
		};
		// 实心图标（复用官方路径）需 fill，自绘的 8 个走 stroke。按 key 区分。
		const MP_FILL_ICONS = { general: true, style: true };
		const mpMakeIcon = (key) => {
			const body = MP_ICONS[key];
			if (!body) return null;
			const NS = "http://www.w3.org/2000/svg";
			const svg = document.createElementNS(NS, "svg");
			svg.setAttribute("width", "16");
			svg.setAttribute("height", "16");
			svg.setAttribute("viewBox", "0 0 16 16");
			// 根节点始终 fill="none"：实心图标靠 path 自带的 fill="currentColor"，
			// 描边图标靠这里的 stroke 属性，互不干扰。
			svg.setAttribute("fill", "none");
			if (!MP_FILL_ICONS[key]) {
				svg.setAttribute("stroke", "currentColor");
				svg.setAttribute("stroke-width", "1.4");
				svg.setAttribute("stroke-linecap", "round");
				svg.setAttribute("stroke-linejoin", "round");
			}
			svg.setAttribute("aria-hidden", "true");
			svg.style.flex = "none";
			svg.innerHTML = body;
			return svg;
		};
		function setupNavIcons(nav) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			const navList = nav.querySelector(".VOzbGW_navList");
			if (!navList || navList.dataset.mpIcons === "1") return;
			navList.dataset.mpIcons = "1";
			// 幂等归一化：结构已一致就完全不动，避免 observer 自触发死循环。
			const normalize = () => {
				navList.querySelectorAll("button").forEach((btn) => {
					const label = btn.querySelector(".VOzbGW_navLabel");
					const key = mpIconKeyOf(label ? label.textContent : btn.innerText);
					if (!key) return;
					// 官方图标 slot：className 含 navIcon
					const slot = btn.querySelector('[class*="navIcon"]');
					if (!slot) return;
					if (slot.dataset.mpIcon === key && slot.firstElementChild) return; // 已是目标态
					const svg = mpMakeIcon(key);
					if (!svg) return;
					slot.replaceChildren(svg);
					slot.dataset.mpIcon = key;
				});
			};
			// 观察子树：官方 React 可能只重渲染某个 button 的图标 slot（不重建 button），
			// 只观察直接 childList 会漏掉这种情况 → 图标永久丢失。
			// 防自我触发：normalize 的判据是「slot 已带正确 marker 且含 svg 就完全不动」，
			// 因此本函数自己造成的 mutation 在下一轮会立即早退，不会无限循环。
			// 另加 reentry 闸：回调内部同步执行，防御性避免极端情况下的重入。
			let running = false;
			const observer = new MutationObserver(() => {
				if (running) return;
				running = true;
				try { normalize(); } finally { running = false; }
			});
			observer.observe(navList, { childList: true, subtree: true });
			normalize();
		}

		// ── 7. 设置页两级化（全 inline style，不用 CSS 规则——见 CSS 区注释） ──
		function setupSettingsDialog(dialog) {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			// ⚠️⚠️ 0.2.2 起**侧栏根节点也带 role="dialog" aria-modal="true"**
			//   （移动端抽屉的语义化写法）。若不排除，这里会把侧栏当成设置页改造：
			//   侧栏里凭空冒出返回圆钮 + 居中的「设置」标题，而真正的设置页
			//   反倒没人处理 —— 真机表现就是"设置页布局错乱"。
			//   侧栏的识别特征：根节点类名 hHd-Xa_root / 直接子节点有 hHd-Xa_panelList。
			if (dialog.classList.contains("hHd-Xa_root")) return;
			if (Array.from(dialog.children).some((c) => c.classList && c.classList.contains("hHd-Xa_panelList"))) return;
			const nav = Array.from(dialog.children).find((c) => c.tagName === "NAV");
			if (!nav) return;
			// ⚠️ 标记载体必须落在"确认这是设置页"之后：原先先写 mpReady 再找 nav，
			//   一旦首次调用时 React 尚未渲染出 nav，就会永久放弃改造（真机上
			//   表现为"有的设备设置页是改造后的样子，有的还是官方原样"）。
			if (dialog.dataset.mpReady) return;
			dialog.dataset.mpReady = "1";
			dialog.dataset.mpSettings = "1";
			// 隐藏安卓壳遗留的"开发者选项"：其功能依赖 window.androidBridge /
			// /api/android/*，在 Web 进程中不存在，保留会进入一个空壳页面。
			nav.querySelectorAll("button").forEach((b) => {
				if ((b.innerText || "").trim() === "开发者选项") b.style.display = "none";
			});
			// ⚠️ 0.2.2 的结构变了：内容区 .VOzbGW_options 是 dialog 的**直接子元素**，
			//   不再是 nav 的兄弟。原来写 nav.nextElementSibling 会取到 .VOzbGW_header
			//   （官方给它 display:none），于是"点二级项"显示的是空内容区。
			const content = dialog.querySelector(".VOzbGW_options") || nav.nextElementSibling;
			setupPluginGrouping(content);
			setupNavIcons(nav);
			const isNarrow = () => mpIsMobile();
			// — 全屏 + 纵向布局（避免旧样式闪烁：立即应用，不等动画结束） —
			Object.assign(dialog.style, {
				width: "100vw", maxWidth: "100vw", height: "100vh", maxHeight: "100vh",
				borderRadius: "0", boxShadow: "none", position: "fixed", top: "0", left: "0",
				flexDirection: "column", overflow: "hidden",
			});
			const overlay = dialog.parentElement;
			if (overlay) {
				overlay.style.left = "0";
				overlay.style.width = "100vw";
				overlay.style.height = "100vh";
				overlay.style.alignItems = "stretch";
			}
			// ⚠️ navTitle 是 dialog 的直接子元素，不是 nav 的子元素。
			//   原来写 nav.querySelector 恒为 null → 官方的左上角「设置」大标题从没被藏掉，
			//   和插件加的居中标题重复显示，这就是"右上角/左上角各有一个设置"的来源。
			const navTitle = dialog.querySelector(".VOzbGW_navTitle");
			if (navTitle) navTitle.style.display = "none";
			// 顶部返回条：只有圆形返回按钮 + 标题，不再放 ✕
			const backBar = document.createElement("div");
			backBar.className = "mp-back-bar";
			backBar.innerHTML = '<button type="button" class="mp-back-circle" aria-label="返回">‹</button>'
				+ '<span class="mp-title">设置</span>';
			Object.assign(backBar.style, {
				display: "flex", flex: "0 0 auto", alignItems: "center", gap: "4px",
				minHeight: "56px", padding: "8px 12px", boxSizing: "border-box",
				borderBottom: "none", background: "transparent", position: "relative",
			});
			// 回退 CSS 规则：用 !important 压内联 padding，让出状态栏
			const sysTop = getComputedStyle(document.documentElement).getPropertyValue("--dsh-android-system-top").trim() || "0px";
			backBar.style.setProperty("padding-top", sysTop, "important");
			const backBtn = backBar.querySelector(".mp-back-circle");
			Object.assign(backBtn.style, {
				width: "36px", height: "36px", borderRadius: "50%", background: "var(--dsw-alias-interactive-bg-hover, #f0f0f0)",
				border: "none", cursor: "pointer", display: "inline-flex", alignItems: "center",
				justifyContent: "center", fontSize: "20px", color: "#222", flex: "0 0 auto",
			});
			const titleSpan = backBar.querySelector(".mp-title");
			Object.assign(titleSpan.style, {
				position: "absolute", left: "50%", top: "50%", transform: "translate(-50%, -50%)",
				maxWidth: "70%", fontSize: "17px", fontWeight: "600",
				color: "var(--dsw-alias-label-primary, #222)", whiteSpace: "nowrap",
				overflow: "hidden", textOverflow: "ellipsis", padding: "0 4px",
			});
			dialog.insertBefore(backBar, nav);
			const closeDialog = () => {
				const mask = dialog.parentElement?.querySelector(".VOzbGW_mask");
				if (mask) mask.click();
			};
			// 返回按钮：一级页关闭设置，二级页回一级
			const showList = () => {
				titleSpan.textContent = "设置";
				// 用 !important 压过 dsh-market 的
				//   @media (max-width:560px){ [role=dialog]:has([data-dsh-market-root])>nav{display:none} }
				// 市场组件（data-dsh-market-root）挂载后一直留在 dialog 里，:has() 只看 DOM
				// 存在性不看可见性，返回列表页时普通内联 display 会被该规则覆盖 → 列表页空白。
				nav.style.setProperty("display", "flex", "important");
				if (content) content.style.display = "none";
				applyNavStyle();
			};
			const showContent = (titleText) => {
				const sectionTitle = content?.querySelector(".zGbnIq_title, h2");
				titleSpan.textContent = titleText || (sectionTitle ? sectionTitle.textContent : "设置");
				// 与 showList 对称：用 !important，确保能覆盖 showList 留下的 flex !important
				nav.style.setProperty("display", "none", "important");
				if (content) {
					content.style.display = "flex";
					content.style.flexDirection = "column";
					content.style.flex = "1 1 0%";
					content.style.minHeight = "0";
					content.style.overflowY = "auto";
				}
				const options = content?.querySelector(".VOzbGW_options");
				if (options) {
					options.style.flex = "1 1 0%";
					options.style.minHeight = "0";
					options.style.overflowY = "auto";
				}
			};
			// 设置页的「退一层」语义（二级 → 一级 → 关闭），返回按钮与**系统返回键**共用。
			// 系统返回键由原生经 "window.__DSH_MOBILE_BACK__" 调进来，见 §12。
			const backOneLevel = () => {
				const atList = !content || content.style.display === "none";
				if (atList) closeDialog();
				else showList();
			};
			backBtn.addEventListener("click", backOneLevel);
			// ⚠️ 用 WeakMap 而不是 DOM 属性存回调：DOM 属性只能存字符串，
			//   而这里要存的是带闭包的函数（"content"/"closeDialog" 都在闭包里）。
			mpDialogBack.set(dialog, backOneLevel);
			// nav 竖向化 + 分类小标题 + 可滚动
			const applyNavStyle = () => {
				if (!isNarrow()) return;
				nav.style.flexDirection = "column";
				nav.style.alignItems = "stretch";
				nav.style.overflowY = "auto";
				nav.style.overflowX = "hidden";
				nav.style.width = "100%";
				nav.style.gap = "2px";
				nav.style.padding = "10px";
				nav.style.flex = "1 1 0%";
				nav.style.minHeight = "0";
				const navList = nav.querySelector(".VOzbGW_navList");
				if (navList) {
					navList.style.flex = "1 1 0%";
					navList.style.minHeight = "0";
					navList.style.overflowY = "auto";
				}
				if (navList && !navList.querySelector("[data-mp-group]")) {
					const groups = [
						{ label: "通用", items: ["通用设置"] },
						{ label: "模型", items: ["模型"] },
						{ label: "插件", items: ["插件"] },
						{ label: "高级", items: ["Agent 预设", "快照", "附件缓存", "特权权限", "悬浮气泡"] },
					];
					for (const g of groups) {
						const first = [...navList.querySelectorAll("button")].find((b) => g.items.includes((b.innerText || "").trim()));
						if (!first) continue;
						const label = document.createElement("div");
						label.className = "mp-nav-group";
						label.dataset.mpGroup = "1";
						label.textContent = g.label;
						label.style.cssText = "padding:14px 16px 4px;font-size:12px;font-weight:600;color:var(--dsw-alias-label-secondary,#888);";
						navList.insertBefore(label, first);
					}
				}
				Array.from(nav.children).forEach((c) => {
					if (c.querySelector && c.querySelector("button")) {
						c.style.flexDirection = "column";
						c.style.overflowX = "hidden";
						c.style.gap = "2px";
						c.style.flex = "1 1 0%";
						c.style.minWidth = "0";
					}
				});
				nav.querySelectorAll("button").forEach((b) => {
					b.style.width = "100%";
					b.style.minHeight = "50px";
					b.style.justifyContent = "flex-start";
					b.style.padding = "0 16px";
					b.style.borderRadius = "12px";
					b.style.fontSize = "15px";
					b.style.flex = "none";
					b.style.textAlign = "left";
					// 移动端一级列表不显示"当前项"高亮（官方 .VOzbGW_navCell.VOzbGW_active
					// 的背景色块）：二级页是全屏的，nav 根本看不见，回到列表时这行残留的
					// 色块只会让人误以为"刚才那下没退掉"（用户 2026-09-14 反馈）。
					// ⚠️ 必须用内联样式：匹配设置 dialog 的 CSS 规则会把 dialog 推出屏幕
					// （x=-301、关不掉），此处禁用 CSS 规则。内联样式优先级高于该类规则，
					// 且官方 React 不管理 background 属性，重渲染不会把它冲掉。
					b.style.background = "transparent";
				});
			};
			const contentHeader = content?.querySelector(".VOzbGW_header");
			if (contentHeader) contentHeader.style.display = "none";
			nav.addEventListener("click", (e) => {
				const btn = e.target.closest("button");
				if (!btn) return;
				const titleText = (btn.innerText || "").trim();
				setTimeout(() => showContent(titleText || "设置"), 0);
			});
			// 立即应用，去掉 800ms 延迟（旧样式闪烁根因）
			applyNavStyle();
			showList();
		}

			// ── 8. 移动端外壳（0.1.5 融合版）──
			// 官方 0.1.5 的窄屏形态只是把侧栏压成 56px 图标条（正文仅 334px）；
			// 旧版自带的抽屉/手机顶栏来自已停用的 @dsh-android/dsh-client-ui-responsive
			// （它在新版槽位校验下渲染即抛异常 → 整页白屏，故弃用）。
			// 融合方案：保留官方骨架 —— 正文占满全宽、侧栏改为浮层抽屉，
			// 官方 header（会话标题/模式/⋯ 菜单含「下载 Session 日志」/右侧栏按钮）
			// 与 对话/轨迹 tabs 原样保留，不再自建顶栏与 ⋮ 菜单。
			const MP_NARROW = 1024;   // 与官方 SIDEBAR_AUTO_COLLAPSE 一致
			let mpShellBuilt = false;
			// ⭐ 修复 47（2026-09-17 啾灵反馈）：汉堡节点的**自持引用**。
			//   官方 React 提交阶段会把 header 的子节点整个 removeChild 掉（实测栈：React j4/xt），
			//   锚在 header 里的汉堡被连带摘走 → detached；而重挂路径用 querySelector 查它，
			//   查询**只匹配已连接节点** → 查不到 → 早退 → 汉堡永远回不来。
			//   自持引用后，detached 也能无损重挂（监听器都还在节点上）。
			var mpNavBtnEl = null;
			// 关闭动画进行中的闸（修复 25）：期间 mpSyncDrawer 不得把抽屉又拉回来；
			// 关闭中再点汉堡 = 取消关闭、直接重开。
			let mpClosing = false;
			let mpCloseTimer = null;
			// 修复 27：设置弹窗打开期间的「视觉收起」标记 —— 此时只把抽屉滑出屏幕，
			// 绝不点官方 toggle（官方 railIn 动画会给 .hHd-Xa_footArea 加 opacity:0，
			// 而设置弹窗的 DOM 就住在 footArea 里 → 整页隐身 150ms → 闪过对话界面）。
			// 官方收起推迟到弹窗关闭后由 mpSyncDrawer 补做（morph 在屏幕外，看不见）。
			let mpModalClose = false;
			// 修复 56：等待官方侧栏收起期间用的观察器与兜底定时器。
			let mpCollapseObs = null;
			let mpCollapseFallback = null;
			// §12：设置弹窗 → 它的「退一层」回调。用 WeakMap 而非 DOM 属性，
			//   因为存的是带闭包的函数（"content"/"closeDialog" 都在 setupSettingsDialog 的闭包里）。
			const mpDialogBack = new WeakMap();

			function mpIsNarrow() { return window.innerWidth < MP_NARROW; }

			// ══ 修复 40：移动端模式总开关 ══
			// 啾灵要求：「手机访问就用我们现在适配的UI，然后电脑那边完全不影响」。
			// ⚠️ 只判宽度不够：桌面浏览器窗口完全可以 <1024（未最大化时），
			//    那样"缩小窗口的电脑"也会被套上手机 UI。
			// 判据 = 窄屏 **且** 不是「悬停可用 + 精确指针」设备（= 确有鼠标/触控板）。
			//   · 这个判据**只做减法**：手机绝不会同时报告 hover:hover + pointer:fine，
			//     所以对真机零风险；
			//   · 任何鼠标设备无论窗口多窄，都保持官方 UI（正是她要的"完全不影响"）；
			//   · matchMedia 缺失/抛错 → 失败**开放**（退回纯宽度判据），
			//     绝不因为判据故障让手机失去适配。
			// ⚠️ 用 var（不是 const）：下面的闭包可能在模块常量初始化前被调用，const 会撞 TDZ。
			var mpDesktopMq;

			function mpIsPointerDesktop() {
				try {
					if (mpDesktopMq === undefined) {
						mpDesktopMq = window.matchMedia
							? window.matchMedia("(hover: hover) and (pointer: fine)") : null;
					}
					if (mpDesktopMq) return !!mpDesktopMq.matches;
					return !!(window.matchMedia
						&& window.matchMedia("(pointer: fine)").matches
						&& window.matchMedia("(hover: hover)").matches);
				} catch (e) { return false; }
			}
			function mpIsMobile() {
				if (!mpIsNarrow()) return false;
				if (mpIsPointerDesktop()) return false;
				return true;
			}
			

			// data-mobile：旧版由 ui-responsive 设置，新版无人设置，而本插件所有 CSS
			// 规则都挂在 [data-mobile] 下（桌面端因此完全不受影响）。
			// ══════════════════════════════════════════════════════════════════
		// 修复 55：失效 CSSModule 类名的「别名桥接」
		//
		// 官方 CSS Module 的类名是 "{hash}_{local}"（如 ZTP-Xa_frame），hash 每次
		// 发版都会变，而本插件的 CSS 与 JS 选择器全部按旧 hash 写死 → 换版本后
		// 样式与交互同时失效（实测 [class*="pI_x6G"] 命中 0 个元素，布局退化成
		// 「56px 图标条 + 334px 正文」，抽屉压窄正文、按钮换行错乱）。
		//
		// 做法：不重写那 37 条 CSS 规则、也不改 ~40 处 JS 选择器，而是**给现有
		// 元素补上旧类名**。局部名（frame/sessionRow/toggle…）是稳定的，所以按
		// 「局部名投票」就能把旧 hash 推导到当前 hash；一旦推导出来，原有规则与
		// 选择器零改动全部复活。官方下次再换 hash 也会自动重新推导。
		//
		// MP_LEGACY_HASHES 由构建脚本从本文件源码自动提取（含只出现在 JS 里的
		// 局部名，如 hHd-Xa_root / hHd-Xa_collapsed —— 手写极易漏，v1 就漏了它
		// 导致 mpOfficialDrawerOpen() 恒 false、抽屉打不开）。
		// ══════════════════════════════════════════════════════════════════

		const MP_LEGACY_HASHES = {
						"Nqubda": [
									"panel"
						],
						"VOzbGW": [
									"active",
									"header",
									"mask",
									"navCell",
									"navIcon",
									"navLabel",
									"navList",
									"navTitle",
									"options"
						],
						"Y0dWHa": [
									"detailBody",
									"details",
									"detailsLocation",
									"overviewPreview",
									"split"
						],
						"YDXeBa": [
									"chevron",
									"folder",
									"iconButton",
									"menuOpen",
									"projectRow",
									"rowActions",
									"searchResultRow",
									"selected",
									"sessionRow",
									"time"
						],
						"bOPqQW": [
									"anchor",
									"label",
									"pill",
									"root"
						],
						"bhn1Oq": [
									"iconButton",
									"searchButton"
						],
						"hHd-Xa": [
									"collapsed",
									"footArea",
									"iconButton",
									"newSession",
									"root",
									"toggle"
						],
						"nL4_yW": [
									"moreButton"
						],
						"pI_x6G": [
									"centerCol",
									"frame",
									"rightbarCol",
									"sidebarCol"
						],
						"qSYn7G": [
									"card",
									"cardDetails",
									"cardTitle",
									"cards",
									"details",
									"group",
									"section"
						],
						"uV2eYG": [
									"grow",
									"input",
									"root"
						],
						"wSkVaW": [
									"crumb",
									"crumbCurrent",
									"crumbs",
									"header",
									"headerActions",
									"headerCorner",
									"headerUtilities",
									"tab",
									"tabs",
									"titleCluster",
									"titleRow",
									"viewArea"
						],
						"xzv4MW": [
									"timeEnd"
						],
						"zGbnIq": [
									"title"
						]
			};

		/** 旧类名 → 当前类名 的映射（运行时推导，官方换 hash 后自动更新）。 */
		let mpPairIndex = null;
		let mpAliasDirty = true;
		let mpAliasSig = '';   // 上次生效的映射指纹，用于去重日志与重扫

		/** 桥接自己补上去的旧类名。用来防止「自我强化」，见 [mpCollectCurrentClasses]。 */
		const mpAliasAdded = new Set();

		/**
		 * 官方「仍在用」的 hash 累积集合（**只增不减**）。
		 *
		 * ⚠️ 为什么必须累积、不能只看当下快照（2026-10-04 第二次踩这个坑）：
		 *   官方 CSS Module 是**按需注入**的 —— 组件没渲染时它的 "<style>" 压根不在
		 *   "document.styleSheets" 里。若判据只看当下快照，同一个 hash 会在「出现过」
		 *   与「没出现」之间来回抖动，于是**同一个页面上有的分组头中招、有的正常**
		 *   （真机实测：22 个 ".O_Ebla_root" 里有 2 个被误加 "bOPqQW_root"，其余正常）。
		 *   只要某个 hash **曾经**出现在官方样式表里，就说明官方在用它，永久不再认领。
		 *   真正过期的 hash 永远不会出现在官方样式表里，因此不受影响。
		 */
		const mpOfficialSeen = new Set();

		/**
		 * 预热轮次计数：前 [MP_ALIAS_WARMUP] 轮**只累积证据、不做任何认领**。
		 *
		 * ⚠️ 为什么需要（2026-10-04）：官方 CSS Module 是懒注入的，页面刚加载时
		 *   "document.styleSheets" 里只有少数几张表，"mpOfficialSeen" 因此接近空集。
		 *   若此时就允许认领，**没过期的** hash（如 "YDXeBa"）会因为「当下没证据」
		 *   而被判为过期，转去认领别的 hash —— 真机日志实测：
		 *     "CSSModule 别名桥接: YDXeBa→pXSMma (2 个类名)"
		 *     "CSSModule 别名桥接: YDXeBa→oz9t_a (2 个类名)"
		 *   这与 "bOPqQW→O_Ebla" 是同一个病，只是发生在启动早期。
		 *   预热期的长度取自 "mpInstallAliasBridge" 的 600ms 轮询节奏，约 9 秒。
		 */
		const MP_ALIAS_WARMUP = 15;
		let mpAliasRound = 0;

		/**
		 * 采集当前存在的全部 "{hash}_{local}" 类名，返回 "byHash"（样式表 + DOM 的并集，用于投票）。
		 *
		 * ⚠️ 两个来源必须区别对待（2026-10-04 踩的坑）：桥接会把旧类名**补到 DOM 元素**上，
		 *   若把这些补上去的类名也算作证据，下一轮推导就会「自己证明自己」，越套越偏。
		 *   样式表是官方产物、桥接从不写入，所以它是唯一可信的
		 *   「官方仍在用这个 hash」判据 —— 且必须**累积**记录到 [mpOfficialSeen]。
		 */
		function mpCollectCurrentClasses() {
			const byHash = {};
			const add = (cls, fromSheet) => {
				// ⚠️ hash 字符集必须含 "-" 与 "_"，且可能以下划线开头
				//   （官方实测形如 ZTP-Xa、n_2Q3W、pI_x6G、_0gBrGq）。
				const mm = /^([A-Za-z0-9_][A-Za-z0-9_-]{2,13})_([A-Za-z][A-Za-z0-9]*)$/.exec(cls);
				if (!mm) return;
				/* 官方样式表里出现过 ⇒ 官方在用这个 hash。**累积**记录，见 [mpOfficialSeen]。 */
				if (fromSheet) mpOfficialSeen.add(mm[1]);
				// 跳过桥接自己补的类名：它们不是官方证据
				if (!fromSheet && mpAliasAdded.has(cls)) return;
				(byHash[mm[1]] || (byHash[mm[1]] = {}))[mm[2]] = true;
			};
			// ① 全部样式表（含尚未挂载组件的规则 —— 拿到完整类名域的关键）
			for (const sheet of document.styleSheets) {
				let rules;
				try { rules = sheet.cssRules; } catch (e) { continue; }
				if (!rules) continue;
				/* ⚠️ 必须跳过**插件自己**的样式表：本插件的 CSS 大量使用旧类名
				 *   （如 "[data-mobile] .bOPqQW_root"），若把它当作「官方仍在用」的
				 *   证据，所有旧 hash 都会被判为未过期，桥接彻底失效。
				 *   ⚠️ 判据必须是**全等**比较：官方 DSH 自己也给每个 CSS Module 的
				 *   "<style>" 打 "dataset.pluginCss"（值是模块路径），
				 *   只判 "dataset.pluginCss" 是否存在会误伤 140 个官方样式表。 */
				const owner = sheet.ownerNode;
				if (owner && owner.dataset &&
					owner.dataset.pluginCss === "dsh-android-mobile/main.css") continue;
				const walk = (rs) => {
					for (const r of rs) {
						if (r.cssRules) { walk(r.cssRules); continue; }
						const sel = r.selectorText;
						if (!sel) continue;
						for (const mm of sel.matchAll(/\.(-?[A-Za-z0-9][A-Za-z0-9_-]*)/g)) add(mm[1], true);
					}
				};
				try { walk(rules); } catch (e) { /* 单条异常不影响其余 */ }
			}
			// ② DOM（fromSheet=false：其中的旧类名可能是桥接自己补的，不能当证据）
			for (const el of document.querySelectorAll('[class]')) for (const cls of el.classList) add(cls, false);
			return { byHash };
		}

		/**
		 * 推导「旧 hash → 当前 hash」。
		 * 判据：统计每个旧 hash 的局部名在各新 hash 上的命中数，取最高者；
		 *   · 并列 → 放弃（套错比不套更糟，例如 bOPqQW 与 hHd-Xa 都可能命中同一个）
		 *   · **命中必须 ≥2** → 才采纳
		 *   · 同一新 hash 只允许被一个旧 hash 认领
		 *
		 * ⚠️ 2026-10-03 修复：原判据有一条 "|| locals.length === 1" 的豁免
		 *   （「该旧 hash 只有 1 个局部名时，1 票即可」）。这条豁免在
		 *   "Nqubda: ["panel"]" 上炸了 —— "panel" 是极通用的局部名，官方
		 *   右侧栏的 dockkit 面板（"P3OORG_panel"）也用它，于是桥接把
		 *   "Nqubda_panel" 补到了**那个面板**上；而 §5 的审批浮层规则
		 *   "[data-mobile] .Nqubda_panel" 随即把它变成 fixed 浮层，
		 *   盖住整个上半屏（实测 "[24,0,336,458]"），连「添加一个 API Key」
		 *   对话框都被压在下面 → 用户看到半屏白块。
		 *   同类隐患还有 "zGbnIq: ["title"]"（title 同样通用）。
		 *
		 *   豁免取消后，单名条目一律不参与推导；需要它们的场合改为在 JS 里
		 *   用「当前 hash」直接定位（见 mpCurrentHash 相关辅助）。
		 */
		function mpDeriveAliasMap() {
			const { byHash: cur } = mpCollectCurrentClasses();
			/* 预热期内只累积 "mpOfficialSeen"（上面的采集已完成），不认领任何映射。
			 * 此时官方样式表还没注入齐，认领必然是误伤。见 [MP_ALIAS_WARMUP]。 */
			if (mpAliasRound <= MP_ALIAS_WARMUP) return { map: {}, pairs: [] };
			const owners = {};
			for (const h of Object.keys(cur)) for (const l of Object.keys(cur[h])) (owners[l] || (owners[l] = [])).push(h);
			const map = {};
			const used = {};
			for (const [oldHash, locals] of Object.entries(MP_LEGACY_HASHES)) {
				/* ⚠️ 官方样式表里**曾经**出现过这个 hash ⇒ 它没有过期，不能拿它去认领别人。
				 *
				 * 2026-10-04 踩的坑："bOPqQW"（composer 遥测药丸）既登记在旧表里，
				 * 又是官方当前仍在用的 hash。原逻辑无条件把它从候选池剔除，于是它
				 * 丢掉了自己的 4 票证据，转而去认领了恰好共享 "label" + "root" 两个
				 * 局部名的**无关** hash "O_Ebla"（2 票，刚好够门槛）——
				 * 而 "O_Ebla_root" 是「执行了命令」分组头，"bOPqQW_root" 带
				 * "justify-content: center"，于是那行标题被居中、不再左对齐。
				 *
				 * ⚠️ 判据必须用**累积**集合 "mpOfficialSeen" 而非当下快照：
				 *   官方 CSS Module 按需注入，未渲染组件的规则此刻不在表里。
				 *   用快照会让同一 hash 在「出现过 / 没出现」之间抖动，表现为
				 *   **同一个页面上有的分组头中招、有的正常**（真机实测 22 个
				 *   ".O_Ebla_root" 里只有 2 个被误加）。累积后结论稳定。
				 *
				 * ⚠️ 判据必须是「样式表 **或** DOM」两者取或，且 DOM 侧必须排除桥接自己补的类名：
				 *   官方类名可能**先出现在 DOM、样式表随后才懒注入**。只看样式表会让
				 *   没过期的 hash 在注入空窗期被判成过期 —— 真机日志实测：
				 *     "CSSModule 别名桥接: YDXeBa→oz9t_a (2 个类名)"
				 *   "YDXeBa" 是 workspace 的当前 hash（官方样式表里 36 个局部名齐全），
				 *   却因为那一刻样式表还没到而被误判。这与 "bOPqQW→O_Ebla" 同病。 */
				if (mpOfficialSeen.has(oldHash) || cur[oldHash]) continue;
				const votes = {};
				for (const l of locals) for (const h of (owners[l] || [])) votes[h] = (votes[h] || 0) + 1;
				const ranked = Object.entries(votes).sort((a, b) => b[1] - a[1]);
				if (ranked.length === 0) continue;
				const [bestHash, hit] = ranked[0];
				if (ranked.filter(([, v]) => v === hit).length > 1) continue;
				/* 只认证据充分的映射：至少 2 个局部名同时命中同一个新 hash */
				if (hit < 2) continue;
				/* ⚠️ 光有 2 票远远不够 —— 必须要求**局部名集合高度重合**。
				 *
				 * 2026-10-04 真机日志实测的误伤："YDXeBa→oz9t_a (2 个类名)"。
				 * "YDXeBa" 是 workspace 的**当前** hash（官方样式表里 36 个局部名齐全），
				 * 只是它的组件（侧栏）那一刻还没渲染、样式表也还没懒注入，于是
				 * 「当下无证据」被判成了「已过期」。它转去认领 "oz9t_a"，只因两者
				 * 偶然共享 "time" + "chevron" 两个**通用**局部名，把 "YDXeBa_time"
				 * 补到了折叠轮次的时间戳上。
				 *
				 * 真正的「过期」是**同一个组件换了 hash**，局部名集合基本不变
				 * （"bOPqQW" 换成新 hash 时 anchor/label/pill/root 仍全在）。
				 * 所以用**重合率**作判据：命中数要占双方较小集合的 60% 以上。
				 *   · "YDXeBa"(10) vs "oz9t_a"(10)，命中 2 → 20% ⇒ 拒绝
				 *   · 真换 hash 时局部名几乎全在 ⇒ 接近 100% ⇒ 采纳
				 */
				const otherSize = Object.keys(cur[bestHash] || {}).length;
				const denom = Math.min(locals.length, otherSize);
				if (denom === 0 || hit / denom < 0.6) continue;
				/* 该新 hash 若自身也在旧表里登记过（即它也是官方当前 hash），
				 * 说明它属于另一个组件，不该被认领。 */
				if (MP_LEGACY_HASHES[bestHash]) continue;
				if (used[bestHash]) continue;
				used[bestHash] = true;
				map[oldHash] = bestHash;
			}
			const pairs = [];
			for (const [oldHash, newHash] of Object.entries(map)) {
				for (const l of MP_LEGACY_HASHES[oldHash]) {
					if (cur[newHash] && cur[newHash][l]) pairs.push([`${newHash}_${l}`, `${oldHash}_${l}`]);
				}
			}
			return { map, pairs };
		}

		function mpRefreshAliasMap(force) {
			if (!force && !mpAliasDirty) return;
			const { map, pairs } = mpDeriveAliasMap();
			mpAliasDirty = false;
			const sig = pairs.map((p) => p.join('=')).sort().join(',');
			if (sig === mpAliasSig) return;   // 映射未变 → 不重复打日志
			mpAliasSig = sig;
			mpPairIndex = new Map(pairs);
			mpPruneStaleAliases();            // 撤掉上一轮误加、现已不再成立的类名
			const desc = Object.entries(map).map(([o, n]) => `${o}→${n}`).join(' ');
			if (desc) console.log('[mobile-polish] CSSModule 别名桥接:', desc, `(${pairs.length} 个类名)`);
		}

		/**
		 * 撤掉「已补上去、但当前映射里不再成立」的旧类名。
		 *
		 * ⚠️ 为什么必须有（2026-10-04）：推导判据修正后，之前误加的类名**不会自己消失** ——
		 *   它们留在 DOM 上，而 ".bOPqQW_root" 那条官方规则（"justify-content: center"）
		 *   照旧生效，用户看到的错位不会随代码修复而恢复，除非重启页面。
		 *   真机实测：修好判据后，已被误加的 2 个 ".O_Ebla_root" 仍然是居中状态。
		 */
		function mpPruneStaleAliases() {
			if (!mpAliasAdded.size) return;
			const live = new Set(mpPairIndex ? mpPairIndex.values() : []);
			const stale = [...mpAliasAdded].filter((c) => !live.has(c));
			if (!stale.length) return;
			for (const cls of stale) mpAliasAdded.delete(cls);
			for (const el of document.querySelectorAll('[class]')) {
				for (const cls of stale) if (el.classList.contains(cls)) el.classList.remove(cls);
			}
		}

		/** 给单个元素补齐旧类名（幂等）。 */
		function mpAliasElement(el) {
			if (!mpPairIndex || !mpPairIndex.size) return;
			if (el.nodeType !== 1 || !el.classList) return;
			for (const cls of el.classList) {
				const alias = mpPairIndex.get(cls);
				if (alias !== undefined && !el.classList.contains(alias)) {
					el.classList.add(alias);
					// 记下来：这是桥接自己补的，下一轮推导不能拿它当官方证据
					mpAliasAdded.add(alias);
				}
			}
		}

		function mpAliasTree(root) {
			if (!mpPairIndex || !mpPairIndex.size) return;
			mpAliasElement(root);
			if (root.querySelectorAll) for (const el of root.querySelectorAll('[class]')) mpAliasElement(el);
		}

		let mpAliasObserver = null;
		function mpInstallAliasBridge() {
			mpRefreshAliasMap(true);
			// 官方样式表随组件懒加载，早期推导可能不全 → 持续重推；
			// mpRefreshAliasMap 内部按指纹去重，映射没变时不会重复打日志。
			let rounds = 0;
			const retry = () => {
				rounds++;
				mpAliasRound++;                 // 预热期计数，见 [MP_ALIAS_WARMUP]
				const before = mpAliasSig;
				mpAliasDirty = true;
				mpRefreshAliasMap(false);
				if (mpAliasSig !== before) mpAliasTree(document.body);   // 映射变了才全树重扫
				// 前 40 轮（约 24s）密集；之后放缓到 2s，长期兜底
				if (rounds < 40) setTimeout(retry, 600);
				else if (rounds < 200) setTimeout(retry, 2000);
			};
			setTimeout(retry, 300);
			mpAliasTree(document.body);
			if (mpAliasObserver) return;
			mpAliasObserver = new MutationObserver((records) => {
				mpRefreshAliasMap(false);
				for (const r of records) {
					if (r.type === 'childList') {
						for (const n of r.addedNodes) if (n.nodeType === 1) mpAliasTree(n);
					} else if (r.type === 'attributes') {
						mpAliasElement(r.target);
					}
				}
			});
			mpAliasObserver.observe(document.documentElement, {
				childList: true, subtree: true, attributes: true, attributeFilter: ['class'],
			});
		}

		function mpTeardownAliasBridge() {
			if (mpAliasObserver) { mpAliasObserver.disconnect(); mpAliasObserver = null; }
			// ⚠️ 不能按 split('_')[0] 取 hash：'pI_x6G' 本身就含下划线。
			// 用「旧类名全集」精确判断，只删我们加上的那些。
			const ours = new Set();
			for (const [oldHash, locals] of Object.entries(MP_LEGACY_HASHES)) for (const l of locals) ours.add(`${oldHash}_${l}`);
			for (const el of document.querySelectorAll('[class]')) {
				for (const cls of [...el.classList]) if (ours.has(cls)) el.classList.remove(cls);
			}
			mpPairIndex = null;
			mpAliasDirty = true;
			mpAliasSig = '';   // 重置指纹：否则重装时指纹相同会跳过重新赋值 mpPairIndex
		}

		/* ══ 启动动画收尾信号 ═══════════════════════════════════════════
		 * 原生侧盖了一层不透明的启动动画（头像 + 光环 + 进度环），它需要知道
		 * 「界面什么时候真的能用了」才能收起来。
		 *
		 * 四个候选信号，只有最后一个是对的：
		 *   · "apply()" 返回   → React 还没挂载，界面是白的
		 *   · "document.readyState === "complete"" → 实测在插件激活**之前**就完成
		 *   · data-mobile 已打上 且 外壳已建 → 只说明**本插件**挂好了
		 *   · 上面两条 **且官方前端的启动页已消失** → 界面真的能用了 ✅
		 *
		 * ⚠️ 第三条单独用是不够的 —— 这是本轮修掉的真 bug，实测数据：
		 *
		 *     t=9339ms  interactive  正文 = "HARNESS Loading plugins…"   官方启动页
		 *     t=10231ms interactive  data-mobile=true, .mp-nav-btn 已存在  ← 旧判据在此满足
		 *     t=10475ms complete     正文 = "探索未至之境 选择工作区…"      启动页才消失
		 *
		 *   也就是说旧判据比「界面真的可用」早了 **244ms 以上**，这段时间原生层已经
		 *   收起，用户就看到官方那个 HARNESS / Loading plugins… 白底页闪了几帧。
		 *
		 * 所以判据必须是「官方前端的启动页没了」。那个页面**没有稳定的 class 或 id**
		 * （它由官方前端在运行时构建，随版本变化），但它的**文案**是稳定的英文
		 * "Loading plugins"。这里按文案判定，并做成宽松匹配（大小写不敏感、
		 * 允许中间有省略号或空格差异），避免官方改一个字符就失效。
		 *
		 * ⚠️ 四条判据的实际分工（真机实测，别想当然）：
		 *   "apply()" 被调用时，页面通常**已经跑过启动页了** —— 本插件的客户端半有
		 *   5.5MB，等它解析完就已经接近 "load"，所以 "officialBootVisible()" 在正常
		 *   路径上测到的是 false（诊断里的 "polls=1 bootSeen=false" 就是这么来的）。
		 *   真正把收起时机推后的是 "pageLoaded()"。
		 *   但文案判据**不能删** —— 它是更慢的设备、更冷的缓存下唯一能挡住启动页的
		 *   那一条，只是在本机复现不出来而已。
		 *
		 * ⚠️ 为什么不用「#root 有内容」："#root" 在启动页阶段就已经有 1 个子节点、
		 *   24 个字符了（就是那个启动页本身），所以「有内容」判不出来。
		 *
		 * ⚠️ 必须轮询而不是监听一次："mpSyncMobileAttr()" 由 observer 节流驱动
		 *   （300ms），首次打上 data-mobile 的时刻不确定，单次检查会漏。
		 *   上限 [UI_READY_MAX_WAIT_MS] 后无条件上报 —— 宁可早收也不能不收，
		 *   否则启动页会永久盖住界面（那是最糟的失败方式）。
		 *
		 * ⚠️ 只在移动端生效。桌面端没有原生启动层，上报是空操作（桥不存在）。
		 */
		function mpSignalUiReady() {
			/*
			 * ⚠️⚠️ 桥不在时**不能直接 return**（§2026-10-31）。
			 *
			 * 原实现在这里写 `if (!bridge) return;`，理由是「桌面端没有原生桥」。
			 * 但**「桥还没注入」与「没有桥」看起来完全一样**：
			 *
			 *   · gecko flavor（`com.dsh.webview`）的桥是 WebExtension 在文档
			 *     `document_start` 之后注入的，比本插件（5.5MB 的 client.js）
			 *     早还是晚**取决于握手时序，不保证**；
			 *   · 而 `mo.observe` / 轮询都还没建立时，`apply()` 一执行到这里就
			 *     return ⇒ 之后桥就算注入进来，**再也没有人去看一眼** ⇒
			 *     `onUiReady` 永不上报 ⇒ 启动层永远盖着。
			 *
			 * 真机现场（20261031-053321，vivo V2231A / gecko 92）：
			 * `engine.ready ms=8537` 后 27 秒 `onUiReady` 依然没来，
			 * 页面**零报错**。这正是「桥后到」的形态。
			 *
			 * 所以改成：桥不在就**轮询等它**，最多等 [BRIDGE_WAIT_MS]；
			 * 等到了照常按判据上报，等不到就什么都不做（真正的桌面端场景，
			 * 那里本来也没有原生启动层需要收）。
			 */
			const hasBridge = () => {
				const b = window.DSHAndroid;
				return b && typeof b.onUiReady === "function" ? b : null;
			};

			let bridge = hasBridge();
			if (!bridge) {
				// 桥可能只是还没到：短轮询等它，到了就正常走下面的判据。
				const BRIDGE_WAIT_MS = 15000;   // 与原生看门狗同量级，避免白等
				const t0 = Date.now();
				const wait = window.setInterval(() => {
					bridge = hasBridge();
					if (bridge) {
						window.clearInterval(wait);
						arm();
					} else if (Date.now() - t0 > BRIDGE_WAIT_MS) {
						window.clearInterval(wait);   // 真的没有桥：桌面端，安静退出
					}
				}, 200);
				return;
			}
			arm();

			function arm() {

			let reported = false;
			let timer = 0;
			const report = (reason) => {
				if (reported) return;
				reported = true;
				if (timer) window.clearInterval(timer);
				try {
					bridge.onUiReady();
					console.log("[dsh-android-mobile] onUiReady: " + reason);   // 便于真机核对是哪条判据收的
				} catch (e) { /* 原生侧异常不影响页面 */ }
			};

			/**
			 * 官方前端的启动页是否还在。
			 *
			 * ⚠️ 判据用 **"body.innerText"** 而不是 "#root.innerText"：
			 *   实测启动页阶段 "#root" 的 innerText 只有 24 字符，而 "body" 的匹配
			 *   才是可靠的（两者并非总是同一个文本视图）。用 body 更稳。
			 *
			 * ⚠️ 只在启动阶段查，不存在"用户聊天内容里恰好出现这个词"的误判风险 ——
			 *   这段时间页面里根本还没有会话内容。
			 */
			const officialBootVisible = () => {
				const body = document.body;
				if (!body) return false;
				// "Loading plugins…" / "Loading plugins..." / 大小写差异都算
				return /loading\s*plugins/i.test(body.innerText || "");
			};

			/**
			 * 页面是否已经加载完。
			 *
			 * ⚠️ 这一条是**文案判据的保险**，不是重复。若官方前端把启动页文案本地化
			 *   （中文界面下就不是 "Loading plugins"），"officialBootVisible()" 会永远
			 *   返回 false → 判据提前满足。加上 readyState 之后，那种情况下至少还能
			 *   等页面真正加载完再收。
			 *
			 * 实测冷启动："readyState" 从 "interactive"（正文 = "HARNESS Loading plugins…"）
			 * 变为 "complete" 的时刻，正是真实 UI 出现的时刻（t≈11606ms）。
			 */
			const pageLoaded = () => document.readyState === "complete";

			/*
			 * ⚠️ `readyState === "complete"` 可能**永远等不到**（§2026-10-31）。
			 *
			 * 只要页面上还有任何一个资源没结束（挂住的图片、没超时的 fetch、
			 * 视频 preload），甚至渲染进程被 Gecko 回收后重新挂载，
			 * `readyState` 就会一直停在 `"interactive"` —— 那时 `ready()`
			 * 永远返回 false，`onUiReady` 永远不报，启动层永远盖着。
			 *
			 * 真机现场（20261031-053321，vivo V2231A / gecko 92）：
			 * `engine.ready` 之后 27 秒 `onUiReady` 依然没来，而页面**没有任何
			 * 报错**（`页面错误: 无`）—— 正是这个形态。
			 *
			 * 所以给 `pageLoaded()` 加一个**宽限**：`data-mobile` 与外壳按钮都已
			 * 就位之后，最多再等 [PAGE_LOAD_GRACE_MS] 就认为页面可以交付。
			 * 判据仍然是「本插件的挂载是否完成」，只是不再让一个不可控的
			 * 全局资源状态把整个界面扣住。
			 *
			 * ⚠️ 宽限只放宽 `readyState` 这一条，**不放宽** `data-mobile` 与
			 *   `.mp-nav-btn`：那两条才是「移动端适配真的挂好了」的证据，
			 *   少了它们收起启动层只会露出错乱布局。
			 */
			const PAGE_LOAD_GRACE_MS = 2500;
			let shellReadyAt = 0;   // 外壳就位（data-mobile + nav 按钮）的时刻

			const shellReady = () =>
				document.documentElement.hasAttribute("data-mobile") &&
				document.documentElement.hasAttribute("data-mp-shell");

			const ready = () => {
				if (!shellReady()) { shellReadyAt = 0; return false; }
				if (!shellReadyAt) shellReadyAt = Date.now();
				if (officialBootVisible()) return false;
				if (pageLoaded()) return true;
				// 外壳早就位，但页面 load 迟迟不结束 ⇒ 宽限到点就交付。
				return Date.now() - shellReadyAt >= PAGE_LOAD_GRACE_MS;
			};

			// 先立刻查一次：apply() 之前可能已经由上一轮 observer 打好属性
			if (ready()) { report("立即"); return; }

			const t0 = Date.now();
			let bootSeen = false;      // 是否观测到过官方启动页
			let polls = 0;
			let firstText = "";        // 第一次轮询时看到的正文（诊断用）
			timer = window.setInterval(() => {
				polls++;
				const bodyTxt = (document.body && document.body.innerText) || "";
				if (polls === 1) firstText = bodyTxt.replace(/\s+/g, " ").slice(0, 40);
				if (officialBootVisible()) bootSeen = true;
				if (ready()) {
					// ⚠️ 诊断信息要带**实际观测到的文本**：只报"见到/没见到"无法区分
					//   "判据正确"与"判据根本没生效"，这一轮就因此多绕了一次。
					report(
						(bootSeen ? "官方启动页已消失" : "未见到官方启动页") +
						" [polls=" + polls + " bootSeen=" + bootSeen +
						" 首帧正文=" + JSON.stringify(firstText) + "]"
					);
				} else if (Date.now() - t0 > UI_READY_MAX_WAIT_MS) {
					report("超时兜底 [polls=" + polls + " bootSeen=" + bootSeen + "]");
				}
			}, 50);
			}
		}

		function mpSyncMobileAttr() {				// ⚠️ 修复 40：改由 mpIsMobile()（窄屏 且 非鼠标设备）决定 data-mobile，
				// 全部 CSS 作用域都挂在这个属性上 → CSS 与 JS 永远同一个判据。
				// 离开移动模式时顺手拆掉我们留下的痕迹（平板旋转 / 跨断点缩放）。
				const on = mpIsMobile();
				const has = document.documentElement.hasAttribute("data-mobile");
				if (on && !has) {
					document.documentElement.setAttribute("data-mobile", "");
					mpInstallAliasBridge();
					// 修复 59：进入移动模式后立即算一次居中留白（首屏不等 mutation）。
					mpScheduleHeaderCenter();
					// ⚠️ 修复 40：桌面启动时 mpBuildShellForMobile 没建外壳（正确），
					// 用户把窗口缩窄后才进入移动模式 → 必须在这里补建，否则没有汉堡按钮。
					mpBuildShell(); // 修复 40：内部按 mpIsMobile() 决定是否建外壳（监听器无条件装）
					mpBuildShellForMobile();
				} else if (!on && has) {
					document.documentElement.removeAttribute("data-mobile");
					// 修复 59：属性已摘 → 立即清掉居中用的 CSS 变量（回到官方布局）
					try { mpSyncHeaderCenter(); } catch (e) { /* 静默 */ }
					mpSetDrawerOpen(false);
					mpTeardownAliasBridge();
					mpTeardownMobile();
				}
			}

			function mpSidebarCol() { return document.querySelector(".pI_x6G_sidebarCol"); }
			function mpOfficialToggle() { return document.querySelector(".hHd-Xa_toggle"); }
			// 官方开合状态的唯一事实源。
			//
			// ⚠️ dsh 0.2.2-rc.1 把这个状态从 `.hHd-Xa_root.hHd-Xa_collapsed` 类
			//    搬到了 `.pI_x6G_frame[data-sidebar-drawer][data-sidebar-collapsed]`
			//    属性上，并且侧栏位置改用 `transform: translate(-100%)` 表达
			//    （旧版是 `left: -340px`）。
			//
			//    只认旧类名的后果**不是"抽屉打不开"而是"一进 App 整屏变灰"**：
			//    找不到 hHd-Xa_collapsed 时本函数恒返回 true（误判"已展开"）→
			//    mpSyncDrawer 立刻给遮罩加 .mp-drawer-open（opacity:1 +
			//    pointer-events:auto + inset:0 z=39）→ 而侧栏实际是收起的、
			//    被官方 transform 推出屏幕 → 页面上没有任何抽屉，只有一层
			//    rgba(0,0,0,.35) 遮罩盖住整页，且所有点击都被遮罩吃掉。
			//    真机症状（CDY-TN90 / 2026-11-09）：首页整体偏灰、点哪都没反应，
			//    点一下反而把抽屉点出来（那次点击恰好命中官方 toggle）。
			//
			//    顺序：先认新属性，再退回旧类名兜底（老版本引擎仍走后者）。
			function mpOfficialDrawerOpen() {
				const frame = document.querySelector(".pI_x6G_frame[data-sidebar-drawer]");
				if (frame) return !frame.hasAttribute("data-sidebar-collapsed");
				const col = mpSidebarCol();
				const root = col ? col.querySelector(".hHd-Xa_root") : null;
				return !!root && !root.classList.contains("hHd-Xa_collapsed");
			}
			// ⚠️ 抽屉遮罩已停用（2026-11-09）。官方 0.2.2 自己处理抽屉的关闭
			//   （实测点侧栏外部、按返回键都能正常收起），而我们的遮罩是
			//   inset:0 + z-index:39 的全屏层，在部分机型上会盖住左上角的官方
			//   侧栏按钮，症状就是"那个按钮点不动"。
			//   保留函数壳只为不打断调用方，返回一个不插入文档的游离节点。
			function mpMask() { return document.createElement("div"); }
			// ⚠️ 已停用：不再给侧栏挂 .mp-drawer-open、不再驱动遮罩。
			//   抽屉的开合完全交给官方按钮，插件不参与。
			function mpSetDrawerOpen(open) { return; }
			function mpSyncDrawer() {
				// ⚠️ 已停用（2026-11-09）：抽屉开合彻底交给官方，插件不再同步、
				//   不再自作主张地"视觉收起"，也不再挂遮罩。
				return;
				// 修复 25：关闭动画进行中，绝不能被 observer/resize 把抽屉又拉回来
				//（官方此刻仍是 expanded，mpSetDrawerOpen 会重新加上 mp-drawer-open）。
				if (mpClosing) return;
				// ⚠️ 修复 39：宽屏下抽屉机制整体不适用（浮层 CSS 挂在 [data-mobile] 下），
				// 直接收工并确保遮罩是关闭态——绝不能让遮罩参与宽屏布局。
				if (!mpIsMobile()) { mpSetDrawerOpen(false); return; }
				// 模态弹窗打开时：只做**视觉收起**（抽屉滑出屏幕），不点官方 toggle。
				// （修复 26 教训：官方收起会触发 railIn → footArea opacity:0 → 连坐弹窗。）
				// 弹窗关闭后这里会自然走到下一行，把推迟的官方收起补做掉。
				// ⚠️ modal 查询必须**排除侧栏自己**。
				//   0.2.2-rc.1 起官方给侧栏根节点（.hHd-Xa_root）加了
				//   role="dialog" aria-modal="true"（移动端抽屉的语义化写法），
				//   于是原来的 querySelector 永远命中"侧栏自身"→ 每次 sync 都走
				//   「有弹窗 → 视觉收起抽屉」分支 → 抽屉刚打开 14ms 就被关掉，
				//   表现是点汉堡毫无反应（真机 2026-11-09 实测：
				//   370ms 加上 .mp-drawer-open，384ms 变 .mp-drawer-closing）。
				//   判定标准改为「不在侧栏子树内」才算真弹窗。
				const col0ForModal = mpSidebarCol();
				const modal = [...document.querySelectorAll('[role="dialog"][aria-modal="true"]')]
					.find((el) => !(col0ForModal && col0ForModal.contains(el)));
				if (modal) {
					const col = mpSidebarCol();
					// 只看「视觉开」类：官方 expanded 与否在弹窗期间无所谓，
					// 否则 visualOnly 收起完成后会被这里反复重入（重启定时器）。
					if (col && col.classList.contains("mp-drawer-open")) {
						mpCloseDrawer({ visualOnly: true });
					}
					return;
				}
				// 走到这里说明没有弹窗了：若之前因弹窗做过视觉收起、官方仍 expanded，
				// 现在把它补收起（此刻无弹窗连坐风险，且抽屉在屏幕外，morph 看不见）。
				if (mpModalClose && mpOfficialDrawerOpen()) {
					mpModalClose = false;
					const btn = mpOfficialToggle();
					if (btn) { btn.click(); mpSetDrawerOpen(false); return; }
				}
				mpModalClose = false;
				mpSetDrawerOpen(mpOfficialDrawerOpen());
			}
			function mpToggleDrawer() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// 关闭中再点汉堡 = 取消关闭、直接重开（而不是又去点官方 toggle）
				if (mpClosing) { mpOpenDrawer(); return; }
				// ⭐ 修复 56b：**关闭方向复用 mpCloseDrawer**。
				//   原实现只做 setTimeout(mpSyncDrawer, 60)：官方 React 要 ~800ms 才
				//   写出 collapsed，60ms 时读到过期的 expanded → 又保持打开；而主
				//   observer 监听的是 {childList,subtree,characterData}，**不含 class**
				//   → 官方随后真正收起时无人复查，抽屉以浮层形式永久占屏。
				//   mpCloseDrawer 已含「滑出动画 + 等官方状态翻转 + 超时兜底」，直接复用。
				//   打开方向保持原样（实测 3 轮开合与遮罩关闭均正常）。
				if (mpOfficialDrawerOpen()) { mpCloseDrawer(); return; }
				const btn = mpOfficialToggle();
				if (btn) btn.click();
				// ⚠️ 点完官方 toggle 必须**立刻**把视觉状态切到打开，不能只等 mpSyncDrawer。
				//   实测（dsh 0.2.2-rc.1 / CDY-TN90，2026-11-09）：官方 React 回流慢于 60ms，
				//   那一刻 mpOfficialDrawerOpen() 仍读到 collapsed=true → mpSyncDrawer
				//   反手把 .mp-drawer-open 摘掉 → 抽屉本体被官方 toggle 打开了，
				//   遮罩却没显示 → 点抽屉外空白处关不掉抽屉（遮罩才是关闭的点击接收器），
				//   用户只能靠再点一次汉堡。CSS 已让 .mp-drawer-open 独立决定位置
				//   （left:0 + transform/visibility 双保险），所以先开视觉是安全的。
				mpSetDrawerOpen(true);
				setTimeout(mpSyncDrawer, 60);
				setTimeout(mpSyncDrawer, 300);
			}
			// 修复 25：抽屉关闭。
			// ⚠️ 旧实现（修复 ③）用 .mp-drawer-closing 把抽屉**瞬间**隐藏，但遮罩仍走
			//   opacity .22s 淡出 → 这 220ms 屏幕上只剩一层 35% 的黑，实测对话区亮度
			//   212.7 → 164.8（-23%），啾灵反馈为「整个画面全部变暗，又瞬间回到正常」。
			// 新实现：抽屉自己**滑出**（left .22s）、遮罩**同步淡出**（opacity .22s），
			//   两者同时开始；等抽屉滑出屏幕（240ms）**之后**才点官方 toggle，
			//   官方切 56px 图标条 + rail-in 动画就发生在屏幕外，看不见（保住修复 ③ 的效果）。
			function mpCloseDrawer(opts) {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const visualOnly = !!(opts && opts.visualOnly);
				if (mpClosing) return;
				if (!mpOfficialDrawerOpen()) { mpSetDrawerOpen(false); return; }
				mpClosing = true;
				// 修复 27：弹窗打开期间只做「视觉收起」—— 抽屉滑出屏幕即可，
				// 绝不点官方 toggle（否则官方 railIn 动画 → footArea opacity:0 → 连坐弹窗）。
				// 官方收起推迟到弹窗关闭后，由 mpSyncDrawer 补做。
				if (visualOnly) {
					mpModalClose = true;
					const col0 = mpSidebarCol();
					if (col0) col0.classList.remove("mp-drawer-open");
					const mask0 = document.querySelector(".mp-drawer-mask");
					if (mask0) { mask0.classList.remove("mp-drawer-open"); mask0.classList.add("mp-drawer-closing"); }
					setTimeout(() => {
						const m2 = document.querySelector(".mp-drawer-mask");
						if (m2) m2.classList.remove("mp-drawer-closing");
						mpClosing = false;
					}, 220);
					return;
				}
				// ① 抽屉滑出 + 遮罩淡出：同时开始，一起走完 .22s
				const col = mpSidebarCol();
				if (col) col.classList.remove("mp-drawer-open");
				const mask = document.querySelector(".mp-drawer-mask");
				if (mask) { mask.classList.remove("mp-drawer-open"); mask.classList.add("mp-drawer-closing"); }
				// ② 等抽屉完全滑出屏幕外，再让官方收起（图标条 morph 在屏幕外发生）
				mpCloseTimer = setTimeout(() => {
					mpCloseTimer = null;
					// ⚠️ 修复 26：这里**不能**给抽屉加 .mp-drawer-closing（visibility:hidden）。
					//   抽屉此刻已在屏幕外（left=-340、width:301 !important），官方 56px 图标条
					//   的 morph 本来看不见，加隐藏是多余的"保险"；而 visibility **会继承给子元素**
					//   —— 设置弹窗的 DOM 就在抽屉内部（实测 col.contains(dialog) === true），
					//   于是弹窗被连坐隐藏 200ms → 啾灵看到"进入设置→闪过对话界面→回到设置"。
					const btn = mpOfficialToggle();
					if (btn) btn.click();
					// ⭐ 修复 56：**等官方状态真正翻转**再收尾，不能定时读一次就下结论。
					//   实测时间线：点 toggle 后约 800ms 官方 React 才写入 hHd-Xa_collapsed，
					//   而旧代码只等 200ms 就去读 → 读到过期的 expanded → mpSetDrawerOpen(true)
					//   把抽屉又拉回来（t=3500ms left 从 -340 滑回 0），等官方真收起时已无人复查
					//   → 56px 图标条以浮层形式占屏 + 遮罩盖全屏 = 卡死。
					mpAwaitOfficialCollapsed();
				}, 240);
			}
			// ⭐ 修复 56：等官方侧栏真的收起（hHd-Xa_collapsed 出现）再复位状态。
			//
			// 为什么必须等：官方 toggle 只是改 React state，DOM 上的 collapsed 类
			// 要等一次渲染（实测 240ms~800ms 不等，视负载而定）。旧实现固定等 200ms
			// 就读 mpOfficialDrawerOpen()，读到过期值反而把抽屉重新拉出来。
			//
			// 等待期间**保持 mpClosing=true**：任何 resize / observer 回调都会在
			// mpSyncDrawer 开头因它而早退，不会把抽屉拉回来。
			// 3s 兜底：官方若因故无响应，至少保证遮罩摘掉，绝不留下卡死状态。
			function mpAwaitOfficialCollapsed() {
				let done = false;
				const finish = (why) => {
					if (done) return;
					done = true;
					if (mpCollapseObs) { mpCollapseObs.disconnect(); mpCollapseObs = null; }
					if (mpCollapseFallback !== null) { clearTimeout(mpCollapseFallback); mpCollapseFallback = null; }
					const m2 = document.querySelector(".mp-drawer-mask");
					if (m2) m2.classList.remove("mp-drawer-closing", "mp-drawer-open");
					const c2 = mpSidebarCol();
					if (c2) c2.classList.remove("mp-drawer-closing");
					mpClosing = false;
					// 以官方**当下真实状态**为准做最后一次同步（此时它必然已更新完）
					mpSetDrawerOpen(mpOfficialDrawerOpen());
					if (why === "timeout") console.warn("[mobile-polish] 官方侧栏收起超时，已强制复位抽屉状态");
				};
				// 已经收起（同步路径）→ 直接收尾
				if (!mpOfficialDrawerOpen()) { finish("sync"); return; }
				const col = mpSidebarCol();
				const root = col ? col.querySelector(".hHd-Xa_root") : null;
				if (!root) { finish("noroot"); return; }
				// ① 首选：观察 root 的 class，等到 collapsed 出现
				if (mpCollapseObs) { mpCollapseObs.disconnect(); mpCollapseObs = null; }
				mpCollapseObs = new MutationObserver(() => {
					if (!mpOfficialDrawerOpen()) finish("observed");
				});
				mpCollapseObs.observe(root, { attributes: true, attributeFilter: ["class"] });
				// ② 兜底：官方若没动静（或 root 被 React 换掉）也不留卡死
				if (mpCollapseFallback !== null) clearTimeout(mpCollapseFallback);
				mpCollapseFallback = setTimeout(() => finish("timeout"), 3000);
			}

			function mpOpenDrawer() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// 关闭动画进行中：取消关闭、直接重开（官方仍是 expanded，加上类即可滑回）
				if (mpClosing) {
					if (mpCloseTimer !== null) { clearTimeout(mpCloseTimer); mpCloseTimer = null; }
					mpClosing = false;
					const c = mpSidebarCol();
					if (c) c.classList.remove("mp-drawer-closing");
					const m = document.querySelector(".mp-drawer-mask");
					if (m) m.classList.remove("mp-drawer-closing");
					mpSetDrawerOpen(true);
					return;
				}
				if (mpOfficialDrawerOpen()) return;
				const btn = mpOfficialToggle();
				if (btn) btn.click();
				// ⚠️ 点完官方 toggle 必须**立刻**把视觉状态切到打开，不能只等 mpSyncDrawer。
				//   实测（0.2.2-rc.1 / CDY-TN90）：官方 React 回流慢于 60ms，
				//   那一刻 mpOfficialDrawerOpen() 仍是 false → mpSyncDrawer 反手把
				//   .mp-drawer-open 摘掉 → 抽屉本体显示了，遮罩却没显示 →
				//   点抽屉外空白处关不掉抽屉（遮罩才是关闭的点击接收器）。
				//   CSS 已让 .mp-drawer-open 独立决定位置（left + transform 双保险），
				//   所以这里先开视觉是安全的；官方状态由下面两次 sync 追上。
				mpSetDrawerOpen(true);
				setTimeout(mpSyncDrawer, 60);
				setTimeout(mpSyncDrawer, 260);
			}

			// 汉堡按钮：固定左上、浮于正文之上（自建元素，不注入 React 子树）
			// ⚠️ 修复 40：mpAnchorBurger 提到模块作用域。
			//   原先它是 mpBuildShell 的**内部函数声明**（外部不可见），
			//   而修复 40 要把 MutationObserver 装到 mpBuildShell 里（宽窄屏都要装），
			//   那里引用内部声明会直接 ReferenceError。
			//   顺带把按钮查找从闭包变量改成按类查询（拆解/重建后依然正确）。
			// ══ 修复 59：标题相对**屏幕**绝对居中（2026-09-28 啾灵反馈）══════════
			// 现象：修完长标题重叠后，左边只有汉堡（50px）而右边有「⋯」+「右侧栏」
			//   （约 88px），标题簇只能靠「左右留白相等」来居中 → 实测簇中心 174px，
			//   而屏幕中线 195px，**偏左 21px**。用户要的是「相对屏幕绝对居中」。
			//
			// 为什么不能用 CSS 写死：
			//   右侧控件宽度会随会话变化（只有「⋯」时有 2 个按钮，有「打开方式」
			//   时分体按钮还在占位 —— 虽然移动端已隐藏，但 headerActions 里可能
			//   还有其他官方槽位内容），写死像素必然在某些会话下再次偏。
			//
			// 做法：让左侧留白 = 右侧真实占位，簇中心即恒等于屏幕中线。
			//   设 header 内容区为 [padL, W - padR]，titleRow 内依次是
			//   [titleCluster(flex:1), headerUtilities, headerCorner]，
			//   右侧总占位 R = titleRow 宽 − 簇宽（**与 padL 无关的常量**）。
			//   簇中心 = padL + (W − padL − padR − R)/2，令其 = W/2 解得：
			//     padL = padR + R
			//   ⇒ 一次测量即可精确解出，无需迭代收敛。
			//
			// 安全上限：簇宽至少留 96px，否则极窄屏会把标题挤没（宁可偏左也要看得见）。
			function mpSyncHeaderCenter() {
				const root = document.documentElement;
				// 桌面端：立刻清掉变量 → CSS 回落到兜底值，官方布局零影响。
				if (!root.hasAttribute("data-mobile")) {
					if (root.style.getPropertyValue("--mp-hdr-padl")) root.style.removeProperty("--mp-hdr-padl");
					return;
				}
				const header = document.querySelector(".wSkVaW_header");
				const row = header && header.querySelector(".wSkVaW_titleRow");
				const cluster = row && row.querySelector(".wSkVaW_titleCluster");
				if (!header || !row || !cluster) return;
				// 官方 header 是空壳（Hero 页 / 尚未渲染）时不动
				const rowBox = row.getBoundingClientRect();
				const clBox = cluster.getBoundingClientRect();
				if (rowBox.width < 1 || clBox.width < 1) return;
				const padR = parseFloat(getComputedStyle(header).paddingRight) || 0;
				const rightTotal = Math.max(0, rowBox.width - clBox.width);
				const maxPadL = Math.max(0, header.getBoundingClientRect().width - padR - rightTotal - 96);
				const padL = Math.max(0, Math.min(Math.round(padR + rightTotal), Math.round(maxPadL)));
				const now = root.style.getPropertyValue("--mp-hdr-padl");
				const next = padL + "px";
				// 只在变化时写（避免无谓的样式失效重算）
				if (now !== next) root.style.setProperty("--mp-hdr-padl", next);
			}

			/* ══ 修复 61：标题栏高度 ×1.25（运行时自校准，配合上面 CSS 的
			   min-height: var(--mp-hdr-h)）═════════════════════════════════

			   为什么不能写死：官方 header 是内容驱动高度（修复 30 把官方
			   min-height:76px 覆盖成 0），自然高度实测在 55~65px 之间浮动：
			     · 有模式徽章 / 标题换行 → 约 65px
			     · 无徽章、单行标题      → 约 55px
			   写死 81.25px（=65×1.25）在 55px 状态下就是 +48% —— 真机反馈
			   「有点高过头」正是如此。先量再乘，才是任何状态都精确 +25%。

			   怎么量（关键）：把 inline min-height 临时压成 0px → 读高度 →
			   立刻还原。整段在同一个任务内完成，浏览器不会绘制中间态，
			   所以没有闪烁、不触发可见跳动。

			   成本控制（修复 59 的教训：别在每帧里反复强制同步布局）：
			   用 mpHdrKey =「header 宽 : 内容高」当指纹，只有这两者之一
			   变化时才真的去量。内容高取 titleRow 所有子项的**最大高度**：
			   这些子项在 titleRow 里都是 align-items:center 的 flex 项、
			   **不会**被拉伸，所以高度恒等于内容高度；取最大值可覆盖
			   「模式徽章挂在 headerActions、快照徽章挂在 utilities」等
			   徽章出现在簇外的情况。其余调用只花几次廉价读 + 比较。 */
			const MP_HDR_SCALE = 1.25;
			var mpHdrKey = "";
			function mpSyncHeaderHeight() {
				const root = document.documentElement;
				// 桌面端：清掉变量 → CSS 回落 0px，官方布局零影响
				if (!root.hasAttribute("data-mobile")) {
					if (root.style.getPropertyValue("--mp-hdr-h")) root.style.removeProperty("--mp-hdr-h");
					mpHdrKey = "";
					return;
				}
				const header = document.querySelector(".wSkVaW_header");
				const row = header && header.querySelector(".wSkVaW_titleRow");
				// 官方 header 是空壳（Hero 页 / 尚未渲染）时不动，免得把 0 当基线
				if (!header || !row) return;
				const cluster = row.querySelector(".wSkVaW_titleCluster") || row;
				const width = Math.round(header.getBoundingClientRect().width);
				// 内容高 = titleRow 各子项（含标题簇 / headerActions / utilities /
				// corner）里的最大高度；它们不被拉伸，故等于内容高度。
				let contentH = cluster.getBoundingClientRect().height;
				for (const child of row.children) {
					const h = child.getBoundingClientRect().height;
					if (h > contentH) contentH = h;
				}
				contentH = Math.round(contentH * 100) / 100;
				if (width < 1 || contentH < 1) return;
				const key = width + ":" + contentH;
				if (key === mpHdrKey) return;
				// 量自然高度：inline + important 能压过样式表里的 !important
				const savedPrio = header.style.getPropertyPriority("min-height");
				const saved = header.style.getPropertyValue("min-height");
				header.style.setProperty("min-height", "0px", "important");
				const natural = header.getBoundingClientRect().height;
				if (saved) header.style.setProperty("min-height", saved, savedPrio);
				else header.style.removeProperty("min-height");
				if (!(natural > 0)) return; // 量不到就保持原样，下一帧再试
				mpHdrKey = key;
				root.style.setProperty("--mp-hdr-h", Math.round(natural * MP_HDR_SCALE * 100) / 100 + "px");
			}

			// 修复 59：把 mpSyncHeaderCenter 合并到下一帧执行。
			// MutationObserver 在 body 上以 subtree:true 观察，触发极频繁
			// （每次消息流更新都会来一发）→ 同一帧内最多测一次，避免手机上
			// 反复强制同步布局（getBoundingClientRect）拖慢滚动。
			// ⚠️ 必须用 var（不能用 let/const）：mpSyncMobileAttr 可能在模块求值
			//   走到本行**之前**就调用 mpScheduleHeaderCenter（首屏 mpBuildShell），
			//   而 let 存在 TDZ → 会抛 "Cannot access before initialization"。
			//   var 会提升并初始化为 undefined，`if (mpCenterRaf) return` 对
			//   undefined 判假 → 行为正确。
			var mpCenterRaf = 0;
			function mpScheduleHeaderCenter() {
				if (mpCenterRaf) return;
				mpCenterRaf = requestAnimationFrame(() => {
					mpCenterRaf = 0;
					// 修复 61：高度自校准与居中留白共用这一帧（共用节流，不额外增加重排次数）
					try { mpSyncHeaderHeight(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
					try { mpSyncHeaderCenter(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
					// 修复 66：与上面两件事共用同一帧（零额外重排）
					try { mpSyncFloatNavGap(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
				});
			}

			/* ══ 修复 66：汉堡浮层时给主区让出顶部空间（配合 CSS 的
			   [data-mobile] .pI_x6G_centerCol { padding-top: var(--mp-float-nav-h) }）══

			   背景见 CSS 那条注释：切到全局面板（插件 / 定时任务）后官方顶栏随
			   conversation 一起卸载，汉堡由 mpAnchorBurger() 回落到 body 的
			   fixed 浮层，而面板页自己的顶部留白是给桌面端预留的（Android 恒为 0）
			   → 面板标题撞进汉堡。

			   ⚠️ **量汉堡的真实 rect，不写死像素**：
			     汉堡 top = calc(var(--dsh-android-system-top, 0px) + 6px)；该变量由
			     原生在**全面屏打开时**写入（关闭时显式写回 0px），单位是 **CSS 像素**
			     （原生已把物理像素 ÷ density，见 MainActivity.pushSystemBarInset）。
			     写死 46px 在别的机型/系统栏高度上就会偏，
			     所以直接读 getBoundingClientRect().bottom —— 它是 fixed 元素，
			     页面滚动不影响，读数恒定，也不需要知道系统栏多高。

			   ⚠️ 判据用 "mp-in-header" 类而不是"header 在不在"：这个类正是
			     mpAnchorBurger() 挂载时打的标记（进 header 加、回落 body 摘），
			     与汉堡的实际归属永远一致，不会出现"类与位置打架"的中间态。

			   非移动端 / 汉堡不在浮层 → 变量归 0px，conversation 页逐像素不变。 */
			const MP_FLOAT_NAV_GAP = 8; // 呼吸位：标题不要贴着汉堡下沿
			var mpFloatNavH = "";
			function mpSyncFloatNavGap() {
				const root = document.documentElement;
				if (!root.hasAttribute("data-mobile")) {
					if (root.style.getPropertyValue("--mp-float-nav-h")) root.style.removeProperty("--mp-float-nav-h");
					mpFloatNavH = "";
					return;
				}
				const btn = mpNavBtnEl;
				const floating = !!(btn && btn.isConnected && !btn.classList.contains("mp-in-header"));
				let needed = 0;
				if (floating) {
					const r = btn.getBoundingClientRect();
					if (r.height > 0) needed = r.bottom + MP_FLOAT_NAV_GAP;
				}
				/* 修复 58：全面屏打开时，内容列还要再让出状态栏。
				   ============================================================
				   全面屏把窗口 content 从 [0,108] 变成 [0,0]，WebView 不再自己
				   避让系统栏，状态栏于是直接压在内容上（实测汉堡 rect.top=3.75、
				   正好被时间/电量那一行盖住）。原生侧会把状态栏高度写进
				   --dsh-android-system-top，这里取它。

				   ⚠️ 两种让位（浮层汉堡、状态栏）必须在**同一个变量**里合并：
				     吃这个变量的是 CSS 里那条带 !important 的
				     .pI_x6G_centerCol { padding-top: var(--mp-float-nav-h) }，
				     同属性上再加一条不带 !important 的规则压不过它。
				   ⚠️ 合并用 JS 的 Math.max 而不是 CSS 的 max()：后者要 Chrome 79，
				     本 App 目标内核是 chrome74，老内核上整条声明失效。
				   ⚠️ 汉堡浮层时不必再取 max —— 它本身是 fixed 且 top 已含系统栏高度
				     （calc(--dsh-android-system-top + 6px)），bottom 必然大于状态栏高度；
				     取 max 只是为了在两种形态下都成立，多算一次不影响结果。 */
				const sysTop = parseFloat(
					getComputedStyle(root).getPropertyValue("--dsh-android-system-top"),
				) || 0;
				if (sysTop > needed) needed = sysTop;
				const next = Math.round(needed) + "px";
				if (next === mpFloatNavH) return; // 无变化 ⇒ 不写 DOM ⇒ 不触发 observer
				mpFloatNavH = next;
				root.style.setProperty("--mp-float-nav-h", next);
			}

			function mpAnchorBurger() {
				if (!mpIsMobile()) return; // 修复 40：非移动端绝不挂载汉堡
				// 修复 59：header 内容变化时顺手重算居中留白（已合并到下一帧）。
				mpScheduleHeaderCenter();
				// ⭐ 修复 47：**先查已连接的，查不到就回退到自持引用**（可能是 detached 的）。
				//   ⚠️ 不能只写 document.querySelector(".mp-nav-btn")：它只匹配已连接节点，
				//   汉堡被 React 摘掉后它返回 null → 早退 → 永远不再挂回去（本次 bug 的根因）。
				const btn = document.querySelector(".mp-nav-btn") || mpNavBtnEl;
				if (!btn) return;
				mpNavBtnEl = btn;
				const header = document.querySelector(".wSkVaW_header");
				const usable = header && header.querySelector(".wSkVaW_titleRow");
				if (usable) {
					if (btn.isConnected && btn.parentElement === header) return;
					header.appendChild(btn);
					btn.classList.add("mp-in-header");
				} else {
					if (btn.isConnected && btn.parentElement === document.body) return;
					btn.classList.remove("mp-in-header");
					document.body.appendChild(btn);
				}
			}

			// ══════════════════════════════════════════════════════════════════
			// §12 系统返回键：把 Android 的返回手势/按键接到 Web UI 上
			// ══════════════════════════════════════════════════════════════════
			//
			// 原生侧（MainActivity）拦下返回键后调 `window.__DSH_MOBILE_BACK__()`，
			// 返回值 = 「这一层被消费了吗」。**只有返回 false，原生才退出 App**，
			// 所以下面这个顺序就是用户感知的"返回栈"。
			//
			// 由内到外（与屏幕上的层次一致）：
			//   ① 自建的重命名弹窗
			//   ② 设置弹窗：二级 → 一级 → 关闭（复用返回按钮那一套语义）
			//   ③ 官方其它模态弹窗（兜底：点遮罩 / 派 Escape）
			//   ④ composer 的触发菜单（「/」指令、@ 引用等）
			//   ⑤ 侧栏抽屉
			//   ⑥ 官方右侧栏（「工作区文件 / 新建终端」那一栏）
			//   ⑦ 官方主面板（插件管理器 / 日程 / 快照…）→ 回对话
			//   都没有 ⇒ false，交原生退出
			//
			// ⚠️ ⑥ 与 ⑦ 的先后：右栏在**对话界面之内**（它是会话的停靠面），而主面板
			//   是替换掉整个对话区。所以先退右栏、再退主面板，与视觉层次一致。
			//
			// ⚠️ 判据一律用**可见性**，不能用 DOM 存在性：官方弹窗关掉后节点往往
			//   仍留在 DOM 里（只改 display），按存在性判会永远认为"有东西可关"，
			//   返回键就再也退不出 App 了。

			/** 元素是否真的显示在屏幕上（"display:none" / 已卸载 ⇒ false）。 */
			function mpVisible(el) {
				if (!el || !el.isConnected) return false;
				try { return el.getClientRects().length > 0; } catch (e) { return false; }
			}

			/** 当前可见的官方模态弹窗；没有则 null。 */
			function mpVisibleModal() {
				const all = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
				for (const d of all) if (mpVisible(d)) return d;
				return null;
			}

			/**
			 * 派发一次 Escape —— 官方大量组件用它做"退一层"
			 * （原生返回键在桌面端的对应物）。
			 *
			 * ⚠️ 两处都发：官方有的监听在 "document" 上，有的挂在获得焦点的元素上。
			 */
			function mpDispatchEscape() {
				try {
					const ev = new KeyboardEvent("keydown", {
						key: "Escape", code: "Escape",
						bubbles: true, cancelable: true, composed: true,
					});
					const target = document.activeElement || document.body;
					if (target) target.dispatchEvent(ev);
					document.dispatchEvent(ev);
				} catch (e) { /* 静默：派发失败也不该让返回键卡住 */ }
			}

			/** 官方 "ctx.layout" 服务（拿不到就返回 null，绝不抛）。 */
			function mpLayout() {
				if (!mpCtx) return null;
				try { return mpCtx.get("layout") ?? null; } catch (e) { return null; }
			}

			/**
			 * 官方 "ctx.sidebarRight" 服务 —— 右上角那一栏（工作区文件 / 新建终端）。
			 *
			 * ⚠️ **不要用 "layout.closeRightbar()" 关它**，那是个陷阱：该方法在
			 *   "ILayout" 上的语义是「**报告**右栏为隐藏」（"dsh-client-ui-layout" 的
			 *   注释原文 "Report the right panel as hidden"），它是给占用方
			 *   （"dsh-client-ui-sidebar-right"）上报呈现状态用的。而右栏自己的
			 *   "expanded" 状态住在 sidebar-right 的 store 里，由它反过来调
			 *   "openRightbar"/"closeRightbar" 同步给 layout（见其 client.js 的
			 *   "syncPresentation"）。直接调 layout 只会改 grid 轨道，**右栏的
			 *   "expanded" 仍是 true**，于是面板被滑到屏幕外却还认为自己开着，
			 *   用户再点右上角按钮时行为就错乱了。
			 *
			 * 正确的接缝是 "ctx.sidebarRight"（"provide("sidebarRight", controller)"）：
			 *   · "isExpanded()" —— 「这一层开着吗」，判据与官方自己一致；
			 *   · "toggleExpanded()" —— 收起/展开，内部走 store action，会正常
			 *     同步 layout 轨道与面板滑出动画。
			 *
			 * @returns {object|null} 服务实例；拿不到（未装该插件）返回 null。
			 */
			function mpSidebarRight() {
				if (!mpCtx) return null;
				try { return mpCtx.get("sidebarRight") ?? null; } catch (e) { return null; }
			}

			/**
			 * 处理一次系统返回键。
			 * @returns {boolean} true = 已消费（原生不退出）；false = 无层级可退。
			 */
			function mpHandleSystemBack() {
				// ① 自建的重命名弹窗
				const rename = document.querySelector(".mp-rename-mask");
				if (mpVisible(rename)) { closeRename(rename); return true; }

				// ② / ③ 模态弹窗
				const dialog = mpVisibleModal();
				if (dialog) {
					const step = mpDialogBack.get(dialog);
					if (typeof step === "function") {
						try { step(); return true; } catch (e) { /* 落到下面的兜底 */ }
					}
					// 没注册回调（`setupSettingsDialog` 因结构不符早退过）或回调抛错：
					// 按官方自己的关闭方式兜底 —— 先点遮罩，没有遮罩再派 Escape。
					const mask = dialog.parentElement;
					if (mask && mask.classList && mask.classList.contains("VOzbGW_mask")) {
						mask.click();
						return true;
					}
					mpDispatchEscape();
					return true;
				}

				// ④ composer 的触发菜单：能读到 store 就按 store 关，否则派 Escape
				const ctrl = mpTriggerController();
				if (ctrl && ctrl.menu && typeof ctrl.menu.getSnapshot === "function") {
					try {
						if (ctrl.menu.getSnapshot().open) { ctrl.dismiss(); return true; }
					} catch (e) { /* 静默 */ }
				}
				if (document.querySelector("[data-trigger-menu]")) { mpDispatchEscape(); return true; }

				// ⑤ 侧栏抽屉。判据用**视觉开**（与 mpCloseDrawer 内部同源）：
				//    设置弹窗打开期间抽屉会被"视觉收起"（官方仍是 expanded），
				//    那种状态下按返回键不该去动抽屉。
				const col = mpSidebarCol();
				if (col && col.classList.contains("mp-drawer-open")) { mpCloseDrawer(); return true; }

				// ⑥ 官方右侧栏（右上角那个按钮开出来的「工作区文件 / 新建终端」栏）。
				//    判据用服务自己的 `isExpanded()`，不猜 DOM —— 右栏收起时面板只是被
				//    平移到屏幕外（`transform`，节点仍在 DOM 里且尺寸非零），用
				//    `mpVisible()` 那套可见性判据会**永远为真**，返回键就再也退不出去了。
				const sidebarRight = mpSidebarRight();
				if (sidebarRight && typeof sidebarRight.isExpanded === "function") {
					try {
						if (sidebarRight.isExpanded()) { sidebarRight.toggleExpanded(); return true; }
					} catch (e) { /* 静默：服务异常不该让返回键卡住 */ }
				}

				// ⑦ 官方主面板 → 回对话
				const layout = mpLayout();
				if (layout) {
					try {
						const info = layout.panelInfo && layout.panelInfo.getSnapshot();
						if (info && info.activePanelId != null) { layout.selectPanel(null); return true; }
					} catch (e) { /* 静默：拿不到就当没有主面板 */ }
				}

				return false;   // 真的没层级可退 ⇒ 原生退出 App
			}

			// ⚠️ 修复 40：这里只装**常驻监听器**（宽窄屏都要装）。
			// 若把监听器留在 mpBuildShellForMobile 里，桌面端启动时直接 return
			// → 用户把窗口缩窄就没人发现（宽→窄切换彻底失效）。
			// ⚠️ mpShellBuilt 一旦置 true 就**永不复位**（拆解时也不复位）——
			//   否则重新进入移动模式会重复注册监听器。
			function mpBuildShell() {
				mpSyncMobileAttr();
				if (mpShellBuilt) return;
				mpShellBuilt = true;
				// 修复 40：监听器移到这里（无条件装），回调内部各自判移动端。
				new MutationObserver(mpAnchorBurger).observe(document.body, { childList: true, subtree: true });
				// ⚠️ 原来这里在捕获阶段拦截官方 .hHd-Xa_toggle 的点击（抢在官方 morph 之前
				//   先滑出抽屉），抽屉交回官方后这段拦截必须去掉，否则官方收不到点击。
				window.addEventListener("resize", () => { mpSyncMobileAttr(); mpSyncDrawer(); mpScheduleHeaderCenter(); }, { passive: true });
				// §12：把返回键处理器交给原生。原生拦下系统返回键后调它，
				//   返回值 = 「这一层被消费了吗」；只有 false 才退出 App。
				//   ⚠️ 挂在 mpBuildShell 里（与上面三个监听器同处，只执行一次）；
				//   拆解时**不摘**，理由与 mpShellBuilt 不复位相同。
				window.__DSH_MOBILE_BACK__ = mpHandleSystemBack;
				mpBuildShellForMobile();
			}

			// 移动端外壳元素（汉堡按钮 + 遮罩）：非移动端一个节点都不建。
			// ══ 修复 40：离开移动模式时的拆解 ══
			// 只在「曾经是移动模式、现在不是」时调用（平板旋转、跨断点缩放）。
			// 纯鼠标桌面**永不进入**移动模式 → 对该场景是恒等操作（零风险）。
			// CSS 部分已由 data-mobile 属性移除自动失效，这里只收拾 JS 留下的痕迹。
			function mpTeardownMobile() {
				if (mpCollapseObs) { mpCollapseObs.disconnect(); mpCollapseObs = null; }
				if (mpCollapseFallback !== null) { clearTimeout(mpCollapseFallback); mpCollapseFallback = null; }
				mpClosing = false;
				try {
					// ① 自建节点：汉堡按钮 / 遮罩 / 设置页返回条 / 分组小标题
					document.querySelectorAll(".mp-nav-btn, .mp-drawer-mask, .mp-back-bar, .mp-nav-group")
						.forEach((n) => { try { n.remove(); } catch (e) { /* 静默 */ } });
					mpNavBtnEl = null;
					// 外壳标记必须一并清掉：mpBuildShellForMobile() 用它做幂等判断，
					// 残留会让"宽屏→窄屏"回来时直接 early return、不再重建遮罩。
					document.documentElement.removeAttribute("data-mp-shell");
					// ⚠️ **不复位 mpShellBuilt**：监听器只该注册一次。
					//   复位它会让重进窄屏时 mpBuildShell 再走一遍 → 重复注册
					//   MutationObserver / click / resize（三个都泄漏）。
					//   重进窄屏的外壳重建由 mpBuildShellForMobile()（幂等）负责。
					// ② 内联覆盖：设置弹窗被我们改成 100vw/100vh 全屏 —— 恢复官方
					document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach((d) => {
						["width", "maxWidth", "height", "maxHeight", "borderRadius", "boxShadow",
							"position", "top", "left", "flexDirection", "overflow"].forEach((k) => {
							try { d.style[k] = ""; } catch (e) { /* 静默 */ }
						});
						const ov = d.parentElement;
						if (ov && ov.classList && ov.classList.contains("VOzbGW_mask")) {
							["left", "width", "height", "alignItems"].forEach((k) => {
								try { ov.style[k] = ""; } catch (e) { /* 静默 */ }
							});
						}
					});
					// ③ 插件分组：把被我们藏起来的官方卡片恢复（否则桌面端插件列表是空的）
					document.querySelectorAll(".qSYn7G_card").forEach((c) => {
						try { c.style.display = ""; } catch (e) { /* 静默 */ }
					});
					// ④ 标记位：清掉以便再次进入窄屏时能重新处理
					document.querySelectorAll("[data-mp-ready]").forEach((n) => { try { delete n.dataset.mpReady; } catch (e) {} });
					document.querySelectorAll("[data-mp-plugin-group]").forEach((n) => { try { delete n.dataset.mpPluginGroup; } catch (e) {} });
					document.querySelectorAll("[data-mp-group]").forEach((n) => { try { delete n.dataset.mpGroup; } catch (e) {} });
					document.querySelectorAll("[data-mp-tap]").forEach((n) => { try { delete n.dataset.mpTap; } catch (e) {} });
					document.querySelectorAll("[data-mp-more-bound]").forEach((n) => { try { delete n.dataset.mpMoreBound; } catch (e) {} });
				} catch (e) { /* 修复 40：拆解失败也绝不抛错、绝不阻断页面 */ }
			}


			function mpBuildShellForMobile() {
				if (!mpIsMobile()) return; // 修复 40
				// 幂等：拆解后重进窄屏会再次调用，别建出第二个汉堡。
				// 幂等：拆解后重进窄屏会再次调用。
				if (document.documentElement.hasAttribute("data-mp-shell")) return;
				// ⚠️ 2026-11-09 起**不再自建汉堡按钮**。
				//   官方 dsh 0.2.2-rc.1 在主区 header 自带「打开侧边栏」按钮
				//   （.IW6AQa_iconButton，label="打开侧边栏"，实测 (12,11) 28x28、
				//   visibility: visible，点击可完整开合抽屉），
				//   而本插件自建的那个 svg 落在 (16,14) 20x20 —— 两个图标几乎完全
				//   重合，用户看到的就是"左上角两个汉堡叠在一起"。
				//   官方既然有入口，自绘只会造成重复，故删除，直接交给官方按钮。
				document.documentElement.setAttribute("data-mp-shell", "");
				mpMask();
				// 修复 32：用户在抽屉内点**官方收起按钮**（.hHd-Xa_toggle）时，
				//   官方会**立刻**把侧栏切到 56px 图标条形态（collapsed + railIn），
				//   而我们的抽屉还在 left:0 没滑走 → 中间约 160ms 露出"空白侧边栏"，
				//   然后才滑出屏幕（啾灵截图反馈）。
				//   做法：捕获阶段拦下这次点击，阻止官方立即 morph，改走我们的
				//   mpCloseDrawer（先滑出屏幕、再点官方 toggle，morph 发生在屏幕外）。
			}

			// ── 8a. 官方 ⋯ 菜单补回「刷新」──
			// 0.1.5 的 ⋯ 菜单由官方提供，只有「下载 Session 日志」一项；旧版我们
			// 自建顶栏时有「下载日志 / 刷新 / 查看轨迹」。现在保留官方菜单，
			// 只把「刷新」按官方同样的 DOM/类名插进去（观感、间距、hover 全一致）。
			// ⚠️ 幂等：官方每次打开菜单会重建 DOM，故按 data-mp-item 去重；
			//    同时用 observer 兜住「菜单已开时 React 又重渲染」的情况。

			/**
			 * 安全重载界面（修复 80）。
			 *
			 * ⚠️⚠️ **不能用 location.reload()。**
			 *
			 * 应用启动时内核加载的是 `http://127.0.0.1:<port>/?token=<一次性 token>`，
			 * 引擎对该地址回 303 + Set-Cookie 完成 token 交换。而**整页重载请求的是
			 * `./`，不会再带那个 token**，于是 API / WebSocket 全部 401 ——
			 * 用户看到「连接异常，刷新重试」、会话列表空、回 Hero 页，
			 * **只有重启 App 才能恢复**（重启会重新带 token 打开）。
			 *
			 * 所以改走原生桥 `DSHAndroid.reloadWebUi()`：原生侧重载的是
			 * `lastLoadedUrl`（就是那条**带 token 的原始地址**），不会丢鉴权。
			 *
			 * ⚠️ 重载前先把当前会话写回官方持久化键 `dsh.sessions.current`，
			 *   否则重载后官方的启动恢复会落到「最近工作区」，表现为**会话跳槽**。
			 * ⚠️ 桥不可用（桌面端 / gecko 未握手）时**什么都不做并明确告知** ——
			 *   不做 `location.reload()` 兜底：那正是要修掉的那个 bug，
			 *   「刷新后必须重启 App」比「刷新没反应」糟得多。
			 */
			function mpReloadUi() {
				mpPersistCurrentSession();
				var bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.reloadWebUi !== "function") {
					window.alert("当前环境没有原生重载入口（DSHAndroid 桥不可用），无法刷新界面。\n请直接重启 App —— 不要用下拉或手势刷新，那会丢登录态。");
					return;
				}
				try {
					bridge.reloadWebUi();
				} catch (e) {
					window.alert("刷新失败：" + (e && e.message ? e.message : e) + "\n请直接重启 App。");
				}
			}

			/**
			 * 把「当前主会话 id」写回官方持久化键 `dsh.sessions.current`。
			 *
			 * ⚠️ 官方自己也会写这个键，但写时机是**切换会话时**；重载前若用户
			 *   刚进来还没切换过，键里可能是上一个会话 —— 重载后就跳槽了。
			 * ⚠️ 读不到当前会话（`uiSession` 服务还没就绪）时**不动原值**，
			 *   保留官方最后写的那一份，比写一个空调过去安全。
			 */
			function mpPersistCurrentSession() {
				try {
					var ui = mpCtx && typeof mpCtx.get === "function" ? mpCtx.get("uiSession") : null;
					var id = ui && ui.current && ui.current.value && ui.current.value.key;
					if (!id || typeof id !== "string") return;
					var raw = localStorage.getItem("dsh.sessions.current");
					var parsed = raw ? JSON.parse(raw) : {};
					if (!parsed || typeof parsed !== "object") parsed = {};
					if (parsed.sessionId === id) return;   // 已经是它 → 不写（避免无谓触发）
					parsed.sessionId = id;
					localStorage.setItem("dsh.sessions.current", JSON.stringify(parsed));
				} catch (e) { /* 落盘失败不能阻断重载本身 */ }
			}

			function mpEnsureRefreshItem() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// ⚠️ 修复 28：只认顶栏 ⋯ 菜单。官方这套 Menu 是全站通用组件——
				//   顶栏 ⋯ 菜单是唯一 dense:true 的（列表带 _denseList_ 类）；
				//   会话行 ⋮、权限切换卡片等都是普通菜单（没有该类）。
				//   旧版带了个 `div[class*="_list_"]` 兜底 → 任何官方下拉菜单都会被
				//   误注入「刷新」（啾灵实测：点会话行 3 个点、权限切换卡片都弹出刷新）。
				const more = document.querySelector('.wSkVaW_headerUtilities button[aria-label="更多操作"][aria-expanded="true"]');
				const list = more && more.parentElement.querySelector('[role="menu"]');
				if (!list) return;
				// 只在菜单确实可见时注入
				if (!list.offsetParent && list.getBoundingClientRect().height === 0) return;
				// 修复 30 / 33：对话⇄轨迹切换入口并入 ⋯ 菜单（官方 tabs 行已在移动端隐藏）。
				//   ⚠️ 必须放在「刷新项已存在就 return」之前：本函数会被 observer 反复调用，
				//   一旦早退就会连带跳过视图切换项的标签刷新（切了视图菜单仍显示旧项）。
				mpInjectTraceItem(list);
				if (list.querySelector('[data-mp-item="refresh"]')) return;
				// 修复 30c：刷新图标（30 重构时漏传 SVG，只剩空壳 → 菜单里看不见图标）
				mpInjectMenuItem(list, "refresh", "刷新",
					'<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
					'<path d="M13.5 8a5.5 5.5 0 1 1-1.62-3.9M13.5 2.2v3.2h-3.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>' +
					"</svg>",
					() => { mpReloadUi(); });
			}

			// 按「官方同款类名克隆」的方式往 ⋯ 菜单里插一项（观感/间距/hover 与官方一致）
			function mpInjectMenuItem(list, key, label, iconSvg, onClick) {
				if (list.querySelector('[data-mp-item="' + key + '"]')) return;
				const sample = list.querySelector('div[class*="_itemWrap_"]');
				if (!sample) return;
				const wrap = document.createElement("div");
				wrap.className = sample.className;
				wrap.dataset.mpItem = key;
				const btn = document.createElement("button");
				btn.type = "button";
				btn.setAttribute("role", "menuitem");
				btn.className = sample.querySelector("button") ? sample.querySelector("button").className : "";
				const iconCls = sample.querySelector('span[class*="_itemIcon_"]')?.className || "";
				const labelCls = sample.querySelector('span[class*="_itemLabel_"]')?.className || "";
				btn.innerHTML =
					'<span class="' + iconCls + '">' +
					(iconSvg || '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"></svg>') +
					"</span>" +
					'<span class="' + labelCls + '">' + label + "</span>";
				btn.addEventListener("click", onClick);
				wrap.appendChild(btn);
				list.appendChild(wrap);
			}

			// 0.2.2 的轨迹标签可能不渲染，复用官方 header 的 store 与 selectView。
			function mpConversationView() {
				const slots = mpCtx && mpCtx.get("slots");
				const binding = mpCtx && mpCtx.get("uiSession")?.current?.value;
				if (!binding || !slots || typeof slots.resolveStore !== "function") return null;
				const entry = slots.entriesOfSlot("conversation.session.header")
					.find((e) => e.store && typeof e.inject === "function");
				if (!entry) return null;
				const store = slots.resolveStore(entry.store, binding);
				return { store, face: entry.inject(binding.key, store.actions) };
			}
			async function mpToggleConversationView() {
				try {
					let view = mpConversationView();
					if (!view) throw new Error("会话视图尚未就绪");
					const target = view.store.getSnapshot().view === "trajectory" ? "chat" : "trajectory";
					if (target === "trajectory") {
						const forms = mpCtx.get("configForms");
						const tools = forms && forms.developerTools;
						if (tools && !tools.enabled.getSnapshot()) await tools.setEnabled(true);
						view = mpConversationView();
					}
					if (!view.face.hooks.conversationViews.getSnapshot().some((v) => v.id === target)) {
						throw new Error("请在设置 → 通用开启显示代码工作视图");
					}
					view.face.selectView(target);
					document.querySelector('.wSkVaW_headerUtilities button[aria-label="更多操作"][aria-expanded="true"]')?.click();
				} catch (e) {
					window.alert("切换会话视图失败：" + (e && e.message || e));
				}
			}

			function mpInjectTraceItem(list) {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const view = mpConversationView();
				if (!view) return;
				const inTrace = view.store.getSnapshot().view === "trajectory";
				const targetLabel = inTrace ? "对话" : "轨迹";
				// 图标：对话 = 气泡；轨迹 = 折线
				const iconSvg = inTrace
					? '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
						'<path d="M2.5 3.5h11v7h-6l-3.2 2.6V10.5h-1.8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>'
					: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
						'<path d="M2 11.5c2.5 0 2.5-7 5-7s2.5 7 5 7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>' +
						'<circle cx="13.8" cy="11.5" r="1.2" fill="currentColor"/></svg>';
				mpInjectMenuItem(list, "view-switch", targetLabel, iconSvg, mpToggleConversationView);
				// 标签/图标随视图状态刷新（同一菜单项复用，避免重复项堆积）
				const item = list.querySelector('[data-mp-item="view-switch"]');
				if (item) {
					const labelEl = item.querySelector('span[class*="_itemLabel_"]');
					if (labelEl && labelEl.textContent !== targetLabel) labelEl.textContent = targetLabel;
					const iconEl = item.querySelector('span[class*="_itemIcon_"]');
					if (iconEl && iconEl.dataset.mpIconFor !== targetLabel) {
						iconEl.dataset.mpIconFor = targetLabel;
						iconEl.innerHTML = iconSvg;
						item.dataset.mpLabel = targetLabel;
					}
				}
			}

			// ── 8b. 点击会话标题 → 重命名（沿用官方 sessions.rename）──
			// ⚠️ 0.1.5 的标题是 <button disabled>（`.wSkVaW_crumbCurrent`）。
			//   **disabled 表单控件不派发任何鼠标事件**（连捕获阶段也收不到，实测点击
			//   命中的就是该按钮，header 上的监听器一次都没被调用）。
			//   故：① 去掉 disabled 让点击可穿透；② 用 role/aria 标注它是可点的；
			//   ③ 再在 header 上做捕获监听（防御 React 重渲染把 disabled 写回来）。
			function mpEnableTitleTap() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const crumb = document.querySelector(".wSkVaW_crumbCurrent");
				if (!crumb) return;
				if (crumb.disabled) crumb.disabled = false;
				if (crumb.dataset.mpTap !== "1") {
					crumb.dataset.mpTap = "1";
					crumb.style.cursor = "pointer";
					// 去掉 disabled 后按钮会进入 tab 序，移动端不需要
					crumb.setAttribute("tabindex", "-1");
				}
			}
			// ⋯ 按钮点击后立刻注入（不等 observer 的 300ms 节流，避免菜单先显示一项再补）
			function mpBindMoreButton() {
				const more = document.querySelector(".nL4_yW_moreButton");
				if (!more || more.dataset.mpMoreBound) return;
				more.dataset.mpMoreBound = "1";
				more.addEventListener("click", () => { setTimeout(mpEnsureRefreshItem, 0); setTimeout(mpEnsureRefreshItem, 60); });
			}

			function bindTitleRename() {
				if (!mpIsMobile()) return;
				mpBindMoreButton();
				mpEnableTitleTap();
				const header = document.querySelector(".wSkVaW_header");
				if (!header || header.dataset.mpRenameBound) return;
				header.dataset.mpRenameBound = "1";
				header.addEventListener("click", (e) => {
					const sessions = mpSessionsOf();
					if (!sessions) return;
					const cluster = e.target.closest && e.target.closest(".wSkVaW_titleCluster");
					if (!cluster) return;
					// 只认标题本体，避免抢「模式」下拉与右侧工具按钮的点击
					if (!e.target.closest(".wSkVaW_crumb, .wSkVaW_crumbCurrent, .wSkVaW_crumbs")) return;
					const list = sessions.list.getSnapshot();
					const id = list.current;
					if (id === void 0) return;
					const binding = sessions.binding(id);
					if (!binding) return;
					const crumb = cluster.querySelector(".wSkVaW_crumbCurrent");
					showRenameDialog((crumb && crumb.innerText || "").trim(), binding.session);
				}, true);
				const cluster = header.querySelector(".wSkVaW_titleCluster");
				if (cluster) cluster.style.cursor = "pointer";
			}

		// ── 全局 MutationObserver：懒处理动态挂载的元素 ──
		// 节流 300ms + 去重：防止历史/流式渲染时的高频 mutation 造成
		// 全量 DOM 扫描风暴（华为 WebView 114 renderer 会 mmap OOM 崩溃）。
		let moTimer = 0;
		// ── 修复 35b：轨迹详情面板「不可用」回填闪烁（同步处理，不等节流）──
		// 现象（啾灵反馈）：点开轨迹表格某行时，面板先渲染出一帧「不可用」，300ms 后才被
		//   mpPatchTrajectoryTiming 回填 → 每次点行都闪一下，体验不如官方原生。
		// 根因：与修复 23 完全同源 —— 回填挂在 300ms 节流里，React 写入的那一帧
		//   屏幕上还是官方原始的「不可用」文案。
		// 修法：observer 回调里检测「details 面板新增/内部文本变更」，用 queueMicrotask
		//   同步跑一次 mpPatchTrajectoryTiming（微任务先于绘制，用户看不到中间帧）。
		// 幂等/收敛：回填自身写 dd 文本会再触发一次 mutation → 下一轮回填时
		//   面板里已无「不可用」→ 在 mpCollectTimings 之前早退（零写 DOM），自然收敛。
		//   事件窗口未就绪时 mpCollectTimings 返回 null 也不写 DOM，留待 300ms 兜底。
		let traceTimingQueued = false;
		const scheduleTraceTiming = () => {
			if (traceTimingQueued) return;
			traceTimingQueued = true;
			queueMicrotask(() => {
				traceTimingQueued = false;
				mpPatchTrajectoryTiming();
			});
		};
		const mo = new MutationObserver((muts) => {
			// 设置 dialog 挂载时立即处理，避免 300ms 节流导致旧样式闪烁
			for (const m of muts) {
				for (const n of m.addedNodes) {
					if (!n || n.nodeType !== 1) continue;
					if (n.matches && n.matches('[role="dialog"][aria-modal="true"]')) {
						setupSettingsDialog(n);
					} else if (n.querySelectorAll) {
						n.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
					}
					// 修复 35b：详情面板挂载 → 微任务回填（抢在绘制前）
					if (n.matches && n.matches(".Y0dWHa_details")) {
						scheduleTraceTiming();
					} else if (n.querySelector && n.querySelector(".Y0dWHa_details")) {
						scheduleTraceTiming();
					}
				}
			}
			// StatsLine 相关改动 → 立即（微任务）归一化，消除闪烁
			// ⚠️ characterData 变更的 target 是**文本节点**（nodeType 3），
			//    必须取 parentElement 再判断，否则漏掉 React 只改文本的情况。
			// 底栏挂载或重建后立即补上缩写属性；已有按钮的属性更新由 mpStatsObserver 处理。
			let dockTouched = false;
			for (const m of muts) {
				let t = m.target;
				if (!t) continue;
				if (t.nodeType === 3) t = t.parentElement;
				else if (t.nodeType !== 1) continue;
				if (t && t.closest && t.closest(".Y0dWHa_details")) {
					// 修复 35b：切行/重渲染时面板文本原地更新 → 同步回填（幂等，早退收敛）
					scheduleTraceTiming();
				}
				if (!dockTouched && t && t.closest && t.closest(".uV2eYG_dock")) dockTouched = true;
			}
			// ⚠️ 还要覆盖 React **重建节点**的情况：那时 mutation 的 target 可能
			//   是 dock 的某个祖先（childList 加进新子树），closest 判定落在
			//   dock 之外就漏了。所以再加一条「新增节点是否落在 dock 内」的判定。
			if (!dockTouched) {
				for (const m of muts) {
					const added = m.addedNodes;
					if (!added || !added.length) continue;
					for (let i = 0; i < added.length; i++) {
						const n = added[i];
						if (n.nodeType !== 1) continue;
						if ((n.closest && n.closest(".uV2eYG_dock")) || n.querySelector && n.querySelector(".uV2eYG_dock")) {
							dockTouched = true;
							break;
						}
					}
					if (dockTouched) break;
				}
			}
			if (dockTouched) mpCompactStats();
			if (moTimer) return;
			moTimer = setTimeout(() => {
				moTimer = 0;
				document.querySelectorAll(".xzv4MW_timeEnd").forEach(trimMeta);
				mpCompactStats();
				watchApproval();
				mpSyncDrawer();
				mpAnchorBurger(); // ⭐ 修复 47：兜底重挂（React 摘掉 header 子节点时专用 observer 若漏掉）
				bindTitleRename();
				mpEnsureRefreshItem();
				mpPatchTrajectoryTiming();
				document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
			}, 300);
		});

		// ── 4e. 非消息区域长按拦截：不触发系统文字选择器 ──
		// 策略：touchstart 非 passive 允许我们在需要时 preventDefault；
		//       只在长按时（≥400ms 且无明显移动）才阻止，短按/滚动正常走原生。
		var mpLongPressStart = null;
		var mpLongPressMoved = false;
		var MP_LONGPRESS_THRESHOLD = 400;
		var MP_LONGPRESS_MOVE_THRESHOLD = 12;

		function mpIsMessageArea(el) {
			if (!el) return false;
			if (el.closest(".wSkVaW_viewArea")) return true;
			if (el.closest("input") || el.closest("textarea") || el.closest("[contenteditable]")) return true;
			return false;
		}

		document.addEventListener("touchstart", function(e) {
			if (!mpIsMobile()) return; // 修复 40
			if (e.touches.length !== 1) return;
			var t = e.touches[0];
			mpLongPressStart = { x: t.clientX, y: t.clientY, time: Date.now() };
			mpLongPressMoved = false;
		}, false);

		document.addEventListener("touchmove", function(e) {
			if (!mpIsMobile()) return; // 修复 40
			if (!mpLongPressStart) return;
			var t = e.touches[0];
			var dx = Math.abs(t.clientX - mpLongPressStart.x);
			var dy = Math.abs(t.clientY - mpLongPressStart.y);
			if (dx > MP_LONGPRESS_MOVE_THRESHOLD || dy > MP_LONGPRESS_MOVE_THRESHOLD) {
				mpLongPressStart = null;
			}
		}, false);

		document.addEventListener("touchend", function(e) {
			if (!mpIsMobile()) return; // 修复 40
			if (!mpLongPressStart) return;
			var elapsed = Date.now() - mpLongPressStart.time;
			if (elapsed < MP_LONGPRESS_THRESHOLD) {
				mpLongPressStart = null;
				return;
			}
			// 长按超时，检查是否在消息区域
			var t = e.changedTouches[0];
			var target = document.elementFromPoint(t.clientX, t.clientY);
			if (mpIsMessageArea(target)) {
				mpLongPressStart = null;
				return; // 消息区域：让原生长按行为正常执行
			}
			// 非消息区域长按：阻止系统文本选择器
			e.preventDefault();
			mpLongPressStart = null;
		}, false);

		// contextmenu 兜底（调试工具偶尔触发）
		document.addEventListener("contextmenu", function(e) {
			// ⚠️ 修复 40：此前**无条件** preventDefault → 桌面端右键菜单被我们吃掉，
			// 这是「电脑那边也被改了」里最影响日常使用的之一。
			if (!mpIsMobile()) return; // 修复 40
			if (!mpIsMessageArea(e.target)) {
				e.preventDefault();
			}
		}, false);

		// ── 10. 顶栏标题重命名：点击标题弹自定义输入框，走官方 sessions.rename ──
		// ⚠️ 不能只在 apply() 里抓一次 ctx.get("sessions")：本插件 `inject: []`（无声明依赖），
		//   激活可能早于 sessions 服务就绪 → 抓到 null 后永久为 null（实测 0.1.5 下
		//   `mpSessions === null`，导致点标题毫无反应）。改为**每次用时懒取**。
		let mpCtx = null;
		let mpSessionsCache = null;
		// ══ 修复 51：刷新后先闪 Hero 主界面（照搬 ECS 的 dsh-custom 补丁 A + C）══
		// 现象（啾灵原话）：「在对话界面中，点右上角刷新，刷新成功后会先出现这个主界面
		//   （探索未至之境），然后才回到对话界面」。
		// 根因（官方状态机的必然结果，0.1.5 / 0.1.6 都一样）：
		//   Hero 的渲染判据是 `sessionId === void 0`，而 sessionId 来自
		//   sessions.list.current，由 SessionManager.buildListSnapshot() 算出：
		//     const current = selected !== void 0 &&
		//       (items.some(i => i.sessionId === selected) || this.addresses.has(selected))
		//         ? selected : void 0
		//   启动瞬间 summaries 必为空 → items 空 → current 恒为 void 0
		//   → **首帧必然渲染 Hero**；等 sessions.list RPC 返回、current 落定，
		//   才挂载对话 → 这就是那一闪。更糟的是 projectList() 里
		//     if (current === void 0) { if (persisted !== void 0) this.selection.set({}) }
		//   会**擦掉官方持久化的选择**。
		// ECS 的解法（/root/dsh-custom，2026-09-10，记录见 ecs维护/HISTORY.md §693）
		// 是给官方文件打两个补丁：
		//   A `runtime-select-trust-pending`：current 判定加 `this.listPhase === "pending" ||`
		//   C `runtime-construct-projectlist`：构造后立刻同步一次 projectList()
		// 本插件不改官方文件（升级不丢），改为**运行时包装同一个方法**：
		//   · 包装 manager.buildListSnapshot，pending 期把 current 补成持久化选择
		//     （= 补丁 A，语义逐字一致）；
		//   · 包装后立刻强制重算缓存 + sessions.projectList()，让**第一帧**就有 current
		//     （= 补丁 C）。
		// ⚠️ 只做「pending 期信任持久化选择」这一件事：清单 ready 后若该会话确已
		//   删除，current 自然回落 void 0 → 照常显示 Hero（与官方语义一致）。
		// ⚠️ selected 由官方构造时从 localStorage `dsh.sessions.current` 同步恢复
		//   （ClientSessions 构造 → new SessionManager(remote, restored.sessionId)），
		//   构造完成即有值，无需我们自己去读 localStorage。
		function mpInstallPendingTrust() {
			const sessions = mpSessionsOf();
			if (!sessions) return false;
			const mgr = sessions.manager;
			if (!mgr || typeof mgr.buildListSnapshot !== "function") return false;
			if (mgr.__mpPendingTrust === true) return true;   // 幂等
			const orig = mgr.buildListSnapshot;
			mgr.buildListSnapshot = function () {
				const snap = orig.call(this);
				/* 补丁 A：pending 期信任持久化选择。
				   官方原判据要求该会话已出现在 items（RPC 结果）里，启动窗口期
				   items 必空 → current 恒 void 0 → 首帧渲染 Hero。 */
				if (snap.current === void 0 && this.listPhase === "pending" && this.selected !== void 0) {
					return {
						...snap,
						current: this.selected,
						currentAddress: this.addresses.get(this.selected)
					};
				}
				return snap;
			};
			mgr.__mpPendingTrust = true;
			/* 补丁 C：立刻同步一次首投影，让**第一帧**就有 current。
			   ⚠️ 不能只调 projectList() —— 它读 getListSnapshot()，而后者走
			   notifier.ensureFresh()，缓存不脏时**直接返回构造期算出的旧值**
			   （那时还没包装，current 已是 void 0）。必须先强制重算缓存。 */
			try {
				mgr.listSnapshotCache = mgr.buildListSnapshot();
				sessions.projectList();
			} catch (e) { /* 失败不影响正确性：后续 manager 通知仍走包装后的方法 */ }
			return true;
		}
		/* 用 ctx.inject 等待 sessions 服务（比定时器可靠）：
		   · 服务已就绪 → 回调**同步**执行，赶在 React 首帧之前；
		   · 服务晚到 → 就绪那一刻触发；
		   · 服务被重建（官方插件重挂）→ 依赖变化会**重新触发**，
		     而定时器只在启动 3s 内有效、之后新实例就漏掉了。 */
		function mpWatchSessions(ctx) {
			try {
				ctx.inject(["sessions"], () => { mpInstallPendingTrust(); });
			} catch (e) { /* 静默：定时器兜底 */ }
		}

		function mpSessionsOf() {
			if (mpSessionsCache) return mpSessionsCache;
			if (!mpCtx) return null;
			try { mpSessionsCache = mpCtx.get("sessions") ?? null; } catch (e) { mpSessionsCache = null; }
			return mpSessionsCache;
		}

		// ══ 修复 65：键盘升起时输入框被盖住（原生预判）════════════════════
		// 现象：点输入框后，键盘从屏幕底部升起来，但输入框要等约 200ms 才跳上去，
		//      中间这几帧输入框被键盘压住。
		//
		// 真机实测（Huawei CDY-TN90 / Android 10，各 2~3 轮一致）：
		//   · focus → 窗口变矮 = 180~220ms
		//   · 期间窗口高度 764 → 475，**只有 1 个 resize 事件、无中间帧**
		//   · visualViewport 与 innerHeight **同一时刻**变化，没有更早的信号
		//   · navigator.virtualKeyboard.boundingRect 恒为 0（该内核不提供几何信息）
		//   · 主线程最长阻塞 76~85ms，不足以解释这 200ms
		// → 这 200ms 里页面**拿不到任何"键盘现在多高"的信息**，只能等窗口变矮。
		//   这是平台行为（逐帧 insets 回调要 API 30 的 WindowInsetsAnimation），
		//   不是插件或主线程的问题。
		//
		// 修法：**提前告诉原生"键盘要来了"**。`focusin` 发生在点击那一刻，
		//   必然早于窗口变矮。原生收到后按**上一次观测到的键盘高度**先把
		//   WebView 变矮，等真 insets 到达再交回给系统。
		//
		// ⚠️ 这里只负责"通知"，不猜高度 —— 高度由原生实测缓存（每台机器、
		//   每种输入法都不同，写死必错）。
		// ⚠️ 只认输入框本体。用 `focusin` 的捕获阶段，保证在任何 stopPropagation 之前。
		function setupImePrejudge() {
			if (!mpIsMobile()) return () => {};   // 仅移动端（桌面无软键盘）
			const onFocusIn = (e) => {
				try {
					const t = e.target;
					if (!t || typeof t.closest !== "function") return;
					// 只认 composer 的编辑区；点「+」等按钮抢焦点不该触发
					if (!t.closest("[data-composer-input]")) return;
					const bridge = window.DSHAndroid;
					if (!bridge || typeof bridge.onImeWillShow !== "function") return;
					bridge.onImeWillShow();
				} catch (err) { /* 静默：绝不打断输入 */ }
			};
			document.addEventListener("focusin", onFocusIn, true);
			return () => document.removeEventListener("focusin", onFocusIn, true);
		}
		// ══ 修复 35：轨迹详情面板「首 token 时间不可用」回填（2026-09-16 啾灵反馈）══
		// 现象：轨迹页点开任意一条历史数据，「首 token 延迟 / 生成 / 吞吐量」三行全显示
		//      「首 token 时间不可用」（实时流式时是有的，落盘后再回看就没了）。
		// 根因（官方缺陷，非本插件造成）：
		//   官方轨迹回放（dsh-client-ui-trajectory 的 fallbackState$1）**只认
		//   `assistant/live-chunk`** 这一种事件来喂 `state.firstTokenTime`；
		//   但持久化到磁盘的分片事件名不是它 —— 老会话是 `assistant/chunk`，
		//   新会话（v3）把分片打包进 `assistant/message.data.stream`。
		//   → 分片被整个跳过 → firstTokenTime 恒为 undefined → ?? null → 显示不可用。
		//   而官方**服务端**（dsh-session-stats）和**客户端 connection 包**都用
		//   `event.data.stream` + `assistantStreamFirstTokenTime()` 算得出这个值，
		//   说明**数据其实在**，只有轨迹页把它忽略了。
		// 修法（纯插件、不改官方文件）：从 sessions 服务读当前会话的事件窗口，
		//   用官方同一套算法（isTokenDelta 判据 + packed-run 的 time0/dt 累加）
		//   算出每个 step 的 firstTokenTime，再在轨迹详情面板里把「不可用」
		//   就地替换成正确的时长/吞吐量文案。
		// ⚠️ 只在窄屏且轨迹面板存在时工作；算不出的一律保持原样（不猜、不编）。
		/* 启动动画收尾的兜底上限（ms）。实测插件激活约 1.4~1.7s，取 6s 留足余量：
		 * 只有判据真的永远不满足时才会走到这里，目的是避免原生启动层永久盖住界面。 */
		/**
		 * 等待「界面真的可用」的上限。
		 *
		 * ⚠️ 从 6000 提到 9000：判据里新增了「官方前端的启动页已消失」，
		 *   而那一项实测要等到 "readyState === complete" 之后才满足
		 *   （旧判据 10231ms 满足，官方启动页 10475ms 才消失），
		 *   留足余量避免在正常启动路径上误触发超时兜底。
		 */
		const UI_READY_MAX_WAIT_MS = 9000;

		const MP_TIMING_UNAVAILABLE = "首 token 时间不可用";		const MP_TIMING_NOT_RECORDED = "未记录";
		const MP_TIMING_STEP_START_UNAVAILABLE = "步骤开始时间不可用";
		const MP_TIMING_DURATION_TOO_SHORT = "时长过短";
		const MP_TIMING_PENDING = "等待中";
		const MP_TIMING_USAGE_UNAVAILABLE = "用量不可用";
		const MP_TIMING_OUTPUT_UNAVAILABLE = "输出 token 数不可用";

		// 与官方 dsh-llm `isTokenDelta` 完全一致的判据：
		// 非空 text/reasoning delta，或带非空 argumentsDelta / 有 name 的 tool-call delta。
		function mpIsTokenDelta(chunk) {
			if (!chunk) return false;
			switch (chunk.type) {
				case "text-delta":
				case "reasoning-delta": return chunk.text !== "" && chunk.text !== void 0;
				case "tool-call-delta": return (chunk.argumentsDelta !== "" && chunk.argumentsDelta !== void 0) || chunk.name !== void 0;
				default: return false;
			}
		}
		// 复现官方 `firstRunMemberTime`：packed run 的成员时间 = time0 起按 dt 累加。
		function mpRunMemberTime(run, predicate) {
			const frags = run.type === "tool-call-chunks" ? run.args : run.texts;
			if (!Array.isArray(frags)) return void 0;
			let time = run.time0;
			for (let i = 0; i < frags.length; i += 1) {
				if (i > 0) {
					const dt = Array.isArray(run.dt) ? run.dt[i - 1] : void 0;
					if (typeof dt !== "number") return void 0;
					time += dt;
				}
				if (predicate(frags[i])) return time;
			}
			return void 0;
		}
		// 复现官方 `assistantStreamFirstTokenTime`：扫一条 stream，返回首个 token 的时间。
		function mpFirstTokenTime(stream) {
			if (!Array.isArray(stream)) return void 0;
			for (const rec of stream) {
				if (!rec) continue;
				if (rec.type === "chunk") {
					if (mpIsTokenDelta(rec.chunk) && typeof rec.time === "number") return rec.time;
					continue;
				}
				if (rec.type === "tool-call-chunks") {
					if (rec.name !== void 0 && typeof rec.time0 === "number") return rec.time0;
					continue;
				}
				if (rec.type === "text-chunks" || rec.type === "reasoning-chunks") {
					const t = mpRunMemberTime(rec, (f) => f !== "");
					if (typeof t === "number") return t;
				}
			}
			return void 0;
		}
		// 扫描当前会话事件窗口，建立 "turn:step" → { startTime, firstTokenTime, completedTime, outputTokens }
		// ⚠️ 只读叶子标量，不整对象序列化（事件是 live 数据）。
		function mpCollectTimings() {
			const sessions = mpSessionsOf();
			if (!sessions) return null;
			let id;
			try {
				const list = sessions.list.getSnapshot();
				id = list && list.current;
			} catch (e) { return null; }
			if (id === void 0 || id === null) return null;
			let binding;
			try { binding = sessions.binding(id); } catch (e) { return null; }
			if (!binding) return null;
			const src = binding.eventSource;
			if (!src || typeof src.getSnapshot !== "function") return null;
			let win;
			try { win = src.getSnapshot(); } catch (e) { return null; }
			const entries = win && Array.isArray(win.entries) ? win.entries : null;
			if (!entries) return null;
			const map = Object.create(null);
			const keyOf = (d) => (d && d.turn !== void 0 && d.step !== void 0) ? (d.turn + ":" + d.step) : null;
			for (const entry of entries) {
				const ev = entry && entry.event;
				if (!ev || !ev.data) continue;
				const k = keyOf(ev.data);
				if (k === null) continue;
				let rec = map[k];
				if (rec === void 0) { rec = map[k] = { startTime: null, firstTokenTime: null, completedTime: null, outputTokens: null }; }
				if (ev.type === "step/start") {
					if (rec.startTime === null && typeof ev.time === "number") rec.startTime = ev.time;
					continue;
				}
				if (ev.type !== "assistant/message" && ev.type !== "assistant/attempt") continue;
				const stream = ev.data.stream;
				if (rec.firstTokenTime === null) {
					const ft = mpFirstTokenTime(stream);
					if (typeof ft === "number") rec.firstTokenTime = ft;
				}
				if (ev.type === "assistant/message") {
					if (typeof ev.time === "number") rec.completedTime = ev.time;
					const usage = ev.data.usage;
					if (usage && Number.isFinite(usage.outputTokens)) rec.outputTokens = usage.outputTokens;
				}
			}
			return map;
		}
		function mpFormatDuration(ms) {
			if (ms < 1e3) return Math.round(ms) + " 毫秒";
			return (ms / 1e3).toFixed(ms < 1e4 ? 2 : 1) + " 秒";
		}
		// 官方三行文案的等价复现（判据、顺序、格式全对齐官方实现）
		function mpTimingText(kind, m) {
			if (!m) return null;
			if (kind === "ttft") {
				// ⚠️ 采集侧字段名是 startTime（对应官方的 stepStartTime）
				if (m.startTime === null) return MP_TIMING_STEP_START_UNAVAILABLE;
				if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
				// ⚠️ 防御：任一非有限数都不算，绝不产出 "NaN 秒" 这种脏文案
				if (!Number.isFinite(m.startTime) || !Number.isFinite(m.firstTokenTime)) return null;
				return mpFormatDuration(Math.max(0, m.firstTokenTime - m.startTime));
			}
			if (kind === "generation") {
				if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
				if (m.completedTime === null) return MP_TIMING_PENDING;
				if (!Number.isFinite(m.firstTokenTime) || !Number.isFinite(m.completedTime)) return null;
				return mpFormatDuration(Math.max(0, m.completedTime - m.firstTokenTime));
			}
			if (kind === "throughput") {
				if (m.outputTokens === null) return MP_TIMING_OUTPUT_UNAVAILABLE;
				if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
				if (m.completedTime === null) return MP_TIMING_PENDING;
				if (!Number.isFinite(m.firstTokenTime) || !Number.isFinite(m.completedTime) || !Number.isFinite(m.outputTokens)) return null;
				const sec = (m.completedTime - m.firstTokenTime) / 1e3;
				if (sec <= 0) return MP_TIMING_DURATION_TOO_SHORT;
				return (m.outputTokens / sec).toFixed(1) + " tok/s";
			}
			return null;
		}
		// 在轨迹详情面板里就地回填。幂等：已回填过的用 dataset 标记跳过。
		function mpPatchTrajectoryTiming() {
			if (!mpIsMobile()) return;
			const details = document.querySelector(".Y0dWHa_details");
			if (!details) return;
			// 面板标题带「第 N 轮 · 步骤 M」→ 直接解析出 key，无需依赖表格行
			const loc = details.querySelector(".Y0dWHa_detailsLocation");
			if (!loc) return;
			const text = (loc.textContent || "").trim();
			const mm = text.match(/第\s*(\d+)\s*轮\s*·\s*步骤\s*(\d+)/);
			if (!mm) return;
			const key = mm[1] + ":" + mm[2];
			// 只在有「不可用」时才干活，避免无谓开销
			const dds = details.querySelectorAll("dt");
			let hasUnavailable = false;
			for (const dt of dds) {
				const dd = dt.nextElementSibling;
				if (dd && (dd.textContent || "").indexOf(MP_TIMING_UNAVAILABLE) >= 0) { hasUnavailable = true; break; }
			}
			if (!hasUnavailable) return;
			const map = mpCollectTimings();
			if (!map) return;
			const m = map[key];
			if (!m) return;
			for (const dt of dds) {
				const label = (dt.textContent || "").trim();
				const dd = dt.nextElementSibling;
				if (!dd) continue;
				const cur = (dd.textContent || "").trim();
				if (cur.indexOf(MP_TIMING_UNAVAILABLE) < 0) continue;
				let kind = null;
				if (label.indexOf("首 token") >= 0) kind = "ttft";
				else if (label === "生成") kind = "generation";
				else if (label === "吞吐量") kind = "throughput";
				if (kind === null) continue;
				const out = mpTimingText(kind, m);
				if (out === null || out === MP_TIMING_UNAVAILABLE) continue;
				dd.textContent = out;
				dd.dataset.mpTimingPatched = kind;
			}
		}
		function showRenameDialog(currentTitle, session) {
			if (!session) return;
			// 复用现有弹窗（若已打开则聚焦输入框）
			let mask = document.querySelector(".mp-rename-mask");
			if (!mask) {
				mask = document.createElement("div");
				mask.className = "mp-rename-mask";
				mask.innerHTML =
					'<div class="mp-rename-box">' +
					'<p class="mp-rename-title">重命名会话</p>' +
					'<input class="mp-rename-input" type="text" maxlength="120" autocomplete="off" />' +
					'<div class="mp-rename-error"></div>' +
					'<div class="mp-rename-row">' +
					'<button type="button" class="mp-rename-btn mp-rename-cancel">取消</button>' +
					'<button type="button" class="mp-rename-btn mp-rename-ok">确定</button>' +
					'</div></div>';
				document.body.appendChild(mask);
				const input = mask.querySelector(".mp-rename-input");
				const error = mask.querySelector(".mp-rename-error");
				const cancel = mask.querySelector(".mp-rename-cancel");
				const ok = mask.querySelector(".mp-rename-ok");
				let busy = false;
				mask.addEventListener("click", (e) => {
					if (e.target === mask) closeRename(mask);
				});
				cancel.addEventListener("click", () => closeRename(mask));
				ok.addEventListener("click", async () => {
					if (busy) return;
					const title = input.value.trim();
					if (!title) {
						error.textContent = "标题不能为空";
						return;
					}
					busy = true;
					ok.disabled = true;
					error.textContent = "";
					try {
						const result = await session.rename(title);
						if (result.ok) closeRename(mask);
						else error.textContent = result.error?.message || "重命名失败";
					} catch (err) {
						error.textContent = err instanceof Error ? err.message : String(err);
					} finally {
						busy = false;
						ok.disabled = false;
					}
				});
				input.addEventListener("keydown", (e) => {
					if (e.key === "Enter") ok.click();
					else if (e.key === "Escape") closeRename(mask);
				});
			}
			const input = mask.querySelector(".mp-rename-input");
			const error = mask.querySelector(".mp-rename-error");
			input.value = currentTitle;
			error.textContent = "";
			mask.style.display = "flex";
			// 等一帧再聚焦（避免弹窗未显示时键盘抢焦点）
			setTimeout(() => {
				input.focus();
				input.select();
			}, 80);
		}
		function closeRename(mask) {
			if (mask) mask.style.display = "none";
		}
		// bindTitleRename 见 §8b（新版没有自建顶栏，改绑官方 header 的标题区）

		// ── 11. 移动端只允许"用户直接点输入框"唤起键盘 ──
		// 官方 composer 会在多处自动 focus 输入框：加号按钮的 keepFocus
		// (onMouseDown)、切换会话时 useEffect([sessionId]) 的自动聚焦。桌面端
		// 合理（方便直接打字），移动端会弹出输入法键盘挡住菜单/遮住界面。
		// 守卫：记录最近一次 pointerdown 的目标，若 textarea 获得的焦点并非
		// 由用户直接点击输入框引起，则立即 blur（同一事件循环内，键盘不弹出）。
		/* ══ 修复 64：移走编辑区焦点的**时机**闸门（两个守卫共用）══
		   真机实测的时序（键盘收起状态点「+」）：
		     ① 官方 onToggleCommandMenu 是「先把焦点还给编辑区，再 toggle 菜单」
		        （dsh-client-ui-conversation/lib/client.js:17390 "focusDraftEditor" → "toggleCommandMenu"），
		        而指令菜单**只在编辑区持焦点时才渲染**；
		     ② 官方的 keepDraftFocus 还会在 mousedown 里再 focus 一次（preventDefault + focus）。
		   于是「谁先把焦点从编辑区拿走」就直接决定菜单能不能出现：

		     | 拿焦点的时机        | 菜单 | 键盘 |
		     |---------------------|------|------|
		     | 菜单渲染前（旧行为）| ❌ 不开 | 不弹 |
		     | 一直不拿            | ✅ 开   | ❌ 弹回 |
		     | **菜单出现之后再拿** | ✅ 开   | ✅ 不弹 |

		   所以把「拿焦点」挂到 "[data-trigger-menu]" 出现的这一刻上，而不是手势里。
		   闸门对**两条**抢焦点的路径同时生效（它们都会被官方那次 focus 触发）：
		     · setupComposerKeyboardGuard 的 onFocusIn 守卫（修复 29）
		     · setupKeepFocusGuard 的 arm 分支（修复 38b）
		   ⚠️ 兜底定时器必需：万一菜单始终不出现（官方改了渲染方式），也必须把焦点
		     还回去，否则键盘会一直挂在那里。
		   ⚠️ 只对会弹指令菜单的按钮延迟（"button[aria-haspopup="listbox"]"）；发送 /
		     停止 / 模型选择等不弹这个菜单，必须维持**立即 blur** —— 对它们延迟等于
		     白等一个兜底周期，那段时间足够键盘被挂回来。
		   ⚠️ 不要退回「立即 blur」，也不要改成「在 keepFocus 里 blur」—— 后者同样抢在
		     React 渲染菜单之前。 */
		const MP_MENU_SEL = "[data-trigger-menu]";
		let mpBlurGateUntilMenu = false;
		let mpPendingBlurEl = null, mpPendingBlurObs = null, mpPendingBlurTid = 0;
		const mpFlushPendingBlur = () => {
			mpBlurGateUntilMenu = false;
			try { if (mpPendingBlurObs) { mpPendingBlurObs.disconnect(); mpPendingBlurObs = null; } } catch (e) { /* 静默 */ }
			try { if (mpPendingBlurTid) { window.clearTimeout(mpPendingBlurTid); mpPendingBlurTid = 0; } } catch (e) { /* 静默 */ }
			const el = mpPendingBlurEl;
			mpPendingBlurEl = null;
			if (el) { try { el.blur(); } catch (e) { /* 静默 */ } }
		};
		const mpDeferBlur = (el) => {
			// arm 与 onFocusIn 是同一次手势的两段，盯的是同一个编辑区 → 别互相冲掉
			if (mpBlurGateUntilMenu && mpPendingBlurEl === el) return;
			mpFlushPendingBlur();
			mpBlurGateUntilMenu = true;
			mpPendingBlurEl = el;
			try {
				const obs = new MutationObserver(() => {
					if (document.querySelector(MP_MENU_SEL)) mpFlushPendingBlur();
				});
				obs.observe(document.body, { childList: true, subtree: true });
				mpPendingBlurObs = obs;
			} catch (e) { /* 静默 */ }
			mpPendingBlurTid = window.setTimeout(mpFlushPendingBlur, 700);
		};

		function setupComposerKeyboardGuard() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）
			let lastDownTarget = null;
			const onDown = (e) => {
				lastDownTarget = e.target;
			};
			// ⚠️ 0.1.5 的输入框由 <textarea> 换成 **contenteditable div**
			//   （`.uV2eYG_input[data-composer-input][role=textbox]`，实测页面里
			//   textarea 数量 = 0）。旧判据只认 TEXTAREA → 守卫整体失效，
			//   官方 keepFocus/auto-focus 会把键盘顶出来。这里两者都认。
			const isComposerInput = (t) => {
				if (!t || typeof t.closest !== "function") return false;
				if (t.tagName === "TEXTAREA") return !!t.closest('[data-composer-seat]');
				if (t.isContentEditable) return !!t.closest('[data-composer-seat], .uV2eYG_root');
				return false;
			};
			const onFocusIn = (e) => {
				const t = e.target;
				if (!isComposerInput(t)) return;
				// 修复 29：必须是「用户直接点了输入框本身」才放行。
				// ⚠️ 旧判据 lastDownTarget.closest('[data-composer-seat]') 会把
				//   composer 内的**所有按钮**（指令「+」、添加附件、模型选择、访问模式、
				//   上下文等，全在 uV2eYG_tools 里、都在 seat 内）误判成"点了输入框"，
				//   → 官方 keepFocus 把焦点塞给 contenteditable → 键盘被顶出来。
				//   （实测事件序列：touchstart(按钮) → +8ms INPUT.focus → focusin，
				//     lastDownTarget=按钮 → 旧判据放行 → 键盘唤起 ❌）
				//   正解：按下的目标必须**就是输入框自己**（contenteditable 本体）。
				const userTapped = lastDownTarget === t ||
					(!!lastDownTarget && typeof lastDownTarget.closest === "function" &&
						lastDownTarget.closest("[data-composer-input]") === t);
				if (!userTapped) {
					// ⭐ 修复 64：官方 onToggleCommandMenu 会先 focus 编辑区再 toggle 菜单，
					//   而菜单只在编辑区持焦点时渲染 —— 这里若当场 blur，菜单永远不出现
					//   （真机实测：只有 focusout，菜单从未进过 DOM）。
					//   故当这次 focus 是「点 + 弹菜单」引起的时候，让路给菜单。
					//   判据有两条：闸门已开（setupKeepFocusGuard 的 arm 先判定了是「+」），
					//   或按下的目标本身就是「+」按钮。
					const menuBtn = mpBlurGateUntilMenu ||
						(!!lastDownTarget && typeof lastDownTarget.closest === "function" &&
							!!lastDownTarget.closest('button[aria-haspopup="listbox"]'));
					if (menuBtn) {
						e.stopImmediatePropagation?.();
						mpDeferBlur(t);
						return;
					}
					e.stopImmediatePropagation?.();
					t.blur();
				}
			};
			document.addEventListener("touchstart", onDown, true);
			document.addEventListener("mousedown", onDown, true);
			document.addEventListener("focusin", onFocusIn, true);
			return () => {
				document.removeEventListener("touchstart", onDown, true);
				document.removeEventListener("mousedown", onDown, true);
				document.removeEventListener("focusin", onFocusIn, true);
			};
		}

		// ══ 修复 37：点输入框后「指令卡片」赖着不走（2026-09-16 啾灵反馈）══
		// 现象（啾灵原话）：「我点击输入框左下角的加号，它弹出一个卡片；后来我又不想点
		//   里面的内容了，就继续点输入框去输入文字 —— 但那个卡片一直不消失，
		//   还要我再点一下加号才消失。我想要的是：点输入框，卡片就自己取消掉。」
		// 根因（官方「点外面关闭」的判据太宽）：
		//   官方 MenuView 的外部 pointerdown 处理器（dsh-client-ui-input-trigger）里有：
		//     if (listRef.current?.closest("[data-composer-card]"))?.contains(ev.target) return;
		//   这一句本意是"别把卡片内的点击当外部"，但卡片 = **整张 composer**（含输入框本体），
		//   而菜单 `[data-trigger-menu]` 自己就挂在卡片的 overlayAnchor 里 →
		//   **点输入框永远被判成"卡片内部"**，onDismiss 根本不触发。
		//   又：点输入框不改变草稿，也不会走 controller.track() 的"无触发符即关闭"路径
		//   → 两条关闭路径同时失效，卡片只能靠再点一次「+」按 toggle 关掉。
		//   （实测证据：点「+」后菜单 rect [16,17,326,328]；改点输入框后菜单依旧 open=true、
		//     menuInCard=true；pointerdown 目标 = DIV.uV2eYG_input 且 inCard=true。）
		// 修法（走官方 API，**不模拟点击**）：捕获阶段监听 pointerdown/touchstart，
		//   若指令菜单正开着、且按下的目标落在**输入框编辑区**（[data-composer-input]
		//   或 .uV2eYG_grow）内 → 直接调官方 controller.dismiss()。
		// ⚠️ 必须排除两种情况，否则会帮倒忙：
		//   ① 「+」开关按钮本身（button[aria-haspopup="listbox"]）——官方 toggle 的语义是
		//      "开着就关"，我们先关掉它就变成"关→再开"，菜单只会闪一下；
		//   ② 菜单内部本身（[data-trigger-menu]）——点选项要照常走官方 pick。
		// ⚠️ 不 preventDefault、不碰焦点：dismiss 只关菜单，随后浏览器照常把焦点给输入框，
		//   不会与修复 29 的键盘守卫打架。
		function mpTriggerController() {
			try {
				const sessions = mpSessionsOf();
				if (!sessions) return null;
				const list = sessions.list.getSnapshot();
				const id = list && list.current;
				if (id === void 0 || id === null) return null;
				const actx = sessions.scope(id);
				if (!actx) return null;
				if (!mpCtx) return null;
				const triggers = mpCtx.get("inputTriggers");
				if (!triggers || typeof triggers.sessionOf !== "function") return null;
				return triggers.sessionOf(actx) || null;
			} catch (e) { return null; }
		}

		function setupTriggerMenuAutoDismiss() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）
			const MENU_SEL = "[data-trigger-menu]";
			const onDown = (e) => {
				// 没开卡片就完全不管（零开销早退）
				if (!document.querySelector(MENU_SEL)) return;
				const t = e.target;
				if (!t || typeof t.closest !== "function") return;
				if (t.closest(MENU_SEL)) return;                                  // ① 菜单内部
				if (t.closest('button[aria-haspopup="listbox"]')) return;          // ② 「+」开关
				const editing = t.closest("[data-composer-input]") || t.closest(".uV2eYG_grow");
				if (!editing) return;                                             // 只认输入框编辑区
				const ctrl = mpTriggerController();
				if (!ctrl || !ctrl.menu || typeof ctrl.menu.getSnapshot !== "function") return;
				try {
					// 以 store 快照为准（touch 与 pointer 会各来一次，第二次已 closed → 早退）
					if (ctrl.menu.getSnapshot().open) ctrl.dismiss();
				} catch (err) { /* 静默：收卡片失败也绝不打断输入 */ }
			};
			document.addEventListener("pointerdown", onDown, true);
			document.addEventListener("touchstart", onDown, true);
			return () => {
				document.removeEventListener("pointerdown", onDown, true);
				document.removeEventListener("touchstart", onDown, true);
			};
		}

		// ══ 修复 38 / 38b：焦点已在输入框时，点「+」会直接唤起键盘（2026-09-16 啾灵反馈）══
		// 现象（啾灵原话）：「如果我的焦点原来已经在输入框上，此时我点加号的话会直接唤起键盘」。
		//   补充（第二次反馈）：「如果我原本焦点在输入框上，手动把输入法收起来之后，再点那个
		//   加号，输入法就会再次弹起；如果本来焦点不在输入框里，点加号就不会弹出输入法」。
		//
		// 官方机制：dsh-client-ui-conversation 里 composer 的 4 个按钮（指令 / 添加附件 /
		//   停止 / 发送）都绑了
		//     onMouseDown: (e) => { e.preventDefault(); editor.getRootElement().focus(...) }
		//   即 `keepFocus` —— **无条件**重聚焦输入框。
		//
		// 【修复 38 的认知（部分正确）】起初判断是"那次对已聚焦元素的重聚焦"重启了输入法：
		//   修复 29 的键盘守卫挂在 `focusin` 上，而对**已聚焦**元素再调 focus() 是空操作、
		//   不派发 focusin → 守卫没机会执行。于是在捕获阶段给输入框实例挂临时 noop `focus`
		//   吃掉这一次调用（不改原型、不 preventDefault、不阻断传播）。
		//
		// 【修复 38b：真机信标推翻上述判断】真机数据显示：点「+」前 v0=752、点完后 vmin=445
		//   （键盘确实弹起），同一时刻 sw=1（那次 focus() 已被中和）、pf=空（无任何 focus()
		//   逃逸）→ **弹键盘与那次 focus() 无关**。
		//   真因：`e.preventDefault()` 让编辑区**始终保持 DOM 焦点**（真机整个手势
		//   activeElement 恒为编辑区）。IME 已被手动收起时，Android 只要看到「仍有可编辑
		//   元素持有焦点」，就会在这次 composer 触摸上重新挂回输入法；焦点不在输入框时
		//   没有可恢复的输入连接 → 不弹（与啾灵两次补充观察完全吻合）。
		//   → 故键盘**不可见**时必须真的 `blur()` 移走焦点；只吞 focus() 不够。
		//   → 键盘**可见**时保持原样（用户在打字，绝不能抢焦点）。
		//
		// ⚠️ 为什么不直接 preventDefault + stopImmediatePropagation（更省事）：
		//   会连「访问模式 / 选择模型」两个弹出按钮的 mousedown 一起吃掉，使它们
		//   **不再移走焦点**（实测：这两个按钮的「焦点在输入框」由 false 变成 true）。
		// ⚠️ 不改原型（曾试 `HTMLElement.prototype.focus` 包装 → 波及所有元素且抛
		//   `triggerRef.current?.focus is not a function`）。
		// ⚠️ 教训：修复 38 的「18/18 全过」量的是**代理指标**（focus() 调用次数）。
		//   无头 Chromium **没有输入法**，"键盘是否弹出"在无头里根本不可观测。
		//   键盘类修复只有真机 visualViewport 信标或用户确认才算证据。
		function setupKeepFocusGuard() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）
			const INPUT_SEL = "[data-composer-input]";
			const CARD_SEL = "[data-composer-card]";
			let stubbed = null, prevOwn = false, prevVal = null;
			let tid = 0;
			let restVh = 0;

			const vh = () => {
				try { return window.visualViewport ? window.visualViewport.height : window.innerHeight; } catch (e) { return 0; }
			};
			// 静止视口高度 = 历史最大值（键盘只会让可视区变矮，不会变高）
			const sampleRest = () => {
				try { const h = vh(); if (h > restVh) restVh = h; } catch (e) { /* 静默 */ }
			};
			// 键盘是否**正在占据视口**。
			// ① 官方 VirtualKeyboard API（Android Chrome 94+）最直接；
			// ② 回退「当前可视高比静止高度矮 80px 以上」——真机实测：静止 752、键盘弹起 445~504。
			// 两条都判不出 → 保守当作**可见**：宁可保留官方行为，也绝不误关用户正在用的键盘。
			const imeVisible = () => {
				let byApi = null;
				try {
					const vk = navigator.virtualKeyboard;
					if (vk && vk.boundingRect) byApi = vk.boundingRect.height > 0;
				} catch (e) { /* 静默 */ }
				if (byApi === true) return true;
				const byVh = restVh > 0 ? (restVh - vh()) > 80 : null;
				if (byVh === true) return true;
				if (byApi === false || byVh === false) return false;
				return true;
			};
			const clearTimer = () => {
				try { if (tid) { window.clearTimeout(tid); tid = 0; } } catch (e) { /* 静默 */ }
			};
			// 只有触摸设备才有"软键盘被手动收起后又被重新挂回"这件事。
			// 桌面端鼠标点「+」时抢走焦点 = 打断正在打字的人 → 必须排除。
			const isTouchUI = () => {
				try {
					return (navigator.maxTouchPoints || 0) > 0 &&
						!!window.matchMedia && window.matchMedia("(pointer: coarse)").matches;
				} catch (e) { return false; }
			};
			// 修复 38b 的注记：官方 keepFocus 里的 preventDefault() 让编辑区**始终
			// 保持 DOM 焦点**（真机信标实测：整个手势 activeElement 恒为编辑区）。
			// 键盘被用户手动收起后，Android 只要看到「仍有可编辑元素持有焦点」，就会在
			// 这次 composer 触摸上重新挂回输入法 —— 与官方那次 focus() 无关。
			// → 键盘不可见时必须真的把焦点移走；但**移走的时机**由修复 64 的共享闸门
			//   决定（见 setupComposerKeyboardGuard 上方的长注释），不能就地 blur。
			const restore = () => {
				clearTimer();
				if (!stubbed) return;
				try { if (prevOwn) stubbed.focus = prevVal; else delete stubbed.focus; } catch (e) { /* 静默 */ }
				stubbed = null;
			};
			const arm = (e) => {
				try {
					// 同一次手势内（touchstart → 合成 mousedown）已接管 → 桩继续留着
					if (stubbed) return;
					const t = e.target;
					if (!t || typeof t.closest !== "function") return;
					if (!t.closest(CARD_SEL)) return;          // 只在 composer 内
					if (t.closest(INPUT_SEL)) return;          // 用户点输入框本体 → 官方语义照旧
					if (!t.closest("button")) return;          // 只针对按钮
					const inp = document.activeElement;
					// 仅当编辑区**此刻已聚焦**才接管。未聚焦时官方 keepFocus 会派发 focusin，
					// 由修复 29 的守卫拦下（那条路径本来就是对的）。
					if (!inp || !inp.matches || !inp.matches(INPUT_SEL)) return;
					sampleRest();
					// ⭐ 修复 38b（2026-09-16 真机实测根因）：
					//   官方 keepFocus 里的 e.preventDefault() 让编辑区**始终保持 DOM 焦点**
					//   （真机信标实测：整个手势 activeElement 恒为编辑区）。
					//   键盘被用户手动收起后，Android 只要看到「仍有可编辑元素持有焦点」，
					//   就会在这次 composer 触摸上重新挂回输入法 —— **与官方那次 focus() 无关**：
					//   实测已中和 focus()（sw=1）、无任何泄漏（pf=空），键盘照样弹起（752→445）。
					//   反之焦点不在输入框时没有可恢复的输入连接，所以不弹（与啾灵观察一致）。
					//   → 键盘不可见时，必须**真的把焦点移走**；只吞 focus() 不够。
					//
					// ⭐ 修复 64：移走焦点的**时机**由共享闸门决定（见上方长注释）。
					//   「+」（`button[aria-haspopup="listbox"]`，真机实测 aria-haspopup=listbox）
					//   要等指令菜单渲染出来再 blur，否则菜单根本不会出现；其余按钮立即 blur。
					const btn = t.closest("button");
					if (!imeVisible()) {
						// 仅触摸端执行：桌面端没有软键盘，抢焦点只会打断打字。
						if (isTouchUI()) {
							try {
								if (btn && btn.matches('button[aria-haspopup="listbox"]')) mpDeferBlur(inp);
								else inp.blur();
							} catch (e2) { /* 静默 */ }
						}
					}
					prevOwn = Object.prototype.hasOwnProperty.call(inp, "focus");
					prevVal = prevOwn ? inp.focus : null;
					// 这一步在 38b 里**仍然必要**：我们刚 blur 走焦点，官方 keepFocus 的
					// mousedown 处理器紧接着就会 `focus()` 把它抢回来，必须吃掉。
					// 桩必须留到本次手势走完（blur 之后合成的 mousedown 仍会调它，
					// 早拆会让编辑区被重新聚焦 → 键盘又回来）。
					// 桩本身是**真正静默**的 noop（不写全局、不产生副作用）。
					inp.focus = function () { /* 修复 38/38b：吃掉官方 keepFocus 的这一次重聚焦 */ };
					stubbed = inp;
					clearTimer();
					// 兜底还原：任何异常路径都保证桩不残留（正常路径由 mouseup/click 拆）
					tid = window.setTimeout(restore, 700);
				} catch (err) { /* 静默：绝不打断输入 */ }
			};
			// ⚠️ 只在 mouseup/click 拆桩，**绝不在 touchend 拆**：
			//   触摸端合成鼠标事件的顺序是 touchstart → touchend → **mousedown** → mouseup → click，
			//   在 touchend 拆会让随后的合成 mousedown 重新武装失败（此时编辑区已被 blur、
			//   不再是 activeElement）→ 官方 keepFocus 的 focus() 逃逸 → 键盘回来。
			const onEnd = () => restore();

			sampleRest();
			try { if (window.visualViewport) window.visualViewport.addEventListener("resize", sampleRest); } catch (e) { /* 静默 */ }
			window.addEventListener("resize", sampleRest);
			document.addEventListener("touchstart", arm, true);
			document.addEventListener("mousedown", arm, true);
			window.addEventListener("mouseup", onEnd, false);
			window.addEventListener("click", onEnd, false);
			return () => {
				restore();
				mpFlushPendingBlur(); // 修复 64：拆掉待执行的 observer/定时器，别泄漏
				try { if (window.visualViewport) window.visualViewport.removeEventListener("resize", sampleRest); } catch (e) { /* 静默 */ }
				window.removeEventListener("resize", sampleRest);
				document.removeEventListener("touchstart", arm, true);
				document.removeEventListener("mousedown", arm, true);
				window.removeEventListener("mouseup", onEnd, false);
				window.removeEventListener("click", onEnd, false);
			};
		}

		// ══════════════════════════════════════════════════════════════════
		// 特权权限（uid 2000）设置分节
		//
		// 需求：设置页加一项，专门引导用户打开 uid 2000 权限。
		// 方案（用户选定）：静态引导 + 实时状态检测；入口 = 设置页左侧一级导航。
		//
		// 接缝 = 官方 `settings.section` 槽位（`dsh-client-ui-settings-plugins` 用的
		// 是同一个）。注册进去就自动出现在一级导航里，并被**现有的两级导航**接管 ——
		// `setupSettingsDialog()` 会把 nav 竖向化、点进去全屏显示二级页，
		// 所以这里**不需要**碰导航 DOM，也不必再写一套页面切换。
		//
		// 状态从哪来：浏览器进程读不到 `process.env` / 文件系统，所以状态由宿主半
		// （`lib/index.js`）的 `GET /api/dsh-android/privileged` 提供。那里返回的是
		// **端到端跑一次 `id -u`** 的结果，不是「文件在不在」—— 密钥文件存在但未被
		// adbd 授权时，通道照样拿不到 uid 2000，只查文件会得到**假阳性**。
		// ══════════════════════════════════════════════════════════════════
		/* ══ 修复 67：回车 = 换行（移动端）══

		   背景：官方 composer 的键盘映射（"dsh-client-ui-conversation/lib/client.js" 的
		   "registerComposerKeymap"）是**桌面语义**：

		     | 按键            | 官方行为 | 出处 |
		     |-----------------|----------|------|
		     | "Enter"         | **发送** | L16704-16716 "handlers.submit(...)" |
		     | "Shift+Enter"   | 换行     | L16706 "if (event?.shiftKey === true) return false;"（放行给编辑器默认行为） |
		     | "Ctrl/Cmd+Enter"| 发送     | L16715 "submit(event.ctrlKey === true \|\| event.metaKey === true)" |

		   桌面上有 Shift 键，所以「Enter 发送、Shift+Enter 换行」是合理默认。但**手机
		   软键盘没有 Shift**（中文输入法更不会给），于是用户想在多行里换行时**做不到**：
		   一按回车就发出去了，长 prompt 只能挤成一行。这是纯移动端才有的一类缺口。

		   做法：在**捕获阶段**拦下编辑器上的 Enter，把它改写成 "Shift+Enter" 派发回去。
		   这样官方那条 "shiftKey === true → return false" 的分支就命中了，编辑器执行
		   默认行为（插入换行），而**不需要**去改引擎源码、也不依赖任何私有 API。

		   ⚠️ **必须拦在 composer 编辑区上，不能拦 document**：官方把键盘映射注册在
		     编辑器实例上（"editor.registerCommand"），拦到别处既无效、又会误伤设置页
		     等其它输入框（那些地方回车该是「确认」）。
		   ⚠️ **必须检查 "isComposing" / "keyCode === 229"**：中文输入法的候选框确认也
		     发 Enter。不排除的话，**选词会被当成换行**，用户根本没法打中文。官方
		     "isComposingEvent()" 用的就是这两个判据（外加 composition 结束 10ms 内的宽限）。
		   ⚠️ **要用真实焦点上的元素派发**，且 "cancelable: true"、"composed: true"：
		     官方这条命令是挂在该编辑器实例的 root 上的。
		   ⚠️ **不要试图 preventDefault 后手工插 "\n"**：编辑器是 Lexical（"editor.update()"
		     那套），直接改 DOM 会被它的模型覆盖掉。走「改写成 Shift+Enter」这条路，
		     等于让官方自己执行它本来就有的换行分支。 */
		const MP_ENTER_SEL = "[data-composer-input]";

		function setupEnterAsNewline() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）

			/** 中文/日文输入法正在组字时，回车属于「上屏」而不是「换行」。 */
			function mpIsComposing(e) {
				if (!e) return false;
				if (e.isComposing === true || e.keyCode === 229) return true;
				// 输入法上屏后紧跟的那次回车，浏览器可能已经把 isComposing 置回 false。
				// 用「最近一次 compositionend 的时间」兜一下（官方宽限 10ms，这里给 80ms：
				// 手机上输入法的时序比桌面慢，实测偶有超出 10ms 的情况）。
				return mpLastCompositionEnd > 0 && (Date.now() - mpLastCompositionEnd) < 80;
			}

			const onKeyDown = (e) => {
				try {
					if (e.key !== "Enter") return;
					// 已经是「换行」或「发送」的变体（用户外接键盘按了 Shift/Ctrl/Cmd）：
					// 一律放行给官方，保持外接键盘上的桌面习惯不变。
					if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
					if (mpIsComposing(e)) return;
					const ed = e.target && e.target.closest ? e.target.closest(MP_ENTER_SEL) : null;
					if (!ed) return;

					// 吃掉这一次 Enter，再补一次带 shiftKey 的 —— 官方把它当换行。
					e.preventDefault();
					e.stopPropagation();
					// ⚠️ 派发目标优先用**真实焦点元素**：官方那条命令注册在编辑器实例的
					//   root 上，而 `[data-composer-input]` 未必就是那个 root（可能只是
					//   它的祖先）。`dispatchEvent` 只向上冒泡、**不进入子元素**，若从祖先
					//   派发而监听在子元素上就永远收不到。焦点元素必然在编辑区内部，
					//   且 `bubbles: true` 会一路冒泡经过 root ⇒ 两种情形都覆盖到。
					let target = ed;
					try {
						const a = document.activeElement;
						if (a && a !== document.body && ed.contains(a)) target = a;
					} catch (err2) { /* 静默：拿不到焦点就用编辑区本体 */ }
					const synth = new KeyboardEvent("keydown", {
						key: "Enter", code: "Enter", keyCode: 13, which: 13,
						shiftKey: true, bubbles: true, cancelable: true, composed: true,
					});
					target.dispatchEvent(synth);
				} catch (err) { /* 静默：绝不打断输入 */ }
			};

			// 组字状态由编辑器自己维护，这里只需要知道「刚刚结束过一次组字」。
			const onCompositionEnd = () => { mpLastCompositionEnd = Date.now(); };

			document.addEventListener("keydown", onKeyDown, true);
			document.addEventListener("compositionend", onCompositionEnd, true);
			return () => {
				document.removeEventListener("keydown", onKeyDown, true);
				document.removeEventListener("compositionend", onCompositionEnd, true);
			};
		}

		/* ══ 修复 78：contenteditable 里「点中间落行尾」—— 亲手校正光标 ══

		   背景：0.1.5 起官方把输入框从 "<textarea>" 换成了 **contenteditable div**
		   （".uV2eYG_input[data-composer-input][role=textbox]"，见修复 61 的注释）。
		   textarea 的光标定位由内核直接映射「像素 → 字符偏移」，很准；而
		   contenteditable 需要内核先把视口坐标落到**文本行内的字形**上，这一步
		   Android WebView 做得相当差：

		     · 点在文字中间，光标却跳到**行尾**（最常见）
		     · 点在第二行，光标留在第一行末尾
		     · 有时干脆不动

		   用户反馈原话：「这个 app 输入框里没法通过点击选中文字中间的位置」。

		   这是 WebView 的固有缺陷（不是本 App 写错），但**可以自己算**：
		   "{@link caretRangeFromPoint}" 能直接把视口坐标换成 DOM Range，
		   再由 Range 定出光标。Chromium 从 76 起就同时支持标准的
		   "caretPositionFromPoint" 和带 "-webkit-" 前缀的旧名，两个都试。

		   ## 为什么必须放在 "click" 而不是 "touchstart"

		   用户可能是在**长按选词**、也可能是**滑动**。若在 touchstart 就强行
		   落光标，长按选择会被打断、滑动会变成误点。放在 "click" 上，
		   浏览器已经判定「这是一次点击（没有拖拽）」—— 语义正好。

		   ## 为什么只在「落点与内核算出来的位置不一致」时才动手

		   内核大多数情况下是对的，无脑覆盖会破坏：
		     · 输入法组字中的光标（组字期间动 selection 会丢字）
		     · 用户按住 shift/外接键盘的扩选
		     · 双击选词、三击选段

		   所以先读出现有 selection，只有「这是空选区（光标，不是选区）」且
		   「算出来的落点明显不同」才修正。偏差容差用 1 个字符。

		   ⚠️ **不动 "contenteditable" 之外的元素**：普通 input/textarea 内核
		     定位是准的，插手反而出错。
		   ⚠️ 全程 try/catch：光标是锦上添花，任何异常都不能影响输入。 */

		function setupCaretFix() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）

			/** 把视口坐标换成 Range；拿不到返回 null。 */
			function rangeFromPoint(x, y) {
				try {
					// 标准名（Chromium 128+ 起是标准）与旧前缀名都试。
					if (typeof document.caretPositionFromPoint === "function") {
						const pos = document.caretPositionFromPoint(x, y);
						if (pos && pos.offsetNode) {
							const r = document.createRange();
							r.setStart(pos.offsetNode, pos.offset);
							r.collapse(true);
							return r;
						}
					}
					if (typeof document.caretRangeFromPoint === "function") {
						const r = document.caretRangeFromPoint(x, y);
						if (r) return r;
					}
				} catch (e) { /* 老内核不支持就算了 */ }
				return null;
			}

			const onClick = (e) => {
				try {
					// 只在「真正的点击」上动手：键盘修饰键一律让给官方（扩选/选段）。
					if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
					// 输入法组字中不动 selection，否则会丢字。
					if (e.isComposing) return;

					// ⚠️ **必须取编辑器根节点，不能用 e.target 当容器**。
					//   点文字时 target 是内部文本节点所在的行内元素（Lexical 渲染成
					//   span），点空白处可能是 root 自己，点到 <br> 又换一个 ——
					//   用 target 做 contains 判据会漏掉一大半情形。
					//   判据分两步：① 点击位置在 composer 内（用 closest 判断归属）；
					//   ② 落点也在同一个编辑器根内。
					const t = e.target;
					if (!t || !t.closest) return;
					// 只认 composer 输入区，别去动对话正文（那里不需要打字光标）。
					const root = t.closest("[data-composer-input], .uV2eYG_root");
					if (!root) return;

					// 真正的编辑元素：优先带 data-lexical-editor 的那个，
					// 否则退回 contenteditable 本体。
					const editor = root.matches("[contenteditable]")
						? root
						: (root.querySelector('[contenteditable="true"]') || root);
					if (!editor || !editor.isContentEditable) return;

					const sel = window.getSelection && window.getSelection();
					if (!sel || sel.rangeCount === 0) return;
					// 已经有选区（不是光标）⇒ 用户可能在选字，别抢。
					if (!sel.isCollapsed) return;

					const want = rangeFromPoint(e.clientX, e.clientY);
					if (!want) return;
					// 落点必须在**编辑器根**里，否则说明算到了别处（例如浮层）。
					if (!editor.contains(want.startContainer)) return;

					// 与内核算出来的位置比对：同一个节点且偏移差 ≤1 就不动。
					const cur = sel.getRangeAt(0);
					if (cur.startContainer === want.startContainer &&
						Math.abs(cur.startOffset - want.startOffset) <= 1) {
						return;
					}

					// ⚠️ **必须延后一拍再改，原因有两个**：
					//
					//   ① composer 是 Lexical 编辑器（`data-lexical-editor="true"`），
					//      它自己也监听 click 并会把 selection 写回内部 model。
					//      我们若在同一个事件里抢先改，随后就被它覆盖 ——
					//      用户看到「光标闪一下又跳回行尾」，比不修还糟。
					//      延后到本次事件全部处理完（setTimeout 0）再改，顺序才对。
					//   ② Lexical 通过 `selectionchange` 同步 model。改完 DOM selection
					//      后浏览器会自己派发该事件，Lexical 就跟着更新 ——
					//      不需要（也拿不到）它的内部 API。
					//
					//   ⚠️ 不要试图在 `__lexicalEditor.update()` 里改 DOM selection：
					//      那个回调是给 Lexical 节点操作用的，在里面动 DOM selection
					//      不会写进它的 model，反而与 reconcile 打架。延后 + 让浏览器
					//      派发 selectionchange 是唯一不需要碰内部 API 的路径。
					const x = e.clientX, y = e.clientY;
					setTimeout(function () {
						try {
							const w = rangeFromPoint(x, y);
							if (!w) return;
							if (!editor.contains(w.startContainer)) return;
							const s2 = window.getSelection && window.getSelection();
							if (!s2) return;
							// 用户可能在这几十毫秒里开始选字了，再确认一次。
							if (!s2.isCollapsed) return;
							s2.removeAllRanges();
							s2.addRange(w);
						} catch (e2) { /* 延后失败也不影响输入 */ }
					}, 0);
				} catch (err) { /* 光标修正失败绝不影响输入 */ }
			};

			document.addEventListener("click", onClick, true);
			return () => document.removeEventListener("click", onClick, true);
		}

		/* ══ 修复 68：凭据输入框改用普通键盘（移动端）══

		   背景：官方把「API key / 凭据」输入框渲染成 "<input type="password">"：

		     · "dsh-client-ui-settings-models/lib/client.js" L1380 与 L1692
		       （模型配置页的两个变体：内置 provider 与自定义 provider）
		     · "dsh-web-frontend/dist" 里的通用凭据字段组件（"type:"password""）

		   这是**桌面语义**：桌面上 "type=password" 只意味着「遮挡字符」，键盘还是
		   物理键盘，"Ctrl+V" 照常可用。但在 Android 上它有一个额外且不明显的后果 ——
		   **输入法会据此切换到「安全键盘」**（华为/小米/OPPO 的密码输入法），而
		   安全键盘**按设计屏蔽剪贴板**：既没有粘贴入口，长按也不出粘贴菜单。

		   而 API key 是一串 51 字符的 "sk-..."，**手抄不现实**，用户唯一的现实
		   做法就是从别处复制粘贴。于是「安全键盘」在这里不是安全增强，而是**把
		   这个功能变不可用**。这是纯移动端才有的一类缺口（同修复 67）。

		   做法：把 "type="password"" 改写成 "type="text""，并移除 "autocomplete"。
		   这样输入法看到的是普通文本框 ⇒ 普通键盘 ⇒ 剪贴板可用。

		   ⚠️⚠️ **绝对不要为了保留圆点外观而补 CSS 遮蔽 —— 那会让整个修复失效。**
		     第一版正是这么干的（`input[data-mp-key="1"] { -webkit-text-security:
		     disc !important; }`），结果属性层面全对、行为层面全错。真机实测：
		       焦点元素的 JS 属性： INPUT type=text  ac=(空)  pwdSel=0   ← 全对
		       输入法实际收到的：   inputType=0xe1                        ← 仍是密码型
		     "0xe1" = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD。
		     根因是 blink 的 "Element::ShouldObscureValue()" 只看
		     "computedStyle.textSecurity != none" —— **CSS 遮蔽本身就是触发条件**，
		     "type=password" 只是经 UA 样式表拿到同一条声明。
		     也就是说：**「普通键盘」与「圆点遮蔽」在 Android WebView 上不可兼得。**
		     用户要的是前者（「跟普通输入框一样」「能粘贴」），所以本插件让 API key
		     明文可见，不做任何遮蔽补偿。详见「修复 68 的教训」那段 CSS 注释。

		   ⚠️ **必须全局扫描，不能只认某个选择器**：这些输入框**没有稳定的
		     class / data 属性**可锚（class 是 CSS Modules 的哈希名，随构建变），
		     且分布在**两个独立编译的包**里。唯一稳定的判据就是 "type="password""
		     这个属性本身 —— 它也正是输入法所用的判据。
		   ⚠️ **用 MutationObserver 兜住懒挂载与重渲染**：设置页是按需打开的
		     对话框，"apply()" 执行时它还不存在。同时观察 "type" 属性变化，
		     万一 React 把它设回去也能立刻改回来。
		   ⚠️ **只改 type，不动 value / onChange**：官方那两个框是**受控组件**
		     （"value={keyDraft}"），字符本身由 React 状态持有。改 type 不碰状态，
		     React 下次渲染时该属性与虚拟 DOM 一致（React 侧一直是 "password"、
		     未变化 ⇒ 不会重设），因此不会打架。
		   ⚠️ **不改 autoComplete**："new-password" 只影响浏览器的密码管理器提示，
		     与输入法（安全键盘）无关，动了反而可能引入自动填充弹窗。
		   ⚠️ 只在移动端生效（修复 40 的惯例）：桌面端 "type=password" 完全正常，
		     没有「安全键盘屏蔽剪贴板」这个问题，不该被改。
		   ⚠️ "data-mp-key" 标记保留：它不再驱动任何 CSS 遮蔽，只作为「本插件
		     处理过这个框」的标记，便于排查时一眼认出（探针就靠它定位焦点元素）。 */
		const MP_KEY_ATTR = "data-mp-key";

		/**
		 * ══ 修复 68 的真正生效点：在**原型层**拦掉 "type=password" ══
		 *
		 * 为什么必须有这一层 —— 这是本修复第二版才搞明白的关键机制：
		 *
		 * 官方那两个凭据输入框除了 "type="password"" 之外，还带 **"autoFocus: true"**
		 * （"dsh-client-ui-settings-models/lib/client.js" L1699 与 L2552）。React 把
		 * 元素插入 DOM 的**同一帧**就让它获得焦点，WebView 在那时按「密码框」建立了
		 * 输入连接并把 "inputType" 定成 "TYPE_TEXT_VARIATION_WEB_PASSWORD"(0xe1)。
		 *
		 * 而 **WebView 不会为已经建立的连接重算 "inputType"**。所以「先让 password
		 * 存在、再由 MutationObserver 改成 text」这条路**必然失败**：改属性发生在
		 * 聚焦之后，输入法早就拿到密码型类型了。
		 *
		 * 真机实测（Huawei CDY-TN90 / Android 10，"dumpsys input_method" 读实时值）：
		 *
		 *   只做事后改属性（第一版行为）：  DOM type=text、ts=none  →  inputType=0xe1 ✗
		 *   本层拦截，password 从未出现：   DOM type=text、ts=none  →  inputType=0x80a1 ✓
		 *
		 * 顺带排除掉的几个嫌疑（都实测过，**不是**触发条件，别再去改它们）：
		 *   · "data-mp-key" 标记本身 —— 带它的纯净 input 仍是 0x0
		 *   · CSS "-webkit-text-security" —— 已确认可读，焦点框实测 "ts=none"
		 *   · blur + focus 重建连接 —— 实测刷不动 "inputType"
		 *
		 * 做法：重定义 "HTMLInputElement.prototype" 的 "type" 访问器与 "setAttribute"，
		 * 把写入的 ""password"" 就地换成 ""text""。React 完全无感 —— 它写 password、
		 * 读回来的也是它自己以为的 password（React 的受控逻辑只认自己的 props，
		 * 不读 DOM 属性），而真实 DOM 里**从头到尾没有出现过 password**。
		 *
		 * ⚠️ 只拦 "type"，**不碰 value / onChange / required**：官方那两个框是受控组件，
		 *    字符由 React 状态持有，本层不介入数据流。
		 * ⚠️ "autocomplete" 仍交给下面的 MutationObserver 摘（它不影响输入连接，
		 *    早一步晚一步都行）。
		 * ⚠️ 只在移动端生效（修复 40 的惯例）：桌面端没有安全键盘问题，不该被改。
		 */
		function installPasswordTypeShim() {
			if (!mpIsMobile()) return () => {};
			try {
				const proto = HTMLInputElement.prototype;
				const desc = Object.getOwnPropertyDescriptor(proto, "type");
				if (!desc || typeof desc.set !== "function") return () => {};
				const origSet = desc.set;
				const origGet = desc.get;
				const origSetAttribute = proto.setAttribute;

				Object.defineProperty(proto, "type", {
					configurable: true,
					enumerable: desc.enumerable,
					get() { return origGet.call(this); },
					set(value) {
						// 只改写入值，不改语义：调用方以为写的是 password，DOM 拿到 text。
						origSet.call(this, String(value).toLowerCase() === "password" ? "text" : value);
					},
				});
				proto.setAttribute = function (name, value) {
					if (String(name).toLowerCase() === "type" && String(value).toLowerCase() === "password") {
						return origSetAttribute.call(this, "type", "text");
					}
					return origSetAttribute.call(this, name, value);
				};
				// 只还原这两个，不做通用 hook 复原：本函数只改这两处。
				return () => {
					try {
						Object.defineProperty(proto, "type", desc);
						proto.setAttribute = origSetAttribute;
					} catch (err) { /* 静默 */ }
				};
			} catch (err) {
				return () => {}; // 拦截失败就退化成下面 MutationObserver 的事后补救
			}
		}

		function setupPlainKeyboardForCredentials() {
			if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（桌面无安全键盘）

			// ⚠️ **顺序要紧**：拦截器必须在任何 React 渲染之前装上，否则第一帧的
			//    `type=password` + autoFocus 又会把输入连接定成密码型（见上面长注释）。
			const disposeShim = installPasswordTypeShim();

			/** 把一处密码输入框降级成普通文本框 + 打标记。返回是否真的改了。 */
			function soften(el) {
				if (!el || el.nodeType !== 1 || el.tagName !== "INPUT") return false;
				// ⚠️ **先看现在是不是密码框，不是就一个字节都不动。**
				//   ① 安全：只处理真正的密码框，页面里普通的 `input[type=email]`
				//      （登录页之类）不被误标。
				//   ② 幂等：改成 text 之后再调用会走到这里直接返回，不产生任何写入 ⇒
				//      MutationObserver 不会自激。
				//   装了拦截器之后，`type=password` 几乎不会出现（写进去就变 text），
				//   所以这里通常不命中 —— 它现在主要是**兜底**：万一 React 用
				//   `innerHTML`/`cloneNode` 等绕过 setter 的路径造出密码框，
				//   或拦截器因环境差异没装上，仍然能补救。
				if ((el.getAttribute("type") || "").toLowerCase() !== "password") return false;
				try {
					el.setAttribute(MP_KEY_ATTR, "1");
					// 主触发点：`type=password` ⇒ WebView 的 inputType 变成
					// TYPE_TEXT_VARIATION_PASSWORD ⇒ 输入法切安全键盘。
					el.setAttribute("type", "text");
					// 次要触发点：`autocomplete="new-password"` 是 Chromium 的 autofill
					// 提示（AutofillManager 会把它作为 hint 交给输入法），部分国产输入法
					// 据此也判成密码字段。**整个移除属性**而不是改成 "off" —— 后者会让
					// Chromium 置上 `IME_FLAG_NO_PERSONALIZED_LEARNING`，又是一条「敏感
					// 字段」信号，等于换一个可能触发安全键盘的理由。
					if (el.hasAttribute("autocomplete")) el.removeAttribute("autocomplete");
					return true;
				} catch (err) { return false; } // 静默：绝不打断设置页
			}

			/**
			 * 兜底扫描 + 摘 "autocomplete"。
			 *
			 * ⚠️ 装了拦截器之后，这里**不再**是主路径（password 压根不会出现），
			 *    它的职责变成两件：
			 *      ① 万一拦截器没生效（环境差异）时的补救；
			 *      ② 摘掉 "autocomplete="new-password"" —— 拦截器不管这个属性。
			 *    所以判断条件放宽成「带 data-mp-key 或 autocomplete 的输入框」，
			 *    而不是只认 "type=password"。
			 */
			function tidy(el) {
				if (!el || el.nodeType !== 1 || el.tagName !== "INPUT") return false;
				let changed = false;
				try {
					if ((el.getAttribute("type") || "").toLowerCase() === "password") {
						changed = soften(el) || changed;
					}
					// autocomplete 只有"像密码框"的输入框才需要摘 —— 判据是官方那两处
					// 都会带 `new-password`，普通输入框的 autocomplete（如 "email"）
					// 是页面正常语义，不该动。
					if (el.getAttribute("autocomplete") === "new-password") {
						el.removeAttribute("autocomplete");
						changed = true;
					}
				} catch (err) { /* 静默 */ }
				return changed;
			}

			function scan(root) {
				if (!root || typeof root.querySelectorAll !== "function") return;
				try {
					tidy(root); // root 自身可能就是那个输入框（addedNodes 分支）
					root.querySelectorAll("input").forEach(tidy);
				} catch (err) { /* 静默 */ }
			}

			scan(document);

			const obs = new MutationObserver((records) => {
				for (const r of records) {
					// attributes 分支：React 把 type / autocomplete 设回去时立刻改回来。
					// tidy 自身幂等（无需改动时不产生写入）⇒ 不会自激循环。
					if (r.type === "attributes") { tidy(r.target); continue; }
					for (const n of r.addedNodes) scan(n);
				}
			});
			// subtree + childList 抓懒挂载（设置页是按需打开的对话框）；
			// attributes 抓 React 回写。
			obs.observe(document.documentElement || document.body, {
				childList: true, subtree: true,
				attributes: true, attributeFilter: ["type", "autocomplete"],
			});

			return () => {
				try { obs.disconnect(); } catch (err) { /* 静默 */ }
				disposeShim();
			};
		}

		/** 最近一次组字结束的时间戳（0 = 从未）。见 setupEnterAsNewline。 */
		let mpLastCompositionEnd = 0;

		/**
		 * 本文件的构建指纹。
		 *
		 * ## 为什么需要它
		 *
		 * 真机日志里只写「dsh-android-mobile」时，**无法区分跑的是哪个版本**。
		 * 一次「改了没生效」的排查由此变成猜谜：到底是代码写错了，还是
		 * 设备上那份根本没换？两者需要完全相反的处理。
		 *
		 * 所以给每次构建一个可肉眼比对的短串。改了本文件就把它一起改掉，
		 * 真机日志里出现它 ⇒ 新版真的上屏了；不出现 ⇒ 旧文件还在，去查解包。
		 *
		 * 2026-10-29-a：`diag` 提到模块级（修 ReferenceError），改名 `mpDiag`。
		 *
		 * 2026-10-31-a：修 `onUiReady` 可能永不上报的三处根因（见 `mpSignalUiReady`
		 *   与 `apply()` 开头的注释）：守候提到最前 + 桥后到轮询 + pageLoaded 宽限。
		 */
		const MP_BUILD = '2026-11-10-features';

		const mpDiag = (msg) => {
			try { console.log("[dsh-android-mobile@" + MP_BUILD + "] " + msg); } catch { /* ignore */ }
			try { window.DSHAndroid?.pageDiag?.("[" + MP_BUILD + "] " + String(msg)); } catch { /* ignore */ }
		};

		function apply(ctx) {
			/*
			 * 启动自报：真机日志里必须能看到这一行。
			 *
			 * 它存在的唯一目的，是让「新版到底有没有上屏」变成一个**看得见的事实**。
			 * 看不到这条 ⇒ 设备上跑的还是旧文件，不用再猜代码。
			 */
			mpDiag('插件已装配 build=' + MP_BUILD);

			/*
			 * ## ⚠️⚠️ `onUiReady` 必须**立刻**开始守，不能等后面那几十个初始化 (§2026-10-31)
			 *
			 * 它原来排在 `apply()` 的**末尾**，前面有 30 多个初始化调用
			 *（`mpBuildShell` / `mpSyncDrawer` / `setupImePrejudge` /
			 * `mo.observe` …），**全程没有 try/catch**。
			 * 任何一个抛异常，`apply()` 就地中断 ⇒ `mpSignalUiReady()` 永不执行
			 * ⇒ 原生侧的 `onUiReady` 永远不来 ⇒ **启动层永远盖着，界面一片空白**。
			 *
			 * 真机取证（20261031-053321，vivo V2231A / gecko 92）：
			 *
			 *     05:29:46  engine.ready ms=8537 port=3080   ← 引擎好好的
			 *     05:29:58  界面就绪回调超时（内核140，页面错误: 无）
			 *     05:30:13  界面就绪回调超时                 ← 27 秒都没来
			 *
			 * 那条日志里**没有** `插件已装配 build=` 之外的任何一行 —— 说明插件
			 * 起来了但中途断了。用户看到的红字是我加的 27 秒兜底，也就是说
			 * **兜底正确地报了「真起不来」，但没人能救**。
			 *
			 * 所以把它提到第一件事：**先挂上守候，再去干别的**。
			 * 这样后续任何一步崩掉，只影响对应功能，不会再让整个界面卡在启动层。
			 */
			try {
				mpSignalUiReady();
			} catch (e) {
				mpDiag('onUiReady 守候安装失败: ' + (e && e.message));
			}

			mpCtx = ctx;
			mpSessionsCache = ctx.get("sessions") ?? null;
			mpInstallPendingTrust();   /* 修复 51：越早越好 */
			mpWatchSessions(ctx);       /* 修复 51：等服务就绪 */
			injectStyles();
			const disposeKeyboardGuard = setupComposerKeyboardGuard();
			const disposeTriggerMenuDismiss = setupTriggerMenuAutoDismiss();
			const disposeKeepFocusGuard = setupKeepFocusGuard();
			const disposeEnterAsNewline = setupEnterAsNewline();   /* 修复 67 */
			const disposePlainKey = setupPlainKeyboardForCredentials();   /* 修复 68 */
			const disposeCaretFix = setupCaretFix();   /* 修复 78 */
			// 初始一轮
			document.querySelectorAll(".xzv4MW_timeEnd").forEach(trimMeta);
			mpCompactStats();
			watchApproval();
			mpBuildShell();
			mpSyncDrawer();
			bindTitleRename();
			document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
			document.addEventListener("click", onNewSessionClick, true);
			document.addEventListener("click", onSessionRowClick, true);
			document.addEventListener("click", onPanelRowClick, true);   /* 修复 66 */
			const disposePressFeedback = setupPressFeedback();
			const disposeImePrejudge = setupImePrejudge();   /* 修复 65 */
			mo.observe(document.body, { childList: true, subtree: true, characterData: true });
			mpStatsObserver.observe(document.body, { attributes: true, subtree: true, attributeFilter: ["aria-label"] });
			// 启动爆发：apply() 执行时 React 尚未挂载，而 observer 有 300ms 节流，
			// 导致顶栏会话标题出现偏慢。前 ~1.2s 内主动补 10 轮（120ms 间隔），
			// 之后完全交给 observer 常态驱动，不增加稳态开销。
			let mpBootBursts = 0;
			const mpBootBurstTimer = window.setInterval(() => {
				mpSyncMobileAttr();
				mpSyncDrawer();
				bindTitleRename();
				mpInstallPendingTrust();   /* 修复 51 兜底 */
				if (++mpBootBursts >= 10) window.clearInterval(mpBootBurstTimer);
			}, 120);
			// 审批徽章可能在侧栏抽屉渲染完成前不存在：轮询兜底（低频）
			const t = window.setInterval(watchApproval, 5000);
			/* ⚠️ `mpSignalUiReady()` 已经提到 `apply()` 开头了（见那里的一大段注释）：
			 * 它原来排在这里 —— `apply()` 的末尾，前面 30 多个初始化全无 try/catch，
			 * 任何一个抛异常都会让它永不执行，界面就永远卡在启动层。
			 * 现在它在最前面挂上守候，这里不再重复调用（函数内部有 reported 幂等保护，
			 * 重复调用本身不会出错，但没必要留两条路径去猜谁生效）。 */

			/* ── 哈希类名体检（只上报，不改变任何界面）────────────────────
			 *
			 * 本文件有 37 处规则挂在**官方构建期哈希类名**上（wSkVaW_、uV2eYG_、
			 * O_Ebla_、_1rdzk_ 这类）。它们是官方打包时按内容生成的，官方每次
			 * 升级都可能变；一旦变了，对应规则就**静默失效** ——
			 * 界面不会白屏、不会报错，只是「某个修复悄悄不见了」。
			 *
			 * 本项目已经为此吃过几次亏（修复 67/78/79 都留过「官方升级可能失效」
			 * 的警告，但**没人知道到底哪天失效了**）。所以这里在启动后扫一遍
			 * 关键选择器，把「命中 0 个」的打进 app.log（经 pageDiag 上原生日志，
			 * 手机上可读）。判据只有一条：**能不能查到元素**，不判断样式是否
			 * 真的生效（那需要 getComputedStyle 逐条比对，代价太大且易误报）。
			 *
			 * ⚠️ 延迟到界面稳定后再扫（mpSignalUiReady 之后仍有约 1.5s 的挂载
			 *    过程），否则会把「还没渲染」误报成「选择器失效」。
			 * ⚠️ 只扫**语义稳定**的几个锚点；不扫 `_1rdzk_*` 这种纯哈希类，
			 *    因为它们在多个组件间复用、命中数不代表任何单一结论。
			 * ⚠️ 全程 try/catch：体检本身绝不能影响插件激活。
			 */
			try {
				window.setTimeout(() => {
					try {
						const probes = [
							['wSkVaW_root', '会话根容器'],
							['uV2eYG_dock', '底栏'],
							['uV2eYG_card', '输入卡片'],
							['YDXeBa_sessionRow', '会话列表行'],
						];
						const missing = [];
						for (const [cls, label] of probes) {
							let n = 0;
							try {
								n = document.querySelectorAll('.' + cls).length;
							} catch (e) {
								n = -1;
							}
							if (n === 0) missing.push(label + '(' + cls + ')');
						}
						mpDiag(missing.length === 0
							? '选择器体检: 4 个关键锚点全部命中'
							: '选择器体检: ' + missing.length + ' 个未命中 → ' +
								missing.join('、') + ' ｜官方可能已改类名，相关 CSS 规则已静默失效');
					} catch (e) {
						mpDiag('选择器体检失败: ' + (e && e.message));
					}
				}, 4000);
			} catch (e) { /* 体检绝不能影响激活 */ }

			return () => {
				mo.disconnect();
				mpStatsObserver.disconnect();
				document.removeEventListener("click", onNewSessionClick, true);
				document.removeEventListener("click", onSessionRowClick, true);
				disposePressFeedback();
				disposeImePrejudge();
				window.clearInterval(t);
				window.clearInterval(mpBootBurstTimer);
				disposeKeyboardGuard();
				disposeTriggerMenuDismiss();
				disposeKeepFocusGuard();
				disposeEnterAsNewline();   /* 修复 67 */
				disposePlainKey();   /* 修复 68 */
				disposeCaretFix();   /* 修复 78 */
				document.removeEventListener("click", onPanelRowClick, true);   /* 修复 66 */
			};
		}

		exports.apply = apply;
		// 业务包独立声明依赖，布局不等待余额账号服务。
		exports.inject = [];
		return module.exports;
	}
});
