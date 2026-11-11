#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');
const protocol = JSON.parse(read('contracts/bridge/protocol.json'));
const page = read('web/contract/src/main/java/com/dsh/android/web/PageBridge.kt');
const system = read('web/host/src/system/java/com/dsh/android/web/SystemWebHost.kt');
const gecko = read('web/host/src/gecko/java/com/dsh/android/web/GeckoWebHost.kt');
const content = read('web/host/src/gecko/assets/dsh-bridge/content.js');
const client = read('plugins/android/components/dsh-android-browser-pane/lib/client.js');
const annotations = read('web/contract/src/main/java/com/dsh/android/web/BridgeProtocolAnnotations.kt');
let checks = 0;
function test(name, fn) { fn(); checks++; console.log(`PASS ${name}`); }
async function testAsync(name, fn) { await fn(); checks++; console.log(`PASS ${name}`); }
const type = { String: 'string', Boolean: 'boolean', Int: 'integer', Unit: 'undefined' };

// 比对完整签名，而不是只搜索方法字符串。
const signatures = [...page.matchAll(/^    fun (\w+)\(([^)]*)\)(?:: (\w+))?/gm)];
test('协议覆盖全部 PageBridge 方法与参数/原生返回类型', () => {
  assert.deepEqual(Object.keys(protocol.methods).sort(), signatures.map((m) => m[1]).sort());
  for (const [, name, args, returns = 'Unit'] of signatures) {
    const params = args ? args.split(',').map((arg) => {
      const [name, kt] = arg.trim().split(': '); return { name, type: type[kt] };
    }) : [];
    assert.deepEqual(protocol.methods[name].params, params, name);
    assert.equal(protocol.methods[name].nativeReturns, type[returns], name);
  }
});
test('System JavaScriptInterface 包装的参数与真实返回一致', () => {
  const wrappers = [...system.matchAll(/@android.webkit.JavascriptInterface\s+fun (\w+)\(([^)]*)\)(?:: (\w+))?\s*(=|\{)/g)];
  assert.equal(wrappers.length, signatures.length);
  for (const [, name, args, explicit, bodyKind] of wrappers) {
    const contract = protocol.methods[name];
    assert.ok(contract, name);
    assert.deepEqual(args ? args.split(',').map((arg) => type[arg.trim().split(': ')[1]]) : [], contract.params.map((p) => p.type), name);
    const returns = explicit ? type[explicit] : bodyKind === '{' ? 'undefined' : contract.nativeReturns;
    assert.equal(returns, contract.system.returns.type, name);
  }
  assert.match(system, /annotationResponse\(annotations\.request\(requestId\)\)/);
  assert.match(system, /aiAnnotateAck\(requestId: String\) \{ if \(isAlive\(\)\) consumeAnnotation\(requestId\) \}/);
  assert.match(system, /postToUi \{ annotations.acknowledge\(requestId\) \}/);
  assert.match(system, /postToUi \{ webView\.evaluateJavascript\(BridgeProtocol\.resultScript\(response\), null\) \}/);
});

