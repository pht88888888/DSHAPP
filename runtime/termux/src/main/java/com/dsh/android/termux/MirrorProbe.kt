package com.dsh.android.termux

import com.dsh.android.termux.util.AtomicTextFile
import java.io.File

/**
 * 国内镜像候选表 + 测速择优 + 各包管理器配置生成。
 *
 * ## ⚠️ 「测速择优」这半条链路目前**没有接线**（2026-10-14 核实）
 *
 * 本文件里解析探测输出、算最快站的那套纯逻辑是完整的，但**没有任何调用者**：
 * `TermuxBootstrap.probeMirrors()`（唯一会执行 `probeScript()` 的地方）从初始提交
 * `e253c7c` 起就从未被调用过，全仓库搜不到调用点。
 *
 * **所以真机上生效的永远是 [defaults] 那张写死的表**（下面这张实测表里各生态的冠军），
 * 不是「每次实测出来的最快站」。读这段 KDoc 时不要以为镜像会自动择优 ——
 * 排查「某生态慢」时这一点是前提。
 *
 * 要接上这条链路属于**功能决策**（多久跑一次、首次启动要不要等），不是缺陷修复。
 *
 * ## 为什么需要「测速择优」而不是写死一个源
 *
 * 2026-09-27 真机实测（Termux 内 `curl -w '%{http_code} %{speed_download}'`）：
 *
 * | 生态 | 最快 | 反面 |
 * |---|---|---|
 * | PyPI | aliyun 1.57 MB/s | 🔴 **pypi.org 完全不可达（code=000）** |
 * | APT  | ustc 47.5 KB/s | 🔴 **aliyun 是 404**（它有 PyPI 但没 Termux 仓） |
 * | NPM  | npmmirror 1.13 MB/s | npmjs.org 397 KB/s |
 * | Go   | goproxy.cn 最快 | proxy.golang.org 不可达（000） |
 *
 * ⚠️ **「同一个站点在不同生态上表现相反」是这张表最重要的事实**：aliyun 是 PyPI 冠军
 * 但 APT 直接 404。任何「写死一个万能源」的方案都会在某个生态上静默变慢或直接失败，
 * 所以候选表必须**按生态分别维护、分别探测**。
 *
 * ## 分层
 *
 * - 本文件是**纯逻辑**（除 `java.io.File` 外不依赖任何 Android 框架），因此可被 JVM
 *   单测直接覆盖：候选表、测速输出解析、各配置文件的生成都是纯函数。
 * - 落盘与调度在 [TermuxBootstrap]；环境变量注入在 `BooBridge`。
 *
 * ## 为什么同时写「配置文件」和「环境变量」
 *
 * 配置文件（pip.conf / .npmrc / go env / .cargo / sources.list）让工具**在任何 shell 里**
 * 都默认走快源 —— 这是「AI 不需要记得」的那一层。环境变量额外覆盖两种漏网：工具无视
 * 配置文件、或调用时 HOME 与预期不符。而 `env` 里能直接看到 `PIP_INDEX_URL`，
 * 模型自己就能**发现**当前走的是哪个源，不必靠提示词告知。
 */
object MirrorProbe {

    const val ECO_PYPI = "pypi"
    const val ECO_APT = "apt"
    const val ECO_NPM = "npm"
    const val ECO_GO = "go"
    const val ECO_CARGO = "cargo"

    /** 探测/落盘涉及的全部生态，顺序即配置文件的书写顺序。 */
    val ECOS: List<String> = listOf(ECO_PYPI, ECO_APT, ECO_NPM, ECO_GO, ECO_CARGO)

    /** 持久化状态文件名（相对 `BOO_HOME`）。 */
    const val STATE_FILE = "mirrors.conf"

    /** 探测脚本落盘的名字（装进 `$PREFIX/bin`）。 */
    const val PROBE_CMD = "boo-mirror"

    /**
     * 单个候选镜像。
     *
     * [url] 是**要写进配置的值**（如 pip 的 `index-url`），[probePath] 是挂在它后面用于
     * 测速的路径。两者必须分开：测速要打一个真实存在、且大小可观的资源才能反映吞吐
     * （实测 `/simple/numpy/` 页面 1.32 MB，比打根路径可靠得多）。
     */
    data class Candidate(
        val eco: String,
        val name: String,
        val url: String,
        val probePath: String,
    ) {
        val probeUrl: String get() = url + probePath
    }

