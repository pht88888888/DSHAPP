package com.dsh.android.engine.constants

/** 旧内置供应商配置的迁移识别值与兼容默认值。 */
        /**
         * 内置号池落在哪一行：**必须复用引擎自己的 `llm-pi-ai` entry**，
         * 不能另开一行同名插件。
         *
         * ⚠️ 为什么不能另开一行（踩过）：`dsh-llm-pi-ai` 的 `apply()` 会**无条件**
         * 把 pi-ai 内置目录里的 41 个 provider 全部 `registerConfigurableProviders()`。
         * 挂第二个实例时，第二批声明撞上第一批已经占用的路由，
         * `commit()` 抛 `DUPLICATE_DIRECTORY`（`configurable provider "anthropic"
         * is already declared`）⇒ 整个目录注册失败，模型列表空掉。
         *
         * ⚠️ 也不能用 `--patch` 去改这一行：overlay 在最顶层，会把用户在 GUI 里
         * 新建的 provider 整块盖掉（就是本次要修的 bug）。
         *
         * 所以唯一正确的落点是**同一个 entry、profile 的用户配置层** —— 那正是
         * GUI 自己写入的那份文件，我们只在缺 `agnes:` 时补一笔，绝不覆盖用户内容。
         */
const val KEY_POOL_NS = "llm-pi-ai"

        /**
         * 可以安全摘掉的「行内空值」写法 —— 摘掉它们**不丢任何数据**。
         *
         * ⚠️ 用途是修 [`mergeKeyPoolProvider`] 里的重复键崩溃：引擎把 schema 默认值
         * 序列化进文件时，`providers: z.dict(profile).default({})` 会写成
         * `providers: {}`，而 `{}` 是流式空映射，在它下面缩进写 `agnes:` 是非法
         * YAML，必须先还原成裸 `providers:`。
         *
         * ⚠️ **只认这四种「空」**。非空的 `providers: {deepseek: {...}}` 绝不能摘 ——
         * 那是用户的 provider 配置，摘掉就是数据丢失，见 [openKeyBlock] 的注释。
         *
         * `null` / `~` 是 YAML 的显式空值，语义上等于「没有内容」，摘下同样安全。
         */
val EMPTY_INLINE_VALUES = setOf("{}", "[]", "null", "~")

        /**
         * 网关令牌（替代旧的 TLS 层注入占位符）。
         *
         * ## 为什么从「占位符 + LD_PRELOAD 注入」改成「网关令牌」
         *
         * 旧方案把 132 把真密钥打进 APK，靠 `libkeyinject.so` 在 `SSL_write` 里
         * 把占位符换成真钥匙。虽然做了混淆，但**密钥终究落在设备上** —— 一旦
         * 泄漏就是 132 把一起丢，且换钥匙必须重新发版。
         *
         * 现在改为服务端中转：App 只持有这一个网关令牌，请求发到
         * `agnes.jiulingssh.xyz`，由服务器按严格轮询挑选真密钥并注入。
         * 好处：
         *   - 真密钥永不落设备（`strings` / 环境变量 / 抓包都拿不到）
         *   - 增删密钥只需改服务器上的 `agnes-keys.properties`，**不用发版**
         *   - 429/5xx 由网关自动换号重试，客户端无感
         *
         * ⚠️ 这个令牌**不是秘密**（它随 APK 分发，粉丝能读到），所以网关侧必须
         * 自己做限流与额度控制。令牌泄漏的后果是「额度被白嫖」，而不是「密钥泄漏」。
         *
         * ⚠️ 与旧 `KEY_PLACEHOLDER` 的兼容：老安装的 `ag-multimodal.json` 里可能
         * 还留着占位符行，[seedAgnesAccounts] 认它并覆盖成新令牌。
         */
