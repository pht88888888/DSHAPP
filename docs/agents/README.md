# docs/agents 索引

现行 Android 平台与开发分册；完整导航见[文档总索引](../README.md)。
根目录的 [AGENTS.md](../../AGENTS.md) 只留**必读硬约束 + 构建命令 + 本索引**；
细节全在这里，按需检索，不要整份读。

| 文件 | 主题 | 一句话 |
|---|---|---|
| [constraints.md](constraints.md) | 两条硬约束 | 包名 15 字符、targetSdk ≤ 28。改错不报错但真机必炸 |
| [build-and-ship.md](build-and-ship.md) | 构建、发行与发布 | gradle 任务、签名、夸克网盘上传与版本整理 |
| [platform-android.md](platform-android.md) | android 平台适配总览 | 为什么 Node 在 Android 上是 android 而不是 linux |
| [link-compat.md](link-compat.md) | ③ SELinux 不给 link 权限 | LD_PRELOAD 转译层：为什么必须转译、转译语义、降级路径 |
| [directory-picker.md](directory-picker.md) | ③.5 目录选择器只在**一棵**树里走 | HOME 下的入口链接接上手机存储 |
| [sandbox.md](sandbox.md) | ④ Android 没有内核级沙箱后端 | 自带 boo-sandbox-run runner 保住写边界 |
| [ripgrep.md](ripgrep.md) | ⑤ @vscode/ripgrep 没有 android 包 | 补一个静态 rg 二进制 |
| [pnpm.md](pnpm.md) | ⑥ Termux 里没有 pnpm | 装 pnpm 11 纯 JS 版；为什么不能用 12；core-memory 是例外 |
| [asset-stamps.md](asset-stamps.md) | 资产版本规则 | 插件内容摘要、兼容产物与其它运行时二进制的版本戳 |
| [startup-performance.md](startup-performance.md) | 冷启动慢 | 启动前体检缓存、跳过降级前端、prefix/bundle 记账、node 编译缓存与实测数字 |
| [ui-jank.md](ui-jank.md) | 使用中卡顿 | 面板页进入慢的采样手法、两处同步布局读、出厂包指纹漏算 |
| [plugin-updates.md](plugin-updates.md) | 内置插件与更新 | 出厂离线可用、标准 bundle、市场直接更新、状态归属与统一构建 |
| [webview-basics.md](webview-basics.md) | ⑦ 文件选择器 / ⑦.5 下载器 | WebView 默认实现静默失败，必须自己接 |
| [webview-gecko.md](webview-gecko.md) | ⑦.7 内置 Web 内核版（gecko flavor） | GeckoView API 差异、桥、注释陷阱 |
| [sharp.md](sharp.md) | ⑧ sharp 没有 android 预编译二进制 | 装 wasm32 变体 |
| [attachment-fsync.md](attachment-fsync.md) | ⑨ 附件模块会 fsync 到 / | SELinux 不给 open 祖先目录 |
| [session-repair.md](session-repair.md) | ⑨.5 / ⑨.6 会话永久损坏的两种形态 | tool_use 块顺序、第三方路由不给 tool_call id |
| [node-pty.md](node-pty.md) | ⑩ 侧栏终端打不开 | node-pty 缺 android 绑定 + 进程检查器只认三平台 |
| [shell-tool.md](shell-tool.md) | ⑪ 内建 uid 2000 执行通道 | shell 工具、adb 签名、密钥来源、18 条缺陷修复、验收 |
| [bubble.md](bubble.md) | ⑫ / ⑫.1 悬浮气泡 | 悬浮球、四项交互契约、窗口与拖动陷阱 |
| [bubble-bridge.md](bubble-bridge.md) | ⑫.2 悬浮窗接进插件生态 | 外观字段、桥方法三处同步、转录通道 |
| [bubble-tool.md](bubble-tool.md) | ⑫.3 bubble 工具 | 文件信箱，让模型自己改悬浮窗 |
| [pet-bridge.md](pet-bridge.md) | ⑫.4 桌面宠物 | 状态→动作映射、两条状态通道 |
| [ui-tool.md](ui-tool.md) | ⑫.5 ui 工具 | 让模型自己看和点界面：坐标系、遮挡、屏幕外、面板、持久化状态 |
| [browser.md](browser.md) | ⑫.8 AI 浏览器 | 模型自己的 WebView：为什么不用 iframe、官方右侧边栏入口的两个接缝、文件信箱链路、控制权仲裁、页面 JS 三条硬规矩 |
| [logging.md](logging.md) | ⑫.6 / ⑫.7 日志与诊断 | 三层存储、崩溃入口、脱敏、诊断包、归因写错的五处 |
| [plugin-source.md](plugin-source.md) | ⑬ 添加插件少了「npm 官方源」 | 不是漏配，是被去重合并掉了 |
| [web-port.md](web-port.md) | ⑭ Web 端口 3080 被占用 | 默认沿用，撞车才让内核分配 |
| [webview-compat.md](webview-compat.md) | ⑯ 旧 WebView 内核白屏 | 前端 bundle 降级编译（不是 polyfill），v2/v19 两轮教训 |
| [keep-alive.md](keep-alive.md) | 后台保活与通知栏实时状态 | 任务切后台不能断；键盘升起遮挡 |
| [mobile-ui.md](mobile-ui.md) | 移动端 UI 插件 | 为什么解到 $DSH_HOME/mobile-ui、别名桥接、+ 菜单时序、安全键盘 |
| [debug-device.md](debug-device.md) | 调试真机 | adb 基本手法 |
| [rescue-mode.md](rescue-mode.md) | 插件改坏导致 App 打不开 | 启动前体检 + 安全模式 |
| [node-selfheal.md](node-selfheal.md) | $PREFIX/bin/node 会坏 | 自校验 + 自愈、$PREFIX 并发写互斥 |
| [boundaries.md](boundaries.md) | 已知边界 | 明确不做 / 做不到的事 |
| [regen-assets.md](regen-assets.md) | 重新生成资产 | 各资产从哪来、怎么打 |
| [desktop-relation.md](desktop-relation.md) | 与 DSH 桌面端的关系 | 哪些东西两端共用、哪些各管各的 |

## 历史档案

退休能力、旧设计和特定个人环境集中在[历史档案索引](../history/README.md)，不作现行要求：

- [已退休的 TLS 注入号池](../history/retired-key-pool.md)。
- [个人服务器构建环境](../history/personal-server-build-env.md)。
- [旧救援启动设计](../history/rescue-boot-design.md)。
