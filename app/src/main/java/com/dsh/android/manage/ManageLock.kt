package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 管理事务的**跨进程监督锁**。
 *
 * ## 为什么需要它
 *
 * 设计说明 §6.5.2：
 *
 * > 启动、停止、A/R/E 切换、更新、修复、恢复和受管包操作由同一监督协调机制受理。
 * > 使用跨进程监督锁及必要的环境写锁；同环境冲突操作串行，不无差别阻塞全部普通任务。
 *
 * 现状是**完全没有**这层协调：`EngineManager.start()` / `stop()` /
 * `enterSafeMode()` 谁都能在任何时刻调用，彼此之间没有互斥。后果：
 *
 * - 两次启动并发 → 两个 node 进程抢同一个 `DSH_HOME` 的会话锁
 * - 停止与修复并发 → 修复改文件时引擎还在写
 * - 救援切换与正常启动并发 → 两个 profile 同时被读
 *
 * ## 为什么用 `FileChannel.tryLock()`
 *
 * | 方案 | 跨进程 | 随进程退出释放 | Android 可用 |
 * |---|---|---|---|
 * | `synchronized` / `ReentrantLock` | ❌ 只在本 JVM | — | ✅ |
 * | `FileChannel.tryLock()` | ✅ | ✅（fcntl 语义） | ✅ |
 * | 自建 pid 文件 | ✅ | ❌ 需手工清理，崩溃留死锁 | ✅ |
 *
 * **「随进程退出自动释放」这一条是决定性的**：崩溃循环是这个 App 最常见的
 * 故障形态，自建 pid 文件在那种场景下会留下永久死锁，用户只能清数据。
 *
 * 机制照抄本项目既有的 `TermuxBootstrap.withPrefixFileLock` 与
 * `LogUploader` 的补传锁 —— 同一手法已经在两处跑过真机。
 *
 * ## ⚠️ 三个必须记住的坑
 *
 * **① `OverlappingFileLockException` 不是「另一个进程」**
 *
 * 同一个 JVM 内对同一文件重复加锁会抛这个异常（Java 的规范如此），
 * 它和「另一个进程持锁」是两件事。日志里必须如实区分，否则排查时会
 * 往「进程重叠」方向查，而实际只是同进程重入。
 *
 * **② 锁文件不能放在会被清理的目录**
 *
 * 放 `cache/` 里会被系统回收，那样两个进程可能各自创建一份新文件、
 * 各自加锁成功 —— 锁就失效了。放 `filesDir/manage/`（私有、持久）。
 *
 * **③ 持锁期间不要做长耗时操作**
 *
 * 锁是**管理操作**的互斥，不是「引擎运行期间一直持有」。启动拿到锁、
 * 完成状态转换后立刻释放，否则修复/停止会被一直挡住。
 */
object ManageLock {

    private const val TAG = "DSH"

    /** 锁文件名。放在 `filesDir/manage/` 下，见 KDoc 坑②。 */
    private const val LOCK_FILE = "manage.lock"

    /** 拿不到锁时返回的原因。 */
    sealed interface Result {
        /** 拿到了锁。**必须**在 `finally` 里 [Handle.close]。 */
        data class Acquired(val handle: Handle) : Result

        /** 另一个进程持锁。 */
        data object HeldByOtherProcess : Result

        /** 本进程已经持锁（同 JVM 重入）。 */
        data object HeldByThisProcess : Result

        /** 锁文件本身打不开（磁盘满、权限异常）。 */
        data class Unavailable(val reason: String) : Result
    }

    /** 持锁句柄。`close()` 幂等。 */
    class Handle internal constructor(
        private val raf: RandomAccessFile,
        private val lock: FileLock,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            // ⚠️ 顺序必须是「先 release 再 close」：反过来的话 release 会抛
            //   ClosedChannelException，而那个异常在 finally 里会被吞掉 ——
            //   表现为「锁看起来释放了，实际进程退出前一直持有」。
            runCatching { lock.release() }
            runCatching { raf.close() }
        }
    }

    private fun lockFile(context: Context): File =
        File(File(context.filesDir, "manage"), LOCK_FILE)

    /**
     * 尝试取得监督锁，**不阻塞**。
     *
     * 用 `tryLock` 而不是 `lock`：启动路径上不能让用户等另一个进程几十秒。
     * 拿不到就如实返回原因，由调用方决定是放弃还是稍后重试。
     *
     * @param reason 拿锁的用途（写进事件日志，便于事后对账）
     */
    fun tryAcquire(context: Context, reason: String): Result {
        val file = lockFile(context)
        file.parentFile?.mkdirs()

        val raf = runCatching { RandomAccessFile(file, "rw") }.getOrElse { t ->
            return Result.Unavailable(t.message ?: t::class.java.simpleName)
        }

        val lock = try {
            raf.channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            // ⚠️ 同 JVM 重入，**不是**另一个进程（见 KDoc 坑①）
            runCatching { raf.close() }
            EventLog.record("manage.lock.reentrant", mapOf("reason" to reason))
            return Result.HeldByThisProcess
        } catch (t: Throwable) {
            runCatching { raf.close() }
            return Result.Unavailable(t.message ?: t::class.java.simpleName)
        }

        if (lock == null) {
            runCatching { raf.close() }
            EventLog.record("manage.lock.busy", mapOf("reason" to reason))
            return Result.HeldByOtherProcess
        }

        Log.i(TAG, "已取得监督锁: $reason")
        EventLog.record("manage.lock.acquired", mapOf("reason" to reason))
        return Result.Acquired(Handle(raf, lock))
    }

    /**
     * 持锁执行一段管理操作。
     *
     * 拿不到锁时**不执行** [block]，返回 null 并记日志 —— 调用方必须
     * 能区分「执行了但失败」与「根本没执行」，不能把两者都当成失败。
     *
     * @return [block] 的返回值；没拿到锁时返回 null
     */
    fun <T> withLock(context: Context, reason: String, block: () -> T): T? {
        return when (val r = tryAcquire(context, reason)) {
            is Result.Acquired -> r.handle.use { block() }
            is Result.HeldByOtherProcess -> {
                Log.w(TAG, "监督锁被另一进程持有，跳过: $reason")
                null
            }
            is Result.HeldByThisProcess -> {
                // 同进程重入：本项目里这**通常**是缺陷（说明有两条管理路径
                // 同时跑），但直接拒绝会让正常流程也失败。这里放行并告警。
                Log.w(TAG, "监督锁在本进程内重入: $reason（若非预期，说明有并发管理路径）")
                block()
            }
            is Result.Unavailable -> {
                Log.w(TAG, "监督锁不可用（${r.reason}），跳过: $reason")
                null
            }
        }
    }

    /** 锁文件路径（诊断用）。 */
    fun lockPath(context: Context): String = lockFile(context).absolutePath
}
