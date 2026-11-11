package com.dsh.android.termux

import com.dsh.android.log.DshLog as Log
import com.dsh.android.termux.util.AtomicTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** 镜像状态、配置和显式测速；复用 MirrorProbe，不在启动时增加测速。 */
internal class TermuxMirrors(
    private val dataDir: String,
    private val prefixDir: File,
    private val homeDir: File,
) {
    private val probeTtlMs = 7L * 24 * 60 * 60 * 1000

    fun ensureMirrorsConfigured() {
        if (!prefixDir.exists()) return
        try {
            applyMirrorConfigs(loadMirrors())
        } catch (e: Exception) {
            Log.w("TERMUX", "ensure mirrors: ${e.message}")
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

    fun writeSourcesList() {
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
    fun applyMirrorConfigs(mirrors: Map<String, String>) {
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


    private companion object { const val PROBE_TIMEOUT_MS = 90_000L }
}
