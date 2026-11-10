import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import http from 'node:http';
import { once } from 'node:events';

const source = async (name) => readFile(new URL(`../../${name}/lib/client.js`, import.meta.url), 'utf8');
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

function environment(bridge = {}) {
  const timers = new Map(), observers = new Set(), listeners = new Set(), modules = new Map();
  let next = 0;
  function target(base = {}) {
    return Object.assign(base, {
      addEventListener(type, fn, options) { listeners.add({ target: this, type, fn, capture: !!(typeof options === 'boolean' ? options : options?.capture) }); },
      removeEventListener(type, fn, options) {
        const capture = !!(typeof options === 'boolean' ? options : options?.capture);
        for (const item of listeners) if (item.target === this && item.type === type && item.fn === fn && item.capture === capture) listeners.delete(item);
      },
    });
  }
  function element(tagName = 'div') {
    const attrs = new Map(), classes = new Set();
    return target({
      nodeType: 1, tagName: tagName.toUpperCase(), dataset: {}, children: [], textContent: '', innerText: '',
      style: { setProperty() {}, removeProperty() {}, getPropertyValue() { return ''; } },
      classList: { add(...xs) { xs.forEach(x => classes.add(x)); }, remove(...xs) { xs.forEach(x => classes.delete(x)); }, contains: x => classes.has(x), [Symbol.iterator]: () => classes[Symbol.iterator]() },
      setAttribute(k, v) { attrs.set(k, String(v)); }, getAttribute: k => attrs.get(k) ?? null,
      hasAttribute: k => attrs.has(k), removeAttribute(k) { attrs.delete(k); },
      querySelector() { return null; }, querySelectorAll() { return []; }, closest() { return null; },
      appendChild(child) { this.children.push(child); child.parentElement = this; return child; },
      removeChild(child) { this.children = this.children.filter(x => x !== child); },
      remove() { this.parentElement?.removeChild(this); }, matches() { return false; },
      getBoundingClientRect() { return { top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0 }; },
      focus() {}, select() {}, setSelectionRange() {},
    });
  }
  const document = target({
    title: '已接受标题 — DeepSeek Harness', hidden: false, readyState: 'complete', styleSheets: [],
    documentElement: element('html'), body: element('body'), head: element('head'),
    createElement: element, querySelector() { return null; }, querySelectorAll() { return []; }, execCommand() { return true; },
  });
  const schedule = (fn, ms, interval = false) => { const id = ++next; timers.set(id, { fn, ms, interval }); return id; };
  const clear = id => timers.delete(id);
  class Observer {
    constructor(fn) { this.fn = fn; }
    observe() { observers.add(this); }
    disconnect() { observers.delete(this); }
  }
  const clipboard = { writeText: async () => 'original' };
  const window = target({
    document, navigator: { clipboard }, DSHAndroid: bridge, innerWidth: 390, innerHeight: 800,
    setTimeout: (fn, ms) => schedule(fn, ms), clearTimeout: clear,
    setInterval: (fn, ms) => schedule(fn, ms, true), clearInterval: clear,
    matchMedia: () => ({ matches: false }), alert() {}, fetch: () => new Promise(() => {}),
    localStorage: { getItem() { return null; }, setItem() {} },
    __ModuleLoader__: { load: def => modules.set(def.id, def) },
  });
  class Input {}
  Object.defineProperty(Input.prototype, 'type', { configurable: true, get() { return 'text'; }, set() {} });
  Input.prototype.setAttribute = function() {};
  window.HTMLInputElement = Input;
  const sandbox = vm.createContext({
    window, document, navigator: window.navigator, localStorage: window.localStorage,
    MutationObserver: Observer, HTMLInputElement: Input,
    setTimeout: window.setTimeout, clearTimeout: clear, setInterval: window.setInterval, clearInterval: clear,
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: clear,
    getComputedStyle: () => ({ getPropertyValue: () => '#fff', backgroundColor: '#fff' }),
    queueMicrotask, console: { log() {}, warn() {}, error() {} },
  });
  return {
    window, document, timers, observers, listeners, clipboard,
    async load(name, react) {
      vm.runInContext(await source(name), sandbox, { filename: name + '/lib/client.js' });
      const def = modules.get(name);
      assert.ok(def, `ModuleLoader received ${name}`);
      return def.factory(id => { if (id === 'react' && react) return react; throw new Error('missing ' + id); });
    },
    fire(ms) {
      for (const [id, item] of [...timers]) if (item.ms === ms) { if (!item.interval) timers.delete(id); item.fn(); }
    },
    mutate() { for (const observer of observers) observer.fn([]); },
  };
}

