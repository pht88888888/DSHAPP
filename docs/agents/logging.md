# 日志与诊断

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 当前模块边界

[core/logging](../../core/logging/src/main/java/com/dsh/android/log/DshLog.kt)拥有日志门面、缓冲、轮转、脱敏、基础事件与崩溃记录，不主动引用 EngineManager、Activity、Service 或 app BuildConfig。运行身份与版本由 app 初始化时注入；物理模块迁移保留 `com.dsh.android.log` 的 Kotlin package，既有调用方式兼容。

跨领域采集在 app 组装：[DiagnosticBundle](../../app/src/main/java/com/dsh/android/diagnostics/DiagnosticBundle.kt)、[EnvironmentSnapshot](../../app/src/main/java/com/dsh/android/diagnostics/EnvironmentSnapshot.kt)与[LogUploader](../../app/src/main/java/com/dsh/android/diagnostics/LogUploader.kt)汇集领域快照、打包和上传。它们仍可保留历史 Kotlin package；诊断不再成为日志底座反向依赖所有模块的理由。通知、WakeLock 与 Window 各由 Service/Activity owner 管理。

以下保留日志策略、故障证据和历史排障记录；旧行号、调用数与当时方法名只描述对应历史版本，不作为当前源码导航或验收结果。当前结构和实际完成情况见[实施状态](../architecture/execution-status.md)，测试输入见[分层测试手册](../runbooks/testing.md)。

### ⑫.6 日志与诊断 —— 拿到日志就能定位问题

**这套系统解决的是一个此前一直存在的空洞**：全工程 476 处 `Log.*` 只写 logcat，
**进程一死现场就没了**。而本项目最需要排查的恰恰是「任务跑一半自己结束了」
「App 突然闪退」「装了某个插件后打不开」这类**伴随进程消失**的问题 ——
logcat 里只剩一片空白。

#### 为什么不是「多打点日志」

「极其强壮」在这里有具体含义，四个失败模式各自要堵：

| 失败模式 | 症状 | 对策 |
|---|---|---|
| 进程被杀，缓冲没落盘 | 崩溃前最后几行丢了 —— 而那正是要的 | 应用日志**同步写 + 每行 flush** |
| 高频日志把磁盘写爆 | 用户手机卡、存储满 | 1 MB 轮转 + 32 MB 总配额 + 限流折叠 |
| 崩溃处理器自己死锁 | 「闪退」变「永久卡死」 | 崩溃路径用 `tryLock`，**绝不阻塞** |
| 日志把密钥带出去 | 上报即泄漏 | 落盘前统一脱敏（[Redactor]） |

#### 三层存储

| 层 | 文件 | 写入方式 | 为什么 |
|---|---|---|---|
| 内存环形缓冲 | （不落盘） | 同步入队 | 4000 条，**最新**。崩溃时磁盘可能还差几行，这里补上 |
| 应用日志 | `app.log` | **同步 + 每行 flush** | 崩溃安全。代价是慢，所以只给 476 处 `DshLog` 用 |
| 引擎/网页日志 | `engine.log` / `web.log` | 异步队列（4096，满了丢） | 高频。**绝不能阻塞**引擎的 64 KB stdout 管道 |

⚠️ 三档写入策略是刻意的，不是不统一：`app.log` 慢但丢不了，另两个快但可能丢尾巴 ——
把它们对调会让引擎的 stdout 管道堵死，症状是「引擎卡住不动」而不是「日志少了」。

#### 476 处调用点一行不改 —— `DshLog` 门面

改造技巧：**换掉 `Log` 这个名字**，而不是改 476 个调用点。

```kotlin
-import android.util.Log
+import com.dsh.android.log.DshLog as Log
```

`DshLog` 的方法名/参数顺序/返回类型与 `android.util.Log` **逐一对应**（`v/d/i/w/e/wtf`
各两档、`println`、`isLoggable`、`getStackTraceString`），所以 `Log.i(TAG, "…")`
一个字符都不用动。

⚠️ **三参重载的 `tr` 必须是 `Throwable?`**：Java 侧 `android.util.Log` 的参数是平台类型，
Kotlin 调用点传可空值（实测 `GeckoWebHost.kt:516` 的 `onError` 回调就是 `Throwable?`）。
写成非空会让 **gecko flavor 编译失败而 system 通过** —— 这种只在单个 flavor 暴露的
签名问题最容易漏（本系统开发时就漏过一次）。

⚠️ **调用点里不能出现全限定写法**（`android.util.Log.i(...)`），那会绕过门面。
改造前已 grep 确认零处。

⚠️ `AppLogger.kt` 已删除。它此前与这套**并存**，而它的 `init()` 从来没被调用过
（只在 `TermuxBootstrap` 里当 tag 用）—— 两套日志系统并存会把日志劈成两半。

