#!/usr/bin/env node
/**
 * 裁剪大型运行资产，压掉 APK 里 Android 永远用不到的部分。
 *
 * ## 为什么需要这个脚本
 *
 * `app/src/main/assets/` 里约 137 MB 的资产占了 system 包 96% 的体积，而其中相当
 * 一部分是**打包时顺手带上、运行时一条都用不到**的文件：sourcemap、TS 声明、
 * 单元测试、包内文档、Windows 的 `.pdb` 调试符号、win32/darwin 的预编译产物。
 * 实测（2026-11-12）engine.bin 265 MB 解压树里有 128 MB 属于这一类。
 *
 * ## 用法
 *
 * ```bash
 * node tools/trim-runtime-assets.mjs --check   # 只报告会删什么、能省多少
 * node tools/trim-runtime-assets.mjs           # 就地重写两个资产
 * ```
 *
 * 需要 `tar` / `gzip` / `zip` / `unzip`（Linux、macOS 自带；Windows 走 WSL 或 Git Bash）。
 *
 * ## 重写之后必须做的三件事
 *
 * 1. 更新 `catalogs/runtime-assets.lock.json` 里这两个资产的 bytes 与 sha256
 *    （`node tools/check-runtime-assets.mjs` 会逐字节校验，不改就红）。
 * 2. 改 `MainActivity` 的 `assetStamp`，否则老用户 `.stamp` 命中，设备上仍是旧树，
 *    包变小了却不生效。
 * 3. 重跑分层门禁；`treeDigest` 一变 L3 证据即失效，八场景要重采。
 *
 * ## 条目取舍原则
 *
 * 只删「运行时不会打开」的文件。凡是可能被 `require`、被 `readFileSync`、
 * 或被 `child_process` 执行的，一律保留 —— 宁可少省几 MB，也不制造只在
 * 特定路径下才炸的故障。已知的唯一软损失是 `bootstrap` 的 `include/`：
 * 应用内用 node-gyp 编译原生模块会缺头文件，`apt install` 可补。
 */

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import zlib from 'node:zlib';

const ROOT = new URL('..', import.meta.url).pathname.replace(/\/$/, '');
const ASSETS = join(ROOT, 'app/src/main/assets');
const ENGINE = join(ASSETS, 'engine.bin');
const BOOTSTRAP = join(ASSETS, 'bootstrap-aarch64.zip');

/**
 * engine.bin 的裁剪规则。每条都写清「为什么 Android 不会用到」。
 *
 * ⚠️ 不要在这里加任何可能被运行时读取的模式：tar 解包后文件直接进 `engine/`，
 *   引擎 `require()` 的路径解析对目录结构敏感。
 */
const ENGINE_EXCLUDES = [
  // 只给 devtools 用的源码映射，运行时零引用。
  '*.map',
  // TypeScript 类型声明，编译期的东西；引擎跑的是已编译的 JS。
  '*.d.ts', '*.d.mts', '*.d.cts',
  // 包内测试与测试数据（例如 domino 的 html5lib-tests.json 2.2 MB）。
  '*/test/*', '*/tests/*', '*.test.js', '*.spec.js',
  // 包内文档。
  '*/docs/*', '*.md',
  // node-pty 的 Windows 调试符号，一共 19.8 MB，只在 Windows 调试时有用。
  '*.pdb',
  // 非 Android 平台的预编译产物（win32 11.7 MB + 11.2 MB、darwin、linux-x64/arm64）。
  '*/prebuilds/win32-*', '*/prebuilds/darwin-*',
  '*/prebuilds/linux-x64', '*/prebuilds/linux-arm64',
  // @vscode/ripgrep 的 linux-x64 静态二进制 5.5 MB：APK 里另带 Android 的 rg。
  '@vscode/ripgrep-linux-x64/*',
];

/**
 * bootstrap 的裁剪规则。
 *
 * 执行位由 `TermuxArchiveInstaller` 按路径前缀设置（bin/、libexec/、lib/apt/methods/），
 * 符号链接来自包内 `SYMLINKS.txt`，都与 zip 条目属性无关，因此重打包不会改变解包结果。
 */
const BOOTSTRAP_EXCLUDES = [
  // C 头文件 7.1 MB：只在应用内编译原生模块时需要，apt 可装回。
  'include/*',
  // man / doc。
  'share/man/*', 'share/doc/*',
];

