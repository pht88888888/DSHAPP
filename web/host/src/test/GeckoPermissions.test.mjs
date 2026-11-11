import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, delimiter, dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { after, before, test } from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../../tools/kotlin-test-runtime.mjs';

const root = resolveRepositoryRoot(import.meta.url);
const source = readFileSync(join(root, 'web/host/src/gecko/java/com/dsh/android/web/GeckoWebHost.kt'), 'utf8');
// Keep production bodies intact: substitutes only implement the narrow platform API.
function declaration(marker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, `Missing production declaration: ${marker}`);
  const open = source.indexOf('{', start);
  let depth = 0, quote = false, line = false, block = 0;
  for (let i = open; i < source.length; i++) {
    const c = source[i], next = source[i + 1];
    if (line) { if (c === '\n') line = false; continue; }
    if (block) {
      if (c === '/' && next === '*') { block++; i++; }
      else if (c === '*' && next === '/') { block--; i++; }
      continue;
    }
    if (quote) { if (c === '\\') i++; else if (c === '"') quote = false; continue; }
    if (c === '/' && next === '/') { line = true; i++; continue; }
    if (c === '/' && next === '*') { block++; i++; continue; }
    if (c === '"') { quote = true; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`Unclosed production declaration: ${marker}`);
}
const runtimeStart = source.indexOf('    private val runtime: GeckoRuntime');
const runtimeEnd = source.indexOf('\n\n', runtimeStart);
assert.ok(runtimeStart >= 0 && runtimeEnd > runtimeStart);
const runtimeField = source.slice(runtimeStart, runtimeEnd).replace('private val ', 'val ');
const initStart = source.indexOf('    init {');
const initEnd = source.indexOf('        session.progressDelegate =', initStart);
assert.ok(initStart >= 0 && initEnd > initStart);
const runtimeInit = source.slice(initStart + '    init {'.length, initEnd);
const fixture = `
class Context
object Color { fun parseColor(value: String) = 0 }
class GeckoView { fun setBackgroundColor(value: Int) {} }
class Handler {
 val pending = ArrayDeque<() -> Unit>()
 fun post(action: () -> Unit) { pending.addLast(action) }
 fun drain() { while (pending.isNotEmpty()) pending.removeFirst()() }
}
class GeckoSessionSettings {
 fun setAllowJavascript(value: Boolean) {}
 fun setUserAgentMode(value: Int) {}
 fun setSuspendMediaWhenInactive(value: Boolean) {}
 companion object { const val USER_AGENT_MODE_MOBILE = 1 }
}
class GeckoSession {
 val settings = GeckoSessionSettings()
 interface PermissionDelegate {
  class MediaSource(val source: Int, val id: String) {
   companion object { const val SOURCE_MICROPHONE = 1; const val SOURCE_CAMERA = 2 }
  }
  interface MediaCallback { fun grant(video: MediaSource?, audio: MediaSource?); fun reject() }
 }
}
class GeckoRuntimeSettings {
 var remoteDebuggingEnabled = false
 fun setAllowInsecureConnections(value: Int) {}
 class Builder {
  private val settings = GeckoRuntimeSettings()
  fun consoleOutput(value: Boolean) = this
  fun remoteDebuggingEnabled(value: Boolean): Builder { settings.remoteDebuggingEnabled = value; return this }
  fun build() = settings
 }
 companion object { const val ALLOW_ALL = 1 }
}
class GeckoRuntime(val settings: GeckoRuntimeSettings) {
 companion object {
  var singleton: GeckoRuntime? = null
  var creates = 0
  fun getDefault(context: Context): GeckoRuntime {
   if (singleton == null) { singleton = GeckoRuntime(GeckoRuntimeSettings()); creates++ }
   return singleton!!
  }
  fun create(context: Context, settings: GeckoRuntimeSettings): GeckoRuntime {
   creates++; return GeckoRuntime(settings).also { singleton = it }
  }
 }
}
fun purgeAuthCookies(context: Context) {}
object Log {
 const val TAG = "fixture"
 fun i(tag: String, message: String) {}
 fun w(tag: String, message: String) {}
}
enum class WebCapability(val label: String) { Microphone("microphone"), Camera("camera") }
class Capability {
 val requests = mutableListOf<WebCapability>()
 val replies = ArrayDeque<(Boolean) -> Unit>()
 fun request(capability: WebCapability, reply: (Boolean) -> Unit) { requests.add(capability); replies.addLast(reply) }
 fun answer(granted: Boolean) { replies.removeFirst()(granted) }
}
class Callbacks { val capability = Capability() }
class Reply : GeckoSession.PermissionDelegate.MediaCallback {
 var grants = 0; var rejects = 0
 var video: GeckoSession.PermissionDelegate.MediaSource? = null
 var audio: GeckoSession.PermissionDelegate.MediaSource? = null
 override fun grant(video: GeckoSession.PermissionDelegate.MediaSource?, audio: GeckoSession.PermissionDelegate.MediaSource?) {
  grants++; this.video = video; this.audio = audio
 }
 override fun reject() { rejects++ }
}
class Host {
 val context = Context(); val geckoView = GeckoView(); val session = GeckoSession()
 val callbacks = Callbacks(); val uiHandler = Handler(); var destroyed = false
 ${runtimeField}
 init { ${runtimeInit} }
 ${declaration('private fun postToUi(')}
 ${declaration('override fun onMediaPermissionRequest(').replace('override fun ', 'fun ')}
 ${declaration('private fun askSequentially(')}
}
${declaration('private fun createRuntime(')}
fun request(host: Host, video: Boolean, audio: Boolean): Reply {
 val reply = Reply()
 host.onMediaPermissionRequest(host.session, "http://127.0.0.1/",
  if (video) arrayOf(GeckoSession.PermissionDelegate.MediaSource(2,"camera-id")) else null,
  if (audio) arrayOf(GeckoSession.PermissionDelegate.MediaSource(1,"microphone-id")) else null, reply)
 return reply
}
fun main(args: Array<String>) {
 val scenario = args.single()
 when (scenario) {
  "audio", "video", "combined" -> {
   val host = Host(); val video = scenario != "audio"; val audio = scenario != "video"
   val reply = request(host, video, audio)
   val expected = buildList { if (audio) add(WebCapability.Microphone); if (video) add(WebCapability.Camera) }
   repeat(expected.size) { host.callbacks.capability.answer(true) }
   check(host.callbacks.capability.requests == expected)
   check(reply.grants == 0) { "grant must wait for UI dispatch" }; host.uiHandler.drain()
   check(reply.grants == 1 && reply.rejects == 0)
   check(reply.video?.id == if (video) "camera-id" else null) { "grant first argument must be video, observed " + reply.video?.id }
   check(reply.audio?.id == if (audio) "microphone-id" else null) { "grant second argument must be audio, observed " + reply.audio?.id }
  }
  "denied" -> {
   val host = Host(); val reply = request(host,true,true)
   host.callbacks.capability.answer(false); host.uiHandler.drain()
   check(reply.rejects == 1 && reply.grants == 0)
   check(host.callbacks.capability.requests == listOf(WebCapability.Microphone))
  }
  "destroyed" -> {
   val initial = Host(); initial.destroyed = true; val rejected = request(initial,true,true)
   check(rejected.rejects == 1 && rejected.grants == 0 && initial.callbacks.capability.requests.isEmpty())
   val late = Host(); val pending = request(late,true,true); late.destroyed = true
   late.callbacks.capability.answer(true); late.uiHandler.drain(); check(pending.grants == 0)
   check(late.callbacks.capability.requests.size == 1)
   val queued = Host(); val dispatched = request(queued,false,true)
   queued.callbacks.capability.answer(true); check(queued.uiHandler.pending.size == 1)
   val dequeued = queued.uiHandler.pending.removeFirst(); queued.destroyed = true; dequeued()
   check(dispatched.grants == 0)
  }
  "runtime" -> {
   val first = Host()
   check(first.runtime.settings.remoteDebuggingEnabled) { "actual Host initializer must enable remote debugging on non-null default runtime" }
   val second = Host()
   check(second.runtime === first.runtime) { "Host recreation must reuse process runtime" }
   check(second.runtime.settings.remoteDebuggingEnabled && GeckoRuntime.creates == 1)
  }
  else -> error("Unknown case")
 }
 println("PASS " + scenario)
}
`;
let work, runtime;
before(() => {
  runtime = resolveKotlinTestRuntime();
  work = mkdtempSync(join(realpathSync(tmpdir()), 'dsh-gecko-permissions-'));
  const filename = join(work, 'Permissions.kt'); writeFileSync(filename, fixture);
  const result = spawnSync(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', runtime.stdlib, '-d', join(work, 'tests.jar'), filename], {encoding:'utf8',timeout:60_000});
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
});
after(() => {
  if (!work) return;
  assert.equal(dirname(work), realpathSync(tmpdir())); assert.ok(basename(work).startsWith('dsh-gecko-permissions-'));
  rmSync(work,{recursive:true,force:true});
});
for (const [scenario, name] of [
  ['audio','audio-only places microphone in grant audio argument'],
  ['video','video-only places camera in grant video argument'],
  ['combined','combined permission preserves video/audio API order after sequential grants'],
  ['denied','denied capability rejects without granting or asking remaining capabilities'],
  ['destroyed','destroyed Host rejects new requests and ignores late approvals and queued grant'],
  ['runtime','actual Host initializer enables debugging on reused non-null default runtime'],
]) test(name, () => {
  const result = spawnSync(runtime.java,['-cp',[join(work,'tests.jar'),runtime.stdlib].join(delimiter),'PermissionsKt',scenario],{encoding:'utf8',timeout:10_000});
  assert.equal(result.status,0,`${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  assert.equal(result.stdout.trim(),`PASS ${scenario}`);
});
