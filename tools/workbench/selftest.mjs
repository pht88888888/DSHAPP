#!/usr/bin/env node
/**
 * 工作台自测。跑之前先 `python3 build.py`。
 *
 * 为什么要有这个：工作台的逻辑大半在浏览器里，没有浏览器就没法点。
 * 但真正容易错的那些（动作名分配、池与状态映射、原生硬限制、
 * 抠色算法、tar 头结构、预设引用完整性）全是**纯函数**，
 * 可以脱离浏览器断言。
 *
 * 覆盖：
 *   ① engine.js 的抠色 / 羽化 / 背景检测 / 自然排序
 *   ② index.html 的分组 / 命名 / 预设 / 校验 / tar / 预设一致性
 *
 * 用法：node selftest.mjs
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HTML = path.join(HERE, 'DSH桌宠工作台.html');

if (!fs.existsSync(HTML)) {
  console.error('先跑 python3 build.py');
  process.exit(1);
}

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) pass++; else { fail++; console.log('  ✗ ' + m); } };
const eq = (a, b, m) => ok(
  JSON.stringify(a) === JSON.stringify(b),
  m + ' → 期望 ' + JSON.stringify(b) + ' 实得 ' + JSON.stringify(a)
);

// ══════════════════════════════════════════════════════════════════
// 从单文件里取出两个 <script> 块 —— 测的就是实际分发的那份代码
// ══════════════════════════════════════════════════════════════════

const html = fs.readFileSync(HTML, 'utf8');
const blocks = [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)]
  .filter(m => !m[1].includes('text/plain'))
  .map(m => m[2]);

if (blocks.length !== 2) {
  console.error('预期 2 个可执行 script 块（engine + 主脚本），实得 ' + blocks.length);
  process.exit(1);
}
const [engineSrc, mainSrc] = blocks;

// ══════════════════════════════════════════════════════════════════
// DOM 桩 —— 目的不是渲染，是让主脚本能跑起来
// ══════════════════════════════════════════════════════════════════

const store = new Map();
const mkEl = (tag = 'div') => {
  const n = {
    tagName: tag.toUpperCase(),
    _text: '', _html: '', value: '', disabled: false,
    className: '', title: '', src: '', href: '', download: '',
    width: 0, height: 0, style: {}, dataset: {}, children: [],
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); },
      toggle(c, on) { on ? this._s.add(c) : this._s.delete(c); },
      contains(c) { return this._s.has(c); },
    },
    get textContent() { return this._text; },
    set textContent(v) { this._text = String(v); this.children = []; },
    get innerHTML() { return this._html; },
    set innerHTML(v) { this._html = String(v); this.children = []; },
    appendChild(c) { this.children.push(c); return c; },
    replaceWith(n) { this._replacedBy = n; },
    remove() {}, addEventListener() {}, click() {}, focus() {},
    setAttribute() {}, removeAttribute() {},
    getContext: () => ({
      clearRect() {}, drawImage() {}, fillRect() {}, putImageData() {},
      getImageData: () => ({ data: new Uint8ClampedArray(4) }),
    }),
    querySelector(sel) {
      if (!this._q) this._q = new Map();
      if (!this._q.has(sel)) this._q.set(sel, mkEl('div'));
      return this._q.get(sel);
    },
    querySelectorAll: () => [],
    play: () => Promise.resolve(), pause() {},
  };
  return n;
};

globalThis.document = {
  getElementById(id) {
    if (!store.has(id)) store.set(id, mkEl('div'));
    return store.get(id);
  },
  createElement: t => mkEl(t),
  querySelectorAll: () => [],
  querySelector: () => null,
  body: mkEl('body'),
  activeElement: null,
  addEventListener() {},
};
globalThis.window = { devicePixelRatio: 1, addEventListener() {} };
globalThis.Image = class { set src(v) { this._src = v; } get src() { return this._src; } };
globalThis.MediaRecorder = class { static isTypeSupported() { return true; } };
globalThis.CompressionStream = class {};
globalThis.ImageDecoder = class {};
globalThis.URL.createObjectURL = () => 'blob:fake';
globalThis.URL.revokeObjectURL = () => {};

// ══════════════════════════════════════════════════════════════════
// 载入 engine（挂全局）与主脚本（注入内部函数引用）
// ══════════════════════════════════════════════════════════════════

// engine 里加测试钩子，测的是真实函数不是复制品
const engineHooked = engineSrc
  .replace('const PetEngine = (() => {', 'globalThis.PetEngine = (() => {')
  .replace(
    'return { create, MAX_FRAMES, MAX_FPS };',
    'return { create, MAX_FRAMES, MAX_FPS, ' +
    '__t: { detectBackground, removeBackground, naturalSort } };'
  );
new Function(engineHooked)();

const mainTail = 'refresh();\ndrawPreview();';
const tailIdx = mainSrc.lastIndexOf(mainTail);
if (tailIdx < 0) throw new Error('主脚本入口结构变了');
const mainHooked = mainSrc.slice(0, tailIdx) +
  'globalThis.__T = { groupFiles, guessRole, ROLES, ROLE, items, renumber,' +
  ' newItem, buildPreset, outDims, validate, Tar, fmtSize, NAME_RE,' +
  ' LIMIT_BYTES, MAX_ACTIONS, hexToRgb, checkPresetRefs };\n' +
  mainSrc.slice(tailIdx);

fs.writeFileSync(path.join(HERE, '.selftest-main.mjs'), mainHooked);
await import(path.join(HERE, '.selftest-main.mjs'));
fs.unlinkSync(path.join(HERE, '.selftest-main.mjs'));

const T = globalThis.__T;
const E = globalThis.PetEngine.__t;
const F = name => ({ name });

// ══════════════════════════════════════════════════════════════════
// 一、引擎：背景检测与抠色
// ══════════════════════════════════════════════════════════════════

const makeImage = (w, h, bg, fg, box) => {
  const d = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      const inside = box && x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3];
      const c = inside ? fg : bg;
      d[o] = c[0]; d[o + 1] = c[1]; d[o + 2] = c[2]; d[o + 3] = 255;
    }
  }
  return d;
};

console.log('一、引擎');

{
  const d = makeImage(100, 100, [255, 255, 255], [20, 20, 20], [40, 40, 60, 60]);
  const bg = E.detectBackground(d, 100, 100);
  ok(bg && bg[0] === 255 && bg[1] === 255 && bg[2] === 255, '纯白背景应被识别');
}

{
  const d = makeImage(100, 100, [255, 255, 255], [20, 20, 20], [40, 40, 60, 60]);
  for (const [x, y] of [[2, 2], [97, 2], [2, 97], [97, 97]]) d[(y * 100 + x) * 4 + 3] = 0;
  ok(E.detectBackground(d, 100, 100) === null, '四角已透明时不该抠');
}

{
  const d = new Uint8ClampedArray(100 * 100 * 4).fill(255);
  const put = (x, y, c) => {
    const o = (y * 100 + x) * 4;
    d[o] = c[0]; d[o + 1] = c[1]; d[o + 2] = c[2]; d[o + 3] = 255;
  };
  put(2, 2, [255, 0, 0]); put(97, 2, [0, 255, 0]);
  put(2, 97, [0, 0, 255]); put(97, 97, [255, 255, 0]);
  ok(E.detectBackground(d, 100, 100) === null, '四角颜色各异时不该抠');
}

{
  const d = makeImage(100, 100, [255, 255, 255], [10, 10, 10], [0, 0, 99, 99]);
  ok(E.detectBackground(d, 100, 100) === null,
    '角色占满画面时不该抠（否则宠物被整个抹掉）');
}

{
  const d = makeImage(10, 10, [255, 255, 255], [20, 20, 20], [3, 3, 6, 6]);
  const removed = E.removeBackground(d, 10, 10, [255, 255, 255], 42, 30);
  ok(removed === 84, '应移除 84 个背景像素，实得 ' + removed);
  ok(d[((3 * 10) + 3) * 4 + 3] === 255, '角色像素应保持不透明');
  ok(d[3] === 0, '背景像素应全透明');
}

{
  const d = new Uint8ClampedArray([255, 255, 255, 255, 205, 205, 205, 255, 0, 0, 0, 255]);
  E.removeBackground(d, 3, 1, [255, 255, 255], 42, 60);
  ok(d[3] === 0, '距离 0 应全透明');
  ok(d[7] > 0 && d[7] < 255, '羽化区应半透明，实得 ' + d[7]);
  ok(d[11] === 255, '远处应完全不透明');
}

{
  const arr = [{ name: 'f_10.png' }, { name: 'f_2.png' }, { name: 'f_1.png' }, { name: 'f_20.png' }];
  arr.sort(E.naturalSort);
  eq(arr.map(a => a.name), ['f_1.png', 'f_2.png', 'f_10.png', 'f_20.png'], '自然排序');
}

// ══════════════════════════════════════════════════════════════════
// 二、工作台逻辑
// ══════════════════════════════════════════════════════════════════

console.log('二、工作台逻辑');

{
  const g = T.groupFiles([F('frame_001.png'), F('frame_002.png'), F('frame_003.png')]);
  eq(g.length, 1, '图片序列应合成一组');
  eq(g[0].files.length, 3, '组内应有 3 张');
}

{
  const g = T.groupFiles([F('1.png'), F('2.png')]);
  eq(g.length, 2, '纯数字文件名不该被并成一组');
}

{
  const g = T.groupFiles([F('a.mp4'), F('b.gif'), F('c.webm')]);
  eq(g.length, 3, '视频/GIF 各自成组');
}

{
  eq(T.guessRole('click-a.mp4'), 'click', '猜 click');
  eq(T.guessRole('拖拽.mp4'), 'drag', '猜中文拖拽');
  eq(T.guessRole('walk_01.mp4'), 'move', '猜 walk');
  eq(T.guessRole('随便.mp4'), 'idle', '猜不出时默认 idle');
}

{
  T.items.length = 0;
  const mk = (role) => {
    const it = T.newItem([F('v.mp4')], role);
    it.info = { frameCount: 5, width: 100, height: 100, bbox: {}, warnings: [] };
    T.items.push(it);
    return it;
  };
  const a = mk('idle'), b = mk('idle'), c = mk('working');
  T.renumber();
  eq([a.actionName, b.actionName, c.actionName], ['idle-1', 'idle-2', 'work'],
    '池类带序号，状态类不带');
}

{
  for (const n of ['idle-1', 'click-2', 'work', 'a', 'a_b-c']) {
    ok(T.NAME_RE.test(n), n + ' 应合法');
  }
  for (const n of ['Idle', 'a b', 'a'.repeat(25), '', '中文']) {
    ok(!T.NAME_RE.test(n), n + ' 应非法');
  }
}

{
  T.items.length = 0;
  const mk = (role) => {
    const it = T.newItem([F('v.mp4')], role);
    it.info = { frameCount: 5, width: 100, height: 100, bbox: {}, warnings: [] };
    T.items.push(it);
    return it;
  };
  mk('idle'); mk('idle'); mk('working');
  T.renumber();
  const p = T.buildPreset();
  eq(p.pools.idle, ['idle-1', 'idle-2'], 'pools.idle');
  eq(p.actionMap.working, 'work', 'actionMap.working');
  eq(p.actionMap.idle, 'idle-1', 'actionMap.idle 指向待机池首个');
  eq(Math.max(p.appearance.boxWidth, p.appearance.boxHeight), p.appearance.size,
    'boxWidth/boxHeight 的长边应等于 size');
}

{
  T.items.length = 0;
  for (let i = 0; i < 25; i++) {
    const it = T.newItem([F('v' + i + '.mp4')], 'idle');
    it.info = { frameCount: 3, width: 10, height: 10, bbox: {}, warnings: [] };
    T.items.push(it);
  }
  T.renumber();
  ok(T.validate().errs.some(e => e.includes('24')), '超过 24 个动作应报错');
}

{
  T.items.length = 0;
  const it = T.newItem([F('big.mp4')], 'idle');
  it.info = { frameCount: 3, width: 10, height: 10, bbox: {}, warnings: [] };
  it.size = 7 * 1024 * 1024;
  T.items.push(it);
  T.renumber();
  ok(T.validate().errs.some(e => e.includes('6 MB')), '单文件超 6 MB 应报错');
}

{
  T.items.length = 0;
  const it = T.newItem([F('w.mp4')], 'working');
  it.info = { frameCount: 3, width: 10, height: 10, bbox: {}, warnings: [] };
  T.items.push(it);
  T.renumber();
  const v = T.validate();
  ok(v.warns.some(w => w.includes('待机')), '缺待机应警告');
  ok(!v.errs.some(e => e.includes('待机')), '缺待机不该拦截导出');
}

{
  eq(T.hexToRgb('#ff8000'), [255, 128, 0], 'hex 解析');
  eq(T.hexToRgb('nope'), null, '非法 hex 返回 null');
}

// tar 结构
{
  const enc = new TextEncoder();
  const tar = T.Tar.build([
    { name: 'package/a.txt', data: enc.encode('hello') },
    { name: 'package/b.txt', data: enc.encode('x'.repeat(700)) },
  ]);
  eq(tar.length % 512, 0, 'tar 长度应是 512 整数倍');
  eq(new TextDecoder().decode(tar.subarray(0, 10)).replace(/\0/g, ''), 'package/a.',
    '首个条目名');
  eq(parseInt(new TextDecoder().decode(tar.subarray(124, 136)).replace(/\0/g, '').trim(), 8),
    5, '首条目 size');
  eq(new TextDecoder().decode(tar.subarray(257, 262)), 'ustar', 'magic');

  // 校验和算法要求 chksum 字段按 8 个空格计入
  const blk = Uint8Array.from(tar.subarray(0, 512));
  for (let i = 148; i < 156; i++) blk[i] = 0x20;
  let sum = 0;
  for (let i = 0; i < 512; i++) sum += blk[i];
  eq(parseInt(new TextDecoder().decode(tar.subarray(148, 156)).replace(/\0/g, '').trim(), 8),
    sum, '校验和');
  ok(tar.subarray(tar.length - 1024).every(b => b === 0), '结尾应是 1024 字节零块');
}

// 预设引用完整性
{
  T.items.length = 0;
  const a = T.newItem([F('a.mp4')], 'idle');
  a.info = { frameCount: 3, width: 10, height: 10, bbox: {}, warnings: [] };
  a.blob = new Blob([]);
  T.items.push(a);
  T.renumber();

  const bad = T.checkPresetRefs({
    actions: { 'idle-1': 'idle-1' },
    pools: { idle: ['idle-1', 'ghost'] },
    actionMap: { working: 'nope' },
    moveParams: { 'walk-x': {} },
  });
  ok(bad.some(b => b.includes('ghost')), '应查出 pools 里的 ghost');
  ok(bad.some(b => b.includes('nope')), '应查出 actionMap 里的 nope');
  ok(bad.some(b => b.includes('walk-x')), '应查出 moveParams 里的 walk-x');

  const bad2 = T.checkPresetRefs({
    actions: { 'idle-1': 'idle-1', 'idle-2': 'idle-2' },
    pools: { idle: ['idle-1', 'idle-2'] },
    actionMap: {},
  });
  ok(bad2.some(b => b.includes('idle-2') && b.includes('没有素材')),
    '声明了动作却没素材也要查出来');

  ok(T.checkPresetRefs({
    actions: { 'idle-1': 'idle-1' },
    pools: { idle: ['idle-1'] },
    actionMap: { idle: 'idle-1' },
  }).length === 0, '正常预设不该报问题');
}

// ══════════════════════════════════════════════════════════════════

console.log('');
console.log(fail === 0 ? `全部通过：${pass} 项` : `通过 ${pass} 项，失败 ${fail} 项`);
process.exit(fail === 0 ? 0 : 1);
