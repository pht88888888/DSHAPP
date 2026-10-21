import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { exactFunction, exactDeclaration } from './production-source.mjs';

// Read production text, never reimplement the functions under test or boot the
// complete plugin. This suite needs only Node; it installs no DOM dependencies.
const clientURL = new URL('../dsh-android-mobile/lib/client.js', import.meta.url);
const client = readFileSync(clientURL, 'utf8');
function fakeClock() {
  let nextID = 1;
  const pending = new Map();
  const callbacks = new Map();
  const cleared = [];
  return {
    pending, callbacks, cleared,
    setTimeout(fn, ms = 0) {
      const id = nextID++;
      pending.set(id, { fn, ms });
      callbacks.set(id, fn);
      return id;
    },
    clearTimeout(id) { cleared.push(id); pending.delete(id); },
    fire(id) {
      assert.ok(pending.has(id), `Timer ${id} must still be pending`);
      const { fn } = pending.get(id);
      pending.delete(id);
      fn();
    },
    // Simulate an already queued browser task, even after clearTimeout.
    fireStale(id) {
      assert.ok(callbacks.has(id), `Timer ${id} must have been scheduled`);
      callbacks.get(id)();
    }
  };
}

class MockStyle {
  #properties = new Map();
  getPropertyValue(key) { return this.#properties.get(key)?.[0] ?? ''; }
  getPropertyPriority(key) { return this.#properties.get(key)?.[1] ?? ''; }
  setProperty(key, value, priority = '') {
    if (value === '') this.removeProperty(key);
    else this.#properties.set(key, [String(value), priority]);
  }
  removeProperty(key) {
    const before = this.getPropertyValue(key);
    this.#properties.delete(key);
    return before;
  }
  get cssText() {
    return [...this.#properties].map(([key, [value, priority]]) =>
      `${key}: ${value}${priority ? ` !${priority}` : ''};`).join(' ');
  }
  set cssText(value) {
    this.#properties.clear();
    for (const declaration of value.split(';')) {
      const colon = declaration.indexOf(':');
      if (colon < 0) continue;
      const key = declaration.slice(0, colon).trim();
      const raw = declaration.slice(colon + 1).trim();
      const important = /\s*!important\s*$/.test(raw);
      this.setProperty(key, raw.replace(/\s*!important\s*$/, ''), important ? 'important' : '');
    }
  }
}

class MockElement {
  constructor(classes = '') {
    this.nodeType = 1;
    this.isConnected = true;
    this.style = new MockStyle();
    this.attributes = new Map();
    this.dataset = {};
    this.childNodes = [];
    this.listeners = new Map();
    this.removedListeners = [];
    const tokens = new Set(classes.split(/\s+/).filter(Boolean));
    this.classList = {
      add: (...names) => names.forEach(name => tokens.add(name)),
      remove: (...names) => names.forEach(name => tokens.delete(name)),
      contains: name => tokens.has(name),
      [Symbol.iterator]: () => tokens[Symbol.iterator]()
    };
  }
  get children() { return this.childNodes.filter(node => node.nodeType === 1); }
  get textContent() { return this.childNodes.map(node => node.textContent ?? node.nodeValue).join(''); }
  set textContent(value) { this.childNodes = [{ nodeType: 3, nodeValue: String(value) }]; }
  getAttribute(key) { return this.attributes.has(key) ? this.attributes.get(key) : null; }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  removeAttribute(key) { this.attributes.delete(key); }
  hasAttribute(key) { return this.attributes.has(key); }
  querySelectorAll(selector) {
    assert.equal(selector, '[class]');
    const result = [];
    for (const child of this.children) {
      if ([...child.classList].length) result.push(child);
      result.push(...child.querySelectorAll(selector));
    }
    return result;
  }
  appendChild(node) { this.childNodes.push(node); node.parentNode = this; return node; }
  remove() {
    if (this.parentNode) this.parentNode.childNodes = this.parentNode.childNodes.filter(node => node !== this);
    this.isConnected = false;
  }
  addEventListener(type, handler, options) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push({ handler, options });
  }
  removeEventListener(type, handler, options) {
    this.removedListeners.push({ type, handler, options });
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter(row => row.handler !== handler));
  }
  emit(type, event = {}) {
    for (const { handler } of [...(this.listeners.get(type) ?? [])]) handler(event);
  }
}

