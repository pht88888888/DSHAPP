// dsh-agnes-multimodal — browser half (client plugin bundle).
//
// Loaded by dsh-client-modules at /plugins/dsh-agnes-multimodal/client.js and
// executed through the vendored cordis Loader's lazy-CJS module table
// (window.__ModuleLoader__.load). The factory body is plain CJS with require()
// resolved against the shell's module table. The `id` below MUST equal the
// package name, or the shell reports "loaded without registering".
//
// This single bundle carries the whole UI of the fused plugin:
//
//   1. 设置 → 多模态配置   (settings.section)
//      A whole top-level settings page of its own, NOT a tab nested inside the
//      Plugins section. `settings.section` owns one full page per sidebar entry;
//      `settings.plugins.tab` would only add a tab under 设置 → 插件.
//      The account pool: each row is one Agnes AI account (region + API key).
//      Every account is an independent rate-limit slot, so N accounts raise the
//      video rate from 1/min to N/min and widen the image batch capacity.
//      Persisted through the fenced host API to $DSH_HOME/ag-multimodal.json.
//
//   2. generate_image 卡片   (tool.call.toolview key=generate_image)
//      A faithful copy of the shell's own MessageImage geometry: a lone image
//      is fitted into a 240px box at its clamped aspect ratio and never
//      upscaled, a batch renders as 64px square tiles, and either opens the
//      original. Images are resolved through the shell's session-authorized
//      `loadImage` prop, from the durable `image` blocks in the tool result
//      content — the same path the shipped read_image view uses. This view
//      never mints or revokes attachment URLs itself.
//
//   3. generate_video 卡片   (tool.call.toolview key=generate_video)
//      Playable MP4 cards in a grid, each with a numbered label and a two-line
//      prompt excerpt; a single video is capped at 240px.
//
//   4. 折叠区外的媒体投递     (conversation.chat.turnTail)
//      The shell folds a completed Turn's process region shut, so the cards
//      above end up one click away once the reply settles. The always-open
//      `conversation.chat.turnTail` list — where ui-deliverables and ui-plan
//      publish their closing artifacts — gets the Turn's generated images and
//      videos again, so they land directly under the reply.
//
// Media never reaches the model: generated images ride along as `image` blocks
// flagged `offloaded`, which the request projection replaces with placeholder
// text, so the model context stays text-only while the UI still renders them.
// Videos are streamed straight from the returned MP4 URL.

