/**
 * dsh-android-shell —— 把 uid 2000（shell）执行通道暴露成一个**独立工具**。
 *
 * ## 为什么是「独立工具」而不是「给 bash 加个提权参数」
 *
 * 早先的做法是经 `node --import` 替换 `dsh-bash-sandbox` 的
 * `danger-full-access` 分支，让 bash 在提权时走 uid 2000。那个做法有个致命
 * 耦合：**它挂在权限预设上**。用户在 GUI 里选「全部权限开放」时
 * `danger-full-access` 就成了会话默认，于是**每一条** bash 命令都走 uid 2000 ——
 * 而 uid 2000 读不到 app 私有目录，工作区、`$PREFIX`、`$DSH_HOME` 全部够不着，
 * bash 工具直接废掉（实测 `pwd` = `/`、`ls` 报 No such file、`cd 工作区` Permission denied）。
 *
 * 本插件把两件事**解耦**：
 *
 * | 工具 | 身份 | 谁决定 |
 * |---|---|---|
 * | `bash` | **永远** app 身份（沙箱、link 转译、工作区全部正常） | 引擎，与预设无关 |
 * | `shell` | **永远** uid 2000 | 模型读工具描述后自己选 |
 *
 * 于是无论预设怎么设，`bash` 都是 app 身份；要特权就显式调 `shell`。
 * 这同时满足两条要求：默认走 app 权限、AI 自己决策要不要用 2000。
 *
 * ## 执行体怎么搭
 *
 * **复用引擎自己的 `ctx.shell` 执行器**，把命令换成
 * `node <runner> '<原命令>'`，并以 `sandboxPolicy.mode = 'danger-full-access'`
 * 调用它。`danger-full-access` 在 `dsh-bash-sandbox` 里的语义恰好是
 * 「不经沙箱、直接跑」（`lib/index.js:68`），所以：
 *
 * - 外层 `bash -c` 以 **app 身份**、**无沙箱** 启动 → 它能读到 app 私有目录里的 runner
 * - runner 连 `127.0.0.1:5555` 的 adbd，认证后**以 uid 2000 执行真正的命令**
 *
 * 这样超时、取消、输出收集、spill、grace 等语义全部沿用引擎既有实现，
 * 本插件不必自己重写一遍（自己 spawn 就得把 `dsh-subprocess` 那套全抄一遍）。
 *
 * ## ⚠️ 走这条通道会绕过沙箱（固有代价，不是缺陷）
 *
 * uid 2000 读不到 app 私有目录，而 `libboosandbox.so`（写沙箱）与
 * `liblinkcompat.so`（link 转译）都在那里 —— 所以经 adbd 执行的命令
 * **必然**拿不到 `LD_PRELOAD`，**写沙箱与 link 转译对它都不生效**。
 *
 * 也就是说：**shell 身份换来的是特权，失去的是隔离。** 这正是为什么
 * 选择权必须交给模型显式声明（它调不调这个工具是看得见的），
 * 而不是让宿主按命令首词去猜、在暗处自动切换。
 *
 * ## 环境变量
 *
 * - `DSH_PRIV_RUNNER`（必需）：uid 2000 通道脚本的绝对路径。
 *   未设置或文件不存在时**不注册工具**，引擎按原样跑（等于没启用本特性）。
 * - `DSH_HOME`：runner 靠它定位 `adbkey`。必须显式传给子进程 —— 引擎的
 *   `scrubbedParentEnv()` 会剥掉**所有** `DSH_*` 变量，不显式传的话 runner
 *   会退回 `os.homedir()/.dsh`，而密钥在 `$DSH_HOME`，路径不一致就找不到密钥、
 *   静默回落成 app 身份。
 * - `DSH_ADB_HOME`：内置 adb 工具链目录。**同样必须显式传**，理由与上一行完全
 *   一样（`DSH_*` 全被剥掉）。漏传的后果比漏 `DSH_HOME` 更隐蔽：runner 里
 *   `ADB_BIN` 变空串 ⇒ 通道 1.5 与通道 2 的门卫双双为假 ⇒ **无线调试通道整条
 *   不存在**，只剩 legacy 5555；而 5555 没在听时就静默回落成 app 身份。
 *   更糟的是设置页探测走的是宿主半插件自己 spawn（env 完整），于是
 *   **探测说「uid 2000 可用」、模型执行却进不去无线通道**。
 * - `DSH_SHIZUKU_SOCKET`：Shizuku 桥的抽象 socket 名。**第三处同样的坑**，
 *   漏传的后果是通道 0 整条不存在。它值得单独列出，因为它是 uid 2000 里
 *   **唯一不依赖 adbd** 的来源 —— 对 Android 10 及以下（没有无线调试，
 *   且 `persist.adb.tcp.port` 写不进去 ⇒ 每次重启都要 PC 重来一次）的设备，
 *   它往往是唯一可行的路。见 ShizukuChannel.kt。
 *   ⚠️ 传的是**纯名字**，不带 `\0` 前缀：envp 是 C 字符串，NUL 会被静默截断，
 *   `\0` 由 runner 自己补（见 dsh-android-shell.mjs 的 SHIZUKU_SOCKET）。
 *
 * 这三个变量都是「插件必须转交的引擎事实」：引擎进程自己的 env 里有它们
 * （见 `EngineManager.runEngine()` 里 `put("DSH_...")` 那几处），但子进程默认看不到。
 *
 * @module dsh-android-shell
 */

