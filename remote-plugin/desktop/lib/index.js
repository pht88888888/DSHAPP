/**
 * dsh-remote-desktop —— 电脑端（host half，装在客户电脑的 DSH 里）。
 *
 * ## 这个插件做什么
 *
 * 把本机 DSH 的会话事件流经 Cloudflare 隧道搬到手机，让手机能「进入电脑目录、
 * 在电脑上发起对话」。公网**只传文本**（用户几串字 + AI 回复 + 事件 JSON），
 * 不映射桌面、不映射 Web UI。
 *
 * ## 架构（路 B：电脑跑 agent loop，手机搬事件流）
 *
 * ```
 *   手机 WebView ──HTTPS──> dsh-remote.example.com ──CF隧道──> 本机 :17890
 *                                                                    │
 *                                                            本插件的中继服务
 *                                                                    │
 *                                                    ctx.sessionController（官方命令层）
 * ```
 *
 * 为什么不是「手机跑 loop、电脑当文件后端」：那样每个文件内容都要在公网来回滚，
 * 带宽与延迟都不可接受。路 B 下电脑是唯一真源，手机只搬事件。
 *
 * ## ⚠️ 关键设计：复用官方 `sessionController`，不自己造 agent
 *
 * 本插件**不**手工拼 `ctx.agents.create(...)`。官方 `SessionCommandController.create()`
 * 内部会 `agents.ensureSession(sessionId, cwd, ...)` —— 那会走 agent factory 建出
 * 一个**真 agent**（带 preset、带模型选择、带工具），这是手搓很难对齐的。
 *
 * 所以：
 * - 建会话 → `ctx.sessionController.create({ sessionId, cwd })`
 * - 发消息 → `ctx.sessionController.prompt({ sessionId, content, requestId })`
 * - 中断   → `ctx.sessionController.cancel({ sessionId })`
 *
 * ⚠️ **`prompt` 必须带唯一的 `requestId`**：官方用 `hasPromptRequest(agent, requestId)`
 * 做幂等去重，而它判的是 `source.rpcId === requestId`。若两边都是 `undefined`，
 * 第二次发消息会被**误判为重复**而静默返回 `{accepted:true}` —— 消息根本没进 agent。
 *
 * ## ⚠️ cwd 绝不跨端传（实测硬约束）
 *
 * Android 是 posix 语义，`path.posix.isAbsolute('C:/Users/...') === false`，
 * 而 `validateSessionHeader` 要求 `cwd` 是绝对路径。更危险的是官方
 * `createOrAdopt()` 里有 `await mkdir(cwd, { recursive: true })` ——
 * 手机端拿电脑路径去建会话会**真去建目录**。
 * 所以两端会话靠 **sessionId** 映射，电脑路径只作纯展示文字。
 * 本插件收到 `cwd` 参数时**只认电脑自己的绝对路径**（手机端不会传电脑路径过来）。
 *
 * ## 中继协议
 *
 * - `POST /api/dsh-remote/rpc`：`{ id, method, params }` → `{ id, ok, result }`
 * - `GET  /api/dsh-remote/stream?session=<id>`：SSE，`event:` 是 durable 事件、
 *   `frame:` 是流式帧
 *
 * 选 SSE 而不是 WebSocket：手机 WebView 里 EventSource 是原生 API，断线自动重连
 * 是浏览器内建的；WS 要自己写心跳与退避。Cloudflare 隧道对 SSE 的支持与普通
 * HTTP 一致。
 *
 * ## ⚠️ 丢帧是安全的（实测结论）
 *
 * 手机端 `ClientAssistantStream.acceptFrame()` 遇到不连续的帧返回
 * `{ type: 'rebaseline' }`，消费点是 `events.restart()` —— 即重连重拉基线。
 * 而 **host 侧的帧永不丢**（进程内事件），重连后从 baseline 能补齐全部已生成内容。
 * 所以这条通道可以做成 best-effort，不必自己设计序号补洞。
 *
 * @module dsh-remote-desktop
 */

import { createServer } from 'node:http';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, isAbsolute, dirname, join, relative, resolve, sep } from 'node:path';
import { createPairing, PAIR_ENDPOINT } from './pair.js';

export const name = 'dsh-jiuling-remote-desktop';

/**
 * `sessionController` 提供官方的建会话/发消息/中断命令层；`sessions` 用于
 * SSE 开场补发历史；中继 HTTP 服务由本插件自建（不用 `webServer`，因为
 * `webServer` 挂在 DSH 自己的 Web 端口上，而隧道的 ingress 指向独立端口 17890）。
 *
 * `webServer` 是给**配对界面**用的：客户端半边要能填激活码、看二维码，
 * 那条路走的是 DSH 自己的 Web 端口（与中继端口无关）。
 */
export const inject = ['sessionController', 'sessions', 'webServer', 'workspaceRegistry'];

/** 中继监听端口。与 Cloudflare 隧道的 ingress 必须一致。 */
const RELAY_PORT = 17890;

/** 配对界面的路由前缀（挂在 DSH 自己的 Web 端口上）。 */
const PAIR_ROUTE = '/api/dsh-remote/pair';

/**
 * 冷会话补洞的**事件条数上限**。
 *
 * `readEventRange` 对冷会话必须物化整份日志（`observation.events` 是惰性
 * getter，一碰就快照全量）。实测本机最大的会话 29638 条 / 155 MB 明文，
 * 为补一两条缺口读它不可接受。
 *
 * 2000 条的明文量级约 10 MB，是「能接受的一次性兜底成本」的上界；
 * 超过它的会话走「放弃补洞 ⇒ 手机端断流重连」那条路，代价是重连一次
 * （SSE handler 那边该付的补齐成本照付，但那是必须的）。
 */
const COLD_RANGE_LIMIT = 2000;

/**
 * 解析 `$DSH_HOME`。
 *
 * ## ⚠️ 空串必须当「未设置」，不能用 `??`（外部审计 4.2 / 自列 I1）
 *
 * `process.env.DSH_HOME ?? ...` 只挡 `null`/`undefined`，**空串会一路进 `join`**，
 * 于是 `STATE_DIR` 变成**相对路径** `dsh-remote`，`dirname` 得到 `.`，
 * `realpathBestEffort('.')` 解析成**进程 CWD** —— 工作区围栏的「禁区根」
 * 从真实 `$DSH_HOME` 悄悄换成了 CWD，而真正的 `$DSH_HOME` 反而落在禁区之外。
 * 后果是 `createSession {cwd:"C:\\Users\\<user>\\.dsh"}` 被放行，再
 * `fileRead {path:"dsh-remote/pair.json"}` 就把 `accessToken` / `tunnelToken`
 * 读走了。而且 `token.txt` / `pair.json` 还会被写进 CWD（可能落在用户项目目录里
 * 被 git 带走）。
 *
 * ⚠️ 引擎自己**明确**把空值当未设置（`dsh-home-paths` 的注释原文：
 * "An empty or whitespace-only `$DSH_HOME` is treated as unset, so a blank
 * override never resolves the home to the current working directory"）。
 * 两端理解必须一致，否则围栏判的是另一个目录。
 *
 * 顺带处理**纯空白**（`"   "`）：引擎也把它当未设置，这里跟着走。
 *
 * @returns 绝对路径形式的 DSH 家目录。
 */
function resolveDshHome() {
	const raw = process.env.DSH_HOME;
	const trimmed = typeof raw === 'string' ? raw.trim() : '';
	return trimmed === '' ? join(homedir(), '.dsh') : trimmed;
}

/** 配置与状态落在 $DSH_HOME 下的这个目录。 */
const STATE_DIR = join(resolveDshHome(), 'dsh-remote');

/** 访问令牌文件。手机端要用同一个令牌才能连上。 */
const TOKEN_PATH = join(STATE_DIR, 'token.txt');

/** 隧道凭据文件（可选：覆盖内置的隧道令牌）。 */
const TUNNEL_PATH = join(STATE_DIR, 'tunnel.txt');

/**
 * 远程调用**不许**当成工作区的目录名（相对路径的最后一段，大小写不敏感）。
 *
 * ## 为什么需要这张表（2026-10-20 现场）
 *
 * `assertWorkspaceAllowed` 原来只排 `$DSH_HOME` 自己/祖先/后代，于是下面这些
 * **全都**是合法工作区，而文件围栏是「以工作区为界」的 —— 工作区一旦是
 * `.ssh`，`fileRead('id_ed25519')` 就是完全合规的请求：
 *
 * | 目录 | 里面是什么 |
 * |---|---|
 * | `~/.ssh` | 私钥（`id_ed25519`）、`known_hosts`、`config` |
 * | `~/.gnupg` | GPG 私钥环 |
 * | `~/.aws` / `~/.kube` / `~/.config/gcloud` | 云凭据 |
 * | `~/.docker` | `config.json` 里常有 registry 凭据 |
 * | `AppData` | 浏览器 profile（Cookie / 已存密码）、`Local/Programs` |
 * | `*.keystore` / `keystore` | 安卓签名密钥 |
 *
 * ⚠️ **现场证据**：2026-10-20 19:50~20:21 有 14 个这样的目录被
 * `ensureWorkspaceId` 自动登记成了永久工作区（`.ssh` 3 个会话、
 * `.docker` 1 个、`keystore` 1 个…），全部留存于 `storages/workspace.json`。
 * 调用方不是外人 —— 是本机一个 AI 会话的探针脚本，它**读到了明文令牌**。
 * 令牌泄漏是另一件事（见 `token.txt` 那段的注释），但「读到令牌 ⇒ 能读私钥」
 * 这一跳**必须**在本函数里掐断。
 *
 * ⚠️ 判的是**路径里的任意一段**，不是只看最后一段：`~/.ssh` 和
 * `~/.ssh/anything` 一样敏感，而 `~/.ssh-backup` 这种**不是**目录名精确匹配，
 * 不受影响（用 `===` 比整段，不做前缀匹配）。
 */
const FORBIDDEN_DIR_NAMES = new Set([
	'.ssh',
	'.gnupg',
	'.aws',
	'.azure',
	'.kube',
	'.docker',
	'.config',
	'.password-store',
	'.terraform.d',
	'appdata',
	'keystore',
	'keystores',
]);

/**
 * 自动建工作区的**速率上限**（每小时）。
 *
 * ## 为什么必须有（2026-10-20 现场）
 *
 * `ensureWorkspaceId` 在 `resolveByPath` 未命中时会 `registry.create(cwd)`，
 * 而 `create` 是**持久化副作用**：写进 `storages/workspace.json`，重启还在，
 * 侧边栏永久多一项。一个循环 25 次的探针脚本在 31 分钟里造出了
 * **14 个工作区 + 25 个空会话**，全部留存至今。
 *
 * 正常用户一小时里最多新建一两个工作区；20 是「明显不是人手动点的」的门槛，
 * 同时留足余量给重装/重配的批量场景。超限后**报错**而不是静默降级 ——
 * 静默降级会让调用方以为成功，继续循环下去。
 */
const WORKSPACE_CREATE_LIMIT = 20;
const WORKSPACE_CREATE_WINDOW_MS = 60 * 60 * 1000;

/**
 * 最近一小时内**新建**工作区的时间戳（滑动窗口，见 `ensureWorkspaceId`）。
 *
 * ⚠️ 放在模块作用域是有意的：它必须跨 RPC 调用累积，而 `apply()` 的闭包
 * 每会话只建一次。这里没有「看不到闭包变量」的坑 —— 它不被 `apply()` 里的
 * 东西引用，只被模块级函数 `ensureWorkspaceId` 读写。
 */
const workspaceCreateStamps = [];

/**
 * 令牌落盘时的随机源 —— **不要再往包里写死任何凭据**。
 *
 * ## 为什么这里必须随机生成（这是一次真实漏洞的修复，别改回去）
 *
 * 早期版本把访问令牌和 Cloudflare 隧道令牌**硬编码在包内**，让「装完即用」。
 * 但那等于把凭据**公开发布到 npm**：任何人都能 `npm pack` 出这两个值。
 * 实测后果（用包内那个公开令牌打公网隧道）：
 *
 * ```
 * POST https://dsh-remote.example.com/api/dsh-remote/rpc
 * x-dsh-remote-token: <包内公开常量>
 * → HTTP 200，cf-ray a4b1631e9a978bcf-SIN，读到本机 177 个目录条目
 * ```
 *
 * 面向大众分发时这个缺陷是**灾难级**的：每个客户机都拿同一个隧道凭据去连
 * **同一条**隧道（`dsh-remote.example.com`），而访问令牌又是公开常量 ——
 * 于是**任何一个客户都能读其他客户整台电脑的目录、并能以他们的身份发消息**。
 * 客户机还会被挂到别人的域名上，本人毫不知情。
 *
 * 所以凭据一律**本机生成、本机落盘**（`token.txt`），配对成功后由服务器下发的
 * `accessToken` 取代（见 `ensureToken` 的优先级）。包里**不含任何可用凭据**。
 */
function generateToken() {
	return randomBytes(32).toString('hex');
}

/**
 * **已作废**的旧公开令牌 —— 只用于识别，**不是凭据，也永远不要再用它鉴权**。
 *
 * 1.0.11 及更早的版本把它当默认令牌写进 `token.txt`。老用户升级后文件里还留着
 * 这个值，若不识别就会**继续用这个全网公开的令牌**跑中继 —— 等于漏洞没修。
 * 所以 `ensureToken` 见到它就当成「没有令牌」，重新随机生成。
 *
 * 它已经发布到 npm，无法收回，因此**它必须被视为已泄露**：只做识别用。
 *
 * ## ⚠️ 为什么存哈希而不是原值
 *
 * 与 `LEGACY_SHARED_TUNNEL_FINGERPRINT` 同构：识别它只需要比对，不需要原值。
 * 把已泄露的令牌**再明文写一遍**没有任何好处，却会让它继续出现在每个新版本里。
 * 所以存 `sha256(原值)`。
 */
const LEGACY_PUBLIC_TOKEN_FINGERPRINT =
	'6c04899c20fc7ffab9b2fe7f9cc6e56a8b7ca0c66c96b393f8b93ab1c9784356';

/**
 * 判断一个令牌是不是那个已泄露的旧公开令牌。
 *
 * @param value - `token.txt` 里的内容。
 * @returns 是否应视为「没有令牌」。
 */
function isLegacyPublicToken(value) {
	const text = String(value ?? '');
	return createHash('sha256').update(text, 'utf8').digest('hex') === LEGACY_PUBLIC_TOKEN_FINGERPRINT;
}

/**
 * 找 cloudflared 可执行文件。
 *
 * @returns 可执行文件路径，或 `undefined`（未安装）。
 */
function findCloudflared() {
	// ⚠️ `<STATE_DIR>/bin/` 这一条不能少：配对流程会自动把 cloudflared 下到这里
	//（见 `pair.js` 的 `ensureCloudflared`），而客户机上通常别处都没有它。
	// 漏了这一条的症状很隐蔽 —— 配对「成功」、隧道却起不来，
	// 日志里只有一句「未找到 cloudflared」。
	const binDir = join(STATE_DIR, 'bin');
	const candidates = [
		process.env.DSH_REMOTE_CLOUDFLARED,
		join(binDir, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared'),
		process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'cloudflared', 'cloudflared.exe') : undefined,
		'/usr/local/bin/cloudflared',
		'/usr/bin/cloudflared',
		'/opt/homebrew/bin/cloudflared',
	].filter(Boolean);
	for (const candidate of candidates) if (existsSync(candidate)) return candidate;
	return undefined;
}

/**
 * 决定中继的访问令牌。
 *
 * ## 优先级（这是配对能自动生效的关键）
 *
 * 1. **配对凭据里的 `accessToken`** —— 手机扫码后从服务器 claim 到的就是它，
 *    两端因此**不需要任何手工同步**。这是面向大众分发的主路径。
 * 2. `token.txt` 文件 —— 老用户 / 想自己换令牌的人走这条。
 * 3. **本机随机生成**并落盘 —— 既没配对也没文件时的兜底。
 *
 * ⚠️ 第 3 条早期是「退回包内硬编码的常量」，那是个真实漏洞：常量随 npm 包公开，
 * 于是任何未配对的客户机都用**同一个公开令牌**跑中继，谁都能连上去读他的电脑。
 * 现在改成随机生成，包里不再含任何可用凭据（详见 `generateToken` 的注释）。
 *
 * ⚠️ 顺序不能反。若把文件放第一位，客户配对成功后令牌仍是文件里的旧值，
 * 而手机端拿到的是服务器新发的 —— 两端不一致，症状是「配对显示成功、
 * 一发消息就 401」，且完全看不出问题在哪。
 *
 * @param ctx - Cordis 上下文（只用 logger）。
 * @param paired - 配对记录，或 `undefined`。
 * @returns 令牌字符串。
 */
function ensureToken(ctx, paired) {
	if (typeof paired?.accessToken === 'string' && paired.accessToken.length >= 16) {
		return paired.accessToken;
	}
	try {
		const existing = readFileSync(TOKEN_PATH, 'utf8').trim();
		// ⚠️ 老版本把公开常量写进了这个文件。**必须当成没有令牌**，
		// 否则升级后仍在用那个已泄露的令牌 —— 漏洞等于没修。
		if (existing.length >= 16 && !isLegacyPublicToken(existing)) return existing;
		if (existing.length >= 16) {
			ctx.logger?.warn?.('[dsh-remote] 检测到旧版内置的公开令牌（已泄露），正在换成本机随机令牌');
		}
	} catch {
		/* 文件不存在：下面生成一份并落盘 */
	}
	const token = generateToken();
	try {
		mkdirSync(STATE_DIR, { recursive: true });
		writeFileSync(TOKEN_PATH, token, { mode: 0o600 });
	} catch (error) {
		// 写不了盘也照用（本机内存里这份仍是随机的）；只是下次启动会换一个，
		// 而手机端要等配对才能知道新值 —— 配对是面向大众的主路径，不受影响。
		ctx.logger?.warn?.(`[dsh-remote] 写令牌文件失败（本次仍用随机令牌）: ${String(error?.message ?? error)}`);
	}
	return token;
}

/** 组一条 SSE 消息。 */
function sse(eventName, payload) {
	return `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n`;
}

/**
 * 「这一环不接手」的哨兵。
 *
 * ⚠️ 不能用 `undefined` / `null`：两者都可能是**合法的应答值**，
 * 用它当哨兵会把「手机明确回了空」误判成「手机没在」而重复走下一环。
 */
const NO_TAKEOVER = Symbol('dsh-remote:no-takeover');

/**
 * 诊断日志：写一行到 `~/.dsh/dsh-remote/desktop-debug.log`。
 *
 * 为什么不用 `console.info`：桌面版是 Electron，插件跑在引擎子进程里，
 * stdout 被父进程收走、没有落盘，真机排查时**一条都看不到**。
 * 直接追加文件才能事后取证。
 *
 * ⚠️ 整个函数吞掉所有异常：诊断日志绝不允许影响业务逻辑。
 */
async function debugLog(line) {
	try {
		const { appendFile, mkdir } = await import('node:fs/promises');
		const { join } = await import('node:path');
		const { homedir } = await import('node:os');
		const dir = join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'dsh-remote');
		await mkdir(dir, { recursive: true });
		await appendFile(join(dir, 'desktop-debug.log'), `${new Date().toISOString()} ${line}\n`, 'utf8');
	} catch {
		// 故意什么都不做
	}
}

/** `approval/request` 的合法返回词表（与 dsh-user-approval 的 OUTCOMES 一致）。 */
const APPROVAL_OUTCOMES = ['allowed-once', 'rejected', 'cancelled', 'unavailable'];

/**
 * 等手机回答的超时。
 *
 * 为什么要有：手机可能锁屏、隧道断了、用户压根没看。没有超时的话
 * 这一轮会**永久挂住**，而「永久挂住」比「失败」更糟 —— 用户看到的是
 * 界面卡死、连停止按钮都没反应，只能杀应用。
 *
 * 取 5 分钟：比任何合理的人工反应时间都长（用户可能在开会、在走路），
 * 又短到不会让一轮真的永远挂着。超时后走 `next()` 交给官方 GUI，
 * 或由官方兜底 fail closed。
 */
const ASK_TIMEOUT_MS = 5 * 60 * 1000;

/**
 * 列出本机存在的盘符（仅 Windows）。
 *
 * 没有这一步，用户从主目录一路退到 `C:\` 就**再也出不去**了 —— 驱动器根没有
 * 上级目录，`parent` 为空，「上一级」按钮随即失灵，D 盘永远不会出现在列表里。
 *
 * @returns `['C:\\', 'D:\\', ...]`。
 */
function listDrives() {
	if (process.platform !== 'win32') return [];
	const drives = [];
	for (let code = 65; code <= 90; code += 1) {
		const root = `${String.fromCharCode(code)}:\\`;
		try {
			if (existsSync(root)) drives.push(root);
		} catch {
			/* 无权限探测：忽略该盘符 */
		}
	}
	return drives;
}

/**
 * 不许把 DSH 自己的状态目录（`$DSH_HOME`）当成会话工作区。
 *
 * ## 为什么必须有这一条（公网实测的攻击链）
 *
 * 文件 API 的围栏是**以会话工作区为界**的（见 `resolveWorkspacePath`），
 * 而工作区由手机端在 `createSession` 里自由指定。两者一叠加，围栏就能被绕开：
 *
 * ```
 * ① createSession { cwd: 'C:\\Users\\XIAOPAN' }          → ok（工作区 = 整个主目录）
 * ② fileStat      { path: '.dsh\\dsh-remote\\pair.json' } → ok，504 字节
 * ```
 *
 * 第 ② 步走的是**相对路径**，所以 `resolveWorkspacePath` 那道围栏完全拦不住 ——
 * 它判的是「在不在工作区内」，而令牌文件**确实**在工作区内。
 *
 * `$DSH_HOME` 里有 `.credentials.yaml`（模型 API 密钥）、`dsh-remote/pair.json`
 * （访问令牌）、`sessions/`（全部会话历史）。这些都不该经公网可读。
 *
 * ## ⚠️⚠️ 1.0.15 的修法是错的：只排除 `$DSH_HOME` **一个根** 挡不住
 *
 * 原判据是「cwd 落在 `$DSH_HOME` 之内就拒绝」。但攻击链第 ① 步用的是
 * **主目录**（`C:\Users\XIAOPAN`）—— 它是 `$DSH_HOME` 的**祖先**，
 * 根本不在 `$DSH_HOME` 之内，于是照旧放行；第 ② 步再去读
 * `.dsh\dsh-remote\pair.json` 是**相对路径**，围栏也拦不住（它判的是
 * 「在不在工作区内」，而令牌文件确实在工作区内）。
 *
 * 外部审计的原话是「用一个排除法思路修一个白名单问题」。实测复现：
 * `_audit/poc_workspace_bypass.mjs` —— 1.0.15 下 3 个方法全部放行。
 *
 * **所以现在是白名单判据**：凡是 `$DSH_HOME` 本身、它的任意**祖先**、
 * 它的任意**子目录**，一律拒绝。理由：把祖先目录当工作区，`$DSH_HOME`
 * 就成了它的子目录，令牌文件立刻落在可读范围里 —— 这正是那条攻击链。
 *
 * ⚠️ **必须走 `realpathSync` 再比**：`C:\Users\X\link` 可以是指向 `.dsh` 的
 * 符号链接，只比字面路径会被绕过。realpath 失败（路径不存在）时退回字面值 ——
 * 那时目录还没建，等官方 `mkdir` 建出来也只是个普通空目录。
 *
 * @param cwd - 手机请求的工作区路径（已确认是绝对路径）。
 * @throws 当它是 `$DSH_HOME`、其祖先或其后代时。
 */
