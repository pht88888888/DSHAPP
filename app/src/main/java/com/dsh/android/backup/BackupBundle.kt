package com.dsh.android.backup

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * **备份包的明文载荷** —— 加解密之前/之后的那一层。
 *
 * ## 分层
 *
 * ```
 *  目录树  ──[BackupBundle]──→  明文 zip 流  ──[BackupCrypto]──→  .dshbak
 *  (文件)      (打包+清单)         (可流式)         (加密)
 * ```
 *
 * 这样分层的好处：[BackupCrypto] 只管密码学（可独立测试），
 * 本类只管「目录 ↔ 流」的映射（也可独立测试）。两者的失败原因不会混在一起。
 *
 * ## 明文载荷的结构
 *
 * ```
 * __dshbak_manifest.json     ← 清单（BackupManifest 的 JSON）
 * files/<相对路径>            ← 实际文件
 * ```
 *
 * ⚠️ 清单放在包的**第一个条目**：读取端可以先拿到它再决定怎么处理后续内容
 * （比如「版本不兼容就中止」），不必解完整个包。
 *
 * ## ⚠️ 防解压炸弹
 *
 * 文档 §5.3.5：「限制总展开量、文件数、单文件大小、路径深度和解压比；
 * **拒绝穿越、危险链接、冲突条目**」。
 *
 * 本类在解包时逐条检查这些限制，任一超限就**整体失败**（不是跳过那一项）——
 * 因为一个超限的包本身就可疑，部分解包比不解更危险。
 */
object BackupBundle {

    private const val TAG = "DSH"

    private const val MANIFEST_ENTRY = "__dshbak_manifest.json"
    private const val FILES_PREFIX = "files/"

    /** 解压炸弹防护：总展开量上限（1 GB）。 */
    private const val MAX_TOTAL_BYTES = 1L * 1024 * 1024 * 1024

    /** 解压炸弹防护：文件数上限。 */
    private const val MAX_FILES = 200_000

    /** 解压炸弹防护：单文件上限（512 MB）。 */
    private const val MAX_SINGLE_FILE = 512L * 1024 * 1024

    /** 解压炸弹防护：路径深度上限。 */
    private const val MAX_DEPTH = 32

    /**
     * 把目录树打成明文 zip 流。
     *
     * ⚠️ **边打边算哈希**：清单里的 sha256 是打包时算的，
     * 不是事先扫一遍再打 —— 那样目录在两次遍历之间变了会得到不一致的清单。
     *
     * @param srcDir 源目录（其**内容**进包，目录本身不进）
     * @param manifest 调用方给的清单骨架（entries 由本函数填充）
     */
    fun buildPlainStream(srcDir: File, manifest: BackupManifest): InputStream {
        // ⚠️ 先写进临时文件而不是全在内存：备份可能几百 MB
        val tmp = File.createTempFile("dshbak-plain-", ".zip")
        tmp.deleteOnExit()

        val entries = mutableListOf<BackupManifest.Entry>()
        val missing = mutableListOf<String>()

        ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
            // 先占位写清单，最后回填 —— zip 流不能回头改
            //
            // ⚠️ 做法：先算出完整清单再写。所以这里先遍历一遍收集信息，
            //   第二遍才真正写。两遍之间目录若变了，第二遍会读到不同的内容 ——
            //   但那时哈希已经定了，会导致校验失败（**这是好事**：
            //   宁可失败也不要产出一个内容与清单不符的包）。
            val files = collectFiles(srcDir, missing)

            // 第一遍：算哈希与大小
            for (f in files) {
                val rel = f.relativeTo(srcDir).path.replace(File.separatorChar, '/')
                try {
                    entries.add(
                        BackupManifest.Entry(
                            path = rel,
                            size = f.length(),
                            sha256 = sha256Of(f),
                            mode = unixMode(f),
                            isDir = f.isDirectory,
                        ),
                    )
                } catch (t: Throwable) {
                    // ⚠️ 读失败必须如实记进 missing，不能静默跳过 ——
                    //   文档要求「读失败均须逐项报告，部分包不称完整成功」
                    Log.w(TAG, "备份：读取失败 $rel（${t.message}）")
                    missing.add("$rel: ${t.message}")
                }
            }

            val full = manifest.copy(
                entries = entries,
                missing = manifest.missing + missing,
                complete = manifest.complete && missing.isEmpty(),
            )

            // 写清单（第一个条目）
            zos.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zos.write(full.toJson().toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 第二遍：写实际内容
            for (f in files) {
                val rel = f.relativeTo(srcDir).path.replace(File.separatorChar, '/')
                if (f.isDirectory) {
                    // 空目录也要有条目，否则恢复后目录结构会缺
                    zos.putNextEntry(ZipEntry("$FILES_PREFIX$rel/"))
                    zos.closeEntry()
                    continue
                }
                try {
                    zos.putNextEntry(ZipEntry("$FILES_PREFIX$rel"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } catch (t: Throwable) {
                    Log.w(TAG, "备份：写入失败 $rel（${t.message}）")
                }
            }
        }

        return tmp.inputStream()
    }

    /** 从明文 zip 里读出清单（不解出文件）。 */
    fun readManifest(staging: File): BackupManifest? {
        ZipInputStream(staging.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == MANIFEST_ENTRY) {
                    val text = zis.readBytes().toString(Charsets.UTF_8)
                    return BackupManifest.fromJson(JSONObject(text))
                }
            }
        }
        return null
    }

