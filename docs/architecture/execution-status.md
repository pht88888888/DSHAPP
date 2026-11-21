# 架构实施台账

## 执行范围与判定

用户已批准实施[完整重组方案](reorganization-plan.md)并自行验收。正式分发、外部 npm 发布和独立远程仓库生产改动不在本轮自动执行范围；不自动 commit。最终应用身份、正式签名、targetSdk、用户数据格式与运行路径保持。

实施结构与 owner 已落地；本台账保存 source-record 前的实施快照和已确认局部证据。**全链路验收以本轮生成报告为准**：主集成在忽略目录 `build/verification/` 生成分层报告及 `build/verification/final-acceptance.json`，其中实际源码绑定、APK/签名/buildType、设备证据、计数/跳过与 passed/failed/not-run 才是最终结果。下文的待验项表示文档冻结时的状态，不覆盖随后真实生成的报告；报告尚未生成时不能预写通过。首次编译、fixture 结果和历史 APK 不能代替最终证据。结构与 owner 图见[当前架构](current-architecture.md)，输入和报告规则见[测试手册](../runbooks/testing.md)。

sourceSnapshot 包含本台账与架构文档。source-record 后冻结源码文档，实际验收证据只写入忽略的 build 输出或仓库外临时目录，避免修改文档使本轮源码记录自我失配。最终报告是生成交付物，路径在此说明，不要求它在源码检出时存在。

## 初始基线

- 起点为运行依赖首次提取完成：EngineManager 6965、MainActivity 5469、FloatingBubble 5426、AiBrowserView 2903 行。此处为调查时数字，不作为当前行数或新锁上限。
- 初始 Gradle 工程仅 `:app`；工作区已有 Android bundle、市场更新、插件构建、运行依赖提取及未提交变更，全部保留，不 reset、不自动提交。
- 批准方案保存设计基线；后续事实更正仅明确调查结论变化，不能把方案目标改写成已完成。

## 阶段进展

| 阶段 | 已落盘成果 | 冻结时待验事项与持续约束 |
|---|---|---|
| P0 基线与契约 | 当前 owner/API/资源/回退表、桥契约、运行资产锁、远程与原生来源审计 | 补最终源码/产物绑定和 L0–L3 实际报告；来源锁不代表全量源码可重建 |
| P1 入口治理 | 显式工具输入、模块/源码策略守卫、分层门禁 runner、发布 CLI 与 runbooks | 源码策略最终逐文件锁及全仓结果由主集成收口；不以局部 fixture 冒充生产基线通过 |
| P2 低耦合拆分 | Activity 的权限/下载/闪烁/恢复/IME/back/overlay/frame/pill 组件与领域 bridge dispatcher，转录 renderer | 下载并发和阻塞取消正在补真实慢 HTTP 场景；新增 App JUnit 未完成，不预记通过 |
| P3 引擎与依赖 | 资产/兼容/工具/权限文件策略/配置/探测/内存/隔离组件，窄端口注入；常量拆为 15 文件 | 正常起停仍由 EngineManager 保留；旧救援与自动恢复已拆除；以最终真实输入与双 flavor 验证，不只按减行验收 |
| P4 其他宿主 | Browser tabs/control/capture/callback/mailbox/owner registry，bubble process/window/action/展示组件 | 双内核 WebHost destroy 终态与旧宿主回调门控正在收口；bubble 完整模块编译与最终生命周期证据待主集成 |
| P5 物理边界 | 11 个模块已注册并接 app 依赖；bubble 源码、三份 UI helpers、23 份资源、三份字符串资源与服务声明已迁移 | 注册不等于 compile passed；最终两 flavor 单测/lint/release 与 APK/Manifest 检查待记录 |
| P6 插件与资产 | 18 项 catalog、4 顶层/14 内部；新 plugins 目录、统一 tooling、生成资产/staging；六项手写脚本/协议迁移；native 三源码唯一落点 | 插件迁移前后运行文件对照和最终 APK 内容以主集成报告为准；9 类外部来源仍有重建输入缺口 |
| P7 工程收口 | 根说明、双语 README、规范与四 ADR、四操作分册，分层测试/发布文档 | 当前 owner/status 已更新；最终门禁数字、实际候选与设备 buildType 证据进入本轮生成报告，无发布动作 |

## 当前模块与装配

[settings.gradle.kts](../../settings.gradle.kts)已注册 `:app`、`:core:contracts`、`:core:logging`、`:runtime:termux`、`:runtime:engine`、`:platform:privilege`、`:web:contract`、`:web:host`、`:feature:browser`、`:feature:bubble`、`:bridge:transport` 以及 `:repair:contract`、`:repair:assistant`，共 13 模块。bubble 已完成物理接入，不再标 pending；独立模块编译和整体验收仍分别等待实际结果。

