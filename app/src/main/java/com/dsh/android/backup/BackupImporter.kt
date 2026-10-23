package com.dsh.android.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dsh.android.log.EventLog
import com.dsh.android.manage.ManageEnv
import java.io.File

/**
 * **备份导入器** —— 从加密包恢复用户数据。
 *
 * ## 设计说明 §5.3.5 的硬要求
 *
 * > 在私有暂存区完成解密认证与清单检查；限制总展开量、文件数、单文件大小、
 * > 路径深度和解压比；拒绝穿越、危险链接、冲突条目及校验后换文件。
 * > **认证不完整的暂存内容不得激活，导入不直接覆盖当前 A**。
 *
 * > 展示包时间、插件组合、范围、完整/部分、ABI/Node/DSH 兼容性、缺项、
 * > 密码世代提示和替换影响，经确认后**停写并给当前 A 建立恢复点**。
 *
 * > 失败**保留当前 A 与原包**，清理未提交明文副本并报告阶段；
 * > 成功分别报告文件恢复、插件激活和功能验证，**不以「已解压」冒充全部恢复成功**。
 *
 * ## ⚠️ 三步分离（这是安全的关键）
 *
 * ```
 * ① inspect()  只读：解开头部 + 用密码验证 + 出清单  ← 不改任何东西
 * ② stage()    解到私有暂存区并校验                  ← 只写 cache
 * ③ apply()    建恢复点 → 替换 → 失败可回滚          ← 唯一动 A 的一步
 * ```
 *
 * 分开的理由：用户在 ① 之后能看到「这个包是什么、会覆盖什么」再决定。
 * 把三步合成一步的话，用户点「导入」就直接覆盖了 —— 那不可接受。
 *
 * ## ⚠️ 为什么必须有恢复点
 *
 * 导入是**破坏性**的：它会替换 `sessions/`、`profiles/` 等。
 * 如果导入的包是错的（比如版本不兼容、内容损坏），用户会丢掉当前数据。
 *
 * 所以 [apply] 在替换前先把当前数据移到 `.pre-import-<ts>`，
 * 成功后才删；失败则移回来。这是文档「失败保留当前 A」的实现。
 */
object BackupImporter {

    private const val TAG = "DSH"

    /** 暂存区目录名（在 cache 下）。 */
    private const val STAGE_DIR = "import-stage"

    /** 恢复点前缀。 */
    private const val ROLLBACK_PREFIX = ".pre-import-"

    /**
     * ① 只读检查的结果 —— 给用户看「这个包是什么」。
     */
    data class Inspection(
        val manifest: BackupManifest,
        val header: BackupCrypto.HeaderInfo,
        /** 与当前环境的兼容性问题（空 = 没问题）。 */
        val warnings: List<String>,
        /** 会被覆盖的现有内容摘要。 */
        val willReplace: List<String>,
    )

    /**
     * ② 暂存结果。
     */
    data class Staged(
        val manifest: BackupManifest,
        val dir: File,
        /** 逐文件校验发现的问题（空 = 全部一致）。 */
        val problems: List<String>,
    )

    /**
     * ③ 应用结果。
     */
    sealed interface Applied {
        data class Ok(val restored: List<String>, val backupPath: File?) : Applied
        data class Failed(val reason: String, val rolledBack: Boolean) : Applied
    }

    // ── ① 只读检查 ───────────────────────────────────────────────────────

