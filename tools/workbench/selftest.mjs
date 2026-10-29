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

// 把页面里真实的模板注入 DOM 桩（浏览器里它们是 text/plain 的 script 块）
{
  const tplRe = /<script type="text\/plain" id="tpl-([^"]+)">\n([\s\S]*?)\n<\/script>/g;
  let n = 0;
  for (const m of html.matchAll(tplRe)) {
    document.getElementById('tpl-' + m[1]).textContent = m[2];
    n++;
  }
  if (n !== 4) throw new Error('模板数量不对：' + n);
}

// engine 里加测试钩子，测的是真实函数不是复制品
const engineHooked = engineSrc
  .replace('const PetEngine = (() => {', 'globalThis.PetEngine = (() => {')
  .replace(
    'return { create, MAX_FRAMES, MAX_FPS };',
    'return { create, MAX_FRAMES, MAX_FPS, ' +
    '__t: { detectBackground, detectBackgroundEx, removeBackground,' +
    ' naturalSort, explainBgFailure } };'
  );
new Function(engineHooked)();

const mainTail = 'refresh();\ndrawPreview();';
const tailIdx = mainSrc.lastIndexOf(mainTail);
if (tailIdx < 0) throw new Error('主脚本入口结构变了');
const mainHooked = mainSrc.slice(0, tailIdx) +
  'globalThis.__T = { groupFiles, guessRole, ROLES, ROLE, items, renumber,' +
  ' newItem, buildPreset, outDims, validate, Tar, fmtSize, NAME_RE,' +
  ' LIMIT_BYTES, MAX_ACTIONS, hexToRgb, checkPresetRefs,' +
  ' preflightExport, PKG_NAME_RE, SEMVER_RE, isWebmBlob };\n' +
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

// engine 从边缘往里收 8% 取四角（跳过黑边/圆角/暗角），
// 测试里的"四角"必须用同一套坐标，否则测的是另一块像素。
const cornerPts = (w, h) => {
  const inset = Math.max(2, Math.round(Math.min(w, h) * 0.08));
  return [[inset, inset], [w - 1 - inset, inset],
          [inset, h - 1 - inset], [w - 1 - inset, h - 1 - inset]];
};

console.log('一、引擎');

{
  const d = makeImage(100, 100, [255, 255, 255], [20, 20, 20], [40, 40, 60, 60]);
  const bg = E.detectBackground(d, 100, 100);
  ok(bg && bg[0] === 255 && bg[1] === 255 && bg[2] === 255, '纯白背景应被识别');
}

