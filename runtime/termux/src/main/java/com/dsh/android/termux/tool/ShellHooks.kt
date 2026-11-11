package com.dsh.android.termux.tool

/**
 * The shell-execution seam, mirroring the package-level hooks in Go's
 * `internal/tool/builtin`.
 *
 * ## Why globals instead of constructor injection
 *
 * Go wires these once in `mobilesdk.NewBooBridge` (`builtin.ShellExecutor = …`)
 * and every tool reads the package variable **at call time**. The Kotlin port
 * keeps that shape deliberately: the router consults the live permission preset
 * on every call, so a per-instance snapshot would freeze the sandbox posture at
 * construction and a mid-session permission switch would not take effect.
 *
 * ⚠️ `BooBridge` must install these before any turn runs. A tool that finds
 * [available] false must fail with a clear message rather than silently
 * pretending the command succeeded.
 */
object ShellHooks {

    /** Go: `builtin.ShellExecutor`. */
    fun interface Executor {
        /**
         * @param runInBackground when true the command is *started* and
         *   [Result.stdout] carries the human-readable "started" line rather
         *   than real output.
         */
        fun run(command: String, workDir: String, runInBackground: Boolean): Result
    }

    /** Go's four-value return from `ShellExecutor`. */
    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val error: String? = null,
    )

    /** Go: `builtin.ShellBgExecutor`. */
    fun interface BgExecutor {
        fun start(command: String, workDir: String): BgStart
    }

    data class BgStart(val pid: Int, val outFd: Int, val error: String? = null)

    /** Go: `builtin.ShellOutputReader`. */
    fun interface OutputReader {
        fun read(pid: Int): String
    }

    /** Go: `builtin.ShellWaiter`. */
    fun interface Waiter {
        fun waitFor(pid: Int, timeoutSec: Int): Int
    }

    /** Go: `builtin.ShellKiller`. */
    fun interface Killer {
        fun kill(pid: Int): Boolean
    }

    /** Go: `builtin.ShellAvailable`. */
    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var executor: Executor? = null
        private set

    @Volatile
    var bgExecutor: BgExecutor? = null
        private set

    @Volatile
    var outputReader: OutputReader? = null
        private set

    @Volatile
    var waiter: Waiter? = null
        private set

    @Volatile
    var killer: Killer? = null
        private set

    /**
     * Installs the whole set in one step.
     *
     * ⚠️ Deliberately one call rather than six assignments: the background-job
     * tools read [bgExecutor] and [outputReader] as a pair, and a turn that
     * started between two separate assignments could observe one installed and
     * the other still null.
     */
    fun install(
        executor: Executor?,
        bgExecutor: BgExecutor? = null,
        outputReader: OutputReader? = null,
        waiter: Waiter? = null,
        killer: Killer? = null,
    ) {
        this.executor = executor
        this.bgExecutor = bgExecutor
        this.outputReader = outputReader
        this.waiter = waiter
        this.killer = killer
        this.available = executor != null
    }

    fun reset() {
        install(null)
    }
}
