package com.dsh.android.engine

/** 启动前使用引擎自己的模板和 YAML 解析器；同一脚本由独立 Node 测试直接执行。 */
internal object BuiltinPluginBootstrap {
    val script = """
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const input = JSON.parse(fs.readFileSync(process.argv[1], 'utf8'));
const engineRequire = createRequire(path.join(input.engineDir, 'node_modules/@deepseek-ai/dsh/package.json'));
const { initProfile } = engineRequire('@deepseek-ai/dsh-app-boot');
const { PROFILE_TEMPLATES } = engineRequire('@deepseek-ai/dsh-official-bundles');
const yaml = engineRequire('js-yaml');
const expression = new yaml.Type('tag:yaml.org,2002:js', {
  kind: 'scalar', construct: value => ({ __jsExpr: value }),
  predicate: value => value && typeof value.__jsExpr === 'string',
  represent: value => value.__jsExpr,
});
const schema = yaml.JSON_SCHEMA.extend(expression);
function atomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temporary = file + '.builtin-tmp';
  try { fs.writeFileSync(temporary, text); fs.renameSync(temporary, file); }
  finally { fs.rmSync(temporary, { force: true }); }
}
function json(file, value) { atomic(file, JSON.stringify(value, null, 2) + '\n'); }
const profileDir = path.join(input.home, 'profiles', input.profile);
const manifestPath = path.join(profileDir, 'package.json');
if (input.phase === 'init') {
  // 模板由本次 APK 解包的官方引擎提供，不能复制上一版核心包名。
  const bundles = PROFILE_TEMPLATES.web?.bundles;
  if (!Array.isArray(bundles) || !bundles.length) throw new Error('missing official web profile template');
  if (!fs.existsSync(manifestPath)) {
    fs.mkdirSync(profileDir, { recursive: true });
    json(manifestPath, { name: 'dsh-profile-' + input.profile, private: true, dependencies: {}, dsh: { profile: { bundles: [...bundles] } } });
  }
  // 仅初始化 pnpm 设置；用户 patch 仍交给 seedDefaultSettings 首次播种。
  const patch = path.join(profileDir, 'cordis.patch.yml');
  const hadPatch = fs.existsSync(patch);
  initProfile(profileDir, bundles);
  if (!hadPatch) fs.rmSync(patch);
} else {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const dependencies = manifest.dependencies ??= {};
  const profile = (manifest.dsh ??= {}).profile ??= {};
  const bundles = profile.bundles ??= [];
  if (!Array.isArray(bundles)) throw new Error('profile bundles must be an array');
  const plan = [];
  for (const item of input.plugins) {
    const directory = path.join(input.home, 'node_modules', item.packageName);
    const marker = path.join(input.home, '.builtin-plugins', item.id + '.json');
    const seen = Object.hasOwn(manifest.dsh?.android?.builtinPlugins ?? {}, item.id) || fs.existsSync(marker) || (item.legacyMarker && fs.existsSync(path.join(input.home, item.legacyMarker)));
    const spec = dependencies[item.packageName];
    const local = 'link:' + directory;
    // 用户卸载后不补依赖；registry/file/git 等用户选择始终优先。
    const owned = spec === local;
    const first = !seen && spec === undefined;
    if (first || owned) plan.push({ ...item, directory, marker, seen, first, local });
  }
  if (input.phase === 'plan') json(input.output, plan);
  else if (input.phase === 'register') {
    const patchPath = path.join(profileDir, 'cordis.patch.yml');
    const originalPatch = fs.existsSync(patchPath) ? fs.readFileSync(patchPath, 'utf8') : '[]\n';
    let patches;
    let patchChanged = false;
    let manifestChanged = false;
    const committed = [];
    function patchRows() {
      if (patches === undefined) {
        patches = yaml.load(originalPatch, { schema }) ?? [];
        if (!Array.isArray(patches)) throw new Error('user patch must be an array');
      }
      return patches;
    }
    function migrate(item) {
      if (Object.hasOwn(manifest.dsh?.android?.builtinPlugins ?? {}, item.id)) return;
      const legacyFile = item.legacyPatch && path.join(input.home, item.legacyPatch);
      const legacy = legacyFile && fs.existsSync(legacyFile) ? yaml.load(fs.readFileSync(legacyFile, 'utf8'), { schema }) : [];
      if (legacy != null && !Array.isArray(legacy)) throw new Error('legacy overlay must be an array: ' + legacyFile);
      const inherited = (legacy || []).flatMap(row => Array.isArray(row?.insert) ? row.insert : [row]).find(row => row?.id === item.entryId);
      if (!inherited) return;
      const values = {};
      for (const key of ['config', 'disabled']) if (Object.hasOwn(inherited, key)) values[key] = inherited[key];
      if (!Object.keys(values).length) return;
      const rows = patchRows();
      let row = rows.find(row => row?.id === item.entryId);
      if (!row) { row = { id: item.entryId }; rows.push(row); }
      for (const [key, value] of Object.entries(values)) {
        if (!Object.hasOwn(row, key)) { row[key] = value; patchChanged = true; }
      }
    }
    for (const item of plan) {
      const pkgFile = path.join(item.directory, 'package.json');
      if (!fs.existsSync(pkgFile)) continue;
      const pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf8'));
      if (pkg.name !== item.packageName || pkg.dsh?.android?.builtinId !== item.id || !pkg.dsh?.bundle?.patch || !fs.existsSync(path.join(item.directory, pkg.dsh.bundle.patch))) continue;
      migrate(item);
      if (item.first) {
        dependencies[item.packageName] = item.local;
        manifestChanged = true;
        if (item.enabled && !bundles.includes(item.packageName)) bundles.push(item.packageName);
      }
      committed.push(item);
    }
    if (patchChanged) atomic(patchPath, yaml.dump(patches, { schema, noRefs: true, lineWidth: -1 }));
    // manifest 内保存不随版本变化的登记凭据，避免文件标记落盘前中断导致关闭被重新启用。
    const registrations = (manifest.dsh.android ??= {}).builtinPlugins ??= {};
    for (const item of input.plugins) {
      // 已有市场选择也必须记为见过，随后用户卸载时不再出厂播种。
      if (dependencies[item.packageName] !== undefined && !Object.hasOwn(registrations, item.id)) {
        registrations[item.id] = item.packageName;
        manifestChanged = true;
      }
    }
    if (manifestChanged) json(manifestPath, manifest);
    for (const item of committed) {
      if (!fs.existsSync(item.marker)) json(item.marker, { id: item.id, package: item.packageName });
      if (item.legacyPatch) fs.rmSync(path.join(input.home, item.legacyPatch), { force: true });
    }
  } else throw new Error('unknown bootstrap phase');
}
""".trimIndent()
}
