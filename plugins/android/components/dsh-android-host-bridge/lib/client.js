window.__ModuleLoader__.load({
	id: "dsh-android-host-bridge",
	factory: (require) => {
		"use strict";
		// ══ 状态上报：把「AI 当前在干什么」实时推给原生通知栏 ══
		// 原生侧在 WebView 上挂了 `window.DSHAndroid.onStatus(json)`，收到后用**同一个
		// 通知 ID** 更新前台服务的通知（只 notify，不重启服务）。
		//
		// 为什么读 DOM 而不是官方 store：本插件 `inject: []`（无声明依赖），
		//   直接读 DOM 反而最稳 —— 官方重命名 store 字段时 DOM 契约还在。
		//
		// 三个信号的来源（0.2.0-rc.2 实测，均在官方 dsh-client-ui-chat / -conversation 里）：
		//   · **运行中** = 主按钮变成「停止生成」。官方 `primaryStops` 为真时把
		//     `uV2eYG_primary` 的 aria-label 换成 `t("input.stop")`
		//     （dsh-client-ui-conversation/lib/client.js:17407 / 17571）。
		//   · **在干什么** = 最后一个分组头 `.O_Ebla_title` 的文本。官方
		//     `ProcessGroupHeader` 在组未关闭时用 `message.stepProcess.<activity>`
		//     （「正在运行命令」），关闭后用 `processTitle()`（「执行了命令」）；
		//     两者都可能接 `message.turnProcess.separator`（" · "）+ `liveToolDetail`
		//     从工具参数里提出来的详情（如 `sleep 10`）。
		//   · **详细内容** = 最后一条助手正文（`[data-slot="conversation.chat.node"]`
		//     下的 `.hWmORq_root`），运行中它同时含正在流式的思考内容。
		//
		// ⚠️ 通知更新节流 1s：太频繁会让通知栏闪烁并白白耗电；内容没变则一个字节都不发。
		let mpStatusTimer = 0;
		let mpStatusPending = false;
		let mpLastStatusJson = "";
		// 转录通道（见 mpCollectTranscript）：节流比 status 宽，指纹不变则一个字节都不发。
		let mpTranscriptTimer = 0;
		let mpTranscriptPending = false;
		let mpLastTranscriptJson = "";

		function mpIsEngineRunning() {
			// 文案判据（引擎版本与界面语言在本 App 里都固定）。
			// 兜底：没有主按钮时当作空闲，宁可显示"空闲"也不要谎报在跑。
			const btn = document.querySelector(".uV2eYG_primary");
			if (!btn) return false;
			const label = btn.getAttribute("aria-label") || "";
			return label.indexOf("停止") >= 0 || /stop/i.test(label);
		}

		function mpCurrentActivity() {
			// 取**最后一个**分组头：它就是当前正在跑（或刚跑完）的那一组。
			const titles = document.querySelectorAll(".O_Ebla_title");
			if (!titles.length) return { activity: "", text: "" };
			const el = titles[titles.length - 1];
			const text = (el.textContent || "").replace(/\s+/g, " ").trim();
			const kind = el.getAttribute("data-process-activity") || "";
			return { activity: kind, text: text };
		}

		function mpTailAssistantText(limit) {
			// ⚠️ 必须限定 `data-chat-flow-kind="assistant-step"`。
			//   `.hWmORq_root` 是 AssistantMarkdown 的类，但**不能**只靠它选：
			//   同名的消息容器会把用户自己发的话也捞进来（真机实测用户消息是
			//   `data-chat-flow-kind="user"`，内含 Sixlwa_* 气泡）。
			//   限定 flow-kind 后取到的必然是助手输出（含 reasoning 与 response 两种
			//   group-part），运行中就是正在流式输出的那段。
			const nodes = document.querySelectorAll(
				'[data-chat-flow-kind="assistant-step"] .hWmORq_root'
			);
			for (let i = nodes.length - 1; i >= 0; i--) {
				const t = (nodes[i].textContent || "").replace(/\s+/g, " ").trim();
				if (t) return t.length > limit ? "…" + t.slice(t.length - limit) : t;
			}
			return "";
		}

		// 页面实际背景色（`#RRGGBB`），用来让原生侧把 WebView / 容器 / 窗口的底色对齐。
		//
		// 为什么必须上报：`adjustResize` 让窗口变矮是**逐帧动画**的，WebView 跟着变矮，
		// 但页面重排要等下一帧 —— 这个空档里新露出的那条区域还没有内容覆盖，
		// 显示的就是 WebView 自己的背景色。底色若与页面不一致，就会看到一条异色块
		// （深色底 + 浅色页面 = 用户实测的"点输入框时下半屏先黑一下"）。
		//
		// ⚠️ 不能写死颜色：官方主题可切换，写死只是把问题换个方向暴露。
		// ⚠️ 输出 `#RRGGBB` 而不是 computed style 的 `rgb(...)`：Android 的
		//   `Color.parseColor` 原生就吃这个格式，省掉在 Kotlin 里再写一套 CSS 颜色解析。
		//
		// ⚠️ **读主题 token 而不是读 `backgroundColor`**（这是"默认跟随主题色"这一条的
		//   实现）：`backgroundColor` 只是 token 的**最终投影**，在纯色主题下两者恰好
		//   相等，一旦主题改用背景图/渐变，投影与主题语义就分叉 —— 那时要把系统栏
		//   对齐到"主题的基底色"就只能靠 token。深色主题下 token 是 `#151517`、
		//   浅色是 `#fff`，与旧行为一致，所以这次替换对当前外观零改变。
		//   token 定义在 `body` 上（`html` 上取到空串），所以两个元素都要试。

		/** 主题基底色 token，按优先级排列。 */
		const MP_THEME_BG_TOKENS = ["--dsw-alias-bg-base"];

		// 把 CSS 颜色值规范化成 `#RRGGBB`；无法解析或完全透明时返回空串。
		//
		// 支持 token 实际会用到的几种写法：`#fff`（缩写）、`#ffffff`、`#ffffffff`
		// （带 alpha，取前 6 位）与 `rgb()/rgba()`。其余写法（百分比、颜色函数）
		// 一律返回空串，让调用方退回下一条路 —— 猜错比不猜更糟。
		function mpCssColorToHex(value) {
			const raw = String(value || "").trim();
			if (!raw) return "";
			let r;
			let g;
			let b;
			if (raw.startsWith("#")) {
				const h = raw.slice(1);
				const expand = (s) => (s.length === 1 ? s + s : s);
				if (h.length === 3 || h.length === 4) {
					r = parseInt(expand(h[0]), 16);
					g = parseInt(expand(h[1]), 16);
					b = parseInt(expand(h[2]), 16);
				} else if (h.length === 6 || h.length === 8) {
					r = parseInt(h.slice(0, 2), 16);
					g = parseInt(h.slice(2, 4), 16);
					b = parseInt(h.slice(4, 6), 16);
				} else {
					return "";
				}
			} else {
				const m = /^rgba?\(([^)]+)\)$/.exec(raw);
				if (!m) return "";
				const parts = m[1].split(",").map((s) => parseFloat(s));
				if (parts.length < 3 || parts.some((n) => !isFinite(n))) return "";
				// 完全透明 ⇒ 视为"没设背景"（rgba(0,0,0,0) 是常见表示）
				if (parts.length === 4 && parts[3] === 0) return "";
				[r, g, b] = parts;
			}
			if (![r, g, b].every((n) => isFinite(n))) return "";
			const hex = (n) => Math.max(0, Math.min(255, Math.round(n)))
				.toString(16).padStart(2, "0").toUpperCase();
			return "#" + hex(r) + hex(g) + hex(b);
		}

		function mpPageBackground() {
			// ① 主题 token 优先（见上方注释里为什么要换掉 backgroundColor）
			for (const el of [document.documentElement, document.body]) {
				if (!el) continue;
				const cs = getComputedStyle(el);
				for (const token of MP_THEME_BG_TOKENS) {
					const hex = mpCssColorToHex(cs.getPropertyValue(token));
					if (hex) return hex;
				}
			}
			// ② token 取不到（老内核/主题改结构）⇒ 退回实际算出来的背景色。
			// body 透明时往上找 html；都透明才退回白。
			for (const el of [document.body, document.documentElement]) {
				if (!el) continue;
				const hex = mpCssColorToHex(getComputedStyle(el).backgroundColor);
				if (hex) return hex;
			}
			return "#FFFFFF";
		}

		function mpCollectStatus() {
			const running = mpIsEngineRunning();
			const { activity, text } = mpCurrentActivity();
			let title = (document.title || "").replace(/\s*—\s*DeepSeek Harness\s*$/, "").trim();
			return {
				running: running,
				activity: activity,
				text: text,
				body: mpTailAssistantText(400),
				title: title,
				bg: mpPageBackground(),
			};
		}

		function mpReportStatus() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onStatus !== "function") return;
			let json;
			try { json = JSON.stringify(mpCollectStatus()); } catch (e) { return; }
			if (json === mpLastStatusJson) return;   // 无变化 ⇒ 不打扰通知栏
			mpLastStatusJson = json;
			try { bridge.onStatus(json); } catch (e) { /* 原生侧异常不影响页面 */ }
		}

		// 节流：流式输出时 DOM 变化极频繁，合并成最多 1 次/秒。
		function mpScheduleStatus() {
			if (mpStatusPending) return;
			mpStatusPending = true;
			mpStatusTimer = window.setTimeout(() => {
				mpStatusPending = false;
				mpReportStatus();
			}, 1000);
		}

		// ══ 悬浮窗展开态的转录通道 ══════════════════════════════════════════
		// 用途：原生侧悬浮球展开后要显示**完整**的对话 —— 用户消息、助手完整回复、
		//   每一次工具调用。通知栏那条 status 通道的 body 被刻意压到 400 字，
		//   体量差一个数量级，所以单独开一条（原生侧对应 ChatTranscript.kt）。
		//
		// ⚠️ 选择器一律用**属性**而不是类名：`data-chat-flow-kind`（官方 chat.js 的
		//   chatNode() 生成）与 `data-tool` / `data-state`（官方 tool.js 的 ToolRow
		//   写死）是稳定契约，而 `hWmORq_root` / `Sixlwa_bubble` 这类 CSS Module
		//   哈希类名每次官方发版都可能变。类名只用于"取文本时收窄"，取不到就退回
		//   整个节点 —— 退化的结果是"多带一点噪音"，而不是"内容全丢"。
		const MP_TRANSCRIPT_MAX_ITEMS = 80;
		const MP_TRANSCRIPT_MAX_CHARS = 2000;
		// ⚠️ 工具行的判据是 `[data-chat-flow-kind="tool-call"]`，**不是** `[data-tool]`。
		//   真机实测（CDP 读真实会话 DOM）：一次 bash 工具调用渲染出的是
		//   `<div data-chat-flow-kind="tool-call" data-turn-process-member="true"
		//     data-turn-process-hidden="true" hidden="until-found">`，
		//   而全文档 `[data-tool]` 命中数**恒为 0** —— `data-tool` 是
		//   `dsh-client-ui-tool` 的标记，本页面走的不是那套渲染器。
		//   只认 `data-tool` 的后果是「工具调用一条都采不到」，正是本通道上线后
		//   一直没见过的现象。
		// ⚠️ 工具行即使被折叠（`hidden="until-found"`）也照样采：`textContent`
		//   对 hidden 元素仍可读，而用户要的正是「展开面板能看到调了哪些工具」，
		//   恰恰是折叠态下最需要补的信息。
		const MP_TRANSCRIPT_SELECTOR = [
			'[data-chat-flow-kind="user"]',
			'[data-chat-flow-kind="steering"]',
			'[data-chat-flow-kind="assistant-step"]',
			'[data-chat-flow-kind="tool-call"]',
			'[data-chat-flow-kind="turn-error"]',
			"[data-tool]",
		].join(",");

		/** 压成单行并按 code point 截断（按 UTF-16 切会把 emoji 切成半个）。 */
		function mpTranscriptText(raw, limit) {
			const flat = String(raw === null || raw === undefined ? "" : raw)
				.replace(/\s+/g, " ").trim();
			const cps = Array.from(flat);
			return cps.length > limit ? cps.slice(0, limit - 1).join("") + "…" : flat;
		}

		/**
		 * 取节点的文本，**剔除元数据与按钮子树**。
		 *
		 * ⚠️ 为什么必须剔除（真机实测）：官方把时间戳与操作按钮放在同一条消息的尾部
		 *   （".xzv4MW_actions" 里一个 ".xzv4MW_timeStart" 加一个含 svg 的复制按钮），
		 *   直接取 "textContent" 会得到「只回复两个字：通了01:14」—— 时间戳粘在用户
		 *   原话后面，看起来像用户自己打的字。
		 *
		 * ⚠️ 判据用 **"[data-clock]" + "button"/"svg" 标签**，不用那个哈希类名：
		 *   "data-clock" 是官方写死的元数据标记（"start" = 时间戳、"end" = 时间戳 +
		 *   用量），实测页面里两处都有；而"消息尾部的操作按钮"永远是 button。
		 *   类名随官方发版会变，属性不会。取不到就退回原文（多带点噪音，不丢内容）。
		 */
		function mpTranscriptVisibleText(el) {
			try {
				const clone = el.cloneNode(true);
				const junk = clone.querySelectorAll("[data-clock], button, svg");
				for (let i = 0; i < junk.length; i++) junk[i].remove();
				return clone.textContent || "";
			} catch (e) {
				return el.textContent || "";
			}
		}

		/** 会话标题（与状态通道同源，两处共用免得正则漂移）。 */
		function mpConversationTitle() {
			return (document.title || "").replace(/\s*—\s*DeepSeek Harness\s*$/, "").trim();
		}

		function mpCollectTranscript() {
			const items = [];
			// 限定在消息视图内：轨迹页/设置页里也可能有 `[data-tool]` 之类的节点，
			// 不加限定会把别的界面的内容混进对话里。取不到容器就退回整个文档。
			const root = document.querySelector(".wSkVaW_viewArea") || document;
			let nodes = [];
			try { nodes = root.querySelectorAll(MP_TRANSCRIPT_SELECTOR); }
			catch (e) { nodes = []; }

			// 引擎是否在跑（与状态通道同源），以及最后一个助手节点 —— 只有它
			// 可能是"正在输出"的那一条。
			const engineRunning = mpIsEngineRunning();
			let lastAssistantStep = null;
			for (const el of nodes) {
				if (el.getAttribute("data-chat-flow-kind") === "assistant-step") lastAssistantStep = el;
			}

			for (const el of nodes) {
				const tool = el.getAttribute("data-tool");
				if (tool) {
					const state = el.getAttribute("data-state") || "";
					items.push({
						role: "tool",
						// 工具行的可见文本已经含标题（如「运行 bash」）与状态文案，
						// 直接用；为空才退回工具名，保证面板上至少看得见调了哪个工具。
						text: mpTranscriptText(mpTranscriptVisibleText(el), MP_TRANSCRIPT_MAX_CHARS) || tool,
						running: state === "running",
					});
					continue;
				}
				const kind = el.getAttribute("data-chat-flow-kind");

				// 工具调用（真机实测形状，见选择器处的注释）：
				//   `data-sample` = 工具名（`bash`），`data-state` = `ok`/`running`，
				//   可见文本 = 标题 + 摘要（「运行命令 Echo TOOL_OK to test output」）。
				// ⚠️ 两个属性都长在**内层** `[data-sample]` 节点上，不在
				//   `[data-chat-flow-kind]` 那个外层 div 上 —— 直接读 el 会全空。
				if (kind === "tool-call") {
					const inner = el.querySelector("[data-sample]") || el;
					const sample = inner.getAttribute("data-sample") || "";
					const state = inner.getAttribute("data-state") || "";
					const body = mpTranscriptText(mpTranscriptVisibleText(inner), MP_TRANSCRIPT_MAX_CHARS);
					items.push({
						role: "tool",
						text: body || sample || "工具调用",
						running: state === "running",
					});
					continue;
				}

				// assistant-step 的**思考**与**正文**是两个并列节点，靠
				// `data-chat-group-part` 区分（真机实测：`reasoning` / `response`）。
				// ⚠️ 不区分时两者都发成 `assistant`，面板上标签同为「AI」、样式也
				//   一模一样，用户看到的就是"思考与正文糊在一起、同时冒出来"。
				const part = kind === "assistant-step"
					? (el.getAttribute("data-chat-group-part") || "")
					: "";

				let body = el;
				if (kind === "assistant-step") {
					// 收窄到 AssistantMarkdown 的正文容器。官方 AssistantMarkdown 对
					// tool-call 块是直接 `break` 跳过的（不渲染进 body），所以这里取到
					// 的是纯正文，不会把工具标题混进助手回复里。
					body = el.querySelector(".hWmORq_root") || el;
				}
				const text = mpTranscriptText(mpTranscriptVisibleText(body), MP_TRANSCRIPT_MAX_CHARS);
				if (!text) continue;
				// 只有"引擎在跑"且"这是最后一条助手节点"才算正在输出 —— 后面的
				// 节点一出现，前一个自然就不再是运行中。
				const live = engineRunning && kind === "assistant-step" && el === lastAssistantStep;
				if (kind === "user") items.push({ role: "user", text: text, running: false });
				// steering = 用户在回合进行中追加的指令，按用户消息显示。
				else if (kind === "steering") items.push({ role: "user", text: text, running: false });
				else if (kind === "assistant-step") {
					items.push({
						role: part === "reasoning" ? "reasoning" : "assistant",
						text: text,
						running: live,
					});
				}
				else if (kind === "turn-error") items.push({ role: "error", text: text, running: false });
			}

			return {
				title: mpConversationTitle(),
				running: mpIsEngineRunning(),
				// 只留最后 MAX 条：面板要看的是当前进展，早期历史可以丢。
				items: items.length > MP_TRANSCRIPT_MAX_ITEMS
					? items.slice(items.length - MP_TRANSCRIPT_MAX_ITEMS)
					: items,
			};
		}

		function mpReportTranscript() {
			const bridge = window.DSHAndroid;
			if (!bridge || typeof bridge.onTranscript !== "function") return;
			let json;
			try { json = JSON.stringify(mpCollectTranscript()); } catch (e) { return; }
			// 指纹不变就一条都不发：这条通道体量比 status 大两个数量级，
			// 无脑重推会白白占用原生侧的 JSON 解析与面板重绘。
			if (json === mpLastTranscriptJson) return;
			mpLastTranscriptJson = json;
			try { bridge.onTranscript(json); } catch (e) { /* 原生侧异常不影响页面 */ }
		}

		// 节流 250ms：用户要求面板里"看得见流式输出"，所以这里必须跟着 DOM 的
		// 逐字变化走。原来的 1.5s 会让整段回复攒成两三坨跳出来 —— 观感就是
		// "没有流式、一次全出"。
		//
		// ⚠️ 成本可控的依据：`mpReportTranscript()` 带指纹去重，**内容没变一个
		//   字节都不发**，所以节流只决定"最多多快发一次"，不决定实际发送量。
		//   DOM 的 characterData 变化本身就已经是逐 token 的节奏。
		function mpScheduleTranscript() {
			if (mpTranscriptPending) return;
			mpTranscriptPending = true;
			mpTranscriptTimer = window.setTimeout(() => {
				mpTranscriptPending = false;
				mpReportTranscript();
			}, 250);
		}

		function mpInstallTranscriptBridge() {
			mpReportTranscript();
			const t = window.setInterval(mpReportTranscript, 5000);   // 兜底：DOM 静默变化时也能纠正
			return () => window.clearInterval(t);
		}

		function mpInstallStatusBridge() {
			mpReportStatus();
			const t = window.setInterval(mpReportStatus, 5000);   // 兜底：DOM 静默变化时也能纠正
			return () => window.clearInterval(t);
		}

		/**
		 * 让 "navigator.clipboard.writeText" 走原生剪贴板。
		 *
		 * ## 为什么必须打这个补丁
		 *
		 * "http://127.0.0.1" 属于**潜在可信来源**，所以 WebView 里
		 * "navigator.clipboard" **存在**、"isSecureContext" 也是 "true" —— 看起来一切正常。
		 * 但 WebView 的剪贴板写权限由原生 "WebChromeClient.onPermissionRequest" 授予，
		 * 本 App 没有实现它，于是**每次调用都抛**
		 * "NotAllowedError: Write permission denied."
		 *
		 * 这种"API 在、一用就炸"的形态最难查：官方的「复制登录链接」只把异常吞成一句
		 * 「复制失败」，没有任何线索。
		 *
		 * ## 做法
		 *
		 * 用 "Object.defineProperty" 覆盖 "navigator.clipboard.writeText"，内部转调
		 * 原生桥 "DSHAndroid.writeClipboard"。**只改 writeText**，"readText" 等一律不动
		 * （读剪贴板牵出隐私面，而需求只有复制）。
		 *
		 * ⚠️ 必须在 "apply()" 里调用："DSHAndroid" 桥在页面脚本执行时可能还没注入，
		 *   模块顶层直接取会拿到 "undefined"。
		 *
		 * @returns {Function} 恢复原剪贴板方法。
		 */
		function mpInstallClipboardBridge() {
			try {
				const bridge = window.DSHAndroid;
				if (!bridge || typeof bridge.writeClipboard !== "function") return () => {};
				const clip = navigator.clipboard;
				if (!clip) return () => {};
				const descriptor = Object.getOwnPropertyDescriptor(clip, "writeText");
				const original = clip.writeText;
				const patched = function writeText(text) {
					return new Promise((resolve, reject) => {
						let ok = false;
						try {
							ok = bridge.writeClipboard(String(text == null ? "" : text));
						} catch (e) {
							reject(e);
							return;
						}
						// 原生返回 false 时**必须 reject**：官方那段代码靠 catch 才会
						// 显示「复制失败」，回 resolve 会让用户以为复制成功但剪贴板是空的。
						if (ok) resolve();
						else reject(new Error("writeClipboard failed"));
					});
				};
				try {
					Object.defineProperty(clip, "writeText", {
						value: patched,
						configurable: true,
						writable: true,
					});
				} catch (e) {
					clip.writeText = patched;
				}
				return () => {
					if (clip.writeText !== patched) return;
					if (descriptor) Object.defineProperty(clip, "writeText", descriptor);
					else { delete clip.writeText; if (clip.writeText !== original) clip.writeText = original; }
				};
			} catch (e) {
				return () => {};
			}
		}


		function apply(ctx) {
			// 与布局原观察器保持相同的 300ms 合并，再由各通道按原节流发送。
			let mutationTimer = 0;
			const observer = new MutationObserver(() => {
				if (mutationTimer) return;
				mutationTimer = window.setTimeout(() => {
					mutationTimer = 0;
					mpScheduleStatus();
					mpScheduleTranscript();
				}, 300);
			});
			mpLastStatusJson = mpLastTranscriptJson = "";
			const disposeStatus = mpInstallStatusBridge();
			const disposeTranscript = mpInstallTranscriptBridge();
			const disposeClipboard = mpInstallClipboardBridge();
			observer.observe(document.body, { childList: true, subtree: true, characterData: true });
			ctx.effect(() => () => {
				observer.disconnect();
				window.clearTimeout(mutationTimer);
				window.clearTimeout(mpStatusTimer);
				window.clearTimeout(mpTranscriptTimer);
				mpStatusPending = mpTranscriptPending = false;
				disposeStatus();
				disposeTranscript();
				disposeClipboard();
			});
		}
		const exports = { apply, inject: [] };

		return exports;
	}
});