import fs from 'node:fs';

export const name = 'dsh-android-shell';

/**
 * `tools` 用来注册工具；`shell` 是执行体（引擎把它注册为 `ctx.shell`）。
 * 两者缺一插件就不该激活，所以都放进 `inject` 而不是运行时抛错。
 * @type {string[]}
 */
export const inject = ['tools', 'shell'];

/** 工具名。引擎既有工具里没有占用（实测 30 个内置工具名，见 AGENTS.md ⑪）。 */
const TOOL_NAME = 'shell';

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 模型完全靠它决定「该用 bash 还是 shell」，所以三件事必须写死：
 * ① 什么场景只有它做得到（否则模型不会想到用）；
 * ② 什么场景**不能**用它（否则模型拿它去读写工作区，然后困惑于 Permission denied）；
 * ③ 失败不一定体现在退出码上（否则模型会基于假前提继续推理）。
 */
const DESCRIPTION = `Execute a command as Android's \`shell\` user (uid 2000) over the device's adbd, outside this app's sandbox.

Use this tool ONLY when the app identity is insufficient:
- Screen capture, injecting input events, package install/uninstall, \`settings\`, \`appops\`, \`am force-stop\`
- Reading \`/dev/input\`, listing all processes (\`ps -A\`), complete \`dumpsys\` output

Use the \`bash\` tool instead for anything that touches the session workspace, the Termux prefix, or relative paths. uid 2000 cannot read this app's private data directory, so it cannot see the workspace at all; commands here start in \`/\` and \`cd\` into the workspace silently fails.

Failure is not always visible in the exit code: some operations run under the app identity return exit code 0 while doing nothing (\`input keyevent\`) or returning truncated output (\`dumpsys\`, \`ps\`). That is exactly why this is a separate tool you choose explicitly rather than an automatic fallback.

If the output contains \`[dsh-adb] uid 2000 不可用\`, the privileged channel is not bootstrapped and the command actually ran as the app identity. Do not conclude the privileged identity is working in that case; tell the user to bootstrap it.

There is no sandbox on this path, and no \`LD_PRELOAD\` (the link-translation layer does not apply). It can write anywhere the shell user can.`;

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
 * 把执行结果渲染成模型可见文本：stdout、标记出的 stderr 段、退出状态标记。
 *
 * 与 `dsh-tool-bash` 的 `renderResult` 保持同构 —— 非零退出**报告**而不报错，
 * 由模型决定怎么反应；只有基础设施失败（spawn 失败、取消）才走 isError。
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
	if (value.timedOut) markers.push('[timed out]');
	if (value.aborted) markers.push('[aborted]');
	if (value.signal !== null && value.signal !== undefined) markers.push(`[killed by signal: ${value.signal}]`);
	else if (value.exitCode !== null && value.exitCode !== undefined && value.exitCode !== 0) {
		markers.push(`[exit code: ${value.exitCode}]`);
	}
	if (markers.length === 0) return body;
	if (!body.endsWith('\n')) body += '\n';
	return body + markers.join('\n');
}

