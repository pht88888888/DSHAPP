#!/usr/bin/env node
/**
 * dsh-android-shell.mjs —— uid 2000（shell）执行通道。
 *
 * ## 为什么需要它
 *
 * DSH 的 bash 工具跑在 App 自己的 uid（如 10323）下，被 SELinux 的
 * `untrusted_app` 域限制，很多操作**做不到**，而且失败方式很阴险：
 *
 * | 命令 | app 域的真实表现 | shell 域（uid 2000） |
 * |---|---|---|
 * | `screencap -p x.png` | rc=1，**stderr 完全为空**，留下 0 字节文件 | rc=0，真图 |
 * | `input keyevent 3` | **rc=0 但屏幕纹丝不动** | rc=0，真的生效 |
 * | `pm uninstall x` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
 * | `settings put/get` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
 * | `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
 * | `getevent` | `/dev/input Permission denied` | 能枚举输入设备 |
 * | `dumpsys battery` | rc=**0**，输出 `Can't find service: battery` | 完整状态 |
 * | `wm size` | rc=**20**，`Can't find service: window` | 正常 |
 * | `ps -A` | rc=0，**只有 6 行**（自己 uid 的进程） | 672 行 |
 *
 * 注意 `input` 和 `dumpsys` 两行：**退出码是 0 或空输出**，静默失败。
 * 所以「失败」不能只看退出码，这也是本通道存在的理由 —— 与其事后猜，
 * 不如让模型在需要时**显式选择**特权身份（见 dsh-bash-sandbox 的 patch）。
 *
 * ## 怎么拿到 uid 2000
 *
 * 有**三条**来源，按下面的顺序尝试，前一条走不通就落回后一条：
 *
 * | 顺序 | 通道 | 前提 |
 * |---|---|---|
 * | 0 | **Shizuku** | 设备装了 Shizuku 且已启动、已给我们授权 |
 * | 1 | legacy 5555 | 有人（PC）执行过 `adb tcpip 5555`，且我们的密钥被信任 |
 * | 1.5 | 切 TCP 再走 legacy | 无线调试可用时顺手切回固定端口 |
 * | 2 | 无线调试 | Android 11+，配对过或发现到 `_adb-tls-connect._tcp` |
 * | 3 | 本地执行 | 以上都不行 ⇒ 以 app 身份跑（不是失败，是降级） |
 *
 * **通道 0（Shizuku）不经过 adbd，也不校验 adbkey** —— 它把命令交给原生侧
 * （`ShizukuChannel.kt`）的本地 socket 桥，由原生调 `IShizukuService.newProcess()`
 * 在 Shizuku 服务端（uid 2000）执行。所以它对「Android 10 没有无线调试、
 * `persist.adb.tcp.port` 又写不进去、每次重启都要 PC 重来一次」的处境是**唯一**
 * 免 PC 的出路。socket 路径由 `DSH_SHIZUKU_SOCKET` 给出。
 *
 * ## 通道 1 的密钥从哪来
 *
 * 设备上的 adbd 以 shell 身份（uid 2000）运行，监听 5555。本脚本直连它，
 * 走标准 ADB 协议认证后执行命令。**不需要 adb 二进制，也不需要 ADB server。**
 *
 * ⚠️ **ADB 的签名算法不是标准的 `crypto.sign('sha1', token)`。**
 * AOSP 是手工拼一个 PKCS#1 v1.5 块，把 **token 原文**放进 DigestInfo 的
 * 摘要槽（而不是 token 的 SHA1）：
 *
 * ```
 * 00 01 FF×219 00 ‖ 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14 ‖ token原文
 * ```
 *
 * 用 `crypto.sign('sha1', token, priv)` 会被 adbd 拒绝（表现为反复重发 AUTH
 * token，或转而要公钥）。这一点踩过坑，别改回去。
 *
 * ## 密钥从哪来
 *
 * Android 10 **没有**无线调试配对机制（那是 API 30+），而且 adbd 在
 * **TCP 连接**下**不会**弹「允许 USB 调试吗」对话框 —— 实测发公钥后
 * adbd 直接静默挂起，永远等不到授权。所以本脚本**必须使用一把设备已授权的密钥**。
 *
 * 因此引导流程是：用户用 PC 的 adb（已授权）把私钥推到设备，
 * 本脚本从 `/sdcard/dsh_adbkey` 导入到 `$DSH_HOME/adbkey`。
 *
 * 公钥**从私钥现场派生**，不读 `adbkey.pub`：PC 上那个文件的格式并不标准
 * （实测缺 `ssh-rsa ` 前缀，且与私钥不匹配），派生才是唯一可靠来源。
 *
 * ## 不可用时怎么办
 *
 * 一律**回落为本地执行**（app 域）—— 即「没授权就走普通的」。
 * 回落是静默的、逐条命令的，不会让 bash 工具整体失效。
 *
 * 用法：`node dsh-android-shell.mjs <command>`
 * 退出码与输出原样透传；stderr 混入的通道诊断行统一带 `[dsh-adb] ` 前缀。
 */

import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

// ── ADB 协议常量 ─────────────────────────────────────────────
const CMD = {
  CNXN: 0x4e584e43,
  AUTH: 0x48545541,
  OPEN: 0x4e45504f,
  OKAY: 0x59414b4f,
  WRTE: 0x45545257,
  CLSE: 0x45534c43,
};
const CMD_NAME = (c) => Object.keys(CMD).find((k) => CMD[k] === c) ?? `0x${c.toString(16)}`;

const AUTH_TOKEN = 1;
const AUTH_SIGNATURE = 2;
/**
 * ⚠️ **故意保留但不再发送**：这是 ADB 协议里「把公钥递给设备求授权」的
 * `arg0`，而递公钥是 adbd 弹「允许 USB 调试吗」的**唯一**触发条件。
 * 本 App 已全面停用这条路径（原因见 `handshake()` 的注释），保留常量只为
 * 让协议定义完整、便于排查抓包。**不要「修好」它。**
 */
const AUTH_RSA_PUBLIC = 3;

const MAX_PAYLOAD = 256 * 1024;

/** shell v2 的流 id。退出码靠 EXIT 包透传，v1 协议没有退出码，所以必须用 v2。 */
const SHELL_STDOUT = 1;
const SHELL_STDERR = 2;
const SHELL_EXIT = 3;
const SHELL_CLOSE_STDIN = 4;

const CONNECT_TIMEOUT_MS = 4000;
const HANDSHAKE_TIMEOUT_MS = 8000;
const HOST = '127.0.0.1';
const PORT = 5555;

const TAG = '[dsh-adb]';

/**
 * 内置 adb 的 **server socket 名**（`adb -L localabstract:<name>`）。
 *
 * ## ⚠️ 这里刻意**不带 pid**，与旧实现相反（那是一个真实缺陷）
 *
 * 旧实现是 `localabstract:dshadb-${process.pid}`。而 runner 是**每条特权命令一个
 * 全新 node 进程**（shell 插件的 `execute()` 每次都 spawn），于是：
 *
 * - 每次命令的 socket 名都不同 ⇒ `adb start-server` **每次都新建一个 adb server**；
 * - adb server 是 **daemonize** 的，runner 退出它**不会跟着退**；
 * - 净效果是设备上不断堆积 `@dshadb-<pid>` 进程与 abstract socket，
 *   用一次涨一个，`ss -xln` 越用越长。
 *
 * 改成固定名之后，**我们自己的所有调用共用同一个 server**：第一次起、之后复用，
 * 不再泄漏。这不会与别人抢占 —— `-L` 正是为了避开默认的 `@chrome_devtools_remote`
 * 之类的公共名，而这个名是本 App 专属的。
 *
 * ⚠️ **不要再改回带 pid 的形式**：那样只是把泄漏换个花样，且没有任何好处
 * （adb server 本来就为多客户端并发设计，共享一个 server 是正确的用法）。
 */
const ADB_SOCKET = 'localabstract:dshadb';

/**
 * adbd 重启后等它重新在固定端口上**真的能被认证**的总预算。
 *
 * ⚠️ **旧的 6 秒是错的判据来源**（一个真实的用户症状根因）：`adb tcpip 5555`
 * 会让 adbd **重启**，低端机上超过 6 秒是常态。超时那一刻旧代码返回 `false`，
 * 而内核其实已经切成功了 —— 于是「配对成功但没切到固定端口」这种**误报**
 * 会被写进通知栏，用户去点「切到固定端口」又失败（那条路当时也依赖无线端口），
 * 最终一直靠无线调试顶着，一关 Wi-Fi 权限就掉。
 *
 * 取 20 秒：覆盖 adbd 重启 + 冷启动，仍远小于用户能忍受的一次性等待
 * （这段只在**需要切换**时执行，稳态下第一句校验就返回了）。
 */
const TCPIP_VERIFY_BUDGET_MS = 20_000;

/**
 * 固定端口已经就绪时的**权威判据**：连上去、认证、跑 `id -u`，看它回什么。
 *
 * ⚠️ **不能只判「端口有人监听」**（那是旧实现的第二个错误判据）。
 * 5555 是 >1024 的端口，**任何应用都能 bind**：撞上时旧代码会把别人的监听
 * 当成「已经是 TCP 模式，无需再切」（假成功），随后每条特权命令都去连那个
 * 不是 adbd 的端口 → 静默回落成 app 身份，而设置页显示「已切到固定端口」。
 *
 * 走一次真实握手（自实现协议，**永不发公钥**，所以不会弹授权框）才能把它
 * 与「恰好有人占了 5555」区分开。
 */
const TCPIP_POLL_INTERVAL_MS = 500;

/**
 * 业务跑完之后，留给事件循环自然排空的宽限期；超过就强制退出。
 *
 * ⚠️ **这是一个兜底，不是主逻辑。** 本文件是**一次性** runner：Kotlin 侧
 * `WirelessDebugging.runRunner()` 起它、`proc.waitFor()` 等它结束。
 * 只要有一个 handle 没释放，node 就不退出，而 Kotlin 那边只能靠 90 秒看门狗
 * 强杀 —— 用户看到的就是「通知栏一直显示正在配对」。
 *
 * 已经修掉的两个真实泄漏源（都踩过，见各自注释）：
 *   1. `connect()` 里 `handshake()` 抛异常时那条已连上的 TCP socket 无人关闭；
 *   2. `adbExec()` 的两条 pipe 被 adb 常驻 daemon 继承写端，读端永不 EOF。
 *
 * 但「穷举所有 handle」在 Android 上是不收敛的（本文件的历史就是一连串漏网的
 * handle）。所以再钉一道**与具体 handle 无关**的保险：`main()` 一 settle 就武装
 * 一个 `unref()` 过的定时器 —— 它**不会**自己吊住事件循环（正常情况下进程早退了），
 * 但只要还有任何东西吊着循环，它就会在宽限期后强制收尾。
 *
 * ⚠️ 用 `process.exit()` 在这里是**安全的**，与 `finish()` 里禁用它的理由不冲突：
 *   `finish()` 要保证的是「输出先排干」，而本定时器只在 `main()` 已经 settle
 *   （输出早已写出去）之后才开始计时。宽限期取 5 秒，远大于管道排空所需。
 */
const EXIT_GRACE_MS = 5000;

// ── 无线调试通道（Android 11+）──────────────────────────────
/**
 * Android 11 起有「无线调试」（Wireless debugging），它和 legacy `adb tcpip`
 * 是**两个不同的东西**：
 *
 * | | legacy `adb tcpip 5555` | 无线调试 |
 * |---|---|---|
 * | 端口 | 固定 5555 | **随机高位端口** |
 * | 协议 | 明文 ADB，`A_AUTH` 认证 | **TLS**，连接后先 `A_STLS` |
 * | 怎么开 | PC 执行 `adb tcpip 5555` | 开发选项里勾选 |
 * | 重启后 | **失效**（Android 10 实测 `persist.adb.tcp.port` 为空） | 仍然有效 |
 *
 * 后者才是「免 PC」的关键，但它带来两个新问题，下面分别解决。
 *
 * ## 为什么必须外挂一个 adb 二进制
 *
 * 配对（pairing）走 **SPAKE2 + TLS-PSK**，而 Node 的 `tls` 模块**不支持
 * external PSK**（`tls.createSecureContext` 没有 pskCallback 之外的口子，
 * TLS 1.3 的 external PSK 更是完全没暴露）。自己实现 SPAKE2 等于手写密码学，
 * 不可接受。
 *
 * 所以直接内置 Termux 官方的 `android-tools` 里的 `adb` 二进制 ——
 * 复用 Google 自己的实现。它同时支持 legacy 与无线调试两条路。
 *
 * ## 端口从哪来：Kotlin 侧用 `NsdManager` 发现，写进文件
 *
 * 无线调试的端口是**随机高位端口**，官方发现手段是 mDNS
 * （`_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp`）。
 *
 * 但**本进程自己发不出 UDP 组播**：
 *
 * ```
 * sock.send(q, 0, q.length, 5353, '224.0.0.251')
 *   → EPERM  send EPERM 224.0.0.251:5353
 * ```
 *
 * 绑定 5353、`addMembership` 都成功，只有 `send` 到组播地址被拒（换任意
 * 组播地址、任意绑定端口都一样，是 SELinux 对 untrusted_app 的限制）。
 * 而 Android 的 `mdnsd` 又只答组播查询（单播查询实测无响应）。
 *
 * ⚠️ **不要退回「扫端口」。** 那是最初的实现（扫 32768–60999，实测 20 秒），
 * 已删除。无线调试端口每次重启/重开都会变，等于每条特权命令前都可能干等 20 秒，
 * 而且每次重扫都会耗电。Shizuku 走的是另一条路 ——
 *
 * **`NsdManager` 是系统服务**：请求经 Binder 交给 `mdnsd`（跑在系统域）代发，
 * 完全不受上面那条限制。真机实测（OPPO PHY110 / Android 15）：**81 毫秒**就
 * 拿到端口。所以端口发现放在 Kotlin 侧（`AdbMdns.kt`），结果落到
 * `$DSH_HOME/adb-wireless-port`，本脚本只负责读。
 *
 * 文件格式（纯文本，一行）：
 *
 * ```
 * <connect端口> [<配对端口>]
 * ```
 *
 * 配对端口只在用户打开「使用配对码配对设备」时才有，可能为空。
 *
 * ⚠️ **拿不到文件就说明无线调试没开或没发现** —— 直接回落，不猜、不扫。
 */
const ADB_HOME = process.env.DSH_ADB_HOME ?? defaultAdbHome();
const ADB_BIN = ADB_HOME === '' ? '' : path.join(ADB_HOME, 'bin', 'adb');

/** Kotlin 侧（`AdbMdns`）把发现到的端口写在这里，供本脚本读取。 */
const PORT_CACHE_FILE = () => path.join(homeDir(), 'adb-wireless-port');

