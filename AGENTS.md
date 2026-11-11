# AGENTS.md — DSH Android 客户端

本项目用 Termux 环境运行 Node 和官方 DSH 引擎，由 system WebView 或 GeckoView 加载官方 Web GUI；移动端适配走插件接缝。不要另建聊天界面。

本页只放硬约束、工作入口与索引。实现细节按需读[开发文档索引](docs/agents/README.md)，工程约定见[架构规范](docs/architecture/conventions.md)，结构迁移进度见[实施状态](docs/architecture/execution-status.md)。批准方案是历史设计基线，不等于当前验收结果。

## 平台硬约束

1. 最终 APK 的两个 `applicationId` 分别是 `com.dsh.android`、`com.dsh.webview`，**均必须恰好 15 个字符**。Termux ELF 中 `/data/data/com.termux/files` 的运行路径需要等长替换；实际包身份由 app 传入运行组件。此限制不适用于 Android library 的 `namespace` 或 Kotlin package，不能把所有 library namespace 改成 app 的名字。
2. 最终应用 `targetSdk = 28`，不能升到 29+。Android 对 target 29+ 禁止执行自身私有目录的二进制，而 Node/bash/dpkg 都在应用数据目录内。保持 `minSdk = 26`、`compileSdk = 35`。
3. 保留正式签名、两个 flavor 身份和既有用户目录。结构迁移不顺带修改桥协议、用户存储格式、插件启用状态或失败策略；默认不卸载、不清空数据。

工具链：**JDK 17 / Gradle 8.13 / Kotlin 2.0.21 / AGP 8.8.2**，以[根插件版本](build.gradle.kts)、[Wrapper](gradle/wrapper/gradle-wrapper.properties)和各模块 Gradle 文件为准。网络代理由本机环境配置，不把个人代理端口或 JDK 安装绝对路径写成项目要求。

## 构建入口

先设置 `JAVA_HOME` 指向本机 JDK 17，并准备锁定资产。Linux/macOS 从仓库根运行：

```bash
bash gradlew :app:assembleSystemDebug --console=plain
bash gradlew :app:assembleGeckoDebug --console=plain
```

PowerShell：

```powershell
.\gradlew.bat :app:assembleSystemDebug --console=plain
.\gradlew.bat :app:assembleGeckoDebug --console=plain
```

产物分别位于 `app/build/outputs/apk/system/debug/` 和 `app/build/outputs/apk/gecko/debug/`。最终集成必须覆盖两种 flavor；一次局部编译通过不能代替另一 flavor 的编译或设备运行。构建不包含安装、上传和发布。

- [资产重建与来源](docs/agents/regen-assets.md)：插件由统一构建器生成，大型运行资产需显式准备；部分外部资产仍缺完整重建输入。
- [构建与发行](docs/agents/build-and-ship.md)：release 签名与候选检查。
- [分步发布操作手册](docs/runbooks/release.md)：本地候选与显式远程 adapter。

## 模块与所有权

实际 Gradle 注册以[settings.gradle.kts](settings.gradle.kts)为准。新增实现放在对应 owner，不回填到 app 根包，也不为减行机械搬运状态。

| 位置 | 职责与边界 |
|---|---|
| `app/` | Application 装配、Activity/Window、EngineService 的通知与 WakeLock、系统 Intent、备份和跨领域诊断组装 |
| `core/contracts/` | 小型共享模型与窄接口；旧 Kotlin package 可为兼容继续保留 |
| `core/logging/` | 日志写入、轮转、事件、脱敏；不主动引用引擎或界面采集快照 |
| `runtime/termux/` | prefix/bootstrap/Node、ELF 重写与安装锁 |
| `runtime/engine/` | 资产安装、启动快照、进程、探测、健康与救援决定；不依赖具体命令桥或 Service |
| `platform/privilege/` | Root/Shizuku/ADB 连接、配对与执行 |
| `web/contract/` | WebHost/PageBridge、权限与文件通道契约 |
| `web/host/` | system/gecko 实现与工厂；GeckoView 仅进入 gecko 变体 |
| `feature/` | 按领域的功能实现；只有已注册模块才是现行构建边界 |
| `native/` | 仓库维护的 linkcompat 与 sandbox 源码、固定配方构建入口 |
| `plugins/android/` | 一个顶层 bundle 与 14 个内部组件源码 |
| `plugins/standalone/` | 多模态、核心记忆和预设等独立插件源码 |
| `catalogs/`、`tooling/plugins/` | 唯一[插件清单](catalogs/plugin-components.json)、来源锁与统一构建实现；旧 tools 入口仅兼容 |

