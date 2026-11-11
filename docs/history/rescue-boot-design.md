# 坏插件兜底方案（救援启动）

> **历史档案，不作现行要求。** 旧救援启动设计，原文记录的是当时未实现方案与历史现场，不代表当前实现、验收结果或开发要求。现行行为见[救援模式](../agents/rescue-mode.md)，验证流程见[测试手册](../runbooks/testing.md)。

> 目标：**用户装了坏插件时，DSH 也一定打得开，并且能让他自己的 AI 动手修好。**
> 前提：不要求兼容所有插件，也不要求自动修好 —— 只要求「一定开得起来」+「修得有条件」。
>
> 状态：设计方案（未实现）。本文所有「已核实」都带真机或源码证据；所有「假设」都列在 §9，
> 实现前必须先验证，不许当事实写进代码注释。

---

## 1. 问题：真机事实

### 1.1 现场

服务器 `39.98.163.161:56789` 上的诊断包，设备 某小米机型 / Android 16 (API 36)，
版本 `0.2.9-tooliso (18) system`（**本方案写作时的最新发布版**）：

| 包 | 时间 | 版本 | 结果 |
|---|---|---|---|
| `20261014-075236-824081c3.zip` | 07:52 | v18 | 引擎启动失败 |
| `20261014-075529-4ddbb96e.zip` | 07:55 | v18 | 引擎启动失败 |
| `20261014-075648-565214a6.zip` | 07:56 | v18 | 引擎启动失败 |
| `20261014-082400-eb33761e.zip` | 08:24 | v18 | 引擎启动失败 |
| `20261014-082435-0f937dde.zip` | 08:24 | v18 | 引擎启动失败 |
| `20261014-082616-a5d15471.zip` | 08:26 | v18 | 引擎启动失败 |

同一个 bootId 时段内**连续 8 次启动失败**（07:28 → 08:24），每次都是：

```
dsh: startup failed: 1 required plugin did not activate

Failed plugins (40):
typert-loader
Package: @deepseek-ai/dsh-typert-loader
AggregateError: typert-loader: 2 typert contributor(s) failed to register:
- typert-loader: dsh-extension-hub invocation "dsh-extension-hub#extensionHub/list" parameter codec has no create() factory
- typert: local endpoint "mcpManager/list" is already registered
```

该用户装了 **40 个第三方插件**（`@linxin666/*` 皮肤、`@dsh-trading/*`、`dsh-mall`、
`dshhub-market`、`@nanmicoder/dsh-plugin-market` …）。真正致命的不是这 40 个里任何一个
本身，而是其中某个**重复注册了 `mcpManager/list` 这个 endpoint**，把冲突灌进了
**核心插件 `typert-loader`**，而 core 插件的失败 = `required plugin did not activate`
= 引擎直接退出 = **App 完全打不开**。

### 1.2 现有的两层防护，为什么都没拦住

**第一层：`preflight()`（启动前体检，自动）** —— 跑了，而且跑得很勤（日志里 `启动前体检`
出现 110 次），但**放行了**。根因见 §2.1，这是本方案最关键的一处。

**第二层：安全模式（`enterSafeMode()`，用户点击）** —— **用户从没点过**。
真机证据：两个包里 `安全模式` 出现 **0 次**。
它已经会做正确的事（`stripThirdPartyBundles()` 摘掉全部第三方 bundle），
但入口是失败浮层上的一个按钮，而用户面对的是「白屏/打不开」，**根本不知道该点它**。

⇒ 结论：**不是缺少兜底能力，而是兜底能力放错了位置** ——
一个必需的自动动作，被做成了需要用户在故障状态下主动发现的按钮。

### 1.3 为什么这值得单独做成一件事

用户的原话诉求是「起码在有坏插件的时候也能打开 DSH，让他的 AI 帮他修复」。
这两件事必须**分开设计**：

- **「一定打得开」** 必须是**自动的、无条件的、不需要用户判断的**。
  用户打不开 App 时没有能力做任何诊断，一切要求用户操作的方案都会失败（§1.2 已证）。
- **「修好」** 只能是**AI 在能用的界面里慢慢做**。自动修不可行：坏法无穷
  （版本不兼容 / endpoint 重复注册 / 导入失败 / 依赖缺失 / 配置格式错），
  且**归因信息不足** —— 日志点名的是受害者（`typert-loader`），不是凶手。

---

## 2. 根因分析

### 2.1 判据错位：体检看的不是启动时真正会失败的那件事（已核实）

这是本方案要修的核心机制缺陷，属于本项目一直在记录的**「结论与它描述的对象不是同一个」**
缺陷族（到 2026-10-14 已确认 26 例，这是第 27 例）。

`preflight()` 的判断链（`EngineManager.kt` L4163-4225）：

```
runDumpConfig()                          ← 跑 node bin.js --profile web --dump-config
  ├─ exitCode != 0  → 从 stderr 解析被指认的文件名，隔离它
  ├─ exitCode == 0  → missingRequiredEntries(stdout)   ← 只看 4 个 id
  │                     REQUIRED_ENTRY_IDS = ["agent-loop", "webserver", "modules", "connection"]
  └─ suspects 为空  → 放行（不做任何事）
```

**`--dump-config` 是静态配置解析：它把插件加载进配置图，但不执行插件的 `apply()`。**
而 `typert-loader` 的失败恰恰发生在 `apply()` 里（`lib/index.js:344` 的
`Object.apply [as callback]`）。所以：

| 问题 | `--dump-config` 能看见吗 |
|---|---|
| patch 文件语法坏 / 引用了不存在的包 | ✅ 能（exitCode != 0） |
| 核心 bundle 缺失 ⇒ required 条目消失 | ✅ 能（4 个 id 里少一个） |
| **插件的 `apply()` 抛异常 ⇒ required plugin did not activate** | ❌ **看不见** |

而真机那 8 次失败，`dump-config` 的 exitCode 是 **0**，4 个 required 条目**全在**
（`typert-loader` 是第 5 个、不在 `REQUIRED_ENTRY_IDS` 里，而且它在配置图里"装上了"）。
于是 `suspects` 为空 ⇒ `return false` ⇒ 放行 ⇒ 引擎照死。

**这就是「体检通过」与「能启动」是两件事的地方。** 修复方向见 §4.2。

### 2.2 归因信息不足：日志点名的是受害者

`Failed plugins (40):` 里 40 项，绝大多数是 `skipping profile bundle`（版本不兼容，
**引擎自愿跳过、不致命**）。唯一致命的是 `typert-loader`，但它是**核心包**：

```
typert-loader: 2 typert contributor(s) failed to register:
  - dsh-extension-hub ... parameter codec has no create() factory
  - typert: local endpoint "mcpManager/list" is already registered   ← 真凶在这
```

第一项 `dsh-extension-hub` 是核心包；第二项 `mcpManager/list` 是**某个第三方插件**
注册的重复 endpoint，但**日志里没有任何一行说出那个插件的名字**。

⇒ **不能靠解析日志做到精确归因。** 任何「解析出坏插件名字然后摘掉它」的设计都是脆的。
本方案因此**不依赖精确归因**（§4.3 用二分法+基线，§4.4 把细粒度归因交给 AI）。

### 2.3 「40 个插件」本身也是问题的一部分

40 个第三方插件里，大量是同一类（`@linxin666/dsh-client-ui-skin-*` 就有 12 个皮肤）。
引擎逐个加载、逐个 skip，日志刷出上百行。这有两个后果：

1. **启动变慢**：8 次失败每次等待 17～22 秒（真机 `已等 22840 ms` / `21735 ms` / `17796 ms`）。
2. **信号淹没**：真正致命的那一行被 100+ 行 skip 噪声埋住，人和 AI 都难一眼看到。

