package com.dsh.android.log

import android.util.Log as AndroidLog
import com.dsh.android.core.DshApplication

/**
 * `android.util.Log` 的**替身门面** —— 让全工程 476 处日志调用一行不改地落盘。
 *
 * ## 为什么要有这一层（这是整个日志系统的关键技巧）
 *
 * 改造前全工程有 **476 处 `Log.*`**，全部只写 logcat：**进程一死现场就没了**。
 * 而本项目最需要排查的恰恰是「任务跑一半自己结束了」「App 突然闪退」这类
 * 伴随进程消失的问题。
 *
 * 逐个改那 476 个调用点既慢又容易漏。所以反过来做：**把 `Log` 这个名字换掉**。
 * 本类的方法名与签名与 `android.util.Log` **逐一对应**（`d/i/w/e/v/wtf` 各两档、
 * `println`、`isLoggable`、`getStackTraceString`），于是每个文件只需改一行：
 *
 * ```kotlin
 * -import android.util.Log
 * +import com.dsh.android.log.DshLog as Log
 * ```
 *
 * `Log.i(TAG, "…")` 这样的调用点**一个字符都不用动**。
 *
 * ⚠️ 调用点里**不能出现全限定写法**（`android.util.Log.i(...)`）—— 那种写法会绕过
 * 本类。改造前已 grep 确认全工程零处，将来新增代码也要守住这条。
 *
 * ## 两条铁律
 *
 * 1. **logcat 行为一个字都不能变。** 每个方法都照旧转发给 `android.util.Log`，
 *    `adb logcat -s DSH:I` 这条既有排查路径必须继续可用。落盘是**附加**动作，
 *    不是替换。
 * 2. **日志绝不能把 App 搞崩。** 任何异常都被吞掉 —— 写日志失败是小事，
 *    因为写日志失败而崩溃是灾难（而且崩溃现场还写不出来）。
 *
 * ## 未安装时零开销
 *
 * [install] 之前 [sink] 为 null，每次调用只是一次 volatile 读 + 返回。所以
 * `DshApplication.attachBaseContext()` 之前发生的日志不会丢（那时本来也没有
 * 文件可写），也不会有额外代价。
 */
object DshLog {

    /**
     * 落盘出口。由 [com.dsh.android.DshApplication] 在 `attachBaseContext()` 里装上。
     *
     * `@Volatile`：安装发生在主线程，写入发生在任意线程（引擎读取线程、WebView
     * 的 JS 线程、崩溃处理器）。
     */
    @Volatile
    private var sink: ((Char, String, String) -> Unit)? = null

    /**
     * 装上落盘出口。**幂等**（重复安装只是覆盖同一个引用）。
     *
     * ⚠️ 必须尽早调用 —— 越晚，启动阶段的日志越可能丢在 `filesDir` 打开之前。
     * 见 [com.dsh.android.DshApplication.attachBaseContext]。
     */
    fun install(writer: (Char, String, String) -> Unit) {
        sink = writer
    }

    /** 落盘出口是否已就绪（自检用，不参与业务逻辑）。 */
    val installed: Boolean get() = sink != null

    // ── 内部转发 ──────────────────────────────────────────────────────────────

    /**
     * 转发一条日志：先落盘、再写 logcat。
     *
     * ⚠️ **顺序是刻意的**：落盘在前，万一写 logcat 抛异常（tag 超长等），
     * 至少文件里有。而 logcat 丢了还能靠 `adb` 重跑，文件丢了就没了。
     *
     * ⚠️ `tr` 非空时把堆栈**拼进正文**再落盘。`android.util.Log.w(tag, msg, tr)`
     * 在 logcat 里会显示 msg + 堆栈，而落盘侧没有第二个参数可用 —— 不拼就会
     * 丢掉堆栈，那正是排查时最需要的部分。
     */
    private fun emit(level: Char, tag: String, msg: String, tr: Throwable?) {
        val s = sink
        if (s != null) {
            try {
                val text = if (tr == null) msg else msg + "\n" + AndroidLog.getStackTraceString(tr)
                s(level, tag, text)
            } catch (_: Throwable) {
                // 铁律 2：写日志失败绝不能冒泡。
            }
        }
    }

    // ── 与 android.util.Log 逐一对应 ──────────────────────────────────────────
    // ⚠️ 方法名、参数顺序、返回类型必须与官方完全一致，否则 `as Log` 的替换
    //    会在编译期炸（那还好），或者更糟 —— 静默走到别的重载上去。

    @JvmStatic
    fun v(tag: String, msg: String): Int {
        emit('V', tag, msg, null)
        return AndroidLog.v(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable?): Int {
        emit('V', tag, msg, tr)
        return AndroidLog.v(tag, msg, tr)
    }

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        emit('D', tag, msg, null)
        return AndroidLog.d(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable?): Int {
        emit('D', tag, msg, tr)
        return AndroidLog.d(tag, msg, tr)
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        emit('I', tag, msg, null)
        return AndroidLog.i(tag, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable?): Int {
        emit('I', tag, msg, tr)
        return AndroidLog.i(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        emit('W', tag, msg, null)
        return AndroidLog.w(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?): Int {
        emit('W', tag, msg, tr)
        return AndroidLog.w(tag, msg, tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        emit('E', tag, msg, null)
        return AndroidLog.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?): Int {
        emit('E', tag, msg, tr)
        return AndroidLog.e(tag, msg, tr)
    }

    @JvmStatic
    fun wtf(tag: String, msg: String): Int {
        emit('E', tag, msg, null)
        return AndroidLog.wtf(tag, msg)
    }

    @JvmStatic
    fun wtf(tag: String, msg: String, tr: Throwable?): Int {
        emit('E', tag, msg, tr)
        return AndroidLog.wtf(tag, msg, tr)
    }

    /**
     * 按 priority 常量转发（`Log.println(Log.WARN, tag, msg)` 这种写法）。
     *
     * ⚠️ priority 用的是 `android.util.Log` 的常量（2..7），不是本类的字符级别。
     * 映射见 [levelOf]。
     */
    @JvmStatic
    fun println(priority: Int, tag: String, msg: String): Int {
        emit(levelOf(priority), tag, msg, null)
        return AndroidLog.println(priority, tag, msg)
    }

    /** 委托给官方实现 —— 语义（`Log.isLoggable` 会读系统属性）必须一致。 */
    @JvmStatic
    fun isLoggable(tag: String, level: Int): Boolean = AndroidLog.isLoggable(tag, level)

    /** 委托给官方实现 —— 堆栈格式必须与 logcat 里看到的一致，否则两边对不上。 */
    @JvmStatic
    fun getStackTraceString(t: Throwable?): String = AndroidLog.getStackTraceString(t)

    /**
     * `android.util.Log` 的 priority 常量 → 本模块的字符级别。
     *
     * ⚠️ 未知值落到 `'I'` 而不是抛异常：日志级别的映射不该成为崩溃点。
     */
    private fun levelOf(priority: Int): Char = when (priority) {
        AndroidLog.VERBOSE -> 'V'
        AndroidLog.DEBUG -> 'D'
        AndroidLog.INFO -> 'I'
        AndroidLog.WARN -> 'W'
        AndroidLog.ERROR, AndroidLog.ASSERT -> 'E'
        else -> 'I'
    }
}
