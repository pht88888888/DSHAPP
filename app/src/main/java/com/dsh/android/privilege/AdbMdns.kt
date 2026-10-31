package com.dsh.android.privilege
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.dsh.android.log.DshLog as Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import com.dsh.android.core.DshApplication

/**
 * 用系统的 `NsdManager`（mDNS/DNS-SD）发现本机 adbd 的**无线调试端口**。
 *
 * ## 为什么必须走系统服务，而不是自己发 mDNS 查询
 *
 * 无线调试的端口是**随机高位端口**，没法猜。按 AOSP 的设计，客户端应该通过 mDNS
 * 查询 `_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp` 拿到它。
 *
 * 但本 App 自己发 mDNS 是**不可能**的 —— 真机实测（OPPO PHY110 / Android 15）：
 *
 * ```
 * sock.send(q, 0, q.length, 5353, '224.0.0.251')  →  EPERM send EPERM 224.0.0.251:5353
 * ```
 *
 * `bind(5353)` 与 `addMembership('224.0.0.251')` 都能成功、单播也正常，
 * **只有往组播地址发会 EPERM** —— 这是 SELinux 对 `untrusted_app` 的限制，改不了。
 *
 * 而 `NsdManager` 是**系统服务**：请求经 Binder 交给 `mdnsd`（跑在系统域）代发，
 * 所以完全不受上面那条限制。Shizuku 走的正是这条路（`AdbMdns.kt`）。
 *
 * ⚠️ **别退回「扫端口」**：那是本模块最初的实现（扫 32768–60999，实测 20 秒），
 * 而无线调试端口每次重启/重开都会变，等于每条特权命令前都可能干等 20 秒。
 *
 * ## 端口有效性判据（借自 Shizuku）
 *
 * `NsdManager` 解析出的端口**不一定真在监听**（设备可能在切换状态、或缓存过期）。
 * Shizuku 的判据是「**尝试 bind 127.0.0.1:port，失败即说明 adbd 正占着它**」——
 * 比 connect 更轻，且不需要等超时。这里照用。
 *
 * ⚠️ 解析出的地址还必须是**本机地址**（`127.0.0.1` 或本机网卡地址），否则那是
 * 局域网里别的设备的 adbd。
 */
