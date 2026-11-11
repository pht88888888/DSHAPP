# ⑪ 内建 uid 2000 执行通道

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑪ 应用身份权限太低 —— 内建 uid 2000（shell）执行通道

**症状**：截图、点击屏幕、卸载应用、改系统设置、读 `/dev/input` 这些操作
**全部做不到**。而且失败方式很隐蔽 —— 有些报权限错，有些**静默失败**。

**真机实测（Huawei CDY-TN90 / Android 10）两个身份的差异**：

| 命令 | app 域（u0_a323） | uid 2000（shell） |
|---|---|---|
| `screencap -p x.png` | rc=1，**stderr 完全为空**，留下 0 字节文件 | rc=0，708460 字节真图 |
| `input keyevent 3` | **rc=0 但前台窗口纹丝不动** | rc=0，真的切到桌面 |
| `pm uninstall x` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
| `settings put/get` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
| `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
| `appops get` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
| `getevent -pl` | `could not add watch for /dev/input, Permission denied` | 能枚举输入设备 |
| `dumpsys battery` | rc=**0**，输出 `Can't find service: battery` | 完整状态（308 B） |
| `wm size` | rc=**20**，`Can't find service: window` | `Physical size: 1080x2400` |
| `ps -A` | rc=0，**只有 6 行**（只看得见自己 uid） | **674 行** |
| `dumpsys activity services` | rc=0，`Permission Denial: … missing DUMP` | 3203 B 正常 |

⚠️ **这张表的关键是 `input` 和 `dumpsys`/`ps` 那几行**：退出码是 0，但结果
要么没生效、要么残缺。**「失败」不能只看退出码** —— 这也是为什么本方案把
「要不要用特权身份」交给**模型自己判断**，而不是让宿主去猜哪条命令该走哪个域。

#### 做法：注册一个**独立的 `shell` 工具**（不是给 bash 加参数）

**这一点是本次改动的地基，改动前务必先理解，尤其是「为什么不能用提权参数」。**

引擎确实原生就有提权机制：`@deepseek-ai/dsh-tool-bash` 在
`ctx.shell.sandboxMode !== undefined` 时会给 `bash` 工具的 schema **自动加上**
`sandbox_permissions`（enum `["workspace-write","danger-full-access"]`）与
`justification`（`lib/index.js:513`），模型声明后由 `ctx.approval` 批准。

**但这个机制在本 App 里是坏的，原因是一个耦合：** 它挂在**权限预设**上。
用户在 GUI 里选「无需审批 / 全部权限开放」时，`defaultPreset` 就是
`danger-full-access`，于是该模式成了会话默认。而
`dsh-sandbox/lib/index.js:101` 里 `approveEscalation()` 对
`mode === effectiveMode` **直接返回原值**（不许"升级"到当前档），
`approval/policy: "never"` 又让审批一律 `rejected` —— 结果是：
**每一条 bash 命令都直接以 uid 2000 跑，模型连选择的余地都没有。**

真机后果（会话日志 `session-77e5515b`，事件 `#1 permission/preset: danger-full-access`、
`#2 sandbox/mode: danger-full-access`、`#3 approval/policy: never`）：
`pwd` = `/`、`ls: No such file or directory`、`cd /data/data/com.dsh.android/files/engine`
→ `Permission denied`。**bash 工具整个废掉**，因为 uid 2000 读不到 app 私有目录。

所以正确做法是把「身份」和「预设」**彻底解耦**，注册一个**新工具**：

| 工具 | 身份 | 谁决定 |
|---|---|---|
| `bash` | **永远** app 身份（沙箱、link 转译、工作区全部正常） | 引擎，与预设无关 |
| `shell` | **永远** uid 2000 | 模型读工具描述后自己选 |

这样无论预设怎么设，`bash` 都是 app 身份；要特权就显式调 `shell` ——
恰好同时满足「默认走 app 权限」与「AI 自己决策要不要用 2000」。

⚠️ `shell` 这个工具名在引擎里**没被占用**（实测 30 个内置工具名），
可以安全注册。`run_code` 是保留名，不能碰。

#### 执行体：复用 `ctx.shell`，只是换个命令

插件在 `apply()` 里 `ctx.tools.register(defineTool({...}))`，`execute` 时把命令
换成 `node <runner> '<原命令>'`，并以 `sandboxPolicy: { mode: 'danger-full-access' }`
调用 `ctx.shell`。`dsh-bash-sandbox/lib/index.js:68` 里 `danger-full-access`
的语义**恰好**是「不做任何限制、直接跑」：

```js
if (mode === "danger-full-access") return SandboxBashExecutor.decorateResult(await super.execute(spec), …);
```

于是外层 `bash -c` 以 **app 身份、无沙箱** 启动（它读得到 app 私有目录里的
runner），runner 再连 adbd 以 uid 2000 跑真正的命令。**超时、取消、输出收集、
spill、grace 全部沿用引擎既有实现**，插件不必自己 spawn 一遍。

⚠️ **必须显式给子进程传 `DSH_HOME`**：`dsh-subprocess` 的 `scrubbedParentEnv()`
会剥掉**所有** `DSH_*` 变量（`lib/index.js:52`）。不传的话 runner 退回
`os.homedir()/.dsh`，与密钥所在的 `$DSH_HOME` 不一致 → 找不到密钥 →
**静默回落成 app 身份**。这是个很难查的坑。

⚠️ **插件手工构造 definition，不要 `import { defineTool }`**：包解在
`$DSH_HOME/shell-tool/` 下，那里没有 `node_modules`，ESM 解析**够不到**引擎的
`@deepseek-ai/dsh-tools`。`defineTool` 只做「spec schema → raw JSON Schema」+
「包一层参数校验」，手工写 raw JSON Schema 完全等价；参数校验由
`ctx.tools.register` 内部的 `validateJsonSchemaValue` 兜底。
支持的 schema 关键字子集是 `type/oneOf/properties/required/additionalProperties/items/enum/const`
+ 注解（`description/title/default/examples`），**`type` 与 `oneOf` 不能同时出现**。

实现分三块：

| 文件 | 作用 |
|---|---|
| [runtime/scripts/dsh-android-shell.mjs](../../runtime/scripts/dsh-android-shell.mjs)（APK 资产名仍为 `dsh-android-shell.mjs`） | 直连 `127.0.0.1:5555` 的 adbd，走 ADB 协议认证后以 uid 2000 执行 |
| `assets/shell-tool.zip` | host 插件 `dsh-android-shell`，注册 `shell` 工具 |
| `$DSH_HOME/shell-tool.patch.yml` | overlay，把上面的插件挂进 profile |

`EngineManager.ensurePrivilegedShell()` 每次启动把 runner 覆盖写到 `filesDir`；
`ensurePrivilegedPlugin()` 把 zip 解到 `$DSH_HOME/shell-tool/dsh-android-shell/`；
`writePrivilegedPatch()` 生成 overlay（`name` 用 `file://` 绝对 URL）。

⚠️ **为什么解到 `$DSH_HOME/shell-tool/` 而不是 profile 的 `node_modules`**：
与移动端 UI 插件同一个坑 —— 那里归 pnpm 管，而本包不在 profile 的
`dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`，
overlay 里的包名随即解析失败。详见「移动端 UI 插件」一节。

⚠️ **通道不可用时必须删掉 overlay**（`writePrivilegedPatch(false)` 会 `delete()`）：
插件本身在缺 `DSH_PRIV_RUNNER` 时不会注册工具，但留着一个指向不存在文件的
overlay 只会制造噪音。

⚠️ **不要改 `node_modules` 里的源码**：会在引擎升级时被覆盖，且改的是打包产物。

#### ⚠️ 历史教训：不要再用 `--import` 挂预加载

