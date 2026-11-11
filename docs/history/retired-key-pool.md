# ⑮ 内置号池

> **历史档案，不作现行要求。** 已退休的 TLS 注入号池实现与排障档案，不属于当前能力或构建要求，不应按文内旧命令恢复。旧生成脚本 `tools/build-keyinject.ps1` 已移除；密钥清单仅在本机忽略目录归档，不随仓库分发。当前仅清理历史 keyinject 残留；来源状态见[原生来源记录](../architecture/native-provenance.md)。

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
历史索引见 [docs/history/README.md](README.md)，现行开发索引见 [docs/agents/README.md](../agents/README.md)。

### ⑮ 内置号池 —— 密钥在 TLS 层注入，粉丝挖不到

**需求**：给粉丝免费用内置的 Agnes 号池（当前 **132 个密钥，国内站 42 + 国际站 90**），
但**不让他们把密钥挖出来**。

> ⚠️ 数量随密钥池增删而变，**别把这里的数字当常量**。早期版本写的「14 个
> （7 + 7）」是首版规模，早就过时了；权威来源永远是
> `keyinject-keys.h` 里的 `KI_CN_COUNT` / `KI_INTL_COUNT`
> （由 `tools/build-keyinject.ps1` 按 `agnes-keys.properties` 的行数生成）。

**先说结论，别夸大**：本 App 给了 AI `bash` 与 `shell` 工具，粉丝**能对 AI 说话**。
所以「密钥藏在设备上」这条路本身是死的 —— 只要密钥在磁盘/环境变量/JS 里出现过
一次，问一句「打印所有环境变量」就出来了。**唯一彻底的解是把密钥放到服务器上做
中转**（密钥永不落设备），那需要一台服务器。

本方案是在「不引入服务器」前提下能做到的上限：**让密钥从来不进入 JS、文件、
环境变量**，只在 TLS 加密前的最后一刻于 C 库内存里出现。

#### 三个组件

| 文件 | 作用 |
|---|---|
| `app/src/main/jni/keyinject.c` | `LD_PRELOAD` 注入库，hook 4 个 OpenSSL 符号（**手写源码，入库**） |
| `tools/build-keyinject.ps1` | 读密钥清单 → 生成混淆头文件 → NDK 编译出 `assets/keyinject.bin`（**入库**） |
| `agnes-keys.properties` | 明文密钥清单（**不入库**，`.gitignore` 已覆盖） |

产物链路：`agnes-keys.properties` → `keyinject-keys.h` → `keyinject.bin`
（后两者都是生成物；头文件也**不入库**，因为混淆可逆、等价于明文）。

`EngineManager` 侧：`ensureKeyInject()` 解到 `filesDir/libkeyinject.so`；
`ensureKeyPoolProvider()` 把号池 provider **并入 profile 的用户配置层**
（`$DSH_HOME/profiles/web/cordis.patch.yml`，只补缺、不覆盖）；`start()` 里把该库
**追加**进 `LD_PRELOAD`（与 linkcompat 并存），并把 `AGNES_CREDENTIAL`
环境变量设成**占位符**。

⚠️ **不要把它写回 `$DSH_HOME/keyinject.patch.yml`** —— 那正是下一节要讲的
`overridden by a home patch` 事故。`KEYINJECT_PATCH` 常量现在**只用于删除**老残留。

#### 三个 hook 的分工

| hook | 做什么 |
|---|---|
| `SSL_set_alpn_protos` | 原样放行；仅当 SNI **已先到**且已判定为目标域名时才降级 `http/1.1` |
| `SSL_ctrl`（SET_SNI） | 拿到域名 → 是目标域名就把标记写进 **SSL 的 ex_data**，并**覆盖** ALPN 只留 `http/1.1` |
| `SSL_write` | 报文头部同时含占位符与目标域名时，**随机抽一把**钥匙；**国内站的钥匙顺带改写 Host 头**（两处都等长）→ 写完**立刻还原** |

