package com.dsh.android.bubble

import android.content.Context
import android.graphics.Color
import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import java.io.File
import com.dsh.android.privilege.ShellPrivilege
import com.dsh.android.ui.BalanceBadge
import com.dsh.android.ui.KeyboardAvoid
import com.dsh.android.bubble.BubbleAppearance.Companion.AVATAR_DEFAULT
import com.dsh.android.bubble.BubbleAppearance.Companion.AVATAR_FILE
import com.dsh.android.bubble.BubbleAppearance.Companion.AVATAR_NONE
import com.dsh.android.bubble.BubbleAppearance.Companion.EDGE_IMAGE_DEFAULT
import com.dsh.android.bubble.BubbleAppearance.Companion.EDGE_IMAGE_FILE
import com.dsh.android.bubble.BubbleAppearance.Companion.EDGE_IMAGE_NONE
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_AVATAR_BASE64
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_BOX_DP
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_EDGE_HEIGHT
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_EDGE_IMAGE_BASE64
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_EDGE_WIDTH
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_POOLS
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_POOL_ACTIONS
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_POOL_NAME_CHARS
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_SHAPE_PATH_CHARS
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_SIZE_DP
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_STATUS_IMAGES
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_STATUS_IMAGES_PER_TIER
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_STATUS_IMAGE_DP
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_STATUS_TEXTS_PER_TIER
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_STATUS_TEXT_CHARS
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_STATUS_TIERS
import com.dsh.android.bubble.BubbleAppearance.Companion.MIN_BOX_DP
import com.dsh.android.bubble.BubbleAppearance.Companion.MIN_EDGE_WIDTH
import com.dsh.android.bubble.BubbleAppearance.Companion.RUN_STATES
import com.dsh.android.bubble.BubbleAppearance.Companion.SHAPE_CIRCLE
import com.dsh.android.bubble.BubbleAppearance.Companion.SHAPE_PATH
import com.dsh.android.bubble.BubbleAppearance.Companion.SHAPE_ROUNDED
import com.dsh.android.bubble.BubbleAppearance.Companion.SHAPE_SQUARE
import com.dsh.android.bubble.BubbleAppearance.Companion.STATE_AUTO
import com.dsh.android.bubble.BubbleAppearance.Companion.STATE_COLLAPSED
import com.dsh.android.bubble.BubbleAppearance.Companion.STATE_EXPANDED
import com.dsh.android.bubble.BubbleAppearance.Companion.STATE_HIDDEN
import com.dsh.android.bubble.BubbleAppearance.MoveParams
import com.dsh.android.bubble.BubbleAppearance.Companion.spriteOf

/** 外观合并、字段校验与配置中素材的摄入；不拥有窗口或动画状态。 */
internal object BubbleAppearanceParser {
    private const val TAG = Log.TAG

