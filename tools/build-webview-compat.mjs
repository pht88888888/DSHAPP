#!/usr/bin/env node
/**
 * WebView 降级前端构建器 —— `build-webview-compat.ps1` 的 Node 移植版。
 *
 * ── 为什么要有这一份 ────────────────────────────────────────────────────
 *
 * 原脚本是 PowerShell（Windows 开发机用）。而本项目现在也在 Linux 服务器上
 * 构建（见 `docs/agents/server-build-env.md`），那台机器**没有 pwsh**。
 * 于是一个关键资产（`app/src/main/assets/webview-compat.zip`）在服务器上
 * **无法重新生成** —— 现实后果见下面「⚠️ 这个资产坏了会怎样」。
 *
 * 两个脚本**必须保持行为一致**。改任意一边都要同步另一边，并在两边的
 * 版本沿革注释里都记一笔。
 *
 * ── ⚠️ 这个资产坏了会怎样（真实踩过的坑）──────────────────────────────
 *
 * `EngineManager.ensureWebViewCompat()` **无条件**把 zip 里的三棵树覆盖到设备上
 * （不看设备 WebView 版本）。其中 `mobileui/` 这一棵树会覆盖
 * `$DSH_HOME/mobile-ui/dsh-android-mobile/lib/client.js` —— 也就是本项目
 * **所有移动端 UI 修复**所在的那份源码。
 *
 * 于是只要 zip 是旧的、而 `-CompatVersion` 又没 bump，设备上就会发生：
 *
 *     1. `ensureMobileUi()`   按 `MOBILE_UI_VERSION` 解出**新**的 client.js
 *     2. `ensureWebViewCompat()` 判「戳一致 → 跳过」或写入**旧**的降级产物
 *     3. 结果：新修复被旧降级产物整个覆盖，而原生日志一切正常
 *
 * 这个失败是**完全静默**的：`已安装移动端 UI 插件 1.0.0-105` 照样打，
 * 磁盘上的 client.js 却还是老的。本项目在 v4~v34 之间为同一件事记了十几次。
 *
 * ── 用法 ────────────────────────────────────────────────────────────────
 *
 *   node tools/build-webview-compat.mjs                      # 默认 chrome74 / v36
 *   node tools/build-webview-compat.mjs --target chrome74 --compat-version 36
 *   node tools/build-webview-compat.mjs --dry-run            # 只报告不写 zip
 *
 * 退出码：0 = 成功，1 = 失败（构建问题），2 = 工具自身跑不起来。
 */
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/* ── 参数 ─────────────────────────────────────────────────────────────── */

function argOf(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const TARGET = argOf('target', 'chrome74');
// ⚠️ **改 mobile-ui 源码就必须 +1。** 判据是「与设备上的戳字符串相等」，
//    不是「这次跑过脚本」—— 重跑而不改版本号等于没跑。
// v35：v78/v79 两条移动端修复（光标定位 / 思考行高）。
// v36：v80 三条 UI 缺陷（过程行标题抽搐 / 统计条字号 / 安全刷新）。
//      降级产物里必须带上 mpReloadUi + reloadWebUi + 新的统计条选择器，
//      否则老内核上「刷新」仍会走 location.reload() 丢 token。
const COMPAT_VERSION = argOf('compat-version', '36');
const DRY_RUN = process.argv.includes('--dry-run');

const ENGINE_BIN = resolve(ROOT, argOf('engine-bin', 'app/src/main/assets/engine.bin'));
const OUT_ZIP = resolve(ROOT, argOf('out-zip', 'app/src/main/assets/webview-compat.zip'));
const MOBILE_UI_SRC = join(ROOT, 'mobile-ui-plugin', 'dsh-android-mobile');

const STAMP_ENTRY = '.compat-stamp';
const PREFIX_DIST = 'dist';
const PREFIX_ENGINE = 'engine';
const PREFIX_MOBILEUI = 'mobileui';

const TARGET_MAJOR = Number(TARGET.replace(/\D/g, ''));

/* ── 小工具 ───────────────────────────────────────────────────────────── */

function log(msg) {
  process.stdout.write(`${msg}\n`);
}

function warn(msg) {
  process.stderr.write(`⚠️ ${msg}\n`);
}

function fail(msg, code = 1) {
  process.stderr.write(`❌ ${msg}\n`);
  process.exit(code);
}

/** 递归列出目录下的文件（不跟随符号链接，避免自引用）。 */
function walk(dir, filter) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    let st;
    try {
      st = statSync(p);
    } catch {
      continue;
    }
    if (st.isDirectory()) out.push(...walk(p, filter));
    else if (st.isFile() && filter(name)) out.push(p);
  }
  return out;
}

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

