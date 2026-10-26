/**
 * 引擎侧 HTTP header 净化垫片（由 `NODE_OPTIONS=--import` 预加载）。
 *
 * ## 治什么病
 *
 * 症状（真机截图，2026-10-25）：
 *
 * ```
 * 本轮运行失败
 * Cannot convert argument to a ByteString because the character at index 21
 * has a value of 27185 which is greater than 255.
 * ```
 *
 * 这是 **undici 的 `Headers` 构造器**抛的：HTTP header 的值必须是 ByteString
 * （每个字符 ≤ 0xFF），而某个值里混进了码点 27185（U+6A31「樱」）这种中文。
 * 报错里只有「第几个字符」，**不说哪个 header**，用户完全看不懂，也没法自查。
 *
 * ## 为什么它必须在这一层修
 *
 * header 交给 undici 的入口有**三个**，全部要在这一层堵上：
 *   ① `new Headers(...)` —— 引擎自己显式构造（如 `dsh-llm-pi-ai` 的 `discoverModels`）；
 *   ② `new Request(url, { headers })` —— **构造时就校验**，早于任何 `fetch`，
 *      只包 fetch 根本拦不住（SDK 里 `new Request` → `fetch(request)` 很常见）；
 *   ③ `fetch(url, { headers })` —— SDK 直接传普通对象
 *      （pi-ai 走 `new OpenAI({ defaultHeaders })` 就是这条）。
 * 三者最终都落在**同一个全局对象**上。在这里兜一层，等于覆盖了所有未来新增的
 * 调用方 —— 比逐个 patch 上游包更稳，也不会被引擎升级冲掉。
 *
 * ## 为什么不「直接抛个更友好的错」
 *
 * 抛错 = 用户还是发不出消息。中文出现在 header 里**一律是配置失误**
 * （比如把中文备注粘进了自定义 header），剔除它不会让请求语义变得「更错」，
 * 反而让用户先把消息发出去。所以这里**修正 + 留痕**，不是拦截。
 *
 * ## 净化规则
 *
 * - 码点 > 0xFF 的字符：剔除（这正是 ByteString 的红线）。
 * - `\r` `\n` `\0`：剔除（防 header 注入，undici 自己也会拒，但我们先动手）。
 * - 0x80–0xFF：**保留**。它们是合法 ByteString，剔除反而会破坏正常的
 *   latin1 编码值。
 *
 * 剔除是**逐字符删**而不是替换成 `?`：中文多半是误粘贴的注释尾巴，
 * 删掉后剩下的正是用户本来想填的东西。
 *
 * ## 不改什么
 *
 * 一切合法输入都走**原生快速路径**（一次正则判断后直接交给原生构造器），
 * 不产生新对象、不改写、不打日志。只有真出现非法字符时才慢一步。
 *
 * @module dsh-fetch-guard
 */

const TAG = '[dsh-fetch-guard]'

/** 需要剔除的字符：>0xFF、CR、LF、NUL。命中才走慢路径。 */
const ILLEGAL = /[\u0100-\u{10FFFF}\r\n\0]/u

/** 同一个 header 名只提示一次，避免流式请求把日志刷爆。 */
const reported = new Set()

function report(where, name, original, cleaned) {
  const key = `${where}\u0000${name}`
  if (reported.has(key)) return
  reported.add(key)
  const bad = []
  for (let i = 0; i < original.length; i += 1) {
    const code = original.charCodeAt(i)
    if (code > 0xff || code === 13 || code === 10 || code === 0) {
      bad.push(`第 ${i} 位 U+${code.toString(16).toUpperCase().padStart(4, '0')}`)
    }
  }
  const shown = original.length > 60 ? `${original.slice(0, 60)}…` : original
  process.stderr.write(
    `${TAG} ${where} 的 header "${name}" 含 HTTP 不能承载的字符（${bad.join('、')}），` +
      `已自动剔除后继续。原值: ${JSON.stringify(shown)} 净化后: ${JSON.stringify(cleaned)}\n`,
  )
}

/**
 * 净化一个 header 值。合法则**原样返回同一个字符串**（调用方可据此判等）。
 *
 * @param {unknown} value 原始值
 * @param {string} where 调用点，仅用于日志
 * @param {string} name header 名，仅用于日志
 * @returns {string} 净化后的值
 */
function cleanValue(value, where, name) {
  const text = String(value)
  if (!ILLEGAL.test(text)) return text
  let out = ''
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i)
    if (code > 0xff || code === 13 || code === 10 || code === 0) continue
    out += text[i]
  }
  report(where, name, text, out)
  return out
}

/** 净化 header 名（名字里更不该有非 ASCII）。 */
function cleanName(name, where) {
  const text = String(name)
  if (!ILLEGAL.test(text)) return text
  const out = cleanValue(text, where, text)
  return out
}

/** 已经是净化过的容器就原样放行，避免重复遍历。 */
const alreadyGuarded = new WeakSet()

