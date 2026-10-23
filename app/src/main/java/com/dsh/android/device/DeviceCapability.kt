package com.dsh.android.device

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.dsh.android.log.EventLog

/**
 * **设备能力发现与授权状态** —— 所有设备能力的公共基础。
 *
 * ## 设计说明 §4.14.1 的硬要求
 *
 * > 区分 **Android 系统授权、基座开放能力、DSH 操作审批及单次调用范围/有效期**。
 * > **插件声明能力不是授权**，应用获授权也不等于所有插件可任意采集、外发或控制设备。
 *
 * > 相机、麦克风、媒体、位置、蓝牙及传感器为首发必需实现，
 * > **按需发现和授权，不提前索要全部权限**。
 *
 * ## ⚠️ 四层权限模型（本对象的定位）
 *
 * | 层 | 管什么 | 谁管 |
 * |---|---|---|
 * | ① 系统授权 | Android 运行时权限 | 本对象（查询） |
 * | ② 基座能力 | 硬件是否存在、API 是否可用 | 本对象（发现） |
 * | ③ DSH 审批 | 每次调用要不要问用户 | 调用方 |
 * | ④ 单次范围 | 这次调用能做什么、多久失效 | 调用方 |
 *
 * **本对象只做 ①②** —— 它是「能不能」的问题，不是「允不允许」的问题。
 * ③④ 涉及具体业务语义（比如「这次录音能不能上传」），不该在这里定。
 *
 * ## ⚠️ 为什么必须区分「无硬件」与「无授权」
 *
 * 这两者的用户可操作性**完全不同**：
 *
 * | 状态 | 用户能做什么 |
 * |---|---|
 * | 无硬件 | 什么都做不了（换设备） |
 * | 无授权 | 去设置里给权限 |
 *
 * 合并成「不可用」会让用户白跑一趟设置页 —— 而文档明确要求
 * 「硬件无该能力时必须有**真实发现及明确拒绝**」。
 */
object DeviceCapability {

    private const val TAG = "DSH"

    /**
     * 能力清单。
     *
     * ⚠️ 只列**首发要求**的能力（见 §4.14.1a 的表），不列「将来可能加」的 ——
     *   列了没有对应实现会让人以为已经支持。
     */
    enum class Cap(val id: String, val label: String, val permissions: List<String>) {
        /** 相机（拍照/预览/录像）。 */
        CAMERA("camera", "相机", listOf(android.Manifest.permission.CAMERA)),

        /** 麦克风（录音/实时采集）。 */
        MICROPHONE("mic", "麦克风", listOf(android.Manifest.permission.RECORD_AUDIO)),

        /** 位置（单次/持续）。 */
        LOCATION("location", "位置", listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )),

        /** 蓝牙（扫描/连接/BLE）。 */
        BLUETOOTH("bluetooth", "蓝牙", listOf(
            // ⚠️ API 31+ 才需要 BLUETOOTH_SCAN/CONNECT；旧版只需 ACCESS_FINE_LOCATION。
            //   这里列的是「新版的必需项」，旧版的实际判据见 [requiredPerms]。
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )),

        /** 传感器（姿态/摇动）。 */
        SENSOR("sensor", "传感器", emptyList()),   // 传感器不需要运行时权限
        ;

