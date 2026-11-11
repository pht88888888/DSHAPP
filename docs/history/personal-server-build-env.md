# DSH Android 服务器构建环境

> **历史档案，不作现行要求。** 特定个人服务器的历史部署记录，目录、网络、凭据存放位置及一键发布说明不作为项目现行要求。当前构建见[构建与发行](../agents/build-and-ship.md)，发布以[分步发布手册](../runbooks/release.md)为准。

八壹云香港 C 型 · Ubuntu 24.04.1 LTS · 8 核 / 7.8G / 系统盘 40G + 数据盘 30G

## 目录

所有东西都在数据盘 `/mnt/data/DSHAPP/` 下，系统盘只放 SDK：

| 路径 | 内容 |
|---|---|
| `/mnt/data/DSHAPP/source` | 仓库源码（git 已接远端，1325 个追踪文件） |
| `/mnt/data/DSHAPP/source/app/src/main/assets/` | 135 MB 构建资产（gitignored，需自备） |
| `/mnt/data/DSHAPP/gradle-home` | `GRADLE_USER_HOME`（Gradle 8.13 + 依赖缓存，约 2.0 G） |
| `/mnt/data/DSHAPP/quark-home` | 夸克 skill 的环境哨兵目录（可为空） |
| `/mnt/data/DSHAPP/release-tmp` | 发布脚本的临时产物与日志 |
| `/opt/android-sdk` | Android SDK（在系统盘） |
| `/opt/quarkclouddrive` | 夸克网盘 skill（含授权凭据，在系统盘） |

## 环境变量

在 `/etc/profile.d/dsh-build.sh`，登录 shell 自动加载：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_USER_HOME=/mnt/data/DSHAPP/gradle-home
export DSH_HOME=/mnt/data/DSHAPP/quark-home
```

## 三条踩过的坑（改动前必读）

### 一、`GRADLE_USER_HOME` 一旦设置，`~/.gradle/` 就被完全忽略

Gradle 只读 `$GRADLE_USER_HOME/gradle.properties`。所以：

- 想改 JVM 参数 / 并行 / 缓存，改 `/mnt/data/DSHAPP/gradle-home/gradle.properties`
- 不要往 `~/.gradle/gradle.properties` 写东西，写了也不生效，只会误导

验证是否生效（抓 daemon 真实参数，别看配置文件）：

```bash
ps -eo pid,args | grep '[o]rg.gradle.launcher.daemon.bootstrap.GradleDaemon' | grep -o '\-Xmx[0-9]*m'
```

### 二、`DSH_HOME` 只是个「环境哨兵」，但路径必须存在

夸克 skill 的判定逻辑是 `DSH_HOME !== void 0`，目录内容无所谓，
**但变量指向的路径必须真实存在**，否则报：

```json
{"code":-104,"msg":"无法识别当前 Agent 环境，禁止继续使用"}
```

### 三、移动目录后必须同步改 profile

`/etc/profile.d/dsh-build.sh` 里有两处绝对路径。**挪目录前先改这里**，
否则会出现「Gradle 在旧路径新建一份缓存」「夸克 CLI 突然 -104」这类
看起来毫不相关的故障。曾因此多下载了 1.1 G 依赖。

## 构建

```bash
source /etc/profile.d/dsh-build.sh
cd /mnt/data/DSHAPP/source

./gradlew :app:assembleSystemDebug --console=plain
./gradlew :app:assembleSystemRelease :app:assembleGeckoRelease --console=plain
```

产物：

- `app/build/outputs/apk/system/debug/app-system-debug.apk`（约 148 MB）
- `app/build/outputs/apk/system/release/app-system-release.apk`
- `app/build/outputs/apk/gecko/release/app-gecko-release.apk`（约 219 MB）

首次构建约 5 分钟，有缓存后增量 20 秒到 3 分钟。

## 一键发布到夸克网盘

```bash
/mnt/data/DSHAPP/source/tools/release-server.sh          # 用 build.gradle.kts 的 versionName
/mnt/data/DSHAPP/source/tools/release-server.sh 0.3.4    # 显式指定（只影响文件名）
```

脚本会：构建两个 release → 自检（versionName + mobile-ui 里有没有 `kbRowSwitch`）
→ 改名 `DSH<ver>.apk` / `DSH<ver>-webview.apk` → 上传 → 把旧包移进 `历史版本/`。

项目根由脚本用 `BASH_SOURCE` 反推，目录放哪都能跑，**不需要改脚本里的路径**。

## 与 Windows 本机的差异

- **不需要代理**。代理配置在 Windows 的 `%USERPROFILE%\.gradle\gradle.properties`，
  服务器直连 Google/Maven 全通。项目里的 `gradle.properties` 刻意不含代理。
- 两个 release 的签名凭据 `keystore.properties` / `dsh-release.jks` 已在源码里。

## 内存

7.8 G，构建时 Gradle daemon 约 2.4 G，**没有 swap**。
跑模拟器会和构建抢内存（合计约 7 G），需要错峰：构建前 `./gradlew --stop`。