/**
 * 把任意 `HeadersInit` 转成净化后的 `HeadersInit`。
 *
 * 支持 `undefined` / `null` / 普通对象 / 数组 / 可迭代 pairs / `Headers` 实例。
 *
 * @param {unknown} init
 * @param {string} where
 * @returns {unknown}
 */
function guardInit(init, where) {
  if (init === undefined || init === null) return init
  if (alreadyGuarded.has(init)) return init

  /** 收集成 [name, value] 数组，再统一净化。 */
  let pairs
  if (typeof init[Symbol.iterator] === 'function') {
    pairs = [...init]
  } else if (typeof init === 'object') {
    pairs = Object.entries(init)
  } else {
    return init
  }

  let dirty = false
  const cleaned = pairs.map((pair) => {
    if (!Array.isArray(pair) || pair.length < 2) return pair
    const name = cleanName(pair[0], where)
    const value = cleanValue(pair[1], where, String(pair[0]))
    if (name !== String(pair[0]) || value !== String(pair[1])) dirty = true
    return [name, value]
  })

  // 一个非法字符都没有 ⇒ 返回原对象，完全等价于原生行为。
  if (!dirty) return init
  alreadyGuarded.add(cleaned)
  return cleaned
}

/**
 * 安装垫片。可重复调用（幂等）。
 */
export function install() {
  const NativeHeaders = globalThis.Headers
  if (typeof NativeHeaders !== 'function') return
  if (NativeHeaders.__dshGuarded === true) return

  /**
   * 包装后的 `Headers`：构造与 `set`/`append` 都过一遍净化。
   *
   * 用 `class extends` 而不是 Proxy：`instanceof Headers` 仍然成立，
   * 上游代码里的类型判断不会因此走偏。
   */
  class GuardedHeaders extends NativeHeaders {
    constructor(init) {
      super(guardInit(init, 'Headers 构造'))
    }

    set(name, value) {
      return super.set(cleanName(name, 'Headers.set'), cleanValue(value, 'Headers.set', String(name)))
    }

    append(name, value) {
      return super.append(cleanName(name, 'Headers.append'), cleanValue(value, 'Headers.append', String(name)))
    }
  }

  Object.defineProperty(GuardedHeaders, 'name', { value: 'Headers', configurable: true })
  GuardedHeaders.__dshGuarded = true
  globalThis.Headers = GuardedHeaders

  // ── Request 兜底 ──
  //
  // ⚠️ **这一层不能省**（2026-10-26 实测发现的缺口）：
  // `new Request(url, { headers: { 'x-foo': '中文' } })` 在**构造时**就把 headers
  // 交给 undici 校验，抛错发生在 `fetch` 之前 —— 只包装 `Headers` 和 `fetch`
  // 根本拦不住它。而 SDK 里 `new Request(...)` 再 `fetch(request)` 是常见写法。
  //
  // 实测（未装本层时）：`new Request('http://x/', { headers: { 'x-test': 'a'*21+'樱' } })`
  // 直接抛与用户截图完全相同的 `Cannot convert argument to a ByteString ...`。
  const NativeRequest = globalThis.Request
  if (typeof NativeRequest === 'function' && NativeRequest.__dshGuarded !== true) {
    const GuardedRequest = class Request extends NativeRequest {
      constructor(input, init) {
        // 只碰 `init.headers`；`input` 是 URL 字符串或另一个 Request，
        // 后者内部已是合法 Headers（非法的话它自己构造时就抛了）。
        if (init !== undefined && init !== null && init.headers !== undefined) {
          init = { ...init, headers: guardInit(init.headers, 'Request 构造') }
        }
        super(input, init)
      }
    }
    Object.defineProperty(GuardedRequest, 'name', { value: 'Request', configurable: true })
    GuardedRequest.__dshGuarded = true
    globalThis.Request = GuardedRequest
  }

  // ── fetch 兜底 ──
  //
  // 包装 `Headers` 能覆盖「用 new Headers 构造」的路径，但 SDK 里还有直接
  // 传普通对象 `{ 'x-foo': '中文' }` 的写法（openai SDK 的 defaultHeaders 就是）。
  // 那种情况不经过 `new Headers`，得在 fetch 入口再兜一层。
  const nativeFetch = globalThis.fetch
  if (typeof nativeFetch === 'function' && nativeFetch.__dshGuarded !== true) {
    const guardedFetch = function fetch(input, init) {
      if (init !== undefined && init !== null && init.headers !== undefined) {
        init = { ...init, headers: guardInit(init.headers, 'fetch') }
      }
      // `fetch(request)` 形式：Request 在本层装好后已被 GuardedRequest 净化过，
      // 能走到这里说明 headers 已合法。不拆包重建 —— 重建会丢掉 body 流状态。
      return nativeFetch.call(this, input, init)
    }
    guardedFetch.__dshGuarded = true
    globalThis.fetch = guardedFetch
  }
}

install()
