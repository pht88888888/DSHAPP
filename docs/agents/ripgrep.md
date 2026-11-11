# ⑤ @vscode/ripgrep 没有 android 包

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑤ `@vscode/ripgrep` 没有 android 平台包 —— 补一个

`grep`/`glob` 都由 `dsh-tool-fs-search` 经 `@vscode/ripgrep` 解析二进制来跑：

```js
const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;  // android-arm64
require.resolve(`${platformPkg}/bin/${binaryName}`);                // ← 抛错
```

官方的 `optionalDependencies` 只列了 darwin/linux/win32 的 11 个变体，**没有 android**，
于是工具层报 `Error: glob could not start its search command (ripgrep launch failed)` /
`SearchError SEARCH_FAILED`。

**做法**：取官方 `@vscode/ripgrep-linux-arm64` 1.18.0 的 `bin/rg`，打成
`assets/ripgrep-android-arm64.zip`，`EngineRuntimeDependencies.ensureRipgrep()` 解到
`engine/node_modules/@vscode/ripgrep-android-arm64/`（条目是 `bin/rg` + `package.json`，
**没有顶层目录**）。它能在 Android 上跑是因为**完全静态** —— `readelf -d` 无任何
`NEEDED`，不依赖 glibc。设备实测 `ripgrep 15.0.0`，`+pcre2`，`simd(compile):+NEON`。

⚠️ 从 APK 资产解出来的文件**没有执行位**，必须 `setExecutable(true, false)`，
否则报 `EACCES` 而不是「找不到」。