#### 四个崩溃入口（都在 `CrashRecorder`）

| 类别 | 现场文件名 | 判据 |
|---|---|---|
| 未捕获异常 | `<时间>-uncaught-exception.txt` | 装了默认 `UncaughtExceptionHandler` |
| 渲染进程崩溃 | `<时间>-renderer-crash.txt` | WebView/GeckoView 报 `didCrash` |
| 渲染进程被回收 | `<时间>-renderer-reclaimed.txt` | 同上但 `didCrash=false`（**不是崩溃**） |
| 引擎退出 | `<时间>-engine-exit.txt` | `Process.waitFor` 返回，带退出码与是否预期 |
| 主线程卡死 | `<时间>-main-thread-stall.txt` | 自建看门狗（5 s ping，15 s 无响应）；**含主线程栈快照** |

⚠️ **历史兼容说明**：`ApplicationExitInfo` 自 API 30 引入，minSdk 26 的设备不能统一依赖此 API；targetSdk 28 本身不等于设备上不存在 API 30。当前退出判断仍按本项目 `BootState` 的持久标记与真实现场工作：启动写 `boot-state.json` 为“正在运行”，正常退出改为“干净退出”。下次读到“不干净”只证明上次没走正常路径，不凭它推断具体死因。

#### ⚠️ 诚实分级是硬要求：**「非正常结束」不等于「崩溃」**

`BootState.Previous.describe()` 的措辞是「上次未正常结束（时间，**无堆栈**）」，不是
「崩溃了」。因为「不干净」有五种可能，而它们的排查方向完全不同：

| 真因 | 特征 | 下一步 |
|---|---|---|
| 未捕获异常 | 有 `uncaught-exception` 现场 | 看堆栈 |
| LMK 内存回收 | 无现场，内存快照里 heap 高 | 看内存 |
| ROM 冻结/后台清理 | 无现场，常在息屏后 | 看保活设置 |
| 用户强停 / 划掉 | 无现场 | 问用户 |
| 断电 / 重启 | 无现场，`uptimeMs` 短 | 问用户 |

⚠️ **看门狗记的是「主线程无响应（自建看门狗，非系统 ANR）」**。`targetSdk 28` 下拿不到
系统 ANR 堆栈，**绝不能伪造一个** —— 编一个看起来像 ANR 的堆栈比不写更糟，
它会把排查带向错误方向。

⚠️ **但现场文件里现在有一段真抓的快照，别把它当成伪造的 ANR 栈**：`main-thread-stall`
的 `--- stack trace ---` 段下是 `--- main thread stack (watchdog snapshot) ---`
（主线程 Java 栈，`Thread.getStackTrace()`）+ `--- main looper queue ---`
（`Looper.dump()`，会打出队列里**正在分派**的那条消息）。两段各自标注了是谁抓的。

- 为什么是**限时**抓：`Thread.getStackTrace()` 抓**别的**线程要等它走到 safepoint；
  主线程若卡在 native 里可能**永远**到不了，抓栈调用自己会无限阻塞 —— 那会把 watchdog
  线程一起拖死（watchdog 死了就再也发现不了卡死）。所以两段都跑在独立线程里、
  最多等 1 秒，超时写一句**有结论**的话（「到不了 safepoint」把「卡在 Java 代码里」
  这一整类排除了），而不是留空。
- `Looper.dump(Printer, String)` 在 SDK 里是 **`@hide`**，编译期直接引用会
  `Unresolved reference`，必须反射；且它要抢 `MessageQueue` 锁，所以同样必须限时。

**这条快照是补出来的，不是一开始就有**（2026-10-15）：服务器侧 4 次独立的真实用户
`main-thread-stall`（`20261014-225014` / `-225538` / `20261015-010934` / `-012538`，
均 `debuggable:false`）`gap` 是 15070 / 19248 / 19622ms —— 全部落在
`(15s, 20s]` 这个硬上界窗口里，即**主线程真的卡了**，可四份现场一份堆栈都没有，
定位只能靠猜。补上之后真机验证（人为阻塞主线程 20 秒）现场文件直接指到了
`MainActivity.onCreate(MainActivity.kt:640)` 与积压的 26 条 Looper 消息。

⚠️ **`CrashRecorder` 必须用 `tryLock`，永远不能用 `lock`**：崩溃可能发生在**已经持有
日志锁的那个线程**上，阻塞等待会把「闪退」变成「永久卡死」。拿不到锁也要照写现场文件
（那条路径不碰锁）。

#### 两个入口（缺一个就有一半场景看不到日志）

