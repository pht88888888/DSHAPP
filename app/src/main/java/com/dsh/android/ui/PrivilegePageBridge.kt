package com.dsh.android.ui

import android.content.Context
import com.dsh.android.core.DshApplication
import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import com.dsh.android.privilege.AdbPairingNotifier
import com.dsh.android.privilege.RootAccess
import com.dsh.android.privilege.ShizukuChannel

/** 页面领域能力；仅保留应用 Context，不持有 Activity 或窗口。 */
internal class PrivilegePageBridge(context: Context, private val rootMounted: () -> Boolean) : PrivilegePageBridgeActions {
    private val app = context.applicationContext

    override fun startWirelessPairing() {
        Log.i(DshApplication.TAG, "设置页请求配对无线调试")
        AdbPairingNotifier.start(app)
    }

    override fun cancelWirelessPairing() {
        AdbPairingNotifier.cancel(app)
    }

    override fun pairingNotificationsEnabled(): Boolean =
        AdbPairingNotifier.notificationsEnabled(app)

    override fun rootState(): String = try {
        val snap = RootAccess.snapshot(app)
        JSONObject()
            .put("known", snap != null)
            .put("state", snap?.result?.state?.name ?: "")
            .put("granted", snap?.result?.granted ?: false)
            .put("detail", snap?.result?.detail ?: "")
            .put("at", snap?.at ?: 0L)
            // ⚠️ 「探测到有 root」与「本会话里 root 工具真的挂上了」是**两件事**。
            //   工具是 `--patch` 挂的，只在引擎启动那一刻生效；用户点「重新检测」
            //   把结论刷成 GRANTED 之后，引擎已经在跑，overlay 不会再读 ——
            //   这个会话里**没有** root 工具。
            //   不把这两个事实分开暴露，界面就只能在「结论已可用」时一律宣称
            //   「root 工具已注册」，那是在说假话，而且用户无法自行发现（只能
            //   靠重启 App 才「突然好了」）。所以这里把挂载事实一并给出去。
            .put("mounted", rootMounted())
            .toString()
    } catch (t: Throwable) {
        Log.w(DshApplication.TAG, "读取 root 状态失败: ${t.message}")
        "{}"
    }

    override fun rootRecheck() {
        Log.i(DshApplication.TAG, "设置页请求重新检测 root")
        val app = this.app
        Thread {
            val result = RootAccess.detect(app, force = true)
            Log.i(DshApplication.TAG, "root 重新检测结果: ${result.state} ${result.detail}")
        }.start()
    }

    override fun shizukuState(): String = runCatching {
        JSONObject()
            .put("installed", ShizukuChannel.isManagerInstalled(app))
            .put("binder", ShizukuChannel.isBinderAlive())
            .put("granted", ShizukuChannel.hasPermission())
            .put("uid", ShizukuChannel.serverUid())
            .put("version", ShizukuChannel.serverVersion())
            .toString()
    }.getOrElse { t ->
        Log.w(DshApplication.TAG, "读取 Shizuku 状态失败: ${t.message}")
        "{}"
    }
}
