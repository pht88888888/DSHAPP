/**
 * dsh-android-uiagent —— 把「操作并观察这个 App 的界面」暴露成一个**模型可调用的工具**。
 *
 * ## 为什么需要这一层
 *
 * 这个 App 的界面不是自绘的：它是官方 DSH Web GUI 跑在 WebView 里，按钮全是
 * React 渲染出来的 DOM。而**模型跑在 Node 进程里** —— 那里没有 `window`、
 * 没有 `document`，也没有任何办法「点一下那个按钮」。
 *
 * 于是「让 AI 自己测试/优化这个 App」在能力上是空的：它既看不见界面，
 * 也动不了界面。本插件补的就是这一段。
 *
 * | 谁 | 能不能操作界面 | 为什么 |
 * |---|---|---|
 * | 页面侧插件（`lib/client.js`） | ✅ | 浏览器里直接摸到 DOM |
 * | 模型（AI） | ❌ | 工具跑在 **Node 进程**里，没有 DOM |
 * | 本插件 | ✅ | 经**文件信箱**把命令送到原生，原生再打进页面 |
 *
 * ## 跨进程链路
 *
 * ```
 * 模型 ui 工具 ──写 ui-cmd.json──▶ UiProbeBridge(Kotlin)
 *                                    └─ WebHost.evaluateJavascript ─▶ __DSH_UI_PROBE__.run(cmd)
 *          ◀──读 ui-result.json──────┘        ◀─── JSON 结果 ───────────┘
 * ```
 *
 * 与 `dsh-android-bubble` 同构（同一个文件信箱模式），但**信箱是独立的**：
 * 命令可能并发发起，共用一个文件会互相覆盖请求 —— 那种失败是偶发的，最难查。
 *
 * ⚠️ 请求带自增 `id`，结果按 id 配对。没有它，「两个工具调用并发、后一个的结果
 *   被前一个读走」会让模型拿到张冠李戴的回执 —— 表现为「AI 说点过了，但界面没动」。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或目录不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-uiagent
 */

import fs from 'node:fs';
import path from 'node:path';

import { ATLAS, findScreen } from './screens.js';

export const name = 'dsh-android-uiagent';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具不冲突（实测 30 个内置工具名里没有它）。 */
const TOOL_NAME = 'ui';

/** 文件信箱里两个文件名，原生侧 `UiProbeBridge` 必须与这里一致。 */
const CMD_FILE = 'ui-cmd.json';
const RESULT_FILE = 'ui-result.json';

/**
 * 等待原生回执的上限。
 *
 * ⚠️ 与 bubble 桥的 4 秒不同，这里给到 **20 秒**：`wait` 动作本身就可能等 10 秒
 *   （界面加载、动画结束），原生侧的总超时是 12 秒，这里必须比它长，
 *   否则会出现「原生还在干活，插件已经报超时」——模型会以为失败而重试，
 *   于是同一个点击做了两次。
 */
const WAIT_TIMEOUT_MS = 20000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 80;

/**
 * 交给**页面探针**执行的动作。
 *
 * ⚠️ 这份清单必须与 `ui-probe.js` 的分派表一致 —— 探针不认识的 action 会返回
 *   `未知 action`，而那是**在真机上**才会暴露的错误（PC 侧跑不到）。
 */
const PROBE_ACTIONS = ['ping', 'info', 'dump', 'find', 'tap', 'type', 'key', 'scroll', 'text', 'html', 'wait', 'overlay', 'reload'];

/**
 * 在**插件侧**实现的动作 —— 它们由「多次探针调用 + 内建图谱」组合而成，
 * 探针本身不认识，所以**绝不能**原样写进 `ui-cmd.json`。
 *
 * | 动作 | 做什么 | 为什么要在这边做 |
 * |---|---|---|
 * | `map` | 列出内建的 17 屏及各屏按钮 | 纯静态数据，不必碰界面 |
 * | `where` | 报告**现在在哪一屏** | 要靠图谱的判据逐个比对当前界面 |
 * | `goto` | 一路点到指定那一屏 | 一条命令要走多步（重载 → 清场 → 逐步 tap） |
 *
 * 最后一条尤其是：`goto` 的每一步都依赖上一步的结果（tap 完要等、等不到要重试、
 * 重试还不行要报告卡在哪一步），而文件信箱是**一问一答**的，没法在中间插入判断。
 * 所以只能由插件这边驱动多次问答。
 */
const LOCAL_ACTIONS = ['map', 'where', 'goto'];

/** 模型可见的全部 action。 */
const ACTIONS = [...LOCAL_ACTIONS, ...PROBE_ACTIONS];

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死，理由与 `dsh-android-bubble` 同：
 * ① 什么场景该用（否则模型不知道有这个能力，会去猜、去瞎点）；
 * ② 字段有哪些、取值范围是多少（否则模型会猜字段名，静默无效）；
 * ③ **坐标系是什么**（这是最容易错的一点：CSS 坐标与屏幕物理坐标不是一回事，
 *    不写清楚模型会拿 CSS 坐标去 `input tap`，然后点到完全错误的位置）。
 */