    /**
     * 从 JSON 解析，**逐字段合并到 [base] 之上**。
     *
     * ⚠️ 语义是「合并」不是「替换」：插件只想改形状时不必重述其它 15 个字段。
     *   这与 [state] 的用法一致 —— 改一个字段就够。
     *
     * @param json 插件下发的 JSON 文本。
     * @param base 当前配置；字段缺失时保留这里的值。
     * @param context 只用于核对自定义头像的落盘文件还在不在；传 null 时按「在」处理。
     * @return 合并后的配置；JSON 不可解析时原样返回 [base]。
     */
    fun merge(json: String, base: BubbleAppearance, context: Context? = null): BubbleAppearance = try {
        val o = JSONObject(json)

        // 轮播帧要先处理：它可能**改变帧数**，而帧数决定 sprite 网格是否还有意义。
        var merged = base.copy(
            state = enumOf(o, "state", base.state, STATE_AUTO, STATE_EXPANDED, STATE_COLLAPSED, STATE_HIDDEN),
            shape = enumOf(
                o, "shape", base.shape,
                SHAPE_CIRCLE, SHAPE_SQUARE, SHAPE_ROUNDED, SHAPE_PATH,
            ),
            shapePath = textOf(o, "shapePath", base.shapePath, MAX_SHAPE_PATH_CHARS),
            shapeViewBox = textOf(o, "shapeViewBox", base.shapeViewBox, 64),
            size = clampInt(o, "size", base.size, 24, MAX_SIZE_DP),
            // ⚠️ `0` 是**合法取值**（= 自动），所以这里不能再套 `clampInt(o,…,
            //   24, 160)` —— 那会把 0 夹成 24，配置里写 `boxWidth:0` 反而变成长方形。
            //   单独用一个 `autoOrInt` 把「0 或 16~400」放行。
            boxWidth = autoOrInt(o, "boxWidth", base.boxWidth),
            boxHeight = autoOrInt(o, "boxHeight", base.boxHeight),
            showDot = if (o.has("showDot")) o.optBoolean("showDot", base.showDot) else base.showDot,
            cornerRadius = clampInt(o, "cornerRadius", base.cornerRadius, 0, 80),
            opacity = clampFloat(o, "opacity", base.opacity, 0.05f, 1f),
            bgOpacity = clampFloat(o, "bgOpacity", base.bgOpacity, 0f, 1f),
            avatarOpacity = clampFloat(o, "avatarOpacity", base.avatarOpacity, 0f, 1f),
            avatarScale = clampFloat(o, "avatarScale", base.avatarScale, 0.2f, 2f),
            bg = colorOf(o, "bg", base.bg),
            bg2 = colorOf(o, "bg2", base.bg2),
            border = colorOf(o, "border", base.border),
            borderWidth = clampInt(o, "borderWidth", base.borderWidth, 0, 8),
            avatar = avatarOf(o, "avatar", base.avatar, context),
            // ⚠️ `frameCount` 必须在这里认：`load()` 就是拿磁盘 JSON 走
            //   `merge`，漏了它的话重启后帧数永远是 0 —— 轮播帧文件还在
            //   磁盘上，却再没人引用，表现为"重启后动画没了"。
            //   下面处理 `frames` 时会用真实写入结果覆盖它。
            frameCount = clampInt(o, "frameCount", base.frameCount, 0, BubbleFrames.MAX_FRAMES),
            frameIntervalMs = clampInt(o, "frameIntervalMs", base.frameIntervalMs, 20, 60_000),
            spriteRows = clampInt(o, "spriteRows", base.spriteRows, 0, 32),
            spriteCols = clampInt(o, "spriteCols", base.spriteCols, 0, 32),
            frameLoop = clampInt(o, "frameLoop", base.frameLoop, -1, 100_000),
            // ⚠️ `action` 空串是**合法取值**（= 自动跟随状态），不能像其它文本
            //   字段那样"空串即保持原值" —— 那会让 AI 没法从钦定动作切回自动。
            //   只在字段缺席时才保留原值。
            action = if (o.has("action")) actionOf(o, "action") else base.action,
            actionMap = if (o.has("actionMap")) actionMapOf(o, "actionMap") else base.actionMap,
            spin = clampInt(o, "spin", base.spin, -720, 720),
            pulse = if (o.has("pulse")) o.optBoolean("pulse", base.pulse) else base.pulse,
            panelWidth = clampInt(o, "panelWidth", base.panelWidth, 180, 2000),
            panelHeight = clampInt(o, "panelHeight", base.panelHeight, 0, 4000),
            panelBg = colorOf(o, "panelBg", base.panelBg),
            panelText = colorOf(o, "panelText", base.panelText),
            panelAccent = colorOf(o, "panelAccent", base.panelAccent),
            statusAutoHideMs = clampInt(o, "statusAutoHideMs", base.statusAutoHideMs, 0, 60_000),
            edgeSnap = if (o.has("edgeSnap")) o.optBoolean("edgeSnap", base.edgeSnap) else base.edgeSnap,
            // 贴边态外观：与 avatar 同一套「data URI 下发、文件标记回读」的陷阱，
            // 见 [edgeImageOf] 的注释。
            edgeImage = if (o.has("edgeImage")) edgeImageOf(o.opt("edgeImage"), base.edgeImage, context) else base.edgeImage,
            edgeWidth = if (o.has("edgeWidth")) {
                o.optInt("edgeWidth", base.edgeWidth).coerceIn(MIN_EDGE_WIDTH, MAX_EDGE_WIDTH)
            } else base.edgeWidth,
            edgeHeight = if (o.has("edgeHeight")) {
                o.optInt("edgeHeight", base.edgeHeight).coerceIn(0, MAX_EDGE_HEIGHT)
            } else base.edgeHeight,
            edgeOpacity = if (o.has("edgeOpacity")) {
                o.optDouble("edgeOpacity", base.edgeOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            } else base.edgeOpacity,
            edgeAnchor = if (o.has("edgeAnchor")) {
                o.optDouble("edgeAnchor", base.edgeAnchor.toDouble()).toFloat().coerceIn(-1f, 1f)
            } else base.edgeAnchor,
            throwEnabled = if (o.has("throwEnabled")) o.optBoolean("throwEnabled", base.throwEnabled) else base.throwEnabled,
            imeFlingEnabled = if (o.has("imeFlingEnabled")) o.optBoolean("imeFlingEnabled", base.imeFlingEnabled) else base.imeFlingEnabled,
            shakeEnabled = if (o.has("shakeEnabled")) o.optBoolean("shakeEnabled", base.shakeEnabled) else base.shakeEnabled,
            // 余额徽标整包下发：`balanceBadge` 缺席时**保持原值**（不能重置成默认，
            // 否则 AI 每改一次别的字段就会把用户精心调好的徽标打回原形）。
            balanceBadge = if (o.has("balanceBadge")) {
                BalanceBadge.merge(o.optJSONObject("balanceBadge"), base.balanceBadge)
            } else {
                base.balanceBadge
            },
            // ⚠️ 池与文案都**允许传空对象/空数组来清空**（与 `actionMap` 同规矩）：
            //   一刀切地"空即保持原值"会让 AI 没法撤掉配错的池。
            pools = if (o.has("pools")) poolsOf(o) else base.pools,
            poolWeights = if (o.has("poolWeights")) poolWeightsOf(o) else base.poolWeights,
            workStatusTexts = if (o.has("workStatusTexts")) statusTextsOf(o) else base.workStatusTexts,
            moveEnabled = if (o.has("moveEnabled")) o.optBoolean("moveEnabled", base.moveEnabled) else base.moveEnabled,
            moveIntervalSec = clampInt(o, "moveIntervalSec", base.moveIntervalSec, 10, 3600),
            moveMinDp = clampInt(o, "moveMinDp", base.moveMinDp, 8, 400),
            moveMaxDp = clampInt(o, "moveMaxDp", base.moveMaxDp, 8, 400),
            statusImageSizeDp = clampInt(o, "statusImageSizeDp", base.statusImageSizeDp, 32, MAX_STATUS_IMAGE_DP),
            flipOnTurn = if (o.has("flipOnTurn")) o.optBoolean("flipOnTurn", base.flipOnTurn) else base.flipOnTurn,
            statusImages = if (o.has("statusImages")) statusImagesOf(o) else base.statusImages,
            moveParams = if (o.has("moveParams")) moveParamsOf(o) else base.moveParams,
        )

        // ── 「键盘顶飞」的能力闸门 ──────────────────────────────────────
        //
        // 它有**两条**检测路径，任一条可用就能工作：
        //
        // | 路径 | 延迟 | 要求 |
        // |---|---|---|
        // | 无障碍哨兵（`BubbleA11yService`） | 事件驱动 + ≤120ms | 用户开一次无障碍 |
        // | `dumpsys input_method` | **2s 起**（单次命令 95–150ms） | Shizuku / root |
        //
        // 两条都没有时**强制关掉**，而不是留一个"开着但静默无效"的开关：后者会让
        // 用户以为功能坏了、反复来报 bug。设置页会用同一判据把开关置灰并说明
        // 原因（见 `KeyboardAvoid.source()`）。
        //
        // ⚠️ 这里用 `KeyboardAvoid.isAvailable()` 而不是只判 `ShellPrivilege`：
        //   只看特权的话，开了无障碍（更快的那条）的用户会被误判成"不可用"，
        //   开关被强制置灰 —— 那正是「用无障碍优先」要解决的问题。
        //
        // ⚠️ 是「读的时候纠正」，**不写回磁盘**：用户哪天接入了特权或开了无障碍，
        //   重启 App 后原来的 true 会自动恢复生效，不需要再手动开一次。
        if (merged.imeFlingEnabled && context != null &&
            !KeyboardAvoid.isAvailable(context)
        ) {
            ShellPrivilege.logDenied("键盘顶飞", ShellPrivilege.status(context))
            merged = merged.copy(imeFlingEnabled = false)
        }

        // ── 轮播帧 / 动作帧集 ──
        //
        // ⚠️ 帧字节**不落进配置**（几十张图进 SharedPreferences 会把它撑爆），
        //   这里只处理"要不要换帧集"以及随之而来的元信息。
        //   没有 context 时（纯解析场景）只认帧数，字节由调用方负责 —— 见 save()。
        //
        // ⚠️ **这段是"默认动作"的路径**：`action` 为空（或恰好是 default）时，
        //   写的就是 `bubble-frames/` 本身 —— 也就是 0.2.6 及以前那套布局。
        //   AI 只改 `frames` 的老用法因此一字不变。
        val targetAction = BubbleFrames.normalizeAction(merged.action)
        if (targetAction == null) {
            Log.w(TAG, "悬浮球动作名非法，保持原动作")
            merged = merged.copy(action = base.action)
        }
        val writeAction = targetAction ?: BubbleFrames.DEFAULT_ACTION

        if (o.has("frames") && context != null) {
            val arr = o.optJSONArray("frames")
            if (arr == null || arr.length() == 0) {
                // 显式传空数组 = 清空这个动作的轮播，回到单张头像。
                BubbleFrames.clear(context, writeAction)
                if (writeAction == BubbleFrames.DEFAULT_ACTION) {
                    merged = merged.copy(frameCount = 0)
                }
            } else {
                val frames = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) frames += s
                }
                val durations = intArrayOf(o, "frameDurations")
                val sprite = spriteOf(merged)
                val loop = clampInt(o, "frameLoop", merged.frameLoop, -1, 100_000)
                val n = BubbleFrames.write(
                    context, frames, durations, sprite, merged.frameIntervalMs,
                    writeAction, loop,
                )
                if (n != null) {
                    if (writeAction == BubbleFrames.DEFAULT_ACTION) {
                        merged = merged.copy(frameCount = n)
                    }
                    // ⚠️ 名字是这个动作唯一的记录 —— 磁盘上的目录建出来了，
                    //   但配置里的 `action` 若还指着别的动作，刚写的这套帧
                    //   就没人在播。所以写谁就切到谁。
                    merged = merged.copy(action = writeAction)
                } else {
                    Log.w(TAG, "轮播帧写入失败，保持原帧集")
                }
            }
        } else if (context != null && o.has("spriteRows") && o.has("spriteCols")) {
            // 只改精灵图网格、没重传 frames：旧帧集是按旧网格切的，必须重切。
            // 做不到就退回"每帧一张完整图"，并明确告知 —— 静默用错网格会让
            // 球显示成网格里的碎块，比不显示更难查。
            val sprite = spriteOf(merged)
            if (sprite != null) {
                val n = BubbleFrames.rewriteSprite(context, sprite, writeAction)
                if (n == null && writeAction == BubbleFrames.DEFAULT_ACTION && merged.frameCount > 0) {
                    Log.w(TAG, "精灵图网格重切失败（帧字节已丢），退回整图模式")
                    merged = merged.copy(spriteRows = 0, spriteCols = 0)
                }
            }
        }

