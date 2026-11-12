# 插件改坏导致 App 打不开

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 插件改坏导致 App 打不开 —— 启动前体检 + 安全模式

**症状**：用户让 App 里的 AI 改插件，AI 把 profile 或 `engine/` 改坏，之后 App
**永久打不开**，且用户手上没有任何出路 —— 只能卸载重装，而那会清掉全部会话与凭据。

**根因是两条叠加**：

1. profile 与 patch 层都是**模型可写**的，改坏会让引擎 `exit 1`（或 boot 挂死）。
2. `ensureExtracted()` 有 `.stamp` 短路 —— 改坏的是 `engine/` 下的文件时，
   **永远不会被还原**，因为版本戳没变。

### 判据：用引擎自己的 `--dump-config`，不要在 Kotlin 里重写 YAML 校验

引擎自带免 boot 的 `dsh --profile web --dump-config`，走的是**和启动完全同一套**
`loadProfile()` + patch 解析，但只解析、不求值 `!!js`、不起服务。所以判据来自引擎
自己的解析器，不会随引擎升级而漂。

真机实测耗时 **0.7~0.8 秒**（3 次：703 / 780 / 800 ms），相对引擎自身 7~10 秒的启动
时间可忽略。

**四种坏法的判据不一样，别合并成一条**（真机逐条实测）：

| 坏在哪 | `dump-config` | 判据 |
|---|---|---|
| `profiles/web/package.json` 非法 JSON | RC=1 | 退出码（但 stderr 里**没有文件路径**，只有 JSON 位置） |
| `profiles/web/cordis.patch.yml` 语法坏 | RC=1 | 退出码 + stderr 里的绝对路径 |
| 第三方 bundle 的 patch 坏 | **RC=0** | stderr 的 `skipping profile bundle` |
| 核心 bundle（`dsh-web-app`）的 patch 坏 | **RC=0** | **required 条目缺失** |

⚠️ **只看退出码会漏掉后两种**。第四种尤其要紧：真机实测该状态下 `dump-config`
返回 **RC=0**，但真正 boot 会**挂死**（45 秒超时被杀，`EXIT=124`）。所以必须再补一道
「required 条目在不在」的检查。

### 自动修：`EngineManager.preflight()`（A）

在 `start()` 的**最前段**跑（早于所有往 `engine/` 写东西的 `ensureXxx`），
发现坏文件就**改名隔离**（`<原名>.bak-<时间戳>`），再复检一次确认修好了。

⚠️ **顺序是硬性的**：体检在发现核心 bundle 坏时会触发**整棵 `engine/` 重解包**，
那会把已经打好的补丁与解出的原生插件全部抹掉。放在 `ensureWebCompat()` 之后会让
重解包抹掉刚打的补丁。

⚠️ **改名不删**：AI 改坏的东西可能还带着用户想要的内容，体检只负责让 App 能打开。

### 兜底：安全模式（B）

体检只用能精确指认的判据，拿不准就不碰 —— 免得误伤好配置。但覆盖不到的情况真实
存在（坏法超出那四类、体检超时、坏在体检看不见的地方）。所以失败浮层上给一个
**用户显式触发**的按钮（`overlaySafeMode`，只在 `isError` 时可见）。

`EngineManager.enterSafeMode()` 推开三样，都不碰用户数据：

| 推开什么 | 为什么 |
|---|---|
| profile 用户 patch 层 | 模型最爱改的就是它，改坏会让引擎 exit 1 |
| 第三方 bundle（保留 deepseek-ai 前缀那些） | 插件装坏 / 版本对不上是常见死因 |
| `engine/.stamp` | 强制重解包，把 `engine/` 里的改动全部还原 |

⚠️ **会话、凭据、工作区都在 `$DSH_HOME` 与 `workspace/`，不在这三样里** ——
真机验收时逐项确认过（`sessions/`、`storages/`、`workspace/` 全部完好）。