早先的实现是 `assets/dsh-android-privileged-loader.mjs` 经 `node --import`
替换 `SandboxBashExecutor.prototype.execute`。**这条路已经废弃并删除**，
原因就是上面那个耦合（它只能挂在 `danger-full-access` 上，而那恰好是用户的默认预设）。
若将来又想走 `--import`，先读这一节。

（技术备注：`--import` 是 **Node 的 flag**，必须排在脚本路径之前 ——
`node --import <loader> <bin.js> --profile web`；而 `--patch` 改的是配置，
够不到执行器行为。这些事实仍然成立，只是这个方案本身不成立。）

#### ADB 签名：不是标准的 `crypto.sign('sha1', token)`

**这是整件事最容易踩的坑，卡了很久。** AOSP 的做法是手工拼一个 PKCS#1 v1.5 块，
把 **token 原文**放进 DigestInfo 的摘要槽（**不是** `sha1(token)`）：

```
00 01 FF×219 00 ‖ 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14 ‖ token原文
                 └────────────── SHA1 DigestInfo 前缀 ──────────────┘
```

用 `crypto.sign('sha1', token, priv)` 会被 adbd 拒绝，症状是**反复重发 AUTH token**
（试满 2 次后转而要公钥），而不是明确的错误信息。Dadb 的
`AdbKeyPair.signPayload()` 用的是 `Cipher.getInstance("RSA/ECB/NoPadding")` +
手写 padding 表，同一回事。

参考实现：`dadb/src/main/kotlin/dadb/AdbKeyPair.kt`。

#### 另一个坑：读完每个 WRTE 必须回 OKAY

ADB 是**窗口式流控**：读完一个 `WRTE` 的 payload 不回 `OKAY`，adbd 会**停在那里
不再发下一个包** —— 表现为命令挂住、永远等不到 `CLSE`。Dadb 的
`AdbStreamImpl.read()` 也是读完即 `writeOkay(localId, remoteId)`。

同理，shell 要用 **v2 协议**（`OPEN` 的 destination 写 `shell,v2,raw:<cmd>`）：
v1 没有 `EXIT` 包，拿不到退出码。

⚠️ **收尾不能用 `process.exit(code)`**：输出走管道时它会在 libc 缓冲区写回之前
就终止进程，**静默截断大输出** —— 而 `screencap`/`dumpsys`/`ps -A` 恰好都是
大输出。要用 `process.exitCode` 让事件循环自然排干。

⚠️ stream id 要**顺序递增**，不能随机：随机 id 可能与在用的 id 撞车，撞车后
先关闭的那条流会把另一条活流的队列一起拆掉（AOSP 的注释专门记了这条）。

#### 密钥从哪来（Android 11+）：无线调试配对，**全程不碰电脑**

Android 11（API 30）起有了「无线调试」，它把「推密钥」这件事变成了**设备上
点几下 + 抄一个 6 位配对码**。配对成功后设备 keystore 里就有了我们的公钥，
此后走密钥认证，**不用再填码**。

分工（**端口发现必须在 Kotlin 侧**，原因见下）：

| 侧 | 文件 | 做什么 |
|---|---|---|
| Kotlin | `AdbMdns.kt` | `NsdManager` 发现 `_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp` 的端口 |
| Kotlin | `WirelessDebugging.kt` | 编排：常驻监听写端口、配对、切固定端口 |
| Kotlin | `AdbPairingNotifier.kt` | 通知栏 `RemoteInput` 收配对码 |
| Node | `dsh-android-shell.mjs` | 读端口文件 → 用**内置 adb** 连上去执行 |

##### ⚠️ 端口发现只能走 `NsdManager`，不能自己实现 mDNS

**app 域发不出 UDP 组播。** 真机实测：

```
sock.send(q, 0, q.length, 5353, '224.0.0.251')
  → EPERM send EPERM 224.0.0.251:5353
```

`bind(5353)` 与 `addMembership` 都**成功**，单播也成功 —— 只有**组播发送**被
SELinux 层挡掉。而 Android 的 `mdnsd` **只应答组播查询**，所以自实现 mDNS 无解。

`NsdManager` 是**系统服务**：请求经 Binder 交给 **system 域**的 `mdnsd` 代发，
绕开了 `untrusted_app` 的限制。这正是 Shizuku 的做法（见其 `AdbMdns.kt`）。

⚠️ **因此不要退回「扫端口」。** 最初的实现扫 32768–60999 要 **20 秒**，而端口
**每次重启/重开都会变**，等于每条特权命令前都可能干等 20 秒。实测
`NsdManager` 解析耗时 **81 毫秒**。

端口有效性沿用 Shizuku 的判据：**能 `bind(127.0.0.1:port)` 成功 = 没人在听**，
失败才是 adbd。另外要求解析出的地址必须是本机地址 —— 实测重启后曾收到 PC 侧
缓存的陈旧 mDNS 条目，被这条规则正确拒绝：

```
[mdns] 忽略 adb-62291ab7-uzVPim → 192.168.1.85:33217（非本机或端口无人监听）
```

##### ⚠️ 配对端口只在那个界面开着时才广播 —— 所以必须**常驻等待**，不能超时就放弃

`_adb-tls-pairing._tcp` **只在用户打开「使用配对码配对设备」界面时才存在**，
界面一关就没了。所以 App 侧必须**一直**监听，直到端口出现。

⚠️ **原实现是「一次性发现 8 秒，没找到就报没找到」，这是个真实的体验缺陷**
（真机反馈修掉的）：用户点完配对后要去系统设置里翻找那个界面 —— 进「开发者选项」
再进「无线调试」再点「使用配对码配对设备」，远超 8 秒。于是：

```
用户刚找到那个按钮 → 通知栏已经显示「没找到端口」→ 只能回 App 重按一次配对
```

而重按一次**还是同样的结果**，因为端口本来就还没出现。这个来回毫无意义。

**现在的实现**（`AdbPairingNotifier.start()` → `runWatch()`）：

- 起一个**常驻**任务，用**一个** `AdbMdns` 实例持续监听，端口一出现就把通知换成
  带输入框的。**不加任何超时** —— 只有用户主动取消（设置页「取消配对」，或通知上的
  「取消」动作）与配对成功会让它停下。
- ⚠️ **必须用常驻实例，不能反复调 `AdbMdns.discover(timeout)`**：后者每次都要重新
  注册 discovery（`NsdManager` 同一 listener 不能重复注册，所以每次都新建对象），
  中间存在「上一次已停、下一次未注册」的**空窗期**，端口恰好在空窗期出现就会错过，
  要再等一轮。
- ⚠️ **常驻通知划不掉**，所以必须挂一个「取消」动作：万一进程被系统回收，监听已经
  没了而通知还挂着，用户就只能去强制停止应用。这个出口是必需的。
- ⚠️ 等待端口的那条通知也设为**常驻**：它是「正在等你操作」的指引，被划掉用户就
  不知道该去哪开无线调试了。
- ⚠️ 失败（配对码抄错）时**不停监听**：那个界面可能还开着、端口还在，用户改一位
  数字重发就行。
- 设置页要同步显示「正在等待…」（按钮禁用 + 把「接下来该做什么」铺在按钮下方）。
  点了按钮界面毫无变化的话，用户会以为没点上而反复点。
- ⚠️ 引导步骤的顺序是**先点配对、再去系统里开界面**（通知栏会指引）。原文案写的是
  反过来的「先开界面再回来点配对」，照旧文案走会白跑一趟。

##### ⚠️ `AdbMdns.isRunning` 的判据只能是 `running`（看门狗用）

