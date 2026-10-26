package com.dsh.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 系统键盘是否弹着 —— 给悬浮球用的**低频**探针。
 *
 * ## 为什么不能直接读 IME insets
 *
 * `TYPE_APPLICATION_OVERLAY` 不在 `adjustResize` 的适用范围里，API 29 上也没有
 * `WindowInsets.Type.ime()` 可用（见 [FloatingBubble.applyImeHeight] 的注释）。
 * 悬浮窗想「知道别的 App 的键盘弹没弹」，唯一可靠来源是 `dumpsys input_method`
 * 里的 `mInputShown`。
 *
 * ## 为什么必须低频
 *
 * 实测单次 `dumpsys input_method` 约 **95–150 ms**（华为 CDY-TN90 / Android 10）。
 * 每 300 ms 跑一次就是 30%–50% 的常驻 CPU —— 这是**不可接受**的。所以：
 *
 * - 只在**球停稳**且**确实有遮挡风险**时才开始探（[start] 由调用方择机调用）；
 * - 间隔取 [POLL_INTERVAL_MS]（2 s），比「键盘动画」慢得多，但键盘会一直弹着，
 *   晚 2 秒飞上去用户也完全能接受；
 * - 一旦检测到弹起就**立刻停探**（[stop]），不在键盘弹着期间继续烧 CPU。
 *
 * ## 为什么不用 AccessibilityService
 *
 * 无障碍服务能直接收到 IME 窗口事件，是「最正确」的做法。但那要求用户额外去
 * 设置里授权无障碍，权限很重、且各家 ROM 的保活策略不同 —— 为了一个「球飞一下」
 * 的趣味功能不值得。这里用已有的 uid 2000 / Shizuku 通道（`dumpsys` 本来就在
 * [com.dsh.android.termux.tool.AppShellExecutor.needsShellUid] 的名单里）。
 *
 * ## 拿不到 shell 身份怎么办
 *
 * 返回 null 表示「读不到」。调用方据此**降级为纯几何避让**：球停稳后若落在屏幕
 * 下方危险区就直接弹开，完全不依赖任何权限。所以没有 adb 授权的用户也不会被
 * 键盘盖住，只是少了「键盘一弹就飞」的戏剧感。
 */
