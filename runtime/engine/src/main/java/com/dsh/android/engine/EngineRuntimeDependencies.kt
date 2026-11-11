package com.dsh.android.engine

import android.content.Context
import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File

/**
 * 安装引擎运行依赖：Android 平台绑定、搜索/图片/终端依赖、pnpm 与沙箱二进制。
 *
 * 只负责资产落盘、权限与已有安装判据，不拥有进程、profile 或救援状态。
 * 调用时序由 [EngineManager] 决定；system 绑定的平台补丁通过回调在原位置执行。
 */
internal class EngineRuntimeDependencies(
    private val context: Context,
    private val engineDir: File,
    private val prefixDir: File,
    private val nodeBinary: File,
) {
    /**
     * 安装 android-arm64 的原生插件（幂等）。
     *
     * DSH 的 profile 解析依赖 `node-addon-require-builtin`：它通过 `dlsym` 拿到 Node
     * 私有的 `PrincipalRealm::builtin_module_require` 符号，再按 AArch64 机器码匹配出
     * Realm 里那个字段的偏移。该插件官方只发布 darwin / linux-glibc / win32 变体，
     * **没有 android**，而 Android 用的是 bionic，`__GLIBC__` 未定义，运行时会直接
     * 报 `No usable native binding found for node-addon-require-builtin-android-arm64`。
     *
     * 这里的做法是用 NDK 为 android-arm64 自行编译一份（复用 linux-glibc-arm64 的
     * AAPCS 匹配器 —— 纯指令匹配，与 libc 无关），按官方平台包的结构装进
     * `node_modules/node-addon-require-builtin-android-arm64`。
     *
     * 插件很小（约 90 KB），所以走独立的小资产，不必重打 52 MB 的 engine.bin。
     */
    fun ensureNativeAddon() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过原生插件安装")
            return
        }

        val target = File(nodeModules, ADDON_PACKAGE)
        val binary = File(target, "prebuilt/android-arm64-napi-v9.node")

        // 已装好就跳过。插件与 Node 大版本解耦（走 N-API），无需按 node 版本重装。
        if (binary.isFile && binary.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(ADDON_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装原生插件 $ADDON_PACKAGE (${binary.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就不阻断启动：让引擎自己报出更具体的加载失败原因
            Log.w(TAG, "安装原生插件失败: ${t.message}")
        }
    }

    /**
     * 安装 system 原生插件，并放行 android 平台（幂等）。
     *
     * 会话落盘要拿一把内核级的排他锁：`dsh-session-persistence-jsonl` 的
     * `SessionWriteLease.acquire()` 调 `@deepseek-ai/node-addon-system/flock` 的
     * `tryLockExclusive(fd)`，拿不到锁就抛 `SessionAlreadyOwnedError`，表现是每轮
     * 回复都以「本轮运行失败」告终。
     *
     * 该插件的 `lib/flock.js` 只白名单了 linux/darwin：
     *   `if (platform !== 'linux' && platform !== 'darwin') throw ...`
     * 而 Android 上 `process.platform === 'android'`，于是直接抛
     * `ERR_FLOCK_UNSUPPORTED_PLATFORM: flock is not supported on android-arm64`。
     * 官方也只发布 darwin/linux 变体，没有 android 平台包。
     *
     * 但 bionic 的 `sys/file.h` 里就有 `int flock(int, int)`，Android 是支持
     * flock(2) 的。所以这里做两件事：
     *   ① 用 NDK 编译一份 android-arm64 的 `bin/system.node`（源码就是官方
     *      `src/flock.c`，未做任何改动），按官方平台包结构装进
     *      `node_modules/@deepseek-ai/node-addon-system-android-arm64`；
     *   ② 把 `flock.js` 的平台白名单补上 `android`。
     *
     * ② 之所以改运行时文件而不是重打 engine.bin：引擎资产每次升级都会整体覆盖，
     * 放在这里可以跟着资产版本自动重打（与引擎兼容资产安装同样的理由）。
     *
     * 注意 flock.js 里 `filename` 只在 `platform === 'linux'` 时才会被改写为
     * `glibc/system.node` 或 `musl/system.node`；android 不满足该条件，保持
     * `system.node`，所以解析出的路径正是 `bin/system.node` —— 与本包结构一致，
     * 无需额外适配。
     */
    fun ensureSystemAddon(patchPlatform: () -> Unit) {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 system 插件安装")
            return
        }

        val target = File(nodeModules, SYSTEM_ADDON_PACKAGE)
        val binary = File(target, "bin/system.node")

        // 已装好就跳过。走 N-API，与 Node 大版本解耦。
        if (!(binary.isFile && binary.length() > 0)) {
            try {
                java.util.zip.ZipInputStream(context.assets.open(SYSTEM_ADDON_ASSET).buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val out = File(target, entry.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                Log.i(TAG, "已安装 system 插件 $SYSTEM_ADDON_PACKAGE (${binary.length()} 字节)")
            } catch (t: Throwable) {
                // 装不上就不阻断启动：让引擎自己报出更具体的加载失败原因
                Log.w(TAG, "安装 system 插件失败: ${t.message}")
            }
        }

        patchPlatform()
    }

    /**
     * 安装 android 版 ripgrep（幂等）。
     *
     * `grep` / `glob` 两个工具都由 `dsh-tool-fs-search` 调 `@vscode/ripgrep`
     * 解析出的二进制来跑：
     *
     * ```
     * const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;  // android-arm64
     * resolved = require.resolve(`${platformPkg}/bin/${binaryName}`);     // 抛错
     * ```
     *
     * 官方的 `optionalDependencies` 只列了 darwin/linux/win32 的 11 个变体，
     * **没有 android**。于是解析抛错，工具层把它包成
     * `Error: glob could not start its search command (ripgrep launch failed)`，
     * 两个工具全废 —— AI 只能改用 `read` 硬啃整个文件，浪费大量上下文。
     *
     * 这里装一个 `@vscode/ripgrep-android-arm64` 补上该平台包。二进制取自
     * 官方的 linux-arm64 变体（`@vscode/ripgrep-linux-arm64` 1.18.0）：
     * 它是**完全静态**的 aarch64 可执行文件（`readelf -d` 无 NEEDED），
     * 不依赖 glibc，因此能直接在 Android 上跑（设备实测 `rg --version` 正常）。
     * 这与 `TermuxBootstrap` 里 termux-exec 那套「静态二进制跨 libc」是同一道理。
     *
     * 无需改 `@vscode/ripgrep` 自身的代码：它按 `process.platform`/`process.arch`
     * 拼包名，装上 `android-arm64` 目录即可命中。也无需改任何版本戳 ——
     * 判定条件是「二进制存在且非空」。
     */
    fun ensureRipgrep() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 ripgrep 安装")
            return
        }

        val target = File(nodeModules, RIPGREP_PACKAGE)
        val binary = File(target, "bin/rg")

        if (binary.isFile && binary.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(RIPGREP_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            // 从 APK 资产解出来的文件默认没有执行位，必须显式补上
            binary.setExecutable(true, false)
            Log.i(TAG, "已安装 ripgrep $RIPGREP_PACKAGE (${binary.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：grep/glob 会各自报 SEARCH_FAILED，其余工具照常
            Log.w(TAG, "安装 ripgrep 失败: ${t.message}")
        }
    }

    /**
     * 安装 sharp 的 WebAssembly 变体（幂等）。
     *
     * **症状**：图片附件上传成功后点发送，提示 `prompt rejected (session/agent-busy)`。
     * 那个 `agent-busy` 是**兜底错误码**（`dsh-api-session-controller` 的
     * `admit()` 把非 RemoteError 一律包成它），真因藏在 `details.reason` 里：
     * `Could not load the "sharp" module using the android-arm64 runtime`。
     *
     * **根因**：`dsh-attachment-local` 用 sharp 做图片的元数据读取与缩放/重编码，
     * 而 sharp 的预编译二进制只覆盖 darwin/linux/linuxmusl/win32/freebsd，
     * **没有 android**。`sharp/dist/sharp.cjs` 按 `runtimePlatformArch()` 拼包名
     * （`${process.platform}${libc}-${process.arch}` = `android-arm64`）走 switch，
     * 落不进任何 case。
     *
     * **做法**：装 sharp 官方的 **wasm32 变体**。它正是 sharp 在
     * 「无预编译二进制」平台上的官方兜底 —— `sharp.cjs` 的 switch 走完之后，
     * 还有一条无条件的 `require("@img/sharp-wasm32/sharp.node")`（第 102-108 行），
     * 只要该包存在就能加载，**不需要改 sharp 自身一行代码**。
     * 它依赖 `@emnapi/runtime`（N-API 的 wasm 实现），故一并安装。
     *
     * 真机实测（`create` 1200×800 RGBA → metadata / resize / png+jpeg+webp 编码 /
     * 缩略图裁剪）全部通过，`sharp.versions` = 0.35.5 / libvips 8.18.7。
     *
     * 无需改版本戳 —— 与 ripgrep 同理，判定条件是「wasm 文件存在且非空」。
     */
    fun ensureSharp() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 sharp 安装")
            return
        }

        val wasm = File(nodeModules, SHARP_WASM_PACKAGE).let { dir ->
            File(dir, "lib/sharp-wasm32-${SHARP_VERSION}.node.wasm")
        }
        if (wasm.isFile && wasm.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(SHARP_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(nodeModules, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装 sharp wasm 变体 ($SHARP_WASM_PACKAGE, ${wasm.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：仅图片附件会发送失败，其余功能照常
            Log.w(TAG, "安装 sharp wasm 变体失败: ${t.message}")
        }
    }

    /**
     * 安装 node-pty 的 android-arm64 原生绑定（幂等）。
     *
     * **症状**：侧栏终端面板打不开，显示
     * `终端错误：subprocess-local: terminal inspection is unsupported on platform android`
     * （那是另一个补丁负责的，见 `EngineManager.patchProcessInspector()`；此处解决的是它背后
     * 真正缺的东西）。
     *
     * **根因**：`dsh-subprocess-local` 的终端后端走 `node-pty`，而 node-pty 的
     * `prebuilds/` 只有 `darwin-arm64` / `darwin-x64` / `linux-arm64` / `linux-x64` /
     * `win32-*`，**没有 android**。它的 `lib/utils.js` 按
     * `prebuilds/${process.platform}-${process.arch}` 拼目录名，所以在 Android 上
     * 会去找 `prebuilds/android-arm64/pty.node`，找不到就
     * `Failed to load native module: pty.node`。
     *
     * **做法**：用 NDK 从 node-pty 自带的 `src/unix/pty.cc` 直接编一份
     * android-arm64 的 `pty.node`，装到 `prebuilds/android-arm64/`。
     * 上游源码**无需任何改动** —— 它本来就按平台拼目录名。
     *
     * ⚠️ 必须 **`-static-libstdc++`**（静态链 libc++）。默认动态链会引入
     * `libc++_shared.so`，而 Termux 环境里**没有**这个库，加载即失败。
     * 静态链之后 `readelf -d` 只剩 `libm/libdl/libc` 三个 bionic 系统库。
     *
     * ⚠️ PTY 本身在 Android 上是**可用**的，别被"平台不支持"误导：真机在
     * app 的 SELinux 域（`untrusted_app`）里实测 `openpty` / `forkpty` /
     * `open("/dev/ptmx")` 全部成功。
     *
     * 真机实测：绑定加载成功，`pty.spawn(bash)` 起得来，写入
     * `echo PTY_WORKS_$((6*7))` 能收到 `PTY_WORKS_42` 回显。
     *
     * 无需改版本戳 —— 判定条件是「绑定存在且非空」。
     */
    fun ensureNodePty() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 node-pty 安装")
            return
        }

        val target = File(nodeModules, "node-pty/prebuilds/android-arm64")
        val binding = File(target, "pty.node")
        if (binding.isFile && binding.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(NODE_PTY_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 条目形如 node-pty/prebuilds/android-arm64/pty.node，直接相对 node_modules 落盘
                        val out = File(nodeModules, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装 node-pty android-arm64 绑定 (${binding.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只有终端面板用不了，其余功能照常
            Log.w(TAG, "安装 node-pty 绑定失败: ${t.message}")
        }
    }

    /**
     * 安装 pnpm 到 `$PREFIX/bin`（幂等）。
     *
     * `plugin_manager` 的 `install_bundle` 通过 spawn `pnpm add` 装插件，而
     * Termux 的 `$PREFIX/bin` 里只有 dpkg/apt 那一套，**没有 pnpm/npm/npx/corepack**。
     * 症状是 `spawn pnpm ENOENT`，插件装不上 —— 实测 AI 因此退回手改 profile：
     * 把包塞进 `profiles/web/node_modules/` 并直接编辑用户的 `cordis.patch.yml`。
     * 那是绕过包管理器，会留下 `.bak` 残file 且不受版本管理。
     *
     * **为什么是 pnpm 11 的纯 JS 版，而不是 `@pnpm/exe.android-arm64`**：
     * exe 版（12.x）是 Rust 重写的自包含二进制，跑得起来（`pnpm --version` 正常），
     * 但它在 Android 上**做不了任何写操作** —— Rust std 在编译期把 Android 的
     * `File::lock_shared()` 判为 unsupported，而 pnpm 的 store 操作锁走的正是它：
     *
     * ```
     * ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK
     *   ╰─▶ Failed to acquire the store operation lock at ".../all-stores.lock":
     *       lock_shared() not supported
     * ```
     *
     * 注意这**不是**内核不给锁：设备实测 `flock(fd, LOCK_SH)` 返回 0，`fcntl` 记录锁
     * 也正常。是 pnpm 那个二进制的动态符号表里**只有 `fcntl`、根本没有 `flock`**
     * （`readelf --dyn-syms` 可验），纯属编译期判定，配置绕不过去。
     *
     * pnpm 11 是最后一个**纯 JS** 主线（`dist/pnpm.mjs` 单文件 bundle，无 Rust），
     * 锁走 JS 实现，真机实测 `pnpm add`（含本地路径包、`nodeLinker: hoisted`
     * 的真实 profile 布局）全部成功。代价是体积从单文件 46 MB 变成 5.9 MB 的 zip
     * —— 反而更小了。
     *
     * 装法：整包解到 `$PREFIX/lib/pnpm`，再在 `$PREFIX/bin/pnpm` 放一个
     * `exec node <pkg>/bin/pnpm.mjs "$@"` 的启动脚本。**不能**直接把 `bin/pnpm.mjs`
     * 软链到 `bin/pnpm`：Android 的 SELinux 不给 app 私有目录建符号链接（与
     * `link` 同一类限制），而且 shebang 在 Termux 里也不可靠。
     *
     * 校验走 `PNPM_VERSION` 版本戳（与 `NODE_VERSION`、`MOBILE_UI_VERSION` 同一套
     * 模式）：把版本写进 marker 文件，不一致才重解。
     */
    fun ensurePnpm() {
        val bin = File(prefixDir, "bin/pnpm")
        val stamp = File(prefixDir, PNPM_STAMP)

        if (bin.isFile && bin.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == PNPM_VERSION
        ) {
            return
        }

        try {
            val libDir = File(prefixDir, PNPM_DIR)
            libDir.deleteRecursively()
            libDir.mkdirs()

            var entryCount = 0
            java.util.zip.ZipInputStream(context.assets.open(PNPM_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(libDir, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        entryCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val entryJs = File(libDir, "bin/pnpm.mjs")
            if (!entryJs.isFile) {
                throw IllegalStateException("资产里没有 bin/pnpm.mjs")
            }

            // $PREFIX/bin/pnpm：node 是绝对路径，免得依赖 PATH
            bin.parentFile?.mkdirs()
            bin.writeText(
                "#!/system/bin/sh\n" +
                    "exec " + nodeBinary.absolutePath +
                    " " + entryJs.absolutePath + " \"$@\"\n"
            )
            bin.setReadable(true, false)
            bin.setExecutable(true, false)

            stamp.writeText(PNPM_VERSION)
            Log.i(TAG, "已安装 pnpm $PNPM_VERSION ($entryCount 个文件, ${bin.length()} 字节启动脚本)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：插件管理器的 install_bundle 会报 ENOENT
            Log.w(TAG, "安装 pnpm 失败: ${t.message}")
        }
    }

    /**
     * 解出 android 沙箱的两个组件（幂等），返回 runner 路径。
     *
     * 背景：DSH 的沙箱层按平台挑后端
     * （`dsh-sandbox-local` 的 `PLATFORM_CHAINS`：linux→bwrap/landlock、
     * darwin→seatbelt、win32→windows-acl），Android 落不进任何一条 ——
     * 设备内核 4.14 没有 Landlock（需 5.13+），没有 bwrap，`unshare -U` 因缺
     * CAP_SYS_ADMIN 而 EPERM。`confine()` 是 **fail-closed** 的，于是
     * `workspace-write` 下 bash 直接不可用：
     *
     * ```
     * sandbox mode "workspace-write" is requested but no sandbox backend is
     * usable on this host; refusing to run the command unconfined.
     * ```
     *
     * 但该 provider 留了操作员口子 `Config.runnerCommand`：非空时**不做平台
     * 探测**，直接把 runner 当成 bwrap 等价物并按 bwrap 方言拼参数
     * （`[...runnerCommand, ...bwrapProfileArgs(policy), "--", ...argv]`）。
     * 所以不必改引擎源码，只要提供一个听得懂那套参数的 runner。
     *
     * 两个组件（源码在 `app/src/main/jni/`）：
     *   · `boo-sandbox-run`   —— 解析 bwrap 方言，把 `--bind` 翻译成
     *     `BOO_SANDBOX_ALLOW`，再 exec 真实命令；
     *   · `libboosandbox.so`  —— LD_PRELOAD 写拦截器，在 libc 边界挡住
     *     allowlist 之外的写（解释器内部的写同样覆盖）。
     *
     * ⚠️ 与真 bwrap 的差异：`--unshare-pid` 无法实现（同样缺 CAP_SYS_ADMIN），
     * 所以 PID 命名空间没有隔离。**文件写边界是完整的**，那是本方案承诺的部分。
     * 详见 `boo-sandbox-run.c` 头部。
     */
    fun ensureSandbox(): File? {
        return try {
            val runner = File(context.filesDir, SANDBOX_RUNNER_FILE)
            context.assets.open(SANDBOX_RUNNER_ASSET).use { input ->
                runner.outputStream().use { output -> input.copyTo(output) }
            }
            runner.setReadable(true, false)
            runner.setExecutable(true, false)

            val lib = File(context.filesDir, SANDBOX_LIB_FILE)
            context.assets.open(SANDBOX_LIB_ASSET).use { input ->
                lib.outputStream().use { output -> input.copyTo(output) }
            }
            lib.setReadable(true, false)

            Log.i(TAG, "已解出沙箱 runner ${runner.name} (${runner.length()} 字节) + ${lib.name} (${lib.length()} 字节)")
            runner
        } catch (t: Throwable) {
            Log.w(TAG, "解出沙箱组件失败: ${t.message}")
            null
        }
    }

}
