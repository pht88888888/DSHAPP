import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import { exactFunction } from './production-source.mjs';
const src=readFileSync(new URL('../dsh-android-mobile/lib/client.js',import.meta.url),'utf8');
const fn=exactFunction(src,'mpRegisterLayoutControl');
function harness(initial={status:'ready',value:{layoutMode:'official'},writable:true}){
 let snapshot=initial, result=true, calls=[], subscribers=new Set(), slots=[], cleanups=[], effects=[], hookState=[], i=0;
 const React={
  createElement:(type,props,...children)=>({type,props:props||{},children}),
  useState:init=>{const k=i++;if(!(k in hookState))hookState[k]=typeof init==='function'?init():init;return[hookState[k],v=>{hookState[k]=v;}];},
  useEffect:fn=>effects.push(fn)
 };
 const form={getSnapshot:()=>snapshot,subscribe:cb=>{subscribers.add(cb);return()=>subscribers.delete(cb);},set:async(field,value)=>{calls.push([field,value]);if(result instanceof Error)throw result;if(result){snapshot={...snapshot,value:{...snapshot.value,[field]:value}};for(const cb of subscribers)cb();}return result;}};
 const scope={configForms:{get:id=>{assert.equal(id,'dsh-android-layout-preferences');return form;}},effect:fn=>{cleanups.push(fn());},slots:{inject:(name,cb)=>cb(),register:(opts,component)=>{slots.push({opts,component});}}};
 const context=vm.createContext({require:id=>{assert.equal(id,'react');return React;},window:{innerWidth:1111,innerHeight:644},mpResolvedLayout:null,mpRefreshLayout:null,mpResolveAutoLayout:(w,h,p)=>w*3>h*4?"official":w*4<h*3?"mobile":p||"mobile",document:{documentElement:{dataset:{}}},mpLayoutMode:null,mpLayoutReady:false,mpIsMobile:()=>vm.runInContext('mpLayoutMode === "mobile"',context),mpSyncMobileAttr:()=>calls.push(['sync']),mpSyncDrawer:()=>{},Set,console});
 vm.runInContext(fn,context);context.mpRegisterLayoutControl({inject:(services,cb)=>{assert.deepEqual(Array.from(services),['slots','configForms','locale']);cb(scope);}});
 return{context,calls,slots,hookState,render(props){i=0;const v=slots.find(s=>s.opts.name==='sidebar.footer.action').component(props||{});for(const ef of effects.splice(0))cleanups.push(ef());return v;},setResult:r=>result=r,publish:s=>{snapshot=s;for(const cb of subscribers)cb();},dispose:()=>{for(const f of cleanups.reverse())f?.();},subscribers};
}
function buttons(v){if(!v||typeof v!=='object')return[];return[(v.type==='button'?v:null),...v.children.flat(Infinity).flatMap(buttons)].filter(Boolean);}
test('controls use additive official footer/settings slots without replacing account/skin slots',()=>{const h=harness();assert.deepEqual(h.slots.map(s=>s.opts.name),['sidebar.footer.action','settings.section']);assert.equal(h.slots[0].opts.id,'dsh-android-layout');});
test('host-ready snapshots apply mode, unavailable snapshots leave current mode alone',()=>{const h=harness();assert.equal(vm.runInContext('mpLayoutMode',h.context),'official');h.publish({status:'ready',value:{layoutMode:'mobile'},writable:true});assert.equal(h.context.document.documentElement.dataset.dshAndroidLayout,'mobile');h.publish({status:'unavailable',writable:false});assert.equal(vm.runInContext('mpLayoutMode',h.context),'mobile');h.dispose();assert.equal(h.subscribers.size,0);});
test('loading config disables controls but leaves the legacy adapter available',()=>{const h=harness({status:'loading',writable:false});assert.equal(vm.runInContext('mpLayoutReady',h.context),true);assert.equal(vm.runInContext('mpLayoutMode',h.context),null);assert.ok(buttons(h.render()).every(b=>b.props.disabled));});
test('missing preferences never disable the existing narrow-screen adapter',()=>{const h=harness({status:'unavailable',writable:false});assert.equal(vm.runInContext('mpLayoutReady',h.context),true);assert.equal(vm.runInContext('mpLayoutMode',h.context),null);assert.ok(h.calls.some(c=>c[0]==='sync'));assert.ok(buttons(h.render()).every(b=>b.props.disabled));h.publish({status:'ready',value:{layoutMode:'mobile'},writable:true});assert.equal(vm.runInContext('mpLayoutMode',h.context),'mobile');});
test('wide choices persist one scalar through official form and accepted snapshot drives mode',async()=>{const h=harness();const b=buttons(h.render()).find(b=>b.props['data-dsh-layout-choice']==='mobile');await b.props.onClick();assert.ok(h.calls.some(c=>c[0]==='layoutMode'&&c[1]==='mobile'));assert.equal(vm.runInContext('mpLayoutMode',h.context),'mobile');});
test('rejected writes do not optimistically change the layout and report an error',async()=>{const h=harness();h.setResult(false);await buttons(h.render()).find(b=>b.props['data-dsh-layout-choice']==='mobile').props.onClick();assert.equal(vm.runInContext('mpLayoutMode',h.context),'official');assert.match(h.hookState[2],/保存失败/);});
test('transport errors keep the old layout and release the shared saving lock',async()=>{const h=harness();h.setResult(new Error('offline'));await buttons(h.render()).find(b=>b.props['data-dsh-layout-choice']==='mobile').props.onClick();assert.equal(vm.runInContext('mpLayoutMode',h.context),'official');assert.match(h.hookState[2],/保存失败/);h.setResult(true);await buttons(h.render()).find(b=>b.props['data-dsh-layout-choice']==='mobile').props.onClick();assert.equal(vm.runInContext('mpLayoutMode',h.context),'mobile');});
test('collapsed official sidebar retains an accessible toggle without account DOM selectors',async()=>{const h=harness();const b=h.render({wide:false});assert.equal(b.type,'button');assert.match(b.props['aria-label'],/切换自动模式/);await b.props.onClick();assert.equal(vm.runInContext('mpLayoutMode',h.context),'auto');});
