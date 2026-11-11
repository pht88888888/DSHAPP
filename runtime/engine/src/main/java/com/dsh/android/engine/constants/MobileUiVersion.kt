package com.dsh.android.engine.constants

/** 移动端插件历史版本戳及其生效约束。 */
        /**
         * 插件内容版本：改了 `mobile-ui.zip` 里的任何东西都必须改这里，
         * 否则设备上已解包的旧版不会被覆盖（同 TermuxBootstrap.NODE_VERSION 的道理）。
         *
         * −48：真正生效。第一版把 `type=password` 改成 `text` 之后，
         *      又用 CSS `-webkit-text-security: disc` 补圆点 —— 而 blink 判定
         *      「密码字段」用的正是 `computedStyle.textSecurity != none`，
         *      等于把密码语义原样装了回去，输入法照样切安全键盘、照样屏蔽剪贴板。
         *      本版把这层 CSS 遮蔽彻底移除。
         * −49：的**真正生效点**。去掉 CSS 之后键盘仍是安全键盘 —— 因为
         *      官方那两个凭据框带 `autoFocus: true`，元素插入 DOM 的同一帧就以
         *      `type=password` 聚焦，WebView 在那一刻就把输入连接的 inputType
         *      定成 `TYPE_TEXT_VARIATION_WEB_PASSWORD`(0xe1)，而**它不会为已建立
         *      的连接重算**（实测 blur+focus 也刷不动）。本版改为在
         *      `HTMLInputElement.prototype` 层拦截 `type=password`，让 DOM 里
         *      从头到尾没出现过 password。真机实测 inputType 0xe1 → 0x80a1。
         * −54：悬浮球转录改为**近流式**并把思考与正文分开。两处改动：
         *      ① `mpScheduleTranscript` 节流 1500ms → 250ms（1.5s 会把整段回复
         *         攒成两三坨跳出来，观感就是"没有流式、一次全出"）；
         *      ② `mpCollectTranscript` 按 `data-chat-group-part` 把 `reasoning`
         *         节点发成独立的 `reasoning` 角色（原来与 `response` 都发成
         *         `assistant`，面板上两条标签样式全一样 ⇒ 看着就是"糊在一起"）。
         *      ⚠️ 必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`，
         *      否则设备上那棵降级过的 mobile-ui 树不会被覆盖（见 ⑯）。
         * 1.0.0-57：`mpPageBackground()` 改为**读主题 token** `--dsw-alias-bg-base`
         *      （取不到才退回 `backgroundColor`）。这是"系统栏默认跟随主题色"的实现 ——
         *      `backgroundColor` 只是 token 的投影，纯色主题下两者相等，主题一改成
         *      背景图/渐变就分叉。同 v56 的道理，也必须 bump -CompatVersion。
         * 1.0.0-58：全面屏让位 —— 内容列 `.pI_x6G_centerCol` 补上
         *      `padding-top: var(--dsh-android-system-top)`。原来只有侧栏让位，
         *      内容列靠 WebView 自身避让系统栏活着；全面屏一开那层避让被撤掉
         *      （窗口 content 由 [0,108] 变 [0,0]），状态栏直接压住汉堡与聊天页
         *      header。同 v57 的道理，也必须 bump `-CompatVersion`。
         * 1.0.0-59：上面那条 CSS 规则**压不过的 `!important`**，实测
         *      `getComputedStyle` 仍是 0px、规则完全不生效。改为让
         *      `mpSyncFloatNavGap()` 在算 `--mp-float-nav-h` 时把状态栏高度一并纳入
         *      （JS 的 `Math.max` 不受内核版本限制；CSS 的 `max()` 要 Chrome 79，
         *      而目标内核是 chrome74，老内核上整条声明会静默失效）。
         *      同 v57/v58 的道理，也必须 bump `-CompatVersion`（现 v15）。
         * 1.0.0-60：设置页新增「运行日志与诊断」分节（`mpLogsSection` +
         *      `mpRegisterLogsSection`），拉起原生 `LogViewerActivity`。
         *      这是**纯 JS 新增**，没有改 CSS，所以**不必** bump
         *      `tools/build-webview-compat.ps1` 的 `-CompatVersion` ——
         *      兼容包是"按 chrome74 降级编译前端 bundle"，插件 JS 走的是
         *      引擎自己的加载路径，不经过那个编译。
         *      ⚠️ 但仍然必须 bump 本常量：否则 `.mobile_ui_version` 标记不变，
         *      `ensureMobileUi()` 直接跳过解包，**老设备永远拿不到这个分节**。
         * 1.0.0-62：**沉浸式下汉堡整行下移的根因不在插件，而在原生推错了单位。**
         *      真正的修复在 `MainActivity.pushSystemBarInset()`（把状态栏物理像素
         *      ÷ density 换算成 CSS 像素再推）。本版只改了两处**注释**：
         *      `mpSyncFloatNavGap()` 与 CSS 那条规则原先写着「该变量在本 App 里
         *      从未被任何代码写入（恒 0）」—— 那句话在之后就过时了，
         *      留着会误导下一个来改的人按"变量恒 0"的旧前提动手。
         *      ⚠️ 严格说这一版**没有改任何 JS 行为**，本可以不 bump；但
         *      `mobileui/lib/client.js` 是降级编译的输入之一，注释变了产物字节就变，
         *      而 `.mobile_ui_version` 与 `-CompatVersion` 两戳只要都对得上，
         *      设备上那棵树就会被跳过、继续用旧产物覆盖。为免"源码与设备不一致"
         *      这种最难查的中间态，两个戳一起 bump（见 ⑯ 的 v4~v17 全部先例）。
         * 1.0.0-63：**特殊权限界面布局重做 + 新增 Shizuku 通道。**
         *      界面侧（`mobileui/lib/client.js`）：
         *      ① 新增 Shizuku 卡片，摆在三条路径的第一位 —— 它是**唯一不依赖
         *         adbd** 的那条（不走 5555、不校验 adbkey、重启后依然有效），
         *         对 Android 10 那批「每次重启都要插电脑跑 adb tcpip」的用户是
         *         唯一走得通的路；
         *      ② 三条路径统一成同一个 `pathCard` 结构、顺序**固定**（原来是按
         *         系统版本翻转的，换机/升级后版面全变，用户得重新认一遍）；
         *      ③ 首屏「下一步」的决策树把 Shizuku 排在 adb 两条路之前；
         *      ④ 按钮去重：一屏最多一个主按钮（原来同屏摆了五个同等分量按钮）。
         *      原生侧：新增 `ShizukuChannel`（LocalServerSocket + IShizukuService
         *      .newProcess）、`PageBridge` 四个方法、runner 的 0 号通道。
         *      ⚠️ **必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`
         *      （18 → 19）**：`mobileui/lib/client.js` 是降级编译的输入之一，
         *      不 bump 就会命中「两戳都一致 → 整棵树跳过」，设备上继续跑旧布局，
         *      而 `已安装移动端 UI 插件 1.0.0-63` 这行日志看起来一切正常。
         *      本次真机实测踩到的正是这个形态（设备上 client.js 停在 369105 字节）。
         * 1.0.0-64：**特殊权限界面改成标签页布局**（用户要求：ADB / Shizuku / root
         *      各占一个标签页，点进去是二级界面）。
         *      ① 标签栏替代原来的「三条路径 + root 卡全部平铺」：平铺时一屏里
         *         四五张结构相似的卡纵向排开，用户在 360px 宽的屏上要滑很久，
         *         而且**看完也不知道该点哪张** —— 层级全靠徽章文字区分，太弱；
         *      ② 标签上带状态圆点，不点进去就能看出哪条通道通（圆点色与卡片
         *         徽章色同源，都取 `MP_PRIV_TONE`）；
         *      ③ 标签栏**本身就是导航**，不加「返回」按钮 —— 中途试过「标签栏 +
         *         三行概览卡」，同一组信息在一屏里说两遍，已删；
         *      ④ 默认停在 **Shizuku** 标签（唯一不依赖 adbd 的那条）；
         *      ⑤ 无线调试在 Android 10 上**恢复渲染**：上一版整张卡不渲染，
         *         用户根本看不到还有这条路，只会以为本应用漏做了。现在渲染成
         *         一张纯说明卡（徽章「不可用」、**不给任何按钮**）——
         *         判据是「有没有可点的动作」，不是「这一块在不在」。
         *      ⚠️ **必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`
         *      （19 → 20）**，理由同 v63：`mobileui/lib/client.js` 是降级编译的
         *      输入之一，不 bump 会命中「两戳都一致 → 整棵树跳过」，
         *      设备上继续跑旧布局，而日志看起来一切正常。
         * 1.0.0-65：修正 v64 里 `rootIntro` 的 Markdown 标记。
         *      文案写成「与上面两条通道**互不影响**」，而本页是**纯文本渲染**
         *      （React 直接塞进 DOM），星号会**原样显示**出来 —— 真机截图上
         *      就是「两条通道**互不影响**：没有 root 的设备…」。
         *      ⚠️ **这一版的存在本身就是一条教训，别删**：v64 的标签页布局做完后
         *      真机验过三个标签都正常，随后才发现星号问题；修完只重打了
         *      `mobile-ui.zip` 并重跑 `build-webview-compat.ps1`（仍是 v20），
         *      **忘了再 bump `MOBILE_UI_VERSION`** ⇒ 设备上两戳依旧是
         *      `1.0.0-64` / `chrome74-v20` ⇒ 解包整体跳过 ⇒ 修正版根本没上机，
         *      而 `已安装移动端 UI 插件` 那行日志压根不打印（版本没变），
         *      看起来「什么都没发生」。
         *      判据：改完 mobile-ui 源码，**两个戳都必须是新值**；
         *      只要其中一个还是上次的值，设备就会继续跑旧产物。
         * 1.0.0-102：设置页「交流群」换成**新群**（「DSHAPP交流群3」，群号 1121898890）。
         *      「DSHAPP交流群2」（1109733098）已满，用户给了新的群二维码截图。
         *      ① 群号 `MP_CONTACT_GROUP_NO`：1109733098 → 1121898890；
         *      ② 二维码 data URI 换成新群（700×700 WebP，68,522 B）。
         *         用户给的是**带群名/群号/QQ logo 的整张截图**，用 **zxing-cpp**
         *         直接读出四个角点（`203,695 / 952,695 / 952,1444 / 203,1444`），
         *         按此裁切、每边留 20px 静区、LANCZOS 缩到 700；
         *      ③ 可扫性用 zxing-cpp 验证：WebP 本身、以及模拟
         *         「CSS 240px × dpr3」重采样后**均解码成功**，
         *         内容 `https://qm.qq.com/q/l8uDVdDRXc`。
         *      ⚠️ **两个戳都 bump**：`MOBILE_UI_VERSION` 101 → 102，
         *      降级戳 `chrome74-v34` → `chrome74-v35`。
         *      这次没有 pwsh/esbuild，降级产物 `webview-compat.zip` 里的
         *      `mobileui/lib/client.js` 是**直接对旧产物做等量替换**得到的
         *      （只换群号与二维码 data URI，其余字节不动），
         *      改完用 `tools/check-frontend-syntax.mjs --target 74` 复检通过。
         * 1.0.0-66：设置页「交流群」换成**新群**（「DSHAPP交流群2」，群号 1109733098）。
         *      ① 群号 `MP_CONTACT_GROUP_NO`：1127652833 → 1109733098；
         *      ② 二维码 data URI 换成新群二维码（720×720 WebP，52,764 B，
         *         比旧图小 9,230 B）。用户给的是**带群名/群号/QQ logo 的整张截图**，
         *         所以先用蓝色像素（`B-R>40 且 B>150`）的包围盒定位二维码本体
         *         （415×415，`x 81..495, y 305..719`），裁出来、每边留 12% 白边
         *         （QR 的 quiet zone，缺了会扫不出）、再 LANCZOS 放大到 720；
         *      ③ 可扫性用 **zxing-cpp** 验证，不用 cv2 —— 这类彩色圆点风格二维码
         *         `cv2.QRCodeDetector` 解不出来（见上方 `MP_CONTACT_QR` 的注释）。
         *         实测：原图 / 裁切图 / 最终 PNG / 最终 WebP，以及按真实布局渲染的
         *         `CSS 240px × dpr1|2|3`、`200|160|120px × dpr3` 各档**全部解码成功**，
         *         内容一致为 `https://qm.qq.com/q/CkDhCJH1rW`（余量最大缩到 240px 仍可读）。
         *      ⚠️ **两个戳都要 bump**：`mobileui/lib/client.js` 是降级编译的输入之一，
         *      只重打 `mobile-ui.zip` 而不 bump `-CompatVersion`（21 → 22），
         *      设备上会命中「两戳都一致 → 整棵树跳过」，继续跑旧群的二维码 ——
         *      而日志不会打任何东西（版本没变），看起来像"什么都没发生"（同 v65 的坑）。
          * 1.0.0-88：**底栏模型名在窄屏下被截断**（用户要「真实的视觉感受」时发现）。
          *      症状（360px / dpr3 真机截图）：模型名显示成 "deepseek-v4.1..."，
          *      后面的推理等级 "Default" **宽度直接是 0**、完全看不见。
          *      根因不是文字太长，而是官方 `uV2eYG_row` 用
          *      `justify-content: space-between`，把 41px 剩余空间全推到了
          *      tools 与 trailing **中间**（实测 tools 结束 x=104、trailing 从 x=145
          *      开始），而右边模型名正因为缺 16px 在截断。
          *      修法：逐层放宽（row → flex-start、trailing/standardControls/
          *      _7KE1Ra_root → `1 1 0%`、按钮 `max-width: 139.5px → none`），
          *      把那段空隙让给模型名；推理等级 Default 隐藏（它是默认值，
          *      不显示不丢信息，而一个被压成 "Def..." 的次级信息比不显示更难看）。
          *      实测模型名 144px = 内容 144px，零截断。
          *      ⚠️ 两个戳都 bump（本常量 87 → 88，`-CompatVersion` 21 → 22）：
          *      `mobileui/lib/client.js` 是降级编译的输入之一（同 v65/v87 的坑）。
          *      ⚠️ 另外记一条**踩坑**：CSS 注释里写反引号会把整份 JS 弄坏 ——
          *      那段注释在模板字符串里，一个反引号就提前闭合模板，
          *      esbuild 报 `Expected ";" but found "deepseek"`。CSS 注释里
          *      一律用普通引号，别用反引号。
          * 1.0.0-89：**推理等级被藏掉是错误取舍，本版改回显示。**
          *      v88 为了让模型名完整显示，把 _7KE1Ra_triggerEffort 直接
          *      display:none 了 —— 当时以为它恒为 "Default"（默认值，
          *      藏掉不丢信息）。真机核对 aria-label 才发现实际值是 **"High"**：
          *      选择模型，当前 deepseek-v4.1-flash，推理等级 High
          *      也就是说**用户设过的非默认推理等级被藏起来了**，实打实的
          *      信息丢失，不能接受。
          *      改回显示，并按宽度实测重新分配：模型名 126px（必须完整）
          *      + High 22px + 内边距/箭头 ⇒ 按钮 168px 刚好够。
          *      High 比 Default 短（22 vs 43），所以显示它不会把模型名挤掉
          *      （v88 之前挤掉正是因为 Default 太长）。
          *      字号 10px + opacity 0.65：它是次级信息，不该和模型名抢视觉重心。
          *      ⚠️ 两个戳都 bump（本常量 88 → 89，-CompatVersion 22 → 23）。
          * 1.0.0-90：**思考正文不换行，把整页推出屏幕左侧**。
          *      症状（真机截图，一眼可见）：整个消息列表向左偏出屏幕，
          *      左侧文字被切一半（「已完成」只剩右半、消息操作图标只剩右半边），
          *      右侧反而留白；页面还能被横向拖动。
          *      根因：思考（reasoning）正文的 span 带 white-space: nowrap，
          *      一行到底不换行 —— 实测最长一条 **1197px**（视口 360px）。
          *      它把滚动容器 .wSkVaW_root 的 scrollWidth 从 360 撑到 **550**，
          *      浏览器为显示光标把 scrollLeft 自动设成 **39.67**，
          *      于是整页看起来左移了 40px（.wSkVaW_scrollBody 的 x = **-40**）。
          *      修法两处：① 正文恢复换行（white-space: normal +
          *      overflow-wrap: anywhere 兜住无空格长串）；
          *      ② ._root_1rdzk_1 官方写的 width: max-content 会按内容原始宽度
          *      撑开，改成 width: auto 才服从父级 100% 约束。
          *      实测：最长文本 1197 → 225px，scrollLeft 归 0，bodyX 回到 0，
          *      手势横向拖动无效（已实测 swipe 前后 scrollLeft 都是 339.67）。
          *      ⚠️ 两个戳都 bump（本常量 89 → 90，-CompatVersion 23 → 24）。
          * 1.0.0-91：**模型名在老会话里又被挤掉**——v89 的补丁不完整。
          *      v89 只保证了推理等级为 High（22px）时放得下；但真机打开一个
          *      老会话时等级是 **Default（33px）**，模型名立刻退回
          *      deepseek-v4.1-fl... 逐值实测（按钮固定 168px，模型名要 122px）：
          *        Default 33 → 模型名 115（截断）
          *        Medium  37 → 模型名 111（截断）
          *        Minimal 36 → 模型名 112（截断）
          *        High/Low/Max 19~22 → 126~129（放得下）
          *      结论：光调 flex 治不了根，**必须让文案本身变短**。
          *      新增 mpAbbreviateEffort()：按表把英文等级缩成中文
          *        Default→默认 / Medium→中 / Minimal→最低 / Low→低 /
          *        High→高 / Max→最高 / XHigh→超高
          *      实测缩写后每个都 ≤20px，模型名一律 128~138px，**任何等级都不截断**。
          *      完整英文保留在 title 与 aria-label 上，可访问性不受影响。
          *      挂载点三处：300ms observer 批处理、scheduleStatsSync 微任务、
          *      启动爆发循环（保证首屏不闪一下截断）。
          *      ⚠️ 两个戳都 bump（本常量 90 → 91，-CompatVersion 24 → 25）。
          * 1.0.0-92：**模型名与推理等级粘连**——v91 引入的视觉回归。
          *      v91 把推理等级改成中文后，「deepseek-v4.1-flash默认」**挤成一片**
          *      没有间距。根因：v89 为挤宽度把 _7KE1Ra_trigger 的 gap 压到 1px，
          *      英文 High 有右侧字距留白看着还行，中文字形紧凑，1px 就是粘连。
          *      但直接加大 gap 会让模型名重新截断（实测 gap 3px → 模型名 119 < 122）。
          *      解法：从别处腾 4px —— 官方 uV2eYG_row 的 gap 是 12px，
          *      而 tools 只占 80px，中间那 12px 纯浪费，缩到 8px。
          *      实测：按钮 163 → 167px，模型名 123px（= 内容宽度，零截断），
          *      推理等级间距 3px（不粘连）。
          *      ⚠️ 两个戳都 bump（本常量 91 → 92，-CompatVersion 25 → 26）。
          * 1.0.0-93：**短模型名下出现幽灵间距**。
          *      换到 agnes-3.0-flash 时，「agnes-3.0-flash    默认」中间空出一大块，
          *      而 deepseek-v4.1-flash 完全正常 —— 所以一直没暴露。
          *      根因：官方给 label 设了 text-align: center，而 v89 起我写成
          *      flex: 1 1 auto ⇒ 盒子撑满剩余空间、文字居中、两边各留一半空白。
          *      实测（按钮 172px）：
          *        agnes-3.0-flash  盒子 128 / 文字 94 ⇒ 真实间隙 20px
          *        deepseek-v4.1-flash 盒子 122 / 文字 122 ⇒ 贴满，看不出
          *      ⚠️ 改成 text-align: left 反而更糟（空白全推到右边，间隙 37px）。
          *      正解是让盒子按内容收缩：flex 1 1 auto → **0 1 auto**
          *      （0=不撑满，1=仍可压缩，超长模型名照样出省略号）。
          *      实测：短名 94=94 间隙 3px；长名 122=122 间隙 3px；
          *      超长名 盒子 128 / 文字 287 ⇒ 截断出省略号，布局不破。
          *      ⚠️ 两个戳都 bump（本常量 92 → 93，-CompatVersion 26 → 27）。
          * 1.0.0-94：**整页能横向滚 114px**，点「更多操作」页面就左偏。
          *      现象：点右上角「⋯」后整个界面左移 114px，标题栏按钮从 x=294 跑到 180，
          *      输入框/发送键/遥测栏一起跟着跑（bodyX = -114、root.scrollLeft = 114）。
          *      排查：菜单本身只有 148px 宽不溢出（排除）；全树扫「右边界超视口 2px」
          *      命中数 0（排除）；点「刷新」关菜单后 scrollLeft 自动回 0（非持续偏移）。
          *      真凶是官方一对**宽度探针** .wSkVaW_widthHandle（absolute、width:0、
          *      height:691、left:-184px / left:544px，视觉上完全不可见，官方靠它们量宽），
          *      它们把 body 的 scrollWidth 撑到 550（360 视口 + 184 左 + 6 右），root 跟着变 550。
          *      ⚠️ 阴在：root 本来就是 overflow-x:hidden，手指横滑**没反应**
          *      （实测 339.67 → 339.67 纹丝不动），用户很难自己发现；
          *      但 hidden **只挡手势、不挡程序化滚动** —— 实测 scrollLeft=50 照样成功、
          *      bodyX 立刻 -50。所以任何一次「把元素滚进视口」的动作（点视口外按钮、
          *      键盘补偿滚动、scrollIntoView）都会把页面永久推偏，而用户既滑不回来、
          *      也不知道发生了什么。
          *      修法：body 加 overflow-x: **clip**（不是 hidden —— clip 不产生滚动容器，
          *      程序化滚动也失效）。实测探针不受影响（x 仍 -184/544、h 691、offsetW 0），
          *      root.scrollWidth 550 → 360，scrollIntoView 后 bodyX 恒 0。
          *      ⚠️ 两个戳都 bump（本常量 93 → 94，-CompatVersion 27 → 28）。
          * 1.0.0-95：**宽表格右侧 226px 在手机上永远看不到**。
          *      现象（真机截图一眼可见）：助手回复里的宽表格被右边缘硬切掉，
          *      表头「目标 / p50 / p95 / max」只显示到 p95，max 整列看不到，
          *      p95 的值（25ms / 1709）也被切一半。
          *      实测两个宽表格：容器 294px，内容 512 / 520px ⇒ 藏掉 218 / 226px。
          *      根因：官方给 .md-table-wide 设的是 **overflow-x: hidden**（不是 auto），
          *      本意是桌面端 —— 容器带 tabindex="0"，靠**键盘**左右滚。
          *      但手机上既没键盘、hidden 又**挡掉触摸滑动**：
          *      实测 dispatch touchstart+touchmove 后 scrollLeft 仍是 0。
          *      ⇒ 右侧内容**永久不可达**，这是移动端信息丢失，不是设计取舍。
          *      修法：改回 overflow-x: auto + iOS 惯性滚动。
          *      实测：scrollLeft 可到 218 / 226，四个表头**逐个**都能滚到可见
          *      （scrollIntoView 验证 0 → 86 → 218），max / 一致性列完整显示。
          *      ⚠️ .md-table-wide 是**语义类名**（非构建期哈希），稳定性高。
          *      ⚠️ 两个戳都 bump（本常量 94 → 95，-CompatVersion 28 → 29）。
          * 1.0.0-96：任务卡片半透明 ⇒ 底下的正文**透上来**，主次颠倒。
          *      现象（截图一眼可见）：任务卡片（「任务 5 已完成」+ 绿点清单）浮在
          *      正文上，背景只有 58% 不透明，下面那条消息的文字
          *      （「错误信息是「特权通道不可用」…」）透过卡片显出来，和清单叠成一团。
          *      实测配色正是难看的原因：背后正文 rgb(15,17,21) 近黑，
          *      卡片背景 rgba(248,249,250,0.58) 58% 浅灰，卡片自身文字 rgb(97,102,107)。
          *      混色 0.58×248 + 0.42×15 ≈ 150 ⇒ 透出来的正文约 rgb(150,152,155)，
          *      **比卡片内文字还亮** ⇒ 被遮挡的内容比该读的内容更显眼。
          *      ⚠️ 不是 backdrop-filter 没生效：实测内核是 Chrome **114**（不是降级目标 74），
          *      backdrop-filter 支持且 computed 就是 blur(40px) saturate(1.5)，
          *      祖先链也无 filter/opacity/transform 破坏。纯粹是基础背景太透明。
          *      修法：不透明度提到 0.97（保留毛玻璃质感、不透字），透出率 42% → 3%。
          *      亮色 rgba(248,249,250,0.97)、暗色 rgba(67,69,74,0.97) —— **必须分开写**，
          *      两边底色不同，不能共用一个颜色。
          *      ⚠️ 不改"卡片遮挡正文"本身：卡片在 composerStack 内与 composerSeat 同级，
          *      官方就是做成浮动面板（实测 30%/50%/70%/90% 都有 1~6 条消息被压住，
          *      只有滚到底部才不重叠）。根治要改官方布局，风险远大于收益。
          *      ⚠️ 两个戳都 bump（本常量 95 → 96，-CompatVersion 29 → 30）。
          * 1.0.0-97：两处**对比度不达标**，亮/暗主题下都读不清（+ 75）。
          *      【】底栏推理等级「默认」：实测亮色 **2.18:1**、暗色 **2.42:1**
          *      （WCAG AA 正文要求 4.5:1）。根因是我在里加的 opacity: 0.65 ——
          *      方向对（次级信息不该抢重心）但太狠：实测把 opacity 拉到 1 也只有
          *      3.71~3.76:1，**光调透明度永远不够**，颜色本身 rgb(129,133,140) 就偏浅。
          *      也不能只改一个颜色：亮底(白)要深色、暗底(rgb(44,44,46))要浅色，
          *      实测同一颜色不可能两边达标（提亮 173,178,184 → 暗 6.53 ✅ / 亮 2.13 ❌；
          *      压深 97,102,107 → 亮 5.80 ✅ / 暗 2.40 ❌）。⇒ 必须写两套。
          *      最终：亮 rgb(97,102,107) 5.80:1、暗 rgb(173,178,184) 6.53:1，opacity 一律 1。
          *      【】输入框占位符：亮色 **2.13:1**、暗色 **3.76:1**。这是用户
          *      **每次发消息都会看到**的提示文字。颜色来自官方变量 label-dimmed。
          *      ⚠️ 但不能一把拉满：占位符故意比正文淡是有语义的（区分"还没输入"与
          *      "已输入内容"），拉满会让人误以为已经填好了。⇒ 取**刚过 AA**：
          *      亮 rgb(112,117,122) 4.65:1、暗 rgb(165,170,176) 5.96:1。
          *      顺带把同色系的「代码块」标签与「快准狠模式（通用）」也从 ~3.5 提到 4.65。
          *      ⚠️ 两个戳都 bump（本常量 96 → 97，-CompatVersion 30 → 31）。
          * 1.0.0-98：抽屉里两处次级文字读不清。
          *      用那套对比度扫描把侧边抽屉也过了一遍，命中 6 处 < 4.5:1，
          *      其中两处值得修（都是 rgb(129,133,140) → 3.55:1）：
          *        · .bhn1Oq_sectionLabel         「工作区」分组标题
          *        · .bhn1Oq_sessionOverflowButton「展开其余 2 个会话」
          *      后者尤其要紧：它是**可点击的操作**，文字太淡会让人看不出能点，
          *      直接影响会话列表的可用性。
          *      ⚠️ 只修这两处，没把 6 处全拉高：其余（时间戳、上下文 68%、
          *      「空面板」提示、任务卡「5 已完成」）都是**纯次级信息**，全拉到 4.5
          *      会让抽屉失去层次感、看起来一片同等重要。这两处是"导航结构"和
          *      "可点操作"，性质不同才单独提上来。
          *      取值同亮 rgb(105,110,115) 4.93:1 / 暗 rgb(165,170,176) 7.36:1。
          *      ⚠️ 两个戳都 bump（本常量 97 → 98，-CompatVersion 31 → 32）。
          * 1.0.0-99：设置页所有说明副标题只有 3.71:1。
          *      设置里每项标题下那行灰字（「选择新会话的默认权限模式」
          *      「仅影响会话内容的字号」「选择希望看到多少工具调用细节」）是
          *      **用户理解这个开关干什么的唯一说明**，太淡等于没有说明。
          *      实测 rgb(129,133,140) 叠白底 → 3.71:1。
          *      ⚠️ 用 [class*="_desc"] 属性选择器而不是逐个写类名：这批文字
          *      **至少三个不同类名**（.oY77xG_desc / .bVCLcG_desc / ._2XZxNq_desc
          *      / .Pt1bsG_description / .T1PP_q_desc），各模块各自起名，逐个列既漏
          *      又难维护（官方一改 hash 前缀就全失效）。实测命中 8 个，颜色只有两种：
          *      6 个 3.71（不达标）+ 2 个 5.8（已达标），统一提亮不会误伤后者。
          *      取值亮 rgb(105,110,115) 5.15:1 / 暗 rgb(165,170,176) 5.96:1。
          *      ⚠️ 两个戳都 bump（本常量 98 → 99，-CompatVersion 32 → 33）。
          * 1.0.0-100：合并 PR #3 —— 悬浮气泡分节新增「键盘避让」卡片
          *      （46×26 开关 + 无障碍权限检测 + 独立「去开启权限」按钮）。
          *      改的仍是 `mobileui/lib/client.js`，降级产物随之变化，必须 bump。
          *      ⚠️ PR #3 原作把本常量写成 1.0.0-68、`-CompatVersion` 写成 24，
          *      那是基于旧的 66/22 算的。合并时**必须取本地已推进的值再 +1**
          *      （99 → 100 / 33 → 34），否则会回退，设备上静默跑回没有这张卡的旧 UI。
          *      ⚠️ 两个戳都 bump（本常量 99 → 100，-CompatVersion 33 → 34）。
          * 1.0.0-103：两条移动端修复（用户截图报的两个问题）。
          *      ① —— 输入框点中间落行尾。0.1.5 起官方把输入框从
          *         `<textarea>` 换成 contenteditable div，Android WebView 在这上面
          *         的「像素 → 字符偏移」算得很差（点中间、光标跳行尾）。改为在
          *         `click` 上自己用 caretPositionFromPoint 定光标；只在
          *         「空选区 + 落点明显不同」时动手，不干扰长按选词/扩选/组字。
          *      ② —— 思考正文两行竖直叠在一起糊成一团。流式追加时容器
          *         高度更新落后于文字一帧（叠加 `overflow-wrap: anywhere` 让每行
          *         宽度不可预测），改为给思考正文显式 line-height + contain: layout。
          *      本版是**纯 JS 改动**（只有 client.js），未动 CSS 之外的产物，
          *      但 `mobileui/lib/client.js` 是降级编译输入，字节变了，
          *      所以本常量必须 bump（否则设备上继续跑旧 client.js，两条修复都收不到）。
          * 1.0.0-104：的实现修正（103 那版有缺陷，未发布到设备即被本版取代）。
          *      ① 原实现用 `e.target` 当编辑区容器做 contains 判据 —— 点文字时
          *         target 是 Lexical 渲染的行内 span、点空白处是 root、点 <br> 又是
          *         另一个，判据会漏掉一大半情形。改成先 closest 找 composer 根，
          *         再在根里找 contenteditable 本体。
          *      ② 原实现在 click 里**同步**改 selection，而 Lexical 自己也监听
          *         click 并会把 selection 写回内部 model，随后覆盖我们设的位置 ——
          *         用户看到「光标闪一下又跳回行尾」。改成 setTimeout(0) 延后到
          *         本次事件处理完之后，让浏览器自然派发 selectionchange，
          *         Lexical 跟着同步。不碰它的内部 API。
          *      ③ 同时把的根因注释改成诚实表述：原注释把推断写成了
          *         「实测」，而这一条**没有真机逐帧验证过**，会误导后来人。
          * 1.0.0-105：用户反馈的《移动端 UI 修复报告》里三条**在当前源码中真实存在**
          *      的缺陷（报告其余条目描述的是手机上手工打的补丁，源码里没有）。
          *      ① —— 过程行标题「一直抽搐」。为了治「思考正文不换行
          *         把整页推出屏幕」，把官方 TextShimmer 的 `._root_1rdzk_1`
          *         （`width: max-content`，标题单行的唯一保证）无差别改成了 `auto`。
          *         流式标题因此按可用宽度换行，行数 1→2→3 反复变，容器高度跟着弹。
          *         改为**只在 `.O_Ebla_title`（过程组标题）内部**把 max-content 还回去，
          *         思考正文章节完全不动；顺带把扫光的 `steps(48)` 改成 `linear`。
          *      ② —— 底部统计条字号突然变大。`processStatsLine` 原先直接取
          *         `[data-composer-stat]`（anchor 层）当 label，`textContent` 赋值会
          *         **删掉内部 button.bOPqQW_pill + span.bOPqQW_label**，只剩裸文本，
          *         字号从官方 13px 变成继承 body 的 16px，撑满底栏。
          *         改为先取下钻到 `.bOPqQW_label`，取不到才退回 anchor（保证降档仍触发）。
          *      ③ —— 点「刷新」后 401、会话列表空、回首页。页面侧原来是
          *         整页 `location.reload()`，而重载请求的是 `./`、不再带启动时那条
          *         `?token=`，于是 API / WebSocket 全部 401，只能重启 App 恢复。
          *         改为走原生桥新增的 `reloadWebUi()`：重载 `lastLoadedUrl`
          *         （带 token 的原始地址），并复用 `reloadWithRetry` 那套
          *         「重置首帧 → 重载 → 等新首帧 → 失败重试」判据。
          *         刷新前先 `mpPersistCurrentSession()` 落盘当前会话，避免跳槽。
          *      ⚠️ 本版**动了 Kotlin**（PageBridge / SystemWebHost / GeckoWebHost /
          *         MainActivity / content.js 五处），不只是 client.js。
          *      本常量仍必须 bump：不然设备上的 `.mobile_ui_version` 不变，
          *      mobile-ui.zip 解包被整体跳过，三条修复一条都收不到。
          */
        /*
         * ## 110（2026-10-31）：`onUiReady` 可能永远不来 ⇒ 启动层永久盖住界面
         *
         * 真机现场（20261031-053321，vivo V2231A / gecko 92）：
         *
         *     05:29:46  engine.ready ms=8537 port=3080     ← 引擎完全正常
         *     05:29:58  界面就绪回调超时（内核140，页面错误: 无）
         *     05:30:13  界面就绪回调超时                  ← 27 秒仍未就绪
         *
         * 三处根因，都改在 `mobile-ui-plugin/dsh-android-mobile/lib/client.js`：
         *
         *   ① `mpSignalUiReady()` 原排在 `apply()` **末尾**，前面 30 多个初始化
         *      全无 try/catch。任何一个抛异常 ⇒ 它永不执行 ⇒ `onUiReady` 永不上报。
         *      ⇒ 现已提到 `apply()` 最前面并 try 包裹。
         *   ② 桥不在时 `if (!bridge) return;` 直接退出。而 gecko 的桥由
         *      WebExtension 注入，**早于还是晚于本插件不保证** ⇒ 晚到时永久丢失。
         *      ⇒ 改为轮询等桥（15 秒），等到就照常按判据上报。
         *   ③ `ready()` 要求 `readyState === "complete"`。页面只要有一个资源
         *      挂住（或渲染进程被回收重挂），就永远停在 `interactive`。
         *      ⇒ 外壳（`data-mobile` + `.mp-nav-btn`）就位后再宽限 2.5 秒即交付。
         *
         * ⚠️ 本版**只动了 client.js**（Kotlin 未改），但常量**必须** bump：
         *   否则 `.mobile_ui_version` 不变，新 zip 解包被整体跳过 —— 这个失败是
         *   完全静默的（见 AGENTS.md 三条最容易违反的规矩之一）。
         */
const val MOBILE_UI_VERSION = "1.0.0-130"
const val MOBILE_UI_STAMP = ".mobile_ui_version"

