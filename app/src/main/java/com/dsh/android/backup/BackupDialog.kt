package com.dsh.android.backup

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dsh.android.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导出 / 导入对话框。
 *
 * ## 为什么单独抽一个类，而不是写在 MainActivity 里
 *
 * 两个入口都要用它（启动层按钮、以后设置页里的按钮），而 MainActivity 已经
 * 9000+ 行。这里的逻辑除了「弹窗 + 后台线程」之外没有 Activity 依赖，
 * 传 `Context` 就够。
 *
 * ## 线程模型
 *
 * - 弹窗、勾选、按钮回弹：主线程
 * - 统计体积、打包、解压：**单线程后台**（`Thread`，不用协程 —— 这些是纯 IO，
 *   而且必须在引擎挂掉时也能跑，不想引入生命周期依赖）
 * - 进度回调用 `view.post` 回主线程
 */
object BackupDialog {

    private const val TAG = "BackupDialog"

    // ── 导出 ────────────────────────────────────────────────────────────

    /**
     * 弹出「导出数据」对话框。
     *
     * 先同步统计各项体积（可能在后台），再把结果铺到列表里。
     */
    fun showExport(context: Context, onDone: ((File?) -> Unit)? = null) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_backup, null)
        val title = view.findViewById<TextView>(R.id.bkTitle)
        val hint = view.findViewById<TextView>(R.id.bkHint)
        val list = view.findViewById<LinearLayout>(R.id.bkList)
        val total = view.findViewById<TextView>(R.id.bkTotal)
        val progress = view.findViewById<ProgressBar>(R.id.bkProgress)
        val cancel = view.findViewById<TextView>(R.id.bkCancel)
        val action = view.findViewById<TextView>(R.id.bkAction)

        title.text = context.getString(R.string.backup_export_title)
        hint.text = context.getString(R.string.backup_export_hint)
        action.text = context.getString(R.string.backup_do_export)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        // 勾选框：id → CheckBox
        val boxes = LinkedHashMap<String, CheckBox>()
        val defaults = UserDataExporter.Options.defaultIds()

        // 先把行铺出来（体积稍后异步填），避免对话框迟迟不出现
        for (item in UserDataExporter.ITEMS) {
            val cb = CheckBox(context).apply {
                text = item.label
                isChecked = item.id in defaults
                setTextColor(0xFFE8EDF5.toInt())
                textSize = 13.5f
                setPadding(0, 12, 0, 12)
            }
            boxes[item.id] = cb
            list.addView(cb)
            if (item.risky) {
                val warn = TextView(context).apply {
                    text = "⚠️ " + context.getString(R.string.backup_risky_hint)
                    setTextColor(0xFFE0A33A.toInt())
                    textSize = 11.5f
                    setPadding(dp(context, 30), 0, 0, dp(context, 8))
                }
                list.addView(warn)
            }
        }

        fun refreshTotal() {
            val ids = boxes.filterValues { it.isChecked }.keys
            val bytes = UserDataExporter.ITEMS
                .filter { it.id in ids }
                .sumOf { UserDataExporter.sizeOf(context, it).bytes }
            total.text = if (ids.isEmpty()) {
                context.getString(R.string.backup_nothing_selected)
            } else {
                context.getString(R.string.backup_total_size, UserDataExporter.humanSize(bytes))
            }
            action.isEnabled = ids.isNotEmpty()
            action.alpha = if (ids.isEmpty()) 0.4f else 1f
        }

        for (cb in boxes.values) cb.setOnCheckedChangeListener { _, _ -> refreshTotal() }
        refreshTotal()

        // 异步把每项的实际大小补到标签上（大目录统计可能要一两秒）
        Thread({
            val sizes = UserDataExporter.ITEMS.associate { it.id to UserDataExporter.sizeOf(context, it) }
            view.post {
                for ((id, sz) in sizes) {
                    val cb = boxes[id] ?: continue
                    val item = UserDataExporter.ITEMS.first { it.id == id }
                    if (!sz.exists) {
                        cb.text = context.getString(R.string.backup_empty_item, item.label)
                        cb.isEnabled = false
                        cb.isChecked = false
                    } else {
                        cb.text = "${item.label}  ·  ${UserDataExporter.humanSize(sz.bytes)}"
                    }
                }
                refreshTotal()
            }
        }, "dsh-backup-size").start()

        val running = AtomicBoolean(false)

        cancel.setOnClickListener { if (!running.get()) dialog.dismiss() }

        action.setOnClickListener {
            if (running.get()) return@setOnClickListener
            val ids = boxes.filterValues { it.isChecked }.keys.toSet()
            if (ids.isEmpty()) return@setOnClickListener

            running.set(true)
            action.isEnabled = false
            cancel.isEnabled = false
            progress.visibility = View.VISIBLE
            action.text = context.getString(R.string.backup_working)

            Thread({
                val out = UserDataExporter.export(context, UserDataExporter.Options(ids)) { p ->
                    view.post { progress.progress = (p * 100).toInt() }
                }
                view.post {
                    running.set(false)
                    dialog.dismiss()
                    onDone?.invoke(out)
                }
            }, "dsh-backup-export").start()
        }

        dialog.show()
    }

    // ── 导入 ────────────────────────────────────────────────────────────

    /**
     * 弹出「导入数据」对话框。
     *
     * 先让用户选一份备份（默认列出「下载」目录里的 `dsh-backup-*.zip`），
     * 选中后**解析这个包里有什么**，再让用户勾选要恢复的项。
     */
    fun showImport(context: Context, onDone: ((UserDataImporter.Result?) -> Unit)? = null) {
        val candidates = UserDataImporter.findBackups(context)
        if (candidates.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.backup_import_title)
                .setMessage(R.string.backup_import_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // 多份备份时先让用户挑一份。
        //
        // ⚠️ **不能用 `setItems()`。** 它走的是系统默认的列表样式：本 App 是深色
        //   主题（`android:windowBackground = #0F1115`），而 `setItems` 生成的
        //   条目背景是浅色、我们又没有指定文字颜色 —— 结果就是**白底白字，
        //   整个列表看起来是空白的**，用户只看到标题和「取消」，以为点不动。
        //   （2026-10-23 真机反馈：「点导入只出了这个，什么意思」。）
        //
        //   改成自绘列表：每一行是 TextView，显式给背景 + 文字色，
        //   与导出对话框保持同一套视觉。
        val listView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        }
        val dlg = AlertDialog.Builder(context)
            .setTitle(R.string.backup_import_title)
            .setView(listView)
            .setNegativeButton(R.string.backup_cancel, null)
            .create()

        // 选中态高亮：给用户「点得动」的反馈，否则完全看不出这是可点的行
        val pressedBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF2C3444.toInt())
            cornerRadius = dp(context, 10).toFloat()
        }
        val normalBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF232936.toInt())
            cornerRadius = dp(context, 10).toFloat()
            setStroke(dp(context, 1), 0xFF3A4353.toInt())
        }

        for ((index, f) in candidates.withIndex()) {
            val row = TextView(context).apply {
                // ⚠️ **必须显式给背景 + 文字色 + 圆角描边。**
                //
                //   原实现用 `setItems()`，那是系统默认列表样式：本 App 是深色主题
                //   （windowBackground = #0F1115），而系统条目给的是浅底 + 我们没
                //   指定字色 ⇒ **白底白字，整行完全看不见**。用户看到的只有一个
                //   标题和「取消」，以为点不动。
                //
                //   现在每一行都是一张独立的卡片：深灰底 + 1dp 描边 + 圆角，
                //   一眼能看出「这是一项，能点」。
                text = "${f.name}\n（${UserDataExporter.humanSize(f.length())}）"
                setTextColor(0xFFDCE6F5.toInt())
                textSize = 13.5f
                setLineSpacing(dp(context, 4).toFloat(), 1.2f)
                setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                background = normalBg
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dlg.dismiss()
                    showImportFor(context, f, onDone)
                }
                // 按压反馈 —— 没有它点了像没点
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> v.background = pressedBg
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> v.background = normalBg
                    }
                    false   // 不消费事件，让 OnClickListener 照常触发
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // 多份之间留 8dp：卡片之间有间隔才看得出是「几个可选项」而不是一个整体
            if (index > 0) lp.topMargin = dp(context, 8)
            listView.addView(row, lp)
        }

        dlg.show()
    }

    private fun showImportFor(
        context: Context,
        zip: File,
        onDone: ((UserDataImporter.Result?) -> Unit)?,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_backup, null)
        val title = view.findViewById<TextView>(R.id.bkTitle)
        val hint = view.findViewById<TextView>(R.id.bkHint)
        val fileLine = view.findViewById<TextView>(R.id.bkFile)
        val list = view.findViewById<LinearLayout>(R.id.bkList)
        val total = view.findViewById<TextView>(R.id.bkTotal)
        val progress = view.findViewById<ProgressBar>(R.id.bkProgress)
        val cancel = view.findViewById<TextView>(R.id.bkCancel)
        val action = view.findViewById<TextView>(R.id.bkAction)

        title.text = context.getString(R.string.backup_import_title)
        hint.text = context.getString(R.string.backup_import_hint)
        action.text = context.getString(R.string.backup_do_import)
        fileLine.visibility = View.VISIBLE
        fileLine.text = zip.name
        action.isEnabled = false
        total.text = context.getString(R.string.backup_reading)

        val dialog = AlertDialog.Builder(context).setView(view).setCancelable(true).create()
        val boxes = LinkedHashMap<String, CheckBox>()
        val running = AtomicBoolean(false)

        cancel.setOnClickListener { if (!running.get()) dialog.dismiss() }

        // 解析包内容（后台）
        Thread({
            val found = UserDataImporter.inspect(zip)
            view.post {
                list.removeAllViews()
                if (found.isEmpty()) {
                    total.text = context.getString(R.string.backup_no_restorable_data)
                    return@post
                }
                for (f in found) {
                    val cb = CheckBox(context).apply {
                        // 默认全勾 —— 用户主动点了「导入」，意图就是恢复
                        isChecked = true
                        text = context.getString(R.string.backup_item_summary, f.label, f.entries, UserDataExporter.humanSize(f.bytes))
                        setTextColor(0xFFE8EDF5.toInt())
                        textSize = 13.5f
                        setPadding(0, 12, 0, 12)
                    }
                    boxes[f.id] = cb
                    list.addView(cb)
                }
                total.text = context.getString(R.string.backup_restorable_count, found.size)
                action.isEnabled = true
            }
        }, "dsh-backup-inspect").start()

        action.setOnClickListener {
            if (running.get()) return@setOnClickListener
            val ids = boxes.filterValues { it.isChecked }.keys.toSet()
            if (ids.isEmpty()) return@setOnClickListener

            running.set(true)
            action.isEnabled = false
            cancel.isEnabled = false
            progress.visibility = View.VISIBLE
            action.text = context.getString(R.string.backup_working)

            Thread({
                val r = UserDataImporter.restore(context, zip, ids) { p ->
                    view.post { progress.progress = (p * 100).toInt() }
                }
                view.post {
                    running.set(false)
                    dialog.dismiss()
                    onDone?.invoke(r)
                }
            }, "dsh-backup-import").start()
        }

        dialog.show()
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** 导出结果的提示文案（调用方拿去 Toast）。 */
    fun exportResultText(context: Context, file: File?): String = if (file == null) {
        context.getString(R.string.backup_export_failed_detail)
    } else {
        // ⚠️ 给**完整路径**：用户要用文件管理器找到它、或从聊天软件发出去
        context.getString(R.string.backup_exported, file.absolutePath, UserDataExporter.humanSize(file.length()))
    }

    /** 导入结果的提示文案。 */
    fun importResultText(context: Context, r: UserDataImporter.Result?): String = when {
        r == null -> context.getString(R.string.backup_import_failed_detail)
        r.entries == 0 -> context.getString(R.string.backup_no_imported_files)
        else -> buildString {
            append(context.getString(R.string.backup_restored_count, r.entries, UserDataExporter.humanSize(r.bytes)))
            if (r.failed > 0) append(context.getString(R.string.backup_failed_count, r.failed))
            // 跨 flavor 导入时改写了配置里的绝对路径 —— 明确告诉用户，
            // 否则他不知道为什么能跨版本用
            if (r.fixedPaths > 0) append(context.getString(R.string.backup_fixed_paths, r.fixedPaths))
            r.backupDir?.let { append(context.getString(R.string.backup_original_saved, it.name)) }
            append(context.getString(R.string.backup_restart_hint))
        }
    }
}
