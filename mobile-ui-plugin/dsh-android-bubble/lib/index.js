/**
 * dsh-android-bubble —— 把「改悬浮窗」暴露成一个**模型可调用的工具**。
 *
 * ## 为什么需要这一层
 *
 * 悬浮球的原生侧早已有完整的外观接口（`FloatingBubble.applyAppearance()` /
 * `currentAppearanceJson()`），页面侧插件也能经 `window.DSHAndroid.bubbleApply()`
 * 调它。但那条路对**模型**是关着的：
 *
 * | 谁 | 能不能改悬浮窗 | 为什么 |
 * |---|---|---|
 * | 页面侧插件（`lib/client.js`） | ✅ | 浏览器里直接摸到 `window.DSHAndroid` |
 * | 模型（AI） | ❌ | 它的工具跑在 **Node 进程**里，那里没有 `window` |
 *
 * 于是「让 AI 改悬浮窗，像改自己的 web 界面一样简单」这句话在**可发现性**上是假的：
 * 模型既不知道有这个能力，也没有任何途径触达它。本插件补的就是这一段 ——
 * 注册一个 `bubble` 工具，让它出现在工具列表里、且真的能改到原生那个球。
 *
 * ## 跨进程怎么打通
 *
 * 引擎 node 是 App 进程的**子进程**，两者共享 app 私有目录（SELinux 同域），
 * 所以走**文件信箱**：本插件把命令写进 `$DSH_HOME/bubble-cmd.json`，原生侧
 * `BubbleCommandBridge` 轮询读走、执行、把结果写回 `$DSH_HOME/bubble-result.json`。
 *
 * 这与项目里 `adb-wireless-port` 那条通道同构（Kotlin 写、Node 读），只是方向反过来。
 * **不选 socket**：Android 的 app 域不让进程开命名管道（见 AGENTS.md 的沙箱说明），
 * 而 AF_UNIX 抽象命名空间是可选实现；文件信箱在两个方向上都已经在本项目里验过。
 *
 * ⚠️ 请求带自增 `id`，结果按 id 配对 —— 否则「两个工具调用并发、后一个的结果被
 *   前一个读走」会让模型拿到张冠李戴的回执。
 *
 * ## 环境变量
 *
 * - `DSH_HOME`（必需）：文件信箱的落点。缺失或目录不可写时**不注册工具** ——
 *   注册一个必然失败的工具比不注册更糟，模型会反复重试。
 *
 * @module dsh-android-bubble
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const name = 'dsh-android-bubble';

/**
 * 只要 `tools`：本插件不碰 shell，不需要执行体。
 * @type {string[]}
 */
export const inject = ['tools'];

/** 工具名。与内置工具不冲突（实测 30 个内置工具名里没有它）。 */
const TOOL_NAME = 'bubble';

/** 文件信箱里三个文件名，原生侧 `BubbleCommandBridge` 必须与这里一致。 */
const CMD_FILE = 'bubble-cmd.json';
const RESULT_FILE = 'bubble-result.json';

/** 等待原生侧回执的上限。原生轮询间隔 250ms，留足余量即可。 */
const WAIT_TIMEOUT_MS = 4000;

/** 轮询回执的间隔。 */
const POLL_INTERVAL_MS = 60;

/**
 * 轮播帧数上限。与原生侧 `BubbleFrames.MAX_FRAMES` 必须一致。
 *
 * 超了不是"少几帧"而是静默丢尾巴，用户会觉得轮播"播到一半就断了"，
 * 所以这里先截断并在结果里说明。
 */
const MAX_FRAMES = 64;

/**
 * 一批轮播帧的 base64 总字节上限。
 *
 * ⚠️ **这条限制是防内存爆的，不是防磁盘的。** 帧以 data URI 形式进 JSON，
 *   原生侧 `BubbleCommandBridge` 要 `readText()` 整个文件再 `JSONObject(...)`；
 *   64 帧 × 每帧 700 KB ≈ 45 MB 的字符串在 Android 上足以让 App 被 LMK 杀掉，
 *   而症状是"悬浮球突然消失、什么都没发生"—— 极难归因。
 *
 * 6 MB 能装下 64 张 ~90 KB 的 PNG（对 100dp 的球绰绰有余）。
 * 超了就在插件侧明确报错，让模型自己缩小图片或减少帧数。
 */
const MAX_FRAMES_BASE64 = 6 * 1024 * 1024;

/**
 * 动作数量上限。与原生侧 `BubbleFrames.MAX_ACTIONS` 必须一致。
 *
 * 桌面宠物通常 3–5 个动作（待机/思考/干活/等待/出错），24 个够用很久。
 */
const MAX_ACTIONS = 24;

/**
 * **一次调用里全部动作**的 base64 总预算。
 *
 * ⚠️ 与 [MAX_FRAMES_BASE64] 是两个口径：那个是"一个动作的帧"，这个是"这次调用
 *   要下发的全部帧"。只查前者的话，宠物一次配 5 个动作、每个 5 MB，合计 25 MB
 *   会照样过闸 —— 而原生侧那一次 `readText()` 就足以被 LMK 杀掉。
 */
const ACTION_BUDGET_BASE64 = 12 * 1024 * 1024;

/**
 * 合法的运行状态名。与原生侧 `BubbleAppearance.RUN_STATES` 必须一致。
 *
 * ⚠️ 多一个、少一个都不会报错，只会**静默不生效**（原生 `actionMapOf` 会把
 *   未知状态整条丢掉）—— 那种"配了但宠物不按它动"最难查。
 *
 * ⚠️ **顺序就是这个数组的顺序，不是字母序**：`workStatusTexts` / `statusImages`
 *   都是**按档位下标**索引的（第 0 档 = idle，第 1 档 = thinking …）。
 *   原生侧的新状态是**追加在末尾**的（`result` / `success`），正是为了不改动
 *   前 5 档的下标 —— 老配置的文案不会错位。往中间插一个状态会让所有老配置的
 *   文案整体串档，而且**不报任何错**。
 */
const RUN_STATES = ['idle', 'thinking', 'working', 'waiting', 'error', 'result', 'success'];

/**
 * 池名/动作名的合法字符集。与原生侧 `BubbleAppearance.normalizePoolName` 必须一致。
 */
const POOL_NAME_RE = /^[a-z0-9_-]{1,24}$/;

/** 动作池数量上限。与原生侧 `BubbleAppearance.MAX_POOLS` 必须一致。 */
const MAX_POOLS = 24;

/** 单个池里的动作数上限。与原生侧 `BubbleAppearance.MAX_POOL_ACTIONS` 必须一致。 */
const MAX_POOL_ACTIONS = 64;

/** 每档状态文案的句数上限。与原生侧 `MAX_STATUS_TEXTS_PER_TIER` 必须一致。 */
const MAX_STATUS_TEXTS_PER_TIER = 8;

/** 单句状态文案的长度上限。与原生侧 `MAX_STATUS_TEXT_CHARS` 必须一致。 */
const MAX_STATUS_TEXT_CHARS = 60;

/** 状态配图的档数上限。与原生侧 `BubbleAppearance.MAX_STATUS_IMAGES` 必须一致。 */
const MAX_STATUS_IMAGES = 7;

/** 每档状态配图的数量上限。 */
const MAX_STATUS_IMAGES_PER_TIER = 8;

/** 状态配图的边长上限（dp）。与原生侧 `MAX_STATUS_IMAGE_DP` 必须一致。 */
const MAX_STATUS_IMAGE_DP = 240;

/** 单张状态图的 base64 长度上限。与原生侧 `BubbleStatusImages.MAX_IMAGE_BASE64` 必须一致。 */
const MAX_STATUS_IMAGE_BASE64 = 4 * 1024 * 1024;

/**
 * **一次调用里全部状态图**的 base64 总预算。
 *
 * ⚠️ 与 `ACTION_BUDGET_BASE64` 同理：只查单张的话，7 档 × 每档 3 张 × 3 MB
 *   合计 60 MB 会照样过闸，而原生侧那一次 `readText()` 就足以被 LMK 杀掉。
 *   状态图是**头顶小图**，正常一张只有几十 KB，8 MB 已经很宽松。
 */
const STATUS_IMAGE_BUDGET_BASE64 = 8 * 1024 * 1024;

/** 单张状态图的数量上限（磁盘上保留的总数）。与原生侧 `BubbleStatusImages.MAX_FILES` 同口径。 */
const MAX_STATUS_IMAGE_FILES = 64;

/**
 * 模型可见的工具描述 —— **这是本插件最要紧的一段文本**。
 *
 * 三件事必须写死，理由与 `dsh-android-shell` 同：
 * ① 什么场景该用（否则模型不知道有这个能力）；
 * ② 字段有哪些、取值范围是多少（否则模型会猜字段名，静默无效）；
 * ③ 改了之后**用户能不能立刻看到**（前台/后台的差别是反直觉的，不写清楚
 *    模型会以为「改了没生效」而反复重试）。
 */
