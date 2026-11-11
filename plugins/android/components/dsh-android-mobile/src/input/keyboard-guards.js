export function createKeyboardGuards(mpIsMobile, mpSessionsOf, getInputTriggers) {
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
		
  		const triggers = getInputTriggers();
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

  return { setupComposerKeyboardGuard, setupTriggerMenuAutoDismiss, setupKeepFocusGuard };
}
