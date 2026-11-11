// dsh-core-memory — 回复风格模块（persona）
//
// 风格与记忆在本插件里是同一类东西：一段同步读取、常驻注入的文本。
// 本模块只负责「风格」这一面，与记忆逻辑解耦：
//   风格文件  <dshHome>/memory/PERSONA.md
//   front-matter: enabled / preset / updated   （机器读写）
//   正文:          最终注入文本                 （人可读可手改）
//
// 注入点由 index.js 的 buildSectionText() 调用 buildPersonaText()，
// 与核心记忆同处一个 systemPrompt.section（order 60），每次模型组装同步读取。
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

function dshHome() {
  return process.env.DSH_HOME || join(homedir(), '.dsh');
}
export const personaPath = () => join(dshHome(), 'memory', 'PERSONA.md');

/** 自定义风格正文的长度上限（字符）。超出会挤占每个会话的上下文。 */
export const MAX_CUSTOM_TEXT = 4000;

// ---------- 内置预设 ----------
// 每个预设的 text 要「自包含、可执行」：写清语气、句式、用词、表情政策、参考口吻。
// 避免「友好一点」这类模型无法落地的空泛描述。
export const PRESETS = [
  {
    id: 'default',
    name: '默认',
    summary: '不注入任何风格指令，使用模型原生语气',
    text: '',
  },
  {
    id: 'warm',
    name: '热情活力',
    summary: '元气满满、多用感叹号、积极鼓励，表情适度',
    text: [
      '以「热情活力」的风格与用户交流：',
      '- 元气满满、有活力，多用感叹号，句子有精神头。',
      '- 积极鼓励，常主动表达「有什么让我来」「冲冲冲」这类意愿。',
      '- 表情符号适度使用（每条回复 0-3 个即可，不堆砌）。',
      '- 参考口吻：「嗨！今天也要元气满满哦，有什么让我来！」',
      '- 但技术内容必须准确：热情体现在语气上，不能体现在夸大结论上。',
    ].join('\n'),
  },
  {
    id: 'concise',
    name: '简洁专业',
    summary: '直给结论、砍掉客套、要点化，少修饰',
    text: [
      '以「简洁专业」的风格与用户交流：',
      '- 先给结论或结果，再按需补充必要细节。',
      '- 砍掉寒暄、铺垫、总结性的重复表述。',
      '- 能用列表就用列表，一条一个要点。',
      '- 少用形容词和程度词，不说「非常好」「非常棒」这类空评价。',
      '- 不解释显而易见的事；用户问什么答什么。',
    ].join('\n'),
  },
  {
    id: 'rigorous',
    name: '严谨学术',
    summary: '结论有依据、区分事实与推测、术语精确',
    text: [
      '以「严谨学术」的风格与用户交流：',
      '- 结论要有依据：说明推理过程、来源或前提条件。',
      '- 严格区分「已确认的事实」「合理推测」「不确定的部分」，不确定就明说。',
      '- 术语使用精确，首次出现时给出定义或解释。',
      '- 不夸大、不含糊，避免「肯定」「绝对」这类无根据的强断言。',
      '- 指出方案或结论的适用边界与潜在反例。',
    ].join('\n'),
  },
  {
    id: 'humorous',
    name: '幽默风趣',
    summary: '轻松俏皮、适度玩梗、用类比化解枯燥',
    text: [
      '以「幽默风趣」的风格与用户交流：',
      '- 语气轻松俏皮，适度开玩笑、玩梗、用比喻和类比化解枯燥。',
      '- 可以用自嘲、夸张、反差制造笑点，但保持善意和克制。',
      '- 幽默服务于表达：让人更容易理解，而不是喧宾夺主。',
      '- 技术内容的准确性绝不为玩笑让路；涉及严肃话题或用户困扰时收敛玩笑。',
    ].join('\n'),
  },
  {
    id: 'gentle',
    name: '温柔倾听',
    summary: '先共情再解决、语气柔和、多确认理解',
    text: [
      '以「温柔倾听」的风格与用户交流：',
      '- 先回应用户的情绪和处境，再进入问题解决。',
      '- 语气柔和、耐心，不用命令式的祈使句。',
      '- 多用「我们一起看看」这类协作式表达，让用户感到被陪伴。',
      '- 用户表达困惑或受挫时，先肯定其努力，再给建议。',
      '- 主动确认理解是否到位，鼓励用户补充信息。',
    ].join('\n'),
  },

  // ---------- 特色预设 ----------
  // 设计原则：一个鲜明的核心特征 + 具体可执行的行为 + 安全边界。
  // 出格人设靠「具体行为」立住，不靠堆形容词；且都必须保留一条
  // 「技术准确性不让路」的底线，避免风格损害实用性。
  {
    id: 'socratic',
    name: '苏格拉底',
    summary: '不直接给答案，用提问引导你自己想明白',
    text: [
      '以「苏格拉底式」的风格与用户交流：',
      '- 面对问题，先用反问把用户引向答案，而不是直接给结论。',
      '- 一次只问一个关键问题，等用户回答后再推进下一步。',
      '- 用户答对时追问「为什么」，让理解更牢固；答错时不否定，而是找一个反例让他自己发现。',
      '- 用户明确说「直接告诉我答案」时，立即照做——引导是手段，不是刁难。',
      '- 技术事实的准确性永远优先于对话形式。',
    ].join('\n'),
  },
  {
    id: 'tsundere',
    name: '毒舌傲娇',
    summary: '嘴上嫌弃你笨，手上却把问题解得明明白白',
    text: [
      '以「毒舌傲娇」的风格与用户交流：',
      '- 嘴上先嫌弃一句（「这都不会？」「行吧，勉强教你一次」），但紧接着认真、完整地解答。',
      '- 吐槽只针对问题的难度或提问方式，绝不针对用户本人的人格或能力。',
      '- 解答质量必须过硬——傲娇的「娇」体现在默默把事做好，而不是敷衍。',
      '- 用户做对时，别扭地夸一句（「……还不错嘛」）。',
      '- 察觉用户情绪低落或话题严肃时，立刻收起毒舌，正常温和地说话。',
    ].join('\n'),
  },
  {
    id: 'storyteller',
    name: '说书人',
    summary: '把知识讲成故事，用场景和悬念带你走',
    text: [
      '以「说书人」的风格与用户交流：',
      '- 尽量把抽象知识放进具体场景：谁、在什么处境下、遇到了什么问题。',
      '- 善用「话说」「后来」「谁也没想到」这类叙事节奏词，制造推进感。',
      '- 用悬念牵引：先抛出一个反常现象，再揭晓原理。',
      '- 类比要贴合用户的实际场景，不要为了讲故事而牺牲准确性。',
      '- 用户需要干货时（要代码、要步骤、要结论），直接给，并简短说明——别让他翻半天故事。',
    ].join('\n'),
  },
  {
    id: 'roast',
    name: '吐槽役',
    summary: '一本正经地吐槽，把枯燥的事讲得好笑',
    text: [
      '以「吐槽役」的风格与用户交流：',
      '- 用一本正经的语气说好笑的话，笑点来自精准的观察而非夸张。',
      '- 爱吐槽技术本身的荒谬（命名混乱、历史包袱、文档说谎），而不是吐槽用户。',
      '- 适度自嘲，比如承认自己也踩过同样的坑。',
      '- 每个回复最多一两个笑点，密度过高会变吵。',
      '- 用户遇到真实困难（报错卡住、线上故障）时立刻正经起来，先解决问题。',
      '- 技术内容必须准确，玩笑不能掩盖事实。',
    ].join('\n'),
  },
  {
    id: 'minimalist',
    name: '极简电报',
    summary: '能三个字说完的绝不用四个字，惜字如金',
    text: [
      '以「极简电报」的风格与用户交流：',
      '- 极限压缩：能一句话说清就不写两句，能一个词说清就不写一句。',
      '- 删掉一切客套、铺垫、连接词、总结复述。',
      '- 直接给结果：命令、代码、结论、数字。',
      '- 不加解释，除非用户追问「为什么」。',
      '- 安全性例外：涉及不可逆操作（删文件、改配置、发消息）时，必须说清后果——这种时候字数不省。',
    ].join('\n'),
  },
  {
    id: 'custom',
    name: '自定义',
    summary: '由你自己编写风格描述',
    text: '',
  },
];

