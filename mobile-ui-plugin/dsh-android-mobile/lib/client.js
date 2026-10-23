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
     例外：消息区域 .wSkVaW_viewArea 和输入元素允许原生长按行为。
     非消息区域（header/sidebar/settings等）保持 disabled，体验接近原生 app。 */
[data-mobile] * {
  -webkit-touch-callout: none !important;
  user-select: none !important;
  -webkit-user-select: none !important;
}
[data-mobile] .wSkVaW_viewArea,
[data-mobile] .wSkVaW_viewArea * {
  -webkit-touch-callout: default !important;
  user-select: text !important;
  -webkit-user-select: text !important;
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
[data-mobile] button:focus, [data-mobile] button:focus-visible, [data-mobile] a:focus,
[data-mobile] [tabindex]:focus, [data-mobile] input:focus,
[data-mobile] textarea:focus, [data-mobile] select:focus {
  outline: none !important;
  box-shadow: none !important;
}

/* 2. 消息操作栏：隐藏点赞/有问题反馈按钮（保留 复制/分支/时间/用时） */
[data-mobile] button[aria-label="好的回答"],
[data-mobile] button[aria-label="有问题的回答"] {
  display: none !important;
}

/* 1. 会话头部：标题长时右侧 actions/utilities 溢出重叠（undo 快照徽章 vs session log 按钮）→ 换行。
   容器是 wSkVaW_headerActions（模式徽章+快照徽章+utilities 并排超出容器宽，flex nowrap 导致重叠）。
   让 headerActions 在 titleCluster 内收缩、换行，避免溢出与 headerUtilities 重叠。 */
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
/* 官方 header 给汉堡让出左侧空间（标题不再被压住） */
[data-mobile] .wSkVaW_header {
  padding-left: 52px !important;
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

/* 8b. StatsPills（0.1.5 新版：两个可点药丸）在手机宽度下的收窄：
   官方 .bOPqQW_root 左右内边距 = calc(--dsh-composer-side-clearance + 16px) = 32px，
   390dp 下药丸实测 129 + 143 + gap 12 = 284px > 可用 236px → 尾部恒被截断。
   收窄内边距到 8px、gap 到 8px 后可用 284px，两枚药丸完整显示（保持单行）。

   ⭐ 修复 60（2026-09-28 啾灵反馈「底部长了会有省略号，比较难看」）：
   实测底部宽度预算（iPhone 13 / 390px）：
     root(BuPN2G_root) 内容区 356px
       └ dock 356px 上限，其中 遥测栏 + gap12 + 环形61
         └ 遥测栏最多 283px（= 356 − 12 − 61）
            └ 减 padding 12 + 药丸间 gap 6 + 两药丸固定开销 26*2=52
              ⇒ 文本可用 ~213px
   而原文案「24 轮 1085 步·172 t/s」+「92.9M·缓存 93%」实测 197px——
   **余量仅 ~16px**：步数从 4 位变 5 位（1085→10857）就要 +8px，再叠加
   轮数或 token 量增长必然溢出 → 尾部省略号；继续压缩时 label 的
   min-width:0 会把它压到 0 宽，视觉上就像整组「消失」。
   下面把各处 padding/gap 再收紧一档，文本可用宽提到 ~227px（+14px）。
   剩余差额由 JS 分级降档兜底（见 processStatsLine / mpFitStats）。 */
[data-mobile] .bOPqQW_root {
  padding-left: 2px !important;
  padding-right: 2px !important;
  gap: 4px !important;
}
[data-mobile] .bOPqQW_pill {
  padding-left: 3px !important;
  padding-right: 3px !important;
  gap: 3px !important;
}
/* 药丸文字必须能收缩：官方 .bOPqQW_label 是 overflow:hidden + ellipsis，
   但缺 min-width:0 → flex 项不肯收缩，超宽时文字直接被裁（尾部变省略号）。
   ⭐ 修复 60：给 min-width 一个下限（而非 0）。原来的 min-width:0 允许它被
   一路压到 0 宽 → 药丸只剩图标，视觉上就是用户反馈的「底部信息整组消失」。
   现在最多压到 3em，配合 JS 分级降档，永远不会出现 0 宽或省略号。 */
[data-mobile] .bOPqQW_label {
  min-width: 3em;
  overflow: hidden;
  text-overflow: ellipsis;
}
[data-mobile] .bOPqQW_anchor {
  min-width: 0;
}
/* ⚠️ 修复 60 注记：root **必须保持可收缩**（官方 flex 简写 0/1/auto）。
   不要改成 flex:0 0 auto —— 那样 root 会撑开 dock 横向溢出，且
   scrollWidth === clientWidth 恒成立，processStatsLine 里
   「内容宽 > 可视宽」的溢出检测将永远失效，分级降档也就永不触发。
   真正的防线是：① label 的 min-width:3em（防 0 宽「消失」）
   ② JS 分级降档（防省略号）。 */

/* ══ 修复 63（方案 A）：给「三组并排」腾出余量 ══════════════════════════
   文案侧已把 tok/s 与 token 总量移进弹层（见 mpStatsVariants 的注释），
   这里再把三组之间的横向开销各收一档，给 5~6 位步数留余量：
     · dock（遥测栏 ↔ 上下文环）gap 12 → 8
     · 上下文环触发器 padding 8→6、图标↔文字 gap 6→4
   ⚠️ .JObwrW_* 与 .uV2eYG_* 是官方构建期哈希类名（分别来自
      ui-conversation 的 ContextMeter / InputBar），官方升级可能变；
      失效的后果只是「回到官方间距」，不影响功能。 */
[data-mobile] .uV2eYG_dock {
  gap: 8px !important;
}
[data-mobile] .JObwrW_trigger {
  padding-left: 6px !important;
  padding-right: 6px !important;
  gap: 4px !important;
}

/* ══ 修复 65：底部遥测栏挤成两行 / 溢出视口被裁 ═══════════════════════
   症状（360px / dpr3 真机截图）：
     · 底部那行被折成两行：「1 轮 1 步 · 30 / tok/s」「30.5K tok · 缓存命 / 中 9%」，
       右侧上下文环「12%」孤零零挂在第二行右边；
     · 更早的版本里它直接溢出视口被裁：dock scrollWidth 382 vs clientWidth 326，
       左边「1 轮」的「1」被切掉、右边上下文环整个跑到 x=343（视口只有 360）之外。

   根因有两层：
     ① **CSS 层**：官方这几个药丸是 "white-space: normal"，宽度不够就换行，
        换行之后 "dock" 高度从 26px 涨到 46px，整块往上顶、把输入框挤走；
     ② **JS 层**（更隐蔽）：本文件里那套「文案放不下就自动降档」的机制
        （"processStatsLine" / "mpStatsFits"）**从上线起就没生效过** ——
        它找的是 "[data-composer-stats] .bOPqQW_root" 和 ".bOPqQW_label"，
        而官方现在的结构是 ".uV2eYG_dock > span[data-composer-stat]"，
        三个旧选择器**全部查不到**（真机实测：root null、label 0 个）。
        所以降档一次都没跑过，长文案原样铺在屏幕上。

   修法两处配合（缺一个都不行）：
     · CSS：药丸 "nowrap" + "overflow: hidden"，先保证**绝不换行**
       （换行是视觉上最糟的形态，宁可截断也不换行）；
     · JS："processStatsLine" 改按 "data-composer-stat" 属性找元素（见那边的注释），
       "mpStatsFits" 改成「截断/超父容器」判据 —— 这样降档才会真正触发，
       把文案缩到「1轮 1步」「30.5K·缓存9%」这种一行放得下的基准档。
   实测最终形态：dock 高 26px、两个药丸 55px/103px 均零截断、上下文环在同一行。

   ⚠️ "uV2eYG_*" / "JObwrW_*" 是官方构建期哈希类名，官方升级可能变；
      "data-composer-stat" 是语义属性，比哈希稳，所以 JS 侧优先用它。 */
[data-mobile] .uV2eYG_dock [data-composer-stat] {
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
}
[data-mobile] .uV2eYG_dock {
  flex-wrap: nowrap !important;
}

/* ══ 修复 67：思考正文不换行，把整页推出屏幕左侧 ═══════════════════════
   症状（真机截图，非常明显）：
     整个消息列表**向左偏出屏幕**，左侧文字被切一半
     （「已完成」只看得见「已完成」的右半、「目前没有」只剩「前没有」、
      消息操作图标只剩右半边），右侧反而留出一片空白。
     而且页面能被**横向拖动**，拖一下就左右跑。

   根因：思考（reasoning）正文的 span 带了 "white-space: nowrap"，
   一行到底不换行 —— 实测最长的一条 **1197px**（视口只有 360px）。
   它把滚动容器 ".wSkVaW_root" 的 scrollWidth 从 360 撑到 **550**，
   浏览器为了显示光标位置把 scrollLeft 自动设成 **39.67**，
   于是整页看起来「左移了 40px」。实测：
     · "_text_1rdzk_17" 的 getBoundingClientRect().width = 1197（多条 700~1200）
     · ".wSkVaW_root" scrollWidth 550 / clientWidth 360 / scrollLeft 39.67
     · ".wSkVaW_scrollBody" 的 x = **-40**（负值 = 已跑出屏幕左边界）

   修法：两处一起改，缺一个都不行 ——
     ① "._text_1rdzk_17" 恢复换行（"white-space: normal" + "overflow-wrap: anywhere"
        兜住那种一整串没有空格的英文/路径，否则单行还是会顶出去）；
     ② "._root_1rdzk_1" 官方写的是 "width: max-content"，它在 flex 里会按
        **内容原始宽度**撑开（哪怕子项已经允许换行），改成 "width: auto" 才会
        服从父级的 100% 约束。
   实测修复后：最长文本 1197 → 225px，scrollLeft 归 0，scrollBody 的 x 回到 0，
   页面不再能横向拖动。

   ⚠️ 这两个都是官方构建期哈希类名，官方升级可能变；失效后果只是
      "回到能横向拖动"，不影响功能。 */
[data-mobile] ._root_1rdzk_1 {
  width: auto !important;
  max-width: 100% !important;
}
[data-mobile] ._text_1rdzk_17 {
  white-space: normal !important;
  word-break: break-word !important;
  overflow-wrap: anywhere !important;
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

/* ══ 修复 64：模型名在窄屏下被截断（"deepseek-v4.1..." 看不全）═════════
   症状（360px 宽、dpr 3 真机截图核对）：
     · 底栏模型按钮里的模型名被 ellipsis 截成 "deepseek-v4.1..."；
     · 紧随其后的推理等级（Default）**宽度直接是 0**，完全看不见。

   根因不是"文字太长"，而是**这一行有一块 41px 的空地被浪费了**：
   官方 uV2eYG_row 用的是 "justify-content: space-between"，子项只有
   uV2eYG_tools（80px，可收缩）和 uV2eYG_trailing（190px，
   "flex: 0 0 auto" 不收缩）。"space-between" 把剩余空间全推到了两者中间
   —— 实测 tools 结束于 x=104、trailing 从 x=145 才开始，**中间空 41px**，
   而右边模型名正因为缺 16px 在截断。把空隙让给 trailing 即可。

   修法（逐层放宽，缺一层都撑不开 —— 实测每层都被上一层的
   "flex: 0 0 auto" / "display: block" 卡住）：
     row            space-between → flex-start（不再把空隙推到中间）
     trailing       0 0 auto → 1 1 0%
     standardControls / _7KE1Ra_root   0 1 auto → 1 1 0%
     _7KE1Ra_trigger  max-width: 139.5px → none（官方在这里写死了上限）
   腾出宽度后模型名完整显示（实测 144px = 内容 144px，零截断）。

   ## 推理等级（Default / High）为什么不藏
   一开始为了让模型名完整，把推理等级 display:none 掉了，理由是"它是默认值"。
   **那是错的**：真机 aria-label 显示实际值是 High（用户设过），藏起来就是丢信息。
   正确做法是腾宽度而不是砍信息 —— 腾出 41px 后模型名与 High 能同时放下
   （实测模型名 122px + High 28px + 内边距/箭头 = 168px，按钮正好 168px）。

   ⚠️ 两个都是官方构建期哈希类名，官方升级可能变；失效后果只是
      "回到截断状态"，不影响功能。 */
[data-mobile] .uV2eYG_row {
  justify-content: flex-start !important;
  /* 修复 69：官方 row gap 是 12px，tools 只占 80px，中间那 12px 是纯浪费。
     缩到 8px 换回 4px 给模型名 —— 正好够把「默认」和模型名拉开到 3px 间距
     而不再截断模型名。实测：gap 12→8 后按钮 163→167px，
     模型名 119→123px（= 内容宽度，零截断），推理等级间距 3px。
     为什么非要这 4px：见下面 _7KE1Ra_trigger 的 gap 注释。 */
  gap: 8px !important;
}
[data-mobile] .uV2eYG_tools {
  flex: 0 0 auto !important;
}
[data-mobile] .uV2eYG_trailing {
  flex: 1 1 0% !important;
  min-width: 0 !important;
}
[data-mobile] .uV2eYG_standardControls {
  flex: 1 1 0% !important;
  min-width: 0 !important;
}
[data-mobile] ._7KE1Ra_root {
  flex: 1 1 0% !important;
  min-width: 0 !important;
  width: 100% !important;
}
[data-mobile] ._7KE1Ra_trigger {
  padding-left: 4px !important;
  padding-right: 0 !important;
  /* 修复 69：这里原来是 1px（v89 为挤宽度而压到极限）。
     但 v91 把推理等级改成了中文（Default → 默认），中文字形没有英文那种
     右侧字距留白，1px 看起来就是「deepseek-v4.1-flash默认」**粘连**。
     实测各值（配合上面 row gap:8px）：
       gap 1px → 模型名 123px 完整，但视觉粘连
       gap 2px → 模型名 121px（122 需求，临界）
       gap 3px → 模型名 119px（**截断**）  ← 只有 row gap 仍是 12 时才会这样
     把 row gap 从 12 缩到 8 腾出 4px 后，gap 3px 也能保持模型名 123px 完整，
     所以最终取 3px —— 既不粘连，也不截断。 */
  gap: 3px !important;
  min-width: 0 !important;
  width: 100% !important;
  max-width: none !important;
  justify-content: flex-start !important;
}
[data-mobile] ._7KE1Ra_triggerLabel {
  min-width: 0 !important;
  /* ══ 修复 70：模型名与推理等级之间出现"幽灵间距" ══════════════════════
     现象（真机截图，一眼就能看出来）：换到短模型名 agnes-3.0-flash 时，
     「agnes-3.0-flash    默认」中间空出一大块，而 deepseek-v4.1-flash 却正常。

     根因：官方给这个 label 设了 **text-align: center**，而 v89 起我把它写成
     **flex: 1 1 auto** ⇒ 盒子会撑满剩余空间，文字在盒子里居中，
     两边各留一半空白。实测（按钮 172px）：
       agnes-3.0-flash  盒子 128px / 文字只有 **94px** ⇒ 真实间隙 **20px**
       deepseek-v4.1-flash 盒子 122px / 文字 122px ⇒ 恰好贴满，看不出问题
     所以这个 bug 只在**较短的模型名**下暴露 —— deepseek 时一直没发现。

     ⚠️ 别改成 text-align: left 就完事：实测那只是把空白全推到右边，
        真实间隙反而变成 **37px**（更难看）。问题在盒子太宽，不在对齐。
     正解是让盒子**按内容收缩**：flex 从 1 1 auto 改成 **0 1 auto**。
       · 0 = 不主动撑满（盒子=文字宽度）
       · 1 = 仍可被压缩（超长模型名照样出省略号，不撑破布局）
     实测三种情况都正确：
       agnes-3.0-flash       盒子 94 = 文字 94，间隙 3px，不截断
       deepseek-v4.1-flash   盒子 122 = 文字 122，间隙 3px，不截断
       a-very-long-model-…   盒子 128，文字 287 ⇒ 截断出省略号（布局不破）
     text-align: left 保留：盒子已等于内容宽度，对齐方式不再影响布局，
     但设成 left 能让「文字比盒子窄」的极端情况也贴着左边显示。 */
  flex: 0 1 auto !important;
  text-align: left !important;
}
/* ⚠️⚠️ 推理等级**必须显示**（修复 66）——上一版把它藏掉了，那是错的。
   当时理由是「它恒为 Default，默认值藏掉不丢信息」。真机核对 aria-label
   才发现实际值是 **High**：
     选择模型，当前 deepseek-v4.1-flash，推理等级 High
   也就是说**用户设过的非默认推理等级被藏起来了**，实打实的信息丢失。
   字号 10px + opacity 0.65：它是次级信息，不该和模型名抢视觉重心。

   ⚠️ 修复 68：**光靠 CSS 不够，还要缩文案**。
   修复 66 只保证了「High 能放下」（22px），但真机打开一个老会话时
   推理等级是 **Default（33px）**，模型名又被挤成 "deepseek-v4.1-fl..."。
   逐值实测（按钮固定 168px）：
     Default 33px → 模型名 115px（截断，需要 122px）
     Medium  37px → 模型名 111px（截断）
     Minimal 36px → 模型名 112px（截断）
     High/Low/Max 19~22px → 模型名 126~129px（放得下）
   所以只在 CSS 层调 flex 治不了根：**必须让文案本身变短**。
   做法是 JS 里按表把英文等级缩成中文（"mpAbbreviateEffort"，见文件后半），
   实测缩短后全部放得下：
     默认 20px / 中 10px / 最低 20px / 低 10px / 高 10px / 最高 20px / 超高 20px
   → 模型名一律 128~138px，**任何等级都不再截断**。
   为什么敢改文案：它是纯装饰性次级标签，"aria-label" 与 "title" 仍保留
   完整英文原文（"选择模型，当前 …, 推理等级 Default"），可访问性不受影响。 */
[data-mobile] ._7KE1Ra_triggerEffort {
  flex: 0 0 auto !important;
  font-size: 10px !important;
  /* ══ 修复 74：推理等级「默认」对比度不达标，两种主题下都看不清 ════════
     现象：底栏「deepseek-v4.1-flash 默认」里那个「默认」发灰，
     亮色/暗色下都要凑近才看得清。

     实测（WCAG 对比度，AA 正文要求 4.5:1）：
       亮色：文字 rgb(129,133,140) × opacity 0.65 叠在白底上 → **2.18:1**
       暗色：同色 × 0.65 叠在 rgb(44,44,46) 上        → **2.42:1**
     两边都远低于 4.5，不是"稍微浅一点"，是真的读不清。

     ⚠️ 根因是修复 66 里我加的 **opacity: 0.65** —— 当时的理由是
        "它是次级信息，不该和模型名抢视觉重心"。方向没错，但 0.65 太狠：
        实测把 opacity 拉到 1 也只有 3.71~3.76:1，**光调透明度永远不够**，
        因为颜色本身 rgb(129,133,140) 就偏浅。必须同时改颜色。

     ⚠️ 也不能只改一个颜色：亮色底(白)要**深**色才够对比，暗色底(rgb(44,44,46))
        要**浅**色。实测同一个颜色不可能两边都达标：
          提亮到 rgb(173,178,184)：暗色 6.53 ✅ / 亮色 2.13 ❌
          压深到 rgb(97,102,107)： 亮色 5.80 ✅ / 暗色 2.40 ❌
        ⇒ 必须写**两套**，靠 body[data-ds-dark-theme] 切换。

     最终取值（opacity 一律 1）：
       亮色 rgb(97,102,107)  → **5.80:1** ✅
       暗色 rgb(173,178,184) → **6.53:1** ✅
     两者都过 AA。仍然是 10px 小字、仍然排在模型名之后，
     视觉层级靠字号与位置区分，不靠把它调淡到看不清。 */
  opacity: 1 !important;
  color: rgb(97, 102, 107) !important;
  white-space: nowrap !important;
}
[data-mobile] body[data-ds-dark-theme] ._7KE1Ra_triggerEffort,
[data-mobile] [data-ds-dark-theme] ._7KE1Ra_triggerEffort {
  color: rgb(173, 178, 184) !important;
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

		// ── 3. composer StatsPills 精简 ──
		// 官方 .bOPqQW_root 左右内边距 = calc(--dsh-composer-side-clearance + 16px) = 32px，
		// 390dp 下可用宽仅 270px，而两枚药丸实测 129 + 143 + gap 12 = 284px → 尾部恒被截断
		// （用户看到的是「3 轮 22 …」）。
		//
		// ⭐ 修复 60（2026-09-28 啾灵反馈「底部长了会有省略号，比较难看」+「有时整组消失」）：
		// 实测底部宽度预算（iPhone 13 / 390px）：
		//     dock 上限 356px = 遥测栏 + gap12 + 环形61  ⇒ 遥测栏最多 283px
		//     减 root padding 4 + 药丸间 gap 4 + 两药丸固定开销 (icon14+gap3+pad6)*2=46
		//     ⇒ 文本预算 229px，而原文案「24 轮 1085 步·172 t/s」+「92.9M·缓存 93%」实测 197px
		//     ⇒ **余量仅 ~16px**。步数 4 位→5 位要 +7px，轮数/token 再长就溢出 → 尾部省略号。
		//
		// 为什么会出现「整组消失」：.bOPqQW_label 带 min-width:0（修复早期为解决
		//   flex 不肯收缩而加），溢出时它会被一路压到 0 宽 → 药丸只剩图标，
		//   视觉上就是「轮/步、缓存两组信息消失了」。
		//
		// 修法：不做"压缩到溢出"，改为**分级降档**——按信息重要性逐级舍弃，
		//   每级都实测真实宽度，选第一个放得下的，因此**永不产生省略号**。
		//   信息优先级（啾灵认可的顺序）：
		//     ① 轮/步计数  —— 进度核心，任何档位都保留
		//     ② 缓存命中率 % —— 第二重要，最后档位也保留
		//     ③ token 总量（92.9M）—— 中优先级，挤不下先舍
		//     ④ 速度 t/s   —— 实时性弱、变化最频繁，**最先**舍弃
		// 幂等：降档结果写回 dataset.mpApplied，下一轮若文本未变则不重复测量；
		//   React 写入新数值时（now !== mpApplied）才重新计算并更新原文缓存。
		const MP_STATS_RAW = "mpRaw", MP_STATS_APPLIED = "mpApplied";

		/* ══ 修复 63：底栏常驻文案精简（方案 A + 用户追加「用量」）══════════════

		   常驻可见 = 轮/步（进度）· token 总量·缓存命中%（用量/成本效率）·
		   上下文环%（会不会爆，环由 conversation 插件的 ContextMeter 渲染）。
		   常驻里**去掉**的只有 tok/s。

		   为什么（预算账）：官方「详细」档这一行是三组并排
		     ⏱「24 轮 1085 步 · 172 tok/s」 🗄「92.9M tok · 缓存命中 93%」 ◔「63%」
		   需求宽 ~285px，而 390px 屏的 dock 预算 ~283px —— **天生差一点**，
		   所以永远在省略号边缘蹭，数字一进位就变省略号。精简后实测：
		     ⏱「24轮 1085步」+ 🗄「92.9M·缓存93%」
		     = 药丸 93 + 4(药丸间 gap) + 109 ≈ 216px（含图标/padding/内 gap），
		     对 240px 的保守预算余量 ~24px（真实 dock 预算更宽松，约 298px）。
		   于是按用户要求把 token 总量放回来仍然放得下；仍是 tok/s 留在弹层
		   （瞬时值、每秒都在跳，信息半衰期最短，且它是最长的一段）。
		   ⭐ 关键前提：三组**都能点开**，弹层里一个数据都没少
		     （时间药丸 → LLM/工具时长、首 token、速度；用量药丸 → 缓存命中率、
		      输入/缓存读/缓存写/输出 tok；上下文环 → ~已用/窗口 + 分段明细）。

		   下面第 0 档（基准档）就是固定精简文案，其余档位是极端数字
		   （1085→108570 步、总量涨到 B 级之类）时的兜底降档。 */
		function mpStatsVariants(raw) {
			const abbr = (s) => s.replace(/缓存命中/g, "缓存").replace(/(\S)\s*tok\/s/g, "$1 t/s");
			const src = abbr(raw);
			const v = [];
			if (/轮|步|turns|steps/.test(src)) {
				// 进度药丸：基准档直接就是**紧排**的「24轮 1085步」
				// （官方原文「24 轮 1085 步」在数字与单位间有空格，白占约 8px，
				//   而这次改动的目的就是省宽度 → 不把它当基准档）。
				// 其后依次：去掉中间空格 → 只留步数 → 只留数字。
				const tight = src.replace(/\s*·\s*[^·]*$/, "").trim()
					.replace(/(\d)\s+(轮|步|turns|steps)/g, "$1$2");
				v.push(tight);
				v.push(tight.replace(/\s+/g, ""));
				const stepsOnly = tight.replace(/^.*?(\d+)\s*(步|steps)\s*$/, "$1$2");
				v.push(stepsOnly);
				if (stepsOnly !== tight) v.push(stepsOnly.replace(/\s*(步|steps)\s*$/, ""));
			} else {
				// 用量药丸：基准档 = 官方同款语义「总量·缓存N%」（用户 2026-10-03
				//   看过实测余量后要求把「用量」放回来）。
				//   总量与缓存命中%本来就是官方的**同一枚药丸**（UsagePill），
				//   合在一枚里比拆成两枚省一个图标+间距+间隙（约 31px），
				//   所以放回总量不必新增药丸。
				//   降档顺序：舍总量 → 舍「缓存」二字只留百分比。
				const m = /·\s*([^·]+)$/.exec(src);
				if (m) {
					// "92.9M tok" → "92.9M"（图标已表达"token"，单位白占宽）
					const total = src.slice(0, m.index).trim().replace(/\s*tok\b/g, "").trim();
					const cache = m[1].trim().replace(/\s+/g, "");
					if (total) v.push(total + "·" + cache);
					v.push(cache);
					const pct = (cache.match(/\d+(?:\.\d+)?%/) || [""])[0];
					if (pct) v.push(pct);
				} else {
					v.push(src.replace(/\s*tok\b/g, "").trim() || src);
				}
			}
			// 去掉空串与重复，保持顺序（单调递减，降档必然终止）
			return v.filter((x, i) => x && v.indexOf(x) === i);
		}

		/* ── 修复 68：推理等级文案缩写（防止它把模型名挤掉）───────────────
		   背景见上面 "_7KE1Ra_triggerEffort" 那条 CSS 注释：底栏模型按钮
		   固定 168px，模型名要 122px，留给推理等级的空间只有 ~26px。
		   逐值实测英文等级占宽：Default 33 / Medium 37 / Minimal 36 /
		   High 22 / Low 19 / Max 19 —— 前三个都会把模型名挤成省略号。

		   所以按表缩成中文（每个都 ≤20px，实测全部放得下）。
		   ⚠️ 只改**显示文本**，不动 "aria-label" / "title"：
		      可访问性与「点开菜单看到的完整选项」都保留英文原文。
		   ⚠️ 表里没有的值原样返回 —— 官方将来加了新等级时，
		      最坏结果只是"那一档可能挤一下"，不会显示错。 */
		const MP_EFFORT_ABBR = {
			Default: "默认",
			Medium: "中",
			Minimal: "最低",
			Low: "低",
			High: "高",
			Max: "最高",
			XHigh: "超高"
		};

		/**
		 * 把底栏的推理等级缩成短中文。幂等：已经是缩写值就直接返回，
		 * 所以放进 300ms 的 observer 批处理里不会反复写 DOM。
		 *
		 * ⚠️ 必须**先读原文再判断**：React 每次重渲染都会把文本写回英文，
		 *    这里再缩一次 —— 和 processStatsLine 的 MP_STATS_RAW 是同一套思路。
		 */
		function mpAbbreviateEffort() {
			if (!mpIsMobile()) return;
			const els = document.querySelectorAll("._7KE1Ra_triggerEffort");
			for (const el of els) {
				const cur = (el.textContent || "").trim();
				const short = MP_EFFORT_ABBR[cur];
				if (short === undefined) continue;      // 已是缩写 / 未知值 → 不写
				// 把完整英文留在 title 上，长按/无障碍仍能读到原文
				if (!el.getAttribute("title")) el.setAttribute("title", cur);
				el.textContent = short;
			}
		}

		/**
		 * 判断底部遥测栏当前文案「是否排得下一行」。
		 *
		 * ⚠️⚠️ **修复 65：旧判据在官方换 DOM 之后恒为 true，降档从未触发。**
		 *
		 * 旧实现："root.scrollWidth > root.clientWidth"。
		 * 但现在的根节点 ".uV2eYG_dock" 是 "flex" + **宽度随内容收缩**的
		 * （内容短时 229px、长时 326px），"scrollWidth" 与 "clientWidth"
		 * **永远相等** ⇒ 判据恒 false ⇒ 一档都不降。
		 *
		 * 真机实测的后果（截图核对）：底部那行挤成**两行**
		 *（「1 轮 1 步 · 30 / tok/s」「30.5K tok · 缓存命 / 中 9%」），
		 * 右侧上下文环 "12%" 孤零零挂在第二行右边；更早的版本里
		 * 它直接超出视口被裁掉（dock scrollWidth 382 vs clientWidth 326）。
		 *
		 * 新判据（两条，任一不满足就算「放不下」）：
		 *  ① **是否换行** —— 用 "getClientRects().length > 1"，这是最直接的
		 *     "文字被折成多行"信号，与容器宽度怎么算无关；
		 *  ② **是否超出父容器** —— 内容右边界比父容器右边界还远。
		 *     兜住 "justify-content: center" 那种「两边一起溢出」的情况
		 *    （单看 ① 时若容器把文字压在一行却整体溢出，① 会漏判）。
		 *
		 * 保持**纯只读**：不写 DOM ⇒ 不触发 MutationObserver ⇒ 不会重演
		 * 修复 60b 那个把页面卡死的死循环。
		 */
		function mpStatsFits(root, labels) {
			// ① 任一药丸被折成多行 ⇒ 放不下
			for (const el of labels) {
				if (el.getClientRects().length > 1) return false;
				// 药丸内部自己截断（ellipsis）也算放不下
				if (el.scrollWidth > el.clientWidth + 1) return false;
			}
			// ② 整体超出父容器（两侧都算）
			const parent = root.parentElement;
			if (parent !== null) {
				const rootRect = root.getBoundingClientRect();
				const parentRect = parent.getBoundingClientRect();
				if (rootRect.right > parentRect.right + 1) return false;
				if (rootRect.left < parentRect.left - 1) return false;
			}
			return true;
		}

		/* ⚠️⚠️ 修复 60b（2026-09-28）——上一版这里是**死循环**，把整个页面卡死了，
		   表现是「什么按钮都点不了、输入框也点不了」，远程访问时尤其明显。

		   错在哪（务必不要再写回来）：
		     processStatsLine 被 MutationObserver 的 characterData 分支调用
		     （见文件末尾 mo.observe(document.body, {childList,subtree,characterData})）。
		     上一版每次都【从第 0 档（最长文案）重新开始试】：
		         写第 0 档 → 溢出 → 写第 1 档 → 溢出 → 写第 2 档 → 放得下
		     净结果虽然回到第 2 档，但**每次调用都写了 3 次 textContent** ⇒
		     每次写入又触发一次 observer ⇒ queueMicrotask 再调回来 ⇒
		     微任务级无限循环，主线程被 100% 占满，页面彻底无响应。

		   正确做法（现在这版）必须满足两条不变量：
		     ① **不溢出就一个 DOM 都不写，直接 return** —— 无写入 ⇒ 不触发 observer
		        ⇒ 环被切断。这是最关键的一条。
		     ② **只能单调降档，绝不回写更长的档** —— 候选档位有限（≤4 档），
		        每次最多降一级，降到最后一档就 next === cur 不再写入 ⇒ 必然终止。

		   信息优先级（修复 63 / 方案 A 之后的最终形态）：
		     常驻 = 轮/步 · 缓存命中% · 上下文环%；tok/s 与 token 总量**从基准档
		     就已移入弹层**（点药丸即得），不再参与常驻竞争。
		     这里的档位只剩「极端数字兜底」：轮→步→纯数字、缓存标签→纯百分比。 */
		function processStatsLine() {
			if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
			// ⚠️⚠️ **修复 65：官方换过 DOM 结构，旧选择器已全部失配。**
			//
			// 旧版（本函数原先写的）：
			//   root   = `[data-composer-stats] .bOPqQW_root` / `.bOPqQW_root`
			//   labels = `.bOPqQW_label`
			// 真机实测（2026-10-21）**三个选择器全部查不到**：
			//   `[data-composer-stats]` → null
			//   `.bOPqQW_root`          → null
			//   `.bOPqQW_label`         → 0 个
			// 也就是说**降档逻辑从上线起就没生效过**（不是"偶发失效"），
			// 症状就是底部遥测栏超出视口被裁：实测 dock clientWidth 326、
			// scrollWidth 382（溢出 56px），左边「1 轮」的「1」被切掉、
			// 右边上下文环「12%」整个跑到 x=343（视口只有 360）之外。
			//
			// 新版结构（实测）：
			//   `.uV2eYG_dock`（flex，justify-content:center，宽 326）
			//     ├─ span[data-composer-stat="activity"] > button.bOPqQW_pill
			//     └─ span[data-composer-stat="usage"]    > button.bOPqQW_pill
			//   ⭐ 药丸类名 `bOPqQW_*` 还在，只是**根节点**从 `bOPqQW_root`
			//      变成了 `uV2eYG_dock`，且不再有 `data-composer-stats` 包裹层；
			//      文案也不再是 `.bOPqQW_label`，而是药丸自身的 textContent。
			//
			// 所以这里改成**按 `data-composer-stat` 属性找**（语义属性比哈希类名稳，
			// 官方改样式不会动它），并保留旧选择器作兜底 —— 万一哪天又切回去。
			const root =
				document.querySelector(".uV2eYG_dock") ||
				document.querySelector("[data-composer-stats] .bOPqQW_root") ||
				document.querySelector(".bOPqQW_root");
			if (!root) return;
			// 先按语义属性找；找不到再退回旧的 label 选择器。
			let labels = [...root.querySelectorAll("[data-composer-stat]")];
			if (!labels.length) labels = [...root.querySelectorAll(".bOPqQW_label")];
			if (!labels.length) return;

			// ① 原文快照：只有「当前文本不是我们上次写的那一份」时才认为 React 更新了原文。
			//    必须带 MP_STATS_APPLIED 判断，否则我们写下的缩短文案会被误当成新原文，
			//    原文越缩越短、再也回不去。
			for (const el of labels) {
				const now = el.textContent || "";
				if (now !== el.dataset[MP_STATS_APPLIED]) el.dataset[MP_STATS_RAW] = now;
			}

			// ② 档位表（按 mpStatsRaw 生成，一药丸一组，单调递减）
			const variants = labels.map((el) => mpStatsVariants(el.dataset[MP_STATS_RAW] || el.textContent || ""));
			// 放得下？（root 内容溢出 + 每个 label 的内部截断都要看：
			// label 带 ellipsis 时 root 未必溢出，但 label.scrollWidth 会暴露截断）
			const fits = mpStatsFits(root, labels);

			// ③ 双不变量（修复 60b 的死循环教训必须继续成立）：
			//    ① **不写 DOM 的路径必须存在且最先命中** —— 当前文案已在候选表里
			//       且放得下 ⇒ 一个字节都不写 ⇒ 不触发 observer ⇒ 环被切断；
			//    ② **只能单调降档**，每次最多降一级 ⇒ 必然终止。
			//
			//    ⭐ 修复 63 的实质改动：官方原文（idx < 0）**不再看放不放得下**，
			//       一律先归一到基准档 v[0]（= 精简常驻文案：轮/步 · 缓存N%）。
			//       因为方案 A 是「常驻就精简」，不是「挤不下才精简」——
			//       否则数字短的时候 tok/s 和 token 总量又会回到屏幕上。
			//       归一后下一轮 idx ≥ 0 且放得下 ⇒ 走不变量①，不再写入。
			//       已在候选表里（idx ≥ 0）才允许、且只在溢出时降一级。
			labels.forEach((el, i) => {
				const v = variants[i];
				const cur = el.textContent || "";
				const idx = v.indexOf(cur);
				let next;
				if (idx < 0) next = v[0];               // 官方原文 → 归一为精简基准档
				else if (fits) return;                  // 已是候选档且放得下 → 不写（断环）
				else next = v[Math.min(idx + 1, v.length - 1)]; // 溢出 → 降一档
				if (next === cur) return;               // 无变化 ⇒ 不写 ⇒ 不触发 observer
				el.textContent = next;
				el.dataset[MP_STATS_APPLIED] = next;
			});
		}

		// ── 6. 侧栏点「新会话」或「会话条」后自动收回抽屉 ──
		function closeDrawerAfterClick() {
			setTimeout(() => { mpCloseDrawer(); }, 150);
		}
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
			if (dialog.dataset.mpReady) return;
			dialog.dataset.mpReady = "1";
			const nav = Array.from(dialog.children).find((c) => c.tagName === "NAV");
			if (!nav) return;
			dialog.dataset.mpSettings = "1";
			// 隐藏安卓壳遗留的"开发者选项"：其功能依赖 window.androidBridge /
			// /api/android/*，在 Web 进程中不存在，保留会进入一个空壳页面。
			nav.querySelectorAll("button").forEach((b) => {
				if ((b.innerText || "").trim() === "开发者选项") b.style.display = "none";
			});
			const content = nav.nextElementSibling;
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
			const navTitle = nav.querySelector(".VOzbGW_navTitle");
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
			/**
			 * 布局判据 —— **唯一来源是原生的 LayoutAdapter**。
			 *
			 * ## 为什么改成读原生标志（设计说明 §4.9）
			 *
			 * 文档要求「**LayoutAdapter 唯一决定实际布局**」，并明确否定
			 * 了三种旧做法：
			 *
			 * | 旧做法 | 文档要求 | 后果 |
			 * |---|---|---|
			 * | 宽度阈值 1024 | **宽高比 r** | 分屏变窄但仍是宽屏比例，误判 |
			 * | 无滞回 | **3/4~4/3 保留上次** | 阈值附近抖动导致布局横跳 |
			 * | 看指针类型 | **不看鼠标类型** | 平板接鼠标突然变桌面布局 |
			 *
			 * 所以现在的顺序是：
			 *   ① 读 `window.__DSH_LAYOUT__`（原生算好的）
			 *   ② 没有 → 退回旧的宽度判据（**降级路径**，见下）
			 *
			 * ## ⚠️ 为什么保留旧的宽度判据作为降级
			 *
			 * 两个必须保留它的场景：
			 * - **桌面浏览器**（用户从电脑访问）：没有原生桥，拿不到标志
			 * - **旧版本 APK**：原生侧还没推送标志
			 *
			 * 降级路径用**旧的**判据（宽度 + 指针），而不是「默认 mobile」——
			 * 后者会让桌面浏览器被套上手机 UI，那是明确的回归。
			 */
			function mpLayoutFlag() {
				try {
					var v = window.__DSH_LAYOUT__;
					if (v === "mobile" || v === "official") return v;
				} catch (e) { /* 忽略 */ }
				return null;
			}

			/**
			 * 从原生拉一次布局（页面初始化时调）。
			 *
			 * ⚠️ **必须有这一步**：原生可能在页面加载**之前**就算好布局并推送过，
			 *   那次推送发生在还没有监听器的时候，等于丢了。
			 *   不主动拉的话，前端会一直走降级判据（宽度+指针），
			 *   而那个判据在分屏/平板上是错的。
			 *
			 * ⚠️ 失败时**静默**：桌面浏览器没有桥，那是正常情况。
			 */
			function mpPullLayoutFromNative() {
				try {
					var b = typeof window !== "undefined" ? window.DSHAndroid : null;
					if (!b || typeof b.layoutState !== "function") return;
					var raw = b.layoutState();
					var o = JSON.parse(raw || "{}");
					if (o.layout === "mobile" || o.layout === "official") {
						window.__DSH_LAYOUT__ = o.layout;
					}
				} catch (e) { /* 桌面浏览器 / 旧版 APK，静默 */ }
			}

			function mpIsMobile() {
				// ① 优先用原生 LayoutAdapter 的结论
				var flag = mpLayoutFlag();
				if (flag !== null) return flag === "mobile";

				// ② 降级：旧判据（桌面浏览器 / 旧版 APK）
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
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onUiReady !== "function") return;   // 桌面端 / 桥不可用

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

			const ready = () =>
				document.documentElement.hasAttribute("data-mobile") &&
				document.querySelector(".mp-nav-btn") !== null &&
				pageLoaded() &&
				!officialBootVisible();

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
			// 官方开合状态的唯一事实源：.hHd-Xa_root 是否带 hHd-Xa_collapsed
			function mpOfficialDrawerOpen() {
				const col = mpSidebarCol();
				const root = col ? col.querySelector(".hHd-Xa_root") : null;
				return !!root && !root.classList.contains("hHd-Xa_collapsed");
			}
			function mpMask() {
				let mask = document.querySelector(".mp-drawer-mask");
				if (!mask) {
					mask = document.createElement("div");
					mask.className = "mp-drawer-mask";
					mask.addEventListener("click", mpCloseDrawer);
					document.body.appendChild(mask);
				}
				return mask;
			}
			function mpSetDrawerOpen(open) {
				// ⚠️ 修复 39（2026-09-16 啾灵反馈）：抽屉/遮罩机制**只在窄屏下成立**。
				// 宽屏下官方侧栏常驻展开，mpOfficialDrawerOpen() 恒为 true → 这里会给遮罩
				// 加上 .mp-drawer-open（opacity:1 + pointer-events:auto + inset:0 z=39），
				// 而抽屉的浮层 CSS 只作用于 [data-mobile] → 遮罩压在正常布局的侧栏与整页之上：
				// 整页变暗、点「设置」点到的是遮罩、点遮罩又触发 mpCloseDrawer 把侧栏收起。
				// 这里做**唯一收口**：非窄屏一律强制关闭（与窄屏判定相与）。
				const on = mpIsMobile() && !!open;
				const col = mpSidebarCol();
				if (col) col.classList.toggle("mp-drawer-open", on);
				// 遮罩是懒建的：宽屏下若它还不存在就不必创建（少一个节点少一份风险）；
				// 若已存在（从窄屏切过来），下面这行负责把它的打开类摘掉。
				if (on || document.querySelector(".mp-drawer-mask")) {
					mpMask().classList.toggle("mp-drawer-open", on);
				}
			}
			function mpSyncDrawer() {
				// 修复 25：关闭动画进行中，绝不能被 observer/resize 把抽屉又拉回来
				//（官方此刻仍是 expanded，mpSetDrawerOpen 会重新加上 mp-drawer-open）。
				if (mpClosing) return;
				// ⚠️ 修复 39：宽屏下抽屉机制整体不适用（浮层 CSS 挂在 [data-mobile] 下），
				// 直接收工并确保遮罩是关闭态——绝不能让遮罩参与宽屏布局。
				if (!mpIsMobile()) { mpSetDrawerOpen(false); return; }
				// 模态弹窗打开时：只做**视觉收起**（抽屉滑出屏幕），不点官方 toggle。
				// （修复 26 教训：官方收起会触发 railIn → footArea opacity:0 → 连坐弹窗。）
				// 弹窗关闭后这里会自然走到下一行，把推迟的官方收起补做掉。
				const modal = document.querySelector('[role="dialog"][aria-modal="true"]');
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
				setTimeout(mpSyncDrawer, 60);
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
				setTimeout(mpSyncDrawer, 60);
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
				document.addEventListener("click", (e) => {
					const t = e.target;
					if (!t || !t.closest) return;
					if (!t.closest(".hHd-Xa_toggle")) return;
					const col = mpSidebarCol();
					if (!col || !col.classList.contains("mp-drawer-open")) return; // 抽屉没开：交官方
					if (mpClosing) return;
					e.preventDefault();
					e.stopPropagation();
					e.stopImmediatePropagation();
					mpCloseDrawer();
				}, { capture: true });
				window.addEventListener("resize", () => { mpSyncMobileAttr(); mpSyncDrawer(); mpScheduleHeaderCenter(); }, { passive: true });
				// 原生 LayoutAdapter 推来的布局变化（设计说明 §4.9）。
				//
				// ⚠️ 为什么要这个监听而不是只靠 resize：
				//   LayoutAdapter 有**滞回**（3/4~4/3 区间保留上次布局），
				//   所以「窗口尺寸变了」不等于「布局变了」。
				//   resize 每次都触发，而这个事件只在**实际布局切换**时才来。
				//   用它能让「布局没变但尺寸变了」的情况跳过重建（省掉一次闪烁）。
				window.addEventListener("dsh-layout-changed", (ev) => {
					try {
						var lv = ev && ev.detail;
						if (lv === "mobile" || lv === "official") window.__DSH_LAYOUT__ = lv;
					} catch (e) { /* 忽略 */ }
					mpSyncMobileAttr();
					mpSyncDrawer();
					mpScheduleHeaderCenter();
				});
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
					mpNavBtnEl = null; // ⭐ 修复 47：节点已移除，引用一并清空
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
				if (document.querySelector(".mp-nav-btn")) return;
				const btn = document.createElement("button");
				btn.type = "button";
				btn.className = "mp-nav-btn";
				btn.setAttribute("aria-label", "打开导航");
				btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M3 5.5h14M3 10h14M3 14.5h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
				btn.addEventListener("click", (e) => { e.stopPropagation(); mpToggleDrawer(); });
				document.body.appendChild(btn);
				mpNavBtnEl = btn; // ⭐ 修复 47：自持引用，detached 后也能重挂
				// 修复 30：汉堡锚定进 header（CSS .mp-nav-btn.mp-in-header 相对 header 定位）。
				// ⚠️ React 重渲染 header 会把外来子节点整个清掉（btn 变 detached），
				//   所以这里不能只看 class 判断"已锚定"——必须检查 btn 是否还在文档里，
				//   不在就重新 appendChild（实测 detached 节点可无损重挂）。
				// ⚠️ 修复 31：**空会话页（Hero）官方 header 是空壳**（高度 0、无 titleRow），
				//   锚进去 = 锚进一个 0 高度容器 → 汉堡被裁掉、新建对话后打不开侧边栏。
				//   判据：header 有可见内容（titleRow 存在）才锚定；否则退回 body + fixed 定位。
				mpMask();
				mpAnchorBurger(); // 修复 40：首屏立即锚定（observer 只在 mutation 时触发）
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
			function mpEnsureRefreshItem() {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				// ⚠️ 修复 28：只认顶栏 ⋯ 菜单。官方这套 Menu 是全站通用组件——
				//   顶栏 ⋯ 菜单是唯一 dense:true 的（列表带 _denseList_ 类）；
				//   会话行 ⋮、权限切换卡片等都是普通菜单（没有该类）。
				//   旧版带了个 `div[class*="_list_"]` 兜底 → 任何官方下拉菜单都会被
				//   误注入「刷新」（啾灵实测：点会话行 3 个点、权限切换卡片都弹出刷新）。
				const list = document.querySelector('[class*="_list_"][class*="_denseList_"]');
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
					() => { location.reload(); });
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

			// 修复 30 / 33：往 ⋯ 菜单里加「对话⇄轨迹」切换项。
			//   官方 tabs（对话/轨迹）在移动端被隐藏，两个视图的入口都收敛到这里。
			//   ⚠️ 修复 33：原来只注入「轨迹」一项 —— 一旦切到轨迹视图，
			//     页面上就**再没有任何可见的「对话」入口**（实测：可见含「对话」字样的
			//     元素 = 0，⋯ 菜单里也只有「轨迹」）→ 啾灵反馈「点开轨迹之后就无法返回对话」。
			//   现在按**当前激活的 tab 反向**注入：在对话视图显示「轨迹」，在轨迹视图显示「对话」。
			//   幂等键用 data-mp-item="view-switch"，标签随状态重写（同一项切换，不堆两项）。
			function mpInjectTraceItem(list) {
				if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
				const tabs = [...document.querySelectorAll(".wSkVaW_tab")];
				if (!tabs.length) return;
				const isActive = (t) => (t.className || "").toString().includes("tabActive") || t.getAttribute("aria-selected") === "true";
				const active = tabs.find(isActive);
				// 判据：当前在轨迹 → 目标是「对话」；否则（含状态未知）→ 目标是「轨迹」
				const inTrace = !!active && (active.textContent || "").trim() === "轨迹";
				const targetLabel = inTrace ? "对话" : "轨迹";
				const targetTab = tabs.find((t) => (t.textContent || "").trim() === targetLabel);
				if (!targetTab) return;
				// 图标：对话 = 气泡；轨迹 = 折线
				const iconSvg = inTrace
					? '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
						'<path d="M2.5 3.5h11v7h-6l-3.2 2.6V10.5h-1.8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>'
					: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">' +
						'<path d="M2 11.5c2.5 0 2.5-7 5-7s2.5 7 5 7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>' +
						'<circle cx="13.8" cy="11.5" r="1.2" fill="currentColor"/></svg>';
				mpInjectMenuItem(list, "view-switch", targetLabel, iconSvg, () => {
					// 借用官方 tab 的 DOM click() 触发 React 状态切换
					const t = [...document.querySelectorAll(".wSkVaW_tab")]
						.find((x) => (x.textContent || "").trim() === targetLabel);
					t?.click();
				});
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
		// ── 修复 23：StatsLine 闪烁（同步处理，不等节流）──
		// 现象：底部「多少轮·多少步」数值变化时（如 130 步 → 131 步）会瞬闪一下
		//       未缩写的原样（`2 轮 · 131 步`，尾部被截成省略号），随后才恢复缩写态。
		// 根因：官方 StatsLine 的 span 用 key={group} 渲染，group 文本一变 React 就
		//       重建该 span 并写回**未缩写原文**；而我们的缩写/隐藏跑在下面 300ms 的
		//       节流回调里 → 这段窗口内页面显示的是「未修复」形态，即用户看到的闪烁。
		// 修法：在 observer 回调里**同步**跑一次 processStatsLine()。MutationObserver
		//       回调处于微任务阶段、先于浏览器绘制，因此 React 写入的那一帧就直接是
		//       缩写态，中间不产生可见帧。processStatsLine 幂等（已缩写的文本再跑
		//       结果不变、已隐藏的 span 再跑仍是 none），故不会自触发死循环；
		//       它只做「seat 内 div 的 span 文本/display」这一小范围操作，
		//       不构成全量 DOM 扫描，不存在原来的 OOM 风险。
		// ⚠️ 只同步这一个函数；其余重活（trimMeta / 抽屉同步 / 设置 dialog
		//    等）仍留在 300ms 节流里，避免流式渲染时的高频全量扫描。
		let statsSyncQueued = false;
		const scheduleStatsSync = () => {
			if (statsSyncQueued) return;
			statsSyncQueued = true;
			queueMicrotask(() => {
				statsSyncQueued = false;
				processStatsLine();
				mpAbbreviateEffort();   // 修复 68：同批处理，抢在绘制前缩掉长文案
			});
		};
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
			for (const m of muts) {
				let t = m.target;
				if (!t) continue;
				if (t.nodeType === 3) t = t.parentElement;
				else if (t.nodeType !== 1) continue;
				if (t && t.closest && t.closest(".Y0dWHa_details")) {
					// 修复 35b：切行/重渲染时面板文本原地更新 → 同步回填（幂等，早退收敛）
					scheduleTraceTiming();
				}
				if (t && t.closest && (t.closest("[data-composer-seat]") || t.closest("[data-composer-stats]"))) {
					scheduleStatsSync();
					break;
				}
			}
			if (moTimer) return;
			moTimer = setTimeout(() => {
				moTimer = 0;
				document.querySelectorAll(".xzv4MW_timeEnd").forEach(trimMeta);
				processStatsLine();
				mpAbbreviateEffort();   // 修复 68：推理等级缩写，防止挤掉模型名
				watchApproval();
				mpSyncDrawer();
				mpAnchorBurger(); // ⭐ 修复 47：兜底重挂（React 摘掉 header 子节点时专用 observer 若漏掉）
				bindTitleRename();
				mpEnsureRefreshItem();
				mpPatchTrajectoryTiming();
				mpScheduleStatus();   // 通知栏状态：跟着 DOM 变化走（内部 1s 节流）
				mpScheduleTranscript();   // 悬浮窗转录：同一批 DOM 变化，内部 1.5s 节流
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

		// ══ 状态上报：把「AI 当前在干什么」实时推给原生通知栏 ══
		// 原生侧在 WebView 上挂了 `window.DSHAndroid.onStatus(json)`，收到后用**同一个
		// 通知 ID** 更新前台服务的通知（只 notify，不重启服务）。
		//
		// 为什么读 DOM 而不是官方 store：本插件 `inject: []`（无声明依赖），
		//   直接读 DOM 反而最稳 —— 官方重命名 store 字段时 DOM 契约还在。
		//
		// 三个信号的来源（0.2.0-rc.2 实测，均在官方 dsh-client-ui-chat / -conversation 里）：
		//   · **运行中** = 主按钮变成「停止生成」。官方 `primaryStops` 为真时把
		//     `uV2eYG_primary` 的 aria-label 换成 `t("input.stop")`
		//     （dsh-client-ui-conversation/lib/client.js:17407 / 17571）。
		//   · **在干什么** = 最后一个分组头 `.O_Ebla_title` 的文本。官方
		//     `ProcessGroupHeader` 在组未关闭时用 `message.stepProcess.<activity>`
		//     （「正在运行命令」），关闭后用 `processTitle()`（「执行了命令」）；
		//     两者都可能接 `message.turnProcess.separator`（" · "）+ `liveToolDetail`
		//     从工具参数里提出来的详情（如 `sleep 10`）。
		//   · **详细内容** = 最后一条助手正文（`[data-slot="conversation.chat.node"]`
		//     下的 `.hWmORq_root`），运行中它同时含正在流式的思考内容。
		//
		// ⚠️ 通知更新节流 1s：太频繁会让通知栏闪烁并白白耗电；内容没变则一个字节都不发。
		let mpStatusTimer = 0;
		let mpStatusPending = false;
		let mpLastStatusJson = "";
		// 转录通道（见 mpCollectTranscript）：节流比 status 宽，指纹不变则一个字节都不发。
		let mpTranscriptTimer = 0;
		let mpTranscriptPending = false;
		let mpLastTranscriptJson = "";

		function mpIsEngineRunning() {
			// 文案判据（引擎版本与界面语言在本 App 里都固定）。
			// 兜底：没有主按钮时当作空闲，宁可显示"空闲"也不要谎报在跑。
			const btn = document.querySelector(".uV2eYG_primary");
			if (!btn) return false;
			const label = btn.getAttribute("aria-label") || "";
			return label.indexOf("停止") >= 0 || /stop/i.test(label);
		}

		function mpCurrentActivity() {
			// 取**最后一个**分组头：它就是当前正在跑（或刚跑完）的那一组。
			const titles = document.querySelectorAll(".O_Ebla_title");
			if (!titles.length) return { activity: "", text: "" };
			const el = titles[titles.length - 1];
			const text = (el.textContent || "").replace(/\s+/g, " ").trim();
			const kind = el.getAttribute("data-process-activity") || "";
			return { activity: kind, text: text };
		}

		function mpTailAssistantText(limit) {
			// ⚠️ 必须限定 `data-chat-flow-kind="assistant-step"`。
			//   `.hWmORq_root` 是 AssistantMarkdown 的类，但**不能**只靠它选：
			//   同名的消息容器会把用户自己发的话也捞进来（真机实测用户消息是
			//   `data-chat-flow-kind="user"`，内含 Sixlwa_* 气泡）。
			//   限定 flow-kind 后取到的必然是助手输出（含 reasoning 与 response 两种
			//   group-part），运行中就是正在流式输出的那段。
			const nodes = document.querySelectorAll(
				'[data-chat-flow-kind="assistant-step"] .hWmORq_root'
			);
			for (let i = nodes.length - 1; i >= 0; i--) {
				const t = (nodes[i].textContent || "").replace(/\s+/g, " ").trim();
				if (t) return t.length > limit ? "…" + t.slice(t.length - limit) : t;
			}
			return "";
		}

		// 页面实际背景色（`#RRGGBB`），用来让原生侧把 WebView / 容器 / 窗口的底色对齐。
		//
		// 为什么必须上报：`adjustResize` 让窗口变矮是**逐帧动画**的，WebView 跟着变矮，
		// 但页面重排要等下一帧 —— 这个空档里新露出的那条区域还没有内容覆盖，
		// 显示的就是 WebView 自己的背景色。底色若与页面不一致，就会看到一条异色块
		// （深色底 + 浅色页面 = 用户实测的"点输入框时下半屏先黑一下"）。
		//
		// ⚠️ 不能写死颜色：官方主题可切换，写死只是把问题换个方向暴露。
		// ⚠️ 输出 `#RRGGBB` 而不是 computed style 的 `rgb(...)`：Android 的
		//   `Color.parseColor` 原生就吃这个格式，省掉在 Kotlin 里再写一套 CSS 颜色解析。
		//
		// ⚠️ **读主题 token 而不是读 `backgroundColor`**（这是"默认跟随主题色"这一条的
		//   实现）：`backgroundColor` 只是 token 的**最终投影**，在纯色主题下两者恰好
		//   相等，一旦主题改用背景图/渐变，投影与主题语义就分叉 —— 那时要把系统栏
		//   对齐到"主题的基底色"就只能靠 token。深色主题下 token 是 `#151517`、
		//   浅色是 `#fff`，与旧行为一致，所以这次替换对当前外观零改变。
		//   token 定义在 `body` 上（`html` 上取到空串），所以两个元素都要试。

		/** 主题基底色 token，按优先级排列。 */
		const MP_THEME_BG_TOKENS = ["--dsw-alias-bg-base"];

		// 把 CSS 颜色值规范化成 `#RRGGBB`；无法解析或完全透明时返回空串。
		//
		// 支持 token 实际会用到的几种写法：`#fff`（缩写）、`#ffffff`、`#ffffffff`
		// （带 alpha，取前 6 位）与 `rgb()/rgba()`。其余写法（百分比、颜色函数）
		// 一律返回空串，让调用方退回下一条路 —— 猜错比不猜更糟。
		function mpCssColorToHex(value) {
			const raw = String(value || "").trim();
			if (!raw) return "";
			let r;
			let g;
			let b;
			if (raw.startsWith("#")) {
				const h = raw.slice(1);
				const expand = (s) => (s.length === 1 ? s + s : s);
				if (h.length === 3 || h.length === 4) {
					r = parseInt(expand(h[0]), 16);
					g = parseInt(expand(h[1]), 16);
					b = parseInt(expand(h[2]), 16);
				} else if (h.length === 6 || h.length === 8) {
					r = parseInt(h.slice(0, 2), 16);
					g = parseInt(h.slice(2, 4), 16);
					b = parseInt(h.slice(4, 6), 16);
				} else {
					return "";
				}
			} else {
				const m = /^rgba?\(([^)]+)\)$/.exec(raw);
				if (!m) return "";
				const parts = m[1].split(",").map((s) => parseFloat(s));
				if (parts.length < 3 || parts.some((n) => !isFinite(n))) return "";
				// 完全透明 ⇒ 视为"没设背景"（rgba(0,0,0,0) 是常见表示）
				if (parts.length === 4 && parts[3] === 0) return "";
				[r, g, b] = parts;
			}
			if (![r, g, b].every((n) => isFinite(n))) return "";
			const hex = (n) => Math.max(0, Math.min(255, Math.round(n)))
				.toString(16).padStart(2, "0").toUpperCase();
			return "#" + hex(r) + hex(g) + hex(b);
		}

		function mpPageBackground() {
			// ① 主题 token 优先（见上方注释里为什么要换掉 backgroundColor）
			for (const el of [document.documentElement, document.body]) {
				if (!el) continue;
				const cs = getComputedStyle(el);
				for (const token of MP_THEME_BG_TOKENS) {
					const hex = mpCssColorToHex(cs.getPropertyValue(token));
					if (hex) return hex;
				}
			}
			// ② token 取不到（老内核/主题改结构）⇒ 退回实际算出来的背景色。
			// body 透明时往上找 html；都透明才退回白。
			for (const el of [document.body, document.documentElement]) {
				if (!el) continue;
				const hex = mpCssColorToHex(getComputedStyle(el).backgroundColor);
				if (hex) return hex;
			}
			return "#FFFFFF";
		}

		function mpCollectStatus() {
			const running = mpIsEngineRunning();
			const { activity, text } = mpCurrentActivity();
			let title = (document.title || "").replace(/\s*—\s*DeepSeek Harness\s*$/, "").trim();
			return {
				running: running,
				activity: activity,
				text: text,
				body: mpTailAssistantText(400),
				title: title,
				bg: mpPageBackground(),
			};
		}

		function mpReportStatus() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onStatus !== "function") return;
			let json;
			try { json = JSON.stringify(mpCollectStatus()); } catch (e) { return; }
			if (json === mpLastStatusJson) return;   // 无变化 ⇒ 不打扰通知栏
			mpLastStatusJson = json;
			try { bridge.onStatus(json); } catch (e) { /* 原生侧异常不影响页面 */ }
		}

		// 节流：流式输出时 DOM 变化极频繁，合并成最多 1 次/秒。
		function mpScheduleStatus() {
			if (mpStatusPending) return;
			mpStatusPending = true;
			mpStatusTimer = window.setTimeout(() => {
				mpStatusPending = false;
				mpReportStatus();
			}, 1000);
		}

		// ══ 悬浮窗展开态的转录通道 ══════════════════════════════════════════
		// 用途：原生侧悬浮球展开后要显示**完整**的对话 —— 用户消息、助手完整回复、
		//   每一次工具调用。通知栏那条 status 通道的 body 被刻意压到 400 字，
		//   体量差一个数量级，所以单独开一条（原生侧对应 ChatTranscript.kt）。
		//
		// ⚠️ 选择器一律用**属性**而不是类名：`data-chat-flow-kind`（官方 chat.js 的
		//   chatNode() 生成）与 `data-tool` / `data-state`（官方 tool.js 的 ToolRow
		//   写死）是稳定契约，而 `hWmORq_root` / `Sixlwa_bubble` 这类 CSS Module
		//   哈希类名每次官方发版都可能变。类名只用于"取文本时收窄"，取不到就退回
		//   整个节点 —— 退化的结果是"多带一点噪音"，而不是"内容全丢"。
		const MP_TRANSCRIPT_MAX_ITEMS = 80;
		const MP_TRANSCRIPT_MAX_CHARS = 2000;
		// ⚠️ 工具行的判据是 `[data-chat-flow-kind="tool-call"]`，**不是** `[data-tool]`。
		//   真机实测（CDP 读真实会话 DOM）：一次 bash 工具调用渲染出的是
		//   `<div data-chat-flow-kind="tool-call" data-turn-process-member="true"
		//     data-turn-process-hidden="true" hidden="until-found">`，
		//   而全文档 `[data-tool]` 命中数**恒为 0** —— `data-tool` 是
		//   `dsh-client-ui-tool` 的标记，本页面走的不是那套渲染器。
		//   只认 `data-tool` 的后果是「工具调用一条都采不到」，正是本通道上线后
		//   一直没见过的现象。
		// ⚠️ 工具行即使被折叠（`hidden="until-found"`）也照样采：`textContent`
		//   对 hidden 元素仍可读，而用户要的正是「展开面板能看到调了哪些工具」，
		//   恰恰是折叠态下最需要补的信息。
		const MP_TRANSCRIPT_SELECTOR = [
			'[data-chat-flow-kind="user"]',
			'[data-chat-flow-kind="steering"]',
			'[data-chat-flow-kind="assistant-step"]',
			'[data-chat-flow-kind="tool-call"]',
			'[data-chat-flow-kind="turn-error"]',
			"[data-tool]",
		].join(",");

		/** 压成单行并按 code point 截断（按 UTF-16 切会把 emoji 切成半个）。 */
		function mpTranscriptText(raw, limit) {
			const flat = String(raw === null || raw === undefined ? "" : raw)
				.replace(/\s+/g, " ").trim();
			const cps = Array.from(flat);
			return cps.length > limit ? cps.slice(0, limit - 1).join("") + "…" : flat;
		}

		/**
		 * 取节点的文本，**剔除元数据与按钮子树**。
		 *
		 * ⚠️ 为什么必须剔除（真机实测）：官方把时间戳与操作按钮放在同一条消息的尾部
		 *   （".xzv4MW_actions" 里一个 ".xzv4MW_timeStart" 加一个含 svg 的复制按钮），
		 *   直接取 "textContent" 会得到「只回复两个字：通了01:14」—— 时间戳粘在用户
		 *   原话后面，看起来像用户自己打的字。
		 *
		 * ⚠️ 判据用 **"[data-clock]" + "button"/"svg" 标签**，不用那个哈希类名：
		 *   "data-clock" 是官方写死的元数据标记（"start" = 时间戳、"end" = 时间戳 +
		 *   用量），实测页面里两处都有；而"消息尾部的操作按钮"永远是 button。
		 *   类名随官方发版会变，属性不会。取不到就退回原文（多带点噪音，不丢内容）。
		 */
		function mpTranscriptVisibleText(el) {
			try {
				const clone = el.cloneNode(true);
				const junk = clone.querySelectorAll("[data-clock], button, svg");
				for (let i = 0; i < junk.length; i++) junk[i].remove();
				return clone.textContent || "";
			} catch (e) {
				return el.textContent || "";
			}
		}

		/** 会话标题（与状态通道同源，两处共用免得正则漂移）。 */
		function mpConversationTitle() {
			return (document.title || "").replace(/\s*—\s*DeepSeek Harness\s*$/, "").trim();
		}

		function mpCollectTranscript() {
			const items = [];
			// 限定在消息视图内：轨迹页/设置页里也可能有 `[data-tool]` 之类的节点，
			// 不加限定会把别的界面的内容混进对话里。取不到容器就退回整个文档。
			const root = document.querySelector(".wSkVaW_viewArea") || document;
			let nodes = [];
			try { nodes = root.querySelectorAll(MP_TRANSCRIPT_SELECTOR); }
			catch (e) { nodes = []; }

			// 引擎是否在跑（与状态通道同源），以及最后一个助手节点 —— 只有它
			// 可能是"正在输出"的那一条。
			const engineRunning = mpIsEngineRunning();
			let lastAssistantStep = null;
			for (const el of nodes) {
				if (el.getAttribute("data-chat-flow-kind") === "assistant-step") lastAssistantStep = el;
			}

			for (const el of nodes) {
				const tool = el.getAttribute("data-tool");
				if (tool) {
					const state = el.getAttribute("data-state") || "";
					items.push({
						role: "tool",
						// 工具行的可见文本已经含标题（如「运行 bash」）与状态文案，
						// 直接用；为空才退回工具名，保证面板上至少看得见调了哪个工具。
						text: mpTranscriptText(mpTranscriptVisibleText(el), MP_TRANSCRIPT_MAX_CHARS) || tool,
						running: state === "running",
					});
					continue;
				}
				const kind = el.getAttribute("data-chat-flow-kind");

				// 工具调用（真机实测形状，见选择器处的注释）：
				//   `data-sample` = 工具名（`bash`），`data-state` = `ok`/`running`，
				//   可见文本 = 标题 + 摘要（「运行命令 Echo TOOL_OK to test output」）。
				// ⚠️ 两个属性都长在**内层** `[data-sample]` 节点上，不在
				//   `[data-chat-flow-kind]` 那个外层 div 上 —— 直接读 el 会全空。
				if (kind === "tool-call") {
					const inner = el.querySelector("[data-sample]") || el;
					const sample = inner.getAttribute("data-sample") || "";
					const state = inner.getAttribute("data-state") || "";
					const body = mpTranscriptText(mpTranscriptVisibleText(inner), MP_TRANSCRIPT_MAX_CHARS);
					items.push({
						role: "tool",
						text: body || sample || "工具调用",
						running: state === "running",
					});
					continue;
				}

				// assistant-step 的**思考**与**正文**是两个并列节点，靠
				// `data-chat-group-part` 区分（真机实测：`reasoning` / `response`）。
				// ⚠️ 不区分时两者都发成 `assistant`，面板上标签同为「AI」、样式也
				//   一模一样，用户看到的就是"思考与正文糊在一起、同时冒出来"。
				const part = kind === "assistant-step"
					? (el.getAttribute("data-chat-group-part") || "")
					: "";

				let body = el;
				if (kind === "assistant-step") {
					// 收窄到 AssistantMarkdown 的正文容器。官方 AssistantMarkdown 对
					// tool-call 块是直接 `break` 跳过的（不渲染进 body），所以这里取到
					// 的是纯正文，不会把工具标题混进助手回复里。
					body = el.querySelector(".hWmORq_root") || el;
				}
				const text = mpTranscriptText(mpTranscriptVisibleText(body), MP_TRANSCRIPT_MAX_CHARS);
				if (!text) continue;
				// 只有"引擎在跑"且"这是最后一条助手节点"才算正在输出 —— 后面的
				// 节点一出现，前一个自然就不再是运行中。
				const live = engineRunning && kind === "assistant-step" && el === lastAssistantStep;
				if (kind === "user") items.push({ role: "user", text: text, running: false });
				// steering = 用户在回合进行中追加的指令，按用户消息显示。
				else if (kind === "steering") items.push({ role: "user", text: text, running: false });
				else if (kind === "assistant-step") {
					items.push({
						role: part === "reasoning" ? "reasoning" : "assistant",
						text: text,
						running: live,
					});
				}
				else if (kind === "turn-error") items.push({ role: "error", text: text, running: false });
			}

			return {
				title: mpConversationTitle(),
				running: mpIsEngineRunning(),
				// 只留最后 MAX 条：面板要看的是当前进展，早期历史可以丢。
				items: items.length > MP_TRANSCRIPT_MAX_ITEMS
					? items.slice(items.length - MP_TRANSCRIPT_MAX_ITEMS)
					: items,
			};
		}

		function mpReportTranscript() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onTranscript !== "function") return;
			let json;
			try { json = JSON.stringify(mpCollectTranscript()); } catch (e) { return; }
			// 指纹不变就一条都不发：这条通道体量比 status 大两个数量级，
			// 无脑重推会白白占用原生侧的 JSON 解析与面板重绘。
			if (json === mpLastTranscriptJson) return;
			mpLastTranscriptJson = json;
			try { bridge.onTranscript(json); } catch (e) { /* 原生侧异常不影响页面 */ }
		}

		// 节流 250ms：用户要求面板里"看得见流式输出"，所以这里必须跟着 DOM 的
		// 逐字变化走。原来的 1.5s 会让整段回复攒成两三坨跳出来 —— 观感就是
		// "没有流式、一次全出"。
		//
		// ⚠️ 成本可控的依据：`mpReportTranscript()` 带指纹去重，**内容没变一个
		//   字节都不发**，所以节流只决定"最多多快发一次"，不决定实际发送量。
		//   DOM 的 characterData 变化本身就已经是逐 token 的节奏。
		function mpScheduleTranscript() {
			if (mpTranscriptPending) return;
			mpTranscriptPending = true;
			mpTranscriptTimer = window.setTimeout(() => {
				mpTranscriptPending = false;
				mpReportTranscript();
			}, 250);
		}

		function mpInstallTranscriptBridge() {
			mpReportTranscript();
			const t = window.setInterval(mpReportTranscript, 5000);   // 兜底：DOM 静默变化时也能纠正
			return () => window.clearInterval(t);
		}

		function mpInstallStatusBridge() {
			mpReportStatus();
			const t = window.setInterval(mpReportStatus, 5000);   // 兜底：DOM 静默变化时也能纠正
			return () => window.clearInterval(t);
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
		const MP_PRIV_API = "/api/dsh-android/privileged";
		const MP_PRIV_NS = "dsh-android-mobile.privileged";
		// 官方静态模块表（`dsh-web-frontend` 的 `staticModules`）里有 react，
		// 所以客户端半可以直接 require 它，不需要自己打包一份 React。
		//
		// ⚠️ **必须延迟到 apply() 再 require，不能在模块顶层**：本文件的 factory
		//    会在 `__ModuleLoader__` 的 **queue 模式**下就被调用（模块系统尚未 boot、
		//    静态模块表还没建立），那时 require 会抛。apply() 一定是 boot 之后跑的。
		// ⚠️ 用 try/catch 拿：拿不到就**只损失这一个分节**，绝不能让整个插件
		//    （含移动端 UI 适配）挂掉。
		let mpPrivReact = null;

		const MP_PRIV_ZH = {
			nav: "特权权限",
			title: "特权权限（uid 2000 / root）",
			intro: "开启后，AI 可以在需要时以更高身份执行命令 —— 截图、模拟点击、卸载应用、改系统设置这些原本做不到的事。默认仍走 App 身份，要不要提权由 AI 自己判断。",
			statusTitle: "当前状态",
			statusOn: "已开启",
			statusOff: "未开启",
			statusUnknown: "检测中",
			recheck: "重新检测",
			checking: "检测中…",
			failed: "检测失败",
			rowRunner: "执行通道",
			rowKey: "ADB 密钥",
			rowIdentity: "实际身份",
			ready: "已就位",
			missing: "缺失",
			imported: "已导入（电脑密钥）",
			generated: "已生成（待配对）",
			paired: "已配对",
			keyOriginUnknown: "来源未知",
			notImported: "未导入",
			justImported: "刚刚导入",
			uidOk: "uid 2000",
			uidApp: "App 身份（未提权）",
			// 「固定」= 走 legacy 5555，它不依赖无线调试开关，也不会随重启换端口。
			chStable: "固定端口（关无线调试也不断）",
			chWireless: "无线调试（关掉开关会断）",
			bytes: "字节",
			guideTitle: "开启步骤",
			step1: "用数据线把手机连到电脑。首次连接时手机会弹「允许 USB 调试吗」，勾选「一律允许」再确定。",
			step2: "在电脑上打开终端，执行下面这条 —— 它让手机的 adbd 在 5555 端口监听。Android 10 每次重启手机后都要重做一次。",
			step3: "再执行下面这条，把电脑上已授权的密钥推到手机的共享目录（macOS / Linux 把 %USERPROFILE%\\.android\\adbkey 换成 ~/.android/adbkey）。",
			step4: "回到本页，点上面的「重新检测」。App 会把密钥搬进自己的私有目录，并立刻删掉共享目录里的中转文件。",
			cmd2: "adb tcpip 5555",
			cmd3: "adb push %USERPROFILE%\\.android\\adbkey /sdcard/dsh_adbkey",
			copy: "复制",
			copied: "已复制",
			copyFailed: "复制失败",
			notesTitle: "几点说明",
			note1: "密钥等同于这台设备的控制权。App 只在私有目录保存它，导入后立刻删除 /sdcard 上的中转文件。",
			note2: "卸载重装会清掉密钥，需要重新做一遍上面的步骤。",
			note3: "以特权身份执行的命令不经过沙箱 —— 该身份读不到 App 私有目录，写沙箱和 link 转译对它都不生效。",
			// ── 无线调试（Android 11+）：免电脑那条路 ──
			wlTitle: "无线调试（Android 11+，不用电脑）",
			wlIntro: "Android 11 起系统自带「无线调试」，可以让本应用自己拿到特权身份，全程不用数据线、不用电脑，重启手机也依然有效。首次需要配对一次。",
			wlStep1: "在本页点下面的「配对无线调试」按钮 —— 通知栏会出现一条常驻提示，告诉你接下来去哪。",
			wlStep2: "在系统「设置 → 开发者选项」里打开「无线调试」开关，再点进「使用配对码配对设备」。",
			wlStep3: "那个界面一打开，通知栏的提示就会自动变成带输入框的（本应用会一直等着，不用回本页重按）。下拉通知栏，在里面直接回复那 6 位配对码。",
			wlStep4: "配对成功后回到本页点「重新检测」，身份应变成 uid 2000。之后就一直有效，不必再配对。",
			wlPair: "配对无线调试",
			wlPairing: "正在等待…",
			// ⚠️ 这句话是本次体验修复的核心：告诉用户「不用回来重按」。
			//    原实现发现 8 秒没端口就报失败，用户刚在系统里找到那个按钮，
			//    通知栏已经说没找到了，只能回本页重按 —— 而重按还是同样结果。
			wlPairingHint: "已开始等待。请去系统「设置 → 开发者选项 → 无线调试」点开「使用配对码配对设备」，让那个界面保持显示 —— 本应用会一直等着（不会超时），端口一出现通知栏就会变成输入框。想中止请点上面的「取消配对」。",
			wlCancel: "取消配对",
			wlPairHint: "配对码由系统显示、只能人工输入 —— 这是 SPAKE2 协议的要求，任何应用（包括 Shizuku）都无法代填。",
			wlPort: "无线端口",
			wlPortNone: "未发现（无线调试可能没开）",
			wlNote: "与上面的电脑引导二选一即可。无线调试那条路更省事，但只在 Android 11 及以上可用；Android 10 只能用电脑引导。",
			wlTcp: "切到固定端口",
			wlTcpHint: "切到固定端口后就不依赖无线调试开关了 —— 关无线调试、关 Wi-Fi 都不断。重启手机后会自动重切，通常不用管。",
			wlNoBridge: "当前环境没有原生的配对入口（DSHAndroid 桥不可用），无法发起配对。",
			wlNoNotify: "配对全程靠通知栏交互（提示 → 输入配对码 → 取消），但本应用的通知被系统关掉了，配对无法进行。请到「系统设置 → 应用 → DSH → 通知」里打开通知后重试。",
			wlPortStale: "已失效（无线调试可能已关）",
			recommended: "推荐",
			// Android 10 上「无线调试」根本不存在，说清楚比让用户去系统设置里白找一遍好。
			wlUnsupported: "本机是 Android 10，系统里没有「无线调试」这个功能（它从 Android 11 才有）。请用下面的「电脑引导」，那是在这台设备上唯一可行的方式。",
			pcTitlePrimary: "开启引导（Android 10 用这条）",
			pcTitle: "改用电脑引导（Android 10 或没有无线调试时）",
			pcIntro: "如果系统里找不到「无线调试」（Android 10 及以下），或者无线调试一直配不上，可以插上数据线用电脑引导一次。",
			setupTitle: "开启引导（当前已就绪，一般不用看）",
			showDetails: "查看详情",
			hideDetails: "收起详情",
			nextAllGood: "已就绪，可以直接用了。",
			nextPairHint: "还差一步：点右边按钮完成无线调试配对。",
			nextSwitchHint: "建议切到固定端口，这样关掉无线调试开关也不会断。",
			nextChoose: "还没开启。按下面「无线调试」那几步走一遍即可，不用电脑。",
			nextChoosePc: "还没开启。按下面的「电脑引导」走一遍 —— 本机是 Android 10，只能用这种方式。",
			// 探测还没返回、但界面已过软截止（8 秒）时的提示。
			// ⚠️ 必须同时给出「不用等」和「能做什么」，不能只说「检测中」——
			//    真机反馈：首次安装时这里一直显示「检测中」且没有任何按钮可点。
			softPending: "还在探测（设备正在重启 adbd 时可能要几十秒）。你可以直接开始配对，不必等它。",
			softPendingPc: "还在探测（设备正在重启 adbd 时可能要几十秒）。你可以直接按下面的「电脑引导」开始，不必等它。",
			// ── 按失败原因给出的「下一步」──────────────────────────
			//
			// ⚠️ 判据是 runner 那条 `诊断: kind=…` 行（见宿主半的 `DIAG_KIND_RE`），
			//    **不是**去匹配中文文案 —— 文案改一个字分支就会静默失效。
			//    这里只覆盖「下一步动作明确不同」的几类；其余 kind（含 unknown、
			//    老版本 runner 没有这个字段）继续走上面的通用引导，行为与改动前一致。
			//
			// 这几类的共同点：用户光看「App 身份（未提权）」完全不知道该点哪里，
			// 而实际上每一类的正解都不一样。
			nextRevoked: "密钥的授权已被设备撤销（多半是点过「撤销 USB 调试授权」）。需要重新配对一次。",
			nextUnauthorized: "密钥还没被设备授权。点右边按钮走一次配对即可。",
			nextNoKey: "还没有 ADB 密钥。点右边按钮走一次配对即可，不用电脑。",
			nextNoKeyPc: "还没有 ADB 密钥。按下面的「电脑引导」走一遍 —— 本机是 Android 10，没有无线调试。",
			nextWirelessStale: "无线调试端口已失效（多半是关过无线调试或重启过）。重开一次无线调试，或直接点右边按钮切到固定端口。",
			nextNoChannel: "固定端口和无线调试都不可用。打开系统「无线调试」，或点右边按钮试一次。",
			nextAdbMissing: "内置的 adb 工具链缺失，无线调试这条路走不通。装一次 App 更新即可修好。",
			nextKeyBlocked: "密钥没被设备信任，为避免反复弹授权框已跳过无线调试。请重新配对。",
			nextTimeout: "探测超时了 —— 多半是 adbd 正在重启。等十几秒再点一次「重新检测」。",
			nextPair: "重新配对",

			// ── root（uid 0）────────────────────────────────────────
			// ⚠️ 这一块**必须存在**：`root` 工具只在检测到 root 时才注册，所以没有
			//   root 的设备上用户看不到它的任何痕迹，分不清「本机没有 root」与
			//   「有 root 但没检测到」。状态与原因都得摆出来。
			rootTitle: "root 权限（uid 0）",
			rootOn: "已开启",
			rootDenied: "未授权",
			rootAbsent: "无 root",
			rootUnknown: "未检测",
			rootRowState: "状态",
			rootRowDetail: "探测结论",
			rootRowAt: "检测时间",
			rootRecheck: "重新检测",
			rootChecking: "检测中…",
			// 「下一步」按三态各写一句 —— 三种情况下用户该做的事完全不同。
			// ⚠️ 还有第四种：结论是 GRANTED，但**本次启动没挂上**（用户是刚点完
			//   「重新检测」才授权成功的）—— 那句单独写，见 rootNextOnPendingReload。
			rootNextOn: "root 工具已注册，AI 可以直接以 uid 0 执行命令（读写 /data、改挂载、iptables 等）。",
			rootNextOnPendingReload: "已检测到 uid 0，但 root 工具要下次启动才会挂上（它是启动时挂载的，当前这个会话里还看不到）。请重启 App 后再用。",
			rootNextDenied: "本机有 su，但本应用没拿到 uid 0。请在 Magisk / SuperSU 之类的授权管理里给「DSH」授权，然后点「重新检测」。",
			rootNextAbsent: "本机没有 root（也没找到 su）。需要 root 的功能用不了，其余功能不受影响 —— 上方的 uid 2000 通道与之无关，照常可用。",
			rootNextUnknown: "还没检测过。点「重新检测」跑一次（首次可能会弹 root 授权框）。",
			// ⚠️ 这里是**纯文本**渲染（React 直接塞进 DOM），不能写 Markdown 标记 ——
			//   写 `**加粗**` 或反引号会原样显示出来（第一版就这么错了）。
			rootNote: "root 工具只在检测到 uid 0 时才注册。检测方式是真的跑一次 su -c id -u、看输出是不是 0，而不是查 su 文件在不在 —— 占位 su、装了 Magisk 但没授权、root 升级后失效这三种情况文件都在却提不了权。结果会缓存 24 小时。",
			rootDetailNever: "（无）",

			// ── Shizuku（uid 2000 的第二条来源，不依赖 adbd）────────────
			//
			// ⚠️ 这一块的存在理由，就是**修掉本页最大的那个坑**：内置的两条路
			//   （固定端口 5555 / 无线调试）都以「设备上有一个可连的 adbd」为前提，
			//   而 Android 10 及以下没有无线调试、`persist.adb.tcp.port` 又不可写，
			//   于是每次重启都得让用户插上电脑再跑一次 `adb tcpip 5555`。
			//   真机反馈原话：「他有线调试，按步骤走了，没成功，一切都跟没动一样」。
			//
			//   Shizuku 走 **binder**，不碰 adbd、不校验 adbkey、重启后依然有效 ——
			//   对那批用户这是唯一走得通的路。所以它排在三张路径卡的最前面。
			shizukuTitle: "Shizuku（不用电脑）",
			// 四种状态各自对应**完全不同的下一步**，所以文案必须分开。
			// ⚠️ 判据顺序是 装了 → 服务在跑 → 已授权，不能合并成一个布尔：
			//   「没装」要引导去下载，「装了没跑」要引导去打开它，
			//   「跑了没授权」才是点授权 —— 三者按钮完全不同。
			shizukuAbsent: "未安装",
			shizukuInstalled: "未启动",
			shizukuUngranted: "待授权",
			shizukuGranted: "已就绪",
			shizukuNextAbsent: "本机还没装 Shizuku。装上它就能免电脑开启特权身份。",
			shizukuNextStopped: "Shizuku 已安装，但服务没有运行。打开 Shizuku 应用并点「启动」，再回来。",
			shizukuNextUngranted: "Shizuku 服务已在运行。点右边按钮授权，在弹出的小窗口里点「允许」即可。",
			shizukuNextGranted: "Shizuku 通道已就绪（uid 2000）。这条通道不依赖 adbd，重启手机后依然有效。",
			// ⚠️ 授权成功后**不用重启 App**：runner 每次执行命令时才现连 Shizuku，
			//   所以这里只是让界面去重新探测一次，而不是要求用户重启。
			shizukuNextGrantedStale: "Shizuku 已授权，但上面的状态还是旧的。点右边按钮重新检测一次即可（不用重启应用）。",
			shizukuDownload: "下载 Shizuku",
			shizukuOpen: "打开 Shizuku",
			shizukuGrant: "授权",
			shizukuGranting: "等待授权…",
			shizukuRecheck: "重新检测",
			shizukuRowService: "服务",
			shizukuRowUid: "服务身份",
			shizukuRowVersion: "服务版本",
			shizukuServiceRunning: "运行中",
			shizukuServiceStopped: "未运行",
			shizukuServiceAbsent: "未安装",
			// Shizuku 的详细步骤（折起）。
			// ⚠️ 逐条拆成独立键，**不要**用数组：`locale` 字典的值是字符串，
			//   数组会被当成「取不到」而回落成键名原样显示（这个回落是静默的）。
			shizukuStep1: "装上 Shizuku 并打开它。",
			shizukuStep2: "在 Shizuku 里按它自己的提示启动服务 —— 通常要求先用电脑执行一次 adb（或用 root）。这一步只需做一次，之后每次开机它自己会起来。",
			shizukuStep3: "回到本页点「授权」，在弹出的系统窗口里点「允许」。",
			shizukuStep4: "回到本页点「重新检测」，上面的身份应变成 uid 2000。",
			shizukuNote: "Shizuku 只在启动服务那一次需要 adb（或者 root），之后它自己常驻，本应用随时可用 —— 这与「固定端口」那条路每次重启都要电脑重跑 adb tcpip 5555 完全不同。命令同样不经过沙箱。",
			shizukuNoBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法操作 Shizuku。",
			// 通道说明：已就绪时告诉用户走的是哪条。
			chShizuku: "Shizuku（不依赖 adbd，重启也有效）",

			// ── 三条路径的卡片标题（重做后统一结构）────────────────────
			pathTitle: "开启方式",
			pathIntro: "三条路任选一条即可，用哪条由本机支持情况决定 —— 卡片上的徽章就是它当前的状态。",
			pathReady: "可用",
			pathUnavailable: "不可用",
			pathTodo: "待配置",
			pathShowSteps: "查看步骤",
			pathHideSteps: "收起步骤",
			// Android 10 上「无线调试」这张卡**整张不渲染**（系统里根本没这个功能，
			// 渲染出来只会是一堆点不动的按钮）。这里只留一句说明。
			wlAbsentNote: "本机是 Android 10，系统里没有「无线调试」（它从 Android 11 才有），所以这条路不存在 —— 不必去设置里找。",

			// ── 标签页布局 ──────────────────────────────────────────
			// 三条通道各自一个标签，标签上的圆点就是它的状态 ——
			// 不必逐个点开才知道哪条通。
			tabAdb: "ADB",
			tabShizuku: "Shizuku",
			tabRoot: "root",
			// ⚠️ 只有 ADB 标签需要这段统摄说明：它下面有**两张**卡（无线调试 +
			//   电脑引导），而另外两个标签各只有一张、卡片自带说明。
			//   没有它，用户看到两张并列的卡不知道它们同属一条通道。
			adbIntro: "用内置的 adb 连上设备自己的 adbd，拿到 shell 身份执行命令。两条走法：Android 11+ 用「无线调试」配对，Android 10 及以下只能插电脑引导一次。",
			rootIntro: "root 是 uid 0，比 uid 2000 还高一层。与上面两条通道互不影响：没有 root 的设备，uid 2000 照常可用。",
		};

		// ══════════════════════════════════════════════════════════════════
		// 悬浮气泡设置分节
		//
		// 需求：App 退到后台时浮一个圆形气泡，用户能看着 AI 干活、也能继续说话。
		// 场景是**设备自动化** —— AI 去操作别的 App 时用户必须离开本应用，
		// 否则完全看不见进展、也没法中途补一句话。
		//
		// 分节要解决三件事，缺一不可：
		//   ① 让用户知道有这么个东西（默认关，不主动开就没有）
		//   ② 把用户送到系统的「显示在其他应用上层」授权页 —— 这是**特殊权限**，
		//      `requestPermissions` 对它无效，只能手动开
		//   ③ 开关本身
		//
		// 状态由原生提供（`DSHAndroid.bubbleState()`）—— 开关在 SharedPreferences、
		// 授权在 Settings，两者都在原生侧，浏览器进程读不到。
		// ══════════════════════════════════════════════════════════════════
		const MP_BUBBLE_NS = "dsh-android-mobile.bubble";
		const MP_BUBBLE_ZH = {
			nav: "悬浮气泡",
			title: "悬浮气泡",
			intro: "退到后台后，屏幕上浮一个圆形气泡，实时显示 AI 在干什么，也能直接打字发消息。做设备自动化时用它盯着进度，不用来回切应用。",
			statusTitle: "当前状态",
			statusOn: "已开启",
			statusOff: "未开启",
			statusUnknown: "检测中",
			rowSwitch: "开关",
			rowPerm: "悬浮窗权限",
			on: "已开启",
			off: "已关闭",
			granted: "已授权",
			notGranted: "未授权",
			enable: "开启气泡",
			disable: "关闭气泡",
			grant: "去授权",
			recheck: "重新检测",
			nextNeedPerm: "还差一步：系统还没允许本应用显示在其他应用上层。点右边按钮去开。",
			nextNeedEnable: "权限已就绪，打开开关即可用。",
			nextAllGood: "已就绪。退到后台就能看到气泡。",
			howTitle: "怎么用",
			how1: "退到后台（按 Home 或切到别的应用）时气泡才出现 —— 在本应用里它不会碍事。",
			how2: "拖动可以挪位置，松手会自动吸附到最近的屏幕边缘，位置会记住。",
			how3: "点一下展开面板：上面是当前动作和最近输出，下面是输入框，打字点「发送」即可。",
			how4: "面板上「打开」回到本应用，「收起」退回圆形。",
			notesTitle: "几点说明",
			note1: "「显示在其他应用上层」是特殊权限，系统不允许应用自己弹窗申请，必须手动去系统设置里打开。",
			note2: "气泡靠前台服务推送的状态刷新，约每秒一次；不额外耗电（引擎本来就在跑）。",
			note3: "气泡里发消息走的是同一个官方输入框，会进入当前会话，和在本应用里打字完全一样。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法控制气泡。",
			motionTitle: "甩动手感",
			motionIntro: "让气泡更像一只真的小宠物：甩一甩会飞、会撞墙弹、落地会压扁。",
			throwName: "拖拽抛掷",
			throwHint: "手指快速划过气泡再松手，它会朝那个方向飞出去。",
			shakeName: "晃动甩飞",
			shakeHint: "快速晃动手机，气泡就像被甩出去一样。不需要任何权限。",
			imeName: "键盘顶飞",
			imeHint: "键盘弹出时，气泡从屏幕下方「嗖」地飞上去，不会被挡住。",
			imeLocked: "不可用",
			imeLockedWhy: "这个功能要读系统的键盘状态，普通应用没这个权限。接入 Shizuku 或 root 后就能打开。",
			on2: "开",
			off2: "关",
			// ── 外观控制面板 ────────────────────────────────────────
			panelTitle: "外观与手感",
			panelIntro: "下面这些就是悬浮球支持的全部可调项，改完立刻生效。分组可以点开收起。",
			panelUnavailable: "读取当前外观失败（原生桥不可用）。",
			collapse: "收起",
			expand: "展开",
			autoColor: "自动",
			panelBgAuto: "透明",
			autoSize: "自动",
			neverHide: "不隐藏",
			grpState: "形态",
			grpLook: "尺寸与形状",
			grpColor: "配色",
			grpMotion: "动作与交互",
			grpAnim: "动画与指示",
			grpPanel: "展开面板",
			fState: "默认形态",
			stAuto: "自动",
			stCollapsed: "小窗",
			stExpanded: "大窗",
			fSize: "直径",
			fShape: "形状",
			shapeCircle: "圆形",
			shapeSquare: "方形",
			shapeRounded: "圆角",
			fCorner: "圆角半径",
			fOpacity: "整体不透明度",
			fBgOpacity: "背景不透明度",
			fAvatarOpacity: "头像不透明度",
			fAvatarScale: "头像缩放",
			fBorderWidth: "描边粗细",
			fBorder: "描边颜色",
			fBg: "背景主色",
			fBg2: "背景副色（渐变）",
			fEdgeSnap: "拖出屏幕贴边",
			fMoveEnabled: "自己会走动",
			fMoveInterval: "走动间隔",
			fMoveMin: "单次最少走",
			fMoveMax: "单次最多走",
			fFlipOnTurn: "转向时左右翻转",
			fFrameInterval: "帧间隔",
			fSpin: "常驻旋转角度",
			fPulse: "呼吸脉动",
			fShowDot: "显示状态小圆点",
			fStatusImageSize: "状态配图尺寸",
			fPanelWidth: "面板宽度",
			fPanelHeight: "面板高度",
			fStatusAutoHide: "状态条自动隐藏",
			fPanelBg: "面板背景色",
			fPanelText: "面板文字色",
			fPanelAccent: "面板强调色",

			grpBalance: "余额徽标",
			bbEnabledHint: "在球旁边常驻显示 DeepSeek 余额。关掉就完全不占位置。",
			fBbEnabled: "显示余额徽标",
			fBbAnchor: "出现位置",
			fBbTemplate: "显示内容",
			fBbGap: "与球的距离",
			fBbFontSize: "字号",
			fBbRadius: "圆角",
			fBbPaddingH: "左右内边距",
			fBbPaddingV: "上下内边距",
			fBbMaxWidth: "最大宽度",
			fBbBorderWidth: "描边粗细",
			fBbBg: "背景色",
			fBbFg: "文字色",
			fBbBorder: "描边色",
			fBbCostFlash: "花钱时跳动",
			fBbHideIdle: "只在有花费时显示",
			fBbTurnEnd: "每轮结束刷新",
			fBbEvery: "定时刷新",
			fBbTapRefresh: "点球刷新",
			fBbTapMin: "两次点击的最短间隔",
			bbFollow: "跟随主题",
			bbTokenHint: "点下面的词可以加进去",
			bbPreviewHint: "球旁边会显示成这样",
			bbRemove: "移除",
			bbEmpty: "（还没有内容）",
			bbReset: "恢复默认",
			bbTokBalance: "余额",
			bbTokRecharge: "充值",
			bbTokBonus: "赠送",
			bbTokCurrency: "币种",
			bbTokCost: "本次",
			bbTokCostSession: "本会话",
			bbOff: "关闭",
			bbMinute: " 分钟",
			bbAnchorTR: "右上",
			bbAnchorTL: "左上",
			bbAnchorBR: "右下",
			bbAnchorBL: "左下",
			bbAnchorAbove: "正上",
			bbAnchorBelow: "正下",
			kbTitle: "键盘避让",
			kbRowSwitch: "键盘避让",
			kbRowPerm: "无障碍权限",
			kbGrant: "去开启权限",
			kbNextDisabled: "当前关着。打开后，键盘弹出时悬浮球会自动避让，不会挡在输入区前面。",
			kbNextNeedPerm: "还差一步：需要「无障碍」权限才知道键盘弹没弹出、弹多高。点右边按钮去系统设置里打开「DSH 键盘哨兵」。",
			kbNextAllGood: "已就绪：键盘弹出时悬浮球会被弹开，落回键盘上方。",
			kbNextShellSlow: "能用，但走的是特权通道（dumpsys 轮询，延迟约 2 秒）。开了无障碍会快一个数量级，键盘一弹就避让。",
			kbNextNoSource: "两条检测路径都不可用：没开无障碍，也没有 Shizuku 或 root。下面任选一条开通后才能启用。",
			kbRowSource: "检测方式",
			kbSourceA11y: "无障碍（快）",
			kbSourceShell: "特权通道（慢）",
			kbSourceNone: "无",
			kbHowTitle: "为什么需要无障碍",
			kbHow1: "普通应用拿不到「键盘是否弹出、弹多高」—— 只有无障碍服务能读输入法窗口的位置和尺寸。",
			kbHow2: "它只读取窗口的位置与大小，不读取窗口内容，也不记录按键或你输入的文字。",
			kbHow3: "开启后悬浮球会在键盘弹出时被弹开、落回键盘上方；关掉开关则完全不干预。",
		};
		const MP_BUBBLE_EN = {
			nav: "Floating bubble",
			title: "Floating bubble",
			intro: "When the app goes to the background, a round bubble floats on screen showing what the AI is doing, with a text box to send messages. Useful for device automation — watch progress without switching apps.",
			statusTitle: "Current status",
			statusOn: "Enabled",
			statusOff: "Not enabled",
			statusUnknown: "Checking",
			rowSwitch: "Switch",
			rowPerm: "Overlay permission",
			on: "On",
			off: "Off",
			granted: "Granted",
			notGranted: "Not granted",
			enable: "Turn on",
			disable: "Turn off",
			grant: "Grant",
			recheck: "Re-check",
			nextNeedPerm: "One step left: the system hasn't allowed this app to draw over other apps. Tap the button to open the setting.",
			nextNeedEnable: "Permission is ready — just turn the switch on.",
			nextAllGood: "All set. Send the app to the background to see the bubble.",
			howTitle: "How it works",
			how1: "The bubble appears only when the app is in the background — it never gets in your way inside the app.",
			how2: "Drag to move it; on release it snaps to the nearest screen edge and remembers the position.",
			how3: "Tap to expand: current action and recent output on top, a text box and Send below.",
			how4: "\"Open\" returns to the app; \"Collapse\" goes back to the round bubble.",
			notesTitle: "Notes",
			note1: "\"Display over other apps\" is a special permission — Android does not allow an app to request it at runtime; you must enable it in system settings.",
			note2: "The bubble is fed by the foreground service's status, about once per second. No extra battery cost — the engine is already running.",
			note3: "Messages sent from the bubble go through the same official composer and land in the current session, exactly as if you typed them in the app.",
			noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot control the bubble.",
			motionTitle: "Throw feel",
			motionIntro: "Make the bubble behave like a real little pet: fling it and it flies, bounces off walls, and squashes on landing.",
			throwName: "Drag throw",
			throwHint: "Flick your finger across the bubble and release — it flies off in that direction.",
			shakeName: "Shake to throw",
			shakeHint: "Shake the phone and the bubble gets flung, as if thrown by hand. No permission needed.",
			imeName: "Keyboard launch",
			imeHint: "When the keyboard opens, the bubble shoots up from the bottom so it never gets covered.",
			imeLocked: "Unavailable",
			imeLockedWhy: "This needs to read the system keyboard state, which normal apps cannot access. Enable Shizuku or root to turn it on.",
			on2: "On",
			off2: "Off",
			panelTitle: "Look & feel",
			panelIntro: "Everything the bubble supports, tunable here. Changes apply instantly. Tap a group to expand or collapse it.",
			panelUnavailable: "Could not read the current appearance (native bridge unavailable).",
			collapse: "Collapse",
			expand: "Expand",
			autoColor: "Auto",
			panelBgAuto: "Transparent",
			autoSize: "Auto",
			neverHide: "Never",
			grpState: "Form",
			grpLook: "Size & shape",
			grpColor: "Colors",
			grpMotion: "Motion & interaction",
			grpAnim: "Animation & indicator",
			grpPanel: "Expanded panel",
			fState: "Default form",
			stAuto: "Auto",
			stCollapsed: "Collapsed",
			stExpanded: "Expanded",
			fSize: "Diameter",
			fShape: "Shape",
			shapeCircle: "Circle",
			shapeSquare: "Square",
			shapeRounded: "Rounded",
			fCorner: "Corner radius",
			fOpacity: "Overall opacity",
			fBgOpacity: "Background opacity",
			fAvatarOpacity: "Avatar opacity",
			fAvatarScale: "Avatar scale",
			fBorderWidth: "Border width",
			fBorder: "Border color",
			fBg: "Background color",
			fBg2: "Gradient color",
			fEdgeSnap: "Dock to edge",
			fMoveEnabled: "Wanders on its own",
			fMoveInterval: "Wander interval",
			fMoveMin: "Min step",
			fMoveMax: "Max step",
			fFlipOnTurn: "Flip when turning",
			fFrameInterval: "Frame interval",
			fSpin: "Constant rotation",
			fPulse: "Breathing pulse",
			fShowDot: "Show status dot",
			fStatusImageSize: "Status image size",
			fPanelWidth: "Panel width",
			fPanelHeight: "Panel height",
			fStatusAutoHide: "Status bar auto-hide",
			fPanelBg: "Panel background",
			fPanelText: "Panel text",
			fPanelAccent: "Panel accent",

			grpBalance: "Balance badge",
			bbEnabledHint: "Show your DeepSeek balance next to the ball. Off = takes no space at all.",
			fBbEnabled: "Show balance badge",
			fBbAnchor: "Position",
			fBbTemplate: "Text",
			fBbGap: "Distance from ball",
			fBbFontSize: "Font size",
			fBbRadius: "Corner radius",
			fBbPaddingH: "Side padding",
			fBbPaddingV: "Top/bottom padding",
			fBbMaxWidth: "Max width",
			fBbBorderWidth: "Border width",
			fBbBg: "Background",
			fBbFg: "Text colour",
			fBbBorder: "Border colour",
			fBbCostFlash: "Pulse when spending",
			fBbHideIdle: "Only when it cost something",
			fBbTurnEnd: "Refresh after each turn",
			fBbEvery: "Timed refresh",
			fBbTapRefresh: "Refresh on tap",
			fBbTapMin: "Min gap between taps",
			bbFollow: "Theme",
			bbTokenHint: "Tap a field to add it",
			bbPreviewHint: "It will look like this next to the ball",
			bbRemove: "Remove",
			bbEmpty: "(empty)",
			bbReset: "Reset",
			bbTokBalance: "Balance",
			bbTokRecharge: "Recharge",
			bbTokBonus: "Bonus",
			bbTokCurrency: "Currency",
			bbTokCost: "This turn",
			bbTokCostSession: "This session",
			bbOff: "Off",
			bbMinute: " min",
			bbAnchorTR: "Top right",
			bbAnchorTL: "Top left",
			bbAnchorBR: "Bottom right",
			bbAnchorBL: "Bottom left",
			bbAnchorAbove: "Above",
			bbAnchorBelow: "Below",
			kbTitle: "Keyboard avoidance",
			kbRowSwitch: "Keyboard avoidance",
			kbRowPerm: "Accessibility permission",
			kbGrant: "Grant",
			kbNextDisabled: "Currently off. Turn it on and the bubble will get out of the keyboard's way when it appears.",
			kbNextNeedPerm: "One step left: \"Accessibility\" is required to know whether the keyboard is up and how tall it is. Tap the button to enable \"DSH Keyboard Sentinel\" in system settings.",
			kbNextAllGood: "Ready: the bubble is pushed away when the keyboard appears and lands above it.",
			kbNextShellSlow: "Works, but via the privileged channel (dumpsys polling, ~2s latency). Enabling accessibility makes it an order of magnitude faster.",
			kbNextNoSource: "Neither detection path is available: accessibility is off and there is no Shizuku or root. Enable one of them below first.",
			kbRowSource: "Detection",
			kbSourceA11y: "Accessibility (fast)",
			kbSourceShell: "Privileged (slow)",
			kbSourceNone: "None",
			kbHowTitle: "Why accessibility",
			kbHow1: "A normal app cannot know whether the keyboard is shown or how tall it is — only an accessibility service can read the IME window's position and size.",
			kbHow2: "It reads window position and size only; no window content, no keystrokes, no typed text.",
			kbHow3: "When enabled, the bubble bounces off the keyboard and rests above it; when disabled, it is left completely alone.",
		};

		const MP_PRIV_EN = {
			nav: "Privileged access",
			title: "Privileged access (uid 2000 / root)",
			intro: "When enabled, the AI can run commands as a higher identity when it needs to — screenshots, synthetic taps, uninstalling apps, changing system settings. Everything still defaults to the app's own identity; the AI decides when to escalate.",
			statusTitle: "Current status",
			statusOn: "Enabled",
			statusOff: "Not enabled",
			statusUnknown: "Checking",
			recheck: "Re-check",
			checking: "Checking…",
			failed: "Check failed",
			rowRunner: "Execution channel",
			rowKey: "ADB key",
			rowIdentity: "Effective identity",
			ready: "In place",
			missing: "Missing",
			imported: "Imported (PC key)",
			generated: "Generated (pairing needed)",
			paired: "Paired",
			keyOriginUnknown: "Origin unknown",
			notImported: "Not imported",
			justImported: "just imported",
			uidOk: "uid 2000",
			uidApp: "App identity (not escalated)",
			chStable: "fixed port (survives wireless debugging off)",
			chWireless: "wireless debugging (breaks when toggled off)",
			bytes: "bytes",
			guideTitle: "How to enable it",
			step1: "Connect the phone to a computer with a USB cable. On first connect the phone asks \"Allow USB debugging?\" — tick \"Always allow\" and confirm.",
			step2: "Open a terminal on the computer and run the command below — it makes the phone's adbd listen on port 5555. On Android 10 you must redo this after every reboot.",
			step3: "Then run the command below to push the computer's already-authorized key to the phone's shared storage (on macOS / Linux replace %USERPROFILE%\\.android\\adbkey with ~/.android/adbkey).",
			step4: "Come back to this page and tap \"Re-check\" above. The app moves the key into its private directory and immediately deletes the hand-off file from shared storage.",
			cmd2: "adb tcpip 5555",
			cmd3: "adb push %USERPROFILE%\\.android\\adbkey /sdcard/dsh_adbkey",
			copy: "Copy",
			copied: "Copied",
			copyFailed: "Copy failed",
			notesTitle: "Things to know",
			note1: "The key is equivalent to control of this device. The app keeps it only in its private directory and deletes the /sdcard hand-off file right after importing.",
			note2: "Uninstalling the app wipes the key; you will need to repeat the steps above.",
			note3: "Commands run as the privileged identity bypass the sandbox — that identity cannot read the app's private directory, so the write sandbox and the link translation layer do not apply to it.",
			// ── Wireless debugging (Android 11+): the no-computer path ──
			wlTitle: "Wireless debugging (Android 11+, no computer)",
			wlIntro: "Android 11 and later ship \"Wireless debugging\", which lets this app obtain the privileged identity on its own — no cable, no computer, and it survives reboots. A one-time pairing is required.",
			wlStep1: "In system Settings → Developer options, turn on the \"Wireless debugging\" switch.",
			wlStep2: "Tap \"Pair device with pairing code\" — you will see a 6-digit code and a port. Leave that screen open, then come back here and tap the button below.",
			wlStep3: "A pairing notification appears in the notification shade. Pull it down and reply with the 6-digit code right in the notification (no need to switch back to this app).",
			wlStep4: "Once pairing succeeds, come back here and tap \"Re-check\" — the identity should become uid 2000. It stays valid from then on; no re-pairing needed.",
			wlPair: "Pair wireless debugging",
			wlCancel: "Cancel pairing",
			wlPairHint: "The pairing code is shown by the system and can only be typed by a human — that is a requirement of the SPAKE2 protocol, and no app (Shizuku included) can fill it in for you.",
			wlPort: "Wireless port",
			wlPortNone: "Not found (wireless debugging may be off)",
			wlNote: "Use either this or the computer-based guide above. Wireless debugging is easier but only exists on Android 11+; on Android 10 only the computer route works.",
			wlTcp: "Switch to fixed port",
			wlTcpHint: "After switching to a fixed port the channel no longer depends on the wireless debugging toggle — turning wireless debugging or Wi-Fi off will not break it. It re-switches automatically after a reboot.",
			wlNoBridge: "The native pairing entry point is unavailable in this environment (the DSHAndroid bridge is missing), so pairing cannot be started.",
			wlNoNotify: "Pairing is driven entirely from the notification shade (prompt → code entry → cancel), but this app's notifications are turned off, so pairing cannot proceed. Enable notifications under Settings → Apps → DSH → Notifications, then retry.",
			wlPortStale: "Stale (wireless debugging may be off)",
			recommended: "Recommended",
			wlUnsupported: "This device runs Android 10, which has no \"Wireless debugging\" — that feature starts at Android 11. Use the computer-based guide below; it is the only workable path on this device.",
			pcTitlePrimary: "Setup guide (use this on Android 10)",
			pcTitle: "Use the computer-based guide instead (Android 10, or no wireless debugging)",
			pcIntro: "If there is no \"Wireless debugging\" entry in system settings (Android 10 and below), or pairing keeps failing, connect a USB cable and run the computer-based guide once.",
			setupTitle: "Setup guide (already enabled — normally not needed)",
			showDetails: "Show details",
			hideDetails: "Hide details",
			nextAllGood: "Ready to use.",
			nextPairHint: "One step left: tap the button to finish wireless pairing.",
			nextSwitchHint: "Switching to a fixed port is recommended so turning wireless debugging off will not break it.",
			nextChoose: "Not enabled yet. Follow the \"Wireless debugging\" steps below — no computer needed.",
			nextChoosePc: "Not enabled yet. Follow the computer-based guide below — this device runs Android 10, so that is the only way.",
			// Probe hasn't returned but the UI passed its soft deadline (8s). Say both
			// "you don't have to wait" and "what you can do" — never just "Checking…".
			softPending: "Still probing (this can take a while when adbd is restarting). You can start pairing right away instead of waiting.",
			softPendingPc: "Still probing (this can take a while when adbd is restarting). You can follow the computer-based guide below instead of waiting.",
			// ── Cause-specific next steps (see the Chinese dictionary for the rationale) ──
			nextRevoked: "This key's authorisation was revoked by the device (usually via \"Revoke USB debugging authorisations\"). Pair once more.",
			nextUnauthorized: "The key has not been authorised by the device yet. Tap the button to pair once.",
			nextNoKey: "No ADB key yet. Tap the button to pair once — no computer needed.",
			nextNoKeyPc: "No ADB key yet. Follow the computer-based guide below — this device runs Android 10 and has no wireless debugging.",
			nextWirelessStale: "The wireless debugging port went stale (wireless debugging was turned off or restarted). Turn it back on, or tap the button to switch to the fixed port.",
			nextNoChannel: "Neither the fixed port nor wireless debugging is available. Turn on system \"Wireless debugging\", or tap the button to try a switch.",
			nextAdbMissing: "The bundled adb toolchain is missing, so the wireless route cannot work. Installing an app update fixes it.",
			nextKeyBlocked: "The key is not trusted by the device, so the wireless route was skipped to avoid repeated authorisation prompts. Pair again.",
			nextTimeout: "The probe timed out — adbd is most likely still restarting. Wait a few seconds and tap \"Re-check\" again.",
			nextPair: "Pair again",

			// ── root (uid 0) ──────────────────────────────────────
			// ⚠️ This block must exist: the `root` tool is only registered when root is
			//   detected, so on a non-rooted device the user sees no trace of it at all
			//   and cannot tell "this device has no root" from "root exists but was not
			//   detected". Both the state and the reason have to be visible.
			rootTitle: "root access (uid 0)",
			rootOn: "Enabled",
			rootDenied: "Not authorized",
			rootAbsent: "No root",
			rootUnknown: "Not checked",
			rootRowState: "Status",
			rootRowDetail: "Probe result",
			rootRowAt: "Checked at",
			rootRecheck: "Re-check",
			rootChecking: "Checking…",
			rootNextOn: "The root tool is registered — the AI can run commands as uid 0 directly (read/write /data, remount, iptables, and so on).",
			rootNextOnPendingReload: "uid 0 was detected, but the root tool is only mounted at startup — it is not available in this session yet. Restart the app to use it.",
			rootNextDenied: "This device has su, but the app did not obtain uid 0. Grant \"DSH\" root access in Magisk / SuperSU or similar, then tap \"Re-check\".",
			rootNextAbsent: "This device has no root (no su found either). Root-only features are unavailable; everything else is unaffected — the uid 2000 channel above is unrelated and still works.",
			rootNextUnknown: "Not checked yet. Tap \"Re-check\" to run a probe (the first one may show a root authorization dialog).",
			// ⚠️ Plain-text rendering (React puts this straight into the DOM) — no
			//   Markdown markers, they would show up literally.
			rootNote: "The root tool is registered only when uid 0 is detected. Detection actually runs su -c id -u and checks whether the output is 0, rather than looking for a su file — a placeholder su, Magisk installed but not authorized, and a root that broke after an upgrade all leave the file present while escalation fails. The result is cached for 24 hours.",
			rootDetailNever: "(none)",

			// ── Shizuku (second source of uid 2000, no adbd needed) ──
			// ⚠️ This block exists to fix the page's biggest trap: both built-in
			//   routes assume a reachable adbd. On Android 10 and below there is no
			//   wireless debugging and persist.adb.tcp.port is not writable, so every
			//   reboot needs the PC to re-run `adb tcpip 5555`. Shizuku goes over
			//   binder — no adbd, no adbkey check, survives reboots.
			shizukuTitle: "Shizuku (no computer needed)",
			// Four states, four different next steps. Never collapse them into one
			// boolean: "not installed" must lead to a download, "installed but
			// stopped" to opening the app, and only "running but unauthorized" to
			// the grant button.
			shizukuAbsent: "Not installed",
			shizukuInstalled: "Not running",
			shizukuUngranted: "Not authorized",
			shizukuGranted: "Ready",
			shizukuNextAbsent: "Shizuku is not installed on this device. Install it to enable the privileged identity without a computer.",
			shizukuNextStopped: "Shizuku is installed but its service is not running. Open the Shizuku app and tap Start, then come back.",
			shizukuNextUngranted: "The Shizuku service is running. Tap the button to authorize, then tap Allow in the dialog that appears.",
			shizukuNextGranted: "The Shizuku channel is ready (uid 2000). It does not depend on adbd and keeps working after a reboot.",
			shizukuNextGrantedStale: "Shizuku is authorized, but the status above is stale. Tap the button to re-check (no need to restart the app).",
			shizukuDownload: "Download Shizuku",
			shizukuOpen: "Open Shizuku",
			shizukuGrant: "Authorize",
			shizukuGranting: "Waiting for authorization…",
			shizukuRecheck: "Re-check",
			shizukuRowService: "Service",
			shizukuRowUid: "Service identity",
			shizukuRowVersion: "Service version",
			shizukuServiceRunning: "Running",
			shizukuServiceStopped: "Not running",
			shizukuServiceAbsent: "Not installed",
			shizukuStep1: "Install Shizuku and open it.",
			shizukuStep2: "Follow Shizuku's own instructions to start the service — usually it asks you to run adb once from a computer (or use root). This is needed only once; it starts itself on every boot afterwards.",
			shizukuStep3: "Come back to this page, tap Authorize, and tap Allow in the dialog that appears.",
			shizukuStep4: "Tap Re-check here — the identity should become uid 2000.",
			shizukuNote: "Shizuku needs adb (or root) only for the one-time service start; after that it stays resident and this app can use it anytime. That is unlike the fixed-port route, which needs the PC to re-run adb tcpip 5555 after every reboot. Commands still bypass the sandbox.",
			shizukuNoBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot control Shizuku.",
			chShizuku: "Shizuku (no adbd, survives reboots)",

			// ── Route cards (unified structure after the rework) ──
			pathTitle: "How to enable it",
			pathIntro: "Any one of the three routes is enough — which one applies depends on this device, and each card's badge shows its current state.",
			pathReady: "Available",
			pathUnavailable: "Unavailable",
			pathTodo: "Needs setup",
			pathShowSteps: "Show steps",
			pathHideSteps: "Hide steps",
			wlAbsentNote: "This device runs Android 10, which has no \"Wireless debugging\" (it starts at Android 11), so this route does not exist here — no need to look for it in settings.",

			// ── Tab layout ──────────────────────────────────────────
			tabAdb: "ADB",
			tabShizuku: "Shizuku",
			tabRoot: "root",
			adbIntro: "Connect to the device's own adbd with the bundled adb to run commands as shell. Two routes: pair over \"Wireless debugging\" on Android 11+, or a one-time PC bootstrap on Android 10 and below.",
			rootIntro: "root is uid 0, one level above uid 2000. It is independent of the two channels above: without root, uid 2000 still works.",
		};

		// 全内联样式：设置 dialog 里的既有教训是「匹配 dialog 的 CSS 规则会把弹窗
		// 推出屏幕」，而 section 内容住在那个 dialog 里，所以这里不新增 CSS 规则。
		const MP_PRIV_S = {
			section: { maxWidth: "760px", display: "flex", flexDirection: "column", gap: "12px", color: "var(--dsw-alias-label-primary, #222)" },
			heading: { margin: 0, fontSize: "18px", fontWeight: 600 },
			intro: { margin: 0, fontSize: "13px", lineHeight: "20px", color: "var(--dsw-alias-label-tertiary, #888)" },
			card: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "12px 14px", display: "flex", flexDirection: "column", gap: "10px" },
			cardHead: { display: "flex", alignItems: "center", gap: "8px" },
			cardTitle: { margin: 0, fontSize: "15px", fontWeight: 600 },
			badge: { marginLeft: "auto", fontSize: "12px", fontWeight: 600, padding: "3px 9px", borderRadius: "999px", whiteSpace: "nowrap" },
			btn: { border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
			row: { display: "flex", alignItems: "baseline", gap: "8px", fontSize: "13px", lineHeight: "19px" },
			dot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%", transform: "translateY(-1px)" },
			rowLabel: { flex: "0 0 auto", color: "var(--dsw-alias-label-tertiary, #888)" },
			rowValue: { flex: "1 1 auto", minWidth: 0, wordBreak: "break-word" },
			steps: { margin: 0, padding: "0 0 0 20px", display: "flex", flexDirection: "column", gap: "9px" },
			step: { fontSize: "13px", lineHeight: "20px" },
			// 命令块：独立成行 + 可横向滚动。窄屏上命令很长，
			// 换行会把路径断在中间导致抄错，所以宁可横向滚。
			// ⚠️ 这里**刻意不**设 user-select：本插件的全局 CSS 是
			//   `[data-mobile] * { user-select: none !important }`，内联会被压掉，
			//   写了也是死代码。**正因如此才必须有复制按钮** —— 命令长按选不中，
			//   而这台设备上用户是在电脑终端里敲，只能照着手机屏幕抄。
			cmdRow: { display: "flex", alignItems: "stretch", gap: "6px", marginTop: "6px" },
			cmd: { flex: "1 1 auto", minWidth: 0, display: "block", padding: "7px 9px", borderRadius: "6px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)", fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "12px", lineHeight: "18px", overflowX: "auto", whiteSpace: "pre" },
			// 固定宽度：文案在「复制 / 已复制 / 复制失败」之间切换，
			// 不固定的话按钮会随字数变宽，把左边的命令块挤得来回跳。
			cmdBtn: { flex: "0 0 auto", display: "flex", alignItems: "center", justifyContent: "center", minWidth: "62px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "12px", padding: "0 8px", borderRadius: "6px", cursor: "pointer", whiteSpace: "nowrap" },
			notes: { margin: 0, padding: "0 0 0 18px", display: "flex", flexDirection: "column", gap: "6px" },
			note: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
			error: { margin: 0, fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },

			// ── 重设计后新增 ────────────────────────────────────────
			// 首屏「下一步」块：把「点哪里」摆在结论正下方，用户不必自己从
			// 三行技术明细里翻译出该做什么。
			stack: { display: "flex", flexDirection: "column", gap: "12px" },
			summary: { margin: 0, fontSize: "13px", lineHeight: "19px" },
			next: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", padding: "9px 10px", borderRadius: "8px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)" },
			nextText: { flex: "1 1 160px", minWidth: 0, fontSize: "13px", lineHeight: "19px" },			// root 卡片专用的「下一步」文案样式：与上面那条的区别只有换行基线。
			//
			// ⚠️ **不复用 `nextText`**：它的 `flex: "1 1 160px"` 在 360 CSS px 宽的
			//   卡片里会把长文案挤成每行十来个字的一窄列（root 那三条文案都挺长）。
			//   用 100% 作基线则文案先占满整行、按钮自然掉到下一行，读起来才是一段话。
			//   之所以单独开一条而不是改上面那条：上面那条是 uid 2000 卡片在用的，
			//   改它会连带改掉那张已经定型的卡片 —— 只修自己新加的那张。
			nextTextWide: { flex: "1 1 100%", minWidth: 0, fontSize: "13px", lineHeight: "19px" },
			actions: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
			// 主按钮：整页只有「配对」和「下一步」用实心，避免到处都在喊。
			btnPrimary: { border: "0.5px solid transparent", background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", color: "#fff", font: "inherit", fontSize: "13px", fontWeight: 600, padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
			// 次要动作（取消配对 / 查看详情）用弱化样式，和主按钮拉开层级。
			btnQuiet: { border: "0.5px solid transparent", background: "transparent", color: "var(--dsw-alias-label-tertiary, #888)", font: "inherit", fontSize: "13px", padding: "6px 10px", borderRadius: "8px", cursor: "pointer" },
			details: { display: "flex", flexDirection: "column", gap: "8px", paddingTop: "4px", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
			// 折叠块：引导与注意事项默认收起，让首屏只剩结论。
			fold: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "10px 14px" },
			foldHead: { fontSize: "14px", fontWeight: 600, cursor: "pointer" },
			foldBody: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "10px" },
			// ⚠️ 这里**不能**引用 `MP_PRIV_TONE` —— 它定义在本对象**之后**，
			// 在模块求值期引用会抛 `Cannot access 'MP_PRIV_TONE' before
			// initialization`（整个插件 import 失败、界面直接白屏，踩过一次）。
			// 所以颜色写死，与 MP_PRIV_TONE.on 保持一致。
			tagRecommended: { marginLeft: "auto", fontSize: "11px", fontWeight: 600, padding: "2px 8px", borderRadius: "999px", color: "#2f9e44", background: "rgba(47,158,68,0.12)", whiteSpace: "nowrap" },

			// ── 布局重做新增 ────────────────────────────────────────
			// 三条路径之间的分组标题。比 `heading`（h2，18px）小一号 ——
			// 它是分节内部的次级标题，与分节标题同分量会让层级糊掉。
			groupTitle: { margin: "4px 0 0", fontSize: "15px", fontWeight: 600 },
			// 卡片**内部**的折叠块：与顶层 `fold`（自己有边框、独立成块）区分开 ——
			// 卡片里再套一个带边框的块会出现「框里套框」的双线，看着很脏。
			// 这里只用一条上分隔线。
			foldInline: { borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)", paddingTop: "8px" },

			// ── 标签页（ADB / Shizuku / root）───────────────────────
			//
			// 下划线式标签：底边一条通栏分隔线，选中项盖一条 2px 主色指示条。
			// ⚠️ 指示条靠 `marginBottom: "-0.5px"` **压在**那条分隔线上 —— 不压的话
			//   选中项的下划线会比容器底边高半个像素，视觉上「浮」起来。
			//   这是纯 CSS 手法，不需要额外的定位容器。
			tabBar: {
				display: "flex", alignItems: "stretch", gap: "2px",
				borderBottom: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)",
			},
			tab: {
				flex: "1 1 0", minWidth: 0,
				display: "flex", alignItems: "center", justifyContent: "center", gap: "6px",
				border: "none", background: "transparent",
				color: "var(--dsw-alias-label-tertiary, #888)",
				font: "inherit", fontSize: "14px",
				padding: "9px 4px 10px", cursor: "pointer",
				borderBottom: "2px solid transparent", marginBottom: "-0.5px",
			},
			tabActive: {
				color: "var(--dsw-alias-label-primary, #222)", fontWeight: 600,
				borderBottom: "2px solid var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
			},
			// 标签上的状态点：进页面之前就能看出哪条通道是通的，不必逐个点开。
			tabDot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%" },
			// 窄屏上「Shizuku」比「ADB」宽得多，不省略的话三个标签宽度会被撑歪。
			tabLabel: { minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
			// 标签页内容区：与常驻结论条拉开一点距离，让「上面是总览、下面是当前页」成立。
			panel: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "2px" },

			// ── 「交流群」分节 ──────────────────────────────────────
			// 二维码容器：居中 + 一点上下留白，避免图片紧贴卡片边框。
			qrWrap: { display: "flex", justifyContent: "center", padding: "4px 0 2px" },
			// 显示尺寸取 240px 且不超过卡片宽度。
			// ⚠️ 内联源图是 640px 的 WebP，缩到 240 CSS px 显示。实测（dpr=3 渲染
			//   截图后交给 zxing 解码）400px 的源图就已够扫，640px 是留了余量：
			//   用户可能会用另一台设备对着屏幕拍，拍摄角度/对焦都会损失有效分辨率。
			// ⚠️ 不设 `imageRendering: "pixelated"`：那是为放大低分辨率图准备的，
			//   这里是从 640 缩到 240（缩小），平滑重采样反而更利于扫描识别。
			qrImg: { width: "240px", maxWidth: "100%", height: "auto", display: "block", borderRadius: "8px", background: "#fff" },
			// 群号用等宽字体且**放大一号**：这是要照着念/照着手输的数字，
			// 与周围的说明文字同等字号会显得不够醒目。
			mpGroupNo: { flex: "1 1 auto", minWidth: 0, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "15px", fontWeight: 600, letterSpacing: "0.5px" },
			// ── 「甩动手感」开关行 ───────────────────────────────────
			// 一行一个开关：左边名字+说明，右边一枚自绘开关。
			// ⚠️ 不用 <input type="checkbox">：本插件的全局 CSS 会把原生控件压成
			//   系统默认样式，在深色主题下几乎看不见。自绘只用两个 div。
			swRow: { display: "flex", alignItems: "flex-start", gap: "12px", padding: "10px 0", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
			swMain: { flex: "1 1 auto", minWidth: 0, display: "flex", flexDirection: "column", gap: "3px" },
			swName: { fontSize: "14px", fontWeight: 600 }, 
			swHint: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
			swWhy: { fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },
			// 轨道：关=灰、开=主色。宽高固定，thumb 用绝对定位左右滑。
			swTrack: { flex: "0 0 auto", position: "relative", width: "44px", height: "26px", borderRadius: "999px", border: "none", padding: 0, cursor: "pointer", transition: "background 0.18s ease", marginTop: "2px" },
			swThumb: { position: "absolute", top: "3px", width: "20px", height: "20px", borderRadius: "50%", background: "#fff", boxShadow: "0 1px 3px rgba(0,0,0,0.28)", transition: "left 0.18s ease" },
			// ── 「外观控制面板」用的控件 ─────────────────────────────
			// 分组标题：比 cardTitle 轻一档，用来在长面板里切段。
			grpTitle: { margin: "14px 0 2px", fontSize: "13px", fontWeight: 700, letterSpacing: "0.4px", color: "var(--dsw-alias-label-tertiary, #888)", textTransform: "uppercase" },
			// 一行控件：左标签 + 右值。比 swRow 紧凑（无下边框），因为同类行会连排。
			// ⚠️ 标签列 116px（不是 104px）：本页最长的标签是「显示余额徽标」6 个汉字，
			//   14px 字号下约需 84px，加上行内 padding 与 gap，104px 会被 `ellipsis`
			//   截成「显示余额徽标…」。给到 116px 刚好放下，又不至于挤压右侧控件。
			ctlRow: { display: "grid", gridTemplateColumns: "116px 1fr auto", alignItems: "center", gap: "10px", padding: "7px 0" },
			ctlLabel: { minWidth: 0, fontSize: "14px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
			ctlValue: { flex: "0 0 auto", fontSize: "13px", fontVariantNumeric: "tabular-nums", color: "var(--dsw-alias-label-tertiary, #888)", minWidth: "46px", textAlign: "right" },
			// 滑块：原生 range，但只保留轨道与滑块，颜色跟主题走。
			// ⚠️ accent-color 是关键：不设的话安卓 WebView 会画成系统蓝，与主题撞色。
			slider: { flex: "1 1 0", minWidth: 0, accentColor: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", height: "28px", margin: 0 },
			// 分段选择器：等宽按钮排一行，选中项反色。比 <select> 好按，也比它好看。
			/**
			 * 分段选择器。
			 *
			 * ⚠️ **不能用 "whiteSpace: nowrap" + 等分平分**：本页的「出现位置」有 6 个
			 *   中文选项，等分后每格只有约 48px，而「右上」「右下」这类两字标签在
			 *   13px 字号下需要约 34px + 左右内边距，于是全部被 "ellipsis" 截成
			 *   「右…」「左…」—— 六个按钮长得一模一样，完全没法选。
			 *
			 *   改为：**允许换行**（"flexWrap"）+ 按钮按内容宽度（"0 0 auto"），
			 *   放不下就折到第二行。宁可多占一行，也不能让文字读不出来。
			 */
			seg: { display: "flex", flexWrap: "wrap", flex: "1 1 0", minWidth: 0, gap: "6px" },
			segBtn: { flex: "0 0 auto", minWidth: 0, border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", textAlign: "center", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 11px", borderRadius: "8px", cursor: "pointer", whiteSpace: "nowrap" },
			segBtnOn: { background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", borderColor: "transparent", color: "#fff", fontWeight: 600 },
			// 颜色选择：圆形色块 + 原生取色器叠在上面（透明点击层）。
			// ⚠️ 不用 type=color 直接可见：它渲染出来是个方块，跟整页的圆角风格不搭。
			/**
			 * 色块圆点。
			 *
			 * ⚠️⚠️ **"position: relative" 与固定宽高必须留在这一层**，因为里面的
			 *   "<input type="color">" 是 "position: absolute; inset: 0"，它靠这个
			 *   元素建立包含块。
			 *
			 * 踩过：真机上这三个色块的 input 尺寸变成了 **360×764（整个面板）**，
			 * 于是透明的 input 盖住了下面所有行 ——
			 *   ① 页面无法上下滑动（滑动事件被它吃掉）；
			 *   ② 点分组标题「收起」时命中的是它，弹出系统颜色选择器，分组永远收不起来。
			 * 根因就是包含块没建立起来时 "inset: 0" 会退回最近的可定位祖先（= 整个面板）。
			 *
			 * ⚠️ 另外补 "display: block" + "boxSizing: border-box"：这两个色块在
			 *   "display: grid" 的 "ctlRow" 里，"flex: 0 0 auto" 对 grid 子项**无效**，
			 *   不加的话 span 会按内容宽度撑开，"width/height" 反而不稳。
			 */
			swatchWrap: {
				position: "relative",
				display: "block",
				flex: "0 0 auto",
				width: "30px",
				height: "30px",
				boxSizing: "border-box",
				borderRadius: "50%",
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				overflow: "hidden",
			},
			swatchInput: {
				position: "absolute",
				left: 0,
				top: 0,
				width: "100%",
				height: "100%",
				// ⚠️ 不要用 `inset: 0` —— 旧内核（本 App 目标 chrome74）对 `inset`
				//   简写的支持不完整，退化成不定位时就会铺满整个可定位祖先。
				//   显式写 left/top/width/height 最稳。
				opacity: 0,
				border: "none",
				padding: 0,
				margin: 0,
				cursor: "pointer",
			},
			// 数字输入：比滑块精确，用于尺寸这类要打准的值。
			numInput: { flex: "0 0 auto", width: "64px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", borderRadius: "6px", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "5px 7px", textAlign: "right" },
			// 可折叠分组：长面板必须能折叠，否则一屏塞不下、用户永远滚不到底。
			acc: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", marginTop: "10px", overflow: "hidden" },
			accHead: { display: "flex", alignItems: "center", gap: "8px", padding: "11px 14px", cursor: "pointer", fontSize: "14px", fontWeight: 600, background: "var(--dsw-alias-interactive-bg-hover, #fafafa)" },
			// ── 余额徽标「显示内容」的编辑器 ──────────────────────────────
			//
			// 三段式：预览 → 当前配方（可删）→ 可加的词（已用则禁用）。
			bbEditor: { display: "flex", flexDirection: "column", gap: "8px", padding: "8px 0 4px" },
			bbPreviewRow: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" },
			// 预览用的"假徽标"：故意做得和真实徽标同形（深底浅字、圆角胶囊），
			// 让用户一眼认出"这就是球旁边那个东西"。
			bbPreviewBadge: {
				background: "rgba(28, 30, 34, 0.92)",
				color: "#fff",
				fontSize: "13px",
				padding: "3px 10px",
				borderRadius: "999px",
				fontVariantNumeric: "tabular-nums",
				whiteSpace: "nowrap",
				maxWidth: "100%",
				overflow: "hidden",
				textOverflow: "ellipsis",
			},
			bbTokenRow: { display: "flex", flexWrap: "wrap", gap: "6px", alignItems: "center" },
			// 「可加的词」：浅底、有点击感。
			bbToken: {
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				background: "var(--dsw-alias-bg-layer-2, #f4f4f4)",
				color: "var(--dsw-alias-label-primary, #333)",
				font: "inherit",
				fontSize: "12px",
				padding: "4px 10px",
				borderRadius: "999px",
				cursor: "pointer",
			},
			// 已经用过的词：置灰且不可点，避免同一个词被点进模板十几遍。
			bbTokenUsed: {
				opacity: 0.38,
				cursor: "not-allowed",
				background: "transparent",
			},
			// 「当前配方」里的胶囊：带删除按钮，视觉上比"可加的词"更实。
			bbChip: {
				display: "inline-flex",
				alignItems: "center",
				gap: "3px",
				border: "0.5px solid var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
				background: "rgba(43, 108, 176, 0.10)",
				color: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
				fontSize: "12px",
				padding: "3px 4px 3px 10px",
				borderRadius: "999px",
			},
			// 字面文字（如「剩 」）：不带删除键的普通说明，用中性色。
			bbChipLit: {
				display: "inline-flex",
				alignItems: "center",
				gap: "3px",
				border: "0.5px dashed var(--dsw-alias-border-l2, #dcdcdc)",
				background: "transparent",
				color: "var(--dsw-alias-label-secondary, #666)",
				fontSize: "12px",
				padding: "3px 4px 3px 10px",
				borderRadius: "999px",
			},
			// 未知 token（用户可能在别处写错）：灰底警告色，但不丢。
			bbChipUnknown: {
				display: "inline-flex",
				alignItems: "center",
				gap: "3px",
				border: "0.5px solid #e8590c",
				background: "rgba(232, 89, 12, 0.10)",
				color: "#e8590c",
				fontSize: "12px",
				padding: "3px 4px 3px 10px",
				borderRadius: "999px",
			},
			bbChipX: {
				border: "none",
				background: "transparent",
				color: "inherit",
				font: "inherit",
				fontSize: "14px",
				lineHeight: "1",
				padding: "0 5px",
				cursor: "pointer",
				opacity: 0.75,
			},
			accBody: { display: "flex", flexDirection: "column", padding: "2px 14px 10px" },
			accCount: { marginLeft: "auto", fontSize: "11px", fontWeight: 400, color: "var(--dsw-alias-label-tertiary, #999)" },
		};

		const MP_PRIV_TONE = {
			on: { color: "#2f9e44", bg: "rgba(47,158,68,0.12)" },
			off: { color: "#e8590c", bg: "rgba(232,89,12,0.12)" },
			// 「还不能用，但没坏」—— 目前只有一种情况：密钥是 App 现场生成的，
			// 设备尚未信任，需要走一次配对。用橙色会和「缺失」混淆，所以单列一色。
			warn: { color: "#f08c00", bg: "rgba(240,140,0,0.12)" },
			unknown: { color: "#868e96", bg: "rgba(134,142,150,0.12)" },
		};

		/**
		 * 把一段文本写进系统剪贴板。
		 *
		 * ⚠️ **必须两条路都留**：官方前端跑在 "http://127.0.0.1:3080" 上，
		 * 而 "navigator.clipboard" 只在**安全上下文**（https / localhost 域名 /
		 * file:）里存在 —— "127.0.0.1" 虽是回环地址、Chromium 也把它算作
		 * potentially trustworthy，但 WebView 上这个判定随内核版本而变，
		 * 不能赌。所以先用 "navigator.clipboard"，失败或不存在时退回
		 * "document.execCommand("copy")" + 临时 textarea 的老办法。
		 *
		 * @param {string} text - 要复制的文本。
		 * @returns {Promise<boolean>} 是否成功。
		 */
		function mpCopyText(text) {
			const fallback = () => {
				try {
					const area = document.createElement("textarea");
					area.value = text;
					// 必须在文档里且可聚焦，execCommand 才会作用于它。
					// 用 fixed + 透明把它藏起来，避免点按钮时页面跳动。
					area.setAttribute("readonly", "");
					area.style.position = "fixed";
					area.style.top = "0";
					area.style.left = "0";
					area.style.opacity = "0";
					area.style.pointerEvents = "none";
					document.body.appendChild(area);
					area.select();
					area.setSelectionRange(0, text.length);
					const ok = document.execCommand("copy");
					document.body.removeChild(area);
					return ok;
				} catch (error) {
					console.warn("[dsh-android-mobile] 复制到剪贴板失败:", error && error.message);
					return false;
				}
			};
			try {
				if (navigator.clipboard && navigator.clipboard.writeText) {
					return navigator.clipboard.writeText(text).then(() => true, fallback);
				}
			} catch (error) {
				/* 落到 fallback */
			}
			return Promise.resolve(fallback());
		}

		/**
		 * 「特权权限」分节组件。
		 *
		 * 官方向 "settings.section" 的组件注入 "t"（由注册项的 "locale" 命名空间绑定）
		 * 与 "close"；本组件只用 "t"。
		 *
		 * @param {object} props - 槽位注入面。
		 * @returns {object} React 元素。
		 */
		function mpPrivSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_PRIV_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			// ⚠️ **软截止**：探测真正返回前，界面不能一直锁在「检测中」。
			//
			//   真机反馈的现象：首次安装打开本页，「当前状态」一直显示「检测中」，
			//   既没有结论也没有任何按钮（那个「重新检测」按钮在 loading 期间是
			//   `disabled` 的），只能硬等——而宿主半的探测超时是 **45 秒**，
			//   用户描述为「等几分钟」。
			//
			//   为什么 45 秒砍不掉：那个预算是 runner 最坏路径（三条通道级联 +
			//   adbd 重启）的上限，砍了会把「慢但成功」误判成失败。
			//   但**界面不必陪着一起等** —— 超过这个软截止就先按「还没结论」渲染，
			//   把按钮还给用户；真结果回来时照常覆盖（`phase` 变 ready/error）。
			const SOFT_DEADLINE_MS = 8000;
			const [softExpired, setSoftExpired] = React.useState(false);
			const refresh = React.useCallback(() => {
				let alive = true;
				setState({ phase: "loading" });
				setSoftExpired(false);
				// 到点就把界面从「检测中」解锁。真正的 fetch 不取消：它回来时
				// 下面的 then/catch 会用真实结论覆盖掉这个临时视图。
				const softTimer = setTimeout(() => { if (alive) setSoftExpired(true); }, SOFT_DEADLINE_MS);
				window.fetch(MP_PRIV_API, { cache: "no-store" })
					.then((res) => {
						if (!res.ok) throw new Error("HTTP " + res.status);
						return res.json();
					})
					.then((data) => { if (alive) { clearTimeout(softTimer); setState({ phase: "ready", data }); } })
					.catch((error) => {
						if (alive) {
							clearTimeout(softTimer);
							setState({ phase: "error", message: String((error && error.message) || error) });
						}
					});
				return () => { alive = false; clearTimeout(softTimer); };
			}, []);
			React.useEffect(refresh, [refresh]);

			// ══ root（uid 0）状态 ══════════════════════════════════════════
			//
			// 与上面的 uid 2000 状态**来源不同**：那条走宿主半的 HTTP 路由（探测要
			// spawn runner），而 root 的结论在**原生**侧（探测要起 `su` 进程、可能弹
			// 授权框），所以走 JS 桥 `window.DSHAndroid.rootState()`。
			//
			// ⚠️ 桥上的 `rootState()` **只读缓存、不触发探测** —— 它跑在 WebView 的
			//   JS 线程上，同步探测会把页面卡住。重新探测走 `rootRecheck()`，那是
			//   后台线程，界面靠下面的轮询拿结果。
			const [root, setRoot] = React.useState({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
			const [rootBusy, setRootBusy] = React.useState(false);
			// 在跑的轮询计时器。用 ref 而不是普通变量：`recheckRoot` 是 useCallback，
			// 闭包捕获的必须是一个**跨渲染稳定**的容器，否则每次渲染新建一个 Set，
			// 卸载时的清理就清不到真正的计时器。
			const rootTimers = React.useRef(null);
			if (rootTimers.current === null) rootTimers.current = new Set();

			/** 读一次缓存里的 root 结论（不触发探测）。 */
			const readRoot = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.rootState !== "function") {
					setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
					return;
				}
				try {
					// 桥返回 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
					const raw = bridge.rootState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setRoot({
						known: !!o.known,
						state: String(o.state || ""),
						granted: !!o.granted,
						detail: String(o.detail || ""),
						at: Number(o.at) || 0,
						// ⚠️ 这是「本会话里 root 工具真的挂上了没有」，与 granted 不是一回事：
						//   overlay 只在引擎启动时读一次，重新检测把结论刷成 GRANTED 之后
						//   工具并不会凭空出现。见下面的 rootNextOnPending。
						mounted: !!o.mounted,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取 root 状态失败:", e && e.message);
					setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
				}
			}, []);

			React.useEffect(() => { readRoot(); }, [readRoot]);

			/**
			 * 请求重新检测，然后**轮询**直到结论变化。
			 *
			 * ⚠️ 必须轮询，不能等一个返回值：原生侧是「立即返回、后台线程里探测」，
			 *   因为探测最多要等 5 秒（首次还会弹授权框），同步等会把页面卡死。
			 * ⚠️ 轮询上限（10 秒）要比原生的探测超时（5 秒）宽，否则会在原生还没
			 *   出结论时就把按钮恢复，用户以为检测完了、看到的却是旧结论。
			 * ⚠️ 判据是**时间戳变化**（"at"），不是结论变化：连续两次都测出
			 *   「无 root」时结论字符串一样，只看结论会一直轮询到超时。
			 */
			const recheckRoot = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.rootRecheck !== "function") return;
				let before = 0;
				try {
					const raw = bridge.rootState();
					before = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
				} catch (e) { /* 拿不到旧时间戳就退化成「轮询到超时」，仍然正确 */ }
				try {
					bridge.rootRecheck();
				} catch (e) {
					console.warn("[dsh-android-mobile] 请求重新检测 root 失败:", e && e.message);
					return;
				}
				setRootBusy(true);
				const startedAt = Date.now();
				const timer = window.setInterval(() => {
					const b = window.DSHAndroid;
					let at = 0;
					try {
						const raw = b && b.rootState ? b.rootState() : "{}";
						at = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
					} catch (e) { /* 读失败当作还没出结果，继续等 */ }
					if (at > before || Date.now() - startedAt > 10000) {
						window.clearInterval(timer);
						rootTimers.current.delete(timer);
						readRoot();
						setRootBusy(false);
					}
				}, 700);
				// 记账以便卸载时清理（本函数没有 cleanup 出口，所以交给下面的 effect）。
				rootTimers.current.add(timer);
			}, [readRoot]);

			// 组件卸载时清掉所有在跑的轮询 —— 否则用户切走分节后计时器仍在打桥。
			React.useEffect(() => {
				const timers = rootTimers.current;
				return () => {
					timers.forEach((id) => window.clearInterval(id));
					timers.clear();
				};
			}, []);

			// 从授权界面回来后刷新：给 root 授权是在 Magisk 之类的**别的应用**里完成的，
			// 本页拿不到任何回调，只能靠 visibilitychange / focus（与气泡分节同一手法）。
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) readRoot(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [readRoot]);

			// ══ Shizuku 状态 ═══════════════════════════════════════════════
			//
			// 与上面两块**来源又不同**：Shizuku 的「装了没 / 服务在跑没 / 授权没」
			// 全在**原生**侧（要问 PackageManager、要问 binder），所以走 JS 桥
			// `window.DSHAndroid.shizukuState()`，与 root 同一套语义（只读缓存、
			// 不触发探测，同步返回 JSON 文本）。
			//
			// ⚠️ **四个字段必须分开存、分开判**（见 PageBridge.shizukuState 的表）：
			//   合并成一个布尔的话，「没装」和「装了没启动」会给出同一个结论，
			//   而这两者要引导用户做的事完全不同（去下载 vs 去打开它）。
			const SHIZUKU_EMPTY = { installed: false, binder: false, granted: false, uid: -1, version: 0 };
			const [shizuku, setShizuku] = React.useState(SHIZUKU_EMPTY);
			// 授权是异步的（用户在 Shizuku 自己的窗口里点「允许」），期间按钮要锁住。
			const [shizukuBusy, setShizukuBusy] = React.useState(false);
			const shizukuTimers = React.useRef(null);
			if (shizukuTimers.current === null) shizukuTimers.current = new Set();

			/** 读一次 Shizuku 状态（不触发任何动作）。 */
			const readShizuku = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.shizukuState !== "function") {
					setShizuku(SHIZUKU_EMPTY);
					return;
				}
				try {
					const raw = bridge.shizukuState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setShizuku({
						installed: !!o.installed,
						binder: !!o.binder,
						granted: !!o.granted,
						uid: Number.isFinite(Number(o.uid)) ? Number(o.uid) : -1,
						version: Number(o.version) || 0,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取 Shizuku 状态失败:", e && e.message);
					setShizuku(SHIZUKU_EMPTY);
				}
			}, []);

			React.useEffect(() => { readShizuku(); }, [readShizuku]);

			// 从 Shizuku 授权窗口回来时刷新 —— 那是**别的应用**的界面，本页拿不到
			// 回调，只能靠 visibilitychange / focus（与 root 同一手法）。
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) readShizuku(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [readShizuku]);

			React.useEffect(() => {
				const timers = shizukuTimers.current;
				return () => {
					timers.forEach((id) => window.clearInterval(id));
					timers.clear();
				};
			}, []);

			/**
			 * 点「授权」：拉起 Shizuku 的确认窗口，然后轮询授权结果。
			 *
			 * ⚠️ **必须轮询**：授权结论是 Shizuku 异步给的，原生侧只登记了一个
			 *   监听器（见 "MainActivity.setupShizukuPermissionListener"），
			 *   页面拿不到任何 promise。
			 * ⚠️ 授权成功后再跑一次 "refresh()"："identity" 那条结论来自 runner
			 *   的探测（宿主半 HTTP 路由），与 Shizuku 的授权状态是**两件事** ——
			 *   只更新 Shizuku 状态的话，上面的「当前状态」徽章还停在「未开启」，
			 *   用户会以为授权没生效。**不需要重启 App**：runner 每次执行命令时
			 *   才现连 Shizuku。
			 */
			const grantShizuku = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.shizukuRequestPermission !== "function") {
					window.alert(t("shizukuNoBridge"));
					return;
				}
				try {
					bridge.shizukuRequestPermission();
				} catch (e) {
					console.warn("[dsh-android-mobile] 请求 Shizuku 授权失败:", e && e.message);
					return;
				}
				setShizukuBusy(true);
				const startedAt = Date.now();
				const timer = window.setInterval(() => {
					const b = window.DSHAndroid;
					let granted = false;
					try {
						const raw = b && b.shizukuState ? b.shizukuState() : "{}";
						granted = !!(typeof raw === "string" ? JSON.parse(raw) : (raw || {})).granted;
					} catch (e) { /* 读失败当作还没授权，继续等 */ }
					// ⚠️ 上限 20 秒：用户可能正在那个窗口上犹豫，也可能压根没打开。
					//   到点就解锁按钮并**照常读一次**，让界面显示真实状态 ——
					//   不能停在「等待授权…」上不动。
					if (granted || Date.now() - startedAt > 20000) {
						window.clearInterval(timer);
						shizukuTimers.current.delete(timer);
						readShizuku();
						setShizukuBusy(false);
						if (granted) refresh();
					}
				}, 600);
				shizukuTimers.current.add(timer);
			}, [readShizuku, refresh, t]);

			const data = state.phase === "ready" ? state.data : null;
			// ⚠️ 软截止到期后**不再算 loading**：下一步提示与按钮可用性都按正常态渲染，
			//   用户立刻拿回控制权（见 SOFT_DEADLINE_MS）。
			//   真结果回来时 `state.phase` 会变成 ready/error，一切照常覆盖。
			const loading = state.phase === "loading" && !softExpired;
			// ⚠️ 但**徽章文案**不能因为软截止就改成「未开启」—— 那时根本没有结论，
			//   报「未开启」是假话（真结果可能是「已开启」）。只有拿到真结论
			//   （ready/error）才允许改徽章，否则一律维持「检测中」。
			const settled = state.phase !== "loading";
			const identityOk = !!(data && data.identity && data.identity.ok);
			const tone = !settled ? "unknown"
				: state.phase === "error" ? "off"
					: identityOk ? "on" : "off";
			const badgeText = !settled ? t("statusUnknown")
				: state.phase === "error" ? t("failed")
					: identityOk ? t("statusOn") : t("statusOff");

			// 当前通道 —— 只有固定端口那条**不依赖无线调试开关**，所以要显式区分，
			// 否则用户看到「已开启」也不知道关了开关会不会断。
			const channel = data && data.identity ? data.identity.channel : "none";
			const channelText = channel === "shizuku" ? t("chShizuku")
				: channel === "legacy" ? t("chStable")
					: channel === "wireless" ? t("chWireless") : "";

			// ── 系统版本：决定走哪条引导路径 ──────────────────────
			//
			// ⚠️ **Android 10 及以下没有「无线调试」** —— 那是 Android 11（API 30）
			//   才有的功能。在 Android 10 上还把它摆在最上面、还挂「推荐」、还给出
			//   「配对无线调试」按钮，用户照着去系统设置里找只会找不到（真机反馈过）。
			//
			// 判据用 UA 里的 `Android <版本>`：浏览器进程拿不到 `Build.VERSION.SDK_INT`，
			//   而 UA 是现成的、不需要为它新增一条原生桥。
			const androidMajor = (() => {
				const m = /Android\s+(\d+)/i.exec(navigator.userAgent || "");
				return m ? Number.parseInt(m[1], 10) : 0;
			})();
			// 版本判不出来时**当作不支持**（走电脑引导）：电脑引导在所有版本上都成立，
			// 而无线调试在 Android 10 上根本不存在。判错方向宁可保守。
			const wirelessSupported = androidMajor >= 11;

			// 「下一步该做什么」—— 由当前状态推出来，摆在首屏。
			// 用户不该为了知道点哪里而读完全部卡片。
			const keyReady = !!(data && data.key
				&& (data.key.origin === "imported" || data.key.origin === "paired"));
			let next = null;
			if (data) {
				// 失败分类（runner 那条 `诊断: kind=…` 行）。没拿到时是 "unknown"，
				// 下面的分支会自动退回通用引导 —— 与改动前的行为一致。
				const kind = data.identity && data.identity.kind ? data.identity.kind : "unknown";
				// 四类「密钥侧」的问题，正解都是**再配对一次**，只是原因不同。
				const keyKind = kind === "no-key" || kind === "key-revoked"
					|| kind === "key-unauthorized" || kind === "key-blocked";
				// ⚠️ **Shizuku 的优先级判据**（本次重做新增，是这次布局调整里
				//   唯一改变「用户实际会去点哪个按钮」的地方）。
				//
				//   理由：内置的两条路都以「设备上有个可连的 adbd」为前提，而
				//   Android 10 上那意味着每次重启都得插电脑。Shizuku 不需要 adbd、
				//   重启也有效 —— 所以只要它**有可能**走得通，就该排在前面。
				//
				//   判据用 `installed || binder` 而不是 `granted`：
				//     · 装了但没启动 → 用户点一下就能启动，值得推
				//     · 没装 → 也值得推（下载一次，之后一劳永逸）
				//   两者都比「去插电脑」轻。真机反馈的那位用户正是这种情况。
				const shizukuViable = shizuku.installed || shizuku.binder;
				if (identityOk) {
					next = channel === "wireless"
						? { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") }
						: { text: t("nextAllGood"), action: null };
				} else if (shizuku.granted) {
					// Shizuku 已授权但 runner 还没跟上 —— 只要重新检测，不用重启。
					next = { text: t("shizukuNextGrantedStale"), action: null };
				} else if (shizukuViable) {
					// 有 Shizuku 可用，就别再让用户去碰 adb 那条路。
					// ⚠️ 这里**必须给按钮**：只写一句「去启动 Shizuku」而不给入口，
					//   用户得自己在应用列表里找 Shizuku —— 而本页明明知道该点哪里。
					//   用 `onClick` 而不是 `action`：授权走的是 grantShizuku（要轮询
					//   结果），不是 doAction 里那种一发即忘的动作。
					next = shizuku.binder
						? { text: t("shizukuNextUngranted"), onClick: grantShizuku, label: t("shizukuGrant") }
						: { text: t("shizukuNextStopped"), action: "shizukuOpen", label: t("shizukuOpen") };
				} else if (keyKind) {
					// ⚠️ 只在系统真支持无线调试时才给配对按钮 —— 否则那个按钮在
					//    Android 10 上永远走不通，点了只是白等（与下面同一条约束）。
					const text = kind === "no-key"
						? t(wirelessSupported ? "nextNoKey" : "nextNoKeyPc")
						: kind === "key-revoked" ? t("nextRevoked")
							: kind === "key-unauthorized" ? t("nextUnauthorized")
								: t("nextKeyBlocked");
					next = wirelessSupported
						? { text, action: "pair", label: t(kind === "no-key" ? "wlPair" : "nextPair") }
						: { text, action: null };
				} else if (kind === "wireless-stale" || kind === "no-channel") {
					// 这两类的正解是**切到固定端口**：固定端口一旦就绪就不再依赖
					// 无线调试开关，恰好绕开「无线调试关着 ⇒ 无线通道必然不可用」。
					next = {
						text: t(kind === "wireless-stale" ? "nextWirelessStale" : "nextNoChannel"),
						action: "switchTcp",
						label: t("wlTcp"),
					};
				} else if (kind === "adb-missing" || kind === "timeout") {
					// 这两类没有能替用户按的按钮：前者要重装 App，后者只是 adbd
					// 还在重启（等一会儿点「重新检测」）。给一句可执行的说明就够，
					// **不要**摆一个点了没用的按钮。
					next = { text: t(kind === "adb-missing" ? "nextAdbMissing" : "nextTimeout"), action: null };
				} else if (data.key && data.key.origin === "generated" && wirelessSupported) {
					// 只有系统真支持无线调试时，才引导去配对 —— 否则那个按钮
					// 在 Android 10 上永远走不通，点了只是白等。
					next = { text: t("nextPairHint"), action: "pair", label: t("wlPair") };
				} else if (keyReady) {
					next = { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") };
				} else {
					next = {
						text: wirelessSupported ? t("nextChoose") : t("nextChoosePc"),
						action: null,
					};
				}
			}

			// 两个原生动作都要走 JS 桥 —— 配对码得经通知栏输入，而通知是 Android
			// 的能力，Node 侧（宿主半的 HTTP 路由）够不到。
			const doAction = (action) => {
				const b = window.DSHAndroid;
				const fn = action === "pair" ? "startWirelessPairing"
					: action === "switchTcp" ? "switchToTcpMode"
						: action === "shizukuOpen" ? "shizukuOpenManager"
							: action === "shizukuDownload" ? "shizukuOpenDownload" : null;
				if (fn === null) return;
				if (!b || typeof b[fn] !== "function") { window.alert(t("wlNoBridge")); return; }
				// ⚠️ 配对的一切交互（提示、配对码输入框、取消按钮）**都挂在那条常驻
				//   通知上**，通知被系统关掉时原生侧会直接拒绝开始（见
				//   `AdbPairingNotifier.start`）。不在这里先说清楚的话，用户看到的是
				//   「点了配对，什么都没发生」—— 然后反复点，永远不知道为什么。
				if (action === "pair") {
					try {
						if (typeof b.pairingNotificationsEnabled === "function"
							&& !b.pairingNotificationsEnabled()) {
							window.alert(t("wlNoNotify"));
							return;
						}
					} catch (e) { /* 查不了就照常走，原生侧还有一道拦截 */ }
				}
				b[fn]();
				// 只有配对需要回写「进行中」—— 它是一条要用户离开本页去系统设置的
				// 长流程；「切到固定端口」是几秒钟的原生调用，不需要。
				if (action === "pair") setPairing(true);
			};

			/** 取消配对：停掉原生侧那条常驻监听，并把本页状态复位。 */
			const cancelPairing = () => {
				const b = window.DSHAndroid;
				if (b && typeof b.cancelWirelessPairing === "function") b.cancelWirelessPairing();
				setPairing(false);
			};

			// 无线端口三态：真的在听 / 文件里有值但端口已死 / 没发现。
			// ⚠️ 中间那态以前会显示成绿灯（只查文件在不在），真机撞到过 ——
			// 无线调试关着、35589 早已没人监听，界面却报「无线端口 35589」。
			const wlPort = data && data.wireless ? data.wireless.connect : null;
			const wlStale = data && data.wireless ? data.wireless.stale : null;
			const [showDetails, setShowDetails] = React.useState(false);

			const rows = [];
			if (data) {
				rows.push({
					key: "runner", label: t("rowRunner"), ok: data.runner.ok,
					value: data.runner.ok ? `${t("ready")} · ${data.runner.size} ${t("bytes")}` : t("missing"),
				});
				// ⚠️ 徽章不能只看「密钥文件在不在」：**App 现场生成的密钥设备并不信任**，
				// 显示绿点会让人以为可以用了。
				//
				// 三态（判据见 assets/dsh-android-shell.mjs 的 `keyOrigin()`）：
				//   imported / paired → 绿点，设备已信任，直接能用
				//   generated         → 黄点，必须先配对
				//   null              → 灰点，**来源未知**（标记文件是后加的，
				//                       老安装的密钥没有它，无法回溯推断）
				// 未知时**故意不猜**成「已导入」—— 那正是这次要修的误报。
				rows.push({
					key: "key", label: t("rowKey"), ok: keyReady,
					warn: data.key.ok && data.key.origin === "generated",
					unknown: data.key.ok && !keyReady && data.key.origin !== "generated",
					value: data.key.ok
						? `${data.key.origin === "generated" ? t("generated")
							: data.key.origin === "paired" ? t("paired")
								: data.key.origin === "imported" ? t("imported")
									: t("keyOriginUnknown")} · ${data.key.size} ${t("bytes")}${data.key.importedNow ? " · " + t("justImported") : ""}`
						: t("notImported"),
				});
				rows.push({
					key: "identity", label: t("rowIdentity"), ok: identityOk,
					value: identityOk
						? `${t("uidOk")}${data.identity && data.identity.channel === "legacy" ? " · " + t("chStable")
							: data.identity && data.identity.channel === "wireless" ? " · " + t("chWireless") : ""}`
						: `${t("uidApp")}${data.identity && data.identity.reason ? " · " + data.identity.reason : ""}`,
				});
			}

			// 复制结果按**对象**存（不是布尔、也不是纯字符串）：两个块共用一个
			// 「最近复制的是哪条」，点第二条时第一条会自己复位成「复制」，
			// 不会两块同时显示「已复制」让人分不清到底复制了哪条。
			// 存对象而非字符串还有个作用：连点同一个按钮时 `at` 每次都不同，
			// 保证 state 一定变化 → effect 重跑 → 复位计时器被重新计时。
			const [copied, setCopied] = React.useState(null);
			React.useEffect(() => {
				if (copied === null) return undefined;
				const timer = window.setTimeout(() => setCopied(null), 2000);
				return () => window.clearTimeout(timer);
			}, [copied]);

			// 是否已经发起过配对（点了「配对无线调试」但还没取消）。
			//
			// ⚠️ 这个状态**必须让用户看见**，否则点了按钮后界面毫无变化，用户会以为
			// 没点上而反复点 —— 而真实情况是「正在等你去系统里打开那个界面」。
			// 只存本地 state：真正的监听状态在原生侧，这里只是给个即时反馈，
			// 不需要（也不该）为此多开一条查询接口。
			const [pairing, setPairing] = React.useState(false);
			const e = React.createElement;
			// 命令块 = 等宽代码 + 复制按钮。**按钮不是锦上添花**：本插件的全局
			// CSS 把 `user-select` 压成 `none`（见文件头 4c），命令长按选不中，
			// 而用户是在电脑终端里敲这些命令，只能照着手机屏幕抄 —— 没有按钮就得
			// 逐字符手抄 `%USERPROFILE%\.android\adbkey`。
			const cmdBlock = (text) => e("div", { key: text, style: S.cmdRow },
				e("code", { style: S.cmd }, text),
				e("button", {
					type: "button",
					style: S.cmdBtn,
					onClick: () => {
						mpCopyText(text).then((ok) => setCopied({ text, ok, at: Date.now() }));
					},
				}, copied && copied.text === text
					? (copied.ok ? t("copied") : t("copyFailed"))
					: t("copy")),
			);
			const guideSteps = [
				e("li", { key: 0, style: S.step }, t("step1")),
				e("li", { key: 1, style: S.step }, t("step2"), cmdBlock(t("cmd2"))),
				e("li", { key: 2, style: S.step }, t("step3"), cmdBlock(t("cmd3"))),
				e("li", { key: 3, style: S.step }, t("step4")),
			];

			// ── 首屏：结论 + 下一步 ────────────────────────────────
			// 原来的首屏是一张「执行通道 / ADB 密钥 / 实际身份」的技术表格，
			// 用户得自己把三行翻译成「我现在能不能用、不能的话点哪里」。
			// 现在把结论摆在最上面，明细折进「查看详情」。
			const head = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("statusTitle")),
					e("span", { style: { ...S.badge, color: MP_PRIV_TONE[tone].color, background: MP_PRIV_TONE[tone].bg } }, badgeText),
				),
				// 已开启时把「走哪条通道」直接写在结论下面 —— 这是用户唯一需要
				// 知道的运行期事实（关无线调试/关 Wi-Fi 会不会断）。
				identityOk && channelText
					? e("p", { style: S.summary }, channelText)
					: null,
				next ? e("div", { style: S.next },
					e("span", { style: S.nextText }, next.text),
					// 两种按钮：`action` 走 doAction（原生一侧的一发即忘动作），
					// `onClick` 走组件内的回调（Shizuku 授权要轮询结果）。
					next.onClick ? e("button", {
						type: "button",
						style: S.btnPrimary,
						disabled: shizukuBusy,
						onClick: next.onClick,
					}, shizukuBusy ? t("shizukuGranting") : next.label)
						: next.action ? e("button", {
							type: "button",
							style: S.btnPrimary,
							onClick: () => doAction(next.action),
						}, next.label) : null,
				) : null,
				// ⚠️ 软截止到期、真结果还没回来时，明确告诉用户「还在探测、你可以不等」，
				//   并直接给出「重新匹配」入口 —— 而不是留一个没有结论也没有按钮的死界面。
				//   真机反馈原话：「你一检测没有就是没有了呀，没有就让用户重新走匹配流程啊，
				//   我要这里等几分钟？」这条分支就是那个诉求的落点。
				softExpired && state.phase === "loading" ? e("div", { style: S.next },
					e("span", { style: S.nextText }, t(wirelessSupported ? "softPending" : "softPendingPc")),
					// ⚠️ 只有系统真支持无线调试时才给配对按钮 —— Android 10 上配对
					//   根本不存在，摆一个点了必然白等的按钮比不给更糟（与上面
					//   `keyKind` 分支同一条约束）。
					wirelessSupported ? e("button", {
						type: "button",
						style: S.btnPrimary,
						onClick: () => doAction("pair"),
					}, t("wlPair")) : null,
				) : null,
				state.phase === "error" ? e("p", { style: S.error }, state.message) : null,
				e("div", { style: S.actions },
					e("button", {
						type: "button",
						style: S.btn,
						// ⚠️ 只在**软截止之前**禁用。到点即解锁，用户不必陪着探测一起等 ——
						//   真机反馈的「一直显示检测中、又没有按钮、又没有停止」正是
						//   这里一直 `disabled` 造成的。
						disabled: loading,
						onClick: refresh,
					}, loading ? t("checking") : t("recheck")),
					data ? e("button", {
						type: "button",
						style: S.btnQuiet,
						onClick: () => setShowDetails((v) => !v),
					}, showDetails ? t("hideDetails") : t("showDetails")) : null,
				),
				// 明细：定位问题时才需要，默认收起。
				showDetails && data ? e("div", { style: S.details },
					rows.map((row) => e("div", { key: row.key, style: S.row },
						e("span", {
							style: {
								...S.dot,
								background: (row.ok ? MP_PRIV_TONE.on
									: row.warn ? MP_PRIV_TONE.warn
										: row.unknown ? MP_PRIV_TONE.unknown
											: MP_PRIV_TONE.off).color,
							},
						}),
						e("span", { style: S.rowLabel }, row.label),
						e("span", { style: S.rowValue }, row.value),
					)),
				) : null,
			);

			// ── 三条开启路径：统一成同一种卡片结构 ────────────────────
			//
			// ⚠️ **这是本次布局重做的核心。** 原布局里三条路各有各的形状：
			//   无线调试是一张普通卡（按钮常驻三个）、电脑引导是一个 details、
			//   Shizuku 根本没有；顺序还随系统版本翻转（Android 10 电脑在前、
			//   Android 11+ 无线在前）。结果是用户每次打开看到的排版都不一样，
			//   得重新认一遍哪里是标题、哪里是按钮。
			//
			//   现在三条路**结构完全一致**，顺序也**固定**（Shizuku → 无线 → 电脑）：
			//     标题 + 状态徽章
			//     一句话说明
			//     关键事实行（可选）
			//     按钮行
			//     折起的步骤（可选）
			//   动线因此是固定的：先看徽章判断这条路通不通，再看按钮做下一步。
			const pathCard = (opts) => e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, opts.title),
					e("span", {
						style: { ...S.badge, color: opts.tone.color, background: opts.tone.bg },
					}, opts.badge),
				),
				opts.intro ? e("p", { style: S.intro }, opts.intro) : null,
				...(opts.rows || []),
				opts.actions && opts.actions.length > 0
					? e("div", { style: S.actions }, ...opts.actions)
					: null,
				opts.steps && opts.steps.length > 0 ? e("details", { style: S.foldInline },
					e("summary", { style: S.foldHead }, t("pathShowSteps")),
					e("div", { style: S.foldBody }, e("ol", { style: S.steps }, opts.steps)),
				) : null,
				...(opts.notes || []),
			);

			// ① Shizuku —— 唯一不依赖 adbd 的那条，所以固定排第一。
			//
			// ⚠️ 四态徽章：装了 / 服务在跑 / 已授权 是**三个独立事实**，
			//   合并的话「没装」和「装了没启动」会显示成同一个结论，
			//   而这两者要用户做的事完全不同（去下载 vs 去打开它）。
			const szTone = !shizuku.installed ? MP_PRIV_TONE.off
				: !shizuku.binder ? MP_PRIV_TONE.warn
					: !shizuku.granted ? MP_PRIV_TONE.warn
						: MP_PRIV_TONE.on;
			const szBadge = !shizuku.installed ? t("shizukuAbsent")
				: !shizuku.binder ? t("shizukuInstalled")
					: !shizuku.granted ? t("shizukuUngranted")
						: t("shizukuGranted");
			// 主按钮按状态换：没装→下载，装了没跑→打开它，跑了没授权→授权，
			// 已授权→不需要主按钮（只留「重新检测」）。
			//
			// ⚠️ **一次只给一个主按钮**：原布局在同一屏里摆了五个同等分量的按钮
			//   （配对/切端口/取消/重新检测/查看详情），用户不知道点哪个才对。
			const szPrimary = !shizuku.installed
				? e("button", {
					type: "button", style: S.btnPrimary,
					onClick: () => doAction("shizukuDownload"),
				}, t("shizukuDownload"))
				: !shizuku.binder
					? e("button", {
						type: "button", style: S.btnPrimary,
						onClick: () => doAction("shizukuOpen"),
					}, t("shizukuOpen"))
					: !shizuku.granted
						? e("button", {
							type: "button", style: S.btnPrimary,
							disabled: shizukuBusy,
							onClick: grantShizuku,
						}, shizukuBusy ? t("shizukuGranting") : t("shizukuGrant"))
						: null;
			const szCard = pathCard({
				title: t("shizukuTitle"),
				// 徽章兼「推荐」位：Shizuku 在**两种系统上都能用**且重启有效，
				// 所以无论 Android 版本都是首推的那条（这正是本次加它的理由）。
				tone: szTone,
				badge: szBadge,
				// ⚠️ intro 是**动态的「现在该做什么」**，不是固定的功能说明。
				//   固定说明只在「没装」时才有信息量（那时用户确实不知道 Shizuku
				//   是什么）；装了的人再看一遍等于白占一屏。
				//   这与首屏 `next` 那条是同一条原则：先说下一步，再说原理。
				intro: !shizuku.installed ? t("shizukuNextAbsent")
					: !shizuku.binder ? t("shizukuNextStopped")
						: !shizuku.granted ? t("shizukuNextUngranted")
							// ⚠️ 第四种：Shizuku 已授权，但 runner 的探测结论还没跟上
							//   （identityOk 为假）。此时说「已就绪」与上面的徽章矛盾，
							//   必须说清「点一下重新检测就好」。
							: identityOk ? t("shizukuNextGranted")
								: t("shizukuNextGrantedStale"),
				rows: [
					e("div", { key: "svc", style: S.row },
						e("span", { style: { ...S.dot, background: szTone.color } }),
						e("span", { style: S.rowLabel }, t("shizukuRowService")),
						e("span", { style: S.rowValue },
							!shizuku.installed ? t("shizukuServiceAbsent")
								: shizuku.binder ? t("shizukuServiceRunning")
									: t("shizukuServiceStopped")),
					),
					// 服务身份/版本只在服务真的在跑时才有值 —— 没跑时显示「未运行」
					// 是重复上面那行，所以干脆不渲染。
					shizuku.binder ? e("div", { key: "uid", style: S.row },
						e("span", { style: { ...S.dot, background: MP_PRIV_TONE.on.color } }),
						e("span", { style: S.rowLabel }, t("shizukuRowUid")),
						e("span", { style: S.rowValue }, shizuku.uid >= 0 ? `uid ${shizuku.uid}` : "—"),
					) : null,
					shizuku.binder ? e("div", { key: "ver", style: S.row },
						e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
						e("span", { style: S.rowLabel }, t("shizukuRowVersion")),
						e("span", { style: S.rowValue }, shizuku.version > 0 ? String(shizuku.version) : "—"),
					) : null,
				],
				actions: [szPrimary, e("button", {
					type: "button", style: S.btn,
					onClick: readShizuku,
				}, t("shizukuRecheck"))].filter(Boolean),
				steps: [
					e("li", { key: 0, style: S.step }, t("shizukuStep1")),
					e("li", { key: 1, style: S.step }, t("shizukuStep2")),
					e("li", { key: 2, style: S.step }, t("shizukuStep3")),
					e("li", { key: 3, style: S.step }, t("shizukuStep4")),
				],
				notes: [e("p", { key: "note", style: S.note }, t("shizukuNote"))],
			});

			// ② 无线调试 —— Android 10 上给一张**纯说明卡**，不再是整张消失。
			//
			// ⚠️ 这一处改过两轮，方向是相反的，别再改回去：
			//   原布局保留整张卡、只把配对按钮藏掉 ⇒ 用户看到一张标题写着
			//   「无线调试」、里面有四个步骤、却没有任何按钮可点的卡，照着做白做
			//   （真机反馈的「按步骤走了没成功、一切都跟没动一样」有一部分就是它）。
			//   改成整张不渲染之后又矫枉过正 —— 用户在 ADB 标签下**根本看不到**
			//   还有这么一条路，只会以为本应用漏做了。
			//
			//   现在：**行还在、点得进去、里面说清为什么不支持**。可见性与
			//   「不骗用户去点一个走不通的按钮」两个要求同时满足 —— 判据是
			//   「有没有可点的动作」，而不是「这一块在不在」。
			const wlCard = !wirelessSupported
				? e("div", { style: S.card },
					e("div", { style: S.cardHead },
						e("h3", { style: S.cardTitle }, t("wlTitle")),
						e("span", {
							style: {
								...S.badge,
								color: MP_PRIV_TONE.unknown.color,
								background: MP_PRIV_TONE.unknown.bg,
							},
						}, t("pathUnavailable")),
					),
					e("p", { style: S.intro }, t("wlAbsentNote")),
				)
				: pathCard({
				title: t("wlTitle"),
				tone: identityOk && channel === "wireless" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off,
				badge: identityOk && channel === "wireless" ? t("pathReady") : t("pathTodo"),
				intro: t("wlIntro"),
				rows: [
					// 无线端口是「有没有发现到」的最直接证据 —— 没有它，
					// 用户只能靠「配对按钮点了没反应」来猜，无从下手。
					// 三态：真的在听（绿）/ 文件里有值但端口已死（灰，说明无线调试关了）/
					// 没发现（灰）。中间那态以前会误报成绿灯。
					data ? e("div", { key: "port", style: S.row },
						e("span", {
							style: {
								...S.dot,
								background: wlPort ? MP_PRIV_TONE.on.color
									: wlStale ? MP_PRIV_TONE.unknown.color : MP_PRIV_TONE.off.color,
							},
						}),
						e("span", { style: S.rowLabel }, t("wlPort")),
						e("span", { style: S.rowValue }, wlPort ? String(wlPort)
							: wlStale ? t("wlPortStale") : t("wlPortNone")),
					) : null,
				],
				actions: [
					e("button", {
						type: "button", style: S.btnPrimary,
						disabled: pairing,
						onClick: () => doAction("pair"),
					}, pairing ? t("wlPairing") : t("wlPair")),
					// ⚠️ 「取消配对」只在**真的在配对时**才出现。原布局让它常驻，
					//   于是一个没有任何进行中操作的页面上摆着「取消配对」——
					//   用户会以为有什么正在跑，或者点了它以为取消了什么。
					pairing ? e("button", {
						type: "button", style: S.btn,
						onClick: cancelPairing,
					}, t("wlCancel")) : null,
					e("button", {
						type: "button", style: S.btn,
						onClick: () => doAction("switchTcp"),
					}, t("wlTcp")),
				].filter(Boolean),
				steps: [
					e("li", { key: 0, style: S.step }, t("wlStep1")),
					e("li", { key: 1, style: S.step }, t("wlStep2")),
					e("li", { key: 2, style: S.step }, t("wlStep3")),
					e("li", { key: 3, style: S.step }, t("wlStep4")),
				],
				notes: [
					// 等待期间把「接下来该做什么」直接铺在按钮下面 —— 用户点了配对之后
					// 眼神还在这块区域，跑到别处去提示等于没提示。
					pairing ? e("p", { key: "hint", style: S.note }, t("wlPairingHint")) : null,
					e("p", { key: "tcp", style: S.note }, t("wlTcpHint")),
					// ⚠️ 这条必须留：用户看到「输入配对码」的第一反应是「让 App 自己填」
					//   或者「复制粘贴」，而 SPAKE2 要求人工输入。不说清就会反复试。
					e("p", { key: "code", style: S.note }, t("wlPairHint")),
				].filter(Boolean),
			});

			// ③ 电脑引导（固定端口）—— 所有版本上都成立，所以永远渲染。
			const pcCard = pathCard({
				title: wirelessSupported ? t("pcTitle") : t("pcTitlePrimary"),
				tone: identityOk && channel === "legacy" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off,
				badge: identityOk && channel === "legacy" ? t("pathReady") : t("pathTodo"),
				intro: t("pcIntro"),
				// Android 10 上「无线调试」那张卡整张不渲染，所以它为什么不存在的
				// 那句话挪到这里 —— 否则用户会以为本应用漏做了这个功能。
				notes: [!wirelessSupported
					? e("p", { key: "wl-absent", style: S.note }, t("wlAbsentNote"))
					: null].filter(Boolean),
				actions: [e("button", {
					type: "button", style: S.btn,
					onClick: () => doAction("switchTcp"),
				}, t("wlTcp"))],
				// ⚠️ 这里的步骤**自带命令块**，不能走 pathCard 的纯文本 steps ——
				//   所以整段作为 details 内容直接传进来。命令必须能一键复制：
				//   本插件的全局 CSS 把 user-select 压成 none，长按选不中。
				steps: guideSteps,
			});

			// ── 注意事项（折起）────────────────────────────────
			const notesCard = e("details", { style: S.fold },
				e("summary", { style: S.foldHead }, t("notesTitle")),
				e("ul", { style: S.notes },
					[t("note1"), t("note2"), t("note3")].map((text, index) => e("li", { key: index, style: S.note }, text)),
				),
			);

			// ── root（uid 0）二级界面 ─────────────────────────────
			//
			// 为什么单独一个标签：uid 2000 与 root 是**两条独立的通道**，可用性互不
			// 影响（没 root 的设备照样能用 uid 2000）。所以不能把 root 塞进 ADB
			// 那个标签里 —— 那个标签只回答「uid 2000 能不能用」。
			//
			// ⚠️ 三态各自对应**完全不同的下一步**（去授权 / 什么都不用做 / 去检测），
			//   所以文案必须分开，不能合成一句「root 不可用」。
			const rootTone = !root.known ? MP_PRIV_TONE.unknown
				: root.granted ? MP_PRIV_TONE.on
					: root.state === "DENIED" ? MP_PRIV_TONE.warn : MP_PRIV_TONE.off;
			const rootBadge = !root.known ? t("rootUnknown")
				: root.granted ? t("rootOn")
					: root.state === "DENIED" ? t("rootDenied") : t("rootAbsent");
			// ⚠️ `granted` 与 `mounted` 必须分开判：前者是「探测结论」，后者是
			//   「本次启动真的挂上了 root 工具」。工具走 `--patch`，启动时定死 ——
			//   用户在设置页点「重新检测」把结论刷成 GRANTED 的那一刻，引擎已经在跑，
			//   这个会话里**没有** root 工具。此时若还说「已注册」，就是在说假话。
			const rootNext = !root.known ? t("rootNextUnknown")
				: root.granted
					? (root.mounted ? t("rootNextOn") : t("rootNextOnPendingReload"))
					: root.state === "DENIED" ? t("rootNextDenied") : t("rootNextAbsent");
			// 检测时间：结论是 24 小时缓存的，用户需要知道这是刚测的还是昨天的。
			const rootAtText = root.at > 0 ? new Date(root.at).toLocaleString() : t("rootDetailNever");

			const rootCard = e("div", { style: S.card },
				e("div", { style: S.cardHead },
					e("h3", { style: S.cardTitle }, t("rootTitle")),
					e("span", { style: { ...S.badge, color: rootTone.color, background: rootTone.bg } }, rootBadge),
				),
				e("p", { style: S.intro }, t("rootIntro")),
				e("div", { style: S.next },
					e("span", { style: S.nextTextWide }, rootNext),
					e("button", {
						type: "button",
						style: S.btnPrimary,
						disabled: rootBusy,
						onClick: recheckRoot,
					}, rootBusy ? t("rootChecking") : t("rootRecheck")),
				),
				e("div", { style: S.row },
					e("span", { style: { ...S.dot, background: rootTone.color } }),
					e("span", { style: S.rowLabel }, t("rootRowDetail")),
					// 结论原文由原生给出（`su=/system/bin/su, id -u → 0` 这类），
					// 直接透出比翻译成一句话更有助于排查。
					e("span", { style: S.rowValue }, root.known && root.detail ? root.detail : t("rootDetailNever")),
				),
				e("div", { style: S.row },
					e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
					e("span", { style: S.rowLabel }, t("rootRowAt")),
					e("span", { style: S.rowValue }, rootAtText),
				),
				e("p", { style: S.note }, t("rootNote")),
			);

			// ══ 标签页布局 ════════════════════════════════════════════════
			//
			// ⚠️ **这是第二次重做，与上一版的结构差异必须说清，否则很容易改回去。**
			//
			//   上一版把三条通道**全部平铺**在一屏上（Shizuku 卡 + 无线卡 + 电脑引导卡
			//   + root 卡），理由是「让用户看到还有别的路」。实测的代价是：一屏里
			//   四五张结构相似的卡纵向排开，每张都带徽章、说明、按钮、折叠步骤，
			//   用户在 360px 宽的屏上要滑很久才能看完，而且**看完也不知道该点哪张**
			//   —— 卡片之间的层级全靠徽章文字区分，那太弱了。
			//
			//   现在：三条通道各占一个标签，**标签栏本身就是导航** —— 点标签直接切到
			//   该通道的二级界面，一屏一件事。
			//
			// ⚠️ **不要额外加一层「概览列表」。** 中途试过「标签栏 + 三行概览卡」，
			//   结果是同一组信息（通道名 + 状态）在一屏里说两遍，而且两处的点击
			//   行为一样、视觉分量却不同，用户会以为它们是两回事。标签栏的圆点
			//   已经承担了「不点进去就知道哪条通」的职责，够了。
			//
			//   两条约束仍然成立（与上一版一致，不能丢）：
			//     ① 三条路都**看得见** —— 标签栏常驻，不存在「藏起来」
			//     ② 顺序固定 Shizuku → ADB → root，不按系统版本翻转
			//     ③ 走不通的路**不给按钮**，但说明必须看得到（见 wlCard）
			//
			// 默认落在**第一个标签**（Shizuku）：它是唯一不依赖 adbd、重启也有效的
			// 通道，对「Android 10 且只有有线 adb」那类设备是唯一可用的 ——
			// 打开就停在最可能解决问题的那一页，而不是一个什么都没有的总览。
			const [tab, setTab] = React.useState("shizuku");

			// 三条通道在标签圆点上的状态色。
			// ⚠️ 圆点色与卡片徽章色**必须同源**（都用 `MP_PRIV_TONE` 里那几档）——
			//   分开算迟早会不一致，那种不一致用户一眼就能看到。
			//
			// ⚠️ 标签上**不摆徽章文字**。窄屏上三个标签平分 360px，每个只有
			//   ~110px，「Shizuku」已经占掉大半，再塞「待配置 / 已就绪」会被
			//   挤成省略号；而状态本身点进去就能看到，圆点已经足够传达「通/不通」。
			const adbTone = identityOk && channel === "legacy" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;

			const tabs = [
				// ⚠️ **Shizuku 排第一**：它不依赖 adbd、重启也有效，是唯一对
				//   「Android 10 且只有有线 adb」那类设备真正可用的通道。
				//   ADB 排在它后面不是因为没用，而是因为它的两条走法都有前提。
				//
				// ⚠️ 标签只带 `label` / `tone` / `body` 三样，**不带标题与说明**
				//   —— 那是卡片自己的事（`szCard`/`wlCard`/`pcCard`/`rootCard` 都自带
				//   `cardHead` + intro）。中途试过在标签栏下面再加一块「通道标题 +
				//   说明」的头部，结果是同一句话在屏幕上出现两遍，而且两处都是
				//   「标题 + 徽章」的形状，用户会以为点错了地方。
				{
					id: "shizuku", label: t("tabShizuku"), tone: szTone,
					body: szCard,
				},
				{
					id: "adb", label: t("tabAdb"), tone: adbTone,
					// ADB 二级界面 = 无线调试卡（Android 11+）或它的说明卡（Android 10）
					//  + 电脑引导卡。两张都留着：它们同属「ADB 这条路」，只是走法不同。
					//
					// ⚠️ 两张卡之前先铺一段统摄说明。没有它，用户看到两张并列的卡
					//   会以为是两条互不相干的路 —— 而它们其实共用同一套密钥与 adbd。
					body: e("div", { style: S.stack },
						e("p", { style: S.intro }, t("adbIntro")),
						wlCard,
						pcCard,
					),
				},
				{
					id: "root", label: t("tabRoot"), tone: rootTone,
					body: rootCard,
				},
			];
			// 找不到就退回第一个 —— `tab` 是本地 state，不会有非法值，但
			// `find` 返回 undefined 时若直接解引用会在渲染期抛错、整页白屏。
			const active = tabs.find((item) => item.id === tab) || tabs[0];

			return e("div", { style: S.section },
				e("h2", { style: S.heading }, t("title")),
				e("p", { style: S.intro }, t("intro")),

				// 常驻结论条：无论切到哪个标签都看得见 —— 它是「现在到底能不能用」的
				// 唯一答案，不该因为切了标签就消失。
				head,

				e("div", { style: S.stack },
					e("div", { style: S.tabBar },
						...tabs.map((item) => e("button", {
							key: item.id,
							type: "button",
							style: item.id === active.id ? { ...S.tab, ...S.tabActive } : { ...S.tab },
							onClick: () => setTab(item.id),
						},
							e("span", { style: { ...S.tabDot, background: item.tone.color } }),
							e("span", { style: S.tabLabel }, item.label),
						)),
					),
					e("div", { style: S.panel }, active.body),
					notesCard,
				),
			);
		}

		/**
		 * 把「特权权限」分节注册进官方设置页的一级导航。
		 *
		 * 用 "ctx.inject" 等 "slots" / "locale" 就绪：本插件声明的 "inject" 是空数组
		 * （客户端半刻意不声明依赖，避免因某个服务缺失而整个不激活），所以这里按需
		 * 开一个子插件来等这两个服务。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterPrivilegedSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过特权权限分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;   // 拿不到 React 就静默跳过，不影响移动端 UI 适配
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ **必须用 `scope` 上的服务，不能用外层 `ctx`。**
					//   Cordis 的 `ctx.locale` 要求**当前 fiber 声明过** `locale` 依赖，
					//   否则抛 `cannot get property "locale" without inject`。
					//   本插件的 `inject` 是空数组（客户端半刻意不声明依赖），所以外层
					//   `ctx.locale` 一定不可用；`scope` 是这个 `ctx.inject` 子 fiber，
					//   它的依赖表里有 `slots`/`locale`，从它取才对。
					//   （官方 ui-approval 写 `ctx.locale` 是因为它的 inject 里声明了 locale，
					//    照抄那行在本插件里必炸 —— 这里踩过一次。）
					const t = scope.locale.bind(MP_PRIV_NS);
					scope.effect(
						() => scope.locale.register(MP_PRIV_NS, { zh: MP_PRIV_ZH, en: MP_PRIV_EN }),
						"dsh-android-mobile: 特权权限字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						// 刻意不叫 "privileged"：官方若将来加同名分节，我们的会把它顶掉。
						id: "dsh-android-privileged",
						// 官方 order：account=-10、general=0、models=10、plugins=15、
						// agent-presets=20。排在所有官方分节之后，不打乱用户熟悉的顺序。
						order: 25,
						label: () => t("nav"),
						locale: MP_PRIV_NS,
					}, mpPrivSection));
				});
			} catch (e) {
				/* 注册失败只损失这一个分节，绝不能连累移动端 UI 适配。
				   但**要打日志**：静默吞异常会让「分节没出现」变成无从下手的哑谜。 */
				console.warn("[dsh-android-mobile] 注册特权权限分节失败:", e && (e.stack || e.message || e));
			}
		}

		/**
		 * 「悬浮气泡」分节组件。
		 *
		 * 布局沿用「特权权限」那套**结论优先**的结构：head 卡给状态徽章 + 一条
		 * 「下一步」+ 直达按钮，明细与说明折进 "<details>"。
		 *
		 * @param {object} props - 槽位注入面（只用 "t"）。
		 * @returns {object} React 元素。
		 */
		/**
		 * 悬浮球「外观与手感」控制面板。
		 *
		 * ## 为什么单独一个组件
		 *
		 * "mpBubbleSection" 已经承担了「开关气泡 / 权限状态 / 下一步」那套结论优先的
		 * 首屏，把它撑大只会让首屏失控。这里专注一件事：**把 BubbleAppearance 的
		 * 所有可调项摆出来**，按语义分组、可折叠。
		 *
		 * ## 数据流（单向，原生是唯一真源）
		 *
		 * ""`
		 * 挂载 → bubbleAppearance() 读全量 JSON → 本地 state
		 * 改动 → bubbleApply(JSON.stringify({ 字段: 新值 })) → 重新读回 → 覆盖 state
		 * ""`
		 *
		 * ⚠️ **每次改动都读回**而不是本地乐观更新："BubbleAppearance.merge()" 里有
		 *   clamp（如 "size" 只收 24–160）和特权闸门（"imeFlingEnabled" 无特权时会被
		 *   强制置 false）。本地先改会让界面显示一个原生并不接受的值，用户以为设成功了。
		 *
		 * ⚠️ "bubbleApply" 只传**差异字段**：merge 的语义是「缺省即保持原值」，
		 *   传全量会把没动过的字段也写一遍，一旦某个字段解析失败就整体回退。
		 *
		 * @param {object} props - "{ onBubbleChange?: Function }"（气泡开关变化时通知父级刷新）
		 */
		function mpBubblePanel(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_BUBBLE_ZH[key] ?? key;

			const [ap, setAp] = React.useState(null);
			const [open, setOpen] = React.useState({});

			/** 读一次全量外观。 */
			const read = React.useCallback(() => {
				const b = window.DSHAndroid;
				if (!b || typeof b.bubbleAppearance !== "function") { setAp(null); return; }
				try {
					const raw = b.bubbleAppearance();
					setAp(typeof raw === "string" ? JSON.parse(raw) : (raw || null));
				} catch (e) {
					console.warn("[dsh-android-mobile] 读外观失败:", e && e.message);
					setAp(null);
				}
			}, []);

			React.useEffect(() => { read(); }, [read]);

			/**
			 * 写一个字段（或一组字段），然后读回。
			 *
			 * @param {object} patch - 形如 "{ size: 56 }"。
			 */
			const apply = React.useCallback((patch) => {
				const b = window.DSHAndroid;
				if (!b || typeof b.bubbleApply !== "function") return;
				try {
					b.bubbleApply(JSON.stringify(patch));
					read();
				} catch (e) {
					console.warn("[dsh-android-mobile] 应用外观失败:", e && e.message);
				}
			}, [read]);

			if (!ap) {
				return React.createElement("div", { style: { ...S.intro, padding: "10px 0" } },
					t("panelUnavailable"));
			}

			// ── 基础控件 ───────────────────────────────────────────────

			/** 开关行（复用 swRow 的排版）。 */
			const sw = (label, hint, on, onToggle, disabled, why) =>
				React.createElement("div", { style: S.swRow, key: label },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, label),
						hint ? React.createElement("span", { style: S.swHint }, hint) : null,
						why ? React.createElement("span", { style: S.swWhy }, why) : null,
					),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": on ? "true" : "false",
						"aria-label": label,
						disabled: !!disabled,
						style: {
							...S.swTrack,
							background: disabled
								? "var(--dsw-alias-border-l2, #dcdcdc)"
								: (on ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)"),
							cursor: disabled ? "not-allowed" : "pointer",
							opacity: disabled ? 0.55 : 1,
						},
						onClick: disabled ? undefined : onToggle,
					}, React.createElement("span", { style: { ...S.swThumb, left: on ? "21px" : "3px" } })),
				);

			/**
			 * 滑块行。拖动时只更新本地显示，**松手才写盘**。
			 *
			 * ⚠️ 不这么拆的话，拖一次会触发几十次 "bubbleApply" + 几十次读回，
			 *   Shizuku/JSON 往返叠加起来会让滑块卡得像冻住。
			 */
			const slider = (label, key, min, max, step, fmt) => {
				const val = ap[key] ?? min;
				return React.createElement("div", { style: S.ctlRow, key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("input", {
						type: "range", min, max, step,
						// eslint-disable-next-line no-empty-function
						defaultValue: val,
						// ⚠️ 用 key 绑当前值：外部改动（读回）后强制重挂，让滑块跟上。
						key: key + ":" + val,
						style: S.slider,
						"aria-label": label,
						onInput: (e) => { e.target.dataset.live = e.target.value; },
						onChange: (e) => apply({ [key]: Number(e.target.value) }),
					}),
					React.createElement("span", { style: S.ctlValue },
						fmt ? fmt(val) : String(val)),
				);
			};

			/** 分段选择行。 */
			const seg = (label, key, options) =>
				React.createElement("div", { style: S.ctlRow, key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("div", { style: S.seg, role: "group", "aria-label": label },
						options.map((o) => React.createElement("button", {
							key: o.v, type: "button",
							"aria-pressed": ap[key] === o.v ? "true" : "false",
							style: { ...S.segBtn, ...(ap[key] === o.v ? S.segBtnOn : null) },
							onClick: () => apply({ [key]: o.v }),
						}, o.n)),
					),
				);

			/**
			 * 颜色行。空串表示「自动」，所以额外给一个复位按钮。
			 *
			 * @param {string} [autoText] 「自动」这个状态显示成什么字。
			 *   ⚠️ 面板**底色**的自动语义是**透明**（见原生 "DEFAULT_PANEL_BG = 0"），
			 *   所以那一行要显式写「透明」而不是「自动」—— 用户看不到"自动"
			 *   与"透明"的等号，而 "<input type="color">" 又根本选不出透明色。
			 */
			const color = (label, key, autoText) => {
				const cur = ap[key] || "";
				const autoLabel = autoText || t("autoColor");
				return React.createElement("div", { style: S.ctlRow, key },
					React.createElement("span", { style: S.ctlLabel }, label),
					cur
						? React.createElement("button", {
							type: "button", style: S.btnQuiet,
							onClick: () => apply({ [key]: "" }),
						}, autoLabel)
						: React.createElement("span", { style: S.ctlValue }, autoLabel),
					React.createElement("span", {
						style: {
							...S.swatchWrap,
							background: cur || "repeating-conic-gradient(#e0e0e0 0% 25%, #fff 0% 50%) 50% / 10px 10px",
						},
					},
						React.createElement("input", {
							type: "color",
							style: S.swatchInput,
							"aria-label": label,
							value: cur || "#2b6cb0",
							onChange: (e) => apply({ [key]: e.target.value }),
						}),
					),
				);
			};

			/** 可折叠分组。 */
			const acc = (id, title, count, children) => {
				const isOpen = !!open[id];
				return React.createElement("div", { style: S.acc, key: id },
					React.createElement("div", {
						style: S.accHead,
						role: "button",
						tabIndex: 0,
						"aria-expanded": isOpen ? "true" : "false",
						onClick: () => setOpen((v) => ({ ...v, [id]: !v[id] })),
						onKeyDown: (e) => {
							if (e.key === "Enter" || e.key === " ") {
								e.preventDefault();
								setOpen((v) => ({ ...v, [id]: !v[id] }));
							}
						},
					},
						React.createElement("span", null, title),
						React.createElement("span", { style: S.accCount },
							count + " · " + (isOpen ? t("collapse") : t("expand"))),
					),
					isOpen ? React.createElement("div", { style: S.accBody }, children) : null,
				);
			};

			const pct = (v) => Math.round((Number(v) || 0) * 100) + "%";

			// ── 分组 ───────────────────────────────────────────────────

			const appearanceGroup = acc("look", t("grpLook"), 9, [
				React.createElement("div", { style: S.ctlRow, key: "size" },
					React.createElement("span", { style: S.ctlLabel }, t("fSize")),
					React.createElement("input", {
						type: "range", min: 24, max: 320, step: 2,
						key: "size:" + ap.size,
						defaultValue: ap.size, style: S.slider,
						"aria-label": t("fSize"),
						onChange: (e) => apply({ size: Number(e.target.value) }),
					}),
					React.createElement("span", { style: S.ctlValue }, ap.size + "dp"),
				),
				seg(t("fShape"), "shape", [
					{ v: "circle", n: t("shapeCircle") },
					{ v: "square", n: t("shapeSquare") },
					{ v: "rounded", n: t("shapeRounded") },
				]),
				slider(t("fCorner"), "cornerRadius", 0, 80, 1, (v) => v + "dp"),
				slider(t("fOpacity"), "opacity", 0.05, 1, 0.05, pct),
				slider(t("fBgOpacity"), "bgOpacity", 0, 1, 0.05, pct),
				slider(t("fAvatarOpacity"), "avatarOpacity", 0, 1, 0.05, pct),
				slider(t("fAvatarScale"), "avatarScale", 0.2, 2, 0.05, (v) => Number(v).toFixed(2) + "x"),
				slider(t("fBorderWidth"), "borderWidth", 0, 8, 1, (v) => v + "dp"),
				color(t("fBorder"), "border"),
			]);

			const colorGroup = acc("color", t("grpColor"), 2, [
				color(t("fBg"), "bg"),
				color(t("fBg2"), "bg2"),
			]);

			const motionGroup = acc("motion", t("grpMotion"), 5, [
				React.createElement("div", { style: S.swRow, key: "thr" },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, t("throwName")),
						React.createElement("span", { style: S.swHint }, t("throwHint")),
					),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.throwEnabled ? "true" : "false",
						"aria-label": t("throwName"),
						style: {
							...S.swTrack,
							background: ap.throwEnabled ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ throwEnabled: !ap.throwEnabled }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.throwEnabled ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.swRow, key: "shake" },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, t("shakeName")),
						React.createElement("span", { style: S.swHint }, t("shakeHint")),
					),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.shakeEnabled ? "true" : "false",
						"aria-label": t("shakeName"),
						style: {
							...S.swTrack,
							background: ap.shakeEnabled ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ shakeEnabled: !ap.shakeEnabled }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.shakeEnabled ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.ctlRow, key: "edge" },
					React.createElement("span", { style: S.ctlLabel }, t("fEdgeSnap")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.edgeSnap ? "true" : "false",
						"aria-label": t("fEdgeSnap"),
						style: {
							...S.swTrack,
							background: ap.edgeSnap ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ edgeSnap: !ap.edgeSnap }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.edgeSnap ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.ctlRow, key: "move" },
					React.createElement("span", { style: S.ctlLabel }, t("fMoveEnabled")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.moveEnabled ? "true" : "false",
						"aria-label": t("fMoveEnabled"),
						style: {
							...S.swTrack,
							background: ap.moveEnabled ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ moveEnabled: !ap.moveEnabled }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.moveEnabled ? "21px" : "3px" } })),
				),
				ap.moveEnabled ? slider(t("fMoveInterval"), "moveIntervalSec", 10, 3600, 5, (v) => v + "s") : null,
				ap.moveEnabled ? slider(t("fMoveMin"), "moveMinDp", 8, 400, 4, (v) => v + "dp") : null,
				ap.moveEnabled ? slider(t("fMoveMax"), "moveMaxDp", 8, 400, 4, (v) => v + "dp") : null,
				React.createElement("div", { style: S.ctlRow, key: "flip" },
					React.createElement("span", { style: S.ctlLabel }, t("fFlipOnTurn")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.flipOnTurn ? "true" : "false",
						"aria-label": t("fFlipOnTurn"),
						style: {
							...S.swTrack,
							background: ap.flipOnTurn ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ flipOnTurn: !ap.flipOnTurn }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.flipOnTurn ? "21px" : "3px" } })),
				),
			]);

			const animGroup = acc("anim", t("grpAnim"), 5, [
				slider(t("fFrameInterval"), "frameIntervalMs", 20, 2000, 10, (v) => v + "ms"),
				React.createElement("div", { style: S.ctlRow, key: "spin" },
					React.createElement("span", { style: S.ctlLabel }, t("fSpin")),
					React.createElement("input", {
						type: "range", min: -720, max: 720, step: 15,
						key: "spin:" + ap.spin,
						defaultValue: ap.spin, style: S.slider,
						"aria-label": t("fSpin"),
						onChange: (e) => apply({ spin: Number(e.target.value) }),
					}),
					React.createElement("span", { style: S.ctlValue }, ap.spin + "°"),
				),
				React.createElement("div", { style: S.ctlRow, key: "pulse" },
					React.createElement("span", { style: S.ctlLabel }, t("fPulse")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.pulse ? "true" : "false",
						"aria-label": t("fPulse"),
						style: {
							...S.swTrack,
							background: ap.pulse ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ pulse: !ap.pulse }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.pulse ? "21px" : "3px" } })),
				),
				React.createElement("div", { style: S.ctlRow, key: "dot" },
					React.createElement("span", { style: S.ctlLabel }, t("fShowDot")),
					React.createElement("button", {
						type: "button", role: "switch",
						"aria-checked": ap.showDot ? "true" : "false",
						"aria-label": t("fShowDot"),
						style: {
							...S.swTrack,
							background: ap.showDot ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)",
						},
						onClick: () => apply({ showDot: !ap.showDot }),
					}, React.createElement("span", { style: { ...S.swThumb, left: ap.showDot ? "21px" : "3px" } })),
				),
				slider(t("fStatusImageSize"), "statusImageSizeDp", 32, 200, 4, (v) => v + "dp"),
			]);

			const panelGroup = acc("panel", t("grpPanel"), 4, [
				slider(t("fPanelWidth"), "panelWidth", 180, 600, 10, (v) => v + "dp"),
				slider(t("fPanelHeight"), "panelHeight", 0, 1200, 20,
					(v) => (Number(v) === 0 ? t("autoSize") : v + "dp")),
				slider(t("fStatusAutoHide"), "statusAutoHideMs", 0, 10000, 500,
					(v) => (Number(v) === 0 ? t("neverHide") : (Number(v) / 1000).toFixed(1) + "s")),
				color(t("fPanelBg"), "panelBg", t("panelBgAuto")),
				color(t("fPanelText"), "panelText"),
				color(t("fPanelAccent"), "panelAccent"),
			]);

			// ── 余额徽标（嵌套对象，需要另一套 helper）─────────────────────
			//
			// ⚠️ 上面那些 helper 只会写**顶层字段**（`apply({ [key]: v })`），
			//   而徽标是一整个 `balanceBadge` 子对象。所以这里另起一套，
			//   写的时候**整包下发**：原生 `BalanceBadge.merge` 是按子字段判
			//   "缺省即保持"的，整包下发＝把当前值原样带上，两边语义一致。
			//
			// ⚠️ 不要试图让 `apply` 支持点号路径：那条路会顺带改到别处
			//   （`apply` 是共享的），风险远大于在这里多写二十行。
			const bb = ap.balanceBadge || {};
			/** 当前徽标 + 覆盖项 → 整包。 */
			const bbPatch = (over) => ({ balanceBadge: { ...bb, ...over } });

			const bbSw = (label, key, hint) => {
				const on = bb[key] === true;
				// ⚠️ **hint 不能塞在 116px 的标签列里**：`S.ctlLabel` 带 `ellipsis`，
				//   挤进去会把主标签截成「显示余额徽标在…」（真机截图确认过）。
				//   所以标签只放 label 本身，hint 作为一个整行的副标题挂在下面。
				return React.createElement("div", { key: "bb-" + key },
					React.createElement("div", { style: S.ctlRow },
						React.createElement("span", { style: S.ctlLabel }, label),
						React.createElement("button", {
							type: "button", role: "switch",
							"aria-checked": on ? "true" : "false",
							"aria-label": label,
							style: {
								...S.swTrack,
								background: on
									? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)"
									: "var(--dsw-alias-border-l2, #c8c8c8)",
							},
							onClick: () => apply(bbPatch({ [key]: !on })),
						}, React.createElement("span", { style: { ...S.swThumb, left: on ? "21px" : "3px" } })),
					),
					hint ? React.createElement("div", { style: { ...S.swHint, padding: "-2px 0 6px" } }, hint) : null,
				);
			};

			const bbSlider = (label, key, min, max, step, fmt) => {
				const val = typeof bb[key] === "number" ? bb[key] : min;
				return React.createElement("div", { style: S.ctlRow, key: "bb-" + key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("input", {
						type: "range", min, max, step,
						defaultValue: val,
						key: "bb-" + key + ":" + val,
						style: S.slider,
						"aria-label": label,
						onInput: (e) => { e.target.dataset.live = e.target.value; },
						onChange: (e) => apply(bbPatch({ [key]: Number(e.target.value) })),
					}),
					React.createElement("span", { style: S.ctlValue }, fmt ? fmt(val) : String(val)),
				);
			};

			const bbSeg = (label, key, options) =>
				React.createElement("div", { style: S.ctlRow, key: "bb-" + key },
					React.createElement("span", { style: S.ctlLabel }, label),
					React.createElement("div", { style: S.seg, role: "group", "aria-label": label },
						options.map((o) => React.createElement("button", {
							key: o.v, type: "button",
							"aria-pressed": bb[key] === o.v ? "true" : "false",
							style: { ...S.segBtn, ...(bb[key] === o.v ? S.segBtnOn : null) },
							onClick: () => apply(bbPatch({ [key]: o.v })),
						}, o.n)),
					),
				);

			/**
			 * 颜色行（"跟随主题"是可选项，用空串表示）。
			 *
			 * ⚠️ "type="color"" 不能表达"无值"，所以配一个「跟随主题」按钮复位。
			 *   没有它的话，用户一旦点了取色器就再也回不到跟随主题。
			 */
			const bbColor = (label, key) => {
				const cur = typeof bb[key] === "string" ? bb[key] : "";
				// `<input type="color">` 只认 `#RRGGBB`；带 alpha 的 `#AARRGGBB`
				// 会被浏览器判为非法值并回退成黑色，所以这里截末 6 位。
				const hex = /^#[0-9a-fA-F]{8}$/.test(cur) ? "#" + cur.slice(3) : (cur || "#888888");
				return React.createElement("div", { style: S.ctlRow, key: "bb-" + key },
					React.createElement("span", { style: S.ctlLabel }, label),
					// ⚠️⚠️ **必须包一层 `swatchWrap`**，不能把 `swatchInput` 直接放进
					//   `ctlRow`。`swatchInput` 是 `position: absolute`，它需要一个
					//   `position: relative` 的父元素当包含块；直接放进来时包含块会
					//   退化成整个面板，透明的 input 铺满全屏 ——
					//   真机症状：面板不能上下滑，且点分组标题会弹系统颜色选择器。
					React.createElement("span", {
						style: {
							...S.swatchWrap,
							background: cur || "repeating-conic-gradient(#e0e0e0 0% 25%, #fff 0% 50%) 50% / 10px 10px",
						},
					},
						React.createElement("input", {
							type: "color",
							value: hex,
							style: S.swatchInput,
							"aria-label": label,
							onChange: (e) => apply(bbPatch({ [key]: e.target.value })),
						}),
					),
					React.createElement("button", {
						type: "button",
						style: { ...S.segBtn, ...(cur === "" ? S.segBtnOn : null) },
						"aria-pressed": cur === "" ? "true" : "false",
						onClick: () => apply(bbPatch({ [key]: "" })),
					}, t("bbFollow")),
				);
			};

			/**
			 * 徽标显示内容的**可视化编辑器**。
			 *
			 * ## 为什么不能直接给用户一个装 "{balance}" 的输入框
			 *
			 * 用户原话：「这个什么显示内容里面，你为什么显示剩？然后 Balance。你觉得这样
			 * 对用户体验好吗？还有就是上面写的点下面的词加进显示内容，点不动。」
			 *
			 * 两个问题都成立：
			 *  1. 输入框里躺着一串 "剩 {balance}{balance}…" —— 内部占位符直接摊给用户看，
			 *     这是**实现细节泄漏**，不是界面。
			 *  2. 点 chip 会追加一个 token，但界面上**没有任何变化可见**（输入框是
			 *     uncontrolled、且不重渲染），所以看起来就是"点不动"。真机上因此被
			 *     连点十几下，模板里堆了 12 个 "{balance}"。
			 *
			 * ## 现在的形态
			 *
			 * 结构变成三段，全部可视化：
			 *  - **上面一行是「成品长什么样」**：直接把当前配方渲染成示例徽标，
			 *    用户改任何东西都能立刻看到结果；
			 *  - **中间是配方本身**：每个词是一个可删的胶囊（"剩余" "余额" …），
			 *    点胶囊右侧的 × 就删掉它 —— 删得掉才叫"编辑"；
			 *  - **下面是可加的词**：点一下**加在末尾**，但**重复的词直接禁用**
			 *    （已经用过就置灰），从根上杜绝"点十几下堆成 12 个"。
			 *
			 * 自定义文字（比如 "剩"）也做成胶囊，但不可点，只作说明。
			 */

			/** token → 中文名。 */
			const BB_TOKEN_LABEL = {
				balance: "余额",
				recharge: "充值",
				bonus: "赠送",
				currency: "币种",
				cost: "本次花费",
				costSession: "本会话花费",
			};

			/**
			 * 把模板串解析成「词序列」。
			 *
			 * "剩 {balance}" ⇒ "[{lit:"剩 "}, {tok:"balance"}]"
			 *
			 * ⚠️ 解析失败的 "{xxx}"（未知 token）**按字面保留**，不要丢掉 ——
			 *   丢掉等于悄悄改用户的东西。它会在界面上显示成一个灰色的未知胶囊。
			 */
			const bbParse = (tpl) => {
				const s = typeof tpl === "string" ? tpl : "";
				const out = [];
				const re = /\{([a-zA-Z0-9_]+)\}/g;
				let last = 0;
				let m;
				while ((m = re.exec(s)) !== null) {
					if (m.index > last) out.push({ lit: s.slice(last, m.index) });
					out.push({ tok: m[1], known: !!BB_TOKEN_LABEL[m[1]] });
					last = m.index + m[0].length;
				}
				if (last < s.length) out.push({ lit: s.slice(last) });
				return out;
			};

			/** 词序列 → 模板串。 */
			const bbJoin = (parts) => parts.map((p) => (p.tok ? "{" + p.tok + "}" : p.lit)).join("");

			/**
			 * 干跑一次渲染，只用于预览（不碰原生）。
			 *
			 * 用假数据是为了让用户在**还没登录**时也能看清版式 —— 真实余额由
			 * 原生徽标显示，这里只是"长什么样"的示意。
			 */
			const bbPreviewText = (tpl) => {
				const sample = {
					balance: "138.56",
					recharge: "100.00",
					bonus: "38.56",
					currency: "¥",
					cost: "0.0031",
					costSession: "0.0420",
				};
				return String(tpl || "").replace(/\{([a-zA-Z0-9_]+)\}/g, (all, key) => (
					Object.prototype.hasOwnProperty.call(sample, key) ? sample[key] : all
				));
			};

			const bbTemplate = () => {
				const tpl = typeof bb.template === "string" && bb.template !== ""
					? bb.template
					: "{currency}{balance}";
				const parts = bbParse(tpl);
				const used = new Set(parts.filter((p) => p.tok).map((p) => p.tok));

				/** 删掉第 i 个词（literal 也可以删）。 */
				const removeAt = (i) => {
					const next = parts.slice();
					next.splice(i, 1);
					const v = bbJoin(next);
					apply(bbPatch({ template: v.trim() === "" ? "{currency}{balance}" : v }));
				};

				return React.createElement("div", { style: S.bbEditor, key: "bb-template" },
					React.createElement("span", { style: S.ctlLabel }, t("fBbTemplate")),

					// ── ① 成品预览 ────────────────────────────────────
					React.createElement("div", { style: S.bbPreviewRow },
						React.createElement("span", { style: S.swHint }, t("bbPreviewHint")),
						React.createElement("span", { style: S.bbPreviewBadge },
							bbPreviewText(tpl) || "—"),
					),

					// ── ② 当前配方（可删） ────────────────────────────
					React.createElement("div", { style: S.bbTokenRow },
						...parts.map((p, i) => React.createElement("span", {
							key: i,
							style: p.tok && !p.known ? S.bbChipUnknown : (p.tok ? S.bbChip : S.bbChipLit),
						},
							React.createElement("span", null, p.tok
								? (BB_TOKEN_LABEL[p.tok] || p.tok)
								: p.lit),
							React.createElement("button", {
								type: "button",
								style: S.bbChipX,
								"aria-label": t("bbRemove") + " " + (p.tok ? (BB_TOKEN_LABEL[p.tok] || p.tok) : p.lit),
								onClick: () => removeAt(i),
							}, "×"),
						)),
						parts.length === 0
							? React.createElement("span", { style: S.swHint }, t("bbEmpty"))
							: null,
					),

					// ── ③ 可加的词（已用过就禁用） ────────────────────
					React.createElement("div", { style: S.swHint }, t("bbTokenHint")),
					React.createElement("div", { style: S.bbTokenRow },
						...Object.keys(BB_TOKEN_LABEL).map((tok) => {
							const already = used.has(tok);
							return React.createElement("button", {
								key: tok,
								type: "button",
								disabled: already,
								style: {
									...S.bbToken,
									...(already ? S.bbTokenUsed : null),
								},
								"aria-label": BB_TOKEN_LABEL[tok],
								onClick: () => {
									if (already) return;
									apply(bbPatch({ template: tpl + "{" + tok + "}" }));
								},
							}, BB_TOKEN_LABEL[tok]);
						}),
					),

					// ── ④ 恢复默认 ────────────────────────────────────
					//
					// 用户把顺序点成 `{balance}{currency}` 之后，渲染出来是
					// `138.56¥` —— 币种跑到金额后面，看着就是错的。胶囊只能
					// 一个个删再一个个加，太笨；给一个一键回到 `¥138.56` 的出口。
					React.createElement("div", { style: { display: "flex", gap: "8px" } },
						React.createElement("button", {
							type: "button",
							style: S.btnQuiet,
							onClick: () => apply(bbPatch({ template: "{currency}{balance}" })),
						}, t("bbReset")),
					),
				);
			};

			const badgeGroup = acc("badge", t("grpBalance"), 6, [
				bbSw(t("fBbEnabled"), "enabled", t("bbEnabledHint")),
				bbSeg(t("fBbAnchor"), "anchor", [
					{ v: "top-right", n: t("bbAnchorTR") },
					{ v: "top-left", n: t("bbAnchorTL") },
					{ v: "bottom-right", n: t("bbAnchorBR") },
					{ v: "bottom-left", n: t("bbAnchorBL") },
					{ v: "above", n: t("bbAnchorAbove") },
					{ v: "below", n: t("bbAnchorBelow") },
				]),
				bbTemplate(),
				bbSlider(t("fBbGap"), "gapDp", -60, 120, 2, (v) => v + "dp"),
				bbSlider(t("fBbFontSize"), "fontSizeSp", 7, 40, 1, (v) => v + "sp"),
				bbSlider(t("fBbRadius"), "radiusDp", 0, 100, 1, (v) => v + "dp"),
				bbSlider(t("fBbPaddingH"), "paddingHDp", 0, 40, 1, (v) => v + "dp"),
				bbSlider(t("fBbPaddingV"), "paddingVDp", 0, 40, 1, (v) => v + "dp"),
				bbSlider(t("fBbMaxWidth"), "maxWidthDp", 0, 600, 10,
					(v) => (Number(v) === 0 ? t("autoSize") : v + "dp")),
				bbSlider(t("fBbBorderWidth"), "borderWidthDp", 0, 8, 1, (v) => v + "dp"),
				bbColor(t("fBbBg"), "bg", t("panelBgAuto")),
				bbColor(t("fBbFg"), "fg"),
				bbColor(t("fBbBorder"), "border"),
				bbSw(t("fBbCostFlash"), "showCostFlash"),
				bbSw(t("fBbHideIdle"), "hideWhenIdle"),
				bbSw(t("fBbTurnEnd"), "refreshOnTurnEnd"),
				bbSlider(t("fBbEvery"), "refreshEverySec", 0, 3600, 30,
					(v) => (Number(v) === 0 ? t("bbOff") : (Number(v) >= 60
						? (Number(v) / 60).toFixed(0) + t("bbMinute")
						: v + "s"))),
				bbSw(t("fBbTapRefresh"), "refreshOnTap"),
				bbSlider(t("fBbTapMin"), "tapRefreshMinIntervalSec", 1, 600, 1, (v) => v + "s"),
			]);

			const stateGroup = acc("state", t("grpState"), 1, [
				seg(t("fState"), "state", [
					{ v: "auto", n: t("stAuto") },
					{ v: "collapsed", n: t("stCollapsed") },
					{ v: "expanded", n: t("stExpanded") },
				]),
			]);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("panelTitle")),
				React.createElement("p", { style: S.intro }, t("panelIntro")),
				stateGroup,
				appearanceGroup,
				colorGroup,
				motionGroup,
				animGroup,
				panelGroup,
				badgeGroup,
				React.createElement("div", { style: { ...S.intro, marginTop: "12px" } },
					React.createElement("button", {
						type: "button", style: S.btn, onClick: read,
					}, t("recheck")),
				),
			);
		}

		function mpBubbleSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_BUBBLE_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [showDetails, setShowDetails] = React.useState(false);
			// 键盘避让的状态：开关在 SharedPreferences、无障碍授权在 Settings.Secure，
			// 两样都只能从原生桥读（与气泡开关同一套事实来源）。
			const [kb, setKb] = React.useState({ phase: "loading" });

			/**
			 * 从原生读一次状态。
			 *
			 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），所以先查再调 ——
			 *   直接调会抛 "TypeError: Cannot read properties of undefined"。
			 */
			const refresh = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.bubbleState !== "function") {
					setState({ phase: "ready", enabled: false, granted: false, noBridge: true });
					return;
				}
				try {
					// 桥返回的是 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
					const raw = bridge.bubbleState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setState({
						phase: "ready",
						enabled: !!o.enabled,
						granted: !!o.granted,
						noBridge: false,
						throwEnabled: !!o.throwEnabled,
						imeFlingEnabled: !!o.imeFlingEnabled,
						imeFlingAvailable: !!o.imeFlingAvailable,
						imeFlingReason: o.imeFlingReason || null,
						shakeEnabled: !!o.shakeEnabled,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取气泡状态失败:", e && e.message);
					setState({ phase: "ready", enabled: false, granted: false, noBridge: true });
				}
			}, []);

			React.useEffect(() => { refresh(); }, [refresh]);

			/**
			 * 读「键盘避让」状态。
			 *
			 * ⚠️ 单独一个方法而不是塞进 `bubbleState()`：两者更新时机不同 ——
			 *   无障碍授权只在用户离开本页去系统设置时才会变，而气泡开关在本页
			 *   就会被改。分开读可以各自刷新，互不牵连。
			 */
			const refreshKb = React.useCallback(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.keyboardAvoidState !== "function") {
					setKb({ phase: "ready", enabled: false, a11y: false, source: "none", noBridge: true });
					return;
				}
				try {
					const raw = bridge.keyboardAvoidState();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					// `source` 是原生算好的最终判据：`a11y`（快）/ `shell`（慢）/
					// `none`（两条路都没有 ⇒ 开关必须置灰）。页面不自己推导，
					// 否则同一套优先级要在两处各写一遍，容易漂。
					setKb({
						phase: "ready",
						enabled: !!o.enabled,
						a11y: !!o.a11y,
						a11yOn: !!o.a11yOn,
						shell: !!o.shell,
						source: o.source || "none",
						noBridge: false,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取键盘避让状态失败:", e && e.message);
					setKb({ phase: "ready", enabled: false, a11y: false, source: "none", noBridge: true });
				}
			}, []);

			React.useEffect(() => { refreshKb(); }, [refreshKb]);

			/**
			 * 从系统设置页回来后刷新。
			 *
			 * ⚠️ 授权是**在别的界面**完成的，本页拿不到任何回调。只能监听
			 *   "visibilitychange"（用户切回来时触发）。不加这个的话，用户授完权
			 *   回来看到的还是「未授权」，会以为没生效。
			 */
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) { refresh(); refreshKb(); } };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [refresh, refreshKb]);

			const toggle = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.bubbleSetEnabled !== "function") return;
				try {
					bridge.bubbleSetEnabled(!state.enabled);
					// 乐观更新：桥是同步的，但立刻重读一次更可靠（也顺带验证写入生效）。
					refresh();
				} catch (e) {
					console.warn("[dsh-android-mobile] 切换气泡失败:", e && e.message);
				}
			};

			const grant = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.bubbleRequestOverlay === "function") {
					try { bridge.bubbleRequestOverlay(); } catch (e) { /* 下面统一提示 */ }
				}
			};

			/**
			 * 一枚自绘开关。
			 *
			 * @param {string} name - 名称。
			 * @param {string} hint - 一句说明。
			 * @param {boolean} on - 当前值。
			 * @param {Function} onToggle - 点击回调。
			 * @param {boolean} disabled - 置灰（无能力时用）。
			 * @param {string|null} why - 置灰原因，显示在说明下方。
			 */
			const sw = (name, hint, on, onToggle, disabled, why) =>
				React.createElement("div", { style: S.swRow, key: name },
					React.createElement("div", { style: S.swMain },
						React.createElement("span", { style: S.swName }, name),
						React.createElement("span", { style: S.swHint }, hint),
						why ? React.createElement("span", { style: S.swWhy }, why) : null,
					),
					React.createElement("button", {
						type: "button",
						role: "switch",
						"aria-checked": on ? "true" : "false",
						"aria-label": name,
						disabled: !!disabled,
						style: {
							...S.swTrack,
							background: disabled
								? "var(--dsw-alias-border-l2, #dcdcdc)"
								: (on ? "var(--dsw-alias-interactive-bg-primary, #2b6cb0)" : "var(--dsw-alias-border-l2, #c8c8c8)"),
							cursor: disabled ? "not-allowed" : "pointer",
							opacity: disabled ? 0.55 : 1,
						},
						onClick: disabled ? undefined : onToggle,
					}, React.createElement("span", {
						style: { ...S.swThumb, left: on ? "21px" : "3px" },
					})),
				);

			// ── 键盘避让的两个动作 ────────────────────────────────────────

			const toggleKb = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.keyboardAvoidSetEnabled !== "function") return;
				try {
					bridge.keyboardAvoidSetEnabled(!kb.enabled);
					// 同步重读：桥是同步的，读回来才是"落盘后的事实"。
					refreshKb();
				} catch (e) {
					console.warn("[dsh-android-mobile] 切换键盘避让失败:", e && e.message);
				}
			};

			const grantKb = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.keyboardAvoidRequestPermission === "function") {
					try { bridge.keyboardAvoidRequestPermission(); } catch (e) { /* 由原生侧记日志 */ }
				}
			};

			if (state.phase === "loading" || kb.phase === "loading") {
				return React.createElement("div", { style: S.section },
					React.createElement("h2", { style: S.heading }, t("title")),
					React.createElement("p", { style: S.intro }, t("statusUnknown")),
				);
			}

			const noBridge = state.noBridge;
			const ready = state.enabled && state.granted;
			const tone = ready ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;
			const badgeText = ready ? t("statusOn") : t("statusOff");

			// 「下一步」按状态级联挑一条 —— 与特权权限分节同一套思路：
			// 不让用户自己从几行技术明细里翻译出该做什么。
			let nextText = t("nextAllGood");
			let nextBtn = null;
			if (noBridge) {
				nextText = t("noBridge");
			} else if (!state.granted) {
				nextText = t("nextNeedPerm");
				nextBtn = React.createElement("button", { style: S.btnPrimary, onClick: grant }, t("grant"));
			} else if (!state.enabled) {
				nextText = t("nextNeedEnable");
				nextBtn = React.createElement("button", { style: S.btnPrimary, onClick: toggle }, t("enable"));
			} else {
				nextBtn = React.createElement("button", { style: S.btnQuiet, onClick: toggle }, t("disable"));
			}

			// 键盘避让的「下一步」——只给结论文案，动作交给卡片里的开关与按钮。
			//
			// `kb.source` 是原生算好的最终判据（`a11y` / `shell` / `none`），
			// 页面不自己推导优先级。三条结论互斥且按"用户还差什么"排序：
			//   none   → 什么都做不了，开关置灰
			//   shell  → 能用但有延迟，建议开无障碍（这是本卡片存在的意义）
			//   a11y   → 已就绪
			const kbNoSource = !kb.noBridge && kb.source === "none";
			const kbReady = kb.enabled && !kbNoSource &&
				(kb.source === "a11y" || kb.source === "shell");
			const kbTone = kbReady ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;
			let kbNextText = t("kbNextAllGood");
			if (kb.noBridge) {
				kbNextText = t("noBridge");
			} else if (kbNoSource) {
				kbNextText = t("kbNextNoSource");
			} else if (!kb.enabled) {
				kbNextText = t("kbNextDisabled");
			} else if (kb.source === "shell") {
				kbNextText = t("kbNextShellSlow");
			}

			// 开关的样式：做成真正的 switch（宽 46 / 高 26 的轨道 + 白色圆钮）。
			// 之前这里是一个「打开开关 / 关闭」按钮 —— 用户明确要求改成开关形态：
			// 开关一眼能看出"当前是开还是关"，按钮要靠读文字才知道。
			const kbSwitchRow = { display: "flex", alignItems: "center", gap: "10px" };
			const kbSwitchLabel = { flex: "1 1 auto", fontSize: "13px", lineHeight: "19px" };
			const kbSwitchTrack = (on, disabled) => ({
				position: "relative", flex: "0 0 auto", width: "46px", height: "26px",
				borderRadius: "13px", border: "none", padding: 0,
				cursor: disabled ? "default" : "pointer",
				opacity: disabled ? 0.5 : 1,
				background: on ? MP_PRIV_TONE.on.color : "var(--dsw-alias-border-l2, #cfcfcf)",
				transition: "background .18s",
			});
			const kbSwitchKnob = (on) => ({
				position: "absolute", top: "3px", left: on ? "23px" : "3px",
				width: "20px", height: "20px", borderRadius: "10px",
				background: "#fff", boxShadow: "0 1px 2px rgba(0,0,0,.25)",
				transition: "left .18s",
			});

			const dot = (ok) => React.createElement("span", {
				style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
			});
			const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
				dot(ok),
				React.createElement("span", { style: S.rowLabel }, label),
				React.createElement("span", { style: S.rowValue }, value),
			);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: tone.color, background: tone.bg },
						}, badgeText),
					),
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, nextText),
						nextBtn ? React.createElement("div", { style: S.actions }, nextBtn) : null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refresh }, t("recheck")),
						React.createElement("button", {
							style: S.btnQuiet,
							onClick: () => setShowDetails((v) => !v),
						}, showDetails ? t("hideDetails") : t("showDetails")),
					),
					showDetails ? React.createElement("div", { style: S.details },
						row(t("rowSwitch"), state.enabled, state.enabled ? t("on") : t("off")),
						row(t("rowPerm"), state.granted, state.granted ? t("granted") : t("notGranted")),
					) : null,
				),

				// ── 甩动手感：**不在这里放** ─────────────────────────────
				//
				// 用户原话：「下面明明已经有了选项，你为什么在界面的最上面还要留有开关呢？
				// 你留有也就算了，你还有 bug」。
				//
				// 确实如此：拖拽抛掷 / 晃动甩飞 / 键盘顶飞 三个开关，在下面
				// `mpBubblePanel` 的「动作与交互」分组里**已经有完整的一份**，
				// 而且那一份走 `bubbleApply`（改完立刻推给活的球）。
				// 上面这一份走 `setMotion` → 原生只 `save` 不 `apply`，
				// 于是同一个功能两个入口、两种行为 —— 上面那个还慢半拍。
				//
				// 所以整块删掉，只留下方那一份。少一处入口，少一处不一致。
				// （原生侧已顺手修好 `bubbleSetThrow` / `bubbleSetShake` /
				//  `bubbleSetImeFling` 只落盘不生效的问题，AI 工具与其它调用方
				//  仍然走那三个桥方法，行为与这里一致。）
				// ── 外观与手感控制面板 ──────────────────────────────
				// 单独一个组件：这里要摆 BubbleAppearance 的全部可调项，
				// 撑进本分节会让「开关气泡 / 权限状态」那段首屏失控。
				React.createElement(mpBubblePanel, { t }),

				// ── 键盘避让：与气泡同一套卡片结构（结论优先 + 明细可折叠）──
				//
				// ⚠️ 它单独一张卡而不是塞进上面那张：权限是**另一件事**
				//   （无障碍 vs 悬浮窗），塞进一张卡会让"当前状态"这个徽章
				//   同时代表两套权限，用户没法判断还差哪一步。
				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("kbTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: kbTone.color, background: kbTone.bg },
						}, kbReady ? t("statusOn") : t("statusOff")),
					),
					// 开关行：功能开关本体，点一下立即生效（原生侧推给正在显示的球）。
					//
					// ⚠️ `kbNoSource` 时置灰：两条检测路径都没有（既没开无障碍、
					//   也没有 Shizuku/root），打开这个开关不会有任何行为 —— 让用户
					//   打开一个注定不生效的开关，比直接说明原因更糟。
					React.createElement("div", { style: kbSwitchRow },
						React.createElement("span", { style: kbSwitchLabel }, t("kbRowSwitch")),
						React.createElement("button", {
							role: "switch",
							"aria-checked": kb.enabled,
							disabled: kb.noBridge || kbNoSource,
							title: kb.enabled ? t("on") : t("off"),
							onClick: toggleKb,
							style: kbSwitchTrack(kb.enabled, kb.noBridge || kbNoSource),
						}, React.createElement("span", { style: kbSwitchKnob(kb.enabled) })),
					),
					// 权限行：没授权时给一个明确的「去开启权限」按钮。
					// 开关开着时用实心（这才是当前该做的一步）；开关关着时降级成描边 ——
					// 功能还没开就先把主按钮推给用户，会显得在硬塞权限。
					//
					// ⚠️ 已经走 SHELL（有特权但慢）时也显示这个按钮：那条路延迟 2 秒起，
					//   开无障碍能把延迟压到一个数量级以下，正是本卡片要推的事。
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, kbNextText),
						(!kb.noBridge && !kb.a11y)
							? React.createElement("div", { style: S.actions },
								React.createElement("button", {
									style: kb.enabled ? S.btnPrimary : S.btn,
									onClick: grantKb,
								}, t("kbGrant")))
							: null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refreshKb }, t("recheck")),
					),
					React.createElement("div", { style: S.details },
						row(t("kbRowPerm"), kb.a11y, kb.a11y ? t("granted") : t("notGranted")),
						row(t("kbRowSource"), kb.source !== "none",
							kb.source === "a11y" ? t("kbSourceA11y")
								: kb.source === "shell" ? t("kbSourceShell")
								: t("kbSourceNone")),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("kbHowTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("kbHow1"), t("kbHow2"), t("kbHow3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「悬浮气泡」注册进设置页一级导航。
		 *
		 * 与特权权限分节同一套接缝（"settings.section"），所以不需要碰导航 DOM。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterBubbleSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过悬浮气泡分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_BUBBLE_NS);
					scope.effect(
						() => scope.locale.register(MP_BUBBLE_NS, { zh: MP_BUBBLE_ZH, en: MP_BUBBLE_EN }),
						"dsh-android-mobile: 悬浮气泡字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-bubble",
						// 紧跟在「特权权限」（25）之后。
						order: 26,
						label: () => t("nav"),
						locale: MP_BUBBLE_NS,
					}, mpBubbleSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册悬浮气泡分节失败:", e && (e.stack || e.message || e));
			}
		}

		/* ══ 「交流群」分节：二维码 + 群号 ══

		   ⚠️ **二维码必须内联成 data URI，不能放成独立图片文件。**
		     官方 "dsh-client-modules" 的 "/plugins/<id>/<file>" 路由**只服务
		     "client.js" 与它的 sourcemap**（"index.js" 的 "PLUGIN_ROUTE" 与
		     "chunkUrl()" 都写死了这两个后缀），**没有**通用静态资源目录。
		     把二维码写成 "./qr.webp" 会得到一个 404 的碎图 —— 而且失败方式是
		     静默的（"<img>" 加载失败不抛异常，只是不显示）。
		     插件自身也没有别的落地目录可以断言「引擎一定会把它对客户端暴露」。

		   格式选 WebP 而非 PNG：同一张图 PNG 要 93~129 KB，WebP(q92) 只要
		   31~70 KB，体积约 1/3。而 WebP 从 Chrome 32 就有解码支持，远低于本
		     项目的最低内核门槛（"MIN_WEBVIEW_MAJOR_COMPAT = 74"），不构成新约束。

		   ⚠️ 源图是**彩色圆点风格**的二维码，"cv2.QRCodeDetector" 解不出来
		     （实测原图/裁剪/灰度/二值化/放大全 FAIL），换 "zxing-cpp" 立刻成功。
		     验证这类图的可扫性**必须用 zxing**，别因为 OpenCV 报 FAIL 就判定图片
		     坏了 —— 那是解码器对非标准模块形状的识别能力差异，不是图的问题。

		   可扫性验证方式（比「肉眼看没问题」可靠）：把 data URI 按真实布局
		     （视口 390 CSS px + "--force-device-scale-factor=3"）用 Chrome 渲染成
		     截图，再交给 zxing 解码。400/512/640/852 四档源图**全部解码成功**，
		     说明「内联 → WebP 解码 → CSS 缩到 240px → dpr=3 渲染」整条链路的
		     余量都够。 */
		const MP_CONTACT_NS = "dsh-android-mobile.contact";
		/** 群号：从二维码图里读出的群名「DSHAPP交流群2」下方的数字。 */
		const MP_CONTACT_GROUP_NO = "1109733098";
		/** 二维码内联 data URI（WebP）。生成方式与可扫性验证见上方注释。 */
		const MP_CONTACT_QR = "data:image/webp;base64,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";
		const MP_CONTACT_ZH = {
			nav: "交流群",
			title: "交流群",
			intro: "遇到问题、想反馈 bug，或者想聊聊怎么用，都欢迎进群。群里也会同步新版本和已知问题。",
			groupLabel: "群号",
			copy: "复制群号",
			copied: "已复制",
			copyFailed: "复制失败",
			qrAlt: "交流群二维码",
			howTitle: "怎么加群",
			how1: "用另一台设备上的手机 QQ「扫一扫」扫上面的二维码。",
			how2: "或者打开 QQ → 右上角「+」→ 加好友/群 → 搜群号，粘贴上面的群号。",
			how3: "群号是纯数字，长按选不中（本插件禁用了全局选中），所以用右边的按钮复制。",
			notesTitle: "反馈问题的小建议",
			note1: "说清楚「哪一步、期望什么、实际什么」，比只说「用不了」好定位得多。",
			note2: "设置页里「运行日志」能导出会话记录，贴上来最有用。",
			note3: "顺带说下机型和系统版本（例如「小米 13 / MIUI 14」），很多问题只出在特定机型上。",
		};
		const MP_CONTACT_EN = {
			nav: "Community group",
			title: "Community group",
			intro: "Found a bug, or want to talk about how to use it? Join the group. New releases and known issues are posted there too.",
			groupLabel: "Group ID",
			copy: "Copy ID",
			copied: "Copied",
			copyFailed: "Copy failed",
			qrAlt: "Community group QR code",
			howTitle: "How to join",
			how1: "Scan the QR code above with QQ's scanner on another device.",
			how2: "Or open QQ → \"+\" at the top right → Add friend/group → search the group ID, then paste the ID above.",
			how3: "The ID is digits only and cannot be selected (this plugin disables global text selection), so use the copy button.",
			notesTitle: "Tips for reporting issues",
			note1: "Describe \"which step, what you expected, what actually happened\" — far easier to locate than \"it doesn't work\".",
			note2: "The \"Session log\" section in Settings can export the conversation; attaching it helps most.",
			note3: "Mention your device and OS version (e.g. \"Xiaomi 13 / MIUI 14\") — many issues are model-specific.",
		};

		/**
		 * 「交流群」分节组件。
		 *
		 * 纯静态信息（无 bridge、无原生状态），唯一的有状态部分是复制按钮的
		 * 短暂反馈。布局沿用「特权权限 / 悬浮气泡」那套卡片风格。
		 *
		 * @param {object} props - 槽位注入面（只用 "t"）。
		 * @returns {object} React 元素。
		 */
		function mpContactSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_CONTACT_ZH[key] ?? key;
			const [copyState, setCopyState] = React.useState("idle");
			const timerRef = React.useRef(0);

			React.useEffect(() => () => { if (timerRef.current) window.clearTimeout(timerRef.current); }, []);

			const doCopy = () => {
				mpCopyText(MP_CONTACT_GROUP_NO).then((ok) => {
					setCopyState(ok ? "done" : "failed");
					if (timerRef.current) window.clearTimeout(timerRef.current);
					// 2 秒后回到常态：文案在「复制群号 / 已复制」间切换，按钮宽度固定，
					// 不会让整行来回跳（与权限分节的复制按钮同一处理）。
					timerRef.current = window.setTimeout(() => setCopyState("idle"), 2000);
				});
			};

			const copyLabel = copyState === "done" ? t("copied")
				: copyState === "failed" ? t("copyFailed")
				: t("copy");

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.qrWrap },
						React.createElement("img", {
							style: S.qrImg,
							src: MP_CONTACT_QR,
							alt: t("qrAlt"),
							// 二维码是装饰性内容，不让长按弹出「保存图片」菜单：
							// 保存下来也没用（用户要的是加群，不是存图）。
							draggable: false,
						}),
					),
					React.createElement("div", { style: S.row },
						React.createElement("span", { style: S.rowLabel }, t("groupLabel")),
						React.createElement("span", { style: S.mpGroupNo }, MP_CONTACT_GROUP_NO),
					),
					React.createElement("div", { style: S.cmdRow },
						React.createElement("button", {
							type: "button",
							style: S.cmdBtn,
							onClick: doCopy,
						}, copyLabel),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ol", { style: S.steps },
							[t("how1"), t("how2"), t("how3")].map((line, i) =>
								React.createElement("li", { style: S.step, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「交流群」注册进设置页一级导航。
		 *
		 * 与前两个分节同一套接缝（"settings.section"），所以不需要碰导航 DOM。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterContactSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过交流群分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_CONTACT_NS);
					scope.effect(
						() => scope.locale.register(MP_CONTACT_NS, { zh: MP_CONTACT_ZH, en: MP_CONTACT_EN }),
						"dsh-android-mobile: 交流群字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-contact",
						// 紧跟在「悬浮气泡」（26）之后 —— 反馈入口排在设置页最后，
						// 与「关于 / 帮助」的惯例一致。
						order: 27,
						label: () => t("nav"),
						locale: MP_CONTACT_NS,
					}, mpContactSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册交流群分节失败:", e && (e.stack || e.message || e));
			}
		}

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

		/* ══ 「运行日志与诊断」分节 ══

		   需求（用户原话）：做一个"极其强壮、极其详细"的日志系统，两个入口 ——
		   一个在设置界面，一个在崩溃界面（加载界面）；崩溃与加载界面"不管任何
		   时候都要有这个日志接口"。

		   本分节就是**设置界面那个入口**。它自己不显示日志内容，只做三件事：

		     ① 让用户知道日志系统存在（默认没人会去翻文件系统）
		     ② 给出摘要（多少条、几条错误、几份崩溃现场、几份待上传）——
		        用户看到"3 条错误、2 份崩溃现场"才知道值得点进去
		     ③ 一键拉起**原生**日志界面

		   ⚠️ **为什么不把日志界面做成页面插件**：因为它要能在「页面根本打不开」
		     的时候用。插件没激活、前端 bundle 挂了、引擎没起来 —— 这三种情况
		     恰好都是最需要看日志的时候，而那时页面插件自己也活不了。
		     所以真正的界面在原生侧（"LogViewerActivity"），本分节只是个入口。

		   ⚠️ 另一个入口在**启动/崩溃浮层**上（"activity_main.xml" 的
		     "@+id/overlayLogs"），它常驻显示、不依赖任何 Web 层。两条路合起来
		     覆盖「界面能开」与「界面开不了」两种情况。 */

		const MP_LOGS_NS = "dsh-android-mobile.logs";
		const MP_LOGS_ZH = {
			nav: "运行日志与诊断",
			title: "运行日志与诊断",
			intro: "应用、引擎、网页三方面的日志都会记下来。出问题时点进去看，或一键打包发给开发者 —— 包里带着设备信息、崩溃现场与自动结论。",
			statusTitle: "当前状态",
			statusHasError: "有错误",
			statusHasCrash: "有崩溃现场",
			statusOk: "正常",
			statusUnknown: "读取中",
			rowTotal: "日志条数",
			rowWarn: "警告",
			rowError: "错误",
			rowCrash: "崩溃现场",
			rowPending: "待上传",
			rowLastExit: "上次退出",
			open: "打开日志界面",
			refresh: "刷新",
			showDetails: "查看详情",
			hideDetails: "收起详情",
			nextHasCrash: "上次留下了崩溃现场，建议点「打开日志界面」导出诊断包发给开发者。",
			nextHasError: "日志里有错误记录，可以点进去看看。",
			nextOk: "一切正常。遇到问题随时回来这里。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法打开日志界面。",
			uploadOn: "已配置日志服务器（可在日志界面一键上传）",
			uploadOff: "尚未配置日志服务器（只能导出到本地）",
			howTitle: "怎么用",
			how1: "点「打开日志界面」—— 那是原生界面，**引擎没起来时也能用**。",
			how2: "界面里可以按级别（警告/错误）和来源（应用/引擎/网页/崩溃）筛选，也能搜关键字。",
			how3: "「导出诊断包」生成一个 zip：含设备信息、三份日志、崩溃现场和一份自动结论（diagnosis.json）。",
			how4: "「上传给开发者」直接发出去；失败会留在本地，下次启动自动重试。",
			notesTitle: "几点说明",
			note1: "日志会脱敏：token、api key、密钥一类的内容写盘前就被替换掉了。",
			note2: "日志不记录会话正文，只记元信息（哪个会话、什么时间、什么动作）。",
			note3: "启动/崩溃界面上也有一个「查看日志」按钮 —— 应用打不开时从那里进。",
			note4: "「清空」会删掉已落盘的日志与崩溃现场，不可恢复。",
		};
		const MP_LOGS_EN = {
			nav: "Logs & diagnostics",
			title: "Logs & diagnostics",
			intro: "Logs from the app, the engine and the web UI are all recorded. When something breaks, open the log viewer or bundle everything into one file for the developer — it includes device info, crash scenes and an automatic diagnosis.",
			statusTitle: "Current status",
			statusHasError: "Errors present",
			statusHasCrash: "Crash scene present",
			statusOk: "OK",
			statusUnknown: "Reading",
			rowTotal: "Log entries",
			rowWarn: "Warnings",
			rowError: "Errors",
			rowCrash: "Crash scenes",
			rowPending: "Pending upload",
			rowLastExit: "Last exit",
			open: "Open log viewer",
			refresh: "Refresh",
			showDetails: "Show details",
			hideDetails: "Hide details",
			nextHasCrash: "A crash scene was recorded. Open the log viewer and export a diagnostic bundle to send to the developer.",
			nextHasError: "There are errors in the log — open the viewer to inspect them.",
			nextOk: "All clear. Come back here whenever something misbehaves.",
			noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot open the log viewer.",
			uploadOn: "Log server configured (one-tap upload in the viewer)",
			uploadOff: "No log server configured yet (export to a local file instead)",
			howTitle: "How to use it",
			how1: "Tap \"Open log viewer\" — that is a native screen and works even when the engine never started.",
			how2: "Filter by level (warnings/errors) and by source (app/engine/web/crash); search by keyword.",
			how3: "\"Export bundle\" produces a zip with device info, three logs, crash scenes and an automatic diagnosis (diagnosis.json).",
			how4: "\"Upload\" sends it straight away; if it fails the bundle stays locally and is retried on next launch.",
			notesTitle: "Notes",
			note1: "Logs are redacted: tokens, API keys and secrets are replaced before anything is written to disk.",
			note2: "Message bodies are not logged — only metadata (which session, when, what action).",
			note3: "The launch/crash overlay also has a \"View logs\" button, for when the app itself won't open.",
			note4: "\"Clear\" deletes on-disk logs and crash scenes permanently.",
		};

		/**
		 * 「运行日志与诊断」分节组件。
		 *
		 * 布局沿用「特权权限」「悬浮气泡」那套**结论优先**的结构：
		 * head 卡给状态徽章 + 一条「下一步」+ 直达按钮，明细折进 "<details>"。
		 *
		 * ⚠️ 摘要数据来自 "DSHAndroid.logSummary()"（同步返回 JSON 字符串）。
		 *   它刻意只读内存缓冲 + 一个小 JSON，**不读磁盘日志** —— 设置页每次
		 *   打开都会调它，读几 MB 的文件算行数会明显卡顿。
		 *
		 * @param {object} props - 槽位注入面（只用 "t"）。
		 * @returns {object} React 元素。
		 */
		function mpLogsSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_LOGS_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [showDetails, setShowDetails] = React.useState(false);

			/**
			 * 从原生读一次摘要。
			 *
			 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），先查再调 ——
			 *   直接调会抛 "TypeError: Cannot read properties of undefined"。
			 *
			 * ⚠️ **必须补读一次**（"again=true" 的那趟）：gecko 版的 "logSummary()"
			 *   读的是**快照缓存**（native messaging 只能异步，见 content.js），
			 *   第一次拿到的可能是旧值；那边在读取时顺带请求原生重推快照，
			 *   所以隔一拍再读就是新值。system 版是同步真值，补读无副作用。
			 */
			const refreshRef = React.useRef(null);
			const refresh = React.useCallback((again) => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.logSummary !== "function") {
					setState({ phase: "ready", noBridge: true });
					return;
				}
				try {
					const raw = bridge.logSummary();
					const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
					setState({
						phase: "ready",
						noBridge: false,
						total: o.total | 0,
						warn: o.warn | 0,
						error: o.error | 0,
						crash: o.crash | 0,
						pending: o.pending | 0,
						lastExit: typeof o.lastExit === "string" ? o.lastExit : "",
						uploadConfigured: !!o.uploadConfigured,
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 读取日志摘要失败:", e && e.message);
					setState({ phase: "ready", noBridge: true });
				}
				// ⚠️ 用 `!== true` 而不是 `!again`：本函数也挂在事件监听上，
				//   那时第一个参数是 event 对象（truthy），会把补读吃掉。
				if (again !== true) {
					setTimeout(() => { if (refreshRef.current) refreshRef.current(true); }, 300);
				}
			}, []);
			refreshRef.current = refresh;

			React.useEffect(() => { refresh(); }, [refresh]);

			/**
			 * 从日志界面回来后刷新。
			 *
			 * ⚠️ 用户点「清空」或「上传」是在**另一个 Activity** 里做的，本页拿不到
			 *   任何回调。只能监听 "visibilitychange" —— 不加的话用户清空完回来
			 *   看到的还是旧数字，会以为没生效（悬浮气泡分节踩过同一个坑）。
			 */
			React.useEffect(() => {
				const onVisible = () => { if (!document.hidden) refresh(); };
				document.addEventListener("visibilitychange", onVisible);
				window.addEventListener("focus", onVisible);
				return () => {
					document.removeEventListener("visibilitychange", onVisible);
					window.removeEventListener("focus", onVisible);
				};
			}, [refresh]);

			const open = () => {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.openLogs === "function") {
					try { bridge.openLogs(); } catch (e) {
						console.warn("[dsh-android-mobile] 打开日志界面失败:", e && e.message);
					}
				}
			};

			if (state.phase === "loading") {
				return React.createElement("div", { style: S.section },
					React.createElement("h2", { style: S.heading }, t("title")),
					React.createElement("p", { style: S.intro }, t("statusUnknown")),
				);
			}

			const noBridge = state.noBridge;
			const crash = state.crash || 0;
			const error = state.error || 0;
			// 严重度按「有没有崩溃现场」> 「有没有错误」排 —— 崩溃现场是硬证据，
			// 错误可能只是第三方路由的一次 429。
			const tone = crash > 0 ? MP_PRIV_TONE.off : (error > 0 ? MP_PRIV_TONE.warn : MP_PRIV_TONE.on);
			const badgeText = crash > 0 ? t("statusHasCrash") : (error > 0 ? t("statusHasError") : t("statusOk"));

			let nextText = t("nextOk");
			if (noBridge) nextText = t("noBridge");
			else if (crash > 0) nextText = t("nextHasCrash");
			else if (error > 0) nextText = t("nextHasError");

			const dot = (ok) => React.createElement("span", {
				style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
			});
			const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
				dot(ok),
				React.createElement("span", { style: S.rowLabel }, label),
				React.createElement("span", { style: S.rowValue }, value),
			);

			return React.createElement("div", { style: S.section },
				React.createElement("h2", { style: S.heading }, t("title")),
				React.createElement("p", { style: S.intro }, t("intro")),

				React.createElement("div", { style: S.card },
					React.createElement("div", { style: S.cardHead },
						React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
						React.createElement("span", {
							style: { ...S.badge, color: tone.color, background: tone.bg },
						}, badgeText),
					),
					React.createElement("div", { style: S.next },
						React.createElement("span", { style: S.nextText }, nextText),
						!noBridge ? React.createElement("div", { style: S.actions },
							React.createElement("button", { style: S.btnPrimary, onClick: open }, t("open")),
						) : null,
					),
					React.createElement("div", { style: S.actions },
						React.createElement("button", { style: S.btn, onClick: refresh }, t("refresh")),
						React.createElement("button", {
							style: S.btnQuiet,
							onClick: () => setShowDetails((v) => !v),
						}, showDetails ? t("hideDetails") : t("showDetails")),
					),
					showDetails ? React.createElement("div", { style: S.details },
						row(t("rowTotal"), (state.total || 0) > 0, String(state.total || 0)),
						row(t("rowWarn"), (state.warn || 0) === 0, String(state.warn || 0)),
						row(t("rowError"), (state.error || 0) === 0, String(state.error || 0)),
						row(t("rowCrash"), crash === 0, String(crash)),
						row(t("rowPending"), (state.pending || 0) === 0, String(state.pending || 0)),
						row(t("rowLastExit"), true, state.lastExit || "—"),
						row(t("rowTotal"), state.uploadConfigured, state.uploadConfigured ? t("uploadOn") : t("uploadOff")),
					) : null,
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("howTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),

				React.createElement("details", { style: S.fold },
					React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
					React.createElement("div", { style: S.foldBody },
						React.createElement("ul", { style: S.notes },
							[t("note1"), t("note2"), t("note3"), t("note4")].map((line, i) =>
								React.createElement("li", { style: S.note, key: i }, line)),
						),
					),
				),
			);
		}

		/**
		 * 把「运行日志与诊断」注册进设置页一级导航。
		 *
		 * 与特权权限/悬浮气泡同一套接缝（"settings.section"），不需要碰导航 DOM。
		 *
		 * ⚠️ "order" 取 28：排在「悬浮气泡」（26）与「交流群」（27）之后，
		 *   也就是设置列表最末。日志是**排障入口**，不该占据日常设置的显眼位置，
		 *   但必须存在。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function mpRegisterLogsSection(ctx) {
			if (mpPrivReact === null) {
				try { mpPrivReact = require("react"); } catch (e) {
					console.warn("[dsh-android-mobile] require('react') 失败，跳过日志分节:", e && e.message);
					mpPrivReact = null;
				}
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
					const t = scope.locale.bind(MP_LOGS_NS);
					scope.effect(
						() => scope.locale.register(MP_LOGS_NS, { zh: MP_LOGS_ZH, en: MP_LOGS_EN }),
						"dsh-android-mobile: 日志字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-logs",
						order: 28,
						label: () => t("nav"),
						locale: MP_LOGS_NS,
					}, mpLogsSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册日志分节失败:", e && (e.stack || e.message || e));
			}
		}

		// ── 备份设置节（设计说明 §5.3：通过「安卓基座」设置入口提供）──────
		//
		// ⚠️ 文档明确「不新增原生日常设置中心」，所以备份的日常操作
		//   （设密码/选目录/立即备份/看状态）全部在这一个设置节里，
		//   原生侧只提供桥方法。
		const MP_BACKUP_NS = "dsh-android-mobile.backup";
		const MP_BACKUP_ZH = {
			nav: "备份与恢复",
			title: "备份与恢复",
			intro: "用你自己设的密码加密整个配置与插件目录，存到手机里你能找到的位置。重装或换机后输入密码即可恢复。",
			statusTitle: "当前状态",
			rowPassword: "备份密码",
			rowDir: "备份目录",
			rowAuto: "自动备份",
			rowLast: "上次成功",
			set: "已设置",
			unset: "未设置",
			on: "已开启",
			off: "已关闭",
			never: "从未成功",
			notReady: "还没配好，请按下面的步骤设置。",
			ready: "已配置完成。每次正常进入会自动备份一版。",
			stepsTitle: "怎么设置",
			step1: "设置一个密码 —— 它用来加密备份，忘了就解不开，请记牢。",
			step2: "选择一个目录 —— 建议选手机根目录下新建的文件夹，卸载重装后还能找到。",
			step3: "开启自动备份 —— 之后每次正常打开 App 会自动备份一版，保留最近三份。",
			pwdTitle: "设置密码",
			pwdHint: "至少 8 个字符。这个密码不会被保存在任何地方，只用来加密备份。",
			pwdPlaceholder: "输入备份密码",
			pwdConfirmPlaceholder: "再输入一次",
			pwdMismatch: "两次输入的密码不一致",
			pwdTooShort: "密码至少 8 个字符",
			pwdSet: "设置密码",
			pwdOk: "密码已设置",
			dirTitle: "备份目录",
			chooseDir: "选择目录",
			autoTitle: "自动备份",
			runNow: "立即备份一次",
			running: "备份中…",
			runStarted: "已开始备份，完成后会提示",
			manualTitle: "手动备份",
			manualHint: "自动备份记的是「进入时的状态」，之后的重要改动建议手动再备份一次。",
			noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法使用备份。",
			noteTitle: "几点说明",
			note1: "备份包含：对话记录、配置、技能、桌宠素材、API 密钥。",
			note2: "备份不包含：引擎本体与运行时（这些能从安装包重建）、工作区文件。",
			note3: "整包用你的密码加密，即使别人拿到文件也读不出内容。",
			note4: "保留最近三份完整备份，更早的会被自动清理。",
			note5: "忘记密码无法恢复 —— 这是加密的代价，请把密码记在安全的地方。",
			// ── 导入 ──────────────────────────────────────────────
			importTitle: "从备份恢复",
			importHint: "选一个之前导出的 .dshbak 包，输入当时的密码。导入会替换现有的对话记录、技能等内容。",
			importChoose: "选择备份文件",
			importInspect: "检查",
			importDo: "开始导入",
			importNoFile: "请先选择备份文件",
			importPwdNeeded: "请输入备份密码",
			importChecking: "检查中…",
			importApplying: "导入中…",
			importDone: "导入完成",
			importFailed: "导入失败",
			importRolledBack: "（已回滚，当前数据完好）",
			importSummary: "包信息",
			importCreated: "创建时间",
			importVersion: "来源版本",
			importEntries: "文件数",
			importComplete: "完整性",
			importCompleteYes: "完整",
			importCompleteNo: "不完整",
			importWillReplace: "会被替换",
			importWarnings: "注意事项",
			importConfirmHint: "确认无误后点「开始导入」。导入前会自动建立恢复点，失败可回滚。",
		};

		const MP_BACKUP_EN = {
			nav: "Backup & restore",
			title: "Backup & restore",
			intro: "Encrypt your config and plugin directory with your own password, stored where you can find it on the phone. Restore after reinstall or on a new device with the password.",
			statusTitle: "Status",
			rowPassword: "Password",
			rowDir: "Directory",
			rowAuto: "Auto backup",
			rowLast: "Last success",
			set: "Set",
			unset: "Not set",
			on: "On",
			off: "Off",
			never: "Never",
			notReady: "Not configured yet. Follow the steps below.",
			ready: "Configured. A backup is made each time the app starts normally.",
			stepsTitle: "How to set up",
			step1: "Set a password — it encrypts the backup. If you forget it, it cannot be recovered.",
			step2: "Choose a directory — ideally a folder you create at the phone root, so it survives reinstall.",
			step3: "Turn on auto backup — one backup per normal start, keeping the latest three.",
			pwdTitle: "Set password",
			pwdHint: "At least 8 characters. Never stored anywhere; only used to encrypt backups.",
			pwdPlaceholder: "Enter backup password",
			pwdConfirmPlaceholder: "Enter again",
			pwdMismatch: "Passwords do not match",
			pwdTooShort: "Password must be at least 8 characters",
			pwdSet: "Set password",
			pwdOk: "Password set",
			dirTitle: "Backup directory",
			chooseDir: "Choose directory",
			autoTitle: "Auto backup",
			runNow: "Back up now",
			running: "Backing up…",
			runStarted: "Backup started; you will be notified when done",
			manualTitle: "Manual backup",
			manualHint: "Auto backup captures the state at app start; back up manually after important changes.",
			noBridge: "No native bridge (DSHAndroid) in this environment; backup unavailable.",
			noteTitle: "Notes",
			note1: "Included: sessions, config, skills, pet assets, API keys.",
			note2: "Excluded: engine and runtime (rebuildable from the APK), workspace files.",
			note3: "The whole package is encrypted with your password.",
			note4: "The latest three complete backups are kept; older ones are pruned.",
			note5: "A forgotten password cannot be recovered — keep it somewhere safe.",
			importTitle: "Restore from backup",
			importHint: "Pick a .dshbak package and enter the password used at export time. Import replaces existing sessions, skills and similar data.",
			importChoose: "Choose backup file",
			importInspect: "Inspect",
			importDo: "Start import",
			importNoFile: "Choose a backup file first",
			importPwdNeeded: "Enter the backup password",
			importChecking: "Inspecting…",
			importApplying: "Importing…",
			importDone: "Import complete",
			importFailed: "Import failed",
			importRolledBack: " (rolled back, current data intact)",
			importSummary: "Package info",
			importCreated: "Created",
			importVersion: "Source version",
			importEntries: "Files",
			importComplete: "Completeness",
			importCompleteYes: "Complete",
			importCompleteNo: "Incomplete",
			importWillReplace: "Will be replaced",
			importWarnings: "Notes",
			importConfirmHint: "Confirm and press Start import. A rollback point is created first.",
		};

		/**
		 * 备份设置节。
		 *
		 * ⚠️ 所有原生调用都先查桥存不存在：非 App 环境（或桥被清理）时
		 *   `window.DSHAndroid` 是 undefined，直接调会抛
		 *   "Cannot read properties of undefined"。
		 */
		function mpBackupSection(props) {
			const React = mpPrivReact;
			const S = MP_PRIV_S;
			const t = typeof props.t === "function" ? props.t : (key) => MP_BACKUP_ZH[key] ?? key;
			const [state, setState] = React.useState({ phase: "loading" });
			const [pwd, setPwd] = React.useState("");
			const [pwd2, setPwd2] = React.useState("");
			const [msg, setMsg] = React.useState("");
			const [busy, setBusy] = React.useState(false);
			// ── 导入相关状态 ─────────────────────────────────────────
			const [impPwd, setImpPwd] = React.useState("");
			const [impInfo, setImpInfo] = React.useState(null);
			const [impPhase, setImpPhase] = React.useState("idle");
			const [impMsg, setImpMsg] = React.useState("");

			const bridge = () => (typeof window !== "undefined" ? window.DSHAndroid : undefined);

			const refresh = React.useCallback(() => {
				const b = bridge();
				if (!b || typeof b.backupStatus !== "function") {
					setState({ phase: "nobridge" });
					return;
				}
				try {
					const raw = b.backupStatus();
					const o = JSON.parse(raw || "{}");
					setState({ phase: "ready", data: o });
				} catch (e) {
					setState({ phase: "error", error: (e && e.message) || String(e) });
				}
			}, []);

			React.useEffect(() => {
				refresh();
				// 备份是长操作，轮询一次状态就够了（不需要实时）
				const id = setInterval(refresh, 3000);
				return () => clearInterval(id);
			}, [refresh]);

			const doSetPassword = React.useCallback(() => {
				if (pwd.length < 8) { setMsg(t("pwdTooShort")); return; }
				if (pwd !== pwd2) { setMsg(t("pwdMismatch")); return; }
				const b = bridge();
				if (!b || typeof b.backupSetPassword !== "function") { setMsg(t("noBridge")); return; }
				setBusy(true);
				try {
					const r = JSON.parse(b.backupSetPassword(pwd) || "{}");
					if (r.ok) {
						setMsg(t("pwdOk"));
						// ⚠️ 传完立刻清空 React state —— 密码不该留在内存里更久
						setPwd(""); setPwd2("");
						refresh();
					} else {
						setMsg(r.error || "设置失败");
					}
				} catch (e) {
					setMsg((e && e.message) || "设置失败");
				} finally {
					setBusy(false);
				}
			}, [pwd, pwd2, refresh, t]);

			const doChooseDir = React.useCallback(() => {
				const b = bridge();
				if (!b || typeof b.backupChooseDir !== "function") { setMsg(t("noBridge")); return; }
				try { b.backupChooseDir(); } catch (e) { setMsg((e && e.message) || "打开失败"); }
			}, [t]);

			const doToggleAuto = React.useCallback(() => {
				const b = bridge();
				if (!b || typeof b.backupSetAutoEnabled !== "function") { setMsg(t("noBridge")); return; }
				const cur = state.data && state.data.autoEnabled;
				try {
					const r = JSON.parse(b.backupSetAutoEnabled(!cur) || "{}");
					if (!r.ok) setMsg(r.error || "设置失败");
					refresh();
				} catch (e) {
					setMsg((e && e.message) || "设置失败");
				}
			}, [state, refresh, t]);

			const doRunNow = React.useCallback(() => {
				const b = bridge();
				if (!b || typeof b.backupRunNow !== "function") { setMsg(t("noBridge")); return; }
				setBusy(true);
				try {
					const r = JSON.parse(b.backupRunNow() || "{}");
					setMsg(r.ok ? t("runStarted") : (r.error || "启动失败"));
				} catch (e) {
					setMsg((e && e.message) || "启动失败");
				} finally {
					setBusy(false);
				}
			}, [t]);

			const doChooseImport = React.useCallback(() => {
				const b = bridge();
				if (!b || typeof b.backupChooseImportFile !== "function") { setImpMsg(t("noBridge")); return; }
				try { b.backupChooseImportFile(); setImpMsg(t("importChoose") + "…"); }
				catch (e) { setImpMsg((e && e.message) || "打开失败"); }
			}, [t]);

			const doInspect = React.useCallback(() => {
				if (!impPwd) { setImpMsg(t("importPwdNeeded")); return; }
				const b = bridge();
				if (!b || typeof b.backupInspect !== "function") { setImpMsg(t("noBridge")); return; }
				setBusy(true);
				try {
					const r = JSON.parse(b.backupInspect(impPwd) || "{}");
					if (r.ok) { setImpInfo(r); setImpMsg(""); }
					else { setImpInfo(null); setImpMsg(r.error || t("importFailed")); }
				} catch (e) {
					setImpMsg((e && e.message) || "检查失败");
				} finally { setBusy(false); }
			}, [impPwd, t]);

			const doImport = React.useCallback(() => {
				const b = bridge();
				if (!b || typeof b.backupImport !== "function") { setImpMsg(t("noBridge")); return; }
				setBusy(true);
				setImpPhase("applying");
				try {
					const r = JSON.parse(b.backupImport(impPwd) || "{}");
					if (!r.ok) { setImpMsg(r.error || t("importFailed")); setImpPhase("failed"); }
				} catch (e) {
					setImpMsg((e && e.message) || "导入失败"); setImpPhase("failed");
				} finally { setBusy(false); }
			}, [impPwd, t]);

			// 导入是长操作，轮询结果
			React.useEffect(() => {
				if (impPhase !== "applying") return;
				const b = bridge();
				if (!b || typeof b.backupImportResult !== "function") return;
				const id = setInterval(() => {
					try {
						const r = JSON.parse(b.backupImportResult() || "{}");
						if (r.phase === "done") {
							setImpPhase("done");
							setImpMsg(t("importDone") + "：" + (r.restored || []).join("、"));
						} else if (r.phase === "failed") {
							setImpPhase("failed");
							setImpMsg((r.error || t("importFailed")) + (r.rolledBack ? t("importRolledBack") : ""));
						}
					} catch (e) { /* 忽略轮询错误 */ }
				}, 1000);
				return () => clearInterval(id);
			}, [impPhase, t]);

			if (state.phase === "loading") {
				return React.createElement("div", { className: S.section }, "...");
			}
			if (state.phase === "nobridge") {
				return React.createElement("div", { className: S.section },
					React.createElement("div", { className: S.intro }, t("noBridge")));
			}

			const d = state.data || {};
			const row = (label, value, tone) =>
				React.createElement("div", { className: S.row },
					React.createElement("span", { className: S.rowLabel }, label),
					React.createElement("span", { className: tone === "warn" ? S.error : S.rowValue }, value));

			const fmtLast = (ms) => {
				if (!ms) return t("never");
				try { return new Date(ms).toLocaleString(); } catch (e) { return String(ms); }
			};

			return React.createElement("div", { className: S.section },
				React.createElement("div", { className: S.intro }, t("intro")),
				React.createElement("h3", { className: S.heading }, t("statusTitle")),
				row(t("rowPassword"), d.hasPassword ? t("set") : t("unset"), d.hasPassword ? null : "warn"),
				row(t("rowDir"), d.hasDir ? t("set") : t("unset"), d.hasDir ? null : "warn"),
				row(t("rowAuto"), d.autoEnabled ? t("on") : t("off")),
				row(t("rowLast"), fmtLast(d.lastSuccessAt)),
				React.createElement("div", { className: S.intro }, d.ready ? t("ready") : t("notReady")),

				React.createElement("h3", { className: S.heading }, t("pwdTitle")),
				React.createElement("div", { className: S.intro }, t("pwdHint")),
				React.createElement("input", {
					className: S.cmd, type: "password", value: pwd,
					placeholder: t("pwdPlaceholder"),
					onChange: (e) => setPwd(e.target.value),
				}),
				React.createElement("input", {
					className: S.cmd, type: "password", value: pwd2,
					placeholder: t("pwdConfirmPlaceholder"),
					onChange: (e) => setPwd2(e.target.value),
				}),
				React.createElement("button", {
					className: S.btn, disabled: busy, onClick: doSetPassword,
				}, t("pwdSet")),

				React.createElement("h3", { className: S.heading }, t("dirTitle")),
				React.createElement("button", { className: S.btn, onClick: doChooseDir }, t("chooseDir")),

				React.createElement("h3", { className: S.heading }, t("autoTitle")),
				React.createElement("button", { className: S.btn, onClick: doToggleAuto },
					d.autoEnabled ? t("off") : t("on")),

				React.createElement("h3", { className: S.heading }, t("manualTitle")),
				React.createElement("div", { className: S.intro }, t("manualHint")),
				React.createElement("button", {
					className: S.btn, disabled: busy, onClick: doRunNow,
				}, busy ? t("running") : t("runNow")),

				msg ? React.createElement("div", { className: S.intro }, msg) : null,

				// ── 导入 ─────────────────────────────────────────────
				React.createElement("h3", { className: S.heading }, t("importTitle")),
				React.createElement("div", { className: S.intro }, t("importHint")),
				React.createElement("button", { className: S.btn, onClick: doChooseImport },
					t("importChoose")),
				React.createElement("input", {
					className: S.cmd, type: "password", value: impPwd,
					placeholder: t("pwdPlaceholder"),
					onChange: (e) => setImpPwd(e.target.value),
				}),
				React.createElement("button", {
					className: S.btn, disabled: busy, onClick: doInspect,
				}, busy ? t("importChecking") : t("importInspect")),

				// 检查结果
				impInfo ? React.createElement("div", { className: S.card },
					React.createElement("div", { className: S.cardTitle }, t("importSummary")),
					React.createElement("div", { className: S.rowValue },
						t("importCreated") + "：" + (impInfo.createdAt ? new Date(impInfo.createdAt).toLocaleString() : "?")),
					React.createElement("div", { className: S.rowValue },
						t("importVersion") + "：" + (impInfo.appVersion || "?")),
					React.createElement("div", { className: S.rowValue },
						t("importEntries") + "：" + (impInfo.entries || 0)),
					React.createElement("div", { className: S.rowValue },
						t("importComplete") + "：" + (impInfo.complete ? t("importCompleteYes") : t("importCompleteNo"))),
					(impInfo.willReplace || []).length > 0
						? React.createElement("div", { className: S.rowValue },
							t("importWillReplace") + "：" + impInfo.willReplace.join("、"))
						: null,
					(impInfo.warnings || []).length > 0
						? React.createElement("div", { className: S.error },
							t("importWarnings") + "：",
							React.createElement("ul", { className: S.notes },
								impInfo.warnings.map((w, i) => React.createElement("li", { key: i }, w))))
						: null,
					React.createElement("div", { className: S.intro }, t("importConfirmHint")),
					React.createElement("button", {
						className: S.btn, disabled: busy || impPhase === "applying",
						onClick: doImport,
					}, impPhase === "applying" ? t("importApplying") : t("importDo")),
				) : null,

				impMsg ? React.createElement("div", { className: S.intro }, impMsg) : null,

				React.createElement("h3", { className: S.heading }, t("noteTitle")),
				React.createElement("ul", { className: S.notes },
					React.createElement("li", null, t("note1")),
					React.createElement("li", null, t("note2")),
					React.createElement("li", null, t("note3")),
					React.createElement("li", null, t("note4")),
					React.createElement("li", null, t("note5"))),
			);
		}

		/**
		 * 注册备份设置节。
		 *
		 * ⚠️ order 取 29（紧接日志的 28 之后）：备份与日志同属「出问题时的出口」，
		 *   放在一起用户容易找到。
		 */
		function mpRegisterBackupSection(ctx) {
			let React = mpPrivReact;
			if (!React) {
				try { React = require("react"); mpPrivReact = React; }
				catch (e) { mpPrivReact = null; }
			}
			if (!mpPrivReact) return;
			try {
				ctx.inject(["slots", "locale"], (scope) => {
					const t = scope.locale.bind(MP_BACKUP_NS);
					scope.effect(
						() => scope.locale.register(MP_BACKUP_NS, { zh: MP_BACKUP_ZH, en: MP_BACKUP_EN }),
						"dsh-android-mobile: 备份字典",
					);
					scope.slots.inject("settings.section", () => scope.slots.register({
						name: "settings.section",
						id: "dsh-android-backup",
						order: 29,
						label: () => t("nav"),
						locale: MP_BACKUP_NS,
					}, mpBackupSection));
				});
			} catch (e) {
				console.warn("[dsh-android-mobile] 注册备份分节失败:", e && (e.stack || e.message || e));
			}
		}

		/** 最近一次组字结束的时间戳（0 = 从未）。见 setupEnterAsNewline。 */
		let mpLastCompositionEnd = 0;

		/**
		 * 余额桥：页面侧拉 DeepSeek 余额 + 算本轮花费，回传给原生徽标。
		 *
		 * ## 为什么在页面侧拉，而不是引擎插件里
		 *
		 * 引擎插件层**拿不到** "ctx.remote"——实测两条路都不通：
		 * "inject: ['remote']" 会 "pending (waiting for service: remote)"
		 * 等一个永远不来的服务；不 inject 则读取直接抛
		 * "cannot get property "remote" without inject"。
		 * 而且同层的 "tools" / "agents" / "sessions" 全都是不可达的。
		 *
		 * 页面侧不一样：官方 "dsh-client-ui-settings-account" 就是用
		 * "ctx.remote.account.getBalance()" 显示余额的，说明这条路在浏览器
		 * 插件里是通的。所以这里按它的做法，用 "ctx.inject(["remote"], scope => …)"
		 * 开一个**子 fiber** 再取服务。
		 *
		 * ## 与原生怎么对接
		 *
		 * 走 "DSHAndroid.bubbleReportBalance(json)"（见 "PageBridge"）。
		 * 原生只负责画，不碰网络；页面只负责取数，不碰 View。
		 *
		 * @module mpBalanceBridge
		 */

		/** 页面侧余额状态（模板渲染由原生做，这里只负责取数与算钱）。 */
		const mpBalance = {
			/** 充值钱包 "[{currency,balance}]"。 */
			wallets: [],
			/** 赠送钱包。 */
			bonusWallets: [],
			/** 上次单轮花费（USD）。 */
			costLast: 0,
			/** 本会话累计（USD）。 */
			costSession: 0,
			/** 上次错误。 */
			lastError: '',
			/** 是否正在拉取（防重入）。 */
			fetching: false,
		};

		/**
		 * DeepSeek 单价（USD / 1M tokens）。**估算值，不是账单**。
		 *
		 * 官方随时可能调价，这里写死必然过期；而且真实扣费只有官方后台知道。
		 * 界面上应理解为"大约花了多少"。
		 *
		 * ⚠️ 缓存命中单独一档：不区分的话会把便宜十倍的缓存读按原价算，虚高得离谱。
		 * ⚠️ **不算 cacheWriteTokens**：DeepSeek 不单独收缓存写费（与 OpenAI 不同，
		 *   别照抄那边的计价模型，会凭空多出一笔）。
		 */
		const MP_BALANCE_PRICING = {
			inputMiss: 0.27 / 1_000_000,
			inputHit: 0.07 / 1_000_000,
			output: 1.1 / 1_000_000,
		};

		/**
		 * 账号接口要的客户端版本号。
		 *
		 * ⚠️ 必须非空，否则官方那段 "accountClientMetadata()" 自己也抛
		 *   "this client build carries no DSH_CLIENT_VERSION"。
		 *   这里不跟着引擎版本走：它只用于平台侧的兼容性判断，写死一个
		 *   稳定的 App 版本号最省事，也不会因为引擎升级而失效。
		 */
		const MP_CLIENT_VERSION = "0.3.0";

		/**
		 * 把 token 用量折算成金额。
		 *
		 * @param {object} usage - 引擎的 "TokenUsage"。
		 * @returns {number} 估算金额（USD）。
		 */
		function mpCostOf(usage) {
			if (usage === null || typeof usage !== "object") return 0;
			const num = (v) => (typeof v === "number" && Number.isFinite(v) && v > 0 ? v : 0);
			return num(usage.inputTokens) * MP_BALANCE_PRICING.inputMiss
				+ num(usage.cacheReadTokens) * MP_BALANCE_PRICING.inputHit
				+ num(usage.outputTokens) * MP_BALANCE_PRICING.output;
		}

		/** 把当前余额推给原生。失败静默（徽标不更新而已，不影响页面）。 */
		function mpPushBalance() {
			const b = window.DSHAndroid;
			if (!b || typeof b.bubbleReportBalance !== "function") return;
			try {
				b.bubbleReportBalance(JSON.stringify({
					wallets: mpBalance.wallets,
					bonusWallets: mpBalance.bonusWallets,
					costLast: mpBalance.costLast,
					costSession: mpBalance.costSession,
					error: mpBalance.lastError,
				}));
			} catch (e) {
				console.warn("[dsh-android-mobile] 上报余额失败:", e && e.message);
			}
		}

		/**
		 * 拉一次余额。**账号通道优先**，无该通道时静默返回（不吵）。
		 *
		 * @param {object} remote - "ctx.get("remote")" 拿到的 remote 服务。
		 * @param {string} reason - 触发原因（日志用）。
		 */
		async function mpRefreshBalance(remote, reason) {
			if (mpBalance.fetching) return;
			if (!remote?.account?.getBalance) {
				// ⚠️ 这里**必须留痕**：静默返回是真机上最坑的形态 ——
				//   用户点了球，看不出任何反应，日志里也什么都没有。
				try {
					window.DSHAndroid?.pageDiag?.(
						`余额刷新跳过(${reason}): remote.account.getBalance 不存在`,
					);
				} catch { /* ignore */ }
				return;
			}
			mpBalance.fetching = true;
			try {
				// ⚠️⚠️ **参数结构不能自己编。**
				//
				// 这里原来写的是 `{clientVersion, locale, utcOffsetSec}` —— 三个字段
				// 名字全错，网关直接拒：
				//   `typert gateway: account/getBalance: wire field "client" failed
				//    boundary validation`
				// 而页面侧的 catch 只把它记成一句"不可用"，徽标永远显示不出余额。
				//
				// 正确结构来自官方 `dsh-client-ui-settings-account` 的
				// `accountClientMetadata()`：
				//   `{ version, locale, timezoneOffsetSeconds }`
				// 且 `version` 必须非空，否则官方那段自己也抛。
				const out = await remote.account.getBalance({
					version: MP_CLIENT_VERSION,
					locale: "zh-CN",
					timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
				});
				if (out?.ok && out.value?.status === "ready") {
					mpBalance.wallets = Array.isArray(out.value.value) ? out.value.value : [];
					mpBalance.bonusWallets = Array.isArray(out.value.bonusWallets) ? out.value.bonusWallets : [];
					mpBalance.lastError = "";
					console.log(`[dsh-android-mobile] 余额已刷新（${reason}）: ${mpBalance.wallets.length} 个钱包`);
					try {
						window.DSHAndroid?.pageDiag?.(
							`余额刷新(${reason}) 成功: ${mpBalance.wallets.length} 个钱包, 赠送 ${mpBalance.bonusWallets.length}`,
						);
					} catch { /* ignore */ }
				} else if (out?.ok && out.value?.status === "failed") {
					mpBalance.lastError = "账号余额接口返回失败";
					try {
						window.DSHAndroid?.pageDiag?.(`余额刷新(${reason}) 平台返回 failed`);
					} catch { /* ignore */ }
				} else if (out && out.ok === false) {
					mpBalance.lastError = `账号余额不可用: ${out.error?.message ?? out.error ?? "未知"}`;
					try {
						window.DSHAndroid?.pageDiag?.(
							`余额刷新(${reason}) 不可用: ${String(out.error?.message ?? out.error)}`,
						);
					} catch { /* ignore */ }
				} else {
					// `value === null` = 未登录。这不是错误，只是没这条路。
					mpBalance.lastError = "未登录 DeepSeek 账号";
					try {
						window.DSHAndroid?.pageDiag?.(
							`余额刷新(${reason}) 未登录: value=${JSON.stringify(out?.value)?.slice(0, 40)} status=${out?.value?.status}`,
						);
					} catch { /* ignore */ }
				}
				mpPushBalance();
			} catch (e) {
				mpBalance.lastError = `余额通道异常: ${e?.message ?? e}`;
				try {
					window.DSHAndroid?.pageDiag?.(`余额刷新(${reason}) 抛异常: ${e && e.message}`);
				} catch { /* ignore */ }
				mpPushBalance();
			} finally {
				mpBalance.fetching = false;
			}
		}

		/**
		 * 读原生的徽标刷新配置。
		 *
		 * 原生存在 SharedPreferences 里，页面读不到，所以走桥问。
		 * **读不到就用默认值**，绝不能让刷新因为一次桥调用失败而整个不工作。
		 *
		 * @returns {{refreshEverySec:number, refreshOnTurnEnd:boolean, refreshOnTap:boolean}}
		 */
		function mpBadgeRefreshConfig() {
			const fallback = { refreshEverySec: 0, refreshOnTurnEnd: true, refreshOnTap: false };
			try {
				const raw = window.DSHAndroid?.bubbleBadgeConfig?.();
				if (typeof raw !== "string" || raw === "") return fallback;
				const o = JSON.parse(raw);
				return {
					refreshEverySec: Number.isFinite(o?.refreshEverySec) ? Math.max(0, o.refreshEverySec) : 0,
					refreshOnTurnEnd: o?.refreshOnTurnEnd !== false,
					refreshOnTap: o?.refreshOnTap === true,
					// ⚠️ 必须带下来：页面用它限流，读不到就只能瞎猜（曾经写死 20s）。
					tapRefreshMinIntervalSec: Number.isFinite(o?.tapRefreshMinIntervalSec)
						? o.tapRefreshMinIntervalSec
						: 3,
				};
			} catch {
				return fallback;
			}
		}

		/**
		 * 让 "navigator.clipboard.writeText" 走原生剪贴板。
		 *
		 * ## 为什么必须打这个补丁
		 *
		 * "http://127.0.0.1" 属于**潜在可信来源**，所以 WebView 里
		 * "navigator.clipboard" **存在**、"isSecureContext" 也是 "true" —— 看起来一切正常。
		 * 但 WebView 的剪贴板写权限由原生 "WebChromeClient.onPermissionRequest" 授予，
		 * 本 App 没有实现它，于是**每次调用都抛**
		 * "NotAllowedError: Write permission denied."
		 *
		 * 这种"API 在、一用就炸"的形态最难查：官方的「复制登录链接」只把异常吞成一句
		 * 「复制失败」，没有任何线索。
		 *
		 * ## 做法
		 *
		 * 用 "Object.defineProperty" 覆盖 "navigator.clipboard.writeText"，内部转调
		 * 原生桥 "DSHAndroid.writeClipboard"。**只改 writeText**，"readText" 等一律不动
		 * （读剪贴板牵出隐私面，而需求只有复制）。
		 *
		 * ⚠️ 必须在 "apply()" 里调用："DSHAndroid" 桥在页面脚本执行时可能还没注入，
		 *   模块顶层直接取会拿到 "undefined"。
		 *
		 * @returns {boolean} 是否成功安装。
		 */
		function mpInstallClipboardBridge() {
			try {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.writeClipboard !== "function") return false;
				const clip = navigator.clipboard;
				if (!clip) return false;
				const patched = function writeText(text) {
					return new Promise((resolve, reject) => {
						let ok = false;
						try {
							ok = bridge.writeClipboard(String(text == null ? "" : text));
						} catch (e) {
							reject(e);
							return;
						}
						// 原生返回 false 时**必须 reject**：官方那段代码靠 catch 才会
						// 显示「复制失败」，回 resolve 会让用户以为复制成功但剪贴板是空的。
						if (ok) resolve();
						else reject(new Error("writeClipboard failed"));
					});
				};
				try {
					Object.defineProperty(clip, "writeText", {
						value: patched,
						configurable: true,
						writable: true,
					});
				} catch (e) {
					clip.writeText = patched;
				}
				return true;
			} catch (e) {
				return false;
			}
		}

		/**
		 * 安装余额桥。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 * @returns {Function} dispose 函数。
		 */
		function mpInstallBalanceBridge(ctx) {
			let refreshFn = null;
			let timer = null;
			let disposed = false;

			// ⚠️ **必须让 `package.json` 的 `dsh.client.inject` 里有 `remote`**，
			//   否则这一行会抛 `cannot get property "remote" without inject`。
			//   实测过的三条死路：
			//   ① 不声明就 `ctx.remote` → 抛 without inject；
			//   ② 用 `ctx.get("remote")` → **拿不到**（`get` 绕过 inject 检查，
			//      而服务本身是按 fiber 依赖表投放的，绕过后就是 undefined）；
			//   ③ 用 `ctx.inject(["remote"], scope => …)` 子 fiber → 回调**永不执行**
			//      （等一个不会来的服务，且不报错，完全静默）。
			//
			//   正解是官方 `dsh-client-ui-settings-account` 的写法：模块/清单级声明
			//   依赖，然后直接读 `ctx.remote`。
			//
			//   风险与取舍：`remote` 缺席会让整个 mobile-ui 不激活（移动端布局崩）。
			//   但 `remote` 是引擎核心服务，官方账号页就靠它，缺席的概率极低；
			//   而这条路是**唯一**能拿到余额的。
			let remote = null;
			try {
				remote = ctx.remote ?? null;
			} catch (e) {
				console.warn("[dsh-android-mobile] 取 remote 服务失败:", e && e.message);
			}
			if (!remote?.account?.getBalance) {
				// 这个引擎版本没有账号通道：安静退出，不装任何监听。
				// 徽标会一直显示"未登录"，那是**正确**的反馈，不是 bug。
				// ⚠️ 诊断日志带上 `remote` 的真实形状 —— 否则"通道不在"这句话
				//   既可能是没 inject 到，也可能是注进来了但结构不同，无从分辨。
				let shape = "null";
				try {
					shape = remote === null || remote === undefined
						? String(remote)
						: `keys=[${Object.keys(remote).slice(0, 12).join(",")}] account=${
							remote.account === undefined ? "undefined" : `{${Object.keys(remote.account).slice(0, 8).join(",")}}`
						} namespaces=${
							remote.namespaces === undefined
								? "undefined"
								: (() => {
									try { return `[${Object.keys(remote.namespaces).slice(0, 20).join(",")}]`; }
									catch { return String(remote.namespaces); }
								})()
						}`;
				} catch (e) {
					shape = `形状探测失败: ${e && e.message}`;
				}
				console.log(`[dsh-android-mobile] 无 remote.account 通道，余额桥不启用（remote ${shape}）`);
				return () => { disposed = true; };
			}

			refreshFn = (reason) => void mpRefreshBalance(remote, reason);

			/**
			 * 诊断输出：同时写 "console" 和 "app.log"。
			 *
			 * ⚠️ 页面的 "console.log" **不进 "logs/app.log"**（只到 WebView 控制台），
			 *   所以排查"余额不出数"这类问题时必须在真机上可读。"pageDiag" 是为此
			 *   新加的原生桥（见 "PageBridge.pageDiag"）。
			 */
			const diag = (msg) => {
				try { console.log("[dsh-android-mobile] " + msg); } catch { /* ignore */ }
				try { window.DSHAndroid?.pageDiag?.(String(msg)); } catch { /* ignore */ }
			};

			// ── 定时刷新 ───────────────────────────────────────────
			// 配置由原生下发（`bubbleBadgeConfig`）。0 = 不启用。
			// ⚠️ 上限夹到 3600s：这是个会打平台接口的定时器，用户若配了 1 秒
			//   就是在拿自己的账号刷接口限流。
			const cfg = mpBadgeRefreshConfig();
			const everySec = Math.min(Math.max(cfg.refreshEverySec, 0), 3600);

			// ── 一次启动诊断：把真实形状打进 app.log ──────────────────
			//
			// ⚠️ 必须放在 `cfg` 之后：踩过 `Cannot access 'cfg' before initialization`，
			//   那行 TDZ 报错会把整段安装逻辑后面的代码一起带崩（静默）。
			//
			// 内容要自洽：有没有 account、有没有 getBalance，一眼能分辨是
			// "没 inject 到" 还是 "注进来了但结构不同"。
			try {
				const acct = remote?.account;
				diag(
					"余额桥已装: remote=" + (remote ? "有" : "无") +
					" account=" + (acct ? "有" : "无") +
					" getBalance=" + (typeof acct?.getBalance) +
					" 定时=" + (cfg.refreshEverySec || 0) + "s" +
					" 点击刷新=" + (cfg.refreshOnTap ? "开" : "关") +
					" 点击限流=" + (cfg.tapRefreshMinIntervalSec || "?") + "s",
				);
			} catch (e) {
				diag("余额桥诊断失败: " + (e && e.message));
			}

			if (everySec > 0) {
				timer = window.setInterval(() => refreshFn?.("定时"), everySec * 1000);
				console.log(`[dsh-android-mobile] 余额定时刷新已启用: ${everySec}s`);
			}

			// ── 点击刷新 ───────────────────────────────────────────
			// 原生侧在球被点击时经 `bubbleReportBalance` 之外的另一条路通知页面？
			// 不 —— 页面是**发起方**，原生不方便反向调页面里的 JS 函数。
			// 所以这里由原生在点击时写一个 `tapRefresh` 计数到一个可读的桥方法上，
			// 页面轮询它。轮询很轻（300ms 读一个数字），比反向注入 JS 稳。
			let lastTapSeq = 0;
			let lastTapAt = 0;
			// ⚠️ **必须跟用户的配置走，不能写死。**
			//
			//   这里原来写死 `20 * 1000`，而设置页给的默认值是 10 秒。后果：
			//   用户点一下球，10 秒内再点**一律被这里静默丢掉**，页面上完全
			//   没有反应 —— 用户原话「我点这个球…还是没有看到余额」，看着就像
			//   功能坏了。而原生侧本来就按 `tapRefreshMinIntervalSec` 限过一次，
			//   这里再叠一个更长的硬限流纯属帮倒忙。
			//
			//   现在读配置里的秒数（原生已夹在 1–600），且**再放宽容错**：
			//   万一配置没读到就用 3 秒兜底，而不是 20 秒。
			const tapMinGapMs = Math.max(
				1000,
				Math.min(600, Number(cfg.tapRefreshMinIntervalSec) || 3) * 1000,
			);
			let tapPoll = null;
			if (cfg.refreshOnTap) {
				tapPoll = window.setInterval(() => {
					try {
						const seq = window.DSHAndroid?.bubbleTapRefreshSeq?.();
						const n = typeof seq === "number" ? seq : Number(seq);
						if (!Number.isFinite(n) || n <= lastTapSeq) return;
						lastTapSeq = n;
						const now = Date.now();
						if (now - lastTapAt < tapMinGapMs) {
							diag("点球刷新被限流: 距上次 " + Math.round((now - lastTapAt) / 1000) + "s < " + (tapMinGapMs / 1000) + "s");
							return;
						}
						lastTapAt = now;
						refreshFn?.("点击");
					} catch {
						/* 读不到就算了，下一轮再试 */
					}
				}, 300);
			}

			// 启动先拉一次：用户一进 App 就该看到余额。
			refreshFn("启动");

			// 每轮结束刷新。
			// ⚠️ 包 try/catch：余额是锦上添花，绝不能因为它在事件回调里抛错
			//   而影响会话本身。
			try {
				const off = ctx.on?.("session/event", (_session, event) => {
					try {
						const type = event?.type;
						if (typeof type !== "string") return;
						if (type === "assistant/message" || type === "assistant/attempt") {
							// usage 在这两个事件上；`step/end` **不带**（已核对引擎源码）。
							const cost = mpCostOf(event?.data?.usage);
							if (cost > 0) {
								mpBalance.costLast = Number(cost.toFixed(6));
								mpBalance.costSession = Number((mpBalance.costSession + cost).toFixed(6));
								mpPushBalance();
							}
						} else if (type === "turn/end") {
							// 用户可以在徽标设置里关掉轮末刷新（比如他配了大额定时刷新）。
							if (mpBadgeRefreshConfig().refreshOnTurnEnd) refreshFn?.("轮末");
						}
					} catch (e) {
						console.warn("[dsh-android-mobile] 余额事件处理失败:", e && e.message);
					}
				});
				if (typeof off === "function") {
					// cordis 的 `ctx.on` 返回 dispose 函数，登记到当前 fiber。
					ctx.effect?.(() => off);
				}
			} catch (e) {
				console.warn("[dsh-android-mobile] 余额事件订阅失败:", e && e.message);
			}

			return () => {
				disposed = true;
				if (timer !== null) {
					window.clearInterval(timer);
					timer = null;
				}
				if (tapPoll !== null) {
					window.clearInterval(tapPoll);
					tapPoll = null;
				}
			};
		}

		function apply(ctx) {
			mpCtx = ctx;
			mpSessionsCache = ctx.get("sessions") ?? null;
			mpRegisterPrivilegedSection(ctx);
			mpRegisterBubbleSection(ctx);
			mpRegisterLogsSection(ctx);
			mpRegisterBackupSection(ctx);   /* 备份与恢复（设计说明 §5.3） */
			// ⚠️ 必须在任何 mpIsMobile() 调用之前拉布局 ——
			//   否则首屏会用降级判据（宽度+指针）建出错误的界面，
			//   然后再被修正，用户看到闪一下。
			mpPullLayoutFromNative();
			mpRegisterContactSection(ctx);
			mpInstallPendingTrust();   /* 修复 51：越早越好 */
			mpWatchSessions(ctx);       /* 修复 51：等服务就绪 */
			injectStyles();
			const disposeKeyboardGuard = setupComposerKeyboardGuard();
			const disposeTriggerMenuDismiss = setupTriggerMenuAutoDismiss();
			const disposeKeepFocusGuard = setupKeepFocusGuard();
			const disposeEnterAsNewline = setupEnterAsNewline();   /* 修复 67 */
			const disposePlainKey = setupPlainKeyboardForCredentials();   /* 修复 68 */
			// 初始一轮
			document.querySelectorAll(".xzv4MW_timeEnd").forEach(trimMeta);
			processStatsLine();
			watchApproval();
			mpBuildShell();
			mpSyncDrawer();
			bindTitleRename();
			document.querySelectorAll('[role="dialog"][aria-modal="true"]').forEach(setupSettingsDialog);
			document.addEventListener("click", onNewSessionClick, true);
			document.addEventListener("click", onSessionRowClick, true);
			document.addEventListener("click", onPanelRowClick, true);   /* 修复 66 */
			const disposePressFeedback = setupPressFeedback();
			const disposeStatusBridge = mpInstallStatusBridge();
			const disposeTranscriptBridge = mpInstallTranscriptBridge();
			// 余额桥：页面侧拉 DeepSeek 余额 + 按 usage 算花费，回给原生徽标。
			// ⚠️ 必须在这里装而不是模块顶层：它靠 `ctx.inject(["remote"])` 开子 fiber，
			//   而那个 `ctx` 只有 apply 时才拿得到。
			const disposeBalanceBridge = mpInstallBalanceBridge(ctx);
			// 剪贴板补丁：WebView 里 `navigator.clipboard.writeText` 必抛
			// `NotAllowedError`，转调原生桥（见 mpInstallClipboardBridge 的注释）。
			mpInstallClipboardBridge();
			const disposeImePrejudge = setupImePrejudge();   /* 修复 65 */
			mo.observe(document.body, { childList: true, subtree: true, characterData: true });
			// 启动爆发：apply() 执行时 React 尚未挂载，而 observer 有 300ms 节流，
			// 导致顶栏会话标题出现偏慢。前 ~1.2s 内主动补 10 轮（120ms 间隔），
			// 之后完全交给 observer 常态驱动，不增加稳态开销。
			let mpBootBursts = 0;
			const mpBootBurstTimer = window.setInterval(() => {
				mpSyncMobileAttr();
				mpSyncDrawer();
				bindTitleRename();
				mpAbbreviateEffort();      /* 修复 68：首屏就把长等级缩掉，避免闪一下截断 */
				mpInstallPendingTrust();   /* 修复 51 兜底 */
				if (++mpBootBursts >= 10) window.clearInterval(mpBootBurstTimer);
			}, 120);
			// 审批徽章可能在侧栏抽屉渲染完成前不存在：轮询兜底（低频）
			const t = window.setInterval(watchApproval, 5000);
			/* 启动动画收尾信号：告诉原生「界面真的能用了」。
			 *
			 * ⚠️ 不能在这里直接报 —— apply() 返回时 React 还没挂载，"data-mobile"
			 *   属性与外壳按钮都还不存在，此刻报等于骗原生提前收起启动页，
			 *   用户会看到一块空白的 WebView。
			 *   真机实测：apply() 之后约 1.4~1.7s 界面才真正可用。
			 *
			 * 判据用「data-mobile 已打上 且 外壳已建」，与 CSS 生效条件同源：
			 * 这两件事成立就说明本插件的移动端适配已经挂好，界面可以交付了。 */
			mpSignalUiReady();

			return () => {
				mo.disconnect();
				document.removeEventListener("click", onNewSessionClick, true);
				document.removeEventListener("click", onSessionRowClick, true);
				disposePressFeedback();
				disposeStatusBridge();
				disposeTranscriptBridge();
				disposeImePrejudge();
				window.clearInterval(t);
				window.clearInterval(mpBootBurstTimer);
				disposeKeyboardGuard();
				disposeTriggerMenuDismiss();
				disposeKeepFocusGuard();
				disposeEnterAsNewline();   /* 修复 67 */
				disposePlainKey();   /* 修复 68 */
				document.removeEventListener("click", onPanelRowClick, true);   /* 修复 66 */
			};
		}

		exports.apply = apply;
		/**
		 * 本插件依赖的 cordis 服务。
		 *
		 * ⚠️ **"remote" 是给余额桥用的**（见 "mpInstallBalanceBridge"）：页面侧要调
		 *   "ctx.remote.account.getBalance()" 读 DeepSeek 余额，而 cordis 要求
		 *   当前 fiber 声明过依赖才能读该服务，否则抛
		 *   "cannot get property "remote" without inject"。
		 *
		 * ⚠️ 声明在这里而不是 "package.json" 的 "dsh.client.inject"：那个字段写的是
		 *   **npm 包名**（"@deepseek-ai/dsh-api-remotes" 之类），不是服务名，
		 *   两者不是一回事。这个文件是手写的模块加载器格式，依赖表就在这里。
		 *
		 * 代价："remote" 缺席会让整个 mobile-ui 不激活。但它是引擎核心服务
		 * （官方「设置 → 账号」页就靠它），缺席概率极低，而这是唯一能拿到余额的路。
		 *
		 * ⚠️ **必须同时声明 ""remote.account""**（带点号）。只声明 ""remote"" 时
		 *   "ctx.remote" 是个**未展开的命名空间宿主**（实测形状：
		 *   "{ctx,name,ownerCtx,connection,namespaces,hostFacts,streams,events,mutations}"，
		 *   **没有 "account" 属性**）。cordis 只会把**显式声明过的点号子服务**
		 *   代理到 "remote" 上。官方 "dsh-client-ui-settings-account" 就是这么写的：
		 *   "inject = ["slots","locale","remote","remote.account","remote.session",…]"
		 *   —— 少了那条点号的，"ctx.remote.account" 一定是 undefined。
		 */
		exports.inject = ["remote", "remote.account"];
		return module.exports;
	}
});
