package com.dsh.android.log

import android.content.Context
import com.dsh.android.core.DshApplication
import com.dsh.android.ui.MainActivity

/**
 * 日志系统自己的 Application 上下文持有者。
 *
 * ## 为什么单独持有一份，而不是用 `DshApplication.instance`
 *
 * 日志系统有一条**极早期的调用路径**：`attachBaseContext()` 里就要装落盘、装崩溃
 * 处理器，而那时 `Application.onCreate()` 还没跑、`DshApplication.instance` 还是
 * `lateinit` 未初始化的状态 —— 去读它会抛 `UninitializedPropertyAccessException`。
 *
 * 而崩溃路径上更麻烦：崩溃可能发生在 `onCreate` 之前或之中。
 *
 * 所以这里持有一份**最早就能拿到**的引用：`attachBaseContext(base)` 的参数本身
 * 就是一个可用的 `Context`（`ContextWrapper` 包装后的 Application），比
 * `onCreate` 早一整个阶段。
 *
 * ## 允许为 null
 *
 * [get] 返回可空 —— 所有调用方都必须容忍拿不到上下文（退回「只采集不依赖
 * Context 的部分」），绝不能因为拿不到上下文而让诊断包生成失败。
 * 见 [EnvironmentSnapshot] 的类注释。
 */
object LogContext {

    @Volatile
    private var ctx: Context? = null

    /** 装上上下文。幂等；重复安装只是覆盖（后续调用给的更完整，直接覆盖即可）。 */
    fun install(context: Context) {
        ctx = context.applicationContext ?: context
    }

    /** 当前上下文；未安装时为 null。 */
    fun get(): Context? = ctx

    /**
     * 应用私有目录。**所有日志落盘都以它为根**。
     *
     * 未安装上下文时返回 null —— 调用方据此退回纯内存模式。
     */
    fun filesDirOrNull(): java.io.File? = ctx?.filesDir

    /**
     * 用户能自己找到的导出目录。
     *
     * ## 为什么必须有这个（这是个真缺陷，不是锦上添花）
     *
     * 诊断包原来落在 `filesDir/logs/export/` —— **app 私有目录**。文件管理器
     * 根本进不去 `/data/data/<包名>/`，所以「导出诊断包」对用户而言等于
     * 「提示保存成功，但那个文件我永远拿不到」，也就**没法通过聊天软件发给开发者**。
     * 而这个功能存在的全部意义就是「用户把包发出来」。
     *
     * ## 落点选择
     *
     * | 情况 | 落点 | 用户怎么拿到 |
     * |---|---|---|
     * | 有存储权限 | `/sdcard/Download/` | 文件管理器 / 任意聊天软件的「发送文件」直接可见 |
     * | 无存储权限 | `getExternalFilesDir(DIRECTORY_DOWNLOADS)` | `Android/data/<包名>/files/Download/`，文件管理器可达 |
     * | 都拿不到 | 退回私有 `filesDir` | 只能靠界面里的「上传」按钮 |
     *
     * 与 `MainActivity.downloadTargetDir()` 同一套取舍（见 ⑦.5）：
     * 公共目录优先，权限不足时退到**外部**私有目录 —— 后者不需要任何权限，
     * 且仍能被文件管理器浏览到，比 `filesDir` 强得多。
     *
     * ⚠️ 用 `LogContext` 的上下文而不是 `DshApplication.instance`：导出可能发生在
     *   极早期，见类注释。
     */
    fun publicExportDirOrNull(): java.io.File? {
        val c = ctx ?: return null
        return try {
            val granted = c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                @Suppress("DEPRECATION")
                val pub = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (pub != null && (pub.isDirectory || pub.mkdirs())) return pub
            }
            c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir
        } catch (t: Throwable) {
            // 崩溃路径/极早期调用：拿不到就退回私有目录，绝不能让导出整个失败。
            c.filesDir
        }
    }
}