const DESCRIPTION = `Inspect and operate THIS Android app's user interface — the DSH Web GUI rendered inside the app's WebView. You run in the Node process and have no DOM access, so this tool is your ONLY way to see or touch the app's buttons, inputs and panels.

## ⚡ Fastest path: jump straight to a screen

The app's UI has ~17 named screens (empty session, settings, plugin panel, model picker, session drawer, right sidebar, terminal, …). Each one is in a **built-in atlas** measured against the real frontend, with an exact route and a unique landmark.

- \`{action:"map"}\`: list all screens with their route. Add \`detail:true\` to also get every button of every screen.
- \`{action:"where"}\`: **answer "which screen am I on right now?"** — tries every screen's landmark against the live page. Use this first when you are unsure of the current state, instead of guessing from a \`dump\`.
- \`{action:"goto", screen:"<id>"}\`: drive the UI to that screen in one call — reload to a known base state, clear modal covers, collapse panels, then tap along the route and verify arrival. Returns \`ok:false\` with the exact step it got stuck on if it could not arrive.

**Prefer \`goto\` + \`where\` over hand-navigating with \`tap\`.** The frontend persists the session drawer's open state, the right sidebar's open state *and its content page*, and the current session — all **one-way**: once you have opened 工作区文件 or seeded a session, plain \`tap\` sequences can never get back to the empty-session screen. \`goto\` reloads into a clean base state first, which is the only reliable way back.

Typical loop:

\`\`\`
{action:"where"}                        # 1. where am I?
{action:"goto", screen:"settings"}      # 2. get where I need to be
{action:"dump"}                         # 3. read the real, live button positions
{action:"tap", text:"..."}              # 4. act
\`\`\`

⚠️ The atlas's coordinates are **reference values** measured on a 360×780 viewport at DPR 3 — a different device will differ. **Always locate by \`text\`/\`aria\` from a fresh \`dump\`, never by the atlas's numbers.**

## What you can do

- \`map\` / \`where\` / \`goto\`: see above — the screen atlas, current-screen identification, and one-call navigation.
- \`dump\`: list every interactive element (buttons, links, inputs, tabs…) with its exact on-screen position. **Start here** whenever you need to know what the UI currently looks like.
- \`tap\`: click an element. Takes a screenshot-free, precise DOM click (fires pointerdown/mousedown/pointerup/mouseup/click, like a real finger).
- \`type\`: write text into an input, done so React actually notices (native value setter + \`input\` event). Set \`enter:true\` to also press Enter.
- \`scroll\`: scroll the message list or any container; \`to:"bottom"\` jumps to the end.
- \`text\`: read the visible text of the page or a container — the fallback when you cannot recognise the UI.
- \`info\`: a one-shot summary (current path, focused element, scroll position, top buttons).
- \`wait\`: block until an element appears / disappears — use this after a tap that opens a panel.
- \`find\` / \`html\`: inspect one element in detail.
- \`reload\`: force the Web view to reload if the UI is broken/blank.

## ⚠️ Element targeting — how to name the thing you want

Give ONE of these (tried in this order):
1. \`sel\` — a CSS selector. Most precise; copy it from a previous \`dump\` result.
2. \`testid\` — the element's \`data-testid\`.
3. \`aria\` — the element's \`aria-label\` (best for icon-only buttons, which have no text).
4. \`text\` — the visible label, e.g. \`"发送"\`. Set \`exact:true\` to require a full match instead of substring.

Prefer \`sel\` from a \`dump\` you did **in the same turn** — React re-renders and element order changes, so a selector copied from long ago may hit a different element.

## ⚠️ Coordinates

Every element carries \`rect\`/\`center\` in **CSS viewport pixels** and, when the host can measure the view, \`screenRect\`/\`screenCenter\` in **physical screen pixels**. If you are going to drive a real touch (e.g. via a \`shell\`/\`adb\` tool with \`input tap\`), use \`screenCenter\`, never \`center\`. \`center\` is relative to the page viewport and will land on the wrong place.

## Practical notes

- The UI is a chat app: the composer input and its send button are the usual targets. \`dump\` with \`filter:"composer"\` finds them fast.
- After tapping something that opens a panel/menu, follow with \`wait\` (e.g. \`{action:"wait", text:"确认"}\`) before tapping inside it — the panel animates in and is not immediately clickable.
- \`dump\` only returns **visible** elements by default. Pass \`includeHidden:true\` to also see hidden ones (useful to diagnose "the button exists but I cannot click it").
- ⚠️ **A button can be visible and still unclickable — for TWO different reasons.** Every element carries \`covered\`/\`blockedBy\` (something is on top of it) and \`offscreen\` (its centre point is outside the viewport, so a finger has no coordinate to hit). \`dump\` also reports \`overlay\` + \`blocked\` + \`offscreen\` counts and a \`warning\` when a full-screen cover is present. **When \`covered\` is true, do not tap — deal with the cover first.** A common case: on a fresh profile the official frontend opens a "预览版说明" dialog whose button reads **「继续」**; until you tap it, all 16 buttons behind it are dead.
- ⚠️ **\`offscreen\` is the other "tapped but nothing happened".** The element is real and the events do get dispatched, but its centre is outside the viewport — e.g. the sidebar slides to a negative \`left\` once the drawer collapses — so a real touch cannot land on it and the screen does not change. The fix is the opposite of a cover: switch the UI into the state that panel belongs to (open the drawer first), then re-\`dump\` to confirm it is on screen. \`tap\` returns \`offscreen:true\` with a \`hint\` in that case, and \`wait\` with \`clickable:true\` will **not** report it as satisfied.
- ⚠️ **A full-screen PANEL is not a cover — do not try to dismiss it.** 设置 / 右侧边栏 / the session drawer are all \`position:fixed\` containers covering the whole viewport, and they look identical in the DOM to a modal backdrop. The difference: a backdrop (\`_mask_\`) is **empty** — its buttons are its *siblings* — while a panel contains the buttons of the current screen. \`dump\` reports a panel via the \`panel\` field with a \`note\`, and \`overlay\` returns \`present:false, kind:"panel"\`. **When you see a panel, tap the buttons inside it; the elements behind it being dead is normal, not a fault.** Only \`kind:"mask"\` means "close it first".
- \`{action:"overlay"}\` answers "what is covering the screen right now, and which buttons are actually live?" — it returns the cover plus the elements you can **really click right now**. (Deliberately *not* "the buttons inside the cover": in this frontend the dialog's buttons are **siblings** of the mask, not descendants, so a descendant search finds nothing.) Reach for it whenever \`covered\`/\`onTarget:false\` shows up. Its \`kind\` field distinguishes \`"mask"\` (a real cover — close it) from \`"panel"\` (your current screen — do not close it).
- \`{action:"dump", clickableOnly:true}\` drops everything currently covered **or off-screen**, so you get only what a finger could really hit right now.
- If \`tap\` reports \`onTarget:false\`, something is covering the element (a modal backdrop or loading mask) — read \`hitTopmost\`/\`blockedBy\` to see what actually received the click, and the returned \`hint\` says what to do next.
- Results include \`ok:false\` with an \`error\` when the element was not found or was not visible. **Always check \`ok\`** and report the failure instead of assuming the click worked.`;

/**
 * 把 `args` 里「对页面探针有意义的字段」挑出来。
 *
 * ⚠️ 白名单而不是黑名单：模型多传一个字段（比如把 `limit` 用在 `tap` 上）
 *   不该让整次调用失败，但也不该被原样送到页面里去（页面探针不认识它，
 *   只会增加 IPC 体积）。
 *
 * @param {object} args - 工具入参。
 * @returns {object} 页面探针能理解的命令体（不含 action/id）。
 */
function pickTarget(args) {
	const out = {};
	const passthrough = [
		'sel', 'testid', 'aria', 'text', 'exact',
		'filter', 'limit', 'includeHidden', 'clickableOnly',
		'dy', 'dx', 'to', 'container',
		'value', 'clear', 'enter', 'key',
		'ctrl', 'shift', 'alt', 'meta',
		'visible', 'clickable',
	];
	for (const key of passthrough) {
		if (args[key] !== undefined) out[key] = args[key];
	}
	return out;
}

/**
 * 校验一次调用，返回错误文本（合法时返回空串）。
 *
 * ⚠️ **校验必须发生在写信箱之前**：原生轮询线程是单线程的，一条注定失败的
 *   命令也会占住它一轮（`wait` 最长 12 秒）。
 *
 * @param {object} args - 工具入参。
 * @returns {string} 错误信息或空串。
 */
function validate(args) {
	const action = args.action;
	if (typeof action !== 'string' || !ACTIONS.includes(action)) {
		return `action 必须是以下之一: ${ACTIONS.join(' / ')}`;
	}
	const needsTarget = ['tap', 'find', 'type', 'wait'];
	if (needsTarget.includes(action)) {
		const has = ['sel', 'testid', 'aria', 'text'].some(
			(k) => typeof args[k] === 'string' && args[k].trim() !== '',
		);
		// `wait` 允许只带 visible:false 之外的东西；但至少要有一个定位条件。
		if (!has) {
			return `action:"${action}" 需要一个定位条件（sel / testid / aria / text 至少给一个）`;
		}
	}
	if (action === 'type' && args.value === undefined) {
		return 'action:"type" 需要 value（要给输入框写的内容；清空请传 clear:true 配合 value:""）';
	}
	if (action === 'key' && typeof args.key !== 'string') {
		return 'action:"key" 需要 key（如 "Enter"、"Escape"）';
	}
	if (action === 'scroll' && args.to === undefined && args.dy === undefined && args.dx === undefined) {
		return 'action:"scroll" 需要 to（"bottom"/"top"）或 dy/dx';
	}
	if (action === 'goto' && (typeof args.screen !== 'string' || args.screen.trim() === '')) {
		return 'action:"goto" 需要 screen（目标屏的 id 或标题，例如 "terminal"、"特权权限"）。'
			+ '用 {action:"map"} 列出全部可选值。';
	}
	return '';
}

