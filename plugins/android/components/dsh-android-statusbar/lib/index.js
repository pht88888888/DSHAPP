/**
 * dsh-android-statusbar —— 把「改系统栏配色」暴露成一个**模型可调用的工具**。
 *
 * ## 两条栏分别是什么（这是本工具最容易理解错的地方）
 *
 * | 名字 | 位置 | 显示内容 |
 * |---|---|---|
 * | 状态栏 statusBar | 屏幕**最顶上**那一条 | 时间、电量、信号、通知图标 |
 * | 导航栏 navBar | 屏幕**最底下**那一条 | 返回 / Home / 最近任务（或手势横条） |
 *
 * 两者**各自独立**：只改顶上那条、底部继续跟页面走，是完全正常的用法。
 *
 * ## 为什么需要这一层
 *
 * 改动之前，这两条栏的颜色**只由页面底色驱动**（页面侧 `mpPageBackground()` 上报，
 * 原生 `StatusBridge.onStatus` 收到后一并刷）。那条路对**模型**是关着的：
 *
 * | 谁 | 能不能改系统栏 | 为什么 |
 * |---|---|---|
 * | 页面侧插件 | 只能间接（改页面底色） | 系统栏是原生窗口属性，页面碰不到 |
 * | 模型（AI） | ❌ | 它的工具跑在 **Node 进程**里，那里没有 Activity |
 *
 * 本插件补的就是这一段：注册一个 `statusbar` 工具，让它出现在工具列表里、
 * 且真的能改到原生那两条栏。
 *
 * ## 跨进程怎么打通
 *
 * 引擎 node 是 App 进程的**子进程**，两者共享 app 私有目录（SELinux 同域），
 * 所以走**文件信箱**：本插件把命令写进 `$DSH_HOME/statusbar-cmd.json`，原生侧
 * `SystemBarCommandBridge` 轮询读走、执行、把结果写回 `$DSH_HOME/statusbar-result.json`。
 *
 * 与 `dsh-android-bubble` / `dsh-android-uiagent` 完全同构，**但信箱文件必须独立**：
 * 三条通道各自从 `id=1` 自增，共用文件会让工具调用收到别人的回执。
 *
 * ⚠️ 请求带自增 `id`，结果按 id 配对 —— 否则「两个工具调用并发、后一个的结果被
 *   前一个读走」会让模型拿到张冠李戴的回执。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或目录不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-statusbar
 */

import fs from 'node:fs';
import path from 'node:path';

export const name = 'dsh-android-statusbar';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具及 `bubble` / `ui` / `shell` / `root` 都不冲突。 */
const TOOL_NAME = 'statusbar';

/** 文件信箱里两个文件名，原生侧 `SystemBarCommandBridge` 必须与这里一致。 */
const CMD_FILE = 'statusbar-cmd.json';
const RESULT_FILE = 'statusbar-result.json';

/** 等待原生侧回执的上限。原生轮询间隔 250ms，留足余量即可。 */
const WAIT_TIMEOUT_MS = 4000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 60;

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死，理由与 `dsh-android-bubble` 同：
 * ① 什么场景该用（否则模型不知道有这个能力）；
 * ② 两条栏的区别与「跟随页面底色」这个默认语义（否则模型会以为不传字段是"没改"）；
 * ③ 改了之后**用户能不能立刻看到**（启动页还在时看不到，这是反直觉的）。
 */