    /**
     * 检查一个包：**不解出内容、不改任何东西**。
     *
     * ⚠️ 需要密码：清单在加密载荷内，没有密码连「包里有什么」都看不到。
     *   这是刻意的（文档：「文件名、清单及内容哈希置于加密载荷」）。
     *
     * @return `null` = 不是有效包或密码错（调用方应提示「密码错误或文件损坏」，
     *   但**不能区分两者** —— 区分会泄露「密码对不对」这个信息）
     */
    fun inspect(
        context: Context,
        uri: Uri,
        password: CharArray,
    ): Inspection? {
        // 先看头部（不需要密码）
        val local = copyToTemp(context, uri) ?: return null
        return try {
            val header = BackupCrypto.peekHeader(local)
            if (header == null) {
                Log.w(TAG, "导入：不是有效的备份包")
                return null
            }
            if (header.formatVersion != BackupCrypto.FORMAT_VERSION) {
                Log.w(TAG, "导入：格式版本 ${header.formatVersion} 不支持")
                return null
            }

            // 解到临时目录拿清单（verifyOnly 不写文件，但 decrypt 需要一个
            // destDir 的父目录来放暂存 —— 用 cache）
            val probeDir = File(context.cacheDir, "$STAGE_DIR-probe").apply {
                deleteRecursively()
                mkdirs()
            }
            val manifest = try {
                BackupCrypto.decrypt(local, probeDir, password, verifyOnly = true)
            } catch (t: Throwable) {
                // ⚠️ 密码错与包损坏**不区分**：区分会泄露「密码对不对」
                Log.w(TAG, "导入：解密失败（${t.message}）")
                return null
            } finally {
                probeDir.deleteRecursively()
            }

            Inspection(
                manifest = manifest,
                header = header,
                warnings = compatibilityWarnings(context, manifest),
                willReplace = willReplace(context, manifest),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "导入检查失败: ${t.message}")
            null
        } finally {
            local.delete()
        }
    }

    // ── ② 暂存 ───────────────────────────────────────────────────────────

    /**
     * 解到私有暂存区并逐文件校验。
     *
     * ⚠️ **只写 `cacheDir`，不碰 `$DSH_HOME`**。这一步失败不影响当前数据。
     */
    fun stage(context: Context, uri: Uri, password: CharArray): Staged? {
        val local = copyToTemp(context, uri) ?: return null
        val stageDir = File(context.cacheDir, STAGE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }

        return try {
            val manifest = BackupCrypto.decrypt(local, stageDir, password, verifyOnly = false)

            // 逐文件校验：解出的内容必须与清单一致
            //
            // ⚠️ 文档：「拒绝…**校验后换文件**」—— 这一步就是防这个。
            val problems = BackupBundle.verifyExtracted(stageDir, manifest)

            EventLog.record(
                "backup.import.staged",
                mapOf("entries" to manifest.entries.size.toString(), "problems" to problems.size.toString()),
            )
            Staged(manifest, stageDir, problems)
        } catch (t: Throwable) {
            Log.w(TAG, "导入暂存失败: ${t.message}")
            stageDir.deleteRecursively()
            null
        } finally {
            local.delete()
        }
    }

    // ── ③ 应用 ───────────────────────────────────────────────────────────

