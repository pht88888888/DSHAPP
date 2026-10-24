/**
 * dsh-android-browser —— 给模型一台**属于它自己的浏览器**。
 *
 * ## 为什么需要这一层
 *
 * 模型跑在 Node 进程里：没有 `window`、没有 `document`，也没有任何办法
 * 打开一个网页。而「让 AI 自己去某个网站把事办了」需要三件事同时成立：
 * ① 有个**真**浏览器在跑（能过 `X-Frame-Options` / CSP）；② 它的登录态
 * **能留住**；③ 模型能**读得懂页面、点得动按钮**。
 *
 * 引擎自带的侧栏浏览器是 **iframe**，GitHub（`X-Frame-Options: deny`）、
 * Google（CSP）直接拒绝嵌入 —— 连登录页都加载不出来。所以这里换成
 * 原生 `WebView`（`AiBrowserView`），跑在**用户自己的手机上**，不消耗
 * 任何服务器资源。
 *
 * ## 跨进程链路
 *
 * ```
 * 模型 browser 工具 ──写 browser-cmd.json──▶ BrowserBridge(Kotlin)
 *                                            └─ AiBrowserView.evaluate ─▶ 页面 JS
 *          ◀──读 browser-result.json─────────┘      ◀── JSON 结果 ───────┘
 *
 * ```
 *
 * 与 `dsh-android-uiagent` 同构（同样的文件信箱 + 自增 id + 先写结果再删请求），
 * 但**信箱是独立的**：命令可能并发发起，共用一个文件会互相覆盖请求 ——
 * 那种失败是偶发的，最难查。
 *
 * ## 与 `ui` 工具的分工（很容易搞混，务必分清）
 *
 * | | `ui` 工具 | `browser` 工具（本插件） |
 * |---|---|---|
 * | 操作对象 | **本 App 自己的界面**（DSH Web GUI） | **别的网站** |
 * | 底层 | 主 WebView | 独立的第二个 WebView |
 * | 用途 | 调这个 App 的按钮/面板 | 上网、登录、抓数据 |
 *
 * 要改 App 自己的设置请用 `ui`；要上网请用 `browser`。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-browser
 */

import fs from 'node:fs';
import path from 'node:path';

export const name = 'dsh-android-browser';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具不冲突。 */
const TOOL_NAME = 'browser';

/** 文件信箱里两个文件名，原生侧 `BrowserBridge.kt` 必须与这里一致。 */
const CMD_FILE = 'browser-cmd.json';
const RESULT_FILE = 'browser-result.json';

/**
 * 等待原生回执的上限。
 *
 * ⚠️ 必须**大于**原生侧的 `OP_TIMEOUT_MS`（18 秒），否则会出现「原生还在
 *   干活，插件已经报超时」—— 模型会以为失败而重试，于是同一个点击做了两次。
 */
const WAIT_TIMEOUT_MS = 25_000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 80;

/** 模型可见的全部 action。 */
const ACTIONS = [
	'open', 'read', 'click', 'type', 'press', 'scroll',
	'screenshot', 'back', 'forward', 'reload', 'tabs', 'wait', 'eval',
];

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死（理由与 `dsh-android-uiagent` 同）：
 * ① 什么场景该用（否则模型不知道有这个能力，会拿 bash + curl 去猜）；
 * ② 字段有哪些（否则模型会猜字段名，静默无效）；
 * ③ **read 的 index 是唯一可靠的定位方式**（猜 CSS 选择器在真实站点上
 *    十次有八次落空，而失败信息指不到真正原因）。
 */
