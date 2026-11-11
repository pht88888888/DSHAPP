# 构建、发行与发布

本文是 [AGENTS.md](../../AGENTS.md) 的分册。当前发行入口是[分步发布 CLI](../../tools/release/cli.mjs)，完整流程以[发布操作手册](../runbooks/release.md)为准；旧 shell 仅转调 CLI，不默认构建、安装、上传或整理远端文件。

## 构建两个 flavor

工具链：JDK17、Gradle8.13、Kotlin2.0.21、AGP8.8.2、compileSdk35、minSdk26。设置本机 `JAVA_HOME`、SDK 与所需网络环境；不依赖仓库里的个人代理或绝对安装目录。先准备[资产锁与来源](regen-assets.md)，再从仓库根构建。

Linux/macOS：

```bash
bash gradlew :app:assembleSystemDebug --console=plain
bash gradlew :app:assembleGeckoDebug --console=plain
```

PowerShell：

```powershell
.\gradlew.bat :app:assembleSystemDebug --console=plain
.\gradlew.bat :app:assembleGeckoDebug --console=plain
```

产物分别位于 `app/build/outputs/apk/system/debug/` 和 `app/build/outputs/apk/gecko/debug/`。system 用系统 WebView，gecko 携带 GeckoView；两种 flavor 都是当前产品，不能把其中一种当作历史版本。

结构迁移中的局部 Kotlin 编译、历史 APK 检查与最终源码构建是不同证据。当前完成情况见[实施状态](../architecture/execution-status.md)，分层输入与执行规则见[测试手册](../runbooks/testing.md)。不因配置阶段或单 flavor 成功而宣布全量验收。

## 签名与版本

最终 applicationId 为 `com.dsh.android`、`com.dsh.webview`，均 15 字符，targetSdk 保持 28。library namespace 不受 15 字符限制。不要通过升 target 或改运行身份修构建错误。

release 签名来自根 `keystore.properties` 指向的密钥，两者均不入库。无凭据时 Gradle 可生成未签名包；未签名包不是正式候选。正式签名是老用户升级身份，必须离线备份并保持一致。debug 与 release 在同 flavor 下共用 applicationId，签名不同不能直接覆盖；默认不卸载自用包或清用户数据。

Linux/macOS：

```bash
bash gradlew :app:assembleSystemRelease --console=plain
bash gradlew :app:assembleGeckoRelease --console=plain
```

PowerShell：

```powershell
.\gradlew.bat :app:assembleSystemRelease --console=plain
.\gradlew.bat :app:assembleGeckoRelease --console=plain
```

产物进入 `app/build/outputs/apk/<flavor>/release/`。正式发布需要双包 versionCode 一致、gecko versionName 对应 system 的 `-gecko` 后缀、证书一致。versionCode 单调递增；恢复旧行为通常发行更高 versionCode 的修复包，不能默认安装低版本。

## 本地候选与来源绑定

```bash
node tools/release/cli.mjs help
node --test tools/release/test/*.test.mjs
```

本地阶段为 `source-record`、`inspect`、`prepare`、`check`；参数和顺序见[runbook](../runbooks/release.md)。构建前记录源码，构建后准备不可变候选，绑定双包、运行资产锁、生成插件 manifest、证书、工具和真实门禁报告。

`inspect` 可检查旧 APK，但会明确 `unverified-existing-artifact`；源码陈旧时标记 stale。检查通过不证明旧 APK 包含当前修改。正式候选拒绝 dirty、缺来源绑定、未完成门禁和开发标记；`--development` 只供本地开发检查，不会升级为正式发布证明。不要在其他成员仍改源码时生成最终 source-record。

## 远端发布边界

本仓不附真实网盘 adapter。publish/promote/archive 只有显式提供 adapter 才能执行；命令会产生实际远端动作，需按用户授权的发布范围执行。默认构建、检查和测试均不调用这些阶段。

目录规则为 `jiulingShare/DSH APP/候选版本/<releaseId>` → `当前最新版` → `历史版本`。同一版本两个 flavor 一起检查、上传和提升。先保留旧双包快照，准确新双包均确认在“当前最新版”后才归档旧双包；部分失败保留状态并可幂等重试，不冒充全部完成。文件名、摘要、目录身份和真实结果以 runbook 及 CLI 核验为准，不凭旧 FID 或同名文件猜测。

## 结果与设备验收

验证报告分别记录用例、跳过、真实输入和证据。签名、清单、APK 内 ZIP/manifest 与生成资产一致性由发布检查完成；进程、桥、权限和用户状态由需要设备的层级验收。编译成功不代表页面已生效，手工产物检查也不能替代 fresh 构建与测试报告。

设备安装仅在任务明确需要且候选身份、签名及数据保全条件满足时执行；不默认卸载、降级或清数据。日志与诊断入口见[日志分册](logging.md)，资产生效判据见[asset-stamps](asset-stamps.md)。
