#!/usr/bin/env node
/**
 * dsh-android-root.mjs —— root（uid 0）执行通道。
 *
 * ## 与 `dsh-android-shell.mjs` 的分工
 *
 * | 通道 | 身份 | 拿不到时的行为 |
 * |---|---|---|
 * | `bash` | 应用 uid（如 10323） | —— |
 * | `shell` | uid 2000（shell） | 回落为本地执行（静默） |
 * | `root`（本脚本） | **uid 0** | **失败**（不回落） |
 *
 * ⚠️ **本脚本刻意不回落**。`shell` 那条通道可以回落，因为「app 身份」本身是个
 * 有意义的降级（很多命令照跑）。而 root 是**显式提权请求**：调用方要的是
 * 「只有 root 能做到」的那些事（读写 `/data`、改 SELinux、`mount`、`iptables`…）。
 * 静默以 app 身份跑一遍，会得到一个**退出码 0 但什么都没发生**的结果 ——
 * 那正是本项目的 `shell` 通道反复踩到的坑（见其头部对照表）。所以这里宁可
 * 明确报错，让模型知道"没成功"，而不是给它一个假的成功。
 *
 * ## 怎么拿到 uid 0
 *
 * 两种途径，按顺序试：
 *
 * 1. **`su -c`**：设备上装了 `su`（Magisk / KernelSU / SuperSU 等）时最直接。
 *    ⚠️ 这是唯一会弹授权框的途径，且**弹框期间进程会一直阻塞**，
 *    所以必须带超时（见 [SU_TIMEOUT_MS]）。
 * 2. **`sh -c` 直连 adbd 的 root 模式**：不适用 —— adbd 即使以 root 跑，
 *    shell 服务仍降权到 uid 2000。故不实现。
 *
 * 因此实际上就是 `su` 一条路。这也是为什么**宿主侧必须先探测**（见
 * `RootAccess.detect()`）：没 root 的设备上根本不挂这个工具。
 *
 * ## 为什么用 `su -c` 而不是 `su` + stdin
 *
 * 各家的 `su` 对 stdin 的处理不一致（有的要求 tty、有的把 stdin 当密码读），
 * 而 `-c` 是 **Android 事实标准**（`su -c 'cmd'`），Magisk / KernelSU 全都支持。
 * 走 stdin 会在部分实现上把命令内容当成密码，静默失败。
 *
 * ## 退出码约定
 *
 * 与 `RootCommandEnvelope` 那类做法同源：**先把身份验掉，再跑真命令**。
 * 但这里更进一步 —— 用**哨兵前缀**把「提权失败」与「命令自己失败」分开，
 * 因为两者的处置方式完全不同：
 *
 * | 退出码 | 含义 |
 * |---|---|
 * | 0 | 命令成功 |
 * | 命令自己的退出码 | 提权成功，命令自己返回非 0 |
 * | **77** | **没拿到 root**（`su` 不存在 / 被拒 / 超时） |
 * | **78** | `su` 拿到了，但**不是 uid 0**（罕见：某些 su 实现会降权） |
 *
 * ⚠️ 77 / 78 是**通道自己的**错误码，与命令无关。调用方（插件）靠它决定
 * 是不是要提示用户「去授权 root」。
 *
 * 用法：`node dsh-android-root.mjs <command> [workdir]`
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const TAG = '[dsh-root]';

/** 提权失败（su 不存在 / 被拒 / 超时）。与命令自身的退出码区分开。 */
const EXIT_NO_ROOT = 77;

/** su 执行了，但拿到的不是 uid 0。 */
const EXIT_NOT_UID0 = 78;

/**
 * `su` 的等待上限（ms）。
 *
 * ⚠️ **不能省**。Magisk 首次调用会弹授权框，用户不点的话 `su` **永久阻塞**
 * （它等的是用户，不是超时）。没有这个上限，`root` 工具的每一次调用都会挂到
 * 引擎自己的超时上限才被杀 —— 期间模型什么都做不了。
 *
 * 取 60s：足够用户在弹框上点「允许」（实测首次授权 3~10 秒），又不至于把
 * 引擎默认的 120s 超时吃满（那样就看不出是谁超时的）。
 */
const SU_TIMEOUT_MS = 60000;

/** 找 `su` 的候选路径。PATH 里那份不一定在（不同 ROM 位置不同）。 */
const SU_CANDIDATES = [
  '/system/bin/su',
  '/system/xbin/su',
  '/sbin/su',
  '/su/bin/su',
  '/debug_ramdisk/su',
  '/magisk/.core/bin/su',
];

/**
 * 定位 `su` 可执行文件。
 * @returns {string|null} 绝对路径；找不到返回 null。
 */
function findSu() {
  for (const p of SU_CANDIDATES) {
    try {
      if (fs.existsSync(p)) return p;
    } catch {
      /* 读不到就试下一个 */
    }
  }
  return null;
}

