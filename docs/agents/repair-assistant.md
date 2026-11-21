# 修复助手：完整原版 DSH 加救援能力

本页区分**当前源码、局部主机证据、最终门禁与设备证据**。用户已明确要求保留原版 DSH 的界面、功能、插件、工具、模型设置和启动流程，只增加救援工具与专用预设；本轮不再维护裁剪运行时或一套新的按钮入口。应用级文件范围见下文；[完整提案](<../architecture/repair-assistant-proposal.md>)中的隔离试跑和多文件原子事务仍不是现状。

历史 `0.1.0` 是只读摘要阶段；`0.2.0` 裁剪版曾通过当时的 L1/L2，却在实际设备出现 `Failed to load plugins`：关闭原有服务后，官方聊天界面的 `fileUpload`、`sessions`、`uiWorkspace` 等依赖无法激活。`0.3.0` 恢复完整宿主后，设备又暴露救援预设 `row 24 names no plugin`：rc.5 在挂载前校验插件 `name` 必须是字符串，不能在这一位置用 `!!js` 动态表达式。旧 smoke 只确认 Host 启动，遗漏了实际预设挂载；直接调用 Host 对象还不能验证真实 Gateway，后者要求可见的 `typertRemote` 绑定。这些事实说明编译、独立插件激活和静态工具表均不等于可用的修复对话。旧报告与旧 APK 不作为本轮通过证据。

## 当前源码能力

- `:repair:assistant` 的身份仍是 `com.dsh.fixhelp`，源码版本 `0.5.3`（versionCode 11）、arm64、`minSdk=26`、`compileSdk=35`、`targetSdk=28`。其[构建定义](<../../repair/assistant/build.gradle.kts>)直接编译原版 app 的 Java/Kotlin、资源和主 Manifest，使用同一 `DshApplication`、`MainActivity`、`EngineManager`、System WebHost、全部 Android 功能模块和完整原版资产；只覆盖名称等身份资源并加入救援代码与资产。它不是 Gecko 内核版，不能拿 Gecko 大小差异评估裁剪。
- 原版启动与官方 Web 不等待目标 grant。打开助手即走原有启动进度、模型配置和聊天流程；原有预设、通用工具、设置、上传、工作区、插件管理、特权能力等保持可用。新增救援入口在原有设置中，原有启动覆盖层的救援按钮作原生后备，不再先显示旧按钮式 launcher。
- [Application 装配](<../../app/src/main/java/com/dsh/android/core/DshApplication.kt>)仅在助手身份注入可选的 [EngineLaunchAdditions](<../../runtime/engine/src/main/java/com/dsh/android/engine/EngineHostPorts.kt>)；[RepairAssistantAssets](<../../app/src/main/java/com/dsh/android/repair/RepairAssistantAssets.kt>)复制 APK-owned 救援资产、建立私有信箱并追加 patch/environment，不替换原版 profile、用户配置或正常启动 owner。
- [追加 patch](<../../repair/assistant/src/main/assets/repair-agent/repair.patch.yml>)新增设置中的「原 DSH 连接」与「修复模式」（preset id `repair`），不禁用原 Host/Client/预设。助手新任务的配置默认值是 `repair`，但用户已保存的 `selectedDefault` 继续优先，不覆盖用户选择。修复预设保留原版常用工具并额外挂载[十三个专用工具](<../../repair/assistant/src/main/assets/repair-agent/tools/index.js>)：`status`、`evidence`、`list_files`、`read_file`、`stage_edit`、`stage_patch`、`stage_file`、`list_changes`、`apply_changes`、`rollback`、`validate_target`、`launch_target`、`verify_target`；工具插件使用字面字符串路径，满足 rc.5 挂载前校验。过去的三 namespace ConfigEditor、repair-only 工具集合与全局 hardening overlay 不再是当前安全策略。
- [Host 救援入口](<../../repair/assistant/src/main/assets/repair-agent/rescue-entry/index.js>)为所有模式追加身份说明：当前运行的是独立助手 `com.dsh.fixhelp`，`com.dsh.android` / `com.dsh.webview` 是外部原应用；截图指向原应用时不能误认为助手自身损坏。普通模式没有救援工具时，应引导进入「原 DSH 连接 → 开始修复」，不能编造工具或用普通文件/shell 工具访问目标。身份提示会读取有界的原生连接投影，但不是安全隔离。
- [连接设置页](<../../repair/assistant/src/main/assets/repair-agent/rescue-entry/client.js>)显示当前目标、连接阶段及结果；流程为「选择目标并连接 → 目标原生授权 → 自动返回续接 → 开始修复」。选择器展示安装版本与不可连接原因，进度和结果留在设置页，不再连续弹状态对话框。「开始修复」通过官方会话服务创建 `agentPreset: repair` 的真实新会话，打开该会话并关闭设置，而不是只改界面标签。连接未成立时不能开始修复；主动断开后仍可普通聊天。
- [RepairHostController](<../../repair/assistant/src/main/java/com/dsh/android/repair/assistant/RepairHostController.kt>)只拥有目标选择、握手、授权与救援桥，不拥有或门控 DSH 引擎。授权返回可能早于 `onResume`，待恢复后重查状态，不丢弃首次结果。`current` 信箱 epoch 固定一个连接；已连接时创建的 Agent 立即捕获绑定，未连接时创建的 Agent 可在首次有效重试时取得绑定。一旦绑定，不会随目标切换或重授权自动改绑；切换后必须新建对话。旧请求、旧对话和旧确认不能自动跨目标。旧裁剪 runtime/launcher 已移入 [legacy-retired](<../../repair/legacy-retired/>)，不参与当前编译。
- [原生启动层布局](<../../app/src/main/res/layout/activity_main.xml>)将完整错误内容放入可滚动区域，把导出、导入、日志和维护授权固定在底部独立操作区，不能再被长错误栈挤出屏幕；System/Gecko 原应用与助手复用这一布局。原应用的[维护授权页](<../../app/src/main/java/com/dsh/android/repair/RepairAuthorizationActivity.kt>)还提供明确的「打开修复助手」动作，打开助手本身不等于给予诊断授权。
- 助手依然有独立安装 UID、私有目录、HOME、DSH_HOME、模型凭据、会话和工作区，不自动导入原 App 的模型配置。应用级救援授权后，原 App 配置也可按需通过目标 broker 读取，故必须避免无关密钥和个人内容发送给模型。共享源码不等于共享运行数据；两个目标的原包身份与数据不迁移。
- [Binder 契约](<../../repair/contract/src/main/java/com/dsh/android/repair/contract/RepairProtocol.kt>)为版本 4（目标与助手必须配套更新，旧协议明确不兼容）；Agent JSON 用 UTF-8 ByteArray 传输，编码后 payload 最多 768 KiB，另限嵌套深度，上限包含 JSON 转义。目标/grant/原生确认 token 由 native 绑定，不是模型参数。目标 [Provider](<../../app/src/main/java/com/dsh/android/repair/RepairProvider.kt>)、授权页和变更确认页在 `:repair`，不要求目标主 Node/Web 可用。

