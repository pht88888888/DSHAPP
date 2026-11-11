# ⑥ Termux 里没有 pnpm

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

### ⑥ Termux 的 `$PREFIX/bin` 里没有 pnpm —— 而且不能用 pnpm 12

`plugin_manager` 的 `install_bundle` 靠 spawn `pnpm add` 装插件，而 Termux bootstrap
只带 dpkg/apt 那一套，**没有 pnpm/npm/npx/corepack**。症状是 `spawn pnpm ENOENT`。

实测后果值得记一笔：AI 因此**绕过包管理器**，把插件包手工塞进
`profiles/web/node_modules/`，并**直接编辑了用户的 `profiles/web/cordis.patch.yml`**
（留下 `cordis.patch.yml.bak-light-green-ui`）。

**做法**：`EngineRuntimeDependencies.ensurePnpm()` 装 **pnpm 11.28.3 的纯 JS 版**
（npm 包 `pnpm`，解到 `$PREFIX/lib/pnpm`），再在 `$PREFIX/bin/pnpm` 写一个
`exec <node> <pkg>/bin/pnpm.mjs "$@"` 的启动脚本。

#### 为什么不是 `@pnpm/exe.android-arm64`（12.x）

那是**原生 Android 可执行文件**（`INTERP=/system/bin/linker64`，只 NEEDED
libc/libm/libdl），装上去 `pnpm --version` 也正常（→ 12.8.1），
**但它做不了任何写操作**：

```
ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK
  ╰─▶ Failed to acquire the store operation lock at
      ".../pnpm-store-operation-locks-<pid>/all-stores.lock": lock_shared() not supported
```

pnpm 12 是 Rust 重写的（pacquet），store 操作锁走 `File::lock_shared()`，
而 **Rust std 在编译期就把 Android 判为不支持**。

⚠️ **这不是内核不给锁**，别往 SELinux / flock(2) 方向查。设备实测同一路径下：

| 调用 | 结果 |
|---|---|
| `flock(fd, LOCK_SH)` | **0（成功）** |
| `flock(fd, LOCK_SH \| LOCK_NB)` | 0 |
| `flock(fd, LOCK_EX)` / `LOCK_UN` | 0 |
| `fcntl(fd, F_SETLK, F_RDLCK)` | 0 |

决定性证据是那个二进制的**动态符号表里只有 `fcntl`、根本没有 `flock`**：

```powershell
llvm-readelf --dyn-syms app\src\main\assets\pnpm-android-arm64.bin | Select-String 'flock|fcntl'
# → 0000000000000000  0 FUNC GLOBAL DEFAULT UND fcntl@LIBC   （仅此一条）
```

所以是编译期判定，**没有任何配置项能绕过去**。

⚠️ **上面这一整段是 12.8.1 的实测结论，12.10.1 已经不成立**（2026-10-10 复测，
CDY-TN90 / Android 10）。上游在这两个版本之间把锁换成了 flock 可用的实现：

| 判据 | 12.8.1（原文实测） | 12.10.1（复测） |
|---|---|---|
| 动态符号表里的 `flock` | **没有**（只有 `fcntl`） | **有**（`grep -c -a flock` 命中 1） |
| `pnpm add` 写操作 | `ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK` | **成功**，RC=0 |

复测证据（`@pnpm/exe.android-arm64@12.10.1`，位于
`$HOME/.local/share/pnpm/store/v11/links/@/pnpm/12.10.1/<hash>/node_modules/pnpm/pnpm`）：

```
$P add is-number   → RC=0，590ms，node_modules/is-number 落盘
$P add is-even     → RC=0，820ms，+5 包
pnpm add is-odd    → RC=0，793ms，+2 包（经 pnpm 11 自动切换后）
```

三次都没出现 `ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK`。store 仍写成 v11 格式。

⚠️ **这不改变本 App 的做法**：插件管理仍固定走 pnpm 11.28.3，理由是**确定性**
（11 是纯 JS，行为已在本项目逐条验过），不是因为 12 不能用。
⚠️ **也别据此就把 12 升上来** —— 上面的对比只覆盖「能不能写」，没覆盖
`nodeLinker`、`node_modules/.ignored` 驱逐行为、本地 `link:` 包这些本项目真正依赖的
语义。要升就按 ⑥ 的老办法重新逐项实测。

⚠️ **pnpm 12 会在特定目录里被自动下载并接管**，这一点值得知道：`pnpm init`（12.x 写的
package.json）会带上

```json
"devEngines": { "packageManager": { "name": "pnpm", "version": "12.10.1", "onFail": "download" } },
"packageManager": "pnpm@12.10.1"
```