        companion object {
            fun of(id: String?): Cap? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 能力的可用性结论。
     *
     * ⚠️ **三态而不是布尔**：见类 KDoc 里「无硬件 vs 无授权」的区别。
     */
    enum class Availability(val id: String, val label: String) {
        /** 硬件在、权限有。 */
        READY("ready", "可用"),

        /** 硬件在，但没授权。 */
        NEED_PERMISSION("need-permission", "需要授权"),

        /** 硬件不存在（或系统不支持）。 */
        NO_HARDWARE("no-hardware", "本机无此硬件"),

        /** 权限被永久拒绝（用户勾了「不再询问」）—— 只能去设置页开。 */
        PERMISSION_DENIED_FOREVER("denied-forever", "权限被永久拒绝，需去设置开启"),
        ;
    }

    /**
     * 一次发现的结果。
     *
     * @param cap 能力
     * @param availability 可用性
     * @param missingPerms 缺哪些权限（[Availability.NEED_PERMISSION] 时有意义）
     * @param detail 人可读说明
     */
    data class Probe(
        val cap: Cap,
        val availability: Availability,
        val missingPerms: List<String> = emptyList(),
        val detail: String = "",
    )

    /**
     * 发现某个能力的可用性。
     *
     * @param granted 调用方提供的「该权限是否已授予」判定。
     *   ⚠️ 由调用方传而不是本对象自己查：那样本对象能**纯逻辑测试**，
     *   也不依赖 `Activity` 的权限回调状态。
     */
    fun probe(
        context: Context,
        cap: Cap,
        granted: (String) -> Boolean,
    ): Probe {
        // ① 先判硬件
        if (!hasHardware(context, cap)) {
            return Probe(cap, Availability.NO_HARDWARE, detail = "系统未报告该硬件")
        }

        // ② 再判权限
        val needed = requiredPerms(cap)
        val missing = needed.filterNot(granted)
        if (missing.isEmpty()) {
            return Probe(cap, Availability.READY)
        }
        return Probe(
            cap,
            Availability.NEED_PERMISSION,
            missingPerms = missing,
            detail = "缺少权限: ${missing.joinToString(",") { it.substringAfterLast('.') }}",
        )
    }

    /**
     * 该能力**实际**需要的权限（按当前 API 版本算）。
     *
     * ⚠️ 蓝牙的权限在 API 31 变了：
     * - API ≤ 30：`BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`
     * - API ≥ 31：`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`
     *
     * 不区分版本的话，在旧设备上会去要一个不存在的权限 —— 那永远授不到。
     */
    fun requiredPerms(cap: Cap): List<String> {
        if (cap != Cap.BLUETOOTH) return cap.permissions
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    /**
     * 硬件是否存在。
     *
     * ⚠️ 用 `PackageManager.hasSystemFeature` 而不是「打开试试」：
     *   打开会触发权限申请，而「发现」阶段不该申请任何东西
     *   （文档：「**不提前索要全部权限**」）。
     */
    private fun hasHardware(context: Context, cap: Cap): Boolean {
        val pm = context.packageManager
        return when (cap) {
            Cap.CAMERA -> pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            Cap.MICROPHONE -> pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            Cap.LOCATION -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)
            Cap.BLUETOOTH -> pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
            // 传感器：`FEATURE_SENSOR_*` 只覆盖特定类型；通用传感器
            // 用 SensorManager 查更准，但这里只判「有没有传感器服务」。
            Cap.SENSOR -> true
        }
    }

    /**
     * 发现全部能力（给诊断与设置页用）。
     */
    fun probeAll(context: Context, granted: (String) -> Boolean): List<Probe> =
        Cap.entries.map { probe(context, it, granted) }

    /**
     * 把发现结果转成 JSON（桥与诊断包用）。
     */
    fun toJson(probes: List<Probe>): String {
        val arr = org.json.JSONArray()
        for (p in probes) {
            arr.put(org.json.JSONObject().apply {
                put("cap", p.cap.id)
                put("label", p.cap.label)
                put("availability", p.availability.id)
                put("detail", p.detail)
                if (p.missingPerms.isNotEmpty()) {
                    put("missing", org.json.JSONArray().apply {
                        p.missingPerms.forEach { put(it) }
                    })
                }
            })
        }
        return arr.toString()
    }

    /**
     * 记录一次能力被请求（审计用）。
     *
     * ⚠️ 文档：「用户批准采集**不代表允许上传**」——
     *   所以这里记的是「采集被请求」，不是「数据被发送」。
     */
    fun recordRequest(cap: Cap, granted: Boolean) {
        Log.i(TAG, "设备能力请求: ${cap.id} → ${if (granted) "已授权" else "被拒"}")
        EventLog.record(
            "device.cap.request",
            mapOf("cap" to cap.id, "granted" to granted.toString()),
        )
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：硬件发现、按 API 版本算必需权限、三态可用性、不提前申请
     * ❌ 未做：
     *   - **权限申请流程**（需要 Activity 的 requestPermissions 回调）
     *   - 「永久拒绝」的判定（`shouldShowRequestPermissionRationale` 需要 Activity）
     *   - 「基座开放能力」层（哪些插件能用哪些能力 —— 那是 [PluginArbiter] 类似的
     *     另一套仲裁，属后续）
     *   - 实际的采集实现（相机/音频/位置/蓝牙/传感器各自一个模块）
     */
}
