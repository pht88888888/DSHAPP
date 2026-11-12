package com.dsh.android.ui

/**
 * 「浏览器侧那一半到底坏没坏」的判据 —— **不依赖官方启动页长什么样**。
 *
 * ## 为什么必须重写这套判据（真机事实，2026-11-12）
 *
 * 旧判据在 [MainActivity.maybeRescueFromWebFailure] 里写着：
 * `line.contains("首帧正文=\"HARNESS")` ⇒ 判定「前端启动卡在官方启动页」。
 *
 * 这条判据有三处致命伤，且**互相独立**：
 *
 * ① **`首帧正文` 是第一次轮询的快照，不是当前状态。**
 *    `dsh-android-mobile` 的 `mpSignalUiReady()` 只在 `polls === 1` 时记录正文，
 *    而第一次轮询发生在插件 `apply()` 之后 —— 那一刻官方启动页**通常还在**。
 *    于是**一次完全正常**的启动也会打印 `首帧正文="HARNESS Loading plugins…"`。
 *    真机现场（CDY-TN90，2026-11-12 15:13:55）：`onUiReady` 已经在
 *    `.750` 报了（插件激活成功），`.870` 那条 console 行仍带着
 *    `首帧正文="HARNESS …"` ⇒ 120 毫秒之内被判「卡住」并降级到救援模式，
 *    而用户界面**本来就是好的**。降级之后 tier 还粘住，连续两次启动都在救援态。
 *
 * ② **用户会自己换掉启动动画 / 主题 / 加载文案。** 这不只是本项目的插件能改，
 *    用户装的主题类插件、自己写的 `client.js`、甚至官方版本的文案本地化都会
 *    改掉这块文字。判据钉在 `HARNESS` 上 ⇒ **文案一变，真正的卡死永远检测不到**
 *    （假阴性），而某些自定义文案里恰好带 HARNESS ⇒ 又变成假阳性。
 *
 * ③ **它把「一次快照」当成了「已确认的持续状态」。** 判据必须是
 *    「**在一段足够长的时间里，始终没拿到健康证据**」—— 这才是「卡住」的定义。
 *    任何单帧文本都证明不了持续性。
 *
 * ## 现在的判据（三条，都不看启动页长什么样）
 *
 * | 判据 | 来自哪里 | 处置 |
 * |---|---|---|
 * | **明确失败**：`Failed to load plugins`、整批 `/plugins/??` 资源加载失败 | 前端自报 / 内核 console | 立刻降级 |
 * | **健康证据**：`onUiReady` 非超时上报、`选择器体检 … 命中`、首帧正文不是失败页 | 本插件上报 | 立刻撤销待判，并记为健康 |
 * | **超时**：页面 `load` 之后 [CLIENT_DEADLINE_MS] 内**一次健康证据都没有** | 宿主的 `UiStage.PageLoaded` | 到期降级 |
 *
 * 关键差别：**「没看到健康证据」是唯一能证明「坏了」的东西**，
 * 「看到了某段特定文字」不是。文案是这个项目里最会变的东西（它属于前端，
 * 不属于我们），拿它当判据等于把别人的字符串当自己的契约。
 *
 * ⚠️ 这个对象**刻意不依赖任何 Android 类型**：判据要被真机行为测试逐条钉死
 * （`app/src/test/WebClientHealth.test.mjs` 抽取声明原文编译执行），
 * 一旦掺进 `Context`/`Log` 就没法在 JVM 上跑，判据又会退化成"靠注释保证"。
 */
object WebClientHealth {

    /**
     * 从「页面 load 完成」起，等健康证据的最长时间。
     *
     * 真机实测（CDY-TN90 / Android 10 / 华为 WebView 114，冷启动 12 次）：
     * `PageLoaded` → `onUiReady` 的间隔 1.4~4.3 秒，最慢一次 6.1 秒（清缓存后首启）。
     * 取 30 秒 = 最慢值的约 5 倍 —— 慢设备 + 冷缓存 + 用户在别处忙都盖得住，
     * 而真有插件坏掉时用户最多等 30 秒就能拿到救援界面（旧判据在这条路上
     * 是**永远等不到**的，因为文案一改就再没有任何判据）。
     */
    const val CLIENT_DEADLINE_MS = 30_000L

