package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File

/** 启动命令、目录、环境和 overlay 的只读快照。 */
internal class EngineLaunchSpec(
    val nodeBinary: File,
    val entryPoint: File,
    val profile: String,
    overlays: List<File>,
    val workspace: File,
    environment: Map<String, String>,
    val port: Int?,
) {
    val overlays: List<File> = java.util.Collections.unmodifiableList(ArrayList(overlays))
    val environment: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(environment))
    val arguments: List<String> get() = buildEngineArgs(nodeBinary, entryPoint, profile, overlays, port)

    fun forProbe(profile: String, overlays: List<File>) =
        EngineLaunchSpec(nodeBinary, entryPoint, profile, overlays, workspace, environment, 0)

    fun processBuilder(mergeError: Boolean = true): ProcessBuilder =
        ProcessBuilder(arguments).directory(workspace).redirectErrorStream(mergeError).also { builder ->
            builder.environment().apply { clear(); putAll(environment) }
        }

    companion object {
        /**
         * 拼出启动引擎的完整 argv（**纯函数**，便于离线验证与复用）。
         *
         * ## 为什么抽出来
         *
         * 它现在有三个调用方，而且**必须给出完全一致的参数**，否则验证结果没有意义：
         *
         * | 调用方 | 用途 |
         * |---|---|
         * | `start()` | 真正启动 |
         * | 试启动（P3 的 `try-boot`） | 让 AI 验证"改完能不能起来" |
         * | L1 二分法（P1） | 逐组验证哪些插件是坏的 |
         *
         * ⚠️ 后两者如果自己拼参数，就会出现「试启动能起来、真启动起不来」这类
         *   无法解释的差异 —— 而那正是本项目缺陷族最爱长的地方（同一个东西
         *   被两处各写一遍，然后慢慢漂开）。所以只留一份。
         *
         * ## ⚠️ `--port` 的位置是硬性的
         *
         * `--port` 属于 **web app 的内层 flag**，而 launcher
         * （`@deepseek-ai/dsh/lib/bin.js`）只解析自己的 `--profile` / `--patch`，
         * 规则是「第一个不认识的 token 就是内层参数的起点，其后原样转交」。
         * 插到 `--patch` 前面会让它们全被当成内层参数，引擎直接
         * `error: unknown option '--patch'` 起不来（真机实测踩过）。
         * 所以 `--patch` 全部排在 `--port` 之前，最后才是 `--no-open`。
         *
         * @param port null = 不传 `--port`（用引擎内置的 3080）；非 null = 显式指定。
         *   试启动**必须传 0**（让内核挑空闲端口）—— 否则会和正在运行的引擎撞端口，
         *   得到 `EADDRINUSE` 引发的 `startup failed: 2 required plugins did not activate`，
         *   把「插件坏」误报成「端口占用」。
         */
        fun buildEngineArgs(
            nodeBin: File,
            binJs: File,
            profile: String,
            overlays: List<File>,
            port: Int?,
        ): List<String> = buildList {
            add(nodeBin.absolutePath)
            add(binJs.absolutePath)
            add("--profile"); add(profile)
            for (patch in overlays) {
                add("--patch"); add(patch.absolutePath)
            }
            port?.let {
                add("--port"); add(it.toString())
            }
            add("--no-open")
        }
    }
}
