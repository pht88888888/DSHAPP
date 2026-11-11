import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, delimiter, dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { after, before, test } from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../../tools/kotlin-test-runtime.mjs';

// Execute production Kotlin entry points against narrow platform substitutes.
// The extracted bodies are not rewritten into a second lifecycle implementation.
const root = resolveRepositoryRoot(import.meta.url);
const source = flavor => readFileSync(join(root, `web/host/src/${flavor}/java/com/dsh/android/web/${flavor === 'system' ? 'System' : 'Gecko'}WebHost.kt`), 'utf8');
const system = source('system');
const gecko = source('gecko');
function body(text, marker) {
  const start = text.indexOf(marker);
  assert.ok(start >= 0, `Missing production declaration: ${marker}`);
  let depth = 0, quote = false, line = false, block = false;
  const open = text.indexOf('{', start);
  for (let i = open; i < text.length; i++) {
    const c = text[i], next = text[i + 1];
    if (line) { if (c === '\n') line = false; continue; }
    if (block) { if (c === '*' && next === '/') { block = false; i++; } continue; }
    if (quote) { if (c === '\\') i++; else if (c === '"') quote = false; continue; }
    if (c === '/' && next === '/') { line = true; i++; continue; }
    if (c === '/' && next === '*') { block = true; i++; continue; }
    if (c === '"') { quote = true; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return text.slice(start, i + 1);
  }
  throw new Error(`Unclosed production declaration: ${marker}`);
}
const methods = (text, names) => names.map(name => {
  const match = text.match(new RegExp(`(?:private|override) fun ${name}\\(`));
  assert.ok(match, name);
  return body(text, match[0]).replace(/^(private|override) fun /, 'fun ');
}).join('\n');
function fields(text, names) {
  return names.map(name => {
    const match = text.match(new RegExp(`^    (?:@Volatile )?private (?:val|var) ${name}[^\\n]+`, 'm'));
    assert.ok(match, `Missing production field: ${name}`);
    return match[0].replace('private ', '');
  }).join('\n');
}
const wiringStart = system.indexOf('webView.addJavascriptInterface(');
const wiringEnd = system.indexOf('}, "DSHAndroid")', wiringStart) + '}, "DSHAndroid")'.length;
assert.ok(wiringStart >= 0 && wiringEnd > wiringStart);
const commonFields = ['annotations', 'destroyed', 'uiHandler', 'lastLoadedUrl', 'painted', 'pendingPaintAction'];
const fixture = `package com.dsh.android.web
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.net.Uri
import java.lang.reflect.Proxy

interface AnnotationSource
class BridgeProtocolAnnotations(source: AnnotationSource?) {
 var requests = 0; var acknowledgements = 0; var clears = 0
 val pending = mutableMapOf<String, String>()
 fun capabilities() = "supported"
 fun request(id: String): String { requests++; pending[id] = id; return "response-$id" }
 fun acknowledge(id: String) { acknowledgements++; pending.remove(id) }
 fun clear() { clears++; pending.clear() }
}
object BridgeProtocol {
 fun capabilities(supported: Boolean) = if (supported) "supported" else "unsupported"
 fun unsupported(id: String) = "unsupported-$id"
 fun resultScript(response: String) = "event:$response"
}
object Log {
 const val TAG = "test"
 fun i(tag: String, message: String) {}
 fun w(tag: String, message: String, error: Throwable? = null) {}
 fun e(tag: String, message: String, error: Throwable? = null) {}
}
class Recorder {
 val calls = mutableListOf<String>()
 val bridge = Proxy.newProxyInstance(PageBridge::class.java.classLoader, arrayOf(PageBridge::class.java, AnnotationSource::class.java)) { _, method, _ ->
  calls.add(method.name)
  when (method.returnType) {
   java.lang.Boolean.TYPE -> true
   java.lang.Integer.TYPE -> 73
   String::class.java -> "live-" + method.name
   else -> null
  }
 } as PageBridge
}
class WebHostCallbacks(val bridge: PageBridge) { val onConsole: (String) -> Unit = {} }
class JSONObject {
 private val data = mutableMapOf<String, Any?>()
 fun put(key: String, value: Any?): JSONObject { data[key] = value; return this }
 fun optString(key: String, fallback: String = "") = data[key]?.toString() ?: fallback
 fun optLong(key: String, fallback: Long) = (data[key] as? Number)?.toLong() ?: fallback
 fun optJSONArray(key: String) = data[key] as? JSONArray
 fun isNull(key: String) = data[key] == null
}
class JSONArray {
 private val data = mutableListOf<Any?>()
 fun put(value: Any?): JSONArray { data.add(value); return this }
 fun optString(index: Int, fallback: String = "") = data.getOrNull(index)?.toString() ?: fallback
 fun optBoolean(index: Int) = data.getOrNull(index) == true
}
class WebExtension {
 class MetaData { val version = "test" }
 val metaData: MetaData? = MetaData()
 interface MessageDelegate { fun onConnect(connectedPort: Port) }
 interface PortDelegate { fun onPortMessage(message: Any, p: Port); fun onDisconnect(p: Port) }
 class Port {
  var currentDelegate: PortDelegate? = null
  var disconnects = 0
  var onSend: ((JSONObject) -> Unit)? = null
  val messages = mutableListOf<JSONObject>()
  fun setDelegate(delegate: PortDelegate?) { currentDelegate = delegate }
  fun postMessage(message: JSONObject) { check(disconnects == 0); messages.add(message); onSend?.invoke(message) }
  fun disconnect() { disconnects++; currentDelegate?.onDisconnect(this) }
 }
}
class InstallResult {
 lateinit var success: (WebExtension?) -> Unit
 lateinit var failure: (Throwable) -> Unit
 fun accept(success: (WebExtension?) -> Unit, failure: (Throwable) -> Unit) { this.success = success; this.failure = failure }
}
class RuntimeController {
 val install = InstallResult()
 fun ensureBuiltIn(location: String, id: String) = install
}
class Runtime { val webExtensionController = RuntimeController() }
class SessionController {
 var installs = 0; var removals = 0
 fun setMessageDelegate(extension: WebExtension, delegate: WebExtension.MessageDelegate?, app: String) {
  if (delegate == null) removals++ else installs++
 }
}
class GeckoSession {
 val webExtensionController = SessionController()
 var progressDelegate: Any? = Any(); var contentDelegate: Any? = Any()
 var permissionDelegate: Any? = Any(); var navigationDelegate: Any? = Any(); var promptDelegate: Any? = Any()
 val urls = mutableListOf<String>()
 var closes = 0
 fun loadUri(url: String) { check(closes == 0) { "load on closed session" }; urls.add(url) }
 fun close() { closes++ }
}
class GeckoView(context: Any) { var releases = 0; fun releaseSession() { releases++ } }
class IncomingDownload(url: String, filename: String) { var closes = 0; fun close() { closes++ } }

${system.slice(system.indexOf('private class SystemPageBridge('))}
private class SystemFixture(val callbacks: WebHostCallbacks) {
 val context = Any()
 ${fields(system, [...commonFields, 'webView', 'filePathCallbackRef', 'paintPending', 'paintRequestId', 'staleRequestId'])}
 init { ${system.slice(wiringStart, wiringEnd)} }
 ${methods(system, ['postToUi', 'destroy', 'loadUrl', 'evaluateJavascript', 'awaitFirstFrame', 'resetFirstFrame', 'reload'])}
}
private class GeckoFixture(val callbacks: WebHostCallbacks) {
 val context = Any()
 val runtime = Runtime()
 ${fields(gecko, [...commonFields, 'geckoView', 'session', 'port', 'bridgeExtension', 'bridgeGateOpen', 'gatedUrl', 'pendingEvals', 'nextEvalId', 'pendingUntilReady'])}
 var incomingDownload: IncomingDownload? = null
 val NATIVE_APP = "test"; val EXTENSION_LOCATION = "test"; val EXTENSION_ID = "test"
 val MAX_PENDING_UNTIL_READY = 256; val TOKEN_FOLLOW_JS = "test"; val TOKEN_FOLLOW_DELAY_MS = 2500L
 fun appendDownloadChunk(chunk: String) { error("Not a lifecycle test input") }
 fun finishDownload() { error("Not a lifecycle test input") }
 fun urlFilename(url: String) = url
 ${body(gecko, 'private val messageDelegate =').replace('private val ', 'val ')}
 ${methods(gecko, ['postToUi', 'destroy', 'installBridgeExtension', 'flushPendingUntilReady', 'handleExtensionMessage', 'dispatchInvoke', 'pushSnapshot', 'postToExtension', 'openBridgeGate', 'loadUrl', 'armTokenRedirectFollower', 'reload', 'evaluateJavascript', 'awaitFirstFrame', 'resetFirstFrame'])}
}
private fun invokeAll(bridge: Any): Int {
 val methods = bridge.javaClass.declaredMethods.filter { it.getAnnotation(android.webkit.JavascriptInterface::class.java) != null }
 check(methods.size == 47)
 methods.forEach { method ->
  val args = method.parameterTypes.map { if (it == java.lang.Boolean.TYPE) true else "r" }.toTypedArray()
  method.invoke(bridge, *args)
 }
 return methods.size
}
private fun message(call: String) = JSONObject().put("type", "invoke").put("call", call).put("args", JSONArray().put(true))
fun main(args: Array<String>) {
 when (args.single()) {
  "system-surface" -> {
   val recorder = Recorder(); val h = SystemFixture(WebHostCallbacks(recorder.bridge))
   val bridge = h.webView.bridge!!
   check(invokeAll(bridge) == 47); h.uiHandler.drain()
   check(recorder.calls.size == 44); check(h.annotations.requests == 1); check(h.annotations.acknowledgements == 1)
   check((bridge as SystemPageBridge).rootState() == "live-rootState")
   val before = recorder.calls.size
   h.destroy(); invokeAll(bridge); h.uiHandler.drain()
   check(recorder.calls.size == before); check(h.annotations.requests == 1); check(h.annotations.acknowledgements == 1)
   check(bridge.rootState() == "{}"); check(bridge.bridgeCapabilities() == "unsupported")
  }
  "system-dequeued" -> {
   val h = SystemFixture(WebHostCallbacks(Recorder().bridge)); val b = h.webView.bridge as SystemPageBridge
   b.aiAnnotateRequest("r"); b.aiAnnotateAck("r")
   val alreadyDequeued = h.uiHandler.take(); check(h.uiHandler.size() == 1)
   h.destroy(); alreadyDequeued.run(); h.uiHandler.drain()
   b.aiAnnotateRequest("late"); b.aiAnnotateAck("r")
   h.evaluateJavascript("late", null); h.loadUrl("late"); h.reload()
   check(h.uiHandler.size() == 0); check(h.webView.evals.isEmpty()); check(h.webView.urls.isEmpty())
   check(h.annotations.pending.isEmpty()); check(h.annotations.requests == 1); check(h.annotations.acknowledgements == 0)
  }
  "system-callbacks" -> {
   val h = SystemFixture(WebHostCallbacks(Recorder().bridge)); var replies = 0; var frames = 0; var cancels = 0
   h.evaluateJavascript("live", { replies++ }); val reply = h.webView.lastCallback!!
   h.awaitFirstFrame { frames++ }; val frame = h.webView.frame!!
   h.filePathCallbackRef = ValueCallback { check(it == null); cancels++ }
   h.destroy(); h.destroy(); reply("late"); frame.second.onComplete(frame.first)
   h.awaitFirstFrame { frames++ }; h.resetFirstFrame()
   check(replies == 0 && frames == 0 && cancels == 1)
   check(h.webView.destroys == 1 && h.webView.removals == 1 && h.annotations.clears == 1)
  }
  "gecko-dequeued" -> {
   val recorder = Recorder(); val h = GeckoFixture(WebHostCallbacks(recorder.bridge))
   h.dispatchInvoke("bubbleSetEnabled", JSONArray().put(true)); val alreadyDequeued = h.uiHandler.take()
   h.destroy(); alreadyDequeued.run(); h.dispatchInvoke("bubbleSetEnabled", JSONArray().put(true)); h.uiHandler.drain()
   check(recorder.calls.isEmpty()); check(h.uiHandler.size() == 0 && h.pendingUntilReady.isEmpty())
  }
  "gecko-install-success", "gecko-install-failure" -> {
   val h = GeckoFixture(WebHostCallbacks(Recorder().bridge))
   h.installBridgeExtension(); h.loadUrl("http://127.0.0.1/?token=test"); check(h.gatedUrl != null)
   h.destroy()
   if (args.single().endsWith("success")) h.runtime.webExtensionController.install.success(WebExtension())
   else h.runtime.webExtensionController.install.failure(IllegalStateException("late install"))
   h.uiHandler.drain()
   check(h.gatedUrl == null && h.session.urls.isEmpty()); check(h.session.webExtensionController.installs == 0)
   check(h.bridgeExtension == null && h.pendingUntilReady.isEmpty())
  }
  "gecko-cleanup" -> {
   val h = GeckoFixture(WebHostCallbacks(Recorder().bridge)); val port = WebExtension.Port()
   h.installBridgeExtension(); h.runtime.webExtensionController.install.success(WebExtension())
   h.messageDelegate.onConnect(port)
   h.evaluateJavascript("pending", {}); h.incomingDownload = IncomingDownload("url", "name"); val download = h.incomingDownload!!
   h.destroy(); h.destroy()
   check(h.session.closes == 1 && h.geckoView.releases == 1 && h.annotations.clears == 1)
   check(h.session.webExtensionController.installs == 1 && h.session.webExtensionController.removals == 1)
   check(port.disconnects == 1 && port.currentDelegate == null && h.port == null && h.bridgeExtension == null)
   check(h.pendingEvals.isEmpty() && h.pendingUntilReady.isEmpty() && h.incomingDownload == null && download.closes == 1)
  }
  "gecko-late-messages" -> {
   val recorder = Recorder(); val h = GeckoFixture(WebHostCallbacks(recorder.bridge)); val port = WebExtension.Port()
   h.messageDelegate.onConnect(port); val delegate = port.currentDelegate!!; val before = recorder.calls.size
   h.destroy()
   for (msg in listOf(message("bubbleSetEnabled"), message("aiAnnotateRequest"), message("aiAnnotateAck"), JSONObject().put("type", "hello"), JSONObject().put("type", "evalResult").put("id", 1))) {
    delegate.onPortMessage(msg, port); h.handleExtensionMessage(msg)
   }
   delegate.onDisconnect(port)
   val latePort = WebExtension.Port(); h.messageDelegate.onConnect(latePort); h.uiHandler.drain()
   check(latePort.disconnects == 1 && latePort.currentDelegate == null)
   check(recorder.calls.size == before && h.port == null && h.pendingUntilReady.isEmpty() && h.uiHandler.size() == 0)
   check(h.annotations.requests == 0 && h.annotations.acknowledgements == 0)
  }
  "gecko-replaced-port" -> {
   val recorder = Recorder(); val h = GeckoFixture(WebHostCallbacks(recorder.bridge))
   val old = WebExtension.Port(); h.messageDelegate.onConnect(old); val stale = old.currentDelegate!!
   val current = WebExtension.Port(); h.messageDelegate.onConnect(current); val before = recorder.calls.size
   stale.onPortMessage(message("bubbleSetEnabled"), old); stale.onDisconnect(old); h.uiHandler.drain()
   check(recorder.calls.size == before && h.port === current)
   current.currentDelegate!!.onPortMessage(message("bubbleSetEnabled"), current); h.uiHandler.drain()
   check(recorder.calls.last() == "bubbleSetEnabled" && recorder.calls.size == before + 1)
   h.destroy()
  }
  "gecko-drained" -> {
   val h = GeckoFixture(WebHostCallbacks(Recorder().bridge))
   h.postToExtension(JSONObject().put("type", "first")); h.postToExtension(JSONObject().put("type", "second"))
   val port = WebExtension.Port(); port.onSend = { if (it.optString("type") == "first") h.destroy() }
   h.messageDelegate.onConnect(port)
   check(port.messages.map { it.optString("type") } == listOf("snapshot", "first"))
   check(h.pendingUntilReady.isEmpty()); h.flushPendingUntilReady(); h.postToExtension(JSONObject().put("type", "late"))
   check(h.pendingUntilReady.isEmpty() && port.messages.size == 2)
  }
  "gecko-delayed-public" -> {
   val h = GeckoFixture(WebHostCallbacks(Recorder().bridge))
   h.loadUrl("http://127.0.0.1/?token=test"); h.armTokenRedirectFollower(); val delayed = h.uiHandler.take()
   h.destroy(); delayed.run(); h.loadUrl("late"); h.reload(); h.evaluateJavascript("late", { error("late eval callback") })
   h.awaitFirstFrame { error("late frame") }; h.resetFirstFrame(); h.openBridgeGate(); h.pushSnapshot(); h.installBridgeExtension()
   check(h.session.urls.isEmpty() && h.pendingEvals.isEmpty() && h.pendingUntilReady.isEmpty() && h.uiHandler.size() == 0)
  }
  "gecko-live" -> {
   val h = GeckoFixture(WebHostCallbacks(Recorder().bridge))
   h.installBridgeExtension(); h.loadUrl("live"); h.runtime.webExtensionController.install.success(WebExtension())
   check(h.session.urls == listOf("live"))
   h.dispatchInvoke("aiAnnotateRequest", JSONArray().put("r")); h.uiHandler.drain()
   check(h.annotations.requests == 1 && h.pendingUntilReady.size == 1)
   val port = WebExtension.Port(); h.messageDelegate.onConnect(port)
   check(port.messages.map { it.optString("type") } == listOf("snapshot", "bridgeResult"))
   h.dispatchInvoke("aiAnnotateAck", JSONArray().put("r")); h.uiHandler.drain(); check(h.annotations.acknowledgements == 1)
   var result: String? = null; h.evaluateJavascript("script", { result = it })
   port.currentDelegate!!.onPortMessage(JSONObject().put("type", "evalResult").put("id", 1L).put("result", "true"), port)
   h.uiHandler.drain(); check(result == "true")
   h.destroy()
   val fallback = GeckoFixture(WebHostCallbacks(Recorder().bridge)); fallback.installBridgeExtension(); fallback.loadUrl("fallback")
   fallback.runtime.webExtensionController.install.failure(IllegalStateException("install failed")); check(fallback.session.urls == listOf("fallback")); fallback.destroy()
  }
  "host-isolation" -> {
   val a = GeckoFixture(WebHostCallbacks(Recorder().bridge)); val recorder = Recorder(); val b = GeckoFixture(WebHostCallbacks(recorder.bridge))
   a.dispatchInvoke("bubbleSetEnabled", JSONArray().put(true)); b.dispatchInvoke("bubbleSetEnabled", JSONArray().put(true))
   a.destroy(); b.uiHandler.drain(); check(recorder.calls == listOf("bubbleSetEnabled")); b.destroy()
   val c = SystemFixture(WebHostCallbacks(Recorder().bridge)); val d = SystemFixture(WebHostCallbacks(Recorder().bridge))
   (d.webView.bridge as SystemPageBridge).aiAnnotateRequest("r"); c.destroy(); d.uiHandler.drain()
   check(d.webView.evals.size == 1 && d.annotations.requests == 1); d.destroy()
  }
  else -> error("Unknown scenario")
 }
 println("PASS " + args.single())
}
`;
const platform = {
  'Handler.kt': `package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
 private val pending = ArrayDeque<Runnable>()
 fun post(action: Runnable): Boolean { pending.addLast(action); return true }
 fun postDelayed(action: Runnable, delay: Long) = post(action)
 fun removeCallbacksAndMessages(token: Any?) { pending.clear() }
 fun take() = pending.removeFirst()
 fun size() = pending.size
 fun drain() { var count = 0; while (pending.isNotEmpty()) { check(count++ < 100); take().run() } }
}`,
  'WebView.kt': `package android.webkit
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class JavascriptInterface
fun interface ValueCallback<T> { fun onReceiveValue(value: T?) }
class WebViewClient
class WebView(context: Any) {
 var bridge: Any? = null; var webChromeClient: Any? = null; var webViewClient = WebViewClient()
 var destroys = 0; var removals = 0
 val evals = mutableListOf<String>(); val urls = mutableListOf<String>()
 var lastCallback: ((String?) -> Unit)? = null
 open class VisualStateCallback { open fun onComplete(requestId: Long) {} }
 var frame: Pair<Long, VisualStateCallback>? = null
 fun addJavascriptInterface(bridge: Any, name: String) { this.bridge = bridge }
 fun removeJavascriptInterface(name: String) { removals++; bridge = null }
 fun setDownloadListener(listener: Any?) {}
 fun destroy() { destroys++ }
 fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) { check(destroys == 0); evals.add(script); lastCallback = callback }
 fun loadUrl(url: String) { check(destroys == 0); urls.add(url) }
 fun postVisualStateCallback(id: Long, callback: VisualStateCallback) { check(destroys == 0); frame = id to callback }
}`,
  'Uri.kt': 'package android.net\nclass Uri',
};
let work, runtime;
before(() => {
  runtime = resolveKotlinTestRuntime();
  work = mkdtempSync(join(realpathSync(tmpdir()), 'dsh-webhost-lifecycle-'));
  const files = [];
  for (const [name, content] of Object.entries({ ...platform, 'Lifecycle.kt': fixture })) {
    const filename = join(work, name); writeFileSync(filename, content); files.push(filename);
  }
  const result = spawnSync(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', runtime.stdlib, '-d', join(work, 'tests.jar'),
    join(root, 'web/contract/src/main/java/com/dsh/android/web/PageBridge.kt'), ...files], { encoding: 'utf8', timeout: 60_000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
});
after(() => {
  if (!work) return;
  assert.equal(dirname(work), realpathSync(tmpdir()));
  assert.ok(basename(work).startsWith('dsh-webhost-lifecycle-'));
  rmSync(work, { recursive: true, force: true });
});
const scenarios = [
  ['system-surface', 'System 47 个入口存活时正常委派，关闭后保持类型并拒绝副作用'],
  ['system-dequeued', 'System 销毁前排队及已出队的标注 ack/result 均失效'],
  ['system-callbacks', 'System 在飞 eval/首帧不回调，选择器取消且双重销毁仅释放一次'],
  ['gecko-dequeued', 'Gecko 销毁前排队及已出队 invoke 不修改领域状态'],
  ['gecko-install-success', 'Gecko 销毁后安装成功不再绑定扩展或加载 gated URL'],
  ['gecko-install-failure', 'Gecko 销毁后安装失败不再放行 gated URL'],
  ['gecko-cleanup', 'Gecko 双重销毁只解绑/断开/关闭一次并清空持有状态'],
  ['gecko-late-messages', 'Gecko 销毁后迟到消息与连接均不能复活宿主'],
  ['gecko-replaced-port', 'Gecko 已替换端口的消息和断连不能作用于当前端口'],
  ['gecko-drained', 'Gecko 待发队列已经取出后遇销毁也停止继续发送'],
  ['gecko-delayed-public', 'Gecko 销毁后迟到 token 定时器及公开入口不触碰关闭 session'],
  ['gecko-live', 'Gecko 正常安装/失败放行、标注响应与 ack、eval 返回保持可用'],
  ['host-isolation', '销毁一个 Host 不清除其他 Host 的任务与标注缓存'],
];
for (const [scenario, name] of scenarios) test(name, () => {
  const result = spawnSync(runtime.java, ['-cp', [join(work, 'tests.jar'), runtime.stdlib].join(delimiter), 'com.dsh.android.web.LifecycleKt', scenario], { encoding: 'utf8', timeout: 10_000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  assert.equal(result.stdout.trim(), `PASS ${scenario}`);
});