const val KEY_PLACEHOLDER = "sk-agnes-54a388522b26770c2b45aab2f5a22c0700b6be4c0f325a96"

        /**
         * **旧版**的 TLS 注入占位符，仅用于**识别并覆盖**老安装残留的配置行。
         *
         * 升级到网关方案前，`ag-multimodal.json` 与 provider 配置里写的是这个
         * `sk-ANON…` 串。现在它已经没有意义（没有注入库会去替换它），但它会
         * 留在用户设备上。`seedAgnesAccounts()` 认它并把整行替换成新令牌，
         * 避免出现「用户有一条永远 401 的旧内置号」。
         *
         * ⚠️ **不要删除这个常量**，也不要把它当成有效配置使用。
         */
const val LEGACY_KEY_PLACEHOLDER = "sk-ANON00000000000000000000000000000000000000000000"

        /**
         * **旧版**直连上游的 baseURL，仅用于**识别并迁移**老安装的 `agnes:` 配置块。
         *
         * 号池改为服务端网关后，`baseURL` 必须是 [KEY_BASE_URL]。但
         * [mergeKeyPoolProvider] 的语义是「已有 agnes: 就一个字节都不动」，
         * 老安装升级后仍指向这两个域名 ⇒ 带着网关令牌直连上游 ⇒ 上游 401 ⇒
         * 界面显示「API 密钥无效」。
         *
         * [mergeKeyPoolProvider] 命中这里列出的值时改写成网关地址；
         * **其它任何值都不碰**（用户自填的第三方地址绝不可覆盖）。
         *
         * ⚠️ 不要把它们当成有效配置使用。
         */
val LEGACY_KEY_BASE_URLS = setOf(
            "https://api.agnes-ai.cn/v1",
            "https://apihub.agnes-ai.cn/v1",
            "https://api.agnes-ai.cn",
            "https://apihub.agnes-ai.cn",
        )

        /**
         * 内置号池的 provider 路由名。
         *
         * **只有一个**：两个站点由 `keyinject.c` 在 TLS 层按钥匙所属站点改写
         * Host 头来区分（combo），所以引擎侧看到的就是一个普通供应商，用户
         * 在主界面选模型时也只看到一个「Agnes」。
         *
         * 依据是三个实测事实：两个域名**同一个 IP**、共用**同一张**
         * `*.agnes-ai.cn` 通配证书、服务器按 **Host 头**路由（不是 SNI）。
         */
const val KEY_PROVIDER = "agnes"

/** 内置 Agnes 网关的域名标记：profile 里命中它就说明是**内置**供应商，清理时据此识别。 */
const val AGNES_GATEWAY_MARK = "agnes.jiulingssh.xyz"

        /** 供应商显示名 —— 主界面模型列表里分组用的就是它。 */
const val KEY_PROVIDER_NAME = "Agnes"

        /**
         * 网关地址（替代旧的直连国际站域名）。
         *
         * 旧方案 baseURL 指向 `apihub.agnes-ai.cn`，靠 `keyinject.c` 等长改写
         * Host 头在两个站点间路由 —— 那条链路已随注入层一起下线。
         *
         * 现在指向自建号池网关：两站钥匙的挑选与 Host 路由全部由**服务器**完成，
         * 客户端只看到一普通 OpenAI 兼容端点，不需要知道底下有几个站点、几把钥匙。
         *
         * ⚠️ 保留 `/v1` 后缀：引擎的 `openai-completions` 会拼 `/chat/completions`，
         * 与 OpenAI 官方 SDK 的习惯一致。
         */
const val KEY_BASE_URL = "https://agnes.jiulingssh.xyz/v1"

        /**
         * `apiKeyEnv` 引用的环境变量名。
         *
         * 名字里**刻意不带 KEY/SECRET/TOKEN**：`dsh-subprocess` 的
         * `scrubbedParentEnv()` 会剥掉所有匹配 `SENSITIVE_ENV_PATTERN`
         * （`/KEY|PASSWORD|SECRET|TOKEN/i`）的变量，带这些字样的名字会让子进程
         * 拿不到它。虽然这里装的是占位符、泄露也无所谓，但保持它能被子进程读到
         * 更省事（引擎若在子进程里做模型发现，缺了它会报 `MISSING_CREDENTIAL`）。
         */
