package com.dsh.android.backup

import android.content.Context
import android.util.Log
import com.dsh.android.core.DshApplication
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 把 [UserDataExporter] 导出的 zip 解回 App 私有目录。
 *
 * ## 为什么导入也要在启动层
 *
 * 用户的完整流程是：
 *
 * ```
 * 引擎坏了打不开 → 启动层「导出数据」→ 卸载重装 → 启动层「导入数据」→ 恢复
 * ```
 *
 * 而**重装后第一次启动时引擎还没起来**，所以导入同样不能依赖引擎。
 *
 * ## 导入时必须防的三件事
 *
 * ### ① 路径穿越（Zip Slip）
 *
 * zip 里的条目名可能是 `../../shared_prefs/xxx.xml`。直接 `File(dest, name)`
 * 会写到目标目录之外。**必须先规范化再校验前缀**。
 *
 * ### ② 覆盖前必须备份
 *
 * 用户可能在新装的环境里已经建了新会话，导入会覆盖掉。所以每一项落地前
 * 先把它改名成 `<原名>.bak-<时间戳>`（与安全模式同一套 quarantine 语义：改名不删）。
 *
 * ### ③ 清单与实际内容要一致
 *
 * 用户导出的包里可能只有 `sessions/`。导入界面必须**先解析出包里到底有什么**，
 * 让用户勾选，而不是假定七项都在。
 */
object UserDataImporter {

    private const val TAG = "UserDataImport"

    /** zip 内的清单文件名（导出时写的）。 */
    const val MANIFEST_NAME = "DSH-BACKUP.txt"

    /**
     * 预扫描结果：这个包里有哪些可导入的项。
     *
     * @param id 与 [UserDataExporter.ITEMS] 的 id 对应
     * @param entries 属于该项的 zip 条目数
     * @param bytes 解压后的总字节数
     */
    data class Found(
        val id: String,
        val label: String,
        val entries: Int,
        val bytes: Long,
    )