function assertWorkspaceAllowed(cwd) {
	const stateRoot = STATE_DIR; // $DSH_HOME/dsh-remote
	const dshHome = dirname(stateRoot);
	const target = realpathBestEffort(cwd);
	const home = realpathBestEffort(dshHome);
	// ① $DSH_HOME 自己 / 其后代：令牌、凭据、会话历史都在里面。
	// ② $DSH_HOME 的祖先（含盘符根如 C:\）：把它当工作区，$DSH_HOME 就成了
	//    它的子目录 —— 用相对路径 `.dsh\dsh-remote\pair.json` 即可读到令牌。
	if (isInside(home, target) || isInside(target, home)) {
		throw new Error('不能把 DSH 自己的配置目录（或其所在目录）当作工作区，请选一个普通文件夹');
	}
	// ③ 凭据目录：`.ssh` / `.gnupg` / `.aws` / `AppData` / `keystore` …
	//    围栏是「以工作区为界」的，所以把 `.ssh` 当工作区 = 允许读私钥。
	//    判的是**任意一段**目录名（`.ssh` 与 `.ssh/sub` 一样敏感）。
	for (const segment of target.split(/[\\/]+/)) {
		if (FORBIDDEN_DIR_NAMES.has(segment.toLowerCase())) {
			throw new Error(
				`不能把「${segment}」这类存放凭据的目录当作工作区，请选一个普通项目文件夹`,
			);
		}
	}
}

/**
 * 常数时间比较访问令牌。
 *
 * ⚠️ **原来写的是 `provided !== auth.token`**：node 的字符串 `!==` 是短路比较，
 * 第一个不同的字节就返回，比较耗时随「前缀匹配到第几位」变化。理论上攻击者
 * 可以靠测量响应时间**逐字节猜出令牌**（经典时序侧信道）。
 *
 * ⚠️ 实践上这条路很难走通 —— 中间隔着 Cloudflare 隧道和公网抖动，单次比较
 * 纳秒级的差异会被噪声淹没，要打到统计显著需要海量样本。但**成本只有一次
 * 函数调用**，而这是发给大众的组件，没有理由留着。
 *
 * ⚠️ **长度不等必须立刻返回 `false`**：`timingSafeEqual` 在两个 Buffer 长度不同时
 * 会**抛异常**，直接漏出去会把 401 变成 500（拿长度探测的行为差异也算一种泄漏）。
 * 令牌长度（64）本身不是秘密，所以「先比长度」不额外泄漏信息。
 *
 * @param provided - 请求里带来的令牌（可能为空串、可能是数组）。
 * @param expected - 本机当前的有效令牌。
 * @returns 是否匹配。
 */
function tokenMatches(provided, expected) {
	const a = Buffer.from(String(provided ?? ''), 'utf8');
	const b = Buffer.from(String(expected ?? ''), 'utf8');
	if (a.length !== b.length || b.length === 0) return false;
	return timingSafeEqual(a, b);
}

/**
 * 令牌的短指纹，**只用来打日志**。
 *
 * ⚠️ 日志里不能出现令牌本身（见启动处那两行的注释），但排查时又需要能对账
 * 「两端用的是不是同一把」—— 于是打一个**单向**短指纹：够用来比，
 * 不够用来还原。
 *
 * 用 `sha256` 而不是「取前 6 位」：令牌是随机十六进制，前 6 位能直接拿去
 * 当暴力破解的起点（缩小 2^24 倍），哈希后没有这个性质。
 *
 * @param token - 访问令牌。
 * @returns 8 位十六进制指纹。
 */
function tokenFingerprint(token) {
	return createHash('sha256').update(String(token ?? ''), 'utf8').digest('hex').slice(0, 8);
}

/**
 * 列一个目录下的子目录（跟随符号链接，与官方 browse 后端一致）。
 *
 * @param input - 请求的路径；缺省用主目录。
 * @returns `{ path, home, parent, entries }`。
 */
function listDirectory(input) {
	const home = homedir();
	const target = input === undefined || input === '' ? home : String(input);
	if (!isAbsolute(target)) throw new Error('path must be absolute');
	const abs = resolve(target);
	const entries = [];
	for (const entry of readdirSync(abs, { withFileTypes: true })) {
		let isDir = entry.isDirectory();
		if (entry.isSymbolicLink()) {
			try {
				isDir = statSync(join(abs, entry.name)).isDirectory();
			} catch {
				continue; // 断链：跳过
			}
		}
		if (!isDir) continue;
		entries.push({ name: entry.name, path: join(abs, entry.name), isDirectory: true, enterable: true });
	}
	entries.sort((a, b) => a.name.localeCompare(b.name));
	// ⚠️ 用 dirname 而不是切字符串：手切 `C:\Users` 会得到 `C:`，
	// 而 win32 下 `isAbsolute('C:') === false` ⇒ 点「上一级」直接报错。
	// dirname('C:\Users') = 'C:\'，dirname('C:\') = 'C:\'（等于自身 ⇒ 到顶了）。
	const up = dirname(abs);
	const parent = up === abs ? '' : up;
	// 站在驱动器根上（parent 为空）时，把其它盘符一并列出来当出口。
	if (parent === '') {
		const current = abs.replace(/[\\/]+$/, '').toLowerCase();
		const drives = listDrives()
			.filter((drive) => drive.replace(/[\\/]+$/, '').toLowerCase() !== current)
			.map((drive) => ({ name: drive, path: drive, isDirectory: true, enterable: true }));
		entries.unshift(...drives);
	}
	return {
		path: abs,
		home,
		parent,
		entries,
	};
}

/**
 * 解析出真实路径，**尽量**返回有意义的答案。
 *
 * ⚠️ **不能只对整条路径调 `realpathSync`**：手机读一个**还不存在**的文件时
 * 也没问题（`fileStat` 探存在性是正常用法），那时 `realpathSync` 会抛，
 * 于是「判据」就退化成比字面路径 —— junction 一穿就过。
 *
 * 做法：**从路径本身向上找最长的一段「已存在」的祖先**，对它 realpath，
 * 再把剩下那段（不存在的）拼回去。这样即使叶子还不存在，中间任何一段
 * 是 junction 也会被解析出来。
 *
 * 全程失败才退回原值（那时路径压根不可达，官方自己也会报 ENOENT）。
 *
 * @param value - 绝对路径。
 * @returns 解析后的路径。
 */
function realpathBestEffort(value) {
	let current = resolve(value);
	const tail = [];
	// ⚠️ 上限只是防御性的死循环保险：正常 Windows 路径不会这么深。
	for (let depth = 0; depth < 64; depth += 1) {
		try {
			const real = realpathSync(current);
			return tail.length === 0 ? real : join(real, ...tail.reverse());
		} catch {
			const parent = dirname(current);
			// 到根了（`dirname('C:\\') === 'C:\\'`），没有可解析的前缀。
			if (parent === current) return resolve(value);
			tail.push(basename(current));
			current = parent;
		}
	}
	return resolve(value);
}

/**
 * 判 `absolute` 是否落在 `root` 之内（含 root 自身）。
 *
 * ⚠️ **不能用 `absolute.startsWith(root)`**：假根 `C:\Users\XIAOPAN\ws-evil` 也以
 * `C:\Users\XIAOPAN\ws` 开头，字符串前缀会把兄弟目录误判成区内。必须比到**分隔符**。
 * ⚠️ **win32 大小写不敏感**：`c:\users\...` 与 `C:\Users\...` 是同一个目录，
 * 不折叠大小写会把合法路径误判成越界。posix 下折叠是无害的（本来就全小写）。
 */
function isInside(root, absolute) {
	const norm = (value) => (process.platform === 'win32' ? value.toLowerCase() : value);
	const base = norm(resolve(root));
	const target = norm(resolve(absolute));
	if (target === base) return true;
	const prefix = base.endsWith(sep) ? base : base + sep;
	return target.startsWith(prefix);
}

/**
 * 按绝对路径拿到（必要时**创建**）电脑上的 Workspace，返回它的 id。
 *
 * ## 为什么必须这么做（用户需求二原文：「电脑侧边栏要显示在对应工作区下」）
 *
 * 官方 `SessionCommandController.create()` 有两条互斥入参
 * （`dsh-api-session-controller/lib/index.js:572`）：
 *
 * ```js
 * if (request.workspaceId !== void 0 && request.cwd !== void 0)
 *   throw new RemoteError("gateway/bad-request", "session.create accepts workspaceId or cwd, not both");
 * ```
 *
 * 而**只有** `workspaceId` 那条分支会走到 `workspace.attachSession(sessionId)`
 * （:586-593）。换句话说：
 *
 * | 传参 | 会话落在电脑 DSH 的哪一栏 |
 * |---|---|
 * | `{ cwd }` | **「未分组」**（session 建出来了，但没挂到任何 workspace 下） |
 * | `{ workspaceId }` | 该 workspace 下 ✅ |
 *
 * 这正是「手机发的消息全堆在未分组」的根因 —— 本插件此前一直只传 `cwd`。
 *
 * ## 为什么不直接用 `resolveByPath`（它会返回 undefined）
 *
 * `resolveByPath`（`dsh-workspace/lib/types/index.js:260`）只查**已有**的
 * workspace，用户手机里选的文件夹很可能在电脑 DSH 里从没被加过工作区。
 * 所以先 `resolveByPath` 命中就复用，没命中就 `create(path)` ——
 * `create` 自己也是幂等的（`createCanonical` 先按 canonical 路径查一遍，:269-272）。
 *
 * ⚠️ **`create` 要求路径已存在且是目录**，否则抛
 * `cannot create a workspace at '…': path is not a directory`。
 * 手机传来的目录是刚列出来的，正常都在；真不在了就该报错而不是静默降级。
 *
 * @param ctx - host context（需要 `workspaceRegistry`）。
 * @param cwd - 电脑上的绝对目录路径。
 * @returns workspace id。
 */
async function ensureWorkspaceId(ctx, cwd) {
	const registry = ctx.workspaceRegistry;
	if (registry === undefined) throw new Error('workspaceRegistry 不可用');
	const existing = await registry.resolveByPath(cwd);
	if (existing !== undefined) return existing.id;
	// ⚠️ **限额只拦「新建」，命中已有工作区的路径不受影响** ——
	// 老客户端重发同一条请求、用户反复进同一个文件夹，都必须照常放行。
	// 只有「这台机器上从没有过、现在要凭空造一个」才算一次创建。
	//
	// ⚠️ 计数器是**进程内**的（插件热重载会清零）。这够用：它防的是
	// 「一个循环脚本在几分钟内刷出几十个」这种模式，不是长期配额。
	const now = Date.now();
	while (workspaceCreateStamps.length > 0 && now - workspaceCreateStamps[0] > WORKSPACE_CREATE_WINDOW_MS) {
		workspaceCreateStamps.shift();
	}
	if (workspaceCreateStamps.length >= WORKSPACE_CREATE_LIMIT) {
		throw new Error(
			`一小时内新建工作区已达上限（${WORKSPACE_CREATE_LIMIT} 个），为防止误建已拒绝。「${cwd}」` +
				'如果确实要加，请在电脑上手动把它添加为工作区。',
		);
	}
	workspaceCreateStamps.push(now);
	const created = await registry.create(cwd);
	return created.id;
}

/**
 * 把手机传来的「工作区相对路径」变成电脑上能直接喂给官方 `workspaceFiles` 的路径，
 * **并把它锁死在会话工作区内**。
 *
 * ⚠️ **两端各算各的**：手机只知道它那边的镜像目录（一个以电脑路径为名的空壳），
 * 它传进来的是**相对于工作区**的一段路径（根目录是空串，posix 分隔符）。
 * 真正的绝对路径由本机拼 —— 本机用什么盘符、什么分隔符只有本机知道，
 * 让手机去拼 `C:\…` 必然出错（见文件头「cwd 绝不跨端传」）。
 *
 * ⚠️⚠️ **官方 `workspaceFiles` 自己【不】做围栏 —— 这里是唯一的防线（实测）**。
 * 它内部只是 `resolve(cwd, path)` 然后干活，`..` 和绝对路径**都能穿出去**。
 * 公网实测（1.0.14，令牌鉴权通过后）：
 *
 *   fileStat  { path: 'C:\\Windows\\win.ini' }                    → ok=true，92 字节
 *   fileReadBytes { path: '..\\..\\..\\..\\..\\..\\Windows\\win.ini' } → ok=true，
 *        data 解出 `; for 16-bit app support`
 *   fileStat  { path: '..\\..\\..\\..\\..\\..\\Users\\XIAOPAN\\.dsh\\dsh-remote\\pair.json' }
 *        → ok=true，504 字节 —— **连自己的访问令牌都能被读走**
 *
 * 所以「手机只能看会话工作区」这件事**必须在本函数里保证**，不能指望官方兜底。
 * 不修的后果不是「能列目录」（那是有意为之，见 `listDirectory`），而是
 * **能读走任意文件**，包括令牌文件本身。
 *
 * 对越界的处理是**抛错**而不是「夹回工作区」：静默改写路径会让手机端看到一个
 * 它没请求的文件，把安全事件伪装成正常结果。
 *
 * ⚠️ **返回值保持「相对路径透传」，不要改成绝对路径**：官方 `list()` 拿到相对路径
 * 会自己按 `cwd: workspaceRoot` 解析，而它的 `path` 字段回显的是**相对路径**
 * （手机端 `mapPath` 就是按这个回显做剥前缀匹配的，见 `_test/file-tree.mjs` ⑧）。
 * 这里若换成绝对路径，手机端的前缀匹配会失配 ⇒ 侧边栏根目录空掉。
 * 所以本函数只**校验**，不改写。
 *
 * @param scope - 官方 `workspaceFileScope`（`{sessionId, workspaceRoot}`）。
 * @param path - 手机传来的**工作区相对**路径（posix 分隔符，根目录为空串）。
 * @returns 校验通过的路径，原样透传给官方；根目录换成 `workspaceRoot`。
 */
function resolveWorkspacePath(scope, path) {
	const root = scope.workspaceRoot;
	// 根目录：官方 `inspect()` 拿 `path.length === 0` 会判 `path is required`，
	// 所以这里必须换成根路径本身（不是空串）。
	if (typeof path !== 'string' || path === '' || path === '.') return root;
	// ⚠️ 绝对路径一律拒绝。手机端只需要相对路径，它也没有理由发绝对路径；
	// 放行绝对路径就等于把上面那个 `C:\Windows\win.ini` 的读法重新打开。
	if (isAbsolute(path)) {
		throw new Error('路径必须是工作区内的相对路径');
	}
	// ⚠️ 用 resolve 只做**判据**，返回的仍是原始 `path`（见 docstring）。
	if (!isInside(root, resolve(root, path))) {
		throw new Error('路径越出了会话工作区');
	}
	// ⚠️⚠️ **文本判据不够**（外部审计 D2）：工作区内只要有一个指向区外的
	// junction / 符号链接，`esc\pair.json` 在文本上就落在工作区里，而
	// `resolve` 不会解析链接 —— 上面那道检查形同虚设。
	// Windows 上普通用户即可 `mklink /J`，pnpm 等工具本来就会造链接；
	// 持有令牌的人还能先 `prompt` agent 在工作区内建一个。
	// 所以**再按真实路径判一次**（解析链接后的位置必须仍在工作区内）。
	if (!isInside(realpathBestEffort(root), realpathBestEffort(resolve(root, path)))) {
		throw new Error('路径经符号链接指向了工作区之外');
	}
	return path;
}

/**
 * 不中断的取消信号。
 *
 * ⚠️ 官方 `workspaceFiles` 的每个方法都要求一个 `signal`（内部会交给 fs 后端）。
 * 本插件的 RPC 没有取消通道（中继那边一问一答），所以给一个**永不 abort** 的
 * 信号 —— 传 `undefined` 在部分后端上会直接抛。
 */
function idleSignal() {
	return new AbortController().signal;
}

/** 取官方的 `workspaceFiles` 服务；没就绪时给一句能看懂的错。 */
function workspaceFilesOf(ctx) {
	const files = ctx.get?.('workspaceFiles');
	if (files === undefined || typeof files.list !== 'function') {
		throw new Error('workspaceFiles 不可用（引擎还没就绪？）');
	}
	return files;
}

/**
 * 按 sessionId 拼出官方要的 `workspaceFileScope`（`{sessionId, workspaceRoot}`）。
 *
 * ⚠️ **照抄官方 `dsh-api-workspace-files` 里那个 lookup 的取法**：活会话读
 * `sessions.get(id).header`，已归档的会话退回 `sessionPersistence.stat(id).header`。
 * 只读活会话的话，手机去列一个电脑上「已落盘但当前没跑」的会话就报「工作区不存在」。
 *
 * ## ⚠️ 这里也必须过围栏（外部审计第 5 条）
 *
 * `assertWorkspaceAllowed` 原本**只在建会话时**判（`createSession`），
 * 而本函数读的是**已存在会话**的 cwd —— 于是电脑上任何**先前**以
 * `$DSH_HOME`（或其祖先）为工作区建的会话，手机端只要知道 sessionId
 * 就能直接 `fileList`/`fileRead`/`fileStat`，**永远不经过围栏**。
 *
 * 为什么这是真问题而不是理论问题：官方 `workspaceFiles.read/stat/readBytes`
 * 对工作区外的路径**本来就是放行的**（它内部只是 `resolve(cwd, path)`），
 * 所以 `resolveWorkspacePath` 是唯一防线，而它的界就是这里给的
 * `workspaceRoot`。若这个 root 本身就是 `$DSH_HOME`，那道防线等于画在
 * `$DSH_HOME` 上 —— `.dsh\dsh-remote\pair.json`（含 `accessToken`）就在界内。
 *
 * 所以这里**同样**要求 cwd 通过围栏。合法的普通目录不受任何影响。
 *
 * @param ctx - Cordis 上下文。
 * @param sessionId - 会话 id。
 * @returns 官方的 `workspaceFileScope`。
 */
async function workspaceScopeOf(ctx, sessionId) {
	if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
	const live = ctx.sessions?.get?.(sessionId)?.header;
	let stored;
	if (live === undefined) {
		const persistence = ctx.get?.('sessionPersistence');
		if (typeof persistence?.stat === 'function') {
			stored = await persistence.stat(sessionId).catch(() => undefined);
		}
	}
	const cwd = (live ?? stored?.header)?.cwd;
	if (typeof cwd !== 'string' || cwd === '') {
		throw new Error(`电脑上没有会话 ${sessionId} 的工作目录`);
	}
	// ⚠️ 读已有会话也要过围栏（见 docstring）。
	assertWorkspaceAllowed(cwd);
	return { sessionId, workspaceRoot: cwd };
}

/**
 * 处理一条 RPC。
 *
 * @param ctx - Cordis 上下文。
 * @param method - 方法名。
 * @param params - 参数。
 * @param io - `startRelay` 闭包里的通道（见其定义）。可选：老调用方不传也能跑，
 *   只有 `answerAsk` 这一个方法依赖它。
 */