常驻等待需要一个看门狗：`NsdManager` 在系统重启 mdnsd 等情况下会回调
`onStartDiscoveryFailed` / `onDiscoveryStopped` 把发现**静默掐掉**。一次性发现
不在乎（下次调用会重建），但常驻等待必须能发现并重建它 —— 否则用户按提示去开了
无线调试，这边却早已不再监听，永远不会出现输入框。

⚠️ **判据不能写成 `running && registered`**：`registered` 要等 `onDiscoveryStarted`
回调才为 true，而那是**异步**的。刚 `start()` 完那一瞬间 `running=true,
registered=false`，看门狗会把它误判成「已停」并**反复重建监听**，形成死循环
（写这一版时踩到过）。

⚠️ **`onDiscoveryStopped` / `onStopDiscoveryFailed` 必须同时清掉 `running`**：
系统**主动**停掉发现时只走这两个回调（我们主动 `stop()` 时 `running` 本来就是
false，再置一次无害）。不清的话 `isRunning` 永远报 true，看门狗永远不重建。

##### 配对码为什么要走通知栏 `RemoteInput`

用户必须**离开 App** 去系统设置页读那 6 位码，所以不能用 App 内输入框 ——
切走 App 后它可能被冻结/回收。通知栏可以在用户停留在设置页时接收输入。
（Shizuku 的 `AdbPairingService` 同理。）

⚠️ **`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`**，否则系统**写不回**
用户输入的内容，症状是「能打字但点确认没反应」。代码里按
`SDK >= S ? FLAG_MUTABLE : 0` 处理。

⚠️ **接收器必须静态声明**（`AndroidManifest.xml` 里 `exported="false"`）：
通知可以比 App 进程活得久，动态注册的接收器会收不到。

⚠️ **`goAsync()` 是必须的**：配对要十几秒（SPAKE2 握手 + adb 启动 + 实际执行，
且端口可能还没出现），远超广播接收器的 10 秒超时（不 `goAsync()` 直接 ANR）。
收尾必须 `pendingResult.finish()`，放在 `finally` 里。

##### 密钥来源必须落盘记录（否则设置页会误报）

设置页原来只按「密钥文件在不在」显示「已导入 / 未导入」—— **这是错的**：
密钥有两个来源，而其中一个设备**尚未信任**。

| 来源 | 设备是否信任 | 用户该做什么 |
|---|---|---|
| `imported`（从 `/sdcard/dsh_adbkey` 搬来） | **是**（那是 PC 已授权的密钥） | 直接能用 |
| `generated`（App 现场生成） | **否** | 必须先配对 |
| `paired`（生成后又配对成功） | **是** | 直接能用 |

来源写在 `$DSH_HOME/adbkey.origin`，由 `markKeyOrigin()` 在**写入密钥那一刻**落盘。

⚠️ **不能靠 stderr 日志判断**：`importKeyIfNeeded()` 是幂等的，导入日志只在
**第一次**运行出现，之后再也看不到 —— 用它判断会在第二次探测时把
`imported` 误报成 `generated`。

⚠️ 标记文件是后加的，**老安装没有它**。`keyOrigin()` 此时返回 `null`，
设置页显示灰点「来源未知」，**故意不猜成「已导入」** —— 那正是要修的误报。

##### ⚠️⚠️ 绝不要主动发公钥 —— 那会**每条命令**都弹「允许 USB 调试吗」

**这是本项目最严重的一处设计错误，也是唯一一处「删掉功能」才算修好的地方。**

**症状**（真机 OPPO / Android 15）：只要 App 碰一次 adbd，屏幕上就弹一次
「允许 USB 调试吗」；用户点了「一律允许」下次照样弹。`shell` 工具因此
**完全不可用** —— 用户不可能为每条命令点一次对话框。

**根因**在 `handshake()` 的失败分支：签名认证失败时它**无条件**发
`A_AUTH / AUTH_RSA_PUBLIC`（把公钥推给 adbd）。而 adbd 收到公钥后**唯一**的反应
就是弹那个对话框 —— 于是每一次连接（每条命令、每次探测）都弹一次。

⚠️ **「一律允许」在 TCP 连接下不落盘**：Android 15 实测勾选后
`/data/misc/adb/adb_keys` 并不会留下我们的公钥，下次连接照样弹。
也就是说**降低频率没有意义，只能彻底不做**。

**实测结论：这条能力在本 App 的所有场景里都是负收益**（所以不是「少用」而是「删掉」）：

| 场景 | 主动推公钥的结果 |
|---|---|
| Android 10 + TCP | adbd **不弹框、静默挂起**，20 s 超时无任何响应 |
| Android 15 + TCP | 弹框，但「一律允许」不持久化 ⇒ 下一条命令再弹 |

两条路都不通。**能拿到信任的只有两条路**，都不经过「主动发公钥」：

1. **Android 11+ 无线调试配对**：6 位配对码走 SPAKE2，设备把我们的公钥写进
   keystore，此后是**设备认得我们**，不需要我们推。
2. **Android 10 用 PC 推一把已授权的密钥**（`adb push %USERPROFILE%\.android\adbkey`）。

**改法**：`handshake(key)` **删掉 `allowAuthorize` 参数、永不发送公钥**，
签名失败直接抛错（`ADB 密钥被设备拒绝（授权可能已被撤销），请重新配对` /
`ADB 密钥尚未被设备授权，请用无线调试配对授权它（Android 10 需先用 PC 执行
adb push 推入已授权的密钥）`）；`connect()` 同步删掉 `allowAuthorize`，
`AUTH_AUTHORIZE_TIMEOUT_MS` 整个常量删除。

⚠️ **`AUTH_RSA_PUBLIC = 3` 这个常量要留着，并标「不要修好它」**：协议完整性
（将来抓包分析要用），但**没有任何代码路径会发它**。看到这个常量别手痒去接上。

##### ⚠️ 信任标记存的是**公钥指纹**，不是一个 bool

`$DSH_HOME/adbkey.trusted` 里存的是**公钥 blob 的 md5**，不是 `true`。

⚠️ **不能存布尔值**：密钥可以被替换（导入 PC 密钥、重新生成、重新配对），
换了一把之后旧的布尔值仍然是 `true`，于是「设备认得这把钥匙」这个判断变成
**假阳性** —— 而假阳性的后果正是上面那个弹框（我们以为设备认得，实际上不认得）。

- `markKeyTrusted(key)` —— `handshake()` 成功那一刻调用。
- `clearKeyTrust()` —— `importKeyIfNeeded()` 写入新密钥后调用（新钥匙要有一次
  被授权的机会），以及 `--pair` 成功后由 `markKeyTrusted(paired)` 覆盖。

##### ⚠️ `keyMayBeAuthorized()`：进「会弹框的通道」之前必须先过这道闸

**这是「删掉发公钥」之后必须补上的一半。** 打包进来的 adb 是**外部进程**，
它会**自己**弹那个对话框 —— 我们拦不住它。所以只能在**进入那些通道之前**挡掉：

```js
// 通道 1.5（切 TCP 再走 legacy）与通道 2（内置 adb + TLS）都要带这个条件
keyMayBeAuthorized()
```

判据是「**设备已经认得这把钥匙**」，三条**任一**成立即可：

| 条件 | 为什么算 |
|---|---|
| `isKeyTrusted(key)`（指纹匹配） | 我们亲手在这台设备上认证成功过 |
| `origin === 'imported'` | 那是 PC 已经授权过的密钥 |
| `origin === 'paired'` | 配对时设备把公钥写进了自己的 keystore |

⚠️ **不能只看信任标记。** 会死锁在这个场景：用户刚导入/刚配对完一把密钥，
**还没成功连过一次**（所以标记文件还没写），此时若 5555 没在监听、
只剩无线调试那条路 —— 只看标记就会拒绝进入通道 2，而通道 2 恰好是唯一
能让它连上并写下标记的路。**「刚配对完」正是最需要放行的那一刻。**