const DESCRIPTION = `Change the colour of the Android system bars (系统栏) of this app — the STATUS BAR along the very top (clock / battery / notification icons) and the NAVIGATION BAR along the very bottom (back / home / recents, or the gesture pill). This is the only way for you to affect them; a page-side plugin can only change the page background, and you run in the Node process where there is no Activity.

The two bars are **independent** — changing only the top one and leaving the bottom one alone is a normal, supported use.

Use it when the user asks to change / restyle the colour of the top bar, the bottom bar, the status bar, the navigation bar, or says the top/bottom edge does not match the app. Also use \`action:"get"\` first when the user wants a change *relative to* the current look, so you do not silently overwrite what they set before.

## The default is "follow the page" — that is what an empty string means

By default both bars simply mirror the page background colour, so they match the app as the
user switches themes. That state is represented as an **empty string**:

- **field omitted**  → leave that bar exactly as it is now.
- **field ""** (empty string) → hand that bar BACK to the page (follow the page background again).
- **field "#RRGGBB"** → pin that bar to this colour, no matter what the page does.

So "reset everything to default" is \`action:"reset"\`, and "let the bottom bar follow the
page again but keep my top bar" is \`{"navBar": ""}\`.

## Icon contrast is automatic — do NOT try to set it

The clock / battery icons are dark or light based on the luminance of the colour that
actually ends up on that bar (ITU-R BT.601 weights). You cannot set it, and you do not need
to: pick a colour and the icons will stay readable. This is deliberate — letting a caller
pick the icon colour is the single easiest way to produce an invisible status bar. When a bar
is flashing, the decision is recomputed **per frame**, so a sequence that alternates light and
dark colours stays readable on every frame.

## Flashing / cycling colours — pass a SEQUENCE, do not loop tool calls

The bars can flash through a list of colours. You pass the whole list once and the **native
side runs the loop itself**; you do NOT call this tool once per frame.

This matters: each tool call takes a full round trip (model → file mailbox → native poll →
apply → reply), and measured frame-to-frame latency when a model drove the loop by hand was
**7.6 to 27.9 seconds**. A model-driven "flashing" loop therefore cannot flash at all. One
call with a sequence is the only thing that works.

- \`statusBarFlash\` / \`navBarFlash\`: arrays of "#RRGGBB" colours. The two bars flash
  **independently** — one can flash while the other is pinned or following the page, and they
  may have different lengths (each wraps around on its own).
- \`flashIntervalMs\`: milliseconds per frame (30–60000, default 120).
- \`flashDurations\`: optional per-frame durations, applied in order and wrapping like the
  colours. Overrides \`flashIntervalMs\` for the frames it covers.
- \`flashLoops\`: how many full cycles to run before stopping (-1 = forever, the default).
- \`flashRunning\`: \`true\`/\`false\`. **Sending a colour array implies true** — you only ever
  need to pass \`false\` to stop. After the loops finish, the bars settle on the pinned colour
  (\`statusBar\` / \`navBar\`), not on a random frame.
- \`action:"stopflash"\` stops the flashing and **keeps the pinned colours**. Use
  \`action:"reset"\` instead if the user wants to go back to following the page entirely.

## Fullscreen / 全面屏 — the base layer that makes any background cover the whole screen

By default the app window starts BELOW the status bar: the page cannot paint into that top strip
at all, so a background image / gradient / colour stops short of the top and the user sees a
visible band. **\`fullscreen: true\` removes that limitation** — the content is laid out across the
entire screen (from y=0 to the very bottom) and both bars become transparent overlays floating on
top of the page.

This is the **base layer**, deliberately not a "background image" special case: turn it on and
*whatever* the page draws — solid colour, gradient, image, video — covers the full screen with no
further work. Turn it on when the user asks for a background image, an immersive / edge-to-edge
look, "make the background fill the screen", or complains about a band along the top.

- \`fullscreen: true\` → both bars transparent, content extended to the whole screen, and the app
  tells the page how tall the status bar is so it can keep its own UI clear of the clock.
- \`fullscreen: false\` → back to normal: the window starts below the status bar again.
- **\`fullscreen\` and the flash fields are mutually exclusive.** Fullscreen keeps both bars
  transparent so the page shows through; flashing paints the bars a solid colour. If both are set,
  fullscreen wins and nothing flashes. Pick one per request — "immersive" and "flashing bars" are
  different intents.

### Icon contrast under fullscreen — this is when you SHOULD set it

While fullscreen is on, "the colour of the bar" no longer exists: the bars are transparent and
whatever is behind them is the page's own content — an image, a gradient, a rounded card. So the
automatic contrast calculation has nothing reliable to work with and can pick the wrong one, making
the clock unreadable.

That is what \`statusBarIcons\` / \`navBarIcons\` are for: set them to \`"light"\` (white icons) or
\`"dark"\` (black icons) based on what is actually behind the bar. **Look at the background and
decide** — dark background → \`"light"\`; light/colourful background → \`"dark"\`. They apply per bar.
Leave them \`"auto"\` (the default) for the ordinary non-fullscreen case, where the automatic choice
is correct.

## Actions

- \`action:"set"\` — apply \`statusBar\` / \`navBar\` and/or the flash fields (default).
- \`action:"get"\` — read the current configuration back as JSON. Empty strings mean "following the page".
- \`action:"stopflash"\` — stop the flashing, keep the colours.
- \`action:"reset"\` — clear BOTH bars back to following the page background (also stops any flashing).

## Fields (all optional; merged onto the current configuration — pass only what you change)

- \`statusBar\`: the TOP bar. "#RRGGBB" or "#AARRGGBB" (with alpha), or "" to follow the page.
- \`navBar\`: the BOTTOM bar. Same format.
- The five flash fields listed above.

## Recipes for common asks

- "make the top bar black" → \`{statusBar: "#000000"}\`
- "make both bars match my theme, dark blue" → \`{statusBar: "#0F1B33", navBar: "#0F1B33"}\`
- "the top bar is fine, fix the bottom one" → \`{navBar: "#111111"}\`
- "put the bottom bar back to normal" → \`{navBar: ""}\`
- "flash the top bar in seven rainbow colours" → \`{statusBarFlash: ["#FF0000","#FF7F00","#FFFF00","#00FF00","#00FFFF","#0000FF","#8B00FF"]}\`
- "party mode, both bars, quite fast" → \`{statusBarFlash: ["#FF0000","#00FF00","#0000FF"], navBarFlash: ["#FF0000","#00FF00","#0000FF"], flashIntervalMs: 150, flashLoops: -1}\`
- "blink the top bar between red and green three times, then stop" → \`{statusBarFlash: ["#FF0000","#00FF00"], flashIntervalMs: 300, flashLoops: 3}\`
- "set a background image and make it fill the whole screen" → \`{fullscreen: true}\` (plus \`statusBarIcons\` if the image is light or dark)
- "make it immersive / edge-to-edge / no black band at the top" → \`{fullscreen: true}\`
- "put it back the way it was" → \`{fullscreen: false}\`
- "stop the flashing please" → \`{action: "stopflash"}\`
- "undo everything, let them follow the app again" → \`{action: "reset"}\`

⚠️ **Separate the flash from the loop-that-cannot-run.** If a user asks for flashing and you
find yourself about to issue one call per colour, stop: that design has already been tried on
a real device and it visibly does not work. Send the sequence instead.

⚠️ **On some devices the navigation bar colour is ignored**: Android 10+ gesture navigation
draws a translucent bar over the app and some vendors (notably Huawei) force it. If the user
reports "the bottom one did not change", that is a platform limitation, not a failure of this
tool — say so instead of retrying. The status bar is honoured on every device.

⚠️ **The bars only become visible after the app's startup layer is dismissed** (a few seconds
after launch). If you change the colour while the app is still booting, the change is saved and
will show up when the app finishes loading; do not conclude it failed.

Invalid colours are ignored (the offending entry is dropped from the sequence; a bad single
colour keeps the previous value) and unknown fields are ignored, so a typo does not blank out
the other bar. The result tells you the configuration as it is *after* the change; check it if
you need to be sure.`;