const DESCRIPTION = `Change the Android floating bubble (悬浮球) of this app — its state, size, shape, colours, panel, avatar and animation. This is the only way for you to affect that floating window; a page-side plugin can call \`window.DSHAndroid.bubbleApply()\`, but you run in the Node process and cannot.

Use it when the user asks to change / restyle / resize / hide the floating ball, or asks you to "change your floating window". Also use \`action:"get"\` first when the user wants a modification relative to the current look, so you do not silently reset their settings.

## The bubble is layered — think in layers, not in presets

    shape layer   : the background outline + its fill + border
    content layer : one image on top (avatar / carousel frame / sprite cell)
    motion layer  : rotation / breathing

Each layer has its own opacity and they multiply, so almost any visual request maps onto
a combination. \`opacity\` is the global multiplier on top of both.

## Actions

- \`action:"set"\` — apply the fields below (default).
- \`action:"get"\` — read the current appearance back as JSON.
- \`action:"notify"\` — push a message into the status strip above the ball (\`text\`, \`durationMs\`, \`sticky\`). This does NOT change any persisted setting; it is you *saying something* to the user. Use it to report progress, or to answer when the user only wants a short notice.

## Fields (all optional; merged onto the current appearance — pass only what you change)

Shape layer:
- \`state\`: "expanded" | "collapsed" | "hidden" | "auto". "auto" (default) shows the ball only while the app is in the background; "expanded"/"collapsed" pin it visible in the foreground too. "hidden" hides it entirely.
- \`shape\`: "circle" | "square" | "rounded" | "path"
- \`shapePath\`: an SVG path \`d\` string — used when \`shape\` is "path", and this is how you give the ball ANY outline (star, heart, hexagon, speech bubble, logo). Coordinates are in \`shapeViewBox\` units, not dp, so the same path scales to any \`size\`. Example five-pointed star, viewBox "0 0 100 100": \`M50 3 L61 38 L98 38 L68 60 L79 95 L50 73 L21 95 L32 60 L2 38 L39 38 Z\`. Max 4000 characters. Invalid paths fall back to a circle.
- \`shapeViewBox\`: "minX minY width height", default "0 0 100 100".
- \`size\`: ball diameter in dp, 24–320. When the canvas is auto-sized, this is its LONG edge.
- \`boxWidth\` / \`boxHeight\`: the canvas (background box / window) size in dp, 16–400. \`0\` (the default) means AUTO: the canvas fits the current subject material — its long edge becomes \`size\` and the short edge follows the material's aspect ratio. So a square avatar/video gives the classic square ball, and a wide sprite sheet gives a wide box. Give only ONE of the two and the other still follows the material. Empty space around the subject is transparent (use \`bgOpacity:0\` to drop the visible background box entirely).
- \`showDot\`: boolean, default **false**. The small status dot in the bottom-right corner; it overlaps the pet artwork, so it is off unless you want it.
- \`cornerRadius\`: dp, 0–80 (only meaningful for "rounded")
- \`bg\` / \`bg2\`: "#RRGGBB". Together they make a vertical gradient; \`bg\` alone is a solid colour. Also accepts "#AARRGGBB" if you want per-colour alpha.
- \`border\` ("#RRGGBB") and \`borderWidth\` (dp, 0–8)
- \`bgOpacity\`: 0–1, applies to the shape layer ONLY. \`0\` = fully transparent background, i.e. only the image is visible — this is the "transparent background, opaque image" request.
- \`opacity\`: 0.05–1, global multiplier over everything.

Content layer:
- \`avatar\`: "default" (built-in portrait) | "none" (no image at all — background shape only) | a \`data:image/...;base64,...\` URI
- \`avatarFile\`: an absolute path to an image readable from this process; the file is read and sent as the avatar. Takes precedence over \`avatar\`. Prefer this over hand-building a data URI.
- \`avatarOpacity\`: 0–1, applies to the content layer ONLY. Combine with \`bgOpacity\` to fade the picture while keeping the background solid, or the reverse.
- \`avatarScale\`: 0.2–2.0. Values above 1 overflow the ball and get clipped by the shape — that is how you crop/zoom a picture.
- \`frameFiles\`: an array of absolute image paths — **an animated carousel**. Each file is one frame, played in order and looping. Up to 64 frames, each up to ~500 KB. Pass \`[]\` to clear the carousel and go back to a single image.
- \`frameDurations\`: optional array of per-frame durations in ms (same order as \`frameFiles\`), 20–60000. Omit to use \`frameIntervalMs\` for every frame.
- \`frameIntervalMs\`: uniform frame duration in ms, 20–60000 (default 400).
- \`frameLoop\`: how many full cycles to play; -1 (default) = forever, 0 = forever too, N>0 = stop on the last frame after N cycles.
- \`spriteRows\` / \`spriteCols\`: **sprite sheet mode**. Pass ONE file in \`frameFiles\` that is a grid of \`rows × cols\` equally-sized cells; the plugin treats each cell as a frame, in row-major order. This is how you animate a character with a single image. Setting these with no new \`frameFiles\` re-slices the sheet that is already stored.
- \`video\`: absolute path to a **transparent WebM** — the highest-quality content layer. This is how you reproduce a dsh-pet style pet: each action is a real animation (idle breathing, a throw, a landing squash) instead of a few still frames. Videos take priority over \`frameFiles\`, so you can ship both (the frames act as a fallback). Up to ~6 MB per file; VP9 with alpha is what dsh-pet uses and it plays correctly here. Pass \`""\` to delete this action's video. ⚠️ Prefer passing a PATH over inlining base64 — a single webm is 200 KB–1.3 MB, and base64 inflates it by another 33%.
- \`throwEnabled\`: boolean (default true) — whether flinging the ball makes it fly, bounce off the screen edges, squash on landing and roll to a stop.

Motion layer:
- \`spin\`: degrees per second, -720–720 (negative = counter-clockwise), 0 = no rotation. Applied to the content layer, so the outline stays put.
- \`pulse\`: boolean, a breathing scale animation on the content layer.

Panel and status:
- \`panelWidth\` (dp) and \`panelHeight\` (dp, 0 = fit content)
- \`panelBg\` / \`panelText\` / \`panelAccent\`: "#RRGGBB" for the expanded panel
- \`statusAutoHideMs\`: how long the status strip stays after its content stops changing; 0 disables auto-hide
- \`edgeSnap\`: boolean, default **false**. When false the ball **stays exactly where the
  user drops it** — screen centre, anywhere. When true it is pulled to the nearest left/right
  edge after every drag. Dragging the ball *off* the screen always collapses it into an edge
  sliver regardless of this flag, because that is an explicit gesture.
- \`throwEnabled\`: boolean, default true. Flick the ball and it **flies, bounces off all four
  walls and the floor, and comes to rest where it lands** (no snap-back). This is the desktop
  pet feel; set false for the old "drop and stick to the edge" behaviour.

### The edge sliver (the state after the ball is dragged off-screen)

Drag the ball past the screen edge and it collapses into a **sliver pinned to that edge**.
That sliver is a first-class, fully editable surface — not a fixed 6dp strip:

- \`edgeImage\`: "default" (built-in peeking character) | "none" (plain colour sliver, the old
  behaviour) | "custom" | a \`data:image/...;base64,...\` URI | \`edgeFile\` (an absolute path,
  read and sent like \`avatarFile\`). Default is "default".
- \`edgeWidth\`: visible width in dp, 6–120, default **28**. The old build hard-coded 6dp, which
  users reported as *too small to tap* — that is why 28 is the new default.
- \`edgeHeight\`: visible height in dp, 0 (default) = follow the ball's canvas height, max 400.
- \`edgeOpacity\`: 0–1, default 1.
- \`edgeAnchor\`: 0–1 fixes the sliver's vertical position (0 = top, 1 = bottom) so the peeking
  character always appears at the same height. \`-1\` (default) keeps whatever y the ball had
  when it was dragged out.

The image is drawn with \`fitCenter\`, so a tall peeking character is never squashed; the
letterboxed area stays transparent and **is still part of the tappable region**.

## Desktop pet: one animation per state

A single frame set can only say "this ball animates". To make the ball a **pet that
reacts to what you are doing**, register several *named* actions and map run states
to them:

1. \`actions: {"idle": {...}, "work": {...}, "wait": {...}}\` — each spec is a
   frame set (same fields as \`frameFiles\` + \`spriteRows\`/\`spriteCols\`/\`frameIntervalMs\`/
   \`frameDurations\`/\`frameLoop\`, but named), **or** a \`video\` for real animation.
   Names: a–z 0–9 \`-\` \`_\`, max 24 chars. An empty \`frames\` array deletes that
   action. \`action:"get"\` returns the \`actions\` list that actually exists on disk,
   plus \`videoActions\` for the ones that have a video.
2. \`actionMap: {"thinking":"think","working":"work","waiting":"wait","error":"sad"}\` —
   the pet then switches animation **by itself** as your run state changes. States:
   \`idle\` (not running), \`thinking\` (you are generating), \`working\` (a tool/command is
   executing), \`waiting\` (waiting for the user — approval or input), \`error\` (last turn
   failed). **An unmapped state falls back to your \`idle\` action**, so mapping only
   \`idle\` already keeps the pet visible in every state — without that fallback the pet
   would flip to the built-in static avatar as soon as you start working, which looks
   broken. (Only if \`idle\` is unmapped too does the state fall back to the avatar.)
3. \`action:"state"\` with \`runState\` (and optionally \`playAction\`) reports the state
   right now **without saving anything** — this is how a state-bridge plugin drives the
   pet from engine events. \`action:"notify"\` still works for one-off lines.

### Make it *alive*: pools, weights, status lines, walking

Steps 1–3 give you a pet that changes animation when *you* tell it to. These four
fields give you one that behaves like a pet on its own:

4. **\`pools\`** — group your actions into named pools and let the app drive them.
   Four names are special:
   - \`idle\` — the **random idle chain**: whenever nothing is running, the pet picks
     one of these at random and plays it (one-shot actions chain on their own; a
     looping one is re-rolled every 8 s). This is what makes it stop looking frozen.
   - \`click\` — **tap response**. ⚠️ Configuring this pool *changes the interaction*:
     a single tap now pets the pet instead of opening the panel, and **the panel
     moves to a long-press**. Don't configure it if the user mainly taps the ball to
     open the panel.
   - \`drag\` — the pose shown while the user drags the pet (e.g. a "being picked up"
     animation). Without it, dragging just moves the ball.
   - \`move\` — the pose used while the pet walks on its own (needs \`moveEnabled\`).
   - \`turn\` — the pose played when the pet changes facing. **The action itself does
     not flip anything**: the flip happens when it finishes playing, and only if
     \`flipOnTurn\` is on (default). Give \`turn\` a weight in \`poolWeights\` (e.g.
     \`{"idle":10,"turn":2}\`) or it will never be picked.
   - \`work-<state>\` (or \`state-<state>\`) — a **per-state pool**, e.g.
     \`work-thinking\`: \`{"work-thinking":["think-a","think-b"]}\`. This is the upgrade
     of \`actionMap\`: instead of one fixed action per state, the pet re-rolls a
     different one **every time it enters that state**. If both are configured the
     pool wins.
   - Any other pool name (\`celebrate\`, \`sleep\`, …) is yours: map it with \`actionMap\`
     or play it by name. It costs nothing to declare.
5. **\`poolWeights\`** — \`{"idle":10}\`. The \`idle\` weight drives the random chain:
   **0 turns it off** (the pet then just stays on whatever \`actionMap\` says — that is
   the "I want a calm ball" setting), and omitting it defaults to 10. \`turn\` is the
   second weight that does something: see \`turn\` above. Other pool names are accepted
   and ignored, so you can send a full weight table without error.
6. **\`workStatusTexts\`** — what the status bubble says, per state, in run-state order
   \`[idle, thinking, working, waiting, error, result, success]\`. Give several lines per
   state and one is picked at random each time the state changes:
   \`[["在发呆"],["正在想…"],["正在干活…"],["等你确认"],["出错了"]]\`.
   This is how you give the pet a *voice* without any LLM call — the lines are static
   text you write once. You may configure only the states you care about.
   ⚠️ The order is positional and the last two states were appended, so a 5-entry array
   still means exactly what it always meant.
7. **\`statusImages\`** — **a picture above the ball's head** (the desktop pet's meme
   panel), same positional structure as \`workStatusTexts\`:
   \`[["/abs/idle.png"],["/abs/think.png"],["/abs/work.png"]]\`. One is picked at random
   each time the state changes. Absolute paths (read by this process) or
   \`data:image/...;base64,...\` both work; the bytes are copied into the app's own
   storage so the picture survives even if the original file moves. A tier with no
   text but a picture is fine — the bubble then shows the picture alone, which is the
   usual look. \`statusImageSizeDp\` sets the size (32–240 dp, default 96).
   Use this for reactions the pet should *show* rather than *say*.
8. **\`flipOnTurn\`** (default true) — mirror the pet horizontally when it turns
   around. Assets are usually drawn facing one way, so turning is done with a mirror;
   turn it off if your art already has both directions (mirroring would flip any text
   in the artwork).
9. **\`moveParams\`** — per-action walk distance, overriding \`moveMinDp\`/\`moveMaxDp\`:
   \`{"shuffle":{"minDp":10,"maxDp":20},"dash":{"minDp":120,"maxDp":200}}\`. Use it when
   different \`move\` animations have different strides (a small shuffle should not
   travel as far as a run). \`{minDp:0,maxDp:0}\` means "play the walk animation in
   place without moving".
10. **\`moveEnabled\`** (+ \`moveIntervalSec\` / \`moveMinDp\` / \`moveMaxDp\`) — let the pet
    walk a short distance on its own every so often. **Off by default**: it is
    charming for a pet and annoying for a button, so only turn it on if the user
    actually wants a pet.

### You are NOT limited to sprite sheets

The content layer can be **either** still frames (carousel / sprite sheet) **or a
transparent WebM video**. If you are asked to build something like the \`dsh-pet\`
desktop pet, use \`video\` — that is what its animations are (VP9 webm with an alpha
channel), and transparent video plays correctly here. Do not conclude that "the
bubble can only do sprite sheets"; that was true before \`video\` existed.

A dsh-pet style pet is therefore:

1. Get the webm files (download them, or generate frames and encode them). They live
   in your workspace, so you have their absolute paths.
2. \`{action:"set", actions:{"idle":{"video":"/abs/idle.webm"}, "thinking":{"video":"/abs/think.webm"}, ...}}\`
3. \`{action:"set", actionMap:{"idle":"idle","thinking":"thinking","working":"work"}}\`
4. Make sure the ball is actually visible: with the default \`state:"auto"\` it only
   shows while the app is in the background.

One video per action; \`idle\` (and any action mapped from \`idle\`) is looped, the
others play once and then fall back to \`idle\`. \`get\` reports \`videoActions\` so you
can confirm each one landed.

\`pinAction\` pins one action by name (the pet ignores \`actionMap\`); \`pinAction:""\` goes
back to automatic switching. Prefer \`action:"state"\` over \`pinAction\` for live control:
\`pinAction\` is saved to disk, \`runState\` is not.

## Recipes for common asks

- "transparent background, keep the picture" → \`{bgOpacity: 0}\`
- "remove the picture" → \`{avatar: "none"}\`
- "make it a star / heart / hexagon" → \`{shape: "path", shapePath: "..."}\`
- "make it spin" → \`{spin: 90}\`; "make it breathe" → \`{pulse: true}\`
- "animate these images" → \`{frameFiles: ["/abs/a.png", "/abs/b.png"], frameIntervalMs: 200}\`
- "animate this sprite sheet" → \`{frameFiles: ["/abs/sheet.png"], spriteRows: 4, spriteCols: 4, frameIntervalMs: 120}\`
- "make it bigger and see-through" → \`{size: 96, opacity: 0.6}\`
- "tell me when you're done" → \`{action: "notify", text: "搞定", durationMs: 4000}\`
- "make it a desktop pet" → register \`actions\` for idle/think/work + an \`actionMap\`, then
  drive it with \`{action:"state", runState:"working"}\` while you work
- "make it look like dsh-pet" → \`{action:"set", actions:{"idle":{"video":"/abs/idle.webm"}},
  actionMap:{"idle":"idle"}}\` — transparent webm per action, not sprite sheets
- "make it feel alive / stop it looking frozen" → \`{pools:{"idle":["idle","stretch","look"]}}\`
  (the random chain). Add \`{workStatusTexts:[["在发呆"],["正在想…"],["正在干活…"]]}\` for a voice
- "let me pet it / it should react when I tap" → \`{pools:{"click":["happy","surprised"]}}\`
  ⚠️ this moves panel-opening to a long-press — tell the user
- "let me pick it up" → \`{pools:{"drag":["being-lifted"]}}\`
- "make it wander around" → \`{moveEnabled:true, pools:{"move":["walk"]}}\`
- "it should turn around / face the way it walks" →
  \`{pools:{"turn":["turn"]}, poolWeights:{"idle":10,"turn":2}}\` (default \`flipOnTurn\` mirrors it)
- "show a picture above its head" →
  \`{statusImages:[["/abs/idle.png"],["/abs/think.png"],["/abs/work.png"]]}\`
- "different animation each time it starts thinking" →
  \`{pools:{"work-thinking":["think-a","think-b"]}}\` (beats \`actionMap\` for that state)
- "walk animations with different strides" →
  \`{pools:{"move":["shuffle","dash"]}, moveParams:{"shuffle":{"minDp":10,"maxDp":20},"dash":{"minDp":120,"maxDp":200}}}\`
- "make it calm again / stop the random actions" → \`{poolWeights:{"idle":0}}\`
- "have the pet react while you work" → \`{action:"state", runState:"thinking"}\` before you
  start, \`runState:"working"\` around each tool call, \`runState:"idle"\` when done
  (also \`runState:"success"\` when it finishes well and \`runState:"error"\` when it fails)
- "let me throw it around" → \`{throwEnabled: true}\` (already the default)
- "stop making it a pet" → \`{actionMap: {}, pinAction: ""}\`

Invalid values fall back to the current value instead of failing, and unknown fields are ignored, so a typo does not make the ball disappear. The result tells you the appearance as it is *after* the change; check it if you need to be sure.

⚠️ With the default \`state:"auto"\` the ball is only visible while this app is in the background — if the user is looking at the app right now and reports "I see nothing", that is expected, not a failure. Setting \`state:"collapsed"\` or \`"expanded"\` pins it visible in the foreground, which is what you usually want when the user asked to see a change.

⚠️ Image paths are read by THIS process, so they must be readable from your working directory. The user's photos are usually under \`/sdcard/...\`. If you only have a picture the user attached, find its path in the workspace first.`;

