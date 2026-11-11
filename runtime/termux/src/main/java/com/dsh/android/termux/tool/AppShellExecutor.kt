package com.dsh.android.termux.tool

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The **app-uid** shell path — Go's `runShell` (C fork/exec), rebuilt on
 * `ProcessBuilder`.
 *
 * ## Why this class has to exist
 *
 * 🔴 **This is a regression fix, not a feature.** Until 2026-09-25 the app's
 * shell tools were completely dead on any device without Shizuku, and nothing in
 * 1,328 tests or the `BridgeDiffTest` differential noticed.
 *
 * The Go original wires shell in **two layers**, and only the second is what
 * "SetShellExecutor" sounds like it does (`mobilesdk/bridge.go:168`):
 *
 * ```go
 * builtin.ShellExecutor = func(command, workDir string, runInBackground bool) (…) {
 *     if pkgCmd := extractPkgCommand(command); pkgCmd != "" { … }   // pkg → pkgmanager
 *     if runInBackground { … }                                     // background jobs
 *     if shizukuExec != nil && needsShellUid(command) { … }         // ← SetShellExecutor sets THIS
 *     return runShell(command, workDir, callEnv)                    // ← app uid: ALWAYS reachable
 * }
 * ```
 *
 * `SetShellExecutor` assigns the **global `shizukuExec`**, not the executor
 * itself. The executor is installed during `NewBooBridge` and its final
 * statement is an unconditional app-uid `fork/exec`. So on stock Android — the
 * common case, since Shizuku needs a separate install and a re-grant after every
 * reboot — the Go engine ran every command as the app user and everything
 * worked: Termux's `python`/`node`/`git`/`bash` live in app-private `$PREFIX`
 * and *cannot* be run by uid=2000 anyway.
 *
 * ## Two failure modes in one bug
 *
 * The port collapsed the two layers into one and gated the single installer on
 * Shizuku:
 *
 * ```kotlin
 * private fun injectShellExecutor(b: BooBridge) {
 *     if (!ShizukuManager.isAvailable()) return   // ← no Shizuku ⇒ nothing installed
 *     b.setShellExecutor(…)                       //   ⇒ ShellHooks.available == false
 * }
 * ```
 *
 * So `ShellHooks.executor == null` made `BashTool` throw
 * `shell execution is not available: no ShellExecutor is installed` — which the
 * model then read as a configuration instruction, wasting a turn explaining a
 * bug to the user. And because this executor is also the only place
 * `LD_PRELOAD` is assembled, **the `libboosandbox.so` write gate (layer 2 of the
 * three-layer permission model) was silently dead too** — `read-only` still held
 * via `access.Confine`, but a shell write to `/sdcard` was unconfined.
 *
 * ## Contract (matches Go's `runShell`)
 *
 *  * Runs `/system/bin/sh -c <command>` as the app uid, with `workDir` as cwd.
 *  * Injects the Termux environment so `$PREFIX` binaries resolve.
 *  * Captures stdout/stderr on **two** threads — a single-threaded
 *    read-then-wait deadlocks as soon as either pipe fills its buffer.
 *
 * ⚠️ **`error != null` means the command could not be *started***, matching Go:
 * a non-zero exit is a normal result (`exitCode`), not an error. Conflating the
 * two would turn every failing command into a tool-level exception and lose its
 * output — Go deliberately returns it as a string with the exit code inline.
 */
object AppShellExecutor {

    /** Go: `bashWaitDelay`-adjacent; no per-command timeout on this path. */
    private const val DRAIN_GRACE_MILLIS = 2_000L

    /**
     * Builds the executor closure. `env` is the full child environment
     * (Termux vars + sandbox posture), computed fresh **per call** so a
     * mid-session permission switch takes effect on the very next command.
     */
    fun executor(
        workDirDefault: String,
        envProvider: () -> Map<String, String>,
    ): ShellHooks.Executor = ShellHooks.Executor { command, workDir, runInBackground ->
        run(
            command = command,
            workDir = workDir.ifEmpty { workDirDefault },
            env = envProvider(),
            runInBackground = runInBackground,
        )
    }