window.__ModuleLoader__.load({
	id: "dsh-agnes-multimodal",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
		var React = require("react");
		var h = React.createElement;

		// `sessions` and `uiConversation` are what the turn-tail seat needs in
		// order to read a Turn's tool results and resolve its images. Both are
		// root client services — the shipped ui-plan and ui-deliverables declare
		// the same pair — and a service that is not listed here is simply absent
		// from `ctx`, which would leave the seat silently empty.
		exports.inject = ["slots", "sessions", "uiConversation"];
		exports.apply = function apply(ctx) {
			// ── shared constants ───────────────────────────────────────────
			var API_URL = "/agnes/api";
			var CN_ENDPOINT = "https://api.agnes-ai.cn/v1";
			var INTL_ENDPOINT = "https://apihub.agnes-ai.cn/v1";
			var REGIONS = [
				{ label: "国内站", endpoint: CN_ENDPOINT },
				{ label: "国际站", endpoint: INTL_ENDPOINT }
			];
			// Longest edge of a single media card, in px.
			var SINGLE_MAX = 240;
			// Settings-sidebar entry label. Also the text used to locate the nav
			// cell when painting the plugin glyph, because the shell's navIcon
			// map is keyed by section id and offers no icon field to register.
			var NAV_LABEL = "多模态配置";
			// Locale namespace of the shipped conversation UI; tool rows register
			// against it so their labels resolve through the same catalogue.
			var CONVERSATION_NS = "conversation";

			// ── stylesheet (hover states and grid behaviour need real CSS) ──
			ctx.effect(function() {
				var style = document.createElement("style");
				style.textContent = `
/* ── config panel ───────────────────────────────────────────────────── */
/* Full settings page (settings.section), so it owns its own page header. */
.am-root{display:flex;flex-direction:column;gap:16px;padding:4px 2px 28px;font-size:13px;color:var(--dsw-alias-label-primary,inherit)}
.am-page-head{display:flex;flex-direction:column;gap:5px}
.am-page-title-row{display:flex;align-items:center;gap:8px}
.am-page-mark{flex:none;width:16px;height:16px;background-color:currentColor;
  -webkit-mask:var(--am-nav-mark) center/16px 16px no-repeat;
  mask:var(--am-nav-mark) center/16px 16px no-repeat}
.am-page-title{margin:0;font-size:16px;font-weight:600;color:var(--dsw-alias-label-primary,inherit)}
.am-page-desc{margin:0;font-size:12px;line-height:1.65;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-hero{border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.28));border-radius:12px;padding:14px 16px;background:rgba(128,128,128,.06);display:flex;flex-direction:column;gap:12px}
.am-hero-head{display:flex;align-items:center;gap:9px}
.am-dot{width:8px;height:8px;border-radius:50%;background:var(--dsw-alias-state-success-primary,#2ea043);flex:none}
.am-dot.off{background:rgba(128,128,128,.6)}
.am-title{font-size:14px;font-weight:600;color:var(--dsw-alias-label-primary,inherit)}
.am-desc{font-size:12px;line-height:1.65;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-stats{display:flex;gap:9px;flex-wrap:wrap}
.am-stat{flex:1;min-width:92px;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.22));border-radius:10px;padding:9px 12px;background:var(--dsw-alias-bg-base,transparent);display:flex;flex-direction:column;gap:1px}
.am-stat-num{font-size:18px;font-weight:600;line-height:1.15;color:var(--dsw-alias-label-primary,inherit);font-variant-numeric:tabular-nums}
.am-stat-label{font-size:11px;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.22));border-radius:10px;padding:10px 12px;background:var(--dsw-alias-bg-base,transparent)}
.am-idx{width:18px;font-size:12px;flex:none;font-variant-numeric:tabular-nums;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-seg{display:flex;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.35));border-radius:8px;overflow:hidden;flex:none}
.am-seg-btn{padding:7px 11px;font-size:12px;font-family:inherit;border:none;background:transparent;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9));cursor:pointer}
.am-seg-btn.on{background:var(--dsw-alias-brand-primary,#4f46e5);color:#fff}
.am-input{flex:1;min-width:168px;padding:8px 11px;border-radius:8px;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.35));background:var(--dsw-alias-bg-base,transparent);color:var(--dsw-alias-label-primary,inherit);font-size:13px;font-family:ui-monospace,Menlo,Consolas,monospace}
.am-input:focus{outline:none;border-color:var(--dsw-alias-brand-primary,#4f46e5)}
.am-btn{padding:7px 12px;border-radius:8px;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.35));background:transparent;color:var(--dsw-alias-label-primary,inherit);font-size:12px;font-family:inherit;cursor:pointer;white-space:nowrap}
.am-btn:hover:not(:disabled){background:rgba(128,128,128,.12)}
.am-btn:disabled{opacity:.45;cursor:default}
.am-btn-primary{border:none;background:var(--dsw-alias-brand-primary,#4f46e5);color:#fff;padding:8px 18px;font-size:13px}
.am-btn-primary:hover:not(:disabled){background:var(--dsw-alias-brand-primary,#4f46e5);opacity:.9}
.am-btn-dashed{border-style:dashed;align-self:flex-start}
.am-hint{font-size:12px;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-ok{color:var(--dsw-alias-state-success-primary,#2ea043)}
.am-bad{color:rgba(255,80,80,.9)}
.am-warn{color:rgba(255,160,60,.95)}
.am-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}

/* 内置号那一行：视觉上就与用户行不同（灰底 + 虚线边），一眼能看出是「系统给的、
   动不了」的那条。key 输入框设为只读，且没有地区切换与移除按钮。 */
.am-row-builtin{border-style:dashed;background:rgba(128,128,128,.07)}
.am-row-builtin .am-input{color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9));cursor:not-allowed}
.am-badge{font-size:11px;padding:2px 8px;border-radius:999px;background:var(--dsw-alias-brand-primary,#4f46e5);color:#fff;flex:none;line-height:16px}
.am-region-fixed{font-size:12px;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9));flex:none}

/* ── media cards (shared shell) ─────────────────────────────────────── */
.am-media{display:flex;flex-direction:column;gap:9px}
.am-media-head{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-chip{font-size:11px;padding:2px 7px;border-radius:999px;background:rgba(128,128,128,.14);color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9));font-variant-numeric:tabular-nums}
.am-loading{font-size:13px;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-err{font-size:12px;color:rgba(255,80,80,.9)}

/* ── image card ─────────────────────────────────────────────────────────
   A faithful copy of the shell's own MessageImage geometry, so a generated
   image sits in the transcript exactly like a pasted one: a lone image is
   fitted into a 240px box at its clamped aspect ratio, a batch renders as
   64px square tiles, and clicking either opens the original. */
.am-gallery{display:flex;flex-wrap:wrap;gap:10px;max-width:100%}
.am-gallery[data-align=start]{justify-content:flex-start;align-self:flex-start}
.am-mimg-frame{border:.5px solid var(--dsw-alias-border-l2-darkmode-thin,rgba(128,128,128,.35));border-radius:var(--dsw-radius-xl,12px);background:var(--dsw-alias-interactive-bg-hover,rgba(128,128,128,.1));cursor:zoom-in;flex:none;place-items:center;min-width:44px;min-height:44px;padding:0;display:grid;overflow:hidden}
.am-mimg-frame[data-variant=tile]{width:64px;min-width:64px;height:64px;min-height:64px}
.am-mimg-frame img{object-fit:cover;width:100%;height:100%;display:block}
.am-mimg-loading{color:var(--dsw-alias-label-tertiary,rgba(128,128,128,.75));font-size:12px;line-height:18px}
.am-mimg-error{border:.5px solid var(--dsw-alias-border-l2-darkmode-thin,rgba(128,128,128,.35));border-radius:var(--dsw-radius-md,8px);background:var(--dsw-alias-interactive-bg-hover-danger,rgba(255,80,80,.12));color:var(--dsw-alias-label-tertiary,rgba(128,128,128,.75));font-size:12px;line-height:18px;cursor:pointer;max-width:240px;padding:10px 12px;text-align:left}
.am-mimg-error[data-variant=tile]{border-radius:var(--dsw-radius-xl,12px);width:64px;height:64px;padding:4px;overflow:hidden}
.am-lb{position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.82);display:grid;place-items:center;cursor:zoom-out}
.am-lb-img{max-width:92vw;max-height:92vh;object-fit:contain;display:block;cursor:default}

/* ── turn-tail delivery ────────────────────────────────────────────────
   The same gallery, repeated below the reply once the Turn's process region
   folds shut. Deliberately chrome-less, like a pasted image in a user bubble:
   the picture itself is the whole message. */
.am-tail{display:flex;flex-direction:column;gap:8px;min-width:0;padding:2px 0}

/* ── video card ─────────────────────────────────────────────────────── */
.am-vgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px;width:100%}
.am-vitem{border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.28));border-radius:10px;overflow:hidden;background:rgba(128,128,128,.06);display:flex;flex-direction:column}
.am-vitem:hover{border-color:var(--dsw-alias-border-l1,rgba(128,128,128,.5))}
.am-vframe{line-height:0;background:#000}
.am-vframe video{display:block;width:100%;height:auto;max-height:260px}
.am-vmeta{display:flex;flex-direction:column;gap:3px;padding:8px 10px}
.am-vidx{font-size:11px;font-variant-numeric:tabular-nums;color:var(--dsw-alias-label-secondary,rgba(128,128,128,.9))}
.am-vprompt{font-size:12px;line-height:1.5;color:var(--dsw-alias-label-primary,inherit);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.am-vsingle{max-width:240px}

/* ── settings-sidebar glyph ─────────────────────────────────────────── */
/* The shell paints each settings nav glyph from a map keyed by section id
   (navIcon in SettingsRoot) and slot registration exposes no icon field, so an
   unknown plugin id silently falls back to the generic gear. Paint this
   plugin's own mark instead: hide that fallback svg and draw the glyph with a
   currentColor mask, so it follows the theme in light and dark alike.
   The cell is tagged with data-am-nav by tagNavGlyph() below — React does not
   manage data-* attributes, so a re-render cannot drop the tag. */
[data-shortcut-modal="settings"]{
  --am-nav-mark:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Crect x='6' y='1.6' width='8.4' height='8.4' rx='1.8' fill='none' stroke='%23000' stroke-width='1.4' opacity='.45'/%3E%3Crect x='1.6' y='6' width='8.4' height='8.4' rx='1.8' fill='none' stroke='%23000' stroke-width='1.4'/%3E%3Cpath d='M4.5 8.5L7.4 10.2L4.5 11.9Z' fill='%23000'/%3E%3C/svg%3E")
}
[data-shortcut-modal="settings"] nav button[data-am-nav] > svg:first-child{display:none}
[data-shortcut-modal="settings"] nav button[data-am-nav]::before{
  content:"";flex:none;width:16px;height:16px;background-color:currentColor;
  -webkit-mask:var(--am-nav-mark) center/16px 16px no-repeat;
  mask:var(--am-nav-mark) center/16px 16px no-repeat;
}
`;
				document.head.appendChild(style);
				return function() { document.head.removeChild(style); };
			});

			// Paint the plugin mark on the settings-nav cell. The observer is
			// scoped to the settings modal: it mounts when the modal opens and
			// unmounts with it, which is also when the cell appears.
			ctx.effect(function() {
				var observer = new MutationObserver(tagNavGlyph);
				observer.observe(document.body, { childList: true, subtree: true });
				tagNavGlyph();
				return function() { observer.disconnect(); };
			});

			// ── small helpers ─────────────────────────────────────────────

			/**
			 * Tag this plugin's settings-nav cell so the stylesheet can swap the
			 * shell's fallback gear for the plugin mark.
			 *
			 * The shell picks the glyph with `navIcon(sectionId)`, a hard-coded
			 * map of shipped ids, and `slots.register` has no icon field — so an
			 * unknown id always gets the generic gear. The cell is found by its
			 * label text and tagged with `data-am-nav`; React renders a fixed set
			 * of attributes and never removes data-* it did not set, so the tag
			 * survives re-renders. Runs on a MutationObserver because the
			 * settings modal mounts and unmounts with the shell.
			 */
			function tagNavGlyph() {
				var nav = document.querySelector('[data-shortcut-modal="settings"] nav');
				if (nav === null) return;
				var cells = nav.querySelectorAll("button");
				for (var i = 0; i < cells.length; i++) {
					var label = cells[i].textContent;
					if (label === null || label.trim() !== NAV_LABEL) continue;
					if (cells[i].getAttribute("data-am-nav") !== "") cells[i].setAttribute("data-am-nav", "");
				}
			}

			/** POST one command to the fenced config API. */
			function callApi(payload) {
				return fetch(API_URL, {
					method: "POST",
					headers: { "content-type": "application/json" },
					body: JSON.stringify(payload)
				}).then(function(r) { return r.json(); });
			}

			/** The REGIONS entry matching an endpoint, or null for a custom one. */
			function regionFor(endpoint) {
				for (var i = 0; i < REGIONS.length; i++) {
					if (REGIONS[i].endpoint === endpoint) return REGIONS[i];
				}
				return null;
			}

			/** A one-line excerpt of a prompt, for the video meta row. */
			function excerpt(text, max) {
				var s = String(text === null || text === undefined ? "" : text).replace(/\s+/g, " ").trim();
				return s.length > max ? s.slice(0, max - 1) + "…" : s;
			}

			// ── 1. config panel ───────────────────────────────────────────

			function ConfigPanel() {
				var accountsState = React.useState([]);
				var accounts = accountsState[0];
				var setAccounts = accountsState[1];
				// 内置号单独一份状态：它只读，不进 `accounts`（用户行），
				// 所以「添加/移除/保存」都碰不到它。为 null 时表示服务端没给。
				var builtinState = React.useState(null);
				var builtin = builtinState[0];
				var setBuiltin = builtinState[1];
				var phaseState = React.useState("loading");
				var phase = phaseState[0];
				var setPhase = phaseState[1];
				var saveState = React.useState({ phase: "idle" });
				var saveSt = saveState[0];
				var setSave = saveState[1];
				var testsState = React.useState({});
				var tests = testsState[0];
				var setTests = testsState[1];
				var revealState = React.useState({});
				var reveal = revealState[0];
				var setReveal = revealState[1];

				React.useEffect(function() {
					var cancelled = false;
					callApi({ method: "get" }).then(function(res) {
						if (cancelled) return;
						var list = [];
						if (res && res.ok === true && res.value) {
							if (Array.isArray(res.value.accounts)) {
								list = res.value.accounts.map(function(a) {
									return { endpoint: a.endpoint, key: a.key, builtin: a.builtin === true };
								});
							} else if (typeof res.value.agnesApiKey === "string" && res.value.agnesApiKey.length > 0) {
								// backward compat: a legacy single key becomes one CN account
								list = [{ endpoint: CN_ENDPOINT, key: res.value.agnesApiKey }];
							}
						} else {
							setPhase("error");
						}
						// 内置行单独拎出来：它是只读的，且必须恒在最后一行。
						// `accounts` 只存用户自己加的行，所以「添加账号」天然追加在
						// 内置行之前，保存时也只把用户行发回去（服务端会补回内置行）。
						var builtinRow = null;
						var userRows = [];
						list.forEach(function(row) {
							if (row.builtin === true) builtinRow = row;
							else userRows.push(row);
						});
						setBuiltin(builtinRow);
						setAccounts(userRows);
						setPhase(function(prev) { return prev === "error" ? prev : "ready"; });
					}).catch(function() {
						if (cancelled) return;
						setAccounts([]);
						setPhase("error");
					});
					return function() { cancelled = true; };
				}, []);

				/** Replace one row and drop its stale test result. */
				function patchRow(idx, patch) {
					setAccounts(function(prev) {
						return prev.map(function(row, i) {
							return i === idx ? Object.assign({}, row, patch) : row;
						});
					});
					setTests(function(prev) {
						if (!prev[idx]) return prev;
						var next = Object.assign({}, prev);
						delete next[idx];
						return next;
					});
					setSave({ phase: "idle" });
				}

				function addRow() {
					setAccounts(function(prev) { return prev.concat([{ endpoint: CN_ENDPOINT, key: "" }]); });
					setSave({ phase: "idle" });
				}

				function removeRow(idx) {
					// 内置行不在 `accounts` 里，所以这里天然删不到它。
					setAccounts(function(prev) {
						return prev.filter(function(_, i) { return i !== idx; });
					});
					setTests(function(prev) {
						var next = Object.assign({}, prev);
						delete next[idx];
						return next;
					});
					setSave({ phase: "idle" });
				}

				function testRow(idx) {
					var row = accounts[idx];
					if (!row || row.key.trim().length === 0) return;
					setTests(function(prev) {
						var next = Object.assign({}, prev);
						next[idx] = { phase: "testing" };
						return next;
					});
					callApi({ method: "test", payload: { endpoint: row.endpoint, key: row.key.trim() } }).then(function(res) {
						setTests(function(prev) {
							var next = Object.assign({}, prev);
							if (res && res.ok === true) next[idx] = { phase: "done", ok: true };
							else if (res && res.reason === "invalid") next[idx] = { phase: "done", ok: false, reason: "invalid" };
							else next[idx] = { phase: "done", ok: false, reason: "network" };
							return next;
						});
					}).catch(function() {
						setTests(function(prev) {
							var next = Object.assign({}, prev);
							next[idx] = { phase: "done", ok: false, reason: "error" };
							return next;
						});
					});
				}

				function save() {
					setSave({ phase: "saving" });
					callApi({ method: "set", patch: { accounts: accounts } }).then(function(res) {
						setSave(res && res.ok === true ? { phase: "saved" } : { phase: "error" });
					}).catch(function() {
						setSave({ phase: "error" });
					});
				}

				// 用户行 + 内置号（内置号恒可用，它带的是网关令牌）。
				// 顺序与 host 半一致：用户号在前先试，内置号在池尾兜底。
				var userValid = accounts.filter(function(a) { return a.key.trim().length > 0; }).length;
				var valid = userValid + (builtin !== null ? 1 : 0);
				// ⚠️ 号池改为服务端网关后，内置号不再指向国际站域名，所以下面这个
				// 「非国内站即算国际站槽位」的旧判据会把内置号误判成国际站。
				// 这里改成：只统计**用户自己填的**国际站号；内置号单独加一个槽位
				// （网关内部会自动在两站间路由，对客户端而言它是可用的）。
				var intl = accounts.filter(function(a) {
					return a.key.trim().length > 0 && !a.endpoint.includes("api.agnes-ai.cn");
				}).length;
				// 内置号经网关可用，按一个槽位计入。
				if (builtin !== null) intl += 1;
				var imageCapacity = (intl > 0 ? intl : Math.max(valid, 1)) * 10;

				var rows = accounts.map(function(row, idx) {
					var region = regionFor(row.endpoint);
					var shown = reveal[idx] === true;

					var seg = h("div", { className: "am-seg" },
						REGIONS.map(function(r) {
							var on = region !== null && region.endpoint === r.endpoint;
							return h("button", {
								key: r.endpoint,
								className: "am-seg-btn" + (on ? " on" : ""),
								onClick: function() { patchRow(idx, { endpoint: r.endpoint }); }
							}, r.label);
						})
					);

					var keyInput = h("input", {
						className: "am-input",
						type: shown ? "text" : "password",
						value: row.key,
						placeholder: "sk-xxxxxxxxxxxx",
						spellCheck: false,
						onChange: function(e) { patchRow(idx, { key: e.target.value }); }
					});

					var eyeBtn = h("button", {
						className: "am-btn",
						title: shown ? "隐藏密钥" : "显示密钥",
						onClick: function() {
							setReveal(function(prev) {
								var next = Object.assign({}, prev);
								next[idx] = !prev[idx];
								return next;
							});
						}
					}, shown ? "隐藏" : "显示");

					var rowTest = tests[idx];
					var busy = rowTest && rowTest.phase === "testing";
					var testBtn = h("button", {
						className: "am-btn",
						disabled: row.key.trim().length === 0 || busy,
						onClick: function() { testRow(idx); }
					}, busy ? "测试中" : "测试");

					var testResult = null;
					if (rowTest && rowTest.phase === "done") {
						if (rowTest.ok) testResult = h("span", { className: "am-hint am-ok" }, "✓ 密钥有效");
						else if (rowTest.reason === "invalid") testResult = h("span", { className: "am-hint am-bad" }, "✗ 密钥无效");
						else testResult = h("span", { className: "am-hint am-warn" }, "✗ 连接失败");
					}

					return h("div", { className: "am-row", key: idx },
						h("span", { className: "am-idx" }, String(idx + 1)),
						seg,
						keyInput,
						eyeBtn,
						testBtn,
						testResult,
						h("button", { className: "am-btn", onClick: function() { removeRow(idx); } }, "移除")
					);
				});

				// 内置号那一行：**只读**，没有地区切换、没有可编辑的输入框、没有「移除」。
				// 它在 UI 上恒排最后（与 host 半的池序一致：用户号先试、内置兜底）。
				// 保护不靠这里 —— 服务端会把传回去的内置条目强制换回权威值，
				// 所以即便有人绕过界面直接调 API 也改不动它。
				var builtinRow = null;
				if (builtin !== null) {
					var builtinRegion = regionFor(builtin.endpoint);
					// 号池改为服务端网关后，内置号的 endpoint 不再是两个站点域名之一，
					// regionFor 返回 null —— 此时显示「内置号池」，不要露出 URL
					// （对用户无意义，且长度会把整行撑开）。
					builtinRow = h("div", { className: "am-row am-row-builtin" },
						h("span", { className: "am-idx" }, "内置"),
						h("span", { className: "am-badge" }, "内置"),
						h("span", { className: "am-region-fixed" },
							builtinRegion !== null ? builtinRegion.label : "内置号池"
						),
						h("input", {
							className: "am-input",
							type: "text",
							value: "sk-agnes••••••••••••••••••••••••••••••••••••••",
							readOnly: true,
							tabIndex: -1,
							title: "内置免费号池，密钥由服务器中转，不可查看或修改"
						}),
						h("span", { className: "am-hint" },
							userValid > 0
								? "免费号池 · 不可修改 · 已让位给自定义号（排在其后兜底）"
								: "免费号池 · 不可修改 · 当前生效"
						)
					);
				}
				var statusLine;
				if (phase === "loading") statusLine = h("span", { className: "am-hint" }, "读取中…");
				else if (phase === "error") statusLine = h("span", { className: "am-hint am-bad" }, "无法连接配置服务");
				else if (saveSt.phase === "saving") statusLine = h("span", { className: "am-hint" }, "保存中…");
				else if (saveSt.phase === "saved") statusLine = h("span", { className: "am-hint am-ok" }, "已保存 ✓");
				else if (saveSt.phase === "error") statusLine = h("span", { className: "am-hint am-bad" }, "保存失败");
				else statusLine = h("span", { className: "am-hint" }, userValid > 0 ? "有未保存的改动" : "尚未添加自定义号，当前使用内置免费号池");

				var pageHead = h("div", { className: "am-page-head" },
					h("div", { className: "am-page-title-row" },
						h("span", { className: "am-page-mark" }),
						h("h2", { className: "am-page-title" }, NAV_LABEL)
					),
					h("p", { className: "am-page-desc" },
						"管理 Agnes AI 账号池。这一页是插件自己的设置页，与「插件」页并列。"
					)
				);

				var hero = h("div", { className: "am-hero" },
					h("div", { className: "am-hero-head" },
						h("span", { className: "am-dot" + (valid > 0 ? "" : " off") }),
						h("span", { className: "am-title" }, "账号池状态")
					),
					h("div", { className: "am-desc" },
						"一个账号池同时供图片生成（generate_image）与视频生成（generate_video）使用，按号轮询。"
						+ "每个号是独立的限流槽位：多一个号，视频速率 +1 次/分钟，图片并发 +10。"
						+ "国内站与国际站各自计费与限流。"
						+ "自定义号排在前面先试，内置免费号池恒在最后兜底，两者都不用时工具仍可用。"
					),
					h("div", { className: "am-stats" },
						h("div", { className: "am-stat" },
							h("span", { className: "am-stat-num" }, String(valid)),
							h("span", { className: "am-stat-label" }, "可用账号")
						),
						h("div", { className: "am-stat" },
							h("span", { className: "am-stat-num" }, String(valid > 0 ? imageCapacity : 10)),
							h("span", { className: "am-stat-label" }, "图片并发上限")
						),
						h("div", { className: "am-stat" },
							h("span", { className: "am-stat-num" }, String(valid > 0 ? valid : 1) + "/分"),
							h("span", { className: "am-stat-label" }, "视频生成速率")
						)
					)
				);

				return h("div", { className: "am-root" },
					pageHead,
					hero,
					h("div", { style: { display: "flex", flexDirection: "column", gap: "8px" } },
						rows.length > 0 ? rows : null,
						builtinRow
					),
					h("button", { className: "am-btn am-btn-dashed", onClick: addRow }, "+ 添加账号"),
					h("div", { className: "am-actions" },
						h("button", {
							className: "am-btn am-btn-primary",
							disabled: saveSt.phase === "saving" || phase !== "ready",
							onClick: save
						}, "保存"),
						statusLine
					)
				);
			}

			// ── 2. image card ─────────────────────────────────────────────

			/** True for a positive integer dimension or byte count. */
			function positiveInt(v) {
				return typeof v === "number" && isFinite(v) && v > 0 && Math.floor(v) === v;
			}

			/**
			 * Collect the durable image references out of a tool result.
			 *
			 * The result CONTENT is the single source of truth, mirroring the
			 * shipped `imageReferences()` that the built-in read_image view uses.
			 * `block.meta` is deliberately NOT consulted: the Web Client derives
			 * card props from the raw result content, not from presentationMeta.
			 * Malformed entries are skipped rather than failing the whole card.
			 */
			function imageRefs(block) {
				if (!block || block.kind !== "tool-result" || block.isError) return [];
				var content = block.content;
				if (!Array.isArray(content)) return [];
				var out = [];
				for (var i = 0; i < content.length; i++) {
					var part = content[i];
					if (!part || typeof part !== "object" || part.type !== "image") continue;
					var a = part.attachment;
					if (!a || typeof a !== "object" || Array.isArray(a)) continue;
					if (typeof a.attachmentId !== "string" || a.attachmentId === "") continue;
					if (typeof a.mediaType !== "string" || a.mediaType.indexOf("image/") !== 0) continue;
					var ref = {
						attachmentId: a.attachmentId,
						mediaType: a.mediaType,
						bytes: positiveInt(a.bytes) ? a.bytes : 0,
						width: positiveInt(a.width) ? a.width : 0,
						height: positiveInt(a.height) ? a.height : 0
					};
					if (typeof a.name === "string" && a.name !== "") ref.name = a.name;
					out.push(ref);
				}
				return out;
			}

			/** Join the text blocks of a tool result, for failure display. */
			function textOf(content) {
				if (!Array.isArray(content)) return "";
				var parts = [];
				for (var i = 0; i < content.length; i++) {
					var p = content[i];
					if (p && p.type === "text" && typeof p.text === "string") parts.push(p.text);
				}
				return parts.join("\n\n");
			}

			// ── image card ────────────────────────────────────────────────
			//
			// The shell hands every tool view a session-authorized `loadImage`
			// loader and nothing else — tool views "do not manage attachment
			// URLs". `loadImage(ref)` resolves the durable reference to a
			// displayable URL and rejects when the session cache is gone, while
			// `loadImage.peek(ref)` answers synchronously from the cache and is
			// what makes a re-render free of flicker.
			//
			// The geometry below mirrors the shipped `MessageImage` used for
			// pasted images, so a generated image lands in the transcript the
			// same way an upload does: one image is fitted into a 240px box at
			// its aspect ratio clamped to [0.25, 4] and never upscaled, several
			// images become 64px square tiles, and either opens the original.

			/** The shell's lone-image box rule, copied from MessageImage. */
			function singleFit(dimensions) {
				var natural = dimensions.width / dimensions.height;
				var ratio = Math.min(4, Math.max(0.25, natural));
				var box = ratio >= 1
					? { width: 240, height: 240 / ratio }
					: { width: 240 * ratio, height: 240 };
				var scale = Math.min(1, dimensions.width / box.width, dimensions.height / box.height);
				return {
					width: Math.max(1, Math.round(box.width * scale)),
					height: Math.max(1, Math.round(box.height * scale)),
					objectPosition: natural < 0.25 ? "center top" : natural > 4 ? "left center" : "center"
				};
			}

			/**
			 * One durable image, resolved through the shell's loader.
			 *
			 * The URL starts from the synchronous cache probe and is replaced
			 * when the async resolution lands, so an already-cached image paints
			 * on the first frame. A rejection flips to a retry control instead
			 * of a broken `<img>`.
			 */
			function MediaImage(props) {
				var ref = props.image;
				var load = props.load;
				var variant = props.variant;
				var openState = React.useState(false);
				var open = openState[0];
				var setOpen = openState[1];
				var urlState = React.useState(function() {
					return typeof load.peek === "function" ? load.peek(ref) || null : null;
				});
				var url = urlState[0];
				var setUrl = urlState[1];
				var errState = React.useState(false);
				var failed = errState[0];
				var setFailed = errState[1];
				var tryState = React.useState(0);
				var attempt = tryState[0];
				var setAttempt = tryState[1];

				React.useEffect(function() {
					var live = true;
					setFailed(false);
					setUrl(typeof load.peek === "function" ? load.peek(ref) || null : null);
					Promise.resolve(load(ref)).then(function(next) {
						if (live) setUrl(next);
					}, function() {
						if (live) setFailed(true);
					});
					return function() { live = false; };
				}, [ref, load, attempt]);

				var fit = variant === "single"
					? (ref.width > 0 && ref.height > 0
						? singleFit({ width: ref.width, height: ref.height })
						: { width: 240, height: 240, objectPosition: "center" })
					: null;

				if (failed) {
					return h("button", {
						type: "button",
						className: "am-mimg-error",
						"data-variant": variant,
						title: "图片加载失败，点击重试",
						onClick: function() { setAttempt(function(a) { return a + 1; }); }
					}, "图片加载失败，点击重试");
				}

				var label = ref.name || "生成的图片";
				var frame = h("button", {
					type: "button",
					className: "am-mimg-frame",
					"data-variant": variant,
					style: fit === null ? null : { width: fit.width, height: fit.height },
					title: url === null ? "加载中…" : "点击查看原图",
					onClick: function() { if (url !== null) setOpen(true); }
				}, url === null
					? h("span", { className: "am-mimg-loading" }, "加载中…")
					: h("img", {
						src: url,
						alt: label,
						style: fit === null ? null : { objectPosition: fit.objectPosition }
					})
				);

				if (!open || url === null) return frame;
				return h(React.Fragment, null,
					frame,
					h("div", {
						className: "am-lb",
						onClick: function() { setOpen(false); }
					}, h("img", { className: "am-lb-img", src: url, alt: label }))
				);
			}

			/** The shell's gallery grouping rule: several images become tiles. */
			function MediaGallery(props) {
				var images = props.images;
				var load = props.load;
				if (images.length === 0) return null;
				var variant = images.length > 1 ? "tile" : "single";
				return h("div", { className: "am-gallery", "data-align": "start" },
					images.map(function(ref, i) {
						return h(MediaImage, { image: ref, load: load, variant: variant, key: ref.attachmentId + ":" + i });
					})
				);
			}

			function ImageCard(props) {
				// Keyed tool views are dispatched with `phase` plus a stage-specific
				// `block`, and only the `result` stage carries the tool result. The
				// view mounts while the call is still `preparing`, so nothing may be
				// decided from the block at mount time.
				var block = props.phase === "result" ? props.block : null;

				if (props.phase !== "result") {
					return h("span", { className: "am-loading" }, "正在生成图片…");
				}
				if (block && block.isError) {
					var errText = textOf(block.content).trim();
					return h("span", { className: "am-err" }, "图片生成失败" + (errText ? "：" + errText.slice(0, 300) : ""));
				}
				// The finished result carries each generated PNG as a durable image
				// block in its content, which is what the Web Client renders.
				var refs = imageRefs(block);
				if (refs.length === 0) {
					return h("span", { className: "am-hint" }, "本次未生成可预览的图片");
				}
				if (typeof props.loadImage !== "function") {
					return h("span", { className: "am-hint" }, "图片加载器不可用");
				}
				return h("div", { className: "am-media" },
					h("div", { className: "am-media-head" },
						h("span", null, "图片生成"),
						h("span", { className: "am-chip" }, String(refs.length) + " 张")
					),
					h(MediaGallery, { images: refs, load: props.loadImage })
				);
			}

			// ── 3. video card ─────────────────────────────────────────────

			/**
			 * The playable videos a `generate_video` result published.
			 *
			 * Videos do not travel as durable content blocks the way images do:
			 * the host half publishes them through the tool result's
			 * `presentationMeta`, which is where the Web Client reads them from
			 * too. A `show: false` call publishes the metadata but marks it
			 * unshown, and that is honoured here.
			 */
			function videoList(meta) {
				if (!meta || meta.shown !== true) return [];
				var list = meta.videos && meta.videos.length > 0
					? meta.videos
					: (meta.url ? [{ url: meta.url, prompt: meta.prompt }] : []);
				return list.filter(function(v) { return v && v.url; });
			}

			/**
			 * The playable grid itself, shared by the tool card and the tail
			 * delivery so both present a video the same way.
			 */
			function VideoGrid(props) {
				var list = props.list;
				var single = list.length === 1;
				var items = list.map(function(v, i) {
					var frame = h("div", { className: "am-vframe" },
						h("video", {
							src: v.url,
							controls: true,
							preload: "metadata",
							style: single ? { maxWidth: SINGLE_MAX + "px" } : null
						})
					);
					var prompt = excerpt(v.prompt, 90);
					var metaRow = h("div", { className: "am-vmeta" },
						h("span", { className: "am-vidx" }, "视频 " + (i + 1) + (v.video_id ? " · " + String(v.video_id).slice(0, 18) : "")),
						prompt ? h("span", { className: "am-vprompt", title: String(v.prompt || "") }, prompt) : null
					);
					return h("div", {
						className: "am-vitem" + (single ? " am-vsingle" : ""),
						key: i
					}, frame, metaRow);
				});
				return h("div", { className: "am-grid", style: single ? { display: "block" } : null },
					single ? items[0] : h("div", { className: "am-vgrid" }, items)
				);
			}

			function VideoCard(props) {
				// Same stage contract as the image card: the view mounts during
				// `preparing` and only the `result` stage carries the tool result.
				if (props.phase !== "result") {
					return h("span", { className: "am-loading" }, "正在生成视频…");
				}
				var block = props.block;
				if (block && block.isError) {
					var errText = textOf(block.content).trim();
					return h("span", { className: "am-err" }, "视频生成失败" + (errText ? "：" + errText.slice(0, 300) : ""));
				}
				var meta = block ? block.meta : null;
				var list = videoList(meta);
				if (list.length === 0) {
					return meta && meta.shown === true
						? h("span", { className: "am-loading" }, "视频未展示")
						: h("span", { className: "am-hint" }, "本次未生成可预览的视频");
				}

				return h("div", { className: "am-media" },
					h("div", { className: "am-media-head" },
						h("span", null, "视频生成"),
						h("span", { className: "am-chip" }, String(list.length) + " 个")
					),
					h(VideoGrid, { list: list })
				);
			}

			// ── 4. turn-tail delivery ─────────────────────────────────────
			//
			// A completed Turn's process region folds shut, which would leave the
			// card above one click away. `conversation.chat.turnTail` is the
			// always-open list the shell renders BELOW that fold — the seat
			// ui-deliverables and ui-plan publish their closing artifacts into —
			// so the Turn's generated images are repeated here and land directly
			// under the reply.
			//
			// The Turn's tool calls are read through `turnDataSource(turn, kind)`,
			// the documented per-Turn node source, rather than by subscribing to
			// the whole timeline: it is already filtered to this Turn and this
			// node kind, and its membership updates incrementally.
			//
			// Rendering carries no chrome on purpose, so a generated image sits
			// in the transcript exactly like a pasted one.

			/** Every durable image produced by one Turn's `generate_image` calls. */
			function generatedImages(nodes) {
				if (!Array.isArray(nodes)) return [];
				var out = [];
				for (var i = 0; i < nodes.length; i++) {
					var root = nodes[i] ? nodes[i].root : null;
					if (!root || root.kind !== "tool-result" || root.isError) continue;
					// `call` is null only when a result arrived without its
					// correlated call event. Requiring the name keeps a
					// read_image result in the same Turn from doubling up here.
					if (!root.call || root.call.name !== "generate_image") continue;
					var found = imageRefs(root);
					for (var j = 0; j < found.length; j++) out.push(found[j]);
				}
				return out;
			}

			/**
			 * Every playable video produced by one Turn's `generate_video` calls.
			 *
			 * Unlike images these live in the result's `presentationMeta`, not in
			 * durable content, so the same `videoList` the tool card uses is
			 * applied here — including its `shown` check, which is what makes
			 * `show: false` stay silent.
			 */
			function generatedVideos(nodes) {
				if (!Array.isArray(nodes)) return [];
				var out = [];
				for (var i = 0; i < nodes.length; i++) {
					var root = nodes[i] ? nodes[i].root : null;
					if (!root || root.kind !== "tool-result" || root.isError) continue;
					if (!root.call || root.call.name !== "generate_video") continue;
					var found = videoList(root.meta);
					for (var j = 0; j < found.length; j++) out.push(found[j]);
				}
				return out;
			}

			function GeneratedMediaTail(props) {
				var turn = props.turn;
				// The seat's own props are always present, the injected ones are
				// not: an unresolvable Session or Chat target yields no hook, and
				// then there is simply nothing to deliver.
				if (!turn || typeof props.useTurnMedia !== "function") return null;
				var nodes = props.useTurnMedia(String(turn.turn));

				var children = [];
				// Images first, then videos, so a Turn that produced both reads
				// in the order the tools were called.
				if (typeof props.loadImage === "function") {
					var refs = generatedImages(nodes);
					if (refs.length > 0) {
						children.push(h(MediaGallery, { images: refs, load: props.loadImage, key: "images" }));
					}
				}
				var videos = generatedVideos(nodes);
				if (videos.length > 0) {
					children.push(h(VideoGrid, { list: videos, key: "videos" }));
				}
				if (children.length === 0) return null;
				return h("div", { className: "am-tail" }, children);
			}

			// ── registration ──────────────────────────────────────────────

			// `settings.section` gives this plugin a whole page in the settings
			// sidebar, next to the shipped pages. `settings.plugins.tab` would
			// only nest a tab under 设置 → 插件.
			ctx.slots.inject("settings.section", function() {
				return ctx.slots.register({
					name: "settings.section",
					id: "agnes-multimodal",
					// Sit after the shipped pages so the stock ordering is
					// unchanged; a high order keeps this page last.
					order: 200,
					label: "多模态配置"
				}, ConfigPanel);
			});

			// `locale` selects the shipped `conversation` namespace, the same one
			// the built-in tool rows use.
			//
			// This entry deliberately declares no `children`. `tool.call.images`
			// already has its spec declared by the built-in `read_image` row, and
			// `slots.register` rejects a second declaration of the same child
			// ("slot ... is already declared"), which would abort this whole
			// client half. The card renders the durable image through the
			// `loadImage` loader the shell hands to every tool view instead —
			// that is the documented path for tool views, which "do not manage
			// attachment URLs".
			ctx.slots.inject("tool.call.toolview", function() {
				return ctx.slots.register({
					name: "tool.call.toolview",
					key: "generate_image",
					locale: CONVERSATION_NS
				}, ImageCard);
			});

			ctx.slots.inject("tool.call.toolview", function() {
				return ctx.slots.register({ name: "tool.call.toolview", key: "generate_video" }, VideoCard);
			});

			// The always-open seat below the Turn's folded process region. It is a
			// plain `list`, so publishing here neither claims the seat nor blocks
			// the shipped entries (ui-deliverables, ui-plan) that share it.
			//
			// `inject` receives the session id and returns the props the component
			// gets on top of the seat's own ({ turn, seq, openFile }). Two things
			// are needed and neither is on the seat:
			//
			//   * `keyedHooks.turnMedia` — the per-Turn node source, read through
			//     the Chat target's snapshot. It becomes the `useTurnMedia` prop
			//     and carries both the images and the videos, because both are
			//     projected into the same `tool-call` node kind.
			//   * `loadImage` — the seat is not a tool view, so the shell does not
			//     hand it a loader; this plugin builds the same session-authorized
			//     one the Chat view builds for its own children. Videos need no
			//     loader: their URLs come from the result metadata.
			//
			// This factory runs while the seat renders, not while `apply` runs, so
			// the two services are resolved lazily and a missing one costs the
			// seat rather than the whole client half.
			ctx.slots.inject("conversation.chat.turnTail", function() {
				return ctx.slots.register({
					name: "conversation.chat.turnTail",
					id: "agnes-multimodal",
					inject: function(sessionId) {
						// A seat is not worth failing the whole client half over:
						// an unresolvable Session or Chat target simply leaves the
						// card out, and the tool view still shows the media.
						try {
							var binding = ctx.sessions.binding(sessionId);
							if (binding === void 0) return {};
							var chat = ctx.uiConversation.binding(binding).target("chat");
							return {
								keyedHooks: {
									turnMedia: function(turn) {
										var snapshot = chat.getSnapshot();
										if (snapshot === void 0) return [];
										return snapshot.nodes.turnDataSource(Number(turn), "tool-call");
									}
								},
								loadImage: Object.assign(
									function(attachment) { return ctx.uiConversation.imageUrl(sessionId, attachment); },
									{ peek: function(attachment) { return ctx.uiConversation.peekImageUrl(sessionId, attachment); } }
								)
							};
						} catch (e) {
							return {};
						}
					}
				}, GeneratedMediaTail);
			});
		};
		return module.exports;
	}
});