function makeVM(source, globals = {}) {
  const context = vm.createContext({ console: { log() {}, warn() {} }, ...globals });
  vm.runInContext(source, context, { filename: 'layout.production-extract.js' });
  return { context, run: code => vm.runInContext(code, context) };
}

const aliasDeclarations = [
  'MP_LEGACY_HASHES', 'mpPairIndex', 'mpAliasDirty', 'mpAliasSig',
  'mpAliasOwned', 'mpAliasTimer', 'mpAliasEpoch', 'mpOfficialSeen',
  'MP_ALIAS_WARMUP', 'mpAliasRound', 'mpAliasObserver'
];
const aliasFunctions = [
  'mpCollectCurrentClasses', 'mpDeriveAliasMap', 'mpRefreshAliasMap',
  'mpPruneStaleAliases', 'mpAliasElement', 'mpAliasTree',
  'mpInstallAliasBridge', 'mpTeardownAliasBridge'
];

function aliasHarness() {
  const clock = fakeClock();
  const observers = [];
  const html = new MockElement();
  const body = html.appendChild(new MockElement());
  const document = {
    documentElement: html, body, styleSheets: [],
    querySelectorAll: selector => html.querySelectorAll(selector)
  };
  class MutationObserver {
    constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
    observe(target, options) { this.target = target; this.options = options; }
    disconnect() { this.disconnected = true; }
    deliver(records) { this.callback(records); }
  }
  const api = makeVM([
    ...aliasDeclarations.map(name => exactDeclaration(client, name)),
    ...aliasFunctions.map(name => exactFunction(client, name))
  ].join('\n'), {
    document, MutationObserver, mobile: true,
    mpIsMobile: () => api.context.mobile,
    setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout
  });
  return { ...api, clock, observers, document, body,
    seed(pairs = [['FreshHash_frame', 'pI_x6G_frame']]) {
      api.context.seedPairs = pairs;
      api.run('mpPairIndex = new Map(seedPairs)');
    },
    add(classes) { return body.appendChild(new MockElement(classes)); }
  };
}

function scopeHarness() {
  const clock = fakeClock();
  const api = makeVM(exactFunction(client, 'mpCreateMobileScope'), {
    mobile: true, mpIsMobile: () => api.context.mobile,
    setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout
  });
  return { ...api, clock, scope: api.run('mpCreateMobileScope()') };
}

const stylePair = (element, property) => [
  element.style.getPropertyValue(property), element.style.getPropertyPriority(property)
];

test('alias ownership is per element/token: never remove a pre-existing native token', () => {
  const h = aliasHarness();
  const nativeOnly = h.add('pI_x6G_frame');
  const nativeAndSource = h.add('FreshHash_frame pI_x6G_frame');
  const injected = h.add('FreshHash_frame hostClass');
  h.seed();
  for (const element of [nativeOnly, nativeAndSource, injected]) {
    h.context.element = element;
    h.run('mpAliasElement(element)');
  }
  assert.equal(h.run('mpAliasOwned.size'), 1);
  assert.equal(injected.classList.contains('pI_x6G_frame'), true);
  h.run('mpTeardownAliasBridge()');
  assert.equal(nativeOnly.classList.contains('pI_x6G_frame'), true);
  assert.equal(nativeAndSource.classList.contains('pI_x6G_frame'), true);
  assert.equal(injected.classList.contains('pI_x6G_frame'), false);
  assert.equal(injected.classList.contains('FreshHash_frame'), true);
  assert.equal(injected.classList.contains('hostClass'), true);
  assert.equal(h.run('mpAliasOwned.size'), 0);
});

test('class collection filters only injected tokens, not same-named native tokens elsewhere', () => {
  const h = aliasHarness();
  const injected = h.add('FreshHash_frame');
  h.seed();
  h.context.element = injected;
  h.run('mpAliasElement(element)');
  assert.equal(h.run('!!mpCollectCurrentClasses().byHash.pI_x6G'), false);
  const native = h.add('pI_x6G_frame');
  assert.equal(h.run('mpCollectCurrentClasses().byHash.pI_x6G.frame'), true);
  assert.equal(native.classList.contains('pI_x6G_frame'), true);
});

