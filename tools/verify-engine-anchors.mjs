#!/usr/bin/env node
/**
 * 引擎补丁锚点体检 —— 专治「补丁写了、程序也跑了、但什么都没改」。
 *
 * ── 为什么必须有这个脚本 ────────────────────────────────────────────────
 *
 * `EngineManager` 里所有对引擎源码的修改都是**字面量锚点补丁**：
 *
 *     原样文本（GUARD）→ 替换成 → 打过补丁的文本（ANCHOR）
 *
 * 设计上刻意做成「找不到锚点就 `Log.w` 跳过，绝不让启动失败」。这个决定是对的
 * （打补丁不该拖垮整个 App），但它带来一个**完全静默的失败模式**：
 *
 *     引擎升级 ⇒ 官方源码改了一个空格 ⇒ 锚点失配 ⇒ 补丁被跳过
 *     ⇒ 修复不生效，而 App 启动完全正常、日志只是一行 W
 *
 * 本项目为此付出过真实代价：v70 的「发图报 agent-busy」查了很久，最后发现
 * 是 `sync()` 那处兜底**根本没打上去**（只补了 open）。那一版的日志里
 * 就有一行「结构与预期不符，跳过该处容错」，但没人去看。
 *
 * 所以这个脚本把「体检」从**运行时日志**提前到**构建期断言**：
 * 拿当前 `engine.bin` 里真实的源码去比对每一个锚点，失配就退出码非 0。
 *
 * ── 用法 ────────────────────────────────────────────────────────────────
 *
 *   node tools/verify-engine-anchors.mjs              # 体检（0 = 全部命中）
 *   node tools/verify-engine-anchors.mjs --verbose    # 打印每个锚点的目标文件
 *
 * 退出码：0 = 全部命中；1 = 有失配（补丁会静默跳过）；2 = 工具自身跑不起来。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ENGINE_BIN = join(ROOT, 'app', 'src', 'main', 'assets', 'engine.bin');
const ENGINE_KT = join(ROOT, 'app', 'src', 'main', 'java', 'com', 'dsh', 'android', 'EngineManager.kt');
const VERBOSE = process.argv.includes('--verbose');

if (!existsSync(ENGINE_BIN)) {
  process.stderr.write(`❌ 找不到 engine.bin: ${ENGINE_BIN}\n`);
  process.exit(2);
}
if (!existsSync(ENGINE_KT)) {
  process.stderr.write(`❌ 找不到 EngineManager.kt: ${ENGINE_KT}\n`);
  process.exit(2);
}

const kotlin = readFileSync(ENGINE_KT, 'utf8');

/**
 * 从 Kotlin 里读出一个字符串常量。
 *
 * ⚠️ 必须同时支持两种写法，本项目两种都用：
 *   ① 单行：  `const val X = "a" + "b"`（Kotlin 允许相邻字符串用 + 拼接）
 *   ② 原样：  `const val X = "....\t\t..."`（含 \t \n 转义）
 * 解析时把相邻的 `"..."` 片段拼起来，再反转义 —— 不能只取第一个字面量，
 * 否则多行拼接的锚点会被截断成半句，永远匹配不上（脚本自己会产生假阳性）。
 */
function readConst(name) {
  const re = new RegExp(`const val ${name}\\s*=\\s*((?:"(?:[^"\\\\]|\\\\.)*"\\s*\\+?\\s*)+)`, 'm');
  const m = kotlin.match(re);
  if (!m) return null;
  const raw = m[1];
  const parts = [...raw.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1]);
  if (parts.length === 0) return null;
  return parts.join('')
    .replace(/\\t/g, '\t')
    .replace(/\\n/g, '\n')
    .replace(/\\r/g, '\r')
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\');
}

