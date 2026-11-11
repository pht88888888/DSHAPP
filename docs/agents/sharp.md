# ⑧ sharp 没有 android 预编译二进制

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑧ `sharp` 没有 android 预编译二进制 —— 装 wasm32 变体

**症状**：图片附件**上传成功**（缩略图正常显示），点发送报
`prompt rejected (session/agent-busy)`。

⚠️ `agent-busy` 是**兜底错误码**，真因藏在 `details.reason` 里，别照着字面查
"会话忙"。`dsh-api-session-controller/lib/index.js` 是
`throw new RemoteError("session/agent-busy", "prompt rejected", { reason: String(error) })`。
**排查手法**：在页面里 hook `window.fetch`，把 `details.reason` 打出来。

`dsh-attachment-local` 用 `sharp`（^0.35.3）做图片元数据 + 缩放重编码。sharp 的
预编译二进制只覆盖 darwin/linux/linuxmusl/win32/freebsd，**没有 android**。

`sharp/dist/sharp.cjs` 算出 `runtimePlatform = android-arm64`，switch 里没有这个分支，
于是**无条件落到 wasm 兜底**：

```js
sharp = require("@img/sharp-wasm32/sharp.node");
```

所以**只需要装 `@img/sharp-wasm32`，sharp 本身一行都不用改**。

**做法**：`assets/sharp-wasm32.zip`（含 `@img/sharp-wasm32` + `@emnapi/runtime`，
条目为相对路径、无顶层目录），`EngineRuntimeDependencies.ensureSharp()` 解到
`engine/node_modules/`。幂等判据是
`@img/sharp-wasm32/lib/sharp-wasm32-0.35.5.node.wasm` 存在且非空。无需版本戳。

⚠️ `@emnapi/runtime` 的 `index.js` 按 `NODE_ENV` 二选一 require
（`emnapi.cjs.min.js` / `emnapi.cjs.js`），**两个都要打进去**。
⚠️ wasm 靠 `locateFile()` 相对 `scriptDirectory` 找同目录的
`sharp-wasm32-0.35.5.node.wasm`，所以 `.node.js` 与 `.node.wasm` 必须同目录。
⚠️ 设备实测通过（`sharp 0.35.5` / `vips 8.18.7`，1200×800 全链路
metadata → resize → png/jpeg/webp → thumbnail 全 OK）。
**别用手搓的 base64 PNG 测**，那会报 `vipspng: libpng read error`，误判成 wasm 的锅；
用 `sharp({ create: {...} })` 造图。

