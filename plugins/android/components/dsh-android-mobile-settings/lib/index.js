/**
 * Android 业务设置的宿主半：特权状态 HTTP 探测。
 * 客户端 settings.section 独立由 dsh-client-modules 加载，不等待 webServer。
 * 保留原探测路径、超时、身份判据和响应契约。
 */

import { spawn } from 'node:child_process';
import { readFile, stat } from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

export const name = 'dsh-android-mobile-settings';

/**
 * 状态接口挂在 web 组合的 HTTP 载体上，所以依赖 `webServer`。
 *
 * ⚠️ 这只影响**宿主半**：客户端半由 `dsh-client-modules` 独立扫描 `dsh.client`
 * 声明编入，宿主半激活与否都不影响「特权权限」分节照常注册。
 * 极端情况下（无 webServer 的部署）接口 404，设置页显示「无法获取状态」。
 *
 * @type {string[]}
 */
export const inject = ['webServer'];

/** 状态接口路径。exact 路由优先于 `/api` 前缀，所以不会被 RPC 通道截走。 */
const ROUTE = '/api/dsh-android/privileged';

/**
 * 探测超时。
 *
 * ⚠️ **这个值必须覆盖 runner 最坏的一条路径**，否则设置页会拿到「探测超时」而不是
 * 真正的原因 —— 而「告诉用户到底哪里不对」正是这个接口存在的理由。
 *
 * 最坏路径 = 通道 1 秒退（ECONNREFUSED）→ 通道 1.5 的 `switchToTcp`：
 *   · 读无线调试端口文件，最多 5 秒重试窗口；
 *   · `adb start-server` + `adb connect`（内置 adb 冷启动本身要 1~2 秒）；
 *   · `adb tcpip 5555` 之后**最多 20 秒**轮询确认 `id -u` 真能拿到 2000
 *     （adbd 重启常超过 6 秒，这里必须给足，见 runner 的 `TCPIP_VERIFY_BUDGET_MS`）。
 * 合计约 30 秒，取 45 秒留余量。
 *
 * 稳态下（5555 正常）这一次探测只花几百毫秒 —— 大预算只在出问题时才被用到，
 * 而那时用户恰恰**需要**等出一个明确结论。
 */
const PROBE_TIMEOUT_MS = 45000;

/** runner 打在 stderr 上的前缀，与 `assets/dsh-android-shell.mjs` 的 `TAG` 一致。 */
const TAG = '[dsh-adb]';

/** runner 回落成 app 身份时的 stderr 文案（原因在中文括号里）。 */
const FALLBACK_RE = /uid 2000 不可用（(.+?)）/;

/**
 * runner 那条机器可读的失败分类行：`[dsh-adb] 诊断: kind=xxx key=… legacy=… wireless=…`。
 *
 * ⚠️ **必须与 `assets/dsh-android-shell.mjs` 的输出逐字对应。** 那边的 `kind`
 * 取值由 `classifyFallback()` 决定，改一边而另一边不改会让设置页静默退回通用引导
 * （不会报错，只是不再给出针对性的下一步）。
 */
const DIAG_KIND_RE = /诊断: kind=([a-z-]+)/;

/**
 * 端口是否**真的在监听**。
 *
 * 判据与 Kotlin 侧 `AdbMdns.isPortInUse()` 同构，但方向相反、更适合这里：
 * 那边是「bind 失败 = adbd 在听」，这边直接 **connect 一次**——无线调试的
 * TLS 端口接受 TCP 连接（TLS 握手之后才发生），连得上就说明 adbd 真的在听。
 *
 * ⚠️ **不能只看端口文件里有没有值**：无线调试一关，`onServiceLost` 未必来得及
 * 清文件（进程可能已被冻结），文件里的陈旧端口会让设置页显示绿灯。真机实测
 * 撞到过：无线调试关着、35589 早已没人监听，界面却报「无线端口 35589」。
 *
 * @param {number} port - 待验证的端口。
 * @returns {Promise<boolean>} 是否有人监听。
 */