⚠️ 三样都是改名不删。`stripThirdPartyBundles()` 改的是 profile **清单**而不是插件包
（包还在 `node_modules` 里，想装回来重新勾一次即可），且**备份失败就不写** ——
否则会把唯一一份原始清单覆盖掉。

### 真机验收（2026-10-11，CDY-TN90 / Android 10）

| 场景 | 结果 |
|---|---|
| 正常态（配置完好） | 体检**静默通过**，无任何日志，启动 11 秒，行为零变化 |
| 坏法① `package.json` = `{ x` | 体检识别 + 隔离 + 复检通过，14 秒起好 |
| 坏法② `cordis.patch.yml` 引号未闭合 | 体检 1 秒识别 + 隔离 + 复检通过，14 秒起好 |
| 坏法④ 删核心 bundle 的 patch | 识别出缺 `webserver, modules, connection` → 重解包 18.5 秒 → 复检通过 → 起好 |
| 体检修不了的失败态（删 `bin.js`） | 失败浮层出现，按钮可见可点 |
| 点安全模式（清单含第三方 bundle） | 隔离 2 个文件 + 摘掉 1 个第三方 bundle + 重解包 → 起好，会话/凭据/工作区全在 |

### 写这套东西踩到的坑

⚠️ **Kotlin 的块注释支持嵌套**：KDoc 里写 `@deepseek-ai` 紧跟 `/*`（即包名通配那种
写法）会**开始一层嵌套注释**，把下面整段代码吞进注释里。报错是 `Missing '}'` +
`Unclosed comment`，**行号指向完全无关的地方**。文档里写这种通配要拆开写。

⚠️ **主题是 `Theme.Material3.*`，它会顶掉 `Button` 的 `android:background`** ——
设了描边背景却渲染成一大块实心紫。气泡里那几个按钮同样是 `TextView`，照抄既有写法
（`TextView` + `clickable="true"` + `@drawable/xxx_bg`）。

⚠️ **`org.json` 的 `JSONObject.toString()` 会把 `/` 转义成 `\/`**（防 `</script>`
的历史行为）。在 JSON 里完全合法、引擎也读得进去，但这份清单用户和模型都会看，
`@deepseek-ai\/dsh-base` 长得就像文件已经坏了 —— 而"配置看起来坏了"正是要消灭的
状态。回写时 `.replace("\\/", "/")` 还原。

⚠️ **`quarantine()` 是两条路径共用的**（体检自动修 / 安全模式），日志前缀必须区分
来源 —— 否则排查时会把用户主动进安全模式误读成"体检又修了一次"。

⚠️ **测试期隔离出来的 `.bak-<时间戳>` 会累积**：真机上跑一轮完整验证会留下五六个。
测完记得 `rm -f files/home/.dsh/profiles/web/*.bak-*`，并把 profile 恢复成测前的样子。

### ⚠️ 体检救不了的一种坏法：**我们自己注入的可选工具插件**注册失败

上面的体检只看**配置文件**（profile / patch / `engine/`）。但还有一条同类死路它完全
看不到 —— 坏在我们**自己的插件代码**里：

```
dsh: startup failed: 1 required plugin did not activate
Failed plugins (1):
  dsh-android-logs
    Package: file:///…/.dsh/logs-tool/dsh-android-logs/lib/index.js
    TypeError: tool "logs" must declare output { schema, render, presentationMeta? }
        at Proxy.register (…/@deepseek-ai/dsh-tools/lib/index.js:2881:188)
        at Object.apply (…/@deepseek-ai/cordis/lib/index.js:120:36)
```

真机实测（2026-10-13 21:46 ~ 23:29，20 个诊断包、**54 次启动**）：`logs` 工具的定义
漏了 `output` 声明，`ctx.tools.register()` 抛 `TypeError`，异常从 `apply()` 冒出去 →
cordis 判定该插件 failed → 引擎**直接退出**。用户看到的是
**「引擎启动失败（引擎进程已退出）」—— App 完全打不开**，
而表面上与「一个日志工具」毫无关联。**这正是体检覆盖不到的那一类**：配置文件全是好的。

