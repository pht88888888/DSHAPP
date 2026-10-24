/**
 * __PKG_NAME__ —— 把 DSH Android 的悬浮球变成 dsh-pet 风格的桌面宠物。
 *
 * ## 这一层解决什么
 *
 * 悬浮球本身已经支持透明 webm 动作、动作池、权重随机链、点击/拖拽回应、
 * 运行状态映射（见 `dsh-android-bubble` 的 `bubble` 工具）。缺的不是能力，
 * 而是**那套配置本身**：动作名、动作池、权重表、七档状态映射、文案，
 * 以及对应的透明动画素材。让人每次手敲一遍是不现实的。
 *
 * 本插件就是那份配置的**载体**：装上、重启，悬浮球自己变成桌宠。
 *
 * ## 为什么是插件而不是一次 `bubble` 工具调用
 *
 * ① **素材要跟着走**。几十段 webm 动辄十几 MB，散在别处（比如别的插件的包目录）
 *    意味着换台机器就断链。放在本包 `assets/webm/` 下，包就是完整的一份。
 * ② **要能分享**。别人 `dsh plugin add` 一个包就得到同样的效果，不需要知道
 *    动作名怎么起、池怎么分、权重怎么算。
 * ③ **要能重放**。配置是声明式的（`presets/pet.json`），改一处、重启即生效，
 *    不依赖"上次 AI 记不记得配过哪些动作"。
 *
 * ## 怎么下发
 *
 * 复用 `dsh-android-bubble` 已经在用的**文件信箱**（`$DSH_HOME/bubble-cmd.json`
 * → 原生 250ms 轮询 → `bubble-result.json` 回执）。这是刻意的：
 *
 * - 悬浮球是**独立进程里的一个窗口**，本插件在 Node 侧，没有别的通道；
 * - 再开一套信箱就等于让原生多轮询一个文件，没有收益；
 * - 代价是**要与 `bubble` 工具错开 id**，见 [ID_BASE]。
 *
 * ## 为什么只下发一次
 *
 * 用户用 `bubble` 工具把宠物改成别的样子之后，重启不该把它按回去。所以本插件
 * 在 `$DSH_HOME/deskpet/state.json` 里记一个**已应用版本**：版本一致就什么都不做。
 * 想重新套用，调 `deskpet` 工具的 `apply`。
 *
 * @module __PKG_NAME__
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const name = '__PKG_NAME__';

/**
 * 需要 `tools` 服务来注册 `deskpet` 工具。
 *
 * ⚠️ 刻意*不* inject 别的 —— 多声明一个服务就多一个"引擎没这个服务就整个
 *   不激活"的机会，而本插件失效时的症状是**静默的**（宠物没出现，没有报错）。
 *
 * @type {string[]}
 */
export const inject = ['tools'];

/** 本包根目录（由模块自身的 URL 推导，不依赖 cwd）。 */
const PKG_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 预设目录与默认预设。 */
const PRESET_DIR = path.join(PKG_ROOT, 'presets');
const DEFAULT_PRESET = 'pet.json';

/** 与 `dsh-android-bubble` 共用的文件信箱（三个文件名必须与原生侧一致）。 */
const CMD_FILE = 'bubble-cmd.json';
const RESULT_FILE = 'bubble-result.json';

/** 等待原生回执的上限。原生轮询间隔 250 ms，留足余量。 */
const WAIT_TIMEOUT_MS = 4000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 60;

/**
 * 请求编号的基数。
 *
 * ⚠️ **必须与 `bubble` 工具错开**：那个插件的 `seq` 从 1 开始自增，而回执是按
 *   `id` 配对的 —— 两条命令撞上同一个 id，工具调用会收到本插件配置下发的回执，
 *   表现为"AI 说改了悬浮球但没生效"。
 *
 *   取 100 万：`bubble` 工具要跑满 100 万次调用才会追上来（不现实），
 *   同时远在 Android `JSONObject.getInt` 的 2^31 之内 —— 用 `Date.now()`
 *   当 id 会溢出 int，原生那边读出来是垃圾值，回执永远配不上。
 */
const ID_BASE = 1_000_000;

/** 启动后第一次下发前的延迟（ms）：原生悬浮球可能还没起来。 */
const BOOT_DELAY_MS = 5000;

/** 启动下发的重试次数与退避基数（ms）。 */
const BOOT_RETRIES = 3;
const BOOT_BACKOFF_MS = 10000;

/**
 * 已应用状态的落点（相对 `$DSH_HOME`）。
 */