async function handleRpc(ctx, method, params, io) {
	const controller = ctx.sessionController;
	if (controller === undefined) throw new Error('sessionController 不可用');

	switch (method) {
		/** 探测：手机端「测试连接」用。 */
		case 'ping':
			/*
			 * ⚠️ **ping 顺带把 dirty 标记捎回去**（需求 5）。
			 *
			 * 用户要求「下次手机发起连通性测试的时候告诉手机我这边有数据变化了」。
			 * 手机每次探测在线状态本来就要发这个 ping，所以捎带一个数组
			 * **几乎不增加流量**（典型情况是空数组 `[]`，2 字节）。
			 *
			 * 这就是整个方案的关键：不在电脑侧主动推送，而是搭手机的顺风车。
			 */
			return {
				ok: true,
				platform: process.platform,
				home: homedir(),
				pid: process.pid,
				dirty: snapshotDirty(),
			};

		/**
		 * 手机确认「我收到 dirty 标记并把数据拉过了」⇒ 清掉对应标记。
		 *
		 * ## 为什么必须由手机来 ack，而不是电脑自己清
		 *
		 * 如果电脑在 ping 之后就清标记，而手机那次拉取失败了（网络抖、隧道断），
		 * 标记就永久丢了 —— 手机再也不会知道有变化。所以清标记的时机必须是
		 * **手机确认拉成功之后**。
		 *
		 * @param params.domains - 要清的域列表；不传就清全部。
		 */
		case 'ackDirty': {
			const list = Array.isArray(params?.domains) ? params.domains : null;
			if (list === null) {
				dirtyDomains.clear();
			} else {
				for (const d of list) {
					if (typeof d === 'string') dirtyDomains.delete(d);
				}
			}
			return { ok: true, dirty: snapshotDirty() };
		}

		/** 列电脑上的会话（只给元数据）。 */
		case 'listSessions': {
			const result = await controller.list({});
			return { sessions: result?.items ?? [] };
		}

		/**
		 * 一次返回**电脑上全部工作区及其未归档对话**，供手机端自动同步。
		 *
		 * ## 为什么需要这个 RPC（用户 2026-10-27 反馈，多条）
		 *
		 * 用户原话：「每次打开软件都要自己同步工作区和对话，非常非常繁琐，
		 * 安装这个本地化 dsh 本身就是为了省事……希望作者可以实现自动识别所有
		 * 工作区内未归档的对话，可以不加载历史对话，但要加载所有真正进行的
		 * 当前对话记录，这样对带宽的压力也会很小。」
		 *
		 * 原来的流程是纯手动的：用户得在工作区行上点「⋯ → 同步对话」，再在
		 * 弹窗里逐条勾选。一次只能同步一个工作区，重启后还要重来。
		 *
		 * 这个 RPC 把「有什么可同步的」一次问清，手机端据此自动建镜像。
		 * **只回元数据，不回历史** —— 历史仍由 `readHistory` 按需分页拉，
		 * 所以这个调用的流量是几百字节量级，可以放心在启动时跑。
		 *
		 * ## 只列「真正有内容的」对话
		 *
		 * 与 `listWorkspaceSessions` 同样的三重过滤（`blank` / `origin` /
		 * `parentSessionId`），理由见那里的注释。区别只在于这里**跨全部工作区**
		 * 一次返回，而不是按单个 cwd 查。
		 *
		 * @returns `{ workspaces: [{ workspaceId, path, title, sessions: [...] }] }`
		 */
		case 'listWorkspaces': {
			// 每个工作区返回的会话条数上限（默认 50，见下面截断处的注释）。
			// 允许调用方覆盖，但**夹在 1..200**：再大就失去「轻量 RPC」的意义了。
			const rawLimit = Number(params?.limit);
			const limit = Number.isFinite(rawLimit) && rawLimit > 0
				? Math.min(200, Math.floor(rawLimit))
				: 50;
			const registry = ctx.workspaceRegistry;
			if (registry === undefined || typeof registry.list !== 'function') {
				return { workspaces: [] };
			}
			// 先拿一份会话摘要索引：registry 只给 id，标题要从 controller 拿。
			// 一次 `controller.list({})` 比按 id 逐个问便宜得多。
			let summaries = [];
			try {
				const listed = await controller.list({});
				summaries = Array.isArray(listed?.items) ? listed.items : [];
			} catch {
				// 拿不到摘要就只回工作和路径，不阻塞自动同步（标题可由后续路径补）。
			}
			const byId = new Map();
			for (const item of summaries) {
				if (typeof item?.sessionId === 'string') byId.set(item.sessionId, item);
			}
			/*
			 * 电脑上的**置顶集合**（`workspaceRegistry` 的全局 `pinnedSessionIds`）。
			 *
			 * 与工作区归属无关 —— 置顶是整个 registry 一份的（见
			 * `dsh-workspace/lib/index.js` 的 `get pinnedSessionIds()`），所以
			 * 循环外取一次就够，不用每个工作区重取。
			 *
			 * 取不到就当空集合：最坏是手机端同步过来的对话不置顶，
			 * 不会影响其它任何字段。
			 */
			let pinnedList = [];
			try {
				const value = registry.pinnedSessionIds;
				if (Array.isArray(value)) pinnedList = value.map(String);
			} catch {
				pinnedList = [];
			}
			const pinnedSet = new Set(pinnedList);
			const out = [];
			for (const entity of registry.list()) {
				if (typeof entity?.path !== 'string' || entity.path === '') continue;
				const ids = typeof entity.sessionIds === 'object' && entity.sessionIds !== null
					? Array.from(entity.sessionIds)
					: [];
				const sessions = [];
				for (const sessionId of ids) {
					const item = byId.get(sessionId);
					if (item === undefined) continue;
					if (item.blank === true) continue;
					if (item.origin === 'subagent') continue;
					if (typeof item.parentSessionId === 'string' && item.parentSessionId !== '') continue;
					const title = item?.projections?.values?.title;
					sessions.push({
						sessionId,
						title: typeof title === 'string' && title !== '' ? title : '（未命名对话）',
						/*
						 * ## ⚠️⚠️ `updatedAt` 是 **number**，不是字符串（2026-10-29 修）
						 *
						 * 官方 `updatedAt()` 的实现是
						 *（`dsh-api-session-controller/lib/index.js:2124`）：
						 *
						 *     function updatedAt(header, metadata) {
						 *         return Math.max(header.createdAt, metadata?.lastPromptAt ?? 0);
						 *     }
						 *
						 * 也就是**毫秒时间戳（数字）**。而这里原来写的是
						 * `typeof item.updatedAt === 'string' ? item.updatedAt : null`
						 * —— 判据与真实类型完全相反 ⇒ **恒为 `null`**。
						 *
						 * 后果一路传下去：手机端 `autoSyncDesktop` 把 `null` 当标题/
						 * 时间什么都不做，`markSessionNonBlank` 补写事件时也没有真实
						 * 时间可用 ⇒ 手机本地会话的 `updatedAt` 变成**建壳时刻**
						 * （也就是同步那一刻），于是**所有同步过来的对话时间戳几乎
						 * 相同** ⇒ 侧边栏「按时间排序」等于没排 —— 用户报的
						 * 「同步过来的对话没有按时间顺序」就是这个。
						 *
						 * 现在原样透传数字；读不到才 `null`。
						 */
						updatedAt: typeof item.updatedAt === 'number' && Number.isFinite(item.updatedAt)
							? item.updatedAt
							: null,
						/*
						 * 是否在电脑上被**置顶**。
						 *
						 * 置顶是 `workspaceRegistry` 的**全局**集合
						 *（`pinnedSessionIds`，与工作区归属无关），由官方
						 * `pinSession` / `unpinSession` 维护。手机端拿它去
						 * 调自己的 `pinSession`，用户的置顶就会跟着过来。
						 *
						 * 用户 2026-10-29 报障原文：「电脑的某些对话人家是置顶的
						 * 状态，你也没有把置顶的状态同步过来」。
						 */
						pinned: pinnedSet.has(sessionId),
					});
				}
				// ⚠️ **先排序，再截断**。
				//
				// 顺序反了的话，截断保留的是「`sessionIds` 集合的遍历顺序」，
				// 而那是无序的 —— 可能把最近在用的对话切掉、留下几个陈年老会话。
				// 用户看到的现象是「自动同步过来的全是旧对话」。
				//
				// ⚠️ 比较方式也要对：`updatedAt` 是**数字**（见上面那段注释），
				// 原来用 `localeCompare` 比字符串是错的 —— 它按字典序比，
				// `'999'` 会排在 `'1000'` 后面。数字直接相减才是时间序。
				sessions.sort((a, b) => {
					const left = typeof a.updatedAt === 'number' ? a.updatedAt : -1;
					const right = typeof b.updatedAt === 'number' ? b.updatedAt : -1;
					return right - left;
				});
				out.push({
					workspaceId: typeof entity.id === 'string' ? entity.id : null,
					path: entity.path,
					// `basename` 已在本文件导入（第 72 行）；desktop 包没有 mobile 那个
					// `desktopTitle()` 助手，这里直接用 node:path 的。
					title: basename(entity.path),
					// ⚠️ **每个工作区最多回 50 条**。
					//
					// 这是跨公网隧道的 RPC：用户可能有一百多个对话，全量返回会让
					// 响应体膨胀到几十 KB，而手机端只是要「把侧边栏填出来」。
					// 50 条足够覆盖「真的在用的」，更早的本来也要用户主动去找。
					//
					// 需要更多时前端可以带 `limit` 参数单独要（这里读 params）。
					sessions: sessions.slice(0, limit > 0 ? limit : 50),
				});
			}
			return { workspaces: out };
		}

		/**
		 * **同步预估**：告诉手机「这次首次同步要做多少事」。
		 *
		 * ## 为什么需要它（用户诉求 2026-10-28）
		 *
		 * 「首次配对它的带宽压力会比较大，希望你做一个配对成功之后的加载动画……
		 *   最好就是电脑那边提前统计好需要传输的内容有多大，先提前告知手机，
		 *   然后手机的加载动画就有一个具体的进度了……一定要有真实的进度提示，
		 *   而不是那种假的循环动画，那样用户就会以为是卡死了。」
		 *
		 * ## 为什么计量单位是「条数」而不是「字节」
		 *
		 * 实测（见 `listWorkspaces` 的注释）：全部工作区+对话的**元数据**
		 * 只有几十 KB（单条 147 B、100 条约 14 KB），一个 RPC 就传完了，
		 * 用字节做进度条会「瞬间 100%」，反而更假。
		 *
		 * 真正的耗时在手机端：每个对话都要在本地**建一个空壳会话**
		 *（`ctx.sessionController.create()`，与 `syncConversations` 同一套），
		 * 这是几次磁盘 IO + 日志初始化。100 个对话就要建 100 次 ——
		 * 这才是用户等待的那几秒。
		 *
		 * 所以进度按「条数」报，再加上真实的元数据字节数供界面显示。
		 *
		 * @returns {{
		 *   workspaces: number,      // 有对话的工作区个数
		 *   sessions: number,        // 需要同步的对话总条数（= 进度分母）
		 *   bytes: number,           // 元数据体积（仅供参考展示）
		 *   truncated: boolean,      // 是否触发了每工作区 limit 截断
		 * }}
		 */
		case 'estimateSync': {
			/*
			 * ⚠️ 这里**把筛选逻辑抄一遍**，而不是去调 `listWorkspaces`。
			 *
			 * `handleRpc` 是 `switch (method)` 语句块，没有 dispatch 方法，
			 * 内部不能自调（我第一版写成 `this.dispatch(...)` 是错的）。
			 *
			 * 抄写的风险是「两处过滤条件漂移」。所以两处必须保持以下三条完全一致
			 *（与 `listWorkspaces` / `listWorkspaceSessions` 相同）：
			 *   · `blank === true` 跳过（没内容的空壳）
			 *   · `origin === 'subagent'` 跳过（子代理会话不单独显示）
			 *   · 有 `parentSessionId` 跳过（属于某个父会话的分支）
			 * 任何一处改动必须同步另一处，否则「预估说 100 条、实际同步 80 条」，
			 * 进度条永远走不满 —— 那比没有进度条更糟。
			 */
			const rawLimitEst = Number(params?.limit);
			const estLimit = Number.isFinite(rawLimitEst) && rawLimitEst > 0
				? Math.min(200, Math.floor(rawLimitEst))
				: 50;
			const estRegistry = ctx.workspaceRegistry;
			if (estRegistry === undefined || typeof estRegistry.list !== 'function') {
				return { workspaces: 0, sessions: 0, bytes: 0, truncated: false };
			}
			let estSummaries = [];
			try {
				const listedEst = await controller.list({});
				estSummaries = Array.isArray(listedEst?.items) ? listedEst.items : [];
			} catch (error) {
				ctx.logger?.warn?.(`[dsh-jiuling-remote-desktop] 预估同步量失败: ${String(error?.message ?? error)}`);
				return { workspaces: 0, sessions: 0, bytes: 0, truncated: false, failed: true };
			}
			const estById = new Map();
			for (const item of estSummaries) {
				if (typeof item?.sessionId === 'string') estById.set(item.sessionId, item);
			}
			let estWorkspaces = 0;
			let estSessions = 0;
			let estTruncated = false;
			for (const entity of estRegistry.list()) {
				if (typeof entity?.path !== 'string' || entity.path === '') continue;
				const estIds = typeof entity.sessionIds === 'object' && entity.sessionIds !== null
					? Array.from(entity.sessionIds)
					: [];
				let n = 0;
				for (const sessionId of estIds) {
					const item = estById.get(sessionId);
					if (item === undefined) continue;
					if (item.blank === true) continue;
					if (item.origin === 'subagent') continue;
					if (typeof item.parentSessionId === 'string' && item.parentSessionId !== '') continue;
					n += 1;
				}
				if (n === 0) continue;
				estWorkspaces += 1;
				// 与 listWorkspaces 同样按 limit 截断，否则预估会大于实际下发量。
				if (n > estLimit) estTruncated = true;
				estSessions += Math.min(n, estLimit);
			}
			/*
			 * 体积按实测单条约 147 B 估算，不 JSON.stringify 全量 ——
			 * 后者要真的把几百个工作区的结构拼出来，在会话很多的机器上
			 *（实测 578 个会话）反而给电脑加了不必要的内存/CPU 压力。
			 * 这个数字只用来给用户看"约 XX KB"，不需要精确到字节。
			 */
			const estBytes = estSessions * 147;
			return { workspaces: estWorkspaces, sessions: estSessions, bytes: estBytes, truncated: estTruncated };
		}

		/**
		 * 某个会话在电脑上是否正在跑。
		 *
		 * ## 为什么需要它（手机端运行态的**校正**通道）
		 *
		 * 手机靠 `turn/start` / `turn/end` 派生运行态（见手机端 `deriveRunning`），
		 * 那是零额外流量的正路。但它有个**必然的失效场景**：事件流断了。
		 *
		 * 断流期间电脑把这一轮跑完了 ⇒ `turn/end` 没送到手机 ⇒ 手机停在
		 * `running = true` ⇒ 输入框卡在「停止生成」出不来，只能杀应用。
		 * 而隧道掐空闲连接、手机休眠断网都是**常态**，不是异常。
		 *
		 * 所以手机在**重连成功那一刻**调一次这个方法做校正：一问一答，
		 * 每次断流重连才一次，不轮询、不持续占流量。
		 *
		 * ## 为什么走 `controller.list()` 而不是直接读 `ctx.agents`
		 *
		 * 本插件的 `inject` 只声明了 `sessionController` / `sessions` / `webServer`，
		 * **没有 `agents`** —— 直接 `ctx.agents.get()` 会拿到 `undefined` 而静默
		 * 把一切都判成「没在跑」。官方 `summaryFor`（`dsh-api-session-controller/
		 * lib/types/list.js:87`）内部用的正是 `ctx.agents.get(id)?.status === 'running'`，
		 * 而 `controller.list()` 会为每个会话产出该字段。
		 *
		 * 代价是它顺带算了所有会话的摘要（投影、元数据），比只问一个会话重。
		 * 但这条路**每次断流重连才走一次**，且拿到的是官方判定，不自己猜。
		 */
		case 'sessionStatus': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			const result = await controller.list({});
			const items = result?.items ?? [];
			const found = items.find((item) => item?.sessionId === sessionId);
			// 找不到 = 电脑上没有这个会话（被删了 / 还没建好）。此时**不能**说在跑。
			return { sessionId, running: found?.running === true };
		}

		/**
		 * 建会话：手机发起，电脑在**电脑自己的** cwd 下建，并建出真 agent。
		 *
		 * `sessionId` 由手机端生成并传入，两端保持一致，事件才能直接互搬。
		 *
		 * ⚠️ **cwd 必须排除 DSH 自己的状态目录**（见 `assertWorkspaceAllowed`）。
		 * 不排的话，文件围栏可被**绕过** —— 公网实测：
		 *
		 *   1. `createSession { cwd: 'C:\\Users\\XIAOPAN' }`   → ok，工作区被设成主目录
		 *   2. `fileStat { path: '.dsh\\dsh-remote\\pair.json' }` → ok，504 字节
		 *
		 * 第 2 步用的是**相对路径**，所以 `resolveWorkspacePath` 那道围栏
		 * 完全拦不住它 —— 工作区本身就是「整个主目录」。围栏是**以工作区为界**的，
		 * 而工作区可被这一步自由指定，两者一叠加，围栏就等于没有。
		 *
		 * 为什么**只**排除状态目录、而不是给 cwd 加白名单：让用户选任意目录当
		 * 工作区是**功能**（「选电脑上的文件夹」），不能一刀切。而 `$DSH_HOME`
		 * 是**程序自己的**目录（凭据、会话库、插件），用户没有理由拿它当工作区，
		 * 拿它当工作区正是上面那条攻击链。
		 */
		case 'createSession': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			const cwd = params.cwd;
			if (typeof cwd !== 'string' || cwd === '' || !isAbsolute(cwd)) {
				throw new Error('cwd must be an absolute path on this machine');
			}
			// ⚠️ **幂等**：会话已存在就原样返回，不重建。
			//
			// 手机端现在把「建会话」合并进了 `prompt`（见那里的 `desktopPath` 分支），
			// 这个方法因此主要服务于旧客户端和排障。但两边都可能因为网络重试而重复
			// 调用 —— 重复 `controller.create` 在官方那边是**接管已有会话**（
			// `agents.ensureSession` 的 adopt 语义），通常不会丢历史，但会白走一遍
			// 建目录/建 agent 的开销，而且 `assertPresetUnchanged` 可能因为预设
			// 已经不同而直接抛错。在这里挡一道最省事、也最不容易出错。
			const existing = ctx.sessions?.get?.(sessionId);
			if (existing !== undefined) return { sessionId, cwd, agentPreset: null, reused: true };
			assertWorkspaceAllowed(cwd);
			// ⚠️ **走 workspaceId 而不是 cwd**（见 `ensureWorkspaceId` 的注释）：
			// 只有 workspaceId 分支会让官方 `controller.create` 调
			// `workspace.attachSession()`，会话才会出现在电脑侧边栏的**对应工作区**下。
			// 只传 cwd 的话会话会堆在「未分组」—— 那正是用户报的那个问题。
			const workspaceId = await ensureWorkspaceId(ctx, cwd);
			// 官方命令层：内部走 agents.ensureSession()，会建出真 agent。
			const created = await controller.create({
				sessionId,
				workspaceId,
				...(typeof params.presetId === 'string' && params.presetId !== ''
					? { agentPreset: params.presetId }
					: {}),
			});
			return { sessionId: created?.sessionId ?? sessionId, cwd, agentPreset: created?.agentPreset ?? null };
		}

		/**
		 * 发消息：驱动电脑上的 agent。
		 *
		 * ## 附件为什么必须原样透传（用户需求原文：「照片就会被吞了 AI 只能看到我的文字」）
		 *
		 * 手机端 composer 里的附件分两种形态（见 `dsh-client-ui-conversation/lib/client.js`
		 * 的 `serializeAttachments`）：
		 *
		 * | 形态 | 结构 | 谁落盘 |
		 * |---|---|---|
		 * | 图片 | `{type:'image', mediaType, data(base64), name?}` | **电脑**（内联字节，没有 receipt） |
		 * | 文件 | `{type:'file', receiptId}` | **手机**（receipt 是本机 `fileUploads` 发的） |
		 *
		 * 所以图片是**自带字节**的，直接转给电脑的 `controller.prompt` 即可，电脑会
		 * 用 `attachments.admitPromptContent` 落成它自己的 content-addressed 对象。
		 *
		 * ⚠️ `{type:'file', receiptId}` **不能**转发：receipt 是**手机本地**
		 * `fileUploads` 服务发的，电脑的 `stagedFiles` 里没有它，转过去必然
		 * `session/attachment-invalid FILE_NOT_STAGED`。所以那种 part 降级成一句
		 * 文本说明 —— 至少让模型知道用户附了个文件，而不是**静默吞掉**。
		 *
		 * @returns `{sessionId, requestId, accepted, forwarded, skipped}`。
		 */
		case 'prompt': {
			const sessionId = params.sessionId;
			const text = typeof params.text === 'string' ? params.text : '';
			// 附件形态的 content（手机端传过来的原始数组）。没有就退回纯文本。
			const incoming = Array.isArray(params.content) ? params.content : null;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			// ⚠️ 判据必须包含附件：用户只发一张图、不打字，是完全正常的用法。
			// 早先只校验 `text.trim() === ''` 会把这种消息直接拒掉。
			const content = [];
			let skipped = 0;
			if (incoming === null) {
				if (text.trim() === '') throw new Error('sessionId and non-empty text required');
				content.push({ type: 'text', text });
			} else {
				for (const part of incoming) {
					if (part?.type === 'text') {
						if (typeof part.text === 'string' && part.text !== '') content.push({ type: 'text', text: part.text });
						continue;
					}
					if (part?.type === 'image') {
						// 只认官方 `imageMediaType()` 放行的四种；其余一律降级成文本，
						// 免得电脑端抛 `UnsupportedImageMediaTypeError` 把整条消息废掉。
						const mediaType = part.mediaType;
						if (
							typeof part.data === 'string' &&
							part.data !== '' &&
							(mediaType === 'image/png' ||
								mediaType === 'image/jpeg' ||
								mediaType === 'image/webp' ||
								mediaType === 'image/gif')
						) {
							content.push({
								type: 'image',
								mediaType,
								data: part.data,
								...(typeof part.name === 'string' && part.name !== '' ? { name: part.name } : {}),
							});
						} else {
							skipped += 1;
						}
						continue;
					}
					// `file` 与任何未知形态：receipt 属于手机，电脑解不开（见上表）。
					skipped += 1;
				}
				if (content.length === 0) {
					if (skipped === 0) throw new Error('prompt content must include text or an image');
					// 只有解不开的附件：给模型一句说明，别让它以为用户什么都没发。
					content.push({
						type: 'text',
						text: `（用户附了 ${skipped} 个文件，但文件传输通道尚未接通，只收到了这条说明。）`,
					});
				}
			}
			// ⚠️ requestId 必须唯一：官方用它做幂等去重，两边都是 undefined 会误判重复。
			const requestId = typeof params.requestId === 'string' && params.requestId !== ''
				? params.requestId
				: randomUUID();
			// ## 按需建会话（用户要求：这几步打包成一个请求发过来）
			//
			// 用户原话：
			//   「我们在电脑上创建一个新对话，顺序是：点击"新对话" → 选择工作区 →
			//    选择预设 → 选择模型 → 发消息。前四步其实它不是一个有效的新建对话，
			//    电脑也是必须发消息之后，它才创建对话的。」
			//   「我觉得新建对话、选择预设、选择模型还有发送消息这么多个步骤，你可以
			//    打包成一个网络请求发过去就可以了，电脑拿到这个请求之后再解析就OK了」
			//
			// 所以手机端**选工作区时不再预先建会话**，而是把 `desktopPath` / `presetId` /
			// `model` 一起塞进这个 `prompt` 请求。这里按官方的真实顺序补做前四步：
			//
			//   create（带 agentPreset） → selectModel → prompt
			//
			// 三个 `await` 都在**同一个 RPC 里**完成，所以对手机来说仍然只过一趟隧道。
			//
			// ⚠️ **幂等**：`params.desktopPath` 只有手机端认定「这个会话在电脑上还没出生」
			// 时才会带。会话已经存在时这里直接跳过（`sessions.get` 有值就不重建），
			// 所以哪怕手机端因为网络抖动重发了同一条带 `desktopPath` 的请求，
			// 也不会把会话推翻重建、丢掉已有历史。
			if (typeof params.desktopPath === 'string' && params.desktopPath !== '') {
				const existing = ctx.sessions?.get?.(sessionId);
				if (existing === undefined) {
					if (!isAbsolute(params.desktopPath)) {
						throw new Error(`desktopPath must be an absolute path on this machine: ${params.desktopPath}`);
					}
					assertWorkspaceAllowed(params.desktopPath);
					// 预设只在**新建**时给得进去（官方 `create` 接受 `agentPreset`；
					// 会话建好之后预设已经固化在 agent 上，不能再改）。
					//
					// ⚠️ **走 workspaceId 而不是 cwd**：只有 workspaceId 分支会触发
					// 官方 `workspace.attachSession()`，会话才会归到电脑侧边栏的
					// **对应工作区**下，而不是「未分组」（详见 `ensureWorkspaceId`）。
					const workspaceId = await ensureWorkspaceId(ctx, params.desktopPath);
					await controller.create({
						sessionId,
						workspaceId,
						...(typeof params.presetId === 'string' && params.presetId !== ''
							? { agentPreset: params.presetId }
							: {}),
					});
					// 模型必须在会话存在之后设（官方 `selectModel` 走 `resolveAgent`）。
					// ⚠️ 失败**不阻断**发送：用户可能选了一个电脑上没有的模型
					//（比如那个模型后来被删了），此时退回电脑默认模型继续回复，
					// 比整条消息发不出去合理。日志留一条便于排查。
					const model = params.model;
					if (
						model !== null &&
						typeof model === 'object' &&
						typeof model.provider === 'string' &&
						model.provider !== '' &&
						typeof model.model === 'string' &&
						model.model !== ''
					) {
						try {
							await controller.selectModel({
								sessionId,
								provider: model.provider,
								model: model.model,
								...(typeof model.reasoningEffort === 'string' && model.reasoningEffort !== ''
									? { reasoningEffort: model.reasoningEffort }
									: {}),
							});
						} catch (error) {
							ctx.logger?.warn?.(
								`[dsh-remote] 会话 ${sessionId} 的模型 ${model.provider}/${model.model} 设置失败，改用默认模型: ${String(
									error?.message ?? error,
								)}`,
							);
						}
					}
				}
			}
			// ⚠️ **必须传第二个参数 `signal`**，`undefined` 会直接炸。
			//
			// 官方 `dsh-api-session-controller/lib/index.js:3096`：
			//   prompt(request, signal) { signal.throwIfAborted(); ... }
			// 它**无条件**对 `signal` 调 `throwIfAborted()`，所以漏传就是
			//   `Cannot read properties of undefined (reading 'throwIfAborted')`
			// 报错文案完全看不出是参数漏传，症状是「手机发了消息、电脑毫无反应」。
			//
			// 传一个永不过期的 signal：远端调用的生命周期不由本机的 UI 决定，
			// 真正的中断走 `cancel` 那条 RPC。
			const accepted = await controller.prompt(
				{
					sessionId,
					requestId,
					content,
					...(params.mode === 'steer' ? { mode: 'steer' } : {}),
				},
				new AbortController().signal,
			);
			return { sessionId, requestId, accepted: accepted?.accepted === true, forwarded: content.length, skipped };
		}

		/** 中断当前生成。 */
		case 'cancel': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			const accepted = await controller.cancel({ sessionId });
			return { sessionId, accepted: accepted?.accepted === true };
		}

		/**
		 * 手机回答一条待决的 ask / 授权弹窗。
		 *
		 * 对应 `startRelay` 里 `askPhone()` 推出去的那条 `ask` 事件。
		 * 手机拿到 `requestId` 后连同用户的选择一起回传。
		 *
		 * ⚠️ 三种「答不上」都返回 `ok: false` 而**不抛错**：
		 *   · `expired` —— 超时了（ASK_TIMEOUT_MS）或这一轮已被中断；
		 *   · `no-relay` —— 老版本桌面端没有这条通道；
		 *   · `bad-request` —— 参数形状不对。
		 * 抛错会让手机端弹一个看不懂的红条，而这不是「出错了」，
		 * 是「这个弹窗已经不用答了」—— 手机端应当静默收起卡片。
		 */
		case 'answerAsk': {
			if (io?.answerAsk === undefined) return { ok: false, reason: 'no-relay' };
			const requestId = params.requestId;
			if (typeof requestId !== 'string' || requestId === '') return { ok: false, reason: 'bad-request' };
			return io.answerAsk(requestId, params.answer);
		}

		/**
		 * **列出当前所有等手机回答的交互**（ask 补拉通道）。
		 *
		 * ## 为什么需要它 —— 这是粉丝反馈那个 bug 的正解
		 *
		 * 用户原话（2026-10-28）：
		 *
		 * 「他电脑上面已经有一个会话了，但此时他是没有在手机 APP 内的，
		 *   它已经发起了一个 ask 弹窗，然后此时用户去打开我们的 APP，
		 *   它虽然消息同步过来了，但是弹窗没有同步过来，也就是说它如果是
		 *   后打开的话，它的弹窗是无法显示的，用户就无法审批。」
		 *
		 * ask 本质是**推送**（电脑 → 手机 SSE），手机没连着就收不到。
		 * 而原来的实现在「手机没连着」时直接 fallback、**连请求都不登记**，
		 * 所以后来打开手机时电脑上根本没东西可补 —— 请求从不存在。
		 *
		 * 现在改成：无论手机在不在都登记，手机**连上来那一刻**调这个方法
		 * 把所有还活着的请求取走，自己重建成弹窗。
		 *
		 * ## 只回「还没过期」的
		 *
		 * `pendingAsks` 里的条目都有 5 分钟定时器（`ASK_TIMEOUT_MS`），
		 * 到点会自己 `cleanup()` 移除。所以这里不需要额外判过期 ——
		 * 还在表里就是还有效。但以防定时器被意外清掉，这里再按 `at` 兜一层。
		 *
		 * @returns `{ requests: [{ requestId, kind, sessionId, payload, at, ageMs }] }`
		 */
		case 'pendingAsks': {
			if (io?.pendingAsks === undefined) return { requests: [] };
			return { requests: io.pendingAsks() };
		}

		/** 列电脑上的目录（手机的「电脑」页签用）。 */
		case 'listDirectory':
			return listDirectory(params.path);

		/**
		 * 列电脑上某个会话工作区里的一层目录（**含文件**）。
		 *
		 * ⚠️ **不要拿 `listDirectory` 顶替**：那个是给「选工作区」对话框用的，
		 * 只列子目录（文件被 `continue` 跳过）。而官方侧边栏的文件树要
		 * `{name, type, size?}` 三样齐全，拿不到 `type:'file'` 的条目就等于
		 * 侧边栏里**一个文件都看不见** —— 这正是本方法存在的理由。
		 *
		 * 直接把官方的 `workspaceFiles.list()` 结果原样带回去，客户端就不需要
		 * 任何形状转换（官方 `sidebar-files` 的 `createList` 只挑
		 * `entries` 与 `truncated` 两个字段）。
		 */
		case 'fileList': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			return await files.list(scope, resolveWorkspacePath(scope, params.path), idleSignal());
		}

		/**
		 * 读电脑上一个会话工作区里的文本文件（分页）。
		 *
		 * @returns 官方 `workspaceFiles.read` 的原样结果
		 *   （`{offset, text, lines, eof, absolutePath, version, bytes?}`）。
		 */
		case 'fileRead': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			const range = params.offset === undefined ? {} : { offset: params.offset };
			return await files.read(scope, resolveWorkspacePath(scope, params.path), range, idleSignal());
		}

		/**
		 * 取电脑上一个会话工作区里某个路径的元信息（`{absolutePath, version, bytes?}`）。
		 *
		 * 官方预览面板靠 `version` 判断文件有没有被外部改动过，缺了它就不会刷新。
		 */
		case 'fileStat': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			return await files.stat(scope, resolveWorkspacePath(scope, params.path), idleSignal());
		}

		/**
		 * 读电脑上一个会话工作区里的**字节**（图片等二进制预览用）。
		 *
		 * ⚠️ 两端的 RPC 都是 JSON，`Uint8Array` 过不去 —— 这里过一道 base64。
		 * 手机端收到后还原成 `Uint8Array`，官方预览面板才认。
		 */
		case 'fileReadBytes': {
			const files = workspaceFilesOf(ctx);
			const scope = await workspaceScopeOf(ctx, params.sessionId);
			const options = params.range === undefined ? {} : { range: params.range };
			const result = await files.readBytes(scope, resolveWorkspacePath(scope, params.path), options, idleSignal());
			const { data, ...meta } = result ?? {};
			return { ...meta, data: Buffer.from(data ?? []).toString('base64') };
		}

		/**
		 * 列某个**工作区目录**下的历史会话（只给名称，供手机端勾选同步）。
		 *
		 * ## 为什么必须在电脑端过滤（用户需求一 + 实测数据）
		 *
		 * `controller.list()` 在本机返回 **578 个会话**，每个都带完整
		 * `projections`（标题、模型、用量……），JSON 有几 MB。手机走的是免费
		 * Cloudflare 隧道，全量下发既慢又费流量。
		 *
		 * 所以过滤在电脑侧做完，**过网的只有 `{sessionId, title, updatedAt}`**。
		 * 用户原话：「只要名称」。
		 *
		 * ## 标题从哪来
		 *
		 * `dsh-session-title` 注册了投影键 `title`
		 *（`titleProjectionDefinition.apply: event.type === 'session/title' ? event.data.title : state`），
		 * 所以 `item.projections.values.title` 就是侧边栏显示的那个标题。
		 * 实测本机返回形如 `"全面了解远程插件"`。
		 *
		 * @returns `{ sessions: [{sessionId, title, updatedAt}] }`。
		 */
		case 'listWorkspaceSessions': {
			const cwd = params.cwd;
			if (typeof cwd !== 'string' || cwd === '') throw new Error('cwd required');
			assertWorkspaceAllowed(cwd);
			// ⚠️ 用 `realpath` 归一后再比：手机传来的是它记忆里的路径，而电脑上
			// 同一个目录可能经符号链接/短路径名到达，直接字符串比会漏。
			let canonical = cwd;
			try {
				canonical = realpathSync(cwd);
			} catch {
				// 目录不在了就按原样比，至少不会抛错让整个列表失败。
			}
			const norm = (value) => (process.platform === 'win32' ? value.toLowerCase() : value);
			const want = norm(canonical);
			/*
			 * 电脑上的置顶集合 —— 供每条会话标 `pinned`（理由见
			 * `listWorkspaces` 里同一段注释）。取不到就当空集合。
			 */
			let pinnedSet = new Set();
			try {
				const value = ctx.workspaceRegistry?.pinnedSessionIds;
				if (Array.isArray(value)) pinnedSet = new Set(value.map(String));
			} catch {
				pinnedSet = new Set();
			}
			const pinnedOf = (sessionId) => pinnedSet.has(String(sessionId));
			const result = await controller.list({});
			const items = result?.items ?? [];
			const sessions = [];
			for (const item of items) {
				if (typeof item?.cwd !== 'string') continue;
				if (norm(item.cwd) !== want) continue;

				// ## ⚠️ 必须过滤「空会话」和「子代理会话」
				//
				// 原来只按 cwd 筛，于是同步框里会冒出两类用户根本不该看见的东西
				//（真机实测：用户看到一堆「（未命名对话）」，还在电脑上找不到）：
				//
				// ① **blank 空会话** —— 官方 `list()` 里 `blank: true` 的那些，
				//    是引擎为「预建复用」留的 400 字节空壳，官方 UI 的侧边栏
				//    **完全不渲染**它们。我们列出来，用户点同步 → 手机上也生成
				//    一个空会话 → 越攒越多（真机实测 1500+ 个）。
				//
				// ② **子代理会话** —— 电脑上 agent 派生出来的子会话
				//   （`header.origin === 'subagent'`，同时带 `parentSessionId`）。
				//    它们属于父会话的执行细节，不是用户的「对话」。官方 UI 同样
				//    不列。若被当成普通会话同步，会整批涌进「未分组」工作区
				//   （用户报障：「子代理会话涌进未分组」）。
				//
				// 判据用官方自己的字段，不靠猜（`listFields()` 与
				// `summarizeCold()` 都会给出 `blank` / `origin` / `parentSessionId`）。
				if (item?.blank === true) continue;
				if (item?.origin === 'subagent') continue;
				if (typeof item?.parentSessionId === 'string' && item.parentSessionId !== '') continue;

				const title = item?.projections?.values?.title;
				sessions.push({
					sessionId: item.sessionId,
					title: typeof title === 'string' && title !== '' ? title : '（未命名对话）',
					// ⚠️ `updatedAt` 是 **number**（毫秒时间戳），不是字符串 ——
					// 详见 `listWorkspaces` 里的长注释。这一处原来也是同样的类型错误。
					updatedAt: typeof item.updatedAt === 'number' && Number.isFinite(item.updatedAt)
						? item.updatedAt
						: null,
					pinned: pinnedOf(item.sessionId),
				});
			}
			/*
			 * 新的排前面 —— 用户要同步的多半是最近聊过的。
			 *
			 * ⚠️ 原来是 `String(b.updatedAt ?? '').localeCompare(...)`。
			 * 配合上面那个"永远 null"的类型错误，两者一起造成同一个后果：
			 * 排序**根本没生效**（全 `null` ⇒ 比较结果恒 0 ⇒ 保持插入顺序，
			 * 而插入顺序是 `controller.list` 的顺序，不是用户期望的顺序）。
			 *
			 * 现在 `updatedAt` 是数字，直接相减；缺值的排最后。
			 */
			sessions.sort((a, b) => {
				const left = typeof a.updatedAt === 'number' ? a.updatedAt : -1;
				const right = typeof b.updatedAt === 'number' ? b.updatedAt : -1;
				return right - left;
			});
			return { sessions };
		}

		/**
		 * 读一段历史（按需、分页，供手机端「点进去才加载」）。
		 *
		 * 直接转发官方 `controller.page()`
		 *（`dsh-api-session-controller/lib/index.js:2954` → `history.page()`），
		 * 它返回 `{ records, hasMore }`，是纯 JSON，正好能过隧道。
		 *
		 * ## 为什么这条路径是对的（而不是把历史全搬到手机）
		 *
		 * 手机本地 session 的 seq 是**连续从 0 开始**的
		 *（`dsh-session/lib/index.js:1195` 的 `get seq() { return this.log.length }`，
		 * `append()` 里 `seq: SessionSeq(this.log.length)` 写死），
		 * 所以「只在手机存最近 10 条」物理上不可能 —— 中间会留一个巨大的 seq 空洞。
		 *
		 * 因此同步来的会话**不在手机落一份完整日志**，而是：
		 *   - 打开时读最后一页（`beforeSeq` 不传 = 从尾部往前）
		 *   - 上滑时带上已加载的最小 seq 继续往前翻
		 *
		 * 实测带宽差：最大的会话全量 155 MB，而「最近 10 条」只要 131 KB —— 1200 倍。
		 *
		 * @returns `{ records, hasMore }`。
		 */
		/**
		 * 读某会话在**电脑上算好的投影**（`tokenUsage` / `contextPressure` / `title` …）。
		 *
		 * ## 为什么必须单独开一个 RPC（用户 2026-10-21 报障）
		 *
		 * 用户原话：「底部的 token 信息以及上下文信息，都没有显示了」。
		 *
		 * 手机端显示底部那两段，唯一的数据源是 `follow` 首帧里的
		 * `projections.values.tokenUsage` / `.contextPressure`
		 *（依据 `dsh-client-ui-chat/lib/client.js` 的 `UsagePill`：
		 * `const usage = useProjection("tokenUsage"); if (usage === void 0) return null;`
		 * —— 拿不到就**整块不渲染**，不是显示 0）。
		 *
		 * 而同步来的会话在手机上**没有本地日志**，本地投影永远是空的。
		 * 原本想复用 `readHistory` 返回的 `projections`，但那行不通：
		 * `SessionHistoryController.page()`（`dsh-api-session-controller/lib/index.js:1475`）
		 * 只返回 `{ records, hasMore }` —— **根本没有 projections 字段**。
		 * 带 projections 的是 `follow()` 的首帧，不是 `page()`。
		 *
		 * 所以这里直接读投影服务。两条路（与官方 `projectionsFor` 一致）：
		 *
		 *   1. 活会话 ⇒ `ctx.sessionProjections.cachedSnapshot(session)`；
		 *   2. 冷会话 ⇒ `ctx.get('sessionProjectionCache').cachedSnapshot(header)`。
		 *
		 * 冷会话那条会读**磁盘上的投影缓存**，所以 App 没打开过的会话也能拿到 ——
		 * 这正是同步会话的常态。
		 *
		 * @returns `{ projections: { asOfSeq, values } }`，拿不到时 `values` 为空对象。
		 */
		case 'readProjections': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			/** 只挑前端真正消费的键，避免把 `inbox` / `todos` 这类时序敏感的东西带过去。 */
			const pick = (values) => {
				const out = {};
				if (values === null || typeof values !== 'object') return out;
				if (values.tokenUsage !== undefined) out.tokenUsage = values.tokenUsage;
				if (values.contextPressure !== undefined) out.contextPressure = values.contextPressure;
				if (typeof values.title === 'string' && values.title !== '') out.title = values.title;
				return out;
			};
			// ① 活会话：内存投影最准。
			try {
				const live = ctx.sessions?.get?.(sessionId);
				if (live !== undefined && live !== null) {
					const block = ctx.sessionProjections?.cachedSnapshot?.(live);
					if (block !== undefined && block !== null) {
						const values = pick(block.values);
						if (Object.keys(values).length > 0) {
							return { projections: { asOfSeq: block.asOfSeq, values } };
						}
					}
				}
			} catch (error) {
				void debugLog(`readProjections 内存投影失败 ${sessionId}: ${String(error?.message ?? error)}`);
			}
			// ② 冷会话：读磁盘投影缓存。
			try {
				const cache = ctx.get?.('sessionProjectionCache');
				const header = { id: sessionId };
				const block =
					(typeof cache?.cachedSnapshot === 'function' ? cache.cachedSnapshot(header) : undefined) ??
					(typeof cache?.cachedPredecessorTitle === 'function' ? cache.cachedPredecessorTitle(header) : undefined);
				if (block !== undefined && block !== null) {
					const values = pick(block.values);
					if (Object.keys(values).length > 0) {
						return { projections: { asOfSeq: block.asOfSeq, values } };
					}
				}
			} catch (error) {
				void debugLog(`readProjections 磁盘投影失败 ${sessionId}: ${String(error?.message ?? error)}`);
			}
			return { projections: { asOfSeq: -1, values: {} } };
		}

		case 'readHistory': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			if (typeof controller.page !== 'function') throw new Error('controller.page 不可用');
			const address = { kind: 'session', sessionId };
			// ⚠️⚠️ **`throughSeq` 是必填项，而且它必须与 `beforeSeq` 配套算。**
			//
			// ## 为什么必填
			//
			// `SessionHistoryController.page` 的第一行就是
			// `validatePageRequest(request)`，要求
			// `Number.isSafeInteger(request.throughSeq) && request.throughSeq >= -1`
			//（`dsh-api-session-controller/lib/index.js:1373`）—— 不传直接抛
			// `gateway/bad-request: throughSeq must be an integer greater than or equal to -1`。
			// 官方前端永远带：`readPage(request, throughSeq, signal)` 把
			// `currentCursor()` 塞进去（`lib/client.js:427`）。
			//
			// ## 为什么不能简单地固定成 -1
			//
			// `paginate()`（同文件 `:1601`）的取页区间是
			//
			// ```js
			// const end = Math.min(throughSeq + 1, beforeSeq ?? throughSeq + 1);
			// ```
			//
			// 传 `throughSeq = -1` 又带 `beforeSeq` 时，`end = Math.min(0, beforeSeq) = 0`
			// ⇒ `events.slice(cut, 0)` **永远是空数组**。「上滑加载更多」会拿到
			// 一页空数据，前端判成「历史到顶了」，加载更多直接失效。
			//
			// 所以这里把 `beforeSeq` 翻译成上界：`throughSeq = beforeSeq - 1`。
			// 语义正是「要 beforeSeq 之前的那些」，与前端 `prepend` 的意图一致
			//（它要的是 `firstCursor` 之前的一页，而 `beforeSeq` 就是 `firstCursor`）。
			//
			// 不传 `beforeSeq`（首屏）时用「日志末尾的 seq」，见下面那段 ⚠️。
			const hasBefore =
				typeof params.beforeSeq === 'number' && Number.isSafeInteger(params.beforeSeq) && params.beforeSeq >= 0;
			// ⚠️⚠️ **首屏（没有 `beforeSeq`）绝对不能用 `throughSeq = -1`。**
			//
			// 实测验证过（复刻 `paginate()` 跑了一遍）：`throughSeq = -1` 时
			//
			// ```js
			// const end = Math.min(throughSeq + 1, beforeSeq ?? throughSeq + 1)  // = min(0, 0) = 0
			// return { events: events.slice(cut, 0), ... }                       // ← 永远空数组
			// ```
			//
			// 也就是说 `-1` 不是「不限上界」的哨兵，而是「取到第 -1 条为止」= 空。
			// 官方前端首屏**根本不走 `page`**（走 `follow` 的 snapshot），所以这个
			// 坑在官方代码里暴露不出来；我们主动用 `page` 取尾部一页，就会踩到。
			//
			// 正确做法：自己算出「日志末尾的 seq」传进去。
			// `dsh-session/lib/index.js:1195` 的 `get seq() { return this.log.length }`
			// 是 O(1)，取它再减 1 就是最后一条的 seq。
			//
			// ⚠️ **`ctx.sessions.get()` 只返回内存里活着的会话**，冷会话（很久没碰、
			// 已从内存卸载的）会返回 `undefined`。不能因为拿不到就断定它是空的。
			//
			// 所以策略是：能拿到 `seq` 就直接用它当上界；拿不到（冷会话）就走
			// `sessionQuery.observeSession()` 读它自带的 `cursor` —— 见下面那段 ⚠️。
			let total = -1;
			try {
				const local = ctx.sessions?.get?.(sessionId);
				if (local !== undefined && typeof local.seq === 'number') total = local.seq;
			} catch {
				total = -1;
			}
			if (total === 0) {
				// 确定是空会话（在内存里且 seq 为 0）：`page()` 对空会话必然抛
				// 「past cursor -1」，直接给空页，别让它报错。
				return { records: [], hasMore: false };
			}
			if (total < 0 && !hasBefore) {
				// ⚠️ **冷会话（不在内存里）的首屏**：`ctx.sessions.get()` 返回
				// `undefined`，拿不到末尾 seq。
				//
				// ⚠️ **不能用 `page({throughSeq: 0})` 探测来“激活”会话**：
				// `page()` 内部走 `sourceFor()` → `sessionQuery.observeSession()`，
				// 那是**只读观测**，**不会**把这个会话塞进 `ctx.sessions`。
				// 探完之后 `sessions.get()` 照样是 `undefined`（这一点查过源码，
				// `sourceFor` 只碰 `sessionQuery`，不碰 `sessions`）。
				//
				// 正解：走同一个 `sessionQuery`。它返回的 observation **自带
				// `cursor`**（`dsh-session-query/lib/index.js:487`：
				// `cursor: seq === 0 ? -1 : SessionSeq(seq - 1)`）—— 那正是
				// 「日志末尾那条的 seq」，也就是我们要的 `throughSeq`，不用自己算。
				//
				// ⚠️ **不要读 `observation.events`**：它是**懒加载 getter**
				//（同文件 `:485` 的 `materialized ??= session.snapshotEvents(...)`），
				// 一碰就会把整份日志快照进内存 —— 对大历史会话正是 155 MB 那个量级。
				// 只读 `cursor` / `inheritedEventCount` 这类标量就没有这个代价。
				//
				// ⚠️ observation 带 `[Symbol.dispose]`，**必须** dispose，
				// 否则每次同步会话首屏都漏一份观测租约。
				const query = ctx.get?.('sessionQuery');
				if (query === undefined || query === null || typeof query.observeSession !== 'function') {
					return { records: [], hasMore: false };
				}
				let observation;
				try {
					observation = await query.observeSession(sessionId, { projectionMode: 'none' });
					const cursor = observation?.cursor;
					// `cursor === -1` ⇒ 空会话；否则末尾 seq = cursor。
					total = typeof cursor === 'number' && Number.isSafeInteger(cursor) ? cursor + 1 : 0;
				} catch {
					// 会话不存在 / 持久化读失败 —— 按空页处理。
					total = 0;
				} finally {
					try {
						observation?.[Symbol.dispose]?.();
					} catch {
						/* dispose 失败不影响返回 */
					}
				}
				if (total === 0) return { records: [], hasMore: false };
			}
			// `total > 0` ⇒ 末尾 seq = total - 1。
			// 仍然 `-1` ⇒ 极端的冷会话兜底：`throughSeq: 0`（至少能看到最早一页）。
			//
			// ⚠️⚠️ **手机端显式给了 `throughSeq` 时必须原样用，不能再自己算。**
			//
			// 这是 1.0.22 真机踩到的硬坑：前端 `RemoteJournalStream.prepend()`
			// 拿到 page 后立刻 `assertPageThrough(page, through)`，判据是
			// **`tail(entries) === through`（精确相等）**，不等就抛
			// `session event stream page did not end at its requested cursor`，
			// 界面显示「历史加载失败」。
			//
			// 也就是说：**这一页的最后一个事件 seq 必须精确等于 `throughSeq`**。
			// 手机端手里就有前端的 `throughSeq`（同一个 seq 坐标系），透传过来
			// 比电脑端猜「最后一条在哪」可靠得多 —— 尤其用户刚发过消息时，
			// 两边的「末尾」会差一拍。
			const hasThrough =
				typeof params.throughSeq === 'number' &&
				Number.isSafeInteger(params.throughSeq) &&
				params.throughSeq >= -1;
			let throughSeq;
			if (hasThrough) {
				throughSeq = params.throughSeq;
			} else if (hasBefore) {
				throughSeq = params.beforeSeq - 1;
			} else if (total > 0) {
				throughSeq = total - 1;
			} else {
				throughSeq = 0;
			}
			const request = { address, throughSeq };
			// ⚠️ **不再传 `beforeSeq`**：`paginate()` 里
			// `end = Math.min(throughSeq + 1, beforeSeq ?? throughSeq + 1)`，
			// 而 `throughSeq` 已经等于 `beforeSeq - 1`，两者算出来一模一样。
			// 少传一个字段就少一处「两个上界不一致」的可能。
			request.maxMessages =
				typeof params.maxMessages === 'number' && Number.isSafeInteger(params.maxMessages) && params.maxMessages > 0
					? params.maxMessages
					: 10;
			// ⚠️ **`throughSeq` 超出电脑实际末尾时要夹回末尾**，不能让 `page()`
			// 抛 `session page through seq N is past cursor M`。
			//
			// 什么时候会超：手机端手里的 `throughSeq` 来自它自己的实时流游标，
			// 而电脑那边的日志可能还没追上（或用户刚在手机上发了一条、电脑的
			// 那条还没落盘）。夹回末尾至少能返回一页真实数据，比整页报错好 ——
			// 前端 `assertPageThrough` 仍然要求 `tail === throughSeq`，所以
			// **夹回之后必须把 `throughSeq` 一起改成实际的 tail**，否则前端照样抛。
			//
			// 实现方式：先按请求的 `throughSeq` 取，取到之后看 tail；
			// tail 与请求值不等就说明被夹过，此时返回真实 tail（前端会拿
			// `page` 的 entries 自行判断，`assertPageThrough` 用的是它**自己的**
			// `through`，所以这里必须让两者一致 —— 见下面 catch 的处理）。
			try {
				return await controller.page(request, idleSignal());
			} catch (error) {
				const message = String(error?.message ?? error);
				// 只兜「through 越界」这一种，其余照旧抛（那是真 bug，不该吞）。
				if (message.indexOf('is past cursor') < 0) throw error;
				// 越界 ⇒ 退回「末尾一页」：拿 `total - 1`（冷会话则是 0）。
				const fallback = total > 0 ? total - 1 : 0;
				if (fallback === throughSeq) throw error;
				request.throughSeq = fallback;
				return await controller.page(request, idleSignal());
			}
		}

		/**
		 * 读一张图片附件的原始字节（给手机端回看历史用）。
		 *
		 * ## 为什么需要（这是「手机上自己发的图看不到」的根因）
		 *
		 * 同步会话在手机本地不落盘、不存附件，所以前端 `MessageImage` 走
		 * `remote.session.attachment({sessionId, attachmentId})` 时，
		 * 手机本地引擎找不到那张图 ⇒ reject ⇒ 缩略图显示「图片加载失败」。
		 *
		 * 而**图片本身是电脑存的**（手机发消息时把 base64 内联在 `prompt` 里，
		 * 电脑用 `attachments.admitPromptContent` 落盘）。所以正确的读法就是
		 * 问电脑要。
		 *
		 * 直接转官方 `controller.attachment()`（`dsh-api-session-controller/lib/index.js:911`），
		 * 它返回 `{ attachment: ref, data: base64 }` —— 与手机端前端
		 * `readAttachment()` 期望的形状**逐字一致**，所以客户端零改动。
		 *
		 * ⚠️ **不要自己用 `attachments.readImage()` 重写**：官方那条分支还做了
		 * 两个必要检查 ——
		 *   1. `readSessionState()` 确认会话存在（否则报 session/not-found）；
		 *   2. `referencedImage(events, attachmentId)` 确认这张图**确实被该会话引用**。
		 * 少了第 2 条，任何人拿到 attachmentId 就能读走会话里的任意图片，
		 * 那是个越权漏洞。
		 */
		case 'attachment': {
			const sessionId = params.sessionId;
			const attachmentId = params.attachmentId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			if (typeof attachmentId !== 'string' || attachmentId === '') throw new Error('attachmentId required');
			if (typeof controller.attachment !== 'function') throw new Error('controller.attachment 不可用');
			return await controller.attachment({ sessionId, attachmentId });
		}

		/**
		 * 按 **seq 区间**读一段原始事件（给手机端补洞用）。
		 *
		 * 与 `readHistory` 的区别：那个按「消息边界」分页（`paginate()`），
		 * 返回的是一组完整的 user/assistant 消息；这个按 seq 精确切片，
		 * 用于「手机端发现事件跳号，要把中间缺的那几条捞回来」。
		 *
		 * `fromSeq` / `toSeq` 都是**闭区间**，且必须是安全整数。
		 * 返回 `{ events }`，按 seq 升序、保证连续。
		 *
		 * ⚠️ 这是**兜底路径**，正常不会走（电脑端 SSE 已经保证从 `fromSeq`
		 * 之后连续推送）。所以不追求效率，只追求「一条不漏、一条不多」。
		 * ⚠️ 一次最多回 `maxEvents`（默认 200）条：补洞场景缺口必然很小，
		 * 缺口大到这个量级说明流已经彻底错乱，该整条重开而不是硬补。
		 */
		case 'readEventRange': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			const fromSeq = params.fromSeq;
			const toSeq = params.toSeq;
			if (typeof fromSeq !== 'number' || !Number.isSafeInteger(fromSeq) || fromSeq < 0) {
				throw new Error('fromSeq must be a non-negative integer');
			}
			if (typeof toSeq !== 'number' || !Number.isSafeInteger(toSeq) || toSeq < fromSeq) {
				throw new Error('toSeq must be an integer >= fromSeq');
			}
			const maxEvents =
				typeof params.maxEvents === 'number' && Number.isSafeInteger(params.maxEvents) && params.maxEvents > 0
					? params.maxEvents
					: 200;
			if (toSeq - fromSeq + 1 > maxEvents) {
				throw new Error(`range too large (${toSeq - fromSeq + 1} > ${maxEvents})`);
			}
			// 和 `readHistory` 一样：活会话直接快照，冷会话走 `sessionQuery`。
			const live = ctx.sessions?.get?.(sessionId);
			let source = null;
			if (live !== undefined && typeof live.snapshotEvents === 'function') {
				source = live.snapshotEvents();
			} else {
				// ⚠️ **冷会话要物化整份日志，必须设上限。**
				//
				// `observation.events` 是惰性 getter，一碰就把整个日志快照进内存。
				// 实测本机最大的会话是 41 MB 磁盘 / **155 MB 明文 / 29638 条** ——
				// 为了补一两条缺口把它整份读进内存是不可接受的。
				//
				// 所以先只读标量 `cursor`（末尾 seq），超过上限就直接放弃：
				// 补洞是**兜底**，放弃的后果只是手机端断流重连一次，而重连时
				// SSE handler 会用同样的 `sessionQuery` 补齐（那边是必须做的），
				// 代价已经在那里付过一次，这里不该再付一次。
				const query = ctx.get?.('sessionQuery');
				if (query === undefined || query === null || typeof query.observeSession !== 'function') {
					return { events: [] };
				}
				let observation;
				try {
					observation = await query.observeSession(sessionId, { projectionMode: 'none' });
					const cursor = observation?.cursor;
					const total = typeof cursor === 'number' && Number.isSafeInteger(cursor) ? cursor + 1 : 0;
					if (total === 0) return { events: [] };
					// 超过上限（大历史会话）就不物化，让手机端走重连。
					if (total > COLD_RANGE_LIMIT) {
						ctx.logger?.warn?.(
							`[dsh-remote] 会话 ${sessionId} 有 ${total} 条事件，超过补洞上限 ${COLD_RANGE_LIMIT}，放弃补发`,
						);
						return { events: [] };
					}
					const events = observation?.events;
					source = Array.isArray(events) ? events : null;
				} catch {
					source = null;
				} finally {
					try {
						observation?.[Symbol.dispose]?.();
					} catch {
						/* dispose 失败不影响返回 */
					}
				}
				if (source === null) return { events: [] };
			}
			// 按 seq 取闭区间。`source` 的 seq 是连续 0..N-1，所以直接用下标，
			// 但仍然逐条校验 seq —— 万一底层不是从 0 开始（继承的历史），
			// 下标偏移会悄悄错位，那比返回空更糟。
			const events = [];
			for (let seq = fromSeq; seq <= toSeq; seq += 1) {
				const event = source[seq];
				if (event === undefined || event === null) break;
				if (event.seq !== seq) break;
				events.push(event);
			}
			return { events };
		}

		/**
		 * 电脑上的模型目录（供手机把「模型选择」列表换成电脑的）。
		 *
		 * ⚠️ **必须走 `controller.modelCatalog()`，不能自己去读配置**：它是官方的
		 * 模型目录构建器（`buildModelCatalog`），已经把「哪些 provider 可用、
		 * 每个模型支持哪些 reasoning effort」都算好了，而且失败会按 provider
		 * 隔离成 `failures` 而不是整体抛错。自己读配置只会得到一份对不上官方
		 * 前端的结构。
		 *
		 * @returns `{ default, routableProviders, groups, failures }`。
		 */
		case 'modelCatalog': {
			if (typeof controller.modelCatalog !== 'function') throw new Error('modelCatalog 不可用');
			return await controller.modelCatalog();
		}

		/**
		 * 在电脑上选定模型（手机的模型选择器选中的是电脑的模型时转发过来）。
		 *
		 * ⚠️ 不转发的话，手机本地 `selectModel` 会拿**手机**的 llm 注册表去校验，
		 * 电脑上独有的模型一律报「模型不可用」—— 而用户明明在菜单里看到了它。
		 */
		case 'selectModel': {
			const sessionId = params.sessionId;
			if (typeof sessionId !== 'string' || sessionId === '') throw new Error('sessionId required');
			if (typeof controller.selectModel !== 'function') throw new Error('selectModel 不可用');
			const selected = await controller.selectModel({
				sessionId,
				provider: params.provider,
				model: params.model,
				...(typeof params.reasoningEffort === 'string' && params.reasoningEffort !== ''
					? { reasoningEffort: params.reasoningEffort }
					: {}),
			});
			return { sessionId, selected: selected?.selected ?? null };
		}

		/**
		 * 电脑上的预设列表（供手机把「预设」列表换成电脑的）。
		 *
		 * `agentPresets.remoteExportList()` 是官方给前端用的那份结构
		 * （`{presets: [{id, name, description, order, isDefault}]}`），
		 * 比 `list()` 多一个 `isDefault` —— 手机要照它显示「默认」标记。
		 */
		case 'presetList': {
			const presets = ctx.get?.('agentPresets');
			if (presets === undefined || typeof presets.remoteExportList !== 'function') {
				throw new Error('agentPresets 不可用');
			}
			return await presets.remoteExportList();
		}

		default:
			throw new Error(`unknown method "${method}"`);
	}
}