function context(services = {}, react) {
  const disposers = [], registrations = new Map(), events = new Set();
  const locale = new Map();
  const ctx = {
    ...services,
    effect(fn) { const dispose = fn(); if (typeof dispose === 'function') disposers.push(dispose); return dispose; },
    get(name) { return services[name]; },
    inject(names, fn) { if (names.every(name => name in ctx)) fn(ctx); },
    locale: {
      bind(ns) { return key => locale.get(ns)?.zh?.[key] ?? key; },
      register(ns, values) { locale.set(ns, values); return () => locale.delete(ns); },
    },
    slots: {
      inject(name, fn) { ctx.effect(fn); },
      register(meta, component) {
        registrations.set(meta.id, { meta, component });
        return () => { react?.unmountAll(); registrations.delete(meta.id); };
      },
    },
    on(name, fn) { const record = { name, fn }; events.add(record); return () => events.delete(record); },
    dispose() { while (disposers.length) disposers.pop()(); },
  };
  return { ctx, registrations, events, locale };
}

function reactMock() {
  const instances = new Set(); let current;
  const React = {
    createElement(type, props, ...children) { return { type, props: props ?? {}, children: children.flat(Infinity).filter(x => x != null && x !== false) }; },
    useState(initial) {
      const instance = current, i = instance.cursor++;
      if (!(i in instance.state)) instance.state[i] = typeof initial === 'function' ? initial() : initial;
      return [instance.state[i], value => { if (instance.disposed) instance.lateWrites++; else instance.state[i] = typeof value === 'function' ? value(instance.state[i]) : value; }];
    },
    useRef(value) { const i = current.cursor++; return current.state[i] ??= { current: value }; },
    useCallback(fn) { current.cursor++; return fn; },
    useEffect(fn) { current.cursor++; current.effects.push(fn); },
    mount(component) {
      const instance = { state: [], cursor: 0, effects: [], cleanups: [], lateWrites: 0, disposed: false };
      current = instance;
      instance.tree = component({});
      current = null;
      for (const effect of instance.effects) { const cleanup = effect(); if (typeof cleanup === 'function') instance.cleanups.push(cleanup); }
      // 让挂载 effect 的同步状态读回参与下一帧；不重复安装 effect。
      instance.render = () => {
        instance.cursor = 0; instance.effects = []; current = instance;
        instance.tree = component({}); current = null;
      };
      instance.render();
      instances.add(instance);
      return instance;
    },
    unmountAll() { for (const instance of instances) { instance.disposed = true; while (instance.cleanups.length) instance.cleanups.pop()(); } },
  };
  return React;
}
function nodes(tree, predicate) {
  if (!tree || typeof tree !== 'object') return [];
  return [...(predicate(tree) ? [tree] : []), ...(tree.children ?? []).flatMap(child => nodes(child, predicate))];
}
const text = tree => typeof tree === 'string' ? tree : (tree?.children ?? []).map(text).join('');

test('基础布局无账号服务仍实际 apply，并完成原有 dispose 路径', async () => {
  const env = environment();
  const plugin = await env.load('dsh-android-mobile');
  assert.deepEqual(Array.from(plugin.inject), []);
  const { ctx } = context();
  const dispose = plugin.apply(ctx);
  assert.equal(env.document.documentElement.hasAttribute('data-mobile'), true);
  assert.ok(env.document.head.children.some(el => el.textContent.includes('[data-mobile]')));
  assert.equal(typeof dispose, 'function');
  dispose(); ctx.dispose();
});

test('余额独立依赖、取数费用、计时器/事件释放与二次启停', async () => {
  const reports = []; let requests = 0, seq = 0;
  const env = environment({
    bubbleBadgeConfig: () => JSON.stringify({ refreshEverySec: 30, refreshOnTap: true, tapRefreshMinIntervalSec: 1 }),
    bubbleTapRefreshSeq: () => seq, bubbleReportBalance: json => reports.push(JSON.parse(json)),
  });
  const plugin = await env.load('dsh-android-balance');
  assert.deepEqual(Array.from(plugin.inject), ['remote', 'remote.account']);
  const remote = { account: { async getBalance(input) { requests++; assert.equal(input.version, '0.3.0'); return { ok: true, value: { status: 'ready', value: [{ currency: 'USD', balance: 3 }] } }; } } };
  for (let cycle = 0; cycle < 2; cycle++) {
    const { ctx, events } = context({ remote });
    plugin.apply(ctx); await flush();
    assert.equal(env.timers.size, 2);
    assert.equal(events.size, 1);
    assert.equal(reports.at(-1).wallets[0].balance, 3);
    const event = [...events][0].fn;
    event(null, { type: 'assistant/message', data: { usage: { inputTokens: 1000000, cacheReadTokens: 1000000, outputTokens: 1000000 } } });
    assert.equal(reports.at(-1).costLast, 1.44);
    assert.equal(reports.at(-1).costSession, 1.44);
    event(null, { type: 'turn/end' }); await flush();
    seq++; env.fire(300); await flush();
    env.fire(30000); await flush();
    const before = reports.length;
    ctx.dispose(); ctx.dispose();
    event(null, { type: 'assistant/message', data: { usage: { inputTokens: 1000000 } } });
    assert.equal(reports.length, before);
    assert.equal(env.timers.size, 0); assert.equal(events.size, 0);
  }
  assert.equal(requests, 8);
});