## 0.5.0：一次维修授权，后台先查，最后用户验证界面

用户已确认 0.4.1 暂存修正实际成功；本版针对重复审批、旧错误页复用、看不到连接状态和手工截图回传的体验，不能把该历史成功当作本版生命周期已验收。

- 原生授权开始一个维修任务，先写持久启动闸，再停止已核验目标进程。一次授权覆盖本次任务内的文件修改（含凭据、会话/数据库、未知数据目录与删除）、停止、重启和验证，不再逐候选弹窗；用户可随时结束维修撤销授权。每个被修改文件在提交前保留原件备份，可按事务 rollback。单路径发布已准入后撤销禁止新请求，进行中的发布须完成或恢复后才解除闸门。
- [RepairTaskCoordinator](<../../app/src/main/java/com/dsh/android/repair/RepairTaskCoordinator.kt>)持有内存授权和异步 operation/attempt 状态；持久任务只代表启动禁令，不恢复执行权限。失效、重启或 repair 进程死亡进入安全恢复，不自动启动原程序。通知与原生维修页面可请求结束；有未决 journal 或未知写进程则保持恢复状态，不谎称结束。
- `validate_target` 的 `syntax` 只检查已应用候选的受支持 JSON/JS，未支持项明确未检查；`engine` 用真实原 profile 在后台启动、HTTP健康/短稳定观察、停止并确认退出后收集当前 attempt 安全证据。它不是隔离沙箱：真实 profile 可能写数据、访问网络，字节备份不能逆转所有副作用。后台日志独立，不覆盖正常启动统计。
- 当前修改版本的后台引擎检查通过后，助手在对话里说明已修复，并请用户回原 DSH 点「重启并验证」；用户重启后的实际启动是最终验收。模型 `launch_target` 只返回需要用户操作，不启动 Activity。用户按钮触发的 UI attempt（新 WebHost、引擎 ready、官方 UI-ready 与首帧）保留为可选附加证据，不再是报告完成的前置条件；失败仍回传助手。
- 后台服务送达不等于已启动引擎。许可拒绝也写 task/attempt 绑定失败证据，真实失败或已取得许可的主进程死亡立即返回；无15秒独立启动硬截止，运行环境准备与引擎检查共用5分钟总兜底，期间显示阶段，不能把等待上限当作每轮固定耗时。验证服务自身进入非 sticky 前台通知，记录运行环境/引擎启动/HTTP/稳定观察/停止阶段；Node 的真实 waitFor 退出唤醒启动等待，不依赖后代持有 stdout 的 EOF。仍不能将协程 timeout 视作同步 Binder/OS 调用均可中断的保证。
- 助手新建空白会话页在官方 `conversation.hero.options`（工作区/预设同行右侧）显示连接状态；聊天标题栏和设置入口保留，共用详情和当前阶段消息。健康冷启动由官方导航创建/复用空白会话；初始化失败且未建立 session 时，该官方 slot 不渲染，设置页仍是后备。
- 助手聊天标题栏和设置页显示目标、连接/任务/验证阶段；终态和首次诊断经官方 `inject/followup` 进入绑定修复会话，按 task/attempt/evidence sequence 去重。轮询不续期，不把旧 boot 摘要当本次验证成功。自动证据有界、脱敏，保留安全错误类型/相对位置，未知正文、URL和源码不自动透传。
- 授权页改为 Material 原生主题，按严格四颜色和对比度方案同步视觉；非法缓存整份回退。主题不是授权。界面保持 secure、防遮挡和普通文本预览。原敏感确认页保留在包内备查，但不再作为提交通道的门禁。

