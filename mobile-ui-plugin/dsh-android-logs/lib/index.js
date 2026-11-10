/**
 * dsh-android-logs —— 把「看日志、导出诊断包」暴露成一个**模型可调用的工具**。
 *
 * ## 为什么需要这一层
 *
 * 日志系统建好之后有两个入口是给**人**的：设置页那个分节、启动/崩溃浮层上那个
 * 按钮。但模型自己看不到日志 —— 它跑在 **Node 进程**里，而日志的权威副本在
 * 原生侧的内存缓冲与 `filesDir/logs` 下（`app.log` / `engine.log` / `web.log` /
 * 崩溃现场 / 事件时间线）。
 *
 * 于是会出现这种对话：
 *
 * > 用户：刚才那条回复报错了，你看看日志
 * > 模型：（读不到日志，只能猜）
 *
 * 本插件补的就是这一段：注册一个 `logs` 工具，让模型**自己**能：
 *
 * | action | 做什么 |
 * |---|---|
 * | `read` | 读最近 N 行日志，可按级别/来源/关键字筛 |
 * | `crashes` | 列出崩溃现场，并读某一份的全文 |
 * | `summary` | 一眼看结论：上次怎么退出的、有几个错误、几份崩溃现场 |
 * | `export` | 生成诊断包（zip），返回路径 —— 模型可据此让用户上传/发送 |
 *
 * ## 跨进程怎么打通
 *
 * 与 `dsh-android-bubble` **完全同构**（照抄，不另造协议）：引擎 node 是 App
 * 进程的子进程，两者共享 app 私有目录，所以走**文件信箱** ——
 * 本插件把请求写进 `$DSH_HOME/logs-cmd.json`，原生侧 `LogCommandBridge`
 * 轮询读走、执行、把结果写回 `$DSH_HOME/logs-result.json`。
 *
 * ⚠️ **独立文件，不复用 bubble 那一对**。理由与 `BubbleCommandBridge.STATE_FILE`
 *   的注释一样：回执按自增 `id` 配对，两个独立进程里的两个独立计数器必然撞
 *   （同一个 `id=5` 同时存在）⇒ 模型收到张冠李戴的回执。而这类错配的表现是
 *   "AI 说日志里没有这条错误"，**看起来像是真的查过了**，比报错危险得多。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或目录不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-logs
 */

import fs from 'node:fs';
import path from 'node:path';

export const name = 'dsh-android-logs';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具不冲突（`bubble` / `shell` / `ui` 之外没有同名的）。 */
const TOOL_NAME = 'logs';

/** 文件信箱里两个文件名，原生侧 `LogCommandBridge` 必须与这里一致。 */
const CMD_FILE = 'logs-cmd.json';
const RESULT_FILE = 'logs-result.json';

/**
 * 等待原生侧回执的上限。
 *
 * ⚠️ 比 bubble 的 4 秒**长得多**，因为 `export` 要打包几 MB 的 zip（读文件、
 *   算行数、压缩）。4 秒会稳定超时，而超时会被模型当成"导出失败"。
 *   原生轮询间隔 200ms，10 秒对打包足够。
 */
const WAIT_TIMEOUT_MS = 10000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 80;

/**
 * 单次 `read` 返回的最大行数。
 *
 * ⚠️ 这是**上下文预算**的限制，不是能力限制。日志一天能到几十万行，一次全塞进
 *   上下文会让这一轮直接爆掉 —— 而模型看到的是"工具返回了超长内容"，
 *   它不会自己意识到该少要点。所以在这里硬截断，并在结果里**说明被截断了**，
 *   让模型知道可以再要一次（带上更精确的筛选）。
 */
const MAX_LINES = 500;

/** 单次返回的文本总字节上限（防止超长单行把上下文撑爆）。 */
const MAX_TEXT_BYTES = 256 * 1024;

/** 崩溃现场全文的最大字符数。同上，超长要截断并说明。 */
const MAX_CRASH_CHARS = 40000;

/** 合法的日志级别。与原生侧 `LogStore.severity` 对应。 */
const LEVELS = ['all', 'warn', 'error'];

/**
 * 合法的日志来源。与原生侧 `LogViewerActivity` 的筛选项一致。
 *
 * ⚠️ **`'crash'` 是个名不副实的取值，但保留它。** 原生侧 `matchesSource` 里
 *   它落进 `else -> true`，与 `'all'` 完全等价 —— 因为 `read` 读的是内存缓冲，
 *   而崩溃现场是独立文件、从来不在缓冲里。删掉它会让老调用直接报「参数非法」
 *   （断崖），所以改为在 description 里说明 + 原生侧在回执里如实纠正。
 *   真正的现场读取入口是 `action:"crashes"`。
 */
