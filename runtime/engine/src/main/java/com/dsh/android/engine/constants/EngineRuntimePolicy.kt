package com.dsh.android.engine.constants

/** 启动、故障定位和内存监控的固定策略。 */
const val READY_TIMEOUT_MS = 120_000L
const val MAX_LOG_LINES = 400

        /**
         * 堆水位监控的检查间隔。
         *
         * 取 3 分钟：泄漏速率实测约 130 MB/小时 ≈ 2.2 MB/分钟，3 分钟只涨
         * 约 6.5 MB —— 相对水位余量（见 [heapRestartRatio]）完全可以忽略，
         * 而检查一次只是读一个 `/proc/<pid>/status` 里的一行，代价可忽略。
         * 不要调得更勤：那对判据没有帮助，只是白唤醒。
         */
const val HEAP_WATCH_INTERVAL_MS = 3 * 60_000L

        /**
         * 触发主动重启的水位（占堆上限的比例）。
         *
         * 为什么是 0.85：
         * · 从判定到重启完成，引擎还在按 2.2 MB/分钟继续涨。`requestRestart`
         *   要走「停 → 起 → 等 ready」的完整状态机，真机上实测 ready 要
         *   10–29 秒，也就是约 0.5–1 MB，相对余量（15% of 3072 ≈ 460 MB）绰绰有余；
         * · 不能取 0.95 以上：V8 在逼近上限时 GC 会退化成近乎停顿（真机日志里
         *   `Mark-Compact 1903.46 / 0.00 ms`，单次 GC 近 2 秒），界面先卡死
         *   再重启，体验反而更差；
         * · 不要取 0.7 以下：那会让 3 GB 的机器每隔几小时就重启一次，太激进。
         */
val URL_PATTERN =
            Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+)""")

        /**
         * 一次试启动（[probeBoot]）的上限。
         *
         * 真机实测失败路径 17~22 秒就返回了，所以 60 秒足够。
         * ⚠️ **刻意比 [READY_TIMEOUT_MS]（120 秒）短**：二分法要跑很多轮，
         *   每轮都按 120 秒算会让总时长失控（上限是 3 分钟，见 §4.3）。
         */
const val PROBE_TIMEOUT_MS = 60_000L

        /**
         * L1 自动隔离的**总时长上限**（方案 §4.3）。
         *
         * 3 分钟的依据：单次失败实测 17~22 秒，基线 1 次 + 二分 ⌈log2(40)⌉ = 6 轮
         * + 收尾 1 次 ≈ 8 次 × 20 秒 ≈ 2.7 分钟。取 3 分钟刚好够走完，
         * 又不会让用户对着"正在启动…"等太久。
         *
         * ⚠️ 超预算必须**优雅放弃**并保留已确认的结论，绝不能无限试下去 ——
         *   那会把「App 打不开」变成「App 一直显示正在启动」，比原来更糟。
         */
const val ISOLATION_BUDGET_MS = 3 * 60 * 1000L

        /**
         * 启动前体检（[preflight]）的超时。
         *
         * 真机实测正常态耗时 0.7~0.8 秒；给到 30 秒是为了容忍慢卡（体检只是
         * 保险，超时即跳过、不影响启动，所以宁可给宽一点）。
         */
const val PREFLIGHT_TIMEOUT_MS = 30_000L

        /**
         * 自动进安全模式所需的**连续真实启动失败**次数（方案 §4.5 的 P6）。
         *
         * 取 3 的依据：1~2 次失败可能是偶发（系统内存紧张导致引擎被 OOM、
         * 端口被上一次残留进程占着），那时推开用户的插件配置是**过度反应** ——
         * 用户会莫名其妙丢掉所有插件。而 3 次连续失败（每次都是完整的启动流程、
         * 都走到「引擎没打印 URL」）已经排除了偶发。
         *
         * ⚠️ 这个数**只是三个条件之一**，见 [shouldAutoEnterSafeMode]：
         *   单独满足它不会触发，还必须 `tier == RESCUE`（= L1/L2 都试过）。
         */
const val AUTO_SAFE_MODE_MIN_FAILURES = 3

        /**
         * `requiredStartupEntryIds` 的副本 —— 与 `dsh-app-boot/lib/index.js` 里的
         * 那个 Set **同源**，少一个引擎就起不来（`auditStartupEntries` 会抛
         * `StartupError` 并以 exit 1 结束）。
         *
         * ⚠️ 这几个 id 不是「重要插件」的意思，而是「缺了整个树就废了」：
         * 真机实测删掉 `dsh-web-app` 的 `cordis.patch.yml` 后，`webserver` /
         * `modules` / `connection` 三个条目全部消失，引擎 boot 时挂死（45 秒超时）。
         * 引擎升级若增删了这个列表，本副本要跟着改 —— 漏改只会让体检**少报**，
         * 不会误伤（体检判据是「缺了才隔离」）。
         */
val REQUIRED_ENTRY_IDS = listOf(
            "agent-loop",
            "tool-executor-native",
            "tool-executor-ptc",
            "webserver",
            "web-runtime",
            "modules",
            "connection",
        )

        /**
         * 从引擎报错行里抠出被指认的文件绝对路径。
         *
         * 覆盖三种官方措辞（真机原文）：
         * - `failed to parse overlay <path>:`
         * - `failed to read overlay <path>:`
         * - `failed to read profile manifest <path>:`
         *
         * 路径一定在 `/` 开头（本 App 的 `$DSH_HOME` 与 `engine/` 都是绝对路径），
         * 所以用 `(/\S+)` 而不是更宽松的匹配 —— 报错行后面还跟着 YAML 片段。
         */
val BLAMED_PATH =
            Regex("""failed to (?:parse|read) (?:overlay|profile manifest) (/\S+?):""")

        /**
         * 引擎 Web GUI 的默认监听端口。
         *
         * 出处：`@deepseek-ai/dsh-web-app/cordis.patch.yml` 的
         * `port: !!js ctx.webStartup.port ?? 3080` —— App 不传 `--port` 时落到的就是它。
         *
         * 被占用时改传 `--port 0` 让内核挑一个空闲端口，见 [isLoopbackPortBusy]。
         */
const val DEFAULT_WEB_PORT = 3080