/**
 * 发一条命令给原生，等回执。
 *
 * ⚠️ **本函数必须串行执行**，调用方走 [enqueue] 排队 —— 理由见那里的注释。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - `{action, ...}`。
 * @returns {Promise<object>} 原生回执（`{ok, error?, element?, ...}`）。
 */
async function sendCommand(home, id, payload) {
	const cmdPath = path.join(home, CMD_FILE);
	const resPath = path.join(home, RESULT_FILE);

	try {
		// 先把旧回执清掉：否则上一轮的 `ok` 会被当成本轮的答案。
		// 用 unlink 而不是写空对象 —— 写空对象会让「解析失败」与「还没处理」
		// 两种状态混在一起，只能靠 id 分辨，多一处出错的机会。
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
	// 超时不代表失败：原生侧可能只是慢（引擎刚启动、或主线程正忙）。
	// 但绝不能返回 ok —— 那会让模型以为操作成功了。
	return {
		ok: false,
		error: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。界面可能已经变化，也可能没有；`
			+ '请用 action:"info" 复查当前状态，确认后再向用户汇报。',
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
 * 把一个「定位参数」变成一问，问出它**此刻**在不在。
 *
 * ⚠️ **用 `find` 而不是 `wait`，这是性能要求不是风格问题。**
 *   原生 `runWait()` 会**一直重试到 10 秒上限**，而且**忽略**调用方传的 `timeout`
 *   （`UiProbeBridge.kt:390` 只用 `WAIT_MAX_MS`）。`where` 要对 17 屏各试一次 ——
 *   用 `wait` 的话，界面不在任何一屏时就是 **17 × 10 = 170 秒**。
 *   `find` 是单次检查，17 次往返秒级完成。
 *
 * ⚠️ `clickable` 必须**在这边**过滤：`find` 不像 `wait` 那样代劳。
 *   好在 `describe()` 回传的元素自带 `covered` / `offscreen` 两个字段
 *   （`ui-probe.js:391-393`），照 `wait` 那行（`ui-probe.js:1159`）的判据写即可。
 *   漏掉这一层，`hero` 的判据会在**任何**一屏「成立」—— 那个 composer 占位符
 *   在 12 屏里都存在，只是其中 11 屏被盖住了。
 *
 * @param {object} ctx - `{probe}` 问答函数。
 * @param {object} spec - `{text|aria|sel|testid, exact?, clickable?}`。
 * @returns {Promise<{ok: boolean, element: object|null}>} 该定位条件此刻是否成立。
 */
async function checkExpect(ctx, spec) {
  if (!spec) return { ok: false, element: null };
  const reply = await ctx.probe({ action: 'find', ...spec });
  if (reply?.ok !== true || !reply.element) return { ok: false, element: null };
  const el = reply.element;
  if (spec.clickable === true && (el.covered || el.offscreen)) {
    return { ok: false, element: el };
  }
  return { ok: true, element: el };
}

/**
 * 判断**现在在哪一屏**。
 *
 * 17 屏的判据**互斥**（`tools/ui-map-expect-audit.mjs` 机械保证每屏判据只在
 * 自己那一屏成立），所以逐个试、命中的那个就是答案。全部不命中时要如实返回
 * 「认不出」而不是猜一个 —— 猜错的后果是模型对着错误的界面点按钮。
 *
 * ⚠️ 逐个试的代价是 17 次页面往返，但**都在原生侧一次 eval 内完成不了**
 *   （探针的 wait 是单次检查，重试在原生侧）。所以这里只做「一次检查」而不等待：
 *   判据都要求元素**已经在场**，界面若还在动画中，认不出是正确行为，重试交给调用方。
 *
 * @param {object} ctx - `{probe}` 问答函数。
 * @returns {Promise<object>} `{screen, matches, unknown}`。
 */
async function detectScreen(ctx) {
  const matches = [];
  for (const s of ATLAS.screens) {
    const r = await checkExpect(ctx, s.expect);
    if (r.ok) matches.push({ id: s.id, title: s.title });
  }
  return {
    matches,
    // 判据互斥 ⇒ 正常情况最多命中一个；命中多个说明图谱或界面有异常，如实报出来
    screen: matches.length === 1 ? matches[0] : null,
  };
}

/**
 * 串行闸门：把并发调用排成一队。
 *
 * ⚠️ **这不是可选优化，是正确性要求。**
 *
 * 原生侧只有**一个**轮询线程，且只认**一个**命令文件（`ui-cmd.json`）+ **一个**
 * 结果文件。两条命令同时写进去，后一条会**覆盖**前一条 —— 于是：
 *
 * - 前一条命令**从未被执行**（模型以为点了，界面没动）；
 * - 而它的 `id` 再也等不到回执，`sendCommand` 会一路超时到 20 秒；
 * - 更糟的是后一条的结果可能被前一条按 `id` 判为「不是给我的」而丢弃。
 *
 * 症状是「偶发、看起来随机」的失败 —— 这类 bug 最难查，所以在这里用最直白的
 * 办法根除：**同一时刻只允许一条命令在信箱里**。
 *
 * 模型确实会并发调用工具（同一轮里并行发起多个调用），所以这个闸门是必需的，
 * 不是防御性的。
 */
let gate = Promise.resolve();

/**
 * 排队执行一次「发命令 + 等回执」。
 * @param {() => Promise<object>} task - 要串行执行的任务。
 * @returns {Promise<object>} 任务结果。
 */
function enqueue(task) {
	// 用 .then(task, task) 而不是 .then(task)：前一个任务失败（抛异常）时闸门
	// 也必须放行，否则一次失败会**永久卡死**后续所有调用。
	const next = gate.then(task, task);
	// 闸门本身永远不 reject —— 错误由 task 的返回值（而非异常）承载。
	gate = next.then(() => undefined, () => undefined);
	return next;
}

/**
 * 「一键到屏」：把界面开到图谱里指定的那一屏。
 *
 * ## 为什么不能只做「沿路线点一遍」
 *
 * 因为**进去过就回不去**。这个前端会持久化三样东西（见 `ui-map-real-frontend.mjs`
 * 顶部那张表），全都**单向**：
 *
 * 1. 右侧边栏的开关状态 —— 重载后它自己又开着；
 * 2. 右侧边栏的**内容页** —— 点过「新建终端」，再打开就直接落在终端页，
 *    启动页那两个入口不再出现；
 * 3. **当前会话** —— 种过会话就回不到空会话态。
 *
 * 所以路线必须**每屏各自从基态重新走**（那是图谱生成时的做法），而「回到基态」
 * 只能靠 `reload`（重载能清掉 1 和 2），3 清不掉 —— 那正是图谱把
 * `drawer` / `right-sidebar` / `workfiles` / `terminal` 排在最后的原因。
 *
 * 这里的实现**照抄图谱生成器的策略**，一条不差：
 *
 * ```
 * reload → 等页面起来 → 清遮挡层 → 收面板 → 沿路线逐步 tap → 用判据确认
 * ```
 *
 * ## 两处必须与生成器一致的细节
 *
 * - **清场用 `overlay` 的 `kind`，不是 `present`**：`present` 把「面板」也算成
 *   覆盖层（语义是「有没有全屏层」），照着它循环会去关掉**当前屏幕本身**。
 * - **重试是原地重点，不是重载重来**：抽屉要点两次是 mobile-ui 的时序竞态，
 *   重载再点会撞上同一个竞态，永远进不去（图谱生成器实测三次全失败）。
 *
 * @param {object} ctx - `{probe, log}`。
 * @param {object} spec - 目标屏（来自 `findScreen`）。
 * @returns {Promise<object>} 给 render 的结果对象。
 */
async function goToScreen(ctx, spec) {
  const steps = [];
  const tick = (msg) => steps.push(msg);

  // ① 先看是不是已经在这一屏了 —— 绝大多数调用会命中这一支，
  //    省掉一次重载（重载要等 2–5 秒，且会打断用户正在看的界面）。
  const here = await checkExpect(ctx, spec.expect);
  if (here.ok) {
    return { ok: true, screen: spec.id, title: spec.title, already: true, steps };
  }

  // ② 回到基态
  const rl = await ctx.probe({ action: 'reload' });
  tick(`重载界面（${rl?.note ?? rl?.error ?? '已触发'}）`);
  // 重载是异步的，且这里是**没有回调**的原生 reload —— 只能等一个保守的时长。
  // 等短了会在空白页上点空；等长了拖慢每次 goto。4 秒与图谱生成器的节奏一致。
  await ctx.sleep(4000);

  // 等探针重新装填（重载后页面世界的 `window.__DSH_UI_PROBE__` 没了）
  let ready = null;
  for (let i = 0; i < 10; i++) {
    ready = await ctx.probe({ action: 'ping' });
    if (ready?.ok === true) break;
    await ctx.sleep(800);
  }
  tick(ready?.ok === true ? '界面已重新加载' : '⚠️ 界面重载后没能确认探针就绪');

  // ③ 清遮挡层（弹窗）：**按 `kind` 判，不是按 `present`** —— 见函数头注释
  for (let i = 0; i < 5; i++) {
    const ov = await ctx.probe({ action: 'overlay' });
    if (ov?.kind !== 'mask') break;
    const live = ov.clickable ?? [];
    const closer = live.find((e) => /继续|知道了|关闭|确定|稍后|跳过/.test(e.text || ''))
      ?? live[live.length - 1];
    if (!closer) break;
    await ctx.probe({ action: 'tap', sel: closer.sel });
    tick(`关掉遮挡层「${closer.text || closer.sel}」`);
    await ctx.sleep(700);
  }

  // ④ 收掉面板：**同样按 `kind` 判**。面板是内容页（工作区文件/终端）的容器，
  //    不收掉的话下一次打开侧边栏会直接落在那个内容页上，启动页的入口点不到。
  for (let i = 0; i < 3; i++) {
    const ov = await ctx.probe({ action: 'overlay' });
    if (ov?.kind !== 'panel') break;
    const live = ov.clickable ?? [];
    // 关闭优先级：内容页自己的「关闭」→ 启动页的「收起侧边栏」→ 泛化的关闭/返回。
    // ⚠️ 顺序不能反：「收起右侧边栏」**不重置内容页**，光收侧边栏的话下次打开
    //    还是那个内容页 —— 那正是图谱生成器最初「找不到要点击的元素」的根因。
    const closer = live.find((e) => /^关闭$/.test(e.text || ''))
      ?? live.find((e) => /收起.*边栏|收起侧边栏|收起面板/.test(e.text || ''))
      ?? live.find((e) => /^返回$/.test(e.text || ''))
      ?? live.find((e) => /关闭|返回/.test(e.text || ''));
    if (!closer) {
      tick(`⚠️ 面板「${ov.panel?.cls || ov.panel?.tag}」里找不到关闭按钮，无法回到基态`);
      break;
    }
    await ctx.probe({ action: 'tap', sel: closer.sel });
    tick(`收掉面板「${closer.text || closer.sel}」`);
    await ctx.sleep(1600);
  }

  // ⑤ 沿路线走
  for (const step of spec.enter) {
    const label = step.text ?? step.aria ?? step.sel;
    const r = await ctx.probe({ action: 'tap', ...step });
    const ok = r?.ok === true && r?.onTarget !== false && r?.offscreen !== true;
    tick(`tap「${label}」 ${ok ? '✓' : `✗ ${r?.error ?? r?.hint ?? ''}`}`);
    await ctx.sleep(900);

    // ⑥ 等判据成立 —— 只在**最后一步**等（中间步骤没有判据可用）。
    //
    // ⚠️ 这里的 `wait` **不传 `timeout`**：原生 `runWait()` 根本不读它，
    //   固定轮询到 `WAIT_MAX_MS`（10 秒，`UiProbeBridge.kt:390`）。传一个
    //   数字只会让读代码的人以为它在生效。
    //   所以「一次 wait」本身就是「最多等 10 秒」，外面**不该**再套 sleep 循环。
    const isLast = step === spec.enter[spec.enter.length - 1];
    if (!isLast) continue;

    let arrived = (await ctx.probe({ action: 'wait', ...spec.expect }))?.satisfied === true;
    // 等不到 ⇒ **原地重点**（不是重载重来）。重载会撞上同一个时序竞态，
    // 永远进不去 —— 图谱生成器在会话抽屉上实测三次全失败，就是踩了这个坑。
    for (let attempt = 2; !arrived && attempt <= 3; attempt++) {
      const again = await ctx.probe({ action: 'tap', ...step });
      tick(`↻ 没等到，重点「${label}」（第 ${attempt} 次）ok=${again?.ok}`);
      await ctx.sleep(1200);
      arrived = (await ctx.probe({ action: 'wait', ...spec.expect }))?.satisfied === true;
    }
    if (!arrived) {
      return {
        ok: false, screen: spec.id, title: spec.title, steps,
        error: `点了「${label}」之后没等到「${JSON.stringify(spec.expect)}」出现，`
          + '界面可能停在了别的屏。用 action:"where" 看现在到底在哪。',
      };
    }
  }

  // ⑦ 无路线的屏（hero）：清场完靠判据确认（同样只等一次，理由见 ⑥）
  if (spec.enter.length === 0) {
    const arrived = (await ctx.probe({ action: 'wait', ...spec.expect }))?.satisfied === true;
    if (!arrived) {
      return {
        ok: false, screen: spec.id, title: spec.title, steps,
        error: '清场之后也没能回到这一屏（判据不成立）。',
      };
    }
  }

  return { ok: true, screen: spec.id, title: spec.title, already: false, steps };
}

/**
 * 把回执渲染成模型读得懂的**文本**。
 *
 * ⚠️ 这里刻意不用「把 JSON 原样丢回去」的做法。工具结果是要**被模型读**的，
 *   而 `dump` 的原始 JSON 动辄几百行；渲染成紧凑的一行一个元素，模型才能在
 *   有限的上下文里真正看懂界面。同时原始数据仍以 `details` 形式附回。
 *
 * @param {object} args - 原始调用。
 * @param {object} reply - 原生回执。
 * @returns {string} 给模型看的文本。
 */
function render(args, reply) {
	const lines = [];
	const action = args.action;

	if (reply.ok !== true) {
		lines.push(`✗ 操作失败：${reply.error ?? '未知原因'}`);
		if (reply.element) lines.push(`  找到的元素: ${fmtElement(reply.element)}`);
		if (reply.spec) lines.push(`  定位条件: ${JSON.stringify(reply.spec)}`);
		return lines.join('\n');
	}

	// ── map：内建的 17 屏图谱 ───────────────────────────────────────
	if (action === 'map') {
		lines.push(`✓ 内建图谱 ${ATLAS.screens.length} 屏（每屏按钮表由 PC 侧对真实前端实测生成）`);
		lines.push(`  这 ${ATLAS.screens.length} 屏的判据**互斥**，所以 where 能唯一确定现在在哪。`);
		lines.push('');
		for (const s of ATLAS.screens) {
			const route = s.enter.length === 0
				? '（基态）'
				: s.enter.map((x) => x.text ?? x.aria ?? x.sel).join(' → ');
			lines.push(`  ${s.id.padEnd(18)} ${s.title}`);
			lines.push(`    ${' '.repeat(18)} 路线: ${route}`);
			// ⚠️ 不列全部按钮 —— 17 屏 195 个按钮会把上下文淹没。
			//   要用具体按钮时用 where 之后接 dump，那时拿到的是**当前屏幕**的。
			if (reply.detail === true) {
				for (const [text, cls, x, y, role] of s.els) {
					lines.push(`    ${' '.repeat(18)}   [${role}] "${text}" CSS(${x},${y})${cls ? ` .${cls}` : ''}`);
				}
			}
		}
		lines.push('');
		lines.push('要看某一屏的按钮表: {action:"goto", screen:"<id>"} 之后接 {action:"dump"}。');
		lines.push('要确认现在在哪: {action:"where"}。');
		return lines.join('\n');
	}

	// ── where：现在在哪一屏 ─────────────────────────────────────────
	if (action === 'where') {
		if (reply.screen) {
			lines.push(`✓ 现在在「${reply.screen.title}」（${reply.screen.id}）`);
			const s = ATLAS.screens.find((x) => x.id === reply.screen.id);
			if (s) {
				lines.push(`  这一屏的按钮（图谱实测，共 ${s.els.length} 个）:`);
				for (const [text, cls, x, y, role] of s.els) {
					lines.push(`    [${role}] "${text}" CSS(${x},${y})${cls ? ` .${cls}` : ''}`);
				}
			}
		} else if ((reply.matches ?? []).length > 1) {
			lines.push(`⚠️ 认不准：有 ${reply.matches.length} 屏的判据同时成立 `
				+ `（${reply.matches.map((m) => m.title).join(' / ')}）。`);
			lines.push('  图谱的判据本该互斥 —— 这是图谱或前端的异常，请用 dump 直接看界面。');
		} else {
			lines.push('✗ 认不出现在在哪一屏。');
			lines.push('  可能界面还在加载/动画中，也可能这一屏还没被内建图谱覆盖。');
			lines.push('  用 {action:"dump"} 看当前实际有什么，或 {action:"overlay"} 看有没有弹窗盖着。');
		}
		return lines.join('\n');
	}

	// ── goto：一键到屏 ──────────────────────────────────────────────
	if (action === 'goto') {
		if (reply.already) {
			lines.push(`✓ 已经就在「${reply.title}」（${reply.screen}），没有做任何改动。`);
		} else {
			lines.push(`✓ 已开到「${reply.title}」（${reply.screen}）`);
		}
		if ((reply.steps ?? []).length > 0) {
			lines.push('  过程:');
			for (const s of reply.steps) lines.push(`    ${s}`);
		}
		lines.push('  下一步: 用 {action:"dump"} 拿这一屏的实时按钮位置，或 {action:"where"} 复核。');
		return lines.join('\n');
	}

	// ── ping / info：一句话讲清"现在是什么界面" ──────────────────────
	if (action === 'ping') {
		lines.push(`✓ 探针就绪 · ${reply.url ?? ''}`);
		lines.push(`  可交互元素 ${reply.interactive ?? '?'} 个 · 视口 ${fmtViewport(reply.viewport)}`);
		return lines.join('\n');
	}

	if (action === 'info') {
		lines.push(`✓ 当前界面: ${reply.path ?? ''}${reply.hash ? ` ${reply.hash}` : ''}`);
		lines.push(`  视口 ${fmtViewport(reply.viewport)}`);
		if (reply.focused?.text) {
			lines.push(`  焦点元素: ${reply.focused.tag}「${reply.focused.text}」${fmtScreen(reply.focused)}`);
		}
		if (reply.scrollable) {
			lines.push(`  滚动容器: ${reply.scrollable.sel}（${reply.scrollable.top}/${reply.scrollable.height}，可视 ${reply.scrollable.view}）`);
		}
		if (Array.isArray(reply.top) && reply.top.length > 0) {
			lines.push(`  主要按钮:`);
			for (const el of reply.top) lines.push(`    ${fmtElement(el)}`);
		}
		return lines.join('\n');
	}

	// ── dump：界面元素清单 ──────────────────────────────────────────
	if (action === 'dump') {
		const list = reply.elements ?? [];
		lines.push(`✓ 找到 ${reply.matched ?? list.length} 个可交互元素，返回前 ${list.length} 个（视口 ${fmtViewport(reply.viewport)}）`);
		// ⚠️ 遮挡警告必须排在元素清单**之前**：模型最容易犯的错是扫一眼清单就
		//   开始挨个 tap。清单本身看着完全正常（元素都有尺寸），只有这一行能
		//   拦住它。
		if (reply.overlay) {
			lines.push(`  ⚠️ 有遮挡层盖住界面（${reply.blocked ?? 0} 个元素当前点不动）:`);
			lines.push(`    ${fmtElement(reply.overlay)}`);
			lines.push(`    ${reply.warning ?? ''}`);
			// ⚠️ 别写成「看遮挡层里面有哪些按钮」：官方前端的弹窗是遮罩与内容
			//   **平级**的（`_root_ > [_mask_, _dialog_]`），`overlay` 报的因此是
			//   「此刻真正能点的」而不是「里面的」—— 照那句理解会找不到按钮。
			lines.push(`    先关掉它（用 overlay 动作看此刻真正能点的是哪几个），再操作下面的元素。`);
		} else if (reply.panel) {
			// ⚠️ **面板屏绝不能套用遮挡那套措辞。** 设置 / 右侧边栏 / 会话抽屉都是
			//   覆盖全屏的容器，探针早期版本把它们一律报成「遮挡层」，于是这里会
			//   打出一句「先关掉它」—— 模型照着去点「返回」，那一屏就永远进不去
			//   （实测踩过）。面板就是**当前屏幕**，它里面的按钮可以正常点。
			lines.push(`  ℹ️ 当前屏幕是一个覆盖全屏的面板（${(reply.panel.cls || reply.panel.tag)}），` +
				`它**里面**的按钮可以正常点。`);
			lines.push(`    ${reply.note ?? ''}`);
		} else if ((reply.blocked ?? 0) > 0) {
			lines.push(`  ⚠️ 其中 ${reply.blocked} 个被上层元素盖住，现在点不动（见元素行上的「被遮挡←」）。`);
		}
		// ⚠️ 「屏幕外」要单独说一句，别和遮挡混在一起：两者的处置办法**相反** ——
		//   遮挡是「先关掉挡在前面的东西」，屏幕外是「先把界面切到它所在的状态」。
		if ((reply.offscreen ?? 0) > 0) {
			lines.push(`  ⚠️ 另有 ${reply.offscreen} 个中心点在视口外（见元素行上的「屏幕外·点不到」）—— ` +
				`它们在屏幕上没有可点的坐标，点了不会有反应。先把对应面板/状态切出来。`);
		}
		if (list.length === 0) {
			lines.push('  （没有匹配的元素。界面可能还在加载，或 filter 太窄。）');
		}
		for (const el of list) lines.push(`  ${fmtElement(el)}`);
		lines.push('');
		lines.push('用返回的 sel 或 text 做 tap/type 的定位条件。');
		return lines.join('\n');
	}

	// ── overlay：现在谁盖着屏幕、此刻真正能点什么 ────────────────────
	if (action === 'overlay') {
		const live = reply.clickable ?? [];
		// ⚠️ 先判 `kind`，再判 `present`。`present` 的语义是「**有没有**覆盖全屏的
		//   层」（面板也算），`blocking` 才是「它挡不挡着下面的东西」（只有空遮罩
		//   算）。把 `present` 读成「有遮挡」会把面板屏渲染成「当前有遮挡层」，
		//   模型就会去关掉一个本该留着的屏幕。
		if (reply.kind === 'panel') {
			lines.push(`✓ 当前屏幕是一个覆盖全屏的面板（${(reply.panel?.cls || reply.panel?.tag)}）—— ` +
				`它不是遮挡层，不用关掉它。`);
			lines.push(`  它**里面**的按钮就是现在能操作的（共 ${live.length} 个）:`);
			for (const el of live) lines.push(`    ${fmtElement(el)}`);
			lines.push(`  它**下面**那层元素点不动是正常的。要回到下面那层，用面板自己的关闭/返回按钮。`);
			return lines.join('\n');
		}
		if (!reply.present) {
			lines.push(`✓ 当前没有遮挡层，界面可以正常操作。`);
			if (live.length > 0) {
				lines.push(`  现在能点的是这 ${live.length} 个:`);
				for (const el of live) lines.push(`    ${fmtElement(el)}`);
			}
			return lines.join('\n');
		}
		lines.push('✓ 当前有遮挡层（空遮罩，里面一个可点元素都没有）:');
		lines.push(`  ${fmtElement(reply.layer)}`);
		// ⚠️ 报「此刻真正能点的」而不是「遮挡层里面的」：官方前端的弹窗是
		//   遮罩与内容**平级**（`_root_ > [_mask_, _dialog_]`），按后代找会得到
		//   0 个，而屏幕上明明有个「继续」能点。
		if (live.length === 0) {
			lines.push('  当前没有任何可点元素（可能是纯加载蒙层，等它自己消失）。');
		} else {
			lines.push(`  此刻只有这 ${live.length} 个元素是真能点的 —— 先处理它们:`);
			for (const el of live) lines.push(`    ${fmtElement(el)}`);
		}
		return lines.join('\n');
	}

	// ── 单个元素类动作 ──────────────────────────────────────────────
	if (action === 'find') {
		lines.push(`✓ 找到元素:`);
		lines.push(`  ${fmtElement(reply.element)}`);
		if (reply.element?.html) lines.push(`  HTML: ${reply.element.html}`);
		return lines.join('\n');
	}

	if (action === 'tap') {
		const el = reply.element;
		lines.push(`✓ 已点击 ${el ? `「${el.text || el.sel}」` : ''}`);
		if (el) lines.push(`  ${fmtElement(el)}`);
		// ⚠️ **必须把「点着了没有」明说出来**，因为 `ok:true` 完全不能表达它：
		//   tap 有三种「执行成功但没点着」的情形（遮挡 / 屏幕外 / 面板之下），
		//   三种都返回 `ok:true`。而模型拿到一句光秃秃的「✓ 已点击」之后，
		//   无从判断下一步该不该信这次点击 —— 真机上就出现过模型点完直接往下走、
		//   而界面纹丝不动的情况。
		//
		//   所以 `onTarget === true` 时也要**主动给一句确认**，而不是只在失败时说话。
		//   这行的措辞刻意用「事件送达目标」，不写成「点击生效」：事件送达 ≠ 界面响应，
		//   后者要靠下一步 dump 看。
		if (reply.onTarget === true && !reply.offscreen) {
			lines.push(`  ✓ 事件已送达目标元素本身（onTarget:true）—— 不是被上层吃掉的点击。`);
		}
		// ⚠️ 「屏幕外」必须排在遮挡判断**之前**、且**独立成支**：它的
		//   `onTarget` 是 true（事件确实发到了目标元素上），走不进下面那个
		//   `onTarget === false` 的分支 —— 而它恰恰是「点了没反应」的第二种成因。
		if (reply.offscreen) {
			lines.push(`  ⚠️ 该元素中心点在视口外 —— 事件发出去了，但屏幕上没有可点的坐标，` +
				`真实手指点不到，界面不会有任何反应。`);
			if (reply.hint) lines.push(`    ${reply.hint}`);
		} else if (reply.onTarget === false) {
			// ⚠️ 被「面板」盖住与被「空遮罩」盖住，处置办法相反：前者是「你要点
			//   的东西根本不在当前屏幕上」，后者是「先把弹窗关掉」。探针把两者
			//   分开报（`panel` vs `overlay`），这里必须跟着分开，否则模型会拿着
			//   「关掉遮挡层」的思路去关一个本该留着的面板。
			if (reply.panel) {
				lines.push(`  ⚠️ 该元素在当前面板**下面**（面板是「${reply.panel.cls || reply.panel.tag}」），` +
					`所以点不到 —— 它不在你现在的屏幕上。`);
			} else if (reply.hitTopmost) {
				lines.push(`  ⚠️ 点击被上层元素接收（元素被遮挡，真机手指会点到那层而不是它）:`);
				lines.push(`    ${fmtElement(reply.hitTopmost)}`);
			} else {
				// ⚠️ 第三态：`onTarget:false` 但**没有任何元素**接到事件。这时说
				//   「被上层接收」是错的 —— 没有上层。真实成因是事件落到了空白处
				//   （元素刚被重渲染掉、或坐标处本来就没有东西）。
				//   第一版把它并进「被遮挡」分支，模型会照着去查 overlay 白费一轮。
				lines.push(`  ⚠️ 点击没有落到任何元素上（onTarget:false，且没有上层元素接收）。` +
					`多半是元素在点击瞬间被重渲染掉了 —— 用 dump 确认它还在，再点一次。`);
			}
			if (reply.hint) lines.push(`    ${reply.hint}`);
		}
		lines.push(`  事件: ${(reply.dispatched ?? []).join(', ')}`);
		lines.push(`  提示: 界面变化是异步的 —— 若这一步应打开面板，接着用 wait 等它出现。`);
		return lines.join('\n');
	}

	if (action === 'type') {
		lines.push(`✓ 已写入输入框（方式: ${reply.how}）`);
		if (reply.element) lines.push(`  ${fmtElement(reply.element)}`);
		// ⚠️ 报出 `cleared` 是为了让「清空到底做没做」可见。`clear:true` 对
		//   `contenteditable`（官方 composer 是 Lexical）**不能**靠写空串实现 ——
		//   真机上那是 no-op，旧内容会留下、新内容被追加在后面，而回执里的
		//   `value` 是一长串拼接结果，模型完全看不出是自己没清掉。
		if (reply.cleared) lines.push(`  已先清空原内容（方式: ${reply.cleared}）`);
		lines.push(`  当时读到的值: ${JSON.stringify(reply.value ?? '')}`);
		// ⚠️ `applied` 是三态，别把它当布尔用：
		//   true  = 同步已确认写入；
		//   null  = 写入已发出但**异步生效**（contenteditable/Lexical 就是这样，
		//           同步读必然为空），**不是失败** —— 下一步复核即可；
		//   false = 真的没写进去。
		if (reply.applied === false) {
			lines.push(`  ⚠️ 内容没有真正写进去（期望 ${JSON.stringify(args.value ?? '')}）。`);
			lines.push(`    若目标是 contenteditable，先 tap 点一下它再 type；或改用 key 动作逐字输入。`);
		} else if (reply.applied === null) {
			lines.push(`  ℹ️ 写入是异步生效的（富文本编辑器），此刻还读不到内容属正常。`);
			lines.push(`    下一步用 find/text 复核它，或直接检查「发送」按钮是否已从禁用变可用。`);
		}
		if (reply.submitted) lines.push(`  已按下 Enter（事件: ${reply.submitted.join(', ')}）`);
		return lines.join('\n');
	}

	if (action === 'key') {
		lines.push(`✓ 已按键 ${args.key ?? ''}（事件: ${(reply.dispatched ?? []).join(', ')}）`);
		if (reply.target) lines.push(`  目标: ${fmtElement(reply.target)}`);
		return lines.join('\n');
	}

	if (action === 'scroll') {
		if (reply.moved === false) {
			lines.push(`⚠️ 滚动没有产生位移（容器: ${reply.container}，位置仍是 ${reply.after}）`);
			lines.push(`  可能已经到头了，或这个容器不可滚动。`);
		} else {
			lines.push(`✓ 已滚动（容器: ${reply.container}）${reply.before} → ${reply.after}`);
		}
		return lines.join('\n');
	}

	if (action === 'text') {
		lines.push(`✓ 可见文本 ${reply.total ?? 0} 行${reply.truncated ? `（只返回前 ${reply.lines?.length ?? 0} 行）` : ''}:`);
		for (const line of reply.lines ?? []) lines.push(`  ${line}`);
		return lines.join('\n');
	}

	if (action === 'html') {
		lines.push(`✓ HTML（${reply.length} 字符${reply.length > (reply.html?.length ?? 0) ? '，已截断' : ''}）:`);
		lines.push(reply.html ?? '');
		return lines.join('\n');
	}

	if (action === 'wait') {
		if (reply.satisfied === true) {
			lines.push(`✓ 条件已成立（第 ${reply.attempts} 次检查）`);
			if (reply.element) lines.push(`  ${fmtElement(reply.element)}`);
		} else {
			lines.push(`✗ 等待超时，条件始终没有成立（检查了 ${reply.attempts} 次）`);
			lines.push(`  元素可能没出现、或一直不可见。用 action:"dump" 看当前界面到底有什么。`);
		}
		return lines.join('\n');
	}

	if (action === 'reload') {
		lines.push(`✓ ${reply.note ?? '已触发重载'}`);
		return lines.join('\n');
	}

	lines.push(`✓ ${action} 完成`);
	return lines.join('\n');
}

/** 视口一行摘要。 */
function fmtViewport(vp) {
	if (!vp) return '未知';
	return `${vp.w}×${vp.h} CSS（dpr ${vp.dpr}，滚动 ${vp.scrollX},${vp.scrollY}，文档 ${vp.docW}×${vp.docH}）`;
}

/** 屏幕坐标摘要（拿不到时给空串）。 */
function fmtScreen(el) {
	if (!el?.screenCenter) return '';
	return ` · 屏幕(${el.screenCenter.x},${el.screenCenter.y})`;
}

/**
 * 一个元素渲染成一行。
 *
 * 形状：`[role] "文本" · sel · CSS中心(x,y) · 屏幕中心(x,y) [状态]`
 *
 * ⚠️ 屏幕坐标**有就一定要显示**：模型要用 `input tap` 真机点击时，唯一能用的
 *   就是它，而 CSS 坐标看起来"也对"，用错了极难发现。
 */
function fmtElement(el) {
	if (!el) return '(无)';
	const parts = [];
	parts.push(`[${el.role || el.tag}]`);
	if (el.text) parts.push(`"${el.text}"`);
	else if (el.testid) parts.push(`testid=${el.testid}`);
	if (el.sel) parts.push(`sel=${el.sel}`);
	const c = el.center;
	if (c) parts.push(`CSS(${c.x},${c.y})`);
	if (el.screenCenter) parts.push(`屏幕(${el.screenCenter.x},${el.screenCenter.y})`);
	const r = el.rect;
	if (r) parts.push(`${r.w}×${r.h}`);
	const flags = [];
	if (el.enabled === false) flags.push('已禁用');
	if (el.checked !== null && el.checked !== undefined) flags.push(el.checked ? '已选中' : '未选中');
	if (el.inViewport === false) flags.push('不在视口内');
	// ⚠️ 与上一行**不是同一件事**，两个都要留：
	//   「不在视口内」= 完全没有一部分露在屏幕里（滚动列表里滚过去的项）；
	//   「屏幕外」    = 中心点在视口外 ⇒ 真手指**没有可点的坐标**，点了也不会有反应。
	//   后者才是「能不能点」的判据。实测抽屉收起后侧栏滑到 `left:-312`，
	//   它 `inViewport` 仍可能是 true（有一角露着），但中心点已在屏幕外。
	if (el.offscreen) flags.push('屏幕外·点不到');
	// ⚠️ 「被盖住」必须直接写在元素这一行上。只在 dump 顶部报一次是不够的：
	//   模型是逐行扫这个清单的，遮挡信息跟元素分离时它会对被挡的那几条照点不误。
	if (el.covered) {
		const by = el.blockedBy;
		flags.push(by ? `被遮挡←${by.cls || by.tag || by.sel}` : '被遮挡');
	}
	if (flags.length > 0) parts.push(`(${flags.join('，')})`);
	return parts.join(' · ');
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/uiagent-tool/` 下，
 *   那里没有 `node_modules`，ESM 解析够不到引擎的 `@deepseek-ai/dsh-tools`。
 *   理由与做法与 `dsh-android-shell` / `dsh-android-bubble` 完全一致。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function uiTool(home) {
	/** 请求编号。进程内自增即可 —— 只有本插件会写这个信箱。 */
	let seq = 0;

	/**
	 * 向探针问一次（走同一个串行闸门）。
	 *
	 * ⚠️ **本地动作必须通过这个函数间接调用探针**，不能自己另开一个
	 *   `sendCommand` 通道 —— 闸门守卫的是「信箱里同一时刻只有一条命令」，
	 *   绕过它就等于把并发覆盖的问题放回来。
	 */
	const probe = (payload) => enqueue(() => sendCommand(home, ++seq, payload));

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
					description: 'What to do. "map" lists the built-in screen atlas; "where" tells you which screen you are on right now; "goto" drives the UI to a named screen (see `screen`); "dump" lists interactive elements with positions; "tap" clicks one; "type" fills an input; "scroll" scrolls; "text" reads visible text; "info" summarises the current screen; "wait" blocks until an element appears; "ping" checks the probe is alive; "find"/"html" inspect one element; "overlay" reports what is currently covering the screen and which buttons inside it are live; "reload" reloads a broken Web view.',
				},
				screen: {
					type: 'string',
					description: 'For action:"goto" — which screen to open. Accepts an atlas id (e.g. "settings-priv", "terminal") or a screen title / part of one (e.g. "特权权限"). Call {action:"map"} to list them all.',
				},
				detail: {
					type: 'boolean',
					description: 'For action:"map" — also list every button of every screen (long; the default is just the routes).',
				},
				sel: { type: 'string', description: 'CSS selector for the target element. Most precise — copy it from a previous dump.' },
				testid: { type: 'string', description: 'Target by data-testid.' },
				aria: { type: 'string', description: 'Target by aria-label. Best for icon-only buttons that have no text.' },
				text: { type: 'string', description: 'Target by visible label, e.g. "发送". A LOCATOR only (used by tap/type/wait/find) — to supply text to write into an input use `value` instead.' },
				exact: { type: 'boolean', description: 'With text: require a full match rather than a substring match.' },
				filter: { type: 'string', description: 'For action:"dump" — only return elements whose text/testid/class/selector contains this substring. Great for narrowing to e.g. "composer".' },
				limit: { type: 'number', description: 'For dump/text — max results (dump default 60, max 300).' },
				includeHidden: { type: 'boolean', description: 'For dump — also list hidden elements. Useful to diagnose "the button exists but cannot be clicked".' },
				clickableOnly: { type: 'boolean', description: 'For dump — drop every element that is currently covered by something else OR whose centre is outside the viewport, leaving only what a real finger could hit right now.' },
				value: { type: 'string', description: 'The text to write into the input, for action:"type". (`value`, not `text` — `text` is the element locator.)' },
				clear: { type: 'boolean', description: 'For action:"type" — empty the input before writing.' },
				enter: { type: 'boolean', description: 'For action:"type" — also press Enter after writing.' },
				key: { type: 'string', description: 'For action:"key" — the key to press, e.g. "Enter", "Escape", "Tab".' },
				ctrl: { type: 'boolean', description: 'Hold Ctrl for action:"key".' },
				shift: { type: 'boolean', description: 'Hold Shift for action:"key".' },
				alt: { type: 'boolean', description: 'Hold Alt for action:"key".' },
				meta: { type: 'boolean', description: 'Hold Meta/Cmd for action:"key".' },
				to: { type: 'string', enum: ['top', 'bottom'], description: 'For action:"scroll" — jump to the top or bottom.' },
				dy: { type: 'number', description: 'For action:"scroll" — vertical pixels to scroll (negative scrolls up).' },
				dx: { type: 'number', description: 'For action:"scroll" — horizontal pixels to scroll.' },
				container: { type: 'string', description: 'For action:"scroll" — CSS selector of the scroll container. Omit to auto-detect the main scrollable area.' },
				visible: { type: 'boolean', description: 'For action:"wait" — require the element to be visible (default true). Pass false to wait for it to disappear.' },
				clickable: { type: 'boolean', description: 'For action:"wait" — also require the element NOT to be covered by anything AND to be on screen (centre inside the viewport), i.e. wait until it is genuinely tappable.' },
			},
		},
		output: {
			schema: {
				type: 'object',
				properties: {
					ok: { type: 'boolean' },
					message: { type: 'string' },
					// 原始回执：形状随 action 变（dump 是数组、tap 是元素对象、
					// wait 带 satisfied…），所以这里**不能**收窄成固定形状，
					// 否则真机上会以 ToolOutputError 的形式炸在渲染之前。
					details: {},
				},
				required: ['ok', 'message'],
			},
			render: (_args, value) => [{ type: 'text', text: value.message }],
		},
		async execute(args) {
			const bad = validate(args ?? {});
			if (bad !== '') return { ok: false, message: bad };

			// ── 本地动作：map / where / goto ─────────────────────────────
			//
			// ⚠️ 这三条**绝不能**原样写进信箱：探针不认识它们，会回
			//   「未知 action: map」—— 而那是**真机专属**的错误，PC 侧跑不到。
			const action = args.action;
			if (LOCAL_ACTIONS.includes(action)) {
				const ctx = { probe, sleep };

				if (action === 'map') {
					const reply = { ok: true, detail: args.detail === true };
					return { ok: true, message: render(args, reply), details: reply };
				}

				if (action === 'where') {
					const r = await detectScreen(ctx);
					const reply = { ok: true, screen: r.screen, matches: r.matches };
					return { ok: true, message: render(args, reply), details: reply };
				}

				// goto：必须先解析目标屏 —— 拼错时如实报「没有这一屏」并把
				// 可选 id 列出来，而不是猜一个最像的（猜错的代价是模型在
				// 错误的界面上继续点，而它以为自己到了）。
				const spec = findScreen(args.screen);
				if (!spec) {
					return {
						ok: false,
						message: `✗ 图谱里没有「${args.screen ?? ''}」这一屏。\n`
							+ `  可用: ${ATLAS.screens.map((s) => s.id).join(', ')}\n`
							+ '  先用 {action:"map"} 看每屏的标题与路线。',
					};
				}
				const reply = await goToScreen(ctx, spec);
				return { ok: reply.ok === true, message: render(args, reply), details: reply };
			}

			// ⚠️ **字段原样透传，一个字都不翻译。**
			//
			// 第一版在这里做了 `text` ↔ `value` 的翻译，结果制造了一个极隐蔽的
			// bug：`tap{text:"发送"}` 的定位条件被当成"输入内容"处理、
			// 转手就把 `text` 删了 —— 于是所有按文本定位的 tap/find/wait 全部
			// 报「找不到元素」，而报错文案完全指不到真因。
			//
			// 页面探针对两个字段的语义已经分得很清楚：`text` = 按可见标签**定位**，
			// `value` = 要写进输入框的**内容**。两边字段名一致，就不需要中间这层
			// 翻译，也就没有再搞错的机会。
			// ⚠️ 走串行闸门：原生只有一个轮询线程 + 一个命令文件，并发写会互相覆盖。
			//   详见 [enqueue] 的注释。
			const reply = await probe({ ...args });
			const text = render(args, reply);
			return {
				ok: reply.ok === true,
				message: text,
				// 原始回执附回去：模型需要精确值（比如 screenCenter）时可以读
				details: reply,
			};
		},
	};
}

/**
 * 注册 `ui` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-uiagent] 未配置 DSH_HOME，ui 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-uiagent] DSH_HOME 不存在（${home}），ui 工具未注册`);
		return;
	}

	// ⚠️ **register 必须包 try/catch**：见 `dsh-android-bubble` 同处的说明 ——
	// 工具注册抛异常会让 cordis 判定插件 failed，进而
	// `dsh: startup failed: N required plugin did not activate` ⇒ 引擎退出、
	// App 完全打不开。真机实测过（`dsh-android-logs` 漏 `output` 声明，54 次）。
	try {
		ctx.tools.register(uiTool(home));
		ctx?.logger?.info?.(`[dsh-android-uiagent] 已注册 UI 调试工具 ${TOOL_NAME}: ${path.join(home, CMD_FILE)}`);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-uiagent] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