/**
 * 从渲染文本尾部取回退出状态 —— 与 `@deepseek-ai/dsh-shell` 的
 * `parseExitStatus()` 同构（该模块解不到，故就地实现这 10 行）。
 *
 * 终端卡片把退出码显示成独立的 pill，所以必须从正文里**摘掉**标记，
 * 否则同一个退出状态会渲染两次。
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
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/shell-tool/` 下，
 * 那里没有 `node_modules`，Node 的 ESM 解析**够不到**引擎的
 * `@deepseek-ai/dsh-tools`，所以不能 import `defineTool`。而 `defineTool` 做的
 * 只是「把 spec 形式的 schema 编译成 raw JSON Schema + 包一层参数校验」，
 * 这两件事手工做完全等价：下面直接写 raw JSON Schema，
 * 参数校验由 `ctx.tools.register` 内部的 `validateJsonSchemaValue` 兜底。
 *
 * @param ctx Cordis 上下文。**必须显式传入** —— `execute` 里要用 `ctx.shell`，
 *   而它不在本函数的词法作用域里（`apply` 的参数不会自动被内层函数捕获）。
 * @param runner uid 2000 通道脚本的绝对路径
 * @param nodeBin 引擎自己的 node（`process.execPath`），绝对路径不依赖 PATH
 * @param dshHome `$DSH_HOME`，runner 靠它定位 `adbkey`
 * @param adbHome 内置 adb 工具链目录（`$DSH_ADB_HOME`）；没有（空串）时 runner
 *   只会走 legacy 5555，无线调试通道不可用。**必须原样转交**，理由见模块头注释。
 * @param shizuku Shizuku 桥的抽象 socket 名（`$DSH_SHIZUKU_SOCKET`）；空串时
 *   runner 跳过 Shizuku 通道。**必须原样转交**，理由见模块头注释。
 */
