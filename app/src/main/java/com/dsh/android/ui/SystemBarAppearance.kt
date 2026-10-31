package com.dsh.android.ui
import android.content.Context
import android.graphics.Color
import com.dsh.android.log.DshLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import com.dsh.android.bubble.BubbleAppearance
import com.dsh.android.bubble.BubbleFrames
import com.dsh.android.bubble.FloatingBubble
import com.dsh.android.core.DshApplication

/**
 * 系统栏（**状态栏** + **导航栏**）的独立配色配置。
 *
 * ## 两条栏分别是什么
 *
 * | 名字 | 位置 | 显示内容 | 对应 API |
 * |---|---|---|---|
 * | 状态栏 statusBar | 屏幕**最顶上**那一条 | 时间、电量、信号、通知图标 | `window.statusBarColor` |
 * | 导航栏 navBar | 屏幕**最底下**那一条 | 返回/Home/最近任务（或手势横条） | `window.navigationBarColor` |
 *
 * ## 为什么要有这个类
 *
 * 改动之前，这两条栏的颜色**只由页面底色驱动**（`StatusBridge.onStatus` 上报
 * `status.bg` → [MainActivity.applySystemBarColor]），没有任何独立入口：
 * 用户改不了、AI 更够不着。本类把「钦定颜色」这一层单独提出来 ——
 * 与 [BubbleAppearance] 之于悬浮球完全同构。
 *
 * ## 字段语义（两条栏**各自独立**）
 *
 * | 字段 | 取值 | 语义 |
 * |---|---|---|
 * | `statusBar` | `#RRGGBB` / `#AARRGGBB` | 状态栏钦定色 |
 * | `navBar` | `#RRGGBB` / `#AARRGGBB` | 导航栏钦定色 |
 * | `statusBarFlash` | `["#RRGGBB", …]` | 状态栏**闪烁序列**；`[]` = 停掉这栏的闪 |
 * | `navBarFlash` | `["#RRGGBB", …]` | 导航栏闪烁序列；同上 |
 * | `flashIntervalMs` | int | 每帧毫秒，30~60000，默认 120 |
 * | `flashDurations` | `[int, …]` | **逐帧**时长（覆盖 `flashIntervalMs`），循环取用 |
 * | `flashLoops` | int | 闪几圈；`-1` = 无限（默认） |
 * | `flashRunning` | bool | 闪不闪。缺席且给了颜色序列 ⇒ **自动置 true** |
 * | `fullscreen` | bool | **全面屏**：内容延展到整屏，两条栏交给页面（默认 false） |
 * | `statusBarIcons` | `auto`/`light`/`dark` | 状态栏图标明暗（默认 `auto`） |
 * | `navBarIcons` | `auto`/`light`/`dark` | 导航栏图标明暗（默认 `auto`） |
 *
 * ⚠️ **空串 = 没有钦定，跟随页面底色**（也就是改动前的原有行为）。
 *   所以「恢复默认」不是删配置，而是把对应字段写回空串 —— 见 [reset]。
 *
 * ⚠️ **闪烁是原生自驱的，不是让模型反复调工具。**
 *   这条设计的由来是一次真机失败：闪 7 色时模型只能一轮一轮调 `statusbar`，
 *   而每次调用要走「模型生成 → 写信箱 → 250ms 轮询 → 应用 → 回执」，
 *   实测帧间隔 **7.6~27.9 秒**（见会话日志），根本闪不起来。所以颜色序列一次性
 *   交给原生、由 `Handler` 自己轮转（[MainActivity] 驱动），模型只调一次即可。
 *   这与悬浮球的 `spin` / `pulse` 是同一个思路：持续动效由原生持有。
 *
 * ⚠️ **两条栏可以各闪各的**（序列长度都可以不同）：一栏闪、另一栏不闪是正常用法。
 *
 * ## 图标明暗：默认自动算，全面屏下可钦定
 *
 * 默认由**这条栏的底色**按 BT.601 感知亮度自动算（[isLightColor]）。让调用方
 * 自己指定图标色是最容易翻车的一条路：白图标配白底 = 状态栏上的时间直接看不见，
 * 而调用方根本无从知道。两条栏各算各的，与 Android 自己的两个开关
 * （`isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`）一一对应。
 * 闪烁时每帧各算一次，所以序列里深浅色混排也不会出现看不见的帧。
 *
 * ⚠️ **但全面屏（`fullscreen`）下"底色"这个概念失效了**：那时两条栏是透明的，
 *   底下压着的是页面任意内容 —— 背景图、渐变、圆角卡片。拿一个颜色去猜图标明暗
 *   必然猜错，而错的那一刻用户看到的是"时间不见了"。所以全面屏把这一层判断权
 *   交回调用方：[statusBarIcons] / [navBarIcons] 取 `light` / `dark` 可钦定，
 *   取 `auto`（默认）仍然自动算。
 *
 * ## 落盘与生效
 *
 * 配置落 SharedPreferences，**重启 App 后仍然生效**。生效需要 [MainActivity] 的
 * window，所以本类持一份 **Activity 弱引用**（[attach]）：Activity 活着时立刻应用，
 * 不在时只落盘、等下次 `onCreate` 自己补上。用弱引用是因为这份引用活到进程结束，
 * 强引用会泄漏整个 Activity。
 */