| 入口 | 在哪 | 覆盖场景 |
|---|---|---|
| ① 浮层按钮 | 加载/崩溃页上的「运行日志与诊断」 | **GUI 打不开**时 |
| ② 设置页分节 | 设置 →「运行日志与诊断」（`settings.section`，`order: 28`） | GUI 正常时 |

⚠️ **①在浮层上是「所有状态都显示」的**，与 `overlaySafeMode`（`isError` 门控）不同。
理由：这条入口的存在意义就是「界面不正常」，把它做成错误态才显示等于自相矛盾。

⚠️ **②必须是 `settings.section`，不能做成页面插件**。页面插件跑在 WebView 里，
而「引擎起不来」「插件把前端搞崩了」恰恰是页面插件一起死掉的场景 ——
用页面插件等于在最需要日志的时候把入口也弄没了。

⚠️ **`LogViewerActivity` 必须零依赖**：纯原生、不碰引擎、不碰 WebView、不初始化任何东西。
它是崩溃循环里唯一还能开的界面。所以列表用**框架 `ListView` + `BaseAdapter`**，
而不是 `RecyclerView` —— `material:1.12.0` 只传递来 `recyclerview:1.0.0`（compile scope），
用它就得加依赖，而「能开」是这条入口的唯一 KPI。

⚠️ 它读的是 `LogStore.entries()`（内存缓冲）而**不是**磁盘文件：磁盘上的 `engine.log`
是异步刷的，最后几行可能还没落盘，而用户要看的经常就是「刚刚那一下」。

#### 诊断包（`DiagnosticBundle`）—— 输出本身就要能定位问题

`diagnostic-<时间>-<bootId>.zip`，条目**按价值排序**写，超 8 MB 从低价值端丢：

```
environment.json   设备/系统/显示/内存/存储/权限/运行时
diagnosis.json     自动结论 + 7 项检查（含 summary.headline）
timeline.jsonl     事件时间线（启动→引擎就绪→渲染→崩溃）
crashes/           崩溃现场全文
files.json         关键文件的存在性/大小/修改时间
web.log / engine.log / app.log（含 .1 代）
manifest.json      最后写 —— 它记录前面实际写进去什么
```

⚠️ **`manifest.json` 必须最后写**，否则它记录的清单与包内容不符。
⚠️ **每个条目是全有或全无**：写一半失败就整条丢弃并在 `completeness` 里记 `truncated`。
半个 zip 条目比没有更糟（解压报错，分析者以为包坏了）。
⚠️ 超预算时**留尾巴不留头**（日志越新越有价值），并把 `truncated: true` 写进 `completeness`
—— 静默截断会让分析者把「没看到」当成「没发生」。

`diagnosis.json` 的检查项全部**给证据不给结论**，例如：
`last-exit` → `warn` / `"上次未正常结束（2026-10-13 19:20:11，无堆栈）"`。

#### 上传（`LogUploader`）

⚠️ **崩溃进程做不了网络 IO**。所以崩溃路径**只落包 + 标 pending**，
真正的上传由**下次启动**的 `uploadPendingBundles()` 做。
用户如果从此不再打开 App，包就传不出去 —— 这是平台限制，不是缺陷，文档里要说明。

- 端点/令牌在 `DshApplication.LOG_UPLOAD_ENDPOINT` / `LOG_UPLOAD_TOKEN`
  （**`val` 不是 `const val`** —— 可空类型不能是 const）。服务器就绪后填这里。
- ⚠️ **未配置时上传必须显式失败**（「尚未配置日志服务器…」），绝不能静默成功 ——
  那会让用户以为日志已经发出去了，而实际什么都没发生。
- ⚠️ **成功与失败都要给 Toast**，并把服务器返回的 `id` 显示出来供用户引用。
- 手写 `multipart/form-data`（`HttpURLConnection`）—— 本项目没有网络库，
  不为一个上传接口引入依赖。
- `device-id` = 安装期生成的随机 UUID。**刻意不用 IMEI/序列号**。

#### 脱敏（`Redactor`）—— 不是可选项

包要离开设备。落盘**之前**统一脱敏，三组模式：

| 模式 | 拦什么 |
|---|---|
| `skTokenPattern` | `sk-…` 形式的 API key |
| `secretKeyPattern` | `api_key=` / `token:` 等键值对 |
| `urlSecretPattern` | `?token=…` —— **引擎启动那行 URL 就带 token** |

⚠️ 第三条是实测逼出来的：`[engine] dsh web: http://127.0.0.1:3080/?token=…` 是每次启动
必打的一行，不拦就等于每次上报都泄漏一个可用凭据。

**包内容边界**：含设备/系统/App 版本、脱敏日志、插件/文件/设置状态；
**不含**会话正文（只含元数据）、凭据、工作区文件内容。