/**
 * 启动中继 HTTP 服务。
 *
 * @param ctx - Cordis 上下文。
 * @param token - 访问令牌。
 * @returns `{ close }`。
 */
function startRelay(ctx, token) {
	/** sessionId → Set<res>：正在跟随该会话的 SSE 连接。 */
	const followers = new Map();

	/**
	 * 当前有效的访问令牌。
	 *
	 * ⚠️ **必须是可变的**：配对是**运行中**发生的，配完要立刻生效。
	 * 若把 `token` 直接闭包进 `apply()` 的常量，客户填完激活码还得手动重启
	 * DSH —— 而那正是「面向大众」最不该有的一步。
	 */
	const auth = { token };

	/** 往某会话的所有跟随者推一条 SSE。 */
	const push = (sessionId, eventName, payload) => {
		const set = followers.get(sessionId);
		if (set === undefined || set.size === 0) return;
		const chunk = sse(eventName, payload);
		for (const res of set) {
			// 死连接先清掉：`writableEnded` / `destroyed` 说明这条 SSE 早就断了，
			// 只是 `req.on('close')` 没来得及跑（手机切后台、隧道被掐）。
			// 不清的话它会一直留在集合里，让 `askPhone` 误判「手机还在」。
			if (res === null || res === undefined || res.writableEnded === true || res.destroyed === true) {
				set.delete(res);
				continue;
			}
			try {
				res.write(chunk);
			} catch (error) {
				ctx.logger?.warn?.(`[dsh-remote] 推送失败: ${String(error?.message ?? error)}`);
				set.delete(res);
			}
		}
		if (set.size === 0) followers.delete(sessionId);
	};

	// ══════════════════════════════════════════════════════════════════════
	// 交互转发：把电脑端的 ask / 权限授权弹窗送到手机
	// ══════════════════════════════════════════════════════════════════════
	//
	// ## 症状（用户 2026-10-21 报）
	//
	// 「手机在远程访问电脑的时候，电脑这边工作发起了 ask 弹窗或者权限授权
	//   弹窗，手机那边都没有正常的弹出，导致用户无法选择任务卡住」
	//
	// ## 根因
	//
	// 这两类交互**根本不走会话事件流**，所以原来的 SSE 转发一条都碰不到它们：
	//
	//   · `ctx.userQuestions.ask()` → `ctx.waterfall(scopeTarget(agent), 'user-questions/request', …)`
	//     （dsh-user-questions/lib/index.js:681）
	//   · `ctx.approval.request()`  → `ctx.waterfall(scopeTarget(agent), 'approval/request', …)`
	//     （dsh-user-approval/lib/index.js:176）
	//
	// 两者都是 **cordis waterfall**：从最内层往外问，谁先给出答案谁作数。电脑端
	// 只有官方 GUI 注册了 answerer，**手机不在这个瀑布里**。所以手机连着的时候
	// 官方 GUI 会弹（用户不在电脑前看不到），而一旦 GUI 不在前台 / 没应答，
	// 瀑布走到底返回兜底值：
	//
	//   · 提问 → `NO_PROVIDER`（`no user-questions answerer accepted the request`）
	//   · 授权 → `unavailable`（fail closed，见 ApprovalService.decide 的兜底）
	//
	// 两者都让这一轮**直接失败或永久等待** —— 正是用户说的「任务卡住」。
	//
	// ## 为什么不能靠官方已有的 `dsh-api-remotes` 转发
	//
	// 官方确实把这两个事件列进了转发白名单
	// （`dsh-api-remotes/lib/index.js` 的 API_REMOTE_FORWARDED_EVENTS，
	//   mode: 'waterfall'），但它走的是 **`typertGateway` 的 Remote 通道**，
	// 而本插件的手机端连的是**自建的 `/api/dsh-remote/rpc` + `/api/dsh-remote/stream`**
	// （为了能在公网隧道上跑、且不需要官方那套 Peer 鉴权）。
	// 两条通道互不相通 ⇒ 官方转发了，手机也收不到。
	//
	// ## 做法
	//
	// 自己注册 answerer，只在「这个会话确实有手机在跟」时才接手 ——
	// 没人跟就 `next()` 放行，让官方 GUI 照常处理，绝不抢答。
	//
	// ⚠️ **注册时不能带 `{ global: true }`**：官方 `dsh-api-remotes` 注册
	//    同样这两个事件时也是裸 `ctx.on(event, fn)`、不带 options。waterfall
	//    的作用域链决定派发顺序，带上 global 会改变它 —— 而「官方 GUI 优先、
	//    手机兜底」这个顺序正是我们要的。
	//
	// ⚠️ **必须让 GUI 优先**：用户可能就坐在电脑前。官方 GUI 是在 Agent
	//    作用域内注册的，天然比本插件（插件根作用域）靠前，所以 GUI 在就会
	//    先弹、先答；GUI 不在（用户走开了）才轮到手机。不需要额外判断。

	/** 待手机回答的交互：`requestId → { resolve, reject, timer }`。 */
	const pendingAsks = new Map();

	/*
	 * **幽灵记录**：手机当时不在线、已经 fallback 给电脑 GUI 的那些 ask。
	 *
	 * 与 `pendingAsks` 的区别（这个区别是整个需求 6 的关键）：
	 *
	 *   · `pendingAsks` —— **手机在等**。答完之后结果会作为 waterfall 的
	 *     返回值继续往下走，即"手机的回答决定电脑这一轮怎么继续"。
	 *   · `ghostAsks`   —— **已经 fallback 给 GUI 了**。这一轮的走向已由
	 *     电脑本地 GUI 决定，手机现在插进来也改不了。
	 *
	 * 那留着它干什么？让**手机也能看到**这个待审批（粉丝反馈的核心诉求是
	 * "用户无法审批"，因为他压根不知道有这回事）。手机看到之后，
	 * 如果 GUI 那边还没人点，用户在手机上点一下就走正常的
	 * `answerAsk` 通道 —— 此时 `pendingAsks` 里已经没有这条了，
	 * 所以 `answerAsk` 会返回 `{ok:false, reason:'expired'}`，
	 * 手机端据此提示"该请求已由电脑端处理"。
	 *
	 * 换句话说：幽灵记录解决的是**可见性**，不是**决定权**。
	 */
	const ghostAsks = new Map();


	/**
	 * 把一个交互请求推给手机并等它回答。
	 *
	 * @param sessionId - 请求所属会话。
	 * @param kind - `'question'` 或 `'approval'`。
	 * @param payload - 给手机渲染用的数据（必须是可 JSON 化的纯数据）。
	 * @param signal - 请求自带的取消信号（用户中断这一轮时触发）。
	 * @param fallback - 手机不应答时返回给 waterfall 的值（fail closed）。
	 * @returns waterfall 的应答值。
	 */
	const askPhone = async (sessionId, kind, payload, signal, fallback) => {
		const set = followers.get(sessionId);
		// ⚠️ **不能只看 `set.size`**：手机切后台 / 断网 / 隧道被掐时，
		// `req.on('close')` 未必立刻触发，`followers` 里会留下**死连接**。
		//
		// 用户 2026-10-22 报障正是这个：他**没有**在手机上远程访问电脑，
		// 但 `desktop-debug.log` 里是 `followers=1` —— 残留订阅者让这里
		// 误判「手机还在」，于是把请求推给一个根本不存在的手机。
		//
		// 现在逐个检查 socket 是否还活着：`writableEnded` / `destroyed`
		// 任一为真就说明这条 SSE 已经死了，顺手清出集合。
		const live = [];
		if (set !== undefined) {
			for (const res of set) {
				const dead = res === null || res === undefined || res.writableEnded === true || res.destroyed === true;
				if (dead) {
					set.delete(res);
					continue;
				}
				live.push(res);
			}
			if (set.size === 0) followers.delete(sessionId);
		}
		// 诊断：把「有没有手机在跟」记下来，真机排查时一眼能看出是没进这里、
		// 还是进来了但没有**活着的**订阅者。
		void debugLog(
			`askPhone kind=${kind} session=${sessionId} followers=${set === undefined ? 'none' : `${live.length}/${set.size}`} all=[${[...followers.keys()].join(',')}]`,
		);
		const requestId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
		const settled = Promise.withResolvers();

		/*
		 * ⚠️⚠️ **手机没连着时，也要把请求登记进 `pendingAsks` 等它回来。**
		 *
		 * ## 这里原来直接 `return fallback`，正是粉丝反馈的那个 bug
		 *
		 * 用户原话（2026-10-28 转述粉丝反馈）：
		 *
		 * 「他电脑上面已经有一个会话了，但此时他是没有在手机 APP 内的，
		 *   它已经发起了一个 ask 弹窗，然后此时用户去打开我们的 APP，
		 *   它虽然消息同步过来了，但是弹窗没有同步过来，也就是说它如果是
		 *   后打开的话，它的弹窗是无法显示的，用户就无法审批。」
		 *
		 * 根因就在这一行：手机没连着 ⇒ `live.length === 0` ⇒ 直接 fallback
		 * ⇒ **这个 ask 在电脑侧压根没有被登记**，只是一个瞬时的函数调用。
		 * 用户之后再打开手机，电脑上没有任何东西可供"补拉" —— 请求已经不存在了。
		 *
		 * ## 现在的做法
		 *
		 * 无论有没有手机在线，都先把请求登记进 `pendingAsks`，然后：
		 *   · 有活着的手机 ⇒ 照旧推过去（行为完全不变）；
		 *   · 没有手机 ⇒ **不推**，但仍然登记，并等手机重连后来取
		 *     （见 `pendingAsks` 的 `case 'pendingAsks'`）。
		 *
		 * ## 为什么这不会让电脑干等
		 *
		 * ① 有 `ASK_TIMEOUT_MS`（5 分钟）兜底，到点 resolve fallback；
		 * ② 手机重连后如果用户没在 5 分钟内回来，请求自己过期，行为与改造前一致；
		 * ③ 官方 GUI 那边**不受影响**：fallback 仍会在这个 waterfall 的后继里
		 *    正常放行（`askPhone` 只是多了等待，不改变最终返回的值）。
		 *
		 * 换句话说：改造前是"手机不在就直接放弃"，现在是"等一等，超时再放弃"。
		 * 对已经在电脑前操作的用户**没有任何行为变化**（GUI 卡片照常弹，
		 * 因为 fallback 仍然会到达），只是额外给了手机一个补救窗口。
		 */
		void debugLog(`askPhone pending requestId=${requestId} live=${live.length}`);
		const cleanup = () => {
			const entry = pendingAsks.get(requestId);
			if (entry !== undefined) {
				clearTimeout(entry.timer);
				pendingAsks.delete(requestId);
			}
		};

		// 超时兜底：手机可能在锁屏 / 隧道断了 / 用户压根没看。
		// 不设超时会让这一轮**永久挂住**（用户原话「任务卡住」），比失败更糟。
		const timer = setTimeout(() => {
			cleanup();
			settled.resolve(fallback);
		}, ASK_TIMEOUT_MS);

		/*
		 * ⚠️ **payload 必须一起存**：手机后连上来时，要靠它把弹窗原样重建成
		 * 与实时推送完全一致的样子（见 `case 'pendingAsks'`）。
		 *
		 * `at` 用于手机端按时间排序 + 显示"这个请求等了多久"。
		 */
		pendingAsks.set(requestId, {
			resolve: settled.resolve,
			reject: settled.reject,
			timer,
			kind,
			sessionId,
			payload,
			at: Date.now(),
		});

		// 用户中断这一轮（点停止生成 / 会话被删）时立刻收尾，别等超时。
		const onAbort = () => {
			cleanup();
			settled.resolve(fallback);
		};
		if (signal !== undefined) {
			if (signal.aborted) {
				onAbort();
				return settled.promise;
			}
			signal.addEventListener('abort', onAbort, { once: true });
			void settled.promise.finally(() => signal.removeEventListener('abort', onAbort));
		}

		/*
		 * ## 有手机在线 ⇒ 正常推送并等它回答（行为与改造前完全一致）
		 */
		if (live.length > 0) {
			push(sessionId, 'ask', { requestId, kind, ...payload });
		} else {
			/*
			 * ## 没有手机在线 ⇒ **立刻把 fallback 交给官方 GUI**，同时留补拉记录
			 *
			 * ### 为什么不能在这里 await
			 *
			 * `askPhone` 是 cordis waterfall 的一环。在这里 `await` 会让
			 * **电脑本地 GUI 的弹窗也要等满 5 分钟**（`ASK_TIMEOUT_MS`）
			 * 才拿到 fallback —— 用户就坐在电脑前，点个"允许"却要等 5 分钟，
			 * 比原来的 bug 更糟。
			 *
			 * ### 现在这样做
			 *
			 * · `fallback` 立刻返回 ⇒ 电脑 GUI 照常**马上**弹窗、当场能批；
			 * · 同时把请求放进 `ghostAsks`（**幽灵记录**），只用于"手机后来
			 *   连上来时补弹一次"，**不参与** waterfall 的返回值。
			 *
			 * 这正是粉丝反馈要的效果：他在电脑前时一切照旧（快），
			 * 他走开后再打开手机，仍能看到那个还在等着的审批。
			 */
			/*
			 * ⚠️ 收尾必须**先把 signal 监听摘掉**再返回。
			 *
			 * 这一分支直接 `return fallback`，不会走到下面的
			 * `finally { cleanup() }`。而上面已经给 `signal` 挂了 `onAbort`
			 *（通过 `finally(() => removeEventListener)` 兜底，但那是绑在
			 * `settled.promise` 上的 —— 这里 promise 永远不会 resolve，
			 * 所以那个 remove 也不会跑）。不显式摘掉就是一次监听泄漏，
			 * 长会话里会越积越多。
			 */
			if (signal !== undefined && typeof signal.removeEventListener === 'function') {
				signal.removeEventListener('abort', onAbort);
			}
			cleanup();
			const ghostTimer = setTimeout(() => {
				ghostAsks.delete(requestId);
			}, ASK_TIMEOUT_MS);
			// `unref` 让这个定时器不阻塞 Node 进程退出（桌面端会被反复启停）。
			ghostTimer.unref?.();
			ghostAsks.set(requestId, {
				kind,
				sessionId,
				payload,
				at: Date.now(),
				timer: ghostTimer,
			});

			void debugLog(`askPhone 无手机在线 ⇒ 立刻 fallback，留幽灵记录 ${requestId}`);
			return fallback;
		}

		try {
			return await settled.promise;
		} finally {
			cleanup();
		}
	};

	/**
	 * 手机端回答一条待决交互（经 RPC 调进来）。
	 *
	 * @param requestId - 推送时给的那个 id。
	 * @param answer - 手机的选择（形状由 `kind` 决定）。
	 * @returns `{ ok: true }`；找不到或已超时返回 `{ ok: false, reason }`。
	 */
	const answerAsk = (requestId, answer) => {
		const entry = pendingAsks.get(requestId);
		void debugLog(
			`answerAsk requestId=${requestId} 找到挂起=${entry !== undefined} 答案=${JSON.stringify(answer)?.slice(0, 150)}`,
		);
		if (entry === undefined) return { ok: false, reason: 'expired' };
		pendingAsks.delete(requestId);
		clearTimeout(entry.timer);
		entry.resolve(answer);
		void debugLog(`answerAsk 已 resolve，等待 waterfall 继续`);
		return { ok: true };
	};

	/**
	 * `carrierKeyOf` 的运行时副本（动态加载）。
	 *
	 * ⚠️ 不用静态 `import`：`@deepseek-ai/dsh-scope` 是引擎内部包，
	 * 万一某个版本没装/改名，**静态 import 会让整个插件加载失败**
	 *（连远程功能一起没）。动态加载失败只是让 `sessionIdOf()` 退回
	 * 第二条后备，最坏情况 `next()` 放行给官方 GUI。
	 */
	let carrierKeyOfImpl = null;
	const carrierKeyReady = import('@deepseek-ai/dsh-scope')
		.then((mod) => {
			if (typeof mod.carrierKeyOf === 'function') carrierKeyOfImpl = mod.carrierKeyOf;
			void debugLog(`carrierKeyOf 加载${carrierKeyOfImpl === null ? '失败' : '成功'}`);
		})
		.catch((error) => {
			void debugLog(`carrierKeyOf 加载失败（不影响主流程）: ${String(error?.message ?? error)}`);
		});

	/**
	 * 从一次 waterfall 派发里取出「这个请求属于哪个会话」。
	 *
	 * 三条路依次尝试，任一命中即返回：
	 *
	 * 1. `carrierKeyOf(this)` —— `this` 是 cordis 派发时的 carrier（`scopeTarget`
	 *    造的那个），它的路由键就是会话 id。这是**最可靠**的一条：官方
	 *    `dsh-api-remotes` 判断「这个请求该发给谁」用的就是它。
	 * 2. `request.agent.session.id` —— Agent 上挂的会话。官方在别处确实这么取，
	 *    但**不是每个 Agent 实例都有 `session`**，所以只能当后备。
	 * 3. 都拿不到 ⇒ 返回 `undefined`，调用方 `next()` 放行，交给官方 GUI。
	 *
	 * ⚠️ 为什么不用 `request.sessionId`：这两个 waterfall 的 request 形状里
	 *    没有它（见 `dsh-user-questions` 的 `ask()`，只带 `agent` / `questions`
	 *    / `signal` / `wait`）。
	 *
	 * @param carrier - 派发时的 `this`（scope carrier）。
	 * @param request - waterfall 的 request 参数。
	 * @returns 会话 id，或 `undefined`。
	 */
	function sessionIdOf(carrier, request) {
		// ① carrier 的路由键
		if (carrierKeyOfImpl !== null) {
			try {
				const key = carrierKeyOfImpl(carrier);
				if (typeof key === 'string' && key !== '') return key;
			} catch {
				// 拿不到就往下走
			}
		}
		// ② Agent 上的会话
		const fromAgent = request?.agent?.session?.id;
		if (typeof fromAgent === 'string' && fromAgent !== '') return fromAgent;
		return undefined;
	}

	// ---- approval/request：权限授权 ----
	//
	// 返回词表是固定的四个（见 dsh-user-approval 的 OUTCOMES）：
	// `allowed-once` / `rejected` / `cancelled` / `unavailable`。
	// 只有 `allowed-once` 是放行，其余一律拒绝 —— 手机端传回任何别的值
	// 都归一成 `unavailable`，避免一个手滑的字符串变成授权。
	//
	// ## ⚠️⚠️ 这里是「旁路」，不是「拦截」（用户 2026-10-22 报障）
	//
	// 用户原话：「我即使没有在手机上面远程访问电脑，电脑这边发起提问，
	// 电脑还是不会显示 —— 本来电脑应该也有弹窗的」。
	//
	// ## 之前的写法为什么错
	//
	// 原来的实现是 `await askPhone(...)`：**先问手机，手机不答就等 5 分钟**
	// （`ASK_TIMEOUT_MS`），这期间**根本不调 `next()`**。而 waterfall 的语义是
	// 「不调 next 就吞掉后面所有环节」，所以：
	//
	//   · `dsh-api-remotes` 排在本插件后面 ⇒ 拿不到请求 ⇒ 转发不到前端 ⇒
	//     **电脑上不弹窗**；
	//   · 只要 `followers` 里还有一条**残留的 SSE 订阅**（手机切后台/断网时
	//     `req.on('close')` 未必立刻触发），`askPhone` 就不走「没手机」的
	//     放行分支，而是一路等到超时 —— 症状就是「电脑端卡死不弹」。
	//
	// 真机日志证据（`desktop-debug.log`）：
	//
	//     user-questions/request 进入本插件 session=session-5d4da935-…
	//     askPhone kind=question session=session-5d4da935-… followers=1
	//
	// 用户当时**并没有**在手机上远程访问，但 `followers=1` ⇒ 走了等待分支。
	//
	// ## 现在的写法
	//
	// 「旁路」：**立刻** `next()` 让官方链路照常走（电脑该弹就弹），
	// 同时把请求推给手机。两条路谁先给出答案就用谁的：
	//
	//   · 手机先答 ⇒ 返回手机的答案（它已经 resolve，官方那条 promise 被丢弃）；
	//   · 电脑先答 ⇒ `next()` 的 promise 先 settle，用电脑的答案；
	//   · 手机压根没连 ⇒ `askPhone` 立刻返回 `NO_TAKEOVER`，完全不影响电脑。
	//
	// 代价：手机和电脑会**同时**看到同一个提问。这正是用户要的
	// ——「电脑那边就正常弹窗，手机响应的电脑，电脑再正常响应就行了」。
	ctx.on(
		'approval/request',
		async function (request, next) {
			// 等 `carrierKeyOf` 加载完（通常早已就绪，这里只是消除竞态）。
			await carrierKeyReady;
			const sessionId = sessionIdOf(this, request);
			void debugLog(`approval/request 进入本插件 session=${sessionId ?? 'none'}`);
			// 拿不到会话就放行，别挡着官方 GUI 那条路。
			if (typeof sessionId !== 'string' || sessionId === '') return next();

			// ⚠️ **先启动官方那条**（同步调用，不等它）：`next()` 会一路把请求
			// 交给 `dsh-api-remotes` → 前端 GUI → 电脑弹窗。必须在本函数返回前
			// 被调用过，否则等于没放行。
			//
			// ⚠️ 末尾的 `.catch(() => undefined)` 是**防 unhandled rejection**：
			// 手机先答时我们会把这条丢掉，但它稍后可能 reject（前端取消、
			// 用户关页面）。没有这个 catch，Node 会打一行 unhandledRejection，
			// 严重时（`--unhandled-rejections=strict`）直接把进程带走。
			const desktop = Promise.resolve()
				.then(next)
				.catch(() => undefined);

			const phone = askPhone(
				sessionId,
				'approval',
				{
					toolName: request.toolName ?? '',
					...(request.callId !== undefined ? { callId: request.callId } : {}),
					...(request.reason !== undefined ? { reason: request.reason } : {}),
				},
				request.signal,
				// 手机不在场时 = 没接手，此时只听电脑的。
				NO_TAKEOVER,
			);

			// 谁先给出有效答案用谁的。
			const first = await Promise.race([
				phone.then((v) => ({ from: 'phone', value: v })),
				desktop.then((v) => ({ from: 'desktop', value: v })),
			]).catch(() => undefined);

			if (first === undefined) {
				// 两条都炸了 —— 交给官方兜底（它会抛自己的错，语义更准）。
				return desktop;
			}
			if (first.from === 'phone') {
				// 手机没接手（没连/超时/中断）⇒ 完全听电脑的。
				if (first.value === NO_TAKEOVER) return desktop;
				void debugLog(`approval/request 由手机作答，丢弃电脑那条`);
				return APPROVAL_OUTCOMES.includes(first.value) ? first.value : 'unavailable';
			}
			void debugLog(`approval/request 由电脑作答，手机那条作废`);
			return first.value;
		},
		// ⚠️⚠️ **必须 `{ global: true, prepend: true }`，两个都不能少。**
		//
		// ## global：让监听器绕过 scope 过滤
		//
		// cordis 的 `EventService.dispatch()` 有一行：
		//
		//     .filter((hook) => hook.global || !filter || filter.call(thisArg, hook.ctx))
		//
		// `dsh-user-approval` 派发时 `thisArg` 是 `scopeTarget(agent, agent)`，
		// 带 `Context.filter`。插件根 ctx 的 `scopeOf` 是 undefined，理论上
		// 会被放行（`tag === void 0 → return true`），但显式写 global 更稳 ——
		// 它明确表达「这个监听器对所有作用域生效」，不依赖 scope 标签的实现细节。
		//
		// ## prepend：仍然要抢在 `dsh-api-remotes` 前面
		//
		// `dsh-api-remotes` 也监听 `approval/request`（`API_REMOTE_FORWARDED_EVENTS`，
		// mode: 'waterfall'），拿到请求就 `forwardWaterfall()` 转发给**前端 GUI**。
		//
		// 注意它**并不是**「永不调 next」——`forwardWaterfall`（`dsh-api-remotes/
		// lib/index.js:187`）在前端回 `{kind:'result'}` 时直接 resolve，只有前端
		// 明确拒绝时才走 `next()`。也就是说：**只要本插件排在它后面，本插件就
		// 永远轮不到**（前端正常答题时它已经 settle 了）。
		//
		// 所以 `prepend` 依然必要 —— 但语义已经从「拦截」改成「旁路」：
		// 本插件**立刻** `next()` 让 api-remotes 照常转发给电脑，
		// 同时把同一个请求推给手机，谁先答用谁的（见上面函数体的注释）。
		{ global: true, prepend: true },
	);

	// ---- user-questions/request：ask_user_question ----
	//
	// 返回值形状是 `{ answers: [{ questionId, selected: [label…], custom?: string }] }`
	// （见 dsh-client-ui-user-questions 的 QuestionAnswer 组装）。手机端原样回传，
	// 这里只做形状校验 —— 形状不对就当没答，放行给下一环。
	ctx.on(
		'user-questions/request',
		async function (request, next) {
			// 等 `carrierKeyOf` 加载完（通常早已就绪，这里只是消除竞态）。
			await carrierKeyReady;
			const sessionId = sessionIdOf(this, request);
			void debugLog(`user-questions/request 进入本插件 session=${sessionId ?? 'none'}`);
			if (typeof sessionId !== 'string' || sessionId === '') return next();

			const questions = Array.isArray(request?.questions) ? request.questions : [];
			if (questions.length === 0) return next();

			// 只把手机渲染需要的字段挑出来 —— 整个 request 里有 Agent 实例、
			// 函数、Symbol，直接 JSON 化会抛「not lossless JSON」。
			const wire = questions.map((q) => ({
				id: q.id,
				question: q.question ?? '',
				...(q.header !== undefined ? { header: q.header } : {}),
				...(q.multiSelect === true ? { multiSelect: true } : {}),
				options: (Array.isArray(q.options) ? q.options : []).map((o) => ({
					label: o.label ?? '',
					...(o.description !== undefined ? { description: o.description } : {}),
				})),
			}));

			const answer = await (async () => {
				// ⚠️ **先启动官方那条**（理由同上面 `approval/request` 的注释：
				// 不先调 `next()`，`dsh-api-remotes` 就转发不到前端 ⇒ 电脑不弹窗）。
				// 末尾 `.catch` 同样是为了防 unhandled rejection。
				const desktop = Promise.resolve()
					.then(next)
					.catch(() => undefined);

				const phone = askPhone(
					sessionId,
					'question',
					{
						questions: wire,
						...(request.wait?.callId !== undefined ? { callId: request.wait.callId } : {}),
					},
					request.signal,
					NO_TAKEOVER,
				);

				// 谁先答用谁的 —— 手机在旁边就手机上点，在电脑前就在电脑上点。
				const first = await Promise.race([
					phone.then((v) => ({ from: 'phone', value: v })),
					desktop.then((v) => ({ from: 'desktop', value: v })),
				]).catch(() => undefined);

				if (first === undefined) return desktop;
				if (first.from === 'phone') {
					// 手机没接手 ⇒ 完全听电脑的。
					if (first.value === NO_TAKEOVER) return desktop;
					// ⚠️ 手机回的是 `{ answers: [...] }`；形状不对就当没答，
					// **退回电脑那条**（不能 `next()` —— 它已经被调用过一次了，
					// 再调一次等于把请求派发两遍）。
					if (
						first.value === null ||
						typeof first.value !== 'object' ||
						!Array.isArray(first.value.answers)
					) {
						void debugLog(`user-questions/request 手机答案形状不对，退回电脑那条`);
						return desktop;
					}
					void debugLog(`user-questions/request 由手机作答，丢弃电脑那条`);
					return first.value;
				}
				void debugLog(`user-questions/request 由电脑作答，手机那条作废`);
				return first.value;
			})();
			return answer;
		},
		// 理由同上面 `approval/request`：**`prepend` 才是关键** ——
		// `dsh-api-remotes` 也监听这个事件且拿到就转发给前端、不调 `next()`，
		// 不插到它前面就永远轮不到本插件。
		{ global: true, prepend: true },
	);

	// ---- durable 通道：落盘的事件 ----
	ctx.on(
		'session/event',
		(session, event) => {
			// 诊断：`session.id` 和实际订阅键不一致时，`push` 会静默不推 ——
			// 症状正是「手机只收到逐字动画(frame)，永远看不到最终消息(event)」。
			// 只记关键类型，避免日志被刷爆。
			if (event?.type === 'assistant/message' || event?.type === 'turn/end') {
				const set = followers.get(session.id);
				void debugLog(
					`session/event ${event.type} session.id=${session.id} 订阅者=${set === undefined ? 0 : set.size}`,
				);
			}
			push(session.id, 'event', event);
		},
		{ global: true },
	);

	// 启动确认：把「answerer 是否真的注册上了」写进诊断日志。
	//
	// ⚠️ 这一行必须留：前面几次真机排查全靠它区分「没注册上」和「注册了但没被
	// 派发到」—— 两者症状完全一样（手机没卡片），但修法截然不同。
	void debugLog('startRelay 就绪：已注册 approval/request + user-questions/request answerer');

	// 诊断：把本插件 ctx 的 scope 标签打出来。
	//
	// `scopeTarget()` 的过滤器是这么判的：
	//     const tag = scopeOf(ctx);
	//     if (tag === void 0) return true;          // 无标签 ⇒ 全局放行
	//     …否则要求 tag 在 dispatch key 的祖先链上
	//
	// 所以「插件 ctx 有没有被打了某个 scope 标签」直接决定 answerer 收不收得到
	// 事件。打出来才能确定是被 filter 拒了，还是压根没派发。
	void import('@deepseek-ai/dsh-scope')
		.then((mod) => {
			void debugLog(`本插件 ctx 的 scope 标签 = ${String(mod.scopeOf(ctx))}`);
		})
		.catch((error) => {
			void debugLog(`读取 scopeOf 失败（不影响功能）: ${String(error?.message ?? error)}`);
		});

	// ---- 流式通道：进程内事件，不落盘，只给逐字动画 ----
	ctx.on(
		'agent/assistant-stream',
		({ agent, frame }) => {
			push(agent.session.id, 'frame', frame);
		},
		{ global: true },
	);

	/**
	 * 给 `handleRpc` 用的通道对象。
	 *
	 * `handleRpc` 是**模块级**函数，看不见 `startRelay` 的闭包。交互应答
	 * （`answerAsk`）必须落回这个闭包里的 `pendingAsks`，所以显式传进去 ——
	 * 与手机端插件里 `pendingPlans` / `workspaceMap` 的处理方式一致。
	 */
	/*
	 * ⚠️ `pendingAsks` 方法名和闭包里的 `pendingAsks` Map **同名**。
	 *
	 * 这是刻意保留的语义对应（一个列出、一个存储），但正因为同名，
	 * 这里必须写成方法简写 `pendingAsks() {...}` —— 写成
	 * `pendingAsks: pendingAsks` 会直接引用到 Map 本身（不是函数），
	 * `handleRpc` 调它就会 `io.pendingAsks is not a function`。
	 *
	 * 返回值形状与 `case 'pendingAsks'` 的注释一致；这里再按 `at` + 超时
	 * 兜一层过期判断，防止极端情况下定时器未触发而残留。
	 */
	const io = {
		answerAsk,
		pendingAsks() {
			const now = Date.now();
			const out = [];
			const collect = (map, isGhost) => {
				for (const [requestId, entry] of map) {
					if (entry === null || entry === undefined) continue;
					const age = now - (typeof entry.at === 'number' ? entry.at : now);
					// 超过超时窗口的视为已过期（正常应已被 timer 清掉，这里是兜底）。
					if (age > ASK_TIMEOUT_MS) continue;
					out.push({
						requestId,
						kind: entry.kind,
						sessionId: entry.sessionId,
						payload: entry.payload ?? {},
						at: entry.at ?? now,
						ageMs: age,
						/*
						 * `ghost: true` = 这条已经 fallback 给电脑 GUI 了，
						 * 手机现在答也改不了那一轮的结果。
						 *
						 * 手机端据此可以：
						 *   · 仍然弹出来让用户知道"电脑上有个审批在等"；
						 *   · 但按钮文案/提示写明"电脑端可能已处理"，
						 *     避免用户以为自己没点成功而反复重试。
						 */
						ghost: isGhost,
					});
				}
			};
			collect(pendingAsks, false);
			collect(ghostAsks, true);
			// 先来的先显示（用户最该先处理等得最久的那个）。
			out.sort((a, b) => a.at - b.at);
			return out;
		},
	};

	const server = createServer((req, res) => {
		const url = new URL(req.url ?? '/', `http://127.0.0.1:${RELAY_PORT}`);

		// 鉴权：令牌走 header 或 query（EventSource 不能设 header，所以 query 也要收）。
		const provided = req.headers['x-dsh-remote-token'] ?? url.searchParams.get('token') ?? '';
		if (!tokenMatches(provided, auth.token)) {
			res.writeHead(401, { 'content-type': 'application/json; charset=utf-8' });
			res.end(JSON.stringify({ error: 'unauthorized' }));
			return;
		}

		// ---- SSE：跟随一个会话的事件流 ----
		if (url.pathname === '/api/dsh-remote/stream' && req.method === 'GET') {
			const sessionId = url.searchParams.get('session');
			if (sessionId === null) {
				res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
				res.end(JSON.stringify({ error: 'session query required' }));
				return;
			}
			res.writeHead(200, {
				'content-type': 'text/event-stream; charset=utf-8',
				'cache-control': 'no-store, no-transform',
				connection: 'keep-alive',
				// 关掉代理缓冲，否则事件会攒着一起发，逐字动画变成一次性出现。
				'x-accel-buffering': 'no',
			});
			// 注释行当心跳：既让浏览器知道连接活着，也让中间代理不掐空闲连接。
			res.write(': connected\n\n');

			let set = followers.get(sessionId);
			if (set === undefined) {
				set = new Set();
				followers.set(sessionId, set);
			}
			// ⚠️ 这里**不能**引用 `fromSeq`：它在下面几十行才用 `const` 声明，
			// 提前引用会踩 TDZ（`ReferenceError: Cannot access 'fromSeq' before
			// initialization`），整个插件 apply 抛错 ⇒ DSH 起不来。
			// 这条诊断日志放在 `fromSeq` 解析之后（见下面的订阅处）。

			// 开场把已有事件补齐（断线重连后能接上）。
			//
			// ⚠️⚠️ **必须支持 `fromSeq`，否则「聊得越大越卡」**（用户报障根因）。
			//
			// 原实现无条件把**整份日志**重推一遍：
			//
			//     for (const event of session.snapshotEvents()) res.write(sse('event', event));
			//
			// 而隧道掐空闲连接、手机休眠断网都是**常态** —— 每次重连都重放全量。
			// 实测本机 311 个会话共 639 MB（磁盘 zstd 压缩后），明文还要再膨胀
			// 3.5～4 倍：最大的一个会话 41 MB → 明文 155 MB / 29638 个事件。
			// 也就是说一次重连就要推 155 MB，手机端当然卡死。
			//
			// 现在手机端在重连时把自己**已有的最大 seq** 用 `?fromSeq=` 带上来，
			// 这里只补 `seq > fromSeq` 的增量。正常重连（手机没丢事件）时
			// 增量就是 0 条，只剩心跳。
			//
			// ⚠️ `fromSeq` **缺省时仍推全量** —— 这是刻意的向后兼容：
			// 老版本手机端不会带这个参数，行为与改动前完全一致。
			const fromSeqRaw = url.searchParams.get('fromSeq');
			const fromSeq = fromSeqRaw === null ? null : Number(fromSeqRaw);
			// 诊断：手机连上来时记一行。放在 `fromSeq` 声明**之后**才不会踩 TDZ。
			void debugLog(`SSE 订阅 session=${sessionId} fromSeq=${fromSeq ?? 'null'} 该会话订阅者=${set.size}`);
			// ⚠️ **补齐必须排在 `set.add(res)` 之前，所以整块包进 async IIFE。**
			//
			// 顺序是硬要求：如果先把 `res` 挂进 `followers` 再慢慢补齐，补齐期间
			// 电脑上新产生的事件会经 `push()` 先写进这个 `res`，然后补齐的旧事件
			// 才到 —— 手机端收到 seq 45 又收到 seq 21，`acceptEntry` 直接判定
			// 不连续（或重复），照样炸。先补完、再订阅，才是单调递增的。
			void (async () => {
				// 已经写给这个 `res` 的**最大 seq**。
				//
				// ⚠️ 它必须被跟踪，因为补齐与订阅之间有**竞态窗口**：
				// `set.add(res)` 之前的任何新事件都不会经 `push()` 到达这里，
				// 补完之后若不再追一次，那些事件就永久丢了 ⇒ 手机端看到跳号 ⇒
				// 又是「历史加载失败」。所以 `set.add(res)` 之后立刻再补一次
				// `seq > sent` 的增量（此时已订阅，没补到的部分 `push()` 会负责）。
				let sent = fromSeq !== null && Number.isFinite(fromSeq) && fromSeq >= 0 ? fromSeq : -1;

				/**
				 * 把 `seq > sent` 的历史事件写给 `res`，并推进 `sent`。
				 *
				 * @param allowCold - 是否允许为**冷会话**物化整份日志。
				 *   首次补齐时允许（那是主修复点）；追竞态窗口那次**不允许** ——
				 *   那时会话已经被唤醒成活会话，走不到冷分支；万一走到，
				 *   说明期间又被卸载，再物化一次 155 MB 不值得，宁可让手机端重连。
				 */
				const pushBacklog = async (allowCold) => {
					const session = ctx.sessions?.get?.(sessionId);
					if (session !== undefined) {
						const all = session.snapshotEvents();
						for (const event of all) {
							if (event.seq <= sent) continue;
							sent = event.seq;
							res.write(sse('event', event));
						}
						return;
					}
					if (!allowCold) return;
					// ⚠️⚠️ **冷会话必须也补齐，否则手机端必然报「历史加载失败」。**
					//
					// 这是真机报错的**真根因**（比 `throughSeq` 那条更根本）：
					//
					// `ctx.sessions.get()` 只返回**内存里活着的**会话。同步来的对话
					// 在电脑上可能很久没碰、已被卸载，此时这里是 `undefined` ——
					// 原实现直接跳过补齐，**一条事件都不发**，但 `res` 已经进了
					// `followers`。等用户发消息把会话唤醒、agent 开始跑，新事件经
					// `push()` 广播过来时，**第一个事件的 seq 就比手机端本地
					// `cursor` 大了一大截**（中间那些历史全丢了）。
					//
					// 前端 `RemoteJournalStream.acceptEntry`
					//（`dsh-api-gateway/lib/client.js:1127`）一看
					// `!follows(lastCursor, firstSeq)`（要求 `first === last + 1`）
					// 就判定不连续，转去 `replaceThrough()` →
					// `assertPageThrough(page, requiredCursor)`，而补页请求给不出
					// 那个精确 cursor ⇒ 抛
					// `session event stream page did not end at its requested cursor`
					// ⇒ 界面「历史加载失败」。
					//
					// 所以冷会话也要补齐：走 `sessionQuery.observeSession()` 拿
					// 事件（**它给的 events 是惰性 getter，这里确实要用，必须物化**）。
					// ⚠️ 只用它补 `seq > sent` 的增量，绝不整份回放 ——
					// 大历史会话整份是 155 MB / 29638 条，那样重连一次就卡死。
					const query = ctx.get?.('sessionQuery');
					if (query === undefined || query === null || typeof query.observeSession !== 'function') {
						return;
					}
					let observation;
					try {
						observation = await query.observeSession(sessionId, { projectionMode: 'none' });
						const events = observation?.events;
						if (Array.isArray(events)) {
							for (const event of events) {
								if (event.seq <= sent) continue;
								sent = event.seq;
								res.write(sse('event', event));
							}
						}
					} finally {
						try {
							observation?.[Symbol.dispose]?.();
						} catch {
							/* dispose 失败不影响补发 */
						}
					}
				};

				try {
					await pushBacklog(true);
				} catch (error) {
					ctx.logger?.warn?.(`[dsh-remote] 补发历史失败: ${String(error?.message ?? error)}`);
				}

				// 补齐完成后才订阅实时推送（顺序见上面的 ⚠️）。
				set.add(res);

				// ⚠️ **订阅后立刻追一次增量**：`pushBacklog(true)` 运行期间产生的事件
				// 既没进补发（那时 `sent` 还是旧的）、也没进 `push()`（那时 `res`
				// 还不在 `followers` 里）。这一趟把那个窗口缝上。
				try {
					await pushBacklog(false);
				} catch (error) {
					ctx.logger?.warn?.(`[dsh-remote] 追补增量失败: ${String(error?.message ?? error)}`);
				}

				const heartbeat = setInterval(() => {
					try {
						res.write(': ping\n\n');
					} catch {
						clearInterval(heartbeat);
					}
				}, 15000);

				const cleanup = () => {
					clearInterval(heartbeat);
					set.delete(res);
					if (set.size === 0) followers.delete(sessionId);
				};
				req.on('close', cleanup);
				req.on('error', cleanup);
			})();
			return;
		}

		// ---- RPC：手机发起的动作 ----
		if (url.pathname === '/api/dsh-remote/rpc' && req.method === 'POST') {
			const chunks = [];
			req.on('data', (c) => chunks.push(c));
			req.on('end', async () => {
				const reply = (payload) => {
					res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
					res.end(JSON.stringify(payload));
				};
				let body;
				try {
					body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
				} catch (error) {
					res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
					res.end(JSON.stringify({ error: `bad json: ${String(error?.message ?? error)}` }));
					return;
				}
				try {
					reply({ id: body.id, ok: true, result: await handleRpc(ctx, body.method, body.params ?? {}, io) });
				} catch (error) {
					reply({ id: body.id, ok: false, error: String(error?.message ?? error) });
				}
			});
			return;
		}

		res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
		res.end(JSON.stringify({ error: 'not found' }));
	});

	/**
	 * ⚠️ `error` 处理器**不能省**，否则端口被占用时整个引擎进程会崩。
	 *
	 * `listen()` 的失败是**异步**通过 `'error'` 事件抛的，`apply()` 里那个
	 * try/catch 完全兜不住 —— 实测（本机已有一个 DSH 在跑时再起一个）：
	 *
	 *   Error: listen EADDRINUSE: address already in use 127.0.0.1:17890
	 *   Emitted 'error' event on Server instance at: ...
	 *   Node.js v24.16.0        ← 进程直接退出
	 *
	 * 后果远不止「远程功能不可用」：客户机上有残留的引擎进程是**常见现象**
	 *（本项目 AGENTS.md 就记着「3080 已被占用（多半是上一次残留的引擎进程）」），
	 * 而崩掉的是**整个 DSH**，客户完全看不出这是本插件造成的。
	 *
	 * 所以这里降级：日志说清原因，其余功能照常。
	 */
	server.on('error', (error) => {
		ctx.logger?.warn?.(
			`[dsh-remote] 中继无法监听 127.0.0.1:${RELAY_PORT}（${String(error?.message ?? error)}）` +
				'；多半是另一个 DSH 实例已在运行。远程功能不可用，但本进程其余功能不受影响。',
		);
	});

	server.listen(RELAY_PORT, '127.0.0.1');
	return {
		/**
		 * 换令牌（配对后热更新，无需重启 DSH）。
		 *
		 * @param next - 新令牌。
		 */
		setToken: (next) => {
			auth.token = next;
		},
		close: () => {
			for (const set of followers.values()) {
				for (const res of set) {
					try {
						res.end();
					} catch {
						/* 连接已断 */
					}
				}
			}
			followers.clear();
			server.close();
		},
	};
}

