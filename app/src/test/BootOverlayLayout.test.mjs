import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// Source-structure regression checks only, not Android measurement/device acceptance.
// Parse real XML with the standard library rather than matching parentage with regex.
const layout = new URL('../main/res/layout/activity_main.xml', import.meta.url);
const parsed = spawnSync('python3', ['-c', `
import json, sys, xml.etree.ElementTree as ET
prefix = '{http://schemas.android.com/apk/res/android}'
def convert(element):
    return {
        'tag': element.tag,
        'attrs': {key.removeprefix(prefix): value for key, value in element.attrib.items()},
        'children': [convert(child) for child in element],
    }
print(json.dumps(convert(ET.parse(sys.argv[1]).getroot())))
`, layout.pathname], { encoding: 'utf8' });
assert.equal(parsed.status, 0, `Layout must be valid XML: ${parsed.stderr}`);
const root = JSON.parse(parsed.stdout);
const byId = new Map();
const parents = new Map();
function index(node, parent) {
  parents.set(node, parent);
  if (node.attrs.id) {
    const id = node.attrs.id.replace('@+id/', '');
    assert.ok(!byId.has(id), `Duplicate view ID: ${id}`);
    byId.set(id, node);
  }
  for (const child of node.children) index(child, node);
}
index(root, null);
function view(id) {
  assert.ok(byId.has(id), `Missing original/native view ID: ${id}`);
  return byId.get(id);
}
function isWithin(node, ancestor) {
  for (let current = node; current; current = parents.get(current)) {
    if (current === ancestor) return true;
  }
  return false;
}
const nativeActions = ['overlayExport', 'overlayImport', 'overlayLogs', 'overlayRepair'];

test('native startup aesthetic and all existing binding IDs are retained', () => {
  for (const id of [
    'webContainer', 'overlay', 'bootAnim', 'overlayTitle', 'overlayText',
    'overlayHint', 'overlayRetry', 'overlayShowPage', ...nativeActions,
  ]) view(id);
  assert.equal(view('overlay').attrs.background, '@drawable/boot_background');
  assert.equal(view('bootAnim').tag, 'com.dsh.android.ui.BootAnimationView');
  assert.equal(view('bootAnim').attrs.layout_width, '190dp');
  assert.equal(view('bootAnim').attrs.layout_height, '190dp');
  assert.equal(view('overlayTitle').attrs.textColor, '#EAF0FA');
  assert.equal(view('overlayTitle').attrs.letterSpacing, '0.32');
  assert.equal(view('webContainer').tag, 'FrameLayout');
  assert.equal(view('webContainer').children.length, 0, 'Do not replace the official Web GUI host');
});

test('weighted center scroll and bottom actions are siblings in a bounded vertical shell', () => {
  const scroll = view('overlayContentScroll');
  const actions = view('overlayActions');
  const shell = parents.get(scroll);
  assert.equal(shell.tag, 'LinearLayout');
  assert.equal(shell.attrs.orientation, 'vertical');
  assert.equal(shell.attrs.layout_height, 'match_parent');
  assert.equal(parents.get(shell), view('overlay'));
  assert.equal(parents.get(actions), shell);
  assert.deepEqual(shell.children, [scroll, actions], 'Actions must follow, not overlay, the center');
  assert.equal(scroll.tag, 'ScrollView');
  assert.equal(scroll.attrs.layout_height, '0dp');
  assert.equal(scroll.attrs.layout_weight, '1');
  assert.equal(scroll.attrs.fillViewport, 'true');
  assert.equal(scroll.children.length, 1);
  assert.equal(scroll.children[0].attrs.layout_height, 'wrap_content');
  assert.equal(scroll.children[0].attrs.gravity, 'center');
  assert.equal(actions.attrs.layout_height, 'wrap_content');
  assert.equal(actions.attrs.visibility, undefined);
});

test('full error details and retry/show-page stay inside the scroll without line/height truncation', () => {
  const scroll = view('overlayContentScroll');
  for (const id of ['bootAnim', 'overlayTitle', 'overlayText', 'overlayHint', 'overlayRetry', 'overlayShowPage']) {
    assert.ok(isWithin(view(id), scroll), `${id} belongs in the scrollable center`);
  }
  for (const id of ['overlayText', 'overlayHint']) {
    const attrs = view(id).attrs;
    assert.equal(attrs.layout_height, 'wrap_content');
    for (const limit of ['maxLines', 'lines', 'singleLine', 'ellipsize', 'maxHeight', 'height']) {
      assert.equal(attrs[limit], undefined, `${id} must not truncate details using ${limit}`);
    }
  }
  assert.equal(view('overlayHint').attrs.layout_width, 'match_parent', 'Wrap long stack lines to viewport width');
  const source = readFileSync(new URL('../main/java/com/dsh/android/ui/MainActivity.kt', import.meta.url), 'utf8');
  assert.match(source, /binding\.overlayHint\.text = hint\.orEmpty\(\)/, 'Keep the complete runtime error hint');
});

test('all four always-visible recovery actions occupy exactly two fixed-bottom rows', () => {
  const actions = view('overlayActions');
  assert.equal(actions.attrs.orientation, 'vertical');
  assert.equal(actions.children.length, 2);
  assert.deepEqual(actions.children.map(row => row.children.map(child => child.attrs.id)), [
    ['@+id/overlayExport', '@+id/overlayImport'],
    ['@+id/overlayLogs', '@+id/overlayRepair'],
  ]);
  for (const row of actions.children) {
    assert.equal(row.tag, 'LinearLayout');
    assert.equal(row.attrs.orientation, 'horizontal');
    assert.equal(row.attrs.layout_width, 'match_parent');
    assert.equal(row.attrs.layout_height, 'wrap_content');
    assert.equal(row.attrs.baselineAligned, 'false');
  }
  for (const id of nativeActions) {
    const action = view(id);
    assert.ok(isWithin(action, actions));
    assert.ok(!isWithin(action, view('overlayContentScroll')), `${id} cannot scroll offscreen with errors`);
    assert.equal(action.attrs.visibility, undefined, `${id} must not depend on engine state`);
  }
});

test('recovery actions use equal-width wrapping labels and at least 48dp touch height', () => {
  for (const id of nativeActions) {
    const attrs = view(id).attrs;
    assert.equal(attrs.layout_width, '0dp');
    assert.equal(attrs.layout_weight, '1');
    assert.equal(attrs.layout_height, 'wrap_content', 'Large font labels can grow rather than clip');
    assert.ok(Number.parseFloat(attrs.minHeight) >= 48, `${id} needs a 48dp minimum touch target`);
    assert.ok(attrs.minHeight.endsWith('dp'));
    assert.equal(attrs.gravity, 'center');
    assert.equal(attrs.clickable, 'true');
    assert.equal(attrs.focusable, 'true');
    assert.equal(attrs.background, '@drawable/log_chip_bg');
    assert.equal(attrs.maxLines, undefined);
    assert.equal(attrs.ellipsize, undefined);
    assert.equal(attrs.singleLine, undefined);
  }
});