function portAlive(port) {
	return new Promise((resolve) => {
		const sock = net.connect({ host: '127.0.0.1', port });
		const done = (ok) => {
			sock.removeAllListeners();
			sock.destroy();
			resolve(ok);
		};
		sock.setTimeout(700);
		sock.on('connect', () => done(true));
		sock.on('error', () => done(false));
		sock.on('timeout', () => done(false));
	});
}

/**
 * 引擎的 `$DSH_HOME`。
 * @returns {string} 绝对路径。
 */
function homeDir() {
	const home = process.env.DSH_HOME;
	if (typeof home === 'string' && home !== '') return home;
	return path.join(os.homedir(), '.dsh');
}

/**
 * uid 2000 执行通道的落盘路径。
 *
 * 优先用启动时注入的 `DSH_PRIV_RUNNER`；缺失时按 `EngineManager` 的布局回推
 * （`$DSH_HOME` = `<filesDir>/home/.dsh`，runner 在 `<filesDir>/dsh-android-shell.mjs`，
 * 所以从 `$DSH_HOME` 上溯两级）。
 *
 * @param {string} home - 引擎的 `$DSH_HOME`。
 * @returns {string} runner 的绝对路径。
 */
function runnerPath(home) {
	const explicit = process.env.DSH_PRIV_RUNNER;
	if (typeof explicit === 'string' && explicit !== '') return explicit;
	return path.resolve(home, '..', '..', 'dsh-android-shell.mjs');
}

/**
 * `stat` 一个普通文件，失败（不存在/目录/权限）一律给 `null`。
 * @param {string} target - 待查路径。
 * @returns {Promise<{size: number, mtimeMs: number}|null>} 文件信息或 `null`。
 */
async function statFile(target) {
	try {
		const info = await stat(target);
		return info.isFile() ? { size: info.size, mtimeMs: info.mtimeMs } : null;
	} catch {
		return null;
	}
}

/**
 * 跑一次 runner 并收齐输出。**不做回落判断**，只如实返回。
 * @param {string} runner - runner 的绝对路径。
 * @param {string} command - 交给 runner 的命令。
 * @param {string} home - 引擎的 `$DSH_HOME`。
 * @returns {Promise<{stdout: string, stderr: string, code: number|null, timedOut: boolean}>} 执行结果。
 */
function runRunner(runner, command, home) {
	return new Promise((resolve) => {
		let settled = false;
		const done = (value) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			resolve(value);
		};
		let child;
		try {
			child = spawn(process.execPath, [runner, command], {
				// ⚠️ 必须显式给 DSH_HOME：runner 靠它定位 adbkey，缺了就静默回落。
				env: { ...process.env, DSH_HOME: home },
				stdio: ['ignore', 'pipe', 'pipe'],
			});
		} catch (error) {
			resolve({ stdout: '', stderr: String(error?.message ?? error), code: null, timedOut: false });
			return;
		}
		let stdout = '';
		let stderr = '';
		let timedOut = false;
		const timer = setTimeout(() => {
			timedOut = true;
			child.kill('SIGKILL');
		}, PROBE_TIMEOUT_MS);
		child.stdout.setEncoding('utf8');
		child.stderr.setEncoding('utf8');
		child.stdout.on('data', (chunk) => {
			stdout += chunk;
		});
		child.stderr.on('data', (chunk) => {
			stderr += chunk;
		});
		child.on('error', (error) => {
			done({ stdout, stderr: `${stderr}${error?.message ?? error}`, code: null, timedOut });
		});
		child.on('close', (code) => {
			done({ stdout, stderr, code, timedOut });
		});
	});
}

/**
 * 端到端探测一次特权能力。
 * @returns {Promise<object>} 可直接 JSON 序列化的状态快照。
 */
