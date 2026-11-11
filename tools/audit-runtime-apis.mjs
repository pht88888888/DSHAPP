#!/usr/bin/env node
/**
 * 扫描前端产物里用到的「标准库运行时 API」，对每个 API 报出它需要的最低 Chromium 版本，
 * 并与 polyfill（webview-polyfill.js）实际覆盖的清单比对，列出**未被覆盖**的缺口。
 *
 * ── 为什么要这个脚本 ────────────────────────────────────────────────────
 *
 * 已经因为「API 清单是人工列的」翻过两次车：
 *   1. `toSorted` —— 清单只扫了 dist（官方壳），而 dist 恰好一处都没用到这个家族，
 *      于是整个 `toSorted` 家族没进 polyfill ⇒ 插件 bundle 一调就炸。
 *   2. `AbortSignal.prototype.throwIfAborted` —— 连扫都没扫过插件，
 *      而 `AbortSignal.any` 却被人工收进去了（同一个接口，不同成员）。
 *
 * 人工名单**必然会漏**，因为缺口不在源码里、而在「哪份产物碰了哪个新 API」这个组合上。
 * 所以改成：从产物里反向抽取用到的 API，逐个查表，再和 polyfill 做差集。
 *
 * ── 做法 ────────────────────────────────────────────────────────────────
 *
 * 用 acorn 把每份产物解析成 AST，遍历 MemberExpression / NewExpression /
 * CallExpression，拼出 `AbortSignal.prototype.throwIfAborted` 这样的规范名，
 * 再查 FEATURES 表。表里没有的名字**不算缺口**（表是白名单式的人工知识，
 * 只收录「已知的、有版本门槛的、且本项目产物真的在用」的 API）。
 *
 * ⚠️ 这是个**静态启发式**，判据是「产物文本里出现了这个成员访问」，
 *    不是「运行时一定会走到」。会有假阳性（例如仅在某个分支里用到），
 *    但对付「漏掉一整个 API」这个失效模式足够 —— 宁可多补一个垫片（幂等、无害），
 *    也不要漏掉一个导致整块 UI 打不开的。
 *
 * 用法：
 *   node tools/audit-runtime-apis.mjs <产物根目录> [更多根目录…]
 *   node tools/audit-runtime-apis.mjs --polyfill runtime/webview/webview-polyfill.js <根目录>
 *
 * 退出码：0 = 无缺口；1 = 有缺口（输出清单）。
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';

const require = createRequire(import.meta.url);

/**
 * 已知的、有 Chromium 版本门槛的标准库 API。
 * key = 规范名（对象链 + 成员），value = { chrome, how }
 *   chrome：最低可用的 Chromium 主版本
 *   how   ：在 polyfill 里怎么补（写给人看，脚本不用）
 */