{
  const d = makeImage(100, 100, [255, 255, 255], [20, 20, 20], [40, 40, 60, 60]);
  // ⚠️ 挖整块而不是单个像素：单点太脆弱，engine 内缩量一变（pad）就错开，
  //    测出来的其实是"四角还是白底"，不是我们想验的"四角透明"。
  for (const [cx, cy] of cornerPts(100, 100)) {
    for (let dy = -10; dy <= 10; dy++) {
      for (let dx = -10; dx <= 10; dx++) {
        const x = cx + dx, y = cy + dy;
        if (x >= 0 && x < 100 && y >= 0 && y < 100) d[(y * 100 + x) * 4 + 3] = 0;
      }
    }
  }
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
// ── 预览与导出必须共用同一套绘制 ──────────────────────────────────
//
// 这是「预览里看着对、装上去不对」的唯一来源。分成两份实现的话，
// 只要有一处改动忘了同步就会出现，而两边各自看起来都"对"。
console.log('  预览/导出绘制一致性');

{
  const esrc = fs.readFileSync(path.join(HERE, 'engine.js'), 'utf8');

  // ① 导出路径调的是共用的 drawFrameTo，没有第二份内联实现
  ok(/function paint\(i\) \{\s*drawFrameTo\(ctx, i, box, outW, outH\);\s*\}/.test(esrc),
    'exportWebm 的 paint 应直接调用 drawFrameTo');
  ok((esrc.match(/Math\.min\(outW \/ sw, outH \/ sh\)/g) || []).length === 1,
    'contain 缩放只应有一处实现（多一处就是复制代码）');

  // ② 直接跑真实函数体验几何
  const body = esrc.match(/function drawFrameTo\(ctx, i, box, outW, outH\) \{([\s\S]*?)\n    \}/)[1];
  const drawFrameTo = new Function('ctx', 'i', 'frames', 'box', 'outW', 'outH', body);

  const run = (fr, box, W, H) => {
    const calls = [];
    const ctx = {
      clearRect: (...a) => calls.push(['clear', ...a]),
      drawImage: (...a) => calls.push(['draw', ...a]),
    };
    drawFrameTo(ctx, 0, fr, box, W, H);
    // drawImage(bitmap, sx, sy, sw, sh, dx, dy, dw, dh) → 索引 2..9
    return { calls, d: calls.find(c => c[0] === 'draw') };
  };

  let { calls, d } = run([{ w: 200, h: 200, bitmap: 'B' }],
    { x0: 0, y0: 0, x1: 1, y1: 1 }, 100, 100);
  ok(d && Math.abs(d[8] - 100) < 0.01 && Math.abs(d[9] - 100) < 0.01,
    '方形源应铺满方形输出');
  ok(d && Math.abs(d[6]) < 0.01, 'X 居中偏移应为 0');
  ok(calls[0][0] === 'clear', '必须先 clearRect，否则帧会叠影');

  ({ d } = run([{ w: 200, h: 100, bitmap: 'B' }],
    { x0: 0, y0: 0, x1: 1, y1: 1 }, 100, 100));
  ok(Math.abs(d[8] - 100) < 0.01 && Math.abs(d[9] - 50) < 0.01,
    '2:1 源在方形画布应画成 100x50（contain，不是拉伸）');
  ok(Math.abs(d[7] - 50) < 0.01, '垂直应底部对齐');

  ({ d } = run([{ w: 200, h: 200, bitmap: 'C' }],
    { x0: 0.25, y0: 0.25, x1: 0.75, y1: 0.75 }, 100, 100));
  ok(Math.abs(d[2] - 50) < 0.01 && Math.abs(d[3] - 50) < 0.01, '裁剪起点应为 50,50');
  ok(Math.abs(d[4] - 100) < 0.01 && Math.abs(d[5] - 100) < 0.01, '裁剪尺寸应为 100x100');

  ({ d } = run([{ w: 100, h: 100, bitmap: 'D' }],
    { x0: -5, y0: -5, x1: 5, y1: 5 }, 100, 100));
  ok(Math.abs(d[4] - 100) < 0.01, '越界裁剪框应被 clamp');

  ({ calls } = run([{ w: 100, h: 100, bitmap: 'E' }], null, 50, 50));
  ok(calls.length > 0, 'box 为 null 时不该崩');

  const fdSrc = esrc.match(/function frameDelay\(i, fps\) \{([\s\S]*?)\n    \}/)[1];
  const frameDelay = new Function('frames', 'i', 'fps', fdSrc);
  ok(frameDelay([{ delay: 0 }], 0, 25) === 40, '无 delay 按 fps 算');
  ok(frameDelay([{ delay: 120 }], 0, 25) === 120, '有 delay 用 delay');
  ok(frameDelay([{ delay: 5 }], 0, 25) === 40, 'delay<=10 应回落到 fps');

  // ③ 页面预览确实走这几个方法
  ok(/engine\.drawFrameTo\(/.test(html), '预览应调 engine.drawFrameTo');
  ok(/engine\.frameDelay\(/.test(html), '预览应按真实帧时长播放');
  ok(/function tickPlay/.test(html), '应有逐帧播放循环');
  ok(/function actionsForState/.test(html), '应有状态→动作模拟');
  ok(/simPick/.test(html), '应能按状态取动作');
}

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
// 二点五、抠背景失败原因（决定用户下一步怎么做，必须准确）
// ══════════════════════════════════════════════════════════════════

console.log('  抠背景失败原因');

{
  // 造一块 w×h 的像素数据，可选填底色与四角
  const mk = (w, h, fill, corners) => {
    const d = new Uint8ClampedArray(w * h * 4);
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const o = (y * w + x) * 4;
        d[o] = fill[0]; d[o + 1] = fill[1]; d[o + 2] = fill[2]; d[o + 3] = 255;
      }
    }
    if (corners) {
      for (const [cx, cy, c] of corners) {
        const o = (cy * w + cx) * 4;
        d[o] = c[0]; d[o + 1] = c[1]; d[o + 2] = c[2];
      }
    }
    return d;
  };

  // ① 纯色背景 + 中间有角色 → ok
  {
    const w = 60, h = 60;
    const d = mk(w, h, [255, 255, 255]);
    for (let y = 20; y < 40; y++) {
      for (let x = 20; x < 40; x++) {
        const o = (y * w + x) * 4;
        d[o] = 20; d[o + 1] = 30; d[o + 2] = 40;
      }
    }
    const r = E.detectBackgroundEx(d, w, h);
    ok(r.reason === 'ok', '白底 + 角色应检测成功，实得 ' + r.reason);
    eq(r.bg, [255, 255, 255], '背景色应为白色');
    ok(r.ratio > 0.4, '背景占比应大于 40%，实得 ' + r.ratio);
  }

  // ② 透明背景 → already-transparent
  {
    const w = 40, h = 40;
    const d = mk(w, h, [255, 255, 255]);
    // 同上：挖块不挖点
    for (const [cx, cy] of cornerPts(w, h)) {
      for (let dy = -6; dy <= 6; dy++) {
        for (let dx = -6; dx <= 6; dx++) {
          const x = cx + dx, y = cy + dy;
          if (x >= 0 && x < w && y >= 0 && y < h) d[(y * w + x) * 4 + 3] = 0;
        }
      }
    }
    const r = E.detectBackgroundEx(d, w, h);
    ok(r.reason === 'already-transparent', '透明四角应判 already-transparent，实得 ' + r.reason);
    ok(r.bg === null, '不该给出背景色');
  }

  // ③ 四角颜色差很大 → corners-differ
  {
    const w = 40, h = 40;
    const pts = cornerPts(w, h);
    const d = mk(w, h, [255, 255, 255]);
    // 左上角一块黑、右上角一块红 —— 块状才稳定
    for (let dy = -6; dy <= 6; dy++) {
      for (let dx = -6; dx <= 6; dx++) {
        let x = pts[0][0] + dx, y = pts[0][1] + dy;
        if (x >= 0 && x < w && y >= 0 && y < h) {
          const o = (y * w + x) * 4;
          d[o] = 0; d[o + 1] = 0; d[o + 2] = 0;
        }
        x = pts[1][0] + dx; y = pts[1][1] + dy;
        if (x >= 0 && x < w && y >= 0 && y < h) {
          const o = (y * w + x) * 4;
          d[o] = 255; d[o + 1] = 0; d[o + 2] = 0;
        }
      }
    }
    const r = E.detectBackgroundEx(d, w, h);
    ok(r.reason === 'corners-differ', '四角不一致应判 corners-differ，实得 ' + r.reason);
  }

  // ④ 背景占比太低 → too-little-background
  //    造法：只把最外一圈窄边留成底色，中间整块是角色色。
  //    ⚠️ 边宽必须**大于 inset**（200×200 时 inset=16），否则四角采样点
  //       会落进角色色里，测的就变成 corners-differ 了。
  //    边宽 20px ⇒ 底色占比 1-(160/200)² = 36%，还不够低；
  //    改用"角色区铺到只剩四角一点点底色"：底色只在四个角上，用 L 形缺口。
  {
    const w = 200, h = 200;
    const d = mk(w, h, [10, 10, 10]);
    // 角色色铺满整幅，只在四个角挖出 20×20 的底色块（各含 inset 采样点）
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const inCorner =
          (x < 20 || x >= w - 20) && (y < 20 || y >= h - 20);
        if (!inCorner) {
          const o = (y * w + x) * 4;
          d[o] = 250; d[o + 1] = 250; d[o + 2] = 250;
        }
      }
    }
    const r = E.detectBackgroundEx(d, w, h);
    ok(r.reason === 'too-little-background',
      '背景太少应判 too-little-background，实得 ' + r.reason
      + ' (ratio=' + r.ratio.toFixed(3) + ')');
    ok(r.ratio < 0.25, '占比应低于 25%，实得 ' + r.ratio.toFixed(3));
  }

  // ⑤ 整屏同色 → no-background-at-all
  {
    const w = 40, h = 40;
    const r = E.detectBackgroundEx(mk(w, h, [128, 128, 128]), w, h);
    ok(r.reason === 'no-background-at-all',
      '整屏同色应判 no-background-at-all，实得 ' + r.reason);
    ok(r.ratio > 0.99, '占比应 > 0.99');
  }

  // ⑥ 兼容包装：detectBackground 仍返回颜色或 null
  {
    const w = 40, h = 40;
    eq(E.detectBackground(mk(w, h, [128, 128, 128]), w, h), null,
      '整屏同色时 detectBackground 应返回 null');
  }

  // ⑦ 每种原因都要翻译成人话，且给出下一步动作
  for (const reason of ['already-transparent', 'corners-differ',
                        'too-little-background', 'no-background-at-all',
                        'disabled', 'manual', 'ok']) {
    const e = E.explainBgFailure(reason, 0.1);
    ok(e && typeof e.text === 'string' && e.text.length > 10,
      reason + ' 应有一段可读说明');
    ok(e && ['ok', 'warn', 'err'].includes(e.level),
      reason + ' 的 level 应合法，实得 ' + (e && e.level));
  }
  // 失败原因必须给出"怎么办"，不能只说现象
  for (const reason of ['corners-differ', 'too-little-background', 'no-background-at-all']) {
    const e = E.explainBgFailure(reason, 0.1);
    ok(/解决|换|改|调|让/.test(e.text), reason + ' 应给出解决办法');
  }
  ok(E.explainBgFailure('corners-differ', 0).level === 'warn', 'corners-differ 应是 warn');
  ok(E.explainBgFailure('ok', 0.5).text.includes('50%'), 'ok 应显示背景占比');

  // ⑧ 页面确实把原因显示出来
  ok(/explainOf/.test(html), '页面应调用原因翻译');
  ok(/reapplyBackground/.test(html), '页面应能重新抠图');
  ok(/btnRecut/.test(html), '应有重新抠图按钮');
}