/** 把一条参数对象压成要下发的字段集合（丢 undefined / null / 空串）。 */
function pickFields(args) {
	const fields = {};
	const keys = [
		'state', 'shape', 'shapePath', 'shapeViewBox', 'size', 'boxWidth', 'boxHeight', 'showDot', 'cornerRadius', 'opacity',
		'bgOpacity', 'avatarOpacity', 'avatarScale',
		'bg', 'bg2', 'border', 'borderWidth', 'avatar',
		'frameIntervalMs', 'frameDurations', 'frameLoop', 'spriteRows', 'spriteCols',
		// ⚠️ `action`（钦定的动作名）**允许空串**：空串的语义是"回到跟随状态自动
		//   切换"，一刀切地丢掉空串会让模型没法从钦定切回自动。
		//   所以它不放进这个通用循环，由 execute 单独处理（见那里的 pinAction）。
		'spin', 'pulse',
		'panelWidth', 'panelHeight', 'panelBg', 'panelText', 'panelAccent',
		'statusAutoHideMs', 'edgeSnap', 'throwEnabled',
		'edgeImage', 'edgeWidth', 'edgeHeight', 'edgeOpacity', 'edgeAnchor',
		'moveEnabled', 'moveIntervalSec', 'moveMinDp', 'moveMaxDp',
		'statusImageSizeDp', 'flipOnTurn',
		// ⚠️ 这几个允许空对象/空数组（那是"清空"这个语义本身），所以能走通用循环；
		//   但**池名与动作名的合法性在 execute 里单独校验** —— 原生侧虽然也会
		//   逐条丢掉非法的，但它只记日志、不回执，AI 看不到自己写错了哪个。
		//   `statusImages` / `moveParams` 同理：它们含路径/动作名，也要单独校验。
		'pools', 'poolWeights', 'workStatusTexts',
	];
	for (const key of keys) {
		const value = args[key];
		if (value === undefined || value === null) continue;
		if (typeof value === 'string' && value === '') continue;
		fields[key] = value;
	}
	return fields;
}

/**
 * 把本地图片读成 data URI。
 *
 * ⚠️ **在插件这一侧读文件**，而不是把路径交给原生：原生拿不到工作区路径
 *   （它按自己的沙箱跑），而本插件与 `read` 工具在同一个进程、同一套权限里。
 *
 * @param {string} file - 绝对路径。
 * @returns {{ok: true, uri: string} | {ok: false, error: string}} 结果。
 */
function readImageFile(file) {
	try {
		const info = fs.statSync(file);
		if (!info.isFile()) return { ok: false, error: `不是普通文件: ${file}` };
		const ext = path.extname(file).toLowerCase();
		const mime = ext === '.png' ? 'image/png'
			: (ext === '.jpg' || ext === '.jpeg') ? 'image/jpeg'
				: ext === '.webp' ? 'image/webp'
					: ext === '.gif' ? 'image/gif'
						: '';
		if (mime === '') return { ok: false, error: `不支持的图片扩展名: ${ext || '(无)'}（支持 png/jpg/webp/gif）` };
		const bytes = fs.readFileSync(file);
		return { ok: true, uri: `data:${mime};base64,${bytes.toString('base64')}` };
	} catch (error) {
		return { ok: false, error: `读取失败: ${error?.message ?? error}` };
	}
}