const FEATURES = {
  // ── AbortSignal / AbortController ─────────────────────────────────
  'AbortSignal.prototype.throwIfAborted': { chrome: 100, how: '给 AbortSignal.prototype 挂一个' },
  'AbortSignal.any': { chrome: 116, how: '静态方法，需组合多个 signal' },
  'AbortSignal.timeout': { chrome: 103, how: '静态方法，定时 aborted' },
  'AbortSignal.prototype.reason': { chrome: 98, how: '属性，垫片里要一并提供' },
  'AbortSignal.abort': { chrome: 93, how: '静态方法' },

  // ── Object / Map / Set ────────────────────────────────────────────
  'Object.hasOwn': { chrome: 93, how: '别名 Object.prototype.hasOwnProperty.call' },
  'Object.groupBy': { chrome: 117, how: '按 key 分组' },
  'Map.groupBy': { chrome: 117, how: '同上，键可以是对象' },
  'Map.prototype.getOrInsert': { chrome: 133, how: 'TS 提案语法糖' },
  'Map.prototype.getOrInsertComputed': { chrome: 133, how: '同上' },
  'Set.prototype.union': { chrome: 122, how: 'Set 集合运算' },
  'Set.prototype.intersection': { chrome: 122, how: 'Set 集合运算' },
  'Set.prototype.difference': { chrome: 122, how: 'Set 集合运算' },
  'Set.prototype.symmetricDifference': { chrome: 122, how: 'Set 集合运算' },
  'Set.prototype.isSubsetOf': { chrome: 122, how: 'Set 集合运算' },
  'Set.prototype.isSupersetOf': { chrome: 122, how: 'Set 集合运算' },
  'Set.prototype.isDisjointFrom': { chrome: 122, how: 'Set 集合运算' },

  // ── Array / String 原型 ───────────────────────────────────────────
  'Array.prototype.at': { chrome: 92, how: '负索引取元素' },
  'String.prototype.at': { chrome: 92, how: '同上' },
  'Array.fromAsync': { chrome: 130, how: '异步可迭代 → 数组' },
  'Array.prototype.toSorted': { chrome: 110, how: '返回新数组的排序' },
  'Array.prototype.toReversed': { chrome: 110, how: '返回新数组的逆序' },
  'Array.prototype.toSpliced': { chrome: 110, how: '返回新数组的拼接' },
  'Array.prototype.with': { chrome: 110, how: '返回新数组的替换' },
  'Array.prototype.findLast': { chrome: 97, how: '从后往前找' },
  'Array.prototype.findLastIndex': { chrome: 97, how: '从后往前找下标' },
  'String.prototype.replaceAll': { chrome: 85, how: '全局替换' },

  // ── Promise ──────────────────────────────────────────────────────
  'Promise.withResolvers': { chrome: 119, how: '把 resolve/reject 暴露出来' },
  'Promise.try': { chrome: 128, how: '同步函数包成 Promise' },
  'Promise.any': { chrome: 85, how: '任一成功' },

  // ── 全局函数 ─────────────────────────────────────────────────────
  structuredClone: { chrome: 98, how: '深拷贝（注意 transfer 选项）' },
  reportError: { chrome: 95, how: '全局错误上报' },
  queueMicrotask: { chrome: 71, how: '微任务入队' },
  'Intl.Segmenter': { chrome: 87, how: '字素/词切分' },

  // ── URL / 编码 ───────────────────────────────────────────────────
  'URL.canParse': { chrome: 120, how: 'URL 可解析性判断' },
  'URL.parse': { chrome: 126, how: '返回 URL 或 null' },

  // ── RegExp ───────────────────────────────────────────────────────
  'RegExp.escape': { chrome: 136, how: '正则元字符转义' },

  // ── 二进制 ───────────────────────────────────────────────────────
  'Uint8Array.fromBase64': { chrome: 130, how: 'base64 → 字节' },
  'Uint8Array.prototype.toBase64': { chrome: 130, how: '字节 → base64' },
  'Uint8Array.fromHex': { chrome: 130, how: 'hex → 字节' },
  'Uint8Array.prototype.toHex': { chrome: 130, how: '字节 → hex' },
  'ArrayBuffer.prototype.transfer': { chrome: 114, how: '转移所有权' },

  // ── 其它全局对象 ─────────────────────────────────────────────────
  WeakRef: { chrome: 84, how: '弱引用' },
  FinalizationRegistry: { chrome: 84, how: '终结器' },
  AggregateError: { chrome: 85, how: '多错误聚合' },
  'Symbol.dispose': { chrome: 134, how: '显式资源管理' },
  'Symbol.asyncDispose': { chrome: 134, how: '显式资源管理（异步）' },
  'CompressionStream': { chrome: 80, how: '流式压缩' },
  'DecompressionStream': { chrome: 80, how: '流式解压' },
  'crypto.randomUUID': { chrome: 92, how: '随机 UUID' },
};

