export const CSS = `/* ══ 修复 34：轨迹页详情面板底部被输入框挡住（2026-09-16 啾灵反馈）══════════
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

`;
