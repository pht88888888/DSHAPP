# 第三方组件与许可证清单

> 本文件记录 DSH Android 客户端**分发载荷**中包含的第三方组件及其许可证。
>
> 生成方式：从 `engine.bin` / `bootstrap-aarch64.zip` 等资产里**实际解出**的
> `package.json` 与 `LICENSE` 文件统计得出，不是凭记忆罗列。
>
> ⚠️ **本清单是 G0-01 盘点产物，尚未经法务审查。** 分发前必须由维护者确认
> 履行了全部义务（尤其是下方「强著佐权组件」一节）。

---

## 1. 载荷构成

| 资产 | 内容 | 版本 | 大小 | sha256(前16) |
|---|---|---|---|---|
| `engine.bin` | 官方 DSH 引擎 + 558 个 npm 依赖 | 见 `engine/.stamp` | 55.0 MB | `adc7a3aa9eeef64e` |
| `node-runtime.bin` | Node.js 运行时 | **v26.4.0**（Termux 包 `26.4.0-2`） | 30.1 MB | `ba16f7f4d0cc0b02` |
| `bootstrap-aarch64.zip` | Termux 基础环境 | 2026-09-25 构建 | 23.1 MB | `b3739c14a2eb7421` |
| `pnpm-android-arm64.zip` | pnpm 纯 JS 版 | **11.28.3** | 5.6 MB | `283fb781491c0672` |
| `ripgrep-android-arm64.zip` | ripgrep 交叉编译 | 未记录 | 2.0 MB | `c53a30002ae9695b` |
| `adb-wireless-arm64.zip` | Android platform-tools | 未记录 | 5.2 MB | `1a0776404f4b3797` |
| `sharp-wasm32.zip` | sharp（libvips wasm） | 未记录 | 3.5 MB | `41ad7e41dd542bcc` |
| `webview-compat.zip` | 本项目构建期生成的降级前端 | 构建期生成 | 9.2 MB | `54cf2e9841190de0` |

> 版本从**二进制/常量**读出，不是照抄文档：
> `strings bin/node | grep '^v[0-9]'` → `v26.4.0`；
> `EngineManager.PNPM_VERSION = "11.28.3"`；
> `TermuxBootstrap.NODE_VERSION = "26.4.0-2"`（Termux 包版本，与上游 v26.4.0 对应）。

---

## 2. 强著佐权组件（分发义务最重）

> ⚠️ **这一节是分发前必须逐条确认的部分。**

### 2.1 Termux bootstrap 中的 GPL / LGPL / AGPL

`bootstrap-aarch64.zip` 是 Termux 官方 bootstrap，其中包含 **35 种许可证**，
涉及 GPL-2.0 / GPL-3.0 / LGPL-2.0 / LGPL-2.1 / LGPL-3.0 / AGPL-V3。

**分发义务**：
- GPL 组件：必须提供对应源码或书面要约
- LGPL 组件：必须允许用户替换该库
- AGPL：若提供网络服务，须提供源码

**当前状态**：⚠️ **未确认是否已履行**。bootstrap 内的
`share/LICENSES/` 目录随包分发（这点满足「附带许可证文本」），
但**源码提供义务未验证**。

### 2.2 其他著佐权组件

| 组件 | 许可证 | 说明 |
|---|---|---|
| 引擎依赖中的 `MPL-2.0` 组件 | MPL-2.0 | 弱著佐权，修改需开源该文件 |

---

## 3. 引擎 npm 依赖（558 个）

> 全部为宽松许可（MIT / Apache-2.0 / BSD / ISC），无强著佐权。

### 3.1 许可证分布

- `(MIT OR CC0-1.0)`: 1 个
- `0BSD`: 1 个
- `Apache-2.0`: 51 个
- `BSD-2-Clause`: 2 个
- `BSD-3-Clause`: 14 个
- `ISC`: 11 个
- `MIT`: 475 个
- `MPL-2.0`: 1 个
- `Python-2.0`: 1 个
- `Unlicense`: 1 个

### 3.2 完整清单

<details>
<summary>展开 558 个依赖</summary>