    /** @see AppShellExecutor */
    fun run(
        command: String,
        workDir: String,
        env: Map<String, String>,
        runInBackground: Boolean,
    ): ShellHooks.Result {
        if (runInBackground) {
            // Go routes background commands to `runShellBg` (a detached fork with
            // an output fd the jobs manager polls). This port has no jobs manager
            // on this path, so the honest reduction is to report that rather than
            // silently run it synchronously and claim it is backgrounded — that
            // would make `bash_output(job_id=…)` address a job that never existed.
            return ShellHooks.Result(
                stdout = "",
                stderr = "",
                exitCode = -1,
                error = "background execution is not available in this context",
            )
        }

        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        // ⚠️ `redirectErrorStream(false)` — stdout and stderr are captured
        // separately so BashTool can frame stderr as `stderr:\n…` exactly like
        // Go. Merging them would silently reorder the combined output.
        pb.redirectErrorStream(false)
        if (workDir.isNotEmpty()) pb.directory(File(workDir))
        pb.environment().putAll(env)

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            // "could not start" — matches Go's `err` return from `cmd.Run()`.
            return ShellHooks.Result("", "", -1, t.message ?: t.toString())
        }

        // ⚠️ Two threads, not one: `readText()` on stdout then stderr deadlocks
        // when the child fills the stderr pipe while we are still blocked on
        // stdout (classic 64 KB pipe-buffer deadlock). `EnvironmentProbe` carries
        // the same note for the same reason.
        val pool = Executors.newFixedThreadPool(2)
        val outF = pool.submit<String> { proc.inputStream.readBytes().toString(Charsets.UTF_8) }
        val errF = pool.submit<String> { proc.errorStream.readBytes().toString(Charsets.UTF_8) }

        val stdout: String
        val stderr: String
        val exit: Int
        try {
            // No timeout on this path (Go's foreground timeout is a bash-tool
            // concern configured above this layer). `waitFor()` is safe here
            // because both pipes are being drained concurrently.
            exit = proc.waitFor()
            stdout = outF.get()
            stderr = errF.get()
        } catch (t: Throwable) {
            proc.destroyForcibly()
            pool.shutdownNow()
            return ShellHooks.Result("", "", -1, t.message ?: t.toString())
        } finally {
            pool.shutdown()
            // Best-effort; the process is already reaped by `waitFor`.
            if (!pool.awaitTermination(DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow()
            }
        }

        return ShellHooks.Result(stdout = stdout, stderr = stderr, exitCode = exit)
    }