之后在该目录跑**任何** pnpm 命令都会自动下载 12.10.1 并切过去（实测就是这样从
11.28.3 切走的），产物落在 `$HOME/.local/share/pnpm/store/`。用户在自己工作目录里
`pnpm init` 后就会遇到，**这是预期行为不是 bug**。
而 `$DSH_HOME/profiles/web/package.json` **没有这两个字段**（只有
`name`/`private`/`dependencies`/`dsh`，已实测确认），所以**插件安装永远走 11.28.3**。

#### 为什么 pnpm 11 可以

11.x 是最后一条**纯 JS** 主线：`dist/pnpm.mjs` 是 15.7 MB 的单文件 bundle，
锁走 JS 实现（`dist/pnpm.mjs` 里搜不到 `lock_shared`）。
真机实测（含本地路径包 + 真实 profile 的 `nodeLinker: hoisted` 布局）：

```
pnpm add <本地包>  →  exit=0，node_modules/@local/xxx 就位，
                      package.json 写入 "link:/data/..."
```

副作用：**体积从 46 MB 单文件变成 5.9 MB 的 zip**（454 个文件），APK 反而小了。

⚠️ **不要把 `bin/pnpm.mjs` 直接软链到 `bin/pnpm`**：Android 的 SELinux
不给 app 私有目录建符号链接（与 ③ 的 `link` 同一类限制），shebang 也不可靠。
写启动脚本，且 node 用**绝对路径**（不依赖 PATH）。

本节后续为旧 overlay 架构的历史说明。当前内置包统一采用标准 bundle：出厂目录位于 `$DSH_HOME/node_modules/<package>`，profile 以明确 `link:` 依赖登记；市场更新后保留 registry 依赖，App 不覆盖。启用与卸载状态只登记一次，详见 [内置插件与市场更新](plugin-updates.md)。

⚠️ **pnpm 会把 profile 的 `node_modules` 里任何"多余依赖"挪到 `node_modules/.ignored/`。**
这正是移动端 UI 插件**曾经**被驱逐的原因，现在已把它挪出 `node_modules` 规避（见
「移动端 UI 插件」一节的 ⚠️）。**不要**再把 App 注入的插件放进 profile 的
`node_modules`，也不要为此把它写进 `dependencies`。

⚠️ 这一处**必须等 Termux 环境就位之后再装**（目标是 `$PREFIX/bin`），
所以调用点在 `bootstrap.installNodeRuntime()` 之后，和其它 `ensureXxx` 不同。

#### 唯一的例外：`dsh-core-memory` 是 bundle，**故意**写进了 `dependencies`

上面那条「不要把 App 注入的插件写进 `dependencies`」有个**例外**，理由不是"这次没关系"，
而是**位置不同、目的不同**：

| | 移动端 UI 插件 / 工具插件 | `dsh-core-memory` |
|---|---|---|
| 包放哪 | `$DSH_HOME/mobile-ui/…`（**在 pnpm workspace 之外**） | `$DSH_HOME/node_modules/dsh-core-memory`（**同样在 workspace 之外**） |
| 怎么挂载 | `--patch` overlay 的 `insert` 行 | profile 的 `dsh.profile.bundles` |
| 写 `dependencies` | ❌ 不写 | ✅ 写 `link:<绝对路径>` |

关键点：`pnpm-workspace.yaml` 是 `packages: [.]`，只覆盖 `profiles/web`。所以
`$DSH_HOME/node_modules/` **不在任何 pnpm 工程里**，pnpm 看不到它 —— 上面那条
「被当多余依赖挪进 `.ignored/`」的机制对它不成立。

**为什么必须写 `dependencies`**：官方插件页的 `listed` 过滤是
`installed || official || error`，而 `installed` 来自 `manifest.dependencies`。
只写 `bundles` 不写 `dependencies`，这一行**不会渲染**（用户看不到、也就关不掉）。
`link:` 是本项目已实测过的本地包协议（见上），不会去 registry 拉。

**为什么非得是 bundle 而不能继续用 overlay**：`--patch` 的 `insert` 行**没有 bundle
身份** —— 官方 plugin-manager 的列表只认 `dsh.profile.bundles`。用户要求「不想用的
用户可以自己关掉」，而 overlay 是 App 每次启动整体覆盖的，用户改不动。

**关掉之后不能再被 App 打开**（这条最容易写错）：`dsh.profile.bundles` 是**用户与
App 共写**的同一份文件。若写成「不在就补」，用户关掉、重启 App 又回来了 = 关不掉。
所以判据是「**从来没见过它**才补」，靠 `$DSH_HOME/.core_memory_bundle_registered`
这个一次性标记。⚠️ **别把它做成随版本变化的戳**，删掉/改值都会让 App 重新「补一次」。