#### `logs` 工具 —— 让模型自己查（⑫.3 的同一套做法）

当前日志工具源码位于 `plugins/android/components/dsh-android-logs`，作为 Android 聚合 bundle 的内部组件随统一产物交付，不新增独立用户顶层包。安装与配置由 `runtime/engine` 的出厂插件组件和 overlay writer 负责，app 装配日志命令桥；命令继续使用 `LogCommandBridge` 的领域文件信箱（`logs-cmd.json` / `logs-result.json`）。历史单组件 zip/目录只属于兼容迁移路径，规范入口见[插件更新](plugin-updates.md)。

| action | 做什么 |
|---|---|
| `summary` | 一眼结论（**默认**，便宜，先调它） |
| `read` | 读最近 N 行，可按 `level`/`source`/`query` 筛 |
| `crashes` | 列崩溃现场，或按 `crashFile` 读全文 |
| `export` | 生成诊断包并返回路径 |

⚠️ **必须用独立的信箱文件**（不复用 bubble 那一对）：回执按自增 `id` 配对，而各插件
各自从 1 开始计数，同一个 `id=5` 会同时存在两条语义不同的请求。这类错配的表现是
**「AI 说日志里没有这条错误」而它看起来像真的查过了** —— 比报错危险得多。

⚠️ **超时上限 10 s**（bubble 是 4 s）：`export` 要打包几 MB 的 zip，4 s 会稳定超时，
而超时会被模型当成「导出失败」。

⚠️ **插件描述里必须写死三条边界**，否则模型会把「日志里没有」当成「没发生过」：
① 文件 1 MB 轮转 + 只留一代，很老的没了；② 重复行会被限流折叠；
③ 进程被系统直接杀掉可能**完全不留日志**。

⚠️ **`crashFile` 只接受纯文件名**（插件与原生两侧都校验）：这个值来自模型，
原生侧要拿它拼 `logs/crash/<name>`，放行 `../` 就等于给模型一个任意文件读取口。

⚠️ 插件侧对 `read` 的结果**再截断一次**（500 行 / 256 KB）—— 那是**上下文预算**限制，
不是能力限制。不截断会把这一轮对话直接撑爆，而模型不会自己意识到该少要点。

#### ⚠️ 四个踩过的坑（0.2.8 → 0.2.8-logsfix 修掉，都是「静默失效」类）

**① 两个 flavor 各有一份手工白名单，往 `PageBridge` 加方法必须三处同改。**

`SystemPageBridge`（[SystemWebHost](../../web/host/src/system/java/com/dsh/android/web/SystemWebHost.kt)，`addJavascriptInterface`
要每个方法带 `@android.webkit.JavascriptInterface`）与 gecko 的
`dispatchInvoke` 的 `when(call)` + `content.js` 的 `BRIDGE_INSTALLER_SOURCE`
**都是白名单**。`SystemPageBridge` 的类注释写着「恰好是页面侧用的那 16 个，不多不少」——
只改 `PageBridge` 接口不改白名单，`window.DSHAndroid.logSummary` 就是 `undefined`，
设置页那一块显示成**「当前环境没有原生桥」**，不报错、不崩溃。

⚠️ gecko 侧还有第三处：`content.js` 里 `window.DSHAndroid = {...}` 那段。
`logSummary` 走**快照缓存**（native messaging 只能异步，而页面侧是同步读），
所以它同时要出现在 `pushSnapshot()` 的 JSON 里、`snapshot` 对象里、以及
`getLogSummary` 那个 `exportFunction` 里 —— 四处。

⚠️ **改 `content.js` 必须 bump `manifest.json` 的 `version`**（否则 GeckoView 用旧扩展）。

**② 手动上传成功后必须删掉 `pending/` 里那一份。**

`LogUploader.upload()` 原来只上传不删，于是下次启动 `retryPending()` 把同一个包再传一遍。
服务器上留了铁证：两份包 `sha256` 完全相同、间隔 2 分钟，`reason` 分别是
`manual` 与 `startup-retry`。手动与自动补传必须共用「成功即出队」这一个语义。

**③ 崩溃包原来**从不**生成 —— `stashForCrash()` 定义了却无人调用。**

`CrashRecorder` 只写现场 `.txt`，`pending/` 永远是空的，`retryPending()` 什么都传不出去：
**「崩溃自动上传」这条链路从来没生效过**。服务器上收到的包 `reason` 只有
`manual` / `startup-retry`、一个 `crash` 都没有，就是判据。

正确形状是**分两段**：崩溃当场只写现场文件（快、可靠，崩溃进程只有几秒预算），
**下次启动**由 `LogUploader.collectCrashScenes()` 补打包 + 入队。幂等判据是
`crash/.queued` 里记的「已打包现场文件名集合」。
⚠️ 标记文件名**不能以 `.txt` 结尾** —— 现场文件就是按 `.txt` 认的，否则标记会把自己
算进集合，签名永远在变、每次启动重复打包。

