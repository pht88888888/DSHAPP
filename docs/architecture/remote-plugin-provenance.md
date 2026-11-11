# 远程插件发布来源与旧副本审计

审计时间：2026-11-10T17:32:55.034Z（UTC）。生产发布内容以该次真实 npm 查询与完整 tarball 为基线；独立仓库是后续开发入口。锁内完整证据见[远程插件来源锁](../../catalogs/remote-plugins.lock.json)，不可用相同版本号推断源码相同。

## 发布与源码结论

| 项目 | 手机端 | 电脑端 |
|---|---|---|
| npm 包 | [dsh-jiuling-remote-mobile](https://registry.npmjs.org/dsh-jiuling-remote-mobile/latest) | [dsh-jiuling-remote-desktop](https://registry.npmjs.org/dsh-jiuling-remote-desktop/latest) |
| 实查 latest | 1.2.105 | 1.0.56 |
| npm gitHead | `6381afde069708c2f2bb5b7c4a9df093690718e4` | `75ed7b9ea9f369d01e7aaa9d98bf94c24c89a0a7` |
| npm tarball 文件数 | 4 | 6 |
| 独立 master 包版本 | 1.2.105 | 1.0.56 |
| 旧 App 副本版本 | 1.2.87 | 1.0.48 |
| 独立仓库中的发布提交 | GitHub HTTP 422，明确 No commit found for SHA | GitHub HTTP 422，明确 No commit found for SHA |

独立来源：[pht88888888/dsh-jiuling-remote-plugins](https://github.com/pht88888888/dsh-jiuling-remote-plugins)，默认分支 master，实查提交 `20e99754eddd55b4f0ea2a0aee5b7e507141681f`。另一个分支 dev 为 `acaad63ddd81e96ee28caa2d1df771959c747d28`，本次无标签。归档按实际 commit 下载并记录 SHA-256；工具分别请求两个 npm gitHead 的 GitHub commit API，不能用 master 替代发布提交。

本次已下载两个 npm 精确版本 tarball，校验 registry 声明的 SHA-512 integrity 和 SHA-1 shasum，解析包内文件并记录每个文件的 SHA-256、字节数。版本、tarball URL、integrity、gitHead、源码归档与差异均在锁内；未写入独立仓库，未发布 npm。

## 同版本号的实际差异

手机端 npm 包只包含 package、patch、client、host 四个文件。独立源码的三个职责模块已进入 `lib`，并由独立源码的 host 导入；当前已发布包仍是未抽出这些模块的 host。逐文件结果如下，方向为 npm → 独立仓库：

| 包 | 变化文件 | 结果 |
|---|---|---|
| mobile | `lib/client.js`、`lib/index.js`、`package.json` | 内容不同 |
| mobile | `lib/outbox.js`、`lib/reconnect.js`、`lib/session-index.js` | 独立源码新增，npm tarball 不含 |
| mobile | `cordis.patch.yml` | 逐字节相同 |
| desktop | `lib/index.js`、`package.json` | 内容不同 |
| desktop | `lib/client.js`、`lib/pair.js`、`lib/qr.js`、`cordis.patch.yml` | 逐字节相同 |

手机源码的 outbox 额外透传提交 metadata；reconnect 有按最后收包时间判断 stale 的重启能力；session-index 有模型快照及更完整的移除清理。npm 与独立仓库的手机 host 都包含 `mapWorkspacePath`，旧 App host 没有该符号；电脑发布包和独立 host 都包含 archive/DNS 相关实现，旧 App host 未包含相同符号。这些是审计定位依据，不能替代完整行为兼容性验收。

两个发布 gitHead 均未在独立仓库中找到，因此当前可以确认“发布归档完整性”和“当前候选源码差异”，尚不能建立 npm 发布提交到独立仓库源码的完整对应关系。任何未来移植必须先保留 npm 发布基线的修复，再逐项合入源码职责拆分；不能将旧 App 副本或同版本 master 整体覆盖线上包。

## 旧副本退役与保留范围

[旧手机入口](../../remote-plugin/mobile/package.json)与[旧电脑入口](../../remote-plugin/desktop/package.json)在锁中标记为 `legacy-reference-only`。它们不属于 APK 组件清单，也不是正式远程插件开发、打包或发布入口。正式工作进入上述独立仓库对应的 dsh-remote-mobile、dsh-remote-desktop，并重新查询实际 npm 发布。

旧源码没有删除。完整受控文件快照包含 mobile 13 项、desktop 5 项；相对独立仓库 mobile 有 9 个不同文件、desktop 有 4 个不同文件。锁的 `repositoryUnique` 逐项记录原文件摘要，以及旧文件中在同路径独立源码未出现的非空行数与这些行的摘要。该集合是保守保留清单，包含旧实现、注释、格式与版本元数据，不能将行数当作未移植功能数量。

手机需保留的差异为 client、host、outbox、reconnect、session-index、package-lock、package、ownership 测试和 sync 测试；其中 outbox、reconnect、session-index 已在独立源码存在并进一步演进，不能因为文件同名就删除旧证据。电脑需保留 client、host、pair 和 package 的差异。每项“same/changed/left-only/right-only”及两端 SHA-256 已完整写入锁。

配对服务的三个文件和历史集成测试的 26 个文件（共 29 项）也逐个锁定。这些内容在独立仓库根目录没有对应的 pair-server 或 _test 目录，不能随旧插件副本一并删除。[配对服务](../../remote-plugin/pair-server/pair_server.py)自身描述了隧道、DNS、激活码及本地监听职责；本次没有启动、部署、调用配对服务或修改其数据。

引用审计覆盖 Git 受控文档、JS、Kotlin/Gradle、JSON、YAML、Python 与 shell/PowerShell 文件，登记路径、行号、类型和行摘要。旧目录内的相对加载/测试引用由完整文件快照保留；外部执行引用会使默认门禁失败。README 中旧目录说明属于历史参考；官方插件更新指南必须指向独立仓库，不能继续引导在旧目录执行 npm 工作。

## 验证入口与结果含义

```bash
# 无网络：锁 schema/自摘要/差异覆盖/旧文件摘要/生产入口排除
node tools/check-remote-plugin-provenance.mjs

# 无网络：验证器契约与反例；合成 tarball 只用于解析测试
node --test tools/test-remote-plugin-provenance.mjs

# 真网络：官方 npm latest + tarball、GitHub refs/源码归档/gitHead
node tools/check-remote-plugin-provenance.mjs --live
```

[验证器](../../tools/check-remote-plugin-provenance.mjs)默认离线通过只表示本地契约与已审计锁一致，不声称刷新线上 latest。显式 `--live` 使用固定官方 npm registry 和固定独立 GitHub 来源；网络、限流、缺输入、完整性失败及发布/refs/源码差异漂移均失败，不降级到 fixture 或本地 checkout。GitHub 发布提交的明确“未找到”响应作为已知缺口锁定，输出仍显示不可达提交数与源码差异数，不能冒充可全量重建。

工具可使用现有 GH_TOKEN/GITHUB_TOKEN 或 gh 已有登录进行只读查询，凭据只发送到该固定 GitHub API，不写入日志或锁。测试没有配置真实发布的替身入口；CLI 不接受 fixture、自定义 registry、缓存或 source 参数。合成测试通过不构成新增线上发布证据。锁的自摘要是漂移检测，不是签名背书；重新建立锁必须重新采集真实来源证据并审阅差异。

尚未解决：两个 npm gitHead 的独立仓库可达性、8 项发布运行文件差异的逐行为收敛、旧副本差异的逐项功能归类、配对服务及历史集成测试的独立维护归属。以上均已明确保留，不以删目录代替完成。
