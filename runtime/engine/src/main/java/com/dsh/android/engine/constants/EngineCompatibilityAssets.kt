package com.dsh.android.engine.constants

/** 平台兼容产物的资产名、路径和完整性标记。 */
        /** WebView 兼容补丁：资产名与注入到 index.html 里的标签。 */
const val POLYFILL_FILE = "webview-polyfill.js"
const val POLYFILL_TAG = """<script src="./$POLYFILL_FILE"></script>"""

        /**
         * 引擎侧 HTTP header 净化垫片（见 [ensureFetchGuard]）。
         *
         * 解到 `filesDir` 根下（与 `dsh-android-shell.mjs` 同一层），通过
         * `NODE_OPTIONS=--import <绝对路径>` 预加载进**引擎进程**。
         *
         * ⚠️ 必须用**绝对路径**：`--import` 的裸标识符会走 Node 的模块解析，
         * 而引擎进程的 cwd 是 `engine/`，相对路径会解析到那里去。
         */
const val FETCH_GUARD_ASSET = "dsh-fetch-guard.mjs"
const val FETCH_GUARD_FILE = "dsh-fetch-guard.mjs"

        /**
         * 按旧内核降级编译的前端 bundle（见 [ensureWebViewCompat]）。
         *
         * 由 `tools/build-webview-compat.ps1` 生成，含 dist 与**各插件的 client bundle**
         * 两部分的降级产物（老版本只含 dist，实测漏了 71 个插件里 68 个的语法缺口）。
         * **换了 engine.bin 必须重跑脚本**。
         */
const val WEBVIEW_COMPAT_ASSET = "webview-compat.zip"

        /** 降级资产在 dist 里留下的戳，以及 zip 内对应的条目名。 */
const val WEBVIEW_COMPAT_STAMP = ".webview-compat-stamp"
const val WEBVIEW_COMPAT_STAMP_ENTRY = ".compat-stamp"

        /**
         * 降级 zip 的条目树前缀 —— 决定每个条目解到哪棵树上（见 [ensureWebViewCompat]）。
         *
         * 两个前缀必须与构建工具一致；插件使用组件构建的降级产物，禁止由兼容大包覆盖。
         */
const val COMPAT_PREFIX_DIST = "dist"
const val COMPAT_PREFIX_ENGINE = "engine"

        /**
         * link()/linkat() 语义转译层（LD_PRELOAD）。
         * 资产名用中性后缀，避免 AGP 按扩展名做格式推断（同 engine.bin 的坑）。
         */
const val LINKCOMPAT_ASSET = "linkcompat.bin"
const val LINKCOMPAT_FILE = "liblinkcompat.so"

        /**
         * **旧版**号池注入库的文件名，仅用于**清理**老安装的残留。
         *
         * 号池已改为服务端网关，不再需要 `LD_PRELOAD` 注入。这个常量保留下来
         * 只为一件事：`ensureKeyInject()` 要把用户设备上残留的
         * `filesDir/libkeyinject.so` 删掉 —— 留着它虽然不会再被加载，但会让人
         * 误以为注入层还在，排查问题时造成误导。
         *
         * ⚠️ 对应的资产 `assets/keyinject.bin` 与源码 `jni/keyinject.c` **已删除**，
         * 不要再加回来。旧的 `KEYINJECT_ASSET` 常量也一并删掉了。
         */
const val KEYINJECT_FILE = "libkeyinject.so"

