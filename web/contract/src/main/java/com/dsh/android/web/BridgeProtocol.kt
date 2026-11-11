package com.dsh.android.web

import org.json.JSONObject

/** 宿主内部的非消费读取接口，不向页面暴露。 */
interface AnnotationSource {
    /** JSON 文本 {annotationId,png,text}；失败返回空串并保留待发送结果。 */
    fun peekAnnotation(): String
    /** 仅清除身份匹配的标注，防止迟到 ack 清掉新截图。 */
    fun acknowledgeAnnotation(annotationId: String)
}

/** 两个内核共享的新协议载荷。旧方法保持各自既有的同步/快照语义。 */
object BridgeProtocol {
    const val VERSION = 1
    const val RESULT_EVENT = "dsh:bridge-result"

    fun capabilities(annotationSupported: Boolean): String {
        val capabilities = JSONObject().put("protocol.capabilities", 1)
        if (annotationSupported) capabilities.put("annotation.request", 1)
        return JSONObject().put("protocolVersion", VERSION).put("capabilities", capabilities).toString()
    }

    fun response(requestId: String): JSONObject = JSONObject()
        .put("protocolVersion", VERSION).put("requestId", requestId).put("method", "aiAnnotateRequest")

    fun unsupported(requestId: String): String = failure(response(requestId), "unsupported", "Annotation source unavailable")

    fun failure(response: JSONObject, code: String, message: String): String = response
        .put("ok", false).put("result", JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()

    /** detail 使用 JSON 文本，避免 Gecko 隔离世界对象跨 compartment 的访问错误。 */
    fun resultScript(response: String): String =
        "window.dispatchEvent(new CustomEvent('${RESULT_EVENT}',{detail:${JSONObject.quote(response)}}));"
}