⚠️ 这个函数必须**只读**：不导入、不生成、不写盘。它是守卫，不是初始化。

##### ⚠️ 无线调试开关一关，通道就断 —— 所以必须切固定端口

**这是本轮最关键的实测结论。** 无线调试的 TLS 端口**只在开关开着时才存在**，
而且**关掉开关会立刻掐断已建立的连接**：

```
settings put global adb_wifi_enabled 0
  → 已连上的 adb-…._adb-tls-connect._tcp 立刻变 device offline
```

也就是说「连上后一直用无线调试」这条路是**死的**。而 `adb tcpip 5555` 把 adbd
重启到 TCP 模式，此后走 `127.0.0.1` 回环：

| 观测项 | 关掉无线调试后 |
|---|---|
| `adb_wifi_enabled` | 0 |
| `service.adb.tcp.port` | 5555 |
| `ss -tln` | `LISTEN *:5555` |
| 经 `127.0.0.1:5555` 跑 runner | **`uid=2000(shell)`** |

⚠️ **两者可以并存，不是互斥的**（我原先判断错了）：Android 15 实测
`adb tcpip 5555` **不会**关闭无线调试（`adb_wifi_enabled` 仍是 1）。所以这是
**纯增益动作**，不破坏用户设置。

⚠️ **`persist.adb.tcp.port` 实测为空** → **重启手机后 5555 消失**，需要重切。
所以 `dsh-android-shell.mjs` 的通道级联里加了「1.5 档」：legacy 不通但无线调试
可用时**自动重切一次**再走 legacy。这样重启后能自愈，用户通常不用手动点。

⚠️ 切换**必须先有一条活着的连接**（`tcpip` 命令本身要通过 adb 发出去）。

通道级联的完整顺序（`main()` 里）：

```
1   legacy 127.0.0.1:5555        自实现协议，最快；不依赖无线调试开关
1.5 legacy 不通 + 无线可用 → 切 TCP 再走 legacy    ← 重启后自愈
2   无线调试 <NsdManager 端口>    内置 adb + TLS
3   本地执行（app 身份）          回落，不是错误
```

##### 内置 adb 的二三事

配对走 **SPAKE2 + TLS-PSK**，Node 的 `tls` 模块**不支持外部 PSK**，所以打包了
Termux 官方的 `android-tools`（`assets/adb-wireless-arm64.zip`，`adb 1.0.41`，
5.26 MB / 105 个文件，含约 90 个 `libabsl_*.so`）。解到 `filesDir/adbwire/`，
判据是「`bin/adb` 存在且非空」，**无需版本戳**（与 ripgrep/node-pty 同类）。

⚠️ **adb 的运行环境三个变量缺一不可**（都踩过）：

| 变量 | 为什么 |
|---|---|
| `HOME` | adb 在 **`$HOME/.android/adbkey`** 找密钥。推到 `$HOME/adbkey` 会**静默自生成**一把新的 |
| `LD_LIBRARY_PATH` | adb 是 Termux 的 ELF，要它自己的 `.so`。**只对 adb 需要** —— 引擎进程绝不能设（见上一节） |
| `TMPDIR` | adb 硬编码往 **`/tmp/adb.<pid>.log`** 写启动日志，app 域无权写 `/tmp`（实测 `cannot open /tmp/adb.10535.log: Permission denied` → server 起不来） |

⚠️ **adb server 必须用 `-L localabstract:<name>`** 指定 socket 名（实测
`ss -xln` 里能看到 `@dshadb`），否则多进程会抢同一个默认 server。

⚠️ **没有配对需求的设备会「假成功」**：若 PC 的 adbkey 已在设备 keystore 里，
App 直接用它就能连上，配对流程根本不会被走到。判断依据是比对 md5
（本次实测 App 的 `$DSH_HOME/adbkey` = PC 的 `%USERPROFILE%\.android\adbkey`
= `d1f60cbb147060bed588e744a79e360e`）。**要在真机上验证配对，必须先把密钥
删掉重装**，否则测的是「复用 PC 密钥」那条路。

##### 设置页的无线调试卡片

界面是**结论优先**的：顶部一张 head 卡（状态徽章 + 当前通道摘要 + 一条「下一步该
做什么」+ `重新检测` / `查看详情`），下面才是可折叠的明细行。无线调试卡片带
**「推荐」标签**，位置**在电脑引导卡片之前**（Android 11+ 不用碰电脑，那是更短的
路径）；电脑引导卡片与注意事项都收进 `<details>`，通道已就绪时整个引导折叠成
「开启引导（当前已就绪，一般不用看）」。

⚠️ 「下一步」是按状态级联挑出来的（已就绪 → 提示可切固定端口；密钥是
`generated` → 提示配对；有密钥但通道没通 → 提示切固定端口；否则 → 提示选一条路径），
按钮直接调对应的动作，不再让用户自己从一堆按钮里挑。

⚠️ 「无线调试端口」行是**三态**，不是两态：`绿灯=端口活着` /
`灰灯=端口文件里那个值已经连不上（多半是无线调试被关了）` / `关=没有端口`。
判据是宿主半真的去 `connect` 一次（`portAlive()`），**不是「端口文件里有没有数字」** ——
只看文件会把关掉无线调试后残留的旧端口显示成绿灯（这个 bug 真实发生过）。

⚠️ 「实际身份」行会显示**当前走哪条通道**（`固定端口（关无线调试也不断）` /
`无线调试（关掉开关会断）`）—— 判据是从 runner 自己打的 `通道: …` 行里读的，
**不额外探测**。不显示这个的话，用户看到「已就绪」也不知道关掉开关会不会断。

#### 密钥从哪来（Android 10）：只能用 PC 引导一次

Android 10 **没有**无线调试配对机制（那是 API 30+）。而且实测：adbd 在
**TCP 连接**下**不会**弹「允许 USB 调试吗」对话框 —— 发完公钥后 adbd 直接静默
挂起，永远等不到授权（20s 超时无任何响应）。

所以只能复用一把**设备已授权**的密钥。引导命令（用户执行一次）：

```powershell
adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey
```

`dsh-android-shell.mjs` 首次运行时会自动导入并**立刻删除中转文件**：

```
[dsh-adb] 已导入 ADB 密钥: /sdcard/dsh_adbkey → .../home/.dsh/adbkey
[dsh-adb] 已删除中转密钥 /sdcard/dsh_adbkey（共享目录不留私钥）
```

⚠️ **中转文件必须删。** `/sdcard` 是所有应用可读的共享目录（实测
`-rw-rw---- root sdcard_rw`），ADB 私钥留在那里等于把设备控制权敞开放着。
实测 app 域**有权**删除它（`run-as … rm -f /sdcard/dsh_adbkey` 成功）。

⚠️ **公钥必须从私钥现场派生**，不要读 `adbkey.pub`：本机那个文件的格式**不标准**
（716 字节，缺 `ssh-rsa ` 前缀，且内容与私钥不匹配 —— 实测
`ssh-keygen -y -f adbkey` 与它不一致）。PC 的 adb 之所以能连上，是因为设备
`adb_keys` 里存的是**更早的另一把**公钥。派生才是唯一可靠来源。

#### 没授权时怎么办：两级降级

**第一级：通道本身没就位 → 工具根本不注册。**
`ensurePrivilegedShell()` 失败（资产缺失/落盘失败）时不设 `DSH_PRIV_RUNNER`，
插件在 `apply()` 里打一条 warning 后 `return`，`shell` 工具**不进工具表**；
`writePrivilegedPatch(false)` 同时删掉 overlay。此时模型看不到 `shell`，
而 `$DSH_HOME/AGENTS.md` 里写的是另一段文本（明确说「`shell` 工具没被注册，
别反复重试、也别试图绕过 app 身份」）。**`bash` 不受任何影响。**

