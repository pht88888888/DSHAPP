package com.dsh.android.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dsh.android.log.EventLog
import com.dsh.android.manage.ManageEnv
import com.dsh.android.manage.ManageGate
import java.io.File

/**
 * **备份编排器** —— 把六个模块串成一条完整的备份流程。
 *
 * ## 文档给的流程（§5.3.4「可靠首版流程」）
 *
 * ```
 * 登记管理事务 → 冻结管理入口 → 等待/确认停止写者 → 停 A 并确认后代/代理状态
 *   → 在私有区取得一致快照 → 恢复原组合与页面 → 对快照流式压缩加密
 *   → SAF 验证发布 → 三份轮换
 * ```
 *
 * ## ⚠️ 本类**不做**的两件事，以及为什么
 *
 * **① 不自己停引擎**
 *
 * 「停 A 并确认后代/代理状态」需要 `EngineManager.stop()` 与
 * `ManageFsm.confirmStopped()`。本类如果直接调它们，就与
 * `EngineManager` 双向依赖了 —— 而 `EngineManager` 已经在调本包的东西。
 *
 * 所以停/启引擎通过 [EngineControl] 接口注入，由调用方（`EngineManager`
 * 或 `MainActivity`）实现。这样本类可以独立测试，也不会形成循环依赖。
 *
 * **② 不做密码的持久化**
 *
 * 密码由调用方持有（用户输入或 Keystore 解出的密钥材料），
 * 用完即清。本类**不缓存密码**，也不写进任何文件 ——
 * 文档明确「不由 AI 保管，不写日志、profile、包 manifest 或导出记录」。
 *
 * ## 流程中的每个失败点都有明确去向
 *
 * | 阶段 | 失败 | 去向 |
 * |---|---|---|
 * | 冻结 | 已被别的流程冻结 | BLOCKED（不抢） |
 * | 停写 | 无法确认停写 | BLOCKED（保留旧包） |
 * | 快照 | 复制失败 | FAILED（旧包仍在） |
 * | 加密 | 空间不足/OOM | FAILED |
 * | 发布 | 写失败/校验失败 | FAILED（不标 published，不挤占三份） |
 * | 轮换 | 删旧包失败 | 成功但仍报（旧包还在，不是坏事） |
 */
object BackupOrchestrator {

    private const val TAG = "DSH"

    /**
     * 引擎控制接口 —— 由调用方实现，避免本类依赖 `EngineManager`。
     */
    interface EngineControl {
        /**
         * 停引擎并**确认**已停。
         *
         * ⚠️ 实现方必须真的确认（`waitFor` + 查进程），不能只发停止信号
         * 就返回 true —— 那会让快照在引擎还在写的时候开始，
         * 拿到的就是不一致数据。
         *
         * @return true = 确认已停；false = 停不下来或无法确认
         */
        suspend fun stopAndConfirm(): Boolean

        /** 重启引擎（恢复到备份前的状态）。 */
        suspend fun restart()
    }

    /**
     * 一次备份的结果。
     */
    sealed interface Outcome {
        /** 成功。 */
        data class Success(val record: BackupRotation.Record, val rotated: Int) : Outcome

        /** 用户没配置密码或目录 —— **不算失败，但也不算成功**。 */
        data class Unconfigured(val reason: String) : Outcome

        /** 受阻：无法安全取得一致点。保留旧包。 */
        data class Blocked(val reason: String) : Outcome

        /** 失败。 */
        data class Failed(val reason: String, val stage: String) : Outcome
    }

