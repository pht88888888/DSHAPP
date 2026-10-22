package com.dsh.android.manage

/**
 * **四层健康状态** —— 分别表达「哪一层坏了」，而不是笼统的「起来了/没起来」。
 *
 * ## 为什么必须分层
 *
 * 设计说明 §6.2：
 *
 * > 分别记录**进程存活、DSH 服务就绪、Web 页面加载、Android 桥可用**四层状态…
 * > **页面白屏而服务正常时优先恢复页面**或关闭界面增强；
 * > **模型服务网络失败不直接触发内核切换**。
 *
 * 不分层的后果是具体的：现在 `EngineManager` 只有 `State.Ready/Failed` 两种，
 * 于是「引擎起来了但页面白屏」与「引擎根本起不来」走同一条降级路径 ——
 * 前者会被判成内核坏了，触发不必要的重解包（15 秒）甚至安全模式（推开插件）。
 *
 * ## 四层之间的依赖关系
 *
 * ```
 *   ① 进程存活  ──→  ② 服务就绪  ──→  ③ 页面加载
 *                        │
 *                        └────────→  ④ 桥可用
 * ```
 *
 * ⚠️ **下层不通时上层必然是「未知」，不是「失败」**。
 * 进程都没起来，页面当然没加载 —— 但那是「还没轮到它」，不是「它坏了」。
 * 把两者混为一谈会导致「进程启动失败」被报成「页面加载失败」，
 * 排查时方向全错。
 */
object ManageHealth {

    /**
     * 单层状态。
     *
     * ⚠️ **`UNKNOWN` 与 `DOWN` 必须分开**：
     * - `UNKNOWN` = 还没到判断它的时机（下层没通）
     * - `DOWN` = 真的检查过并且失败了
     */
    enum class Layer(val id: String, val label: String) {
        /** 还没法判断（下层未就绪）。 */
        UNKNOWN("unknown", "未判断"),

        /** 检查过，正常。 */
        UP("up", "正常"),

        /** 检查过，失败。 */
        DOWN("down", "失败"),
        ;
    }

    /**
     * 四层快照。
     *
     * @param process 进程存活（`Process.isAlive` 或已退出）
     * @param service DSH 服务就绪（引擎打印了监听 URL）
     * @param page Web 页面加载（收到内核首帧 / `onUiReady`）
     * @param bridge Android 桥可用（页面能调到 `DSHAndroid.*`）
     * @param detail 人可读的诊断（哪一层、为什么）
     */
    data class Snapshot(
        val process: Layer,
        val service: Layer,
        val page: Layer,
        val bridge: Layer,
        val detail: String = "",
    ) {
        /** 四层全通。 */
        val isFullyHealthy: Boolean
            get() = process == Layer.UP && service == Layer.UP &&
                page == Layer.UP && bridge == Layer.UP

        /**
         * 整体结论：**第一层出问题的地方**。
         *
         * ⚠️ 这个字段是给降级决策用的，不是给人看的。设计说明要求
         * 「页面白屏先修页面」—— 判据就是「进程和服务都 UP，只有页面 DOWN」。
         */
        val firstBroken: Layer
            get() = when {
                process == Layer.DOWN -> Layer.DOWN
                service == Layer.DOWN -> Layer.DOWN
                page == Layer.DOWN -> Layer.DOWN
                bridge == Layer.DOWN -> Layer.DOWN
                else -> Layer.UNKNOWN
            }

        /** 一句话摘要（日志/诊断用）。 */
        fun summary(): String =
            "进程=${process.label} 服务=${service.label} " +
                "页面=${page.label} 桥=${bridge.label}" +
                if (detail.isNotEmpty()) "（$detail）" else ""
    }

    /**
     * 恢复建议 —— **降级决策的输入**。
     *
     * 设计说明 §6.3 的恢复链表：
     *
     * | 情况 | 处理 |
     * |---|---|
     * | A 正常，仅可选插件异常 | 保留可用功能并提示，**不强制切换** |
     * | A 无法就绪或关键功能不可用 | 保存现场，停止并确认退出，再启动 R |
     */
    enum class Remedy(val id: String) {
        /** 无需处理。 */
        NONE("none"),

