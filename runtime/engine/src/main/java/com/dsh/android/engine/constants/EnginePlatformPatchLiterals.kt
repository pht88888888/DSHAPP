package com.dsh.android.engine.constants

/** Android 平台适配与生命周期补丁的原文字面量。 */
        /**
         * flock.js 里平台白名单的原样文本，以及放行 android 之后的文本。
         * 两者都必须与官方发布的 flock.js 逐字一致；引擎升级后若此处不再匹配，
         * [patchFlockPlatform] 会跳过并在日志里提示，不会让启动失败。
         */
const val FLOCK_PLATFORM_GUARD =
            "if (platform !== 'linux' && platform !== 'darwin') {"
const val FLOCK_ANDROID_ANCHOR =
            "if (platform !== 'linux' && platform !== 'darwin' && platform !== 'android') {"

        /**
         * `dsh-app-boot/lib/index.js` 里 `installFailLoud` 的两处 `proc.exit(1);`，
         * 以及换成「什么也不做」之后的文本。见 [patchFailLoudExit]。
         *
         * 实测该文件全文**只有这 2 处** `proc.exit(1);`，且都在 `installFailLoud`
         * 的 `report()` 里（一条在 `release === void 0` 分支，一条在 release 收尾）。
         * 所以整文件替换是安全的，不会波及别处。
         *
         * 替换成一句块注释而不是删成空串：`report` 里那句 stderr 已经写出了完整的
         * fatal 栈（进 `logs/engine.log`），这里不需要重复记录。
         */
const val FAIL_LOUD_EXIT_GUARD = "proc.exit(1);"
const val FAIL_LOUD_ANDROID_ANCHOR =
            "/* DSH-ANDROID-KEEP-ALIVE: 插件崩溃不再杀死整个引擎 */"

        /** 幂等标记：出现在替换**之后**的文本里，官方原文中不会有。 */
const val FAIL_LOUD_ANDROID_MARK = "DSH-ANDROID-KEEP-ALIVE"

        /**
         * `dsh/lib/profile-boot-BZ2ZjNWi.js` 传给 `installFailLoud` 的 release 回调
         * 原文，以及换成空函数之后的文本。见 [patchFailLoudExit]。
         *
         * ⚠️ **这一处必须和第 1 步一起改**。只改 `proc.exit(1)` 的话，release 仍会把
         *   整个应用 fiber 树 dispose 掉，node 没有活干自然退出 —— 表现为
         *   「退出码从 1 变成 0，但引擎照退」，实测踩过。
         *
         * ⚠️ 文件名带构建 hash（`profile-boot-BZ2ZjNWi.js`），换引擎版本会变。
         *   所以 [patchFailLoudExit] 先按固定名找，找不到再按前缀扫目录兜底。
         */
const val FAIL_LOUD_RELEASE_GUARD =
            "await app.current?.fiber.dispose();"
const val FAIL_LOUD_RELEASE_ANCHOR =
            "/* DSH-ANDROID-KEEP-ALIVE-RELEASE: 插件崩溃不再 dispose 整个应用 */"

        /** 幂等标记：第 2 步的替换结果。 */
const val FAIL_LOUD_RELEASE_MARK = "DSH-ANDROID-KEEP-ALIVE-RELEASE"

        /**
         * `dsh-terminal-bash/lib/index.js` 里 `DEFAULT_BASH_SHELL` 的原样文本，
         * 以及换成 Termux bash 之后的文本。见 [patchTerminalShell]。
         *
         * ⚠️ `/bin/bash` 在 Android 上不存在（`/bin -> /system/bin` 里只有 mksh），
         * 不改这里 `bash` 工具每条命令都会以 `PTY shell exited during startup` 失败。
         * 两者都必须与官方发布件逐字一致；引擎升级后若此处不再匹配，
         * [patchTerminalShell] 会跳过并在日志里提示，不会让启动失败。
         *
         * 替换值用 `process.env.PREFIX` 拼，不写死绝对路径：`PREFIX` 由 [start]
         * 传给引擎，这样不依赖包名或目录布局；取不到时回落 `/bin/sh`（一定存在）。
         */
const val TERMINAL_SHELL_GUARD =
            "const DEFAULT_BASH_SHELL = \"/bin/bash\";"
const val TERMINAL_SHELL_ANCHOR =
            "const DEFAULT_BASH_SHELL = process.env.PREFIX ? process.env.PREFIX + \"/bin/bash\" : \"/bin/sh\";"

        /**
         * `dsh-host-directory-picker-browse/lib/index.js` 里 `list()` 决定默认起点
         * 的那一行（[PICKER_START_GUARD]），以及曾经把它指向手机存储的文本
         * （[PICKER_START_ANCHOR]，现在只用于**撤回**）。见 [restoreDirectoryPickerStart]。
         *
         * ⚠️ 两个锚点都必须逐字保留：`GUARD` 是官方原文（撤回时写回去的内容），
         * `ANCHOR` 是旧版本打进用户设备的内容（撤回时用来识别）。少一个，
         * 老安装就永远停在 `/sdcard` 起点上。
         *
         * ⚠️ `const home = homedir();` 那一行**从头到尾没被碰过** —— 返回给客户端的
         * `home` 字段靠它，客户端拿 `home` 折叠面包屑。入口链接方案能work，正是
         * 因为起点回到了 `home`。
         *
         * ⚠️ 缩进是**两个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 撤回会跳过并在日志里提示，不会让启动失败。
         */
const val PICKER_START_GUARD =
            "\t\tconst target = resolve(path ?? home);"
const val PICKER_START_ANCHOR =
            "\t\tconst target = resolve(path ?? (process.platform === 'android' ? '/sdcard' : home));"

        /**
         * `dsh-subprocess-local` 里 `createProcessInspector()` 的平台判断原文，
         * 以及放行 android 之后的文本。见 [patchProcessInspector]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致。
         * ⚠️ 文本里的 `${platform}` 是**引擎源码**里的模板插值，不是 Kotlin 的，
         * 所以 `$` 必须转义成 `\$`，否则 Kotlin 会当成自己的变量去解析。
         */
const val INSPECTOR_GUARD =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"
const val INSPECTOR_ANDROID_ANCHOR =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\t/* Android 就是 Linux 内核，/proc 格式一致，且 SYSCALLS 表里本来就有 arm64。\n" +
                "\t   app 域下 /proc 目录可读，只是别的 uid 的条目会被 SELinux 挡掉 ——\n" +
                "\t   LinuxProcessInspector 对读不到的条目一律跳过，不抛错。\n" +
                "\t   代价是只能看见自己 uid 的进程，而终端 shell 及其子进程全是本 app 的 uid。 */\n" +
                "\tif (platform === \"android\") return new LinuxProcessInspector(arch, internals);\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"

