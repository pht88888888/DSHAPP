package com.dsh.android.engine

import java.io.File

/** 构建引擎正常启动的 argv。 */
internal object EngineLaunchSpec {
    /**
     * launcher 只解析自己的 `--profile` / `--patch`；遇到第一个不认识的参数后，
     * 剩余参数原样交给 Web 服务。故所有 `--patch` 必须排在 `--port` / `--no-open` 前面。
     *
     * @param port null = 使用引擎默认端口，0 = 由内核分配空闲端口。
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