const argv = new Set(process.argv.slice(2));
const CHECK = argv.has('--check');

function run(cmd, args, opts = {}) {
  return execFileSync(cmd, args, { encoding: 'utf8', maxBuffer: 1 << 30, ...opts });
}

const sha256 = file => createHash('sha256').update(readFileSync(file)).digest('hex');
const mb = bytes => (bytes / 1048576).toFixed(1);

/** 递归统计目录大小，并按 [判断函数] 归类，用于 --check 报告。 */
function walk(dir, visit) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const info = statSync(path, { throwIfNoEntry: false });
    if (!info) continue;
    if (info.isDirectory()) walk(path, visit);
    else visit(relative(dir, path), info.size);
  }
}

/**
 * 按**设备侧 `TarGz.kt` 的同一套规则**复解析归档，提前发现「tar 自己能读、设备读不了」。
 *
 * 这是本脚本唯一一次真的踩坑留下的护栏：GNU 格式的 'L' 长名条目在设备上被整段跳过，
 * 而 `tar -tzf` 一切正常，症状只在真机首次启动时才暴露。所以规则必须写成断言：
 * 类型只允许 '0'（普通文件）/ '5'（目录）/ NUL，路径由 prefix + name 拼出。
 */
function verifyUstar(file) {
  const stream = zlib.gunzipSync(readFileSync(file));
  const BLOCK = 512;
  let offset = 0;
  let entries = 0;
  let bytes = 0;
  const seen = new Set();
  while (offset + BLOCK <= stream.length) {
    const header = stream.subarray(offset, offset + BLOCK);
    if (header.every(byte => byte === 0)) break; // 归档结束标记
    const name = header.toString('utf8', 0, 100).replace(/\0.*$/, '');
    const prefix = header.toString('utf8', 345, 500).replace(/\0.*$/, '');
    const size = parseInt(header.toString('ascii', 124, 136).replace(/\0.*$/).trim() || '0', 8) || 0;
    const typeFlag = String.fromCharCode(header[156]);
    if (!['0', '5', '\u0000'].includes(typeFlag)) {
      throw new Error(
        `归档含设备解析器不支持的类型 '${typeFlag}'（${name}）——` +
        '必须用 --format=ustar 重打，否则真机上这些条目会被静默跳过',
      );
    }
    const fullName = (prefix ? `${prefix}/${name}` : name).replace(/\/$/, '');
    if (!fullName) throw new Error('归档条目名为空');
    if (fullName !== 'node_modules' && !fullName.startsWith('node_modules/')) throw new Error(`归档条目越界: ${fullName}`);
    if (typeFlag !== '5') { seen.add(fullName); bytes += size; }
    entries++;
    offset += BLOCK + Math.ceil(size / BLOCK) * BLOCK;
  }
  const listed = run('tar', ['-tzf', file]).trim().split('\n').filter(Boolean).length;
  if (entries !== listed) {
    throw new Error(`设备解析器只认出 ${entries} 个条目，tar 列出 ${listed} 个 —— 归档里有多余的类型条目`);
  }
  console.log(`  自检通过：${entries} 个条目 / ${seen.size} 个文件 / 解压 ${mb(bytes)} MB，格式 = POSIX ustar`);
}