test('prune removes only obsolete injected aliases and preserves other tokens and valid ownership', () => {
  const h = aliasHarness();
  const valid = h.add('FreshHash_frame');
  const stale = h.add('FreshHash_sidebarCol hostClass');
  const native = h.add('pI_x6G_sidebarCol');
  h.seed([
    ['FreshHash_frame', 'pI_x6G_frame'],
    ['FreshHash_sidebarCol', 'pI_x6G_sidebarCol']
  ]);
  h.run('mpAliasTree(document.body)');
  h.seed();
  h.run('mpPruneStaleAliases()');
  assert.equal(valid.classList.contains('pI_x6G_frame'), true);
  assert.equal(stale.classList.contains('pI_x6G_sidebarCol'), false);
  assert.equal(native.classList.contains('pI_x6G_sidebarCol'), true);
  assert.equal(stale.classList.contains('FreshHash_sidebarCol'), true);
  assert.equal(stale.classList.contains('hostClass'), true);
  assert.equal(h.run('mpAliasOwned.size'), 1);
});

test('an official stylesheet adopting an injected token transfers ownership without deletion', () => {
  const h = aliasHarness();
  const element = h.add('FreshHash_frame');
  h.seed();
  h.run('mpAliasTree(document.body)');
  h.document.styleSheets.push({ cssRules: [{ selectorText: '.pI_x6G_frame' }] });
  h.run('mpCollectCurrentClasses(); mpPruneStaleAliases(true)');
  assert.equal(element.classList.contains('pI_x6G_frame'), true);
  assert.equal(h.run('mpAliasOwned.size'), 0);
  h.run('mpTeardownAliasBridge()');
  assert.equal(element.classList.contains('pI_x6G_frame'), true);
});

test('prune drops owned aliases when their source class disappears or element disconnects', () => {
  const h = aliasHarness();
  const sourceGone = h.add('FreshHash_frame');
  const detached = h.add('FreshHash_frame');
  h.seed();
  h.run('mpAliasTree(document.body)');
  sourceGone.classList.remove('FreshHash_frame');
  detached.isConnected = false;
  h.run('mpPruneStaleAliases()');
  assert.equal(sourceGone.classList.contains('pI_x6G_frame'), false);
  assert.equal(detached.classList.contains('pI_x6G_frame'), false);
  assert.equal(h.run('mpAliasOwned.size'), 0);
});

test('class mutation cleans stale aliases immediately even when mapping signature does not change', () => {
  const h = aliasHarness();
  const element = h.add('FreshHash_frame');
  h.add('FreshHash_sidebarCol FreshHash_centerCol FreshHash_rightbarCol');
  h.run('mpAliasRound = MP_ALIAS_WARMUP + 1; mpInstallAliasBridge()');
  assert.equal(element.classList.contains('pI_x6G_frame'), true, 'fixture must derive the real alias map');
  const signature = h.run('mpAliasSig');
  element.classList.remove('FreshHash_frame');
  h.observers[0].deliver([{ type: 'attributes', attributeName: 'class', target: element }]);
  assert.equal(h.run('mpAliasSig'), signature);
  assert.equal(element.classList.contains('pI_x6G_frame'), false, 'source-less injected alias must not survive a class update');
});

test('leaving mobile mode cancels retry; an already queued retry/observer cannot act', () => {
  const h = aliasHarness();
  h.add('FreshHash_frame FreshHash_sidebarCol');
  h.run('mpInstallAliasBridge()');
  const timer = h.run('mpAliasTimer');
  const observer = h.observers[0];
  assert.equal(h.clock.pending.size, 1);
  // Exercise the production mode-exit path, not merely the teardown helper.
  h.document.documentElement.setAttribute('data-mobile', '');
  Object.assign(h.context, {
    mpMobileScope: null,
    mpSyncHeaderCenter() {}, mpSetDrawerOpen() {}, mpTeardownMobile() {}
  });
  h.run(exactFunction(client, 'mpSyncMobileAttr'));
  h.context.mobile = false;
  h.run('mpSyncMobileAttr()');
  assert.equal(h.document.documentElement.hasAttribute('data-mobile'), false);
  assert.equal(h.clock.pending.size, 0);
  assert.ok(h.clock.cleared.includes(timer));
  assert.equal(observer.disconnected, true);
  assert.equal(h.run('mpAliasTimer'), null);
  const round = h.run('mpAliasRound');
  h.clock.fireStale(timer);
  observer.deliver([{ type: 'childList', addedNodes: [h.add('FreshHash_frame')] }]);
  assert.equal(h.run('mpAliasRound'), round);
  assert.equal(h.clock.pending.size, 0);
  assert.equal(h.run('mpAliasOwned.size'), 0);
});

