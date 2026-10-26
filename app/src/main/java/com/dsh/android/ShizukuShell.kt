package com.dsh.android

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import kotlin.concurrent.thread
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 以 **uid 2000** 执行一条 shell 命令并拿回 stdout。
 *
 * ## 为什么需要它（而不是直接用 `ProcessBuilder`）
 *
 * 本进程是普通应用 uid。凡是需要 `android.permission.DUMP` / `INSTALL_PACKAGES`
 * 之类特权权限的命令，直接 `ProcessBuilder` 跑都会被系统拒绝。典型例子：
 *
 * ```
 * $ run-as com.dsh.android dumpsys input_method
 * Permission Denial: can't dump InputMethodManagerService ... uid=10347
 *   due to missing android.permission.DUMP permission
 * ```
 *
 * 所以「读键盘状态」这条需求**只能**走 Shizuku 的 `newProcess()`（服务端以
 * uid 2000 运行，它 fork 出来的子进程也就带着特权）。
 *
 * ## 与 [ShizukuChannel] 的关系
 *
 * [ShizukuChannel] 是给 Node 侧用的 LocalSocket **服务端**，输出是 JSON 帧
 * （`{"stdout":…}`），不适合 Kotlin 内部直接消费。本对象只做最小的一件事：
 * 拿到 stdout 原文。底层调用与 `ShizukuChannel.runCommand` 完全同构 ——
 * 包括那两处踩过的坑（双路并发排空、`arrayOfNulls` 而非 `arrayOf`）。
 *
 * ## 调用开销
 *
 * 每次调用是一次 `newProcess` + 两次 `drain` 线程。单次 `dumpsys input_method`
 * 实测 95–150 ms，所以调用方**必须低频**（见 [ImeProbe.POLL_INTERVAL_MS]）。
 */
internal object ShizukuShell {

    private const val TAG = "DSH"

    /** 单次 Shizuku 命令的总超时。见 [exec] 的说明。 */
    private const val EXEC_TIMEOUT_MS = 3_000L

    /**
     * 执行命令，返回 stdout。失败（无 binder / 无授权 / 执行异常）返回 **null**。
     *
     * ⚠️ 失败一律不抛异常：调用方（[ImeProbe]）用 null 当作「读不到」并据此降级，
     *   抛异常会让轮询线程每次都走异常路径，日志刷屏还难读。
     */
    /**
     * 单次命令最多捕获多少字节。
     *
     * ⚠️ 与 [ShizukuChannel.MAX_CAPTURE_BYTES] 同一个理由，取同一个数量级。
     *
     * 本通道唯一的调用方是 [ImeProbe]，它跑的是 `dumpsys input_method`，
     * 正常输出几十 KB。**但"当前只跑这一条命令"不构成不设上限的理由**：
     * 上限防的是"意外"，而不是"当前的固定用法" ——
     *   · 不同 ROM 的 `dumpsys` 输出差别很大（有的会把整个输入法服务的
     *     历史记录也倒出来）；
     *   · 将来任何人在 [ShizukuShell.exec] 上加一条新命令（`logcat`、`dumpsys meminfo`、
     *     `cat`），无上限的 `readBytes()` 就会把 App 直接吃爆 ——
     *     而 shizuku 的 uid 2000 能读的东西远比 app uid 多，这一层没有任何保护。
     *
     * 取 1 MB 与 Channel 一致：`dumpsys input_method` 远小于它，
     * 超了就说明撞上了畸形输出，截断并把前 1 MB 交出去比 OOM 好。
     */
    private const val MAX_CAPTURE_BYTES = 1024 * 1024

    /**
     * 把 [ParcelFileDescriptor] 读干净（**带上限**）。
     *
     * ⚠️ `IRemoteProcess.inputStream` 返回的是 **ParcelFileDescriptor** 而不是
     *   Java 的 InputStream —— Shizuku 跨进程传文件描述符，不能直接 `readBytes()`。
     *
     * ⚠️ **不能直接 `it.readBytes()`**：那个实现会按 8 KB 递增扩容直到 EOF，
     *   对端吐多少它就吃多少，没有任何上界。改成自己按块读、累计到
     *   [MAX_CAPTURE_BYTES] 就停 —— 并且**必须继续把管道读空**再关，
     *   否则对端写满了管道会阻塞在 write 上（`dumpsys` 这类命令尤其容易撞上）。
     */
    private fun drain(pfd: ParcelFileDescriptor?): ByteArray {
        if (pfd == null) return ByteArray(0)
        return try {
            FileInputStream(pfd.fileDescriptor).use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var captured = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    if (captured >= MAX_CAPTURE_BYTES) {
                        // 到上限了：不再累积，但继续把管道读空，避免对端阻塞。
                        continue
                    }
                    val take = minOf(n, MAX_CAPTURE_BYTES - captured)
                    out.write(buf, 0, take)
                    captured += take
                }
                if (captured >= MAX_CAPTURE_BYTES) {
                    Log.w(TAG, "ShizukuShell 输出超过 ${MAX_CAPTURE_BYTES / 1024} KB，已截断")
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            ByteArray(0)
        } finally {
            runCatching { pfd.close() }
        }
    }