**第二级：密钥没授权 → 逐条命令回落 app 身份。**
runner 存在但连不上 adbd（无密钥/密钥未授权/5555 没监听）时，
runner 自己**回落为本地执行（app 域）**，并把原因写进输出：

```
[dsh-adb] uid 2000 不可用（未找到 ADB 密钥（先用 PC 执行 adb push 导入））→ 回落为本地执行
```

⚠️ 这一级是**静默降级**：命令照样跑、退出码照样正常，只是身份变回了 app。
所以插件在 `DESCRIPTION` 里明确告诉模型「输出里出现 `[dsh-adb] uid 2000 不可用`
就说明这条命令实际是 app 身份跑的」，避免模型基于假前提继续推理。

#### ⚠️ 固定端口 5555 的可靠性与 18 条缺陷（2026-10-12 全面修复）

**用户症状**（三条，指向同一根因）：① 开无线调试拿到权限后**关掉 WiFi 权限就掉**；
② 关 WiFi 后权限没掉、但**清后台重开就掉**；③ 用 Shizuku 取权限 + **打开固定端口**后，
关 WiFi、关无线调试都**不掉**。

③ 是决定性证据：**正解是真正切到 5555 并守住它**，而不是「无线调试开关开着」这种
脆弱状态 —— 无线调试端口只在开关开着时存在，关掉开关立刻掐断已建立的连接。

场景 ② 的完整链条：没切成 5555 → 关 WiFi ⇒ 没有网络接口 ⇒ mDNS 发现必然失败 →
清后台被杀 → 重开时**没有任何东西**能拉起 5555。

⚠️ **判定「固定端口是否可用」的唯一权威判据是 `connect()` + `conn.shell('id -u')`
返回 `2000`**，不是「端口有人听」（`isPortListening` / `legacyAlive` 只验有人听，
拿不到 `2000` 也算成功，是**假阳性**）。`isPortListening()` 已删除。

当时修掉的 15 条（迁移前路径 `app/src/main/assets/dsh-android-shell.mjs`，现源码为 [runtime/scripts/dsh-android-shell.mjs](../../runtime/scripts/dsh-android-shell.mjs)，以及当时的
`WirelessDebugging.kt` + `EngineManager.kt` + `mobile-ui-plugin/dsh-android-mobile/`）：

| # | 缺陷 | 修法 |
|---|---|---|
| 1 | `switchToTcp` 只等 6 秒判 `legacyAlive()` ⇒ 切成功却报失败 | 新 `fixedPortWorks()` + `TCPIP_VERIFY_BUDGET_MS = 20_000` 轮询 |
| 2 | `--switch-tcp` 只读端口文件，而该文件在无线调试关闭时被删 | Kotlin 侧把 `knownPort` 作为 `argv[1]` 传下去 |
| 3 | 通道 1.5 / 2 以「端口文件有值」为门卫 ⇒ 循环依赖 | 去掉 `if (wlPort !== null)` 前置条件 |
| 4 | `startWatcher` 判据 `watcher != null`（线程死了也算在跑）且无看门狗 | 改 `watcher?.isRunning == true` + `ensureWatchdog()`（30 秒） |
| 5 | App 进程重启后陈旧端口文件不被清理 | `purgeStalePort()`：先 `Socket.connect(127.0.0.1, port, 700)` 探，连不上才删 |
| 6 | 全工程**零**周期性守护，无任何东西监听 5555 | `startFixedPortGuard()`：5 分钟一轮走完整 `ensureTcpPort`，连续 3 次失败退避到 15 分钟 |
| 7 | `EngineManager.start()` 早返回分支跳过整个自愈块（用户「清后台重开」命中的就是它） | 抽出 `resumePrivilegedChannel()`，早返回前也调 |
| 8 | `ensureTcpPort` 15 秒预算不够 | 先问 runner 再 mDNS 发现（13 秒），发现到的端口传下去 |
| 9/13 | `isPortListening` / `legacyAlive` 只验「有人听」 | 删 `isPortListening`，统一用握手 + `id -u` |
| 10/14 | `localabstract:dshadb-${pid}` 每条命令泄漏一个 adb server | 改 `ADB_SOCKET = 'localabstract:dshadb'`（去掉 pid） |
| 11 | 设置页把 4 类失败混成一句不可执行的原因 | runner 输出 `诊断: kind=… key=… legacy=… wireless=…`，宿主半 `DIAG_KIND_RE` 解析成 `identity.kind`，客户端按 kind 分支给「下一步」 |
| 12 | `keyMayBeAuthorized()` 的 `imported`/`paired` 短路绕过「删掉发公钥」那道闸 | `isKeyRevoked(key)` 检查**排在所有放行判据之前**；收到第二次 `A_AUTH` 即 `markKeyRevoked()` |
| 15 | 端口文件 `pairing` 字段恒 null 且无消费方 | 两侧收敛为单值：`readWirelessPorts()` → `readWirelessPort()` |
| 16 | 守护/看门狗线程可重入（共享布尔量当判据）⇒ 两个守护并存，各自周期往 adbd 发 `tcpip` 互相打断 | 循环判据改成**代际比对** `guard === Thread.currentThread()`；`stopXxx()` 先置空字段再中断旧引用 |
| 17 | `markKeyRevoked()` 无条件落盘 ⇒ 全新设备上「必然被拒」的 `generated` 密钥被误记成「授权已被撤销」，把设置页结论引向错误方向，还多叠一道只有配对才能解的闸 | 只在 `origin = imported\|paired` 或 `isKeyTrusted()` 时记（与 `keyMayBeAuthorized()` 的放行条件逐条对齐） |
| 18 | `importKeyIfNeeded()` 以「密钥文件在不在」为早返回判据 ⇒ **已撤销**（或损坏）的密钥被当成可用，用户「重新推一把密钥」这条补救路径被吞掉；**Android 10 上是死锁**（没有无线调试，配对那条路走不通） | 判据改成「有没有一把**设备可能还认**的密钥」（`generated` / 已撤销 / 解析不出来都继续）；并新增 `readKeyFileNoImport()` + `isStagedNewerThanDest()` 支撑「重新推入即解除撤销」 |

⚠️ **缺陷 16 的机理值得记一笔（`interrupt()` 打不断阻塞读）**：守护线程会卡在
`ProcessBuilder` 的 `forEachLine` 上读外部进程输出（最长 30 秒），`Thread.interrupt()`
**对它无效**。所以 `stopFixedPortGuard()` 置 `guarding = false` 之后线程并不会马上退出；
若此时用户又触发一次 `start()`，字段被置回 `true`，旧线程醒来看到 `true` 就**继续跑**。
判据改成「我还是当前这一代吗」之后，旧线程必然自己退出。
`ensureWatchdog()` 是同型问题（`stopWatcher` → `startWatcher`），已一并改。
另外看门狗重建 `AdbMdns` 时，「写字段 → `start()`」两行之间被 `stopWatcher()` 穿过
会 stop 一个尚未 start 的对象（等于没停）⇒ 监听常驻泄漏，故启动后补一次代际自校验。

⚠️ **缺陷 17 的判据要与 `keyMayBeAuthorized()` 逐条对齐**，别简化成「握手失败就记」：
`generated` 且从未配对成功的密钥被设备拒绝是**正常**的（全新设备就长这样），
`keyMayBeAuthorized()` 本来就会挡它。把它记成 revoked 有两个坏后果：设置页会说
「多半是你点过『撤销 USB 调试授权』」（用户没做过），以及多叠一道**只有配对/重新导入
才能解除**的闸 —— 而原本只需一次成功握手就能自愈。

