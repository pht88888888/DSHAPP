import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { join, resolve, dirname, delimiter } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';
test('production bridge grant-covered apply, no approval round-trip, foreground-only controlled UI and projection',()=>{
 const rt=resolveKotlinTestRuntime(),root=resolveRepositoryRoot(import.meta.url),temp=mkdtempSync(join(tmpdir(),'dsh-repair-task-bridge-'));
 const json=process.env.ENGINE_TEST_JSON_JAR;assert.ok(json);
 try{
 const files={
 'content.kt':`package android.content
 class ComponentName(val pkg:String,val cls:String)
 class Intent { var component:ComponentName?=null;var action:String?=null;val extras=mutableMapOf<String,String>();var flags=0
 fun setComponent(c:ComponentName)=apply{component=c};fun setAction(a:String)=apply{action=a};fun putExtra(k:String,v:String)=apply{extras[k]=v};fun addFlags(f:Int)=apply{flags=flags or f};fun getStringExtra(k:String)=extras[k]
 companion object {const val FLAG_ACTIVITY_NEW_TASK=1;const val FLAG_ACTIVITY_CLEAR_TOP=2}}
 `,
 'app.kt':`package android.app
 import android.content.Intent
 class Activity {val filesDir=java.io.File(System.getProperty("test.root"));var isFinishing=false;val starts=mutableListOf<Intent>();val approvals=mutableListOf<Intent>()
 fun startActivity(i:Intent){starts.add(i)};fun startActivityForResult(i:Intent,r:Int){approvals.add(i)};companion object{const val RESULT_OK=-1}}
 `,
 'os.kt':`package android.os
 class Looper{companion object{fun getMainLooper()=Looper()}}
 class Handler(l:Looper){fun post(r:Runnable){queue.add(r)};fun post(f:()->Unit){queue.add(Runnable(f))};fun postDelayed(r:Runnable,d:Long){};fun removeCallbacks(r:Runnable){queue.remove(r)}
 companion object {val queue=java.util.concurrent.ConcurrentLinkedQueue<Runnable>();fun drain(){while(true){val r=queue.poll()?:break;r.run()}}}}
 object SystemClock{fun elapsedRealtime()=100000L}
 `,
 'net.kt':`package android.net
 class Uri`,
 'system.kt':`package android.system
 import java.io.FileDescriptor
 class StructStat(val st_mode:Int=0,val st_size:Long=0)
 object OsConstants{const val O_RDONLY=1;const val O_NOFOLLOW=2;const val O_NONBLOCK=4;fun S_ISREG(m:Int)=true}
 object Os{fun open(p:String,f:Int,m:Int)=FileDescriptor();fun fstat(f:FileDescriptor)=StructStat();fun read(f:FileDescriptor,b:ByteArray,o:Int,n:Int)=0;fun close(f:FileDescriptor){}}
 `,
 'client.kt':`package com.dsh.android.repair.assistant
 import android.app.Activity
 import org.json.JSONObject
 class RepairClient(a:Activity){data class State(val authorized:Boolean=true,val grantId:String?="grant-1");sealed interface Reply{data class Status(val state:State):Reply}
 fun query(t:String,m:String):Reply=Reply.Status(State());fun heartbeat(t:String,g:String):Boolean{heartbeats++;return true}
 fun operation(t:String,g:String,m:String,args:String,approval:String?=null):String { synchronized(calls){calls.add(m)};return when(m){
 "apply_changes","rollback"->JSONObject().put("ok",true).put("status","queued").put("operation_id","op-1").toString()
 "prepare_ui"->JSONObject().put("ok",true).put("task_id","task-1").put("attempt_id","attempt-1").put("task",task("ui_validating")).toString()
 else->JSONObject().put("ok",true).put("task",task("awaiting_ui")).toString()}}
 companion object{var heartbeats=0;var sensitive=false;val calls=mutableListOf<String>();fun task(p:String)=JSONObject().put("task_id","task-1").put("phase",p).put("revision",1).put("change_revision",1).put("evidence_seq",1)}}
 class RepairSessionService{companion object{var interactions=0;fun noteInteraction(t:String,g:String){interactions++}}}
 `,
 'checks.kt':`package com.dsh.android.repair.assistant
 import android.app.Activity
 import android.os.Handler
 import org.json.JSONObject
 import java.io.File
 fun main(){
 val a=Activity();val root=File(a.filesDir,"repair-mailboxes").apply{mkdirs()};val box=File(root,"current").apply{mkdirs()}
 val bridge=RepairAgentBridge.attach(a,"com.dsh.android","grant-1",box.absolutePath)
 val store=RepairConnectionState(root);store.publish("connected","com.dsh.android","active",bridge.binding)
 val method=bridge.javaClass.getDeclaredMethod("dispatch",String::class.java,JSONObject::class.java).apply{isAccessible=true}
 fun dispatch(m:String,args:JSONObject=JSONObject())=method.invoke(bridge,m,args) as JSONObject
 val args=JSONObject().put("change_id","change-1")
 // One task grant covers ordinary and sensitive publication: never a native approval round-trip.
 val ordinary=dispatch("apply_changes",args);check(ordinary.getString("status")=="queued");Handler.drain();check(a.approvals.isEmpty())
 RepairClient.sensitive=true
 val sensitive=dispatch("apply_changes",args);check(sensitive.getString("status")=="queued");Handler.drain();check(a.approvals.isEmpty())
 check(!bridge.onActivityResult(7301,0,null))
 val rollback=dispatch("rollback",args);check(rollback.getString("status")=="queued");Handler.drain();check(a.approvals.isEmpty())
 bridge.detach(a);check(dispatch("rollback",args).getString("status")=="queued");Handler.drain();check(a.approvals.isEmpty())
 bridge.rebind(a);val launch=dispatch("launch_target");Handler.drain();check(launch.getString("status")=="user_action_required" && a.starts.isEmpty())
 check(launch.getString("message").contains("重启并验证"))
 bridge.validateUi();repeat(100){Thread.sleep(2);Handler.drain()};check(a.starts.size==1)
 val intent=a.starts.single();check(intent.action=="com.dsh.android.repair.VALIDATE_UI");check(intent.component!!.pkg=="com.dsh.android" && intent.component!!.cls=="com.dsh.android.ui.MainActivity");check(intent.extras==mapOf("task_id" to "task-1","attempt_id" to "attempt-1"))
 bridge.validateUi();bridge.detach(a);repeat(100){Thread.sleep(2);Handler.drain()};check(a.starts.size==1)
 val before=RepairClient.heartbeats;dispatch("verify_target");check(before==RepairClient.heartbeats)
 val state=JSONObject(File(root,"connection-state.json").readText());check(state.getJSONObject("task").getString("task_id")=="task-1");check(!state.toString().contains("grant-1"))
 val rev=state.getLong("revision");store.project("old-binding",JSONObject().put("task",RepairClient.task("failed")));check(JSONObject(File(root,"connection-state.json").readText()).getLong("revision")==rev)
 bridge.close();check(!File(box,"binding.json").exists())
 println("passed 18 production repair-task bridge checks")
 }
  `};
 for(const[name,body]of Object.entries(files))writeFileSync(join(temp,name),body);
 const cp=[rt.stdlib,json].join(delimiter),out=join(temp,'classes');
 const sources=[...Object.keys(files).map(n=>join(temp,n)),...['RepairAgentBridge.kt','RepairConnectionState.kt'].map(n=>join(root,'repair/assistant/src/main/java/com/dsh/android/repair/assistant',n)),...['RepairProtocol.kt','RepairJsonLimits.kt'].map(n=>join(root,'repair/contract/src/main/java/com/dsh/android/repair/contract',n))];
 const compile=spawnSync(rt.java,['-cp',rt.classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',out,...sources],{encoding:'utf8',timeout:120000});assert.equal(compile.status,0,compile.stdout+'\n'+compile.stderr);
 const run=spawnSync(rt.java,['-Dtest.root='+temp,'-cp',[out,cp].join(delimiter),'com.dsh.android.repair.assistant.ChecksKt'],{encoding:'utf8',timeout:30000});assert.equal(run.status,0,run.stdout+'\n'+run.stderr);assert.match(run.stdout,/passed 18 production repair-task bridge checks/);console.log(run.stdout.trim());
 }finally{assert.equal(dirname(resolve(temp)),resolve(tmpdir()));assert.ok(temp.includes('/dsh-repair-task-bridge-'));rmSync(temp,{recursive:true,force:true})}
});