        // ── 视频素材（桌面宠物的高质量动效）──
        //
        // ⚠️ 与 `frames` **并列而不是替代**：视频是"高质量"，帧集是"能跑就行"
        //   的兜底。两者可以同时存在于同一个动作名下（不同目录），内容层的
        //   优先级是 视频 > 帧集 > 头像（见 FloatingBubble.applyContentLayer）。
        //   所以这里既不删帧集，也不因为帧集存在就跳过视频。
        //
        // ⚠️ 传空串 = **删掉这个动作的视频**，与 `frames: []` 的语义对齐。
        //   不这么设计的话，"换成静态图"就永远删不掉旧视频（视频优先级更高，
        //   会一直盖着）。
        if (context != null && o.has("video")) {
            val v = o.optString("video", "")
            if (v.isEmpty()) {
                BubbleVideos.clear(context, writeAction)
                Log.i(TAG, "已清除悬浮球视频素材: $writeAction")
            } else {
                val n = BubbleVideos.write(context, v, writeAction)
                if (n != null) {
                    merged = merged.copy(action = writeAction)
                } else {
                    Log.w(TAG, "视频素材写入失败，保持原素材")
                }
            }
        }

        // ── 一次配一套动作（桌面宠物）──
        //
        // ⚠️ 传的是**对象**而不是数组：`{"idle":{"frames":[…]},"thinking":{…}}`。
        //   用数组的话动作名要跟帧并列塞进同一层，解析时得分辨"这是帧还是名字"，
        //   而对象让每个动作各自带着自己那套 `frames`/`spriteRows`/`frameLoop`。
        //
        // ⚠️ 每个动作**独立合并**，一个失败不影响其余：宠物有 4 个动作，
        //   其中一个图坏了，另外 3 个照常换上，比整批回退好。
        if (context != null && o.has("actions")) {
            val acts = o.optJSONObject("actions")
            if (acts != null) {
                val names = acts.keys().asSequence()
                    .map { BubbleFrames.normalizeAction(it) }
                    .filterNotNull()
                    .filter { it != BubbleFrames.DEFAULT_ACTION }
                    .take(BubbleFrames.MAX_ACTIONS)
                    .toList()
                for (nm in names) {
                    val spec = acts.optJSONObject(nm) ?: continue
                    applyActionSpec(context, nm, spec)
                }
                if (names.isNotEmpty() && merged.action.isEmpty()) {
                    // 只配动作集、没点名播哪个 —— 让状态映射（或默认动作）去决定。
                    Log.i(TAG, "已写入悬浮球动作集: ${names.joinToString()}")
                }
            }
        }