⚠️ **缺陷 18 是 17 的「下游」**：17 修好「什么时候记 revoked」，18 修「记了之后怎么解除」。
两处合起来才是完整闭环，只修一处会在 Android 10 上留下死锁。判定条件是**三种而不是一种**
（`generated` / 已撤销 / 解析不出来），三者都允许被 `/sdcard/dsh_adbkey` 顶掉。
「重新推入」的判据用 **mtime 而不是内容**：内容相同时既可能是用户刚重推（要清撤销标记，
这是 Android 10 的唯一自救路径），也可能是上次 `unlinkSync` 没成功留下的残渣
（**不能**清，否则每次启动都会放行一次可能弹授权框的通道）。容差 2 秒适配 `/sdcard`
的时间戳粒度，取不到 mtime 时一律当作「不是新推的」。

⚠️ **`importKeyIfNeeded()` 里不能调 `loadKey()`**：`loadKey()` 第一行就是
`importKeyIfNeeded()`，直接无限递归（栈溢出，症状是启动即崩、看不出与密钥有关）。
所以额外提供了不做导入/生成的 `readKeyFileNoImport()`。

⚠️ **本轮的教训：这类纯判据逻辑可以离线断言，别拿真机试。** 把 runner 的源码
（剥掉末尾 `main().catch(...)`）当模块 import，配合 `DSH_HOME` 指向临时目录、
`C:\sdcard` 建目录模拟 `/sdcard`，就能把 17/18 的全部判据跑成 38 项断言，几秒出结果。
**这类断言真的抓到了东西**：`classifyFallback()` 的 `key-revoked` 与 `wireless-stale`
两条文案里带了全角 `）`，违反上面那条接口约束（会让设置页的原因显示被截断成半句）——
`node --check` 与编译都不会报，只有把正则从宿主半源码里抠出来跑一遍才发现。

⚠️ **新增守护引入的设计风险（已自纠，别改回去）**：固定端口守护每 5 分钟调一次
`switchToTcp`，而它要用内置 adb 连无线端口 —— **未授权密钥会被放大成「每 5 分钟弹一次
『允许 USB 调试吗』」**。所以 `switchToTcp` 里必须有 `keyMayBeAuthorized()` 守卫，
且 `ensureKey()` 要排在守卫**之前**（否则「还没有密钥」会误报成「未授权」）。
另外 `fixedPortWorks()` 的幂等短路要排在**密钥/内置 adb 检查之前** —— 否则
「5555 已就绪但密钥未授权」会被误判成失败。

⚠️ **回落文案的格式是接口**：`诊断: kind=` 必须与宿主半 `DIAG_KIND_RE = /诊断: kind=([a-z-]+)/`
逐字对应；`classifyFallback()` 产出的 text 里**不可出现全角 `）`**，否则会截断宿主的
`FALLBACK_RE = /uid 2000 不可用（(.+?)）/`。kind 取值：
`no-key` / `key-revoked` / `key-unauthorized` / `adb-missing` / `key-blocked` /
`wireless-stale` / `no-channel` / `unknown`（宿主半另有 `no-runner` / `timeout`）。

⚠️ **宿主半的探测超时必须覆盖 runner 的最坏路径**：`PROBE_TIMEOUT_MS` 从 15_000
提到 **45_000**（runner 最坏 ≈30 秒）。改 runner 的预算就必须同步改它，否则设置页
会把「还在切」误报成失败。

⚠️ **`startFixedPortGuard` 的闸是 `switching: AtomicBoolean`**：`ensureTcpPort()` 现在是
薄包装（CAS 失败返回「已有一次固定端口切换正在进行中」），主体在
`ensureTcpPortLocked()`；守护撞上 `switching.get()` 时 `continue`（**不计失败**），
否则会把「用户正在手动切」累计成 3 次失败而退避。

⚠️ **改了 `mobile-ui-plugin/` 就绕不开两个资产戳**（本轮都动过，别只改一个）：
`EngineManager.MOBILE_UI_VERSION` → `1.0.0-56`，且重跑
`tools/build-webview-compat.ps1`（`-CompatVersion` → `12`，戳变 `chrome74-v12`）。
**这两个 zip 是预打包资产，源码改了必须重打**，否则「编译通过但改动不生效」——
本轮真实踩到过一次（首轮构建后解包 APK 发现 `DIAG_KIND_RE` 不在里面）。
验证手法就是解包 APK 断言：`assets/mobile-ui.zip` 内应含 `诊断: kind=` 与
`PROBE 45000`，`assets/webview-compat.zip` 的戳应是 `chrome74-v11`。

#### ⚠️ 走这条通道会绕过沙箱（这是固有代价，不是缺陷）

uid 2000 **读不到 app 私有目录**（实测 `/data/user/0/com.dsh.android` 是
`drwx------`），而 `libboosandbox.so`（写沙箱）与 `liblinkcompat.so`
（link 转译）都放在那里 —— 所以经 adbd 执行的命令**必然**拿不到 `LD_PRELOAD`，
**写沙箱与 link 转译对它都不生效**。

也就是说：**shell 身份换来的是特权，失去的是隔离。**

⚠️ 因此**不能**用「首词白名单」之类的方式去猜哪条命令该走哪个域并自动切换 ——
那会让 `touch /sdcard/x` 这类写在不知不觉中绕过沙箱。**选择权交给模型**
（它显式选择调 `shell` 而不是 `bash`），调没调这个工具在会话里看得见，边界清清楚楚。

⚠️ **cwd 会丢**：adbd 执行命令的 cwd 恒为 `/`，而工作区 uid 2000 进不去。
所以 runner 用 `cd '<cwd>' 2>/dev/null; <command>`（**不是** `&&`）——
切不过去就留在 `/` 继续跑，而不是让整条命令失败。特权命令本就不该依赖
工作区里的相对路径。（插件只把 `workdir` 透传给 runner，且工具描述里写明
它只对 `/sdcard` 这类共享路径有意义。）

#### 系统提示词怎么告知模型

**两条通道，分工不同**：

1. **工具描述**（插件里的 `DESCRIPTION` 常量）—— 这是**主通道**。模型是靠工具
   描述来决定调不调 `shell` 的，所以「什么时候用、什么时候别用、退出码会骗人」
   三件事都写在那里。
2. **`$DSH_HOME/AGENTS.md`** —— 补充说明（完整的 app 域 vs shell 域对照表）。
   `@deepseek-ai/dsh-agent-instructions` 会自动读它并注入系统提示词，
   **零代码**的通道，不需要改引擎、也不需要挂 overlay。

`EngineManager.ensurePrivilegedPrompt()` 只管理自己那一段
（`<!-- dsh-android:privileged BEGIN -->` / `END` 之间），**标记之外的用户内容
原样保留**。通道不可用时写的是另一段文本（明确告诉模型「`shell` 工具没被注册，
别反复重试」）。

**为什么必须让模型知道**：上面那张表里的 `input`（rc=0 但无效）和
`dumpsys`/`ps`（rc=0 但残缺）都是**静默失败** —— 模型若不知道自己在哪个身份下，
会基于假前提继续推理、越走越错。

#### 验收手法（数值证据）

