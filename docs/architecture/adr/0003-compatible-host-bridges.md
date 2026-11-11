# ADR 0003：app 装配，保持宿主桥与运行身份兼容

- 状态：已接受，行为验收单独执行
- 依据：[工程规范](../conventions.md)、[平台约束](../../../AGENTS.md)

## 背景

主聊天有 system/gecko 两种内核，插件依赖稳定桥方法、信箱与标注确认。Termux ELF 重写依赖最终应用身份，用户配置与插件市场状态也不能随结构迁移重置。

## 决定

app 装配运行组件与宿主窄接口。保留两个最终 applicationId：com.dsh.android、com.dsh.webview，均 15 字符；targetSdk 维持 28。library namespace 可独立命名，Kotlin package/Manifest 类名按兼容需要保留。

Web 契约独立于具体内核，GeckoView 仅进入 gecko 变体。迁移保留桥方法、参数与返回、snapshot/invoke 分类、requestId、异步结果事件和标注匹配 ack；领域文件信箱不合并成万能总线。

用户存储路径、正式签名、profile、禁用/卸载状态和 registry 优先级不变。代码拆模块不增加用户可见 Android 顶层插件，内部保留 14 个组件原 ID。

## 其他选择

不在结构重组中同时更名协议或重建 profile，不让 runtime 通过具体 Activity/Service 反向调用宿主。

## 后果与验证

同包源码跨 Gradle 边界时须检查 internal 可见性与实际编译依赖，不能仅凭 import 名相同判断兼容。桥验证同时覆盖协议完整性与真实分派行为，两 flavor 分别编译；设备验收保留数据。接口变化另建决策与迁移方案，不借本次结构重组发布不兼容协议。
