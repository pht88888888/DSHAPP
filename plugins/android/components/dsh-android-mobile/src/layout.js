import { injectStyles } from "./styles/index.js";
import { createSettingsNavigation } from "./settings-navigation.js";
import { createAliasBridge } from "./selector-aliases.js";
import { createKeyboardGuards } from "./input/keyboard-guards.js";
import { createEnterAsNewline } from "./input/enter-newline.js";
import { createCaretFix } from "./input/caret.js";
import { createCredentialKeyboard } from "./input/credentials.js";
import { createTrajectoryTiming } from "./trajectory-timing.js";
import { setupLongPress } from "./input/long-press.js";
import { createMobileShell } from "./shell.js";

export function createMobileLayout() {
  let active = true;
  const MP_NARROW = 1024;
  const mpDialogBack = new WeakMap();
  const { setupSettingsDialog } = createSettingsNavigation(mpIsMobile, mpDialogBack);
  const { mpInstallAliasBridge, mpTeardownAliasBridge } = createAliasBridge();
  const { setupComposerKeyboardGuard, setupTriggerMenuAutoDismiss, setupKeepFocusGuard } = createKeyboardGuards(mpIsMobile, mpSessionsOf, () => mpCtx?.get("inputTriggers"));
  const setupEnterAsNewline = createEnterAsNewline(mpIsMobile);
  const setupCaretFix = createCaretFix(mpIsMobile);
  const setupPlainKeyboardForCredentials = createCredentialKeyboard(mpIsMobile);
  const { mpPatchTrajectoryTiming } = createTrajectoryTiming(mpIsMobile, mpSessionsOf);
  const { mpSyncMobileAttr, mpBuildShell, mpSyncDrawer, mpAnchorBurger, bindTitleRename, mpEnsureRefreshItem, mpOpenDrawer, mpCloseDrawer, disposeShell } = createMobileShell({
    mpIsMobile, mpDialogBack, mpInstallAliasBridge, mpTeardownAliasBridge, mpSessionsOf, showRenameDialog,
    layoutOf: () => mpCtx?.get("layout"), sidebarRightOf: () => mpCtx?.get("sidebarRight"),
    uiSessionOf: () => mpCtx?.get("uiSession"), slotsOf: () => mpCtx?.get("slots"), configFormsOf: () => mpCtx?.get("configForms"),
  });


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
    const timers = new Set();
    const poll = (fn, ms) => {
      const id = window.setInterval(() => { if (active) fn(); }, ms);
      timers.add(id);
      return id;
    };
    const dispose = () => { for (const id of timers) window.clearInterval(id); timers.clear(); };
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
  		const wait = poll(() => {
  			bridge = hasBridge();
  			if (bridge) {
  				window.clearInterval(wait);
  				arm();
  			} else if (Date.now() - t0 > BRIDGE_WAIT_MS) {
  				window.clearInterval(wait);   // 真的没有桥：桌面端，安静退出
  			}
  		}, 200);
  		return dispose;
  	}
  	arm();
    return dispose;

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

  	/*
  	 * 真实 UI 的**结构锚点**：这些元素只存在于「官方前端已经渲染出可用界面」之后，
  	 * 启动页（无论它长什么样）里一个都不会有。
  	 *
  	 * ⚠️ 为什么必须有这一条（2026-11-12，用户反馈）：
  	 * 上面那条 `officialBootVisible()` 认的是**文案**（`Loading plugins`）。而
  	 * **用户可以自己换掉启动动画 / 主题 / 加载文案** —— 换掉之后
  	 *  - 文案判据永远返回 false：不能再拿它证明「启动页还在」（早收，白闪）；
  	 *  - 更糟的是原生侧的卡死判据原本也钉在启动页文案上（`首帧正文="HARNESS`），
  	 *    用户一改文案，**真正卡死时反而永远检测不到**。
  	 * 所以判"界面真的能用了"必须落在**我们自己能观察到的事实**上：
  	 * 真实 UI 的锚点在不在。
  	 *
  	 * 选择器的取舍：只挑**结构性的**、不随官方哈希类名漂移的
  	 * （`aria-label`、`role`、`contenteditable`、以及本插件自己打的
  	 * `data-mobile`/`.mp-nav-btn`）。`uV2eYG_input` 那类哈希类名**不进这里** ——
  	 * 官方一升级就失效，等于又给自己造一条会静默失效的判据。
  	 */
  	const realUiAnchors = () => {
  		if (!document.querySelectorAll) return 0;
  		let n = 0;
  		const probe = (sel) => { try { if (document.querySelector(sel)) n++; } catch (e) { /* 旧内核不认某个选择器不算错 */ } };
  		probe('[contenteditable="true"]');
  		probe('div[role="textbox"]');
  		probe('textarea');
  		probe('button[aria-label]');
  		probe('[class*="_sessionRow"]');
  		probe('.mp-nav-btn');
  		return n;
  	};

  	const ready = () => {
  		if (!shellReady()) { shellReadyAt = 0; return false; }
  		if (!shellReadyAt) shellReadyAt = Date.now();
  		// ⚠️ 顺序：**先看结构锚点**。真实 UI 已经挂上就是最硬的证据，
  		//   比任何文案判据都可靠，也比 `readyState === "complete"` 稳
  		//   （后者会被一个挂住的图片/请求永久拖住，见下面 PAGE_LOAD_GRACE_MS）。
  		if (realUiAnchors() > 0) return true;
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
  	timer = poll(() => {
  		polls++;
  		const bodyTxt = (document.body && document.body.innerText) || "";
  		if (polls === 1) firstText = bodyTxt.replace(/\s+/g, " ").slice(0, 40);
  		if (officialBootVisible()) bootSeen = true;
  		if (ready()) {
  			// ⚠️ 诊断信息要带**实际观测到的文本**：只报"见到/没见到"无法区分
  			//   "判据正确"与"判据根本没生效"，这一轮就因此多绕了一次。
  			// ⚠️ 锚点数一并上报：原生侧据此判断「界面真的能用了」，
  			//   而这个判断**不依赖任何前端文案**（用户可换启动动画，见 realUiAnchors）。
  			report(
  				(bootSeen ? "官方启动页已消失" : "未见到官方启动页") +
  				" [polls=" + polls + " bootSeen=" + bootSeen +
  				" 锚点=" + realUiAnchors() +
  				" 首帧正文=" + JSON.stringify(firstText) + "]"
  			);
  		} else if (Date.now() - t0 > UI_READY_MAX_WAIT_MS) {
  			report("超时兜底 [polls=" + polls + " bootSeen=" + bootSeen +
  				" 锚点=" + realUiAnchors() + "]");
  		}
  	}, 50);
  	}
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
  		if (active) mpPatchTrajectoryTiming();
  	});
  };
  const mo = new MutationObserver((muts) => {
  	if (!active) return;
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
  		ctx.effect(mpSignalUiReady);
  	} catch (e) {
  		mpDiag('onUiReady 守候安装失败: ' + (e && e.message));
  	}

  	mpCtx = ctx;
  	mpSessionsCache = ctx.get("sessions") ?? null;
  	mpInstallPendingTrust();   /* 修复 51：越早越好 */
  	mpWatchSessions(ctx);       /* 修复 51：等服务就绪 */
  	const disposeStyles = injectStyles();
  	const disposeLongPress = setupLongPress(mpIsMobile);
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
  		if (!active) return;
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
  	let probeTimer = 0;
  	try {
  		probeTimer = window.setTimeout(() => {
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
  		active = false;
  		clearTimeout(probeTimer);
  		clearTimeout(moTimer);
  		disposeLongPress();
  		disposeShell();
  		disposeStyles();
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


  // 业务包独立声明依赖，布局不等待余额账号服务。

  return { apply, inject: [] };
}
