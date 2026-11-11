# 安全策略

[English below ↓](#security-policy-english)

## 报告漏洞

**不要通过公开 Issue 报告安全漏洞。**

请通过以下任一方式私下报告：

1. **GitHub Security Advisories**（推荐）——
   仓库 → Security → Advisories → [Report a vulnerability](https://github.com/pht88888888/DSHAPP/security/advisories/new)
2. 或通过 GitHub 个人主页联系仓库维护者。

请尽量包含：

- 受影响的版本（`versionName` + `versionCode`，见「设置 → 关于」或 APK 文件名）
- 设备型号与 Android 版本
- 复现步骤或 PoC
- 影响评估（能读到什么 / 能执行什么 / 是否需要用户交互）

我们会在 **7 天内**确认收到，并在修复后于 Release Notes 中致谢（除非你要求匿名）。

## 支持范围

本项目**只能侧载**（targetSdk 28），不通过 Google Play 分发。安全修复只针对最新
Release 版本；更早的版本请先升级再报告。

## 本项目的安全模型

理解这些设计前提，有助于判断某个问题是否属于安全漏洞。

### 沙箱（`libboosandbox.so` + `boo-sandbox-run`）

**设计意图**：Android 没有 bwrap / Landlock，但 `bash` 工具要求写边界。

该适配层通过运行器和写操作拦截器限制文件写入，不等同于内核级隔离，也不承诺覆盖全部绕过方式。
现行实现与已知限制见[沙箱说明](../docs/agents/sandbox.md)，验证入口见[测试手册](../docs/runbooks/testing.md)。

**明确不保证**：

- **不隔离 PID 命名空间** —— `--unshare-pid` 需要 CAP_SYS_ADMIN，普通应用拿不到。
- **不做只读挂载** —— `--ro-bind / /` 靠拦截器拦写，不是内核级只读。

这是平台限制下的取舍，**不是漏洞**。但如果你发现能**绕过写边界**的方法
（例如通过某种间接路径写到白名单外），那是真正的安全问题，请报告。

### 特权通道（uid 2000）

`shell` 工具可通过 adb 或 Shizuku 以 uid 2000 执行命令。这是**用户显式授权**的
能力，不是提权漏洞。App 不会自行获取该权限，也不会在用户不知情时使用。

### 不在范围内

以下**不属于**安全漏洞：

- 需要 root / adb 权限才能实施的攻击
- 沙箱不隔离 PID 命名空间（平台限制）
- `targetSdk = 28` 本身（硬约束，见 [AGENTS.md](../AGENTS.md)）
- 用户自行安装的第三方插件造成的任何后果

---

# Security Policy (English)

## Reporting a vulnerability

**Do not report security vulnerabilities through public issues.**

Report privately via either:

1. **GitHub Security Advisories** (preferred) —
   Security → Advisories → [Report a vulnerability](https://github.com/pht88888888/DSHAPP/security/advisories/new)
2. Or contact the maintainer through their GitHub profile.

Please include:

- Affected version (`versionName` + `versionCode`, visible in the APK filename)
- Device model and Android version
- Reproduction steps or a PoC
- Impact assessment (what can be read / executed / does it need user interaction)

We will acknowledge within **7 days** and credit you in the release notes after the fix
(unless you ask to remain anonymous).

## Supported versions

This project is **sideload-only** (targetSdk 28) and is not distributed via Google Play.
Security fixes target the latest release only; please upgrade before reporting.

## Security model

Understanding these design premises helps determine whether something is a vulnerability.

### Sandbox (`libboosandbox.so` + `boo-sandbox-run`)

**Intent**: Android has no bwrap / Landlock, but the `bash` tool requires a write boundary.

The runner and write interceptor restrict file writes. This is not kernel-level
isolation and does not guarantee coverage of every bypass. See the [sandbox guide](../docs/agents/sandbox.md)
and [testing runbook](../docs/runbooks/testing.md) for the current implementation and checks.

**Explicitly not guaranteed**:

- **No PID namespace isolation** — `--unshare-pid` requires CAP_SYS_ADMIN, unavailable
  to ordinary apps.
- **No read-only mounts** — `--ro-bind / /` relies on the interceptor, not kernel-level
  read-only.

These are platform-limited tradeoffs, **not vulnerabilities**. But if you find a way to
**bypass the write boundary** (e.g. writing outside the allowlist via some indirect
path), that is a genuine security issue — please report it.

### Privileged channel (uid 2000)

The `shell` tool can execute commands as uid 2000 via adb or Shizuku. This is a
capability the **user explicitly grants**, not a privilege-escalation bug. The app never
acquires it on its own and never uses it without the user's knowledge.

### Out of scope

The following are **not** security vulnerabilities:

- Attacks requiring root or adb access
- The sandbox not isolating the PID namespace (platform limitation)
- `targetSdk = 28` itself (a hard constraint, see [AGENTS.md](../AGENTS.md))
- Any consequence of third-party plugins the user installs themselves