const SOURCES = ['all', 'app', 'engine', 'web', 'crash'];

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死（理由与 `dsh-android-bubble` / `dsh-android-shell` 同）：
 * ① 什么场景该用（否则模型不知道有这个能力，会一直靠猜）；
 * ② 字段有哪些、取值范围是多少（否则模型会猜字段名，静默无效）；
 * ③ **能力的边界在哪**（本工具看不到什么、什么情况下不可靠）——
 *    不写清楚，模型会把「日志里没有」当成「没发生过」。
 */
const DESCRIPTION = `Read this Android app's own logs and diagnostic bundles. You run in the Node process and cannot see the native log buffer, so this is the ONLY way for you to inspect app/engine/web logs and crash scenes.

Use it whenever the user reports something broken ("it crashed", "this reply failed", "it won't open", "the plugin doesn't work") — check the logs BEFORE guessing, and quote the actual error line instead of speculating.

Actions:
- "summary" (default): one-shot conclusion — how the last launch ended, how many warnings/errors, how many crash scenes are stored, how many bundles await upload. START HERE: it is cheap and usually enough to decide the next step.
- "read": recent log lines (newest last). Filter with level ("all"/"warn"/"error"), source ("all"/"app"/"engine"/"web"), and query (substring, case-insensitive; searches both tag and text). At most ${MAX_LINES} lines are returned — if you need older lines, narrow the filters instead of asking for more. Note: the schema also accepts source:"crash", but it is NOT a filter — crash scenes are separate files, so it behaves exactly like "all" and the reply will say so. Use action:"crashes" for scenes.
- "crashes": list stored crash scenes, or read one in full via crashFile.
- "export": build a diagnostic bundle (zip) containing the logs, device info, crash scenes and an automatic diagnosis (diagnosis.json), and return its path so the user can send it. This takes a few seconds.

IMPORTANT limits you must not misreport:
- "app" source = the native app (476 call sites). "engine" = the Node engine's stdout/stderr. "web" = the WebView console. All three are capped at ~1 MB per file with one rotated generation, so very old lines may be gone.
- Lines may have been throttled (identical repeats within 1 s are collapsed). The summary action reports the dropped counts; if they are non-zero, do not claim "this error never happened".
- "The log has no such error" does NOT prove it never happened. The log may be rotated away, throttled, or the failure may have left no trace at all (e.g. the process was killed by the system). Say "I could not find it in the logs", never "it did not happen".
- Secrets are redacted before anything reaches disk (tokens, API keys). Do not ask the user to paste credentials to "help diagnose".`;

/**
 * 请求原生侧执行一次动作并等回执。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号（进程内自增）。
 * @param {object} payload - `{action, ...}`。
 * @returns {Promise<object>} 原生侧回执对象。
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
		// 先把旧回执清掉：否则上一轮的 ok 会被当成本轮的答案。
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
		return reply;
	}
	// 超时**绝不能**返回 ok —— 那会让模型以为查到了/导出成功了。
	// 但要提醒它可能只是慢，别直接宣布失败（export 打包确实可能超过 10 秒）。
	return {
		ok: false,
		timeout: true,
		message: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。`
			+ '如果刚才做的是 export，包可能仍在生成；请稍后用 action:"summary" 复查 '
			+ '（pending 数会增加），或让用户在设置页「运行日志与诊断」里手动导出。',
	};
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

/** 按字节截断，并明确说明被截断了 —— 静默截断会让模型以为读完了。 */
function truncateBytes(text, limit) {
	const buf = Buffer.from(text, 'utf8');
	if (buf.length <= limit) return text;
	const cut = buf.subarray(0, limit).toString('utf8');
	return `${cut}\n\n…（内容超过 ${Math.round(limit / 1024)} KB 上限，已截断。请用更精确的筛选再读一次）`;
}

/**
 * 把回执渲染成模型可见文本。
 *
 * ⚠️ 渲染里**必须保留**「日志里没有」与「没发生过」的区别。原生侧的
 *   `diagnosis.json` 已经做了诚实分级（「非正常结束」不等于崩溃），
 *   这里不能再把两者压成一句话 —— 那会把辛苦做的分级抹掉。
 */