// ── ADB 报文封装 ─────────────────────────────────────────────
let CRC_TABLE = null;
function crc32(buf) {
  if (CRC_TABLE === null) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pack(cmd, arg0, arg1, data) {
  const payload = data ?? Buffer.alloc(0);
  const header = Buffer.alloc(24);
  header.writeUInt32LE(cmd, 0);
  header.writeUInt32LE(arg0, 4);
  header.writeUInt32LE(arg1, 8);
  header.writeUInt32LE(payload.length, 12);
  header.writeUInt32LE(crc32(payload), 16);
  header.writeUInt32LE((cmd ^ 0xffffffff) >>> 0, 20);
  return Buffer.concat([header, payload]);
}

const be32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
};
const sshString = (s) => {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([be32(b.length), b]);
};
/** SSH mpint：去前导零，最高位为 1 时补 0x00 防止被当成负数。 */
const mpint = (b) => {
  let i = 0;
  while (i < b.length - 1 && b[i] === 0) i++;
  let x = b.subarray(i);
  if ((x[0] & 0x80) !== 0) x = Buffer.concat([Buffer.from([0]), x]);
  return Buffer.concat([be32(x.length), x]);
};

/** SHA1 的 DigestInfo 前缀（ASN.1 DER），ADB 签名固定用 SHA1。 */
const SHA1_DIGEST_INFO = Buffer.from('3021300906052b0e03021a05000414', 'hex');

// ── 密钥 ─────────────────────────────────────────────────────
function homeDir() {
  return process.env.DSH_HOME ?? path.join(os.homedir(), '.dsh');
}

/**
 * `DSH_ADB_HOME` 缺失时的兜底推导：`<filesDir>/adbwire`。
 *
 * ## 为什么必须有这个兜底
 *
 * 引擎的 `scrubbedParentEnv()`（`@deepseek-ai/dsh-subprocess`）会剥掉子进程 env 里
 * **所有** `DSH_*` 变量，所以插件必须**显式**把要用的每一个都传下来。而
 * `dsh-android-shell` 插件原先只传了 `DSH_HOME`，漏了 `DSH_ADB_HOME` ——
 * 结果是 runner 里 `ADB_BIN` 变成空串，通道 1.5 与通道 2 的门卫
 * （`ADB_BIN !== ''`）双双为假，**无线调试通道整条不存在**，只能靠 legacy 5555；
 * 5555 没在听时就静默回落成 app 身份。
 *
 * 更隐蔽的是：设置页的「重新检测」走的是宿主半插件**自己** spawn（env 里
 * `DSH_ADB_HOME` 完整），于是**探测报告「uid 2000 可用」，而模型执行时进不去
 * 无线通道** —— 同一台设备上两处对同一件事给出相反结论。
 *
 * 插件那次传递已经补上（`dsh-android-shell/lib/index.js`），这里再加一层兜底：
 * 布局是固定的（`$DSH_HOME` = `<filesDir>/home/.dsh`，adb 工具链在
 * `<filesDir>/adbwire`），所以从 `$DSH_HOME` 上溯两级即可，与
 * `dsh-android-mobile/lib/index.js` 的 `runnerPath()` 同一手法。
 * 两者都对时才用（目录真的存在），避免在桌面端瞎猜出一个不存在的路径。
 *
 * @returns {string} adb 工具链目录；推导不出可用目录时给 `''`。
 */
function defaultAdbHome() {
  const candidate = path.resolve(homeDir(), '..', '..', 'adbwire');
  try {
    if (fs.existsSync(path.join(candidate, 'bin', 'adb'))) return candidate;
  } catch {
    /* 读不到当作没有 */
  }
  return '';
}
function keyPath() {
  return path.join(homeDir(), 'adbkey');
}

/**
 * 密钥**来源**的标记文件，内容是 `imported` / `generated` / `paired`。
 *
 * ## 为什么需要它
 *
 * 设置页原来只显示「已导入 / 未导入」，判据是**密钥文件在不在** —— 这是错的：
 * 密钥有两个来源，而其中一个（App 自己生成）在设备看来**尚未被信任**。
 * 两者都显示「已导入」会让用户以为可以用了，实际却拿不到 uid 2000。
 *
 * | 来源 | 设备是否信任 | 用户该做什么 |
 * |---|---|---|
 * | `imported`（从 `/sdcard/dsh_adbkey` 搬进来） | **是**（那是 PC 已授权的密钥） | 直接就能用 |
 * | `generated`（App 现场生成） | **否** | 必须先配对 |
 * | `paired`（生成后又配对成功） | **是** | 直接就能用 |
 *
 * ⚠️ **不能靠 stderr 日志判断**：`importKeyIfNeeded()` 是幂等的，导入日志只在
 * 第一次运行出现，之后再也看不到 —— 用它判断会在第二次探测时误报成「已生成」。
 * 所以来源必须在写入密钥的**那一刻**落盘。
 */
const KEY_ORIGIN_FILE = () => path.join(homeDir(), 'adbkey.origin');

/**
 * 密钥**信任状态**的标记文件：内容是这把密钥公钥指纹的 md5。
 *
 * ## 为什么必须有它（这条是弹窗问题的根治点）
 *
 * ADB 协议里，adbd 只有一条路径会弹「允许 USB 调试吗」：客户端发了
 * `A_AUTH / AUTH_RSA_PUBLIC`（把公钥递给设备求授权）。而原实现是在
 * `handshake()` 里「签名没过就无条件发公钥」—— 于是只要这把密钥没被设备
 * **持久**信任，**每一条命令、每一次连接**都会重弹一次对话框。
 *
 * 用户勾了「一律允许」也没用：TCP 连接下的授权在部分 ROM（实测 OPPO/Android 15）
 * 上不写进持久 keystore，于是下一条命令又是一次全新连接，又弹一次。
 * 用户体感就是「你每执行一次命令我手机就弹一次」。
 *
 * ## 状态机
 *
 * | 状态 | 判据 | 发公钥？ |
 * |---|---|---|
 * | 从未成功过 | 无标记文件 / 指纹不匹配 | **仅当**调用方显式允许（用户主动点「重新检测」或走配对） |
 * | 曾经成功过 | 标记文件里的指纹 == 当前密钥指纹 | **永不**（签名失败即报错回落，不骚扰用户） |
 *
 * ⚠️ 标记里存**指纹**而不是一个裸布尔值：密钥换了（导入 PC 那把、或重新生成）
 * 之后旧标记必须失效，否则新密钥永远拿不到「首次授权」的机会。
 *
 * ⚠️ 指纹算的是**公钥 blob**（与设备 keystore 里存的是同一串），不是私钥文件字节 ——
 * 私钥重新导出（换行符/封装差异）会让字节指纹漂移，公钥不会。
 */
const KEY_TRUST_FILE = () => path.join(homeDir(), 'adbkey.trusted');

/** 公钥 blob 的 md5，用作信任标记的内容。 */
function keyFingerprint(key) {
  return crypto.createHash('md5').update(Buffer.from(key.publicKey, 'base64')).digest('hex');
}

/** 这把密钥是否被设备接受过（曾经握手成功）。 */
function isKeyTrusted(key) {
  try {
    return fs.readFileSync(KEY_TRUST_FILE(), 'utf8').trim() === keyFingerprint(key);
  } catch {
    return false;
  }
}

/** 记下「这把密钥已被设备接受」。写不进去只是下次可能多弹一次，不影响功能。 */
function markKeyTrusted(key) {
  try {
    fs.writeFileSync(KEY_TRUST_FILE(), keyFingerprint(key), { mode: 0o600 });
  } catch {
    // 忽略
  }
}

/** 密钥被替换时必须清掉旧信任标记，否则新密钥拿不到首次授权机会。 */
function clearKeyTrust() {
  try {
    fs.unlinkSync(KEY_TRUST_FILE());
  } catch {
    // 本来就没有
  }
}

/**
 * 设备**已经明确拒绝过**这把密钥的标记文件（内容是公钥指纹，与信任标记同格式）。
 *
 * ## 为什么必须有它（否则「删掉发公钥」那道闸会被绕过）
 *
 * `keyMayBeAuthorized()` 对 `origin === 'imported' | 'paired'` 是**无条件放行**的，
 * 依据是「设备曾经认得这把钥匙」。但设备端的授权**是会失效的**：
 *
 * - 开发者选项里点「撤销 USB 调试授权」；
 * - 换 ROM / 恢复出厂 / 清 `/data/misc/adb/adb_keys`；
 * - 系统在某些升级路径上重建 keystore。
 *
 * 失效之后那个 `origin` 标记**依然在**，于是：
 *
 * 1. `keyMayBeAuthorized()` 仍返回 `true`；
 * 2. 通道 1.5 与通道 2 **双双放行**，去用**内置 adb**（一个我们拦不住的外部
 *    进程）连无线调试端口；
 * 3. 内置 adb 遇到未授权密钥时**自己会发 `AUTH_RSA_PUBLIC`** —— 那是 adbd
 *    弹「允许 USB 调试吗」的唯一触发条件；
 * 4. 用户**每执行一条特权命令就弹一次授权框**。
 *
 * 这正是 AGENTS.md ⑪ 记下的那个「本项目最严重的设计错误」，只是被 `origin`
 * 的短路重新引了回来。握手失败时把这件事**记下来**，`keyMayBeAuthorized()`
 * 才能据此收手。
 *
 * ⚠️ 只记「设备拒绝了这把密钥」这个**确定事实**（握手时 `A_AUTH` 再来一次），
 * 不记「连接失败」「超时」—— 那些是网络/端口问题，不是授权问题，记了会误伤。
 */
const KEY_REVOKED_FILE = () => path.join(homeDir(), 'adbkey.revoked');

/** 这把密钥是否已被设备明确拒绝过。 */
function isKeyRevoked(key) {
  try {
    return fs.readFileSync(KEY_REVOKED_FILE(), 'utf8').trim() === keyFingerprint(key);
  } catch {
    return false;
  }
}

/** 记下「设备拒绝了这把密钥」。 */
function markKeyRevoked(key) {
  try {
    fs.writeFileSync(KEY_REVOKED_FILE(), keyFingerprint(key), { mode: 0o600 });
  } catch {
    // 写不进去只是下次可能多弹一次，不影响功能
  }
}

/** 密钥重新可用（握手成功 / 重新配对 / 换了一把）时清掉撤销标记。 */
function clearKeyRevoked() {
  try {
    fs.unlinkSync(KEY_REVOKED_FILE());
  } catch {
    // 本来就没有
  }
}

/**
 * 读现有密钥**但不触发导入/生成**。
 *
 * ⚠️ **不能改用 `loadKey()`**：它内部会调 `importKeyIfNeeded()`，而本函数的调用方
 * 正是 `importKeyIfNeeded()` —— 那会变成无限递归（栈溢出，且症状是启动即崩、
 * 看不出与密钥有关）。这里只做「读 + 解析」，与 `loadKey()` 的区别仅在于此。
 *
 * @returns {{priv: unknown, publicKey: string}|null} 解析不出来时给 `null`。
 */
function readKeyFileNoImport() {
  const file = keyPath();
  if (!fs.existsSync(file)) return null;
  try {
    const priv = crypto.createPrivateKey(fs.readFileSync(file));
    return { priv, publicKey: publicKeyBlob(priv) };
  } catch {
    return null;
  }
}

/**
 * `/sdcard/dsh_adbkey` 是否比现有密钥**更新**（= 用户刚推了一遍）。
 *
 * ⚠️ 判据用 mtime 而不是内容：内容相同时也有两种完全不同的处境，见
 * `importKeyIfNeeded()` 里那段注释。容差 2 秒，避免 FAT/FUSE 的时间戳粒度
 * （实测 `/sdcard` 上是 1 秒级）把「同一次操作」判成「新推的」。
 *
 * ⚠️ 取不到时间戳时一律返回 `false`（= 当作「不是新推的」）。方向选保守的那边：
 * 误判成「新推的」会让每次启动都放行一次可能弹授权框的通道。
 */
function isStagedNewerThanDest(staged, dest) {
  try {
    return fs.statSync(staged).mtimeMs > fs.statSync(dest).mtimeMs + 2000;
  } catch {
    return false;
  }
}

/**
 * 记下密钥来源。写不进去不影响功能，只是设置页少一条提示。
 */
function markKeyOrigin(origin) {
  try {
    fs.writeFileSync(KEY_ORIGIN_FILE(), origin, { mode: 0o600 });
  } catch {
    // 忽略
  }
}

/**
 * 读密钥来源；密钥不存在或来源未知时给 `null`。
 * @returns {'imported'|'generated'|'paired'|null}
 */
function keyOrigin() {
  if (!fs.existsSync(keyPath())) return null;
  try {
    const raw = fs.readFileSync(KEY_ORIGIN_FILE(), 'utf8').trim();
    return raw === 'imported' || raw === 'generated' || raw === 'paired' ? raw : null;
  } catch {
    return null;
  }
}

/**
 * 把 `/sdcard/dsh_adbkey` 里的密钥搬进 `$DSH_HOME`。
 *
 * 用户的引导命令是
 * `adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey`。
 *
 * ## 判据不是「有没有密钥」，而是「在的那把设备信不信」
 *
 * ⚠️ 这里**不能**写成「目标已存在就跳过」。App 自己生成的密钥
 * （`origin = generated`）**设备并不信任** —— 拿它连 adbd 会弹
 * 「是否允许 USB 调试？」，而且用户点「始终允许」**也没用**：那把公钥本来就不在
 * 设备的 `adb_keys` 里，系统每次都当新密钥问一遍。
 *
 * 后果是一个死循环：用户按引导推了 PC 密钥上去，但导入逻辑看到「已有密钥」就
 * 直接返回，App 于是永远拿着自己那把不受信任的密钥去连、永远弹窗、永远点不掉。
 * （真机撞到过，用户反馈「点了很多次始终允许都没效果」。）
 *
 * 所以规则是：**只有 `generated` 允许被顶掉**。
 * `imported` / `paired` 是设备已经信任的密钥，照旧不动。
 *
 * ⚠️ **导入后立刻删掉中转文件。** `/sdcard` 是所有应用都能读的共享目录
 * （本机实测该文件是 `-rw-rw---- root sdcard_rw`），ADB 私钥留在那里
 * 等于把设备控制权敞开放着。实测 app 域**有权**删除它，所以这步是可行的。
 */
