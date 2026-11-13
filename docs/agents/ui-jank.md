# 使用中卡顿（Web 侧）排查记录

> 「用着卡」和「启动慢」是两条不同的账。启动那条见 [startup-performance.md](startup-performance.md)；
> 这里记的是**界面跑起来之后**的卡顿：进入面板页、滚动会话、页面切换。
> 全部数字来自真机（华为 CDY-TN90 / Android 10 / WebView 114），
> 手法是 CDP `Profiler` 采样 + 页面内 `PerformanceObserver('longtask')`。

## 症状：从侧边栏进「插件」页要等

页面内实测（`am force-stop` 后冷启，`Page.reload` → 点「插件」→ 逐 25ms 采样正文变化）：

| 指标 | 优化前 | 优化后 |
|---|---|---|
| 点击 → 页面出现「安装、启用和配置插件」 | 1.33~1.44 s | 1.33~1.44 s（官方 React 渲染，未变） |
| 该窗口内的 long task | 990~1000 ms | 757~852 ms |
| 我们自己代码的自身耗时 top | `mpIsNarrow` 716 ms + `getPropertyValue` 550 ms | 均不再出现在 top 6 |
| `pluginInventory/list` 服务端耗时 | 650~2900 ms（冷/热差很大） | 同（引擎侧，不在本仓） |

剩下的 ~800 ms 是官方 React 把上百个插件行渲染出来的 `(program)` 时间（布局 + 提交），
剩下的 650 ms 是引擎侧 `pluginInventory/list`，两条都不在本仓能改的范围。

## 修掉的两处「读一下就强制整个样式重算」

两处都是**同步布局读**被挂在了每个 mutation 批次后的 rAF 里，等于每次界面更新都付一次全量重排。

| 位置 | 原来 | 现在 |
|---|---|---|
| `plugins/android/components/dsh-android-mobile/src/layout.js` | `mpIsNarrow()` 每次 `window.innerWidth`（同步布局读），被 `mpSyncMobileAttr`、body 上的 MutationObserver、各处 rAF 高频调用 | 宽度缓存 `mpViewportWidth()`，只在 `resize` / `orientationchange` / `visualViewport.resize` 失效；`mpIsMobile()` 再加一层「一帧内恒定」的缓存，跨帧必重算 |
| `src/shell.js`（`mpSyncFloatNavGap`）、`src/settings-navigation.js` | `getComputedStyle(root).getPropertyValue('--dsh-android-system-top')` | 改成读**内联**变量 `documentElement.style.getPropertyValue(...)`：原生（`MainActivity.pushSystemBarInset`）本来就是用内联样式写的，内联读取不触发任何样式计算；只有内联为空（原生还没推过）才退回 computed 兜底 |

判据完全等价：宽度只在旋转/窗口变化时变，状态栏高度只会被原生改写。

第三处（同一天继续剥）：顶栏三件量算（`mpSyncHeaderHeight` / `mpSyncHeaderCenter` / `mpSyncFloatNavGap`）
原来只是「一帧最多一次」，而流式输出时**每帧都有 mutation**，等于每帧白扔几次
`getBoundingClientRect` + 一次 `getComputedStyle`。改成首帧立刻量、之后最多每 200ms 一次，
并加一个末尾补一次的定时器保证最终一致（纯外观量算晚 200ms，用户看不出来）。

滚动帧间隔（插件页 1114px 长列表，页面内 rAF 采样 140 帧、每帧滚 37px）：
`p50 19.7ms / p90 29.7ms / p95 30.5ms / max 47ms / >33ms 3 帧` —— 这条是优化后基线，
没留优化前同口径数据，别拿它当收益证明。

## 顺带修掉的回归：换了 APK，出厂插件不更新

上一轮给 `BuiltinPluginBundles.ensure()` 加记账短路时，指纹只算了
「引擎版本戳 + 14 个组件开关 + profile 文件内容」，**没算 APK 里那份出厂包的内容**。
于是「APK 换了、出厂插件更新了、引擎戳没变」时记账相等 ⇒ 登记动作被跳过 ⇒ 设备上一直跑上一版插件。

实测现场：本地改完 `dsh-android-mobile`、重装 APK，设备里
`files/home/.dsh/node_modules/dsh-android-bundle/node_modules/dsh-android-mobile/lib/client.js`
的 sha256 与旧的完全一样（页面里那个 `client.js&rev=…` 也没变），改什么优化都看不见。

现在指纹里加了 `asset:<id>=<manifest.revision>`（只读几十字节的 manifest，不碰 zip 本体）。

## 复现命令

```bash
# 1) 打开页面并计时（需要先 adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>）
node /tmp/cdp-plugin-open3.mjs "$WS"      # 3 次 reload + 点「插件」，输出 markAt 与 longtask
node /tmp/cdp-profile3.mjs  "$WS"         # Profiler 采样，按自身耗时排序并打印调用链
```

改完插件源码后必须重建资产并装包，否则量到的还是旧代码：

```bash
node tools/build-plugin-components.mjs build --out-dir app/build/generated/plugin-components --install-assets
bash gradlew :app:assembleSystemDebug --console=plain
bash build/verification/deploy-apk.sh app/build/outputs/apk/system/debug/app-system-debug.apk com.dsh.android
# 核对设备上真的是新代码：
adb shell "run-as com.dsh.android sha256sum files/home/.dsh/node_modules/dsh-android-bundle/node_modules/dsh-android-mobile/lib/client.js"
sha256sum app/build/generated/plugin-components/npm/dsh-android-bundle/node_modules/dsh-android-mobile/lib/client.js
```

## 还没动的（下一步候选）

- 官方 `dsh-client-ui-sidebar-documentpreview` 在 `scroll` 里做 `measure`（真机采样里
  `getBoundingClientRect` + `measure` + `scroll` 合计 ~740 ms），属于官方代码；移动端是否整块关掉要评估功能影响。
- `pluginInventory/list` 冷的时候 2.9 s：可以试着在 App 空闲时预热一次同一份 RPC，
  但官方页面不会用我们缓存的结果，收益取决于引擎侧是否自己缓存。
