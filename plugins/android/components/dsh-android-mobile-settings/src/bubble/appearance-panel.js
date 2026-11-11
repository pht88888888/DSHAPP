import { MP_PRIV_S, MP_PRIV_TONE } from "../settings-styles.js";
import { MP_BUBBLE_ZH } from "./messages.js";

export function createBubblePanel(getReact) {
  function mpBubblePanel(props) {
  	const React = getReact();
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

  return mpBubblePanel;
}