const DESCRIPTION = `Control a REAL browser that lives on the user's phone — a native Android WebView, separate from this app's own UI. Use it whenever you need to go on the web: open a page, read it, click links, fill and submit forms, log in to a site, scrape data, or take a screenshot to look at.

## Why this tool exists

You run in a Node process with no DOM. This browser is a genuine second WebView inside the app, so it is NOT blocked by \`X-Frame-Options\` or CSP — GitHub, Google, Twitter, any site that refuses to be iframed will load here normally. It also has **persistent cookies**: a login you perform now survives app restarts, so you can log in once and act on that site in later sessions.

The user can see this browser on their screen while you drive it. When you act, you take exclusive control (a small chip at the top shows "AI 操作中"); the user can tap it to take over. Don't worry about stepping on them — but do call \`read\` again if a click stops working, because they may have navigated.

## The workflow that actually works

1. \`{action:"open", url:"https://github.com"}\` — navigates and waits for the page to load.
2. \`{action:"read"}\` — returns the page's visible text plus a numbered list of every interactive element, with its tag, type, label, placeholder and name.
3. \`{action:"click", index:7}\` or \`{action:"type", index:3, value:"me@example.com"}\` — **use the index from the read you just did.** Indices are stable as long as the page does not change, and are the only reliable locator: guessing CSS selectors or visible text fails constantly on real sites.
4. \`read\` again after anything that navigates or opens a dialog.

## Logging in

Many login flows are multi-step and asynchronous. Type the credentials, click the submit button, then \`read\` again — or use \`{action:"wait", text:"Inbox"}\` to block until the post-login page settles. If the site shows a captcha, pick whatever option is easiest for the user (some have audio / "try another"; some can be skipped). If you are truly stuck on a captcha, tell the user what you see and ask them to tap the chip and solve it — that is a normal ending, not a failure.

## Screenshots

\`{action:"screenshot"}\` saves a PNG of the current viewport and returns its absolute path. \`read\` this file with your normal read tool to actually look at the page — this is the fallback when the DOM dump is not enough (canvas UIs, visual layout questions).

## Notes

- \`read\` returns at most 150 elements; use \`filter\` to narrow, or \`limit\` to trim.
- Downloads triggered on the page are saved to the app's own Download folder automatically.
- File uploads work: a file input opens the system picker for the user.
- Up to 6 tabs. \`{action:"tabs", sub:"new", url:"..."}\` opens one.`;

/**
 * 向原生发一条命令并等回执。
 *
 * ⚠️ **先把旧回执删掉**：否则上一轮的 `ok` 会被当成本轮的答案。
 *   用 `unlink` 而不是写空对象 —— 写空对象会让「解析失败」与「还没处理」
 *   两种状态混在一起，只能靠 id 分辨，多一处出错的机会。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - 命令体（至少含 `action`）。
 * @returns {Promise<object>} 原生回执。
 */
async function sendCommand(home, id, payload) {
	const cmdPath = path.join(home, CMD_FILE);
	const resPath = path.join(home, RESULT_FILE);

	try {
		try { fs.unlinkSync(resPath); } catch { /* 不存在就是已经干净了 */ }
		writeJsonAtomic(cmdPath, { id, ...payload });
	} catch (error) {
		return { ok: false, error: `写入文件信箱失败: ${error?.message ?? error}` };
	}

	const deadline = Date.now() + WAIT_TIMEOUT_MS;
	while (Date.now() < deadline) {
		await sleep(POLL_INTERVAL_MS);
		const reply = readJson(resPath);
		if (reply === null || reply.id !== id) continue;
		return reply;
	}
	// 超时不代表失败：原生侧可能只是慢（页面在加载、主线程正忙）。
	// 但绝不能返回 ok —— 那会让模型以为操作成功了。
	return {
		ok: false,
		error: `等待浏览器回执超时（${WAIT_TIMEOUT_MS} ms）。页面可能还在加载，也可能已经变化；`
			+ '请用 {action:"read"} 复查当前状态，确认后再向用户汇报。',
	};
}