test('stale retry epochs cannot execute after exit and re-entry, or clobber the new timer', () => {
  const h = aliasHarness();
  h.run('mpInstallAliasBridge()');
  const oldTimer = h.run('mpAliasTimer');
  const oldObserver = h.observers[0];
  h.run('mpTeardownAliasBridge(); mpInstallAliasBridge()');
  const currentTimer = h.run('mpAliasTimer');
  assert.notEqual(currentTimer, oldTimer);
  const round = h.run('mpAliasRound');
  const epoch = h.run('mpAliasEpoch');
  h.seed();
  const staleTarget = h.add('FreshHash_frame');
  h.clock.fireStale(oldTimer);
  oldObserver.deliver([{ type: 'attributes', target: staleTarget }]);
  assert.equal(staleTarget.classList.contains('pI_x6G_frame'), false, 'old observer must not use the new epoch alias map');
  assert.equal(h.run('mpAliasRound'), round);
  assert.equal(h.run('mpAliasEpoch'), epoch);
  assert.equal(h.run('mpAliasTimer'), currentTimer);
  assert.deepEqual([...h.clock.pending.keys()], [currentTimer]);
  h.clock.fire(currentTimer);
  assert.equal(h.run('mpAliasRound'), round + 1);
  assert.equal(h.clock.pending.size, 1, 'one live retry chain, not overlapping chains');
});

test('repeated alias install creates only one observer and one retry chain', () => {
  const h = aliasHarness();
  h.run('mpInstallAliasBridge()');
  const timer = h.run('mpAliasTimer');
  const epoch = h.run('mpAliasEpoch');
  h.run('mpInstallAliasBridge(); mpInstallAliasBridge()');
  assert.equal(h.observers.length, 1);
  assert.equal(h.clock.pending.size, 1);
  assert.equal(h.run('mpAliasTimer'), timer);
  assert.equal(h.run('mpAliasEpoch'), epoch);
  h.clock.fire(timer);
  const next = h.run('mpAliasTimer');
  h.run('mpInstallAliasBridge()');
  assert.deepEqual([...h.clock.pending.keys()], [next]);
  assert.equal(h.observers.length, 1);
});

test('scope restores individual style values/priorities, not a whole stale cssText snapshot', () => {
  const { scope } = scopeHarness();
  const element = new MockElement();
  element.style.setProperty('margin-left', '12px', 'important');
  element.style.setProperty('color', 'red');
  scope.style(element, 'marginLeft', '0px');
  scope.style(element, 'height', '40px', 'important');
  scope.style(element, '--mobile-inset', '8px');
  element.style.setProperty('color', 'blue', 'important');
  element.style.setProperty('display', 'grid');
  scope.dispose();
  assert.deepEqual(stylePair(element, 'margin-left'), ['12px', 'important']);
  assert.deepEqual(stylePair(element, 'height'), ['', '']);
  assert.deepEqual(stylePair(element, '--mobile-inset'), ['', '']);
  assert.deepEqual(stylePair(element, 'color'), ['blue', 'important']);
  assert.deepEqual(stylePair(element, 'display'), ['grid', '']);
});

test('scope does not overwrite later host writes to a touched style value or priority', () => {
  const { scope } = scopeHarness();
  const element = new MockElement();
  element.style.setProperty('width', '20px');
  element.style.setProperty('height', '10px');
  scope.style(element, 'width', '80px', 'important');
  scope.style(element, 'height', '40px');
  element.style.setProperty('width', '120px');
  element.style.setProperty('height', '40px', 'important');
  scope.dispose();
  assert.deepEqual(stylePair(element, 'width'), ['120px', '']);
  assert.deepEqual(stylePair(element, 'height'), ['40px', 'important']);
});

test('scope repeated writes restore the latest intervening host style, including its priority', () => {
  const { scope } = scopeHarness();
  const element = new MockElement();
  element.style.setProperty('width', '20px');
  scope.style(element, 'width', '80px');
  scope.style(element, 'width', '90px');
  element.style.setProperty('width', '120px', 'important');
  scope.style(element, 'width', '100px');
  scope.dispose();
  assert.deepEqual(stylePair(element, 'width'), ['120px', 'important']);
});