#### ⚠️⚠️ 曾经的真实线上故障：固定槽位表耗尽 ⇒「API 密钥无效、重启又恢复」

**症状**（用户反馈）：用着用着报「API 密钥无效」，**重启 App 就恢复**。

**根因**：旧实现用一张**固定大小**的全局静态表记状态：

```c
#define MAX_SSL_SLOTS 32
static SSL *slot_ssl[MAX_SSL_SLOTS];      /* 哪个 SSL* 是目标域名 */
static int  slot_target[MAX_SSL_SLOTS];
static unsigned char slot_alpn[MAX_SSL_SLOTS][128];  /* 被扣住的 ALPN */
```

`slot_of(ssl)` 只做「命中返回 / 找空位登记」，**32 个占满就 `return -1`**，
而**全文件没有任何释放槽位的代码**（SSL 对象销毁时没有任何 hook 收到通知）。
`SSL_write` 里是 `if (i >= 0 && slot_target[i])` —— `i < 0` 时**静默跳过注入**。

⚠️ **要害是「任何域名」都在消耗槽位**：槽位在 `SSL_set_alpn_protos` 里登记，
而那个 hook 对**所有** TLS 连接都跑，不只 agnes。所以用户随便上上网、
引擎发任何 HTTPS 请求，都在吃这 32 个位置。

链条：槽位耗尽 → 不替换 → 占位符 `sk-ANON0…` 原样发到上游 →
上游回 `401 {"message":"Invalid token"}` → 用户看到「API 密钥无效」；
**重启 App = 进程重启 = 静态数组清零 → 自动恢复**。

⚠️ **排查时别去查密钥池**：真机上逐把实测 **132/132 全部有效**。
这个 bug 是**运行期状态累积**，与密钥本身无关。

**修法**：**不再自己管这份状态**，而不是把 32 调大（调大只是推迟耗尽）：

| 原来的状态 | 现在 |
|---|---|
| `slot_target[]`：这个 SSL* 是不是目标域名 | 挂到 SSL 自己的 **ex_data**（`SSL_get_ex_new_index`）—— 容量由 OpenSSL 分配，**随 `SSL_free` 自动失效**，没有上限、不会泄漏 |
| `slot_alpn[]` + `slot_len[]`：扣住的 ALPN 列表 | **不扣了**：`SSL_ctrl` 拿到域名后**直接覆盖**设置（OpenSSL 把列表暂存在 SSL 对象里、构造 ClientHello 时才读，握手前后一次设置覆盖前一次） |
| `SSL_write` 查 `slot_target[i]` 决定是否注入 | **直接看报文**：头部区域同时含占位符与目标域名才注入 |

⚠️ **最后一条是这次修复里最要紧的设计**：注入的最终判据是**报文本身**，
不是「这个 SSL* 是什么」。占位符只会由内置号池的 provider 写进请求，
所以这个判据更准，也不会因 SSL 对象地址被回收复用而误判
（旧实现里串行建连时地址复用会**掩盖**耗尽，导致问题更难复现）。

⚠️ 初始化从裸标志位 `inited` 换成 **`pthread_once`** —— `SSL_write` 会被 node
的连接池**跨线程**调用，裸标志位在多线程下有竞态。

⚠️ **真机验收**（`CDY-TN90` / Android 10，同条件 A/B）：

| 场景 | 旧库 | 修复后 |
|---|---|---|
| 60 个请求（先 40 条并发连接占位） | **200=32 / 失败 28** | **200=60 / 失败 0** |
| 300 请求压力（并发 8） | — | **300/300** |
| 40 条并发新连接 | 第 33 条起全挂 | **40/40** |
| 40 条 `www.baidu.com` 连接占位后再请求 agnes | **6/6 全 401** | **8/8 全 200** |
| 非目标域名 ALPN | — | `cloudflare`/`npmjs` 仍是 **h2**，只有 agnes 降到 http/1.1 |
| `DSH_KEYINJECT_OFF=1` | — | 正确退回 401（开关仍有效） |

