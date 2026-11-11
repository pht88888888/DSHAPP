/**
 * dsh-android-root —— 把 uid 0（root）执行通道暴露成一个**独立工具**。
 *
 * ## 与 `dsh-android-shell`（uid 2000）的关系
 *
 * 两者是**同一套做法的两个身份档位**，结构与取舍完全同源（那份注释里的
 * 「为什么是独立工具而不是给 bash 加提权参数」在这里逐字成立，不再重复）：
 *
 * | 工具 | 身份 | 拿不到时的行为 |
 * |---|---|---|
 * | `bash` | app uid | —— |
 * | `shell` | uid 2000 | 回落为 app 身份（静默） |
 * | `root`（本插件） | **uid 0** | **失败，不回落** |
 *
 * ⚠️ **`root` 刻意不回落。** `shell` 可以回落，因为「app 身份」本身是个有意义的
 * 降级。而 root 是显式提权请求，静默以 app 身份跑一遍会得到**退出码 0 但什么都没
 * 发生**的结果 —— 那正是本项目在 `shell` 通道上反复踩到的坑（见其头部对照表）。
 * 所以 runner 用 77 / 78 两个专属退出码把「没拿到 root」与「命令自己失败」分开，
 * 本插件再把它们翻译成模型能理解的报错。
 *
 * ## 什么时候才会被注册
 *
 * 宿主（`EngineManager`）只在**探测到真能拿到 uid 0** 时才挂载本插件的 overlay
 * （见 `RootAccess.detect()`）。探测是端到端跑一次 `su -c id -u`，不是查文件 ——
 * 「存在 su」与「能提权」是两回事（占位 su、未授权、root 失效都是假阳性）。
 *
 * 因此 `apply()` 里**只需要**检查环境变量是否就位；「有没有 root」这个判断
 * 已经在宿主侧做完，在这里重做一遍只会让两处判据漂移。
 *
 * ## 环境变量
 *
 * - `DSH_ROOT_RUNNER`（必需）：root 通道脚本的绝对路径。未设置或文件不存在时
 *   **不注册工具**（而不是注册一个必然失败的）。
 * - `DSH_HOME`：显式传给子进程。引擎的 `scrubbedParentEnv()` 会剥掉**所有**
 *   `DSH_*` 变量，不显式传的话子进程里连 `DSH_ROOT_RUNNER` 都会消失。
 *
 * @module dsh-android-root
 */

import fs from 'node:fs';

export const name = 'dsh-android-root';

/**
 * `tools` 用来注册工具；`shell` 是执行体（引擎把它注册为 `ctx.shell`）。
 * @type {string[]}
 */
export const inject = ['tools', 'shell'];

/** 工具名。引擎既有工具里没有占用。 */
const TOOL_NAME = 'root';

/** runner 自己的「没拿到 root」退出码（与 `dsh-android-root.mjs` 一致）。 */
const EXIT_NO_ROOT = 77;

/** runner 自己的「su 跑了但不是 uid 0」退出码。 */
const EXIT_NOT_UID0 = 78;

/**
 * 模型可见的工具描述 —— 与 `dsh-android-shell` 同样，**这是最要紧的一段文本**。
 *
 * 必须写死三件事：① 什么场景只有它做得到；② 用它的代价；③ 失败长什么样。
 * 尤其第 ③ 点：77 / 78 两个码是「通道没提权成功」，与命令自己的非零退出
 * 是**完全不同**的处置（前者要用户去授权，后者是命令本身的问题）。
 */
const DESCRIPTION = `Execute a command as the superuser (uid 0) via the device's \`su\`.

Use this tool ONLY for operations that neither the app identity nor \`shell\` (uid 2000) can perform:
- Reading or writing under \`/data\` (other apps' private data, \`/data/local/tmp\`, \`/data/misc\`)
- \`mount\` / remount, editing SELinux policy or labels (\`setenforce\`, \`chcon\`)
- \`iptables\`, kernel tunables under \`/proc/sys\`, \`sysctl\`
- Inspecting or modifying files owned by \`root\` that are not world-readable

Prefer the \`shell\` tool (uid 2000) when it suffices — screen capture, input injection, package management, \`settings\`, \`appops\`, \`dumpsys\`. Prefer \`bash\` for anything touching the session workspace: uid 0 can read this app's private directory but the workspace tooling, sandbox and path translation do not apply on this path.

There is no sandbox on this path and no \`LD_PRELOAD\`, so the link-translation layer and the write sandbox do not apply. Anything you run here can change the device irreversibly; state what the command does in \`description\` before running it.

Exit codes 77 and 78 are **this channel's own** errors, not the command's:
- 77: root was not obtained (no \`su\`, the request was denied, or the authorization prompt timed out). The command did NOT run.
- 78: \`su\` ran but the resulting identity was not uid 0, so the command did not really run as root.

If you see either, do not retry blindly and do not assume the operation succeeded — tell the user to grant root access to this app.`;

