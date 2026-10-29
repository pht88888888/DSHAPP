import fs from 'node:fs';
import { createRequire } from 'node:module';
let JSDOM=null;
try {
  const require = createRequire(import.meta.url);
  JSDOM = require('jsdom').JSDOM;
} catch (missing) {
  console.log('  ⚠️ 未安装 jsdom，跳过侧栏端到端测试');
  process.exit(0);
}

let pass=0,fail=0;
const T=(n,c,e)=>{c?(pass++,console.log('  ✓',n)):(fail++,console.log('  ✗',n+(e?'  '+e:'')))};

const cli=fs.readFileSync(new URL('../lib/client.js', import.meta.url),'utf8');

/*
 * 端到端：在 jsdom 里真实装载 client.js → apply(ctx) → 造官方侧边栏 DOM
 * → 触发装饰定时器 → 检查真实 DOM 结果。
 *
 * 这是唯一能回答「徽标到底插没插」的办法。
 */
const dom=new JSDOM(`<!doctype html><html><body>
  <div data-sidebar>
    <div role="tree" id="list"></div>
  </div>
</body></html>`, { pretendToBeVisual: true });
const win=dom.window, doc=win.document;

// 必要的浏览器 API 桩（jsdom 缺的）
const MO=class{observe(){}disconnect(){}takeRecords(){return[];}};
if(!win.MutationObserver) win.MutationObserver=MO;
const RO=class{observe(){}disconnect(){}unobserve(){}};
if(!win.ResizeObserver) win.ResizeObserver=RO;
if(!win.IntersectionObserver) win.IntersectionObserver=RO;

let registered=null;
win.__ModuleLoader__={ load:(d)=>{ registered=d; } };

// 官方侧边栏 DOM（严格按 dsh-client-ui-workspace 的 ownRow）
const list=doc.getElementById('list');
function makeRow(key,label,remote){
  const row=doc.createElement('div');
  row.setAttribute('role','treeitem');
  row.setAttribute('data-row-key','workspace:'+key);
  row.setAttribute('tabindex','0');
  // folder slot
  const folder=doc.createElement('span');
  folder.className='YDXeBa_slot YDXeBa_folder';
  const svg1=doc.createElementNS('http://www.w3.org/2000/svg','svg');
  svg1.setAttribute('width','16'); svg1.setAttribute('height','16');
  svg1.setAttribute('viewBox','0 0 16 16');
  const p1=doc.createElementNS('http://www.w3.org/2000/svg','path');
  p1.setAttribute('d','M1 3h5l1.5 2H15v8H1z');
  p1.setAttribute('stroke','currentColor'); p1.setAttribute('fill','none');
  svg1.appendChild(p1); folder.appendChild(svg1); row.appendChild(folder);
  // chevron slot
  const chev=doc.createElement('span');
  chev.className='YDXeBa_slot YDXeBa_chevron';
  const svg2=doc.createElementNS('http://www.w3.org/2000/svg','svg');
  svg2.setAttribute('width','16'); svg2.setAttribute('height','16');
  const p2=doc.createElementNS('http://www.w3.org/2000/svg','path');
  p2.setAttribute('d','M6 4l4 4-4 4');
  svg2.appendChild(p2); chev.appendChild(svg2); row.appendChild(chev);
  // projectText
  const pt=doc.createElement('span'); pt.className='YDXeBa_projectText';
  const ti=doc.createElement('span'); ti.className='YDXeBa_title';
  ti.textContent=(remote?'\u200B':'')+label;
  pt.appendChild(ti); row.appendChild(pt);
  // rowActions
  const ra=doc.createElement('span'); ra.className='YDXeBa_rowActions';
  const b1=doc.createElement('button'); b1.textContent='\u22ef';
  const b2=doc.createElement('button'); b2.textContent='+';
  ra.appendChild(b1); ra.appendChild(b2); row.appendChild(ra);
  list.appendChild(row);
  return row;
}

const rows={
  dsh:      makeRow('k1','dsh',false),
  aliyun:   makeRow('k2','\u963f\u91cc\u4e91e\u5b9e\u4f8b',false),
  uidesign: makeRow('k3','ui-design',false),
  desktop:  makeRow('k4','Desktop',true),
  hk:       makeRow('k5','\u9999\u6e2f\u4e91',true),
  proj:     makeRow('k6','_\u9879\u76ee\u6587\u4ef6\u5939',true),
  def:      makeRow('k7','\u9ed8\u8ba4\u5de5\u4f5c\u533a',false),
};

console.log('  造了', list.children.length, '行，其中 3 行是电脑行（带零宽前缀）');

// h 桩
/*
 * ⚠️ 关键：必须返回**React 元素对象**（{type, props}），不能返回 DOM 节点。
 * elementToDom 读 element.type / element.props；给它 DOM 节点会 return null，
 * 于是 buildBadgeNode 得到空壳 —— 那是我测试桩的错，不是产品 bug。
 */
const h=(tag,props,kids)=>{
  const merged = Object.assign({}, props||{});
  if (kids !== undefined) merged.children = kids;
  return { type: tag, props: merged, $$typeof: Symbol.for('react.element') };
};