function renderText(value) {
	if (value?.ok === false && value?.timeout === true) {
		return `结果: 超时\n${value.message ?? ''}`;
	}
	if (value?.ok !== true) {
		return `结果: 失败\n${value?.message ?? '原生侧未返回原因'}`;
	}

	const lines = ['结果: 成功'];

	// summary 分支
	if (typeof value.headline === 'string' && value.headline !== '') {
		lines.push(`结论: ${value.headline}`);
	}
	if (typeof value.lastExit === 'string' && value.lastExit !== '') {
		lines.push(`上次退出: ${value.lastExit}`);
	}
	if (typeof value.counts === 'object' && value.counts !== null) {
		const c = value.counts;
		// ⚠️ 标签是「现场文件」不是「崩溃现场」：`counts.crash` 数的是 `crash/`
		//   目录下的**全部** .txt，其中大部分是**非**崩溃类（引擎起不来、引擎正常
		//   退出、主线程卡住、渲染进程被回收…）。而同一屏的 `结论:` 行会写
		//   「有 N 份非崩溃现场（…）」—— 两行指着同一批文件却一个说「非崩溃」
		//   一个说「崩溃」，模型会据此认为存在 N 份崩溃。
		//   真正的硬崩溃数只有 `crashes` 分支的 `[崩溃]/[非崩溃]` 标签能给出。
		lines.push(
			`计数: 共 ${c.total ?? 0} 条 / 警告 ${c.warn ?? 0} / 错误 ${c.error ?? 0}`
			+ ` / 现场文件 ${c.crash ?? 0} / 待上传 ${c.pending ?? 0}`,
		);
		// ⚠️ **必须把两种总体分开说。** 上面那行把口径不同的两组数并排放在一起：
		//   `共/警告/错误` 来自内存缓冲，只覆盖**本次运行**；`现场文件/待上传`
		//   来自磁盘目录，是**跨多次运行累积**的。并排且不标注，读者会以为
		//   「错误 0」说的是这台机器从来没出过错，而它只说明「这次启动以来没有」。
		//   原生侧已在 `counts.scope` 里如实写清了，这里照搬，不要在渲染层丢掉。
		if (typeof c.scope === 'string' && c.scope !== '') {
			lines.push(`口径: ${c.scope}`);
		}
	}
	if (typeof value.throttleNote === 'string' && value.throttleNote !== '') {
		lines.push(`⚠ ${value.throttleNote}`);
	}

	// read 分支
	if (typeof value.logText === 'string' && value.logText !== '') {
		lines.push('');
		// ⚠️ **`source:"crash"` 不能原样复述进标题**（2026-10-14 自检发现）。
		//   原生侧 `matchesSource` 让 `"crash"` 落进 `else -> true`，与 `"all"` 等价，
		//   并且会在 `truncatedNote` 里如实纠正。但那个纠正句在**标题下方**，
		//   而标题写「来源 crash」会先入为主 —— 模型扫一眼标题就开始下结论，
		//   后面那句「不返回崩溃现场」很容易被跳过。
		//   标题里只写**实际生效**的来源，误导性取值交给下面那句纠正去解释。
		const effectiveSource = value.source === 'crash' ? 'all（"crash" 不是筛选条件，见下方说明）' : (value.source ?? 'all');
		lines.push(`—— 日志（${value.returned ?? 0} 行，来源 ${effectiveSource}，级别 ${value.level ?? 'all'}）——`);
		lines.push(value.logText);
	}
	if (typeof value.truncatedNote === 'string' && value.truncatedNote !== '') {
		lines.push('');
		lines.push(`⚠ ${value.truncatedNote}`);
	}

	// crashes 分支
	if (Array.isArray(value.scenes)) {
		if (value.scenes.length === 0) {
			lines.push('现场文件: 无');
		} else {
			lines.push('');
			// 标题同样不能说「崩溃现场」：这一列里通常大部分是 `[非崩溃]`。
			lines.push(`—— 现场文件（${value.scenes.length} 份，逐份标注是否真崩溃）——`);
			for (const s of value.scenes) {
				lines.push(`${s.file}  ${s.hardCrash ? '[崩溃]' : '[非崩溃]'}  ${s.atText ?? ''}  ${s.sizeBytes ?? 0} B`);
			}
		}
	}
	if (typeof value.sceneText === 'string' && value.sceneText !== '') {
		lines.push('');
		lines.push('—— 现场全文 ——');
		lines.push(value.sceneText);
	}

	// export 分支
	if (typeof value.bundlePath === 'string' && value.bundlePath !== '') {
		lines.push('');
		lines.push(`诊断包已生成: ${value.bundlePath}`);
		if (typeof value.bundleBytes === 'number') {
			lines.push(`体积: ${Math.round(value.bundleBytes / 1024)} KB`);
		}
		if (typeof value.diagnosisHeadline === 'string' && value.diagnosisHeadline !== '') {
			lines.push(`包内自动结论: ${value.diagnosisHeadline}`);
		}
		lines.push('可以请用户在设置页「运行日志与诊断」里点「上传给开发者」，或直接把该文件发出来。');
	}

	if (typeof value.message === 'string' && value.message !== '') {
		lines.push('');
		lines.push(value.message);
	}
	return lines.join('\n');
}