本版只授权实现、主机门禁与三 APK 交付，不自动安装、上传、发布或变更手机。完整 Android 生命周期、UI像素、厂商后台限制与双内核真实修复仍须独立设备验收。

## 0.5.2：一次授权覆盖修改，模型只报进度，原 APP 一键重启验收

用户明确要求按更短的流程走：原 DSH 授权 → 打开修复助手再次授权并连接 → 首页维修入口点「开始恢复」→ 会话自动开始 → 模型直接提交修改并在对话说明完成 → 用户回到原 DSH 点「重启并验证」。因为授权本身可随时撤回，不再需要逐次人工审批。

- 去掉逐候选原生确认门禁：[RepairTaskCoordinator](<../../app/src/main/java/com/dsh/android/repair/RepairTaskCoordinator.kt>) 的 `apply_changes`/`rollback` 直接以本次任务授权提交，敏感/未知路径不再返回 `NATIVE_CONFIRMATION_REQUIRED`。一次性票据、确认页与 `approvalBinding` 仍编译保留但不再触发；撤销授权、单路径发布准入和启动闸门语义不变。
- 原 DSH 的维修闸页改为「返回修复助手 / 重启并验证 / 关闭」，文字说明重启即可验收。[RepairAuthorizationActivity](<../../app/src/main/java/com/dsh/android/repair/RepairAuthorizationActivity.kt>) 增加 `EXTRA_RESTART` 模式：确认后调用 `end`（保留已应用修改、撤销授权），等待任务进入 complete，再以 `NEW_TASK|CLEAR_TASK` 正常启动原 DSH；存在未决事务或进程未停止时报告并保持维修状态，不谎称重启。
- 助手侧删除未再触发的审批请求/回传分支，`launch_target` 提示改为回原 DSH 点「重启并验证」；工具描述与修复预设同步：`queued` 只代表已排入提交，engine 通过即可说明已修复，不再要求用户点其它验证按钮，也不得声称 UI 已通过。连接状态面板同时去掉「打开原 DSH 验证界面」按钮，阶段标签与脚注改为「检查通过，可回原 DSH 重启验证」。
- 这一版授权覆盖任务内的敏感文件修改，风险边界由「一次任务授权 + 每个文件原生备份 + 可回退事务 + 随时结束维修」承担；仍不能把连接授权理解为无限期、跨任务的修改许可。

