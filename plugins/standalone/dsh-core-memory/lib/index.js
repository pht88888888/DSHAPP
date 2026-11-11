// dsh-core-memory — 跨会话持久记忆（OpenClaw 风格：核心记忆常驻注入 + 归档记忆按需检索）
// 由动态插件 dshmem-3 原型固化而来；静态 host 插件，Node 原生 fs 直读直写。
//
// 布局（<dshHome>/memory/，默认 ~/.dsh/memory）：
//   MEMORY.md   核心记忆：每个会话系统提示常驻注入（order 60 节，同步读、永远新鲜）
//   notes/      归档记忆：一条一文件，front-matter（id/title/tags/created/updated）
//   index.json  元数据索引（list/search 的真相源；损坏时从 notes/ 重建）
import { readFile, writeFile, rename, mkdir, readdir } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, dirname } from 'node:path';
import {
  buildPersonaText,
  listPresets,
  readPersona,
  renderPersonaFile,
  normalizePersonaInput,
  personaPath,
  MAX_CUSTOM_TEXT,
  PRESETS,
} from './persona.js';

export const name = 'dsh-core-memory';
export const inject = ['tools', 'systemPrompt'];

function dshHome() {
  return process.env.DSH_HOME || join(homedir(), '.dsh');
}
const corePath = () => join(dshHome(), 'memory', 'MEMORY.md');
const notesDir = () => join(dshHome(), 'memory', 'notes');
const indexPath = () => join(dshHome(), 'memory', 'index.json');

const TEMPLATE = [
  '# DSH 核心记忆',
  '',
  '> 由 dsh-core-memory 插件维护，并注入每个会话的系统提示。保持精炼（建议 6KB 内）。',
  '> 可用 memory_core 工具增删改，也可手工编辑（插件每次组装时同步读取，立即可见）。',
  '',
  '## 用户',
  '- （长期有效的用户画像与偏好，用 memory_core append 追加）',
  '',
  '## 环境',
  '- （机器、部署、常用路径等长期事实）',
  '',
  '## 项目',
  '- （进行中的项目、关键决策与约定）',
  '',
].join('\n');

// ---------- 纯函数 ----------
function noteSort(a, b) { return (b.created || '').localeCompare(a.created || '') }

function parseNoteFile(text) {
  const out = { meta: {}, body: text };
  if (typeof text === 'string' && text.startsWith('---')) {
    const end = text.indexOf('\n---', 3);
    if (end >= 0) {
      const fm = text.slice(3, end);
      out.body = text.slice(end + 4).replace(/^\n+/, '');
      for (const line of fm.split('\n')) {
        const at = line.indexOf(':');
        if (at > 0) out.meta[line.slice(0, at).trim()] = line.slice(at + 1).trim();
      }
    }
  }
  return out;
}

function parseCoreSections(text) {
  const lines = String(text).split('\n');
  const header = [];
  const sections = [];
  let current = null;
  let body = [];
  for (const line of lines) {
    const m = /^##\s+(.+?)\s*$/.exec(line);
    if (m) {
      if (current !== null) sections.push({ title: current, body: body.join('\n') });
      current = m[1];
      body = [];
    } else if (current !== null) {
      body.push(line);
    } else {
      header.push(line);
    }
  }
  if (current !== null) sections.push({ title: current, body: body.join('\n') });
  return { header: header.join('\n'), sections };
}

function renderCore(parsed) {
  let text = parsed.header;
  for (const s of parsed.sections) {
    const body = s.body.replace(/^\n+/, '').replace(/\n+\s*$/, '');
    text += '\n## ' + s.title + '\n' + (body ? body + '\n' : '');
  }
  return text;
}

function makeId(title, existing) {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = '' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + '-' + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds());
  const slug = String(title || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 32).replace(/-+$/g, '');
  const base = stamp + (slug ? '-' + slug : '');
  const taken = new Set(existing.map((n) => n.id));
  let candidate = base;
  let n = 2;
  while (taken.has(candidate)) { candidate = base + '-' + n; n++; }
  return candidate;
}

