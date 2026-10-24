/**
 * dsh-android-browser-pane —— 客户端半：在**官方右侧边栏**里加一项「AI 浏览器」。
 *
 * ## 为什么必须走官方槽位
 *
 * 需求是「从应用右侧边栏进入」。贴一个悬浮按钮不是那个位置：它挡内容、位置固定、
 * 与官方的面板切换逻辑无关。官方右侧栏的接缝是两个，**缺一不可**：
 *
 * | 接缝 | 作用 | 缺了会怎样 |
 * |---|---|---|
 * | `sidebarRightTabs.register({ id, kind, title, guide })` | 定义一种 tab —— 右侧栏的「+」新建列表与空态引导页会列出 `guide` 里的胶囊 | 列表里根本没有这一项 |
 * | `slots.register({ name: "sidebar.right.pane.tab", key })` | 提供这个 tab 的**正文** | tab 能开出来，但一片空白 |
 *
 * `key` 必须等于上面那个 `id` —— 座位就是按类型 id 找正文的。
 *
 * ## 两个必须知道的前提
 *
 * ① 本包 `package.json` 的 `dsh.client.inject` 是空数组（客户端半刻意不声明依赖，
 *    免得某个服务缺失就让整个插件不激活）。所以**外层 `ctx` 取不到**
 *    `slots` / `sidebarRightTabs`（会抛 `cannot get property ... without inject`），
 *    必须用 `ctx.inject([...], (scope) => ...)` 开一个子 fiber，从 `scope` 上取。
 *
 * ② 客户端模块不是 ESM，而是 `window.__ModuleLoader__.load({ id, factory })` ——
 *    由引擎提供的 `require` 才能拿到 `react` 与官方服务。
 */

