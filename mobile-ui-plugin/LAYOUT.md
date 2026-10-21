# Android 布局切换与安全退出

本变更只包含现有 `dsh-android-mobile` 布局适配修复、独立的 **profile 配置所有者**、回归测试与说明。没有第二套布局实现，不改引擎、皮肤、原生 Kotlin、启动动画、凭据或个人 profile。

## 行为

侧栏账号区上方及「设置 → 界面布局」提供三个选项：

- **自动**：以 WebView 的 `window.innerWidth / window.innerHeight` 为窗口比例。大于 `4/3` 使用官方布局，小于 `3/4` 使用手机布局；闭区间 `[3/4, 4/3]` 保留此前实际布局。启动落在中间区域时恢复 `lastLayout`，没有历史则先用手机布局。无效/零尺寸保留此前布局。
- **手机布局**：锁定现有 Android 手机适配，不随窗口比例或鼠标变化切换。
- **官方布局**：撤销手机专属效果，交还宿主原有响应式布局；不强制宿主显示宽屏桌面形态。

新配置包未安装、加载中或不可用时，尚无已接受偏好的客户端继续原有「宽度 < 1024 且非 hover+fine 指针」策略，控件不可写。若已接受过偏好后服务暂时不可用，则保留当前策略；写失败不乐观切换。窗口变化由已有 shell resize 监听器分发，不新增 `visualViewport` 自动策略。

`layoutMode` 和 `lastLayout` 通过官方 `configForms` / settings 服务写入，不改会话或草稿、不刷新页面、不替换皮肤。自动历史写入合并到最新值并串行执行；失败立即重试最多一次，后续事件可再次尝试。

## 为什么需要两个包

`dsh-android-mobile` 是 APK 用晚于 profile 的 `--patch` 插入的适配器。profile 不能可靠地 override 一个之后才插入的配置条目，重复声明同名条目也不是解决办法。

因此 `dsh-android-layout-preferences` 仅作为正常 profile bundle 提供两个 volatile 配置字段和官方 settings 注册：**无客户端入口、无 HTTP 路由、无布局代码**。实际控件和布局仍在原适配器里，使用官方 additive `sidebar.footer.action` / `settings.section` 槽位，不替换账号或皮肤槽位。

## 手工验证安装（源码贡献，不是 APK 发布）

已验证宿主版本：DSH `0.2.1-rc.2`，Node `26.4.0`；配置包要求 Node `>=22.19.0`。本地包名 `@local/dsh-android-layout-preferences` **尚未发布到 npm**，请使用绝对本地路径，不要按 registry 包名安装。

1. 备份现有 Android 适配包及当前 profile。记录当前 APK 的资源版本戳；不要修改生成的 `mobile-ui.patch.yml`、不要清除应用数据。
2. 在本源码目录内准备配置包的生产依赖（例如 `pnpm --dir mobile-ui-plugin/dsh-android-layout-preferences install --prod --ignore-scripts`）。
3. 通过官方管理器将它加入实际 Web profile，例如：

   ```sh
   dsh plugin --profile web add /absolute/checkout/mobile-ui-plugin/dsh-android-layout-preferences --ignore-scripts
   ```

   保持源码路径稳定，并在官方插件界面确认启用。不要手工覆盖整份 profile 或仅复制一个 `node_modules` 目录。
4. 由熟悉本 App 资源发布流程的维护者部署本变更中的原 Android 适配源与 manifest；Chrome 74 目标仍须走仓库既有转译流程。该内置包不是第二个普通 profile 布局插件，**不要再在 profile 中安装一份同名适配器**。
5. 加载更新后的前端，确认按钮可写，再测试下方清单。若按钮不可用，检查配置包的真实依赖导入和启用状态，不要以重复重启替代诊断。

撤销：通过官方管理器移除 `@local/dsh-android-layout-preferences`，恢复备份的内置适配源并重新加载前端。只移除配置包时，当前页面会保留已接受策略；重新加载后回到旧策略。

## 修复点与回归

- alias bridge 按 **元素 + token + 来源** 记录归属，只删除自己注入的别名；原生 CSS 类及后来被官方样式采用的类不得被退出逻辑误删。
- 退出取消别名重试/观察器；epoch 阻止旧回调在再进入后修改新状态。
- 手机 DOM 变更按属性/样式值及 priority 记账，退出仅在值仍由本适配器持有时恢复；不重排/替换 React 卡片或原生子树。
- 监听器、定时器、设置页观察器、拥有节点与键盘适配随模式创建/销毁；保留原有密码 type 早期拦截以兼容国产安全键盘，退出时 CAS 恢复原型，不访问输入值。
- 抽屉遮罩放在侧栏的共同父层，避免根节点建立 stacking context 后遮罩压住整个抽屉；不做特定皮肤分支。

测试见 [tests/README.md](tests/README.md)。`node --test mobile-ui-plugin/tests/*.test.mjs` 可离线运行 VM 回归；安装配置包依赖才会实际验证 schema，真实引擎组合测试需显式设置 `DSH_ENGINE_ROOT`。跳过不等于通过。

设备检查清单：原皮肤与有 root stacking context 的皮肤；手机/官方反复切换；设置两级导航及返回；抽屉遮罩与点击；输入草稿不丢；手动模式缩放锁定；自动模式跨越两个阈值、中间带滞回、刷新恢复；不安装配置包时原适配可用。Honor 平板电脑模式的本地布局实现已获用户现场反馈无问题；这些反馈不等于本源码移植已在所有 APK/WebView 上重新验证。

## APK 发布边界

合并源码 **不会自动更新已安装 APK 的内置资源，也不会自动安装配置包**。本 PR 不包含 APK、zip、版本戳、兼容 bundle 或自动安装器。

若维护者要随 APK 发布：依据 [mobile-ui](../docs/agents/mobile-ui.md) 与 [asset-stamps](../docs/agents/asset-stamps.md) 重建 `mobile-ui.zip`、更新 `EngineManager.MOBILE_UI_VERSION`，并同步重建/更新 Chrome 74 webview-compat 中的 mobileui 资源和相应戳。还须决定配置包的正式分发/启用方式；否则布局修复仍生效，但新偏好控件不可写并使用上述兼容回退。升级时资源戳可能覆盖手工部署文件，请勿宣称手工替换能永久跨版本保留。