/** 用单引号包裹一个 shell 参数；内部的单引号按 POSIX 唯一的正确写法转义。 */
function shellQuote(value) {
	return `'${String(value).replaceAll("'", `'\\''`)}'`;
}

/** 把一条流渲染成模型可见文本，截断时附上完整输出路径（与 bash 工具同构）。 */
function streamText(stream) {
	if (stream === undefined) return '';
	if (!stream.truncated) return stream.text;
	return `${stream.text}\n[output truncated; full output: ${stream.spillPath ?? '(unavailable)'}]`;
}

/**
 * 把执行结果渲染成模型可见文本。
 *
 * ⚠️ 与 `dsh-android-shell` 的差别只有一处：**77 / 78 要显式说明「命令没跑」**。
 * 那两个码在语义上不是"命令失败了"，而是"根本没能以 root 执行" —— 不写清楚的话
 * 模型会去调试命令本身，而真正要做的是让用户去授权。
 */
function renderText(value) {
	const out = value.stdout ?? '';
	const err = value.stderr ?? '';
	let body = out;
	if (err.length > 0) {
		if (body.length > 0 && !body.endsWith('\n')) body += '\n';
		body += `[stderr]\n${err}`;
	}
	if (body.length === 0) body = '(no output)';

	const markers = [];
	if (value.exitCode === EXIT_NO_ROOT) {
		markers.push('[root not obtained — the command did NOT run; ask the user to grant root access]');
	} else if (value.exitCode === EXIT_NOT_UID0) {
		markers.push('[su did not yield uid 0 — the command did not run as root]');
	}
	if (value.timedOut) markers.push('[timed out]');
	if (value.aborted) markers.push('[aborted]');
	if (value.signal !== null && value.signal !== undefined) markers.push(`[killed by signal: ${value.signal}]`);
	else if (
		value.exitCode !== null && value.exitCode !== undefined &&
		value.exitCode !== 0 && value.exitCode !== EXIT_NO_ROOT && value.exitCode !== EXIT_NOT_UID0
	) {
		markers.push(`[exit code: ${value.exitCode}]`);
	}
	if (markers.length === 0) return body;
	if (!body.endsWith('\n')) body += '\n';
	return body + markers.join('\n');
}

/**
 * 从渲染文本尾部取回退出状态 —— 与 `@deepseek-ai/dsh-shell` 的
 * `parseExitStatus()` 同构（该模块解不到，故就地实现）。
 */
function parseExitStatus(text) {
	const signal = /\n\[killed by signal: ([^\]\n]+)\]$/.exec(text);
	if (signal?.[1] !== undefined) return { body: text.slice(0, signal.index), signal: signal[1] };
	const exit = /\n\[exit code: (\d+)\]$/.exec(text);
	if (exit?.[1] !== undefined) return { body: text.slice(0, exit.index), exitCode: Number(exit[1]) };
	return { body: text, exitCode: 0 };
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/root-tool/` 下，
 * 那里没有 `node_modules`，Node 的 ESM 解析够不到引擎的 `@deepseek-ai/dsh-tools`。
 * 理由与写法与 `dsh-android-shell` 完全一致。
 *
 * @param ctx Cordis 上下文（`execute` 里要用 `ctx.shell`）
 * @param runner root 通道脚本的绝对路径
 * @param nodeBin 引擎自己的 node（`process.execPath`）
 * @param dshHome `$DSH_HOME`
 */
function rootTool(ctx, runner, nodeBin, dshHome) {
	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			properties: {
				command: {
					type: 'string',
					description: 'The command to execute as uid 0. Runs through `su -c`, starting in `/`.',
				},
				description: {
					type: 'string',
					description:
						'Clear, concise description of what this command does in active voice, 5-10 words (shown in the UI).',
				},
				timeoutMs: {
					type: 'number',
					description:
						'Timeout in milliseconds. The executor applies its configured default and cap, and kills the command on expiry.',
				},
				workdir: {
					type: 'string',
					description:
						'Absolute working directory, applied via `cd` inside the root shell. Note that app-private paths may be reachable as uid 0 but the session workspace tooling still does not apply.',
				},
			},
			required: ['command', 'description'],
		},
		output: {
			schema: {
				type: 'object',
				additionalProperties: false,
				properties: {
					exitCode: { oneOf: [{ type: 'integer' }, { type: 'null' }] },
					signal: { oneOf: [{ type: 'string' }, { type: 'null' }] },
					timedOut: { type: 'boolean' },
					aborted: { type: 'boolean' },
					stdout: { type: 'string' },
					stderr: { type: 'string' },
				},
				required: ['exitCode', 'signal', 'timedOut', 'aborted', 'stdout', 'stderr'],
			},
			render: (_args, value) => [{ type: 'text', text: renderText(value) }],
		},
		// 与 shell 工具同样的 terminal 卡片；工具名不同，用户一眼能看出这条是 root 命令
		presentCall: (args) => ({
			card: 'terminal',
			title: args.command,
			description: args.description,
			...(args.workdir !== undefined ? { cwd: args.workdir } : {}),
		}),
		presentResult: (args, result) => {
			const block = result.content.length === 1 ? result.content[0] : undefined;
			if (block === undefined || block.type !== 'text') return undefined;
			if (result.isError) return undefined;
			const { body, ...exit } = parseExitStatus(block.text);
			return { card: 'terminal', output: body, ...exit };
		},
		async execute(args, exec) {
			// 原命令用单引号包裹后交给外层 `bash -c`，所以命令里的 `$`、反引号、
			// `&&` 不会被外层 shell 提前展开 —— 它们原样到达 `su -c` 那边解释。
			const parts = [shellQuote(nodeBin), shellQuote(runner), shellQuote(args.command)];
			if (typeof args.workdir === 'string' && args.workdir !== '') parts.push(shellQuote(args.workdir));
			const command = parts.join(' ');

			const handle = await ctx.shell.execute(
				ctx.shell.resolve({
					command,
					...(args.timeoutMs !== undefined ? { timeoutMs: args.timeoutMs } : {}),
					// 外层进程必须以 app 身份、无沙箱跑起来 —— 它要读 app 私有目录里的 runner。
					// ⚠️ 超时必须**大于** runner 自己的 SU_TIMEOUT_MS（60s），否则引擎会先杀，
					//   用户看到的是「超时」而不是「root 授权框没人点」这个真正的诊断。
					sandboxPolicy: { mode: 'danger-full-access' },
					env: { DSH_HOME: dshHome },
					signal: exec.signal,
				})
			);
			const result = await handle.result();

			return {
				exitCode: result.exitCode ?? null,
				signal: result.signal ?? null,
				timedOut: result.timedOut === true,
				aborted: result.aborted === true,
				stdout: streamText(result.stdout),
				stderr: streamText(result.stderr),
			};
		},
	};
}

