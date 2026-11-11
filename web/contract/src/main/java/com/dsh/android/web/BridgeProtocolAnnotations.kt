package com.dsh.android.web

import org.json.JSONObject

/** 每个 WebHost 独占；同 requestId 重读返回同结果，只有 ack 才消费原生标注。 */
class BridgeProtocolAnnotations(private val source: AnnotationSource?) {
    private data class Pending(val annotationId: String, val response: String)
    private val pending = mutableMapOf<String, Pending>()

    fun capabilities(): String = BridgeProtocol.capabilities(source != null)

    @Synchronized
    fun request(requestId: String): String {
        val response = BridgeProtocol.response(requestId)
        if (requestId.isBlank() || requestId.length > 128) {
            return BridgeProtocol.failure(response, "invalid_request", "Invalid requestId")
        }
        pending[requestId]?.let { return it.response }
        val reader = source ?: return BridgeProtocol.unsupported(requestId)
        // 不逐出尚未确认的结果，防止上层丢响应后无法用同一个 requestId 重试。
        if (pending.size >= 8) return BridgeProtocol.failure(response, "busy", "Too many pending annotation requests")
        return try {
            val raw = reader.peekAnnotation()
            if (raw.isEmpty()) return BridgeProtocol.failure(response, "unavailable", "No readable annotation available")
            val payload = JSONObject(raw)
            val annotationId = payload.getString("annotationId")
            require(annotationId.isNotEmpty() && payload.getString("png").isNotEmpty()) { "Invalid annotation payload" }
            payload.getString("text")
            // annotationId 只用于内部身份校验，不扩大页面载荷。
            payload.remove("annotationId")
            val result = response.put("ok", true).put("result", payload).put("error", JSONObject.NULL).toString()
            pending[requestId] = Pending(annotationId, result)
            result
        } catch (e: Exception) {
            BridgeProtocol.failure(response, "read_failed", e.message ?: "Annotation read failed")
        }
    }

    @Synchronized
    fun acknowledge(requestId: String) {
        val result = pending[requestId] ?: return
        source?.acknowledgeAnnotation(result.annotationId)
        // 多个 requestId 可以读取同一标注；任一确认后释放该标注的全部缓存。
        pending.entries.removeAll { it.value.annotationId == result.annotationId }
    }

    @Synchronized
    fun clear() { pending.clear() }
}
