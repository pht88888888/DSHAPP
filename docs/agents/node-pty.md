# ⑩ 侧栏终端打不开

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑩ 侧栏终端打不开 —— node-pty 缺 android 绑定 + 进程检查器只认三平台

**症状**：终端面板显示
`终端错误：subprocess-local: terminal inspection is unsupported on platform android`。

**这是两个独立的缺口叠在一起**，修一个会立刻撞上另一个，所以一起记：

**缺口一：`createProcessInspector()` 只认 linux/darwin/win32。**
它在 `dsh-subprocess-local/lib/runner-launch-<hash>.js` 里，是
`spawnTerminal()` 的第一步，落不进任何分支就 `throw`。

**做法**：让 android 复用 **Linux 实现**（`EngineManager.patchProcessInspector()`，
锚点 `INSPECTOR_GUARD` / `INSPECTOR_ANDROID_ANCHOR`）。依据：

- Android 就是 Linux 内核，`/proc/<pid>/stat` 格式一致（真机实测 `parseProcStat` 能解析）。
- `SYSCALLS` 表里**本来就有 arm64**，安卓 arm64 正好命中。
- app 域下 `/proc` **目录可读**，只是别的 uid 的条目被 SELinux 挡掉；而
  `LinuxProcessInspector` 对读不到的条目一律跳过（`readLinuxStat` 吞异常返回 `undefined`），
  **不会因此抛错**。真机实测 `readable=3`（只有自己 uid 的几个进程可读）。
  代价是只能看见自己 uid 的进程 —— 而终端 shell 及其子进程全是本 app 的 uid，
  正是需要观察的那部分。

⚠️ 实现体在**带 hash 的 chunk 名**里（`runner-launch-<hash>.js`），所以补丁按
**内容**找文件而不是按文件名找。⚠️ 幂等判断必须**先查「已打过补丁」再查「原文在不在」**：
打完之后原文就不存在了，顺序反了会把已完成的状态误报成「未找到」。

**缺口二：`node-pty` 没有 android 预编译二进制。**
它的 `prebuilds/` 只有 darwin/linux/win32，而 `lib/utils.js` 按
`prebuilds/${process.platform}-${process.arch}` 拼目录名 → 在 Android 上找
`prebuilds/android-arm64/pty.node` → `Failed to load native module: pty.node`。

**做法**：用 NDK 从 node-pty 自带的 `src/unix/pty.cc` 编一份，打成
`assets/node-pty-android-arm64.zip`，`EngineRuntimeDependencies.ensureNodePty()` 解到
`engine/node_modules/node-pty/prebuilds/android-arm64/`。**上游源码一行都不用改。**

```powershell
# 先取 node 头文件（版本必须与设备上的 node 一致）
Invoke-WebRequest https://nodejs.org/dist/v26.4.0/node-v26.4.0-headers.tar.gz `
  -OutFile hdr.tgz -Proxy "http://127.0.0.1:7897"
tar -xzf hdr.tgz
$cxx = "C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang++.cmd"
& $cxx -shared -fPIC -O2 -std=c++17 -static-libstdc++ `
  -I node-v26.4.0/include/node `
  -I <engine>\node_modules\node-addon-api `
  -o pty.node <engine>\node_modules\node-pty\src\unix\pty.cc
# 然后按「条目不带顶层目录」打成 zip：node-pty/prebuilds/android-arm64/pty.node
```

⚠️ **必须 `-static-libstdc++`**。默认动态链会引入 `libc++_shared.so`，
而 Termux 环境里**没有**这个库，加载即失败。静态链后 `readelf -d` 只剩
`libm/libdl/libc` 三个 bionic 系统库。

⚠️ **PTY 在 Android 上可用，别被「平台不支持」误导**：真机在 app 的 SELinux 域
（`untrusted_app`）里实测 `openpty` / `forkpty` / `open("/dev/ptmx")` 全部成功。
设备实测：绑定加载成功，`pty.spawn(bash)` 起得来，写入 `echo PTY_WORKS_$((6*7))`
能收到 `PTY_WORKS_42` 回显。