// ══════════════════════════════════════════════════════════════════
// 二点六、重抠不叠加（调参越调越糊的根源）
// ══════════════════════════════════════════════════════════════════

console.log('  重抠图不叠加');

{
  const esrc = fs.readFileSync(path.join(HERE, 'engine.js'), 'utf8');
  const grab = (name) => {
    const m = esrc.match(new RegExp('async function ' + name + '\\(\\) \\{([\\s\\S]*?)\\n    \\}'));
    if (!m) throw new Error('找不到 ' + name);
    return m[1];
  };

  // 模拟 ImageBitmap：被关闭后再拿来创建必须报错
  let idSeq = 0;
  const fakeBitmap = (tag) => {
    const b = { id: ++idSeq, tag, closed: false };
    b.close = () => { b.closed = true; };
    return b;
  };
  let closedTouched = 0;
  const createImageBitmap = async (src) => {
    if (src && src.closed) { closedTouched++; throw new Error('用了已关闭的位图'); }
    return fakeBitmap(src ? src.tag + '-clone' : 'new');
  };

  const wrap = (body) => new Function('frames', 'createImageBitmap',
    'return (async () => {' + body + '\n})()');

  const restore = wrap(grab('restoreOriginalFrames'));
  const snapshot = wrap(grab('snapshotOriginals'));

  const frames = [
    { bitmap: fakeBitmap('f0'), orig: fakeBitmap('o0') },
    { bitmap: fakeBitmap('f1'), orig: fakeBitmap('o1') },
  ];
  const origRefs = [frames[0].orig, frames[1].orig];

  // 模拟连点 5 次「重新抠图」
  for (let round = 1; round <= 5; round++) {
    await restore(frames, createImageBitmap);
    ok(!frames[0].orig.closed && !frames[1].orig.closed,
      '第 ' + round + ' 轮后 orig 不能被关闭');
    ok(frames[0].orig === origRefs[0] && frames[1].orig === origRefs[1],
      '第 ' + round + ' 轮后 orig 必须还是同一个源头');
    ok(frames[0].bitmap !== frames[0].orig,
      '第 ' + round + ' 轮后成品不能就是 orig 本身');

    // 模拟抠图：产出新 bitmap，旧成品关闭
    for (const fr of frames) {
      const old = fr.bitmap;
      fr.bitmap = await createImageBitmap(fr.orig);
      old.close();
    }
  }
  eq(closedTouched, 0, '5 轮重抠全程不该碰到已关闭的位图');

  // snapshotOriginals 不覆盖已有的 orig
  const before = frames[0].orig;
  await snapshot(frames, createImageBitmap);
  ok(frames[0].orig === before, 'snapshotOriginals 不该覆盖已有 orig');

  // 没有 orig 的帧要能补上
  const fresh = [{ bitmap: fakeBitmap('n0') }];
  await snapshot(fresh, createImageBitmap);
  ok(fresh[0].orig && fresh[0].orig.tag === 'n0-clone', '没 orig 的帧应补一份克隆');

  // 源码里绝不能出现关掉 orig 的语句 —— 那会让第二次重抠直接失效
  ok(!/fr\.orig\.close\(\)/.test(esrc), '源码不能关闭 fr.orig');
  ok(!/fr\.orig\s*&&\s*fr\.orig\.close\(\)/.test(esrc), '源码不能关闭 fr.orig');

  // 抠背景必须是破坏性重算：每次都从 orig 出发
  ok(/await restoreOriginalFrames\(\)/.test(esrc),
    'applyBgRemoval 必须先从 orig 恢复');

  // ⚠️ 回归：曾出过 `fr.orig && fr.orig.close()` 的 bug ——
  //    它让 restoreOriginalFrames 先关掉唯一源头再克隆，报
  //    "createImageBitmap: The image source is not usable"，导入直接失败。
  //    上一版只查字面，本可以用"跑一遍真实时序"抓住，所以这里做时序验证。
  {
    const orig = fakeBitmapSrc => fakeBitmap(fakeBitmapSrc);
    const seqFrames = [{ bitmap: fakeBitmap('a'), orig: fakeBitmap('o') }];
    // 走真实实现：restore 必须能成功克隆，且 orig 仍然存活
    await restore(seqFrames, createImageBitmap);
    ok(seqFrames[0].bitmap && seqFrames[0].bitmap.tag === 'o-clone',
      'restore 后应拿到 orig 的克隆');
    ok(!seqFrames[0].orig.closed, 'restore 后 orig 必须存活（否则下次重抠必崩）');
    // 再恢复一次，验证不会因为上一次克隆而耗尽源头
    await restore(seqFrames, createImageBitmap);
    ok(seqFrames[0].bitmap.tag === 'o-clone', '第二次 restore 仍应成功');
  }
}

