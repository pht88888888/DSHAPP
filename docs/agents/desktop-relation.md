# 与 DSH 桌面端的关系

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 与 DSH 桌面端的关系

桌面端实际版本可从 `resources/app.asar` 内的 `dsh/package.json` 核对。
Android 随包版本以[运行资产锁](../../catalogs/runtime-assets.lock.json)及实际归档内容为准：
`engine.bin` 内 `node_modules/@deepseek-ai/dsh/package.json` 的 `version` 当前是 `0.2.2-rc.5`。
`MainActivity.assetStamp`（现 `0.2.2-rc.5-3`）由引擎版本和自增后缀组成，
两者应该始终指向同一个引擎版本。
`C:\Users\你的用户名\.dsh\profiles\node_modules\@deepseek-ai\` 是旧版（`0.1.6-alpha.1`），
**不是权威来源**，别拿它当版本依据。
