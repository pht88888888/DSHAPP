package com.dsh.android.engine.constants

/** 启动和日志的固定策略。 */
const val READY_TIMEOUT_MS = 120_000L
const val MAX_LOG_LINES = 400

/** 引擎就绪时打印的带 token 回环地址。 */
val URL_PATTERN =
    Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+)""")

        /**
         * 引擎 Web GUI 的默认监听端口。
         *
         * 出处：`@deepseek-ai/dsh-web-app/cordis.patch.yml` 的
         * `port: !!js ctx.webStartup.port ?? 3080` —— App 不传 `--port` 时落到的就是它。
         *
         * 被占用时改传 `--port 0` 让内核挑一个空闲端口，见 [isLoopbackPortBusy]。
         */
const val DEFAULT_WEB_PORT = 3080

