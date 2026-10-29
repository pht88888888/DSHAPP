/**
 * 冒烟测试：**这个插件到底能不能跑起来**。
 *
 * ## 为什么必须有这个文件
 *
 * 1.2.79 跑过 1471 项断言全绿，然后真机上插件压根没加载成功：
 *
 *     dsh: fatal load failure: ReferenceError: refreshOfficialModelCatalog is not defined
 *         at .../dsh-jiuling-remote-mobile/lib/index.js:6487:8
 *
 * 那 1471 项测的全是我「猜」有问题的地方（图标识别、缓存 key、定时器），
 * 而「文件能不能加载、apply 能不能执行、effect 能不能跑」从没被测过。
 * `node --check` 只查语法；静态扫描查不到运行时作用域。
 *
 * 所以这里只做一件事：**真的加载、真的执行、真的跑 effect**。
 * 任何一步抛错就是失败 —— 因为真机上任何一步抛错都等于插件失效。
 *
 * 定位：这个文件应该放在 `npm test` 或发布前必跑的清单第一位。
 * 它花不到 2 秒，能挡住最致命的一类故障。
 */
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';

const ENTRY = new URL('../lib/index.js', import.meta.url).href;
const CLIENT = new URL('../lib/client.js', import.meta.url);

let failed = 0;
const step = async (name, fn) => {
  currentStep = name;
  try {
    await fn();
    console.log('  ✓ ' + name);
  } catch (error) {
    failed += 1;
    console.log('  ✗ ' + name + '\n      ' + String(error && error.message ? error.message : error));
  }
};

/*
 * ⚠️⚠️ 全局捕获 unhandled rejection（1.2.81）
 *
 * 插件里最致命的一类 bug（悬空引用）发生在微任务回调里：handler 返回了，
 * 异常才冒出来。Node 默认会把未处理的 rejection 当致命错误 —— 真机上就是
 * 「dsh: fatal load failure」+ 整插件失效。
 *
 * 所以测试必须主动接住它们，否则只会看到进程崩掉、看不到是哪个引用悬空。
 */
const unhandled = [];
let currentStep = '(启动)';
process.on('unhandledRejection', (reason) => {
  const where = (reason && reason.stack) ? '\n        ' + String(reason.stack).split('\n').slice(1, 4).join('\n        ') : '';
  unhandled.push(String(reason && reason.message ? reason.message : reason) + where + '\n        [发生在步骤: ' + currentStep + ']');
});
process.on('uncaughtException', (error) => {
  const where = error && error.stack ? '\n        ' + String(error.stack).split('\n').slice(1, 4).join('\n        ') : '';
  unhandled.push(String(error && error.message ? error.message : error) + where + '\n        [发生在步骤: ' + currentStep + ']');
});

console.log('\n══ 宿主侧（index.js）══');

let mod;
mod = await import(ENTRY).catch((error) => {
  failed += 1;
  console.log('  ✗ 模块加载失败\n      ' + String(error && error.message ? error.message : error));
  return null;
});
if (mod) console.log('  ✓ 模块能加载（ESM 解析 + 顶层代码执行）');