---

## 3. 设计目标与不变量

### 3.1 目标（按优先级）

| # | 目标 | 判据 |
|---|---|---|
| G1 | 有坏插件时**一定打得开** | 用户不点任何东西，App 也能进到可用的界面 |
| G2 | 打开后**用户能看见发生了什么** | 界面明确告知「已进入救援模式 + 原因」，不静默 |
| G3 | 打开后**AI 能动手修** | 救援会话里 AI 能读日志、读写 profile、逐个试插件 |
| G4 | 用户数据**零损失** | 会话 / 凭据 / 工作区与之前完全一致 |
| G5 | 一切改动**可逆** | 只改名不删；用户能一键切回正常模式 |
| G6 | 好插件**不受牵连** | 修好后切回正常模式，被摘的插件配置原样恢复 |

### 3.2 不变量（违反即设计错误）

- **I1 救援启动本身必须比正常启动简单。**
  它不能引入任何新的失败点（不加载任何第三方插件、不做体检、不跑 patch 里的第三方层）。
  否则「救援」会变成第二个故障源。
- **I2 救援启动绝不碰 `$DSH_HOME` 里的会话 / 凭据 / 工作区。**
  只读它们，不写。唯一允许写的是它自己 profile 目录下的东西。
- **I3 自动降级必须幂等。**
  反复触发不能产生 `web.bak-<ts>` 的嵌套备份，也不能每次都改东西。
- **I4 降级态必须显式暴露。**
  界面上有常驻提示 + 一键退出。不许出现「用户以为这是正常模式」。
- **I5 自动动作不许删数据。**
  一律 `quarantine()`（改名）语义，与现有 `enterSafeMode()` 一致。
- **I6 不许把「体检通过」当成「能启动」。**
  任何地方都不得用 `dump-config` 的结论替代一次真实启动的结果（§2.1）。

---

## 4. 方案总览

### 4.1 分层降级阶梯

核心设计：把现在**二元的**「正常 / 安全模式」扩成**四级的自动阶梯**，
每一级只在前一级失败后进入，且每级都朝「更少的第三方插件」方向退。

```
L0 正常启动
   │  引擎启动成功 → 结束
   ↓ 失败（真实启动失败，不是 dump-config）
L1 自动隔离（新增）
   │  从真实启动失败日志里提取「可疑第三方插件」，只摘掉它们，重试一次
   │  成功 → 进入 L1 态（少数插件缺失，其余照常）
   ↓ 仍失败
L2 救援启动（新增，本方案主体）
   │  --profile rescue：一个干净的、不含任何第三方插件的 profile
   │  一定成功（I1）→ 进入 L2 态：完整 AI 可用，带常驻提示
   ↓ 用户在救援会话里修好了 / 或 L2 也起不来（极端）
L3 安全模式（已有，保留）
      推开一切 + 重解包。最后由用户点击，不作自动兜底。
```

**为什么 L2 用独立 profile，而不是「摘掉 web profile 的全部第三方 bundle」**：

| | 独立 `rescue` profile | 直接改 `web` profile |
|---|---|---|
| 用户配置 | **原封不动**（G6 天然满足） | 被改名隔离，用户看不出原来装了什么 |
| 可逆性 | 删一个目录就切回 | 要按 `.bak-<ts>` 恢复，容易漏 |
| 失败面 | 极小（模板生成的干净 profile） | 仍受 web profile 里其它坏东西影响 |
| 与 `enterSafeMode()` 的关系 | **互补**：一个是"另起炉灶"，一个是"收拾残局" | 重复 |

⚠️ L2 与 L3 不是替代关系。L3 解决的是「**连干净 profile 都起不来**」
（node 损坏、engine/ 被改坏、`$DSH_HOME` 结构坏），那时只有重解包能救。

### 4.2 修掉判据错位（G1 的前提，必须先做）

**不加新功能，先把 `preflight()` 的判据补全。** 三处改动：

**(a) 把「真实启动失败」纳入判据。**
现在的流程是「体检 → 启动」。补成「体检 → 启动 → **启动失败则解析失败原因** → 决定是否降级」。
关键是失败原因的解析入口必须是**真实启动的 stdout**，而不是 `--dump-config` 的输出。

判据（严格、可机械验证）：

```
启动失败 ⇔ stdout 里出现 /^dsh: startup failed: \d+ required plugin did not activate/m
```

⚠️ 不要用「有 `Failed plugins` 就是坏」——那 40 项里绝大多数是正常的 skip（§2.3）。
真机反例：`Failed plugins (40)` 但只有一个致命，且引擎**有时**仍能起来
（该设备 07:14 就成功启动过两次，`引擎就绪` 出现 2 次）。

**(b) 体检结论必须区分「静态通过」与「运行期未验证」。**
现在 `preflight()` 返回 `false`（没东西可修）与「体检通过」在调用方看来是同一件事。
改成三态：`Clean`（静态干净）/ `Repaired`（修了东西）/ `Unavailable`（跑不起来），
并**新增一态 `StaticOnly`**：静态干净 ≠ 已验证。Telemetry 里要能分开计数。

**(c) 启动失败后，把引擎 stdout 的尾部落盘。**
现在这个信息只在 logcat 和 `LogStore` 里；降级决策需要它在**下次启动前**可读。
用 `$DSH_HOME/rescue/last-boot-failure.json` 存：
`{bootId, exitCode, tailLines[], failedPluginNames[], requiredCount, at}`。

### 4.3 L1 自动隔离：不依赖精确归因

**核心技巧：不猜哪个插件坏，用「基线 + 二分」验证。**

前提（**必须实测，见 §9 假设 A1**）：把**所有**第三方 bundle 摘掉后，引擎一定能起来。
这是整个方案的地基 —— L1 和 L2 都建立在它上面。

算法（全部基于真实启动，每次约 20 秒）：

```
bundle_list = profile.dsh.profile.bundles 里的非 @deepseek-ai/* 项
if bundle_list is empty:  → 不是插件问题，交给 L3
基线: 全摘 → 启动
  成功 → 基线成立，进入二分
  失败 → 不是插件问题（或不止插件问题），跳到 L2
二分（最多 ⌈log2(N)⌉ 次，N=40 时 6 次 ≈ 2 分钟）:
  维护「保留集 keep」，初始 = 全部
  每轮把 keep 对半分，摘掉一半 → 启动
    成功 ⇒ 被摘的那一半里没有致命的 → 换另一半
    失败 ⇒ 致命的在被摘的那一半里 → 收窄
  收敛到最小致命集合 → 用 quarantine 摘掉它 → 写回 bundle_list
```

**成本控制**（重要，不能无脑做）：

- 只在**连续失败 ≥2 次**时才启动二分（单次失败可能是系统回收，
  与现有 `MAX_ENGINE_RESTARTS = 2` 的口径一致）。

  ⚠️ **不是「同一个 bootId 内连续 ≥2 次」—— 那个条件永远不成立（2026-10-14 实测证伪）。**
  原文写的是「同一个 bootId 内」，但 `BootState.begin()` 在**每次进程启动时**都生成
  新的 `bootId`（`boot-state.json` 每次启动覆盖写），所以「同一个 bootId 内失败两次」
  在物理上不可能发生。

  真机证据（那台 Xiaomi 的 30 个诊断包、共 8 次 `startup failed`）：把它们按
  `manifest.json` 的 `bootId` 分组，结果是

  ```
  070347 → b-…f543   失败 0
  072356 → b-…68fd   失败 1
  074524 → b-…14e2   失败 1
  075236 → b-…a870   失败 2
  …（每个 bootId 各出现一次，失败计数 3,4,5,6,7,8 全是 engine.log 的**累积**行号）
  ```

  即 **8 次失败分布在 8 个不同的 bootId 里，没有任何一个 bootId 里有 2 次失败**。
  按原文的判据，L1 在这台真机上**一次都不会触发** —— 而它正是唯一需要 L1 的设备。

  ⇒ 正确的判据是**跨启动的连续失败计数**：`RescueBoot` 自己在
  `last-boot-failure.json` 里累加 `consecutiveFailures`（`recordFailure` 时 +1，
  真实启动成功 `clearFailure` 时归零）。语义与要判断的事完全一致，且不依赖
  `bootId` 的生命周期。

  ⚠️ **不要改用 `BootState.consecutiveUncleanCount` 代替**：那个计数的语义是
  「上次**未正常退出**」（异常死亡、被系统回收都算），与「**启动就起不来**」
  不是同一个总体 —— 混用正是本项目缺陷族（「结论与它描述的对象不是同一个」）
  的典型形态。