/**
 * 启动 cloudflared 隧道（可选）。
 *
 * 没配置 token 就只跑中继 —— 用户仍可用局域网或自己的反代，本插件不强制隧道。
 *
 * @param ctx - Cordis 上下文。
 * @param token - 隧道 token。
 * @returns 子进程，或 `undefined`。
 */
function startTunnel(ctx, token) {
	const bin = findCloudflared();
	if (bin === undefined) {
		ctx.logger?.warn?.('[dsh-remote] 未找到 cloudflared，跳过隧道（中继仍在本地监听）');
		return undefined;
	}
	// ⚠️⚠️ **隧道退出必须自动重连**（用户 2026-10-22 报障「加载工作区报 530」）
	//
	// ## 530 是什么
	//
	// Cloudflare 边缘返回 `530`（配 error 1033）只有一个含义：
	// **边缘知道这条隧道，但此刻没有任何 cloudflared 连在上面**。
	// 也就是「电脑开着、DSH 开着，但隧道进程死了」。
	//
	// ## 原来为什么会死
	//
	// 原实现只有一句
	//
	//     child.on('exit', (code) => ctx.logger?.warn?.(`隧道退出，code=${code}`));
	//
	// 打完就完事了 —— **没有任何重连**。而 cloudflared 退出的常见原因是
	// 网络抖动 / 休眠唤醒 / 代理切换，这些都不需要人工干预，重启进程即可。
	//
	// 更糟的是那条 warn 在真机上**根本看不到**（`ctx.logger` 进不了 app.log，
	// 用户报障时查过：一条都没有）。于是用户看到的是「手机突然连不上电脑，
	// 报 HTTP 530」，而电脑上一切正常、没有任何提示 —— 只能重启 DSH 才能恢复。
	//
	// ## 重连策略
	//
	// 指数退避 1s → 2s → 4s → … 封顶 30s，**永不放弃**（隧道是远程访问的命脉，
	// 放弃了就等于用户必须手动重启）。用户主动杀隧道（`restartTunnel` /
	// 卸载）时不重连 —— 用 `stopped` 标志区分「意外退出」与「我们叫它退的」。
	let stopped = false;
	let attempt = 0;
	let timer;
	let child;

	// ## ⚠️⚠️ 隧道传输协议：默认走 **http2**，不要用 cloudflared 的默认值
	//
	// 用户反馈（20261028-161358-a74170，iQOO 15u，附完整电脑端日志）：
	//
	//   「插件启动隧道用的命令是 `cloudflared tunnel --no-autoupdate run`，
	//     未指定协议，即默认 QUIC。该网络会丢 UDP 7844：
	//     INF Registered tunnel connection ... protocol=quic 之后立即
	//     WRN datagram manager error: timeout: no recent network activity
	//     WRN Connection terminated error=failed to dial to edge with quic
	//     手动改为 --protocol http2（走 TCP）后能稳定注册。」
	//
	// 也就是说：原实现（不指定 `--protocol`）在**丢 UDP 的网络**里
	// （酒店/校园/部分运营商，UDP 7844 被丢或被 QoS）**隧道根本起不来**，
	// 而 cloudflared 会一直「Registered → Connection terminated」循环，
	// 电脑侧看着像正常、手机侧永远连不上。
	//
	// 而 QUIC 唯一的好处是延迟略低，代价却是这类网络下**完全不可用**。
	// 所以默认改成 http2（TCP，几乎不会被拦），并把 QUIC 保留为可选项：
	//   · 环境变量 `DSH_REMOTE_TUNNEL_PROTOCOL=quic|http2` 可强制指定；
	//   · 不指定时用 http2。
	//
	// 另外做**自动降级**：若 http2 也被显式指成了 quic 且连续建不起来，
	// 见 `downgradeToHttp2` —— 但默认路径不需要它，因为默认就是 http2。
	const requestedProtocol = (() => {
		const raw = String(process.env.DSH_REMOTE_TUNNEL_PROTOCOL ?? '').trim().toLowerCase();
		if (raw === 'quic') return 'quic';
		return 'http2';
	})();
	let protocol = requestedProtocol;
	/** QUIC 连续失败次数（见 relay 里的自动降级）。 */
	let quicStrikes = 0;

	const launch = () => {
		child = spawn(bin, ['tunnel', '--no-autoupdate', '--protocol', protocol, 'run'], {
			stdio: ['ignore', 'pipe', 'pipe'],
			windowsHide: true,
			env: { ...process.env, TUNNEL_TOKEN: token },
		});
		const relay = (buffer) => {
			const line = String(buffer).trim();
			if (line) ctx.logger?.info?.(`[dsh-remote][tunnel] ${line}`);
			// ⚠️ **连上之后要把退避计数清零**。
			//
			// 不清零的话，退避是单调递增的：隧道稳定跑了半小时后又断一次，
			// 会从上次的 16s / 30s 接着退避，而不是从 1s 开始 —— 而那种
			// 「跑很久才断一次」的场景恰恰是网络抖动的常态，用户要等半分钟。
			//
			// cloudflared 建连成功时一定会打含 `Registered tunnel connection`
			// 的那一行（真机日志核对过）。见到它就说明这次起来了。
			if (line.indexOf('Registered tunnel connection') >= 0) attempt = 0;

			// ## QUIC 连不上时自动降级到 http2
			//
			// 只有在**用户显式要求 quic**（`DSH_REMOTE_TUNNEL_PROTOCOL=quic`）
			// 的情况下才需要这段：默认路径已经是 http2，不会进这里。
			//
			// 判据取 cloudflared 在 UDP 被丢/被 QoS 时的固定特征串（用户日志里
			// 的原话，逐字核对过）：
			//
			//     failed to dial to edge with quic: timeout: no recent network activity
			//     datagram manager error: timeout: no recent network activity
			//
			// 连续 3 次就认定这条网络不给 UDP 走，切 http2 并立刻重启隧道。
			// 只降一次（降完 `protocol` 就是 http2，条件不再成立），不会来回抖。
			if (protocol === 'quic') {
				const quicFailed =
					line.indexOf('failed to dial to edge with quic') >= 0 ||
					line.indexOf('datagram manager error') >= 0;
				if (quicFailed) {
					quicStrikes += 1;
					if (quicStrikes >= 3) {
						protocol = 'http2';
						quicStrikes = 0;
						attempt = 0;
						ctx.logger?.warn?.(
							'[dsh-remote][tunnel] QUIC 连续 3 次连不上（该网络大概率丢弃 UDP 7844），' +
								'已自动降级到 http2/TCP 并重启隧道',
						);
						// 立刻重建：不等 exit 的退避，这条隧道此刻已经是死的。
						try {
							child?.kill();
						} catch {
							/* 已退出 */
						}
					}
				}
			}
		};
		child.stdout?.on('data', relay);
		child.stderr?.on('data', relay);
		child.on('error', (error) => {
			ctx.logger?.warn?.(`[dsh-remote] 隧道启动失败: ${String(error?.message ?? error)}`);
		});
		child.on('exit', (code) => {
			if (stopped) {
				ctx.logger?.info?.(`[dsh-remote] 隧道已按请求停止，code=${code}`);
				return;
			}
			// 指数退避，封顶 30 秒。手机那边此刻会看到 530，越快恢复越好，
			// 但也不能在电脑断网时每秒重试一次把日志刷爆。
			const delay = Math.min(1000 * Math.pow(2, attempt), 30000);
			attempt += 1;
			ctx.logger?.warn?.(
				`[dsh-remote] 隧道退出（code=${code}），${Math.round(delay / 1000)} 秒后自动重连（第 ${attempt} 次）`,
			);
			timer = setTimeout(() => {
				if (stopped) return;
				launch();
			}, delay);
			// 别让这个定时器把进程钉住（Node 里 unref 后它不会阻止退出）。
			if (typeof timer.unref === 'function') timer.unref();
		});
	};

	launch();

	// 对外仍然返回一个「像子进程」的句柄：`restartTunnel` / `ctx.effect` 的
	// 清理函数只用到 `kill()`，这里把 kill 语义升级成「停止并禁止重连」。
	return {
		kill() {
			stopped = true;
			if (timer !== undefined) clearTimeout(timer);
			try {
				child?.kill();
			} catch {
				/* 已退出 */
			}
		},
		/** 供诊断：当前是否还在自动重连（`true` = 已停）。 */
		get stopped() {
			return stopped;
		},
	};
}

