package com.dsh.android.device

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * **能力请求管理** —— 去重、取消、配额。
 *
 * ## 设计说明验收矩阵第 19 条
 *
 * > 基座能力发现、未知/未实现能力、权限拒绝、**超时后查询**、
 * > **请求去重**、**事件取消**与**资源配额**有可验证行为
 *
 * ## ⚠️ 为什么需要「请求管理」而不是各能力自己管
 *
 * 三种问题在「各管各的」时会重复出现：
 *
 * | 问题 | 没有统一管理时 |
 * |---|---|
 * | **重复请求** | 用户连点两次「拍照」→ 打开两次相机 → 第二个必然失败（镜头被占） |
 * | **无法取消** | 用户点了「取消」但请求还在跑 → 相机一直开着 |
 * | **配额失控** | 十个插件同时要位置 → 系统限流 → 全部失败，但没人知道为什么 |
 *
 * 统一管理后：相同请求合并、取消能真停、超配额明确拒绝。
 *
 * ## ⚠️ 去重的判据：**相同能力 + 相同参数**
 *
 * ```
 * 请求 A: CAMERA + {facing: back}     ← 发起
 * 请求 B: CAMERA + {facing: back}     ← 合并到 A（不新开）
 * 请求 C: CAMERA + {facing: front}    ← 不同参数，新开
 * ```
 *
 * ⚠️ **参数必须参与判据**：只按「能力」去重会让「拍后置」和「拍前置」
 * 被当成同一个请求 —— 用户会拿到错的那张照片。
 */
object CapabilityRequests {

    private const val TAG = "DSH"

    /**
     * 每类能力的并发上限（文档：「**资源配额**」）。
     *
     * ⚠️ 数值不是随便定的：
     * - 相机：系统通常只允许 1 个（`ERROR_MAX_CAMERAS_IN_USE`）
     * - 麦克风：1 个（第二个会拿到静音数据）
     * - 位置：可以多个订阅者共享同一个定位源
     *
     * ⚠️ **相机的实际生效配额是 `limit + 1`**（见 [request] 里的说明）：
     *   允许「换镜头」这种合理场景超出 1 个，由底层报
     *   `ERROR_CAMERA_IN_USE` 而不是我们这层假装知道。
     */
    private fun quotaOf(cap: DeviceCapability.Cap): Int = when (cap) {
        DeviceCapability.Cap.CAMERA -> 1
        DeviceCapability.Cap.MICROPHONE -> 1
        DeviceCapability.Cap.LOCATION -> 4
        DeviceCapability.Cap.BLUETOOTH -> 2
        DeviceCapability.Cap.SENSOR -> 8
    }

    /**
     * 一个进行中的请求。
     */
    class Request internal constructor(
        val id: String,
        val cap: DeviceCapability.Cap,
        /** 参数指纹（参与去重）。 */
        val fingerprint: String,
        /** 创建时刻。 */
        val createdAt: Long,
    ) {
        /** 是否已被取消。 */
        @Volatile var cancelled = false
            private set

        /** 取消回调（由发起方注册，用于真正停掉底层操作）。 */
        @Volatile private var onCancel: (() -> Unit)? = null

        /** 注册取消回调。 */
        fun setCancelHandler(handler: () -> Unit) {
            onCancel = handler
            // ⚠️ 注册前已被取消 ⇒ 立即执行 ——
            //   否则「先取消后注册」会让底层操作永远不停。
            if (cancelled) {
                runCatching { handler() }
                onCancel = null
            }
        }

        internal fun markCancelled() {
            if (cancelled) return
            cancelled = true
            runCatching { onCancel?.invoke() }
            onCancel = null
        }
    }

    /**
     * 发起结果。
     */
    sealed interface Outcome {
        /** 新请求已发起。 */
        data class Started(val request: Request) : Outcome

        /**
         * 合并到已有请求（**去重**）。
         *
         * @param existing 已存在的那个请求
         */
        data class Merged(val existing: Request) : Outcome

        /** 超过配额。 */
        data class QuotaExceeded(val cap: DeviceCapability.Cap, val limit: Int) : Outcome
    }

    /** 进行中的请求（id → 请求）。 */
    private val active = ConcurrentHashMap<String, Request>()

    /** 每类能力的当前计数。 */
    private val counts = ConcurrentHashMap<DeviceCapability.Cap, AtomicInteger>()

