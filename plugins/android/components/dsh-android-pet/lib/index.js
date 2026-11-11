/**
 * dsh-android-pet —— 把引擎的**运行状态**透传给原生悬浮球，让它能当桌面宠物。
 *
 * ## 这一层解决什么
 *
 * 悬浮球要"思考时转圈、干活时挥锤子、等你确认时招手"，就得知道 AI 此刻在干什么。
 * 原生侧有两条路拿状态，各有各的毛病：
 *
 * | 通道 | 粒度 | 毛病 |
 * |---|---|---|
 * | 页面 DOM 属性（`data-process-activity`） | 每秒采一次 | 短于一秒的工具调用**整个漏掉** |
 * | 本插件（订阅 `session/event`） | 每个事件 | 无（这正是它存在的理由） |
 *
 * 引擎自己在 `@deepseek-ai/dsh-session` 里就声明了 `'session/event'` 这个
 * post-commit 事件（`@mode emit`，监听器抛错只记日志、不影响会话），
 * 所以**不需要改引擎**，订阅它即可。
 *
 * ## 分工：插件只报"状态"，动作名由原生决定
 *
 * 本插件**只报运行状态**（`idle`/`thinking`/`working`/`waiting`/`error`），
 * 不报动作名 —— 状态到动作的映射（`actionMap`）存在原生配置里，AI 改它、
 * 落盘、重启仍在。插件是"传感器"，原生才是"策略"。
 *
 * ⚠️ 这条分工不是洁癖：把映射也放进插件，就会出现**两份映射**（原生一份给
 *   离线/无插件场景、插件一份给在线场景），它们必然漂移，而症状是"有时按状态
 *   换动作、有时不换"。
 *
 * ## 跨进程怎么打通
 *
 * 与 `dsh-android-bubble` 同一个文件信箱目录，但用**另一个文件**：
 * `$DSH_HOME/bubble-state.json`，单向、无回执，原生读完即删。
 *
 * ⚠️ **刻意不复用 `bubble-cmd.json`**，两条都是硬理由：
 *   ① 回执按 `id` 配对，而本插件与 `bubble` 工具各自从 1 开始自增 ——
 *      同一个 id 同时存在时，工具调用会收到这里的状态播报回执，表现为
 *      "AI 说改了悬浮球但没生效"；
 *   ② 工具调用写完命令要等最多 4 秒回执，这期间被覆盖就必然超时。
 *   而状态播报本来就不需要回执（它是"我在干什么"，不是"请你做什么"）。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：状态信箱的落点。缺失时**不订阅**（没有落点就没有意义）。
 *
 * @module dsh-android-pet
 */

import fs from 'node:fs';
import path from 'node:path';

export const name = 'dsh-android-pet';

/**
 * 空数组：本插件只订阅事件、写一个文件，不需要任何服务。
 *
 * ⚠️ 刻意*不* inject `tools` —— 需要 `inject` 的是 `dsh-android-bubble`
 *   （它要注册工具）。这里多声明一个服务就多一个"引擎没这个服务就整个不激活"
 *   的机会，而本插件失效时的症状是**静默的**（宠物不动了，没有任何报错）。
 *
 * @type {string[]}
 */
export const inject = [];

/** 状态信箱文件名。原生侧 `BubbleCommandBridge` 必须与这里一致。 */
const STATE_FILE = 'bubble-state.json';

/**
 * 两次写盘的最小间隔（ms）。
 *
 * 一轮回复会吐出成百上千个事件（`step/start`/`step/end`/`tool/call`…），
 * 每次都写盘是白费 I/O。120ms 已经比原生 250ms 的轮询更快 —— 再密也没有收益，
 * 因为原生那一拍只能读到一个状态（后写的覆盖前面的）。
 */
const MIN_INTERVAL_MS = 120;

/**
 * 事件类型 → 运行状态。
 *
 * ⚠️ **只列有明确归属的**。没列的事件（`session/title`、`todo/write`、
 *   `workspace/changes`…）一律忽略，而不是猜一个 —— 猜错会让宠物在"正在
 *   认真思考"时突然切回待机，比不切更糟。
 *
 * ⚠️ 事件名取自 `@deepseek-ai/dsh-session` 的 `KNOWN_SESSION_EVENT_TYPES`
 *   （已在真机上逐条核对过）。引擎升级后若新增事件，本表不会失效（未知事件
 *   被忽略），只是那些新事件不会驱动宠物。
 */
const EVENT_STATES = {
	// ── 一轮的开合 ──
	'turn/start': 'thinking',
	'turn/end': 'idle',
	'user/message': 'thinking',
	// ── 模型在生成 ──
	'step/start': 'thinking',
	'step/end': 'thinking',
	'llm/retry': 'waiting',
	'llm/retry-started': 'waiting',
	// ── 工具在跑 ──
	'tool/call': 'working',
	'tool/result': 'thinking',
	'tool/ptc-dispatch': 'working',
	'tool/ptc-dispatch-start': 'working',
	'tool-workflow/run-start': 'working',
	'tool-workflow/run-end': 'thinking',
	'tool-workflow/agent-start': 'working',
	'tool-workflow/agent-end': 'thinking',
	'command/run': 'working',
	'command/done': 'thinking',
	// ── 在等你 ──
	'approval/asked': 'waiting',
	'approval/decided': 'thinking',
	// ── 整理上下文属于"在忙"，不是"在等你" ──
	'compaction/start': 'working',
	'compaction/end': 'thinking',
};