/** esbuild 的持久安装目录（构建期工具，不进 APK）。 */
const toolDir = join(tmpdir(), 'dsh-esbuild-tool');

/**
 * 跑 esbuild。
 *
 * ⚠️ **直接调原生二进制，不要用 `process.execPath` 去跑 `bin/esbuild`。**
 *    `node_modules/esbuild/bin/esbuild` 是一个**原生 ELF / .exe**（esbuild 是 Go
 *    写的，不是 JS），交给 node 执行会报 `SyntaxError: Invalid or unexpected token`
 *    并把 ELF 头打进错误里 —— 那个报错完全不指向真因。
 * ⚠️ 也不能用 `.bin/esbuild` 那个包装脚本：Windows 是 .cmd、Linux 是 sh，
 *    跨平台分支纯属自找麻烦。直接算出平台对应的原生包路径。
 */
function esbuildBinary() {
  const pkgDir = join(toolDir, 'node_modules');
  const candidates = [];
  const triples = {
    'linux-x64': ['esbuild-linux-64', 'bin/esbuild'],
    'linux-arm64': ['esbuild-linux-arm64', 'bin/esbuild'],
    'darwin-x64': ['esbuild-darwin-64', 'bin/esbuild'],
    'darwin-arm64': ['esbuild-darwin-arm64', 'bin/esbuild'],
    'win32-x64': ['esbuild-win32-x64', 'esbuild.exe'],
  };
  const key = `${process.platform === 'win32' ? 'win32' : process.platform}-${process.arch}`;
  const hit = triples[key];
  if (hit) candidates.push(join(pkgDir, hit[0], hit[1]));
  // 兜底：扫 @esbuild/* 与 esbuild-* 两种打包布局。
  for (const name of readdirSync(pkgDir)) {
    for (const [pkg, rel] of [['@esbuild', null], [null, null]]) {
      if (pkg && name !== pkg) continue;
      if (pkg) {
        for (const sub of readdirSync(join(pkgDir, pkg))) {
          candidates.push(join(pkgDir, pkg, sub, 'bin', process.platform === 'win32' ? 'esbuild.exe' : 'esbuild'));
        }
      }
    }
    if (name.startsWith('esbuild-')) {
      candidates.push(join(pkgDir, name, 'bin', process.platform === 'win32' ? 'esbuild.exe' : 'esbuild'));
      candidates.push(join(pkgDir, name, 'esbuild.exe'));
    }
  }
  return candidates.find((c) => existsSync(c)) || null;
}

const ESBUILD_BIN = esbuildBinary();
if (!ESBUILD_BIN) fail(`找不到 esbuild 原生二进制（在 ${toolDir} 下），请删掉该目录重跑`, 2);

function esbuild(args) {
  const r = spawnSync(ESBUILD_BIN, args, { encoding: 'utf8' });
  if (r.status !== 0) {
    const msg = `${r.stdout || ''}${r.stderr || ''}`.trim();
    throw new Error(`esbuild 失败: ${args.join(' ')}\n${msg}`);
  }
  return `${r.stdout || ''}${r.stderr || ''}`.trim();
}

/* ── 1. 准备 esbuild ──────────────────────────────────────────────────── */