data class SystemBarAppearance(
    /** 状态栏钦定色；空串 = 跟随页面底色。 */
    val statusBar: String = "",
    /** 导航栏钦定色；空串 = 跟随页面底色。 */
    val navBar: String = "",
    /** 状态栏闪烁序列；空 = 这栏不闪。 */
    val statusBarFlash: List<String> = emptyList(),
    /** 导航栏闪烁序列；空 = 这栏不闪。 */
    val navBarFlash: List<String> = emptyList(),
    /** 每帧停留毫秒。被 [flashDurations] 逐帧覆盖。 */
    val flashIntervalMs: Int = DEFAULT_FLASH_INTERVAL_MS,
    /** 逐帧停留时长；长度不足的帧用 [flashIntervalMs]。 */
    val flashDurations: List<Int> = emptyList(),
    /** 闪烁圈数；[FLASH_LOOPS_INFINITE] = 无限。 */
    val flashLoops: Int = FLASH_LOOPS_INFINITE,
    /** 是否正在闪。见类注释里「缺席且给了序列即自动置 true」那条。 */
    val flashRunning: Boolean = false,
    /**
     * **全面屏**：内容延展到整屏（状态栏/导航栏区域也归页面所有）。
     *
     * 打开时两条栏被设为**透明**，页面内容直接铺到屏幕最上/最下边；页面需要自己
     * 给顶部/底部留出让位空间（原生把状态栏高度写进 `--dsh-android-system-top`）。
     * 这是「背景图能覆盖全屏」的**底座**：不是给背景图开特例，而是让内容本来就
     * 铺满整屏，于是任何背景（纯色 / 渐变 / 图片）都自然覆盖全屏。
     */
    val fullscreen: Boolean = false,
    /**
     * 状态栏图标明暗：`auto`（按底色算）/ `light` / `dark`。
     *
     * 全面屏下底色是页面任意内容，自动算不准，此时由调用方钦定。
     */
    val statusBarIcons: String = ICON_AUTO,
    /** 导航栏图标明暗。语义同 [statusBarIcons]。 */
    val navBarIcons: String = ICON_AUTO,
) {

    /** 两条栏当前是否有可闪的内容。 */
    val hasFlash: Boolean
        get() = statusBarFlash.isNotEmpty() || navBarFlash.isNotEmpty()

    /**
     * 第 [index] 帧的停留时长：优先逐帧表，越界或表为空时退回 [flashIntervalMs]。
     *
     * 与悬浮球 `BubbleAppearance.frameDurations` 的语义一致（那里由
     * `FloatingBubble` 消费）。
     */
    fun frameDelayMs(index: Int): Long {
        val ms = flashDurations.getOrNull(index) ?: flashIntervalMs
        return ms.coerceIn(MIN_FLASH_INTERVAL_MS, MAX_FLASH_INTERVAL_MS).toLong()
    }

    companion object {
        private const val TAG = DshApplication.TAG

        /** 落盘的 SharedPreferences 名。与悬浮球的 `dsh_bubble` 分开，互不影响。 */
        const val PREFS = "dsh_systembar"

        /** 整个配置对象的 key。 */
        const val KEY_APPEARANCE = "appearance"

        /** 闪烁默认帧长。比人眼可分辨的闪烁（约 100ms）略慢一点，不刺眼。 */
        const val DEFAULT_FLASH_INTERVAL_MS = 120

        /**
         * 闪烁帧长的下限（ms）。
         *
         * ⚠️ **这条下限是防呆不是性能考虑**：每帧都要走一次 `window.statusBarColor`
         *   赋值 + 图标明暗切换，即一次窗口属性变更。低于 30ms 会以每秒 33 次的频率
         *   打 WindowManager，在低端机上引起整个界面掉帧 —— 而调用方（模型）
         *   完全想不到这一层。
         */
        const val MIN_FLASH_INTERVAL_MS = 30

        /** 闪烁帧长的上限（ms）。再慢就不像"闪"了，不如直接设个固定色。 */
        const val MAX_FLASH_INTERVAL_MS = 60_000

        /** 闪烁圈数：无限循环。 */
        const val FLASH_LOOPS_INFINITE = -1

        /**
         * 图标明暗：按底色自动算。
         *
         * ⚠️ 这就是**默认值**，且必须是默认值 —— 绝大多数使用场景（两条栏是纯色）
         *   下自动算是对的，只有全面屏才需要调用方介入。
         */
        const val ICON_AUTO = "auto"

        /** 图标明暗：钦定浅色（深色图标画在浅色内容上）。 */
        const val ICON_DARK = "dark"

        /** 图标明暗：钦定深色（浅色图标画在深色内容上）。 */
        const val ICON_LIGHT = "light"

        /**
         * 一个序列最多几色。
         *
         * 与悬浮球 `BubbleFrames.MAX_FRAMES` 同一个理由：上限本身是**接口的一部分**，
         * 超了要么截断要么报错，不能静默变成别的行为。
         */
        const val MAX_FLASH_COLORS = 64

        /** 当前承载窗口的 Activity。弱引用 —— 见类注释。 */
        @Volatile
        private var host: WeakReference<MainActivity>? = null

        /**
         * 由 `MainActivity.onCreate` 调用，把窗口宿主记下来。
         *
         * ⚠️ 必须在 `onCreate` 里就登记（不能等 `onResume`）：AI 可能在 App 刚起、
         *   还没走到 `onResume` 时下发配置 —— 那时没有宿主就只能落盘、这一轮看不到
         *   效果，表现为「AI 说改好了但没生效」。
         */
        fun attach(activity: MainActivity) {
            host = WeakReference(activity)
        }

        /** 由 `MainActivity.onDestroy` 调用，避免对着已销毁的窗口设色。 */
        fun detach(activity: MainActivity) {
            if (host?.get() === activity) host = null
        }

        /**
         * 把一条 JSON 合并到当前配置上，落盘，并**尽力立即应用**。
         *
         * 语义是「合并」不是「替换」：只想改导航栏时不必重述状态栏。
         * 未知字段忽略，非法颜色保持原值 —— 写错一个字段不该让另一条栏的配色一起回退。
         *
         * @param json `{"statusBar":"#RRGGBB","navBar":"#RRGGBB","statusBarFlash":[…]}`，字段都可选。
         * @return 合并后的配置（即使宿主不在、没能立刻应用，也已经是落盘后的值）。
         */
        fun apply(context: Context, json: String): SystemBarAppearance {
            val next = merge(json, load(context))
            save(context, next)
            push()
            return next
        }

        /**
         * 停掉两条栏的闪烁，**保留钦定色**。
         *
         * ⚠️ 与 [reset] 分开：那个是「全部恢复默认」（连颜色一起清），这个是「别闪了，
         *   但颜色留着」。真机使用中这两种意图经常交替出现 —— 用户看够了闪，
         *   通常还想停在某一色上，而不是连颜色一起丢掉。
         */
        fun stopFlash(context: Context): SystemBarAppearance {
            val cur = load(context)
            val next = cur.copy(flashRunning = false)
            save(context, next)
            push()
            return next
        }

        /**
         * 清掉两条栏的钦定色、闪烁与全面屏，回到「跟随页面底色 + 常规窗口布局」。
         *
         * ⚠️ 这是**恢复原有行为**的唯一入口，必须存在：没有它，AI 一旦钦定过颜色或
         *   开过全面屏，用户就再也回不到「系统栏跟着主题自动走」那个状态，只能清 App 数据。
         *   全面屏尤其要紧——它会改窗口 flag，用户找不到开关就"退不出来"了。
         */
        fun reset(context: Context): SystemBarAppearance {
            val next = SystemBarAppearance()
            save(context, next)
            push()
            return next
        }

        /** 当前配置的 JSON 文本，供插件做「读取-修改-写回」。 */
        fun currentJson(context: Context): String = toJson(load(context))

        /**
         * 配置 → JSON。**读取路径与落盘路径共用它**，所以两边的字段集永远一致。
         *
         * ⚠️ 这是个真踩过的坑面：[save] 与 [currentJson] 各写一份的话，新增字段时
         *   极容易只改一处 —— 症状是「AI 读回来的配置里没有刚设的那个字段」，
         *   于是它以为没设上、反复重设。
         *
         * ⚠️ **只放字段，不放派生量**：`hasFlash` 这种由两个序列算出来的东西不进
         *   JSON（曾误加过一次）。它一旦落盘就成了持久化格式的一部分，
         *   而派生量的正确性取决于读它的那份代码 —— 格式里躺着个会变的派生值，
         *   迟早误导下一个改这里的人。要它的人自己从 `statusBarFlash` /
         *   `navBarFlash` 算，那才是唯一的真相来源。
         */
        fun toJson(a: SystemBarAppearance): String = JSONObject().apply {
            put("statusBar", a.statusBar)
            put("navBar", a.navBar)
            put("statusBarFlash", jsonArray(a.statusBarFlash))
            put("navBarFlash", jsonArray(a.navBarFlash))
            put("flashIntervalMs", a.flashIntervalMs)
            put("flashDurations", JSONArray().apply { a.flashDurations.forEach { put(it) } })
            put("flashLoops", a.flashLoops)
            put("flashRunning", a.flashRunning)
            put("fullscreen", a.fullscreen)
            put("statusBarIcons", a.statusBarIcons)
            put("navBarIcons", a.navBarIcons)
        }.toString()

        private fun jsonArray(items: List<String>): JSONArray =
            JSONArray().apply { items.forEach { put(it) } }


        /** 通知宿主重新应用一次（宿主不在时什么都不做，配置已经落盘了）。 */
        private fun push() {
            val activity = host?.get() ?: return
            activity.onSystemBarConfigChanged()
        }

        /** 从 SharedPreferences 读回配置。缺失或损坏时返回默认（两条都跟随页面）。 */
        fun load(context: Context): SystemBarAppearance {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APPEARANCE, null) ?: return SystemBarAppearance()
            return try {
                merge(raw, SystemBarAppearance())
            } catch (t: Throwable) {
                Log.w(TAG, "系统栏配色配置损坏，已回退默认: ${t.message}")
                SystemBarAppearance()
            }
        }

        /**
         * 落盘。**每次 apply 都写**：AI 改完就该在重启后仍然生效。
         *
         * ⚠️ 序列化成 JSON 的那一步走 [toJson]，与 [currentJson] 共用 ——
         *   那里有为什么必须共用的说明。
         */
        fun save(context: Context, appearance: SystemBarAppearance) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_APPEARANCE, toJson(appearance)).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "系统栏配色配置落盘失败: ${t.message}")
            }
        }

        /**
         * 逐字段合并。**字段缺席 = 保持原值**，字段存在但为空串 = 清掉钦定。
         *
         * ⚠️ 不能像 [BubbleAppearance.merge] 的多数文本字段那样「空串即保持原值」：
         *   空串在这里是**合法且必要**的取值（= 回到跟随页面底色），一刀切地
         *   丢掉空串会让调用方没法把某一条栏单独交还给页面。
         *
         * ⚠️ **`flashRunning` 的默认值取决于本次是否给了新序列**：只发颜色序列
         *   （AI 最常见的用法）自动开始闪；发送 `{"flashRunning":false}` 才停。
         *   若把它当普通布尔处理，`{"statusBarFlash":[…]}` 会因为 `flashRunning`
         *   缺席而保持 `false` —— AI 明明设了序列却什么都不闪，纯浪费一轮。
         */
        fun merge(json: String, base: SystemBarAppearance): SystemBarAppearance = try {
            val o = JSONObject(json)
            val statusFlash = if (o.has("statusBarFlash")) {
                colorListOf(o, "statusBarFlash", base.statusBarFlash)
            } else {
                base.statusBarFlash
            }
            val navFlash = if (o.has("navBarFlash")) {
                colorListOf(o, "navBarFlash", base.navBarFlash)
            } else {
                base.navBarFlash
            }
            val next = base.copy(
                statusBar = colorOf(o, "statusBar", base.statusBar),
                navBar = colorOf(o, "navBar", base.navBar),
                statusBarFlash = statusFlash,
                navBarFlash = navFlash,
                flashIntervalMs = clampInt(
                    o, "flashIntervalMs", base.flashIntervalMs,
                    MIN_FLASH_INTERVAL_MS, MAX_FLASH_INTERVAL_MS,
                ),
                flashDurations = if (o.has("flashDurations")) {
                    intListOf(o, "flashDurations", base.flashDurations)
                } else {
                    base.flashDurations
                },
                flashLoops = clampInt(o, "flashLoops", base.flashLoops, FLASH_LOOPS_INFINITE, 100_000),
                // 见上方注释：给了新序列就自动开始闪。
                flashRunning = if (o.has("flashRunning")) {
                    o.optBoolean("flashRunning", base.flashRunning)
                } else if (o.has("statusBarFlash") || o.has("navBarFlash")) {
                    statusFlash.isNotEmpty() || navFlash.isNotEmpty()
                } else {
                    base.flashRunning
                },
                fullscreen = if (o.has("fullscreen")) {
                    o.optBoolean("fullscreen", base.fullscreen)
                } else {
                    base.fullscreen
                },
                statusBarIcons = iconModeOf(o, "statusBarIcons", base.statusBarIcons),
                navBarIcons = iconModeOf(o, "navBarIcons", base.navBarIcons),
            )
            // 序列被清空时不能留下 flashRunning=true —— 那会让驱动去闪一个空序列。
            if (!next.hasFlash) next.copy(flashRunning = false) else next
        } catch (t: Throwable) {
            Log.w(TAG, "系统栏配色配置解析失败（保持原配置）: ${t.message}")
            base
        }

        /**
         * 颜色数组字段：逐项校验，非法项**丢弃该项**（不是整组回退）。
         *
         * ⚠️ 逐项丢弃而不是整组回退：模型给的序列里混进一个 `"red"` 这种写法很常见，
         *   整组回退会让它精心排的 7 色全废，而用户只看到"没反应"。
         *   丢一项的后果只是少一色，用户看到的是"闪的色少了一个"，容易归因得多。
         */
        private fun colorListOf(o: JSONObject, key: String, fallback: List<String>): List<String> {
            val arr = o.optJSONArray(key) ?: run {
                Log.w(TAG, "系统栏配色的 $key 不是数组（保持原值）")
                return fallback
            }
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_FLASH_COLORS) {
                    Log.w(TAG, "系统栏配色的 $key 超过 $MAX_FLASH_COLORS 项，多余的已截断")
                    break
                }
                val v = arr.optString(i, "").trim()
                if (isValidColor(v)) out.add(v)
                else Log.w(TAG, "系统栏配色的 $key[$i] 非法，已丢弃该项: $v")
            }
            return out
        }

        /** 整数数组字段（逐帧时长）：非正数项丢弃。 */
        private fun intListOf(o: JSONObject, key: String, fallback: List<Int>): List<Int> {
            val arr = o.optJSONArray(key) ?: return fallback
            val out = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_FLASH_COLORS) break
                val v = arr.optInt(i, -1)
                if (v in MIN_FLASH_INTERVAL_MS..MAX_FLASH_INTERVAL_MS) out.add(v)
                else Log.w(TAG, "系统栏配色的 $key[$i] 越界已丢弃: $v")
            }
            return out
        }

        /** 颜色是否合法：`#RRGGBB` / `#AARRGGBB` 且能解析。 */
        private fun isValidColor(v: String): Boolean {
            if (!v.startsWith("#") || (v.length != 7 && v.length != 9)) return false
            return try {
                Color.parseColor(v)
                true
            } catch (t: Throwable) {
                false
            }
        }

        /** 整数字段的通用夹取：非数字/缺失时保持原值。 */
        private fun clampInt(o: JSONObject, key: String, fallback: Int, min: Int, max: Int): Int {
            if (!o.has(key)) return fallback
            val v = o.optInt(key, fallback)
            if (v < min || v > max) {
                Log.w(TAG, "系统栏配色的 $key 越界（$v 不在 $min..$max），保持 $fallback")
                return fallback
            }
            return v
        }

        /**
         * 图标明暗字段：只接受 `auto` / `light` / `dark`，其它保持原值。
         *
         * ⚠️ 不做「不认识就当 auto」这种兜底：调用方写了个 `"bright"`，若静默按
         *   auto 处理，它得到的回执与意图不符却看不出来 —— 而这条字段的**全部意义**
         *   就是"自动算不准、我要钦定"，静默降级恰好把它退化成最不准的那种。
         */
        private fun iconModeOf(o: JSONObject, key: String, fallback: String): String {
            if (!o.has(key)) return fallback
            val v = o.optString(key, "").trim().lowercase()
            if (v == ICON_AUTO || v == ICON_LIGHT || v == ICON_DARK) return v
            Log.w(TAG, "系统栏配色的 $key 不认识: $v（保持 $fallback），只接受 auto/light/dark")
            return fallback
        }


        /**
         * 颜色字段：只接受 `#RRGGBB` / `#AARRGGBB` 或空串，其它一律保持原值。
         *
         * ⚠️ 不用 `optString` + `Color.parseColor` 裸奔：parseColor 对非法串抛
         *   `IllegalArgumentException`，那会让整份配置被丢弃（另一条栏的颜色一起回退）。
         */
        private fun colorOf(o: JSONObject, key: String, fallback: String): String {
            if (!o.has(key)) return fallback
            val v = o.optString(key, "").trim()
            if (v.isEmpty()) return ""
            if (!v.startsWith("#") || (v.length != 7 && v.length != 9)) {
                Log.w(TAG, "系统栏配色的 $key 不是 #RRGGBB 形式: $v（保持 ${fallback.ifEmpty { "跟随页面底色" }}）")
                return fallback
            }
            return try {
                Color.parseColor(v)
                v
            } catch (t: Throwable) {
                Log.w(TAG, "系统栏配色的 $key 解析失败: $v")
                fallback
            }
        }

        /**
         * 判断颜色是否算「浅色」，用来决定系统栏图标该用深色还是浅色。
         *
         * 用 ITU-R BT.601 的感知亮度权重，不取 RGB 平均：人眼对绿最敏感、对蓝最不敏感，
         * 取平均会把纯蓝（`#0000FF`）误判成浅色，于是配上深色图标、在蓝底上糊成一片。
         */
        fun isLightColor(color: Int): Boolean {
            val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
            return luminance / 255.0 > 0.5
        }
    }
}