internal class ImeProbe(
    private val context: Context,
    /** 执行一条 shell 命令并返回 stdout；失败返回 null。由调用方注入（见 [FloatingBubble]）。 */
    private val shell: (String) -> String?,
) {
    companion object {
        private const val TAG = "DSH"

        /**
         * 轮询间隔（ms）。
         *
         * ## 这个值是被用户实测逼下来的
         *
         * 起初定 2000 ms，理由是「单次 `dumpsys` 95–150 ms，2 秒一轮约 5%–7% CPU」。
         * **真机反馈是「键盘唤起后大概半秒才有动作」** —— 2 秒间隔下平均延迟就有
         * 1 秒，最坏接近 2 秒，主观上就是「迟钝」。
         *
         * 收到 600 ms 后：平均延迟 ≈ 300 ms，配合 `dumpsys` 自身开销，体感上
         * 「键盘刚弹完球就动了」。开销约 150/600 ≈ 25% 的单核占用 —— 但它**只在球
         * 停在下方危险区时才跑**（见 `FloatingBubble.startImeWatching`），球在上半屏
         * 或收起探针时是零开销，所以平均到全天可以忽略。
         *
         * ⚠️ 不要再往下压到 200 ms 以下：`dumpsys` 本身 100+ ms，间隔小于它只会
         *   让请求排队（`inFlight` 守卫会跳过，实际间隔反而变成「每次执行耗时」），
         *   既不会更快，又把 CPU 拉满。
         */
        const val POLL_INTERVAL_MS = 600L

        /**
         * 单次探测的超时（ms）。超过就当作「读不到」。
         *
         * `dumpsys` 正常 150 ms 内返回，但系统繁忙时可能拖到几秒 —— 那时线程会
         * 一直占着，下一次 [tick] 又排一个，很快就把线程堆满。所以给个上限。
         */
        private const val PROBE_TIMEOUT_MS = 1500L
    }

    private val main = Handler(Looper.getMainLooper())

    /** 探到的键盘状态。null = 还没探过 / 读不到。 */
    @Volatile
    var imeShown: Boolean? = null
        private set

    /** shell 身份不可用（没有 uid 2000 / Shizuku）。一旦确认就**不再重试**。 */
    @Volatile
    var unavailable: Boolean = false
        private set

    /*
     * ⚠️ **这两个必须是 `@Volatile`。**
     *
     * `inFlight` 的写方在**两处不同线程**：
     *   · 主线程：`tick.run()` 里 `inFlight = true`（第 ~101 行）
     *   · 后台线程：`Thread({ … inFlight = false })` 结尾
     * 而读方是主线程（`if (inFlight) { schedule(); return }`）。
     *
     * 没有 `@Volatile` 时，后台线程写的 `false` **不保证被主线程看到**
     * （JIT 可以把 `inFlight` 缓存在寄存器里，或读到过期的缓存行）。
     * 后果极具迷惑性：主线程一直认为 `inFlight == true`，于是
     * **此后每一拍都直接跳过、再也不发起探测** —— 表现为
     * 「键盘避让功能用着用着就不动了」，重启 App 才恢复，
     * 而且没有任何日志（因为根本没进探测逻辑）。
     *
     * `running` 同理：`start()`/`stop()` 在主线程，`tick.run()` 也读它，
     * 但它还被 [FloatingBubble] 的其它路径触及，统一加 `@Volatile` 更稳。
     */
    @Volatile
    private var running = false
    @Volatile
    private var inFlight = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // 上一次还没回来就跳过这一拍 —— 不排队堆积。
            if (inFlight) {
                schedule()
                return
            }
            inFlight = true
            Thread({
                /*
                 * ⚠️ **[PROBE_TIMEOUT_MS] 必须真的用上，不能只是声明。**
                 *
                 * 原来这个常量只在 KDoc 里被提到过一次（`grep` 全文只有定义那一行），
                 * 实际代码里**从来没有拿它做过任何事**。而 KDoc 明确写着
                 * 「超过就当作读不到 … 那时线程会一直占着，下一次 tick 又排一个，
                 * 很快就把线程堆满。所以给个上限」—— 这个保证并不存在。
                 *
                 * 真正的失效模式是：`shell(...)` 挂死（binder 卡住 / 系统服务死锁）
                 * ⇒ 这个线程永远不返回 ⇒ 末尾的 `inFlight = false` 永不执行
                 * ⇒ 之后**每一拍都被 `if (inFlight)` 跳过**，键盘避让永久失效，
                 * 且没有任何日志。
                 *
                 * 下面用看门狗定时器保证 `inFlight` 被复位。
                 */
                /*
                 * ⚠️ 为什么不用 `withTimeout`：`shell` 是普通同步 lambda
                 *    `(String) -> String?`，不是 suspend 函数，协程无法取消它。
                 *    对它的调用会一直占着这条线程直到自己返回。
                 *
                 * 所以超时的作用点只有一个：**保证 `inFlight` 能被复位**。
                 * 复位之后主线程的 tick 会继续发起新的探测（挂死的那条线程
                 * 由 [ShizukuShell] 的 `EXEC_TIMEOUT_MS` 兜底释放）。
                 *
                 * 如果什么都不做，`inFlight` 一旦卡在 true，此后每一拍都被
                 * `if (inFlight)` 跳过 —— 键盘避让永久失效且无日志。
                 */
                val watchdog = java.util.Timer("dsh-ime-probe-wd", true).apply {
                    schedule(object : java.util.TimerTask() {
                        override fun run() {
                            if (inFlight) {
                                Log.w(
                                    "ImeProbe",
                                    "探测超过 ${PROBE_TIMEOUT_MS}ms 未返回，强制复位 inFlight",
                                )
                                inFlight = false
                            }
                        }
                    }, PROBE_TIMEOUT_MS)
                }
                val out = try {
                    shell("dumpsys input_method | grep -m1 mInputShown=")
                } catch (t: Throwable) {
                    Log.w("ImeProbe", "探测异常: ${t.message}")
                    null
                } finally {
                    watchdog.cancel()
                }
                val shown = parseInputShown(out)
                inFlight = false
                if (shown == null) {
                    // 读不到：可能是没权限，也可能是这一拍恰好失败。
                    // 只在前几次连续失败后才判定「不可用」，避免偶发失败就永久降级。
                    misses++
                    if (misses >= 3) {
                        unavailable = true
                        Log.i(TAG, "键盘探测不可用（连续 $misses 次读不到），改走几何避让")
                        main.post { stop() }
                    }
                } else {
                    misses = 0
                    val changed = imeShown != shown
                    imeShown = shown
                    if (changed && shown) {
                        Log.i(TAG, "检测到键盘弹出")
                        onShown?.let { cb -> main.post { cb() } }
                    }
                }
                main.post { if (running) schedule() }
            }, "ime-probe").start()
        }
    }

    private var misses = 0

    /** 检测到键盘**从无到有**时回调（已在主线程）。 */
    var onShown: (() -> Unit)? = null

    /**
     * 开始探测。重复调用无副作用。
     *
     * @param immediate true = 立刻探一拍（球刚停稳时用，别让用户等满一个间隔）。
     */
    fun start(immediate: Boolean = false) {
        if (unavailable) return
        // ⚠️ 已经在跑时**不能**直接 return：调用方（球刚停稳）期望「现在就探一拍」，
        //   直接返回的话它会沿用上一拍的排期，最坏要再等一整个 [POLL_INTERVAL_MS]。
        //   正确做法是保持 running、但把排期重置成"立刻"。
        if (running) {
            if (immediate && !inFlight) {
                main.removeCallbacks(tick)
                tick.run()
            }
            return
        }
        running = true
        misses = 0
        if (immediate) tick.run() else schedule()
    }

    /** 停止探测。幂等。 */
    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    /** 彻底释放（球被移除时调）。之后对象不可再用。 */
    fun release() {
        stop()
        onShown = null
        imeShown = null
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, POLL_INTERVAL_MS)
    }

    private fun parseInputShown(out: String?): Boolean? {
        if (out.isNullOrBlank()) return null
        // 形如：`  mShowRequested=false mShowExplicitlyRequested=false mShowForced=false mInputShown=true`
        val m = Regex("mInputShown=(true|false)").find(out) ?: return null
        return m.groupValues[1] == "true"
    }
}