if (!existsSync(join(toolDir, 'node_modules', 'esbuild', 'package.json'))) {
  log('安装 esbuild（构建期工具，不进 APK；仅首次需要）…');
  mkdirSync(toolDir, { recursive: true });
  // ⚠️ 服务器直连 npm 可用（不像 Windows 开发机需要代理）。
  //    若所在地需要代理，在这里加 `--proxy`，不要写死 7897。
  const r = spawnSync('npm', ['install', 'esbuild@0.25.0', '--prefix', toolDir,
    '--no-audit', '--no-fund', '--loglevel=error'], { encoding: 'utf8' });
  if (r.status !== 0) fail(`esbuild 安装失败\n${r.stderr || r.stdout}`, 2);
} else {
  log(`复用已装的 esbuild: ${toolDir}`);
}

/* ── 2. 从 engine.bin 解出 dist 与插件 client bundle ──────────────────── */

if (!existsSync(ENGINE_BIN)) fail(`找不到 engine.bin: ${ENGINE_BIN}`, 2);

const work = join(tmpdir(), 'dsh-webview-compat');
rmSync(work, { recursive: true, force: true });
mkdirSync(work, { recursive: true });

const extract = join(work, 'extract');
mkdirSync(extract, { recursive: true });

log('解出前端 dist 与插件 client bundle…');
// engine.bin 就是 engine.tar.gz（见 docs/agents/regen-assets.md）。
// ⚠️ 两处通配符走**同一次** tar 调用。
// ⚠️ **GNU tar 需要 --wildcards，bsdtar 反而会因它报错。** ps1 版跑在 Windows
//    （bsdtar，原生支持 `*`），本脚本跑在 Linux（GNU tar）。这里探测一次再决定，
//    不要写死 —— 写死 `--wildcards` 会让 Windows 上的 Git Bash 跑不了，
//    写死不带则 Linux 上直接 `Not found in archive`（这个坑移植时立刻踩到）。
const tarBase = ['-xzf', ENGINE_BIN, '-C', extract];
const tarPatterns = [
  'node_modules/@deepseek-ai/dsh-web-frontend/dist',
  'node_modules/@deepseek-ai/*/lib/client*.js',
];
function runTar(extra) {
  const r = spawnSync('tar', [...extra, ...tarBase, ...tarPatterns], { encoding: 'utf8' });
  return r;
}
let tarResult = runTar(['--wildcards']);
if (tarResult.status !== 0 && /unrecognized option|not supported/i.test(`${tarResult.stderr || ''}`)) {
  // bsdtar 不认识 --wildcards：去掉重试（它本来原生支持 `*`）。
  tarResult = runTar([]);
}
if (tarResult.status !== 0) {
  fail(`从 engine.bin 解包失败:\n${`${tarResult.stdout || ''}${tarResult.stderr || ''}`.trim()}`, 1);
}

const dist = join(extract, 'node_modules', '@deepseek-ai', 'dsh-web-frontend', 'dist');
if (!existsSync(dist)) fail('engine.bin 里没找到前端 dist', 1);

const pluginRoot = join(extract, 'node_modules', '@deepseek-ai');
const pluginFiles = walk(pluginRoot, (n) => /^client.*\.js$/.test(n)).filter((p) => {
  // 只要 <pkg>/lib/client*.js，且排除 dsh-web-frontend（那个是 shell，走 dist）
  const parts = p.split(sep);
  const libIdx = parts.lastIndexOf('lib');
  if (libIdx < 0 || libIdx !== parts.length - 2) return false;
  return parts[libIdx - 1] !== 'dsh-web-frontend';
});
if (pluginFiles.length === 0) fail('engine.bin 里没解出任何插件 client bundle，解包通配符可能失效了', 1);
log(`  → 插件 client bundle ${pluginFiles.length} 个`);

/* ── 3. 组待降级清单 ──────────────────────────────────────────────────── */

// Entry 是 zip 内部路径，前缀决定 App 侧解到哪棵树。
const targets = [];

for (const f of walk(dist, (n) => n.endsWith('.js') || n.endsWith('.css'))) {
  const rel = relative(dist, f).split(sep).join('/');
  targets.push({ src: f, entry: `${PREFIX_DIST}/${rel}`, label: 'dist' });
}