if (mod) {
  await step('导出 apply', () => assert.equal(typeof mod.apply, 'function'));

  /**
   * 造一个「最穷」的 ctx：所有可选服务都是 undefined。
   *
   * ## 为什么用最穷的
   *
   * 1.2.81 的三个崩溃（page / attachment / follow）都是
   * `ctx.sessionController === undefined` 时抛的。真机上服务可能因为
   * 加载顺序、版本差异、用户关插件而缺失 —— 那时插件必须**退化成无操作**，
   * 而不是把整个引擎带走。
   *
   * 用完整 mock 反而测不出这类问题。
   */
  const makeCtx = () => {
    const effects = [];
    return {
      effects,
      /*
       * ⚠️ `connection` 必须给：桥路由的准入门是 fail-closed ——
       * 没有 connection 服务时 `bridgeRejection` 直接返回 503，
       * handler 会在 `handleBridge({io:…})` **之前** early return，
       * 于是 1.2.79 那个 ReferenceError 永远触发不到。
       * （这正是本测试第一版没抓到它的原因。）
       */
      get: (name) => (name === 'connection' ? { requestRejection: () => undefined } : undefined),
      effect: (fn) => {
        effects.push(fn);
        return () => {};
      },
      logger: { warn: () => {}, info: () => {}, error: () => {}, debug: () => {} },
      webServer: { register: () => () => {} },
      inject: () => {},
      on: () => () => {},
      slots: {},
      sessions: {},
      remote: {},
    };
  };

  await step('apply(ctx) 在最穷 ctx 下不抛错', () => {
    const ctx = makeCtx();
    mod.apply(ctx);
  });

  await step('每个 ctx.effect 回调执行都不抛错', () => {
    const ctx = makeCtx();
    mod.apply(ctx);
    assert.ok(ctx.effects.length > 0, '一个 effect 都没注册，apply 大概没执行到');
    const errors = [];
    for (const fn of ctx.effects) {
      try {
        fn();
      } catch (error) {
        errors.push(String(error && error.message ? error.message : error));
      }
    }
    assert.deepEqual(errors, [], '有 ' + errors.length + ' 个 effect 抛错：' + errors.join(' | '));
  });

  /**
   * ## 必须真的调用 webServer 的 handler（1.2.81 补）
   *
   * 1.2.79 那个致命 bug —— `ReferenceError: refreshOfficialModelCatalog
   * is not defined` —— 发生在：
   *
   *     ctx.webServer.register({ handler: (req,res) => { ... handleBridge({ io: {...} }) } })
   *
   * `handleBridge({io: {...}})` 是在 **handler 内部**求值的，所以只注册不调用
   * 永远触发不到那个 ReferenceError。这也是我最初那版冒烟测试没抓到它的原因
   * —— 它只跑了 effect，没跑 handler。
   *
   * 这里用最小 req/res 把 handler 真的走一遍。
   */
  await step('webServer handler 真的能被调用（覆盖 handleBridge 的 io 求值）', async () => {
    const registered = [];
    /* 必须在 apply 之前就把 register 换成收集器 —— 路由是在 apply 里注册的 */
    const ctx = makeCtx();
    ctx.webServer = { register: (o) => { registered.push(o); return () => {}; } };
    mod.apply(ctx);
    /* 路由可能注册在 effect 里（ctx.effect 回调执行时才跑） */
    for (const fn of ctx.effects) {
      try { fn(); } catch (error) { /* 上面那步已经单独测过 */ }
    }

    assert.ok(registered.length > 0, '一个 webServer 路由都没注册（apply 或 effect 没跑到注册处）');

    const errors = [];
    for (const route of registered) {
      if (typeof route.handler !== 'function') continue;
      // 最小 req/res：让 handler 跑到 handleBridge 的 io 求值那一步
      const req = {
        method: 'POST',
        url: route.path || '/',
        headers: { 'content-type': 'application/json', host: 'localhost' },
        on: (event, cb) => {
          if (event === 'data') cb(Buffer.from('{"op":"__smoke__"}'));
          if (event === 'end') cb();
          return req;
        },
        destroy: () => {},
      };
      const res = {
        writeHead: () => {},
        end: () => {},
        statusCode: 0,
        /* handler 内部有些路径会读 res.body（例如转发/流式），给个空值避免误报 */
        body: null,
      };
      try {
        await route.handler(req, res);
      } catch (error) {
        // ReferenceError 才是真 bug（悬空引用）；入参不对导致的其它错误可以接受
        if (error instanceof ReferenceError) {
          errors.push(String(route.path) + ' → ' + error.message);
        }
      }
    }

    /*
     * ⚠️⚠️ **必须等一下，并监听 unhandledRejection**（1.2.81 的关键教训）
     *
     * 1.2.79 那个 `refreshOfficialModelCatalog is not defined` 是我漏掉的最重要一环：
     * 它发生在 `readBodyLimited(req).then((read) => { ... handleBridge({io:{...}}) })`
     * 的**微任务回调**里，所以 `handler()` 立刻返回、`await` 也等不到它 ——
     * 它变成 unhandled rejection，在真机上直接把整个插件带走。
     *
     * try/catch 在这里**毫无作用**，必须靠这个全局钩子。
     */
    await new Promise((resolve) => setTimeout(resolve, 60));

    assert.deepEqual(errors, [], 'handler 里抛 ReferenceError（真机上会带走整个插件）：' + errors.join(' | '));
  });

  /*
   * ⚠️ 这里**不能**拿 `handleBridge({}, {})` 当测试。
   *
   * 第一版这么写，测出 `Cannot read properties of undefined (reading 'body')`
   * —— 那不是产品 bug，是我传了空 io：函数本来就要读 io.body，传空对象必然崩。
   * 等于我自己造了个假失败，还差点去改源码。
   *
   * 真正要验的是「悬空引用」：`handleBridge` 是模块级函数，它引用的
   * `workspaceMap` / `pendingPlans` / `refreshOfficialModelCatalog` 都必须由
   * 调用方经 io 显式传进来，漏传就是 ReferenceError。
   *
   * 这条路径已被上面「webServer handler 真的能被调用」覆盖 ——
   * 那里会走真实 handler、构造完整 io、真的进 handleBridge。
   */
  await step('handleBridge 是模块级导出（io 契约由 handler 测试覆盖）', () => {
    assert.equal(typeof mod.handleBridge, 'function');
  });
}

console.log('\n══ 前端侧（client.js）══');

await step('client.js 能通过 chrome73 解析（无 ?. / ??）', () => {
  const source = readFileSync(CLIENT, 'utf8');
  // 剔除注释与字符串后检查 —— 注释里为说明历史会引用这些写法
  const stripped = source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
    .replace(/'(?:[^'\\]|\\.)*'/g, "''")
    .replace(/"(?:[^"\\]|\\.)*"/g, '""');
  const bad = [];
  if (/\?\./.test(stripped)) bad.push('?.(可选链)');
  if (/\?\?/.test(stripped)) bad.push('??(空值合并)');
  assert.deepEqual(bad, [], 'chrome73 上会整个文件解析失败：' + bad.join(', '));
});

await step('client.js 能求值（模块包装函数可构造）', () => {
  const source = readFileSync(CLIENT, 'utf8');
  // 官方装载器就是这么包的，构造成功说明没有语法错误
  new Function('return (function (h, require, window, document, React) {' + source + '\n})')();
});

await step('PLUGIN_VERSION 与 package.json 一致', () => {
  const source = readFileSync(CLIENT, 'utf8');
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  const m = source.match(/var PLUGIN_VERSION = '([^']+)'/);
  assert.ok(m, '找不到 PLUGIN_VERSION');
  assert.equal(m[1], pkg.version);
});

await new Promise((resolve) => setTimeout(resolve, 120));
await step('全局没有任何未捕获异常', () => {
  assert.deepEqual(unhandled, [], '有未捕获异常（真机上会让插件整体加载失败）：' + unhandled.join(' | '));
});

console.log('\n════════ ' + (failed === 0 ? '全部通过' : failed + ' 项失败') + ' ════════\n');
process.exit(failed === 0 ? 0 : 1);
