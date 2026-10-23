# 资产版本戳

> 本文是 [AGENTS.md](../../AGENTS.md) 的分册之一，
索引见 [docs/agents/README.md](README.md)。

## 资产版本戳（改了资产必须同步改代码常量）

| 改了什么 | 必须改 |
|---|---|
| `mobile-ui.zip` 内容 | `EngineManager.MOBILE_UI_VERSION`（现 `1.0.0-107`）**且**重跑 `tools/build-webview-compat.ps1` 时 bump `-CompatVersion`（现 `34`，见 ⑯） |
| `dsh-bridge/content.js` 内容 | `app/src/gecko/assets/dsh-bridge/manifest.json` 的 `version`（现 `1.0.13`），否则 GeckoView 继续用旧扩展（见 ⑦.7） |
| `node-runtime.bin` 内容 | `TermuxBootstrap.NODE_VERSION`（`26.4.0-2`） |
| `engine.bin` 内容 | `MainActivity.assetStamp`（`0.2.1-rc.2-1`） |
| `pnpm-android-arm64.zip` 内容 | `EngineManager.PNPM_VERSION`（`11.28.3`） |
| `adb-wireless-arm64.zip` | 无需改（判据是 `bin/adb` 非空 + 完整性哨兵 `.extracted`，见 ⑪.1） |
| `node-addon-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-addon-system-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `ripgrep-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `node-pty-android-arm64.zip` | 无需改（只查文件存在且非空） |
| `linkcompat.bin` | 无需改（每次启动覆盖写） |
| `keyinject.bin` | 无需改（每次启动覆盖写；换密钥池必须重新跑 `tools/build-keyinject.ps1`，见 ⑮） |
| `boosandbox.bin` / `boo-sandbox-run.bin` | 无需改（每次启动覆盖写） |
| `dsh-android-shell.mjs` | 无需改（每次启动覆盖写） |
| `shell-tool.zip` | 无需改（每次启动解包覆盖写） |
| `bubble-tool.zip` | 无需改（每次启动解包覆盖写，见 ⑫.3） |
| `pet-bridge.zip` | 无需改（每次启动解包覆盖写，见 ⑫.4） |
| `uiagent-tool.zip` | 无需改（每次启动解包覆盖写，见 ⑫.5） |
| `logs-tool.zip` | 无需改（每次启动解包覆盖写，见 ⑫.6） |
| `ui-probe.js` | 无需改（原生每次启动读一次进内存，见 ⑫.5）。⚠️ 但改了语义要同步升 `ui-probe.js` 内部的 `VERSION`（页面上的旧探针靠它判断要不要换） |
| `webview-polyfill.js` | 无需改（每次启动覆盖写） |
| `webview-compat.zip` | **必须重跑** `tools/build-webview-compat.ps1`（见 ⑯；文件名带内容 hash，换 `engine.bin` 后必然对不上） |
| `preset-skills.zip` | `EngineManager.PRESET_SKILLS_VERSION`（现 `1.0.0-1`）。判据是 `skills/.preset_skills_version` 里的字符串，**改了内容不改这个常量 ⇒ 老设备永远拿不到新 skill** |
| `preset-razor.zip` | `EngineManager.PRESET_RAZOR_VERSION`（现 `1.0.3-1`）。⚠️ 它走 profile bundle（解到 `$DSH_HOME/node_modules/dsh-preset-razor/`），判据是包目录里的 `.preset_razor_version`，**改了内容不改这个常量 ⇒ 老设备永远停在旧提示词**（踩过：0.2.1 已删掉 `{{cwd}}` 变量，旧提示词留着它会让「快准狠模式」一选中就处理失败） |
| `core-memory.zip` | `EngineManager.CORE_MEMORY_VERSION`（现 `1.0.0-2`） |

**漏改的后果是静默的**：老标记仍在 → 解包被跳过 → 新资产永不生效。
这三个都是踩过的坑。

### ⚠️ 改 `mobile-ui-plugin/**` 的源码**必须手工重打对应的 `.zip`**

`assets/*.zip` 全部被 `.gitignore` 忽略（L33），**没有任何打包脚本** ——
`logs-tool.zip` / `bubble-tool.zip` / `statusbar-tool.zip` / `shell-tool.zip` /
`uiagent-tool.zip` / `root-tool.zip` 都是**手工**打的。所以改完源码很容易
「commit 了、构建也过了、APK 里却是旧代码」。

**真踩过（`b0fbb4b`，10-14 02:44）**：那次给 6 个工具插件加了「注册失败不拖垮引擎」
的 try/catch，commit 里**一个 zip 都没有**。事后补打时又**漏了
`root-tool.zip`** —— 它停在 10-08 的版本，于是 `0.2.9-tooliso` 的 APK 里
**唯独 root 插件仍会在注册失败时把整个引擎带崩**（这正是那次修复要解决的问题）。

⚠️ **实际失效形态比「全忘」更隐蔽，这才是要警惕的地方**：查 zip 时间戳可见
b0fbb4b 之后其实**补打过 5 个**（`logs-tool` 02:41:56、`bubble-tool`/`shell-tool`
02:43:19、`statusbar-tool`/`uiagent-tool` 02:43:20，而 b0fbb4b 提交于 02:44:51），
**只漏了 `root-tool`**（它的 mtime 停在 03:18:20，是后来才补的）。也就是说当时
当事人**以为打完了** —— 六个里漏一个，从"改了几个文件"是看不出来的。
所以判据只能是**逐 zip 与源目录做字节比对**，不能靠"我记得打过"。

**判据（每次改完必须做）**：zip 内的 `lib/index.js` 必须与源目录**逐字节一致**。
一条命令验完 6 个：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pairs=@{'logs-tool'='dsh-android-logs';'bubble-tool'='dsh-android-bubble';
 'statusbar-tool'='dsh-android-statusbar';'shell-tool'='dsh-android-shell';
 'uiagent-tool'='dsh-android-uiagent';'root-tool'='dsh-android-root'}
foreach($k in $pairs.Keys){
  $z=[IO.Compression.ZipFile]::OpenRead((Resolve-Path "app\src\main\assets\$k.zip").Path)
  foreach($e in $z.Entries){
    $r=New-Object IO.StreamReader($e.Open());$a=$r.ReadToEnd();$r.Dispose()
    $b=Get-Content "mobile-ui-plugin\$($pairs[$k])\$($e.FullName -replace '/','\')" -Raw
    if($a -ne $b){ "⚠️ $k :: $($e.FullName) 不一致" }
  }
  $z.Dispose()
}
```

**打包格式**（六个 zip 同构）：条目为 `package.json` + `lib/index.js`
（`uiagent-tool` 多一个 `lib/screens.js`），**相对路径、没有顶层目录** ——
`EngineManager` 直接往 `$DSH_HOME/<xxx>-tool/dsh-android-xxx/` 解，多一层目录就找不到入口。