function trimEngine() {  const before = statSync(ENGINE).size;
  const work = mkdtempSync(join(tmpdir(), 'dsh-trim-engine-'));
  const unpacked = join(work, 'tree');
  console.log(`engine.bin 原 ${mb(before)} MB，解包中…`);
  run('mkdir', ['-p', unpacked]);
  run('tar', ['-xzf', ENGINE, '-C', unpacked]);
  const out = join(work, 'engine.bin');

  const excludeArgs = ENGINE_EXCLUDES.flatMap(pattern => ['--exclude', pattern]);
  // 归一化 owner/mtime/mode，让同样的输入产出同样的 sha256（锁文件与复采都要它稳定）。
  //
  // ⚠️ `--format=ustar` 是硬要求，不能用 GNU 默认格式。
  //   设备侧的 `TarGz.kt` 是手写解析器：只认 512 字节头里的 name(100) + prefix(155)
  //   + 八进制 size，`typeFlag` 只处理 '0' 和 '5'，其它类型**跳过数据但不重建路径**。
  //   GNU 格式对超过 100 字节的名字写 'L' 长名条目、magic 是 `ustar  \0`，
  //   于是真机上一半条目被跳过 —— 实测症状：解包"成功"，`engine/node_modules/@deepseek-ai/`
  //   下只剩一个目录，引擎报 `Cannot find module '@deepseek-ai/dsh-app-boot'`。
  //   原始资产就是 POSIX ustar（magic `ustar\0` + `00`），必须保持一致。
  run('tar', [
    ...excludeArgs, '--format=ustar',
    '--sort=name', '--numeric-owner', '--owner=0', '--group=0', '--mtime=@0', '--mode=0777',
    '-czf', out, '-C', unpacked, 'node_modules',
  ]);
  verifyUstar(out);
  const after = statSync(out).size;
  console.log(`  裁剪后 ${mb(after)} MB（省 ${mb(before - after)} MB，${(100 * (1 - after / before)).toFixed(0)}%）`);
  if (CHECK) return { before, after, out, work };
  run('cp', [out, ENGINE]);
  rmSync(work, { recursive: true, force: true });
  return { before, after };
}

function trimBootstrap() {
  const before = statSync(BOOTSTRAP).size;
  const work = mkdtempSync(join(tmpdir(), 'dsh-trim-bootstrap-'));
  const unpacked = join(work, 'tree');
  console.log(`bootstrap 原 ${mb(before)} MB，解包中…`);
  run('unzip', ['-q', BOOTSTRAP, '-d', unpacked]);
  for (const pattern of BOOTSTRAP_EXCLUDES) {
    const [head, tail] = pattern.split('/');
    if (tail !== '*') continue;
    rmSync(join(unpacked, head), { recursive: true, force: true });
  }
  // 固定条目时间戳，重打包结果可复现。
  run('find', ['.', '-exec', 'touch', '-h', '-d', '@0', '{}', '+'], { cwd: unpacked });
  const out = join(work, 'bootstrap.zip');
  const names = readdirSync(unpacked);
  run('zip', ['-9', '-q', '-r', '-X', out, ...names], { cwd: unpacked });
  const after = statSync(out).size;
  console.log(`  裁剪后 ${mb(after)} MB（省 ${mb(before - after)} MB）`);
  const symlinks = existsSync(join(unpacked, 'SYMLINKS.txt'));
  if (!symlinks) throw new Error('裁剪后缺少 SYMLINKS.txt —— 解包会丢符号链接，终止');
  // 逐条目 CRC 校验：`zip -r` 出错的概率低，但错了只会表现为「装完缺文件」，值得一次全量校验。
  const test = run('unzip', ['-t', out]);
  if (!/No errors detected/.test(test)) throw new Error('重打包的 bootstrap 未通过 zip 校验');
  console.log('  自检通过：zip CRC 全量校验无误，SYMLINKS.txt 在位');
  if (CHECK) return { before, after, out, work };
  run('cp', [out, BOOTSTRAP]);
  rmSync(work, { recursive: true, force: true });
  return { before, after };
}

function main() {
  if (!existsSync(ENGINE) || !existsSync(BOOTSTRAP)) {
    throw new Error(`资产缺失：${ENGINE} / ${BOOTSTRAP}（大资产不入库，需先按 regen-assets.md 准备）`);
  }
  console.log(`sha256(engine.bin) 原 = ${sha256(ENGINE)}`);
  console.log(`sha256(bootstrap)  原 = ${sha256(BOOTSTRAP)}\n`);

  const engine = trimEngine();
  const bootstrap = trimBootstrap();

  const saved = engine.before + bootstrap.before - engine.after - bootstrap.after;
  console.log(`\n合计可省 ${mb(saved)} MB`);

  if (CHECK) {
    console.log('\n--check 模式未改动资产。候选产物：');
    console.log(`  ${engine.out}`);
    console.log(`  ${bootstrap.out}`);
    return;
  }
  console.log(`\nsha256(engine.bin) 新 = ${sha256(ENGINE)}`);
  console.log(`sha256(bootstrap)  新 = ${sha256(BOOTSTRAP)}`);
  console.log('\n下一步：更新 catalogs/runtime-assets.lock.json、MainActivity 的 assetStamp，然后重编与重采。');
  console.log(`（原始资产备份在 /root/asset-backup/，未纳入版本控制）`);
}

main();