⚠️ **复现这类问题时必须注意三点**（否则会得出错误结论）：
1. **必须设 `ALPNProtocols`**：不设时 `SSL_set_alpn_protos` 钩子不被调用 ⇒
   完全不注入 ⇒ 全程 401，会误判成「注入层彻底失效」；
2. **必须让连接同时存活**（keepAlive + 并发）：串行建连时 SSL* 内存被回收复用，
   会**恰好命中旧槽位**，掩盖耗尽；
3. 判定「非目标域名有没有被误降级」要**在 `secureConnect` 上直接读
   `socket.alpnProtocol`** 并与**不加载库的对照组**比 —— 用 http/1.1 解析器
   去读 h2 响应会抛 `Parse Error`，那只说明「不是 http/1.1」，
   区分不出「对方服务器本来就不支持 h2」。

#### combo：两个站点，一个供应商

**需求**：模型列表里**只显示一个「Agnes」**，但 14 把钥匙分属两个站点，而两站
钥匙**不通用**（实测互打 401，文档也写了不可混用）。

一开始的实现是**两个 provider 路由**（`agnes` + `agnes-intl`），各指自己的
`baseURL`。这样能用满 14 把钥匙，但代价是**模型选择器里出现两个 Agnes 分组**
（「Agnes 国内站」「Agnes 国际站」），每个都列 11 个模型 —— 列表又长又乱。

**现在的做法**：引擎侧只配**一个** provider，两站由 `keyinject.c` 在 TLS 层
当小路由 —— **选中哪把钥匙，就把 Host 头改成那把钥匙所属站点的域名**。

成立依据是三个实测事实：

| 事实 | 实测值 |
|---|---|
| 两个域名解析到**同一个 IP** | `api.agnes-ai.cn` 与 `apihub.agnes-ai.cn` 都是 `119.0.68.30` |
| 共用**同一张通配证书** | 都是 `CN=*.agnes-ai.cn`，SAN `*.agnes-ai.cn, agnes-ai.cn`，指纹一致 |
| 服务器按 **Host 头**路由，**不是** SNI | 见下表 |

路由依据的实测（同 IP 同证书，只换 SNI / Host，用「返回 11 还是 12 个模型」
区分站点 —— 国际站多一个 `agnes-image-2.0-flash`）：

| 请求 | 结果 | 实际站点 |
|---|---|---|
| INTL key + `Host: apihub.agnes-ai.cn` | 200 | 国际站 |
| INTL key + `SNI=apihub` + `Host: api.agnes-ai.cn` | 401 | — |
| **INTL key + `SNI=api` + `Host: apihub.agnes-ai.cn`** | **200** | **国际站** |
| **CN key + `SNI=apihub` + `Host: api.agnes-ai.cn`** | **200** | **国内站** |

⚠️ 后两行是**决定性证据**：SNI 与站点无关，**Host 头才是路由依据**。所以同一条
TLS 连接上换 Host 就能换站点（实测同一连接先 CN 后 INTL，两条都 200）。

⚠️ **Host 改写必须等长**：`apihub.agnes-ai.cn`（18 字符）比 `api.agnes-ai.cn`
（15 字符）长，所以后者用 **3 个尾部空格**补齐到 18。HTTP 头值的尾部空格由服务器
剥离（实测 `Host: api.agnes-ai.cn   ` 正确路由到国内站）。

⚠️ **因此 `baseURL` 必须用较长的国际站域名** —— 等长改写只能从长的往短的改，
反过来没有等长写法。这条决定了 `KEY_BASE_URL` 的取值，别随手改成国内站。

⚠️ **Host 改写只在头部区域**（首个 `\r\n\r\n` 之前）进行，避免误改 body 里恰好
出现的同一串域名（用户消息里粘了域名时，改 body 就是篡改用户内容）。

