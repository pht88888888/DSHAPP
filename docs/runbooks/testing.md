# 测试与门禁操作手册

门禁入口为 `node tools/run-gates.mjs --level LEVEL --out /绝对路径/报告.json`。每次只执行一层；不串行启动下一层，不安装 App，不启动设备，不上传或发布。输出位置必须在源码目录之外，已有报告不能覆盖。每条检查都记录命令、真实计数、跳过数和带 SHA-256 的原始日志引用。

| 层级 | 必须具备的输入 | 实际检查 |
|---|---|---|
| L0 | Node 24、锁定 npm 依赖 | 物理模块边界、守卫行为、架构与双内核桥、市场官方源码样本、移动插件、组件真实 npm pack、发布流程拒绝行为、远程来源离线锁 |
| L1 | 显式真实引擎包/归档、运行资产锁、JDK 17、Kotlin 2.0.21 | 资产逐字节摘要、已安装/出厂两套官方契约、真实 Kotlin 补丁和引擎组件行为 |
| L2 | 显式 JDK 17、Android SDK、锁定运行资产、正式签名 | 双 flavor 单测/lint/release；读取新生成 JUnit/lint/APK，并核对身份、签名、运行资产和插件逐文件内容 |
| L3 | 独立设备运行报告及原始证据、两个实际测试 APK | 验证设备身份、源码绑定、双包摘要、必需场景、计数、零跳过及原始证据完整性；此命令不驱动设备 |

缺输入为 `not-run`，断言/命令失败为 `failed`，两者均返回非零。零测试、跳过、陈旧 JUnit/APK、仅声明通过而没有日志都不能变成 `passed`。正式候选必须向发布 CLI 提供完整 L0–L3 报告；测试 fixture 产生的报告不能代替设备验收。

## L0：干净检出与 PR

```bash
npm ci --prefix tools --ignore-scripts --no-audit --no-fund
npm ci --prefix plugins/android/components/dsh-android-market --ignore-scripts --no-audit --no-fund
node tools/run-gates.mjs --level L0 --out /tmp/dsh-gates-L0.json
```

`tools/package-lock.json` 锁定 esbuild；市场包的 lock 锁定 zod。L0 不读取全局 DSH、隐式 Kotlin 编译器或 Android 大资产。市场测试读取 `contracts/engine/` 的原始官方源码样本并校验摘要，保留官方 RemoteInfo/协议/RPC 校验。真实输入模式仅属于 L1。

CI 上传报告及同名 `.evidence/` 目录，包括失败输出。CI 的敏感文件和文档任务仍单独执行。L0 门禁报告本身不代表 Android 编译或设备已通过。

单独调试模块守卫：

```bash
node tools/check-module-boundaries.mjs
node --test tools/test-module-boundaries.mjs
```

守卫读取 Gradle 实际注册模块、依赖和 Kotlin/Java 源声明。旧 Kotlin package 可以保留，提供者按真实文件所属模块定位；import、别名、全限定引用、同包引用、生成 R/BuildConfig 均使用此索引。新增模块或边需要修改显式许可矩阵并经审阅；模块缺失、循环、未声明直接依赖、反向引用均失败。此静态检查补充编译，不代替 Kotlin 类型解析或反射运行行为。

## 源体积与历史例外

`node tools/check-source-policy.mjs` 直接扫描注册的 13 个模块的生产 Kotlin 源码，不漏掉 app 之外的目录；排除 build、Gradle 缓存、单测/设备测试与 fixture。非历史例外的手写 `.kt` 上限为 1000 行，缺失锁或基线文件明确失败，不能把缺文件计作零行。现有编号式“修复 N”注释禁令继续生效。

历史超线文件由 `catalogs/source-policy.lock.json` 逐文件记录 `{path,maxLines,owner,reason}`，说明真实状态所有权或原生字面量等原因。基线以结构迁移完成后的实际行数为初始值，缩短文件不要求同次立刻更新锁，后续可下调；不能为过检查抬高上限。