function noteFileText(meta, content) {
  return '---\n' +
    'id: ' + meta.id + '\n' +
    'title: ' + meta.title + '\n' +
    'tags: ' + ((meta.tags || []).join(', ')) + '\n' +
    'created: ' + meta.created + '\n' +
    'updated: ' + meta.updated + '\n' +
    '---\n\n' + content + '\n';
}

function scoreNote(note, query, bodyText) {
  const q = query.toLowerCase();
  const tokens = q.split(/\s+/).filter(Boolean);
  const title = (note.title || '').toLowerCase();
  const tagStr = (note.tags || []).join(' ').toLowerCase();
  const body = (bodyText || '').toLowerCase();
  let score = 0;
  for (const t of tokens) {
    if (title.indexOf(t) >= 0) score += 3;
    if (tagStr.indexOf(t) >= 0) score += 3;
    let hits = 0;
    let at = 0;
    while (hits < 50) {
      const i = body.indexOf(t, at);
      if (i < 0) break;
      score += 1;
      hits++;
      at = i + t.length;
    }
  }
  if (tokens.length > 1 && body.indexOf(q) >= 0) score += 2;
  return score;
}

function makeSnippet(bodyText, query) {
  const flat = String(bodyText).replace(/\s+/g, ' ').trim();
  const lower = flat.toLowerCase();
  const tokens = query.toLowerCase().split(/\s+/).filter(Boolean);
  let at = -1;
  for (const t of tokens) {
    const i = lower.indexOf(t);
    if (i >= 0 && (at < 0 || i < at)) at = i;
  }
  if (at < 0) return flat.slice(0, 120) + (flat.length > 120 ? '…' : '');
  const start = Math.max(0, at - 40);
  const end = Math.min(flat.length, at + 120);
  return (start > 0 ? '…' : '') + flat.slice(start, end) + (end < flat.length ? '…' : '');
}

// prompt 组装对 {{variable}} 严格插值——注入前把字面 {{ 拆开，避免撞变量语法
function sanitize(text) { return String(text).split('{{').join('{ {') }

// ---------- fs 辅助 ----------
function readFileSyncOrNull(path) {
  try { return readFileSync(path, 'utf8'); } catch { return null; }
}

async function atomicWrite(path, content) {
  await mkdir(dirname(path), { recursive: true });
  const tmp = `${path}.tmp-${process.pid}-${Date.now()}`;
  await writeFile(tmp, content, 'utf8');
  await rename(tmp, path);
}

async function rebuildIndex() {
  const out = [];
  let entries = [];
  try { entries = await readdir(notesDir()); } catch { return out; }
  for (const name of entries) {
    if (!name.endsWith('.md')) continue;
    try {
      const text = await readFile(join(notesDir(), name), 'utf8');
      const parsed = parseNoteFile(text);
      if (parsed.meta.deleted === 'true') continue; // 墓碑文件不复活
      const id = name.slice(0, -3);
      out.push({
        id,
        title: parsed.meta.title || id,
        tags: (parsed.meta.tags || '').split(',').map((s) => s.trim()).filter(Boolean),
        created: parsed.meta.created || '',
        updated: parsed.meta.updated || parsed.meta.created || '',
        size: text.length,
      });
    } catch { /* 跳过无法解析的文件 */ }
  }
  return out.sort(noteSort);
}

async function loadIndex() {
  try {
    const raw = await readFile(indexPath(), 'utf8');
    const parsed = JSON.parse(raw);
    if (parsed && Array.isArray(parsed.notes)) {
      return parsed.notes.filter((n) => n && typeof n.id === 'string').sort(noteSort);
    }
  } catch { /* 缺失或损坏 → 从 notes/ 重建 */ }
  return rebuildIndex();
}

// 写串行化：同进程多 agent 并发写也按序落盘
let tail = Promise.resolve();
function enqueue(job) {
  const run = tail.then(job, job);
  tail = run.then(() => undefined, () => undefined);
  return run;
}