/**
 * 注册 `root` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 与 `shell` 服务）。
 */
export function apply(ctx) {
	const runner = process.env.DSH_ROOT_RUNNER;
	if (typeof runner !== 'string' || runner === '' || !fs.existsSync(runner)) {
		ctx?.logger?.warn?.(
			'[dsh-android-root] 未配置 DSH_ROOT_RUNNER（或文件不存在），root 工具未注册'
		);
		return;
	}

	const dshHome = process.env.DSH_HOME;
	if (typeof dshHome !== 'string' || dshHome === '') {
		ctx?.logger?.warn?.('[dsh-android-root] 未配置 DSH_HOME，root 工具未注册');
		return;
	}

	// ⚠️ **register 必须包 try/catch**：见 `dsh-android-bubble` 同处的说明 ——
	// 工具注册抛异常会让 cordis 判定插件 failed，进而
	// `dsh: startup failed: N required plugin did not activate` ⇒ 引擎退出、
	// App 完全打不开。真机实测过（`dsh-android-logs` 漏 `output` 声明，54 次）。
	try {
		ctx.tools.register(rootTool(ctx, runner, process.execPath, dshHome));
		ctx?.logger?.info?.(`[dsh-android-root] 已注册 root 特权工具 ${TOOL_NAME}: ${runner}`);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-root] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