    /**
     * 插件自己上报「超时兜底」之后，再给它多久的自证清白时间。
     *
     * `超时兜底` 的语义是：`dsh-android-mobile` 等了 `UI_READY_MAX_WAIT_MS`
     * （9 秒）都没等到「界面真的可用」（官方启动页没消失 / 外壳没挂上）。
     * 它**不等于**坏了 —— 真机上见过页面 `readyState` 卡在 `interactive`
     * 而界面其实能用的情况。所以不立刻降级，而是把待判窗口缩短到 10 秒：
     * 这 10 秒里只要来一条健康证据就撤销，一条都不来才降级。
     */
    const val GAVE_UP_GRACE_MS = 10_000L

    /** 判据结论。 */
    enum class Verdict {
        /** 什么都没说 —— 不影响任何计时器。 */
        NONE,

        /** 明确失败（前端自己说了 / 整批资源加载失败）⇒ 立刻降级。 */
        EXPLICIT_FAILURE,

        /** 插件自报等不到可用界面 ⇒ 缩短待判窗口，到期且无健康证据才降级。 */
        GAVE_UP,

        /** 健康证据 ⇒ 撤销待判，并记为健康。 */
        HEALTHY,
    }

    /** 明确失败的关键词（前端原文，大小写固定，照抄）。 */
    private const val PLUGIN_LOAD_FAILED = "Failed to load plugins"

    /** 整批合并资源加载失败的标记：`/plugins/??a,b,c` 这种一次拉一批的 URL。 */
    private const val BATCH_RESOURCE_FAILED = "资源加载失败"
    private const val BATCH_MARKER = "/plugins/??"

    /** 插件自报「等不到可用界面」。 */
    private const val GAVE_UP_MARKER = "超时兜底"

    /** 插件自报「界面已可用」（`onUiReady` 的非超时上报）。 */
    private const val UI_READY_MARKER = "onUiReady"

    /** 选择器体检：4 个关键锚点全中 ⇒ 页面确实是真实 UI。 */
    private const val SELECTOR_PROBE = "选择器体检"

    /** 首帧正文标记（只在**判定其内容不是失败页**时用作健康证据）。 */
    private const val FIRST_TEXT_MARKER = "首帧正文="

    /**
     * 结构锚点计数标记：`锚点=2`。
     *
     * 由 `dsh-android-mobile` 的 `realUiAnchors()` 上报 —— 它数的是「真实 UI 里
     * 才有的结构性元素」（`[contenteditable="true"]`、`role=textbox`、`textarea`、
     * `button[aria-label]`、`_sessionRow`、`.mp-nav-btn`）。
     *
     * ⚠️ **这是本判据最重要的一条证据**，比 `onUiReady` 本身还硬：
     * 真机实测（CDY-TN90 / 2026-11-12 15:57，把 `dsh-android-market` 的
     * client.js 尾部加了一个 `)))` 之后）——**插件照样上报了 `onUiReady`**，
     * 而页面实际卡在官方启动页、锚点=0。也就是「插件活着」与「界面能用」
     * 是两件事，只有锚点数能区分。
     */
    private const val ANCHOR_MARKER = "锚点="

    /**
     * 正文里出现这些说明页面自己就是失败页 —— 此时**不能**算健康证据。
     *
     * 只放「明确的错误页文案」，不放启动页文案：启动页长什么样是前端的自由，
     * 用户换掉它、本地化它、改成自己的动画，都不该影响判定（见类注释 ②）。
     */
    private fun bodyIsFailurePage(text: String): Boolean =
        text.contains(PLUGIN_LOAD_FAILED) ||
            text.contains("Import failed") ||
            text.contains("did not activate") ||
            text.lowercase().contains("error")