function errText(e) { return '操作失败：' + (e && e.message ? e.message : String(e)); }

/** 读取并限制请求体大小（防误用），返回 UTF-8 文本。 */
function readBody(req, limit = 16384) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) { reject(new Error('请求体过大')); try { req.destroy(); } catch { /* 忽略 */ } return; }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// ---------- 召回节（同步读，每次模型组装永远新鲜） ----------
function buildSectionText() {
  try {
    let core = '';
    const coreRaw = readFileSyncOrNull(corePath());
    if (coreRaw !== null) core = coreRaw.trim();
    let coreNote = '';
    if (core.length > 3000) {
      core = core.slice(0, 3000);
      coreNote = '\n（核心记忆过长已截断——请尽快用 memory_core 精简）';
    }
    let notes = [];
    const indexRaw = readFileSyncOrNull(indexPath());
    if (indexRaw) {
      try {
        const p = JSON.parse(indexRaw);
        if (p && Array.isArray(p.notes)) notes = p.notes.filter((n) => n && typeof n.id === 'string').sort(noteSort);
      } catch { /* 索引损坏时本节不列归档，工具侧会重建 */ }
    }
    const recent = notes.slice(0, 12).map((n) =>
      '- [' + n.id + '] ' + n.title + ((n.tags && n.tags.length) ? '  #' + n.tags.join(' #') : '')
    ).join('\n');
    const persona = buildPersonaText();
    return [
      '# 跨会话记忆（dsh-core-memory）',
      '',
      '你在本机的所有会话共享以下持久记忆。核心记忆常驻注入；归档记忆仅列索引，用 memory_search / memory_read 按需取用。',
      '',
      '## 核心记忆（MEMORY.md）',
      sanitize(core) || '（空）',
      coreNote,
      ...(persona ? ['', '## 回复风格（用户设置）', sanitize(persona)] : []),
      '',
      '## 最近归档记忆（新→旧，最多 12 条）',
      recent || '（暂无归档记忆）',
      '',
      '## 使用政策',
      '- 当用户表达长期偏好、纠正你的做法、给出长期有效的环境/项目事实或重要决策时：立即用 memory_write（归档）或 memory_core（核心）保存，不要等用户要求。',
      '- 保存的内容必须自包含：未来会话看不到当前对话，要写清楚背景。',
      '- 不要保存：一次性细节、敏感信息（密码/凭据/token）、能从代码或文件轻易恢复的信息。',
      '- 需要回忆细节时主动 memory_search；需要浏览时 memory_list。',
      '- 用户要求改变回复风格时：用 persona 工具（action=set 切预设 / action=custom 写自定义），不要写进 MEMORY.md。',
    ].join('\n');
  } catch {
    return '（跨会话记忆：暂不可用）';
  }
}

// ---------- 六个记忆工具 ----------
const outputText = { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] };

