export function createMobileShell({ mpIsMobile, mpDialogBack, mpInstallAliasBridge, mpTeardownAliasBridge, mpSessionsOf, showRenameDialog, layoutOf, sidebarRightOf, uiSessionOf, slotsOf, configFormsOf }) {
  const disposers = [];
  const pendingTimers = new Set();
  let active = true;
  let anchorObserver = null;
  let previousBack;
  function listen(target, type, handler, options) {
    target.addEventListener(type, handler, options);
    disposers.push(() => target.removeEventListener(type, handler, options));
  }
  function later(handler, ms) {
    const id = window.setTimeout(() => { pendingTimers.delete(id); if (active) handler(); }, ms);
    pendingTimers.add(id);
    return id;
  }
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
  		//   原实现只做 later(mpSyncDrawer, 60)：官方 React 要 ~800ms 才
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
  		later(mpSyncDrawer, 60);
  		later(mpSyncDrawer, 300);
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
  			later(() => {
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
  		mpCloseTimer = later(() => {
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
  		mpCollapseFallback = later(() => finish("timeout"), 3000);
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
  		later(mpSyncDrawer, 60);
  		later(mpSyncDrawer, 260);
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
  	/* ══ 每帧最多量一次还不够：聊天流式输出时**每帧都有 mutation** ══════
  	 *
  	 * ⚠️ 真机 CPU profile（2026-11-13）：这三件事合起来在 4 秒窗口里占掉
  	 *   "getBoundingClientRect" 250+238ms、还有一次 "getComputedStyle" —— 而它们
  	 *   量的是**顶栏标题居中留白 / 顶栏高度 / 浮层汉堡让位**，这些东西在流式输出
  	 *   期间根本不变。等于每帧白扔几次强制同步布局，正是「用着发黏」的来源之一。
  	 *
  	 * 现在改成：首帧立刻量（保证首屏不错位），之后**最多每 200ms 一次**，
  	 * 并且用末尾补一次的定时器保证「最后一次变化一定会被量到」。
  	 * 纯外观量算晚 200ms 用户看不出来，但省下的是每帧一到两次强制布局。
  	 */
  	var mpCenterLastRun = 0;
  	var mpCenterTrailing = 0;
  	var MP_CENTER_INTERVAL_MS = 200;
  	function mpRunHeaderCenter() {
  		mpCenterLastRun = Date.now();
  		// 修复 61：高度自校准与居中留白共用这一帧（共用节流，不额外增加重排次数）
  		try { mpSyncHeaderHeight(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
  		try { mpSyncHeaderCenter(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
  		// 修复 66：与上面两件事共用同一帧（零额外重排）
  		try { mpSyncFloatNavGap(); } catch (e) { /* 静默：纯外观，绝不影响功能 */ }
  	}
  	function mpScheduleHeaderCenter() {
  		if (!active) return;
  		if (mpCenterRaf) return;
  		mpCenterRaf = requestAnimationFrame(() => {
  			mpCenterRaf = 0;
  			const now = Date.now();
  			if (mpCenterLastRun && now - mpCenterLastRun < MP_CENTER_INTERVAL_MS) {
  				// 节流中：记一笔「稍后还欠一次」，保证最终状态一致
  				if (!mpCenterTrailing) {
  					mpCenterTrailing = window.setTimeout(() => {
  						mpCenterTrailing = 0;
  						if (!active) return;
  						mpRunHeaderCenter();
  					}, MP_CENTER_INTERVAL_MS);
  				}
  				return;
  			}
  			mpRunHeaderCenter();
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
  	/* ══ 状态栏高度：读内联变量，绝不读 computed ══════════════════════
  	 *
  	 * ⚠️ 真机 CPU profile（2026-11-13）里 "getPropertyValue" 的自身耗时 550ms，
  	 *   是全页最贵的一项，调用点就是这里 —— "getComputedStyle(...)" 是同步样式
  	 *   计算，而 "mpSyncFloatNavGap()" 被 "mpScheduleHeaderCenter()" 挂在每个
  	 *   mutation 批次后的 rAF 里，等于**每次界面更新都强制一次全量样式重算**。
  	 *
  	 * 原生写入方式（见 MainActivity.pushSystemBarInset）是
  	 *   "document.documentElement.style.setProperty('--dsh-android-system-top', …)"
  	 * —— 也就是**内联样式**。内联读取不触发任何计算，值还更准（computed 会
  	 * 受层叠/继承影响）。只有内联值为空时（原生一次都没写过）才退回 computed，
  	 * 那种情况只可能发生在启动极早期，兜底用。
  	 */
  	function mpReadSystemTop() {
  		try {
  			const inline = document.documentElement.style.getPropertyValue("--dsh-android-system-top");
  			if (inline) return parseFloat(inline) || 0;
  			if (mpSysTopFallback === undefined) {
  				mpSysTopFallback = parseFloat(
  					getComputedStyle(document.documentElement).getPropertyValue("--dsh-android-system-top"),
  				) || 0;
  			}
  			return mpSysTopFallback;
  		} catch (e) { return 0; }
  	}
  	var mpSysTopFallback;

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
  		const sysTop = mpReadSystemTop();
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
		
  		try { return layoutOf() ?? null; } catch (e) { return null; }
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
		
  		try { return sidebarRightOf() ?? null; } catch (e) { return null; }
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
  		anchorObserver = new MutationObserver(() => { if (active) mpAnchorBurger(); });
  		anchorObserver.observe(document.body, { childList: true, subtree: true });
  		// ⚠️ 原来这里在捕获阶段拦截官方 .hHd-Xa_toggle 的点击（抢在官方 morph 之前
  		//   先滑出抽屉），抽屉交回官方后这段拦截必须去掉，否则官方收不到点击。
  		listen(window, "resize", () => { mpSyncMobileAttr(); mpSyncDrawer(); mpScheduleHeaderCenter(); }, { passive: true });
  		// §12：把返回键处理器交给原生。原生拦下系统返回键后调它，
  		//   返回值 = 「这一层被消费了吗」；只有 false 才退出 App。
  		//   ⚠️ 挂在 mpBuildShell 里（与上面三个监听器同处，只执行一次）；
  		//   拆解时**不摘**，理由与 mpShellBuilt 不复位相同。
  		previousBack = window.__DSH_MOBILE_BACK__;
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
  			var ui = uiSessionOf();
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
  		listen(btn, "click", onClick);
  		wrap.appendChild(btn);
  		list.appendChild(wrap);
  	}

  	// 0.2.2 的轨迹标签可能不渲染，复用官方 header 的 store 与 selectView。
  	function mpConversationView() {
  		const slots = slotsOf();
  		const binding = uiSessionOf()?.current?.value;
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
  				const forms = configFormsOf();
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
  		listen(more, "click", () => { later(mpEnsureRefreshItem, 0); later(mpEnsureRefreshItem, 60); });
  	}

  	function bindTitleRename() {
  		if (!mpIsMobile()) return;
  		mpBindMoreButton();
  		mpEnableTitleTap();
  		const header = document.querySelector(".wSkVaW_header");
  		if (!header || header.dataset.mpRenameBound) return;
  		header.dataset.mpRenameBound = "1";
  		listen(header, "click", (e) => {
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

  function disposeShell() {
    active = false;
    for (const id of pendingTimers) window.clearTimeout(id);
    pendingTimers.clear();
    for (const dispose of disposers.splice(0)) dispose();
    anchorObserver?.disconnect();
    if (mpCenterRaf) cancelAnimationFrame(mpCenterRaf);
    if (window.__DSH_MOBILE_BACK__ === mpHandleSystemBack) {
      if (previousBack === undefined) delete window.__DSH_MOBILE_BACK__;
      else window.__DSH_MOBILE_BACK__ = previousBack;
    }
    document.querySelectorAll("[data-mp-rename-bound]").forEach(node => { delete node.dataset.mpRenameBound; });
    mpTeardownAliasBridge();
    mpTeardownMobile();
    document.documentElement.removeAttribute("data-mobile");
  }

  return { disposeShell, mpSyncMobileAttr, mpBuildShell, mpSyncDrawer, mpAnchorBurger, bindTitleRename, mpEnsureRefreshItem, mpOpenDrawer, mpCloseDrawer, mpTeardownMobile };
}
