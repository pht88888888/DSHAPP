# 参与贡献

感谢你有兴趣贡献代码。本项目是一个**原生 Android + native 层 + Node 引擎**的复合体，
有些地方「写错了能编译通过，但真机上一定炸」。动手前请花十分钟读完这份文档。

[English below ↓](#contributing-english)

---

## 开始之前

### 1. 两条硬约束（违反必炸，且报错不指向真因）

**① 包名必须恰好 15 字符**

最终 APK 的 `applicationId` 分别为 `com.dsh.android`、`com.dsh.webview`（均 15 字符）。Termux 官方二进制把
`/data/data/com.termux/files`（27 字节）烤进 ELF 的 `.dynstr`，而
`TermuxBootstrap.patchElfInPlace()` 只能做**等长原地覆盖**。
替换串 `/data/data/<包名>/` = 12 + N = 27 ⇒ **N = 15**。

长度不等时该函数**静默 `return 0`**，不抛异常。症状：运行时
`CANNOT LINK EXECUTABLE` 或 `dpkg: Permission denied`。

**② targetSdk 必须保持 28**

不能升到 29+。Android 10 起，targetSdk ≥ 29 的应用不允许 `execve()` 自身私有目录
里的文件，而 node / bash / dpkg 全在 `filesDir` 下。

> **不要为了「修」某个报错去升 targetSdk 或改包名。** 那正是这两条约束禁止的事，
> 而且报错信息不会告诉你真相。

细节见 [docs/agents/constraints.md](../docs/agents/constraints.md)。

### 2. 静默失败陷阱

| 陷阱 | 后果 | 判据 |
|---|---|---|
| 改了 `.zip` / `.bin` 资产没改版本戳 | 解包被跳过，**新资产永不生效**，且完全静默 | [asset-stamps.md](../docs/agents/asset-stamps.md) |
| 改了插件源码没更新生成产物 | APK 里是旧代码 | 统一构建器与逐文件 manifest 校验 |
| 「构建成功」当「改动生效」 | 装上去还是老行为 | `adb logcat -d -s DSH:I` 看落盘行 |

插件由[唯一组件清单与统一构建器](../docs/agents/plugin-updates.md)生成 ZIP 和逐文件 manifest，
Gradle 消费生成产物。不要手工压缩插件目录；其他运行资产按[资产重建说明](../docs/agents/regen-assets.md)准备。

## 开发环境

```powershell
$env:JAVA_HOME = "<JDK 17 路径>"   # 必须显式设置

.\gradlew.bat :app:assembleSystemDebug --console=plain
.\gradlew.bat :app:assembleGeckoDebug --console=plain
```

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| AGP | 8.8.2 |
| Kotlin | 2.0.21 |
| Gradle | 8.13 |
| compileSdk / minSdk / targetSdk | 35 / 26 / **28** |

⚠️ **构建资产需要自备**（约 135 MB，不在仓库）。见
[regen-assets.md](../docs/agents/regen-assets.md)。

## 验证你的改动

按[分层测试手册](../docs/runbooks/testing.md)选择与改动对应的检查；构建、安装和发布是独立操作，见[发布手册](../docs/runbooks/release.md)。

**「构建成功」不等于「改动生效」。** 装到真机后用日志确认（TAG 恒为 `DSH`）：

```powershell
adb install -r app\build\outputs\apk\system\debug\app-system-debug.apk
adb logcat -c ; adb shell am start -n com.dsh.android/com.dsh.android.ui.MainActivity
adb logcat -d -s DSH:I
```

关键行（引擎就绪、界面加载完成、各 `ensureXxx` 的落盘行）见
[分层测试手册](../docs/runbooks/testing.md)。

## 提交 PR

1. **先开 Issue 讨论** —— 尤其是涉及 native 层、沙箱、插件、资产的改动。
2. Fork → 建分支 → 改动 → 自测。
3. **在 PR 描述里写清你怎么验证的** —— 命令、日志片段、真机型号。
   「我测过了」不算验证。
4. 提交信息用中文，说清**根因**而不只是「修了 X」。参考现有提交的风格。

### PR 检查清单

- [ ] 没有提交 `keystore.properties` / `*.jks` / `local.properties`
- [ ] 改了资产 ⇒ 同步改了版本戳
- [ ] 改了插件源码 ⇒ 用统一构建器生成并核验 ZIP 和逐文件 manifest
- [ ] 在真机上验证过（写清型号与 Android 版本）
- [ ] 没有为了「修」报错而升 targetSdk / 改包名

## 代码风格

- **Kotlin**：官方风格（`kotlin.code.style=official`），4 空格缩进。
- **C（jni）**：与现有文件保持一致，注释解释**为什么**而不是**做什么**。
- **注释**：本项目注释密度较高，且大量记录「为什么不能那样写」。**保留它们。**
  ⚠️ Kotlin 块注释**会嵌套** —— 注释里提到路径请用 `「」` 包裹，不要写 `/*`。
- **不要顺手重构** —— 改动最小化，只改必要部分。

## 安全

发现安全漏洞**不要开公开 Issue**，见 [SECURITY.md](SECURITY.md)。

---

# Contributing (English)

Thanks for your interest. This is a composite of **native Android + a native layer +
a Node engine**, where some mistakes compile fine and then fail on a real device.
Please read this before you start.

## Before you start

### The two hard constraints (violate them and it breaks — with misleading errors)

**① The package name must be exactly 15 characters.**
Termux binaries have `/data/data/com.termux/files` (27 bytes) baked into their ELF
`.dynstr`, and `patchElfInPlace()` can only overwrite in place with equal length.
`/data/data/<package>/` = 12 + N = 27 ⇒ **N = 15**. If the length differs, that
function **silently returns 0**; the symptom is a runtime `CANNOT LINK EXECUTABLE`
or `dpkg: Permission denied`.

**② targetSdk must remain 28.** From Android 10 on, targetSdk ≥ 29 apps cannot
`execve()` files in their own private directory — and node / bash / dpkg all live
under `filesDir`.

> **Do not raise targetSdk or change the package name to "fix" an error.** Those are
> exactly what these constraints forbid, and the error message will not tell you the
> truth.

See [docs/agents/constraints.md](../docs/agents/constraints.md).

### Silent-failure traps

| Trap | Consequence | How to tell |
|---|---|---|
| Changed a `.zip`/`.bin` asset without bumping its version stamp | Unpack is skipped, **new asset never takes effect**, completely silent | [asset-stamps.md](../docs/agents/asset-stamps.md) |
| Changed plugin source without regenerating artifacts | The APK ships old code | Shared builder and per-file manifest checks |
| Treating "build succeeded" as "change took effect" | Device still behaves the old way | `adb logcat -d -s DSH:I` and look for the unpack lines |

Plugins use the [component catalog and shared builder](../docs/agents/plugin-updates.md)
to generate ZIPs and per-file manifests consumed by Gradle. Do not hand-package plugin
directories; prepare other runtime assets using the [asset guide](../docs/agents/regen-assets.md).

## Development environment

```powershell
$env:JAVA_HOME = "<path to JDK 17>"   # must be set explicitly

.\gradlew.bat :app:assembleSystemDebug --console=plain
.\gradlew.bat :app:assembleGeckoDebug --console=plain
```

JDK 17 · AGP 8.8.2 · Kotlin 2.0.21 · Gradle 8.13 · compileSdk 35 / minSdk 26 / **targetSdk 28**

⚠️ **Build assets (~135 MB) are not in the repo** — see
[regen-assets.md](../docs/agents/regen-assets.md).

## Verify your change

Choose checks relevant to your change using the [testing runbook](../docs/runbooks/testing.md). Building, installation and publishing are separate operations; see the [release runbook](../docs/runbooks/release.md).

**"Build succeeded" is not "change took effect."** Confirm on a real device via logs
(TAG is always `DSH`):

```powershell
adb install -r app\build\outputs\apk\system\debug\app-system-debug.apk
adb logcat -c ; adb shell am start -n com.dsh.android/com.dsh.android.ui.MainActivity
adb logcat -d -s DSH:I
```

## Pull requests

1. **Open an issue first** — especially for anything touching the native layer,
   sandbox, plugins, or assets.
2. Fork → branch → change → self-test.
3. **Describe how you verified it** in the PR — commands, log excerpts, device model.
   "I tested it" does not count.
4. Commit messages are written in Chinese and explain the **root cause**, not just
   "fixed X."

### PR checklist

- [ ] Did not commit `keystore.properties` / `*.jks` / `local.properties`
- [ ] Changed an asset ⇒ bumped its version stamp
- [ ] Changed plugin source ⇒ used the shared builder and verified ZIPs and per-file manifests
- [ ] Verified on a real device (state model and Android version)
- [ ] Did not raise targetSdk / change the package name to "fix" an error

## Code style

- **Kotlin**: official style, 4-space indent.
- **C (jni)**: match the surrounding file; comments explain **why**, not **what**.
- **Comments**: this project carries a high comment density recording "why it must not
  be written that way." **Keep them.** ⚠️ Kotlin block comments **nest** — when
  mentioning a path in a comment, wrap it in `「」` and never write `/*`.
- **Don't refactor opportunistically** — keep changes minimal.

## Security

Do **not** open a public issue for a security vulnerability — see [SECURITY.md](SECURITY.md).