for (const f of pluginFiles) {
  const pkg = f.split(sep).slice(-3)[0];
  targets.push({ src: f, entry: `${PREFIX_ENGINE}/${pkg}/lib/${f.split(sep).pop()}`, label: pkg });
}

// App 自己注入的移动端 UI 插件。**不在 engine.bin 里**（源码在仓库、由 mobile-ui.zip 下发），
// 但同样是浏览器要解析的 bundle —— 不降级的话老内核上会静默退回桌面布局。
if (existsSync(MOBILE_UI_SRC)) {
  for (const f of walk(MOBILE_UI_SRC, (n) => /^client.*\.js$/.test(n))) {
    const rel = relative(MOBILE_UI_SRC, f).split(sep).join('/');
    targets.push({ src: f, entry: `${PREFIX_MOBILEUI}/${rel}`, label: 'mobile-ui' });
  }
} else {
  warn(`找不到 ${MOBILE_UI_SRC}，跳过移动端 UI 插件的降级`);
}

/* ── 4. 逐个降级，只留真有语法缺口的 ──────────────────────────────────── */

/*
 * 判据是「同一份输入，--target=esnext 与 --target=$TARGET 的输出是否相同」：
 * esbuild 只在**确实需要降级**时才改写代码，所以输出相同 ⇔ 该文件没有缺口。
 *
 * ⚠️ **不能拿「输入 hash ≠ 输出 hash」当判据** —— esbuild 即使不降级也会重新
 *    格式化，输出必然不同，那会把 61 个文件全收进来。
 * ⚠️ **也不能用正则扫 `??=` / `static {`** —— 正则字面量里就有这些字符，
 *    实测会把 59 个 langs 全误收。交给 esbuild 自己判，它认语法、不认字符串。
 */

const out = join(work, 'out');
mkdirSync(out, { recursive: true });
const probe = join(work, 'probe');
mkdirSync(probe, { recursive: true });

const changed = [];
log(`扫描 ${targets.length} 个 js/css（目标 ${TARGET}）…`);

for (const t of targets) {
  const dst = join(out, t.entry.split('/').join(sep));
  mkdirSync(dirname(dst), { recursive: true });

  const baseline = join(probe, 'baseline');
  esbuild([t.src, '--target=esnext', `--outfile=${baseline}`, '--log-level=error']);
  esbuild([t.src, `--target=${TARGET}`, `--outfile=${dst}`, '--log-level=error']);

  if (sha256(readFileSync(baseline)) !== sha256(readFileSync(dst))) {
    changed.push(t);
  } else {
    rmSync(dst, { force: true });
  }
}

if (changed.length === 0) {
  warn(`没有任何文件需要降级 —— 前端可能已按低版本编译，或 ${TARGET} 给高了`);
}

log(`需要降级的文件（${changed.length} 个）:`);
const groups = new Map();
for (const t of changed) {
  const g = t.entry.split('/')[0];
  if (!groups.has(g)) groups.set(g, []);
  groups.get(g).push(t);
}
for (const [g, items] of groups) {
  log(`  [${g}] ${items.length} 个`);
  for (const t of items) log(`      ${t.entry}`);
}

/* ── 4.5 三棵树的覆盖自检 ─────────────────────────────────────────────── */

/*
 * ⚠️ **这是 v1 那个 bug 的守卫，别删。**
 * v1 只降了 dist，71 个插件 bundle 里 68 个有语法缺口，覆盖率不到 5%，
 * 而产物「看起来」是成功的（zip 生成、exit=0）。所以这里显式检查：
 * 三棵树里**每一棵只要源文件有缺口，就必须有产物**；一棵都没覆盖到也报错。
 */
const treeHasGap = new Set(changed.map((t) => t.entry.split('/')[0]));
const treeScanned = new Set(targets.map((t) => t.entry.split('/')[0]));
for (const tree of treeScanned) {
  if (!treeHasGap.has(tree)) {
    warn(`[${tree}] 这棵树扫描了 ${targets.filter((t) => t.entry.startsWith(`${tree}/`)).length} 个文件，`
      + '但一个都不需要降级 —— 请确认是否符合预期（v1 的病就是某棵树整体漏掉）');
  }
}

