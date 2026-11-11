package com.dsh.android.engine.constants

/** 按已确认的特权能力生成的身份说明正文。 */
        /** 特权身份说明写进这个文件 —— agent-instructions 会自动读它并注入系统提示词。 */
const val INSTRUCTIONS_FILE = "AGENTS.md"

const val PRIVILEGED_PROMPT_BEGIN = "<!-- dsh-android:privileged BEGIN（本段由客户端生成，勿手改）-->"
const val PRIVILEGED_PROMPT_END = "<!-- dsh-android:privileged END -->"

const val ROOT_PROMPT_BEGIN = "<!-- dsh-android:root BEGIN（本段由客户端生成，勿手改）-->"
const val ROOT_PROMPT_END = "<!-- dsh-android:root END -->"

        /**
         * root 可用时写给模型的说明。
         *
         * 与 uid 2000 那段同样，重点在**边界**：什么时候用、什么时候别用、
         * 失败长什么样。root 的边界与 shell 不同 —— 它能读到 app 私有目录，
         * 所以「读工作区」不再是禁区；但**沙箱与 link 转译依然不生效**。
         */
val ROOT_PROMPT_ENABLED = """
            ## root 身份（uid 0）

            本机**已确认可以提权**，因此多了一个 `root` 工具：

            | 工具 | 身份 | 能力边界 |
            |---|---|---|
            | `bash`（默认） | 应用身份 | 工作区、沙箱、link 转译全部正常 |
            | `shell` | uid 2000 | 系统级操作；读不到应用私有目录 |
            | `root` | **uid 0** | 内核/分区/SELinux 等最高权限操作 |

            ### 什么时候该用 `root`

            只有 `shell`（uid 2000）也做不到时才用：

            - 读写 `/data` 下非本应用的数据（`/data/local/tmp`、`/data/misc`、别的应用私有目录）
            - `mount` / 重新挂载分区、改 SELinux 策略或标签（`setenforce`、`chcon`）
            - `iptables`、`/proc/sys` 下的内核参数
            - 读改属主是 `root` 且不 world-readable 的文件

            截图、点击、装卸载应用、`settings`、`appops`、`dumpsys` 这类
            **`shell` 就够了，优先用 `shell`** —— 权限越小越安全。

            ### ⚠️ `root` 通道的两个专属退出码

            | 退出码 | 含义 |
            |---|---|
            | **77** | 没拿到 root（没有 `su` / 被拒 / 授权框超时）—— **命令根本没跑** |
            | **78** | `su` 跑了但身份不是 uid 0 —— 命令**没有真正以 root 执行** |

            看到这两个码：**不要重试、不要假设操作成功**，如实告诉用户需要重新授权
            root 权限。它们与命令自己的非零退出码是两回事。

            ### ⚠️ 用了 `root` 会失去什么

            与 `shell` 一样：**没有写沙箱、没有 link 转译**（`LD_PRELOAD` 传不进去）。
            这条通道上的任何写操作都**不会被拦**，误删就是真删。执行前把命令的作用
            写进 `description`，破坏性的操作先跟用户确认。
        """.trimIndent()

        /**
         * root 不可用时写给模型的说明。
         *
         * ⚠️ 这里**不给引导步骤**（与 shell 那段不同）：root 只能由用户自己在设备上
         * 装（Magisk / KernelSU）并授权，App 侧没有任何办法推进。所以只需明确
         * 「没有这个能力」+「不要瞎试」，否则模型会去反复跑 `su` 然后被 77 号码困住。
         */
val ROOT_PROMPT_DISABLED = """
            ## root 身份（uid 0）—— 当前不可用

            本机**没有**可用的 root：探测未发现 `su`，或存在 `su` 但拿不到 uid 0
            （未授权 / 被拒 / 授权框超时）。**`root` 工具因此没有被注册**，
            你只有 `bash` 与（若可用）`shell`。

            在此之前：

            - **不要**尝试用任何方式绕过：`su`、`sudo`、`magisk`、`busybox` 之类的
              调用都不会成功，只会浪费一轮。
            - 读写 `/data` 下别的应用的数据、`mount`、改 SELinux、`iptables` 这类
              操作**当前一律做不到**，如实告诉用户即可，不要反复重试。
            - 需要系统级操作时用 `shell`（uid 2000）—— 它能覆盖截图、输入注入、
              包管理、`settings`、`appops`、`dumpsys` 等绝大多数场景。
        """.trimIndent()