/**
 * 换一条隧道：杀掉旧的、起新的。
 *
 * ⚠️ 换隧道**必须换进程**：`cloudflared` 的凭据是启动参数，运行中改不了。
 * 旧的还挂着上一次（或内置）的凭据，不杀的话两条隧道同时连着 Cloudflare，
 * 域名解析到哪一条是随机的 —— 表现是「一会儿通一会儿 502」。
 *
 * @param ctx - Cordis 上下文。
 * @param current - 当前隧道子进程，或 `undefined`。
 * @param token - 新隧道 token；为空则只杀不起。
 * @returns 新的子进程，或 `undefined`。
 */
function restartTunnel(ctx, current, token) {
	try {
		current?.kill();
	} catch {
		/* 已退出 */
	}
	if (typeof token !== 'string' || token.length < 16) {
		ctx.logger?.warn?.('[dsh-remote] 配对记录里没有隧道 token，跳过重启隧道');
		return undefined;
	}
	return startTunnel(ctx, token);
}

/**
 * **已作废**的旧共享隧道令牌的指纹 —— 只用于识别，**不是凭据**。
 *
 * ## 为什么需要这一条（外部审计自列 I3）
 *
 * 1.0.11 及更早的版本把**我自己的**那条 Cloudflare 隧道凭据当默认值硬编码在包里
 * （常量名已从源码中彻底移除，`_test/no-shared-credentials.mjs` 就断言它不再出现），
 * 既硬编码在包里、也会被写进 `tunnel.txt`。
 * 那个值已经随 npm 公开发布，**必须视为已泄露**。
 *
 * 老用户升级后 `tunnel.txt` 里还留着它，而 `readTunnelToken` 会照用 ⇒
 * 客户机被挂回**那条共享隧道**，等于 ① 挂到别人的域名上（本人毫不知情）
 * ② 任何拿到那个公开常量的人都能连上**所有**这些机器。这正是 `generateToken`
 * 的注释里描述的那个灾难级缺陷 —— 只修了 `token.txt` 那半边，
 * `tunnel.txt` 这半边漏了。
 *
 * ## ⚠️ 为什么存哈希而不是原值
 *
 * 识别它只需要比对，不需要原值。把完整凭据**再写进一遍源码**没有任何好处，
 * 却会让它继续出现在每个新版本里（审计也专门点了这一点）。所以存 sha256。
 *
 * 判据是「见到就当成没有隧道凭据」—— 与 `LEGACY_PUBLIC_TOKEN_FINGERPRINT` 同构：
 * 不是报错，是**重新走配对**（未配对就不起隧道，本机中继照常跑）。
 */