- 二分总时长上限 **3 分钟**；超时就放弃，直接跳 L2。
- 二分的每次尝试都必须**有超时**（复用 `READY_TIMEOUT_MS`，但降到 60 秒 ——
  失败路径实测 17～22 秒就返回了，60 秒足够且不拖垮体验）。

#### ⚠️ L1 的**每一条**「我解决不了」路径都必须推进到 L2（实现期发现的漏洞）

原文只写了「失败 ⇒ 跳到 L2」，但**没有说清有哪些路径会走到"失败"**。实现时
按原文逐条写，结果写成了一堆**各写各的** `return null`，其中一条的日志写着
「不是插件问题，直接交 L2」而代码**只是返回了** —— `tier` 于是停在 `NORMAL`，
下一次启动又从头跑一遍 L1、又放弃，**永远到不了 L2，也永远到不了 L3**。

这正是本项目缺陷族的又一例：**日志声称的动作没有发生**。危害比"没做"更大 ——
排查的人读到「直接交 L2」会以为已经交过去了。

L1 一共有**七条**「我解决不了」的路径，全部必须做同一件事（把 tier 记成 `RESCUE`）：

⚠️ **不要把这七条与「不该做」的四道门混为一谈**。四道门（`tier != NORMAL`、
`lastFailure() == null`、`consecutiveFailures < 2`、`activeEnv` 为空）是
**"这次不该跑 L1"**，返回 null 是对的 —— 那时问题不是"L1 解决不了"，
而是"还没轮到 L1 或 L1 跑不了"。下面七条才是**"跑了/想跑，但解决不了"**。

| # | 路径 | 原文怎么说的 |
|---|---|---|
| 1 | 引擎没报 `required plugin did not activate`（第③道门不过） | 「不是插件问题，交给 L3」 |
| 2 | 清单里没有第三方 bundle | 「`bundle_list is empty` → 交给 L3」 |
| 3 | `profiles/web/package.json` 缺失 | 原文未提 |
| 4 | 清单 JSON 解析失败（清单本身已坏） | 原文未提（那其实是 L3 的活） |
| 5 | 备份清单失败（绝不覆盖用户唯一一份原件） | 原文未提 |
| 6 | 全摘之后仍起不来（基线失败） | 「不是插件问题，跳到 L2」 |
| 7 | 二分没收敛 / 预算耗尽 | 「超时就放弃，直接跳 L2」 |

⇒ **实现要求**：这七条路径必须调用**同一个** `escalateToRescue(reason)`，
不许各写一遍 `RescueBoot.setTier(...)`。各写一遍的写法已经证明会漏
（本次就漏了 1、3、4、5 四条）—— 结构上共用一个出口，这类"漏了一条"
的缺陷才不可能再发生。

**实现现状**（2026-10-14 真机验证）：路径 1 与 6/7 已在
`maybeIsolateBadBundles` 里各自接上；路径 2/3/4/5 在 `isolateBadBundles`
返回 null 或空 `removed` 时由上层统一收口，全部走 `escalateToRescue`。
路径 2/6/7 返回的是**非 null** 的 `IsolationResult(removed = 空)`，
由「`removed.isEmpty()` ⇒ 降 L2」那一段接住 —— 也就是说收口点有两个
（`null` 与 `removed 为空`），**两者都必须接**。

⚠️ 路径 4 有个附加判断：**清单 JSON 坏了**说明坏法超出"某个插件致命"，
那种情况 L1 的二分（它要读写这个清单）根本无法进行，理论上该直接进 L3。
但**不要**在这里自动进 L3 —— L3 是破坏性的，而 §4.5 的门槛（连续 3 次失败）
还没满足。正确动作是**升到 L2**（非破坏性）把界面先给用户，让计数继续累积，
由 §4.5 那道门决定要不要进 L3。

#### ⚠️ `bundleFilter` 不能靠「第二个 profile 名」实现

二分与 AI 试启动都需要「只加载一部分插件」。直觉做法是造一个临时 profile
目录，但**那是错的**，而且错得**不会报错**：

`dsh-app-boot` 的 `resolveBundleDir()` 只在两个锚点找包 —— dsh 安装目录与
`<profileDir>/package.json`。第三方包住在 `profiles/web/node_modules/`，
换一个 profile 名就**解析不到**；而 `loadProfileDirectory()` 对每个 bundle
都是 try/catch，解析失败只是被推进 `skippedBundles` —— **引擎照常启动**。

于是探测会报「能起来」，而真实的 web profile 根本起不来：**假成功**，
而且症状是"探测说修好了、用户说没好"，极难归因。

⇒ **正确做法**：**临时重写 `profiles/web/package.json`** 本身（改成
`核心 + 想保留的那些`），跑完探测后**在 `finally` 里逐字节还原**。
这样 `node_modules` 的解析路径与真实启动完全一致，结论才可信。

⚠️ 备份用 `.bak-probe` 后缀，与 L1 的 `.bak-l1` **刻意区分**：前者是瞬时的、
成功即删；后者是持久的、是用户的恢复点。两者同名会让人分不清哪个能删。

⚠️ 还原必须**无条件**（`finally`），包括探测抛异常、超时、被取消时。
一个"探测完没还原"的 bug 会让用户下次启动时插件莫名消失。

⚠️ **二分会失败的情况**（必须写进文档，否则实现者会以为它总能work）：
如果存在**多个致命的第三方插件**，二分仍然能收敛（每次失败只收窄一侧，
最终逐个被找到），但轮数是 `O(k·log N)` 而不是 `log N`。所以**必须有总时长上限**。

⚠️ **二分的副作用**：每次尝试都是一次真实启动，会往 `crash/` 写现场文件。
必须给二分期间产生的现场打标记（文件名前缀 `probe-`），否则会污染用户的崩溃历史
（这正是本项目反复踩的坑：「结论与它描述的对象不是同一个」）。

#### ✅ 实现期更正：这个副作用**不存在**，所以不需要 `probe-` 前缀

真机核对（2026-10-14）：`probeBoot()` 起的是一个**自己持有的**一次性进程，
它**不调用** `CrashRecorder.recordAnomaly()`、**不写** `events.jsonl`、
也不写 `rescue/last-boot-failure.json`（探测失败**不是**用户的启动失败，
所以刻意不调 `recordFailure`）。它只把引擎 stdout 收进内存里供判读。

⇒ 二分跑 6 轮也**不会**在 `crash/` 里留下任何文件，用户原有的现场文件
「一个不多一个不少」。原文要求的 `probe-` 前缀**没有作用对象**，
按它实现会变成一段"看起来在防什么、其实什么都没防"的死代码
（实现时确实先写了 `put("DSH_PROBE_MARK", "probe")` 与一句声称它会打前缀的注释，
核对后删掉了 —— 那是本缺陷族的又一例：**注释描述了一个不存在的机制**）。

