# 重新生成资产

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 重新生成资产

内置插件现在由 `tools/build-plugin-components.mjs` 和 Gradle 预构建重建，见 [内置插件与市场更新](plugin-updates.md)。插件源码修改后由统一产物检查确认内容，不手工打包。

其它运行时二进制的构建脚本仍不在本仓库，以下为其来源和历史存放位置：

- Termux debs / node 运行时打包：WSL `/opt/termux-debs/`、`/opt/node-runtime/`
- 引擎 staging：`%TEMP%\engine-build\`（`node_modules` 451 MB）
- 原生插件源码与构建：`%TEMP%\narb-src\`、`%TEMP%\narb-build\`

更新引擎 = 重跑 npm install + 重打包 `engine.bin`（约 2 分钟）。
更新 Node = 重下 debs + 重跑打包脚本 + **改 `NODE_VERSION`**。
更新原生插件 = 用 NDK 27 重新编译 + 换 zip（无需改版本戳）。

更新 link 转译层（改完 `jni/linkcompat.c` 后）：

```powershell
$cc = "C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
& $cc -shared -fPIC -O2 -Wall -o app\src\main\assets\linkcompat.bin app\src\main\jni\linkcompat.c -ldl
```

无需改版本戳（每次启动覆盖写）。

更新沙箱两个组件（改完 `jni/boosandbox.c` 或 `jni/boo-sandbox-run.c` 后）：

```powershell
$cc = "C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
& $cc -shared -fPIC -O2 -Wall -o app\src\main\assets\boosandbox.bin      app\src\main\jni\boosandbox.c -ldl
& $cc -O2 -Wall              -o app\src\main\assets\boo-sandbox-run.bin app\src\main\jni\boo-sandbox-run.c
```

无需改版本戳（每次启动覆盖写）。

更新 pnpm（换版本时**必须同步改** `EngineManager.PNPM_VERSION`）：

```powershell
# 取 npm 包 pnpm 的 tarball（**纯 JS 主线，11.x 是最后一条**；不要用 12.x，
# 见 ⑥ 的 lock_shared 说明）。解出 package/ 后按「条目不带顶层目录」打包：
Invoke-WebRequest https://registry.npmjs.org/pnpm/-/pnpm-11.28.3.tgz -OutFile pnpm.tgz
tar -xzf pnpm.tgz
Compress-Archive -Path package\* -DestinationPath app\src\main\assets\pnpm-android-arm64.zip -Force
# 打完后用 ZipFile 确认条目名是 bin/pnpm.mjs 而不是 package/bin/pnpm.mjs
```

更新 ripgrep（无需改版本戳）：

```powershell
# 从 https://registry.npmjs.org/@vscode/ripgrep-linux-arm64 取 bin/rg，
# 连同 package.json 打成 zip。条目必须是 bin/rg 这种相对路径（不能带顶层目录）。
```

更新 node-pty 绑定（无需改版本戳）：

```powershell
# 用 NDK 27 从 node-pty 自带的 src/unix/pty.cc 编，**必须 -static-libstdc++**。
# 完整命令与理由见 ⑩。
```

验收方式：把 `.so` 和一个 14 项断言的测试程序（独占/保源/内容一致/linkat/ENOENT/目录
EPERM/无残留/大文件完整性）`adb push` 到设备，用 `run-as` 直接跑。一次性脚本，不入库。
沙箱与 runner 同理（`sbtest2.sh` 8/8、runner 10/10）。

### ⚠️ 手写源码资产要单独加 `.gitignore` 白名单

`.gitignore` 是**整目录忽略**（`app/src/main/assets/*`），因为那些二进制合计 112.7 MB
且无法从本仓库重建。但 `assets/` 里有**两个手写源码**，必须入库：

```
app/src/main/assets/*
!app/src/main/assets/webview-polyfill.js
!app/src/main/assets/dsh-android-shell.mjs
```

⚠️ **新增手写源码资产时，记得同步加一条 `!` 例外**，否则它会被静默忽略 ——
`git status` 里看不见、`git add` 也不报错，改动就这么丢了（`dsh-android-shell.mjs`
本轮就是这么被发现漏了入库的）。

⚠️ `COMMIT_MSG.tmp` 也在忽略列表里：写提交信息用的临时文件，曾经误入库过一次
（`9919b27`），用 `git rm --cached` 清掉后补进 `.gitignore`。提交信息用
`git commit -F <file>` 传，文件放 `.git/` 下或记得删。