/**
 * 把 `frameFiles` 读成 data URI 数组。
 *
 * ⚠️ **全有或全无**：任何一帧读失败就整批放弃并明确报错。只送一部分会让
 *   用户看到"轮播缺了几帧"，而他根本不知道为什么。
 *
 * ⚠️ 上限与原生侧 `BubbleFrames.MAX_FRAMES` 对齐（64）。超了就在这里截断并
 *   在 message 里说明，而不是送过去让原生静默丢掉尾巴。
 *
 * @param {string[]} files - 绝对路径数组。
 * @returns {{ok: true, frames: string[], note: string} | {ok: false, error: string}} 结果。
 */
function readFrameFiles(files) {
	if (!Array.isArray(files)) return { ok: false, error: 'frameFiles 必须是数组' };
	if (files.length === 0) return { ok: true, frames: [], note: '' };

	const capped = files.slice(0, MAX_FRAMES);
	const frames = [];
	let total = 0;
	for (const file of capped) {
		const read = readImageFile(file);
		if (!read.ok) return { ok: false, error: `第 ${frames.length + 1} 帧不可用（${read.error}）` };
		total += read.uri.length;
		if (total > MAX_FRAMES_BASE64) {
			return {
				ok: false,
				error: `轮播帧总量过大（第 ${frames.length + 1} 帧后已超 ${Math.round(MAX_FRAMES_BASE64 / 1024 / 1024)} MB）。`
					+ '请先把图片缩小（例如压到 256×256 的 PNG），或减少帧数 —— '
					+ '整批一次送过去会把原生侧的内存撑爆，悬浮球会直接消失。',
			};
		}
		frames.push(read.uri);
	}
	const note = files.length > capped.length
		? `（只用了前 ${capped.length} 帧，上限 ${MAX_FRAMES}）`
		: '';
	return { ok: true, frames, note };
}

/**
 * 校验并透传 `pools`：池名 → 动作名数组。
 *
 * ⚠️ **非法项在这里就报错，不丢给原生**。原生侧对非法池名/动作名的策略是
 *   "逐条丢掉 + 记日志"，它不回执 —— AI 于是看不到自己写错了哪一个，只会
 *   觉得"配了但宠物不动"。这类静默失效是最难查的一类。
 *
 * ⚠️ 但**池里引用了磁盘上不存在的动作名不算错**：先配池、后传素材是很正常的
 *   顺序（素材可能很大，AI 会分几次传）。原生侧抽签时会跳过不存在的，
 *   所以最坏结果只是"那个动作永远抽不到"，不会卡住宠物。
 *
 * @param {object} pools - `{池名: [动作名…]}`。
 * @returns {{ok: true, pools: object} | {ok: false, error: string}} 结果。
 */
function checkPools(pools) {
	if (pools === null || typeof pools !== 'object' || Array.isArray(pools)) {
		return { ok: false, error: 'pools 必须是对象（池名 → 动作名数组）' };
	}
	const names = Object.keys(pools);
	if (names.length > MAX_POOLS) {
		return { ok: false, error: `动作池过多（${names.length} > 上限 ${MAX_POOLS}）` };
	}
	const out = {};
	for (const rawPool of names) {
		const pool = String(rawPool).trim().toLowerCase();
		if (!POOL_NAME_RE.test(pool)) {
			return { ok: false, error: `池名不合法: ${rawPool}（只允许 a-z 0-9 - _，最多 24 字符）` };
		}
		const list = pools[rawPool];
		if (!Array.isArray(list)) {
			return { ok: false, error: `动作池 ${rawPool} 必须是数组` };
		}
		if (list.length > MAX_POOL_ACTIONS) {
			return { ok: false, error: `动作池 ${rawPool} 里动作过多（${list.length} > 上限 ${MAX_POOL_ACTIONS}）` };
		}
		const acts = [];
		for (const rawAct of list) {
			const act = String(rawAct).trim().toLowerCase();
			if (!POOL_NAME_RE.test(act) || act === 'default') {
				return { ok: false, error: `动作池 ${rawPool} 里的动作名不合法: ${rawAct}` };
			}
			if (!acts.includes(act)) acts.push(act);
		}
		out[pool] = acts;
	}
	return { ok: true, pools: out };
}

/**
 * 校验并透传 `poolWeights`：池名 → 相对权重。
 *
 * ⚠️ 只有 `idle` 的权重被原生使用，但**其余池名不报错**：AI 写一份完整的权重表
 *   （对应它配的全部池）是很自然的，为此报错会逼它维护"哪些池有特殊含义"这份
 *   隐含知识。写多了无害，写错了池名才是错。
 *
 * @param {object} weights - `{池名: 数字}`。
 * @returns {{ok: true, weights: object} | {ok: false, error: string}} 结果。
 */
function checkPoolWeights(weights) {
	if (weights === null || typeof weights !== 'object' || Array.isArray(weights)) {
		return { ok: false, error: 'poolWeights 必须是对象（池名 → 数字）' };
	}
	const out = {};
	for (const rawPool of Object.keys(weights)) {
		const pool = String(rawPool).trim().toLowerCase();
		if (!POOL_NAME_RE.test(pool)) {
			return { ok: false, error: `权重里的池名不合法: ${rawPool}` };
		}
		const v = weights[rawPool];
		if (typeof v !== 'number' || !Number.isFinite(v)) {
			return { ok: false, error: `池 ${rawPool} 的权重必须是数字` };
		}
		out[pool] = Math.max(0, Math.min(1000, Math.trunc(v)));
	}
	return { ok: true, weights: out };
}

/**
 * 校验并透传 `workStatusTexts`：二维数组，外层是档位。
 *
 * ⚠️ 档位顺序 = `RUN_STATES`（idle/thinking/working/waiting/error/result/success），
 *   **不是**按名字索引。写超 7 档就是写错了，直接报错而不是丢掉 —— 多出来的档永远
 *   不会显示，静默丢掉等于骗 AI。
 *
 * @param {Array} tiers - `[[文案…], …]`。
 * @returns {{ok: true, tiers: Array} | {ok: false, error: string}} 结果。
 */
function checkStatusTexts(tiers) {
	if (!Array.isArray(tiers)) {
		return { ok: false, error: 'workStatusTexts 必须是数组（按状态顺序，每项是一个字符串数组）' };
	}
	if (tiers.length > RUN_STATES.length) {
		return {
			ok: false,
			error: `workStatusTexts 最多 ${RUN_STATES.length} 档（顺序 = ${RUN_STATES.join(' / ')}），收到 ${tiers.length} 档`,
		};
	}
	const out = [];
	for (let i = 0; i < tiers.length; i++) {
		const tier = tiers[i];
		if (!Array.isArray(tier)) {
			return { ok: false, error: `第 ${i} 档（${RUN_STATES[i]}）必须是字符串数组` };
		}
		if (tier.length > MAX_STATUS_TEXTS_PER_TIER) {
			return { ok: false, error: `第 ${i} 档（${RUN_STATES[i]}）文案过多（${tier.length} > 上限 ${MAX_STATUS_TEXTS_PER_TIER}）` };
		}
		const texts = [];
		for (const raw of tier) {
			if (typeof raw !== 'string') {
				return { ok: false, error: `第 ${i} 档（${RUN_STATES[i]}）里的文案必须是字符串` };
			}
			const s = raw.trim();
			if (s !== '') texts.push(s.slice(0, MAX_STATUS_TEXT_CHARS));
		}
		out.push(texts);
	}
	return { ok: true, tiers: out };
}

/**
 * 把 `statusImages` 读成 data URI 的二维数组（外层是档位）。
 *
 * ⚠️ **与 `frameFiles` 相反，这里允许 data URI 直传**：状态图常常是模型自己
 *   生成/拼出来的小图，走 base64 反而省一步落盘。两种形态都收。
 *
 * ⚠️ 与 [checkStatusTexts] 一样按**档位下标**对齐 `RUN_STATES`，所以档数上限
 *   就是状态数；写超了直接报错（多出来的档永远不会显示，静默丢掉等于骗模型）。
 *
 * ⚠️ 这里只做**格式与预算**校验，不校验图片能不能解码 —— 那要真去解一张图，
 *   而原生侧 `BubbleStatusImages.write` 已经有一道 `BitmapFactory` 闸（解不出来
 *   就丢掉并记日志）。在这里重复一遍只是多花时间。
 *
 * @param {Array} tiers - `[[路径或 dataURI…], …]`。
 * @returns {{ok: true, tiers: Array, note: string} | {ok: false, error: string}} 结果。
 */
function checkStatusImages(tiers) {
	if (!Array.isArray(tiers)) {
		return { ok: false, error: 'statusImages 必须是数组（按状态顺序，每项是一个字符串数组）' };
	}
	if (tiers.length > MAX_STATUS_IMAGES) {
		return {
			ok: false,
			error: `statusImages 最多 ${MAX_STATUS_IMAGES} 档（顺序 = ${RUN_STATES.join(' / ')}），收到 ${tiers.length} 档`,
		};
	}
	const out = [];
	let budget = 0;
	let total = 0;
	for (let i = 0; i < tiers.length; i++) {
		const tier = tiers[i];
		if (!Array.isArray(tier)) {
			return { ok: false, error: `第 ${i} 档（${RUN_STATES[i]}）必须是字符串数组` };
		}
		if (tier.length > MAX_STATUS_IMAGES_PER_TIER) {
			return {
				ok: false,
				error: `第 ${i} 档（${RUN_STATES[i]}）图片过多（${tier.length} > 上限 ${MAX_STATUS_IMAGES_PER_TIER}）`,
			};
		}
		const uris = [];
		for (const raw of tier) {
			if (typeof raw !== 'string' || raw.trim() === '') {
				return { ok: false, error: `第 ${i} 档（${RUN_STATES[i]}）里的图片必须是路径或 data URI 字符串` };
			}
			const s = raw.trim();
			// 已经是 data URI：直接收（但要算进预算）。
			if (s.startsWith('data:image/')) {
				budget += s.length;
				uris.push(s);
				continue;
			}
			const read = readImageFile(s);
			if (!read.ok) {
				return {
					ok: false,
					error: `第 ${i} 档（${RUN_STATES[i]}）第 ${uris.length + 1} 张图不可用（${read.error}）`,
				};
			}
			budget += read.uri.length;
			uris.push(read.uri);
		}
		total += uris.length;
		out.push(uris);
		if (budget > STATUS_IMAGE_BUDGET_BASE64) {
			return {
				ok: false,
				error: `本次要下发的状态图总量过大（已超 ${Math.round(STATUS_IMAGE_BUDGET_BASE64 / 1024 / 1024)} MB）。`
					+ '状态图是头顶的小图，请把图片压小（例如 240×240 的 PNG），或一次少配几张。',
			};
		}
	}
	const note = total > MAX_STATUS_IMAGE_FILES
		? `（共 ${total} 张，磁盘上只保留最近 ${MAX_STATUS_IMAGE_FILES} 张，旧的会被清理）`
		: '';
	return { ok: true, tiers: out, note };
}

