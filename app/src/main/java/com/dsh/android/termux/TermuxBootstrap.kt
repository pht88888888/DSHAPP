package com.dsh.android.termux

import android.content.Context
import android.os.Build
import android.system.Os
import com.dsh.android.log.DshLog as Log
import com.dsh.android.BuildConfig
import com.dsh.android.termux.util.AtomicTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Manages the Termux bootstrap — extracts from bundled assets into prefix dir.
 *
 * The bootstrap zip is bundled at app/src/main/assets/bootstrap-{arch}.zip
 * during build. On first launch we extract it to {dataDir}/termux/usr/,
 * set up symlinks and apt sources, so the LLM's shell has full pkg/apt.
 *
 * It also owns **mirror selection** (see [MirrorProbe]): every launch it re-asserts
 * the domestic fast mirrors into pip/npm/go/cargo/apt configs so the model never
 * has to remember `-i` / `--registry` flags, and it can re-measure candidates on
 * demand via [probeMirrors].
 */
class TermuxBootstrap(private val context: Context) {

    private val dataDir = context.filesDir.absolutePath
    private val rootDir get() = File(dataDir, "termux")
    val prefixDir get() = File(rootDir, "usr")
    val homeDir get() = File(rootDir, "home")
    private val marker get() = File(prefixDir, ".bootstrap_done")

    /**
     * `OLD_TERMUX_ROOT` 在 ELF 二进制里被替换成的字符串。
     *
     * ⚠️ 必须**恰好 27 字节**才能与 [OLD_TERMUX_ROOT] 等长 —— 末尾那个斜杠就是
     *   用来凑长度的，而「等长」正是这个补丁安全的全部理由（纯覆盖，
     *   偏移不移动）。改这里要重新数字节，别用眼睛估。更长会破坏 ELF；
     *   更短会把一个 NUL 截掉、并让任何共享该后缀的字符串被削掉尾巴。
     *
     * ## ⚠️ 必须取「运行时真实数据目录」，不能用 `BuildConfig.APPLICATION_ID`
     *
     * 原来这里派生自 `BuildConfig.APPLICATION_ID`（编译期常量，也是 manifest 里
     * 声明的那个包名）。两者**平时**一致，但在被改过包名的安装上会分叉，
     * 而分叉的后果极难查 —— 因为等长断言**照样通过**：
     * `com.dsh.android` 与 `com.dsh.androie` 都是 15 字符，[installDebFixHook]
     * 里的 `check(...)` 只看长度，看不出内容已经不是这台设备上的真实目录。
     *
     * 真机铁证（服务器诊断包 `20261014-144021-fd859918`，
     * Xiaomi 2312DRA50C / Android 16 / `0.2.9-tooliso (18) system`）：
     * 设备上真实数据目录是 `/data/user/0/com.dsh.androie/`
     * （该包 `app.log` 里 `com.dsh.android` 出现 **0 次**、`com.dsh.androie`
     * **21 次**），而 ELF 被改写成 `/data/data/com.dsh.android/` ——
     * 一个**不存在**的路径。症状正是
     * `CANNOT LINK EXECUTABLE … library "libz.so.1" not found`，
     * 引擎永远起不来（现场文件 `20261014-143950-engine-start-failed.txt`）。
     * 而同一份包的 `environment.json` 里 `applicationId` 却写着
     * `com.dsh.android`（那也来自 `BuildConfig`），于是诊断包**自相矛盾**，
     * 把真因盖住了 —— 本项目缺陷族「结论与它描述的对象不是同一个」的又一例。
     *
     * `context.filesDir` 是系统按**这台设备上真实生效的包名**给的，
     * 所以从它反推最可靠：`filesDir` = `/data/user/0/<pkg>/files`。
     *
     * ⚠️ **不能用 `context.packageName` 代替**：它是**编译期 manifest 的声明值**，
     *   与 `BuildConfig.APPLICATION_ID` 同源，在改过包名的安装上一样会分叉。
     */
    private val elfRootReplacement: String = run {
        val fallback = "/data/data/${BuildConfig.APPLICATION_ID}/"
        val real = runCatching { context.filesDir?.parentFile?.name }.getOrNull()
        if (real.isNullOrEmpty()) {
            fallback
        } else {
            val candidate = "/data/data/$real/"
            // 长度不对就退回：宁可回到「改造前那个已知会分叉的行为」，
            // 也不要用一个会破坏 ELF 的替换串。
            if (candidate.length == OLD_TERMUX_ROOT.length) candidate else fallback
        }
    }

    val isReady: Boolean get() = marker.exists()

    /**
     * Marker for [rewriteTermuxPaths] and the prefix repair that goes with it.
     *
     * ⚠️ The version suffix is load-bearing — **bump it whenever the repair must
     * reach machines that already installed**. `install()` returns early when
     * `.bootstrap_done` exists, so an upgraded app only ever runs
     * [ensurePathsRewritten], and that is gated on this marker being absent.
     *
     * - `_v1` → `_v2`: v1 only rewrote **text** files and skipped ELF binaries,
     *   so installations repaired by v1 would never pick up the ELF fix.
     * - `_v2` → `_v3`: v2 wrote a `dpkg.cfg` with `instdir` alone and no apt
     *   hook, so those installations keep failing on every package that ships a
     *   maintainer script.
     * - `_v3` → `_v4`: the v3 hook rewrote the prefix inside maintainer scripts
     *   but left dpkg's own `DPKG_ROOT` export in place. Tools that honour it
     *   (`update-alternatives`) then prepend `<aptroot>` to the already-real
     *   path and look for `aptroot/data/user/0/…/bin/nano`, which does not
     *   exist — every package using alternatives (nano, vim, …) aborted in
     *   `--configure` and stuck at `iF`.
     * - `_v4` → `_v5`: v4 only repaired the `.deb` **control** member, which
     *   fixes the scripts dpkg runs but not the files dpkg *installs*. A fresh
     *   payload keeps the official prefix in its own shebangs and in plain-text
     *   data files, so `python-pip` landed at `ii` yet `pip` died with
     *   `bad interpreter` and tried to write `/data/data/com.termux/files/usr/
     *   etc/pip.conf` — installed, but broken. v5 adds the post-unpack sweep.
     * - `_v5` → `_v6`: v5's `.deb` repair shelled out to `ar`, which the baseline
     *   does not have — `$PREFIX/bin/ar` only exists once the `llvm` package
     *   lands, and `llvm` is itself a dependency of `python`, i.e. installed by
     *   the same transaction that needs the hook. On a cold prefix the extract
     *   failed, the loop skipped the package silently, and every package with a
     *   maintainer script died at `dpkg returned an error code (1)`. v6 uses
     *   `dpkg-deb`, which ships inside `dpkg` and is therefore always present.
     * - `_v6` → `_v7`: v6's post-unpack sweep skipped binaries on purpose
     *   (`grep -I`), which leaves the official prefix inside every freshly
     *   installed **ELF**. `git` bakes it into 12 strings — the
     *   `share/git-core/templates` path, `etc/gitconfig`, the default `sh`/
     *   `bash`/`login` for hooks, and `RUNPATH` — so every `git init` printed
     *   `warning: templates not found in /data/data/com.termux/...` and the
     *   linker was pointed at com.termux's private directory. v7 adds an
     *   equal-length ELF rewrite (27 → 27 bytes, pure overwrite) to the same
     *   hook, so it applies to packages installed from now on.
     */
    private val rewriteMarker get() = File(prefixDir, ".paths_rewritten_v7")

    /**
     * 安装标记，`.node_runtime_<版本>`。
     *
     * ⚠️ 与 [rewriteMarker] **分开**是必需的：那个标记只覆盖 bootstrap 自己解出来的文件，
     * 而 Node 运行时是随后单独铺进去的一批文件。若共用标记，升级安装时新增或更新的
     * 运行时对**老装机永远不会生效** —— 正是 [rewriteMarker] 的 KDoc 记录过的那类坑。
     */
    private val nodeMarker get() = File(prefixDir, ".node_runtime_$NODE_VERSION")

    /**
     * How long a mirror probe result stays fresh.
     *
     * ⚠️ **不要每次启动都重测**：一次探测要打 19 个候选（并行，约 8 秒），而且镜像速度
     * 不会分钟级变化。7 天是个折中 —— 既不会长期停留在某个已劣化的源上，也不会把
     * 启动路径变成测速工具。用户/模型随时可以 `boo-mirror` 手动重测。
     */
    private val probeTtlMs = 7L * 24 * 60 * 60 * 1000

