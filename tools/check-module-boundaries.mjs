#!/usr/bin/env node
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { resolve, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const MODULES = [':app', ':core:contracts', ':core:logging', ':runtime:termux', ':platform:privilege', ':runtime:engine', ':web:contract', ':web:host', ':feature:bubble', ':feature:browser', ':bridge:transport'];
// Code dependencies are checked against the actual declaring module, not package names.
export const ALLOWED = {
  ':app': MODULES.filter(name => name !== ':app'),
  ':core:logging': [],
  ':core:contracts': [':core:logging'],
  ':runtime:termux': [':core:logging'],
  ':platform:privilege': [':core:logging'],
  ':bridge:transport': [],
  ':runtime:engine': [':core:logging', ':core:contracts', ':runtime:termux', ':platform:privilege'],
  ':web:contract': [],
  ':web:host': [':web:contract', ':core:logging'],
  ':feature:bubble': [':core:logging', ':core:contracts', ':platform:privilege', ':bridge:transport'],
  ':feature:browser': [':bridge:transport'],
};

/** Mask literals/comments but retain executable Kotlin string interpolation at exact offsets. */
export function maskKotlin(source) {
  const output = source.split(''); let index = 0;
  const erase = (start, end) => { for (let offset = start; offset < end; offset++) if (!/[\n\r]/.test(source[offset])) output[offset] = ' '; };
  function stringLiteral() {
    const quote = source.startsWith('"""', index) ? '"""' : source[index];
    const interpolated = quote[0] === '"';
    erase(index, index + quote.length); index += quote.length;
    while (index < source.length) {
      if (source.startsWith(quote, index)) { erase(index, index + quote.length); index += quote.length; return; }
      if (quote.length === 1 && source[index] === '\\') { erase(index, Math.min(source.length, index + 2)); index += 2; }
      else if (interpolated && source.startsWith('${', index)) { erase(index, index + 2); index += 2; code(true); }
      else if (interpolated && source[index] === '$' && /[A-Za-z_]/.test(source[index + 1] ?? '')) {
        erase(index, index + 1); index++;
        while (/[\w]/.test(source[index] ?? '') && index < source.length) index++;
      } else { erase(index, index + 1); index++; }
    }
  }
  function code(interpolation = false) {
    let braces = 0;
    while (index < source.length) {
      if (interpolation && source[index] === '}' && braces === 0) { erase(index, index + 1); index++; return; }
      if (source.startsWith('//', index)) {
        const end = source.indexOf('\n', index); const stop = end < 0 ? source.length : end;
        erase(index, stop); index = stop;
      } else if (source.startsWith('/*', index)) {
        const start = index; let depth = 1; index += 2;
        while (index < source.length && depth) {
          if (source.startsWith('/*', index)) { depth++; index += 2; }
          else if (source.startsWith('*/', index)) { depth--; index += 2; }
          else index++;
        }
        erase(start, index);
      } else if (source[index] === '"' || source[index] === "'") stringLiteral();
      else { if (source[index] === '{') braces++; if (source[index] === '}') braces--; index++; }
    }
  }
  code(); return output.join('');
}

const identifier = '[A-Za-z_$][\\w$]*';
function sourceInfo(source) {
  const code = maskKotlin(source);
  const packageName = code.match(/^\s*package\s+([\w.]+)/m)?.[1] ?? '';
  const imports = [...code.matchAll(/^\s*import\s+(?:static\s+)?([\w.*]+)(?:\s+as\s+(\w+))?/gm)].map(match => ({ name: match[1], alias: match[2] }));
  const declarations = new Set();
  let depth = 0, parentheses = 0;
  const localNames = new Set();
  // Top-level names are indexed for classes AND Kotlin top-level functions/properties.
  const tokens = [...code.matchAll(/[A-Za-z_$][\w$]*|[{}().<>:;=]/g)];
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i][0];
    if (token === '(') { parentheses++; continue; }
    if (token === ')') { parentheses--; continue; }
    if (token === '{') { depth++; continue; }
    if (token === '}') { depth--; continue; }
    if (['class', 'object', 'interface', 'typealias', 'val', 'var'].includes(token) && /^[A-Za-z_$][\w$]*$/.test(tokens[i + 1]?.[0] ?? '')) localNames.add(tokens[i + 1][0]);
    if (token === 'fun') {
      for (let j = i + 1; j < tokens.length; j++) {
        if (['{', '}', '=', ';'].includes(tokens[j][0])) break;
        if (tokens[j][0] === '(') { localNames.add(tokens[j - 1]?.[0]); break; }
      }
    }
    if (depth !== 0 || parentheses !== 0) continue;
    if (['class', 'object', 'interface', 'typealias', 'enum', 'record'].includes(token)) {
      const name = tokens[i + 1]?.[0];
      if (name && !['class', 'object', ':', '{'].includes(name) && /^[\w$]+$/.test(name)) declarations.add(name);
    } else if (token === 'fun') {
      // Last identifier before '(' also covers receiver extension declarations.
      for (let j = i + 1; j < tokens.length; j++) {
        if (['{', '}', '=', ';'].includes(tokens[j][0])) break;
        if (tokens[j][0] === '(') { const name = tokens[j - 1]?.[0]; if (/^[\w$]+$/.test(name)) declarations.add(name); break; }
      }
    } else if (token === 'val' || token === 'var') {
      const name = tokens[i + 1]?.[0]; if (/^[A-Za-z_$][\w$]*$/.test(name ?? '')) declarations.add(name);
    }
  }
  const body = code.replace(/^\s*(?:package|import)\b[^\n]*/gm, '');
  return { packageName, imports, declarations, localNames, body };
}

