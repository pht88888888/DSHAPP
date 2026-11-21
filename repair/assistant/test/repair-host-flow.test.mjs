import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { join, resolve, dirname, delimiter } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('真实修复宿主控制器和原子状态：首击、授权回返、取消、旋转、断开及过期', () => {
 const rt=resolveKotlinTestRuntime(), root=resolveRepositoryRoot(import.meta.url), temporary=mkdtempSync(join(tmpdir(),'dsh-repair-host-'));
 const json=process.env.ENGINE_TEST_JSON_JAR;
 assert.ok(json,'需要 ENGINE_TEST_JSON_JAR');
 try {
 const files={
 'android.kt':`package android
 object R { object string { const val cancel=1; const val ok=2 } }`,
 'content.kt':`package android.content
 open class Intent() { constructor(a:android.app.Activity,c:Class<*>):this(); fun setComponent(c:ComponentName)=this; fun setAction(a:String)=this; fun putExtra(k:String,v:String)=this }
 class ComponentName(val pkg:String,val cls:String)`,
 'app.kt':`package android.app
 import java.io.File
 import android.content.Intent
 open class Activity {
  val filesDir=File(System.getProperty("test.root")); var isFinishing=false; var starts=0; var foregroundStarts=0; var serviceStops=0
  val packageName="com.dsh.fixhelp"
  fun startActivityForResult(i:Intent,r:Int){ starts++ }
  fun startForegroundService(i:Intent){ foregroundStarts++ }
  fun stopService(i:Intent){ serviceStops++ }
  fun <T> getSystemService(c:Class<T>):T = c.getDeclaredConstructor().newInstance()
  companion object { const val RESULT_OK=-1 }
 }
 class NotificationManager
 class AlertDialog {
  var onDismiss:(()->Unit)?=null; var items:((Any?,Int)->Unit)?=null; var negative:((Any?,Int)->Unit)?=null;var labels=emptyArray<String>()
  fun setOnDismissListener(f:()->Unit){onDismiss=f}; fun show(){ latest=this;shown++ }; fun dismiss(){onDismiss?.invoke()}
  class Builder(val a:Activity){ val d=AlertDialog(); fun setTitle(t:String)=this; fun setMessage(t:String)=this
   fun setItems(v:Array<String>,f:(Any?,Int)->Unit)=apply {d.items=f;d.labels=v}; fun setNegativeButton(id:Int,f:((Any?,Int)->Unit)?)=apply {d.negative=f}
   fun setNegativeButton(t:String,f:((Any?,Int)->Unit)?)=apply {d.negative=f}; fun setPositiveButton(t:String,f:((Any?,Int)->Unit)?)=this
   fun setPositiveButton(id:Int,f:((Any?,Int)->Unit)?)=this; fun setCancelable(v:Boolean)=this; fun create()=d; fun show()=d.also{it.show()}
  }
  companion object { var latest:AlertDialog?=null;var shown=0 }
 }`,
 'os.kt':`package android.os
 class Looper { companion object { fun getMainLooper()=Looper() } }
 class Handler(l:Looper){ fun post(r:Runnable){queue.add(r)}; fun post(f:()->Unit){queue.add(Runnable(f))}
  fun postDelayed(r:Runnable,d:Long){}; fun postDelayed(f:()->Unit,d:Long){};fun removeCallbacks(r:Runnable){};fun removeCallbacksAndMessages(t:Any?){}
  companion object {val queue=java.util.concurrent.ConcurrentLinkedQueue<Runnable>();fun drain(){while(true){val x=queue.poll()?:break;x.run()}}}
 }`,
 'bridge.kt':`package com.dsh.android.repair.assistant
 import android.app.Activity
 import android.app.NotificationManager
 import android.content.Intent
 class RepairClient(a:Activity){enum class Failure{NOT_INSTALLED,NO_PROVIDER,NO_REPLY,WRONG_SIGNATURE,ACCESS_DENIED,INCOMPATIBLE,CONNECTION_FAILED}
  data class State(val authorized:Boolean,val grantId:String?)
  data class Availability(val target:String,val version:String?,val failure:Failure?)
  sealed interface Reply {data class Status(val state:State):Reply;data class Failed(val reason:Failure):Reply}
  fun availability(t:String):Availability { availabilityChecks++;return Availability(t,"1.2.3",if(unavailable)Failure.NOT_INSTALLED else if(availabilityChecks>=denyAvailabilityAt)Failure.WRONG_SIGNATURE else null) }
  fun query(t:String,m:String):Reply { queries++;return queryFailure?.let{Reply.Failed(it)}?:Reply.Status(State(authorized,if(authorized)"grant-1" else null)) }
  fun revoke(t:String,g:String):Boolean{revokes++;return true}
  companion object { @Volatile var authorized=false; @Volatile var queries=0;var revokes=0;var unavailable=false;var availabilityChecks=0;var denyAvailabilityAt=Int.MAX_VALUE;@Volatile var queryFailure:Failure?=null }
 }
 class RepairAgentBridge(val target:String,val grant:String) { var alive=true;val binding="binding-1";fun close(){alive=false;live=null};fun isOpen()=alive;fun markActive(){};fun validateUi(){uiValidations++};fun detach(a:Activity){};fun rebind(a:Activity){rebinds++};fun onActivityResult(r:Int,c:Int,d:Intent?)=false
  companion object {var live:RepairAgentBridge?=null;var rebinds=0;var attached=0;var uiValidations=0;fun current()=live; fun attach(a:Activity,t:String,g:String,p:String)=RepairAgentBridge(t,g).also{attached++;live=it} }
 }
 class RepairSessionService {companion object{ const val ACTION_START="start";const val EXTRA_TARGET="target";fun ensureChannel(a:Activity){};fun canNotify(n:NotificationManager)=notify;var notify=true}}`,
 'flow.kt':`package com.dsh.android.repair.assistant
 import android.app.Activity
 import android.app.AlertDialog
 import android.os.Handler
 import java.io.File
 import org.json.JSONObject
 fun awaitMain(){repeat(100){Handler.drain();Thread.sleep(2)}}
 fun state()=JSONObject(File(System.getProperty("test.root"),"repair-mailboxes/connection-state.json").readText())
 fun phase()=state().getString("phase")
 fun main(){
  val a=Activity();val c=RepairHostController(a)
  c.onStart();Handler.drain();c.open();check(AlertDialog.latest!!.labels[0].contains("1.2.3"));AlertDialog.latest!!.items!!.invoke(null,0)
  awaitMain();check(a.starts==1){"first click launches authorization"};check(phase()=="authorizing");check(RepairAgentBridge.attached==0)
  c.onPause();RepairClient.authorized=true
  check(c.onActivityResult(7302,Activity.RESULT_OK,null));awaitMain();check(RepairAgentBridge.attached==0){"result before resume queued"}
  c.onStart();awaitMain();check(RepairAgentBridge.attached==1 && a.foregroundStarts==1);check(phase()=="connected")
  check(AlertDialog.shown==2){"overlay gets picker and exactly one terminal result, no progress popup barrage"}
  val text=state().toString();check(!text.contains("grant-1"));check(state().keys().asSequence().toSet()==setOf("phase","target","label","message","revision","binding"))
  val firstBinding=state().getString("binding");c.close();check(a.serviceStops==0 && RepairClient.revokes==0){"rotation is not disconnect"}
  val rotated=Activity();val rotation=RepairHostController(rotated);rotation.onStart();Handler.drain();check(RepairAgentBridge.rebinds==1 && RepairAgentBridge.attached==1);check(state().getString("binding")==firstBinding)
  val store=RepairConnectionState(File(rotated.filesDir,"repair-mailboxes"));val revision=state().getLong("revision")
  store.ended("older-binding","com.dsh.android","expired","old status");check(state().getLong("revision")==revision){"stale service must not overwrite new epoch"}
  rotation.open();AlertDialog.latest!!.negative!!.invoke(null,0);awaitMain();check(rotated.serviceStops==1 && RepairClient.revokes==1 && phase()=="disconnected");rotation.close()
  val b=Activity();val d=RepairHostController(b);RepairClient.authorized=false
  d.onStart();Handler.drain();d.open();AlertDialog.latest!!.items!!.invoke(null,1);awaitMain();check(b.starts==1)
  d.onPause();check(d.onActivityResult(7302,0,null));d.onStart();awaitMain();check(b.foregroundStarts==0 && phase()=="disconnected");d.close()
  val e=Activity();val f=RepairHostController(e);f.onStart();Handler.drain();f.open();AlertDialog.latest!!.items!!.invoke(null,0);f.onPause();awaitMain()
  val before=e.starts;f.onStart();awaitMain();check(e.starts==before+1){"pause during query resumes without stuck progress"};f.onActivityResult(7302,0,null);f.close()
  RepairClient.unavailable=true;val g=Activity();val h=RepairHostController(g);h.onStart();Handler.drain();h.open();check(AlertDialog.latest!!.labels[0].contains("未安装"));val errorShown=AlertDialog.shown;AlertDialog.latest!!.items!!.invoke(null,0);check(g.starts==0 && phase()=="error");check(AlertDialog.shown==errorShown+1){"overlay failure receives one native result"};h.close();RepairClient.unavailable=false
  RepairClient.authorized=true;RepairSessionService.notify=false;val i=Activity();val j=RepairHostController(i);j.onStart();Handler.drain();j.open();AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();check(i.foregroundStarts==0 && phase()=="error");j.close();RepairSessionService.notify=true
  store.publish("connected","com.dsh.android","connected","new-binding");store.ended("new-binding","com.dsh.android","expired","expired");check(phase()=="expired" && !state().has("binding"))
  val k=Activity();val l=RepairHostController(k);check(phase()=="expired"){"recreation retains durable expiry feedback"};l.onStart();Handler.drain();val control=File(k.filesDir,"repair-mailboxes/control-request.json")
  control.writeText("{\\"action\\":\\"authorize\\",\\"target\\":\\"com.dsh.android\\"}");val shown=AlertDialog.shown;l.onStart();Handler.drain();check(AlertDialog.shown==shown && !control.exists()){"control rejects target injection"}
  control.writeText("{\\"action\\":\\"authorize\\"}");l.onStart();Handler.drain();check(AlertDialog.shown==shown+1);AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();check(phase()=="connected" && AlertDialog.shown==shown+1){"settings action has inline status only"}
  control.writeText("{\\"action\\":\\"disconnect\\"}");l.onStart();Handler.drain();awaitMain();check(phase()=="disconnected" && AlertDialog.shown==shown+1){"settings disconnect does not open native result"};l.close()
  RepairClient.authorized=false;val m=Activity();val n=RepairHostController(m);n.onStart();Handler.drain();n.open();AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();n.onPause();val cancelledShown=AlertDialog.shown;n.onActivityResult(7302,0,null);check(AlertDialog.shown==cancelledShown){"background cancellation queues feedback"};n.close()
  val o=Activity();val p=RepairHostController(o);p.onStart();Handler.drain();check(AlertDialog.shown==cancelledShown+1){"overlay cancellation feedback survives recreation"};p.close()
  // Actual OEM cold-provider regression: explicit trusted selection gets one consent fallback.
  RepairClient.queryFailure=RepairClient.Failure.NO_REPLY;RepairClient.authorized=false
  val cold=Activity();val coldHost=RepairHostController(cold);coldHost.onStart();Handler.drain();coldHost.open();AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();check(cold.starts==1 && cold.foregroundStarts==0 && phase()=="authorizing"){"first NO_REPLY opens native consent, not a grant"}
  coldHost.onPause();RepairClient.queryFailure=null;RepairClient.authorized=true;coldHost.onActivityResult(7302,Activity.RESULT_OK,null);coldHost.onStart();awaitMain();check(cold.starts==1 && cold.foregroundStarts==1 && phase()=="connected"){"consent return continues original click"}
  val coldControl=File(cold.filesDir,"repair-mailboxes/control-request.json");coldControl.writeText("{\\"action\\":\\"disconnect\\"}");coldHost.onStart();Handler.drain();awaitMain();coldHost.close()
  RepairClient.queryFailure=RepairClient.Failure.NO_REPLY;RepairClient.authorized=false
  val stillCold=Activity();val stillHost=RepairHostController(stillCold);stillHost.onStart();Handler.drain();stillHost.open();AlertDialog.latest!!.items!!.invoke(null,1);awaitMain();check(stillCold.starts==1);stillHost.onPause();val stillShown=AlertDialog.shown;stillHost.onActivityResult(7302,Activity.RESULT_OK,null);stillHost.onStart();awaitMain();check(stillCold.starts==1 && stillCold.foregroundStarts==0 && phase()=="error" && AlertDialog.shown==stillShown+1){"NO_REPLY after consent fails once without native loop"};stillHost.close()
  val cancelCold=Activity();val cancelHost=RepairHostController(cancelCold);cancelHost.onStart();Handler.drain();cancelHost.open();AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();val queriesBeforeCancel=RepairClient.queries;cancelHost.onPause();val cancelShown=AlertDialog.shown;cancelHost.onActivityResult(7302,0,null);cancelHost.onStart();awaitMain();check(cancelCold.starts==1 && cancelCold.foregroundStarts==0 && RepairClient.queries==queriesBeforeCancel && phase()=="disconnected" && AlertDialog.shown==cancelShown+1){"cancel fallback neither queries nor reopens consent"};cancelHost.close()
  for(failure in listOf(RepairClient.Failure.WRONG_SIGNATURE,RepairClient.Failure.ACCESS_DENIED,RepairClient.Failure.NO_PROVIDER,RepairClient.Failure.INCOMPATIBLE,RepairClient.Failure.CONNECTION_FAILED)){
   RepairClient.queryFailure=failure;val denied=Activity();val deniedHost=RepairHostController(denied);deniedHost.onStart();Handler.drain();deniedHost.open();AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();check(denied.starts==0 && denied.foregroundStarts==0 && phase()=="error"){"security/incompatibility failure never launches consent: $failure"};deniedHost.close()
  }
  RepairClient.queryFailure=RepairClient.Failure.NO_REPLY;RepairClient.availabilityChecks=0;RepairClient.denyAvailabilityAt=3
  val changed=Activity();val changedHost=RepairHostController(changed);changedHost.onStart();Handler.drain();changedHost.open();AlertDialog.latest!!.items!!.invoke(null,0);awaitMain();check(RepairClient.availabilityChecks==3 && changed.starts==0 && phase()=="error"){"trust is revalidated before NO_REPLY fallback"};changedHost.close();RepairClient.queryFailure=null;RepairClient.denyAvailabilityAt=Int.MAX_VALUE
  println("passed 33 production rescue-host/status lifecycle checks")
 }`
 };
 for(const [name,body]of Object.entries(files))writeFileSync(join(temporary,name),body);
 const output=join(temporary,'classes'),cp=[rt.stdlib,json].join(delimiter);
 const sources=[...Object.keys(files).map(n=>join(temporary,n)),join(root,'app/src/main/java/com/dsh/android/repair/RepairAssistantHost.kt'),join(root,'repair/contract/src/main/java/com/dsh/android/repair/contract/RepairProtocol.kt'),...['RepairHostController.kt','RepairConnectionState.kt'].map(n=>join(root,'repair/assistant/src/main/java/com/dsh/android/repair/assistant',n))];
 const compile=spawnSync(rt.java,['-cp',rt.classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',output,...sources],{encoding:'utf8',timeout:120000});
 assert.equal(compile.status,0,compile.stdout+'\n'+compile.stderr);
 const run=spawnSync(rt.java,['-Dtest.root='+temporary,'-cp',[output,cp].join(delimiter),'com.dsh.android.repair.assistant.FlowKt'],{encoding:'utf8',timeout:30000});
 assert.equal(run.status,0,run.stdout+'\n'+run.stderr);assert.match(run.stdout,/passed 33 production rescue-host\/status lifecycle checks/);console.log(run.stdout.trim());
 }finally{assert.equal(dirname(resolve(temporary)),resolve(tmpdir()));assert.ok(temporary.includes('/dsh-repair-host-'));rmSync(temporary,{recursive:true,force:true})}
});
