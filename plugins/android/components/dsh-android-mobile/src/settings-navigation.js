export function createSettingsNavigation(mpIsMobile, mpDialogBack) {
  const MP_PLUGIN_DESCS = {
  	"./plugins/argfix3.js":"cordis 工具参数扁平化补丁（会话预设 cordis-argfix 自带）", "@deepseek-ai/cordis-plugin-hmr":"cordis 的热模块替换（HMR）插件", "@deepseek-ai/cordis-plugin-timer":"为 cordis 提供定时器服务",
  	"@deepseek-ai/dsh-agent":"Agent 接口、注册表、发起者作用域与事件词汇", "@deepseek-ai/dsh-agent-default-model":"Agent 各入口共用的默认模型选择", "@deepseek-ai/dsh-agent-instructions":"加载工作区 AGENTS.md/CLAUDE.md 指令文件",
  	"@deepseek-ai/dsh-agent-loop":"智能体主循环的具体实现插件", "@deepseek-ai/dsh-agent-presets":"按预设 cordis.yml 文件为每个会话组装智能体", "@deepseek-ai/dsh-api-gateway":"Typert 远程宿主分发器与客户端 API 端点",
  	"@deepseek-ai/dsh-api-remotes":"远程 BFF 装配与 Host 端 Agent／Session 查找策略", "@deepseek-ai/dsh-attachment-local":"DSH_HOME 内按内容寻址的私有附件存储", "@deepseek-ai/dsh-bash-sandbox":"消费沙箱的 bash 执行器实现，逐条命令受限并报告结果",
  	"@deepseek-ai/dsh-client-connection":"连接消费层：上行 HTTP、下行 WebSocket 与可重连的双流", "@deepseek-ai/dsh-client-hmr":"仅开发用的客户端热重载驱动：SSE 帧重建触发 fiber 交换", "@deepseek-ai/dsh-client-locale":"语言插件：Host 侧中英偏好、浏览器回退与命名空间词典",
  	"@deepseek-ai/dsh-client-modules":"客户端模块系统：node 半构建入口图，浏览器半为惰性 CJS 表", "@deepseek-ai/dsh-client-runtime":"客户端核心服务：SlotRegistry 与 SessionRuntime", "@deepseek-ai/dsh-client-ui-agent-preset":"智能体预设界面：后续会话默认值、本会话席位与编排编辑器",
  	"@deepseek-ai/dsh-client-ui-attachment":"对话输入与消息图片插槽的动态附件展示插件", "@deepseek-ai/dsh-client-ui-brand-official":"Web 端侧边栏与对话 Hero 插槽的官方品牌内容", "@deepseek-ai/dsh-client-ui-commands":"客户端命令界面：全局目录缓存、/ 来源与 popupSelect 注册表",
  	"@deepseek-ai/dsh-client-ui-conversation":"会话域：骨架、有序对话流、带 Host 端忙碌回车偏好的输入框与详情宿主", "@deepseek-ai/dsh-client-ui-cordis":"Cordis 动态插件定义卡片：cordis_define 工具行与运行开关", "@deepseek-ai/dsh-client-ui-deliverables":"Web 端产出文件回合尾部与最终回复的可点击文件引用",
  	"@deepseek-ai/dsh-client-ui-directory-picker-browse":"应用内目录浏览界面：渲染宿主列举与创建原语的工作区目录流", "@deepseek-ai/dsh-client-ui-goal":"会话目标界面：停靠输入框上方的 GoalBar，读取目标会话投影", "@deepseek-ai/dsh-client-ui-input-trigger":"输入触发管线：检测 / 与 @、候选菜单、把选择路由到已注册来源",
  	"@deepseek-ai/dsh-client-ui-jobs":"会话头部后台任务列表：镜像 session/jobs 帧的实时注册表状态", "@deepseek-ai/dsh-client-ui-layout":"外壳插件：三栏 AppFrame 与 ctx.layout 视图状态", "@deepseek-ai/dsh-client-ui-message-feedback":"助手消息操作栏的逐条反馈控件，由 messageFeedback 支撑",
  	"@deepseek-ai/dsh-client-ui-model-selection":"模型选择：session.selectModel 上的 /model 弹窗", "@deepseek-ai/dsh-client-ui-permission-presets":"权限界面：新会话默认值设置与当前会话的 /permission 弹窗", "@deepseek-ai/dsh-client-ui-plan":"计划模式输入控件：plan 席位与 /plan 命令通道",
  	"@deepseek-ai/dsh-client-ui-reference":"Web 端统一的 @file 与 @session 引用来源", "@deepseek-ai/dsh-client-ui-renderer":"浏览器 UI 渲染器：ctx.uiRenderer 插槽绑定与应用根装配", "@deepseek-ai/dsh-client-ui-settings":"设置域基础插件：settings 命名空间作用域服务与标准设置插槽契约",
  	"@deepseek-ai/dsh-client-ui-settings-general":"通用设置分节、外壳头部内容、设置词典与版本化欢迎提示", "@deepseek-ai/dsh-client-ui-settings-models":"模型设置与共享的产品引导弹窗，复用既有设置与凭据关联", "@deepseek-ai/dsh-client-ui-settings-plugin-inventory":"Web 插件设置中的只读 Cordis Loader 清单标签页",
  	"@deepseek-ai/dsh-client-ui-settings-plugins":"插件设置分节：各功能自有的标签页与可配置的宿主平面插件卡片", "@deepseek-ai/dsh-client-ui-sidebar":"侧边栏插件：会话多级树、搜索、分组与状态圆点", "@deepseek-ai/dsh-client-ui-skill":"Web 端 skill 引用与专用的 skill 工具行",
  	"@deepseek-ai/dsh-client-ui-subagent":"子智能体会话目录、续接路由 UI 与 @ 引用来源", "@deepseek-ai/dsh-client-ui-theme":"主题插件：亮暗与系统主题状态、--dsw-* 令牌样式", "@deepseek-ai/dsh-client-ui-tool":"客户端工具调用树渲染器与按工具键入的展示插槽",
  	"@deepseek-ai/dsh-client-ui-trajectory":"轨迹事件账本与交互式耗时总览：注册进会话 ViewMap 的纯消费插件", "@deepseek-ai/dsh-client-ui-user-questions":"ask_user_question 功能：宿主工具挂载与提问界面", "@deepseek-ai/dsh-client-ui-workflow-run":"Web 端持久化工作流运行的会话节点与嵌套成员展开",
  	"@deepseek-ai/dsh-client-ui-workspace":"工作区选择插件：向侧边栏与空状态插槽注册 WorkspacePicker", "@deepseek-ai/dsh-code-runtime-worker-thread":"代码执行抽象层的 worker-thread 实现", "@deepseek-ai/dsh-command-compact":"显式压缩会话的斜杠命令",
  	"@deepseek-ai/dsh-command-feedback":"仅记录日志的会话反馈与斜杠命令", "@deepseek-ai/dsh-command-goal":"持久化会话目标的斜杠命令", "@deepseek-ai/dsh-commands":"面向 UI 的命令注册表，由插件提供",
  	"@deepseek-ai/dsh-compaction-basic":"基于 token 计量的压缩策略与 LLM 摘要后端", "@deepseek-ai/dsh-compaction-tool-result-pruner":"可重放的免模型工具结果头中尾裁剪", "@deepseek-ai/dsh-cordis-client-runner":"动态双半插件的浏览器半：事件订阅、闭包求值与守卫门面",
  	"@deepseek-ai/dsh-cordis-host-runner":"动态包定义注册表、host 半沙箱生命周期与调用处理表", "@deepseek-ai/dsh-credentials-local":"文件存储的凭据提供方（$DSH_HOME/.env）", "@deepseek-ai/dsh-file-reference-local":"本地文件系统 ctx.fileReferences 提供方，带限界模糊索引",
  	"@deepseek-ai/dsh-fs-observation-policy":"文件上下文策略：经 fs/* 事件闸门实现观察态、先读后写与版本校验", "@deepseek-ai/dsh-fs-sandbox":"文件系统抽象层的沙箱实现：按调用级沙箱模式拦截写/编辑，读取放行", "@deepseek-ai/dsh-goal":"事件溯源的会话目标状态与生命周期服务",
  	"@deepseek-ai/dsh-goal-round-driver":"带竞态防护的会话目标轮次驱动", "@deepseek-ai/dsh-host-apiproxy":"API 网关：ApiProxy 契约与 ctx.apiProxy 提供方", "@deepseek-ai/dsh-host-directory-picker-auto":"目录选择抽象层的自适应选择器：按启动环境挂载原生或浏览后端",
  	"@deepseek-ai/dsh-host-directory-picker-browse":"目录选择抽象层的应用内浏览后端：宿主文件系统的列举与创建原语", "@deepseek-ai/dsh-host-plugin-inventory":"当前 Cordis Loader 插件状态的只读远程投影", "@deepseek-ai/dsh-host-webserver":"Web 路由注册插件：HTTP 路由、index 改写与静态资源回退",
  	"@deepseek-ai/dsh-jobs-local":"后台任务注册表（ctx.jobs）的进程内实现", "@deepseek-ai/dsh-llm":"提供方中立的 LLM 服务接口", "@deepseek-ai/dsh-llm-deepseek":"为 LLM 抽象层实现 DeepSeek chat-completions 适配",
  	"@deepseek-ai/dsh-llm-pi-ai":"基于 pi-ai 的 DeepSeek 适配器，dsh-llm-deepseek 的设计验证孪生", "@deepseek-ai/dsh-llm-retry":"按提供方路由的 LLM 请求重试策略", "@deepseek-ai/dsh-message-feedback":"与生命周期绑定的逐条消息评分与备注",
  	"@deepseek-ai/dsh-permission-presets":"面向用户的权限预设：一个 Permissions 下拉，统一沙箱模式与审批策略", "@deepseek-ai/dsh-persona":"由编排定义的部署级人格提示分节", "@deepseek-ai/dsh-plan-mode":"按智能体记录的计划模式，含退出确认",
  	"@deepseek-ai/dsh-pwsh-sandbox":"消费沙箱的 PowerShell 执行器实现，逐条命令受限并报告结果", "@deepseek-ai/dsh-repeat-tool-reminder":"重复工具调用防护：智能体循环调用时给出提醒", "@deepseek-ai/dsh-sandbox-local":"沙箱抽象层的本地后端（bwrap、landlock、Seatbelt 等）",
  	"@deepseek-ai/dsh-sandbox-policy":"逐次调用的沙箱策略解析器与当前模型上下文", "@deepseek-ai/dsh-session":"基于事件溯源的会话存储", "@deepseek-ai/dsh-session-checkpoint-policy":"模型请求与工具副作用前的会话持久化检查点",
  	"@deepseek-ai/dsh-session-log-export":"Web 会话日志导出命令与共用的下载弹窗", "@deepseek-ai/dsh-session-persistence-jsonl":"JSONL 持久化会话存储后端", "@deepseek-ai/dsh-session-projection":"会话投影抽象层：投影类型表、提供方契约与当前值注册表",
  	"@deepseek-ai/dsh-session-projection-cache":"持久化投影缓存：逐会话检查点、节流写回与冷读回放", "@deepseek-ai/dsh-session-query-sqlite":"带 SQLite FTS5 全文检索的 ctx.sessionQuery 后端", "@deepseek-ai/dsh-session-reference":"跨会话快照引用与持久化的非可信模型上下文",
  	"@deepseek-ai/dsh-session-stats":"全日志会话计数与耗时投影 sessionStats", "@deepseek-ai/dsh-session-telemetry-otel":"遥测抽象层的 OpenTelemetry 后端，会话记录交给 OTel 日志管道", "@deepseek-ai/dsh-session-title":"基于日志的会话标题服务与提供方注册表",
  	"@deepseek-ai/dsh-session-title-first-prompt-llm":"用首条消息生成会话标题的 LLM 提供方", "@deepseek-ai/dsh-settings-file":"文件存储的设置提供方（settings.yaml）", "@deepseek-ai/dsh-shell-env":"与工具无关的托管 DSH_* shell 环境注册表",
  	"@deepseek-ai/dsh-skill":"智能体 Skill 提供方注册表", "@deepseek-ai/dsh-skill-badge":"内置 dsh badge 徽章技能的提供方", "@deepseek-ai/dsh-skill-filesystem":"本地文件系统的 Skill 提供方",
  	"@deepseek-ai/dsh-spill-local":"溢出存储抽象层的本地文件系统实现", "@deepseek-ai/dsh-spill-policy":"工具结果溢出策略：超长结果替换为预览与溢出文件路径", "@deepseek-ai/dsh-storage":"存储中枢 ctx.storage：具名后端注册表与数据形态设施",
  	"@deepseek-ai/dsh-storage-domain":"领域数据形态：经 schema 校验、可发事件的 KV 领域", "@deepseek-ai/dsh-storage-json":"存储中枢的 JSON 文件 KV 后端", "@deepseek-ai/dsh-subagent":"子智能体抽象层（ctx.subagents）与提供方注册表",
  	"@deepseek-ai/dsh-subagent-fork-in-process":"进程内 fork 子智能体后端：用父日志前缀启动子智能体", "@deepseek-ai/dsh-subagent-spawn-in-process":"进程内 spawn 子智能体后端（ctx.agents）", "@deepseek-ai/dsh-subprocess-local":"子进程抽象层的本地实现",
  	"@deepseek-ai/dsh-system-prompt":"系统提示词组装注册表", "@deepseek-ai/dsh-token-meter":"可重放的 token 计量服务（ctx.tokenMeter）", "@deepseek-ai/dsh-tool-ask-user":"基于 ctx.userQuestions 抽象层的提问工具",
  	"@deepseek-ai/dsh-tool-bash":"面向模型的 bash 工具，支持后台任务与沙箱提权", "@deepseek-ai/dsh-tool-call-timeout-policy":"工具调用超时策略：按工具设置截止时间并返回超时错误", "@deepseek-ai/dsh-tool-cordis":"自省式 Cordis 工具集：查看运行时状态、挂载与卸载模型编写的插件",
  	"@deepseek-ai/dsh-tool-fs":"面向模型的文件读写与编辑工具（ctx.fs）", "@deepseek-ai/dsh-tool-fs-search":"面向模型的文件检索工具（glob/grep，ripgrep 支持）", "@deepseek-ai/dsh-tool-goal":"面向模型的同会话目标工具，带执行期权限校验",
  	"@deepseek-ai/dsh-tool-jobs":"面向模型的后台任务工具（job_output、job_list、job_kill）", "@deepseek-ai/dsh-tool-present":"把文件登记为本次任务的交付物", "@deepseek-ai/dsh-tool-pwsh":"基于 bash 执行器抽象层的 pwsh 工具",
  	"@deepseek-ai/dsh-tool-ralph":"面向模型的 Ralph 循环：全新智能体多轮迭代", "@deepseek-ai/dsh-tool-skill":"面向模型的 skill 加载工具", "@deepseek-ai/dsh-tool-str-replace-editor":"面向模型的文件查看、创建、替换与插入工具",
  	"@deepseek-ai/dsh-tool-subagent":"面向模型的子智能体委派工具（ctx.subagents）", "@deepseek-ai/dsh-tool-subagent-control":"全局命名工具：send_message 等子智能体续接操作", "@deepseek-ai/dsh-tool-subagent-control/list-agents":"全局命名工具：send_message 等子智能体续接操作",
  	"@deepseek-ai/dsh-tool-subagent-report":"子智能体作用域的 report 上报工具", "@deepseek-ai/dsh-tool-todo":"面向模型的 todo_write 待办工具", "@deepseek-ai/dsh-tool-web":"面向模型的网络工具：web_search 与 web_fetch",
  	"@deepseek-ai/dsh-tool-workflow":"面向模型的 workflow 工具：执行 JS 编排脚本", "@deepseek-ai/dsh-tools":"工具注册表与工具执行流水线", "@deepseek-ai/dsh-typert-loader":"生成的 Typert 包贡献的加载器集成",
  	"@deepseek-ai/dsh-typert-registry":"生成包反射与 Zod schema 的运行时注册表", "@deepseek-ai/dsh-user-approval":"用户审批抽象层（ctx.approval）：一次性授权经应答方瀑布派发，默认拒绝", "@deepseek-ai/dsh-user-questions":"agent 运行期间向人提问的抽象层（ctx.userQuestions）",
  	"@deepseek-ai/dsh-web":"网络访问抽象层 ctx.web：搜索/抓取提供方注册表与 WebError 分类", "@deepseek-ai/dsh-web-app":"浏览器界面 bundle：dsh-base 的 web 补丁层与运行时粘合插件", "@deepseek-ai/dsh-web-app/startup":"浏览器界面 bundle：dsh-base 的 web 补丁层与运行时粘合插件",
  	"@deepseek-ai/dsh-web-search-deepseek":"DeepSeek 搜索提供方：经兼容 API 调用原生 web_search", "@deepseek-ai/dsh-workflow-worker-thread":"worker-thread 工作流引擎：宿主事件循环外执行编排脚本", "@deepseek-ai/dsh-workspace":"工作区实体注册表：持久化记录与经校验的会话挂载",
  	"@dsh-android/dsh-client-ui-responsive":"移动端核心响应式布局：窄屏抽屉侧栏、底部弹层、手机顶栏与安全区", "@dsh-external/dsh-thinking-loop-guard":"检测并打断思维链死循环：推理反复重复时自动中止本轮并注入纠正提示", "dsh-android-mobile":"移动端界面打磨：顶栏、消息操作、插件分组、设置页两级化等 24 项修复",
  	"@xmanrui/dsh-im":"接入九种 IM 机器人与公网 AI Office，把消息转发给本机 Harness", "cordis:include":"Cordis 组合的 include 指令，把外部插件文件并入当前组合", "dsh-agconfig":"在设置里填写 Agnes AI 密钥，供图片与视频生成两个工具共用",
  	"dsh-agimage":"Agnes AI 图片生成工具（generate_image），结果图在会话内直接显示", "dsh-agvideo":"Agnes AI 视频生成工具（generate_video），支持文生、首尾帧、参考图三模式", "dsh-away-nudge":"离开提醒：AI 回复后你久未查看时，自动唤醒会话并发微信提醒",
  	"dsh-basic-auth":"给 DSH 网页入口加一道账号密码门卫：凭据由浏览器保管，换设备也不用重新登录", "dsh-client-ui-file-upload":"在「+」菜单加「文件上传」，文件落到会话 uploads/ 目录供 AI 读取", "dsh-client-ui-photo-upload":"在「+」菜单加「照片上传」，手机可直接从相册或相机发图",
  	"dsh-file-show":"让 AI 把本地文件展示进对话：图片成图卡，其它类型成可点文件卡", "dsh-im-proactive":"让 AI 主动发消息给你的工具（send_im_message）", "dsh-imgswitch":"设置页「图片输入」开关：按渠道或模型开关图片输入，免手改配置",
  	"dsh-memory":"跨会话记忆与回复风格：核心记忆常驻注入，另有 7 个记忆工具与设置页", "dsh-restart-local":"独立自重启控制：设置页按钮与 restart_harness 工具", "dsh-skill-list":"在设置 → 插件里加「Skill 列表」页，列出当前会话可用的 Skill",
  	"dshmarket":"DSH 可视化插件市场：浏览、搜索并一键安装社区插件",
  };
  // 注入描述。幂等判据：已注入且 dataset.mpDescFor 与目标文案一致则不动 DOM
  // ——否则自己写 DOM 又会触发 observer，重复重建会拖死页面（见修复 24）。
  // 注意：官方只为**已展开**的卡片渲染 .qSYn7G_cardDetails，所以每次最多处理一两张。
  const applyPluginDescriptions = (section) => {
  	if (!section) return;
  	section.querySelectorAll("li.qSYn7G_card").forEach((card) => {
  		const details = card.querySelector(":scope > .qSYn7G_cardDetails");
  		if (!details) return;
  		const title = card.querySelector(".qSYn7G_cardTitle");
  		const mod = (title && title.getAttribute("title")) || "";
  		const desc = MP_PLUGIN_DESCS[mod] || "";
  		const marked = details.querySelector(":scope > .mp-plugin-desc");
  		if (!desc) {
  			if (marked) marked.remove();
  			return;
  		}
  		if (marked && marked.dataset.mpDescFor === desc) return;
  		const el = marked || document.createElement("p");
  		if (!marked) {
  			el.className = "mp-plugin-desc";
  			// 设置 dialog 子树内一律用 inline style（注入 CSS 会把 dialog 推出屏幕，见 §4.2）
  			el.style.cssText = "margin:0 0 8px;color:var(--dsw-alias-label-secondary,#555);"
  				+ "font-size:12px;line-height:17px;overflow-wrap:anywhere;";
  		}
  		el.dataset.mpDescFor = desc;
  		el.textContent = desc;
  		if (!marked) {
  			const dl = details.querySelector(":scope > .qSYn7G_details");
  			if (dl) details.insertBefore(el, dl); else details.appendChild(el);
  		}
  	});
  };

  function setupPluginGrouping(content) {
  	if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
  	if (!content || content.dataset.mpPluginGroup) return;
  	content.dataset.mpPluginGroup = "1";
  	// ── 0.1.5 结构（2026-09-15 重新适配）──
  	// 官方自己已经有**两级**分组：
  	//   SECTION.qSYn7G_group[data-plugin-scope="preset"] 「会话插件」  → 28 个（当前 Agent 预设）
  	//   SECTION.qSYn7G_group[data-plugin-scope="global"] 「全局插件」  → 171 个（系统与所有会话共用）
  	// 我们原来那套「用户插件 / 官方插件」是给旧版 182 个扁平列表用的，
  	// 套在「会话插件」（28 个全官方）上只会得到一个空的「用户插件 0」——纯噪音。
  	// 新规则：**只在「全局插件」这一级内部**再做「用户 / 官方」二分
  	// （171 个里混着 14 个自装 + 155 官方 + 2 内置，混排才是问题）；
  	// 「会话插件」保持官方原样，不再插手。
  	const isOfficial = (moduleName) => {
  		if (!moduleName) return true;
  		if (moduleName === "cordis:include" || moduleName === "dshmarket") return true;
  		return moduleName.startsWith("@deepseek-ai/");
  	};
  	const isNarrow = () => mpIsMobile();
  	// 全局插件里：自装 14 个默认**展开**（这是用户最关心的），官方 155 个默认**折叠**
  	// （否则一屏 171 张卡片，滚半天）。空分组直接隐藏。
  	const expanded = { user: true, official: false };
  	let ul = null;
  	const groupOf = (card) => {
  		if (card.dataset.mpGroup) return card.dataset.mpGroup;
  		const title = card.querySelector(".qSYn7G_cardTitle");
  		const moduleName = (title && (title.getAttribute("title") || title.textContent)) || "";
  		const g = isOfficial(moduleName) ? "official" : "user";
  		card.dataset.mpGroup = g;
  		return g;
  	};
  	const applyVisibility = () => {
  		if (!ul) return;
  		const counts = { user: 0, official: 0 };
  		ul.querySelectorAll(":scope > .qSYn7G_card").forEach((card) => {
  			const g = groupOf(card);
  			counts[g] = (counts[g] || 0) + 1;
  			card.style.display = expanded[g] ? "" : "none";
  		});
  		ul.querySelectorAll(":scope > .mp-plugin-group").forEach((h) => {
  			const g = h.dataset.group;
  			// 空分组隐藏：0.1.5 常出现「用户插件 0」（当前预设未用到自装插件），
  			// 留一个点不开的空标题只是噪音。
  			const empty = (counts[g] || 0) === 0;
  			h.style.display = empty ? "none" : "";
  			h.classList.toggle("mp-plugin-group-open", !!expanded[g]);
  			const btn = h.querySelector(".mp-plugin-group-btn");
  			if (btn) btn.setAttribute("aria-expanded", String(!!expanded[g]));
  		});
  	};
  	const toggle = (g) => {
  		expanded[g] = !expanded[g];
  		applyVisibility();
  	};
  	const mkHeader = (g, label, count) => {
  		const li = document.createElement("li");
  		li.className = "mp-plugin-group";
  		li.dataset.group = g;
  		const btn = document.createElement("button");
  		btn.type = "button";
  		btn.className = "mp-plugin-group-btn";
  		const lab = document.createElement("span");
  		lab.className = "mp-plugin-group-label";
  		lab.textContent = label;
  		const cnt = document.createElement("span");
  		cnt.className = "mp-plugin-group-count";
  		cnt.textContent = String(count);
  		const chev = document.createElement("span");
  		chev.className = "mp-plugin-group-chevron";
  		chev.textContent = "▾";
  		btn.append(lab, cnt, chev);
  		btn.addEventListener("click", () => toggle(g));
  		li.appendChild(btn);
  		return li;
  	};
  	// ⚠️ 修复 24（UI 卡死/渲染进程崩溃）：
  	// 判据必须用 classList.contains，**绝不能用 className 全等**。
  	// 展开分组时 applyVisibility() 会 classList.toggle("mp-plugin-group-open")，
  	// 于是 header 的 className 变成 "mp-plugin-group mp-plugin-group-open"，
  	// 与全等字面量不再匹配 → groupedSignature 永远返回 false → 每次 mutation
  	// 都走 replaceChildren 重建 184 张卡片 → 重建自身又产生 mutation →
  	// 无限微任务循环，主线程锁死、WebView renderer 崩溃（用户表现为点卡片后 UI 卡死、
  	// 必须重新进入 Web）。折叠态恰好全等所以不复现，只有展开分组后点卡片才触发。
  	const isGroupHeader = (el, group) =>
  		!!el && el.classList && el.classList.contains("mp-plugin-group") && el.dataset.group === group;
  	const groupedSignature = (userCards, officialCards) => {
  		const kids = Array.from(ul.children);
  		if (kids.length !== userCards.length + officialCards.length + 2) return false;
  		if (!isGroupHeader(kids[0], "user")) return false;
  		if (!isGroupHeader(kids[1 + userCards.length], "official")) return false;
  		for (let i = 0; i < userCards.length; i++) if (kids[1 + i] !== userCards[i]) return false;
  		for (let i = 0; i < officialCards.length; i++) if (kids[2 + userCards.length + i] !== officialCards[i]) return false;
  		return true;
  	};
  	// ⚠️ 修复 24 加固：自愈型熔断。
  	// 上面"幂等"的成立完全依赖 groupedSignature 判据正确；判据一旦失效
  	// （修复 24 正是 className 全等被 -open 类打破），replaceChildren 自身
  	// 产生的 mutation 会把 normalize 无限重入下去 → 主线程锁死、renderer 崩溃。
  	// 这里做**基于时间窗**的熔断：1 秒内重建超过 10 次即认定不收敛，
  	// 暂停分组 2 秒（期间只做 applyVisibility，不再重建结构），然后自动复位重试。
  	// 效果：即使判据再次失效，最坏也只是"分组短暂不生效"，绝不会拖死页面。
  	let rebuildTimes = [];
  	const rebuildAllowed = () => {
  		const now = Date.now();
  		rebuildTimes = rebuildTimes.filter((t) => now - t < 1000);
  		if (rebuildTimes.length >= 10) return false;
  		rebuildTimes.push(now);
  		return true;
  	};
  	const normalize = () => {
  		if (!isNarrow()) return;
  		// 描述注入对两个分区都要做（会话插件也有卡片）
  		applyPluginDescriptions(content);
  		const section = content.querySelector(".qSYn7G_section");
  		if (!section) return;
  		// ⚠️ 只接管「全局插件」分区（data-plugin-scope="global"）。
  		//     「会话插件」交给官方原样渲染（它只有 28 个官方插件，二分无意义）。
  		const globalGroup = section.querySelector('.qSYn7G_group[data-plugin-scope="global"]');
  		if (!globalGroup) return;
  		const u = globalGroup.querySelector("ul.qSYn7G_cards");
  		if (!u) return;            // 折叠态官方不渲染 ul，等展开后再说
  		if (u !== ul) ul = u;
  		const cards = Array.from(u.querySelectorAll(":scope > .qSYn7G_card"));
  		if (cards.length === 0) return;
  		const search = section.querySelector("input[type=search]");
  		const searching = search && search.value.trim().length > 0;
  		if (searching) {
  			// 搜索时保持官方扁平过滤列表（React 自己渲染）
  			if (u.querySelector(".mp-plugin-group")) {
  				u.querySelectorAll(":scope > .mp-plugin-group").forEach((h) => h.remove());
  				u.querySelectorAll(":scope > .qSYn7G_card").forEach((c) => { c.style.display = ""; });
  			}
  			return;
  		}
  		const userCards = [];
  		const officialCards = [];
  		cards.forEach((card) => {
  			(groupOf(card) === "user" ? userCards : officialCards).push(card);
  		});
  		if (!groupedSignature(userCards, officialCards) && rebuildAllowed()) {
  			u.replaceChildren(
  				mkHeader("user", "自装插件", userCards.length),
  				...userCards,
  				mkHeader("official", "官方插件", officialCards.length),
  				...officialCards,
  			);
  		}
  		applyVisibility();
  	};
  	const observer = new MutationObserver(() => normalize());
  	// 同步 normalize：MutationObserver 回调在微任务阶段运行，先于浏览器绘制，
  	// 因此 React 渲染完扁平列表后、首帧绘制前就完成分组 → 不闪现旧样式。
  	// normalize 幂等：第二次进入时结构已一致，不再改动，不会死循环。
  	observer.observe(content, { childList: true, subtree: true });
  	normalize();
  }


  // ── 6.5 设置页导航专属图标 ──
  // 官方 navIcon() 只给 models / agent-presets / plugins 三个 id 发专属图标，
  // 其余 id 一律落到 IconSettingsOutline16（齿轮）→ 手机上是一排重复齿轮。
  // 这里按导航项文本匹配，替换 .VOzbGW_navIcon 里的 SVG。
  // 规格对齐官方 Icon*Outline16：viewBox 0 0 16 16、fill:none、stroke:currentColor，
  // 因此暗色模式自动跟随文字色，无需额外适配。
  // ⚠️ 用 stroke 而非 fill，9 个并排才不糊；marker 属性 data-mp-icon 做幂等判据。
  const MP_ICONS = {
  	// 通用设置：直接复用官方 IconSettingsOutline16 的原始 SVG（实心 fill）。
  	// 自绘齿轮在 16px 下无法做到几何对称（实测自绘版中心偏上 1.8px），
  	// 复用官方原始路径既精准又与官方视觉一致。
  	general: '<path fill="currentColor" d="M14.0861 5.51366C13.8717 5.0575 13.588 4.58542 13.2889 4.18108C13.208 4.07172 13.1596 4.04373 13.0243 4.03054C12.4277 3.97255 11.8245 4.05527 11.2269 3.9972C10.7224 3.94816 10.3133 3.71661 10.0115 3.30919C9.66986 2.84777 9.43973 2.31343 9.09824 1.85234C9.01771 1.74365 8.96805 1.71589 8.83354 1.70282C8.29432 1.65044 7.70402 1.65061 7.16656 1.70282C7.03205 1.71589 6.98239 1.74365 6.90186 1.85234C6.56067 2.31303 6.33025 2.84774 5.98855 3.30919C5.68681 3.71661 5.27774 3.94816 4.77317 3.9972C4.17564 4.05527 3.57239 3.97255 2.97585 4.03054C2.84046 4.04373 2.79208 4.07172 2.71115 4.18108C2.41212 4.58542 2.12835 5.0575 1.91403 5.51366C1.85299 5.64359 1.85286 5.7018 1.91403 5.8319C2.14865 6.33077 2.49748 6.76892 2.73237 7.26854C2.9594 7.7515 2.96041 8.24717 2.73338 8.73044C2.49837 9.23061 2.14891 9.66837 1.91403 10.1681C1.85291 10.2982 1.85299 10.3564 1.91403 10.4863C2.12856 10.9429 2.41185 11.4142 2.71115 11.8189C2.79208 11.9283 2.84046 11.9563 2.97585 11.9694C3.57239 12.0274 4.17564 11.9447 4.77317 12.0028C5.27774 12.0518 5.68681 12.2834 5.98855 12.6908C6.33024 13.1522 6.56037 13.6866 6.90186 14.1476C6.98239 14.2563 7.03205 14.2841 7.16656 14.2972C7.70402 14.3494 8.29432 14.3495 8.83354 14.2972C8.96805 14.2841 9.01771 14.2563 9.09824 14.1476C9.43944 13.687 9.66985 13.1522 10.0115 12.6908C10.3133 12.2834 10.7224 12.0518 11.2269 12.0028C11.8244 11.9447 12.4271 12.0275 13.0243 11.9694C13.1596 11.9563 13.208 11.9283 13.2889 11.8189C13.5891 11.4131 13.872 10.942 14.0861 10.4863C14.1471 10.3564 14.1472 10.2982 14.0861 10.1681C13.8513 9.66861 13.5017 9.23061 13.2667 8.73044C13.0397 8.24717 13.0407 7.7515 13.2677 7.26854C13.5026 6.7689 13.8513 6.33106 14.0861 5.8319C14.1472 5.7018 14.1471 5.64359 14.0861 5.51366ZM15.3035 6.40373C15.0685 6.90359 14.7188 7.34119 14.4841 7.84037C14.4231 7.97025 14.423 8.02855 14.4841 8.15861C14.7189 8.65833 15.0685 9.09611 15.3035 9.59626C15.5308 10.0801 15.5308 10.5744 15.3035 11.0582C15.052 11.5933 14.7225 12.1426 14.37 12.6191C14.0685 13.0265 13.6581 13.259 13.1536 13.3081C12.5566 13.366 11.9541 13.2835 11.3573 13.3414C11.2228 13.3545 11.1731 13.3823 11.0926 13.491C10.7511 13.9521 10.521 14.4864 10.1793 14.9478C9.87828 15.3542 9.46719 15.5869 8.96387 15.6358C8.34008 15.6964 7.66194 15.6966 7.03623 15.6358C6.53291 15.5869 6.12182 15.3542 5.82084 14.9478C5.47911 14.4863 5.24878 13.9517 4.90753 13.491C4.82701 13.3823 4.77734 13.3545 4.64284 13.3414C4.04647 13.2835 3.44373 13.366 2.84653 13.3081C2.34201 13.259 1.93164 13.0265 1.63013 12.6191C1.27867 12.144 0.948453 11.5941 0.696621 11.0582C0.469315 10.5744 0.469279 10.0801 0.696621 9.59626C0.931628 9.09613 1.2813 8.65807 1.51597 8.15861C1.57708 8.02855 1.57702 7.97025 1.51597 7.84037C1.28117 7.34095 0.931635 6.9036 0.696621 6.40373C0.469213 5.91992 0.469367 5.42562 0.696621 4.94183C0.948441 4.40587 1.27868 3.85598 1.63013 3.38092C1.93164 2.97349 2.34201 2.74095 2.84653 2.6919C3.44353 2.63397 4.04599 2.71649 4.64284 2.65856C4.77734 2.64549 4.82701 2.61774 4.90753 2.50904C5.24905 2.04792 5.47913 1.51362 5.82084 1.05219C6.12182 0.645806 6.53291 0.413119 7.03623 0.364178C7.66002 0.303556 8.33816 0.303369 8.96387 0.364178C9.46719 0.413119 9.87828 0.645806 10.1793 1.05219C10.521 1.51365 10.7513 2.04828 11.0926 2.50904C11.1731 2.61774 11.2228 2.64549 11.3573 2.65856C11.9541 2.71649 12.5566 2.63397 13.1536 2.6919C13.6581 2.74095 14.0685 2.97349 14.37 3.38092C14.7214 3.85598 15.0517 4.40587 15.3035 4.94183C15.5307 5.42562 15.5309 5.91992 15.3035 6.40373Z"/><path fill="currentColor" d="M9.13764 7.99999C9.13764 7.3715 8.62855 6.8624 8.00005 6.8624C7.37155 6.8624 6.86246 7.3715 6.86246 7.99999C6.86246 8.62849 7.37155 9.13759 8.00005 9.13759C8.62855 9.13759 9.13764 8.62849 9.13764 7.99999ZM10.4834 7.99999C10.4834 9.37126 9.37132 10.4833 8.00005 10.4833C6.62878 10.4833 5.51674 9.37126 5.51674 7.99999C5.51674 6.62873 6.62878 5.51669 8.00005 5.51669C9.37132 5.51669 10.4834 6.62873 10.4834 7.99999Z"/>',
  	model: '<path d="M2 4.2h4.2M9.4 4.2H14"/><circle cx="7.8" cy="4.2" r="1.6"/><path d="M2 11.8h2.6M7.8 11.8H14"/><circle cx="6.2" cy="11.8" r="1.6"/>',
  	image: '<rect x="1.8" y="3" width="12.4" height="10" rx="1.6"/><circle cx="5.6" cy="6.4" r="1.1"/><path d="M2.2 11.2l3-2.6 2.4 2 2.3-2.4 3.7 3.4"/>',
  	plugin: '<path d="M6.1 1.6c.9 0 1.6.7 1.6 1.6v1h2.6c.5 0 .9.4.9.9v2.4h1c.9 0 1.6.7 1.6 1.6s-.7 1.6-1.6 1.6h-1v3.1c0 .5-.4.9-.9.9H7.2v-1.1c0-.9-.7-1.6-1.6-1.6s-1.6.7-1.6 1.6v1.1H2.9c-.5 0-.9-.4-.9-.9V9.7h1.1c.9 0 1.6-.7 1.6-1.6S4 6.5 3.1 6.5H2V4.3c0-.5.4-.9.9-.9h3.2z"/>',
  	bell: '<path d="M4.3 7.2a3.7 3.7 0 0 1 7.4 0v2.3l1.1 1.9H3.2l1.1-1.9z"/><path d="M6.6 13.2a1.6 1.6 0 0 0 2.8 0"/><path d="M13.4 2.4a6.2 6.2 0 0 1 1.5 2.6M2.6 2.4A6.2 6.2 0 0 0 1.1 5"/>',
  	// AI 回复风格：复用官方 IconSparkle16（一大两小凹弧星芒，实心）。
  	// AI 的通用视觉标识，比自绘的「气泡+三点」更贴题，且与品牌一致。
  	style: '<path fill="currentColor" d="M6.1 3.1Q6.6 7.8 11.3 8.3Q6.6 8.8 6.1 13.5Q5.6 8.8 0.9 8.3Q5.6 7.8 6.1 3.1Z"/><path fill="currentColor" d="M11.9 1Q12.2 3.7 14.9 4Q12.2 4.3 11.9 7Q11.6 4.3 8.9 4Q11.6 3.7 11.9 1Z"/><path fill="currentColor" d="M12.5 9.4Q12.7 11.4 14.7 11.6Q12.7 11.8 12.5 13.8Q12.3 11.8 10.3 11.6Q12.3 11.4 12.5 9.4Z"/>',
  	agent: '<rect x="3" y="5.4" width="10" height="8" rx="2"/><path d="M8 5.4V3.2"/><circle cx="8" cy="2.2" r="1"/><circle cx="6.1" cy="9.1" r="1"/><circle cx="9.9" cy="9.1" r="1"/><path d="M6.3 11.6h3.4"/>',
  	im: '<rect x="2" y="2.4" width="12" height="8.6" rx="2.2"/><path d="M5.4 14.2l1.6-3.2"/><circle cx="6" cy="6.7" r="0.95"/><circle cx="8" cy="6.7" r="0.95"/><circle cx="10" cy="6.7" r="0.95"/>',
  	market: '<path d="M2 6.6V4.8l.9-2.2c.2-.4.5-.6 1-.6h8.2c.5 0 .8.2 1 .6l.9 2.2v1.8"/><path d="M2 6.6h12v1.6c0 1.2-1 2.2-2.2 2.2S9.6 9.4 9.6 8.2c0 1.2-1 2.2-2.2 2.2S5.2 9.4 5.2 8.2C5.2 9.4 4.2 10.4 3 10.4S2 9.4 2 8.2z"/><path d="M3.4 10.6v3.2c0 .5.4.9.9.9h7.4c.5 0 .9-.4.9-.9v-3.2"/><path d="M6.6 14.7v-3h2.8v3"/>',
  	// 特权权限（uid 2000）：自绘「盾牌 + 钥匙孔」。
  	// 语义是「被管住的提权」—— 盾牌代表边界还在（该身份反而没有沙箱），
  	// 钥匙孔代表拿得到特权。用描边风格（同 plugin/model 等），
  	// 与「通用设置/AI 回复风格」那两个实心官方图标区分开。
  	privileged: '<path d="M8 1.8l5.6 2.1v4.5c0 3.2-2.4 5.6-5.6 6.5-3.2-.9-5.6-3.3-5.6-6.5V3.9z"/><circle cx="8" cy="6.9" r="1.35"/><path d="M8 8.25V10.7"/>',
  	// 悬浮气泡：圆（气泡本体）+ 一条底线（屏幕/别的应用）。
  	// 语义是「浮在其它应用之上」—— 这也是气泡与「IM机器人」那个
  	// 「气泡+三点」的区别所在：这里表达的是**层级**，不是对话。
  	bubble: '<circle cx="8" cy="6.5" r="4.3"/><path d="M2.2 13.7h11.6"/>',
  	// 交流群：双人。前面一个是完整的头+肩，后面那个只画半侧轮廓，
  	// 靠「被挡住」表现多人 —— 16px 下画两个完整人会糊成一团。
  	community: '<circle cx="5.9" cy="5.9" r="2.5"/><path d="M1.5 13.4c0-2.4 2-4.4 4.4-4.4s4.4 2 4.4 4.4"/><path d="M10.7 3.8a2.5 2.5 0 0 1 0 4.6"/><path d="M12 9.4c1.4.6 2.3 2 2.3 3.8"/>',
  };
  // 文本 → 图标键。先长后短匹配，避免「插件」抢走「插件市场」。
  const MP_NAV_ICONS = [
  	["通用设置", "general"],
  	["图片输入", "image"],
  	["AI 回复风格", "style"],
  	["AI回复风格", "style"],
  	["Agent 预设", "agent"],
  	["Agent预设", "agent"],
  	["IM机器人", "im"],
  	["IM 机器人", "im"],
  	["插件市场", "market"],
  	["模型", "model"],
  	["插件", "plugin"],
  	["离开提醒", "bell"],
  	// 本插件自己注册的分节。⚠️ 必须排在「插件」之后：mpIconKeyOf 是
  	// 子串匹配、先到先得，「特权权限」不含「插件」所以不冲突，但顺序仍
  	// 保持「长/具体优先」的既有约定。
  	["特权权限", "privileged"],
  	["Privileged access", "privileged"],
  	// 本插件自己注册的另两个分节（悬浮气泡 26 / 交流群 27）。
  	// ⚠️ 同样必须排在「插件」之后才不会被它的子串匹配抢先；
  	//   这两个词本身不含「插件」，所以只是沿用既有顺序约定。
  	["悬浮气泡", "bubble"],
  	["Floating bubble", "bubble"],
  	["交流群", "community"],
  	["Community group", "community"],
  ];
  const mpIconKeyOf = (label) => {
  	const t = (label || "").trim();
  	if (!t) return null;
  	for (const [needle, key] of MP_NAV_ICONS) if (t.includes(needle)) return key;
  	return null;
  };
  // 实心图标（复用官方路径）需 fill，自绘的 8 个走 stroke。按 key 区分。
  const MP_FILL_ICONS = { general: true, style: true };
  const mpMakeIcon = (key) => {
  	const body = MP_ICONS[key];
  	if (!body) return null;
  	const NS = "http://www.w3.org/2000/svg";
  	const svg = document.createElementNS(NS, "svg");
  	svg.setAttribute("width", "16");
  	svg.setAttribute("height", "16");
  	svg.setAttribute("viewBox", "0 0 16 16");
  	// 根节点始终 fill="none"：实心图标靠 path 自带的 fill="currentColor"，
  	// 描边图标靠这里的 stroke 属性，互不干扰。
  	svg.setAttribute("fill", "none");
  	if (!MP_FILL_ICONS[key]) {
  		svg.setAttribute("stroke", "currentColor");
  		svg.setAttribute("stroke-width", "1.4");
  		svg.setAttribute("stroke-linecap", "round");
  		svg.setAttribute("stroke-linejoin", "round");
  	}
  	svg.setAttribute("aria-hidden", "true");
  	svg.style.flex = "none";
  	svg.innerHTML = body;
  	return svg;
  };
  function setupNavIcons(nav) {
  	if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
  	const navList = nav.querySelector(".VOzbGW_navList");
  	if (!navList || navList.dataset.mpIcons === "1") return;
  	navList.dataset.mpIcons = "1";
  	// 幂等归一化：结构已一致就完全不动，避免 observer 自触发死循环。
  	const normalize = () => {
  		navList.querySelectorAll("button").forEach((btn) => {
  			const label = btn.querySelector(".VOzbGW_navLabel");
  			const key = mpIconKeyOf(label ? label.textContent : btn.innerText);
  			if (!key) return;
  			// 官方图标 slot：className 含 navIcon
  			const slot = btn.querySelector('[class*="navIcon"]');
  			if (!slot) return;
  			if (slot.dataset.mpIcon === key && slot.firstElementChild) return; // 已是目标态
  			const svg = mpMakeIcon(key);
  			if (!svg) return;
  			slot.replaceChildren(svg);
  			slot.dataset.mpIcon = key;
  		});
  	};
  	// 观察子树：官方 React 可能只重渲染某个 button 的图标 slot（不重建 button），
  	// 只观察直接 childList 会漏掉这种情况 → 图标永久丢失。
  	// 防自我触发：normalize 的判据是「slot 已带正确 marker 且含 svg 就完全不动」，
  	// 因此本函数自己造成的 mutation 在下一轮会立即早退，不会无限循环。
  	// 另加 reentry 闸：回调内部同步执行，防御性避免极端情况下的重入。
  	let running = false;
  	const observer = new MutationObserver(() => {
  		if (running) return;
  		running = true;
  		try { normalize(); } finally { running = false; }
  	});
  	observer.observe(navList, { childList: true, subtree: true });
  	normalize();
  }

  // ── 7. 设置页两级化（全 inline style，不用 CSS 规则——见 CSS 区注释） ──
  function setupSettingsDialog(dialog) {
  	if (!mpIsMobile()) return; // 修复 40：仅移动端；桌面端完全走官方
  	// ⚠️⚠️ 0.2.2 起**侧栏根节点也带 role="dialog" aria-modal="true"**
  	//   （移动端抽屉的语义化写法）。若不排除，这里会把侧栏当成设置页改造：
  	//   侧栏里凭空冒出返回圆钮 + 居中的「设置」标题，而真正的设置页
  	//   反倒没人处理 —— 真机表现就是"设置页布局错乱"。
  	//   侧栏的识别特征：根节点类名 hHd-Xa_root / 直接子节点有 hHd-Xa_panelList。
  	if (dialog.classList.contains("hHd-Xa_root")) return;
  	if (Array.from(dialog.children).some((c) => c.classList && c.classList.contains("hHd-Xa_panelList"))) return;
  	const nav = Array.from(dialog.children).find((c) => c.tagName === "NAV");
  	if (!nav) return;
  	// ⚠️ 标记载体必须落在"确认这是设置页"之后：原先先写 mpReady 再找 nav，
  	//   一旦首次调用时 React 尚未渲染出 nav，就会永久放弃改造（真机上
  	//   表现为"有的设备设置页是改造后的样子，有的还是官方原样"）。
  	if (dialog.dataset.mpReady) return;
  	dialog.dataset.mpReady = "1";
  	dialog.dataset.mpSettings = "1";
  	// 隐藏安卓壳遗留的"开发者选项"：其功能依赖 window.androidBridge /
  	// /api/android/*，在 Web 进程中不存在，保留会进入一个空壳页面。
  	nav.querySelectorAll("button").forEach((b) => {
  		if ((b.innerText || "").trim() === "开发者选项") b.style.display = "none";
  	});
  	// ⚠️ 0.2.2 的结构变了：内容区 .VOzbGW_options 是 dialog 的**直接子元素**，
  	//   不再是 nav 的兄弟。原来写 nav.nextElementSibling 会取到 .VOzbGW_header
  	//   （官方给它 display:none），于是"点二级项"显示的是空内容区。
  	const content = dialog.querySelector(".VOzbGW_options") || nav.nextElementSibling;
  	setupPluginGrouping(content);
  	setupNavIcons(nav);
  	const isNarrow = () => mpIsMobile();
  	// — 全屏 + 纵向布局（避免旧样式闪烁：立即应用，不等动画结束） —
  	Object.assign(dialog.style, {
  		width: "100vw", maxWidth: "100vw", height: "100vh", maxHeight: "100vh",
  		borderRadius: "0", boxShadow: "none", position: "fixed", top: "0", left: "0",
  		flexDirection: "column", overflow: "hidden",
  	});
  	const overlay = dialog.parentElement;
  	if (overlay) {
  		overlay.style.left = "0";
  		overlay.style.width = "100vw";
  		overlay.style.height = "100vh";
  		overlay.style.alignItems = "stretch";
  	}
  	// ⚠️ navTitle 是 dialog 的直接子元素，不是 nav 的子元素。
  	//   原来写 nav.querySelector 恒为 null → 官方的左上角「设置」大标题从没被藏掉，
  	//   和插件加的居中标题重复显示，这就是"右上角/左上角各有一个设置"的来源。
  	const navTitle = dialog.querySelector(".VOzbGW_navTitle");
  	if (navTitle) navTitle.style.display = "none";
  	// 顶部返回条：只有圆形返回按钮 + 标题，不再放 ✕
  	const backBar = document.createElement("div");
  	backBar.className = "mp-back-bar";
  	backBar.innerHTML = '<button type="button" class="mp-back-circle" aria-label="返回">‹</button>'
  		+ '<span class="mp-title">设置</span>';
  	Object.assign(backBar.style, {
  		display: "flex", flex: "0 0 auto", alignItems: "center", gap: "4px",
  		minHeight: "56px", padding: "8px 12px", boxSizing: "border-box",
  		borderBottom: "none", background: "transparent", position: "relative",
  	});
  	// 回退 CSS 规则：用 !important 压内联 padding，让出状态栏
  	/* 内联读取（原生就是用内联样式写的），避免为一行 padding 触发一次全量样式重算 */
  	const sysTop = (document.documentElement.style.getPropertyValue("--dsh-android-system-top") || "").trim() || "0px";
  	backBar.style.setProperty("padding-top", sysTop, "important");
  	const backBtn = backBar.querySelector(".mp-back-circle");
  	Object.assign(backBtn.style, {
  		width: "36px", height: "36px", borderRadius: "50%", background: "var(--dsw-alias-interactive-bg-hover, #f0f0f0)",
  		border: "none", cursor: "pointer", display: "inline-flex", alignItems: "center",
  		justifyContent: "center", fontSize: "20px", color: "#222", flex: "0 0 auto",
  	});
  	const titleSpan = backBar.querySelector(".mp-title");
  	Object.assign(titleSpan.style, {
  		position: "absolute", left: "50%", top: "50%", transform: "translate(-50%, -50%)",
  		maxWidth: "70%", fontSize: "17px", fontWeight: "600",
  		color: "var(--dsw-alias-label-primary, #222)", whiteSpace: "nowrap",
  		overflow: "hidden", textOverflow: "ellipsis", padding: "0 4px",
  	});
  	dialog.insertBefore(backBar, nav);
  	const closeDialog = () => {
  		const mask = dialog.parentElement?.querySelector(".VOzbGW_mask");
  		if (mask) mask.click();
  	};
  	// 返回按钮：一级页关闭设置，二级页回一级
  	const showList = () => {
  		titleSpan.textContent = "设置";
  		// 用 !important 压过 dsh-market 的
  		//   @media (max-width:560px){ [role=dialog]:has([data-dsh-market-root])>nav{display:none} }
  		// 市场组件（data-dsh-market-root）挂载后一直留在 dialog 里，:has() 只看 DOM
  		// 存在性不看可见性，返回列表页时普通内联 display 会被该规则覆盖 → 列表页空白。
  		nav.style.setProperty("display", "flex", "important");
  		if (content) content.style.display = "none";
  		applyNavStyle();
  	};
  	const showContent = (titleText) => {
  		const sectionTitle = content?.querySelector(".zGbnIq_title, h2");
  		titleSpan.textContent = titleText || (sectionTitle ? sectionTitle.textContent : "设置");
  		// 与 showList 对称：用 !important，确保能覆盖 showList 留下的 flex !important
  		nav.style.setProperty("display", "none", "important");
  		if (content) {
  			content.style.display = "flex";
  			content.style.flexDirection = "column";
  			content.style.flex = "1 1 0%";
  			content.style.minHeight = "0";
  			content.style.overflowY = "auto";
  		}
  		const options = content?.querySelector(".VOzbGW_options");
  		if (options) {
  			options.style.flex = "1 1 0%";
  			options.style.minHeight = "0";
  			options.style.overflowY = "auto";
  		}
  	};
  	// 设置页的「退一层」语义（二级 → 一级 → 关闭），返回按钮与**系统返回键**共用。
  	// 系统返回键由原生经 "window.__DSH_MOBILE_BACK__" 调进来，见 §12。
  	const backOneLevel = () => {
  		const atList = !content || content.style.display === "none";
  		if (atList) closeDialog();
  		else showList();
  	};
  	backBtn.addEventListener("click", backOneLevel);
  	// ⚠️ 用 WeakMap 而不是 DOM 属性存回调：DOM 属性只能存字符串，
  	//   而这里要存的是带闭包的函数（"content"/"closeDialog" 都在闭包里）。
  	mpDialogBack.set(dialog, backOneLevel);
  	// nav 竖向化 + 分类小标题 + 可滚动
  	const applyNavStyle = () => {
  		if (!isNarrow()) return;
  		nav.style.flexDirection = "column";
  		nav.style.alignItems = "stretch";
  		nav.style.overflowY = "auto";
  		nav.style.overflowX = "hidden";
  		nav.style.width = "100%";
  		nav.style.gap = "2px";
  		nav.style.padding = "10px";
  		nav.style.flex = "1 1 0%";
  		nav.style.minHeight = "0";
  		const navList = nav.querySelector(".VOzbGW_navList");
  		if (navList) {
  			navList.style.flex = "1 1 0%";
  			navList.style.minHeight = "0";
  			navList.style.overflowY = "auto";
  		}
  		if (navList && !navList.querySelector("[data-mp-group]")) {
  			const groups = [
  				{ label: "通用", items: ["通用设置"] },
  				{ label: "模型", items: ["模型"] },
  				{ label: "插件", items: ["插件"] },
  				{ label: "高级", items: ["Agent 预设", "快照", "附件缓存", "特权权限", "悬浮气泡"] },
  			];
  			for (const g of groups) {
  				const first = [...navList.querySelectorAll("button")].find((b) => g.items.includes((b.innerText || "").trim()));
  				if (!first) continue;
  				const label = document.createElement("div");
  				label.className = "mp-nav-group";
  				label.dataset.mpGroup = "1";
  				label.textContent = g.label;
  				label.style.cssText = "padding:14px 16px 4px;font-size:12px;font-weight:600;color:var(--dsw-alias-label-secondary,#888);";
  				navList.insertBefore(label, first);
  			}
  		}
  		Array.from(nav.children).forEach((c) => {
  			if (c.querySelector && c.querySelector("button")) {
  				c.style.flexDirection = "column";
  				c.style.overflowX = "hidden";
  				c.style.gap = "2px";
  				c.style.flex = "1 1 0%";
  				c.style.minWidth = "0";
  			}
  		});
  		nav.querySelectorAll("button").forEach((b) => {
  			b.style.width = "100%";
  			b.style.minHeight = "50px";
  			b.style.justifyContent = "flex-start";
  			b.style.padding = "0 16px";
  			b.style.borderRadius = "12px";
  			b.style.fontSize = "15px";
  			b.style.flex = "none";
  			b.style.textAlign = "left";
  			// 移动端一级列表不显示"当前项"高亮（官方 .VOzbGW_navCell.VOzbGW_active
  			// 的背景色块）：二级页是全屏的，nav 根本看不见，回到列表时这行残留的
  			// 色块只会让人误以为"刚才那下没退掉"（用户 2026-09-14 反馈）。
  			// ⚠️ 必须用内联样式：匹配设置 dialog 的 CSS 规则会把 dialog 推出屏幕
  			// （x=-301、关不掉），此处禁用 CSS 规则。内联样式优先级高于该类规则，
  			// 且官方 React 不管理 background 属性，重渲染不会把它冲掉。
  			b.style.background = "transparent";
  		});
  	};
  	const contentHeader = content?.querySelector(".VOzbGW_header");
  	if (contentHeader) contentHeader.style.display = "none";
  	nav.addEventListener("click", (e) => {
  		const btn = e.target.closest("button");
  		if (!btn) return;
  		const titleText = (btn.innerText || "").trim();
  		setTimeout(() => showContent(titleText || "设置"), 0);
  	});
  	// 立即应用，去掉 800ms 延迟（旧样式闪烁根因）
  	applyNavStyle();
  	showList();
  }

  return { setupSettingsDialog };
}
