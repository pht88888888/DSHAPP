# __PKG_NAME__

DSH Android 桌面宠物插件 —— 由「DSH 桌宠制作工作台」生成。

装好之后，屏幕上的悬浮球会变成一只会动的宠物：待机时随机播放小动作、
点它会回应、拖起来会悬空挣扎、DSH 干活时它也跟着进入对应状态。

## 装法

### 在手机上装（推荐）

1. 把 `__PKG_NAME__-<版本>.tgz` 放到手机上（用手机做的话它已经在
   `/sdcard/Download` 了）。
2. 打开 DSH App → **插件** → **添加插件** → 选 **「压缩包」**。
3. 填入 tgz 的**完整绝对路径**，例如
   `/sdcard/Download/__PKG_NAME__-1.0.0.tgz`。
4. 装好后**重启引擎**（或杀掉 App 重开）。

重启后悬浮球会自己变成你的宠物。

### 在电脑上装

```bash
# 直链或本地 tgz 都可以
dsh plugin add /绝对路径/__PKG_NAME__-1.0.0.tgz
```

或者解压后按本地目录装：

```bash
tar xzf __PKG_NAME__-1.0.0.tgz
dsh plugin add /绝对路径/package
```

## 里面有什么

```
package/
├── package.json          # npm 包定义 + dsh.bundle 挂载声明
├── cordis.patch.yml      # bundle patch（把本插件挂进 DSH 配置树）
├── lib/index.js          # 插件入口：读预设 → 通过文件信箱下发给悬浮球
├── presets/pet.json      # 宠物配置：动作、动作池、状态映射、外观
└── assets/webm/*.webm    # 透明动画素材（VP9，带 alpha 通道）
```

## 它是怎么生效的

`lib/index.js` 在引擎启动时被调用一次，它：

1. 读 `presets/pet.json`，把每个动作名映射到 `assets/webm/<动作名>.webm`；
2. 通过文件信箱（`$DSH_HOME/bubble-cmd.json`）把整套配置交给原生悬浮球
   （原生侧 250 ms 轮询一次）；
3. 在 `$DSH_HOME/deskpet/state.json` 里记一个**内容指纹** —— 下次启动时
   指纹没变就跳过，所以你自己用 `bubble` 工具改过外观之后，重启不会被按回去。

配置是声明式的：想换动作、改大小、调文案，改 `presets/pet.json` 再重启即可。

## 怎么改

- **换素材**：把 `assets/webm/` 下的 webm 换掉（文件名要和 `pet.json` 里
  `actions` 的值对得上）。必须是 **VP9 编码、带 alpha 通道**的 webm，
  单个不超过 **6 MB**。
- **加动作**：在 `presets/pet.json` 的 `actions` 里加一项，同时在
  `assets/webm/` 放同名文件。总共最多 **24 个动作**。
- **调大小**：`appearance.size` 是宠物在屏幕上**最长那条边**的 dp 值；
  `boxWidth` / `boxHeight` 只表达**长宽比**（长边由 `size` 决定）。
- **改状态文案**：`workStatusTexts` 是 7 档数组，按
  `idle / thinking / working / waiting / error / result / success` 的顺序，
  每档可以放多句备选（随机抽一句显示）。不想改的档留空数组即可。

改完重启引擎生效。

## 常见问题

**装好了但宠物没出现？**

- 确认**重启过引擎**（配置是启动时下发的）；
- 看日志里有没有 `__PKG_NAME__:` 开头的行 —— 没有说明插件没被加载
  （检查 `cordis.patch.yml` 的 `id` 是否与包名一致）；
- 如果之前用 `bubble` 工具手动改过外观，调一下 `deskpet` 工具的
  `apply` 动作重新套用。

**宠物是黑块 / 没有透明背景？**

素材不是带 alpha 的 VP9 webm。用「DSH 桌宠制作工作台」重新导出一遍，
它会保证输出格式正确。

**动作播不出来？**

`pet.json` 里引用的动作名在 `assets/webm/` 下找不到同名文件时，
整个预设会被拒绝应用（日志里会明确写哪几个缺）。这是刻意的：
只配一半会让宠物"有的动作能播有的不能"，而表面上看是成功的。

## 许可

MIT
