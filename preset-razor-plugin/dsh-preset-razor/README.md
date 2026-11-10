# dsh-preset-razor

DSH 的 **agent preset（预设）** 包：装完即出现在新会话的预设选择器里，显示名
**「Fast 模式」**。

它的主题是**指哪打哪、不顺手加活**：不假设技术栈，先探明本项目自己的构建 / 测试 /
验收规矩再动手，改动最小化；用户没要求的步骤一律不做。全程中文（思考链与回复），
开工即建 todo 清单并实时更新。

---

## 为什么预设能像插件一样装

DSH 的插件 bundle 契约是：包的 `package.json` 声明
`"dsh": { "bundle": { "patch": "./cordis.patch.yml" } }`，而那个 patch 文件就是一份
**普通的 loader patch 列表**。预设也是一行 loader 声明行，所以它可以原封不动地放进
bundle 的 patch 里 —— 装插件的那套流程，同时也就把预设装上了。

```
package.json     声明 dsh.bundle.patch
cordis.patch.yml 一个 - insert: 行，挂载 @deepseek-ai/dsh-agent-preset
                 config 里是预设的 id / 显示名 / 工具组装 / 系统提示词
```

patch 里被挂载的模块名是官方的 `@deepseek-ai/dsh-agent-preset`，**不是本包的包名** ——
本包不提供任何插件代码，它携带的是一份**预设定义**。这与其他 bundle 不同，是刻意的。

---

## 安装

### 方式一：插件页安装框（推荐，装完即生效）

打开 **设置 → 插件**，在安装框里输入：

```
dsh-preset-razor
```

回车。`installBundle` 会自动完成 pnpm 安装、契约校验、写进 profile 的
`dsh.profile.bundles`、并热重载 —— 不需要手动改任何 patch 文件。

### 方式二：命令行

```powershell
dsh plugin --profile desktop add dsh-preset-razor
```

⚠️ 这条路**只把参数转发给 pnpm**，不会写 `dsh.profile.bundles`。装完包会出现在插件页
列表里但开关是关的，**必须再点一下那个开关**。

### 方式三：本地 tgz

```powershell
npm pack            # 产出 dsh-preset-razor-1.0.0.tgz
```

然后在插件页安装框里填这个 `.tgz` 的路径。

---

## 生效与使用

装完（若走命令行则还要打开开关）后：

1. **新开一个会话**（已有消息的会话不能切预设 —— 那段历史是在旧组装下产生的）。
2. 打开预设选择器，应看到 **「Fast 模式」**，且**不显示为加载失败**。
3. 选中它开始会话。该会话的系统提示词应以「你是一个由 … 模型驱动的通用编码 Agent」
   开头，第一条铁律是「指哪打哪，不顺手加活」。

**它不会自动成为默认预设** —— 本包只注册预设，不改变 `selectedDefault`。
想让新会话默认用它，在自己 profile 的 `cordis.patch.yml` 里改
`agent-preset-registry` 那行的 `selectedDefault`：

```yaml
- id: agent-preset-registry
  name: "@deepseek-ai/dsh-agent-preset-registry"
  config:
    default: standard
    selectedDefault: razor     # ← 改成 razor
```

---

## 装之前要确认的两件事

### 1. 必须是桌面版（声明行机制）

本包用的是**声明行**机制。web / CLI 版走的是另一套（扫描 `~/.dsh/.agent-presets/`
目录的 `@deepseek-ai/dsh-agent-presets`，复数），两者互不通用。

判断方法：看 `<dshHome>/profiles/desktop/cordis.patch.yml` 是否存在。
不存在就别装这个包 —— 装了在选择器里也不会出现。

### 2. 预设 id `razor` 不能被占用

本预设的 id 是 `razor`。若你的 profile 里**已经有**一行预设声明用了同一个 id，
两者会撞车，registry 会报 `Duplicate agent preset: razor` 并让其中一个挂载失败。

⚠️ **`- insert:` 行不按 id 去重**，是无条件追加。所以装本包之前，请先在自己 profile 的
`cordis.patch.yml` 里搜一遍 `preset-razor` 或 `id: razor`，有的话删掉旧行再装
（本包会提供同样的预设）。

---

## 自定义

想改提示词或工具集，**不要改本包的文件**（升级会覆盖，而且 `node_modules` 里的改动
不干净）。在自己的 profile `cordis.patch.yml` 里按 loader id 覆盖：

```yaml
- id: preset-razor
  config:
    id: razor
    name: 快准狠模式（我的版本）
    description: …
    plugins:
      # …完整重述
```

⚠️ patch 语义是**整块替换 config，不是深合并** —— 只想改提示词，也必须把
`plugins` 整份重述。因此更省事的做法是：把本包的 `cordis.patch.yml` 复制一份、
改掉 `config.id` 与 `config.name`，作为自己的预设行。

---

## 卸载

**设置 → 插件** 里找到本包，关掉开关（预设从选择器消失），或直接移除。

npm 上的包不会被自动清理，但关掉后它不参与任何组装。

---

## 包内文件

```
package.json       dsh.bundle.patch 声明
cordis.patch.yml   一行 - insert:，挂载 @deepseek-ai/dsh-agent-preset
README.md          本文件
```

预设标识符：`razor`　显示名：`Fast 模式`

---

## 依赖

**没有 npm 依赖。** 预设引用的 26 个模块全部是 DSH 自带的官方包
（`@deepseek-ai/dsh-tool-*` / `dsh-persona` / `dsh-compaction-*` 等），由宿主组装解析，
不需要额外安装任何东西。