/** 合法的运行状态。与原生侧 `BubbleAppearance.RUN_STATES` 必须一致。 */
const RUN_STATES = ['idle', 'thinking', 'working', 'waiting', 'error'];

/** 一次轮运行是"失败"的那些 kind（`turn/end` 的 `data.kind`）。 */
const FAILED_KINDS = ['failed', 'error', 'aborted', 'cancelled', 'canceled'];

/**
 * 从 `turn/end` 的载荷里判断这一轮是不是失败收场。
 *
 * ⚠️ 判据刻意宽松而**只认明确失败**：拿不准时按成功（= `idle`）处理。
 *   反过来（拿不准就当失败）会让宠物动不动就耷拉下来 —— 那比不报错更烦人。
 *
 * @param {object} data - `event.data`。
 * @returns {boolean} 是否失败。
 */
function isFailedTurn(data) {
	if (data === null || typeof data !== 'object') return false;
	const kind = typeof data.kind === 'string' ? data.kind.toLowerCase() : '';
	if (FAILED_KINDS.includes(kind)) return true;
	if (data.error !== undefined && data.error !== null) return true;
	return data.ok === false;
}

/**
 * 打印一行诊断信息。
 *
 * ⚠️ **刻意走 `console.log` 而不是 `ctx.logger`**：本 App 只把引擎子进程的
 *   stdout/stderr 转发进 logcat（`dsh-core-memory` 就是这么打"已激活"的），
 *   `ctx.logger` 的去向在移动端不可见。用 `ctx.logger` 的后果是**插件到底有没有
 *   激活、DSH_HOME 有没有拿到，全都查不出来** —— 而本插件失效的症状本来就是静默的
 *   （宠物不动，没有任何报错）。
 *
 * @param {string} message - 不含前缀的消息体。
 */
function log(message) {
	try {
		console.log(`dsh-android-pet: ${message}`);
	} catch {
		// 控制台不可用（被重定向到已关闭的管道）时静默，绝不能因此让引擎启动失败。
	}
}

/**
 * 注册状态桥。
 * @param {object} ctx - Cordis 上下文。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		log('未配置 DSH_HOME，状态桥未启动');
		return;
	}
	if (!fs.existsSync(home)) {
		log(`DSH_HOME 不存在（${home}），状态桥未启动`);
		return;
	}

	const target = path.join(home, STATE_FILE);
	const tmp = `${target}.tmp`;

	/** 上一次真正写出去的状态 key，用来去重。 */
	let lastKey = '';
	/** 上一次写盘的时刻。 */
	let lastAt = 0;
	/** 节流窗口内要补写的最新状态。 */
	let queued = '';
	let timer = null;

	/** 立刻写盘（原子：临时文件 + rename，原生可能正好读到一半）。 */
	function flush(state) {
		const payload = JSON.stringify({ state, at: Date.now() });
		try {
			fs.writeFileSync(tmp, payload, 'utf8');
			fs.renameSync(tmp, target);
			lastAt = Date.now();
			lastKey = state;
		} catch (error) {
			// ⚠️ 写失败**不改 lastKey**：下次同一个状态还要再试一次。
			//   这里如果把 key 记下来，一次瞬时失败（磁盘满、被清理）会让那个状态
			//   此后永远不再播报 —— 宠物卡在一个动作上，且没有任何报错。
			log(`写状态信箱失败: ${error?.message ?? error}`);
		}
	}

	/** 报一个运行状态（带节流；同一状态不重复写）。 */
	function report(state) {
		if (!RUN_STATES.includes(state)) return;
		if (state === lastKey && timer === null) return;
		const since = Date.now() - lastAt;
		if (since >= MIN_INTERVAL_MS) {
			queued = '';
			flush(state);
			return;
		}
		// 节流窗口内：记下最新状态，到点一次写出去（中间的合并掉）。
		queued = state;
		if (timer !== null) return;
		timer = setTimeout(() => {
			timer = null;
			const next = queued;
			queued = '';
			if (next !== '' && next !== lastKey) flush(next);
		}, MIN_INTERVAL_MS - since);
		// ⚠️ `unref()`：Node 的定时器默认会把进程钉住不退。这个 120ms 的尾巴
		//   虽然很短，但在"引擎准备退出"那一瞬间正好挂着时，会让退出多等一拍。
		timer?.unref?.();
	}

	ctx.on('session/event', (session, event) => {
		try {
			const type = event?.type;
			if (typeof type !== 'string') return;

			if (type === 'turn/end') {
				report(isFailedTurn(event.data) ? 'error' : 'idle');
				return;
			}
			const state = EVENT_STATES[type];
			if (state !== undefined) report(state);
		} catch (error) {
			// ⚠️ 绝不让本插件把引擎搞崩：监听器里抛错在 cordis 里只记日志，
			//   但这里再兜一层 —— 状态播报是锦上添花，会话本身绝对不能受影响。
			log(`处理事件失败: ${error?.message ?? error}`);
		}
	});

	log(`状态桥已启动: ${target}`);
}
