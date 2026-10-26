package com.dsh.android

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Shizuku 特权通道 —— 让 uid 2000 不再**只**依赖 adbd。
 *
 * ## 为什么必须加这条通道
 *
 * 现有的 uid 2000 通道全部建立在「连上设备上的 adbd」之上：
 *
 * | 通道 | 前提 |
 * |---|---|
 * | legacy 5555 | PC 先执行过 `adb tcpip 5555`，且我们那把 adbkey 被设备信任 |
 * | 无线调试 | Android 11+ 的无线调试（配对或 `_adb-tls-connect._tcp`） |
 *
 * 这两条对相当一部分用户**根本不成立**：
 *
 * - Android 10 及以下没有无线调试，唯一的路是 PC 跑一次 `adb tcpip 5555`；
 *   而 `persist.adb.tcp.port` 在 Android 10 上写不进去（SELinux 拒绝），
 *   于是**每次重启都要 PC 再来一次**；
 * - 更糟的是 App 自己**造不出** 5555：发 `adb tcpip` 必须先有一条活的 adb 连接，
 *   而没有 5555、没有无线调试时那条连接不存在 —— 循环依赖，用户在设置页
 *   怎么点都没用。
 *
 * 而 Shizuku 恰好绕开了**全部**这些前提：它自己用 root 或 adb 起一个常驻
 * `app_process`（uid 2000），通过 binder 提供服务。设备上**不需要 adbd 在 5555
 * 监听**，也**不需要**校验我们那把 adbkey —— 所以用户那边「Shizuku 能用、
 * 我们的特权通道用不了」是常态，而不是例外。
 *
 * ## 为什么要在原生开一个 socket 桥
 *
 * Shizuku 的能力只能从**原生 Kotlin 侧**调用（它是 binder IPC，需要
 * `ShizukuProvider` 在清单里收 binder）。而真正要执行命令的是
 * [com.dsh.android.EngineManager] 解出来的 Node runner
 * （`dsh-android-shell.mjs`）—— 它是个独立进程，碰不到 binder。
 *
 * 所以这里把「执行一条 shell 命令」做成本地 IPC 服务：
 *
 * ```
 * dsh-android-shell.mjs ──(LocalSocket, 抽象命名空间)──> ShizukuChannel
 *                                                             │
 *                                                             │ IShizukuService.newProcess()
 *                                                             ▼
 *                                                    Shizuku 服务端 (uid 2000)
 * ```
 *
 * 选抽象命名空间的 `LocalSocket` 而不是 TCP 回环端口：
 * ① 不占端口、不会与别的应用撞车；② 只有本机进程能连（不受 `usesCleartextTraffic`
 * 影响，也不必担心被同机其它 app 蹭）；③ 名字带包名，两个 flavor 同装时互不干扰。
 *
 * ## ⚠️ 与本 App 其它通道的语义差异（必须知道）
 *
 * 1. **这条通道不校验任何 adbkey** —— 它是 Shizuku 自己的授权体系
 *    （用户在 Shizuku 里给我们授权，`Shizuku.checkSelfPermission()`）。
 *    所以早先那些「密钥没导入 / 被撤销」的诊断在 Shizuku 通道上**不适用**。
 * 2. **`newProcess` 在 Shizuku API 14 计划移除**（见 `Shizuku.java` 的注释）。
 *    当前设备实测是 13.6.0，可用。真要升到 14 时这条通道要改成
 *    「`ShizukuBinderWrapper` + 反射调 `Runtime.exec`」那一套，
 *    **不要**因为编译期还能过就以为永远能用。
 * 3. **走这条通道同样绕过沙箱**：uid 2000 读不到 app 私有目录，
 *    `libboosandbox.so` / `liblinkcompat.so` 都挂在 `LD_PRELOAD` 上、
 *    都在那里 —— 所以写沙箱与 link 转译对它都不生效。
 *    这与 legacy 通道的代价完全一致，不是新增的缺陷。
 *
 * ## 协议（长度前缀帧）
 *
 * 请求：[4 字节大端长度][UTF-8 JSON] `{"command":"…","workdir":"…"|null}`
 * 响应：[4 字节大端长度][UTF-8 JSON] 见 [Frame]
 *
 * stdout/stderr 用 **Base64** 而不是 JSON 字符串：原生侧拿到的是原始字节，
 * 直接按 UTF-8 解会在遇到非法字节序列时替换成 U+FFFD，把信息抹掉；
 * Base64 往返保真，解码成字符串的决定权留给 runner（与它既有的
 * legacy 通道行为一致）。
 */