**④ 「导出诊断包」原来落到 app 私有目录，用户根本拿不到。**

`filesDir/logs/export/` 是私有目录，文件管理器进不去 `/data/data/<包名>/`，
于是「导出」对用户等于**「提示保存成功，但那个文件我永远拿不到」**，
也就没法通过聊天软件发给开发者 —— 而这个功能存在的全部意义就是让用户把包发出来。

做法：打包仍写在 `logs/export/`（上传链路的输入，**不能移走**），
额外**复制**一份到 `LogContext.publicExportDirOrNull()`：有存储权限 → `/sdcard/Download/`；
无权限 → `getExternalFilesDir(DOWNLOADS)`（不需权限，文件管理器仍可达）；
都拿不到才退回私有目录。`logs` 工具的 `export` 同样返回 `sharedPath`。

#### 判定「崩溃自动上传」到底通没通

```powershell
# 服务器侧：看有没有 reason=crash 的包
ssh ... 'cat /workspace/dsh-logs/index.jsonl'
```

只有 `manual` / `startup-retry` ⇒ 崩溃那条链路没生效（见上面 ③）。
⚠️ 判断「重复上传」看 `sha256` 是否相同（见 ②），不要只看条数。

#### ⚠️ 三处「归因写错了」的修正（前两处不是本 App 的错，第三处是本 App 自己的）

诊断包的 `environment.json` / `crashes/*.txt` 是排查的**唯一入口**。下面两条会让
入口本身指向错误的方向 —— 不是功能坏了，是**归因错了**，代价是排查时间全花在
自己的代码上。

**① `device.kernel` 曾经大面积为空 —— 是 SELinux 不给读，不是设备老。**

现象：62 个诊断包里 **41 个** `device.kernel` 是空串。第一直觉是「老设备没有这个
文件」，但按 SDK 分组后**恰好相反**：SDK 35 的设备 20/20 全空，SDK 28/31 的反倒有值。

真机实测（CDY-TN90，HarmonyOS，Android 10 / SDK 29，`getenforce` = `Enforcing`）。
最硬的证据是**内核自己的 avc 记录**，它直接落在 `untrusted_app` 域上：

```
avc: denied { read } for pid=25826 comm="sofire_pool_thr" name="version" dev="proc"
  ino=4026532165 scontext=u:r:untrusted_app:s0:c84,c256,c512,c768
  tcontext=u:object_r:proc_version:s0 tclass=file permissive=0
```

辅助对照（⚠️ `run-as` 跑的是 `u:r:runas_app` 域，**不是** `untrusted_app`，
所以下面这组只能当旁证，**不能**当「同一域被拒」的证明 —— 上面那条 avc 才是）：

```
run-as com.dsh.android cat /proc/version     → cat: /proc/version: Permission denied
adb shell cat /proc/version                  → Linux version 4.14.116 (HarmonyOS@localhost) …
run-as com.dsh.android /system/bin/uname -a  → Linux localhost 4.14.116 #1 SMP PREEMPT … aarch64
```

Android 10+ 的 SELinux **拒绝 `untrusted_app` 读 `/proc/version`**（`/proc/sys/kernel/osrelease`、
`/proc/sys/kernel/version`、`/system/build.prop` 同样被拒），而 `/proc/cpuinfo`、
`/proc/self/status`、`/proc/self/maps` 可读。原实现是

```kotlin
runCatching { File("/proc/version").readText() }.getOrDefault("")
```

—— `runCatching` 把 EACCES **静默吞成空串**，于是「权限被拒」在包里长得跟
「设备没这个信息」一模一样。

**做法（`EnvironmentSnapshot.kernelVersion()`）：走 `uname(2)`，不读文件、不起子进程。**

1. `android.system.Os.uname()`（API 21+）—— `release` + `version` + `machine` 拼装，
   与 `uname -a` 等价且是**结构化**的（不用解析字符串）；
2. `System.getProperty("os.version")` —— 同一条 `uname(2)`：AOSP libcore 的
   `System.initUnchangeableSystemProperties()` 里就是
   `p.put("os.version", Libcore.os.uname().release)`，进程启动时填好，零成本；
3. `/proc/version` 降为**兜底**（Android 10+ 多为 `Permission denied`，所以排最后）。

三条都失败才返回 `""`，此时 `collect()` 补写 `kernelUnavailable: "unavailable"`，
让「没读到」在包里**可区分于「本来就没有」**。