```powershell
# 插件解出来了没、工具注册了没（两行都要有）
adb logcat -d -s DSH:I | Select-String "uid 2000|特权工具|特权身份说明|dsh-android-shell"
#   → 已解出 uid 2000 执行通道 dsh-android-shell.mjs (21322 字节)
#   → 已安装 uid 2000 特权工具插件 dsh-android-shell (<N> 字节)
#   → 已写入 uid 2000 特权工具 overlay: shell-tool.patch.yml
#   → 已写入特权身份说明: AGENTS.md（通道可用=true）
#   → [engine] [dsh-android-shell] 已注册 uid 2000 特权工具 shell: /data/.../dsh-android-shell.mjs

# 工具真的进了模型可见的工具表（`shell` 应与 bash 并列出现）
adb shell run-as com.dsh.android cat files/home/.dsh/shell-tool.patch.yml
adb shell run-as com.dsh.android ls -l files/home/.dsh/shell-tool/dsh-android-shell/lib/index.js

# 通道本身能不能拿到 uid 2000（应输出 2000）
adb shell run-as com.dsh.android sh -c "DSH_HOME=/data/data/com.dsh.android/files/home/.dsh \
  /data/data/com.dsh.android/files/usr/bin/node files/dsh-android-shell.mjs 'id -u'"
#   → 2000

# 截图（app 域是 0 字节，这里应是几百 KB）
adb shell run-as com.dsh.android sh -c "DSH_HOME=... node files/dsh-android-shell.mjs \
  'rm -f /sdcard/r.png; screencap -p /sdcard/r.png; ls -l /sdcard/r.png'"
```

⚠️ **`bash` 的行为必须与改动前一致**：无论 GUI 里的权限预设怎么设，
`bash` 都应以 app 身份执行（`id -u` 给的是 `u0_a…` 而不是 `2000`）。
这是本次改动的核心断言 —— 若 `bash` 仍走 uid 2000，说明旧的 `--import` 加载器
还没删干净（检查启动命令行里是否还有 `--import`）。

#### 设置页的「特权权限」引导分节

用户不该去读 AGENTS.md 才会开这个权限。设置页左侧一级导航里有一项
**「特权权限」**（`dsh-android-mobile` 的客户端半注册的），布局是
**结论优先**，不是「先摆一堆步骤让用户自己对照」：

- **head 卡（默认展开）**：状态徽章（已开启 / 未开启）+ 一句话通道摘要 +
  **一条「下一步」**（带一个直达按钮）+ `重新检测` / `查看详情`。
- **明细行（折叠在「查看详情」里）**：执行通道 / ADB 密钥 / 实际身份 / 无线调试端口。
- **两条路径的引导卡片**：无线调试（Android 11+，带「推荐」标签）在前，
  电脑引导（4 步 + 两条带复制按钮的等宽命令）在后，各自收进 `<details>`；
  通道已就绪时**整个引导**再折一层（「开启引导（当前已就绪，一般不用看）」）。
- 注意事项卡片同样折叠。

⚠️ 结论优先是**用户明确要求的重设计**（原版是「徽章 + 三行状态 + 一堆按钮平铺」，
用户要自己判断该点哪个）。改回去之前先问。

⚠️ 折叠用原生 `<details>`，不引入任何 UI 依赖 —— 本插件的定位是「窄屏适配 +
补充面板」，不重写官方组件。

⚠️ **复制按钮不是锦上添花，是必需品。** 本插件的全局 CSS 是
`[data-mobile] * { user-select: none !important }`（文件头 4c，为了消灭长按弹菜单），
命令块**长按选不中**，而用户是在电脑终端里敲这两条命令、只能照着手机屏幕抄 ——
没有按钮就得逐字符手抄 `%USERPROFILE%\.android\adbkey`。
（内联 `user-select: text` 压不过 `!important`，别想靠那个绕。）

⚠️ **写剪贴板必须留降级路径。** 真机实测（Huawei CDY-TN90 / Android 10 WebView）：

| 路径 | 结果 |
|---|---|
| `window.isSecureContext` | **true**（`127.0.0.1` 算 trustworthy origin） |
| `navigator.clipboard.writeText` | 存在，但抛 **`NotAllowedError: Write permission denied`** |
| `document.execCommand("copy")` + 临时 textarea | **返回 true，内容正确落进剪贴板** |

也就是说**光看 `isSecureContext` 或「API 在不在」都会误判成可用**。
必须 `navigator.clipboard` 失败后回落到 `execCommand`。已用「复制后粘贴到临时
textarea 再读回」逐字节验证（`execCommand:true` → 粘贴内容与命令完全一致）。

⚠️ **测这个按钮不能用 `element.click()`**：合成点击**不算用户手势**，`execCommand`
会返回 `false`，于是测出「复制失败」的**假象** —— 本次调试中真的被它误导过一轮。
要用 CDP 的 `Input.dispatchMouseEvent`（`mousePressed` + `mouseReleased`）发真实鼠标事件。

实现分两半，**跨进程**：

| 半 | 文件 | 做什么 |
|---|---|---|
| 客户端半 | `dsh-android-mobile/lib/client.js` | 注册 `settings.section`（`id: "dsh-android-privileged"`，`order: 25`），渲染分节 UI |
| 宿主半 | `dsh-android-mobile/lib/index.js` | `GET /api/dsh-android/privileged` 返回状态快照 |

浏览器进程读不到 `process.env` / 文件系统，所以状态必须由宿主半提供。
宿主半用 `ctx.webServer.register({kind:"exact", path, handler})` 挂路由
（`inject: ["webServer"]`）—— 这是引擎给插件留的自定义 HTTP 接缝。

⚠️ **状态判据必须是「端到端跑一次 `id -u`」，不能是「`adbkey` 文件在不在」。**
密钥文件存在但未被 adbd 授权（设备 `adb_keys` 里没有对应公钥）时，通道照样
拿不到 uid 2000 —— 只查文件会得到**假阳性**。所以宿主半实际 spawn 一次
runner 跑 `id -u`，看它回什么；文件存在性只作为定位用的分解信息。
探测会顺带触发 runner 的密钥导入，所以点「重新检测」就等于走完引导最后一步。

⚠️ **`slots.register` 要求槽位已被声明**，而 `settings.section` 由
`dsh-client-ui-settings-general` 的 `settings.root` factory 在 `children` 里声明。
用 `ctx.slots.inject("settings.section", …)` 等它 —— 声明已存在时同步执行，
否则在声明提交后执行。

⚠️ **`ctx.locale` 必须从 `ctx.inject` 的回调参数取，不能从外层 `ctx` 取。**
Cordis 的 `ctx.locale` 要求**当前 fiber 声明过** `locale` 依赖，否则抛

```
Error: cannot get property "locale" without inject
```

本插件的 `inject` 是空数组（客户端半刻意不声明依赖，避免某个服务缺失就整个不激活），
所以外层 `ctx.locale` **一定**不可用。正确写法是 `ctx.inject(["slots","locale"], (scope) => …)`
然后用 `scope.locale` / `scope.slots`。

> 官方 `ui-approval` 写的是 `ctx.locale.bind(NS)`，那是**因为它的 `inject` 数组里
> 声明了 `locale`**。照抄那一行在本插件里必炸 —— 这里踩过一次。

⚠️ **异常会变成全局未捕获异常，`try/catch` 捕不到。** `slots.inject` 内部对回调
异常的处理是 `queueMicrotask(() => { throw failure })`，所以「分节没出现、
日志里什么都没有」是典型症状。排查手法：在回调**内部**包 try/catch 并
`console.warn`（页面 `console` 会转发到 `DSH` tag，前缀 `[web]`）。

**验收手法**（数值证据）：

```powershell
# 插件版本要变（改了 mobile-ui-plugin 必须 bump MOBILE_UI_VERSION）
adb logcat -d -s DSH:I | Select-String "移动端 UI 插件"
#   → 已安装移动端 UI 插件 dsh-android-mobile 1.0.0-19 (<N> 字节)

# 状态接口（用 app 自己的 node 访问，设备上没有 curl）
adb shell "run-as com.dsh.android sh -c 'cp /data/local/tmp/probe.mjs files/p.mjs; \
  /data/data/com.dsh.android/files/usr/bin/node files/p.mjs; rm -f files/p.mjs'"
#   → {"runner":{"ok":true,"size":21322},"key":{"ok":true,"size":1732},
#      "identity":{"ok":true,"uid":"2000"}}

# 分节真的进了导航、二级页真的渲染（走 CDP）
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
# 在页面里查：navButtons 应含 "特权权限"，点进去 content.innerText 应含
# "adb tcpip 5555" / "adb push" / "uid 2000"
```

