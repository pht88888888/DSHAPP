#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { digest, loadRecipes, ROOT } from '../tools/check-native-recipes.mjs';

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, ...options });
  if (result.error || result.status !== 0) throw new Error(`${command}: ${result.error?.message || result.stderr || result.stdout || result.status}`);
  return result.stdout;
}
export function buildNative({ ndk, outDir, hostTag, root = ROOT }) {
  if (!ndk || !outDir) throw new Error('需要显式 --ndk DIR 和 --out-dir DIR，不自动下载、不写运行资产');
  const { lock } = loadRecipes(root);
  const ndkRoot = resolve(ndk);
  const revision = readFileSync(join(ndkRoot, 'source.properties'), 'utf8').match(/^Pkg\.Revision\s*=\s*(\S+)/m)?.[1];
  if (revision !== lock.toolchain.ndkRevision) throw new Error(`NDK版本不匹配: ${revision}; 需要${lock.toolchain.ndkRevision}`);
  const tag = hostTag || ({ linux: 'linux-x86_64', darwin: 'darwin-x86_64', win32: 'windows-x86_64' })[process.platform];
  if (!lock.toolchain.supportedHostTags.includes(tag)) throw new Error(`不支持host tag: ${tag}`);
  const bin = join(ndkRoot, 'toolchains/llvm/prebuilt', tag, 'bin');
  const compiler = join(bin, `${lock.toolchain.compiler}${tag.startsWith('windows') ? '.cmd' : ''}`);
  const readelf = join(bin, `llvm-readelf${tag.startsWith('windows') ? '.exe' : ''}`);
  const output = resolve(outDir);
  const rel = relative(resolve(root), output);
  // 仓库内仅接受ignored build/；临时输出必须在仓库外。不接收assets或native源码目录。
  if (rel === '' || (!isAbsolute(rel) && rel !== '..' && !rel.startsWith('../') && !rel.startsWith('build/'))) throw new Error('输出只能在仓库build/或仓库外临时目录，禁止写运行assets/源码');
  if (existsSync(output)) throw new Error('输出目录必须不存在，避免覆盖既有产物');
  const compilerVersion = run(compiler, ['--version']);
  mkdirSync(output, { recursive: true });
  const reports = [];
  for (const recipe of lock.recipes) {
    const artifact = join(output, recipe.output);
    const source = resolve(root, recipe.source.path);
    const args = recipe.args.map(arg => arg === '{source}' ? source : arg === '{output}' ? artifact : arg);
    run(compiler, args, { cwd: root, env: { ...process.env, SOURCE_DATE_EPOCH: '0', LC_ALL: 'C' } });
    const bytes = readFileSync(artifact);
    if (bytes.length < 64 || bytes.subarray(0, 4).toString('hex') !== '7f454c46' || bytes[4] !== 2 || bytes[5] !== 1 || bytes.readUInt16LE(18) !== 183) throw new Error(`${recipe.id}: 不是little-endian ELF64 AArch64`);
    const type = bytes.readUInt16LE(16);
    if ((recipe.kind === 'shared-library' && type !== 3) || (recipe.kind === 'executable' && ![2, 3].includes(type))) throw new Error(`${recipe.id}: ELF类型错误`);
    const symbols = run(readelf, ['--symbols', '--wide', artifact]);
    for (const symbol of recipe.requiredSymbols) if (!symbols.split('\n').some(line => new RegExp(`\\bGLOBAL\\b.*\\b(?:DEFAULT|PROTECTED)\\b\\s+(?!UND\\b)\\S+\\s+${symbol}(?:@[^\\s]+)?\\s*$`).test(line))) throw new Error(`${recipe.id}: 缺少已定义全局符号${symbol}`);
    const actual = digest(bytes);
    reports.push({ id: recipe.id, output: artifact, bytes: bytes.length, sha256: actual, abi: 'arm64-v8a', requiredSymbols: recipe.requiredSymbols, command: { executable: compiler, args }, existingAssetSha256: recipe.verification.existingAssetSha256, byteIdenticalToObservedArtifact: actual === recipe.verification.existingAssetSha256 });
  }
  const report = { schemaVersion: 1, status: 'passed', ndkRevision: revision, compilerVersion: compilerVersion.trim(), recipes: reports, scope: 'Build and ELF contracts only; no installation or Android runtime behavior test.' };
  writeFileSync(join(output, 'native-build-report.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  return report;
}
function main() {
  const values = {};
  const args = process.argv.slice(2);
  if (!args.length || args.includes('--help')) { console.log('node native/build.mjs --ndk DIR --out-dir NEW_DIR [--host-tag linux-x86_64]'); return; }
  for (let i = 0; i < args.length; i += 2) {
    const name = { '--ndk': 'ndk', '--out-dir': 'outDir', '--host-tag': 'hostTag' }[args[i]];
    if (!name || !args[i + 1]) throw new Error(`非法参数: ${args[i]}`);
    values[name] = args[i + 1];
  }
  console.log(JSON.stringify(buildNative(values), null, 2));
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