    /**
     * 把暂存内容应用到环境。
     *
     * ## 流程
     *
     * ```
     * 建恢复点（把现有目录改名 .pre-import-<ts>）
     *   → 复制暂存内容进去
     *   → 成功：删恢复点
     *   → 失败：恢复点移回来
     * ```
     *
     * ⚠️ **只覆盖备份范围内**的子目录，不碰其它（`engine/`、`usr/` 等）。
     *
     * @param env 目标环境。**只接受 A** —— 文档：「R/E、工作区与聊天会话不被误覆盖」
     */
    fun apply(context: Context, staged: Staged, env: ManageEnv.Env): Applied {
        if (env != ManageEnv.Env.A) {
            return Applied.Failed("只能恢复到日常环境（A）", rolledBack = false)
        }
        if (staged.problems.isNotEmpty()) {
            // ⚠️ 有校验问题时**拒绝应用**：内容与清单不符意味着包被篡改或损坏，
            //   用它覆盖当前数据比不导入更糟。
            return Applied.Failed(
                "包内容与清单不符（${staged.problems.size} 项），拒绝导入",
                rolledBack = false,
            )
        }

        val home = ManageEnv.home(context, env)
        val stamp = System.currentTimeMillis()
        val rollbackRoot = File(context.filesDir, "$ROLLBACK_PREFIX$stamp")

        val moved = mutableListOf<Pair<File, File>>()   // (原位置, 恢复点位置)
        val restored = mutableListOf<String>()
        // profile 配置是**逐文件**恢复的（不整体替换目录，见 ②b 的说明），
        // 所以回滚也要逐文件 —— 单独一份清单。
        val profileFiles = mutableListOf<Pair<File, File>>()

        return try {
            // ① 建恢复点：把**将被覆盖**的目录改名移走
            //
            // ⚠️ 用 rename 而不是复制：rename 是原子的（同分区），
            //   而且不用双倍空间 —— 用户可能只剩几十 MB。
            //
            // 🔴 **只移走「备份包里确实有」的目录**。
            //
            //   第一版无条件移走 RESTORED 里的全部目录，于是「备份里没有
            //   skills、当前有 skills」这种情况下：skills 被移进恢复点，
            //   第②步又因为没有源而不复制，最后第③步把恢复点删掉 ——
            //   **用户的 skills 就永久没了**。
            //
            //   实测验证暴露了这个缺陷（导入后 skills/ 变成不存在）。
            //   现在改成：备份里没有的目录**原地不动**，只移走会被覆盖的。
            val toReplace = RESTORED.filter { sub ->
                File(staged.dir, sub).exists()
            }
            for (sub in toReplace) {
                val cur = File(home, sub)
                if (!cur.exists()) continue
                val bak = File(rollbackRoot, sub)
                bak.parentFile?.mkdirs()
                if (cur.renameTo(bak)) {
                    moved.add(cur to bak)
                } else {
                    // rename 失败（跨分区？权限？）→ 整体中止并回滚
                    throw IllegalStateException("无法建立恢复点: $sub")
                }
            }

            // ② 复制暂存内容进去（用同一份 toReplace，保证移走与放回一致）
            for (sub in toReplace) {
                val src = File(staged.dir, sub)
                val dst = File(home, sub)
                src.copyRecursively(dst, overwrite = true)
                restored.add(sub)
            }

            // ②b profile 配置：**逐文件**恢复，不整体替换目录
            //
            // ⚠️ 为什么不整体替换 `profiles/web/`：那个目录里还有 `node_modules`
            //   （几百 MB 的插件实体）。整体替换会：
            //   - 把用户的插件安装状态换成备份时的（可能已过期）
            //   - 复制几百 MB，导入时间从秒级变成分钟级
            //
            //   所以只恢复**配置**（package.json + cordis.patch.yml），
            //   插件的实际安装由包管理器负责（G5 范围）。
            val profileName = staged.manifest.profile.ifEmpty { "web" }
            val profileDst = File(home, "profiles/$profileName")
            val profileSrc = File(staged.dir, "profiles/$profileName")
            if (profileSrc.isDirectory) {
                // 建恢复点（逐文件）
                val profileBak = File(rollbackRoot, "profiles/$profileName")
                profileBak.mkdirs()
                for (name in PROFILE_FILES) {
                    val cur = File(profileDst, name)
                    if (cur.isFile) {
                        val bak = File(profileBak, name)
                        if (cur.copyTo(bak, overwrite = true).exists()) {
                            profileFiles.add(cur to bak)
                        }
                    }
                }
                // 覆盖
                profileDst.mkdirs()
                for (name in PROFILE_FILES) {
                    val src = File(profileSrc, name)
                    if (src.isFile) {
                        src.copyTo(File(profileDst, name), overwrite = true)
                        restored.add("profiles/$profileName/$name")
                    }
                }
            }

            // ③ 成功：删恢复点
            rollbackRoot.deleteRecursively()

            EventLog.record(
                "backup.import.applied",
                mapOf("restored" to restored.joinToString(",")),
            )
            Applied.Ok(restored, rollbackRoot.takeIf { it.exists() })
        } catch (t: Throwable) {
            Log.w(TAG, "导入应用失败，回滚: ${t.message}")
            // ⚠️ 回滚：把恢复点移回来
            var allBack = true
            // profile 逐文件回滚（先做，因为它是细粒度的）
            for ((orig, bak) in profileFiles.asReversed()) {
                try {
                    if (bak.isFile) bak.copyTo(orig, overwrite = true)
                } catch (e: Throwable) {
                    allBack = false
                    Log.w(TAG, "回滚 profile 文件失败: ${orig.name}")
                }
            }
            for ((orig, bak) in moved.asReversed()) {
                // 先删掉可能已写了一半的目标
                orig.deleteRecursively()
                if (!bak.renameTo(orig)) {
                    allBack = false
                    Log.w(TAG, "回滚失败: ${orig.name}")
                }
            }
            if (allBack) rollbackRoot.deleteRecursively()
            EventLog.record(
                "backup.import.rolledback",
                mapOf("ok" to allBack.toString(), "reason" to (t.message ?: "?")),
            )
            Applied.Failed(t.message ?: "应用失败", rolledBack = allBack)
        }
    }