⚠️ 但**验收标准仍然要保留**（§7.2 第 7 条）：判据是「跑完二分后 `crash/` 里的
文件数与跑之前相同」。它现在的意义不是"检查前缀打对了"，而是
**回归防护** —— 万一将来有人在探测路径上加了崩溃记录，这条会立刻失败。

⚠️ 同理，`bundleFilter` 的实现**不是**"造一个临时 profile"，而是
**临时重写 `profiles/web/package.json` 本身**（理由见 §4.3 末尾：
换 profile 名会让第三方包解析不到，而解析失败只是被静默 skip ⇒ 假成功）。

### 4.4 L2 救援启动：方案主体

#### 4.4.1 机制

```
node <engine>/bin.js --profile rescue --no-open
```

- profile 名 `rescue`，目录 `$DSH_HOME/profiles/rescue/`，**由引擎按模板初始化**（假设 A2）。
- 不含任何第三方 bundle ⇒ 不受用户插件影响（I1）。
- **共享 `$DSH_HOME`** ⇒ 凭据、会话、工作区、日志都在（G4）。
  ⚠️ 这一点是 L2 能"让 AI 修"的关键：AI 既能看到出问题的 `web` profile，
  又是在一个**能正常工作的**环境里操作它。

#### 4.4.2 移动端适配必须以 L2 为第一等公民

App 注入的东西必须对 `rescue` profile **同样生效**，否则救援界面是废的：

| 注入物 | 现在挂在 | L2 必须 |
|---|---|---|
| `mobile-ui`（移动端 UI 适配） | web profile 的 overlay patch | ✅ 必须有，否则窄屏不可用 |
| `sandbox.patch.yml`（沙箱 runner） | `$DSH_HOME/` 全局 | ✅ 已经是全局的 |
| 6 个工具插件（logs / shell / bubble / uiagent / statusbar / root） | `$DSH_HOME/<xxx>-tool/` + overlay | ✅ logs 必须有（AI 要靠它读日志） |
| `preset-skills.zip` | `$DSH_HOME/` | ✅ 已经是全局的 |

⚠️ 实现要点：现在这些 overlay 的生成函数都**硬绑定 `PROFILE_NAME`（= "web"）**
（`EngineManager.kt` 多处 `File(dshHome, "profiles/$PROFILE_NAME")`）。
需要抽出「生成 overlay 到指定 profile」的参数化版本，并**对两个 profile 都生成**。
这是本方案**改动面最大的地方**，也是最容易漏的地方。

⚠️ 漏了的症状是静默的：救援界面能打开，但窄屏错乱 / 没有 logs 工具，
用户和 AI 都会以为「救援模式就是个残废界面」。**必须逐项验收**（§7）。

#### 4.4.3 救援会话里的 AI 怎么修

用户在 L2 里拿到一个**完整可用**的 DSH（界面正常、AI 正常、凭据正常），
系统提示词里注入救援上下文，AI 手上有：

| 素材 | 位置 |
|---|---|
| 真实启动失败日志 | `$DSH_HOME/logs/engine.log` + `rescue/last-boot-failure.json` |
| 被隔离的原件 | `$DSH_HOME/profiles/web/package.json.bak-<ts>` |
| 当前 profile 装了什么 | `$DSH_HOME/profiles/web/package.json` |
| 崩溃现场 | `$DSH_HOME/logs/crash/*-engine-start-failed.txt` |
| 诊断包自检结论 | 设置 → 日志 → 导出诊断包 |

AI 的修复循环（**教给 AI**，写进救援 skill）：

```
1. 读 last-boot-failure.json + engine.log，确认失败模式
2. 读 web/package.json，列出第三方 bundle
3. 逐个（或分组）把 bundle 加回一个「试验 profile」→ 触发一次启动 → 看结果
   ⚠️ AI 不能直接重启 App；改造见 §4.4.4
4. 定位到坏插件后：给出建议（禁用 / 升级 / 换替代 / 联系作者）
5. 写「待切换」标记 → App 切回正常模式
```

⚠️ **第 3 步是难点**：AI 在会话里改配置后，需要一次**真实启动**才能验证。
让用户手动重启 App 太慢（每轮 20 秒 + 人工）。解决办法见下。

#### 4.4.4 救援信封（让 AI 能触发重启）

复用本项目已经用了 4 次的**文件信箱**范式（`bubble-cmd.json` / `ui-cmd.json` /
`logs-cmd.json`），新增 `$DSH_HOME/rescue-cmd.json`：

```json
{"id": 7, "action": "try-boot", "profile": "web", "bundleFilter": ["@linxin666/..."] }
```

App 侧 200 ms 轮询，执行后写 `rescue-result.json`：

```json
{"id": 7, "ok": false, "requiredDidNotActivate": 1, "exitCode": 0,
 "tail": ["dsh: startup failed: 1 required plugin did not activate", "..."]}
```

`action` 取值：
- `try-boot`：用指定 profile 试启动一次（**只跑到达 URL 或失败**，不切界面）
- `switch-back`：切回正常 profile（`web`），重启 App 界面
- `switch-rescue`：切回救援 profile

⚠️ 这套信箱的幂等语义必须与现有那几个一致：**`id` 必须是不重复的整数**，
App 处理过的最大 id 要持久化（现有 `LogCommandBridge` 用的就是这个模式，
`id` 类型是 Long，写错了不报错、静默丢弃 —— 本项目踩过）。

⚠️ `try-boot` 是**有副作用**的（会真的起一个引擎进程、写引擎自己的日志）。必须给它加：
- 单独的配额（**实现定稿：一次救援会话最多 20 次**，记在 `rescue/probe-count.json`）
- 配额用尽时返回**明确的**「配额用尽」而不是静默失败（§7.2 第 16 条）

⚠️ ~~试启动产生的现场打 `probe-` 前缀~~ —— **实现期更正：探测不产生崩溃现场**，
所以不需要前缀（理由见 §4.3 末尾「实现期更正」）。`try-boot` 唯一的持久副作用是
配额计数，以及它在**确实拿到 URL** 时会打印一行引擎日志。

#### 4.4.5 救援态的用户提示（I4）

界面上必须常驻、清晰、不能误导：

```
⚠️ 救援模式
   上次启动因为插件问题没能起来，已用最小配置启动。
   AI 可以帮你找出并修好那个插件 —— 直接问它就行。
   [退出救援模式]  [查看上次失败详情]
```

⚠️ 不许把「救援模式」做成一句一闪而过的 toast。
用户下次打开 App 可能已经忘了这件事，而他的插件仍然是坏的。

### 4.5 L3 安全模式：保留，但补一条自动触发

现在 `enterSafeMode()` **只能用户点**（§1.2 证明这会失败）。
补一条：**L2 也起不来时**（极端情况）自动进入 L3，并在浮层上说明发生了什么。

但要小心：L3 是**破坏性**的（重解包 15 秒 + 推开配置）。
现有的克制（"拿不准就别动"）是对的，不能因为要好用就把它变成默认行为。

⇒ 折中：**只在「连续 3 次真实启动失败且 L1/L2 都已尝试过」时自动进 L3**，
并把改动写成 `Log.w` 明确记录「本次是自动触发的安全模式」（而不是用户点的）——
现有的 `[preflight]` 与 `enterSafeMode` 已经注意了这个区分（L4434 的注释），
自动触发必须新增第三种标记。

#### 判据：两个条件，缺一不可（实现定稿）