⚠️ **不要再回去用 `ProcessBuilder("/system/bin/uname", "-a")`。** 用过一版，两个真实缺陷：
① `redirectErrorStream(true)` 会把 stderr 并进结果 —— toybox 的 uname 参数出错时把错误
写 stderr，那串会被当成内核版本写进包，且因「非空」直接 `return`，不再降级；
② 先 `readText()` 再 `waitFor(3, SECONDS)` 是**死代码** —— `readText()` 要等管道关闭
（子进程已退出）才返回，执行到 `waitFor` 时它必然已 true，`destroyForcibly()` 永不执行，
「3 秒超时」形同虚设，实际是**无界阻塞** —— 而这段代码跑在崩溃补打包路径上
（`DshApplication.uploadPendingBundles()`），阻塞的代价不划算。
`uname(2)` 这两条天然不存在：无子进程、无流、无超时需求。

**真机验收判据**（`0.2.9-tooliso (18) system`，手动导出诊断包）：
`environment.json` 的 `device.kernel` = `4.14.116 #1 SMP PREEMPT Wed Jun 26 17:43:27 CST 2024 aarch64`，
且 `kernelUnavailable` **不出现**。同一台设备在修复前该字段是空串。

**② `org.chromium.base.JniAndroid$UncaughtExceptionException` 不是本 App 的崩溃。**

3 份现场（2 个包：`20261014-013411-94efb107`、`20261014-013846-35b8918e`，
OPPO PKJ110 / Android 16 / API 36 / `0.2.8-logsfix (16) system`）被判成 App 的
**硬崩溃**，`crash-scenes` 直接报 fail。看栈才发现：

```
reason: org.chromium.base.JniAndroid$UncaughtExceptionException: Native stack trace:
#00 pc 0x… /my_product/app/TrichromeLibrary64CN/TrichromeLibrary64CN.apk (offset 0x8dc000)
…（14 帧全在这个 apk 里）
   ← org.chromium.base.JniAndroid.handleException
   ← org.chromium.content.browser.input.ImeAdapterImpl.r
   ← ImeAdapterImpl.updateState
```

那是**这台 ROM 的系统 WebView 内核在 IME 状态更新时自己 native 崩了**，
抛进我们的默认未捕获处理器。`grep` 全工程 `app/src/**` 确认**零处**处理过它。

做法（`CrashRecorder`）：新增 `isWebViewKernelCrash(err, stack)` —— 要求异常类名含
`JniAndroid` **且** native 栈里出现内核库名 `TrichromeLibrary` / `libmonochrome`
（两个条件同时成立才认，避免误伤本 App 自己的异常），
命中时给 `reason` 加前缀 `【系统 Web 内核崩溃，非本 App】`。

⚠️ **不要再把 `stack.contains("JniAndroid.handleException")` 加回判据里。** 加过一版，
它是**恒真**的 —— `UncaughtExceptionException` 正是由 `JniAndroid.handleException`
构造的（chromium 源码里 `new UncaughtExceptionException(nativeStackTrace, throwable)`
就在该方法体内），必然出现在自己的栈里。恒真条件会把判据短路成「只看类名」，而
chromium 的 `CheckException(env)` 是 **jni_zero 的通用 JNI 边界检查** —— native 调用
**任何** Java 方法返回时有未决异常都会触发，不限于内核自己崩。本工程这些回调都是被
WebView native 经 JNI 调用的（`onPageFinished`、`onStatus`、`onUiReady`、`bubbleApply`…），
任一处抛未捕获异常就会被**误标成「非本 App」**，与该修复的意图正好相反。
**要加特征就往「native 栈里出现内核库名」这个方向加。**

⚠️ **只改归因文案，不改 level、不改 `isHardCrash`。** 界面确实死了（用户看到的是
App 崩），把它从硬崩溃里摘出去会让 `crash-scenes` 漏报真实后果。**归因准确 ≠ 降低严重度。**

⚠️ **`reason` 里可能带整段 native 栈**（`UncaughtExceptionException` 的 message 就是
`"Native stack trace:" + 换行 + nativeStackTrace`），所以两个写入点都要防多行：
现场文件 `writeScene` 把换行折叠成 `\n` 字面量（保住 `key: value` 逐行格式，栈全文仍在
下方的 stack 段里完整保留），`app.log` 那条只取 `reason` 首行（下一行紧接着就是
`stack`，否则同一条崩溃在 app.log 里会出现两遍、且这一行被撑成几百行）。

**③ 崩溃现场「最后 200 行」的线程名曾经是读取者，不是写入者 —— 伪造了最关键那段证据。**

前两条是别人的代码记错了归因，这一条是**本 App 自己**记错了：现场文件里
`--- last 200 log lines ---` 那一段（类注释里写明「这是**最关键的一段**」）**每一行的
线程名都是假的**。