    /**
     * 校验 profile 的 bundles 清单与实际安装的包是否一致。
     *
     * ## 文档要求（§5.3.5）
     *
     * > 按原安装图恢复目录、依赖实体和安全链接，**不盲目混合两套依赖图**
     *
     * ## 为什么需要它
     *
     * 备份里的 `package.json` 记录「当时启用了哪些插件」（`dsh.profile.bundles`），
     * 但**包实体没有进备份**（几百 MB，且可从 npm 重装）。
     *
     * 于是恢复后会有一个真实的不一致：
     *
     * ```
     * bundles: ["dsh-android-mobile", "some-third-party"]   ← 从备份恢复的
     * node_modules/                                          ← 用户当前的
     *   dsh-android-mobile/  ✅ 在
     *   some-third-party/    ❌ 不在（备份里有，当前机器没装）
     * ```
     *
     * 引擎遇到「清单里有但包不在」会报错，而**报错信息指向的是包名**，
     * 用户完全不知道这是「导入了一份来自别的机器的备份」导致的。
     *
     * 所以本函数把这个不一致**提前摆出来**，让调用方决定：
     * 是移除缺的条目，还是提示用户去装。
     *
     * @return 问题描述列表；空 = 一致
     */
    fun checkBundleConsistency(
        context: Context,
        env: ManageEnv.Env,
        profileName: String,
    ): List<String> {
        val home = ManageEnv.home(context, env)
        val profileDir = File(home, "profiles/$profileName")
        val manifest = File(profileDir, "package.json")
        if (!manifest.isFile) return emptyList()

        return try {
            val o = org.json.JSONObject(manifest.readText())
            val bundles = o.optJSONObject("dsh")
                ?.optJSONObject("profile")
                ?.optJSONArray("bundles")
                ?: return emptyList()

            val nodeModules = File(profileDir, "node_modules")
            val problems = mutableListOf<String>()

            for (i in 0 until bundles.length()) {
                val name = bundles.optString(i, "")
                if (name.isEmpty()) continue
                // 包名可能是 @scope/name，路径要拆成两级（File 会自动处理）
                val pkgDir = File(nodeModules, name)
                if (!pkgDir.isDirectory) {
                    problems.add("清单里有「$name」但包未安装")
                }
            }
            problems
        } catch (t: Throwable) {
            // ⚠️ 清单解析失败**如实报告**，不静默放过 ——
            //   一个坏掉的 package.json 会让引擎直接起不来。
            listOf("profile 清单无法解析：${t.message}")
        }
    }