| # | 条件 | 为什么 |
|---|---|---|
| ① | `consecutiveFailures >= 3` | 1~2 次可能是偶发（系统内存紧张导致引擎被 OOM、端口被上次残留进程占着），那时推开用户的插件配置是**过度反应** |
| ② | `tier == RESCUE` | **L1 与 L2 都已经试过**。`tier` 只有在「L1 二分没收敛 / 不是插件问题」时才会被升成 `RESCUE`，所以 `tier == RESCUE` **本身就是**「L1 试过且失败」的机器可判证据；而在救援 profile 下再失败，等于 L2 也试过了 |

⚠️ 条件②**不能**写成 `tier != NORMAL`：`ISOLATED` 态意味着 L1 **成功**摘掉了
致命插件 —— 那时该做的是让用户用着（哪怕少了几个插件），而不是把他剩下的插件
**也**推开。判据必须精确指向「L1 已经没辙了」这个状态。

⚠️ 条件②**同时**覆盖了「已经在安全模式里」（`SAFE_MODE != RESCUE`），所以
**不要**再补一条 `tier != SAFE_MODE` —— 那是永远不成立的死判据（写了一条
与它要拦的对象对不上的条件，又是本缺陷族的形态）。

⚠️ **不能只看 `consecutiveFailures`**：装了坏插件的用户，L1 每次都能成功隔离
（`tier == ISOLATED`），失败计数会因 `clearFailure` 归零 —— 那种情况本来就不
需要安全模式。加上条件②才不会误伤。

⚠️ **`enterSafeMode()` 必须把 `tier` 记成 `SAFE_MODE`**（实现时补上的）。
不记的话有两个真实后果：① 横幅会说谎（仍显示"救援模式"而实际跑的是安全模式，
违反 I4）；② 条件②一直成立，**每次启动都再推开一次配置并重解包 15 秒**。

#### 三种日志标记（必须分得清）

| 标记 | 谁触发的 |
|---|---|
| `[preflight]` | 启动前体检自动修（只在能精确指认坏文件时） |
| 无前缀 | **用户**点浮层上的「安全模式」 |
| `[rescue-auto]` | **本节的自动触发** |

分不清的后果是事后复盘把「系统自动推开了用户的配置」误读成「用户自己点的」——
两者对用户的责任完全不同。

#### 真机验证（2026-10-14，CDY-TN90 / Android 10）

把 `profiles/rescue/package.json` 的 bundles 改成不存在的包（让救援也起不来），
清空失败记录，连续启动：

```
第 1 轮: [rescue-auto] 不自动进安全模式：连续失败仅 0 次（需 ≥3）
         [rescue-auto] 不自动进安全模式：连续失败仅 2 次（需 ≥3）
第 2 轮: rescue：启动层级 = safemode（安全模式）
         [rescue-auto] 安全模式：改动=true，重启引擎
         → 引擎就绪 ✅（App 打开了）
第 3 轮: L1 隔离：当前层级 安全模式，跳过      ← 幂等，不重复重解包
```

即：**连续失败累计到 3 ⇒ 自动进 L3 ⇒ App 真的打开了**，且此后不再重复触发。

---

## 5. 数据结构

### 5.1 `$DSH_HOME/rescue/`（新增目录）

```
rescue/
  last-boot-failure.json    每次真实启动失败后覆盖写（§4.2c）
  state.json                当前启动层级 + 时间 + 原因（I4）
  probe-count.json          试启动配额（§4.4.4）
  attempts/                 L1 二分法的每轮结果（供 AI 与人复盘）
    0001.json
    0002.json
```

`state.json`：

```json
{
  "schemaVersion": 1,
  "tier": "rescue",            // normal | isolated | rescue | safemode
  "since": "2026-10-14T08:26:00+08:00",
  "reason": "连续 2 次启动失败：required plugin did not activate",
  "removedBundles": ["@linxin666/dsh-client-ui-skin-xp"],
  "restorePoint": "profiles/web/package.json.bak-20261014-082601"
}
```

⚠️ `schemaVersion` 必须有（诊断包 `manifest.json` 已有这个范式），
否则将来加字段会让老版本 App 读崩。

### 5.2 与诊断包的关系（必须接上）

诊断包（`DiagnosticBundle`）现有的 6 个检查**没有一个会报「处于救援模式」**。
必须新增第 7 个检查 `boot-tier`：

- `ok`：正常启动
- `warn`：L1 隔离态（有插件被摘）
- `fail`：L2/L3（救援/安全模式）—— 用户的 App 是残的

⚠️ 这条直接对应本项目的**第 26 例**（「App 完全打不开」在 6 个检查里没有任何一个判 fail）。
不加这条，就等于一边修第 26 例、一边制造第 27 个同族缺陷。

---

## 6. 实现顺序（每步独立可验）

| 阶段 | 内容 | 独立价值 | 依赖 |
|---|---|---|---|
| P0 | 判据补全：真实启动失败识别 + `last-boot-failure.json` + 三态化 | **单独就有价值**：让「体检放过坏插件」这件事第一次可见 | 无 |
| P1 | L1 自动隔离（基线 + 二分） | 40 插件的用户里，多数能自动恢复 | P0；假设 A1 |
| P2 | L2 救援 profile（含 overlay 参数化） | 保底「一定打得开」 | 假设 A2 |
| P3 | rescue-cmd 信箱（try-boot / switch-back） | 让 AI 能真正修 | P2 |
| P4 | 救援 skill + 系统提示词注入 | 让 AI 知道怎么修 | P3 |
| P5 | 诊断包 `boot-tier` 检查 | 让问题在报告里可见 | P0 |
| P6 | L3 自动触发（严格条件下） | 极端情况的最后兜底 | P1+P2 |

**P0 与 P5 应当先做**：它们不引入新的启动路径（风险最低），
且立刻让「有坏插件」这件事从**不可见**变成**可见**。P1-P4 才是新机制。

### 6.1 实现状态（2026-10-14）

| 阶段 | 状态 | 主要落点 |
|---|---|---|
| P0 | ✅ 已实现并编译 | `log/RescueBoot.kt`（新增）；`EngineManager` 的 `recordFailure`/`clearFailure`/三态 `PreflightResult` |
| P1 | ✅ 已实现并编译 | `EngineManager.isolateBadBundles` / `maybeIsolateBadBundles` / `escalateToRescue` / `probeWithBundleFilter` / `buildEngineArgs` |
| P2 | ✅ 已实现并**真机验证** | `ensureRescueProfile()`；`rescueBanner`（`activity_main.xml` + `MainActivity.refreshRescueBanner`/`exitDegradedMode`/`showDegradedDetail`） |
| P3 | ✅ 已实现并**真机验证** | `RescueCommandBridge.kt`（新增）：`status`/`try-boot`/`switch-rescue`/`switch-back` |
| P4 | ✅ 已实现并**真机验证** | `EngineManager.RESCUE_PROMPT` + `ensureRescuePrompt()`（写 `$DSH_HOME/AGENTS.md` 的 `dsh-android:rescue` 段） |
| P5 | ✅ 已实现并编译 | `log/DiagnosticBundle.kt` 的 `boot-tier` 检查 |
| P6 | ✅ 已实现并**真机验证** | `EngineManager.shouldAutoEnterSafeMode()` + `MainActivity.autoEnterSafeMode()` |

⚠️ **P4 原计划写「救援 skill」，实现时改成了「系统提示词注入」**，理由：
skill 的投递走 `ensurePresetSkills()` + `preset-skills.zip` + `PRESET_SKILLS_VERSION`
版本戳 —— 那是一条**需要重新打包资产、且判据是版本戳字符串**的路径
（AGENTS.md 明确记着「改了内容不改常量 ⇒ 老设备永远拿不到新 skill」）。
而降级态的说明本质上是**一句"你现在处于 X、可以这样修"的上下文**，
天然属于系统提示词，且 `AGENTS.md` 的 `BEGIN`/`END` 段机制
（`ensurePrivilegedPrompt` 已在用）**零资产、零版本戳、每次启动现写** ——
更可靠，且不会因为漏 bump 一个常量而静默失效。

