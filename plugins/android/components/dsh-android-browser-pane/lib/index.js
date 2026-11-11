/**
 * dsh-android-browser-pane —— 官方右侧边栏「AI 浏览器」入口的**宿主半**。
 *
 * 全部实现都在客户端半（`lib/client.js`）：它由
 * `@deepseek-ai/dsh-client-modules` 按本包 `package.json` 的 `dsh.client`
 * 声明自动编入浏览器侧。
 *
 * ⚠️ 但包**必须**有一个 Node 侧入口：`main` 指向的模块会被 Cordis 真正 import，
 *   而 `lib/client.js` 是浏览器代码（顶层引用 `window.__ModuleLoader__`），
 *   在 Node 里 import 会当场抛 `window is not defined`。
 *   这个空壳文件就是那个入口 —— **不要删**。
 *
 * 为什么不把这个入口做进 `dsh-android-mobile`（移动端适配插件）里：
 * 那个包的 `lib/client.js` 在不同分支上差异极大（dev 分支比 main 多 17 万字节），
 * 往它里面加东西就必须重打它的 zip，而 zip 是跨工作树共享的资产 ——
 * 一次重打会把另一条分支的移动端修复整个回退掉。独立成包互不干扰。
 *
 * @module dsh-android-browser-pane
 */

export const name = 'dsh-android-browser-pane';

/**
 * 宿主半不做任何事。
 *
 * 不声明 `inject`：这个包不需要任何宿主服务，声明了反而会在服务缺失时
 * 变成 pending、连带整条插件链不激活。
 */
export function apply() {
	// 客户端半自己注册侧栏项，宿主侧没有可做的事。
}
