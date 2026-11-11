package com.dsh.android.engine.constants

/** 引擎与运行依赖的安装元数据；版本值沿用已有生效判据。 */
        // 注意：不能用 .tar.gz 作后缀 —— AGP 会把它当作 gzip 资源自动解压并改名为 .tar，
        // 导致运行时按原名找不到。用中性后缀避免 AGP 的格式推断。
const val ASSET_NAME = "engine.bin"
        /** 自编译的 android-arm64 原生插件（官方无此变体）。 */
const val ADDON_ASSET = "node-addon-android-arm64.zip"
const val ADDON_PACKAGE = "node-addon-require-builtin-android-arm64"

        /** system 插件（会话写入锁 flock）：同样是自编译的 android-arm64 变体。 */
const val SYSTEM_ADDON_ASSET = "node-addon-system-android-arm64.zip"
const val SYSTEM_ADDON_PACKAGE = "@deepseek-ai/node-addon-system-android-arm64"

        /** ripgrep：官方 `@vscode/ripgrep` 没有 android 平台包，这里补一个。 */
const val RIPGREP_ASSET = "ripgrep-android-arm64.zip"
const val RIPGREP_PACKAGE = "@vscode/ripgrep-android-arm64"

        /** sharp 的 wasm32 变体（官方兜底，见 `ensureSharp()`）。 */
const val SHARP_ASSET = "sharp-wasm32.zip"
const val SHARP_WASM_PACKAGE = "@img/sharp-wasm32"
const val SHARP_VERSION = "0.35.5"

        /** node-pty：官方 prebuilds 没有 android，自编译一份（见 `ensureNodePty()`）。 */
const val NODE_PTY_ASSET = "node-pty-android-arm64.zip"

        /** 沙箱：runner（bwrap 方言翻译）+ LD_PRELOAD 写拦截器。 */
const val SANDBOX_RUNNER_ASSET = "boo-sandbox-run.bin"
const val SANDBOX_RUNNER_FILE = "boo-sandbox-run"
const val SANDBOX_LIB_ASSET = "boosandbox.bin"
const val SANDBOX_LIB_FILE = "libboosandbox.so"
        /** pnpm：Termux 的 $PREFIX/bin 里没有，插件管理器靠它装 bundle。 */
const val PNPM_ASSET = "pnpm-android-arm64.zip"
const val PNPM_VERSION = "11.28.3"
const val PNPM_STAMP = ".pnpm_version"

        /** pnpm 整包解到 `$PREFIX/lib/pnpm`，`bin/pnpm` 只是个 exec 它的脚本。 */
const val PNPM_DIR = "lib/pnpm"

        // ── uid 2000 特权通道 ────────────────────────────────────────
        //
        // 把 shell 身份执行注册成一个**独立的 `shell` 工具**（不是改 bash 的行为）。
        // 机制、实测数据与取舍见 assets/dsh-android-shell.mjs 与
        // mobile-ui-plugin/dsh-android-shell/lib/index.js 的头部注释。

        /** uid 2000 执行通道脚本（直连本机 adbd，走 ADB 协议）。 */
const val PRIVILEGED_RUNNER_ASSET = "dsh-android-shell.mjs"
const val PRIVILEGED_RUNNER_FILE = "dsh-android-shell.mjs"

        // ── root（uid 0）特权通道 ────────────────────────────────────
        //
        // 与 uid 2000 通道**同一套做法、同一套取舍**（见上面那段），只是身份档位不同：
        // 注册一个独立的 `root` 工具，仅在**宿主探测到确实能提权**时才挂载。
        // 检测方式与「存在 su ≠ 有 root」的三种假阳性见 [RootAccess]。

        /** root 执行通道脚本（经 `su -c` 以 uid 0 执行）。 */
const val ROOT_RUNNER_ASSET = "dsh-android-root.mjs"
const val ROOT_RUNNER_FILE = "dsh-android-root.mjs"

        /**
         * 无线调试（Android 11+）用的 adb 工具链。
         *
         * 取自 Termux 官方 `android-tools` 包的 `adb`（37.0.0），外加它 NEEDED 的
         * 全部 `.so`（`libprotobuf` / `libfmt` / `libabsl_*` / `libbrotli*` /
         * `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`）。
         * 理由与踩过的坑见 [ensureAdbWireless]。
         */
const val ADB_WIRELESS_ASSET = "adb-wireless-arm64.zip"
const val ADB_WIRELESS_DIR = "adbwire"

        /**
         * 无线调试 adb 工具链的**完整性哨兵**（落在 [ADB_WIRELESS_DIR] 内）。
         *
         * 只在 107 个条目全部写完之后才落盘，因此「哨兵存在」= 解包完整走完过。
         * 幂等判据必须带上它，否则一次中断留下的半成品会被永久认定为「已完成」——
         * 详见 `ensureAdbWireless()`。
         */
const val ADB_WIRELESS_STAMP = ".extracted"