/**
 * 校验并透传 `moveParams`：动作名 → `{minDp, maxDp}`。
 *
 * ⚠️ 键是**动作名**（不是池名），所以用与 `actions` 同一套字符规则校验 ——
 *   它必须能和 `pools.move` 里的元素对上，规则不一致就永远对不上。
 *
 * ⚠️ `minDp`/`maxDp` **允许只写一个**：缺的那个跟随全局 \`moveMinDp\`/\`moveMaxDp\`。
 *   这与"写成 0"是两回事（0 = 原地踏步），所以缺省必须传 `null` 而不是 0 ——
 *   原生侧的 `MoveParams` 就是这么区分的。
 *
 * @param {object} params - `{动作名: {minDp?, maxDp?}}`。
 * @returns {{ok: true, params: object} | {ok: false, error: string}} 结果。
 */
function checkMoveParams(params) {
	if (params === null || typeof params !== 'object' || Array.isArray(params)) {
		return { ok: false, error: 'moveParams 必须是对象（动作名 → {minDp, maxDp}）' };
	}
	const out = {};
	for (const rawAction of Object.keys(params)) {
		const action = String(rawAction).trim().toLowerCase();
		if (!POOL_NAME_RE.test(action) || action === 'default') {
			return { ok: false, error: `moveParams 里的动作名不合法: ${rawAction}` };
		}
		const spec = params[rawAction];
		if (spec === null || typeof spec !== 'object' || Array.isArray(spec)) {
			return { ok: false, error: `moveParams.${rawAction} 必须是对象（{minDp, maxDp}）` };
		}
		const entry = {};
		for (const key of ['minDp', 'maxDp']) {
			if (spec[key] === undefined || spec[key] === null) continue;
			const v = spec[key];
			if (typeof v !== 'number' || !Number.isFinite(v)) {
				return { ok: false, error: `moveParams.${rawAction}.${key} 必须是数字` };
			}
			entry[key] = Math.max(0, Math.min(400, Math.trunc(v)));
		}
		if (Object.keys(entry).length === 0) {
			return { ok: false, error: `moveParams.${rawAction} 至少要给 minDp 或 maxDp（都是 0 表示原地踏步）` };
		}
		out[action] = entry;
	}
	return { ok: true, params: out };
}

/**
 * 把 `actions` 参数（动作名 → 规格）翻成原生认的那份 `actions` 对象。
 *
 * 规格里允许 `frameFiles`（本进程读文件）或 `frames`（已是 data URI），其余字段
 * 原样透传。**全有或全无**：任何一个动作的图读不出来就整批放弃 —— 只写一半会让
 * 宠物"有的动有的不动"，而模型拿到的是"成功"，无从知道哪几个没生效。
 *
 * ⚠️ 预算分两层：单个动作由 [readFrameFiles] 限 6 MB，**全部动作合计**由
 *   [ACTION_BUDGET_BASE64] 限 12 MB。只限前者的话，一次配 5 个动作各 5 MB
 *   会照样过闸，而原生侧那一次 `readText()` 就足以被 LMK 杀掉。
 *
 * @param {object} actions - `{动作名: 规格}`。
 * @returns {{ok: true, actions: object, note: string, count: number} | {ok: false, error: string}}。
 */
function buildActions(actions) {
	if (actions === null || typeof actions !== 'object' || Array.isArray(actions)) {
		return { ok: false, error: 'actions 必须是对象（动作名 → 规格）' };
	}
	const names = Object.keys(actions);
	if (names.length > MAX_ACTIONS) {
		return { ok: false, error: `动作过多（${names.length} > 上限 ${MAX_ACTIONS}）` };
	}

	const out = {};
	let budget = 0;
	for (const rawName of names) {
		const name = String(rawName).trim().toLowerCase();
		if (!/^[a-z0-9_-]{1,24}$/.test(name)) {
			return { ok: false, error: `动作名不合法: ${rawName}（只允许 a-z 0-9 - _，最多 24 字符）` };
		}
		const spec = actions[rawName];
		if (spec === null || typeof spec !== 'object') {
			return { ok: false, error: `动作 ${rawName} 的规格必须是对象` };
		}
		const entry = {};
		for (const key of ['spriteRows', 'spriteCols', 'frameIntervalMs', 'frameDurations', 'frameLoop']) {
			if (spec[key] !== undefined && spec[key] !== null) entry[key] = spec[key];
		}
		// 视频素材：**直接透传路径**（原生自己读文件，见顶层 `video` 处的说明）。
		// ⚠️ 与 `frames` 二选一**不成立** —— 一个动作可以同时带两者（视频是主素材、
		//   帧集是兜底），所以这里不参与下面的 else-if 链。
		if (spec.video !== undefined && spec.video !== null) {
			if (typeof spec.video !== 'string') {
				return { ok: false, error: `动作 ${rawName} 的 video 必须是字符串（绝对路径）` };
			}
			entry.video = spec.video.trim();
		}
		// 两种来源二选一：`frames` 已是 data URI（模型自己拼的），`frameFiles` 要读盘。
		if (spec.frames !== undefined) {
			if (!Array.isArray(spec.frames)) {
				return { ok: false, error: `动作 ${rawName} 的 frames 必须是数组` };
			}
			budget += spec.frames.reduce((n, s) => n + (typeof s === 'string' ? s.length : 0), 0);
			entry.frames = spec.frames;
		} else if (spec.frameFiles !== undefined) {
			const read = readFrameFiles(spec.frameFiles);
			if (!read.ok) return { ok: false, error: `动作 ${rawName}: ${read.error}` };
			budget += read.frames.reduce((n, s) => n + s.length, 0);
			entry.frames = read.frames;
		} else if (entry.video === undefined) {
			// 既没有帧、也没有视频 —— 这个动作是空的。**报错而不是静默跳过**：
			//   静默会让模型以为配好了，而宠物纹丝不动。
			return { ok: false, error: `动作 ${rawName} 缺少 frames、frameFiles 或 video` };
		}
		// ⚠️ 有视频、没帧时**补一个空数组**：原生侧把"frames 缺席"当成"这是老式
		//   的帧集配置"，补上空数组能把语义明确成"这个动作不要帧集"。
		if (entry.frames === undefined) entry.frames = [];
		if (budget > ACTION_BUDGET_BASE64) {
			return {
				ok: false,
				error: `本次要下发的全部动作帧总量过大（已超 ${Math.round(ACTION_BUDGET_BASE64 / 1024 / 1024)} MB）。`
					+ '请一次只配 2–3 个动作，或把图片压小 —— 一次塞太多会让原生侧被系统杀掉，'
					+ '症状是"悬浮球突然消失、什么都没发生"。',
			};
		}
		out[name] = entry;
	}
	return { ok: true, actions: out, note: '', count: Object.keys(out).length };
}

/** 写文件（先写临时文件再 rename —— 原生侧可能正读到一半）。 */
function writeJsonAtomic(target, value) {
	const tmp = `${target}.tmp`;
	fs.writeFileSync(tmp, JSON.stringify(value), 'utf8');
	fs.renameSync(tmp, target);
}

/** 读 JSON；文件不存在/内容不完整时给 null（原生正在写的那一瞬间会读到半个）。 */
function readJson(target) {
	try {
		return JSON.parse(fs.readFileSync(target, 'utf8'));
	} catch {
		return null;
	}
}

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

/**
 * 把一条命令交给原生侧并等回执。
 * @param {string} home - `$DSH_HOME`。
 * @param {number} id - 请求编号。
 * @param {object} payload - `{action, fields}`。
 * @returns {Promise<{ok: boolean, message: string, appearance?: string}>} 结果。
 */
const commandGates = new Map();

function sendCommand(home, id, payload) {
	const key = path.resolve(home);
	const previous = commandGates.get(key) ?? Promise.resolve();
	const run = () => sendCommandNow(home, id, payload);
	const next = previous.then(run, run);
	const settled = next.then(() => undefined, () => undefined);
	commandGates.set(key, settled);
	void settled.then(() => {
		if (commandGates.get(key) === settled) commandGates.delete(key);
	});
	return next;
}

// 同一信箱的写入与完整回执等待必须一起排队，异常也释放后续请求。
async function sendCommandNow(home, id, payload) {
	const cmdPath = path.join(home, CMD_FILE);
	const resPath = path.join(home, RESULT_FILE);

	try {
		// 先把旧回执清掉：否则上一轮的 `ok` 会被当成本轮的答案。
		// 用 unlink 而不是写空对象 —— 写空对象会让「解析失败」与「还没处理」
		// 两种状态混在一起，只能靠 id 分辨，多一处出错的机会。
		try { fs.unlinkSync(resPath); } catch { /* 不存在就是已经干净了 */ }
		writeJsonAtomic(cmdPath, { id, ...payload });
	} catch (error) {
		return { ok: false, message: `写入文件信箱失败: ${error?.message ?? error}` };
	}

	const deadline = Date.now() + WAIT_TIMEOUT_MS;
	while (Date.now() < deadline) {
		await sleep(POLL_INTERVAL_MS);
		const reply = readJson(resPath);
		if (reply === null || reply.id !== id) continue;
		return {
			ok: reply.ok === true,
			message: typeof reply.message === 'string' ? reply.message : '',
			appearance: typeof reply.appearance === 'string' ? reply.appearance : undefined,
		};
	}
	// 超时不代表失败：原生侧可能只是慢（引擎刚启动、或主线程正忙）。
	// 但绝不能返回 ok —— 那会让模型以为改成功了。
	return {
		ok: false,
		message: `等待原生回执超时（${WAIT_TIMEOUT_MS} ms）。悬浮窗可能已经改好，也可能没有；`
			+ '请用 action:"get" 复查，确认后再向用户汇报。',
	};
}