// ── 重抠接口连通性 ────────────────────────────────────────────────
console.log('  重抠接口');

{
  const esrc = fs.readFileSync(path.join(HERE, 'engine.js'), 'utf8');

  ok(/reapplyBackground,/.test(esrc), '实例应暴露 reapplyBackground');
  ok(/bgReport,/.test(esrc), '实例应暴露 bgReport');
  ok(/async function reapplyBackground/.test(esrc), 'reapplyBackground 应是 async');

  // load 里顺序必须是：先存原图，再抠
  const snapIdx = esrc.indexOf('await snapshotOriginals();');
  const bgIdx = esrc.indexOf('const bgResult = await applyBgRemoval(');
  ok(snapIdx > 0 && bgIdx > snapIdx, '必须先 snapshotOriginals 再 applyBgRemoval');
  ok(/lastBg = bgResult/.test(esrc), 'load 应记录 lastBg');

  // 页面接线
  ok(/id="btnRecut"/.test(html), '应有重新抠图按钮');
  ok(/\$\('btnRecut'\)\.onclick/.test(html), '按钮应绑定事件');
  ok(/id="bgMsg"/.test(html), '应有 bgMsg 提示区');
  ok(/reapplyBackground\(opts\)/.test(html), '页面应调 reapplyBackground');

  const recut = html.match(/async function recutAll\(\)[\s\S]*?\n\}/);
  ok(recut, '应能抓到 recutAll');
  ok(recut && /for \(const it of ready\)/.test(recut[0]), '应遍历所有素材');
  ok(recut && /thumbDataUrl/.test(recut[0]), '重抠后要刷新缩略图');
  ok(recut && /drawPreview\(\)/.test(recut[0]), '重抠后要刷新预览');

  // 参数确实从界面读，不是写死
  ok(/mode: \$\('bgMode'\)\.value/.test(html), 'mode 应从 bgMode 读');
  ok(/tolerance: \+\$\('tolerance'\)\.value/.test(html), 'tolerance 应从滑块读');
  ok(/feather: \+\$\('feather'\)\.value/.test(html), 'feather 应从滑块读');
}