| 包 | 版本 | 许可证 |
|---|---|---|
| `@agentclientprotocol/sdk` | 1.4.0 | Apache-2.0 |
| `@anthropic-ai/sdk` | 0.129.0 | MIT |
| `@aws-sdk/client-bedrock-runtime` | 3.1127.0 | Apache-2.0 |
| `@aws-sdk/core` | 3.978.3 | Apache-2.0 |
| `@aws-sdk/credential-provider-env` | 3.972.74 | Apache-2.0 |
| `@aws-sdk/credential-provider-http` | 3.972.76 | Apache-2.0 |
| `@aws-sdk/credential-provider-ini` | 3.973.19 | Apache-2.0 |
| `@aws-sdk/credential-provider-login` | 3.972.81 | Apache-2.0 |
| `@aws-sdk/credential-provider-node` | 3.972.86 | Apache-2.0 |
| `@aws-sdk/credential-provider-process` | 3.972.74 | Apache-2.0 |
| `@aws-sdk/credential-provider-sso` | 3.973.18 | Apache-2.0 |
| `@aws-sdk/credential-provider-web-identity` | 3.972.80 | Apache-2.0 |
| `@aws-sdk/eventstream-handler-node` | 3.972.36 | Apache-2.0 |
| `@aws-sdk/middleware-eventstream` | 3.972.31 | Apache-2.0 |
| `@aws-sdk/middleware-websocket` | 3.972.56 | Apache-2.0 |
| `@aws-sdk/nested-clients` | 3.997.48 | Apache-2.0 |
| `@aws-sdk/signature-v4-multi-region` | 3.996.48 | Apache-2.0 |
| `@aws-sdk/token-providers` | 3.1127.0 | Apache-2.0 |
| `@aws-sdk/token-providers` | 3.1150.0 | Apache-2.0 |
| `@aws-sdk/types` | 3.974.6 | Apache-2.0 |
| `@aws-sdk/xml-builder` | 3.972.41 | Apache-2.0 |
| `@aws/lambda-invoke-store` | 0.3.0 | Apache-2.0 |
| `@babel/code-frame` | 7.29.7 | MIT |
| `@babel/helper-validator-identifier` | 7.29.7 | MIT |
| `@babel/runtime` | 7.29.10 | MIT |
| `@deepseek-ai/cordis` | 4.0.5 | MIT |
| `@deepseek-ai/cordis-plugin-group` | 1.0.5 | MIT |
| `@deepseek-ai/cordis-plugin-include` | 1.0.10 | MIT |
| `@deepseek-ai/cordis-plugin-loader` | 1.0.6 | MIT |
| `@deepseek-ai/cordis-plugin-timer` | 1.1.7 | MIT |
| `@deepseek-ai/cosmokit` | 1.8.6 | MIT |
| `@deepseek-ai/dsh` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-acp` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-acp-app` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent-default-model` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent-instructions` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent-loop` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent-preset` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent-preset-registry` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-agent-tool-presentation` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-anonymous-user-id` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-account-controller` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-gateway` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-job-controller` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-remote-access` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-remotes` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-session-controller` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-settings-controller` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-terminal-controller` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-workspace-controller` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-api-workspace-files` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-app-boot` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-atomic-write` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-attachment` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-attachment-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-authorization` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-base` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-bash-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-bash-sandbox` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-brand` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-chunked-list` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-connection` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-file-upload` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-hmr` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-locale` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-modules` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-product-analytics` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-resources` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-shortcuts` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-agent-preset` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-approval` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-attachment` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-brand-official` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-chat` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-commands` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-conversation` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-cordis` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-deepseek-model-label-1017` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-deliverables` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-directory-picker-browse` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-directory-picker-native` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-goal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-input-trigger` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-jobs` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-layout` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-message-feedback` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-model-selection` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-open-in-app` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-permission-presets` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-plan` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-plugin-manager` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-plugin-market` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-reference` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-renderer` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-schedule` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-secret-mask` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-session` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-account` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-agent-loop` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-general` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-models` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-plugin-inventory` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-plugins` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-session-log` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-shell` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-subagent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-settings-web-search` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-shortcuts` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-sidebar` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-sidebar-browser` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-sidebar-documentpreview` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-sidebar-files` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-sidebar-right` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-sidebar-terminal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-skill` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-subagent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-theme` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-tool` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-trajectory` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-user-questions` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-workflow-run` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-client-ui-workspace` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-cmdline` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-command-compact` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-command-feedback` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-command-goal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-commands` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-compaction` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-compaction-basic` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-compaction-image-offload` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-compaction-tool-result-pruner` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-config-editor` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-cordis-client-runner` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-cordis-host-runner` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-credentials` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-credentials-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-deepseek-account` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-deepseek-account-platform` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-deepseek-llm-api-extensions` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-deque` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-agent-team` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-agent-team-profile` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-api-speech-to-text` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-auto-review` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-badge-skill-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-client-ui-agent-team` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-client-ui-cot-translation` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-client-ui-voice-input` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-cot-translation-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-inspector` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-inspector-profile` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-plugin-market-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-ralph-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-session-inspector` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-session-search` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-session-titles-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-speech-to-text` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-speech-to-text-sensevoice` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-terminal-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-tool-agent-team` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-tool-worktree` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-translator` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-voice-input-bundle` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-experimental-worktree` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-file-reference` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-file-reference-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-fs` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-fs-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-fs-observation-policy` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-fs-sandbox` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-goal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-goal-round-driver` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-harness-dev-context` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-headless` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-hmr` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-home-paths` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-directory-picker` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-directory-picker-auto` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-directory-picker-browse` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-directory-picker-native` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-frontend-static` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-open-in-app` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-plugin-inventory` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-product-telemetry-otel` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-host-webserver` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-http-proxy` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-jobs` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-jobs-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-launch-environment` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-lazy-require` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-llm` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-llm-deepseek` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-llm-deepseek-account` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-llm-deepseek-api-key` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-llm-pi-ai` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-llm-retry` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-mcp-client` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-mcp-resources` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-message-feedback` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-native-command` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-office-to-pdf` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-otel` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-output-retention` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-package-manifest` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-permission-presets` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-persona` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-plan-mode` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-plugin-manager` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-plugin-market` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-plugin-market-index` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-plugin-package-inventory-deepseek` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-ptc-runtime` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-ptc-runtime-node` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-pwsh-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-pwsh-sandbox` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-repeat-tool-reminder` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sandbox` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sandbox-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sandbox-policy` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sandbox-windows-acl` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-schedule` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-scope` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sdk-app` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sdk-jsonrpc-server` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sdk-minimal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-sdk-protocol` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-checkpoint-policy` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-format` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-format-catalog` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-format-v0-to-v1` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-format-v1-to-v2` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-format-v2-to-v3` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-format-v3-to-v4` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-log-deepseek` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-log-export` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-persistence` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-persistence-jsonl` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-projection` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-projection-cache` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-query` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-query-sqlite` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-reference` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-stats` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-telemetry` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-telemetry-otel` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-title` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-title-all-prompts-llm` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-title-first-prompt-llm` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-title-llm` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-session-turn-outline` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-settings` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-shell` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-shell-env` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-skill` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-skill-badge` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-skill-filesystem` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-skill-office` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-spill` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-spill-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-spill-policy` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-storage` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-storage-domain` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-storage-json` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-subagent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-subagent-fork-in-process` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-subagent-spawn-in-process` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-subprocess` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-subprocess-local` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-system-prompt` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-terminal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-terminal-bash` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-time-context` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-timeout` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tmux-context` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-token-meter` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-ask-user` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-bash` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-bash-persistent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-call-timeout-policy` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-cordis` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-fs` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-fs-search` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-goal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-jobs` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-present` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-pwsh` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-pwsh-persistent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-ralph` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-schedule` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-session-query` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-skill` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-str-replace-editor` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-subagent` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-subagent-control` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-terminal` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-todo` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-web` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-workflow` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-working-directory` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tool-workspace-dependencies` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-tools` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-typert-loader` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-typert-protocol` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-typert-registry` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-user-approval` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-user-questions` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-util-code-language` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-util-crypto` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-util-time` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-util-values` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-util-workspace-path` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-web` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-web-app` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-web-deepseek-api` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-web-fetch-http` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-web-frontend` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-web-search-deepseek` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-win32-process` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-workflow` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-workflow-ptc` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-working-directory` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-workspace` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/dsh-workspace-changes` | 0.2.1-rc.2 | MIT |
| `@deepseek-ai/libreoffice-kit` | 0.1.5 | MPL-2.0 |
| `@deepseek-ai/node-addon-system` | 0.1.2 | BSD-3-Clause |
| `@deepseek-ai/schemastery` | 3.18.5 | MIT |
| `@earendil-works/pi-ai` | 1.3.0 | MIT |
| `@earendil-works/pi-telemetry` | 1.3.0 | MIT |
| `@eslint-community/regexpp` | 4.12.2 | MIT |
| `@google/genai` | 2.21.0 | Apache-2.0 |
| `@img/colour` | 1.1.0 | MIT |
| `@joplin/turndown-plugin-gfm` | 1.0.68 | MIT |
| `@js-temporal/polyfill` | 0.5.1 | ISC |
| `@keyv/serialize` | 1.1.1 | MIT |
| `@koromix/koffi-win32-x64` | 3.1.1 | MIT |
| `@mixmark-io/domino` | 2.2.0 | BSD-2-Clause |
| `@modelcontextprotocol/client` | 2.2.0 | MIT |
| `@modelcontextprotocol/core` | 2.2.0 | MIT |
| `@opentelemetry/api` | 1.9.1 | Apache-2.0 |
| `@opentelemetry/api-logs` | 0.222.0 | Apache-2.0 |
| `@opentelemetry/core` | 2.11.0 | Apache-2.0 |
| `@opentelemetry/otlp-exporter-base` | 0.222.0 | Apache-2.0 |
| `@opentelemetry/otlp-transformer` | 0.222.0 | Apache-2.0 |
| `@opentelemetry/resources` | 2.11.0 | Apache-2.0 |
| `@opentelemetry/sdk-logs` | 0.222.0 | Apache-2.0 |
| `@opentelemetry/sdk-metrics` | 2.11.0 | Apache-2.0 |
| `@opentelemetry/sdk-trace` | 2.11.0 | Apache-2.0 |
| `@opentelemetry/semantic-conventions` | 1.43.0 | Apache-2.0 |
| `@protobufjs/aspromise` | 1.1.2 | BSD-3-Clause |
| `@protobufjs/base64` | 1.1.2 | BSD-3-Clause |
| `@protobufjs/codegen` | 2.0.5 | BSD-3-Clause |
| `@protobufjs/eventemitter` | 1.1.1 | BSD-3-Clause |
| `@protobufjs/fetch` | 1.1.1 | BSD-3-Clause |
| `@protobufjs/float` | 1.0.2 | BSD-3-Clause |
| `@protobufjs/path` | 1.1.2 | BSD-3-Clause |
| `@protobufjs/pool` | 1.1.0 | BSD-3-Clause |
| `@protobufjs/utf8` | 1.1.2 | BSD-3-Clause |
| `@sec-ant/readable-stream` | 0.4.1 | MIT |
| `@sindresorhus/is` | 7.2.0 | MIT |
| `@sindresorhus/merge-streams` | 4.0.0 | MIT |
| `@smithy/core` | 3.36.0 | Apache-2.0 |
| `@smithy/credential-provider-imds` | 4.5.2 | Apache-2.0 |
| `@smithy/fetch-http-handler` | 5.8.0 | Apache-2.0 |
| `@smithy/node-http-handler` | 4.12.1 | Apache-2.0 |
| `@smithy/signature-v4` | 5.7.4 | Apache-2.0 |
| `@smithy/types` | 4.19.0 | Apache-2.0 |
| `@stablelib/base64` | 1.0.1 | MIT |
| `@standard-schema/spec` | 1.1.0 | MIT |
| `@swc/helpers` | 0.5.23 | Apache-2.0 |
| `@types/http-cache-semantics` | 4.2.0 | MIT |
| `@types/node` | 26.6.5 | MIT |
| `@types/retry` | 0.12.0 | MIT |
| `@vscode/ripgrep` | 1.18.0 | MIT |
| `@vscode/ripgrep-win32-x64` | 1.18.0 | MIT |
| `@xterm/addon-serialize` | 0.14.0 | MIT |
| `@xterm/headless` | 6.0.0 | MIT |
| `agent-base` | 7.1.4 | MIT |
| `agent-base` | 9.0.0 | MIT |
| `ajv` | 8.20.0 | MIT |
| `argparse` | 2.0.1 | Python-2.0 |
| `base64-js` | 1.5.1 | MIT |
| `benchmark` | 1.0.0 | ISC |
| `bignumber.js` | 9.3.1 | MIT |
| `bowser` | 2.14.1 | MIT |
| `brotli` | 1.3.3 | MIT |
| `buffer-equal-constant-time` | 1.0.1 | BSD-3-Clause |
| `bundle-name` | 4.1.1 | MIT |
| `byte-counter` | 0.1.0 | MIT |
| `bytes` | 3.1.2 | MIT |
| `cacheable-lookup` | 7.0.0 | MIT |
| `cacheable-request` | 13.0.19 | MIT |
| `chokidar` | 5.0.0 | MIT |
| `clone` | 2.1.2 | MIT |
| `commander` | 15.0.0 | MIT |
| `compressible` | 2.0.18 | MIT |
| `compression` | 1.8.2 | MIT |
| `content-type` | 2.1.0 | MIT |
| `cross-spawn` | 7.0.6 | MIT |
| `data-uri-to-buffer` | 4.0.1 | MIT |
| `debug` | 2.6.9 | MIT |
| `debug` | 4.4.3 | MIT |
| `debug` | 4.4.3 | MIT |
| `debug` | 4.4.3 | MIT |
| `debug` | 4.4.3 | MIT |
| `decompress-response` | 10.0.0 | MIT |
| `default-browser` | 5.5.1 | MIT |
| `default-browser-id` | 5.0.1 | MIT |
| `define-lazy-prop` | 3.0.0 | MIT |
| `depd` | 2.0.0 | MIT |
| `destroy` | 1.2.0 | MIT |
| `detect-libc` | 2.1.2 | Apache-2.0 |
| `dfa` | 1.2.0 | MIT |
| `diff` | 9.0.0 | BSD-3-Clause |
| `ecdsa-sig-formatter` | 1.0.11 | Apache-2.0 |
| `ee-first` | 1.1.1 | MIT |
| `encodeurl` | 2.0.0 | MIT |
| `escape-html` | 1.0.3 | MIT |
| `etag` | 1.8.1 | MIT |
| `eventsource` | 3.0.7 | MIT |
| `eventsource-parser` | 3.1.1 | MIT |
| `execa` | 10.2.0 | MIT |
| `extend` | 3.0.2 | MIT |
| `fast-deep-equal` | 3.1.3 | MIT |
| `fast-sha256` | 1.3.0 | Unlicense |
| `fast-uri` | 3.1.8 | BSD-3-Clause |
| `fetch-blob` | 3.2.0 | MIT |
| `fflate` | 0.8.2 | MIT |
| `fflate` | 0.8.3 | MIT |
| `figures` | 6.1.0 | MIT |
| `fontkit` | 2.0.4 | MIT |
| `form-data-encoder` | 4.1.0 | MIT |
| `formdata-polyfill` | 4.0.10 | MIT |
| `fresh` | 2.0.0 | MIT |
| `gaxios` | 7.3.1 | Apache-2.0 |
| `gcp-metadata` | 8.1.2 | Apache-2.0 |
| `get-stream` | 9.0.1 | MIT |
| `google-auth-library` | 10.9.1 | Apache-2.0 |
| `google-logging-utils` | 1.1.3 | Apache-2.0 |
| `got` | 14.6.6 | MIT |
| `http-cache-semantics` | 4.3.0 | BSD-2-Clause |
| `http-errors` | 2.0.1 | MIT |
| `http-proxy-agent` | 9.1.0 | MIT |
| `http2-wrapper` | 2.2.1 | MIT |
| `https-proxy-agent` | 7.0.6 | MIT |
| `https-proxy-agent` | 9.1.0 | MIT |
| `human-signals` | 8.0.1 | Apache-2.0 |
| `inherits` | 2.0.4 | ISC |
| `ipaddr.js` | 2.5.0 | MIT |
| `is-docker` | 3.0.0 | MIT |
| `is-in-ssh` | 1.0.0 | MIT |
| `is-inside-container` | 1.0.0 | MIT |
| `is-plain-obj` | 4.1.0 | MIT |
| `is-stream` | 4.0.1 | MIT |
| `is-unicode-supported` | 2.1.0 | MIT |
| `is-wsl` | 3.1.1 | MIT |
| `isexe` | 2.0.0 | ISC |
| `jose` | 6.2.12 | MIT |
| `js-tokens` | 4.0.0 | MIT |
| `js-yaml` | 4.3.2 | MIT |
| `jsbi` | 4.3.2 | Apache-2.0 |
| `json-bigint` | 1.0.0 | MIT |
| `json-schema-to-ts` | 3.1.1 | MIT |
| `json-schema-traverse` | 1.0.0 | MIT |
| `jwa` | 2.0.1 | MIT |
| `jws` | 4.0.1 | MIT |
| `keyv` | 5.6.0 | MIT |
| `koffi` | 3.1.1 | MIT |
| `long` | 5.3.2 | Apache-2.0 |
| `lowercase-keys` | 3.0.0 | MIT |
| `mime-db` | 1.54.0 | MIT |
| `mime-types` | 3.0.2 | MIT |
| `mimic-response` | 4.0.0 | MIT |
| `minisearch` | 7.2.0 | MIT |
| `ms` | 2.0.0 | MIT |
| `ms` | 2.1.3 | MIT |
| `ms` | 2.1.3 | MIT |
| `ms` | 2.1.3 | MIT |
| `ms` | 2.1.3 | MIT |
| `negotiator` | 0.6.4 | MIT |
| `negotiator` | 1.1.0 | MIT |
| `node-addon-api` | 7.1.1 | MIT |
| `node-addon-native-custom-loader` | 0.1.9 | MIT |
| `node-addon-require-builtin` | 0.1.9 | MIT |
| `node-domexception` | 1.0.0 | MIT |
| `node-fetch` | 3.3.2 | MIT |
| `node-pty` | 1.2.0-beta.15 | MIT |
| `normalize-url` | 8.1.1 | MIT |
| `npm-run-path` | 6.0.0 | MIT |
| `on-finished` | 2.4.1 | MIT |
| `on-headers` | 1.1.0 | MIT |
| `open` | 11.0.4 | MIT |
| `openai` | 7.19.0 | Apache-2.0 |
| `p-cancelable` | 4.0.1 | MIT |
| `p-limit` | 7.3.3 | MIT |
| `p-retry` | 4.6.2 | MIT |
| `pako` | 0.2.9 | MIT |
| `parse-ms` | 4.0.0 | MIT |
| `parseurl` | 1.3.3 | MIT |
| `partial-json` | 0.1.7 | MIT |
| `path-key` | 3.1.1 | MIT |
| `path-key` | 4.0.0 | MIT |
| `picocolors` | 1.1.1 | ISC |
| `picomatch` | 4.0.7 | MIT |
| `pkce-challenge` | 5.0.1 | MIT |
| `powershell-utils` | 0.2.1 | MIT |
| `pretty-ms` | 9.3.1 | MIT |
| `protobufjs` | 7.6.6 | BSD-3-Clause |
| `proxy-agent-negotiate` | 1.1.0 | MIT |
| `quick-lru` | 5.1.1 | MIT |
| `range-parser` | 1.3.0 | MIT |
| `readdirp` | 5.1.1 | MIT |
| `require-from-string` | 2.0.2 | MIT |
| `resolve-alpn` | 1.2.1 | MIT |
| `resolve.exports` | 2.0.4 | MIT |
| `responselike` | 4.0.2 | MIT |
| `restructure` | 3.0.2 | MIT |
| `retry` | 0.13.1 | MIT |
| `run-applescript` | 7.1.0 | MIT |
| `safe-buffer` | 5.2.1 | MIT |
| `saxes` | 6.0.0 | ISC |
| `semver` | 7.8.5 | ISC |
| `send` | 1.2.1 | MIT |
| `serve-static` | 2.2.1 | MIT |
| `setprototypeof` | 1.2.0 | ISC |
| `sharp` | 0.35.5 | Apache-2.0 |
| `shebang-command` | 2.0.0 | MIT |
| `shebang-regex` | 3.0.0 | MIT |
| `sherpa-onnx-node` | 1.13.8 | Apache-2.0 |
| `signal-exit` | 4.1.0 | ISC |
| `standardwebhooks` | 1.1.1 | MIT |
| `statuses` | 2.0.2 | MIT |
| `strip-final-newline` | 4.0.0 | MIT |
| `tiny-inflate` | 1.0.3 | MIT |
| `toidentifier` | 1.0.1 | MIT |
| `ts-algebra` | 2.0.0 | MIT |
| `tslib` | 2.8.1 | 0BSD |
| `turndown` | 7.2.4 | MIT |
| `type-fest` | 4.41.0 | (MIT OR CC0-1.0) |
| `typebox` | 1.3.27 | MIT |
| `undici` | 8.11.2 | MIT |
| `undici-types` | 8.9.0 | MIT |
| `unicode-properties` | 1.4.1 | MIT |
| `unicode-trie` | 2.0.0 | MIT |
| `unicorn-magic` | 0.3.0 | MIT |
| `vary` | 1.1.2 | MIT |
| `web-streams-polyfill` | 3.3.3 | MIT |
| `which` | 2.0.2 | ISC |
| `which-command` | 0.1.0 | MIT |
| `ws` | 8.22.0 | MIT |
| `wsl-utils` | 1.0.1 | MIT |
| `xmlchars` | 2.2.0 | MIT |
| `yaml` | 2.9.1 | ISC |
| `yocto-queue` | 1.2.2 | MIT |
| `yoctocolors` | 2.2.0 | MIT |
| `zod` | 4.4.3 | MIT |
</details>