#### ⚠️ 14 把钥匙一个池子，两站交错，每个请求随机抽一把

池子按「国际站、国内站」**交错**排列：

```
intl.1, cn.1, intl.2, cn.2, … intl.7, cn.7
```

`keyref_at(slot, &site, &idx)` 就是这张映射表（偶数位国际、奇数位国内），
用 `% count` 索引，所以两站数量不等也能跑（少的那个被循环复用）。

**抽取策略是「每个请求独立随机抽」**（`arc4random_uniform(POOL_COUNT)`），
不是轮转、也不是失败退避。理由：

- 两站配额**同步消耗**，单把钥匙的速率摊薄到 1/14，429 概率显著下降；
- **没有「当前钥匙」这个状态**，也就没有需要推进的游标 —— 一个请求一次抽取，
  与上一个请求用了什么无关。

⚠️ **为什么用 `arc4random_uniform` 而不是 `rand()`**：`rand()` 需要一个可靠的
播种点，而本库是 `LD_PRELOAD` 进去的，没有合适的初始化时机（在构造函数里播种
还会干扰宿主自己的随机数使用）。bionic 的 `arc4random` 自行维护熵池且线程安全，
不需要播种。

⚠️ **解出来的明文钥匙必须放在栈上，不能用全局缓存**（踩过）：`SSL_write` 会被
node 的连接池**跨线程调用**，而随机化之后两个并发请求抽到的站点很可能不同。
用全局缓存就会出现「A 线程刚解出国际站钥匙、B 线程立刻覆盖成国内站的」——
于是 A 拿着国际站密钥去打国内站域名，**症状是稳定 401**，且只在并发时出现。
栈上 52 字节，代价可忽略。

⚠️ 早先还踩过更早的一版：按「表内下标」缓存，于是 `CN,idx=0` 与 `INTL,idx=0`
被当成同一把，症状同样是稳定 401。

**401 无法「就地重试」**：响应已经发完，字节改不了。引擎自己的 `retryPolicy`
会重发失败请求，重发时自然重新抽一把。

⚠️ 排查「抽到哪把」只能靠 `DSH_KEYINJECT_DEBUG=1`（打 `use CN idx=0` 到 stderr，
用 `adb logcat` 看）。**曾经**为此做过一套写文件的「路由日志」+ 设置页分节，
调试完之后**已经整套删掉** —— 不要再加回来。删掉的东西包括：本库里的
`route_log` / `DSH_KEYINJECT_LOG` / `SSL_read` hook、`EngineManager` 的
`readKeyInjectLog` / `clearKeyInjectLog`、`MainActivity` 的两个桥接口、
`client.js` 的 `dsh-android-route-log` 分节。

#### ⚠️ 必须降级 HTTP/1.1 —— 这是本方案最反直觉的一点

**真机实测**：引擎的 undici 默认协商 **HTTP/2**（`SSL_write` 里看到的是
`PRI * HTTP/2.0`），而 HTTP/2 的头部是 **HPACK 压缩**的 —— 看到的是一堆二进制，
**没有明文 `Authorization` 头**，等长替换无从谈起。

强制 ALPN 只留 `http/1.1` 之后，`SSL_write` 里就是标准明文 HTTP/1.1 报文，
`Authorization: Bearer sk-ANON…` 清晰可见，替换才成立。

⚠️ **降级只对目标域名生效，这是设计上最要紧的一条。** 全局降级会让引擎**所有**
HTTPS 请求失去 HTTP/2（多路复用、头部压缩），是实打实的性能损失。实测调用顺序是
`SSL_set_alpn_protos` **在前**、`SSL_ctrl(SET_SNI)` **在后**，所以本库先扣住
ALPN 列表、等 SNI 到达再决定是否降级。

⚠️ **`SSL_set_tlsext_host_name()` 是宏**，展开成 `SSL_ctrl()`，动态符号表里只有
后者 —— hook 前者**永远不会被调用**（踩过，白白多花一轮真机验证）。域名必须从
`SSL_ctrl(cmd=55 /* SSL_CTRL_SET_TLSEXT_HOSTNAME */, larg=0)` 的 `parg` 拿。