object ShizukuChannel {

    private const val TAG = "DSH"

    /** Shizuku 管理器包名。只在**引导用户去装**时用，不参与授权判断。 */
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * 单帧上限。
     *
     * 取 32 MB：`dumpsys` 这类命令的输出可以到几 MB，而 `ps -A` 在真机上是
     * 672 行。上限的作用只是**挡住畸形长度**（防止对端报一个天文数字把内存吃爆），
     * 不是业务限制。
     */
    private const val MAX_FRAME = 32 * 1024 * 1024

    /**
     * 单次命令最多捕获多少字节的 stdout / stderr。
     *
     * ⚠️ 取值要考虑**下游的内存放大**，不只是「看起来够大」：
     *   捕获的字节 → Base64（×1.33）→ 拼进 JSON 字符串（再×1~2）→ 跨桥传给页面。
     *   所以 1 MB 的原始输出在下游大约占 3~5 MB。
     *
     * 取 1 MB：`ps -A`、`df`、`ls -R` 这类日常命令的输出通常只有几十 KB，
     * 1 MB 对它们绰绰有余；而真要读大文件，正确做法是让命令自己截断
     * （`head`/`tail`）或用 paging —— 不是让这条通道无限吃内存。
     *
     * ⚠️ 必须小于 [MAX_FRAME]：否则输出还没到对端就被帧长校验拒掉，
     *   用户会看到「帧过大」这种与真实原因无关的报错。
     */
    private const val MAX_CAPTURE_BYTES = 1024 * 1024

    @Volatile private var server: LocalServerSocket? = null
    private val running = AtomicBoolean(false)

    /** 抽象 socket 名（Java 侧不带 `\0` 前缀，底层仍是 abstract namespace）。 */
    fun socketName(context: Context): String = "${context.packageName}.shizuku"

    /**
     * runner 侧连接用的路径。
     *
     * ⚠️ **`\0` 前缀是语义的一部分**：Node 的 `net.connect({path})` 用它表示
     * Linux 抽象命名空间。少了这个字节，Node 会去文件系统里找一个叫
     * `<包名>.shizuku` 的**文件**，而它不存在 —— 症状是
     * `ENOENT: no such file or directory`，看起来像「桥没起来」，
     * 而其实桥好得很。
     *
     * 🔴 **这个值绝对不能直接塞进环境变量。** `execve` 的 `envp` 是 C 字符串数组，
     *    NUL 就是它的结束符 —— Java 的 `ProcessBuilder.environment()` 收到含 NUL
     *    的字符串时，传过去的值会在 NUL 处被**静默截断**，runner 拿到空串，
     *    于是这条通道整条不生效，而且**没有任何报错**。
     *
     *    所以对外只传 [socketName]（纯名字），`\0` 由 runner 自己补。
     *    这个方法保留下来是为了让「要连的到底是什么」有一处权威定义。
     */
    fun socketPath(context: Context): String = "\u0000${socketName(context)}"

    // ── 服务状态 ────────────────────────────────────────────────