async function probe() {
	const home = homeDir();
	const runner = runnerPath(home);
	const keyPath = path.join(home, 'adbkey');

	const runnerStat = await statFile(runner);
	const keyBefore = await statFile(keyPath);

	/** @type {any} */
	const snapshot = {
		home,
		runner: { path: runner, ok: runnerStat !== null, size: runnerStat?.size ?? 0 },
		key: {
			path: keyPath,
			ok: keyBefore !== null,
			size: keyBefore?.size ?? 0,
			mtimeMs: keyBefore?.mtimeMs ?? 0,
			importedNow: false,
			origin: null,
		},
		identity: { ok: false, uid: null, reason: '', channel: 'none', kind: 'unknown', log: [] },
	};

	if (runnerStat === null) {
		snapshot.identity.reason = '执行通道文件不存在，无法探测';
		snapshot.identity.kind = 'no-runner';
		return snapshot;
	}

	const result = await runRunner(runner, 'id -u', home);
	const uid = result.stdout.trim();

	// runner 的 `[dsh-adb] …` 行是给用户看的原因（密钥导入、回落原因），原样带上。
	snapshot.identity.log = result.stderr
		.split('\n')
		.map((line) => line.trim())
		.filter((line) => line.startsWith(TAG));

	// 这次实际走的是哪条通道 —— 从 runner 自己打的 `通道: …` 行里读，不额外探测。
	// 只有 legacy 5555 那条**不依赖无线调试开关**，所以设置页要能区分出来，
	// 否则用户看到「已就绪」也不知道关了无线调试会不会断。
	//
	// ⚠️ Shizuku 那条更特殊：它**连 adbd 都不需要**（不依赖 5555、不依赖无线调试、
	//    也不校验 adbkey）。对 Android 10 及以下它就是唯一免 PC 的出路，
	//    所以必须单独认出来并显示 —— 否则用户看到「已就绪」会以为还得靠电脑。
	snapshot.identity.channel = 'none';
	if (/通道: Shizuku/.test(result.stderr)) snapshot.identity.channel = 'shizuku';
	else if (/通道: legacy/.test(result.stderr)) snapshot.identity.channel = 'legacy';
	else if (/通道: 无线调试/.test(result.stderr)) snapshot.identity.channel = 'wireless';

	if (uid === '2000') {
		snapshot.identity.ok = true;
		snapshot.identity.uid = '2000';
	} else {
		const fallback = FALLBACK_RE.exec(result.stderr);
		snapshot.identity.uid = uid === '' ? null : uid;
		snapshot.identity.reason = fallback !== null
			? fallback[1]
			: result.timedOut
				? `探测超时（${PROBE_TIMEOUT_MS / 1000} 秒）`
				: uid === ''
					? '执行通道没有返回身份'
					: `实际身份是 uid ${uid}，没有拿到 uid 2000`;
		// ⚠️ **机器可读的失败分类**，来自 runner 那条 `诊断: kind=… key=… …` 行。
		//
		// 上面那句 `reason` 是给人看的（且受 `FALLBACK_RE` 的非贪婪匹配限制，
		// 只能是一句话），而设置页需要据此决定**该给用户哪个按钮** —— 靠中文
		// 文案去猜分支太脆（改一个字就失效），所以 runner 额外打了一条结构化行。
		//
		// 取值见 `assets/dsh-android-shell.mjs` 的 `classifyFallback()`：
		//   no-key / key-revoked / key-unauthorized / adb-missing / key-blocked
		//   / wireless-stale / no-channel / unknown
		// 没拿到（老版本 runner、或异常退出）时保持 'unknown'，客户端会退回
		// 现在的通用引导，不会因为多了个字段就显示空白。
		const kind = DIAG_KIND_RE.exec(result.stderr);
		if (kind !== null) snapshot.identity.kind = kind[1];
		else if (result.timedOut) snapshot.identity.kind = 'timeout';
	}

	// 密钥可能是**这次探测**才导入的（runner 首次运行会搬运中转文件），复查一次。
	const keyAfter = await statFile(keyPath);
	if (keyAfter !== null) {
		snapshot.key.ok = true;
		snapshot.key.size = keyAfter.size;
		snapshot.key.mtimeMs = keyAfter.mtimeMs;
		snapshot.key.importedNow = keyBefore === null;
	}

	// 密钥**来源**：`imported`（从 PC 中转文件搬进来，设备已信任，直接能用）
	// 还是 `generated`（App 现场生成，**设备尚未信任，必须先配对**）。
	//
	// ⚠️ 光看密钥文件在不在会误报 —— 两种情况都"有密钥"，但只有前者能直接拿到
	// uid 2000。来源由 runner 在写入密钥那一刻落盘到 `adbkey.origin`
	// （见 assets/dsh-android-shell.mjs 的 `markKeyOrigin()`），上面那次 `id -u`
	// 已经跑过 `ensureKey()`，所以此刻这个文件一定是最新的。
	snapshot.key.origin = null;
	try {
		const raw = (await readFile(path.join(home, 'adbkey.origin'), 'utf8')).trim();
		if (raw === 'imported' || raw === 'generated' || raw === 'paired') snapshot.key.origin = raw;
	} catch {
		// 老版本装出来的密钥没有这个标记，或者密钥还没生成 —— 交给 UI 兜底
	}

	// 无线调试（Android 11+）的端口：由 Kotlin 侧的 `NsdManager` 发现后写入
	// `$DSH_HOME/adb-wireless-port`。Node 侧读不到也发不出 mDNS（SELinux 挡组播），
	// 所以这里只读文件，不做任何发现 —— 文件不在就说明无线调试没开。
	//
	// ⚠️ **但读到的值必须再验一次「端口是否真的在监听」**：文件可能是陈旧的
	// （无线调试关了、`onServiceLost` 没来得及清）。只信文件会让设置页显示
	// 一个早已死掉的端口，还点着绿灯。详见 portAlive()。
	//
	// 格式：**单个连接端口**（见 assets/dsh-android-shell.mjs 的 `readWirelessPort`）。
	// ⚠️ 这里原先还解析第二个字段当「配对端口」——那是错的：写文件的
	//   `WirelessDebugging.writePort()` 从来只写一个值，所以那个字段恒为 null，
	//   而设置页也从不显示它。配对端口走的是通知 → `pair(knownPort)` 那条路。
	snapshot.wireless = { connect: null, stale: null };
	try {
		const raw = (await readFile(path.join(home, 'adb-wireless-port'), 'utf8')).trim();
		const connect = Number.parseInt(raw.split(/\s+/)[0], 10);
		if (Number.isInteger(connect) && connect > 0) {
			if (await portAlive(connect)) snapshot.wireless.connect = connect;
			// 文件里有值、端口却没人听 —— 这是「无线调试已关」的确凿证据，
			// 交给 UI 说明为什么绿灯没了（否则用户会以为是检测坏了）。
			else snapshot.wireless.stale = connect;
		}
	} catch {
		// 文件不存在 = 无线调试没开或还没发现，属于正常情况
	}

	return snapshot;
}

/**
 * 挂上「特权权限」状态接口。
 * @param {object} ctx - Cordis 上下文（已注入 `webServer`）。
 */
export function apply(ctx) {
	ctx.effect(() => ctx.webServer.register({
		kind: 'exact',
		path: ROUTE,
		handler: (req, res) => {
			if (req.method !== 'GET' && req.method !== 'HEAD') {
				res.writeHead(405, { allow: 'GET, HEAD' });
				res.end();
				return;
			}
			probe().then((body) => {
				res.writeHead(200, {
					'content-type': 'application/json; charset=utf-8',
					// 状态含密钥存在性与时间戳，不给任何一层缓存。
					'cache-control': 'no-store',
				});
				res.end(req.method === 'HEAD' ? undefined : JSON.stringify(body));
			}).catch((error) => {
				ctx.logger?.warn?.(`[dsh-android-mobile] 特权状态探测失败: ${error?.message ?? error}`);
				if (res.headersSent) {
					res.destroy();
					return;
				}
				res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
				res.end(JSON.stringify({ error: String(error?.message ?? error) }));
			});
		},
	}), 'dsh-android-mobile: privileged status route');
}