/**
 * ⚠️ **实例成员为什么不能按根对象名过滤**（这是本脚本第一版最大的坑）
 *
 * 第一版只认 `ROOTS` 里那些全局名开头、且链长 ≥2 的表达式，结果
 * `AbortSignal.prototype.throwIfAborted` **完全没被扫出来** —— 而它正是线上
 * 真实报错（粉丝截图：`signal.throwIfAborted is not a function`）。
 *
 * 原因是产物里写的是 `signal.throwIfAborted`，而 `signal` 是个**局部变量**，
 * 静态分析根本不知道它是 AbortSignal。凡是「方法挂在实例上」的 API
 * （`throwIfAborted` / `toSorted` / `getOrInsert` / `isSubsetOf` …）
 * 都会因为根对象追不到而漏掉，**而那恰恰是绝大多数**。
 *
 * 所以改成两条路：
 *   a) 静态成员（`AbortSignal.any`）：根对象名必须命中 ROOTS —— 精确、无噪音。
 *   b) 实例成员（`x.throwIfAborted`）：**不看根对象**，只按末段成员名匹配。
 *      根对象不可知，这是唯一可行的判据。
 *
 * (b) 的代价是误报（任何 `foo.with` 都会撞上 `Array.prototype.with`）。
 * 这是**故意的**：误报只是多写一个幂等垫片（无害、几行代码），
 * 漏报则是整块 UI 打不开（`toSorted` 与 `throwIfAborted` 连炸两次的教训）。
 * 不对称的代价下，宁可误报。
 */
const CHAIN_STOP = new Set([
  'this', 'window', 'globalThis', 'self', 'global',
]);

/** 只有这些根对象开头的链，才按 (a) 精确匹配静态成员。 */
const ROOTS = new Set([
  'AbortSignal', 'AbortController', 'Object', 'Map', 'Set', 'Array', 'String',
  'Promise', 'URL', 'RegExp', 'Uint8Array', 'Int8Array', 'Uint8ClampedArray',
  'Uint16Array', 'Int16Array', 'Uint32Array', 'Int32Array', 'Float32Array',
  'Float64Array', 'BigInt64Array', 'BigUint64Array', 'ArrayBuffer',
  'SharedArrayBuffer', 'DataView', 'Intl', 'WeakRef', 'FinalizationRegistry',
  'AggregateError', 'Symbol', 'CompressionStream', 'DecompressionStream',
  'ReadableStream', 'WritableStream', 'TransformStream', 'TextEncoder',
  'TextDecoder', 'Headers', 'Request', 'Response', 'FormData', 'Blob', 'File',
  'structuredClone', 'reportError', 'queueMicrotask', 'crypto', 'Atomics',
  'Number', 'BigInt', 'Math', 'JSON', 'Reflect', 'Proxy', 'Error',
]);

/**
 * 已知但**故意不补**的缺口。每条都必须写清理由 —— 没有理由的豁免就是掩盖问题。
 *
 * 加东西进来之前先自问：这是「补不了」，还是「不想写」？
 * 只有前者能进这张表。
 */
const EXEMPT = {
  // 流式压缩/解压：**无法用 JS 补齐**。
  // 它要的是流式 deflate-raw，纯 JS 实现需要一个完整的 zlib（inflate+deflate）+
  // ReadableStream/WritableStream 管线，代价与正确性风险都远超收益。
  //
  // 可达性已逐处核对：只在 client.pdf.js 的 PDF **数字签名**读写里用到
  // （compressSignature / decompressSignature），而两处外层都有 try/catch ——
  // 失败只是 warn(`decompressSignature: ...`)，不影响 PDF 预览本身。
  // 即：只在「用户打开带数字签名的 PDF」时才可能退化，且是优雅降级。
  CompressionStream: '流式压缩，纯 JS 补不了；只在 PDF 数字签名路径用到且外层有 try/catch',
  DecompressionStream: '同上',
};

/**
 * 目标内核之下的 API 不需要垫片 —— 它们在目标内核上是原生的。
 * 早先没有这层过滤，`queueMicrotask`（Chrome 71）被报成缺口 —— 纯噪音。
 */
const TARGET_MAJOR_DEFAULT = 74;

/**
 * (b) 路的候选成员名 = FEATURES 里所有 prototype 成员的末段。
 * 只在「链的末段命中这个集合」时才收，否则 137 个文件会产出上万条无关链。
 * 例：`toSorted` / `throwIfAborted` / `replaceAll` / `getOrInsert` …
 */