⚠️ **根因链的最后一环是 `EngineManager.writeLogsToolPatch()` 的 `- insert:` 语义**：
它把 `dsh-android-logs` 作为**普通条目**插进 overlay，于是该插件与核心 bundle 的条目
**同等对待**，失败即「required」⇒ 整引擎不启动。所以从这个插件进来的任何异常，
代价都等于「App 打不开」。

**做法：6 个工具插件的 `ctx.tools.register()` 一律包 `try/catch`。**
覆盖 `bubble` / `logs` / `root` / `shell` / `statusbar` / `uiagent`
（`mobile-ui-plugin/dsh-android-*/lib/index.js`）。

降级方向：**注册失败 ⇒ 只有这一个工具不存在**（模型调用时会明确报「未知工具」），
而不是整个 App 不可用。⚠️ **但绝不能静默** —— catch 里用 `logger.warn` 把**原始错误
消息**（`error?.message`）原样打出来，否则又变成「工具不见了但没人知道为什么」。
日志里应看到：

```
[dsh-android-logs] 注册 logs 工具失败，本插件不激活（引擎继续启动）: tool "logs" must declare output …
```

⚠️ **`try/catch` 只能挡住「注册那一刻抛的异常」，挡不住「工具自身在调用时炸」** ——
后者由 `dsh-tools` 自己的调用边界处理，与启动无关。两者别混为一谈。

⚠️ **给新插件写 `apply()` 时照抄这个形状**；已有插件逐个核对过，6/6 都带保护、
无裸调用残留（`^\tctx\.tools\.register` 为 0 命中）。


## 「浏览器侧那一半坏了」的判据（2026-11-12 重写）

引擎健康、坏在 client 半时的降级入口有三类证据，**全部不依赖官方启动页长什么样**。

| 判据 | 来源 | 处置 |
|---|---|---|
| 明确失败 | `Failed to load plugins`；整批 `/plugins/??` 资源加载失败；首帧正文是失败页 | 立刻降级 |
| 健康证据 | `dsh-android-mobile` 上报**结构锚点 ≥ 1**（`[contenteditable="true"]` / `role=textbox` / `textarea` / `button[aria-label]` / `_sessionRow` / `.mp-nav-btn`） | 撤销待判 |
| 超时 | 页面 `load` 之后 30 秒内**一条健康证据都没有**；插件自报「超时兜底」后缩短为 10 秒 | 到期降级 |

实现分成三块，都不碰界面文本：

- 判据本身 `app/src/main/java/com/dsh/android/ui/WebClientHealth.kt`（纯逻辑、零 Android
  依赖，由 `app/src/test/WebClientHealth.test.mjs` 编译生产文件原文跑 13 条行为检查）；
- 计时与三类处置 `app/src/main/java/com/dsh/android/ui/WebClientWatchdog.kt`
  （`arm` / `disarm` / `reset`，规则是「允许提前，不允许推迟」，`MainActivity` 只做接线）；
- 自愈额度 `core/logging/src/main/java/com/dsh/android/log/RescueAutoRetry.kt`
  （自有 `rescue/auto-retry.json`，不碰 `state.json` 的 `since`）与
  `runtime/engine/.../BootProfilePlan.kt`（决定本次用哪个 profile、是否允许 L1 隔离）。

### 为什么废掉了「首帧正文里有 HARNESS」这条判据

1. **那是 `polls=1` 的快照，不是当前状态。** 第一次轮询发生在 `dsh-android-mobile`
   的 `apply()` 之后，官方启动页通常还在。真机（CDY-TN90 / 2026-11-12 15:13:55）：
   `onUiReady` 已在 `.750` 成功上报，`.870` 那条 console 行仍带
   `首帧正文="HARNESS Loading plugins…"` ⇒ **120 毫秒内**把一次本来健康的启动降级。
