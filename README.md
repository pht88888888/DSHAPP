# DSH for Android

**在 Android 手机上跑一个完整的 AI 编码 Agent。**

[English](README.en.md) · [文档](docs/agents/README.md) · [参与贡献](CONTRIBUTING.md)

---

## 这是什么

DSH for Android 把 [DSH](https://github.com/deepseek-ai)（DeepSeek Harness）桌面端引擎
原样搬进手机：内置 Termux 环境跑一个 Node，在 Node 上跑 DSH 引擎，再用 WebView
加载 DSH 自带的 Web GUI。

**它不自绘聊天界面。** UI 完全复用官方前端，只在窄屏下由内置插件做移动端适配 ——
所以你得到的不是「手机版阉割界面」，而是**与桌面端同一个前端**，只是能装进口袋。

```
APK
 ├─ Termux bootstrap        bash / dpkg / apt + 64 个 .so
 ├─ Node 运行时              Termux 官方 arm64 node 26.4.0
 ├─ DSH 引擎                 node_modules，完整解包到私有目录
 ├─ 原生插件（自编译）        require-builtin / flock / node-pty
 ├─ Web 内核（gecko 版）      GeckoView，不依赖系统 WebView
 └─ 内置插件                 移动端适配 / 悬浮球 / UI 调试 / 日志 / 记忆 …
```

## 特性

- **完整的 Agent 能力** —— `bash`、文件读写、`grep`/`glob`、终端 PTY 全部可用，
  不是只读聊天框。
- **双 Web 内核** —— `system` 版用设备自带 WebView（小），`gecko` 版内置 GeckoView
  （大一倍，但老设备、系统 WebView 被裁的设备也能用）。**两个包可同装一台机器。**
- **移动端 UI 适配** —— 官方前端 + 窄屏适配插件，不是另写一套界面。
- **悬浮球** —— 随时唤起，支持自定义外观、帧动画、视频图层。
- **UI 调试工具** —— 让模型自己看屏幕、点按钮，实现端到端自测。
- **救援模式** —— 插件写坏导致 App 打不开时，四级降级阶梯 + App 内自助摘除。
- **日志与诊断** —— 三层存储、崩溃现场快照、一键导出诊断包。

## 快速开始

### 安装

从 [Releases](../../releases) 下载 APK，侧载安装。

| 包 | applicationId | 适用 |
|---|---|---|
| `DSH<版本>.apk` | `com.dsh.android` | 绝大多数设备，体积小 |
| `DSH<版本>-gecko.apk` | `com.dsh.webview` | 老设备 / 系统 WebView 不可用时 |

两个包 **applicationId 不同，可以同时安装**，数据目录互相独立。

> ⚠️ **仅支持 arm64-v8a，且 targetSdk = 28。** 这是硬约束，不是疏忽 ——
> 见下方[两条硬约束](#两条硬约束)。

### 从源码构建

```powershell
$env:JAVA_HOME = "<你的 JDK 17 路径>"   # 必须显式设置

.\gradlew.bat :app:assembleSystemDebug --console=plain
# → app\build\outputs\apk\system\debug\app-system-debug.apk

.\gradlew.bat :app:assembleGeckoDebug --console=plain
# → app\build\outputs\apk\gecko\debug\app-gecko-debug.apk
```

**环境**：JDK 17 · AGP 8.8.2 · Kotlin 2.0.21 · Gradle 8.13 · compileSdk 35 · minSdk 26

### ⚠️ 构建资产需要自备

`app/src/main/assets/` 下约 **135 MB** 的构建资产**不在本仓库**（`.gitignore` 排除，
避免仓库臃肿）。**没有它们构建不出可用的 APK。**

| 资产 | 大小 | 来源 |
|---|---|---|
| `engine.bin` | 55 MB | DSH 引擎 `node_modules` 打包 |
| `node-runtime.bin` | 30 MB | Termux 官方 arm64 node |
| `bootstrap-aarch64.zip` | 23 MB | Termux bootstrap |
| `webview-compat.zip` | 9.3 MB | 按 chrome73 降级编译的前端 bundle |
| `pnpm-android-arm64.zip` | 5.6 MB | pnpm 11.28.3 纯 JS 版 |
| `adb-wireless-arm64.zip` | 5.3 MB | Termux 官方 adb |
| `sharp-wasm32.zip` | 3.5 MB | sharp 的 wasm32 变体 |
| `ripgrep-android-arm64.zip` | 2.0 MB | 官方 linux-arm64 静态 rg |
| 其余 26 个 | < 1 MB | 自编译 native 绑定 / 内置插件 zip |

**每个资产的来源、生成方式与校验方法**见
[docs/agents/regen-assets.md](docs/agents/regen-assets.md)。
其中 `keyinject.bin`（内置号池）是**可选的** —— 没有它 App 照常运行，只是不内置 API 账号。

## 两条硬约束

这两条是**平台事实**，不是可以「优化掉」的设计。改动前必读。

### ① 包名必须恰好 15 字符

`applicationId` = `com.dsh.android`（15 字符）。

Termux 官方二进制把 `/data/data/com.termux/files`（27 字节）烤进 ELF 的 `.dynstr`，
而 `TermuxBootstrap.patchElfInPlace()` 只能做**等长原地覆盖**。替换串
`/data/data/<包名>/` = 12 + N 字节 = 27 ⇒ **N = 15**。

长度不等时该函数**静默 `return 0`**，不抛异常；症状是运行时
`CANNOT LINK EXECUTABLE` 或 `dpkg: Permission denied`。

### ② targetSdk 必须 ≤ 28

**不能升到 29+。** Android 10（API 29）起，targetSdk ≥ 29 的应用不允许 `execve()`
自身私有目录里的文件（W^X / SELinux `untrusted_app` 域禁止 `app_data_file`
`execute_no_trans`）。

而 node / bash / dpkg 全都装在 `filesDir` 下的 `$PREFIX` 里 —— 一升级就全线
`Permission denied`。Termux 官方 App 同样停在 28。

**代价**：只能侧载，无法上架 Google Play。

## 架构

```
MainActivity (WebView / GeckoView)
      │  加载 http://127.0.0.1:<port>
      ▼
  DSH Web GUI  ←── 官方前端 + 移动端适配插件
      │
      ▼  HTTP
  DSH 引擎（Node）
      │
      ├─ tool: bash      → 经 boo-sandbox-run 沙箱
      ├─ tool: read/write → 经 liblinkcompat.so 转译 link
      ├─ tool: grep/glob → 静态 ripgrep
      └─ tool: ui/bubble → 原生桥（文件信箱）
      │
      ▼
  $PREFIX（Termux 环境，filesDir 下）
```

### 为什么需要这么多 native 层

Android 的 `process.platform` 是 `'android'` 而非 `'linux'`，而 DSH 官方的原生插件
与平台判断都是按 `linux`/`darwin`/`win32` 三选一写的。凡是「官方只认 linux、但代码
本身与 libc 无关」的地方，都要为 android 补一份。

更麻烦的是**三处 Android 主动不给的能力**，只能靠 `LD_PRELOAD` 在 libc 边界绕开：

| 层 | 解决什么 | 文档 |
|---|---|---|
| `liblinkcompat.so` | SELinux 不给 `link()` 权限（`EACCES`/`EINVAL`） | [link-compat.md](docs/agents/link-compat.md) |
| `boo-sandbox-run` + `libboosandbox.so` | Android 没有 bwrap/Landlock，但 `bash` 工具要求沙箱 | [sandbox.md](docs/agents/sandbox.md) |
| `libkeyinject.so` | 内置 API 号池，密钥在 TLS 层替换，不落盘不进 JS | [key-pool.md](docs/agents/key-pool.md) |

## 目录结构

```
app/src/main/java/com/dsh/android/     原生宿主（Kotlin）
app/src/main/jni/                      native 层（C，LD_PRELOAD）
app/src/main/assets/                   构建资产（⚠️ 不在仓库，见上）
app/src/system/ · app/src/gecko/       两个 Web 内核的 flavor 源集
mobile-ui-plugin/                      内置插件源码（TypeScript/JS）
remote-plugin/                         配对服务端 + 端到端测试
tools/                                 构建与校验脚本
docs/agents/                           开发文档（36 篇，按需检索）
```

## 文档

**别整份读 `docs/agents/`** —— 按「你要改什么」查索引：
[docs/agents/README.md](docs/agents/README.md)

| 你要改什么 | 先看 |
|---|---|
| 任何 native / 子进程相关 | [platform-android.md](docs/agents/platform-android.md) |
| `bash` 工具报 "no sandbox backend" | [sandbox.md](docs/agents/sandbox.md) |
| `write` 工具报 `EACCES/EINVAL: link` | [link-compat.md](docs/agents/link-compat.md) |
| 改任何 `.zip` / `.bin` 资产 | [asset-stamps.md](docs/agents/asset-stamps.md) |
| 构建发行包 / 签名 / 发布 | [build-and-ship.md](docs/agents/build-and-ship.md) |
| 插件改坏导致 App 打不开 | [rescue-mode.md](docs/agents/rescue-mode.md) |

## 已知边界

- **沙箱不隔离 PID 命名空间** —— `--unshare-pid` 需要 CAP_SYS_ADMIN，普通应用拿不到。
  文件写边界是完整的。
- **仅 arm64-v8a** —— 没有 arm32 / x86 构建。
- **只能侧载** —— targetSdk 28，上不了 Google Play（见硬约束②）。
- **APK 较大** —— system 版约 137 MB，gecko 版约 216 MB（引擎与内核随包分发）。
- **首次启动较慢** —— 要解包 Termux 环境与引擎，视设备约 10–60 秒。

完整清单见 [boundaries.md](docs/agents/boundaries.md)。

## 参与贡献

欢迎 Issue 与 PR。动手前请读 [CONTRIBUTING.md](CONTRIBUTING.md) ——
本项目有两条**改错不报错但真机必炸**的硬约束，以及「改了资产必须同步改版本戳」
这类静默失败陷阱。

## 致谢

本项目站在这些项目的肩膀上：

- [DSH / DeepSeek Harness](https://github.com/deepseek-ai) —— 引擎与 Web 前端
- [Termux](https://github.com/termux) —— bootstrap、node、adb 运行时
- [Mozilla GeckoView](https://wiki.mozilla.org/Mobile/GeckoView) —— 内置 Web 内核
- [ripgrep](https://github.com/BurntSushi/ripgrep) · [sharp](https://github.com/lovell/sharp)
  · [pnpm](https://github.com/pnpm/pnpm) · [Shizuku](https://github.com/RikkaApps/Shizuku)

## 许可证

[MIT](LICENSE)