const INSTANCE_MEMBER_NAMES = new Set(
  Object.keys(FEATURES)
    .filter((k) => k.includes('.prototype.'))
    .map((k) => k.split('.').pop()),
);

/** 从 MemberExpression 节点拼出规范名（含中间的 .prototype）。 */
function memberChain(node) {
  const parts = [];
  let cur = node;
  while (cur) {
    if (cur.type === 'MemberExpression') {
      if (cur.property.type === 'Identifier' && !cur.computed) {
        parts.unshift(cur.property.name);
      } else if (cur.property.type === 'Literal') {
        // 计算属性 obj['foo'] —— 当作静态名处理
        parts.unshift(String(cur.property.value));
      } else {
        return null; // 动态属性名，无法静态判定
      }
      cur = cur.object;
    } else if (cur.type === 'Identifier') {
      parts.unshift(cur.name);
      break;
    } else if (cur.type === 'ThisExpression') {
      parts.unshift('this');
      break;
    } else {
      return null;
    }
  }
  return parts.join('.');
}

/**
 * 遍历 AST，收集用到的 API。三条路：
 *   a) 根对象命中 ROOTS 的成员链 → 全部收下（静态成员精确匹配）
 *   b) 末段命中已知实例成员名 → 收下（根对象是局部变量，追不到，只能按名认）
 *   c) **裸标识符**调用/构造：`new CompressionStream()`、`structuredClone(x)`、
 *      `AggregateError(...)` —— 这类没有成员链，`callee` 直接是 Identifier。
 *
 * ⚠️ (c) 是补出来的：反例验证时发现 `new CompressionStream("gzip")` **完全没被扫到**，
 *    因为当时只处理了 `callee.type === 'MemberExpression'`。凡是「以全局名直接调用」
 *    的 API（结构化克隆、错误上报、各类 Stream 构造器）都会整体漏掉 —— 又是同一类盲区。
 */
function collectChains(ast, sink) {
  /** FEATURES 里的裸全局名（不含 `.` 的键）。 */
  const BARE = new Set(Object.keys(FEATURES).filter((k) => !k.includes('.')));

  const visit = (node) => {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (const n of node) visit(n);
      return;
    }
    if (typeof node.type === 'string') {
      if (
        node.type === 'MemberExpression' ||
        node.type === 'NewExpression' ||
        node.type === 'CallExpression'
      ) {
        const target = node.type === 'MemberExpression' ? node : node.callee;
        if (target && target.type === 'MemberExpression') {
          const chain = memberChain(target);
          if (chain) {
            const root = chain.split('.')[0];
            const leaf = chain.split('.').pop();
            const byRoot = ROOTS.has(root) && !CHAIN_STOP.has(root);
            const byLeaf = INSTANCE_MEMBER_NAMES.has(leaf);
            if (byRoot || byLeaf) sink(chain);
          }
        } else if (target && target.type === 'Identifier' && BARE.has(target.name)) {
          // (c) 裸全局调用：new CompressionStream(...) / structuredClone(...)
          sink(target.name);
        }
      }
    }
    for (const key of Object.keys(node)) {
      if (key === 'type' || key === 'start' || key === 'end' || key === 'loc') continue;
      visit(node[key]);
    }
  };
  visit(ast);
}

/**
 * 把一个链「降级」成 FEATURES 里的规范名。
 *
 * 难点：产物里的写法五花八门，需要对上表里的键。
 *   `AbortSignal.prototype.throwIfAborted`  ⇔ 产物里可能写
 *      `AbortSignal.prototype.throwIfAborted`（少见）
 *      `signal.throwIfAborted`（常见，signal 是变量）
 * 因为变量名无法静态追踪，这里对**实例成员**做宽松匹配：
 * 若链的末段是表里某个 `X.prototype.member` 的 member，且链里出现了 prototype
 * 或链长 ≥ 2，就算命中。宁松勿漏。
 */