- Application 注入 EngineCommandMailboxes、内核身份与配对操作；引擎不引用 app Service/具体命令桥。
- app 注入 RuntimeAppInfo，core/logging 不主动采集跨领域快照；三类诊断组装留在 app diagnostics，旧 Kotlin package 保留。
- MainActivity 装配 Activity/Bubble/Privilege actions；Activity 仍持 Window/Intent/权限 UI，EngineService 持通知与 WakeLock。
- Browser 领域独占 claim/receipt/recent-id/重启策略，共享 transport 只负责原子文件发布。
- BubbleController 持进程快照与弱动作口，FloatingBubble 持窗口资源，BubbleActionController 持动作状态与三个定时器；Activity 强持动作端口，hide 不清进程快照。
- Bubble 服务归 feature Manifest；ShizukuProvider 与 AdbPairingReceiver 归 privilege Manifest，权限保护、authority 和 action ID 保持兼容。
- RuntimePaths 当前只集中 DSH_HOME 相对路径，不声称已接管所有目录；用户 prefix/HOME/workspace/会话/插件状态不迁移。

## 独立修复助手：当前源码与验收边界

当前用户要求是**完整原版 DSH 加救援能力**，不是裁剪版或新按钮 launcher。助手 `com.dsh.fixhelp` 源码 `0.4.1`（targetSdk 28/arm64）通过[构建定义](<../../repair/assistant/build.gradle.kts>)直接复用原 app Java/Kotlin、资源、主 Manifest、全部模块与 System 资产，走同一 DshApplication/MainActivity/EngineManager。可选 launch additions 只追加救援 patch/environment；原模型设置、Host/Client、预设、shell/文件/插件工具和原版特权能力均保留。独立 UID、HOME、DSH_HOME、模型凭据、会话和工作区不共享原目标数据。设置中新增救援入口，原启动覆盖层有原生后备入口；普通启动/聊天不等待 grant。旧裁剪 runtime/launcher 移入 legacy-retired，不再编译。

此前 `0.1.0` 是只读摘要阶段；`0.2.0` 裁剪版虽有旧 L1/L2 通过报告，用户设备实际出现 Web `Failed to load plugins`，因为裁掉 fileUpload 等依赖导致 sessions/uiWorkspace 等无法激活。旧静态或局部激活检查未覆盖完整 Web，不能沿用为本轮证据。新增十二工具通过 Binder v3 与 native 绑定目标/grant/一次性确认；但其受限范围只是 broker 边界，不是整个 Agent 沙箱。完整原版工具尤其用户授予的 root/ADB 等能力可能绕过 broker，不能宣称所有跨包修改都强制经过确认；普通 UID 不因复制源码获得另一 App 的私有目录权限。

`0.3.2` 的实际用户修复已通过文件通道/原生批准修正入口，后续却被我们的单包/128 KiB 人为范围挡住 `dsh-app-boot`，不能归因于 Android 权限。`0.4.0` 提供应用级虚拟根、目录分页、UTF-8/base64 分段、流式全 SHA-256/备份、任意大文件局部字节补丁及文件/空目录创建删除候选，128 KiB 仅为内容片段传输上限。包括配置、会话、插件、数据库、私有 native/Termux 文件，不再按包/扩展名排除；精确保留授权、排他锁、事务存储不可编辑。OS 只读安装 APK/dex/native 区仍须正式签名更新，链接/硬链接明确拒绝，不宣称所有对象必然可改。apply/rollback 需目标原生一次性确认、有效 grant、原摘要及引擎排他；还要求目标主进程不存在，同 UID `/proc` 扫描无 Node/engine 命令行，未知状态 fail closed，以防父进程死亡后的孤儿 Node。正常引擎锁覆盖整个 start 至 Node `waitFor` 退出，不能用 stdout EOF 释放。原生一次性确认可同时请求窄范围 SIGTERM 已核验的同 UID 精确主进程 argv 或精确 Node 可执行文件/目标引擎参数，发送前重查 PID/启动身份，告知请求、终端及未保存内容会中断；不使用 forceStop/SIGKILL，不动无关 Node，失败不提交。该新增辅助仍须最终重跑和设备验证。journal 保存原件和候选，当前是单文件事务。新 grant 不自动开放旧 grant 的备份；多文件原子事务、数据库语义恢复和隔离自动试跑仍未实现，纳入文件范围不代表这些领域的逻辑修复已验收。