#### ⚠️ 等长替换：唯一不可妥协的前提

14 个密钥实测**全部恰好 51 字符**，占位符 `sk-ANON` + 44 个 `0` 也是 51 字符。
所以替换是**等长原地覆盖**：`Content-Length` 不变、缓冲区布局不变。

- 占位符长度写死在两处：C 侧的 `KI_PLACEHOLDER` 与 Kotlin 侧的 `KEY_PLACEHOLDER`，
  **必须逐字一致**。`build-keyinject.ps1` 会读 Kotlin 文件校验两者长度相等，
  不一致时**直接构建失败**（不静默放过）。
- `keyinject.c` 里有 `_Static_assert(sizeof KI_PLACEHOLDER - 1 == KI_KEY_LEN, ...)`
  兜底。C 侧的 `KI_KEY_LEN` 由生成的头文件给出，所以换密钥池时长度一变就编译不过。
- **换密钥池时长度必须保持一致**，否则请求会因长度错乱失败。

⚠️ **写完必须还原**：`SSL_write` 的缓冲区属于**调用方**（node 的 Buffer，可能被
复用），留下真密钥会污染它。所以替换前先存下原 51 字节，`real_SSL_write()` 返回后
立刻抹回占位符。

#### 密钥怎么存（⚠️ 是混淆，不是加密）

`build-keyinject.ps1` 对每个 key 逐字节 XOR 一个随机 mask，再叠一层位置相关的
滚动常量（`i * 7 + 0x5A`）。所以 `strings keyinject.bin` 搜不到 `sk-` 开头的明文。

⚠️ **这是混淆，不是加密。** mask 与滚动常量都在二进制里，有耐心的逆向者可以还原。
**别在任何地方说成「密钥被加密保护了」**。

⚠️ **门槛没有「会反汇编」那么高 —— 实测比这低得多，别把防线说强了。**
真正的弱点不在反汇编，而在**全表共用一把 mask**（见上面：`K[i]` 只与列号有关、
与行号无关）。攻击者**不读源码、不读头文件、不读 mask**，只要知道
「密钥是 51 字符、`sk-` 开头、以步长 51 的二维表存放」，就能逐列枚举
`K[i] ∈ [0,256)`、要求该列所有行解密后都落在 base62 内 —— 行数一多候选就收敛到 1。
实测（2026-10-13，`keyinject.bin` 16,688 B / 132 行）：

```
零知识定位：扫「步长 51 且前 3 列可解」的偏移 → 113 处候选，最强 base=2084 撑满 132 行
逐列枚举  ：51/51 列唯一确定，候选空间 = 1
终局比对  ：盲解 ∩ agnes-keys.properties = 132/132 条**逐字节完全相同**，独有条数 0
```

也就是说**全程没看 `.c`、没看 `-keys.h`、没看 `KI_MASK`**，一把全出。
这是本项目已知的、**刻意接受**的取舍（真正的解只有服务端中转，见下），
但**别把它的强度说高**：准确的表述是「挡得住 `strings` 级的顺手一搜，
挡不住愿意写二十行脚本的人」。`keyinject.c` 的注释与本节口径要一致。

⚠️ 每次构建都换一把新 mask，所以**不可复现构建** —— 这是有意的（从某版本还原出
明文也无法用同一套常量解开下一版），代价对本项目无影响（头文件与 `.bin` 都不入库）。

⚠️ 加密端（PowerShell 的 `Get-Scramble`）与解密端（C 的 `derive_key`）**必须逐字节
一致**，改一边必须同步改另一边。脚本里有往返自检，不一致会直接报错。

#### ⚠️⚠️ 内置号池**绝不能**写成 `--patch` overlay —— 那会让用户加不了第三方 API