/** 下发的字段全集。加字段时**这里和原生 `SystemBarAppearance.merge` 必须一起改**。 */
const PLAIN_FIELDS = ['statusBar', 'navBar', 'statusBarIcons', 'navBarIcons'];

/** 颜色数组字段（每项都要是合法颜色）。 */
const LIST_FIELDS = ['statusBarFlash', 'navBarFlash'];

/** 整数数组字段。 */
const INT_LIST_FIELDS = ['flashDurations'];

/** 整数标量字段。 */
const INT_FIELDS = ['flashIntervalMs', 'flashLoops'];

/** 布尔字段。 */
const BOOL_FIELDS = ['flashRunning', 'fullscreen'];

/**
 * 把一条参数压成要下发的字段集合。
 *
 * ⚠️ **空串必须保留**（与 `dsh-android-bubble` 的 `pickFields` 相反）：那里的空串是
 *   "没传"，这里的空串是"把这条栏交还给页面"这个**合法取值**。一刀切丢掉空串会让
 *   调用方没法把某一条栏单独恢复默认。
 *
 * ⚠️ **数组字段不做本地校验就下发**：颜色/时长的合法性判定在原生侧（那里才有
 *   `Color.parseColor` 那套），本地再抄一份必然与原生分叉。这里只负责"传下去"。
 *   唯一的例外是**类型**：把字符串塞进 `statusBarFlash` 会让原生整组回退，
 *   而模型看到的是"没生效"，所以这里把非数组直接丢掉并让调用方收到明确报错。
 */
