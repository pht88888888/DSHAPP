package com.dsh.android.manage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.android.log.EventLog
import java.io.File

/**
 * **最小原生恢复出口** —— 全部 DSH 环境都起不来时的最后一道门。
 *
 * ## 它为什么必须存在
 *
 * 设计说明 §4：
 *
 * > 共享底座故障可能影响两套内核，因此仍需要**不依赖 Node 的原生恢复页**。
 *
 * §5.3.1：
 *
 * > 全部 DSH 环境无法启动时，只显示**最小原生恢复出口**，例如重试、
 * > 日志导出和受控恢复包导入，**不建设独立业务设置中心**。
 *
 * ## ⚠️ 三条硬性约束
 *
 * **① 不依赖引擎**
 *
 * 整个类只用 Android 原生 API（View / Activity / File），**一行引擎代码都不调**。
 * 这是它存在的全部意义 —— 引擎、Node、Termux 全挂时它仍要能打开。
 *
 * ⚠️ 所以它**不能** import `EngineManager`。一旦依赖，引擎挂了它也挂。
 * 需要引擎信息时只读磁盘上的日志文件，不调它的 API。
 *
 * **② 不做成「设置中心」**
 *
 * 文档明确「不建设独立业务设置中心」。所以这里只有四个动作：
 * 重试 / 导出日志 / 导入恢复包 / 查看诊断。日常设置仍在官方插件的「安卓基座」里。
 *
 * **③ 用纯代码构建 UI，不用 XML 布局**
 *
 * 理由：XML 布局如果被改坏（资源混淆、主题缺失）会让这个页面本身打不开 ——
 * 而它是最后一道门。纯代码构建不依赖 `R.layout.*`，只要 Activity 能启动就能显示。
 *
 * ⚠️ 代价是没有预览、改样式要重编译。对「最后一道门」来说这是值得的：
 * 它**永远不该因为布局资源问题而失效**。
 */
class RecoveryActivity : Activity() {

    private companion object {
        const val TAG = "DSH"
        const val BG = 0xFF0E1116.toInt()
        const val FG = 0xFFE6E9EF.toInt()
        const val DIM = 0xFF8B93A3.toInt()
        const val ACCENT = 0xFF4C8DFF.toInt()
        const val WARN = 0xFFF0A92E.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "进入原生恢复入口")
        EventLog.record("recovery.open")

        // 纯代码构建（见 KDoc 约束③）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        root.addView(title())
        root.addView(subtitle())
        root.addView(stateCard())
        root.addView(actionButton("重新启动 DSH") { retry() })
        root.addView(actionButton("查看日志") { openLogs() })
        root.addView(actionButton("导出诊断包") { exportDiagnostics() })
        root.addView(
            actionButton("导入恢复包（待实现）", enabled = false) { /* G4 实现 */ },
        )
        root.addView(footer())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────

    private fun title() = TextView(this).apply {
        text = "DSH 恢复模式"
        setTextColor(FG)
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtitle() = TextView(this).apply {
        text = "引擎无法启动时的最小恢复入口。这里不依赖 Node / 引擎，只做最基本的几件事。"
        setTextColor(DIM)
        textSize = 13f
        setPadding(0, dp(8), 0, dp(18))
    }

    /**
     * 状态卡：把「为什么进到这里」如实摆出来。
     *
     * ⚠️ 内容全部来自**磁盘上的状态文件**，不调引擎 API（见 KDoc 约束①）。
     */
    private fun stateCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF171A21.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        fun row(k: String, v: String, color: Int = FG) {
            box.addView(TextView(this).apply {
                text = "$k：$v"
                setTextColor(color)
                textSize = 13f
                setPadding(0, dp(3), 0, dp(3))
            })
        }

        row("管理状态", describeManageState())
        row("引擎进程", describeEngineProcess())
        row("最近失败", describeLastFailure(), WARN)
        return box
    }