2. **用户会自己换启动动画 / 主题 / 加载文案。** 文案一改，这条判据对真正的卡死**永久失效**；
   换成本地化文案还可能在正常启动上继续误报。
3. **单帧文本证明不了「持续卡住」。** 判据必须是「一段时间内始终拿不到健康证据」。

### 第二条坑：`UiReady` 不是健康证据

桥的 `onUiReady()` 只能证明**插件活着**。真机实测（2026-11-12 15:57，把
`dsh-android-market/lib/client.js` 尾部加了一个 `)))`）：插件照常上报 `onUiReady`、
页面始终停在启动页、结构锚点一直是 **0**。所以 `EngineManager.UiStage.UiReady`
**不能**撤销待判窗口 —— 只有锚点 ≥ 1 才算「界面真的能用」。

### 第三条坑：降级层级会粘死，必须自带额度

`targetProfile` 只看 `RescueBoot.tier()`，而 tier 只在「用**正常** profile 起成功」时
复位 —— 一旦写进 `RESCUE`，之后每次启动都用救援 profile，那条复位路径**永远走不到**。
`RescueAutoRetry.shouldRetry()` 因此给了有上限的自愈额度：
`RESCUE` + 距 `since` ≥ 5 分钟（`MIN_AGE_MS`）+ 本周期自动重试 < 2 次（`MAX_ATTEMPTS`）
⇒ 本次故意用正常 profile 启动；启动成功则既有 `rescue.tier.reset` 把层级写回 `NORMAL`。
额度记在 `~/.dsh/rescue/auto-retry.json`（`{schemaVersion,attempts}`，tmp+rename 落盘），
层级不再是 `RESCUE` 时读取端自动归零，所以不需要额外的清理时机。

⚠️ **自愈那一轮必须让 `BootProfilePlan.retryNormal` 为真**，因为 tier 里躺着的是
**过期**值。否则 `maybeIsolateBadBundles()` 的入口闸门和启动前那次
「L1 可能刚改层级 ⇒ 不发车，改走救援 profile」的重判会立刻把这次重试推回救援：
真机现场 16:08 就是这样 —— `--profile web` 拉起 250 毫秒后被改回 `--profile rescue`，
2 次额度在 5 秒内消耗光，自愈完全无效。

### 真机验收（2026-11-12，CDY-TN90 / Android 10）

| 场景 | 期望 | 实测 |
|---|---|---|
| 正常冷启动 | 不降级 | 待判窗口 30s 开启 → 0.6s 后收到 `锚点=2` ⇒ 撤销，tier 保持 normal |
| 用户插件改坏（market client.js 加 `)))`） | 降级到救援 | `锚点=0` ⇒ 10s 窗口到期 ⇒ `tier=rescue`，改 `--profile rescue` 重启 |
| 修好之后 | 自动回正常 | 冷却到期 ⇒ `自动回正常 profile 重试（第 1/2 次）` ⇒ `层级已复位：rescue → normal`，`锚点=2` |
| 冷却期内重启（since 仅 90 秒） | 不进重试、不消耗额度 | 仍 `--profile rescue`，`auto-retry.json` 保持空 |

重采（2026-11-12 19:11–19:16，判据重写成 `WebClientHealth` + 看门狗 + 自愈额度之后）
逐条复核了上表三行与「冷却期内不重试」，原始记录在 `/tmp/dsh-judge/`
（`A-degrade.txt`、`B-selfheal.txt`）：改坏 `dsh-android-market/lib/client.js` 后
第 30 秒采样点已从 `normal` 变 `rescue`；还原并把 `since` 挪到 6 分钟前再启动，
日志出现 `自动回正常 profile 重试（第 1/2 次，距进入 rescue 已 377s，落盘=true）`
⇒ `降级层级已复位：rescue → normal`，`auto-retry.json = {"attempts":1}`。