    /**
     * 候选表。**每个生态的第一项就是 [defaults] 采用的默认值**，所以这里的顺序是有
     * 意义的：按 2026-09-27 实测速度降序排列，最快的在前。
     *
     * ⚠️ 不要在表里放已实测不可达的站点（`pypi.org` / `proxy.golang.org`）：探测脚本是
     * 真发 HTTP 请求的，放进去只会让每次探测白等一个超时。
     */
    val CANDIDATES: List<Candidate> = listOf(
        // PyPI —— 实测 1.57 MB/s
        Candidate(ECO_PYPI, "aliyun", "https://mirrors.aliyun.com/pypi/simple", "/numpy/"),
        Candidate(ECO_PYPI, "tuna", "https://pypi.tuna.tsinghua.edu.cn/simple", "/numpy/"),
        Candidate(ECO_PYPI, "ustc", "https://mirrors.ustc.edu.cn/pypi/simple", "/numpy/"),
        Candidate(ECO_PYPI, "bfsu", "https://mirrors.bfsu.edu.cn/pypi/web/simple", "/numpy/"),
        Candidate(ECO_PYPI, "tencent", "https://mirrors.cloud.tencent.com/pypi/simple", "/numpy/"),
        Candidate(ECO_PYPI, "huawei", "https://repo.huaweicloud.com/repository/pypi/simple", "/numpy/"),
        // Termux APT —— 实测 ustc 47.5 KB/s 最快；aliyun 在此生态是 404，故不入表
        Candidate(ECO_APT, "ustc", "https://mirrors.ustc.edu.cn/termux/apt/termux-main", "/dists/stable/InRelease"),
        Candidate(ECO_APT, "tuna", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main", "/dists/stable/InRelease"),
        Candidate(ECO_APT, "bfsu", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main", "/dists/stable/InRelease"),
        Candidate(ECO_APT, "official-cf", "https://packages-cf.termux.dev/apt/termux-main", "/dists/stable/InRelease"),
        // NPM —— 实测 npmmirror 1.13 MB/s
        Candidate(ECO_NPM, "npmmirror", "https://registry.npmmirror.com", "/express"),
        Candidate(ECO_NPM, "tencent", "https://mirrors.cloud.tencent.com/npm", "/express"),
        Candidate(ECO_NPM, "huawei", "https://mirrors.huaweicloud.com/repository/npm", "/express"),
        Candidate(ECO_NPM, "npmjs", "https://registry.npmjs.org", "/express"),
        // Go module proxy —— 实测 goproxy.cn 最快
        Candidate(ECO_GO, "goproxy.cn", "https://goproxy.cn", "/github.com/pkg/errors/@v/list"),
        Candidate(ECO_GO, "goproxy.io", "https://goproxy.io", "/github.com/pkg/errors/@v/list"),
        Candidate(ECO_GO, "aliyun", "https://mirrors.aliyun.com/goproxy", "/github.com/pkg/errors/@v/list"),
        // Cargo sparse registry
        Candidate(ECO_CARGO, "tuna", "https://mirrors.tuna.tsinghua.edu.cn/crates.io-index", "/config.json"),
        Candidate(ECO_CARGO, "ustc", "https://mirrors.ustc.edu.cn/crates.io-index", "/config.json"),
    )

    /**
     * 每个生态的默认源（候选表里各生态的第一项）。
     *
     * 用于**同步**写入：冷启动第一秒就把环境指向可用快源，**绝不为了测速阻塞启动**。
     * 择优结果由后台探测稍后覆盖。
     */
    fun defaults(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (c in CANDIDATES) if (!out.containsKey(c.eco)) out[c.eco] = c.url
        return out
    }

    /**
     * 在默认值之上叠加探测结果；探测没覆盖到的生态保留默认。
     *
     * 同样只接受已知键：`KEY_APT_WRITTEN` 必须原样带过（[TermuxBootstrap.writeSourcesList]
     * 靠它判断 sources.list 是不是用户改的），但除此之外的键一律丢弃。
     */
    fun merge(probed: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(defaults())
        for ((eco, url) in probed) {
            if (eco != KEY_APT_WRITTEN && eco !in ECOS) continue
            if (url.isNotEmpty()) out[eco] = url
        }
        return out
    }

    // ── 探测脚本 ────────────────────────────────────────────────────────────

    /**
     * 生成探测脚本正文。
     *
     * 三个刻意的设计：
     *  1. **并行**：19 个候选若串行、每个 8 秒超时，最坏要 2.5 分钟。后台 `&` + `wait`
     *     把总时长压到约等于最慢的那一个。
     *  2. **只依赖 `/system/bin/sh` + prefix 里的 `curl`**：不依赖 prefix 里除 curl 外的
     *     任何东西 —— 探测的意义恰恰是在「源不可用」时还能跑起来。
     *  3. **自己找出 prefix**：🔴 不要把 `PREFIX`/`PATH` 当成调用方一定给好的输入。实测
     *     `run-as … sh 脚本` 直接跑（无 app shell env）时 `curl` 找不到，脚本**不报错**，
     *     只是每个候选都吐出空字段 —— 看起来「探测跑过了但一个都没测到」。
     *     脚本自己就装在 `<prefix>/bin/boo-mirror`，所以从 `$0` 反推 prefix 最可靠，
     *     而且不需要把包名硬编码进来。
     *
     * 输出制表符分隔的 `eco<TAB>url<TAB>code<TAB>speed`，由 [parseProbeOutput] 解析。
     * 写文件再 `cat` 是为了避免 `while` 落在管道子 shell 里导致 `wait` 等不到后台任务。
     *
     * （正文用 `§` 占位 `$` 再替换 —— 直接在 Kotlin 里转义几十处 `$` 极易写错，
     * 而写错的表现恰好是「静默输出空字段」，正是上面第 3 条那个坑。）
     */
    fun probeScript(): String {
        val body = """
            |#!/system/bin/sh
            |# boo-mirror —— 探测国内镜像测速，挑最快。由 Boo 自动生成，可重复执行。
            |# 输出：eco<TAB>url<TAB>http_code<TAB>speed_bytes_per_sec
            |
            |# 自己定位 prefix：本脚本就在 §PREFIX/bin/ 下。
            |if [ -z "§PREFIX" ]; then
            |  case "§0" in
            |    */*) PREFIX=§(cd "§(dirname "§0")/.." 2>/dev/null && pwd);;
            |  esac
            |fi
            |[ -n "§PREFIX" ] && PATH="§PREFIX/bin:§PATH"
            |PATH="§PATH:/system/bin"
            |export PATH PREFIX
            |
            |# 🔴 TMPDIR 必须早于第一个 heredoc：mksh 的 `<<EOF` 会先落一个临时文件，而它用的
            |# 正是 TMPDIR。未设时回落到 /data/local（不可写）⇒ 报 "can't create temporary
            |# file /data/local/shXXXX.tmp: Permission denied"，脚本在第 39 行就死了。
            |[ -n "§TMPDIR" ] || TMPDIR="§PREFIX/tmp"
            |mkdir -p "§TMPDIR" 2>/dev/null
            |export TMPDIR
            |
            |D="§{TMPDIR:-§PREFIX/tmp}/.boo-mirror.§§"
            |mkdir -p "§D" 2>/dev/null || { D="/data/local/tmp/.boo-mirror.§§"; mkdir -p "§D"; }
            |mkdir -p "§D" || exit 1
            |
            |cat > "§D/c" <<'BOOEOF'
            |§CANDIDATES
            |BOOEOF
            |
            |i=0
            |while IFS='	' read -r eco url; do
            |  [ -n "§eco" ] || continue
            |  [ -n "§url" ] || continue
            |  (
            |    r=§(curl -sL --max-time 8 -o /dev/null -w '%{http_code} %{speed_download}' "§url" 2>/dev/null)
            |    set -- §r
            |    printf '%s\t%s\t%s\t%s\n' "§eco" "§url" "§1" "§2" > "§D/r§i"
            |  ) &
            |  i=§((i+1))
            |done < "§D/c"
            |wait
            |cat "§D"/r* 2>/dev/null
            |rm -rf "§D"
            |""".trimMargin()
        val candidates = CANDIDATES.joinToString("\n") { "${it.eco}\t${it.probeUrl}" }
        return body.replace("§CANDIDATES", candidates).replace('§', '$')
    }

    /** `probeUrl → 基址`。用于把探测结果还原成「该写进配置的那个 URL」。 */
    private val baseByProbeUrl: Map<String, String> =
        CANDIDATES.associate { it.probeUrl to it.url }

    /**
     * 把任意来源的 URL 规范成**该写进配置的基址**；无法识别则返回 null。
     *
     * 🔴 **为什么必须过这一道**：探测请求打的是 `probeUrl`（= 基址 + 一个真实、够大的资源
     * 路径，`/simple/numpy/`，否则 curl 量不到有意义的吞吐），而**写进配置的必须是基址**。
     * 实测踩过：直接把探测结果落盘 ⇒ `index-url = https://…/pypi/simple/numpy/`，
     * pip 把它当索引根去拼 `…/numpy/six/` ⇒ 装什么都是 404。
     *
     * 同时承担**自愈**职责：旧版本写坏的 `mirrors.conf` 里存的正是 `probeUrl`，
     * 这里识别出来还原成基址，于是升级一次就修好，不需要用户手动删状态文件。
     */
    fun canonicalUrl(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        baseByProbeUrl[value]?.let { return it }
        if (CANDIDATES.any { it.url == value }) return value
        return null
    }

    /**
     * 解析 [probeScript] 的输出，返回 `eco → 最快 url`（**基址**，非 probeUrl）。
     *
     * 只接受 `code == 200` 且 `speed > 0` 的行 —— **可达性优先于速度**：一个 000
     * （连不上）的源即使 `speed_download` 报了个数也不能选。没有任何 200 的生态不出现在
     * 结果里，由 [merge] 保留默认值，绝不会出现「探测失败 ⇒ 没有源可用」。
     */
    fun parseProbeOutput(raw: String): Map<String, String> {
        val best = HashMap<String, Double>()
        val chosen = LinkedHashMap<String, String>()
        for (line in raw.split('\n')) {
            val f = line.split('\t')
            if (f.size < 4) continue
            val eco = f[0].trim()
            // 还原成基址；识别不出来就别写进配置（宁可保留默认值）。
            val url = canonicalUrl(f[1].trim()) ?: continue
            if (eco.isEmpty()) continue
            if (f[2].trim() != "200") continue
            val speed = f[3].trim().toDoubleOrNull() ?: continue
            if (speed <= 0.0) continue
            val prev = best[eco]
            if (prev == null || speed > prev) {
                best[eco] = speed
                chosen[eco] = url
            }
        }
        return chosen
    }

    // ── 配置文件生成 ────────────────────────────────────────────────────────

    /** 从 URL 取 host，用于 pip 的 `trusted-host`。 */
    fun hostOf(url: String): String {
        val s = url.indexOf("://")
        val rest = if (s >= 0) url.substring(s + 3) else url
        val slash = rest.indexOf('/')
        return if (slash >= 0) rest.substring(0, slash) else rest
    }

    private fun pipConf(url: String): String =
        "[global]\n" +
            "index-url = $url\n" +
            "trusted-host = ${hostOf(url)}\n" +
            "disable-pip-version-check = true\n"

    private fun npmrc(url: String): String = "registry=$url\n"

    private fun cargoConf(url: String): String {
        // 稀疏索引必须带 `sparse+` 前缀和结尾斜杠，否则 cargo 会当 git 索引去 clone。
        val sparse = if (url.endsWith("/")) url else "$url/"
        return "[source.crates-io]\n" +
            "replace-with = \"boo-mirror\"\n" +
            "\n" +
            "[source.boo-mirror]\n" +
            "registry = \"sparse+$sparse\"\n"
    }

    private fun goEnv(url: String): String =
        "GOPROXY=$url,direct\n" +
            // sum.golang.org 在境内不可达，会让每个模块校验都超时；用官方镜像的
            // 校验库。⚠️ 不改成 `off` —— 那等于关掉校验和验证，是安全降级。
            "GOSUMDB=sum.golang.google.cn\n"

    private fun aptSources(url: String): String = "deb $url/ stable main\n"

    /**
     * 上游 Termux 官方仓库的主机名。
     *
     * 🔴 **为什么单独识别它**：`$PREFIX/etc/apt/sources.list` 是 **apt 包自己的
     * conffile**（真机实测 `var/lib/dpkg/info/apt.conffiles` 唯一一行就是它）。
     * 首次安装 apt 包时该文件还不存在，dpkg 于是直接落**包内自带**的官方源 ——
     * 实测是 `https://grimler.se/termux/termux-main`。
     *
     * 而 [isUserCustomizedApt] 的判据是「内容是否与我上次写的一致」，包覆写必然不一致
     * ⇒ 被判成「用户自己改过」⇒ **永久拒绝修复**，日志还把这次覆写说成「保留用户选择」。
     * 实测后果：`mirrors.conf` 里选着 50 KB/s 的清华源，`sources.list` 却一直走
     * 11 KB/s 的 grimler.se，一次 96 MB 的下载花了 15 分钟。
     *
     * ⚠️ 同一份 bootstrap zip 里带的是 `packages-cf.termux.dev`，与 apt 包里的
     * grimler.se **不是同一个地址**，所以两个都得在表里，只认一个仍会漏。
     */
    private val OFFICIAL_APT_HOSTS: Set<String> = setOf(
        "grimler.se",
        "packages.termux.dev",
        "packages-cf.termux.dev",
        "termux.dev",
    )

    /** 候选表里 apt 生态的国内源主机，从 [CANDIDATES] 推导，避免手抄漂移。 */
    private val CANDIDATE_APT_HOSTS: Set<String> =
        CANDIDATES.filter { it.eco == ECO_APT }.map { hostOf(it.url) }.toSet()

    /** 取出 `deb [opts] <url> <suite> <comps>` 里的主机名；不是 deb 行则返回 null。 */
    private fun aptHostOfLine(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("deb ") && !t.startsWith("deb\t")) return null
        val fields = t.split(' ', '\t').filter { it.isNotEmpty() }
        // 跳过 `[arch=… trusted=yes]` 这类选项段，第一个带协议头的字段才是地址。
        val url = fields.drop(1).firstOrNull { it.contains("://") } ?: return null
        return hostOf(url)
    }

    /**
     * 判断 `sources.list` 的内容**只可能**来自「上游包自带」或「我们自己写的」。
     *
     * 这两类都**不构成用户的选择**：用户真要换源，会写一个候选表以外的地址
     * （`pkg` 的 `termux-change-repo` 给的就是第三方站，如 termux.danyael.xyz）。
     * 因此这份白名单能把 [isUserCustomizedApt] 从「包覆写 ⇒ 永久锁死」里救出来。
     */
    fun isBooOwnedApt(current: String?, upstreamMirrorHosts: Set<String> = emptySet()): Boolean {
        if (current == null) return false
        val hosts = current.split('\n').mapNotNull { aptHostOfLine(it) }
        return hosts.isNotEmpty() &&
            hosts.all {
                it in OFFICIAL_APT_HOSTS || it in CANDIDATE_APT_HOSTS || it in upstreamMirrorHosts
            }
    }

    /**
     * 扫出 Termux 上游镜像池里**所有**主机名，供 [isBooOwnedApt] 判定用。
     *
     * 🔴 为什么需要它：`pkg` 的 `select_mirror()` 会从 `$PREFIX/etc/termux/mirrors/`
     * 下的全部镜像文件里随机抽一个写进 `sources.list`（见 [shellEnv] 的说明）。
     * 池子里 60+ 个站点绝大部分是我们候选表里没有的海外站，若不识别，一次自动换源
     * 就会被 [isUserCustomizedApt] 判成「用户自己改的」⇒ 那份慢源**永远修不回来**。
     *
     * ⚠️ **动态扫设备而不是硬编码清单**：这份池子是 Termux 上游随 bootstrap 分发的，
     * 会随版本增删（实测 bootstrap zip 里 60 个文件）。硬编码一份必然漂移，而漂移的
     * 表现是「新版本里被抽中的源又变成不可修复」—— 正是本函数要根除的故障模式。
     *
     * ⚠️ 目录可能不存在（老装机 / 被裁剪的精简 bootstrap）⇒ 返回空集，行为退化为
     * 「只认官方源与候选表」，不会误伤。
     */
    fun upstreamMirrorHosts(mirrorsDir: File): Set<String> {
        val out = LinkedHashSet<String>()
        for ((url, _) in mirrorPool(mirrorsDir)) {
            hostOf(url).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    /**
     * 扫出 Termux 镜像池里 `MAIN 地址 → 镜像文件` 的映射。
     *
     * 镜像文件是被 shell `source` 的片段，形如 `MAIN="https://host/path"`。只取 `MAIN`
     * 一个通道：`ROOT`/`X11` 是可选仓，同一站点的 `MAIN` 必然也在，取它不会漏站点。
     *
     * ⚠️ 池子是 Termux 上游随 bootstrap 分发的，会随版本增删（实测 zip 里 60 个文件）。
     * 所以必须**动态扫设备**而不是硬编码清单 —— 硬编码必然漂移，而漂移的表现是
     * 「新版本里被抽中的源又变成不可修复」，正是 [upstreamMirrorHosts] 要根除的故障。
     *
     * 目录不存在（老装机 / 精简 bootstrap）⇒ 返回空表，判定退化为「只认官方源与候选表」，
     * 不会误伤。
     */
    fun mirrorPool(mirrorsDir: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val files = try {
            mirrorsDir.walkTopDown().filter { it.isFile }.toList()
        } catch (_: Exception) {
            return out
        }
        for (f in files) {
            val text = try {
                f.readText()
            } catch (_: Exception) {
                continue
            }
            for (line in text.split('\n')) {
                val t = line.trim()
                if (!t.startsWith("MAIN=")) continue
                val url = t.substringAfter('=', "").trim().trim('"', '\'')
                if (url.contains("://")) out.putIfAbsent(url, f)
            }
        }
        return out
    }

    /**
     * 需要落盘的全部配置文件：`文件 → 内容`。
     *
     * 🔴 **同一个工具要写多个位置，这不是冗余而是必需**（真机踩过）：Termux 给系统 pip
     * 打了补丁让它读 `$PREFIX/etc/pip.conf`，而 venv / 标准 pip 读 `~/.config/pip/pip.conf`。
     * 实测只写前者时，venv 里 `pip config list` 是空的，装包直接
     * `ConnectionResetError(104)` —— 用户以为配好了，其实只有一半生效。
     *
     * 同理 `~` 有两个候选：引擎给 shell 的 `HOME` 是 `$PREFIX/../home`，而 `BOO_HOME`
     * （以及模型自己 `cd` 到的工作目录直觉）是 dataDir。两边都写，避免「换个 shell 就失效」。
     */
    fun configFiles(
        prefixDir: File,
        homeDir: File,
        dataDir: File,
        mirrors: Map<String, String>,
    ): Map<File, String> {
        val out = LinkedHashMap<File, String>()
        val pypi = mirrors[ECO_PYPI]
        val npm = mirrors[ECO_NPM]
        val cargo = mirrors[ECO_CARGO]
        val go = mirrors[ECO_GO]

        if (!pypi.isNullOrEmpty()) {
            val body = pipConf(pypi)
            out[File(prefixDir, "etc/pip.conf")] = body
            out[File(homeDir, ".config/pip/pip.conf")] = body
            out[File(dataDir, ".config/pip/pip.conf")] = body
        }
        if (!npm.isNullOrEmpty()) {
            val body = npmrc(npm)
            out[File(homeDir, ".npmrc")] = body
            out[File(dataDir, ".npmrc")] = body
        }
        if (!cargo.isNullOrEmpty()) {
            val body = cargoConf(cargo)
            out[File(homeDir, ".cargo/config.toml")] = body
            out[File(dataDir, ".cargo/config.toml")] = body
        }
        if (!go.isNullOrEmpty()) {
            val body = goEnv(go)
            out[File(homeDir, ".config/go/env")] = body
            out[File(dataDir, ".config/go/env")] = body
        }
        return out
    }

    /** apt 的 `sources.list` 内容。单独列出：它的写入受「用户是否自己改过」约束。 */
    fun aptSourcesList(mirrors: Map<String, String>): String? =
        mirrors[ECO_APT]?.takeIf { it.isNotEmpty() }?.let { aptSources(it) }

    /**
     * 注入给 shell 子进程的镜像环境变量。
     *
     * ⚠️ 覆盖面小于配置文件（cargo 没有等价的单个环境变量），是**补充**不是替代。
     * 价值在于：`env | grep -i proxy` 就能看出当前走哪个源，模型不必读配置文件去猜。
     */
    fun shellEnv(mirrors: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // 🔴🔴 必须是**无条件**的第一项，与 mirrors 表是否读得到无关。
        //
        // Termux 上游的 `pkg` 脚本（`$PREFIX/bin/pkg`，`select_mirror()`）在每次
        // `pkg install` / `pkg update` 时都会**自己重挑镜像并覆写 sources.list**：
        //
        //     echo "deb $MAIN stable main" > $PREFIX/etc/apt/sources.list
        //
        // 而它挑的方式**不是挑最快的** —— 是「全都并行 ping 一遍，活下来的按 WEIGHT
        // 加权随机抽一个」。bootstrap 没带 `chosen_mirrors`，于是候选池就是**全部
        // 60+ 个镜像**（default + asia + chinese_mainland + europe + north_america +
        // oceania + russia）。
        //
        // 实测后果（2026-09-27，真机 `pkg install python`）：抽中了越南
        // `mirrors.nguyenhoang.cloud`（84 KB/s），而候选表里的 bfsu 实测 1.07 MB/s ——
        // 慢 13 倍。`libllvm` 解包后 169 MB，84 KB/s 下光这一个包就要 ~10 分钟，
        // 用户看到的就是「装了十分钟还没完」。我们写好的国内源被静默冲掉，
        // 而且 `isUserCustomizedApt` 会把这份覆写误认成「用户自己的选择」，**永久拒绝修复**。
        //
        // `TERMUX_PKG_NO_MIRROR_SELECT` 是**上游自己的开关**（`pkg:184`）：
        //     [ -n "${TERMUX_PKG_NO_MIRROR_SELECT-}" ] && [ -n "$current_mirror" ] && return
        // 只要求 sources.list 里已有一个地址就原样保留。用它比打补丁改 `pkg` 脚本干净 ——
        // 后者会在每次 bootstrap 升级时被覆盖，且属于改动上游分发物。
        //
        // ⚠️ 值非空即可（pkg 只做 `-n` 判断）；`current_mirror` 为空时它仍会换源，
        // 所以这条**依赖 bootstrap 已经写过 sources.list**，两条路要一起成立。
        out["TERMUX_PKG_NO_MIRROR_SELECT"] = "1"
        mirrors[ECO_PYPI]?.takeIf { it.isNotEmpty() }?.let {
            out["PIP_INDEX_URL"] = it
            out["PIP_TRUSTED_HOST"] = hostOf(it)
        }
        mirrors[ECO_NPM]?.takeIf { it.isNotEmpty() }?.let { out["npm_config_registry"] = it }
        mirrors[ECO_GO]?.takeIf { it.isNotEmpty() }?.let { out["GOPROXY"] = "$it,direct" }
        return out
    }

    // ── 状态持久化 ──────────────────────────────────────────────────────────

    /**
     * 上次由本 App 写进 `sources.list` 的 apt 源地址，用于判断「用户是否自己换过源」。
     *
     * 存 URL 而不是整份文件内容：apt 行格式由 [aptSources] 唯一决定，有 URL 就能还原。
     * 这样状态文件是纯 `key=value`，不需要 base64 —— 本文件因此**完全不依赖 Android
     * 框架**，可以直接被 JVM 单测覆盖。
     */
    const val KEY_APT_WRITTEN = "apt_written"

    /** 序列化探测结果（纯 `key=value`，值里不含空格）。 */
    fun encodeState(mirrors: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("# Boo 自动生成：镜像选择结果。删掉本文件会在下次启动时重新探测。\n")
        for (eco in ECOS) {
            val url = mirrors[eco]
            if (!url.isNullOrEmpty()) sb.append(eco).append('=').append(url).append('\n')
        }
        val apt = mirrors[KEY_APT_WRITTEN]
        if (!apt.isNullOrEmpty()) sb.append(KEY_APT_WRITTEN).append('=').append(apt).append('\n')
        return sb.toString()
    }

    /**
     * 读回 [encodeState] 的结果。文件不存在或损坏时返回空表（调用方回退默认值）。
     *
     * ⚠️ **只接受已知键**：状态文件是磁盘上的普通文本，可能被截断、手改、或写入时被
     * 强杀留下半行。若照单全收任意 `key=value`，一个坏文件就能往镜像表里塞进垃圾键，
     * 并随 `merge` → `encodeState` 一路传播下去。白名单让「损坏」退化成「忽略该行」，
     * 最坏情况就是回退到候选表默认值 —— 这正是我们要的行为。
     *
     * ⚠️ 值还要过 [canonicalUrl]：老版本曾把 **probeUrl**（带 `/simple/numpy/` 这种探测
     * 路径）写进配置，pip 会拿它当索引根去拼 `…/numpy/six/` ⇒ 装什么都是 404。
     * 在这里还原成基址，于是**升级一次就自愈**，不要求用户删状态文件。
     * `apt_written` 存的是我们上次写进 sources.list 的**完整 url**，同样要能识别。
     */
    fun decodeState(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val k = t.substring(0, eq).trim()
            val raw = t.substring(eq + 1).trim()
            if (k != KEY_APT_WRITTEN && k !in ECOS) continue
            val v = canonicalUrl(raw) ?: continue
            out[k] = v
        }
        return out
    }

    /**
     * 判断 `sources.list` 的当前内容是否仍是我们写的。
     *
     * 🔴 这条判断是**不覆盖用户选择**的关键：`pkg` 或用户手动 `pkg install` 时会调用
     * `termux-change-repo` 重写 sources.list。若我们无条件覆盖，用户刚挑好的源会在下次
     * 冷启动被静默改回，且没有任何提示 —— 比不管更糟。
     *
     * 判定规则（宽松，倾向「是用户的」）：
     *  - 文件不存在 → 我们写（`false`）
     *  - 内容与我们上次写的一致 → 我们写（`false`）
     *  - 内容是空/只有注释 → 我们写（`false`）
     *  - 内容指向的都是**上游官方源或候选表里的源** → 不是用户的选择（`false`），
     *    见 [isBooOwnedApt]：apt 包自己的 conffile 被 dpkg 落盘时会覆写成官方源，
     *    这不是「用户改过」，把它当用户选择会让换源**永久失效**
     *  - 其他任何情况 → 视为用户改过，不动（`true`）
     */
    fun isUserCustomizedApt(
        current: String?,
        lastWrittenUrl: String?,
        upstreamMirrorHosts: Set<String> = emptySet(),
    ): Boolean {
        if (current == null) return false
        val meaningful = current.split('\n').any { l ->
            val t = l.trim()
            t.isNotEmpty() && !t.startsWith("#")
        }
        if (!meaningful) return false
        if (!lastWrittenUrl.isNullOrEmpty() &&
            current.trim() == aptSources(lastWrittenUrl).trim()
        ) {
            return false
        }
        return !isBooOwnedApt(current, upstreamMirrorHosts)
    }

    /**
     * 读取状态文件并合并到默认值；任何异常都回退默认值，绝不抛。
     *
     * 顺带**自愈**：老版本写坏的 `mirrors.conf`（值里带 probeUrl 的探测路径）在
     * [decodeState] 里已被还原成基址，这里把规范化后的内容写回，让磁盘上的文本也干净 ——
     * 否则每次读都要重新纠正一遍，且用户 `cat` 看到的是错的，排查时会误判。
     * 仅在内容真的不同时才写，正常运行零开销。
     */
    fun loadOrDefaults(state: File): Map<String, String> {
        val raw = try {
            if (state.isFile) state.readText() else return defaults()
        } catch (_: Exception) {
            return defaults()
        }
        val merged = try {
            merge(decodeState(raw))
        } catch (_: Exception) {
            defaults()
        }
        // ⚠️ 用 AtomicTextFile 而不是 `writeText`：本函数被 `BooBridge` 的
        // **每条 shell 命令**间接调用（组装 baseEnv），而 `writeText` 会先 TRUNCATE。
        // 万一此刻进程被杀，状态文件就空了 —— 下次启动会当作「无历史」重新探测。
        // 原子写让自愈变成全有或全无，最坏也只是没自愈（读路径已规范化，行为不变）。
        try {
            val fixed = encodeState(withAptWritten(merged))
            if (state.isFile && fixed != raw) AtomicTextFile.write(state, fixed)
        } catch (_: Exception) {
            // 自愈失败不影响返回值：内存里的表已经是对的。
        }
        return merged
    }

    /** 把 `apt_written` 一并编进状态（[encodeState] 只写显式给出的那一个键）。 */
    private fun withAptWritten(mirrors: Map<String, String>): Map<String, String> {
        if (mirrors.containsKey(KEY_APT_WRITTEN)) return mirrors
        val out = LinkedHashMap(mirrors)
        mirrors[ECO_APT]?.let { out[KEY_APT_WRITTEN] = it }
        return out
    }

    // ── 给模型的说明（设备侧 skill）────────────────────────────────────────

    /**
     * 设备侧 skill 的正文。
     *
     * ⚠️ 为什么不做成「内置 skill」：`app/aar-source/oracle/golden/skillbuiltins.jsonl`
     * 逐字钉住了 7 个内置 skill 的名字与 `body_len`，新增一个内置 skill 会直接打破差分
     * （铁律⑥）。设备侧 skill（`dataDir/skills/<name>/SKILL.md`）不在金标里，是干净的路。
     */
    fun skillMarkdown(mirrors: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: fast-mirrors\n")
        sb.append("description: 国内镜像源速查与重探。装依赖慢、超时、连不上（pip/npm/go/cargo/apt/pkg）时读它。")
        sb.append("触发词：装依赖、安装包、下载慢、超时、连不上、换源、镜像源、pip、npm、pnpm、yarn、go get、cargo、apt、pkg install。\n")
        sb.append("---\n\n")
        sb.append("# 依赖下载慢 / 超时 / 连不上时先看这里\n\n")
        sb.append("## 当前源（已自动择优，通常无需你操心）\n\n")
        sb.append("| 生态 | 当前源 |\n|---|---|\n")
        val labels = linkedMapOf(
            ECO_PYPI to "PyPI (pip)", ECO_APT to "Termux APT (pkg)",
            ECO_NPM to "npm / pnpm", ECO_GO to "Go module", ECO_CARGO to "Cargo",
        )
        for (eco in ECOS) {
            val url = mirrors[eco] ?: continue
            sb.append("| ").append(labels[eco] ?: eco).append(" | `").append(url).append("` |\n")
        }
        sb.append("\n配置已落盘，**任何 shell 里直接装即可**，不用加 `-i` / `--registry` 参数：\n\n")
        sb.append("- pip → `\$PREFIX/etc/pip.conf` 与 `~/.config/pip/pip.conf`（两处都要，venv 读后者）\n")
        sb.append("- npm → `~/.npmrc`\n")
        sb.append("- Go → `~/.config/go/env`\n")
        sb.append("- Cargo → `~/.cargo/config.toml`\n")
        sb.append("- apt → `\$PREFIX/etc/apt/sources.list`\n\n")
        sb.append("环境变量 `PIP_INDEX_URL` / `npm_config_registry` / `GOPROXY` 也已注入，`env` 里可直接看到。\n\n")
        sb.append("## 还是慢？重新测速挑最快\n\n")
        sb.append("```sh\n").append(PROBE_CMD).append("\n```\n\n")
        sb.append("它并行探测全部候选节点（约 8 秒）并输出 `生态 地址 状态码 速度(B/s)`。\n")
        sb.append("挑出每个生态 200 状态里速度最大的那个，写回上面的配置文件即可；改完新开 shell 生效。\n\n")
        sb.append("## 硬知识（实测，别再重新踩）\n\n")
        sb.append("- 🔴 **`pypi.org` 与 `proxy.golang.org` 在本机完全不可达**（curl code=000），别试。\n")
        sb.append("- 🔴 **同一个站点在不同生态上表现相反**：aliyun 是 PyPI 最快（1.57 MB/s），")
        sb.append("但它的 Termux APT 仓库是 **404**。所以**不要**把某一个站当万能源。\n")
        sb.append("- 实测速度（2026-09-27）：PyPI aliyun 1.57 MB/s > tuna 1.14 > ustc 1.13；")
        sb.append("APT ustc 47 KB/s > tuna > bfsu；npm npmmirror 1.13 MB/s > tencent 0.93 > npmjs 0.40。\n")
        sb.append("- 装 Python 包优先用 venv（`~/venv/bin/pip`）：系统 python 保持干净，")
        sb.append("且 venv 要读 `~/.config/pip/pip.conf` 才走镜像。\n")
        return sb.toString()
    }
}