test('余额卸载期间未完成的请求不会回写，缺失通道可安全释放', async () => {
  let resolve; const reports = [];
  const env = environment({ bubbleReportBalance: json => reports.push(json) });
  const plugin = await env.load('dsh-android-balance');
  const { ctx } = context({ remote: { account: { getBalance: () => new Promise(r => { resolve = r; }) } } });
  plugin.apply(ctx); ctx.dispose();
  resolve({ ok: true, value: { status: 'ready', value: [{ balance: 100 }] } });
  await flush(); assert.equal(reports.length, 0); assert.equal(env.timers.size, 0);
  const missing = context(); plugin.apply(missing.ctx); missing.ctx.dispose();
  assert.equal(env.timers.size, 0); assert.equal(missing.events.size, 0);
});

test('宿主状态/转录保持原数据，观察与节流 timer 全释放并恢复剪贴板', async () => {
  const status = [], transcripts = [], copies = [];
  const env = environment({ onStatus: json => status.push(JSON.parse(json)), onTranscript: json => transcripts.push(JSON.parse(json)), writeClipboard: text => { copies.push(text); return true; } });
  const original = env.clipboard.writeText;
  const message = (kind, value, part = '') => ({
    textContent: value,
    getAttribute: key => key === 'data-chat-flow-kind' ? kind : key === 'data-chat-group-part' ? part : null,
    querySelector() { return null; },
  });
  const messages = [message('user', '用户问题'), message('assistant-step', '思考内容', 'reasoning'), message('assistant-step', '完整回复', 'response')];
  const root = { querySelectorAll: () => messages };
  env.document.querySelector = selector => selector === '.wSkVaW_viewArea' ? root : selector === '.uV2eYG_primary' ? { getAttribute: () => '停止生成' } : null;
  env.document.querySelectorAll = selector => selector === '.O_Ebla_title' ? [{ textContent: '正在运行命令', getAttribute: () => 'command' }] : selector.includes('.hWmORq_root') ? [messages[2]] : [];
  const plugin = await env.load('dsh-android-host-bridge');
  for (let cycle = 0; cycle < 2; cycle++) {
    const { ctx } = context(); plugin.apply(ctx);
    assert.equal(status.at(-1).title, '已接受标题');
    assert.equal(status.at(-1).bg, '#FFFFFF');
    assert.equal(status.at(-1).running, true);
    assert.equal(status.at(-1).text, '正在运行命令');
    assert.equal(status.at(-1).body, '完整回复');
    assert.deepEqual(transcripts.at(-1).items.map(item => [item.role, item.text, item.running]), [['user', '用户问题', false], ['reasoning', '思考内容', false], ['assistant', '完整回复', true]]);
    await env.clipboard.writeText('复制内容'); assert.equal(copies.at(-1), '复制内容');
    assert.equal(env.observers.size, 1); assert.equal(env.timers.size, 2);
    env.mutate(); env.fire(300);
    assert.equal(env.timers.size, 4);
    ctx.dispose();
    assert.equal(env.observers.size, 0); assert.equal(env.timers.size, 0);
    assert.equal(env.clipboard.writeText, original);
  }
  assert.equal(status.length, 2);
});