function importKeyIfNeeded() {
  const dest = keyPath();
  const staged = '/sdcard/dsh_adbkey';
  // ⚠️ 判据是「有没有一把**设备可能还认**的密钥」，不是「有没有密钥」。有三种情况
  //    必须继续往下看 `/sdcard/dsh_adbkey`：
  //
  //    ① `origin === 'generated'` —— App 自己生成的，设备从来没认过（见下面的长注释）。
  //    ② **已被设备撤销** —— 设备已经不认这把了，重新推一把正是它的补救手段。
  //    ③ **文件损坏 / 解析不出来** —— 同样只能靠重新推一把救回来。
  //
  //    ⚠️⚠️ ② 这条是**必需的**，否则 Android 10 上会出现一条**死锁**：
  //    设备端点了「撤销 USB 调试授权」⇒ `markKeyRevoked()` 记上标记 ⇒
  //    `keyMayBeAuthorized()` 挡掉通道 1.5 / 2 ⇒ 而 Android 10 **没有无线调试、
  //    配对那条路根本走不通**（配对要 Android 11+）⇒ 用户唯一能做的补救动作
  //    （在 PC 上重新授权后 `adb push` 一把）会被早返回吞掉，于是
  //    **永远**回落到 app 身份，怎么推都没反应。
  //
  //    ⚠️ 但 ②③ 只在**确实有中转文件**时才需要判断，所以判据分两层：
  //    没有 `/sdcard/dsh_adbkey` 时（绝大多数启动）连私钥都不解析，直接返回 ——
  //    这是每条 `shell` 命令都会经过的路径（`connect()` → `loadKey()` → 这里），
  //    不该白白多做一次 RSA 解析。
  //
  //    ⚠️ 这里**不能**调 `loadKey()` —— 它内部会调回本函数，直接无限递归。
  //       所以用不做导入/生成的 `readKeyFileNoImport()`。
  if (fs.existsSync(dest) && keyOrigin() !== 'generated') {
    // 没有中转文件 ⇒ 下面无论如何都会返回，不必判断现有密钥可不可用。
    if (!fs.existsSync(staged)) return;
    const existing = readKeyFileNoImport();
    // 解析不出来（损坏）与已被撤销同样算「不可用」，都允许被顶掉。
    // 下面会用 `crypto.createPrivateKey(pem)` 先验证新密钥，失败就什么都不做，无副作用。
    if (existing !== null && !isKeyRevoked(existing)) return;
  }
  try {
    if (!fs.existsSync(staged)) return;
    const pem = fs.readFileSync(staged);
    // 先验证能解析再落盘，避免把半个文件写进去后永久卡住导入
    crypto.createPrivateKey(pem);
    if (fs.existsSync(dest)) {
      // 内容一模一样说明目标本来就是 PC 那把（只可能是上次导入后 origin 没写上）。
      let same = false;
      try {
        same = fs.readFileSync(dest).equals(pem);
      } catch {
        // 读不出来就照常覆盖
      }
      if (same) {
        markKeyOrigin('imported');
        // ⚠️ 内容相同也有两种情形，**必须分开**：
        //    ① 上次导入后 `unlinkSync(staged)` 没成功（比如没有存储权限），文件
        //       一直躺在那里 —— 这不是用户的新动作，**不能**清撤销标记，否则每次
        //       启动都会放行一次「会弹授权框」的通道。
        //    ② 用户刚刚重新推了一遍（设备撤销授权后，他在 PC 上重新授权、再把同一把
        //       密钥推过来）—— 这是**明确的重新授权信号**，必须清标记，否则上面那条
        //       Android 10 死锁依然存在。
        //    判据用 mtime：staged 比 dest 新 ⇒ 是刚推的。
        if (isStagedNewerThanDest(staged, dest)) {
          clearKeyRevoked();
          process.stderr.write(
            `${TAG} 检测到重新推入的密钥（比现有新）—— 已清除「授权被撤销」标记，重新尝试\n`,
          );
        }
        return;
      }
      process.stderr.write(`${TAG} 已存在的密钥不受设备信任（generated），改用 PC 推来的那把\n`);
    }
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, pem, { mode: 0o600 });
    markKeyOrigin('imported');
    // 密钥换了，旧的信任标记随之作废：新密钥需要一次「首次授权」的机会。
    clearKeyTrust();
    // ⚠️ 撤销标记同理必须清掉。它记的是**上一把**密钥被拒的事实；不清的话
    //    这把新导入的（设备本就信任的）密钥会被那道闸挡在通道 1.5 / 2 之外，
    //    表现为「明明导了 PC 密钥却一直回落成 app 身份」。
    clearKeyRevoked();
    process.stderr.write(`${TAG} 已导入 ADB 密钥: ${staged} → ${dest}\n`);
    try {
      fs.unlinkSync(staged);
      process.stderr.write(`${TAG} 已删除中转密钥 ${staged}（共享目录不留私钥）\n`);
    } catch (e) {
      process.stderr.write(`${TAG} ⚠️ 无法删除中转密钥 ${staged}: ${e.message}，请手动删除\n`);
    }
  } catch (e) {
    process.stderr.write(`${TAG} 导入密钥失败: ${e.message}\n`);
  }
}

/**
 * 由私钥导出 ADB 公钥 blob（`ssh-rsa` + e + n 的 SSH 线格式）。
 *
 * ⚠️ **必须现场派生，不要读 `adbkey.pub`**：实测本机那个文件格式不标准
 * （716 字节、缺 `ssh-rsa ` 前缀、内容与私钥不匹配），而设备 keystore 里存的
 * 是公钥 blob。派生才是唯一可靠来源。
 *
 * @param {unknown} priv `crypto.createPrivateKey()` 的产物。
 * @returns {string} base64 的公钥 blob（不带 `ssh-rsa ` 前缀）。
 */
function publicKeyBlob(priv) {
  const jwk = crypto.createPublicKey(priv).export({ format: 'jwk' });
  return Buffer.concat([
    sshString('ssh-rsa'),
    mpint(Buffer.from(jwk.e, 'base64url')),
    mpint(Buffer.from(jwk.n, 'base64url')),
  ]).toString('base64');
}

function loadKey() {
  importKeyIfNeeded();
  const file = keyPath();
  if (!fs.existsSync(file)) return null;
  try {
    const priv = crypto.createPrivateKey(fs.readFileSync(file));
    return { priv, publicKey: publicKeyBlob(priv) };
  } catch (e) {
    process.stderr.write(`${TAG} 解析密钥失败: ${e.message}\n`);
    return null;
  }
}

/**
 * 当前这把密钥**是否有可能**已被设备授权（用于「能不能进内置 adb 那条通道」的守卫）。
 *
 * 与 `isKeyTrusted()` 的区别：这个**不导入、不生成**，纯读盘判断，无副作用，
 * 所以可以在通道选择之前当守卫用。
 *
 * ## 为什么需要这个守卫
 *
 * 内置 adb（通道 2 与 1.5）是**外部进程**，它遇到未授权的密钥时**自己会弹**
 * 「允许 USB 调试吗」，我们拦不住它的 AUTH 行为。所以只能在**进入那条通道之前**
 * 就挡住 —— 宁可回落成 app 身份，也不去弹用户的窗。
 *
 * ## 判据为什么不是「有信任标记」
 *
 * 只认标记会误伤：一把刚从 PC 导入（`imported`）、或刚配对成功（`paired`）的密钥，
 * 在**第一次真正连上之前**是没有标记的 —— 而它恰恰是设备**已经知道**的密钥，
 * 用它连接根本不会弹窗。若因此把内置 adb 挡掉，用户会在「5555 没在听、只有
 * 无线调试可用」时永远拿不到特权通道，那是个死锁。
 *
 * 所以判据是「设备**已经知道**这把密钥」，三条任一条成立即可：
 *
 * | 条件 | 依据 |
 * |---|---|
 * | 信任标记匹配 | 我们亲手握手成功过 |
 * | `origin = imported` | 它来自 PC 的 adbkey，设备 keystore 里本来就有 |
 * | `origin = paired` | 配对时设备把我们的公钥写进了 keystore |
 *
 * 唯一被挡住的是 `origin = generated` 且从未配对成功的密钥 —— 那正是会反复弹窗的那把。
 *
 * @returns {boolean}
 */
function keyMayBeAuthorized() {
  const file = keyPath();
  if (!fs.existsSync(file)) return false;
  let priv;
  try {
    priv = crypto.createPrivateKey(fs.readFileSync(file));
  } catch {
    return false;
  }
  const key = { publicKey: publicKeyBlob(priv) };
  // ⚠️ **这一句必须排在所有放行判据之前。** 设备已经明确拒绝过这把密钥时，
  //    再进「会弹授权框」的通道（通道 1.5 / 通道 2 用的是**内置 adb**，
  //    它自己会发公钥，我们拦不住）只会让用户每条命令都弹一次窗。
  //    宁可回落成 app 身份，也不要骚扰用户。详见 markKeyRevoked()。
  if (isKeyRevoked(key)) return false;
  const origin = keyOrigin();
  if (origin === 'imported' || origin === 'paired') return true;
  return isKeyTrusted(key);
}

/**
 * 没有密钥就**自己生成一把**。
 *
 * ## 为什么必须自己生成
 *
 * 「从 PC 导入密钥」那套是 Android 10 的无奈之举（没有无线调试配对，只能复用
 * 一把设备已授权的密钥）。而 Android 11+ 的**配对机制本身就是用来授权新密钥的** ——
 * 用户看到 6 位配对码，输进来，设备就把**我们这把**公钥写进 keystore。
 *
 * 所以正确的免 PC 流程是：**自己生成密钥 → 用配对码授权它**，而不是继续找 PC 要。
 * 不生成的话，配对会因为「没有密钥」直接失败，无线调试又退化成需要 PC 引导。
 *
 * ## 与 PC 导入的关系
 *
 * 顺序是「先导入、后生成」：`/sdcard/dsh_adbkey` 存在时仍优先用它（那是设备
 * **已经信任**的密钥，导入后 legacy 5555 通道立刻可用，不用配对）。只有两处都
 * 没有时才生成 —— 也就是全新的、只开了无线调试的设备。
 *
 * ⚠️ 生成的密钥**在配对之前是不被设备信任的**，所以：
 * - legacy 5555 通道会认证失败（设备 keystore 里没有它）→ 正常回落，不是 bug；
 * - 必须先走一次 `--pair`，之后无线通道才能用。
 *
 * ⚠️ 用 PKCS#8（`-----BEGIN PRIVATE KEY-----`）而不是 PKCS#1：实测设备上那把
 * PC 密钥也是 PKCS#8，且 Node 的 `createPrivateKey` 两者都认。
 *
 * @returns {boolean} 现在是否有一把可用的密钥。
 */
