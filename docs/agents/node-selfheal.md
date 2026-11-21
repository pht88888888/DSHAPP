# 内置 Node 的安装校验

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## `$PREFIX/bin/node` 损坏排查与安装校验

**症状**（粉丝截图，无日志）：启动层停在

```
引擎启动失败（超时未打印URL）
# Fatal error in , line 0
# Check failed: magic_number_ == SerializedData::kMagicNumber
```

### 判据：这条报错**只可能**来自 node 自己

`magic_number_ == SerializedData::kMagicNumber` 是 V8 `Deserializer` 构造函数里的
CHECK（`deps/v8/src/snapshot/deserializer.cc`），即在**反序列化内嵌启动快照**。
那发生在**任何一行 JS 执行之前**。所以它跟插件、`engine/`、profile、`$DSH_HOME`
**全都没关系** —— 坏的是 `$PREFIX/bin/node` 这个二进制本身。
`Fatal error in , line 0`（函数名为空、行号 0）也符合「CHECK 在构造函数里」。

⚠️ **真机排除实验记录**（别重复做）：

| 实验 | 结果 |
|---|---|
| 把 node 截断到 49,000,000 B（`head -c`） | `Segmentation fault` —— **不是**这条报错 |
| `--snapshot-blob` 喂 32 字节垃圾 | `node::SnapshotData::FromBlob … (magic) == (kMagic)` @ `node_snapshotable.cc:654` —— **不同字符串、不同站点** |
| 改文件偏移 `0xf914c4` 处的 `0xc0de05c2` → `0xdeadbeef` | node 照常打印 `v26.4.0` ⇒ **那个偏移不是启动快照** |

### 为什么原来的代码修不了它

`installNodeRuntime()` 的跳过判据原本只是**存在性**：

```kotlin
if (nodeMarker.exists() && nodeBinary.isFile) return true   // ← 内容坏掉照样命中
```

旧版仅检查文件存在，导致安装校验放过坏的 Node。现行启动只走正常 APK 资产安装流程，没有独立的安全模式修复入口。

### 做法一：`nodeIsUsable()` —— 用「能不能起来」当判据

跑一次 `node --version`（约 50 ms），只有**退出码 0 且 stdout 恰好等于
`NODE_EXECUTABLE_VERSION`（`v26.4.0`）**才算可用。有两处校验：

| 位置 | 时机 |
|---|---|
| `TermuxNodeRuntime.installLocked()` 开头 | marker 命中时，坏了就删 marker + 删二进制，走重解包 |
| `TermuxNodeRuntime.installLocked()` 结尾 | 装完再验一次，**验不过就不写 marker**，下次启动重来 |

⚠️ **必须传 `LD_LIBRARY_PATH=$PREFIX/lib`**：不传的话 node 报
`CANNOT LINK EXECUTABLE … not found`，**一个健康的二进制会被误判成损坏**，
然后每次启动都白重解包一遍（实测 15 s）。
另外**两路流都要在读 `waitFor` 之前读完**，否则子进程可能卡在写满的管道上。

⚠️ **判据刻意不用 sha256 常量**：资产一换就得重算，漏改是**静默**的
（本项目已有多次「版本戳漏改 ⇒ 老装机不更新」的教训）；而且每次启动读 47 MB 太重。
`--version` 恰好覆盖「ELF 能加载 + 依赖库齐 + V8 启动快照能反序列化」这三点。

⚠️ **`NODE_EXECUTABLE_VERSION` 与 `NODE_VERSION` 必须同步改**：后者是安装标记
`.node_runtime_<版本>` 的版本，前者是资产里 node 自报的版本。改一个漏另一个，
症状相反但都是坏：漏前者 ⇒ 新 node 每次被误判成损坏、反复重解包；
漏后者 ⇒ marker 命中、新资产永不生效。

### 做法二：`$PREFIX` 上的并发写必须互斥（这是坏 node 的来源）

`TermuxBootstrap` 有**两个实例**（`DshApplication` 一个、`EngineManager` 的
`by lazy` 一个），两条路径会**同时**进来：

- `DshApplication.bootstrapTermux()`（`applicationScope`，IO 线程）
- `EngineManager.start()`（`lifecycleScope`，调度到 IO）

而两条路径都会**原地重写同一个 47 MB 的 node 二进制**。**实例级锁挡不住它们**，
所以 `prefixLock` 挂在 **companion object**（进程级），安装与路径改写入口共用：
`install()` / `installNodeRuntime()` / `ensureNodeRuntimePatched()` /
`ensurePathsRewritten()`。实现体抽成 `*Locked()` 形式，KDoc 里写明「调用方必须已持锁」。

同时把两处写盘改成**临时文件 + 改名**（全有或全无，没有半截文件窗口）：

| 函数 | 原写法 | 现写法 |
|---|---|---|
| `TermuxNodeRuntime.installLocked()` 解包循环 | 固定名 `<name>.part` + `renameTo` | `File.createTempFile(".<name>.", ".part", 同目录)` + `Files.move(REPLACE_EXISTING)` |
| `patchElfInPlace()` 写回 | `FileOutputStream(file).write(bytes)`（**原地截断重写**） | 唯一名临时文件 + `fd.sync()` + `Files.move(ATOMIC_MOVE)` |

⚠️ 固定临时名 `<name>.part` 本身就是个坑：两个写者会打开**同一个**临时文件。
⚠️ `patchElfInPlace()` 原来那个写法的窗口有几十毫秒，期间磁盘上是个**半截文件** ——
任何在这个窗口里 exec 它、或读到一半的进程，拿到的都是「大小对得上、内容已经坏掉」
的二进制，与粉丝报的症状一致。
⚠️ `fd.sync()` 不能省：`close()` 只把数据交给 OS，掉电会留下大小正确、内容全零的文件。

### 历史真机记录（2026-10-11，CDY-TN90 / Android 10，debug 包 `-r` 覆盖安装保留数据）

把 node 的 ELF magic 改成 `XXXX`（`printf "XXXX" | dd seek=0 conv=notrunc`），
**marker 保持不动**（这正是原来短路的条件），然后启动 App：

```
W Boo/TERMUX: node --version 校验失败: rc=1 out= err=…/bin/node[1]: XXXX: inaccessible or not found
W Boo/TERMUX: 内置 Node 运行时已损坏，重新解包: …/bin/node
I Boo/TERMUX: 安装内置 Node 运行时 26.4.0-2
I Boo/TERMUX: Node 运行时就绪: …/bin/node
I DSH     : Termux 引导完成（24945 ms）   ← 对照：健康路径 15265 ms，差值就是重解包
I DSH     : 引擎就绪: http://127.0.0.1:3080/?token=…
I DSH     : WebView 加载完成: http://127.0.0.1:3080/
```

治愈后逐项核对（**都是实测**）：

| 检查 | 结果 |
|---|---|
| ELF magic | `7f 45 4c 46 02 01 01 00` ✅ |
| 文件大小 | 49,715,528 B（与资产一致）✅ |
| `node --version` | `v26.4.0` ✅ |
| 内容正确性：`grep -c -a com.dsh.android node` | **22** ✅ |
| 内容正确性：`grep -c -a com.termux node` | **0** ✅ （证明 `patchElfInPlace` 的原子写回产出正确） |
| `bin/` 与整个 prefix 下的 `.part` / `.patched` 残留 | 0 个 ✅ |

⚠️ 健康路径**不打印** `node --version` 相关行（判据静默通过），所以
「日志里没有那两行 W/I」本身就是正常信号。