## 0.4.1：读取正常但所有候选暂存失败的修正

用户实际升级0.4.0后，`status`、授权、`read_file`、分片/完整摘要、`list_files`均正常，而`stage_edit/stage_patch`跨别名和文件大小全部`io_error`，`list_changes`无可见候选。不能归因为连接或授权过期。无候选只证明没有可列的完整journal，并不能证明没有创建过临时目录。此前生产adapter主机仿真仅在外部根拒绝hardlink、未模拟私有目录拒绝hardlink，故没有覆盖这一差异。

对比已通过普通App UID scratch测试的0.3.2，0.4.0暂存新增两个共同系统调用依赖：空间预检`statvfs(procfd路径)`及私有备份/候选的`link`发布。0.4.1用持有的目录FD调用公开`fstatvfs`，并把hardlink拒绝后的O_EXCL预约/身份检查/rename兼容路径从仅external推广至全部可信根；仅接受EACCES/EPERM/EOPNOTSUPP/ENOSYS进入兼容，EEXIST或未知IO失败仍拒绝、不覆盖。目录fsync不静默降级，预约窗口崩溃仍可能保守conflict。

目标`status`增加`file_broker_revision: app_files_041`，让模型能验证实际目标文件实现而不是只看助手版本。失败返回固定实现标记、阶段、IO操作和errno（若有），不返回原始异常路径、内容、栈或凭据；阶段区分授权存储、容量预检、候选目录、原件备份、候选写入/摘要、journal与清理。清理失败不覆盖首次失败的阶段/errno，未知异常仍保守`io_error`。模型必须保存诊断并停止未改变条件的写入重试。

**证据边界：**私有hardlink拒绝是可复现的实现缺陷与修复对象，不是已经取得设备errno的根因结论。此轮没有擅自连接/修改手机或安装探针；最终门禁、APK及真实设备验收另行记录。不能以主机注入拒绝的红绿回归代替用户设备暂存已恢复。

## 0.3.2：实际修复失败后的文件通道修正

用户在 `0.3.1` 实际修复原 System 时，授权、`status/evidence` 正常，而 `read_file/list_changes` 反复 `io_error`，整条修复链路未能开始。此前真机只验证连接、摘要和交互，遗漏了真实文件读取/事务；主机事务用 `HostIo` 执行，生产 `AndroidRepairFileIo` 只编译未执行，不能用这些结果宣称“可修复”。

[Android 文件代理](<../../app/src/main/java/com/dsh/android/repair/files/AndroidRepairFileIo.kt>)旧实现从 `/` 逐级以 `O_RDONLY` 打开公共祖先；Android 普通 App 即使可穿行这些目录，也可能无权读取，造成所有 broker 操作在检查自身 filesDir 时共同失败。当前改为直接打开来自 `Context.filesDir` 的可信 canonical 锚，只对锚内部逐级 `O_NOFOLLOW` 并固定描述符；根 stat 使用该描述符的 `fstat`，拒绝锚外、`..` 与链接替身，未放宽用户路径或确认权限。无底层错误证据时不把失败武断归因于某厂商 SELinux。

此修正位于**原应用的 native broker**，需更新对应 System/Gecko 目标 APK，仅更新助手不能修好已安装旧目标；助手 `0.3.2` 另补通道失败说明，禁止重复无变化的失败请求、翻助手自身文件代替目标、凭行号断言 BOM/shebang 内容或把重新授权当作通用修复办法。更新会使旧 grant 失效，须正常重新授权并新建修复会话。

[新增代理回归](<../../app/src/test/AndroidRepairFileIo.test.mjs>)执行真实生产代理与 broker，窄 OS fixture 拒绝公共祖先读取并保留真实主机文件/描述符操作；它不模拟 Android SELinux，也不是设备或真实模型自主修复证据。

另在既有 Android 10 测试设备以独立普通 App UID、真实 Android `Os/PFD` 执行同一份生产代理与 broker，仅触碰独立包自己的 scratch 引擎文件：公共 `/data` 的旧方式 open 被拒绝；新实现的根 fstat、read+hash、list、stage+preview（原件不变）、apply+目录 fsync、rollback、叶子链接/祖先链接/锚外拒绝共 10 项全部通过，0 跳过。探针已卸载，未更新或修改原 System/Gecko/助手。源码摘要与探针/报告绑定保存在本机记录中。

