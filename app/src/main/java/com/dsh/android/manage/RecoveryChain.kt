package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import com.dsh.android.log.RescueBoot

/**
 * **恢复链决策器** —— 实现设计说明 §6.3 的六级分流。
 *
 * ## 文档原文
 *
 * | 情况 | 处理 |
 * |---|---|
 * | A 正常，仅可选插件异常 | 保留可用功能并提示，**不强制切换** |
 * | A 无法就绪或关键功能不可用 | 保存现场，停止并确认退出，再启动一号内核的 R |
 * | R 能运行 | **留在 R 诊断 A，不重置 A** |
 * | R 无法就绪 | 启动二号内核的 E |
 * | 明确确认一号文件损坏等故障 | **可直接进入 E** |
 * | E 也无法运行 | 留在**原生恢复页**，**不无限重启** |
 *
 * > R 能否运行是主要分流依据之一，而不是强行把内核启动和插件加载划成
 * > 互不关联的两个阶段。
 *
 * ## ⚠️ 本决策器解决的核心问题：无限重启
 *
 * 没有它的话，降级是「每次启动失败就往下退一格」—— 退到底之后呢？
 * 现有代码里 `Tier.EMERGENCY` 之下没有出口，于是会：
 *
 * ```
 * E 也起不来 → 重启 → 还是 E → 还是起不来 → 重启 → …
 * ```
 *
 * 文档明确禁止：「**不无限重启**」。所以决策器要能判出
 * [Action.GIVE_UP] —— 停在那儿，让用户从原生恢复页介入。
 *
 * ## ⚠️ 第二个核心问题：不重置 A
 *
 * 文档：「留在 R 诊断 A，**不重置 A**」。这条最容易被写成「救援模式下顺手修一下」，
 * 而那会毁掉用户的插件配置。
 *
 * 本决策器**只决定去哪，不做任何修改**。修改是另一个显式动作（用户点了才算）。
 */
object RecoveryChain {

    private const val TAG = "DSH"

    /**
     * 决策结果 —— 「下一步该做什么」。
     *
     * ⚠️ 刻意不用 `Tier` 表达：Tier 是**当前状态**，Action 是**下一步动作**。
     * 混用会让「当前在 E」与「要去 E」看起来一样，而那正是无限重启的根源。
     */
    enum class Action(val id: String) {
        /** 保持现状，什么都不做。 */
        STAY("stay"),

        /** 用正常 profile 启动（A）。 */
        START_A("start-a"),

        /** 用救援 profile 启动（R）。 */
        START_R("start-r"),

        /** 用二号内核启动（E）。 */
        START_E("start-e"),

        /** **停在这里**，显示原生恢复页。这是「不无限重启」的实现。 */
        GIVE_UP("give-up"),
        ;
    }

    /**
     * 一次决策的输入。
     *
     * @param tier 当前持久化的层级
     * @param consecutiveFailures 跨启动的连续失败次数
     * @param primaryLaunchable 一号内核（`engine/`）是否还值得尝试
     * @param emergencyLaunchable E 环境是否已部署可启动
     * @param rescueJustTried 本次是否**已经**试过 R（防止 R→E 循环）
     * @param emergencyJustTried 本次是否**已经**试过 E（防止 E→原生循环）
     */
    data class Input(
        val tier: RescueBoot.Tier,
        val consecutiveFailures: Int,
        val primaryLaunchable: Boolean,
        val emergencyLaunchable: Boolean,
        val rescueJustTried: Boolean = false,
        val emergencyJustTried: Boolean = false,
    )

    /**
     * 决策结果 + 人能看懂的理由。
     *
     * ⚠️ `reason` 不是可选的装饰：降级是用户能感知到的**功能缺失**，
     * 不告诉他为什么，他只会觉得「App 变难用了」。
     */
    data class Decision(val action: Action, val reason: String)