/**
 * 工具定义。
 *
 * ⚠️ **手工构造而不是 `defineTool({...})`**：本包解在 `$DSH_HOME/bubble-tool/` 下，
 * 那里没有 `node_modules`，ESM 解析够不到引擎的 `@deepseek-ai/dsh-tools`。
 * 理由与做法与 `dsh-android-shell` 完全一致（那里有更详细的说明）。
 *
 * @param {string} home - `$DSH_HOME`。
 * @returns {object} 工具定义。
 */
function bubbleTool(home) {
	/** 请求编号。进程内自增即可 —— 只有本插件会写这个信箱。 */
	let seq = 0;

	return {
		name: TOOL_NAME,
		description: DESCRIPTION,
		parameters: {
			type: 'object',
			additionalProperties: false,
			properties: {
				action: {
					type: 'string',
					enum: ['set', 'get', 'notify', 'state'],
					description: '"set" applies the fields below; "get" only reads the current appearance back; "notify" pushes text into the status strip above the ball without changing any setting; "state" reports the CURRENT RUN STATE (and optionally which action to play) without changing any setting — that is what a desktop-pet companion uses.',
				},
				state: { type: 'string', enum: ['expanded', 'collapsed', 'hidden', 'auto'] },
				runState: {
					type: 'string',
					enum: ['idle', 'thinking', 'working', 'waiting', 'error'],
					description: 'For action:"state" (and for actionMap below) — what the engine is doing right now. idle = not running; thinking = model generating; working = a tool/command is executing; waiting = waiting for the user (approval/input); error = the last turn failed.',
				},
				playAction: {
					type: 'string',
					description: 'For action:"state" — the action name to play right now. Pass "" to clear it and let actionMap decide again. Omit to leave the current forced action alone. Names come from the `actions` list; register them with `actions` first.',
				},
				actions: {
					type: 'object',
					description: 'Register/update named action frame sets — this is how you build a desktop pet with one animation per state. An object mapping action name → spec, e.g. {"idle":{"frameFiles":["/abs/idle.png"],"spriteRows":2,"spriteCols":4},"think":{"frameFiles":["/abs/a.png","/abs/b.png"],"frameIntervalMs":150}}. Each spec accepts frameFiles (or frames), spriteRows, spriteCols, frameIntervalMs, frameDurations, frameLoop. An empty frames array deletes that action. Names: a–z, 0–9, "-", "_" (max 24 chars).',
					additionalProperties: true,
				},
				actionMap: {
					type: 'object',
					description: 'State → action name map, e.g. {"thinking":"think","working":"work","waiting":"wait","error":"sad","success":"happy"}. States are idle / thinking / working / waiting / error / result / success. With an empty `action` the pet then switches animation by itself as the run state changes. A state you do NOT map falls back to your `idle` action, so mapping just `idle` already keeps the pet alive in every state — without this fallback the pet would snap back to the built-in static avatar the moment you start working. Only if `idle` is unmapped too does that state fall back to the avatar. A per-state pool (work-thinking / state-working) beats this map for that state. Pass {} to clear the map.',
					additionalProperties: { type: 'string' },
				},
				pinAction: {
					type: 'string',
					description: 'Pin ONE action by name so the pet always plays it and ignores actionMap. Use "default" to force the default action set, or "" to go back to automatic state-driven switching. Saved to disk, unlike playAction.',
				},
				shape: { type: 'string', enum: ['circle', 'square', 'rounded', 'path'] },
				shapePath: { type: 'string', description: 'SVG path "d" for shape:"path" — any outline you like, in shapeViewBox units. Max 4000 chars.' },
				shapeViewBox: { type: 'string', description: '"minX minY width height" for shapePath, default "0 0 100 100".' },
				size: { type: 'number', description: 'Ball diameter in dp, 24–320. When the canvas is auto-sized, this is its LONG edge.' },
				boxWidth: { type: 'number', description: 'Canvas width in dp, 16–400. 0 (default) = auto: fit to the current subject material, long edge = size. Give only one of boxWidth/boxHeight and the other still follows the material.' },
				boxHeight: { type: 'number', description: 'Canvas height in dp, 16–400. 0 (default) = auto. See boxWidth.' },
				showDot: { type: 'boolean', description: 'Show the small status dot at the bottom-right. Default false (hidden) — it sits on top of the pet artwork.' },
				cornerRadius: { type: 'number', description: 'dp, 0–80.' },
				opacity: { type: 'number', description: 'Global opacity multiplier, 0.05–1.' },
				bgOpacity: { type: 'number', description: '0–1, affects the background shape ONLY. 0 = fully transparent background.' },
				avatarOpacity: { type: 'number', description: '0–1, affects the image ONLY.' },
				avatarScale: { type: 'number', description: '0.2–2.0; above 1 overflows and gets clipped by the shape.' },
				bg: { type: 'string', description: 'Solid colour "#RRGGBB" (or "#AARRGGBB"), or the top colour of a vertical gradient with bg2.' },
				bg2: { type: 'string', description: 'Bottom colour "#RRGGBB" of the vertical gradient.' },
				border: { type: 'string', description: 'Border colour "#RRGGBB".' },
				borderWidth: { type: 'number', description: 'dp, 0–8.' },
				avatar: { type: 'string', description: '"default", "none" (no image at all), or a data:image/...;base64,... URI.' },
				avatarFile: { type: 'string', description: 'Absolute path to an image file to use as the avatar. Takes precedence over avatar.' },
				frameFiles: {
					type: 'array',
					items: { type: 'string' },
					description: 'Absolute paths to image files forming an animated carousel (or ONE sprite sheet when spriteRows/spriteCols are set). Pass [] to clear the carousel. Max 64 frames.',
				},
				frameDurations: {
					type: 'array',
					items: { type: 'number' },
					description: 'Optional per-frame durations in ms, same order as frameFiles, each 20–60000.',
				},
				frameIntervalMs: { type: 'number', description: 'Uniform frame duration in ms, 20–60000 (default 400).' },
				frameLoop: { type: 'number', description: '-1 (default) plays forever; N>0 stops on the last frame after N cycles.' },
				spriteRows: { type: 'number', description: 'Sprite-sheet rows, 0–32. Use with spriteCols and a single frameFiles entry.' },
				spriteCols: { type: 'number', description: 'Sprite-sheet columns, 0–32.' },
				video: {
					type: 'string',
					description: 'Absolute path to a transparent WebM video to use as the animation for this action — the highest-quality content layer, and how you build a pet like dsh-pet. Prefer a path (the bytes are read by this process); a data:video/webm;base64,... URI also works for small files. Pass "" to delete this action\'s video. Up to ~6 MB per file. Videos take priority over frameFiles, which then act as a fallback.',
				},
				pools: {
					type: 'object',
					description: 'Named action pools: {"idle":["a","b"],"click":["c"],"drag":["d"],"move":["e"],"turn":["t"],"work-thinking":["x","y"],"celebrate":["f"]}. Names the app drives itself: "idle" = the random idle chain (the pet picks one when nothing is running), "click" = tap response (configuring it turns a single tap into petting, and expanding the panel moves to a long-press), "drag" = the pose shown while the user drags the pet, "move" = the pose used while the pet walks on its own, "turn" = the pose played when the pet changes facing (give it a weight in poolWeights or it is never picked; the flip itself happens when the animation finishes, see flipOnTurn), "work-<state>" or "state-<state>" = a per-state pool that re-rolls a different action every time the pet enters that state and beats actionMap for it. Any other pool name is yours — map it with actionMap or play it by name. Pool names use a-z 0-9 - _ (max 24 chars). Action names that have no asset on disk are skipped, so a typo can never freeze the pet. Pass {} to clear all pools.',
				},
				poolWeights: {
					type: 'object',
					description: 'Relative weights for the random chain, e.g. {"idle":10,"turn":2}. "idle" drives the chain: 0 disables it entirely (the pet then just stays on whatever actionMap says), and omitting it defaults to 10. "turn" is the chance of picking from the "turn" pool (0/unset means the pet never turns on its own). Other names are accepted and ignored. Negative values are clamped to 0.',
				},
				workStatusTexts: {
					type: 'array',
					description: 'Per-state lines for the status bubble, in run-state order [idle, thinking, working, waiting, error, result, success]: [["daydreaming"],["thinking…"],["working…"]]. One line is picked at random each time the state changes (write a single line to keep it fixed). Shorter arrays are fine — you can configure only the states you care about. Empty strings are dropped; lines are capped at 60 chars.',
				},
				statusImages: {
					type: 'array',
					description: 'A picture shown above the ball (the desktop pet\'s head-top panel / meme), in the SAME positional order as workStatusTexts: [["/abs/idle.png"],["/abs/think.png"],["/abs/work.png"]]. One is picked at random each time the state changes. Entries are absolute image paths (read by this process, png/jpg/webp/gif) or data:image/...;base64,... URIs. The bytes are copied into the app\'s own storage, so the picture keeps working even if the original file is moved or deleted. A tier with a picture but no text shows the picture alone — that is the normal look. Pass [[]] or [] to clear. Use statusImageSizeDp to size it.',
				},
				statusImageSizeDp: {
					type: 'number',
					description: 'Side of the status picture in dp, 32–240 (default 96). The longer edge fits this value; the aspect ratio is preserved.',
				},
				flipOnTurn: {
					type: 'boolean',
					description: 'Default true. Mirror the pet horizontally when it turns around (from the "turn" pool, or when it walks the other way). Assets are normally drawn facing one direction, so the mirror IS the turn. Set false if your artwork already contains both directions — mirroring would reverse any text drawn in it.',
				},
				moveParams: {
					type: 'object',
					description: 'Per-action walk distance, overriding moveMinDp/moveMaxDp: {"shuffle":{"minDp":10,"maxDp":20},"dash":{"minDp":120,"maxDp":200}}. Keys are action names (the ones in the "move" pool). Either bound may be omitted, in which case the global moveMinDp/moveMaxDp is used for it. {minDp:0,maxDp:0} means "play the walk animation without moving". Values 0–400 dp.',
				},
				moveEnabled: {
					type: 'boolean',
					description: 'Let the pet walk around on its own while idle (default false). Off by default because most people use the ball as a button and would find it intrusive.',
				},
				moveIntervalSec: { type: 'number', description: 'Seconds between walks, 10–3600 (default 20). The actual delay is randomized to 0.5–1.5× this.' },
				moveMinDp: { type: 'number', description: 'Shortest walk distance in dp, 8–400 (default 24).' },
				moveMaxDp: { type: 'number', description: 'Longest walk distance in dp, 8–400 (default 80).' },
				spin: { type: 'number', description: 'Degrees per second of rotation, -720–720; negative is counter-clockwise, 0 stops it.' },
				pulse: { type: 'boolean', description: 'Breathing scale animation on the image.' },
				panelWidth: { type: 'number', description: 'Expanded panel width in dp.' },
				panelHeight: { type: 'number', description: 'Expanded panel height in dp; 0 fits the content.' },
				panelBg: { type: 'string', description: 'Panel background "#RRGGBB".' },
				panelText: { type: 'string', description: 'Panel body text colour "#RRGGBB".' },
				panelAccent: { type: 'string', description: 'Panel accent colour "#RRGGBB" (send button, user bubble).' },
				statusAutoHideMs: { type: 'number', description: 'Milliseconds before the status strip hides once its content stops changing; 0 disables it.' },
				edgeSnap: { type: 'boolean', description: 'Default false. False = the ball stays where the user drops it (anywhere on screen). True = it is pulled back to the nearest left/right edge after each drag. Dragging the ball off-screen always collapses it into an edge sliver either way.' },
				throwEnabled: { type: 'boolean', description: 'Default true. True = flick the ball and it flies, bounces off all four walls and the floor, then rests where it lands (desktop-pet feel). False = the old drop-and-stick-to-edge behaviour.' },
				edgeImage: { type: 'string', description: '"default" (built-in peeking character) | "none" (plain colour sliver) | a data:image/...;base64 URI. The image shown while the ball is collapsed against a screen edge.' },
				edgeFile: { type: 'string', description: 'Absolute path to an image used as the edge sliver; read by this process and sent as edgeImage. Takes precedence over edgeImage.' },
				edgeWidth: { type: 'number', description: 'Edge sliver visible width in dp, 6–120, default 28. The old build hard-coded 6dp, which users reported as too small to tap.' },
				edgeHeight: { type: 'number', description: 'Edge sliver visible height in dp; 0 (default) follows the ball canvas height. Max 400.' },
				edgeOpacity: { type: 'number', description: 'Edge sliver image opacity, 0–1, default 1.' },
				edgeAnchor: { type: 'number', description: '0–1 fixes the sliver vertical position (0 = top, 1 = bottom). -1 (default) keeps the y the ball had when dragged out.' },
				text: { type: 'string', description: 'For action:"notify" — the message to show in the status strip above the ball.' },
				durationMs: { type: 'number', description: 'For action:"notify" — how long the message stays, in ms. Omit to use statusAutoHideMs.' },
				sticky: { type: 'boolean', description: 'For action:"notify" — true keeps the message up until the next notify.' },
			},
			required: ['action'],
		},
		output: {
			schema: {
				type: 'object',
				additionalProperties: false,
				properties: {
					ok: { type: 'boolean' },
					appearance: { type: 'string' },
					message: { type: 'string' },
				},
				required: ['ok', 'appearance', 'message'],
			},
			render: (_args, value) => [{ type: 'text', text: renderText(value) }],
		},
		presentCall: (args) => ({
			card: 'generic',
			title: args.action === 'get'
				? '读取悬浮窗外观'
				: args.action === 'notify' ? '推送状态消息'
					: args.action === 'state' ? '驱动宠物动作' : '修改悬浮窗',
			description: describeCall(args),
		}),
		async execute(args) {
			const action = args.action;

			// `notify` 走独立分支：它不改外观，也就不该被外观字段的校验牵连。
			if (action === 'notify') {
				const text = typeof args.text === 'string' ? args.text.trim() : '';
				if (text === '') {
					return { ok: false, appearance: '', message: 'notify 需要非空的 text' };
				}
				const reply = await sendCommand(home, ++seq, {
					action: 'notify',
					text,
					durationMs: typeof args.durationMs === 'number' ? args.durationMs : 0,
					sticky: args.sticky === true,
				});
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? `已推送状态消息: ${text}` : reply.message,
				};
			}

			// `state` 的报状态分支。同样走独立通道：它不改外观（不落盘），
			// 只影响"现在演哪个动作"。
			if (action === 'state') {
				const runState = typeof args.runState === 'string' ? args.runState.trim() : '';
				if (runState !== '' && !RUN_STATES.includes(runState)) {
					return {
						ok: false,
						appearance: '',
						message: `runState 取值非法: ${runState}（可选 ${RUN_STATES.join(' / ')}）`,
					};
				}
				// ⚠️ 「没传 playAction」与「传了空串」意思不同：前者不动钦定的动作，
				//   后者清掉钦定、回到 actionMap。用 `!== undefined` 区分，不能靠 trim。
				const hasPlay = args.playAction !== undefined;
				const play = hasPlay && typeof args.playAction === 'string' ? args.playAction.trim() : '';
				if (runState === '' && !hasPlay) {
					return {
						ok: false,
						appearance: '',
						message: 'action:"state" 至少要带 runState 或 playAction',
					};
				}
				const payload = { action: 'state' };
				if (runState !== '') payload.state = runState;
				if (hasPlay) payload.name = play;
				const reply = await sendCommand(home, ++seq, payload);
				const what = [
					runState === '' ? '' : `状态=${runState}`,
					hasPlay ? (play === '' ? '动作=恢复自动' : `动作=${play}`) : '',
				].filter(Boolean).join(' · ');
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? `已更新运行状态（${what}）` : reply.message,
				};
			}

			const fields = pickFields(args ?? {});
			const notes = [];

			// 钦定的动作名单独处理：它**允许空串**（= 回到自动跟随状态），
			// 而 pickFields 会把空串当"没传"丢掉。
			if (typeof args?.pinAction === 'string') fields.action = args.pinAction.trim();

			// `video` 也单独处理，理由与 `pinAction` 同：**空串是合法取值**
			// （= 删掉这个动作的视频），pickFields 会把它当"没传"丢掉。
			//
			// ⚠️ 与 `avatarFile` 相反，这里**传路径而不是读成 data URI**：
			//   webm 单个 200KB~1.3MB，base64 后还要再涨 33%，而原生侧完全有能力
			//   自己读文件（它就在同一台设备上、同一个 App 里）。图片走 base64 是
			//   因为原生拿不到**工作区**路径 —— 但 `video` 传的是**设备上的绝对
			//   路径**（AI 把素材下载到 /sdcard 或 filesDir 下），原生读得到。
			if (typeof args?.video === 'string') {
				fields.video = args.video.trim();
			}

			// `actionMap`：状态 → 动作名。逐条校验，非法的那条直接报错而不是静默丢 ——
			// 「配了但宠物不按它动」是最难查的一类问题（原生侧也会丢未知状态）。
			if (args?.actionMap !== undefined) {
				const map = args.actionMap;
				if (map === null || typeof map !== 'object' || Array.isArray(map)) {
					return { ok: false, appearance: '', message: 'actionMap 必须是对象（状态 → 动作名）' };
				}
				const bad = Object.keys(map).filter((k) => !RUN_STATES.includes(k));
				if (bad.length > 0) {
					return {
						ok: false,
						appearance: '',
						message: `actionMap 里有未知状态: ${bad.join(', ')}（可选 ${RUN_STATES.join(' / ')}）`,
					};
				}
				fields.actionMap = map;
			}

			// `actions`：一次配多套动作帧集（桌面宠物的主体）。
			if (args?.actions !== undefined) {
				const built = buildActions(args.actions);
				if (!built.ok) return { ok: false, appearance: '', message: `动作集不可用（${built.error}）` };
				fields.actions = built.actions;
				if (built.count > 0) notes.push(` · 已配 ${built.count} 个动作`);
			}

			// `pools` / `poolWeights` / `workStatusTexts`：**在这里校验而不是交给原生**。
			// 原生侧对非法项的策略是"逐条丢掉并记日志"，它不回执 —— AI 于是看不到
			// 自己写错了哪一个，只会觉得"配了但没生效"。这类静默失效最难查，
			// 所以在能报错的地方就报错。
			if (args?.pools !== undefined) {
				const chk = checkPools(args.pools);
				if (!chk.ok) return { ok: false, appearance: '', message: chk.error };
				// 写回**规范化后**的版本（小写化 + 去重），别让原生再猜一遍。
				fields.pools = chk.pools;
				const total = Object.keys(chk.pools).length;
				if (total > 0) notes.push(` · 已配 ${total} 个动作池`);
			}
			if (args?.poolWeights !== undefined) {
				const chk = checkPoolWeights(args.poolWeights);
				if (!chk.ok) return { ok: false, appearance: '', message: chk.error };
				fields.poolWeights = chk.weights;
			}
			if (args?.workStatusTexts !== undefined) {
				const chk = checkStatusTexts(args.workStatusTexts);
				if (!chk.ok) return { ok: false, appearance: '', message: chk.error };
				fields.workStatusTexts = chk.tiers;
			}
			// `statusImages`：头顶配图。**在这里读成 data URI**，与 `frameFiles` 同理
			// —— 原生侧拿不到工作区路径。但状态图允许 data URI 直传（见函数注释）。
			if (args?.statusImages !== undefined) {
				const chk = checkStatusImages(args.statusImages);
				if (!chk.ok) return { ok: false, appearance: '', message: chk.error };
				fields.statusImages = chk.tiers;
				const n = chk.tiers.reduce((acc, t) => acc + t.length, 0);
				if (n > 0) notes.push(` · 已配 ${n} 张状态图`);
				if (chk.note !== '') notes.push(chk.note);
			}
			if (args?.moveParams !== undefined) {
				const chk = checkMoveParams(args.moveParams);
				if (!chk.ok) return { ok: false, appearance: '', message: chk.error };
				fields.moveParams = chk.params;
			}

			// `avatarFile` → data URI。先做，因为它可能失败，失败时要**明确报错**
			// 而不是静默退回内置头像（那样用户会看到球突然变成默认头像却没有任何提示）。
			if (typeof args?.avatarFile === 'string' && args.avatarFile !== '') {
				const read = readImageFile(args.avatarFile);
				if (!read.ok) {
					return { ok: false, appearance: '', message: `头像文件不可用（${read.error}）` };
				}
				fields.avatar = read.uri;
			}

			// `edgeFile` → data URI。与 `avatarFile` 完全同构：读失败明确报错，
			// 不静默退回内置图（否则用户看到贴边形象莫名变回默认，却没有任何提示）。
			if (typeof args?.edgeFile === 'string' && args.edgeFile !== '') {
				const read = readImageFile(args.edgeFile);
				if (!read.ok) {
					return { ok: false, appearance: '', message: `贴边图文件不可用（${read.error}）` };
				}
				fields.edgeImage = read.uri;
			}

			// `frameFiles` → data URI 数组。同上：读失败就整批放弃。
			if (args?.frameFiles !== undefined) {
				const read = readFrameFiles(args.frameFiles);
				if (!read.ok) {
					return { ok: false, appearance: '', message: `轮播帧不可用（${read.error}）` };
				}
				fields.frames = read.frames;
				if (read.note !== '') notes.push(read.note);
			}

			if (action === 'get') {
				const reply = await sendCommand(home, ++seq, { action: 'get' });
				return {
					ok: reply.ok,
					appearance: reply.appearance ?? '',
					message: reply.ok ? '已读取当前悬浮窗外观' : reply.message,
				};
			}

			// 空 fields 的 set 没有意义（原生会原样返回当前配置），但也不算错 ——
			// 模型可能只是想确认当前状态。这里只提示一句，不报失败。
			if (Object.keys(fields).length === 0) {
				notes.push('没有传入任何字段，外观未改变（只想读状态请用 action:"get"）');
			}

			const reply = await sendCommand(home, ++seq, { action: 'set', fields });
			return {
				ok: reply.ok,
				appearance: reply.appearance ?? '',
				message: reply.ok
					? `已应用（只列出了你改动的字段时，未列出的字段保持原值）：${summarizeFields(fields)}${notes.join('')}`
					: reply.message,
			};
		},
	};
}

