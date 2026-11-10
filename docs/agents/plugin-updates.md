# 内置插件与市场更新

内置插件随 APK 出厂可用，不依赖联网首次安装。维护者发布 npm 新版本后，用户在市场直接点更新；保留启用状态。兼容的 Host 与页面插件分别由官方 HMR 应用，只有接口明确返回需要重启时才提示重启。页面加载失败、等待同步、需要刷新与 Host 已应用分开显示。

## 组件来源与产物

`tools/plugin-components.json` 是 APK 插件资产的唯一组件清单，记录源码、zip、入口和客户端文件。`tools/build-plugin-components.mjs` 对客户端统一编译到 Chrome 73，生成固定顺序、时间与权限的 zip，以及包含包版本、源码摘要、工具版本、逐文件摘要的 manifest。

```bash
node tools/build-plugin-components.mjs build --out-dir app/build/generated/plugin-components
node tools/build-plugin-components.mjs check --out-dir app/build/generated/plugin-components
node tools/build-plugin-components.mjs pack-check
node --test tools/test-plugin-components.mjs
```

Gradle 的 `buildBuiltinPluginAssets` 预构建任务生成并校验整组插件后安装资产；保留现有软链对象。不要再手工压缩插件目录，也不要只改 Kotlin 版本戳。运行时以组件 manifest 的内容摘要作为出厂版本判据。

插件 npm 发布内容必须包含 `package.json`、`dsh.bundle` 指向的 patch 与实际入口。发布操作独立执行；`pack-check` 仅检查真实 tarball 内容，不发布包。

## 版本与用户状态归属

出厂包保存在 `$DSH_HOME/node_modules/<package>`，位于 profile 的 pnpm workspace 之外。profile 通过 `dependencies` 的 `link:<绝对路径>` 与 `dsh.profile.bundles` 登记，官方管理器负责启用状态。包声明 `dsh.android.builtinId`，与组件清单的 ID 对应。

登记标记只表示“已经登记过”，不能随版本变化；用户关闭或卸载后，重启与 APK 升级都不得重新启用。既有 registry 依赖优先于出厂链接，APK 不能覆盖它。更新失败时保留独立的旧版恢复结果，不能将配置写入成功当成运行成功。

首次从本地出厂链接转为 registry 版本使用官方更新事务。Android 定向适配仅允许组件白名单、包元数据与实际出厂路径均匹配的本地链接；其他本地包继续遵循官方限制。市场始终调用 `listBundles`、`listVersions` 和 `updateBundle`，不执行自己的 pnpm 安装器。

## 精确版本与结果反馈

新安装传递 `name@version` 与 `saveExact:true`。更新传递本次官方版本查询选出的兼容稳定版本，以及返回的 registry；官方更新内部固定精确版本并保持 enabled。实际版本同时核对更新结果与安装清单。

pnpm 11 默认的 minimum release age 不会把精确版本静默换成旧版；用户显式启用严格等待策略时，保留策略并展示原始错误。不要全局禁用等待期。

首次安装的构建脚本审批可携带原始精确目标重试。官方 `updateBundle` 的 options 只接受 registry 与 requestId，更新遇到脚本审批阻塞时展示原始失败、待审批包及旧版恢复结果；不能将 approvedBuilds 塞入更新调用，也不能伪装成直接安装重试。

`application=applied` 仅证明 Host 已应用。页面插件需观测目标模块修订变化、同步结束且对应 fiber 激活；失败与 release change 分别提示加载失败或刷新。没有这些证据时保持“尚未确认同步”。

## 移动能力拆分

基础 `dsh-android-mobile` 负责布局和已有 UI 适配，不依赖账户余额。设置、宿主连接和余额分别由 `dsh-android-mobile-settings`、`dsh-android-host-bridge` 与 `dsh-android-balance` 负责；各自释放计时器、监听器、观察器和迟到请求。

引擎兼容资产只拥有官方 dist 与 engine 客户端文件；不能再包含覆盖 Android 插件的 mobileui 树。compat manifest 记录引擎、polyfill、目标和工具摘要及逐文件摘要。Windows 入口转调同一个 Node 构建器。

## 验证入口

市场行为：在 `market-plugin/dsh-android-market` 运行 `npm test`。移动插件、桥协议、出厂登记与更新适配、远程同步分别执行各目录的行为测试。最终运行项目的两种 flavor 构建与 `bash tools/check-architecture.sh`，并核对 APK 内 zip、manifest 与生成产物一致。视觉与交互变化由用户验收。

引擎源码补丁由 `EngineFilePatches.kt` 的 11 类注册项维护，管理器只负责顺序和日志；`node tools/verify-engine-anchors.mjs` 在真实引擎归档副本上运行同一 Kotlin 注册表，再验证内置更新事务。图片 MIME 补丁必须在兼容资产安装后应用，避免兼容客户端覆盖补丁。

出厂登记测试依赖真实官方模板与解析器，不放入默认的无引擎资产 CI。显式指定引擎包路径后执行：

```bash
DSH_ENGINE_PACKAGE=<engine>/node_modules/@deepseek-ai/dsh/package.json \
  node --test app/src/main/java/com/dsh/android/engine/BuiltinPluginBootstrap.test.mjs
```

远程插件不属于 APK 出厂组件清单；在 `remote-plugin/mobile` 执行 `npm ci`、`npm test` 和 `npm pack --ignore-scripts`，确认发布包包含 `lib/session-index.js`、`lib/outbox.js` 与 `lib/reconnect.js`。这些模块分别拥有会话映射、发送确认和重连状态。
