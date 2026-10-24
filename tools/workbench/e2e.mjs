#!/usr/bin/env node
/**
 * 端到端校验：拿工作台**真实逻辑**打出来的包，走一遍插件入口。
 *
 * 自测（selftest.mjs）只验纯逻辑；这个脚本验的是「装上去到底能不能用」：
 *   工作台的 Tar/模板 → .tgz → 解包 → lib/index.js 真被 import → 配置真下发
 *
 * 之所以要两层：tar 头写错了 `tar -tzf` 会报错，但 `actions` 里动作名
 * 与素材文件对不上这种错，tar 完全正常、装也"成功"，只是宠物不出来。
 *
 * 用法：node e2e.mjs
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HTML = path.join(HERE, 'DSH桌宠工作台.html');
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'petworkbench-'));

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  ✓ ' + m); } else { fail++; console.log('  ✗ ' + m); } };

// ── 从单文件版里取引擎与模板（测的就是分发的那一份）───────────────
const html = fs.readFileSync(HTML, 'utf8');

const tpl = (id) => {
  const m = html.match(new RegExp(
    '<script type="text/plain" id="tpl-' + id.replace('.', '\\.') + '">\\n([\\s\\S]*?)\\n</script>'
  ));
  if (!m) throw new Error('找不到模板 ' + id);
  return m[1];
};

const engineSrc = [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)]
  .filter(m => !m[1].includes('text/plain'))
  .map(m => m[2])
  .find(s => s.includes('PetEngine'));

// ── 造一个"工作台产物"：3 个动作 ───────────────────────────────────
const PKG = 'dsh-app-pet-e2e';
const VER = '2.3.4';
const ACTIONS = ['idle-1', 'idle-2', 'click-1'];

const EBML = Buffer.from([0x1A, 0x45, 0xDF, 0xA3]);

// 取工作台里真实的 Tar 实现（不复制一份，直接 eval 那段）
const tarMatch = engineSrc.match(/$^/) ? null : null;
const mainSrc = [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)]
  .filter(m => !m[1].includes('text/plain'))
  .map(m => m[2])
  .find(s => s.includes('function packageFiles'));

const tarSrc = mainSrc.match(/const Tar = \(\(\) => \{[\s\S]*?\n\}\)\(\);/);
if (!tarSrc) throw new Error('从主脚本里找不到 Tar 实现');
const Tar = new Function(tarSrc[0] + '\nreturn Tar;')();

const preset = {
  name: '端到端测试宠物',
  description: '由 e2e.mjs 生成，用于校验完整链路。',
  assets: 'assets/webm',
  sources: { 'idle-1': '待机 1', 'idle-2': '待机 2', 'click-1': '点击回应' },
  actions: Object.fromEntries(ACTIONS.map(a => [a, a])),
  pools: { idle: ['idle-1', 'idle-2'], click: ['click-1'] },
  poolWeights: { idle: 10 },
  actionMap: { idle: 'idle-1', working: 'idle-2' },
  moveParams: {},
  appearance: {
    shape: 'path',
    shapePath: 'M0 0 L160 0 L160 142 L0 142 Z',
    shapeViewBox: '0 0 160 142',
    size: 160, boxWidth: 160, boxHeight: 142,
    cornerRadius: 16, showDot: false, bgOpacity: 0, bg: '#00000000',
    avatar: 'none', avatarScale: 1, flipOnTurn: true,
    throwEnabled: true, edgeSnap: false,
  },
  move: { enabled: false, intervalSec: 30, minDp: 40, maxDp: 120 },
  statusAutoHideMs: 4000,
  workStatusTexts: [['发呆'], ['想着呢'], ['干活'], ['等你'], ['出错'], ['有结果'], ['成了']],
};

const packageJson = {
  name: PKG, version: VER,
  description: preset.description,
  type: 'module', main: './lib/index.js',
  exports: {
    '.': { default: './lib/index.js' },
    './cordis.patch.yml': './cordis.patch.yml',
    './package.json': './package.json',
  },
  files: ['lib', 'presets', 'assets/webm', 'cordis.patch.yml', 'README.md'],
  keywords: ['dsh', 'dsh-plugin', 'DeepSeek', 'desktop-pet', 'pet', 'android', 'bubble'],
  license: 'MIT',
  dsh: { manifestVersion: 1, bundle: { patch: './cordis.patch.yml' } },
};

const fill = s => s.split('__PKG_NAME__').join(PKG);
const enc = new TextEncoder();
const files = [
  { name: 'package/package.json', data: enc.encode(JSON.stringify(packageJson, null, 2) + '\n') },
  { name: 'package/cordis.patch.yml', data: enc.encode(fill(tpl('patch.yml'))) },
  { name: 'package/lib/index.js', data: enc.encode(fill(tpl('index.js'))) },
  { name: 'package/presets/pet.json', data: enc.encode(JSON.stringify(preset, null, 2) + '\n') },
  { name: 'package/README.md', data: enc.encode(fill(tpl('readme'))) },
  { name: 'package/LICENSE', data: enc.encode(fill(tpl('license'))) },
];
for (const a of ACTIONS) {
  const b = Buffer.alloc(200 * 1024);
  EBML.copy(b, 0);
  for (let i = 4; i < b.length; i++) b[i] = (i * 31) & 0xFF;
  files.push({ name: 'package/assets/webm/' + a + '.webm', data: new Uint8Array(b) });
}

const tgz = path.join(TMP, `${PKG}-${VER}.tgz`);
fs.writeFileSync(tgz, zlib.gzipSync(Tar.build(files)));

console.log('① 包结构');
{
  const listing = execFileSync('tar', ['-tzf', tgz], { encoding: 'utf8' }).trim().split('\n');
  for (const need of ['package/package.json', 'package/cordis.patch.yml',
    'package/lib/index.js', 'package/presets/pet.json']) {
    ok(listing.includes(need), need);
  }
  ok(listing.filter(f => f.endsWith('.webm')).length === ACTIONS.length,
    `素材 ${ACTIONS.length} 个`);

  const out = path.join(TMP, 'x');
  fs.mkdirSync(out, { recursive: true });
  execFileSync('tar', ['-xzf', tgz, '-C', out]);
  ok(fs.existsSync(path.join(out, 'package/package.json')), '解包成功（系统 tar 认得）');
}

console.log('② 包内一致性');
const root = path.join(TMP, 'x', 'package');
{
  const pj = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  ok(pj.name === PKG, 'package.json name');
  ok(pj.dsh?.bundle?.patch === './cordis.patch.yml', 'dsh.bundle.patch');
  ok(!('engines' in pj), '不声明 engines（否则可能被判 incompatible）');
  ok(!('peerDependencies' in pj), '不声明 dsh peerDeps（同上）');

  const patch = fs.readFileSync(path.join(root, 'cordis.patch.yml'), 'utf8');
  // YAML 里是 `- id: 值`，别漏了列表项的 `- `
  const ids = [...patch.matchAll(/^\s*-\s*id:\s*(\S+)\s*$/gm)].map(m => m[1]);
  ok(ids.length === 1 && ids[0] === PKG, 'cordis.patch.yml 的 id 与包名一致');

  const lib = fs.readFileSync(path.join(root, 'lib/index.js'), 'utf8');
  ok(!lib.includes('__PKG_NAME__'), 'lib 里没有残留占位');
  ok(patch.indexOf('__PKG_NAME__') < 0, 'patch.yml 里没有残留占位');

  const p = JSON.parse(fs.readFileSync(path.join(root, 'presets/pet.json'), 'utf8'));
  const onDisk = fs.readdirSync(path.join(root, 'assets/webm'))
    .filter(f => f.endsWith('.webm')).map(f => f.replace(/\.webm$/, '')).sort();
  ok(JSON.stringify(Object.keys(p.actions).sort()) === JSON.stringify(onDisk),
    'actions 与磁盘上的素材一一对应');
}

console.log('③ 插件入口真跑');
{
  const home = path.join(TMP, 'home');
  fs.mkdirSync(home, { recursive: true });
  process.env.DSH_HOME = home;

  const mod = await import(path.join(root, 'lib/index.js'));
  ok(mod.name === PKG, 'export const name');
  ok(typeof mod.apply === 'function', 'export function apply');

  const tools = [];
  mod.apply({ tools: { register: t => tools.push(t) } });
  ok(tools.length === 1 && tools[0].name === 'deskpet', '注册了 deskpet 工具');

  // 等启动下发（BOOT_DELAY_MS 5s）
  await new Promise(r => setTimeout(r, 6500));

  const cmdPath = path.join(home, 'bubble-cmd.json');
  if (fs.existsSync(cmdPath)) {
    const cmd = JSON.parse(fs.readFileSync(cmdPath, 'utf8'));
    const got = Object.keys(cmd.fields.actions).sort();
    ok(JSON.stringify(got) === JSON.stringify([...ACTIONS].sort()),
      '下达的动作集与素材一致');
    ok(cmd.fields.pools?.idle?.length === 2, 'pools.idle 传下去了');
    const v = cmd.fields.actions[got[0]].video;
    ok(typeof v === 'string' && path.isAbsolute(v) && fs.existsSync(v),
      '素材被解析成存在的绝对路径');
  } else {
    ok(false, '没有生成 bubble-cmd.json（下发链路断了）');
  }
}

console.log('');
console.log(fail === 0 ? `全部通过：${pass} 项` : `通过 ${pass} 项，失败 ${fail} 项`);
console.log('临时目录: ' + TMP);
process.exit(fail === 0 ? 0 : 1);