const toolDefs = [
  {
    name: 'memory_write',
    description: '保存一条归档记忆（episodic note），跨所有会话持久。适用：用户偏好/纠正、项目决策、环境事实、重要结论等未来会话可能需要的内容。内容要自包含（未来会话看不到当前对话）。保存后立刻进入索引，所有会话可用 memory_search 检索。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        title: { type: 'string', description: '简短标题（用于生成文件名与检索）' },
        content: { type: 'string', description: '记忆正文，自包含，可多行 Markdown' },
        tags: { type: 'array', items: { type: 'string' }, description: '可选标签数组，便于检索' },
      },
      required: ['title', 'content'],
    },
    output: outputText,
    async execute(args) {
      const title = typeof args.title === 'string' ? args.title.replace(/\s+/g, ' ').trim() : '';
      const content = typeof args.content === 'string' ? args.content : '';
      if (!title || !content.trim()) return '保存失败：title 和 content 均不能为空。';
      const tags = Array.isArray(args.tags)
        ? args.tags.filter((t) => typeof t === 'string' && t.trim()).map((t) => t.trim())
        : [];
      try {
        return await enqueue(async () => {
          const existing = await loadIndex();
          const id = makeId(title, existing);
          const now = new Date().toISOString();
          const meta = { id, title, tags, created: now, updated: now, size: 0 };
          const fileText = noteFileText(meta, content);
          meta.size = fileText.length;
          await atomicWrite(join(notesDir(), id + '.md'), fileText);
          const notes = [meta, ...existing].sort(noteSort);
          await atomicWrite(indexPath(), JSON.stringify({ version: 1, notes }, null, 2));
          return '已保存归档记忆 [' + id + '] ' + title + (tags.length ? ' #' + tags.join(' #') : '') + '。已进入索引，所有会话可见。';
        });
      } catch (e) { return errText(e); }
    },
  },
  {
    name: 'memory_core',
    description: '编辑核心记忆 MEMORY.md——它在每个会话的系统提示里常驻注入，务必保持精炼。action=append 在指定分节末尾追加要点；action=replace 用 text 整体替换指定分节；action=remove 删除指定分节。适合：用户画像/偏好、环境事实、项目约定这类需要"每次都记得"的长期信息。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        action: { type: 'string', enum: ['append', 'replace', 'remove'], description: 'append=追加到分节末尾；replace=整体替换分节；remove=删除分节' },
        section: { type: 'string', description: '分节名（MEMORY.md 里的 ## 标题），如：用户 / 环境 / 项目' },
        text: { type: 'string', description: '要写入的内容（append/replace 时必填）' },
      },
      required: ['action', 'section'],
    },
    output: outputText,
    async execute(args) {
      const action = args.action;
      if (action !== 'append' && action !== 'replace' && action !== 'remove') {
        return '编辑失败：action 必须是 append / replace / remove。';
      }
      const section = typeof args.section === 'string' ? args.section.trim() : '';
      const text = typeof args.text === 'string' ? args.text : '';
      if (!section) return '编辑失败：section 不能为空。';
      if ((action === 'append' || action === 'replace') && !text.trim()) return '编辑失败：append/replace 需要提供 text。';
      try {
        return await enqueue(async () => {
          let raw = null;
          try { raw = await readFile(corePath(), 'utf8'); } catch { /* 不存在 → 用模板 */ }
          const parsed = parseCoreSections(raw !== null ? raw : TEMPLATE);
          const target = parsed.sections.find((s) => s.title.toLowerCase() === section.toLowerCase());
          const titles = parsed.sections.map((s) => s.title).join('、') || '（无）';
          if (action === 'remove') {
            if (!target) return '删除失败：找不到分节「' + section + '」。现有分节：' + titles;
            parsed.sections = parsed.sections.filter((s) => s !== target);
          } else if (action === 'append') {
            if (target) target.body = (target.body.replace(/\s+$/, '') ? target.body.replace(/\s+$/, '') + '\n' : '') + text;
            else parsed.sections.push({ title: section, body: text });
          } else {
            if (!target) return '替换失败：找不到分节「' + section + '」。现有分节：' + titles;
            target.body = text;
          }
          const next = renderCore(parsed);
          await atomicWrite(corePath(), next);
          const verb = action === 'append' ? '追加' : (action === 'replace' ? '替换' : '删除');
          const warn = next.length > 6000 ? '（警告：核心记忆已超 6KB，会占用每个会话的上下文，建议尽快精简）' : '';
          return '已' + verb + '核心记忆分节「' + section + '」。当前核心记忆 ' + next.length + ' 字节。' + warn;
        });
      } catch (e) { return errText(e); }
    },
  },
  {
    name: 'memory_search',
    description: '按关键词检索归档记忆（标题/标签/正文），返回相关度排序的摘要。需要回忆过往会话保存的细节时主动使用。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        query: { type: 'string', description: '检索关键词，可多个（空格分隔）' },
        limit: { type: 'integer', description: '最多返回条数，默认 8' },
      },
      required: ['query'],
    },
    output: outputText,
    async execute(args) {
      const query = typeof args.query === 'string' ? args.query.trim() : '';
      if (!query) return '搜索失败：query 不能为空。';
      const limit = typeof args.limit === 'number' && args.limit > 0 ? Math.min(Math.floor(args.limit), 30) : 8;
      try {
        const notes = await loadIndex();
        const scored = [];
        for (const note of notes) {
          let bodyText = '';
          try {
            const t = await readFile(join(notesDir(), note.id + '.md'), 'utf8');
            bodyText = parseNoteFile(t).body;
          } catch { bodyText = ''; }
          const score = scoreNote(note, query, bodyText);
          if (score > 0) scored.push({ note, score, bodyText });
        }
        scored.sort((a, b) => b.score - a.score);
        if (scored.length === 0) return '没有匹配「' + query + '」的记忆。可尝试其他关键词，或用 memory_list 浏览全部。';
        const lines = scored.slice(0, limit).map((hit) =>
          '[' + hit.note.id + '] ' + hit.note.title + '（' + (hit.note.created || '').slice(0, 10) + '）\n  ' + makeSnippet(hit.bodyText, query)
        );
        return '找到 ' + scored.length + ' 条匹配（显示前 ' + Math.min(limit, scored.length) + ' 条，按相关度排序）：\n\n' + lines.join('\n\n');
      } catch (e) { return errText(e); }
    },
  },
  {
    name: 'memory_read',
    description: '按 id 读取一条归档记忆的完整内容。id 来自 memory_list / memory_search 的结果（或注入索引里的 [id]）。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        id: { type: 'string', description: '记忆 id（可带 .md 后缀；支持唯一前缀匹配）' },
      },
      required: ['id'],
    },
    output: outputText,
    async execute(args) {
      const ref = typeof args.id === 'string' ? args.id.trim() : '';
      if (!ref) return '读取失败：id 不能为空。可用 memory_list 获取 id。';
      try {
        const notes = await loadIndex();
        const clean = ref.replace(/\.md$/, '');
        let matches = notes.filter((n) => n.id === clean);
        if (matches.length === 0) matches = notes.filter((n) => n.id.indexOf(clean) === 0);
        if (matches.length === 0) return '未找到记忆「' + ref + '」。可用 memory_list 浏览全部 ' + notes.length + ' 条归档记忆。';
        if (matches.length > 1) return 'id 有歧义，候选：\n' + matches.map((n) => '- [' + n.id + '] ' + n.title).join('\n');
        const note = matches[0];
        let text = '';
        try {
          text = await readFile(join(notesDir(), note.id + '.md'), 'utf8');
        } catch (e) {
          return '记忆 [' + note.id + '] 在索引中存在但文件读取失败：' + errText(e);
        }
        const parsed = parseNoteFile(text);
        let out = '[' + note.id + '] ' + note.title + '\n创建：' + note.created + ((note.tags && note.tags.length) ? '\n标签：' + note.tags.join(', ') : '') + '\n\n' + parsed.body;
        if (out.length > 8000) out = out.slice(0, 8000) + '\n…（内容过长已截断）';
        return out;
      } catch (e) { return errText(e); }
    },
  },
  {
    name: 'memory_list',
    description: '列出归档记忆（新→旧），可按标签过滤。用于浏览记忆库全貌。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        limit: { type: 'integer', description: '最多返回条数，默认 15' },
        tag: { type: 'string', description: '可选：只列带此标签的记忆' },
      },
      required: [],
    },
    output: outputText,
    async execute(args) {
      const limit = typeof args.limit === 'number' && args.limit > 0 ? Math.min(Math.floor(args.limit), 100) : 15;
      const tag = typeof args.tag === 'string' ? args.tag.trim().toLowerCase() : '';
      try {
        let notes = await loadIndex();
        if (tag) notes = notes.filter((n) => (n.tags || []).some((t) => String(t).toLowerCase() === tag));
        if (notes.length === 0) return tag ? '没有带标签 #' + tag + ' 的记忆。' : '暂无归档记忆。';
        const lines = notes.slice(0, limit).map((n) =>
          '- [' + n.id + '] ' + n.title + ((n.tags && n.tags.length) ? '  #' + n.tags.join(' #') : '') + '（' + (n.created || '').slice(0, 10) + '）'
        );
        return '归档记忆共 ' + notes.length + ' 条（显示前 ' + Math.min(limit, notes.length) + ' 条，新→旧）：\n' + lines.join('\n');
      } catch (e) { return errText(e); }
    },
  },
  {
    name: 'memory_delete',
    description: '删除一条归档记忆：从索引移除并把文件改写为墓碑占位。用于清理过时或错误的记忆。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        id: { type: 'string', description: '要删除的记忆 id' },
      },
      required: ['id'],
    },
    output: outputText,
    async execute(args) {
      const ref = typeof args.id === 'string' ? args.id.trim() : '';
      if (!ref) return '删除失败：id 不能为空。';
      try {
        return await enqueue(async () => {
          const notes = await loadIndex();
          const clean = ref.replace(/\.md$/, '');
          const matches = notes.filter((n) => n.id === clean || n.id.indexOf(clean) === 0);
          if (matches.length === 0) return '删除失败：未找到「' + ref + '」。可用 memory_list 浏览全部。';
          if (matches.length > 1) return 'id 有歧义，请精确指定：\n' + matches.map((n) => '- [' + n.id + '] ' + n.title).join('\n');
          const note = matches[0];
          const next = notes.filter((n) => n.id !== note.id);
          await atomicWrite(indexPath(), JSON.stringify({ version: 1, notes: next }, null, 2));
          try {
            await atomicWrite(join(notesDir(), note.id + '.md'), '---\ndeleted: true\nid: ' + note.id + '\n---\n\n（此记忆已删除）\n');
          } catch { /* 索引已移除，墓碑写失败不影响结果 */ }
          return '已删除记忆 [' + note.id + '] ' + note.title + '。';
        });
      } catch (e) { return errText(e); }
    },
  },
  {
    name: 'persona',
    description: '读取或设置 AI 的回复风格（与 MEMORY.md 并列的持久偏好，注入每个会话的系统提示）。action=get 查看当前风格与全部预设；action=set 切换到某个预设；action=custom 写入自定义风格文本；action=disable 停用风格注入。仅在用户明确要求改变回复风格、或表达长期语气偏好时调用。',
    parameters: {
      type: 'object',
      additionalProperties: false,
      properties: {
        action: { type: 'string', enum: ['get', 'set', 'custom', 'disable'], description: 'get=查看；set=切预设；custom=写自定义；disable=停用' },
        preset: { type: 'string', description: 'action=set 时的预设 id，如 warm / concise / rigorous / humorous / gentle / default' },
        text: { type: 'string', description: 'action=custom 时的风格正文（建议 200 字内，上限 ' + MAX_CUSTOM_TEXT + ' 字符）' },
      },
      required: ['action'],
    },
    output: outputText,
    async execute(args) {
      const action = args.action;
      try {
        if (action === 'get') {
          const state = readPersona();
          const current = state.enabled
            ? (state.preset === 'custom' ? '自定义' : ((PRESETS.find((p) => p.id === state.preset) || {}).name || state.preset))
            : '（已停用）';
          return '当前回复风格：' + current +
            (state.enabled && state.preset === 'custom' && state.custom ? '（正文 ' + state.custom.length + ' 字符）' : '') +
            '\n生效文本：' + (buildPersonaText() || '（默认：不注入风格指令）') +
            '\n\n可选预设：\n' + PRESETS.map((p) => '- ' + p.id + '（' + p.name + '）：' + p.summary).join('\n') +
            '\n\n提示：改动将在下一次模型组装时生效。';
        }

        if (action === 'disable') {
          return await enqueue(async () => {
            const cur = readPersona();
            await atomicWrite(personaPath(), renderPersonaFile({ enabled: false, preset: cur.preset, custom: cur.custom }));
            return '已停用回复风格注入（预设选择「' + cur.preset + '」已保留，重新启用即可恢复）。';
          });
        }

        if (action !== 'set' && action !== 'custom') {
          return '设置失败：action 必须是 get / set / custom / disable。';
        }

        const patch = { enabled: true, preset: action === 'custom' ? 'custom' : (typeof args.preset === 'string' ? args.preset.trim() : '') };
        if (action === 'set' && !patch.preset) {
          return '设置失败：action=set 需要提供 preset。可用：' + PRESETS.map((p) => p.id).join(' / ');
        }
        if (action === 'custom') patch.custom = typeof args.text === 'string' ? args.text : '';

        return await enqueue(async () => {
          const cur = readPersona();
          const incoming = { enabled: true, preset: patch.preset, custom: patch.custom !== undefined ? patch.custom : cur.custom };
          const check = normalizePersonaInput(incoming, cur);
          if (!check.ok) return '设置失败：' + check.error;
          await atomicWrite(personaPath(), renderPersonaFile(check.value));
          const preset = PRESETS.find((p) => p.id === check.value.preset);
          return '已切换回复风格为「' + (preset ? preset.name : check.value.preset) + '」。已在后续模型组装中生效。';
        });
      } catch (e) { return errText(e); }
    },
  },
];

