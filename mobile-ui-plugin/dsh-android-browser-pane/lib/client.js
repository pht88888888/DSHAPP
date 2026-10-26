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
		 * 原生 → 前端的「把侧栏切到 AI 浏览器」请求。
		 *
		 * **必须与 `MainActivity.requestBrowserPane` 里 `dispatchEvent` 的事件名
		 * 逐字一致。**
		 */
		const REVEAL_EVENT = "dsh:browser-reveal";

		/**
		 * 原生 → 前端的「标注结果可取」通知。
		 *
		 * 必须与 `MainActivity.notifyAnnotateReady` 里的事件名逐字一致。
		 * 前端收到后调 `aiAnnotateResult()` 取回图片与提问。
		 */
		const ANNOTATE_EVENT = "dsh:browser-annotate";

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
		/**
		 * 取回原生刚导出的标注图，连同提问一起发到当前会话。
		 *
		 * ## 链路
		 *
		 * 1. 原生把合成图落到 `filesDir/browser-shots/mark-*.png`，然后派发
		 *    [ANNOTATE_EVENT]。
		 * 2. 这里调桥方法 `aiAnnotateResult()` 拿到 `{png, bytes, text}`。
		 * 3. 原生侧**没有 HTTP 文件服务**，所以不能给个 URL 让页面 fetch。
		 *    改成 `fetch("file://" + path)` —— WebView 的 file:// 访问与页面
		 *    同源策略允许读**沙盒自己的** filesDir（`allowFileAccess` 已开）。
		 *    读不到就退化成纯文本发送，并明确告诉用户图片没带上。
		 * 4. `conversation.createDrafts()` 把 File 登记成 draft 附件，
		 *    再 `sendSession()` 带附件发送。
		 *
		 * @param {object} scope - 注入后的插件作用域（要 `conversation`）。
		 */
		/**
		 * 把发送结果回报给原生，由原生 Toast 显示在屏幕上。
		 *
		 * 手机上看不到 `console.log`，所以**失败原因必须显示出来**，否则
		 * 用户只能看到「点了没反应」。这一步是为了不再靠反复下载安装包试错。
		 *
		 * @param {string} message - 给用户看的一句话。
		 */
		function report(message) {
			try {
				const bridge = window.DSHAndroid;
				if (bridge && typeof bridge.aiAnnotateReport === "function") bridge.aiAnnotateReport(message);
			} catch (error) {
				// 回报本身失败就算了，不能因此中断发送。
			}
		}

		async function sendAnnotate(scope) {
			console.log("[annotate] 进入 sendAnnotate");
			const bridge = window.DSHAndroid;
			if (!bridge) {
				report("标注失败：没有原生桥");
				return;
			}
			if (typeof bridge.aiAnnotateResult !== "function") {
				report("标注失败：桥上没有 aiAnnotateResult（版本不匹配）");
				return;
			}

			let payload;
			try {
				const raw = bridge.aiAnnotateResult();
				if (!raw) return;
				payload = JSON.parse(raw);
			} catch (error) {
				report(`标注失败：读不到结果 ${error && error.message}`);
				return;
			}

			const question = (payload.text || "").trim() || "请看我标注的地方，告诉我这是什么 / 有什么问题。";
			let conversation;
			try {
				conversation = typeof scope.get === "function" ? scope.get("conversation") : void 0;
			} catch (error) {
				console.warn("[annotate] scope.get(conversation) 抛错:", error && error.message);
			}
			const sessionId = sessionOf(scope);
			console.log("[annotate] conversation=", conversation !== void 0, "sessionId=", sessionId, "text=", question);

			// ── base64 → File ─────────────────────────────────────────
			//
			// 原生给的是 base64 字符串（不是路径）：主 WebView 的
			// `allowFileAccess=false`，`fetch("file://…")` 必然失败。
			let file = null;
			try {
				const bin = atob(payload.png || "");
				const bytes = new Uint8Array(bin.length);
				for (let i = 0; i < bin.length; i += 1) bytes[i] = bin.charCodeAt(i);
				if (bytes.length > 0) {
					file = new File([bytes], `annotate-${Date.now()}.png`, { type: "image/png" });
				}
			} catch (error) {
				console.warn("[dsh-android-browser-pane] 解码标注图失败，将只发文字:", error);
			}

			// ── 发送 ──────────────────────────────────────────────────
			try {
				if (conversation === void 0) throw new Error("拿不到 conversation 服务");
				if (sessionId === void 0) throw new Error("拿不到当前会话 id");
				if (file === null) {
					// 图片没读到：至少把文字发出去，不让用户白画一场。
					// ⚠️ `conversation.send` 走的是 `scopedSession`，在**根作用域**
					// 会抛「requires a session scope」，所以这里也用 sendSession
					// 的空附件形式，保证行为一致。
					const plain = await conversation.sendSession(session, question, [], "queue");
					report(
						plain && plain.kind === "error"
							? "发送失败：被会话拒绝"
							: "已发送文字（图片读取失败）",
					);
					return;
				}
				// ⚠️ `sendSession` 的第一个参数要的是 **Session 对象**，不是
				// sessionId 字符串。它内部会调 `session.beginSubmission(...)`，
				// 传字符串必然抛「beginSubmission is not a function」——而这个
				// 异常又被下面的 catch 吞掉，表现就是「点发送完全没反应」。
				const session = sessionObjectOf(scope, sessionId);
				if (session === void 0) throw new Error(`找不到 session 对象: ${sessionId}`);
				const drafts = conversation.createDrafts(sessionId, [file]);
				const ids = drafts.map((d) => d.id);
				// ⚠️ `sendSession` **失败时不抛异常**，而是返回 `{kind:"error"}`。
				// 只 `await` 不检查返回值 = 静默失败，用户看到的就是「点了没反应」。
				const outcome = await conversation.sendSession(session, question, ids, "queue");
				if (outcome && outcome.kind === "error") {
					report("发送失败：被会话拒绝（可能正在生成回复）");
					return;
				}
				report("已发送标注");
			} catch (error) {
				console.warn("[dsh-android-browser-pane] 发送标注失败:", error && (error.stack || error.message || error));
				report(`标注发送失败：${error && (error.message || error)}`);
			}
		}

		/**
		 * 当前屏上的 Session id。
		 *
		 * 侧栏面板知道自己属于哪个会话，但这里拿的是**根作用域**，所以走
		 * `sidebarRight.mounted` 快照 —— 它给的就是屏上那个 Session。
		 *
		 * @param {object} scope - 插件作用域。
		 * @returns {string|undefined} Session id。
		 */
		/**
		 * 按 id 取到**活的 Session 对象**（`conversation.sendSession` 要的是它）。
		 *
		 * 客户端 `sessions` 服务的 `get(id)` 直接返回 session 实例；官方
		 * `conversation` 内部也是 `sessions.binding(id).session` 这么拿的。
		 *
		 * @param {object} scope - 插件作用域。
		 * @param {string} sessionId - 目标会话 id。
		 * @returns {object|undefined} Session 对象。
		 */
		function sessionObjectOf(scope, sessionId) {
			if (sessionId === void 0) return void 0;
			try {
				const sessions = typeof scope.get === "function" ? scope.get("sessions") : void 0;
				if (sessions === void 0) {
					console.warn("[annotate] 取不到 sessions 服务");
					return void 0;
				}
				// ⚠️ 客户端 `sessions` 服务是 **ClientSessions**，它**没有 `get()`**。
				// `get(sessionId)` 是内部 `SessionManager` 的方法，不可达。
				// 公开且正确的是 `binding(id)`，返回 `{ sessionId, session, ... }`；
				// 官方 `conversation` 内部也正好是 `sessions.binding(id).session`。
				if (typeof sessions.binding === "function") {
					const bound = sessions.binding(sessionId);
					if (bound !== void 0 && bound.session !== void 0) return bound.session;
					console.warn("[annotate] binding 无 session:", sessionId);
				} else {
					console.warn("[annotate] sessions 上没有 binding()");
				}
			} catch (error) {
				console.warn("[annotate] 取 session 对象失败:", error && error.message);
			}
			return void 0;
		}

		function sessionOf(scope) {
			try {
				const mounted = scope.sidebarRight && scope.sidebarRight.mounted;
				if (mounted && typeof mounted.getSnapshot === "function") return mounted.getSnapshot();
			} catch (error) {
				// 忽略：调用方会走「拿不到会话」的分支。
			}
			return void 0;
		}

		/**
		 * 找出当前面板里那个「开始」（guide）标签，用来被替换掉。
		 *
		 * 侧栏第一次打开时 `defaultSeed()` 会先放一个 guide 标签。直接
		 * `openTab` 是**在它旁边再加一个**，于是顶部出现「开始」+「AI 浏览器」
		 * 两个标签，并且激活的是 guide —— 用户看到的是一整页「开始」引导，
		 * 而不是我们刚打开的网页。
		 *
		 * @param {object} scope - 注入后的插件作用域。
		 * @returns {object} `{ replaceTab }` 或空对象（没有可替换的 guide）。
		 */
		/**
		 * 当前活动标签 id —— **仅当它就是我们这个浏览器标签时**才返回。
		 *
		 * 用来让「展开侧栏」保持幂等：用户已经停在浏览器上时，再 `openTab`
		 * 会重复建实例、把已登录的页面顶掉。
		 *
		 * ⚠️ 只用公开 API（`active()` 返回 TabRecord），不去翻 `layout` 的
		 * 内部字段 —— 那些字段名在不同版本里会变，猜错了就是静默失效。
		 */
		function currentTabId(scope) {
			try {
				if (typeof scope.sidebarRight.active !== "function") return void 0;
				const rec = scope.sidebarRight.active();
				if (rec === void 0 || rec === null) return void 0;
				return rec.kind === TAB_KIND ? rec.id : void 0;
			} catch (error) {
				return void 0;
			}
		}

		function guideReplacement(scope) {
			try {
				// `screen()` 是**无参**的：它自己从 `mounted` 快照里取当前屏上的
				// Session，返回 `{ sessionId, surface }`。侧栏还没挂载过时返回
				// undefined —— 那就没有 guide 可替换。
				const screen = typeof scope.sidebarRight.screen === "function" ? scope.sidebarRight.screen() : void 0;
				const tabs = screen && screen.surface && screen.surface.layout && screen.surface.layout.tabs;
				if (!tabs) return {};
				for (const id of Object.keys(tabs)) {
					if (tabs[id] && tabs[id].kind === "guide") return { replaceTab: id };
				}
				return {};
			} catch (error) {
				// 拿不到布局就退化成「加一个标签」，功能不因此中断。
				return {};
			}
		}

		/**
		 * 面板挂载后补一次「顶掉 guide」。
		 *
		 * 首次展开侧栏时 `screen()` 尚未就绪，`guideReplacement()` 只能返回空，
		 * 于是侧栏会停在「开始」页上。这里用有限次 rAF 轮询，等布局出现后把
		 * guide 换成 AI 浏览器。
		 *
		 * ⚠️ **次数必须有上限**：用户可能已经手动切到别的标签，无限轮询会把
		 *   他的选择一直抢回来。只重试 20 帧（约 330ms），够挂载用。
		 *
		 * @param {object} scope - 注入后的插件作用域。
		 */
		function dropGuideAfterMount(scope) {
			let tries = 0;
			const step = () => {
				tries += 1;
				try {
					const guide = guideReplacement(scope);
					if (guide.replaceTab !== void 0) {
						scope.sidebarRight.openTab(TAB_KIND, {
							revealIfOpened: true,
							replaceTab: guide.replaceTab,
						});
						return;
					}
					// 已经找不到 guide（= 已被替换，或用户自己切走了）就收手。
					const screen = typeof scope.sidebarRight.screen === "function" ? scope.sidebarRight.screen() : void 0;
					const tabs = screen && screen.surface && screen.surface.layout && screen.surface.layout.tabs;
					if (tabs && Object.keys(tabs).length > 0) {
						const hasOurs = Object.keys(tabs).some((id) => tabs[id] && tabs[id].kind === TAB_KIND);
						if (hasOurs) return;
					}
				} catch (error) {
					return;
				}
				if (tries < 20) window.requestAnimationFrame(step);
			};
			window.requestAnimationFrame(step);
		}

		function install(ctx) {
			try {
				ctx.inject(["slots", "sidebarRightTabs", "sidebarRight", "conversation"], (scope) => {
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

					// ── 原生请求「把侧栏展开到 AI 浏览器」──────────────────
					//
					// 用户在**对话界面**里点了一个链接（或点登录），原生要把内置
					// 浏览器亮出来。但原生自己只会「全屏铺满」—— 那时侧栏没开，
					// 屏幕上只有一整块网页，**没有任何侧栏 UI**，用户连「这个面板
					// 在哪、怎么收起来」都看不到，只能点 ✕ 彻底关掉，一关就丢页面。
					//
					// 所以点链接时先让这里把侧栏切到 AI 浏览器 tab：面板一挂载，
					// `BrowserPane` 就会把矩形上报给原生，浏览器自然落进面板里，
					// 顶部/侧边该有的 UI 也就都在了。
					//
					// ⚠️ 用 `revealIfOpened: true`：tab 已经开着就直接切过去，
					//   不要重复开第二个。
					scope.effect(() => {
						const onReveal = () => {
							try {
								// ⚠️ **幂等保护**：侧栏可能已经停在浏览器标签上了。
								// 这时再 `openTab` 会重复建实例、把已登录的页面顶掉。
								// 只要它当前就在屏幕上，改为「聚焦 + 展开」即可。
								const shown = currentTabId(scope);
								if (shown !== void 0) {
									// 已经停在浏览器上：只需要确保侧栏是展开的，
									// 并把它聚焦到前台，绝不能再开一个标签。
									try {
										if (typeof scope.sidebarRight.isExpanded === "function" && !scope.sidebarRight.isExpanded()) {
											scope.sidebarRight.toggleExpanded();
										}
									} catch (ignored) {}
									try {
										scope.sidebarRight.focus(shown);
									} catch (ignored) {}
									return;
								}
								// ⚠️ **必须顶掉 guide tab**，否则侧栏会变成
								//    「开始」+「AI 浏览器」两个标签，而且停在 guide 页上。
								//    官方自己点侧栏项时就是这么做的（`openTabFromTarget`
								//    里 `tab?.kind === "guide" ? { replaceTab: tab.id } : {}`），
								//    我们绕过它直接调 openTab，就得自己补这一步。
								scope.sidebarRight.openTab(TAB_KIND, {
									revealIfOpened: true,
									...guideReplacement(scope),
								});
								// ⚠️ 侧栏**第一次**打开时，`screen()` 还是空的 —— 上面
								//    那次替换找不到 guide，于是留下「开始」+「AI 浏览器」
								//    两个标签，且激活的是「开始」。等面板挂载完成后再补
								//    一次替换。重试几次就够：面板挂载是同步布局后的下一帧。
								dropGuideAfterMount(scope);
							} catch (error) {
								// 侧栏打不开不能连累浏览器本身：原生那边已经全屏显示了，
								// 用户至少还能用（只是没有面板 UI）。
								console.warn(
									"[dsh-android-browser-pane] 展开侧栏失败:",
									error && (error.stack || error.message || error),
								);
							}
						};
						window.addEventListener(REVEAL_EVENT, onReveal);
						return () => window.removeEventListener(REVEAL_EVENT, onReveal);
					}, "dsh-android-browser-pane: 展开侧栏");

					// ── 标注发送 ──────────────────────────────────────────
					// 用户在画布上圈完、打完字点了发送，原生把图导出并通知到这里。
					scope.effect(() => {
						const onAnnotate = () => {
							// 不 await：事件监听里不能阻塞派发方（原生 evaluateJavascript）。
							sendAnnotate(scope);
						};
						window.addEventListener(ANNOTATE_EVENT, onAnnotate);
						return () => window.removeEventListener(ANNOTATE_EVENT, onAnnotate);
					}, "dsh-android-browser-pane: 发送标注");
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