test('业务设置四个原槽位实际挂载、操作与卸载释放', async () => {
  const React = reactMock(); let resolveFetch;
  const env = environment({ rootState: () => '{}', rootRecheck() {}, shizukuState: () => '{"installed":true,"binder":true,"granted":false}', shizukuRequestPermission() {}, logSummary: () => '{}', openLogs() {}, bubbleState: () => '{}', keyboardAvoidState: () => '{}' });
  env.window.fetch = () => new Promise(resolve => { resolveFetch = resolve; });
  const plugin = await env.load('dsh-android-mobile-settings', React);
  const { ctx, registrations, locale } = context({}, React); plugin.apply(ctx);
  assert.deepEqual([...registrations.keys()], ['dsh-android-privileged', 'dsh-android-bubble', 'dsh-android-logs', 'dsh-android-contact']);
  const instances = [...registrations.values()].map(({ component }) => React.mount(component));
  const privileged = instances[0];
  const rootTab = nodes(privileged.tree, node => node.type === 'button' && text(node).includes('root'))[0];
  assert.ok(rootTab, 'root 标签入口'); rootTab.props.onClick(); privileged.render();
  const rootRecheck = nodes(privileged.tree, node => node.type === 'button' && text(node).includes('重新检测'))[0];
  assert.ok(rootRecheck); rootRecheck.props.onClick();
  const shizukuTab = nodes(privileged.tree, node => node.type === 'button' && text(node).includes('Shizuku'))[0];
  assert.ok(shizukuTab); shizukuTab.props.onClick(); privileged.render();
  for (const title of ['授权']) {
    const buttons = nodes(privileged.tree, node => node.type === 'button' && text(node).includes(title));
    for (const button of buttons) button.props.onClick?.();
  }
  const adbTab = nodes(privileged.tree, node => node.type === 'button' && text(node).includes('ADB'))[0];
  assert.ok(adbTab); adbTab.props.onClick(); privileged.render();
  const copyCommand = nodes(privileged.tree, node => node.type === 'button' && text(node) === '复制')[0];
  assert.ok(copyCommand); copyCommand.props.onClick();
  const logsOpen = nodes(instances[2].tree, node => node.type === 'button' && text(node).includes('打开日志界面'))[0];
  assert.ok(logsOpen); logsOpen.props.onClick();
  const contact = nodes(instances[3].tree, node => node.type === 'button')[0];
  assert.ok(contact); contact.props.onClick();
  assert.ok([...env.timers.values()].some(timer => timer.ms === 700), 'root 轮询确实执行');
  assert.ok([...env.timers.values()].some(timer => timer.ms === 600), 'Shizuku 轮询确实执行');
  assert.ok([...env.timers.values()].some(timer => timer.ms === 300), '日志补读确实执行');
  assert.ok(env.listeners.size > 0);
  ctx.dispose();
  resolveFetch({ ok: true, json: async () => ({}) }); await flush();
  assert.equal(env.timers.size, 0); assert.equal(env.listeners.size, 0);
  assert.equal(registrations.size, 0); assert.equal(locale.size, 0);
  assert.ok(instances.every(instance => instance.lateWrites === 0));
});

test('设置宿主注册真实 HTTP 路由、方法与卸载恢复，基础宿主无依赖', async () => {
  const settings = await import('../../dsh-android-mobile-settings/lib/index.js');
  const mobile = await import('../../dsh-android-mobile/lib/index.js');
  assert.deepEqual(settings.inject, ['webServer']);
  assert.equal(mobile.inject, undefined);
  assert.equal(mobile.apply({}), undefined);
  const previousRunner = process.env.DSH_PRIV_RUNNER;
  process.env.DSH_PRIV_RUNNER = '/__dsh_mobile_feature_test_missing_runner__';
  const routes = new Set();
  const server = http.createServer((req, res) => {
    const route = [...routes].find(route => route.path === req.url);
    if (route) route.handler(req, res); else { res.writeHead(404); res.end(); }
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const url = `http://127.0.0.1:${server.address().port}/api/dsh-android/privileged`;
  try {
    for (let cycle = 0; cycle < 2; cycle++) {
      const { ctx } = context({ webServer: { register(route) { routes.add(route); return () => routes.delete(route); } } });
      settings.apply(ctx); assert.equal(routes.size, 1);
      const get = await fetch(url);
      assert.equal(get.status, 200); assert.equal(get.headers.get('cache-control'), 'no-store');
      const body = await get.json(); assert.equal(body.identity.kind, 'no-runner'); assert.equal(body.runner.ok, false);
      const head = await fetch(url, { method: 'HEAD' }); assert.equal(head.status, 200); assert.equal(await head.text(), '');
      const post = await fetch(url, { method: 'POST' }); assert.equal(post.status, 405); assert.equal(post.headers.get('allow'), 'GET, HEAD');
      ctx.dispose(); assert.equal(routes.size, 0);
      assert.equal((await fetch(url)).status, 404);
    }
  } finally {
    if (previousRunner === undefined) delete process.env.DSH_PRIV_RUNNER; else process.env.DSH_PRIV_RUNNER = previousRunner;
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
});

test('业务设置缺失可选 React 不影响模块加载及关闭', async () => {
  const env = environment(); const plugin = await env.load('dsh-android-mobile-settings');
  const { ctx, registrations } = context(); plugin.apply(ctx); ctx.dispose();
  assert.equal(registrations.size, 0); assert.equal(env.timers.size, 0);
});
