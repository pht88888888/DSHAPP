# 分步发布操作手册

## 入口与边界

发布入口为 [CLI](../../tools/release/cli.mjs)。旧的 [shell 入口](../../tools/release-server.sh)只转调它，不再运行 Gradle、查找网盘、上传或移动文件。无参数只显示帮助。

```bash
bash tools/release-server.sh
node tools/release/cli.mjs help
node --test tools/release/test/*.test.mjs
```

本仓库默认只提供本地 `source-record / inspect / prepare / check`。远程阶段要求显式注入 adapter，没有内置网盘凭据、自动发布或隐式构建。正式发布前由主构建流程准备两个已经签名的 release APK。

## 本地产物检查

要求 Node、Python 3、Android SDK build-tools 35.0.0；可用 `--sdk` 或 `ANDROID_HOME` 指定 SDK，也可分别指定 `--aapt`、`--apksigner`。

```bash
node tools/release/cli.mjs inspect --development --sdk /opt/android-sdk
```

默认读取：

- system/gecko 对应的 release APK。
- [插件清单](../../tools/plugin-components.json)和 `app/build/generated/plugin-components` 的 ZIP、manifest。
- [运行资产锁](../../catalogs/runtime-assets.lock.json)。开发模式下锁不存在可继续，但报告为 null；正式候选必须有锁。

`inspect` 使用真实 `aapt dump badging` 和 `apksigner verify --verbose --print-certs`，检查：

1. applicationId 分别为 `com.dsh.android`、`com.dsh.webview`，均为 15 字符，targetSdk 为 28。
2. 两包 versionCode 相同，gecko versionName 为 system versionName 加 `-gecko`，证书 SHA-256 相同。
3. 四个顶层出厂包；Android 聚合清单和包内 bundledDependencies 均为 14 项。
4. APK 中 ZIP/manifest 与指定生成目录逐字节一致，ZIP 文件闭包、大小、SHA-256、package/version 与 manifest 一致。
5. 每项运行资产的本地 bytes/SHA-256 与锁一致，APK 内 `apkEntry` 解压字节也与锁一致。
6. 记录 APK 摘要、真实工具版本、插件 revision/sourceDigest 和当前源码匹配情况。

`inspect` 总是标记 `sourceBinding=unverified-existing-artifact`。源码不匹配时报告 stale；这允许检查历史产物，**不证明历史 APK 包含当前修改**。APK 与生成资产不同、签名无效、运行资产不符等仍失败。

正式模式还拒绝 debuggable APK 和 Android Debug 签名证书。

## 构建前记录与候选生成

构建由上层独立执行，本 CLI 不启动 Gradle。构建前运行：

```bash
node tools/release/cli.mjs source-record --out build/release-input/source-record.json
```

记录 git commit、dirty 状态、可跟踪源码树摘要和时间。忽略的 build/node_modules 不进入摘要，不读取签名密钥、证书和本地凭据文件内容。输出不可覆盖，应为每次构建使用新路径。把输出放在已忽略的 build 目录，避免记录文件自身改变源码树。

构建完成并收集门禁报告后：

```bash
node tools/release/cli.mjs prepare \
  --out build/releases/candidate-115 \
  --source-record build/release-input/source-record.json \
  --gate-report build/reports/architecture/gates.json \
  --sdk /opt/android-sdk

node tools/release/cli.mjs check \
  --candidate build/releases/candidate-115 \
  --sdk /opt/android-sdk
```

`--gate-report` 可重复。报告为 `schemaVersion:1, checks:[{name,level,status,command,tests,skipped,evidence}]`。正式模式要求 L0/L1/L2/L3 均有真实通过记录；`not-run`、失败、缺输入或跳过都不能算通过。设备无法安全验收时如实记录 not-run，正式候选被阻断。

正式模式拒绝 dirty 工作树。显式 `--development` 可生成带 `DEVELOPMENT — NOT FOR FORMAL DISTRIBUTION` 标记的开发候选；仍要求 APK 插件与当前源码/生成资产一致。开发未提供 source-record 时标记 unverified，不会隐式把旧 APK 绑定到当前源码。

source-record 必须与当前源码一致，APK 修改时间必须晚于记录时间；这是对既有构建流程的来源检查，不能替代隔离构建器的完整证明。prepare 检查前后比较源码，遇到并发改动停止。不要在其他成员还修改源码时生成最终记录。

候选目录必须不存在，工具复制两个 APK，并以不可覆盖写入的方式生成 `release-manifest.json`。记录 APK/证书/版本/插件摘要、运行锁、源码状态、工具版本和门禁报告摘要。`check` 重验候选、当前输入及源码是否改变。候选不能原地修补，应重新生成。

## 远程 adapter 协议

adapter 是显式指定的本地 ESM 模块，导出：