CI 通过 `DSH_SOURCE_POLICY_BASE_REF` 传入 PR 基准/推送前的精确 commit，并保留 Git 历史；也可手动用 `--base-ref` 指定 40 位 commit。旧提交已有策略锁时，检查器拒绝增加历史例外或上调旧阈值，允许缩小/移除例外。旧提交尚无策略锁时允许首次建立审阅后的基线。直接本地检查不传基准时，仅检查当前锁与当前源码，输出会说明未做历史比较。

守卫行为测试：`node --test tools/test-source-policy.mjs`。它只生成临时小型源码 fixture，不修改生产基线；最终当前文件清单及是否通过以真实检查输出为准。

## L1：真实引擎与 Kotlin

先自行准备真实文件和已安装编译器，不让脚本猜全局 DSH 或下载工具。下列变量值须替换为本机明确输入：

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export KOTLIN_COMPILER_LIB=/absolute/path/to/kotlin-2.0.21/compiler-lib
export DSH_ENGINE_PACKAGE=/absolute/path/to/installed/@deepseek-ai/dsh/package.json
export DSH_ENGINE_ARCHIVE=/absolute/path/to/locked/engine.bin
node tools/run-gates.mjs --level L1 --runtime-lock /absolute/path/to/checkout/catalogs/runtime-assets.lock.json --out /tmp/dsh-gates-L1.json
```

也可显式提供 `KOTLIN_COMPILER_CLASSPATH`，配合运行时解析器所需的 `KOTLIN_STDLIB_JAR`。编译器输入解析规则见 `tools/kotlin-test-runtime.mjs`，门禁本层要求显式选中输入。归档摘要必须等于锁中 engine 项，所有 active 运行资产也需符合锁。来源缺口仍须披露；摘要一致不等于拥有完整重建配方。

市场真实引擎模式同时读取已安装包与出厂归档，并拒绝契约漂移。缺少任一输入时，不回退到 L0 样本。

## L2：Android 实际任务与产物

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
node tools/run-gates.mjs --level L2 --sdk /absolute/path/to/android-sdk --runtime-lock /absolute/path/to/checkout/catalogs/runtime-assets.lock.json --out /tmp/dsh-gates-L2.json
```

默认通过 `bash gradlew` 执行。若本机已有与工具链锁完全同版的 Gradle，可在运行门禁前显式设置 `DSH_GRADLE_BINARY=/absolute/path/to/existing/gradle`。指定值必须是绝对路径且为现存普通文件；门禁运行其 `--version`，要求版本精确等于 `catalogs/toolchain.lock.json` 的 `tools.gradle`，并归档工具文件与工具链锁的摘要引用和版本原始输出。路径、执行或版本校验失败会阻止 Android 任务，不回退其他工具；不设置该变量时保持 Wrapper 入口。

两种入口都执行完整的 `--offline --no-daemon --rerun-tasks`，任务为：

- `:app:testSystemDebugUnitTest`、`:app:testGeckoDebugUnitTest`
- `:app:lintSystemDebug`、`:app:lintGeckoDebug`
- `:app:assembleSystemRelease`、`:app:assembleGeckoRelease`
- `:repair:assistant:assembleRelease`、`:repair:assistant:lintDebug`，并检查三个新 APK 的维护通道清单和同一证书

不会将 `NO-SOURCE`、任务退出码 0、旧测试 XML 或旧 APK 当作本次通过。两个 flavor 各自必须有新鲜、非零且零跳过的 JUnit 结果，lint 不得有 Error/Fatal。正式 APK 必须存在有效签名、既有 15 字符身份、targetSdk 28、正确版本配对、相同证书与插件内容，且运行资产符合锁。

可用 `--system /absolute/system.apk --gecko /absolute/gecko.apk --generated-dir /absolute/generated-plugin-components --build-tools 35.0.0` 指定真实输出；APK 必须由本次任务生成。签名配置不足或构建缓存/工具缺失会失败，不自动退回 debug 包。