const val KEY_ENV = "AGNES_CREDENTIAL"

        /**
         * 号池重试策略里声明的**可重试错误码**。
         *
         * ⚠️ `retryableCodes` 是**整体替换**默认值，不是追加 —— 引擎侧
         * `resolveRetryPolicy()` 写的是 `config.retryableCodes ?? [...DEFAULT_RETRYABLE_CODES]`，
         * 一旦我们提供了这个数组，默认那五个就**全都不再生效**。所以必须把它们
         * **原样再列一遍**（值与 `dsh-llm/lib/types/retry-policy.js` 的
         * `DEFAULT_RETRYABLE_CODES` 逐字一致），漏掉哪个就等于把那类错误的重试关掉。
         *
         * 五个默认值之外**多了一个 `AUTH`**。
         *
         * ⚠️ 这条的**原始动机已随网关改造消失**，但**仍然保留**，理由如下：
         *
         * 旧方案里号池是「随机抽钥匙」，抽到死钥匙就回 401/403，所以要靠引擎
         * 重发请求来换一把。现在网关自己会换号重试（`AGNES_MAX_ATTEMPTS`），
         * 客户端正常情况下**收不到** 401 了。
         *
         * 但保留它仍有两个实际好处：
         * 1. 网关令牌本身被吊销/更换时，客户端回 401 —— 重试虽无用，但能让
         *    错误在界面上表现为「重试若干次后失败」，而不是瞬间中断；
         * 2. 网关若因全池冷却返回 429（`RATE_LIMIT` 已在表里）或临时 5xx，
         *    重试是有意义的。
         *
         * 换言之：`AUTH` 现在大概率是空转，但删掉它不会带来收益，留着无害。
         */
val KEY_RETRYABLE_CODES = listOf(
            "EMPTY_RESPONSE",
            "RATE_LIMIT",
            "SERVER",
            "TIMEOUT",
            "TRANSPORT",
            "AUTH",
        )

        /**
         * 内置号池对外提供的模型清单。
         *
         * ⚠️ 必须显式列出：pi-ai 对手工声明的路由要求 `models` 非空，否则
         * 整个 profile 会被拒绝（"A route pi-ai does not ship needs `api`,
         * `baseURL`, and a non-empty `models` list"）。
         *
         * 只留 `agnes-3.0-flash`：号池是给粉丝免费体验用的，列一长串
         * （图像、视频、各种 pro 变体）只会让模型选择器变乱，而实际额度也不支持
         * 那些模型。要放开时在这里加一行即可。
         */