    /**
     * 执行一次完整备份。
     *
     * @param env 要备份的环境。**只接受 A** —— 文档：「R/E 不作为用户备份对象」
     * @param password 用户密码（调用方负责用后清零）
     * @param treeUri 用户授权的 SAF 目录；null = 未配置
     * @param engine 引擎控制
     */
    suspend fun run(
        context: Context,
        env: ManageEnv.Env,
        password: CharArray?,
        treeUri: Uri?,
        engine: EngineControl,
        eventId: String,
    ): Outcome {
        // ── 前置检查 ─────────────────────────────────────────────────────
        if (env != ManageEnv.Env.A) {
            return Outcome.Unconfigured("只备份日常环境（A），R/E 不入用户包")
        }
        if (password == null || password.isEmpty()) {
            BackupScheduler.setStatus(context, eventId, BackupScheduler.Status.UNCONFIGURED, "未设置密码")
            return Outcome.Unconfigured("未设置备份密码")
        }
        if (treeUri == null) {
            BackupScheduler.setStatus(context, eventId, BackupScheduler.Status.UNCONFIGURED, "未选择目录")
            return Outcome.Unconfigured("未选择备份目录")
        }

        // ── ① 冻结管理入口 ───────────────────────────────────────────────
        //
        // ⚠️ 冻结失败（已被别的流程持有）时**不抢**：抢过来会让先发起的
        //   那个流程失去保护，两个流程同时操作同一批文件。
        if (!ManageGate.freeze(context, ManageGate.Reason.SNAPSHOT)) {
            BackupScheduler.setStatus(
                context, eventId, BackupScheduler.Status.BLOCKED,
                "已有 ${ManageGate.currentReason().label} 在进行",
            )
            return Outcome.Blocked("另一个管理操作正在进行（${ManageGate.currentReason().label}）")
        }

        BackupScheduler.setStatus(context, eventId, BackupScheduler.Status.RUNNING)

        var snapshotDir: File? = null
        var encrypted: File? = null
        var engineWasRunning = false

        try {
            // ── ② 停 A 并确认 ────────────────────────────────────────────
            engineWasRunning = true
            if (!engine.stopAndConfirm()) {
                // ⚠️ 停不下来时**不继续**：在还在写的目录上取快照，
                //   得到的是不一致状态，比没有备份更危险（用户以为有备份）。
                BackupScheduler.setStatus(
                    context, eventId, BackupScheduler.Status.BLOCKED, "无法确认引擎已停止",
                )
                return Outcome.Blocked("无法确认引擎已停止 —— 不取不一致快照")
            }

            // ── ③ 在私有区取一致快照 ─────────────────────────────────────
            //
            // ⚠️ 先复制再加密：文档要求「在私有区取得一致快照」，而复制本身
            //   是在引擎停止后做的，所以这份副本是一致的。
            //   加密直接从快照做，不回头读原目录（那样又可能变了）。
            val home = ManageEnv.home(context, env)
            val snap = File(context.cacheDir, "backup-snap").apply {
                deleteRecursively()
                mkdirs()
            }
            snapshotDir = snap

            val manifest = BackupManifest(
                sourceEnv = env.id,
                profile = ManageEnv.profileName(env),
                appVersion = appVersionOf(context),
                nodeVersion = "unknown",   // ⚠️ 见边界说明
                abi = "arm64-v8a",
                complete = true,
            )

            // 复制要备份的子树
            //
            // ⚠️ **刻意排除的目录**（见 KDoc）：
            //   - engine/ 与 usr/：几百 MB，可从 APK 重建
            //   - 我们注入的插件：可从 APK 重建
            //   纳入的是「用户独有、重建不了」的部分
            for (sub in BACKED_UP) {
                val src = File(home, sub)
                if (!src.exists()) continue
                val dst = File(snap, sub)
                dst.parentFile?.mkdirs()
                src.copyRecursively(dst, overwrite = true)
            }
            // profile 配置（不含 node_modules）
            val profileSrc = File(home, "profiles/${ManageEnv.profileName(env)}")
            val profileDst = File(snap, "profiles/${ManageEnv.profileName(env)}")
            profileDst.mkdirs()
            for (name in PROFILE_FILES) {
                val f = File(profileSrc, name)
                if (f.isFile) f.copyTo(File(profileDst, name), overwrite = true)
            }

            // ── ④ 恢复 A（快照已取得，可以续启了）──────────────────────────
            //
            // ⚠️ 顺序：先取快照、再续启、最后加密。文档明确
            //   「快照已取得后 A 可继续任务，不为大包压缩长期停机」。
            engine.restart()
            engineWasRunning = false   // 已恢复

            // ── ⑤ 加密 ───────────────────────────────────────────────────
            val enc = File(context.cacheDir, "backup-enc/${BackupRotation.newName()}")
            enc.parentFile?.mkdirs()
            encrypted = enc
            try {
                BackupCrypto.encryptDirectory(snap, enc, password, manifest)
            } catch (t: Throwable) {
                BackupScheduler.setStatus(
                    context, eventId, BackupScheduler.Status.FAILED, "加密失败: ${t.message}",
                )
                return Outcome.Failed(t.message ?: "加密失败", "encrypt")
            }

            // ── ⑥ SAF 发布 ───────────────────────────────────────────────
            val name = enc.name
            val pub = BackupPublisher.publish(context, treeUri, enc, name)
            when (pub) {
                is BackupPublisher.Result.Published -> {
                    // ── ⑦ 三份轮换 ───────────────────────────────────────
                    val plan = BackupRotation.plan(context)
                    var rotated = 0
                    for (old in plan.toDelete) {
                        // ⚠️ 删除失败**不当作整体失败**：新包已经发布成功了，
                        //   旧包多留一份不是坏事。如实记录即可。
                        if (BackupPublisher.delete(context, old)) {
                            BackupRotation.commitDelete(context, listOf(old))
                            rotated++
                        } else {
                            Log.w(TAG, "轮换：删除旧包失败 ${old.name}（保留）")
                        }
                    }

                    BackupScheduler.setStatus(context, eventId, BackupScheduler.Status.SUCCEEDED)
                    EventLog.record(
                        "backup.done",
                        mapOf("name" to name, "rotated" to rotated.toString()),
                    )
                    return Outcome.Success(pub.record, rotated)
                }
                is BackupPublisher.Result.Unverified -> {
                    BackupScheduler.setStatus(
                        context, eventId, BackupScheduler.Status.FAILED, pub.reason,
                    )
                    return Outcome.Failed(pub.reason, "publish-verify")
                }
                is BackupPublisher.Result.Failed -> {
                    BackupScheduler.setStatus(
                        context, eventId, BackupScheduler.Status.FAILED, pub.reason,
                    )
                    return Outcome.Failed(pub.reason, "publish")
                }
                BackupPublisher.Result.NoDirectory -> {
                    BackupScheduler.setStatus(
                        context, eventId, BackupScheduler.Status.UNCONFIGURED, "目录授权失效",
                    )
                    return Outcome.Unconfigured("备份目录授权失效，请重新选择")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "备份流程异常: ${t.message}")
            BackupScheduler.setStatus(
                context, eventId, BackupScheduler.Status.FAILED, t.message ?: "未知异常",
            )
            return Outcome.Failed(t.message ?: "未知异常", "unexpected")
        } finally {
            // ── 收尾：无论成败 ────────────────────────────────────────────
            //
            // ⚠️ 引擎没恢复时必须恢复：否则用户会遇到「备份完 App 用不了了」，
            //   那比「备份失败」严重得多。
            if (engineWasRunning) {
                runCatching { engine.restart() }
                    .onFailure { Log.w(TAG, "备份后恢复引擎失败: ${it.message}") }
            }
            // 快照与加密产物都是明文/临时，必须清
            runCatching { snapshotDir?.deleteRecursively() }
            runCatching { encrypted?.parentFile?.deleteRecursively() }
            ManageGate.unfreeze(ManageGate.Reason.SNAPSHOT)
        }
    }

    /**
     * 纳入备份的子目录（相对 `$DSH_HOME`）。
     *
     * ## ⚠️ 为什么是这几个
     *
     * | 目录 | 纳入 | 理由 |
     * |---|---|---|
     * | `sessions/` | ✅ | 对话记录，**重建不了** |
     * | `storages/` | ✅ | 引擎键值存储 |
     * | `skills/` | ✅ | 用户技能 |
     * | `deskpet/` | ✅ | 用户装的桌宠素材 |
     * | `.credentials.yaml` | ✅ | 见下 |
     * | `profiles/<名字>/` | 只取 package.json + patch | node_modules 可重装 |
     * | `engine/` `usr/` | ❌ | 几百 MB，从 APK 重建 |
     * | `mobile-ui/` 等注入插件 | ❌ | 从 APK 重建 |
     *
     * ## ⚠️ 凭据文件纳入，但整包是加密的
     *
     * 文档：「目录内可能含 API Key、token、Cookie 或其他秘密，用户已选择用
     * 自设密码**加密完整备份**」。所以凭据进包是**设计如此**，
     * 前提是整包加密 —— 而 [BackupCrypto] 保证这一点。
     */
    private val BACKED_UP = listOf(
        "sessions",
        "storages",
        "skills",
        "deskpet",
        ".credentials.yaml",
    )

    /** profile 里要备份的文件（不含 node_modules）。 */
    private val PROFILE_FILES = listOf(
        "package.json",
        "cordis.patch.yml",
    )

    private fun appVersionOf(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (t: Throwable) {
        ""
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：完整流程串联、每阶段失败有明确去向、引擎一定恢复、
     *          临时产物清理、冻结一定释放
     * ❌ 未做：
     *   - **工作区（workspace/）不在备份范围**：它可能几百 MB，且文档
     *     §5.3.1 说「工作区…不默认进入用户包」。要不要纳入需要用户选择，
     *     那是 UI 的事。
     *   - nodeVersion 写死 "unknown"：读它需要 bootstrap 的 node 路径，
     *     而本类刻意不依赖 EngineManager。
     *   - 「等待有界且可见」的写者等待：当前只靠 stopAndConfirm 一次性确认。
     *   - 空间预检：加密前没查 cacheDir 与 SAF 的可用空间。
     *   - 与 ManageState/ManageFsm 的联动：本类走 ManageGate，
     *     没走完整的状态机转换。
     */
}