function normalize(chain) {
  if (FEATURES[chain]) return chain;

  const segs = chain.split('.');

  // 静态成员：AbortSignal.any / Object.hasOwn / Intl.Segmenter
  for (let i = segs.length - 1; i >= 1; i--) {
    const cand = segs.slice(0, i + 1).join('.');
    if (FEATURES[cand]) return cand;
  }

  // 实例成员：末段是某个 prototype 成员名 → 归到那条
  const last = segs[segs.length - 1];
  for (const key of Object.keys(FEATURES)) {
    if (!key.includes('.prototype.')) continue;
    if (key.split('.').pop() === last) {
      // 要求链里出现 prototype，或链长 ≥ 2（obj.member）
      if (chain.includes('.prototype.') || segs.length >= 2) return key;
    }
  }

  return null;
}

// ── acorn 加载（与 check-frontend-syntax.mjs 同一套自装逻辑）────────────
async function loadAcorn() {
  try {
    return require('acorn');
  } catch {}
  const dir = join(tmpdir(), 'dsh-syntax-check');
  const modPath = join(dir, 'node_modules', 'acorn', 'package.json');
  try {
    statSync(modPath);
  } catch {
    console.error('未找到 acorn，正在安装到临时目录…');
    const { execFileSync } = await import('node:child_process');
    const env = { ...process.env, HTTPS_PROXY: 'http://127.0.0.1:7897', HTTP_PROXY: 'http://127.0.0.1:7897' };
    try {
      execFileSync('npm.cmd', ['install', 'acorn@8', '--prefix', dir, '--no-audit', '--no-fund', '--loglevel=error'], { stdio: 'inherit', env, shell: true });
    } catch {
      console.error('无法安装 acorn，审计未能执行（不是代码问题）');
      process.exit(2);
    }
  }
  try {
    const { createRequire: cr } = await import('node:module');
    return cr(pathToFileURL(join(dir, 'package.json'))).call(null, 'acorn');
  } catch (e) {
    console.error('无法加载 acorn：' + e.message);
    process.exit(2);
  }
}

// ── 收集待扫文件 ────────────────────────────────────────────────────────
function walkJs(root, acc = []) {
  for (const name of readdirSync(root)) {
    const p = join(root, name);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) walkJs(p, acc);
    else if (name.endsWith('.js')) acc.push(p);
  }
  return acc;
}

// ── 主流程 ──────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
let polyfillPath = null;
let targetArg = null;
const roots = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--polyfill') { polyfillPath = argv[++i]; continue; }
  if (argv[i] === '--target') { targetArg = argv[++i]; continue; }
  roots.push(argv[i]);
}
if (!polyfillPath) polyfillPath = 'runtime/webview/webview-polyfill.js';
if (roots.length === 0) {
  console.error('用法: node tools/audit-runtime-apis.mjs [--polyfill <path>] [--target chrome74] <产物根目录> [更多…]');
  process.exit(2);
}

const acorn = await loadAcorn();

// polyfill 已经覆盖了哪些 API（按文本出现即算覆盖 —— 幂等垫片不会误判）
let polyfillText = '';
try { polyfillText = readFileSync(polyfillPath, 'utf8'); } catch (e) {
  console.error(`读不到 polyfill：${polyfillPath}`);
  process.exit(2);
}

const hits = new Map(); // 规范名 → Set(文件)
let scanned = 0;
let parseFailed = 0;

for (const root of roots) {
  let files;
  try { files = walkJs(root); } catch { console.error(`跳过不存在的目录：${root}`); continue; }
  for (const f of files) {
    scanned++;
    let ast;
    try {
      ast = acorn.parse(readFileSync(f, 'utf8'), {
        ecmaVersion: 'latest', sourceType: 'module', allowHashBang: true,
      });
    } catch {
      // 解析失败不算审计结论（语法检查是 check-frontend-syntax.mjs 的活）
      parseFailed++;
      continue;
    }
    const found = new Set();
    collectChains(ast, (c) => found.add(c));
    for (const c of found) {
      const norm = normalize(c);
      if (!norm) continue;
      if (!hits.has(norm)) hits.set(norm, new Set());
      hits.get(norm).add(relative(process.cwd(), f).split(sep).join('/'));
    }
  }
}

console.log(`扫描 ${scanned} 个 js（解析失败 ${parseFailed} 个，交给语法检查器处理）\n`);