/** 原子写：先临时文件再 rename（原生侧可能正好读到这里）。 */
function writeJsonAtomic(file, value) {
	const tmp = `${file}.${process.pid}.tmp`;
	fs.writeFileSync(tmp, JSON.stringify(value), 'utf8');
	try {
		fs.renameSync(tmp, file);
	} catch (error) {
		try { fs.unlinkSync(tmp); } catch { /* 忽略 */ }
		throw error;
	}
}

/** 读一个 JSON 文件；不存在/坏了都返回 null（都不是错误）。 */
function readJson(file) {
	try {
		return JSON.parse(fs.readFileSync(file, 'utf8'));
	} catch {
		return null;
	}
}

function sleep(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 参数校验。返回空串表示通过，否则返回给模型看的错误文案。
 *
 * ⚠️ 只校验**结构**，不校验语义（比如 index 越界）—— 后者只有原生侧知道。
 * @param {object} args - 工具参数。
 * @returns {string} 错误文案，或空串。
 */
function validate(args) {
	if (typeof args.action !== 'string' || args.action === '') {
		return `✗ 缺少 action。可选：${ACTIONS.join(', ')}`;
	}
	if (!ACTIONS.includes(args.action)) {
		return `✗ 未知 action「${args.action}」。可选：${ACTIONS.join(', ')}`;
	}
	if (args.action === 'open' && (typeof args.url !== 'string' || args.url.trim() === '')) {
		return '✗ open 需要 url（如 "https://github.com"）。';
	}
	if (args.action === 'type' && typeof args.value !== 'string') {
		return '✗ type 需要 value（要写进输入框的文字；定位用 index/selector，不是 text）。';
	}
	if (args.action === 'eval' && (typeof args.js !== 'string' || args.js.trim() === '')) {
		return '✗ eval 需要 js。';
	}
	if (args.action === 'wait' && !args.text && !args.selector) {
		return '✗ wait 需要 text 或 selector。';
	}
	return '';
}

/**
 * 把回执渲染成模型能直接读的文本。
 *
 * 之所以要手写渲染而不是 `JSON.stringify`：`read` 的回执里那段可见文本可能
 * 有几千字，套在 JSON 里会带上成片的 `\n` 转义，模型读起来既费 token 又容易
 * 把转义当成内容的一部分。
 *
 * @param {object} args - 原始参数。
 * @param {object} reply - 原生回执。
 * @returns {string} 渲染后的文本。
 */
function render(args, reply) {
	// `read` 的回执是「页面内容」而不是「操作结果」，早期原生侧漏了 ok 字段，
	// 导致每次 read 都渲染成「✗ 未知错误」。这里按形状兜一层：带 url/elements
	// 的 read 回执就认为是成功的，不再只认 ok 标志。
	const looksLikeRead = args.action === 'read'
		&& reply && typeof reply === 'object'
		&& Array.isArray(reply.elements);
	if (reply?.ok !== true && !looksLikeRead) {
		return `✗ ${reply?.error ?? '未知错误'}`;
	}

	switch (args.action) {
		case 'open':
		case 'back':
		case 'forward':
		case 'reload': {
			const loaded = reply.loaded === true ? '' : '\n⚠️ 页面加载未在超时内完成，可能还在加载。';
			return `✓ ${args.action} → ${reply.url ?? '(未知地址)'}${loaded}`;
		}
		case 'read': {
			const lines = [];
			lines.push(`URL: ${reply.url ?? ''}`);
			lines.push(`标题: ${reply.title ?? ''}`);
			if (typeof reply.scrollY === 'number' && typeof reply.scrollHeight === 'number') {
				lines.push(`滚动位置: ${reply.scrollY} / ${reply.scrollHeight}`);
			}
			const text = typeof reply.text === 'string' ? reply.text.trim() : '';
			if (text !== '') {
				lines.push('');
				lines.push('── 可见文本 ──');
				lines.push(text);
			}
			const els = Array.isArray(reply.elements) ? reply.elements : [];
			lines.push('');
			lines.push(`── 可交互元素（${els.length}）──`);
			for (const el of els) {
				const bits = [`[${el.i}]`, el.tag];
				if (el.type) bits.push(`type=${el.type}`);
				if (el.name) bits.push(`name=${el.name}`);
				if (el.checked === true) bits.push('已勾选');
				const label = el.text || el.placeholder || el.href || '';
				if (label) bits.push(`"${label}"`);
				lines.push(bits.join(' '));
			}
			if (els.length === 0) {
				lines.push('（没有可交互元素 —— 页面可能是 canvas 渲染的，试 screenshot）');
			}
			return lines.join('\n');
		}
		case 'screenshot':
			return `✓ 截图已保存: ${reply.path ?? '(未知路径)'}\n用 read 工具打开这个 PNG 就能看到页面。`;
		case 'tabs': {
			if (Array.isArray(reply.tabs)) {
				const lines = [`标签（当前第 ${reply.active} 个）:`];
				for (const t of reply.tabs) {
					lines.push(`  [${t.index}] ${t.title || '(无标题)'} — ${t.url}`);
				}
				return lines.join('\n');
			}
			return `✓ 现在 ${reply.count ?? '?'} 个标签${reply.url ? `，当前地址 ${reply.url}` : ''}`;
		}
		case 'wait':
			return reply.found === true
				? '✓ 等到了。'
				: '✗ 等到超时也没出现。用 read 看看页面现在是什么状态。';
		case 'click':
			return `✓ 点了「${reply.clicked || '(无标签元素)'}」。需要的话 read 一次看结果。`;
		case 'type':
			return `✓ 已输入，输入框现在是「${reply.value ?? ''}」。`;
		case 'press':
			return `✓ 按了 ${reply.key ?? args.key ?? 'Enter'}。`;
		case 'scroll':
			return `✓ 已滚动，当前位置 ${reply.y ?? '?'}。`;
		case 'eval':
			return typeof reply.value === 'string' ? reply.value : JSON.stringify(reply);
		default:
			return JSON.stringify(reply);
	}
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在
 *   `$DSH_HOME/browser-tool/` 下，那里没有 `node_modules`，ESM 解析够不到
 *   引擎的 `@deepseek-ai/dsh-tools`。做法与 `dsh-android-uiagent` 一致。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function browserTool(home) {
	/** 请求编号。进程内自增即可 —— 只有本插件会写这个信箱。 */
	let seq = 0;

	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			required: ['action'],
			properties: {
				action: {
					type: 'string',
					enum: ACTIONS,
					description: 'What to do. "open" navigates to a URL; "read" dumps the page (visible text + numbered interactive elements); "click"/"type" act on an element by its index from the last read; "press" sends a key to the focused element; "scroll" moves the viewport; "screenshot" saves a PNG of the viewport; "back"/"forward"/"reload" navigate history; "tabs" manages tabs; "wait" blocks until some text appears; "eval" runs raw JavaScript as an escape hatch.',
				},
				url: {
					type: 'string',
					description: 'For action:"open" and action:"tabs" with sub:"new". A full URL (https://…). Bare domains also work.',
				},
				index: {
					type: 'number',
					description: 'For click/type/tabs-select — the element number from the most recent "read". This is the reliable locator; prefer it over selector/text.',
				},
				selector: {
					type: 'string',
					description: 'For click/type — a CSS selector, as a fallback when you cannot use index.',
				},
				text: {
					type: 'string',
					description: 'For click — match an interactive element by its visible label (substring). For wait — the text to wait for.',
				},
				value: {
					type: 'string',
					description: 'For action:"type" — the text to write into the input. (Not `text`: `text` is a locator.)',
				},
				enter: {
					type: 'boolean',
					description: 'For action:"type" — submit the form (press Enter / requestSubmit) after writing.',
				},
				key: {
					type: 'string',
					description: 'For action:"press" — the key to send, e.g. "Enter", "Escape", "Tab". Default "Enter".',
				},
				dy: {
					type: 'number',
					description: 'For action:"scroll" — vertical pixels to scroll (negative scrolls up). Default 600.',
				},
				to: {
					type: 'string',
					enum: ['top', 'bottom'],
					description: 'For action:"scroll" — jump to the top or bottom instead of by pixels.',
				},
				sub: {
					type: 'string',
					enum: ['list', 'new', 'close', 'select'],
					description: 'For action:"tabs" — what to do. Default "list".',
				},
				timeout: {
					type: 'number',
					description: 'For action:"wait" — max milliseconds to wait (default 8000, max 18000).',
				},
				js: {
					type: 'string',
					description: 'For action:"eval" — JavaScript to run in the page. Return a JSON-serialisable value. Use sparingly; read/click/type cover most cases.',
				},
				limit: {
					type: 'number',
					description: 'Reserved — the native side caps the element list at 150.',
				},
				filter: {
					type: 'string',
					description: 'Reserved — filter the element list client-side by substring (applied here, not natively).',
				},
			},
		},
		output: {
			schema: {
				type: 'object',
				properties: {
					ok: { type: 'boolean' },
					message: { type: 'string' },
					// 原始回执：形状随 action 变（read 带 elements、screenshot 带 path…），
					// 所以这里**不能**收窄成固定形状，否则真机上会以 ToolOutputError
					// 的形式炸在渲染之前。
					details: {},
				},
				required: ['ok', 'message'],
			},
			render: (_args, value) => [{ type: 'text', text: value.message }],
		},
		async execute(args) {
			const bad = validate(args ?? {});
			if (bad !== '') return { ok: false, message: bad };

			// `filter` / `limit` 是**在插件这边**生效的展示层裁剪：原生侧一次
			// 最多回 150 个元素，把它们全塞进上下文对模型没有好处。
			const payload = { ...args };
			delete payload.filter;
			delete payload.limit;

			const reply = await sendCommand(home, ++seq, payload);

			if (args.action === 'read' && Array.isArray(reply?.elements)) {
				let els = reply.elements;
				if (typeof args.filter === 'string' && args.filter !== '') {
					const needle = args.filter.toLowerCase();
					els = els.filter((el) => JSON.stringify(el).toLowerCase().includes(needle));
				}
				// 默认只回 60 个：一个登录页就有一两百个链接，全塞进上下文
				// 既贵又没用（模型真正要点的通常就在前 20 个里）。
				const cap = typeof args.limit === 'number' && args.limit > 0
					? Math.floor(args.limit)
					: 60;
				els = els.slice(0, cap);
				reply.elements = els;
			}

			const message = render(args, reply);
			// 与 render 同一套判定：read 的成功以「回执形状」为准，
			// 否则工具会以 ok:false 收场，上层可能据此判定调用失败。
			const looksLikeRead = args.action === 'read'
				&& reply && typeof reply === 'object'
				&& Array.isArray(reply.elements);
			return {
				ok: reply?.ok === true || looksLikeRead,
				message,
				// 原始回执附回去：模型需要精确值时（比如截图路径）可以读
				details: reply,
			};
		},
	};
}

/**
 * 注册 `browser` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-browser] 未配置 DSH_HOME，browser 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-browser] DSH_HOME 不存在（${home}），browser 工具未注册`);
		return;
	}

	// ⚠️ **register 必须包 try/catch**：工具注册抛异常会让 cordis 判定插件 failed，
	//   进而 `dsh: startup failed: N required plugin did not activate` ⇒ 引擎退出、
	//   App 完全打不开。真机上踩过（`dsh-android-logs` 漏 `output` 声明，54 次）。
	try {
		ctx.tools.register(browserTool(home));
		ctx?.logger?.info?.(`[dsh-android-browser] 已注册 ${TOOL_NAME} 工具: ${path.join(home, CMD_FILE)}`);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-browser] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
