package com.dsh.android.engine.constants

/** 模型消息映射补丁的原文字面量。 */
        /**
         * `dsh-llm-deepseek/lib/index.js` 里 `assistant()` 映射的**首尾两行原文**，
         * 以及把 `tool_use` 块收敛到末尾之后的文本。见 [patchLlmDeepseekToolOrder]。
         *
         * 分首尾两处替换（而不是整段替换）：中间是一个完整的 `switch`，整段照抄
         * 容易在引擎升级时因为无关的空白/注释变化而失配，只锚定首尾两行更稳。
         *
         * ⚠️ **重排方向是「置尾」而不是「置首」**：assistant 的 content 里可能有
         * 带签名的 `thinking` 块，而 Messages API 要求 thinking 排在 assistant
         * content 的**最前**。把 `tool_use` 提到最前会让 thinking 落到它后面，
         * 可能触发签名校验失败。收敛到末尾得到的 `[thinking, text, tool_use…]`
         * 才是规范形状。
         *
         * ⚠️ 缩进是**制表符**（首行 1 个、尾行 3/2/1/0 个），与官方发布件逐字一致；
         * 引擎升级后若不再匹配，补丁会跳过并在日志里提示，不会让启动失败。
         *
         * ⚠️ **0.2.1-rc.2 起首行从 `map` 变成了 `flatMap`**（空 text 块返回 `[]`
         *   靠 flatMap 展开）。锚点只认新文本 —— 引擎已固定为 0.2.1，老文本不再需要，
         *   漏改的后果是补丁静默跳过、assistant 的 tool_use 顺序又变回交错，
         *   ⑨.5 那个「会话永久发不出去」的故障会复发。
         */
const val TOOL_ORDER_GUARD_HEAD =
            "\treturn message.content.flatMap((block, index) => {"
const val TOOL_ORDER_ANCHOR_HEAD =
            "\tconst mapped = message.content.flatMap((block, index) => {"

const val TOOL_ORDER_GUARD_TAIL =
            "\t\t\tdefault: return unsupported(`assistant content \${block.type}`);\n" +
                "\t\t}\n" +
                "\t});\n" +
                "}"
const val TOOL_ORDER_ANCHOR_TAIL =
            "\t\t\tdefault: return unsupported(`assistant content \${block.type}`);\n" +
                "\t\t}\n" +
                "\t});\n" +
                "\t/* Android: DeepSeek Messages 要求 assistant 的 tool_use 块连续，且紧后\n" +
                "\t   必须携带全部 tool_result。持久化历史里 assistant 的块是交错的\n" +
                "\t   （text 夹在多个 tool_use 之间），原样发出会被 API 以\n" +
                "\t   \"tool_use ids were found without tool_result blocks\" 拒绝，\n" +
                "\t   且该历史从此永久发不出去。这里把 tool_use 收敛到末尾，\n" +
                "\t   其余块保持原有相对顺序（thinking 必须留在最前）。\n" +
                "\t   只改本次请求体，不改持久化历史，replay 签名已在映射时贴好。 */\n" +
                "\tconst calls = mapped.filter((block) => block.type === \"tool_use\");\n" +
                "\tif (calls.length === 0) return mapped;\n" +
                "\treturn [...mapped.filter((block) => block.type !== \"tool_use\"), ...calls];\n" +
                "}"

        /**
         * `dsh-llm-pi-ai/lib/index.js` 里 tool-call 事件的**首尾两处原文**，
         * 以及给空 id 兜底之后的文本。见 [patchLlmPiAiToolCallId]。
         *
         * ⚠️ 两处都要改，缺一不可：
         * - `toolcall_delta` 显式造空串（`known?.id ?? ""`），它经 `partial.toolCallId`
         *   进入组装器；
         * - `toolcall_end` 直接造整个 block（`id: brandString(event.toolCall.id)`），
         *   经 `partial.block` 短路返回，**绕过组装器的 `??` 兜底**。
         *
         * 兜底值用 `call-<index>` 形状（与 `dsh-llm` 组装器原生的兜底命名一致），
         * 保证 provider 不给 id 时仍有稳定的、非空的调用标识。
         *
         * ⚠️ 缩进是**制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
const val PI_AI_TOOLID_GUARD_START =
            "\t\t\t\tid: brandString(known?.id ?? \"\"),\n"
const val PI_AI_TOOLID_ANCHOR_START =
            "\t\t\t\t/* Android: 第三方路由（实测 agnes/q）在流式响应里可能不给 tool_call id，\n" +
                "\t\t\t\t   原样透出会让 tool-call 块拿到空 id，落盘后该会话永久不可读。\n" +
                "\t\t\t\t   这里补一个稳定的非空兜底。 */\n" +
                "\t\t\t\tid: brandString(known?.id || `call-\${event.contentIndex}`),\n"

const val PI_AI_TOOLID_GUARD_END =
            "\t\t\t\t\tid: brandString(event.toolCall.id),\n"
const val PI_AI_TOOLID_ANCHOR_END =
            "\t\t\t\t\t/* Android: 同 toolcall_delta —— provider 不给 id 时兜底，\n" +
                "\t\t\t\t\t   否则这个 block 会短路返回、绕过组装器的 ?? 兜底。 */\n" +
                "\t\t\t\t\tid: brandString(event.toolCall.id || `call-\${event.contentIndex}`),\n"

        /**
         * `dsh-llm/lib/index.js` 里 `assemble()` 组装 tool-call 块的那一行原文，
         * 以及空串也能兜住的文本。见 [patchLlmToolCallIdFallback]。
         *
         * ⚠️ 原文用 `??`，**只兜 `null`/`undefined`**；而 pi-ai 的 delta 路径
         * 显式产生空串 `""`，`??` 放行 → 空 id 落盘 → 会话永久不可读。
         * 改成 `||` 让空串也走兜底。
         *
         * ⚠️ 缩进是**四个制表符**，与官方发布件逐字一致。
         */
const val LLM_TOOLID_GUARD =
            "\t\t\t\tid: partial.toolCallId ?? brandString(`call-\${index}`),\n"
const val LLM_TOOLID_ANCHOR =
            "\t\t\t\t/* Android: `??` 兜不住空串，而 pi-ai 的 delta 路径会产生 \"\"，\n" +
                "\t\t\t\t   空 id 落盘后整个会话都读不出来。改用 `||` 连空串一起兜。 */\n" +
                "\t\t\t\tid: partial.toolCallId || brandString(`call-\${index}`),\n"