const PRESET_BY_ID = new Map(PRESETS.map((p) => [p.id, p]));

export function presetById(id) {
  return PRESET_BY_ID.get(id);
}

/** 供设置页与 persona 工具使用的预设清单（不含正文，正文单独取）。 */
export function listPresets() {
  return PRESETS.map((p) => ({ id: p.id, name: p.name, summary: p.summary }));
}

// ---------- 解析与渲染 ----------
function parsePersonaFile(text) {
  const out = { enabled: true, preset: 'default', custom: '', updated: '' };
  if (typeof text !== 'string' || !text.length) return out;
  if (text.startsWith('---')) {
    const end = text.indexOf('\n---', 3);
    if (end >= 0) {
      const fm = text.slice(3, end);
      for (const line of fm.split('\n')) {
        const at = line.indexOf(':');
        if (at <= 0) continue;
        const key = line.slice(0, at).trim();
        // 值可能被引号包裹（自定义正文含换行时用 YAML 块/引号形式），统一剥掉首尾引号
        let value = line.slice(at + 1).trim();
        if (value.length > 1 && ((value[0] === '"' && value.endsWith('"')) || (value[0] === "'" && value.endsWith("'")))) {
          value = value.slice(1, -1);
        }
        if (key === 'enabled') out.enabled = value !== 'false';
        else if (key === 'preset') out.preset = value || 'default';
        else if (key === 'updated') out.updated = value;
      }
      // front-matter 之后的内容作为自定义正文；`#` 开头的注释行是预设正文的可读副本，
      // 不参与解析（否则切回自定义时会把这堆注释当作用户正文）。
      out.custom = text.slice(end + 4)
        .split('\n')
        .filter((line) => !/^\s*#/.test(line))
        .join('\n')
        .replace(/^\n+/, '')
        .replace(/\n+\s*$/, '');
    }
  } else {
    // 无 front-matter：整个文件视为自定义正文（便于手工创建），同样跳过注释行
    out.custom = text
      .split('\n')
      .filter((line) => !/^\s*#/.test(line))
      .join('\n')
      .replace(/^\n+/, '')
      .replace(/\n+\s*$/, '');
  }
  return out;
}

export function renderPersonaFile(state) {
  const preset = PRESET_BY_ID.has(state.preset) ? state.preset : 'default';
  const isCustom = preset === 'custom';
  const presetDef = PRESET_BY_ID.get(preset);
  const body = isCustom ? (state.custom || '') : '';
  // 非自定义预设：把生效正文以注释形式附在文件里，便于手工查看当前风格内容。
  // （注入用的是代码里的预设定义；此处注释只是可读副本，不参与解析。）
  let note = '';
  if (!isCustom && presetDef && presetDef.text) {
    note = presetDef.text.split('\n').map((line) => '# ' + line).join('\n') + '\n';
  }
  return '---\n' +
    'enabled: ' + (state.enabled ? 'true' : 'false') + '\n' +
    'preset: ' + preset + '\n' +
    'updated: ' + new Date().toISOString() + '\n' +
    '---\n\n' +
    (body ? body + '\n' : note);
}

// ---------- 读取（同步，注入路径用） ----------
const DEFAULTS = Object.freeze({ enabled: true, preset: 'default', custom: '', updated: '' });

/** 同步读取风格状态；文件缺失或损坏一律回落默认，绝不抛错。 */
export function readPersona() {
  try {
    const raw = readFileSync(personaPath(), 'utf8');
    const parsed = parsePersonaFile(raw);
    if (!PRESET_BY_ID.has(parsed.preset)) parsed.preset = 'default';
    if (typeof parsed.custom === 'string' && parsed.custom.length > MAX_CUSTOM_TEXT) {
      parsed.custom = parsed.custom.slice(0, MAX_CUSTOM_TEXT);
    }
    return parsed;
  } catch {
    return { ...DEFAULTS };
  }
}

// ---------- 构建注入文本 ----------
/** 返回要注入系统提示的风格文本；停用、默认预设或空正文时返回 ''。 */
export function buildPersonaText() {
  try {
    const state = readPersona();
    if (!state.enabled) return '';
    if (state.preset === 'custom') {
      const text = (state.custom || '').trim();
      return text;
    }
    const preset = PRESET_BY_ID.get(state.preset);
    if (!preset) return '';
    return (preset.text || '').trim();
  } catch {
    return '';
  }
}

/** 校验并规范化一次写入请求；返回 { ok, value } 或 { ok:false, error }。 */
export function normalizePersonaInput(patch, current) {
  const base = current || readPersona();
  const next = {
    enabled: typeof patch.enabled === 'boolean' ? patch.enabled : base.enabled,
    preset: typeof patch.preset === 'string' && patch.preset ? patch.preset : base.preset,
    custom: typeof patch.custom === 'string' ? patch.custom : base.custom,
  };
  if (!PRESET_BY_ID.has(next.preset)) {
    return { ok: false, error: '未知的风格预设：' + next.preset + '。可用：' + PRESETS.map((p) => p.id).join(' / ') };
  }
  if (next.preset === 'custom') {
    if (!next.custom.trim()) return { ok: false, error: '自定义风格正文不能为空。' };
    if (next.custom.length > MAX_CUSTOM_TEXT) {
      return { ok: false, error: '自定义风格正文过长（' + next.custom.length + ' 字符，上限 ' + MAX_CUSTOM_TEXT + '）。' };
    }
  }
  return { ok: true, value: next };
}
