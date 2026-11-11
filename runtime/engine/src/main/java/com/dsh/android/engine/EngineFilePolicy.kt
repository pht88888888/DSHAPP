package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** 拥有 Android 工作区入口和凭据文件的宿主文件策略。 */
internal class EngineFilePolicy(
    private val filesDir: File,
    private val homeDir: File,
    private val dshHome: File,
) {
    /**
     * 在引擎 HOME 下建两个「选择工作区」的入口链接（幂等）。
     *
     * **为什么需要**：目录选择器一次只在**一棵**目录树里走，而 Android 上
     * 手机存储与 app 私有目录**互不可达** —— app 域连 `readdir("/")`、
     * `readdir("/data")` 都是 EACCES（实测表见 [patchAttachmentDurability]），
     * 所以起点无论放在哪一边，另一边都永远进不去。
     *
     * **做法**：起点留在 HOME（客户端 `displayCrumbs()` 会把 HOME 折叠成一项
     * 「主目录」），在 HOME 下建两个符号链接当入口：
     *
     * ```
     * filesDir/home/手机存储  → /sdcard      选手机上的任意文件夹
     * filesDir/home/应用目录  → filesDir     选/建 app 私有目录里的文件夹
     * ```
     *
     * 用户在对话框里看到的就是「主目录」下的两个入口，点进去各走各的树，
     * 点面包屑上的「主目录」就能回来 —— 有入口、也有向上导航。
     *
     * ⚠️ **链接只能建在 app 私有目录这一侧**：`/sdcard` 上 `symlink()` 返回
     * **EPERM**（FUSE/sdcardfs 不支持），所以不能反过来把私有目录的入口放进
     * 手机存储。真机实测：私有目录里建链接成功，`/sdcard` 上失败。
     *
     * ⚠️ **browse 后端会跟随符号链接**：`directoryRow()` 对 `isSymbolicLink()`
     * 的条目补一次 `stat()`，是目录就照常列为可进入的文件夹。真机实测两个链接
     * 都 `enterable=true`，且经链接列目录（145 项）、建文件夹、写文件全部成功。
     *
     * ⚠️ 读得到 `/sdcard` 靠的是 manifest 里已申请的 `READ_EXTERNAL_STORAGE` /
     * `WRITE_EXTERNAL_STORAGE`（targetSdk 28，legacy 存储模型）。用户拒绝该权限
     * 时「手机存储」那一行 `stat()` 失败 ⇒ `directoryRow()` 返回 null ⇒
     * **该行不显示**（官方逻辑，不会崩），只剩「应用目录」可用。
     *
     * ⚠️ 建链接失败只打日志，不让启动失败。
     */
    fun ensurePickerRoots() {
        val home = homeDir
        if (!home.isDirectory) {
            Log.w(TAG, "HOME 不存在，跳过工作区入口链接: ${home.absolutePath}")
            return
        }

        // 名字会显示在对话框里、也会出现在所选工作区的路径中，用一眼能懂的词。
        val roots = listOf("手机存储" to File("/sdcard"), "应用目录" to filesDir)
        for ((name, target) in roots) {
            val link = File(home, name)
            try {
                val path = link.toPath()
                // ⚠️ 用 isSymbolicLink 而不是 exists()：断链时 exists() 返回 false，
                // 会让我们去 createSymbolicLink 一个已经存在的链接名（EEXIST）。
                if (Files.isSymbolicLink(path)) {
                    if (Files.readSymbolicLink(path).toString() == target.absolutePath) {
                        continue // 已就位
                    }
                    Files.delete(path) // 指向变了（换过包名/清过数据），重建
                } else if (link.exists()) {
                    Log.w(TAG, "工作区入口被同名文件占位，跳过: ${link.absolutePath}")
                    continue
                }
                Files.createSymbolicLink(path, target.toPath())
                Log.i(TAG, "已建工作区入口: $name → ${target.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "建工作区入口失败（$name）: ${t.message}")
            }
        }
    }

    /**
     * 修掉 `$DSH_HOME/.credentials.yaml` 过宽的权限位（`0600` 之外的组/其他位）。
     *
     * **症状（致命）**：引擎**起不来**，界面上是「引擎启动失败（引擎进程已退出）」，
     * 而真正的报错在 `engine.log` 里：
     *
     * ```
     * dsh: startup failed: 1 required plugin did not activate
     * Failed plugins (1):
     *   credentials
     *     Package: @deepseek-ai/dsh-credentials-local
     *     Error: credentials-local: <$DSH_HOME>/.credentials.yaml is readable
     *            beyond its owner (mode 660); run "chmod 600 …" before starting again
     * ```
     *
     * 而且**不是只坏一个插件** —— `credentials` 是 `connection` / `authorization` /
     * `deepseek-account` / `session-controller` / `file-upload` … 共 **9 个**插件的
     * 前置依赖，它一挂全部 `pending`，引擎判定「必需插件没起来」直接退出。
     * 用户看到的是整个 App 不可用，与「凭据文件权限」毫无表面关联。
     *
     * **根因**：`dsh-credentials-local` 的 `assertOwnerOnly()` 是**纯校验、不自愈** ——
     * 读文件前先 `stat`，只要 `mode & 0o077` 非零就 `throw`，从不尝试 `chmod`。
     * 而引擎自己写入走的是 `writeFileAtomic(..., { mode: 0o600 })`（临时文件
     * `flag: 'wx'` 建好后 rename），写出来的**必然**是 0600。
     *
     * 所以 660 一定是**引擎之外**写成的，实测来自这几类操作（都会落到这个路径）：
     *  · 用户/工具 `cp -p`、`unzip`、`tar -x` 解压出带组写位的副本；
     *  · `adb push`、文件管理器、第三方终端 App 代写；
     *  · 老版本或手工 `chmod` 留下的残留。
     * 这些在**换手机/迁移 `$DSH_HOME`** 之后特别容易命中。
     *
     * ⚠️ **为什么必须由 App 自愈，而不是让用户手敲 `chmod 600`**：
     *  ① 报错文案里那条命令，普通用户根本没有终端可用（这正是「Termux 内置
     *     在 App 里」的代价）—— 而且 `$DSH_HOME` 在 app 私有目录，非 root 也进不去；
     *  ② 这是**永久性砖**：引擎每次启动都在同一个文件上失败，不自愈就永远进不去，
     *     唯一的出路变成「卸载重装」，而卸载会清掉整个 `$DSH_HOME`
     *     （会话/凭据/工作区全没）—— 代价远大于修一个权限位。
     *
     * ⚠️ **只收窄、不开放**：目标就是 0600，绝不把权限改宽，也不碰文件内容。
     *  这个文件里是 API 凭据，收窄权限本身就是它的安全前提。
     *
     * ⚠️ 幂等：已经是 0600 时不写盘、不打日志（开机会走一次，不能刷屏）。
     *
     * ⚠️ 找不到文件 = 正常（尚未存过凭据），静默返回。
     *
     * 定位见 `dsh-credentials-local/lib/index.js` 的 `assertOwnerOnly()`（L92-106）
     * 与 `GROUP_OTHER_BITS = 63`（L64）。
     */
    fun ensureCredentialFileMode() {
        val file = File(dshHome, CREDENTIALS_FILE)
        if (!file.isFile) return
        try {
            val path = file.toPath()
            // ⚠️ 顺序要紧：**先读旧值、再无条件收窄**。
            //   反过来的话（先判断就 return），一旦 getPosixFilePermissions
            //   抛异常就会直接跳到 catch，结果是「本该修的没修」—— 这个函数
            //   就白写了。而 setPosixFilePermissions 本身幂等：已经是 0600 时
            //   只多一次 syscall，没有可见副作用。
            val before = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
            // 判据与引擎**逐位对齐**：引擎只拒绝「组/其他位非零」
            // （`assertOwnerOnly` 的 `mode & 0o077`）。所以 0700 这种带属主执行位
            // 但组/其他位全零的文件，引擎**本来就接受**，不该去动它 ——
            // 否则我们会打出「过宽，会让引擎启动失败」这句不成立的日志。
            // 读取失败（before == null）时保守处理：照修。
            if (before != null && before.none { it in GROUP_OTHER_BITS }) return
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
            Log.w(
                TAG,
                "已收窄凭据文件权限为 0600（此前 " +
                    (before?.let { posixModeText(it) } ?: "无法读取") +
                    " 的组/其他位非零，会让引擎直接启动失败）: ${file.absolutePath}"
            )
        } catch (t: Throwable) {
            // 修不了也让启动继续：引擎那边若仍拒绝，engine.log 里会有它自己的原文报错
            Log.w(TAG, "收窄凭据文件权限失败: ${t.message}")
        }
    }

    /** 把权限集合渲染成 `rw-rw----` 这种九字符形式，只用于日志。 */
    private fun posixModeText(perms: Set<PosixFilePermission>): String {
        fun triad(
            read: PosixFilePermission,
            write: PosixFilePermission,
            execute: PosixFilePermission,
        ): String =
            (if (perms.contains(read)) "r" else "-") +
                (if (perms.contains(write)) "w" else "-") +
                (if (perms.contains(execute)) "x" else "-")

        return triad(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ) + triad(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
        ) + triad(
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}