/** 补丁清单：锚点常量名 → 它要打的引擎文件（相对 engine 解包根）。 */
const PATCHES = [
  {
    name: 'ATTACH_SYNC_GUARD',
    anchor: 'ATTACH_SYNC_ANCHOR',
    target: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    why: '目录 open 的 EACCES/EPERM 兜底（SELinux 拒绝祖先目录）',
  },
  {
    name: 'ATTACH_FSYNC_GUARD',
    anchor: 'ATTACH_FSYNC_ANCHOR',
    target: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    why: '目录 fsync 的 EINVAL 兜底（只读分区上 fsync 返回 EINVAL）—— v70 的真根因',
  },
  {
    name: 'IMG_TYPE_GUARD',
    anchor: 'IMG_TYPE_ANCHOR',
    target: 'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js',
    why: '放宽发图格式白名单（原版只认 4 个精确 MIME）',
  },
];

/* ── 解包目标文件 ─────────────────────────────────────────────────────── */

const work = mkdtempSync(join(tmpdir(), 'dsh-anchor-'));
const needed = [...new Set(PATCHES.map((p) => p.target))];
const sources = new Map();

try {
  for (const target of needed) {
    // GNU tar 需要 --wildcards；bsdtar 反而会因它报错 ⇒ 探测后重试。
    const args = ['-xzf', ENGINE_BIN, '-C', work];
    let r;
    try {
      r = execFileSync('tar', ['--wildcards', ...args, target], { stdio: 'pipe' });
    } catch {
      r = null;
    }
    if (!r) {
      try {
        execFileSync('tar', [...args, target], { stdio: 'pipe' });
      } catch (e) {
        process.stderr.write(`❌ 从 engine.bin 解出 ${target} 失败\n${e.stderr || ''}\n`);
        process.exit(2);
      }
    }
    const p = join(work, target);
    if (!existsSync(p)) {
      process.stderr.write(`❌ 解包后找不到 ${target}（引擎结构可能变了）\n`);
      process.exit(2);
    }
    sources.set(target, readFileSync(p, 'utf8'));
  }
} finally {
  rmSync(work, { recursive: true, force: true });
}

/* ── 逐个比对 ─────────────────────────────────────────────────────────── */

let failed = 0;
process.stdout.write('引擎补丁锚点体检（对 engine.bin 内的真实源码）\n\n');

for (const p of PATCHES) {
  const guard = readConst(p.name);
  const anchor = readConst(p.anchor);
  const src = sources.get(p.target);

  if (guard === null || anchor === null) {
    process.stdout.write(`  ⚠️ ${p.name}: 无法从 EngineManager.kt 解析出常量\n`);
    failed++;
    continue;
  }

  const alreadyPatched = src.includes(anchor);
  const guardHits = src.includes(guard);

  let verdict;
  let ok;
  if (alreadyPatched) {
    // 资产本身已是打过补丁的（例如手工重打过 engine.bin）—— 不算失败。
    verdict = '✅ 已是补丁后状态（无需再替换）';
    ok = true;
  } else if (guardHits) {
    verdict = '✅ 锚点命中，补丁会生效';
    ok = true;
  } else {
    verdict = '❌ **锚点失配 —— 补丁会被静默跳过，修复不生效**';
    ok = false;
    failed++;
  }

  process.stdout.write(`  ${verdict}\n`);
  process.stdout.write(`     常量: ${p.name}\n`);
  process.stdout.write(`     作用: ${p.why}\n`);
  if (VERBOSE) {
    process.stdout.write(`     目标: ${p.target}\n`);
    process.stdout.write(`     GUARD 前 80 字符: ${JSON.stringify(guard.slice(0, 80))}\n`);
  }
  if (!ok) {
    process.stdout.write(
      `     处理: 引擎升级改了这段源码。请按上面的语义重新对齐 ${p.name}，\n`
      + '           不要直接删掉补丁 —— 那是真机上已经确认过的故障。\n',
    );
  }
  process.stdout.write('\n');
}

if (failed > 0) {
  process.stderr.write(`❌ ${failed} 个锚点失配（补丁会静默跳过）\n`);
  process.exit(1);
}
process.stdout.write('✅ 全部锚点命中\n');