    /**
     * Go's `needsShellUid` (`mobilesdk/bridge.go:1363`) — the routing predicate
     * that decides whether a command needs uid=2000.
     *
     * ⚠️ **Reproduced verbatim, including the fact that it only inspects
     * `strings.Fields(cmd)[0]`.** A leading `cd /x && pm install …` is therefore
     * *not* routed to Shizuku — that is the original's behaviour, not an
     * oversight to "fix" here; changing it would route commands differently than
     * the reference implementation.
     *
     * ⚠️ `strings.Fields` splits on **runs** of whitespace and drops empties —
     * `split(" ")` would yield `""` for a leading space and misroute.
     *
     * ## 🔴 Deliberate deviation from Go: identity probes
     *
     * Go's table has **no** `id` / `whoami`, so both run as the app uid and
     * report `uid=10319(u0_a319)`. That is *correct as routing*, but it is a
     * trap for the model: the system prompt states the app holds uid=2000
     * (`ConfigAccessors.DEFAULT_SYSTEM_PROMPT`), so a model that self-checks
     * with the obvious `id` sees a contradiction and reports "I have no
     * uid=2000 permission" to the user — even while `pm uninstall` (which
     * demands `DELETE_PACKAGES`) demonstrably works.
     *
     * Routing them makes `id`/`whoami` a truthful probe of *whether the
     * privileged route is available*: with Shizuku granted they report
     * `uid=2000(shell)`; with it absent or revoked they still fall back to the
     * app uid, so the answer stays honest in both directions. Both are
     * read-only, so routing them costs nothing.
     *
     * ⚠️ This is a product decision, not a port bug — `needsShellUid` is a
     * Kotlin-side helper that `BooBridge` consults per command; it is **not**
     * part of the 26-method diff surface, so the Go oracle is unaffected.
     *
     * ## 🔴 首词匹配对身份探针**不够**（2026-09-27 真机二次实测）
     *
     * 上面那条改完之后用户再测，`id` 依然报 `uid=10319(u0_a319)`。logcat 里
     * 模型**实际**发出的命令是：
     *
     * ```
     * echo "UID: $(id -u)"; echo "GID: $(id -g)"; echo "whoami: $(whoami)"
     * ```
     *
     * 首词是 **`echo`**，不是 `id`。`fields[0]` 判定为 false，整条命令照旧回落
     * app uid —— 而 `$(id -u)` 是在**那个** shell 里做命令替换的，于是永远
     * 10319。换句话说：只要模型把探针包进任何 shell 包装（`echo "…$(id)…"`、
     * `;`、`&&`、管道、反引号），首词就不再是探针本身。
     *
     * 所以身份探针改走 [isIdentityProbeOnly]：按 shell 分隔符与命令替换切开，
     * 要求**每一段**的首词都是「不写盘」的无害词，且至少一段真的是探针。
     *
     * ## 🔴 为什么必须是「整条无害」而不是「任一段命中」
     *
     * uid=2000 那条路由会**主动剥离 `LD_PRELOAD`**（`BooBridge.kt:376`）—— 这是
     * 必需的，因为沙箱 `.so` 是 app 私有路径、uid=2000 读不到。于是**任何**被路由
     * 过去的命令都绕开了第二层沙箱。若判据写成「任一段以 `id` 开头就整条路由」，
     * 那么 read-only 档下
     *
     * ```
     * echo "$(id -u)"; touch /sdcard/evil
     * ```
     *
     * 会真的把 `/sdcard/evil` 写出来。所以判据被收紧成两个合取条件：
     *
     *  1. 整条命令**无写型重定向**（`>`；`2>&1` 这类 fd 复制先剔除再判）；
     *  2. 切分后**每一段**首词都在 [SAFE_PROBE_WRAPPERS] 里（只有 `id`/`whoami`
     *     与纯 stdout 输出的内建），且至少一段是 `id`/`whoami`。
     *
     * 满足这两条的命令除了往 stdout 打印什么都不做，因此剥离沙箱没有代价。
     * 任何「不认识」的词（`touch`、`cp`、`sort -o`、`cd`…）都会让整条回落 app uid，
     * 沙箱照常生效 —— 宁可误拒（探针以 app uid 跑、如实报 10319），不可误放。
     *
     * ⚠️ **只放宽 `id`/`whoami`，不放宽 `pm`/`am` 等**，这是有意为之：
     * `cd $PREFIX && pm list packages` 若整条改送 uid=2000，`cd` 会因 uid=2000
     * 读不了 app 私有的 `$PREFIX` 而失败，`&&` 随即短路掉 `pm` —— 比「以 app uid
     * 跑 pm 并报权限不足」更糟（命令根本没执行）。身份探针没有这个副作用：
     * 参与判定的词全是 sh 内建或 `/system/bin` 下的只读命令，uid=2000 下都可用。
     */
    fun needsShellUid(cmd: String): Boolean {
        val fields = GoFields.fields(cmd)
        if (fields.isEmpty()) return false
        return when (fields[0]) {
            "pm", "am", "dumpsys", "settings", "screencap",
            "input", "wm", "cmd", "service", "content",
            "appops", "device_config", "monkey", "ime",
            "media", "netd", "bugreport", "getevent", "sendevent",
            -> true
            // See "Deliberate deviation from Go" above — identity probes answer
            // "do I actually have uid=2000 right now?", which is what both the
            // model and the user are really asking. Not in Go's table.
            else -> isIdentityProbeOnly(cmd)
        }
    }

    /** The commands whose output the model self-checks its identity against. */
    private val IDENTITY_PROBES = setOf("id", "whoami")

