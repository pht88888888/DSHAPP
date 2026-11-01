package com.dsh.android.backup

import android.content.Context
import android.util.Log
import com.dsh.android.core.DshApplication
import com.dsh.android.log.LogContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把**用户数据**打成一个 zip，落到用户找得到的目录。
 *
 * ## 为什么需要它（不是锦上添花）
 *
 * 用户遇到「引擎起不来」时，唯一的自救手段是卸载重装 —— 而卸载会
 * **清掉整个 App 私有目录**，会话、凭据、工作区、自己装的插件全部消失。
 * 所以用户宁可忍着也不用重装，App 就永久卡死。
 *
 * 这个导出器提供的是**卸载前的最后一条退路**：只要 App 还能打开到启动层
 * （引擎挂没挂都行），就能把数据拿走。
 *
 * ## 为什么是纯 Kotlin、不依赖引擎
 *
 * 它必须在「引擎完全起不来」时也能用。所以：
 * - 不读引擎状态、不连 WebView、不等任何异步初始化
 * - 只做文件系统操作（`filesDir` 下的目录直接读）
 * - 目录不存在就跳过，不报错
 *
 * ## 导出什么 / 不导出什么
 *
 * **导出**（用户独有、重建不出来的）：
 * ```
 * $DSH_HOME/sessions/        对话记录
 * $DSH_HOME/storages/        引擎键值存储
 * $DSH_HOME/profiles/        配置 + 用户插件
 * $DSH_HOME/skills/          技能
 * $DSH_HOME/deskpet/         用户装的桌宠
 * $DSH_HOME/.credentials.yaml  API 密钥
 * filesDir/workspace/        工作区（用户文件）
 * ```
 *
 * **不导出**（能从 APK 资产重新解出来的，占 300+ MB）：
 * ```
 * engine/       引擎解包目录
 * usr/          Termux 环境（node / pnpm）
 * mobile-ui/ 等 我们注入的插件
 * logs/         日志（另有专门的诊断包导出）
 * ```
 *
 * 这样包体积从 300+ MB 降到几 MB ~ 几十 MB，用户手机装得下、传得动。
 */
object UserDataExporter {

    private const val TAG = "UserDataExport"

    /**
     * 可导出的项。
     *
     * @param id 稳定标识（勾选框用，不要翻译）
     * @param label 显示名
     * @param path 相对 `filesDir` 的路径
     * @param risky 是否需要在界面上给用户额外提示
     */
    data class Item(
        val id: String,
        val label: String,
        val path: String,
        val risky: Boolean = false,
    )

    /** 全部可导出项。**顺序即界面顺序**。 */
    val ITEMS: List<Item> = listOf(
        Item("sessions", "对话记录", DshApplication.DSH_HOME_REL + "/sessions"),
        // ⚠️ **附件必须导出**，否则换设备/重装后会话里的图片全变成裂图。
        //
        //   群友反馈原话：「如果跨版本进行数据迁移的话，可能不知道为什么
        //   会读取不到图片」—— 原因就是这一项原来**不在清单里**：
        //   图片本体存在 `$DSH_HOME/attachments/v1/objects/<前2位>/<id>`，
        //   而 `sessions/` 里存的只是**引用 id**。只导出 sessions 的话，
        //   会话结构完整、消息也在，但每一张图都指向一个不存在的对象，
        //   界面上就是一片裂图。
        //
        //   ⚠️ 顺序放在 sessions 紧后面：两者是一体的，界面上挨着看才不容易
        //     只勾一个。
        Item("attachments", "图片与附件", DshApplication.DSH_HOME_REL + "/attachments"),
        Item("profiles", "配置与插件", DshApplication.DSH_HOME_REL + "/profiles"),
        Item("storages", "引擎数据", DshApplication.DSH_HOME_REL + "/storages"),
        Item("skills", "技能", DshApplication.DSH_HOME_REL + "/skills"),
        Item("deskpet", "桌面宠物", DshApplication.DSH_HOME_REL + "/deskpet"),
        Item("workspace", "工作区文件", "workspace"),
        // ⚠️ 凭据是明文 API key，落到 /sdcard/Download 后任何 App 都能读。
        //   默认**不勾**，由用户自己决定（见 ExportOptions）。
        Item("credentials", "API 密钥", DshApplication.DSH_HOME_REL + "/.credentials.yaml", risky = true),
    )