        /** **只修页面**：服务正常但页面白屏。不切内核、不重解包。 */
        RELOAD_PAGE("reload-page"),

        /** **只修桥**：页面正常但桥不通（通常是插件没激活）。不切内核。 */
        REINIT_BRIDGE("reinit-bridge"),

        /** 重启引擎（服务层失败）。 */
        RESTART_ENGINE("restart-engine"),

        /** 走完整降级链（进程起不来）。 */
        ESCALATE("escalate"),
        ;
    }

    /**
     * 按四层状态给出恢复建议。
     *
     * ## ⚠️ 两条文档明确禁止的错误处置
     *
     * **① 页面白屏不能判成内核坏了**
     *
     * > 页面白屏而服务正常时优先恢复页面或关闭界面增强
     *
     * 进程和服务都 UP、只有页面 DOWN ⇒ 只重载页面。
     * 走完整降级会白等 15 秒重解包，还会把用户的插件配置推开。
     *
     * **② 模型网络错误不能触发内核切换**
     *
     * > 模型服务网络失败不直接触发内核切换
     *
     * ⚠️ 这一条**本函数判不了** —— 四层里没有「模型连通性」这一层。
     * 所以调用方必须自己保证：模型请求失败**不要**调到这里来。
     * 把网络错误映射成「服务 DOWN」是本函数无法防的错误，只能靠调用方遵守。
     */
    fun remedy(s: Snapshot): Remedy = when {
        // 进程起不来：唯一需要走降级链的情况
        s.process == Layer.DOWN -> Remedy.ESCALATE

        // 进程活着但服务没就绪：重启引擎（可能是端口冲突、profile 坏）
        s.service == Layer.DOWN -> Remedy.RESTART_ENGINE

        // 服务好了但页面白屏：**只修页面**（文档明确要求）
        s.page == Layer.DOWN -> Remedy.RELOAD_PAGE

        // 页面好了但桥不通：只重初始化桥
        s.bridge == Layer.DOWN -> Remedy.REINIT_BRIDGE

        else -> Remedy.NONE
    }

    /**
     * 构造快照的辅助：下层未通时，上层自动填 `UNKNOWN`。
     *
     * ⚠️ **用它而不是手工填四层**：手工填很容易把「还没轮到」写成「失败」，
     * 而那正是本类要消除的歧义。
     */
    fun snapshot(
        processAlive: Boolean,
        serviceReady: Boolean,
        pageLoaded: Boolean,
        bridgeReady: Boolean,
        detail: String = "",
    ): Snapshot {
        val p = if (processAlive) Layer.UP else Layer.DOWN
        // 进程没起来 ⇒ 后面三层都没轮到
        val s = when {
            !processAlive -> Layer.UNKNOWN
            serviceReady -> Layer.UP
            else -> Layer.DOWN
        }
        val pg = when {
            s != Layer.UP -> Layer.UNKNOWN
            pageLoaded -> Layer.UP
            else -> Layer.DOWN
        }
        val b = when {
            s != Layer.UP -> Layer.UNKNOWN
            bridgeReady -> Layer.UP
            else -> Layer.DOWN
        }
        return Snapshot(p, s, pg, b, detail)
    }

    /*
     * ⚠️ **本对象只做判断，不做动作**。
     *
     * 与 [ManageFsm] 同一分工：「判断」与「执行」分离，这样：
     * - 可以纯逻辑验证（不需要 Android 运行时）
     * - 调用方不必为了过检查而改变原有逻辑
     * - 排查时能明确区分「判成需要重载页面」与「重载页面失败了」
     *
     * 未实现：
     * - 四层状态的实际采集（现在各层数据散落在 EngineManager / MainActivity，
     *   需要有人把它们汇总成 Snapshot —— 那是下一步的接入工作）
     * - 「模型连通性」作为第五层（文档明确它不该触发内核切换，但也没要求单独成层）
     */
}
