# 重新生成资产

本文是 [AGENTS.md](../../AGENTS.md) 的分册；先区分源码构建、外部资产恢复与运行生效判据。拥有旧二进制不等于已经具备完整重建配方。

## 组件插件与引擎兼容前端

出厂插件的唯一清单是[组件 catalog](../../catalogs/plugin-components.json)，实现为[统一构建器](../../tooling/plugins/build.mjs)；[旧 tools 入口](../../tools/build-plugin-components.mjs)仅作兼容。插件规范源码放在 `plugins/android/bundle`、`plugins/android/components` 和 `plugins/standalone`，映射由 catalog 决定。

```bash
node tools/build-plugin-components.mjs build --out-dir app/build/generated/plugin-components
node tools/build-plugin-components.mjs check --out-dir app/build/generated/plugin-components
node tools/build-plugin-components.mjs pack-check
```

构建器统一编译客户端、生成确定性 zip 和逐文件 manifest；Gradle 的预构建任务消费同一组产物。不要手工压目录或用旧 zip 覆盖市场安装。修改后的实际版本判断见[资产生效规则](asset-stamps.md)及[插件更新](plugin-updates.md)。

引擎 Web 兼容资产只包含官方 dist 与 engine 客户端文件，不再包含 Android mobileui 树。引擎归档、polyfill、编译目标和工具来源都需要匹配 manifest。兼容资产生成与插件构建分别管理，不互相覆盖。

## 仓库维护的 C 源码

本仓有三份规范源码：

| 源码 | 产物 |
|---|---|
| [linkcompat.c](../../native/linkcompat/linkcompat.c) | `linkcompat.bin` |
| [boosandbox.c](../../native/sandbox/boosandbox.c) | `boosandbox.bin` |
| [boo-sandbox-run.c](../../native/sandbox/boo-sandbox-run.c) | `boo-sandbox-run.bin` |

统一入口为 [native/build.mjs](../../native/build.mjs)，参数、固定 NDK/API/ABI、输出限制和具体命令见[原生来源与配方](../architecture/native-provenance.md)。使用本机 NDK 27.0.12077973 和一个尚不存在的输出目录；工具不下载 NDK，不安装设备，不回写运行 assets。

```bash
node tools/check-native-recipes.mjs
node --test tools/test-native-recipes.mjs
```

这两条是配方与来源校验，不需要 NDK，也不能证明原生产物已编译或已运行。实际编译报告记录命令、编译器和 ELF/符号/字节摘要；新 ELF 与旧资产是否一致、Android 行为是否正确分别验收。不要恢复个人机器编译器绝对路径，也不要重新使用旧 jni 副本作为规范来源。

## 外部运行资产

[运行资产锁](../../catalogs/runtime-assets.lock.json)记录当前持有制品的字节与来源事实，[原生来源文档](../architecture/native-provenance.md)逐项列出仍缺输入。以下项目**不由上述 native runner 重建**：

| 类别 | 仍需恢复或核对的输入 |
|---|---|
| Termux bootstrap、Node | 精确 deb/revision、依赖与完整性、staging 配方 |
| DSH `engine.bin` | 官方 npm tarball integrity、依赖锁与原打包流程 |
| require-builtin/system addons | 精确源码、Node headers、NDK 参数、打包与上游完整性 |
| node-pty | 精确源码 revision、Node/Node-addon-api headers、C++/静态链接和封装配方 |
| adb、ripgrep、sharp wasm | 精确上游版本和完整传递依赖、下载完整性、封装来源 |

不得把本机历史 staging 目录当输入恢复方案。Node 安装判据与运行依赖安装器保持一致；外部源缺口填完后才可声称从零重建。node-pty 的平台限制见[PTY 文档](node-pty.md)，历史参数不能代替锁定输入。

pnpm 是独立的纯 JS 运行资产，也不进入 C runner。当前版本和封装以运行锁及 `EngineRuntimeAssets` 为准；归档条目必须直接为 `bin/pnpm.mjs` 等相对路径，不能多套 `package/`。pnpm 历史主线选择及 Android 约束见[平台说明](platform-android.md)。更换资产后核对版本、生效标记与实际条目，不能只替换文件名。

keyinject 已退休，由 gateway 路径替代；安装器只清旧库。它不是 active APK 资产，也不是当前待补原生源码，不重新打包旧 TLS 注入库。

## 手写运行脚本与桥协议

手写源码不再存放在 APK assets 源目录，规范位置如下：

| 源码位置 | APK 内资产名 |
|---|---|
| [shell runner](../../runtime/scripts/dsh-android-shell.mjs) | `dsh-android-shell.mjs` |
| [root runner](../../runtime/scripts/dsh-android-root.mjs) | `dsh-android-root.mjs` |
| [fetch guard](../../runtime/scripts/dsh-fetch-guard.mjs) | `dsh-fetch-guard.mjs` |
| [UI probe](../../runtime/scripts/ui-probe.js) | `ui-probe.js` |
| [WebView polyfill](../../runtime/webview/webview-polyfill.js) | `webview-polyfill.js` |
| [桥协议](../../contracts/bridge/protocol.json) | `bridge-protocol.json` |

Gradle 的 `stageAndroidAssets` 从规范目录暂存打包，桥协议仅在输出时从 `protocol.json` 改名为 `bridge-protocol.json`。APK 的 `assets.open` 名称与设备落盘名称保持不变；修改源码时不要在旧 assets 目录重建第二份副本。UI probe 保持原生函数表达式和既有语法目标，不因目录移动而改变转译规则。

## 入库与提交

大型生成资产与本机签名凭据不提交；手写脚本、契约、锁、配方和工具版本需受控。assets 下只保留该目录实际负责的静态/生成输入，不再添加已迁移脚本副本；新增受控源码要确认未被根 `.gitignore` 遮住。临时 staging 和一次性脚本放在临时目录或忽略的 build 输出，不作为第二份规范源码。

按当前改动选择检查，不默认安装、上传或运行下游流程。发行候选需绑定真实构建输入和资产，见[构建与发行](build-and-ship.md)及[发布手册](../runbooks/release.md)。
