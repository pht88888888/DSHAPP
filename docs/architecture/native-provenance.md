# 原生源码、配方与来源记录

## 当前可维护源码

| 组件 | 规范源码 | 现有运行制品 | 已记录的构建参数 |
|---|---|---|---|
| link 转译层 | [linkcompat.c](../../native/linkcompat/linkcompat.c) | linkcompat.bin | `-shared -fPIC -O2 -Wall -ldl` |
| 写操作拦截库 | [boosandbox.c](../../native/sandbox/boosandbox.c) | boosandbox.bin | `-shared -fPIC -O2 -Wall -ldl` |
| runner | [boo-sandbox-run.c](../../native/sandbox/boo-sandbox-run.c) | boo-sandbox-run.bin | `-O2 -Wall` |

三份源码从原 jni 目录原样迁移，没有行为修改；主集成已逐字节比对并移除旧 jni 副本，运行资产锁的 source.location 已指向 native 源码，制品摘要保持原值。迁移前调查未发现 build-linkcompat/build-sandbox 脚本，实际可追溯命令来自[资产重建记录](../agents/regen-assets.md)。旧 keyinject 构建脚本不代表当前能力。

仓库根 [MIT 许可证](../../LICENSE)适用于这些仓库源码；配方记录许可证及源码摘要，不将本项目许可证推及外部 Termux、Node、引擎和原生依赖。

## 配方与工具入口

[原生配方锁](../../catalogs/native-recipes.lock.json)记录 schemaVersion、源码 SHA-256、许可证摘要、固定参数、ELF 符号契约、历史运行制品摘要及外部来源缺口。

固定目标为 NDK `27.0.12077973`、API 26、arm64-v8a、`aarch64-linux-android26-clang`，依据是原有文档中的实际命令。它固定后续重建输入，**不证明旧二进制由该精确编译器版本构建**。

基础检查无需 NDK、APK、运行二进制或网络：

```bash
node tools/check-native-recipes.mjs
node --test tools/test-native-recipes.mjs
```

检查源码和许可证摘要、配方字段、外部来源缺口及与运行资产锁的元数据一致性。只读取小型 JSON 和源码，不读取大资产。

已有准确 NDK 的环境可直接构建到新目录：

```bash
node native/build.mjs \
  --ndk /path/to/android-ndk-27.0.12077973 \
  --out-dir /tmp/dsh-native-new-build
```

[构建入口](../../native/build.mjs)验证 NDK source.properties 中精确版本，按锁的参数执行，不下载、不安装、不启动 Gradle、不回写运行 assets。输出只能是仓库 build 下或仓库外的新目录，已有目录拒绝覆盖。环境设置 SOURCE_DATE_EPOCH=0、LC_ALL=C；原参数不擅自追加或删除。

输出 ELF 必须是 little-endian ELF64 AArch64，并具有所需已定义全局符号。生成 native-build-report.json，记录真实命令、编译器版本、产物 SHA-256 与旧运行制品是否逐字节相同。构建与符号检查不能证明 Android 运行行为；字节不等不能报告旧制品已复现。

完整可重建还需要固定 NDK 分发包来源/完整性和构建环境。当前只固定 revision，不虚构未核对的 NDK tarball SHA。

## 外部资产来源与缺口

所有已观察本地制品摘要引用[运行资产锁](../../catalogs/runtime-assets.lock.json)。这些摘要是当前持有字节的锁，不等同上游下载完整性。

| 类别 | 已记录事实 | 仍缺少的重建输入 |
|---|---|---|
| Termux bootstrap | 上游 termux-packages、arm64 制品摘要 | 精确上游 revision、deb 清单/完整性、staging 配方 |
| Node | Termux 26.4.0-2、当前制品摘要 | 上游 deb 完整性、依赖集与打包配方 |
| 引擎 | 官方包 0.2.2-rc.1、归档摘要 | 精确 npm tarball integrity、依赖锁、原 staging 配方 |
| require-builtin native addon | 包 0.1.7、当前 ZIP 摘要 | 上游 tarball integrity、源码 revision、NDK/头文件与编译参数 |
| system native addon | 包 0.1.2、当前 ZIP 摘要 | 原始源码/头文件/编译与打包配方 |
| ripgrep | Android arm64 包 1.18.0、制品摘要 | 上游 tarball integrity、精确源码/编译链与封装配方 |
| sharp wasm | 0.35.5，历史记录含 emnapi/runtime 1.11.3 | 完整传递依赖和原始封装配方 |
| node-pty | 上游 microsoft/node-pty、当前 ZIP 摘要 | 精确源码 revision、node-addon-api、Node headers 完整性、完整命令/打包配方 |
| adb | Termux 上游、当前 ZIP 摘要 | 精确版本/revision、依赖和 staging 配方 |

node-pty 历史命令要求 Node 26.4.0 头文件、NDK C++17 和 `-static-libstdc++`，见[现有说明](../agents/node-pty.md)。现有记录不足以声称从零可重建，不能用一条未固定输入的命令替代来源恢复。

外部资产仍需完整传递许可证清单；不为这些未知项填写推测性版本或完整性。源码/配方恢复后单独更新锁，并在临时目录构建、比对、运行验收，最后才另行迁移运行资产。

## 已下线项目

keyinject 已由 gateway 路径替代。当前安装器只清理历史库并返回 null，不是 active APK 资产，也不是本轮必须补齐的“缺源码”发布项。旧脚本保留清理由主集成处理。

## 本轮验收

- 三份规范源码与旧 jni 源码逐字节相同，SHA-256 进入锁；未回写运行 assets。
- `node tools/check-native-recipes.mjs` 通过：3 项源码配方、9 项外部来源缺口，明确 Android build=not-run。
- `node --test tools/test-native-recipes.mjs`：13 项通过、0 失败、0 跳过。覆盖无大资产 fixture、源码漂移、路径逃逸、NDK/API/ABI 失配、共享库参数、符号契约、许可证、运行锁不一致、外部缺口、keyinject 下线和缺少 NDK 拒绝。
- 本机 NDK 环境变量未设置，SDK ndk/ndk-bundle 与常见安装目录不存在；只有主机 clang。因此本轮 Android 编译、ELF 产物验证和与历史二进制逐字节重现均未执行，不能据此声称重建成功。

旧 jni 重复源码已由主集成移除，锁内 migratedFrom 仅为迁移历史；实际构建以 native 为唯一来源。运行制品及其摘要没有因源码目录整理而替换。
