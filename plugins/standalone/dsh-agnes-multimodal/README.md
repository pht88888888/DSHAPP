# dsh-agnes-multimodal

Agnes AI 多模态融合插件（DSH 标准插件包）。一个包同时提供三件事：

| 组成 | 位置 | 说明 |
|---|---|---|
| **多模态配置** | 设置 → 多模态配置 | Agnes AI 账号池（地区 + API 密钥），持久化到 `$DSH_HOME/ag-multimodal.json` |
| **`generate_image`** | 模型工具 | Agnes 图片生成 / 图片编辑（`agnes-image-2.1-flash`） |
| **`generate_video`** | 模型工具 | Agnes 视频生成（`agnes-video-2.5-flash`），text / keyframe / reference 三模式 |

融合自原先的三个独立包 `dsh-agconfig` + `dsh-agimage` + `dsh-agvideo`。

## 安装

在 **设置 → 插件** 的安装框里输入包名（`installBundle` 会自动启用），或命令行：

```powershell
dsh plugin --profile desktop add dsh-agnes-multimodal
# CLI 路径不写 dsh.profile.bundles，装完需回插件页手动打开开关
```

> ⚠️ 若此前手工挂载过 `dsh-agconfig` / `dsh-agimage` / `dsh-agvideo`，
> **必须先删掉那三行**再装本包。`- insert:` 行不按 id 去重，重复挂载会让插件注册两次。

## 配置

打开 **设置 → 多模态配置**（设置侧栏独立一页，不在「插件」页里面），添加账号：

- **国内站** `https://api.agnes-ai.cn/v1`
- **国际站** `https://apihub.agnes-ai.cn/v1`

每个账号是一个独立的限流槽位：**N 个号 = 视频 N 次/分钟、图片并发 10×N**。
国内站与国际站各自计费与限流。每行都有「测试」按钮，用 `GET /models` 零成本校验密钥。
全部留空时回退到插件内置的默认号。

配置文件沿用旧 `dsh-agconfig` 的同一路径，**已有的账号池无需重新填写**；
旧的单密钥格式（`agnesApiKey`）也仍被识别。

## 与旧三包的行为差异

- 视频传输层从 **PowerShell 子进程重写为 Node 原生 fetch** —— 不再依赖 pwsh，
  也没有 shell 转义问题；创建 → 轮询 → 下载 MP4 全部在 Node 内完成。
- 图片与视频共用一个账号池、一个状态文件、一个日志（`$DSH_HOME/agnes-multimodal.log`）。
- HTTP 路由只保留 `/agnes/api`（配置 API）。生成的图片走 DSH 自带的附件通道，
  不再需要插件自己起一条图片字节路由。

## UI

- **图片卡**：自适应网格 + 编号角标 + 悬停浮起；单击进入全屏灯箱，支持
  ←/→ 键切换、底部缩略图条、下载、ESC 关闭。单图上限 240px 且**不放大**；
  超过 12 张折叠为预览 + 「展开全部」。
- **视频卡**：可播放的 MP4 网格，每项带编号与两行提示词摘要；单个视频上限 240px。
- **配置页**：设置侧栏里独立的一页（`settings.section`），不是「插件」页里的标签页。
  顶部状态卡实时显示可用账号数、图片并发上限、视频速率；密钥默认掩码，可逐行显示/隐藏。
- **侧栏图标**：shell 的 `navIcon(id)` 是硬编码映射，未注册的 id 一律回退成通用齿轮，
  且 slot 注册参数没有 icon 字段。本插件自行绘制 mark（`data-am-nav` + `currentColor` mask），
  跟随主题明暗，并同样出现在页面标题左侧。

## 模型上下文规则

图片工具把生成的 PNG 存进 DSH 的持久化附件库，并在 `render` 里以
`{ type: "image", attachment, offloaded: true }` 随结果返回。`offloaded` 是 DSH
自带的机制：请求投影会把它换成占位文本，所以**模型上下文仍然只有文本**，
而对话界面照常渲染图片。视频的 `render` 依旧是纯文本，播放器直接读返回的
MP4 URL。

## 图片显示路径

卡片不自己管 URL：结果内容里的 `image` 块由 shell 的 `loadImage` 换成
session 授权的地址（每会话一个缓存、随 session 释放而回收）。这与内置
`read_image` 视图走的是同一条路。

## 许可

MIT
