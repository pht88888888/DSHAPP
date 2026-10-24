# DSH 桌宠制作工作台

浏览器端工具。喂它几段**纯色背景的宠物视频**（或 GIF / 图片序列），
它抠成透明动画、按用途分组、打包成一个**能直接装进 DSH App 的 npm 插件包**。

全程本地处理，不上传任何文件。

## 一句话流程

```
拖进视频  →  给每段指定用途  →  点生成  →  导出 .tgz  →  App 里「压缩包」安装
```

## 用法

双击 `DSH桌宠工作台.html`，用 **Chrome / Edge** 打开（Firefox / Safari 不支持
VP9 alpha 录制，页面会直接提示）。

### ① 导入

把视频一次拖进去，多少个都行。文件名里带 `click` / `drag` / `walk` /
`think` / `error` 这类词会自动猜中用途，猜错了在表格里改下拉即可。

**图片序列**（`frame_001.png`、`frame_002.png`…）会自动合成一个动作，
按文件名里的数字自然排序（`2` 在 `10` 前面）。

背景处理三档：

| 模式 | 什么时候用 |
|---|---|
| 自动检测 | 默认。四角取色、判断是不是纯色背景，是就抠 |
| 保持原样 | 素材本来就是透明 webm / png |
| 指定颜色 | 自动判断不出来（比如背景有两三种近似色）时手动指定 |

抠色强度：**背景没抠干净就调大，宠物身上出洞就调小**。羽化让边缘不那么硬。

### ② 用途

每个素材变成一个**动作**。用途分两类，对应原生侧的两套机制：

| 用途 | 原生侧 | 能放几个 |
|---|---|---|
| 待机 / 点击 / 拖拽 / 走动 / 转向 | **动作池**（随机抽签） | 多个，越多越不单调 |
| 思考 / 干活 / 等待 / 出错 / 结果 / 完成 | **状态映射**（一个状态一个） | 一个 |

只放 7 个状态动作也能跑；但待机池放 3 个以上，宠物才不会一直重复同一个动作。

### ③ 生成

**所有动作共用同一个裁剪框**（取全部素材包围盒的并集）。
这是刻意的：每段各裁各的，切换动作时角色大小和位置都会跳。

输出是 **VP9 编码、带 alpha 通道的 webm**，contain + **底部对齐**
（宠物脚踩地面，所以贴底不居中）。

⚠️ MediaRecorder 是**实时录制**，24 个动作各 1 秒就是 24 秒真实时间。
录制期间别切走标签页（后台标签的 `requestAnimationFrame` 会被节流，掉帧）。

### ④ 导出

产出 `<包名>-<版本>.tgz`，结构与官方 `dsh-app-pet` 完全同构：

```
package/
├── package.json          # 含 dsh.bundle.patch 挂载声明
├── cordis.patch.yml      # bundle patch，id 与包名一致
├── lib/index.js          # 插件入口（模板注入包名）
├── presets/pet.json      # 动作、动作池、状态映射、外观
├── README.md
├── LICENSE
└── assets/webm/*.webm    # 透明动画
```

装法：App → 插件 → 添加插件 → **「压缩包」** → 填 tgz 的绝对路径 → 重启引擎。

## 硬限制（超了会静默失败，所以界面会拦）

| 限制 | 值 | 来源 |
|---|---|---|
| 单个 webm | 6 MB | `BubbleVideos.MAX_VIDEO_BYTES` |
| 动作总数 | 24 | `BubbleFrames.MAX_ACTIONS` |
| 动作名 | `a-z0-9_-`，≤24 字符 | `BubbleFrames.normalizeAction` |

原生侧超限是**静默丢弃**（不报错），所以工作台在导出前就挡住。

## 为什么导出的包不声明 `engines.dsh`

插件管理器的兼容性门禁只看 `engines.dsh` 和 `@deepseek-ai/dsh*` 的
peerDependencies。这两个一旦写上、版本又对不上，会被直接判
`incompatible-version` 拒绝安装。

本包只通过文件信箱（`$DSH_HOME/bubble-cmd.json`）与原生通信，各版本都兼容，
所以**刻意不写**这两个字段。

## 文件

| 文件 | 作用 |
|---|---|
| `DSH桌宠工作台.html` | **单文件版**（分发用），engine + 模板全内联 |
| `index.html` | 开发版，引用外部 `engine.js` |
| `engine.js` | 素材处理引擎：导入 / 抠色 / 并集裁剪 / 导出 webm |
| `build.py` | 合成单文件版 |
| `selftest.mjs` | 纯逻辑自测（51 项） |
| `e2e.mjs` | 端到端校验：真打一个包 → 解包 → 真跑插件入口（20 项） |
| `tpl/index.js.tpl` | 插件入口模板（`__PKG_NAME__` 占位） |
| `tpl/patch.yml.tpl` | `cordis.patch.yml` 模板 |
| `tpl/readme.md.tpl` | 生成包的 README 模板 |
| `tpl/license.tpl` | MIT LICENSE 模板 |

改完 `index.html` / `engine.js` / `tpl/*` 后重新合成并验：

```bash
python3 build.py
node selftest.mjs    # 纯逻辑
node e2e.mjs         # 真打包 + 真跑插件入口
```

`build.py` 会检查模板里有没有 `</script`（有的话会提前闭合标签，产出坏文件），
以及有没有漏掉的占位。

### 为什么要有两层测试

`selftest.mjs` 只验纯函数（分组、命名、池映射、硬限制、抠色、tar 头）。
但有一类错它抓不到：**tar 结构完全正确、安装也"成功"，只是宠物不出现**
（比如 `actions` 里写了动作名、`assets/webm/` 却没有同名文件）。
`e2e.mjs` 就是补这个洞 —— 它用工作台真实的 `Tar` 实现和真实模板打一个包，
用系统 `tar` 解开，再 `import` 解出来的 `lib/index.js`，断言配置真的下发了。

## 自动裁剪为什么用并集

每帧各自裁 → 尺寸不一致 → 切换动作时角色跳。
取所有帧的**并集**包围盒 → 所有帧同一尺寸 → 平滑切换。

## 已知限制

- `MediaRecorder` 实时录制，动作越多耗时越长
- 后台标签页会被浏览器节流，录制期间别切走
- `ImageDecoder` 在部分环境不可用，GIF 会退回只取第一帧并提示
- 只支持 Chrome / Edge（Firefox、Safari 不支持 VP9 alpha 录制）