// ── 比对：产物用到的 / polyfill 覆盖的 ──────────────────────────────────
//
// ⚠️ 「polyfill 覆盖了吗」的判据不能用朴素 `text.includes(leaf)`：
//    末段名常常是英文常用词（`with` / `at` / `any` / `reason`），
//    正文与注释里随手出现就会**把真缺口误判成已覆盖** —— 而漏报正是
//    连炸两次的那个方向（`toSorted`、`throwIfAborted`）。
//
// 所以：先剥掉注释（消除英文散文噪音），再按 **标识符边界** 匹配，
// 避免 `withResolvers` 被 `with` 命中这类子串误判。
function stripComments(src) {
  // 粗暴但够用：去掉 // 行注释与 /* */ 块注释。
  // 产物 polyfill 是手写代码，不含会误伤的字符串字面量里的 `//`（已抽查）。
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
}

const polyfillCode = stripComments(polyfillText);

function isCovered(api) {
  const leaf = api.split('.').pop();
  // 标识符边界：前后不能再是标识符字符，避免子串误命中
  const re = new RegExp(`(?<![\\w$])${leaf.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w$])`);
  return re.test(polyfillCode);
}

const missing = [];
const covered = [];
const skipped = []; // 低于目标内核 / 已豁免
// ⚠️ `--target` 接受 `chrome74` / `74` 两种写法，所以必须剥掉非数字前缀。
//    直接 `Number('chrome74')` 得到 **NaN**，而 `x <= NaN` 恒为 false ——
//    过滤会静默失效（所有低版本 API 都被报成缺口）。踩过一次。
let targetMajor = TARGET_MAJOR_DEFAULT;
if (targetArg !== null) {
  const parsed = Number(String(targetArg).replace(/\D/g, ''));
  if (!Number.isFinite(parsed) || parsed <= 0) {
    console.error(`--target 无法解析: ${targetArg}（应形如 chrome74 或 74）`);
    process.exit(2);
  }
  targetMajor = parsed;
}

for (const [api, files] of [...hits.entries()].sort()) {
  const info = FEATURES[api];
  const row = { api, chrome: info.chrome, how: info.how, count: files.size, sample: [...files][0] };

  // 目标内核已经是「原生支持」的 → 不需要垫片，报出来只是噪音
  if (info.chrome <= targetMajor) {
    skipped.push({ ...row, why: `Chrome ${info.chrome} ≤ 目标 ${targetMajor}，目标内核原生支持` });
    continue;
  }
  // 已豁免（补不了，或代价远超收益）
  if (EXEMPT[api]) {
    skipped.push({ ...row, why: `已豁免：${EXEMPT[api]}` });
    continue;
  }
  if (isCovered(api)) covered.push(row);
  else missing.push(row);
}

console.log(`目标内核 chrome${targetMajor}\n`);
console.log('=== polyfill 已覆盖 ===');
for (const r of covered) {
  console.log(`  ✅ ${r.api.padEnd(40)} (Chrome ${String(r.chrome).padStart(3)})  ${r.count} 个文件`);
}

console.log('\n=== ❌ 未被 polyfill 覆盖（产物里真的在用）===');
if (missing.length === 0) {
  console.log('  （无缺口）');
} else {
  for (const r of missing.sort((a, b) => b.chrome - a.chrome)) {
    console.log(`  ❌ ${r.api.padEnd(40)} (Chrome ${String(r.chrome).padStart(3)})  ${r.count} 个文件`);
    console.log(`       ${r.how}`);
    console.log(`       例: ${r.sample}`);
  }
}

if (skipped.length > 0) {
  console.log('\n=== 跳过（无需垫片）===');
  for (const r of skipped.sort((a, b) => b.chrome - a.chrome)) {
    console.log(`  ⏭  ${r.api.padEnd(40)} (Chrome ${String(r.chrome).padStart(3)})  ${r.why}`);
  }
}

console.log(`\n合计用到 ${hits.size} 个受跟踪 API；缺口 ${missing.length} 个，跳过 ${skipped.length} 个。`);
process.exit(missing.length === 0 ? 0 : 1);