铁证是同一时间戳、同一 `#1` 在两份文件里的对比：

```
app.log（写入时定格）  ... [W] [Boot] #1 main 上次启动未正常结束（bootId=…）
现场文件（读取时取）    ... [W] [Boot] #1 dsh-log-watchdog 上次启动未正常结束（bootId=…）
```

**根因**：`LogStore.Entry` 只有 `t/level/tag/msg/seq`，**没有线程名字段**；而
`tail()` 是拿 `Entry` **重新**调 `format()` 渲染的，`format()` 里取的是
`Thread.currentThread().name` —— 也就是**调用 `tail()` 的那个线程**（写现场的是
`dsh-log-watchdog`），而不是当初写这条日志的线程。

**为什么这条比其他两条更值得修**：现场第 3 段是全文件信息量最大的一段
（「崩之前最后做的那件事」），而线程名是判断「**谁**卡住了 / 谁在刷屏」的**第一依据**。
写成读取者，等于让分析者顺着一个从头到尾没参与过那些日志的线程去查 —— 方向和线索
一起错。而且它**不会报错、不会告警**，只是静静地显示一个看似合理的线程名。

**做法**：`Entry` 增加 `thread` 字段，在三个写入入口（`app`/`engine`/`web`）与
`writeAppThrottleNote` 里用 `Thread.currentThread().name` **定格**，`tail()` 读该字段。
顺带把 `logs` 工具（`LogCommandBridge`）与日志界面（`LogViewerActivity.formatRow`）的行
格式补上线程名 —— 它们此前都不带，模型/用户只能靠正文猜是谁写的。
`LogViewerActivity.crashEntries()` 里代表「现场文件」的合成行走 `thread = "-"`：
那不是一条日志、没有写入线程，填 `Thread.currentThread().name` 会显示成 UI 线程，同属编造。

⚠️ **判据是「两份文件里同一行必须逐字相同」**。真机验收（`0.2.9-tooliso (18) system`、
`logs` 工具 `read` 动作）：`03:49:05.258 [I] [DSH] #76 main [web] [dsh-android-mobile]
onUiReady…` 与 `app.log` 尾部逐字一致；`thread` 字段真实呈现为 `main` / `Thread-11` /
`DefaultDispatcher-worker-3` / `dsh-engine-output` / `NsdManager` / `JavaBridge`。

⚠️ **`Entry` 是 data class**，加字段会改掉 `copy()` / `componentN()` 的签名 ——
编译期会全部报出来（这正是本轮能一次改完 5 处的依据），不会静默漏改。

#### ⚠️ 第四处：**给结论的地方举例子，等于编造**（同一次排查里查出 4 个副本）

前三处是「判据/元数据」错了，这一处是**措辞**错了，而且错得更隐蔽 ——
代码逻辑完全正确，只是**在需要陈述事实的位置写了一个猜测**。

**症状**：真机 162 份崩溃现场里，非硬崩溃类共 99 份，其中 `engine-start-failed`
占 **81** 份（82%）。而三处文案举的例子是「渲染进程被回收、引擎正常退出等」，
那两个例子合计只有 18 份。**举例子举的恰恰是少数派，把最大的一类漏掉了。**

| 副本 | 位置 | 原文 | 实际情况 |
|---|---|---|---|
| ① | `DiagnosticBundle` 的 `crash-scenes` warn 分支 | 「有 N 份非崩溃现场（渲染进程被回收/引擎正常退出等）」 | 22 个 warn 包里那三个例子出现 **0** 次，全是 `engine-start-failed`×70 + `engine-exit`×16 |
| ② | `LogCommandBridge.doSummary` 的 headline `else` | 「本次运行未发现错误，**也无崩溃现场**」 | 该分支**根本没有覆盖「有现场但非硬崩溃」**，于是 `crash:1` 时也报「无崩溃现场」 |
| ③ | `LogViewerActivity.renderHeader` | 「共 N 份现场文件（非崩溃类：渲染进程被回收、引擎退出等）」 | 同 ① |
| ④ | `logs` 工具 JS 侧 `renderText` | 同一屏打出「结论: 有 2 份**非崩溃**现场」+「计数: … **崩溃现场** 2」 | `counts.crash` 数的是 `crash/` 下全部 `.txt`，两行指着同一批文件却互相矛盾 |

②的要害不是措辞而是**分支缺失**：`EngineManager.kt:5163` 自己把
`engine-start-failed` 称作「本 App 最严重的一类故障（界面直接不可用）」，
而诊断包把它归到「引擎正常退出等」里 —— 最严重的故障被归成良性的。

**做法**：`CrashRecorder.summarizeKinds(scenes)` 按类别如实汇总（降序，
同数量按名排序），三处共用同一口径；②补一级分支；④把标签改成「现场文件」。