/** 生成 `presentCall` 的一行摘要。 */
function describeCall(args) {
	const action = args?.action ?? 'summary';
	if (action === 'read') {
		const bits = [];
		if (args?.level && args.level !== 'all') bits.push(`级别 ${args.level}`);
		if (args?.source && args.source !== 'all') bits.push(`来源 ${args.source}`);
		if (args?.query) bits.push(`关键字「${args.query}」`);
		return bits.length > 0 ? bits.join(' · ') : '读取最近日志';
	}
	if (action === 'crashes') {
		return args?.crashFile ? `读取现场 ${path.basename(String(args.crashFile))}` : '列出崩溃现场';
	}
	if (action === 'export') return '生成诊断包';
	return '读取日志摘要';
}

/**
 * 构造 `logs` 工具对象。
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function logsTool(home) {
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
					enum: ['summary', 'read', 'crashes', 'export'],
					description: '"summary" = cheap conclusion (start here); "read" = recent log lines with filters; "crashes" = list/read crash scenes; "export" = build a diagnostic zip and return its path.',
				},
				level: {
					type: 'string',
					enum: LEVELS,
					description: 'For action:"read" — minimum severity. "all" (default) = everything; "warn" = warnings and errors; "error" = errors only.',
				},
				source: {
					type: 'string',
					enum: SOURCES,
					description: 'For action:"read" — which stream. "app" = native app logs, "engine" = Node engine stdout/stderr, "web" = WebView console, "all" (default) = everything merged. ⚠️ "crash" is NOT a filter: crash scenes are separate files, not part of the in-memory buffer, so "crash" behaves exactly like "all" (and the reply says so). To read a crash scene use action:"crashes".',
				},
				query: {
					type: 'string',
					description: 'For action:"read" — case-insensitive substring matched against both the tag and the message. Use this instead of pulling many lines: e.g. "EACCES", "INVALID_REQUEST", "flock".',
				},
				lines: {
					type: 'number',
					description: `For action:"read" — how many recent lines to return, 1–${MAX_LINES}. Default 120.`,
				},
				crashFile: {
					type: 'string',
					description: 'For action:"crashes" — file name (from a previous listing, e.g. "20261013-192800-uncaught-exception.txt") to read in full. Omit to just list them.',
				},
			},
		},
		// ⚠️ **`render` 必须嵌在 `output` 里，不能放在工具定义顶层**（踩过）。
		//   `dsh-tools` 的 `register()`（lib/index.js 的 `const output = definition.output;`
		//   那一行）校验的是 `definition.output.render`，顶层 `render` 它**根本看不到**：
		//
		//     if (output === void 0 || typeof output !== 'object'
		//         || typeof output.render !== 'function' || …)
		//         throw new TypeError(`tool "${name}" must declare output { schema, render, presentationMeta? }`);
		//
		//   症状极具误导性：**插件装好了、日志里能看到「已安装日志工具插件」
		//   ` + 「已写入日志工具 overlay」，但 `logs` 工具从没出现在工具列表里**，
		//   而且整个 entry 注册失败（`dsh: warning: 1 entry did not activate`）。
		//   19 份真机诊断包全是这个报错 —— 也就是说这个工具**从未在真机上可用过一次**。
		//
		// ⚠️ `schema` 也**不能收窄**：返回形状随 action 变（summary 是统计、
		//   read 带 `logText`、crashes 带 `sceneText`、export 带 `bundlePath`），
		//   写成固定形状会让真机以 `ToolOutputError` 炸在渲染之前。
		//   引擎侧 `assertSupportedJsonSchema` 只要求 object-rooted，与
		//   `dsh-android-uiagent` / `dsh-android-bubble` 同一写法。
		output: {
			schema: {
				type: 'object',
				properties: {
					ok: { type: 'boolean' },
					message: { type: 'string' },
					logText: { type: 'string' },
					sceneText: { type: 'string' },
					bundlePath: { type: 'string' },
					// 原始回执：形状随 action 变，不收窄。
					details: {},
				},
				required: ['ok'],
			},
			render: (_args, value) => [{ type: 'text', text: renderText(value) }],
		},
		presentCall: (args) => ({
			card: 'generic',
			title: (args?.action ?? 'summary') === 'read'
				? '读取运行日志'
				: args?.action === 'crashes' ? '查看崩溃现场'
					: args?.action === 'export' ? '生成诊断包' : '读取日志摘要',
			description: describeCall(args),
		}),
		async execute(args) {
			const action = args?.action ?? 'summary';

			// ── 参数校验放在插件侧 ──
			// ⚠️ 校验必须在这里做，不能靠原生侧兜底：原生侧拿到的是一段 JSON，
			//   它没法把「字段名写错了」与「字段名对但值非法」区分开，
			//   只能返回一个笼统的失败。而模型需要知道**具体**哪里错了才能改对。
			if (action === 'read') {
				const level = args?.level ?? 'all';
				const source = args?.source ?? 'all';
				if (!LEVELS.includes(level)) {
					return { ok: false, message: `level 取值非法: ${level}（可选 ${LEVELS.join(' / ')}）` };
				}
				if (!SOURCES.includes(source)) {
					return { ok: false, message: `source 取值非法: ${source}（可选 ${SOURCES.join(' / ')}）` };
				}
				const lines = typeof args?.lines === 'number' ? Math.floor(args.lines) : 120;
				if (lines < 1 || lines > MAX_LINES) {
					return { ok: false, message: `lines 必须在 1–${MAX_LINES} 之间（收到 ${args?.lines}）。要更多请改用更精确的筛选，而不是加大这个值。` };
				}
				const reply = await sendCommand(home, ++seq, {
					action: 'read',
					level,
					source,
					query: typeof args?.query === 'string' ? args.query : '',
					lines,
				});
				if (reply?.ok === true && typeof reply.logText === 'string') {
					reply.logText = truncateBytes(reply.logText, MAX_TEXT_BYTES);
				}
				return reply;
			}

			if (action === 'crashes') {
				const crashFile = typeof args?.crashFile === 'string' ? args.crashFile.trim() : '';
				// ⚠️ 只允许文件名，不允许路径：这个值来自模型，而原生侧会拿它去
				//   拼 `logs/crash/<name>`。放行 `../` 就等于给模型一个任意文件读取口。
				if (crashFile !== '' && (crashFile.includes('/') || crashFile.includes('\\') || crashFile.includes('..'))) {
					return { ok: false, message: `crashFile 必须是纯文件名，不能含路径分隔符（收到 ${crashFile}）` };
				}
				const reply = await sendCommand(home, ++seq, { action: 'crashes', crashFile });
				if (reply?.ok === true && typeof reply.sceneText === 'string' && reply.sceneText.length > MAX_CRASH_CHARS) {
					reply.sceneText = `${reply.sceneText.slice(0, MAX_CRASH_CHARS)}\n\n…（现场超过 ${Math.round(MAX_CRASH_CHARS / 1000)} K 字符，已截断）`;
				}
				return reply;
			}

			// summary / export
			return await sendCommand(home, ++seq, { action });
		},
	};
}

/**
 * 注册 `logs` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-logs] 未配置 DSH_HOME，logs 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-logs] DSH_HOME 不存在（${home}），logs 工具未注册`);
		return;
	}

	// ⚠️ **register 必须包 try/catch，否则一个可选调试工具能把整个 App 搞死。**
	//
	// 真机实测（`20261013-214917-3582eef4` 等 20 个诊断包、54 次启动）：本插件的
	// 工具定义漏了 `output` 声明，`ctx.tools.register()` 抛
	//   TypeError: tool "logs" must declare output { schema, render, presentationMeta? }
	// 异常从 apply 冒出去 → cordis 判定该插件 failed → 引擎打印
	//   dsh: startup failed: 1 required plugin did not activate
	//   Failed plugins (1): dsh-android-logs
	// 然后**整个引擎退出**，用户看到的是「引擎启动失败（引擎进程已退出）」——
	// App 完全打不开，而表面上与「一个日志工具」毫无关联。
	//
	// 降级方向是对的：工具注册失败 ⇒ **只有这个工具不存在**（模型调用时会明确
	// 报「未知工具」），而不是整个 App 不可用。但**绝不能静默** —— 所以这里
	// 用 warn 把原始错误原样打出来（含 `output` 契约那类原因），否则又变成
	// 「工具不见了但没人知道为什么」这种本工程反复踩过的坑。
	try {
		ctx.tools.register(logsTool(home));
		ctx?.logger?.info?.(`[dsh-android-logs] 已注册日志工具 ${TOOL_NAME}: ${path.join(home, CMD_FILE)}`);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-logs] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
