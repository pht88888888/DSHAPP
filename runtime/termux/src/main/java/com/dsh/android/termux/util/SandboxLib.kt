package com.dsh.android.termux.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 把 `libboosandbox.so` 从 APK 解到真实文件路径，供 `LD_PRELOAD` 使用。
 *
 * 为什么需要这一步：AGP 默认 `extractNativeLibs=false`（minSdk≥23），native 库被
 * 直接映射在 APK 内，`applicationInfo.nativeLibraryDir` 是**空目录**（真机实测），
 * 而 `LD_PRELOAD` 只接受真实文件路径。
 *
 * ⚠️ 抽成独立且**不依赖 Android 框架**的类，是为了让这段逻辑能被 JVM 单测覆盖。
 * 它是安全链路的一环（沙箱库解不出来 ⇒ bash 内的写入完全没有约束），却曾经
 * 因为「缓存键只含 versionCode」而**静默地把安全修复挡在用户之外**（见 [markerName]）。
 * 这种缺陷靠真机走查很难发现 —— 装机后一切"正常"，只是跑的是旧库。
 */
object SandboxLib {

    /** APK 内的路径模板。 */
    fun apkEntry(abi: String): String = "lib/$abi/libboosandbox.so"

    /**
     * 缓存标记的文件名。
     *
     * 🔴 **必须包含库内容的哈希**（2026-09-26 真机踩到）：最初这里是
     * `.v$versionCode-$abi`，只在**升级安装**时失效。于是只要重编了
     * `libboosandbox.so` 而 versionCode 没变（开发期常态；发版也未必为一次安全
     * 修复改版本号），老装机就**永远继续用那个旧的 .so**，且没有任何报错。
     *
     * 实测：修掉沙箱 `..` 逃逸后装机验证，设备上跑的仍是几天前那个 12,600 字节的
     * 旧库，标记却"匹配"，静默跳过解压 —— 修复等于没发出去。
     *
     * 带上哈希后，内容一变必然重解；顺带解决"写到一半断电留下半截 .so"：
     * 半截文件哈希对不上，下次启动重解。
     */
    fun markerName(versionCode: Long, abi: String, contentHash: String): String =
        ".v$versionCode-$abi-$contentHash"

    /** 内容哈希取前 8 字节（16 个十六进制字符）——够区分，又不至于让文件名过长。 */
    fun contentHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8)
            .joinToString("") { String.format("%02x", it) }

    /**
     * 从 [apk] 里读出 [abi] 对应的沙箱库字节。
     *
     * @return 库字节；APK 内缺少该条目时返回 `null`（调用方应告警而非崩溃）。
     */
    fun readFromApk(apk: File, abi: String): ByteArray? =
        ZipFile(apk).use { zip ->
            val ze = zip.getEntry(apkEntry(abi)) ?: return null
            zip.getInputStream(ze).use { input ->
                // ⚠️ 不用 `readAllBytes()` / `InputStream.transferTo()` —— Java 9+ API
                // 在 Android 上会 `NoSuchMethodError`（见 CLAUDE.md 的铁律）。
                val buf = ByteArrayOutputStream()
                input.copyTo(buf)
                buf.toByteArray()
            }
        }

    /** [ensureExtracted] 的结果，便于调用方打日志 / 断言。 */
    data class Result(
        /** 沙箱库的最终真实路径；失败时为 `null`。 */
        val path: File?,
        /** 本次是否真的重新解压了（false = 命中缓存）。 */
        val extracted: Boolean,
        /** 缺少 APK 条目时为 true。 */
        val missingInApk: Boolean = false,
        /** 内容哈希（[contentHash]）；无法读取时为 `null`。 */
        val hash: String? = null,
        /** 失败原因；成功时为 `null`。 */
        val error: String? = null,
    )

    /**
     * 确保 [destDir] 下有一份与 APK 内容一致的沙箱库，并返回其路径。
     *
     * 幂等：内容一致时直接复用，不重复写盘。
     *
     * ⚠️ 失败**不抛异常**：调用方（启动流程）不应因此中断。文件工具仍受
     * `Confine.confine()` 的进程内闸门约束，只有 bash 子进程内的写入会失去沙箱
     * —— 属于能力降级，不是崩溃。调用方负责把 [Result.error] / [Result.missingInApk]
     * 记进日志。
     */
    fun ensureExtracted(apk: File, abi: String, versionCode: Long, destDir: File): Result {
        return try {
            destDir.mkdirs()
            val dest = File(destDir, "libboosandbox.so")
            val bytes = readFromApk(apk, abi)
                ?: return Result(path = null, extracted = false, missingInApk = true)

            val hash = contentHash(bytes)
            val marker = File(destDir, markerName(versionCode, abi, hash))

            // 三重命中才算缓存有效：标记在、文件在、且长度与 APK 内一致。
            // 长度比对能挡住"标记写了但文件被截断/清空"的情况。
            if (marker.exists() && dest.exists() && dest.length() == bytes.size.toLong()) {
                return Result(path = dest, extracted = false, hash = hash)
            }

            // 清掉所有旧标记再写新的，避免目录里堆积历史版本的标记文件。
            destDir.listFiles()?.forEach { if (it.name.startsWith(".v")) it.delete() }
            dest.writeBytes(bytes)
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            marker.writeText("ok")
            Result(path = dest, extracted = true, hash = hash)
        } catch (e: Exception) {
            Result(path = null, extracted = false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