独立修复助手当前源码 `0.4.1` 是**完整原版 DSH 加应用级救援**：直接编译原 app 源码、资源和主 Manifest，复用 System 宿主、全部模块/资产、正常启动、模型设置、原预设和通用工具，只追加救援设置入口、预设/十二工具与 native 桥。旧裁剪版 `0.2.0` 有过本地门禁通过但实际 Web 依赖失败，不能把其报告或假工具表当作当前可用证据。L1 证据投影、应用级根/流式文件 broker 等实际 Kotlin 测试显式提供 `ENGINE_TEST_JSON_JAR`；真实 rc.5 检查须验证追加后原 Host/Client/预设仍可激活、Web 所需 fileUpload/sessions/uiWorkspace 服务与完整 boot、参数/信箱及绑定 epoch 的拒绝路径，不能只 grep 未禁用。授权覆盖 30 分钟空闲、同一有效 grant 续期、撤销/替换/重启/安装身份变化；真实活动/通知另需设备证据。

0.4.0用户实测读/list正常而全部stage失败，不能归因断连。0.4.1新增私有hardlink拒绝的生产adapter红绿回归（当前16场景），包括EACCES拒绝下真实appboot大文件stage/apply/rollback、四种兼容errno、EEXIST/EIO不覆盖、O_EXCL预约冲突及FD无泄漏；空间检查使用持有目录FD的fstatvfs。另有broker17场景，其中覆盖storage_preflight/original_backup/candidate_write/journal_publish固定阶段与errno、清理失败不覆盖原始异常及脱敏。主机注入拒绝只是复现条件，不是设备实际errno证据；真实目标status须有`file_broker_revision: app_files_041`，失败返回`diagnostic`固定标签，禁止把无候选视为绝无临时目录。新增修正需要目标native与助手配套更新，更新导致旧grant失效。最终新门禁和设备结果仍另行报告。

L2 助手产物须核对 `com.dsh.fixhelp`/targetSdk 28/arm64、原 MainActivity launcher/DshApplication/EngineService、完整原版模块组件和权限（含用户另授 Shizuku/ADB 等原能力）、29 个原版 assets/lib 字节一致及追加资产、目标 `:repair` 原生确认页、三个 APK 同一正式证书。不再要求移除 Shizuku/ADB 或私有新 Agent Activity——那些是旧裁剪架构断言。[APK 检查器](<../../tools/check-repair-artifacts.mjs>)应按 applicationId 规范化比较完整组件/权限；AGP 自动生成 dexopt/baseline profile 可随编译变化，不能拿它当固定原版资产。中间三 release 构建及资产检查不代替最新源码最终 L1/L2。中间助手约 137,769,103 bytes、System 137,708,664 bytes，差约 60 KB；最终大小/摘要看新报告。L0 既有 mobile 定时器和 npm pack 失败保留，不宣称全绿。

[repair patch](<../../repair/assistant/src/main/assets/repair-agent/repair.patch.yml>)只追加救援，不再有三 namespace 设置保护或全局封闭工具限制。原模型设置/ConfigEditor 与 shell/文件/插件工具保持可用。**安全报告必须区分 broker 与完整 Agent**：同 UID 通用工具可读助手私有信箱并仿造请求，不能声称信箱对这些工具隔离；用户另授 root/ADB 等原能力也可能绕过 broker。普通助手 UID 不可直接读取目标私有目录，跨包 broker 自身仍须 native 身份、grant、范围和确认检查。每 Agent/epoch 绑定旨在拦截无意的旧对话/旧请求跨目标，不是防恶意同 UID 代码的强制安全边界。生产授权流主机测试使用 Android 窄 stubs，包含首次点击与授权返回先于恢复；不证明实际设备 UI、通知或真实网络模型保存。

