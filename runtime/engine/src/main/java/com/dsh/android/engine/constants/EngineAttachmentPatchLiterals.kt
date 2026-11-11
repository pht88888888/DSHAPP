package com.dsh.android.engine.constants

/** 附件持久化和图片 MIME 补丁的原文字面量。 */
        /**
         * `dsh-attachment-local/lib/index.js` 里 `syncDirectory()` 的原样文本，
         * 以及容忍祖先目录打不开之后的文本。见 [patchAttachmentDurability]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
const val ATTACH_SYNC_GUARD =
            "\tconst handle = await open(path, constants.O_RDONLY);"
const val ATTACH_SYNC_ANCHOR =
            "\tlet handle;\n" +
                "\ttry {\n" +
                "\t\thandle = await open(path, constants.O_RDONLY);\n" +
                "\t} catch (err) {\n" +
                "\t\t/* Android: 祖先目录（/data、/data/user/0 等）被 SELinux 拒绝 open，\n" +
                "\t\t   而这些目录项的崩溃持久性在应用沙箱内本来就不可达，跳过即可。 */\n" +
                "\t\tif (err && (err.code === \"EACCES\" || err.code === \"EPERM\")) return;\n" +
                "\t\tthrow err;\n" +
                "\t}"

        /**
         * `handle.sync()` 那段的原样文本与容错版本。见 [patchAttachmentDurability]。
         *
         * **这是「发图报 session/agent-busy」的真正根因，只补 `open` 是不够的。**
         *
         * 因果链：`ensureDurableHome` 把持久化边界算成 `parse(home).root`（恒为 `/`），
         * `ensureDurableDirectory` 于是逐级上溯到 `/`，对只读分区做目录 `fsync`。
         * Android 的 `/` 是 erofs **只读**挂载，内核对只读文件系统上的目录 `fsync`
         * 返回 **`EINVAL`**（这是 Linux 标准行为，不是某台机器的毛病）。
         * 而原版 `sync()` 只有 `try/finally`、**没有 `catch`**，`EINVAL` 直接穿透 →
         * 附件落盘失败 → `session-controller` 兜底成 `session/agent-busy`。
         *
         * 表现上的迷惑点：**纯文字完全正常**。因为 `admitPromptContent` 里
         * `if (content.every((part) => part.type !== "image")) return;` ——
         * 只有图片才走附件落盘。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
const val ATTACH_FSYNC_GUARD =
            "\ttry {\n" +
                "\t\tawait handle.sync();\n" +
                "\t} finally {\n" +
                "\t\tawait handle.close();\n" +
                "\t}"
const val ATTACH_FSYNC_ANCHOR =
            "\ttry {\n" +
                "\t\tawait handle.sync();\n" +
                "\t} catch (err) {\n" +
                "\t\t/* Android: / 是 erofs 只读挂载，对只读文件系统上的目录做 fsync，\n" +
                "\t\t   内核返回 EINVAL（Linux 标准行为）。这类目录项本进程根本改不动，\n" +
                "\t\t   跳过即可 —— 否则整条附件落盘失败，用户看到的是「会话忙」。 */\n" +
                "\t\tif (err && (err.code === \"EINVAL\" || err.code === \"EACCES\" || err.code === \"EPERM\")) return;\n" +
                "\t\tthrow err;\n" +
                "\t} finally {\n" +
                "\t\tawait handle.close();\n" +
                "\t}"

        /**
         * 发图格式白名单的原版判断（精确比较 4 个 MIME）。
         *
         * ⚠️ 缩进是**三个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。见 [patchImageMediaType]。
         */
        /*
         * ⚠️ **锚点里不能带缩进**（2026-10-27 真机取证）。
         *
         * 原来这里是 `"\t\t\treturn value === \"image/png\" || …"` —— 写死 3 个
         * 制表符。而真机（HUAWEI CDY-TN90 / vc 29）上该文件的实际内容是
         * **6 个空格**缩进：
         *
         *     4861:      return value === "image/png" || … ;
         *     实测 `cat -A` → "      return value === … ;$"     ← 6 空格
         *     源码常量        → "^I^I^Ireturn value === … ;"     ← 3 制表符
         *
         * 于是 `source.contains(IMG_TYPE_GUARD)` 恒为 false，补丁**从未生效过**，
         * 每次启动都在 app.log 里留下一条「发图格式判断结构与预期不符，跳过补丁」。
         * 用户侧的表现就是那张表格里的问题原样存在：`.jpg`（`image/jpg`）、
         * iPhone 的 HEIC、部分相册的 BMP/AVIF、剪贴板粘贴的空 type
         * 全被拒，提示「仅支持 PNG、JPG、WebP、GIF」。
         *
         * 压缩产物里缩进本来就不可靠（不同版本的 minifier / 格式化配置都会改），
         * 所以锚点只锚**语句内容**，由 [patchImageMediaType] 用行级方式替换。
         */
const val IMG_TYPE_GUARD =
            "return value === \"image/png\" || value === \"image/jpeg\" || " +
                "value === \"image/webp\" || value === \"image/gif\";"

        /**
         * 放宽后的判断：任何 `image/` 前缀 + `image/jpg` 别名都放行。
         *
         * 解码能力交给下游 sharp 判断 —— 它会给明确错误，
         * 比在入口处一刀切拦住友好（见 [patchImageMediaType] 的表格）。
         */
        /**
         * 补丁要写入的**语句主体**（不含行首缩进）。
         *
         * ⚠️ 缩进由 [patchImageMediaType] 从被替换那一行的真实前缀里取，
         *   不写死 —— 原因见 [IMG_TYPE_GUARD] 的注释（真机上是 6 空格，
         *   而这里原来写的是 3 个制表符）。
         */
const val IMG_TYPE_ANCHOR =
            "if (typeof value !== \"string\") return false;\n" +
                "/* DSH Android: 放宽到任何 image/ 前缀。原版只认 4 个精确值，\n" +
                "   而 image/jpg、image/heic、image/avif、image/bmp 以及剪贴板粘贴\n" +
                "   的空 type 都会被误拒，用户看到的是「仅支持 PNG、JPG、WebP、GIF」。\n" +
                "   真正的解码能力由下游 sharp 判断，那里报错更准确。 */\n" +
                "return value.startsWith(\"image/\");"