**这是本方案踩过的最严重的一个坑**，症状是「更新完内置模型之后，用户一添加第三方
API 就报错」，报错原文：

```
Configuration for "llm-pi-ai" is overridden by a home patch or command-line overlay
```

**根因**（`@deepseek-ai/dsh-config-editor/lib/index.js:122`）：GUI 的「创建提供商」
走 `settings.mutate("llm-pi-ai", …)` → `ConfigEditor.edit()`，它在写盘前有一道
**保护性校验** —— 把刚写下的值经**完整层序（含 `--patch` overlay）**重新
`composeEntries()` 一遍，必须与写入值**深相等**，否则抛上面那句。

而 patch 的语义是**整块替换该行的 `config`**（不是深合并，见 `dsh-base/cordis.patch.yml`
头部注释），overlay 又排在**最顶层**。所以 `- id: llm-pi-ai` 的 overlay 一旦存在：

| 层 | `llm-pi-ai` 的 `providers` |
|---|---|
| bundle | （空） |
| **用户层**（GUI 写入 `agnes` + 用户新建的 `a`） | `agnes`, `a` |
| **`--patch` overlay**（内置号池） | **`agnes`** ← 只剩这个 |

compose 结果是 `{agnes}`，与写入的 `{agnes, a}` 不相等 ⇒ 校验失败 ⇒ **报错**。
用户的 provider 一个都存不进去。

**修法**：内置号池并入 **profile 的用户配置层**
（`$DSH_HOME/profiles/web/cordis.patch.yml`）—— 那正是 GUI 自己读写的那份文件，
格式与语义完全一致（同 [seedDefaultSettings] 的理由）。`EngineManager.ensureKeyPoolProvider()`
只做**补缺**：已有 `agnes:` 时一个字节都不动，所以既能保证号池始终在，又不会覆盖
用户自己加的 provider。

⚠️ **老安装残留的 `keyinject.patch.yml` 必须无条件删除**（与号池资产在不在无关）：
它本身就是那个 bug，留着它报错就还在。

⚠️ **也不能给号池另开一行**（比如 `- insert: [{id: agnes-pool, name: '@deepseek-ai/dsh-llm-pi-ai'}]`）：
`dsh-llm-pi-ai` 的 `apply()` 会**无条件**把 pi-ai 内置目录的 **41 个 provider**
全部 `registerConfigurableProviders()`。挂第二个实例时第二批声明撞上第一批已占用的
路由，抛 `DUPLICATE_DIRECTORY`（`configurable provider "anthropic" is already declared`）
⇒ 整个目录注册失败、**模型列表空掉**。实测 `getBuiltinProviders()` 返回 41 项。

⚠️ 改这一处时注意两个 YAML 细节（都踩过）：
- 空模板是 `[]`，往里追加 entry 前**必须先摘掉它**，否则 `[]` 后面跟块序列是**非法 YAML**；
- 行已存在但没写 `config:` 时，只补 `config` 子树，**不能再输出一遍 `name:`**（重复键非法）。

**验收**（离线，用引擎自己的 `composeEntries` + `dsh-config-editor` 的判据）：
四种起点（空模板 `[]` / 已有用户 provider / 已播种出厂默认项 / 行存在但无 config）
补完后，模拟 GUI 新建 provider 再 compose，**必须深相等**；幂等（跑两次逐字节相同）；
已有 `agnes:` 时**一个字节都不动**。

#### 验收

**离线实测（PC 上直接打两个站点，绕过引擎，验的是 combo 的分流原理）**：

| 项 | 结果 |
|---|---|
| 两域名 IP | 都是 `119.0.68.30` |
| 两域名证书 | 都是 `CN=*.agnes-ai.cn`，指纹一致 |
| 路由依据 | **Host 头**（`SNI=api` + `Host: apihub` 照样进国际站） |
| 同连接换 Host | 可行（先 CN 后 INTL，两条都 200） |
| 14 把钥匙各自站点 | **14/14 全 200** |
| `Host: api.agnes-ai.cn   `（3 空格补齐） | 200，且**路由到国内站**（用「有没有 `agnes-image-2.0-flash`」判定） |
| 真实 `chat/completions` | 走 Host 改写后 **200** |
| 二进制里搜明文密钥 | **0 命中**（`sk-` 只出现 1 次，就是占位符） |