    /** 清理暂存区（导入结束或取消时）。 */
    fun cleanupStage(context: Context) {
        runCatching { File(context.cacheDir, STAGE_DIR).deleteRecursively() }
        runCatching { File(context.cacheDir, "$STAGE_DIR-probe").deleteRecursively() }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 导入要覆盖的子目录。
     *
     * ⚠️ **必须与 [BackupOrchestrator] 的 BACKED_UP 对应**，否则会出现
     * 「备份了 A、B，导入只恢复 A」这种半吊子状态。
     *
     * ⚠️ `profiles/` 单独处理：只恢复我们备份的那两个文件，
     * 不覆盖整个 profile（那会丢掉 node_modules 的安装状态）。
     */
    /**
     * profile 里要恢复的**配置文件**（不含 `node_modules`）。
     *
     * ⚠️ 必须与 [BackupOrchestrator] 的 `PROFILE_FILES` 一致 ——
     *   备份了什么就必须能恢复什么，否则是「备份了但恢复不回来」的半吊子状态。
     */
    private val PROFILE_FILES = listOf(
        "package.json",
        "cordis.patch.yml",
    )

    private val RESTORED = listOf(
        "sessions",
        "storages",
        "skills",
        "deskpet",
        ".credentials.yaml",
    )

    /** 把 SAF 上的包复制到私有临时文件（后续解密需要随机访问）。 */
    private fun copyToTemp(context: Context, uri: Uri): File? {
        return try {
            val tmp = File.createTempFile("import-", ".dshbak", context.cacheDir)
            val ins = context.contentResolver.openInputStream(uri)
            if (ins == null) {
                tmp.delete()
                return null
            }
            ins.use { input ->
                tmp.outputStream().buffered().use { input.copyTo(it) }
            }
            tmp
        } catch (t: Throwable) {
            Log.w(TAG, "读取备份包失败: ${t.message}")
            null
        }
    }

    /**
     * 兼容性检查 —— 给用户看的警告（**不阻止导入**）。
     *
     * ⚠️ 文档要求展示「ABI/Node/DSH 兼容性」，但没说必须阻止。
     *   这里只警告：版本不同不代表不能用（配置格式通常向后兼容），
     *   而阻止会让「从旧版恢复」变成不可能。
     */
    private fun compatibilityWarnings(context: Context, m: BackupManifest): List<String> {
        val out = mutableListOf<String>()
        val curVer = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }

        if (m.appVersion.isNotEmpty() && curVer.isNotEmpty() && m.appVersion != curVer) {
            out.add("这份备份来自 App $${m.appVersion}，当前是 $curVer")
        }
        if (m.sourceEnv != "a") {
            out.add("这份备份来自 ${m.sourceEnv.uppercase()} 环境，不是日常环境")
        }
        if (!m.complete) {
            out.add("这是一份**不完整**的备份（${m.missing.size} 项未纳入）")
        }
        if (m.missing.isNotEmpty()) {
            out.add("缺项：" + m.missing.take(3).joinToString("、") +
                if (m.missing.size > 3) " 等 ${m.missing.size} 项" else "")
        }
        return out
    }

    /** 列出会被覆盖的现有内容（让用户知道代价）。 */
    private fun willReplace(context: Context, m: BackupManifest): List<String> {
        val home = ManageEnv.home(context, ManageEnv.Env.A)
        return RESTORED.filter { sub ->
            val cur = File(home, sub)
            cur.exists() && m.entries.any { it.path.startsWith("$sub/") || it.path == sub }
        }
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：三步分离（检查/暂存/应用）、暂存区隔离、逐文件校验、
     *          恢复点与回滚、兼容性警告、只覆盖备份范围内的子目录
     * ❌ 未做：
     *   - **profile 的恢复**（只恢复 sessions 等，没恢复 profiles/web/package.json
     *     与 cordis.patch.yml —— 那需要处理 node_modules 安装状态，
     *     属 G5「插件」范围）
     *   - 「事务提交」（文档 §6.5 要求 PREPARED→COMMITTED，当前是直接改名）
     *   - 「依赖实体重建」（跨 ABI/Node 的原生依赖需重新安装，当前不处理）
     *   - 「权限重新检查」（文档：备份里的「曾获授权」不构成当前授权）
     *   - 导入 UI（下一步做）
     */
}