    private fun actionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isEnabled = enabled
            setTextColor(if (enabled) Color.WHITE else DIM)
            setBackgroundColor(if (enabled) ACCENT else 0xFF2A2F3A.toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

    private fun footer() = TextView(this).apply {
        text = "日常设置（备份、导入、密码）在 DSH 正常启动后的" +
            "「插件 → 安卓基座」里，这里刻意不做成设置中心。"
        setTextColor(DIM)
        textSize = 11.5f
        setPadding(0, dp(20), 0, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── 状态读取（只读磁盘，不碰引擎）─────────────────────────────────────

    /** 读管理状态文件。⚠️ 不调 `ManageState.read()` —— 那会 import 引擎包。 */
    private fun describeManageState(): String {
        val f = File(filesDir, "manage/manage-state.json")
        if (!f.isFile) return "无记录（从未转换过）"
        return try {
            val o = org.json.JSONObject(f.readText())
            val phase = o.optString("phase", "?")
            val desired = o.optString("desired", "?")
            val observed = o.optString("observed", "?")
            val epoch = o.optLong("epoch", 0)
            "phase=$phase epoch=$epoch desired=$desired observed=$observed"
        } catch (t: Throwable) {
            "状态文件损坏（${t.message}）"
        }
    }

    /**
     * 检查引擎进程是否还在。
     *
     * ⚠️ 用 `/proc` 而不是 `Process` 引用：本 Activity 独立于引擎，
     * 拿不到 `EngineManager.process`。查账本里的 PID 更接近事实。
     */
    private fun describeEngineProcess(): String {
        val f = File(filesDir, "manage/manage-ledger.json")
        if (!f.isFile) return "无账本记录"
        return try {
            val arr = org.json.JSONArray(f.readText())
            if (arr.length() == 0) return "账本为空"
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val pid = e.optInt("pid", 0)
                val alive = File("/proc/$pid").exists()
                if (sb.isNotEmpty()) sb.append("，")
                sb.append("pid=$pid ${if (alive) "存在" else "已退出"}")
            }
            sb.toString()
        } catch (t: Throwable) {
            "账本损坏（${t.message}）"
        }
    }

    private fun describeLastFailure(): String {
        val f = File(filesDir, "rescue/state.json")
        if (!f.isFile) return "无记录"
        return try {
            val o = org.json.JSONObject(f.readText())
            o.optString("reason", "无记录").take(120)
        } catch (t: Throwable) {
            "读取失败（${t.message}）"
        }
    }

    // ── 动作 ─────────────────────────────────────────────────────────────

    /**
     * 重试：**重启整个 App 进程**而不是调引擎。
     *
     * ⚠️ 为什么不调 `EngineManager.start()`：本 Activity 的设计前提是
     *   「引擎可能已经坏到调不动」。走进程重启能让所有状态归零，
     *   包括那些卡在内存里的坏状态。
     */
    private fun retry() {
        EventLog.record("recovery.retry")
        val i = packageManager.getLaunchIntentForPackage(packageName)
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        // 杀掉自己，让新进程从头开始
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun openLogs() {
        val i = Intent(this, com.dsh.android.LogViewerActivity::class.java)
        startActivity(i)
    }

    /**
     * 导出诊断包。
     *
     * ⚠️ 这里**不做**完整的加密备份（那是 G4 的事）。只把日志目录打个包
     *   放到用户能找到的位置 —— 诊断包不含凭据（`DiagnosticBundle` 已脱敏）。
     */
    private fun exportDiagnostics() {
        EventLog.record("recovery.export")
        val i = Intent(this, com.dsh.android.LogViewerActivity::class.java)
        i.putExtra("autoExport", true)
        startActivity(i)
    }

    // ── 边界说明 ─────────────────────────────────────────────────────────

    /*
     * ⚠️ **本 Activity 刻意不做的事**：
     *
     * - ❌ 不调 `EngineManager`（引擎可能已坏，那是本页存在的前提）
     * - ❌ 不读 `ManageState` / `ManageEnv` 等 Kotlin 对象（它们 import 引擎包）
     * - ❌ 不用 XML 布局（布局资源坏掉会让最后一道门失效）
     * - ❌ 不做日常设置（文档明确「不建设独立业务设置中心」）
     *
     * 未实现：
     * - **导入恢复包**（按钮已置灰）—— 依赖 G4 的加密备份格式
     * - 「停用故障扩展」—— 依赖 G6 的扩展系统
     * - 「强制停止本应用」的引导文案 —— 需要先有只读恢复态
     */
}