export function apply(ctx) {
  // 引导创建（不阻塞激活；失败仅告警）
  enqueue(async () => {
    await mkdir(notesDir(), { recursive: true });
    if (readFileSyncOrNull(corePath()) === null) await atomicWrite(corePath(), TEMPLATE);
    if (readFileSyncOrNull(indexPath()) === null) await atomicWrite(indexPath(), JSON.stringify({ version: 1, notes: [] }, null, 2));
  }).catch((e) => console.error('dsh-core-memory: bootstrap failed', e));

  for (const def of toolDefs) {
    ctx.tools.register(def);
  }

  // 自动召回：常驻注入每个会话的每次模型组装（同步读取，永远最新）
  ctx.systemPrompt.section({
    name: 'dsh-core-memory:recall',
    order: 60,
    text: () => buildSectionText(),
  });

  // 设置页读写接口：风格页（client.js）经同源 fetch 调用。
  // webServer 是可选依赖——缺席时插件照常工作，只是设置页不可用。
  ctx.inject(['webServer'], (c) => {
    c.effect(() => c.webServer.register({
      kind: 'exact',
      path: '/dsh-core-memory/persona',
      async handler(req, res) {
        const sendJson = (code, payload) => {
          try {
            const body = JSON.stringify(payload);
            res.statusCode = code;
            res.setHeader('content-type', 'application/json; charset=utf-8');
            res.end(body);
          } catch { /* 响应已发出时忽略 */ }
        };
        try {
          if (req.method === 'POST') {
            const body = await readBody(req);
            const patch = JSON.parse(body || '{}');
            if (!patch || typeof patch !== 'object') throw new Error('请求体必须是对象');
            const result = await enqueue(async () => {
              const cur = readPersona();
              const check = normalizePersonaInput(patch, cur);
              if (!check.ok) return { ok: false, error: check.error };
              await atomicWrite(personaPath(), renderPersonaFile(check.value));
              return { ok: true, persona: readPersona() };
            });
            if (!result.ok) { sendJson(400, { error: result.error }); return; }
            sendJson(200, { persona: result.persona, presets: listPresets() });
            return;
          }
          sendJson(200, { persona: readPersona(), presets: listPresets(), maxCustom: MAX_CUSTOM_TEXT });
        } catch (error) {
          sendJson(400, { error: (error && error.message) ? error.message : String(error) });
        }
      },
    }), 'dsh-core-memory: persona settings route');
  });

  console.log('dsh-core-memory: 已激活，记忆库 ' + join(dshHome(), 'memory'));
}
