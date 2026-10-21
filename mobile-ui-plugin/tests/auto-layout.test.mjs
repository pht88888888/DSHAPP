import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { exactFunction, exactDeclaration } from './production-source.mjs';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../dsh-android-mobile/lib/client.js',import.meta.url),'utf8');
const policy=[
 ...['mpLayoutMode','mpLayoutReady','mpResolvedLayout','mpRefreshLayout'].map(name=>exactDeclaration(source,name)),
 ...['mpResolveAutoLayout','mpIsMobile'].map(name=>exactFunction(source,name))
].join('\n');
const control=exactFunction(source,'mpRegisterLayoutControl');
function harness(value={},width=1000,height=1000,{defer=false,rejects=0}={}) {
 let snapshot={status:'ready',writable:true,value:{...value}},listeners=new Set(),cleanups=[],calls=[],queue=[];
 const context=vm.createContext({window:{innerWidth:width,innerHeight:height},document:{documentElement:{dataset:{}}},mpIsNarrow:()=>false,mpIsPointerDesktop:()=>true,mpSyncMobileAttr(){},mpSyncDrawer(){},require:()=>({}),Set,console});
 const form={getSnapshot:()=>snapshot,subscribe:fn=>{listeners.add(fn);return()=>listeners.delete(fn);},set(field,value){calls.push([field,value]);const apply=()=>{if(rejects-->0){for(const fn of listeners)fn();return false;}snapshot={...snapshot,value:{...snapshot.value,[field]:value}};for(const fn of listeners)fn();return true;};return defer?new Promise(resolve=>queue.push(()=>resolve(apply()))):Promise.resolve(apply());}};
 const scope={configForms:{get:()=>form},effect:fn=>cleanups.push(fn()),slots:{inject:(n,fn)=>fn(),register(){}}};
 vm.runInContext(policy+control,context);context.mpRegisterLayoutControl({inject:(deps,fn)=>fn(scope)});
 return{context,calls,queue,get actual(){return context.document.documentElement.dataset.dshAndroidLayout;},resize(w,h){context.window.innerWidth=w;context.window.innerHeight=h;vm.runInContext('mpRefreshLayout()',context);},publish(patch){snapshot={...snapshot,value:{...snapshot.value,...patch}};for(const fn of listeners)fn();},dispose(){cleanups.reverse().forEach(fn=>fn?.());},get history(){return snapshot.value.lastLayout;}};
}
const settle=async()=>{for(let n=0;n<8;n++)await Promise.resolve();};
test('first auto startup in band defaults mobile; persisted history restores either layout',()=>{
 assert.equal(harness({layoutMode:'auto'}).actual,'mobile');
 for(const previous of ['mobile','official'])assert.equal(harness({layoutMode:'auto',lastLayout:previous}).actual,previous);
});
test('startup outside thresholds overrides saved history',()=>{
 assert.equal(harness({layoutMode:'auto',lastLayout:'mobile'},1600,900).actual,'official');
 assert.equal(harness({layoutMode:'auto',lastLayout:'official'},600,1000).actual,'mobile');
});
test('strict thresholds and both equal boundaries preserve prior state',()=>{
 const h=harness({layoutMode:'auto',lastLayout:'mobile'});
 for(const [w,t]of [[4,3],[3,4],[1,1]]){h.resize(w,t);assert.equal(h.actual,'mobile');}
 h.resize(4001,3000);assert.equal(h.actual,'official');
 for(const [w,t]of [[4,3],[3,4],[1,1]]){h.resize(w,t);assert.equal(h.actual,'official');}
 h.resize(2999,4000);assert.equal(h.actual,'mobile');
});
test('resize chain uses previous resolved state and ignores pointer type',()=>{
 const h=harness({layoutMode:'auto'});
 for(const [w,t,expected]of [[1600,900,'official'],[1000,1000,'official'],[600,1000,'mobile'],[1000,1000,'mobile']]){h.resize(w,t);assert.equal(h.actual,expected);}
});
test('manual modes lock across all aspect ratios and retain history',async()=>{
 for(const mode of ['mobile','official']){const h=harness({layoutMode:mode});for(const [w,t]of [[1600,900],[500,1000],[1000,1000]]){h.resize(w,t);assert.equal(h.actual,mode);}await settle();assert.equal(h.history,mode);}
});
test('invalid or transient zero window measurements retain current layout',()=>{
 const h=harness({layoutMode:'auto',lastLayout:'official'});
 for(const [w,t]of [[0,0],[1000,0],[-1,1000],[NaN,1000],[1000,Infinity]]){h.resize(w,t);assert.equal(h.actual,'official');}
});
test('history echoes during rapid resize never revert actual layout; latest write wins',async()=>{
 const h=harness({layoutMode:'auto'},1000,1000,{defer:true});
 h.resize(1600,900);h.resize(600,1000);h.resize(1600,900);h.resize(1000,1000);assert.equal(h.actual,'official');
 h.queue.shift()();await settle();assert.equal(h.actual,'official');
 while(h.queue.length){h.queue.shift()();await settle();}
 assert.equal(h.history,'official');assert.equal(h.calls.length,2);
});
test('persisted effective history restores in-band startup after reload',async()=>{
 const h=harness({layoutMode:'auto'},1600,900);await settle();
 const reloaded=harness({layoutMode:'auto',lastLayout:h.history});assert.equal(reloaded.actual,'official');
});
test('disposal stops callbacks and queued history writes',async()=>{
 const h=harness({layoutMode:'auto'},1000,1000,{defer:true});h.resize(1600,900);h.dispose();
 assert.equal(vm.runInContext('mpRefreshLayout',h.context),null);h.queue.shift()();await settle();assert.equal(h.calls.length,1);
});
test('one rejected history write recovers and persists the latest actual layout',async()=>{
 const h=harness({layoutMode:'auto',lastLayout:'mobile'},1600,900,{rejects:1});await settle();
 assert.equal(h.actual,'official');assert.equal(h.history,'official');assert.equal(h.calls.length,2);
});
test('persistent write rejection is bounded and a later event can retry',async()=>{
 const h=harness({layoutMode:'auto',lastLayout:'mobile'},1600,900,{rejects:2});await settle();assert.equal(h.calls.length,2);assert.equal(h.history,'mobile');
 h.resize(1000,1000);await settle();assert.equal(h.history,'official');assert.equal(h.calls.length,3);
});
test('existing shell window resize invokes preference refresh; visualViewport does not drive auto policy',()=>{
 assert.match(source,/mpShellResize = \(\) => \{\s*if \(mpRefreshLayout\) mpRefreshLayout\(\);/);
 assert.doesNotMatch(control,/visualViewport/);
});