/* ── 4.6 语法复检 ─────────────────────────────────────────────────────── */

/*
 * ⚠️ 不能省。「esbuild exit=0」只说明它没报错，而解析门槛是**产物**的属性。
 * 复用 tools/check-frontend-syntax.mjs（acorn 遍历 AST 按特性查 Chrome 版本）。
 *
 * ⚠️ 只复检 .js：check-frontend-syntax.mjs 是 acorn（JS 解析器），喂 .css 必报
 *    `Unexpected character '@'` —— 那是解析器不适用，不是 CSS 有问题。
 */
const checker = join(ROOT, 'tools', 'check-frontend-syntax.mjs');
if (existsSync(checker) && changed.length > 0) {
  log(`语法复检（目标 chrome${TARGET_MAJOR}）…`);
  const bad = [];
  let toolBroke = null;
  let checked = 0;
  for (const t of changed) {
    if (!t.entry.endsWith('.js')) continue;
    checked++;
    const dst = join(out, t.entry.split('/').join(sep));
    const r = spawnSync(process.execPath, [checker, '--target', String(TARGET_MAJOR), dst],
      { encoding: 'utf8' });
    // ⚠️ 必须分开看退出码：1 = 真有语法缺口，2 = 校验工具自己跑不起来。
    //    早先只判 !=0，把「acorn 装不上」也当成语法不合格 —— 环境问题
    //    绝不能伪装成语法问题，否则排查方向直接跑偏。
    if (r.status === 2) {
      toolBroke = `${r.stdout || ''}${r.stderr || ''}`.trim().split('\n').slice(-2).join('\n');
      break;
    }
    if (r.status !== 0) bad.push(`${t.entry}\n${`${r.stdout || ''}${r.stderr || ''}`.trim()}`);
  }
  if (toolBroke) {
    warn(`语法复检未能执行（校验工具本身的问题，不代表产物有问题）：\n${toolBroke}`);
    warn('请手工确认产物，或修好 tools/check-frontend-syntax.mjs 后重跑');
  } else if (bad.length > 0) {
    log(bad.join('\n'));
    fail(`降级产物仍有 chrome${TARGET_MAJOR} 以上语法，见上方清单 —— 换更高的 --target 或升级 esbuild`, 1);
  } else {
    log(`  → ${checked} 个 js 产物全部通过`);
  }
}

/* ── 4.7 运行时 API 审计 ──────────────────────────────────────────────── */

/*
 * ⚠️ **这一步是「完整修复」的地基，别删。**
 * 语法复检只能证明产物能**解析**，证明不了能**跑**：`toSorted`（Chrome 110）、
 * `AbortSignal.prototype.throwIfAborted`（100）都是合法语法，esbuild 也降不了
 * （运行时 API），但老内核上就是 `xxx is not a function`，整块 UI 打不开。
 *
 * 所以从产物反向扫描，和 polyfill 做差集，有缺口就构建失败。
 *
 * ⚠️ **三棵树都要扫**（dist / 插件 / mobile-ui）—— 曾经只扫 dist，
 *    那正是 `toSorted` 漏掉的原因。
 * ⚠️ 审计**源文件**而不是 $out 里的降级产物：$out 只含「有语法缺口」的文件，
 *    而「语法本来就 OK、但用了新 API」的文件不在里面，它们照样会被加载。
 */