function pickFields(args) {
	const fields = {};
	for (const key of PLAIN_FIELDS) {
		const value = args?.[key];
		if (value === undefined || value === null) continue;
		fields[key] = String(value).trim();
	}
	for (const key of [...LIST_FIELDS, ...INT_LIST_FIELDS]) {
		const value = args?.[key];
		if (value === undefined || value === null) continue;
		if (!Array.isArray(value)) continue;
		fields[key] = value;
	}
	for (const key of INT_FIELDS) {
		const value = args?.[key];
		if (value === undefined || value === null) continue;
		if (typeof value !== 'number' || !Number.isFinite(value)) continue;
		fields[key] = Math.trunc(value);
	}
	for (const key of BOOL_FIELDS) {
		const value = args?.[key];
		if (value === undefined || value === null) continue;
		if (typeof value !== 'boolean') continue;
		fields[key] = value;
	}
	return fields;
}


/** 写文件（先写临时文件再 rename —— 原生侧可能正读到一半）。 */
function writeJsonAtomic(target, value) {
	const tmp = `${target}.tmp`;
	fs.writeFileSync(tmp, JSON.stringify(value), 'utf8');
	fs.renameSync(tmp, target);
}

/** 读 JSON；文件不存在/内容不完整时给 null（原生正在写的那一瞬间会读到半个）。 */
function readJson(target) {
	try {
		return JSON.parse(fs.readFileSync(target, 'utf8'));
	} catch {
		return null;
	}
}

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

/**
 * 把一条命令交给原生侧并等回执。
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - `{action, fields}`。
 * @returns {Promise<{ok: boolean, message: string, appearance?: string}>} 结果。
 */
const commandGates = new Map();

function sendCommand(home, id, payload) {
	const key = path.resolve(home);
	const previous = commandGates.get(key) ?? Promise.resolve();
	const run = () => sendCommandNow(home, id, payload);
	const next = previous.then(run, run);
	const settled = next.then(() => undefined, () => undefined);
	commandGates.set(key, settled);
	void settled.then(() => {
		if (commandGates.get(key) === settled) commandGates.delete(key);
	});
	return next;
}