    /** Shizuku 管理器是否装了。 */
    fun isManagerInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Shizuku 服务的 binder 是否到手。
     *
     * ⚠️ **`pingBinder()` 只是「能联系上」**，不代表我们有权限执行 —— 那是
     * [hasPermission] 的事。两者必须分开报，否则设置页会把
     * 「Shizuku 在跑但没给我们授权」显示成「Shizuku 不可用」，
     * 用户就会去卸载重装 Shizuku 而不是点一下授权。
     */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** 我们是否已被 Shizuku 授权。 */
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        // binder 不在（或已死）时这里会抛，语义上等价于「还没有权限」
        false
    }

    /**
     * Shizuku 服务端以什么身份在跑：`2000` = 经 adb 启动，`0` = 经 root 启动。
     * 拿不到给 `-1`。
     */
    fun serverUid(): Int = try {
        Shizuku.getUid()
    } catch (t: Throwable) {
        -1
    }

    /** Shizuku 服务端 API 版本；拿不到给 `-1`。 */
    fun serverVersion(): Int = try {
        Shizuku.getVersion()
    } catch (t: Throwable) {
        -1
    }

    /**
     * 让 Shizuku 弹授权框。
     *
     * ⚠️ **必须在有 binder 且前台 Activity 存在时调**：Shizuku 的确认界面是
     * **它自己**的活动，由 Shizuku 服务端拉起。binder 不在时这个调用会抛。
     * 结果经 `Shizuku.addRequestPermissionResultListener` 异步回来，
     * 所以调用方要注册监听（见 [MainActivity]）。
     */
    fun requestPermission(activity: Activity, requestCode: Int): Boolean = try {
        if (!isBinderAlive()) false else {
            Shizuku.requestPermission(requestCode)
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "请求 Shizuku 授权失败: ${t.message}")
        false
    }

    // ── 桥的生命周期 ────────────────────────────────────────────

    /**
     * 起桥（幂等）。
     *
     * ⚠️ **无论 Shizuku 当前可不可用都要起**：Shizuku 服务可能是用户**稍后**
     * 才启动的（重启手机后 Shizuku 要手动开一次），而桥一旦绑定名字就固定了。
     * 起桥时检查可用性会让「先开 App 再开 Shizuku」变成永远连不上。
     * 所以这里只负责「有人连进来时如实回答当前能不能执行」。
     *
     * @return true 表示桥在跑（含本来就是它在跑）。
     */
    fun start(context: Context): Boolean {
        if (running.get()) return true
        synchronized(this) {
            if (running.get()) return true
            return try {
                val name = socketName(context)
                val s = LocalServerSocket(name)
                server = s
                running.set(true)
                thread(name = "dsh-shizuku-accept", isDaemon = true) { acceptLoop(s) }
                Log.i(TAG, "已启动 Shizuku 桥: @$name")
                true
            } catch (t: Throwable) {
                // 起不来只是少一条通道，绝不能拖垮引擎启动
                running.set(false)
                server = null
                Log.w(TAG, "启动 Shizuku 桥失败: ${t.message}")
                false
            }
        }
    }

    /** 关桥（进程退出时调用，主要为了让端口名随进程释放）。 */
    fun stop() {
        running.set(false)
        val s = server
        server = null
        // close 会让阻塞在 accept() 上的线程抛异常退出，这是预期的收尾方式
        runCatching { s?.close() }
    }

    private fun acceptLoop(s: LocalServerSocket) {
        while (running.get()) {
            val client = try {
                s.accept()
            } catch (t: Throwable) {
                // 关桥时 accept 必然抛，不是错误；桥还在跑却抛了则继续等下一个
                if (!running.get()) return
                continue
            }
            thread(name = "dsh-shizuku-conn", isDaemon = true) { handle(client) }
        }
    }

    // ── 单条命令 ────────────────────────────────────────────────

    private fun handle(client: LocalSocket) {
        try {
            val input = DataInputStream(client.inputStream.buffered())
            val output = DataOutputStream(client.outputStream.buffered())

            val len = try {
                input.readInt()
            } catch (t: Throwable) {
                // 对端连上又立刻断开（探测型连接），正常
                return
            }
            if (len <= 0 || len > MAX_FRAME) {
                writeFrame(output, errorFrame("bad-frame", "帧长度非法: $len"))
                return
            }
            val body = ByteArray(len)
            input.readFully(body)
            val req = JSONObject(String(body, Charsets.UTF_8))
            val command = req.optString("command", "")
            // ⚠️ **必须用 `isNull` 判，不能用 `optString` 的默认值兜底。**
            //
            //   runner 在「不指定工作目录」时发的是 JSON 字面量 `null`（见
            //   `dsh-android-shell.mjs` 的 `workdir: workdir ?? null`）。
            //   而 `org.json` 的 `optString(key, fallback)` 内部是
            //   `opt(key).toString()`，`JSONObject.NULL` 是个**非 null 的哨兵对象**，
            //   它的 `toString()` 返回字符串 `"null"` —— 于是 fallback 根本不会被用到，
            //   我们拿到的是四个字母的目录名 `null`。
            //
            //   症状（真机实测）：`newProcess` 报
            //   `Cannot run program "/system/bin/sh" (in directory "null"):
            //    error=2, No such file or directory`，
            //   整个 Shizuku 通道 100% 不可用，而错误信息看起来像是 sh 不存在 ——
            //   排查方向会被完全带偏。
            val workdir = if (req.isNull("workdir")) {
                null
            } else {
                req.optString("workdir", "").ifEmpty { null }
            }

            if (command.isEmpty()) {
                writeFrame(output, errorFrame("empty-command", "命令为空"))
                return
            }
            writeFrame(output, runCommand(command, workdir))
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku 桥处理命令失败: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * 真正执行：走 `IShizukuService.newProcess()`。
     *
     * ⚠️ **`Shizuku.newProcess` 是 private**，所以这里绕一层
     * `IShizukuService.Stub.asInterface(Shizuku.getBinder())` 自己调。
     * `ShizukuRemoteProcess` 的构造器是包内可见的，也用它不了 —— 但那没关系：
     * 它只是 `IRemoteProcess` 的一层包装，我们直接用接口即可。
     */
    private fun runCommand(command: String, workdir: String?): String {        val binder = try {
            Shizuku.getBinder()
        } catch (t: Throwable) {
            null
        } ?: return errorFrame("shizuku-unavailable", "Shizuku 服务未运行")

        if (!hasPermission()) {
            return errorFrame("shizuku-no-permission", "尚未获得 Shizuku 授权")
        }

        val proc: IRemoteProcess = try {
            val service = IShizukuService.Stub.asInterface(binder)
            // env 传 null：用服务端的 shell 环境（PATH 等齐全）。
            // 传一份自造的空环境会让 `pm`、`ls` 这类命令找不到，是踩过的坑。
            service.newProcess(arrayOf("/system/bin/sh", "-c", command), null, workdir)
        } catch (t: Throwable) {
            return errorFrame("shizuku-exec-failed", "newProcess 失败: ${t.message}")
        }

        return try {
            // ⚠️ 两路输出必须**并发**排空：单线程先读 stdout 再读 stderr 会在
            //    子进程写满 stderr 管道（64 KB）而 stdout 还没读完时死锁
            //    （与 AppShellExecutor 里那条注释同一个原因）。
            //
            // ⚠️ **必须是 `arrayOfNulls<ByteArray>(1)`，不能是 `arrayOf<ByteArray>()`。**
            //    后者创建的是**长度 0** 的数组（`arrayOf()` 无参就是空数组），
            //    `outBox[0] = …` 立刻抛 ArrayIndexOutOfBoundsException。而这里跑在
            //    `thread {}` 里 —— **子线程的未捕获异常会直接杀掉整个进程**，
            //    不是只让这条命令失败。
            //
            //    真机实测（Android 10，Shizuku 已授权）：`shell` 工具第一次执行
            //    命令就把 App 崩掉，堆栈是
            //    `ArrayIndexOutOfBoundsException: length=0; index=0 at
            //     ShizukuChannel.runCommand$lambda$6(ShizukuChannel.kt:342)`，
            //    连崩两次后系统直接 `has crashed too many times: killing!`。
            //    而 runner 侧的诊断行只显示 `shizuku=unavailable` —— 从日志看
            //    像是通道没接上，实际是**执行成功了但读输出时把 App 崩了**。
            val outBox = arrayOfNulls<ByteArray>(1)
            val errBox = arrayOfNulls<ByteArray>(1)
            // ⚠️ 再包一层 `runCatching`：`thread {}` 里抛出的异常**没有 catch 出口**，
            //    会走线程默认处理器把整个进程带走。上面那个数组长度写错就是活例。
            //    这里多兜一层，代价是零，换来的是「读输出出岔子最多让这条命令失败」。
            // ⚠️ 输出**必须限长**（见 drain 的 KDoc）：这条通道执行的是任意命令，
            //   `cat` 大文件 / `logcat -d` 的输出能把堆撑爆，而这里在子线程上，
            //   OOM 没有 catch 出口，会直接把整个进程带走。
            val truncBox = java.util.concurrent.atomic.AtomicBoolean(false)
            val t1 = thread(isDaemon = true) {
                runCatching {
                    val (bytes, cut) = drain(proc.inputStream)
                    outBox[0] = bytes
                    if (cut) truncBox.set(true)
                }
            }
            val t2 = thread(isDaemon = true) {
                runCatching {
                    val (bytes, cut) = drain(proc.errorStream)
                    errBox[0] = bytes
                    if (cut) truncBox.set(true)
                }
            }
            val code = try {
                proc.waitFor()
            } catch (t: Throwable) {
                runCatching { proc.destroy() }
                return errorFrame("shizuku-exec-failed", "waitFor 失败: ${t.message}")
            }
            t1.join()
            t2.join()
            JSONObject()
                .put("ok", true)
                .put("exitCode", code)
                .put("stdout", b64(outBox[0] ?: ByteArray(0)))
                .put("stderr", b64(errBox[0] ?: ByteArray(0)))
                // ⚠️ **必须把截断事实告诉调用方。** 不然模型会拿着半截输出
                //   当成完整结果去下结论，那比直接报错更难查。
                .put("truncated", truncBox.get())
                .put("captureLimitBytes", MAX_CAPTURE_BYTES)
                .toString()
        } catch (t: Throwable) {
            runCatching { proc.destroy() }
            errorFrame("shizuku-exec-failed", "读取输出失败: ${t.message}")
        }
    }

    /**
     * 读干一个 [ParcelFileDescriptor]，并关掉它（不关会泄漏 fd）。
     *
     * ⚠️ **必须限长读取，不能用 `readBytes()`。**
     *
     * 这条通道跑的是**用户/AI 给的任意 shell 命令**，输出体量完全不受控：
     * `cat` 一个大文件、`logcat -d`、`find /`、`dumpsys` 都是几 MB 到几十 MB。
     * 而 `readBytes()` 是「一直读到一个字节都没有为止」，全塞进堆里；
     * 之后还要 Base64（+33%）再拼进 JSON 字符串（再翻一倍）——
     * 一个 20 MB 的输出能让峰值内存冲到 50 MB 以上，低内存机上直接 OOM。
     *
     * 更糟的是它跑在 `thread {}` 里：子线程的 OOM 没有 catch 出口，
     * 会把**整个进程**带走（本文件上面那段 ArrayIndexOutOfBounds 的注释
     * 就是同一个机制的真机教训）。
     *
     * 所以改成限长读取：最多 [MAX_CAPTURE_BYTES]，超了就截断并**明确标记**。
     * 调用方（工具层）需要知道「这不是全部输出」，否则会拿着半截输出
     * 得出错误结论 —— 那比报错更难查。
     *
     * @return (已读字节, 是否被截断)
     */
    private fun drain(pfd: ParcelFileDescriptor?): Pair<ByteArray, Boolean> {
        if (pfd == null) return ByteArray(0) to false
        return try {
            FileInputStream(pfd.fileDescriptor).use { it ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var truncated = false
                while (true) {
                    // 多读 1 字节用于判断「是否还有剩余」，避免多截一段再猜
                    val room = MAX_CAPTURE_BYTES - buf.size()
                    if (room <= 0) {
                        truncated = it.read() >= 0
                        break
                    }
                    val n = it.read(chunk, 0, minOf(chunk.size, room + 1))
                    if (n <= 0) break
                    if (n > room) {
                        buf.write(chunk, 0, room)
                        truncated = true
                        break
                    }
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray() to truncated
            }
        } catch (t: Throwable) {
            ByteArray(0) to false
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun errorFrame(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun writeFrame(output: DataOutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