const fakeRequire=(n)=>{
  if(n==='react') return { useState:(v)=>[typeof v==='function'?v():v,()=>{}], useRef:(v)=>({current:v}), useEffect:()=>{}, useMemo:(f)=>f(), useCallback:(f)=>f, createElement:h, Fragment:'f' };
  if(n==='react-dom') return { render:()=>{}, flushSync:(f)=>f(), unmountComponentAtNode:()=>{} };
  if(n==='react-dom/client') return { createRoot:()=>({render:()=>{}, unmount:()=>{}}) };
  // primitives：给一个假的图标组件，模拟官方可用
  if(n==='@deepseek-ai/dsh-client-ui-primitives'){
    const Icon=(props)=>h('svg',{width:props.size||16,height:props.size||16,viewBox:'0 0 16 16',fill:'none',stroke:'currentColor','stroke-width':'1'},
      h('path',{d:'M8 1a7 7 0 100 14A7 7 0 008 1z M1 8h14 M8 1c2 2 2 12 0 14'}));
    return { IconGlobeOutlineRegular:Icon, IconFolderCloseRegular:Icon, IconChevronRightOutlineRegular:Icon, IconPlusOutlineRegular:Icon };
  }
  return {};
};

console.log('\n══ 真实装载 ══');
try{
  const fn=new Function('require','window','document','React','h','MutationObserver','IntersectionObserver','ResizeObserver', cli);
  fn(fakeRequire, win, doc, {createElement:h,useState:(v)=>[v,()=>{}],useRef:(v)=>({current:v}),useEffect:()=>{}}, h, MO, RO, RO);
  T('模块装载', true);
}catch(e){
  console.log('  ✗ 装载失败:', e.message); fail++;
}

T('__ModuleLoader__.load 被调', registered!==null);

const effects=[];
const timers=[];
const realSI=win.setInterval;
win.setInterval=(f,ms)=>{ timers.push({f,ms}); return timers.length; };
win.clearInterval=()=>{};
const realST=win.setTimeout;
win.setTimeout=(f,ms)=>{ try{ if(typeof f==='function' && ms<10000) f(); }catch(e){} return 0; };

if(registered){
  try{
    const exp=registered.factory(fakeRequire);
    const ctx={
      get:()=>undefined, remote:null,
      effect:(f)=>{ effects.push(f); return ()=>{}; },
      logger:{warn:()=>{},info:()=>{},error:()=>{}},
      slots:{register:()=>()=>{},inject:()=>{}},
      on:()=>()=>{}, inject:(d,cb)=>{ try{cb(ctx);}catch(e){} },
    };
    exp.apply(ctx);
    T('apply 执行', true);
  }catch(e){ console.log('  ✗ apply:', e.message); fail++; }
}

console.log('  注册', effects.length, '个 effect，', timers.length, '个定时器');
console.log('  __dshRemoteGuardError =', JSON.stringify(win.__dshRemoteGuardError||null));
console.log('  __dshRemoteSidebarError =', JSON.stringify(win.__dshRemoteSidebarError||null));
// 逐个 effect 单独跑，找出谁抛错
for(let i=0;i<effects.length;i++){
  try{ effects[i](); }catch(e){ console.log('  effect#'+i+' 抛错:', e.message); }
}
console.log('  运行 effect 后定时器数:', timers.length, timers.map(t=>t.ms).join(','));
/* 装饰由 effect 直接执行 */

console.log('\n══ 逐个执行 effect（装饰在这里启动）══');
for(const [i,f] of effects.entries()){
  try{ f(); }catch(e){ console.log('  ✗ effect#'+i+' 抛错:', e.message); fail++; }
}

// 直接看 buildBadgeNode 返回什么
console.log('\n══ 直接调用 buildBadgeNode ══');
try{
  if(typeof buildBadgeNode === 'function'){
    const n = buildBadgeNode();
    console.log('  返回:', n===null?'null':(n.nodeName + ' children=' + (n.childNodes?n.childNodes.length:'?')));
    if(n && n.outerHTML) console.log('  HTML:', n.outerHTML.slice(0,150));
  } else {
    console.log('  buildBadgeNode 不在作用域');
  }
}catch(e){ console.log('  抛错:', e.message); }

console.log('\n══ 检查真实 DOM 结果 ══');
const badges=doc.querySelectorAll('[data-dsh-desktop-badge]');
const hidden=doc.querySelectorAll('[data-dsh-folder-hidden]');
console.log('  徽标节点数:', badges.length);
console.log('  被隐藏的 folder 数:', hidden.length);
console.log('  诊断变量:', JSON.stringify(win.__dshRemoteSidebarDiag||null));

T('**3 个电脑行都插了徽标**', badges.length===3, '实际 '+badges.length);
T('**3 个 folder 都被隐藏**', hidden.length===3, '实际 '+hidden.length);
T('非电脑行没被动过',
  rows.dsh.querySelector('[data-dsh-desktop-badge]')===null &&
  rows.aliyun.querySelector('[data-dsh-desktop-badge]')===null);

// 检查徽标内容
if(badges.length>0){
  // 逐行检查每个徽标是否都有 svg（这才是用户看到的）
  for(let bi=0; bi<badges.length; bi++){
    const bb=badges[bi];
    const has=bb.querySelector('svg')!==null;
    const kids=bb.childNodes.length;
    console.log('  徽标#'+bi+': childNodes='+kids+' svg='+has);
  }
  const b=badges[0];
  const svg=b.querySelector('svg');
  console.log('  徽标 HTML 完整:', b.outerHTML);
  console.log('  wrap.childNodes:', b.childNodes.length, '=> 期望 >0');
  console.log('  若为0 说明 icon 是 null/undefined 但没被拦住');
  console.log('  childNodes:', b.childNodes.length);
  for(const c of b.childNodes) console.log('    -', c.nodeName, c.nodeType);
  T('徽标内有 svg', svg!==null);
  if(svg){
    const shapes=svg.querySelectorAll('path,circle,rect,line,polygon');
    T('**svg 里有真实图形元素**', shapes.length>0, '实际 '+shapes.length);
    const pw=svg.getAttribute('width');
    T('svg 有宽高', pw!==null);
  }
}

console.log('\n════════ '+pass+' 通过, '+fail+' 失败 ════════');
process.exit(fail>0?1:0);