// 同一信箱的写入与完整回执等待必须一起排队，异常也释放后续请求。
async function sendCommandNow(home, id, payload) {
	const cmdPath = path.join(home, CMD_FILE);
	const resPath = path.join(home, RESULT_FILE);

	try {
		// 先把旧回执清掉：否则上一轮的 `ok` 会被当成本轮的答案。
		// 用 unlink 而不是写空对象 —— 写空对象会让「解析失败」与「还没处理」
		// 两种状态混在一起，只能靠 id 分辨，多一处出错的机会。
		try { fs.unlinkSync(resPath); } catch { /* 不存在就是已经干净了 */ }
		writeJsonAtomic(cmdPath, { id, ...payload });
	} catch (error) {
		return { ok: false, message: `写入文件信箱失败: ${error?.message ?? error}` };
	}

	const deadline = Date.now() + WAIT_TIMEOUT_MS;
	while (Date.now() < deadline) {
		await sleep(POLL_INTERVAL_MS);
		const reply = readJson(resPath);
		if (reply === null || reply.id !== id) continue;
		return {
			ok: reply.ok === true,
			message: typeof reply.message === 'string' ? reply.message : '',
			appearance: typeof reply.appearance === 'string' ? reply.appearance : undefined,
		};
	}
	// 超时不代表失败：原生侧可能只是慢（引擎刚启动、或主线程正忙）。
	// 但绝不能返回 ok —— 那会让模型以为改成功了。
	return {
		ok: false,
		message: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。配色可能已经改好，也可能没有；`
			+ '请用 action:"get" 复查，确认后再向用户汇报。',
	};
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/statusbar-tool/` 下，
 * 那里没有 `node_modules`，ESM 解析够不到引擎的 `@deepseek-ai/tools`。
 * 理由与做法与 `dsh-android-bubble` 完全一致（那里有更详细的说明）。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function statusBarTool(home) {
	/** 请求编号。进程内自增即可 —— 只有本插件会写这个信箱。 */
	let seq = 0;

	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			properties: {
				action: {
					type: 'string',
					enum: ['set', 'get', 'stopflash', 'reset'],
					description: '"set" applies the fields below; "get" only reads the current configuration back; "stopflash" stops the flashing but keeps the pinned colours; "reset" clears BOTH bars so they follow the page background again (and stops any flashing).',
				},
				statusBar: {
					type: 'string',
					description: 'The TOP bar (clock / battery / notification icons). "#RRGGBB" or "#AARRGGBB", or "" to hand it back to the page so it follows the page background again. Omit to leave it unchanged.',
				},
				navBar: {
					type: 'string',
					description: 'The BOTTOM bar (back / home / recents, or the gesture pill). "#RRGGBB" or "#AARRGGBB", or "" to hand it back to the page. Omit to leave it unchanged.',
				},
				statusBarFlash: {
					type: 'array',
					items: { type: 'string' },
					description: 'Colours for the TOP bar to cycle through, e.g. ["#FF0000","#00FF00","#0000FF"]. The native side runs the loop itself — send the whole list once, never call this tool per frame. Pass [] to stop that bar flashing.',
				},
				navBarFlash: {
					type: 'array',
					items: { type: 'string' },
					description: 'Same for the BOTTOM bar. The two bars flash independently and may have different sequence lengths.',
				},
				flashIntervalMs: {
					type: 'number',
					description: 'Milliseconds each frame stays on screen (30–60000, default 120). Values outside the range are ignored.',
				},
				flashDurations: {
					type: 'array',
					items: { type: 'number' },
					description: 'Optional per-frame durations in ms, applied in order and wrapping like the colours. Overrides flashIntervalMs for the frames it covers.',
				},
				flashLoops: {
					type: 'number',
					description: 'How many full cycles to run before stopping; -1 (default) = forever.',
				},
				flashRunning: {
					type: 'boolean',
					description: 'Whether the flashing runs. Sending statusBarFlash/navBarFlash implies true, so you only need this to pass false to stop. Distinct from action:"stopflash" only in that it is a field.',
				},
				fullscreen: {
					type: 'boolean',
					description: '全面屏 / immersive edge-to-edge: lay the content out across the WHOLE screen and make both bars transparent, so any page background (image / gradient / colour) covers the full screen. This is the base layer for background images — no special handling needed. Set false to go back to the normal layout. Mutually exclusive with the flash fields (fullscreen wins).',
				},
				statusBarIcons: {
					type: 'string',
					enum: ['auto', 'light', 'dark'],
					description: 'Colour of the clock / battery icons on the TOP bar: "light" = white icons, "dark" = black icons, "auto" = derive from the bar colour (default, correct in the normal non-fullscreen case). Set it explicitly when fullscreen is on, because the bars are then transparent and the automatic choice can be wrong.',
				},
				navBarIcons: {
					type: 'string',
					enum: ['auto', 'light', 'dark'],
					description: 'Same for the BOTTOM bar icons. Set it explicitly when fullscreen is on.',
				},
			},
			required: ['action'],
		},
		output: {
			schema: {
				type: 'object',
				additionalProperties: false,
				properties: {
					ok: { type: 'boolean' },
					appearance: { type: 'string' },
					message: { type: 'string' },
				},
				required: ['ok', 'appearance', 'message'],
			},
			render: (_args, value) => [{ type: 'text', text: renderText(value) }],
		},
		presentCall: (args) => ({
			card: 'generic',
			title: args.action === 'get'
				? '读取系统栏配色'
				: args.action === 'stopflash'
					? '停止系统栏闪烁'
					: args.action === 'reset' ? '恢复系统栏默认' : '修改系统栏配色',
			description: describeCall(args),
		}),
		async execute(args) {
			const action = typeof args?.action === 'string' ? args.action : 'set';

			if (action === 'get') {
				const reply = await sendCommand(home, ++seq, { action: 'get' });
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? '已读取当前系统栏配色' : reply.message,
				};
			}

			if (action === 'reset') {
				const reply = await sendCommand(home, ++seq, { action: 'reset' });
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? '已清空两条栏的钦定色与闪烁，回到跟随页面底色' : reply.message,
				};
			}

			if (action === 'stopflash') {
				const reply = await sendCommand(home, ++seq, { action: 'stopflash' });
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? '已停止闪烁，钦定色保留' : reply.message,
				};
			}

			const fields = pickFields(args ?? {});
			if (Object.keys(fields).length === 0) {
				return {
					ok: false,
					appearance: '',
					message: '没有传入任何字段 —— 想读当前配置请用 action:"get"，'
						+ '想停止闪烁请用 action:"stopflash"，想恢复默认请用 action:"reset"。',
				};
			}

			const reply = await sendCommand(home, ++seq, { action: 'set', fields });
			return {
				ok: reply.ok,
				appearance: reply.appearance ?? '',
				message: reply.ok
					? `已应用：${summarizeFields(fields)}`
					: reply.message,
			};
		},
	};
}

