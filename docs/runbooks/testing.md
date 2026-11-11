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

`node tools/check-source-policy.mjs` 直接扫描固定 11 个模块的生产 Kotlin 源码，不漏掉 app 之外的目录；排除 build、Gradle 缓存、单测/设备测试与 fixture。非历史例外的手写 `.kt` 上限为 1000 行，缺失锁或基线文件明确失败，不能把缺文件计作零行。现有编号式“修复 N”注释禁令继续生效。

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

不会将 `NO-SOURCE`、任务退出码 0、旧测试 XML 或旧 APK 当作本次通过。两个 flavor 各自必须有新鲜、非零且零跳过的 JUnit 结果，lint 不得有 Error/Fatal。正式 APK 必须存在有效签名、既有 15 字符身份、targetSdk 28、正确版本配对、相同证书与插件内容，且运行资产符合锁。

可用 `--system /absolute/system.apk --gecko /absolute/gecko.apk --generated-dir /absolute/generated-plugin-components --build-tools 35.0.0` 指定真实输出；APK 必须由本次任务生成。签名配置不足或构建缓存/工具缺失会失败，不自动退回 debug 包。

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

上例只是格式说明，不是可用设备报告。必须覆盖以下八个场景，每项都有真实命令和原始证据：`cold-start`、`keep-alive`、`web-permissions-system`、`web-permissions-gecko`、`recovery`、`bubble-dispose`、`browser-dispose`、`upgrade-market-state`。源码身份需与当前检出一致，摘要计算使用 `tools/release/inspect.mjs` 的 `sourceSnapshot`；验收报告存于仓库外以避免自引用。

设备报告证明范围以其原始记录为准。结构校验器无法独立证明人工记录的真实性，维护者必须核对实际设备结果。门禁不会生成成功设备记录、伪造序列号或通过本机单元 fixture 冒充 L3。

## 报告与后续发布

报告结构为 `schemaVersion: 1` 和 `checks: [{name, level, status, command, tests, skipped, evidence}]`，附源码快照和时间。Node 用例数从 TAP 汇总读取，Android 用例数从 JUnit XML 读取；非测试静态命令的 `tests: 1` 表示一次检查执行，不声称内部断言数。失败也保留命令原始输出和能取得的计数。

把报告连同 `.evidence/`、设备原始记录及实际 APK 一起归档。发布前的源码记录、不可变候选、双包和正式门禁校验见[发布操作手册](release.md)。L0/L1/L2/L3 的当前实际结果应写入[实施状态](../architecture/execution-status.md)，不能从本手册中的命令示例推断已通过。