const auditor = join(ROOT, 'tools', 'audit-runtime-apis.mjs');
const polyfill = join(ROOT, 'app', 'src', 'main', 'assets', 'webview-polyfill.js');
if (existsSync(auditor)) {
  log(`运行时 API 审计（对照 ${polyfill.split(sep).pop()}）…`);
  const r = spawnSync(process.execPath, [
    auditor, '--polyfill', polyfill, '--target', `chrome${TARGET_MAJOR}`,
    dist, pluginRoot, MOBILE_UI_SRC,
  ], { encoding: 'utf8' });
  const text = `${r.stdout || ''}${r.stderr || ''}`.trim();
  for (const line of text.split('\n').slice(-22)) log(`  ${line}`);
  if (r.status === 2) {
    warn('运行时 API 审计未能执行（工具自身问题，不代表产物有问题）');
  } else if (r.status !== 0) {
    fail('存在未被 polyfill 覆盖的运行时 API（见上方 ❌ 清单）—— 在 webview-polyfill.js 里补齐后重跑', 1);
  } else {
    log('  → 无缺口');
  }
} else {
  warn(`找不到 ${auditor}，跳过运行时 API 审计（缺口将静默漏到用户手上）`);
}

/* ── 5. 打 zip ────────────────────────────────────────────────────────── */

const stamp = `${TARGET}-v${COMPAT_VERSION}`;

if (DRY_RUN) {
  log('');
  log(`--dry-run：不写 ${OUT_ZIP}`);
  log(`  将要写入的戳: ${stamp}`);
  log(`  条目 ${changed.length + 1} 个`);
  process.exit(0);
}

// ⚠️ 用 zip 命令而不是手写 ZIP 编码：条目顺序与压缩率跟 ps1 版不必逐字节相同，
//    因为 App 侧只读**条目内容**和戳字符串，不校验 zip 的字节哈希。
//    `${'`'}` 这一层是为了避免与 Windows 侧产物的条目顺序差异引起误解。
const stage = join(work, 'stage');
mkdirSync(stage, { recursive: true });
for (const t of changed) {
  const src = join(out, t.entry.split('/').join(sep));
  const dst = join(stage, t.entry.split('/').join(sep));
  mkdirSync(dirname(dst), { recursive: true });
  copyFileSync(src, dst);
}
writeFileSync(join(stage, STAMP_ENTRY), stamp, 'ascii');

/*
 * ⚠️ **不能直接 rmSync(OUT_ZIP) 再写。** 本仓库的 `app/src/main/assets/*.zip`
 * 在 `source-main` 这个 worktree 里是**软链**，指向 `source/` 里的真实文件
 * （两个 worktree 共享同一份资产，见 README 的目录表）。
 * 直接删会把软链本身删掉、换成一个普通文件 —— 表面看不出来，但从此
 * 两个 worktree 各存一份资产，改一处另一处不生效，排查起来极其费时。
 * 所以：先解析软链的真实路径，把内容写到那里，再确保软链还在。
 */
const realZip = existsSync(OUT_ZIP) && statSync(OUT_ZIP).isSymbolicLink() === false
  ? OUT_ZIP
  : OUT_ZIP;
// 用 readlink 判断是否软链（statSync 对软链返回目标的信息，看不出是不是链）
let zipTarget = OUT_ZIP;
try {
  const lst = execFileSync('ls', ['-l', OUT_ZIP], { encoding: 'utf8' });
  const arrow = lst.indexOf(' -> ');
  if (arrow >= 0) zipTarget = lst.slice(arrow + 4).trim();
} catch {
  /* 不存在就按原路径写 */
}

const tmpZip = join(work, 'out.zip');
try {
  execFileSync('zip', ['-r', '-X', '-q', tmpZip, '.'], { cwd: stage });
} catch (e) {
  fail(`打 zip 失败: ${e.message}`, 1);
}
// 写到软链的**目标**位置，软链本身保持不动。
copyFileSync(tmpZip, zipTarget);
if (zipTarget !== OUT_ZIP) {
  log(`（已写入软链目标: ${zipTarget}）`);
}

const size = statSync(OUT_ZIP).size;
log('');
log(`已生成: ${OUT_ZIP}`);
log(`  条目 ${changed.length + 1} 个，${size.toLocaleString()} 字节（${(size / 1048576).toFixed(2)} MB）`);
log('');
log(`⚠️ 戳是 ${stamp}（App 侧从 zip 里读，无需改 Kotlin 常量）`);
log(`⚠️ 确认 MainActivity.MIN_WEBVIEW_MAJOR = ${TARGET_MAJOR} 与本次 --target 一致`);