    /**
     * 跨进程排他锁 —— 锁在 `dataDir/termux/.prefix.lock` 上。
     *
     * ## 🔴 为什么 [prefixLock] 不够：这是真机炸过的坑
     *
     * [prefixLock] 是 `ReentrantLock`，**只在进程内互斥**。而 gecko flavor 依赖的
     * `geckoview-arm64-v8a` AAR 声明了 **39 个 `android:process` 子进程**
     * （`:tab0`…`:tab39`、`:gpu`、`:media`、`:socket`、`:crashhelper` …），
     * 每个子进程都会实例化 `DshApplication`，于是**每个子进程都完整跑一遍** [install]。
     *
     * 真机证据（gecko 版诊断包 `20261014-010151-de8734c5`，2026-10-14）：
     *
     * ```
     * #6 main app.onCreate                      ← 进程内序号反复归零 = 多个进程
     * #6 main app.onCreate
     * #7 worker-1 install start
     * #8 worker-1 Extracting Termux bootstrap from assets
     * #7 worker-3 install start                 ← 时间与上面重叠，进程内锁不可能允许
     * java.io.FileNotFoundException: …/usr/etc/dpkg/dpkg.cfg: open failed: ENOENT
     *   at TermuxBootstrap.writeAptConfig(TermuxBootstrap.kt:753)
     *   at TermuxBootstrap.installLocked(TermuxBootstrap.kt:187)
     * ```
     *
     * 机制：多个进程各自执行 `prefixDir.deleteRecursively()`，把别人刚解出来的
     * `etc/dpkg/` 删掉，于是 `dpkg.cfg` 写不进去。判别性极强 ——
     * **system flavor 的 50 个包一个都没有**这个现象（没有子进程），
     * **gecko 的 3 个包全部都有**。
     *
     * ## 为什么是 `FileChannel.lock()`
     *
     * 它在 Android 上落到 `fcntl(F_SETLKW)`，**随进程退出自动释放** —— 持锁进程被
     * 系统杀掉不会留下死锁。`ReentrantLock` 没有这个性质，而引导路径恰好会被杀
     * （见 `CrashRecorder` 的 `process-freeze`）。
     *
     * ⚠️ **必须先持有 [prefixLock] 再进这里**：同一进程重复对同一文件加锁会抛
     * `OverlappingFileLockException`。进程内锁保证同进程串行、文件锁保证跨进程串行，
     * 两层缺一不可。
     */
    private inline fun <T> withPrefixFileLock(block: () -> T): T {
        val lockFile = File(rootDir, ".prefix.lock")
        lockFile.parentFile?.mkdirs()
        return RandomAccessFile(lockFile, "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /** Install bootstrap from assets → prefix dir. Safe to call multiple times. */
    suspend fun install() {
        if (isReady) return
        Log.i("TERMUX", "install start arch=$termuxArch")
        withContext(Dispatchers.IO) {
          // 与 installNodeRuntime / rewriteTermuxPaths 共用同一把锁：三者都会
          // 往 prefix 里写文件，而 install() 开头就有 deleteRecursively()。
          // ⚠️ 外层进程内锁 + 内层跨进程文件锁，两层都要（见 withPrefixFileLock）。
          prefixLock.withLock {
            withPrefixFileLock {
              // 抢到文件锁时别的进程可能已经装完了 —— 必须重新判一次，否则
              // 这里会再 deleteRecursively() 一遍刚装好的 prefix。
              installLocked()
            }
          }
        }
    }

    /** [install] 的实现体，必须已持有 [prefixLock]。 */
    private fun installLocked() {
        if (isReady) return
        Log.i(TAG, "Extracting Termux bootstrap from assets")
        rootDir.mkdirs()
        homeDir.mkdirs()
        prefixDir.deleteRecursively()
        prefixDir.mkdirs()

        val zipName = "bootstrap-${termuxArch}.zip"
        context.assets.open(zipName).use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    /*
                     * ⚠️ **必须校验解包路径不越界（zip-slip）。**
                     *
                     * `File(prefixDir, name)` 对含 `..` 或绝对路径的 name
                     * **不做任何限制**：
                     *   · name = "../../../data/data/com.dsh.android/shared_prefs/x"
                     *     会写到 prefix 之外；
                     *   · name = "/data/local/tmp/x"（绝对路径）在 Java 侧虽然
                     *     会被当成相对路径处理，但 `..` 一定会生效。
                     *
                     * 本项目的 bootstrap zip 是自己打包的，正常不会有这种条目；
                     * 但这条路径解的是**应用资产里的压缩包**，一旦打包环节出问题、
                     * 或将来换成可下载的 bootstrap，这就是一条写到沙箱任意位置的通道。
                     * 校验成本只有一次 canonicalPath 比较，值得加。
                     *
                     * 与 `EngineManager.ensureMobileUi()` 里已有的同名校验保持一致的写法。
                     */
                    val outFile = File(prefixDir, name)
                    val canonicalTarget = outFile.canonicalPath
                    val canonicalRoot = prefixDir.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalRoot + File.separator) &&
                        canonicalTarget != canonicalRoot
                    ) {
                        Log.w(TAG, "跳过越界的解包条目: $name")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (name == "SYMLINKS.txt") {
                        outFile.outputStream().use { zis.copyTo(it) }
                    } else {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zis.copyTo(it) }
                            if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                                name.startsWith("lib/apt/methods/")) {
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (symlinksFile.exists()) {
            processSymlinks(symlinksFile, prefixDir)
            symlinksFile.delete()
        }

        // Point every baked-in /data/data/com.termux path at this install.
        // Order matters: the alias symlinks come first because the paths
        // rewritten inside ELF binaries resolve *through* them.
        // See rewriteTermuxPaths for why binaries need a different rule.
        createPrefixAliases()
        ensureDpkgDirs()
        ensureAptInstdirRoot()
        rewriteTermuxPathsLocked(prefixDir)

        // Install GPG keyrings so apt can verify repository signatures.
        // The bootstrap zip ships keys in share/termux-keyring/*.gpg
        // but etc/apt/trusted.gpg.d/ is empty. Normally the second-stage
        // bootstrap (dpkg --configure termux-keyring) does this, but
        // we skip second-stage since its scripts also have broken paths.
        installGpgKeys(prefixDir)

        // Ensure TMPDIR exists — the shell environment references it.
        File(prefixDir, "tmp").mkdirs()

        // Pre-create apt directories. The bootstrap zip doesn't ship
        // var/cache/apt/ or var/lib/apt/ — apt creates them lazily, but
        // pre-creating avoids any edge case with directory creation on
        // read-only / restrictive Android filesystem paths.
        File(prefixDir, "var/cache/apt/archives/partial").mkdirs()
        File(prefixDir, "var/lib/apt/lists/partial").mkdirs()

        writeSourcesList()
        writeAptConfig()
        // 首次安装就把国内快源写进 pip/npm/go/cargo 配置。用候选表默认值（实测各生态
        // 最快的一个），**不做同步测速** —— 启动路径绝不为了挑源多等 8 秒。
        applyMirrorConfigs(loadMirrors())
        marker.writeText("ok")
        rewriteMarker.writeText("ok")
        Log.i(TAG, "Termux bootstrap installed ($prefixDir)")
    }

    /**
     * 每次启动都跑：确保各包管理器指向**候选表里**的国内快源（幂等，内容相同则不写盘）。
     *
     * ⚠️ **这里是「写死的那张表」，不是「实测最快的那张表」。** 真机实测过的
     *   「打 19 个候选站挑最快」那条链路是 [probeMirrors]，而它**从未被调用**
     *   （见其 KDoc 的核实记录）。所以本函数实际写入的是
     *   [MirrorProbe.defaults]（各生态实测最快的那一个）或 `mirrors.conf` 里
     *   已被 [MirrorProbe.loadOrDefaults] 自愈过的表 —— 后者同样源自默认表。
     *   说清楚这一点，是为了不让排查者以为「某生态慢」是测速选错了站。
     *
     * ⚠️ **不能挂在 [ensurePathsRewritten] 里**：那条路径被 `.paths_rewritten_v6` 门住，
     * 已装好的机器永远不会再进 —— 也就是说新增的镜像配置对**老装机完全不会生效**。
     * 用户升级安装后第一次启动就应该看到快源，所以这条必须是独立的、每启动必跑的路径。
     *
     * 成本：读一个状态文件 + 最多 7 个小文本文件的内容比对，量级是微秒。
     */
    fun ensureMirrorsConfigured() {
        if (!prefixDir.exists()) return
        try {
            applyMirrorConfigs(loadMirrors())
        } catch (e: Exception) {
            Log.w("TERMUX", "ensure mirrors: ${e.message}")
        }
    }

    /**
     * 安装内置的 Node 运行时（node 可执行文件 + 它 NEEDED 的库）。
     *
     * ## 为什么不用 `pkg install nodejs`
     *
     * 那需要联网，而首次启动必须能在离线状态下把 DSH 跑起来。所以运行时随 APK 一起
     * 分发（[NODE_RUNTIME_ASSET]，33 MB 压缩后），解到 `$PREFIX/{bin,lib}`。
     *
     * ## 为什么是「叠加」而不是「覆盖整个 lib」
     *
     * 只铺入 bootstrap **没有**的那几个库（ICU / c-ares / libffi / libsqlite3）。
     * `libc++_shared.so`、`libcrypto.so.3`、`libssl.so.3`、`libz.so.1.3.2` 在 bootstrap
     * 里已经有一份，而 bootstrap 的 apt/dpkg/curl 都链接着它们 —— 用另一批 deb 的
     * 同名库盖掉，等于把整个包管理器的 ABI 换掉，风险远大于收益。node 与它们同源
     * （都是 Termux 官方仓库同一时刻的构建），链接兼容。
     *
     * ## 软链必须运行期重建
     *
     * zip 格式不保存符号链接，而 Termux 的库全靠 `libicuuc.so.78 -> libicuuc.so.78.3`
     * 这类链被 `dlopen` 找到。asset 里带一份 `LINKS.txt`，这里逐行 [Os.symlink] 出来。
     *
     * @return true 表示运行时已就位（含本就已安装的情况）。
     */
    suspend fun installNodeRuntime(): Boolean = withContext(Dispatchers.IO) {
        prefixLock.withLock { withPrefixFileLock { installNodeRuntimeLocked() } }
    }

    /** [installNodeRuntime] 的实现体，必须已持有 [prefixLock]。 */
    private fun installNodeRuntimeLocked(): Boolean {
        if (!prefixDir.exists()) {
            Log.w("TERMUX", "installNodeRuntime: prefix 不存在，跳过")
            return false
        }
        if (nodeMarker.exists() && nodeBinary.isFile) {
            // 标记只证明「曾经装过」，不证明「现在还能用」。node 是 47 MB 的
            // 可执行文件，任何把它写坏的原因（写入中途被杀、两个入口并发写、
            // 磁盘错误）都会留下一个「大小对、ELF 头也在、但内容已坏」的文件，
            // 而这里原本只看文件在不在 ⇒ 直接放行 ⇒ 引擎再也起不来。
            //
            // 真机实测那种坏法：node 起动时在 V8 反序列化内嵌启动快照处
            // `Check failed: magic_number_ == SerializedData::kMagicNumber`，
            // 报错发生在**任何一行 JS 之前**，所以插件/profile 层面的所有自救
            // 路径（含「进入安全模式」）都够不着它 —— 用户只能卸载重装，而那会
            // 清掉整个 $DSH_HOME。见 [nodeIsUsable]。
            if (nodeIsUsable()) return true
            Log.w("TERMUX", "内置 Node 运行时已损坏，重新解包: ${nodeBinary.absolutePath}")
            nodeMarker.delete()
            nodeBinary.delete()
        }

        return try {
            Log.i("TERMUX", "安装内置 Node 运行时 $NODE_VERSION")
            val links = mutableListOf<Pair<String, String>>()

            context.assets.open(NODE_RUNTIME_ASSET).use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // ⚠️ 同 installLocked：解包路径必须校验不越界（zip-slip）。
                        //   见那边更长的说明。这里多一层：连目录条目也一并跳过，
                        //   否则一个 "../" 目录能在 prefix 外建出空目录树。
                        val outCheck = File(prefixDir, name)
                        val ct = outCheck.canonicalPath
                        val cr = prefixDir.canonicalPath
                        if (!ct.startsWith(cr + File.separator) && ct != cr) {
                            Log.w(TAG, "跳过越界的 Node 运行时条目: $name")
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        when {
                            entry.isDirectory -> outCheck.mkdirs()

                            name == "LINKS.txt" -> {
                                val text = zis.readBytes().toString(Charsets.UTF_8)
                                for (line in text.lineSequence()) {
                                    val t = line.trim()
                                    if (t.isEmpty() || t.startsWith("#")) continue
                                    val parts = t.split(Regex("\\s+"))
                                    if (parts.size == 2) links.add(parts[0] to parts[1])
                                }
                            }

                            else -> {
                                val out = File(prefixDir, name)
                                out.parentFile?.mkdirs()
                                // 先写临时文件再改名：中途被杀不会留下半截的可执行文件，
                                // 而半截的 node 会在 exec 时报 "Exec format error"，
                                // 症状离根因很远。
                                //
                                // ⚠️ 临时名必须**唯一**（`createTempFile` 而不是固定的
                                // `<名字>.part`）：.part 是固定名，两个入口同时装就会开
                                // 同一个文件写、内容交错 —— 产出的正是上面那种「大小对、
                                // 内容已坏」的二进制。锁已经挡住了本类的并发，这里再防一层
                                // 别的进程/残留进程。
                                val tmp = File.createTempFile(".${out.name}.", ".part", out.parentFile)
                                try {
                                    FileOutputStream(tmp).use { zis.copyTo(it) }
                                    Files.move(
                                        tmp.toPath(), out.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING,
                                    )
                                    if (name.startsWith("bin/")) out.setExecutable(true, false)
                                } finally {
                                    tmp.delete()
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // 软链在文件全部落盘之后建，否则指向的目标还不存在。
            //
            // ⚠️ 第一列是**相对链接路径**（必须带 lib/ 前缀），第二列是相对目标。
            // 漏掉 lib/ 前缀会把软链建到 $PREFIX 根目录，而 node 的 NEEDED 写的是裸
            // 文件名、由 LD_LIBRARY_PATH=$PREFIX/lib 去那里找 —— 真机症状是
            // `CANNOT LINK EXECUTABLE ... library "libsqlite3.so" not found`。
            // 目标不存在就跳过：bootstrap 与内置运行时可能带不同小版本的同一个库
            // （如 zlib），断链会让 dlopen 报错，跳过则回落到 bootstrap 那份。
            var linked = 0
            for ((link, target) in links) {
                val linkPath = File(prefixDir, link)
                val targetPath = File(linkPath.parentFile, target)
                if (!targetPath.exists()) {
                    Log.w("TERMUX", "跳过断链 $link -> $target（目标不存在）")
                    continue
                }
                try {
                    if (Files.isSymbolicLink(linkPath.toPath())) linkPath.delete()
                    else if (linkPath.exists()) continue
                    Os.symlink(target, linkPath.absolutePath)
                    linked++
                } catch (e: Exception) {
                    Log.w("TERMUX", "symlink $link -> $target: ${e.message}")
                }
            }
            Log.i("TERMUX", "建立 $linked/${links.size} 个库软链")

            // 🔴 必须重写 node 与这批库里的烤死路径。它们全是 Termux 官方构建，
            //    二进制里带着 /data/data/com.termux/files/usr/lib 作为 RUNPATH，
            //    不重写就永远去读 com.termux 的私有目录（读不到 ⇒ 链接失败）。
            rewriteTermuxPathsLocked(prefixDir)

            // 装完再验一次：写坏了就**别写标记**，让下次启动重来一遍，而不是把
            // 一个坏运行时封印成「已安装」。
            if (nodeBinary.isFile && nodeIsUsable()) {
                nodeMarker.writeText("ok")
                Log.i("TERMUX", "Node 运行时就绪: ${nodeBinary.absolutePath}")
                true
            } else {
                Log.w("TERMUX", "Node 运行时安装后仍不可用: ${nodeBinary.absolutePath}")
                false
            }
        } catch (t: Throwable) {
            Log.w("TERMUX", "安装 Node 运行时失败: ${t.message}")
            false
        }
    }

    /**
     * 跑一次 `node --version`，判断这个二进制**真的能起来**。
     *
     * ## 🔴 为什么不能只查「文件在不在」
     *
     * 粉丝真机反馈（`Check failed: magic_number_ == SerializedData::kMagicNumber`）：
     * 崩溃点在 V8 反序列化内嵌启动快照，**早于任何一行 JS**。所以这不是插件、
     * `engine/`、profile 的问题，而是 `$PREFIX/bin/node` 本身坏了。而 [nodeMarker]
     * 的判据原本只是「标记在 + 文件在」，坏了的 node 照样命中 ⇒ 引擎永远起不来，
     * 而「进入安全模式」只推开 profile patch / 第三方 bundle / `engine/.stamp`，
     * **完全不碰 `$PREFIX`** ⇒ 用户唯一的出路是卸载重装，那会清掉整个 `$DSH_HOME`。
     *
     * ## 为什么用 `--version` 当判据
     *
     * - 不引入 sha256 常量：资产一更新就要重新算，漏算就是静默失效（本项目已有
     *   多处「版本戳漏改 ⇒ 老装机永远不更新」的教训）。
     * - 不做 47 MB 全量读校验：那是每次启动的常态开销。
     * - `--version` 恰好走完「ELF 可加载 + 动态库齐 + V8 启动快照可反序列化」这三步，
     *   正是上面那个报错发生的地方，成本约 50 ms。
     *
     * ⚠️ 判据是**版本字符串相符**，不只是「跑起来了」。链接器指错目录、库不匹配等
     * 情况会打印别的版本或直接报错，都应当判为不可用。
     */
    private fun nodeIsUsable(): Boolean {
        if (!nodeBinary.isFile) return false
        return try {
            val pb = ProcessBuilder(nodeBinary.absolutePath, "--version")
            pb.environment().apply {
                // 与真正启动**同一套**环境：node 的 NEEDED 靠 LD_LIBRARY_PATH 找
                // $PREFIX/lib，缺了它会以 `CANNOT LINK EXECUTABLE ... not found`
                // 失败，从而把「环境没给对」误判成「二进制坏了」。
                put("PREFIX", prefixDir.absolutePath)
                put("LD_LIBRARY_PATH", "${prefixDir.absolutePath}/lib")
                put("TMPDIR", "$prefixDir/tmp")
            }
            /*
             * ⚠️ **必须把 stderr 合并进 stdout，不能分两条流读。**
             *
             * 原来的写法是「先读 stdout 到 EOF、再读 stderr」：
             *
             *     val text = proc.inputStream.bufferedReader().use { it.readText() }
             *     val err  = proc.errorStream.bufferedReader().use { it.readText() }
             *
             * 这是一个**经典管道死锁**：管道的缓冲只有约 64 KB，
             * 而这里恰恰是「node 跑不起来」的那条路径 —— 缺库时
             * `CANNOT LINK EXECUTABLE` 那串错误是往 **stderr** 写的。
             * 一旦 stderr 写满 64 KB，子进程就阻塞在 write 上，再也写不出 stdout；
             * 而本线程正卡在 `readText()` 等 stdout 的 EOF —— **两边永远等不到对方**。
             *
             * 更糟的是超时兜不住它：`NODE_CHECK_TIMEOUT_MS` 写在后面的
             * `proc.waitFor(...)` 里，而死锁发生在**到达 waitFor 之前**。
             * 结果是「装了个缺库的运行时 ⇒ App 每次启动都卡死在检查这一步」，
             * 而且没有任何日志 —— 因为那行 `Log.w` 也在 waitFor 之后。
             *
             * 合并成一条流就没有这个问题：只有一个读端，读到 EOF 即进程结束。
             * 代价是 stdout/stderr 混在一起，但这里只做字符串比较
             * （`text.trim() == NODE_EXECUTABLE_VERSION`），混了也不影响判断 ——
             * 反而失败时能在同一条日志里看到两边的输出。
             */
            pb.redirectErrorStream(true)
            val proc = pb.start()
            // 必须把输出读走：不读会让子进程卡在写管道上，waitFor 等到超时。
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            // stderr 已并入 text（见上面的 redirectErrorStream），不再单读。
            val ok = proc.waitFor(NODE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                proc.exitValue() == 0 &&
                text.trim() == NODE_EXECUTABLE_VERSION
            if (!ok) {
                Log.w(
                    "TERMUX",
                    "node --version 校验失败: rc=${if (proc.isAlive) "timeout" else proc.exitValue()} " +
                        // stderr 已合并进 text（redirectErrorStream），这里一并输出。
                        "output=${text.trim().take(300)}",
                )
                if (proc.isAlive) proc.destroyForcibly()
            }
            ok
        } catch (t: Throwable) {
            Log.w("TERMUX", "node --version 校验异常: ${t.message}")
            false
        }
    }

    /** 内置 Node 可执行文件的绝对路径。 */
    val nodeBinary: File get() = File(prefixDir, "bin/node")

    /**
     * 用户显式触发「安全模式」时调用的 Node 自愈入口：**只有当 node 真的不可用时**
     * 才重解包，可用时零成本返回。
     *
     * ## 为什么安全模式必须带上这一步
     *
     * [enterSafeMode] 原本只推开三样：profile patch 层、第三方 bundle、`engine/.stamp`。
     * 这三样都在 `$DSH_HOME` / `engine/` 里，**完全不碰 `$PREFIX`**。而 node 损坏
     * （真机实测：`Check failed: magic_number_ == SerializedData::kMagicNumber`）会让
     * 引擎**连一行 JS 都跑不到**就死掉 —— 于是用户点了「进入安全模式」也一样打不开，
     * 唯一出路是卸载重装，而卸载会清掉整个 `$DSH_HOME`（会话、凭据、工作区）。
     *
     * 成本：一次 `node --version`（约 50 ms）。**不会**在 node 正常时重解包。
     *
     * @return true = 确实做了修复（调用方据此更新「已推开哪些东西」的提示）。
     */
    fun repairNodeRuntimeIfNeeded(): Boolean = prefixLock.withLock {
        withPrefixFileLock {
            if (!prefixDir.exists()) {
                false
            } else if (nodeMarker.exists() && nodeBinary.isFile && nodeIsUsable()) {
                // 用与 [installNodeRuntime] 完全相同的判据，别另立一套。
                false
            } else {
                Log.w("TERMUX", "安全模式：内置 Node 运行时不可用，重新解包 $NODE_VERSION")
                nodeMarker.delete()
                nodeBinary.delete()
                installNodeRuntimeLocked()
                true
            }
        }
    }

    /**
     * 每次启动都跑：把 ELF 路径重写补到新铺进来的文件上（幂等，已正确则零成本）。
     *
     * ⚠️ 与 [ensurePathsRewritten] 分开，原因同 [ensureMirrorsConfigured]：那条被
     * `.paths_rewritten_v6` 门住，老装机不会重进，而 Node 运行时是**后来才加的**。
     */
    fun ensureNodeRuntimePatched() {
        if (!nodeBinary.isFile) return
        try {
            // 走锁：本方法与 DshApplication 的 installNodeRuntime 是**两个不同实例**
            // 上的两条并发路径，都会原地重写同一个 47 MB 的 node 二进制。
            prefixLock.withLock { withPrefixFileLock { rewriteTermuxPathsLocked(prefixDir) } }
        } catch (e: Exception) {
            Log.w("TERMUX", "ensure node patched: ${e.message}")
        }
    }

    /**
     * Ensure shebangs and paths are rewritten for the current installation.
     * Safe to call every launch: fast on already-correct files (read + skip),
     * and fixes existing installations that upgraded from a broken APK
     * without re-extracting the 30 MB bootstrap zip.
     */
    fun ensurePathsRewritten() {
        if (prefixDir.exists() && !rewriteMarker.exists()) {
            Log.i("TERMUX", "rewriting Termux paths")
            prefixLock.withLock {
            withPrefixFileLock {
            // Same first-install sequence, minus the zip extraction: existing
            // installs need the aliases and the dpkg skeleton too, not just the
            // path rewrite.
            createPrefixAliases()
            ensureDpkgDirs()
            ensureAptInstdirRoot()
            rewriteTermuxPathsLocked(prefixDir)
            // Re-assert our apt/dpkg config on the repair path too — an install
            // that predates the instdir redirect would otherwise keep failing.
            // Only Boo-authored files are (re)written; sources.list is left
            // alone because the user or `pkg` may have repointed the mirror.
            writeAptConfig()
            rewriteMarker.writeText("ok")
            }
            }
        }
    }

    /** Environment variables for shell commands run under PREFIX. */
    fun getShellEnv(originalPath: String = "/system/bin"): Map<String, String> {
        val p = prefixDir.absolutePath
        val env = linkedMapOf(
            "PREFIX" to p,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to "$p/tmp",
            "LD_LIBRARY_PATH" to "$p/lib",
            // Termux's CA bundle lives in the app-private prefix; curl/openssl
            // won't trust HTTPS mirrors without these (mirror of bridge.go termuxEnv).
            "SSL_CERT_FILE" to "$p/etc/tls/cert.pem",
            "CURL_CA_BUNDLE" to "$p/etc/tls/cert.pem",
            // git compiled with the standard Termux prefix baked in; migrated
            // installs need these to find helpers/templates/skip system gitconfig.
            "GIT_EXEC_PATH" to "$p/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "$p/share/git-core/templates",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_SSL_CAINFO" to "$p/etc/tls/cert.pem",
            "PATH" to "$p/bin:$p/bin/applets:$originalPath",
        )
        // 镜像环境变量：配置文件已经能覆盖绝大多数工具，但 env 里直接可见 `PIP_INDEX_URL`
        // 让模型**自己就能发现**当前走哪个源，不必去读/猜配置文件内容。
        try {
            env.putAll(MirrorProbe.shellEnv(loadMirrors()))
        } catch (_: Exception) {
            // 读状态失败不影响 shell 可用性 —— 配置文件那一层仍然生效。
        }
        return env
    }

    // ── private ──

    private val termuxArch: String get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    private fun processSymlinks(symlinksFile: File, prefixDir: File) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath
        symlinksFile.readLines().forEach { line ->
            val parts = line.split("←")
            if (parts.size == 2) {
                val target = parts[0].replace(oldPrefix, newPrefix)
                try {
                    Os.symlink(target, File(prefixDir, parts[1]).absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "symlink ${parts[1]} -> $target: ${e.message}")
                }
            }
        }
    }

    private fun writeSourcesList() {
        val etcApt = File(prefixDir, "etc/apt")
        etcApt.mkdirs()
        val f = File(etcApt, "sources.list")
        val mirrors = loadMirrors()
        val chosen = mirrors[MirrorProbe.ECO_APT]
            ?: MirrorProbe.defaults()[MirrorProbe.ECO_APT] ?: return

        // 🔴 不覆盖用户/`pkg` 自己换过的源。判定见 MirrorProbe.isUserCustomizedApt：
        // 只有「文件不存在 / 空 / 与我们上次写的完全一致 / 上游 pkg 自己写的（官方源或
        // 它镜像池里的任意站）」才允许改写。
        val current = try {
            if (f.isFile) f.readText() else null
        } catch (_: Exception) {
            null
        }
        //
        // ⚠️ 第三个参数**必须传**：`pkg` 的 `select_mirror()` 会从镜像池 60+ 个站点里
        // 加权随机抽一个覆写 sources.list，池子里绝大多数是我们候选表里没有的海外站
        // （实测抽中越南 mirrors.nguyenhoang.cloud，84 KB/s vs bfsu 1.07 MB/s）。
        // 不把池子纳入判定，这份覆写就会被当成「用户自己的选择」而**永久拒绝修复**。
        val upstreamHosts = try {
            MirrorProbe.upstreamMirrorHosts(File(prefixDir, "etc/termux/mirrors"))
        } catch (_: Exception) {
            emptySet<String>()
        }
        if (MirrorProbe.isUserCustomizedApt(current, mirrors[MirrorProbe.KEY_APT_WRITTEN], upstreamHosts)) {
            Log.i("TERMUX", "apt sources.list 被用户改过，保留不动")
            return
        }
        val body = MirrorProbe.aptSourcesList(mapOf(MirrorProbe.ECO_APT to chosen)) ?: return
        try {
            f.writeText(body)
        } catch (e: Exception) {
            Log.w("TERMUX", "write sources.list: ${e.message}")
        }
    }

    /**
     * 把当前镜像选择落盘到各包管理器的配置文件。
     *
     * 幂等且**不覆盖用户手改**：只在内容与目标不一致时写入，且 apt 走
     * [writeSourcesList] 的「用户改过就不动」判定。
     */
    private fun applyMirrorConfigs(mirrors: Map<String, String>) {
        val files = MirrorProbe.configFiles(prefixDir, homeDir, File(dataDir), mirrors)
        for ((file, body) in files) {
            try {
                if (file.isFile && file.readText() == body) continue
                file.parentFile?.mkdirs()
                if (!AtomicTextFile.write(file, body)) {
                    // 回退到非原子写：某些文件系统不支持 ATOMIC_MOVE，但配置文件
                    // 写入不敏感（内容小、可重写），不值得因此整条链路失败。
                    file.writeText(body)
                }
            } catch (e: Exception) {
                Log.w("TERMUX", "write ${file.name}: ${e.message}")
            }
        }
        // apt 单独走一遍：它受「用户是否改过」约束，且失败要留日志。
        writeSourcesList()
    }

    /** 读取 [MirrorProbe.STATE_FILE]；不存在或损坏时回退候选表默认值。 */
    fun loadMirrors(): Map<String, String> =
        MirrorProbe.loadOrDefaults(File(dataDir, MirrorProbe.STATE_FILE))

    /** 当前生效的镜像表，供 UI / 调试查看。 */
    val mirrors: Map<String, String> get() = loadMirrors()

    /**
     * 后台跑一次镜像测速，挑每个生态最快的源并落盘。**不阻塞启动**。
     *
     * ⚠️ **⚠️ 本函数从未被调用过（死代码，2026-10-14 核实）。**
     *   `git log --all -S 'probeMirrors('` 只有初始提交 `e253c7c` 那一条，
     *   全仓库（Kotlin / Java / 资产 / 插件 JS）搜不到任何调用点。
     *
     *   **后果**：下面的 KDoc 描述的能力——「让机器实测一遍比让模型记住一张表
     *   可靠得多」——**从来没有生效过**。实际生效的只有
     *   `MirrorProbe.loadOrDefaults`（读 [MirrorProbe.STATE_FILE]，不存在/损坏
     *   时回退候选表默认值）。所以 `mirrors.conf` 里永远是 `defaults()` 那张
     *   写死的表，不是实测结果。
     *
     *   ⚠️ 别把这段话当成「补个调用点就好」——`probeScript()` 会打 19 个候选站，
     *   而它**每次启动都要跑**（`ensureMirrorsConfigured` 是每启动必跑的路径）还是
     *   只在首次跑，是个需要真机实测才能定的功能决策，不是缺陷修复。
     *   在这里写明「没接线」是为了不让下一个人照 KDoc 以为它已经生效。
     *
     * 为什么值得自动跑：真机实测 `pypi.org` 与 `proxy.golang.org` 完全不可达、
     * aliyun 是 PyPI 冠军但 APT 是 404 —— 写死任何一个源都会在某个生态上静默失败。
     * 让机器实测一遍比让模型记住一张表可靠得多（这正是用户要的「先检索节点挑最快」）。
     *
     * ⚠️ 失败**不是**错误：探测不到任何东西时保留 [MirrorProbe.defaults]（那是实测里
     * 各生态最快的一个），所以「探测挂了」不会让任何工具失去可用源。
     *
     * @param force 为 true 时忽略缓存，强制重测。
     * @return 本次选中的表；探测无结果时返回当前（默认或缓存）表。
     */
    suspend fun probeMirrors(force: Boolean = false): Map<String, String> {
        val cached = loadMirrors()
        if (!force) {
            val marker = File(dataDir, MirrorProbe.STATE_FILE)
            // 缓存有效期内不重测：每次启动都打 19 个候选是纯粹的浪费。
            if (marker.isFile && System.currentTimeMillis() - marker.lastModified() < probeTtlMs) {
                return cached
            }
        }
        val script = File(prefixDir, "bin/${MirrorProbe.PROBE_CMD}")
        val raw = try {
            script.parentFile?.mkdirs()
            script.writeText(MirrorProbe.probeScript())
            script.setExecutable(true, false)
            runProbeScript(script)
        } catch (e: Exception) {
            Log.w("TERMUX", "mirror probe: ${e.message}")
            return cached
        }
        val picked = MirrorProbe.parseProbeOutput(raw)
        if (picked.isEmpty()) {
            Log.w("TERMUX", "mirror probe 没有任何 200 结果，保留当前源")
            return cached
        }
        val merged = MirrorProbe.merge(cached + picked)
        applyMirrorConfigs(merged)
        saveState(merged)
        Log.i("TERMUX", "mirror probe 选中: ${picked.entries.joinToString { "${it.key}=${it.value}" }}")
        return merged
    }

    /** 执行探测脚本。用 `/system/bin/sh` 而非 prefix 里的 shell —— 探测的意义正是在
     *  prefix 不可用时也能跑，用需要依赖 prefix 的 shell 去探测 prefix 是循环依赖。 */
    private suspend fun runProbeScript(script: File): String = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("/system/bin/sh", script.absolutePath)
        pb.redirectErrorStream(true)
        pb.environment().putAll(getShellEnv())
        val proc = pb.start()
        /*
         * ⚠️ 三点都必须有，缺一个就会"卡死整条链路"：
         *
         * ① **`waitFor` 必须带超时**。原来是无参的 `proc.waitFor()`，
         *    探测脚本要连 19 个镜像站，任何一个 TCP 连接挂在半开状态
         *    （SYN 不回也不 RST）就会永远不返回 —— 而这段代码跑在
         *    `Dispatchers.IO` 上，占住的是一条 IO 线程，排队等它的协程全卡住。
         *    本项目在 [checkNodeRuntime] 里已经有同样的做法（`NODE_CHECK_TIMEOUT_MS`），
         *    这里照抄那个范式。
         *
         * ② **`readBytes()` 必须换成带上限的读**。`/system/bin/sh` 跑的是
         *    我们能改的脚本没错，但脚本里的 `curl` 输出量取决于**远端**：
         *    镜像站返回一个巨大的错误页，`readBytes()` 就把它整个吃进堆。
         *    与 [ShizukuShell.drain] / [ShizukuChannel.drain] 同一族问题。
         *
         * ③ **超时后必须 `destroyForcibly`**，否则子进程会变成孤儿继续跑
         *    （它可能还在挨个连镜像站），用户装一次 App 多 19 条 TCP 连接。
         *
         * ⚠️ 补充说明：本函数**当前没有活调用方**（`probeMirrors` 从初始提交起
         *   就没人调用，见 [saveState] 的注释）。但这不构成"不用管"的理由 ——
         *   死代码一旦被接上就立刻变成真实缺陷，而修它只花这几行。
         */
        val maxBytes = 512 * 1024
        val out = StringBuilder()
        try {
            val buf = ByteArray(8 * 1024)
            var captured = 0
            while (true) {
                val n = proc.inputStream.read(buf)
                if (n <= 0) break
                if (captured >= maxBytes) continue   // 到上限后继续读空，避免子进程写阻塞
                val take = minOf(n, maxBytes - captured)
                out.append(String(buf, 0, take, Charsets.UTF_8))
                captured += take
            }
            // 流读到 EOF 说明进程已结束，这个 waitFor 会立刻返回；超时分支只为兜底。
            if (!proc.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w("TERMUX", "镜像探测脚本超过 ${PROBE_TIMEOUT_MS / 1000}s 未结束，强制结束")
                proc.destroyForcibly()
            }
        } catch (t: Throwable) {
            Log.w("TERMUX", "镜像探测脚本执行失败: ${t.message}")
            runCatching { proc.destroyForcibly() }
        }
        out.toString()
    }

    /**
     * 落盘镜像选择 + 记录我们写进 sources.list 的 apt 源，供下次判断是否被用户改过。
     *
     * ⚠️ **本函数目前没有活调用点。** 唯一调用者是 [probeMirrors]，而它从初始提交
     *   `e253c7c` 起就**从未被任何地方调用**（`git log -S 'probeMirrors('` 只有初始
     *   提交那一条，此后无人调用）—— 也就是说自动测速这条链路整条是死的，
     *   `mirrors.conf` 从来只有 `MirrorProbe.loadOrDefaults` 的**自愈写**在维护
     *   （那条会写 `apt_written`，所以 `isUserCustomizedApt` 的判定仍能工作）。
     *
     *   这与 `skipDirs` / `writeEntry` / `bumpAttempts` 是同一族缺陷：**KDoc 承诺了
     *   一个实现里不存在的保证**（[probeMirrors] 的 KDoc 写着「后台跑一次镜像测速…
     *   不阻塞启动」，读代码的人会以为镜像会自动择优）。真要修不是补这一行的返回值，
     *   而是决定这条链路**要不要接上** —— 那属于功能决策，不是缺陷修复，见 AGENTS.md。
     */
    private fun saveState(mirrors: Map<String, String>) {
        val withApt = LinkedHashMap(mirrors)
        mirrors[MirrorProbe.ECO_APT]?.let { withApt[MirrorProbe.KEY_APT_WRITTEN] = it }
        val f = File(dataDir, MirrorProbe.STATE_FILE)
        try {
            // ⚠️ **必须检查返回值**：`AtomicTextFile.write` 失败时**返回 false 而不抛异常**
            //   （见其 KDoc），只包 try/catch 接不住这种失败。写不进去的后果不是
            //   「少了个缓存」—— `apt_written` 是下次启动判断「sources.list 是不是被
            //   用户手改过」的唯一依据；丢了这个键，App 会把自己上次写的源当成
            //   「用户改的」，于是再也不更新 apt 源，而日志里看不到任何异常。
            if (!AtomicTextFile.write(f, MirrorProbe.encodeState(withApt))) {
                Log.w("TERMUX", "镜像选择落盘失败（${f.name}）：下次启动可能误判 apt 源被用户改过")
            }
        } catch (e: Exception) {
            Log.w("TERMUX", "save mirror state: ${e.message}")
        }
    }

    /** Write APT config for a faster, non-interactive Termux experience. */
    private fun writeAptConfig() {
        val confDir = File(prefixDir, "etc/apt/apt.conf.d")
        confDir.mkdirs()
        // Disable recommends/suggests — they pull in X11 etc. Termux doesn't need.
        File(confDir, "01-norecommends").writeText("""
APT::Get::Install-Recommends "false";
APT::Get::Install-Suggests "false";
""".trimIndent())
        // Non-interactive, assume yes.
        File(confDir, "02-options").writeText("""
APT::Get::Assume-Yes "true";
APT::Get::AllowUnauthenticated "true";
Dpkg::Options {"--force-confdef";"--force-confold"};
""".trimIndent())
        // Redirect apt's archive cache. libapt-pkg.so has
        // `/data/data/com.termux/cache/apt` compiled in, and that path does NOT
        // match the `/data/data/com.termux/files` rule the ELF patcher rewrites
        // (its root is `…/com.termux/cache`, a sibling of `…/com.termux/files`).
        // Left alone, every download dies with
        //   E: Archives directory /data/data/com.termux/cache/apt/archives/partial
        //      is missing. - Acquire (13: Permission denied)
        // `Dir::Cache` is a plain apt.conf default, so overriding it here is
        // enough — no need to touch the shared library.
        File(confDir, "03-cache").writeText("""
Dir::Cache "$prefixDir/var/cache/apt";
Dir::Cache::archives "$prefixDir/var/cache/apt/archives";
""".trimIndent())
        // Make dpkg unpack into our prefix instead of the filesystem root.
        // See ensureAptInstdirRoot for why bare `/` cannot work. The braced
        // list form is required: apt.conf.d splits `DPkg::Options::=` on
        // whitespace, so the un-braced spelling silently truncates the path.
        val instdir = File(rootDir, "aptroot").absolutePath
        File(confDir, "04-instdir").writeText("""
DPkg::options { "--instdir=$instdir"; };
""".trimIndent())
        // Same setting for direct `dpkg` invocations, which never read
        // apt.conf.d. dpkg.cfg uses the bare `option value` form — this file is
        // NOT apt.conf syntax, and quoting the value or adding a semicolon makes
        // dpkg abort with "unbalanced quotes in 'instdir'".
        //
        // All three lines are required; each one was surfaced only after fixing
        // the previous, measured on device with `dpkg --configure -a`:
        //
        //  1. `instdir` alone            → "admindir must be inside instdir for
        //                                   dpkg to work properly"
        //  2. + `admindir`               → "failed to chroot to '<aptroot>':
        //                                   Operation not permitted" — an app uid
        //                                   has no CAP_SYS_CHROOT, so dpkg can
        //                                   never chroot no matter what
        //  3. + `force-script-chrootless` → maintainer scripts finally run
        //
        // Only packages that *have* a maintainer script trip 1 and 2, which is
        // why `wget` installed cleanly while `glib`/`openssh` always failed.
        //
        // Line 4 is a `post-invoke` sweep (see [installShebangFixHook]). It has
        // to live in **dpkg.cfg**, not apt.conf.d: dpkg runs maintainer scripts
        // in a separate `--configure` pass *after* `--unpack`, and apt's
        // `DPkg::Post-Invoke` only fires once the whole transaction is done —
        // far too late. Measured with `python-pip`, whose postinst shells out to
        // `pip` itself: the apt-level hook did rewrite the shebang, but only
        // after the postinst had already died on it.
        File(prefixDir, "etc/dpkg/dpkg.cfg").writeText(
            "instdir $instdir\n" +
                "admindir $instdir/var/lib/dpkg\n" +
                "force-script-chrootless\n" +
                "post-invoke $shebangHookPath\n"
        )
        installDebFixHook()
    }

    /**
     * Install the apt hook that repairs `.deb` control scripts before dpkg runs
     * them.
     *
     * [rewriteTermuxPaths] fixes the paths of everything **already on disk**.
     * That is not enough: every `pkg install` unpacks a *fresh* `.deb` whose
     * maintainer scripts still carry the official Termux prefix, so dpkg writes
     * those official paths back over our repaired copies. Measured on device —
     * re-installing `glib` after a manual fix restored the broken shebang and
     * the package dropped straight back to `iF`.
     *
     * Maintainer scripts are not cosmetic. `openssh.postinst` is what generates
     * the sshd host keys and `glib.postinst` is what compiles the GSettings
     * schema cache; when the script dies on its shebang the package still ends
     * up "installed" but silently half-working, which is exactly the
     * "能用但不好用" failure mode.
     *
     * `DPkg::Pre-Install-Pkgs` is invoked once per dpkg transaction with every
     * `.deb` path on stdin (measured: one PRE / one POST for a 10-package
     * install), so the hook is the single interception point where a package can
     * still be repaired before it lands. It rewrites the prefix inside the
     * `control.tar.*` member and repacks with `ar` — the payload member is
     * copied through untouched, so package contents are never rewritten.
     *
     * The hook runs under `/system/bin/sh` (mksh, ships with Android) rather
     * than our prefix's shell: a hook that can only run once the prefix works
     * would be useless on exactly the installs that need it.
     */
    private fun installDebFixHook() {
        // The sweep rewrites the official prefix inside ELF binaries, which is
        // only safe because the two strings are the same length (pure
        // overwrite — no offsets shift). The sweep interpolates elfRootReplacement
        // into itself, so assert the invariant here: refuse to write a hook that
        // would silently corrupt every binary it touched.
        check(OLD_TERMUX_ROOT.length == elfRootReplacement.length) {
            "Termux path rewrite must be equal length: " +
                "${OLD_TERMUX_ROOT.length} != ${elfRootReplacement.length}"
        }
        try {
            val hookDir = File(prefixDir, "etc/apt/hooks")
            val confDir = File(prefixDir, "etc/apt/apt.conf.d")
            hookDir.mkdirs()
            confDir.mkdirs()

            val hook = File(hookDir, "boo-fix-deb.sh")
            hook.writeText(debFixHookScript())
            hook.setExecutable(true, false)

            // Repairs the payload *after* unpack; referenced by dpkg.cfg, not
            // apt.conf.d — see writeAptConfig for why the timing matters.
            val sweep = File(hookDir, "boo-fix-paths.sh")
            sweep.writeText(shebangFixHookScript())
            sweep.setExecutable(true, false)

            File(confDir, "97-boo-fixdeb").writeText(
                "DPkg::Pre-Install-Pkgs { \"${hook.absolutePath}\"; };\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "install deb fix hook: ${e.message}")
        }
    }

    /** Path of the post-unpack sweep hook, referenced from `dpkg.cfg`. */
    private val shebangHookPath: String
        get() = File(prefixDir, "etc/apt/hooks/boo-fix-paths.sh").absolutePath

    /**
     * Body of `etc/apt/hooks/boo-fix-paths.sh`, wired in as dpkg's `post-invoke`.
     *
     * [debFixHookScript] repairs the `.deb` **control** member — the scripts
     * dpkg executes. That leaves the files dpkg *installs* alone, and a fresh
     * payload carries the official prefix in two more places:
     *
     *  1. **Shebangs of the installed entry points.** `python-pip` ships
     *     `$PREFIX/bin/pip` starting with `#!/data/data/com.termux/files/usr/bin/
     *     python3.14`. The package reaches `ii`, and the moment anything runs
     *     `pip` it dies with `bad interpreter: No such file or directory`.
     *  2. **Plain-text data files.** Termux's own packages are patched upstream
     *     to hardcode `/data/data/com.termux/files/usr/etc` as the site config
     *     root — `pip/_vendor/platformdirs/unix.py` and `pip/_internal/utils/
     *     appdirs.py` both do. `pip` then writes `/data/data/com.termux/files/
     *     usr/etc/pip.conf` and fails with `PermissionError: [Errno 13]`.
     *
     * Both are rewritten here, right after unpack and before `--configure`, so a
     * package's own postinst sees a self-consistent prefix. Measured: with this
     * hook in place `python-pip` configures cleanly and `pip download six` works.
     *
     * `grep -I` skips binaries, which is what keeps the sweep safe — an ELF
     * cannot be length-changed, and [patchElfInPlace] already handled the ones
     * that matter. The whole prefix costs well under a second (measured: 0 s for
     * ~1,900 candidate files), so it runs unattended on every transaction rather
     * than trying to guess which paths a package touched.
     *
     * The log lives in `$PREFIX/tmp` and is append-only, so a failed install can
     * be diagnosed after the fact; the hook always exits 0 and must never turn a
     * working install into a failing one.
     */
    private fun shebangFixHookScript(): String = """
        #!/system/bin/sh
        # Boo: repoint official-Termux paths left behind by a freshly unpacked
        # payload. Runs as dpkg `post-invoke`, i.e. after --unpack and before
        # --configure, so maintainer scripts see a consistent prefix.
        PREFIX=$prefixDir
        T=com.termux
        OLD=/data/data/${'$'}T/files/usr
        PATH=${'$'}PREFIX/bin:/system/bin
        export PATH
        LOG=${'$'}PREFIX/tmp/boo-fix-paths.log
        ch=0

        # 1. Shebangs of installed entry points. `bin` holds ~450 files and
        #    `libexec` ~107; a single `grep -l` over the globs keeps this at a
        #    few milliseconds instead of a shell loop over every file.
        for d in bin libexec; do
          for f in $(grep -l -a "^#!${'$'}OLD" "${'$'}PREFIX/${'$'}d"/* 2>/dev/null); do
            sed -i "1s|${'$'}OLD|${'$'}PREFIX|" "${'$'}f" 2>/dev/null && echo "shebang ${'$'}f" >> "${'$'}LOG"
            ch=1
          done
        done

        # 2. Maintainer scripts sitting in dpkg's own database. `--unpack`
        #    extracts every package's scripts into `var/lib/dpkg/info/` and
        #    `--configure` executes them from there, so this is the copy that
        #    actually runs, and the sweep above deliberately skips the whole
        #    `var/lib/dpkg` tree. The `.deb` repair in [debFixHookScript] should
        #    have already cleaned them at the source; doing it again here is the
        #    safety net for the case where that hook could not run at all, which
        #    is exactly how the `ar`-less baseline failed before. Measured on
        #    device: `dpkg --unpack vim` leaves 11 official-prefix hits in
        #    `vim.postinst` and 9 in `vim.prerm`, and fixing them here is what
        #    lets `--configure` succeed.
        for f in "${'$'}PREFIX"/var/lib/dpkg/info/*.postinst \
                 "${'$'}PREFIX"/var/lib/dpkg/info/*.preinst \
                 "${'$'}PREFIX"/var/lib/dpkg/info/*.prerm \
                 "${'$'}PREFIX"/var/lib/dpkg/info/*.postrm; do
          [ -f "${'$'}f" ] || continue
          grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null || continue
          sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f"
          # Same DPKG_ROOT trap as in the .deb repair — see that hook for the
          # full account. Idempotent, so applying it twice is harmless.
          grep -q '^unset DPKG_ROOT${'$'}' "${'$'}f" || sed -i '1a unset DPKG_ROOT' "${'$'}f"
          echo "info ${'$'}f" >> "${'$'}LOG"
          ch=1
        done

        # 3. Plain-text files anywhere in the prefix. `-I` skips binaries; a
        #    length-changing sed on an ELF would corrupt it. dpkg's own metadata
        #    is skipped: rewriting `info/*.list` would desynchronise the file
        #    list from what dpkg believes it installed, and the apt logs are
        #    historical records, not live configuration. The hooks are skipped so
        #    this script cannot rewrite its own needle.
        for f in $(grep -rl -I "${'$'}OLD" "${'$'}PREFIX" 2>/dev/null); do
          case "${'$'}f" in
            "${'$'}PREFIX"/var/lib/dpkg/*|"${'$'}PREFIX"/var/log/*|"${'$'}PREFIX"/tmp/*) continue ;;
            "${'$'}PREFIX"/etc/apt/hooks/*) continue ;;
          esac
          sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f" 2>/dev/null && echo "text ${'$'}f" >> "${'$'}LOG"
          ch=1
        done

        # 4. ELF binaries, equal-length rewrite only. A fresh .deb payload carries
        #    the official prefix inside its executables too — `git` bakes it into
        #    12 strings (its templates dir, `etc/gitconfig`, the sh/bash/login it
        #    hands to hooks, and RUNPATH). Unpatched, every `git init` prints
        #    "warning: templates not found in" the com.termux path and the
        #    compiled-in RUNPATH sends the linker into com.termux's private dir.
        #
        #    The substitution MUST be byte-for-byte equal length (27 -> 27): that
        #    is what makes a plain `sed` safe on an ELF — nothing shifts, no
        #    `.dynstr` index moves, no neighbouring string gets clipped. This is
        #    the same rule [patchElfInPlace] applies at bootstrap; the difference
        #    is that it runs only once, at unpack time, so packages installed
        #    *later* never got it. Verified on device: 3613096 bytes before and
        #    after, 0 residual hits, `git --version` still answers 2.56.0.
        #
        #    ⚠️ ELF_OLD is assembled from T for the same reason the hooks above
        #    are: [rewriteTermuxPaths] walks this very file and rewrites every
        #    literal occurrence of the official root, so spelling it out in full
        #    would make the rewriter substitute the longer real prefix, the
        #    lengths would stop matching, and every binary this loop touched
        #    would be silently corrupted.
        #    ⚠️ ELF_NEW is interpolated from elfRootReplacement so there is a
        #    single source of truth for the 27-byte spelling;
        #    [installDebFixHook] refuses to write this script at all unless that
        #    constant matches OLD_TERMUX_ROOT in length.
        ELF_OLD=/data/data/${'$'}T/files
        ELF_NEW=$elfRootReplacement
        for d in bin libexec lib; do
          [ -d "${'$'}PREFIX/${'$'}d" ] || continue
          for f in ${'$'}(grep -rl -a "${'$'}ELF_OLD" "${'$'}PREFIX/${'$'}d" 2>/dev/null); do
            sed -i "s|${'$'}ELF_OLD|${'$'}ELF_NEW|g" "${'$'}f" 2>/dev/null && echo "elf ${'$'}f" >> "${'$'}LOG"
            ch=1
          done
        done

        # Python bytecode caches the string constants it was compiled from, so a
        # stale .pyc keeps the old path alive after its .py has been fixed.
        # Gated on `ch` — an untouched transaction must not throw away every
        # cache in the prefix, and a recompile on next import is the whole cost.
        if [ "${'$'}ch" = "1" ]; then
          find "${'$'}PREFIX/lib/python"* -name '*.pyc' -delete 2>/dev/null
        fi

        exit 0
    """.trimIndent()

    /**
     * Body of `etc/apt/hooks/boo-fix-deb.sh`.
     *
     * ⚠️ The official prefix is spelled as `$T` + `/files/usr` on purpose.
     * [rewriteTermuxPaths] rewrites every plain-text occurrence of
     * [OLD_TERMUX_ROOT], and it walks this very file — writing the prefix out in
     * full would make the rewriter blank its own needle, and the hook would then
     * silently match nothing. Splitting the literal keeps it out of reach.
     *
     * The archive is unpacked and repacked with `dpkg-deb`, **not** `ar`.
     * `ar` is not part of the baseline: the prefix has no `binutils`, and
     * `$PREFIX/bin/ar` only resolves to `llvm-ar` — which the `llvm` package
     * provides, and which a cold `pkg install python` pulls in as a dependency
     * *of that very transaction*. A hook that needs `ar` therefore fails on the
     * first package that carries a maintainer script, and fails **silently**
     * (the old code ended the loop iteration on a failed extract), leaving the
     * `.deb` untouched and the install dead at `dpkg returned an error code
     * (1)`. Measured 2026-09-27: the model then spent 12 rounds hand-patching
     * dpkg's own maintainer-script copies under `var/lib/dpkg/info/` on device
     * to get python installed at all.
     *
     * ⚠️ Never write a bare path glob in a KDoc block here. Kotlin block
     * comments **nest**, so the slash-star that a glob such as `info` + `/*`
     * contains opens a second comment level, and every `*/` after it closes the
     * wrong one — the compiler then reports `Unclosed comment` at end of file
     * and every symbol in the class reads as unresolved. Cost me one build.
     *
     * `dpkg-deb` ships inside the `dpkg` package itself, which is already
     * present before the very first `apt install` — so it is the one extractor
     * guaranteed to exist. It also removes the hand-rolled `ar` member ordering:
     * `debian-binary` / `control.tar.*` / `data.tar.*` is emitted by dpkg-deb in
     * the required order, where the old `ar rc …` had to spell it out.
     *
     * ⚠️ `dpkg-deb -R` extracts maintainer scripts as mode 644 while `-b`
     * refuses anything below 0555 (`maintainer script 'preinst' has bad
     * permissions 644`), so the scripts are `chmod 755`ed before repacking.
     * Measured: without that chmod the rebuild aborts and `termux-tools` — the
     * one baseline package with a `preinst` — fails with `unable to execute new
     * termux-tools package pre-installation script … No such file or
     * directory`.
     */
    private fun debFixHookScript(): String = """
        #!/system/bin/sh
        # Boo: rewrite the Termux prefix baked into .deb control scripts.
        # apt passes the .deb paths on stdin via DPkg::Pre-Install-Pkgs.
        PREFIX=$prefixDir
        T=com.termux
        OLD=/data/data/${'$'}T/files/usr
        PATH=${'$'}PREFIX/bin:/system/bin
        export PATH
        # ⚠️ Prefer apt's own TMPDIR. Under the `workspace-write` preset the
        # shell layer relocates TMPDIR into the workspace (and under `read-only`
        # it clears it entirely), so hardcoding `${'$'}PREFIX/tmp` would scribble
        # outside the directory the sandbox actually allows. Fall back to the
        # prefix only when TMPDIR is unset or dangling — that is the
        # full-access case, where nothing is confined anyway.
        TMPROOT=${'$'}{TMPDIR:-}
        [ -n "${'$'}TMPROOT" ] && [ -d "${'$'}TMPROOT" ] || TMPROOT=${'$'}PREFIX/tmp
        LOG=${'$'}TMPROOT/boo-fix-deb.log
        TMPD=${'$'}TMPROOT/bfd.${'$'}${'$'}
        mkdir -p "${'$'}TMPD" || exit 0

        while read -r deb; do
          [ -f "${'$'}deb" ] || continue
          case "${'$'}deb" in *.deb) ;; *) continue ;; esac

          # Cheap gate: pull just the control member first. Most of a
          # transaction needs no repair at all, and a package that is left
          # byte-identical is strictly better than one needlessly rebuilt.
          # ⚠️ `-e` writes the control files straight into the directory given,
          # whereas `-R` nests them under `DEBIAN/` — hence the two spellings.
          e=${'$'}TMPD/e
          rm -rf "${'$'}e"; mkdir -p "${'$'}e" || continue
          dpkg-deb -e "${'$'}deb" "${'$'}e" 2>/dev/null || continue

          need=0
          for f in "${'$'}e"/postinst "${'$'}e"/preinst "${'$'}e"/prerm \
                   "${'$'}e"/postrm "${'$'}e"/triggers "${'$'}e"/control; do
            [ -f "${'$'}f" ] || continue
            grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null && need=1
          done
          [ "${'$'}need" = "0" ] && continue

          d=${'$'}TMPD/w
          rm -rf "${'$'}d"; mkdir -p "${'$'}d" || continue
          dpkg-deb -R "${'$'}deb" "${'$'}d" >>"${'$'}LOG" 2>&1 || continue

          ch=0
          # Real maintainer scripts: rewrite the prefix AND clear DPKG_ROOT.
          for f in "${'$'}d/DEBIAN"/postinst "${'$'}d/DEBIAN"/preinst \
                   "${'$'}d/DEBIAN"/prerm "${'$'}d/DEBIAN"/postrm; do
            [ -f "${'$'}f" ] || continue
            # 🔴 `-R` drops maintainer scripts to 0644, and `-b` refuses to
            # rebuild anything below 0555. Measured: the rebuild aborted with
            # "maintainer script 'preinst' has bad permissions 644" and, because
            # the repacked file was never produced, `dpkg --unpack` then died on
            # "/…/tmp.ci/preinst: No such file or directory".
            chmod 755 "${'$'}f"
            grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null || continue
            sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f"
            # 🔴 dpkg exports `DPKG_ROOT=<aptroot>` to every maintainer script
            # (probed on device). Tools that honour it — `update-alternatives`,
            # `dpkg-maintscript-helper` — prepend it to each path they are
            # handed. The paths above are now real, so that prepending lands on
            # `<aptroot>/data/user/0/.../bin/nano`, which does not exist, and
            # the script dies during --configure. Measured: `nano` aborted with
            # "alternative path ... doesn't exist" and stuck at `iF`.
            # Clearing it makes the script agree with the paths it now holds.
            # Inserted after line 1 so a shebang stays first; idempotent.
            grep -q '^unset DPKG_ROOT${'$'}' "${'$'}f" || sed -i '1a unset DPKG_ROOT' "${'$'}f"
            ch=1
          done

          # `triggers`/`control` are metadata parsed by dpkg, not shell — the
          # prefix is rewritten but nothing may be inserted into them.
          for f in "${'$'}d/DEBIAN"/triggers "${'$'}d/DEBIAN"/control; do
            [ -f "${'$'}f" ] || continue
            grep -q "${'$'}OLD" "${'$'}f" 2>/dev/null || continue
            sed -i "s|${'$'}OLD|${'$'}PREFIX|g" "${'$'}f"
            ch=1
          done

          if [ "${'$'}ch" = "1" ]; then
            # Rebuild the whole package: `-b` takes the parent of `DEBIAN/`, and
            # emits a correctly ordered ar archive for us.
            # 🔴 The output must NOT live inside "${'$'}d" — that directory *is* the
            # payload being packed, so `-b "${'$'}d" "${'$'}d/fix.deb"` sweeps the
            # half-written archive into the package and every rebuilt .deb ships
            # a stray `/fix.deb`. Measured: the first rebuilt package
            # (libcompiler-rt) claimed it, and python, glib and python-pip then
            # all died with "trying to overwrite '/fix.deb'". Write to the
            # sibling scratch dir instead.
            # ⚠️ `-Zgzip -z1` is deliberate. The default `-Zxz` re-compresses the
            # payload and costs ~14 s on python's 25 MB of files; python pulls
            # ~20 packages, so the default would add minutes to a single `pkg
            # install`. Measured on device: `-Zxz` 14 s / 4.82 MB, `-Zxz -z0`
            # 1 s / 6.05 MB, `-Zgzip -z1` 0 s / 7.90 MB. The repacked file only
            # ever lives in apt's archive cache long enough for dpkg to read it,
            # so the size costs nothing and the seconds are the whole story.
            # Verified faithful: 1044 of 1044 payload entries preserved with
            # mode-identical listings, and `dpkg --dry-run -i` accepts it.
            dpkg-deb -b -Zgzip -z1 "${'$'}d" "${'$'}TMPD/fix.deb" >>"${'$'}LOG" 2>&1 || continue
            cp "${'$'}TMPD/fix.deb" "${'$'}deb" && echo "fixed $(basename ${'$'}deb)" >> "${'$'}LOG"
          fi
        done

        rm -rf "${'$'}TMPD"
        exit 0
    """.trimIndent()

    /** Copy GPG keyrings from share/termux-keyring/ into etc/apt/trusted.gpg.d/.
     *  Symlinks are avoided: on some Android versions Os.symlink fails for
     *  cross-directory links under the app data dir. */
    private fun installGpgKeys(prefixDir: File) {
        val keyDir = File(prefixDir, "share/termux-keyring")
        if (!keyDir.isDirectory) return
        val trustedDir = File(prefixDir, "etc/apt/trusted.gpg.d")
        trustedDir.mkdirs()
        keyDir.listFiles { f -> f.name.endsWith(".gpg") }?.forEach { key ->
            val dest = File(trustedDir, key.name)
            if (!dest.exists()) {
                try {
                    key.inputStream().use { src -> dest.outputStream().use { src.copyTo(it) } }
                    Log.i(TAG, "  gpg key: ${key.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "copy gpg key ${key.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * Create the short symlinks that the rewritten paths resolve through.
     *
     * The real prefix is `/data/data/com.dsh.android/files/termux/usr` (43 bytes).
     * Termux binaries bake in `/data/data/com.termux/files/usr` (31 bytes), and a
     * replacement string inside an ELF **cannot grow** — see [patchElfInPlace].
     * So we rewrite the 27-byte root `/data/data/com.termux/files` to the equally
     * sized `/data/data/com.dsh.android/`, which leaves binaries asking for
     * `/data/data/com.dsh.android//usr` (a legal double slash). These links make
     * both spellings — the ELF one and the text-file one — land on the real
     * prefix. Creating them is cheap and they are idempotent.
     */
    private fun createPrefixAliases() {
        val appDataRoot = File(dataDir).parentFile ?: return
        // ELF spelling: /data/data/com.dsh.android//usr
        ensureSymlink(File(appDataRoot, "usr"), prefixDir)
        ensureSymlink(File(appDataRoot, "home"), homeDir)
        // Text spelling: /data/data/com.dsh.android/files/usr
        ensureSymlink(File(dataDir, "usr"), prefixDir)
        ensureSymlink(File(dataDir, "home"), homeDir)
    }

    /**
     * dpkg opens its compiled-in config directory *before* it parses argv or
     * environment, so `DPKG_CONFIG_DIR`, `DPKG_ADMINDIR`, `DPKG_ROOT` and even
     * `--admindir` are all ignored — it dies with EACCES on the com.termux path
     * regardless. The bootstrap ships `var/lib/dpkg` fully seeded but does not
     * ship `etc/dpkg` at all, so create that skeleton ourselves.
     */
    private fun ensureDpkgDirs() {
        File(prefixDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(prefixDir, "etc/dpkg/origins").mkdirs()
        val cfg = File(prefixDir, "etc/dpkg/dpkg.cfg")
        if (!cfg.exists()) cfg.writeText("")
    }

    /**
     * Build the fake root that makes dpkg unpack into the real prefix.
     *
     * Termux `.deb` files store their payload under the absolute path they
     * expect to be installed at:
     *   `./data/data/com.termux/files/usr/lib/libz.so.1.3.2`
     * and dpkg's `instdir` defaults to `/`. So dpkg literally tries to
     * `stat("./data/data/com.termux")` under the filesystem root — a directory
     * owned by com.termux, unreadable by this app — and the install dies with
     *   `unable to stat './data/data/com.termux' …: Permission denied`
     * even after every path string in dpkg/apt has been rewritten.
     *
     * Fix: point `--instdir` at a directory tree that *contains* that path as a
     * symlink back to our own data dir. dpkg then resolves
     * `<root>/data/data/com.termux/files/usr/…` straight into the real prefix,
     * and no package needs repacking. `var/lib/dpkg` is linked for the same
     * reason: dpkg derives its admin dir from `instdir`, and without the link it
     * would silently create an *empty* database there (every package would then
     * look uninstalled and get reinstalled wholesale).
     *
     * A symlink rather than a bind mount because this runs as an ordinary app —
     * no root, no mount namespace.
     */
    private fun ensureAptInstdirRoot() {
        val root = File(rootDir, "aptroot")
        File(root, "data/data").mkdirs()
        File(root, "var/lib").mkdirs()
        val appDataRoot = File(dataDir).parentFile ?: return
        ensureSymlink(File(root, "data/data/com.termux"), appDataRoot)
        ensureSymlink(File(root, "var/lib/dpkg"), File(prefixDir, "var/lib/dpkg"))
    }

    private fun ensureSymlink(linkPath: File, target: File) {
        try {
            // Never clobber a real file or directory that happens to sit there.
            if (Files.isSymbolicLink(linkPath.toPath())) linkPath.delete()
            else if (linkPath.exists()) return
            Os.symlink(target.absolutePath, linkPath.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "symlink ${linkPath.name}: ${e.message}")
        }
    }

    /**
     * Point every baked-in `/data/data/com.termux/files` reference at this install.
     *
     * Two rules, because the two kinds of file have different constraints:
     *
     * - **Text** (shebangs, profile scripts, configs) is rewritten to the real
     *   prefix path, length be damned. Kernel shebang resolution can follow the
     *   `files/usr` alias symlink.
     * - **ELF binaries** get an **equal-length, in-place** byte substitution:
     *   `/data/data/com.termux/files` (27 bytes) → `/data/data/com.dsh.android/`
     *   (27 bytes, trailing slash included). Length is identical, so this is a
     *   pure overwrite: no offset shifts, no `.dynstr` indices move, no
     *   neighbouring string that shares a suffix gets clipped, and no NUL
     *   padding is needed. The old code skipped binaries entirely, which is why
     *   `dpkg`/`apt` kept reading com.termux's app-private dir and died with
     *   `Permission denied`.
     *
     * Files we cannot patch safely are left untouched on purpose: the
     * `libtermux-exec*.so` shims carry a bare `/data/data/com.termux` (no
     * `/files` suffix) and `bin/termux-am*` carries a socket path under
     * `.../apps/com.termux/` — neither matches the rule, and Boo never invokes
     * any of them.
     *
     * ⚠️ 本函数会**原地重写** prefix 里的可执行文件（含 47 MB 的 node），所以
     * 调用方**必须已持有 [prefixLock]** —— 两个实例并发跑它会产出内容交错的
     * 二进制。对外的入口见 [ensureNodeRuntimePatched] / [ensurePathsRewritten]。
     */
    private fun rewriteTermuxPathsLocked(prefixDir: File) {
        val textNew = dataDir
        if (OLD_TERMUX_ROOT == textNew) return

        val elfOld = OLD_TERMUX_ROOT.toByteArray(Charsets.ISO_8859_1)
        val elfNew = elfRootReplacement.toByteArray(Charsets.ISO_8859_1)
        var patched = 0

        prefixDir.walkTopDown()
            // Do not descend into directory symlinks: the upstream bootstrap
            // ships links such as `bin/applets`, and following one would let us
            // walk (and now *write*) outside the prefix.
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                if (!file.isFile) return@forEach
                // Never patch *through* a file symlink either — see onEnter.
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                try {
                    if (isElf(file)) {
                        if (file.length() <= MAX_ELF_BYTES) {
                            patched += patchElfInPlace(file, elfOld, elfNew)
                        }
                    } else {
                        if (file.length() > MAX_TEXT_BYTES) return@forEach
                        val text = file.readText()
                        if (OLD_TERMUX_ROOT in text) {
                            // Preserve executable bit: writeText creates a new
                            // file which resets permissions to 644, but scripts
                            // in bin/ must stay executable or exec() fails.
                            val wasExec = file.canExecute()
                            file.writeText(text.replace(OLD_TERMUX_ROOT, textNew))
                            if (wasExec) file.setExecutable(true, false)
                        }
                    }
                } catch (_: Exception) { /* skip unreadable files silently */ }
            }
        if (patched > 0) Log.i(TAG, "patched $patched ELF path reference(s)")
    }

    private fun isElf(file: File): Boolean = try {
        FileInputStream(file).use { `in` ->
            val magic = ByteArray(4)
            val n = `in`.read(magic, 0, 4)
            n == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Replace every occurrence of [old] with [new] inside an ELF file, in place.
     *
     * Requires `old.size == new.size` ([new] must be a pure overwrite). Returns
     * the number of substitutions. Uses only Java 8 APIs and a manual read loop
     * — see the project's rule against Java 9+ APIs, which compile fine on the
     * host JDK and then throw `NoSuchMethodError` on device.
     *
     * ## 🔴 写回为什么必须是「临时文件 + 改名」
     *
     * 原来是 `FileOutputStream(file).write(bytes)`：**直接截断并原地重写**。
     * node 是 47 MB，这个窗口有几十毫秒，期间磁盘上是一个**半截文件** ——
     * 任何在这个窗口里 exec 它、或读到一半的进程，都会拿到「大小对得上、
     * 内容已经坏掉」的二进制。
     *
     * 临时文件 + `Files.move` 让写回变成全有或全无：目标要么是改前的完整文件，
     * 要么是改后的完整文件，**没有中间态**。同目录内的改名在同一文件系统上，
     * 不涉及拷贝。
     */
    private fun patchElfInPlace(file: File, old: ByteArray, new: ByteArray): Int {
        if (old.size != new.size) return 0
        val size = file.length().toInt()
        if (size <= 0 || size < old.size) return 0

        val bytes = ByteArray(size)
        FileInputStream(file).use { `in` ->
            var off = 0
            while (off < size) {
                val n = `in`.read(bytes, off, size - off)
                if (n <= 0) break
                off += n
            }
            // Short read ⇒ the file changed under us. Bail rather than write a
            // buffer with a zeroed tail over a live binary.
            if (off != size) return 0
        }

        var hits = 0
        var i = 0
        while (i <= size - old.size) {
            if (bytes[i] != old[0]) { i++; continue }
            var k = 1
            while (k < old.size && bytes[i + k] == old[k]) k++
            if (k == old.size) {
                System.arraycopy(new, 0, bytes, i, new.size)
                hits++
                i += old.size
            } else {
                i++
            }
        }
        if (hits == 0) return 0

        val wasExec = file.canExecute()
        val dir = file.absoluteFile.parentFile ?: return 0
        var tmp: File? = null
        try {
            // 临时名必须唯一：固定名字会让并发/残留进程写到同一个文件上。
            tmp = File.createTempFile(".${file.name}.", ".patched", dir)
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.flush()
                // fsync：close() 只把数据交给 OS，掉电会留下一个大小正确但内容全零的文件 ——
                // 对一个可执行文件来说与写坏无异。
                it.fd.sync()
            }
            if (wasExec) tmp.setExecutable(true, false)
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            tmp = null
        } catch (t: Throwable) {
            // 保留改前的完整文件，这是本函数这层保护的全部意义。
            Log.w("TERMUX", "重写 ${file.name} 失败，保留原文件: ${t.message}")
            return 0
        } finally {
            tmp?.delete()
        }
        return hits
    }

    companion object {
        private const val TAG = "TermuxBootstrap"

        /**
         * Termux's own prefix root, as baked into every shipped binary (27 bytes).
         *
         * Note this is the **root**, not `.../usr`: the bootstrap also hardcodes
         * `/data/data/com.termux/files/home` and `/data/data/com.termux/cache/apt`,
         * so anchoring on the root covers all of them with one rule.
         */
        private const val OLD_TERMUX_ROOT = "/data/data/com.termux/files"

        /** Plain-text files larger than this are left alone (they are data, not scripts). */
        private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

        /**
         * Refuse to buffer a binary bigger than this; keeps the peak allocation sane.
         *
         * 🔴 不能是原来的 16 MB：内置的 `node` 是 **47.4 MB**，超限就会被
         * [rewriteTermuxPaths] 静默跳过（它只做 `if (file.length() <= MAX_ELF_BYTES)`），
         * 于是 node 继续带着 `/data/data/com.termux/files/usr/lib` 的 RUNPATH 去读
         * com.termux 的私有目录 —— 链接器报 `library not found`，而根因是一个字节数。
         * 64 MB 给后续 node 版本留了余量；峰值内存是单次 64 MB 分配，可接受。
         */
        private const val MAX_ELF_BYTES = 64L * 1024 * 1024

        /** 内置 Node 运行时资产名（中性后缀，避免 AGP 按扩展名做格式推断）。 */
        private const val NODE_RUNTIME_ASSET = "node-runtime.bin"

        /**
         * 运行时版本标识，参与 [nodeMarker] 的文件名。
         *
         * ⚠️ **资产内容一变就必须改这里**（`-2`、`-3`…），否则已装机的机器会因为
         * marker 命中而跳过安装，拿到的是旧运行时且没有任何报错。
         */
        private const val NODE_VERSION = "26.4.0-2"

        /**
         * `node --version` 应当打印的字符串，[nodeIsUsable] 的判据。
         *
         * ⚠️ 与 [NODE_VERSION] **必须同步改**：那个是安装标记的版本，这个是资产
         * 里 node 自报的版本。资产一换，两个都得跟着走，否则 `nodeIsUsable()`
         * 会把刚铺好的新 node 判成「损坏」，然后每次启动都白重解包一遍。
         */
        private const val NODE_EXECUTABLE_VERSION = "v26.4.0"

        /**
         * `node --version` 的超时。
         *
         * 正常约 50 ms（真机实测）。给到 10 秒是因为校验发生在**启动路径**上，
         * 而它跑在一个可能已经被拖慢的机器上；超时即判为损坏并重解包，代价是
         * 一次约 15 秒的重装 —— 远小于把一个起不来的运行时封印成「已安装」。
         */
        private const val NODE_CHECK_TIMEOUT_MS = 10_000L

        /**
         * 镜像探测脚本的总超时。
         *
         * 脚本要依次试 19 个候选站，每个都带自己的 `--max-time`；
         * 整体给 90 秒 —— 足够跑完最坏情况（19 × 几个小请求），
         * 又不至于让一条 IO 线程被永久占住。
         */
        private const val PROBE_TIMEOUT_MS = 90_000L

        /**
         * prefix 的写锁。
         *
         * ## 🔴 为什么必须是 **companion**（进程级）而不是实例级
         *
         * [installNodeRuntime] / [rewriteTermuxPathsLocked] 会被**两个不同实例**
         * 上的两条路径并发调用：
         *
         * - `DshApplication.bootstrapTermux()`（`applicationScope`，IO 线程）
         * - `EngineManager.start()`（`lifecycleScope`，主线程调度到 IO）
         *
         * 两者都持有各自 `by lazy { TermuxBootstrap(context) }` 的实例，所以实例级
         * 锁挡不住它们。而这两条路径都会**原地重写同一个 47 MB 的 node 二进制**，
         * 交错写入的产物就是粉丝那个「文件大小对、ELF 头在、内容已坏」的 node。
         */
        private val prefixLock = ReentrantLock()
    }
}