这仅完成**隔离普通 App UID 的真实文件事务**，没有经过跨包 Provider 授权、用户原生批准、模型生成修改、原目标启动/新 boot 验证。代码修正、主机通过、隔离设备事务和完整 AI 修复验收必须分别报告；最后一项仍未通过，不能宣称修复助手已通过实际修复验收。

## 权限边界：不再宣称封闭工具沙箱

完整复用意味着原版 shell、文件、插件与用户授予的 Root/Shizuku/ADB 等能力仍可用。**十三工具表只描述新增救援 broker，不是整个 Agent 的工具限制，也不能约束原版通用工具的所有行为。** 普通助手 UID 不能直接读取另一 App 的私有目录；新增跨包读取/编辑通过目标 broker 的身份、grant、范围和一次性确认检查。但是用户另行授予 root/ADB 等权限后，原版能力可能越过这条 broker，不能承诺所有目标改动都必然经过它或把救援预设当作强制安全隔离。同 UID 通用工具还可读取助手私有信箱并仿造请求，信箱并不隔离这些工具。每 Agent/epoch 绑定用于避免旧对话或旧请求无意跨目标，不是抵御恶意同 UID 代码的强制边界；提示词约束也不是权限边界。

目标各自声明 `signature` 权限。Provider 核对 Android callingPackage、Binder UID 唯一包归属及证书；原生授权页不接受 Intent 自报身份。同签名其它包也不自动获准。已交给助手或模型的数据不能撤回。

## 授权、通知与真实活动

新增 broker 使用 **30 分钟空闲租约**，grant 绑定设备启动次数和双方安装身份；只有仍有效的同一 grant 可续期。撤销、到期、重启、更新/重装或新授权替换使旧 grant 失效。

[RepairSessionService](<../../repair/assistant/src/main/java/com/dsh/android/repair/assistant/RepairSessionService.kt>)是用户发起的非 sticky 前台会话，有持续通知与停止操作；**连接租约由 FGS 持有，不由 Activity 持有**。Activity 暂停、销毁或旋转只解绑 UI，不主动撤销连接；恢复时可接回进程内同一目标、grant 与 epoch。进程死亡不把持久化状态当成仍有效的连接，也不把 grant 写入状态文件。

[RepairConnectionState](<../../repair/assistant/src/main/java/com/dsh/android/repair/assistant/RepairConnectionState.kt>)原子投影 `disconnected/checking/authorizing/connecting/connected/disconnecting/error/expired`。设置页只读取有界、脱敏的目标/标签/消息/阶段/版本号；Web 不获得 grant。固定控制信箱只接收零目标参数的授权或断开意图，目标必须经过原生固定选择器和目标确认。旧 binding 的停止回调不能覆盖新连接状态。

真实用户交互、已绑定 Agent 运行/流式输出/步骤或工具工作才形成活动；空页面、轮询和定时器不续租。明确断开、通知停止、失效或空闲到期会结束救援桥并反映状态，**不结束正常 DSH 聊天**。Android 后台保活、通知禁用/停止与长时间推理续期仍须设备验证，不以 FGS 声明代替。

## 新增 broker 的证据、编辑与确认边界