/**
 * 用 `su -c` 跑一条命令。
 *
 * 命令**不经外层 shell 二次展开**：用 `sh -c` 的 argv 形式交给 `su`，
 * 命令原文由 adbd/su 那侧解释。这里只是把它作为 `-c` 的参数传下去。
 *
 * @param {string} suPath `su` 的绝对路径
 * @param {string} command 原命令
 * @param {string|null} workdir 工作目录（在 root 侧 `cd`，因为 app 侧 `cwd` 可能对 root 不可达）
 * @returns {Promise<{code:number, stderr:string}>}
 */
function runSu(suPath, command, workdir) {
  // ⚠️ 身份校验与真命令**在同一次 su 调用里**完成，分两次调用会：
  //   ① 弹两次授权框（Magisk 每次都弹，除非用户勾了"记住"）；
  //   ② 中间存在时间窗，两次的权限可能不一致。
  //
  // 哨兵行写 stderr：与命令输出天然分流（命令的 stdout 会被调用方完整读走），
  // 不会污染模型看到的正文。
  const guarded = workdir
    ? `cd ${shellQuote(workdir)} || exit 79; ${command}`
    : command;
  const wrapped = `[ "$(id -u)" = 0 ] || { echo "${SENTINEL_NOT_ROOT}" >&2; exit ${EXIT_NOT_UID0}; }; ${guarded}`;

  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(suPath, ['-c', wrapped], {
        stdio: ['ignore', 'inherit', 'inherit'],
        env: process.env,
      });
    } catch (e) {
      resolve({ code: EXIT_NO_ROOT, stderr: `${TAG} 启动 su 失败: ${e.message}\n` });
      return;
    }

    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill('SIGKILL'); } catch { /* 已经退了 */ }
    }, SU_TIMEOUT_MS);

    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ code: EXIT_NO_ROOT, stderr: `${TAG} 执行 su 失败: ${e.message}\n` });
    });

    child.on('exit', (code, signal) => {
      clearTimeout(timer);
      if (timedOut) {
        resolve({
          code: EXIT_NO_ROOT,
          stderr:
            `${TAG} su 等待超时（${SU_TIMEOUT_MS / 1000}s）—— 多半是 root 授权框弹出来了\n` +
            `${TAG} 而没有人点「允许」。请在设备上授权本应用的 root 权限后重试。\n`,
        });
        return;
      }
      const rc = code ?? (signal !== null ? 128 : 1);
      resolve({ code: rc, stderr: '' });
    });
  });
}

/** 哨兵行：su 拿到了但身份不是 uid 0。 */
const SENTINEL_NOT_ROOT = 'DSH_ROOT_IDENTITY_MISMATCH';

/** 用单引号包裹一个 shell 参数；内部的单引号按 POSIX 唯一的正确写法转义。 */
function shellQuote(value) {
  return `'${String(value).replaceAll("'", `'\\''`)}'`;
}

/**
 * 写出全部数据并等它真正 flush 完，再设定退出码。
 *
 * ⚠️ **不要用 `process.exit(code)` 收尾。** 输出走管道时它会在 libc 缓冲区
 * 写回之前就终止进程，**静默截断**大输出。用 `process.exitCode` 让事件循环
 * 自然退出。这是从 `dsh-android-shell.mjs` 继承的教训。
 */
function finish(stderr, code) {
  const done = () => { process.exitCode = code; };
  if (stderr.length === 0) { done(); return; }
  process.stderr.write(stderr, done);
}

async function main() {
  const argv = process.argv.slice(2);
  const command = argv[0];
  const workdir = argv[1] ?? null;

  if (command === undefined || command === '') {
    process.stderr.write(`${TAG} 用法: node dsh-android-root.mjs <command> [workdir]\n`);
    process.exitCode = 2;
    return;
  }

  const suPath = findSu();
  if (suPath === null) {
    // ⚠️ 宿主侧本应先探测过（没 root 就不注册这个工具），走到这里说明
    //   探测通过后又没了（用户卸载了 Magisk？）。如实报错，不回落。
    finish(
      `${TAG} 找不到 su（已试: ${SU_CANDIDATES.join(', ')}）—— 设备当前没有可用的 root。\n`,
      EXIT_NO_ROOT,
    );
    return;
  }

  const { code, stderr } = await runSu(suPath, command, workdir);
  if (stderr.length > 0) process.stderr.write(stderr);

  // 身份不匹配（su 跑起来了但没给 uid 0）：把 78 换成更明确的诊断。
  if (code === EXIT_NOT_UID0) {
    finish(
      `${TAG} su 执行了，但进程身份不是 uid 0（某些 su 实现会降权）—— 本条命令未真正以 root 运行。\n`,
      EXIT_NOT_UID0,
    );
    return;
  }

  finish('', code);
}

main().catch((e) => {
  process.stderr.write(`${TAG} 未捕获异常: ${e?.stack ?? e}\n`);
  process.exitCode = 1;
});
