# 与 DSH 桌面端的关系

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 与 DSH 桌面端的关系

引擎版本以**桌面端实际安装的版本为准**（当前 `0.2.2-rc.1`），来源是
`Y:\DSH\resources\app.asar` → `dsh/package.json`。
本仓库内置的版本可直接核对：`app/src/main/assets/engine.bin` 里
`node_modules/@deepseek-ai/dsh/package.json` 的 `version`（现 `0.2.2-rc.1`）。
`MainActivity.assetStamp`（现 `0.2.2-rc.1-1`）是这个版本加一个自增后缀，
两者应该始终指向同一个引擎版本。
`C:\Users\你的用户名\.dsh\profiles\node_modules\@deepseek-ai\` 是旧版（`0.1.6-alpha.1`），
**不是权威来源**，别拿它当版本依据。