// ══════════════════════════════════════════════════════════════════
// 三、导出前自检 —— 装不装得上靠它，必须自己先可信
// ══════════════════════════════════════════════════════════════════

console.log('三、导出前自检');

const setV = (id, v) => { document.getElementById(id).value = v; };
const webmBlob = (size = 1000) => {
  const b = new Uint8Array(size);
  b[0] = 0x1A; b[1] = 0x45; b[2] = 0xDF; b[3] = 0xA3;
  return new Blob([b]);
};
const pngBlob = () => new Blob([new Uint8Array([0x89, 0x50, 0x4E, 0x47])]);

function resetWorkbench() {
  T.items.length = 0;
  setV('pkgName', 'dsh-app-pet-custom');
  setV('pkgVer', '1.0.0');
  setV('presetJson', '');
}
function mkItem(role, opts = {}) {
  const it = T.newItem([F(opts.name || 'v.mp4')], role);
  it.info = { frameCount: 5, width: 100, height: 100, bbox: {}, warnings: [] };
  it.blob = opts.blob !== undefined ? opts.blob : webmBlob();
  it.size = opts.size !== undefined ? opts.size : 1000;
  T.items.push(it);
  return it;
}

{
  resetWorkbench();
  mkItem('idle'); mkItem('working');
  T.renumber();
  const r = await T.preflightExport();
  ok(r.blocking.length === 0, '正常包不该阻塞：' + JSON.stringify(r.blocking));
}