    fun exec(command: String): String? {
        val binder = try {
            Shizuku.getBinder()
        } catch (t: Throwable) {
            null
        } ?: return null

        if (!ShizukuChannel.hasPermission()) return null

        val proc: IRemoteProcess = try {
            val service = IShizukuService.Stub.asInterface(binder)
            // env 传 null ⇒ 用服务端的 shell 环境（PATH 等齐全）。自造空环境会让
            // `dumpsys` 这类命令找不到，是 [ShizukuChannel] 里记过的坑。
            service.newProcess(arrayOf("/system/bin/sh", "-c", command), null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "ShizukuShell 启动进程失败: ${t.message}")
            return null
        }

        // ⚠️ 必须是 `arrayOfNulls<ByteArray>(1)`：`arrayOf<ByteArray>()` 是**长度 0**
        //   的数组，`outBox[0] = …` 立刻 ArrayIndexOutOfBoundsException，而它跑在
        //   子线程里 —— 未捕获异常会**杀掉整个进程**（[ShizukuChannel] 真机崩过两次）。
        val outBox = arrayOfNulls<ByteArray>(1)
        val errBox = arrayOfNulls<ByteArray>(1)
        val t1 = thread(isDaemon = true) {
            runCatching { outBox[0] = drain(proc.inputStream) }
        }
        val t2 = thread(isDaemon = true) {
            // stderr 也要排空，否则子进程写满 64 KB 管道会卡死。
            runCatching { errBox[0] = drain(proc.errorStream) }
        }

        /*
         * ⚠️ `waitFor` **必须带超时**，不能是无参的那个。
         *
         * 无参 `waitFor()` 会一直等到子进程退出。而这个函数唯一的调用方是
         * [ImeProbe]，它**每 2 秒轮询一次** `dumpsys input_method`。
         *
         * 只要有一次子进程挂死（远端 binder 卡住、`dumpsys` 撞上系统服务死锁、
         * 或被 Shizuku 服务端抛弃），这一次调用就永久占住调用线程；更麻烦的是
         * **每次轮询还会新建两条 daemon 线程**（下面的 t1/t2）去排空那两个流 ——
         * 它们同样永远等不到 EOF。于是每分钟最多攒 60 组、120 条线程，
         * 最终 OOM 或线程耗尽。
         *
         * 注意下面 `t1.join(1500)` / `t2.join(1500)` 本来就已经带了超时 ——
         * 说明"流可能读不完"这件事是想到过的，只是 `waitFor` 漏了。
         *
         * 取 3000 ms：`dumpsys input_method` 实测 95–150 ms，3 秒是二十倍余量；
         * 而 [ImeProbe] 的轮询周期是 **600 ms**（见 `ImeProbe.POLL_INTERVAL_MS`），
         * 且它有 `inFlight` 守卫会跳过重叠的拍 —— 超时不宜远大于一个周期，
         * 否则挂死期间会白等好几拍。
         */
        return try {
            /*
             * ⚠️ `IRemoteProcess.waitFor()` **没有带超时的重载**（它是 Shizuku 自己
             *    的接口，签名是 `fun waitFor(): Int`，不像 `java.lang.Process` 那样
             *    有 `waitFor(long, TimeUnit)`）。所以超时只能靠「另起一条线程去等，
             *    主线程限时 join」实现。
             */
            val waiter = thread(isDaemon = true) { runCatching { proc.waitFor() } }
            waiter.join(EXEC_TIMEOUT_MS)
            if (waiter.isAlive) {
                Log.w(TAG, "Shizuku 命令超过 ${EXEC_TIMEOUT_MS}ms 未结束，强制结束")
                runCatching { proc.destroy() }
            }
            t1.join(1500)
            t2.join(1500)
            outBox[0]?.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "ShizukuShell 执行失败: ${t.message}")
            runCatching { proc.destroy() }
            null
        }
    }
}
