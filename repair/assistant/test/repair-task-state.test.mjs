import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';
import { projectState, evidenceNotice, deliverEvidence, validateTheme } from '../src/main/assets/repair-agent/rescue-entry/state.js';
import { validateArgs } from '../src/main/assets/repair-agent/tools/contract.js';
const requireEngine = createRequire(process.env.DSH_ENGINE_PACKAGE);
const { createUserMessage } = await import(pathToFileURL(requireEngine.resolve('@deepseek-ai/dsh-llm')).href);
const nativeState = () => ({phase:'connected',target:'com.dsh.android',label:'System',message:'active',revision:2,binding:'binding-1',grant:'not-public',
  task:{task_id:'task-1',phase:'awaiting_ui',revision:5,change_revision:2,evidence_seq:4,last_attempt:{attempt_id:'attempt-1',operation_id:'op-1',level:'engine',status:'passed',change_revision:2,verified:false,private:'ignored'}},
  evidence:{summary:'engine ready',message:'bounded diagnostic',secret:'ignored'}});
test('task projection is closed, public and bounds evidence without grant or credentials',()=>{
  const value=projectState(nativeState());
  assert.equal(value.binding,undefined);assert.equal(value.grant,undefined);
  assert.equal(value.task.last_attempt.private,undefined);assert.equal(value.evidence.secret,undefined);
  assert.equal(value.task.last_attempt.verified,false);
  assert.equal(projectState(nativeState(),true).binding,'binding-1');
  const state=nativeState();state.evidence.message='a'.repeat(5000);state.task.message='正在准备目标运行环境';
  assert.equal(projectState(state).evidence.message.length,1024);
  assert.equal(projectState(state).task.message,'正在准备目标运行环境');
  state.task.message='p'.repeat(5000);assert.equal(projectState(state).task.message.length,1024);
});
test('only terminal current-change attempt creates native notice and never claims engine is UI success',()=>{
  const state=projectState(nativeState());
  assert.match(evidenceNotice(state).key,/task-1:attempt-1:4/);
  assert.match(evidenceNotice(state).text,/untrusted diagnostic data/);
  assert.match(evidenceNotice(state).text,/"verified":false/);
  for(const status of ['queued','running']){state.task.last_attempt.status=status;assert.equal(evidenceNotice(state),null);}
  state.task.last_attempt.status='passed';state.task.last_attempt.change_revision=1;assert.equal(evidenceNotice(state),null);
});
test('official identified messages inject while running, followup while idle, dedupe inbox and durable session across observer recreation',()=>{
  const state=projectState(nativeState()), log=[], nextStep=[], nextTurn=[], calls=[];
  const agent={status:'running',session:{deriveMessages:()=>log},inbox:{nextStep,nextTurn},inject:message=>{calls.push('inject');nextStep.push(message)},followup:message=>{calls.push('followup');nextTurn.push(message)}};
  const seen=new Set();
  assert.equal(deliverEvidence(agent,state,seen,createUserMessage),true);
  assert.equal(nextStep[0].role,'user');assert.ok(nextStep[0].id);assert.equal(nextStep[0].source.kind,'android-repair-evidence');
  assert.equal(deliverEvidence(agent,state,seen,createUserMessage),false);
  assert.equal(deliverEvidence(agent,state,new Set(),createUserMessage),false,'pending inbox survives observer recreation');
  log.push(nextStep.pop());assert.equal(deliverEvidence(agent,state,new Set(),createUserMessage),false,'durable transcript dedupes');
  state.task.last_attempt={...state.task.last_attempt,attempt_id:'attempt-2',level:'ui',status:'failed'};state.task.evidence_seq++;
  agent.status='idle';assert.equal(deliverEvidence(agent,state,new Set(),createUserMessage),true);
  assert.deepEqual(calls,['inject','followup']);assert.equal(nextTurn.length,1);
});
test('initial diagnostics enter the real repair conversation once without a validation claim',()=>{
  const state=projectState(nativeState());state.task.last_attempt=null;state.task.change_revision=0;state.task.evidence_seq=1;
  const delivered=[], agent={status:'idle',session:{deriveMessages:()=>[]},inbox:{nextStep:[],nextTurn:[]},followup:message=>delivered.push(message)};
  const seen=new Set();assert.equal(deliverEvidence(agent,state,seen,createUserMessage),true);
  assert.equal(deliverEvidence(agent,state,seen,createUserMessage),false);
  assert.equal(delivered[0].source.attempt_id,'initial');assert.match(delivered[0].content[0].text,/not validation success/);
});
test('validation tool cannot name target, task, UI, authority or run generic command',()=>{
  for(const level of ['syntax','engine'])assert.doesNotThrow(()=>validateArgs('validate_target',{level}));
  for(const args of [{level:'ui'},{level:'engine',task_id:'x'},{level:'syntax',command:'rm'},{}])assert.throws(()=>validateArgs('validate_target',args));
  assert.throws(()=>validateArgs('prepare_ui',{}));assert.throws(()=>validateArgs('validateUi',{}));
});
test('visual snapshot admits four opaque colors and bool only, never authority',()=>{
  const theme={dark:true,colors:{background:'#101010',surface:'#202020',text:'#FFFFFF',primary:'#4488FF'}};
  assert.deepEqual(validateTheme(theme),theme);
  for(const value of [{...theme,grant:'x'},{...theme,dark:'true'},{...theme,colors:{...theme.colors,primary:'url(x)'}},{...theme,colors:{...theme.colors,primary:'#1234'}},{...theme,colors:{...theme.colors,onPrimary:'#ffffff'}}])assert.throws(()=>validateTheme(value));
});
test('native model launch path has no startActivity and user entry binds controlled task attempt',async()=>{
  const bridge=await readFile(new URL('../src/main/java/com/dsh/android/repair/assistant/RepairAgentBridge.kt',import.meta.url),'utf8');
  const launch=bridge.slice(bridge.indexOf('if (method == "launch_target")'),bridge.indexOf('if (method == "validate_target")'));
  assert.match(launch,/user_action_required/);assert.doesNotMatch(launch,/startActivity|launchIntent/);
  const action=bridge.slice(bridge.indexOf('fun validateUi()'),bridge.indexOf('fun onActivityResult'));
  assert.match(action,/"prepare_ui", "\{\}"/);assert.match(action,/activity === owner/);
  assert.match(action,/com\.dsh\.android\.repair\.VALIDATE_UI/);assert.match(action,/putExtra\("task_id", taskId\)/);assert.match(action,/putExtra\("attempt_id", attemptId\)/);
  assert.doesNotMatch(action,/EXTRA_GRANT_ID/);
});