- [FailureEvidence](<../../app/src/main/java/com/dsh/android/repair/RepairFailureEvidence.kt>)自动摘要提供当前 boot ID、引擎状态、正式界面标志及有界错误类别/引擎相对栈位置，覆盖所有引擎依赖，不再限定单包。自动摘要不复制原始异常正文或源码；需要日志/配置时由 Agent 明确按需读取，而不是自动收集秘密。
- [RepairFileAccess](<../../app/src/main/java/com/dsh/android/repair/RepairFileAccess.kt>)仅从目标 Context 建立可信虚拟根：`app`（credential data，包括 files/shared_prefs/databases/cache/no_backup）、可用的 `device_app`，以及各卷 `external_files`、`external_cache`、`external_media`（后续卷后缀 `_2` 等）。不暴露模型指定的绝对路径。旧 `engine/...` 仍映射 `app/files/engine/...`。`list_files` 返回实际根与分页目录，不承诺未挂载/OS 拒绝的存储可用。
- [RepairFileBroker](<../../app/src/main/java/com/dsh/android/repair/files/RepairFileBroker.kt>)不再按文件扩展名、包名或文本类型白名单筛选。普通单链接文件可读写二进制；`read_file` 返回完整 SHA-256/大小及有界 UTF-8/base64 分段，`stage_edit` 小份整体替换，`stage_patch` 按原 SHA-256、字节偏移、删除长度及插入内容流式生成大文件候选。**128 KiB 是每段传输上限，不再是目标文件总大小限制**；旧无 offset/length 全读请求遇大文件会提示分段。备份、hash、候选、发布均流式，不把整个大文件载入内存。
- `stage_file` 暂存创建文件、删除文件、新建目录、删除空目录，支持受控权限位（显式文件权限必须保留 owner-read，目录必须 owner-read/search，避免批准后无法校验回退）；父目录须真实存在，不隐式递归建/删。权限由目标 UID 和 OS 约束，不扩大为 root；应用专属外部目录的模式由平台管理，不接受显式 chmod，也不把合成 mode 当可还原的 POSIX 权限。仅精确保护救援授权 XML/备份、引擎排他锁（包括读取，避免 POSIX 锁被关 fd 释放）及事务存储。拒绝绝对路径、越界、路径链接、非普通文件和多硬链接；不把拒绝链接描述为所有路径必然可改。
- 所有 stage 不动目标文件；apply/rollback 在本任务授权下异步执行，不再逐文件弹窗。每个事务先写原件备份并校验完整 SHA-256，`rollback` 只恢复该事务。[可选确认页](<../../app/src/main/java/com/dsh/android/repair/RepairChangeApprovalActivity.kt>)与一次性票据仍编译保留，当前流程不再触发；`queued` 只代表已排入提交。
- 提交/回退须有效 grant、原摘要与[引擎排他锁](<../../runtime/engine/src/main/java/com/dsh/android/engine/EngineRepairLock.kt>)。正常启动从 start 准备至 Node `waitFor` 持锁；另经[静止检查](<../../app/src/main/java/com/dsh/android/repair/RepairEngineQuiescence.kt>)确认主进程不存在，同 UID 没有 Node/engine 命令行，未知 `/proc` 状态拒绝。原生一次性确认可请求窄范围 SIGTERM：先扫描全部相关进程，再仅关闭已核验 UID/exe/argv/PID/启动时间的精确目标主进程或可信 Node + engine 脚本；允许同包已知 Android 路径别名，主进程先停，最多等 2 秒。提示请求、终端及未保存操作可能中断；不 forceStop/SIGKILL，不碰无关 Node，失败不覆盖、候选保留。纯 Kotlin 选择策略的主机测试不是实际 OS 信号证据。
- journal 保留原件和候选；当前是**单路径事务**（不是多文件原子提交）。同 grant 内兼容旧 schema1 候选的完整性读取与回退，但双方 APK 更新使旧 grant 安装身份失效；新 grant 不能自动列出或回退旧备份，也没有独立历史恢复入口，不能承诺随时跨授权回退。每 grant 最多16候选、最多32个 grant 存储分区，空间预检保留安全余量；没有自动删除历史备份或授权绕过，达到存储上限会明确拒绝。
- 外部 emulated 存储不支持 hardlink 时，新建采用排他的空路径预约、inode 复核后 rename；原有文件不会因新建被覆盖，但在预约与 rename 之间进程死亡可能保留空预约并判 conflict。mkdir 在记录新 inode 前崩溃同样保守报 conflict，不宣称所有中断都自动可回退。fsync/存储错误不会伪报已成功；发布错误后必须查询事务状态，不能盲重试。外部存储的真实设备 FUSE/SELinux/持久化语义仍须设备验收。
- 应用后仍是「尚未验证」；`validate_target` 分层后台检查，`launch_target` 不启动前台。engine 通过后助手在对话里说明已修复，并请用户回原 DSH 点「重启并验证」；该按钮结束维修（保留已应用修改、撤销授权）后正常启动原 DSH，作为最终验收。`prepare_ui`/UI attempt 仅作可选附加，`verify_target` 的 `verified:true` 仍只表示本轮 UI attempt 到位，不能据此声称界面或业务全部通过。