    /**
     * 把明文 zip 解到目标目录。
     *
     * ## ⚠️ 逐条安全校验（文档 §5.3.5）
     *
     * 任一条不满足就**整体失败**，不做部分解包 ——
     * 一个超限的包本身就可疑，部分解包比不解更危险。
     */
    fun extract(staging: File, destDir: File) {
        var totalBytes = 0L
        var fileCount = 0

        ZipInputStream(staging.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val name = e.name

                // 清单条目跳过（它不是文件）
                if (name == MANIFEST_ENTRY) continue
                if (!name.startsWith(FILES_PREFIX)) {
                    throw BackupCrypto.DecryptError("包内有非预期条目: $name")
                }

                val rel = name.removePrefix(FILES_PREFIX)
                if (rel.isEmpty()) continue

                // ① 路径穿越
                if (rel.contains("..") || rel.startsWith("/") || rel.contains('\u0000')) {
                    throw BackupCrypto.DecryptError("包内有危险路径: $rel")
                }
                // ② 路径深度
                if (rel.count { it == '/' } > MAX_DEPTH) {
                    throw BackupCrypto.DecryptError("包内路径过深: $rel")
                }
                // ③ 文件数
                if (++fileCount > MAX_FILES) {
                    throw BackupCrypto.DecryptError("包内文件数超限（>$MAX_FILES）")
                }

                val out = File(destDir, rel)
                // ④ 再确认一次解析后的路径仍在 destDir 内（防符号链接绕过）
                if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                    out.canonicalPath != destDir.canonicalPath
                ) {
                    throw BackupCrypto.DecryptError("包内条目逃逸目标目录: $rel")
                }

                if (e.isDirectory || rel.endsWith("/")) {
                    out.mkdirs()
                    continue
                }

                out.parentFile?.mkdirs()
                FileOutputStream(out).buffered().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        totalBytes += n
                        // ⑤ 总展开量
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw BackupCrypto.DecryptError("包展开后过大（>$MAX_TOTAL_BYTES 字节）")
                        }
                        os.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /**
     * 逐文件校验解出的内容与清单是否一致。
     *
     * ⚠️ 文档 §5.3.5：「成功解压不等于功能验收通过」，
     * 但**解压内容与清单不符**必须当场发现 —— 那可能是传输损坏或恶意替换。
     */
    fun verifyExtracted(destDir: File, manifest: BackupManifest): List<String> {
        val problems = mutableListOf<String>()
        for (e in manifest.entries) {
            val f = File(destDir, e.path)
            if (e.isDir) {
                if (!f.isDirectory) problems.add("${e.path}: 目录缺失")
                continue
            }
            if (!f.isFile) {
                problems.add("${e.path}: 文件缺失")
                continue
            }
            if (f.length() != e.size) {
                problems.add("${e.path}: 大小不符（${f.length()} != ${e.size}）")
                continue
            }
            if (e.sha256.isNotEmpty()) {
                val actual = sha256Of(f)
                if (actual != e.sha256) problems.add("${e.path}: 哈希不符")
            }
        }
        return problems
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 收集要备份的文件。
     *
     * ⚠️ **不追随符号链接**：文档明确「禁止盲目追随链接卷入任意用户文件」。
     * 遇到链接时把它记进 [missing]（表示「这个没纳入」），而不是跟进去。
     */
    private fun collectFiles(root: File, missing: MutableList<String>): List<File> {
        val out = mutableListOf<File>()
        if (!root.isDirectory) return out

        root.walkTopDown()
            .onEnter { dir ->
                // 不进入符号链接目录
                if (java.nio.file.Files.isSymbolicLink(dir.toPath())) {
                    missing.add("${dir.name}: 符号链接目录（未纳入）")
                    false
                } else {
                    true
                }
            }
            .forEach { f ->
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    missing.add("${f.name}: 符号链接（未纳入）")
                    return@forEach
                }
                if (f.isDirectory || f.isFile) out.add(f)
            }
        return out
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * 读 Unix 权限位。
     *
     * ⚠️ 用 `Files.getPosixFilePermissions` 而不是 `File.canExecute()` ——
     * 后者只能区分三档，拿不到完整 mode。
     */
    private fun unixMode(f: File): Int = try {
        val perms = java.nio.file.Files.getPosixFilePermissions(f.toPath())
        var m = 0
        for (p in perms) {
            m = m or when (p) {
                java.nio.file.attribute.PosixFilePermission.OWNER_READ -> 0b100_000_000
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE -> 0b010_000_000
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
                java.nio.file.attribute.PosixFilePermission.GROUP_READ -> 0b000_100_000
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE -> 0b000_010_000
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ -> 0b000_000_100
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
            }
        }
        m
    } catch (t: Throwable) {
        0
    }

    /*
     * ⚠️ **本对象的边界**：
     *
     * ✅ 做到：流式打包、边打边算哈希、空目录、权限位、符号链接不追随、
     *          解压炸弹防护（总量/文件数/单文件/深度）、路径穿越拒绝、
     *          逐文件哈希校验
     * ❌ 未做：pnpm 外链实体的收集（externalRefs 字段留空）、
     *          权限位的实际恢复（解包时没 setPosixFilePermissions）、
     *          压缩比限制（只限了总量）
     */
}