function eventWindow() {
  const listeners = new Map();
  return {
    addEventListener(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    dispatchEvent(event) { for (const fn of [...(listeners.get(event.type) || [])]) fn(event); },
    listenerCount(name) { return listeners.get(name)?.size || 0; },
  };
}
function createGecko() {
  const sent = [];
  let receive, disconnect;
  const window = eventWindow();
  const context = vm.createContext({ window, console: { log() {}, info() {}, warn() {}, error() {} },
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    HTMLAnchorElement: function () {}, setTimeout, clearTimeout, btoa: (s) => Buffer.from(s, 'binary').toString('base64') });
  context.HTMLAnchorElement.prototype.click = () => {};
  window.eval = (script) => vm.runInContext(script, context);
  const isolated = vm.createContext({ window: { wrappedJSObject: window },
    exportFunction: (fn, target) => { assert.equal(target, window); return fn; },
    browser: { runtime: { connectNative: () => ({ postMessage: (msg) => sent.push(JSON.parse(JSON.stringify(msg))),
      onMessage: { addListener(fn) { receive = fn; } }, onDisconnect: { addListener(fn) { disconnect = fn; } } }) } },
    setTimeout: () => 1, clearTimeout, console });
  vm.runInContext(content, isolated, { filename: 'content.js' });
  return { window, sent, receive: (msg) => receive(msg), disconnect: () => disconnect() };
}
const bridge = createGecko();
const fixtures = {
  root: '{"known":true}', bubble: '{"enabled":true}', appearance: '{"size":72}', pairingEnabled: true,
  logSummary: '{"total":2}', shizuku: '{"binder":true}', aiBrowser: 'visible',
  bridgeCapabilities: JSON.stringify({ protocolVersion: 1, capabilities: { 'protocol.capabilities': 1, 'annotation.request': 1 } }),
  badgeConfig: '{"refreshEverySec":20}', tapRefreshSeq: 5, keyboardAvoid: '{"enabled":true,"a11y":true}',
};
bridge.receive({ type: 'snapshot', ...fixtures });
test('真实 Gecko content script 暴露全方法且参数/快照/乐观返回吻合协议', () => {
  assert.deepEqual(Object.keys(bridge.window.DSHAndroid).sort(), Object.keys(protocol.methods).sort());
  for (const [name, method] of Object.entries(protocol.methods)) {
    if (name === 'aiAnnotateResult') continue;
    const args = method.params.map((p, i) => p.type === 'boolean' ? true : `arg-${i}`);
    const before = bridge.sent.length;
    const result = bridge.window.DSHAndroid[name](...args);
    assert.equal(typeof result, method.gecko.returns.type === 'integer' ? 'number' : method.gecko.returns.type, name);
    if (method.gecko.mode === 'snapshot') assert.equal(result, fixtures[method.gecko.field], name);
    else {
      const message = bridge.sent.slice(before).find((m) => m.call === name);
      assert.ok(message, name);
      assert.equal(message.type, 'invoke');
      assert.deepEqual(message.args, args, name);
      // 对应原生分派不是一个同名空占位：检查完整方法调用及参数索引/类型。
      const at = gecko.indexOf(`"${name}" ->`);
      assert.ok(at >= 0, `dispatch ${name}`);
      const branch = gecko.slice(at, gecko.indexOf('\n                    "', at + name.length + 6));
      if (!['aiAnnotateRequest', 'aiAnnotateAck'].includes(name)) {
        assert.ok(branch.includes(`b.${name}(`), `native target ${name}`);
        method.params.forEach((p, i) => assert.ok(branch.includes(`args.opt${p.type === 'boolean' ? 'Boolean' : 'String'}(${i}`), `${name} argument ${i}`));
      }
    }
  }
});
test('Gecko legacy 结果与 browser 可见性分开，空串→结果→消费一次', () => {
  assert.equal(bridge.window.DSHAndroid.aiAnnotateResult(), '');
  assert.equal(bridge.sent.at(-1).call, 'aiAnnotateResult');
  const count = bridge.sent.length;
  assert.equal(bridge.window.DSHAndroid.aiAnnotateResult(), '');
  assert.equal(bridge.sent.length, count);
  const payload = JSON.stringify({ png: 'cG5n', text: 'question' });
  bridge.receive({ type: 'annotationLegacy', result: payload });
  assert.equal(bridge.window.DSHAndroid.aiAnnotateResult(), payload);
  assert.equal(bridge.window.DSHAndroid.aiAnnotateResult(), '');
  assert.equal(bridge.window.DSHAndroid.aiBrowserState(), 'visible');
  const snapshot = gecko.slice(gecko.indexOf('private fun pushSnapshot()'), gecko.indexOf('private fun postToExtension'));
  assert.ok(!snapshot.includes('aiAnnotateResult('));
  assert.ok(!snapshot.includes('peekAnnotation('));
  assert.match(gecko, /"aiAnnotateResult" -> postToExtension\(JSONObject\(\)[\s\S]*?\.put\("result", b\.aiAnnotateResult\(\)\)\)/);
});
test('Gecko 新响应事件在页面世界创建并保留完整 JSON 结构', () => {
  const response = { protocolVersion: 1, requestId: 'id', method: 'aiAnnotateRequest', ok: true,
    result: { png: 'cG5n', text: '引号"\n脚本</script>' }, error: null };
  let result;
  bridge.window.addEventListener('dsh:bridge-result', (event) => { result = JSON.parse(event.detail); });
  bridge.receive({ type: 'bridgeResult', response: JSON.stringify(response) });
  assert.deepEqual(result, response);
  assert.equal(bridge.window.DSHAndroid.aiAnnotateAck('id'), undefined);
  assert.deepEqual(bridge.sent.at(-1), { __dsh: true, type: 'invoke', call: 'aiAnnotateAck', args: ['id'] });
});
test('非消费来源、缓存重试、身份 ack 和销毁释放的实际 Kotlin 路径', () => {
  assert.match(annotations, /pending\[requestId\]\?\.let \{ return it.response \}/);
  assert.match(annotations, /val raw = reader.peekAnnotation\(\)/);
  assert.ok(!annotations.includes('aiAnnotateResult('));
  assert.match(annotations, /if \(raw.isEmpty\(\)\) return BridgeProtocol.failure/);
  assert.match(annotations, /pending\[requestId\] = Pending\(annotationId, result\)/);
  assert.match(annotations, /val result = pending\[requestId\] \?: return[\s\S]*source\?\.acknowledgeAnnotation\(result.annotationId\)/);
  assert.match(annotations, /pending.entries.removeAll \{ it.value.annotationId == result.annotationId \}/);
  assert.match(annotations, /fun clear\(\) \{ pending.clear\(\) \}/);
  for (const host of [system, gecko]) {
    assert.match(host, /BridgeProtocolAnnotations\(callbacks.bridge as\? AnnotationSource\)/);
    assert.match(host, /override fun destroy\(\) \{\s*if \(destroyed\) return\s*destroyed = true\s*uiHandler.removeCallbacksAndMessages\(null\)\s*annotations.clear\(\)/);
  }
  const native = read('app/src/main/java/com/dsh/android/ui/MainActivity.kt');
  const dispatcher = read('app/src/main/java/com/dsh/android/ui/NativePageBridgeDispatcher.kt');
  const coordinator = read('app/src/main/java/com/dsh/android/ui/BrowserPaneCoordinator.kt');
  const peek = native.slice(native.indexOf('override fun peekAnnotation()'), native.indexOf('override fun aiAnnotateResult()'));
  assert.match(native, /bridge = NativePageBridgeDispatcher\([\s\S]*activity = ActivityPageBridge\(\)/);
  assert.match(dispatcher, /:\s*PageBridge,\s*AnnotationSource/);
  assert.match(dispatcher, /override fun peekAnnotation\(\): String = activity.peekAnnotation\(\)/);
  assert.match(dispatcher, /override fun acknowledgeAnnotation\(annotationId: String\) = activity.acknowledgeAnnotation\(annotationId\)/);
  assert.match(peek, /browserPane.readAnnotation\(consume = false\)/);
  assert.match(peek, /put\("annotationId", pending.id\)/);
  assert.match(peek, /browserPane.acknowledgeAnnotation\(annotationId\)/);
  assert.ok(!peek.includes('consume = true'));
  assert.match(coordinator, /pending = Annotation\("\$identity-\$\{\+\+annotationSequence\}"/);
  assert.match(coordinator, /if \(consume\) pending = null/);
  assert.match(coordinator, /!consume && pending\?\.id != item.id/);
  assert.match(coordinator, /if \(!closed && pending\?\.id == id\) pending = null/);
  assert.match(coordinator, /fun close\(\) \{\s*closed = true\s*canvasGeneration\+\+\s*pending = null/);
  assert.match(native, /browserPane.close\(\)/);
});

function createClient(options = {}) {
  const window = eventWindow();
  const acks = [], reports = [], sends = [];
  let currentRequest;
  const payload = options.payload || { png: 'cG5n', text: 'question' };
  const bridge = {
    bridgeCapabilities: () => JSON.stringify({ protocolVersion: 1, capabilities: { 'protocol.capabilities': 1, 'annotation.request': 1 } }),
    aiAnnotateRequest(requestId) {
      currentRequest = requestId;
      if (options.noResponse) return;
      // 错 requestId 必须被忽略，然后才送真正响应。
      for (const id of ['wrong-request', requestId]) window.dispatchEvent({ type: 'dsh:bridge-result', detail: JSON.stringify({
        protocolVersion: 1, requestId: id, method: 'aiAnnotateRequest', ok: !options.responseError,
        result: options.responseError ? null : payload, error: options.responseError ? { code: 'read_failed', message: 'read failed' } : null,
      }) });
    },
    aiAnnotateAck: (id) => acks.push(id), aiAnnotateReport: (message) => reports.push(message),
    aiAnnotateResult: () => { throw new Error('new protocol must not consume legacy getter'); },
  };
  if (options.legacy) {
    delete bridge.bridgeCapabilities;
    let readCount = 0;
    bridge.aiAnnotateResult = () => ++readCount === 1 ? '' : JSON.stringify(payload);
  }
  window.DSHAndroid = bridge;
  const session = {};
  const scope = {
    get: (name) => name === 'conversation' ? { createDrafts: () => [{ id: 'draft' }],
      sendSession: async (...args) => { sends.push(args); return options.sendError ? { kind: 'error' } : { kind: 'ok' }; } }
      : { binding: () => ({ session }) },
    sidebarRight: { mounted: { getSnapshot: () => 'session-id' } },
  };
  const context = vm.createContext({ window, console: { log() {}, warn() {} },
    setTimeout: (fn, delay) => setTimeout(fn, options.fastTimeout ? 0 : delay), clearTimeout,
    atob: (s) => { if (options.decodeError) throw Error('decode failed'); return Buffer.from(s, 'base64').toString('binary'); },
    Uint8Array, File: class { constructor(bytes, name) { this.bytes = bytes; this.name = name; } } });
  const start = client.indexOf('\t\tfunction report(message)');
  const end = client.indexOf('\n\t\tfunction currentTabId(scope)', start);
  assert.ok(start >= 0 && end > start);
  vm.runInContext(client.slice(start, end), context, { filename: 'client-annotation.js' });
  return { acks, reports, sends, window, session, bridge, request: () => currentRequest,
    send: () => context.sendAnnotate(scope), read: () => context.readAnnotation(bridge) };
}
await testAsync('前端错 requestId 被忽略，完整发送成功后显式 ack 并释放监听', async () => {
  const client = createClient(); await client.send();
  assert.deepEqual(client.acks, [client.request()]);
  assert.equal(client.sends.length, 1); assert.equal(client.sends[0][0], client.session);
  assert.equal(client.reports.at(-1), '已发送标注');
  assert.equal(client.window.listenerCount('dsh:bridge-result'), 0);
});
await testAsync('前端发送失败、原生读失败与图片解码失败都不 ack', async () => {
  for (const options of [{ sendError: true }, { responseError: true }, { decodeError: true }]) {
    const client = createClient(options); await client.send(); assert.deepEqual(client.acks, []);
    assert.equal(client.window.listenerCount('dsh:bridge-result'), 0);
    assert.ok(client.reports.length);
  }
});
await testAsync('响应超时释放监听并保留原生标注', async () => {
  const client = createClient({ noResponse: true, fastTimeout: true }); await client.send();
  assert.deepEqual(client.acks, []);
  assert.equal(client.window.listenerCount('dsh:bridge-result'), 0);
  assert.ok(client.reports.at(-1).includes('等待超时'));
});
await testAsync('旧桥保留消费读取，兼容 Gecko 异步缓存首轮空串', async () => {
  const client = createClient({ legacy: true }); await client.send();
  assert.equal(client.sends.length, 1); assert.deepEqual(client.acks, []);
  assert.equal(client.reports.at(-1), '已发送标注');
});
await testAsync('前端并发 ready 通知只发一个请求与一次发送', async () => {
  const client = createClient(); await Promise.all([client.send(), client.send()]);
  assert.equal(client.sends.length, 1); assert.equal(client.acks.length, 1);
});
test('JSON 状态返回字段逐项对应实际构造器，标注结构不冒充浏览器状态', () => {
  const owners = {
    bubbleState: 'BubblePageBridge.kt', bubbleBadgeConfig: 'BubblePageBridge.kt',
    rootState: 'PrivilegePageBridge.kt', shizukuState: 'PrivilegePageBridge.kt', logSummary: 'MainActivity.kt',
  };
  for (const [name, owner] of Object.entries(owners)) {
    const native = read(`app/src/main/java/com/dsh/android/ui/${owner}`);
    const at = native.indexOf(`override fun ${name}(`);
    assert.ok(at >= 0, `${owner} implements ${name}`);
    const next = /\n {4,8}override fun /.exec(native.slice(at + 1));
    const body = native.slice(at, next ? at + 1 + next.index : undefined);
    const keys = [...body.matchAll(/(?:\.put|\bput)\("([^"]+)"/g)].map((m) => m[1]);
    assert.deepEqual([...new Set(keys)].sort(), Object.keys(protocol.schemas[name].properties).sort(), name);
  }
  const avoid = read('feature/bubble/src/main/java/com/dsh/android/ui/KeyboardAvoid.kt');
  const avoidBody = avoid.slice(avoid.indexOf('fun stateJson('));
  assert.deepEqual([...avoidBody.matchAll(/\.put\("([^"]+)"/g)].map((m) => m[1]).sort(), Object.keys(protocol.schemas.keyboardAvoidState.properties).sort());
  const appearance = read('feature/bubble/src/main/java/com/dsh/android/bubble/FloatingBubble.kt');
  const at = appearance.indexOf('fun currentAppearanceJson(');
  const body = appearance.slice(at, appearance.indexOf('\n        }', at));
  for (const key of Object.keys(protocol.schemas.bubbleAppearance.properties)) {
    assert.ok([...body.matchAll(/put\(\s*"([^"]+)"/g)].some((m) => m[1] === key), `appearance field ${key}`);
  }
  assert.deepEqual(protocol.schemas.annotation.properties.png, { type: 'string', minLength: 1, encoding: 'base64-png' });
});
test('能力声明仅公布实际 AnnotationSource，协议列出真实响应结构和消费约束', () => {
  const native = read('web/contract/src/main/java/com/dsh/android/web/BridgeProtocol.kt');
  assert.match(native, /if \(annotationSupported\) capabilities.put\("annotation.request", 1\)/);
  assert.match(annotations, /BridgeProtocol.capabilities\(source != null\)/);
  assert.deepEqual(protocol.schemas.annotation.required, ['png', 'text']);
  assert.deepEqual(protocol.schemas.annotationResponse.required, ['protocolVersion', 'requestId', 'method', 'ok', 'result', 'error']);
  assert.equal(protocol.methods.aiAnnotateRequest.system.returns.type, 'undefined');
  assert.equal(protocol.methods.aiAnnotateAck.gecko.mode, 'consume-ack');
  assert.match(protocol.capabilities['annotation.request'].consumption, /Only aiAnnotateAck/);
  const manifest = JSON.parse(read('web/host/src/gecko/assets/dsh-bridge/manifest.json'));
  assert.equal(manifest.version, '1.0.16');
});
console.log(`bridge contract: ${checks} tests passed; ${signatures.length} methods checked; 0 skipped`);
