# AGENTS.md — DSH Android 客户端

Android 用 Termux 运行 Node 和官方 DSH 引擎，system WebView / GeckoView 加载官方 Web GUI。移动适配走插件接缝，不另建聊天界面。本机路径和设备信息只放 `AGENTS.local.md`（不入库）。

## 硬约束

- system / gecko 的最终 `applicationId` 分别为 `com.dsh.android` / `com.dsh.webview`，必须各 **15 字符**，以便等长替换 Termux ELF 路径；library `namespace` 和 Kotlin package 不受此限制。`targetSdk` 保持 **28**（29+ 无法执行私有目录二进制）；`minSdk=26`、`compileSdk=35`。
- 保留正式签名、包身份、用户数据和插件状态；默认不卸载、不降级、不清数据。结构调整不顺带改桥协议、存储或降级策略。出厂包在 `$DSH_HOME/node_modules`；profile registry 版本优先，禁用或卸载的插件不因升级复活。Android 保持一个顶层 bundle、14 个原组件。
- 以 [Gradle 模块注册](settings.gradle.kts)和[所有权清单](docs/architecture/current-architecture.md)为准。`app` 只负责装配和宿主动作；底层不反向依赖 Activity、Service、app 的 `R` / `BuildConfig`，也不靠万能上下文绕过边界。状态和资源由所属组件负责创建、取消与释放。
- system / gecko 共用桥契约：签名、snapshot/invoke、异步结果和标注 ack 一致。新增 Web 能力同时接好 Manifest、`WebCapability` / `WebHostCallbacks.capability`、双内核回调与 Activity 串行权限请求，并验证实际行为。

## 构建、验收与发布

先配置 JDK 17 和 Android SDK，准备[锁定资产](docs/agents/regen-assets.md)。工具版本以[工具链锁](catalogs/toolchain.lock.json)和 Gradle Wrapper 为准，不把本机路径或代理写入通用配置。

```bash
bash gradlew :app:assembleSystemDebug :app:assembleGeckoDebug --console=plain
bash tools/check-architecture.sh
```

- Debug / Release、Windows 命令与产物位置见[构建分册](docs/agents/build-and-ship.md)。构建不等于安装、上传或发布；最终集成覆盖两个 flavor。
- 按[测试手册](docs/runbooks/testing.md)选择 L0–L3，报告实际输入、用例数、跳过数和失败；缺输入不算通过，不抬基线、不弱化守卫。旧 APK 验签不能证明当前源码生效；真机证据须绑定源码和实际测试双包。
- 正式候选按[发布手册](docs/runbooks/release.md)绑定源码、资产、签名与门禁。远端操作须明确授权：核对两个新包均已到目标目录，再归档已确认的旧双包；不覆盖同名不同内容。用夸克 Skill 手工操作时见[夸克网盘上传与归档](docs/runbooks/release.md#夸克网盘-skill-手工上传)，不替代正式发布门禁。

## 按需查阅

- [开发分册索引](docs/agents/README.md)：构建、双内核、真机调试、插件、日志和救援。
- [架构规范](docs/architecture/conventions.md) · [所有权及运行路径](docs/architecture/current-architecture.md) · [实施状态](docs/architecture/execution-status.md)：设计、现行边界与实际验证结果分别看。
- [资产重建](docs/agents/regen-assets.md) · [版本戳](docs/agents/asset-stamps.md) · [插件更新](docs/agents/plugin-updates.md) · [远程插件来源](docs/architecture/remote-plugin-provenance.md)：以独立仓库为远程插件生产入口；不从旧副本发包，不恢复已退休的 keyinject。