⚠️ **仍未做**：`versionCode`/`versionName` 未为本次功能递增；未提交；未发布。

---

## 7. 验收标准

每条都必须**在真机上跑到**，不接受「代码看起来对」。

### 7.1 P0

1. 手工把 `profiles/web/package.json` 的某个第三方 bundle 改成一个会在 `apply()`
   抛异常的包 → 启动 → `rescue/last-boot-failure.json` 里的
   `requiredCount` = 1，`tailLines` 含 `startup failed`。
2. **阳性对照**：正常启动时该文件**不被写**（或写 `requiredCount: 0`）。
3. 跑一次 `--dump-config`：exitCode 0、4 个 required 条目全在 —— 证明判据错位真实存在，
   且新判据**不依赖**它。

### 7.2 P1

4. 用 40 个第三方 bundle 的**真实配置**（从那台设备的诊断包里取，见 §10）制造二分场景：
   注入 1 个致命插件 → 二分在 ≤6 轮内找到它，且**只摘掉它一个**。
5. 注入 2 个致命插件 → 能收敛（轮数允许更多），或按总时长上限优雅放弃并跳 L2。
6. 摘掉的 bundle 只出现在 `removedBundles` 里；原 `package.json` 有 `.bak-<ts>`；
   再触发一次降级**不产生第二个 `.bak`**（I3 幂等）。
7. ~~二分期间产生的崩溃现场都带 `probe-` 前缀~~ **判据改为**：跑完二分后
   `$DSH_HOME/logs/crash/` 里的文件数与跑之前**相同**，用户原有的现场文件
   **一个不多一个不少**。（实现期核对：探测根本不写现场，所以正确实现天然满足；
   这条现在是**回归防护** —— 将来若有人在探测路径上加了崩溃记录，它会立刻失败。）

### 7.3 P2

8. 把 `profiles/web/` 整个删掉（模拟最坏情况）→ 仍能从 `rescue` 起，界面可用。
9. **窄屏验收**：救援界面在 360×640 下布局正确（移动端 UI 插件生效）。
10. 救援会话里 `logs` / `shell` / `bubble` / `uiagent` 工具**都在**（`output` 声明的坑已修）。
11. 救援态的会话列表、凭据、工作区与正常态**一致**（G4）。
12. 引擎日志里能看出启动用的是 `--profile rescue`。

### 7.4 P3

13. AI 在救援会话里写 `rescue-cmd.json` → App 在 ≤500 ms 内执行并写回 `rescue-result.json`。
14. `id` 重复的请求被忽略（幂等）；`id` 为字符串时**明确报错而不是静默丢弃**
    （现有信箱踩过这个坑）。
15. `try-boot` 试启动失败时，`tail` 里带回真正的 `startup failed` 行。
16. 配额用尽后 `try-boot` 返回明确的「配额用尽」，不静默失败。
17. `switch-back` 后 App 切回正常 profile，界面重启且**救援提示消失**。

### 7.5 P5

18. L2 态导出诊断包 → `diagnosis.json` 里 `boot-tier` 为 `fail`，且 `headline` 不再是
    「未发现异常」。
19. L0 态导出 → `boot-tier` 为 `ok`（**阳性对照**，防止恒为 fail）。

### 7.6 P6

20. 连续 3 次真实启动失败 + `tier == RESCUE` → 自动进安全模式，且日志带 `[rescue-auto]`。
21. **阳性对照**：`tier == ISOLATED` 时**不**自动进（L1 成功了就不该推开剩余插件）。
22. **幂等**：已在 `safemode` 时不再重复触发（否则每次启动重解包 15 秒）。

### 7.7 端到端（最重要的一条）

23. 复刻真机场景：同一个设备装 40 个插件 + 注入导致 `mcpManager/list` 重复注册的插件
    → **用户什么都不点** → App 打开 → 界面显示救援提示 → 对 AI 说「帮我找出坏插件」
    → AI 定位并给出修复建议 → 用户按建议处理后切回正常模式 → 插件全部恢复。

---

## 7.8 真机验证记录（2026-10-14，CDY-TN90 / Android 10 / API 29）

设备上没有第三方插件（`profiles/web/package.json` 的 `dependencies` 为空、
只有 2 个核心 bundle），所以**验证方式是把降级态手工置入**，逐条走链路。
每条都给出了实际观测到的日志/文件内容 —— 不接受「代码看起来对」。

| 验收项 | 怎么验的 | 实测结果 |
|---|---|---|
| 正常路径不回归 | 启动 App | `启动前体检结论：静态干净` → `降级态说明: 层级=normal，注入=false` → `引擎就绪` ✅ |
| **A2：救援 profile 能起来** | 置 `state.json` 为 `rescue` 后启动 | `以救援 profile 启动（--profile rescue）` → `救援启动：跳过启动前体检（I1）` → `引擎就绪: http://127.0.0.1:3080/...` ✅ **A2 成立** |
| P4 提示词注入（降级态） | 读设备上 `$DSH_HOME/AGENTS.md` | `<!-- dsh-android:rescue BEGIN -->` 段存在，正文是「你正处于降级启动模式…」三条硬约束 ✅ |
| P4 提示词注入（正常态） | 同上 | 段存在但正文是「## 启动状态：正常」——**显式否定**，不是删段 ✅ |
| P3 `status` | 写 `rescue-cmd.json` `{"id":1,"action":"status"}` | `{"id":1,"ok":true,...,"tier":"rescue","rescueProfileAvailable":true,"probesUsed":0,"probeQuota":20}` ✅ |
| P3 `try-boot` | `{"id":2,"action":"try-boot"}` | `"ok":true,"message":"试启动成功：引擎打印了 URL（profile=web）"`，`tail` 里真有 `dsh web: http://127.0.0.1:45339/?token=...` ✅ |
| P3 配额记账 | 读 `rescue/probe-count.json` | `{"handledId":2,"used":1,"quota":20}` ✅ |
| P3 `bundleFilter` 还原 | 先记录 `web/package.json`，发 `{"id":3,"action":"try-boot","bundleFilter":[]}` | 探测 `ok:true`；跑完**逐字节相同**；`profiles/web/` 里**没有** `.bak-probe` 残留 ✅ |
| P3 `switch-back` | `{"id":4,"action":"switch-back"}` | 回执「已切回正常模式」→ `state.json` 变 `normal` → `降级态说明: 层级=normal，注入=false` → `引擎就绪` ✅ |
| P2 常驻横幅（ISOLATED） | 置 `tier=isolated` + 2 个 `removedBundles`，截图 | 橙色横幅常驻，文案「⚠️ 已隔离可疑插件（…起）／启动时发现 2 个插件会让引擎起不来，已把它们改名隔离（没有删除），其余插件照常工作。／想让 AI 复查或恢复，直接问它就行。」+ 两个按钮「退出并重试」「上次为什么失败」；**App 正常打开** ✅ |
| **P6 自动触发** | 把 `profiles/rescue/package.json` 的 bundles 改成不存在的包（让救援也起不来）+ 清空失败记录，连续启动 3 轮 | 第1轮 `[rescue-auto] 不自动进安全模式：连续失败仅 0 次（需 ≥3）` → `仅 2 次`；第2轮 `[rescue-auto] 安全模式：改动=true，重启引擎` → `tier=safemode` → **`引擎就绪`** ✅ |
| P6 幂等 | 第 3 轮再启动 | `L1 隔离：当前层级 安全模式，跳过`，**没有**第二次 `[rescue-auto]`，没有重复重解包 ✅ |
| P6 阳性对照 | 见上表「P2 常驻横幅」那次：`tier=isolated`、`consecutiveFailures=0` | 日志里**没有** `[rescue-auto]` 触发 ✅ |