        // ⚠️ **帧数以磁盘为准**：配置里那个 `frameCount` 只是"上一次写成功了几帧"，
        //   而动作集批量写入、清空、精灵图重切都会改变真实帧数。
        //   不信磁盘就会显示空白（找不到 index.bin）或漏播最后几帧。
        // ⚠️ 帧数与网格都以磁盘为准：写入、清空、精灵图重切都会改变真实帧数，
        //   而配置里那份只是"上一次写成功了几帧"。不信磁盘就会显示空白
        //   （找不到 index.bin）或按错网格切片（显示成碎块）。
        if (context != null) {
            val realCount = BubbleFrames.frameCount(context, writeAction)
            val realSprite = BubbleFrames.sprite(context, writeAction)
            merged = merged.copy(
                frameCount = realCount,
                spriteRows = realSprite?.first ?: 0,
                spriteCols = realSprite?.second ?: 0,
            )
        }

        // 帧集在磁盘上真的存在才算数：用户清过数据、或上一轮写失败时，
        // 配置里留着 frameCount>0 会让球显示空白（找不到帧文件）。
        if (context != null && merged.frameCount > 0 && !BubbleFrames.available(context, writeAction)) {
            Log.w(TAG, "配置里有轮播帧但磁盘上找不到，退回单张头像")
            merged = merged.copy(frameCount = 0)
        }

