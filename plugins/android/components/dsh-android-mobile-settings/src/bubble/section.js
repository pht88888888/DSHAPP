import { MP_PRIV_S, MP_PRIV_TONE } from "../settings-styles.js";
import { MP_BUBBLE_NS, MP_BUBBLE_ZH, MP_BUBBLE_EN } from "./messages.js";
import { createBubblePanel } from "./appearance-panel.js";

export function createBubbleSection(require) {
  let mpPrivReact = null;
  const mpBubblePanel = createBubblePanel(() => mpPrivReact);
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
  return mpRegisterBubbleSection;
}
