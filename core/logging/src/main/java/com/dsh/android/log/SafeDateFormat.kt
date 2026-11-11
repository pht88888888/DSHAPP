package com.dsh.android.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 线程安全的日期格式化器 —— 每个线程一份 [SimpleDateFormat]。
 *
 * ## 🔴 为什么必须包一层（2026-10-14 对抗式审查查出）
 *
 * `java.text.SimpleDateFormat` **不是线程安全的**：它内部有一个可变的 `Calendar`
 * 字段，`format()` 会先 `calendar.setTime(date)` 再逐字段读出来。两个线程交错时，
 * A 线程可能读到 B 线程刚设进去的时刻 —— 输出一个**语法完全合法、但属于另一个
 * 线程的另一个时刻**的时间戳。
 *
 * 而本项目的日志格式正是「时间戳 + 线程名 + 正文」，`format()` 的调用点分布在
 * 至少 6 个文件、被多个线程并发调用（真机 `app.log` 里出现过 14 个不同线程名，
 * 且有 3 个毫秒桶内出现 ≥2 个不同线程）。隔离环境复现（4 线程 × 5 万次）：
 *
 * ```
 * calls=200000 WRONG=136729 (68.36%)
 * expect=2025-10-09 16:53:22.931  got=2025-10-09 16:53:20.001   ← 差了近 3 秒
 * ```
 *
 * **诚实标注证据等级**：用真机 `events.jsonl` 的 `t`（独立 `System.currentTimeMillis()`）
 * 交叉校验 `app.log` 的 182 条 Event 行，偏差分布是 `0ms→142 / 1ms→38 / 5ms→1 /
 * 6ms→1`，**偏差 >100 ms 的 0 条** —— 也就是说这个竞态在真机当前采样下**尚未观测到**，
 * 它是「调用路径确定 + 隔离环境必现」，不是「已在真机上抓到」。之所以没抓到，
 * 可能因为单次 `format` 的临界区极短（微秒级）而真机写入频率不高。
 *
 * 但代价不对称：修它的成本是每个线程一个对象（几十字节），不修则一旦触发，
 * 分析者按时间戳与 `events.jsonl` / 崩溃现场对齐时会得到**错误结论**，
 * 而且没有任何字段能提示那一行不可信。所以照修。
 *
 * ## 为什么用 `ThreadLocal` 而不是每次 `new`
 *
 * 每次 `new SimpleDateFormat` 要重新解析 pattern、建 `Calendar`，是微秒级开销；
 * 而 `app.log` 是**每行 flush** 的热路径（崩溃日志完整性靠它），不该在这里付
 * 构造代价。`ThreadLocal` 让每个线程只构造一次。
 *
 * ⚠️ **不要改回共享实例**，也不要「加个 synchronized」—— 后者会把日志写入串行化，
 *   而日志写入路径上已经有 [LogStore] 的 `lock`，再加一层锁只会制造死锁风险。
 */
class SafeDateFormat(pattern: String, locale: Locale = Locale.US) {

    /** 存成属性，[fmtOf] 兜底重建时要用（构造参数本身不是属性）。 */
    private val pattern = pattern
    private val locale = locale

    private val holder = ThreadLocal.withInitial { SimpleDateFormat(pattern, locale) }

    /**
     * ⚠️ `ThreadLocal.get()` 在 Kotlin 里被看成可空（平台类型 `T!` 经
     *   `withInitial` 后推断成 `SimpleDateFormat?`）。刚 `withInitial` 过就不可能
     *   是 null，但编译器不认 —— 用 `?:` 兜一个新实例，比 `!!` 安全：
     *   万一将来有人把 `withInitial` 换成裸 `ThreadLocal()`，`!!` 会直接 NPE
     *   炸掉写日志的线程（铁律 ①），而 `?:` 只是退化成「这次多构造一个对象」。
     */
    private fun fmtOf(): SimpleDateFormat = holder.get() ?: SimpleDateFormat(pattern, locale)

    /** 等价于 `SimpleDateFormat(pattern).format(date)`，但线程安全。 */
    fun format(date: Date): String = fmtOf().format(date)

    /**
     * 解析。⚠️ 返回可空 —— `SimpleDateFormat.parse()` 在 Java 里就声明成可空，
     * 解析失败时返回 null（不会抛异常）。签名照抄，别在这里假装非空。
     *
     * ⚠️ 解析路径同样要独占实例 —— `parse()` 也写那个共享的 `Calendar`。
     */
    fun parse(source: String): Date? = fmtOf().parse(source)
}