    /** 预扫描：**不解压**，只读中央目录，快速报告包内容。 */
    fun inspect(zip: File): List<Found> {
        if (!zip.isFile) return emptyList()

        // 先按 id 归类统计。条目名形如 `home/.dsh/sessions/<sid>/session.v4.jsonl.zstd`
        val agg = LinkedHashMap<String, Pair<Int, Long>>()

        return try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (!e.isDirectory && name != MANIFEST_NAME) {
                        val id = matchId(name)
                        if (id != null) {
                            val cur = agg[id] ?: (0 to 0L)
                            agg[id] = (cur.first + 1) to (cur.second + e.size.coerceAtLeast(0))
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            agg.mapNotNull { (id, v) ->
                val item = UserDataExporter.ITEMS.firstOrNull { it.id == id } ?: return@mapNotNull null
                Found(item.id, item.label, v.first, v.second)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "预扫描失败: ${t.message}")
            emptyList()
        }
    }

    /** 条目名 → 可导入项的 id；不属于任何项返回 null。 */
    private fun matchId(entryName: String): String? {
        for (item in UserDataExporter.ITEMS) {
            if (entryName == item.path || entryName.startsWith(item.path + "/")) {
                return item.id
            }
        }
        return null
    }

    /** 解压统计。 */
    data class Result(
        val entries: Int,
        val bytes: Long,
        val failed: Int,
        /** 被覆盖前备份出来的目录（用户想反悔时找这里）。 */
        val backupDir: File?,
        /** 跨 flavor 修正了多少个配置文件的路径。 */
        val fixedPaths: Int = 0,
    )

    /**
     * 两个 flavor 的 `applicationId`。
     *
     * ⚠️ **必须写死这两个值，不能猜。** 它们由 `build.gradle.kts` 的 flavor
     *   决定（system = `com.dsh.android`、gecko = `com.dsh.webview`），
     *   而本函数要在**导入另一个 flavor 的备份**时把路径改写成当前包名。
     *   写死的代价只是「以后新增 flavor 要同步这里」，比正则瞎猜安全得多。
     */
    private val KNOWN_PACKAGES = listOf("com.dsh.android", "com.dsh.webview")

    /**
     * 把配置文本里**指向另一个 flavor 私有目录**的绝对路径改写成当前包名。
     *
     * ## 为什么需要
     *
     * 备份包内部用的是**相对路径**（`home/.dsh/sessions`），所以目录结构本身
     * 跨 flavor 通用。但 `cordis.patch.yml` 是**用户层配置**，里面可能有
     *
     * ```yaml
     * name: 'file:///data/user/0/com.dsh.android/files/home/.dsh/xxx/lib/index.js'
     * ```
     *
     * 这种绝对路径。从 system 导到 gecko 时，路径指向的是**另一个 App 的私有
     * 目录** —— 那个目录在当前进程里根本不存在，插件加载失败。
     *
     * ## 只改这一种形态
     *
     * 只替换 `/data/user/0/<已知包名>/` 与 `/data/data/<已知包名>/`
     * （后者是旧路径形式，部分配置里还留着）。**不碰任何其它内容** ——
     * 用户的配置里可能有别的 App 路径、或他自己写死的绝对路径，那不该动。
     *
     * @return 改写后的文本；没有需要改的就原样返回（调用方可用 === 判断跳过写盘）
     */
    private fun rewritePackagePaths(text: String, currentPkg: String): String {
        var out = text
        for (pkg in KNOWN_PACKAGES) {
            if (pkg == currentPkg) continue   // 已经是当前包名，不动
            for (prefix in listOf("/data/user/0", "/data/data")) {
                out = out.replace("$prefix/$pkg/", "$prefix/$currentPkg/")
            }
        }
        return out
    }

    /** 需要做路径改写的文件（相对 filesDir）。 */
    private val REWRITE_FILES = listOf(
        "home/.dsh/profiles/web/cordis.patch.yml",
        "home/.dsh/profiles/rescue/cordis.patch.yml",
        "home/.dsh/profiles/web/package.json",
        "home/.dsh/profiles/rescue/package.json",
    )

    /**
     * 解压完成后，把配置里的跨 flavor 绝对路径改成当前包名。
     *
     * @return 实际改写的文件数
     */
    private fun fixCrossFlavorPaths(context: Context, ids: Set<String>): Int {
        if ("profiles" !in ids) return 0
        val currentPkg = context.packageName
        var fixed = 0
        for (rel in REWRITE_FILES) {
            val f = File(context.filesDir, rel)
            if (!f.isFile) continue
            try {
                val src = f.readText()
                val out = rewritePackagePaths(src, currentPkg)
                if (out != src) {
                    f.writeText(out)
                    fixed++
                    Log.i(TAG, "已修正跨版本路径: $rel → $currentPkg")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "修正路径失败 $rel: ${t.message}")
            }
        }
        return fixed
    }

    /**
     * 执行导入。
     *
     * @param ids 用户勾选了哪些项（来自 [inspect] 的结果）
     * @param onProgress 0..1
     * @return 结果；彻底失败（包读不了）返回 null
     */
    fun restore(
        context: Context,
        zip: File,
        ids: Set<String>,
        onProgress: ((Float) -> Unit)? = null,
    ): Result? {
        if (!zip.isFile || ids.isEmpty()) return null

        val filesDir = context.filesDir
        val stamp = System.currentTimeMillis()

        // ① 覆盖前把现有数据改名备份（改名不删，与安全模式同一语义）
        val backupRoot = File(filesDir, "backup-before-import-$stamp")
        var backedUp = false
        for (item in UserDataExporter.ITEMS) {
            if (item.id !in ids) continue
            val dst = File(filesDir, item.path)
            if (!dst.exists()) continue
            val b = File(backupRoot, item.path)
            b.parentFile?.mkdirs()
            if (dst.renameTo(b)) backedUp = true
        }

        // ② 解压
        var entries = 0
        var bytes = 0L
        var failed = 0
        val totalHint = inspect(zip).filter { it.id in ids }.sumOf { it.bytes }.coerceAtLeast(1)

        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name != MANIFEST_NAME) {
                        val id = matchId(e.name)
                        if (id != null && id in ids) {
                            val target = safeResolve(filesDir, e.name)
                            if (target == null) {
                                // ⚠️ 路径穿越：直接跳过，不写、不报错中断
                                Log.w(TAG, "跳过可疑路径: ${e.name}")
                                failed++
                            } else {
                                try {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().buffered().use { out ->
                                        zis.copyTo(out)
                                    }
                                    entries++
                                    bytes += e.size.coerceAtLeast(0)
                                    onProgress?.invoke((bytes.toFloat() / totalHint).coerceIn(0f, 1f))
                                } catch (t: Throwable) {
                                    Log.w(TAG, "写入失败 ${e.name}: ${t.message}")
                                    failed++
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "导入中断: ${t.message}")
            return null
        }

        // ③ 跨 flavor 修正：把配置里指向**另一个包名**的绝对路径改成当前包名。
        //
        // 备份包内部用相对路径，所以目录结构天然跨 flavor 通用；但用户层配置
        // `cordis.patch.yml` 里可能有 `file:///data/user/0/com.dsh.android/...`
        // 这种绝对路径 —— 导到 gecko（`com.dsh.webview`）后那些路径指向
        // 另一个 App 的私有目录，插件必然加载失败。见 [fixCrossFlavorPaths]。
        val fixedPaths = fixCrossFlavorPaths(context, ids)

        Log.i(TAG, "导入完成: $entries 个文件, $bytes 字节, 失败 $failed, 修正路径 $fixedPaths 处")
        return Result(entries, bytes, failed, if (backedUp) backupRoot else null, fixedPaths)
    }

    /**
     * 把 zip 条目名解析成 `filesDir` 下的安全路径。
     *
     * ⚠️ **必须 `canonicalPath` 再判前缀**：只判 `startsWith("..")` 不够，
     *   `a/../../b` 这种就能绕过。
     */
    private fun safeResolve(root: File, entryName: String): File? {
        return try {
            val rootPath = root.canonicalPath + File.separator
            val target = File(root, entryName).canonicalFile
            if (target.path.startsWith(rootPath) || target.path == root.canonicalPath) target else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 从公共目录里找可导入的备份包（最新的在前）。 */
    fun findBackups(context: Context): List<File> {
        val dirs = buildList {
            @Suppress("DEPRECATION")
            android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?.let { add(it) }
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { add(it) }
        }
        val out = ArrayList<File>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            d.listFiles { f ->
                f.isFile && f.name.startsWith("dsh-backup-") && f.name.endsWith(".zip")
            }?.let { out.addAll(it) }
        }
        return out.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    /** 方便界面显示：`home/.dsh/xxx` → `xxx`。 */
    fun describePath(id: String): String =
        UserDataExporter.ITEMS.firstOrNull { it.id == id }?.path ?: id

    /** 未被任何项覆盖的路径前缀（调试用）。 */
    fun homePrefix(): String = DshApplication.DSH_HOME_REL
}
