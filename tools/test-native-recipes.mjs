import test from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { loadRecipes, ROOT, validateRecipes } from './check-native-recipes.mjs';
import { buildNative } from '../native/build.mjs';

const { lock } = loadRecipes();
const clone = () => structuredClone(lock);
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'dsh-native-recipes-'));
  t.after(() => { assert.ok(resolve(root).startsWith(resolve(tmpdir()) + '/dsh-native-recipes-')); rmSync(root, { recursive: true, force: true }); });
  for (const path of ['LICENSE', lock.runtimeAssetLock, ...lock.recipes.map(recipe => recipe.source.path)]) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    copyFileSync(join(ROOT, path), join(root, path));
  }
  return root;
}

test('真实三项源配方及九项外部来源缺口通过L0', () => {
  const report = validateRecipes(lock);
  assert.equal(report.recipes.length, 3);
  assert.equal(report.externalGaps, 9);
  assert.equal(report.assetAccess, false);
  assert.equal(report.androidBuild, 'not-run');
});
test('无大资产fixture可运行，未读取APK或runtime二进制', t => {
  const root = fixture(t);
  assert.equal(validateRecipes(lock, root).status, 'passed');
});
test('源码漂移必须失败', t => {
  const root = fixture(t);
  writeFileSync(join(root, lock.recipes[0].source.path), '// changed');
  assert.throws(() => validateRecipes(lock, root), /源码摘要/);
});
test('配方禁止路径逃逸', () => {
  const changed = clone(); changed.recipes[0].source.path = '../source.c';
  assert.throws(() => validateRecipes(changed), /非法仓库路径/);
});
test('未固定NDK/API/ABI必须失败', () => {
  for (const [key, value] of [['ndkRevision', '28.0'], ['api', 29], ['abi', 'x86_64']]) {
    const changed = clone(); changed.toolchain[key] = value;
    assert.throws(() => validateRecipes(changed), /NDK\/API\/ABI/);
  }
});
test('共享库缺少dl或PIC必须失败', () => {
  for (const flag of ['-ldl', '-fPIC']) {
    const changed = clone(); changed.recipes[0].args = changed.recipes[0].args.filter(arg => arg !== flag);
    assert.throws(() => validateRecipes(changed), /共享库参数/);
  }
});
test('重复配方ID和缺符号契约必须失败', () => {
  const duplicate = clone(); duplicate.recipes[1].id = duplicate.recipes[0].id;
  assert.throws(() => validateRecipes(duplicate), /重复/);
  const missing = clone(); missing.recipes[0].requiredSymbols = [];
  assert.throws(() => validateRecipes(missing), /符号契约/);
});
test('许可证摘要变动必须失败', t => {
  const root = fixture(t); writeFileSync(join(root, 'LICENSE'), 'changed');
  assert.throws(() => validateRecipes(lock, root), /许可证摘要/);
});
test('外部来源缺口和未知完整性必须明确', () => {
  const changed = clone(); changed.externalArtifacts[0].gaps = [];
  assert.throws(() => validateRecipes(changed), /缺口不明确/);
  const unmarked = clone(); delete unmarked.externalArtifacts[0].upstream.integrity;
  assert.throws(() => validateRecipes(unmarked), /完整性缺口/);
});
test('配方观察摘要不能偏离运行资产锁', () => {
  const changed = clone(); changed.externalArtifacts[0].observedArtifactSha256 = 'a'.repeat(64);
  assert.throws(() => validateRecipes(changed), /与运行锁不一致/);
});
test('keyinject必须标明下线而非active缺源', () => {
  const changed = clone(); changed.retired = [];
  assert.throws(() => validateRecipes(changed), /keyinject/);
});
test('缺NDK不使用host clang冒充Android构建', t => {
  const root = fixture(t);
  assert.throws(() => buildNative({ ndk: join(root, 'missing-ndk'), outDir: join(root, 'output') }), /ENOENT/);
  assert.throws(() => buildNative({}), /显式 --ndk/);
});
test('NDK版本错误在执行编译器前阻断', t => {
  const root = fixture(t);
  const ndk = join(root, 'ndk'); mkdirSync(ndk); writeFileSync(join(ndk, 'source.properties'), 'Pkg.Revision = 99.0.0\n');
  assert.throws(() => buildNative({ ndk, outDir: join(root, 'output') }), /NDK版本不匹配/);
});
