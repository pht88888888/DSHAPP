export const CSS = `/* ══ 修复 75：输入框占位符对比度只有 2.13:1，手机上几乎读不出 ═══════════
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