const STATE_FILE = path.join('deskpet', 'state.json');

/**
 * 打印一行诊断信息。
 *
 * ⚠️ **刻意走 `console.log` 而不是 `ctx.logger`**：本 App 只把引擎子进程的
 *   stdout/stderr 转发进 logcat，`ctx.logger` 的去向在移动端不可见。用
 *   `ctx.logger` 的后果是"插件到底有没有激活、有没有下发成功"全都查不出来。
 *
 * @param {string} message - 不含前缀的消息体。
 */
function log(message) {
	try {
		console.log(`__PKG_NAME__: ${message}`);
	} catch {
		// 控制台不可用（被重定向到已关闭的管道）时静默，绝不能因此让引擎启动失败。
	}
}

/** 读 JSON；文件不存在 / 内容不完整时给 null。 */
function readJson(target) {
	try {
		return JSON.parse(fs.readFileSync(target, 'utf8'));
	} catch {
		return null;
	}
}

/** 写文件（先写临时文件再 rename —— 原生侧可能正读到一半）。 */
function writeJsonAtomic(target, value) {
	const tmp = `${target}.tmp`;
	fs.writeFileSync(tmp, JSON.stringify(value), 'utf8');
	fs.renameSync(tmp, target);
}

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

/**
 * 把一条命令交给原生侧并等回执。
 *
 * 与 `dsh-android-bubble` 的 `sendCommand` 同构，只有 id 空间不同（见 [ID_BASE]）。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - `{action, fields}` 等。
 * @returns {Promise<{ok: boolean, message: string, appearance?: string}>} 结果。
 */