应用私有目录内的 profile、插件、Termux/Node 二进制、runner、原生库副本、凭据、会话和工作区均不再因类别被排除。但 OS 只读的已安装 APK/dex/系统 native 库必须通过正式签名 APK 更新，不宣称能就地改安装区；不读取其它 App/系统数据。代码/配置/数据库文件的语义正确性和多文件一致性不因字节事务而自动保证，数据库及其 WAL/SHM 不构成一组原子事务，须静止和验证，不能声称通用数据库逻辑修复已验收。

## 本地构建和证据状态

```bash
bash gradlew --offline :app:assembleSystemRelease :app:assembleGeckoRelease \
  :app:testSystemDebugUnitTest :app:testGeckoDebugUnitTest \
  :app:lintSystemDebug :app:lintGeckoDebug \
  :repair:assistant:assembleRelease :repair:assistant:lintDebug --console=plain
node tools/check-repair-artifacts.mjs
```

`0.3.0` 曾有完整三 release 构建及 29 个原版 assets/lib 条目逐字节保留的记录（不把构建生成的 dexopt/baseline profile 当作固定资产），但随后真机发现修复预设不可用，不能把该历史门禁当作本轮 `0.3.1` 已通过。当前产物大小、签名、摘要和资产保留结论必须来自冻结源码后的新报告。

本轮局部主机验证已有生产控制器的首次授权、返回先于恢复、取消、Activity 重建、断开、错误/过期反馈和状态脱敏等检查；这些在窄 Android stubs 上执行，不是设备/像素测试。[真实 rc.5 profile smoke](<../../repair/assistant/test/profile-smoke.mjs>)现在必须确认所有原预设和 `repair` 均未 broken、官方会话服务创建真实 Agent 且挂载修复工具，并经实际 Client Remote 与 Host Gateway 调用零参数连接服务。对缺少 `typertRemote` 的旧对象形式，应在真实 Gateway 上验证拒绝；只直接调用 `ctx.androidRescue` 不能替代这一证据。

`0.5.0` 源码与集成门禁在进行中，最终计数和结论以源码冻结后的外部报告为准。用户在 `0.3.2` 实际经 native 批准修复首个入口，重启暴露第二个 `dsh-app-boot` 损坏文件；旧单包/128 KiB 人为限制挡住后续修复。此轮按用户要求扩大到应用级，不自动安装、上传或改设备数据。传输测速和设备入口仅记入不入库的本机说明。**目前不宣称整套真机验收、长时间租约或当前应用级 AI 自主修复通过。** L0 既有 mobile 定时器清理和 npm pack 失败仍需如实报告；确定 shebang fixture 不能冒充 AI 自主修复。

产物位置：[System release APK](<../../app/build/outputs/apk/system/release/app-system-release.apk>)、[Gecko release APK](<../../app/build/outputs/apk/gecko/release/app-gecko-release.apk>)、[助手 release APK](<../../repair/assistant/build/outputs/apk/release/assistant-release.apk>)。不卸载、不清数据；历史上传不授权本轮新上传。源码冻结后不为回填结果改文档，最终报告保存在仓库外或忽略目录。

## 不能由编译替代的设备验收

1. 覆盖旧助手及全新安装都直接进入原版正常启动/聊天/模型配置；检查完整原工具、上传、工作区、插件和预设，未连接目标时也能使用。救援设置入口与原生后备入口可发现，首次点击有反馈，授权返回不丢失。
2. 模拟原目标主初始化、引擎和 Web 故障，维护握手不启动其主进程或覆盖现场；两个目标都测试实际授权和证据读取。
3. 真实 AI 阅读实际错误、生成候选，经确认 apply、启动原目标并核对新 boot/engine/Web；不是手写映射。测拒绝、冲突、无关 Node、PID 复用、未知 `/proc`、空间不足、链接越界、各事务边界进程死亡及回退；确认中断提示与失败不强杀。
4. 30 分钟无活动到期、长流式推理续期、空页面不续期、通知禁用/停止、进程重建、重启、双方更新和授权替换；断开不结束普通对话。切目标或重绑后旧 Agent/旧请求不能获得新目标授权。
5. 验证同 grant 回退与新修改冲突，明确旧备份跨 grant 不可达。测错签名、同签错误包、共享 UID、旧协议、API 26/27、Android 10/新系统及强停/禁用/未解锁情况。原版 root/ADB 等用户额外权限单独说明，不宣传成 broker 强制沙箱。