    /**
     * 做决策。
     *
     * ## 决策顺序（严格照文档的表格）
     *
     * 1. **E 已经试过还失败** → GIVE_UP（不无限重启）
     * 2. 一号内核不可用（文件损坏）→ 直接进 E（文档：「可直接进入 E」）
     * 3. 当前在 NORMAL/ISOLATED/QUARANTINED → 试 A
     * 4. 当前在 RESCUE（R 试过）→ 试 E；E 不可用 → GIVE_UP
     * 5. 当前在 SAFE_MODE（推开+重解包试过）→ 试 E
     * 6. 当前在 EMERGENCY → 若 E 也不可用 → GIVE_UP
     */
    fun decide(input: Input): Decision {
        // ── ① 已经在 E 且又失败 ⇒ 停住，不重启 ──────────────────────────
        //
        // ⚠️ 这是「不无限重启」的唯一实现点。判据用 `emergencyJustTried`
        //   而不是「tier == EMERGENCY」：tier 是持久化的，进程重启后它还是
        //   EMERGENCY，光看它会导致「每次启动都判 GIVE_UP」—— 那样用户
        //   连手动重试的机会都没有。
        if (input.emergencyJustTried) {
            return Decision(
                Action.GIVE_UP,
                "二号内核（E）也起不来 —— 停在原生恢复页，不再自动重启",
            )
        }

        // ── ② 一号内核不可用 ⇒ 直接进 E ────────────────────────────────
        //
        // 文档：「明确确认一号文件损坏等故障 → 可直接进入 E」。
        //
        // ⚠️ 这里**跳过 R**：R 用的还是一号内核的代码（`engine/`），
        //   一号文件坏了 R 必然也起不来。硬试一次只是白等 15 秒。
        if (!input.primaryLaunchable) {
            return if (input.emergencyLaunchable) {
                Decision(
                    Action.START_E,
                    "一号内核文件不可用，直接用二号内核（E）启动",
                )
            } else {
                Decision(
                    Action.GIVE_UP,
                    "一号内核不可用且 E 未部署 —— 只能进原生恢复页",
                )
            }
        }

        // ── ③ 正常态 ⇒ 试 A ────────────────────────────────────────────
        return when (input.tier) {
            RescueBoot.Tier.NORMAL,
            RescueBoot.Tier.ISOLATED,
            RescueBoot.Tier.QUARANTINED,
            -> Decision(Action.START_A, "正常启动（层级 ${input.tier.id}）")

            // ── ④ R 已经试过 ⇒ 退到 E ────────────────────────────────────
            //
            // 文档：「R 无法就绪 → 启动二号内核的 E」。
            RescueBoot.Tier.RESCUE -> {
                if (input.rescueJustTried) {
                    // R 本次刚失败 ⇒ 该退 E 了
                    if (input.emergencyLaunchable) {
                        Decision(Action.START_E, "救援模式（R）也起不来，退到二号内核（E）")
                    } else {
                        Decision(
                            Action.GIVE_UP,
                            "救援模式（R）起不来且 E 未部署 —— 进原生恢复页",
                        )
                    }
                } else {
                    // 上次记录在 R，但本次还没试 ⇒ 再试一次 R
                    //
                    // ⚠️ 不能因为「上次 R 失败过」就永久跳过 R：
                    //   用户可能刚把坏插件删掉、或修好了配置。
                    Decision(Action.START_R, "上次在救援模式，本次再试一次")
                }
            }

            // ── ⑤ 安全模式试过 ⇒ 退到 E ──────────────────────────────────
            RescueBoot.Tier.SAFE_MODE -> {
                if (input.emergencyLaunchable) {
                    Decision(
                        Action.START_E,
                        "安全模式（已推开配置并重解包）仍失败，退到二号内核（E）",
                    )
                } else {
                    Decision(
                        Action.GIVE_UP,
                        "安全模式仍失败且 E 未部署 —— 进原生恢复页",
                    )
                }
            }

            // ── ⑥ 当前在 E ───────────────────────────────────────────────
            RescueBoot.Tier.EMERGENCY -> {
                if (input.emergencyLaunchable) {
                    // 用户可能手动点了「重试」，允许再试一次 E
                    Decision(Action.START_E, "仍在应急模式，按用户请求重试")
                } else {
                    Decision(Action.GIVE_UP, "应急环境不可用 —— 进原生恢复页")
                }
            }
        }
    }

    /**
     * 便捷入口：从磁盘读真实状态后决策。
     *
     * ⚠️ 本函数**只读**，不写任何状态。写状态是调用方的事 ——
     *   这样「决策」与「执行」分离，排查时能明确区分
     *   「判成要进 E」与「进 E 失败了」。
     */
    fun decideFromDisk(
        context: Context,
        primaryLaunchable: Boolean,
        primaryStamp: String,
        rescueJustTried: Boolean = false,
        emergencyJustTried: Boolean = false,
    ): Decision {
        val st = RescueBoot.tier()
        val input = Input(
            tier = st.tier,
            consecutiveFailures = RescueBoot.lastFailure()?.consecutiveFailures ?: 0,
            primaryLaunchable = primaryLaunchable,
            emergencyLaunchable = EmergencyEnv.isLaunchable(context),
            rescueJustTried = rescueJustTried,
            emergencyJustTried = emergencyJustTried,
        )
        val d = decide(input)
        Log.i(TAG, "恢复链决策: ${d.action.id} —— ${d.reason}")
        EventLog.record(
            "recovery.decide",
            mapOf(
                "action" to d.action.id,
                "tier" to input.tier.id,
                "primaryOk" to input.primaryLaunchable.toString(),
                "eOk" to input.emergencyLaunchable.toString(),
            ),
        )
        return d
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：六级分流、不无限重启、不重置 A、跳过无意义的 R
     * ❌ 未做：
     *   - **实际执行切换**（本对象只决策，执行在 EngineManager）
     *   - 「保存现场」的动作（文档要求 A 失败时保存现场 —— 那是 CrashRecorder 的事）
     *   - 「页面白屏先修页面」的分流（那在 ManageHealth，与内核切换是两条独立的判据）
     *   - R/E 的独立 Node 前缀（当前共用 usr/）
     */
}
