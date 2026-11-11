# Bubble 局部行为测试

从仓库根运行：

```bash
node --test feature/bubble/tests/bubble.test.mjs
```

测试使用共享 `tools/kotlin-test-runtime.mjs` 校验 JDK 17 和 Kotlin 2.0.21。可以通过 `JAVA_HOME` 指定 JDK，通过 `KOTLIN_COMPILER_LIB` 指定现有编译器 jar 目录，或用 `KOTLIN_COMPILER_CLASSPATH` 显式列出编译器、stdlib 及其依赖；也支持已存在的 Gradle 发行目录。缺少输入会失败，不下载工具、不跳过测试。

测试直接编译当前 bubble 组件和 `core/contracts` 中真实快照模型，支持源码从 app 迁入 feature/bubble；不读取 app/build 中的旧 class、APK 或 Android SDK。生成类放在系统临时目录，结束后清理。

目前有 9 个 Node 用例，执行 60 个 Kotlin 行为场景：

- 转录：延后渲染、指纹去重、行复用、角色/样式/宽度失效、阅读位置、底部跟随和解除绑定。
- 播放与内容动效：帧延迟、有限循环、取消与代际保护，图像/视频 pulse、朝向、挤压与资源释放。
- 展示与运动：状态内容去重及到期、余额 decimal 比较和模板、步长夹取、IME 地面逃逸、碰撞与静止。
- 进程控制：三类快照分别覆盖，空值字段完整替换，隐藏不清快照，启用与探测策略，原子唯一点击计数，旧 Activity 解绑不影响新界面。
- 动作控制：首次绘制前配置与挂载后起计时，固定/覆盖/手动/链/状态池/映射优先级，状态抽样缓存、重复游标、转向消费、姿势到期、拖拽避让和取消后不续排。

平台替身只提供确定性的 View 队列与动画生命周期。JSON 方法会直接抛错以防误用；这些测试覆盖组件状态和清理协议，真实 Android 窗口挂载与画面由集成和设备层验证。