```js
export async function createAdapter({ candidate }) {
  return {
    async list({ directory }) {
      // 仅返回已确认的文件身份；名称、大小和内容摘要必须真实可核查。
      return [{ name: 'DSH0.5.0.apk', bytes: 150804605, sha256: '<64位小写hex>' }];
    },
    async upload({ directory, file, name, bytes, sha256, operationId }) {
      // 不覆盖同名不同内容；相同operationId/内容可安全重试。
    },
    async move({ from, to, name, bytes, sha256, operationId }) {
      // 相同请求重试可恢复；禁止依据陈旧远端ID操作其他文件。
    },
  };
}
```

adapter 须完成远端身份与内容校验。若远端不提供 SHA-256，需通过受控校验机制确认；不能回填调用者期待摘要来假装核验。服务返回成功但结果未知时，下一次请求先读取真实状态。不允许同名覆盖、静默忽略失败或把部分成功当全部完成。adapter 自行处理凭据，凭据不能进入 manifest 或日志。

远程调用方式如下；这些命令会执行注入模块的动作，不属于默认本地流程：

```bash
node tools/release/cli.mjs publish --candidate build/releases/candidate-115 --adapter /path/adapter.mjs --sdk /opt/android-sdk
node tools/release/cli.mjs promote --candidate build/releases/candidate-115 --adapter /path/adapter.mjs --sdk /opt/android-sdk
node tools/release/cli.mjs archive --candidate build/releases/candidate-115 --adapter /path/adapter.mjs --sdk /opt/android-sdk
```

正式流程拒绝 dirty、开发或来源未绑定候选。仅隔离开发适配器测试可显式传 `--development`；标记不会被提升成正式候选。

## 状态机与重试

- publish：上传到 `jiulingShare/DSH APP/候选版本/<releaseId>`，分别核对两包；双包齐备才标记 published。
- promote：先保存当前最新版旧双包快照，再确认两新包均在候选或已提升位置，然后移动到 `jiulingShare/DSH APP/当前最新版`。旧包尚不归档。
- archive：仅新双包均确认在当前最新版后，把快照中的旧双包移到 `jiulingShare/DSH APP/历史版本`。最终回查当前仅有准确新双包、历史存在旧双包。

当前最新版在第一次提升前必须为空或是准确的一对旧双包。额外 APK、残缺双包和同名不同摘要会阻断；工具不会猜测并删除文件。

每次动作结果写入 `release-state.json`，包含 completed、旧包快照、阶段和错误。任何部分失败返回非零，保留上次完成阶段；重试原命令会先核对远端，再继续缺失动作。移动成功但响应丢失不会重复移动或丢失旧包归档信息。

同一候选用 `release-state.lock` 阻止并行发布。进程被强制杀死可能留下锁；先确认无进程使用该候选，再由维护者清除该候选的锁文件后重试，不能删除状态文件假装从头成功。

## 回退

源代码回退按独立变更处理。APK 默认通过原签名、更高 versionCode 的修复版本恢复，不能假设低 versionCode 可直接覆盖。市场插件由官方精确版本事务恢复，APK 回退不会自动覆盖用户 registry 插件。数据写入需要对应备份与兼容策略。

远程提升部分失败时优先重试完成；本工具不自动移动新包回旧目录，以免未知响应导致进一步破坏。归档失败不会伪装成发布全部完成。

## 本轮真实验收记录

- `node --test tools/release/test/*.test.mjs`：13 项通过、0 失败、0 跳过。覆盖双包齐备、提升先决条件、归档、上传/移动部分失败、响应丢失、幂等重试、同名冲突、开发/dirty 拒绝、不可变记录和门禁假绿阻断。
- `node tools/release/cli.mjs inspect --development --sdk /opt/android-sdk`：真实 aapt/apksigner 对现有 system/gecko release APK 检查通过；版本码 115，system 0.5.0、gecko 0.5.0-gecko，targetSdk 28，证书 SHA-256 为 `ef6afe33330839ed6d6739fb4d4bb84d358fd7bec8c1116c97309f92d9d3f385`。
- 上述检查对象为已存在的 APK；报告明确未绑定本轮源码，并列出 stale 源码项。完整报告位于 `/tmp/dsh-release-inspect.json`，属于本机临时验收证据。
- 同一真实检查使用运行资产锁再次执行通过：两包的 15 项运行资产均与本地锁的 bytes/SHA-256 一致，报告为 `/tmp/dsh-release-inspect-runtime.json`。
- 真实 CLI 拒绝验证通过：正式 prepare 在 dirty 工作树返回 1；开发 prepare 对旧 APK 返回 1，原因为 `android-bundle: 生成产物不对应当前源码 market-plugin/dsh-android-market/package.json`；未生成错误候选。兼容 shell 无参数返回 0 且仅显示帮助。
- 本轮未执行实际远程发布；远程动作验收全部通过离线 fake adapter。