val KEY_MODELS = listOf("agnes-3.0-flash")

        /**
         * 内置号池模型对外声明的思考强度档位（`reasoningEfforts` 的键）。
         *
         * ## 为什么必须声明
         *
         * 不声明时 `dsh-llm-pi-ai` 的 `resolveModelReasoning()` 走
         * `base?.reasoning ?? false`，而 `agnes` 是自定义路由、pi-ai 内置目录里
         * 没有它，于是 `reasoning` 落到 **false**；`openai-completions` 的两个
         * 分支都要求 `model.reasoning`，**请求里根本不会带 `reasoning_effort`**。
         * Agnes 不传该参数就不返回 `reasoning_content` —— 界面上「思考过程」永远
         * 是空的。声明之后引擎才会带上它，思考内容才会流回来。
         *
         * ## 合法值来自实测，不是抄文档
         *
         * 给 `reasoning_effort: 'off'` 时 Agnes 直接 400，报错原文把合法集合列全了：
         *
         * ```
         * Input should be 'none', 'minimal', 'low', 'medium', 'high', 'xhigh' or 'max'
         * ```
         *
         * ## ⚠️⚠️ 为什么这里**刻意不声明 `off` 档**
         *
         * 这是踩过的坑，改回去会让「思考混进正文」重现，务必读完再动。
         *
         * 发送侧是**硬编码**的（`dsh-llm-pi-ai` 的 `streamSimple`）：
         *
         * ```js
         * const reasoningEffort = clampedReasoning === "off" ? undefined : clampedReasoning;
         * ```
         *
         * 也就是说**只要档位是 `off`，引擎就不发 `reasoning_effort`** —— 无论
         * overlay 里把 `off` 映射成什么（`null` 也一样）都改变不了这一点。
         * 而 Agnes 收不到「已关闭」这个信号时，会**把思考直接写进 `content`**
         * （实测 7 次里中 1 次，且内容是第一人称英文推理，例如
         * "The existing file is just a minimal placeholder…"），
         * 于是界面上思考内容就出现在正文里，而不是 thinking 块里。
         *
         * 更要命的是 `off` **一旦写进 `reasoningEfforts` 反而会变成可选档**：
         *
         * | overlay 里 | `thinkingLevelMap['off']` | `getSupportedThinkingLevels()` |
         * |---|---|---|
         * | 不写 `off` 键 | `null`（`wire === void 0` 分支） | `mapped === null` ⇒ **不可选** ✅ |
         * | 写 `'off': null` | `undefined`（`wire !== null` 跳过赋值） | 不为 null ⇒ **可选** ❌ |
         *
         * 所以唯一可靠的修法是**整档不声明**：off 从选择器里消失，
         * `clampThinkingLevel()` 把默认档落到 `minimal`，而 `minimal` 实测
         * **7/7 都走 `reasoning_content`**，思考正确进 thinking 块。
         *
         * ⚠️ 也别想用 `none` 顶替 `off`：`none` 同样不产生思考，但它会被真的发出去，
         * 与「不发字段」不是同一条路径，且实测出现过指令复述混进 `content` 的情况。
         *
         * ## ⚠️ 实测：这 6 档没有强度梯度
         *
         * 同一道推理题反复测，思考长度在 minimal=479 / low=1593 / medium=589 /
         * high=877 / xhigh=923 / max=1225 之间**随机乱跳**，不随档位递增。
         * 也就是说 Agnes 目前实际只区分「开 / 关」，档位名只是被接受而已。
         * 保留全部 6 档是为了不挡用户，而不是因为选了真会想得更多。
         */
val KEY_EFFORT_LEVELS = listOf("minimal", "low", "medium", "high", "xhigh", "max")

        /**
         * 内置号池模型对外声明的**输入模态**（`input` 字段）。
         *
         * ## 为什么必须声明
         *
         * `dsh-llm-pi-ai` 的 `DEFAULT_INPUT = ["text"]`，且解析顺序是
         *
         * ```js
         * input: declaredInput(entry.input) ?? base?.input ?? [...request.defaultInput]
         * ```
         *
         * `agnes` 是自定义路由、pi-ai 内置目录里没有它 ⇒ `base` 不存在 ⇒
         * 不声明就落到 `["text"]`。后果是**图片在附加前就被拒**：
         * composer 里选好图、点发送，引擎报「该模型不接受图片输入」，
         * 而 Agnes 本身是能识图的 —— 属于「能力被配置挡住」。
         *
         * ## 依据来自实测，不是照抄文档
         *
         * 拿真实 PNG（64×64 红底黄圆）走 OpenAI 兼容的 `image_url` 数据 URI
         * 直连两家站点，**14 把密钥 14/14 都正确答出「红色、黄色」**，
         * 且 `usage.prompt_tokens_details.image_tokens = 64` —— 图片确实进了模型
         * 并按图计费，不是被忽略后靠文本猜的。
         *
         * ⚠️ 手搓的 base64 PNG 会被服务端判成
         * `Could not decode image: broken data stream`（HTTP 400），
         * 那是**图坏了**而不是不支持图片 —— 与 AGENTS.md ⑧ 记的 sharp 那条同一个坑。
         * 看到这个 400 别急着下「不支持」的结论，换一张真图再测。
         *
         * ## ⚠️ over-claim 的代价（所以这里只声明实测过的能力）
         *
         * pi-ai 的注释写得很明确：少声明只是「附加前被拒、并指名模型」，
         * 多声明则是「图进去了、供应商中途拒绝」，而那时消息**已经落盘**，
         * 会话会卡在一条永远发不出去的请求上反复重试。
         * 所以这个列表只放实测通过的模态，不要凭「多模态」这个词顺手加。
         */
val KEY_MODEL_INPUT = listOf("text", "image")

