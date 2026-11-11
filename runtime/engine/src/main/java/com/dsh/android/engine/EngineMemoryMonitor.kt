package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import com.dsh.android.log.EventLog

/** 拥有 RSS 采样线程；引擎代际与重启决定通过窄端口提供。 */
internal class EngineMemoryMonitor(
    private val isCurrent: (Process) -> Boolean,
    private val isBusy: () -> Boolean,
    private val requestRestart: () -> Unit,
) {
    @Volatile private var heapWatch: Thread? = null
    /** 停止堆水位监控。幂等；由 [stop] 调用。 */
    fun stop() {
        val t = heapWatch ?: return
        heapWatch = null
        t.interrupt()
    }

    /**
     * 启动堆水位监控。
     *
     * ⚠️ **在引擎就绪之后才启动**：`process` 还没起来时读不到 `/proc`。
     * ⚠️ 幂等：已在跑就先停掉旧的（重启引擎时会有上一代的线程）。
     */
    fun start(proc: Process, limitMb: Int, restartRatio: Double) {
        stop()
        // 水位 = 上限的 [heapRestartRatio]。留出余量是因为从"判定"到"重启完成"
        // 之间引擎还在涨，而重启本身要 10+ 秒。
        val triggerMb = (limitMb * restartRatio).toInt()
        val t = Thread {
            var warned = false
            /*
             * 上一次采样的（RSS, 时刻），用来算**增长速率**。
             *
             * ## 为什么必须记速率（真机取证，2026-10-27）
             *
             * 真机数据表明这是一个**线性**泄漏：10 次崩溃的运行时长是
             * `11.1h / 7.8h / 7.7h / 7.8h / 7.8h / 7.7h / 7.8h`，
             * 折算约 **130 MB/小时**。
             *
             * 但"约"这个字是问题所在：只看崩溃时的堆大小，无法区分
             * 「恒定 130MB/h 线性涨」和「某几个操作各泄漏一大块」。而这两种
             * 的修法完全不同 —— 前者要找常驻引用，后者要找特定触发点。
             *
             * 记下速率之后，`engine.heap` 事件里就有了一条**时间序列**：
             * 速率忽高忽低 ⇒ 与操作相关（可以对着 events.jsonl 的时间点找
             * 当时在干什么）；速率恒定 ⇒ 常驻引用，与用户操作无关。
             *
             * ⚠️ 采样**必须便宜**：这里只在每 [HEAP_WATCH_INTERVAL_MS] 记一行
             *    I 级日志（3 分钟一条，一天 480 条，远小于 1 MB 的日志上限）。
             *    不要改成每秒采样 —— 那会把日志冲爆，反而更难查。
             */
            var prevMb = -1
            var prevAt = 0L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(HEAP_WATCH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // 引擎已经换了/停了就别再看这个 proc。
                if (!isCurrent(proc)) return@Thread
                val rssMb = readRssMb(proc)
                if (rssMb <= 0) continue
                // ── 速率采样（只在两次都有效时算）──
                val nowMs = System.currentTimeMillis()
                if (prevMb > 0 && prevAt > 0) {
                    val hours = (nowMs - prevAt) / 3_600_000.0
                    if (hours > 0.01) {
                        val rate = (rssMb - prevMb) / hours
                        Log.i(
                            TAG,
                            "引擎堆采样: ${rssMb}MB（较上次 ${if (rssMb >= prevMb) "+" else ""}${rssMb - prevMb}MB，" +
                                "约 ${rate.toInt()}MB/小时，上限 $limitMb MB）",
                        )
                    }
                }
                prevMb = rssMb
                prevAt = nowMs
                if (rssMb >= triggerMb) {
                    /*
                     * 到水位了。**只重启一次，不要反复触发。**
                     *
                     * 用 `warned` 而不是直接 return：重启是异步的（`restart()`
                     * 走状态机），如果这里立刻退出，重启失败时就再没人看水位了。
                     * 记一次日志后保持监控，但不再重复触发 —— `restart` 成功后
                     * `startHeapWatch` 会起一个新线程（旧的被 interrupt 掉）。
                     */
                    if (!warned) {
                        /*
                         * ⚠️ **AI 正在跑就先别重启。**
                         *
                         * `requestRestart` 走 `State.Dead` ⇒ `MainActivity` 会
                         * `web.loadUrl(...)` **重载整个页面**。对话内容在引擎侧
                         * 不会丢，但用户输入框里**还没发出去的文字会没**，
                         * 而且正在流式输出的回答会被打断。
                         *
                         * 所以：正在生成时只记一次日志、把 `warned` 留 false，
                         * 下一轮（3 分钟后）再看。生成通常几十秒到几分钟，
                         * 水位余量（15% of 3072 ≈ 460 MB ≈ 3.5 小时）
                         * 足够等它跑完。
                         *
                         * 读不到状态（`currentStatus()` 为 null，比如引擎刚起来
                         * 页面还没上报）时按**空闲**处理 —— 不能因为读不到就一直
                         * 不重启，那会让监控形同虚设。
                         */
                        val busy = isBusy()
                        if (busy) {
                            Log.i(
                                TAG,
                                "堆水位 ${rssMb}MB 已过 $triggerMb MB，但 AI 正在运行，" +
                                    "等它跑完再重启（对话内容在引擎侧不会丢，但不能打断生成）",
                            )
                        } else {
                            warned = true
                            Log.w(
                                TAG,
                                "引擎堆水位 ${rssMb}MB 已达上限的 " +
                                    "${(restartRatio * 100).toInt()}%（$triggerMb / $limitMb MB），" +
                                    "主动重启以免用户撞上 OOM 闪崩（真机实测约 130MB/小时线性增长）",
                            )
                            EventLog.record(
                                "engine.heap-high",
                                mapOf("rssMb" to rssMb.toString(), "triggerMb" to triggerMb.toString()),
                            )
                            // 在主线程之外发起重启是安全的：restart 内部自己切线程。
                            runCatching { requestRestart() }
                        }
                    }
                } else if (rssMb < triggerMb / 2) {
                    // 掉回一半以下说明水位过去了（比如用户清空了长会话），允许再次触发。
                    warned = false
                }
            }
        }
        t.isDaemon = true
        t.name = "dsh-heap-watch"
        heapWatch = t
        t.start()
        Log.i(
            TAG,
            "堆水位监控已启动：上限 ${limitMb}MB，水位 ${triggerMb}MB，" +
                "每 ${HEAP_WATCH_INTERVAL_MS / 60_000} 分钟检查一次",
        )
    }

    /**
     * 读引擎进程的 `VmRSS`（MB）；读不到给 -1。
     *
     * ⚠️ **不能用 `proc.pid()`**：那是 Java 9 的 API，Android 的 `java.lang.Process`
     *   没有它（`Unresolved reference: pid`，已实测编译失败）。Android 上
     *   `ProcessImpl` 有一个私有 `int pid` 字段，只能用反射读。
     *
     * ⚠️ 反射失败时**必须安静返回 -1**，不能抛：这条路径跑在监控线程上，
     *   而监控本身是锦上添花 —— 读不到就退化成"不监控"，绝不能因为它影响引擎。
     *   （不同 ROM / 未来 Android 版本把 `ProcessImpl` 改名或换实现都可能发生。）
     */
    private fun readRssMb(proc: Process): Int = runCatching {
        val pidField = proc.javaClass.getDeclaredField("pid").apply { isAccessible = true }
        val pid = pidField.getInt(proc)
        if (pid <= 0) return@runCatching -1
        val status = File("/proc/$pid/status").readText()
        val kb = Regex("VmRSS:\\s+(\\d+)\\s+kB").find(status)
            ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return@runCatching -1
        (kb / 1024L).toInt()
    }.getOrDefault(-1)
}
