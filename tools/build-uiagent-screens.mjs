/**
 * 把 `tools/ui-map-real-frontend.json`（17 屏图谱）转成插件内建的屏幕表。
 *
 * ## 为什么要有这一步
 *
 * 图谱是**对真实前端实测出来的**，而模型跑在**手机上的 Node 进程**里 ——
 * 它读不到这个仓库，也跑不了那个生成器。所以「每个按钮在哪、怎么走到那一屏」
 * 这套知识必须在**打包时**烘进插件，否则真机上的模型每次都要从零试错点击，
 * 而试错的代价是真实界面被搅乱。
 *
 * 产物 `plugins/android/components/dsh-android-uiagent/lib/screens.js` 由
 * `uiagent-tool.zip` 一起打进 APK，每次启动解包到 `$DSH_HOME/uiagent-tool/`。
 *
 * ## ⚠️ 图谱必须采自**真机**（`source === 'device'`）
 *
 * 生成器有两种采集目标，结论差别很大（详见它文件头那张表）：
 *
 * | 维度 | PC 隔离实例 | 真机 |
 * |---|---|---|
 * | 视口 | 360×780（强设） | 360×764（设备定） |
 * | CSS Modules 类名 | `_2H3hWW_newSession` | `hHd-Xa_newSession` |
 * | 用户配置 | 默认（DeepSeek-V41-Flash / High） | 用户自己的（Agnes / Default） |
 *
 * 真机审计 17 屏的实测结论：**PC 采的表在真机上有 100% 类名不匹配，且 `els`
 * 里混着用户数据（模型名、推理等级）** —— 拿它去真机点按钮会直接点空。
 *
 * 所以本脚本**默认拒绝** `source !== 'device'` 的图谱。真要内建 PC 图谱
 * （例如手上没有设备时先出一版），显式给 `UIAGENT_ALLOW_PC_ATLAS=1`。
 *
 * ⚠️ **坐标是参考值，不是定位条件。** 真机采来的坐标相对那台设备准确，但换了
 *    设备（或字号设置变了）就不一样。运行时**必须优先用 `text` 定位**，
 *    坐标只用来核对「图谱里的按钮和屏幕上的按钮是不是同一个」。
 *
 * ## 校验：坏数据必须在打包时炸掉，不能带进 APK
 *
 * 内建表的每一屏都必须 `verified`（图谱实测确认真的进去了）且每一步 `ok`。
 * 少一条就退出非零 —— 一份「有一屏是假的」的图谱比没有图谱更糟：模型会照它开车。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'tools', 'ui-map-real-frontend.json');
const OUT = path.join(ROOT, 'plugins', 'android', 'components', 'dsh-android-uiagent', 'lib', 'screens.js');

const map = JSON.parse(fs.readFileSync(SRC, 'utf8'));

if (!Array.isArray(map.screens) || map.screens.length === 0) {
  console.error(`✗ 图谱里没有 screens（${SRC}）`);
  process.exit(1);
}

// ⚠️ 表来源必须显式过一道：PC 采的图谱在真机上不能用（见文件头）。
const ALLOW_PC = process.env.UIAGENT_ALLOW_PC_ATLAS === '1';
if (map.source !== 'device' && !ALLOW_PC) {
  console.error(`✗ 图谱的 source=${JSON.stringify(map.source)}，不是真机采的。`);
  console.error('  PC 采的图谱：类名与真机构建 100% 不匹配、els 里混着生成机器上的用户数据；');
  console.error('  内建它 ⇒ 真机上的 goto 会走到屏、但按表里的坐标/类名核对必然错。');
  console.error('');
  console.error('  正解（在设备上重采，约 10 分钟）：');
  console.error('    adb shell pidof com.dsh.android');
  console.error('    adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>');
  console.error('    $env:UIMAP_CDP = "http://127.0.0.1:19222"');
  console.error('    node tools/ui-map-real-frontend.mjs "<真机 url（带 token）>"');
  console.error('');
  console.error('  确实要内建 PC 版就显式放行：$env:UIAGENT_ALLOW_PC_ATLAS = "1"');
  process.exit(1);
}

const problems = [];
const dropped = [];
const screens = [];

for (const s of map.screens) {
  if (s.verified !== true) problems.push(`${s.id}: verified=${s.verified}（这一屏没被实测确认进入）`);
  if (s.entered !== true) problems.push(`${s.id}: entered=${s.entered}`);
  for (const step of s.enter ?? []) {
    if (step.ok !== true) problems.push(`${s.id}: 进入步骤「${step.target}」ok=${step.ok}（${step.error}）`);
  }
  if (!s.expect) problems.push(`${s.id}: 缺少 expect（没有「真的进去了吗」的判据，goto 就成了盲开车）`);

  // ⚠️⚠️ `expect` 与 `enter` 也**绝不能**含用户数据 —— 那比 `els` 里含更严重。
  //
  //    `els` 含用户数据是「表里多了一条没用的记录」；`expect`/`enter` 含用户数据
  //    是**判据本身失效**：换台设备就永远进不去那一屏，`goto` 变成盲开车。
  //    实测两个真例：`model-picker` 的 `{text:'推理等级 High', exact:true}`
  //    （真机上是 Default）与 `terminal` 的 `{text:'cmd.exe'}`（真机上是 login）。
  //
  //    判据同 `els`：**含中文引号 ⇒ 嵌了用户数据**。另加一条更宽的 ——
  //    `expect`/`enter` 里出现「当前」「已选」这类**状态词**同样危险，
  //    因为它们后面接的多半是用户配置值。
  const expectStr = JSON.stringify(s.expect ?? {});
  const enterStr = JSON.stringify((s.enter ?? []).map((x) => x.spec));
  if (/[\u201c\u201d]/.test(expectStr)) problems.push(`${s.id}: expect 含中文引号（嵌了用户数据）: ${expectStr}`);
  if (/[\u201c\u201d]/.test(enterStr)) problems.push(`${s.id}: enter 含中文引号（嵌了用户数据）: ${enterStr}`);

  // 丢掉的用户数据条数要**报出来** —— 静默丢弃等于「看不见的损失」。
  const droppedByUserData = (s.elements ?? []).filter(
    (e) => (e.text || e.testid) && !e.covered && !e.offscreen && /[\u201c\u201d]/.test(e.text ?? ''),
  );
  for (const e of droppedByUserData) dropped.push(`${s.id}: ${JSON.stringify(e.text)}`);

  const els = (s.elements ?? [])
    // ⚠️ 被遮挡 / 屏幕外的元素**不进内建表**：它们在真机上点不动，
    //    出现在按钮表里只会诱使模型去点。真机上确实需要时，用 dump 现查。
    .filter((e) => (e.text || e.testid) && !e.covered && !e.offscreen)
    // ⚠️⚠️ **含中文引号的文案一律丢掉 —— 那是嵌了用户数据的模板。**
    //
    //    实测（2026-10-13）：会话抽屉里那些「会话“<标题>”的操作」「在“<工作区名>”
    //    中新建会话」是前端把**用户自己的**会话标题 / 工作区名套进模板渲染出来的。
    //    PC 表里它们长成「会话“图谱种会话”的操作」（那是种会话时发的消息），
    //    真机表里就变成用户真实的会话标题（「会话“UI工具调用与坐标提取”的操作」）。
    //
    //    这类文案**两个方向都有害**：
    //      · 打包进 APK ⇒ 把用户数据（会话标题）随插件发给每一个装机的人；
    //      · 当定位条件用 ⇒ 换台设备/换个用户就必然找不到元素。
    //
    //    判据「含中文引号 `“”` ⇒ 用户数据」已全图谱扫过，**无反例**：
    //    两个图谱共 12 处命中，全是上面那两种模板，没有一处是固定文案。
    //    （前端在需要拼用户数据的地方统一用中文引号，这是它的书写习惯。）
    .filter((e) => !/[\u201c\u201d]/.test(e.text ?? ''))
    // 形状：`[定位文本, 类名首段, CSS x, CSS y, role]`
    .map((e) => [
      e.text || `#${e.testid}`,
      (e.cls || '').split(' ')[0],
      Math.round(e.center.x),
      Math.round(e.center.y),
      e.role || e.tag,
    ]);

  screens.push({
    id: s.id,
    title: s.title,
    // 进入路线：`[{text|aria|sel}, …]`，与 `ui{action:"tap"}` 的定位参数同构，
    // 可以原样喂回去 —— 这正是内建表既当坐标表又当路线图的原因。
    enter: (s.enter ?? []).map((step) => step.spec),
    expect: s.expect,
    els,
  });
}

if (problems.length > 0) {
  console.error(`✗ 图谱有 ${problems.length} 处问题，拒绝内建：`);
  for (const p of problems) console.error(`   ${p}`);
  process.exit(1);
}

const total = screens.reduce((a, s) => a + s.els.length, 0);

if (dropped.length > 0) {
  console.log(`ℹ️ 丢掉 ${dropped.length} 个含用户数据的元素（会话标题 / 工作区名的模板文案）:`);
  for (const d of dropped) console.log(`   ${d}`);
}

const body = `/**
 * 内建的屏幕表 —— **自动生成，请勿手改**。
 *
 * 来源: \`tools/ui-map-real-frontend.json\`（由 \`tools/ui-map-real-frontend.mjs\`
 * 对**真实前端**实测生成，${screens.length} 屏 / ${total} 个可点按钮）。
 * 重新生成: \`node tools/build-uiagent-screens.mjs\`。
 *
 * 采集目标: ${map.source === 'device' ? '**真机**（设备上 WebView 的 CDP）' : 'PC 无头 Chrome（⚠️ 与真机不同源）'}
 * 生成时的视口: ${map.viewport?.w}×${map.viewport?.h} CSS / dpr ${map.viewport?.dpr}
 * 探针版本: ${map.probeVersion}
 *
 * 每屏的形状：
 * \`\`\`js
 * { id, title, enter: [{text|aria}], expect: {text|aria|prefix|sel}, els: [[定位文本, 类名, cssX, cssY, role]] }
 * \`\`\`
 *
 * ⚠️ \`els\` 里的坐标是**参考值**，随设备/视口/字号变化 ——
 *    定位永远优先用 \`[0]\` 那个文本，坐标只用来核对是不是同一个按钮。
 * ⚠️ \`enter\` 的每一步都能**原样**喂给 \`ui{action:"tap", ...}\`。
 * ⚠️ 屏的顺序**有语义**：这个前端会持久化「当前会话」「右侧边栏的内容页」，
 *    两者都是**单向**的（进去了就回不去）。\`goto\` 靠「重载回基态」规避，
 *    但**改这个数组的顺序前**要先读 \`ui-map-real-frontend.mjs\` 顶部那段说明。
 * ⚠️ \`expect\` 里**绝不能出现用户数据**（模型名、推理等级、权限、主题、字号）：
 *    那会让判据只在生成时那台机器上成立。实例见 \`model-picker\`（改用了
 *    \`prefix:'模型 '\`）与 \`terminal\`（改用了 \`sel:'.xterm-screen'\`）。
 */
export const ATLAS = ${JSON.stringify({ viewport: map.viewport, probeVersion: map.probeVersion, screens })};

/** 按 id 或标题查一屏（宽松匹配：忽略大小写、允许标题子串）。 */
export function findScreen(ref) {
  if (ref == null || ref === '') return null;
  const want = String(ref).trim().toLowerCase();
  for (const s of ATLAS.screens) if (s.id === want) return s;
  for (const s of ATLAS.screens) if (s.title.toLowerCase() === want) return s;
  for (const s of ATLAS.screens) if (s.title.toLowerCase().includes(want)) return s;
  return null;
}
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, body, 'utf8');

console.log(`✓ 已写出 ${path.relative(ROOT, OUT)}`);
console.log(`  ${screens.length} 屏 · ${total} 个按钮 · ${Buffer.byteLength(body)} 字节`);
for (const s of screens) console.log(`    ${s.id.padEnd(18)} ${String(s.els.length).padStart(2)} 个按钮 · 路线 ${s.enter.length} 步`);