test('scope attributes preserve null (absent), remove with null, and respect later host writes', () => {
  const { scope } = scopeHarness();
  const element = new MockElement();
  element.setAttribute('title', 'official');
  element.setAttribute('aria-label', 'host');
  scope.attr(element, 'data-mobile-test', 'on');
  scope.attr(element, 'title', null);
  scope.attr(element, 'aria-label', 'mobile');
  assert.equal(element.getAttribute('title'), null);
  element.setAttribute('aria-label', 'host later');
  scope.dispose();
  assert.equal(element.getAttribute('data-mobile-test'), null);
  assert.equal(element.hasAttribute('data-mobile-test'), false, 'must not stringify null');
  assert.equal(element.getAttribute('title'), 'official');
  assert.equal(element.getAttribute('aria-label'), 'host later');
});

test('scope repeated attribute writes restore an intervening host removal as null', () => {
  const { scope } = scopeHarness();
  const element = new MockElement();
  element.setAttribute('title', 'official');
  scope.attr(element, 'title', 'mobile');
  element.removeAttribute('title');
  scope.attr(element, 'title', 'mobile again');
  scope.dispose();
  assert.equal(element.getAttribute('title'), null);
});

test('scope text never replaces a host subtree, multi-node content, or an empty element', () => {
  const { scope } = scopeHarness();
  const parent = new MockElement();
  const child = parent.appendChild(new MockElement());
  child.textContent = 'official child';
  scope.text(parent, 'short');
  assert.equal(parent.children[0], child);
  assert.equal(child.textContent, 'official child');
  const multiple = new MockElement();
  const first = { nodeType: 3, nodeValue: 'first' };
  const second = { nodeType: 3, nodeValue: 'second' };
  multiple.childNodes = [first, second];
  scope.text(multiple, 'short');
  assert.deepEqual(multiple.childNodes, [first, second]);
  const empty = new MockElement();
  scope.text(empty, 'short');
  assert.equal(empty.childNodes.length, 0);
  scope.dispose();
  assert.equal(parent.children[0], child);
});

test('scope text edits the existing single text node and restores only still-owned values', () => {
  const { scope } = scopeHarness();
  const element = new MockElement();
  element.textContent = 'official label';
  const node = element.childNodes[0];
  scope.text(element, 'short');
  assert.equal(element.childNodes[0], node);
  assert.equal(node.nodeValue, 'short');
  scope.dispose();
  assert.equal(element.childNodes[0], node);
  assert.equal(node.nodeValue, 'official label');
  const other = scopeHarness();
  other.scope.text(element, 'mobile again');
  node.nodeValue = 'host later';
  other.scope.dispose();
  assert.equal(node.nodeValue, 'host later');
});

test('scope exit cancels all timers and rejects stale callbacks and new scheduling', () => {
  const { scope, clock } = scopeHarness();
  let calls = 0;
  const first = scope.defer(() => calls++, 5);
  const second = scope.defer(() => calls++, 10);
  assert.equal(clock.pending.size, 2);
  scope.dispose();
  assert.equal(scope.active, false);
  assert.equal(clock.pending.size, 0);
  assert.ok(clock.cleared.includes(first));
  assert.ok(clock.cleared.includes(second));
  clock.fireStale(first);
  clock.fireStale(second);
  assert.equal(calls, 0);
  assert.equal(scope.defer(() => calls++), null);
  assert.equal(clock.pending.size, 0);
  scope.dispose();
  assert.equal(clock.cleared.length, 2, 'disposal must be idempotent');
});

test('scope timers run once in mobile mode and are guarded if mode has already changed', () => {
  const h = scopeHarness();
  let calls = 0;
  h.clock.fire(h.scope.defer(() => calls++, 20));
  assert.equal(calls, 1);
  const timer = h.scope.defer(() => calls++, 20);
  h.context.mobile = false;
  h.clock.fire(timer);
  assert.equal(calls, 1);
  h.scope.dispose();
  assert.equal(h.clock.cleared.length, 0, 'already fired timers must be removed from scope bookkeeping');
});

test('scope listeners are mode guarded and removed with original identity/options on exit', () => {
  const h = scopeHarness();
  const element = new MockElement();
  const options = { capture: true, passive: true };
  const event = { detail: 7 };
  const received = [];
  h.scope.listen(element, 'click', value => received.push(value), options);
  const handler = element.listeners.get('click')[0].handler;
  element.emit('click', event);
  assert.deepEqual(received, [event]);
  h.context.mobile = false;
  element.emit('click', event);
  assert.equal(received.length, 1);
  h.context.mobile = true;
  h.scope.dispose();
  assert.equal(element.listeners.get('click').length, 0);
  assert.equal(element.removedListeners[0].handler, handler);
  assert.equal(element.removedListeners[0].options, options);
  handler(event);
  element.emit('click', event);
  assert.equal(received.length, 1, 'a queued listener must also become inert');
  h.scope.dispose();
  assert.equal(element.removedListeners.length, 1);
});