/**
 * 把下发的字段压成一行摘要给模型看。
 *
 * ⚠️ **不能直接 `JSON.stringify(fields)`**：`avatar` 与 `frames` 里装的是
 *   base64 data URI，一帧就几十万字符、64 帧能到几十 MB —— 那会把整条对话
 *   的上下文当场撑爆（而且这些内容模型本来就刚给过，回显毫无价值）。
 *   所以图片类字段只报"有几帧 / 多少字节"。
 */
function summarizeFields(fields) {
	const parts = [];
	for (const [key, value] of Object.entries(fields)) {
		if (key === 'frames') {
			const n = Array.isArray(value) ? value.length : 0;
			parts.push(`frames:${n} 帧`);
			continue;
		}
		// ⚠️ `actions` 里装的是**每个动作的整批帧**，同样不能 stringify ——
		//   5 个动作 × 每动作几十万字符，回显一次就把上下文撑爆。只报动作名与帧数。
		if (key === 'actions') {
			const names = value && typeof value === 'object' ? Object.keys(value) : [];
			const detail = names.map((n) => {
				const spec = value[n] ?? {};
				const frames = Array.isArray(spec.frames) ? spec.frames.length : 0;
				const grid = spec.spriteRows && spec.spriteCols ? `,精灵图${spec.spriteRows}×${spec.spriteCols}` : '';
				return `${n}(${frames} 帧${grid})`;
			});
			parts.push(`actions:{${detail.join(', ')}}`);
			continue;
		}
		if (key === 'avatar' && typeof value === 'string' && value.startsWith('data:')) {
			parts.push(`avatar:自定义图片(${Math.round(value.length / 1024)} KB)`);
			continue;
		}
		parts.push(`${key}:${JSON.stringify(value)}`);
	}
	return parts.join(', ');
}