⚠️ **改了 `mobile-ui-plugin/` 必须同时做两件事**：重新打 `mobile-ui.zip` +
bump `EngineManager.MOBILE_UI_VERSION`。漏 bump 的后果是**静默的** ——
版本戳没变 → 解包被跳过 → 新代码永不生效（本次调试中真实踩到过一次）。

⚠️ **重打 zip 必须先删旧文件，不能用 `-Force`。** PowerShell 的
`Compress-Archive -Force` 对**已存在的归档不会替换里面的条目**（会保留旧内容），
于是打出来的 zip 里是**上一版代码**，而版本戳已经 bump 过 → 解包照常执行 →
装进去的是旧代码。症状是「日志说装了新版本，但新代码一点没生效」。

```powershell
Remove-Item app\src\main\assets\mobile-ui.zip -Force
Compress-Archive -Path mobile-ui-plugin\dsh-android-mobile\* `
  -DestinationPath app\src\main\assets\mobile-ui.zip
```

⚠️ **验证 zip 里真的是新代码**（别只看文件大小）：

```powershell
# 把 zip 解开 grep 新加的函数名/文案，计数应为非 0
```

⚠️ **别在 `MP_PRIV_S` 这类对象字面量里引用它后面才定义的常量。**
本插件用一堆 `MP_PRIV_S.xxx` 内联样式对象，而 `MP_PRIV_TONE` 定义在
`MP_PRIV_S` **之后** —— 在 `MP_PRIV_S` 里写 `color: MP_PRIV_TONE.on` 会在
**模块求值期**抛

```
Cannot access 'MP_PRIV_TONE' before initialization
```

整个插件 import 失败，界面是空白的「Failed to load plugins」页
（日志里是 `dsh-android-mobile: import failed: …` +
`web boot: 1 entry did not activate`）。**颜色值直接写字面量**，
`MP_PRIV_S` 里那几处 `MP_PRIV_TONE` 只允许出现在注释里。

#### ⚠️ 不要设 `LD_LIBRARY_PATH`（它只会破坏系统二进制）

启动引擎的 env 里**刻意没有** `LD_LIBRARY_PATH`。这一行曾经存在（当作「RUNPATH 没覆盖
到时的兜底」），但它是**净负面**的：

| | 带 `LD_LIBRARY_PATH=$PREFIX/lib` | 不带 |
|---|---|---|
| `$PREFIX/bin` 下 **305 个 ELF** 全量对比 | —— | **0 差异** |
| `screencap` / `input` / `app_process` | **rc=127 `CANNOT LINK EXECUTABLE`** | 正常（该失败还是失败） |

原因：Termux 二进制的 RUNPATH 已被 `patchElfInPlace()` 等长重写成
`/data/data/com.dsh.android//usr/lib`，**自己就找得到 .so**，根本不需要这行；而系统
二进制本来该去 `/system/lib64` 找依赖，被这行带去 Termux 的 lib 目录后，加载到 Termux
的 `libz.so` 而不是系统的，于是报

```
CANNOT LINK EXECUTABLE "screencap": cannot locate symbol "CrcGenerateTable"
  referenced by "/system/lib64/libunwindstack.so"
```

⚠️ **危害是误导而不是功能缺失**：`screencap` 在 app 域本来就因权限失败，现在失败得更早、
错误信息指向动态库，模型会被带偏去反复试 `env -u LD_PRELOAD` 之类的无用方向 ——
真机会话日志里实测发生过（连试三轮才放弃）。

去掉后实测正常：node 本体、4 个原生模块（`node-addon-require-builtin` / flock /
`node-pty` / `sharp-wasm32`）、`bash` / `dpkg` / `pnpm` / `rg` / `apt` / `tar` / `curl`
全部照常，`LD_PRELOAD=liblinkcompat.so` 也照常生效（link 转译实测仍 OK）。

#### 跨身份文件交接：`/sdcard` 要授权，否则绕道 app 自己的外部目录

`shell`（uid 2000）在 `/sdcard` 上建的文件属主是 **`root:sdcard_rw`、模式 `640`**，
`chmod 666` **改不动**（sdcardfs 的固定行为）。而 app 进程的附加组里没有 `sdcard_rw`
（实测引擎进程 `Groups: 3003 9997 20324 50324`），于是 `shell` 截的图 `bash` 读不了。

**机制**（真机 A/B，同一个 pid、同一次进程生命周期内切换）：

| 观测项 | 未授权 | 已授权 |
|---|---|---|
| `READ/WRITE_EXTERNAL_STORAGE` | `granted=false` | `granted=true` |
| 进程 `Groups` | `3003 9997 20324 50324` | **完全没变** |
| `/storage/emulated` 绑定视图 | `gid=1015(sdcard_rw), mask=6` | **`gid=9997(everybody), mask=7`** |

对照 launcher（无写权限）：`gid=9997, mask=23`。

⚠️ **机制不是「授权后进程多一个 GID」** —— `Groups` 一个字节都没变，
`platform.xml` 里这两条权限也确实没有 gid 属性。真正变的是 **sdcardfs 按权限把
`/storage/emulated` 绑到不同的 runtime 视图**：未授权落在 `default` 视图
（gid=1015，进程没这个组 → 读不了），授权后切到 `write` 视图（gid=9997，进程自带 →
组对上），再配合 `derive_gid` 把底层文件的 gid 1015 派生映射成视图的 gid 9997。
**授权是唯一钥匙，`chmod` 绕不过去。**

做法：manifest 声明 `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`，
`MainActivity.ensureStorageAccess()` 在启动时申请一次（已授予则静默返回）。
targetSdk = 28 走 legacy 模型，不需要 `MANAGE_EXTERNAL_STORAGE`。

⚠️ **用户拒绝也不阻塞**：退回原状而已 —— 跨身份交接绕道 app 自己的外部目录
（`/sdcard/Android/data/com.dsh.android/files/`，那里属主是 app 自己，
`shell` 写进去后 `bash` 读得到）。这条备用路径在真机会话里被模型自己摸索出来过
（连试 4 轮），值得写进工具描述。

⚠️ **调试时注意 `pm revoke` 要带 `--user 0`**，否则静默不生效；
而 `run-as` 的组集合含 `sdcard_rw`(1015)/`sdcard_r`(1028)、**不含** `9997`，
与真实引擎进程**正好相反** —— 用 `run-as` 验证跨身份读取会得到**假阳性**。

**已知代价**（如实记录）：
- **Android 10 每次重启后要重新引导**：`persist.adb.tcp.port` 实测为空，
  重启后 5555 不再监听，需要 PC/USB 再 `adb tcpip 5555` 一次。
  这是平台限制（Android 10 没有无线调试），改不了。
- **Android 11+ 可以完全免 PC** —— 走无线调试配对，见上一节
  「密钥从哪来（Android 11+）」。配对成功后切到固定 TCP 端口，此后**关无线调试、
  关 Wi-Fi 都不影响**（实测）。
- **卸载重装会清掉 `$DSH_HOME`**（Termux 环境、全部会话日志、`adbkey`），
  重装后要重新 bootstrap。Android 10 要再用 PC 导入一次 adbkey；
  Android 11+ 只需重新配对一次（也是手机上点几下，不用电脑）。

