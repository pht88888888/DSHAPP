import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { exactFunction } from './production-source.mjs';
const source = readFileSync(new URL('../dsh-android-mobile/lib/client.js', import.meta.url), 'utf8');
function harness() {
  let mobile = true;
  class Input {
    constructor() { this.attrs = new Map(); }
    getAttribute(k) { return this.attrs.has(k) ? this.attrs.get(k) : null; }
    setAttribute(k, v) { this.attrs.set(k, String(v)); }
  }
  Object.defineProperty(Input.prototype, 'type', { configurable: true, enumerable: true, get() { return this.getAttribute('type') || 'text'; }, set(v) { this.attrs.set('type', String(v)); } });
  const before = Object.getOwnPropertyDescriptor(Input.prototype, 'type');
  const attribute = Input.prototype.setAttribute;
  const ctx = vm.createContext({ HTMLInputElement: Input, mpIsMobile: () => mobile, Map });
  vm.runInContext(exactFunction(source, 'installPasswordTypeShim'), ctx);
  return { Input, before, attribute, install: () => ctx.installPasswordTypeShim(), mode: v => { mobile = v; } };
}
test('password interception precedes autofocus via both setter APIs and touches no values', () => {
  const h = harness(), off = h.install(), a = new h.Input(), b = new h.Input();
  Object.defineProperty(a, 'value', { get() { throw Error('must not access secrets'); }, set() { throw Error('must not write secrets'); } });
  a.type = 'password'; b.setAttribute('TYPE'.toLowerCase(), 'PASSWORD');
  assert.equal(a.type, 'text'); assert.equal(b.type, 'text');
  off(); assert.equal(a.type, 'password'); assert.equal(b.type, 'password');
  assert.equal(Object.getOwnPropertyDescriptor(h.Input.prototype, 'type').set, h.before.set);
  assert.equal(h.Input.prototype.setAttribute, h.attribute);
});
test('shim is mode guarded and disposal preserves later input or prototype writes', () => {
  const h = harness(), off = h.install(), a = new h.Input(), b = new h.Input();
  a.type = 'password'; a.type = 'email';
  h.mode(false); b.type = 'password'; assert.equal(b.type, 'password');
  const hostSetter = function(v) { this.attrs.set('type', String(v)); };
  Object.defineProperty(h.Input.prototype, 'type', { ...h.before, set: hostSetter });
  const hostAttribute = function(k,v) { this.attrs.set(k,String(v)); };
  h.Input.prototype.setAttribute = hostAttribute;
  off(); off(); assert.equal(a.type, 'email');
  assert.equal(Object.getOwnPropertyDescriptor(h.Input.prototype, 'type').set, hostSetter);
  assert.equal(h.Input.prototype.setAttribute, hostAttribute);
});
test('official mode does not install a password prototype hook', () => {
  const h = harness(); h.mode(false); h.install()();
  assert.equal(Object.getOwnPropertyDescriptor(h.Input.prototype, 'type').set, h.before.set);
  assert.equal(h.Input.prototype.setAttribute, h.attribute);
});
test('credential setup owns the early hook and removes it on mode exit', () => {
  const fn = exactFunction(source, 'setupPlainKeyboardForCredentials');
  assert.ok(fn.indexOf('installPasswordTypeShim()') < fn.indexOf('scan(document)'));
  assert.match(fn, /return \(\) => \{\s*disposeShim\(\);/);
});
test('legacy readiness is initialized even if optional configForms services never arrive', () => {
  let ready = false, syncs = 0;
  const ctx = vm.createContext({ require: () => ({}), mpLayoutReady: false, mpSyncMobileAttr() { syncs++; ready = ctx.mpLayoutReady; }, mpSyncDrawer() {} });
  vm.runInContext(exactFunction(source, 'mpRegisterLayoutControl'), ctx);
  ctx.mpRegisterLayoutControl({ inject() {} });
  assert.equal(ready, true); assert.equal(syncs, 1);
});
test('contribution retains original host inject contract and excludes unrelated local APK features', () => {
  assert.doesNotMatch(source, /mpBubblePanel|mpInstallClipboardBridge|mpInstallBalanceBridge|mpBadgeRefreshConfig|remote\.account/);
  assert.match(source, /exports\.inject = \[\]/);
});