⚠️ **仍未在真机上验的**（设备不具备条件，如实记录）：
**A1（全摘第三方就能起）** —— 需要那台装 40 个插件的 Xiaomi，当前未连接。
所以 L1 的二分**从未在真机上跑过一次完整收敛**；它的正确性目前只有
等价算法验证（`l1_bisect_verify.py` 6 例 + `l1_ladder_verify.py` 6 例 + 
`p6_gate_verify.py` 14 例，全通过）+ 代码路径核对。**不要**把它当成已实测。
A1 若不成立，方案收缩为「只做 L2 救援启动」—— 那仍然满足「一定打得开」，
但 L1 会自动降级到 L2（`escalateToRescue` 已接好），不会卡死。

---

## 8. 风险与回滚

| 风险 | 后果 | 对策 |
|---|---|---|
| 二分法总在失败 ⇒ 每次启动 20 秒 | 用户觉得 App 卡住 | 总时长上限 3 分钟；期间界面上显示进度文案（「正在排查插件（第 2/6 次）」） |
| **救援 profile 起不来** | 本方案失效，退回 L3 | §4.5 的自动 L3；且 P2 的第一步就是先验证假设 A2 |
| overlay 只给 web 生成 | 救援界面残废（窄屏错乱 / 无 logs） | §7.3 的第 9、10 条列为**必过项** |
| 试启动污染用户崩溃现场 | 用户/我们的排查被带偏 | ~~`probe-` 前缀~~ **已由实现消除**：`probeBoot` 走的是自己起的一次性进程，**不调用 `CrashRecorder`、不写 `events.jsonl`**，所以根本不产生现场文件（见下） |
| 自动降级把好插件也摘了 | 用户功能丢失 | 只摘二分收敛出的最小集合；原文件 `.bak`；`state.json` 记录；一键恢复 |
| AI 在救援会话里改坏 web profile | 更糟 | 救援 skill 明确要求：**先备份再改**；且 `try-boot` 不直接改 web profile（用 `bundleFilter` 临时覆盖） |

回滚：本方案**不删任何数据**（I5），所以回滚 = 把 `rescue` 相关代码路径关掉
（`state.json` 里的 `tier` 手动置回 `normal` + 删 `$DSH_HOME/profiles/rescue/`）。

⚠️ 但要写清：**一旦 L1 摘过 bundle，用户的 web profile 就已经被改过了**
（有 `.bak`）。回滚代码路径**不会**自动恢复它 —— 用户要在界面上点「恢复插件配置」。
这个入口必须做，否则「可逆」只是理论上的。

---

## 9. 待验证假设（实现前必须逐条验证，不许当成事实）

| # | 假设 | 验证方法 | 若不成立 |
|---|---|---|---|
| **A1** | 摘掉全部第三方 bundle 后，引擎一定能起来 | 在 40 插件真机配置上手工全摘 + 启动 | **L1 的二分地基塌了**，只能直接跳 L2 |
| **A2** | ~~`--profile rescue` 首次启动会按模板初始化~~ | **2026-10-14 真机已验：不成立** —— 见 §9.1 | **已改为**：App 自己创建 rescue profile 的 `package.json`（已验证可行） |
| **A3** | rescue profile 与 web profile 共享 `$DSH_HOME` 下的凭据，AI 因此可用 | 真机在 rescue 会话里发一条消息 | 需要额外把凭据路径显式指向 |
| **A4** | 二分期间反复启动不会留下引擎残留进程（端口/特权身份） | 连续 6 次 `try-boot` 后 `ps` 检查 | 每次 try-boot 前加 stop + 端口检查 |
| **A5** | `bin.js` 支持 `--no-open` 且 `--profile <name>`（任意名） | 已在代码里用 `--profile web`；需确认任意名可用 | 见 A2 的备选 |
| **A6** | 40 个插件的 web profile 能**被 App 读出来**（不是靠解析日志） | 读 `profiles/web/package.json` 的 `dsh.profile.bundles` | 改读 `node_modules` 目录 |

⚠️ **A1 是全案地基**。它若不成立，本方案的形态要从「自动二分隔离」改成
「只做 L2 救援启动」（仍然满足用户的核心诉求，只是少了自动修一部分的能力）。

### 9.1 A2 真机验证记录（2026-10-14，CDY-TN90 / Android 10）

**结论：`--profile <自定义名>` 不会自动初始化 profile。原设计的假设不成立。**
但**替代方案已真机验证通过**，L2 因此仍然可行。

**（1）原假设被证伪。** 直接跑 `--profile rescue --dump-config`：

```
Error: dsh: profile "rescue" does not exist;
       create it with 'dsh plugin --profile rescue add <package>'
    at loadProfile (.../dsh-app-boot/lib/index.js:974)
```

根因（读 `dsh-app-boot/lib/index.js` 源码，L968-978）：

```js
const dir = resolveProfileDir(name, home);
if (!existsSync(join(dir, "package.json"))) {
  const template = PROFILE_TEMPLATES[name];
  if (template === void 0) throw new Error(`${binName}: profile ${JSON.stringify(name)} does not exist; ...`);
  initProfile(dir, template.bundles);      // ← 只有内置模板名才会走到这里
}
```

即：**只有 `PROFILE_TEMPLATES` 里有名字的 profile（如 `web`）才会被自动初始化**，
任意自定义名会直接抛错。所以 L2 **必须由 App 自己把 `profiles/rescue/package.json` 写出来**。

**（2）替代方案已验证可行。** 手工写入 `profiles/rescue/package.json`：

```json
{
  "name": "dsh-profile-rescue",
  "private": true,
  "dependencies": {},
  "dsh": { "profile": { "bundles": [
    "@deepseek-ai/dsh-base",
    "@deepseek-ai/dsh-web-app"
  ] } }
}
```

⚠️ 注意 `name` 字段是 `dsh-profile-rescue`（引擎的 `web` profile 用的是
`dsh-profile-web`，同构命名）。`bundles` 只列**两个核心 bundle**，与出厂 `web` 一致 ——
这是 §4.4.1 「不含任何第三方插件」的具体落实。

随后两步都真机通过：

| 验证 | 命令 | 结果 |
|---|---|---|
| 配置能解析 | `--profile rescue --dump-config` | ✅ 输出完整 entry 列表（1500+ 行，含 `tool-todo` / `tool-web` / `present` 等） |
| **真能起引擎** | `--profile rescue --no-open --port 0` | ✅ **`dsh web: http://127.0.0.1:44057/?token=…`** |

**（3）对方案的影响**：

- §4.4.1 的 `--profile rescue` 机制保留，但**必须新增一步**：首次进入 L2 前，
  若 `profiles/rescue/package.json` 不存在则写入上面的模板（幂等）。
  这一步是**新增的失败点**，按 I1 必须做到最简：只写一个 JSON、不联网、不做其它事。
  写失败 ⇒ 直接退 L3（安全模式），不要把用户卡在中间态。
- 模板内容建议**从出厂 `web` profile 的 bundle 列表派生**（而不是我这三个字段写死），
  以免引擎升级后核心 bundle 改名导致 rescue 也起不来。
  ⚠️ 但**不要读用户的 `web/package.json`** —— 那个文件可能正是坏的（§6 的 P0 场景）。
  正确来源是引擎自己的模板（`PROFILE_TEMPLATES`）或 engine 包内自带的出厂 profile。

**（4）顺带确认的两件事**：

