package com.dsh.android.termux

import android.system.Os
import com.dsh.android.log.DshLog as Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Node 的安装标记、自校验和修复事务；所有入口由引导器持有 prefix 双锁后调用。 */
internal class TermuxNodeRuntime(
    private val prefixDir: File,
    private val openAsset: (String) -> InputStream,
    private val rewritePaths: () -> Unit,
) {
    private val nodeMarker get() = File(prefixDir, ".node_runtime_$NODE_VERSION")
    val nodeBinary: File get() = File(prefixDir, "bin/node")

    fun installLocked(): Boolean {
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

            openAsset(NODE_RUNTIME_ASSET).use { raw ->
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
            rewritePaths()

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

    /** 返回是否尝试了修复，与既有安全模式 API 的语义保持一致。 */
    fun repairIfNeededLocked(): Boolean {
        return if (!prefixDir.exists()) {
            false
        } else if (nodeMarker.exists() && nodeBinary.isFile && nodeIsUsable()) {
            false
        } else {
            Log.w("TERMUX", "安全模式：内置 Node 运行时不可用，重新解包 $NODE_VERSION")
            nodeMarker.delete()
            nodeBinary.delete()
            installLocked()
            true
        }
    }

    private companion object {
        const val TAG = "TermuxBootstrap"
        const val NODE_RUNTIME_ASSET = "node-runtime.bin"
        const val NODE_VERSION = "26.4.0-2"
        const val NODE_EXECUTABLE_VERSION = "v26.4.0"
        const val NODE_CHECK_TIMEOUT_MS = 10_000L
    }
}
