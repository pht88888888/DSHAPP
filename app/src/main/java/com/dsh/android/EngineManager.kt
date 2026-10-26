package com.dsh.android

import android.app.ActivityManager
import android.content.Context
import com.dsh.android.log.DshLog as Log
import com.dsh.android.log.EventLog
import com.dsh.android.log.LogStore
import com.dsh.android.log.CrashRecorder
import com.dsh.android.log.BootState
import com.dsh.android.log.RescueBoot
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * 管理内置 DSH 引擎的生命周期。
 *
 * 架构：
 *   $PREFIX/bin/node（Termux 官方 arm64 构建，随 APK 分发并由 TermuxBootstrap 铺入）
 *     └─ engine/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --no-open
 *          └─ 本地 HTTP 服务，打印带 token 的 URL
 *
 * 引擎通过子进程运行，App 解析其 stdout 得到 URL 后交给 WebView 加载。
 *
 * ## 为什么 Node 用 Termux 的构建而不是自编译
 *
 * node 与它的全部依赖（ICU / OpenSSL / libc++ …）都由 Termux 官方仓库针对 Android
 * bionic 构建，随 APK 内置即可离线可用。自编译需要交叉编译整个 V8，且要处理
 * `import.meta.main`（需 Node ≥ 24.2）等版本约束，成本与风险都高得多。
 *
 * ⚠️ 这条路线要求 `targetSdk ≤ 28`：Android 10 起 targetSdk ≥ 29 的应用不允许
 * `execve()` 自身私有目录里的文件（W^X / SELinux app_data_file 禁止 execute_no_trans），
 * 而 `$PREFIX/bin/node` 正落在私有目录里。详见 app/build.gradle.kts 的注释。
 */
class EngineManager private constructor(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Extracting(val percent: Int) : State
        data object Starting : State
        data class Ready(val url: String) : State
        data class Failed(val message: String) : State

        /**
         * 引擎**曾经就绪过**，之后进程退出 —— 与 [Failed] 是两回事。
         *
         * ## 为什么必须单独一个状态
         *
         * 这个区分不是措辞讲究，而是**恢复动作完全不同**：
         *
         * - [Failed] 是「压根没起来」（Node 缺失、端口被占、插件没激活…）。
         *   重试同一个配置只会再失败一次，用户得走「安全模式」推开坏配置。
         * - [Dead] 是「本来跑得好好的，后来死了」。绝大多数是系统回收
         *   （LMK / 国产 ROM 的后台冻结）或引擎自己 OOM。**配置是好的**，
         *   原样重启就是正确动作，绝不该推开用户的插件配置。
         *
         * ## 症状（用户报的）
         *
         * 原先没有任何地方检测运行期退出：引擎进程一死，`_state` 永远是
         * `Ready(url)`，界面停在上一次渲染好的页面上 —— 用户看着一切正常，
         * 直到发消息才发现「发送失败 / 一直转圈」。而前端的重连逻辑打的是
         * **那个已经没人监听的端口**，于是连错误提示都是误导性的。
         */
        data class Dead(val message: String) : State
    }

    /**
     * 页面阶段 —— 引擎就绪**之后**还要走的两段。
     *
     * 拆成独立的一条流而不是并进 [State]：`State.Ready` 带着 url，而
     * MainActivity 要靠它决定 `loadUrl`；如果把后续阶段也塞进同一个枚举，
     * 状态一推进 url 就丢了，`loadUrl` 的判断也跟着失效。
     *
     * 真机实测（OPPO PHY110 / Android 15，冷启动）各段耗时：
     * ```
     * responseEnd        231ms   ← 引擎已就绪，开始导航
     * domInteractive     450ms
     * domContentLoaded   557ms
     * loadEvent         2084ms   ← onPageFinished，此时前端 bundle 刚解析完
     * 插件激活收尾       3808ms   ← 移动端 UI 插件挂完，界面才真正可用
     * ```
     * 也就是说 `onPageFinished` 之后**还有约 1.7 秒**宿主完全感知不到，
     * 而那段时间屏幕上什么都不会变。加这两段就是为了让动画如实反映它。
     */
    sealed interface UiStage {
        /** 还没开始加载页面。 */
        data object None : UiStage

        /** `onPageFinished`：DOM 到了，但插件仍在激活。 */
        data object PageLoaded : UiStage

        /** 移动端 UI 插件已激活，界面可用 —— 启动动画到此收尾。 */
        data object UiReady : UiStage
    }

    private val _uiStage = MutableStateFlow<UiStage>(UiStage.None)
    val uiStage: StateFlow<UiStage> = _uiStage.asStateFlow()

    /**
     * 页面 DOM 就绪（`WebViewClient.onPageFinished`）。
     *
     * ⚠️ 幂等且**只前进不后退**：`onPageFinished` 会因转屏、iframe、
     *   重定向而多次触发，退回 None 会让动画倒放。
     */
    fun notifyPageLoaded() {
        if (_uiStage.value == UiStage.None) _uiStage.value = UiStage.PageLoaded
    }

    /** 移动端 UI 插件激活完成（由 WebView 的 JS 桥回调）。 */
    fun notifyUiReady() {
        _uiStage.value = UiStage.UiReady
    }

    /**
     * 把页面阶段退回 [UiStage.None]（引擎重启时用）。
     *
     * ## 为什么必须有这个「后退」
     *
     * [notifyPageLoaded] / [notifyUiReady] 都刻意**只前进不后退**（`onPageFinished`
     * 会因转屏、iframe 重复触发，退回 None 会让启动动画倒放）。
     *
     * 但引擎**重启**是另一回事：整个页面要重新加载，那两段必须能重走一遍。
     * 不复位的话，`_uiStage` 已经停在 `UiReady` —— 而 StateFlow **相同值不会重复
     * 发射**，于是 `MainActivity` 里「等首帧再收启动层」的分支一次都不会跑到：
     * `webViewPainted` 永远停在 false，启动层再也收不起来（界面上就是一直卡在
     * 「正在加载界面…」）。
     *
     * ⚠️ 只在**引擎重启**这条路径上调用，不要挂到普通的页面重载上。
     */
    fun resetUiStage() {
        _uiStage.value = UiStage.None
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 当前引擎进程。
     *
     * ⚠️ `@Volatile`：由启动协程写、由该进程自己的 stdout 消费线程读
     *   （见 [markEngineDeadIfCurrent] 的 `process !== proc` 判据）。读到旧值时
     *   判据会偏向「不动状态」，是安全的一侧；但没有可见性保证时行为不可预期，
     *   所以显式标上。
     */
    @Volatile
    private var process: Process? = null

    /**
     * 是否是**我们自己**在停引擎（[stop]）。
     *
     * ⚠️ 用来区分「引擎意外死亡」与「用户/代码主动停止」。两者都会让 stdout 读到
     *   EOF，若不区分，`stop()` 之后会立刻把状态打成 [State.Dead]（"引擎意外退出"），
     *   盖掉 `stop()` 自己设的 [State.Idle]，界面上就会莫名其妙弹一句失败提示。
     */
    @Volatile
    private var userStopped = false
    private val logLines = ArrayDeque<String>()

    /**
     * 最近一次 [ensureExtracted] 收到的版本戳。
     *
     * 体检（见 [preflight]）发现 `engine/` 目录被改坏时，要拿它强制重解包 ——
     * 版本戳是调用方（MainActivity）传进来的，引擎这一层不能自己编一个。
     */
    private var lastAssetStamp: String? = null

    /**
     * 降级前端是否已就位（见 [ensureWebViewCompat]）。
     *
     * 供 [MainActivity] 决定要不要因内核过旧而拦截加载：降级生效后门槛就降到
     * `chrome73`，不再一律卡 94。
     *
     * ⚠️ 由 IO 线程写、主线程读（`observeEngine` 的 `Ready` 分支），必须 `@Volatile`。
     */
    @Volatile
    var webViewCompatApplied = false
        private set

    /**
     * 本次引擎启动时，root 工具是否**真的挂上了**。
     *
     * ## 为什么必须单独记一笔，而不是让界面去看探测结论
     *
     * `root` 工具是作为 `--patch` overlay 挂载的，而 overlay 是**命令行参数** ——
     * 只在引擎进程启动那一刻读一次。所以「本机有 root」与「这个会话里能用 root 工具」
     * 是**两件事**：
     *
     * - 用户在设置页点「重新检测」，拿到 `GRANTED` ⇒ 结论变了；
     * - 但引擎已经在跑，overlay 不会再读 ⇒ **`root` 工具这个会话里根本不存在**。
     *
     * 此时界面若照旧显示「root 工具已注册，AI 可以直接以 uid 0 执行命令」，
     * 就是**在说假话**：模型根本没有这个工具可调。用户会以为可用，然后发现
     * AI 完全不碰 root，只能靠重启 App 才「突然好了」—— 而这没有办法自己发现。
     *
     * 所以：探测结论（缓存，随时可刷新）与**挂载事实**（启动时定死）必须分开暴露，
     * 界面按这两者组合出诚实的文案（见 `client.js` 的 `rootNextOnPendingReload`）。
     *
     * ⚠️ 由启动协程写、WebView 的 JS 线程读（[MainActivity] 的 `rootState()` 桥），
     *   必须 `@Volatile`。
     */
    @Volatile
    var rootToolMounted = false
        private set

    /**
     * 救援 profile 本次启动是否可用（[ensureRescueProfile] 建出来了）。
     *
     * ⚠️ 与 [rootToolMounted] 同构：由启动协程写、主线程/桥读，必须 `@Volatile`。
     *
     * 用途：降级阶梯在决定「要不要退到 L2」之前必须问一句 ——
     * 救援 profile 建不出来时退到 L2 只会**再失败一次**，那比直接退 L3 更糟
     * （用户多等一轮、日志里多一条误导性的失败）。所以这个事实必须能被读到。
     */
    @Volatile
    var rescueAvailable = false
        private set

    /**
     * 本次引擎启动时，uid 2000（`shell`）通道是否**真的挂上了**。
     *
     * 与 [rootToolMounted] 同构：`shell` 工具同样走 `--patch`，同样是启动时定死。
     * 区别在于它的挂载判据用的是产物文件是否存在（见 [writePrivilegedPatch]），
     * 而 root 用的是端到端探测结果。
     */
    @Volatile
    var shellToolMounted = false
        private set

    /** Termux 环境：提供 node 可执行文件与 $PREFIX。 */
    private val bootstrap by lazy { com.dsh.android.termux.TermuxBootstrap(context) }

    /** 引擎解包目录：filesDir/engine */
    private val engineDir get() = File(context.filesDir, "engine")

    /**
     * 已落盘的 HTTP header 净化垫片路径（见 [ensureFetchGuard]）。
     *
     * `null` = 还没解出来 / 解失败。两个 [ProcessBuilder] 的 env 都要读它，
     * 所以缓存在字段里，避免重复读盘。
     */
    @Volatile
    private var fetchGuardPath: File? = null

    /** 引擎运行所需的可写 HOME（DSH 在此存放配置、凭据、会话）。 */
    private val homeDir get() = File(context.filesDir, "home")

    /** DSH_HOME：DSH 自己的数据目录。
     *
     * ⚠️ 路径字面量来自 [DshApplication.DSH_HOME_REL]（**单一真相源**）：
     *   `attachBaseContext()` 里装 [com.dsh.android.log.RescueBoot] 时
     *   `EngineManager` 实例还不存在，只能从常量拼。两边各写一份的话，
     *   改一处漏一处就是两个组件看不同的目录，症状只是「读不到状态」。
     */
    private val dshHome get() = File(context.filesDir, DshApplication.DSH_HOME_REL)

    /**
     * `$DSH_HOME` 的**只读**对外视图 —— 给宿主侧的信箱桥用
     * （见 [UiProbeBridge]、[BubbleCommandBridge]）。
     *
     * 那些桥跑在 App 进程里，和模型（Node 进程）靠**同一个目录下的文件**通信，
     * 所以它们都得知道 `$DSH_HOME` 在哪。暴露成 `val` 而不是让各处自己拼
     * `File(filesDir, "home/.dsh")`：拼错一次就是两个进程永远看不到对方的文件，
     * 而症状只是「工具超时」，没有任何线索指向路径。
     */
    val dshHomeDir: File get() = dshHome

    /** 引擎工作目录（agent 的文件操作以此为根）。 */
    private val workspaceDir get() = File(context.filesDir, "workspace")

    /** 版本戳：内容变化时触发重新解包。 */
    private val stampFile get() = File(engineDir, ".stamp")

    /**
     * 确保引擎资产已解包（幂等）。
     *
     * @param versionStamp 资产版本标识；与已解包版本一致时直接跳过。
     */
    suspend fun ensureExtracted(versionStamp: String) = withContext(Dispatchers.IO) {
        lastAssetStamp = versionStamp
        if (stampFile.isFile && stampFile.readText().trim() == versionStamp) {
            Log.i(TAG, "引擎资产已是最新（$versionStamp），跳过解包")
            return@withContext
        }

        _state.value = State.Extracting(0)
        // 先解到临时目录，成功后再原子替换，避免中途失败留下半成品
        val staging = File(context.filesDir, "engine.staging")
        staging.deleteRecursively()

        /*
         * 空间预检：不够就**立刻**报一句人话，不要先花十几秒解到一半再抛 IOException。
         *
         * ⚠️ 真机上这条路径很容易被走到：引擎解压后约 **262 MB**，而用户把手机
         *   装满是常态。不做预检时的症状是「正在解包引擎 47%」卡住 → 抛
         *   `IOException: No space left on device` → 界面显示
         *   「引擎解包失败: ...」，用户完全不知道是自己空间不够、也不知道要清多少。
         *
         * 余量给 1.25 倍：解压过程需要的峰值略高于最终大小（tar 条目 + 块对齐）。
         * ⚠️ getUsableSpace() 是**建议值**，会随系统压力变化，所以它只能用来
         *   「提前劝退明显不够的情况」，**不能**代替写失败的处理 —— 下面的 catch
         *   依然要保留（写一半空间被别的进程吃掉照样会发生）。
         */
        run {
            val need = estimateExtractBytes()
            if (need > 0L) {
                val free = staging.parentFile?.usableSpace ?: 0L
                val required = (need * 5L) / 4L
                if (free in 1 until required) {
                    val msg = "存储空间不足：解包引擎需要约 " +
                        "${required / (1024 * 1024)} MB，当前可用仅 ${free / (1024 * 1024)} MB"
                    Log.e(TAG, msg)
                    _state.value = State.Failed(msg)
                    return@withContext
                }
            }
        }

        staging.mkdirs()

        try {
            // 进度基准：**tar.gz 压缩流的总字节数**（约 54 MB）。
            //
            // ⚠️ 这里原先用 `assets.openFd(ASSET_NAME).length`，而它**必然失败**：
            //   openFd 只对**未压缩**（stored）的资产有效，AGP 对 engine.bin 做了
            //   deflate，于是抛 FileNotFoundException → total=0 → 下面的
            //   `if (total > 0)` 恒假 → **进度永远停在 0%**。
            //   真机实测确认：首次启动那 15 秒里文案一直是「正在解包引擎 0%」。
            //
            // 改用 `ZipFile` 直接读 APK 自己的 zip 目录（`sourceDir` 就是 APK 路径），
            //   那里有权威的未压缩大小。
            //
            // ⚠️ 曾经想过读 gzip 尾部的 ISIZE 字段（那是解压后总字节数，实测精确），
            //   但那要**完整 inflate 一遍 262 MB** 才能摸到最后 4 字节 —— 白白让首次
            //   启动多花几秒。这里改成「基准 = 压缩流总长 + 实时统计已消费的压缩字节」，
            //   量纲天然一致，零额外成本。
            val total = archiveLength() ?: 0L
            if (total <= 0L) {
                Log.w(TAG, "读不到归档长度，本次解包不显示百分比")
            }
            var lastPercent = -1
            context.assets.open(ASSET_NAME).use { raw ->
                val counting = CountingInputStream(raw)
                TarGz.extract(counting, staging) {
                    if (total > 0L) {
                        val pct = ((counting.count * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPercent) {
                            lastPercent = pct
                            _state.value = State.Extracting(pct)
                        }
                    }
                }
            }
            File(staging, ".stamp").writeText(versionStamp)

            engineDir.deleteRecursively()
            if (!staging.renameTo(engineDir)) {
                throw java.io.IOException("无法将解包目录重命名为 $engineDir")
            }
            _state.value = State.Extracting(100)
            Log.i(TAG, "引擎资产解包完成：${engineDir.absolutePath}")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            _state.value = State.Failed("引擎解包失败: ${t.message}")
            throw t
        }
    }

    /**
     * 读 `assets/engine.bin` 在 APK 里的**未压缩大小**（约 54 MB）。
     *
     * ⚠️ 为什么不用 `assets.openFd().length`：那只对未压缩资产有效，而 AGP 对
     *   engine.bin 做了 deflate，必然抛 FileNotFoundException。见调用点的注释。
     *
     * `context.applicationInfo.sourceDir` 就是 APK 的绝对路径，用 `ZipFile` 打开它
     * 即可拿到权威的 zip 目录项大小 —— 只读中央目录，不解压、不落盘，开销可忽略。
     *
     * ⚠️ 失败一律返回 null，**绝不能因此让解包失败** —— 没有百分比只是体验差，
     *   解包失败是功能不可用。
     */
    private fun archiveLength(): Long? = try {
        val apk = File(context.applicationInfo.sourceDir)
        java.util.zip.ZipFile(apk).use { zip ->
            // ⚠️ zip 里的条目全名带 `assets/` 前缀，直接用 ASSET_NAME 查会得到 null
            //   （且**不抛异常**，只是静默返回 null）。这里踩过一次。
            val entry = zip.getEntry("assets/$ASSET_NAME")
            val len = entry?.size ?: 0L
            Log.i(TAG, "engine.bin 归档长度 = $len 字节（entry=${entry?.name}）")
            len.takeIf { it > 0L }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读归档长度失败: ${t.message}")
        null
    }

    /**
     * 估算解包引擎**需要多少磁盘空间**（字节）。估不出来时返回 0（调用方跳过预检）。
     *
     * ## 为什么不用 `archiveLength()`
     *
     * 那个返回的是 **APK 里 engine.bin 这个 zip 条目的压缩后大小**（约 55 MB），
     * 而 tar.gz 解出来的内容约 **262 MB** —— 差了近 5 倍。拿它做空间判断会
     * 让「明明装得下」的用户被误拒，属于比不做预检更糟的行为。
     *
     * ## 这里怎么估
     *
     * 要拿到准确值只有两条路，都不合适：
     *   · 读完 gzip 尾部的 ISIZE 字段 —— 需要完整 inflate 一遍 262 MB（首次启动多花几秒）；
     *   · 逐条遍历 tar 头累加 size —— 也要把整个压缩流解一遍。
     *
     * 所以用**压缩比的保守上界**推：tar.gz 里绝大多数是 JS 文本，实测压缩比约 4.7:1。
     * 取 4 倍作为估计（比实测小）→ 给出 220 MB 左右的下界，再乘 1.25 的峰值余量。
     * 宁可估小一点：估大了会把本来装得下的用户挡在门外，那不可接受；
     * 估小了只是这次预检没拦住，后面写失败的 catch 仍然兜着。
     */
    private fun estimateExtractBytes(): Long {
        val archived = archiveLength() ?: return 0L
        return archived * 4L
    }

    /**
     * 只统计「从底层流读走了多少字节」的包装流。
     *
     * 用于把解包进度换算成**压缩流**的消费比例 —— 与 [archiveLength] 同一量纲。
     * 不改变任何读写语义，纯粹是个计数器。
     */
    private class CountingInputStream(
        private val delegate: java.io.InputStream,
    ) : java.io.InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }

    /**
     * 给 DSH 自带前端打兼容补丁（幂等）。
     *
     * DSH 前端按较新的 Chromium 编写，而设备上的 WebView 可能是旧版（本机是 114），
     * 缺 `Promise.withResolvers` / `AbortSignal.any` 等标准 API 时会直接抛 TypeError，
     * 界面起不来。这里在 `index.html` 的模块脚本之前插入一段 polyfill。
     *
     * 之所以改运行时目录而不是打包时改资产：引擎资产每次升级都会重新解包覆盖，
     * 放在这里可以跟着资产版本自动重打，不必重新制作 engine.bin。
     */
    private fun ensureWebCompat() {
        val dist = File(engineDir, "node_modules/@deepseek-ai/dsh-web-frontend/dist")
        val indexHtml = File(dist, "index.html")
        if (!indexHtml.isFile) {
            Log.w(TAG, "未找到前端入口，跳过 WebView 兼容补丁: ${indexHtml.absolutePath}")
            return
        }

        try {
            // polyfill 脚本本身每次都覆盖写：它随 APK 升级而变，代价只有几 KB
            val polyfill = File(dist, POLYFILL_FILE)
            context.assets.open(POLYFILL_FILE).use { input ->
                polyfill.outputStream().use { output -> input.copyTo(output) }
            }

            val html = indexHtml.readText()
            if (html.contains(POLYFILL_TAG)) {
                return
            }

            // 必须在 type="module" 的入口脚本之前执行：模块脚本是延迟执行的，
            // 但 polyfill 要保证在那之前就已挂好。
            val anchor = """<script type="module""""
            val idx = html.indexOf(anchor)
            if (idx < 0) {
                Log.w(TAG, "前端入口结构与预期不符，跳过 WebView 兼容补丁")
                return
            }
            val patched = html.substring(0, idx) + POLYFILL_TAG + "\n    " + html.substring(idx)
            indexHtml.writeText(patched)
            Log.i(TAG, "已注入 WebView 兼容补丁: $POLYFILL_FILE")
        } catch (t: Throwable) {
            // 补丁失败不阻断启动：新版 WebView 本来就不需要它
            Log.w(TAG, "注入 WebView 兼容补丁失败: ${t.message}")
        }
    }

    /**
     * 落盘引擎侧的 HTTP header 净化垫片，返回它的绝对路径（失败返回 null）。
     *
     * ## 为什么必须有这一层
     *
     * 用户的真实报错（2026-10-25 真机截图）：
     *
     * ```
     * 本轮运行失败
     * Cannot convert argument to a ByteString because the character at index 21
     * has a value of 27185 which is greater than 255.
     * ```
     *
     * 这是 **undici 的 `Headers` 构造器**抛的：HTTP header 值必须是 ByteString
     * （每字符 ≤ 0xFF），而某个值里混进了中文（27185 = U+6A31）。用户看到的是
     * 一串英文 + 一个数字，**完全不知道该改哪里**，而且消息直接发不出去。
     *
     * 排查结论：**全链路只有两处**会把 header 交给 undici —— 引擎自己的
     * `new Headers(...)`，以及各家 SDK 内部的 `fetch`。两处最终都落在同一个
     * 全局对象上，所以在引擎进程里兜一层，等于覆盖了**所有**调用方，
     * 包括未来引擎升级后新增的那些。
     *
     * ## 为什么用 `--import` 而不是改引擎源码
     *
     * 本类里已有 `patchLlmPiAiToolCallId` 那种**直接改引擎文件**的先例，但那种
     * 做法依赖源码锚点：引擎一升级、结构一变，补丁就静默跳过（那里确实会打日志
     * 然后 return）。`--import` 是 Node 的**官方预加载机制**，只依赖
     * 「进程启动时要加载这个模块」，不依赖任何引擎内部结构，抗升级。
     *
     * 每次都覆盖写：模块本身只有几 KB，跟着 APK 升级自动生效，不需要版本戳
     * （与 [ensureWebCompat] 对 polyfill 的处理同理）。
     */
    private fun ensureFetchGuard(): File? {
        return try {
            val target = File(context.filesDir, FETCH_GUARD_FILE)
            val temp = File(context.filesDir, "$FETCH_GUARD_FILE.tmp")

            // ⚠️ **必须先写临时文件再原子 rename**，不能直接往 target 上覆盖写。
            //
            // 这个文件是 `--import` 的加载目标，而 Node 对预加载模块的**语法错误**
            // 是解析阶段就炸的（实测：文件里混进一行非法语法 ⇒
            // `SyntaxError: Unexpected identifier` ⇒ 进程根本不启动），
            // 垫片内部的 try/catch **救不了**（它在模块求值之后才生效）。
            //
            // 直接覆盖写的话，只要某次写入中途失败（磁盘满、进程被杀、电量断），
            // target 就永久停在「半个文件」上，之后**每次启动引擎都起不来**。
            // 先写 .tmp 再 rename，target 要么是旧的完整版、要么是新的完整版，
            // 不存在中间态。
            context.assets.open(FETCH_GUARD_ASSET).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            // 落盘后自检：内容必须与资产一致，且看起来像一份完整的 ES 模块。
            // 不一致就放弃替换，继续用上一次的（或干脆不启用）。
            val expected = context.assets.open(FETCH_GUARD_ASSET).use { it.readBytes() }
            val actual = temp.readBytes()
            if (!expected.contentEquals(actual)) {
                Log.w(TAG, "净化垫片写入不完整（${actual.size}/${expected.size} 字节），放弃替换")
                temp.delete()
                return fetchGuardPath?.takeIf { it.isFile }
            }
            if (!actual.toString(Charsets.UTF_8).contains("export function install()")) {
                Log.w(TAG, "净化垫片内容异常（缺少 install 导出），放弃替换")
                temp.delete()
                return fetchGuardPath?.takeIf { it.isFile }
            }

            if (!temp.renameTo(target)) {
                // rename 失败时退回覆盖写（极罕见：同目录 rename 一般不会失败）
                Log.w(TAG, "净化垫片 rename 失败，退回直接覆盖写")
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setReadable(true, false)
            fetchGuardPath = target
            Log.i(TAG, "已解出 HTTP header 净化垫片 ${target.name} (${target.length()} 字节)")
            target
        } catch (t: Throwable) {
            // 垫片缺失不阻断启动：它只影响「配置里混进中文」这一种异常场景
            fetchGuardPath = null
            Log.w(TAG, "解出 HTTP header 净化垫片失败: ${t.message}")
            null
        }
    }

    /**
     * 拼引擎进程的 `NODE_OPTIONS`：堆上限 + （可选）预加载净化垫片。
     *
     * ⚠️ 垫片**没落盘就绝不能出现在这里**：`--import` 指向不存在的文件时
     * Node 会直接拒绝启动（`ERR_MODULE_NOT_FOUND`），整台 App 打不开 ——
     * 那比「配置里混了中文发不出消息」严重得多。所以按 [fetchGuardPath] 决定。
     *
     * 路径含空格也不加引号：`NODE_OPTIONS` 由 Node 自己按空白切分，加了引号
     * 反而会把引号当成路径的一部分。`filesDir` 是
     * `/data/user/0/<包名>/files`，包名恒为 `com.dsh.android`，不含空格。
     */
    private fun nodeOptions(): String {
        val base = "--max-old-space-size=${oldSpaceMb()}"
        val guard = fetchGuardPath ?: return base
        if (!guard.isFile) return base
        return "$base --import ${guard.absolutePath}"
    }

    /**
     * 决定 Node 的老生代堆上限（MB）。
     *
     * ## ⚠️ 为什么不能写死 1024
     *
     * 原来这里是 `--max-old-space-size=1024`，没有任何说明。真机证据
     * （HUAWEI CDY-TN90，Android 10，**物理内存 7.7 GB**）上的 `engine.log`：
     *
     * ```
     * Mark-Compact 1004.6 (1029.1) -> 1001.7 (1029.4) MB
     * FATAL ERROR: Ineffective mark-compacts near heap limit
     * Allocation failed - JavaScript heap out of memory
     * ```
     *
     * 堆一满到 1004 MB 就 SIGABRT（退出码 134），**6 天的崩溃文件里这个 FATAL
     * 出现了 10 次**，每次自动重启要 27 秒（`engine.ready ms=27534`），
     * 期间界面完全没有后端。
     *
     * 关键点：**这不是设备内存不够**。7.7 GB 的机器上 V8 只被允许用 1 GB，
     * 撞上限是必然的 —— 长会话累积上下文、检索、日志都会把堆推上去。
     * 换句话说，原来的 1024 是在一台 7.7 GB 的机器上人为制造瓶颈。
     *
     * ## 取值规则
     *
     * 按**物理内存**分档，并给 App 自身与系统留足余量（V8 的 old space 只是
     * 引擎进程的一部分，外面还有 native、code space、以及 App 自己的 Java 堆）：
     *
     * | 物理内存 | old space | 说明 |
     * |---|---|---|
     * | ≥ 6 GB | 3072 MB | 留 4 GB 以上给系统与本 App，够用且不会把系统逼进 LMK |
     * | ≥ 4 GB | 2048 MB | 中端机常见档 |
     * | ≥ 3 GB | 1536 MB | |
     * | 其它 | 1024 MB | **不低于原值** —— 低端机上贸然加大只会把系统拖进 LMK，比 OOM 更糟 |
     *
     * ⚠️ **下限保持 1024**：本修复的目标是"别在内存充裕的机器上人为卡死"，
     *   不是"所有机器都加大"。内存紧张时 1024 已经会与系统抢，再加只会
     *   让整个 App 被 LMK 杀掉（那比引擎自己 SIGABRT 更难受，因为连日志都留不下）。
     *
     * ⚠️ 拿不到 `totalMem` 时（极罕见）退回 1024，与修复前一致。
     */
    private fun oldSpaceMb(): Int {
        val total = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            mi.totalMem
        }.getOrDefault(0L)
        val gb = total / (1024L * 1024L * 1024L)
        return when {
            gb >= 6L -> 3072
            gb >= 4L -> 2048
            gb >= 3L -> 1536
            else -> 1024
        }
    }

    /**
     * 把前端 bundle 换成按旧内核降级编译的版本（幂等）。
     *
     * **为什么 polyfill 不够**：[ensureWebCompat] 那种运行时垫片只能补**缺失的 API**，
     * 而官方前端还用了**新语法** —— 最典型的是 class static block（`static { ... }`，
     * Chromium 94+）。语法缺口发生在**解析阶段**，整份 chunk 一行都不执行，
     * 垫片根本没机会跑。表现是 HTML/CSS 都正常、`#root` 永远为空，
     * 页面侧连个错误都报不出来。
     *
     * 真机实测（用户反馈的 vivo Y52s / Android 10，**WebView 内核 83**）：
     *   index  : class static block ×2（需 94）、逻辑赋值 ??= ×25（需 85）
     *   vendor : private method ×3（需 84）、逻辑赋值 ×7（需 85）
     *   CSS    : inset ×18（需 87）
     *
     * **降级范围是「三棵树」，不是只有 dist**（v2 起）。前端分两部分下发，
     * 各自独立编译，只降 dist 会漏掉全部插件：
     *
     * | zip 条目前缀 | 落到哪 | 为什么 |
     * |---|---|---|
     * | `dist/`     | `engine/…/dsh-web-frontend/dist/` | 官方 shell |
     * | `engine/`   | `engine/node_modules/@deepseek-ai/` | 71 个插件的 client bundle |
     * | `mobileui/` | `$DSH_HOME/mobile-ui/dsh-android-mobile/` | App 自己注入的插件 |
     *
     * ⚠️ **这是线上真实踩到的坑**（用户反馈 `groups.toSorted is not a function`，
     *    模型选择器整块打不开；同一类缺口的 `dsh-client-ui-settings-models`
     *    则让「模型」设置页**完全空白**）。v1 只降了 dist，实测 71 个插件 bundle 里
     *    有 68 个在 chrome74 下有语法缺口 —— 覆盖率不到 5%。
     *
     * ⚠️ **`mobileui/` 必须晚于 [ensureMobileUi] 执行**：[ensureMobileUi] 里
     *    版本戳不一致时会 `target.deleteRecursively()` 整个重解包，**会把降级产物抹掉**。
     *    所以在 [start] 里的调用顺序是 `ensureMobileUi()` → `ensureWebViewCompat()`
     *    （而 `dist`/`engine` 两棵树没有这个问题，谁先谁后都行）。
     *
     * ⚠️ 幂等靠 `dist/.webview-compat-stamp` 与 zip 内 `.compat-stamp` 比对。
     *    **戳文件写在 dist 里而不是别处**：引擎升级会重新解包 dist，戳跟着一起没，
     *    于是自动重打 —— 正是想要的。文件名带内容 hash（`index-5SrrfWpU.js`），
     *    引擎升级后旧资产必然对不上，必须重跑脚本重新生成。
     *    ⚠️ 换降级范围/目标必须 bump `-CompatVersion`，否则老设备命中「戳一致 → 跳过」，
     *    新纳入的那部分**永远不生效**（静默，最难查的那种）。
     */
    private fun ensureWebViewCompat() {
        // 内置内核版整个跳过：这一整套的存在理由是「设备上的系统 WebView 太旧」，
        // 而内置内核的版本在编译期就定了（GeckoView 140），永远不可能过旧。
        // 跑了反而有害 —— 前端会被换成按 chrome73 降级、砍掉语法糖的版本。
        if (BuildConfig.BUILT_IN_WEB_ENGINE) {
            Log.i(TAG, "内置 Web 内核版：不需要 WebView 降级前端，跳过")
            return
        }

        val dist = File(engineDir, "node_modules/@deepseek-ai/dsh-web-frontend/dist")
        if (!dist.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 WebView 降级资产")
            return
        }

        try {
            // ── 第一遍：**只读戳、只数前缀**，不持有任何条目内容 ──────────
            //
            // ⚠️ **原来是「把 zip 全部条目读进 LinkedHashMap<String, ByteArray>」
            //   再决定要不要落盘 —— 这是个会真炸的 OOM 风险，已改掉。**
            //
            //   实测本资产解压后共 **32.2 MB**（234 个条目，单个最大 8.7 MB 的
            //   client.excel.js）。旧写法等于每次启动都在堆上摊开 32 MB 字节数组，
            //   而且**连「戳一致、什么都不用做」的那条路径也要先摊开**才判断得出来 ——
            //   绝大多数启动都属于这一类，等于纯白付。
            //
            //   触发条件在真机上很现实：低内存机（2~3 GB）、Dalvik 堆上限 192~256 MB，
            //   再叠加引擎/WebView 的常驻占用，32 MB 的瞬时分配足以 OutOfMemoryError。
            //   而这里 catch 的是 Throwable，OOM 会被吞成一行 W，表现为
            //   「降级没打上 → 老内核白屏」，排查方向完全跑偏。
            //
            //   改成两遍扫描后：跳过路径**零字节分配**；落盘路径一次只持有**单个条目**。
            var stampInZip = ""
            val prefixCounts = HashMap<String, Int>()
            var totalEntries = 0
            java.util.zip.ZipInputStream(context.assets.open(WEBVIEW_COMPAT_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (entry.name == WEBVIEW_COMPAT_STAMP_ENTRY) {
                            stampInZip = zis.readBytes().toString(Charsets.UTF_8).trim()
                        } else {
                            totalEntries++
                            val p = entry.name.substringBefore('/')
                            prefixCounts[p] = (prefixCounts[p] ?: 0) + 1
                        }
                    }
                    // ⚠️ closeEntry 会自行 skip 掉当前条目未读的数据，**不占内存** ——
                    //   所以「不读内容也能前进」是成立的，不需要先读出来再丢掉。
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (totalEntries == 0) {
                Log.w(TAG, "WebView 降级资产是空的，跳过")
                return
            }

            val engineModules = File(engineDir, "node_modules/@deepseek-ai")
            val mobileUiRoot = File(dshHome, "$MOBILE_UI_DIR/$MOBILE_UI_PACKAGE")

            // ⚠️ **两棵树各判各的戳，不能只看 dist 那一个。**
            // [ensureMobileUi] 在版本戳不一致时会 `deleteRecursively()` 整个重解包
            // mobile-ui 目录 —— 降级产物连同它自己的戳一起被抹掉。若这里仍按 dist 的戳
            // 判定「已就位」就整体跳过，结果就是**新装的 mobile-ui 是没降级的原版**，
            // 在老内核上静默退回桌面布局。两戳分开后，重解包会自动触发重降级。
            val distUpToDate = File(dist, WEBVIEW_COMPAT_STAMP).let {
                it.isFile && it.readText().trim() == stampInZip
            }
            val wantsMobile = (prefixCounts[COMPAT_PREFIX_MOBILEUI] ?: 0) > 0
            val mobileStamp = File(mobileUiRoot, WEBVIEW_COMPAT_STAMP)
            val mobileUpToDate = !wantsMobile ||
                (mobileStamp.isFile && mobileStamp.readText().trim() == stampInZip)

            if (distUpToDate && mobileUpToDate) {
                webViewCompatApplied = true
                // 幂等跳过也要打日志：否则「降级到底在不在」只能靠翻文件系统确认，
                // 而真机上排查白屏时这条信息恰恰最关键（见 MainActivity 的门槛判定）。
                Log.i(TAG, "WebView 降级前端已就位，跳过（$stampInZip）")
                return
            }

            val counts = LinkedHashMap<String, Int>()
            var distWrote = false
            var mobileWrote = false

            // ── 第二遍：逐条流式落盘，一次只持有**一个条目** ──────────────
            //
            // ⚠️ 不要退回到「先把 entries 全读进 Map 再写」：见上面第一遍的说明，
            //   那是 32 MB 的堆分配。这里每条写完立刻丢掉，峰值 ≈ 最大单条（8.7 MB）。
            java.util.zip.ZipInputStream(context.assets.open(WEBVIEW_COMPAT_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name != WEBVIEW_COMPAT_STAMP_ENTRY) {
                        val name = entry.name
                        // 条目前缀决定落到哪棵树。前缀本身就是防目录穿越的第一道闸：
                        // 认不出的前缀直接丢弃，绝不按 zip 里的路径原样拼。
                        val prefix = name.substringBefore('/')
                        var root: File? = null
                        var rel = ""
                        when (prefix) {
                            COMPAT_PREFIX_DIST -> if (!distUpToDate) {
                                root = dist
                                rel = name.removePrefix("$COMPAT_PREFIX_DIST/")
                                distWrote = true
                            }
                            COMPAT_PREFIX_ENGINE -> if (!distUpToDate) {
                                root = engineModules
                                rel = name.removePrefix("$COMPAT_PREFIX_ENGINE/")
                                distWrote = true
                            }
                            COMPAT_PREFIX_MOBILEUI -> if (!mobileUpToDate) {
                                root = mobileUiRoot
                                rel = name.removePrefix("$COMPAT_PREFIX_MOBILEUI/")
                                mobileWrote = true
                            }
                            else -> {
                                Log.w(TAG, "跳过前缀无法识别的降级条目: $name")
                            }
                        }

                        if (root != null) {
                            val out = File(root, rel)
                            when {
                                // 防目录穿越：资产是我们自己打的，但解包路径仍按约定校验
                                !out.canonicalPath.startsWith(root.canonicalPath + File.separator) -> {
                                    Log.w(TAG, "跳过越界的资产条目: $name")
                                }
                                // mobile-ui 那棵树要等 [ensureMobileUi] 先解包出目录才能写。
                                // 真缺了就跳过（下次启动会重试，因为戳没写），
                                // 不要把整趟降级带崩 —— dist/engine 两棵树跟它无关，照常生效。
                                prefix == COMPAT_PREFIX_MOBILEUI && !mobileUiRoot.isDirectory -> {
                                    mobileWrote = false
                                }
                                else -> {
                                    out.parentFile?.mkdirs()
                                    // 流式拷贝：不把整条读成 ByteArray
                                    out.outputStream().use { zis.copyTo(it) }
                                    counts[prefix] = (counts[prefix] ?: 0) + 1
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 戳只在对应树真的写了之后才落盘，否则会把「没写成功」记成「已完成」
            if (distWrote || distUpToDate) {
                File(dist, WEBVIEW_COMPAT_STAMP).writeText(stampInZip)
            }
            if (mobileWrote) {
                File(mobileUiRoot, WEBVIEW_COMPAT_STAMP).writeText(stampInZip)
            } else if (wantsMobile && !mobileUpToDate) {
                Log.w(TAG, "移动端 UI 插件尚未就位，其降级条目顺延到下次启动")
            }

            webViewCompatApplied = true
            Log.i(
                TAG,
                "已应用 WebView 降级前端: ${counts.entries.joinToString(", ") { "${it.key}=${it.value}" }} 个文件（$stampInZip）",
            )
        } catch (t: Throwable) {
            // 打不上就维持原样：新内核本来就不需要它，旧内核也只是回到白屏
            Log.w(TAG, "应用 WebView 降级前端失败: ${t.message}")
        }
    }

    /**
     * 安装 android-arm64 的原生插件（幂等）。
     *
     * DSH 的 profile 解析依赖 `node-addon-require-builtin`：它通过 `dlsym` 拿到 Node
     * 私有的 `PrincipalRealm::builtin_module_require` 符号，再按 AArch64 机器码匹配出
     * Realm 里那个字段的偏移。该插件官方只发布 darwin / linux-glibc / win32 变体，
     * **没有 android**，而 Android 用的是 bionic，`__GLIBC__` 未定义，运行时会直接
     * 报 `No usable native binding found for node-addon-require-builtin-android-arm64`。
     *
     * 这里的做法是用 NDK 为 android-arm64 自行编译一份（复用 linux-glibc-arm64 的
     * AAPCS 匹配器 —— 纯指令匹配，与 libc 无关），按官方平台包的结构装进
     * `node_modules/node-addon-require-builtin-android-arm64`。
     *
     * 插件很小（约 90 KB），所以走独立的小资产，不必重打 52 MB 的 engine.bin。
     */
    private fun ensureNativeAddon() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过原生插件安装")
            return
        }

        val target = File(nodeModules, ADDON_PACKAGE)
        val binary = File(target, "prebuilt/android-arm64-napi-v9.node")

        // 已装好就跳过。插件与 Node 大版本解耦（走 N-API），无需按 node 版本重装。
        if (binary.isFile && binary.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(ADDON_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装原生插件 $ADDON_PACKAGE (${binary.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就不阻断启动：让引擎自己报出更具体的加载失败原因
            Log.w(TAG, "安装原生插件失败: ${t.message}")
        }
    }

    /**
     * 安装 system 原生插件，并放行 android 平台（幂等）。
     *
     * 会话落盘要拿一把内核级的排他锁：`dsh-session-persistence-jsonl` 的
     * `SessionWriteLease.acquire()` 调 `@deepseek-ai/node-addon-system/flock` 的
     * `tryLockExclusive(fd)`，拿不到锁就抛 `SessionAlreadyOwnedError`，表现是每轮
     * 回复都以「本轮运行失败」告终。
     *
     * 该插件的 `lib/flock.js` 只白名单了 linux/darwin：
     *   `if (platform !== 'linux' && platform !== 'darwin') throw ...`
     * 而 Android 上 `process.platform === 'android'`，于是直接抛
     * `ERR_FLOCK_UNSUPPORTED_PLATFORM: flock is not supported on android-arm64`。
     * 官方也只发布 darwin/linux 变体，没有 android 平台包。
     *
     * 但 bionic 的 `sys/file.h` 里就有 `int flock(int, int)`，Android 是支持
     * flock(2) 的。所以这里做两件事：
     *   ① 用 NDK 编译一份 android-arm64 的 `bin/system.node`（源码就是官方
     *      `src/flock.c`，未做任何改动），按官方平台包结构装进
     *      `node_modules/@deepseek-ai/node-addon-system-android-arm64`；
     *   ② 把 `flock.js` 的平台白名单补上 `android`。
     *
     * ② 之所以改运行时文件而不是重打 engine.bin：引擎资产每次升级都会整体覆盖，
     * 放在这里可以跟着资产版本自动重打（与 [ensureWebCompat] 同样的理由）。
     *
     * 注意 flock.js 里 `filename` 只在 `platform === 'linux'` 时才会被改写为
     * `glibc/system.node` 或 `musl/system.node`；android 不满足该条件，保持
     * `system.node`，所以解析出的路径正是 `bin/system.node` —— 与本包结构一致，
     * 无需额外适配。
     */
    private fun ensureSystemAddon() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 system 插件安装")
            return
        }

        val target = File(nodeModules, SYSTEM_ADDON_PACKAGE)
        val binary = File(target, "bin/system.node")

        // 已装好就跳过。走 N-API，与 Node 大版本解耦。
        if (!(binary.isFile && binary.length() > 0)) {
            try {
                java.util.zip.ZipInputStream(context.assets.open(SYSTEM_ADDON_ASSET).buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val out = File(target, entry.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                Log.i(TAG, "已安装 system 插件 $SYSTEM_ADDON_PACKAGE (${binary.length()} 字节)")
            } catch (t: Throwable) {
                // 装不上就不阻断启动：让引擎自己报出更具体的加载失败原因
                Log.w(TAG, "安装 system 插件失败: ${t.message}")
            }
        }

        patchFlockPlatform()
    }

    /**
     * 安装 android 版 ripgrep（幂等）。
     *
     * `grep` / `glob` 两个工具都由 `dsh-tool-fs-search` 调 `@vscode/ripgrep`
     * 解析出的二进制来跑：
     *
     * ```
     * const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;  // android-arm64
     * resolved = require.resolve(`${platformPkg}/bin/${binaryName}`);     // 抛错
     * ```
     *
     * 官方的 `optionalDependencies` 只列了 darwin/linux/win32 的 11 个变体，
     * **没有 android**。于是解析抛错，工具层把它包成
     * `Error: glob could not start its search command (ripgrep launch failed)`，
     * 两个工具全废 —— AI 只能改用 `read` 硬啃整个文件，浪费大量上下文。
     *
     * 这里装一个 `@vscode/ripgrep-android-arm64` 补上该平台包。二进制取自
     * 官方的 linux-arm64 变体（`@vscode/ripgrep-linux-arm64` 1.18.0）：
     * 它是**完全静态**的 aarch64 可执行文件（`readelf -d` 无 NEEDED），
     * 不依赖 glibc，因此能直接在 Android 上跑（设备实测 `rg --version` 正常）。
     * 这与 `TermuxBootstrap` 里 termux-exec 那套「静态二进制跨 libc」是同一道理。
     *
     * 无需改 `@vscode/ripgrep` 自身的代码：它按 `process.platform`/`process.arch`
     * 拼包名，装上 `android-arm64` 目录即可命中。也无需改任何版本戳 ——
     * 判定条件是「二进制存在且非空」。
     */
    private fun ensureRipgrep() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 ripgrep 安装")
            return
        }

        val target = File(nodeModules, RIPGREP_PACKAGE)
        val binary = File(target, "bin/rg")

        if (binary.isFile && binary.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(RIPGREP_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            // 从 APK 资产解出来的文件默认没有执行位，必须显式补上
            binary.setExecutable(true, false)
            Log.i(TAG, "已安装 ripgrep $RIPGREP_PACKAGE (${binary.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：grep/glob 会各自报 SEARCH_FAILED，其余工具照常
            Log.w(TAG, "安装 ripgrep 失败: ${t.message}")
        }
    }

    /**
     * 安装 sharp 的 WebAssembly 变体（幂等）。
     *
     * **症状**：图片附件上传成功后点发送，提示 `prompt rejected (session/agent-busy)`。
     * 那个 `agent-busy` 是**兜底错误码**（`dsh-api-session-controller` 的
     * `admit()` 把非 RemoteError 一律包成它），真因藏在 `details.reason` 里：
     * `Could not load the "sharp" module using the android-arm64 runtime`。
     *
     * **根因**：`dsh-attachment-local` 用 sharp 做图片的元数据读取与缩放/重编码，
     * 而 sharp 的预编译二进制只覆盖 darwin/linux/linuxmusl/win32/freebsd，
     * **没有 android**。`sharp/dist/sharp.cjs` 按 `runtimePlatformArch()` 拼包名
     * （`${process.platform}${libc}-${process.arch}` = `android-arm64`）走 switch，
     * 落不进任何 case。
     *
     * **做法**：装 sharp 官方的 **wasm32 变体**。它正是 sharp 在
     * 「无预编译二进制」平台上的官方兜底 —— `sharp.cjs` 的 switch 走完之后，
     * 还有一条无条件的 `require("@img/sharp-wasm32/sharp.node")`（第 102-108 行），
     * 只要该包存在就能加载，**不需要改 sharp 自身一行代码**。
     * 它依赖 `@emnapi/runtime`（N-API 的 wasm 实现），故一并安装。
     *
     * 真机实测（`create` 1200×800 RGBA → metadata / resize / png+jpeg+webp 编码 /
     * 缩略图裁剪）全部通过，`sharp.versions` = 0.35.5 / libvips 8.18.7。
     *
     * 无需改版本戳 —— 与 ripgrep 同理，判定条件是「wasm 文件存在且非空」。
     */
    private fun ensureSharp() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 sharp 安装")
            return
        }

        val wasm = File(nodeModules, SHARP_WASM_PACKAGE).let { dir ->
            File(dir, "lib/sharp-wasm32-${SHARP_VERSION}.node.wasm")
        }
        if (wasm.isFile && wasm.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(SHARP_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(nodeModules, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装 sharp wasm 变体 ($SHARP_WASM_PACKAGE, ${wasm.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：仅图片附件会发送失败，其余功能照常
            Log.w(TAG, "安装 sharp wasm 变体失败: ${t.message}")
        }
    }

    /**
     * 安装 node-pty 的 android-arm64 原生绑定（幂等）。
     *
     * **症状**：侧栏终端面板打不开，显示
     * `终端错误：subprocess-local: terminal inspection is unsupported on platform android`
     * （那是另一个补丁负责的，见 [patchProcessInspector]；此处解决的是它背后
     * 真正缺的东西）。
     *
     * **根因**：`dsh-subprocess-local` 的终端后端走 `node-pty`，而 node-pty 的
     * `prebuilds/` 只有 `darwin-arm64` / `darwin-x64` / `linux-arm64` / `linux-x64` /
     * `win32-*`，**没有 android**。它的 `lib/utils.js` 按
     * `prebuilds/${process.platform}-${process.arch}` 拼目录名，所以在 Android 上
     * 会去找 `prebuilds/android-arm64/pty.node`，找不到就
     * `Failed to load native module: pty.node`。
     *
     * **做法**：用 NDK 从 node-pty 自带的 `src/unix/pty.cc` 直接编一份
     * android-arm64 的 `pty.node`，装到 `prebuilds/android-arm64/`。
     * 上游源码**无需任何改动** —— 它本来就按平台拼目录名。
     *
     * ⚠️ 必须 **`-static-libstdc++`**（静态链 libc++）。默认动态链会引入
     * `libc++_shared.so`，而 Termux 环境里**没有**这个库，加载即失败。
     * 静态链之后 `readelf -d` 只剩 `libm/libdl/libc` 三个 bionic 系统库。
     *
     * ⚠️ PTY 本身在 Android 上是**可用**的，别被"平台不支持"误导：真机在
     * app 的 SELinux 域（`untrusted_app`）里实测 `openpty` / `forkpty` /
     * `open("/dev/ptmx")` 全部成功。
     *
     * 真机实测：绑定加载成功，`pty.spawn(bash)` 起得来，写入
     * `echo PTY_WORKS_$((6*7))` 能收到 `PTY_WORKS_42` 回显。
     *
     * 无需改版本戳 —— 判定条件是「绑定存在且非空」。
     */
    private fun ensureNodePty() {
        val nodeModules = File(engineDir, "node_modules")
        if (!nodeModules.isDirectory) {
            Log.w(TAG, "引擎尚未解包，跳过 node-pty 安装")
            return
        }

        val target = File(nodeModules, "node-pty/prebuilds/android-arm64")
        val binding = File(target, "pty.node")
        if (binding.isFile && binding.length() > 0) {
            return
        }

        try {
            java.util.zip.ZipInputStream(context.assets.open(NODE_PTY_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 条目形如 node-pty/prebuilds/android-arm64/pty.node，直接相对 node_modules 落盘
                        val out = File(nodeModules, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装 node-pty android-arm64 绑定 (${binding.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只有终端面板用不了，其余功能照常
            Log.w(TAG, "安装 node-pty 绑定失败: ${t.message}")
        }
    }

    /**
     * 给 `dsh-subprocess-local` 的进程检查器补 android 分支（幂等）。
     *
     * **症状**：终端面板报
     * `subprocess-local: terminal inspection is unsupported on platform android`。
     *
     * **根因**：`createProcessInspector()` 是 linux/darwin/win32 三选一
     * （`runner-launch-*.js` 的 `createProcessInspector`），android 落不进任何分支
     * 就 `throw`。而 `spawnTerminal()` 第一件事就是调它。
     *
     * **做法**：让 android 复用 **Linux 实现**。这是有依据的，不是将就：
     *
     * - `LinuxProcessInspector` 读的全是 `/proc`，Android 就是 Linux 内核，
     *   格式一致（真机实测 `parseProcStat` 能正常解析）。
     * - 它需要的 syscall 表里**本来就有 arm64**（`SYSCALLS.arm64`），
     *   安卓 arm64 正好命中。
     * - app 域下 `/proc` **目录可读**，只是别的 uid 的进程条目会被 SELinux 挡掉；
     *   而 `LinuxProcessInspector` 对每个读不到的条目都是「跳过」
     *   （`readLinuxStat` 吞异常返回 `undefined`），不会因此抛错。
     *   代价是**只能看见自己 uid 的进程** —— 而终端 shell 及其子进程
     *   恰好全是本 app 的 uid，正是需要观察的那部分。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchProcessInspector() {
        val dir = File(engineDir, "node_modules/@deepseek-ai/dsh-subprocess-local/lib")
        if (!dir.isDirectory) {
            Log.w(TAG, "未找到 subprocess-local，跳过进程检查器平台补丁")
            return
        }

        // 实现体在一个带 hash 的 chunk 里（runner-launch-<hash>.js），名字随版本变，
        // 所以按内容找而不是按文件名找。
        // ⚠️ 必须先判「已打过补丁」再判「原文在不在」：打完之后原文就不存在了，
        //    顺序反了会把已完成的状态误报成「未找到」。
        val candidates = dir.listFiles { f -> f.isFile && f.name.startsWith("runner-launch-") && f.name.endsWith(".js") }
            ?.toList().orEmpty()
        val sources = candidates.map { it to it.readText() }
        if (sources.any { (_, text) -> text.contains(INSPECTOR_ANDROID_ANCHOR) }) {
            return
        }
        val entry = sources.firstOrNull { (_, text) -> text.contains(INSPECTOR_GUARD) }?.first
        if (entry == null) {
            Log.w(TAG, "未找到进程检查器的平台判断，跳过补丁")
            return
        }

        try {
            val source = entry.readText()
            entry.writeText(source.replace(INSPECTOR_GUARD, INSPECTOR_ANDROID_ANCHOR))
            Log.i(TAG, "已放行 subprocess-local 的 android 进程检查器: ${entry.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "放行 subprocess-local 的 android 进程检查器失败: ${t.message}")
        }
    }

    /**
     * 把 `terminal-bash` 硬编码的 `/bin/bash` 换成 Termux 的 bash（幂等）。
     *
     * ## 症状
     *
     * `bash` 工具**每一条命令**都失败，报：
     *
     * ```
     * Error: PTY shell exited during startup
     * ```
     *
     * 而**侧栏终端却能正常打开** —— 「一个坏一个好」这个分裂现象是本节的要害，
     * 它排除了「PTY 不可用」这个直觉上最像的解释。
     *
     * ## 根因：`/bin/bash` 在 Android 上根本不存在
     *
     * 抛出点是 `dsh-terminal-bash/lib/index.js` 的 `initialize()`：
     *
     * ```js
     * if (result.waitReason === "session_exit") throw new Error("PTY shell exited during startup");
     * ```
     *
     * `session_exit` = shell 进程**起来后立刻退出**。而它 spawn 的是什么，
     * 由同文件的 `resolveConfig()` 决定：
     *
     * ```js
     * const DEFAULT_BASH_SHELL = "/bin/bash";
     * shellPath: config.shellPath?.length > 0 ? config.shellPath : DEFAULT_BASH_SHELL,
     * ```
     *
     * ⚠️ **引擎没有任何一层配置写过 `shellPath`**（全 engine + `$DSH_HOME` 搜过，
     * 只有类型定义和 GUI 侧栏终端的同名字段），于是必然落到这个硬编码值。
     *
     * 真机实测（uid 2000 与 app 域都测了，排除权限干扰）：
     *
     * | 命令 | 结果 |
     * |---|---|
     * | `ls -l /system/bin/bash` | `No such file or directory` |
     * | `/bin/bash -c "echo OK"` | rc=**127** `inaccessible or not found` |
     * | `/bin/sh -c "echo SH_OK"` | `SH_OK`，rc=0 |
     * | `$PREFIX/bin/bash -c ...` | `5.3.9(1)-release`，rc=0 |
     *
     * `/bin` 在 Android 上是 `-> /system/bin`，那里只有 `sh`（mksh），**没有 bash**。
     * 这与权限无关 —— 把「特殊权限」全开也变不出这个文件来。
     *
     * ⚠️ **`terminal-bash` 不做 PATH 查找**：`spawn()` 直接用 `argv[0]`，
     * 不调 `resolveExecutable`。所以哪怕引擎的 `PATH` 第一项就是 `$PREFIX/bin`
     * （真机确认），它也找不到 bash。这是「为什么不靠 PATH 修」的答案。
     *
     * ## 为什么侧栏终端不受影响
     *
     * 它走的是**另一条路径**（`dsh-api-terminal-controller` 的 `resolveShell()`）：
     *
     * ```js
     * shell = profile(environment.defaultShell ?? (platform === "windows" ? "cmd.exe" : "/bin/sh"));
     * ```
     *
     * 回落的是 `/bin/sh` —— 那个**真实存在**（引擎环境里没有 `SHELL` 变量，
     * 真机确认，所以走的就是这个兜底值）。同一个 App 里两条路径一个默认
     * `/bin/sh`、一个默认 `/bin/bash`，后者在 Android 上必挂。
     *
     * ## 做法
     *
     * 换成 `$PREFIX/bin/bash`（Termux 的 bash 5.3.9）。选它而不是 `/bin/sh`：
     * `terminal-bash` 整个包是按 bash 语义写的（`--noprofile --norc -i`、
     * `stty -echo`、提示符契约），而 `--noprofile` 这种长选项 mksh 不认。
     *
     * ⚠️ 已用真机 PTY 实测（同一份 node-pty 绑定、同款参数、带 link 转译层）：
     * 启动即出提示符 `bash-5.3$`，写入 `echo READY_$((6*7))` 回显 `READY_42`，
     * 进程存活（`exited=null`）。不是推测。
     *
     * 用 `process.env.PREFIX` 而不是硬编码绝对路径：`PREFIX` 由 [start] 显式传给
     * 引擎（真机确认 `PREFIX=/data/user/0/com.dsh.android/files/termux/usr`），
     * 这样不依赖包名或目录布局。取不到时回落 `/bin/sh`（一定存在）—— 虽然会失去
     * bash 语义，也好过必定 rc=127 的 `/bin/bash`。
     *
     * ⚠️ 全 engine 只有**这一处**硬编码 `"/bin/bash"`（全盘搜过），所以改这一处
     * 就覆盖了所有 preset —— 包括用户当前在用的 `minimal`（只有它的
     * `persistent-shell` group 挂 `terminal-bash`）。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchTerminalShell() {
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-terminal-bash/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "未找到 terminal-bash，跳过默认 shell 补丁: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            // ⚠️ 必须先判「已打过补丁」再判「原文在不在」：打完之后原文就不存在了，
            //    顺序反了会把已完成的状态误报成「未找到」。
            if (source.contains(TERMINAL_SHELL_ANCHOR)) {
                return
            }
            if (!source.contains(TERMINAL_SHELL_GUARD)) {
                Log.w(TAG, "terminal-bash 的默认 shell 结构与预期不符，跳过补丁")
                return
            }
            entry.writeText(source.replace(TERMINAL_SHELL_GUARD, TERMINAL_SHELL_ANCHOR))
            Log.i(TAG, "已把 terminal-bash 的默认 shell 换成 ${'$'}PREFIX/bin/bash: ${entry.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "修正 terminal-bash 的默认 shell 失败: ${t.message}")
        }
    }

    /**
     * 撤回「把选择工作区对话框的起始目录改成 `/sdcard`」那次改动（幂等）。
     *
     * **症状**：点「选择工作区」后，对话框停在 `files/home`（app 私有目录），
     * 既看不到手机上的任何文件夹，也**走不上去**（面包屑起点就是那里），
     * 于是「选一个手机上的文件夹当工作区」这件事在 App 里根本做不到。
     *
     * **根因**：Android 上引擎把目录选择器判成 `browse` 后端
     * （`dsh-host-directory-picker-auto` 的判定表：非 darwin/win32、非带显示的
     * linux ⇒ `browse`，真机实测 boot 图里挂的正是
     * `@deepseek-ai/dsh-client-ui-directory-picker-browse`）。该后端的 `list()`
     * 在没给路径时用 `homedir()` 当起点：
     *
     * ```js
     * const home = homedir();          // → $HOME，本 App 里是 filesDir/home
     * const target = resolve(path ?? home);
     * ```
     *
     * 而本 App 把引擎的 `HOME` 设成 `filesDir/home`（Termux 习惯，见 [start]），
     * 那是应用私有目录 —— 手机存储根本不在它的祖先链上。
     *
     * **做法（曾经）**：把 android 下的默认起点换成 `/sdcard`。
     *
     * ⚠️ **这个做法已经被推翻，本函数现在负责把它撤回来。** 起点放在 `/sdcard`
     * 虽然能选手机上的文件夹，但用户**再也进不了 app 私有目录** —— 两棵树在
     * Android 上互不可达（见 [ensurePickerRoots] 的实测表），而私有目录正是
     * 默认工作区所在的地方。现在改为「起点留在 HOME + 在 HOME 下建两个入口
     * 链接」，两边的需求同时成立。
     *
     * ⚠️ **必须真的撤回，不能只改代码不还原**：引擎解包目录只在版本戳变化时
     * 才重解包（[ensureExtracted] + [stampFile]），已经打过补丁的安装里那行
     * 仍然是 `/sdcard`，改 `assetStamp` 会让所有用户白重解包 100+ MB。
     *
     * ⚠️ 幂等判据是「打过补丁的文本在不在」：撤回之后它就不存在了，第二次调用
     * 直接返回。官方若改了这一行，两个锚点都不匹配 ⇒ 跳过并打日志，不会让
     * 启动失败（`home` 字段本来就该是 `homedir()`，不改才是对的）。
     */
    private fun restoreDirectoryPickerStart() {
        val entry = File(
            engineDir,
            "node_modules/@deepseek-ai/dsh-host-directory-picker-browse/lib/index.js"
        )
        if (!entry.isFile) {
            Log.w(TAG, "未找到目录选择器后端，跳过起始目录还原: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            if (!source.contains(PICKER_START_ANCHOR)) {
                return
            }
            entry.writeText(source.replace(PICKER_START_ANCHOR, PICKER_START_GUARD))
            Log.i(TAG, "已还原目录选择器的起始目录（改由主目录下的入口链接提供）")
        } catch (t: Throwable) {
            Log.w(TAG, "还原目录选择器起始目录失败: ${t.message}")
        }
    }

    /**
     * 在引擎 HOME 下建两个「选择工作区」的入口链接（幂等）。
     *
     * **为什么需要**：目录选择器一次只在**一棵**目录树里走，而 Android 上
     * 手机存储与 app 私有目录**互不可达** —— app 域连 `readdir("/")`、
     * `readdir("/data")` 都是 EACCES（实测表见 [patchAttachmentDurability]），
     * 所以起点无论放在哪一边，另一边都永远进不去。
     *
     * **做法**：起点留在 HOME（客户端 `displayCrumbs()` 会把 HOME 折叠成一项
     * 「主目录」），在 HOME 下建两个符号链接当入口：
     *
     * ```
     * filesDir/home/手机存储  → /sdcard      选手机上的任意文件夹
     * filesDir/home/应用目录  → filesDir     选/建 app 私有目录里的文件夹
     * ```
     *
     * 用户在对话框里看到的就是「主目录」下的两个入口，点进去各走各的树，
     * 点面包屑上的「主目录」就能回来 —— 有入口、也有向上导航。
     *
     * ⚠️ **链接只能建在 app 私有目录这一侧**：`/sdcard` 上 `symlink()` 返回
     * **EPERM**（FUSE/sdcardfs 不支持），所以不能反过来把私有目录的入口放进
     * 手机存储。真机实测：私有目录里建链接成功，`/sdcard` 上失败。
     *
     * ⚠️ **browse 后端会跟随符号链接**：`directoryRow()` 对 `isSymbolicLink()`
     * 的条目补一次 `stat()`，是目录就照常列为可进入的文件夹。真机实测两个链接
     * 都 `enterable=true`，且经链接列目录（145 项）、建文件夹、写文件全部成功。
     *
     * ⚠️ 读得到 `/sdcard` 靠的是 manifest 里已申请的 `READ_EXTERNAL_STORAGE` /
     * `WRITE_EXTERNAL_STORAGE`（targetSdk 28，legacy 存储模型）。用户拒绝该权限
     * 时「手机存储」那一行 `stat()` 失败 ⇒ `directoryRow()` 返回 null ⇒
     * **该行不显示**（官方逻辑，不会崩），只剩「应用目录」可用。
     *
     * ⚠️ 建链接失败只打日志，不让启动失败。
     */
    private fun ensurePickerRoots() {
        val home = homeDir
        if (!home.isDirectory) {
            Log.w(TAG, "HOME 不存在，跳过工作区入口链接: ${home.absolutePath}")
            return
        }

        // 名字会显示在对话框里、也会出现在所选工作区的路径中，用一眼能懂的词。
        val roots = listOf("手机存储" to File("/sdcard"), "应用目录" to context.filesDir)
        for ((name, target) in roots) {
            val link = File(home, name)
            try {
                val path = link.toPath()
                // ⚠️ 用 isSymbolicLink 而不是 exists()：断链时 exists() 返回 false，
                // 会让我们去 createSymbolicLink 一个已经存在的链接名（EEXIST）。
                if (Files.isSymbolicLink(path)) {
                    if (Files.readSymbolicLink(path).toString() == target.absolutePath) {
                        continue // 已就位
                    }
                    Files.delete(path) // 指向变了（换过包名/清过数据），重建
                } else if (link.exists()) {
                    Log.w(TAG, "工作区入口被同名文件占位，跳过: ${link.absolutePath}")
                    continue
                }
                Files.createSymbolicLink(path, target.toPath())
                Log.i(TAG, "已建工作区入口: $name → ${target.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "建工作区入口失败（$name）: ${t.message}")
            }
        }
    }

    /**
     * 让 `dsh-attachment-local` 的目录 fsync 容忍「祖先目录打不开」（幂等）。
     *
     * **症状**：图片/文件附件**上传成功**（缩略图都在），但点发送报
     * `prompt rejected (session/agent-busy)`。`agent-busy` 又是兜底错误码，
     * `details.reason` 里的真因是：
     *
     * ```
     * Error: EACCES: permission denied, open '/data/user/0'
     * ```
     *
     * **根因**：该包发布附件时要证明「目录项已落盘」，于是从 `$DSH_HOME` **逐级向上**
     * fsync 每一层父目录（`syncDirectory()` = `open(dir, O_RDONLY)` + `handle.sync()`），
     * 而 `ensureDurableHome()` 把 boundary 定为 `parse(home).root` —— 也就是 `/`，
     * 于是这趟遍历一定会走到应用目录之外。
     *
     * 但 **Android 的 SELinux 不允许应用 open 这些祖先目录**（真机实测，连
     * root 的 `/` 也是 EACCES，不是权限位的问题）：
     *
     * | 目录 | 模式 | `open(O_RDONLY)` |
     * |---|---|---|
     * | `/` | 755 | **EACCES** |
     * | `/data` | 771 | **EACCES** |
     * | `/data/user/0` | 771 | **EACCES** |
     * | `/data/data` | 771 | **EACCES** |
     * | `/data/data/com.dsh.android` | 700 | OK |
     *
     * 于是走到 `/data/user/0` 就抛 EACCES，整个附件发布失败。
     *
     * **做法**：只把「open 目录被拒（EACCES/EPERM）」这一种失败改成**跳过该层**
     * （`return`），其余 errno 照旧抛出。遍历本身会继续向上，最终自然收敛。
     *
     * ⚠️ **丢掉的只是「祖先目录项崩溃持久性」这一项加固，不是正确性** ——
     * 附件字节本身的 fsync（临时文件 `handle.sync()`）照旧执行，应用自己目录
     * （`$DSH_HOME` 及其以下，700、可 open）的 fsync 也照旧执行。
     * 而应用目录之外的目录项在 Android 上**本来就不可能**被 fsync，
     * 这不是取舍，是平台事实。
     *
     * ⚠️ 桌面端不受影响：`/`、`/home` 在 Linux 上都是可 open 的 755，
     * 这条 catch 分支根本不会触发。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchAttachmentDurability() {
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "未找到附件模块入口，跳过目录 fsync 容错: ${entry.absolutePath}")
            return
        }

        try {
            var source = entry.readText()
            var changed = false

            // ① open 的 EACCES/EPERM 兜底（SELinux 拒绝祖先目录 open）。
            if (!source.contains(ATTACH_SYNC_ANCHOR)) {
                if (source.contains(ATTACH_SYNC_GUARD)) {
                    source = source.replace(ATTACH_SYNC_GUARD, ATTACH_SYNC_ANCHOR)
                    changed = true
                } else {
                    Log.w(TAG, "附件模块的目录 open 结构与预期不符，跳过该处容错")
                }
            }

            // ② **真正的根因**：只读分区上目录 fsync 返回 EINVAL。
            //    原版 `sync()` 只有 try/finally、没有 catch，EINVAL 直接穿透，
            //    整条附件落盘失败 → 用户看到「会话忙」，且**只有发图片会中招**
            //    （纯文字不走附件落盘）。
            if (!source.contains(ATTACH_FSYNC_ANCHOR)) {
                if (source.contains(ATTACH_FSYNC_GUARD)) {
                    source = source.replace(ATTACH_FSYNC_GUARD, ATTACH_FSYNC_ANCHOR)
                    changed = true
                } else {
                    Log.w(TAG, "附件模块的目录 fsync 结构与预期不符，跳过该处容错")
                }
            }

            if (!changed) return
            entry.writeText(source)
            Log.i(TAG, "已放行附件模块的目录 fsync（open+sync）: dsh-attachment-local")
        } catch (t: Throwable) {
            Log.w(TAG, "放行附件模块的祖先目录 fsync 失败: ${t.message}")
        }
    }

    /**
     * 放宽「发图格式白名单」—— 原版只认 4 个精确的 MIME 字符串。
     *
     * **症状**：新会话里粘贴/选择图片，直接弹「仅支持 PNG、JPG、WebP、GIF 格式的图片」，
     * 图根本没进输入框。而同一张图在已有会话里能发。
     *
     * **根因**：`dsh-client-ui-conversation` 的 `isImageMediaType()` 是**精确字符串比较**：
     *
     * ```js
     * return value === "image/png" || value === "image/jpeg"
     *     || value === "image/webp" || value === "image/gif";
     * ```
     *
     * 于是下面这些**明明能显示的图片**全被判成非法：
     *
     * | 场景 | `file.type` | 结果 |
     * |---|---|---|
     * | 后缀是 `.jpg`（部分系统/相册） | `image/jpg` | ❌ 被拒（不是 `jpeg`） |
     * | iPhone 拍照 | `image/heic` / `image/heif` | ❌ 被拒 |
     * | 部分安卓相册 | `image/bmp` / `image/avif` | ❌ 被拒 |
     * | 从剪贴板粘贴 | 可能是空串 | ❌ 被拒 |
     *
     * 触发点还叠了 `continuable`（新开会话）这个条件，所以表现成
     * 「新会话一粘图就报错，老会话没事」。
     *
     * **做法**：把精确比较换成前缀 + 别名判断 —— 只要 `image/` 开头就放行，
     * 再补上 `image/jpg` 这个常见别名。
     *
     * ⚠️ **只放宽「能不能选」，不放宽「能不能解码」**：真正的解码在下游
     * （`dsh-attachment-local` 用 sharp），不支持的格式那里会给出明确错误，
     * 比在入口处一刀切拦住友好得多 —— 用户至少能看到「这张图解码失败」，
     * 而不是莫名其妙的格式白名单。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败）。
     */
    private fun patchImageMediaType() {
        val entry = File(
            engineDir,
            "node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js",
        )
        if (!entry.isFile) {
            Log.w(TAG, "未找到会话 UI 入口，跳过发图格式补丁: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            // 已打过补丁：补丁体里那句 startsWith 就是标记（原版没有它）。
            if (source.contains("return value.startsWith(\"image/\")")) {
                return
            }
            /*
             * ⚠️ **按行处理，不按固定缩进匹配**（2026-10-27 真机取证）。
             *
             * 原来这里是 `source.contains(IMG_TYPE_GUARD)` 再 `replace`，
             * 而 GUARD 里写死了 3 个制表符 —— 真机上该行是 **6 个空格**缩进，
             * 于是恒不匹配、补丁从未生效（每次启动都记一条"结构与预期不符"）。
             *
             * 现在只找**包含语句主体**的那一行，把它的行首空白取出来，
             * 用同一份空白去缩进多行替换体。这样缩进换了也不影响。
             */
            val lines = source.split("\n").toMutableList()
            val idx = lines.indexOfFirst { it.trimEnd().endsWith(IMG_TYPE_GUARD.trimEnd()) }
            if (idx < 0) {
                Log.w(
                    TAG,
                    "发图格式判断结构与预期不符，跳过补丁（找不到以 " +
                        "`${IMG_TYPE_GUARD.take(40)}…` 结尾的行）",
                )
                return
            }
            // 取那一行的行首空白（可能为空）。
            val indent = lines[idx].takeWhile { it == ' ' || it == '\t' }
            val replacement = IMG_TYPE_ANCHOR.split("\n").joinToString("\n") { indent + it }
            lines[idx] = replacement
            entry.writeText(lines.joinToString("\n"))
            Log.i(
                TAG,
                "已放宽发图格式白名单: dsh-client-ui-conversation（行 ${idx + 1}，缩进 ${indent.length} 字符）",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "放宽发图格式白名单失败: ${t.message}")
        }
    }

    /**
     * 从引擎打印的 URL 里取出端口号，取不到给空串。
     *
     * ⚠️ **不能写成 `url.substringAfter(":").substringBefore("/")`**（踩过，真机全中）：
     *   `substringAfter(":")` 命中的是 `http:` 里那个冒号，于是对
     *   `http://127.0.0.1:3080/?token=...` 得到 `//127.0.0.1:3080/?token=...`，
     *   再 `substringBefore("/")` 就是**空串**。实测 53 份诊断包的时间线里
     *   `engine.ready` 的 `port` 字段**无一例外都是空**，等于白记了一个字段。
     *
     *   后果很具体：「端口撞车」这条分支（3080 空闲就沿用默认值 / 被占用就改由
     *   内核分配空闲端口）在时间线上无法区分，只能去翻 app.log 正文。
     *   而端口撞车恰恰是「引擎起不来」的一大类原因（EADDRINUSE → webserver
     *   激活失败 → 必需插件缺失 → 引擎退出）。
     *
     * 用正则抓 `:<端口>` 而不是按 `:` 切分，天然躲开协议名的冒号。
     */
    private fun portOf(url: String): String =
        Regex(""":(\d{2,5})(?:[/?]|$)""").find(url)?.groupValues?.get(1) ?: ""

    /**
     * 修掉 `$DSH_HOME/.credentials.yaml` 过宽的权限位（`0600` 之外的组/其他位）。
     *
     * **症状（致命）**：引擎**起不来**，界面上是「引擎启动失败（引擎进程已退出）」，
     * 而真正的报错在 `engine.log` 里：
     *
     * ```
     * dsh: startup failed: 1 required plugin did not activate
     * Failed plugins (1):
     *   credentials
     *     Package: @deepseek-ai/dsh-credentials-local
     *     Error: credentials-local: <$DSH_HOME>/.credentials.yaml is readable
     *            beyond its owner (mode 660); run "chmod 600 …" before starting again
     * ```
     *
     * 而且**不是只坏一个插件** —— `credentials` 是 `connection` / `authorization` /
     * `deepseek-account` / `session-controller` / `file-upload` … 共 **9 个**插件的
     * 前置依赖，它一挂全部 `pending`，引擎判定「必需插件没起来」直接退出。
     * 用户看到的是整个 App 不可用，与「凭据文件权限」毫无表面关联。
     *
     * **根因**：`dsh-credentials-local` 的 `assertOwnerOnly()` 是**纯校验、不自愈** ——
     * 读文件前先 `stat`，只要 `mode & 0o077` 非零就 `throw`，从不尝试 `chmod`。
     * 而引擎自己写入走的是 `writeFileAtomic(..., { mode: 0o600 })`（临时文件
     * `flag: 'wx'` 建好后 rename），写出来的**必然**是 0600。
     *
     * 所以 660 一定是**引擎之外**写成的，实测来自这几类操作（都会落到这个路径）：
     *  · 用户/工具 `cp -p`、`unzip`、`tar -x` 解压出带组写位的副本；
     *  · `adb push`、文件管理器、第三方终端 App 代写；
     *  · 老版本或手工 `chmod` 留下的残留。
     * 这些在**换手机/迁移 `$DSH_HOME`** 之后特别容易命中。
     *
     * ⚠️ **为什么必须由 App 自愈，而不是让用户手敲 `chmod 600`**：
     *  ① 报错文案里那条命令，普通用户根本没有终端可用（这正是「Termux 内置
     *     在 App 里」的代价）—— 而且 `$DSH_HOME` 在 app 私有目录，非 root 也进不去；
     *  ② 这是**永久性砖**：引擎每次启动都在同一个文件上失败，不自愈就永远进不去，
     *     唯一的出路变成「卸载重装」，而卸载会清掉整个 `$DSH_HOME`
     *     （会话/凭据/工作区全没）—— 代价远大于修一个权限位。
     *
     * ⚠️ **只收窄、不开放**：目标就是 0600，绝不把权限改宽，也不碰文件内容。
     *  这个文件里是 API 凭据，收窄权限本身就是它的安全前提。
     *
     * ⚠️ 幂等：已经是 0600 时不写盘、不打日志（开机会走一次，不能刷屏）。
     *
     * ⚠️ 找不到文件 = 正常（尚未存过凭据），静默返回。
     *
     * 定位见 `dsh-credentials-local/lib/index.js` 的 `assertOwnerOnly()`（L92-106）
     * 与 `GROUP_OTHER_BITS = 63`（L64）。
     */
    private fun ensureCredentialFileMode() {
        val file = File(dshHome, CREDENTIALS_FILE)
        if (!file.isFile) return
        try {
            val path = file.toPath()
            // ⚠️ 顺序要紧：**先读旧值、再无条件收窄**。
            //   反过来的话（先判断就 return），一旦 getPosixFilePermissions
            //   抛异常就会直接跳到 catch，结果是「本该修的没修」—— 这个函数
            //   就白写了。而 setPosixFilePermissions 本身幂等：已经是 0600 时
            //   只多一次 syscall，没有可见副作用。
            val before = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
            // 判据与引擎**逐位对齐**：引擎只拒绝「组/其他位非零」
            // （`assertOwnerOnly` 的 `mode & 0o077`）。所以 0700 这种带属主执行位
            // 但组/其他位全零的文件，引擎**本来就接受**，不该去动它 ——
            // 否则我们会打出「过宽，会让引擎启动失败」这句不成立的日志。
            // 读取失败（before == null）时保守处理：照修。
            if (before != null && before.none { it in GROUP_OTHER_BITS }) return
            Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
            Log.w(
                TAG,
                "已收窄凭据文件权限为 0600（此前 " +
                    (before?.let { posixModeText(it) } ?: "无法读取") +
                    " 的组/其他位非零，会让引擎直接启动失败）: ${file.absolutePath}"
            )
        } catch (t: Throwable) {
            // 修不了也让启动继续：引擎那边若仍拒绝，engine.log 里会有它自己的原文报错
            Log.w(TAG, "收窄凭据文件权限失败: ${t.message}")
        }
    }

    /** 把权限集合渲染成 `rw-rw----` 这种九字符形式，只用于日志。 */
    private fun posixModeText(perms: Set<PosixFilePermission>): String {
        fun triad(
            read: PosixFilePermission,
            write: PosixFilePermission,
            execute: PosixFilePermission,
        ): String =
            (if (perms.contains(read)) "r" else "-") +
                (if (perms.contains(write)) "w" else "-") +
                (if (perms.contains(execute)) "x" else "-")

        return triad(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ) + triad(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
        ) + triad(
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }

    /**
     * 让 DeepSeek Messages 适配器把 assistant 的 `tool_use` 块**前置**（幂等）。
     *
     * **症状（严重，会话级永久损坏）**：某一轮因为 API 错误结束（实测是第三方
     * 路由的 `429 RATE_LIMIT`）之后，**该会话此后的每一条消息都失败**，连
     * 「只回复两个字：通了」这种最简消息也一样：
     *
     * ```
     * 本轮运行失败 messages.7.1: `tool_use` ids were found without
     * `tool_result` blocks immediately after: call_f0dd74e854fd4cb1a0a6ee0a.
     * (INVALID_REQUEST)
     * ```
     *
     * 新建会话不受影响，说明是**历史消息**的问题，不是当前请求的问题。
     *
     * **根因**：`serialize()` 把 assistant 消息的 content 块**原样保序**映射成
     * `tool_use` / `text` / `thinking`，而持久化历史里 assistant 的实际形状是
     * **交错的**（模型先说话、再调工具、再说一句、再调工具）：
     *
     * ```
     * ["tool_use:call_e6d1…", "text(3)", "tool_use:call_a47f…", "tool_use:call_2399…"]
     * ```
     *
     * 而 DeepSeek Messages API 要求 assistant 的 `tool_use` 块**必须连续**，
     * 且紧后必须是携带全部 `tool_result` 的 user 消息。夹在中间的 `text`
     * 直接违约 → 整段历史从此再也发不出去。
     *
     * ⚠️ 引擎自己的自检（`serialize()` 末尾的 `pending` 循环）**只查 id 配对、
     * 不查块顺序**，所以这个历史在本地自检里是「合法」的，只有真发给 API 才炸。
     *
     * ⚠️ 触发它的**不是**用户主动点「停止生成」（实测那条路径正常），而是
     * **API 侧的错误中断** —— 那一轮 assistant 消息已经带上了 `tool_use`，
     * 但永远等不到 `tool_result`，历史就此定形。
     *
     * **做法**：只在 `assistant()` 的 `map` 之后补一次稳定排序 —— 把
     * `tool_use` 抽到最前、其余（`text` / `thinking`）保持原有相对顺序跟在后面。
     *
     * ⚠️ **只动 wire 请求体，不动持久化历史**：`assistant()` 返回的是新数组，
     * `message.content` 本身不被改写，会话日志与 UI 显示都不受影响。
     *
     * ⚠️ **`replay` 的 index 对齐不能破坏**：`readReplay()` 返回的签名数组是
     * **按下标**与 `message.content` 对齐的，而 `assistant()` 在 `map` 里用
     * `replay[index]` 取签名。所以重排必须发生在**映射之后**（对已经带上
     * `signature` 的 wire 块重排），不能在 `map` 之前重排 `message.content`，
     * 否则签名会串位。本补丁插在 `return ... .map(...)` 的外层，正是这个位置。
     *
     * ⚠️ 引擎自己**已经**对 user 消息做了同样的重排（`[...results, ...其余]`），
     * 只是漏了 assistant 这一侧 —— 这个补丁补的就是那处遗漏。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchLlmDeepseekToolOrder() {
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "未找到 DeepSeek Messages 适配器，跳过 tool_use 顺序补丁: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            if (source.contains(TOOL_ORDER_ANCHOR_HEAD)) {
                return
            }
            if (!source.contains(TOOL_ORDER_GUARD_HEAD) || !source.contains(TOOL_ORDER_GUARD_TAIL)) {
                Log.w(TAG, "DeepSeek Messages 适配器的 assistant 映射结构与预期不符，跳过 tool_use 顺序补丁")
                return
            }
            entry.writeText(
                source
                    .replace(TOOL_ORDER_GUARD_HEAD, TOOL_ORDER_ANCHOR_HEAD)
                    .replace(TOOL_ORDER_GUARD_TAIL, TOOL_ORDER_ANCHOR_TAIL),
            )
            Log.i(TAG, "已修正 DeepSeek Messages 的 tool_use 块顺序: dsh-llm-deepseek")
        } catch (t: Throwable) {
            Log.w(TAG, "修正 DeepSeek Messages 的 tool_use 块顺序失败: ${t.message}")
        }
    }

    /**
     * 修正 pi-ai 适配器在**第三方路由不返回 tool_call id** 时写出的空 tool-call id（幂等）。
     *
     * **症状（会话级永久损坏，比 ⑨.5 更狠）**：某一轮结束后，该会话从列表里消失，
     * `/export` 报 HTTP 500，**新建会话也跟着失败**：
     *
     * ```
     * format v4 tool/result at seq 19 requires toolCallId matching its tool source
     * tool call id requires a nonempty string
     * 新建会话失败: gateway/internal: failed to create session "session-7d4ab1ab-…":
     *   SessionQueryError: stored session … is corrupt:
     *   SessionFormatError: tool call id requires a nonempty string
     * ```
     *
     * **根因**：`toolcall_end` 分支拿 provider 给的 id 造 tool-call 块，**没有任何校验**：
     *
     * ```js
     * id: brandString(event.toolCall.id),   // brandString 是纯类型断言，return value，零校验
     * ```
     *
     * 对比 DeepSeek 官方适配器，那里有防线
     * （`if (!content.id || !content.name) return malformed("empty tool identity")`），
     * **pi-ai 这条路径漏了**。第三方路由（实测设备上的 `agnes` / `q`）在流式响应里
     * 不给 `toolCall.id` 时，`id` 就是 `""` 或 `undefined`。
     *
     * ⚠️ **`dsh-llm` 的收口兜不住它**（[patchLlmToolCallIdFallback] 是第二道防线）：
     * `assemble()` 里 `id: partial.toolCallId ?? brandString(...)` 的 `??`
     * **只兜 `null`/`undefined`，兜不住空串**；而且 pi-ai 会经 `block-end` 直接把
     * 整个 block 塞进 `partial.block`，`assemble()` 第一行
     * `if (partial.block) return partial.block;` 会**直接返回**，`??` 那行根本不执行。
     *
     * ⚠️ **为什么这会造成整个会话不可读**：`tool/result` 行落盘后，读取侧
     * `dsh-session-format-v3-to-v4` 的 `assertV4ToolResultMessage()` 要求
     * `toolCallId` 是非空字符串且等于 `source.callId`；而
     * `dsh-session-persistence-jsonl` 的 `consumeEventLine()` 对格式错误
     * **只重抛、不恢复**（只有 JSON 解析失败才走 `issue` 恢复路径），
     * `finish()` 还会再跑一遍 `assertReleasedV4Relationships()`。
     * 所以**一行坏数据就让整个会话加载失败** → 从列表消失 → 导出 500 → 新建会话被拖累。
     *
     * ⚠️ 空 id 能一路落盘是因为 `snapshotJsonValue()` 只校验「可无损 JSON 序列化」，
     * 空串是合法 JSON；`undefined` 则会被 JSON 往返整个丢掉字段，两种都逃得过写入侧。
     *
     * ⚠️ 只改**流式事件的 id 兜底**，不改持久化历史、不改正常路径：
     * provider 给了真实 id 时行为完全不变。
     *
     * 找不到锚点就跳过（引擎升级后结构变了也不让启动失败，届时日志里有提示）。
     */
    private fun patchLlmPiAiToolCallId() {
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-llm-pi-ai/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "未找到 pi-ai 适配器，跳过空 tool-call id 补丁: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            if (source.contains(PI_AI_TOOLID_ANCHOR_START)) {
                return
            }
            if (!source.contains(PI_AI_TOOLID_GUARD_START) || !source.contains(PI_AI_TOOLID_GUARD_END)) {
                Log.w(TAG, "pi-ai 适配器的 toolcall 事件结构与预期不符，跳过空 tool-call id 补丁")
                return
            }
            entry.writeText(
                source
                    .replace(PI_AI_TOOLID_GUARD_START, PI_AI_TOOLID_ANCHOR_START)
                    .replace(PI_AI_TOOLID_GUARD_END, PI_AI_TOOLID_ANCHOR_END),
            )
            Log.i(TAG, "已修正 pi-ai 的空 tool-call id: dsh-llm-pi-ai")
        } catch (t: Throwable) {
            Log.w(TAG, "修正 pi-ai 的空 tool-call id 失败: ${t.message}")
        }
    }

    /**
     * 修正 `dsh-llm` 组装 tool-call 块时**兜不住空串**的 id 兜底（幂等）。
     *
     * 这是 [patchLlmPiAiToolCallId] 的**第二道防线**，覆盖 pi-ai 之外的 provider：
     * 任何走「只有 delta、没有 block-end」协议的适配器，其空 id 都会从这里出去。
     *
     * ```js
     * id: partial.toolCallId ?? brandString(`call-${index}`),
     * //                      ^^ 只兜 null/undefined，空串照样透出去
     * ```
     *
     * ⚠️ 这是**所有 LLM 适配器共用的收口**（`BlockAssembler`），所以只修 pi-ai
     * 不够 —— 别的 provider 出现同样形状时，坏行会从同一个地方产生。
     *
     * ⚠️ 走 `block-end` 的协议（pi-ai 就是）**不会**执行到这一行：
     * `assemble()` 第一行 `if (partial.block) return partial.block;` 直接返回了
     * block-end 带来的块。所以这两处补丁各管一段路径，都要在。
     *
     * 找不到锚点就跳过。
     */
    private fun patchLlmToolCallIdFallback() {
        val entry = File(engineDir, "node_modules/@deepseek-ai/dsh-llm/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "未找到 dsh-llm 组装器，跳过 tool-call id 兜底补丁: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            if (source.contains(LLM_TOOLID_ANCHOR)) {
                return
            }
            if (!source.contains(LLM_TOOLID_GUARD)) {
                Log.w(TAG, "dsh-llm 组装器的 tool-call 组装结构与预期不符，跳过 tool-call id 兜底补丁")
                return
            }
            entry.writeText(source.replace(LLM_TOOLID_GUARD, LLM_TOOLID_ANCHOR))
            Log.i(TAG, "已修正 dsh-llm 组装器的空 tool-call id 兜底: dsh-llm")
        } catch (t: Throwable) {
            Log.w(TAG, "修正 dsh-llm 组装器的空 tool-call id 兜底失败: ${t.message}")
        }
    }

    /**
     * 安装 pnpm 到 `$PREFIX/bin`（幂等）。
     *
     * `plugin_manager` 的 `install_bundle` 通过 spawn `pnpm add` 装插件，而
     * Termux 的 `$PREFIX/bin` 里只有 dpkg/apt 那一套，**没有 pnpm/npm/npx/corepack**。
     * 症状是 `spawn pnpm ENOENT`，插件装不上 —— 实测 AI 因此退回手改 profile：
     * 把包塞进 `profiles/web/node_modules/` 并直接编辑用户的 `cordis.patch.yml`。
     * 那是绕过包管理器，会留下 `.bak` 残file 且不受版本管理。
     *
     * **为什么是 pnpm 11 的纯 JS 版，而不是 `@pnpm/exe.android-arm64`**：
     * exe 版（12.x）是 Rust 重写的自包含二进制，跑得起来（`pnpm --version` 正常），
     * 但它在 Android 上**做不了任何写操作** —— Rust std 在编译期把 Android 的
     * `File::lock_shared()` 判为 unsupported，而 pnpm 的 store 操作锁走的正是它：
     *
     * ```
     * ERR_PNPM_STORE_DIR_ACQUIRE_OPERATION_LOCK
     *   ╰─▶ Failed to acquire the store operation lock at ".../all-stores.lock":
     *       lock_shared() not supported
     * ```
     *
     * 注意这**不是**内核不给锁：设备实测 `flock(fd, LOCK_SH)` 返回 0，`fcntl` 记录锁
     * 也正常。是 pnpm 那个二进制的动态符号表里**只有 `fcntl`、根本没有 `flock`**
     * （`readelf --dyn-syms` 可验），纯属编译期判定，配置绕不过去。
     *
     * pnpm 11 是最后一个**纯 JS** 主线（`dist/pnpm.mjs` 单文件 bundle，无 Rust），
     * 锁走 JS 实现，真机实测 `pnpm add`（含本地路径包、`nodeLinker: hoisted`
     * 的真实 profile 布局）全部成功。代价是体积从单文件 46 MB 变成 5.9 MB 的 zip
     * —— 反而更小了。
     *
     * 装法：整包解到 `$PREFIX/lib/pnpm`，再在 `$PREFIX/bin/pnpm` 放一个
     * `exec node <pkg>/bin/pnpm.mjs "$@"` 的启动脚本。**不能**直接把 `bin/pnpm.mjs`
     * 软链到 `bin/pnpm`：Android 的 SELinux 不给 app 私有目录建符号链接（与
     * `link` 同一类限制），而且 shebang 在 Termux 里也不可靠。
     *
     * 校验走 `PNPM_VERSION` 版本戳（与 `NODE_VERSION`、`MOBILE_UI_VERSION` 同一套
     * 模式）：把版本写进 marker 文件，不一致才重解。
     */
    private fun ensurePnpm() {
        val bin = File(bootstrap.prefixDir, "bin/pnpm")
        val stamp = File(bootstrap.prefixDir, PNPM_STAMP)

        if (bin.isFile && bin.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == PNPM_VERSION
        ) {
            return
        }

        try {
            val libDir = File(bootstrap.prefixDir, PNPM_DIR)
            libDir.deleteRecursively()
            libDir.mkdirs()

            var entryCount = 0
            java.util.zip.ZipInputStream(context.assets.open(PNPM_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(libDir, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        entryCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val entryJs = File(libDir, "bin/pnpm.mjs")
            if (!entryJs.isFile) {
                throw IllegalStateException("资产里没有 bin/pnpm.mjs")
            }

            // $PREFIX/bin/pnpm：node 是绝对路径，免得依赖 PATH
            bin.parentFile?.mkdirs()
            bin.writeText(
                "#!/system/bin/sh\n" +
                    "exec " + bootstrap.nodeBinary.absolutePath +
                    " " + entryJs.absolutePath + " \"$@\"\n"
            )
            bin.setReadable(true, false)
            bin.setExecutable(true, false)

            stamp.writeText(PNPM_VERSION)
            Log.i(TAG, "已安装 pnpm $PNPM_VERSION ($entryCount 个文件, ${bin.length()} 字节启动脚本)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：插件管理器的 install_bundle 会报 ENOENT
            Log.w(TAG, "安装 pnpm 失败: ${t.message}")
        }
    }

    /**
     * 解出 android 沙箱的两个组件（幂等），返回 runner 路径。
     *
     * 背景：DSH 的沙箱层按平台挑后端
     * （`dsh-sandbox-local` 的 `PLATFORM_CHAINS`：linux→bwrap/landlock、
     * darwin→seatbelt、win32→windows-acl），Android 落不进任何一条 ——
     * 设备内核 4.14 没有 Landlock（需 5.13+），没有 bwrap，`unshare -U` 因缺
     * CAP_SYS_ADMIN 而 EPERM。`confine()` 是 **fail-closed** 的，于是
     * `workspace-write` 下 bash 直接不可用：
     *
     * ```
     * sandbox mode "workspace-write" is requested but no sandbox backend is
     * usable on this host; refusing to run the command unconfined.
     * ```
     *
     * 但该 provider 留了操作员口子 `Config.runnerCommand`：非空时**不做平台
     * 探测**，直接把 runner 当成 bwrap 等价物并按 bwrap 方言拼参数
     * （`[...runnerCommand, ...bwrapProfileArgs(policy), "--", ...argv]`）。
     * 所以不必改引擎源码，只要提供一个听得懂那套参数的 runner。
     *
     * 两个组件（源码在 `app/src/main/jni/`）：
     *   · `boo-sandbox-run`   —— 解析 bwrap 方言，把 `--bind` 翻译成
     *     `BOO_SANDBOX_ALLOW`，再 exec 真实命令；
     *   · `libboosandbox.so`  —— LD_PRELOAD 写拦截器，在 libc 边界挡住
     *     allowlist 之外的写（解释器内部的写同样覆盖）。
     *
     * ⚠️ 与真 bwrap 的差异：`--unshare-pid` 无法实现（同样缺 CAP_SYS_ADMIN），
     * 所以 PID 命名空间没有隔离。**文件写边界是完整的**，那是本方案承诺的部分。
     * 详见 `boo-sandbox-run.c` 头部。
     */
    private fun ensureSandbox(): File? {
        return try {
            val runner = File(context.filesDir, SANDBOX_RUNNER_FILE)
            context.assets.open(SANDBOX_RUNNER_ASSET).use { input ->
                runner.outputStream().use { output -> input.copyTo(output) }
            }
            runner.setReadable(true, false)
            runner.setExecutable(true, false)

            val lib = File(context.filesDir, SANDBOX_LIB_FILE)
            context.assets.open(SANDBOX_LIB_ASSET).use { input ->
                lib.outputStream().use { output -> input.copyTo(output) }
            }
            lib.setReadable(true, false)

            Log.i(TAG, "已解出沙箱 runner ${runner.name} (${runner.length()} 字节) + ${lib.name} (${lib.length()} 字节)")
            runner
        } catch (t: Throwable) {
            Log.w(TAG, "解出沙箱组件失败: ${t.message}")
            null
        }
    }

    /**
     * 写沙箱 overlay，把 `sandbox` 行的 `runnerCommand` 指向上面的 runner（幂等）。
     *
     * 该 overlay 作为 `--patch` 传给引擎，在 profile 层之后应用，因此能覆盖
     * `dsh-base/cordis.patch.yml` 里的 `sandbox` 行（patch 是整行替换 config，
     * 不是合并）。
     *
     * `runnerFailureSignatures` **不能留空**。provider 构造函数里有一对硬校验：
     *
     * ```js
     * if (runner.length > 0 && runnerFailureSignatures.length === 0)
     *   throw new Error("sandbox-local: runnerCommand requires at least one runnerFailureSignatures entry");
     * ```
     *
     * 它映射到 `ConfinedArgv.runnerFailureRules[].fatalSignatures` —— 判定
     * 「runner 自己挂了、命令根本没跑起来」的 stderr 子串（与 bwrap 的 `"bwrap: "`、
     * landlock 的 `"landlock-launcher: "` 同构）。填错/留空的后果不是降级而是
     * **整个 sandbox 服务注册失败**：`4 entries did not activate`，然后
     * `bash-sandbox` → `permission` → `ptc-runtime` 全部 pending，
     * 表现为新建会话失败：
     *
     * ```
     * agent-preset/invalid: tool-bash: waiting for shell workflow-ptc:
     *   waiting for ptcRuntime tool-workflow: waiting for workflowEngine
     * ```
     *
     * `boo-sandbox-run.c` 的每条 fail-closed 路径都用 `boo-sandbox-run: ` 前缀
     * （与 bwrap 同名同格式），所以这里就填它。**改 runner 的输出前缀必须同步改这里。**
     *
     * 沙箱「拒绝」与「runner 失败」是两回事：前者靠 provider 内置的
     * `DENIAL_SIGNATURES.runnerCommand = ["read-only file system",
     * "permission denied"]` 识别，而 `libboosandbox.so` 的 deny 输出正好含
     * `permission denied`。
     *
     * 为什么不用 profile 自己的 `cordis.patch.yml`：那层归 DSH 初始化、也可能
     * 承载用户改动（本机上 AI 就往里插过自己的插件）。`--patch` 层完全由本 App
     * 拥有，且优先级更高。
     */
    private fun writeSandboxPatch(runner: File?): File? {
        if (runner == null) return null
        val file = File(dshHome, SANDBOX_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# Android 没有内核级沙箱后端（无 bwrap / Landlock 需 5.13+，设备为 4.14），")
            appendLine("# 故用自带 runner + LD_PRELOAD 写拦截器保住 workspace-write 的写边界。")
            appendLine("# 详见 app/src/main/jni/boo-sandbox-run.c。")
            appendLine("- id: sandbox")
            appendLine("  name: '@deepseek-ai/dsh-sandbox-local'")
            appendLine("  config:")
            appendLine("    runnerCommand:")
            appendLine("      - '${runner.absolutePath}'")
            appendLine("    runnerFailureSignatures:")
            appendLine("      - 'boo-sandbox-run: '")
        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入沙箱 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入沙箱 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 给 workspace controller 指定「文档目录」，让首次使用时能自动建出默认工作区。
     *
     * ## 为什么需要
     *
     * 全新安装（无工作区、无会话）时，前端会调 `workspaces.initializeDefault()`
     * 自动建一个默认工作区；失败就弹「无法创建默认工作区，请通过"选择工作区"选择文件夹」。
     *
     * host 侧 `@deepseek-ai/dsh-api-workspace-controller/lib/types/default-directory.js`
     * 的平台分支只认三平台：
     *
     * ```js
     * switch (platform) {
     *   case 'darwin': ... osascript ...
     *   case 'win32':  ... powershell ...
     *   case 'linux':  ... xdg-user-dir ...
     *   default: throw new Error(`system Documents directory is unavailable on ${platform}`);
     * }
     * ```
     *
     * Node 在 Android 上 `process.platform === 'android'`，直接落进 `default:` 抛错。
     * 这是本项目第 ⑫ 处「官方只认 linux/darwin/win32」的同款问题（与 ripgrep / sharp /
     * node-pty / flock 同类），**不是本 App 的 bug，但必须由本 App 补上**。
     *
     * ## 为什么用 `documentsDirectory` 而不是改引擎源码
     *
     * 上游 README 明确写了这个口子：「不具备该条件的 Host 必须配置 `documentsDirectory`
     * 或使用文件夹选择器」。它是 `Config` 里的 `z.string()`，非空时**跳过整个平台探测**
     * （`let directory = documentsDirectory; if (directory === undefined) { ...switch... }`），
     * 且构造时会 `validateDocumentsDirectory()` 要求绝对路径。所以只要给一个绝对路径，
     * Android 就完全绕开那个 switch。
     *
     * ## 为什么指向 app 私有目录下的 workspace
     *
     * 最终工作区是 `<documentsDirectory>/deepseek-harness/default-workspace`。
     * 用 `filesDir/workspace`（即 [workspaceDir]）而不是 `/sdcard`：
     *
     * - app 私有目录无需任何存储权限，Android 10+ 的分区存储下 `/sdcard` 反而更麻烦；
     * - 引擎进程的 cwd 本来就是 [workspaceDir]，默认工作区落在同一棵树下，
     *   agent 的文件操作与沙箱的写边界（`--bind <workspaceRoot>`）天然一致；
     * - `/sdcard` 在沙箱里是**被拒**的（见 AGENTS.md ④ 的验收项）。
     *
     * 注意这里指向 [workspaceDir] 本身，而实际建出来的是它下面的
     * `deepseek-harness/default-workspace`，不是把 [workspaceDir] 直接当工作区。
     *
     * ## 为什么用 `--patch` 而不是用户层
     *
     * 这一项**不是 GUI 设置项**（`settings/describe` 里没有它），用户无从覆盖，
     * 所以不存在「锁死用户配置」的问题；而它是 Host 部署事实（本机文档目录在哪），
     * 本来就该由 App 拥有。这与 [writeSandboxPatch] 同类，每次启动整体覆盖。
     */
    private fun writeWorkspacePatch(): File? {
        val file = File(dshHome, WORKSPACE_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# Android 落不进引擎的平台分支（只认 darwin/win32/linux），")
            appendLine("# 必须显式给 documentsDirectory，否则首次使用建不出默认工作区。")
            appendLine("# 详见 EngineManager.writeWorkspacePatch() 的注释。")
            appendLine("- id: workspace-controller")
            appendLine("  name: '@deepseek-ai/dsh-api-workspace-controller'")
            appendLine("  config:")
            appendLine("    documentsDirectory: '${workspaceDir.absolutePath}'")
        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入工作区 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入工作区 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 让「添加插件」对话框同时列出镜像源与 npm 官方源（幂等）。
     *
     * ## 症状
     *
     * 真机对话框的「安装源」下拉只有**一项**（中国大陆镜像源）+ 自定义地址，
     * 而桌面版有两项（npm 官方源 + 中国大陆镜像源）+ 自定义地址。功能不算坏
     * （选「自定义地址」填 `https://registry.npmjs.org/` 等效），但用户看到的是
     * 「手机端少了官方源」。
     *
     * ## 根因：不是少配了，而是**被去重合并掉了**
     *
     * 列表由官方前端按 Host 返回的源**动态去重**生成
     * （`dsh-client-ui-plugin-manager/lib/client.js` 的 `offeredRegistries()`），
     * 遍历三处、按 URL 归一化后去重：
     *
     * ```
     * [config.registry] + [config.fallbackRegistries] + [pnpm 自身配置(~/.npmrc) 指的源]
     * ```
     *
     * 而官方 `fallbackRegistries` 的默认值**恰好就是** `NPMMIRROR_REGISTRY`。
     * 本机 [MirrorProbe] 测速后把 `registry=https://registry.npmmirror.com` 写进了
     * `~/.npmrc`，于是上面三项归一化后**全是同一个 URL** → 去重后只剩一项。
     * 桌面版 `.npmrc` 指的是官方源，与 fallback 的 npmmirror 不同 → 两项都留下。
     *
     * ## 为什么显式写 `registry` 且 fallback 里**也要**放镜像
     *
     * 两处都写，是因为 `registryPlan()` 的 `ownIsPublic` 判定：
     *
     * ```js
     * const ownIsPublic = own !== null && (own === "https://registry.npmjs.org/" || fallbacks.includes(own));
     * ```
     *
     * `own` 是 pnpm 自己报的源（本机 = npmmirror）。若 fallback 里不放它，
     * `ownIsPublic` 为 false，于是下面这条会让安装**只试一个源、没有回退**：
     *
     * ```js
     * if ((first === null || firstKey === own) && !ownIsPublic) return [normalizedFirst];
     * ```
     *
     * 把 npmmirror 也放进 fallback ⇒ `ownIsPublic` 为 true ⇒
     * 列表是 `[npmmirror, npmjs]`（镜像在前，与桌面版顺序一致），
     * 安装时先走镜像、失败自动回退官方源（`registryPlan` 的 known 序列）。
     *
     * ⚠️ URL 必须**逐字**与官方常量一致（含**尾斜杠**）。少了斜杠去重键不同，
     * 对话框会多出一项看起来重复的源。出处：`dsh-plugin-manager/lib/index.js`
     * 的 `NPMMIRROR_REGISTRY`。
     *
     * ## 为什么用 `--patch` 而不是用户层
     *
     * `plugin-manager` 的 `inject` 里**没有 `settings`**，这两项也就不是 GUI 设置项
     * （`settings/describe` 里没有它们），用户无从覆盖 —— 不存在「锁死用户配置」的
     * 问题。它们是 Host 部署事实（本机走哪个源），与 [writeSandboxPatch] /
     * [writeWorkspacePatch] 同类，每次启动整体覆盖。
     *
     * ⚠️ `name` 必须与 base 层逐字一致，否则 `applyEntryPatches()` 会
     * `name mismatch ... skipping` 整条跳过（静默，只在日志里 warn）。
     * 只写 `config:` 不会动 base 层那行的 `disabled: !!js ...`
     * —— patch 是**逐字段覆盖**（`target[key] = value`），不是整行替换。
     */
    private fun writePluginManagerPatch(): File? {
        val file = File(dshHome, PLUGIN_MANAGER_PATCH)
        val yaml = buildString {
            appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
            appendLine("# 插件安装源：首选国内镜像，回退 npm 官方源。")
            appendLine("# 官方 fallbackRegistries 的默认值只有镜像源，而本机 ~/.npmrc 指的也是")
            appendLine("# 镜像源，两者被前端按 URL 去重合并 ⇒ 对话框只剩一项。显式写出两项即可。")
            appendLine("# 详见 EngineManager.writePluginManagerPatch() 的注释。")
            appendLine("- id: plugin-manager")
            appendLine("  name: '@deepseek-ai/dsh-plugin-manager'")
            appendLine("  config:")
            appendLine("    registry: '$NPM_MIRROR_REGISTRY'")
            appendLine("    fallbackRegistries:")
            appendLine("      - '$NPM_MIRROR_REGISTRY'")
            appendLine("      - '$NPM_OFFICIAL_REGISTRY'")
        }
        return try {
            if (!file.isFile || file.readText() != yaml) {
                file.writeText(yaml)
                Log.i(TAG, "已写入插件源 overlay: ${file.name}")
            }
            file
        } catch (t: Throwable) {
            Log.w(TAG, "写入插件源 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 把 `flock.js` 的平台白名单补上 android（幂等）。
     *
     * 只替换那一处判断，不动其它任何逻辑；找不到锚点就跳过（引擎升级后结构变了
     * 也不要因此让启动失败，届时日志里会有明确提示）。
     */
    private fun patchFlockPlatform() {
        val flock = File(engineDir, "node_modules/@deepseek-ai/node-addon-system/lib/flock.js")
        if (!flock.isFile) {
            Log.w(TAG, "未找到 flock 入口，跳过 android 平台放行: ${flock.absolutePath}")
            return
        }

        try {
            val source = flock.readText()
            if (source.contains(FLOCK_ANDROID_ANCHOR)) {
                return
            }
            if (!source.contains(FLOCK_PLATFORM_GUARD)) {
                Log.w(TAG, "flock 平台判断结构与预期不符，跳过 android 平台放行")
                return
            }
            flock.writeText(source.replace(FLOCK_PLATFORM_GUARD, FLOCK_ANDROID_ANCHOR))
            Log.i(TAG, "已放行 flock 的 android 平台: ${flock.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "放行 flock 的 android 平台失败: ${t.message}")
        }
    }

    /**
     * 让引擎**不再因为一个插件的未捕获异常而整个进程退出**（幂等）。
     *
     * 这是「用户自己做插件把 App 搞崩」的**唯一根因**，真机实测确认：
     * 语法写坏、`apply()` 里同步抛错，引擎都只 `warn: N entry did not activate`
     * 然后照常就绪（因为 `requiredStartupEntryIds` 是白名单，用户插件不在其中）。
     * 真正会死的是**运行期回调里抛出的未捕获异常**：
     *
     * ```
     * dsh: fatal uncaught exception: Error: BOOM
     * 引擎已退出: 退出码 1，准备自动重启（第 1 次）
     * … 反复 3 次后「引擎反复停止」，App 再也起不来
     * ```
     *
     * ⚠️ 这条路径下 `rescue/` 目录**始终是空的**，`RescueBoot.recordFailure`
     *   一次都不会被调用 —— 因为 App 走的是「引擎退出→重启」分支，不是
     *   「启动失败」分支，于是 L1/L2/L3 三级降级阶梯**判据全不成立**。
     *   这就是为什么现有的防崩措施对这个场景完全无效。
     *
     * 修法分两步（**两处都必须改，只改一处不行** —— 实测过）：
     *
     * 1. `dsh-app-boot` 的 `installFailLoud` 里两处 `proc.exit(1);` → 空注释。
     * 2. `dsh` 的 `profile-boot` 传给 `installFailLoud` 的 release 回调
     *    `async () => { await app.current?.fiber.dispose(); }` → 空函数。
     *
     * ⚠️ **第 2 步是关键，漏了它整件事白做**：只改第 1 步时进程会以
     *   `退出码 0` 安静退出 —— 因为 release 把整个应用 fiber 树 dispose 掉了，
     *   node 没有活干自然退出。表现为「补丁生效了（1→0）但引擎照退」。
     *
     * 崩溃的那个插件自身的 entry 已由 Loader 标记失败、不参与后续工作，
     * 其余插件的服务照常可用 —— 比「整个 App 打不开」好得多。
     *
     * ⚠️ 只在 Android 上这么干。桌面端「大声失败后退出」是合理语义，
     *   那边没有「用户装第三方插件」这条路径。
     */
    private fun patchFailLoudExit() {
        // ── 第 1 步：installFailLoud 里的 proc.exit(1) ──
        val boot = File(engineDir, "node_modules/@deepseek-ai/dsh-app-boot/lib/index.js")
        if (!boot.isFile) {
            Log.w(TAG, "未找到 dsh-app-boot 入口，跳过引擎抗崩溃补丁: ${boot.absolutePath}")
        } else {
            try {
                val source = boot.readText()
                if (!source.contains(FAIL_LOUD_ANDROID_MARK)) {
                    if (!source.contains(FAIL_LOUD_EXIT_GUARD)) {
                        Log.w(TAG, "installFailLoud 结构与预期不符，跳过引擎抗崩溃补丁")
                    } else {
                        boot.writeText(source.replace(FAIL_LOUD_EXIT_GUARD, FAIL_LOUD_ANDROID_ANCHOR))
                        Log.i(TAG, "已让引擎不再因插件的未捕获异常而退出进程: ${boot.name}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "应用引擎抗崩溃补丁失败: ${t.message}")
            }
        }

        // ── 第 2 步：profile-boot 的 release 回调（不做就白改）──
        // ⚠️ 文件名带构建 hash（profile-boot-<hash>.js），换引擎版本会变，
        //   所以先按已知名找，找不到就按前缀扫目录兜底。
        val dshLib = File(engineDir, "node_modules/@deepseek-ai/dsh/lib")
        val profileBoot = File(dshLib, "profile-boot-BZ2ZjNWi.js").takeIf { it.isFile }
            ?: dshLib.listFiles()
                ?.firstOrNull { it.isFile && it.name.startsWith("profile-boot-") && it.name.endsWith(".js") }
        if (profileBoot == null || !profileBoot.isFile) {
            Log.w(TAG, "未找到 profile-boot，跳过引擎抗崩溃补丁第 2 步: ${dshLib.absolutePath}")
            return
        }
        try {
            val source = profileBoot.readText()
            if (source.contains(FAIL_LOUD_RELEASE_MARK)) {
                return
            }
            if (!source.contains(FAIL_LOUD_RELEASE_GUARD)) {
                Log.w(TAG, "profile-boot 的 release 回调结构与预期不符，跳过第 2 步")
                return
            }
            // ⚠️ 走原子写入：这是引擎启动必经的文件，写坏了引擎直接起不来，
            //   而报错信息不会指向「文件被写了一半」这件事。
            if (!writeTextAtomically(
                    profileBoot,
                    source.replace(FAIL_LOUD_RELEASE_GUARD, FAIL_LOUD_RELEASE_ANCHOR),
                )
            ) {
                Log.w(TAG, "profile-boot 补丁未能落盘，保持原样（下次启动重试）")
                return
            }
            Log.i(TAG, "已让引擎在插件崩溃后保留进程: ${profileBoot.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "应用引擎抗崩溃补丁第 2 步失败: ${t.message}")
        }
    }

    /**
     * 安装移动端 UI 适配插件（幂等）。
     *
     * DSH 自带的前端是桌面布局：侧栏常驻、顶栏按鼠标悬停设计、消息操作按钮
     * 一长排。窄屏手机上这些要么挤成一团，要么根本点不到。
     *
     * 做法与桌面端一致：把一个声明了 `dsh.client` 的插件包落盘，再用 `--patch`
     * 传一层 overlay 把它挂上。客户端半由 `@deepseek-ai/dsh-client-modules`
     * 自动编入浏览器侧的 combo bundle，宿主半（lib/index.js）只是占位 —— 包必须
     * 有 Node 侧入口，否则 Cordis import 浏览器代码会当场抛 `window is not defined`。
     *
     * 插件自身用 `[data-mobile]` 属性作用域 + `matchMedia` 判据，只在窄屏且
     * 非鼠标设备时生效，宽屏/桌面窗口下官方布局一字不变。
     *
     * ## ⚠️ 为什么解到 `$DSH_HOME/mobile-ui/`，而不是 profile 的 `node_modules`
     *
     * 这是**踩过的坑**。原先解到 `profiles/web/node_modules/dsh-android-mobile`，
     * 而该包**不在** profile 的 `dependencies` 里（写进去会让 DSH 去 registry 拉
     * 这个本地包，桌面端同样刻意不写）。后果是：**用户只要用插件管理器装一次
     * 插件，pnpm 就按「多余依赖」把它挪进 `node_modules/.ignored/`**。
     *
     * 包一挪走，`--patch` 里那个按包名解析的 `name` 就找不到模块，客户端半不再
     * 编入浏览器 —— 而 `injectStyles()` 注入的 `<style>` 随插件卸载被一并移除，
     * **整个移动端适配（53 条 `[data-mobile]` 规则）全部消失**，界面静默退回
     * 桌面布局。表现就是：侧栏不再是抽屉而是常驻 56px 图标条（正文只剩 304px），
     * 且工具卡片/执行过程里的长文本左右两端都被裁出屏幕。
     *
     * 更要命的是它**不报错**：宿主半照常 apply，页面上甚至残留着上一轮渲染的
     * `.mp-nav-btn` / `.mp-drawer-mask` 元素，只是 CSS 没了（`position` 全是
     * `static`），看着像"插件装了一半"。
     *
     * 所以现在把包放在 pnpm 管不到的地方，并在 overlay 里用 `file://` 绝对 URL
     * 挂载 —— `dsh-client-modules.locatePkgJson()` 显式支持 pathLike 说明符
     * （`.` / `file:` / 绝对路径），从模块向上找到最近的 `package.json` 即认定为
     * 该包。这样 pnpm 怎么装插件都不会再动到它。
     */
    private fun ensureMobileUi() {
        val target = File(dshHome, "$MOBILE_UI_DIR/$MOBILE_UI_PACKAGE")
        val clientJs = File(target, "lib/client.js")
        val stamp = File(target, MOBILE_UI_STAMP)

        // 版本戳一致才跳过：插件随 APK 升级而变，只看文件存在会留下旧版
        if (clientJs.isFile && clientJs.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == MOBILE_UI_VERSION
        ) {
            return
        }

        /*
         * ⚠️ **解包必须是原子的：先解到临时目录，全部成功后再整体换上去。**
         *
         * 原来的写法是 `target.deleteRecursively()` 之后直接往 `target` 里解。
         * 这有个很硬的问题：**一旦解包中途失败，旧版本已经被删掉了**。
         * 失败原因在真机上都是现实存在的 ——
         *   · 磁盘不足（这个 zip 解出来约 1.2 MB，但 dshHome 在共享存储上，
         *     用户把它塞满是很常见的事，`copyTo` 中途抛 IOException）；
         *   · 用户在解包过程中把 App 切走，进程被系统回收；
         *   · 存储被卸载（SD 卡用户）。
         * 结果是 `lib/client.js` 缺失或只有半个文件 —— 插件加载失败，
         * 界面**静默退回桌面布局**，而用户完全不知道发生了什么，
         * 下次启动还会因为「clientJs 不完整」再删一遍。反复几次仍然如此。
         *
         * 现在改成：解到 `<pkg>.tmp` → 全部写完并落戳 → 删旧 → 改名替换。
         * 任何一步失败都只留下一个可丢弃的 `.tmp`，**旧版本原样可用**。
         * 这是本项目所有资产解包都应该遵守的规矩（engine.bin 那条同理）。
         */
        val tmp = File(target.parentFile, "$MOBILE_UI_PACKAGE.tmp")
        try {
            // 清掉上次失败的残留（它一定是不完整的，没有保留价值）
            tmp.deleteRecursively()
            tmp.mkdirs()
            if (!tmp.isDirectory) {
                throw java.io.IOException("无法创建临时目录: ${tmp.absolutePath}")
            }
            java.util.zip.ZipInputStream(context.assets.open(MOBILE_UI_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 防目录穿越：zip 条目名不允许跑出临时目录
                        val out = File(tmp, entry.name)
                        val basePath = tmp.canonicalPath + File.separator
                        if (!out.canonicalPath.startsWith(basePath)) {
                            Log.w(TAG, "mobile-ui.zip 条目越界，已丢弃: ${entry.name}")
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            // 先确认解出来的东西真的完整，再换掉旧版本
            val tmpClient = File(tmp, "lib/client.js")
            if (!tmpClient.isFile || tmpClient.length() <= 0L) {
                throw java.io.IOException("解包结果缺少 lib/client.js")
            }
            File(tmp, MOBILE_UI_STAMP).writeText(MOBILE_UI_VERSION)
            // 换上去：删旧 → 改名。两步之间窗口极短，且此时新版本已完整就绪。
            target.deleteRecursively()
            if (!tmp.renameTo(target)) {
                // rename 失败（跨设备挂载等）就退化成拷贝，仍然保证新版本可用
                tmp.copyRecursively(target, overwrite = true)
                tmp.deleteRecursively()
            }
            Log.i(TAG, "已安装移动端 UI 插件 $MOBILE_UI_PACKAGE $MOBILE_UI_VERSION (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就不阻断启动：宁可给桌面布局，也不要白屏。
            // ⚠️ 旧版本**仍然完好**（上面没有提前删它），下次启动会重试。
            Log.w(TAG, "安装移动端 UI 插件失败（旧版本保持可用）: ${t.message}")
            tmp.deleteRecursively()
        }

        // 清理旧位置：早先版本解到 profile 的 node_modules 下，留在那里既没用，
        // 又会被插件清单扫到；pnpm 也可能把它当成待驱逐的"多余依赖"。
        File(dshHome, "profiles/web/node_modules/$MOBILE_UI_PACKAGE").deleteRecursively()
    }

    /**
     * 写出挂载移动端 UI 插件的 overlay，返回其路径；不可用时返回 null。
     *
     * 走 `--patch` 而不是改 profile 自己的 `cordis.patch.yml`：那个文件由 DSH
     * 初始化并可能承载用户配置，覆写它会丢东西；而 `--patch` 层在 profile 层
     * **之后**应用，优先级更高，且完全由本 App 拥有，升级时整体覆盖即可。
     *
     * `name` 用 `file://` 绝对 URL 而不是裸包名 —— 包在 pnpm 管辖之外，按包名
     * 解析不到（原因见 ensureMobileUi() 的注释）。YAML 里带 `:` 的标量必须加引号。
     */
    private fun writeMobileUiPatch(): File? {
        val patch = File(dshHome, MOBILE_UI_PATCH)
        val entry = File(dshHome, "$MOBILE_UI_DIR/$MOBILE_UI_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "移动端 UI 插件入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            // 手工拼 `file://` + 绝对路径（三斜杠）而不是 File.toURI()：后者给的是
            // `file:/data/...`（单斜杠），虽然多数解析器能接受，但 ESM 侧要的是标准
            // 文件 URL。路径全为 ASCII 且无保留字符，不需要百分号编码。
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载移动端 UI 适配插件；插件包随 APK 分发，已解到 .dsh/${MOBILE_UI_DIR}。
                # 用 file:// 绝对路径挂载：包刻意放在 profile 的 node_modules 之外，
                # 否则用户每次用插件管理器装东西都会被 pnpm 挪进 .ignored/。
                - insert:
                    - id: $MOBILE_UI_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入移动端 UI overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出**右侧栏「AI 浏览器」入口**插件（`browser-pane.zip` →
     * `$DSH_HOME/browser-pane/dsh-android-browser-pane/`）。
     *
     * 该插件是**客户端插件**（`dsh.client` 声明 + `lib/client.js`），在官方右侧
     * 边栏里注册一项「AI 浏览器」：点开就调原生 `openAiBrowser()` 打开
     * [com.dsh.android.browser.AiBrowserView]。
     *
     * ## 为什么单独成包，而不是并进 `dsh-android-mobile`
     *
     * 那个包的 `lib/client.js` 在 dev 与 main 两条分支上差 17 万字节，而
     * `assets/mobile-ui.zip` 是**跨工作树共享的符号链接资产** —— 往它里面加东西
     * 就必须重打整包，一次重打会把另一条分支的移动端修复整个回退掉。
     * 独立成包互不干扰，代价只是多一个 8 KB 的 zip。
     *
     * ⚠️ 与 `bubble-tool` / `uiagent-tool` 那类**工具插件**不同，这个包是
     *   **客户端插件**：它必须在**引擎启动之前**解包落盘，因为引擎启动时才会
     *   扫描 `dsh.client` 声明并把客户端代码编进前端 bundle。解晚一步就是
     *   「文件在、侧栏里没有这一项」，而且没有任何报错。
     */
    private fun ensureBrowserPane() {
        val target = File(dshHome, "$BROWSER_PANE_DIR/$BROWSER_PANE_PACKAGE")
        val clientJs = File(target, "lib/client.js")
        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(BROWSER_PANE_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "已安装侧栏浏览器入口插件 $BROWSER_PANE_PACKAGE (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是侧栏里少一项（没有别的入口可退，但启动不该因此失败）
            Log.w(TAG, "安装侧栏浏览器入口插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载侧栏浏览器入口的 overlay，返回其路径；入口不存在时返回 null。
     *
     * 与 [writeMobileUiPatch] 完全同构（`file://` 绝对路径 + `insert`）。
     */
    private fun writeBrowserPanePatch(): File? {
        val patch = File(dshHome, BROWSER_PANE_PATCH)
        val entry = File(dshHome, "$BROWSER_PANE_DIR/$BROWSER_PANE_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "侧栏浏览器入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载右侧栏「AI 浏览器」入口插件；插件包随 APK 分发，已解到 .dsh/${BROWSER_PANE_DIR}。
                - insert:
                    - id: $BROWSER_PANE_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入侧栏浏览器入口 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入侧栏浏览器入口 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 Agnes 多模态插件（`generate_image` / `generate_video` + 多模态配置页）。
     *
     * 与 [ensureMobileUi] 同一套做法、同一套理由：包解到 `$DSH_HOME/agnes-multimodal/`
     * 而不是 profile 的 `node_modules`（那里归 pnpm 管，本包不在 profile 的
     * `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`，
     * overlay 里的包名随即解析失败）。
     *
     * 幂等判据是版本戳而不是「文件在不在」：插件随 APK 升级而变，只看文件存在
     * 会让老设备永远停在旧版。
     */
    private fun ensureAgnesMultimodal() {
        // ⚠️ **必须早于下面的版本戳提前返回**：本故障只出现在老设备上（版本戳已经
        // 是最新），放末尾就等于「永远不会执行」，修了个寂寞。
        //
        // 清理 profile 里的同名副本。老版本或插件管理器可能把本包装进
        // `profiles/<p>/node_modules/`，而 App 的 overlay 又按 `file://` 挂了同一份，
        // `dsh-client-modules` 的 `reconcilePackage()` 会判成「同一包来自两个
        // Loader 源」并抛错 ⇒ `modules (required)` 不激活 ⇒ **引擎起不来、界面打不开**：
        //
        //   client-modules: package dsh-agnes-multimodal resolves from multiple active
        //   Loader sources: "dsh-agnes-multimodal" from .../profiles/web/,
        //   "file://.../agnes-multimodal/dsh-agnes-multimodal/lib/index.js" from .../profiles/web/
        //
        // 与 [ensureMobileUi] 末尾那行**同构、同一套理由**（它清的是自己那份）。
        // 判据是「包解析得到 client bundle」而不是「目录在不在」：删掉后裸包名那条
        // entry 的 `locatePkgJson()` 返回 undefined，源数就回到 1。
        File(dshHome, "profiles").listFiles()?.forEach { profile ->
            val duplicate = File(profile, "node_modules/$AGNES_MM_PACKAGE")
            if (duplicate.exists() && duplicate.deleteRecursively()) {
                Log.i(TAG, "已清理 profile 里重复的 $AGNES_MM_PACKAGE 副本: ${profile.name}")
            }
        }

        val target = File(dshHome, "$AGNES_MM_DIR/$AGNES_MM_PACKAGE")
        val clientJs = File(target, "lib/client.js")
        val stamp = File(target, AGNES_MM_STAMP)

        if (clientJs.isFile && clientJs.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == AGNES_MM_VERSION
        ) {
            return
        }

        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(AGNES_MM_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(AGNES_MM_VERSION)
            Log.i(TAG, "已安装 Agnes 多模态插件 $AGNES_MM_PACKAGE $AGNES_MM_VERSION (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了两个生成工具
            Log.w(TAG, "安装 Agnes 多模态插件失败: ${t.message}")
        }
    }

    /**
     * 把内置号池接到 Agnes 多模态插件上（写 `$DSH_HOME/ag-multimodal.json`）。
     *
     * 插件调 `readAccounts()` 拿账号池，每条形如 `{endpoint, key}`，然后直接
     * `Authorization: Bearer <key>` 发出去。
     *
     * ## 号池改为服务端网关后的变化
     *
     * 以前这里写的 `key` 是占位符 `sk-ANON…`，由 `libkeyinject.so` 在 `SSL_write`
     * 里换成真钥匙；`endpoint` 必须用国际站域名，因为要靠等长改写 Host 头做
     * 两站路由。**那条链路已整体下线**。
     *
     * 现在：
     * - `endpoint` = 网关地址 [KEY_BASE_URL]
     * - `key`      = 网关令牌 [KEY_PLACEHOLDER]
     *
     * 真密钥、两站路由、429 换号重试**全部由服务器负责**，客户端不需要知道
     * 底下有几个站点、几把钥匙。
     *
     * ## ⚠️ 内置号是「不可删除、不可更改」的，所以每次启动都要修复
     *
     * 与 [seedDefaultSettings] 那种「只播种一次、之后不碰」的策略**刻意不同**。
     * 内置号是 App 提供的免费号池，属于产品能力的一部分，不是用户数据：用户删掉它
     * 就等于把内置号池弄丢了。所以这里每次都确保它存在且是权威值。
     *
     * 三重保护，缺一不可：
     *
     * 1. **服务端强制**（插件 `lib/index.js` 的 `set` 分支）：传回去的内置条目
     *    一律丢弃、用权威值重建 —— 绕过 UI 直接调 API 也改不动。
     * 2. **UI 只读**（插件 `lib/client.js`）：内置行没有地区切换、没有可编辑的
     *    输入框、没有「移除」按钮。
     * 3. **每次启动修复**（本方法）：兜住「用户改了磁盘上的文件」这种情况。
     *
     * ⚠️ 只**补回/纠正**内置条目，绝不触碰用户自己加的行，也不改它们的顺序 ——
     * 用户的号必须保持排在前面（优先级更高）。
     */
    private fun seedAgnesAccounts() {
        val state = File(dshHome, AGNES_MM_STATE)
        try {
            val root = if (state.isFile) {
                // 解析失败（手改坏了 / 空文件）时退回空对象，下面会重建出内置号。
                JSONObject(state.readText().trim().ifEmpty { "{}" })
            } else {
                JSONObject()
            }

            val accounts = root.optJSONArray("accounts") ?: JSONArray()
            val next = JSONArray()
            // 用户行原样保留、保持原有顺序（它们排在前面 ⇒ 优先级更高）。
            for (i in 0 until accounts.length()) {
                val row = accounts.optJSONObject(i) ?: continue
                // 内置行一律跳过，下面统一追加权威值。认三种标记：
                //   - `builtin` 布尔标记（当前写法）
                //   - 老版本的 TLS 注入占位符 `sk-ANON…`（升级前写下的）
                //   - 当前网关令牌（用户手改后被纠正）
                val key = row.optString("key")
                if (row.optBoolean("builtin") || key == LEGACY_KEY_PLACEHOLDER || key == KEY_PLACEHOLDER) continue
                if (key.isBlank()) continue
                next.put(JSONObject().put("endpoint", row.optString("endpoint")).put("key", key))
            }
            // 内置号恒在最后一位（池尾兜底），带 builtin 标记。
            next.put(
                JSONObject()
                    .put("endpoint", KEY_BASE_URL)
                    .put("key", KEY_PLACEHOLDER)
                    .put("builtin", true)
            )
            root.put("accounts", next)

            val body = root.toString() + "\n"
            // 内容一致就不写盘：避免每次启动都动文件（也少一次 rename）。
            if (!state.isFile || state.readText() != body) {
                state.writeText(body)
                Log.i(TAG, "已把内置号池接入 Agnes 多模态插件: ${state.absolutePath}")
            }
        } catch (t: Throwable) {
            // 修复失败不阻断启动：插件会回落到它自带的默认账号
            Log.w(TAG, "写入 Agnes 账号池失败（不影响启动）: ${t.message}")
        }
    }

    /**
     * 写出挂载 Agnes 多模态插件的 overlay，返回其路径；不可用时返回 null。
     *
     * 与 [writeMobileUiPatch] 同构：`--patch` 在 profile 层**之后**应用，优先级
     * 更高，且完全由本 App 拥有，升级时整体覆盖即可。`name` 用 `file://` 绝对
     * URL，因为包在 pnpm 管辖之外、按裸包名解析不到。
     */
    private fun writeAgnesMultimodalPatch(): File? {
        val patch = File(dshHome, AGNES_MM_PATCH)
        val entry = File(dshHome, "$AGNES_MM_DIR/$AGNES_MM_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "Agnes 多模态插件入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载 Agnes 多模态插件；插件包随 APK 分发，已解到 .dsh/${AGNES_MM_DIR}。
                # 用 file:// 绝对路径挂载：包刻意放在 profile 的 node_modules 之外，
                # 否则用户每次用插件管理器装东西都会被 pnpm 挪进 .ignored/。
                - insert:
                    - id: $AGNES_MM_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入 Agnes 多模态 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 把内置的**中文 skill** 解到 `$DSH_HOME/skills/`（幂等）。
     *
     * ## 为什么是这个落点
     *
     * `dsh-skill-filesystem` 的发现根里，`$DSH_HOME/skills`（`USER_DSH_RANK=400`）
     * 是**全局根**：预设行没写 `dshHome` 时它回落到 `$DSH_HOME`（[resolveDshHome]），
     * 而 `includeDefaultRoots` 默认 true —— 所以**每个预设都会扫到这个目录**，
     * 不需要改任何一份 `cordis.patch.yml`。这正是「用户有需求时直接注入」的接缝。
     *
     * ## ⚠️ 绝不整目录重建
     *
     * `$DSH_HOME/skills/` 是**用户自己的** skill 目录（他手写的 skill 也放这儿）。
     * 所以只删本 App 管的那几个子目录（[PRESET_SKILL_NAMES]），
     * **不能** `deleteRecursively()` 整个 `skills/` —— 那会删掉用户的东西。
     * 版本戳（[PRESET_SKILLS_STAMP]）同理放在 `skills/` 下而不是先清空它。
     *
     * 幂等判据是版本戳而不是「文件在不在」：skill 随 APK 升级而变，
     * 只看文件存在会让老设备永远停在旧版内容（与 [ensurePresetRazor] 同一取舍）。
     */
    private fun ensurePresetSkills() {
        val root = File(dshHome, "skills")
        val stamp = File(root, PRESET_SKILLS_STAMP)

        if (stamp.isFile && stamp.readText().trim() == PRESET_SKILLS_VERSION) return

        try {
            root.mkdirs()
            for (name in PRESET_SKILL_NAMES) File(root, name).deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(PRESET_SKILLS_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(root, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(PRESET_SKILLS_VERSION)
            Log.i(
                TAG,
                "已安装内置 skill $PRESET_SKILLS_VERSION: " +
                    PRESET_SKILL_NAMES.joinToString(", ") + " → ${root.absolutePath}",
            )
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了这几份 skill，引擎照常跑。
            Log.w(TAG, "安装内置 skill 失败: ${t.message}")
        }
    }

    /**
     * 把「快准狠」约束并入**创造模式**（`cordis` 预设）的 persona（幂等）。
     *
     * ## 为什么直接改引擎里的预设文件，而不是走 `--patch` overlay
     *
     * `--patch` 的语义是给 loader **顶层**条目按 `id` 覆盖，而 `applyEntryPatches()`
     * 建索引时只递归 `entry.group && Array.isArray(entry.config)`：
     *
     * ```js
     * const buildMap = (entries) => {
     *   for (const entry of entries) {
     *     if (entry.id) entryMap.set(entry.id, entry);
     *     if (entry.group && Array.isArray(entry.config)) buildMap(entry.config);
     *   }
     * };
     * ```
     *
     * 预设声明行（`id: preset-cordis`）**不是 group** —— 它的 `plugins` 列表挂在
     * `config` 里、每一行也没有 `group: true`。所以 overlay 里写 `- id: persona`
     * **匹配不到东西**（`patch: entry "persona" not found`），那条 persona 永远改不到。
     * 而按 `preset-cordis` 整块覆盖 `config` 又会把整套工具列表一起搬进 Kotlin 常量
     * （补丁是整块替换、不深合并），引擎每次升级都要跟着重写 —— 那是长期负债。
     *
     * 官方文件自己就是真相源（`dsh-web-app/presets/cordis.patch.yml`），
     * 所以这里用一个**字面文本锚点补丁**，与 [patchAttachmentDurability] /
     * [patchLlmDeepseekToolOrder] 同一套做法：只改这一个文件、只改这一个字段。
     *
     * ## ⚠️ 引擎重解包会让补丁消失，所以每次启动都要重打
     *
     * `ensureExtracted()` 在版本戳变化时 `deleteRecursively()` 整个 `engine/`
     * 再重解包 —— 补丁随之被抹掉。因此本函数**不是一次性的**，幂等判据用
     * 「补丁后的文本特征在不在」（[PRESET_CORDIS_PERSONA_ANCHOR_HEAD]），
     * 而不是某个戳文件（戳文件也在 engine 里，会一起没）。
     *
     * ⚠️ 锚点找不到就跳过并打日志，**不让启动失败** —— 引擎升级后官方那句
     * persona 原文若变了，届时按新文本更新 [PRESET_CORDIS_PERSONA_GUARD] 即可。
     */
    private fun patchPresetCordisPersona() {
        val entry = File(engineDir, PRESET_CORDIS_PRESET_PATH)
        if (!entry.isFile) {
            Log.w(TAG, "未找到创造模式预设文件，跳过提示词补丁: ${entry.absolutePath}")
            return
        }

        try {
            val source = entry.readText()
            if (source.contains(PRESET_CORDIS_PERSONA_ANCHOR_HEAD)) return
            if (!source.contains(PRESET_CORDIS_PERSONA_GUARD)) {
                Log.w(TAG, "创造模式预设的 persona 结构与预期不符，跳过提示词补丁")
                return
            }
            entry.writeText(source.replace(PRESET_CORDIS_PERSONA_GUARD, PRESET_CORDIS_PERSONA_PATCH))
            Log.i(TAG, "已把快准狠约束并入创造模式: ${entry.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "创造模式提示词补丁失败: ${t.message}")
        }
    }

    /**
     * 把内置预设 dsh-preset-razor 解到 `$DSH_HOME/node_modules/dsh-preset-razor/`（幂等）。
     *
     * 与 [ensureCoreMemory] 同一套做法、同一套理由 —— 这两个是**唯一走 profile bundle
     * 的内置包**，其余（mobile-ui / market / bubble…）仍走 `--patch` overlay：
     *
     * - 位置必须是 `$DSH_HOME/node_modules/`：bundle 名靠 Node 的向上查找解析
     *   （`createRequire().resolve.paths()`），require 只查 `node_modules` 这一层，
     *   查不到兄弟目录。**也不能**放 `profiles/web/node_modules/` —— 那是 pnpm 的地盘，
     *   会被当「多余依赖」挪进 `.ignored/`。
     * - 目录名**必须**是包名 `dsh-preset-razor`：解析是按
     *   `<某个 node_modules>/<包名>/package.json` 找的。
     *
     * 幂等判据是版本戳而不是「文件在不在」：预设随 APK 升级而变，只看文件存在
     * 会让老设备永远停在旧版提示词（旧提示词里那句已被 0.2.1 删掉的
     * `你的工作目录是 {{cwd}}。` 正是本次要淘汰的东西）。
     */
    private fun ensurePresetRazor() {
        val target = presetRazorDir()
        val upstream = File(target, "cordis.patch.yml")
        val stamp = File(target, PRESET_RAZOR_STAMP)

        if (upstream.isFile && upstream.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == PRESET_RAZOR_VERSION
        ) {
            return
        }

        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(PRESET_RAZOR_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(PRESET_RAZOR_VERSION)
            Log.i(TAG, "已安装内置预设 $PRESET_RAZOR_PACKAGE $PRESET_RAZOR_VERSION (${upstream.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了这个预设
            Log.w(TAG, "安装内置预设失败: ${t.message}")
        }
    }

    /**
     * 把内置预设 dsh-preset-razor 登记成 profile 的一个 **bundle**，让它在
     * 「插件 → 已安装插件」列表里出现、并能被用户单独开关（默认开启）。
     *
     * ## 为什么不再是 `--patch` overlay 里的 `- insert:`
     *
     * 与 [registerCoreMemoryBundle] 完全同因：overlay 插进来的条目**没有身份** ——
     * 官方 plugin-manager 的列表只认 `dsh.profile.bundles`（`listBundles()`）与 Loader
     * 里由 bundle 层贡献的行，overlay 的行不在任何一份清单里。于是插件页看不到它，
     * 用户既关不掉、也不知道它存在。
     *
     * 改成 bundle 之后，「关掉」= 从 `dsh.profile.bundles` 摘掉这个名字，包本身留在
     * `node_modules`，随时能再打开。
     *
     * ## ⚠️ 幂等判据必须「只看缺失才补」，不能「每次都补齐」
     *
     * 这是**与改造前最大的语义差别**。overlay 是 App 每次启动整体覆盖的，用户改不动；
     * 而 bundle 名单是**用户与 App 共写**的同一份文件。若写成「不在就加上」，用户
     * 在插件页关掉它、重启 App，这里又会把它加回去 —— 变成「关不掉」。
     *
     * 所以判据是「**从来没见过它**才补」：见标记文件 [PRESET_RAZOR_MARKER]。
     * 一旦登记过（标记文件在），此后只在用户保留它时维持现状，绝不自动复活。
     * 用户想再打开，在插件页把它打开即可，那时的写入由官方插件管理器完成。
     *
     * ## ⚠️ `dependencies` 也要写
     *
     * 官方 `listBundles()` 的 `installed`、`removable`、版本比较都读
     * `manifest.dependencies`，只写 `bundles` 会让这一行在插件页**根本不渲染**
     * （`listed` 的判据是 `installed || official || error`）。
     * 依赖值用 `link:` 指向真实目录，pnpm 认得这种本地链接、也不会去 registry 找。
     *
     * ⚠️ 用户关掉后**不删**这一行 —— 它是包的存在声明，不是启用开关。
     */
    private fun registerPresetRazorBundle() {
        val manifest = File(dshHome, "profiles/$PROFILE_NAME/package.json")
        val marker = File(dshHome, PRESET_RAZOR_MARKER)
        if (!File(presetRazorDir(), "package.json").isFile) {
            Log.w(TAG, "内置预设包不存在，跳过 bundle 登记: ${presetRazorDir().absolutePath}")
            return
        }
        if (!manifest.isFile) {
            // profile 还没初始化：官方启动时会用 PROFILE_TEMPLATES.web 建好，
            // 那时只带两个核心 bundle，下一次启动这里再补。
            Log.i(TAG, "profile 清单尚不存在，内置预设的 bundle 登记推迟到下次启动")
            return
        }
        try {
            val json = JSONObject(manifest.readText())
            val dsh = json.optJSONObject("dsh") ?: JSONObject().also { json.put("dsh", it) }
            val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            val bundles = profile.optJSONArray("bundles") ?: JSONArray().also { profile.put("bundles", it) }

            var listed = false
            for (i in 0 until bundles.length()) {
                if (bundles.optString(i) == PRESET_RAZOR_PACKAGE) { listed = true; break }
            }

            var changed = false
            if (!listed && !marker.isFile) {
                // 第一次见到它：补进名单（老用户升级上来也走这一支）
                bundles.put(PRESET_RAZOR_PACKAGE)
                changed = true
                Log.i(TAG, "已把内置预设登记为 profile bundle（默认开启，可在插件页关闭）")
            }
            if (!marker.isFile) {
                marker.parentFile?.mkdirs()
                marker.writeText("registered")
            }

            val deps = json.optJSONObject("dependencies") ?: JSONObject().also { json.put("dependencies", it) }
            if (!deps.has(PRESET_RAZOR_PACKAGE)) {
                deps.put(PRESET_RAZOR_PACKAGE, "link:${presetRazorDir().absolutePath}")
                changed = true
            }

            if (changed) manifest.writeText(json.toString(2) + "\n")
        } catch (t: Throwable) {
            // 登记失败不阻断启动：只是预设不出现在选择器里
            Log.w(TAG, "登记内置预设 bundle 失败: ${t.message}")
        }
    }

    /**
     * 清掉老版本留下的两处 razor 残留（幂等）。
     *
     * 1. `$DSH_HOME/preset-razor.patch.yml` —— 改造前每次启动被 `--patch` 挂上的
     *    主机 overlay。若还在，同一个预设会被**挂载两次**（`insert` 行不按 id 去重），
     *    registry 直接报 `Duplicate agent preset: razor` 并让其中一个挂载失败。
     * 2. `$DSH_HOME/preset-razor/` —— 改造前的解包目录（`preset-razor/dsh-preset-razor/`）。
     *    包已经搬到 `$DSH_HOME/node_modules/dsh-preset-razor/`，旧目录不再被任何东西
     *    引用；留着只会让排查时看到两份同名包、分不清哪份在生效。
     */
    private fun removeLegacyPresetRazor() {
        val overlay = File(dshHome, PRESET_RAZOR_PATCH)
        if (overlay.isFile && overlay.delete()) {
            Log.i(TAG, "已清理旧的内置预设 overlay（改由 profile bundle 挂载）")
        }
        val legacyDir = File(dshHome, PRESET_RAZOR_LEGACY_DIR)
        if (legacyDir.isDirectory && legacyDir.deleteRecursively()) {
            Log.i(TAG, "已清理旧的内置预设解包目录: ${legacyDir.absolutePath}")
        }
    }


    /**
     * 把跨会话持久记忆插件 dsh-core-memory 解到 `$DSH_HOME/core-memory/dsh-core-memory/`（幂等）。
     *
     * 与 [ensureMobileUi] / [ensureAgnesMultimodal] 同一套做法、同一套理由：包解到
     * `$DSH_HOME` 下而不是 profile 的 `node_modules`（那里归 pnpm 管，本包不在 profile
     * 的 `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`，
     * overlay 里的包名随即解析失败）。
     *
     * ⚠️ **`lib/persona.js` 必须一起解出来**：`lib/index.js` 用相对路径
     * `from './persona.js'` 导入它（宿主半的回复风格逻辑全在那里）。少这一个文件，
     * 插件在 `import` 阶段就 `ERR_MODULE_NOT_FOUND`，宿主半整个不激活 —— 而且报错
     * 只在引擎启动日志里出现一次，界面上表现为「记忆功能完全不存在」。
     *
     * 幂等判据是版本戳而不是「文件在不在」：插件随 APK 升级而变，只看文件存在
     * 会让老设备永远停在旧版。
     */
    private fun ensureCoreMemory() {
        val target = coreMemoryDir()
        val clientJs = File(target, "lib/client.js")
        val stamp = File(target, CORE_MEMORY_STAMP)

        if (clientJs.isFile && clientJs.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == CORE_MEMORY_VERSION
        ) {
            return
        }

        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(CORE_MEMORY_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(CORE_MEMORY_VERSION)
            Log.i(TAG, "已安装跨会话记忆插件 $CORE_MEMORY_PACKAGE $CORE_MEMORY_VERSION (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了记忆能力
            Log.w(TAG, "安装跨会话记忆插件失败: ${t.message}")
        }
    }

    /**
     * 清掉改造前解在 `$DSH_HOME/core-memory/` 的那一份（幂等）。
     *
     * 包目录从 `$DSH_HOME/core-memory/dsh-core-memory/` 搬到了
     * `$DSH_HOME/node_modules/dsh-core-memory/`（理由见 [coreMemoryDir]）。老设备上
     * 旧目录还在 —— 它不再被任何东西引用，留着只是一个永远不更新的死副本，
     * 将来排查「为什么改了插件没生效」时极易看错。
     *
     * ⚠️ 只删这一层，**不碰 `$DSH_HOME/memory/`** —— 那里面是用户真正的记忆数据，
     * 与包目录无关，关掉插件也**不该**删。
     */
    private fun removeLegacyCoreMemoryDir() {
        val legacy = File(dshHome, CORE_MEMORY_LEGACY_DIR)
        if (legacy.isDirectory && legacy.deleteRecursively()) {
            Log.i(TAG, "已清理旧位置的跨会话记忆插件目录: $CORE_MEMORY_LEGACY_DIR")
        }
    }

    /**
     * dsh-core-memory 的包目录：`$DSH_HOME/node_modules/dsh-core-memory`。
     *
     * ## 为什么是这个位置（三个候选逐个否掉后的结论）
     *
     * `loadProfileDirectory()` → `resolveBundleDir()` 解析 bundle 名时只认两个锚点：
     * dsh 安装目录、`<profileDir>/package.json`。后者走 `createRequire().resolve.paths()`
     * —— 也就是 **Node 的向上查找**：`profiles/web/node_modules` → `profiles/node_modules`
     * → `$DSH_HOME/node_modules` → …。所以放在 `$DSH_HOME/node_modules` 下能被解析到。
     *
     * | 候选 | 结论 |
     * |---|---|
     * | `profiles/web/node_modules/`（与第三方插件同级） | ❌ pnpm 会把它挪进 `.ignored/`。AGENTS.md ⑥ 的老坑，移动端 UI 插件就被这么驱逐过 |
     * | `$DSH_HOME/core-memory/`（改造前的位置） | ❌ require 向上只查到 `$DSH_HOME/node_modules`，查不到这个兄弟目录 ⇒ bundle 解析失败 |
     * | **`$DSH_HOME/node_modules/`** | ✅ 能被解析，且**不在 pnpm workspace 内**（`pnpm-workspace.yaml` 的 `packages: [.]` 只覆盖 `profiles/web`），pnpm 看不到它 |
     *
     * ⚠️ 目录名**必须**是 `dsh-core-memory`：Node 的解析是按包名找
     * `<某个 node_modules>/<包名>/package.json`，目录名对不上就找不到。
     */
    private fun coreMemoryDir(): File =
        File(dshHome, "node_modules/$CORE_MEMORY_PACKAGE")

    /**
     * 内置预设的包目录：`$DSH_HOME/node_modules/dsh-preset-razor`。
     *
     * 与 [coreMemoryDir] 同一套理由：bundle 名靠 Node 的向上查找解析，只查
     * `node_modules` 这一层；且**不能**放 `profiles/web/node_modules/`（pnpm 的地盘，
     * 会被挪进 `.ignored/`）。目录名必须是包名，解析是按
     * `<某个 node_modules>/<包名>/package.json` 找的。
     */
    private fun presetRazorDir(): File =
        File(dshHome, "node_modules/$PRESET_RAZOR_PACKAGE")

    /**
     * 把 dsh-core-memory 登记成 profile 的一个 **bundle**，让它在「插件 → 已安装插件」
     * 列表里出现、并能被用户单独开关。
     *
     * ## 为什么不再是 `--patch` overlay 里的 `- insert:`
     *
     * 改造前它是主机 overlay 的一行 `insert`。那种挂载方式**没有身份**：官方
     * plugin-manager 的列表只认 `dsh.profile.bundles`（`listBundles()`）与 Loader 里
     * 由 bundle 层贡献的行，overlay 插进来的条目不在任何一份清单里 —— 于是插件页
     * 看不到它，用户既关不掉、也不知道它存在。用户原话：「有的用户他可能不太想用
     * 这个插件」。
     *
     * 改成 bundle 之后，「关掉」= 从 `dsh.profile.bundles` 摘掉这个名字，包本身留在
     * `node_modules`、记忆数据留在 `$DSH_HOME/memory`，随时能再打开。
     *
     * ## ⚠️ 幂等判据必须「只看缺失才补」，不能「每次都补齐」
     *
     * 这是**与改造前最大的语义差别**。overlay 是 App 每次启动整体覆盖的，用户改不动；
     * 而 bundle 名单是**用户与 App 共写**的同一份文件。若写成「不在就加上」，用户
     * 在插件页关掉它、重启 App，这里又会把它加回去 —— 变成「关不掉」。
     *
     * 所以判据是「**从来没见过它**才补」：见标记文件 [CORE_MEMORY_MARKER]。
     * 一旦登记过（标记文件在），此后只在用户保留它时维持现状，绝不自动复活。
     * 用户想再打开，在插件页把它打开即可，那时的写入由官方插件管理器完成。
     *
     * ## ⚠️ `dependencies` 也要写
     *
     * `loadProfileDirectory()` 解析 bundle 名时的锚点是「dsh 安装目录」和
     * 「`<profileDir>/package.json`」。包在 `$DSH_HOME/node_modules/` 下，靠 Node 的
     * 向上查找命中；但官方 `listBundles()` 的 `installed`、`removable`、版本比较都读
     * `manifest.dependencies`，只写 `bundles` 会让这一行在插件页**根本不渲染**。
     * 依赖值用 `link:` 指向真实目录，pnpm 认得这种本地链接、也不会去 registry 找。
     */
    private fun registerCoreMemoryBundle() {
        val manifest = File(dshHome, "profiles/$PROFILE_NAME/package.json")
        val marker = File(dshHome, CORE_MEMORY_MARKER)
        if (!File(coreMemoryDir(), "package.json").isFile) {
            Log.w(TAG, "跨会话记忆插件包不存在，跳过 bundle 登记: ${coreMemoryDir().absolutePath}")
            return
        }
        if (!manifest.isFile) {
            // profile 还没初始化：官方启动时会用 PROFILE_TEMPLATES.web 建好，
            // 那时只带两个核心 bundle，下一次启动这里再补。
            Log.i(TAG, "profile 清单尚不存在，跨会话记忆插件的 bundle 登记推迟到下次启动")
            return
        }

        try {
            val json = JSONObject(manifest.readText())
            val dsh = json.optJSONObject("dsh") ?: JSONObject().also { json.put("dsh", it) }
            val profile = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            val bundles = profile.optJSONArray("bundles") ?: JSONArray().also { profile.put("bundles", it) }

            var listed = false
            for (i in 0 until bundles.length()) {
                if (bundles.optString(i, "") == CORE_MEMORY_PACKAGE) {
                    listed = true
                    break
                }
            }

            var changed = false
            if (!listed && !marker.isFile) {
                // 第一次见到它：补进名单（老用户升级上来也走这一支，见 [CORE_MEMORY_MARKER] 注释）
                bundles.put(CORE_MEMORY_PACKAGE)
                changed = true
                Log.i(TAG, "已把跨会话记忆插件登记为 profile bundle（可在插件页关闭）")
            }
            if (!marker.isFile) {
                marker.parentFile?.mkdirs()
                marker.writeText("registered")
            }

            // 依赖项：解析锚点之一，也让官方列表能算出版本与可移除性。
            // 用户关掉后**不删**这一行 —— 它是包的存在声明，不是启用开关。
            val deps = json.optJSONObject("dependencies") ?: JSONObject().also { json.put("dependencies", it) }
            if (!deps.has(CORE_MEMORY_PACKAGE)) {
                deps.put(CORE_MEMORY_PACKAGE, "link:${coreMemoryDir().absolutePath}")
                changed = true
            }

            if (changed) manifest.writeText(json.toString(2) + "\n")
        } catch (t: Throwable) {
            // 登记失败不阻断启动：记忆功能退化成「不挂载」，与包缺失同级
            Log.w(TAG, "登记跨会话记忆插件 bundle 失败: ${t.message}")
        }
    }

    /**
     * 清掉老版本留下的 dsh-core-memory 主机 overlay（幂等）。
     *
     * 改造前它是 `$DSH_HOME/core-memory.patch.yml` 里的一行 `insert`，每次启动被
     * `--patch` 挂上。现在改走 profile bundle，这份 overlay 若还在，同一个包会被
     * **挂载两次** —— `insert` 行不按 id 去重，结果是记忆工具重复注册、核心记忆在
     * 系统提示里出现两遍。
     *
     * ⚠️ 不能只在「不再生成它」就算了：老设备上那份文件**已经存在**，App 不生成
     * 不代表 `--patch` 不传它 —— 传不传由 applyOverlays 里那一串列表决定，而列表
     * 现在已不含 core-memory 这一项。但文件本身仍要删，否则它会被别的路径读到。
     */
    private fun removeCoreMemoryOverlay() {
        val stale = File(dshHome, CORE_MEMORY_PATCH)
        if (stale.isFile && stale.delete()) {
            Log.i(TAG, "已清理旧的跨会话记忆 overlay（改由 profile bundle 挂载）")
        }
    }

    /**
     * 解出插件市场（dsh-android-market）到 `$DSH_HOME/market/`。
     *
     * 与 [ensureCoreMemory] / [ensureMobileUi] 同一套做法、同一套理由：包解到
     * `$DSH_HOME` 下而不是 profile 的 `node_modules`（那里归 pnpm 管，本包不在 profile
     * 的 `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`，
     * overlay 里的包名随即解析失败）。
     *
     * ⚠️ **`lib/collect.mjs` 与 `lib/seed.json` 必须一起解出来**：宿主半
     * `lib/index.js` 用相对路径 `from './collect.mjs'` 导入采集核心，并用
     * `new URL('./seed.json', import.meta.url)` 定位离线种子。少任何一个，
     * 插件在 `import` 阶段就 `ERR_MODULE_NOT_FOUND`，宿主半整个不激活 ——
     * 报错只在引擎启动日志里出现一次，界面上表现为「设置页里没有插件市场」。
     *
     * 幂等判据是版本戳而不是「文件在不在」：插件随 APK 升级而变，只看文件存在
     * 会让老设备永远停在旧版（尤其是 `lib/seed.json` 那份离线目录）。
     */
    private fun ensureMarket() {
        val target = File(dshHome, "$MARKET_DIR/$MARKET_PACKAGE")
        val clientJs = File(target, "lib/client.js")
        val stamp = File(target, MARKET_STAMP)

        if (clientJs.isFile && clientJs.length() > 0 &&
            stamp.isFile && stamp.readText().trim() == MARKET_VERSION
        ) {
            return
        }

        try {
            target.deleteRecursively()
            java.util.zip.ZipInputStream(context.assets.open(MARKET_ASSET).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(target, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            stamp.writeText(MARKET_VERSION)
            Log.i(TAG, "已安装插件市场 $MARKET_PACKAGE $MARKET_VERSION (${clientJs.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上不阻断启动：只是少了市场，官方的「输入包名安装」照常可用
            Log.w(TAG, "安装插件市场失败: ${t.message}")
        }
    }

    /**
     * 写出挂载 dsh-android-market 的 overlay，返回其路径；不可用时返回 null。
     *
     * 与其它 `writeXxxPatch` 同构：`--patch` 在 profile 层**之后**应用，优先级
     * 更高，且完全由本 App 拥有，升级时整体覆盖即可。`name` 用 `file://` 绝对
     * URL，因为包在 pnpm 管辖之外、按裸包名解析不到。
     *
     * ⚠️ 浏览器半（设置页的「插件市场」分节）由 `dsh-client-modules` 通过 package.json
     * 的 `dsh.client` 声明 + `exports["./client"]` 自动编入，**不需要**在这里声明。
     *
     * ⚠️ 与 `plugin-manager` 那条 overlay 是**不同 id 的条目**（那边 `- id:` 覆盖 config，
     * 这边 `- insert:` 加新条目），两者不互相覆盖，所以先后顺序不影响正确性 ——
     * 调用点上把它排在 `writePluginManagerPatch()` 附近只是就近分组。
     */
    private fun writeMarketPatch(): File? {
        val patch = File(dshHome, MARKET_PATCH)
        val entry = File(dshHome, "$MARKET_DIR/$MARKET_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            Log.w(TAG, "插件市场入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载插件市场；插件包随 APK 分发，已解到 .dsh/${MARKET_DIR}。
                # 用 file:// 绝对路径挂载：包刻意放在 profile 的 node_modules 之外，
                # 否则用户每次用插件管理器装东西都会被 pnpm 挪进 .ignored/。
                - insert:
                    - id: $MARKET_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入插件市场 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 把 link()/linkat() 的语义转译层解到真实文件路径（幂等），返回其路径。
     *
     * 为什么需要：SELinux 的 `untrusted_app(_27)` 域对 `app_data_file` **没有
     * `link` 权限**（AOSP 为防沙箱数据外泄主动移除），所以 node 进程里任何
     * `link()` 都返回 EACCES。而 DSH 上游用硬链接做独占原子发布，并靠 EEXIST
     * 判定「已被别人发布」：
     *
     * ```
     * try { await link(staged, currentPath); }
     * catch (e) { if (e.code === 'EEXIST') return false; throw e; }  // ← EACCES 走这里
     * ```
     *
     * 表现就是每轮回复都以「本轮运行失败 EACCES: permission denied, link」告终。
     *
     * 策略改不了，但**不需要让 link 成功，只需要让调用方不依赖它**：在 libc 边界
     * 把 link 转译成「复制 + renameat2(RENAME_NOREPLACE) 发布」，同时保住独占
     * （目标存在→EEXIST）与保源（真 link 不消耗源文件）两个语义。详见
     * `app/src/main/jni/linkcompat.c` 的头部注释。
     *
     * 为什么解到文件而不是走 jniLibs：`LD_PRELOAD` 只接受真实文件路径，而
     * AGP 默认 `extractNativeLibs=false` 时 `nativeLibraryDir` 是空目录。本工程
     * 虽设了 `extractNativeLibs=true`，但资产化能避免 AGP 对 native 库做 strip /
     * 改名，也与 `engine.bin` / `node-runtime.bin` 的处理方式一致。
     */
    private fun ensureLinkCompat(): File? {
        val dest = File(context.filesDir, LINKCOMPAT_FILE)
        return try {
            // 每次都覆盖写：库只有 10KB，而它随 APK 升级而变，比对哈希不值当
            context.assets.open(LINKCOMPAT_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            Log.i(TAG, "已解出 link 兼容层 $LINKCOMPAT_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "解出 link 兼容层失败: ${t.message}")
            null
        }
    }

    /**
     * 解出内置号池的注入库（`keyinject.bin` → `filesDir/libkeyinject.so`）。
     *
     * 这是「给粉丝免费用、又不让他们挖到密钥」的实现载体。它经 `LD_PRELOAD`
     * 注入引擎进程，在 `SSL_write()` 加密前的最后一刻把配置里的**占位符**
     * 换成真密钥，写完立刻还原。所以：
     *
     * - 磁盘上、环境变量里、JS 能碰到的任何地方，都**只有占位符**；
     * - 粉丝对 AI 说「打印所有环境变量」或「读一下 .credentials.yaml」，
     *   拿到的都是占位符；
     * - `strings` APK 也搜不到明文密钥（资产里是混淆后的密文）。
     *
     * ⚠️ **这不是加密，是提高门槛。** 有耐心的逆向者（反汇编本库、复现 XOR）
     * 仍能还原，拿到 root 用 Frida 读内存也能拿到。它把攻击成本从「会打字」
     * 提到「会逆向」。完整的威胁模型见 `app/src/main/jni/keyinject.c` 头部。
     *
     * ⚠️ 资产不存在时**静默跳过**（返回 null，不挂 LD_PRELOAD、不写 provider
     * 配置）：那是别人 clone 之后没跑 `tools/build-keyinject.ps1` 的正常状态，
     * 此时引擎照常启动、只是没有内置号池，用户仍可自己配 provider。
     */
    private fun ensureKeyInject(): File? {
        // ⚠️ 号池改为服务端网关后，本方法**不再解出注入库**，恒返回 null。
        //
        // 保留这个函数（而不是删掉调用点）有两个原因：
        //   1. 调用点有三处（start / rescue / LD_PRELOAD 拼接），删函数要连带
        //      改多处控制流，风险大于收益；
        //   2. 老安装的 `filesDir/libkeyinject.so` 需要顺手清掉 —— 留着它虽然
        //      不会再被 LD_PRELOAD 加载，但会让人误以为注入层还在。
        //
        // 真正删掉的是：assets/keyinject.bin（不再打进 APK）、
        // app/src/main/jni/keyinject.c、tools/build-keyinject.ps1 的产物链路。
        File(context.filesDir, KEYINJECT_FILE).takeIf { it.isFile }?.let {
            if (it.delete()) Log.i(TAG, "已删除旧的号池注入库: ${it.name}")
        }
        return null
    }

    /**
     * 把内置号池并入 profile 的用户配置层（`profiles/web/cordis.patch.yml`）。
     *
     * **只有一个供应商**，但底下的 14 把钥匙分属两个站点。合并靠的是
     * `keyinject.c` 在 TLS 层当小路由：选中哪把钥匙，就把 Host 头改成那把钥匙
     * 所属站点的域名（两站同 IP、同一张通配证书，服务器按 Host 头路由）。
     *
     * 所以这里只要配**一个**路由，`baseURL` 取较长的国际站域名 —— 等长改写
     * 只能从长的往短的改（见 [KEY_BASE_URL]）。
     *
     * ## 为什么 `apiKeyEnv` 指向的是**占位符**
     *
     * `apiKeyEnv` 是**凭据引用**（credential reference），不是密钥本身：引擎每次
     * 请求都通过凭据 seam 把它解析成实际字符串，再放进 `Authorization` 头。所以
     * 这里只写引用名，实际值由 [start] 注入成环境变量 —— 而那个值是占位符。
     *
     * 占位符随请求进入 TLS 缓冲区后，由 `libkeyinject.so` 在加密前换成真密钥。
     * **整条链路上密钥只存在于 C 库的内存里**，这是本方案的全部要害。
     *
     * ## ⚠️ 为什么落在用户层，而不是 `--patch` overlay（踩过，就是本次要修的 bug）
     *
     * 老写法是 `- id: llm-pi-ai` 的 `--patch` overlay。patch 语义是**整块替换该行
     * 的 config**（不是深合并），而 overlay 在层序**最顶层** ⇒ 用户在 GUI 里新建的
     * provider 会被内置号池那一份 config 整个盖掉。于是
     * `@deepseek-ai/dsh-config-editor/lib/index.js` 的写入校验（它会把刚写下的值
     * 经**完整层序含 overlay**重新 compose 一遍再比对）直接抛：
     *
     * ```
     * Configuration for "llm-pi-ai" is overridden by a home patch or command-line overlay
     * ```
     *
     * 用户看到的就是「一添加第三方 API 就报错」。所以内置值必须落在**用户层** ——
     * 那正是 GUI 自己写入的那份文件，格式一致、语义一致（同 [seedDefaultSettings]）。
     *
     * ⚠️ 也不能给号池另开一行同名插件：`dsh-llm-pi-ai` 的 `apply()` 会**无条件**
     * 声明 pi-ai 内置目录的 41 个 provider，第二个实例必然撞
     * `DUPLICATE_DIRECTORY`（`configurable provider "anthropic" is already declared`）
     * ⇒ 整个目录注册失败、模型列表空掉。详见 [KEY_POOL_NS]。
     *
     * ⚠️ 与 [seedDefaultSettings] 的「只播种一次」**刻意不同**：内置号是产品能力
     * 的一部分，用户删掉它就等于弄丢了号池，所以每次启动都要确保它在。但**只补缺**
     * （见 [mergeKeyPoolProvider]），已有 `agnes:` 时一个字节都不动。
     */
    /**
     * 删掉 `$DSH_HOME/.credentials.yaml` 里那条**老用户手填的**内置号池密钥。
     *
     * ## 症状
     *
     * 升级后界面显示「API 密钥无效」，而 API 地址已经是网关地址。设置页里密钥
     * 输入框提示「留空使用已保存凭据」，**看不到**「已检测到环境变量」。
     *
     * ## 根因
     *
     * `dsh-credentials-local` 的 `describe()` 优先级是：
     *
     * ```
     * values（.credentials.yaml）→ inherited（父进程环境变量）→ dotenv 兜底
     * ```
     *
     * 老版本要求用户在设置页里手填 Agnes 真密钥，那份值被写进 `.credentials.yaml`。
     * 新版本改用环境变量 `AGNES_CREDENTIAL`（值是网关令牌），但**文件层优先**，
     * 于是 `Authorization` 头带的仍是用户当年手填的**旧真密钥** ——
     * 打到现在指向的网关地址上，网关不认识它，回 401 ⇒ 界面「API 密钥无效」。
     *
     * 这正是「留空使用已保存凭据」与「已检测到环境变量」互斥的原因：
     * 前者说明 `source === "file"`，后者说明 `source === "env"`。老用户是前者。
     *
     * ## 为什么可以安全删除
     *
     * `AGNES_CREDENTIAL` 是**内置号池专用的引用名**（见 [KEY_ENV]），
     * 用户自己的第三方 provider 用各自的引用名，不受影响。
     * 而且网关令牌永远由 [start] 注入成环境变量 —— 文件里那条只会造成遮蔽。
     *
     * ⚠️ **只删这一条**。整份文档原样保留（注释、其它条目、格式都不动）：
     * 用户可能同时配了别的 provider，粗暴重写等于毁他数据。
     *
     * ⚠️ 只认「值不是网关令牌」的条目。若用户手动填的就是网关令牌，留着无害。
     */
    private fun dropStaleBuiltinCredential() {
        val file = File(dshHome, ".credentials.yaml")
        if (!file.isFile) return
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            Log.w(TAG, "读取 .credentials.yaml 失败: ${t.message}")
            return
        }

        // 找 `AGNES_CREDENTIAL:` 那一行（顶层键，允许引号/注释/空行）。
        // 只处理顶层缩进为 0 的行，避免误伤嵌套结构。
        val lines = text.lines().toMutableList()
        val idx = lines.indexOfFirst { line ->
            val t = line.trimStart()
            line.indexOfFirst { it != ' ' } == 0 &&
                t.startsWith("$KEY_ENV:") &&
                !t.startsWith("#")
        }
        if (idx < 0) return

        val raw = lines[idx].substringAfter("$KEY_ENV:").trim()
        // 去掉行尾注释与引号，取出真正的值
        val value = raw
            .substringBefore(" #")
            .trim()
            .trim('\'', '"')
        if (value.isEmpty()) return
        if (value == KEY_PLACEHOLDER) return   // 已经是网关令牌，无需处理

        // 还有别的条目就只删这一行，否则整个文件只剩这一条 ⇒ 删文件更干净
        val remaining = lines.filterIndexed { i, line ->
            i != idx && !(line.isBlank() || line.trimStart().startsWith("#"))
        }
        try {
            if (remaining.isEmpty()) {
                if (file.delete()) Log.i(TAG, "已删除只含旧号池密钥的 .credentials.yaml")
            } else {
                val body = lines.filterIndexed { i, _ -> i != idx }.joinToString("\n")
                file.writeText(body.trimEnd('\n') + "\n")
                Log.i(TAG, "已从 .credentials.yaml 移除旧的 $KEY_ENV 条目")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "清理 .credentials.yaml 失败: ${t.message}")
        }
    }

    private fun ensureKeyPoolProvider(profile: String = PROFILE_NAME): Boolean {
        // 老版本把内置号池写成 --patch overlay（keyinject.patch.yml）。那个写法会
        // 在最顶层整块替换 `llm-pi-ai` 那行，把用户自己的 provider 一起盖掉 ——
        // 见 [KEY_POOL_NS]。这份残留**必须无条件删掉**（与号池资产在不在无关）：
        // 它本身就是那个 bug，留着它「一加第三方 API 就报错」就还在。
        File(dshHome, KEYINJECT_PATCH).takeIf { it.isFile }?.let {
            if (it.delete()) Log.i(TAG, "已删除旧的内置号池 overlay: ${it.name}")
        }
        // ⚠️ 号池改为服务端网关后，不再依赖本地注入库，所以这里**不再**有
        // 「没有 libkeyinject.so 就跳过」的短路。旧逻辑的因果已经不存在了 ——
        // 现在只要网关可用，号池就该在（原参数 `inject: File?` 已随之删除）。

        val patch = File(dshHome, "profiles/$profile/$PROFILE_PATCH_FILENAME")
        val existing = try {
            if (patch.isFile) patch.readText() else ""
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile 用户配置失败: ${t.message}")
            return false
        }
        val updated = mergeKeyPoolProvider(existing)
        if (updated == existing) {
            Log.i(TAG, "内置号池已在 profile 用户配置里: ${patch.absolutePath}")
            return true
        }
        return try {
            patch.parentFile?.mkdirs()
            patch.writeText(updated)
            Log.i(TAG, "已把内置号池并入 profile 用户配置: ${patch.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "写入内置号池失败: ${t.message}")
            false
        }
    }

    /**
     * 把内置号池那一行并进 profile 用户配置文本（纯函数，便于离线验证）。
     *
     * 判据是「`llm-pi-ai` 那一行的 `providers:` 下有没有 `agnes:` 键」：
     *
     * - 整行不存在 ⇒ 追加完整的一行；
     * - 有行但没有 `providers:` ⇒ 在该行块内补出 `config.providers`；
     * - 有 `providers:` 但没有 `agnes:` ⇒ 在 `providers:` 之后插入内置块；
     * - 已有 `agnes:` ⇒ **一个字节都不动**（用户/引擎写下的值原样保留）。
     *
     * ⚠️ 为什么只做「补缺」而不整块重写：这一行是 `dsh-config-editor` 实际读写
     * 的那份文档，用户每次在 GUI 里改模型设置都会被它整体重写。我们只在缺失时
     * 补一笔，才既能保证内置号池始终在，又不会覆盖用户自己加的 provider。
     */
    private fun mergeKeyPoolProvider(body: String): String {
        val lines = body.lines().toMutableList()
        val idIndex = lines.indexOfFirst { isEntryIdLine(it, KEY_POOL_NS) }

        if (idIndex < 0) {
            // 这一行还不存在。⚠️ 必须先摘掉空模板的 `[]` —— 留着它会变成
            // `[]` 后面又跟一个 entry，是**非法 YAML**（一个文档不能既是流式
            // 空数组又是块序列）。注释原样保留，YAML 允许注释出现在数组之前。
            // 只认**顶层**的 `[]`（引擎模板就长这样），免得误删别处的空数组值。
            lines.indexOfFirst { it.isNotBlank() && it.indexOfFirst { c -> c != ' ' } == 0 && it.trim() == "[]" }
                .takeIf { it >= 0 }?.let { lines.removeAt(it) }
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty()) lines.add("")
            lines.addAll(keyPoolEntryYaml(0).trimEnd('\n').lines())
            return lines.joinToString("\n") + "\n"
        }

        // 缩进不写死：GUI（js-yaml）与用户手写的缩进都照各自的来。
        val idIndent = lines[idIndex].indexOfFirst { it != ' ' }.coerceAtLeast(0)
        val child = idIndent + 2

        // 该 entry 的块范围：到下一个同级或更浅的项为止。
        var end = lines.size
        for (i in idIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.indexOfFirst { it != ' ' } <= idIndent) {
                end = i
                break
            }
        }

        /**
         * 块内缩进恰好为 [indent] 的 `key:` 行下标，找不到给 -1。
         *
         * ⚠️ **必须容忍「行内值」形态**（`key: {}` / `key: []`），不能只认裸 `key:`。
         *
         *   踩过的坑（真机崩溃，见下）：引擎把 schema 的默认值**序列化进文件**，
         *   `dsh-llm-pi-ai` 的 `Config` 是
         *   `providers: z.dict(profile).default({})` —— 于是用户只要在 GUI 设置页
         *   碰过一次「模型」，写下的那份 `cordis.patch.yml` 里就是 `providers: {}`
         *   而不是裸 `providers:`。
         *
         *   旧实现用 `line.trim() == key` 精确相等 ⇒ **看不见 `providers: {}`** ⇒
         *   判定「没有 providers」⇒ 再插一个 `providers:` ⇒ 同一个 mapping 里出现
         *   两个 `providers` 键 ⇒ 引擎 `failed to parse overlay` 抛在顶层、
         *   **启动即崩**（`YAMLException: duplicated mapping key`）。
         *
         *   这条路径特别毒：全新安装的机器上文件是空的、插进去就是对的，
         *   只有**已经用过设置页的老用户**才会踩到 —— 典型的「新装测不出」。
         *
         * `key:` 之后必须跟空白（或行尾），免得 `providers:` 把 `providersExtra:`
         * 这种更长的键名也命中。
         */
        /**
         * 本次调用有没有真的改过 [lines]。
         *
         * ⚠️ 只有它能区分「一个字节都没动」与「动过但结果恰好等于按原样处理」。
         *   踩过的坑：自愈分支（见 [dropDuplicateEmptyKeys]）先把重复键删了，
         *   随后走到「`agnes` 已有 retryPolicy」那一支，那里 `return body` 返回的
         *   是**入参原文**，于是刚删掉的重复键又被原样吐回去 —— 自愈白做，
         *   用户的砖机照旧崩。所以「无需再补」时也必须返回 [lines]（若它被改过）。
         */
        var dirty = false

        fun findKey(key: String, indent: Int, from: Int): Int {
            for (i in from until end) {
                val line = lines[i]
                if (line.isBlank()) continue
                val trimmed = line.trim()
                if (line.indexOfFirst { it != ' ' } == indent &&
                    (trimmed == key || trimmed.startsWith("$key "))
                ) {
                    return i
                }
            }
            return -1
        }

        /**
         * 把 `key: <行内值>` 收敛成裸 `key:`，返回「这一行现在能不能挂子键」。
         *
         * ⚠️ 光让 [findKey] 认出 `providers: {}` 还不够：`{}` 是**流式空映射**，
         *   在它下面缩进写 `agnes:` 同样是非法 YAML（标量值后面不能接块内容）。
         *   所以认出来之后必须把行内值摘掉，把这一行还原成「块映射的开头」。
         *
         * 语义等价：`providers: {}` 就是「空映射」，摘成 `providers:` 再挂上
         * `agnes:` 得到的就是「只含 agnes 的映射」—— 正是我们要的结果，
         * 而 `{}` 里本来就没有任何东西可以丢。
         *
         * ⚠️ **但只对「空集合」等价的写法做这件事**（`{}` / `[]` / `null` / `~`）。
         *   非空行内映射（`providers: {deepseek: {...}}`，用户手写或别的工具写的）
         *   摘掉就是**把用户的 provider 配置整行抹掉** —— 那是数据丢失，
         *   比「号池这一版没并进去」严重得多。这种情况返回 false，
         *   由调用方**放弃本次插入**（宁可不动，也不毁用户数据）。
         *
         * 已经是裸 `key:` 时返回 true 且一个字节都不动。
         */
        fun openKeyBlock(index: Int, key: String, indent: Int): Boolean {
            val trimmed = lines[index].trim()
            if (trimmed == key) return true
            val inline = trimmed.removePrefix(key).trim()
            if (inline !in EMPTY_INLINE_VALUES) return false
            lines[index] = " ".repeat(indent) + key
            dirty = true
            return true
        }

        fun insert(at: Int, text: String) {
            val added = text.trimEnd('\n').lines()
            lines.addAll(at, added)
            // ⚠️ [end] 是「该 entry 的块范围」，插入点一定在 [at] <= end 之内，
            //   所以块范围只是往后平移，不能直接写成 lines.size（那会把范围
            //   扩到整个文件末尾，后续 findKey 会越界到别的 entry 里去）。
            end += added.size
            dirty = true
        }

        /**
         * 删掉同一缩进下**多余的、且值写成行内空集合**的同名键行 —— 自愈旧版写坏的文件。
         *
         * ⚠️ 为什么非有不可：上面那个 `findKey` 盲区在 0.2.8 里**已经把一批用户写成了砖**。
         *   他们文件里现在是
         *   ```
         *   config:
         *     providers:        ← 旧版插的（含 agnes）
         *       agnes: {...}
         *     providers: {}     ← 引擎原本写的，重复键
         *   ```
         *   光修 `findKey` 只能防住**新**的写入；对这份已坏文件，`findKey` 会命中
         *   **第一个** `providers:`（它下面确实有 agnes）⇒ 判定「已经并好了」⇒
         *   `return body` ⇒ 重复键原样留着 ⇒ **引擎每次启动照样崩**。
         *   也就是说：不写这段自愈，老用户的砖机永远修不好，只能卸载重装（清空
         *   整个 `$DSH_HOME`：会话、凭据、工作区全没）。这正是最不能接受的结局。
         *
         * ⚠️ **只删「行内空集合」形态的行**（`{}` / `[]` / `null` / `~`）。
         *   非空值的重复键一个都不碰 —— 那种文件本来就是我们判断不了的用户内容，
         *   宁可留着让它继续报错（用户至少能从报错里看见自己的东西），也不静默乱删。
         *   我们实际写坏的那一行恰好就是 `{}`（引擎的默认值序列化），所以够用。
         *
         * 从后往前删，[keep] 之前的索引不受影响；[end] 同步收缩。
         */
        fun dropDuplicateEmptyKeys(key: String, indent: Int, keep: Int) {
            var removed = 0
            for (i in end - 1 downTo keep + 1) {
                if (i >= lines.size) continue
                val line = lines[i]
                if (line.isBlank() || line.indexOfFirst { it != ' ' } != indent) continue
                val trimmed = line.trim()
                if (!trimmed.startsWith("$key ")) continue
                if (trimmed.removePrefix(key).trim() !in EMPTY_INLINE_VALUES) continue
                lines.removeAt(i)
                removed++
            }
            // 删掉的都在块范围内，所以块范围等量收缩（同样不能写成 lines.size）。
            end -= removed
            if (removed > 0) dirty = true
        }

        /** 无需再补时的返回：改过就必须给出 [lines]，绝不能把入参原文吐回去。 */
        fun unchangedOrHealed(): String =
            if (dirty) lines.joinToString("\n") else body

        val configIndex = findKey("config:", child, idIndex + 1)
        if (configIndex < 0) {
            // 行在、但没有 config（只有 id + name）：只补 config 子树。
            // ⚠️ 不能再输出一遍 `name:` —— YAML 重复键是非法文档（踩过）。
            insert(end, keyPoolConfigBody(child))
            return lines.joinToString("\n")
        }
        // ⚠️ 从这里开始会改 [lines]。三处 [openKeyBlock] 里任何一处判定「不能安全收敛」
        //   （碰到非空行内映射，摘了就是抹用户配置）都必须**整份回滚**：
        //   只收敛了 `config:` 却没插成 `providers:`，会把用户文件从 `config: {}`
        //   改成 `config:`（空块）—— 语义未必等价，而 KDoc 承诺「不动就一个字节不动」。
        //   宁可这一版不并号池，也不能留下半成品改动。
        val snapshot = lines.toList()
        fun abandon(): String {
            lines.clear()
            lines.addAll(snapshot)
            dirty = false
            return body
        }

        // 同样是行内值盲区：`config: {}` 也得先收敛成块开头，
        // 否则下面挂 `providers:` 是非法 YAML。见 [findKey] / [openKeyBlock]。
        if (!openKeyBlock(configIndex, "config:", child)) return abandon()
        val providersIndex = findKey("providers:", child + 2, configIndex + 1)
        if (providersIndex < 0) {
            insert(configIndex + 1, " ".repeat(child + 2) + "providers:")
            insert(configIndex + 2, keyPoolAgnesYaml(child + 4))
            return lines.joinToString("\n")
        }
        // 引擎会把默认值序列化成 `providers: {}`。识别出来之后必须把 `{}` 摘掉，
        // 否则在它下面缩进写 `agnes:` 仍是非法 YAML（标量后不能接块）。
        // 见 [findKey] 与 [openKeyBlock] 的注释。
        if (!openKeyBlock(providersIndex, "providers:", child + 2)) return abandon()
        // 自愈旧版写坏的文件：同一层里重复的 `providers: {}` 要删掉，否则引擎照样崩。
        dropDuplicateEmptyKeys("providers:", child + 2, providersIndex)
        // 已有内置号池：默认一个字节都不动（用户/引擎写下的值原样保留）。
        //
        // ⚠️ **但有一个例外：`retryPolicy` 缺失时必须补进去**（踩过）。
        //   只做「有 agnes: 就整块跳过」的话，**所有老安装永远拿不到新策略** ——
        //   它们的 `agnes:` 块在上一版就写好了，`mergeKeyPoolProvider` 一看存在
        //   就直接 return body，于是「抽到坏钥匙自动换一把」这个修复对存量用户
        //   **完全不生效**，只对全新安装生效。这是最容易漏的一类 bug：代码看着对、
        //   新装的机器上测也对，就是老用户没反应。
        //
        //   补的范围**严格限定在 `retryPolicy:` 这一个键**：只在它整个子树缺失时
        //   插一笔，已有则一个字节都不动（用户手改过的值照样保留）。
        val agnesIndex = findKey(KEY_PROVIDER + ":", child + 4, providersIndex + 1)
        if (agnesIndex < 0) {
            insert(providersIndex + 1, keyPoolAgnesYaml(child + 4))
            return lines.joinToString("\n")
        }
        // `agnes: {}` 同理（行内值盲区）：不收敛成块开头就没法往下挂 `retryPolicy:`。
        // 非空行内映射（用户手写的 `agnes: {...}`）则放弃整次改动，绝不抹掉它。
        if (!openKeyBlock(agnesIndex, "$KEY_PROVIDER:", child + 4)) return abandon()
        // 同一盲区在 agnes 层同样写过重复键（文件里有 `agnes: {}` 时旧版会再插一个）。
        dropDuplicateEmptyKeys("$KEY_PROVIDER:", child + 4, agnesIndex)

        // `agnes:` 块的范围（到下一个同级或更浅的键为止），用于在它内部找键。
        var agnesEnd = end
        for (i in agnesIndex + 1 until end) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.indexOfFirst { it != ' ' } <= child + 4) {
                agnesEnd = i
                break
            }
        }
        // 判据同样必须容忍行内值：`retryPolicy: {}` 也算「已经有」——
        // 用精确相等会把已有的认成缺失，再插一个就是重复键（同一个坑的第三次）。
        val hasRetryPolicy = (agnesIndex + 1 until agnesEnd).any { i ->
            val line = lines[i]
            val trimmed = line.trim()
            line.isNotBlank() && line.indexOfFirst { it != ' ' } == child + 6 &&
                (trimmed == "retryPolicy:" || trimmed.startsWith("retryPolicy: "))
        }
        // ── baseURL 迁移：老安装必须改到网关地址 ──────────────────────────
        //
        // ⚠️ 与上面 `retryPolicy` 完全同一类 bug，只是这次漏的是 baseURL。
        //
        //   号池从「客户端 TLS 注入 + 直连上游」改成「服务端网关」后，
        //   `agnes:` 块里的 `baseURL` 必须从 `api.agnes-ai.cn` /
        //   `apihub.agnes-ai.cn` 换成 [KEY_BASE_URL]。
        //
        //   但 `mergeKeyPoolProvider` 的既定语义是「已有 agnes: 就一个字节都不动」，
        //   于是**所有老安装升级后仍然指向旧域名**：注入库已经删了，占位符不再被
        //   替换成真密钥，请求带着 `sk-agnes-…` 令牌直连 Agnes 上游 ⇒ 上游回 401
        //   ⇒ 客户端显示「API 密钥无效」。全新安装反而是对的，典型的
        //   「新装测不出、老用户全炸」。
        //
        //   纠偏范围**严格限定在 baseURL 这一个键**，且**只认已知的旧值**：
        //   - 命中旧直连域名 ⇒ 改成网关地址（这是本次迁移的唯一目的）
        //   - 已是网关地址   ⇒ 不动
        //   - 其它任意值     ⇒ 不动（用户自己填的第三方地址，绝不可覆盖）
        var baseUrlFixed = false
        val baseUrlIndent = child + 6
        for (i in agnesIndex + 1 until agnesEnd) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.indexOfFirst { it != ' ' } != baseUrlIndent) continue
            val trimmed = line.trim()
            if (!trimmed.startsWith("baseURL:")) continue
            val value = trimmed.removePrefix("baseURL:").trim().trim('\'', '"')
            if (value in LEGACY_KEY_BASE_URLS) {
                lines[i] = " ".repeat(baseUrlIndent) + "baseURL: '$KEY_BASE_URL'"
                dirty = true
                baseUrlFixed = true
                Log.i(TAG, "内置号池 baseURL 迁移: $value -> $KEY_BASE_URL")
            }
            break
        }
        // ⚠️ 不能在这里 `return`：baseURL 改了之后，retryPolicy 可能**同样缺失**
        //    （老安装两样都缺）。早退会让「换号重试」这个修复对老用户失效，
        //    与上面 retryPolicy 注释里踩过的坑完全一样。继续往下走。
        if (hasRetryPolicy) {
            return if (baseUrlFixed) lines.joinToString("\n") else unchangedOrHealed()
        }

        insert(agnesIndex + 1, keyPoolRetryPolicyYaml(child + 6))
        return lines.joinToString("\n")
    }

    /**
     * 确保 profile 的用户配置层里写着「默认模型 = 内置 Agnes」（老安装补缺用）。
     *
     * [DEFAULT_SETTINGS_PATCH] 里虽然已经带了这一项，但那份只在「用户从未动过设置」
     * 时播种一次 —— 而 [ensureKeyPoolProvider] 会往同一个文件里补内置号池，于是
     * **任何装过一次的机器**都不再是空模板，播种被跳过、永远拿不到这个默认值。
     * 已发出去的 0.1.x / 0.2.0 全是这种状态，所以这一支是**必须**的。
     *
     * 判据与 [ensureKeyPoolProvider] 一致：**只补缺，不覆盖**。行已存在就一个字节
     * 都不动 —— 那意味着用户在设置页里选过模型，属于用户意图。
     */
    private fun ensureDefaultModel(profile: String = PROFILE_NAME) {
        val patch = File(dshHome, "profiles/$profile/$PROFILE_PATCH_FILENAME")
        val existing = try {
            if (patch.isFile) patch.readText() else ""
        } catch (t: Throwable) {
            Log.w(TAG, "读取 profile 用户配置失败: ${t.message}")
            return
        }
        val updated = mergeDefaultModel(existing)
        if (updated == existing) {
            Log.i(TAG, "默认模型已在 profile 用户配置里（或用户已自选）: ${patch.absolutePath}")
            return
        }
        try {
            patch.parentFile?.mkdirs()
            patch.writeText(updated)
            Log.i(TAG, "已把默认模型设为内置 $KEY_PROVIDER: ${patch.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "写入默认模型失败: ${t.message}")
        }
    }

    /**
     * 把「默认模型」那一行并进 profile 用户配置文本（纯函数，便于离线验证）。
     *
     * ⚠️ 判据比 [mergeKeyPoolProvider] 简单得多：**只看那一行在不在**。
     *   因为 profile 的用户 patch 层里本来就没有这一行（引擎的出厂值在 bundle 层，
     *   不在这个文件里），所以「行存在」⇔「用户或本 App 写过它」。
     *   行在就原样保留 —— 用户改过的模型选择必须留住。
     */
    private fun mergeDefaultModel(body: String): String {
        val lines = body.lines().toMutableList()
        if (lines.any { isEntryIdLine(it, DEFAULT_MODEL_NS) }) return body

        // 与 mergeKeyPoolProvider 同一处理：先摘掉空模板的顶层 `[]`，否则会变成
        // `[]` 后面又跟一个 entry —— **非法 YAML**（一个文档不能既是流式空数组
        // 又是块序列）。注释原样保留，YAML 允许注释出现在数组之前。
        lines.indexOfFirst { it.isNotBlank() && it.indexOfFirst { c -> c != ' ' } == 0 && it.trim() == "[]" }
            .takeIf { it >= 0 }?.let { lines.removeAt(it) }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (lines.isNotEmpty()) lines.add("")
        lines.addAll(defaultModelEntryYaml(0).trimEnd('\n').lines())
        return lines.joinToString("\n") + "\n"
    }

    /** 默认模型那一行的 YAML，[indent] 为整行基准缩进。 */
    private fun defaultModelEntryYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}- id: $DEFAULT_MODEL_NS")
            appendLine("$pad  name: '$DEFAULT_MODEL_PACKAGE'")
            appendLine("$pad  config:")
            appendLine("$pad    provider: $KEY_PROVIDER")
            appendLine("$pad    model: ${KEY_MODELS.first()}")
        }
    }

    /** [mergeKeyPoolProvider] 的判据：这一行是不是 `- id: <ns>`。 */
    private fun isEntryIdLine(line: String, ns: String): Boolean {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("-")) return false
        val rest = trimmed.removePrefix("-").trimStart()
        if (!rest.startsWith("id:")) return false
        return rest.removePrefix("id:").trim().trim('\'', '"') == ns
    }

    /** 完整的一行（`- id:` + 内容），[indent] 为整行基准缩进。 */
    private fun keyPoolEntryYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            append(pad)
            appendLine("- id: $KEY_POOL_NS")
            append(keyPoolEntryBody(indent + 2))
        }
    }

    /** 一行 entry 除 `- id:` 之外的部分（`name` + `config`），[indent] 为 `name:` 的缩进。 */
    private fun keyPoolEntryBody(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}name: '@deepseek-ai/dsh-llm-pi-ai'")
            append(keyPoolConfigBody(indent))
        }
    }

    /** 只有 `config` 子树（用于「行已存在但没写 config」的情况），[indent] 为 `config:` 的缩进。 */
    private fun keyPoolConfigBody(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}config:")
            appendLine("${pad}  providers:")
            append(keyPoolAgnesYaml(indent + 4))
        }
    }

    /** 内置号池那一个 provider 的 YAML 块，[indent] 为 `agnes:` 的缩进。 */
    private fun keyPoolAgnesYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}$KEY_PROVIDER:")
            appendLine("$pad  displayName: '$KEY_PROVIDER_NAME'")
            appendLine("$pad  api: openai-completions")
            appendLine("$pad  baseURL: '$KEY_BASE_URL'")
            appendLine("$pad  apiKeyEnv: $KEY_ENV")
            append(keyPoolRetryPolicyYaml(indent + 2))
            appendLine("$pad  models:")
            for (m in KEY_MODELS) {
                appendLine("$pad    - id: $m")
                // 声明输入模态，否则默认 ["text"] ⇒ 图片在附加前就被拒（见 [KEY_MODEL_INPUT]）。
                appendLine("$pad      input:")
                for (modality in KEY_MODEL_INPUT) {
                    appendLine("$pad        - $modality")
                }
                // ⚠️ 刻意**不声明** `off` 档，理由见 [KEY_EFFORT_LEVELS]。
                appendLine("$pad      reasoningEfforts:")
                for (e in KEY_EFFORT_LEVELS) {
                    appendLine("$pad        '$e': $e")
                }
            }
        }
    }

    /**
     * 号池的重试策略块，[indent] 为 `retryPolicy:` 的缩进。
     *
     * ⚠️ **这是「抽到坏钥匙自动换一把」的全部实现**，别删。
     *
     * 号池每次请求都在 TLS 层随机换一把钥匙（见 `keyinject.c` 的 `SSL_write`），
     * 而池子里**确实有死钥匙**（实测 `cn.87` 稳定返回 403「检测到账号存在异常
     * 活动」）。没有这份策略时，那一次请求的 403 会被引擎归类成 `AUTH`
     * （`dsh-llm-pi-ai` 的 `classifyPiAiError`：`/\b(?:401|403)\b/` → `"AUTH"`），
     * 而 `AUTH` **不在** `DEFAULT_RETRYABLE_CODES` 里
     * （默认只有 EMPTY_RESPONSE / RATE_LIMIT / SERVER / TIMEOUT / TRANSPORT），
     * 于是 `dsh-llm-retry` 直接放行不重试 ⇒ **报错砸到用户脸上**。
     * 概率是 1/池子大小，池子越大越容易撞上，属于「偶尔抽风」的体验杀手。
     *
     * 配上这份策略后：403 → `AUTH` ∈ `retryableCodes` ⇒ 重发请求 ⇒ `SSL_write`
     * 重新随机抽一把钥匙 ⇒ 绝大多数情况第二次就通了，**用户完全无感**。
     *
     * ⚠️ `retryableCodes` 是**整体替换**默认值而不是追加
     * （`resolveRetryPolicy`：`config.retryableCodes ?? [...DEFAULT_RETRYABLE_CODES]`），
     * 所以默认那五个必须**原样再列一遍**，漏掉哪个就等于把那类错误的重试关掉了。
     *
     * ⚠️ 只对号池这个 provider 生效（写在 `agnes:` 块里），用户自己加的 provider
     * 一个字节都不受影响。
     *
     * ⚠️ `mode: normal` + `maxRetries: 3`：真撞上一整片死钥匙时最多重试 3 次就
     * 如实报错，不会无限重发。
     */
    private fun keyPoolRetryPolicyYaml(indent: Int): String {
        val pad = " ".repeat(indent)
        return buildString {
            appendLine("${pad}retryPolicy:")
            appendLine("$pad  mode: normal")
            appendLine("$pad  maxRetries: 3")
            appendLine("$pad  retryableCodes:")
            for (code in KEY_RETRYABLE_CODES) {
                appendLine("$pad    - $code")
            }
        }
    }

    /**
     * 解出 uid 2000 执行通道（`dsh-android-shell.mjs`，每次启动覆盖写）。
     *
     * 该脚本直连本机 adbd（`127.0.0.1:5555`）走 ADB 协议认证，从而以 shell
     * 身份（uid 2000）执行命令 —— 这是 `screencap` / `input` / `pm uninstall` /
     * `settings` / `getevent` 等操作唯一可行的路径，app 域全部做不到
     * （详见 `assets/dsh-android-shell.mjs` 头部的实测对照表）。
     *
     * ⚠️ 没有密钥时**不报错**：脚本自己会回落为本地执行（app 域），
     * 也就是「授权了走 shell，没授权走普通的」。所以这里返回 null 只是
     * 让引擎不挂 overlay，而不是失败。
     */
    private fun ensurePrivilegedShell(): File? {
        val dest = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        return try {
            // 每次都覆盖写：脚本 20KB 且随 APK 升级而变，比对哈希不值当
            context.assets.open(PRIVILEGED_RUNNER_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            Log.i(TAG, "已解出 uid 2000 执行通道 $PRIVILEGED_RUNNER_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            // 解不出来只是没有特权通道，不该拖垮引擎启动
            Log.w(TAG, "解出 uid 2000 执行通道失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 uid 2000 特权工具插件（`shell-tool.zip` → `$DSH_HOME/shell-tool/dsh-android-shell/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `shell` 的**独立工具**，它永远以 uid 2000
     * 执行；`bash` 工具则永远保持 app 身份。两者的机制与取舍见插件源码头部注释
     * （`mobile-ui-plugin/dsh-android-shell/lib/index.js`）。
     *
     * ⚠️ **为什么解到 `$DSH_HOME/shell-tool/` 而不是 profile 的 `node_modules`**：
     * 与移动端 UI 插件同一个坑 —— 那个目录归 pnpm 管，而本包不在 profile 的
     * `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`。
     * 详见 [ensureMobileUi]。
     *
     * 与移动端 UI 插件不同，这里**不做版本戳**：`lib/index.js` 每次启动覆盖写，
     * 逻辑与 linkcompat/boosandbox 那类「每次覆盖」的资产一致 —— 包很小（单文件），
     * 比对哈希不值当。
     */
    private fun ensurePrivilegedPlugin() {
        val target = File(dshHome, "$SHELL_TOOL_DIR/$SHELL_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(SHELL_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装 uid 2000 特权工具插件 $SHELL_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 shell 工具，不该拖垮引擎启动
            Log.w(TAG, "安装 uid 2000 特权工具插件失败: ${t.message}")
        }
    }

    /**
     * 解出 root 执行通道（`dsh-android-root.mjs`，每次启动覆盖写）。
     *
     * 与 [ensurePrivilegedShell] 同构，差别只在身份：那条走 adbd 拿 uid 2000，
     * 这条走 `su -c` 拿 uid 0。**不返回 null 表示「有 root」** —— 那件事由
     * [RootAccess] 单独探测，本方法只负责把脚本落盘。
     *
     * ⚠️ 与 `shell` 通道的关键差别：**root 通道不回落**。脚本自己会在拿不到
     *   uid 0 时以专属退出码 77/78 失败，而不是静默降级成 app 身份。
     *   理由见 `assets/dsh-android-root.mjs` 头部。
     */
    private fun ensureRootShell(): File? {
        val dest = File(context.filesDir, ROOT_RUNNER_FILE)
        return try {
            context.assets.open(ROOT_RUNNER_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setReadable(true, false)
            Log.i(TAG, "已解出 root 执行通道 $ROOT_RUNNER_FILE (${dest.length()} 字节)")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "解出 root 执行通道失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 root 特权工具插件（`root-tool.zip` → `$DSH_HOME/root-tool/dsh-android-root/`）。
     *
     * 结构、放置位置与不做版本戳的理由与 [ensurePrivilegedPlugin] 完全一致
     * （⚠️ 同样**不能**放进 profile 的 `node_modules` —— 会被 pnpm 挪走）。
     */
    private fun ensureRootPlugin() {
        val target = File(dshHome, "$ROOT_TOOL_DIR/$ROOT_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(ROOT_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装 root 特权工具插件 $ROOT_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            Log.w(TAG, "安装 root 特权工具插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载 root 特权工具的 overlay，返回其路径；不可用时返回 null。
     *
     * @param available 是否**确实能拿到 uid 0**（由 [RootAccess.detect] 端到端探测得出，
     *   不是「存在 su 文件」）。不可用时把上次留下的 overlay 删掉 —— 与
     *   [writePrivilegedPatch] 同理：留着一个指向不存在文件的 overlay 只会制造噪音，
     *   更糟的是让模型以为这台设备有 root。
     */
    private fun writeRootPatch(available: Boolean): File? {
        val patch = File(dshHome, ROOT_TOOL_PATCH)
        val entry = File(dshHome, "$ROOT_TOOL_DIR/$ROOT_TOOL_PACKAGE/lib/index.js")
        if (!available || !entry.isFile) {
            if (patch.isFile) patch.delete()
            if (!available) Log.i(TAG, "未检测到可用 root，跳过挂载 root 工具")
            else Log.w(TAG, "root 特权工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载 root 特权工具插件：注册一个独立的 `root` 工具，
                # 由模型按需选择；`bash` 与 `shell` 都不受影响。
                - insert:
                    - id: $ROOT_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入 root 特权工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入 root 特权工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出悬浮球控制工具插件（`bubble-tool.zip` → `$DSH_HOME/bubble-tool/dsh-android-bubble/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `bubble` 的**独立工具**，模型可以据此
     * 改悬浮窗的状态、大小、形状、配色、面板与头像 —— 也就是把原先只有**页面侧**
     * 插件够得着的那条能力，接到模型的工具列表里。
     *
     * ⚠️ **为什么解到 `$DSH_HOME/bubble-tool/` 而不是 profile 的 `node_modules`**：
     * 与 [ensureMobileUi] / [ensurePrivilegedPlugin] 同一个坑 —— 那个目录归 pnpm 管，
     * 而本包不在 profile 的 `dependencies` 里，用户每装一次插件就会被挪进
     * `node_modules/.ignored/`。
     *
     * 与 `shell-tool` 一样**不做版本戳**：包很小（单文件），每次启动覆盖写。
     */
    private fun ensureBubbleTool() {
        val target = File(dshHome, "$BUBBLE_TOOL_DIR/$BUBBLE_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(BUBBLE_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装悬浮球控制工具插件 $BUBBLE_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 bubble 工具，不该拖垮引擎启动
            Log.w(TAG, "安装悬浮球控制工具插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载悬浮球控制工具的 overlay，返回其路径；入口不存在时返回 null。
     *
     * 与 [writePrivilegedPatch] / [writeRootPatch] 同构。**没有「能力探测」这一步**：
     * 悬浮球不需要任何特权（用户开了开关并授权悬浮窗就够），所以只要文件在就挂载 ——
     * 真正能不能显示由 `FloatingBubble` 自己在运行时判断，工具照常返回它读到的状态。
     */
    private fun writeBubbleToolPatch(): File? {
        val patch = File(dshHome, BUBBLE_TOOL_PATCH)
        val entry = File(dshHome, "$BUBBLE_TOOL_DIR/$BUBBLE_TOOL_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "悬浮球控制工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载悬浮球控制工具插件：注册一个独立的 `bubble` 工具，
                # 让模型能改悬浮窗的状态/大小/形状/配色/头像。
                - insert:
                    - id: $BUBBLE_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入悬浮球控制工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入悬浮球控制工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出系统栏配色工具插件（`statusbar-tool.zip` → `$DSH_HOME/statusbar-tool/dsh-android-statusbar/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `statusbar` 的**独立工具**，让模型能改
     * **状态栏（顶部）**与**导航栏（底部）**的配色 —— 也就是把原先只有 App 自己
     * （页面底色驱动）够得着的那两条栏，接到模型的工具列表里。
     *
     * ⚠️ **为什么解到 `$DSH_HOME/statusbar-tool/` 而不是 profile 的 `node_modules`**：
     * 与 [ensureBubbleTool] 同一个坑 —— 那个目录归 pnpm 管，而本包不在 profile 的
     * `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`。
     *
     * 与 `bubble-tool` 一样**不做版本戳**：包很小（两个文件），每次启动覆盖写。
     */
    private fun ensureStatusBarTool() {
        val target = File(dshHome, "$STATUSBAR_TOOL_DIR/$STATUSBAR_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(STATUSBAR_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装系统栏配色工具插件 $STATUSBAR_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 statusbar 工具，不该拖垮引擎启动
            Log.w(TAG, "安装系统栏配色工具插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载系统栏配色工具的 overlay，返回其路径；入口不存在时返回 null。
     *
     * 与 [writeBubbleToolPatch] 同构。**没有「能力探测」这一步**：改系统栏配色
     * 不需要任何特权或授权，只要文件在就挂载。
     */
    private fun writeStatusBarToolPatch(): File? {
        val patch = File(dshHome, STATUSBAR_TOOL_PATCH)
        val entry = File(dshHome, "$STATUSBAR_TOOL_DIR/$STATUSBAR_TOOL_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "系统栏配色工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载系统栏配色工具插件：注册一个独立的 `statusbar` 工具，
                # 让模型能改状态栏（顶部）与导航栏（底部）的配色。
                - insert:
                    - id: $STATUSBAR_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入系统栏配色工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入系统栏配色工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出 UI 调试工具插件（`uiagent-tool.zip` → `$DSH_HOME/uiagent-tool/dsh-android-uiagent/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `ui` 的**独立工具**，让模型能枚举界面上的
     * 可交互元素（带屏幕精确坐标）、点击、输入、滚动、读文本 —— 也就是把「操作并
     * 观察这个 App 的界面」接到模型的工具列表里。真实执行者是 [UiProbeBridge]
     * 与页面探针 `assets/ui-probe.js`。
     *
     * ⚠️ **必须解到 `$DSH_HOME/uiagent-tool/` 而不是 profile 的 `node_modules`**：
     *   与 [ensureBubbleTool] 同一个坑 —— 那个目录归 pnpm 管，而本包不在 profile
     *   的 `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`。
     *
     * 与 `shell-tool` / `bubble-tool` 一样**不做版本戳**：包很小（两个文件），
     * 每次启动覆盖写。
     */
    private fun ensureUiTool() {
        val target = File(dshHome, "$UI_TOOL_DIR/$UI_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(UI_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装 UI 调试工具插件 $UI_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 ui 工具，不该拖垮引擎启动
            Log.w(TAG, "安装 UI 调试工具插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载 UI 调试工具的 overlay，返回其路径；入口不存在时返回 null。
     *
     * 与 [writeBubbleToolPatch] 同构。**没有「能力探测」这一步**：ui 工具不需要
     * 任何特权（只要界面加载出来了就能用），所以文件在就挂载 —— 真正能不能用
     * 由 [UiProbeBridge] 在运行时判断（`probeReady`），工具照常返回它读到的状态。
     */
    private fun writeUiToolPatch(): File? {
        val patch = File(dshHome, UI_TOOL_PATCH)
        val entry = File(dshHome, "$UI_TOOL_DIR/$UI_TOOL_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "UI 调试工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载 UI 调试工具插件：注册一个独立的 `ui` 工具，
                # 让模型能枚举/点击/输入/滚动这个 App 的界面（带屏幕精确坐标）。
                - insert:
                    - id: $UI_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入 UI 调试工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入 UI 调试工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出**浏览器工具**插件（`browser-tool.zip` → `$DSH_HOME/browser-tool/dsh-android-browser/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `browser` 的**独立工具**，让模型能：
     * 打开网页、读页面结构（可见文本 + 带编号的可交互元素）、点按钮、填表单、
     * 截图、管理标签页。真实执行者是 [com.dsh.android.browser.BrowserBridge]
     * 与 [com.dsh.android.browser.AiBrowserView]。
     *
     * ⚠️ **必须解到 `$DSH_HOME/browser-tool/` 而不是 profile 的 `node_modules`**：
     *   与 [ensureBubbleTool] 同一个坑 —— 那个目录归 pnpm 管，而本包不在 profile
     *   的 `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`。
     *
     * 与其它工具插件一样**不做版本戳**：包很小（两个文件），每次启动覆盖写。
     */
    private fun ensureBrowserTool() {
        val target = File(dshHome, "$BROWSER_TOOL_DIR/$BROWSER_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(BROWSER_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装浏览器工具插件 $BROWSER_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 browser 工具，不该拖垮引擎启动
            Log.w(TAG, "安装浏览器工具插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载浏览器工具的 overlay，返回其路径；入口不存在时返回 null。
     *
     * 与 [writeUiToolPatch] 同构。**没有「能力探测」这一步**：浏览器不需要
     * 任何特权，只要插件入口在就挂载 —— 浏览器实例由
     * [com.dsh.android.MainActivity.ensureAiBrowser] 在模型第一次调用时按需创建。
     */
    private fun writeBrowserToolPatch(): File? {
        val patch = File(dshHome, BROWSER_TOOL_PATCH)
        val entry = File(dshHome, "$BROWSER_TOOL_DIR/$BROWSER_TOOL_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "浏览器工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载浏览器工具插件：注册一个独立的 `browser` 工具，
                # 让模型能用自己的 WebView 上网、登录、读页面、点按钮。
                - insert:
                    - id: $BROWSER_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入浏览器工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入浏览器工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出**日志工具**插件（`logs-tool.zip` → `$DSH_HOME/logs-tool/dsh-android-logs/`）。
     *
     * 该插件在 `apply()` 里注册一个名为 `logs` 的**独立工具**，让模型能：
     * 读最近日志（可按级别/来源/关键字筛）、列崩溃现场并读全文、看一条摘要、
     * 生成诊断包。
     *
     * ## 为什么必须由原生侧供数
     *
     * 模型跑在 **Node 进程**里，而日志的权威副本有两处它都够不着：
     * ① 原生侧的内存环形缓冲（`LogStore.entries()`，最新、同步入队）；
     * ② `filesDir/logs/` 下的文件与崩溃现场（`crash/`）。
     * 它虽然能读文件系统（同域、共享目录），但**「文件在哪、叫什么、怎么筛」**
     * 这套知识不该让模型去猜 —— 猜错的表现是「日志里没有」，而它看起来像查过了。
     *
     * ⚠️ **为什么解到 `$DSH_HOME/logs-tool/` 而不是 profile 的 `node_modules`**：
     *   与 [ensureBubbleTool] 同一个坑 —— 那个目录归 pnpm 管，而本包不在 profile 的
     *   `dependencies` 里，用户每装一次插件就会被挪进 `node_modules/.ignored/`。
     *
     * 与其它工具插件一样**不做版本戳**：包很小（两个文件），每次启动覆盖写。
     */
    private fun ensureLogsTool() {
        val target = File(dshHome, "$LOGS_TOOL_DIR/$LOGS_TOOL_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(LOGS_TOOL_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装日志工具插件 $LOGS_TOOL_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是没有 logs 工具，不该拖垮引擎启动
            Log.w(TAG, "安装日志工具插件失败: ${t.message}")
        }
    }

    /**
     * 写出挂载日志工具的 overlay，返回其路径；入口不存在时返回 null。
     *
     * 与 [writeBubbleToolPatch] / [writeUiToolPatch] 同构。
     */
    private fun writeLogsToolPatch(): File? {
        val patch = File(dshHome, LOGS_TOOL_PATCH)
        val entry = File(dshHome, "$LOGS_TOOL_DIR/$LOGS_TOOL_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "日志工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载日志工具插件：注册一个独立的 `logs` 工具，
                # 让模型能读运行日志 / 看崩溃现场 / 生成诊断包。
                - insert:
                    - id: $LOGS_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入日志工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入日志工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 写出**定向隔离层** `$DSH_HOME/quarantine.patch.yml`（L1.5 的执行端）。
     *
     * 内容 = [RescueBoot.tier] 的 `quarantinedNames` 里每个条目一条
     * `- id: <id>` + `disabled: true`。**没有隔离项时删掉该文件并返回 null** ——
     * 留一个空层会让 `--patch` 多传一个空文件，且下次排查时「有文件」会被误读成
     * 「隔离过东西」。
     *
     * 幂等：每次启动整体覆盖，与其它 overlay 同构。
     *
     * ⚠️ **不能**把这里做成「扫描并删掉坏插件」：删文件是不可逆的，
     * 而用户要的是「App 能开」而不是「我的插件没了」。禁用层可一键撤销
     * （[RescueBoot.clearQuarantine]）。
     *
     * @return 写好的文件；无隔离项或写入失败时 null（失败时**不**挂这一层，
     *   退化成「本轮没隔离」，下次启动重试 —— 绝不让写文件失败变成新的启动失败点）。
     */
    private fun writeQuarantinePatch(): File? {
        val patch = File(dshHome, QUARANTINE_PATCH)
        val names = RescueBoot.tier().quarantinedNames
        if (names.isEmpty()) {
            if (patch.isFile) patch.delete()
            return null
        }
        return try {
            val body = buildString {
                appendLine("# 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。")
                appendLine("# 定向隔离：这些插件条目上次把引擎弄到起不来，本轮禁用它们。")
                appendLine("# 文件一个没删 —— 想放回来见 rescue/state.json 的 quarantinedNames。")
                for (n in names) {
                    appendLine("- id: $n")
                    appendLine("  disabled: true")
                }
            }
            patch.writeText(body)
            Log.i(TAG, "已写入定向隔离 overlay: ${patch.name}（${names.size} 项: ${names.joinToString()}）")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入定向隔离 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 安装期**语法预检**：把「一眼就是坏的」插件挡在挂载之前。
     *
     * ## 为什么需要它（不是锦上添花）
     *
     * L1 二分的代价是 `log2(N)` 次**真实启动**，每次最长 [PROBE_TIMEOUT_MS]
     * （60 秒），而总预算只有 [ISOLATION_BUDGET_MS]（3 分钟）。真机那个装了
     * **40 个插件**的用户：`log2(40) ≈ 6` 次探测 × 20~60 秒 ⇒ **必然超预算**，
     * 二分在收敛到具体插件之前就放弃，然后落 L2（全部插件不生效）。
     *
     * 而「语法坏了」是**本地可判**的，一次读取就能定位，不需要启动任何进程。
     * 把它前置，等于把最常见的那类故障从「6 次真实启动」降到「0 次」。
     *
     * ## 判据刻意保守（这一条是实测定的，不是设计出来的）
     *
     * 只认两类**结构性**损坏：引号/模板字符串未闭合、块注释未闭合。外加文件为空。
     *
     * ⚠️ **第一版还数了括号配平，在真实文件上误判 5/10 个正常插件** ——
     *   因为 JS 的**正则字面量**合法地含括号与引号（`/^[a-z0-9_-]{1,24}$/`、`/"/g`）。
     *   加上正则识别后括号也能算对，但仍然不划算：多一条判据就多一份误杀风险，
     *   而引号/块注释这两类已经覆盖「文件被截断」「粘贴漏引号」这两个最常见坏法，
     *   且对全部现有插件文件**零误报**（13 个真实文件 + 13 个构造用例实测）。
     *
     * ⚠️ **宁可放过，不可错杀**：误判会把一个好插件禁掉，用户看到「插件没生效」
     *   却查不出原因 —— 比不检更糟。所以读文件失败、编码异常等**一律放行**。
     *
     * ## 为什么不用 `node --check`
     *
     * 那需要拿得到可执行的 node 并起一个进程。本方法在 `start()` 里被调用，
     * 此时 node 二进制**可能还没解包完**（[TermuxBootstrap.installNodeRuntime]
     * 之后才有），而且为一个 200 行的文件起一次 node 进程在低端机上要 300ms+，
     * 乘 40 个插件就是十几秒 —— 与它要省下的时间相抵。
     *
     * @param entry 插件入口（`lib/index.js`）。
     * @return true = 看着没问题（或无法判断），照常挂载；false = 明显坏了，跳过。
     */
    private fun preflightPluginSyntax(entry: File): Boolean {
        val text = try {
            entry.readText()
        } catch (t: Throwable) {
            // 读不了不是「坏了」—— 可能是权限/编码问题，交给引擎自己判。
            Log.w(TAG, "插件语法预检：读取失败，放行（${entry.parentFile?.name}）: ${t.message}")
            return true
        }
        if (text.isBlank()) {
            Log.w(TAG, "插件语法预检：入口文件为空，跳过挂载（${entry.parentFile?.name}）")
            return false
        }

        // 逐字符扫描，跳过字符串 / 注释 / **正则字面量**，只判两件事：
        // 「引号未闭合」与「块注释未闭合」。
        //
        // ⚠️ **刻意不数括号**（第一版数了，在真实文件上误判 5/10 个正常插件）。
        //   原因是 JS 的**正则字面量**里合法地含括号与引号，例如
        //   `/^[a-z0-9_-]{1,24}$/`、`/"/g`。不处理正则就会把它们当代码括号，
        //   于是**完全正常的插件被判坏**。而误杀的后果比不检严重得多
        //   （用户看到插件莫名不生效、还查不出原因）。
        //   引号/块注释这两类判据经实测对全部现有插件文件**零误报**，
        //   而它们恰好覆盖「文件被截断」「粘贴时漏了引号」这两种最常见的坏法。
        //
        // 状态机用「代码区栈」表达：栈底永远是 CODE，进入模板字符串时压入
        // 反引号，模板里的 `${` 再压一层 CODE，`}` 弹回模板层。
        val CODE = '\u0000'
        val stack = ArrayDeque<Char>()
        stack.addLast(CODE)
        var i = 0
        val n = text.length
        var inLineComment = false
        var inBlockComment = false
        // 正则字面量状态
        var inRegex = false
        var inRegexClass = false
        // 上一个有意义的代码字符，用来判断 `/` 是除号还是正则的开始
        var prev = ' '
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            val top = stack.last()

            if (inLineComment) {
                if (c == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inRegex) {
                when {
                    c == '\\' -> { i += 2; continue }
                    c == '[' -> inRegexClass = true
                    c == ']' -> inRegexClass = false
                    c == '/' && !inRegexClass -> {
                        inRegex = false
                        i++
                        while (i < n && text[i].isLetter()) i++
                        prev = '/'
                        continue
                    }
                }
                i++
                continue
            }
            // 模板字符串内部
            if (top == '`') {
                when {
                    c == '\\' -> { i += 2; continue }
                    c == '`' -> { stack.removeLast(); i++; prev = '`'; continue }
                    c == '$' && next == '{' -> { stack.addLast(CODE); i += 2; continue }
                    else -> { i++; continue }
                }
            }
            // 普通字符串内部
            if (top == '\'' || top == '"') {
                when {
                    c == '\\' -> { i += 2; continue }
                    c == top -> { stack.removeLast(); i++; prev = top; continue }
                    else -> { i++; continue }
                }
            }

            // 代码区
            when {
                c == '/' && next == '/' -> { inLineComment = true; i += 2; continue }
                c == '/' && next == '*' -> { inBlockComment = true; i += 2; continue }
                // `/` 出现在「不是值结尾」的位置 ⇒ 正则字面量的开始。
                // 值结尾 = 标识符/数字/`)`/`]`/`}`/引号，那些位置上的 `/` 是除号。
                c == '/' && (prev == ' ' || prev in "=(,:[!&|?{};+-*%<>~^") -> {
                    inRegex = true
                    inRegexClass = false
                    i++
                    continue
                }
                c == '\'' || c == '"' || c == '`' -> { stack.addLast(c); i++; prev = c; continue }
                c == '}' -> if (stack.size > 1) stack.removeLast()
            }
            if (!c.isWhitespace()) prev = c
            i++
        }
        // 只有 CODE 之外的**引号**才算未闭合；模板层残留也是未闭合。
        val unclosedQuotes = stack.drop(1)

        val problems = ArrayList<String>()
        if (unclosedQuotes.isNotEmpty()) problems += "引号/模板未闭合（${unclosedQuotes.size} 处）"
        if (inBlockComment) problems += "块注释未闭合"
        if (problems.isEmpty()) return true
        Log.w(
            TAG,
            "插件语法预检：疑似损坏，跳过挂载（${entry.parentFile?.parentFile?.name}）：" +
                problems.joinToString("；"),
        )
        return false
    }

    /**
     * 解出桌面宠物的**状态桥**插件（`pet-bridge.zip` → `$DSH_HOME/pet-bridge/dsh-android-pet/`）。
     *
     * 该插件订阅引擎的 `session/event`（`@deepseek-ai/dsh-session` 声明的 post-commit
     * 事件流），把「AI 现在在干什么」翻译成运行状态写进 `$DSH_HOME/bubble-state.json`，
     * 由 [BubbleCommandBridge] 读走喂给悬浮球 —— 悬浮球据此换动作，成为桌面宠物。
     *
     * ## 为什么不并进 `dsh-android-bubble`
     *
     * 两者生命周期完全不同：[BUBBLE_TOOL_PACKAGE] 只在**模型调用 `bubble` 工具**时
     * 被用到，而这个桥**每一轮回复都在跑**。并在一起会让「只想让 AI 改外观」的用户
     * 也长期挂着一个事件订阅者，而且 `inject: ['tools']` 是工具插件需要的、状态桥
     * 不需要 —— 多声明的服务就是多一个"引擎没它就整个不激活"的机会。
     *
     * 与悬浮球工具插件一样**不做版本戳**：包很小（两个文件），每次启动覆盖写。
     */
    private fun ensurePetBridge() {
        val target = File(dshHome, "$PET_BRIDGE_DIR/$PET_BRIDGE_PACKAGE")
        val entry = File(target, "lib/index.js")
        try {
            context.assets.open(PET_BRIDGE_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(target, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            Log.i(TAG, "已安装桌面宠物状态桥插件 $PET_BRIDGE_PACKAGE (${entry.length()} 字节)")
        } catch (t: Throwable) {
            // 装不上就只是宠物不会自己动，不该拖垮引擎启动
            Log.w(TAG, "安装桌面宠物状态桥插件失败: ${t.message}")
        }
    }


    /**
     * 写出挂载状态桥的 overlay，返回其路径；入口不存在时返回 null。
     *
     * ⚠️ **与悬浮球工具是两条独立 overlay，不是一个**：那条走 [writeBubbleToolPatch]，
     *   缺了它模型仍然能用 `bubble` 工具；这条缺了则宠物不会自己动。合在一起的话
     *   任一侧失败会让另一侧也失效，而两者的失败原因毫无关系。
     */
    private fun writePetBridgePatch(): File? {
        val patch = File(dshHome, PET_BRIDGE_PATCH)
        val entry = File(dshHome, "$PET_BRIDGE_DIR/$PET_BRIDGE_PACKAGE/lib/index.js")
        if (!entry.isFile) {
            if (patch.isFile) patch.delete()
            Log.w(TAG, "桌面宠物状态桥入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载桌面宠物状态桥：订阅 session/event，把运行状态写进
                # ${'$'}DSH_HOME/bubble-state.json，让悬浮球按状态换动作。
                - insert:
                    - id: $PET_BRIDGE_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入桌面宠物状态桥 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入桌面宠物状态桥 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 解出无线调试用的 adb 工具链（`adb-wireless-arm64.zip` → `filesDir/adbwire/`）。
     *
     * ## 为什么需要一个 adb 二进制

     *
     * Android 11 起有「无线调试」，它和 legacy `adb tcpip 5555` 是两回事：
     * 端口随机、走 TLS、**重启后依然有效**（legacy 的在 Android 10 上重启即失效）。
     * 后者才是「免 PC」的关键。
     *
     * 但配对（pairing）走 **SPAKE2 + TLS-PSK**，而 Node 的 `tls` 模块**不支持
     * external PSK**，自己实现等于手写密码学。所以直接内置 Termux 官方
     * `android-tools` 里的 `adb`，复用 Google 自己的实现。
     *
     * ## 为什么带一堆 .so
     *
     * 那个 adb 是动态链接的，NEEDED 里有 `libprotobuf` / `libfmt` / `libabsl_*`
     * / `libbrotli*` / `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`。
     * 它的 RUNPATH 指向 Termux 的 `/data/data/com.termux/files/usr/lib`（本 App 里
     * 不存在），所以必须把库一并带上，并由 runner 设 `LD_LIBRARY_PATH`。
     *
     * ⚠️ **这里的 `LD_LIBRARY_PATH` 只作用于 adb 这一个子进程**，与引擎进程
     * 那处「刻意不设 LD_LIBRARY_PATH」的结论不冲突 —— 那条结论针对的是**引擎进程
     * 全局**设置该变量会污染系统二进制（见 [runEngine] 里的长注释）。adb 是自带
     * 全套依赖的自洽单元，且只跑它自己。
     *
     * ⚠️ **两个库必须按 SONAME 改名**：bootstrap 里是 `libz.so.1.3.2` 与
     * `libzstd.so.1.5.7`，而 adb 找的是 `libz.so.1` / `libzstd.so.1`。
     * Android 的 SELinux 不给 app 私有目录建符号链接，所以打包时就直接放了
     * SONAME 名字的副本（见资产构建步骤）。
     *
     * ⚠️ **`libc++_shared.so` 必须用与 adb 同期的那份**（来自 `libc++` 包），
     * 不能用 bootstrap 里那份：bootstrap 的是 9 月版，缺
     * `_ZNSt6__ndk113__hash_memoryEPKvm`，加载时报
     * `cannot locate symbol ... referenced by .../bin/adb`。
     *
     * @return adb 所在目录，失败给 `null`。
     */
    private fun ensureAdbWireless(): File? {
        val dest = File(context.filesDir, ADB_WIRELESS_DIR)
        val adb = File(dest, "bin/adb")
        // ⚠️ 幂等判据用**完整性哨兵**，不是「bin/adb 在不在」。
        //
        // 这套工具链有 107 个条目 / 5.26MB，解包要好几秒 —— 而启动过程中进程被系统
        // 杀掉、用户强退、磁盘满，都会**中途停下**。若判据只看 `bin/adb`，而 zip 的
        // 条目顺序恰好让 `bin/adb` 落在前面（实际就是这样），那么一次中断就会留下
        // 「adb 在、lib/* 缺失」的半成品，且此后**每次启动都判定为已完成**并跳过 ——
        // 于是这台设备上的无线调试**永久**坏掉，还查不出原因（解包日志早就滚走了）。
        //
        // 哨兵在**全部条目写完之后**才落盘，所以「哨兵存在」= 解包完整走完过一次。
        // 老安装没有哨兵，会多解一次（一次性代价），换来此后不会再锁死半成品。
        val sentinel = File(dest, ADB_WIRELESS_STAMP)
        if (adb.isFile && adb.length() > 0 && sentinel.isFile) {
            adb.setExecutable(true, false)
            File(dest, "tmp").mkdirs()
            File(dest, "home").mkdirs()
            return dest
        }
        if (adb.isFile && adb.length() > 0 && !sentinel.isFile) {
            Log.w(TAG, "无线调试 adb 工具链缺少完整性标记（疑似上次解包中断），重新解出")
        }
        try {
            context.assets.open(ADB_WIRELESS_ASSET).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val out = File(dest, e.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
            // ⚠️ 从 APK 资产解出来的文件**没有执行位**，必须显式设，
            // 否则报 EACCES 而不是「找不到」。
            adb.setExecutable(true, false)
            File(dest, "tmp").mkdirs()
            File(dest, "home").mkdirs()
            // 全部写完之后才落哨兵 —— 顺序不能反，否则中途失败反而留下「已完成」的假象。
            sentinel.writeText("ok\n")
            Log.i(TAG, "已解出无线调试 adb 工具链 ($ADB_WIRELESS_DIR, adb ${adb.length()} 字节)")
            return dest
        } catch (t: Throwable) {
            // 解不出来只是没有无线通道，legacy 与 app 身份都不受影响
            Log.w(TAG, "解出无线调试 adb 工具链失败: ${t.message}")
            return null
        }
    }

    /**
     * 写出挂载 uid 2000 特权工具的 overlay，返回其路径；不可用时返回 null。
     *
     * 走 `--patch` 与移动端 UI 插件同样的理由（不改用户自己的 profile 配置、
     * 优先级更高、完全由本 App 拥有）。`name` 用 `file://` 绝对 URL，因为包
     * 刻意放在 pnpm 管辖之外，按包名解析不到。
     *
     * @param available 特权通道是否可用。不可用时**不挂载** —— 插件本身在
     *   没有 `DSH_PRIV_RUNNER` 时也不会注册工具，但少挂一层就少一处噪音。
     */
    private fun writePrivilegedPatch(available: Boolean): File? {
        val patch = File(dshHome, SHELL_TOOL_PATCH)
        val entry = File(dshHome, "$SHELL_TOOL_DIR/$SHELL_TOOL_PACKAGE/lib/index.js")
        if (!available || !entry.isFile) {
            // 通道不可用：把上次可能留下的 overlay 删掉，否则引擎会挂上一个
            // 因为找不到文件而报错的模块（或者更糟，挂上旧的残留版本）。
            if (patch.isFile) patch.delete()
            if (!available) Log.i(TAG, "uid 2000 通道不可用，跳过挂载 shell 工具")
            else Log.w(TAG, "uid 2000 特权工具入口不存在，跳过挂载: ${entry.absolutePath}")
            return null
        }
        return try {
            val url = "file://${entry.absolutePath}"
            patch.writeText(
                """
                # 由 DSH Android 客户端生成，请勿手改（每次启动会整体覆盖）。
                # 挂载 uid 2000 特权工具插件：注册一个独立的 `shell` 工具，
                # 由模型按需选择；`bash` 工具不受影响，永远走 app 身份。
                - insert:
                    - id: $SHELL_TOOL_PACKAGE
                      name: '$url'
                """.trimIndent() + "\n"
            )
            Log.i(TAG, "已写入 uid 2000 特权工具 overlay: ${patch.name}")
            patch
        } catch (t: Throwable) {
            Log.w(TAG, "写入 uid 2000 特权工具 overlay 失败: ${t.message}")
            null
        }
    }

    /**
     * 首次安装时把本 App 的推荐设置播种进 profile 的用户 patch 层（幂等）。
     *
     * ## 为什么落在这里，而不是 `--patch` overlay
     *
     * 引擎的 patch 层序（`@deepseek-ai/dsh/lib/profile-boot-*.js` 的 `composeProfile`
     * 注释是权威定义）：
     *
     * ```
     * bundle 层 → profiles/web/cordis.patch.yml（用户）→ $DSH_HOME/cordis.patch.yml → --patch overlay（本 App）
     * ```
     *
     * `--patch` 在**最顶层**，放进去就盖住用户；而且
     * `@deepseek-ai/dsh-config-editor/lib/index.js` 在用户改这项设置时会直接抛
     * `Configuration for "<id>" is overridden by a home patch or command-line overlay` ——
     * 也就是「锁死」，与「用户可覆盖」的产品要求正好相反。
     *
     * 所以内置值必须落在**用户层**（`cordis.patch.yml`）：它就是 GUI 设置页
     * （`POST api/settings/mutate`）实际写入的那份文件，格式是 `id` + `name` + `config`。
     * 本方法只在用户**从未改过设置**时写一次，之后无论用户怎么改都不再碰
     * （判据见 [isEmptyProfilePatch]）。
     *
     * ## 为什么不会被引擎覆盖
     *
     * `@deepseek-ai/dsh-app-boot` 的 `initProfile()` 里是：
     *
     * ```js
     * const patchPath = join(dir, PROFILE_PATCH_FILENAME);
     * if (!existsSync(patchPath)) writeFileSync(patchPath, PROFILE_PATCH_TEMPLATE);
     * ```
     *
     * 只在缺失时写模板 `[]`，已有文件**一个字节都不动**（同函数上方的注释也写明
     * "Existing files are never touched"）。所以本方法必须跑在引擎启动**之前**。
     *
     * ## 各项取值的出处
     *
     * 全部由真机实测得到（Huawei CDY-TN90 / Android 10），不是猜的：在设置页里
     * 改一项，hook `window.fetch` 抓到 `settings/mutate` 的 payload 与落盘结果，
     * 再用 `settings/describe` 核对 schema 里的可选项与 `base`/`user` 分层。
     */
    /**
     * 确保救援 profile 存在且**可用**（P2，见 `docs/RESCUE-BOOT-PLAN.md` §4.4.1）。
     *
     * ## 为什么 App 要自己写这个 profile
     *
     * 引擎**不会**为 `rescue` 初始化 profile：`dsh-app-boot/lib/index.js` L973-975
     * 只认 `PROFILE_TEMPLATES` 里的五个内置名字，别的名字直接抛
     * `dsh: profile "rescue" does not exist; create it with 'dsh plugin --profile rescue add <package>'`。
     *
     * 而 `initProfile()`（同文件 L576）其实只需要一个 `package.json` ——
     * 它自己也是这么写的（`{name, private, dependencies:{}, dsh:{profile:{bundles}}}`）。
     * 所以 App 照同一格式写一份即可。真机实测（CDY-TN90 / Android 10）确认可用：
     * `--profile rescue --dump-config` 打出完整条目表，
     * `--profile rescue --no-open --port 0` 打出 `dsh web: http://127.0.0.1:<port>/?token=…`。
     *
     * ## ⚠️ 三条要害
     *
     * 1. **模板不能取自用户的 `profiles/web/package.json`** —— 那份文件正是
     *    可能已经坏掉的东西（用户装了 40 个插件、某个拖挂了核心插件）。
     *    拿它当模板等于把病灶复制进救援环境。所以 bundles 逐字取自引擎的
     *    `PROFILE_TEMPLATES.web.bundles`，见 [RESCUE_BUNDLES]。
     * 2. **只在文件不存在时写**（与引擎 `initProfile` 同一判据）：救援 profile
     *    一旦被写坏，用户/AI 可能正在里面修东西，每次启动覆盖会把他们的活抹掉。
     * 3. **写失败要如实报出来**：救援 profile 建不出来 ⇒ 救援启动一定失败。
     *    这时**不能**假装没事继续走救援路径，必须让调用方回落到 L3（安全模式）。
     *    返回 null 就是「建不出来」的信号。
     *
     * @return 写好的 profile 目录；null = 建不出来（调用方应回落 L3）。
     */
    private fun ensureRescueProfile(): File? {
        val dir = File(dshHome, "profiles/$RESCUE_PROFILE_NAME")
        val manifest = File(dir, "package.json")
        if (manifest.isFile) return dir

        return try {
            dir.mkdirs()
            // ⚠️ 格式逐字对齐引擎 `initProfile()` 的输出（含 2 空格缩进与结尾换行）：
            //   引擎会读回这份文件（`readProfileManifest`），格式不一致虽然多数情况
            //   仍能解析，但没有理由给自己制造差异。`name` 也用引擎的命名规则
            //   `dsh-profile-<目录名>`。
            val json = JSONObject()
                .put("name", "dsh-profile-$RESCUE_PROFILE_NAME")
                .put("private", true)
                .put("dependencies", JSONObject())
                .put(
                    "dsh",
                    JSONObject().put(
                        "profile",
                        JSONObject().put("bundles", JSONArray(RESCUE_BUNDLES)),
                    ),
                )
            // 同 [stripThirdPartyBundles] 的理由：`JSONObject` 会把 `/` 转义成 `\/`，
            // 那在 JSON 里合法但看起来像坏了。这里全是 `@deepseek-ai/...` 包名，
            // 转义后一眼就能看出不对，还原回去。
            manifest.writeText(json.toString(2).replace("\\/", "/") + "\n")
            Log.i(TAG, "已创建救援 profile: ${manifest.absolutePath}（bundles=${RESCUE_BUNDLES.joinToString(", ")}）")
            dir
        } catch (t: Throwable) {
            Log.e(TAG, "创建救援 profile 失败（救援启动将不可用）: ${t.message}")
            null
        }
    }

    private fun seedDefaultSettings(profile: String = PROFILE_NAME) {
        val profileDir = File(dshHome, "profiles/$profile")
        val patch = File(profileDir, PROFILE_PATCH_FILENAME)
        if (patch.exists() && !isEmptyProfilePatch(patch)) {
            Log.i(TAG, "profile 用户配置已存在，保留用户设置: ${patch.absolutePath}")
            return
        }
        try {
            profileDir.mkdirs()
            // ⚠️ 走原子写入：写坏一半的话，下次启动 isEmptyProfilePatch 会判定
            //   「不是空模板」从而不再播种，而引擎拿到的是一个语法坏掉的 YAML ——
            //   用户设置等于永久损坏，且不会自愈。
            if (!writeTextAtomically(patch, DEFAULT_SETTINGS_PATCH)) {
                Log.w(TAG, "默认设置未能落盘（引擎会写空模板，不影响启动）")
                return
            }
            Log.i(TAG, "已播种默认设置: ${patch.absolutePath}")
        } catch (t: Throwable) {
            // 播种失败不能挡住启动：引擎会自己写一份空的模板，只是没有默认值
            Log.w(TAG, "播种默认设置失败（不影响启动）: ${t.message}")
        }
    }

    /**
     * 判断 profile patch 是否还是引擎生成的那份**空模板**（即用户从未改过任何设置）。
     *
     * 为什么需要这一条：引擎的 `initProfile()` 会给缺失的 patch 文件补一份
     * `PROFILE_PATCH_TEMPLATE`（注释 + 一个 `[]`）。所以「跑过一次引擎但没动过
     * 设置」的安装，文件是**存在**的 —— 只看 `exists()` 会让这些用户永远拿不到
     * 出厂默认值。
     *
     * 而空模板里没有任何用户意图（全是引擎自己写的注释和空数组），把它当作
     * 「未设置过」覆盖掉是安全的；只要用户动过任何一项设置，内容就不再是空模板，
     * 本方法返回 false，用户配置被完整保留。
     *
     * 判据取「去掉注释行与空行后只剩 `[]`」，而不是比对整段文本 —— 引擎改注释
     * 文案（甚至只是换行）时不会误判成「用户改过」。
     */
    private fun isEmptyProfilePatch(patch: File): Boolean {
        val body = try {
            patch.readText()
        } catch (t: Throwable) {
            // 读不出来（权限/编码）时按「有用户内容」处理，宁可不播种也不覆盖
            Log.w(TAG, "读取 profile patch 失败: ${t.message}")
            return false
        }
        val meaningful = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("")
        return meaningful == "[]"
    }

    /**
     * 原子写入文本文件：先写同目录的临时文件，再 `renameTo` 顶上。
     *
     * ## 为什么不能直接 `writeText`
     *
     * `File.writeText()` 是「truncate 成 0 字节 → 写内容」。这两步之间**有窗口**，
     * 且在真机上真的会被打断：`start()` 跑在 `Dispatchers.IO`，用户随手把 App
     * 切走、系统因内存压力杀进程，或者写一半没空间了 —— 结果是文件只剩前一半，
     * 或者干脆 0 字节。
     *
     * 对本文件里的两处调用来说，后果分别是：
     *   · `profiles/web/cordis.patch.yml`（用户设置）写坏了 → **用户的配置丢了**，
     *     而且下次启动 `isEmptyProfilePatch` 判定它不是空模板、不会重新播种，
     *     于是引擎拿一个语法坏掉的 YAML 去启动，报错信息还指不到配置文件上；
     *   · `profile-boot` 的引擎补丁写坏了 → 引擎直接起不来。
     *
     * ## 为什么临时文件必须同目录
     *
     * `renameTo` 跨文件系统会失败（返回 false）而不是抛异常。同目录保证同一个
     * 文件系统，改名是原子操作。
     *
     * @return 成功与否。**调用方必须处理 false** —— 它意味着磁盘上仍是旧内容。
     */
    private fun writeTextAtomically(target: File, text: String): Boolean = try {
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory) dir.mkdirs()
        // 临时文件用固定后缀而不是随机名：同名残留下次会被直接覆盖，不会越积越多。
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        // 先把临时文件的内容刷到磁盘，再改名 —— 否则断电时可能改完名但内容还在页缓存里。
        // 这里刻意不 fsync：这些文件在切换到后台时本来就是「下次启动重写」的语义，
        // 而 fsync 在 Android 上会引入明显的掉帧（主线程调用时）。原子性靠 rename 保证。
        if (tmp.renameTo(target)) {
            true
        } else {
            // rename 失败（极少见，如跨挂载点）：退化成直接写。
            // 此时原子性没了，但至少内容是对的 —— 比留一个损坏的旧文件强。
            Log.w(TAG, "原子改名失败，退化为直接写入: ${target.name}")
            target.writeText(text)
            tmp.delete()
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "原子写入失败: ${target.name}: ${t.message}")
        false
    }

    /**
     * 往 `$DSH_HOME/AGENTS.md` 写入特权身份说明（幂等）。
     *
     * ## 为什么写这个文件
     *
     * `@deepseek-ai/dsh-agent-instructions` 会自动读 `$DSH_HOME/AGENTS.md`
     * （默认候选名 `AGENTS.md` / `CLAUDE.md`）并注入系统提示词 —— 这是**零代码**
     * 的提示词通道，不需要改引擎、也不需要挂 overlay。
     *
     * ⚠️ 该文件归 DSH 的指令机制所有，用户也可能自己往里写东西。所以本方法
     * **只管理自己那一段**（`BEGIN`/`END` 标记之间），标记之外的内容原样保留。
     *
     * ## 为什么必须让模型知道
     *
     * 实测（见 `assets/dsh-android-shell.mjs` 头部对照表）app 域与 uid 2000 的
     * 差异**不体现在退出码上**：`input keyevent` 在 app 域返回 **rc=0 但屏幕不动**，
     * `dumpsys battery` 在 app 域返回 **rc=0 但输出 `Can't find service`**。
     * 模型若不知道自己在哪个身份下，会基于假前提继续推理、越走越错。
     *
     * @param available 特权工具是否**真的挂上了**（overlay 写入成功，即
     *   [shellToolMounted]），而不是「runner 文件解出来了没有」。
     *
     *   ⚠️ 这两个判据差一层，而差的正是结果：runner 解出成功、但插件入口文件没解出来
     *   （或 overlay 写失败）时，`shell` 工具**根本没注册**。若此时仍告诉模型「你有
     *   uid 2000 通道，需要设备级操作就用它」，模型会去调一个不存在的工具 ——
     *   表现为它反复尝试、或者干脆改用 `bash` 去做注定失败的设备级操作，而**没有任何
     *   一条报错指向真正的原因**。判据取「挂载结果」而不是「前置产物」才是诚实的。
     */
    private fun ensurePrivilegedPrompt(available: Boolean) {
        // ⚠️ 单独打一行「通道可用=…」：AGENTS.md 的验收手法查的就是这条。
        //   它与下面 `writePromptSection` 的日志**互为补充** —— 后者只在文本
        //   真的变了时才打（幂等），所以稳态下看不到；而「这一轮到底可不可用」
        //   是每次启动都必须能查的事实。
        Log.i(TAG, "特权身份说明（uid 2000）: 通道可用=$available")
        writePromptSection(
            begin = PRIVILEGED_PROMPT_BEGIN,
            end = PRIVILEGED_PROMPT_END,
            body = if (available) PRIVILEGED_PROMPT_ENABLED else PRIVILEGED_PROMPT_DISABLED,
            label = "特权身份说明（uid 2000）",
        )
    }

    /**
     * 把「你正处于降级态、可以这样修」写进 agent 指令（方案 §4.4 的 P4）。
     *
     * ## 为什么是条件注入
     *
     * 正常态下这段整个不写（`writePromptSection` 不能删段落，所以正常态写一段
     * **空的**替代文本 —— 见下）。理由有两条：
     *
     * ① 它描述的是一个**当下成立的事实**（"你的插件被隔离了"）。正常态下这句话
     *    是假的，而把一个假前提塞进系统提示词会让模型做出错误判断 —— 那正是
     *    本项目缺陷族的形态（"结论与它描述的对象不符"）。
     * ② 它不短（约 2KB）。降级态下是值得的，正常态下纯属浪费上下文。
     *
     * ⚠️ 不能用「从没写过」来表示正常态：那样从降级态恢复后，旧文本会**永远
     *    留在 `AGENTS.md` 里**，模型此后一直以为自己处在救援模式。
     *    所以正常态要写一段**显式的否定**。
     */
    private fun ensureRescuePrompt() {
        val st = RescueBoot.tier()
        val degraded = st.tier != RescueBoot.Tier.NORMAL
        // 单独打一行：AGENTS.md 的验收手法查的就是这条（同 [ensurePrivilegedPrompt]）。
        Log.i(TAG, "降级态说明: 层级=${st.tier.id}，注入=${degraded}")
        writePromptSection(
            begin = RESCUE_PROMPT_BEGIN,
            end = RESCUE_PROMPT_END,
            body = if (degraded) {
                RESCUE_PROMPT
            } else {
                """
                ## 启动状态：正常

                本机**没有**处于降级启动模式，用户的插件配置完好。
                若用户说他打不开 App、或有插件没生效，读 `${'$'}DSH_HOME/rescue/state.json`
                与 `${'$'}DSH_HOME/rescue/last-boot-failure.json` 看有没有历史记录。
                """.trimIndent()
            },
            label = "降级态说明",
        )
    }

    /**
     * 把「这台设备有没有 root」写进 agent 指令。
     *
     * 与 [ensurePrivilegedPrompt] 同一个理由：模型必须知道自己**有没有**这个能力。
     * 不说的话它会去试 `su`（然后拿到 77 号退出码一脸茫然），或者反过来以为
     * 自己没有 root 而放弃本来做得到的事。
     *
     * ⚠️ 与 uid 2000 那段的差别：**没有 root 是常态**，所以不可用时那段文本要
     * 明确写「不要尝试绕过」，而不是像 shell 那样给一套引导步骤（root 只能由
     * 用户自己在设备上装/授权，App 帮不上忙）。
     *
     * @param available root 工具是否已注册（= 探测确认能拿到 uid 0 且 overlay 已挂）。
     */
    private fun ensureRootPrompt(available: Boolean) {
        writePromptSection(
            begin = ROOT_PROMPT_BEGIN,
            end = ROOT_PROMPT_END,
            body = if (available) ROOT_PROMPT_ENABLED else ROOT_PROMPT_DISABLED,
            label = "root 身份说明",
        )
    }

    /**
     * 把一段由本 App 拥有的说明写进 `$DSH_HOME/AGENTS.md` 的 `BEGIN`/`END` 标记之间。
     *
     * ⚠️ 该文件归 DSH 的指令机制所有，用户也可能自己往里写东西。所以**只管理
     * 自己那一段**，标记之外的内容原样保留。标记不存在时追加到末尾。
     *
     * @param begin 段落起始标记（必须逐字唯一，用作幂等判据）
     * @param end 段落结束标记
     * @param body 段落正文
     * @param label 日志里用的名字
     */
    private fun writePromptSection(begin: String, end: String, body: String, label: String) {
        val file = File(dshHome, INSTRUCTIONS_FILE)
        try {
            val existing = if (file.isFile) file.readText() else ""
            val section = buildString {
                appendLine(begin)
                appendLine(body.trimEnd())
                appendLine(end)
            }

            val at = existing.indexOf(begin)
            val atEnd = existing.indexOf(end)
            val updated = if (at >= 0 && atEnd > at) {
                // 已有本段：整体替换，保留前后用户内容
                existing.substring(0, at) + section + existing.substring(atEnd + end.length).trimStart('\n', '\r')
            } else if (existing.isBlank()) {
                section
            } else {
                existing.trimEnd() + "\n\n" + section
            }

            if (updated != existing) {
                dshHome.mkdirs()
                file.writeText(updated)
                Log.i(TAG, "已写入${label}: ${file.name}")
            }
        } catch (t: Throwable) {
            // ⚠️ 必须写 `${label}` 而不是 `$label`：中文是合法的 Kotlin 标识符字符，
            //   `"写入$label失败"` 会被解析成标识符 `label失败` → Unresolved reference。
            Log.w(TAG, "写入${label}失败: ${t.message}")
        }
    }

    /**
     * 启动前体检：跑一次 `--dump-config`，把「坏到起不来」的 profile 先修好。
     *
     * ## 为什么需要它
     *
     * profile 的 bundle 清单与 patch 层都落在 `$DSH_HOME` 下，而那是**模型可写**
     * 的目录（AI 能直接编辑 `profiles/web/` 与 `engine/`）。一旦改坏，引擎在
     * `loadProfile()` 阶段就抛错并以 `process.exit(1)` 退出，App 只会在启动页上
     * 显示「引擎启动失败」——**没有任何自救路径**，用户只能卸载重装，而卸载会清掉
     * 整个 `filesDir`（Termux 环境、会话、凭据、工作区全没）。
     *
     * ## 为什么用 `--dump-config` 当体检手段
     *
     * 引擎自带这条免 boot 的命令：它走的是**和启动完全同一套** `loadProfile()` +
     * patch 解析（`dump-config-BEDI-dNY.js` 的 `prepareProfile()`），但只解析、
     * 不求值 `!!js`、不起服务。于是：
     * - 判据来自引擎自己的解析器，不会随引擎升级而漂（**不要**用 Kotlin 重写 YAML 校验）；
     * - 真机实测耗时约 **0.7~0.8 秒**（3 次：703 / 780 / 800 ms），相对引擎自身
     *   7~10 秒的启动时间可忽略。
     *
     * ## 三种坏法，判据**不一样**（真机逐条实测，别合并成一条）
     *
     * | 坏在哪 | `dump-config` | 判据 |
     * |---|---|---|
     * | `profiles/web/package.json` 非法 JSON | RC=1 | 退出码 |
     * | `profiles/web/cordis.patch.yml` 语法坏 | RC=1 | 退出码 |
     * | 第三方 bundle 的 patch 坏 | **RC=0** | stderr 的 `skipping profile bundle` |
     * | 核心 bundle（`dsh-web-app`）的 patch 坏 | **RC=0** | **required 条目缺失** |
     *
     * ⚠️ **只看退出码会漏掉后两种**，而第四种正是「AI 改坏 `engine/` 目录」的形态：
     * 真机实测该状态下 `dump-config` 返回 **RC=0**，但真正 boot 会**挂死**
     * （45 秒超时被杀，EXIT=124，日志里 `webserver` 等服务永远 pending）。
     * 所以必须再补一道「required 条目在不在」的检查 —— 那 4 个 id 与
     * `dsh-app-boot` 的 `requiredStartupEntryIds` 同源，缺一个引擎就起不来。
     *
     * ## 隔离方式：改名不删
     *
     * 一律改成 `<原名>.bak-<时间戳>`。模型改坏的东西可能还带着用户想要的内容，
     * 而本方法只负责让 App 能打开，不该替用户销毁数据。
     *
     * @return true = 做过修复（调用方据此提示用户）；false = profile 本来就是好的。
     */
    /**
     * 启动前体检的结论。
     *
     * ⚠️ **为什么不能再用 `Boolean`**：`false` 同时表示「静态干净」和「体检压根没跑成」，
     * 而这两件事的**可信度完全不同** —— 前者至少解析过一遍配置，后者什么都不知道。
     * 调用方（以及读日志的人）看到 `false` 会以为"体检说没问题"，于是
     * 引擎接下来死掉时，第一反应是往别处查。
     *
     * 真机证据（2026-10-14，某小米机型 / Android 16 / v18，8 次连续启动失败）：
     * `--dump-config` 退出码 **0**、4 个 required 条目**全在**，体检因此放行，
     * 引擎照死。**静态干净 ≠ 已验证** —— 这就是新增 [StaticOnly] 的理由。
     *
     * ⚠️ [Clean] / [StaticOnly] 都**不代表引擎能起来**：`--dump-config` 只解析配置，
     * 不执行插件的 `apply()`，而真机上杀死引擎的异常正是在 `apply()` 期抛的。
     * 真正"能起来"的唯一证据是引擎打印了 URL（见 `start()` 里的 `engine.ready`）。
     */
    private enum class PreflightResult(val label: String) {
        /** 静态解析过了，没有可疑文件要隔离。**运行期仍未验证。** */
        Clean("静态干净"),

        /** 体检跑不了（node 缺失、profile 还没建）—— 对配置一无所知。 */
        StaticOnly("未做静态检查"),

        /** 体检发现并隔离/重解包了东西，复检通过。 */
        Repaired("已修复"),
    }

    /**
     * 启动前体检：profile 与 patch 层都是模型可写的，改坏会让引擎直接 exit 1，
     * 而 App 没有任何自救路径（用户只能卸载重装，那会清掉全部会话与凭据）。
     *
     * ⚠️ **本方法看不见插件的 `apply()` 期异常** —— 它跑的是 `--dump-config`，
     * 只解析配置。所以它的结论**不能**用来担保"引擎能起来"（见 [PreflightResult]）。
     *
     * @return 三态结论，见 [PreflightResult]。
     */
    private suspend fun preflight(): PreflightResult {
        val profileDir = File(dshHome, "profiles/$PROFILE_NAME")
        if (!profileDir.isDirectory) {
            // 还没有 profile：引擎首次启动会自己按模板初始化，没什么可体检的。
            // ⚠️ 这不是 Clean —— 一次 dump-config 都没跑过，运行期零验证。
            return PreflightResult.StaticOnly
        }

        val first = runDumpConfig()
        if (first == null) {
            // 跑不起来（node 缺失等）：体检本身失效，放行让引擎报它自己的错。
            // ⚠️ 同样不是 Clean —— 见上面那条。
            Log.w(TAG, "启动前体检无法执行，跳过（不影响启动）")
            return PreflightResult.StaticOnly
        }

        val suspects = LinkedHashSet<File>()
        if (first.exitCode != 0) {
            // 坏法①②：stderr 里有绝对路径，引擎自己指认了是哪个文件
            suspects += parseBlamedFiles(first.stderr)
            if (suspects.isEmpty()) {
                // 指认不出来（比如 package.json 的报错只有 JSON 位置、没有路径）：
                // 退到隔离 profile 自己的两个文件，这是唯一可能抛在这两处的来源
                suspects += File(profileDir, "package.json")
                suspects += File(profileDir, PROFILE_PATCH_FILENAME)
            }
        } else {
            // 坏法④：核心 bundle 被 skip 时 required 条目会消失
            val missing = missingRequiredEntries(first.stdout)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "启动前体检：核心条目缺失 ${missing.joinToString(", ")}")
                // 核心 bundle 来自 engine/，用户配置层动不了它 —— 只能整棵重解包
                return rebuildEngine(missing)
            }
            // 坏法③：退出码是 0，只能看 stderr 的跳过行
            suspects += parseSkippedBundles(first.stderr)
        }

        // 落在 engine/ 下的坏文件隔离没有意义（下次解包就覆盖了，而 `.stamp` 短路
        // 意味着没有"下次"）—— 直接整棵重解包
        val engineSide = suspects.filter { it.absolutePath.startsWith(engineDir.absolutePath) }
        if (engineSide.isNotEmpty()) {
            Log.w(TAG, "启动前体检：engine/ 内的 bundle 损坏 ${engineSide.joinToString(", ")}")
            return rebuildEngine(emptyList())
        }

        if (suspects.isEmpty()) return PreflightResult.Clean

        var quarantined = 0
        for (file in suspects) {
            if (quarantine(file, "启动前体检")) quarantined++
        }
        if (quarantined == 0) return PreflightResult.Clean

        // 复检：隔离完再跑一次，确认真的修好了
        val second = runDumpConfig()
        if (second != null && second.exitCode == 0 && missingRequiredEntries(second.stdout).isEmpty()) {
            Log.i(TAG, "启动前体检：已隔离 $quarantined 个损坏的 profile 文件，复检通过")
            return PreflightResult.Repaired
        }

        // 隔离了还不够：坏的不止那几个文件，退到整棵重解包
        Log.w(TAG, "启动前体检：隔离后复检仍未通过，改走整棵重解包")
        return rebuildEngine(emptyList())
    }

    /**
     * P6：判断「该不该**自动**进安全模式」（方案 §4.5）。
     *
     * ## 为什么要有这道门
     *
     * 安全模式此前**只能用户点**，而真机证据（那台 Xiaomi 的两个诊断包里
     * `安全模式` 出现 **0 次**，同期 `启动前体检` 出现 110 次）说明：用户根本
     * 不会点它 —— 界面在启动失败时只是停在浮层上，用户以为"再等等就好了"。
     * 于是「App 一定打得开」这条承诺在**用户不配合**时就断了。
     *
     * ## 为什么门槛定得这么高（三个条件全满足才自动进）
     *
     * L3 是**破坏性**的：推开用户的插件配置 + 删版本戳重解包（约 15 秒）。
     * 现有的克制（"拿不准就别动"）是对的，不能因为要好用就把它变成默认行为。
     * 所以要求**同时**满足：
     *
     * | # | 条件 | 为什么 |
     * |---|---|---|
     * | ① | `consecutiveFailures >= 3` | 连续 3 次真实启动失败。1~2 次可能是偶发（系统内存紧张、端口占用），不值得推开配置 |
     * | ② | `tier == RESCUE` | **L1 与 L2 都已经试过**。这是最要紧的一条：`tier` 只有在「L1 二分没收敛」时才会被升成 RESCUE（见 [maybeIsolateBadBundles] 末尾），所以 `tier == RESCUE` **本身就是**「L1 试过且失败」的机器可判证据。而在救援 profile 下再失败，等于 L2 也试过了 |
     *
     * ⚠️ 条件②**不能**写成「`tier != NORMAL`」：ISOLATED 态意味着 L1 **成功**
     *   摘掉了致命插件，那时该做的是让用户用着（哪怕少了几个插件），而不是把
     *   他剩下的插件**也**推开。判据必须精确指向「L1 已经没辙了」这个状态。
     *
     * ⚠️ 条件②同时也覆盖了「已经在安全模式里」：`SAFE_MODE != RESCUE`，所以
     *   安全模式下这道门自动关闭，不会每次启动都重解包 15 秒。
     *   **不要再补一条 `tier != SAFE_MODE`** —— 那是永远不成立的死判据
     *   （本族已 30 例：写了一条与它要拦的对象对不上的条件）。
     *
     * ⚠️ 也不能只看 `consecutiveFailures`：一个装了坏插件的用户，L1 每次都能
     *   成功隔离（`tier == ISOLATED`），失败计数会因 `clearFailure` 归零 ——
     *   那种情况**本来就不需要**安全模式。加上条件②才不会误伤。
     *
     * ## 第三种日志标记
     *
     * `[preflight]`（自动修）与用户点击（无前缀，见 [quarantine] 的 `reason`）
     * 已经分得清，本方法必须新增**第三种**，否则事后复盘会把「系统自动推开了
     * 用户的配置」误读成「用户自己点的安全模式」—— 两者对用户的责任完全不同。
     * 标记用 `[rescue-auto]`，与那两种都不撞。
     *
     * @return true = 本次确实自动进了安全模式（调用方应据此提示用户）。
     */
    internal fun shouldAutoEnterSafeMode(): Boolean {
        val f = RescueBoot.lastFailure()
        val st = RescueBoot.tier()
        val streak = f?.consecutiveFailures ?: 0

        val reasons = ArrayList<String>()
        if (streak < AUTO_SAFE_MODE_MIN_FAILURES) reasons += "连续失败仅 $streak 次（需 ≥$AUTO_SAFE_MODE_MIN_FAILURES）"
        if (st.tier != RescueBoot.Tier.RESCUE) reasons += "层级是 ${st.tier.id} 而不是 rescue（L1/L2 还没试过）"

        if (reasons.isEmpty()) {
            Log.w(TAG, "[rescue-auto] 连续 $streak 次启动失败且 L1/L2 都没能救回来，自动进入安全模式")
            EventLog.record(
                "rescue.degrade.l3",
                mapOf("consecutiveFailures" to streak.toString(), "trigger" to "auto"),
            )
            return true
        }
        Log.i(TAG, "[rescue-auto] 不自动进安全模式：${reasons.joinToString("；")}")
        return false
    }

    /**
     * 安全模式：把「模型能改坏的一切」一次性推开，让 App 必定能开起来。
     *
     * ## 它和 [preflight] 的分工
     *
     * [preflight] 是**自动**的，只在能精确指认坏文件时动手（体检判据 + 复检通过），
     * 拿不准就不碰 —— 免得把好配置误伤。但体检覆盖不到的情况真实存在：
     * 坏法超出上面四类、体检超时、或者坏在体检看不见的地方（比如 profile 的
     * `dependencies` 被改得指向一个不存在的包）。那时 App 依然打不开，而用户
     * 手上没有任何出路 —— 只能卸载重装，那会清掉全部会话与凭据。
     *
     * 本方法就是那个出路：**用户在失败浮层上点一下**，然后 App 一定开得起来。
     * 它是破坏性的、由人显式触发的，所以不需要"拿不准就别动"的克制。
     *
     * ## 推开哪三样（都不碰用户数据）
     *
     * | 推开什么 | 为什么 |
     * |---|---|
     * | profile 用户 patch 层 | 模型最爱改的就是它，且改坏会让引擎 exit 1 |
     * | 第三方 bundle（保留核心的 deepseek-ai 前缀那些） | 插件装坏 / 版本对不上是常见死因 |
     * | `engine/.stamp` | 强制重解包，把 `engine/` 里的改动全部还原 |
     *
     * ⚠️ 上面那条里**不能**写 `@deepseek-ai` 加斜杠星号：Kotlin 的块注释支持嵌套，
     * 那两字符会开始一层嵌套注释，把下面整段代码吞进注释里（编译报
     * `Missing '}'` + `Unclosed comment`，且报错行号指向完全无关的地方）。
     *
     * ⚠️ **会话、凭据、工作区都在 `$DSH_HOME` 与 `workspace/`，不在这三样里**，
     * 所以安全模式不会丢用户数据。代价只是重解包那约 15 秒。
     *
     * ⚠️ 三样都是**改名不删**（见 [quarantine]）：安全模式只负责让 App 能打开，
     * 不该替用户销毁配置 —— 用户想找回改坏前的内容，`.bak-<时间戳>` 还在那儿。
     *
     * @return true = 至少改动了一样（调用方据此提示用户）；false = 三样本来就都干净。
     */
    suspend fun enterSafeMode(): Boolean = withContext(Dispatchers.IO) {
        stop()

        val profileDir = File(dshHome, "profiles/$PROFILE_NAME")
        var changed = false

        // ① 用户配置层：模型改坏它的形态最多（语法坏、patch 目标已消失）
        if (quarantine(File(profileDir, PROFILE_PATCH_FILENAME), "安全模式")) changed = true

        // ② 第三方 bundle：只留 @deepseek-ai/* 的核心 bundle。
        //    改的是 profile 的清单，不是插件包本身 —— 包还在 node_modules 里，
        //    想装回来只要重新勾一次。
        if (stripThirdPartyBundles(profileDir)) changed = true

        // ③ 第三方插件**包本身**：把 node_modules 里非核心的目录整个挪走。
        //    ⚠️ 这一步是 ② 之外**另一道**、而且是更要紧的一道，理由见 [quarantineThirdPartyPackages]：
        //    ② 只改「清单里写没写它」，而包还在原地时，任何一条没被枚举到的加载路径
        //    （patch 层里的 insert、引擎自己的兜底扫描、后续版本新增的入口）都能把它捡回来。
        //    真机现场（OPPO PHY110）：用户点「进入安全模式」后界面**仍旧**停在
        //    `Failed to load plugins` —— 因为坏插件是 AI 手工塞进 node_modules 的，
        //    清单摘了、包还在，前端那一批合并 bundle 里照样有它的坏段。
        if (quarantineThirdPartyPackages(profileDir)) changed = true

        // ④ 引擎目录：删戳 = 下次启动重解包，把 engine/ 下的改动全部还原
        if (stampFile.isFile && stampFile.delete()) {
            Log.w(TAG, "安全模式：已删除引擎版本戳，将重新解包 engine/")
            changed = true
        }

        // ⑤ Node 运行时：上面几样都在 $DSH_HOME / engine/ 里，**都不碰 $PREFIX**，
        //    而 node 损坏会让引擎连一行 JS 都跑不到 —— 真机实测那种坏法报
        //    `Check failed: magic_number_ == SerializedData::kMagicNumber`（V8 反序列化
        //    内嵌启动快照，早于任何 JS）。不带上这一步，用户点了安全模式也照样打不开，
        //    唯一出路是卸载重装，而卸载会清掉整个 $DSH_HOME。
        //    node 可用时这一步是零成本的（一次 node --version）。
        if (bootstrap.repairNodeRuntimeIfNeeded()) {
            Log.w(TAG, "安全模式：内置 Node 运行时已重新解包")
            changed = true
        }

        // ⚠️ 必须把层级记成 SAFE_MODE，不能只做改动不记状态。两个后果都会真实发生：
        //
        //   ① **横幅会说谎**（违反不变量 I4）：`refreshRescueBanner` 读的就是这个
        //      `tier`，不记的话它仍显示「救援模式」而实际跑的是安全模式 ——
        //      又一处「结论与它描述的对象不是同一个」（本族已 30 例）。
        //   ② **P6 的自动触发会重复**：那道门的判据是 `tier == RESCUE`，
        //      不记的话它一直是 RESCUE，下次失败又自动推开一次配置（破坏性动作
        //      重复执行，且每次白等 15 秒重解包）。
        //
        //   位置在最后：`changed == false` 时也记 —— 安全模式**本来就是**「推开
        //   坏配置」，三样都干净只是说明这次没东西可推，但「用户/系统选择了安全
        //   模式」这个事实仍成立，界面必须照实显示。
        RescueBoot.setTier(
            RescueBoot.Tier.SAFE_MODE,
            "安全模式：推开损坏的配置并重新解包引擎（改动=$changed）",
        )

        Log.w(TAG, "安全模式：已推开损坏的配置（改动=$changed），准备重启引擎")
        changed
    }

    /**
     * 把 profile 清单里的**第三方** bundle 摘掉，只留 deepseek-ai 前缀的核心 bundle（幂等）。
     *
     * ⚠️ 用 `JSONObject` 解析再回写，不搞正则替换：profile 清单是 JSON，
     * 而"注释/尾逗号"这类东西在 JSON 里本来就不合法，引擎自己也读不了。
     * 解析失败（清单已经坏了）时**直接隔离整份清单** —— 引擎发现清单缺失会
     * 按模板重建一份核心的，这正是安全模式想要的结果。
     *
     * ⚠️ 回写前先备份原文件（[quarantine]），用户想恢复第三方插件时有据可依。
     *
     * @return true = 确实摘掉了东西。
     */
    private fun stripThirdPartyBundles(profileDir: File): Boolean {
        val manifest = File(profileDir, "package.json")
        if (!manifest.isFile) return false
        val json = try {
            JSONObject(manifest.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "安全模式：profile 清单已损坏（${t.message}），整份隔离")
            return quarantine(manifest, "安全模式")
        }

        val dsh = json.optJSONObject("dsh") ?: return false
        val profile = dsh.optJSONObject("profile") ?: return false
        val bundles = profile.optJSONArray("bundles") ?: return false

        val kept = ArrayList<String>(bundles.length())
        var dropped = 0
        for (i in 0 until bundles.length()) {
            val name = bundles.optString(i, "")
            // 核心 bundle 一律保留：它们来自 engine/，重解包就会还原成好的
            if (name.startsWith("@deepseek-ai/")) kept += name else dropped++
        }

        // ⚠️ `dependencies` **也要一起清**，这是「摘掉全部插件」的另一半。
        //   清单里除了 `dsh.profile.bundles`，`dependencies` 同样列着用户装的包；
        //   引擎启动时会做依赖解析，列着解析不到的包会直接报
        //   `cannot resolve profile bundle` —— 那正是「点了安全模式还进不去」的
        //   另一种成因（[enterSafeMode] 的 KDoc 里点名过这种坏法）。官方桌面端的
        //   「摘掉全部插件并重新加载」就是两处一起动的，这里照同一语义办。
        //   核心包（`@deepseek-ai/`）保留，否则 App 自己的 patch 层依赖会断。
        var depsDropped = 0
        json.optJSONObject("dependencies")?.let { deps ->
            for (key in deps.keys().asSequence().toList()) {
                if (!key.startsWith("@deepseek-ai/")) {
                    deps.remove(key)
                    depsDropped++
                }
            }
        }

        if (dropped == 0 && depsDropped == 0) return false

        // 先备份再写：摘掉的是用户装的插件，配置本身有价值。
        // ⚠️ 备份失败就**不要写** —— 否则会把唯一一份原始清单覆盖掉。
        if (!quarantine(manifest, "安全模式")) {
            Log.w(TAG, "安全模式：备份 profile 清单失败，放弃摘除第三方 bundle")
            return false
        }

        profile.put("bundles", JSONArray(kept))
        dsh.put("profile", profile)
        json.put("dsh", dsh)
        return try {
            // ⚠️ `JSONObject.toString()` 会把 `/` 转义成 `\/`（org.json 防 `</script>`
            // 的历史行为）。那在 JSON 里**完全合法**（引擎实测读得进去），但这份文件
            // 用户和模型都会看，`@deepseek-ai\/dsh-base` 长得就像文件已经坏了 ——
            // 而"配置看起来坏了"正是本方法要消灭的状态。还原回去。
            //
            // 安全性：`\/` 与 `/` 在 JSON 里语义完全相同，还原不会产生歧义 ——
            // 反斜杠本身在 org.json 的输出里是 `\\`，所以 `\\\/`（原串为 `\` + `/`）
            // 还原成 `\\/` 后仍解析为 `\` + `/`，与还原前一致。
            manifest.writeText(json.toString(2).replace("\\/", "/"))
            Log.w(
                TAG,
                "安全模式：已摘掉 $dropped 个第三方 bundle" +
                    (if (depsDropped > 0) " + $depsDropped 个第三方依赖" else "") +
                    "，保留 ${kept.joinToString(", ")}",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "安全模式：回写 profile 清单失败: ${t.message}")
            false
        }
    }

    /**
     * 把 profile 的 `node_modules/` 下**第三方**插件包整体挪进隔离目录（幂等）。
     *
     * ## 为什么摘了清单还要摘包
     *
     * [stripThirdPartyBundles] 改的是「引擎照着清单去不去加载它」，本方法改的是
     * 「它还在不在那儿」。两道都要有：
     *
     * | 只做其中一道 | 后果 |
     * |---|---|
     * | 只摘清单 | 包还在原地，patch 层里的 insert、引擎的兜底扫描、日后新增的加载入口都可能把它捡回来 ⇒ **摘了等于没摘** |
     * | 只挪包 | 清单里还留着名字，引擎解析不到会报 `cannot resolve profile bundle`，也可能被算成致命 |
     *
     * 真机现场（用户「做插件做到一半退出 App」，界面停在 `Failed to load plugins`）：
     * 坏插件是 AI **手工**塞进 `node_modules/` 的（绕过了 pnpm），只摘清单之后界面
     * **仍旧**打不开 —— 因为前端那一批合并 bundle 里照样有它的坏段。
     *
     * ## 挪到哪、能不能找回来
     *
     * 挪进 `profiles/<profile>/.safe-mode-plugins/`，**改名不删**（同 [quarantine]
     * 的约定）：安全模式只负责让 App 能打开，不替用户销毁插件 —— 想装回来重新勾一次，
     * 或者直接从那个目录拷回去都行。
     *
     * ⚠️ 点开头的条目（`.bin` / `.pnpm` / `.ignored` / `.modules.yaml`）一律不动：
     * 它们是包管理器的簿记，不是插件包 —— 尤其 `.ignored` 是 pnpm 刻意驱逐的，
     * 动它反而会让 pnpm 的下一轮安装行为异常。
     *
     * ⚠️ `@deepseek-ai/` 下的核心包一律保留：它们来自 `engine/`，重解包就会还原成好的，
     * 而且 App 自己的 patch 层（mobile-ui 等）依赖它们。
     *
     * @return true = 确实挪走了东西。
     */
    private fun quarantineThirdPartyPackages(profileDir: File): Boolean {
        val nm = File(profileDir, "node_modules")
        if (!nm.isDirectory) return false
        val spare = File(profileDir, SAFE_MODE_PLUGIN_DIR)
        var moved = 0

        // 挪一个实体；目标已存在时加时间戳，绝不覆盖（那是用户上一次的插件）
        fun moveInto(src: File, dest: File): Boolean {
            if (!src.exists()) return false
            val target = if (dest.exists()) {
                File(dest.parentFile, "${dest.name}.bak-${System.currentTimeMillis()}")
            } else {
                dest
            }
            target.parentFile?.mkdirs()
            if (src.renameTo(target)) return true
            // 同卷内 renameTo 正常不会失败；失败（跨卷 / 被占用）时退回拷贝，
            // 宁可慢也不能漏 —— 漏一个就意味着安全模式可能仍然进不去。
            return try {
                src.copyRecursively(target, overwrite = false)
                src.deleteRecursively()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "安全模式：挪走 ${src.name} 失败: ${t.message}")
                false
            }
        }

        val entries = nm.listFiles() ?: return false
        for (entry in entries) {
            val n = entry.name
            if (n.startsWith(".")) continue
            if (n == "@deepseek-ai") continue
            if (n.startsWith("@")) {
                // scoped 包：逐个成员判，只挪非核心的
                val members = entry.listFiles() ?: continue
                for (member in members) {
                    if (member.name == "deepseek-ai") continue
                    if (moveInto(member, File(File(spare, n), member.name))) moved++
                }
                // scope 目录空了就删掉，免得留个空壳给引擎的扫描看
                if (entry.listFiles()?.isEmpty() == true) entry.delete()
            } else if (moveInto(entry, File(spare, n))) {
                moved++
            }
        }

        if (moved > 0) {
            Log.w(
                TAG,
                "安全模式：已挪走 $moved 个第三方插件包 → $SAFE_MODE_PLUGIN_DIR/（想装回来重新勾一次即可）",
            )
        }
        return moved > 0
    }

    /**
     * 体检输出里缺了哪些 required 条目（见 [REQUIRED_ENTRY_IDS]）。
     *
     * ⚠️ 匹配要**行级**：`dump-config` 的输出里每行都是 `- id: xxx`，用
     * `contains("- id: webserver")` 会误命中 `webserver-xxx` 之类的前缀相同项。
     */
    private fun missingRequiredEntries(dumpOutput: String): List<String> {
        val ids = dumpOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- id: ") }
            .map { it.removePrefix("- id: ").trim() }
            .toHashSet()
        return REQUIRED_ENTRY_IDS.filterNot { it in ids }
    }

    /**
     * 强制重解包 `engine/`：删掉版本戳，然后**当场**重解包一次。
     *
     * ⚠️ **这是唯一能修「`engine/` 目录被改坏」的手段**：`ensureExtracted()` 有
     * `.stamp` 短路（见该方法），AI 改了 `engine/` 下的文件之后**永远不会被还原**。
     * 删掉戳之后必须**立刻**重解包，不能等下次启动 —— 本方法在 `start()` 里跑，
     * 而 `MainActivity` 的 `ensureExtracted()` 调用已经过去了，光删戳这一轮仍会用
     * 坏掉的引擎目录起来（真机实测就是这么踩的）。
     *
     * ⚠️ 会话、凭据、工作区都在 `$DSH_HOME` 与 `workspace/`，**不在 `engine/`**，
     * 所以这一下不会丢用户数据；代价只是重新解包那约 15 秒。
     *
     * ⚠️ 调用点在 `start()` 的最前段（早于所有往 `engine/` 写东西的 ensureXxx），
     * 否则重解包会把那些补丁抹掉。见 `start()` 里的顺序注释。
     *
     * @param missing 体检发现缺失的 required 条目 id（仅用于日志）。
     */
    private suspend fun rebuildEngine(missing: List<String>): PreflightResult {
        // 用户配置层也可能是共犯（比如它 patch 的目标行已随 bundle 一起消失）
        quarantine(File(dshHome, "profiles/$PROFILE_NAME/$PROFILE_PATCH_FILENAME"), "启动前体检")

        val stamp = lastAssetStamp
        if (stamp == null) {
            // 没拿到版本戳（ensureExtracted 还没被调用过）：只能删戳，让下次启动重解包
            if (stampFile.delete()) Log.w(TAG, "启动前体检：已删除引擎版本戳，下次启动将重新解包")
            return PreflightResult.Repaired
        }

        Log.w(
            TAG,
            "启动前体检：核心 bundle 损坏（缺 ${missing.joinToString(", ").ifEmpty { "未指认" }}），" +
                "正在重新解包 engine/（约 15 秒）",
        )
        if (stampFile.isFile) stampFile.delete()
        return try {
            ensureExtracted(stamp)
            val check = runDumpConfig()
            val stillMissing = check?.let { missingRequiredEntries(it.stdout) }.orEmpty()
            if (check != null && check.exitCode == 0 && stillMissing.isEmpty()) {
                Log.i(TAG, "启动前体检：重解包完成，复检通过")
                PreflightResult.Repaired
            } else {
                // 重解包都修不好 ⇒ 不是 engine/ 的问题（多半是设备环境），如实报出来
                Log.e(TAG, "启动前体检：重解包后仍未通过（缺 ${stillMissing.joinToString(", ")}）")
                PreflightResult.Repaired
            }
        } catch (t: Throwable) {
            // ensureExtracted 自己会把 State 置成 Failed 并抛出；这里不再吞掉它，
            // 让 start() 的调用方看到真实的解包失败原因
            Log.e(TAG, "启动前体检：重新解包失败", t)
            throw t
        }
    }

    /**
     * 把 [file] 改名成 `<原名>.bak-<时间戳>`（不删）。
     *
     * 时间戳冲突时补一个序号 —— 同一毫秒内隔离两个文件是可能的。
     *
     * ⚠️ [reason] 是**日志前缀**，不是给人看的备注：两条路径共用本方法
     * （[preflight] 自动修 / [enterSafeMode] 用户显式触发），日志里必须能分清
     * 是哪一条动的 —— 否则排查时会把用户主动进安全模式误读成"体检又修了一次"。
     *
     * @return true = 确实隔离了；false = 路径本来就不存在或改名失败。
     */
    private fun quarantine(file: File, reason: String): Boolean {
        // ⚠️ **判据是 `exists()` 而**不是** `isFile`：L1 隔离要摘的是
        //   `profiles/web/node_modules/@deepseek-ai/<名>` —— 那是**目录**。
        //   用 `isFile` 的话它直接返回 false，而调用方看到的是
        //   「一个都没能隔离」⇒ 修复静默失效（真机踩过：日志里明明
        //   「发现 1 个遮蔽副本」，下一行却是「遮蔽副本一个都没能隔离」）。
        //   改名对目录与文件是同一个系统调用，语义完全一致。
        //
        // ⚠️ **这一条也必须打日志，不能静默 return false**（2026-10-15 修）。
        //   上面那三种 false 里，`renameTo` 失败与抛异常**都留了痕**，唯独
        //   「路径不存在」原本是一行字都没有的 —— 于是真机出现了这样一对
        //   自相矛盾的日志（vivo V2232A / 20261015-054819-f2d62a77，03:16:39）：
        //
        //     #67 L1 隔离：全摘第三方仍起不来，但发现 1 个遮蔽副本（…/dsh-llm）⇒ 摘掉它们再试
        //     #68 L1 隔离：遮蔽副本一个都没能隔离，继续按原判据走
        //
        //   两行相隔 2 毫秒、中间没有任何一行解释，而 `#67` 打印的那个目录
        //   恰恰就是 `quarantine` 收到的那个对象。**同一天 03:23 的同一轮，
        //   同一个路径却隔离成功**（`已隔离损坏的文件 dsh-llm → dsh-llm.bak-…`
        //   → `摘掉 1 个遮蔽副本后能正常启动`），所以这不是「代码没写对」，
        //   而是「某个时刻那个目录不在了，而代码没说」。
        //
        //   这正是本项目缺陷族（结论与它描述的对象不是同一个）的又一实例：
        //   日志给出的结论是「摘不掉」，而它描述的事实可能是「已经不需要摘」。
        //   把真实原因说出来，下一次现场就能直接分辨这两种情况。
        if (!file.exists()) {
            Log.w(TAG, "$reason：${file.name} 不在（${file.absolutePath}）—— 无需隔离，跳过")
            return false
        }
        val base = "${file.name}.bak-${System.currentTimeMillis()}"
        var target = File(file.parentFile, base)
        var ordinal = 0
        while (target.exists()) target = File(file.parentFile, "$base-${++ordinal}")
        return try {
            if (file.renameTo(target)) {
                Log.w(TAG, "$reason：已隔离损坏的文件 ${file.name} → ${target.name}")
                true
            } else {
                Log.w(TAG, "$reason：隔离 ${file.name} 失败（改名返回 false）")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$reason：隔离 ${file.name} 失败: ${t.message}")
            false
        }
    }

    /** [runDumpConfig] 的结果。 */
    private class DumpResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * 一次「试启动」的结果（P1 二分法 / P3 `try-boot` 共用）。
     *
     * ⚠️ **`internal` 而不是 `private`**：`RescueCommandBridge` 要把它转成
     *   JSON 回执给 AI。做成 `private` 的话桥就只能自己重新解析一次引擎输出 ——
     *   那是同一件事的两份实现，正是本项目缺陷族的温床。
     *
     * @param ready 引擎是否打印了带 token 的 URL —— **这是"能起来"的唯一证据**。
     *   不变量 I6：任何静态检查的结论都不能替代它。
     * @param requiredDidNotActivate 引擎自称没激活的 required 插件数；没提到时为 null。
     * @param tail 引擎输出的尾部（已截断），给 AI 与人看。
     */
    internal class ProbeResult(
        val ready: Boolean,
        val exitCode: Int?,
        val requiredDidNotActivate: Int?,
        val tail: List<String>,
    ) {
        /** 是不是「插件问题导致起不来」——判据见 [RescueBoot.Failure.looksLikePluginProblem]。 */
        val looksLikePluginProblem: Boolean
            get() = requiredDidNotActivate != null && requiredDidNotActivate > 0
    }

    /**
     * 用给定 profile **真跑一次引擎**，看它能不能到「打印 URL」那一步，然后立刻杀掉。
     *
     * ## 这是本方案唯一可信的验证手段
     *
     * `--dump-config`（[runDumpConfig]）只解析配置、**不执行插件的 `apply()`**，
     * 而真机上杀死引擎的异常正是在 `apply()` 期抛的 —— 8 次真实启动失败里，
     * `dump-config` 退出码全是 0、4 个 required 条目全在。所以判据只能是真跑。
     * 这就是不变量 I6。
     *
     * ## 三条要害
     *
     * 1. **端口必须传 0**：正在运行的引擎占着 3080，探测进程再抢它就会得到
     *    `EADDRINUSE` → `startup failed: 2 required plugins did not activate`，
     *    把「插件坏」误报成「端口占用」。`--port 0` 让内核挑空闲端口
     *    （真机实测可行，A2 记录里那次打的是 44057）。
     * 2. **必须杀掉进程**：探测完不杀就会留一个占着端口、跑着插件的引擎。
     *    真机验证过 `--port 0` 起完再 kill，`ps -A | grep -c bin.js` = 0。
     * 3. **超时是 60 秒而不是 [READY_TIMEOUT_MS] 的 120 秒**：失败路径实测
     *    17~22 秒就返回了，60 秒足够，且二分会跑很多次，120 秒会让总时长失控。
     *
     * @param profile 用哪个 profile 试。
     * @param overlays `--patch` 列表。**必须与真实启动一致**（见 [buildEngineArgs]）。
     * @param timeoutMs 单次上限。
     * @return null = 压根跑不起来（node 缺失等）—— 与「跑起来但失败」是两回事，
     *   调用方必须区分：前者不是插件问题。
     */
    private fun probeBoot(
        profile: String,
        overlays: List<File>,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): ProbeResult? {
        val nodeBin = bootstrap.nodeBinary
        val binJs = File(engineDir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!nodeBin.isFile || !binJs.isFile) return null

        val cmd = buildEngineArgs(nodeBin, binJs, profile, overlays, port = 0)
        // ⚠️ **环境变量必须与真实启动逐项相同**，所以直接用 [activeEnv]（[start] 存下的
        //   那一份），而不是在这里再拼一遍。
        //
        //   再拼一遍的后果是**必须靠人肉比对才能发现差异**，而差异的症状是
        //   「试启动能过、真启动过不了」—— 这类差异几乎无法排查，因为两边的
        //   参数看起来都"对"。真机上真实存在的例子：漏掉 `LD_PRELOAD` 时
        //   转译层不生效，试启动会因为 link EACCES 而报出一堆与插件无关的错，
        //   探测结论于是**指向错误的方向**。
        //
        //   空 = 还没启动过 ⇒ 无法同构，直接放弃探测（见 [activeEnv] 的说明）。
        val env = activeEnv
        if (env.isEmpty()) {
            Log.w(TAG, "试启动放弃：尚无真实启动的环境变量基线（activeEnv 为空）")
            return null
        }
        Log.i(TAG, "试启动（$profile）: ${cmd.joinToString(" ")}")
        return try {
            val pb = ProcessBuilder(cmd)
                .directory(workspaceDir)
                .redirectErrorStream(true)
            pb.environment().apply {
                clear()
                putAll(env)
            }
            val proc = pb.start()
            val lines = java.util.Collections.synchronizedList(ArrayList<String>())
            // ⚠️ 用 `AtomicBoolean` 而不是 `@Volatile var`：Kotlin **不允许**把
            //   `@Volatile` 用在局部变量上（它只作用于有幕后字段的属性），
            //   而这里必须保证读取线程写、主线程读之间有可见性 ——
            //   否则可能出现"引擎明明打印了 URL，主线程永远看不到 ready"，
            //   症状是一次**假失败**，二分法据此摘掉一个好插件。
            val ready = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        lines.add(line)
                        if (URL_PATTERN.containsMatchIn(line)) ready.set(true)
                    }
                } catch (t: Throwable) {
                    // 被 destroyForcibly 打断是预期路径，见 [runDumpConfig] 的同款说明
                    Log.w(TAG, "试启动输出读取结束: ${t.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; start() }

            // ⚠️ **不能只 `waitFor(timeoutMs)` 等它退出。** 引擎是个**常驻 HTTP 服务**：
            //   成功时它打印 URL 之后**永不退出**，`waitFor` 必然一路等到超时 ——
            //   于是每次*成功*的探测都白烧满 60 秒，而 L1 的二分法上限是 3 分钟总时长，
            //   6 轮根本跑不完（第一轮就吃掉 60 秒）。
            //
            //   所以判据是"**三者先到先算**"：
            //     - 打印了 URL ⇒ 成功，立刻杀掉（这就是"能起来"的定义）
            //     - 进程自己退出了 ⇒ 失败，收集输出后返回
            //     - 到点了 ⇒ 超时，杀掉（失败路径实测 17~22 秒就该有结论了）
            //
            //   轮询粒度 100ms：失败实测 17~22 秒，粒度对结论毫无影响；成功路径则
            //   决定"省多少时间"，100ms 足够细。
            val deadline = System.currentTimeMillis() + timeoutMs
            var exited = false
            while (System.currentTimeMillis() < deadline) {
                if (ready.get()) break
                if (!proc.isAlive) {
                    exited = true
                    break
                }
                Thread.sleep(100)
            }
            // 成功 ⇒ 立刻杀；失败/超时 ⇒ 杀掉（此时它多半已经死了，destroyForcibly 幂等）
            val ok = ready.get()
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor(5, TimeUnit.SECONDS)
            }
            // 等读取线程把管道里剩下的输出收干净，否则 tail 会缺最后几行 ——
            // 而失败原因恰恰常常在最后几行。
            reader.join(2000)

            Log.i(
                TAG,
                if (ok) "试启动成功（已结束探测进程）"
                else if (!exited) "试启动超时（${timeoutMs} ms），已强制结束"
                else "试启动失败（进程已退出）",
            )

            val snapshot = synchronized(lines) { ArrayList(lines) }
            val exitCode = if (exited) runCatching { proc.exitValue() }.getOrNull() else null
            val parsed = RescueBoot.parseFailure(
                bootId = BootState.bootId,
                exitCode = exitCode,
                tailLines = snapshot,
                at = System.currentTimeMillis(),
            )
            ProbeResult(
                ready = ok,
                exitCode = exitCode,
                requiredDidNotActivate = parsed.requiredDidNotActivate,
                tail = parsed.tail,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "试启动执行失败: ${t.message}")
            null
        }
    }

    /**
     * 当前生效的 `--patch` overlay 列表（由 [start] 在启动时算好后存下）。
     *
     * ⚠️ **刻意"存下来"而不是"再算一遍"**：`--patch` 列表由 `start()` 里十几个
     *   `ensureXxx`/`writeXxxPatch` 的返回值组成，重算一遍要再调一遍那十几个函数
     *   （它们有副作用：解包、写文件、起进程）。更要紧的是——**重算出来的可能与
     *   真实启动用的不是同一份**，于是「试启动能过、真启动过不了」这种差异
     *   就无法排查了。存下来的那一份与真实启动**逐项相同**（不变量 I1 的另一面：
     *   探测必须与它要预测的那件事同构）。
     *
     * ⚠️ 空列表 = 还没启动过。试启动在这种情况下**不是**"没有 overlay"，
     *   而是"不知道真实启动用了什么"，所以 [probeBoot] 的调用方（救援信箱）
     *   保证只在引擎起来之后才发探测。
     */
    @Volatile
    private var activeOverlays: List<File> = emptyList()

    /**
     * 真实启动**实际用的**那一份环境变量（由 [start] 算好后存下）。
     *
     * ⚠️ **这是"试启动与真实启动同构"的结构性保证**，不是缓存优化。
     *
     * 试启动（[probeBoot]）的结论要用来预测"改完配置之后真实启动能不能过"。
     * 如果两边各自拼一遍环境变量，差异就只能靠人肉比对才能发现，而症状是
     * **「试启动能过、真启动过不了」**——那时人已经不知道该信哪一个了。
     *
     * 真机上会让两边分叉的例子（都是 `start()` 里有、想当然重拼时会漏的）：
     *
     * | 变量 | 漏掉的后果 |
     * |---|---|
     * | `LD_PRELOAD` | link 转译层不生效 ⇒ 试启动报一堆 link EACCES，**指向错误方向** |
     * | `BOO_SANDBOX_LIB` | 沙箱 runner 失效 ⇒ 报沙箱后端不可用，同样与插件无关 |
     * | `DSH_PRIV_RUNNER` / `DSH_ROOT_RUNNER` | 特权和 root 插件读不到通道路径 |
     * | `AGNES_CREDENTIAL` | 号池注入层拿不到凭据引用 |
     * | `TERMUX_PKG_NO_MIRROR_SELECT` 等镜像组 | 探测进程可能去连慢源，**超时被误判成插件问题** |
     *
     * ⚠️ 空 = 还没真实启动过。此时 [probeBoot] **拒绝探测**（返回 null）而不是
     *   用一份"差不多"的环境凑合 —— 那样出来的结论不可信，而不可信的探测
     *   比没有探测更糟：L1 的二分法会据此摘错插件。
     */
    @Volatile
    private var activeEnv: Map<String, String> = emptyMap()

    /**
     * 供 `RescueCommandBridge` 调用的试启动入口。
     *
     * ⚠️ **阻塞十几秒**，只能在后台线程调用（桥已经把 probe 放在
     *   `dsh-rescue-probe` 单线程池里）。
     *
     * @param bundleFilter 非空时：**临时**把 `web` 清单的 bundles 换成
     *   `core + 这些名字`，跑一次试启动，然后**无条件还原**原来的清单。
     *   这就是方案 §4.4.4 的 `bundleFilter`，也是 §8 承诺的
     *   「try-boot 不直接改 web profile」的实现方式 —— 用户磁盘上的配置
     *   在探测前后**逐字节相同**。
     */
    internal fun probeBootForBridge(
        profile: String,
        bundleFilter: List<String>? = null,
    ): ProbeResult? {
        if (bundleFilter == null) {
            // 探测是**只读**的：不写 [RescueBoot.recordFailure]（见上）。
            return probeBoot(profile = profile, overlays = activeOverlays)
        }
        return probeWithBundleFilter(profile, bundleFilter)
    }

    /**
     * 带 `bundleFilter` 的试启动：临时改写清单 → 探测 → **无条件还原**。
     *
     * ## ⚠️ 为什么不能另开一个 profile 名
     *
     * 直觉做法是「写一个 `profiles/probe/package.json`，用它试启动」——**错的**。
     * bundle 名的解析锚点只有两个：dsh 安装目录、`<profileDir>/package.json`。
     * 第三方插件装在 `profiles/web/node_modules/` 下，换一个 profile 名就解析不到，
     * 而解析不到**不是报错**：`loadProfileDirectory` 把它塞进 `skippedBundles`
     * 然后**照常启动成功**。于是探测报「能起来」、真实 web 起不来 —— 一个
     * 教科书式的假成功，且几乎无法排查（两边参数看起来都对）。
     *
     * 所以必须**在 `web` 目录上**做临时改写，让 `node_modules` 解析路径不变。
     *
     * ## ⚠️ 还原必须无条件
     *
     * 用 `try/finally` 而不是「探测成功才还原」：探测中途抛异常、被取消、
     * 或 App 被杀，都不能把用户配置留在半改状态。`finally` 覆盖前两者，
     * 被杀则靠 `.bak-l1`（[ensureManifestBackup] 已就位）兜底。
     *
     * @return 与 [probeBoot] 同义；`null` = 压根跑不起来或**没能建立起试验条件**。
     */
    private fun probeWithBundleFilter(profile: String, keep: List<String>): ProbeResult? {
        val manifest = File(dshHome, "profiles/$profile/package.json")
        if (!manifest.isFile) {
            Log.w(TAG, "试启动（bundleFilter）：找不到 $profile 清单")
            return null
        }
        // 备份用**独立的固定后缀**，避免与 L1 的 `.bak-l1` 互相覆盖（两者语义
        // 不同：`.bak-l1` 是"隔离前的原始配置"，`.bak-probe` 是"一次探测的现场"）。
        val backup = File(manifest.parentFile, "${manifest.name}.bak-probe")
        val original = try {
            manifest.readText()
        } catch (t: Throwable) {
            Log.w(TAG, "试启动（bundleFilter）：读清单失败: ${t.message}")
            return null
        }
        try {
            backup.writeText(original)
        } catch (t: Throwable) {
            Log.w(TAG, "试启动（bundleFilter）：备份失败，放弃（绝不覆盖用户配置）: ${t.message}")
            return null
        }
        return try {
            val core = ArrayList<String>()
            run {
                val bundles = JSONObject(original).optJSONObject("dsh")
                    ?.optJSONObject("profile")?.optJSONArray("bundles") ?: return@run
                for (i in 0 until bundles.length()) {
                    val n = bundles.optString(i, "")
                    if (n.startsWith("@deepseek-ai/")) core += n
                }
            }
            if (core.isEmpty()) {
                Log.w(TAG, "试启动（bundleFilter）：清单里没有核心 bundle，放弃")
                return null
            }
            if (!writeBundles(manifest, core, keep)) return null
            Log.w(TAG, "试启动（bundleFilter）：临时改成 core(${core.size}) + ${keep.size} 个指定 bundle")
            probeBoot(profile = profile, overlays = activeOverlays)
        } finally {
            try {
                // 写回**原样字节**（不是重新序列化）：探测不该在用户配置里留下
                // 任何格式差异，否则每探测一次配置就漂一点。
                manifest.writeText(original)
                backup.delete()
                Log.i(TAG, "试启动（bundleFilter）：已还原 $profile 清单")
            } catch (t: Throwable) {
                Log.e(TAG, "试启动（bundleFilter）：还原清单失败！备份在 ${backup.absolutePath}: ${t.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // P1：L1 自动隔离（基线 + 二分）
    // ─────────────────────────────────────────────────────────────────────

    /** [isolateBadBundles] 的结果，供写 `rescue/state.json` 与日志用。 */
    internal class IsolationResult(
        /** 最终被摘掉的第三方 bundle（只写**已证实致命**的那些）。 */
        val removed: List<String>,
        /** 给人看的一句话结论。 */
        val reason: String,
        /** 做了几次真实试启动（基线算一次）。 */
        val attempts: Int,
        /** 摘掉之后是否**确实**能起来（收尾确认过）。 */
        val verified: Boolean,
    )

    /**
     * L1 自动隔离的**触发闸门**（真正的算法在 [isolateBadBundles]）。
     *
     * 把「该不该做」与「怎么做」分开，是因为触发条件本身就是本方案最容易写错的
     * 一处：方案原稿写的「同一 bootId 内连续失败 ≥2 次」在真机上**永不成立**，
     * 已由 30 个诊断包实测证伪（见 [RescueBoot.Failure.consecutiveFailures]）。
     *
     * ## 四条闸门，缺一不可
     *
     * ① `tier == NORMAL` **或** `tier == QUARANTINED`：救援态/隔离态下没有可隔离
     *    的对象（救援 profile 压根不含第三方插件）。不判这一条会在救援态反复跑
     *    二分，把界面搅乱。
     *    ⚠️ `QUARANTINED` 必须放行：它是「上一轮禁掉一个、还没验证成功」的
     *    **进行中**状态，挡掉它会让用户卡在死循环里（见方法内注释）。
     * ② `consecutiveFailures >= 2`：单次失败可能是系统回收 —— 与现有
     *    `MAX_ENGINE_RESTARTS = 2` 的口径一致。
     * ③ `looksLikePluginProblem`：引擎**自己**说了「required plugin did not
     *    activate」，**或**失败清单里含非官方包（见 `Failure.hasThirdPartyFailure`）。
     *    ⚠️ 后半条是 2026-10-17 加的：`requiredStartupEntryIds` 是**白名单**，
     *    用户自己写的插件不在里面，它失败时引擎只 warn、不打那句 required 文案。
     *    只有前半条时，L1 二分对**用户插件永远不触发** —— 而那正是本功能的目标。
     *    ⚠️ 不能用「日志里有 Failed plugins 段落」代替 —— 那一段里绝大多数是
     *    正常的 skip，真机上 `Failed plugins (40)` 时引擎仍能起来过。
     * ④ 环境基线（[activeEnv]）就绪：二分靠 [probeBoot] 判真假，而它的结论只有在
     *    与真实启动同构时才可信 —— 同构的载体就是那一份环境变量表。
     *
     *    ⚠️ **不要**在这里判 `activeOverlays.isEmpty()`。空 overlay 列表是**合法**
     *    状态（一个 patch 都没写出来时真实启动也是空的，两边仍然同构），
     *    把它当"未就绪"会让一种正常配置永远无法进入 L1 —— 又一个"判据与对象不符"。
     *
     * @return 做了什么；没触发时返回 null。
     */
    private suspend fun maybeIsolateBadBundles(): IsolationResult? {
        val st = RescueBoot.tier()
        // ⚠️ [RescueBoot.Tier.QUARANTINED] **必须**放行（与 NORMAL 同等待遇）：
        //   它意味着「上一轮已禁掉一个坏插件、但还没验证成功」。若在这里挡掉，
        //   用户就会卡在「每次启动都禁同一个插件、每次都起不来」的死循环里 ——
        //   正是闸门①的注释警告的那种「停在原地、永远到不了下一层」。
        //   QUARANTINED 的语义是**进行中**，不是终点。
        if (st.tier != RescueBoot.Tier.NORMAL && st.tier != RescueBoot.Tier.QUARANTINED) {
            Log.i(TAG, "L1 隔离：当前层级 ${st.tier.label}，跳过")
            return null
        }
        val f = RescueBoot.lastFailure() ?: return null
        if (f.consecutiveFailures < 2) {
            Log.i(TAG, "L1 隔离：连续失败 ${f.consecutiveFailures} 次（<2），本次不触发")
            return null
        }
        if (!f.looksLikePluginProblem) {
            // ⚠️ 这里**必须真的推进到 L2**，不能只打一行日志就返回。
            //
            //   原先的代码正是「日志写『直接交 L2』、代码却只 `return null`」——
            //   于是 tier 停在 NORMAL，没有任何一处会把它改成 RESCUE，结果是
            //   **每次启动都从头再来一遍**（等 20 秒 → 失败 → 计数 +1 → 还是
            //   NORMAL），永远到不了救援模式、也永远到不了 L3。
            //   用户看到的是「App 一直起不来」，而日志里那句「直接交 L2」
            //   让排查的人以为已经交过去了 —— 这正是本项目缺陷族
            //   （结论/日志与它描述的事实不符）的第 31 例。
            //
            //   方案 §4.3 对此有明确要求：「失败 ⇒ 不是插件问题（或不止插件问题），
            //   跳到 L2」。L2 是**非破坏性**的（换一个最小 profile，不动用户配置，
            //   有常驻横幅 + 一键退出），所以即使这次判断偏保守也不会造成损害；
            //   而停在 NORMAL 是**确定**打不开。
            //
            //   ⚠️ 判据仍要求 `consecutiveFailures >= 2`（上面那道门），所以
            //     单次偶发失败不会把用户丢进救援模式。
            Log.w(
                TAG,
                "L1 隔离：连续失败 ${f.consecutiveFailures} 次，但引擎没报 required 插件未激活" +
                    " ⇒ 不是插件问题，降级到 L2 救援模式",
            )
            escalateToRescue(
                "连续 ${f.consecutiveFailures} 次启动失败，且引擎未报 required 插件未激活（不是插件问题）",
            )
            return null
        }
        if (activeEnv.isEmpty()) {
            // 调用点已在 [activeEnv] 赋值之后，所以这条是纯防御（理论上到不了）。
            // 真的到了也不能硬跑：没有同构基线的探测结论会指向错误的方向。
            Log.w(TAG, "L1 隔离：环境基线为空，无法做同构探测，本轮不触发")
            return null
        }

        Log.w(
            TAG,
            "L1 隔离启动：连续 ${f.consecutiveFailures} 次启动失败，" +
                "引擎报 ${f.requiredDidNotActivate} 个 required 插件未激活",
        )
        EventLog.record(
            "rescue.isolate.start",
            mapOf("consecutiveFailures" to f.consecutiveFailures.toString()),
        )

        val r = isolateBadBundles()
        if (r == null) {
            // ⚠️ `isolateBadBundles` 返回 null 意味着「L1 放弃了」：清单缺失、
            //   清单解析失败、或备份失败。**这三种都必须推进到 L2**，理由与上面
            //   那条「不是插件问题」完全相同 —— 不推进的话 tier 停在 NORMAL，
            //   下一次启动又从头跑一遍、又放弃，永远到不了 L2、也永远到不了 L3。
            //
            //   这不是新加的策略，而是本方法**唯一的目的**：只要 L1 没解决问题，
            //   就必须把控制权交给下一层。方案 §4.2 的「一定打得开」正是靠这条
            //   链子成立的（L1 → L2 → L3，每一环都必须真的接上）。
            //
            //   ⚠️ 注意 L2 **不改用户的任何配置**（它只是换一个最小 profile 启动，
            //     有常驻横幅 + 一键退出），所以这里推进不会造成损害；而停在
            //     NORMAL 是**确定**打不开。
            Log.w(TAG, "L1 隔离：未能给出结论（见上面那行）⇒ 降级到 L2 救援模式")
            escalateToRescue("L1 无法给出结论（清单缺失/解析失败/备份失败）")
            return null
        }
        Log.w(TAG, "L1 隔离结论：${r.reason}（试启动 ${r.attempts} 次，已验证=${r.verified}）")
        EventLog.record(
            "rescue.isolate.done",
            mapOf(
                "removed" to r.removed.size.toString(),
                "attempts" to r.attempts.toString(),
                "verified" to r.verified.toString(),
            ),
        )
        // 有插件被摘 ⇒ 记 ISOLATED 态（I4：降级必须常驻可见 + 可一键退出）。
        // ⚠️ 只在**确实摘了东西**时才记：`isolateBadBundles` 在"不是插件问题"时
        //   返回空列表，那种情况下不该让用户看到"有插件被隔离了"。
        if (r.removed.isNotEmpty()) {
            RescueBoot.setTier(
                tier = RescueBoot.Tier.ISOLATED,
                reason = r.reason,
                removedBundles = r.removed,
                restorePoint = "profiles/$PROFILE_NAME/package.json.bak-l1",
            )
            // ⚠️ 半成功（摘了、但收尾没验证通过）时，**额外**把已定位的插件
            //   记进定向隔离层：`package.json` 那条路已经证明不够（还有别的
            //   致命插件或预算耗尽），而 `disabled: true` 层是**下一轮启动就会
            //   生效**的第二道保险 —— 即使 `package.json` 被 pnpm 重写、
            //   被用户改回去、或 `bundles` 列表被重新生成，隔离层依然独立生效。
            //
            //   只在 `!r.verified` 时记：已验证成功的那条路（ISOLATED）语义更轻，
            //   不该让用户看到「已禁用」这种更重的措辞。
            if (!r.verified) {
                r.removed.forEach { RescueBoot.addQuarantine(it) }
                RescueBoot.setTier(
                    tier = RescueBoot.Tier.QUARANTINED,
                    reason = r.reason,
                    removedBundles = r.removed,
                    restorePoint = "profiles/$PROFILE_NAME/package.json.bak-l1",
                )
            }
            return r
        }

        // ── L1 没摘到东西 ⇒ **必须往前推进到 L2** ──────────────────────────
        //
        // ⚠️ 这一步不是可选的。不推进的话 tier 停在 NORMAL，于是**每次启动都
        //   重跑一遍完整的 L1**（基线 + 最多 6 轮二分 ≈ 3 分钟），而结论永远是
        //   "解决不了" —— 用户看到的是「每次打开 App 都要黑屏等三分钟，然后还是
        //   起不来」。那比现在（至少界面能出来）更糟，而且**永远到不了 L2**，
        //   直接违背本方案的唯一硬承诺「一定打得开」。
        //
        // 走到这里只有两种情形，**两种都该进 L2**：
        //   a) 全摘之后仍起不来（`removed` 为空）⇒ 不是插件问题，web profile 这条路
        //      已经证明走不通，只能换干净的 rescue profile。
        //   b) 二分没收敛 / 预算耗尽（`removed` 为空）⇒ 可能是多致命插件或耗时过长，
        //      再花三分钟重跑一遍的期望收益是负的，同样该先进 L2 把界面给用户。
        //
        // ⚠️ 用 `RESCUE` 而不是别的层级：语义就是"这次不用用户配置，用最小配置起"。
        //   代价是用户这次看不到自己的插件 —— 但那正是"一定打得开"的定价，
        //   而且 `state.json` 里记着原因、界面常驻提示、可一键退出（I4）。
        Log.w(TAG, "L1 隔离未能定位致命插件（${r.reason}）⇒ 降级到 L2 救援模式")
        escalateToRescue("${r.reason}（试启动 ${r.attempts} 次）")
        return r
    }

    /**
     * 把层级推进到 L2 救援模式（L1 没解决问题时的**唯一**出口）。
     *
     * ## 为什么单独抽出来
     *
     * L1 有**四条**「我解决不了」的路径（不是插件问题 / 清单缺失 / 清单解析失败 /
     * 备份失败 / 二分不收敛），它们**必须做完全相同的一件事**：把 tier 记成
     * RESCUE，让下一次启动走最小 profile。
     *
     * ⚠️ 早先的写法是每条路径各写一遍，结果是**有的路径忘了写** ——
     *   日志说「直接交 L2」而代码只 `return null`，于是 tier 停在 NORMAL，
     *   每次启动都重跑一遍 L1 然后放弃，**永远到不了 L2**（也就永远到不了 L3）。
     *   四条路径共用一个函数，这类「漏了一条」的缺陷在结构上就不可能再发生。
     *
     * ⚠️ 这里**只改 `rescue/state.json`，不碰用户的任何配置**。L2 是
     *   非破坏性的（换一个最小 profile 启动，原配置原地不动、有常驻横幅、
     *   可一键退出），所以这条路径可以放心走。
     */
    private fun escalateToRescue(reason: String, from: String = "l1") {
        // ⚠️ `from` 不能写死 "l1"：本函数还有第二个调用方 —— [start] 的
        //   「引擎起来了但 gateway 废了」那条路（**没经过 L1 二分**）。
        //   写死会让事件时间线把一次 NORMAL→RESCUE 的直接降级记成 L1 的产物，
        //   排查时按 `from=l1` 找证据会落空（同族缺陷：「结论与它描述的对象不符」）。
        EventLog.record("rescue.degrade.l2", mapOf("from" to from, "reason" to reason))
        RescueBoot.setTier(tier = RescueBoot.Tier.RESCUE, reason = "自动降级：$reason")
    }

    /**
     * L1 自动隔离：从真实启动失败的日志出发，**用真实启动验证**找出致命插件，
     * 只摘掉它们，让用户的其余插件照常工作。
     *
     * ## 为什么不能靠"日志里的失败插件名"直接摘
     *
     * 那 40 项里绝大多数是正常的 `skipping profile bundle`（版本不兼容，引擎自愿
     * 跳过、**不致命**）。真机反例：那台设备 `Failed plugins (40)` 但引擎**有时
     * 仍能起来**（07:14 成功启动过两次）。按日志名字摘 = 把用户 40 个插件全卸了，
     * 而真正致命的可能只有 1 个。
     *
     * 更要紧的是：日志里被点名的是**受害者**不是**元凶**。真机那次的因果链是
     * 「某个第三方插件重复注册了 `mcpManager/list` 端点 → 拖挂了核心插件
     * `typert-loader`」—— 而 `Failed plugins` 段落里排在最前、堆栈最长的正是
     * `typert-loader`（核心包）。照着它摘，摘的是核心包，摘完更起不来。
     *
     * ## 算法（全部结论都来自真实启动）
     *
     * ```
     * third = web 清单里非 @deepseek-ai/ 的 bundle
     * third 为空                      ⇒ 不是插件问题，交 L2
     * 基线：摘掉全部 third → 试启动
     *   失败 ⇒ 不是（仅）插件问题，交 L2
     *   成功 ⇒ 确认"插件问题"，继续
     * 二分（候选集 C 初始 = third）：
     *   每轮把 C 分两半 C1/C2，摘掉 C1（保留 C2 与其余全部）→ 试启动
     *     成功 ⇒ 致命的**都在** C1 里 ⇒ C = C1
     *     失败 ⇒ C1 里没有（全部）致命的 ⇒ 致命的在 C2 里 ⇒ C = C2
     *   |C| == 1 ⇒ 找到一个致命插件
     * 收尾：摘掉找到的那个 → 试启动确认
     *   成功 ⇒ 完成（只摘了 1 个）
     *   失败 ⇒ 还有别的致命插件，但预算已尽 ⇒ 保留已知结论，交 L2
     * ```
     *
     * ## ⚠️ 三条安全约束（都是"不能省"而不是"最好有"）
     *
     * ① **只由 [maybeIsolateBadBundles] 调用**（它负责判"该不该做"）。
     *    不要绕过闸门直接调用 —— 闸门里的四条判断（层级/连续失败数/是不是插件问题/
     *    基线是否就绪）每一条都对应一种"跑了反而有害"的场景。
     *
     *    ⚠️ 这里原先写的是「只在救援态（`tier == RESCUE`）调用」，那是**错的**：
     *    救援态走的是 rescue profile，它压根不含第三方 bundle，没有任何可隔离的对象 ——
     *    按那条写，L1 就永远没有适用场景。真正的适用场景恰恰是**正常态**：
     *    用户自己的 web profile 里某个第三方插件致命时，摘掉它而不是整包放弃。
     * ② **写清单前必须先备份成功**。备份失败就**什么都不做** —— 否则会把用户
     *    唯一一份原始清单覆盖掉（沿用 [stripThirdPartyBundles] 的既有约定）。
     * ③ **总时长上限** [ISOLATION_BUDGET_MS]。单次失败实测 17~22 秒，
     *    6 轮 + 基线约 2.5 分钟。超预算立刻放弃并保留已确认的结论，
     *    **绝不**让用户对着"正在启动…"无限等下去。
     *
     * ## ⚠️ 与正在运行的引擎共存
     *
     * 试启动会真的起一个引擎进程，与当前那个**共享 `$DSH_HOME`**。已经处理的两点：
     * `--port 0`（不抢端口）、探测完立刻杀掉（不留僵尸）。
     * 残余风险：探测进程启动时会初始化存储目录，理论上可能与当前引擎的
     * 跨进程写声明（flock）相撞。**实测未观察到**（A2 那次 `--profile rescue
     * --port 0` 起停干净），但它没有强保证 —— 这是本方案已知的取舍，
     * 不假装它不存在。
     *
     * @return 摘了什么、试了几次、有没有验证过。**不抛异常**。
     */
    private suspend fun isolateBadBundles(): IsolationResult? = withContext(Dispatchers.IO) {
        val manifest = File(dshHome, "profiles/$PROFILE_NAME/package.json")
        if (!manifest.isFile) {
            Log.w(TAG, "L1 隔离：找不到 $PROFILE_NAME 的清单，放弃")
            return@withContext null
        }
        val json = try {
            JSONObject(manifest.readText())
        } catch (t: Throwable) {
            // 清单本身已经坏了 —— 那是 L3（安全模式）的活，不是"某个插件致命"。
            Log.w(TAG, "L1 隔离：$PROFILE_NAME 清单解析失败（${t.message}），交给安全模式")
            return@withContext null
        }
        val core = ArrayList<String>()
        val third = ArrayList<String>()
        run {
            val bundles = json.optJSONObject("dsh")?.optJSONObject("profile")
                ?.optJSONArray("bundles") ?: return@run
            for (i in 0 until bundles.length()) {
                val name = bundles.optString(i, "")
                if (name.isEmpty()) continue
                if (name.startsWith("@deepseek-ai/")) core += name else third += name
            }
        }
        // ⚠️ **遮蔽副本的检查必须在这里、而不是在 `third.isEmpty()` 之后**：
        //   「profile 的 node_modules 里躺着引擎自带官方包的旧副本」与
        //   「清单里有没有第三方 bundle」是**互相独立**的两类故障。先判 third
        //   会让「没装第三方 bundle、却有遮蔽副本」这种形态直接走「不是插件
        //   问题」分支，永远救不回来 —— 又一个「判据与对象不是同一个」。
        val shadowed = shadowingOfficialCopies()
        if (third.isEmpty() && shadowed.isEmpty()) {
            Log.w(TAG, "L1 隔离：清单里没有第三方 bundle，也没有遮蔽副本，不是插件问题")
            return@withContext IsolationResult(
                emptyList(), "清单里没有第三方插件 —— 不是插件问题", 0, false,
            )
        }
        Log.w(
            TAG,
            "L1 隔离开始：${third.size} 个第三方 bundle，核心 ${core.size} 个" +
                (if (shadowed.isNotEmpty()) "，${shadowed.size} 个遮蔽引擎自带包的本地副本" else ""),
        )

        // ⚠️ **语法预检前置**：先把「一眼就是坏的」插件挑出来直接摘掉，**不进二分**。
        //
        //   理由（真机算出来的，不是猜的）：二分的代价是 `log2(N)` 次**真实启动**，
        //   每次最长 [PROBE_TIMEOUT_MS]（60 秒），总预算 [ISOLATION_BUDGET_MS]
        //   只有 3 分钟。装了 40 个插件时 `log2(40) ≈ 6` 次探测，低端机单次要
        //   20~60 秒 ⇒ **必然超预算**，二分在收敛前就放弃、落 L2（全部插件失效）。
        //   而语法坏是本地一次读取就能判的，把它前置等于把最常见那类故障
        //   从「6 次真实启动」降到「0 次」。
        //
        //   ⚠️ 只摘**预检明确判坏**的，判不了的一律留在候选集里走原二分。
        //     宁可多花时间，不可错杀（见 [preflightPluginSyntax] 的保守原则）。
        // ② 备份失败就什么都不做。备份**只做一次**（幂等，I3）：
        //    判断依据是"已经存在同源备份"，避免每轮都产生一个新的 .bak-<ts>。
        if (!ensureManifestBackup(manifest)) {
            Log.w(TAG, "L1 隔离：备份清单失败，放弃（绝不覆盖用户唯一一份原始清单）")
            return@withContext null
        }

        val overlays = activeOverlays
        val deadline = System.currentTimeMillis() + ISOLATION_BUDGET_MS
        var attempts = 0

        /** 写清单（`core + keep`），然后试启动。true = 能起来。 */
        fun tryWith(keep: List<String>, label: String): Boolean {
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "L1 隔离：预算耗尽（$label），放弃")
                return false
            }
            // ⚠️ `keep` **只放第三方**，不要放 core：下面 [writeBundles] 自己会拼
            //   `core + keep`。早先的写法把 core 也塞进 keep，于是核心 bundle 被
            //   写了两遍 —— 而那种错误**不会报错**（引擎照样能解析一个含重复项的
            //   数组），只会让配置文件里凭空多出一倍的 `@deepseek-ai/*` 条目。
            if (!writeBundles(manifest, core, keep)) {
                Log.w(TAG, "L1 隔离：写清单失败（$label）")
                return false
            }
            attempts++
            val r = probeBoot(PROFILE_NAME, overlays)
            val ok = r?.ready == true
            Log.w(
                TAG,
                "L1 隔离[$label] 保留 ${keep.size} 个第三方 → ${if (ok) "能起来" else "起不来"}" +
                    (r?.requiredDidNotActivate?.let { "（required 未激活 $it）" } ?: ""),
            )
            recordAttempt(label, keep, ok, r, deadline)
            return ok
        }

        // ⚠️ **语法预检前置**：先把「一眼就是坏的」插件挑出来直接摘掉，**不进二分**。
        //
        //   理由（算出来的，不是猜的）：二分的代价是 `log2(N)` 次**真实启动**，
        //   每次最长 [PROBE_TIMEOUT_MS]（60 秒），总预算 [ISOLATION_BUDGET_MS]
        //   只有 3 分钟。装了 40 个插件时 `log2(40) ≈ 6` 次探测，低端机单次要
        //   20~60 秒 ⇒ **必然超预算**，二分在收敛前就放弃、落 L2（全部插件失效）。
        //   而语法坏是本地一次读取就能判的，把它前置等于把最常见那类故障
        //   从「6 次真实启动」降到「0 次」。
        //
        //   ⚠️ 只摘**预检明确判坏**的，判不了的一律留在候选集里走原二分。
        //     宁可多花时间，不可错杀（见 [preflightPluginSyntax] 的保守原则）。
        val syntaxBad = third.filter { name ->
            val entry = File(dshHome, "profiles/$PROFILE_NAME/node_modules/$name/lib/index.js")
            entry.isFile && !preflightPluginSyntax(entry)
        }
        if (syntaxBad.isNotEmpty()) {
            Log.w(TAG, "L1 隔离：语法预检直接判坏 ${syntaxBad.size} 个（${syntaxBad.joinToString()}）")
            // 先只摘这些、其余照旧，试一次 —— 能起来就一步到位，不必二分。
            val keepAfterSyntax = third.filter { it !in syntaxBad.toSet() }
            if (tryWith(keepAfterSyntax, "syntax-precheck")) {
                Log.w(TAG, "L1 隔离完成：语法预检定位到 ${syntaxBad.size} 个坏插件，摘掉后能正常启动")
                return@withContext IsolationResult(
                    syntaxBad,
                    "语法预检定位到 ${syntaxBad.size} 个损坏的插件（${syntaxBad.joinToString()}），" +
                        "摘掉后能正常启动（其余 ${third.size - syntaxBad.size} 个保留）",
                    attempts,
                    true,
                )
            }
            Log.w(TAG, "L1 隔离：摘掉语法坏的插件后仍起不来 ⇒ 还有别的致命插件，继续二分")
        }

        // 基线：摘掉全部第三方。失败 ⇒ 再试一次「摘遮蔽副本」。
        if (!tryWith(emptyList(), "baseline-all-removed")) {
            // ⚠️ **这一步是本方法存在的第二个理由**（第一个是二分）。
            //
            //   真机形态（vivo V2232A / 20261014-211229-de7d4b8f，23 个真实用户包）：
            //   用户装了第三方插件（`dsh-better-sidebar`、`@dsh-trading/*`），
            //   pnpm 把它们**依赖的官方包旧版**一并装进了 profile：
            //
            //     profiles/web/node_modules/@deepseek-ai/dsh-llm@0.1.0-rc.6
            //     profiles/web/node_modules/@deepseek-ai/dsh-tools@0.1.0-rc.8
            //
            //   Node 的解析顺序是**从近到远**，于是 profile 里那份旧副本**遮蔽**了
            //   引擎自带的 0.2.0-rc.2。引擎一看版本对不上就把它禁掉：
            //
            //     disabling profile plugin row "llm": Plugin @deepseek-ai/dsh-llm@0.1.0-rc.6
            //       is incompatible with dsh 0.2.0-rc.2: peerDependencies {...}
            //
            //   而 `llm` 是**服务提供者**：它一被禁，`agent-loop (required)` 就永远
            //   拿不到 `llm` ⇒ `1 required plugin did not activate` ⇒ 引擎起不来。
            //
            //   ⚠️ **二分救不了它**：`llm` / `tools` 都是 `@deepseek-ai/` 开头，
            //     [isolateBadBundles] 开头的分类把它们放进 `core`，而 `core`
            //     是**恒定保留**的（`writeBundles` 自己拼 `core + keep`）。
            //     于是「全摘第三方」这一步摘的是**宿主插件**，遮蔽副本原封不动，
            //     试启动照样失败 ⇒ 旧代码在这里判「不是（仅）插件问题」→ 交 L2。
            //     而 L2 换的是**另一个 profile**，用户的 web profile 一个字没动，
            //     下次启动又走同一条路 —— **永远打不开**。
            //
            //   真机 A/B（同一台机器、同一份清单，只差这一个目录）：
            //     有 profiles/web/node_modules/@deepseek-ai/dsh-llm  ⇒ 引擎起不来
            //     删掉它                                              ⇒ 引擎立刻就绪
            //   所以这一处不是推测，是实测出来的因果。
            if (shadowed.isNotEmpty()) {
                // ⚠️ **必须重算，不能复用上面的 `shadowed`**（2026-10-15 修）。
                //
                //   `shadowed` 是 [L5333] 处算的，而到这一行之间**至少跑过一次
                //   `probeBoot`**（上一步的 `baseline-all-removed`）—— 真机实测间隔
                //   **7.1 秒**（vivo V2232A / 20261015-054819-f2d62a77：`#61` 03:16:31.861
                //   算出快照，`#67` 03:16:39.008 才用它）。
                //
                //   而 `probeBoot` 就是一次**真实的引擎启动**，它会动那个 profile 的
                //   `node_modules`（本项目已知：引擎/插件管理会把「多余依赖」挪进
                //   `node_modules/.ignored/`，见 AGENTS.md ⑥）。于是隔离操作实际作用的
                //   是**一个 7 秒前的目录列表**：清单里那个路径此刻可能已经不在，
                //   `quarantine` 返回 false，而调用方读到的结论是「一个都没能隔离」。
                //
                //   真机现场正是这样一对自相矛盾的日志 —— 两行相隔 **2 毫秒**、
                //   中间一个字都没有，而 `#67` 打印的那个目录就是 `quarantine`
                //   收到的那个对象；同一天 03:23 的同一轮同一路径却隔离成功：
                //
                //     #67 …但发现 1 个遮蔽副本（…/dsh-llm）⇒ 摘掉它们再试
                //     #68 …遮蔽副本一个都没能隔离，继续按原判据走
                //
                //   ⚠️ 说清界限：这 7 秒里**只跑过 `probeBoot` 一件事**，所以
                //     「引擎启动动过 node_modules」是**唯一的候选解释**，而我没有
                //     拿到直接证据（现场包里的 engine.log 没有任何 prune/ignored 痕迹，
                //     设备上也没有可复现的 node_modules 布局）。**所以这里不写成
                //     「根因已确认」** —— 修的是「拿过期快照去操作」这个**确定**的
                //     结构问题：重算之后，日志里打印的清单与实际要隔离的对象一致，
                //     无论那 7 秒里发生了什么，现场都不会再出现这种自相矛盾。
                val fresh = shadowingOfficialCopies()
                if (fresh.size != shadowed.size) {
                    Log.w(
                        TAG,
                        "L1 隔离：遮蔽副本清单在这段时间内发生了变化" +
                            "（快照 ${shadowed.size} 个 → 现在 ${fresh.size} 个），以现在的为准",
                    )
                }
                Log.w(
                    TAG,
                    "L1 隔离：全摘第三方仍起不来，但发现 ${fresh.size} 个遮蔽副本" +
                        "（${fresh.joinToString()}）⇒ 摘掉它们再试",
                )
                val moved = fresh.filter { quarantine(it, "L1 隔离") }
                if (moved.isNotEmpty()) {
                    val ok = tryWith(emptyList(), "shadowed-official-copies")
                    if (ok) {
                        Log.w(TAG, "L1 隔离完成：摘掉 ${moved.size} 个遮蔽副本后能正常启动")
                        return@withContext IsolationResult(
                            moved.map { it.name },
                            "profile 里遮蔽引擎自带包的 ${moved.size} 个旧副本（" +
                                "${moved.joinToString { it.name }}）已隔离，摘掉后能正常启动",
                            attempts,
                            true,
                        )
                    }
                    // ⚠️ **这一条以前是漏的**（2026-10-15 修）：摘成功了但试启动仍失败时，
                    //   流程直接落到下面那句「全摘之后仍起不来 ⇒ 不是（仅）插件问题」——
                    //   而那句话**没有提遮蔽副本已经摘掉**。读者会以为遮蔽副本没被处理过，
                    //   于是下一次继续按「不是插件问题」的方向查，重复烧一轮。
                    //   本项目缺陷族（结论与它描述的事实不符）在**结论粒度**上的又一例：
                    //   事实是「摘掉了 N 个、仍然起不来」，结论写的是「摘不掉／没摘」。
                    Log.w(
                        TAG,
                        "L1 隔离：已摘掉 ${moved.size} 个遮蔽副本" +
                            "（${moved.joinToString { it.name }}），但试启动仍起不来",
                    )
                } else if (fresh.isEmpty()) {
                    // ⚠️ 重算之后一个都不剩：说明那段时间里遮蔽副本**自己没了**
                    //   （被引擎/pnpm 挪走），所以此刻没有可摘的对象。
                    //   必须与「摘了但摘不掉」分开说 —— 两者的下一步动作完全不同。
                    Log.w(
                        TAG,
                        "L1 隔离：遮蔽副本此刻已不存在（快照里有 ${shadowed.size} 个），" +
                            "无对象可摘，继续按原判据走",
                    )
                } else {
                    Log.w(
                        TAG,
                        "L1 隔离：${fresh.size} 个遮蔽副本一个都没能隔离" +
                            "（逐个原因见上一行 quarantine 的日志），继续按原判据走",
                    )
                }
            }
            Log.w(TAG, "L1 隔离：全摘之后仍起不来 ⇒ 不是（仅）插件问题，交 L2")
            // ⚠️ 把用户清单**还回去**：既然不是插件问题，就不该留下改动 ——
            //   否则 L2 虽然能打开界面，用户的插件配置却被我们悄悄清空了。
            if (!writeBundles(manifest, core, third)) {
                Log.w(TAG, "L1 隔离：还原清单失败（用户配置可能停留在已摘状态，备份仍在）")
            }
            return@withContext IsolationResult(emptyList(), "全摘之后仍起不来 —— 不是插件问题", attempts, false)
        }

        // 二分：找**第一个**致命插件。
        //
        // 方向说明（**容易写反，且方案原稿的文字就是反的**）：每一轮都把候选集
        // `C` 对半切成 C1/C2，然后**只摘掉 C1**（`keep = third − C1`，即 C 之外的
        // 那些照旧保留）。于是：
        //   - 摘掉 C1 之后**能起来** ⇒ 所有致命插件都在 C1 里 ⇒ `C = C1`
        //   - 摘掉 C1 之后**起不来** ⇒ C1 不足以解决问题，致命的在 C2 ⇒ `C = C2`
        // 两个分支都成立的前提是「keep 是 third − C1」—— 若误写成「keep = C2」，
        // 那些已排除的插件会被一起摘掉，判据就失效了。
        var candidates: List<String> = ArrayList(third)
        while (candidates.size > 1) {
            if (System.currentTimeMillis() > deadline) break
            val half = candidates.size / 2
            val c1 = candidates.subList(0, half).toList()
            val c2 = candidates.subList(half, candidates.size).toList()
            // 只摘 C1；C 之外的（已排除的）照旧保留
            val drop = c1.toSet()
            val keep = third.filter { it !in drop }
            candidates = if (tryWith(keep, "bisect-${candidates.size}→${c1.size}")) c1 else c2
        }

        val culprit = candidates.firstOrNull()
        if (culprit == null) {
            Log.w(TAG, "L1 隔离：没收敛到具体插件，交 L2")
            return@withContext IsolationResult(emptyList(), "二分未收敛", attempts, false)
        }

        // 收尾：只摘这一个，确认真的能起来。
        // ⚠️ 同 [tryWith]：`finalKeep` **只放第三方**，core 由 [writeBundles] 拼。
        val finalKeep = third.filter { it != culprit }
        val verified = tryWith(finalKeep, "verify-single:$culprit")
        if (verified) {
            Log.w(TAG, "L1 隔离完成：摘掉 1 个（$culprit），其余 ${third.size - 1} 个保留")
            IsolationResult(
                listOf(culprit),
                "二分定位到 $culprit，摘掉后能正常启动（其余 ${third.size - 1} 个插件保留）",
                attempts,
                true,
            )
        } else {
            // 还有别的致命插件，但预算已尽。留着"摘掉它"的清单（至少排除了一个），
            // 剩下的交给 L2 与救援会话 —— **不假装成功**。
            Log.w(TAG, "L1 隔离：摘掉 $culprit 后仍起不来 ⇒ 还有别的致命插件，交 L2")
            IsolationResult(
                listOf(culprit),
                "找到 $culprit 但摘掉后仍起不来（还有别的致命插件，或预算耗尽）",
                attempts,
                false,
            )
        }
    }

    /**
     * 确保清单有一份**同源**备份（幂等）。
     *
     * ⚠️ **幂等判据是"有没有指向同一份原件的备份"**，不是"有没有 .bak"：
     *   每轮二分都调一次 [quarantine] 的话，会在用户目录里堆出 6 个 `.bak-<ts>`，
     *   而每个都只是**上一轮的中间状态**（不是用户的原始配置）—— 用户看到
     *   6 个备份文件，根本分不清哪个才是"我原来的配置"。
     *
     *   所以这里用固定后缀 `.bak-l1`（区分于用户手动隔离产生的 `.bak-<ts>`），
     *   存在即视为已备份。方案 §7.2 第 6 条验收的就是这条。
     *
     * @return 备份就位（含"本来就有"）返回 true。
     */
    private fun ensureManifestBackup(manifest: File): Boolean {
        val backup = File(manifest.parentFile, "${manifest.name}.bak-l1")
        if (backup.isFile) return true
        return try {
            backup.writeText(manifest.readText())
            Log.w(TAG, "L1 隔离：已备份原始清单 → ${backup.name}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "L1 隔离：备份清单失败: ${t.message}")
            false
        }
    }

    /**
     * 找出 profile 的 `node_modules` 里**遮蔽引擎自带官方包**的本地副本。
     *
     * ## 为什么这是一个独立的故障类
     *
     * Node 的模块解析是**从近到远**：`profiles/web/node_modules/@deepseek-ai/dsh-llm`
     * 会先于 `engine/node_modules/@deepseek-ai/dsh-llm` 被找到。用户装第三方插件时，
     * pnpm 会把那些插件**声明的官方依赖**一并装进 profile —— 版本往往是插件发布时
     * 锁定的旧版。于是引擎自带的 `0.2.0-rc.2` 被 profile 里那份 `0.1.0-rc.6` 顶掉，
     * 引擎按 `peerDependencies` 判它不兼容并**禁用该 row**：
     *
     * ```
     * disabling profile plugin row "llm": Plugin @deepseek-ai/dsh-llm@0.1.0-rc.6
     *   is incompatible with dsh 0.2.0-rc.2: peerDependencies {"@deepseek-ai/dsh":"^0.1.0-rc.6"}
     * dsh: startup failed: 1 required plugin did not activate
     * ```
     *
     * 被禁的是 `llm` 这种**服务提供者**，于是 `agent-loop (required)` 永远等不到
     * `llm` ⇒ 引擎起不来 ⇒ 界面永远打不开。
     *
     * ## 判据：只认「引擎里也有同名包，且版本不同」
     *
     * ⚠️ **不能只看「profile 的 node_modules 里有没有 @deepseek-ai/ 目录」** ——
     *   那会把用户正常安装的、引擎里**没有**的官方扩展插件也当成遮蔽副本摘掉。
     *   真正的遮蔽需要两个条件同时成立：
     *     ① 引擎 `node_modules/@deepseek-ai/<名>` 存在（同名，才谈得上遮蔽）；
     *     ② 两者的 `version` 不相等（相等时引擎不会报不兼容，摘了纯属误伤）。
     *
     * ⚠️ **也不能只比对版本、不看引擎有没有**：引擎里没有的同名包，摘掉就是
     *   把用户装的插件删了 —— 那是破坏而不是修复。
     *
     * @return 需要隔离的目录列表（可能为空）。**不修改任何东西**，只报告。
     */
    private fun shadowingOfficialCopies(): List<File> {
        val profileModules = File(dshHome, "profiles/$PROFILE_NAME/node_modules/@deepseek-ai")
        if (!profileModules.isDirectory) return emptyList()
        val engineModules = File(engineDir, "node_modules/@deepseek-ai")
        val found = ArrayList<File>()
        profileModules.listFiles()?.forEach { local ->
            if (!local.isDirectory) return@forEach
            val inEngine = File(engineModules, local.name)
            if (!inEngine.isDirectory) return@forEach
            val localVersion = packageVersion(local)
            val engineVersion = packageVersion(inEngine)
            // 版本读不出来时不摘：判据不成立就不动手（宁可少救，不可误伤）。
            if (localVersion == null || engineVersion == null) return@forEach
            if (localVersion != engineVersion) {
                Log.w(
                    TAG,
                    "L1 隔离：发现遮蔽副本 ${local.name}@$localVersion" +
                        "（引擎自带 $engineVersion）",
                )
                found += local
            }
        }
        return found
    }

    /** 读一个包目录的 `package.json` 里的 `version`；读不出来返回 null。 */
    private fun packageVersion(dir: File): String? = try {
        val f = File(dir, "package.json")
        if (!f.isFile) null else JSONObject(f.readText()).optString("version", "").ifEmpty { null }
    } catch (_: Throwable) {
        null
    }

    /**
     * 把清单写成 `core + keep`（顺序：核心在前，保留的第三方在后）。
     *
     * ⚠️ 与 [stripThirdPartyBundles] 一样用 `JSONObject` 解析再回写，并且
     *   把 `\/` 还原成 `/`（见那里的说明：org.json 的转义会让文件看起来像坏了）。
     */
    private fun writeBundles(manifest: File, core: List<String>, keep: List<String>): Boolean = try {
        val json = JSONObject(manifest.readText())
        val dsh = json.optJSONObject("dsh") ?: JSONObject()
        val profile = dsh.optJSONObject("profile") ?: JSONObject()
        profile.put("bundles", JSONArray(core + keep))
        dsh.put("profile", profile)
        json.put("dsh", dsh)
        manifest.writeText(json.toString(2).replace("\\/", "/"))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "L1 隔离：写清单失败: ${t.message}")
        false
    }

    /**
     * 把一轮二分的结论落进 `$DSH_HOME/rescue/attempts/NNNN.json`（方案 §5.1）。
     *
     * ⚠️ 这是**给人/AI 复盘用的**，所以即使失败也不能影响流程。写不进去只记警告。
     */
    private fun recordAttempt(
        label: String,
        keep: List<String>,
        ok: Boolean,
        r: ProbeResult?,
        deadline: Long,
    ) {
        try {
            val dir = File(dshHome, "${RescueBoot.DIR_NAME}/attempts")
            if (!dir.isDirectory && !dir.mkdirs()) return
            // 序号 = 现有文件数 + 1（从 1 开始，4 位补零，与方案 §5.1 的 0001.json 一致）
            val next = (dir.listFiles()?.size ?: 0) + 1
            val f = File(dir, "%04d.json".format(next))
            f.writeText(
                JSONObject()
                    .put("label", label)
                    .put("keptCount", keep.size)
                    .put("kept", JSONArray(keep))
                    .put("bootOk", ok)
                    .put("requiredDidNotActivate", r?.requiredDidNotActivate ?: JSONObject.NULL)
                    .put("exitCode", r?.exitCode ?: JSONObject.NULL)
                    .put("budgetLeftMs", (deadline - System.currentTimeMillis()).coerceAtLeast(0))
                    .put("at", System.currentTimeMillis())
                    .toString(2),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "L1 隔离：写试探记录失败: ${t.message}")
        }
    }

    /**
     * 拼出启动引擎的完整 argv（**纯函数**，便于离线验证与复用）。
     *
     * ## 为什么抽出来
     *
     * 它现在有三个调用方，而且**必须给出完全一致的参数**，否则验证结果没有意义：
     *
     * | 调用方 | 用途 |
     * |---|---|
     * | `start()` | 真正启动 |
     * | 试启动（P3 的 `try-boot`） | 让 AI 验证"改完能不能起来" |
     * | L1 二分法（P1） | 逐组验证哪些插件是坏的 |
     *
     * ⚠️ 后两者如果自己拼参数，就会出现「试启动能起来、真启动起不来」这类
     *   无法解释的差异 —— 而那正是本项目缺陷族最爱长的地方（同一个东西
     *   被两处各写一遍，然后慢慢漂开）。所以只留一份。
     *
     * ## ⚠️ `--port` 的位置是硬性的
     *
     * `--port` 属于 **web app 的内层 flag**，而 launcher
     * （`@deepseek-ai/dsh/lib/bin.js`）只解析自己的 `--profile` / `--patch`，
     * 规则是「第一个不认识的 token 就是内层参数的起点，其后原样转交」。
     * 插到 `--patch` 前面会让它们全被当成内层参数，引擎直接
     * `error: unknown option '--patch'` 起不来（真机实测踩过）。
     * 所以 `--patch` 全部排在 `--port` 之前，最后才是 `--no-open`。
     *
     * @param port null = 不传 `--port`（用引擎内置的 3080）；非 null = 显式指定。
     *   试启动**必须传 0**（让内核挑空闲端口）—— 否则会和正在运行的引擎撞端口，
     *   得到 `EADDRINUSE` 引发的 `startup failed: 2 required plugins did not activate`，
     *   把「插件坏」误报成「端口占用」。
     */
    private fun buildEngineArgs(
        nodeBin: File,
        binJs: File,
        profile: String,
        overlays: List<File>,
        port: Int?,
    ): List<String> = buildList {
        add(nodeBin.absolutePath)
        add(binJs.absolutePath)
        add("--profile"); add(profile)
        for (patch in overlays) {
            add("--patch"); add(patch.absolutePath)
        }
        port?.let {
            add("--port"); add(it.toString())
        }
        add("--no-open")
    }

    /**
     * 跑一次 `--dump-config` 并收集输出。
     *
     * 与真正启动用**同一套**环境变量与 node 二进制（见 [start] 的 `pb.environment()`），
     * 否则体检结论对不上真实启动。区别只有：这里不传 `--patch`，因为本 App 自己写的
     * 那几份 overlay 是每次启动整体覆盖的、不可能是坏的，带上反而多一层噪声。
     *
     * @return null = 压根跑不起来（node 缺失等），调用方应放行而不是误判成 profile 坏了。
     */
    private fun runDumpConfig(): DumpResult? {
        val nodeBin = bootstrap.nodeBinary
        val binJs = File(engineDir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!nodeBin.isFile || !binJs.isFile) return null
        return try {
            val prefix = bootstrap.prefixDir.absolutePath
            val pb = ProcessBuilder(
                nodeBin.absolutePath, binJs.absolutePath,
                "--profile", PROFILE_NAME, "--dump-config",
            )
                .directory(workspaceDir)
            pb.environment().apply {
                put("PREFIX", prefix)
                put("DSH_HOME", dshHome.absolutePath)
                put("HOME", homeDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
                put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
                put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
                // 堆上限 + 预加载 HTTP header 净化垫片（见 ensureFetchGuard）
                put("NODE_OPTIONS", nodeOptions())
            }
            val proc = pb.start()
            // ⚠️ 必须**并发**读走两个流：dump 的输出有 1500+ 行（真机实测），
            // 单线程先 readText() 一个流会让另一个流的 64KB 管道缓冲写满，
            // 引擎卡在 write 上永远不退出 —— 体检就成了新的挂死点。
            //
            // ⚠️ **两个线程体都必须自己 try/catch**，不能裸奔。
            //
            // 上面那条超时分支会 `destroyForcibly()` 杀掉引擎，此刻正阻塞在
            // `read()` 上的这条线程会被内核中断，`forEachLine` 抛
            // `java.io.InterruptedIOException: read interrupted`（真机堆栈：
            // `Linux.readBytes` ← `BufferedReader.readLine` ←
            // `kotlin.io.LinesSequence$iterator$1.hasNext`）。
            //
            // 这是**预期路径**（超时本来就该杀进程），不是故障；但裸线程里没人接
            // 这个异常，它会一路冒到 JVM 的默认未捕获处理器，被 `CrashRecorder`
            // 记成一份 `uncaught-exception` 崩溃现场 —— 于是「体检超时」在日志里
            // 伪装成「未捕获异常」，把排查方向带偏。
            // 真机证据：`20261013-221218-uncaught-exception.txt`（vivo V2313A，
            // `thread: Thread-9`，栈底就是这里）。
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                } catch (t: Throwable) {
                    // 被 destroyForcibly 打断是正常的，只记一句不抛。
                    Log.w(TAG, "体检 stdout 读取结束: ${t.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; start() }
            val errThread = Thread {
                try {
                    proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
                } catch (t: Throwable) {
                    Log.w(TAG, "体检 stderr 读取结束: ${t.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; start() }
            if (!proc.waitFor(PREFLIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                outThread.join(1000)
                errThread.join(1000)
                Log.w(TAG, "启动前体检超时（${PREFLIGHT_TIMEOUT_MS} ms），跳过")
                return null
            }
            outThread.join(2000)
            errThread.join(2000)
            DumpResult(proc.exitValue(), out.toString(), err.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "启动前体检执行失败，跳过: ${t.message}")
            null
        }
    }

    /**
     * 从 stderr 里解析引擎自己指认的坏文件路径。
     *
     * 引擎的报错长这样（真机原文，`--dump-config` 下）：
     * ```
     * Error: dsh: failed to parse overlay /data/.../profiles/web/cordis.patch.yml: YAMLException: ...
     * Error: dsh: failed to read profile manifest /data/.../profiles/web/package.json: ...
     * ```
     * 只接受**落在 `$DSH_HOME` 或 `engine/` 以内**的路径 —— 别的一律不动。
     */
    private fun parseBlamedFiles(stderr: String): List<File> {
        val out = ArrayList<File>()
        for (line in stderr.lineSequence()) {
            val match = BLAMED_PATH.find(line) ?: continue
            val file = File(match.groupValues[1])
            if (isWithinManagedRoots(file)) out += file
        }
        return out
    }

    /**
     * 从 stderr 的 `skipping profile bundle "x": ...` 行里，反查出该 bundle 的目录。
     *
     * 真机原文：`dsh: skipping profile bundle "dsh-ppt-master": Error: ... failed to parse
     * overlay /data/.../node_modules/dsh-ppt-master/cordis.patch.yml: ...`。
     * 报错行里带的那个 overlay 路径就是坏文件，直接隔离它 —— 比按包名去猜目录可靠。
     *
     * ⚠️ 返回值里可能含**落在 `engine/` 下**的路径（核心 bundle 被 skip 时）——
     * 那类文件隔离没有意义（会被重解包覆盖），由调用方据此改走整棵重解包。
     */
    private fun parseSkippedBundles(stderr: String): List<File> {
        val out = ArrayList<File>()
        for (line in stderr.lineSequence()) {
            if (!line.contains("skipping profile bundle")) continue
            Log.w(TAG, "启动前体检：${line.trim()}")
            val match = BLAMED_PATH.find(line) ?: continue
            val file = File(match.groupValues[1])
            if (isWithinManagedRoots(file)) out += file
        }
        return out
    }

    /** [file] 是否落在本 App 管得着的两棵树里（`$DSH_HOME` 与 `engine/`）。 */
    private fun isWithinManagedRoots(file: File): Boolean {
        val path = file.absolutePath
        return path.startsWith(dshHome.absolutePath) || path.startsWith(engineDir.absolutePath)
    }

    /**
     * 决定引擎 Web GUI 的监听端口。
     *
     * 返回 `null` 表示**不传 `--port`**，让引擎落到它自己的默认值
     * （`@deepseek-ai/dsh-web-app/cordis.patch.yml` 的 `port ?? 3080`）。
     * 返回 `0` 表示让内核挑一个空闲端口。
     *
     * ## 为什么需要它
     *
     * `dsh-host-webserver` 的 `listen()` 失败会**直接 reject 插件激活**
     * （`lib/index.js` 的 `server.once("error", reject)`），而它是 required 插件，
     * 于是整个引擎以
     * `dsh: startup failed: N required plugins did not activate` 退出，
     * 界面上就是「引擎启动失败（引擎进程已退出）」+ 一串
     * `waiting for webServer / webRuntime / connection` 的连锁行。
     *
     * 真机反馈的场景：App 上一次的 node 进程没跟着进程一起死（`ProcessBuilder`
     * 起的子进程不会随父进程退出），仍占着 3080，之后每次冷启动都撞 EADDRINUSE ——
     * 用户只能重启手机。
     *
     * ## 为什么「探测空闲就照旧传默认值」而不是一律 `--port 0`
     *
     * 一律 `--port 0` 会让每次启动的端口都变，而移动端 UI 插件与用户的收藏/
     * 书签都可能记着 `127.0.0.1:3080`。只有真被占时才让出端口，正常路径的
     * 行为与改动前**逐字一致**。
     *
     * ⚠️ 探测与真正 `listen` 之间天然存在竞态（探测完端口被别人抢走）。
     * 这个窗口只有毫秒级，且失败后果与改动前完全相同（引擎报错退出），
     * 所以不为此引入重试 —— 真正的占用源是上一个残留进程，它不是并发抢端口。
     */
    private fun resolveWebPort(): Int? {
        if (!isLoopbackPortBusy(DEFAULT_WEB_PORT)) {
            Log.i(TAG, "Web 端口 $DEFAULT_WEB_PORT 空闲，沿用引擎默认值")
            return null
        }
        Log.w(
            TAG,
            "Web 端口 $DEFAULT_WEB_PORT 已被占用（多半是上一次残留的引擎进程），" +
                "改由内核分配空闲端口",
        )
        return 0
    }

    /**
     * `127.0.0.1` 上的 [port] 是否已被占用。
     *
     * ⚠️ 判据与 `AdbMdns.isPortInUse` 相反：那里「bind 失败 = adbd 在听」是**成功**，
     * 这里「bind 失败」就是**真被占用**。两处语义不同，别互相抄。
     *
     * 只探测回环地址，与引擎的 `host: '127.0.0.1'` 对齐 —— 别的进程若绑在
     * `0.0.0.0:3080`，对 `127.0.0.1:3080` 的 bind 同样会失败，判据成立。
     */
    private fun isLoopbackPortBusy(port: Int): Boolean = try {
        ServerSocket().use {
            // 不设 SO_REUSEADDR：Android 上它只影响 TIME_WAIT 复用，不影响
            // 「有人在 listen」的判定，默认行为更贴近引擎自己的 bind。
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
        }
        false
    } catch (_: IOException) {
        true
    }

    /** 启动引擎，等待其打印带 token 的 URL。 */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) {
            Log.i(TAG, "引擎已在运行")
            // ⚠️ **早返回也必须跑特权通道自愈。** 这一段原先直接 return，于是
            //   「引擎还活着」时整块自愈（常驻监听 + 启动自愈 + 固定端口守护）
            //   一次都不跑 —— 而它恰恰是**用户重开 App 时唯一会走到的那条路**：
            //   引擎进程被前台服务（`START_STICKY`）保着，`MainActivity.onCreate`
            //   每次都会调 `start()`，每次都命中这个早返回。
            //   症状就是用户报的那两条：「清后台重开，权限还是没回来」。
            resumePrivilegedChannel()
            return@withContext
        }

        _state.value = State.Starting
        logLines.clear()
        // 启动计时锚点。后面「引擎就绪」事件用它算出总耗时 ——
        // 「打开 App 慢」这类反馈只有靠这个数才能定位到具体阶段。
        val startMs = System.currentTimeMillis()
        EventLog.record("engine.start")
        // ⚠️ 每次启动都清掉「主动停止」标记，否则 stop() 之后再也起不来
        //   （EOF 会被当成用户停止而不再上报死亡，这里反过来：新一轮启动了，
        //   旧标记已经没有意义）。
        userStopped = false

        homeDir.mkdirs()
        dshHome.mkdirs()
        workspaceDir.mkdirs()

        // ⚠️ Termux 环境与启动前体检**必须排在最前面**，早于下面所有往 engine/ 写东西
        // 的 ensureXxx。理由是体检在发现核心 bundle 被改坏时会触发**整棵 engine/
        // 重解包**，而那会把已经打好的补丁与已解出的原生插件全部抹掉 —— 那些
        // ensureXxx 都是幂等的，重解包之后再跑一遍才是正确顺序。
        // 真机实测：正常态体检耗时 0.7~0.8 秒，对 7~10 秒的启动时间可忽略。
        if (!bootstrap.installNodeRuntime()) {
            _state.value = State.Failed(
                "Termux 环境未就绪，无法启动引擎。\n" +
                    "prefix=${bootstrap.prefixDir.absolutePath}\n" +
                    "node=${bootstrap.nodeBinary.absolutePath}"
            )
            return@withContext
        }

        val nodeBin = bootstrap.nodeBinary
        if (!nodeBin.isFile) {
            _state.value = State.Failed("找不到 Node 可执行文件: ${nodeBin.absolutePath}")
            return@withContext
        }

        // ⚠️ 救援 profile 必须在**体检之前**建好，且必须建在体检**之外**。
        //
        //   体检（[preflight]）只处理 `web` profile，它的结论不该影响救援 profile
        //   的可用性 —— 恰恰相反：**web 坏掉时救援必须还能起来**，那正是它存在的理由。
        //   所以这里独立建、独立判失败（见 [ensureRescueProfile] 的 @return）。
        //
        //   建不出来 ⇒ 救援这条路不可用。这时**不能**假装没事继续（那会让
        //   L2 的失败变成"救援模式也起不来"这种更糟的表现），而是记下来，
        //   让降级决策直接跳到 L3（安全模式）。见 [rescueAvailable]。
        val rescueProfileDir = ensureRescueProfile()
        rescueAvailable = rescueProfileDir != null
        if (!rescueAvailable) {
            Log.e(TAG, "救援 profile 不可用 —— 降级阶梯将跳过 L2 直接到 L3")
            EventLog.record("rescue.profile.unavailable")
        }

        // 本次用哪个 profile 启动，由**上一次**留下的救援层级决定。
        //
        //   判据只看 RESCUE 一态。SAFE_MODE（L3）与 ISOLATED（L1）**都仍然用
        //   `web` profile** 启动 —— 两者的语义都是「用用户自己的配置，只是少加载
        //   几个 bundle」，与救援 profile（完全不用用户配置）是两套不同的机制
        //   （见 `docs/RESCUE-BOOT-PLAN.md` §4.1 的表）。把它俩也切到 rescue 会
        //   让它们失去原本的作用，那是行为变更，不是本方案要的。
        //
        //   `tier()` 读的是 `$DSH_HOME/rescue/state.json`，由降级决策写入。
        //   ⚠️ 它**不会**因为"这次起来了"而自动复位 —— 复位只在用户点
        //   「退出救援模式」时发生（I4：降级状态必须常驻可见 + 可一键退出）。
        val targetProfile = if (RescueBoot.tier().tier == RescueBoot.Tier.RESCUE) {
            RESCUE_PROFILE_NAME
        } else {
            PROFILE_NAME
        }
        if (targetProfile != PROFILE_NAME) {
            Log.w(TAG, "以救援 profile 启动（--profile $targetProfile）：不加载任何第三方插件")
            EventLog.record("engine.start.rescue", mapOf("profile" to targetProfile))
        }

        // 启动前体检：profile 与 patch 层都是模型可写的，改坏会让引擎直接 exit 1，
        // 而 App 没有任何自救路径（用户只能卸载重装，那会清掉全部会话与凭据）。
        //
        // ⚠️ 结论**只是静态的**：它跑 `--dump-config`，不执行插件 `apply()`。
        //   真机上杀死引擎的异常正是在 `apply()` 期抛的，所以体检放行 ≠ 能起来。
        //   三态如实写进日志与事件时间线（原来 `false` 把两件事混成了一件）。
        //
        // ⚠️ **救援态下跳过体检**（I1：救援启动必须比正常启动简单，不能成为
        //   第二个失败源）。体检会跑 `--dump-config`、可能触发整棵 engine/
        //   重解包（15 秒 + 抹掉已打好的补丁）—— 那些动作在救援场景下全是风险，
        //   而救援 profile 本来就只有核心 bundle，没什么可体检的。
        val pre = if (targetProfile == PROFILE_NAME) {
            preflight()
        } else {
            Log.i(TAG, "救援启动：跳过启动前体检（I1：救援路径不引入新的失败点）")
            PreflightResult.StaticOnly
        }
        Log.i(TAG, "启动前体检结论：${pre.label}")
        EventLog.record("preflight.result", mapOf("result" to pre.label))
        if (pre == PreflightResult.Repaired) {
            Log.i(TAG, "启动前体检已修复损坏的插件配置，继续启动")
        }

        // 前端补丁要在 WebView 首次加载前落盘，所以放在启动引擎这一步里
        ensureWebCompat()
        // HTTP header 净化垫片：配置里混进中文时，undici 会抛一句只带字符下标的
        // 英文错误、消息直接发不出去。必须在引擎进程启动前落盘，否则
        // `NODE_OPTIONS=--import` 拿不到文件（见 ensureFetchGuard）。
        ensureFetchGuard()
        // 原生插件是引擎启动的前提（DSH 的 profile 解析依赖它），必须早于进程启动
        ensureNativeAddon()
        // system 插件（会话写入锁）：缺了它每轮回复都会以「本轮运行失败」告终
        ensureSystemAddon()
        // ripgrep：官方无 android 平台包，缺了 grep/glob 两个工具全废
        ensureRipgrep()
        // sharp：官方无 android 预编译二进制，缺了图片附件发送会以 agent-busy 失败
        ensureSharp()
        // node-pty：官方 prebuilds 无 android，缺了侧栏终端打不开
        ensureNodePty()
        // 终端后端的进程检查器只认 linux/darwin/win32，android 要显式放行
        patchProcessInspector()
        // terminal-bash 硬编码 /bin/bash，而 Android 上只有 /bin/sh（没有 bash）→
        // bash 工具每条命令都报「PTY shell exited during startup」，改用 $PREFIX/bin/bash
        patchTerminalShell()
        // 附件模块的目录 fsync 会走到 /data/user/0（SELinux 拒绝 open）→ 附件发不出去
        patchAttachmentDurability()
        // 发图格式白名单只认 4 个精确 MIME → image/jpg、heic、剪贴板粘贴全被误拒
        patchImageMediaType()
        // ⚠️ 用户自己做的插件在运行期抛出未捕获异常时，installFailLoud 会 proc.exit(1)
        //   把整个引擎杀掉 → 反复重启 → App 再也打不开（三级降级阶梯对此无效，
        //   因为这条路径下 RescueBoot.recordFailure 一次都不会被调用）。见其 KDoc。
        patchFailLoudExit()
        // 凭据文件权限过宽（0600 之外）会让 credentials 插件硬抛错 → 9 个插件级联 pending
        // → 整个引擎退出。引擎自己是纯校验不自愈，所以由 App 在启动前收窄。
        ensureCredentialFileMode()
        // DeepSeek Messages 要求 assistant 的 tool_use 块连续且紧后跟 tool_result，
        // 而持久化历史里 text 会夹在多个 tool_use 之间 → 该会话从此永久发不出去
        patchLlmDeepseekToolOrder()
        // pi-ai：第三方路由不给 tool_call id 时，原样透出空 id → 落盘后会话永久不可读。
        // 两处都要改（delta 与 end 各一条路径），见各自的 KDoc。
        patchLlmPiAiToolCallId()
        patchLlmToolCallIdFallback()
        // 目录选择器：撤回「起点改 /sdcard」的旧补丁，改由 HOME 下的入口链接提供
        restoreDirectoryPickerStart()
        // 手机存储与 app 私有目录在 Android 上互不可达，靠 HOME 下两个链接做入口
        ensurePickerRoots()
        // 移动端 UI 适配插件：必须在 profile 解析前落盘，否则 --patch 挂不上
        ensureMobileUi()
        // 右侧栏「AI 浏览器」入口（客户端插件）。必须在引擎启动前解包：
        // 引擎启动时才扫描 dsh.client 声明并编译前端 bundle，晚一步就是
        // 「文件在、侧栏里没有这一项」，且没有任何报错。
        ensureBrowserPane()
        val browserPanePatch = writeBrowserPanePatch()
        // 降级 bundle：polyfill 只补 API，语法缺口得靠换 bundle 覆盖 dist 与各插件。
        // ⚠️ **必须在 ensureMobileUi() 之后**：那一支在版本戳不一致时会
        // `deleteRecursively()` 重解包 mobile-ui 目录，先降级会被它整个抹掉。
        // （dist / engine 两棵树与该顺序无关，只是顺手放在一起。）
        ensureWebViewCompat()
        // 出厂默认设置：只写 profile 的用户 patch 层，且只在它不存在时写一次。
        // 必须早于引擎启动 —— 引擎的 initProfile() 会给缺失的 patch 文件补一份空模板。
        seedDefaultSettings()
        val mobileUiPatch = writeMobileUiPatch()
        // Agnes 多模态插件：generate_image / generate_video 两个工具 + 多模态配置页。
        // 同样必须在 profile 解析前落盘，否则 --patch 挂不上。
        ensureAgnesMultimodal()
        // 把内置号池接到该插件上（写插件自己的账号池文件，只在缺失时播种一次）。
        // 必须早于引擎启动：插件读不到账号池就会回落到它自带的默认账号。
        seedAgnesAccounts()
        val agnesMmPatch = writeAgnesMultimodalPatch()
        // 内置预设 dsh-preset-razor（「快准狠模式（通用）」）。
        // 走 profile bundle（不是 overlay）⇒ 用户能在「插件 → 已安装插件」里自己关掉，
        // 默认开启。必须在 profile 解析前解包 + 登记，否则 bundle 解析不到。
        ensurePresetRazor()
        removeLegacyPresetRazor()
        registerPresetRazorBundle()
        // 内置**中文 skill**：落到 $DSH_HOME/skills（全局发现根，每个预设都扫得到），
        // 让 AI 做主题/桌面宠物时不必重新摸一遍 DSH 结构。
        ensurePresetSkills()
        // 创造模式（cordis 预设）的 persona：在官方原句之上并入「快准狠」铁律。
        // ⚠️ 必须在 ensureExtracted() 之后（改的是 engine/ 里的文件），
        //    且每次启动都要重打 —— 引擎重解包会把补丁抹掉，见该函数的 KDoc。
        patchPresetCordisPersona()
        // ⚠️ 这里**不再**写 `agent-preset-registry` 的 `default: razor`。
        //
        // 改造前为了让新会话默认用 razor，App 会把出厂默认位补进 profile 的用户层。
        // 但 razor 现在是**可被用户关掉的 bundle** —— 一旦关掉，registry 的
        // `resolve()` 就查不到 `razor` 这条定义，直接抛
        // `agent-preset/not-found: Unknown agent preset: razor`，新建会话全部失败。
        //
        // 所以「默认用它」改为靠「默认开启」实现（bundle 在名单里 ⇒ 预设就在选择器里，
        // 且 registry 的出厂默认位本来就会选中它）。用户关掉之后，由用户在预设页自己
        // 选一个默认 —— 那是用户意图，App 不替他决定，也不留一个悬空的 default。

        // 跨会话持久记忆插件：核心记忆常驻注入 + 归档记忆检索 + 回复风格设置页。
        // 走 profile bundle（不是 overlay）⇒ 用户能在「插件 → 已安装插件」里自己关掉。
        ensureCoreMemory()
        removeLegacyCoreMemoryDir()
        removeCoreMemoryOverlay()
        registerCoreMemoryBundle()
        // 插件市场：从 npm 按关键词收录插件，在设置页浏览/搜索/一键安装。
        // 安装动作复用官方 pluginManager 的 Remote 服务，本插件不自己碰 pnpm。
        // 同样必须在 profile 解析前落盘，否则 --patch 挂不上。
        ensureMarket()
        val marketPatch = writeMarketPatch()
        // 沙箱：Android 无内核级后端，用自带 runner 保住 workspace-write 的写边界
        val sandboxPatch = writeSandboxPatch(ensureSandbox())
        // 默认工作区：Android 落不进引擎的平台分支，必须显式给 documentsDirectory
        val workspacePatch = writeWorkspacePatch()
        // 插件安装源：官方 fallback 只有镜像源，与本机 ~/.npmrc 撞成同一项被去重掉
        val pluginManagerPatch = writePluginManagerPatch()
        // link 兼容层：SELinux 不给 app_data_file 的 link 权限，需在 libc 边界转译
        val linkCompat = ensureLinkCompat()
        // 内置号池：已改为**服务端网关**，本地不再解出注入库。
        // 这里调用是为了清掉老安装残留的 libkeyinject.so，恒返回 null（结果不使用）。
        ensureKeyInject()
        // ⚠️ **不能**走 `--patch` overlay：overlay 在最顶层整块替换 `llm-pi-ai`
        // 那一行的 config，会把用户在 GUI 里新建的 provider 一起盖掉，于是
        // `dsh-config-editor` 的写入校验直接抛
        // `Configuration for "llm-pi-ai" is overridden by a home patch or
        // command-line overlay` —— 用户表现为「一添加第三方 API 就报错」。
        // 所以改为把内置号池**补进 profile 的用户配置层**（见 [ensureKeyPoolProvider]）。
        ensureKeyPoolProvider()
        // 老用户在 GUI 里手填过真密钥 ⇒ `.credentials.yaml` 里有一条
        // `AGNES_CREDENTIAL`，它的优先级**高于**环境变量，会把网关令牌顶掉。
        // 号池改成服务端网关后这条残留必须清掉，详见 [dropStaleBuiltinCredential]。
        dropStaleBuiltinCredential()
        // 默认模型 = 内置 Agnes。引擎的出厂默认是官方模型（deepseek-official），
        // 而本 App 的用户大多没配过第三方 API ⇒ 那个路由必然失败。详见 [ensureDefaultModel]。
        ensureDefaultModel()

        // ⚠️ **救援 profile 也必须拿到同一套用户层配置**，否则救援界面是残废的：
        //   没有默认模型 ⇒ AI 一开口就路由失败；没有号池 ⇒ 用户得自己配 key；
        //   没有出厂设置 ⇒ 主题/权限预设全空。而「救援模式里 AI 能帮我修」
        //   正是整个方案的核心承诺（G3），AI 不能用就全盘失效。
        //
        //   这四处写入都是**幂等 + 只补缺**的（各自 KDoc 有论证），所以对 rescue
        //   重复调用是安全的：救援 profile 刚被建出来时它们会播种，之后不再动。
        //
        //   ⚠️ 只对**已建出来**的救援 profile 播种（`rescueProfileDir != null`）：
        //     目录不存在时这几个函数会 `mkdirs()` 把它造出来，而造出来的目录
        //     只有 `cordis.patch.yml`、**没有 `package.json`** —— 那样的 profile
        //     引擎读不了（`profile "rescue" does not exist`），比不建更糟。
        if (rescueProfileDir != null) {
            seedDefaultSettings(RESCUE_PROFILE_NAME)
            ensureKeyPoolProvider(RESCUE_PROFILE_NAME)
            ensureDefaultModel(RESCUE_PROFILE_NAME)
        }
        // uid 2000 通道：截图/点击/卸载/settings 等 app 域做不到的操作要靠它。
        // 未导入密钥时脚本自己回落为本地执行，不影响任何现有功能。
        val privRunner = ensurePrivilegedShell()
        // ⚠️ **Shizuku 桥必须在引擎启动前起来**，而且**无论 Shizuku 当前可不可用
        //    都要起**：Shizuku 服务可能是用户稍后才启动的（重启手机后要手动开一次），
        //    而 socket 名一旦绑定就固定了 —— 起桥时检查可用性会让「先开 App
        //    再开 Shizuku」永远连不上。见 [ShizukuChannel.start]。
        //
        // 这是 uid 2000 的第二条来源，且**不依赖 adbd**：对 Android 10 及以下
        // （没有无线调试，且 persist.adb.tcp.port 写不进去 ⇒ 每次重启都要 PC
        // 再跑一次 `adb tcpip 5555`）的用户，这往往是唯一可行的路。
        if (ShizukuChannel.start(context)) {
            Log.i(TAG, "Shizuku 桥已就绪: 管理器=${ShizukuChannel.isManagerInstalled(context)} " +
                "binder=${ShizukuChannel.isBinderAlive()} 已授权=${ShizukuChannel.hasPermission()}")
        }
        // 无线调试（Android 11+）用的 adb 工具链：让 uid 2000 通道在
        // 「只开了无线调试、没做 adb tcpip」的设备上也能用。见 ensureAdbWireless()。
        val adbWireless = ensureAdbWireless()
        // 把该通道注册成独立的 `shell` 工具（**不是**改 bash 的行为）：
        // 默认走 app 身份，模型读工具描述后自己决定要不要用 2000。见 ensurePrivilegedPlugin()。
        ensurePrivilegedPlugin()
        val privPatch = writePrivilegedPatch(privRunner != null)
        shellToolMounted = privPatch != null
        // 悬浮球控制工具：把「改悬浮窗」接到模型的工具列表里。
        // ⚠️ 必须早于引擎启动（overlay 要在 profile 解析前落盘），且**与特权无关**——
        //   悬浮球不需要任何身份，只要求用户开过开关并授权过悬浮窗。
        ensureBubbleTool()
        val bubblePatch = writeBubbleToolPatch()
        // 系统栏配色工具：把「改状态栏/导航栏颜色」接到模型的工具列表里。
        // ⚠️ 与 bubble 工具**完全并列且独立**（不同信箱、不同能力），同样与特权无关：
        //   改系统栏配色不需要任何授权。必须早于引擎启动（overlay 要在 profile 解析前落盘）。
        ensureStatusBarTool()
        val statusBarPatch = writeStatusBarToolPatch()
        // UI 调试工具：把「看/点这个 App 的界面」接到模型的工具列表里。
        // ⚠️ 与 bubble 工具**完全并列且独立**（不同信箱、不同能力）。
        //   同样必须早于引擎启动：overlay 要在 profile 解析前落盘。
        ensureUiTool()
        val uiToolPatch = writeUiToolPatch()
        // 浏览器工具：把「上网 / 登录 / 读页面 / 点按钮」接到模型的工具列表里。
        // ⚠️ 与 ui 工具**完全并列且独立**：ui 操作的是本 App 自己的界面，
        //   browser 操作的是一个独立的第二个 WebView（别的网站）。同样必须
        //   早于引擎启动：overlay 要在 profile 解析前落盘。
        ensureBrowserTool()
        val browserToolPatch = writeBrowserToolPatch()
        // 日志工具：把「读日志 / 看崩溃现场 / 生成诊断包」接到模型的工具列表里。
        // ⚠️ 与上面三个工具**完全并列且独立**（不同信箱、不同能力），同样与特权无关：
        //   读日志不需要任何授权。必须早于引擎启动（overlay 要在 profile 解析前落盘）。
        ensureLogsTool()
        val logsToolPatch = writeLogsToolPatch()
        // 定向隔离层（L1.5）：把上次定位到的坏插件条目在 patch 层禁掉。
        // ⚠️ 读的是 `RescueBoot` 的持久化状态，**不依赖**本轮启动的任何中间结果，
        //   所以放在这里（早于引擎启动、晚于所有 `- insert:` overlay 的生成）就够。
        val quarantinePatch = writeQuarantinePatch()
        // 桌面宠物状态桥：把引擎的 `session/event` 转成悬浮球的动作切换。
        // ⚠️ 与上面那条**并列且独立**：那条给模型 `bubble` 工具，这条让宠物自己会动。
        ensurePetBridge()
        val petPatch = writePetBridgePatch()
        // 悬浮球命令信箱：`bubble` 工具把命令写进 `$DSH_HOME/bubble-cmd.json`，
        // 这里轮询读走交给原生。**必须早于引擎启动** —— 否则模型第一次调工具时
        // 信箱还没人守，命令会一直等不到回执。
        BubbleCommandBridge.start(dshHome)
        // 日志命令信箱：`logs` 工具把请求写进 `$DSH_HOME/logs-cmd.json`，
        // 这里轮询读走交给原生。**必须早于引擎启动** —— 否则模型第一次调工具时
        // 信箱还没人守，请求会一直等不到回执。
        LogCommandBridge.start(dshHome)
        // 系统栏配色命令信箱：`statusbar` 工具把命令写进 `$DSH_HOME/statusbar-cmd.json`，
        // 这里轮询读走交给原生。**必须早于引擎启动** —— 否则模型第一次调工具时
        // 信箱还没人守，命令会一直等不到回执。
        SystemBarCommandBridge.start(dshHome)
        // 救援命令信箱：AI 通过插件把 `rescue-cmd.json` 写进 `$DSH_HOME`，这里轮询读走。
        // 三个动作：`status`（当前层级、摘了哪些插件、上次失败详情）、
        // `probe`（拿改动后的配置**真跑一次**引擎验证，配额见 PROBE_QUOTA）、
        // `switch-rescue` / `switch-back`（进出救援态）。
        //
        // ⚠️ **必须早于引擎启动**，理由与上面三条相同；而且对这条尤其要紧 ——
        //   救援场景下引擎可能压根起不来，那时用户唯一的自救手段就是让 AI 走这条路，
        //   所以信箱必须在**引擎启动之前**就已经有人守。
        RescueCommandBridge.start(dshHome)
        // 让模型知道「有特权身份可选、什么时候该用、用了会失去什么」。
        // ⚠️ 判据用**挂载结果**（`privPatch != null`）而不是 `privRunner != null`：
        //   前者才是「`shell` 工具真的注册了」。见 ensurePrivilegedPrompt 的 @param。
        ensurePrivilegedPrompt(privPatch != null)

        // root（uid 0）通道：**先端到端探测**再决定挂不挂。
        // ⚠️ 顺序是硬性的 —— 探测要起 su 进程（可能弹授权框），必须早于引擎进程
        //   启动，否则用户会在界面已经可用之后才看到那个框。见 [RootAccess]。
        // ⚠️ 探测结果有 24h 缓存，所以稳态下这里只是一次文件读。
        val rootRunner = ensureRootShell()
        val rootState = RootAccess.detect(context)
        ensureRootPlugin()
        val rootPatch = writeRootPatch(rootRunner != null && rootState.granted)
        rootToolMounted = rootPatch != null
        if (rootState.granted) {
            Log.i(TAG, "root 可用（${rootState.detail}），已挂载 root 工具")
        } else {
            Log.i(TAG, "root 不可用（${rootState.state}: ${rootState.detail}），root 工具未挂载")
        }
        // 让模型知道「这台设备有没有 root」，否则它会去猜、去反复试
        ensureRootPrompt(rootPatch != null)

        // pnpm 要落到 $PREFIX/bin，必须等 Termux 环境就位之后再装
        ensurePnpm()

        val binJs = File(engineDir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (!binJs.isFile) {
            _state.value = State.Failed("找不到引擎入口: ${binJs.absolutePath}")
            return@withContext
        }

        // ⚠️ overlay 的顺序是**语义**的一部分（`--patch` 可重复、按顺序应用，
        //   后应用的覆盖先应用的）。这里的顺序与改造前逐项一致，别顺手调。
        val overlays = listOfNotNull(
            mobileUiPatch,
            browserPanePatch,
            agnesMmPatch,
            marketPatch,
            sandboxPatch,
            workspacePatch,
            pluginManagerPatch,
            privPatch,
            rootPatch,
            bubblePatch,
            statusBarPatch,
            petPatch,
            uiToolPatch,
            logsToolPatch,
            browserToolPatch,
            // ⚠️ **必须排在最后**：这一层只做 `disabled: true`，而它要禁用的条目
            //   正是前面那些 `- insert:` 插进来的。`--patch` 按顺序应用，排在
            //   插入之前会让 patch 匹配不到目标 id（官方行为：warn 后跳过，静默失效）。
            quarantinePatch,
        )
        // ⚠️ 内置号池**刻意不在这个列表里**：它并入 profile 用户配置层，
        // 不走 overlay —— 理由见 [ensureKeyPoolProvider]。
        //
        // ⚠️ 存下来给试启动用（见 [activeOverlays]）：试启动必须与真实启动
        //   用**同一份** overlay，否则「探测能过、真实过不了」无法排查。
        activeOverlays = overlays
        val cmd = buildEngineArgs(nodeBin, binJs, targetProfile, overlays, resolveWebPort())

        Log.i(TAG, "启动引擎: ${cmd.joinToString(" ")}")

        val prefix = bootstrap.prefixDir.absolutePath

        val pb = ProcessBuilder(cmd)
            .directory(workspaceDir)
            .redirectErrorStream(true)

        pb.environment().apply {
            // ⚠️ 刻意**不设** LD_LIBRARY_PATH。
            //
            // 这里原先设了 `LD_LIBRARY_PATH=$prefix/lib`，当作「RUNPATH 没覆盖到时
            // 的兜底」。但它对 Termux 二进制**完全无用**（真机全量对比 $PREFIX/bin 下
            // 305 个 ELF，带与不带 LD_LIBRARY_PATH 的输出**零差异** —— Termux 的
            // RUNPATH 已被等长重写成 /data/data/com.dsh.android//usr/lib，自己找得到），
            // 却会**污染系统二进制**：它们本来该去 /system/lib64 找依赖，被这行带去
            // Termux 的 lib 目录，于是 screencap / input / app_process 全部
            // `CANNOT LINK EXECUTABLE "screencap": cannot locate symbol
            //  "CrcGenerateTable" referenced by "/system/lib64/libunwindstack.so"`。
            //
            // 后果不是「少了个功能」而是**误导**：screencap 在 app 域本来就因权限失败，
            // 现在失败得更早、错误信息指向动态库，模型会被带偏去反复试
            // `env -u LD_PRELOAD` 之类的无用方向（真机会话日志里实测发生过）。
            //
            // 去掉后 node 与 4 个原生模块（require-builtin / flock / node-pty /
            // sharp-wasm32）、bash/dpkg/pnpm/rg 全部实测正常。
            // PREFIX/HOME 让 DSH 内部按 Termux 习惯推导路径，与库搜索无关，保留。
            put("PREFIX", prefix)
            put("DSH_HOME", dshHome.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            // $PREFIX/bin 必须排在最前：node 自己 spawn 子进程（如 shell 工具）时
            // 要能找到 Termux 的 sh/bash 而不是系统的。
            put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
            // CA 证书在 Termux 私有 prefix 里，否则 HTTPS 请求会因证书链失败
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
            // 堆上限 + 预加载 HTTP header 净化垫片（见 ensureFetchGuard）
            put("NODE_OPTIONS", nodeOptions())
            // 镜像环境变量必须注入**引擎进程**，不能只靠 shell 工具那条路。
            //
            // ⚠️ 实测漏掉它的后果（2026-10-10，同一台设备）：
            //   `pkg install` 会调 `select_mirror()` 从 60+ 个上游镜像里**加权随机**
            //   重挑一个并覆写 `sources.list`，把 App 刚写好的国内快源冲掉。重置成
            //   USTC 后跑一次 `pkg install zlib`，源立刻变成 grimler.se。
            //   而 `TERMUX_PKG_NO_MIRROR_SELECT=1`（上游自己的开关）实测能挡住这次覆写。
            //
            // ⚠️ [TermuxBootstrap.getShellEnv] 早就带了这组变量，但那条路只覆盖
            //   `runProbeScript` 与 shell 工具；引擎进程自己 spawn 的 bash（模型装依赖
            //   用的就是它）走的是本函数的 environment，于是**恰好缺了这一条**。
            //
            // 代价实测（同一台设备、同一个包）：USTC 源 71 秒 vs 慢源 21 分钟。
            try {
                putAll(com.dsh.android.termux.MirrorProbe.shellEnv(bootstrap.loadMirrors()))
            } catch (t: Throwable) {
                // 读状态失败不影响引擎启动 —— 配置文件那一层仍然生效。
                Log.w(TAG, "注入镜像环境变量失败: ${t.message}")
            }
            // link()/linkat() 语义转译：SELinux 不允许 untrusted_app 对 app_data_file
            // 做硬链接，而 DSH 上游用硬链接做独占原子发布。见 [ensureLinkCompat]。
            //
            // ⚠️ 号池注入库已下线（改为服务端网关），现在这里只剩 linkcompat 一个。
            //    仍用列表拼接而不是覆盖，是为了将来加库时不用改结构。
            //
            // ⚠️ 只加在本进程：LD_PRELOAD 会一并继承给 node spawn 的子进程（如 shell
            // 工具）。linkcompat 只 hook link/linkat 且先试原生，对系统二进制无害。
            val preloads = listOfNotNull(linkCompat).map { it.absolutePath }
            if (preloads.isNotEmpty()) {
                // 空格分隔，与 boo-sandbox-run.c 里追加 LD_PRELOAD 的写法一致
                put("LD_PRELOAD", preloads.joinToString(" "))
            }
            // 内置号池的凭据引用指向**网关令牌**。
            //
            // ⚠️ 与旧逻辑的关键差异：以前这里只在注入库解出成功时才设置，因为
            // 占位符要靠注入库才会被替换 —— 没有库就宁可不设。现在令牌**不需要
            // 任何本地组件**，所以必须无条件设置，否则引擎解析 `apiKeyEnv` 时
            // 拿不到值，会报 `MISSING_CREDENTIAL`，号池直接不可用。
            put(KEY_ENV, KEY_PLACEHOLDER)
            // 沙箱 runner 靠它定位 LD_PRELOAD 拦截器。runner 会把它追加到子进程
            // 的 LD_PRELOAD 里（保留这里已有的 link 兼容层，两个库都生效）。
            put("BOO_SANDBOX_LIB", File(context.filesDir, SANDBOX_LIB_FILE).absolutePath)
            // uid 2000 通道脚本的路径，供特权插件读取（见 [ensurePrivilegedPlugin]）。
            // 经环境变量传递而不是让 Kotlin 拼 JS 字面量：路径里可能有反斜杠与引号。
            privRunner?.let { put("DSH_PRIV_RUNNER", it.absolutePath) }
            // Shizuku 桥的 socket 名，供 runner 走通道 0。
            // ⚠️ 这里传的是**纯名字**（`<包名>.shizuku`），不是带 `\0` 前缀的路径：
            //    envp 是 C 字符串，NUL 就是结束符 —— 传带 NUL 的值会被**静默截断**，
            //    runner 拿到空串、通道 0 整条不生效且毫无报错。`\0` 由 runner 自己补
            //    （见 runViaShizuku）。理由详见 ShizukuChannel.socketPath 的注释。
            // ⚠️ 与 DSH_PRIV_RUNNER 同理：引擎的 scrubbedParentEnv() 会剥掉所有
            //    `DSH_*`，插件必须显式转交（见 dsh-android-shell/lib/index.js）。
            put("DSH_SHIZUKU_SOCKET", ShizukuChannel.socketName(context))
            // root 通道脚本的路径，供 root 插件读取（见 [ensureRootPlugin]）。
            // 同上：走环境变量而不是拼 JS 字面量。
            rootRunner?.let { put("DSH_ROOT_RUNNER", it.absolutePath) }
            // 无线调试用的 adb 工具链目录，runner 靠它决定要不要启用无线通道。
            // 缺失时 runner 只走 legacy 5555，行为与改动前完全一致。
            adbWireless?.let { put("DSH_ADB_HOME", it.absolutePath) }
        }

        // ⚠️ 存下这一份给试启动用（见 [activeEnv]）：试启动必须与真实启动**逐项相同**，
        //   否则「探测能过、真实过不了」无法排查。**必须在 pb.start() 之前存**，
        //   否则第一次探测可能读到空表而被拒绝。
        activeEnv = pb.environment().toMap()

        // ── L1 自动隔离（P1）──────────────────────────────────────────────
        //
        // ⚠️ **位置是硬性的：必须在这里**，即 [activeOverlays] 与 [activeEnv] 都已
        //   就绪之后、真正 `pb.start()` 之前。
        //
        //   这两个基线原先是在 `start()` 靠后位置才赋值的，而触发闸门要求它们非空 ——
        //   也就是说，如果按"读起来更顺"的直觉把闸门放在选 profile 那里，闸门**每轮
        //   都会被自己拦下**，L1 永远不触发。这与本方案开头证伪的
        //   `consecutiveFailures` 是**同一个缺陷**：判据描述的时机与它存在的时机不重合。
        //   所以这里不改闸门（改成"没有基线也硬跑"会让探测结论不可信），
        //   而是把调用点挪到基线之后。
        //
        //   触发条件（**不是**「同一 bootId 内连续失败 ≥2 次」—— 那个条件永远不成立，
        //    见 [RescueBoot.Failure.consecutiveFailures] 里的实测证伪）：
        //      连续 ≥2 次真实启动失败，且失败原因**指向插件**，且当前是正常态。
        //
        //   ⚠️ 为什么在 `pb.start()` **之前**：隔离成功之后本次就要用正常 profile
        //     起来（用户的绝大多数插件保住了），而引擎是在 `bin.js` 启动时才读
        //     `package.json` 的 —— 所以在这里改清单，本次启动就生效。
        //
        //   ⚠️ 只在正常态跑：救援态走的是 rescue profile（压根没有第三方 bundle，
        //     没有可隔离的对象）；隔离态已经是上次隔离后的结果了，再跑一遍没有意义
        //     （幂等靠这个判断 + `.bak-l1`）。见 [maybeIsolateBadBundles]。
        //
        //   ⚠️ 隔离之后**不重跑 [preflight]**。L1 只动 `bundles` 里第三方的名字，
        //     而体检判的是核心 bundle 完不完整，判据没变、结论不会变。反过来，
        //     体检在发现核心损坏时会触发**整棵 engine/ 重解包**，那会把此刻已经
        //     就绪的 native addon 与补丁抹掉 —— 白跑一趟还引入新失败点。
        maybeIsolateBadBundles()

        // ⚠️ L1 可能刚刚把层级降级成 RESCUE（二分没收敛时，见 [maybeIsolateBadBundles]
        //   末尾）。但 `targetProfile` 与 `cmd` 是在这**之前**算好的，本次发车仍会用
        //   用户那个已经证明起不来的 web profile —— 白等 20 秒拿到同样的失败。
        //
        //   所以这里重新读一次层级：变了就**不发车**，改走既有的重启路径。
        //   复用 [requestRestart] 而不是自己再拼一遍启动流程，是因为它已经接好了
        //   `MainActivity` 那条 `State.Dead` 分支（清 `webViewPainted`、置
        //   `forceReloadOnReady`、重新 `start()`），多一条自建路径只会多一个失败点。
        //
        //   ⚠️ 条件**只能判 RESCUE**，不能写成 `tier != NORMAL`：ISOLATED 态意味着
        //     L1 **成功**摘掉了致命插件，而那时用用户的 web profile 启动恰恰是
        //     正确动作（绝大多数插件保住了）。写成 `!= NORMAL` 会把成功当成失败，
        //     把用户直接丢进救援模式、白丢全部插件。
        if (RescueBoot.tier().tier == RescueBoot.Tier.RESCUE &&
            targetProfile != RESCUE_PROFILE_NAME
        ) {
            Log.w(TAG, "L1 已把层级改为救援模式，本次不发车，改用救援 profile 重启")
            EventLog.record("engine.restart.for-rescue", mapOf("staleProfile" to targetProfile))
            requestRestart("L1 降级到救援模式")
            return@withContext
        }

        // 无线调试端口由 Kotlin 侧发现（Node 侧发不出 UDP 组播，见 WirelessDebugging）。
        // 常驻监听：端口会随用户重开无线调试/重启/切网络而变，只在启动时发现一次的话
        // 一变就永久失效。监听本身由系统 mdnsd 承担，开销可忽略。
        resumePrivilegedChannel()

        // ── P4：把「你现在处于降级态、可以这样修」写进 agent 指令 ──────────
        //
        // ⚠️ **必须在这里**，即 [maybeIsolateBadBundles] 之后。层级是在 L1 里才
        //   定下来的（它可能刚把 tier 改成 ISOLATED 或 RESCUE），早于它写提示词
        //   就会写进上一次的层级 —— 表现为「AI 被告知处于救援模式，实际不是」。
        //
        // ⚠️ 也**必须早于引擎启动**：`AGENTS.md` 是引擎启动时读的（agent-instructions
        //   插件），启动之后再写，这一轮就注入不进系统提示词了。
        ensureRescuePrompt()

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            _state.value = State.Failed("引擎进程启动失败: ${t.message}")
            return@withContext
        }
        process = proc

        // 只用一个后台线程从头到尾持续消费 stdout。
        // 不能用 use{} 读完 URL 就关流：引擎之后仍在写日志，64KB 管道缓冲写满后
        // 会阻塞在 write 上导致引擎彻底卡死；重复打开同一 inputStream 也会失败。
        // ⚠️ 用共享常量 [URL_PATTERN] 而不是就地写一份：试启动（[probeBoot]）
                        //   也用同一条正则判断"起来了没有"，两处各写一份迟早漂开，
                        //   而症状会是「试启动说成功、真启动说失败」这种无法解释的差异。
                        val urlPattern = URL_PATTERN
        val readyLatch = java.util.concurrent.CountDownLatch(1)
        var readyUrl: String? = null

        Thread {
            try {
                proc.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        // ⚠️ 引擎输出走**独立的 `engine.log`**，不进 `app.log`。
                        //
                        // 理由是容量：引擎会逐 token 打输出，一天能刷出几十 MB，
                        // 而 `app.log` 单文件上限只有 1MB。混在一起的话，原生侧的
                        // 关键日志（启动、插件、补丁）会被引擎刷掉 —— 而排查时
                        // 恰恰是原生侧那几十行最有价值。
                        //
                        // `LogStore.engine` 是异步写的，所以这一行**不会拖慢读流**
                        // （读流一慢，引擎写 stdout 就会阻塞，那是老问题的来源）。
                        LogStore.engine(line)
                        // ⚠️ **logcat 照打，但必须绕开 `DshLog` 的 sink**（2026-10-14
                        //   自检发现，同族第 23 例）。
                        //
                        // 原来这里是 `Log.i(TAG, "[engine] $line")`。本文件顶部是
                        // `import com.dsh.android.log.DshLog as Log`，而 `DshLog.i`
                        // 除了转发给 `android.util.Log` 之外，还会**再落一次盘**
                        // （见 `DshLog.emit`）—— 而上面那行 `LogStore.engine(line)`
                        // 已经落过了。于是同一条引擎输出在 `app.log` 与内存缓冲里
                        // 各存两份（tag 分别是 `DSH` 与 `engine`）。
                        //
                        // 真机证据（服务器上 74 个真实用户包）：**72 个**的 `app.log`
                        // 里混着引擎输出，占 11%~25% 的行；168 个崩溃现场文件里
                        // 「tag=[DSH] 且正文以 [engine] 开头」的行数与「tag=[engine]」
                        // 的行数**比值恒为 1.00** —— 一条不多一条不少，正是重复。
                        //
                        // 后果正是上面那段注释要避免的那个：`app.log` 单文件只有 1MB、
                        // `--- last 200 log lines ---` 只有 200 行预算，两者都被重复行
                        // 占掉一半，而 `engine-start-failed` 的根因（引擎自己打印的
                        // `dsh: startup failed: … plugin did not activate`）就挤在这半边里。
                        //
                        // ⚠️ **不能用 `Log.i`**（那会走回 sink），要用全限定名
                        // `android.util.Log.i` —— 这违反 `DshLog` 类注释里「调用点不出现
                        // 全限定写法」那条，但这里正是**必须**绕开的场合：落盘已经由
                        // `LogStore.engine` 负责，此处只要 logcat。两处都不丢。
                        android.util.Log.i(TAG, "[engine] $line")
                        synchronized(logLines) {
                            logLines.addLast(line)
                            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
                        }
                        if (readyUrl == null) {
                            val m = urlPattern.find(line)
                            if (m != null) {
                                readyUrl = m.groupValues[1]
                                readyLatch.countDown()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "引擎输出流结束: ${t.message}")
            } finally {
                // 流关闭（引擎退出）时也要唤醒等待方，避免白等到超时
                readyLatch.countDown()
                // ⚠️ **引擎一死就必须把 `engine.log` 的缓冲刷出去**（2026-10-14 自检发现，
                //   同族第 24 例）。
                //
                // `engine.log` / `web.log` 是**异步档**：`LogStore.writeAsync` 攒着
                // 不 flush，只靠 `BufferedWriter` 的 8 KB 缓冲与「超 1 MB 才 flush」
                // 那条阈值（见那里的注释）。引擎**正常**跑时这没问题 —— 它迟早会
                // 攒够 8 KB。
                //
                // 但**引擎起不来时正好相反**：它只来得及打几十行（实测约 5 行、
                // 不足 2 KB）就退出，缓冲**永远到不了阈值** —— 而那几十行恰恰是
                // 唯一能解释「为什么起不来」的证据（实测是
                // `dsh: startup failed: 1 required plugin did not activate` +
                // `credentials-local: .credentials.yaml is readable beyond …`）。
                // 进程一退，缓冲随进程消失，`engine.log` 停在 0 字节。
                //
                // 真机证据（服务器上 73 个真实用户包）：含 `engine-start-failed`
                // 现场的包有 **33 个**，其中包内有 `engine.log` 的只有 **6 个** ——
                // 且那 6 个的时间戳是**上一次运行**留下的残卷（23:53 写的，
                // 包生成于 04:13），不是本次故障的输出。也就是说这个文件在最需要
                // 它的场合恰好是空的。
                //
                // 放在 `finally` 里是因为三条退出路径都要覆盖：引擎启动失败、
                // 运行期崩溃退出、以及正常结束。
                runCatching { LogStore.flushAsync() }
                // ⚠️ **运行期退出检测就在这一行**。
                //
                // stdout 读到 EOF ⇔ 引擎进程真的没了（管道写端全关）。这是唯一
                // 可靠的死亡信号：`process.isAlive` 要靠轮询，而轮询在 App 被
                // 冻结时同样停摆；EOF 是内核主动通知的，不需要我们醒着。
                //
                // 判据用「曾经就绪过」而不是「现在是 Ready」——后者会被
                // 紧随其后的 `_state.value = State.Failed(...)` 覆盖掉顺序
                // （见下面的 got/url 分支），用 readyUrl 更稳。
                //
                // 只读一次 volatile 快照，避免与 start() 的另一次调用赛跑：
                // 这次线程属于**这一个** proc，若 process 已被换成新的进程，
                // 说明有人重启过引擎，此时不该把状态打成 Dead。
                markEngineDeadIfCurrent(proc, readyUrl != null)
            }
        }.apply { isDaemon = true; name = "dsh-engine-output" }.start()

        val got = readyLatch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val url = readyUrl

        if (got && url != null) {
            // ── 引擎**起来了**，但 gateway 可能已经废了（本 App 的第三种故障形态）──
            //
            // ⚠️ **这一段的判据与处置都必须早于 `clearFailure()`**，见下面两条注释。
            //
            // 真机现场（OPPO PKJ110 / v18）：引擎打印了 URL、界面正常加载，但
            // `typert-loader` 的 contributor 注册失败 ⇒ gateway 的严格定义被撤下
            // ⇒ `session/control` / `session/modelCatalog` / `directoryPicker/list`
            // 全部报 `its strict definition was withdrawn and SRC fallback is forbidden`。
            // 用户「选不了工作区、发不出消息、插件面板打不开」，而**界面上没有任何
            // 按钮**——救援阶梯的每一道门都要求「启动失败」，这次启动是成功的。
            //
            // 时序是可靠的：真机 `engine.log` 里 typert 那段报错在 `dsh web:`
            // **之前 21ms**（13:29:30.709 vs .730），而 stdout 循环是先
            // `logLines.addLast(line)` 再 `readyLatch.countDown()`（见上面那段），
            // 所以等到这里时证据必然已经在缓冲里。
            val gatewayTail = synchronized(logLines) { logLines.toList() }
            val gatewayCulprit = RescueBoot.parseGatewayFailure(gatewayTail)
            if (gatewayCulprit != null) {
                Log.e(
                    TAG,
                    "引擎打印了 URL 但 typert gateway 整体下线（涉事=${gatewayCulprit.ifEmpty { "未解析到包名" }}）" +
                        " —— 界面会正常加载，但会话/模型列表/目录选择全部不可用",
                )
                EventLog.record(
                    "engine.gateway-degraded",
                    mapOf("culprit" to gatewayCulprit.ifEmpty { "unknown" }),
                )
                // ⚠️ **先记失败**，再决定降级。这份记录让「连续失败几次」「涉事是谁」
                //   在重启后仍然可查 —— `start()` 成功分支本来会 `clearFailure()`，
                //   而这次的「成功」是假的（见 [RescueBoot.recordGatewayFailure] 的说明）。
                RescueBoot.recordGatewayFailure(
                    bootId = BootState.bootId,
                    tailLines = gatewayTail,
                    culprit = gatewayCulprit,
                )
                // 正常态 ⇒ 降级到 L2 救援模式并**立刻重启**，用户零操作。
                // L2 是**非破坏性**的（不动用户配置、有常驻横幅、可一键退出），
                // 所以这条路径可以放心走（同 [escalateToRescue] 的 KDoc）。
                //
                // ⚠️ 只在 NORMAL 态推。已是 RESCUE 时再推没有意义，而且会把横幅的
                //   `since` 刷成每次启动的时刻（「自 XX 起」就永远显示刚刚）。
                // ⚠️ `rescueAvailable == false` 时**不能**推：那个 profile 建不出来，
                //   推过去下次启动会直接失败（见上面 `ensureRescueProfile` 那段）。
                val tierNow = RescueBoot.tier().tier
                if (tierNow == RescueBoot.Tier.NORMAL && rescueAvailable) {
                    escalateToRescue(
                        "引擎起来了但 typert gateway 整体下线（contributor 注册失败：" +
                            "${gatewayCulprit.ifEmpty { "包名未解析到" }}）",
                        // 这条降级**没有经过 L1 二分**（引擎是「成功」启动的，L1 的闸门
                        // 一个都没开），所以事件里必须如实标成 gateway 检测，不能冒 L1 的名。
                        from = "gateway",
                    )
                    Log.w(TAG, "已降级到救援模式，立刻重启引擎（下次启动不加载任何第三方插件）")
                    // ⚠️ **不设 Ready**：直接走既有重启路径，避免界面先加载再重启闪一下。
                    //   状态打成 [State.Dead] 而不是 [State.Failed] —— 引擎没崩、页面能
                    //   加载，而 Failed 会把用户引向**破坏性**的「安全模式」按钮；
                    //   Dead 的「原样重启」正是这里要的（同 [requestRestart] 的 KDoc）。
                    requestRestart("typert gateway 整体下线，切救援模式")
                    return@withContext
                }
                // 走到这里 = 已经是救援态仍失效，或救援 profile 不可用。
                // 两者都**不是**第三方插件的问题（rescue 不含任何第三方 bundle），
                // 所以不再自动推层级 —— 只保持界面可用，让常驻横幅把事实说出来
                // （不变量 I4：降级状态必须常驻可见 + 可一键退出）。
                Log.w(
                    TAG,
                    "gateway 仍失效（层级=${tierNow.id}，救援可用=$rescueAvailable），不再自动降级",
                )
                EventLog.record(
                    "engine.ready",
                    mapOf(
                        "ms" to (System.currentTimeMillis() - startMs).toString(),
                        "port" to portOf(url),
                        "degraded" to "gateway",
                    ),
                )
                _state.value = State.Ready(url)
                Log.i(TAG, "引擎就绪: $url")
                startHeapWatch(proc)
                return@withContext
            }
            _state.value = State.Ready(url)
            Log.i(TAG, "引擎就绪: $url")
            startHeapWatch(proc)
            // ⚠️ 引擎**真的起来了** ⇒ 清掉上次的失败记录。
            //
            //   见 RescueBoot 的类注释：这份记录是「上次为什么起不来」的唯一线索，
            //   救援会话里的 AI 全靠它。但它在**成功之后必须清掉**，
            //   否则下次再出问题时，AI 看到的是早就不成立的旧失败 ——
            //   又一处「结论与它描述的时刻不是同一个」（同族已 28 例）。
            //
            //   位置要紧：必须放在「确实拿到 URL」这个分支里，不能放在别处。
            //   `got && url != null` 就是「引擎打印过 URL」的严格判据。
            //
            //   ⚠️ 上面那段 gateway 检测已经 `return@withContext` 走了，
            //   所以走到这里就是**真·成功**，可以放心清。
            RescueBoot.clearFailure()
            // 事件时间线里记一笔「从起进程到就绪花了多久」。
            // ⚠️ 这是「打开 App 慢」这类模糊反馈唯一能定量的地方：没有它，
            //   用户说「卡」，我们只能猜是引擎慢、解包慢还是网络慢。
            EventLog.record(
                "engine.ready",
                mapOf("ms" to (System.currentTimeMillis() - startMs).toString(), "port" to portOf(url)),
            )
        } else if (userStopped || process !== proc) {
            // ⚠️ **我们自己停掉的，不是启动失败。**（2026-10-15 修，同族第 25 例）
            //
            // `readyLatch` 有三条唤醒路径，上面那条注释只交代了前两条：
            //   ① 正则匹配到 URL ⇒ 引擎起来了
            //   ② stdout 读到 EOF ⇒ 引擎死了
            //   ③ **`stop()` 主动 destroy 旧进程** —— 也会让 stdout 立刻 EOF
            //
            // 第 ③ 条与「引擎自己崩了」在 `await` 这一侧**完全同形**，于是一路走到
            // 下面那个 `else`，被记成「引擎启动失败」。而 `stop()` 明明已经把状态
            // 设成了 [State.Idle]，紧接着被 `State.Failed` 覆盖 —— 用户看到失败
            // 浮层，更糟的是 `RescueBoot.recordFailure` 把「连续启动失败」+1。
            //
            // 真机现场（服务器 `20261014-173741-e2020a5e`，OPPO PKJ110 / Android 16 /
            // v21 system，`20261014-165820-engine-start-failed.txt`）：
            //
            // ```
            // 16:58:16.174 main                用户请求退出降级态（当前 rescue）
            // 16:58:16.176 main                请求界面重启引擎：用户请求退出降级态
            // 16:58:16.177 main                停止引擎            ← stop()
            // 16:58:16.479 main                引擎已退出: …，准备自动重启（第 2 次）
            // 16:58:16.483 main                [connection] connection lost, retry #1
            // 16:58:17.195 DefaultDispatcher-2 启动引擎: …              ← 新 start()
            // 16:58:18.443 main                界面就绪回调超时（WebView 内核 138）
            // 16:58:20.152 DefaultDispatcher-1 停止引擎            ← enterSafeMode → stop()
            // 16:58:20.153 main                [rescue-auto] 连续失败仅 0 次（需 ≥3）
            // 16:58:20.160 DefaultDispatcher-2 已记录现场: crash/…-engine-start-failed.txt
            //                                  —— 引擎启动失败（引擎进程在打印 URL 之前就结束了（等了 3672 ms））
            // 16:58:20.160 DefaultDispatcher-2 engine.failed reason=引擎进程在打印 URL 之前就结束了（等了 3672 ms）
            // 16:58:23.155 DefaultDispatcher-1 安全模式：已隔离损坏的文件 cordis.patch.yml → …
            // ```
            //
            // 两次 `停止引擎` 都是**主动**的（`requestRestart` 与 `enterSafeMode`），
            // 而那条 3672 ms 的「启动失败」纯粹是第 ③ 条唤醒路径的产物 ——
            // 引擎**从未**失败过，是被人叫停的。
            //
            // ⚠️ 后果不止一行错日志：这次假失败触发了 `enterSafeMode`（推开用户的
            //    `cordis.patch.yml` / `package.json`、摘掉 7 个第三方 bundle、删掉
            //    引擎版本戳），代价是用户重解包 15 秒 + 插件配置全丢。也就是说
            //   这条误判把「切个 profile」变成了**破坏性操作**。
            //
            // 判据与 [markEngineDeadIfCurrent] 完全同源（那里早就在用 `userStopped`），
            // 只是这里漏了：
            //   · `userStopped` ⇒ 是我们自己停的，状态已由 [stop] 设为 [State.Idle]
            //   · `process !== proc` ⇒ 这一轮已被另一次启动接管（stop 之后又 start 了），
            //     这一轮的任何结论都不该落到全局状态上
            // 两者都成立时**必须原样返回**，不能改状态、不能记失败。
            Log.i(
                TAG,
                "启动等待被主动停止打断（已等 ${System.currentTimeMillis() - startMs} ms），不算启动失败",
            )
            return@withContext
        } else {
            val tail = synchronized(logLines) { logLines.joinToString("\n") }
            // ⚠️ **判据是「等满了没有」，不是「进程还活着没有」。**
            //
            // 这里原来写 `if (proc.isAlive) "超时未打印 URL" else "引擎进程已退出"`，
            // 是**错的**，会把最常见的一种情况报成「超时」：
            //
            // `readyLatch` 除了「正则匹配到 URL」之外，还在读 stdout 那条线程的
            // `finally` 里被 countDown（见上面 `流关闭（引擎退出）时也要唤醒等待方`）。
            // 于是引擎一退出，`await` **立刻**返回，而此刻 `proc.isAlive` 往往还是
            // true（`Process.isAlive` 要等子进程被 reap 之后才翻假）。结果：
            // 引擎明明**刚启动就死了**，界面却写「超时未打印 URL」——
            // 把「启动即崩」误导成「起得慢」，排查方向直接带偏。
            //
            // 真机证据（53 个诊断包、80 场 engine-start-failed）：`READY_TIMEOUT_MS`
            // 是 120_000，而全部 9 场「超时未打印 URL」的实际等待只有
            // **6873 / 6911 / 6911 / 6911 / 7356 / 8009 / 10699 ms** ——
            // 没有一场等满过 120 秒，所以它们**全都不是超时**。
            //
            // 改判据后文案才与事实一致，并且把等待时长一并写进原因里
            // （原来只有崩溃现场文件里才有，日志行里看不到）。
            val waited = System.currentTimeMillis() - startMs
            val reason = if (waited >= READY_TIMEOUT_MS) {
                "等待 ${waited / 1000} 秒仍未打印 URL（确为超时）"
            } else {
                "引擎进程在打印 URL 之前就结束了（等了 ${waited} ms）"
            }
            // ⚠️ **顺序是要害：先落盘「为什么失败」，再广播 `State.Failed`。**
            //
            //   这两个动作的**读者是不同线程**，而广播会**同步**唤醒 main 线程上
            //   的收集者（`MainActivity` 的 `collect { }` 里那句
            //   `engine.shouldAutoEnterSafeMode()`），后者立刻去读
            //   `RescueBoot.lastFailure()`。
            //
            //   原先的顺序是反的（先广播、后记录），于是 main 读到的永远是**上一次**
            //   的失败记录 —— 真机现场（`20261015-013148-76dfff48.zip`，
            //   Xiaomi 24117RK2CC / Android 17 / v21 gecko）：
            //
            //   ```
            //   01:31:31.462 [I] #101 main                 [rescue-auto] 连续失败仅 0 次（需 ≥3）
            //   01:31:31.470 [E] #102 DefaultDispatcher-2  已记录现场: engine-start-failed
            //   ```
            //
            //   同一场失败，读记录的线程比写记录的线程**早 8 ms**。后果是
            //   `consecutiveFailures` 每次都被读成比实际小 1 —— 判据里的门
            //   （`>= 3`）**永远差一次**，于是自动进安全模式（L3）这条兜底
            //   整条阶梯**整体推迟一轮**：引擎本该在第 3 次失败时自救，实际要等到
            //   第 4 次。而这一轮里用户面对的是引擎起不来、界面完全不可用，
            //   只能自己反复重开 App 去凑那个数。这正是本项目缺陷族
            //   「结论与它描述的对象不是同一个」在**时序**上的又一例。
            //
            //   ⚠️ 说清界限：这是「晚一轮」而**不是**「永不触发」。`tier` 升到
            //      RESCUE 后 tier 这一条会长期成立，所以下一轮 streak 追上时仍会
            //      触发。别把它写成「永不」—— 那本身又是一次同族失真。
            //
            //   把记录提到广播之前即可根除：广播之后任何时刻读，记录都已就位。
            //   ⚠️ 别改成「广播后再补一次记录」—— 那会让同一场失败被记两次，
            //      计数翻倍，L1/L2/L3 全部提前触发（另一种失真）。
            val tailList = synchronized(logLines) { logLines.toList() }
            RescueBoot.recordFailure(
                bootId = BootState.bootId,
                exitCode = runCatching { proc.exitValue() }.getOrNull(),
                tailLines = tailList,
            )
            _state.value = State.Failed("引擎启动失败（$reason）。最后输出:\n$tail")
            // ⚠️ **P0：把「真实启动失败」变成可读的状态**（见 RescueBoot 的类注释）。
            //
            // 这里传的是**真实启动**的 stdout 尾部 —— 这一点是整个修复的要害：
            // 启动前体检（[preflight]）读的是 `--dump-config` 的**静态输出**，
            // 而它看不见插件在 `apply()` 期抛的异常。真机反例（2026-10-14，
            // 某小米机型 / Android 16 / v18）：`dump-config` 退出码 0、
            // 4 个 required 条目全在，体检因此**放行**，引擎照死 —— 连续 8 次。
            //
            // 所以判据必须换成「引擎自己说了 required plugin did not activate」，
            // 而那行只在真实启动的输出里。
            //
            // ⚠️ 顺序：必须在 CrashRecorder 之前 —— 下面那步要写崩溃现场，
            //   而现场文件里会读事件时间线；先把「为什么失败」的结构化事实落盘，
            //   保证即使现场写入失败也留下了线索。
            //   （记录本身已提到上面 `State.Failed` 之前，见那段说明。）
            // ⚠️ 「引擎起不来」是本 App 最严重的一类故障（界面直接不可用），
            //   所以要写**崩溃现场文件**而不只是日志行：现场文件里带着
            //   最后 200 行日志与设备快照，用户点「导出日志」就能一次性带走。
            CrashRecorder.recordAnomaly(
                "engine-start-failed",
                "引擎启动失败（$reason），已等 $waited ms",
                'E',
            )
            EventLog.record("engine.failed", mapOf("reason" to reason))
        }
    }

    /**
     * 让 uid 2000 特权通道回到「尽力可达」状态：常驻监听 + 一次性自愈 + 周期守护。
     *
     * ## 为什么单独抽出来
     *
     * 这三件事原先只写在 [start] 的**新建进程那条路**上，而 [start] 开头有个
     * 「引擎已在运行 → 直接 return」的早返回 —— 用户「清后台重开 App」时命中的
     * 恰恰是那个早返回（引擎进程被前台服务 `START_STICKY` 保着），于是整块自愈
     * 一次都不跑。这正是用户报的「关掉 WiFi 再清后台重开，权限就没了」里
     * **后半段**的根因。
     *
     * 抽成方法之后两条路都调用它，语义是「无论引擎是刚起还是早就在跑，都把特权
     * 通道重新拉一遍」。幂等：监听与守护都带「已在跑就返回」的判据，自愈本身也幂等
     * （5555 正常时 runner 第一句探测就返回）。
     *
     * ⚠️ **自愈与守护都在后台线程**，本方法本身不阻塞。
     * ⚠️ 依赖的路径参数从字段现取（`bootstrap` / `dshHome` / `filesDir`），
     *   不依赖 [start] 的局部变量 —— 早返回那条路上那些局部变量根本不存在。
     */
    private fun resumePrivilegedChannel() {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        // runner 不存在 = 连脚本都没解出来，后面两件事都无从谈起
        if (!runner.isFile) return
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        val portFile = File(dshHome, WirelessDebugging.PORT_FILE)
        val nodeBin = bootstrap.nodeBinary

        // 1) 常驻监听无线调试端口（端口会变，一变就永久失效）
        WirelessDebugging.startWatcher(context, portFile)

        // 2) 立即自愈一次：重启手机后 5555 必然消失（`persist.adb.tcp.port` 写不进去），
        //    这里顺手切一次，省掉「用户得自己点一下切到固定端口」这一步。
        Thread {
            runCatching {
                WirelessDebugging.ensureTcpPort(
                    context = context,
                    nodeBin = nodeBin,
                    runner = runner,
                    adbHome = adbHome,
                    dshHome = dshHome,
                )
            }.onSuccess {
                // ⚠️ 成功也要打：这条自愈**没有**任何界面反馈，不打日志的话
                //   「到底切没切成功」只能去翻 runner 的 stderr。
                if (it.ok) Log.i(TAG, "特权通道自愈：${it.message}")
                else Log.i(TAG, "特权通道自愈未切换固定端口：${it.message}")
            }.onFailure { Log.w(TAG, "特权通道自动切固定端口失败: ${it.message}") }
        }.start()

        // 3) 周期守护：这是「过一阵权限自己掉了」唯一的自动恢复路径。
        //    ⚠️ 没有它的话，手机重启 / adbd 重启 / 端口被抢都只能靠用户手点。
        if (adbHome.isDirectory) {
            WirelessDebugging.startFixedPortGuard(
                context = context,
                nodeBin = nodeBin,
                runner = runner,
                adbHome = adbHome,
                dshHome = dshHome,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 引擎堆水位监控（防止可预测的 OOM 突然打死界面）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 堆水位监控线程。
     *
     * ## 为什么需要它（真机取证，2026-10-27）
     *
     * 真机 `files/logs/engine.log` 里 **10 次**同样的 FATAL：
     *
     * ```
     * Mark-Compact 1004.4 (1026.1) -> 1004.4 (1026.1) MB, 1903.46 / 0.00 ms
     * FATAL ERROR: Ineffective mark-compacts near heap limit
     * ```
     *
     * 注意 GC 前后都是 **1004.4 MB**、释放量 0.00 —— 一个字节都没回收。
     * 这不是"堆不够大"，是**真实泄漏**（可达对象一直在涨）。
     *
     * 而 `events.jsonl` 里 10 次退出的运行时长是：
     * `11.1h / 7.8h / 7.7h / 7.8h / 7.8h / 7.7h / 7.8h` —— **规整得可怕**，
     * 折算约 130 MB/小时线性增长。也就是说：
     *
     * · 崩溃时刻是**可预测的**（约 7.7 小时）；
     * · 提高堆上限（[oldSpaceMb]）只能把 7.7 小时推成约 24 小时，**治不了本**；
     * · 用户每次撞上的体验都是：正用着 → 界面突然全无响应 → 等 27 秒才恢复
     *   （`engine.ready ms=27534`）。
     *
     * 既然崩溃可预测，就不该"等它崩"，而该**在它崩之前有序重启**。
     *
     * ## 为什么用 /proc 而不是 Node 自报
     *
     * 读 `VmRSS` 不需要在引擎里跑代码、不需要改引擎、不依赖任何协议 ——
     * 引擎进程就是我们自己 spawn 的，pid 一直在手上。
     * `VmRSS` 包含 V8 堆 + native + code space，比 `heapUsed` 更接近
     * "这个进程真的吃了多少"，正是 OOM 的判据。
     *
     * @see HEAP_WATCH_INTERVAL_MS
     * @see heapRestartRatio
     */
    @Volatile
    private var heapWatch: Thread? = null

    /** 停止堆水位监控。幂等；由 [stop] 调用。 */
    private fun stopHeapWatch() {
        val t = heapWatch ?: return
        heapWatch = null
        t.interrupt()
    }

    /**
     * 启动堆水位监控。
     *
     * ⚠️ **在引擎就绪之后才启动**：`process` 还没起来时读不到 `/proc`。
     * ⚠️ 幂等：已在跑就先停掉旧的（重启引擎时会有上一代的线程）。
     */
    private fun startHeapWatch(proc: Process) {
        stopHeapWatch()
        val limitMb = oldSpaceMb()
        // 水位 = 上限的 [heapRestartRatio]。留出余量是因为从"判定"到"重启完成"
        // 之间引擎还在涨，而重启本身要 10+ 秒。
        val triggerMb = (limitMb * heapRestartRatio()).toInt()
        val t = Thread {
            var warned = false
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(HEAP_WATCH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // 引擎已经换了/停了就别再看这个 proc。
                if (process !== proc || userStopped) return@Thread
                val rssMb = readRssMb(proc)
                if (rssMb <= 0) continue
                if (rssMb >= triggerMb) {
                    /*
                     * 到水位了。**只重启一次，不要反复触发。**
                     *
                     * 用 `warned` 而不是直接 return：重启是异步的（`restart()`
                     * 走状态机），如果这里立刻退出，重启失败时就再没人看水位了。
                     * 记一次日志后保持监控，但不再重复触发 —— `restart` 成功后
                     * `startHeapWatch` 会起一个新线程（旧的被 interrupt 掉）。
                     */
                    if (!warned) {
                        warned = true
                        Log.w(
                            TAG,
                            "引擎堆水位 ${rssMb}MB 已达上限的 " +
                                "${(heapRestartRatio() * 100).toInt()}%（$triggerMb / $limitMb MB），" +
                                "主动重启以免用户撞上 OOM 闪崩（真机实测约 130MB/小时线性增长）",
                        )
                        EventLog.record(
                            "engine.heap-high",
                            mapOf("rssMb" to rssMb.toString(), "triggerMb" to triggerMb.toString()),
                        )
                        // 在主线程之外发起重启是安全的：restart 内部自己切线程。
                        runCatching { requestRestart("堆水位过高") }
                    }
                } else if (rssMb < triggerMb / 2) {
                    // 掉回一半以下说明水位过去了（比如用户清空了长会话），允许再次触发。
                    warned = false
                }
            }
        }
        t.isDaemon = true
        t.name = "dsh-heap-watch"
        heapWatch = t
        t.start()
        Log.i(
            TAG,
            "堆水位监控已启动：上限 ${limitMb}MB，水位 ${triggerMb}MB，" +
                "每 ${HEAP_WATCH_INTERVAL_MS / 60_000} 分钟检查一次",
        )
    }

    /**
     * 读引擎进程的 `VmRSS`（MB）；读不到给 -1。
     *
     * ⚠️ **不能用 `proc.pid()`**：那是 Java 9 的 API，Android 的 `java.lang.Process`
     *   没有它（`Unresolved reference: pid`，已实测编译失败）。Android 上
     *   `ProcessImpl` 有一个私有 `int pid` 字段，只能用反射读。
     *
     * ⚠️ 反射失败时**必须安静返回 -1**，不能抛：这条路径跑在监控线程上，
     *   而监控本身是锦上添花 —— 读不到就退化成"不监控"，绝不能因为它影响引擎。
     *   （不同 ROM / 未来 Android 版本把 `ProcessImpl` 改名或换实现都可能发生。）
     */
    private fun readRssMb(proc: Process): Int = runCatching {
        val pidField = proc.javaClass.getDeclaredField("pid").apply { isAccessible = true }
        val pid = pidField.getInt(proc)
        if (pid <= 0) return@runCatching -1
        val status = File("/proc/$pid/status").readText()
        val kb = Regex("VmRSS:\\s+(\\d+)\\s+kB").find(status)
            ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return@runCatching -1
        (kb / 1024L).toInt()
    }.getOrDefault(-1)

    fun stop() {
        stopHeapWatch()
        // Shizuku 桥随引擎一起收：它的接受线程是 daemon、名字随进程释放，
        // 但显式关掉能让下一次 start() 重新绑定同一个名字而不撞车。
        ShizukuChannel.stop()
        process?.let { p ->
            Log.i(TAG, "停止引擎")
            // ⚠️ 必须在 destroy 之前置位：destroy 之后 stdout 线程会立刻读到 EOF
            //   并进 markEngineDeadIfCurrent，两者是并发的。
            userStopped = true
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
        process = null
        // 引擎停了就别再占着 mDNS 监听（NsdManager 的 listener 注册是全局资源）
        WirelessDebugging.stopWatcher()
        // 守护线程同理：引擎都停了，再去切 adbd 没有意义，还白耗电
        WirelessDebugging.stopFixedPortGuard()
        _state.value = State.Idle
    }

    /**
     * 请求**界面**重启引擎（供救援信箱的 `switch-rescue` / `switch-back` 用）。
     *
     * ## 为什么要拐一道，而不是直接 `stop()` + `start()`
     *
     * 换 profile 之后界面必须**重新加载 Web GUI**：旧页面连的是旧引擎的端口，
     * 而 `--profile rescue` 起来的是**另一个引擎实例**（另一个端口、另一个 token）。
     * 光在后台重启引擎、不重载页面，用户看到的就是一个永远连不上的界面。
     *
     * 重载页面的能力在 `MainActivity`（`forceReloadOnReady` + `loadUrl`），
     * 而 `EngineManager` 不该反向持有 Activity —— 所以这里只发一个**状态信号**，
     * 让 `MainActivity.observeEngine` 走它已有的 `State.Dead` 重启路径
     * （那条路径已经会把 `forceReloadOnReady` 置位并重新 `loadUrl`，
     * 正是这里需要的行为，不必新写一套）。
     *
     * ## 为什么用 `State.Dead` 而不是 `State.Failed`
     *
     * 见 `State.Dead` 的 KDoc：`Failed` 的界面分支会引导用户去点「安全模式」，
     * 而安全模式会**推开用户的插件配置** —— 切 profile 是正常操作，不该诱导
     * 用户做破坏性动作。`Dead` 的语义「原样重启」正好匹配。
     *
     * ⚠️ 必须**先** `stop()`（把 `userStopped` 置位并杀死旧进程），否则
     *   `start()` 会在开头命中「引擎已在运行」的早返回，profile 根本不会切换。
     */
    fun requestRestart(reason: String) {
        Log.i(TAG, "请求界面重启引擎：$reason")
        EventLog.record("engine.restart-requested", mapOf("reason" to reason))
        stop()
        _state.value = State.Dead(reason)
    }

    /**
     * 引擎**运行期**退出时的收口（由 stdout 消费线程在读到 EOF 时调用）。
     *
     * @param proc     读到 EOF 的那个进程实例。
     * @param wasReady 它是否曾经打印过带 token 的 URL。
     *
     * ## 为什么需要它
     *
     * 原先 `_state` 只在「启动期」被写：进程一启动就 `Ready(url)`，此后**再也没有
     * 任何一处**会因进程退出而更新它。于是引擎被系统回收之后，界面仍停在
     * `Ready(url)`、WebView 仍是上一帧渲染好的画面 —— 用户看不出任何异常，
     * 直到发消息失败。更糟的是前端的重连逻辑打的是那个**已经没人监听的端口**，
     * 报出来的错也是误导性的。
     *
     * ## 为什么是 [State.Dead] 而不是 [State.Failed]
     *
     * 见 `State.Dead` 的 KDoc：启动失败要「安全模式」推开配置，运行期死亡只需
     * 原样重启。混在一起会诱导用户去点那个破坏性的按钮。
     *
     * ## 判据
     *
     * - `process !== proc` ⇒ 已经有人重启过引擎（或 stop 过了），本次 EOF 属于
     *   被换掉的旧进程，**不动状态**。
     * - `userStopped` ⇒ 是我们自己停的，状态已由 [stop] 设为 [State.Idle]。
     * - 没 ready 过 ⇒ 是启动失败，那条路径由 `start()` 自己的超时分支负责
     *   （它会给出更准确的 reason + 日志尾巴），这里不抢。
     */
    private fun markEngineDeadIfCurrent(proc: Process, wasReady: Boolean) {
        if (userStopped) return
        if (process !== proc) return
        if (!wasReady) return
        /*
         * ⚠️ `exitValue()` 拿不到时**要短暂重试**，不能直接记 null。
         *
         * 这个方法是被 stdout 读线程在 `finally` 里调的（EOF ⇔ 引擎没了），
         * 而 `Process.exitValue()` 在子进程**还没被 reap** 时会抛
         * `IllegalThreadStateException` —— EOF 与 reap 之间有一个很短但真实的窗口。
         *
         * 真机现场就是这样：`crane ... reason: exitCode=null 进程已退出`。
         * 于是最有价值的那一个数字（134 = SIGABRT）**在每一份现场里都丢了**，
         * 只能从 `recent events` 的 `engine.exit code=134` 里间接看出来。
         *
         * 最多等 300ms（3 × 100ms）：这已经远超 reap 的实际延迟，
         * 又不至于拖住这条线程 —— 它后面还有 flush 和状态置位要做。
         */
        var code = runCatching { proc.exitValue() }.getOrNull()
        if (code == null) {
            for (i in 0 until 3) {
                if (code != null) break
                runCatching { Thread.sleep(100L) }
                code = runCatching { proc.exitValue() }.getOrNull()
            }
        }
        val detail = if (code != null) "退出码 $code" else "进程已退出（未能取到退出码：EOF 后 300ms 内仍未 reap）"
        Log.w(TAG, "引擎在运行期退出（$detail），界面已失去后端")
        /*
         * ⚠️ **必须把引擎自己的输出一起写进现场**（2026-10-27 真机审查补）。
         *
         * 真机上这个崩溃每天来 2–3 次（`20261023` 至 `20261026` 连续 6 天），
         * `recent events` 里每次都写着同一个 `engine.exit code=134`：
         *
         *     134 = 128 + 6 = SIGABRT
         *
         * 在 Node 里它几乎总是这几种之一：`FATAL ERROR: Reached heap limit`、
         * `Assertion failed`、插件 `apply()` 里抛的未捕获异常。**这些消息是
         * V8/Node 直接写到进程 stdout/stderr 的**，从来不经过 `LogStore` ——
         * 也就是说，原来那份现场里 App 侧的 `--- last 200 log lines ---`
         * 完全是 mdns / 固定端口守护之类的无关日志，**引擎说过的话一个字都没有**。
         *
         * 而引擎的输出其实一直躺在 [logLines] 这个 400 行环形缓冲里
         * （stdout 读取线程每读一行就 `addLast`，读线程的 `finally` 在引擎死亡
         * 时正好执行到这里）。**取一行快照的成本可以忽略，换来的却是唯一根因。**
         *
         * 快照必须在这里取（而不是事后）：下一次 `start()` 一进来就会
         * `logLines.clear()`（见 [start] 开头），过期即失效。
         */
        val engineTail = synchronized(logLines) { logLines.toList() }
        if (code == 134) {
            /*
             * 单独提一句 SIGABRT 的含义。
             *
             * ⚠️ 只在这个码上打，不要泛化：`Log.w` 里写"可能 OOM"却对
             *   所有退出码都说，就成了噪音。134 的语义是确定的（abort），
             *   这么写不算猜。
             */
            Log.w(
                TAG,
                "引擎退出码 134 = SIGABRT（V8 堆耗尽 / 断言失败 / 未捕获异常的典型信号），" +
                    "引擎最后输出 ${engineTail.size} 行已并入崩溃现场",
            )
        }
        CrashRecorder.recordEngineExit(
            code,
            expected = false,
            detail = detail,
            engineTail = engineTail,
        )
        EventLog.record("engine.exit", mapOf("code" to (code?.toString() ?: "?"), "wasReady" to "true"))
        _state.value = State.Dead(detail)
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * 用配对码把本 App 的 ADB 密钥授权给设备（无线调试配对，Android 11+）。
     *
     * ⚠️ **阻塞调用**（发现端口 + SPAKE2 握手 + adb 启动，可能十几秒），
     * 必须在后台线程调用。调用方见 [AdbPairingNotifier]（走 `goAsync()`）。
     *
     * @param code 用户从系统「使用配对码配对设备」界面抄来的 6 位码。
     * @param port 配对端口；`<= 0` 时由 [WirelessDebugging.pair] 自己重新发现。
     */
    fun pairWirelessDebugging(code: String, port: Int): WirelessDebugging.Result {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        if (!adbHome.isDirectory) {
            return WirelessDebugging.Result(
                false,
                "内置 adb 工具链未就绪（$ADB_WIRELESS_DIR 不存在），无线调试不可用。",
            )
        }
        val result = WirelessDebugging.pair(
            context = context,
            nodeBin = bootstrap.nodeBinary,
            runner = runner,
            adbHome = adbHome,
            dshHome = dshHome,
            code = code,
            // ⚠️ 把通知带下来的端口**真的传下去**。它一直到这一步都没被用过：
            //    方法签名收下了 port，却没往下传，`WirelessDebugging.pair()` 于是
            //    自己重新发现一次 —— 而用户此刻多半已经从「使用配对码配对设备」
            //    界面切到了通知栏，那个端口早没了，重新发现必然落空。
            //    症状就是「通知栏明明显示了端口，配对却报没找到端口」。
            knownPort = port,
        )
        // 配对成功后立刻发现连接端口并落盘，这样用户不必再等常驻监听
        // 撞上「无线调试已开启」那一刻。
        //
        // ⚠️ 用**带重试的** `ensureTcpPort` 而不是单次 `discoverConnectPort`。
        //   配对过程中无线调试会重启 adbd，端口要过一会儿才广播出来 —— 单次发现
        //   常常落空，而原实现在落空时**静默跳过整个补切**（连一句日志都没有），
        //   于是「配对成功但特权身份还是 app」既没修好、也查不出原因。
        //   现在失败会明确记日志，并且和启动自愈共用同一套重试预算。
        if (result.ok) {
            // runner 在 `--pair` 里已经自己切过一次，但**那一次用的是配对前的
            // 旧端口**：无线调试在配对过程中会重启 adbd，端口随之改变，于是
            // `adb -s 127.0.0.1:<旧端口> tcpip 5555` 必然失败。
            // 这里拿着**重新发现的端口**再补切一次，把那条缝补上。
            // 已经切成 5555 时 `switchToTcp` 是幂等的（先探 legacy，通就返回 true），
            // 所以重复调用没有代价。
            val switched = WirelessDebugging.ensureTcpPort(
                context = context,
                nodeBin = bootstrap.nodeBinary,
                runner = runner,
                adbHome = adbHome,
                dshHome = dshHome,
            )
            if (switched.ok) {
                Log.i(TAG, "配对成功且已切到固定端口: ${switched.message}")
                // ⚠️ 补切成功就要**改掉** runner 那句「还没切到固定端口」的结论。
                //   runner 报「没切成」时用的是旧端口（配对过程中 adbd 重启、端口变了），
                //   这里用新端口补切成功，那句话此刻已经不成立。不改的话通知栏会告诉
                //   用户「请去设置页点『切到固定端口』重试」—— 而实际上什么都不用做，
                //   用户白跑一趟还会以为修复没生效。
                return result.copy(message = "配对成功，并已切到固定端口 5555 —— 现在关掉无线调试、关掉 Wi-Fi 都不影响特权通道。")
            }
            Log.w(TAG, "配对成功但补切固定端口失败: ${switched.message}")
        }
        return result
    }

    /**
     * 把 adbd 切到固定的 TCP 端口（`adb tcpip 5555`）。
     *
     * 切完之后特权通道**不再依赖无线调试开关** —— 实测关掉无线调试、关掉 Wi-Fi
     * 都不影响（走 127.0.0.1 回环）。详见 [WirelessDebugging.switchToTcp]。
     *
     * ⚠️ **阻塞调用**（最坏约 30 秒：发现无线调试端口 + 切换 + 轮询确认真的拿到
     * uid 2000），必须在后台线程调用。稳态下（5555 已在听）只有几百毫秒。
     */
    fun switchToTcpMode(): WirelessDebugging.Result {
        val runner = File(context.filesDir, PRIVILEGED_RUNNER_FILE)
        val adbHome = File(context.filesDir, ADB_WIRELESS_DIR)
        if (!adbHome.isDirectory) {
            return WirelessDebugging.Result(
                false,
                "内置 adb 工具链未就绪（$ADB_WIRELESS_DIR 不存在），无线调试不可用。",
            )
        }
        return WirelessDebugging.switchToTcp(
            nodeBin = bootstrap.nodeBinary,
            runner = runner,
            adbHome = adbHome,
            dshHome = dshHome,
        )
    }

    fun recentLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    companion object {
        private const val TAG = "DSH"
        // 注意：不能用 .tar.gz 作后缀 —— AGP 会把它当作 gzip 资源自动解压并改名为 .tar，
        // 导致运行时按原名找不到。用中性后缀避免 AGP 的格式推断。
        private const val ASSET_NAME = "engine.bin"
        private const val READY_TIMEOUT_MS = 120_000L
        private const val MAX_LOG_LINES = 400

        /**
         * 堆水位监控的检查间隔。
         *
         * 取 3 分钟：泄漏速率实测约 130 MB/小时 ≈ 2.2 MB/分钟，3 分钟只涨
         * 约 6.5 MB —— 相对水位余量（见 [heapRestartRatio]）完全可以忽略，
         * 而检查一次只是读一个 `/proc/<pid>/status` 里的一行，代价可忽略。
         * 不要调得更勤：那对判据没有帮助，只是白唤醒。
         */
        private const val HEAP_WATCH_INTERVAL_MS = 3 * 60_000L

        /**
         * 触发主动重启的水位（占堆上限的比例）。
         *
         * 为什么是 0.85：
         * · 从判定到重启完成，引擎还在按 2.2 MB/分钟继续涨。`requestRestart`
         *   要走「停 → 起 → 等 ready」的完整状态机，真机上实测 ready 要
         *   10–29 秒，也就是约 0.5–1 MB，相对余量（15% of 3072 ≈ 460 MB）绰绰有余；
         * · 不能取 0.95 以上：V8 在逼近上限时 GC 会退化成近乎停顿（真机日志里
         *   `Mark-Compact 1903.46 / 0.00 ms`，单次 GC 近 2 秒），界面先卡死
         *   再重启，体验反而更差；
         * · 不要取 0.7 以下：那会让 3 GB 的机器每隔几小时就重启一次，太激进。
         */
        private fun heapRestartRatio(): Double = 0.85

        /**
         * 引擎「起来了」的唯一判据：它打印了带 token 的回环 URL。
         *
         * 真机原文：`dsh web: http://127.0.0.1:3080/?token=xxxxxxxx`。
         *
         * ⚠️ **`start()` 与 [probeBoot] 共用这一条**（原来 `start()` 里就地写了一份）。
         *   两处各写一份的后果是「试启动说成功、真启动说失败」这类无法解释的差异 ——
         *   而验证类工具一旦不可信，整个降级阶梯的判断都跟着不可信。
         */
        private val URL_PATTERN =
            Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+)""")

        /**
         * 一次试启动（[probeBoot]）的上限。
         *
         * 真机实测失败路径 17~22 秒就返回了，所以 60 秒足够。
         * ⚠️ **刻意比 [READY_TIMEOUT_MS]（120 秒）短**：二分法要跑很多轮，
         *   每轮都按 120 秒算会让总时长失控（上限是 3 分钟，见 §4.3）。
         */
        private const val PROBE_TIMEOUT_MS = 60_000L

        /**
         * L1 自动隔离的**总时长上限**（方案 §4.3）。
         *
         * 3 分钟的依据：单次失败实测 17~22 秒，基线 1 次 + 二分 ⌈log2(40)⌉ = 6 轮
         * + 收尾 1 次 ≈ 8 次 × 20 秒 ≈ 2.7 分钟。取 3 分钟刚好够走完，
         * 又不会让用户对着"正在启动…"等太久。
         *
         * ⚠️ 超预算必须**优雅放弃**并保留已确认的结论，绝不能无限试下去 ——
         *   那会把「App 打不开」变成「App 一直显示正在启动」，比原来更糟。
         */
        private const val ISOLATION_BUDGET_MS = 3 * 60 * 1000L

        /**
         * 启动前体检（[preflight]）的超时。
         *
         * 真机实测正常态耗时 0.7~0.8 秒；给到 30 秒是为了容忍慢卡（体检只是
         * 保险，超时即跳过、不影响启动，所以宁可给宽一点）。
         */
        private const val PREFLIGHT_TIMEOUT_MS = 30_000L

        /**
         * 自动进安全模式所需的**连续真实启动失败**次数（方案 §4.5 的 P6）。
         *
         * 取 3 的依据：1~2 次失败可能是偶发（系统内存紧张导致引擎被 OOM、
         * 端口被上一次残留进程占着），那时推开用户的插件配置是**过度反应** ——
         * 用户会莫名其妙丢掉所有插件。而 3 次连续失败（每次都是完整的启动流程、
         * 都走到「引擎没打印 URL」）已经排除了偶发。
         *
         * ⚠️ 这个数**只是三个条件之一**，见 [shouldAutoEnterSafeMode]：
         *   单独满足它不会触发，还必须 `tier == RESCUE`（= L1/L2 都试过）。
         */
        private const val AUTO_SAFE_MODE_MIN_FAILURES = 3

        /**
         * `requiredStartupEntryIds` 的副本 —— 与 `dsh-app-boot/lib/index.js` 里的
         * 那个 Set **同源**，少一个引擎就起不来（`auditStartupEntries` 会抛
         * `StartupError` 并以 exit 1 结束）。
         *
         * ⚠️ 这几个 id 不是「重要插件」的意思，而是「缺了整个树就废了」：
         * 真机实测删掉 `dsh-web-app` 的 `cordis.patch.yml` 后，`webserver` /
         * `modules` / `connection` 三个条目全部消失，引擎 boot 时挂死（45 秒超时）。
         * 引擎升级若增删了这个列表，本副本要跟着改 —— 漏改只会让体检**少报**，
         * 不会误伤（体检判据是「缺了才隔离」）。
         */
        private val REQUIRED_ENTRY_IDS = listOf("agent-loop", "webserver", "modules", "connection")

        /**
         * 从引擎报错行里抠出被指认的文件绝对路径。
         *
         * 覆盖三种官方措辞（真机原文）：
         * - `failed to parse overlay <path>:`
         * - `failed to read overlay <path>:`
         * - `failed to read profile manifest <path>:`
         *
         * 路径一定在 `/` 开头（本 App 的 `$DSH_HOME` 与 `engine/` 都是绝对路径），
         * 所以用 `(/\S+)` 而不是更宽松的匹配 —— 报错行后面还跟着 YAML 片段。
         */
        private val BLAMED_PATH =
            Regex("""failed to (?:parse|read) (?:overlay|profile manifest) (/\S+?):""")

        /**
         * 引擎 Web GUI 的默认监听端口。
         *
         * 出处：`@deepseek-ai/dsh-web-app/cordis.patch.yml` 的
         * `port: !!js ctx.webStartup.port ?? 3080` —— App 不传 `--port` 时落到的就是它。
         *
         * 被占用时改传 `--port 0` 让内核挑一个空闲端口，见 [isLoopbackPortBusy]。
         */
        private const val DEFAULT_WEB_PORT = 3080

        /** WebView 兼容补丁：资产名与注入到 index.html 里的标签。 */
        private const val POLYFILL_FILE = "webview-polyfill.js"
        private const val POLYFILL_TAG = """<script src="./$POLYFILL_FILE"></script>"""

        /**
         * 引擎侧 HTTP header 净化垫片（见 [ensureFetchGuard]）。
         *
         * 解到 `filesDir` 根下（与 `dsh-android-shell.mjs` 同一层），通过
         * `NODE_OPTIONS=--import <绝对路径>` 预加载进**引擎进程**。
         *
         * ⚠️ 必须用**绝对路径**：`--import` 的裸标识符会走 Node 的模块解析，
         * 而引擎进程的 cwd 是 `engine/`，相对路径会解析到那里去。
         */
        private const val FETCH_GUARD_ASSET = "dsh-fetch-guard.mjs"
        private const val FETCH_GUARD_FILE = "dsh-fetch-guard.mjs"

        /**
         * 按旧内核降级编译的前端 bundle（见 [ensureWebViewCompat]）。
         *
         * 由 `tools/build-webview-compat.ps1` 生成，含 dist 与**各插件的 client bundle**
         * 两部分的降级产物（老版本只含 dist，实测漏了 71 个插件里 68 个的语法缺口）。
         * **换了 engine.bin 必须重跑脚本**。
         */
        private const val WEBVIEW_COMPAT_ASSET = "webview-compat.zip"

        /** 降级资产在 dist 里留下的戳，以及 zip 内对应的条目名。 */
        private const val WEBVIEW_COMPAT_STAMP = ".webview-compat-stamp"
        private const val WEBVIEW_COMPAT_STAMP_ENTRY = ".compat-stamp"

        /**
         * 降级 zip 的条目树前缀 —— 决定每个条目解到哪棵树上（见 [ensureWebViewCompat]）。
         *
         * ⚠️ 这三个字符串必须与 `tools/build-webview-compat.ps1` 里写死的一致：
         *    对不上时条目会被当「前缀无法识别」丢掉，症状是降级**静默不生效**。
         */
        private const val COMPAT_PREFIX_DIST = "dist"
        private const val COMPAT_PREFIX_ENGINE = "engine"
        private const val COMPAT_PREFIX_MOBILEUI = "mobileui"

        /** 自编译的 android-arm64 原生插件（官方无此变体）。 */
        private const val ADDON_ASSET = "node-addon-android-arm64.zip"
        private const val ADDON_PACKAGE = "node-addon-require-builtin-android-arm64"

        /** system 插件（会话写入锁 flock）：同样是自编译的 android-arm64 变体。 */
        private const val SYSTEM_ADDON_ASSET = "node-addon-system-android-arm64.zip"
        private const val SYSTEM_ADDON_PACKAGE = "@deepseek-ai/node-addon-system-android-arm64"

        /**
         * flock.js 里平台白名单的原样文本，以及放行 android 之后的文本。
         * 两者都必须与官方发布的 flock.js 逐字一致；引擎升级后若此处不再匹配，
         * [patchFlockPlatform] 会跳过并在日志里提示，不会让启动失败。
         */
        private const val FLOCK_PLATFORM_GUARD =
            "if (platform !== 'linux' && platform !== 'darwin') {"
        private const val FLOCK_ANDROID_ANCHOR =
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
        private const val FAIL_LOUD_EXIT_GUARD = "proc.exit(1);"
        private const val FAIL_LOUD_ANDROID_ANCHOR =
            "/* DSH-ANDROID-KEEP-ALIVE: 插件崩溃不再杀死整个引擎 */"

        /** 幂等标记：出现在替换**之后**的文本里，官方原文中不会有。 */
        private const val FAIL_LOUD_ANDROID_MARK = "DSH-ANDROID-KEEP-ALIVE"

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
        private const val FAIL_LOUD_RELEASE_GUARD =
            "await app.current?.fiber.dispose();"
        private const val FAIL_LOUD_RELEASE_ANCHOR =
            "/* DSH-ANDROID-KEEP-ALIVE-RELEASE: 插件崩溃不再 dispose 整个应用 */"

        /** 幂等标记：第 2 步的替换结果。 */
        private const val FAIL_LOUD_RELEASE_MARK = "DSH-ANDROID-KEEP-ALIVE-RELEASE"

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
        private const val TERMINAL_SHELL_GUARD =
            "const DEFAULT_BASH_SHELL = \"/bin/bash\";"
        private const val TERMINAL_SHELL_ANCHOR =
            "const DEFAULT_BASH_SHELL = process.env.PREFIX ? process.env.PREFIX + \"/bin/bash\" : \"/bin/sh\";"

        /**
         * `dsh-attachment-local/lib/index.js` 里 `syncDirectory()` 的原样文本，
         * 以及容忍祖先目录打不开之后的文本。见 [patchAttachmentDurability]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
        private const val ATTACH_SYNC_GUARD =
            "\tconst handle = await open(path, constants.O_RDONLY);"
        private const val ATTACH_SYNC_ANCHOR =
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
        private const val ATTACH_FSYNC_GUARD =
            "\ttry {\n" +
                "\t\tawait handle.sync();\n" +
                "\t} finally {\n" +
                "\t\tawait handle.close();\n" +
                "\t}"
        private const val ATTACH_FSYNC_ANCHOR =
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
        private const val IMG_TYPE_GUARD =
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
        private const val IMG_TYPE_ANCHOR =
            "if (typeof value !== \"string\") return false;\n" +
                "/* DSH Android: 放宽到任何 image/ 前缀。原版只认 4 个精确值，\n" +
                "   而 image/jpg、image/heic、image/avif、image/bmp 以及剪贴板粘贴\n" +
                "   的空 type 都会被误拒，用户看到的是「仅支持 PNG、JPG、WebP、GIF」。\n" +
                "   真正的解码能力由下游 sharp 判断，那里报错更准确。 */\n" +
                "return value.startsWith(\"image/\");"

        /**
         * 凭据文件名（`dsh-credentials-local` 的 `CREDENTIALS_FILENAME`，写死，
         * 因为它是引擎的公开约定而不是可配置项）。见 [ensureCredentialFileMode]。
         */
        private const val CREDENTIALS_FILE = ".credentials.yaml"

        /**
         * 凭据文件唯一被接受的权限位：`0600`（仅属主可读写）。
         *
         * 见 [ensureCredentialFileMode]。写入值写死成 `0600` 而不是「把过宽的位
         * 减掉」：引擎自己写这个文件时用的就是 `writeFileAtomic(..., { mode: 0o600 })`，
         * `0600` 才是它认得的规范形状。
         */
        private val OWNER_ONLY_PERMISSIONS: Set<PosixFilePermission> =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /**
         * 引擎 `assertOwnerOnly()` 的判据位：组 + 其他的全部 9 位，即 `0o077`。
         *
         * `dsh-credentials-local/lib/index.js` 里是
         * `const GROUP_OTHER_BITS = 63;` + `if ((mode & GROUP_OTHER_BITS) === 0) return;`。
         * **只拒绝这 9 位非零**，不关心属主执行位（所以 `0700` 是它接受的文件）。
         * 我们照抄这个判据，免得去改一个引擎本来就接受的文件。
         */
        private val GROUP_OTHER_BITS: Set<PosixFilePermission> =
            EnumSet.of(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            )

        /**
         * `dsh-llm-deepseek/lib/index.js` 里 `assistant()` 映射的**首尾两行原文**，
         * 以及把 `tool_use` 块收敛到末尾之后的文本。见 [patchLlmDeepseekToolOrder]。
         *
         * 分首尾两处替换（而不是整段替换）：中间是一个完整的 `switch`，整段照抄
         * 容易在引擎升级时因为无关的空白/注释变化而失配，只锚定首尾两行更稳。
         *
         * ⚠️ **重排方向是「置尾」而不是「置首」**：assistant 的 content 里可能有
         * 带签名的 `thinking` 块，而 Messages API 要求 thinking 排在 assistant
         * content 的**最前**。把 `tool_use` 提到最前会让 thinking 落到它后面，
         * 可能触发签名校验失败。收敛到末尾得到的 `[thinking, text, tool_use…]`
         * 才是规范形状。
         *
         * ⚠️ 缩进是**制表符**（首行 1 个、尾行 3/2/1/0 个），与官方发布件逐字一致；
         * 引擎升级后若不再匹配，补丁会跳过并在日志里提示，不会让启动失败。
         *
         * ⚠️ **0.2.1-rc.2 起首行从 `map` 变成了 `flatMap`**（空 text 块返回 `[]`
         *   靠 flatMap 展开）。锚点只认新文本 —— 引擎已固定为 0.2.1，老文本不再需要，
         *   漏改的后果是补丁静默跳过、assistant 的 tool_use 顺序又变回交错，
         *   ⑨.5 那个「会话永久发不出去」的故障会复发。
         */
        private const val TOOL_ORDER_GUARD_HEAD =
            "\treturn message.content.flatMap((block, index) => {"
        private const val TOOL_ORDER_ANCHOR_HEAD =
            "\tconst mapped = message.content.flatMap((block, index) => {"

        private const val TOOL_ORDER_GUARD_TAIL =
            "\t\t\tdefault: return unsupported(`assistant content \${block.type}`);\n" +
                "\t\t}\n" +
                "\t});\n" +
                "}"
        private const val TOOL_ORDER_ANCHOR_TAIL =
            "\t\t\tdefault: return unsupported(`assistant content \${block.type}`);\n" +
                "\t\t}\n" +
                "\t});\n" +
                "\t/* Android: DeepSeek Messages 要求 assistant 的 tool_use 块连续，且紧后\n" +
                "\t   必须携带全部 tool_result。持久化历史里 assistant 的块是交错的\n" +
                "\t   （text 夹在多个 tool_use 之间），原样发出会被 API 以\n" +
                "\t   \"tool_use ids were found without tool_result blocks\" 拒绝，\n" +
                "\t   且该历史从此永久发不出去。这里把 tool_use 收敛到末尾，\n" +
                "\t   其余块保持原有相对顺序（thinking 必须留在最前）。\n" +
                "\t   只改本次请求体，不改持久化历史，replay 签名已在映射时贴好。 */\n" +
                "\tconst calls = mapped.filter((block) => block.type === \"tool_use\");\n" +
                "\tif (calls.length === 0) return mapped;\n" +
                "\treturn [...mapped.filter((block) => block.type !== \"tool_use\"), ...calls];\n" +
                "}"

        /**
         * `dsh-llm-pi-ai/lib/index.js` 里 tool-call 事件的**首尾两处原文**，
         * 以及给空 id 兜底之后的文本。见 [patchLlmPiAiToolCallId]。
         *
         * ⚠️ 两处都要改，缺一不可：
         * - `toolcall_delta` 显式造空串（`known?.id ?? ""`），它经 `partial.toolCallId`
         *   进入组装器；
         * - `toolcall_end` 直接造整个 block（`id: brandString(event.toolCall.id)`），
         *   经 `partial.block` 短路返回，**绕过组装器的 `??` 兜底**。
         *
         * 兜底值用 `call-<index>` 形状（与 `dsh-llm` 组装器原生的兜底命名一致），
         * 保证 provider 不给 id 时仍有稳定的、非空的调用标识。
         *
         * ⚠️ 缩进是**制表符**，与官方发布件逐字一致；引擎升级后若不再匹配，
         * 补丁会跳过并在日志里提示，不会让启动失败。
         */
        private const val PI_AI_TOOLID_GUARD_START =
            "\t\t\t\tid: brandString(known?.id ?? \"\"),\n"
        private const val PI_AI_TOOLID_ANCHOR_START =
            "\t\t\t\t/* Android: 第三方路由（实测 agnes/q）在流式响应里可能不给 tool_call id，\n" +
                "\t\t\t\t   原样透出会让 tool-call 块拿到空 id，落盘后该会话永久不可读。\n" +
                "\t\t\t\t   这里补一个稳定的非空兜底。 */\n" +
                "\t\t\t\tid: brandString(known?.id || `call-\${event.contentIndex}`),\n"

        private const val PI_AI_TOOLID_GUARD_END =
            "\t\t\t\t\tid: brandString(event.toolCall.id),\n"
        private const val PI_AI_TOOLID_ANCHOR_END =
            "\t\t\t\t\t/* Android: 同 toolcall_delta —— provider 不给 id 时兜底，\n" +
                "\t\t\t\t\t   否则这个 block 会短路返回、绕过组装器的 ?? 兜底。 */\n" +
                "\t\t\t\t\tid: brandString(event.toolCall.id || `call-\${event.contentIndex}`),\n"

        /**
         * `dsh-llm/lib/index.js` 里 `assemble()` 组装 tool-call 块的那一行原文，
         * 以及空串也能兜住的文本。见 [patchLlmToolCallIdFallback]。
         *
         * ⚠️ 原文用 `??`，**只兜 `null`/`undefined`**；而 pi-ai 的 delta 路径
         * 显式产生空串 `""`，`??` 放行 → 空 id 落盘 → 会话永久不可读。
         * 改成 `||` 让空串也走兜底。
         *
         * ⚠️ 缩进是**四个制表符**，与官方发布件逐字一致。
         */
        private const val LLM_TOOLID_GUARD =
            "\t\t\t\tid: partial.toolCallId ?? brandString(`call-\${index}`),\n"
        private const val LLM_TOOLID_ANCHOR =
            "\t\t\t\t/* Android: `??` 兜不住空串，而 pi-ai 的 delta 路径会产生 \"\"，\n" +
                "\t\t\t\t   空 id 落盘后整个会话都读不出来。改用 `||` 连空串一起兜。 */\n" +
                "\t\t\t\tid: partial.toolCallId || brandString(`call-\${index}`),\n"

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
        private const val PICKER_START_GUARD =
            "\t\tconst target = resolve(path ?? home);"
        private const val PICKER_START_ANCHOR =
            "\t\tconst target = resolve(path ?? (process.platform === 'android' ? '/sdcard' : home));"

        /** 移动端 UI 适配插件：包名、资产名，以及挂载它的 overlay 文件名。 */
        private const val MOBILE_UI_ASSET = "mobile-ui.zip"
        private const val MOBILE_UI_PACKAGE = "dsh-android-mobile"
        private const val MOBILE_UI_PATCH = "mobile-ui.patch.yml"

        /**
         * 插件解包目录，相对 `$DSH_HOME`。
         *
         * ⚠️ **刻意不放在 profile 的 `node_modules` 下** —— 那里归 pnpm 管，而本包
         * 不在 profile 的 `dependencies` 里（写了会去 registry 拉），于是**用户每装一次
         * 插件，pnpm 就把它挪进 `node_modules/.ignored/`**，`--patch` 里的包名随即解析
         * 失败 → 客户端半不编入 → 界面静默退回桌面布局。详见 ensureMobileUi()。
         */
        private const val MOBILE_UI_DIR = "mobile-ui"

        /** Agnes 多模态插件（dsh-agnes-multimodal）：资产名、包名、overlay 文件名。 */
        private const val AGNES_MM_ASSET = "agnes-multimodal.zip"
        private const val AGNES_MM_PACKAGE = "dsh-agnes-multimodal"
        private const val AGNES_MM_PATCH = "agnes-multimodal.patch.yml"

        /** 解包目录，相对 `$DSH_HOME`；与 [MOBILE_UI_DIR] 同理，刻意在 pnpm 管辖之外。 */
        private const val AGNES_MM_DIR = "agnes-multimodal"

        /** 插件内容版本戳：改了 `agnes-multimodal.zip` 里任何东西都必须改这里。 */
        private const val AGNES_MM_VERSION = "1.0.0-4"
        private const val AGNES_MM_STAMP = ".agnes_mm_version"

        /** 插件自己的账号池状态文件（`lib/index.js` 的 `readAccounts()` 读的就是它）。 */
        private const val AGNES_MM_STATE = "ag-multimodal.json"

        /**
         * 内置预设 dsh-preset-razor（「快准狠模式（通用）」）：资产名、包名、旧 overlay 文件名。
         *
         * ⚠️ 本包与其它插件不同：它**没有任何 JS 代码**，`cordis.patch.yml` 里就一行
         * `- insert:`，挂的是官方包 `@deepseek-ai/dsh-agent-preset`，携带的是一份
         * **预设定义**（工具组装 + 系统提示词）。
         *
         * 它和 dsh-core-memory 一样走 **profile bundle**（包放在 `$DSH_HOME/node_modules/`），
         * 而不是 `--patch` overlay —— 这样用户才能在「插件 → 已安装插件」里自己关掉。
         */
        private const val PRESET_RAZOR_ASSET = "preset-razor.zip"
        private const val PRESET_RAZOR_PACKAGE = "dsh-preset-razor"

        /**
         * 旧版本用它走主机 overlay；改造为 profile bundle 后只用于**清理残留**。
         * 老设备上这份文件已经存在，不删会被 `--patch` 再次挂上 ⇒ 同一个预设挂两遍，
         * registry 直接报 `Duplicate agent preset: razor`。
         */
        private const val PRESET_RAZOR_PATCH = "preset-razor.patch.yml"

        /** 改造前的解包目录，相对 `$DSH_HOME`；现在只用于清理残留。 */
        private const val PRESET_RAZOR_LEGACY_DIR = "preset-razor"

        /**
         * 「本 App 曾经把这个 bundle 登记进 profile」的一次性标记。
         *
         * ⚠️ 判据是「标记在不在」，不是「版本变没变」—— 它**不该**随版本 bump。
         * 一旦用户关掉这个预设，App 重启时绝不能把它加回去，否则就是「关不掉」。
         */
        private const val PRESET_RAZOR_MARKER = ".preset_razor_bundle_registered"

        /** 内容版本戳：改了 `preset-razor.zip` 里任何东西都必须改这里。 */
        private const val PRESET_RAZOR_VERSION = "1.0.3-1"
        private const val PRESET_RAZOR_STAMP = ".preset_razor_version"

        /**
         * 内置**中文 skill**（`dsh-mobile-theme` / `dsh-desktop-pet` 等）。
         *
         * 落在 `$DSH_HOME/skills/`（`USER_DSH_RANK=400` 的全局发现根），
         * 每个预设都会扫到，因此无需改任何 `cordis.patch.yml`。见 [ensurePresetSkills]。
         */
        private const val PRESET_SKILLS_ASSET = "preset-skills.zip"

        /** 内容版本戳：改了 `preset-skills.zip` 里任何东西都必须改这里。 */
        private const val PRESET_SKILLS_VERSION = "1.0.0-3"
        private const val PRESET_SKILLS_STAMP = ".preset_skills_version"

        /**
         * 本 App 管的 skill 子目录名 —— [ensurePresetSkills] 只删这几个再重解包。
         *
         * ⚠️ **必须与 zip 里的一级目录名逐字一致**，且**不得包含用户可能自己写的名字**：
         * `$DSH_HOME/skills/` 是用户自己的目录，删错就是删用户的东西。
         * 新增一份内置 skill 时，这里和 zip 内容要同时改。
         */
        private val PRESET_SKILL_NAMES = listOf("dsh-mobile-theme", "dsh-desktop-pet")

        /** 创造模式（`cordis` 预设）的官方文件路径，相对 `engineDir`。 */
        private const val PRESET_CORDIS_PRESET_PATH =
            "node_modules/@deepseek-ai/dsh-web-app/presets/cordis.patch.yml"

        /**
         * 官方 persona 原文（**锚点**，必须与引擎里的逐字一致才会被替换）。
         *
         * 来自 `dsh-web-app/presets/cordis.patch.yml`：
         *
         * ```yaml
         *           - id: persona
         *             name: '@deepseek-ai/dsh-persona'
         *             config:
         *               prefix: >-
         *                 You are a coding agent powered by the {{model}} model.
         * ```
         *
         * ⚠️ **0.2.1-rc.2 起官方删掉了 `suffix: Your working directory is {{cwd}}.`
         *   这一行**（工作目录改由 `time-context` / 工具描述承载），锚点必须跟着删，
         *   否则整个 GUARD 匹配不上 → 补丁静默跳过 → 「快准狠」提示词不再注入。
         *   实测就是这样：升级后日志出现「创造模式预设的 persona 结构与预期不符」。
         *
         * ⚠️ 但**光删锚点还不够**：`{{cwd}}` 这个模板变量本身在 0.2.1 里也**不再注册**
         *   （报错原文：`unknown prompt variable "{{cwd}}" in section
         *   "deployment:persona-suffix"; registered variables: provider, model`）。
         *   所以补丁正文里的 `suffix` 也不能再写它 —— 否则锚点匹配上了、补丁打上了，
         *   整个预设却一渲染就失败，表现为「快准狠模式一选中就处理失败」。
         *   工作目录由 `time-context` 承载，persona 这边不需要再声明。
         */
        private const val PRESET_CORDIS_PERSONA_GUARD = """          - id: persona
            name: '@deepseek-ai/dsh-persona'
            config:
              prefix: >-
                You are a coding agent powered by the {{model}} model."""

        /**
         * 打过补丁后的文本特征 —— 幂等判据。
         *
         * 用它而不是戳文件：`engine/` 目录会被 `ensureExtracted()` 在版本戳变化时
         * 整个删掉重解包，任何放在里面的戳都会一起没；而"补丁后文本在不在"
         * 每次都能重新判定，重解包后自然会被重新打上。
         */
        private const val PRESET_CORDIS_PERSONA_ANCHOR_HEAD = "━━━ 铁律零：指哪打哪，不顺手加活"

        /**
         * 替换后的 persona —— 在官方原句之上并入「快准狠」铁律（用户要求：
         * 「整体不用怎么改，就是把它的提示词改一下，在原有的基础上加入我们快准狠模式的约束」）。
         *
         * 与 `preset-razor` 的 persona **同源**，差别只有两点、都是为了不破坏创造模式的定位：
         * 1. 首句保留官方的"coding agent"口径，再点明「用于定制 DSH 本身」；
         * 2. 铁律五补一句「创造模式特有的规矩」，并显式指向两份中文 skill ——
         *    这正是用户要的「提前在预设里写清楚，不用 AI 再摸一遍项目结构」。
         *
         * ⚠️ YAML 里用的是**块标量**（`|-`），所以每行必须保持缩进，且**不能出现
         *   未转义的 `:` 后跟空格**（那会被解析成映射）。正文里凡是要写冒号的地方，
         *    都用中文全角「：」。
         */
        private val PRESET_CORDIS_PERSONA_PATCH = buildString {
            append("          - id: persona\n")
            append("            name: '@deepseek-ai/dsh-persona'\n")
            append("            config:\n")
            append("              suffix: |-\n")
            append("                【重申铁律】无论思考（思维链、推理过程）还是回复，一律使用中文，禁止用英文思考。\n")
            append("              prefix: |-\n")
            append("                你是一个由 {{model}} 模型驱动的 DSH 定制 Agent，正在与用户配对，")
            append("通过对话为 DSH 本身扩展能力：编写插件、添加界面、组合工具与提示词、创建新的模式。\n")
            append("                你不假设技术栈、不假设目录结构、不假设构建命令——一切以仓库里的实际情况为准。\n")
            append("\n")
            append("                ━━━ 铁律零：指哪打哪，不顺手加活（最高准则，与其余各条冲突时以此为准）━━━\n")
            append("                任务边界＝用户字面说的范围。用户没要求的步骤，一律不做。典型越界行为：\n")
            append("                装完再试着打开看看、改完再顺手测下游、做完再自检一遍还有没有别的毛病、\n")
            append("                顺手重构一下、顺手优化一下、顺手补个注释/日志/抽象。\n")
            append("                「顺手多做一点」在本模式里是错误行为，不是贴心：它拖慢速度，用户要的时候会自己说。\n")
            append("                确实认为有必要扩展时，只能先问一句「要不要顺便 X」，用户点头才做；严禁先做后报。\n")
            append("                判断标准：这一步是用户要我做的事本身，还是这件事之后可能发生的事？后者不做。\n")
            append("\n")
            append("                ━━━ 铁律一：全程中文 ━━━\n")
            append("                你的思考过程（思维链、推理）和最终回复都必须用中文书写。不要先用英文推理再翻译，\n")
            append("                直接用中文思考。代码、命令、标识符、日志原文保持原样，不要翻译。\n")
            append("\n")
            append("                ━━━ 铁律二：开工先建清单，边做边更新 ━━━\n")
            append("                接到任务后第一件事是调用 todo_write 建立完整清单（拆成 3–8 个可验证步骤），\n")
            append("                之后每完成一步就立刻更新清单（整表重发），让用户随时看得到进展。\n")
            append("                - 同一时刻只允许一个 in_progress。\n")
            append("                - 完成一步立即标 completed，不要攒到最后一起标。\n")
            append("                - 新发现的必要步骤补进清单，跑偏的删掉。\n")
            append("                清单是给用户看的进度条，不是摆设——不许只建一次就再也不更新。\n")
            append("\n")
            append("                ━━━ 铁律三：进度汇报只说三句 ━━━\n")
            append("                每轮汇报压缩成三句：现在做什么 / 已完成什么 / 下一步。\n")
            append("                不复述代码，不贴大段日志，不做无关铺垫。\n")
            append("\n")
            append("                ━━━ 铁律四：快、准、好 ━━━\n")
            append("                - 快：先读代码再动手，但信息足够就立刻停止探索。不要为「更确定」反复读同一个\n")
            append("                  文件、反复跑同一条命令、反复量同一个事实。探到能动手的程度就动手。\n")
            append("                - 准：定位问题要落到具体文件与行，说清根因，不含糊地说「可能是某某」。\n")
            append("                - 好：改动最小化，只改必要部分，不顺手重构、不顺手加抽象、不顺手加依赖。\n")
            append("                - 同一个事实只量一遍；结论一旦成立立即进入下一步，不再重复验证。\n")
            append("                - 脚手架与临时文件用内联命令解决，不要写进仓库，用完即删。\n")
            append("                - 能数值断言就不靠截图；一句话能说清就不写十句。\n")
            append("                - 不要问自己能查到答案的问题（如「你项目用什么框架」——去读代码）。\n")
            append("\n")
            append("                ━━━ 铁律五：先探明本项目的规矩，再动手 ━━━\n")
            append("                每个项目的构建、测试、验收方式都不同，不要套用上一个项目的习惯。\n")
            append("                动手前用最少的读取量摸清这三件事：\n")
            append("                1. 规矩在哪：根目录的 AGENTS.md / CLAUDE.md / README / CONTRIBUTING，\n")
            append("                   以及 Makefile、justfile、package.json scripts、Taskfile、构建脚本。\n")
            append("                   有就照它写的来；没有就自己判断，但不要凭想象编一条命令。\n")
            append("                2. 怎么改：本项目的既有写法（命名、错误处理、分层、测试组织），照抄现成的模式，\n")
            append("                   不要引入本项目没有的技术或依赖。\n")
            append("                3. 怎么验：本项目自己的编译 / 测试 / lint 命令就是唯一验收信号。\n")
            append("                验证只用来确认「我这一步做成了没有」，到此为止，不向外延伸：\n")
            append("                装完了就验到装成功，不去试能不能打开、打开有没有异常；\n")
            append("                改完了就验到改动生效，不去验下游功能还正不正常。\n")
            append("                ⚠️ 构建成功 ≠ 改动生效；测试通过 ≠ 测试真跑了。\n")
            append("                   要用项目自己的方式确认：产物里真的有你的改动、测试确实执行了（看用例数与跳过数）。\n")
            append("                   不要把「退出码为 0」当成「验证过了」。\n")
            append("                   但也不要把验证扩成加活：用户没要求的检查不做（见铁律零）。\n")
            append("\n")
            append("                ━━━ 铁律五之补充：创造模式特有的规矩 ━━━\n")
            append("                你已经装好了两份**中文 skill**，专门解决「每次都要重新摸一遍 DSH 结构」这个低效问题。\n")
            append("                动手前先按需加载，不要凭印象猜（更不要重新去读引擎源码）：\n")
            append("                - `dsh-mobile-theme` —— 做移动端界面主题 / 换肤 / 改配色 / 深色适配时加载。\n")
            append("                  内含 DSH 前端的取色机制、完整的 --dsw-* token 清单、主题插件的最小结构、\n")
            append("                  以及打包并挂到手机 App 上的完整步骤。\n")
            append("                - `dsh-desktop-pet` —— 做悬浮球外观 / 桌面宠物 / 精灵图动画时加载。\n")
            append("                  内含精灵图的精确切片规则（很多人栽在这里）、bubble 工具的全部字段与上限、\n")
            append("                  运行状态到动作的映射，以及最常见的四种错。\n")
            append("                加载方式：调用 `skill` 工具，传 skill 名。它们与其它 skill 一起出现在 skill 目录里。\n")
            append("                用户的需求若落在上述范围，先加载对应 skill 再动手；不在范围内就跳过。\n")
            append("\n")
            append("                ━━━ 铁律六：把需要人判断的节点交棒 ━━━\n")
            append("                - 你能自动验证的（编译、测试、静态检查、数值断言），自己跑完，不要甩给用户。\n")
            append("                  但「能验证」不等于「该验证」：只验证用户明确要求的那件事的结果，\n")
            append("                  用户没要求的检查一律不做（见铁律零）。做完就报，不要自己加验一遍。\n")
            append("                - 到了只有人能判断的节点（视觉外观、交互手感、业务是否合理），把状态准备好，\n")
            append("                  用编号写清「做哪几步 → 应看到什么」，然后停下来等用户确认。\n")
            append("                - 不要用截图 / 录屏循环反复自测来替代人的判断，那只会拖慢节奏。\n")
            append("                - 用户说「没问题了 / 可以了 / 通过」= 验收通过 → 立即收尾，不要再补测一轮，\n")
            append("                  不要追问「要不要再测测别的」。只有用户明确报「不行」时才继续排障。\n")
            append("\n")
            append("                ━━━ 铁律七：诚实收尾 ━━━\n")
            append("                - 没做的验证不要声称做过；跑失败就说失败，把原始报错贴出来。\n")
            append("                - 阻塞就说清卡在哪、需要用户做什么决定，不要装作已完成。\n")
            append("                - 收尾给出三件事：做了什么（文件 + 行）、结果如何、还剩什么没做。\n")
            append("                  验证过就写清怎么验的；没验就不要写、也不要补一句「我没验 X」——用户没要求就不验，\n")
            append("                  不需要交代，用户要知道会自己问。")
        }.trimEnd()

        /** 跨会话持久记忆插件（dsh-core-memory）：资产名、包名、旧 overlay 文件名。 */
        private const val CORE_MEMORY_ASSET = "core-memory.zip"
        private const val CORE_MEMORY_PACKAGE = "dsh-core-memory"

        /**
         * 旧版本用它走主机 overlay；改造为 profile bundle 后只用于**清理残留**。
         * 老设备上这份文件已经存在，不删会被 `--patch` 再次挂上 ⇒ 同一个包挂两遍。
         */
        private const val CORE_MEMORY_PATCH = "core-memory.patch.yml"

        /**
         * 「本 App 曾经把这个 bundle 登记进 profile」的一次性标记。
         *
         * ⚠️ 它存在的唯一理由是**别把用户关掉的插件又打开**：`dsh.profile.bundles`
         * 是用户与 App 共写的，若每次启动都「不在就补」，用户在插件页关掉、重启 App
         * 就又回来了 —— 那等于关不掉。有了这个标记，App 只在**从没见过它**时补一次。
         *
         * 不随版本变化：它不是内容版本戳，删掉会导致 App 重新「补一次」。
         */
        private const val CORE_MEMORY_MARKER = ".core_memory_bundle_registered"

        /**
         * 改造前的解包目录（相对 `$DSH_HOME`），只用于清理残留。
         * ⚠️ 别把它和 `$DSH_HOME/memory/` 混淆：那个是用户的记忆数据，永远不删。
         */
        private const val CORE_MEMORY_LEGACY_DIR = "core-memory"

        /** 内容版本戳：改了 `core-memory.zip` 里任何东西都必须改这里。 */
        private const val CORE_MEMORY_VERSION = "1.0.0-2"
        private const val CORE_MEMORY_STAMP = ".core_memory_version"

        /** 插件市场（dsh-android-market）：资产名、包名、overlay 文件名。 */
        private const val MARKET_ASSET = "market-plugin.zip"
        private const val MARKET_PACKAGE = "dsh-android-market"
        private const val MARKET_PATCH = "market.patch.yml"

        /** 解包目录，相对 `$DSH_HOME`；与 [MOBILE_UI_DIR] 同理，刻意在 pnpm 管辖之外。 */
        private const val MARKET_DIR = "market"

        /**
         * 内容版本戳：改了 `market-plugin.zip` 里任何东西都必须改这里。
         *
         * ⚠️ 尤其**重跑 `tools/market-collect.mjs` 更新了 `lib/seed.json` 之后**
         * 也要改 —— 否则老设备会继续用包里那份旧种子。
         */
        private const val MARKET_VERSION = "1.0.0-6"
        private const val MARKET_STAMP = ".market_version"

        /**
         * link()/linkat() 语义转译层（LD_PRELOAD）。
         * 资产名用中性后缀，避免 AGP 按扩展名做格式推断（同 engine.bin 的坑）。
         */
        private const val LINKCOMPAT_ASSET = "linkcompat.bin"
        private const val LINKCOMPAT_FILE = "liblinkcompat.so"

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
        private const val KEYINJECT_FILE = "libkeyinject.so"

        /**
         * 旧版内置号池 overlay 的文件名。**只用于删除**：那个写法（`- id: llm-pi-ai`
         * 的 `--patch`）会把用户新建的 provider 一起盖掉，导致 GUI 一加第三方 API
         * 就报 `Configuration for "llm-pi-ai" is overridden by a home patch or
         * command-line overlay`。老安装里残留的这份必须删掉，否则它仍会生效。
         */
        private const val KEYINJECT_PATCH = "keyinject.patch.yml"

        /**
         * 内置号池落在哪一行：**必须复用引擎自己的 `llm-pi-ai` entry**，
         * 不能另开一行同名插件。
         *
         * ⚠️ 为什么不能另开一行（踩过）：`dsh-llm-pi-ai` 的 `apply()` 会**无条件**
         * 把 pi-ai 内置目录里的 41 个 provider 全部 `registerConfigurableProviders()`。
         * 挂第二个实例时，第二批声明撞上第一批已经占用的路由，
         * `commit()` 抛 `DUPLICATE_DIRECTORY`（`configurable provider "anthropic"
         * is already declared`）⇒ 整个目录注册失败，模型列表空掉。
         *
         * ⚠️ 也不能用 `--patch` 去改这一行：overlay 在最顶层，会把用户在 GUI 里
         * 新建的 provider 整块盖掉（就是本次要修的 bug）。
         *
         * 所以唯一正确的落点是**同一个 entry、profile 的用户配置层** —— 那正是
         * GUI 自己写入的那份文件，我们只在缺 `agnes:` 时补一笔，绝不覆盖用户内容。
         */
        private const val KEY_POOL_NS = "llm-pi-ai"

        /**
         * 可以安全摘掉的「行内空值」写法 —— 摘掉它们**不丢任何数据**。
         *
         * ⚠️ 用途是修 [`mergeKeyPoolProvider`] 里的重复键崩溃：引擎把 schema 默认值
         * 序列化进文件时，`providers: z.dict(profile).default({})` 会写成
         * `providers: {}`，而 `{}` 是流式空映射，在它下面缩进写 `agnes:` 是非法
         * YAML，必须先还原成裸 `providers:`。
         *
         * ⚠️ **只认这四种「空」**。非空的 `providers: {deepseek: {...}}` 绝不能摘 ——
         * 那是用户的 provider 配置，摘掉就是数据丢失，见 [openKeyBlock] 的注释。
         *
         * `null` / `~` 是 YAML 的显式空值，语义上等于「没有内容」，摘下同样安全。
         */
        private val EMPTY_INLINE_VALUES = setOf("{}", "[]", "null", "~")

        /**
         * 网关令牌（替代旧的 TLS 层注入占位符）。
         *
         * ## 为什么从「占位符 + LD_PRELOAD 注入」改成「网关令牌」
         *
         * 旧方案把 132 把真密钥打进 APK，靠 `libkeyinject.so` 在 `SSL_write` 里
         * 把占位符换成真钥匙。虽然做了混淆，但**密钥终究落在设备上** —— 一旦
         * 泄漏就是 132 把一起丢，且换钥匙必须重新发版。
         *
         * 现在改为服务端中转：App 只持有这一个网关令牌，请求发到
         * `agnes.jiulingssh.xyz`，由服务器按严格轮询挑选真密钥并注入。
         * 好处：
         *   - 真密钥永不落设备（`strings` / 环境变量 / 抓包都拿不到）
         *   - 增删密钥只需改服务器上的 `agnes-keys.properties`，**不用发版**
         *   - 429/5xx 由网关自动换号重试，客户端无感
         *
         * ⚠️ 这个令牌**不是秘密**（它随 APK 分发，粉丝能读到），所以网关侧必须
         * 自己做限流与额度控制。令牌泄漏的后果是「额度被白嫖」，而不是「密钥泄漏」。
         *
         * ⚠️ 与旧 `KEY_PLACEHOLDER` 的兼容：老安装的 `ag-multimodal.json` 里可能
         * 还留着占位符行，[seedAgnesAccounts] 认它并覆盖成新令牌。
         */
        private const val KEY_PLACEHOLDER = "sk-agnes-54a388522b26770c2b45aab2f5a22c0700b6be4c0f325a96"

        /**
         * **旧版**的 TLS 注入占位符，仅用于**识别并覆盖**老安装残留的配置行。
         *
         * 升级到网关方案前，`ag-multimodal.json` 与 provider 配置里写的是这个
         * `sk-ANON…` 串。现在它已经没有意义（没有注入库会去替换它），但它会
         * 留在用户设备上。`seedAgnesAccounts()` 认它并把整行替换成新令牌，
         * 避免出现「用户有一条永远 401 的旧内置号」。
         *
         * ⚠️ **不要删除这个常量**，也不要把它当成有效配置使用。
         */
        private const val LEGACY_KEY_PLACEHOLDER = "sk-ANON00000000000000000000000000000000000000000000"

        /**
         * **旧版**直连上游的 baseURL，仅用于**识别并迁移**老安装的 `agnes:` 配置块。
         *
         * 号池改为服务端网关后，`baseURL` 必须是 [KEY_BASE_URL]。但
         * [mergeKeyPoolProvider] 的语义是「已有 agnes: 就一个字节都不动」，
         * 老安装升级后仍指向这两个域名 ⇒ 带着网关令牌直连上游 ⇒ 上游 401 ⇒
         * 界面显示「API 密钥无效」。
         *
         * [mergeKeyPoolProvider] 命中这里列出的值时改写成网关地址；
         * **其它任何值都不碰**（用户自填的第三方地址绝不可覆盖）。
         *
         * ⚠️ 不要把它们当成有效配置使用。
         */
        private val LEGACY_KEY_BASE_URLS = setOf(
            "https://api.agnes-ai.cn/v1",
            "https://apihub.agnes-ai.cn/v1",
            "https://api.agnes-ai.cn",
            "https://apihub.agnes-ai.cn",
        )

        /**
         * 内置号池的 provider 路由名。
         *
         * **只有一个**：两个站点由 `keyinject.c` 在 TLS 层按钥匙所属站点改写
         * Host 头来区分（combo），所以引擎侧看到的就是一个普通供应商，用户
         * 在主界面选模型时也只看到一个「Agnes」。
         *
         * 依据是三个实测事实：两个域名**同一个 IP**、共用**同一张**
         * `*.agnes-ai.cn` 通配证书、服务器按 **Host 头**路由（不是 SNI）。
         */
        private const val KEY_PROVIDER = "agnes"

        /** 供应商显示名 —— 主界面模型列表里分组用的就是它。 */
        private const val KEY_PROVIDER_NAME = "Agnes"

        /**
         * 网关地址（替代旧的直连国际站域名）。
         *
         * 旧方案 baseURL 指向 `apihub.agnes-ai.cn`，靠 `keyinject.c` 等长改写
         * Host 头在两个站点间路由 —— 那条链路已随注入层一起下线。
         *
         * 现在指向自建号池网关：两站钥匙的挑选与 Host 路由全部由**服务器**完成，
         * 客户端只看到一普通 OpenAI 兼容端点，不需要知道底下有几个站点、几把钥匙。
         *
         * ⚠️ 保留 `/v1` 后缀：引擎的 `openai-completions` 会拼 `/chat/completions`，
         * 与 OpenAI 官方 SDK 的习惯一致。
         */
        private const val KEY_BASE_URL = "https://agnes.jiulingssh.xyz/v1"

        /**
         * `apiKeyEnv` 引用的环境变量名。
         *
         * 名字里**刻意不带 KEY/SECRET/TOKEN**：`dsh-subprocess` 的
         * `scrubbedParentEnv()` 会剥掉所有匹配 `SENSITIVE_ENV_PATTERN`
         * （`/KEY|PASSWORD|SECRET|TOKEN/i`）的变量，带这些字样的名字会让子进程
         * 拿不到它。虽然这里装的是占位符、泄露也无所谓，但保持它能被子进程读到
         * 更省事（引擎若在子进程里做模型发现，缺了它会报 `MISSING_CREDENTIAL`）。
         */
        private const val KEY_ENV = "AGNES_CREDENTIAL"

        /**
         * 号池重试策略里声明的**可重试错误码**。
         *
         * ⚠️ `retryableCodes` 是**整体替换**默认值，不是追加 —— 引擎侧
         * `resolveRetryPolicy()` 写的是 `config.retryableCodes ?? [...DEFAULT_RETRYABLE_CODES]`，
         * 一旦我们提供了这个数组，默认那五个就**全都不再生效**。所以必须把它们
         * **原样再列一遍**（值与 `dsh-llm/lib/types/retry-policy.js` 的
         * `DEFAULT_RETRYABLE_CODES` 逐字一致），漏掉哪个就等于把那类错误的重试关掉。
         *
         * 五个默认值之外**多了一个 `AUTH`**。
         *
         * ⚠️ 这条的**原始动机已随网关改造消失**，但**仍然保留**，理由如下：
         *
         * 旧方案里号池是「随机抽钥匙」，抽到死钥匙就回 401/403，所以要靠引擎
         * 重发请求来换一把。现在网关自己会换号重试（`AGNES_MAX_ATTEMPTS`），
         * 客户端正常情况下**收不到** 401 了。
         *
         * 但保留它仍有两个实际好处：
         * 1. 网关令牌本身被吊销/更换时，客户端回 401 —— 重试虽无用，但能让
         *    错误在界面上表现为「重试若干次后失败」，而不是瞬间中断；
         * 2. 网关若因全池冷却返回 429（`RATE_LIMIT` 已在表里）或临时 5xx，
         *    重试是有意义的。
         *
         * 换言之：`AUTH` 现在大概率是空转，但删掉它不会带来收益，留着无害。
         */
        private val KEY_RETRYABLE_CODES = listOf(
            "EMPTY_RESPONSE",
            "RATE_LIMIT",
            "SERVER",
            "TIMEOUT",
            "TRANSPORT",
            "AUTH",
        )

        /**
         * 内置号池对外提供的模型清单。
         *
         * ⚠️ 必须显式列出：pi-ai 对手工声明的路由要求 `models` 非空，否则
         * 整个 profile 会被拒绝（"A route pi-ai does not ship needs `api`,
         * `baseURL`, and a non-empty `models` list"）。
         *
         * 只留 `agnes-3.0-flash`：号池是给粉丝免费体验用的，列一长串
         * （图像、视频、各种 pro 变体）只会让模型选择器变乱，而实际额度也不支持
         * 那些模型。要放开时在这里加一行即可。
         */
        private val KEY_MODELS = listOf("agnes-3.0-flash")

        /**
         * 内置号池模型对外声明的思考强度档位（`reasoningEfforts` 的键）。
         *
         * ## 为什么必须声明
         *
         * 不声明时 `dsh-llm-pi-ai` 的 `resolveModelReasoning()` 走
         * `base?.reasoning ?? false`，而 `agnes` 是自定义路由、pi-ai 内置目录里
         * 没有它，于是 `reasoning` 落到 **false**；`openai-completions` 的两个
         * 分支都要求 `model.reasoning`，**请求里根本不会带 `reasoning_effort`**。
         * Agnes 不传该参数就不返回 `reasoning_content` —— 界面上「思考过程」永远
         * 是空的。声明之后引擎才会带上它，思考内容才会流回来。
         *
         * ## 合法值来自实测，不是抄文档
         *
         * 给 `reasoning_effort: 'off'` 时 Agnes 直接 400，报错原文把合法集合列全了：
         *
         * ```
         * Input should be 'none', 'minimal', 'low', 'medium', 'high', 'xhigh' or 'max'
         * ```
         *
         * ## ⚠️⚠️ 为什么这里**刻意不声明 `off` 档**
         *
         * 这是踩过的坑，改回去会让「思考混进正文」重现，务必读完再动。
         *
         * 发送侧是**硬编码**的（`dsh-llm-pi-ai` 的 `streamSimple`）：
         *
         * ```js
         * const reasoningEffort = clampedReasoning === "off" ? undefined : clampedReasoning;
         * ```
         *
         * 也就是说**只要档位是 `off`，引擎就不发 `reasoning_effort`** —— 无论
         * overlay 里把 `off` 映射成什么（`null` 也一样）都改变不了这一点。
         * 而 Agnes 收不到「已关闭」这个信号时，会**把思考直接写进 `content`**
         * （实测 7 次里中 1 次，且内容是第一人称英文推理，例如
         * "The existing file is just a minimal placeholder…"），
         * 于是界面上思考内容就出现在正文里，而不是 thinking 块里。
         *
         * 更要命的是 `off` **一旦写进 `reasoningEfforts` 反而会变成可选档**：
         *
         * | overlay 里 | `thinkingLevelMap['off']` | `getSupportedThinkingLevels()` |
         * |---|---|---|
         * | 不写 `off` 键 | `null`（`wire === void 0` 分支） | `mapped === null` ⇒ **不可选** ✅ |
         * | 写 `'off': null` | `undefined`（`wire !== null` 跳过赋值） | 不为 null ⇒ **可选** ❌ |
         *
         * 所以唯一可靠的修法是**整档不声明**：off 从选择器里消失，
         * `clampThinkingLevel()` 把默认档落到 `minimal`，而 `minimal` 实测
         * **7/7 都走 `reasoning_content`**，思考正确进 thinking 块。
         *
         * ⚠️ 也别想用 `none` 顶替 `off`：`none` 同样不产生思考，但它会被真的发出去，
         * 与「不发字段」不是同一条路径，且实测出现过指令复述混进 `content` 的情况。
         *
         * ## ⚠️ 实测：这 6 档没有强度梯度
         *
         * 同一道推理题反复测，思考长度在 minimal=479 / low=1593 / medium=589 /
         * high=877 / xhigh=923 / max=1225 之间**随机乱跳**，不随档位递增。
         * 也就是说 Agnes 目前实际只区分「开 / 关」，档位名只是被接受而已。
         * 保留全部 6 档是为了不挡用户，而不是因为选了真会想得更多。
         */
        private val KEY_EFFORT_LEVELS = listOf("minimal", "low", "medium", "high", "xhigh", "max")

        /**
         * 内置号池模型对外声明的**输入模态**（`input` 字段）。
         *
         * ## 为什么必须声明
         *
         * `dsh-llm-pi-ai` 的 `DEFAULT_INPUT = ["text"]`，且解析顺序是
         *
         * ```js
         * input: declaredInput(entry.input) ?? base?.input ?? [...request.defaultInput]
         * ```
         *
         * `agnes` 是自定义路由、pi-ai 内置目录里没有它 ⇒ `base` 不存在 ⇒
         * 不声明就落到 `["text"]`。后果是**图片在附加前就被拒**：
         * composer 里选好图、点发送，引擎报「该模型不接受图片输入」，
         * 而 Agnes 本身是能识图的 —— 属于「能力被配置挡住」。
         *
         * ## 依据来自实测，不是照抄文档
         *
         * 拿真实 PNG（64×64 红底黄圆）走 OpenAI 兼容的 `image_url` 数据 URI
         * 直连两家站点，**14 把密钥 14/14 都正确答出「红色、黄色」**，
         * 且 `usage.prompt_tokens_details.image_tokens = 64` —— 图片确实进了模型
         * 并按图计费，不是被忽略后靠文本猜的。
         *
         * ⚠️ 手搓的 base64 PNG 会被服务端判成
         * `Could not decode image: broken data stream`（HTTP 400），
         * 那是**图坏了**而不是不支持图片 —— 与 AGENTS.md ⑧ 记的 sharp 那条同一个坑。
         * 看到这个 400 别急着下「不支持」的结论，换一张真图再测。
         *
         * ## ⚠️ over-claim 的代价（所以这里只声明实测过的能力）
         *
         * pi-ai 的注释写得很明确：少声明只是「附加前被拒、并指名模型」，
         * 多声明则是「图进去了、供应商中途拒绝」，而那时消息**已经落盘**，
         * 会话会卡在一条永远发不出去的请求上反复重试。
         * 所以这个列表只放实测通过的模态，不要凭「多模态」这个词顺手加。
         */
        private val KEY_MODEL_INPUT = listOf("text", "image")

        /** ripgrep：官方 `@vscode/ripgrep` 没有 android 平台包，这里补一个。 */
        private const val RIPGREP_ASSET = "ripgrep-android-arm64.zip"
        private const val RIPGREP_PACKAGE = "@vscode/ripgrep-android-arm64"

        /** sharp 的 wasm32 变体（官方兜底，见 `ensureSharp()`）。 */
        private const val SHARP_ASSET = "sharp-wasm32.zip"
        private const val SHARP_WASM_PACKAGE = "@img/sharp-wasm32"
        private const val SHARP_VERSION = "0.35.5"

        /** node-pty：官方 prebuilds 没有 android，自编译一份（见 `ensureNodePty()`）。 */
        private const val NODE_PTY_ASSET = "node-pty-android-arm64.zip"

        /**
         * `dsh-subprocess-local` 里 `createProcessInspector()` 的平台判断原文，
         * 以及放行 android 之后的文本。见 [patchProcessInspector]。
         *
         * ⚠️ 缩进是**一个制表符**，与官方发布件逐字一致。
         * ⚠️ 文本里的 `${platform}` 是**引擎源码**里的模板插值，不是 Kotlin 的，
         * 所以 `$` 必须转义成 `\$`，否则 Kotlin 会当成自己的变量去解析。
         */
        private const val INSPECTOR_GUARD =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"
        private const val INSPECTOR_ANDROID_ANCHOR =
            "\tif (platform === \"win32\") return createWindowsProcessInspector();\n" +
                "\t/* Android 就是 Linux 内核，/proc 格式一致，且 SYSCALLS 表里本来就有 arm64。\n" +
                "\t   app 域下 /proc 目录可读，只是别的 uid 的条目会被 SELinux 挡掉 ——\n" +
                "\t   LinuxProcessInspector 对读不到的条目一律跳过，不抛错。\n" +
                "\t   代价是只能看见自己 uid 的进程，而终端 shell 及其子进程全是本 app 的 uid。 */\n" +
                "\tif (platform === \"android\") return new LinuxProcessInspector(arch, internals);\n" +
                "\tthrow new Error(`subprocess-local: terminal inspection is unsupported on platform \${platform}`);"

        /** 沙箱：runner（bwrap 方言翻译）+ LD_PRELOAD 写拦截器。 */
        private const val SANDBOX_RUNNER_ASSET = "boo-sandbox-run.bin"
        private const val SANDBOX_RUNNER_FILE = "boo-sandbox-run"
        private const val SANDBOX_LIB_ASSET = "boosandbox.bin"
        private const val SANDBOX_LIB_FILE = "libboosandbox.so"
        private const val SANDBOX_PATCH = "sandbox.patch.yml"

        /** 默认工作区 overlay：补上引擎缺的 android 平台分支。见 [writeWorkspacePatch]。 */
        private const val WORKSPACE_PATCH = "workspace.patch.yml"

        /** 插件安装源 overlay：让对话框同时列出镜像与官方源。见 [writePluginManagerPatch]。 */
        private const val PLUGIN_MANAGER_PATCH = "plugin-manager.patch.yml"

        /**
         * 插件安装源。URL 必须与官方常量逐字一致（含尾斜杠），否则去重键不同、
         * 会多出一项重复的源。出处：`@deepseek-ai/dsh-plugin-manager` 的
         * `NPMMIRROR_REGISTRY`，以及 npm 官方源。
         */
        private const val NPM_MIRROR_REGISTRY = "https://registry.npmmirror.com/"
        private const val NPM_OFFICIAL_REGISTRY = "https://registry.npmjs.org/"

        /** pnpm：Termux 的 $PREFIX/bin 里没有，插件管理器靠它装 bundle。 */
        private const val PNPM_ASSET = "pnpm-android-arm64.zip"
        private const val PNPM_VERSION = "11.28.3"
        private const val PNPM_STAMP = ".pnpm_version"

        /** pnpm 整包解到 `$PREFIX/lib/pnpm`，`bin/pnpm` 只是个 exec 它的脚本。 */
        private const val PNPM_DIR = "lib/pnpm"

        /**
         * 插件内容版本：改了 `mobile-ui.zip` 里的任何东西都必须改这里，
         * 否则设备上已解包的旧版不会被覆盖（同 TermuxBootstrap.NODE_VERSION 的道理）。
         *
         * −48：修复 68 真正生效。第一版把 `type=password` 改成 `text` 之后，
         *      又用 CSS `-webkit-text-security: disc` 补圆点 —— 而 blink 判定
         *      「密码字段」用的正是 `computedStyle.textSecurity != none`，
         *      等于把密码语义原样装了回去，输入法照样切安全键盘、照样屏蔽剪贴板。
         *      本版把这层 CSS 遮蔽彻底移除。
         * −49：修复 68 的**真正生效点**。去掉 CSS 之后键盘仍是安全键盘 —— 因为
         *      官方那两个凭据框带 `autoFocus: true`，元素插入 DOM 的同一帧就以
         *      `type=password` 聚焦，WebView 在那一刻就把输入连接的 inputType
         *      定成 `TYPE_TEXT_VARIATION_WEB_PASSWORD`(0xe1)，而**它不会为已建立
         *      的连接重算**（实测 blur+focus 也刷不动）。本版改为在
         *      `HTMLInputElement.prototype` 层拦截 `type=password`，让 DOM 里
         *      从头到尾没出现过 password。真机实测 inputType 0xe1 → 0x80a1。
         * −54：悬浮球转录改为**近流式**并把思考与正文分开。两处改动：
         *      ① `mpScheduleTranscript` 节流 1500ms → 250ms（1.5s 会把整段回复
         *         攒成两三坨跳出来，观感就是"没有流式、一次全出"）；
         *      ② `mpCollectTranscript` 按 `data-chat-group-part` 把 `reasoning`
         *         节点发成独立的 `reasoning` 角色（原来与 `response` 都发成
         *         `assistant`，面板上两条标签样式全一样 ⇒ 看着就是"糊在一起"）。
         *      ⚠️ 必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`，
         *      否则设备上那棵降级过的 mobile-ui 树不会被覆盖（见 ⑯）。
         * 1.0.0-57：`mpPageBackground()` 改为**读主题 token** `--dsw-alias-bg-base`
         *      （取不到才退回 `backgroundColor`）。这是"系统栏默认跟随主题色"的实现 ——
         *      `backgroundColor` 只是 token 的投影，纯色主题下两者相等，主题一改成
         *      背景图/渐变就分叉。同 v56 的道理，也必须 bump -CompatVersion。
         * 1.0.0-58：全面屏让位 —— 内容列 `.pI_x6G_centerCol` 补上
         *      `padding-top: var(--dsh-android-system-top)`。原来只有侧栏让位，
         *      内容列靠 WebView 自身避让系统栏活着；全面屏一开那层避让被撤掉
         *      （窗口 content 由 [0,108] 变 [0,0]），状态栏直接压住汉堡与聊天页
         *      header。同 v57 的道理，也必须 bump `-CompatVersion`。
         * 1.0.0-59：上面那条 CSS 规则**压不过修复 66 的 `!important`**，实测
         *      `getComputedStyle` 仍是 0px、规则完全不生效。改为让
         *      `mpSyncFloatNavGap()` 在算 `--mp-float-nav-h` 时把状态栏高度一并纳入
         *      （JS 的 `Math.max` 不受内核版本限制；CSS 的 `max()` 要 Chrome 79，
         *      而目标内核是 chrome74，老内核上整条声明会静默失效）。
         *      同 v57/v58 的道理，也必须 bump `-CompatVersion`（现 v15）。
         * 1.0.0-60：设置页新增「运行日志与诊断」分节（`mpLogsSection` +
         *      `mpRegisterLogsSection`），拉起原生 `LogViewerActivity`。
         *      这是**纯 JS 新增**，没有改 CSS，所以**不必** bump
         *      `tools/build-webview-compat.ps1` 的 `-CompatVersion` ——
         *      兼容包是"按 chrome74 降级编译前端 bundle"，插件 JS 走的是
         *      引擎自己的加载路径，不经过那个编译。
         *      ⚠️ 但仍然必须 bump 本常量：否则 `.mobile_ui_version` 标记不变，
         *      `ensureMobileUi()` 直接跳过解包，**老设备永远拿不到这个分节**。
         * 1.0.0-62：**沉浸式下汉堡整行下移的根因不在插件，而在原生推错了单位。**
         *      真正的修复在 `MainActivity.pushSystemBarInset()`（把状态栏物理像素
         *      ÷ density 换算成 CSS 像素再推）。本版只改了两处**注释**：
         *      `mpSyncFloatNavGap()` 与 CSS 那条规则原先写着「该变量在本 App 里
         *      从未被任何代码写入（恒 0）」—— 那句话在修复 58 之后就过时了，
         *      留着会误导下一个来改的人按"变量恒 0"的旧前提动手。
         *      ⚠️ 严格说这一版**没有改任何 JS 行为**，本可以不 bump；但
         *      `mobileui/lib/client.js` 是降级编译的输入之一，注释变了产物字节就变，
         *      而 `.mobile_ui_version` 与 `-CompatVersion` 两戳只要都对得上，
         *      设备上那棵树就会被跳过、继续用旧产物覆盖。为免"源码与设备不一致"
         *      这种最难查的中间态，两个戳一起 bump（见 ⑯ 的 v4~v17 全部先例）。
         * 1.0.0-63：**特殊权限界面布局重做 + 新增 Shizuku 通道。**
         *      界面侧（`mobileui/lib/client.js`）：
         *      ① 新增 Shizuku 卡片，摆在三条路径的第一位 —— 它是**唯一不依赖
         *         adbd** 的那条（不走 5555、不校验 adbkey、重启后依然有效），
         *         对 Android 10 那批「每次重启都要插电脑跑 adb tcpip」的用户是
         *         唯一走得通的路；
         *      ② 三条路径统一成同一个 `pathCard` 结构、顺序**固定**（原来是按
         *         系统版本翻转的，换机/升级后版面全变，用户得重新认一遍）；
         *      ③ 首屏「下一步」的决策树把 Shizuku 排在 adb 两条路之前；
         *      ④ 按钮去重：一屏最多一个主按钮（原来同屏摆了五个同等分量按钮）。
         *      原生侧：新增 `ShizukuChannel`（LocalServerSocket + IShizukuService
         *      .newProcess）、`PageBridge` 四个方法、runner 的 0 号通道。
         *      ⚠️ **必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`
         *      （18 → 19）**：`mobileui/lib/client.js` 是降级编译的输入之一，
         *      不 bump 就会命中「两戳都一致 → 整棵树跳过」，设备上继续跑旧布局，
         *      而 `已安装移动端 UI 插件 1.0.0-63` 这行日志看起来一切正常。
         *      本次真机实测踩到的正是这个形态（设备上 client.js 停在 369105 字节）。
         * 1.0.0-64：**特殊权限界面改成标签页布局**（用户要求：ADB / Shizuku / root
         *      各占一个标签页，点进去是二级界面）。
         *      ① 标签栏替代原来的「三条路径 + root 卡全部平铺」：平铺时一屏里
         *         四五张结构相似的卡纵向排开，用户在 360px 宽的屏上要滑很久，
         *         而且**看完也不知道该点哪张** —— 层级全靠徽章文字区分，太弱；
         *      ② 标签上带状态圆点，不点进去就能看出哪条通道通（圆点色与卡片
         *         徽章色同源，都取 `MP_PRIV_TONE`）；
         *      ③ 标签栏**本身就是导航**，不加「返回」按钮 —— 中途试过「标签栏 +
         *         三行概览卡」，同一组信息在一屏里说两遍，已删；
         *      ④ 默认停在 **Shizuku** 标签（唯一不依赖 adbd 的那条）；
         *      ⑤ 无线调试在 Android 10 上**恢复渲染**：上一版整张卡不渲染，
         *         用户根本看不到还有这条路，只会以为本应用漏做了。现在渲染成
         *         一张纯说明卡（徽章「不可用」、**不给任何按钮**）——
         *         判据是「有没有可点的动作」，不是「这一块在不在」。
         *      ⚠️ **必须同时 bump `tools/build-webview-compat.ps1 -CompatVersion`
         *      （19 → 20）**，理由同 v63：`mobileui/lib/client.js` 是降级编译的
         *      输入之一，不 bump 会命中「两戳都一致 → 整棵树跳过」，
         *      设备上继续跑旧布局，而日志看起来一切正常。
         * 1.0.0-65：修正 v64 里 `rootIntro` 的 Markdown 标记。
         *      文案写成「与上面两条通道**互不影响**」，而本页是**纯文本渲染**
         *      （React 直接塞进 DOM），星号会**原样显示**出来 —— 真机截图上
         *      就是「两条通道**互不影响**：没有 root 的设备…」。
         *      ⚠️ **这一版的存在本身就是一条教训，别删**：v64 的标签页布局做完后
         *      真机验过三个标签都正常，随后才发现星号问题；修完只重打了
         *      `mobile-ui.zip` 并重跑 `build-webview-compat.ps1`（仍是 v20），
         *      **忘了再 bump `MOBILE_UI_VERSION`** ⇒ 设备上两戳依旧是
         *      `1.0.0-64` / `chrome74-v20` ⇒ 解包整体跳过 ⇒ 修正版根本没上机，
         *      而 `已安装移动端 UI 插件` 那行日志压根不打印（版本没变），
         *      看起来「什么都没发生」。
         *      判据：改完 mobile-ui 源码，**两个戳都必须是新值**；
         *      只要其中一个还是上次的值，设备就会继续跑旧产物。
         * 1.0.0-102：设置页「交流群」换成**新群**（「DSHAPP交流群3」，群号 1121898890）。
         *      「DSHAPP交流群2」（1109733098）已满，用户给了新的群二维码截图。
         *      ① 群号 `MP_CONTACT_GROUP_NO`：1109733098 → 1121898890；
         *      ② 二维码 data URI 换成新群（700×700 WebP，68,522 B）。
         *         用户给的是**带群名/群号/QQ logo 的整张截图**，用 **zxing-cpp**
         *         直接读出四个角点（`203,695 / 952,695 / 952,1444 / 203,1444`），
         *         按此裁切、每边留 20px 静区、LANCZOS 缩到 700；
         *      ③ 可扫性用 zxing-cpp 验证：WebP 本身、以及模拟
         *         「CSS 240px × dpr3」重采样后**均解码成功**，
         *         内容 `https://qm.qq.com/q/l8uDVdDRXc`。
         *      ⚠️ **两个戳都 bump**：`MOBILE_UI_VERSION` 101 → 102，
         *      降级戳 `chrome74-v34` → `chrome74-v35`。
         *      这次没有 pwsh/esbuild，降级产物 `webview-compat.zip` 里的
         *      `mobileui/lib/client.js` 是**直接对旧产物做等量替换**得到的
         *      （只换群号与二维码 data URI，其余字节不动），
         *      改完用 `tools/check-frontend-syntax.mjs --target 74` 复检通过。
         * 1.0.0-66：设置页「交流群」换成**新群**（「DSHAPP交流群2」，群号 1109733098）。
         *      ① 群号 `MP_CONTACT_GROUP_NO`：1127652833 → 1109733098；
         *      ② 二维码 data URI 换成新群二维码（720×720 WebP，52,764 B，
         *         比旧图小 9,230 B）。用户给的是**带群名/群号/QQ logo 的整张截图**，
         *         所以先用蓝色像素（`B-R>40 且 B>150`）的包围盒定位二维码本体
         *         （415×415，`x 81..495, y 305..719`），裁出来、每边留 12% 白边
         *         （QR 的 quiet zone，缺了会扫不出）、再 LANCZOS 放大到 720；
         *      ③ 可扫性用 **zxing-cpp** 验证，不用 cv2 —— 这类彩色圆点风格二维码
         *         `cv2.QRCodeDetector` 解不出来（见上方 `MP_CONTACT_QR` 的注释）。
         *         实测：原图 / 裁切图 / 最终 PNG / 最终 WebP，以及按真实布局渲染的
         *         `CSS 240px × dpr1|2|3`、`200|160|120px × dpr3` 各档**全部解码成功**，
         *         内容一致为 `https://qm.qq.com/q/CkDhCJH1rW`（余量最大缩到 240px 仍可读）。
         *      ⚠️ **两个戳都要 bump**：`mobileui/lib/client.js` 是降级编译的输入之一，
         *      只重打 `mobile-ui.zip` 而不 bump `-CompatVersion`（21 → 22），
         *      设备上会命中「两戳都一致 → 整棵树跳过」，继续跑旧群的二维码 ——
         *      而日志不会打任何东西（版本没变），看起来像"什么都没发生"（同 v65 的坑）。
          * 1.0.0-88：**底栏模型名在窄屏下被截断**（用户要「真实的视觉感受」时发现）。
          *      症状（360px / dpr3 真机截图）：模型名显示成 "deepseek-v4.1..."，
          *      后面的推理等级 "Default" **宽度直接是 0**、完全看不见。
          *      根因不是文字太长，而是官方 `uV2eYG_row` 用
          *      `justify-content: space-between`，把 41px 剩余空间全推到了
          *      tools 与 trailing **中间**（实测 tools 结束 x=104、trailing 从 x=145
          *      开始），而右边模型名正因为缺 16px 在截断。
          *      修法：逐层放宽（row → flex-start、trailing/standardControls/
          *      _7KE1Ra_root → `1 1 0%`、按钮 `max-width: 139.5px → none`），
          *      把那段空隙让给模型名；推理等级 Default 隐藏（它是默认值，
          *      不显示不丢信息，而一个被压成 "Def..." 的次级信息比不显示更难看）。
          *      实测模型名 144px = 内容 144px，零截断。
          *      ⚠️ 两个戳都 bump（本常量 87 → 88，`-CompatVersion` 21 → 22）：
          *      `mobileui/lib/client.js` 是降级编译的输入之一（同 v65/v87 的坑）。
          *      ⚠️ 另外记一条**踩坑**：CSS 注释里写反引号会把整份 JS 弄坏 ——
          *      那段注释在模板字符串里，一个反引号就提前闭合模板，
          *      esbuild 报 `Expected ";" but found "deepseek"`。CSS 注释里
          *      一律用普通引号，别用反引号。
          * 1.0.0-89：**推理等级被藏掉是错误取舍，本版改回显示。**
          *      v88 为了让模型名完整显示，把 _7KE1Ra_triggerEffort 直接
          *      display:none 了 —— 当时以为它恒为 "Default"（默认值，
          *      藏掉不丢信息）。真机核对 aria-label 才发现实际值是 **"High"**：
          *      选择模型，当前 deepseek-v4.1-flash，推理等级 High
          *      也就是说**用户设过的非默认推理等级被藏起来了**，实打实的
          *      信息丢失，不能接受。
          *      改回显示，并按宽度实测重新分配：模型名 126px（必须完整）
          *      + High 22px + 内边距/箭头 ⇒ 按钮 168px 刚好够。
          *      High 比 Default 短（22 vs 43），所以显示它不会把模型名挤掉
          *      （v88 之前挤掉正是因为 Default 太长）。
          *      字号 10px + opacity 0.65：它是次级信息，不该和模型名抢视觉重心。
          *      ⚠️ 两个戳都 bump（本常量 88 → 89，-CompatVersion 22 → 23）。
          * 1.0.0-90：**思考正文不换行，把整页推出屏幕左侧**（修复 67）。
          *      症状（真机截图，一眼可见）：整个消息列表向左偏出屏幕，
          *      左侧文字被切一半（「已完成」只剩右半、消息操作图标只剩右半边），
          *      右侧反而留白；页面还能被横向拖动。
          *      根因：思考（reasoning）正文的 span 带 white-space: nowrap，
          *      一行到底不换行 —— 实测最长一条 **1197px**（视口 360px）。
          *      它把滚动容器 .wSkVaW_root 的 scrollWidth 从 360 撑到 **550**，
          *      浏览器为显示光标把 scrollLeft 自动设成 **39.67**，
          *      于是整页看起来左移了 40px（.wSkVaW_scrollBody 的 x = **-40**）。
          *      修法两处：① 正文恢复换行（white-space: normal +
          *      overflow-wrap: anywhere 兜住无空格长串）；
          *      ② ._root_1rdzk_1 官方写的 width: max-content 会按内容原始宽度
          *      撑开，改成 width: auto 才服从父级 100% 约束。
          *      实测：最长文本 1197 → 225px，scrollLeft 归 0，bodyX 回到 0，
          *      手势横向拖动无效（已实测 swipe 前后 scrollLeft 都是 339.67）。
          *      ⚠️ 两个戳都 bump（本常量 89 → 90，-CompatVersion 23 → 24）。
          * 1.0.0-91：**模型名在老会话里又被挤掉**（修复 68）——v89 的补丁不完整。
          *      v89 只保证了推理等级为 High（22px）时放得下；但真机打开一个
          *      老会话时等级是 **Default（33px）**，模型名立刻退回
          *      deepseek-v4.1-fl... 逐值实测（按钮固定 168px，模型名要 122px）：
          *        Default 33 → 模型名 115（截断）
          *        Medium  37 → 模型名 111（截断）
          *        Minimal 36 → 模型名 112（截断）
          *        High/Low/Max 19~22 → 126~129（放得下）
          *      结论：光调 flex 治不了根，**必须让文案本身变短**。
          *      新增 mpAbbreviateEffort()：按表把英文等级缩成中文
          *        Default→默认 / Medium→中 / Minimal→最低 / Low→低 /
          *        High→高 / Max→最高 / XHigh→超高
          *      实测缩写后每个都 ≤20px，模型名一律 128~138px，**任何等级都不截断**。
          *      完整英文保留在 title 与 aria-label 上，可访问性不受影响。
          *      挂载点三处：300ms observer 批处理、scheduleStatsSync 微任务、
          *      启动爆发循环（保证首屏不闪一下截断）。
          *      ⚠️ 两个戳都 bump（本常量 90 → 91，-CompatVersion 24 → 25）。
          * 1.0.0-92：**模型名与推理等级粘连**（修复 69）——v91 引入的视觉回归。
          *      v91 把推理等级改成中文后，「deepseek-v4.1-flash默认」**挤成一片**
          *      没有间距。根因：v89 为挤宽度把 _7KE1Ra_trigger 的 gap 压到 1px，
          *      英文 High 有右侧字距留白看着还行，中文字形紧凑，1px 就是粘连。
          *      但直接加大 gap 会让模型名重新截断（实测 gap 3px → 模型名 119 < 122）。
          *      解法：从别处腾 4px —— 官方 uV2eYG_row 的 gap 是 12px，
          *      而 tools 只占 80px，中间那 12px 纯浪费，缩到 8px。
          *      实测：按钮 163 → 167px，模型名 123px（= 内容宽度，零截断），
          *      推理等级间距 3px（不粘连）。
          *      ⚠️ 两个戳都 bump（本常量 91 → 92，-CompatVersion 25 → 26）。
          * 1.0.0-93：**短模型名下出现幽灵间距**（修复 70）。
          *      换到 agnes-3.0-flash 时，「agnes-3.0-flash    默认」中间空出一大块，
          *      而 deepseek-v4.1-flash 完全正常 —— 所以一直没暴露。
          *      根因：官方给 label 设了 text-align: center，而 v89 起我写成
          *      flex: 1 1 auto ⇒ 盒子撑满剩余空间、文字居中、两边各留一半空白。
          *      实测（按钮 172px）：
          *        agnes-3.0-flash  盒子 128 / 文字 94 ⇒ 真实间隙 20px
          *        deepseek-v4.1-flash 盒子 122 / 文字 122 ⇒ 贴满，看不出
          *      ⚠️ 改成 text-align: left 反而更糟（空白全推到右边，间隙 37px）。
          *      正解是让盒子按内容收缩：flex 1 1 auto → **0 1 auto**
          *      （0=不撑满，1=仍可压缩，超长模型名照样出省略号）。
          *      实测：短名 94=94 间隙 3px；长名 122=122 间隙 3px；
          *      超长名 盒子 128 / 文字 287 ⇒ 截断出省略号，布局不破。
          *      ⚠️ 两个戳都 bump（本常量 92 → 93，-CompatVersion 26 → 27）。
          * 1.0.0-94：**整页能横向滚 114px**，点「更多操作」页面就左偏（修复 71）。
          *      现象：点右上角「⋯」后整个界面左移 114px，标题栏按钮从 x=294 跑到 180，
          *      输入框/发送键/遥测栏一起跟着跑（bodyX = -114、root.scrollLeft = 114）。
          *      排查：菜单本身只有 148px 宽不溢出（排除）；全树扫「右边界超视口 2px」
          *      命中数 0（排除）；点「刷新」关菜单后 scrollLeft 自动回 0（非持续偏移）。
          *      真凶是官方一对**宽度探针** .wSkVaW_widthHandle（absolute、width:0、
          *      height:691、left:-184px / left:544px，视觉上完全不可见，官方靠它们量宽），
          *      它们把 body 的 scrollWidth 撑到 550（360 视口 + 184 左 + 6 右），root 跟着变 550。
          *      ⚠️ 阴在：root 本来就是 overflow-x:hidden，手指横滑**没反应**
          *      （实测 339.67 → 339.67 纹丝不动），用户很难自己发现；
          *      但 hidden **只挡手势、不挡程序化滚动** —— 实测 scrollLeft=50 照样成功、
          *      bodyX 立刻 -50。所以任何一次「把元素滚进视口」的动作（点视口外按钮、
          *      键盘补偿滚动、scrollIntoView）都会把页面永久推偏，而用户既滑不回来、
          *      也不知道发生了什么。
          *      修法：body 加 overflow-x: **clip**（不是 hidden —— clip 不产生滚动容器，
          *      程序化滚动也失效）。实测探针不受影响（x 仍 -184/544、h 691、offsetW 0），
          *      root.scrollWidth 550 → 360，scrollIntoView 后 bodyX 恒 0。
          *      ⚠️ 两个戳都 bump（本常量 93 → 94，-CompatVersion 27 → 28）。
          * 1.0.0-95：**宽表格右侧 226px 在手机上永远看不到**（修复 72）。
          *      现象（真机截图一眼可见）：助手回复里的宽表格被右边缘硬切掉，
          *      表头「目标 / p50 / p95 / max」只显示到 p95，max 整列看不到，
          *      p95 的值（25ms / 1709）也被切一半。
          *      实测两个宽表格：容器 294px，内容 512 / 520px ⇒ 藏掉 218 / 226px。
          *      根因：官方给 .md-table-wide 设的是 **overflow-x: hidden**（不是 auto），
          *      本意是桌面端 —— 容器带 tabindex="0"，靠**键盘**左右滚。
          *      但手机上既没键盘、hidden 又**挡掉触摸滑动**：
          *      实测 dispatch touchstart+touchmove 后 scrollLeft 仍是 0。
          *      ⇒ 右侧内容**永久不可达**，这是移动端信息丢失，不是设计取舍。
          *      修法：改回 overflow-x: auto + iOS 惯性滚动。
          *      实测：scrollLeft 可到 218 / 226，四个表头**逐个**都能滚到可见
          *      （scrollIntoView 验证 0 → 86 → 218），max / 一致性列完整显示。
          *      ⚠️ .md-table-wide 是**语义类名**（非构建期哈希），稳定性高。
          *      ⚠️ 两个戳都 bump（本常量 94 → 95，-CompatVersion 28 → 29）。
          * 1.0.0-96：任务卡片半透明 ⇒ 底下的正文**透上来**，主次颠倒（修复 73）。
          *      现象（截图一眼可见）：任务卡片（「任务 5 已完成」+ 绿点清单）浮在
          *      正文上，背景只有 58% 不透明，下面那条消息的文字
          *      （「错误信息是「特权通道不可用」…」）透过卡片显出来，和清单叠成一团。
          *      实测配色正是难看的原因：背后正文 rgb(15,17,21) 近黑，
          *      卡片背景 rgba(248,249,250,0.58) 58% 浅灰，卡片自身文字 rgb(97,102,107)。
          *      混色 0.58×248 + 0.42×15 ≈ 150 ⇒ 透出来的正文约 rgb(150,152,155)，
          *      **比卡片内文字还亮** ⇒ 被遮挡的内容比该读的内容更显眼。
          *      ⚠️ 不是 backdrop-filter 没生效：实测内核是 Chrome **114**（不是降级目标 74），
          *      backdrop-filter 支持且 computed 就是 blur(40px) saturate(1.5)，
          *      祖先链也无 filter/opacity/transform 破坏。纯粹是基础背景太透明。
          *      修法：不透明度提到 0.97（保留毛玻璃质感、不透字），透出率 42% → 3%。
          *      亮色 rgba(248,249,250,0.97)、暗色 rgba(67,69,74,0.97) —— **必须分开写**，
          *      两边底色不同，不能共用一个颜色。
          *      ⚠️ 不改"卡片遮挡正文"本身：卡片在 composerStack 内与 composerSeat 同级，
          *      官方就是做成浮动面板（实测 30%/50%/70%/90% 都有 1~6 条消息被压住，
          *      只有滚到底部才不重叠）。根治要改官方布局，风险远大于收益。
          *      ⚠️ 两个戳都 bump（本常量 95 → 96，-CompatVersion 29 → 30）。
          * 1.0.0-97：两处**对比度不达标**，亮/暗主题下都读不清（修复 74 + 75）。
          *      【修复 74】底栏推理等级「默认」：实测亮色 **2.18:1**、暗色 **2.42:1**
          *      （WCAG AA 正文要求 4.5:1）。根因是我在修复 66 里加的 opacity: 0.65 ——
          *      方向对（次级信息不该抢重心）但太狠：实测把 opacity 拉到 1 也只有
          *      3.71~3.76:1，**光调透明度永远不够**，颜色本身 rgb(129,133,140) 就偏浅。
          *      也不能只改一个颜色：亮底(白)要深色、暗底(rgb(44,44,46))要浅色，
          *      实测同一颜色不可能两边达标（提亮 173,178,184 → 暗 6.53 ✅ / 亮 2.13 ❌；
          *      压深 97,102,107 → 亮 5.80 ✅ / 暗 2.40 ❌）。⇒ 必须写两套。
          *      最终：亮 rgb(97,102,107) 5.80:1、暗 rgb(173,178,184) 6.53:1，opacity 一律 1。
          *      【修复 75】输入框占位符：亮色 **2.13:1**、暗色 **3.76:1**。这是用户
          *      **每次发消息都会看到**的提示文字。颜色来自官方变量 label-dimmed。
          *      ⚠️ 但不能一把拉满：占位符故意比正文淡是有语义的（区分"还没输入"与
          *      "已输入内容"），拉满会让人误以为已经填好了。⇒ 取**刚过 AA**：
          *      亮 rgb(112,117,122) 4.65:1、暗 rgb(165,170,176) 5.96:1。
          *      顺带把同色系的「代码块」标签与「快准狠模式（通用）」也从 ~3.5 提到 4.65。
          *      ⚠️ 两个戳都 bump（本常量 96 → 97，-CompatVersion 30 → 31）。
          * 1.0.0-98：抽屉里两处次级文字读不清（修复 76）。
          *      用修复 75 那套对比度扫描把侧边抽屉也过了一遍，命中 6 处 < 4.5:1，
          *      其中两处值得修（都是 rgb(129,133,140) → 3.55:1）：
          *        · .bhn1Oq_sectionLabel         「工作区」分组标题
          *        · .bhn1Oq_sessionOverflowButton「展开其余 2 个会话」
          *      后者尤其要紧：它是**可点击的操作**，文字太淡会让人看不出能点，
          *      直接影响会话列表的可用性。
          *      ⚠️ 只修这两处，没把 6 处全拉高：其余（时间戳、上下文 68%、
          *      「空面板」提示、任务卡「5 已完成」）都是**纯次级信息**，全拉到 4.5
          *      会让抽屉失去层次感、看起来一片同等重要。这两处是"导航结构"和
          *      "可点操作"，性质不同才单独提上来。
          *      取值同修复 75：亮 rgb(105,110,115) 4.93:1 / 暗 rgb(165,170,176) 7.36:1。
          *      ⚠️ 两个戳都 bump（本常量 97 → 98，-CompatVersion 31 → 32）。
          * 1.0.0-99：设置页所有说明副标题只有 3.71:1（修复 77）。
          *      设置里每项标题下那行灰字（「选择新会话的默认权限模式」
          *      「仅影响会话内容的字号」「选择希望看到多少工具调用细节」）是
          *      **用户理解这个开关干什么的唯一说明**，太淡等于没有说明。
          *      实测 rgb(129,133,140) 叠白底 → 3.71:1。
          *      ⚠️ 用 [class*="_desc"] 属性选择器而不是逐个写类名：这批文字
          *      **至少三个不同类名**（.oY77xG_desc / .bVCLcG_desc / ._2XZxNq_desc
          *      / .Pt1bsG_description / .T1PP_q_desc），各模块各自起名，逐个列既漏
          *      又难维护（官方一改 hash 前缀就全失效）。实测命中 8 个，颜色只有两种：
          *      6 个 3.71（不达标）+ 2 个 5.8（已达标），统一提亮不会误伤后者。
          *      取值亮 rgb(105,110,115) 5.15:1 / 暗 rgb(165,170,176) 5.96:1。
          *      ⚠️ 两个戳都 bump（本常量 98 → 99，-CompatVersion 32 → 33）。
          * 1.0.0-100：合并 PR #3 —— 悬浮气泡分节新增「键盘避让」卡片
          *      （46×26 开关 + 无障碍权限检测 + 独立「去开启权限」按钮）。
          *      改的仍是 `mobileui/lib/client.js`，降级产物随之变化，必须 bump。
          *      ⚠️ PR #3 原作把本常量写成 1.0.0-68、`-CompatVersion` 写成 24，
          *      那是基于旧的 66/22 算的。合并时**必须取本地已推进的值再 +1**
          *      （99 → 100 / 33 → 34），否则会回退，设备上静默跑回没有这张卡的旧 UI。
          *      ⚠️ 两个戳都 bump（本常量 99 → 100，-CompatVersion 33 → 34）。
          * 1.0.0-103：两条移动端修复（用户截图报的两个问题）。
          *      ① 修复 78 —— 输入框点中间落行尾。0.1.5 起官方把输入框从
          *         `<textarea>` 换成 contenteditable div，Android WebView 在这上面
          *         的「像素 → 字符偏移」算得很差（点中间、光标跳行尾）。改为在
          *         `click` 上自己用 caretPositionFromPoint 定光标；只在
          *         「空选区 + 落点明显不同」时动手，不干扰长按选词/扩选/组字。
          *      ② 修复 79 —— 思考正文两行竖直叠在一起糊成一团。流式追加时容器
          *         高度更新落后于文字一帧（叠加 `overflow-wrap: anywhere` 让每行
          *         宽度不可预测），改为给思考正文显式 line-height + contain: layout。
          *      本版是**纯 JS 改动**（只有 client.js），未动 CSS 之外的产物，
          *      但 `mobileui/lib/client.js` 是降级编译输入，字节变了，
          *      所以本常量必须 bump（否则设备上继续跑旧 client.js，两条修复都收不到）。
          * 1.0.0-104：修复 78 的实现修正（103 那版有缺陷，未发布到设备即被本版取代）。
          *      ① 原实现用 `e.target` 当编辑区容器做 contains 判据 —— 点文字时
          *         target 是 Lexical 渲染的行内 span、点空白处是 root、点 <br> 又是
          *         另一个，判据会漏掉一大半情形。改成先 closest 找 composer 根，
          *         再在根里找 contenteditable 本体。
          *      ② 原实现在 click 里**同步**改 selection，而 Lexical 自己也监听
          *         click 并会把 selection 写回内部 model，随后覆盖我们设的位置 ——
          *         用户看到「光标闪一下又跳回行尾」。改成 setTimeout(0) 延后到
          *         本次事件处理完之后，让浏览器自然派发 selectionchange，
          *         Lexical 跟着同步。不碰它的内部 API。
          *      ③ 同时把修复 79 的根因注释改成诚实表述：原注释把推断写成了
          *         「实测」，而这一条**没有真机逐帧验证过**，会误导后来人。
          * 1.0.0-105：用户反馈的《移动端 UI 修复报告》里三条**在当前源码中真实存在**
          *      的缺陷（报告其余条目描述的是手机上手工打的补丁，源码里没有）。
          *      ① 修复 80 —— 过程行标题「一直抽搐」。修复 67 为了治「思考正文不换行
          *         把整页推出屏幕」，把官方 TextShimmer 的 `._root_1rdzk_1`
          *         （`width: max-content`，标题单行的唯一保证）无差别改成了 `auto`。
          *         流式标题因此按可用宽度换行，行数 1→2→3 反复变，容器高度跟着弹。
          *         改为**只在 `.O_Ebla_title`（过程组标题）内部**把 max-content 还回去，
          *         思考正文章节完全不动；顺带把扫光的 `steps(48)` 改成 `linear`。
          *      ② 修复 80 —— 底部统计条字号突然变大。`processStatsLine` 原先直接取
          *         `[data-composer-stat]`（anchor 层）当 label，`textContent` 赋值会
          *         **删掉内部 button.bOPqQW_pill + span.bOPqQW_label**，只剩裸文本，
          *         字号从官方 13px 变成继承 body 的 16px，撑满底栏。
          *         改为先取下钻到 `.bOPqQW_label`，取不到才退回 anchor（保证降档仍触发）。
          *      ③ 修复 80 —— 点「刷新」后 401、会话列表空、回首页。页面侧原来是
          *         整页 `location.reload()`，而重载请求的是 `./`、不再带启动时那条
          *         `?token=`，于是 API / WebSocket 全部 401，只能重启 App 恢复。
          *         改为走原生桥新增的 `reloadWebUi()`：重载 `lastLoadedUrl`
          *         （带 token 的原始地址），并复用 `reloadWithRetry` 那套
          *         「重置首帧 → 重载 → 等新首帧 → 失败重试」判据。
          *         刷新前先 `mpPersistCurrentSession()` 落盘当前会话，避免跳槽。
          *      ⚠️ 本版**动了 Kotlin**（PageBridge / SystemWebHost / GeckoWebHost /
          *         MainActivity / content.js 五处），不只是 client.js。
          *      本常量仍必须 bump：不然设备上的 `.mobile_ui_version` 不变，
          *      mobile-ui.zip 解包被整体跳过，三条修复一条都收不到。
          */
        private const val MOBILE_UI_VERSION = "1.0.0-105"
        private const val MOBILE_UI_STAMP = ".mobile_ui_version"

        // ── 出厂默认设置 ─────────────────────────────────────────────
        //
        // 只在首次安装时播种进 profile 的**用户 patch 层**，之后永不覆盖。
        // 为什么不能用 `--patch` overlay、为什么不会被引擎覆盖，见 seedDefaultSettings()。

        /**
         * 引擎启动时用的 profile 名（`--profile web`）。
         *
         * ⚠️ **`internal` 而不是 `private`**：`RescueCommandBridge` 的 `probe`
         *   默认就探测这个 profile。让它自己写 `"web"` 字面量等于把这个名字
         *   复制一份，将来改名必漏一处。
         */
        internal const val PROFILE_WEB = "web"

        /** [PROFILE_WEB] 的私有别名，保持文件内既有引用不动。 */
        private const val PROFILE_NAME = PROFILE_WEB

        /**
         * 救援 profile 名（`--profile rescue`）。
         *
         * ⚠️ **不能用引擎内置模板名**：`PROFILE_TEMPLATES` 只有
         * `acp` / `web` / `headless` / `sdk` / `sdk-minimal` 五个，
         * 传别的名字引擎直接抛
         * `dsh: profile "rescue" does not exist; create it with 'dsh plugin --profile rescue add <package>'`
         * （`dsh-app-boot/lib/index.js` L973-974）。
         *
         * 真机实测过（2026-10-14，CDY-TN90 / Android 10）：
         * 手工写好 `profiles/rescue/package.json`（bundles =
         * `["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"]`）之后，
         * `--profile rescue --dump-config` 打出完整条目表、
         * `--profile rescue --no-open --port 0` 打出
         * `dsh web: http://127.0.0.1:44057/?token=…` —— 完全可用。
         *
         * ⇒ 所以 App **自己写这个 profile 的 `package.json`**，不指望引擎初始化它。
         * 见 [ensureRescueProfile]。
         */
        private const val RESCUE_PROFILE_NAME = "rescue"

        /**
         * 救援 profile 的 bundle 清单。
         *
         * 逐字取自引擎自己的 `PROFILE_TEMPLATES.web.bundles`
         * （`dsh-app-boot/lib/index.js` L531），**不是**从用户的
         * `profiles/web/package.json` 推导的 —— 那份文件正是可能已经坏掉的东西
         * （用户装了 40 个插件、其中某个拖挂了核心插件）。用它当模板等于
         * 把病灶复制进救援环境，救援就一起起不来了。
         *
         * ⚠️ 只放 `@deepseek-ai/…` 核心 bundle ⇒ 天然满足不变量 I1
         * （救援启动必须比正常启动简单，不能成为第二个失败源）。
         */
        private val RESCUE_BUNDLES = listOf(
            "@deepseek-ai/dsh-base",
            "@deepseek-ai/dsh-web-app",
        )

        /** profile 的用户 patch 层文件名（引擎的 `PROFILE_PATCH_FILENAME`）。 */
        private const val PROFILE_PATCH_FILENAME = "cordis.patch.yml"

        /**
         * 默认模型那一行的 entry id。
         *
         * 出处是 bundle 层 `@deepseek-ai/dsh-base/cordis.patch.yml`（`- id: agent-default-model`）。
         * 我们要覆盖的是它的 `config.provider`/`config.model`，所以 **id 必须逐字一致**
         * 才能命中同一行。
         */
        private const val DEFAULT_MODEL_NS = "agent-default-model"

        /** 该 entry 的 `name`（= 引擎里提供 `agentDefaultModel` 服务的包名）。 */
        private const val DEFAULT_MODEL_PACKAGE = "@deepseek-ai/dsh-agent-default-model"

        /**
         * 出厂默认设置。格式与 GUI 设置页写出的完全一致（`id` + `name` + `config`），
         * 所以用户在设置页里改任何一项，都只是往同一份文件里追加/覆盖对应的 `config`。
         *
         * 四项取值均由真机实测确定（Huawei CDY-TN90 / Android 10）：
         *
         * | 设置页文案 | ns | 取值 | 为什么是这个值 |
         * |---|---|---|---|
         * | 权限 = 完全权限 | `permission` | `defaultPreset: danger-full-access` | 手机上没有桌面那种"逐条审批"的交互条件 |
         * | 字号大小 = 15 | `ui-theme` | `fontSize: 15` | 引擎默认 14 在手机上偏小 |
         * | 工作步骤展示 = 标准 | `ui-chat` | `transcriptView: standard` | 引擎默认 `detailed`，手机上太占屏 |
         * | 繁忙时的发送行为 = 插话发送 | `ui-conversation` | `busyEnter: steer` | 引擎默认 `queue`（排队），插话更符合"打断纠正"的直觉 |
         * | 默认模型 = 内置 Agnes | `agent-default-model` | `provider: agnes` | 见下 |
         *
         * ## 为什么默认模型必须显式播种
         *
         * 引擎的出厂默认写在 bundle 层（`@deepseek-ai/dsh-base/cordis.patch.yml`）：
         *
         * ```yaml
         * - id: agent-default-model
         *   name: '@deepseek-ai/dsh-agent-default-model'
         *   config:
         *     provider: deepseek-official
         *     model: deepseek-flash
         * ```
         *
         * 也就是**官方模型**。而本 App 的用户大多是冲着内置号池来的 —— 他们没配过
         * 任何第三方 API，`deepseek-official` 对他们来说是个**必然失败**的路由
         * （没有凭据）。表现就是「第一次打开就报错，得自己去模型选择器里换一个」。
         *
         * ⚠️ **不能靠改 bundle 层**：那是引擎的包内文件，引擎升级就被覆盖。
         * ⚠️ **也不能只写进 [DEFAULT_SETTINGS_PATCH]**：那份只在「用户从未动过设置」
         *   时播种一次。而 `ensureKeyPoolProvider()` 会往同一个文件里补内置号池，
         *   于是**任何已装过的设备**都不再是空模板 ⇒ 播种被跳过 ⇒ 老用户永远
         *   拿不到这个默认值。所以老安装要由 [ensureDefaultModel] 单独补缺。
         *
         * ⚠️ `permission` 这项 GUI 会连同 `presets` 整块一起写出（实测），
         * 这里照抄同一形态，保证"用户之后在设置页改这一项"时 diff 最小。
         * ⚠️ 各项的 `name` 必须与引擎里该 entry 的包名一致，否则 patch 匹配不上。
         */
        private val DEFAULT_SETTINGS_PATCH = """
            # 由 DSH Android 客户端播种的出厂默认设置（仅首次安装时写入一次）。
            # 这是**你的**配置层：此后在设置页里的任何改动都写在这里，App 不再覆盖。
            # 想恢复出厂值：删掉本文件后重启 App。
            - id: ui-theme
              name: "@deepseek-ai/dsh-client-ui-theme"
              config:
                fontSize: 15
            - id: permission
              name: "@deepseek-ai/dsh-permission-presets"
              config:
                presets:
                  read-only:
                    sandbox: read-only
                    approval: ask
                  workspace-write:
                    sandbox: workspace-write
                    approval: ask
                  danger-full-access:
                    sandbox: danger-full-access
                    approval: never
                defaultPreset: danger-full-access
            - id: ui-chat
              name: "@deepseek-ai/dsh-client-ui-chat"
              config:
                transcriptView: standard
            - id: ui-conversation
              name: "@deepseek-ai/dsh-client-ui-conversation"
              config:
                busyEnter: steer
            - id: agent-default-model
              name: "@deepseek-ai/dsh-agent-default-model"
              config:
                provider: $KEY_PROVIDER
                model: ${KEY_MODELS.first()}
        """.trimIndent() + "\n"

        // ── uid 2000 特权通道 ────────────────────────────────────────
        //
        // 把 shell 身份执行注册成一个**独立的 `shell` 工具**（不是改 bash 的行为）。
        // 机制、实测数据与取舍见 assets/dsh-android-shell.mjs 与
        // mobile-ui-plugin/dsh-android-shell/lib/index.js 的头部注释。

        /** uid 2000 执行通道脚本（直连本机 adbd，走 ADB 协议）。 */
        private const val PRIVILEGED_RUNNER_ASSET = "dsh-android-shell.mjs"
        private const val PRIVILEGED_RUNNER_FILE = "dsh-android-shell.mjs"

        /** 注册 `shell` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val SHELL_TOOL_ASSET = "shell-tool.zip"
        private const val SHELL_TOOL_PACKAGE = "dsh-android-shell"
        private const val SHELL_TOOL_PATCH = "shell-tool.patch.yml"

        /**
         * 插件解包目录，相对 `$DSH_HOME`。
         *
         * ⚠️ **刻意不放在 profile 的 `node_modules` 下** —— 与移动端 UI 插件同一个坑：
         * 那里归 pnpm 管，而本包不在 profile 的 `dependencies` 里，用户每装一次插件
         * 就会被挪进 `node_modules/.ignored/`，overlay 里的包名随即解析失败。
         * 详见 [ensureMobileUi]。
         */
        private const val SHELL_TOOL_DIR = "shell-tool"

        // ── root（uid 0）特权通道 ────────────────────────────────────
        //
        // 与 uid 2000 通道**同一套做法、同一套取舍**（见上面那段），只是身份档位不同：
        // 注册一个独立的 `root` 工具，仅在**宿主探测到确实能提权**时才挂载。
        // 检测方式与「存在 su ≠ 有 root」的三种假阳性见 [RootAccess]。

        /** root 执行通道脚本（经 `su -c` 以 uid 0 执行）。 */
        private const val ROOT_RUNNER_ASSET = "dsh-android-root.mjs"
        private const val ROOT_RUNNER_FILE = "dsh-android-root.mjs"

        /** 注册 `root` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val ROOT_TOOL_ASSET = "root-tool.zip"
        private const val ROOT_TOOL_PACKAGE = "dsh-android-root"
        private const val ROOT_TOOL_PATCH = "root-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 与 [SHELL_TOOL_DIR] 同理，刻意在 pnpm 管辖之外。 */
        private const val ROOT_TOOL_DIR = "root-tool"

        // ── 悬浮球控制工具 ───────────────────────────────────────────
        //
        // 与 shell / root 那两个**同一套做法**（独立工具 + 刻意在 pnpm 管辖之外），
        // 差别只在能力本身：那两个给的是别的身份，这个给的是**改悬浮窗**。
        //
        // 为什么需要它：悬浮球的外观接口只有**页面侧**插件够得着
        // （`window.DSHAndroid.bubbleApply()`），而模型跑在 Node 进程里，那里没有
        // `window` —— 于是「让 AI 改悬浮窗」在可发现性上是空的。这个插件把同一条
        // 能力注册成一个 `bubble` 工具，并靠 [BubbleCommandBridge] 的文件信箱
        // 把命令送回原生。详见 `mobile-ui-plugin/dsh-android-bubble/lib/index.js`。

        /** 注册 `bubble` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val BUBBLE_TOOL_ASSET = "bubble-tool.zip"
        private const val BUBBLE_TOOL_PACKAGE = "dsh-android-bubble"
        private const val BUBBLE_TOOL_PATCH = "bubble-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
        private const val BUBBLE_TOOL_DIR = "bubble-tool"

        // ── 系统栏配色工具 ───────────────────────────────────────────
        //
        // 与 bubble 工具**同一套做法**（独立工具 + 文件信箱 + 刻意在 pnpm 管辖之外），
        // 差别只在能力：那个改悬浮窗，这个改**状态栏（顶部）与导航栏（底部）的配色**。
        //
        // 为什么需要它：那两条栏原先**只由页面底色驱动**（页面侧上报 → 原生一并刷），
        // 没有任何独立入口 —— 用户改不了，模型更够不着。这个插件把同一条能力注册成
        // 一个 `statusbar` 工具，并靠 [SystemBarCommandBridge] 的文件信箱把命令送回
        // 原生。详见 `mobile-ui-plugin/dsh-android-statusbar/lib/index.js`。
        //
        // ⚠️ 与 bubble / ui 工具走**不同的信箱文件**：命令可能并发发起，回执按 `id`
        //   配对，而各插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。

        /** 注册 `statusbar` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val STATUSBAR_TOOL_ASSET = "statusbar-tool.zip"
        private const val STATUSBAR_TOOL_PACKAGE = "dsh-android-statusbar"
        private const val STATUSBAR_TOOL_PATCH = "statusbar-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
        private const val STATUSBAR_TOOL_DIR = "statusbar-tool"

        // ── UI 调试工具 ──────────────────────────────────────────────
        //
        // 与 bubble 工具**同一套做法**（独立工具 + 文件信箱 + 刻意在 pnpm 管辖之外），
        // 差别只在能力：那个改悬浮窗，这个**看和点页面里的界面**。
        //
        // 为什么需要它：界面是官方 DSH Web GUI 跑在 WebView 里，按钮全是 React
        // 渲染的 DOM；而模型跑在 Node 进程里，那里既没有 `window` 也没有 `document`
        // —— 于是「让 AI 自己测试这个 App」在能力上是空的。这个插件把「枚举界面
        // 上的按钮（带屏幕精确坐标）/ 点击 / 输入 / 滚动 / 读文本」注册成一个 `ui`
        // 工具，靠 [UiProbeBridge] 的信箱把命令送到原生、再由原生经
        // `WebHost.evaluateJavascript` 打进页面里的探针（`assets/ui-probe.js`）。
        //
        // ⚠️ 与 bubble 工具**走不同的信箱文件**：命令可能并发发起，回执按 `id`
        //   配对，而两个插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。
        //   理由与 `bubble-state.json` 独立成文件完全一致。

        /** 注册 `ui` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val UI_TOOL_ASSET = "uiagent-tool.zip"
        private const val UI_TOOL_PACKAGE = "dsh-android-uiagent"
        private const val UI_TOOL_PATCH = "uiagent-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。同样刻意在 pnpm 管辖之外。 */
        private const val UI_TOOL_DIR = "uiagent-tool"

        // ⚠️ 这里原本有 `UI_PROBE_ASSET = "ui-probe.js"`，已删除：从未被使用。
        //   探针资产的实际读取点不在本类（资产名请以 `assets/ui-probe.js` 为准，
        //   见 [UiProbeBridge] 与 `EngineManager` 的注释）。

        // ── 日志工具 ─────────────────────────────────────────────────
        //
        // 与 bubble / statusbar / ui 工具**同一套做法**（独立工具 + 文件信箱 +
        // 刻意在 pnpm 管辖之外），差别只在能力：那些改外观/点界面，这个**读日志**。
        //
        // 为什么需要它：日志系统的两个入口（设置页分节、启动/崩溃浮层按钮）都是给
        // **人**的，而模型跑在 Node 进程里 —— 它看不到原生侧的内存缓冲，也看不到
        // `filesDir/logs` 下的 `app.log` 与崩溃现场。于是「你看看日志」这句话对模型
        // 是空的，它只能猜。这个插件把「读日志 / 看崩溃现场 / 生成诊断包」注册成一个
        // `logs` 工具，靠 [LogCommandBridge] 的文件信箱把请求送回原生。
        // 详见 `mobile-ui-plugin/dsh-android-logs/lib/index.js`。
        //
        // ⚠️ 与上面三个工具**走不同的信箱文件**：命令可能并发发起，回执按 `id`
        //   配对，而各插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。
        //   这类错配的表现是「AI 说日志里没有这条错误」，比报错危险得多。
        //
        // ⚠️ **没有「能力探测」这一步**：读日志不需要任何授权。只要插件入口在就挂载，
        //   与 [writeBubbleToolPatch] 同理。

        /** 注册 `logs` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val LOGS_TOOL_ASSET = "logs-tool.zip"
        private const val LOGS_TOOL_PACKAGE = "dsh-android-logs"
        private const val LOGS_TOOL_PATCH = "logs-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
        private const val LOGS_TOOL_DIR = "logs-tool"

        // ── 浏览器工具 ───────────────────────────────────────────────
        //
        // 与 bubble / statusbar / ui / logs **同一套做法**（独立工具 + 文件信箱 +
        // 刻意在 pnpm 管辖之外），差别只在能力：那些操作**这个 App**，这个**上网**。
        //
        // 为什么需要它：引擎自带的侧栏浏览器是 iframe，而 GitHub
        // （`X-Frame-Options: deny`）、Google（CSP）拒绝被嵌入 —— iframe 方案
        // 连登录页都加载不出来。所以换成原生 `AiBrowserView`（真 WebView，
        // 跑在用户手机上），本插件把「开网页/读页面/点按钮/填表单」注册成
        // `browser` 工具，靠 [com.dsh.android.browser.BrowserBridge] 的文件信箱
        // 把命令送回原生。详见 `mobile-ui-plugin/dsh-android-browser/lib/index.js`。
        //
        // ⚠️ **信箱必须是独立文件**：命令可能并发发起，回执按 `id` 配对，
        //   而各插件各自从 1 自增 —— 共用文件会让工具调用收到对方的回执。
        //
        // ⚠️ **没有「能力探测」这一步**：浏览器不需要任何授权，只要入口在就挂载。
        //   浏览器实例由 [com.dsh.android.MainActivity.ensureAiBrowser] 按需创建。

        /** 注册 `browser` 工具的 host 插件；打包成 zip 随 APK 分发。 */
        private const val BROWSER_TOOL_ASSET = "browser-tool.zip"
        private const val BROWSER_TOOL_PACKAGE = "dsh-android-browser"
        private const val BROWSER_TOOL_PATCH = "browser-tool.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。⚠️ 同理，刻意在 pnpm 管辖之外。 */
        private const val BROWSER_TOOL_DIR = "browser-tool"

        // ── 右侧栏「AI 浏览器」入口（**客户端插件**）──────────────────────
        //
        // 与上面那些**工具插件**不同：这个包不含任何工具，它只在前端注册一个
        // 右侧栏 tab，点开就调原生 `openAiBrowser()`。所以它必须在**引擎启动前**
        // 落盘 —— 引擎启动时才扫描 `dsh.client` 声明并编译前端 bundle。
        //
        // ⚠️ 单独成包、不复用 `mobile-ui.zip`：那个 zip 是**跨工作树共享的符号
        //   链接资产**，dev 分支的 `client.js` 比 main 多 17 万字节；重打它会把
        //   另一条分支的移动端修复整个回退掉。
        private const val BROWSER_PANE_ASSET = "browser-pane.zip"
        private const val BROWSER_PANE_PACKAGE = "dsh-android-browser-pane"
        private const val BROWSER_PANE_PATCH = "browser-pane.patch.yml"
        private const val BROWSER_PANE_DIR = "browser-pane"

        // ── 定向隔离层（L1.5） ────────────────────────────────────────
        //
        // 一个**纯禁用层**：只含 `- id: <条目 id>` + `disabled: true`，
        // 由 `RescueBoot.quarantinedNames()` 驱动，每次启动整体重写。
        //
        // ⚠️ 为什么用 `disabled` 而不是删文件/改 package.json：
        //   `cordis-plugin-include` 的 `applyEntryPatches` 把除
        //   `id`/`insert`/`name` 之外的键**原样赋给目标条目**
        //   （`for (const [key, value] of Object.entries(overrides)) target[key] = value`），
        //   而 `disabled` 正是 Cordis 条目的标准字段；`auditStartupEntries` 的注释也写明
        //   「disabled required entries are ignored」。所以这是**官方语义**，
        //   不需要改用户的任何文件，且随时可撤销（删掉本 overlay 即可）。
        //
        // ⚠️ 它必须是 overlay 列表的**最后一项**：`--patch` 按顺序应用，
        //   禁用一个由前面 `- insert:` 插入的条目，必须排在那条 insert 之后。
        private const val QUARANTINE_PATCH = "quarantine.patch.yml"

    /**
     * 安全模式把第三方插件包挪到这里（profile 目录下，**改名不删**）。
     *
     * ⚠️ 必须是点开头的名字：`node_modules` 的扫描与 pnpm 的簿记都约定忽略点目录，
     * 万一将来有人把 `node_modules` 整个交给包管理器，它也不会被当成一个插件包。
     */
    private const val SAFE_MODE_PLUGIN_DIR = ".safe-mode-plugins"

        // ── 桌面宠物状态桥 ───────────────────────────────────────────
        //
        // 与悬浮球控制工具**分开两个包**，理由见 [ensurePetBridge] 的注释。
        // 它订阅 `session/event`，把运行状态写进 `$DSH_HOME/bubble-state.json`。
        //
        // ⚠️ 与 `bubble` 工具走**不同的信箱文件**：回执按 `id` 配对，而两者各自
        //   从 1 自增，共用文件会让工具调用收到状态播报的回执。见
        //   `BubbleCommandBridge.STATE_FILE` 的注释。

        /** 状态桥插件；打包成 zip 随 APK 分发。不做版本戳（每次启动覆盖写）。 */
        private const val PET_BRIDGE_ASSET = "pet-bridge.zip"
        private const val PET_BRIDGE_PACKAGE = "dsh-android-pet"
        private const val PET_BRIDGE_PATCH = "pet-bridge.patch.yml"

        /** 插件解包目录，相对 `$DSH_HOME`。同样刻意在 pnpm 管辖之外。 */
        private const val PET_BRIDGE_DIR = "pet-bridge"

        /**
         * 无线调试（Android 11+）用的 adb 工具链。
         *
         * 取自 Termux 官方 `android-tools` 包的 `adb`（37.0.0），外加它 NEEDED 的
         * 全部 `.so`（`libprotobuf` / `libfmt` / `libabsl_*` / `libbrotli*` /
         * `libc++_shared` / `libz.so.1` / `liblz4` / `libzstd.so.1`）。
         * 理由与踩过的坑见 [ensureAdbWireless]。
         */
        private const val ADB_WIRELESS_ASSET = "adb-wireless-arm64.zip"
        private const val ADB_WIRELESS_DIR = "adbwire"

        /**
         * 无线调试 adb 工具链的**完整性哨兵**（落在 [ADB_WIRELESS_DIR] 内）。
         *
         * 只在 107 个条目全部写完之后才落盘，因此「哨兵存在」= 解包完整走完过。
         * 幂等判据必须带上它，否则一次中断留下的半成品会被永久认定为「已完成」——
         * 详见 `ensureAdbWireless()`。
         */
        private const val ADB_WIRELESS_STAMP = ".extracted"

        /** 特权身份说明写进这个文件 —— agent-instructions 会自动读它并注入系统提示词。 */
        private const val INSTRUCTIONS_FILE = "AGENTS.md"

        private const val PRIVILEGED_PROMPT_BEGIN = "<!-- dsh-android:privileged BEGIN（本段由客户端生成，勿手改）-->"
        private const val PRIVILEGED_PROMPT_END = "<!-- dsh-android:privileged END -->"

        private const val ROOT_PROMPT_BEGIN = "<!-- dsh-android:root BEGIN（本段由客户端生成，勿手改）-->"
        private const val ROOT_PROMPT_END = "<!-- dsh-android:root END -->"

        private const val RESCUE_PROMPT_BEGIN =
            "<!-- dsh-android:rescue BEGIN（本段由客户端生成，勿手改）-->"
        private const val RESCUE_PROMPT_END = "<!-- dsh-android:rescue END -->"

        /**
         * root 可用时写给模型的说明。
         *
         * 与 uid 2000 那段同样，重点在**边界**：什么时候用、什么时候别用、
         * 失败长什么样。root 的边界与 shell 不同 —— 它能读到 app 私有目录，
         * 所以「读工作区」不再是禁区；但**沙箱与 link 转译依然不生效**。
         */
        private val ROOT_PROMPT_ENABLED = """
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
        private val ROOT_PROMPT_DISABLED = """
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

        private val PRIVILEGED_PROMPT_ENABLED = """
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

        private val PRIVILEGED_PROMPT_DISABLED = """
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

        /**
         * 降级态下写给模型的说明（方案 §4.4 的 P4）。
         *
         * ## 为什么这段必须存在
         *
         * 整个方案的承诺是「App 一定打得开 + AI 帮你修」。前半段由 Kotlin 侧保证，
         * 后半段**只靠这段文本** —— 模型不知道 `rescue-cmd.json` 存在、不知道怎么
         * 验证改动，它就只会说「我建议你禁用那个插件」，然后用户还是打不开。
         *
         * ⚠️ 这段是**条件注入**的（只在降级态时写入），所以正文里可以放心说
         *   「你现在处于 X」。正常态下这段整个不存在，不占一个 token。
         *
         * ⚠️ 三条给模型的硬约束必须写清，否则它会做出有害的动作：
         *   ① 不许删插件（只能改名隔离）—— 用户可能还要它；
         *   ② 必须用 `try-boot` 验证，不能靠 `--dump-config` 猜（那是本方案的地基）；
         *   ③ **日志里的失败插件名不是元凶**（真机实测：引擎列 40 个，元凶不在里面）。
         */
        private val RESCUE_PROMPT = """
            ## 你正处于降级启动模式（插件把 App 搞崩了）

            上一轮启动时，用户装的第三方插件里有东西让引擎起不来。App 已经**自动
            降级**，用一个最小配置把你启动起来了 —— 所以你现在能用，但用户的插件
            配置处于降级状态。**用户很可能就是来找你修这个的。**

            ### 先看现状，不要猜

            用户在 App 顶部看到一条常驻横幅。你也可以自己查：

            - 当前层级、原因、被摘掉的插件：读 `${'$'}DSH_HOME/rescue/state.json`
            - 上次启动为什么失败：读 `${'$'}DSH_HOME/rescue/last-boot-failure.json`
            - 自动隔离时每一轮试启动的结果：`${'$'}DSH_HOME/rescue/attempts/`
            - 用户的原始插件清单备份：`profiles/web/package.json.bak-l1`

            想知道当前状态又不想读文件，可以发一条命令（见下）。

            ### ⚠️ 三条硬约束（违反会造成真实损害）

            1. **不许删除用户的插件。** 要排除某个插件，只允许**改名**（例如
               `package.json.bak-<时间>`），绝不允许 `rm`。用户可能还要它、可能
               只是版本不兼容、也可能是别的东西拖累了它。
            2. **任何「修好了」的结论都必须用 `try-boot` 真跑一次验证。**
               `--dump-config`、看配置文件、看日志**都不算证据** —— 真机上杀死
               引擎的异常是在插件 `apply()` 阶段抛的，而 `--dump-config` **不执行
               `apply()`**（实测它退出码 0、条目齐全，引擎照样起不来）。
            3. **日志里那份「失败插件」名单不是元凶名单。** 真机实测：引擎打印
               `Failed plugins (40)` 而实际只列了 38 条，且**真正的元凶不在里面** ——
               日志点名的是**受害者**（比如核心的 `typert-loader` 被某个第三方插件
               重复注册端点拖死）。所以不要拿日志里的名字当结论去禁用它们。

            ### 怎么验证你的修改（rescue 信箱）

            你不能重启 App（那会把自己杀掉），但可以让原生侧帮你**真跑一次**：

            1. 把命令写进 `${'$'}DSH_HOME/rescue-cmd.json`（`id` 必须是**整数**且递增）：

               ```json
               {"id": 1, "action": "try-boot"}
               ```

            2. 等 1~25 秒，读 `${'$'}DSH_HOME/rescue-result.json`：

               ```json
               {"id": 1, "ok": true, "message": "试启动成功：引擎打印了 URL（profile=web）",
                "data": {"ready": true, "exitCode": null, "requiredDidNotActivate": null,
                         "tail": ["...引擎输出尾部..."], "probesUsed": 1, "probeQuota": 20}}
               ```

               `data.ready == true` 是**唯一**可信的成功标志。

            #### 不想动用户的配置就验证？用 `bundleFilter`

            加一个 `bundleFilter` 数组，原生侧会**临时**只加载「核心 + 你列的那些」
            试启动，跑完**把用户配置逐字节还原**（你什么都不用清）：

            ```json
            {"id": 2, "action": "try-boot", "bundleFilter": ["@some/pkg-a", "@some/pkg-b"]}
            ```

            ⚠️ 这就是定位坏插件的正道：**二分**。40 个插件最多 6 轮就能定位到 1 个
            （`bundleFilter` 给一半，看能不能起来；能起来说明坏的在你**没**给的那半）。

            #### 其它动作

            | action | 作用 |
            |---|---|
            | `status` | 立刻返回当前层级、被隔离的插件、上次失败详情（不跑进程） |
            | `switch-rescue` | 切到救援模式（最小配置），界面重启 |
            | `switch-back` | **切回正常模式并重启界面** —— 你确信修好了就用它 |

            ⚠️ `try-boot` 有配额（一次会话 20 次），每次十几秒。用尽会明确告诉你，
            不要再试 —— 改用 `bundleFilter` 做更少的轮次，或者把结论交给用户。

            ### 修好之后

            1. 先 `try-boot`（不带 `bundleFilter`，用用户完整配置）确认 `ready: true`；
            2. 再发 `{"id": N, "action": "switch-back"}` 让 App 切回正常模式重启；
            3. 告诉用户你做了什么、动过哪个文件。

            ⚠️ 如果你判断**根本不是插件问题**（全摘之后仍起不来、或引擎报的错与插件
            无关），就**不要**乱动用户的配置 —— 直接如实告诉用户，并建议他点横幅上的
            「退出并重试」，或考虑 App 里的「安全模式」（那会重新解包引擎，能救
            「引擎目录被改坏」这类问题）。
        """.trimIndent()

        @Volatile
        private var instance: EngineManager? = null

        fun get(context: Context): EngineManager =
            instance ?: synchronized(this) {
                instance ?: EngineManager(context.applicationContext).also { instance = it }
            }
    }
}