/** 把下发的字段压成一行摘要给模型看。空串要显式说成"跟随页面"，否则看起来像没改。 */
function summarizeFields(fields) {
	const parts = [];
	for (const [key, value] of Object.entries(fields)) {
		if (key === 'statusBar' || key === 'navBar') {
			const label = key === 'statusBar' ? '状态栏(顶)' : '导航栏(底)';
			parts.push(value === '' ? `${label}:跟随页面底色` : `${label}:${value}`);
			continue;
		}
		if (key === 'statusBarIcons' || key === 'navBarIcons') {
			const label = key === 'statusBarIcons' ? '状态栏(顶)' : '导航栏(底)';
			parts.push(value === 'auto' ? `${label}图标:自动` : `${label}图标:${value === 'light' ? '浅色' : '深色'}`);
			continue;
		}
		if (key === 'statusBarFlash' || key === 'navBarFlash') {
			const label = key === 'statusBarFlash' ? '状态栏(顶)' : '导航栏(底)';
			parts.push(value.length === 0
				? `${label}闪烁:已停`
				: `${label}闪烁:${value.length} 色 [${value.join(' ')}]`);
			continue;
		}
		if (key === 'fullscreen') {
			parts.push(value ? '全面屏:开启（两条栏透明，任何背景都铺满全屏）' : '全面屏:关闭');
			continue;
		}
		// ⚠️ 数组用 join 而不是默认的 toString：默认形式对模型是 `1,2,3`，
		//   看不出这是"逐帧时长表"还是别的什么。
		parts.push(`${key}:${Array.isArray(value) ? `[${value.join(' ')}]` : value}`);
	}
	return parts.join(', ');
}

/** 把一次调用压成一行给用户看的说明。 */
function describeCall(args) {
	if (args?.action === 'get') return '读取状态栏与导航栏的当前配色';
	if (args?.action === 'reset') return '两条栏都恢复为跟随页面底色，并停止闪烁';
	if (args?.action === 'stopflash') return '停止系统栏闪烁（钦定色保留）';
	const bits = [];
	if (args?.fullscreen === true) bits.push('开启全面屏（背景铺满全屏）');
	if (args?.fullscreen === false) bits.push('关闭全面屏');
	if (args?.statusBar !== undefined) {
		bits.push(args.statusBar === '' ? '状态栏跟随页面' : `状态栏 ${args.statusBar}`);
	}
	if (args?.navBar !== undefined) {
		bits.push(args.navBar === '' ? '导航栏跟随页面' : `导航栏 ${args.navBar}`);
	}
	if (args?.statusBarIcons !== undefined && args.statusBarIcons !== 'auto') {
		bits.push(`状态栏图标${args.statusBarIcons === 'light' ? '浅色' : '深色'}`);
	}
	if (args?.navBarIcons !== undefined && args.navBarIcons !== 'auto') {
		bits.push(`导航栏图标${args.navBarIcons === 'light' ? '浅色' : '深色'}`);
	}
	for (const [key, label] of [['statusBarFlash', '状态栏闪烁'], ['navBarFlash', '导航栏闪烁']]) {
		const value = args?.[key];
		if (Array.isArray(value)) {
			bits.push(value.length === 0 ? `${label}停止` : `${label} ${value.length} 色`);
		}
	}
	if (typeof args?.flashIntervalMs === 'number') bits.push(`每帧 ${args.flashIntervalMs}ms`);
	if (typeof args?.flashLoops === 'number') {
		bits.push(args.flashLoops < 0 ? '无限循环' : `${args.flashLoops} 圈`);
	}
	return bits.length > 0 ? bits.join(' · ') : '应用系统栏配色';
}