window.__ModuleLoader__.load({
	id: "dsh-android-browser-pane",
	factory: (require) => {
		"use strict";
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

		/** 侧栏项的类型 id。**必须与 `slots.register` 的 `key` 一致。** */
		const TAB_ID = "dsh-android-ai-browser";

		/** 类型判别符。用自己独有的 kind，不去抢官方的（`browser` 是官方的 iframe 浏览器）。 */
		const TAB_KIND = "android-ai-browser";

		/**
		 * 调原生打开 AI 浏览器。
		 *
		 * @returns {boolean} 桥不存在时 false（旧 APK / 白名单漏了）。
		 */
		function openNativeAiBrowser() {
			try {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.openAiBrowser !== "function") {
					console.warn("[dsh-android-browser-pane] 原生桥没有 openAiBrowser（版本过旧？）");
					return false;
				}
				bridge.openAiBrowser();
				return true;
			} catch (error) {
				console.warn("[dsh-android-browser-pane] 打开 AI 浏览器失败:", error && error.message);
				return false;
			}
		}

		/**
		 * 原生 → 前端的状态事件名。**必须与 `MainActivity.notifyBrowserPane`
		 * 里 `dispatchEvent` 的事件名逐字一致。**
		 */
		const STATE_EVENT = "dsh:browser-state";

		/**
		 * 右侧栏「AI 浏览器」面板的正文。
		 *
		 * ## 它其实是个「占位 + 覆盖」
		 *
		 * 原生 WebView **不能**放进网页 DOM 里（iframe 会被 `X-Frame-Options`
		 * / CSP 拒绝 —— 那正是这套方案存在的原因）。所以这里渲染一块铺满面板的
		 * 空 div，把它 `getBoundingClientRect()` 量出来上报给原生，原生再把
		 * WebView 摆到屏幕上同一块矩形上。
		 *
		 * ## 为什么是「逐帧跟随」而不是观察者
		 *
		 * 收起侧栏时官方**不卸载面板**，只是给容器加 `transform: translateX(...)`
		 * 把它滑到屏幕外（`dsh-client-ui-sidebar-right` 的注释写得很清楚：
		 * "Docked content stays mounted while collapsed, translated off the
		 * frame's right edge"）。
		 *
		 * transform **不改变 `getBoundingClientRect()` 之外任何观察者能感知的东西**：
		 *
		 * | 观察者 | 收起时会不会触发 | 为什么 |
		 * |---|---|---|
		 * | `ResizeObserver` | **不会** | 尺寸一个像素都没变 |
		 * | `IntersectionObserver` | 会，但**晚** | 它是异步的、按空闲时机回调，动画早跑完了 |
		 * | `requestAnimationFrame` | **每帧都跑** | 自己轮询位置，跟得上动画 |
		 *
		 * 实测（用户录屏）：收起动画 ~250ms，用 IntersectionObserver 时浏览器
		 * 停在原地约 1 秒才突然消失，看起来就是「延迟收回、没跟上」。
		 * 所以这里改成**浏览器显示期间每帧量一次位置**，矩形一变就推给原生。
		 *
		 * 代价是显示期间每帧一次 `getBoundingClientRect()`。它是只读布局查询，
		 * 面板本身不动时读的是缓存值，开销可忽略；而且矩形没变时**不上报**，
		 * 原生那边也会短路。不显示时（`liveRef` 为 false）整个循环直接停掉，
		 * 不空转。
		 *
		 * ## 两个方向都必须有通知
		 *
		 * ```
		 * 前端 → 原生：aiBrowserLayout(rect)   面板在哪、多大
		 * 原生 → 前端：dsh:browser-state      浏览器还在不在
		 * ```
		 *
		 * @param {object} props - 座位注入的 hook 上下文。
		 * @returns {object} React 元素。
		 */
		function BrowserPane(props) {
			// ⚠️ `useTabInfo` **必须作为形参声明**才能拿到。
			//   渲染器只把「槽位声明的 hook 依赖」展开成同名 props
			//   （`standardHookPropName("tabInfo")` → `useTabInfo`），
			//   而 `hookContext` 本身**不会**作为 prop 传下来。
			//   之前写 `props.hookContext.actions` 永远是 undefined，
			//   于是「点 ✕ 关 tab」静默失效 —— 用户看到的就是
			//   「浏览器关了，但 DSH 的浏览器标签还在」。
			const useTabInfo = props && props.useTabInfo;
			// ⚠️ 官方 `useTabInfo()` 在 tab 尚未 commit 时会**抛异常**
			//   （`sidebarRight: tab "..." is not committed in session "..."`）。
			//   正常渲染不会撞上，但错误边界或热重载的瞬间可能，不能让整个
			//   面板白屏 —— 退化成「拿不到动作」，功能降级而不是崩。
			let tabInfo = null;
			if (typeof useTabInfo === "function") {
				try {
					tabInfo = useTabInfo();
				} catch (error) {
					tabInfo = null;
				}
			}
			const React = require("react");
			const e = React.createElement;
			const slotRef = React.useRef(null);
			// idle = 还没打开；live = 浏览器正在这块区域里显示
			const [phase, setPhase] = React.useState("idle");
			// ⚠️ 这几个必须用 ref 而不是闭包变量 / state：
			//   闭包变量在 effect 外（点击回调里）读不到，state 在 rAF 回调里
			//   读到的是旧值。ref 两边都能读写当前值。
			const liveRef = React.useRef(false);
			const rectPayloadRef = React.useRef(null);
			const detachRef = React.useRef(null);
			/**
			 * 关闭整个侧栏 tab。座位通过 `useTabInfo().actions.close` 提供，
			 * 拿不到时退化成只隐藏浏览器（老版本官方前端没有这个动作）。
			 */
			const closeTabRef = React.useRef(null);
			// 座位通过 hookContext 把该 tab 的动作传进来（`useTabInfo().actions`）。
			// `actions.close()` 就是标题栏那个 ✕ 做的事 —— 关掉本 tab。
			const tabActions = tabInfo && tabInfo.tab && tabInfo.tab.actions;
			React.useEffect(() => {
				closeTabRef.current =
					tabActions && typeof tabActions.close === "function"
						? () => tabActions.close()
						: null;
			}, [tabActions]);

			React.useEffect(() => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.aiBrowserLayout !== "function") {
					setPhase("noBridge");
					return undefined;
				}

				let raf = 0;
				let lastPayload = "";
				// 卸载后 rAF 回调可能还在队列里，必须能一眼看出来。
				let disposed = false;

				/**
				 * 读出 DSH 当前主题的配色，交给原生去画浏览器外框。
				 *
				 * 为什么从**这里**取而不是原生自己判断：原生那个 WebView 是独立的，
				 * 它既不知道 DSH 现在是深色还是浅色，也拿不到 DSH 的配色变量。
				 * 而面板插件正跑在 DSH 页面里，`getComputedStyle` 一问就有。
				 *
				 * 取的是 `document.body` 的**计算后**背景色：DSH 的主题就是靠
				 * `body[data-ds-dark-theme]` 切换的，计算值天然跟着变，不用维护
				 * 一份色表。深色下实测是 `rgb(67, 69, 74)`，浅色是 `rgb(248, 249, 250)`。
				 *
				 * @returns {object} `{ dark, bg, fg, muted }`，取不到的字段不出现。
				 */
				const themePayload = () => {
					try {
						const dark = !!document.body && document.body.hasAttribute("data-ds-dark-theme");
						const style = document.body ? getComputedStyle(document.body) : null;
						const bg = style ? style.backgroundColor : "";
						const fg = style ? style.color : "";
						// `rgba(0, 0, 0, 0)` 这种透明背景没有参考价值，丢掉。
						const solid = (c) => (c && !/rgba\(\s*0,\s*0,\s*0,\s*0\s*\)/.test(c) ? c : "");
						return {
							dark,
							bg: solid(bg),
							fg: solid(fg),
						};
					} catch (error) {
						return { dark: false };
					}
				};

				/**
				 * 量出占位区域的当前矩形（CSS 视口坐标）。
				 *
				 * @returns {string|null} JSON 串；尺寸无效时 null。
				 */
				const rectPayload = () => {
					const node = slotRef.current;
					if (!node) return null;
					let rect = node.getBoundingClientRect();
					// 占位 div 在某些面板里高度会塌成 0（父级是 auto 高度）。
					// 那就退一步量父元素 —— 面板本身的矩形才是我们真正要的区域。
					if (rect.height < 8 && node.parentElement) {
						rect = node.parentElement.getBoundingClientRect();
					}
					if (rect.width < 8 || rect.height < 8) return null;
					return JSON.stringify({
						x: Math.round(rect.left),
						y: Math.round(rect.top),
						w: Math.round(rect.width),
						h: Math.round(rect.height),
						vw: Math.round(window.innerWidth),
						vh: Math.round(window.innerHeight),
						...themePayload(),
					});
				};
				rectPayloadRef.current = rectPayload;

				/**
				 * 收起浏览器（只隐藏，页面与登录态都留着）。
				 *
				 * @param {boolean} resetPhase - 是否把面板切回「未打开」。
				 */
				const detach = (resetPhase) => {
					liveRef.current = false;
					lastPayload = "";
					if (resetPhase) setPhase("idle");
					try {
						if (typeof bridge.aiBrowserDetach === "function") bridge.aiBrowserDetach();
					} catch (error) {
						/* 面板正在收起 / 已卸载，忽略 */
					}
				};
				detachRef.current = detach;

				/** 把当前矩形推给原生。矩形没变就不推。 */
				const push = () => {
					if (!liveRef.current) return;
					const payload = rectPayload();
					// 量不出矩形 = 面板被完全收起（宽度塌陷 / 移出视口）。
					// 这时候要立刻隐藏，否则原生那块 WebView 会留在屏幕上。
					if (!payload) {
						detach(true);
						return;
					}
					if (payload === lastPayload) return;
					lastPayload = payload;
					try {
						bridge.aiBrowserLayout(payload);
					} catch (error) {
						console.warn("[dsh-android-browser-pane] 上报面板矩形失败:", error && error.message);
					}
				};

				/**
				 * 逐帧跟随循环。
				 *
				 * 只在浏览器显示期间跑；隐藏后**自己停掉**，不空转。
				 * 用 rAF 而不是 `setInterval`：前者跟着屏幕刷新走，动画期间
				 * 一帧一次刚好对齐；后者会跟渲染抢时间、还可能重复上报。
				 */
				const tick = () => {
					raf = 0;
					if (!liveRef.current) return;
					push();
					if (liveRef.current) raf = window.requestAnimationFrame(tick);
				};

				const startLoop = () => {
					if (raf) return;
					raf = window.requestAnimationFrame(tick);
				};

				/** 原生通知浏览器开了 / 关了。 */
				const onState = (event) => {
					const visible = !!(event && event.detail && event.detail.visible);
					lastPayload = "";
					if (visible) {
						liveRef.current = true;
						setPhase("live");
						startLoop();
					} else {
						liveRef.current = false;
						// ⚠️ 用户点 ✕ 主动关掉浏览器 ⇒ **连侧栏 tab 一起关**。
						//   只把面板切回 idle 的话，用户看到的是「浏览器没了、
						//   但 tab 还在」，还得自己去点那个 ✕ —— 这正是他抱怨的
						//   「APP 没有关掉浏览器的标签页」。
						//   侧栏自己收起（detach）不会带这个标记，tab 要留着。
						if (event && event.detail && event.detail.userClosed) {
							setPhase("idle");
							if (closeTabRef.current) {
								try {
									closeTabRef.current();
									return;
								} catch (error) {
									console.warn("[dsh-android-browser-pane] 关闭 tab 失败:", error && error.message);
								}
							}
						}
						setPhase("idle");
					}
				};

				window.addEventListener(STATE_EVENT, onState);

				// 尺寸 / 位置突变时的兜底（键盘弹出、旋转、侧栏拖宽）。
				// 正常跟随靠 tick，这里只负责在隐藏状态下也别漏掉首次布局。
				const onViewport = () => {
					if (liveRef.current) startLoop();
				};
				window.addEventListener("resize", onViewport);
				window.addEventListener("orientationchange", onViewport);

				// ⚠️ 挂载即打开 —— 不要留「再点一次才能用」的二级入口。
				//
				// 用户点开侧栏的「AI 浏览器」，要的就是浏览器本身。之前这里先渲染
				// 一张说明卡片 + 「打开浏览器」按钮，等于让他多点一步，体验很别扭。
				//
				// 顺序仍然是「先摆位、再显示」：rectPayload 此时可能还量不到
				// （首帧布局未完成），所以推不进矩形时交给 tick 循环，它会等到
				// 量得出矩形的那一刻再推。
				let already = false;
				try {
					already =
						typeof bridge.aiBrowserState === "function" &&
						bridge.aiBrowserState() === "visible";
				} catch (error) {
					/* 老 APK 没有这个方法：按「没开」处理，直接打开 */
				}
				if (already) {
					liveRef.current = true;
					setPhase("live");
					startLoop();
				} else {
					// 用一帧的延迟让面板先完成首次布局，否则量到的是 0×0。
					window.requestAnimationFrame(() => {
						if (disposed) return;
						const payload = rectPayload();
						if (payload && typeof bridge.aiBrowserLayout === "function") {
							try {
								bridge.aiBrowserLayout(payload);
							} catch (error) {
								/* 下一帧 tick 还会再推 */
							}
						}
						liveRef.current = true;
						setPhase("live");
						openNativeAiBrowser();
						startLoop();
					});
				}

				return () => {
					disposed = true;
					rectPayloadRef.current = null;
					detachRef.current = null;
					if (raf) window.cancelAnimationFrame(raf);
					window.removeEventListener(STATE_EVENT, onState);
					window.removeEventListener("resize", onViewport);
					window.removeEventListener("orientationchange", onViewport);
					// tab 切走 / 面板卸载：把浏览器收起来（只隐藏，状态保留）。
					try {
						if (typeof bridge.aiBrowserDetach === "function") bridge.aiBrowserDetach();
					} catch (error) {
						/* 面板已经在拆，什么都做不了 */
					}
				};
			}, []);

			/**
			 * 打开浏览器。
			 *
			 * ⚠️ **顺序不能反**：先把矩形推过去让原生把 WebView 摆到面板上并
			 * 显示，再调 `openAiBrowser()` 加载首页。反过来的话 WebView 会先以
			 * MATCH_PARENT（全屏）露出来一帧，用户看到一次「闪全屏」。
			 */
			const openBrowser = () => {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.openAiBrowser !== "function") return;
				const payload = rectPayloadRef.current ? rectPayloadRef.current() : null;
				if (payload && typeof bridge.aiBrowserLayout === "function") {
					try {
						bridge.aiBrowserLayout(payload);
					} catch (error) {
						console.warn("[dsh-android-browser-pane] 摆位失败:", error && error.message);
					}
				}
				liveRef.current = true;
				setPhase("live");
				openNativeAiBrowser();
			};

			// 侧栏 tab 的标题栏 chip 会渲染同一个组件，但 `props.hookContext.title`
			// 为真 —— 那边只需要一行文字，不能去抢浏览器。
			// 标题栏 chip 与正文会各自渲染一次本组件，靠 seat 区分的只有
			// `useTabInfo()` 返回的 `title` 标志。
			const isTitleChip = !!(tabInfo && tabInfo.title);
			if (isTitleChip) return e("span", null, "AI 浏览器");

			const note = { fontSize: "12px", lineHeight: 1.65, opacity: 0.72 };

			return e("div", {
				style: {
					position: "relative",
					width: "100%",
					height: "100%",
					// 面板高度是 auto 时给个下限，保证第一次量得到一块可用区域
					minHeight: "320px",
					boxSizing: "border-box",
				},
			},
				// 这块就是浏览器要盖住的区域。背景留空 —— 原生 WebView 会盖上来。
				e("div", {
					ref: slotRef,
					style: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0 },
				}),
				// ⚠️ 这里**刻意不放任何提示 / 按钮**：
				//   用户点开侧栏的「AI 浏览器」，要的就是浏览器本身，不是
				//   「再点一下才能用」的二级入口。打开动作在挂载时由
				//   `autoOpenRef` 那条路径自动触发（见下方 useEffect）。
				phase === "live" ? null : e("div", { style: Object.assign({}, note, {
					position: "absolute", left: 0, top: 0, right: 0,
					padding: "14px", opacity: 0.55,
				}) }, "正在打开浏览器…"),
			);
		}

		/**
		 * 注册侧栏项。
		 *
		 * @param {object} ctx - 客户端插件上下文。
		 */
		function install(ctx) {
			try {
				ctx.inject(["slots", "sidebarRightTabs"], (scope) => {
					scope.effect(() => scope.sidebarRightTabs.register({
						id: TAB_ID,
						kind: TAB_KIND,
						// `extension` = 来自产品外部的类型（band 最高）。它只在**自己独有的
						// kind** 上生效，不会顶掉官方的 `browser`（那是 iframe 方案）。
						priority: "extension",
						title: () => "AI 浏览器",
						guide: [{
							id: "open",
							// 排在最前：这是本 App 自己加的唯一一项，用户最容易找的位置。
							order: 1,
							title: () => "AI 浏览器",
							description: () => "用手机上的真浏览器上网，AI 可操作",
						}],
					}), "dsh-android-browser-pane: tab 类型");

					scope.effect(() => scope.slots.inject("sidebar.right.pane.tab", () => scope.slots.register({
						name: "sidebar.right.pane.tab",
						// ⚠️ key 必须等于 TAB_ID —— 座位按类型 id 找正文。
						key: TAB_ID,
					}, BrowserPane)), "dsh-android-browser-pane: tab 正文");
				});
			} catch (error) {
				// 注册失败只损失这一个侧栏项，不能连累别的插件。
				// 但**要打日志**：静默吞异常会让「侧栏里没有这一项」变成无从下手的哑谜。
				console.warn(
					"[dsh-android-browser-pane] 注册侧栏项失败:",
					error && (error.stack || error.message || error),
				);
			}
		}

		exports.apply = install;
		exports.inject = [];
		return module.exports;
	},
});