function ensureKey() {
  const file = keyPath();
  // 先把 PC 中转的那把收进来 —— 那是设备**已经信任**的密钥，能直接用，
  // 不该被新生成的顶掉。importKeyIfNeeded 自身幂等（已有就不动）。
  importKeyIfNeeded();
  if (fs.existsSync(file)) return true;
  try {
    const { privateKey } = crypto.generateKeyPairSync('rsa', {
      modulusLength: 2048,
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, privateKey, { mode: 0o600 });
    markKeyOrigin('generated');
    process.stderr.write(`${TAG} 已生成新的 ADB 密钥（等待用配对码授权）: ${file}\n`);
    return true;
  } catch (e) {
    process.stderr.write(`${TAG} 生成 ADB 密钥失败: ${e.message}\n`);
    return false;
  }
}

/**
 * ADB 签名：`RSA_NO_PADDING` 下手工构造 PKCS#1 v1.5 块。
 * ⚠️ 摘要槽里放的是 **token 原文**，不是 `sha1(token)`。见文件头注释。
 */
function signToken(token, priv) {
  const size = (crypto.createPublicKey(priv).asymmetricKeyDetails?.modulusLength ?? 2048) / 8;
  const padLen = size - 3 - SHA1_DIGEST_INFO.length - token.length;
  if (padLen < 8) throw new Error(`token 过长，无法装进 ${size} 字节的 RSA 块`);
  const block = Buffer.concat([
    Buffer.from([0x00, 0x01]),
    Buffer.alloc(padLen, 0xff),
    Buffer.from([0x00]),
    SHA1_DIGEST_INFO,
    token,
  ]);
  return crypto.privateEncrypt({ key: priv, padding: crypto.constants.RSA_NO_PADDING }, block);
}

// ── 连接 ─────────────────────────────────────────────────────
/**
 * 建立一条已认证的 ADB 连接。失败抛异常（调用方负责回落）。
 *
 * ⚠️ 这条路径**永不**向设备递公钥，所以**永不弹**「允许 USB 调试吗」。
 * 密钥的授权只能来自「无线调试配对」或「PC 推入已授权密钥」，见 `handshake()`。
 *
 * @returns {Promise<AdbConn>}
 */
async function connect({ probe = false } = {}) {
  const key = loadKey();
  if (key === null) throw new Error('未找到 ADB 密钥（先用 PC 执行 adb push 导入）');

  const sock = await new Promise((resolve, reject) => {
    const s = net.connect({ host: HOST, port: PORT });
    const timer = setTimeout(() => {
      s.destroy();
      reject(new Error(`连接 ${HOST}:${PORT} 超时`));
    }, CONNECT_TIMEOUT_MS);
    s.once('connect', () => {
      clearTimeout(timer);
      resolve(s);
    });
    s.once('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });

  // ⚠️⚠️ **握手失败必须显式销毁这条 socket —— 这是「通知栏一直正在配对」的根因之一。**
  //
  //   真机症状（OPPO / Android 15，17:58 那一次）：adb 明明报了
  //   `Successfully paired`、runner 也把 `[dsh-adb] 无法切到固定端口：密钥尚未被
  //   设备授权…` 打了出来，**但 node 进程迟迟不退出**（45 秒后 `ps` 里两个 node
  //   仍活着），于是 Kotlin 的 `proc.waitFor()` 等不到，通知栏卡在「正在配对…」，
  //   最后只能靠 90 秒看门狗强杀。
  //
  //   链路：`switchToTcp()` 第一句就是 `fixedPortWorks()`，而它内部 `connect()`
  //   要跟 adbd 真握手一次 —— 刚配对完那会儿密钥还没被持久信任，`handshake()`
  //   抛「ADB 密钥尚未被设备授权」。**异常从 `connect()` 里抛出时，上面那条
  //   已经连上的 TCP socket 谁也没关**：
  //
  //     const conn = new AdbConn(sock);   // socket 已被包装、监听器已注册
  //     await conn.handshake(key);        // ← 在这里抛
  //     return conn;                      // ← 永远到不了，conn 从未被赋给调用方
  //
  //   调用方 `fixedPortWorks()` 是 `conn = await connect()` —— 抛异常时这个赋值
  //   根本不发生，`conn` 保持 `null`，于是它 `finally { conn?.close() }` 成了空操作。
  //   而 `net.connect()` 出来的 socket 是 **ref'd** 的活跃 handle，adbd 那边也不会
  //   主动断开（它只是拒绝认证，等着我们重试），于是事件循环永远不空 ⇒
  //   **node 永不退出**。
  //
  //   ⚠️ 不要试图靠 `AdbConn` 里那个 `sock.on('close', ...)` 兜底：那条路要求
  //   对端先关，而这里对端恰恰不关。必须在**抛出的这一侧**主动销毁。
  //
  //   ⚠️ 这里用 `destroy()` 而不是 `unref()`：socket 已经被 `AdbConn` 接管，
  //      它是我们唯一持有的引用，销毁它是正确且彻底的（与 `adbExec` 的 pipe 不同 ——
  //      那条路下面那个 fd 仍被 libuv 算作活跃，只能 `unref`，见那里的实测对照表）。
  let conn;
  try {
    conn = new AdbConn(sock);
    // ⚠️ `probe` 透传给 `handshake()`：探测性握手**不许**写「授权已撤销」标记，
    //    理由见 `handshake()` 的 `recordRevocation` 参数。
    await conn.handshake(key, { recordRevocation: !probe });
  } catch (e) {
    try {
      sock.destroy();
    } catch {
      // 已经断了，忽略
    }
    throw e;
  }
  return conn;
}

class AdbConn {
  #sock;
  #buf = Buffer.alloc(0);
  #queue = [];
  #waiter = null;
  #closed = false;
  /** 每个本地 stream id 一个消息队列，ADB 允许同一连接上并发多个 stream。 */
  #streams = new Map();

  constructor(sock) {
    this.#sock = sock;
    sock.on('data', (d) => this.#feed(d));
    sock.on('error', () => this.#die());
    sock.on('close', () => this.#die());
  }

  #die() {
    this.#closed = true;
    for (const q of this.#streams.values()) q.fail(new Error('ADB 连接已关闭'));
    this.#streams.clear();
    if (this.#waiter !== null) {
      const w = this.#waiter;
      this.#waiter = null;
      w.fail(new Error('ADB 连接已关闭'));
    }
  }

  #feed(chunk) {
    this.#buf = Buffer.concat([this.#buf, chunk]);
    while (this.#buf.length >= 24) {
      const cmd = this.#buf.readUInt32LE(0);
      const arg0 = this.#buf.readUInt32LE(4);
      const arg1 = this.#buf.readUInt32LE(8);
      const len = this.#buf.readUInt32LE(12);
      if (this.#buf.length < 24 + len) break;
      const data = this.#buf.subarray(24, 24 + len);
      this.#buf = this.#buf.subarray(24 + len);

      const stream = this.#streams.get(arg1);
      if (stream !== undefined && (cmd === CMD.WRTE || cmd === CMD.CLSE || cmd === CMD.OKAY)) {
        stream.push({ cmd, arg0, arg1, data });
      } else if (this.#waiter !== null) {
        const w = this.#waiter;
        this.#waiter = null;
        w.push({ cmd, arg0, arg1, data });
      }
    }
  }

  /** 读一条非 stream 报文（握手/OPEN 应答）。 */
  recv(timeoutMs) {
    return new Promise((resolve, reject) => {
      if (this.#closed) return reject(new Error('ADB 连接已关闭'));
      if (this.#queue.length > 0) return resolve(this.#queue.shift());
      const timer = setTimeout(() => {
        if (this.#waiter === entry) this.#waiter = null;
        reject(new Error(`等待 ADB 报文超时（${timeoutMs}ms）`));
      }, timeoutMs);
      const entry = {
        push: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
        fail: (e) => {
          clearTimeout(timer);
          reject(e);
        },
      };
      this.#waiter = entry;
    });
  }

  send(cmd, arg0, arg1, data) {
    if (this.#closed) throw new Error('ADB 连接已关闭');
    this.#sock.write(pack(cmd, arg0, arg1, data));
  }

  /**
   * 握手 + 认证。
   *
   * ## ⚠️ 这里曾经是「每执行一条命令手机就弹一次授权窗」的根因
   *
   * 原实现：签名验证失败 → 发 `AUTH_RSA_PUBLIC`（把公钥递给设备求授权）。
   * 而 adbd 只有收到公钥才会弹「允许 USB 调试吗」，于是密钥只要不被设备
   * **持久**信任，每一次连接（= 每一条命令、每次探测）都会弹一次。
   *
   * ## 为什么是**删掉**而不是「降低触发频率」
   *
   * 递公钥在**本 App 的全部场景下都是无效动作**，两类设备都实测过：
   *
   * | 设备 | 递公钥之后 |
   * |---|---|
   * | Android 10（TCP 连接） | adbd **不弹对话框**，静默挂起，永远等不到授权（20s 超时无任何响应） |
   * | Android 15（TCP 连接） | 弹窗，但点「一律允许」**不写进持久 keystore**，下一条命令又弹 |
   *
   * 也就是说它要么什么都不做，要么只制造骚扰。所以**直接不发**：签名失败就报错，
   * 让调用方回落成 app 身份。
   *
   * ## 密钥怎么获得信任（两条路都不弹窗）
   *
   * 1. **Android 11+**：设置页走「无线调试配对」，输 6 位配对码 —— 设备把我们的
   *    公钥写进 keystore，这正是配对机制的本来用途。见 `--pair` 分支。
   * 2. **Android 10**：PC 上 `adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey`，
   *    那是**设备已经信任**的密钥，导入即可用。见 `importKeyIfNeeded()`。
   *
   * @param {{priv: unknown, publicKey: string}} key
   * @param {{recordRevocation?: boolean}} [opts]
   *   `recordRevocation=false` 表示这是**探测性**握手（只想知道「这条通道通不通」），
   *   即使被设备拒绝也**不许**写下「授权已撤销」标记。理由见下面的 ⚠️⚠️。
   */
  async handshake(key, { recordRevocation = true } = {}) {
    this.send(CMD.CNXN, 0x01000001, MAX_PAYLOAD, Buffer.from('host::features=shell_v2,cmd,stat_v2\0'));
    let msg = await this.recv(HANDSHAKE_TIMEOUT_MS);

    if (msg.cmd === CMD.AUTH && msg.arg0 === AUTH_TOKEN) {
      this.send(CMD.AUTH, AUTH_SIGNATURE, 0, signToken(msg.data, key.priv));
      msg = await this.recv(HANDSHAKE_TIMEOUT_MS);
      if (msg.cmd === CMD.AUTH) {
        // ⚠️ 这里**故意不发公钥**。原因见上面的表 —— 它在本 App 的场景下要么
        // 无效、要么只制造弹窗，是纯粹的负收益。要授权请走配对或 PC 导入。
        //
        // ⚠️ **同时记下「设备拒绝了这把密钥」**：这是唯一能区分「授权被撤销」与
        //   「端口/网络问题」的信号（adbd 又发了一次 `A_AUTH` 就是要我们重新证明
        //   身份）。不记的话，`origin = imported|paired` 会让 `keyMayBeAuthorized()`
        //   永远放行，于是内置 adb 在通道 1.5 / 2 里握着这把不受信的钥匙去连，
        //   **它自己会发公钥** ⇒ 用户每执行一条命令弹一次授权框。
        //
        // ⚠️⚠️ **但不是所有被拒都算「撤销」，只在「本来会被放行」时才记。**
        //   对 `generated` 且从未配对成功的密钥，被设备拒绝是**必然且正常**的 ——
        //   全新的、只开了无线调试的设备就长这样，`keyMayBeAuthorized()` 本来就会
        //   挡它（走 `isKeyTrusted()` 那条返回 false）。若这时也记上 revoked：
        //
        //   - `classifyFallback()` 会把结论从正确的「尚未授权，请去配对」改成
        //     **「授权已被撤销，多半是在开发者选项里点了『撤销 USB 调试授权』」**——
        //     用户从没做过那个动作，被引向错误的方向；
        //   - 还多叠一道**只有配对/重新导入才能解除**的闸（见 `clearKeyRevoked()`
        //     的两个调用点），而原本那条路只需一次成功握手就能自愈。
        //
        //   判据与 `keyMayBeAuthorized()` 的放行条件**逐条对齐**：只有这三种情况
        //   才是「从可放行变成被拒」，也就是真的撤销。
        //
        // ⚠️⚠️ **探测性握手（`recordRevocation=false`）绝不写这个标记 —— 真机实证的坑。**
        //
        //   真机日志（OPPO / Android 15，17:58 那一次）逐行：
        //
        //     17:58:28.068 [pair] Successfully paired to 127.0.0.1:42549
        //     17:58:28.127 [pair] 无法切到固定端口：密钥尚未被设备授权，跳过…
        //                      ↑ 只隔了 59ms
        //
        //   这 59ms 里发生的事：`--pair` 成功后立刻调 `switchToTcp()`，而它的**第一句**
        //   就是 `fixedPortWorks()` —— 那是一次**探测性**握手，目的是「5555 是不是
        //   已经能给我 uid 2000」。此刻 adbd 刚收到配对、keystore 还没重载完，
        //   于是它拒绝了这次签名。**探测失败本来只是一句「还不行」，但旧代码把它
        //   当成权威结论写下了 `markKeyRevoked()`**，而这个标记紧接着就挡住了
        //   `switchToTcp()` 自己后面的 `keyMayBeAuthorized()`：
        //
        //     探测失败 → 写「已撤销」→ keyMayBeAuthorized() 读到它 → 返回 false
        //     → 报「密钥尚未被设备授权，跳过以免反复弹窗」→ 放弃切固定端口
        //
        //   结果就是「**刚配对成功，却立刻宣称密钥未授权**」，而用户回到设置页点
        //   「重新检测」时又好了（那时 adbd 早已重载完 keystore，探测通过）。
        //   这正是用户反馈的原话：「第一次匹配就是一直显示配对中，但是回到 APP
        //   里面点重新检测，其实它是已经就绪了的。」
        //
        //   为什么「探测失败」不配当权威结论：
        //   - 它是一次**瞬时**观测，而 keystore 重载、adbd 重启、端口刚监听上
        //     这些过渡态都会让它假失败；
        //   - 「授权已撤销」是个**很重**的结论 —— 它会叠加一道只有重新配对或重新
        //     导入才能解除的闸（`clearKeyRevoked()` 的两个调用点），用户被引向
        //     「去开发者选项看看是不是撤销了授权」这个他从没做过的动作。
        //
        //   所以：**只有「真的要执行特权命令、并且已经确认这把密钥本应可用」时
        //   才允许记撤销**。`fixedPortWorks()` 是纯探测，传 `probe: true`。
        const origin = keyOrigin();
        if (recordRevocation && (origin === 'imported' || origin === 'paired' || isKeyTrusted(key))) {
          markKeyRevoked(key);
        }
        throw new Error(
          isKeyTrusted(key)
            ? 'ADB 密钥被设备拒绝（授权已被撤销），请重新配对授权。' +
              '在此之前特权命令会以应用身份执行。'
            : 'ADB 密钥尚未被设备授权，请用无线调试配对授权它'
              + '（Android 10 需先用 PC 执行 adb push 推入已授权的密钥）',
        );
      }
    }

    if (msg.cmd !== CMD.CNXN) {
      throw new Error(`ADB 认证失败（收到 ${CMD_NAME(msg.cmd)} arg0=${msg.arg0}）`);
    }
    // 走到这里说明设备认了这把密钥。落盘标记，此后永不因它弹窗。
    markKeyTrusted(key);
    // 设备重新认了 ⇒ 之前那次「授权被撤销」的结论已经过期，清掉它，
    // 否则通道 1.5 / 通道 2 会一直被那道闸挡着（表现为「明明能用却总是回落」）。
    clearKeyRevoked();
  }

  /**
   * 用 shell v2 协议执行一条命令，拿回分开的 stdout / stderr / 退出码。
   * @returns {Promise<{stdout: Buffer, stderr: Buffer, exitCode: number}>}
   */
  async shell(command, { timeoutMs } = {}) {
    const localId = this.#nextId();
    const q = new StreamQueue();
    this.#streams.set(localId, q);

    try {
      // raw: 表示不做 PTY 分配，命令原文直传；v2 才带 EXIT 包
      this.send(CMD.OPEN, localId, 0, Buffer.from(`shell,v2,raw:${command}\0`));
      const opened = await q.next(timeoutMs ?? 15000);
      if (opened.cmd !== CMD.OKAY) {
        throw new Error(`adbd 拒绝打开 shell 流（${CMD_NAME(opened.cmd)}）`);
      }
      const remoteId = opened.arg0;

      const stdout = [];
      const stderr = [];
      let exitCode = 0;
      let pending = Buffer.alloc(0);

      // 一个 WRTE 里可能装多个 v2 包，也可能只装半个 —— 按流式解析，跨报文续上
      const consume = (chunk) => {
        pending = Buffer.concat([pending, chunk]);
        for (;;) {
          if (pending.length < 5) break;
          const id = pending[0];
          const len = pending.readUInt32LE(1);
          if (pending.length < 5 + len) break;
          const payload = pending.subarray(5, 5 + len);
          pending = pending.subarray(5 + len);
          if (id === SHELL_STDOUT) stdout.push(payload);
          else if (id === SHELL_STDERR) stderr.push(payload);
          else if (id === SHELL_EXIT) exitCode = payload.length > 0 ? payload[0] : 0;
          else if (id === SHELL_CLOSE_STDIN) { /* 无需回写 stdin */ }
        }
      };

      let sawClose = false;
      for (;;) {
        if (sawClose && q.isEmpty) break;
        const m = sawClose ? q.tryNext() : await q.next(timeoutMs ?? 0);        if (m === null) break;
        if (m.cmd === CMD.CLSE) {
          sawClose = true;
          continue;
        }
        if (m.cmd !== CMD.WRTE) continue;

        consume(m.data);
        // ⚠️ 必须回 OKAY：ADB 是窗口式流控，读完一个 WRTE 不回执，adbd 会**停在那里
        // 不再发下一个包**（表现为命令挂住、永远等不到 CLSE）。Dadb 的
        // AdbStreamImpl.read() 也是读完即 `writeOkay(localId, remoteId)`。
        this.send(CMD.OKAY, localId, remoteId, Buffer.alloc(0));
      }

      // 回执 CLSE，让 adbd 释放这条流
      try {
        this.send(CMD.CLSE, localId, remoteId, Buffer.alloc(0));
      } catch { /* 连接可能已经断了，忽略 */ }

      return {
        stdout: Buffer.concat(stdout),
        stderr: Buffer.concat(stderr),
        exitCode,
      };
    } finally {
      this.#streams.delete(localId);
    }
  }

  #nextIdCounter = 0;
  /**
   * 与 AOSP 一样顺序递增。**不要改成随机 id**：随机 id 可能与在用的 id 撞车，
   * 撞车后先关闭的那条流会把另一条活流的队列一起拆掉（AOSP 的注释专门记了这条）。
   */
  #nextId() {
    return ++this.#nextIdCounter;
  }

  close() {
    try {
      this.#sock.destroy();
    } catch { /* ignore */ }
  }
}

/** 单条 stream 的报文队列。 */
class StreamQueue {
  #items = [];
  #waiter = null;
  #error = null;

  get isEmpty() {
    return this.#items.length === 0;
  }

  push(item) {
    if (this.#waiter !== null) {
      const w = this.#waiter;
      this.#waiter = null;
      w.resolve(item);
    } else {
      this.#items.push(item);
    }
  }

  fail(err) {
    this.#error = err;
    if (this.#waiter !== null) {
      const w = this.#waiter;
      this.#waiter = null;
      w.reject(err);
    }
  }

  /** 只取已有报文，没有就返回 null（用于 CLSE 之后的收尾排空）。 */
  tryNext() {
    return this.#items.length > 0 ? this.#items.shift() : null;
  }

  /** timeoutMs 为 0 表示不设超时（等命令自然结束）。 */
  next(timeoutMs) {
    if (this.#items.length > 0) return Promise.resolve(this.#items.shift());
    if (this.#error !== null) return Promise.reject(this.#error);
    return new Promise((resolve, reject) => {
      let timer = null;
      if (timeoutMs > 0) {
        timer = setTimeout(() => {
          if (this.#waiter === entry) this.#waiter = null;
          reject(new Error(`等待命令输出超时（${timeoutMs}ms）`));
        }, timeoutMs);
      }
      const entry = {
        resolve: (v) => {
          if (timer !== null) clearTimeout(timer);
          resolve(v);
        },
        reject: (e) => {
          if (timer !== null) clearTimeout(timer);
          reject(e);
        },
      };
      this.#waiter = entry;
    });
  }
}

/**
 * 把命令包成「先切到 cwd，再执行」。
 *
 * ⚠️ **cwd 必须显式传递，而且允许失败。** adbd 执行 shell 命令时的 cwd 恒为 `/`，
 * 而引擎会把工作目录设成 workspace（`/data/user/0/com.dsh.android/files/workspace`），
 * 那个目录是 `drwx------`，**uid 2000 进不去**（实测 Permission denied）。
 *
 * 所以这里用 `cd … 2>/dev/null; <command>` 而不是 `cd … && <command>`：
 * 切不过去就留在 `/` 继续跑，而不是让整条命令失败。
 *
 * 这个降级是**有意**的 —— 特权命令本就不该依赖工作区里的相对路径
 * （uid 2000 读不到工作区，相对路径对它没有意义）。而如果用 `&&`，
 * 那么「在工作区里跑 `screencap`」这种完全合理的用法会直接失败。
 *
 * ⚠️ 与 root runner（`dsh-android-root.mjs`）的 `cd … || exit 79` **刻意不同**：
 * 那边是 uid 0，任何目录都进得去，切不过去就是真错误、必须失败。
 *
 * ⚠️ 但降级**不能是无声的**。原实现把 `cd` 的报错 `2>/dev/null` 丢掉、又没有任何
 * 替代提示，于是「workdir 不存在 / 进不去」与「workdir 正常」在输出上**完全一样**：
 * 模型 `shell(command="ls", workdir="/sdcard/nope")` 会在 `/` 下执行并拿到一堆
 * 看起来正常的结果，据此认为命令在目标目录里跑成功了 —— 这是**静默失真**，
 * 比命令直接失败更难查（调用方甚至不会起疑）。
 *
 * 所以这里在切不过去时补一行带 `[dsh-adb]` 前缀的 stderr 提示（与 runner 里
 * 其它诊断同一格式），说明实际用的是哪个目录。命令本身仍照常执行、退出码不受影响。
 */
function withCwd(command, cwd) {
  if (typeof cwd !== 'string' || cwd === '' || cwd === '/') return command;
  // 单引号包裹 + 内部单引号转义，避免路径里的空格/特殊字符破坏命令
  const quoted = `'${cwd.replaceAll("'", `'\\''`)}'`;
  const notice =
    `printf '%s\\n' '[dsh-adb] 无法进入工作目录 ${cwd.replaceAll("'", `'\\''`)}（uid 2000 通常读不到应用私有目录），已在 / 下执行' >&2; `;
  return `cd ${quoted} 2>/dev/null || { ${notice}cd / 2>/dev/null; }; ${command}`;
}

// ── Shizuku 通道（经原生侧的本地 socket 桥）─────────────────
/**
 * Shizuku 桥的抽象 socket 名，由 Kotlin 侧经 `DSH_SHIZUKU_SOCKET` 传入。
 *
 * ⚠️ **`\0` 前缀必须在这里补，不能指望环境变量带上它。**
 * `execve` 的 `envp` 是 C 字符串数组，NUL 就是结束符 —— Kotlin 侧若把带 NUL 的
 * 值放进 `ProcessBuilder.environment()`，传过来会被**静默截断成空串**，
 * 这条通道整条不生效且毫无报错。所以原生侧只传纯名字（`<包名>.shizuku`），
 * Node 的 `net.connect({ path })` 靠这个 NUL 字节认出「抽象命名空间」。
 *
 * ⚠️ 少了这个字节，Node 会去文件系统里找一个同名**文件**，报 `ENOENT` ——
 * 看起来像「桥没起来」，而桥其实好好的。
 *
 * ⚠️ 未设置时给空串（而不是某个猜测的默认值）：猜错的后果是连到一个不存在的
 * socket 白等一次超时；空串则直接跳过这条通道。
 */
const SHIZUKU_SOCKET =
  process.env.DSH_SHIZUKU_SOCKET === undefined || process.env.DSH_SHIZUKU_SOCKET === ''
    ? ''
    : `\u0000${process.env.DSH_SHIZUKU_SOCKET}`;

/**
 * 单条 Shizuku 命令的超时（毫秒）。
 *
 * 取 60 秒：这条通道**不经过 adb server**，省掉了连接与认证，但 `dumpsys`
 * 这类命令本身就慢。超时的作用只是防止「Shizuku 服务端卡住 ⇒ 特权命令永远不返回」，
 * 不必设得太紧。
 */
const SHIZUKU_TIMEOUT_MS = 60_000;

/** 帧长度上限，与 Kotlin 侧 `ShizukuChannel.MAX_FRAME` **必须一致**。 */
const SHIZUKU_MAX_FRAME = 32 * 1024 * 1024;

/**
 * 经 Shizuku 桥执行一条命令。
 *
 * ## 为什么这条通道要排在 legacy 之前
 *
 * 它绕开了 legacy 通道的**全部**前提：不需要 `adb tcpip 5555`、不需要
 * adbkey 被设备信任、不需要无线调试。对 Android 10 及以下（没有无线调试，
 * 且 `persist.adb.tcp.port` 写不进去 ⇒ 每次重启都要 PC 重来一次）的用户来说，
 * 这往往是**唯一**可行的路。
 *
 * 而它失败得也快：桥没起 / Shizuku 没跑时，连 socket 直接 ECONNREFUSED 或
 * ENOENT，几毫秒就返回，不会拖慢本来就该走 legacy 的设备。
 *
 * ## 协议
 *
 * 请求 `[4 字节大端长度][UTF-8 JSON]`，响应同构，见 `ShizukuChannel.kt`。
 * stdout/stderr 用 Base64 传输（原生侧拿到的是原始字节，直接按 UTF-8 解会把
 * 非法字节序列替换成 U+FFFD，把信息抹掉），这里解回来。
 *
 * @param {string} command 要执行的命令（已含 `withCwd` 的包装）。
 * @param {string|undefined} workdir 工作目录，`newProcess` 的第三个参数。
 * @returns {Promise<{stdout: string, stderr: string, code: number, truncated: boolean}|null>}
 *   成功给结果；**任何失败都给 `null`**，由调用方继续往下回落 ——
 *   这条通道的失败不该让整条链断掉（与 legacy 通道的写法一致）。
 *
 *   ⚠️ `truncated` 为 true 表示原生侧因超出 `MAX_CAPTURE_BYTES` 截断了输出。
 *   调用方**必须**把它透给模型（同时已写一行 stderr 提示），
 *   否则模型会拿半截输出当完整结果下结论。
 */
function runViaShizuku(command, workdir) {
  return new Promise((resolve) => {
    if (SHIZUKU_SOCKET === '') return resolve(null);

    let settled = false;
    const done = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        socket.destroy();
      } catch {
        // 已经关了
      }
      resolve(value);
    };

    const timer = setTimeout(() => {
      process.stderr.write(`${TAG} Shizuku 通道超时（${SHIZUKU_TIMEOUT_MS}ms），继续回落\n`);
      done(null);
    }, SHIZUKU_TIMEOUT_MS);
    // ⚠️ 必须 unref：否则这条定时器自己就成了吊住事件循环的 handle，
    //    与文件尾 armExitBackstop() 的说明同一个原因。
    timer.unref?.();

    const socket = net.connect({ path: SHIZUKU_SOCKET });
    let buf = Buffer.alloc(0);

    socket.on('connect', () => {
      const payload = Buffer.from(JSON.stringify({ command, workdir: workdir ?? null }), 'utf8');
      const head = Buffer.alloc(4);
      head.writeUInt32BE(payload.length, 0);
      socket.write(Buffer.concat([head, payload]));
    });

    socket.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (buf.length < 4) return;
      const len = buf.readUInt32BE(0);
      if (len <= 0 || len > SHIZUKU_MAX_FRAME) {
        process.stderr.write(`${TAG} Shizuku 桥回了非法帧长度 ${len}，继续回落\n`);
        return done(null);
      }
      if (buf.length < 4 + len) return;
      let res;
      try {
        res = JSON.parse(buf.subarray(4, 4 + len).toString('utf8'));
      } catch (e) {
        process.stderr.write(`${TAG} Shizuku 桥响应解析失败: ${e.message}\n`);
        return done(null);
      }
      if (res.ok !== true) {
        // 桥如实回答了「现在执行不了」，把它的原话透出去（比我们自己猜一句准）。
        process.stderr.write(
          `${TAG} Shizuku 通道不可用（${res.code ?? 'unknown'}: ${res.message ?? ''}），继续回落\n`
        );
        return done(null);
      }
      // ⚠️ 截断标记必须往下传，不能在这里丢掉。
      //   原生侧对输出做了限长（见 ShizukuChannel.MAX_CAPTURE_BYTES），
      //   超限时 `truncated: true`。若这里悄悄丢弃，模型会拿半截输出当完整结果
      //   去下结论（"文件里只有这些行"），比直接报错难查得多。
      const truncated = res.truncated === true;
      const limit = typeof res.captureLimitBytes === 'number' ? res.captureLimitBytes : 0;
      if (truncated) {
        process.stderr.write(
          `${TAG} 命令输出超过 ${limit} 字节上限，已截断；` +
            '需要完整内容请用 head/tail/分页读取\n'
        );
      }
      done({
        stdout: Buffer.from(res.stdout ?? '', 'base64').toString('utf8'),
        stderr: Buffer.from(res.stderr ?? '', 'base64').toString('utf8'),
        code: typeof res.exitCode === 'number' ? res.exitCode : 0,
        truncated,
      });
    });

    // ⚠️ 连不上是**正常路径**（多数设备压根没装 Shizuku），不打 `[dsh-adb]`
    //    前缀的诊断行 —— 那会污染每一条特权命令的 stderr，让用户以为出了问题。
    //    真正的结论由 `classifyFallback()` 在全部通道都失败时统一给出。
    socket.on('error', () => done(null));
    socket.on('close', () => done(null));
  });
}

// ── 无线调试通道（经内置 adb 二进制）────────────────────────
/**
 * 读 Kotlin 侧（`AdbMdns`）写下的端口文件。
 *
 * 格式：**单个连接端口**，一行。
 *
 * ⚠️ **这里曾经声明成 `<connect端口> [<配对端口>]`，那是错的。** Kotlin 侧的
 * `WirelessDebugging.writePort()` 从头到尾只写一个值，所以第二个字段永远是
 * `undefined` → `pairing` 恒为 `null`，而全工程**没有任何地方读它**。
 * 一个恒为 null 的字段比没有这个字段更坏：它让人以为「配对端口可以从文件里拿到」，
 * 从而在排查配对问题时去查一个根本不存在的通道。
 *
 * 配对端口**不经文件传递**：它在 `AdbPairingNotifier` 里由 `AdbMdns` 发现后
 * 直接经通知带进 `pair()` 的 `knownPort` 参数（见 `WirelessDebugging.pair`）。
 * 那条路径是必需的 —— 配对端口只在「使用配对码配对设备」界面开着时才存在，
 * 等用户回到 App 再读文件必然已经落空。
 *
 * ⚠️ 这里**不做任何端口探测**（不 connect、不 bind、不扫）。有效性由写入方
 * 负责 —— `AdbMdns` 已经用「bind 127.0.0.1:port 失败即 adbd 在听」筛过一遍，
 * 并且校验了解析出的地址必须是本机地址。重复校验只是白花时间。
 * 端口死没死由调用方按实际连接结果判定（见通道 2 的 `clearWirelessPortFile()`）。
 *
 * @returns {number|null} 连接端口；没有或格式不对给 `null`。
 */
function readWirelessPort() {
  try {
    const raw = fs.readFileSync(PORT_CACHE_FILE(), 'utf8').trim();
    if (raw === '') return null;
    const port = Number.parseInt(raw.split(/\s+/)[0], 10);
    return Number.isInteger(port) && port > 0 ? port : null;
  } catch {
    // 文件不存在 = 无线调试没开或还没发现，属于正常情况
    return null;
  }
}

/**
 * 用内置 adb 二进制跑一条命令。
 *
 * 命令**不经 shell 二次转义**：`adb shell <cmd>` 的参数是逐个传给 adb 的，
 * adb 会把它们拼成一条命令交给设备端 shell，所以这里直接把原命令作为一个
 * argv 传进去，与用户敲 `adb shell '...'` 等价（实测退出码 42/0/5 透传正常，
 * 单引号、中文、`&&`、管道全部正确）。
 *
 * @param {string} adbBin - adb 可执行文件路径。
 * @param {string} target - `127.0.0.1:<port>`。
 * @param {string} command - 要执行的命令。
 * @returns {Promise<{stdout: string, stderr: string, code: number}>} 执行结果。
 */
/**
 * 内置 adb 的运行环境。
 *
 * ⚠️ 三个变量缺一不可，都是踩过的坑：
 * - `HOME`：adb 在 `$HOME/.android/adbkey` 找密钥。指向别处会**静默自生成**一把新密钥。
 * - `LD_LIBRARY_PATH`：adb 是 Termux 的 ELF，要它的 `.so`。**只对 adb 需要** ——
 *   引擎进程绝不能设它（会毒化系统二进制，见 AGENTS.md）。
 * - `TMPDIR`：adb 硬编码往 `/tmp/adb.<pid>.log` 写启动日志，app 域无权写 `/tmp`
 *   （实测 `cannot open /tmp/adb.10535.log: Permission denied` → server 起不来）。
 */
function adbEnv() {
  return {
    ...process.env,
    HOME: path.join(ADB_HOME, 'home'),
    LD_LIBRARY_PATH: path.join(ADB_HOME, 'lib'),
    TMPDIR: path.join(ADB_HOME, 'tmp'),
  };
}

/** 跑一次内置 adb，收集 stdout/stderr/退出码。 */
function adbExec(args) {
  return new Promise((resolve) => {
    const env = adbEnv();
    try {
      fs.mkdirSync(env.TMPDIR, { recursive: true });
    } catch {
      // 已存在
    }
    const child = spawn(ADB_BIN, ['-L', ADB_SOCKET, ...args], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
    });
    let out = '';
    let err = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (c) => { out += c; });
    child.stderr.on('data', (c) => { err += c; });
    child.on('error', (e) => resolve({ code: 127, out, err: `${err}${e.message}` }));
    // ⚠️ **必须监听 `'exit'`，不能监听 `'close'`**（真机踩过，是本文件最隐蔽的一个坑）。
    //
    //   `'close'` 要等**所有 stdio 流都关闭**才触发，而 `adb start-server` 起的是
    //   **常驻 adb server daemon** —— 它继承了这两个 pipe 的写端并一直持有，
    //   读端于是永远等不到 EOF ⇒ `'close'` 永不触发 ⇒ 这个 Promise 永不 resolve
    //   ⇒ **node 事件循环不空、进程永不退出**，最后只能靠 Kotlin 侧的 90 秒看门狗
    //   强杀（真机日志：`switch-tcp 超过 90 秒仍未结束，强制结束它`，
    //   退出码 143 = SIGTERM）。
    //
    //   症状是「配对码填对了、adb 明明报了 Successfully paired，通知栏却一直
    //   正在配对」—— 因为 `--pair` 里配对成功后要补切固定端口，而那条路会调
    //   `adbExec`，而当时 `adbExec` 里正卡着一个永远不触发的 `'close'`。
    //
    //   `'exit'` 只等**进程**退出，正是我们要的语义：命令的输出在进程退出前
    //   就已写进管道缓冲，进程一退即可安全读取。
    child.on('exit', (code, signal) => {
      // ⚠️ 进程退出 ≠ 数据已全部派发：pipe 里可能还有已到达但没触发 `data` 的字节
      //   （`connect` 输出里的 `connected` 正是要匹配的，少几个字符就会误判成
      //   连接失败）。所以**先给 `'close'` 一个窗口**让它把数据派发干净；
      //   若 daemon 一直占着写端导致 `'close'` 不来，则由 `setTimeout` 兜底。
      //
      //   窗口取 1000ms：这一条只在**进程已经退出**之后才开始等，正常情况
      //   `'close'` 几乎立刻到（数据早已在管道缓冲里），所以稳态零代价；
      //   而慢机上 pipe 派发要几百毫秒，给 150ms 会**偶发**读到截断的输出。
      //   这是「宁可偶尔多等 1 秒，也不要读到半个 connected」的取舍。
      //   两路都走同一个 `finish()`，Promise 只认第一次 resolve。
      const finish = () => {
        // ⚠️⚠️ **必须 `unref()` 这两条 pipe，否则 node 照样不退出。**
        //
        //   光把监听器从 `'close'` 换成 `'exit'` 只解决了「Promise 永不 resolve」，
        //   没有解决「handle 永不释放」——这是两个独立的问题，真机上表现为同一个
        //   症状（通知栏一直「正在配对」）。
        //
        //   任何一条 adb 命令在 server 没跑时都会**自己 fork 出常驻 adb daemon**，
        //   而这个 daemon **继承了我们给客户端的那两条 pipe 的写端**并一直持有。
        //   于是 `child.stdout` / `child.stderr` 这两个 pipe socket 的读端永远等不到
        //   EOF —— 子进程虽然退出了，但**pipe 本身仍是 ref'd 的活跃 handle**，
        //   `process.exitCode` 设好之后事件循环依然不空 ⇒ node 不退出 ⇒
        //   Kotlin 的 `proc.waitFor()` 等不到 ⇒ 90 秒看门狗强杀。
        //
        //   ## 为什么是 `unref()` 而不是 `destroy()`（实测对照，别改回去）
        //
        //   | 对两条 pipe 做什么 | 事件循环里残留的 handle |
        //   |---|---|
        //   | 什么都不做 | 2 个 Socket |
        //   | 只 `destroy()` | **1 个**（底层 fd 仍在，仍吊着循环） |
        //   | 只 `unref()` | **0 个** ← 只有这个能让循环真正排空 |
        //   | `unref()` + `destroy()` | 1 个（`destroy` 又把它算回活跃） |
        //
        //   `destroy()` 关的是**我们这侧的读端**，但 libuv 那边仍把这条 pipe 算作
        //   活跃句柄；`unref()` 才是「别再拿它当进程活着的理由」。这里不需要关 fd
        //   （本 runner 是一次性进程，跑完即退，fd 随进程消失），只需要让循环能空。
        //
        //   ⚠️ 数据不会因此丢：`'exit'` 之后还有 `'close'`/兜底窗口把管道读干净，
        //      `unref()` 只影响「循环是否被它吊住」，不影响已到达的数据。
        for (const s of [child.stdout, child.stderr]) {
          try {
            s.unref();
          } catch {
            // 不支持就算了，还有 main() 尾部的兜底
          }
        }
        resolve({ code: code ?? (signal ? 128 : 1), out, err });
      };
      const fallback = setTimeout(finish, 1000);
      child.once('close', () => {
        clearTimeout(fallback);
        finish();
      });
    });
  });
}

/**
 * 用内置 adb 二进制跑一条命令。
 *
 * 命令**不经 shell 二次转义**：`adb shell <cmd>` 的参数是逐个传给 adb 的，
 * adb 会把它们拼成一条命令交给设备端 shell，所以这里直接把原命令作为一个
 * argv 传进去，与用户敲 `adb shell '...'` 等价（实测退出码 42/0/5 透传正常，
 * 单引号、中文、`&&`、管道全部正确）。
 *
 * @param {string} adbBin - adb 可执行文件路径（保留形参以兼容调用点）。
 * @param {string} target - `127.0.0.1:<port>`。
 * @param {string} command - 要执行的命令。
 * @returns {Promise<{stdout: string, stderr: string, code: number}>} 执行结果。
 */
function runWireless(adbBin, target, command) {
  return (async () => {
    try {
      // ⚠️ **不要在这里显式 `start-server`。** 任何 adb 命令都会自动拉起 server，
      //   而且 adb 客户端**内部会等 server 就绪**再发这条命令。显式再调一次反而
      //   有害：`start-server` 是**异步 daemonize** 的，它一返回 server 可能还在
      //   初始化，紧接着的 `connect` 就会撞上未就绪的 server 并报
      //   `error: protocol fault (couldn't read status message)`（真机踩过）。
      const conn = await adbExec(['connect', target]);
      if (!conn.out.includes('connected')) {
        return { stdout: '', stderr: `${TAG} 无线调试连接失败: ${(conn.out + conn.err).trim()}\n`, code: 1 };
      }
      const r = await adbExec(['-s', target, 'shell', command]);
      // adb 把远端 stderr 并进自己的 stderr，stdout/stderr 天然分开
      return { stdout: r.out, stderr: r.err, code: r.code };
    } catch (e) {
      return { stdout: '', stderr: `${TAG} 无线调试执行异常: ${e.message}\n`, code: 1 };
    }
  })();
}

// ── 回落：本地执行（app 域）─────────────────────────────────
function runLocal(command) {
  const shell = process.env.PREFIX ? path.join(process.env.PREFIX, 'bin', 'bash') : 'bash';
  return new Promise((resolve) => {
    const child = spawn(shell, ['-c', command], {
      stdio: ['ignore', 'inherit', 'inherit'],
      env: process.env,
    });
    child.on('error', (e) => {
      process.stderr.write(`${TAG} 本地执行失败: ${e.message}\n`);
      resolve(127);
    });
    child.on('exit', (code, signal) => resolve(code ?? (signal !== null ? 128 : 1)));
  });
}

// ── 入口 ─────────────────────────────────────────────────────
/**
 * 写出全部数据并等它真正 flush 完，再设定退出码。
 *
 * ⚠️ **不要用 `process.exit(code)` 收尾。** 输出走管道时 `process.exit()` 会
 * 在 libc 缓冲区写回之前就终止进程，**静默截断**大输出 —— `screencap`、
 * `dumpsys`、`ps -A` 这类恰好都是大输出。用 `process.exitCode` 让事件循环
 * 自然退出，Node 会把流排干。
 *
 * ⚠️ **返回 Promise，调用方必须 `await`。** `main()` 尾部那个 `armExitBackstop()`
 * 兜底会在 `main()` settle 之后强制 `process.exit()`；若这里不 await，兜底就可能
 * 在输出排干之前开火，把上面那条「不许截断」的承诺破掉。await 之后，`main()`
 * settle 即代表输出已确实写出，兜底只负责「释放 handle」这一件事。
 */
function finish(stdout, stderr, code) {
  const write = (stream, buf) =>
    new Promise((resolve) => {
      if (buf.length === 0) return resolve();
      stream.write(buf, () => resolve());
    });
  return Promise.all([write(process.stdout, stdout), write(process.stderr, stderr)]).then(() => {
    process.exitCode = code;
  });
}

/**
 * 让内置 adb 用同一把密钥：把 `$DSH_HOME/adbkey` 放到它期望的
 * `$HOME/.android/adbkey`。
 *
 * ⚠️ 两边必须是**同一把**。设备 keystore 里记的是 PC 那把公钥，换一把就认证不过。
 * 同时删掉 `.pub`：让 adb 从私钥现场派生，避免用上不匹配的公钥（PC 上那个
 * `adbkey.pub` 就与私钥不一致，是踩过的坑）。
 * @returns {boolean} 密钥是否就位。
 */
function syncKeyForAdb() {
  const src = keyPath();
  if (!fs.existsSync(src)) return false;
  const dir = path.join(ADB_HOME, 'home', '.android');
  const dest = path.join(dir, 'adbkey');
  try {
    fs.mkdirSync(dir, { recursive: true });
    const want = fs.readFileSync(src);
    // 内容不同才写，省掉每条命令一次无谓的磁盘写
    let same = false;
    try {
      same = fs.readFileSync(dest).equals(want);
    } catch {
      same = false;
    }
    if (!same) {
      fs.writeFileSync(dest, want, { mode: 0o600 });
      fs.rmSync(`${dest}.pub`, { force: true });
    }
    return true;
  } catch (e) {
    process.stderr.write(`${TAG} 同步 adb 密钥失败: ${e.message}\n`);
    return false;
  }
}

/**
 * 无线调试的**配对**（Android 11+）。
 *
 * 配对走 SPAKE2 + TLS-PSK，Node 的 `tls` 实现不了，所以借内置 adb 二进制的
 * `adb pair`。配对成功后设备 keystore 就记下了我们的公钥，之后 `connect` 免配对。
 *
 * @param {string} port - 配对端口（设备「使用配对码配对设备」界面上显示的端口）。
 * @param {string} code - 6 位配对码。
 * @returns {Promise<number>} 退出码。
 */
function pairWireless(port, code) {
  // 配对输出要实时给用户看（Kotlin 侧读出来打日志），所以这里不用 adbExec 的
  // 一次性收集，而是 inherit 出去。
  return new Promise((resolve) => {
    const env = adbEnv();
    try {
      fs.mkdirSync(env.TMPDIR, { recursive: true });
    } catch {
      // 已存在
    }
    const child = spawn(ADB_BIN, ['-L', ADB_SOCKET, 'pair', `${HOST}:${port}`, code], {
      stdio: ['ignore', 'inherit', 'inherit'],
      env,
    });
    child.on('error', (e) => {
      process.stderr.write(`${TAG} 启动 adb 失败: ${e.message}\n`);
      resolve(127);
    });
    child.on('exit', (c) => resolve(c ?? 1));
  });
}

/** legacy 端口是否有人监听（能连上即视为就绪）。 */
function legacyAlive() {
  return new Promise((resolve) => {
    const sock = net.connect({ host: HOST, port: PORT });
    const done = (ok) => {
      sock.removeAllListeners();
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(1000);
    sock.on('connect', () => done(true));
    sock.on('error', () => done(false));
    sock.on('timeout', () => done(false));
  });
}

/**
 * 固定端口是否**真的能拿到 uid 2000**（而不是「有人占着这个端口」）。
 *
 * ⚠️ **这是本文件最重要的一条判据，不要退化成 `legacyAlive()`。**
 *
 * `legacyAlive()` 只做一次 TCP connect：5555 是 >1024 的端口，**任何应用都能
 * bind**。旧实现用它当「固定端口已就绪」的判据，撞上别人的监听时会：
 *
 * 1. 报「已经是 TCP 模式，无需再切」（假成功）；
 * 2. 之后每条特权命令都去连那个**不是 adbd** 的端口 → 握手失败 → 静默回落成
 *    app 身份；
 * 3. 而设置页因为拿到了 `通道: legacy` 之类的行（或至少 `switchToTcp` 返回 true）
 *    而显示「已切到固定端口」。
 *
 * 走一次真实握手才能把两者分开。`connect()` 内部**永不发公钥**，所以这个探测
 * 即使对上未授权的密钥也**不会弹「允许 USB 调试吗」**（见 `handshake()`）。
 *
 * @returns {Promise<boolean>} 是否真的以 uid 2000 跑通了 `id -u`。
 */
async function fixedPortWorks() {
  let conn = null;
  try {
    // ⚠️ **必须 `probe: true`** —— 这是一次纯探测（「5555 现在能不能给我 uid 2000」），
    //    它的失败可能只是 adbd 还没重载完 keystore（刚配对完那几十毫秒就是这样）。
    //    若让它写「授权已撤销」，就会把**刚配对成功的密钥**当场判死，并且这个错误
    //    结论会反过来挡住 `switchToTcp()` 自己后续的步骤（真机 59ms 内复现）。
    //    完整推导见 `handshake()` 的 `recordRevocation` 参数。
    conn = await connect({ probe: true });
    const { stdout, exitCode } = await conn.shell('id -u');
    return exitCode === 0 && stdout.toString().trim() === '2000';
  } catch {
    return false;
  } finally {
    conn?.close();
  }
}

/** 阻塞 `ms` 毫秒。 */
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * 把 adbd 从「无线调试」切到固定的 legacy TCP 端口（默认 5555）。
 *
 * ## 为什么需要这一步（实测结论）
 *
 * 无线调试的 TLS 端口**只在无线调试开关开着时才存在**，而且**关掉开关会立刻
 * 掐断已建立的连接**（实测：`settings put global adb_wifi_enabled 0` 之后，
 * 已连上的 `adb-…._adb-tls-connect._tcp` 立刻变 `device offline`）。
 * 也就是说「连上后一直用无线调试」这条路是死的 —— 用户一关开关，通道就断。
 *
 * 而 `adb tcpip 5555` 会把 adbd **重启到 TCP 模式**，此后走 `127.0.0.1` 回环：
 *
 * | | 无线调试 | tcpip 5555 |
 * |---|---|---|
 * | 关掉无线调试开关 | **立刻断连** | **不受影响**（实测 `wifi=0` 时仍 `LISTEN *:5555`） |
 * | 关掉 Wi-Fi | 断 | **不受影响**（走 127.0.0.1 回环） |
 * | 端口 | 每次重启随机 | 固定 5555 |
 * | 加密 | TLS | 明文（但只在回环上，不出设备） |
 * | 重启手机后 | 需重开开关 | **需重新切一次**（`persist.adb.tcp.port` 实测写不进去） |
 *
 * ⚠️ **两者可以并存**：Android 15 实测 `adb tcpip 5555` **不会**关闭无线调试
 * （`adb_wifi_enabled` 仍是 1）。所以这是一个纯增益动作，不会破坏用户的设置。
 *
 * ⚠️ 切之前**必须先有一条活着的连接**（要么 legacy 已在听，要么无线调试连着），
 * 因为 `tcpip` 命令本身要通过 adb 发出去。
 *
 * ## 本轮修掉的两个判据错误（都是真实用户症状的根因）
 *
 * 1. **成功判据**：旧代码用「20 × 300ms 轮询 `legacyAlive()`」—— 那只是
 *    「端口有人听」，而且只等 6 秒。adbd 重启常超过 6 秒，于是**切成功了却报失败**
 *    （通知栏说「还没切到固定端口」，用户去点重试又遇到问题 2）。
 *    现在改成 `fixedPortWorks()`：**握手 + `id -u` == 2000**，预算 20 秒。
 * 2. **端口从哪来**：旧代码**只**从 `adb-wireless-port` 文件读，而那个文件恰恰
 *    在**无线调试被关掉时会被删除**（`AdbMdns.onServiceLost` → `writePort(-1)`）。
 *    于是最需要切的时候它必然说「没有可用的无线调试端口」。
 *    现在分两路：文件为空时给 5 秒重试窗口（adbd 重启后常驻监听可能还没写），
 *    仍为空则接受**由 Kotlin 侧直接传进来的端口**（`--switch-tcp <port>`）——
 *    `WirelessDebugging.ensureTcpPort()` 会先自己发现一次再调，所以
 *    「刚关掉无线调试但设备上还开着」这个场景也能走通。
 *
 * @param {number|null} knownPort Kotlin 侧已发现的无线调试端口；`null` 表示没有。
 * @returns {Promise<boolean>} 是否**确实**拿到了 uid 2000 的固定端口。
 */
async function switchToTcp(knownPort = null) {
  // ── 已经就绪？先验一次（幂等，且这一句就是稳态下的全部开销）──
  //
  // ⚠️ **这一步必须排在「检查密钥 / 内置 adb」之前。** 它用的是**自实现 ADB 协议**
  //    （见 `fixedPortWorks()` → `connect()`），既不依赖内置 adb，也不需要
  //    `syncKeyForAdb()`。反过来把密钥检查提前会造成一个假失败：
  //    「5555 明明已经在听、只是这把密钥还没被授权」时直接报「密钥不可用」，
  //    而那时**什么都不用做**——固定端口本来就是好的。
  if (await fixedPortWorks()) {
    process.stderr.write(`${TAG} 固定端口 ${PORT} 已就绪（已确认可拿到 uid 2000）\n`);
    return true;
  }

  if (ADB_BIN === '' || !fs.existsSync(ADB_BIN)) {
    process.stderr.write(`${TAG} 内置 adb 不可用，无法切换固定端口\n`);
    return false;
  }

  // 先保证有密钥（没有就现场生成一把）。**必须排在下面的授权判据之前** ——
  // 否则「还没有密钥」会被误报成「密钥未被授权」，两种处境用户该做的事不同。
  if (!ensureKey()) {
    process.stderr.write(`${TAG} ADB 密钥不可用，无法切换固定端口\n`);
    return false;
  }

  // ⚠️ **这一步必须排在 `syncKeyForAdb()` 与 `adbExec()` 之前**，理由是它挡的是
  //    「弹授权框」而不是「功能不可用」：
  //    下面 `adbExec(['connect', …])` 用的是**内置 adb**，它遇到设备不认识的密钥
  //    会**自己**发公钥 → 系统弹「允许 USB 调试吗」。那个行为在外部进程里，
  //    runner 拦不住，只能提前不进这条分支。
  //
  // ⚠️ 这一句原先**没有**，因为 `switchToTcp` 只有「用户手点按钮」与「引擎启动
  //    自愈一次」两个调用点，最多弹一两次、用户还能理解成「点了按钮就该授权」。
  //    现在多了**每 5 分钟一次的固定端口守护**（`WirelessDebugging.startFixedPortGuard`），
  //    没有守卫就是**每 5 分钟弹一次窗**，用户会以为中了病毒。
  //    所以这个守卫是随守护一起必须补上的。
  //
  // 判据与通道 1.5 / 通道 2 用的是同一个 `keyMayBeAuthorized()`：它只挡
  // `origin = generated` 且从未握手成功的那把（唯一会弹窗的），
  // `imported` / `paired` / 已建立过信任标记的密钥照常放行。
  if (!keyMayBeAuthorized()) {
    process.stderr.write(
      `${TAG} 无法切到固定端口：密钥尚未被设备授权，跳过以免反复弹出「允许 USB 调试吗」\n` +
        `${TAG}   → 请在设置页点「配对无线调试」完成一次授权，之后本步会自动完成。\n`,
    );
    return false;
  }

  if (!syncKeyForAdb()) {
    process.stderr.write(`${TAG} ADB 密钥不可用，无法切换固定端口\n`);
    return false;
  }

  // 优先用调用方给的端口（Kotlin 侧刚发现的新鲜值），否则读文件并重试。
  let port = knownPort !== null && knownPort > 0
    ? knownPort
    : await readWirelessConnectPortWithRetry();
  if (port !== null) {
    process.stderr.write(`${TAG} 准备经无线调试端口 ${port} 切到固定端口 ${PORT}\n`);
  }
  if (port === null) {
    process.stderr.write(
      `${TAG} 无法切到固定端口：本机现在既没有 ${PORT}，也没有无线调试端口。\n` +
        `${TAG}   → 请在系统设置里打开「无线调试」（Android 11+），或先用电脑执行一次 ` +
        `adb tcpip ${PORT}，然后回到设置页点「切到固定端口」。\n`,
    );
    return false;
  }

  // ⚠️ **必须先 `connect` 一次**，否则下面那句 `-s ${HOST}:${port}` 会因为
  //    adb server 里根本没有这个 device 而直接报 `device not found`。
  //    （这一段原先只在 `--switch-tcp` 分支里做，`--pair` 分支没做 ——
  //    于是「配对成功后自动切 5555」**必然失败**，用户只能一直靠无线调试
  //    顶着，一关 Wi-Fi 通道就断。现在收进来，三个调用点共用同一份保证。）
  //
  // ⚠️ **不要再补 `await adbExec(['start-server'])`。** `connect` 自己就会把
  //    server 拉起来并等它就绪；显式调反而会与紧随其后的命令抢时序，报
  //    `error: protocol fault (couldn't read status message)`（真机踩过，
  //    见 `adbExec` 里关于 `'exit'` 的注释）。
  const conn = await adbExec(['connect', `${HOST}:${port}`]);
  if (!conn.out.includes('connected')) {
    // 无线调试端口已经失效（用户关了开关、或 adbd 重启换了端口）。
    // 清掉陈旧端口文件，避免下一条命令再去连它。
    clearWirelessPortFile();
    process.stderr.write(
      `${TAG} 无法切到固定端口：连不上无线调试 ${HOST}:${port}` +
        `（${(conn.out + conn.err).trim()}）。若你刚关掉无线调试，请先重新打开它。\n`,
    );
    return false;
  }

  const rc = await adbExec(['-s', `${HOST}:${port}`, 'tcpip', String(PORT)]);
  if (rc.code !== 0) {
    process.stderr.write(`${TAG} 切换 TCP 模式失败: ${(rc.out + rc.err).trim()}\n`);
    return false;
  }

  // ── 等 adbd 重启完，并用**握手 + id -u** 确认（不是「端口有人听」）──
  const deadline = Date.now() + TCPIP_VERIFY_BUDGET_MS;
  let checks = 0;
  while (Date.now() < deadline) {
    await sleep(TCPIP_POLL_INTERVAL_MS);
    checks += 1;
    if (await fixedPortWorks()) {
      process.stderr.write(
        `${TAG} 已切到固定端口 ${HOST}:${PORT}（第 ${checks} 次确认拿到 uid 2000，` +
          `不再依赖无线调试开关与 Wi-Fi）\n`,
      );
      return true;
    }
  }
  process.stderr.write(
    `${TAG} 切换 TCP 模式后 ${TCPIP_VERIFY_BUDGET_MS / 1000} 秒内未能确认 ${PORT} 可拿到 uid 2000` +
      `（已确认 ${checks} 次）。adbd 可能仍在重启，稍后会自动重试。\n`,
  );
  return false;
}

/**
 * 找无线调试的连接端口：先读 Kotlin 侧写的文件，给它一段重试窗口。
 *
 * ⚠️ **本函数刻意只读文件，绝不自己发现。** 两条理由都是硬性的：
 *
 * 1. **Node 发不出 UDP 组播**（实测 `EPERM send EPERM 224.0.0.251:5353`），
 *    而 Android 的 `mdnsd` 只应答组播查询 —— 所以 runner 自己做不了 mDNS。
 *    真正的发现能力在 Kotlin 侧（`NsdManager` 走系统服务）。
 * 2. 本项目**曾经**有一个 `--discover` 子命令，已经被删除（见 `main()` 里的注释），
 *    留着它只会伪造出一个陈旧但精确的结论。**不要再加回来。**
 *
 * 因此「文件为空」时的正确做法是**让 Kotlin 侧去发现、把端口作为参数传进来**
 * （`--switch-tcp <port>`）。本函数只负责那种情况的兜底：文件可能只是**还没写**
 * （adbd 重启后常驻监听尚未更新），所以给 5 秒重试窗口，而不是立刻放弃。
 *
 * @returns {Promise<number|null>} 端口号；文件始终为空则给 `null`。
 */
async function readWirelessConnectPortWithRetry() {
  for (let i = 0; i < 20; i += 1) {
    const port = readWirelessPort();
    if (port !== null) return port;
    await sleep(250);
  }
  return null;
}

/**
 * 删掉陈旧的无线调试端口文件。
 *
 * ⚠️ **只在确认那个端口已经连不上时调用。** 端口文件是 Kotlin 侧常驻监听写的，
 * 无脑删会让正在工作的无线通道失去端口（那正是 AGENTS.md 里记过的
 * 「一次没探到就删文件」那个缺陷）。所以这条只在 `adb connect` 明确失败时走。
 */
function clearWirelessPortFile() {
  try {
    fs.unlinkSync(PORT_CACHE_FILE());
    process.stderr.write(`${TAG} 已清除失效的无线调试端口文件（下次会重新发现）\n`);
  } catch {
    // 本来就没有，或删不掉 —— 都不影响功能
  }
}

/**
 * 把三条通道的各自结论**收敛成一条可执行的原因**。
 *
 * ## 为什么需要它（旧实现的一个真实缺陷）
 *
 * 回落分支原来只报**通道 1** 的失败原因：
 *
 * ```js
 * const why = legacyReason === '' ? '没有可用的 uid 2000 通道' : legacyReason;
 * ```
 *
 * 于是四种完全不同的处境在设置页长得一模一样：
 *
 * | 真实处境 | 用户该做的事 |
 * |---|---|
 * | 还没有密钥 | 点「配对无线调试」 |
 * | 密钥被设备撤销了 | 重新配对（旧的授权没了） |
 * | 5555 没在听、无线调试也没开 | 打开无线调试，或点「切到固定端口」 |
 * | 无线端口文件是陈旧的 | 重开一次无线调试 |
 *
 * 用户拿到「uid 2000 不可用（连接 127.0.0.1:5555 超时）」这种结论**无法照做** ——
 * 他不知道 5555 是什么，也不知道该点哪个按钮。
 *
 * 所以这里按「最确定、最可执行」优先给出结论，并在末尾附一条机器可读的
 * `诊断:` 行（`kind=` 供设置页分支）。
 *
 * ⚠️ **`text` 里不能出现全角右括号 `）`**：宿主半的 `FALLBACK_RE`
 * （`mobile-ui-plugin/dsh-android-mobile/lib/index.js`）是
 * `/uid 2000 不可用（(.+?)）/`，非贪婪匹配到**第一个** `）` 就停。插一个进去
 * 会让设置页显示的原因被截断成半句。要断句用 `——`。
 *
 * @param {{key: string, legacy: string, wireless: string}} diag 三条通道的结论。
 * @param {string} legacyReason 通道 1 的原始失败原因。
 * @returns {{kind: string, text: string}} 分类与给人看的一句话。
 */
function classifyFallback(diag, legacyReason) {
  if (diag.key === 'missing') {
    return {
      kind: 'no-key',
      text: '还没有 ADB 密钥 —— 请在设置页点「配对无线调试」授权一把',
    };
  }
  if (diag.key === 'revoked') {
    return {
      kind: 'key-revoked',
      // ⚠️ 断句一律用 `——`，**不要**用全角括号包裹补充说明 —— 理由见本函数的
      //    「text 里不能出现全角右括号」那段注释。这条曾经违反过一次。
      text: '这把 ADB 密钥已被设备撤销授权 —— 请重新配对，' +
        '多半是在开发者选项里点了「撤销 USB 调试授权」',
    };
  }
  if (diag.key === 'unauthorized') {
    return {
      kind: 'key-unauthorized',
      text: 'ADB 密钥尚未被设备授权 —— 请在设置页点「配对无线调试」',
    };
  }
  if (diag.wireless === 'adb-missing') {
    return {
      kind: 'adb-missing',
      text: '内置 adb 工具链缺失，无线调试通道不可用 —— 请重装 App',
    };
  }
  if (diag.wireless === 'key-blocked') {
    return {
      kind: 'key-blocked',
      text: '密钥未获设备信任，已跳过无线调试通道以免频繁弹出授权框 —— 请重新配对',
    };
  }
  if (diag.wireless === 'connect-failed') {
    return {
      kind: 'wireless-stale',
      // ⚠️ 同 `key-revoked`：补充说明用 `——` 引出，**不要**用全角括号包起来。
      text: '无线调试端口已失效 —— 多半是无线调试被关掉或重启过，' +
        '请重新打开无线调试，或在设置页点「切到固定端口」',
    };
  }
  if (diag.wireless === 'no-port') {
    return {
      kind: 'no-channel',
      // ⚠️ 这条文案是**无无线调试设备**（Android 10 及以下）最常见的一屏。
      //    必须提到 Shizuku：对这类设备它是唯一免 PC 的路，而用户往往已经装了它。
      text: '固定端口 5555 与无线调试都不可用 —— 请在设置页启用 Shizuku，' +
        '或用电脑执行一次 `adb tcpip 5555`',
    };
  }
  return {
    kind: 'unknown',
    text: legacyReason === '' ? '没有可用的 uid 2000 通道' : legacyReason,
  };
}

async function main() {
  const argv = process.argv.slice(2);

  // ── 子命令：配对 ──────────────────────────────────────────
  // 端口由 Kotlin 侧（AdbMdns 发现 `_adb-tls-pairing._tcp`）通过参数传入。
  if (argv[0] === '--pair') {
    const port = argv[1];
    const code = argv[2];
    if (port === undefined || code === undefined) {
      process.stderr.write(`${TAG} 用法: node dsh-android-shell.mjs --pair <port> <code>\n`);
      process.exitCode = 2;
      return;
    }
    if (ADB_BIN === '' || !fs.existsSync(ADB_BIN)) {
      process.stderr.write(`${TAG} 内置 adb 不可用，无法配对\n`);
      process.exitCode = 1;
      return;
    }
    // 没有密钥就自己生成一把 —— 配对的目的正是**授权一把新密钥**，
    // 而不再需要 PC。详见 ensureKey()。
    if (!ensureKey() || !syncKeyForAdb()) {
      process.stderr.write(`${TAG} ADB 密钥不可用，无法配对\n`);
      process.exitCode = 1;
      return;
    }
    const rc = await pairWireless(port, code);
    if (rc === 0) {
      // 配对成功 = 设备 keystore 里已经存了我们的公钥，这把密钥从此**被信任**。
      // 把来源升级掉，否则设置页会一直显示「待配对」的黄点（判据见 keyOrigin）。
      markKeyOrigin('paired');
      // 同步把「信任」标记落盘。配对成功意味着设备已经收下了这把公钥，此后
      // legacy 通道的签名认证必然通过 —— 若不记，第一次 legacy 连接万一因别的原因
      // 签名失败，就会去发公钥、白弹一次窗（那正是要根治的行为）。
      const paired = loadKey();
      if (paired !== null) markKeyTrusted(paired);
      // ⚠️ 重新配对成功意味着设备**重新收下了**这把公钥 ⇒ 之前那次
      //    「授权被撤销」的结论已经过期。不清的话 `keyMayBeAuthorized()`
      //    会一直返回 false，通道 1.5 / 通道 2 永远进不去 —— 用户刚配对成功
      //    却发现特权身份还是拿不到，这是最难查的一类「配对成功但没用」。
      clearKeyRevoked();
      // 立刻切到固定的 TCP 端口 —— 配对只给了我们「能连无线调试」的权利，而
      // 无线调试的端口**一关开关就没了**（实测断连）。切成 tcpip 之后通道就
      // 不再依赖那个开关，也不会随重启换端口。详见 switchToTcp()。
      //
      // ⚠️ 配对刚成功时端口文件可能还没写成（或刚被清掉），`switchToTcp()`
      //    会因此拿不到端口而失败。这**不该**让配对报错（配对本身是成功的），
      //    但也不能再像以前那样静默 —— 结果要如实写进 stderr，让 Kotlin 侧
      //    和设置页能区分「配对成功且已切固定端口」与「配对成功但还得靠无线调试」。
      const switched = await switchToTcp();
      if (!switched) {
        process.stderr.write(
          `${TAG} 配对成功，但固定端口 ${PORT} 未切成功 —— ` +
            `当前只能靠无线调试，关掉 Wi-Fi 或无线调试开关后特权通道会失效。` +
            `可在设置页点「切到固定端口」重试。\n`
        );
      }
    }
    process.exitCode = rc;
    return;
  }

  // ── 子命令：切到固定 TCP 端口（供设置页按钮 / 配对后自动调用）──
  //
  // ⚠️ 可选第二个参数是 **Kotlin 侧刚发现的无线调试端口**。它必须能传进来：
  //    「用户刚关掉无线调试，想让 App 切到固定端口」这个场景下，端口文件是空的
  //    （被 `onServiceLost` 删了），而只有 Kotlin 侧的 `NsdManager` 能重新发现
  //    （Node 发不出 UDP 组播）。收到就用它，收不到就走文件 + 重试。
  if (argv[0] === '--switch-tcp') {
    // ⚠️ 这里**不再**自己检查密钥 —— `switchToTcp()` 现在负责这件事，而且它把
    //    检查排在了「5555 是否已就绪」**之后**：固定端口本来就好的时候，
    //    密钥有没有授权都不该妨碍我们如实返回「已就绪」。
    //    （在这里提前 `ensureKey()` 还会有一个副作用：每次探测都去生成/导入密钥。）
    const hint = Number.parseInt(argv[1] ?? '', 10);
    process.exitCode = (await switchToTcp(Number.isInteger(hint) && hint > 0 ? hint : null)) ? 0 : 1;
    return;
  }

  // ⚠️ 这里**曾经**有一个 `--discover` 子命令（打印密钥来源与端口文件内容，
  // 供设置页显示）。它已被删除，理由有两条：
  //   1. **没有任何调用者**：设置页的状态走的是宿主半插件的 HTTP 路由
  //      （`dsh-android-mobile`），那条路由自己 spawn runner 跑真实探测，
  //      从不经过这个子命令。留着等于一条永不执行的死路径。
  //   2. 它的输出会**误导排查**：它把 `readWirelessPort()` 读到的文件内容
  //      当成「当前可用端口」报出去，而那个文件可能已经过期（端口是随机且
  //      每次重启都变的）。伪造出一个精确但陈旧的结论，比不报更坏事。
  // 需要无线调试状态时看日志里的 `[mdns] 解析到 … 端口 N` 与 `[dsh-adb]` 行。

  const command = argv[0];
  if (command === undefined || command === '') {
    process.stderr.write(`${TAG} 用法: node dsh-android-shell.mjs <command> [cwd]\n`);
    process.exitCode = 2;
    return;
  }
  const cwd = argv[1];
  const wrapped = withCwd(command, cwd);

  // ── 诊断采集：每条通道各自记下「为什么没走成」──
  // 旧实现只记通道 1 的原因，导致四种完全不同的处境在设置页长得一模一样
  // （见 classifyFallback 的表）。这里几条通道分别记账，最后收敛成一条可执行的结论。
  const diag = { key: 'ok', wireless: '', shizuku: '' };

  // ── 通道 0：Shizuku（不依赖 adbd，优先级最高）──────────────
  //
  // ⚠️ **排在 legacy 之前是刻意的。** 它绕开了 legacy 与无线调试的**全部**前提：
  //    不需要 `adb tcpip 5555`、不需要 adbkey 被设备信任、不需要无线调试开关。
  //    对 Android 10 及以下（没有无线调试，且 `persist.adb.tcp.port` 写不进去
  //    ⇒ 每次重启都要 PC 重来一次）的用户，这往往是唯一可行的路。
  //
  // ⚠️ 放在 `ensureKey()` / 密钥诊断**之后**：这条通道与密钥无关，
  //    但下面 key 那几行是**无条件**执行的诊断记账（不是通道门卫），
  //    所以顺序不影响 Shizuku 的可用性 —— 只是要保持诊断表完整。
  if (SHIZUKU_SOCKET === '') {
    diag.shizuku = 'not-configured';
  } else {
    const r = await runViaShizuku(wrapped, cwd);
    if (r !== null) {
      process.stderr.write(`${TAG} 通道: Shizuku（uid 2000）\n`);
      return await finish(r.stdout, r.stderr, r.code);
    }
    // 失败原因已由 runViaShizuku 自己写进 stderr（超时/桥拒绝/解析失败），
    // 这里只记一个笼统的标记 —— 细分的 kind 由 Kotlin 侧探测给出，比在这里猜准。
    diag.shizuku = 'unavailable';
  }

  // 先保证有密钥：可能来自 PC 中转（设备已信任），也可能现场生成（等待配对授权）。
  // 放在通道尝试**之前**，这样两条通道用的是同一把，诊断信息也准确。
  const hasKey = ensureKey();

  if (!hasKey) {
    diag.key = 'missing';
  } else {
    const k = loadKey();
    if (k !== null) {
      if (isKeyRevoked(k)) diag.key = 'revoked';
      else if (!keyMayBeAuthorized()) diag.key = 'unauthorized';
    }
  }

  // ── 通道 1：legacy 5555（自实现协议，最快）──
  // 先试它是因为成本极低：端口没监听时是立刻 ECONNREFUSED，不拖慢。
  let legacyReason = hasKey ? '' : '没有 ADB 密钥';
  let conn = null;
  try {
    conn = await connect();
  } catch (e) {
    legacyReason = e.message;
  }
  if (conn !== null) {
    try {
      const { stdout, stderr, exitCode } = await conn.shell(wrapped);
      conn.close();
      process.stderr.write(`${TAG} 通道: legacy 127.0.0.1:${PORT}\n`);
      return await finish(stdout, stderr, exitCode);
    } catch (e) {
      conn.close();
      legacyReason = `执行失败: ${e.message}`;
    }
  }

  // ── 通道 1.5：legacy 不通但无线调试可用 → 切到 TCP 模式再走 legacy ──
  //
  // 这一步让「重启后 5555 消失」自动恢复：只要无线调试还开着，就顺手切回
  // 固定的 5555，之后连无线调试开关都不用管了。稳态下（5555 已在听）
  // 通道 1 已经返回，走不到这里，所以没有额外开销。
  //
  // ⚠️ 同样要 `keyMayBeAuthorized()` 守卫：这里用内置 adb 去 `connect` 无线调试
  // 端口，密钥未授权时**内置 adb 自己会弹授权窗**，拦不住，只能提前不进这条分支。
  //
  // ⚠️⚠️ **不要给这一段加「端口文件里得有值」的前置条件。** 那个文件正是
  //    `AdbMdns.onServiceLost` 在**无线调试被关掉时删除**的，而「无线调试被关掉、
  //    只剩 5555 可救」恰恰是最需要切一次的场景 —— 加了前置条件就形成循环依赖：
  //
  //        要恢复 5555 → 需要一条活的 adb 连接 → 只有无线调试点 → 文件却是空的
  //
  //    所以端口为空时**照样进去**：`switchToTcp()` 自己会先探 5555、再读文件、
  //    最后接受调用方传进来的端口。它返回 false 时这一段什么都不做，成本只有
  //    一次 TCP 探测。
  if (conn === null && hasKey && ADB_BIN !== '' && fs.existsSync(ADB_BIN) && keyMayBeAuthorized()) {
    const wlPort = readWirelessPort();
    // 只把「当前活着的那个端口」当线索用；为空就是 null，仍让 switchToTcp 自己决断。
    if (await switchToTcp(wlPort)) {
      try {
        conn = await connect();
      } catch (e) {
        legacyReason = `切 TCP 后仍连不上: ${e.message}`;
      }
      if (conn !== null) {
        try {
          const { stdout, stderr, exitCode } = await conn.shell(wrapped);
          conn.close();
          process.stderr.write(`${TAG} 通道: legacy 127.0.0.1:${PORT}（自动切自无线调试）\n`);
          return await finish(stdout, stderr, exitCode);
        } catch (e) {
          conn.close();
          legacyReason = `执行失败: ${e.message}`;
        }
      }
    }
  }

  // ── 通道 2：无线调试（内置 adb + NsdManager 发现的 TLS 端口）──
  // ⚠️ 守卫：内置 adb 遇到未授权的密钥**自己也会弹「允许 USB 调试吗」**，而它
  // 是外部进程，我们拦不住它的 AUTH 行为。所以只能在进这条通道**之前**就挡住 ——
  // 密钥没被接受过就干脆不试，宁可回落成 app 身份，也不去弹用户的窗。
  if (ADB_BIN === '' || !fs.existsSync(ADB_BIN)) {
    diag.wireless = 'adb-missing';
  } else if (!hasKey) {
    diag.wireless = 'no-port'; // 没有密钥时通道 2 也不成立，但原因已由 key 那条报出
  } else if (!keyMayBeAuthorized()) {
    diag.wireless = 'key-blocked';
  } else if (!syncKeyForAdb()) {
    diag.wireless = 'key-blocked';
  } else {
    const port = readWirelessPort();
    if (port === null) {
      diag.wireless = 'no-port';
    } else {
      const target = `${HOST}:${port}`;
      const r = await runWireless(ADB_BIN, target, wrapped);
      // 连不上就当作这条通道不可用，继续往下回落
      if (r.code !== 1 || !r.stderr.includes('无线调试连接失败')) {
        process.stderr.write(`${TAG} 通道: 无线调试 ${target}\n`);
        return await finish(r.stdout, r.stderr, r.code);
      }
      // ⚠️ 端口连不上 ⇒ 那个文件已经陈旧（无线调试关掉或 adbd 重启换了端口）。
      //    清掉它，避免**接下来每一条**特权命令都先去 connect 一个死端口 ——
      //    那既拖慢速度，又会在 adb server 里留下一条 offline 设备记录。
      clearWirelessPortFile();
      diag.wireless = 'connect-failed';
      process.stderr.write(`${TAG} 无线调试端口 ${port} 连不上，继续回落\n`);
    }
  }

  // ── 通道 3：本地执行（app 身份）────────────────────────────
  const why = classifyFallback(diag, legacyReason);
  process.stderr.write(
    `${TAG} uid 2000 不可用（${why.text}）→ 回落为本地执行\n` +
      `${TAG} 诊断: kind=${why.kind} key=${diag.key} legacy=${legacyReason || 'n/a'} wireless=${diag.wireless || 'n/a'} shizuku=${diag.shizuku || 'n/a'}\n`,
  );
  process.exitCode = await runLocal(command);
}

/**
 * 本 runner 是**一次性**的：跑完就该退。这里钉一道与具体 handle 无关的保险，
 * 保证「有 handle 忘了释放」不会退化成「通知栏一直正在配对」。
 *
 * ⚠️ **定时器必须 `unref()`**：否则它自己就成了那个吊住事件循环的 handle，
 *    本末倒置（这正是探测脚本里 `STILL_ALIVE_AT_3000MS` 那次假阳性的成因）。
 *    `unref()` 之后，正常路径上进程在它触发前就自然退出，它是纯粹的后备。
 *
 * ⚠️ **只在 `main()` settle 之后武装**：不能更早，否则会打断仍在进行的业务。
 *    宽限期 5 秒足够任何管道排空。
 */
function armExitBackstop() {
  const timer = setTimeout(() => {
    // ⚠️ 走到这里说明有 handle 没释放 —— 把现场写进 stderr 便于以后定位，
    //    然后用 `process.exit()` 强制收尾（此时业务已结束、输出已写出，
    //    与 `finish()` 里禁用 `process.exit()` 的场景不同）。
    //
    // ⚠️ `_getActiveHandles()` 是 Node 的内部 API，仅用于诊断，**必须容错**：
    //    它不存在时不能让这条兜底自己抛异常（那会把「不退出」变成「退出码错」）。
    let kinds = 'n/a';
    try {
      kinds = (process._getActiveHandles?.() ?? [])
        .map((h) => h?.constructor?.name ?? typeof h)
        .join(',') || 'n/a';
    } catch {
      // 拿不到就算了，兜底照常生效
    }
    process.stderr.write(
      `${TAG} 业务已结束但事件循环仍不空（${EXIT_GRACE_MS}ms 后强制退出）: ${kinds}\n`,
    );
    process.exit(process.exitCode ?? 0);
  }, EXIT_GRACE_MS);
  timer.unref?.();
}

main()
  .catch((e) => {
    process.stderr.write(`${TAG} 未捕获异常: ${e?.stack ?? e}\n`);
    process.exitCode = 1;
  })
  .finally(armExitBackstop);