/**
 * 把结果渲染成模型可见文本。
 *
 * ⚠️ **`appearance` 是 JSON 字符串，这里要解出来额外说一句"正在闪"**：光看那串
 *   JSON，模型得自己去找 `flashRunning` 加两个序列才敢下结论，而它最容易犯的错
 *   恰恰是「序列设了，但被 `flashRunning:false` 顶掉了」——那种状态下它以为在闪、
 *   用户看到的不闪。
 *
 * ⚠️ **`hasFlash` 在这里算，不从 `appearance` 读**：原生侧的 `toJson()` 刻意
 *   不放派生量（理由见那里的注释），所以这一层要自己从两个序列推。
 */
function renderText(value) {
	const lines = [];
	lines.push(value.ok ? '结果: 成功' : '结果: 失败');
	if (value.appearance !== '') {
		lines.push(`当前配色: ${value.appearance}`);
		try {
			const state = JSON.parse(value.appearance);
			const hasFlash = Array.isArray(state?.statusBarFlash) && state.statusBarFlash.length > 0
				|| Array.isArray(state?.navBarFlash) && state.navBarFlash.length > 0;
			// ⚠️ 全面屏要显式报出来：它一开，两条栏的颜色就被忽略了（透明）。
			//   不说的话模型会看到 "statusBar: #FF0000" 却看不到红色，从而反复重设。
			if (state?.fullscreen === true) {
				lines.push('全面屏: 已开启（两条栏透明，页面背景铺满全屏；此时配色字段不生效）');
			}
			if (hasFlash) {
				if (state?.fullscreen === true) {
					// 全面屏与闪烁互斥，这里如实说，免得模型以为"设了闪却没闪是坏了"
					lines.push('闪烁: 颜色序列已保存，但被全面屏抑制（关掉全面屏才会闪）');
				} else if (state?.flashRunning === true) {
					lines.push('闪烁: 正在运行（由原生自驱，不需要你再调这个工具去推进帧）');
				} else {
					lines.push('闪烁: 已停止（颜色序列还在，用 flashRunning:true 可以重新开始）');
				}
			}
			const icons = [];
			if (state?.statusBarIcons && state.statusBarIcons !== 'auto') {
				icons.push(`顶栏${state.statusBarIcons === 'light' ? '浅色' : '深色'}`);
			}
			if (state?.navBarIcons && state.navBarIcons !== 'auto') {
				icons.push(`底栏${state.navBarIcons === 'light' ? '浅色' : '深色'}`);
			}
			if (icons.length > 0) lines.push(`图标明暗: ${icons.join('、')}（其余为自动）`);
		} catch {
			// appearance 不是 JSON 就不多嘴 —— 主结果已经在了
		}
	}
	if (value.message !== '') lines.push(value.message);
	return lines.join('\n');
}

/**
 * 注册 `statusbar` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-statusbar] 未配置 DSH_HOME，statusbar 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-statusbar] DSH_HOME 不存在（${home}），statusbar 工具未注册`);
		return;
	}

	// ⚠️ **register 必须包 try/catch**：见 `dsh-android-bubble` 同处的说明 ——
	// 工具注册抛异常会让 cordis 判定插件 failed，进而
	// `dsh: startup failed: N required plugin did not activate` ⇒ 引擎退出、
	// App 完全打不开。真机实测过（`dsh-android-logs` 漏 `output` 声明，54 次）。
	try {
		ctx.tools.register(statusBarTool(home));
		ctx?.logger?.info?.(
			`[dsh-android-statusbar] 已注册系统栏配色工具 ${TOOL_NAME}: ${path.join(home, CMD_FILE)}`,
		);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-statusbar] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
