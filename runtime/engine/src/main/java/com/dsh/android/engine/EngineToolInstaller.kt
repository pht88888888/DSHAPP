package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
/** 拥有随 APK 提供的附属工具和 skill 文件，保留各自的完成判据。 */
internal class EngineToolInstaller(
    private val assets: android.content.res.AssetManager,
    private val filesDir: File,
    private val dshHome: File,
) {
    /**
     * 解出 Agnes 多模态插件（`generate_image` / `generate_video` + 多模态配置页）。
     *
     * 与 [ensureMobileUi] 同一套做法、同一套理由：包解到 `$DSH_HOME/agnes-multimodal/`
     * 而不是 profile 的 `node_modules`（那里归 pnpm 管，本包不在 profile 的
     * `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`，
     * overlay 里的包名随即解析失败）。
     *
     * 幂等判据是版本戳而不是「文件在不在」：插件随 APK 升级而变，只看文件存在
     * 会让老设备永远停在旧版。
     */
    fun ensureAgnesMultimodal() {
        // ⚠️ **必须早于下面的版本戳提前返回**：本故障只出现在老设备上（版本戳已经
        // 是最新），放末尾就等于「永远不会执行」，修了个寂寞。
        //
        // 清理 profile 里的同名副本。老版本或插件管理器可能把本包装进
        // `profiles/<p>/node_modules/`，而 App 的 overlay 又按 `file://` 挂了同一份，
        // `dsh-client-modules` 的 `reconcilePackage()` 会判成「同一包来自两个
        // Loader 源」并抛错 ⇒ `modules (required)` 不激活 ⇒ **引擎起不来、界面打不开**：
        //
        //   client-modules: package dsh-agnes-multimodal resolves from multiple active
        //   Loader sources: "dsh-agnes-multimodal" from .../profiles/web/,
        //   "file://.../agnes-multimodal/dsh-agnes-multimodal/lib/index.js" from .../profiles/web/
        //
        // 与 [ensureMobileUi] 末尾那行**同构、同一套理由**（它清的是自己那份）。
        // 判据是「包解析得到 client bundle」而不是「目录在不在」：删掉后裸包名那条
        // entry 的 `locatePkgJson()` 返回 undefined，源数就回到 1。
        File(dshHome, "profiles").listFiles()?.forEach { profile ->
            val duplicate = File(profile, "node_modules/$AGNES_MM_PACKAGE")
            if (duplicate.exists() && duplicate.deleteRecursively()) {
                Log.i(TAG, "已清理 profile 里重复的 $AGNES_MM_PACKAGE 副本: ${profile.name}")
            }
        }

        val target = File(dshHome, "$AGNES_MM_DIR/$AGNES_MM_PACKAGE")
        val clientJs = File(target, "lib/client.js")
        val stamp = File(target, AGNES_MM_STAMP)

        if (clientJs.isFile && clientJs.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == AGNES_MM_VERSION
        ) {
            return
        }

        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(assets.open(AGNES_MM_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(AGNES_MM_VERSION)
            Log.i(TAG, "已安装 Agnes 多模态插件 $AGNES_MM_PACKAGE $AGNES_MM_VERSION (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了两个生成工具
            Log.w(TAG, "安装 Agnes 多模态插件失败: ${t.message}")
        }
    }

    /**
     * 把内置的**中文 skill** 解到 `$DSH_HOME/skills/`（幂等）。
     *
     * ## 为什么是这个落点
     *
     * `dsh-skill-filesystem` 的发现根里，`$DSH_HOME/skills`（`USER_DSH_RANK=400`）
     * 是**全局根**：预设行没写 `dshHome` 时它回落到 `$DSH_HOME`（[resolveDshHome]），
     * 而 `includeDefaultRoots` 默认 true —— 所以**每个预设都会扫到这个目录**，
     * 不需要改任何一份 `cordis.patch.yml`。这正是「用户有需求时直接注入」的接缝。
     *
     * ## ⚠️ 绝不整目录重建
     *
     * `$DSH_HOME/skills/` 是**用户自己的** skill 目录（他手写的 skill 也放这儿）。
     * 所以只删本 App 管的那几个子目录（[PRESET_SKILL_NAMES]），
     * **不能** `deleteRecursively()` 整个 `skills/` —— 那会删掉用户的东西。
     * 版本戳（[PRESET_SKILLS_STAMP]）同理放在 `skills/` 下而不是先清空它。
     *
     * 幂等判据是版本戳而不是「文件在不在」：skill 随 APK 升级而变，
     * 只看文件存在会让老设备永远停在旧版内容（与 [ensurePresetRazor] 同一取舍）。
     */
    fun ensurePresetSkills() {
        val root = File(dshHome, "skills")
        val stamp = File(root, PRESET_SKILLS_STAMP)

        if (stamp.isFile && stamp.readText().trim() == PRESET_SKILLS_VERSION) return

        try {
            root.mkdirs()
            for (name in PRESET_SKILL_NAMES) File(root, name).deleteRecursively()
            java.util.zip.ZipInputStream(assets.open(PRESET_SKILLS_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(root, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(PRESET_SKILLS_VERSION)
            Log.i(
                TAG,
                "已安装内置 skill $PRESET_SKILLS_VERSION: " +
                    PRESET_SKILL_NAMES.joinToString(", ") + " → ${root.absolutePath}",
            )
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了这几份 skill，引擎照常跑。
            Log.w(TAG, "安装内置 skill 失败: ${t.message}")
        }
    }

    /**
     * 解出 uid 2000 执行通道（`dsh-android-shell.mjs`，每次启动覆盖写）。
     *
     * 该脚本直连本机 adbd（`127.0.0.1:5555`）走 ADB 协议认证，从而以 shell
     * 身份（uid 2000）执行命令 —— 这是 `screencap` / `input` / `pm uninstall` /
     * `settings` / `getevent` 等操作唯一可行的路径，app 域全部做不到
     * （详见 `assets/dsh-android-shell.mjs` 头部的实测对照表）。
     *
     * ⚠️ 没有密钥时**不报错**：脚本自己会回落为本地执行（app 域），
     * 也就是「授权了走 shell，没授权走普通的」。所以这里返回 null 只是
     * 让引擎不挂 overlay，而不是失败。
     */
    fun ensurePrivilegedShell(): File? {
        val dest = File(filesDir, PRIVILEGED_RUNNER_FILE)
        return try {
            // 每次都覆盖写：脚本 20KB 且随 APK 升级而变，比对哈希不值当
            assets.open(PRIVILEGED_RUNNER_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            Log.i(TAG, "已解出 uid 2000 执行通道 $PRIVILEGED_RUNNER_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            // 解不出来只是没有特权通道，不该拖垮引擎启动
            Log.w(TAG, "解出 uid 2000 执行通道失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 root 执行通道（`dsh-android-root.mjs`，每次启动覆盖写）。
     *
     * 与 [ensurePrivilegedShell] 同构，差别只在身份：那条走 adbd 拿 uid 2000，
     * 这条走 `su -c` 拿 uid 0。**不返回 null 表示「有 root」** —— 那件事由
     * [RootAccess] 单独探测，本方法只负责把脚本落盘。
     *
     * ⚠️ 与 `shell` 通道的关键差别：**root 通道不回落**。脚本自己会在拿不到
     *   uid 0 时以专属退出码 77/78 失败，而不是静默降级成 app 身份。
     *   理由见 `assets/dsh-android-root.mjs` 头部。
     */
    fun ensureRootShell(): File? {
        val dest = File(filesDir, ROOT_RUNNER_FILE)
        return try {
            assets.open(ROOT_RUNNER_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            Log.i(TAG, "已解出 root 执行通道 $ROOT_RUNNER_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "解出 root 执行通道失败: ${t.message}")
            null
        }
    }

    /**
     * 解出无线调试用的 adb 工具链（`adb-wireless-arm64.zip` → `filesDir/adbwire/`）。
     *
     * ## 为什么需要一个 adb 二进制

     *
     * Android 11 起有「无线调试」，它和 legacy `adb tcpip 5555` 是两回事：
     * 端口随机、走 TLS、**重启后依然有效**（legacy 的在 Android 10 上重启即失效）。
     * 后者才是「免 PC」的关键。
     *
     * 但配对（pairing）走 **SPAKE2 + TLS-PSK**，而 Node 的 `tls` 模块**不支持
     * external PSK**，自己实现等于手写密码学。所以直接内置 Termux 官方
     * `android-tools` 里的 `adb`，复用 Google 自己的实现。
     *
     * ## 为什么带一堆 .so
     *
     * 那个 adb 是动态链接的，NEEDED 里有 `libprotobuf` / `libfmt` / `libabsl_*`
     * / `libbrotli*` / `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`。
     * 它的 RUNPATH 指向 Termux 的 `/data/data/com.termux/files/usr/lib`（本 App 里
     * 不存在），所以必须把库一并带上，并由 runner 设 `LD_LIBRARY_PATH`。
     *
     * ⚠️ **这里的 `LD_LIBRARY_PATH` 只作用于 adb 这一个子进程**，与引擎进程
     * 那处「刻意不设 LD_LIBRARY_PATH」的结论不冲突 —— 那条结论针对的是**引擎进程
     * 全局**设置该变量会污染系统二进制（见 [runEngine] 里的长注释）。adb 是自带
     * 全套依赖的自洽单元，且只跑它自己。
     *
     * ⚠️ **两个库必须按 SONAME 改名**：bootstrap 里是 `libz.so.1.3.2` 与
     * `libzstd.so.1.5.7`，而 adb 找的是 `libz.so.1` / `libzstd.so.1`。
     * Android 的 SELinux 不给 app 私有目录建符号链接，所以打包时就直接放了
     * SONAME 名字的副本（见资产构建步骤）。
     *
     * ⚠️ **`libc++_shared.so` 必须用与 adb 同期的那份**（来自 `libc++` 包），
     * 不能用 bootstrap 里那份：bootstrap 的是 9 月版，缺
     * `_ZNSt6__ndk113__hash_memoryEPKvm`，加载时报
     * `cannot locate symbol ... referenced by .../bin/adb`。
     *
     * @return adb 所在目录，失败给 `null`。
     */
    fun ensureAdbWireless(): File? {
        val dest = File(filesDir, ADB_WIRELESS_DIR)
        val adb = File(dest, "bin/adb")
        // ⚠️ 幂等判据用**完整性哨兵**，不是「bin/adb 在不在」。
        //
        // 这套工具链有 107 个条目 / 5.26MB，解包要好几秒 —— 而启动过程中进程被系统
        // 杀掉、用户强退、磁盘满，都会**中途停下**。若判据只看 `bin/adb`，而 zip 的
        // 条目顺序恰好让 `bin/adb` 落在前面（实际就是这样），那么一次中断就会留下
        // 「adb 在、lib/* 缺失」的半成品，且此后**每次启动都判定为已完成**并跳过 ——
        // 于是这台设备上的无线调试**永久**坏掉，还查不出原因（解包日志早就滚走了）。
        //
        // 哨兵在**全部条目写完之后**才落盘，所以「哨兵存在」= 解包完整走完过一次。
        // 老安装没有哨兵，会多解一次（一次性代价），换来此后不会再锁死半成品。
        val sentinel = File(dest, ADB_WIRELESS_STAMP)
        if (adb.isFile && adb.length() > 0 && sentinel.isFile) {
            adb.setExecutable(true, false)
            File(dest, "tmp").mkdirs()
            File(dest, "home").mkdirs()
            return dest
        }
        if (adb.isFile && adb.length() > 0 && !sentinel.isFile) {
            Log.w(TAG, "无线调试 adb 工具链缺少完整性标记（疑似上次解包中断），重新解出")
        }
        try {
            assets.open(ADB_WIRELESS_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(dest, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            // ⚠️ 从 APK 资产解出来的文件**没有执行位**，必须显式设，
            // 否则报 EACCES 而不是「找不到」。
            adb.setExecutable(true, false)
            File(dest, "tmp").mkdirs()
            File(dest, "home").mkdirs()
            // 全部写完之后才落哨兵 —— 顺序不能反，否则中途失败反而留下「已完成」的假象。
            sentinel.writeText("ok\n")
            Log.i(TAG, "已解出无线调试 adb 工具链 ($ADB_WIRELESS_DIR, adb ${adb.length()} 字节)")
            return dest
        } catch (t: Throwable) {
            // 解不出来只是没有无线通道，legacy 与 app 身份都不受影响
            Log.w(TAG, "解出无线调试 adb 工具链失败: ${t.message}")
            return null
        }
    }
}
