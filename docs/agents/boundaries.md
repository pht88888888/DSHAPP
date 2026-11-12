# 已知边界

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 已知边界

- **终端/PTY 执行**：已接入（见 ⑩）。侧栏终端可用，但**沙箱不隔离 PID 命名空间**
  （见上），且 app 域下 `/proc` 只能看见自己 uid 的进程。
- **中断处理**：用户自行解决，别自作主张加。
- **`libboosandbox.so` 已构建并接入**（见「④ Android 没有内核级沙箱后端」）。
  它同时是 shell 写入沙箱的强制层。
- **沙箱不隔离 PID 命名空间**：`--unshare-pid` 需 CAP_SYS_ADMIN，普通应用拿不到。
  文件写边界是完整的。
- **`engine-overlay/disable-native.yml` 与空的 `x/` 目录均已删除**：前者针对 dsh
  0.2.2-rc.1 的 `subprocess` / `attachment-local` 禁用，早已不被任何构建或运行路径读取
  （node-pty 与 sharp wasm 变体已随资产提供）。
- **`engine.bin` 在 APK 内每轮构建都要重新压缩**（52 MB）。若嫌构建慢，可在首次启动时
  改用 `adb push`，能把构建压到 10 秒内。
- **APK 约 117 MB**：`engine.bin` 52 MB + `node-runtime.bin` 30 MB + bootstrap 23 MB
  + pnpm zip 5.9 MB + ripgrep 2 MB。若嫌大，pnpm 可改为首次启动联网下载，
  代价是离线不可用（现在它是离线的）。