    /**
     * 发起一个能力请求。
     *
     * @param params 参数（**参与去重判据**）
     */
    fun request(
        context: Context,
        cap: DeviceCapability.Cap,
        params: Map<String, String> = emptyMap(),
    ): Outcome {
        val fp = fingerprintOf(cap, params)

        // ① 去重：相同能力 + 相同参数 且 未取消 ⇒ 合并
        active.values.firstOrNull {
            it.cap == cap && it.fingerprint == fp && !it.cancelled
        }?.let { existing ->
            Log.i(TAG, "请求合并：${cap.id}（已有 ${existing.id.take(8)}）")
            EventLog.record(
                "device.req.merged",
                mapOf("cap" to cap.id, "existing" to existing.id.take(8)),
            )
            return Outcome.Merged(existing)
        }

        // ② 配额
        //
        // ⚠️ **相机是特例：允许一个「换镜头」的请求**。
        //   实测推演暴露过这个问题：用户先拍后置（占满配额=1），
        //   再点「拍前置」→ 被配额拒绝 → 但用户的意图完全合理。
        //
        //   正确行为是：**让新请求排队等旧的释放**，而不是直接拒绝。
        //   但那会让调用方等待，而等待的语义需要 UI 配合。
        //
        //   折中：相机允许「不同参数」的请求超出配额 1 个 ——
        //   由底层 `openCamera` 自己报 `ERROR_CAMERA_IN_USE`（它会明确告知），
        //   而不是我们在这一层假装知道「现在不能换镜头」。
        val counter = counts.getOrPut(cap) { AtomicInteger(0) }
        val limit = quotaOf(cap)
        val effectiveLimit = if (cap == DeviceCapability.Cap.CAMERA) limit + 1 else limit
        if (counter.get() >= effectiveLimit) {
            Log.w(TAG, "配额已满：${cap.id}（${counter.get()}/$effectiveLimit）")
            EventLog.record(
                "device.req.quota",
                mapOf(
                    "cap" to cap.id,
                    "current" to counter.get().toString(),
                    "limit" to effectiveLimit.toString(),
                ),
            )
            return Outcome.QuotaExceeded(cap, effectiveLimit)
        }

        // ③ 新建
        val req = Request(
            id = UUID.randomUUID().toString(),
            cap = cap,
            fingerprint = fp,
            createdAt = System.currentTimeMillis(),
        )
        active[req.id] = req
        counter.incrementAndGet()

        EventLog.record(
            "device.req.start",
            mapOf("cap" to cap.id, "id" to req.id.take(8), "active" to counter.get().toString()),
        )
        return Outcome.Started(req)
    }

    /**
     * 结束一个请求（**必须调**，否则配额永远被占着）。
     */
    fun finish(context: Context, request: Request) {
        if (active.remove(request.id) == null) return
        counts[request.cap]?.decrementAndGet()
        EventLog.record(
            "device.req.finish",
            mapOf("cap" to request.cap.id, "cancelled" to request.cancelled.toString()),
        )
    }

    /**
     * 取消一个请求。
     *
     * ⚠️ **不只是标记**：会调发起方注册的取消回调（真正停掉底层操作）。
     *   只标记的话「用户点了取消但相机还开着」。
     */
    fun cancel(context: Context, request: Request) {
        request.markCancelled()
        finish(context, request)
        EventLog.record("device.req.cancel", mapOf("cap" to request.cap.id))
    }

    /**
     * 取消某一类能力的全部请求（**撤权时必须调**）。
     *
     * ⚠️ 文档要求「**撤权立即停止**」—— 权限被撤销时，
     *   所有用到该权限的请求都必须立刻停，而不是等它们自己超时。
     */
    fun cancelAll(context: Context, cap: DeviceCapability.Cap): Int {
        val targets = active.values.filter { it.cap == cap }
        targets.forEach { cancel(context, it) }
        if (targets.isNotEmpty()) {
            Log.i(TAG, "撤权清理：取消了 ${targets.size} 个 ${cap.id} 请求")
            EventLog.record(
                "device.req.cancelAll",
                mapOf("cap" to cap.id, "count" to targets.size.toString()),
            )
        }
        return targets.size
    }

    /**
     * 清理所有已取消/超时的请求（定期调，防止配额泄漏）。
     *
     * @param maxAgeMs 超过这个时长的请求视为泄漏
     */
    fun sweepStale(context: Context, maxAgeMs: Long = 5 * 60 * 1000L): Int {
        val now = System.currentTimeMillis()
        val stale = active.values.filter { now - it.createdAt > maxAgeMs }
        stale.forEach {
            Log.w(TAG, "清理泄漏的请求：${it.cap.id}（存活 ${now - it.createdAt}ms）")
            finish(context, it)
        }
        return stale.size
    }

    /** 当前进行中的请求数（诊断用）。 */
    fun activeCount(cap: DeviceCapability.Cap? = null): Int =
        if (cap == null) active.size else active.values.count { it.cap == cap }

    /** 某类能力是否还有配额。 */
    fun hasQuota(cap: DeviceCapability.Cap): Boolean =
        (counts[cap]?.get() ?: 0) < quotaOf(cap)

    /** 全部重置（测试/异常兜底）。 */
    fun reset() {
        active.clear()
        counts.clear()
    }

    /**
     * 参数指纹。
     *
     * ⚠️ 用**排序后的键值对**：`{a:1,b:2}` 与 `{b:2,a:1}` 必须得到同一个指纹，
     *   否则「参数相同但顺序不同」会被当成两个请求，去重失效。
     */
    private fun fingerprintOf(cap: DeviceCapability.Cap, params: Map<String, String>): String =
        cap.id + "|" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：相同能力+参数的请求合并、按能力的并发配额、
     *          取消（含真停回调）、撤权时批量取消、泄漏清理
     * ❌ 未做：
     *   - **接入各能力模块**（`CameraCapability` / `AudioCapture` /
     *     `LocationCapability` 等仍直接发起，未走本对象 —— 那是接线工作）
     *   - 「超时后查询」（文档提到 —— 需要请求有状态查询接口）
     *   - 「事件取消」（事件与请求是两回事：请求是「我要用相机」，
     *     事件是「相机拍好了」—— 事件取消需要另一个机制）
     *   - 跨进程配额（当前只在本进程内）
     */
}
