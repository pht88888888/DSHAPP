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
| P3 引擎与依赖 | 资产/兼容/工具/权限文件策略/配置/探测/内存/隔离组件，窄端口注入；常量拆为 15 文件 | 起停、救援及降级状态仍由 EngineManager 保留；以最终真实输入与双 flavor 验证，不只按减行验收 |
| P4 其他宿主 | Browser tabs/control/capture/callback/mailbox/owner registry，bubble process/window/action/展示组件 | 双内核 WebHost destroy 终态与旧宿主回调门控正在收口；bubble 完整模块编译与最终生命周期证据待主集成 |
| P5 物理边界 | 11 个模块已注册并接 app 依赖；bubble 源码、三份 UI helpers、23 份资源、三份字符串资源与服务声明已迁移 | 注册不等于 compile passed；最终两 flavor 单测/lint/release 与 APK/Manifest 检查待记录 |
| P6 插件与资产 | 18 项 catalog、4 顶层/14 内部；新 plugins 目录、统一 tooling、生成资产/staging；六项手写脚本/协议迁移；native 三源码唯一落点 | 插件迁移前后运行文件对照和最终 APK 内容以主集成报告为准；9 类外部来源仍有重建输入缺口 |
| P7 工程收口 | 根说明、双语 README、规范与四 ADR、四操作分册，分层测试/发布文档 | 当前 owner/status 已更新；最终门禁数字、实际候选与设备 buildType 证据进入本轮生成报告，无发布动作 |

## 当前模块与装配

[settings.gradle.kts](../../settings.gradle.kts)已注册 `:app`、`:core:contracts`、`:core:logging`、`:runtime:termux`、`:runtime:engine`、`:platform:privilege`、`:web:contract`、`:web:host`、`:feature:browser`、`:feature:bubble`、`:bridge:transport` 共 11 模块。bubble 已完成物理接入，不再标 pending；独立模块编译和整体验收仍分别等待实际结果。

- Application 注入 EngineCommandMailboxes、EngineActivitySource、内核身份与配对操作；引擎不引用 app Service/具体命令桥。
- app 注入 RuntimeAppInfo，core/logging 不主动采集跨领域快照；三类诊断组装留在 app diagnostics，旧 Kotlin package 保留。
- MainActivity 装配 Activity/Bubble/Privilege actions；Activity 仍持 Window/Intent/权限 UI，EngineService 持通知与 WakeLock。
- Browser 领域独占 claim/receipt/recent-id/重启策略，共享 transport 只负责原子文件发布。
- BubbleController 持进程快照与弱动作口，FloatingBubble 持窗口资源，BubbleActionController 持动作状态与三个定时器；Activity 强持动作端口，hide 不清进程快照。
- Bubble 服务归 feature Manifest；ShizukuProvider 与 AdbPairingReceiver 归 privilege Manifest，权限保护、authority 和 action ID 保持兼容。
- RuntimePaths 当前只集中 DSH_HOME 相对路径，不声称已接管所有目录；用户 prefix/HOME/workspace/会话/插件状态不迁移。

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
