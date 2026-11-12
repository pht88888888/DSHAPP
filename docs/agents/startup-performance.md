# 冷启动性能（真机实测与优化项）

> 「App 很卡、启动很慢」这条反馈的排查记录与落地手段。数字全部来自真机
> （华为 CDY-TN90 / Android 10 / WebView 114.0.5.302），冷启动 = `am force-stop` 后
> `am start`，每次取三次以上一致值。

## 结论：用户可用的那一下从 ~19.3 s 降到 ~15.2 s

| 阶段 | 优化前 | 优化后 | 手段 |
|---|---|---|---|
| 进程起来 → `engine.start()` 之前 | ~5.9 s | ~0.5 s | 见下面 1~3 项 |
| 其中 `--dump-config` 启动前体检 | 2.3 s | 0.06 s | [启动前体检缓存](#1-启动前体检结果缓存) |
| 其中 WebView 降级前端安装 | 1.5 s（且**失败**） | 0 ms（跳过） | [内核够新就不装 chrome73 bundle](#2-内核够新跳过降级前端) |
| 其中 Termux prefix 逐文件改写 | 1.6~1.8 s | 0.08 s（短路） | [改写记账](#3-termux-prefix-改写记账) |
| 其中 profile 初始化与出厂 bundle 登记 | 1.4 s | 0.02 s | [登记记账](#4-profile-初始化与-bundle-登记的记账) |
| `启动引擎` → `引擎就绪`（node 起服务） | 8.1~8.5 s | 7.4 s | [V8 编译缓存](#5-node-编译缓存) |
| `引擎就绪` → Web 首帧 | ~4.0 s | ~3.7 s | 未动（引擎与 GUI 自身的加载） |

`am start -W` 的 `TotalTime` 只有 0.9~1.05 s —— 那只是 Activity 起得来，**不代表能用**：
用户真正等的是引擎就绪加首帧。报告启动时间时要说清楚是哪一个。

## 1. 启动前体检结果缓存

`runtime/engine/.../PreflightDumpCache.kt`。体检是**再起一个 node** 跑
`bin.js --dump-config`（单跑 635 ms，启动里 ~2.3 s 因为抢 IO），而它的输入只有
「引擎资产版本戳 + 引擎目录 + profile 文件内容」。输入没变时，把上次的
`exitCode/stdout/stderr` 直接当结果用，**体检逻辑本身一行不改**（三态判定、
`Repaired` 分支全部照旧跑在缓存输出上）。

两个真机坑，写在这里免得重犯：

- **指纹必须在「引擎就绪」时算，不能在这之前算**。引擎启动过程自己会补模板、写默认值，
  如果在体检前算指纹，下次启动读到的永远是「被上次启动改过」的状态 —— 第一版就是这么写的，
  实测 100% miss（`启动前体检缓存未命中（体检输入变了）`）。
- **小文件只认内容，不认 mtime**。引擎运行期会重写 `cordis.patch.yml`（时间戳变了、内容一字未改），
  按 mtime 记账同样会天天 miss。现在 ≤256 KB 的文件按内容摘要、更大的按 `size@mtime`。

只缓存 `exitCode == 0`：配置真有问题时下次必须真跑，不能拿旧结论骗自己。
缓存最多信 24 小时，文件是 `$DSH_HOME/.preflight-dump`，删掉即回到「每次真跑」。

## 2. 内核够新就跳过降级前端

`EngineCompatibilityInstaller.ensureWebViewCompat(webViewMajor)`。降级前端（chrome73 语法 +
polyfill）是给 `WebView < 94` 的老内核用的。老实现**每次启动**都做：对 32.9 MB 的
`engine.bin` 全量 `sha256` + 解压 7.5 MB 的 `webview-compat.zip`，然后才判断要不要装
（约 1.5 s）。真机是 114，装完还会让页面跑一份老语法 + polyfill。

现在 `MainActivity.detectWebViewVersion()` 把主版本数交给引擎，`≥ 94` 直接返回：
日志里会出现 `内核 114 ≥ 94，跳过降级前端（省下 engine.bin 全量摘要与 zip 解压）`。
门槛常量在 `EngineCompatibilityAssets.OFFICIAL_MIN_WEBVIEW_MAJOR`，与
`MainActivity.MIN_WEBVIEW_MAJOR` 是同一个数，改一处要改两处。

⚠️ 顺带修掉一个回归：裁剪 `engine.bin` 后 `webview-compat.zip` 里的 `engineDigest`
不再匹配，老内核机器上会报 `应用 WebView 降级前端失败: compat 来源 engine.bin 不一致`。
`tools/build-webview-compat.mjs` 已重新生成并同步进 `catalogs/runtime-assets.lock.json`。

## 3. Termux prefix 改写记账

`TermuxBootstrap.ensureNodeRuntimePatched()` 逐文件扫 `$PREFIX` 找 `/data/data/com.termux`
字样并等长改写（首次装完 1.6~1.8 s）。改写是幂等的，判定条件改成
`「node 二进制的 size@mtime」未变 ⇒ 已经改过`，记账文件 `$PREFIX/.node_patched_v1`，
与既有的 `.paths_rewritten_v7` 同一套思路。node 被替换（升级、重新解包）时记账自动失效。

## 4. profile 初始化与 bundle 登记的记账

`BuiltinPluginBundles`。这两步各要再起 node 进程（`init` / `plan` + `register`），
合计 ~1.4 s，而它们的输入只有三样：本次 APK 的引擎（`$ENGINE_DIR/.stamp`）、
用户对 14 个组件的开关、profile 的两个配置文件内容。

- `initializeProfile()`：`profiles/<name>/package.json` 在盘上、且记账
  `init-<name>.stamp` 里的「引擎版本戳 + profile 名」一致 ⇒ 跳过 spawn。
- `ensure()`：跑完之后把 `ensure.stamp` 写成上面三样输入的 SHA-256；下次启动算出来相等就跳过
  `plan` + `register`。**用户在界面里关掉/打开插件、或自己改了 patch 文件，key 就变了**，
  登记动作照旧真跑 —— 缓存只吃掉「什么都没变」的那部分启动。

引擎一升级（`.stamp` 变）两者全部失效，回到完整路径。

## 5. node 编译缓存

引擎进程的环境里加了 `NODE_COMPILE_CACHE=<cacheDir>/node-compile-cache`
（node ≥ 22 支持）。引擎要加载上千个模块，真机实测这一段 8.4 s → 7.4 s。

代价是缓存目录会长到十几 MB（系统 `cacheDir` 下，可被系统回收），删掉只是回到未预热状态。
⚠️ 缓存要**跑满一次**才生效：装完 APK 的第一次冷启动仍然是 8.4 s，第二次起才是 7.4 s。
另外 `am force-stop`（SIGKILL）不会丢已写入的条目，但会丢最后一段 —— 反复强杀后启动时间会有抖动。

## 复现测量

```bash
export PATH=/opt/android-sdk/platform-tools:$PATH
adb logcat -c; adb shell am force-stop com.dsh.android; sleep 2
adb shell am start -W -n com.dsh.android/.ui.MainActivity
# 看这几行（按时间排序）：Termux 引导完成 / 启动阶段 … / 启动前体检结论 /
# 启动引擎 / 引擎就绪 / Web 内核已提交首帧
adb logcat -d | grep -aE "启动阶段|引导完成|体检结论|引擎就绪|首帧"
```

`start()` 里的分段计时由 `BootTimeline` 打在 `启动阶段 <名字>: 累计 Nms (+增量ms)`，
定位新热点时先看这一串，再看引擎进程自己的 `files/logs/engine.log`。