app 提供引擎所需的状态读口与宿主动作，底层不能借 app 的 `BuildConfig`、`R`、Activity 或万能参数袋绕回装配层。资源只由所属组件创建和释放：探测器结束临时进程，监控器停止采样线程，页面/窗口宿主按自身生命周期解绑。完整约定与决策见[规范](docs/architecture/conventions.md)和[ADR](docs/architecture/adr/README.md)。

## 运行路径

以下均相对于 `filesDir`；迁移模块不迁移用户数据。

| 路径 | 用途 |
|---|---|
| `termux/usr/` | `$PREFIX`：Termux、Node、pnpm、原生依赖；不是根下的 `usr/` |
| `termux/home/` | Termux shell home |
| `engine/` | APK 引擎解包与兼容产物 |
| `home/` | 引擎 HOME |
| `home/.dsh/` | DSH_HOME、配置、凭据、会话、插件与 profiles |
| `workspace/` | 用户工作区 |
| `logs/` | 日志、现场与诊断导出 |

出厂包位于 `$DSH_HOME/node_modules`，profile registry 版本优先。用户禁用/卸载状态不能因启动或 APK 升级而复活。Android 顶层仍只有一个聚合 bundle，内部保留 14 个组件，见[插件规则](docs/agents/plugin-updates.md)。

## 修改与验证

先确认本项目规则、现有写法和当前任务的验证入口。验证只覆盖当前改动；报告实际执行的用例数、跳过数、输入和失败。编译通过不代表资产或页面已生效，历史 APK 的签名检查也不证明包含当前源码。

```bash
bash tools/check-architecture.sh
```

架构守卫检查职责边界、体积基线、权限及双内核桥等规则；以脚本实际输出为准。不能为使检查通过而抬高基线、屏蔽检查或把缺输入当成功。分层测试输入与命令见[测试手册](docs/runbooks/testing.md)，边界约定见[规范](docs/architecture/conventions.md)，结果见[实施状态](docs/architecture/execution-status.md)。

新增 Android Web 能力需要同时具备：

1. app Manifest 声明权限。
2. `WebCapability`/`WebHostCallbacks.capability` 中的能力通道。
3. system/gecko 内核回调与 Activity 的串行运行时权限申请。

桥签名、snapshot/invoke 模式、异步结果与标注 ack 必须保持双内核一致；不凭签名列表通过就声称行为已覆盖。

## 按任务查文档

| 任务 | 入口 |
|---|---|
| 平台判断、Node、native、子进程 | [平台适配](docs/agents/platform-android.md)、[原生来源与配方](docs/architecture/native-provenance.md) |
| link、沙箱、PTY、附件 | [link](docs/agents/link-compat.md)、[sandbox](docs/agents/sandbox.md)、[node-pty](docs/agents/node-pty.md)、[附件持久化](docs/agents/attachment-fsync.md) |
| Web 权限、双内核、移动布局 | [WebView](docs/agents/webview-basics.md)、[Gecko](docs/agents/webview-gecko.md)、[移动适配](docs/agents/mobile-ui.md) |
| 救援、保活、日志与诊断 | [救援](docs/agents/rescue-mode.md)、[保活](docs/agents/keep-alive.md)、[日志](docs/agents/logging.md) |
| 资产、出厂插件、市场更新 | [生效判据](docs/agents/asset-stamps.md)、[插件更新](docs/agents/plugin-updates.md)、[重建](docs/agents/regen-assets.md) |
| 远程插件开发与 npm 基线 | [来源审计](docs/architecture/remote-plugin-provenance.md)；独立仓库是生产入口，本仓旧副本仅供参考 |
| 发布双包 | [构建与发行](docs/agents/build-and-ship.md)、[发布 runbook](docs/runbooks/release.md) |

不要使用本仓不存在的 `termux-kit/tools/verify-kit.ps1` 作为验收命令。keyinject 已退休，当前只清理旧库；不恢复 TLS 注入资产，也不把它算作 active 资产缺源。
