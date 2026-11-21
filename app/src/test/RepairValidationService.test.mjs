import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, delimiter } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('production validation service records rejected permit and foreground failure without starting engine (host service ports)', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const json = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(json && existsSync(json), 'ENGINE_TEST_JSON_JAR required');
  const coroutines = runtime.jars.find(p => /kotlinx-coroutines-core-jvm-/.test(p));
  assert.ok(coroutines, 'Existing Kotlin coroutines jar required');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-validation-service-'));
  try {
    const inputs = {
      'Android.kt': `package android
object R { object drawable { const val ic_menu_manage=1 } }`,
      'Os.kt': `package android.os
interface IBinder
object SystemClock { fun elapsedRealtime()=System.nanoTime()/1000000 }`,
      'Intent.kt': `package android.content
class Intent(var action:String?=null) { val extras=mutableMapOf<String,String>(); fun getStringExtra(k:String)=extras[k] }`,
      'Service.kt': `package android.app
import java.io.File
import android.content.Intent
import android.os.IBinder
open class Service {
 val filesDir:File get()=root; val application:Any=com.dsh.android.core.DshApplication()
 var stopped=false; var foreground=false
 open fun onBind(intent:Intent?):IBinder?=null
 open fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=0
 open fun onDestroy(){}
 fun <T> getSystemService(cls:Class<T>):T=cls.cast(NotificationManager())
 fun startForeground(id:Int,notification:Any){ if(rejectForeground) error("denied");foreground=true }
 fun stopForeground(flags:Int){foreground=false}
 fun stopSelf(id:Int){stopped=true}
 companion object { lateinit var root:File; var rejectForeground=false;const val START_NOT_STICKY=2;const val STOP_FOREGROUND_REMOVE=1 }
}
class NotificationChannel(id:String,name:String,importance:Int)
class NotificationManager { fun createNotificationChannel(channel:NotificationChannel){};companion object { const val IMPORTANCE_LOW=2 } }`,
      'Notification.kt': `package androidx.core.app
object NotificationCompat { class Builder(context:Any,channel:String) { fun setSmallIcon(id:Int)=this;fun setContentTitle(s:String)=this;fun setContentText(s:String)=this;fun setOngoing(b:Boolean)=this;fun setSilent(b:Boolean)=this;fun build()=Any() } }`,
      'Atomic.kt': `package android.util
import java.io.File
import java.io.FileOutputStream
class AtomicFile(val file:File) { fun startWrite()=FileOutputStream(file); fun finishWrite(out:FileOutputStream){out.close()};fun failWrite(out:FileOutputStream){out.close()} }`,
      'Application.kt': `package com.dsh.android.core
class DshApplication { suspend fun ensureRuntimeInitialized(p:com.dsh.android.repair.RepairRuntimeGate.Permit)=true }`,
      'Engine.kt': `package com.dsh.android.engine
class EngineManager {
 sealed interface State { data object Idle:State; data class Ready(val url:String):State }
 class Cell(var value:State);val state=Cell(State.Idle)
 fun resetUiStage(){};suspend fun ensureExtracted(s:String)=true;suspend fun start(startupTimeoutMs:Long=120000L){starts++};suspend fun stopForRepair()=true
 companion object {var starts=0;fun get(c:Any)=EngineManager()}
}`,
      'Gate.kt': `package com.dsh.android.repair
import org.json.JSONObject
import java.io.File
object RepairRuntimeGate {
 const val EXTRA_TASK="task_id";const val EXTRA_ATTEMPT="attempt_id";const val ACTION_ENGINE="engine"
 data class Permit(val taskId:String,val attemptId:String,val kind:String,val id:String,val deadlineElapsed:Long)
 var acquisitions=0;var reports=0
 fun validId(s:String)=s.matches(Regex("[A-Za-z0-9_-]{1,128}"))
 fun acquire(c:Any,kind:String,task:String,attempt:String):Permit? {acquisitions++;return null}
 fun valid(c:Any,p:Permit)=false
 fun report(c:Any,p:Permit,event:String,error:String=""):Boolean {reports++;return false}
 fun clear(p:Permit){}
}
object RepairDiagnostics { fun evidenceFromLogs(f:File)=JSONObject() }`,
      'Boot.kt': `package com.dsh.android.log
object BootState {val bootId="fixture-boot"}`,
      'Checks.kt': `package com.dsh.android.repair
import android.app.Service
import android.content.Intent
import java.io.File
import org.json.JSONObject
fun main(args:Array<String>) {
 Service.root=File(args.single()).apply{mkdirs()}
 fun launch(attempt:String):RepairValidationService { val s=RepairValidationService();val i=Intent(RepairRuntimeGate.ACTION_ENGINE);i.extras["task_id"]="task-1";i.extras["attempt_id"]=attempt;s.onStartCommand(i,0,1);return s }
 fun result(attempt:String)=JSONObject(File(Service.root,"repair-control/validation/$attempt.json").readText())
 val rejected=launch("permit-null")
 val deadline=System.nanoTime()+3000000000L
 while(!rejected.stopped && System.nanoTime()<deadline)Thread.sleep(5)
 check(rejected.stopped && !rejected.foreground)
 val report=result("permit-null")
 check(report.getString("task_id")=="task-1" && report.getString("attempt_id")=="permit-null")
 check(report.getString("status")=="failed" && report.getString("error_code")=="permit_rejected" && report.getBoolean("stopped"))
 check(com.dsh.android.engine.EngineManager.starts==0 && RepairRuntimeGate.acquisitions==1 && RepairRuntimeGate.reports==0)
 rejected.onDestroy()
 Service.rejectForeground=true
 val denied=launch("fgs-denied");check(denied.stopped)
 val failure=result("fgs-denied")
 check(failure.getString("status")=="failed" && failure.getString("error_code")=="service_foreground_failed" && failure.getBoolean("stopped"))
 check(RepairRuntimeGate.acquisitions==1 && com.dsh.android.engine.EngineManager.starts==0)
 denied.onDestroy()
 println("passed 8 production validation service checks")
}`,
    };
    const sources = Object.entries(inputs).map(([name, text]) => { const p=join(temp,name);writeFileSync(p,text);return p; });
    sources.push(join(root,'app/src/main/java/com/dsh/android/repair/RepairValidationService.kt'));
    const cp=[runtime.stdlib,json,coroutines].join(delimiter), classes=join(temp,'classes');
    const compile=spawnSync(runtime.java,['-cp',runtime.classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',classes,...sources],{encoding:'utf8',timeout:120000});
    assert.equal(compile.status,0,`${compile.stdout}\n${compile.stderr}`);
    const run=spawnSync(runtime.java,['-cp',[classes,cp].join(delimiter),'com.dsh.android.repair.ChecksKt',join(temp,'data')],{encoding:'utf8',timeout:10000});
    assert.equal(run.status,0,`${run.stdout}\n${run.stderr}`);assert.match(run.stdout,/passed 8 production validation service checks/);
  } finally { assert.equal(join(temp,'..'),tmpdir());rmSync(temp,{recursive:true,force:true}); }
});

test('actual child waitFor wakes readiness even when stdout remains owned by descendants', () => {
  const root=resolveRepositoryRoot(import.meta.url);
  const source=readFileSync(join(root,'runtime/engine/src/main/java/com/dsh/android/engine/EngineManager.kt'),'utf8');
  const begin=source.indexOf('        val readyLatch = java.util.concurrent.CountDownLatch(1)',source.indexOf('val proc = try'));
  const end=source.indexOf('\n        // 只用一个后台线程',begin);
  assert.ok(begin>0 && end>begin);
  const production=source.slice(begin,end).replace('var readyUrl: String? = null','');
  assert.match(production,/proc\.waitFor\(\).*finally \{ readyLatch\.countDown\(\); repairExclusion\.close\(\) \}/s);
  const runtime=resolveKotlinTestRuntime();const temp=mkdtempSync(join(tmpdir(),'dsh-child-ready-'));
  try {
    const input=join(temp,'Check.kt');writeFileSync(input,`fun main() { val proc=ProcessBuilder("sh","-c","sleep 3 & exit 7").start();val repairExclusion=java.io.Closeable {}\n${production}\ncheck(readyLatch.await(1500,java.util.concurrent.TimeUnit.MILLISECONDS));check(!proc.isAlive);println("child exit promptly wakes readiness") }`);
    const classes=join(temp,'classes');const compile=spawnSync(runtime.java,['-cp',runtime.classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',runtime.stdlib,'-d',classes,input],{encoding:'utf8',timeout:120000});
    assert.equal(compile.status,0,compile.stderr);
    const run=spawnSync(runtime.java,['-cp',[classes,runtime.stdlib].join(delimiter),'CheckKt'],{encoding:'utf8',timeout:5000});assert.equal(run.status,0,run.stderr);assert.match(run.stdout,/promptly wakes/);
  } finally { assert.equal(join(temp,'..'),tmpdir());rmSync(temp,{recursive:true,force:true}); }
});