- `--port 0` 可用（引擎自己挑空闲端口），L2 启动同样要带上它，
  避免撞上残留引擎进程（复用现有逻辑，见 `EngineManager` 的端口处理）。
- 测试后 `ps -A | grep -c 'bin.js'` = **0**，无残留进程 ⇒ **A4（反复启动不留残留）**
  初步成立，但二分场景要连启 6 次，仍需在 P1 实现时复测。

---

## 10. 素材与工具

**真机样本**（复刻场景用，服务器 `39.98.163.161:56789`，`/workspace/dsh-logs/incoming/`）：

- `20261014-082616-a5d15471.zip` —— v18，含完整 8 次失败记录 + 644 KB `engine.log`
- `20261014-082400-eb33761e.zip` —— 同上
- `20261014-082435-0f937dde.zip` —— v18，`diagnosis.json` 已正确报 `fail: 1`（第 26 例修复生效）

该设备 config（40 个第三方 bundle 的原始清单）可从
`profiles/web/package.json` 的 `dsh.profile.bundles` 取得 —— 这是复刻
§7.2 二分测试的**唯一可靠素材**，不要手搓。

**相关代码位置**：

| 关注点 | 位置 |
|---|---|
| 启动前体检 | `EngineManager.kt` L4163-4225 |
| required 条目判据 | `EngineManager.kt` L4365-4371（`REQUIRED_ENTRY_IDS` = 4 个） |
| `--dump-config` 执行 | `EngineManager.kt` L4471-4538 |
| 安全模式 | `EngineManager.kt` L4261-4294 |
| 摘第三方 bundle | `EngineManager.kt` L4308-4357 |
| 启动失败与重启计数 | `MainActivity.kt` L2013-2028（`MAX_ENGINE_RESTARTS = 2`） |
| 失败浮层 + 安全模式按钮 | `MainActivity.kt` L2190-2243 |
| 引擎启动失败记录 | `EngineManager.kt` L5203-5217 |
| 诊断包检查 | `DiagnosticBundle.kt`（6 个检查，需加第 7 个） |
| 信箱范式参考 | `LogCommandBridge.kt`（`logs-cmd.json` / `logs-result.json`） |

**服务器访问**：

```
ssh -i C:\Users\你的用户名\.ssh\id_ed25519_logsrv -p 56789 \
    -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null root@39.98.163.161
```

⚠️ 服务器**不解析** `manifest` / `completeness`，只是存包。所有统计要在本机或服务器上
现写脚本跑；不要指望服务器上有现成的索引。

---

## 11. 附：为什么不做另外几种方案

| 方案 | 为什么不做 |
|---|---|
| **patch 引擎，让 `typert-loader` 的 contributor 冲突不致命** | 改的是核心插件的语义（`required` 的判定权在上游 cordis）。引擎升级就失效（本项目已有 `patchFlockPlatform` / `patchAttachmentDurability` 两个先例，都是"锚点失配就跳过"）。而且它只治 `typert-loader` 这一种坏法，换成"导入失败"就又不管用了。**覆盖面太窄。** |
| **解析日志精确归因到具体插件** | §2.2 已证：日志点名的是受害者，不给凶手名字。基于它的实现必然是脆的。 |
| **自动卸载可疑插件** | 违反 I5（删数据）。且"可疑"判不准 ⇒ 会误删好插件。 |
| **每次启动都强制进救援模式** | 破坏正常路径的体验（每次都要重配插件），且掩盖问题而不是解决。 |
| **只把「安全模式」按钮做得更显眼** | §1.2 已证：用户面对打不开的 App 时不会去点任何按钮。**这是已失败的方案。** |
| **要求插件作者修** | 40 个插件的生态，我们控制不了。用户要的是"不管谁的插件坏了都能开"。 |

---

## 12. 一页总结

**问题**：用户装了 40 个第三方插件，其中一个重复注册 `mcpManager/list`，
把核心插件 `typert-loader` 拖挂 ⇒ 引擎退出 ⇒ App 打不开，**连续 8 次**。
已有的两层防护都没起作用：体检的判据（`--dump-config`）**看不见运行期失败**；
安全模式**用户从没点过**（他根本不知道该点）。

**方案**：把兜底从「一个要用户发现的按钮」改成「四级自动阶梯」。

1. **P0 修判据** —— 让「真实启动失败」第一次可见（静态通过 ≠ 能启动）。
2. **L1 自动隔离** —— 用「全摘基线 + 二分」找出致命插件，**不依赖日志归因**。
3. **L2 救援启动** —— `--profile rescue` 起一个不含任何第三方插件的干净引擎，
   **保底一定打得开**，且 AI / 凭据 / 会话全都正常。
4. **P3 救援信箱** —— 让 AI 能触发试启动与切回，从而**真正动手修**。
5. **P5 诊断包第 7 个检查** —— 让「处于救援模式」在报告里是 `fail`，
   不重演第 26 例。

**关键取舍**：App 负责「一定打得开」（自动、无条件），
AI 负责「逐个修好」（慢、但可行）。**不试图自动修好** —— 坏法无穷且归因信息不足。

**地基假设**：A1（全摘第三方就能起）。实现 P1 前必须先验它；若不成立，
方案收缩为「只做 L2 救援启动」，仍满足用户的核心诉求。

---

### 12.1 落地后的状态（2026-10-14）

**已实现**：P0~P6 全部（见 §6.1 的落点表）。新增 2 个文件
（`log/RescueBoot.kt`、`RescueCommandBridge.kt`），改动 5 个
（`EngineManager`、`MainActivity`、`DshApplication`、`DiagnosticBundle`、
`activity_main.xml` + `strings.xml`）。

**真机已验**（CDY-TN90 / Android 10，见 §7.8 的完整表）：
正常路径不回归、A2（救援 profile 能起来）、降级态提示词注入、P3 四个动作、
`bundleFilter` 逐字节还原、常驻横幅、**P6 自动进安全模式并真的把 App 打开**。

**仍未验 / 未做**（如实列出，不含糊）：

1. **A1 未在真机验证** —— 需要那台装 40 个插件的 Xiaomi，当前未连接。
   L1 的二分**从未真机跑过一次完整收敛**，目前只有等价算法验证 + 代码核对。
2. **未 bump `versionCode`/`versionName`**，未提交，未发布。
3. **`enterSafeMode()` 会重解包 `engine/`（15 秒）** —— 本次 P6 自动触发时
   用户会看到这段等待，文案已说明，但体验上仍是一次明显的停顿。

**实现期发现并修掉的自身缺陷**（都属本项目缺陷族「结论/日志与它描述的对象不符」，
编号接续到 31）：

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 28 | 闸门写在 `activeEnv` 赋值**之前** | `activeEnv.isEmpty()` 恒真 ⇒ L1 **永不触发** | 调用点移到 `activeEnv` 赋值之后、`pb.start()` 之前 |
| 29 | `put("DSH_PROBE_MARK","probe")` + 声称会打前缀的注释 | 一个**不存在的机制**，没人读那个变量 | 删掉变量与注释；§4.3 注明该副作用不存在 |
| 30 | `keep` 里也放了 core，而 `writeBundles` 又拼一次 core | 核心 bundle 被写两遍，**不报错** | `keep` 只放第三方 |
| 31 | 日志写「直接交 L2」，代码只 `return null` | tier 停在 NORMAL ⇒ **永远到不了 L2/L3** | 抽出 `escalateToRescue()`，七条路径共用 |

⚠️ 第 28 与 31 两例有共同形态：**判据/动作的时机或出口与它要作用的对象错位**。
这正是「先写文档、再照着文档逐条实现、再逐条核对」能抓到的原因 ——
它们都不是"代码写错了"，而是"代码没做注释/日志说它会做的事"。