    /**
     * 从行里读结构锚点数；没有这个字段时返回 null（老版本插件不上报）。
     *
     * 只取紧跟标记的数字前缀 —— 行尾还有别的字段（`首帧正文=…`），不能整段转数字。
     */
    fun anchors(line: String): Int? {
        val at = line.indexOf(ANCHOR_MARKER)
        if (at < 0) return null
        val tail = line.substring(at + ANCHOR_MARKER.length)
        val digits = StringBuilder()
        for (c in tail) {
            if (c in '0'..'9') digits.append(c) else break
        }
        return if (digits.isEmpty()) null else digits.toString().toIntOrNull()
    }

    /**
     * 给一行 console / 诊断文本定性。
     *
     * @param line 内核 console 行或 `pageDiag` 行原文。
     * @return 见 [Verdict]；无法定性时 [Verdict.NONE]。
     */
    fun classify(line: String): Verdict {
        if (line.isEmpty()) return Verdict.NONE

        // ① 明确失败：前端自己报的插件加载失败（单批里个别失败时它能活着说话）
        if (line.contains(PLUGIN_LOAD_FAILED)) return Verdict.EXPLICIT_FAILURE

        // ② 明确失败：整批 /plugins/?? 资源加载失败（任何一个 client bundle 语法错
        //    都会让整批失败，此时连本插件都加载不了，没有任何人上报 ①）
        if (line.contains(BATCH_RESOURCE_FAILED) && line.contains(BATCH_MARKER)) {
            return Verdict.EXPLICIT_FAILURE
        }

        // ③ 首帧正文里若是失败页，同样算明确失败（它是**内容**判据，不是启动页
        //    文案判据：改动画、换主题都不影响「页面显示的是失败页」这件事）
        val firstTextAt = line.indexOf(FIRST_TEXT_MARKER)
        if (firstTextAt >= 0) {
            val body = line.substring(firstTextAt + FIRST_TEXT_MARKER.length)
            if (bodyIsFailurePage(body)) return Verdict.EXPLICIT_FAILURE
        }

        // ④ **结构锚点判据**（最硬的一条，优先于任何文案）：
        //    · 锚点 ≥ 1 ⇒ 真实 UI 的元素确实在 DOM 里 ⇒ 健康；
        //    · 锚点 = 0 ⇒ 真实 UI 还没挂上。**此时哪怕插件报了 onUiReady 也不算健康** ——
        //      真机上（2026-11-12 15:57）插件加载成功、`onUiReady` 照报，而页面
        //      始终停在启动页、锚点一直是 0。判「健康」会让这种坏法永远不降级。
        val anchorCount = anchors(line)
        if (anchorCount != null) {
            return if (anchorCount > 0) Verdict.HEALTHY else Verdict.GAVE_UP
        }

        // ⑤ 插件自报等不到可用界面 ⇒ 弱信号，缩短待判窗口
        if (line.contains(GAVE_UP_MARKER)) return Verdict.GAVE_UP

        // ⑥ 健康证据（老版本插件兼容路径，没有 `锚点` 字段时）：onUiReady 的非超时
        //    上报 —— ③ 已把超时兜底与失败页分流走，不会误判
        if (line.contains(UI_READY_MARKER)) return Verdict.HEALTHY

        // ⑦ 健康证据：选择器体检命中 ⇒ 页面里已经有真实 UI 的锚点
        if (line.contains(SELECTOR_PROBE) && line.contains("命中") &&
            !line.contains("未命中")
        ) {
            return Verdict.HEALTHY
        }

        // ⑧ 健康证据（最后的兜底）：首帧正文取到了内容且不是失败页
        if (firstTextAt >= 0) {
            val body = line.substring(firstTextAt + FIRST_TEXT_MARKER.length)
            if (body.trim().length > 2 && !bodyIsFailurePage(body)) return Verdict.HEALTHY
        }

        return Verdict.NONE
    }

    /** 便捷判据：这行是不是明确失败。 */
    fun isExplicitFailure(line: String): Boolean = classify(line) == Verdict.EXPLICIT_FAILURE

    /** 便捷判据：这行是不是健康证据。 */
    fun isHealthy(line: String): Boolean = classify(line) == Verdict.HEALTHY
}