function shellTool(ctx, runner, nodeBin, dshHome, adbHome, shizuku) {
	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			properties: {
				command: {
					type: 'string',
					description: 'The command to execute as uid 2000. Runs under the device shell in `/`.',
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
						'Absolute working directory. uid 2000 cannot enter the session workspace, so this is only useful for shared paths such as `/sdcard`.',
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
		// 与 bash 工具同样的 terminal 卡片；工具名不同，用户一眼能看出这条是特权命令
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
			// 原命令用单引号包裹后交给外层 `bash -c`（引擎的本地执行器就是这么跑的），
			// 所以命令里的 `$`、反引号、`&&` 不会被外层 shell 提前展开 ——
			// 它们原样到达 adbd 那边的 shell，由它正常解释。
			const parts = [shellQuote(nodeBin), shellQuote(runner), shellQuote(args.command)];
			if (typeof args.workdir === 'string' && args.workdir !== '') parts.push(shellQuote(args.workdir));
			const command = parts.join(' ');

			const handle = await ctx.shell.execute(
				ctx.shell.resolve({
					command,
					...(args.timeoutMs !== undefined ? { timeoutMs: args.timeoutMs } : {}),
					// 关键：danger-full-access 在 dsh-bash-sandbox 里就是「不经沙箱直接跑」，
					// 我们要的正是这个语义 —— 外层进程必须以 app 身份跑起来才读得到 runner。
					sandboxPolicy: { mode: 'danger-full-access' },
					// ⚠️ 必须显式传 DSH_HOME：引擎的 scrubbedParentEnv() 会剥掉所有
					// `DSH_*`，不传的话 runner 找不到 adbkey，会静默回落成 app 身份。
					//
					// ⚠️ `DSH_ADB_HOME` 同样必须传，而且**漏了它更隐蔽**：runner 里
					// `ADB_BIN` 变空串 ⇒ 无线调试通道整条不存在 ⇒ 只能靠 legacy 5555。
					// 而设置页探测是宿主半插件自己 spawn 的（env 完整），会出现
					// 「探测说可用、模型执行进不去无线通道」。见模块头注释。
					//
					// ⚠️ `DSH_SHIZUKU_SOCKET` 是**第三处**同样的坑，漏了它这条通道
					// 整条不存在（`SHIZUKU_SOCKET` 为空 ⇒ 直接跳过通道 0）。
					// 它是 uid 2000 里唯一**不依赖 adbd** 的来源：Android 10 及以下
					// 没有无线调试、`persist.adb.tcp.port` 又写不进去，对这类设备
					// 它是唯一免 PC 的出路 —— 所以更不能不传。
					env: {
						DSH_HOME: dshHome,
						...(adbHome === '' ? {} : { DSH_ADB_HOME: adbHome }),
						...(shizuku === '' ? {} : { DSH_SHIZUKU_SOCKET: shizuku }),
					},
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
 * 注册 `shell` 工具；前置条件不满足时**不注册**（而不是注册一个必然失败的工具，
 * 那样模型会反复尝试）。
 * @param {object} ctx - Cordis 上下文（需 `tools` 与 `shell` 服务）。
 */
export function apply(ctx) {
	const runner = process.env.DSH_PRIV_RUNNER;
	if (typeof runner !== 'string' || runner === '' || !fs.existsSync(runner)) {
		ctx?.logger?.warn?.(
			'[dsh-android-shell] 未配置 DSH_PRIV_RUNNER（或文件不存在），shell 工具未注册'
		);
		return;
	}

	const dshHome = process.env.DSH_HOME;
	if (typeof dshHome !== 'string' || dshHome === '') {
		ctx?.logger?.warn?.('[dsh-android-shell] 未配置 DSH_HOME，shell 工具未注册');
		return;
	}

	// 内置 adb 工具链目录。缺失**不阻止注册** —— legacy 5555 那条通道不需要它，
	// 设备照常能用特权身份（这正是「有些手机没问题」的原因）。但没有它就只剩
	// legacy：重启后 5555 消失、或用户从未做过 `adb tcpip`，通道就静默回落成
	// app 身份。所以这里要**把缺失说清楚**，而不是默默少传一个变量。
	//
	// ⚠️ 必须从**引擎进程**的 env 读（插件跑在引擎进程里，这里是完整的）；
	// 传给子进程时再显式带上 —— 子进程那份会被 `scrubbedParentEnv()` 剥掉。
	const adbHome = process.env.DSH_ADB_HOME;
	const adbHomeValue = typeof adbHome === 'string' ? adbHome : '';
	if (adbHomeValue === '') {
		ctx?.logger?.warn?.(
			'[dsh-android-shell] 未配置 DSH_ADB_HOME —— 无线调试通道不可用，' +
				'shell 工具只能走 legacy 5555（重启后需重新 `adb tcpip 5555`）'
		);
	}

	// Shizuku 桥的 socket 名。缺失**同样不阻止注册** —— 它只是 uid 2000 的
	// 又一条来源，legacy / 无线调试照常可用。但它是**唯一不依赖 adbd** 的那条，
	// 对 Android 10 及以下（没有无线调试）的设备往往是唯一出路，所以要报出来。
	const shizukuRaw = process.env.DSH_SHIZUKU_SOCKET;
	const shizukuValue = typeof shizukuRaw === 'string' ? shizukuRaw : '';
	if (shizukuValue === '') {
		ctx?.logger?.warn?.(
			'[dsh-android-shell] 未配置 DSH_SHIZUKU_SOCKET —— Shizuku 通道不可用，' +
				'uid 2000 只能靠 legacy 5555 或无线调试'
		);
	}

	// ⚠️ **register 必须包 try/catch**：见 `dsh-android-bubble` 同处的说明 ——
	// 工具注册抛异常会让 cordis 判定插件 failed，进而
	// `dsh: startup failed: N required plugin did not activate` ⇒ 引擎退出、
	// App 完全打不开。真机实测过（`dsh-android-logs` 漏 `output` 声明，54 次）。
	try {
		ctx.tools.register(shellTool(ctx, runner, process.execPath, dshHome, adbHomeValue, shizukuValue));
		ctx?.logger?.info?.(
			`[dsh-android-shell] 已注册 uid 2000 特权工具 ${TOOL_NAME}: ${runner}` +
				(adbHomeValue === '' ? '（无线调试通道不可用）' : `（内置 adb: ${adbHomeValue}）`) +
				(shizukuValue === '' ? '（Shizuku 通道不可用）' : `（Shizuku: ${shizukuValue}）`)
		);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-shell] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