    /** 用户勾了哪些。 */
    data class Options(
        val ids: Set<String> = defaultIds(),
    ) {
        companion object {
            /** 默认勾选：用户独有、体积可控、无安全风险的那些。 */
            fun defaultIds(): Set<String> =
                setOf("sessions", "attachments", "profiles", "storages", "skills")
        }
    }

    /** 单项的体积统计结果。 */
    data class Size(
        val id: String,
        val bytes: Long,
        val files: Int,
        val exists: Boolean,
    )

    /**
     * 统计某一项有多大（递归）。
     *
     * ⚠️ 只用于**界面展示**，所以用粗略统计即可 —— 不要为了精确而把
     *   几十万个文件的目录完整遍历两遍（用户点开对话框会卡住）。
     */
    fun sizeOf(context: Context, item: Item): Size {
        val f = File(context.filesDir, item.path)
        if (!f.exists()) return Size(item.id, 0, 0, false)
        if (f.isFile) return Size(item.id, f.length(), 1, true)

        var bytes = 0L
        var files = 0
        // 用栈而不是递归：目录很深时递归会 StackOverflow
        val stack = ArrayDeque<File>()
        stack.addLast(f)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c)
                else {
                    bytes += c.length()
                    files++
                }
            }
        }
        return Size(item.id, bytes, files, true)
    }

    /**
     * 执行导出。
     *
     * @param onProgress 进度回调（0..1），在**调用线程**上调用 —— 调用方自己
     *   决定要不要 post 到主线程。
     * @param onError 失败原因回调，在**调用线程**上调用。传给人看的短句
     *   （「存储空间不足」这类），调用方负责显示到界面上。
     *   ⚠️ 手机上看不到 logcat，失败原因**必须能上屏**，否则用户只会看到
     *   「导出失败」四个字，除了反复重试没有别的办法 —— 这正是以前的状态。
     * @return 写出的 zip 文件；失败返回 null。
     */
    fun export(
        context: Context,
        options: Options,
        onProgress: ((Float) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ): File? {
        val selected = ITEMS.filter { it.id in options.ids && File(context.filesDir, it.path).exists() }
        if (selected.isEmpty()) {
            Log.w(TAG, "没有可导出的内容（勾选项都不存在）")
            return null
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "dsh-backup-$stamp.zip"

        // ⚠️ 优先写公共 Downloads；拿不到（没授权）就退回私有目录。
        //   LogContext.publicExportDirOrNull() 已经处理了这个降级，直接复用。
        val outDir = LogContext.publicExportDirOrNull() ?: context.filesDir
        val target = File(outDir, name)

        // 先写临时文件再 rename：中途失败不会留下半个 zip 让用户以为导成功了
        val tmp = File(outDir, ".$name.part")

        return try {
            // ⚠️ 进度分母。这里会**再遍历一遍**所有选中目录（`sizeOf` 要 stat
            //   每个文件）。加入 `attachments/` 之后这一步可能上万次系统调用，
            //   所以放后台线程（调用方本来就开了线程）。分母只影响进度条百分比，
            //   拿不准时偏大一点无害（进度会慢一点到 100%），偏小则会超过 100%。
            val totalBytes = selected.sumOf { sizeOf(context, it).bytes }.coerceAtLeast(1)
            var written = 0L
            /*
             * 打不开的文件（权限、竞态删掉、崩溃留下的坏文件）记在这里，**跳过继续**。
             *
             * 见 [writeEntryOrSkip]：一个读不了的文件不该让整包备份作废。
             */
            val skipped = mutableListOf<String>()

            ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                // 先写一份清单，恢复时靠它识别「这是我们自己的备份包」
                zos.putNextEntry(ZipEntry("DSH-BACKUP.txt"))
                zos.write(
                    buildString {
                        appendLine("DSH Android 用户数据备份")
                        appendLine("导出时间: $stamp")
                        appendLine("包含项:")
                        for (s in selected) appendLine("  - ${s.id}  (${s.path})")
                        appendLine()
                        appendLine("恢复方式: 把本 zip 内的目录按路径放回 App 私有目录。")
                        appendLine("⚠️ 本包不含引擎（engine/）与 Termux 环境（usr/），它们由 APK 重新解包。")
                    }.toByteArray()
                )
                zos.closeEntry()

                for (item in selected) {
                    val src = File(context.filesDir, item.path)
                    if (!src.exists()) continue
                    if (src.isFile) {
                        if (writeEntryOrSkip(zos, item.path, src, skipped)) written += src.length()
                    } else {
                        // 逐文件写：大目录不会一次性读进内存
                        val stack = ArrayDeque<Pair<File, String>>()
                        stack.addLast(src to item.path)
                        while (stack.isNotEmpty()) {
                            val (dir, relBase) = stack.removeLast()
                            val children = dir.listFiles() ?: continue
                            for (c in children) {
                                val rel = "$relBase/${c.name}"
                                if (c.isDirectory) {
                                    stack.addLast(c to rel)
                                } else {
                                    if (!writeEntryOrSkip(zos, rel, c, skipped)) continue
                                    written += c.length()
                                    onProgress?.invoke((written.toFloat() / totalBytes).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                /*
                 * 有跳过的文件就在包里留一份清单 —— 备份包必须**自解释**。
                 *
                 * 否则用户拿到一个「导出成功」的包，恢复时才发现某几个 session
                 * 没进去，而那时谁也不知道当时缺了什么、为什么缺。
                 */
                if (skipped.isNotEmpty()) {
                    zos.putNextEntry(ZipEntry("DSH-BACKUP-SKIPPED.txt"))
                    zos.write(
                        buildString {
                            appendLine("以下 ${skipped.size} 个文件没能打进备份（其余已正常写入）:")
                            for (line in skipped) appendLine("  - $line")
                        }.toByteArray(),
                    )
                    zos.closeEntry()
                    Log.w(TAG, "导出时跳过了 ${skipped.size} 个读不了的文件（清单已写入包内）")
                }
            }

            if (!tmp.renameTo(target)) {
                // 有些文件系统 rename 跨目录会失败，退回复制
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "用户数据已导出: ${target.absolutePath} (${target.length()} 字节)")
            target
        } catch (t: Throwable) {
            Log.w(TAG, "导出失败: ${t.message}", t)
            runCatching { tmp.delete() }
            onError?.invoke(describeFailure(t))
            null
        }
    }

    /**
     * 把异常翻成人能看懂的一句话。
     *
     * 导出失败最常见的就是**空间不足**（附件目录可以有几个 GB），而
     * `IOException: write failed: ENOSPC` 这种文本对用户毫无意义，
     * 所以单独认出来，其它情况退回异常自身的描述。
     */
    private fun describeFailure(t: Throwable): String {
        val msg = (t.message ?: "").lowercase()
        return when {
            msg.contains("enospc") || msg.contains("no space") ->
                "存储空间不足，换个位置或清理后重试（图片与附件通常最占空间）"
            msg.contains("eacces") || msg.contains("permission denied") ->
                "没有写入权限，请在系统设置里允许本应用访问存储"
            msg.contains("erofs") -> "目标位置是只读的"
            else -> t.message?.take(120)?.let { "导出失败：$it" } ?: "导出失败（未知原因）"
        }
    }

    private fun writeEntry(zos: ZipOutputStream, path: String, file: File) {
        zos.putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /**
     * 写一个条目，**单个文件打不开不拖垮整包**。
     *
     * ## 为什么需要
     *
     * 原来遍历里直接调 [writeEntry]：任何一个文件在 `inputStream()` 上抛
     * （`EACCES` 权限、`ENOENT` 遍历期间被删、崩溃留下的坏文件），异常一路
     * 冒到 [export] 的 catch，**整包备份作废** —— 用户的附件目录动辄上万个文件，
     * 只要有一个怪文件，导出就永远是「导出失败」。
     *
     * ## 边界：只管「打开阶段」的失败
     *
     * `ZipOutputStream` 不是事务性的：`putNextEntry` 之后写到一半失败，
     * 这个 entry 的字节已经在流里了，**没法回滚** —— 继续写会得到损坏的 zip。
     * 所以读到一半抛错时仍然让整包失败（那是真的 I/O 故障，不该假装成功）。
     * 这里挡的是「打开就能判断出来」的那一类，也正是线上最常见的那一类。
     *
     * @return true = 已写入；false = 已跳过并记进 [skipped]。
     */
    private fun writeEntryOrSkip(
        zos: ZipOutputStream,
        path: String,
        file: File,
        skipped: MutableList<String>,
    ): Boolean {
        val stream = try {
            file.inputStream().buffered()
        } catch (t: Throwable) {
            skipped += "$path（${t.javaClass.simpleName}: ${t.message?.take(80) ?: "无详情"}）"
            return false
        }
        stream.use {
            zos.putNextEntry(ZipEntry(path))
            it.copyTo(zos)
            zos.closeEntry()
        }
        return true
    }

    /** 人类可读的体积文案。 */
    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }
}
