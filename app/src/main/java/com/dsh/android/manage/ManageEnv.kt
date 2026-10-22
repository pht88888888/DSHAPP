package com.dsh.android.manage

import android.content.Context
import java.io.File

/**
 * **三环境模型** —— A（日常）/ R（修复）/ E（应急）的路径与职责。
 *
 * ## 现状与文档要求的差距
 *
 * 现在 App 只有一个 `DSH_HOME`（`filesDir/home/.dsh`），A 与 R 只是**profile 不同**：
 *
 * ```
 * filesDir/home/.dsh/
 *   ├─ profiles/web/      ← A 用这个
 *   └─ profiles/rescue/   ← R 用这个
 * ```
 *
 * 设计说明 §3.1 要求的是**各自独立的 DSH_HOME**：
 *
 * > A、R、E 分别使用独立 DSH_HOME，隔离 home patch、模型设置、凭据、插件和
 * > 其他可变状态。
 *
 * > R 与 A **共用一号内核代码**，**不共用可变插件安装区**。
 *
 * ### 为什么共用 HOME 是不够的
 *
 * | 共用 HOME 的问题 | 后果 |
 * |---|---|
 * | `.credentials.yaml` 共用 | R 里改密钥会改到 A 的 |
 * | `skills/` 共用 | R 里删技能 A 也没了 |
 * | `profiles/<名字>/node_modules` 同树 | R 的修复可能动到 A 的插件 |
 * | `home patch` 共用 | A 的坏配置在 R 里照样生效 |
 *
 * R 存在的意义就是「**绕开 A 的坏配置**」。共用 HOME 时，坏配置仍在 ——
 * 救援的价值大打折扣。
 *
 * ## 目录布局（本次引入）
 *
 * ```
 * filesDir/
 *   ├─ home/.dsh/           ← A（保持原路径，不动存量用户）
 *   ├─ env-r/.dsh/          ← R（新建）
 *   ├─ env-e/.dsh/          ← E（新建，预留）
 *   ├─ engine/              ← 内核代码（A 与 R 共用）
 *   └─ usr/                 ← Node/Termux 底座（共用）
 * ```
 *
 * ⚠️ **A 的路径刻意不变**：存量用户的 `home/.dsh` 里有会话、凭据、插件。
 * 搬它需要迁移逻辑与回滚方案，风险远大于收益。R/E 是**新增**目录，
 * 不存在迁移问题。
 *
 * ## ⚠️ 独立目录 ≠ 安全隔离
 *
 * 设计说明明确：「**独立目录不等于安全隔离**：同一 Android 应用身份下的
 * 代码可能访问其他环境文件，必须另行约束修复权限。」
 *
 * 所以本对象只提供**路径隔离**，不声称任何安全边界。
 */
object ManageEnv {

    /**
     * 环境标识。
     *
     * ⚠️ 与 [ManageState.Env] 是同一套概念，但那边用于**状态记录**，
     * 这边用于**文件路径**。刻意不合并：状态记录不该依赖文件系统布局，
     * 否则以后挪目录就要改状态 schema。
     */
    enum class Env(val id: String, val label: String) {
        /** 日常环境。路径保持原样，不动存量用户。 */
        A("a", "日常"),

        /** 修复环境。独立 HOME + 修复 profile，共用内核代码。 */
        R("r", "修复"),

        /** 应急环境。**尚未实现** —— 它需要二号内核（独立精简版）。 */
        E("e", "应急"),
        ;

        companion object {
            fun of(id: String?): Env? = entries.firstOrNull { it.id == id }
        }
    }

    /** A 环境的 HOME 相对路径（与 `DshApplication.DSH_HOME_REL` 一致）。 */
    private const val A_HOME_REL = "home/.dsh"

    /** R 环境的 HOME 相对路径。 */
    private const val R_HOME_REL = "env-r/.dsh"

    /** E 环境的 HOME 相对路径（预留）。 */
    private const val E_HOME_REL = "env-e/.dsh"

    /**
     * 某个环境的 DSH_HOME。
     *
     * ⚠️ A 返回的是**原路径**，与 `EngineManager.dshHome` 完全相同 ——
     * 这样调用方可以无痛替换，不会出现「两个函数给不同路径」的分叉。
     */
    fun home(context: Context, env: Env): File = when (env) {
        Env.A -> File(context.filesDir, A_HOME_REL)
        Env.R -> File(context.filesDir, R_HOME_REL)
        Env.E -> File(context.filesDir, E_HOME_REL)
    }

    /**
     * 该环境用的 profile 名。
     *
     * ⚠️ R 用 `rescue` 而不是新建一个名字：那个 profile 的 bundle 清单
     * （[RESCUE_BUNDLES] 那套）已经在真机验证过能起来。换新名字等于重新验证一遍。
     */
    fun profileName(env: Env): String = when (env) {
        Env.A -> "web"
        Env.R -> "rescue"
        Env.E -> "rescue"   // E 也用最小 profile；它靠独立 HOME 隔离
    }

    /**
     * 该环境是否**已实现**。
     *
     * ⚠️ E 尚未实现（需要二号内核）。调用方必须先问这个，
     * 而不是拿到路径就去用 —— 那会得到一个空目录，然后引擎起不来。
     */
    fun isImplemented(env: Env): Boolean = env != Env.E

    /**
     * 该环境的 HOME 是否已初始化（存在且有 profiles 目录）。
     */
    fun isInitialized(context: Context, env: Env): Boolean {
        val h = home(context, env)
        return h.isDirectory && File(h, "profiles").isDirectory
    }

    /**
     * 内核代码目录。**A 与 R 共用**。
     *
     * 设计说明：「R 与 A **共用一号内核代码**，不共用可变插件安装区」。
     *
     * ⚠️ 所以 R 环境**不需要**独立的 `engine/` 目录 —— 那是几百 MB 的重复。
     * 隔离靠的是 HOME（profile / 插件 / 凭据），不是内核代码。
     */
    fun engineDir(context: Context): File = File(context.filesDir, "engine")

    /**
     * Node/Termux 底座。**三个环境共用**。
     *
     * 设计说明：「Node 和原生工具底座仅可在兼容验证通过后共享，首版即按版本
     * 引用受管运行时；正常包管理不得原地修改已激活或救援引用的版本」。
     *
     * ⚠️ 「共用」是当前事实，不是设计上的保证 —— 用户如果在 A 里用 pnpm 升级了
     * 某个全局包，R 也会受影响。真正的隔离需要「独立前缀」，那是 G2 后续的工作。
     */
    fun prefixDir(context: Context): File = File(context.filesDir, "usr")

    /**
     * 列出所有已实现且已初始化的环境。
     *
     * 供诊断与「切换环境」UI 使用。
     */
    fun available(context: Context): List<Env> =
        Env.entries.filter { isImplemented(it) && isInitialized(context, it) }

    /*
     * ⚠️ **本对象只描述布局，不创建目录**。
     *
     * 创建目录涉及「profile 怎么初始化」「bundle 清单写什么」等逻辑，
     * 那些在 `EngineManager.ensureRescueProfile()` 里已有实现。
     * 这里只回答「路径在哪」，不重复那套逻辑 —— 否则两处必然漂开。
     *
     * 未实现：
     * - E 环境（需要二号内核，见设计说明 §3「二号 DSH 内核」）
     * - 独立 Node 前缀（当前三环境共用 `usr/`）
     * - 环境间的权限约束（文档说「独立目录不等于安全隔离」）
     */
}