---

## 4. 本项目自身

| 项 | 值 |
|---|---|
| 项目许可证 | MIT（见 `LICENSE`） |
| Android 兼容补丁 | MIT（同项目） |
| NDK 编译的 flock 绑定 | MIT（源码为官方 `src/flock.c`，未修改） |

---

## 5. 未完成事项

- [ ] Termux GPL/LGPL/AGPL 组件的**源码提供方式**未确认
- [ ] Node.js 运行时的**确切版本**未记录（`node-runtime.bin` 无版本元数据）
- [ ] 各资产的**上游获取 URL** 未记录（只有本地路径）
- [ ] 未做法务审查

> 这些是 G0 阶段「来源与许可证可追溯」的剩余缺口，**阻塞发行但不阻塞开发**。

---

## 附录：Termux bootstrap 内含的许可证文件

- `AGPL-V3.txt`
- `Apache-2.0.txt`
- `Artistic-License-2.0.txt`
- `BSL-1.0.txt`
- `CC0-1.0.txt`
- `CeCILL-2.1.txt`
- `EPL-1.0.txt`
- `EPL-2.0.txt`
- `GPL-2.0-only.txt`
- `GPL-2.0-or-later.txt`
- `GPL-2.0.txt`
- `GPL-3.0-only.txt`
- `GPL-3.0-or-later.txt`
- `GPL-3.0.txt`
- `ISC.txt`
- `ImageMagick.txt`
- `LGPL-2.0-only.txt`
- `LGPL-2.0-or-later.txt`
- `LGPL-2.0.txt`
- `LGPL-2.1-only.txt`
- `LGPL-2.1-or-later.txt`
- `LGPL-2.1.txt`
- `LGPL-3.0-only.txt`
- `LGPL-3.0-or-later.txt`
- `LGPL-3.0.txt`
- `MPL-2.0.txt`
- `NCSA.txt`
- `Nethack.txt`
- `OpenLDAP.txt`
- `PHP-3.0.txt`
- `Public Domain.txt`
- `Unlicense.txt`
- `VIM License.txt`
- `WTFPL.txt`
- `wxWindows.txt`