/** 把一次调用压成一行给用户看的说明。 */
function describeCall(args) {
	if (args?.action === 'get') return '读取当前状态、大小、形状与配色';
	if (args?.action === 'notify') {
		const text = typeof args?.text === 'string' ? args.text : '';
		return text === '' ? '推送状态消息' : `推送状态消息：${text}`;
	}
	if (args?.action === 'state') {
		const bits = [];
		if (args?.runState) bits.push(`状态 ${args.runState}`);
		if (args?.playAction !== undefined) {
			bits.push(args.playAction === '' ? '动作恢复自动' : `动作 ${args.playAction}`);
		}
		return bits.length > 0 ? `驱动宠物：${bits.join(' · ')}` : '驱动宠物动作';
	}
	const bits = [];
	if (args?.state !== undefined) bits.push(String(args.state));
	if (args?.actions !== undefined) {
		const n = args.actions && typeof args.actions === 'object' ? Object.keys(args.actions).length : 0;
		bits.push(`配 ${n} 个动作`);
	}
	if (args?.actionMap !== undefined) bits.push(`状态映射 ${Object.keys(args.actionMap ?? {}).length} 项`);
	if (args?.pinAction !== undefined) {
		bits.push(args.pinAction === '' ? '动作恢复自动' : `钦定动作 ${args.pinAction}`);
	}
	if (args?.size !== undefined) bits.push(`${args.size}dp`);
	// ⚠️ `0` 不是"没给"，是"自动"这个取值本身 —— 用 `!== undefined` 判，
	//   不能写成 `if (args?.boxWidth)`，那会把 `boxWidth:0`（= 恢复自动）静默吞掉，
	//   用户看到的正是"说了改回自动却没反应"。
	if (args?.boxWidth !== undefined || args?.boxHeight !== undefined) {
		const w = args?.boxWidth === undefined || args.boxWidth === 0 ? '自动' : `${args.boxWidth}dp`;
		const h = args?.boxHeight === undefined || args.boxHeight === 0 ? '自动' : `${args.boxHeight}dp`;
		bits.push(`画布 ${w}×${h}`);
	}
	if (args?.showDot !== undefined) bits.push(args.showDot ? '显示状态点' : '隐藏状态点');
	if (args?.shape !== undefined) bits.push(String(args.shape));
	if (args?.shapePath !== undefined) bits.push('自定义形状');
	if (args?.bg !== undefined) bits.push(String(args.bg));
	if (args?.bgOpacity !== undefined) bits.push(`背景不透明度 ${args.bgOpacity}`);
	if (args?.avatarOpacity !== undefined) bits.push(`图片不透明度 ${args.avatarOpacity}`);
	if (args?.spin !== undefined && args.spin !== 0) bits.push(`旋转 ${args.spin}°/s`);
	if (args?.pulse === true) bits.push('呼吸动画');
	if (Array.isArray(args?.frameFiles)) {
		const n = args.frameFiles.length;
		bits.push(args?.spriteRows && args?.spriteCols
			? `精灵图 ${args.spriteRows}×${args.spriteCols}`
			: `轮播 ${n} 帧`);
	}
	if (args?.avatarFile !== undefined) bits.push(`头像 ${path.basename(String(args.avatarFile))}`);
	if (args?.edgeFile !== undefined) bits.push(`贴边图 ${path.basename(String(args.edgeFile))}`);
	else if (args?.avatar === 'none') bits.push('去掉图片');
	else if (args?.avatar !== undefined) bits.push('自定义头像');
	return bits.length > 0 ? bits.join(' · ') : '应用外观改动';
}

/** 把结果渲染成模型可见文本。 */
function renderText(value) {
	const lines = [];
	lines.push(value.ok ? '结果: 成功' : '结果: 失败');
	if (value.appearance !== '') lines.push(`当前外观: ${value.appearance}`);
	if (value.message !== '') lines.push(value.message);
	return lines.join('\n');
}

/**
 * 注册 `bubble` 工具；前置条件不满足时**不注册**。
 * @param {object} ctx - Cordis 上下文（需 `tools` 服务）。
 */
export function apply(ctx) {
	const home = process.env.DSH_HOME;
	if (typeof home !== 'string' || home === '') {
		ctx?.logger?.warn?.('[dsh-android-bubble] 未配置 DSH_HOME，bubble 工具未注册');
		return;
	}
	if (!fs.existsSync(home)) {
		ctx?.logger?.warn?.(`[dsh-android-bubble] DSH_HOME 不存在（${home}），bubble 工具未注册`);
		return;
	}

	// ⚠️ **register 必须包 try/catch**：本插件的工具注册一旦抛异常（工具定义
	// 不合契约、服务未就绪…），异常会从 apply 冒出去 → cordis 判定插件 failed
	// → 引擎打印 `dsh: startup failed: N required plugin did not activate`
	// 并**直接退出**。真机实测同类情形（`dsh-android-logs` 漏了 `output` 声明）
	// 造成 54 次「引擎启动失败」，用户看到的是 App 完全打不开，而表面上与
	// 「一个可选工具」毫无关联。
	//
	// 降级方向：工具注册失败 ⇒ 只有这个工具不存在（模型调用时会明确报「未知工具」），
	// 而不是整个 App 不可用。但**不能静默** —— warn 里带上原始错误。
	try {
		ctx.tools.register(bubbleTool(home));
		ctx?.logger?.info?.(`[dsh-android-bubble] 已注册悬浮球控制工具 ${TOOL_NAME}: ${path.join(home, CMD_FILE)}`);
	} catch (error) {
		ctx?.logger?.warn?.(
			`[dsh-android-bubble] 注册 ${TOOL_NAME} 工具失败，本插件不激活（引擎继续启动）: ${error?.message ?? error}`,
		);
	}
}