⚠️ **为什么不能靠「改文案」蒙过去**：`engine-start-failed` 只在引擎起不来时产生，
而「引擎起不来」的排查方向与「引擎正常退出」完全不同。归类错了，分析者会去查
退出路径 —— 那条路根本不存在。

⚠️ **判据**：任何给结论/分类的地方，都必须是**从数据算出来的**，不能写例子。
判据取「该分支实际会处理哪些数据」，不是「作者以为会遇到哪些情况」。

⚠️ **改了 `mobile-ui-plugin/**` 就必须重打 zip**（见「改源码必须手工重打 `.zip`」）——
本轮 ④ 改了 `dsh-android-logs/lib/index.js`，已同步重打 `assets/logs-tool.zip`
并逐字节比对 6 个插件 zip 全一致。

**真机验收**（`0.2.9-tooliso (18) system`、投放 1 份 `engine-start-failed` 现场）：

```
投放后 headline = 有 1 份非崩溃现场（engine-start-failed×1），最近一份 …（…）
删除后 headline = 上次未正常结束（上次启动于 …，无堆栈）     ← 正确回落，不再谎报
诊断包 crash-scenes = 有 2 份非崩溃现场（engine-exit×1, engine-start-failed×1），详见 crashes/
```

### ⑫.7 ⚠️ 第五处：**文档/签名承诺了实现里不存在的保证**（同一次对抗式审查查出 3 个副本）

第四处是「给结论的地方举例子」；这一处是**给保护的地方说空话** —— 代码能跑、
不报错、单测全过，但**它承诺的性质根本不存在**。比第四处更危险：第四处会让分析者
查错方向，这一处会让分析者**以为某类故障已被防住而不再查**。

| 副本 | 位置 | 承诺 | 实际 |
|---|---|---|---|
| ① | `LogStore.enforceDirQuota(d, quota, skipDirs)` | 参数 `skipDirs` 让调用方指定豁免目录 | 函数体**从没用过它**，只靠 `it.isFile` 兜住子目录。已删参数 |
| ② | `DiagnosticBundle.writeEntry` 的 KDoc | 「单个条目超预算就**整个跳过**并在 completeness 里记一笔」 | 签名里**既没有 `budget` 也没有 `completeness`**，函数体无条件写入 |
| ③ | `LogUploader.bumpAttempts` | 失败返回 0 ⇒ 配合 `attempts >= MAX_ATTEMPTS` 触发 `failed/` | `0 >= 3` **恒假**，`MAX_ATTEMPTS` 与 `failed/` 永不生效；而 `.attempts` 写不进去的典型原因正是**磁盘满** —— 最需要它生效时恰好失效 |

还有一个同族的**静默吞异常**副本（性质相同：承诺"丢不了"，实际丢了不吭声）：

| 副本 | 位置 | 问题 |
|---|---|---|
| ④ | `DiagnosticBundle.writeCrashes` | 裸 `runCatching {}` 无 `onFailure`；只记成功数不记总数 ⇒「本来只有 2 份」与「3 份里 1 份读失败」在产物上**完全一样** |
| ⑤ | `LogUploader.retryPending` | `uploading` 被占用时 `return`（而非 `continue`）⇒ 静默放弃整批，一行日志都没有，调用方看起来像"传完了" |

⚠️ **判据（写 KDoc 时就地问一句）**：这句话所说的事，**这个函数的签名与函数体里有没有对应的代码**？
没有就删掉承诺或补上实现，**不能留着**。①的教训是删参数而不是补实现（该保护另有机制）。

⚠️ **同族判据（静默失败）**：每个 `runCatching` / 每个提前 `return`，都要能回答
「失败/放弃时，**谁会知道**？」。答案是"没人"就是缺陷 —— 崩溃现场漏一份、
整批补传不作声，都是分析者最需要信号时恰好没有信号。

⚠️ **已修**（`ab9d413`，8 条对抗式发现；基线 `e0ea1fb`）。每条都独立复现过判据：
`BootState` 的 clean 分支时刻用数值断言（clean 场景修复前 1000 / 修复后 5000）；
`kindOf` 与 `substringAfterLast('-')` 对 4 个类别名逐一比对，
`engine-start-failed`→`failed`、`engine-exit`→`exit` 均不同（时间戳自己也含 `-`）。

**真机验收**（`0.2.9-tooliso (18) system`、种入 `startedAt=05:00` / `lastExit.at=05:30`
的 clean 状态）：重启后 summary 报「上次正常退出（**05:30:00**.000）」，修复前会报 05:00:00。
25 份现场导出诊断包 → `totalOnDevice=20` / `included=20` / 包内 `crashes/` 20 条，三个数一致。