{
  resetWorkbench();
  mkItem('idle'); T.renumber();
  for (const bad of ['Bad Name', '中文包', 'UPPER', '-start', '']) {
    setV('pkgName', bad);
    const r = await T.preflightExport();
    ok(r.blocking.some(b => b.includes('包名')), '包名「' + bad + '」应判非法');
  }
  setV('pkgName', 'ok-name');
  ok(!(await T.preflightExport()).blocking.some(b => b.includes('包名')),
    'ok-name 应合法');
}

{
  resetWorkbench();
  mkItem('idle'); T.renumber();
  for (const v of ['1.0', 'v1.0.0', 'abc', '', '1.0.0.0']) {
    setV('pkgVer', v);
    const r = await T.preflightExport();
    ok(r.blocking.some(b => b.includes('版本号')), '版本「' + v + '」应判非法');
  }
  for (const v of ['1.0.0', '0.0.1', '2.3.4-beta.1']) {
    setV('pkgVer', v);
    ok(!(await T.preflightExport()).blocking.some(b => b.includes('版本号')),
      '版本「' + v + '」应合法');
  }
}

{
  resetWorkbench();
  mkItem('idle'); mkItem('click', { blob: pngBlob() });
  T.renumber();
  ok((await T.preflightExport()).blocking.some(b => b.includes('webm')),
    '文件头不是 webm 应阻塞');
}

{
  resetWorkbench();
  mkItem('idle', { size: 7 * 1024 * 1024 });
  T.renumber();
  ok((await T.preflightExport()).blocking.some(b => b.includes('6 MB')),
    '超 6 MB 应阻塞');
}

{
  resetWorkbench();
  for (let i = 0; i < 25; i++) mkItem('idle');
  T.renumber();
  ok((await T.preflightExport()).blocking.some(b => b.includes('24')),
    '超 24 个动作应阻塞');
}

{
  resetWorkbench();
  mkItem('idle'); T.renumber();
  setV('presetJson', JSON.stringify({
    actions: { 'idle-1': 'idle-1' },
    pools: { idle: ['idle-1'], click: ['ghost'] },
    actionMap: {},
  }));
  ok((await T.preflightExport()).blocking.some(b => b.includes('ghost')),
    '预设引用不存在的动作应阻塞');
}

{
  resetWorkbench();
  mkItem('working'); T.renumber();
  setV('presetJson', JSON.stringify({
    actions: { work: 'work' },
    pools: {},
    actionMap: { working: 'work' },
  }));
  ok((await T.preflightExport()).blocking.some(b => b.includes('待机')),
    '没待机动作应阻塞');
}

{
  resetWorkbench();
  mkItem('idle');
  const b = mkItem('click');
  b.blob = null;
  T.renumber();
  const r = await T.preflightExport();
  ok(r.blocking.length === 0, '未生成完不该阻塞');
  ok(r.warnings.some(w => w.includes('没生成')), '应警告有未生成的');
}

{
  resetWorkbench();
  mkItem('idle'); T.renumber();
  setV('presetJson', JSON.stringify({
    actions: { 'idle-1': 'idle-1' },
    pools: { idle: ['idle-1'] },
    actionMap: { idle: 'idle-1' },
  }));
  const r = await T.preflightExport();
  ok(r.warnings.some(w => w.includes('状态')), '应警告状态没配全');
  ok(r.blocking.length === 0, '状态没配全不该阻塞');
}

{
  ok(await T.isWebmBlob(webmBlob()), '真 webm 应通过');
  ok(!(await T.isWebmBlob(pngBlob())), 'png 不该通过');
  ok(!(await T.isWebmBlob(new Blob([]))), '空 blob 不该通过');
  ok(!(await T.isWebmBlob(new Blob([new Uint8Array([0x1A, 0x45])]))), '太短不该通过');
}

// ══════════════════════════════════════════════════════════════════

console.log('');
console.log(fail === 0 ? `全部通过：${pass} 项` : `通过 ${pass} 项，失败 ${fail} 项`);
process.exit(fail === 0 ? 0 : 1);