class AdbMdns(
    private val context: Context,
    private val serviceType: String,
    /** 解析到有效端口时回调。常驻监听靠它把端口写出去。 */
    private val onFound: (Int) -> Unit = {},
    /**
     * 之前解析到的那个服务**消失**时回调（典型场景：用户关掉无线调试开关）。
     *
     * 常驻监听靠它清掉过期的端口文件 —— 少了这一步，设置页会拿着一个早已
     * 没人监听的端口显示绿灯（真机实测撞到过：无线调试关着，界面却报
     * 「无线端口 35589」）。
     */
    private val onLost: () -> Unit = {},
) {

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    @Volatile
    private var running = false
    private var registered = false

    /**
     * 监听是否**仍然有效**（已启动，且没有被系统停掉）。
     *
     * 供常驻监听方做看门狗：`NsdManager` 在系统重启 mdnsd 等情况下会回调
     * `onStartDiscoveryFailed` / `onDiscoveryStopped` 把发现静默掐掉。
     * 一次性发现不在乎（下次调用会重建），但**常驻等待**必须能发现并重启它 ——
     * 否则用户按提示去开了无线调试，这边却早已不再监听，永远不会出现输入框。
     *
     * ⚠️ **不要写成 `running && registered`**：`registered` 要等
     * `onDiscoveryStarted` 回调才为 true，而那是异步的。刚 `start()` 完的那一瞬间
     * `running=true, registered=false`，看门狗会把它误判成「已停」并**反复重建**
     * 监听，形成死循环（真机踩过）。判据只用 [running] —— 它在 `start()` 里同步
     * 置 true，在两条「被停掉」的回调里置 false，语义正好。
     */
    val isRunning: Boolean
        get() = running

    /** 最近一次解析到并通过有效性校验的端口；-1 表示还没有。 */
    @Volatile
    var port: Int = -1
        private set

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            registered = true
            Log.i(DshApplication.TAG, "[mdns] 开始发现 $type")
        }

        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(DshApplication.TAG, "[mdns] 启动发现失败 $type error=$errorCode")
            running = false
        }

        override fun onDiscoveryStopped(type: String) {
            registered = false
            // ⚠️ 必须同时清掉 running：系统**主动**停掉发现时只会走这个回调
            //    （我们主动 stop() 时 running 本来就是 false，再置一次无害）。
            //    不清的话 [isRunning] 会一直报 true，常驻监听的看门狗就永远
            //    不会重建，用户开了无线调试也等不到输入框。
            running = false
        }

        override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(DshApplication.TAG, "[mdns] 停止发现失败 $type error=$errorCode")
            registered = false
            running = false
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            // 找到服务只是第一步，端口要 resolve 之后才有。
            //
            // ⚠️ **每次都新建一个 ResolveListener，不能复用同一个实例。**
            //    `NsdManager` 对同一个 listener **同一时刻只允许一次 resolve**，
            //    并发调用会以 `FAILURE_ALREADY_ACTIVE` 直接失败（只打一行日志、
            //    没有重试）。而本机的 `_adb-tls-connect._tcp` 会被系统**重复通告**
            //    （开关无线调试、切网络、adbd 重启时尤其明显），于是「第一次
            //    resolve 还没回、第二次 onServiceFound 就来了」是常态 ——
            //    复用 listener 会把第二次（往往才是拿到端口的那次）静默丢掉。
            //
            //    这正是一条**表现为「有的手机行、有的不行」**的缺陷：不同 ROM 的
            //    mdnsd 重复通告的频率不同，撞上的概率就不同。
            val resolver = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info2: NsdServiceInfo, errorCode: Int) {
                    Log.w(
                        DshApplication.TAG,
                        "[mdns] 解析失败 ${info2.serviceName} error=$errorCode（等待下一次通告重试）",
                    )
                }

                override fun onServiceResolved(info2: NsdServiceInfo) = acceptResolved(info2)
            }
            runCatching { nsdManager.resolveService(info, resolver) }
                .onFailure { Log.w(DshApplication.TAG, "[mdns] resolve 失败: ${it.message}") }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            Log.i(DshApplication.TAG, "[mdns] 服务消失 ${info.serviceName}")
            // 服务消失 = 端口大概率已经没了（无线调试被关掉时就是这个信号）。
            // 必须先清掉本地缓存的端口与落盘文件，否则设置页会拿着一个
            // 早已没人监听的端口显示绿灯。
            //
            // ⚠️ **但「有一个服务条目消失」不等于「端口没了」。** 本机的
            //    `_adb-tls-connect._tcp` 会被系统**重复通告**（开关无线调试、切网络、
            //    adbd 重启时尤其明显），`NsdManager` 于是为每个条目各发一次
            //    `onServiceLost`。若收到就无脑清，那么「两个条目、其中一个消失」这种
            //    完全正常的情况会把**仍然活着**的端口清掉 —— 紧接着的切 5555 拿不到
            //    端口、通道退回只靠无线调试，于是「关 Wi-Fi 就掉」。
            //    判据用**端口真的不在了**（bind 试一下），比「某个条目报丢了」扎实。
            if (port > 0 && isPortInUse(port)) {
                Log.i(
                    DshApplication.TAG,
                    "[mdns] 端口 $port 仍在监听，忽略这次服务消失（重复通告的旧条目）"
                )
                return
            }
            port = -1
            runCatching { onLost() }
                .onFailure { Log.w(DshApplication.TAG, "[mdns] onLost 回调失败: ${it.message}") }
        }
    }

    /**
     * 收下一个解析结果：校验地址是本机、端口真在监听，通过才回调出去。
     *
     * ⚠️ 抽成方法而不是复用同一个 `ResolveListener` 实例 —— 原因见
     * [listener] 的 `onServiceFound`（`NsdManager` 同一 listener 不许并发 resolve）。
     */
    private fun acceptResolved(info: NsdServiceInfo) {
        val candidate = info.port
        if (!isLocalAddress(info) || !isPortInUse(candidate)) {
            Log.i(
                DshApplication.TAG,
                "[mdns] 忽略 ${info.serviceName} → ${info.host?.hostAddress}:$candidate" +
                    "（非本机或端口无人监听）"
            )
            return
        }
        port = candidate
        Log.i(DshApplication.TAG, "[mdns] 解析到 $serviceType 端口 $candidate")
        runCatching { onFound(candidate) }
            .onFailure { Log.w(DshApplication.TAG, "[mdns] 回调失败: ${it.message}") }
    }

    /** 服务地址必须是本机地址，否则是局域网里别的设备的 adbd。 */
    private fun isLocalAddress(info: NsdServiceInfo): Boolean {
        val host = info.host?.hostAddress ?: return false
        if (host == "127.0.0.1" || host == "::1" || host == "localhost") return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.hostAddress == host }
        }.getOrDefault(false)
    }

    /**
     * 端口是否**已被占用**（= adbd 在监听）。
     *
     * ⚠️ 判据是反的：**bind 失败才算成功**。adbd 监听该端口时我们绑不上，
     * 于是「绑不上」正是「它在听」的证据。bind 成功说明没人监听，这个端口是假的。
     */
    private fun isPortInUse(p: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", p), 1)
            false
        }
    } catch (_: IOException) {
        true
    }

    fun start() {
        if (running) return
        running = true
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            running = false
            Log.w(DshApplication.TAG, "[mdns] discoverServices 抛错: ${it.message}")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    companion object {
        /** 已建立信任后的连接端口（TLS）。 */
        const val TLS_CONNECT = "_adb-tls-connect._tcp"

        /** 配对端口（仅在系统弹出「使用配对码配对设备」时存在）。 */
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"

        /**
         * 发现一个端口，最多等 [timeoutMs]。
         *
         * 每次新建一个实例：`NsdManager` 的同一个 listener 不能重复注册，
         * 而复用实例会踩到「上一次的 discovery 还没停干净」的竞态。
         *
         * @return 端口号；超时或未发现给 -1。
         */
        fun discover(context: Context, serviceType: String, timeoutMs: Long): Int {
            val mdns = AdbMdns(context, serviceType)
            mdns.start()
            // 轮询而不是用 latch 等一次回调：解析是异步的，且可能先失败后成功
            // （服务刚广播出来时 resolve 常失败一次）。只等第一个回调会把这种情况
            // 误判成「没发现」。
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (mdns.port > 0) return mdns.port
                    Thread.sleep(200)
                }
                return mdns.port
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return mdns.port
            } finally {
                mdns.stop()
            }
        }
    }
}