test('scope disposes owned nodes and custom cleanups once in reverse order, even if one throws', () => {
  const { scope } = scopeHarness();
  const parent = new MockElement();
  const node = parent.appendChild(new MockElement());
  const order = [];
  assert.equal(scope.own(node), node);
  scope.add(() => order.push('first'));
  scope.add(() => { order.push('throwing'); throw new Error('expected cleanup test'); });
  scope.add(() => order.push('last'));
  scope.dispose();
  scope.dispose();
  assert.deepEqual(order, ['last', 'throwing', 'first']);
  assert.equal(parent.children.length, 0);
  assert.equal(node.isConnected, false);
});

function layoutHarness() {
  let pointer = false;
  const window = {
    innerWidth: 400,
    matchMedia: () => ({ get matches() { return pointer; } })
  };
  const declarations = ['mpLayoutMode', 'mpLayoutReady'].map(name => exactDeclaration(client, name));
  const functions = ['mpIsNarrow', 'mpIsPointerDesktop', 'mpIsMobile'].map(name => exactFunction(client, name));
  // The cached media-query variable is an uninitialized production declaration.
  const pointerDeclaration = client.match(/\b(?:var|let) mpDesktopMq\s*;/)?.[0];
  assert.ok(pointerDeclaration, 'Missing production mpDesktopMq declaration');
  const api = makeVM([
    exactDeclaration(client, 'MP_NARROW'), pointerDeclaration,
    ...declarations, ...functions
  ].join('\n'), { window });
  return { ...api, window, setPointer(value) { pointer = value; } };
}

test('layout readiness gate prevents mobile mutation before preference initialization', () => {
  const h = layoutHarness();
  h.run('mpLayoutMode = "mobile"; mpLayoutReady = false');
  assert.equal(h.run('mpIsMobile()'), false);
  h.run('mpLayoutReady = true');
  assert.equal(h.run('mpIsMobile()'), true);
});

test('manual mobile/official modes stay locked across width and pointer changes', () => {
  const h = layoutHarness();
  h.run('mpLayoutReady = true');
  for (const mode of ['mobile', 'official']) {
    h.context.mode = mode;
    h.run('mpLayoutMode = mode');
    for (const width of [320, 767, 768, 1024, 2560]) {
      for (const pointer of [false, true]) {
        h.window.innerWidth = width;
        h.setPointer(pointer);
        assert.equal(h.run('mpIsMobile()'), mode === 'mobile', `${mode}, width=${width}, fine pointer=${pointer}`);
      }
    }
  }
});

test('drawer mask shares the drawer stacking context, without moving host nodes', () => {
  const attached=[];const body={appendChild:n=>attached.push(['body',n])};
  const parent={appendChild:n=>attached.push(['drawer-parent',n])};
  const document={body,querySelector:()=>null,createElement:()=>({addEventListener(){}})};
  const context=vm.createContext({document,mpSidebarCol:()=>({parentElement:parent}),mpMobileOwn:n=>n,mpCloseDrawer(){}});
  vm.runInContext(exactFunction(client,'mpMask'),context);
  const mask=vm.runInContext('mpMask()',context);
  assert.equal(mask.className,'mp-drawer-mask');assert.equal(attached.length,1);assert.equal(attached[0][0],'drawer-parent');
});

test('unset manual preference retains legacy narrow/touch policy and exact width boundary', () => {
  const h = layoutHarness();
  h.run('mpLayoutReady = true; mpLayoutMode = null');
  const narrow = h.run('MP_NARROW');
  h.window.innerWidth = narrow - 1;
  h.setPointer(false);
  assert.equal(h.run('mpIsMobile()'), true);
  h.setPointer(true);
  assert.equal(h.run('mpIsMobile()'), false);
  h.setPointer(false);
  h.window.innerWidth = narrow;
  assert.equal(h.run('mpIsMobile()'), false);
  h.window.innerWidth = narrow + 1;
  assert.equal(h.run('mpIsMobile()'), false);
});
