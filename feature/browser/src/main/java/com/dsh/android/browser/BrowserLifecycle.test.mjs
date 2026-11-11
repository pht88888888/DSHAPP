import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { after, before, test } from 'node:test';

// 编译并执行生产 Kotlin；平台替身仅驱动 UI 队列、WebView 和 PixelCopy 生命周期。
// 编译器使用仓库公共工具定位；显式输入优先，不下载工具，缺工具不跳过用例。
const source = dirname(fileURLToPath(import.meta.url));
let work;
let runtime;
async function loadRuntime() {
  let root = source;
  while (!existsSync(join(root, 'settings.gradle.kts')) || !existsSync(join(root, 'tools/kotlin-test-runtime.mjs'))) {
    const parent = dirname(root);
    assert.notEqual(parent, root, '找不到仓库测试工具');
    root = parent;
  }
  const helper = await import(pathToFileURL(join(root, 'tools/kotlin-test-runtime.mjs')).href);
  assert.equal(helper.resolveRepositoryRoot(import.meta.url), root);
  return helper.resolveKotlinTestRuntime();
}
const fixtures = {
  'Context.kt': `package android.content
open class Context`,
  'Activity.kt': `package android.app
class Activity : android.content.Context() { val window = Any() }`,
  'Looper.kt': `package android.os
class Looper { companion object {
 private val main = Looper()
 fun myLooper() = main
 fun getMainLooper() = main
} }`,
  'Clock.kt': `package android.os
object SystemClock {
 var time = 0L
 fun elapsedRealtime() = time
 fun uptimeMillis() = time
}`,
  'Handler.kt': `package android.os
class Handler(looper: Looper) {
 data class Entry(val runnable: Runnable, val token: Any?, val due: Long)
 companion object {
  private val pending = ArrayList<Entry>()
  fun runNext() {
   val e = pending.minByOrNull { it.due } ?: error("队列为空")
   pending.remove(e); SystemClock.time = e.due; e.runnable.run()
  }
  fun count() = pending.size
 }
 fun postDelayed(r: Runnable, ms: Long): Boolean { pending.add(Entry(r, null, SystemClock.time + ms)); return true }
 fun postAtTime(r: Runnable, token: Any, due: Long): Boolean { pending.add(Entry(r, token, due)); return true }
 fun removeCallbacks(r: Runnable) { pending.removeAll { it.runnable === r } }
 fun removeCallbacksAndMessages(token: Any?) { pending.removeAll { token == null || it.token === token } }
}`,
  'Log.kt': `package android.util
object Log { fun w(tag: String, message: String) = 0; fun i(tag: String, message: String) = 0 }`,
  'Uri.kt': `package android.net
class Uri`,
  'View.kt': `package android.view
import android.os.Handler
import android.os.Looper
open class View(val context: android.content.Context) {
 companion object { const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8 }
 var visibility = VISIBLE
 var width = 24
 var height = 48
 var parent: Any? = null
 var windowToken: Any? = Any()
 private val handler = Handler(Looper.getMainLooper())
 fun getLocationInWindow(out: IntArray) { out[0] = 0; out[1] = 0 }
 fun postOnAnimation(r: Runnable) { handler.postDelayed(r, 0) }
 fun removeCallbacks(r: Runnable) { handler.removeCallbacks(r) }
}
class ViewGroup(context: android.content.Context) : View(context) {
 fun removeView(view: View) { view.parent = null }
}`,
  'WebView.kt': `package android.webkit
fun interface ValueCallback<T> { fun onReceiveValue(value: T?) }
class WebView(context: android.content.Context) : android.view.View(context) {
 var stopFailure = false
 var destroyed = false
 var javascriptCallback: ((String?) -> Unit)? = null
 fun evaluateJavascript(script: String, callback: (String?) -> Unit) { javascriptCallback = callback }
 fun stopLoading() { if (stopFailure) error("stop failure") }
 fun destroy() { destroyed = true }
 fun draw(canvas: android.graphics.Canvas) {}
}`,
  'Graphics.kt': `package android.graphics
class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
class Canvas(bitmap: Bitmap)
class Bitmap(val width: Int, val height: Int) {
 enum class Config { ARGB_8888 }
 enum class CompressFormat { PNG }
 companion object {
  val allocated = ArrayList<Bitmap>()
  var compressResult = true
  fun createBitmap(w: Int, h: Int, config: Config) = Bitmap(w, h).also { allocated.add(it) }
 }
 var isRecycled = false
 fun recycle() { check(!isRecycled) { "重复回收" }; isRecycled = true }
 fun getPixel(x: Int, y: Int): Int { check(!isRecycled); return 0 }
 fun compress(format: CompressFormat, quality: Int, output: java.io.OutputStream) = compressResult
}`,
  'PixelCopy.kt': `package android.view
object PixelCopy {
 const val SUCCESS = 0
 data class Request(val bitmap: android.graphics.Bitmap, val callback: (Int) -> Unit)
 val requests = ArrayDeque<Request>()
 fun request(window: Any, rect: android.graphics.Rect, bitmap: android.graphics.Bitmap,
  callback: (Int) -> Unit, handler: android.os.Handler) { requests.addLast(Request(bitmap, callback)) }
 fun complete() {
  val request = requests.removeFirst()
  check(!request.bitmap.isRecycled) { "系统仍持有的 Bitmap 被提前回收" }
  request.callback(SUCCESS)
 }
}`,
  'BrowserLifecycleCases.kt': `package com.dsh.android.browser
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import java.io.File

fun main(args: Array<String>) {
 val context = Activity()
 fun store() = BrowserTabSessionStore(6)
 when (args.single()) {
  "control" -> {
   val s = BrowserControlSession()
   s.setHolder(BrowserControlSession.Holder.AI); s.silentInput = true
   check(!s.allowsInputConnection)
   s.touchStarted(); check(s.allowsInputConnection && !s.silentInput)
   s.touchEnded(); s.silentInput = true; s.release()
   check(s.holder == BrowserControlSession.Holder.USER && !s.silentInput)
   s.silentForTool = true; s.close(); check(!s.silentForTool)
  }
  "tab-close" -> {
   val s = store(); val a = s.open { WebView(context) }!!; val b = s.open { WebView(context) }!!
   val results = ArrayList<String>()
   s.awaitLoad(a) { results.add("a:$it") }; s.awaitLoad(b) { results.add("b:$it") }
   a.webView.stopFailure = true; s.close(a)
   check(results == listOf("a:false") && a.webView.destroyed && !b.webView.destroyed)
   s.completeLoad(b, true); check(results == listOf("a:false", "b:true"))
   s.destroy(); check(b.webView.destroyed && s.open { WebView(context) } == null)
  }
  "tab-selection" -> {
   val s = store(); val a = s.open { WebView(context) }!!; val b = s.open { WebView(context) }!!
   s.open { WebView(context) }; s.select(1); s.close(a)
   check(s.current() === b && s.activeIndex == 0)
   val old = s.selection()!!; s.select(1); s.select(0); check(!s.isCurrent(old))
   val navigation = s.selection()!!; s.navigationStarted(b); check(!s.isCurrent(navigation))
  }
  "tab-reentry" -> {
   val s = store(); val a = s.open { WebView(context) }!!
   var calls = 0
   s.awaitLoad(a) { calls++; s.awaitLoad(a) { calls++ } }
   s.completeLoad(a, true); check(calls == 1)
   s.completeLoad(a, true); check(calls == 2)
  }
  "evaluate-close", "evaluate-generation" -> {
   val s = store(); val a = s.open { WebView(context) }!!
   var calls = 0
   s.evaluate(s.selection(), "script") { check(it == null); calls++ }
   if (args.single() == "evaluate-close") { s.close(a); check(calls == 1) }
   else s.navigationStarted(a)
   a.webView.javascriptCallback!!.invoke("old result")
   check(calls == 1)
   a.webView.javascriptCallback!!.invoke("duplicate result"); check(calls == 1)
  }
  "file-late" -> {
   val s = store(); val a = s.open { WebView(context) }!!; val chooser = BrowserFileChooser(s)
   var first = 0; var second = 0
   var oldPicker: ValueCallback<Array<android.net.Uri>>? = null
   var newPicker: ValueCallback<Array<android.net.Uri>>? = null
   chooser.open(a, ValueCallback { first++ }) { oldPicker = it }
   chooser.open(a, ValueCallback { second++ }) { newPicker = it }
   check(first == 1 && second == 0)
   oldPicker!!.onReceiveValue(null); check(second == 0)
   newPicker!!.onReceiveValue(null); chooser.close(); check(first == 1 && second == 1)
  }
  "file-owner" -> {
   val s = store(); val a = s.open { WebView(context) }!!; val b = s.open { WebView(context) }!!
   val chooser = BrowserFileChooser(s); var calls = 0
   chooser.open(b, ValueCallback { calls++ }) {}
   s.close(a); check(calls == 0)
   s.close(b); chooser.close(); check(calls == 1)
  }
  "capture-cancel", "capture-timeout", "capture-invalid" -> {
   val surface = View(context); surface.visibility = View.INVISIBLE
   var suppressions = 0; var restores = 0; var calls = 0; var valid = true
   val service = BrowserCaptureService(surface) { suppressions++; { restores++ } }
   val cancel = service.capture(WebView(context), { valid }, false) { check(it == null); calls++ }
   Handler.runNext(); Handler.runNext()
   val bitmap = PixelCopy.requests.first().bitmap
   when (args.single()) {
    "capture-timeout" -> Handler.runNext()
    "capture-invalid" -> valid = false
    else -> cancel()
   }
   check(!bitmap.isRecycled)
   PixelCopy.complete(); cancel(); service.close()
   check(calls == 1 && bitmap.isRecycled && suppressions == 1 && restores == 1)
   check(surface.visibility == View.INVISIBLE && Handler.count() == 0)
  }
  "capture-stable" -> {
   var result: Bitmap? = null; var calls = 0
   val service = BrowserCaptureService(View(context)) { {} }
   service.capture(WebView(context), { true }, true) { result = it; calls++ }
   Handler.runNext(); Handler.runNext(); PixelCopy.complete()
   check(calls == 0)
   Handler.runNext(); PixelCopy.complete()
   check(calls == 1 && result != null && Bitmap.allocated.first().isRecycled)
   check(!result!!.isRecycled && Handler.count() == 0)
   result!!.recycle(); service.close()
  }
  "capture-overlap" -> {
   val surface = View(context); surface.visibility = View.INVISIBLE
   val service = BrowserCaptureService(surface) { {} }
   val a = service.capture(WebView(context), { true }, false) { it?.recycle() }
   val b = service.capture(WebView(context), { true }, false) { it?.recycle() }
   check(surface.visibility == View.VISIBLE)
   a(); check(surface.visibility == View.VISIBLE)
   b(); check(surface.visibility == View.INVISIBLE && Handler.count() == 0)
  }
  "save-failure" -> {
   val service = BrowserCaptureService(View(context)) { {} }
   val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
   Bitmap.compressResult = false
   var calls = 0
   val directory = File(System.getProperty("java.io.tmpdir"), "failed-shot")
   service.save(bitmap, directory) { check(it == null); calls++ }
   check(bitmap.isRecycled && calls == 1 && directory.listFiles()!!.isEmpty())
   directory.delete()
  }
  else -> error("未知测试")
 }
 println("PASS " + args.single())
}
`,
};

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024 });
  assert.equal(result.status, 0, `${command} 失败\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

before(async () => {
  runtime = await loadRuntime();
  work = mkdtempSync(join(tmpdir(), 'dsh-browser-lifecycle-'));
  const stub = join(work, 'fixtures');
  mkdirSync(stub);
  for (const [name, contents] of Object.entries(fixtures)) writeFileSync(join(stub, name), contents);
  run(runtime.java, ['-cp', runtime.classpath,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', runtime.stdlib, '-d', join(work, 'classes'),
    ...Object.keys(fixtures).map((name) => join(stub, name)),
    ...['BrowserControlSession.kt', 'BrowserTabSessionStore.kt', 'BrowserFileChooser.kt',
      'BrowserCaptureService.kt', 'ShotStore.kt'].map((name) => join(source, name)),
  ]);
});
after(() => {
  if (!work) return;
  assert.equal(dirname(work), tmpdir(), '只清理本次系统临时目录');
  assert.ok(work.startsWith(join(tmpdir(), 'dsh-browser-lifecycle-')));
  rmSync(work, { recursive: true });
});

for (const name of ['control', 'tab-close', 'tab-selection', 'tab-reentry', 'evaluate-close', 'evaluate-generation', 'file-late', 'file-owner',
  'capture-cancel', 'capture-timeout', 'capture-invalid', 'capture-stable', 'capture-overlap', 'save-failure']) {
  test(name, () => {
    const output = run(runtime.java, ['-cp', `${join(work, 'classes')}${delimiter}${runtime.stdlib}`,
      `-Djava.io.tmpdir=${work}`, 'com.dsh.android.browser.BrowserLifecycleCasesKt', name]);
    assert.match(output, new RegExp(`PASS ${name}`));
  });
}
