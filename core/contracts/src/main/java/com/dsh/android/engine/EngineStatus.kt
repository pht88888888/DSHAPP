package com.dsh.android.engine

import org.json.JSONObject
import com.dsh.android.log.DshLog as Log

/**
 * 插件从页面里读出来的一份状态快照。
 *
 * 字段由 `mobile-ui-plugin/dsh-android-mobile/lib/client.js` 的 `mpCollectStatus()`
 * 产生，两边必须一起改。
 */
data class EngineStatus(
    /** AI 是否正在跑（页面主按钮是「停止生成」）。 */
    val running: Boolean,
    /** 当前活动标题，如「正在运行命令 · sleep 10」；空闲时是上一组的「执行了命令」。 */
    val text: String,
    /** 最后一条助手消息（运行中含正在流式的思考内容）。 */
    val body: String,
    /** 会话标题，用作通知副标题。 */
    val title: String,
    /**
     * 页面实际背景色（`#RRGGBB`），用于对齐 WebView / 容器 / 窗口的底色。
     *
     * 只影响窗口底色，不影响通知内容 —— 所以 `updateStatus()` 比较时把它排除。
     */
    val bg: String,
    /**
     * 页面给的**粗粒度活动**（`data-process-activity` 属性），如 `thinking`
     * / `tool` / 空串。
     *
     * ⚠️ 这条通道原先一直是**通着的但没接**：`mpCollectStatus()` 一直在上报
     *   `activity`，而这里的 [parse] 把它丢掉了 —— 于是"页面已经知道 AI 在
     *   干什么"这一层信息到不了原生。桌面宠物按状态换动作就用它当**粗粒度**依据
     *   （细粒度那一路是 `session/event`，见 `dsh-android-pet` 插件）。
     *
     * ⚠️ 粒度就是粗的：页面每 1 秒才采一次（`mpScheduleStatus` 的节流），
     *   短于一秒的动作会整个漏掉。要精确就用插件那条通道。
     */
    val activity: String,
) {
    companion object {
        /**
         * 解析插件发来的 JSON。
         *
         * ⚠️ 任何字段缺失/类型不对都退化成空串而不是抛异常 —— 通知是锦上添花，
         * 不能因为页面改了个字段就把引擎进程搞崩。
         */
        fun parse(json: String): EngineStatus? = try {
            val o = JSONObject(json)
            EngineStatus(
                running = o.optBoolean("running", false),
                text = o.optString("text", ""),
                body = o.optString("body", ""),
                title = o.optString("title", ""),
                bg = o.optString("bg", ""),
                activity = o.optString("activity", ""),
            )
        } catch (t: Throwable) {
            Log.w(Log.TAG, "状态上报解析失败: ${t.message}")
            null
        }
    }
}