val PRIVILEGED_PROMPT_ENABLED = """
            ## 特权身份（uid 2000 / shell）

            这台设备上你有**两个工具**可跑命令，默认的那个是受限的：

            | 工具 | 身份 | 能力 |
            |---|---|---|
            | `bash`（默认） | 应用身份 | 读写工作区、跑脚本、文件操作；有写沙箱与 link 转译保护 |
            | `shell` | shell 身份（uid 2000） | 系统级操作；**无写沙箱、无 link 转译** |

            ### 什么时候该用 `shell`

            需要**操作设备本身**时。这些在应用身份下**做不到**，而且失败方式很隐蔽：

            | 操作 | 应用身份的真实表现 | `shell` |
            |---|---|---|
            | 截图 `screencap` | rc=1，**stderr 完全为空**，只留下 0 字节文件 | rc=0，真图 |
            | 点击/滑动 `input` | **rc=0 但屏幕纹丝不动** | rc=0，真的生效 |
            | 卸载应用 `pm uninstall` | `SecurityException: INTERACT_ACROSS_USERS_FULL` | 走到包管理器 |
            | 读写系统设置 `settings` | `SecurityException: INTERACT_ACROSS_USERS` | 正常 |
            | 强制停止 `am force-stop` | `SecurityException: FORCE_STOP_PACKAGES` | 正常 |
            | 查看电池/窗口 `dumpsys` `wm` | rc=**0**，输出 `Can't find service` | 完整状态 |
            | 读输入设备 `getevent` | `/dev/input Permission denied` | 能枚举设备 |
            | 列出全部进程 `ps -A` | rc=0，**只看得见自己 uid 的 6 个进程** | 674 行 |

            ⚠️ **注意退出码骗人**：`input` 在应用身份下返回 0 却什么都没做；
            `dumpsys`/`ps` 返回 0 但结果是残缺的。所以**不要**用「退出码是不是 0」
            来判断命令有没有真正生效——上面的操作如果结果可疑，直接改用 `shell` 重试。

            ### 怎么用

            换工具名即可，**命令原文一字不改**：

            ```
            shell(command="screencap -p /sdcard/s.png",
                  description="截图到共享目录")
            ```

            ### ⚠️ 用了 `shell` 会失去什么

            shell 身份**读不到应用私有目录**（`/data/user/0/com.dsh.android` 是
            `drwx------`），因此：

            - **工作区、`${'$'}PREFIX`、`${'$'}DSH_HOME` 全部不可访问**。不要用 `shell` 跑
              `ls`、`cat`、相对路径这类依赖工作目录的命令——它们会失败或看到空目录。
              （`shell` 的 `workdir` 参数只对 `/sdcard` 这类共享路径有意义。）
            - **写沙箱不生效**：`touch /sdcard/x` 这类命令会真的写下去，不再被拦。
            - **link 转译不生效**：硬链接语义回到系统默认（应用私有目录里会 EACCES）。

            也就是说：**`shell` 换来的是特权，失去的是隔离**。只在该用的时候用。
            纯粹的读文件、写文件、跑脚本、装依赖，一律留在默认的 `bash`。

            ⚠️ 若 `shell` 的输出里出现 `${'$'}[dsh-adb] uid 2000 不可用`，说明特权通道
            此刻不可用，那条命令实际是以应用身份跑的——不要据此认为特权生效了，
            如实告诉用户需要重新引导。
        """.trimIndent()

val PRIVILEGED_PROMPT_DISABLED = """
            ## 特权身份（uid 2000 / shell）—— 当前不可用

            本机的 shell 身份通道**尚未启用**。**`shell` 工具因此没有被注册**，
            你只有 `bash`。

            现在有**三条**可用的开启方式，按对用户的代价从低到高：

            1. **Shizuku（最省事，推荐先说这条）** —— 一次启动就常驻，不需要数据线、
               不需要电脑、重启手机也依然有效。引导用户：装好 Shizuku → 在 Shizuku 里
               启动服务（它自己会提示怎么做，通常要先用电脑跑一次 adb，或直接用 root）
               → 回到本 App 的「设置 → 特权权限」点「授权」→ 点「重新检测」。
            2. **无线调试（仅 Android 11 及以上）** —— 本 App 能自己完成配对，不用电脑。
               在本 App 的设置页点「配对无线调试」，按通知栏提示走。
            3. **电脑引导（所有版本都能用，但每次重启手机后要重做一次）** ——
               用 USB 连上电脑后执行：

               ```
               adb tcpip 5555
               adb push %USERPROFILE%\.android\adbkey /sdcard/dsh_adbkey
               ```

               然后回到本 App 点「重新检测」。

            ⚠️ **先说第 1 条**：第 3 条在 Android 10 及以下每次重启手机都要重做一遍
            （`persist.adb.tcp.port` 不可写），这是用户抱怨最多的一点。

            在通道启用之前：

            - **不要**尝试用任何方式绕过应用身份去执行特权命令，那只会回落成应用身份。
            - 截图、点击屏幕、卸载应用、改系统设置、读 `/dev/input` 这类操作
              **当前一律做不到**，如实告诉用户并说明上面三条路，不要反复重试。
        """.trimIndent()