async function sendCommand(home, id, payload) {
	const cmdPath = path.join(home, CMD_FILE);
	const resPath = path.join(home, RESULT_FILE);

	try {
		// 先清旧回执：否则上一轮的 ok 会被当成本轮的答案。
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
	return {
		ok: false,
		message: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。悬浮窗可能已经改好，也可能没有；`
			+ '请用 bubble 工具的 action:"get" 复查。',
	};
}

/**
 * 把一份预设翻成 `bubble` 工具的 `set` 字段。
 *
 * 预设里的动作名 → 素材绝对路径的映射在这里完成：`assets` 是相对包根目录的
 * 素材目录，每个动作对应 `<assets>/<动作名>.webm`。
 *
 * ⚠️ **动作缺素材就整份放弃并明确报错**，不静默跳过：只配一半会让宠物"有的动作
 *   能播有的不能"，而调用方拿到的是"成功"，无从知道哪几个没生效。
 *
 * @param {object} preset - 预设对象。
 * @returns {{ok: true, fields: object, note: string} | {ok: false, error: string}} 结果。
 */
function buildFields(preset) {
	if (preset === null || typeof preset !== 'object' || Array.isArray(preset)) {
		return { ok: false, error: '预设必须是对象' };
	}
	const actions = preset.actions;
	if (actions === null || typeof actions !== 'object' || Array.isArray(actions)) {
		return { ok: false, error: '预设缺少 actions（动作名 → 素材名）' };
	}
	const assetDir = path.join(PKG_ROOT, typeof preset.assets === 'string' && preset.assets !== ''
		? preset.assets
		: 'assets/webm');

	const built = {};
	const missing = [];
	for (const actionName of Object.keys(actions)) {
		const assetName = String(actions[actionName]);
		const file = path.join(assetDir, `${assetName}.webm`);
		if (!fs.existsSync(file)) {
			missing.push(`${actionName}（缺 ${path.basename(file)}）`);
			continue;
		}
		built[actionName] = { video: file };
	}
	if (missing.length > 0) {
		return { ok: false, error: `以下动作缺素材，整份预设未应用: ${missing.join('、')}` };
	}

	// 池与映射原样透传：合法性由 `bubble` 工具与原生各自校验，本插件不重复实现。
	// 但它们引用的动作名必须真实存在，否则宠物会在那些档位上"什么都不播"。
	const fields = { actions: built };
	for (const key of ['pools', 'poolWeights', 'actionMap', 'moveParams', 'workStatusTexts', 'appearance', 'move']) {
		if (preset[key] !== undefined) fields[key] = preset[key];
	}
	if (preset.statusAutoHideMs !== undefined) fields.statusAutoHideMs = preset.statusAutoHideMs;

	// `appearance` / `move` 是给预设读起来清楚的分组，下发时要摊平到顶层。
	if (preset.appearance !== undefined) {
		delete fields.appearance;
		if (preset.appearance === null || typeof preset.appearance !== 'object') {
			return { ok: false, error: 'appearance 必须是对象' };
		}
		Object.assign(fields, preset.appearance);
	}
	if (preset.move !== undefined) {
		delete fields.move;
		if (preset.move === null || typeof preset.move !== 'object') {
			return { ok: false, error: 'move 必须是对象' };
		}
		const m = preset.move;
		if (m.enabled !== undefined) fields.moveEnabled = m.enabled === true;
		if (m.intervalSec !== undefined) fields.moveIntervalSec = m.intervalSec;
		if (m.minDp !== undefined) fields.moveMinDp = m.minDp;
		if (m.maxDp !== undefined) fields.moveMaxDp = m.maxDp;
	}

	// 池 / 映射 / 文案里引用的动作名必须在 built 里 —— 引用不存在的动作名，
	// 原生抽签时会跳过它，最坏结果是"那个动作永远抽不到"，不会卡住宠物；
	// 但那是**静默**的，所以在能报错的地方报出来。
	const unknown = [];
	for (const poolName of Object.keys(fields.pools ?? {})) {
		for (const act of fields.pools[poolName] ?? []) {
			if (built[act] === undefined) unknown.push(`pools.${poolName} → ${act}`);
		}
	}
	for (const state of Object.keys(fields.actionMap ?? {})) {
		const act = fields.actionMap[state];
		if (act !== '' && built[act] === undefined) unknown.push(`actionMap.${state} → ${act}`);
	}
	for (const act of Object.keys(fields.moveParams ?? {})) {
		if (built[act] === undefined) unknown.push(`moveParams → ${act}`);
	}
	if (unknown.length > 0) {
		return { ok: false, error: `预设引用了不存在的动作: ${unknown.join('、')}` };
	}

	const note = `${Object.keys(built).length} 个动作 · `
		+ `${Object.keys(fields.pools ?? {}).length} 个池 · `
		+ `${Object.keys(fields.actionMap ?? {}).length} 项状态映射`;
	return { ok: true, fields, note };
}

/** 读一份预设文件。 */
function loadPreset(file) {
	const target = path.join(PRESET_DIR, file);
	if (!fs.existsSync(target)) {
		const names = fs.existsSync(PRESET_DIR)
			? fs.readdirSync(PRESET_DIR).filter((n) => n.endsWith('.json')).map((n) => n.replace(/\.json$/, ''))
			: [];
		return {
			ok: false,
			error: `预设不存在: ${file}${names.length > 0 ? `（可选: ${names.join(' / ')}）` : '（预设目录为空）'}`,
		};
	}
	const data = readJson(target);
	if (data === null) return { ok: false, error: `预设不是合法 JSON: ${target}` };
	return { ok: true, preset: data };
}

/**
 * 把一份预设下发到悬浮球。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {object} preset - 预设对象。
 * @returns {Promise<{ok: boolean, message: string, note?: string}>} 结果。
 */
async function applyPreset(home, preset) {
	const built = buildFields(preset);
	if (!built.ok) return { ok: false, message: built.error };
	const id = ID_BASE + Math.floor(Math.random() * 100000);
	const reply = await sendCommand(home, id, { action: 'set', fields: built.fields });
	return {
		ok: reply.ok,
		message: reply.ok ? `桌宠配置已下发（${built.note}）` : reply.message,
		note: built.note,
	};
}

/**
 * 算一份预设的指纹。
 *
 * ⚠️ **刻意不写一个手改的 `CONFIG_VERSION` 常量**：那种写法的失效方式是静默的 ——
 *   改了预设却忘了同步版本号，老用户重启后**不会**重新下发，而日志里一切正常
 *   （"已是版本 N，跳过下发"）。指纹把"改了没有"交给内容本身判断。
 *
 * 指纹取预设 JSON 的规范化序列化 + 每个动作素材的字节数：前者抓住动作/池/映射/
 * 文案的任何改动，后者抓住"同名素材被换了另一段动画"。取 sha256 前 16 位十六进制，
 * 够用且日志里好读。
 *
 * @param {object} preset - 预设对象。
 * @returns {string} 指纹（16 位十六进制）。
 */
function fingerprint(preset) {
	const h = crypto.createHash('sha256');
	h.update(JSON.stringify(preset));
	const built = buildFields(preset);
	if (built.ok) {
		for (const actionName of Object.keys(built.fields.actions).sort()) {
			const file = built.fields.actions[actionName].video;
			try {
				h.update(`${actionName}:${fs.statSync(file).size}`);
			} catch {
				h.update(`${actionName}:missing`);
			}
		}
	}
	return h.digest('hex').slice(0, 16);
}

/** 读已应用的指纹；读不到给空串（= 从未应用过）。 */
function appliedFingerprint(home) {
	const data = readJson(path.join(home, STATE_FILE));
	const v = data?.fingerprint;
	return typeof v === 'string' ? v : '';
}

/** 记下已应用的指纹。 */
function markApplied(home, print, note) {
	try {
		const target = path.join(home, STATE_FILE);
		fs.mkdirSync(path.dirname(target), { recursive: true });
		writeJsonAtomic(target, { fingerprint: print, appliedAt: Date.now(), note: note ?? '' });
	} catch (error) {
		log(`写已应用标记失败: ${error?.message ?? error}`);
	}
}

/**
 * 启动时下发一次（带重试）。
 *
 * ⚠️ **失败不抛、不标记**：原生可能只是还没起来，下次启动再试即可。把失败标记成
 *   "已应用"会让这个用户此后**永远**得不到宠物，且没有任何报错。
 *
 * @param {string} home - `$DSH_HOME`。
 * @param {object} preset - 预设对象。
 * @param {string} print - 预设指纹。
 */
async function bootApply(home, preset, print) {
	await sleep(BOOT_DELAY_MS);
	for (let attempt = 1; attempt <= BOOT_RETRIES; attempt++) {
		const result = await applyPreset(home, preset);
		if (result.ok) {
			markApplied(home, print, result.note);
			log(`桌宠配置已应用（第 ${attempt} 次尝试）: ${result.note}`);
			return;
		}
		log(`第 ${attempt}/${BOOT_RETRIES} 次下发失败: ${result.message}`);
		if (attempt < BOOT_RETRIES) await sleep(BOOT_BACKOFF_MS * attempt);
	}
	log('下发始终失败，未标记已应用（下次启动会重试）。可调 deskpet 工具手动重试。');
}

/**
 * 注册 `deskpet` 工具。
 *
 * ⚠️ 手工构造而不是 `defineTool({...})`：本包解在 `$DSH_HOME/deskpet/` 下，
 *   那里没有 `node_modules`，ESM 解析够不到引擎的 `@deepseek-ai/dsh-tools`。
 *   理由与做法与 `dsh-android-bubble` 完全一致。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function deskpetTool(home) {
	return {
		name: 'deskpet',
		description: `Manage the Android floating-bubble desktop pet (桌宠) installed by this plugin.

This plugin ships a ready-made dsh-pet-style pet: a set of transparent WebM animations grouped into action pools with a weighted random chain, plus per-state status lines. The exact actions, pools and state map are declared in \`presets/pet.json\` — call \`action:"list"\` to see what this particular package contains. It applies itself once on startup — you normally never need to call this tool.

Use it when the user asks to re-apply / reset the pet, to check whether it is configured, or after they broke the configuration with the \`bubble\` tool and want the pet back.

- \`action:"apply"\` — re-send the pet preset to the floating bubble (all animations, pools, weights, state map, status lines, appearance). Use this to restore the pet or to pick up a preset change. Pass \`preset\` to choose another preset file (default "pet").
- \`action:"status"\` — report the installed presets, the config version applied so far, and whether the engine has a usable command mailbox. Changes nothing.
- \`action:"list"\` — list the animation assets shipped with this plugin (name, size, and the action it is wired to).

⚠️ This plugin configures the ball through the same file mailbox the \`bubble\` tool uses, so both can be used in the same session; the ids are kept apart on purpose. After \`apply\`, the ball keeps playing even if this plugin is later removed — the configuration lives in the app, not in the plugin.`,
		parameters: {
			type: 'object',
			additionalProperties: false,
			properties: {
				action: {
					type: 'string',
					enum: ['apply', 'status', 'list'],
					description: '"apply" re-sends the pet preset; "status" reports presets + applied version; "list" lists shipped animation assets.',
				},
				preset: {
					type: 'string',
					description: 'For action:"apply" — preset name without the .json suffix (default "pet").',
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
					message: { type: 'string' },
				},
				required: ['ok', 'message'],
			},
			render: (_args, value) => [{
				type: 'text',
				text: `${value.ok ? '结果: 成功' : '结果: 失败'}\n${value.message}`,
			}],
		},
		presentCall: (args) => ({
			card: 'generic',
			title: args.action === 'apply' ? '套用桌宠预设'
				: args.action === 'list' ? '列出桌宠动画素材' : '查看桌宠状态',
			description: args.action === 'apply'
				? `下发预设 ${args.preset ?? 'pet'} 到悬浮球`
				: '读取桌宠插件的状态',
		}),
		async execute(args) {
			const action = args.action;

			if (action === 'status') {
				const presets = fs.existsSync(PRESET_DIR)
					? fs.readdirSync(PRESET_DIR).filter((n) => n.endsWith('.json')).map((n) => n.replace(/\.json$/, ''))
					: [];
				const applied = appliedFingerprint(home);
				const current = fingerprint(loadPreset(DEFAULT_PRESET).preset ?? {});
				const assetDir = path.join(PKG_ROOT, 'assets', 'webm');
				const assetCount = fs.existsSync(assetDir)
					? fs.readdirSync(assetDir).filter((n) => n.endsWith('.webm')).length
					: 0;
				const lines = [
					`插件包: ${PKG_ROOT}`,
					`可用预设: ${presets.length > 0 ? presets.join(' / ') : '(无)'}`,
					`当前预设指纹: ${current}`,
					`已应用指纹: ${applied === '' ? '尚未应用过' : applied}`
						+ `${applied === current ? '（一致，重启不会重新下发）' : '（不一致，重启会重新下发）'}`,
					`随包素材: ${assetCount} 段 webm`,
					`命令信箱: ${path.join(home, CMD_FILE)}`,
				];
				return { ok: true, message: lines.join('\n') };
			}

			if (action === 'list') {
				const presetName = typeof args.preset === 'string' && args.preset.trim() !== ''
					? args.preset.trim()
					: 'pet';
				const loaded = loadPreset(`${presetName}.json`);
				if (!loaded.ok) return { ok: false, message: loaded.error };
				const built = buildFields(loaded.preset);
				if (!built.ok) return { ok: false, message: built.error };
				const rows = [];
				for (const actionName of Object.keys(loaded.preset.actions ?? {})) {
					const spec = built.fields.actions[actionName];
					const bytes = spec ? fs.statSync(spec.video).size : 0;
					const from = loaded.preset.sources?.[actionName];
					rows.push(`${actionName}（${Math.round(bytes / 1024)} KB）${from ? ` ← ${from}` : ''}`);
				}
				return { ok: true, message: `${rows.length} 段素材（预设 ${presetName}）:\n${rows.join('\n')}` };
			}

			// action === 'apply'
			const presetName = typeof args.preset === 'string' && args.preset.trim() !== ''
				? args.preset.trim()
				: 'pet';
			const loaded = loadPreset(`${presetName}.json`);
			if (!loaded.ok) return { ok: false, message: loaded.error };
			const result = await applyPreset(home, loaded.preset);
			if (result.ok) markApplied(home, fingerprint(loaded.preset), result.note);
			return { ok: result.ok, message: result.message };
		},
	};
}

/**
 * 注册桌宠插件；前置条件不满足时**不注册**，但绝不抛。
 *
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		log('未配置 DSH_HOME，桌宠未启动');
		return;
	}
	if (!fs.existsSync(home)) {
		log(`DSH_HOME 不存在（${home}），桌宠未启动`);
		return;
	}

	const loaded = loadPreset(DEFAULT_PRESET);
	if (!loaded.ok) {
		log(`默认预设不可用，桌宠未启动: ${loaded.error}`);
		return;
	}

	// 启动下发：只在预设指纹变化时做。**不 await** —— apply 必须立刻返回，
	// 否则引擎启动会被这个最长十几秒的重试链拖住（而它只是"锦上添花"）。
	const print = fingerprint(loaded.preset);
	const applied = appliedFingerprint(home);
	if (applied === print) {
		log(`桌宠配置指纹未变（${print}），跳过下发（想重新套用请调 deskpet 工具）`);
	} else {
		bootApply(home, loaded.preset, print).catch((error) => {
			log(`启动下发异常: ${error?.message ?? error}`);
		});
	}

	// ⚠️ **register 必须包 try/catch**：本插件的工具注册一旦抛异常（工具定义
	//   不合契约、服务未就绪…），异常会从 apply 冒出去 → cordis 判定插件 failed
	//   → 引擎打印 `dsh: startup failed: N required plugin did not activate`
	//   并**直接退出**。用户看到的是 App 完全打不开，而表面上与"一个可选工具"
	//   毫无关联。降级方向：只有这个工具不存在，而不是整个 App 不可用。
	try {
		ctx.tools.register(deskpetTool(home));
		log(`已注册 deskpet 工具（包目录 ${PKG_ROOT}）`);
	} catch (error) {
		log(`注册 deskpet 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`);
	}
}