const LEGACY_SHARED_TUNNEL_FINGERPRINT =
	'70a18de8959ec4697239a8a5fa10abfb1e378328553f4b3ea862b29656a687ad';

/**
 * 判断一个隧道凭据是不是那个已泄露的旧共享令牌。
 *
 * @param value - `tunnel.txt` 里的内容。
 * @returns 是否应视为「没有凭据」。
 */
function isLegacySharedTunnelToken(value) {
	const text = String(value ?? '').trim();
	if (text === '') return true;
	// ⚠️ 比对的是**旧常量本身**的 sha256（见上面常量的注释）。
	return createHash('sha256').update(text, 'utf8').digest('hex') === LEGACY_SHARED_TUNNEL_FINGERPRINT;
}

/**
 * 读隧道凭据：**配对记录优先**，其次文件；都没有就返回 `undefined`（不起隧道）。
 *
 * ⚠️ **这里绝不能有「包内硬编码的兜底隧道令牌」**（早期版本有，是真实漏洞）。
 *
 * 硬编码的后果不是「方便」，而是把**同一条隧道**发给所有客户：每台未配对的机器
 * 都拿同一个凭据去连 `dsh-remote.example.com`，于是
 * ① 客户机被挂到**别人的域名**上，本人毫不知情；
 * ② 谁能拿到那个公开常量（`npm pack` 即可），谁就能连上**所有**这些机器。
 *
 * 隧道凭据只能来自「本机配对」——配对服务按激活码签发**每机一条**独立隧道。
 * 没有配对就不起隧道，本机中继仍在 `127.0.0.1:17890` 上跑（局域网/自建反代可用）。
 *
 * ⚠️ **`tunnel.txt` 里那个已泄露的旧共享令牌要当成没有**（见
 * `LEGACY_SHARED_TUNNEL_FINGERPRINT`）：只修 `token.txt` 那半边不够。
 *
 * @param paired - 配对记录，或 `undefined`。
 * @returns 隧道 token 字符串，或 `undefined`。
 */
function readTunnelToken(paired) {
	if (typeof paired?.tunnelToken === 'string' && paired.tunnelToken.length >= 16) {
		// ⚠️ 配对记录里的凭据也要过这一关：老版本可能把它写进过 pair.json。
		if (isLegacySharedTunnelToken(paired.tunnelToken)) {
			return undefined;
		}
		return paired.tunnelToken;
	}
	let value;
	try {
		value = readFileSync(TUNNEL_PATH, 'utf8').trim();
	} catch {
		return undefined;
	}
	if (value === '') return undefined;
	if (isLegacySharedTunnelToken(value)) {
		// ⚠️ 见到就当成没有（不是报错）：让这台机器回去走配对，而不是
		// 静默挂回那条全网共享的隧道。
		return undefined;
	}
	return value;
}

/**
 * 配对路由的准入检查（fence）。
 *
 * ## ⚠️ 为什么必须自己加（外部审计 D4）
 *
 * 引擎的 `webserver` 只按 pathname 查表，**在路由命中前不做任何鉴权**
 * （`dsh-host-webserver/lib/index.js`：`handle()` 拿到 route 就直接
 * `route.handler(req, res)`）。引擎自己的准入（Host / `sec-fetch-site` /
 * Origin / 浏览器会话 cookie）是加在**它自己那条 `/api` 前缀路由的 handler 里**的。
 *
 * 而 `match()` 是「**最长前缀胜出**」：`/api/dsh-remote/pair`（20 字符）比
 * `/api`（4 字符）长 ⇒ 请求直接落到本函数的 handler，**引擎那层门根本不会被调用**。
 * 结果是：客户机上（按说明只装这两个 tgz、没有额外门卫）该路由**未认证可达**，
 * 于是 `/status`（泄露激活码）、`/handshake`、`/unpair`、`/register` 谁都能打。
 *
 * ## 判据（与引擎同款）
 *
 * - **Host 必须是 loopback** —— 配对界面是**本机**的（见 `handlePairRoute` 的
 *   docstring），没有任何正当理由从外部主机名访问。
 * - **`sec-fetch-site` 不能是 `cross-site`** —— 挡住「用户访问了恶意页面，
 *   那个页面在后台打本机 127.0.0.1」这类攻击。浏览器会带这个头，
 *   而**非浏览器（脚本）可以不带**，所以它只作辅助，不能单独当门。
 * - **Origin（若有）必须与 Host 同源**。
 * - **必须是 JSON 的 `content-type`** —— 挡掉 `text/plain` 这类**跨站简单请求**
 *   （它们不触发预检，浏览器也不带 Origin 之外的凭据）。注意这**不是** CSRF 的
 *   充分条件，但配合上面的 Host/Origin 检查足够挡住审计里点出的那条路。
 *
 * ⚠️ 失败一律回 **403 + 空 body**：不解释原因，不给探测者反馈。
 *
 * @param req - 请求。
 * @returns 是否放行。
 */
