package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
/** 保守的本地语法筛查；不替代引擎真实启动判据。 */
internal object PluginSyntaxPreflight {
    /**
     * 安装期**语法预检**：把「一眼就是坏的」插件挡在挂载之前。
     *
     * ## 为什么需要它（不是锦上添花）
     *
     * L1 二分的代价是 `log2(N)` 次**真实启动**，每次最长 [PROBE_TIMEOUT_MS]
     * （60 秒），而总预算只有 [ISOLATION_BUDGET_MS]（3 分钟）。真机那个装了
     * **40 个插件**的用户：`log2(40) ≈ 6` 次探测 × 20~60 秒 ⇒ **必然超预算**，
     * 二分在收敛到具体插件之前就放弃，然后落 L2（全部插件不生效）。
     *
     * 而「语法坏了」是**本地可判**的，一次读取就能定位，不需要启动任何进程。
     * 把它前置，等于把最常见的那类故障从「6 次真实启动」降到「0 次」。
     *
     * ## 判据刻意保守（这一条是实测定的，不是设计出来的）
     *
     * 只认两类**结构性**损坏：引号/模板字符串未闭合、块注释未闭合。外加文件为空。
     *
     * ⚠️ **第一版还数了括号配平，在真实文件上误判 5/10 个正常插件** ——
     *   因为 JS 的**正则字面量**合法地含括号与引号（`/^[a-z0-9_-]{1,24}$/`、`/"/g`）。
     *   加上正则识别后括号也能算对，但仍然不划算：多一条判据就多一份误杀风险，
     *   而引号/块注释这两类已经覆盖「文件被截断」「粘贴漏引号」这两个最常见坏法，
     *   且对全部现有插件文件**零误报**（13 个真实文件 + 13 个构造用例实测）。
     *
     * ⚠️ **宁可放过，不可错杀**：误判会把一个好插件禁掉，用户看到「插件没生效」
     *   却查不出原因 —— 比不检更糟。所以读文件失败、编码异常等**一律放行**。
     *
     * ## 为什么不用 `node --check`
     *
     * 那需要拿得到可执行的 node 并起一个进程。本方法在 `start()` 里被调用，
     * 此时 node 二进制**可能还没解包完**（[TermuxBootstrap.installNodeRuntime]
     * 之后才有），而且为一个 200 行的文件起一次 node 进程在低端机上要 300ms+，
     * 乘 40 个插件就是十几秒 —— 与它要省下的时间相抵。
     *
     * @param entry 插件入口（`lib/index.js`）。
     * @return true = 看着没问题（或无法判断），照常挂载；false = 明显坏了，跳过。
     */
    fun preflightPluginSyntax(entry: File): Boolean {
        val text = try {
            entry.readText()
        } catch (t: Throwable) {
            // 读不了不是「坏了」—— 可能是权限/编码问题，交给引擎自己判。
            Log.w(TAG, "插件语法预检：读取失败，放行（${entry.parentFile?.name}）: ${t.message}")
            return true
        }
        if (text.isBlank()) {
            Log.w(TAG, "插件语法预检：入口文件为空，跳过挂载（${entry.parentFile?.name}）")
            return false
        }

        // 逐字符扫描，跳过字符串 / 注释 / **正则字面量**，只判两件事：
        // 「引号未闭合」与「块注释未闭合」。
        //
        // ⚠️ **刻意不数括号**（第一版数了，在真实文件上误判 5/10 个正常插件）。
        //   原因是 JS 的**正则字面量**里合法地含括号与引号，例如
        //   `/^[a-z0-9_-]{1,24}$/`、`/"/g`。不处理正则就会把它们当代码括号，
        //   于是**完全正常的插件被判坏**。而误杀的后果比不检严重得多
        //   （用户看到插件莫名不生效、还查不出原因）。
        //   引号/块注释这两类判据经实测对全部现有插件文件**零误报**，
        //   而它们恰好覆盖「文件被截断」「粘贴时漏了引号」这两种最常见的坏法。
        //
        // 状态机用「代码区栈」表达：栈底永远是 CODE，进入模板字符串时压入
        // 反引号，模板里的 `${` 再压一层 CODE，`}` 弹回模板层。
        val CODE = '\u0000'
        val stack = ArrayDeque<Char>()
        stack.addLast(CODE)
        var i = 0
        val n = text.length
        var inLineComment = false
        var inBlockComment = false
        // 正则字面量状态
        var inRegex = false
        var inRegexClass = false
        // 上一个有意义的代码字符，用来判断 `/` 是除号还是正则的开始
        var prev = ' '
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            val top = stack.last()

            if (inLineComment) {
                if (c == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inRegex) {
                when {
                    c == '\\' -> { i += 2; continue }
                    c == '[' -> inRegexClass = true
                    c == ']' -> inRegexClass = false
                    c == '/' && !inRegexClass -> {
                        inRegex = false
                        i++
                        while (i < n && text[i].isLetter()) i++
                        prev = '/'
                        continue
                    }
                }
                i++
                continue
            }
            // 模板字符串内部
            if (top == '`') {
                when {
                    c == '\\' -> { i += 2; continue }
                    c == '`' -> { stack.removeLast(); i++; prev = '`'; continue }
                    c == '$' && next == '{' -> { stack.addLast(CODE); i += 2; continue }
                    else -> { i++; continue }
                }
            }
            // 普通字符串内部
            if (top == '\'' || top == '"') {
                when {
                    c == '\\' -> { i += 2; continue }
                    c == top -> { stack.removeLast(); i++; prev = top; continue }
                    else -> { i++; continue }
                }
            }

            // 代码区
            when {
                c == '/' && next == '/' -> { inLineComment = true; i += 2; continue }
                c == '/' && next == '*' -> { inBlockComment = true; i += 2; continue }
                // `/` 出现在「不是值结尾」的位置 ⇒ 正则字面量的开始。
                // 值结尾 = 标识符/数字/`)`/`]`/`}`/引号，那些位置上的 `/` 是除号。
                c == '/' && (prev == ' ' || prev in "=(,:[!&|?{};+-*%<>~^") -> {
                    inRegex = true
                    inRegexClass = false
                    i++
                    continue
                }
                c == '\'' || c == '"' || c == '`' -> { stack.addLast(c); i++; prev = c; continue }
                c == '}' -> if (stack.size > 1) stack.removeLast()
            }
            if (!c.isWhitespace()) prev = c
            i++
        }
        // 只有 CODE 之外的**引号**才算未闭合；模板层残留也是未闭合。
        val unclosedQuotes = stack.drop(1)

        val problems = ArrayList<String>()
        if (unclosedQuotes.isNotEmpty()) problems += "引号/模板未闭合（${unclosedQuotes.size} 处）"
        if (inBlockComment) problems += "块注释未闭合"
        if (problems.isEmpty()) return true
        Log.w(
            TAG,
            "插件语法预检：疑似损坏，跳过挂载（${entry.parentFile?.parentFile?.name}）：" +
                problems.joinToString("；"),
        )
        return false
    }
}