    /**
     * Words allowed to *wrap* an identity probe: commands that can only ever write
     * to **stdout**, and have no parameter that writes a file.
     *
     * 🔴 The admission test is not "is it read-only" (ShellSafe's question) but
     * "can it write a file *by itself*". Hence `sed`, `sort`, `awk`, `find`,
     * `tee`, `xargs` are all **excluded** even though they are ordinary read-only
     * filters — `sed -i`, `sort -o`, `awk '{print > f}'`, `find -delete`,
     * `xargs -I{} sh -c …` each write without any `>`. The commands below have no
     * such flag; every write they could perform must go through a redirection,
     * which [isIdentityProbeOnly] rejects separately. `cd` is excluded too, but
     * for a different reason — see the KDoc on [needsShellUid].
     *
     * ⚠️ Three plausible-looking entries are **deliberately absent** because each
     * hides a write behind a flag: `hostname NEW` renames the device,
     * `date -s` sets the clock, `file -C` compiles `.mgc` files. Read-only for
     * *ordinary* use is not the bar.
     */
    private val SAFE_PROBE_WRAPPERS = IDENTITY_PROBES + setOf(
        // shell builtins
        "echo", "printf", "true", "false", ":",
        // /system/bin and toybox probes with no file-writing flag
        "uname", "pwd", "printenv", "getprop",
        "cat", "head", "tail", "ls", "wc", "stat",
        "df", "du", "ps", "readlink", "realpath", "basename", "dirname",
    )

    /**
     * Splits on shell separators and the `$(` substitution opener so that each
     * resulting segment's **first** word is a command that will actually run.
     *
     * ⚠️ `)` is deliberately **not** a separator. It would turn `echo "a)b"` into
     * the bogus segment `b"`, and `b"` is not a wrapper word ⇒ the whole command
     * would fall back to the app uid for no reason. Instead, the *trailing* `)`
     * left glued to a substitution's last word is stripped in
     * [isIdentityProbeOnly].
     */
    private val SHELL_WRAPPER_SPLIT = Regex("[;|&\\n`]|\\$\\(")

    /** `2>&1`, `1>&2`, `>&2` — a file-descriptor copy, not a write redirection. */
    private val FD_DUP = Regex("[0-9]*>&[0-9]*")

    /** Glue left on a word by quoting or by a substitution's closing paren. */
    private val QUOTE_GLUE = charArrayOf(')', '"', '\'', '`')

    /**
     * True when [cmd] provably does nothing but write to stdout, and at least one
     * of its commands is an [IDENTITY_PROBES] word. See the KDoc on
     * [needsShellUid] for why both halves are required.
     *
     * The steps below are order-sensitive; each one exists because of a concrete
     * command that misjudged without it:
     *
     *  1. [FD_DUP] is stripped **before** the `>` test and **before** splitting.
     *     Otherwise the `&` inside `2>&1` splits as a separator and manufactures a
     *     bogus segment (`echo $(id -u) 2>&1` ⇒ `…`, `1`).
     *  2. Any remaining `>` rejects — that is the only way a whitelisted command
     *     could still write a file.
     *  3. Each segment's first word has [QUOTE_GLUE] trimmed, because quoting and a
     *     substitution's closing paren stay glued to the word:
     *     `echo "id: $(id)"` ⇒ the last segment's first word is `id)"`, not `id`.
     *  4. A segment that is *whitespace only* is skipped (a leading/trailing
     *     separator), but a segment whose first word trims to **empty** rejects the
     *     whole command. Skipping it instead would let `"" touch /sdcard/evil`
     *     through — after expansion that word disappears and `touch` becomes the
     *     command, so the word's real command is never the one we inspected.
     */
    private fun isIdentityProbeOnly(cmd: String): Boolean {
        val norm = FD_DUP.replace(cmd, "")
        if (norm.contains('>')) return false
        var sawProbe = false
        for (segment in SHELL_WRAPPER_SPLIT.split(norm)) {
            val f = GoFields.fields(segment)
            if (f.isEmpty()) continue          // pure separator, no command
            val head = f[0].trim(*QUOTE_GLUE)
            if (head.isEmpty()) return false   // e.g. `"" touch x` — see step 4
            if (head !in SAFE_PROBE_WRAPPERS) return false
            if (head in IDENTITY_PROBES) sawProbe = true
        }
        return sawProbe
    }
}

/**
 * Go's `strings.Fields`, which splits on runs of Unicode whitespace and removes
 * empty tokens.
 *
 * ⚠️ Not `split(" ")`: Go collapses runs, so `"  pm   install "` yields
 * `["pm","install"]` while `split(" ")` yields `["","pm","","","install"]` and
 * would make `needsShellUid` inspect an empty first token.
 */
internal object GoFields {
    fun fields(s: String): List<String> =
        if (s.isBlank()) emptyList() else s.trim().split(Regex("\\s+"))
}
