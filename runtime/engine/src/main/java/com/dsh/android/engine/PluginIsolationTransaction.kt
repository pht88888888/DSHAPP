package com.dsh.android.engine

import com.dsh.android.engine.constants.*
import com.dsh.android.log.DshLog as Log
import java.io.File
import com.dsh.android.log.RescueBoot
import com.dsh.android.engine.EngineManager.ProbeResult
import com.dsh.android.engine.EngineManager.IsolationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/** 拥有清单备份、临时改写还原和 L1 搜索现场；不修改救援层级。 */
internal class PluginIsolationTransaction(
    private val dshHome: File,
    private val engineDir: File,
    private val probeBoot: (String, List<File>) -> ProbeResult?,
) {
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
    suspend fun isolateBadBundles(overlays: List<File>): IsolationResult? = withContext(Dispatchers.IO) {
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
            entry.isFile && !PluginSyntaxPreflight.preflightPluginSyntax(entry)
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
    fun probeWithBundleFilter(profile: String, keep: List<String>, overlays: List<File>): ProbeResult? {
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
            probeBoot(profile, overlays)
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
    fun quarantine(file: File, reason: String): Boolean {
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

}