function walkSources(directory, pattern = /\.(kt|java)$/) {
  if (!existsSync(directory)) return [];
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (['build', '.gradle', 'node_modules', '.git'].includes(entry.name)) continue;
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) files.push(...walkSources(path, pattern));
    else if (pattern.test(entry.name) && !/[/\\]src[/\\](?:test|androidTest|testFixtures)(?:[/\\]|$)/.test(path)) files.push(path);
  }
  return files;
}

export function checkModules({ root = ROOT, modules = MODULES, allowed = ALLOWED } = {}) {
  const errors = [], dependencies = {}, sources = [], providers = new Map();
  const settingsPath = resolve(root, 'settings.gradle.kts');
  const settings = existsSync(settingsPath) ? readFileSync(settingsPath, 'utf8').replace(/\/\/[^\n]*/g, '') : '';
  const included = new Set([...settings.matchAll(/include\s*\(([^)]*)\)/g)].flatMap(match => [...match[1].matchAll(/["'](:[\w:-]+)["']/g)].map(value => value[1])));
  for (const name of included) if (!modules.includes(name)) errors.push(`Unregistered Gradle module: ${name}`);
  function addProvider(symbol, owner, file) {
    const existing = providers.get(symbol);
    if (existing && existing.module !== owner) errors.push(`Ambiguous declaration ${symbol}: ${existing.module} and ${owner}`);
    else providers.set(symbol, { module: owner, file });
  }
  for (const module of modules) {
    const directory = resolve(root, module.slice(1).replaceAll(':', '/'));
    const build = resolve(directory, 'build.gradle.kts');
    if (!included.has(module)) errors.push(`Missing settings include: ${module}`);
    if (!existsSync(build)) { errors.push(`Missing module build file: ${module}`); dependencies[module] = []; continue; }
    const buildSource = readFileSync(build, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
    dependencies[module] = [...new Set([...buildSource.matchAll(/project\s*\(\s*["'](:[\w:-]+)["']\s*\)/g)].map(match => match[1]))];
    for (const target of dependencies[module]) {
      if (!modules.includes(target)) errors.push(`${module}: unknown project dependency ${target}`);
      if (!(allowed[module] ?? []).includes(target)) errors.push(`${module}: forbidden Gradle dependency ${target}`);
    }
    const namespace = buildSource.match(/namespace\s*=\s*["']([^"']+)/)?.[1];
    if (namespace) {
      addProvider(`${namespace}.R`, module, build);
      if (/viewBinding\s*=\s*true/.test(buildSource)) {
        for (const layout of walkSources(resolve(directory, 'src'), /\.xml$/).filter(path => /[/\\]res[/\\]layout[^/\\]*[/\\]/.test(path))) {
          if (/viewBindingIgnore\s*=\s*["']true/.test(readFileSync(layout, 'utf8'))) continue;
          const name = layout.split(/[/\\]/).pop().replace(/\.xml$/, '').split('_').map(part => part[0].toUpperCase() + part.slice(1)).join('') + 'Binding';
          addProvider(`${namespace}.databinding.${name}`, module, layout);
        }
      }
      if (module === ':app' || /buildConfig\s*=\s*true/.test(buildSource)) addProvider(`${namespace}.BuildConfig`, module, build);
    }
    const files = walkSources(resolve(directory, 'src'));
    if (!files.length) errors.push(`Module has no production sources: ${module}`);
    for (const file of files) {
      const info = { module, file, ...sourceInfo(readFileSync(file, 'utf8')) };
      if (!info.packageName) errors.push(`${relative(root, file)}: missing package declaration`);
      for (const name of info.declarations) addProvider(`${info.packageName}.${name}`, module, file);
      sources.push(info);
    }
  }
  const visited = new Set(), visiting = [];
  function visit(module) {
    if (visiting.includes(module)) { errors.push(`Gradle dependency cycle: ${[...visiting.slice(visiting.indexOf(module)), module].join(' -> ')}`); return; }
    if (visited.has(module)) return;
    visiting.push(module);
    for (const next of dependencies[module] ?? []) if (modules.includes(next)) visit(next);
    visiting.pop(); visited.add(module);
  }
  for (const module of modules) visit(module);
  function lookup(name) {
    let candidate = name;
    while (candidate.includes('.')) {
      if (providers.has(candidate)) return providers.get(candidate);
      candidate = candidate.slice(0, candidate.lastIndexOf('.'));
    }
  }
  const refs = new Set();
  function checkReference(source, provider, symbol) {
    if (!provider || source.module === provider.module) return;
    const key = `${source.file}:${provider.module}:${symbol}`;
    if (refs.has(key)) return; refs.add(key);
    if (!(dependencies[source.module] ?? []).includes(provider.module)) errors.push(`${relative(root, source.file)}: ${symbol} is declared by ${provider.module}; missing direct project dependency from ${source.module}`);
    if (!(allowed[source.module] ?? []).includes(provider.module)) errors.push(`${relative(root, source.file)}: forbidden source dependency ${source.module} -> ${provider.module} via ${symbol}`);
  }
  for (const source of sources) {
    for (const item of source.imports) {
      if (item.name.endsWith('.*')) {
        const prefix = item.name.slice(0, -1);
        for (const [symbol, provider] of providers) if (symbol.startsWith(prefix)) checkReference(source, provider, item.name);
      } else {
        const provider = lookup(item.name);
        checkReference(source, provider, item.name);
        if (!provider && item.name.startsWith('com.dsh.android.')) errors.push(`${relative(root, source.file)}: unresolved project import ${item.name}`);
      }
    }
    for (const match of source.body.matchAll(new RegExp(`\\b${identifier}(?:\\.${identifier})+`, 'g'))) checkReference(source, lookup(match[0]), match[0]);
    for (const match of source.body.matchAll(new RegExp(`\\b${identifier}\\b`, 'g'))) {
      if (source.localNames.has(match[0]) || source.imports.some(item => (item.alias || item.name.split('.').pop()) === match[0])) continue;
      const symbol = `${source.packageName}.${match[0]}`;
      checkReference(source, providers.get(symbol), symbol);
    }
  }
  return { schemaVersion: 1, modules: modules.length, sources: sources.length, declarations: providers.size, edges: Object.values(dependencies).reduce((count, edges) => count + edges.length, 0), references: refs.size, dependencies, errors: [...new Set(errors)], skipped: 0 };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2); const rootIndex = args.indexOf('--root');
  const result = checkModules({ root: rootIndex < 0 ? ROOT : resolve(args[rootIndex + 1]) });
  console.log(JSON.stringify(result, null, 2));
  if (result.errors.length) process.exitCode = 1;
}