        // 精灵图网格只在有帧时才有意义。
        if (merged.frameCount <= 0 && (merged.spriteRows > 0 || merged.spriteCols > 0)) {
            merged = merged.copy(spriteRows = 0, spriteCols = 0)
        }

        merged
    } catch (t: Throwable) {
        Log.w(TAG, "悬浮球外观配置解析失败（保持原配置）: ${t.message}")
        base
    }

    /**
     * 动作名字段。
     *
     * ⚠️ 空串是**合法取值**（= 跟随状态自动切换），所以不能复用 [textOf]
     *   （它把空串当"没传"并返回 fallback）。非法名（含路径分隔符等）一律
     *   退成空串：宁可回到自动，也不要让一个坏名字写到不该写的地方。
     */
    private fun actionOf(o: JSONObject, key: String): String {
        val raw = o.optString(key, "").trim()
        if (raw.isEmpty()) return ""
        val name = BubbleFrames.normalizeAction(raw)
        if (name == null) {
            Log.w(TAG, "悬浮球动作名非法: $raw（退回自动跟随状态）")
            return ""
        }
        // ⚠️ `default` 要**保留**而不是归一成空串：空串的语义是"跟随状态自动切换"，
        //   而 `action:"default"` 的语义是"就播默认动作、别跟状态变"。两者不同，
        //   归一了就没法强制回到默认动作。
        return name
    }

    /**
     * 状态 → 动作名 的映射。
     *
     * ⚠️ 两处都校验：key 必须是已知运行状态，value 必须是合法动作名。
     *   任何一项不合规就**丢掉这一条而不是整份映射** —— 宠物配了 4 个状态，
     *   其中一个打错字不该让另外 3 个也失效。
     *
     * ⚠️ 传空对象 = 清空映射（回到"永远播默认动作"），与"没传"不同。
     */
    private fun actionMapOf(o: JSONObject, key: String): Map<String, String> {
        val src = o.optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (state in src.keys()) {
            if (state !in RUN_STATES) {
                Log.w(TAG, "动作映射的运行状态未知，已忽略: $state")
                continue
            }
            val raw = src.optString(state, "").trim()
            if (raw.isEmpty()) continue
            val name = BubbleFrames.normalizeAction(raw)
            if (name == null) {
                Log.w(TAG, "动作映射里的动作名非法，已忽略: $state → $raw")
                continue
            }
            out[state] = if (name == BubbleFrames.DEFAULT_ACTION) "" else name
        }
        return out
    }

    /**
     * 一个池名/动作名是否合法（`[a-z0-9_-]`，1~[MAX_POOL_NAME_CHARS] 字符）。
     *
     * ⚠️ **不复用 [BubbleFrames.normalizeAction]**：那个函数把 `default`
     *   单独挑出来当"默认动作"，而池名里 `idle`/`click` 这些要原样保留 ——
     *   借它来过一道会把池名判成 null。
     */
    fun normalizePoolName(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s.length > MAX_POOL_NAME_CHARS) return null
        return if (s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) s else null
    }

    /**
     * 解析 `pools`：池名 → 动作名列表。
     *
     * ⚠️ 非法池名/非法动作名**只丢那一条**，不影响同一个池里的其余动作，
     *   更不影响其它池 —— 配 5 个池错一个字不该让整份配置作废。
     * ⚠️ 传空对象 = 清空全部池（与"没传"不同）。
     */
    private fun poolsOf(o: JSONObject): Map<String, List<String>> {
        val src = o.optJSONObject("pools") ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (rawPool in src.keys()) {
            val pool = normalizePoolName(rawPool)
            if (pool == null) {
                Log.w(TAG, "动作池名非法，已忽略: $rawPool")
                continue
            }
            if (out.size >= MAX_POOLS) {
                Log.w(TAG, "动作池数量超过 $MAX_POOLS，其余已忽略")
                break
            }
            val arr = src.optJSONArray(rawPool)
            if (arr == null) {
                Log.w(TAG, "动作池 $pool 不是数组，已忽略")
                continue
            }
            val acts = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i, "").trim()
                if (raw.isEmpty()) continue
                val nm = BubbleFrames.normalizeAction(raw)
                if (nm == null || nm == BubbleFrames.DEFAULT_ACTION) {
                    Log.w(TAG, "动作池 $pool 里的动作名非法，已忽略: $raw")
                    continue
                }
                if (acts.size >= MAX_POOL_ACTIONS) break
                if (nm !in acts) acts += nm
            }
            if (acts.isNotEmpty()) out[pool] = acts
        }
        return out
    }

    /**
     * 解析 `poolWeights`：池名 → 相对权重。
     *
     * ⚠️ 负数**归零而不是丢弃**：0 与"没配"的语义不同（0 = 明确不参与
     *   随机链），把负数当没配会让 AI 没法表达"这个池先别用"。
     */
    private fun poolWeightsOf(o: JSONObject): Map<String, Int> {
        val src = o.optJSONObject("poolWeights") ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (rawPool in src.keys()) {
            val pool = normalizePoolName(rawPool)
            if (pool == null) {
                Log.w(TAG, "权重里的动作池名非法，已忽略: $rawPool")
                continue
            }
            out[pool] = src.optInt(rawPool, 0).coerceIn(0, 1000)
        }
        return out
    }

    /**
     * 解析 `workStatusTexts`：二维数组，外层是档位（顺序同 [RUN_STATES]）。
     *
     * ⚠️ 允许**只写前几档**（数组短了后面的档就没有文案）—— 让 AI 可以只配
     *   它在意的思考/干活两档，不必凑满 5 档。
     * ⚠️ 每档内空字符串直接丢掉：显示一个空横条比不显示更奇怪。
     */
    private fun statusTextsOf(o: JSONObject): List<List<String>> {
        val arr = o.optJSONArray("workStatusTexts") ?: return emptyList()
        val out = ArrayList<List<String>>()
        for (i in 0 until minOf(arr.length(), MAX_STATUS_TIERS)) {
            val tier = arr.optJSONArray(i) ?: continue
            val texts = ArrayList<String>(tier.length())
            for (j in 0 until tier.length()) {
                val s = tier.optString(j, "").trim()
                if (s.isEmpty()) continue
                if (texts.size >= MAX_STATUS_TEXTS_PER_TIER) break
                texts += s.take(MAX_STATUS_TEXT_CHARS)
            }
            out += texts
        }
        return out
    }

    /**
     * 解析 `statusImages`：二维数组，外层是档位（顺序同 [RUN_STATES]）。
     *
     * 结构与 `workStatusTexts` 完全同构，理由也一样：允许只写前几档。
     * ⚠️ 每档内的字符串**原样保留**（可能是绝对路径，也可能是 data URI），
     *   到落盘那一步才决定怎么存 —— 见 `BubbleStatusImages`。
     */
    private fun statusImagesOf(o: JSONObject): List<List<String>> {
        val arr = o.optJSONArray("statusImages") ?: return emptyList()
        val out = ArrayList<List<String>>()
        for (i in 0 until minOf(arr.length(), MAX_STATUS_IMAGES)) {
            val tier = arr.optJSONArray(i) ?: continue
            val imgs = ArrayList<String>()
            for (j in 0 until tier.length()) {
                val s = tier.optString(j, "").trim()
                if (s.isEmpty()) continue
                if (imgs.size >= MAX_STATUS_IMAGES_PER_TIER) break
                imgs += s
            }
            out += imgs
        }
        return out
    }

    /**
     * 解析 `moveParams`：动作名 → 距离覆盖值。
     *
     * ⚠️ 动作名走 [BubbleFrames.normalizeAction] 校验（与池里的元素同一套
     *   规则），因为它的键**就是**池里的动作名，规则不一致会对不上。
     * ⚠️ `minDp`/`maxDp` **缺省必须是 null 而不是 0**：0 是"原地踏步"这个
     *   合法用法，缺省是"跟随全局默认"。混成一个就没法表达了。
     */
    private fun moveParamsOf(o: JSONObject): Map<String, MoveParams> {
        val src = o.optJSONObject("moveParams") ?: return emptyMap()
        val out = LinkedHashMap<String, MoveParams>()
        for (rawAction in src.keys()) {
            val name = BubbleFrames.normalizeAction(rawAction)
            if (name == null || name == BubbleFrames.DEFAULT_ACTION) {
                Log.w(TAG, "moveParams 里的动作名非法，已忽略: $rawAction")
                continue
            }
            val spec = src.optJSONObject(rawAction)
            if (spec == null) {
                Log.w(TAG, "moveParams.$name 不是对象，已忽略")
                continue
            }
            out[name] = MoveParams(
                minDp = if (spec.has("minDp")) spec.optInt("minDp", 0).coerceIn(0, 400) else null,
                maxDp = if (spec.has("maxDp")) spec.optInt("maxDp", 0).coerceIn(0, 400) else null,
            )
        }
        return out
    }

    /**
     * 写入一个动作的素材（`actions` 对象里的一项）。
     *
     * 一个动作可以带**两种素材**，各自独立：
     * - `video`：一段带 alpha 的 webm（高质量动效，dsh-pet 那一套）；
     * - `frames`/`frameFiles`：一组静态图或一张精灵图（不需要解码器支持的兜底）。
     *
     * 内容层优先级是 视频 > 帧集 > 头像，所以**两者都给时视频会盖住帧集** ——
     * 这是刻意的：帧集留作"视频放不出来时的兜底"。
     *
     * ⚠️ 每个动作**各自解析自己的网格与轮数**，不共用全局那份：宠物不同动作
     *   常常来自不同的素材包（待机是 4 帧精灵图、思考是 6 帧独立图），
     *   强制共用网格会让其中一套显示成碎块。
     *
     * ⚠️ 失败**不抛**：批量配动作时一个坏图不该让另外几个换不上。
     */
    private fun applyActionSpec(context: Context, name: String, spec: JSONObject) {
        // ── 视频素材 ──
        // ⚠️ 先于 frames 处理，且**不因 frames 缺席而跳过** —— 只传 video 是
        //   最自然的用法（"这个动作是一段动画"），旧逻辑会把"没有 frames"
        //   当成"删掉这个动作"，正好把刚写的视频删掉。
        val hasVideo = spec.has("video")
        if (hasVideo) {
            val v = spec.optString("video", "")
            if (v.isEmpty()) {
                BubbleVideos.clear(context, name)
                Log.i(TAG, "已清除悬浮球动作视频: $name")
            } else {
                val n = BubbleVideos.write(context, v, name)
                if (n == null) Log.w(TAG, "悬浮球动作 $name 的视频写入失败，保持原素材")
                else Log.i(TAG, "悬浮球动作 $name 视频已就位: $n 字节")
            }
        }

        val arr = spec.optJSONArray("frames")
        if (arr == null || arr.length() == 0) {
            // 显式空数组 = 删掉这个动作的帧集。
            // ⚠️ 但如果这一项**带了视频**，那只删帧集、不删视频 ——
            //   否则 `{"idle":{"video":"/a.webm","frames":[]}}` 这种写法会
            //   把刚写进去的视频一起清掉（本意只是"这个动作不要帧集"）。
            if (!hasVideo) {
                BubbleFrames.clear(context, name)
                BubbleVideos.clear(context, name)
                Log.i(TAG, "已删除悬浮球动作: $name")
            } else {
                BubbleFrames.clear(context, name)
            }
            return
        }
        val frames = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) frames += s
        }
        val rows = spec.optInt("spriteRows", 0)
        val cols = spec.optInt("spriteCols", 0)
        val sprite = if (rows > 0 && cols > 0) rows to cols else null
        val intervalMs = spec.optInt("frameIntervalMs", 400).coerceIn(20, 60_000)
        val loop = spec.optInt("frameLoop", BubbleFrames.LOOP_FOREVER).coerceIn(-1, 100_000)
        val n = BubbleFrames.write(
            context, frames, intArrayOf(spec, "frameDurations"), sprite, intervalMs, name, loop,
        )
        if (n == null) {
            Log.w(TAG, "悬浮球动作 $name 写入失败，保持原帧集")
        } else {
            Log.i(TAG, "悬浮球动作 $name 已就位: $n 帧")
        }
    }

    /** 读一个 int 数组字段；不存在或非法时返回空数组。 */
    private fun intArrayOf(o: JSONObject, key: String): List<Int> {
        if (!o.has(key)) return emptyList()
        val arr = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v > 0) out += v
        }
        return out
    }

    /**
     * 文本字段（如 SVG path）：去空白、限长。
     *
     * ⚠️ 限长是必须的：一条几万字符的 path 会让每次解析都卡住主线程，
     *   而它带来的收益为零 —— 100dp 的球上画不出那么多细节。
     */
    private fun textOf(o: JSONObject, key: String, fallback: String, maxChars: Int): String {
        if (!o.has(key)) return fallback
        val v = o.optString(key, "").trim()
        if (v.isEmpty()) return ""
        if (v.length > maxChars) {
            Log.w(TAG, "悬浮球外观配置的 $key 过长（${v.length} > $maxChars），已忽略")
            return fallback
        }
        return v
    }

    /** 枚举字段：不在白名单里就退回 [fallback]。 */
    private fun enumOf(
        o: JSONObject,
        key: String,
        fallback: String,
        vararg allowed: String,
    ): String {
        if (!o.has(key)) return fallback
        val v = o.optString(key, "")
        if (v in allowed) return v
        Log.w(TAG, "悬浮球外观配置的 $key 取值非法: $v（保持 ${fallback}）")
        return fallback
    }

    private fun clampInt(o: JSONObject, key: String, fallback: Int, min: Int, max: Int): Int {
        if (!o.has(key)) return fallback
        val v = o.optInt(key, fallback)
        return v.coerceIn(min, max)
    }

    /**
     * 「**0 = 自动**」的尺寸字段（[BubbleAppearance.boxWidth] / [boxHeight]）。
     *
     * 存在的唯一理由是 `0` 必须**原样保留** —— 走 [clampInt] 会被夹到下限，
     * 于是「自动」这个取值根本表达不出来。其余取值仍然照 [MIN_BOX_DP]~[MAX_BOX_DP]
     * 夹紧，防插件传个 `99999` 把窗口撑爆。
     */
    private fun autoOrInt(o: JSONObject, key: String, fallback: Int): Int {
        if (!o.has(key)) return fallback
        val v = o.optInt(key, fallback)
        return if (v <= 0) 0 else v.coerceIn(MIN_BOX_DP, MAX_BOX_DP)
    }

    private fun clampFloat(o: JSONObject, key: String, fallback: Float, min: Float, max: Float): Float {
        if (!o.has(key)) return fallback
        val v = o.optDouble(key, fallback.toDouble()).toFloat()
        if (v.isNaN()) return fallback
        return v.coerceIn(min, max)
    }

    /**
     * 颜色字段：只接受 `#RRGGBB` / `#AARRGGBB`，其它一律保持原值。
     *
     * ⚠️ 不用 `optString` + `Color.parseColor` 裸奔：parseColor 对非法串抛
     *   `IllegalArgumentException`，那会让整份配置被丢弃（其余字段一起回退）。
     */
    private fun colorOf(o: JSONObject, key: String, fallback: String): String {
        if (!o.has(key)) return fallback
        val v = o.optString(key, "").trim()
        if (v.isEmpty()) return ""
        if (!v.startsWith("#") || (v.length != 7 && v.length != 9)) {
            Log.w(TAG, "悬浮球外观配置的 $key 不是 #RRGGBB 形式: $v（保持 ${fallback}）")
            return fallback
        }
        return try {
            Color.parseColor(v)
            v
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球外观配置的 $key 解析失败: $v")
            fallback
        }
    }

    /**
     * 头像字段：`default` / `none` / data URI 三者之一。
     *
     * 非 data URI 的其它字符串一律当成 `default` —— 插件可能想传本地路径，
     * 但页面侧拿不到路径，静默退回内置头像比显示一块空白好。
     */
    private fun avatarOf(o: JSONObject, key: String, fallback: String, context: Context? = null): String {
        if (!o.has(key)) return fallback
        val v = o.optString(key, "").trim()
        if (v == AVATAR_NONE || v == AVATAR_DEFAULT) return v
        // ⚠️ **落盘用的标记必须能被读回来。**
        //
        // 这不是插件会主动传的值 —— 它是 [save] 自己写进去的（见那里的注释：
        // base64 太大不进 SharedPreferences，配置里只留一个文件名标记）。
        // 漏掉这一条时，回读会把标记判成非法值退回 `default`，于是：
        //   ① 自定义头像重启即丢（图片文件还在，却再没人引用它）；
        //   ② `bubbleAppearance()` 回读与 `bubbleApply()` 下发不一致，
        //      插件做「读-改-写」时会把头像悄悄洗掉。
        //
        // 文件真丢了（用户清数据）时退回 `default`，而不是留一个悬空引用。
        if (v == AVATAR_FILE) {
            val f = context?.let { File(it.filesDir, AVATAR_FILE) }
            return when {
                // 没有 context（纯解析场景）：按「文件在」处理，保住标记。
                f == null -> AVATAR_FILE
                f.isFile && f.length() > 0L -> AVATAR_FILE
                else -> AVATAR_DEFAULT
            }
        }
        if (!v.startsWith("data:image/")) return AVATAR_DEFAULT
        if (v.length > MAX_AVATAR_BASE64) {
            Log.w(TAG, "悬浮球自定义头像过大（${v.length} 字节 > $MAX_AVATAR_BASE64），已忽略")
            return fallback
        }
        return v
    }

    /**
     * 解析贴边态图片字段。
     *
     * 与 [avatarOf] **完全同构**，包括那个最容易漏的坑：`save()` 会把 data URI
     * 换成文件标记 [EDGE_IMAGE_FILE] 落盘，回读时必须认得这个标记 —— 否则
     * 自定义贴边图重启即丢，且「读-改-写」会把它洗回内置图。
     */
    private fun edgeImageOf(raw: Any?, fallback: String, context: Context? = null): String {
        val v = (raw as? String)?.trim() ?: return fallback
        if (v.isEmpty()) return fallback
        if (v == EDGE_IMAGE_NONE || v == EDGE_IMAGE_DEFAULT) return v
        if (v == EDGE_IMAGE_FILE) {
            val f = context?.let { File(it.filesDir, EDGE_IMAGE_FILE) }
            return when {
                f == null -> EDGE_IMAGE_FILE
                f.isFile && f.length() > 0L -> EDGE_IMAGE_FILE
                else -> EDGE_IMAGE_DEFAULT
            }
        }
        if (!v.startsWith("data:image/")) return EDGE_IMAGE_DEFAULT
        if (v.length > MAX_EDGE_IMAGE_BASE64) {
            Log.w(TAG, "悬浮球贴边图过大（${v.length} 字节 > $MAX_EDGE_IMAGE_BASE64），已忽略")
            return fallback
        }
        return v
    }
}