授权改为 30 分钟空闲租约：真实用户交互或已绑定 Agent 活动才形成续租依据，轮询/页面存在/定时器本身不算活动；前台服务有持续通知和停止操作，不以 sticky 自动恢复授权。停止、到期、重启及双方安装身份变化须拒绝旧 grant。

用户实际0.4.0读/list正常、全部stage失败，已证实该版没有通过完整修复验收。0.4.1新增私有hardlink拒绝兼容及fstatvfs、固定阶段/errno脱敏诊断与目标实现标记，保留O_EXCL无覆盖和目录fsync要求；主机注入拒绝已形成红绿回归，不代表取得设备真实errno或实际恢复。详细边界见[0.4.1修正](<../agents/repair-assistant.md#041读取正常但所有候选暂存失败的修正>)。

**此前完整原版接入已有中间三 release 构建；当前0.4.1故障修正的最终绑定门禁、完整 Web smoke 和设备修复结论仍待新报告。** 中间助手约 137,769,103 bytes，对应 System 137,708,664 bytes，差约 60 KB；29 个原 assets/lib 条目逐字节相同，不把构建生成的 dexopt/baseline profile 作为固定资产。这不是最新资产或最终 APK 数字。原生流在 Android 窄 stubs 上执行生产逻辑的局部场景通过，含首次点击与授权返回早于恢复；准确新计数看最终报告，不是设备或像素证据。绑定 epoch 与每 Agent 状态防止无意的旧请求跨目标，仍需设备验收；同 UID 通用工具能查看私有信箱并仿造请求，不能把此绑定描述成恶意同 UID 代码隔离。原 broker/锁/进程策略局部证据及确定 shebang fixture 只证明各自范围，不是 AI 自主修复。L0 mobile 定时器及 npm pack 既有失败仍须保留，不宣称全绿。新的[追加层](<../../repair/assistant/src/main/assets/repair-agent/repair.patch.yml>)不再关闭原服务；旧三 namespace 设置保护与 repair-only 工具限制已不适用。最终状态从主集成仓库外报告读取，冻结后不回填改源码。[完整提案](<repair-assistant-proposal.md>)与[操作和设备待验项](<../agents/repair-assistant.md>)分别保留目标与现状；本轮未安装、清数据或新上传，历史上传授权不自动覆盖本轮。

## 已有局部证据

下列数字来自本轮实际执行记录或组件负责人的已执行结果，只代表对应范围；未把不同轮次合并成一次全量运行。

| 范围 | 已执行结果 | 能证明与不能证明 |
|---|---|---|
| 引擎常量 | 213 个 Kotlin 运行值迁移前后逐字节相同，0 跳过；合并 SHA-256 为 `14f1b917049fa1f4cc39dae8cf45cadafa2014d3cc3c90068c1e43442c29c533` | 同 package 常量提取保持运行字节；不代表全部引擎行为或 L1 已通过 |
| Activity 状态/桥 | 29 个真实 Kotlin 行为场景通过；桥契约 13 项、47 方法、0 跳过；权限队列 owner/pending 关闭与重入 4 场景通过 | 覆盖对应组件与桥；新增下载 5 场景和 App JUnit 尚在编写/验证，不能计入 |
| Browser 原职责拆分 | 22/22 局部真实测试通过，0 跳过 | tab/control/capture/callback/owner 行为；不含正在收口的双内核 WebHost 任务 |
| transport + browser mailbox | 14/14 局部真实测试通过，0 跳过；Browser 独立编译通过，保留 4 条既有 WebSettings 弃用警告 | 共享发布和领域事务；不代替两 flavor app 编译或设备运行 |
| Bubble 基础与新增场景 | 前一轮 8 个 Node 用例 / 58 个 Kotlin 场景通过、0 跳过；随后 startup 定向 1 个 Node / 2 个 Kotlin 场景通过、0 跳过 | 登记合计 9 Node / 60 Kotlin 场景，不声称一次全量跑完。源码直接编译使用窄队列/动效 fixture，不依赖 app/build；迁移后的正式模块编译待主集成 |
| 源码策略检查器 | 15 项 fixture 行为测试通过，0 跳过 | 检查器接受/拒绝契约；最终生产大文件锁与全仓执行另验 |
| 原生配方 | 3 份源码配方和 9 类外部缺口检查通过；13 项测试通过、0 跳过 | 配方/摘要/拒绝行为；本机缺 NDK，Android build、ELF 验证及旧制品字节复现均 not-run |
| 发布 CLI | 13 项离线测试通过；历史 APK 检查观察到 15 项资产，dirty/旧 APK prepare 拒绝行为已验证 | CLI 的本地状态与拒绝规则；历史 APK 是 stale/unverified，不是本轮候选，无真实上传/发布 |
| 手写运行资产 | 五脚本语法（UI probe 包装函数表达式）和桥 JSON 解析通过；桥契约 13 项/47 方法/0 跳过；迁移源 SHA 不变 | 六项源码迁移与既有资产名兼容；不等于 Gradle staging 或新 APK 已通过 |
| task15 文档 | 14 份文档、149 个本地链接有效；后续资产迁移文档 4 份、34 链接有效 | 对应时间点的标题/路径；不是构建或运行验收 |

常量验证入口见[运行值检查](../../app/src/test/engine-constants-values.mjs)，其输入通过显式 Kotlin runtime 选择；原生与远程证据分别见[原生来源](native-provenance.md)、[远程来源](remote-plugin-provenance.md)。CLI 与完整门禁的报告格式见[发布手册](../runbooks/release.md)、[测试手册](../runbooks/testing.md)。

## 冻结时编译记录与最终门禁职责

下表记录源码文档冻结时已掌握的结果及验收要求；各层最终状态读取本轮生成报告，不在 source-record 后回填此表。

| 范围 | 冻结时记录 | 最终报告须包含 |
|---|---|---|
| 局部 Gradle 编译 | 主集成已确认 browser/transport 编译通过；App internal 可见性集成错误已修复 | 不把首次 compile 当最终通过；其余独立模块及双 flavor 结果待主集成记录 |
| L0 | 未记录最终整层通过 | 完成最后 owner 收口、源码策略锁和依赖后生成新报告；过渡运行的失败不得改写为通过 |
| L1 | 未记录最终整层通过 | 显式 engine package/archive、runtime lock 与 JDK/Kotlin 输入；真实契约/补丁/组件结果 |
| L2 | 未记录最终整层通过 | 正式证书签名的 SystemRelease 与 GeckoRelease、两 flavor 新鲜 JUnit/lint/APK 与资产/插件逐文件证据 |
| L3 | 未记录通过 | 独立设备身份、候选 SHA、实际 buildType/签名、场景和原始证据；不靠静态报告自称设备已运行 |

设备方案需与 L2 正式发行候选分开记录：现存 system debug 安装签名与正式证书不同；主集成准备新的 SystemDebug 与 GeckoRelease 做明确 buildType 的设备测试。必须分别记录实际安装/测试 APK、签名与源码绑定，不能写成“两个 release 均已设备验收”。不为测试默认卸载或清空已有用户数据；签名/设备替换决策按主集成的明确方案执行。

## 未决事项与回退

1. **下载与双内核生命周期**：下载取消的阻塞 IO 关闭、并发临时文件/提交；system/gecko destroy 终态与旧 owner 回调仍在收口，等待对应真实用例与最终集成。
2. **生产源码策略锁**：最终大文件行数由主集成确认后建立锁；[检查器](../../tools/check-source-policy.mjs)及[行为测试](../../tools/test-source-policy.mjs)已落盘，锁缺失应失败。本台账不链接尚未建立的锁，不猜阈值。
3. **完整来源重建**：3 份规范 native 源码已在 native 唯一落点，9 类外部输入仍缺完整重建链。keyinject 已由 gateway 替代并退休，不列 active 缺源码项。
4. **远程来源差异**：已核对 npm mobile 1.2.105 / desktop 1.0.56；同版本独立源码仍有 8 个运行文件差异、两个 gitHead 不可解析。旧 App 副本 reference-only，保留特有 pair-server/历史测试，不直接覆盖生产仓库。
5. **最终设备与候选**：由主集成将实际报告和数字写入本轮生成交付物，不回填已冻结的源码文档。缺输入应 not-run/非零；零测试、skip、旧 JUnit/APK 或只有退出码 0 不能标通过。

回退单元与资源 owner 已写入[当前架构](current-architecture.md)。模块迁移需同步回退注册/依赖/源码/接线；插件需同步 catalog/source/builder/staging；运行数据只使用既有 staged 导入/备份/隔离恢复，禁止通过 reset、卸载、清数据或替换签名掩盖失败。

## 集成纪律

- 各负责人独占写域；主集成统一处理共享接口、Gradle、Manifest、源码基线与最终报告。
- 组件负责人提供已执行用例、计数/skip、输入与限制，不并行运行共享 Gradle或安装资产；主集成统一执行两个 flavor。
- 维护源码、生成产物、APK 与设备证据分别绑定来源；本轮不执行真实云发布、不替换独立远程仓库生产代码。
- source-record 后不再修改源码文档；最终结果与原始证据写入忽略的 `build/verification/` 或仓库外临时目录，不以减行、模块注册或局部测试将全阶段标成完成。