function pairRequestAllowed(req) {
	const host = String(req.headers?.host ?? '');
	// Host 可能带端口（`127.0.0.1:3080`）与 IPv6 方括号。
	const hostname = host.startsWith('[')
		? host.slice(0, host.indexOf(']') + 1).toLowerCase()
		: host.split(':')[0].toLowerCase();
	const loopback =
		hostname === '127.0.0.1' ||
		hostname === 'localhost' ||
		hostname === '[::1]' ||
		hostname === '::1';
	if (!loopback) return false;

	// ⚠️ `sec-fetch-site` 缺失时**放行**（非浏览器客户端不带这个头，比如
	// `curl` 或我们自己的测试）；但 `cross-site` 一律拒绝。
	const site = String(req.headers?.['sec-fetch-site'] ?? '').toLowerCase();
	if (site === 'cross-site') return false;

	const origin = req.headers?.origin;
	if (typeof origin === 'string' && origin !== '' && origin !== 'null') {
		let originHost;
		try {
			originHost = new URL(origin).host.toLowerCase();
		} catch {
			return false;
		}
		if (originHost !== host.toLowerCase()) return false;
	}

	// 写操作（非 GET/HEAD）必须是 JSON：`text/plain` 的跨站简单请求不需要预检。
	const method = String(req.method ?? 'GET').toUpperCase();
	if (method !== 'GET' && method !== 'HEAD') {
		const type = String(req.headers?.['content-type'] ?? '').toLowerCase();
		if (!type.includes('application/json')) return false;
	}
	return true;
}

/**
 * 配对路由的准入检查。
 *
 * ## ⚠️ 为什么需要两层（外部审计 4.1 / AUDIT_desktop 第 1 条）
 *
 * 引擎的 `webserver` 在路由命中**之前不做任何鉴权**，而 `match()` 是
 * 「精确表先命中、再最长前缀」：本插件的 `/api/dsh-remote/pair`(20) 比引擎的
 * `/api`(4) 长 ⇒ 请求落进本插件的 handler，**引擎那层 cookie 门根本不会被调用**。
 *
 * 第一版只有下面 `pairRequestAllowed` 那一条，它只做**浏览器向的 CSRF 防护**
 * （Host 是 loopback + `sec-fetch-site` + Origin 同源）。那挡不住**同机其它进程
 * 或同机另一个用户**：它们打 `127.0.0.1` 时 Host 也是 loopback，而非浏览器
 * 客户端根本不带 `sec-fetch-site`。攻击链：
 *
 * ```
 * GET  /api/dsh-remote/pair/status    → 拿到 hostname
 * GET  /api/dsh-remote/pair/handshake → 拿到 6 位配对码
 * POST https://pair.example.com/pair/claim → 拿到 accessToken
 * POST https://<hostname>/api/dsh-remote/rpc  → 驱动这台电脑上的真 agent
 * ```
 *
 * 同一作者在**手机端**已经用引擎的 `connection.requestRejection` 修过同类问题
 * （见 `dsh-remote-mobile` 的 `bridgeRejection`），电脑端当时没跟上 —— 这里补上。
 *
 * ## ⚠️ 取不到 `connection` 一律 **fail-closed**，与手机端同款
 *
 * 中途有一版在这里 fail-open（拿不到引擎判据就退回弱判据 + 告警），理由是
 * 「真 fail-closed 会让还没建会话的用户打不开配对界面」。⚠️ **那个理由是错的**：
 * `connection` **服务**是否存在，与**用户有没有登录**是两件事 ——
 * 前者是 cordis 的服务注册，引擎启动后必然就绪；后者才是 cookie。
 * 把两者混为一谈，等于用一个不存在的场景换来一个**永久**的不安全状态
 * （只要判据取不到就一直弱判据），那正是审计要修的东西。
 *
 * 窗口期的真实形态：cordis 的 `ctx.get(name, strict)` 对尚未 ACTIVE 的提供者
 * 返回 `undefined`（`cordis/lib/index.js:770`），而 client-connection 的
 * `await BrowserAuth.create(...)` 排在 `provide('connection')` 之前 ⇒
 * 引擎启动 / 插件重载期间本路由已经在收请求，而服务还没就绪。
 * 这段窗口**几秒钟**，界面表现为 HTTP 503 —— 刷新一下即可，与手机端一致。
 *
 * ## 逃生口（默认关）
 *
 * 万一某个部署里 `connection` 因非预期原因始终不可用，配对功能会整体不可用
 * （而配对是本机唯一的绑定入口，没有别的自救路径）。所以留一个**显式**开关：
 * `DSH_REMOTE_ALLOW_WEAK_GATE=1` 时才退回弱判据，并且**每次**都打告警。
 * ⚠️ 默认必须是关的 —— 默认开的判据等于没有判据。
 *
 * @param ctx - Cordis 上下文。
 * @param req - 请求。
 * @returns 拒绝时的 HTTP 状态码，放行时 `undefined`。
 */
function pairGateRejection(ctx, req) {
	// 第一层：CSRF 向的弱判据（永远要过）。
	if (!pairRequestAllowed(req)) return 403;

	// 第二层：引擎的判据（含浏览器会话 cookie）。
	const connection = ctx.get?.('connection');
	if (connection !== undefined && typeof connection.requestRejection === 'function') {
		try {
			return connection.requestRejection(req);
		} catch (error) {
			ctx.logger?.warn?.(
				`[dsh-remote][pair] connection.requestRejection 抛错，配对路由 503（fail-closed）: ${String(error?.message ?? error)}`,
			);
			return pairWeakGateFallback(ctx, '判据抛错');
		}
	}
	return pairWeakGateFallback(ctx, '取不到 connection 服务');
}

/**
 * `connection` 不可用时的处置：默认拒绝，只有显式开关打开才退回弱判据。
 *
 * @param ctx - Cordis 上下文。
 * @param why - 原因（进告警文案）。
 * @returns 拒绝时的状态码，逃生口打开时 `undefined`。
 */
function pairWeakGateFallback(ctx, why) {
	if (process.env.DSH_REMOTE_ALLOW_WEAK_GATE === '1') {
		// ⚠️ 每一次都告警（不折成「只报一次」）：这是**降低安全性**的状态，
		// 一直提示才对得起它。只报一次的话，运维翻日志时会以为早修好了。
		ctx.logger?.warn?.(
			`[dsh-remote][pair] ${why}，已按 DSH_REMOTE_ALLOW_WEAK_GATE=1 退回弱判据（Host + Origin + sec-fetch-site，**不含**浏览器会话校验）—— 同机其它进程/用户不构成边界`,
		);
		return undefined;
	}
	if (!pairGateDegradedWarned) {
		pairGateDegradedWarned = true;
		ctx.logger?.warn?.(
			`[dsh-remote][pair] ${why}，配对路由一律 503（fail-closed，不降级到弱判据）—— 界面刷新即可；若持续如此，确认部署环境是否需要 DSH_REMOTE_ALLOW_WEAK_GATE=1`,
		);
	}
	return 503;
}

/** fail-closed 告警只打一次，别刷屏（逃生口那条不在这里，它每次都打）。 */
let pairGateDegradedWarned = false;

/**
 * 处理一条配对相关的 HTTP 请求。
 *
 * 路由挂在 DSH 自己的 Web 端口上（不是中继端口 17890）—— 配对界面是
 * **本机**的，不该经公网隧道绕一圈回来。
 *
 * @param ctx - Cordis 上下文。
 * @param pairing - `createPairing()` 的返回值。
 * @param state - `apply()` 的运行态 `{ relay, tunnel }`（配对要热切这两样）。
 * @param req - 请求。
 * @param res - 响应。
 */
function handlePairRoute(ctx, pairing, state, req, res) {
	const reply = (code, payload) => {
		res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
		res.end(JSON.stringify(payload));
	};
	// ⚠️ 前置准入：只解析 body **之前**判，被拒的请求连 JSON 都不解析（见 fence 的 docstring）。
	const rejected = pairGateRejection(ctx, req);
	if (rejected !== undefined) {
		res.writeHead(rejected);
		res.end();
		return;
	}
	const chunks = [];
	req.on('data', (c) => chunks.push(c));
	req.on('end', async () => {
		const url = new URL(req.url ?? '/', 'http://127.0.0.1');
		const action = url.pathname.slice(PAIR_ROUTE.length) || '/status';
		let body = {};
		if (chunks.length > 0) {
			try {
				body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
			} catch (error) {
				reply(400, { ok: false, message: `请求体不是合法 JSON: ${String(error?.message ?? error)}` });
				return;
			}
		}
		try {
			switch (action) {
				case '/status':
					reply(200, { ok: true, ...pairing.status() });
					return;

				/** 填激活码 → 向服务器注册 → 起隧道。 */
				case '/register': {
					// ## ⚠️⚠️ 这里**绝不能再下载 cloudflared**（用户报障，已改）
					//
					// 用户原话：
					//   「用户经常会遇到绑定的时候依赖下载超时，导致激活码也失效了，
					//    然后又安装不上的问题。改成用户可以自己提前点击下载依赖，
					//    就是环境检查通过了之后，再输入激活码。」
					//
					// 原来的顺序是：`register()` 先把激活码交给服务端（**此刻就已经
					// 消费掉了**）→ 然后在同一次 HTTP 请求里下载 55 MB 的
					// cloudflared（实测 47–67 秒，`ensureCloudflared` 的超时是 300 秒）。
					//
					// 于是国内网络下最常见的一幕是：
					//   ① 激活码已经绑到这台机器上（服务端记录已建、隧道已建）
					//   ② 下载超时/中断 ⇒ 这次响应是失败的
					//   ③ 用户看到「绑定失败」，以为激活码没用上，**再填一次**
					//   ④ 服务端按设备凭据认机器，但这台机器的 `pair.json` 里
					//      已经写了 `clientSecret`，于是走的是「复用」分支……
					//      而用户如果清过配置/重装插件，`clientSecret` 没了，
					//      服务端就是 fail-closed ⇒「设备不符」⇒ **激活码永久废掉**。
					//
					// 现在把两件事**彻底拆开**：
					//   `/environment` → 先检查/下载依赖（可以慢慢下、可以重试）
					//   `/register`    → 只做注册，几秒返回，不再碰网络大文件
					//
					// 这样「下载失败」永远不会影响激活码。
					const result = await pairing.register(body.code);
					if (!result.ok) {
						reply(200, { ok: false, message: result.message });
						return;
					}
					// ⚠️ 换隧道必须**热切**，不能让客户去重启 DSH。
					//
					// 配对是运行中发生的：旧的隧道子进程还挂着内置凭据，
					// 中继也还在用旧令牌校验。两处都要就地换掉 ——
					// 面向大众的产品里「配完请重启」这一步等于功能不可用。
					ctx.logger?.info?.(`[dsh-remote][pair] 隧道切换到 ${result.record.hostname}`);
					// 只**查**不下载：注册这一步绝不阻塞在网络上。
					const binary = pairing.findCloudflared();
					if (binary === undefined) {
						ctx.logger?.warn?.(
							'[dsh-remote][pair] cloudflared 未就绪，隧道没起来（请在设置里先做「环境准备」）',
						);
					}
					if (state.relay !== undefined && typeof result.record.accessToken === 'string') {
						state.relay.setToken(result.record.accessToken);
					}
					state.tunnel = restartTunnel(ctx, state.tunnel, result.record.tunnelToken);
					reply(200, {
						ok: true,
						message: binary !== undefined
							? result.message
							: `${result.message}。但隧道组件还没装好，请点上面的「下载隧道组件」补上，否则手机连不上这台电脑`,
						...pairing.status(),
					});
					return;
				}

				/**
				 * 环境准备：检查依赖，缺就下载。
				 *
				 * 与 `/register` 拆开的原因见 `/register` 顶部的注释 —— 一句话：
				 * **下载失败不能牵连激活码**。这个端点可以随便重试、可以慢慢下，
				 * 用户想点几次点几次。
				 */
				case '/environment': {
					/*
					 * `body.channel` 允许前端指定渠道（需求 4）：
					 * `'github'`（官方，默认）/ `'mirror'`（作者中转）。
					 * 不传就让 `ensureCloudflared` 按默认顺序自动试。
					 */
					const result = await pairing.ensureCloudflared({ channel: body?.channel });
					reply(200, { ok: result.ok === true, ...result, ...pairing.status() });
					return;
				}

				/** 生成 6 位配对码 + 二维码。 */
				case '/handshake': {
					const result = await pairing.handshake();
					reply(200, result);
					return;
				}

				/** 确保 cloudflared 在位（需要时自动下载）。 */
				case '/cloudflared': {
					const result = await pairing.ensureCloudflared({ channel: body?.channel });
					reply(200, result);
					return;
				}

				/**
				 * 解绑：**先通知服务端清记录**，再清本机凭据、换令牌、停隧道。
				 *
				 * ⚠️ 必须通知服务端（外部审计 4.3）：否则服务端记录仍在，而
				 * `legacy_claim` 已删除 ⇒ 这台机器**再也注册不上**（唯一出路是
				 * 管理员 unregister）。详见 `pairing.release()` 的注释。
				 */
				case '/unpair': {
					const result = await pairing.release();
					// 解绑后必须**换掉**令牌：旧令牌已随配对公开过（手机端存了一份），
					// 继续用等于解绑无效。
					const fresh = generateToken();
					try {
						writeFileSync(TOKEN_PATH, fresh, { mode: 0o600 });
					} catch (error) {
						ctx.logger?.warn?.(`[dsh-remote] 解绑时写令牌失败: ${String(error?.message ?? error)}`);
					}
					if (state.relay !== undefined) state.relay.setToken(fresh);
					// 隧道凭据属于**那一台**已配对的机器，解绑即失效；没有新凭据就不再起隧道。
					state.tunnel = restartTunnel(ctx, state.tunnel, undefined);
					// ⚠️ 服务端没清成功要**如实告诉用户**（不能只说「已解绑」）：
					// 那种情况下本机确实不再对外了，但同一激活码重配会失败。
					reply(200, {
						ok: true,
						released: result.released,
						message: result.released
							? '已解绑（服务端记录已清除），如需重连请重新配对'
							: `本机已解绑并停止隧道，但服务端记录未清除（${result.message}）——如需重新配对请联系客服`,
					});
					return;
				}

				default:
					reply(404, { ok: false, message: `未知操作 ${action}` });
			}
		} catch (error) {
			ctx.logger?.warn?.(`[dsh-remote][pair] ${action} 失败: ${String(error?.message ?? error)}`);
			reply(200, { ok: false, message: String(error?.message ?? error) });
		}
	});
}

/**
 * 插件入口。
 *
 * @param ctx - Cordis 上下文。
 * @param config - 插件配置：`{ tunnelToken?, autoTunnel? }`。
 */
/**
 * **数据变更标记（dirty flag）** —— 需求 5 的核心。
 *
 * ## 用户需求（2026-10-28）
 *
 * 「我希望两端的插件都可以实时收集它对应那边的 DSH 的状态……随时监听一下
 *   他那边的工作区呀、模型列表呀、预设的列表，这些都可以监听一下，没有变化的
 *   话就不做任何动作，有变化的话那就下次手机发起连通性测试的时候告诉手机
 *   我这边有数据变化了你同步一下，然后再进行一个同步，这样子的话就可以把
 *   性能压力分摊到两端，而不是实时的网络请求。」
 *
 * ## 做法
 *
 * 电脑侧**只在内存里记"什么东西变了"**，一个 bit 都不用发出去：
 *
 *   · 收到 `domain/changed`（工作区/模型/预设任一）⇒ 打上对应标记；
 *   · 手机下一次 ping（`pingStatus` 或 `ping`）⇒ 把标记附带在响应里；
 *   · 手机看到标记就去拉对应数据，然后**调 `ackDirty` 清标记**；
 *   · 没有变化 ⇒ ping 响应里 `dirty` 为空数组，手机什么都不做。
 *
 * 这样：
 *   · 零额外网络请求（监听是本地的，上报搭在本来就要发的 ping 上）；
 *   · 压力在两端分摊（电脑只做内存标记，手机只在自己主动问时才知道）；
 *   · 不会漏（内存标记只在被 ack 后才清，手机没收到就一直亮着）。
 *
 * ## 为什么用 `Set` 而不是时间戳
 *
 * 标记只有「脏/干净」两态。用时间戳会引入"多久算过期"的判断，
 * 而实际上只要手机没确认过，就应该一直告诉它"有变化"——
 * 用 `Set` 的增删最直接，也便于 `ackDirty` 精确清除。
 */
const dirtyDomains = new Set();

/** 标记某个域的数据变了（同一次变更重复打标记是幂等的）。 */
function markDirty(domain) {
	if (typeof domain !== 'string' || domain === '') return;
	dirtyDomains.add(domain);
}

/** 取出当前全部脏标记（不清除 —— 由手机 ack 才清）。 */
function snapshotDirty() {
	return [...dirtyDomains];
}

export function apply(ctx, config = {}) {
	const pairing = createPairing({ stateDir: STATE_DIR, logger: ctx.logger });
	const paired = pairing.read();
	const token = ensureToken(ctx, paired);
	const state = { relay: undefined, tunnel: undefined };

	/*
	 * ---- 状态变更监听（需求 5，见 `dirtyDomains` 的长注释） ----
	 *
	 * 只监听、只打内存标记，**不发任何网络请求**。
	 *
	 * ## 为什么监听三类
	 *
	 * 用户点名的就是这三样：「工作区呀、以及模型列表呀、以及预设的列表」。
	 * 它们在官方事件里都走 `domain/changed`，靠 `change.domain` 区分。
	 *
	 * ## ⚠️ 必须包 try/catch 且不能抛
	 *
	 * 这是挂在引擎事件总线上的监听器。它抛错会污染整条派发链
	 *（官方 `domain/changed` 是并发派发给所有订阅者的），
	 * 表现可能是「改个工作区名字，整个 GUI 卡一下」。
	 */
	ctx.effect(() => {
		const off = ctx.on('domain/changed', function (change) {
			try {
				if (change === null || typeof change !== 'object') return;
				const domain = typeof change.domain === 'string' ? change.domain : '';
				const table = typeof change.table === 'string' ? change.table : '';
				/*
				 * 映射到手机端认识的三类（与 `case 'dirty'` 的返回一致）：
				 *   · workspace —— 工作区增删/改名
				 *   · model     —— 模型目录变化
				 *   · preset    —— 预设列表变化
				 *
				 * 判据同时看 `domain` 与 `table`：不同版本的字段不一致
				 *（工作区那条实测是 `domain: 'workspace'` 或
				 * `table: 'workspaces'`，见手机端 `subscribe` 那段注释）。
				 */
				if (domain === 'workspace' || table === 'workspaces') markDirty('workspace');
				else if (domain === 'model' || table === 'models') markDirty('model');
				else if (domain === 'preset' || table === 'presets') markDirty('preset');
			} catch {
				/* 监听器自身绝不能让引擎的事件派发崩掉 */
			}
		});
		return () => {
			if (typeof off === 'function') off();
		};
	});

	ctx.effect(() => {
		try {
			state.relay = startRelay(ctx, token);
			ctx.logger?.info?.(`[dsh-remote] 中继已监听 http://127.0.0.1:${RELAY_PORT}`);
			// ⚠️⚠️ **绝不能把令牌本身打进日志**（外部审计 D3）。
			// 令牌 = 这台电脑的完全控制权：日志会落盘、可被 GUI 的 `/export`
			// 导成 zip、可能随诊断包上传，而 **agent 自己就有读日志的能力**
			// （用户一句「把日志打出来」就等于把钥匙交出去）。
			// 需要排查时只打**指纹**：`tokenHash` 可对账、不可复用。
			ctx.logger?.info?.(
				`[dsh-remote] 访问令牌: <已隐藏，指纹 ${tokenFingerprint(token)}>`
			);
			ctx.logger?.info?.(`[dsh-remote] 令牌文件: ${TOKEN_PATH}`);
			if (paired !== undefined) {
				// ⚠️ 同理不打激活码（外部审计 D3）：配合 S1 那条链，日志里的
				// 激活码 = 静默接管。只留主机名供运维对账。
				ctx.logger?.info?.(`[dsh-remote] 已配对: ${paired.hostname}`);
			} else {
				ctx.logger?.info?.(`[dsh-remote] 尚未配对，请在设置里填激活码（配对服务 ${PAIR_ENDPOINT}）`);
			}
		} catch (error) {
			ctx.logger?.error?.(`[dsh-remote] 中继启动失败: ${String(error?.message ?? error)}`);
		}

		// ---- 配对路由（挂在本机 Web 端口上） ----
		//
		// ⚠️ 只注册 `prefix` 一条：`register()` 对重复的 `(kind, path)` 会**抛错**
		//（`webserver: duplicate ...`），而 exact 与 prefix 是两张表 —— 同时注册
		// 同一个 path 虽然不冲突，但 exact 永远先命中，等于白写一条。
		// 前缀匹配已经覆盖 `/api/dsh-remote/pair` 与它的全部子路径。
		if (typeof ctx.webServer?.register === 'function') {
			ctx.effect(
				() =>
					ctx.webServer.register({
						kind: 'prefix',
						path: PAIR_ROUTE,
						handler: (req, res) => handlePairRoute(ctx, pairing, state, req, res),
					}),
				'dsh-remote-desktop: pairing route',
			);
		} else {
			ctx.logger?.warn?.('[dsh-remote] webServer 不可用，配对界面无法工作（中继不受影响）');
		}

		const tunnelToken = config.tunnelToken ?? process.env.DSH_REMOTE_TUNNEL_TOKEN ?? readTunnelToken(paired);
		if (config.autoTunnel === false) {
			ctx.logger?.info?.('[dsh-remote] autoTunnel=false，只跑本地中继（不起隧道）');
		} else if (typeof tunnelToken !== 'string' || tunnelToken.length < 16) {
			// 未配对（且没给 tunnelToken / tunnel.txt）⇒ **不起隧道**。
			// 这是修掉「包内硬编码共享隧道凭据」之后的正常状态，不是错误。
			ctx.logger?.info?.(
				'[dsh-remote] 尚未配对，未启动隧道（中继仍在 127.0.0.1:' +
					`${RELAY_PORT} 上监听）；配对成功后会自动起隧道。`,
			);
		} else {
			state.tunnel = startTunnel(ctx, tunnelToken);
		}

		return () => {
			try {
				state.tunnel?.kill();
			} catch {
				/* 已退出 */
			}
			try {
				state.relay?.close();
			} catch {
				/* 已关闭 */
			}
		};
	}, 'dsh-remote-desktop: relay + tunnel + pairing');
}

export { RELAY_PORT, TOKEN_PATH, TUNNEL_PATH, PAIR_ROUTE, listDirectory, handleRpc };
