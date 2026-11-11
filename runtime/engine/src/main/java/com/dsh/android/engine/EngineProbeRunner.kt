package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import com.dsh.android.log.RescueBoot
import com.dsh.android.log.BootState
import com.dsh.android.engine.EngineManager.ProbeResult
import java.util.concurrent.TimeUnit

/** 每次调用独占临时进程和读取线程，任何出口都结束本次资源。 */
internal class EngineProbeRunner {
    class DumpResult(val exitCode: Int, val stdout: String, val stderr: String)
    /**
     * 用给定 profile **真跑一次引擎**，看它能不能到「打印 URL」那一步，然后立刻杀掉。
     *
     * ## 这是本方案唯一可信的验证手段
     *
     * `--dump-config`（[runDumpConfig]）只解析配置、**不执行插件的 `apply()`**，
     * 而真机上杀死引擎的异常正是在 `apply()` 期抛的 —— 8 次真实启动失败里，
     * `dump-config` 退出码全是 0、4 个 required 条目全在。所以判据只能是真跑。
     * 这就是不变量 I6。
     *
     * ## 三条要害
     *
     * 1. **端口必须传 0**：正在运行的引擎占着 3080，探测进程再抢它就会得到
     *    `EADDRINUSE` → `startup failed: 2 required plugins did not activate`，
     *    把「插件坏」误报成「端口占用」。`--port 0` 让内核挑空闲端口
     *    （真机实测可行，A2 记录里那次打的是 44057）。
     * 2. **必须杀掉进程**：探测完不杀就会留一个占着端口、跑着插件的引擎。
     *    真机验证过 `--port 0` 起完再 kill，`ps -A | grep -c bin.js` = 0。
     * 3. **超时是 60 秒而不是 [READY_TIMEOUT_MS] 的 120 秒**：失败路径实测
     *    17~22 秒就返回了，60 秒足够，且二分会跑很多次，120 秒会让总时长失控。
     *
     * @param spec 从真实启动快照派生的 profile、overlay、目录与环境，端口为 0。
     * @param timeoutMs 单次上限。
     * @return null = 压根跑不起来（node 缺失等）—— 与「跑起来但失败」是两回事，
     *   调用方必须区分：前者不是插件问题。
     */
    fun probeBoot(
        spec: EngineLaunchSpec,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): ProbeResult? {
        if (!spec.nodeBinary.isFile || !spec.entryPoint.isFile) return null
        val profile = spec.profile
        val cmd = spec.arguments
        var ownedProcess: Process? = null
        var ownedReader: Thread? = null
        Log.i(TAG, "试启动（$profile）: ${cmd.joinToString(" ")}")
        return try {
            val proc = spec.processBuilder().start().also { ownedProcess = it }
            val lines = java.util.Collections.synchronizedList(ArrayList<String>())
            // ⚠️ 用 `AtomicBoolean` 而不是 `@Volatile var`：Kotlin **不允许**把
            //   `@Volatile` 用在局部变量上（它只作用于有幕后字段的属性），
            //   而这里必须保证读取线程写、主线程读之间有可见性 ——
            //   否则可能出现"引擎明明打印了 URL，主线程永远看不到 ready"，
            //   症状是一次**假失败**，二分法据此摘掉一个好插件。
            val ready = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        lines.add(line)
                        if (URL_PATTERN.containsMatchIn(line)) ready.set(true)
                    }
                } catch (t: Throwable) {
                    // 被 destroyForcibly 打断是预期路径，见 [runDumpConfig] 的同款说明
                    Log.w(TAG, "试启动输出读取结束: ${t.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; ownedReader = this; start() }

            // ⚠️ **不能只 `waitFor(timeoutMs)` 等它退出。** 引擎是个**常驻 HTTP 服务**：
            //   成功时它打印 URL 之后**永不退出**，`waitFor` 必然一路等到超时 ——
            //   于是每次*成功*的探测都白烧满 60 秒，而 L1 的二分法上限是 3 分钟总时长，
            //   6 轮根本跑不完（第一轮就吃掉 60 秒）。
            //
            //   所以判据是"**三者先到先算**"：
            //     - 打印了 URL ⇒ 成功，立刻杀掉（这就是"能起来"的定义）
            //     - 进程自己退出了 ⇒ 失败，收集输出后返回
            //     - 到点了 ⇒ 超时，杀掉（失败路径实测 17~22 秒就该有结论了）
            //
            //   轮询粒度 100ms：失败实测 17~22 秒，粒度对结论毫无影响；成功路径则
            //   决定"省多少时间"，100ms 足够细。
            val deadline = System.currentTimeMillis() + timeoutMs
            var exited = false
            while (System.currentTimeMillis() < deadline) {
                if (ready.get()) break
                if (!proc.isAlive) {
                    exited = true
                    break
                }
                Thread.sleep(100)
            }
            // 成功 ⇒ 立刻杀；失败/超时 ⇒ 杀掉（此时它多半已经死了，destroyForcibly 幂等）
            val ok = ready.get()
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor(5, TimeUnit.SECONDS)
            }
            // 等读取线程把管道里剩下的输出收干净，否则 tail 会缺最后几行 ——
            // 而失败原因恰恰常常在最后几行。
            reader.join(2000)

            Log.i(
                TAG,
                if (ok) "试启动成功（已结束探测进程）"
                else if (!exited) "试启动超时（${timeoutMs} ms），已强制结束"
                else "试启动失败（进程已退出）",
            )

            val snapshot = synchronized(lines) { ArrayList(lines) }
            val exitCode = if (exited) runCatching { proc.exitValue() }.getOrNull() else null
            val parsed = RescueBoot.parseFailure(
                bootId = BootState.bootId,
                exitCode = exitCode,
                tailLines = snapshot,
                at = System.currentTimeMillis(),
            )
            ProbeResult(
                ready = ok,
                exitCode = exitCode,
                requiredDidNotActivate = parsed.requiredDidNotActivate,
                tail = parsed.tail,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "试启动执行失败: ${t.message}")
            null
        } finally {
            finish(ownedProcess, listOfNotNull(ownedReader))
        }
    }

    /**
     * 跑一次 `--dump-config` 并收集输出。
     *
     * 调用方保留安装前体检的既有环境策略：继承宿主环境并覆盖基础 Termux 变量，
     * 不传 overlay。运行器只拥有临时进程和输出流，不负责补齐真实启动的环境。
     *
     * @return null = 压根跑不起来（node 缺失等），调用方应放行而不是误判成 profile 坏了。
     */
    fun runDumpConfig(pb: ProcessBuilder, timeoutMs: Long = PREFLIGHT_TIMEOUT_MS): DumpResult? {
        var ownedProcess: Process? = null
        val readers = ArrayList<Thread>()
        return try {
            val proc = pb.start().also { ownedProcess = it }
            // ⚠️ 必须**并发**读走两个流：dump 的输出有 1500+ 行（真机实测），
            // 单线程先 readText() 一个流会让另一个流的 64KB 管道缓冲写满，
            // 引擎卡在 write 上永远不退出 —— 体检就成了新的挂死点。
            //
            // ⚠️ **两个线程体都必须自己 try/catch**，不能裸奔。
            //
            // 上面那条超时分支会 `destroyForcibly()` 杀掉引擎，此刻正阻塞在
            // `read()` 上的这条线程会被内核中断，`forEachLine` 抛
            // `java.io.InterruptedIOException: read interrupted`（真机堆栈：
            // `Linux.readBytes` ← `BufferedReader.readLine` ←
            // `kotlin.io.LinesSequence$iterator$1.hasNext`）。
            //
            // 这是**预期路径**（超时本来就该杀进程），不是故障；但裸线程里没人接
            // 这个异常，它会一路冒到 JVM 的默认未捕获处理器，被 `CrashRecorder`
            // 记成一份 `uncaught-exception` 崩溃现场 —— 于是「体检超时」在日志里
            // 伪装成「未捕获异常」，把排查方向带偏。
            // 真机证据：`20261013-221218-uncaught-exception.txt`（vivo V2313A，
            // `thread: Thread-9`，栈底就是这里）。
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                } catch (t: Throwable) {
                    // 被 destroyForcibly 打断是正常的，只记一句不抛。
                    Log.w(TAG, "体检 stdout 读取结束: ${t.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; readers += this; start() }
            val errThread = Thread {
                try {
                    proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
                } catch (t: Throwable) {
                    Log.w(TAG, "体检 stderr 读取结束: ${t.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; readers += this; start() }
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                outThread.join(1000)
                errThread.join(1000)
                Log.w(TAG, "启动前体检超时（${timeoutMs} ms），跳过")
                return null
            }
            outThread.join(2000)
            errThread.join(2000)
            DumpResult(proc.exitValue(), out.toString(), err.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "启动前体检执行失败，跳过: ${t.message}")
            null
        } finally {
            finish(ownedProcess, readers)
        }
    }

    private fun finish(proc: Process?, readers: List<Thread>) {
        if (proc == null) return
        runCatching {
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor(5, TimeUnit.SECONDS)
            }
        }
        listOf(proc.outputStream, proc.inputStream, proc.errorStream).forEach { stream ->
            runCatching { stream.close() }
        }
        readers.forEach { reader -> runCatching { reader.join(2000) } }
    }
}