**真机实测（安装 debug 包后看日志）**：

| 项 | 结果 |
|---|---|
| `已解出号池注入库 libkeyinject.so (12576 字节)` | 注入库已落盘 |
| `已把内置号池并入 profile 用户配置: .../profiles/web/cordis.patch.yml` | 已并入用户层（**不是** overlay） |
| `已删除旧的内置号池 overlay: keyinject.patch.yml` | 老安装的残留 overlay 已清掉（只出现一次） |
| 用户层里的内置号池内容 | **单** provider `agnes`、`displayName: 'Agnes'`、baseURL 是 apihub、**只有 `agnes-3.0-flash` 一个模型** |
| 引擎启动 | `引擎就绪` + `WebView 加载完成` |
| `已安装移动端 UI 插件 dsh-android-mobile <版本>` | 移动端插件已解包 |

⚠️ **下面这些要人工确认**（GUI 观感与真实对话，离线探针测不到）：

1. 主界面模型选择器里**只有一个「Agnes」分组**，下面**只有 `agnes-3.0-flash`**。
2. 选它发一条消息，能正常回复（证明 combo 在真机上把钥匙与 Host 配对了）。
3. 设置 → 高级 里**不应再有「路由日志」分节**（那套东西已随调试一起删掉）。

体积代价：`keyinject.bin` 12,576 字节 —— 对 132.8 MB 的发行包约 0.01%，可忽略。
真正的代价是维护面（一个原生组件、一条 `LD_PRELOAD`、一个启动顺序依赖，
以及 combo 引入的「Host 改写必须等长」这条隐性约束）。

#### ⚠️ 挡住了什么、挡不住什么（不要夸大）

**挡住**：
- AI 工具读文件 / 环境变量 / `/proc/<pid>/environ` —— 那些地方只有占位符（实测）
- `strings` APK / 十六进制搜索 —— 二进制里没有明文 key（实测）
- 装用户 CA 抓包 —— 抓到的是密文

**挡不住**：
- 有耐心的逆向者（反汇编本库、复现 XOR 与滚动算法）—— 是门槛，不是墙
- 拿到 root 后用 Frida / ptrace 读进程内存 —— 但 release 包
  `android:debuggable=false`，Frida 需要 root
- hook 了 `SSL_write` 的抓包工具 —— 与本库同一层，看得到
- ⚠️ **`android:usesCleartextTraffic="true"` 且没有 `networkSecurityConfig`**，
  所以用户装的 CA 能解密 HTTPS。本库不防这个。

⚠️ `/proc/<pid>/mem` 真机实测**被内核挡住**（`head: I/O error`），
`/proc/sys/kernel/yama/ptrace_scope` 在本设备上不存在 —— 但这两条**都不构成防护**，
ptrace/Frida 不受此限。

⚠️ **调试开关**：`DSH_KEYINJECT_OFF=1` 关掉注入（用来区分「是本库引起的」还是
「本来就这样」）；`DSH_KEYINJECT_DEBUG=1` 打开日志。两者都不参与保密 ——
关掉后请求会带占位符发出去并被判 401，是个可观测的失败。

#### ⚠️ 安全提醒（与代码无关，但比代码更要紧）

密钥文档挂在 **`http://39.98.163.161:57647/`**（**明文 HTTP**，无 TLS）。
若该主机对公网可达，那才是最大的暴露面 —— **比 APK 泄露严重得多**，
因为它是 14 个密钥的完整清单、一次请求全部拿走。发版前建议确认它只在内网，
或尽快改成 HTTPS / 加访问控制。