必须另验应用级根/分页目录、每段 128 KiB（不是整文件上限）、任意大文件流式全摘要与字节补丁、UTF-8/base64、私有配置/插件/二进制范围、新建/删除/空目录回退与原摘要约束、符号链接与越界、原生一次性确认、整个 start/Node `waitFor` 期间的引擎排他、主进程仍在或同 UID 孤儿 Node 的拒绝、`/proc` 未知状态 fail closed、原生一次性确认后窄范围 SIGTERM 的 UID/精确 argv/可执行文件/参数及 PID 启动身份复核、无关 Node 不受影响、未保存请求/终端中断警告、关闭失败不覆盖且不自动 forceStop/SIGKILL、事务边界中断/空间不足/冲突及回退；新 grant 不应访问旧 grant 备份。当前原生策略 harness 含 1 个 Node 驱动/19 个真实 Kotlin 场景（含全引擎依赖错误定位）、零跳过的局部记录；真实 Node `--check` 对损坏/修正 shebang 的 fixture 不等于 AI 自主定位编辑或 Android 修复，最终门禁必须准确保留这种证明范围。profile、通用插件及私有 native/lib/二进制已纳入同一文件范围，但链接/硬链接及 OS 只读安装区不可就地修改，后者须签名更新。多文件原子事务、数据库/WAL 逻辑修复和隔离自动试跑未实现，不能用字节回退用例宣称这些能力。跨包权限、独立引擎、真实 AI 编辑与验证、长期有活动续租/无活动到期、通知停止以及故障隔离仍需[专门设备验收](<../agents/repair-assistant.md#不能由编译替代的设备验收>)。既有八场景、旧裁剪 APK/只读联调和本轮中间构建不能自动证明新增链路；最终源码 release/门禁、完整 Web smoke 以本轮仓库外报告为准，实际模型修复和设备结果尚无通过证据。本轮未安装或新上传，不作发布结论；冻结后不回填源码文档改变摘要。

## L3：独立设备证据

先由真实设备验收流程生成独立 JSON 和原始日志/记录，再导入：

```bash
node tools/run-gates.mjs --level L3 --device-evidence /absolute/path/to/device-report.json --out /tmp/dsh-gates-L3.json
```

设备报告格式：

```json
{
  "schemaVersion": 1,
  "kind": "dsh-device-evidence",
  "createdAt": "设备验收完成的 ISO 时间",
  "source": { "commit": "40位真实commit", "treeDigest": "64位源码摘要" },
  "device": { "serial": "真实设备序列号", "model": "真实型号", "androidVersion": "真实系统版本" },
  "apks": [
    { "flavor": "system", "path": "/absolute/tested-system.apk", "sha256": "实际APK摘要" },
    { "flavor": "gecko", "path": "/absolute/tested-gecko.apk", "sha256": "实际APK摘要" }
  ],
  "checks": [
    {
      "name": "cold-start",
      "level": "L3",
      "status": "passed",
      "command": "实际执行的命令/验收入口",
      "tests": 1,
      "skipped": 0,
      "evidence": [{ "path": "/absolute/raw.log", "bytes": 123, "sha256": "实际原始文件摘要" }]
    }
  ]
}
```

上例只是格式说明，不是可用设备报告。必须覆盖以下八个场景，每项都有真实命令和原始证据：`cold-start`、`keep-alive`、`web-permissions-system`、`web-permissions-gecko`、`manual-failure-actions`、`bubble-dispose`、`browser-dispose`、`upgrade-market-state`。源码身份需与当前检出一致，摘要计算使用 `tools/release/inspect.mjs` 的 `sourceSnapshot`；验收报告存于仓库外以避免自引用。

`manual-failure-actions`：在两个 flavor 的测试环境分别记录引擎启动失败/意外退出、Web 渲染进程丢失；确认无自动重启、无自动重载、不改插件和用户数据。验证原生日志及备份入口可用，用户手动重试引擎或重建页面后才继续。

设备报告证明范围以其原始记录为准。结构校验器无法独立证明人工记录的真实性，维护者必须核对实际设备结果。门禁不会生成成功设备记录、伪造序列号或通过本机单元 fixture 冒充 L3。

## 报告与后续发布

报告结构为 `schemaVersion: 1` 和 `checks: [{name, level, status, command, tests, skipped, evidence}]`，附源码快照和时间。Node 用例数从 TAP 汇总读取，Android 用例数从 JUnit XML 读取；非测试静态命令的 `tests: 1` 表示一次检查执行，不声称内部断言数。失败也保留命令原始输出和能取得的计数。

把报告连同 `.evidence/`、设备原始记录及实际 APK 一起归档。发布前的源码记录、不可变候选、双包和正式门禁校验见[发布操作手册](release.md)。L0/L1/L2/L3 的当前实际结果应写入[实施状态](../architecture/execution-status.md)，不能从本手册中的命令示例推断已通过。
