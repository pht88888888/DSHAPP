package com.dsh.android

import com.dsh.android.log.EventLog
import com.dsh.android.bubble.BubbleEventGate
import com.dsh.android.bubble.PluginArbiter
import com.dsh.android.bubble.SessionAggregator
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.dsh.android.log.DshLog as Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮气泡：App 退到后台时浮在别的应用之上，让用户看得到 AI 在干什么、并能继续对话。
 *
 * ## 为什么需要
 *
 * 设备自动化场景下（让 AI 去操作微信、设置、别的 App），用户**必须离开本 App**，
 * 于是官方界面看不见了 —— 既不知道 AI 跑到哪一步，也没法中途喊停或补一句话。
 * 通知栏能显示状态，但点一下就跳走，没法交互。
 *
 * ## 形态（三种，共用一个 window，靠 visibility 切换，不重建 View）
 *
 * | 形态 | 触发 | 内容 |
 * |---|---|---|
 * | **收起** | 默认 | 球（尺寸/形状/底色/头像由插件配置）+ 状态圆点 |
 * | **贴边** | 把球**往屏幕外拖**后松手 | 只剩一条薄片贴在那一侧的边缘 |
 * | **展开** | 点球 / 点贴边薄片 | 可拖动、可缩放的面板，含**完整会话转录** + 输入框 |
 *
 * ## 本轮升级的五件事（用户明确要求）
 *
 * ① **状态横条无变化 2 秒后自动消失** —— 见 [scheduleStatusAutoHide]。原来是常驻的，
 *    只要 AI 在跑就一直挂着，屏幕上永远有一块东西。现在只有**内容真的变化**才
 *    重新计时，2 秒内没有新内容就淡出。
 *
 * ② **拖出屏幕自动贴边折叠** —— 见 [enterEdge] / [onTouchUp]。判定用**未经夹取的
 *    原始坐标**（`rawX`），因为夹取逻辑会把越界量吃掉，用夹取后的值永远判不出来。
 *
 * ③ **展开显示完整转录** —— 见 [applyTranscript]。原实现只有一个 TextView 显示
 *    「最后一条助手正文的前 400 字」，用户在自己的悬浮窗里**看不到自己发过什么、
 *    也看不到 AI 调了哪些工具**。现在由 [ChatTranscript] 驱动逐条渲染：用户消息、
 *    助手完整回复、每一次工具调用、错误，全都在。
 *
 * ④ **面板可拖动、可缩放** —— 拖动走顶栏（[R.id.panelHeader]），缩放走底部手柄
 *    （[R.id.panelResize]）。位置与尺寸都落盘，下次开还是那样。
 *
 * ⑤ **插件可改外观** —— 见 [applyAppearance] 与 [BubbleAppearance]。任何页面内的
 *    插件都能 `window.DSHAndroid.bubbleApply('{"shape":"square","size":72}')`
 *    即时改变悬浮球，配置落盘、重启仍在。
 *
 * ## 三个必须知道的平台事实
 *
 * ### ① 权限是特殊的，拿不到就没救
 *
 * `SYSTEM_ALERT_WINDOW` 不能运行时请求。未授权时 `WindowManager.addView` 抛
 * `BadTokenException: permission denied for window type 2038`。所以 [show] 先查
 * [canDrawOverlays]，不满足就直接放弃（由设置页的引导卡负责引导用户去开）。
 *
 * ### ② 输入框要用 TYPE_APPLICATION_OVERLAY，且展开态不能带 FLAG_NOT_FOCUSABLE
 *
 * 悬浮窗默认**抢不到输入法焦点**：`FLAG_NOT_FOCUSABLE` 让整个 window 收不到按键，
 * 而带上它之后 `EditText` 又永远弹不出键盘。正确组合是：
 *
 * ```
 * type    = TYPE_APPLICATION_OVERLAY (API 26+) / TYPE_PHONE (更低)
 * flags   = FLAG_NOT_TOUCH_MODAL + 展开态去掉 NOT_FOCUSABLE   ← 见 applyFocusFlags
 * ```
 *
 * ⚠️ 展开态**不能**设 `FLAG_ALT_FOCUSABLE_IM` —— 那正是键盘弹不出来的原因
 *   （该标志的语义是「把 NOT_FOCUSABLE 对输入法的含义取反」）。详见 [applyFocusFlags]。
 *
 * ### ③ 拖动必须用绝对坐标，不能用 getRawX 的差值
 *
 * `WindowManager.updateViewLayout` 的 x/y 是相对**屏幕**的左上角，而 `getRawX()`
 * 是相对屏幕的触点。用 `rawX - downRawX` 得到的是位移量，必须**累加到按下时的
 * window 坐标**上。直接拿 `rawX` 当 x 会让气泡瞬移到手指位置，这是最容易踩的坑。
 *
 * ## 状态来源
 *
 * 两条通道，都由 `client.js` 推送：
 *
 * | 通道 | 入口 | 消费者 |
 * |---|---|---|
 * | [EngineStatus]（≤400 字，高频） | `mpReportStatus()` → [onStatus] | 通知栏 + 状态横条 |
 * | [ChatTranscript]（完整，变化才推） | `mpReportTranscript()` → [onTranscript] | 展开面板 |
 *
 * 实测 App 退到后台后 WebView 的 `setInterval` **照常运行**（按 HOME 后 12 秒走满
 * 12 拍），所以两条都是实时的，不需要另建引擎订阅。
 */
class FloatingBubble(private val context: Context) {

    companion object {
        private const val TAG = DshApplication.TAG

        /** 状态横条自动消失的默认时长（ms）。插件可用 `statusAutoHideMs` 覆盖。 */
        private const val STATUS_AUTO_HIDE_MS = 2000

        /**
         * AI 推送的宽限期（ms）。
         *
         * 推送自己的停留计时器比横条的自动消失计时器**晚这么久**才触发清理：
         * 两者如果同时到期，谁先跑是不确定的 —— 若清理先跑，它会用引擎状态覆盖
         * 刚推送的内容，用户看到的是"推送闪了一下就变回工具调用状态"。
         * 晚一点清，保证推送完整停留。
         */
        private const val STATUS_PUSH_GRACE_MS = 400L

        /**
         * 贴边态薄片的宽度（dp）。
         *
         * 取 6：够窄（不挡下层应用）、够宽（手指点得到）。实测 4dp 在 xxhdpi 上
         * 只有 12px，容易点不中。
         */
        private const val EDGE_WIDTH_DP = 6

        /**
         * 贴边后距屏幕边缘的留白（dp）。
         *
         * 取 0：用户拖动时本来就会自己找舒服的位置，强制留白反而"够不到边"。
         * 但**竖直方向**会夹在 [TOP_MARGIN_DP] 与状态栏之下，避免压住状态栏。
         */
        private const val EDGE_MARGIN_DP = 0

        /** 顶部留白（dp）：不让气泡压住状态栏（那里的时间和电量用户要常看）。 */
        private const val TOP_MARGIN_DP = 8

        /** 底部留白（dp）：避开手势条 / 导航栏。 */
        private const val BOTTOM_MARGIN_DP = 48

        /**
         * 键盘「危险区」占屏幕高度的比例。
         *
         * 球底边越过 `屏高 × (1 - 本值)` 就算会被键盘盖住。0.42 与
         * [keyboardHeightPx] 的 40% 估值同量级，留一点余量。
         */
        private const val IME_RISK_RATIO = 0.42f

        /**
         * 键盘顶飞的向上初速度（px/s，正数）。
         *
         * ## 这个值怎么定的
         *
         * 用户反馈「起飞的速度不够快，都没冲出键盘区域」。最初写 2100，按
         * `h = v²/(2g)`、`g = GRAVITY*密度 = 1400` 算：`2100²/2800 ≈ 1575 px`，
         * 而本机要逃的高度约 `2016 - 1329 ≈ 690 px` —— 理论上是够的。
         *
         * 但**主观速度**取决于前 100 ms 的位移量，而抛物线在起点附近爬升最快、
         * 随后迅速变慢；2100 在头 100 ms 只挪约 200 px，肉眼看就是「慢慢升」。
         * 提到 [IME_FLING_SPEED] 后头 100 ms 约 290 px，且顶点更高，才有「嗖」
         * 那一下。
         *
         * ⚠️ 上限受 [BubblePhysics.MAX_THROW_SPEED]（3600）约束，超过会被截断。
         */
        private const val IME_FLING_SPEED = 3300f

        /** 键盘顶飞时横向随机分量的幅度（px/s）。避免每次都笔直上冲显得死板。 */
        private const val SHAKE_RANDOM_VX = 260

        /**
         * 键盘可见时，球与键盘顶之间留的空隙（dp）。
         *
         * ⚠️ 不能沿用 [BOTTOM_MARGIN_DP]（48dp）：那个是给手势条 / 导航栏留的，
         * 键盘顶上本来就没有手势条。
         *
         * ⚠️ 也**不能只留几 dp**：键盘上方还有一条「文字选择工具栏」
         *   （`FloatingActionMode` 的剪切/复制/粘贴条，高约 48dp）。留 8dp 时球正好
         *   压在这条栏上，真机反馈"挡着文字选择栏了"。64dp = 工具栏 48dp + 余量，
         *   球停在工具栏**上面**。
         */
        private const val IME_FLOOR_PAD_DP = 64

        /** 被键盘盖住时额外弹起的高度（dp）—— 让"撞了一下"看得出来，而不只是挪开。 */
        private const val IME_BOUNCE_EXTRA_DP = 28

        /**
         * 弹射初速相对「刚好越过键盘」的放大倍数。
         *
         * 1.12 是留余量的系数，再乘 3 是**产品要求**：真机反馈原速"太肉"，
         * 看不出是"被弹开"，希望至少三倍。按 v² 关系，弹起高度约为原来的 9 倍，
         * 通常会一路顶到屏幕顶部再落回键盘上弹两下 —— 那正是想要的效果。
         *
         * ⚠️ 调这个值会同时改变"飞行时间"，而飞行期间用户可能已经在拖球了；
         *   拖动会 `stopThrow()`，所以不会打架，只是弹射会被打断。
         */
        private const val IME_BOUNCE_GAIN = 3.36f

        /**
         * 弹射的**水平分量**占竖直分量的比例 —— 让球斜着飞，而不是笔直上下。
         *
         * 方向固定朝**屏幕中心**：球在左半边就往右踢、在右半边往左踢。朝外踢的话
         * 它一出发就撞侧边（`WALL_DAMPING` 削掉 18%），看起来像被墙弹回来而不是
         * 被键盘弹开。
         *
         * 取 0.18 而不是 0.5：竖直速度已经是 3 倍，水平再大的话球会在左右墙之间
         * 来回撞，轨迹变得像弹珠机。0.18 ≈ 偏离垂直方向 10°，看得出斜、仍以
         * "上下"为主。
         */
        private const val IME_BOUNCE_SIDE_RATIO = 0.18f

        /**
         * 判定"球正靠在键盘顶上"的容差（dp）。
         *
         * 键盘收起时靠它决定要不要让球**掉下去**：只有真的贴着键盘顶的球才落下，
         * 用户自己放在屏幕中部的球不该因为键盘收起而开始下落。
         */
        private const val IME_REST_TOLERANCE_DP = 6

        /** 判定"点击"而非"拖动"的位移阈值（dp）。与系统 ViewConfiguration 同量级。 */
        private const val TAP_SLOP_DP = 6

        /**
         * 判定"拖出屏幕"的越界阈值（dp）。
         *
         * 取球宽的一半：**明显往外拖**才触发贴边。阈值太小会让正常的边缘吸附
         * 误判成贴边（用户只是想把球放到边上，结果球变成了一条缝）。
         */
        private const val EDGE_OVERSHOOT_DP = 28

        /** 转录区最小高度（dp）。再矮就看不见内容了。 */
        private const val TRANSCRIPT_MIN_DP = 72

        /** 面板最小宽度（dp）。 */
        private const val PANEL_MIN_WIDTH_DP = 180

        /** 位置与外观持久化。都是小整数/小 JSON，不值得上 DataStore。 */
        private const val PREFS = BubbleAppearance.PREFS
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        /** 展开面板的宽度（px）落盘 key；0/缺失表示用外观配置里的值。 */
        private const val KEY_PANEL_W = "panel_w"

        /** 转录区高度（px）落盘 key。 */
        private const val KEY_TRANSCRIPT_H = "transcript_h"

        /**
         * 随机链的兜底换动作间隔（ms）。
         *
         * ⚠️ 只对**循环播放**的动作起作用：一次性动作播完会自己触发 `ended`，
         *   这个定时器是给"待机动画永远循环、永远不结束"那种情况兜底的。
         *   8 秒是刻意的中值 —— 太短会让宠物像抽搐，太长就失去"活着"的感觉。
         */
        private const val CHAIN_INTERVAL_MS = 8000L

        /**
         * 单击判定为"点宠物"还是"点开面板"的**长按阈值**（ms）。
         *
         * ⚠️ 只有配了 `click` 池时才启用长按展开。没配池时单击照旧展开面板 ——
         *   否则老用户会发现"球点不开了"，那是破坏性的行为变更。
         */
        private const val LONG_PRESS_MS = 500L

        /** 自己走动的动画时长（ms）。走太快会像瞬移。 */
        private const val MOVE_MS = 900L

        /**
         * 点击回应姿势的保持时长（ms）。
         *
         * ⚠️ 必须有存活期：`click` 池的动作多是**循环**短片（`开心跃动` 那种），
         *   没有 `ended` 回调来结束它。不设期限的话宠物会永远停在"点击回应"上，
         *   AI 干完活也不回到 `actionMap` 的动作。
         */
        private const val CLICK_POSE_MS = 2500L

        /**
         * 气泡是否应当显示。
         *
         * 由 `MainActivity` 在 `onStart`/`onStop` 里设置 —— 只有**退到后台**才显示，
         * 前台时 App 内已有完整界面，气泡纯属碍事。
         *
         * ⚠️ 例外：插件把 `state` 设成 `expanded` / `collapsed` 时，**前台也显示**。
         *   那是插件在明确要求「把这个悬浮窗摆出来」，如果前台一律不收，插件就没法
         *   让用户看到自己改的效果 —— 而"让 AI 改悬浮窗"正是这条通道存在的理由。
         *   默认的 `auto` 保持既有行为（只在后台显示）。
         *
         * ⚠️ 做成进程级单例而不是每个 Activity 一份：`MainActivity` 转屏会重建，
         *   但气泡应当连续存在，不该闪一下。
         */
        @Volatile
        private var instance: FloatingBubble? = null

        /**
         * application context。
         *
         * ⚠️ 必须自己存一份：插件可能在**没有 Activity** 的时候下发配置
         *   （App 在后台、Activity 已销毁），而那时只有 [instance] 里的 context 可用，
         *   但 instance 可能为 null。存 application context 不泄漏。
         */
        @Volatile
        private var appContext: Context? = null

        /**
         * 用户是否在设置里开了气泡。默认**关**。
         *
         * 默认关的理由：它需要用户去系统设置里授权悬浮窗，没授权时开了也没用，
         * 不如让用户主动开一次、顺带走完授权流程。
         */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply()
            Log.i(TAG, "悬浮气泡开关: $enabled")
        }

        /** 系统是否已授予「显示在其他应用上层」。 */
        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true

        /**
         * 由 `MainActivity.onCreate` 调用一次，把 application context 记下来。
         *
         * ⚠️ **必须有这个入口，不能只在 [onAppBackground] 里赋值。** 真机实测踩到过：
         *   用户在前台（气泡还没显示过）时插件下发配置，[applyAppearance] 拿到的
         *   `appContext` 是 null ⇒ 直接 `return false`，配置**静默丢弃**。
         *   症状是「插件调了 bubbleApply，返回 false，什么也没发生」——
         *   而 App 一切到后台就又能用了，看起来像随机失效。
         */
        fun attach(context: Context) {
            val ctx = context.applicationContext
            appContext = ctx
            // 插件上次把形态设成了 expanded / collapsed（不是 auto）时，重启后也要
            // 摆出来 —— 否则「重启 App 后依然生效」这句承诺就只对外观成立、对形态
            // 不成立。`auto` 走 else 分支什么都不做，保持既有行为。
            main.post { reconcileVisibility(ctx, BubbleAppearance.load(ctx)) }
        }

        /** App 退到后台 → 显示气泡（前提：用户开了开关且已授权）。 */
        fun onAppBackground(context: Context) {
            if (!isEnabled(context)) return
            if (!canDrawOverlays(context)) {
                Log.i(TAG, "气泡开关已开但未授权悬浮窗，不显示")
                return
            }
            val ctx = context.applicationContext
            appContext = ctx
            if (BubbleAppearance.load(ctx).state == BubbleAppearance.STATE_HIDDEN) {
                Log.i(TAG, "插件要求隐藏悬浮球，不显示")
                return
            }
            main.post {
                if (instance?.isShown == true) return@post
                // 半路残留的实例（addView 失败、或转屏把 Activity 拆了）先收干净，
                // 否则旧 window 会一直挂在 WindowManager 上，用户看得见却点不动。
                instance?.hide()
                instance = FloatingBubble(ctx).also {
                    it.show()
                    // 球起来的这一刻，把 App 在前台期间攒下的余额快照贴上去。
                    // 没有这一步，徽标要等下一次页面刷新（点球/定时/轮末）才出现，
                    // 而用户回桌面后通常**什么都不会做**，就一直看不到余额。
                    lastBalanceSnapshot?.let { snap -> it.applyBalanceSnapshot(snap) }
                }
            }
        }

        /**
         * App 回到前台 → 收起气泡。
         *
         * ⚠️ 插件显式指定了形态（`state != auto`）时**不收** —— 见 [instance] 的说明。
         */
        fun onAppForeground() {
            val ctx = appContext ?: return
            if (BubbleAppearance.load(ctx).state != BubbleAppearance.STATE_AUTO) {
                Log.i(TAG, "插件已指定悬浮球形态，回到前台也不收起")
                return
            }
            main.post {
                instance?.hide()
                instance = null
            }
        }

        /** 设置页关掉开关时立即生效，不用等下次切后台。 */
        fun hideNow() = onAppForeground()

        /**
         * **锁屏/亮屏时暂停或恢复视频解码**（开发指导 §G6）。
         *
         * 文档：「**隐藏/锁屏/节能暂停无用解码与循环**」。
         *
         * ## ⚠️ 为什么需要单独一条路径
         *
         * `hide()` 会 `release()` 整个 WebView —— 那是「球不显示了」。
         * 但**锁屏时球仍在**（后台悬浮窗不受锁屏影响），WebView 继续解码。
         * 用户看不到任何东西，却在持续耗电。
         *
         * 实测场景：用户开着球锁屏睡觉，早上发现手机烫且掉了 20% 电。
         *
         * ⚠️ 用暂停而不是 release：解锁后要立刻能播，而 release 后重建
         * 要 200~500ms（重新加载页面 + 解码首帧），用户会看到黑一下。
         */
        fun onScreenLocked(context: Context) {
            val inst = instance ?: return
            inst.videoLayer?.pause()
            EventLog.record("bubble.screen.off")
        }

        /** 亮屏/解锁：恢复解码。 */
        fun onScreenUnlocked(context: Context) {
            val inst = instance ?: return
            inst.videoLayer?.resume()
            EventLog.record("bubble.screen.on")
        }

        /**
         * 状态更新入口。由 `EngineService.updateStatus()` 顺带调用。
         *
         * ⚠️ 可能在**任意线程**被调用（JS 桥线程），内部自己切主线程。
         */
        fun onStatus(status: EngineStatus) {
            val cur = instance ?: return
            main.post { cur.applyStatus(status) }
        }

        /**
         * 会话转录更新入口。由页面侧 `mpReportTranscript()` 推送。
         *
         * ⚠️ 与 [onStatus] 一样可能在任意线程被调用（JS 桥线程），内部切主线程。
         */
        fun onTranscript(json: String) {
            val cur = instance ?: return
            val transcript = ChatTranscript.parse(json) ?: return
            main.post { cur.applyTranscript(transcript) }
        }

        /**
         * 输入法窗口状态变化入口。由 [BubbleA11yService]（无障碍）推送。
         *
         * ⚠️ 与 [onStatus] 一样可能在**任意线程**被调用 —— 无障碍回调其实在
         *   主线程，但这里仍然统一 post 一次：调用方不该关心线程模型。
         *
         * @param visible 输入法窗口当前是否真的可见（服务已排掉"零高度/屏幕外"的假窗口）。
         * @param topPx   键盘顶在屏幕上的 y 坐标（px）。
         * @param heightPx 键盘高度（px）。
         */
        fun onImeWindow(visible: Boolean, topPx: Int, heightPx: Int) {
            // 无障碍回调本来就在主线程 —— 直接调用，省掉一次 post 的一帧；
            // 其它线程（将来若有推送）仍然走 post。
            val cur = instance
            if (cur != null && Looper.myLooper() == Looper.getMainLooper()) {
                cur.applyImeWindow(visible, topPx, heightPx)
            } else {
                main.post { instance?.applyImeWindow(visible, topPx, heightPx) }
            }
        }

        /**
         * 设置页改了「键盘避让」开关（[KeyboardAvoid.setEnabled] 推过来）。
         *
         * 关掉时只改内存里的开关，不打断正在飞的球 —— 打断会让人以为"点开关
         * 把球点炸了"。下一次落地/拖动就按新地面算。
         */
        fun onKeyboardAvoidChanged(enabled: Boolean) {
            val cur = instance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cur.applyKeyboardAvoidEnabled(enabled)
            } else {
                main.post { cur.applyKeyboardAvoidEnabled(enabled) }
            }
        }

        /**
         * 插件下发外观配置。**这是「让 AI 改悬浮窗」的唯一入口。**
         *
         * @param json 见 [BubbleAppearance] 的字段表；未知字段忽略、非法值退回原值。
         * @return 是否已接受（不代表视图已刷新 —— 那是下一帧的事）。
         */
        fun applyAppearance(json: String): Boolean {
            val ctx = appContext ?: return false

            // ── 事件闸门：拒绝迟到的旧事件（设计说明 §4.11）──────────────
            //
            // 文档要求「**不采用无规则的最后写入覆盖**」，验收矩阵第 18 条
            // 要求「**乱序或过期事件不覆盖当前状态**」。
            //
            // ⚠️ 为什么在这里拦而不是在视图层拦：这里是**唯一**的配置入口，
            //   在这里拦能把「旧事件」挡在落盘之前 —— 落到磁盘上再拦就晚了，
            //   因为下次启动会读到它。
            //
            // ⚠️ JSON 里可以带 `_seq` / `_lease`（可选）：
            //   不带时视为「当前租约 + 下一个序号」，即**普通调用照常生效**。
            //   只有显式带了旧值时才拒绝 —— 这样不会破坏现有调用方。
            val evLease: Long
            val evSeq: Long
            try {
                val probe = org.json.JSONObject(json)
                evLease = if (probe.has("_lease")) probe.optLong("_lease") else BubbleEventGate.currentLease()
                evSeq = if (probe.has("_seq")) probe.optLong("_seq") else BubbleEventGate.nextSeq()
            } catch (t: Throwable) {
                // JSON 本身坏了：交给下面的 merge 去处理（它会退回原值）
                return false
            }
            when (val verdict = BubbleEventGate.check(evLease, evSeq, BubbleEventGate.Source.ENGINE_PLUGIN)) {
                is BubbleEventGate.Verdict.Accept -> Unit
                is BubbleEventGate.Verdict.StaleSeq -> {
                    Log.i(DshApplication.TAG, "悬浮外观：拒绝迟到事件（seq ${verdict.got} ≤ ${verdict.current}）")
                    return false
                }
                is BubbleEventGate.Verdict.StaleLease -> {
                    Log.i(DshApplication.TAG, "悬浮外观：拒绝旧租约事件（lease ${verdict.lease} ≠ ${verdict.current}）")
                    return false
                }
            }

            // ── 插件仲裁（设计说明 §4.11）──────────────────────────────
            //
            // 文档：「同一窗口多插件竞争由基座仲裁，**不采用无规则的最后写入覆盖**」
            // 「用户手动设置优先级…插件重启**不能无条件覆盖用户后续修改**」
            //
            // ⚠️ 从 JSON 里取 `_plugin` 作为申请者标识（可选）：
            //   不带时视为「用户手动」（最高优先级）—— 因为不带标识的调用
            //   只可能来自页面桥或原生自身，那都是用户动作。
            val pluginId: String
            val requestedFields: List<String>
            try {
                val probe = org.json.JSONObject(json)
                pluginId = probe.optString("_plugin").ifEmpty { PluginArbiter.USER_ID }
                requestedFields = probe.keys().asSequence()
                    .filter { !it.startsWith("_") }
                    .toList()
            } catch (t: Throwable) {
                return false
            }

            // 申请控制权（用户永远能拿到；插件先到先得）
            if (pluginId == PluginArbiter.USER_ID) {
                PluginArbiter.acquireByUser()
            } else if (!PluginArbiter.acquire(pluginId)) {
                Log.i(DshApplication.TAG, "悬浮外观：$pluginId 未获得控制权，拒绝写入")
                return false
            }

            // 逐字段检查：用户锁定的字段插件改不动
            val blocked = requestedFields.filterNot { PluginArbiter.canWrite(pluginId, it) }
            if (blocked.isNotEmpty()) {
                Log.i(
                    DshApplication.TAG,
                    "悬浮外观：$pluginId 试图改被锁定的字段 ${blocked.joinToString(",")}（已剔除）",
                )
                // ⚠️ 剔除而不是整体拒绝：用户可能只想固定颜色，
                //   整体拒绝会废掉插件控制动作的能力。
            }

            val next = BubbleAppearance.merge(json, BubbleAppearance.load(ctx), ctx)
            // 自定义头像单独落盘：base64 有几百 KB，不适合进 SharedPreferences。
            //
            // ⚠️ **落盘后必须把内存里的 data URI 换成文件标记。** 视图判定用的是
            //   `usesCustomAvatar`（即 `avatar == AVATAR_FILE`），data URI 不匹配 ⇒
            //   下发自定义头像的那一帧**不显示**它，要等下次 `load()` 才生效，
            //   表现为「AI 换了头像但没反应，重启才看见」。
            //   换掉还能顺带避免几百 KB 的字符串一直挂在实例上。
            var merged = next
            if (next.avatar.startsWith("data:image/")) {
                merged = if (BubbleAppearance.writeAvatar(ctx, next.avatar) != null) {
                    next.copy(avatar = BubbleAppearance.AVATAR_FILE)
                } else {
                    // 图不是有效位图（writeAvatar 已记日志）：只把头像退回改之前的值，
                    // 不能留一个指向不存在文件的引用 —— 那会让球变成一块空白。
                    next.copy(avatar = BubbleAppearance.load(ctx).avatar)
                }
            }
            // 贴边图与头像**完全同构**：base64 落盘，内存里换成文件标记。
            // ⚠️ 不换标记的话，`usesEdgeImage()` 会认成"未知来源 ⇒ 用内置图"，
            //   表现为「AI 换了贴边图但没反应」，要等重启 `load()` 才生效。
            if (merged.edgeImage.startsWith("data:image/")) {
                merged = if (BubbleAppearance.writeEdgeImage(ctx, merged.edgeImage) != null) {
                    merged.copy(edgeImage = BubbleAppearance.EDGE_IMAGE_FILE)
                } else {
                    next.copy(edgeImage = BubbleAppearance.load(ctx).edgeImage)
                }
            }
            // 状态配图同理，只是它是一张二维表：逐档逐张过一遍 [BubbleStatusImages.ensure]。
            //
            // ⚠️ **`ensure` 而不是 `write`**：表里可能混着两种东西 —— 这次新传的
            //   路径/data URI，以及**上一次下发后回读出来的存储名**（插件做
            //   "读-改-写"时表里就是存储名）。用 `write` 会把存储名当路径去解析，
            //   失败后那一张就被剔掉，表现为「AI 只改了个尺寸，配图全没了」。
            merged = merged.copy(
                statusImages = merged.statusImages.map { tier ->
                    tier.mapNotNull { BubbleStatusImages.ensure(ctx, it) }
                },
            )
            BubbleAppearance.save(ctx, merged)
            Log.i(TAG, "插件下发悬浮球外观: state=${merged.state} shape=${merged.shape} size=${merged.size}")
            main.post {
                instance?.applyAppearance(merged)
                reconcileVisibility(ctx, merged)
            }
            return true
        }

        /**
         * AI 主动往状态气泡推一条消息。
         *
         * ⚠️ **不改持久配置**：这是"说一句话"，不是"改外观"。所以它不走
         *   `applyAppearance`、不落 SharedPreferences —— 否则下次启动气泡上会
         *   挂着上次推送的旧话。
         *
         * ⚠️ 气泡没显示时返回 false 而不是先把它显示出来：`state:"hidden"` 是
         *   插件明确要求"别出现"，为一条推送打破它是越权。
         *
         * @return 是否已推给一个正在显示的悬浮球。
         */
        fun pushStatusToActive(text: String, durationMs: Long, sticky: Boolean): Boolean {
            val cur = instance ?: return false
            main.post { cur.pushStatus(text, durationMs, sticky) }
            return true
        }

        /**
         * 直接切动作（桌面宠物用），**不改持久配置**。
         *
         * ⚠️ 与 [applyAppearance] 是两条路：那条改的是"这个球以后长什么样"（落盘、
         *   重启仍在），这条只影响"现在这一下演什么"。常驻状态桥插件每几秒就要报
         *   一次状态，走落盘那条会把 SharedPreferences 刷爆、也会让 AI 的配置被
         *   运行状态覆盖掉。
         *
         * ⚠️ 传空串 = 清掉临时钦定，回到 `actionMap`／配置里的动作。
         *
         * @return 是否已交给一个正在显示的悬浮球。
         */
        fun applyActionToActive(action: String): Boolean {
            val cur = instance ?: return false
            main.post { cur.setOverrideAction(action) }
            return true
        }

        /**
         * 按**运行状态**驱动动作（`session/event` 那一路的落点）。
         *
         * @param runState [BubbleAppearance.RUN_STATES] 之一；空串或未知值表示
         *   只切动作、不动运行状态。
         * @param action 该状态下要播的动作名；**null = 别动临时钦定的动作**，
         *   空串 = 清掉钦定（回到 `actionMap` 自动映射）。
         */
        /**
         * 余额信箱 → 活动实例。由 [BubbleCommandBridge.pollBalance] 调。
         *
         * @param snap 新快照。
         * @return 是否有活动实例接收（false 表示球没显示，这次数据先存着）。
         *
         * ## 为什么必须缓存，不能丢
         *
         * 原来这里球没显示就**直接丢弃**，理由是"余额是现在还剩多少，等球再次
         * 显示时该重新拉"。听起来合理，实际把主路径整个堵死了：
         *
         * - 余额的刷新发生在**页面活着的时候**，而页面活着 = App 在前台 = **球是隐藏的**
         *   （[onAppForeground] 会把 `instance` 置空）；
         * - 用户一按 Home 回桌面，球出现，但此刻没有任何东西会再推一次余额。
         *
         * 于是真机上的固定现象就是：日志明明写着「余额刷新成功: 1 个钱包」，
         * 但球旁边**永远没有徽标**。用户原话：「我点这个球，还是没有看到余额」。
         *
         * 所以改成：球不在就先**存一份最新的**，等球起来（[onAppBackground] 之后
         * 建窗）再贴上去。数据旧一点也远好过没有 —— 而且要的是"一回到桌面就能看见"。
         */
        fun applyBalanceToActive(snap: BalanceSnapshot): Boolean {
            lastBalanceSnapshot = snap
            val cur = instance ?: return false
            main.post { cur.applyBalanceSnapshot(snap) }
            return true
        }

        /**
         * 最近一次拿到的余额快照（球不在时先存着）。
         *
         * 球建窗时用它先把徽标画出来，随后页面侧的定时/点击刷新会覆盖成更新的值。
         */
        private var lastBalanceSnapshot: BalanceSnapshot? = null

        fun applyRunStateToActive(runState: String, action: String?): Boolean {
            val cur = instance ?: return false
            main.post {
                // ⚠️ 顺序要紧：先落钦定动作，再落运行状态。反过来的话，钦定动作还没
                //   生效时 [applyRunState] 会先按 `actionMap` 刷一次，紧接着钦定那一步
                //   又刷一次 —— 一次状态推送重启两遍轮播。
                if (action != null) cur.setOverrideAction(action)
                if (runState.isNotEmpty()) cur.syncRunStateFromPlugin(runState)
            }
            return true
        }

        /** 读回当前外观配置（JSON 文本）。插件据此做「读取-修改-写回」。 */
        fun currentAppearanceJson(): String {
            val ctx = appContext ?: return "{}"
            val a = BubbleAppearance.load(ctx)
            val action = instance?.frameAction ?: BubbleFrames.DEFAULT_ACTION
            return JSONObject().apply {
                put("state", a.state)
                put("shape", a.shape)
                // SVG 形状的 path 可能上千字符，读回时截断没意义（插件要靠它判断
                // "现在是不是同一个形状"），所以原样给。上限由 merge 侧把关。
                put("shapePath", a.shapePath)
                put("shapeViewBox", a.shapeViewBox)
                put("size", a.size)
                // ⚠️ `0`（自动）要**原样**读回，插件靠它区分「自动」与「钦定了多少」。
                put("boxWidth", a.boxWidth)
                put("boxHeight", a.boxHeight)
                put("showDot", a.showDot)
                // 解析后的实际画布尺寸（px → dp）—— 自动模式下插件读回这两个就知道
                // 「按素材自适应」到底算成了多少，不用自己再猜一遍。
                val (bx, by) = BubbleAppearance.boxPx(ctx, a, action)
                put("boxWidthPx", bx)
                put("boxHeightPx", by)
                put("cornerRadius", a.cornerRadius)
                put("opacity", a.opacity.toDouble())
                put("bgOpacity", a.bgOpacity.toDouble())
                put("avatarOpacity", a.avatarOpacity.toDouble())
                put("avatarScale", a.avatarScale.toDouble())
                put("bg", a.bg)
                put("bg2", a.bg2)
                put("border", a.border)
                put("borderWidth", a.borderWidth)
                put("avatar", if (a.usesCustomAvatar) "custom" else a.avatar)
                put("frameCount", a.frameCount)
                put("frameIntervalMs", a.frameIntervalMs)
                put("frameLoop", a.frameLoop)
                put("spriteRows", a.spriteRows)
                put("spriteCols", a.spriteCols)
                // ⚠️ `action` 是**空串也要给**：空串正是"自动跟随状态"的取值，
                //   插件要靠它判断"现在是不是自动模式"，省略掉会读成 undefined。
                put("action", a.action)
                put(
                    "actionMap",
                    JSONObject().apply {
                        for ((k, v) in a.actionMap) put(k, v)
                    },
                )
                // 磁盘上真实存在的动作名 —— AI 据此知道"现在能用哪些动作"，
                // 不用自己维护一份清单。
                //
                // ⚠️ **帧集与视频要分开报**：一个动作名可能在两边都存在（视频是
                //   主素材、帧集是兜底），也可能只有一边。合成一个列表会让 AI
                //   分不清"这个动作到底有没有视频"，从而反复重传素材。
                put("actions", JSONArray().apply {
                    for (n in BubbleFrames.actionNames(ctx)) put(n)
                })
                put("videoActions", JSONArray().apply {
                    for (n in BubbleVideos.actionNames(ctx)) put(n)
                })
                put("throwEnabled", a.throwEnabled)
                // ⚠️ 这三个是「动作手感」的开关，之前**漏了**，导致
                //   `bubbleAppearance()` 读回里没有它们 —— 前端 `ap.shakeEnabled`
                //   永远是 undefined ⇒ 设置页的「晃动甩飞」开关一直显示成关，
                //   点一下反而把它真正关掉（读回 undefined → !undefined === true
                //   只在一边成立，视觉与实际彻底错位）。
                //   **凡是 merge() 认的布尔开关，这里必须都读回一份。**
                put("shakeEnabled", a.shakeEnabled)
                put("imeFlingEnabled", a.imeFlingEnabled)
                // 余额徽标的配置 + 当前实际余额，**一起读回**。
                //
                // ⚠️ 配置与实际值必须同时给：AI 只看到配置就不知道"现在到底显示
                //   什么"，只看到数值又没法据此调整模板/位置。两者都要。
                put(
                    "balanceBadge",
                    JSONObject().apply {
                        val b = a.balanceBadge
                        put("enabled", b.enabled)
                        put("anchor", b.anchor)
                        put("gapDp", b.gapDp)
                        put("bg", b.bg)
                        put("fg", b.fg)
                        put("border", b.border)
                        put("borderWidthDp", b.borderWidthDp)
                        put("radiusDp", b.radiusDp)
                        put("fontSizeSp", b.fontSizeSp)
                        put("paddingHDp", b.paddingHDp)
                        put("paddingVDp", b.paddingVDp)
                        put("maxWidthDp", b.maxWidthDp)
                        put("template", b.template)
                        put("showCostFlash", b.showCostFlash)
                        put("hideWhenIdle", b.hideWhenIdle)
                        put("refreshOnTurnEnd", b.refreshOnTurnEnd)
                        put("refreshEverySec", b.refreshEverySec)
                        put("refreshOnTap", b.refreshOnTap)
                        put("tapRefreshMinIntervalSec", b.tapRefreshMinIntervalSec)
                    },
                )
                // 余额实际值（内存态）。没拉过时 `ready=false`，AI 据此知道要先拉。
                put("balance", instance?.balanceSnapshotJson()?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                } ?: JSONObject().put("ready", false))
                // 池、权重、状态文案、走动 —— 与 `actions` 同理：AI 据此知道
                // "现在配了哪些池"，不用自己维护一份清单。
                put(
                    "pools",
                    JSONObject().apply {
                        for ((k, v) in a.pools) put(k, JSONArray(v))
                    },
                )
                put(
                    "poolWeights",
                    JSONObject().apply {
                        for ((k, v) in a.poolWeights) put(k, v)
                    },
                )
                put(
                    "workStatusTexts",
                    JSONArray().apply {
                        for (tier in a.workStatusTexts) put(JSONArray(tier))
                    },
                )
                put("moveEnabled", a.moveEnabled)
                put("moveIntervalSec", a.moveIntervalSec)
                put("moveMinDp", a.moveMinDp)
                put("moveMaxDp", a.moveMaxDp)
                // 状态配图读回时给的是**绝对路径**，不是内部存储名。
                //
                // ⚠️ 这一步是"读-改-写"能不能成立的关键：插件拿到的东西会原样
                //   回传，而它只能读工作区里的路径（存储名 `ab12….png` 对它是个
                //   不存在的相对路径，会报"图片不可用"）。给绝对路径它就能读成
                //   data URI 再传回来，原生侧 [BubbleStatusImages.ensure] 按内容
                //   摘要算出同一个存储名 ⇒ 幂等，不会复制出一堆重复文件。
                put(
                    "statusImages",
                    JSONArray().apply {
                        for (tier in a.statusImages) {
                            put(
                                JSONArray().apply {
                                    for (name in tier) {
                                        BubbleStatusImages.file(ctx, name)?.let { put(it.absolutePath) }
                                    }
                                },
                            )
                        }
                    },
                )
                put("statusImageSizeDp", a.statusImageSizeDp)
                put("flipOnTurn", a.flipOnTurn)
                put(
                    "moveParams",
                    JSONObject().apply {
                        for ((action, p) in a.moveParams) {
                            put(
                                action,
                                JSONObject().apply {
                                    // ⚠️ null 的键**不写**：写 0 会让"跟随全局"变成
                                    //   "原地踏步"，那是两种完全不同的行为。
                                    p.minDp?.let { put("minDp", it) }
                                    p.maxDp?.let { put("maxDp", it) }
                                },
                            )
                        }
                    },
                )
                // ⚠️ 这两个只有"球正在显示"时才有意义：当前随机链抽中的动作，
                //   以及当前抽中的状态文案（没配则为空串）。
                put("chainAction", instance?.chainAction ?: "")
                put("statusText", instance?.workStatusText ?: "")
                // ⚠️ 这两个只有"球正在显示"时才有意义（取自实例，不是配置）：
                //   `runState` 是当前运行状态，`playingAction` 是**实际**在播的动作
                //   （可能与配置里的 `action` 不同 —— 指向的动作没帧时会回退）。
                put("runState", instance?.runState ?: "")
                put("playingAction", instance?.frameAction ?: "")
                put("spin", a.spin)
                put("pulse", a.pulse)
                put("panelWidth", a.panelWidth)
                put("panelHeight", a.panelHeight)
                put("panelBg", a.panelBg)
                put("panelText", a.panelText)
                put("panelAccent", a.panelAccent)
                put("statusAutoHideMs", a.statusAutoHideMs)
                put("edgeSnap", a.edgeSnap)
            }.toString()
        }

        /**
         * 按插件要求的形态决定要不要显示/隐藏窗口。
         *
         * 只在 [applyAppearance] 之后调 —— 它的语义是「配置变了，把窗口状态跟上去」。
         */
        private fun reconcileVisibility(ctx: Context, a: BubbleAppearance) {
            if (!isEnabled(ctx) || !canDrawOverlays(ctx)) return
            when (a.state) {
                BubbleAppearance.STATE_HIDDEN -> {
                    instance?.hide()
                    instance = null
                }
                BubbleAppearance.STATE_EXPANDED -> {
                    if (instance?.isShown != true) {
                        instance?.hide()
                        instance = FloatingBubble(ctx).also { it.show() }
                    }
                    instance?.expand()
                }
                BubbleAppearance.STATE_COLLAPSED -> {
                    if (instance?.isShown != true) {
                        instance?.hide()
                        instance = FloatingBubble(ctx).also { it.show() }
                    }
                    instance?.collapse()
                }
                else -> Unit
            }
        }

        /**
         * 发送文本的回调。由 `MainActivity` 注入 —— 气泡自己拿不到 WebView。
         *
         * 返回 true 表示已投递（不代表 AI 已回复）。
         */
        @Volatile
        var sendText: ((String) -> Boolean)? = null

        /**
         * 中断当前这一轮的回调。由 `MainActivity` 注入。
         *
         * 返回 true 表示已投递。页面侧会先验「主按钮此刻是不是停止键」，
         * 不是的话拒绝执行 —— 绝不能把用户的草稿误发出去。
         */
        @Volatile
        var stopRun: (() -> Boolean)? = null

        /** 打开 App 的回调（气泡面板上那个「打开」）。 */
        @Volatile
        var openApp: (() -> Unit)? = null

        /** 主线程 Handler，所有对 View / window 的操作都走它。 */
        private val main = Handler(Looper.getMainLooper())

        /**
         * 球的默认底色。
         *
         * ## 为什么是**全透明**而不是白色
         *
         * 用户反复提（原话：「为什么设置是透明 实际是白色，说了多少次了」、
         * 「我说的是我们悬浮球那个头像的背景，那个圆圈」）。
         *
         * 原来这里是 `0xFFFFFFFF`（纯白），于是不管设置页怎么显示「透明」，
         * 头像外面那圈永远是**一块白底**，压在桌面上像个白色圆贴纸。
         *
         * 现在改成全透明：球只显示头像本身，和桌面贴在一起。
         * 需要可见轮廓时由 [DEFAULT_BORDER] 那圈淡描边承担 —— 那本来就是
         * 描边的职责，不该靠一块不透明底把整个圆涂满。
         */
        const val DEFAULT_BALL_BG = 0x00000000

        /** 默认描边（8% 黑，与 XML 里的 `#14000000` 一致）。 */
        const val DEFAULT_BORDER = 0x14000000

        /**
         * 球投影的高度（dp），与 `bubble_window.xml` 的 `android:elevation` 一致。
         *
         * ⚠️ 必须是个常量并与 XML 同步：代码里要按 `bgOpacity` 把它压到 0（背景全
         *   透明时不该留投影），两处写不一致会让"背景透明了还有灰影"复现。
         */
        const val ELEVATION_DP = 8f

        /**
         * 默认面板底色（= 配置里 `panelBg` 为空、「自动」时用它）。
         *
         * ## 为什么是**完全透明**而不是某种白色
         *
         * 用户的诉求原话：「我就是要你选择自动的时候，它就变成透明啊。因为不然的话，
         * 你那个色板里面也没有透明这个选项，那用户怎么办？」
         *
         * 这句把约束说全了：`<input type="color">` **只能选不透明色**，页面上
         * 根本给不出「透明」这个选项。所以「自动」是用户表达"我不要自己上色"的
         * **唯一入口**，它必须承担"透明"这个语义 —— 否则用户永远做不出透明面板。
         *
         * 顺带解决观感不一致：设置页把「自动」画成一个**棋盘格**（透明的通用符号），
         * 而面板此前是死白，用户看到的符号与实际效果对不上。
         *
         * ⚠️ 完全透明时**必须同时去掉描边和投影**，否则会看到"一圈没有底色的边框
         *   浮在屏幕上"，比白板更难看。见 [panelBackground] 与 `applyPanelChrome`。
         *
         * ⚠️ 文字色不能跟着透明：没有底色的面板上，文字必须有足够对比度
         *   （[DEFAULT_TEXT] 是深灰，在浅色背景上可读；`panelBg` 透明时底下是
         *   用户的壁纸，深浅不定 —— 所以透明模式下要给文字加一层浅色阴影，
         *   见 [applyPanelColors]）。
         */
        const val DEFAULT_PANEL_BG = 0x00000000

        /** 默认正文色。 */
        const val DEFAULT_TEXT = 0xFF1A1D21.toInt()

        /** 默认强调色（与「发送」按钮的 `#3B6FE0` 一致）。 */
        const val DEFAULT_ACCENT = 0xFF3B6FE0.toInt()

        /** 运行中状态点的颜色（原实心绿）。 */
        const val DEFAULT_RUNNING = 0xFF34C759.toInt()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var root: View? = null
    private var circleView: View? = null
    private var avatarView: ImageView? = null

    /**
     * 视频层宿主。默认 GONE，只有在**磁盘上真有视频素材**时才会创建 WebView 并显示。
     *
     * 见 [BubbleVideoLayer] 与 [applyContentLayer] 的优先级说明。
     */
    private var videoHost: View? = null

    /** 视频渲染后端。惰性创建 —— 没有视频素材的用户不会有这个 WebView。 */
    private var videoLayer: BubbleVideoLayer? = null
    private var panelView: View? = null
    private var dotView: View? = null
    private var panelDot: View? = null
    private var panelAction: TextView? = null
    private var panelTitle: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelTranscript: LinearLayout? = null
    private var panelInput: EditText? = null
    private var panelStop: View? = null
    private var panelHeader: View? = null
    private var panelResize: View? = null
    private var panelSend: TextView? = null

    /** 贴边态薄片与它上面的小点。 */
    private var edgeView: View? = null

    /**
     * 贴边态的图片层。
     *
     * ⚠️ 与 [edgeView] 分开持有：贴边态现在**可配图片**（默认是探头形象），
     *   而 `edgeView` 的背景是纯色薄片（`edgeImage == "none"` 时才用它）。
     *   两者叠在同一个 FrameLayout 里，靠 visibility 切换。
     */
    private var edgeImageView: ImageView? = null

    /** [applyEdgeImage] 的去重指纹（来源 + 尺寸）。没变就不重设图片。 */
    private var edgeImageKey: String = ""
    private var edgeDot: View? = null

    /**
     * 轮播播放器的当前帧下标。
     *
     * ⚠️ 跨 `applyAppearance()` 保留：AI 只改了个颜色时不该让轮播从头开始 ——
     *   那在用户眼里是"球闪了一下"。
     */
    private var frameIndex = 0

    /** 轮播已完成的循环数，用于 `frameLoop`。 */
    private var frameLoops = 0

    /** 下一次切帧的 Runnable（用来取消）。null = 没有在跑。 */
    private var frameRunnable: Runnable? = null

    /**
     * 当前正在播的动作名。
     *
     * ⚠️ 与 [BubbleAppearance.action] 不是一回事：那个是**配置里的意愿**，
     *   这个是**实际在播的那个**（意愿指向的动作没帧时会退回默认动作）。
     *   帧下标 [frameIndex] 只对同一个动作有意义，所以换动作时必须把它清零。
     */
    private var frameAction = BubbleFrames.DEFAULT_ACTION

    /** 当前运行状态（[BubbleAppearance.RUN_IDLE] 等），由引擎状态推导。 */
    private var runState = BubbleAppearance.RUN_IDLE

    /**
     * **随机动作链**当前抽中的动作（空串 = 没抽中，走 `actionMap`）。
     *
     * ⚠️ 它**不进配置、不落盘**：这是"这一次播什么"的临时状态，与
     *   `actionMap`（AI 配好的映射）是两回事。落盘会让宠物每次重启都从
     *   同一个动作开始。
     *
     * ⚠️ 只在 [runState] 为 idle 时被 [wantedAction] 采信 —— 干活时必须演
     *   "正在干活"，详见那里的注释。
     */
    private var chainAction = ""

    /** 随机链里"连抽同一个动作"的次数，用来避免连着播两遍同一段动画。 */
    private var chainRepeats = 0

    /**
     * [chainAction] 是从 `turn` 池抽出来的吗（播完要翻转朝向）。
     *
     * ⚠️ **必须记下来，不能事后从池里反查**：同一个动作名可能既在 `idle` 池又在
     *   `turn` 池里（AI 图省事直接复用素材）。反查会把它一律判成"要转身"，
     *   于是待机时宠物会莫名其妙地自己翻面。
     */
    private var chainIsTurn = false

    /**
     * 宠物当前是否**面朝右**（false = 面朝左，与 dsh-pet 的默认值一致）。
     *
     * 素材只画了一个方向，所以"转身"只能靠水平镜像（`scaleX = -1`）表达 ——
     * 这与 dsh-pet 的做法逐字相同（见其 `pet.ts` 的 `el.style.transform =
     * facingRef.current === 'right' ? 'scaleX(-1)' : ''`）。
     */
    private var facingRight = false

    /** 下一次抽随机动作的 runnable（球隐藏 / 干活时必须取消，否则白转）。 */
    private var chainRunnable: Runnable? = null

    /** 自己走动：下一次挪位置的 runnable。 */
    private var moveRunnable: Runnable? = null

    /**
     * 当前运行状态对应的**配置文案**（`workStatusTexts` 抽中的那一句，空串 = 没配）。
     *
     * ⚠️ **在状态切换时抽一次并缓存**，不能在每次渲染时抽：`applyStatus` 每秒都推，
     *   每次渲染都重抽会让那句话**每秒闪一次**，看起来像坏了。
     */
    private var workStatusText = ""

    /**
     * 当前运行状态**专属池**抽中的动作（空串 = 没配这个状态的池）。
     *
     * ⚠️ 与 [workStatusText] 同一套规矩：**状态变化时抽一次并缓存**。
     *   在 [wantedAction] 里现抽会让每次调用拿到不同动作（那里被调用多次），
     *   表现为宠物动作乱跳。
     */
    private var statePoolAction = ""

    /**
     * 当前运行状态的**配图路径**（空串 = 没配）。
     *
     * ⚠️ 同样只在状态变化时抽一次。图片字节由 `BubbleStatusImages` 落在
     *   `filesDir/bubble-status-images/` 下，这里存的是落盘后的文件名。
     */
    private var statusImagePath = ""

    /** 按下时刻（`eventTime`），用于区分"单击"与"长按"。 */
    private var downAt = 0L

    /** 长按定时器（配了 `click` 池时才用）。 */
    private var longPressRunnable: Runnable? = null

    /** 当前是否正演着拖拽姿势（松手时要还原）。 */
    private var draggingPose = false

    /**
     * **用户直接操作**（点/拖/走）引起的临时姿势（空串 = 没有）。
     *
     * ⚠️ 它必须与 [chainAction] **分开**，而且**不受 [runState] 限制**。
     *   这两条都是真机踩出来的：
     *   - `chainAction`（随机链）只在 idle 时生效是对的 —— 干活时宠物该演"正在干活"，
     *     随机玩耍动作会演反。
     *   - 但**用户的手**不是"AI 在忙就该忽略"的东西。AI 每时每刻都在 thinking/working
     *     （那正是用户发消息的时候），若把点/拖/走也挂在 `runState == idle` 上，
     *     用户**永远**点不出回应、拖不出姿势、看不到走动 —— 实测就是"2、4、5 全都没有"。
     *
     * 所以：手动操作压过运行状态，且有自己的存活期（见 [manualUntil]）。
     */
    private var manualAction = ""

    /** [manualAction] 的失效时刻（`uptimeMillis`，0 = 一直有效直到显式清除）。 */
    private var manualUntil = 0L

    /** [manualAction] 的到期定时器。 */
    private var manualRunnable: Runnable? = null

    /** 正在跑的"自己走动"动画（用户一按就要停掉，否则和拖拽抢坐标）。 */
    private var walkAnim: android.animation.ValueAnimator? = null

    /**
     * 脚本/AI **临时**钦定的动作（不走持久配置）。
     *
     * ⚠️ 优先级低于 [BubbleAppearance.action]（那是落盘的明确意愿）、高于
     *   `actionMap` 的自动映射 —— 否则"配了状态映射"就等于"脚本再也控制不了动作"。
     */
    private var stateOverrideAction = ""

    /** 上一次因为"动作不存在"而回退时警告过的动作名，避免每帧刷日志。 */
    private var warnedMissingAction = ""

    /** 旋转动画是否已在跑（避免重复 start）。 */
    private var spinning = false

    /** 呼吸动画是否已在跑。 */
    private var pulsing = false

    /** 正在跑的旋转动画；null = 没在跑。改参数时要先 cancel 它。 */
    private var spinAnimator: ObjectAnimator? = null

    /** 当前旋转动画的每圈时长与终值，用来判断"配置真的变了没"。 */
    private var spinDuration = 0L
    private var spinTo = 0f

    /** 正在跑的呼吸动画（X / Y 各一个，必须同步）。 */
    private var pulseAnimator: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null
    /** 视频层的呼吸动画（X/Y 各一个，与图片层同频同相）。 */
    private var pulseVideoX: ObjectAnimator? = null
    private var pulseVideoY: ObjectAnimator? = null

    /** 当前呼吸动画的基准缩放，用来判断"配置真的变了没"。 */
    private var pulseBase = 0f

    /** 当前是否展开。 */
    private var expanded = false

    /** AI 主动推送的文案；空串 = 没有推送，横条回到引擎状态。 */
    private var pushedStatus = ""

    /** 推送是否常驻（不自动消失）。 */
    private var pushedSticky = false

    /** 推送的停留时长（ms）；0 = 用外观配置里的 `statusAutoHideMs`。 */
    private var pushedDurationMs = 0L

    /** 是否处于贴边态。 */
    private var edgeCollapsed = false

    /**
     * 贴边在哪一侧：-1 = 左，+1 = 右。
     *
     * ⚠️ 必须记住而不是每帧重算：薄片贴着左边时它的中心在屏幕左侧，
     *   重算会得到「离左边更近」，看似等价；但薄片**宽度只有 6dp**，
     *   用户点它之后要弹回完整球，弹到哪一侧得看用户当初往哪边拖的，
     *   不能靠"现在离哪边近"倒推（球比薄片宽得多，结论会反）。
     */
    private var edgeSide = 1

    /** 是否已 addView（避免重复添加 / 重复移除）。 */
    private var attached = false

    /** 当前 window 的左上角坐标（px）。拖动时以它为基准累加位移。 */
    private var curX = 0
    private var curY = 0

    /**
     * 拖动中**未经夹取**的 window 坐标（px）。
     *
     * ⚠️ 这是「拖出屏幕自动贴边」判定的**唯一依据**。用 [curX]/[curY] 判不出来 ——
     *   它们已经被 [clampInto] 夹进可见区域，越界量在那一步就丢了，永远为 0。
     */
    private var rawX = 0
    private var rawY = 0

    /** 拖动状态。 */
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var moved = false

    /** 缩放状态。 */
    private var resizing = false
    private var downWidth = 0
    private var downHeight = 0

    // ── 抛掷物理 ──────────────────────────────────────────────────────

    /** 抛掷物理（按设备密度构造，保证不同 DPI 手感一致）。 */
    private val physics by lazy { BubblePhysics(density) }

    /** 手指轨迹采样（用来估抛掷速度）。 */
    private val trail = BubblePhysics.Trail()

    /** 当前飞行状态；null = 没在飞。 */
    private var throwState: BubblePhysics.State? = null

    /** 飞行循环的 Runnable（用来取消）。 */
    private var throwRunnable: Runnable? = null

    /** 上一帧的时间戳（ns），用来算 dt。 */
    private var throwLastFrameNs = 0L

    // ── 晃动甩飞 ──────────────────────────────────────────────────────

    /**
     * 加速度计监听：快速晃手机 ⇒ 球被甩飞。
     *
     * 用 lazy 是因为它依赖 `context`，而 `context` 在构造函数里就有；放在这里只是
     * 为了跟其它子系统保持同一排版。真正 `start()` 在 [show] 里。
     */
    private val shakeDetector by lazy { ShakeDetector(context) }

    // ── 键盘顶飞 ──────────────────────────────────────────────────────

    /**
     * 键盘探针。**惰性构造**，且 [ImeProbe.start] 只在「球停稳且位于危险区」时调用。
     *
     * ⚠️ 单次 `dumpsys input_method` 95–150 ms，绝不能常驻轮询 —— 见 [ImeProbe] 的说明。
     */
    private val imeProbe by lazy {
        ImeProbe(context) { cmd ->
            // ⚠️ **必须走 Shizuku 的 uid 2000**。`dumpsys input_method` 要求
            //   `android.permission.DUMP`，普通 app uid 直接被拒：
            //   `Permission Denial: can't dump InputMethodManagerService ... uid=10347`。
            //   这里曾经写成 `ProcessBuilder("/system/bin/sh", "-c", cmd)` —— 那是在
            //   **app uid** 下跑的，必然读不到，表现为「Shizuku 已授权但功能仍不可用」。
            runCatching { ShizukuShell.exec(cmd) }.getOrNull()
        }
    }

    /** 键盘探针是否已挂上回调（避免重复设置）。 */
    private var imeProbeWired = false

    /** 落地挤压动画（X / Y 各一个，必须同步）。 */
    private var squashAnim: android.animation.ValueAnimator? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()

    private val tapSlop = dp(TAP_SLOP_DP)

    /** 最近一次状态，展开面板时用它填充。 */
    private var lastStatus: EngineStatus? = null

    /** 最近一次转录。展开时用它填充（球形态下不渲染，省一次 View 树重建）。 */
    private var lastTranscript: ChatTranscript? = null

    /** 转录脏标记：球形态下收到新转录只置位，展开时才真正重建 View。 */
    private var transcriptDirty = true

    /** 已渲染的转录指纹，用来跳过重复渲染（状态每秒推一次，无脑重建会一直重排）。 */
    private var transcriptFingerprint = ""

    /**
     * 当前 [panelTranscript] 里逐行对应的 role 序列。
     *
     * 流式输出时每秒要重渲染好几次，用来判断能不能**复用已有行**（只改文本）：
     * role 序列一致 ⇒ 复用，避免 `removeAllViews()` 造成的整块闪烁。
     */
    private var renderedRoles: List<String> = emptyList()

    /** 当前外观配置。所有尺寸/颜色/形状都从这里取。 */
    private var appearance: BubbleAppearance = BubbleAppearance()

    // ══ 收起态的状态气泡（球上方那个小标签）══════════════════════════════
    //
    // 独立 window。理由见 layout/bubble_status.xml 的头注释：塞进球的 window
    // 会把 window 撑大，而 window 左上角是定位锚点 —— 球本身的位置会跟着偏。
    private var statusRoot: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null

    /** 状态配图（球头顶那张表情包）。没配 `statusImages` 时一直是 GONE。 */
    private var statusImage: ImageView? = null

    /** 「点 + 文字」那一行。只有配图没文案时整行收起（否则留一条空白）。 */
    private var statusTextRow: View? = null

    // ══════════════════════════════════════════════════════════════════
    // 余额徽标（独立 window，与球平级）
    // ══════════════════════════════════════════════════════════════════
    //
    // ⚠️ 与状态气泡是**两个完全不同的东西**，不要合并：
    //   状态气泡是「AI 正在干什么」（转瞬即逝、走 notify/state 通道），
    //   余额徽标是「还剩多少钱」（常驻、由 BalanceBadge 配置、跟着球走）。
    //   合并会让"刷新余额"把正在显示的状态消息冲掉。

    /** 徽标窗口里的 View。null = 没创建（未启用或球没显示）。 */
    private var balanceRoot: BalanceBadgeView? = null

    /** 上一次渲染的文字，用于判重与"花费跳一下"。 */
    private var balanceLastText = ""

    /** 上一次渲染时的花费值，用于判断"要不要跳一下"。 */
    private var balanceLastCost: java.math.BigDecimal = java.math.BigDecimal.ZERO

    /**
     * 当前余额快照。**只存内存**（理由见 [BalanceSnapshot] 的头注释）。
     *
     * 初始为 null：这表示"还没拉取过"，徽标会保持隐藏 —— 而不是显示一个
     * 编造的 `¥0.00`。用假数据填充比不显示更糟，用户会以为真的没钱了。
     */
    private var balanceCurrent: BalanceSnapshot? = null

    /** 状态横条自动消失的计时器。 */
    private val statusHandler = Handler(Looper.getMainLooper())

    /**
     * 状态横条当前显示的「内容指纹」。
     *
     * ⚠️ 自动消失的**判据就是它变不变**：内容变了才重新计时。用户要的是
     *   「没有变化时 2 秒后自动消失」，所以状态每秒刷新但文字相同**不算**变化，
     *   计时器继续跑，2 秒后照样消失。见 [scheduleStatusAutoHide]。
     */
    private var statusKey = ""

    /**
     * 键盘高度变了（展开态把面板顶上去，保证输入框露在键盘之上）。
     *
     * ⚠️ 由**输入框焦点**驱动，不是由系统 insets 驱动：悬浮窗拿不到 IME insets
     *   （`TYPE_APPLICATION_OVERLAY` 不在 `adjustResize` 的适用范围里，API 29 上
     *   也没有 `WindowInsets.Type.ime()`）。所以用「输入框聚焦 ⇒ 键盘即将弹出」
     *   这个等价条件来预留空间。实测在这台机器上成立。
     */
    private fun applyImeHeight(heightPx: Int) {
        if (imeHeightPx == heightPx) return
        imeHeightPx = heightPx
        if (!expanded) return
        val (nx, ny) = clampInto(curX, curY)
        moveTo(nx, ny)
    }

    // ══════════════════════════════════════════════════════════════════
    // 键盘避让：无障碍看到真实输入法窗口之后，让球"被键盘弹开"
    // ══════════════════════════════════════════════════════════════════

    /**
     * 收到一次输入法窗口变化（来自 [BubbleA11yService]）。
     *
     * 三种情形：
     * 1. **键盘弹出、球被盖住** ⇒ 向上弹射（见 [bounceOffKeyboard]）；
     * 2. **键盘收起、球正靠在键盘顶上** ⇒ 交给重力把它带下去（见 [dropFromKeyboard]）；
     * 3. 其余情况只更新坐标，不动视图。
     *
     * ⚠️ 展开态 / 贴边态 / 正在拖动时**一律不弹**：
     * - 展开态由输入框焦点驱动让位（[applyImeHeight]），再弹一次会两套逻辑打架；
     * - 贴边态是一条贴在左右边缘的薄片，"弹射"没有可读的方向；
     * - 拖动中弹射会从用户手指底下把球抢走。
     */
    private fun applyImeWindow(visible: Boolean, topPx: Int, heightPx: Int, force: Boolean = false) {
        val wasVisible = imeWindowVisible
        val oldTop = imeWindowTopPx
        if (!force && wasVisible == visible && oldTop == topPx) return
        imeWindowVisible = visible
        imeWindowTopPx = if (visible) topPx else 0

        // 功能开关关掉时只记状态、不做动作：地面也不该被键盘抬高（见 maxWindowY）。
        if (!keyboardAvoidEnabled) return
        // 贴边态不参与键盘避让：它贴的是屏幕左右边缘，不是底部，键盘盖不到它。
        if (expanded || edgeCollapsed || dragging) return
        if (visible) {
            // ⚠️ 飞行中不再重复给初速：键盘弹出的那段动画里 `top` 会变几次，
            //   每次变化都会走到这里 —— 不挡住的话要么把球反复"重新发射"（观感
            //   是原地抽搐），要么在落地附近反复注入能量。飞行中它自己会落在
            //   新的地面（键盘顶）上，不需要外力。
            if (throwState == null) bounceOffKeyboard()
        } else if (wasVisible) {
            dropFromKeyboard(oldTop)
        }
    }

    /**
     * 设置页改了「键盘避让」开关。
     *
     * - 关掉：不打断当前动作，但地面立刻回到屏幕底（下一次拖动/落点按新状态算）。
     * - 打开：如果键盘正开着，立刻按真实键盘顶避让一次 —— 否则用户要等下一次
     *   键盘弹出才看到效果，会以为开关没生效。
     */
    private fun applyKeyboardAvoidEnabled(enabled: Boolean) {
        keyboardAvoidEnabled = enabled
        Log.i(TAG, "键盘避让开关生效: $enabled")
        if (enabled && ImeWindowState.visible) {
            applyImeWindow(true, ImeWindowState.topPx, ImeWindowState.heightPx, force = true)
        }
    }

    /**
     * 球被键盘盖住时，给它一个向上的初速度，让它"被弹开"再落回键盘顶上。
     *
     * ## 为什么是算速度而不是直接改坐标
     *
     * 直接 `moveTo` 是瞬移，观感是"球被谁拽走了"；而这里要的是**碰撞**：
     * 球被键盘顶面顶飞、升到顶点、再落回键盘上弹两下（落地挤压由物理循环里的
     * `step.landed` 自动触发，不需要另写动画）。
     *
     * 初速度按能量守恒反推：要升高 [rise] 像素，`v = sqrt(2·g·rise)`。
     * 乘 1.12 留一点余量（阻尼/掉帧会吃掉一点高度），再压到 [BubblePhysics]
     * 的抛掷软上限之内 —— 超过上限的那一档本来就会被物理层重新缩放。
     */
    private fun bounceOffKeyboard() {
        val v = root ?: return
        val h = v.height.takeIf { it > 0 } ?: boxH()
        val w = v.width.takeIf { it > 0 } ?: boxW()
        val floorY = maxWindowY(h)
        // 球顶已经在键盘上方：没被盖住，什么都不做。
        if (curY <= floorY) return

        val rise = (curY - floorY) + dp(IME_BOUNCE_EXTRA_DP)
        val v0 = (kotlin.math.sqrt(2f * physics.gravity * rise) * IME_BOUNCE_GAIN)
            .coerceAtMost(physics.maxThrowSpeed)
        // 水平分量：朝屏幕中心踢（球在左半边往右、右半边往左），让轨迹斜着出去。
        val ballCenter = curX + w / 2f
        val toCenter = if (ballCenter <= context.resources.displayMetrics.widthPixels / 2f) 1f else -1f
        val vx = v0 * IME_BOUNCE_SIDE_RATIO * toCenter
        Log.i(
            TAG,
            "键盘弹起：键盘顶 $imeWindowTopPx px（高 ${ImeWindowState.heightPx} px），" +
                "球底 ${curY + h} px 低于可用下限 ${floorY + h} px ⇒ " +
                "向上弹射 v=(${vx.roundToInt()}, ${(-v0).roundToInt()}) px/s",
        )
        // 起跳前先"蹲"一下：与落地挤压共用同一条 Q 弹曲线，手感连贯。
        playSquash(0.88f)
        startThrow(vx, -v0)
    }

    /**
     * 键盘收起后，原本靠在键盘顶上的球交给重力落下去。
     *
     * ⚠️ 只处理**真的停在键盘顶上**的球（容差 [IME_REST_TOLERANCE_DP]）：
     *   用户自己放在屏幕中部的球不该因为键盘收起而开始下落 —— 那会变成
     *   "一收键盘球就自己往下掉"，与"扔到哪就待在哪"的产品语义冲突。
     */
    private fun dropFromKeyboard(oldTopPx: Int) {
        val v = root ?: return
        val h = v.height.takeIf { it > 0 } ?: boxH()
        val restedOnKeyboard = abs(curY - keyboardRestY(oldTopPx, h)) <= dp(IME_REST_TOLERANCE_DP)
        if (!restedOnKeyboard) return
        Log.i(TAG, "键盘收起：球原本停在键盘顶 $oldTopPx，交给重力落回底部")
        startThrow(0f, 0f)
    }

    /** 键盘顶 [topPx] 时，球顶应该停在哪（px）。 */
    private fun keyboardRestY(topPx: Int, h: Int): Int =
        (topPx - h - dp(IME_FLOOR_PAD_DP)).coerceAtLeast(dp(TOP_MARGIN_DP))

    /**
     * 当前可用区域的**底边**（px）。
     *
     * 键盘可见时是键盘顶；否则是屏幕底减去学到的键盘高度（[imeHeightPx]）。
     */
    private fun usableBottomY(): Int {
        val dm = context.resources.displayMetrics
        // 开关关掉时假装没看见键盘：球可以停在屏幕最底部（旧行为）。
        return if (keyboardAvoidEnabled && imeWindowVisible && imeWindowTopPx > 0) imeWindowTopPx
        else dm.heightPixels - imeHeightPx
    }

    /**
     * 球顶允许到达的最低 y（px）—— 物理循环的地面与拖动夹取共用这一个判据。
     *
     * ⚠️ 必须**统一**：物理循环用一个地面、`clampInto` 用另一个的话，会出现
     *   "拖的时候停在键盘上、松手却又弹到别处"这种自相矛盾的行为。
     */
    private fun maxWindowY(h: Int): Int {
        val pad = if (keyboardAvoidEnabled && imeWindowVisible) dp(IME_FLOOR_PAD_DP)
        else dp(BOTTOM_MARGIN_DP)
        return (usableBottomY() - h - pad).coerceAtLeast(dp(TOP_MARGIN_DP))
    }

    /**
     * 键盘高度（px）。
     *
     * 优先用 MainActivity 学到的**真实值**（它按「输入法高度 + 朝向」落盘，
     * 见 `MainActivity.saveImeHeight`）；取不到就按屏高 40% 估一个。
     * 估值的风险是留多了面板偏高，但比"输入框被键盘盖住"轻得多 ——
     * 后者会让用户完全看不见自己在打什么。
     */
    private fun keyboardHeightPx(): Int {
        val sp = context.getSharedPreferences(MainActivity.IME_PREFS, Context.MODE_PRIVATE)
        val learned = sp.getInt(MainActivity.imePrefsKeyOf(context), 0)
        if (learned > 0) return learned
        return (context.resources.displayMetrics.heightPixels * 0.4f).roundToInt()
    }

    /** 展开前（收起态）的位置。收起时用它还原 —— 见 `expand()` 里的说明。 */
    private var preExpandX = 0
    private var preExpandY = 0

    /** 防重入守卫：`positionStatusBubble` 会改 layoutParams，可能再触发 layout 回调。 */
    private var positioning = false

    /**
     * 输入法当前遮住屏幕底部多少像素（0 = 键盘没弹）。
     *
     * ⚠️ 悬浮窗**不会**被系统自动顶上去：`adjustPan`/`adjustResize` 只对 Activity 的
     *   window 生效，`TYPE_APPLICATION_OVERLAY` 不在其中。真机实测（华为 CDY-TN90 /
     *   Android 10）：键盘弹出后输入框被完全盖住，用户看不见自己打了什么。
     *   所以高度由 MainActivity 观测到之后**推**过来，这里自己挪。
     */
    @Volatile
    private var imeHeightPx = 0

    /**
     * 无障碍观测到的**真实**输入法窗口状态。
     *
     * 与 [imeHeightPx] 的分工：
     * - [imeHeightPx] 是「输入框拿到焦点」推出来的**估计值**，用于展开态提前让位；
     * - 这两个是系统窗口列表里的**事实**，键盘真的弹出来/收回去时才会变。
     *
     * ⚠️ 不能拿 [imeHeightPx] 代替这一对：展开态里它由焦点驱动，收起态里它恒为 0
     *   —— 而收起态的球正是最需要知道"键盘挡在哪"的那一种。
     */
    @Volatile
    private var imeWindowVisible = false

    /** 键盘顶的 y 坐标（px）；[imeWindowVisible] 为 false 时无意义。 */
    @Volatile
    private var imeWindowTopPx = 0

    /**
     * 「键盘避让」功能开关（设置页可关，默认开）。
     *
     * 缓存一份而不是每帧读 SharedPreferences：`maxWindowY()` 在物理循环里每帧
     * 都被调用，读盘会直接反映成掉帧。开关变化由 [onKeyboardAvoidChanged] 推入。
     */
    @Volatile
    private var keyboardAvoidEnabled = true


    /** 状态气泡距球顶部的间距（dp）。 */
    private val statusGapPx = dp(8)

    /** 状态气泡的宽度上限（dp）。 */
    private val statusMaxWidthPx = dp(200)

    /** 状态标签的字符数上限。标签是单行小标签，太长会把球顶得很宽。 */
    private val statusMaxChars = 60

    // ══════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════

    @SuppressLint("InflateParams")
    fun show() {
        if (attached) return
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "未授予悬浮窗权限，气泡不显示")
            return
        }

        appearance = BubbleAppearance.load(context)
        // 每次显示都重新读一次功能开关：设置页可能在气泡隐藏期间改过它。
        keyboardAvoidEnabled = KeyboardAvoid.isEnabled(context)

        val view = LayoutInflater.from(context).inflate(R.layout.bubble_window, null)
        root = view
        circleView = view.findViewById(R.id.bubbleCircle)
        avatarView = view.findViewById(R.id.bubbleAvatar)
        videoHost = view.findViewById(R.id.bubbleVideoHost)
        panelView = view.findViewById(R.id.bubblePanel)
        dotView = view.findViewById(R.id.bubbleDot)
        panelDot = view.findViewById(R.id.panelDot)
        panelAction = view.findViewById(R.id.panelAction)
        panelTitle = view.findViewById(R.id.panelTitle)
        panelScroll = view.findViewById(R.id.panelScroll)
        panelTranscript = view.findViewById(R.id.panelTranscript)
        panelInput = view.findViewById(R.id.panelInput)
        panelStop = view.findViewById(R.id.panelStop)
        panelHeader = view.findViewById(R.id.panelHeader)
        panelResize = view.findViewById(R.id.panelResize)
        panelSend = view.findViewById(R.id.panelSend)
        edgeView = view.findViewById(R.id.bubbleEdge)
        edgeDot = view.findViewById(R.id.edgeDot)
        edgeImageView = view.findViewById(R.id.edgeImage)

        paintDot(dotView, running = false)
        paintDot(panelDot, running = false, small = true)
        paintDot(edgeDot, running = false, small = true)

        // 位置：优先用上次记住的；没有就贴右侧中部
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val sidePx = boxW()
        curX = prefs.getInt(KEY_X, screenW - sidePx - dp(EDGE_MARGIN_DP))
        curY = prefs.getInt(KEY_Y, (screenH * 0.42f).roundToInt())

        applyAppearanceToViews()
        restorePanelSize()
        clampInto(curX, curY).let { curX = it.first; curY = it.second }

        installTouch(circleView!!)
        installEdgeTouch(edgeView!!)
        installPanelDrag(panelHeader!!)
        installPanelResize(panelResize!!)
        installPanelActions(view)

        val params = baseParams().apply { x = curX; y = curY }
        try {
            wm.addView(view, params)
            attached = true
            // 只有球在场时才让无障碍服务兜底轮询键盘（见 ImeWindowState.keepPolling）。
            ImeWindowState.setKeepPolling(true)
            Log.i(TAG, "气泡已显示: ($curX, $curY) shape=${appearance.shape} size=${appearance.size}")
            // 主动拉一次当前状态：气泡是刚创建的，错过了之前所有的推送，
            // 不拉的话会停在"空闲"直到下一次状态变化（可能几十秒后）。
            EngineService.currentStatus()?.let { applyStatus(it) }
            // 键盘此刻已经弹着时（从别的 App 切过来、或者球刚被重新创建），
            // 也得按真实坐标摆一次 —— 否则球会先出现在键盘底下，等下一次
            // 窗口变化事件才被"弹"出来。
            if (ImeWindowState.visible) {
                applyImeWindow(true, ImeWindowState.topPx, ImeWindowState.heightPx)
            }
            // 插件要求的初始形态（非 auto 时生效）。
            when (appearance.state) {
                BubbleAppearance.STATE_EXPANDED -> expand()
                BubbleAppearance.STATE_COLLAPSED -> Unit
                else -> Unit
            }
            // 随机链与自己走动：**显示之后**才起（hide 里已经把它们取消了）。
            workStatusText = BubbleAppearance.statusTextFor(appearance, runState) ?: ""
            statePoolAction = BubbleAppearance.statePoolPick(context, appearance, runState) ?: ""
            statusImagePath = BubbleAppearance.statusImageFor(appearance, runState) ?: ""
            scheduleChain(CHAIN_INTERVAL_MS)
            scheduleMove()
            // 晃动甩飞：加速度计是普通传感器，零权限，装上就能用。
            startShakeDetection()
            // 键盘顶飞：只有拿到特权通道才起探针（否则 BubbleAppearance 已把它置 false）。
            startImeWatching()
            // 余额徽标：球显示出来之后才有"旁边"可言。
            renderBalanceBadge()
        } catch (t: Throwable) {
            // 最常见的是没权限（BadTokenException）。不致命，只是没气泡。
            Log.w(TAG, "添加气泡失败: ${t.message}")
            root = null
        }
    }

    fun hide() {
        if (!attached) return
        val v = root ?: return
        attached = false
        ImeWindowState.setKeepPolling(false)
        hideStatusBubble()
        // 停掉轮播与动效：视图都要移除了，继续 postDelayed 只是空转耗电，
        // 而且回调里会碰到已经 detach 的 ImageView。
        stopFramePlayer()
        // 视频层要彻底释放：WebView 持有解码器与渲染线程，只把 View 摘掉不 destroy
        // 会让它继续占内存，反复显示/隐藏几次就是几十 MB 的泄漏。
        videoLayer?.release()
        videoLayer = null
        // 飞行中的球也要停下：视图都要移除了，继续 postOnAnimation 会碰到 null root。
        stopThrow()
        // 传感器与探针同理：视图都要移除了，继续监听只是白耗电。
        stopShakeDetection()
        stopImeWatching()
        // 徽标是独立窗口，球没了它必须一起消失，否则会孤立地挂在屏幕上。
        removeBalanceBadge()
        squashAnim?.cancel()
        squashAnim = null
        spinning = false
        pulsing = false
        spinAnimator?.cancel()
        spinAnimator = null
        spinDuration = 0L
        spinTo = 0f
        pulseAnimator?.cancel()
        pulseAnimatorY?.cancel()
        pulseAnimator = null
        pulseAnimatorY = null
        pulseBase = 0f
        // 推送标记也要清：下次显示气泡时若还留着旧推送，横条会先冒出一句
        // 上一轮的旧话，看起来像"卡住了"。
        statusHandler.removeCallbacks(pushedClearRunnable)
        pushedStatus = ""
        pushedSticky = false
        pushedDurationMs = 0L
        // 随机链与走动的定时器同理：View 都要摘掉了，继续 postDelayed 只会空转，
        // 回调里还会碰到已 detach 的 root。
        cancelChain()
        cancelMove()
        stopWalk()
        cancelLongPress()
        clearManualAction()
        chainAction = ""
        workStatusText = ""
        statePoolAction = ""
        statusImagePath = ""
        runCatching { wm.removeViewImmediate(v) }
        root = null
        circleView = null
        avatarView = null
        videoHost = null
        panelView = null
        panelInput = null
        edgeView = null
        expanded = false
        edgeCollapsed = false
    }

    // ══════════════════════════════════════════════════════════════════
    // 外观（插件可控）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 应用一份外观配置。
     *
     * ⚠️ 全程 try/catch：气泡是锦上添花，一份写错的配置不能让它消失。
     */
    fun applyAppearance(next: BubbleAppearance) {
        val prev = appearance
        appearance = next
        try {
            // ⚠️ **插件改了面板尺寸就清掉用户的手动拖动记录。**
            //
            //   原逻辑里 `panelWidth` / `panelHeight` 的取值是：
            //     prefs 里有拖动记录 ⇒ 用记录值（插件配置被永久忽略）
            //   于是用户只要拖过一次缩放手柄，之后再怎么调设置页的
            //   「面板宽度 / 面板高度」都**完全没反应** —— 真机反馈为
            //   「面板尺寸调节无效」，而且看起来像滑杆坏了。
            //
            //   修法：插件下发的配置里尺寸**变了**，就当成"这次以插件为准"，
            //   清掉拖动记录。没变则不动（保留用户的微调）。
            val sizeChanged = next.panelWidth != prev.panelWidth ||
                next.panelHeight != prev.panelHeight
            if (sizeChanged) {
                prefs.edit()
                    .remove(KEY_PANEL_W)
                    .remove(KEY_TRANSCRIPT_H)
                    .apply()
                Log.i(
                    TAG,
                    "面板尺寸改为插件配置: width=${next.panelWidth} height=${next.panelHeight}" +
                        "（已清除手动拖动记录）",
                )
            }

            applyAppearanceToViews()
            // ⚠️ **按新尺寸夹，不能读 `root.width`。** 这里刚改完 layoutParams，
            //   布局还没重测，`root.width` 仍是**旧**尺寸（可能是贴边态的 6dp，
            //   也可能是上一版的小球）。拿它算夹取范围，AI 一把球改大，球就会
            //   跑出屏幕右/下边缘 —— 用户看到的是"球消失了"。
            //   收起态尺寸就是画布尺寸，直接用它算。展开态不能用球尺寸
            //   （面板宽得多），交给下面那一帧用真实布局尺寸夹。
            if (!expanded) {
                val side = boxW()
                val sideH = boxH()
                clampInto(curX, curY, side, sideH).let { moveTo(it.first, it.second) }
            }
            // 展开态尺寸由面板决定，要等布局出来才知道；下一帧再夹一次兜住。
            root?.post {
                val (nx, ny) = clampInto(curX, curY)
                moveTo(nx, ny)
            }
            // 配置变了 → 随机链与走动的定时器都要按**新配置**重排：AI 可能刚把
            // `moveEnabled` 打开、或刚配上一批池。不重排的话这次改动要等下一次
            // 状态切换才生效，表现为"配了但宠物不动"。
            //
            // ⚠️ 先清 `chainAction`：它指向的是**旧配置**里的池成员，新配置可能
            //   根本没这个池，留着会让宠物卡在一个不该播的动作上。
            chainAction = ""
            workStatusText = BubbleAppearance.statusTextFor(appearance, runState) ?: ""
            statePoolAction = BubbleAppearance.statePoolPick(context, appearance, runState) ?: ""
            statusImagePath = BubbleAppearance.statusImageFor(appearance, runState) ?: ""
            scheduleChain(CHAIN_INTERVAL_MS)
            scheduleMove()
        } catch (t: Throwable) {
            Log.w(TAG, "应用悬浮球外观失败: ${t.message}")
        }
    }

    /** 把 [appearance] 刷到各个 View 上。 */
    private fun applyAppearanceToViews() {
        val boxW = boxW()
        val boxH = boxH()
        val dm = context.resources.displayMetrics

        // ── 球：尺寸、形状、底色、描边 ─────────────────────────────────
        circleView?.let { c ->
            (c.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = boxW
                lp.height = boxH
                c.layoutParams = lp
            }
            // 形状层：SVG path 优先，否则退回圆/方/圆角。
            c.background = ballBackground()
            // 背景层的透明度**单独**作用在背景 drawable 上，与整体 opacity 相乘 ——
            // 这正是「背景透明、图片不透明」能成立的原因。
            c.background?.alpha = (appearance.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
            // 形状可以不是圆的，内容要跟着裁（见 clipToShape 的注释）。
            clipToShape(c)
            // ⚠️ **投影必须跟着背景一起消失。**
            //   `elevation` 的那圈灰影由 **outline 形状**画，**完全不看背景 drawable
            //   的 alpha** —— 所以 `bgOpacity:0` 只把卡片涂透明，灰影照旧留在屏幕上。
            //   用户报的"背景都透明了还留一圈灰影"就是这个：四组对照（改 bg 颜色、
            //   改 shape、把 shapePath 扩满画布、把画布归零）之所以全对不上，是因为
            //   它压根不属于任何一个外观字段，而是 `bubble_window.xml` 里写死的
            //   `android:elevation="8dp"`。把 elevation 与 `bgOpacity` 绑在一起，
            //   背景全透明时就没有投影。
            c.elevation = if (appearance.bgOpacity > 0f) ELEVATION_DP * density else 0f
            // 状态点跟着球缩放：球小到 24dp 时 14dp 的点会占掉一大半。
            // ⚠️ 默认 `showDot = false` —— 那个绿点压在宠物形象上很碍眼。
            dotView?.let { d ->
                d.visibility = if (appearance.showDot) View.VISIBLE else View.GONE
                val dotPx = (appearance.size * 0.25f).roundToInt().coerceIn(8, 18)
                (d.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(dotPx)
                    lp.height = dp(dotPx)
                    d.layoutParams = lp
                }
            }
        }

        // ── 内容层：内置头像 / 自定义图片 / 动作帧集 / 精灵图 ───────────
        applyActionLayer(boxReq())

        // ── 贴边薄片：尺寸与图片都由外观决定 ───────────────────────────
        //
        // ⚠️ 原实现宽度写死 `EDGE_WIDTH_DP = 6`，真机反馈「太小了，点不到」——
        //   6dp × 44dp 在 3x 屏上只有 18×132 像素。现在宽度走 `edgeWidth`
        //   （默认 28dp），并且可以显示图片。
        edgeView?.let { e ->
            val edgeW = dp(edgeWidthDp())
            val edgeH = if (appearance.edgeHeight > 0) dp(appearance.edgeHeight) else boxH
            (e.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = edgeW
                lp.height = edgeH
                e.layoutParams = lp
            }
            // ⚠️ 显示图片时**不能有背景与投影**。
            //
            //   `bubble_edge_bg` 是「白色圆角矩形 + 1dp 灰描边」，原本是纯色薄片
            //   的底；`elevation = 8dp` 又给整个 FrameLayout 加了一层投影。
            //   图片叠在这两者之上时，人物周围会套一个带阴影的圆角框 ——
            //   真机反馈为「碰撞箱边缘有暗影」，看起来像图片被裁进了一个框里。
            //
            //   所以：有图 ⇒ 背景置空、投影归零；无图（`edgeImage == "none"`）
            //   ⇒ 恢复成原来的纯色薄片。
            if (usesEdgeImage()) {
                e.background = null
                e.elevation = 0f
            } else {
                e.background = edgeBackground()
                e.background?.alpha = (appearance.bgOpacity.coerceIn(0f, 1f) * 255).roundToInt()
                e.elevation = dp(8).toFloat()
            }
        }
        // ⚠️ 贴边态的状态点**跟着 `showDot` 走**，与球上那个点同一套开关。
        //
        //   原实现让它无条件可见，而它 `layout_gravity="center"` 正好压在探头
        //   人物的脸上 —— 真机反馈「那个圆圈挡到角色了」。
        //   显示图片时尤其碍眼：图片本身已经表达了形象，再叠一个灰点没有信息量。
        edgeDot?.visibility = if (appearance.showDot) View.VISIBLE else View.GONE
        applyEdgeImage()

        // ── 整体不透明度（与两个分层透明度相乘）─────────────────────────
        root?.alpha = appearance.opacity.coerceIn(0.05f, 1f)

        // ── 动效层：旋转 / 呼吸 ────────────────────────────────────────
        applyMotion()

        // ── 面板：宽度、配色 ───────────────────────────────────────────
        panelView?.let { p ->
            val w = prefs.getInt(KEY_PANEL_W, 0).takeIf { it > 0 }
                ?: appearance.effectivePanelWidth(dm.widthPixels, density)
            (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = w.coerceIn(dp(PANEL_MIN_WIDTH_DP), dm.widthPixels)
                p.layoutParams = lp
            }
            p.background = panelBackground()
        }

        applyPanelColors()

        // ── 状态横条宽度上限随球走 ─────────────────────────────────────
        statusText?.maxWidth = statusMaxWidthPx
    }

    /**
     * 按运行状态选一个动作，再交给 [applyContentLayer]。
     *
     * 选择优先级（桌面宠物的"该做哪个动作"就这三条）：
     * ① 配置里的 `action` 非空 → 就播它（AI 明确点名，压过一切）；
     * ② 脚本临时钦定的 [stateOverrideAction] → 播它；
     * ③ `actionMap[runState]` → 按当前运行状态自动映射；
     * ④ 都没有 → [BubbleFrames.DEFAULT_ACTION]。
     *
     * ⚠️ **要的那套帧不在磁盘上就退回默认动作**，并把 [frameAction] 记成实际播的
     *   那个。若不退回：`actionMap` 配了 `thinking` 但用户没传过 thinking 的帧，
     *   球会**整个变空白**（帧文件找不到），比"动作没换"严重得多。
     */
    private fun applyActionLayer(side: Int) {
        val playable = resolvePlayableAction(wantedAction())
        var req = side
        if (playable != frameAction) {
            // ⚠️ 换动作必须清零帧下标：下标是"第几个动作里的第几帧"，
            //   沿用旧下标会从新动作的中间开始播（甚至越界）。
            frameIndex = 0
            frameLoops = 0
            frameAction = playable
            // ⚠️ 动作换了 ⇒ **素材可能也换了** ⇒ 自适应画布尺寸要跟着重算。
            //   不能沿用调用方算好的 `side`：那是**旧动作**的素材比例算出来的，
            //   拿它去解码新素材，长宽比一变就糊（或浪费一倍内存）。
            refreshBoxSize()
            req = boxReq()
        }
        applyContentLayer(req)
    }

    /**
     * 只把**画布尺寸**刷到受影响的 View 上（不重走整个外观装配）。
     *
     * 用在「动作切换导致自适应画布尺寸变化」这一处 —— 那里只需要改尺寸，
     * 背景 drawable、透明度、动效、面板都不该被重刷。
     */
    private fun refreshBoxSize() {
        val w = boxW()
        val h = boxH()
        circleView?.let { c ->
            (c.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    c.layoutParams = lp
                }
            }
        }
        edgeView?.let { e ->
            (e.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.height != h) {
                    lp.height = h
                    e.layoutParams = lp
                }
            }
        }
    }

    /**
     * 当前**想要**播的动作名（还没核对磁盘上有没有）。
     *
     * 选择优先级（桌面宠物的"该做哪个动作"就这四条）：
     * ① 配置里的 `action` 非空 → 就播它（AI 明确点名，压过一切）；
     * ② 脚本临时钦定的 [stateOverrideAction] → 播它；
     * ③ 随机链抽中的 [chainAction]（**只在 idle 时生效**）→ 播它；
     * ④ `actionMap[runState]` → 按当前运行状态自动映射；
     * ⑤ 都没有 → [BubbleFrames.DEFAULT_ACTION]。
     *
     * ⚠️ ③ 排在 ④ 前面但**只在 idle 时**生效，这是关键：干活时宠物必须演
     *   "正在干活"，而随机链抽中的多半是个玩耍小动作 —— 不限制的话 AI 一忙
     *   起来宠物反而在玩，正好演反。
     */
    private fun wantedAction(): String = when {
        appearance.action.isNotEmpty() -> appearance.action
        stateOverrideAction.isNotEmpty() -> stateOverrideAction
        // ②.5 用户手动操作（点/拖/走）的姿势 —— **压在运行状态前面**：
        //   用户的手比 AI 的忙闲重要，且这类姿势有存活期，过期自动让位。
        isManualAlive() -> manualAction
        runState == BubbleAppearance.RUN_IDLE && chainAction.isNotEmpty() -> chainAction
        // ③ 状态专属池（`work-thinking` / `state-working` …）优先于 `actionMap`：
        //   池能"每次进这个状态换个花样"，而 `actionMap` 只能固定一个动作。
        //   两者都配时池赢 —— 配池是更明确、更花力气的表达。
        //
        // ⚠️ 读的是**已经抽好的** [statePoolAction]，不在这里现抽。
        //   本函数被调用多次（`applyRunState` 判一次、`applyActionLayer` 再判一次），
        //   在这里现抽会让两次拿到不同动作 —— 表现为宠物动作不停乱跳。
        //   抽取时机与 `workStatusText` 完全一致：**状态变化时抽一次**。
        statePoolAction.isNotEmpty() -> statePoolAction
        else -> BubbleAppearance.actionFor(appearance, runState, BubbleFrames.DEFAULT_ACTION)
    }

    /** [manualAction] 现在是否还有效（顺带把过期的清掉）。 */
    private fun isManualAlive(): Boolean {
        if (manualAction.isEmpty()) return false
        if (manualUntil == 0L) return true
        if (android.os.SystemClock.uptimeMillis() < manualUntil) return true
        clearManualAction()
        return false
    }

    /**
     * 演一个**用户操作**引起的临时姿势。
     *
     * @param action 动作名（空串 = 清掉）。
     * @param holdMs 保持多久（0 = 一直保持，直到调用方显式清掉，拖拽用这个）。
     */
    private fun playManualAction(action: String, holdMs: Long) {
        manualRunnable?.let { root?.removeCallbacks(it) }
        manualRunnable = null
        manualAction = action
        manualUntil = if (holdMs > 0) android.os.SystemClock.uptimeMillis() + holdMs else 0L
        if (holdMs > 0) {
            val r = Runnable {
                manualRunnable = null
                clearManualAction()
                // 姿势到期 → 交回给状态映射/随机链。
                applyActionLayer(boxReq())
            }
            manualRunnable = r
            root?.postDelayed(r, holdMs)
        }
        applyActionLayer(boxReq())
    }

    /** 清掉手动姿势（不重刷视图，调用方自己决定要不要刷）。 */
    private fun clearManualAction() {
        manualRunnable?.let { root?.removeCallbacks(it) }
        manualRunnable = null
        manualAction = ""
        manualUntil = 0L
    }

    /**
     * 由引擎状态推导**运行状态**（宠物该做哪一类动作）。
     *
     * ⚠️ 这是**粗粒度**那一路：页面每 1 秒才采一次，短于一秒的工具调用会整个漏掉。
     *   细粒度那一路是 `dsh-android-pet` 插件订阅引擎的 `session/event`，
     *   它走 [applyRunState] 直接点名动作 —— 两条路最终都落到同一处切换。
     *
     * ⚠️ 判定顺序要紧：「等待确认」这类文案往往**同时**含"运行命令"之类的词
     *   （审批就是从工具调用里弹出来的），先判工具就会把"等你确认"显示成"干活中"。
     */
    private fun deriveRunState(status: EngineStatus): String {
        if (!status.running) return BubbleAppearance.RUN_IDLE
        val act = status.activity.lowercase()
        val text = status.text
        if (text.contains("等待") || text.contains("确认") ||
            act.contains("approval") || act.contains("wait") || act.contains("input")
        ) {
            return BubbleAppearance.RUN_WAITING
        }
        if (act.contains("tool") || act.contains("command") ||
            text.startsWith("正在运行") || text.contains("执行了")
        ) {
            return BubbleAppearance.RUN_WORKING
        }
        return BubbleAppearance.RUN_THINKING
    }

    /**
     * 引擎状态刷新时顺带更新运行状态，并（在自动模式下）让动作跟上。
     *
     * ⚠️ **动作没变就不重刷内容层**：状态每秒推一次，无条件重刷会把正在播的那一轮
     *   打断（每帧都从当前下标重新排一次 postDelayed）—— 表现为"宠物动作一卡一卡"。
     */
    private fun syncRunState(status: EngineStatus) {
        // ── 多会话聚合（设计说明 §4.11 / 验收矩阵第 18 条）──────────────
        //
        // 文档要求「多会话事件有**明确跟随/聚合规则**」。
        //
        // ⚠️ 先把这份状态喂进聚合器，再由聚合器选「该显示哪个」：
        //   直接 applyRunState(deriveRunState(status)) 是「最后写入者赢」——
        //   多个会话并发时报的状态会互相覆盖，用户看到球在「干活中」与
        //   「等你确认」之间乱跳，而实际上有个会话**一直在等他确认**。
        //
        // ⚠️ sessionId 为空时的处理：**照旧走单状态路径**。
        //   空串意味着页面拿不到当前会话（首帧、官方 DOM 变了、旧版页面），
        //   这时如果硬塞进聚合器，会让「无归属状态」参与竞争，
        //   反而把真实会话的状态挤掉。
        val sid = status.sessionId
        if (sid.isEmpty()) {
            applyRunState(deriveRunState(status))
            return
        }

        // 把这份状态转成聚合器认识的形式
        val kind = when (deriveRunState(status)) {
            BubbleAppearance.RUN_WAITING -> SessionAggregator.KIND_WAITING
            BubbleAppearance.RUN_WORKING -> SessionAggregator.KIND_WORKING
            BubbleAppearance.RUN_THINKING -> SessionAggregator.KIND_THINKING
            else -> SessionAggregator.KIND_IDLE
        }
        SessionAggregator.update(sid, status.running, kind, status.text)

        // 由聚合器选代表
        val rep = SessionAggregator.representative()
        if (rep == null) {
            // 所有会话都空闲 ⇒ 显示 idle
            applyRunState(BubbleAppearance.RUN_IDLE)
            return
        }
        // 把聚合器的结论映射回动作
        applyRunState(
            when (rep.kind) {
                SessionAggregator.KIND_WAITING -> BubbleAppearance.RUN_WAITING
                SessionAggregator.KIND_WORKING -> BubbleAppearance.RUN_WORKING
                SessionAggregator.KIND_THINKING -> BubbleAppearance.RUN_THINKING
                else -> BubbleAppearance.RUN_IDLE
            },
        )
    }

    /**
     * 落到一个新的运行状态，并（在自动模式下）让动作跟上。
     *
     * ⚠️ **动作没变就不重刷内容层**：状态每秒推一次，无条件重刷会把正在播的那一轮
     *   打断（每帧都从当前下标重新排一次 postDelayed）—— 表现为"宠物动作一卡一卡"。
     *
     * ⚠️ 两条状态通道（页面采样 / 插件事件）都必须走这里。曾经插件那一路只改
     *   [runState] 而不刷新，理由是"动作名会紧接着设"—— 但插件**可以只报状态不带
     *   动作名**（那是它的常态），于是 `actionMap` 永远不生效，表现为
     *   "runState 跟着变、宠物动作纹丝不动"。
     */
    private fun applyRunState(next: String) {
        if (next == runState) return
        runState = next
        // 状态变了 → 重抽一次状态文案。**必须在这里抽而不是渲染时抽**：
        // applyStatus 每秒推一次，渲染时抽会让那句话每秒闪一次。
        workStatusText = BubbleAppearance.statusTextFor(appearance, next) ?: ""
        // 同理：状态专属池的动作也只在这里抽一次（见 [statePoolAction]）。
        statePoolAction = BubbleAppearance.statePoolPick(context, appearance, next) ?: ""
        // 同理：状态配图也只在这里抽一次。
        statusImagePath = BubbleAppearance.statusImageFor(appearance, next) ?: ""
        // 干活时随机链要让位（wantedAction 里也有这道闸，这里顺手清掉，
        // 免得回到 idle 时先播一个"上一轮残留"的动作）。
        if (next != BubbleAppearance.RUN_IDLE) {
            chainAction = ""
            cancelChain()
        } else {
            scheduleChain(CHAIN_INTERVAL_MS)
        }
        // 状态一变，头顶那句话/那张图**立刻**要跟着换。
        //
        // ⚠️ 不能只等引擎状态推送来刷新：那条路是"页面里发生了什么"的采样，空闲时
        //   几秒才推一次、而且**内容没变就不推**。状态图（表情包）恰恰是在"状态变了
        //   但页面没动静"时最该出现 —— 漏了这一步，AI 配好的图会一直不显示，
        //   看起来像接口没生效。用上一次的引擎状态重刷即可（只有图/文案取自配置）。
        lastStatus?.let { refreshStatusBubble(it) }
        // 钦定动作（配置或脚本）不受运行状态影响，不用白刷一遍。
        if (appearance.action.isNotEmpty() || stateOverrideAction.isNotEmpty()) return
        if (resolvePlayableAction(wantedAction()) == frameAction) return
        avatarView?.let { applyActionLayer(boxReq()) }
    }

    /**
     * 脚本/插件临时钦定一个动作。
     *
     * ⚠️ 传空串是**清掉钦定**，不是"播一个叫空串的动作"。
     * ⚠️ 钦定的动作存在于磁盘时不必重刷内容层，由 [applyActionLayer] 幂等地判定。
     */
    private fun setOverrideAction(action: String) {
        val next = if (action.isBlank()) "" else action.trim()
        if (next == stateOverrideAction) return
        stateOverrideAction = next
        if (resolvePlayableAction(wantedAction()) != frameAction) {
            applyActionLayer(boxReq())
        }
    }

    /**
     * `session/event` 那一路报来的运行状态（比页面的 1 秒采样细得多）。
     *
     * ⚠️ 与 [syncRunState] 走同一个落点：插件**可以只报状态、不带动作名**
     *   （那是它的常态），这时动作就得靠 `actionMap` 自动跟上。
     */
    private fun syncRunStateFromPlugin(state: String) {
        if (state !in BubbleAppearance.RUN_STATES) {
            Log.w(TAG, "运行状态未知，已忽略: $state")
            return
        }
        applyRunState(state)
    }

    /**
     * 把一个"想播的动作名"落到磁盘上真实存在的动作。
     *
     * ⚠️ **"存在"包含视频素材**：只传了 `video` 的动作（那是 dsh-pet 那种宠物的
     *   常态 —— 一个动作就是一段动画，没有帧集）如果不认，会被判成"动作不在
     *   磁盘上"而回退到默认动作，症状是「视频明明写进去了，球却纹丝不动」。
     *   所以判据是 [BubbleFrames.available] **或** [BubbleVideos.available]。
     *
     * ⚠️ 名字不合法（含路径字符等）时 [BubbleFrames.normalizeAction] 返回 null ——
     *   这种情况**只警告一次**，否则每帧（轮播 400ms 一帧）都会刷一行日志。
     */
    private fun resolvePlayableAction(wanted: String): String {
        val name = BubbleFrames.normalizeAction(wanted)
        if (name != null && (BubbleFrames.available(context, name) || BubbleVideos.available(context, name))) {
            warnedMissingAction = ""
            return name
        }
        if (name == null) {
            if (warnedMissingAction != wanted) {
                Log.w(TAG, "悬浮球动作名非法，改用默认动作: $wanted")
                warnedMissingAction = wanted
            }
            return BubbleFrames.DEFAULT_ACTION
        }
        // ⚠️ **默认动作没有素材不是异常**，而是绝大多数用户的常态（他们只用内置头像）。
        //   这里若也打日志，每次启动都会留一条"帧集不在磁盘上"的警告，把真正的
        //   问题（配了 actionMap 却忘了传那套帧）淹掉。
        if (name != BubbleFrames.DEFAULT_ACTION && warnedMissingAction != name) {
            Log.i(TAG, "悬浮球动作 $name 的素材不在磁盘上，改用默认动作")
            warnedMissingAction = name
        }
        return BubbleFrames.DEFAULT_ACTION
    }

    /**
     * 内容层（视频 / 轮播帧 / 精灵图 / 头像）。
     *
     * 优先级：**视频 > 轮播帧 > 自定义头像 > 内置头像**。
     *
     * ⚠️ 视频排最前是因为它才是「像 dsh-pet 那样的宠物动效」—— 帧集只能表达
     *   "N 张静态图轮播"，做不出带 alpha 的连续动画。但**两者不是替代关系**：
     *   没有视频素材时行为与加视频层之前**逐字相同**，所以老用户零影响。
     *
     * ⚠️ 轮播在跑时不去碰单张头像的逻辑，否则两套代码会互相覆盖 `setImageBitmap`。
     */
    private fun applyContentLayer(side: Int) {
        val iv = avatarView ?: return

        // 内容层的透明度单独作用在 ImageView 上（与整体 opacity 相乘）。
        iv.alpha = appearance.avatarOpacity.coerceIn(0f, 1f)
        // 内容缩放：0.2~2.0，>1 会溢出球，由球的裁剪兜住（这正是"图片比球大"想要的效果）。
        // ⚠️ 用 [facingScaleX] 而不是直接读 `avatarScale`：转身（`flipOnTurn`）就是
        //   靠这里的水平镜像表达的，直接赋值会把朝向抹掉 —— 表现为"转身只生效一瞬"。
        iv.scaleY = appearance.avatarScale
        // ⚠️ **视频层也必须一起缩放。**
        //
        //   原实现只给 `avatarView`（图片层）设了 `scaleY`，视频层 `videoHost`
        //   完全没设。而桌宠最主流的用法就是视频素材 —— 于是调设置页的
        //   「头像缩放」时，图片素材的球会变大变小，**视频素材的宠物纹丝不动**。
        //   真机反馈为「宠物比例调整后不会自动变化」，看起来像滑杆坏了。
        (videoHost as? View)?.scaleY = appearance.avatarScale
        // 水平镜像（转身）交给 [applyFacing] 统一刷。
        //
        // ⚠️ 不能只在"朝向变化的那一刻"刷一次：内容层会因换动作/换状态被反复重建，
        //   视频视图是新的（或刚被复用）。不在每次装配时补一次的话，视频素材的
        //   宠物**永远不会转身** —— 而用视频素材恰恰是宠物最常见的用法。
        applyFacing()

        // 轮播帧：先停掉旧的播放器，再按新配置决定要不要重启。
        stopFramePlayer()

        // ── ① 视频层：磁盘上有这个动作的 webm 就用它 ────────────────────
        if (BubbleVideos.available(context, frameAction)) {
            showVideo(frameAction, side)
            iv.visibility = View.GONE
            return
        }
        hideVideo()

        // ── ② 轮播帧 / 精灵图 ─────────────────────────────────────────
        // ⚠️ 帧数**按当前动作问磁盘**，不信 `appearance.frameCount` ——
        //   那个值描述的是配置里那个动作，换到别的动作后会对不上。
        val count = BubbleFrames.frameCount(context, frameAction)
        if (count > 0 && BubbleFrames.available(context, frameAction)) {
            iv.visibility = View.VISIBLE
            showFrame(frameIndex.coerceIn(0, count - 1), side)
            startFramePlayer()
            return
        }

        // 内容层整个去掉：`avatar:"none"` 且没有帧 —— 只留背景形状。
        if (appearance.avatar == BubbleAppearance.AVATAR_NONE) {
            iv.visibility = View.GONE
            return
        }

        if (appearance.usesCustomAvatar) {
            val bmp = BubbleAppearance.readAvatar(context, side)
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
            } else {
                // 图片文件丢了（用户清了数据）—— 退回内置头像而不是留白。
                iv.setImageResource(R.drawable.bubble_avatar)
                iv.visibility = View.VISIBLE
            }
        } else {
            iv.setImageResource(R.drawable.bubble_avatar)
            iv.visibility = View.VISIBLE
        }
    }

    /**
     * 显示视频层的某个动作。
     *
     * ⚠️ **只有在真的有素材时才创建 WebView**（几十 MB 内存）。这一步是惰性的，
     *   所以没有视频的用户不会有任何额外开销。
     *
     * ⚠️ 视频层可见时**必须打开 [BubbleTouchFrame.interceptAll]**：WebView 会在
     *   native 层吃掉 ACTION_DOWN，不抢回来的话球拖不动、点不开。详见那个类的注释。
     */
    private fun showVideo(action: String, side: Int) {
        val host = videoHost as? FrameLayout ?: return
        val url = BubbleVideos.urlOf(context, action) ?: return

        val layer = videoLayer ?: BubbleVideoLayer(context) { ended ->
            // 一次性动作播完 → 交回给「挑下一个动作」的逻辑。
            // ⚠️ 走 post 是因为回调来自 JS 线程的桥，直接动 View 树不安全。
            root?.post { onVideoActionEnded(ended) }
        }.also { videoLayer = it }

        layer.attach(host)
        host.visibility = View.VISIBLE
        (circleView as? BubbleTouchFrame)?.interceptAll = true

        // 循环与否：待机类动作是"背景动作"，播完就该无限循环；其余播一次就换。
        // ⚠️ **循环与否看"这是不是个姿势"，不是看动作名。**
        //   原判据 `action == RUN_IDLE`（拿动作名跟状态名比）有两处错：
        //   ① `idle` 池里通常还有别的待机动作（`happy` 之类），它们同样该循环，
        //      却被判成一次性 —— 播完就触发 `ended` 去换动作，待机反而变得很跳；
        //   ② 手动姿势（点/拖/走）是**循环短片**（`开心跃动` 那种没有 `ended`），
        //      判成一次性会让"点击回应"只播一遍就没了。
        //   正确的语义：姿势类（手动操作 + 待机）循环，其余（一次性表演）播完就换。
        //   ⚠️ 这里直接读 [manualAction] 而不调 [isManualAlive]：后者会**清掉过期的**
        //   手动姿势，而本函数在 `applyActionLayer` → `applyContentLayer` 的深处被调，
        //   此时 [wantedAction] 已经跑过一轮、过期项早已清掉。这里只读不写，避免在
        //   渲染路径上产生副作用。
        //
        //   ⚠️ **转向动作必须一次性播**（`!chainIsTurn`）：翻转朝向挂在"播完"这个
        //   事件上（见 [consumeTurnFlip]），循环播放的话那个事件永远不来 ——
        //   宠物会一直演转身动画却从不真的转身。dsh-pet 那边 turn 也走 `once`。
        val isPose = (manualAction.isNotEmpty() || runState == BubbleAppearance.RUN_IDLE) && !chainIsTurn
        layer.play(action, url, loop = isPose)
    }

    /** 收起视频层（切回帧集/头像，或球被隐藏）。 */
    private fun hideVideo() {
        (videoHost as? FrameLayout)?.visibility = View.GONE
        (circleView as? BubbleTouchFrame)?.interceptAll = false
        videoLayer?.pause()
    }

    /**
     * 一次性视频动作播完之后做什么。
     *
     * 策略：回到 idle，并在空闲时从 `idle` 池**随机抽下一个动作**（随机链）。
     * 与 [BubbleFrames] 里 `loop >= 0` 的处理同构：播完停在最后一帧会让人以为卡住了。
     *
     * ⚠️ 「不随机挑下一个动作」是**加池之前**的行为：那时没有权重表，而权重属于
     *   素材语义（哪些是待机、哪些是玩耍），只有配素材的 AI 才知道。现在那份语义
     *   由 `pools` + `poolWeights` 表达了，所以这里可以真的抽。
     *
     * ⚠️ 抽之前先判 [runState]：正在干活时必须让 `actionMap` 说了算，否则宠物会
     *   在 AI 忙的时候播玩耍动作，正好演反。
     */
    private fun onVideoActionEnded(ended: String) {
        if (ended != frameAction) return
        if (runState != BubbleAppearance.RUN_IDLE) return
        // ⚠️ 拖拽/走动期间不换姿势：`drag`/`move` 池的动作多是**一次性**短片，
        //   播完就触发这里。不挡的话用户还拎着宠物，姿势已经被换成待机 ——
        //   实测一次 3 秒的慢拖拽里，`被吓一跳` 播完就把姿势抢回了 idle。
        if (dragging || draggingPose || walkAnim != null) return
        // ⚠️ 转向动作播完 → 先翻朝向，再抽下一个。顺序反了的话"下一个动作"会
        //   按**旧朝向**渲染一帧，用户看到的是转身之后又弹回原方向。
        consumeTurnFlip()
        rollNextChainAction()
        applyActionLayer(boxReq())
    }

    /**
     * 若刚播完的是 `turn` 池的动作，翻转朝向（并把标记清掉）。
     *
     * ⚠️ 与 dsh-pet 的时机**逐字一致**：翻转发生在转向动画**播完**时
     *   （`pet.ts` 的 `if (animations.turn.includes(animRef.current)) setFacing(next)`），
     *   而不是开播时 —— 开播就翻的话，那段"转身"动画本身会被镜像成反的，
     *   看起来像在原地抖。
     *
     * ⚠️ `flipOnTurn` 关掉时**只清标记、不翻朝向**：那是"我的素材本来就两个方向
     *   各有一套，别镜像我"的用法，镜像会把人脸变成镜像文字。
     */
    private fun consumeTurnFlip() {
        if (!chainIsTurn) return
        chainIsTurn = false
        if (appearance.flipOnTurn) facingRight = !facingRight
    }

    /** 走动方向决定朝向（走右朝右、走左朝左）。 */
    private fun setFacing(right: Boolean) {
        if (!appearance.flipOnTurn || facingRight == right) return
        facingRight = right
        applyFacing()
    }

    /**
     * 把当前朝向刷到内容层上。
     *
     * ⚠️ **两个内容视图都要刷**（`avatarView` 与 `videoHost`）：它们是互斥的内容层
     *   （视频优先，否则帧集/头像）。只刷头像的话，用视频素材的宠物**永远不会转身**，
     *   而转身恰恰是视频素材最常见的用法。
     *
     * ⚠️ 不镜像父容器（`circleView`）：那会连带把右下角的状态点也镜像到左下角。
     *   dsh-pet 镜像的也只是精灵元素本身，不是整个宠物容器。
     */
    private fun applyFacing() {
        val s = facingScaleX()
        avatarView?.scaleX = s
        (videoHost as? View)?.scaleX = s
    }

    /**
     * 内容层该用的 `scaleX`（含水平镜像）。
     *
     * ⚠️ **镜像就是"转身"的全部实现**：素材只画了一个方向，所以 dsh-pet 用
     *   `scaleX(-1)` 表达朝向（`pet.ts` 的 `facingRef.current === 'right' ? 'scaleX(-1)' : ''`）。
     *   我们逐字照搬。
     */
    private fun facingScaleX(): Float {
        val s = appearance.avatarScale
        return if (appearance.flipOnTurn && facingRight) -s else s
    }

    /**
     * 抽下一个随机动作，写进 [chainAction]（抽不到就清空，退回 `actionMap`）。
     *
     * ⚠️ **权重只对 idle 池生效**：`poolWeights["idle"]` 为 0 时不再抽，宠物就停在
     *   `actionMap` 指的那个待机动作上 —— 那是"我只想要一个安静的球"的用法，必须支持。
     *
     * ⚠️ 连抽同一个动作时**重抽一次**（不是无限重抽）：池里只有 2 个动作时无限重抽
     *   会退化成死循环，而重抽一次就够把"连续播两遍"的概率压下去。
     *
     * ⚠️ **配了 `turn` 池就优先掷一次"要不要转身"**：电脑端的随机链是三档
     *   （`idle` / `turn` / `move`），转身是**独立的一档**，而不是待机池里的
     *   一个普通成员 —— 因为转身有个副作用（翻朝向），不能和普通动作混在一起抽。
     *   没配 `turn` 池时这一档直接跳过，行为与加它之前逐字相同。
     */
    private fun rollNextChainAction() {
        if (runState != BubbleAppearance.RUN_IDLE) return
        val weight = appearance.poolWeights[BubbleAppearance.POOL_IDLE]
            ?: BubbleAppearance.DEFAULT_IDLE_WEIGHT
        if (weight <= 0) {
            chainAction = ""
            chainIsTurn = false
            return
        }

        // ── ① 转向那一档（只有配了 `turn` 池才参与掷骰）──────────────────
        // 概率取 `poolWeights["turn"]`，与 idle 的相对关系同 dsh-pet 的
        // `rollKind`（turn 的份额排在 idle 之后）。没配权重就按 0 算 ——
        // 配了池却不配权重时不该让宠物频繁自顾自转身。
        val turnWeight = appearance.poolWeights[BubbleAppearance.POOL_TURN] ?: 0
        if (turnWeight > 0 && appearance.pools[BubbleAppearance.POOL_TURN].orEmpty().isNotEmpty()) {
            val roll = Math.random() * (weight + turnWeight)
            if (roll >= weight) {
                val t = BubbleAppearance.pickFromPool(
                    context, appearance, BubbleAppearance.POOL_TURN, chainAction,
                )
                if (!t.isNullOrEmpty()) {
                    chainAction = t
                    chainIsTurn = true
                    return
                }
                // 池里全是磁盘上没有的动作 → 落到下面的普通抽取（不空转）。
            }
        }
        chainIsTurn = false

        val picked = BubbleAppearance.pickFromPool(
            context, appearance, BubbleAppearance.POOL_IDLE, chainAction,
        ) ?: run {
            chainAction = ""
            return
        }
        if (picked == chainAction) {
            if (++chainRepeats < 2) {
                chainAction = BubbleAppearance.pickFromPool(
                    context, appearance, BubbleAppearance.POOL_IDLE, chainAction,
                ) ?: picked
                return
            }
        } else {
            chainRepeats = 0
        }
        chainAction = picked
    }

    /**
     * 排下一次随机动作（兜底定时器）。
     *
     * ⚠️ 循环播放的动作（待机）永远不会触发 `ended`，所以只靠事件驱动会让宠物
     *   永远卡在第一段动画上。这里排一个定时器保证它**一定会换**。
     *
     * ⚠️ 没配任何池时**不排** —— 不配就是"不要宠物感"，不该留一个空转的定时器。
     */
    private fun scheduleChain(delayMs: Long) {
        cancelChain()
        if (appearance.pools.isEmpty()) return
        val r = object : Runnable {
            override fun run() {
                chainRunnable = null
                if (!attached || runState != BubbleAppearance.RUN_IDLE) return
                // ⚠️ **拖拽期间不许抢姿势**：用户正拎着宠物（`drag` 池的动作在演），
                //   随机链到点会把 `chainAction` 换成 idle 池里的动作 —— 宠物在手里
                //   突然"放下手站着"，动作与手的动作对不上。
                //   （实测：3 秒的慢拖拽里正好撞上一次 8 秒定时器，姿势被抢回 idle。）
                if (dragging || draggingPose) {
                    scheduleChain(delayMs)
                    return
                }
                val before = chainAction
                rollNextChainAction()
                if (chainAction != before) applyActionLayer(boxReq())
                scheduleChain(delayMs)
            }
        }
        chainRunnable = r
        root?.postDelayed(r, delayMs)
    }

    /** 取消随机链的定时器。 */
    private fun cancelChain() {
        chainRunnable?.let { root?.removeCallbacks(it) }
        chainRunnable = null
    }

    /**
     * 排一个长按定时器（到点展开面板）。
     *
     * ⚠️ **只有配了 `click` 池才排**：没配池时单击就展开，再排一个长按会让
     *   "按住不放"也展开 —— 那本来应该是拖拽的起手式，会互相打架。
     */
    private fun scheduleLongPress() {
        cancelLongPress()
        if (appearance.pools[BubbleAppearance.POOL_CLICK].isNullOrEmpty()) return
        val r = Runnable {
            longPressRunnable = null
            // 到点时还按着、且没拖动 → 判成长按。
            if (dragging && !moved) {
                dragging = false
                moved = false
                expand()
            }
        }
        longPressRunnable = r
        root?.postDelayed(r, LONG_PRESS_MS)
    }

    /** 取消长按定时器。 */
    private fun cancelLongPress() {
        longPressRunnable?.let { root?.removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * 播一个"点击回应"动作（从 `click` 池随机抽）。
     *
     * ⚠️ 走 [playManualAction]（手动通道）而**不是** [chainAction]：随机链那条路
     *   在非 idle 时会被 `wantedAction` 挡掉，而用户点宠物恰恰多半发生在 AI 忙的时候
     *   （用户刚发完消息）。走错通道的症状就是"点了没反应"。
     *
     * ⚠️ 有存活期（[CLICK_POSE_MS]）：这是一次性回应，到点就该回到状态映射，
     *   不能永久占着 —— 否则 AI 干完活宠物还停在"开心跃动"上。
     */
    private fun playClickPose() {
        // ⚠️ **先切动作，再压扁**，顺序不能反。切动作会走 `applyActionLayer` →
        //   `applyContentLayer`，那里会把 `scaleY` 重置回 `avatarScale` 基准值 ——
        //   先压扁的话，刚起动的挤压动画会被这一下重置打断（表现为"点了只闪一下"）。
        //
        // ⚠️ 挤压**无条件**发生：抽不到 `click` 池的动作时也该压一下，那是点击的
        //   即时反馈（电脑上点宠物也是这个），否则"点了没反应"的手感又回来了。
        val picked = BubbleAppearance.pickFromPool(
            context, appearance, BubbleAppearance.POOL_CLICK, manualAction,
        )
        if (picked != null) playManualAction(picked, CLICK_POSE_MS)
        playSquash(BubblePhysics.SQ_CLICK_DEPTH)
    }

    /**
     * 切到"被拎起来"的拖拽姿势（从 `drag` 池随机抽）。
     *
     * ⚠️ 存活期 = 0（一直保持）：拖拽的结束时刻由手指决定，不由定时器决定。
     *   松手时 [playManualAction]`("", 0)` 清掉。
     *
     * ⚠️ 抽不到就**什么都不做**（保留当前动作）—— 拖拽时画面已经在动，
     *   强行切回默认动作反而会让宠物"变脸"。
     */
    private fun showDragPose() {
        val picked = BubbleAppearance.pickFromPool(
            context, appearance, BubbleAppearance.POOL_DRAG, manualAction,
        ) ?: return
        playManualAction(picked, 0)
    }

    /**
     * 让宠物自己走一段（`moveEnabled` 打开时）。
     *
     * ⚠️ 走动**不改配置、不落盘**位置：那是"宠物自己溜达"，用户拖到哪就还回哪，
     *   下次启动仍从用户放的位置开始。落盘会让用户下次找不到球。
     *
     * ⚠️ 走动时**先切 `move` 池的动作**（配了的话），走完再切回来 —— 不切的话
     *   宠物是"平移"而不是"走过去"，很假。
     */
    private fun scheduleMove() {
        cancelMove()
        if (!appearance.moveEnabled) return
        val base = appearance.moveIntervalSec * 1000L
        // 0.5~1.5 倍随机：固定周期会像钟摆，随机才有"活着"的感觉。
        val delay = (base * (0.5 + Math.random())).toLong()
        val r = object : Runnable {
            override fun run() {
                moveRunnable = null
                if (!attached) {
                    scheduleMove()
                    return
                }
                // ⚠️ **走动不看 `runState`**：那是"宠物自己溜达"，与 AI 忙不忙无关。
                //   挂在 idle 上会让走动只在 AI 完全不干活时才发生 —— 而用户盯着
                //   宠物看的时候，往往正是 AI 在忙的时候。真机症状就是"走动永远看不到"。
                //   （与点/拖同理，见 [manualAction] 的说明。）
                if (expanded || edgeCollapsed || dragging || draggingPose) {
                    scheduleMove()
                    return
                }
                doWalk()
                scheduleMove()
            }
        }
        moveRunnable = r
        root?.postDelayed(r, delay)
    }

    /**
     * 取消**下一次**走动的定时器。
     *
     * ⚠️ **不能顺手把 [walkAnim] 也停掉。** 这条踩过：`scheduleMove()` 的第一句就是
     *   `cancelMove()`，而 [doWalk] 刚 `start()` 的动画正是在 `scheduleMove()` 之后
     *   才排下一轮的 —— 于是动画被自己取消，位置永远不动。日志特征很明确：
     *   `走动: curX=792 targetX=623 nx=623` 算得没错，但下一次打印 `curX` 仍是 792。
     *
     * 要停掉正在走的动画用 [stopWalk]（球隐藏、手指按下时才需要）。
     */
    private fun cancelMove() {
        moveRunnable?.let { root?.removeCallbacks(it) }
        moveRunnable = null
    }

    /** 停掉正在跑的走动动画（球隐藏、用户按下时用）。 */
    private fun stopWalk() {
        walkAnim?.cancel()
        walkAnim = null
    }

    /**
     * 真的走一段：水平挪一段距离（方向随机），撞到屏幕边就反向。
     *
     * ⚠️ 用 [moveTo]（改 window 的 `x`）而不是 `translationX`：后者会和
     *   WindowManager 的坐标打架，拖拽时就会打架（见 `startThrow` 的注释）。
     *
     * ⚠️ **距离按"这次抽中的走路动作"取**（`moveParams[动作]`），不是全局一个值。
     *   电脑端每个移动动作自带 `minDist`/`maxDist`：小碎步的动作就该挪得少，
     *   大步跑的动作就该挪得多。所以**必须先抽动作、再算距离**，顺序反了就只能
     *   用全局值 —— 那样 `moveParams` 这个接口等于不存在。
     */
    private fun doWalk() {
        val side = boxW()
        val sideH = boxH()
        // ① 先抽姿势（抽不到就没配 `move` 池，此时用全局距离）。
        val pose = BubbleAppearance.pickFromPool(
            context, appearance, BubbleAppearance.POOL_MOVE, manualAction,
        )
        // ② 按这个动作取距离区间（没覆盖值就回落全局）。
        val (loDp, hiDp) = BubbleAppearance.moveRangeFor(appearance, pose ?: "")
        val minD = dp(loDp).toFloat()
        val maxD = dp(hiDp).toFloat()
        if (maxD < minD) return
        val dist = minD + (maxD - minD) * Math.random().toFloat()
        // ③ 距离为 0 = **原地踏步**（`moveParams` 里把 min/max 都写成 0）。
        //   这是合法用法（"别到处跑，就演一下走路的动作"），所以要在这里就把
        //   姿势演出来再返回 —— 否则下面的 `nx == curX` 会直接 return，
        //   连动作都不播，用户看到的是"配了走路却什么都不发生"。
        if (dist < 1f) {
            if (pose != null) playManualAction(pose, MOVE_MS + 600)
            return
        }
        val dir = if (Math.random() < 0.5) -1 else 1
        var targetX = curX + (dist * dir).roundToInt()
        // 撞边就朝反方向走，而不是卡在边上（卡住会让"走动"变成"贴边抖动"）。
        val (clamped, _) = clampInto(targetX, curY, side, sideH)
        if (clamped == curX && abs(targetX - curX) > 2) {
            targetX = curX - (dist * dir).roundToInt()
        }
        val (nx, ny) = clampInto(targetX, curY, side, sideH)
        if (nx == curX) return
        // 朝向：走动方向变了就转身（`flipOnTurn` 关掉时是空操作）。
        setFacing(nx > curX)
        // 走动姿势：配了 `move` 池就切过去，走完清掉、交回状态映射/随机链。
        // ⚠️ 走**手动通道**：走动不看 `runState`（见 scheduleMove），姿势也就不能
        //   挂在只看 idle 的 `chainAction` 上，否则 AI 一忙姿势立刻被顶掉。
        // ⚠️ 带一个**兜底期限**（比动画略长）：动画正常结束会 `clearManualAction`，
        //   但若动画是被 `stopWalk()` 取消的（用户按下的那一瞬间），`onAnimationEnd`
        //   会因 `dragging` 提前返回而不清 —— 那样宠物就永久卡在"走路"姿势上。
        if (pose != null) playManualAction(pose, MOVE_MS + 600)
        val startX = curX
        val anim = android.animation.ValueAnimator.ofInt(startX, nx)
        anim.duration = MOVE_MS
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener { curX = it.animatedValue as Int; moveTo(curX, curY) }
        // ⚠️ 播完把姿势还回去：留着 `move` 池的动作会让宠物停在"走路"姿势上
        //   （腿抬着不动），看起来像卡死。
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (walkAnim === animation) walkAnim = null
                if (dragging || draggingPose) return
                clearManualAction()
                if (runState == BubbleAppearance.RUN_IDLE) rollNextChainAction()
                applyActionLayer(side)
            }
        })
        walkAnim = anim
        anim.start()
    }

    /**
     * 按形状裁剪球的子视图。
     *
     * ⚠️ 只在**非圆/非方**形状（即 SVG path）时才裁：圆和方分别用
     *   `OVAL`/`RECTANGLE` 的 outline 更省，且裁剪会带来一次离屏绘制。
     *
     * ⚠️ 裁剪只在 API 21+ 的 `ViewOutlineProvider` 上有效，而 path 可能非凸
     *   （星形、心形）—— 非凸 path 在 API 29 及以下无法作为 outline。此时
     *   **不裁剪**（形状层照常画），代价是内容可能溢出形状边缘。这是刻意的
     *   降级：宁可"图没被裁"也不要"整个球不显示"。
     */
    private fun clipToShape(view: View) {
        val usePath = appearance.shape == BubbleAppearance.SHAPE_PATH &&
            appearance.shapePath.isNotEmpty()
        if (!usePath) {
            view.clipToOutline = false
            return
        }
        val outline = BubbleShapeDrawable(
            pathData = appearance.shapePath,
            viewBox = appearance.shapeViewBox,
            fillStart = Color.TRANSPARENT,
            fillEnd = null,
            strokeColor = Color.TRANSPARENT,
            strokeWidth = 0f,
        )
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                outline.setBounds(0, 0, v.width, v.height)
                outline.getOutline(o)
            }
        }
        view.invalidateOutline()
    }

    /** 显示当前动作的第 [index] 帧。解码失败时保持上一帧，不留白。 */
    private fun showFrame(index: Int, side: Int) {
        val iv = avatarView ?: return
        // ⚠️ 网格优先问**磁盘**（每个动作各自带网格），配置里那份只当兜底 ——
        //   宠物不同动作常来自不同素材，共用一份网格必然有一套被切错。
        val bmp = BubbleFrames.loadFrame(
            context, index, side, frameAction, BubbleAppearance.spriteOf(appearance),
        )
        if (bmp != null) iv.setImageBitmap(bmp)
    }

    /**
     * 启动轮播。
     *
     * ⚠️ 逐帧时长可以各不相同（`frameDurations`），所以用**自递归的 postDelayed**
     *   而不是 `scheduleAtFixedRate` —— 后者只能等间隔。
     *
     * ⚠️ `loop >= 0` 时播完指定轮数就停在最后一帧（不回到第一帧）：
     *   那是"播一次作为提示"的用法，回到第一帧会让人以为它还在转。
     *
     * ⚠️ 帧数/时长/网格/轮数**全部在启动时从磁盘读一次**，而不是每帧去读：
     *   轮播 400ms 一帧，每次都解析 meta.json 是白白的 I/O。中途换了动作由
     *   `frameAction != action` 那一条判据退出，交给下一次切动作重启。
     */
    private fun startFramePlayer() {
        val action = frameAction
        val count = BubbleFrames.frameCount(context, action)
        if (count <= 1) return
        val durations = BubbleFrames.durations(context, appearance.frameIntervalMs, action)
        val loop = BubbleFrames.loop(context, appearance.frameLoop, action)
        val interval = appearance.frameIntervalMs
        val side = boxReq()

        val step = object : Runnable {
            override fun run() {
                // 动作换了或球没了就退出，由下一次 applyActionLayer 重启。
                if (frameAction != action || !expandedSafeForFrames()) return
                val next = frameIndex + 1
                if (next >= count) {
                    frameLoops++
                    if (loop in 0 until frameLoops) {
                        // 播够了：停在最后一帧。
                        frameRunnable = null
                        return
                    }
                }
                frameIndex = next % count
                showFrame(frameIndex, side)
                val delay = (durations.getOrNull(frameIndex) ?: interval).toLong()
                root?.postDelayed(this, delay.coerceAtLeast(20L))
            }
        }
        frameRunnable = step
        val delay = (durations.getOrNull(frameIndex) ?: interval).toLong()
        root?.postDelayed(step, delay.coerceAtLeast(20L))
    }

    /** 帧播放器是否还该继续（球被移除/隐藏时停掉，别空转耗电）。 */
    private fun expandedSafeForFrames(): Boolean = attached && root != null

    /** 停掉轮播。幂等。 */
    private fun stopFramePlayer() {
        frameRunnable?.let { root?.removeCallbacks(it) }
        frameRunnable = null
    }

    /**
     * 动效层：旋转与呼吸。
     *
     * ⚠️ 两者都作用在 `avatarView`（内容层）而不是整个球：转整个球会把形状层
     *   也转起来，那对非圆形状（星形/心形）是灾难。
     *
     * ⚠️ **必须用两个独立的 `ObjectAnimator`，不能用 `view.animate()`。**
     *   `ViewPropertyAnimator` 对同一个 View 是**单例**，后一次 `.start()` 会
     *   取消前一次 —— 于是「旋转 + 呼吸」同时打开时只有一个生效，而且看不出
     *   是谁把谁取消了。`ObjectAnimator` 是独立对象，两个不同属性可以并行。
     *
     * ⚠️ 旋转用 `repeatCount = INFINITE` 而不是 `withEndAction` 递归：递归那种
     *   写法在"动画已在跑"的守卫下会转完一圈就停（守卫把它自己挡住了）。
     */
    private fun applyMotion() {
        val iv = avatarView ?: return

        // ⚠️ **视频层要跟着一起做动效。**
        //
        //   原实现只对 `avatarView`（图片层）设 spin/pulse，视频层 `videoHost`
        //   完全不动。而桌宠的主流素材是视频 —— 于是「旋转」「呼吸」这两个
        //   开关对视频宠物**完全无效**，且两个内容层互斥显示时观感不一致
        //   （图片球会呼吸，切成视频后突然静止）。
        //
        //   这里只同步**静态部分**（基础缩放与旋转角）；呼吸是 `ObjectAnimator`，
        //   动画器要各自建一个（见下面 pulse 分支里的 `pulseVideoX/Y`）。
        (videoHost as? View)?.let { vh ->
            vh.scaleY = appearance.avatarScale
            vh.scaleX = if (appearance.flipOnTurn && facingRight) -appearance.avatarScale
                        else appearance.avatarScale
            vh.rotation = if (appearance.spin != 0) vh.rotation else 0f
        }

        // ── 旋转 ──────────────────────────────────────────────────────
        if (appearance.spin != 0) {
            // 每 360° 的时长 = 360 / 每秒角度。负角度即逆时针。
            val degPerSec = abs(appearance.spin).coerceAtLeast(1)
            val duration = (360f / degPerSec * 1000f).toLong().coerceAtLeast(200L)
            val to = if (appearance.spin > 0) 360f else -360f
            val changed = spinAnimator == null || spinDuration != duration || spinTo != to
            if (changed) {
                spinAnimator?.cancel()
                // 从 0 转到 ±360：`repeatMode = RESTART` 会把每轮重置回 0，
                // 所以终值必须正好是一整圈，否则每轮之间会跳一下。
                spinAnimator = ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, to).apply {
                    this.duration = duration
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = LinearInterpolator()
                    start()
                }
                spinDuration = duration
                spinTo = to
                spinning = true
            }
        } else if (spinning) {
            spinAnimator?.cancel()
            spinAnimator = null
            spinDuration = 0L
            spinTo = 0f
            spinning = false
            iv.rotation = 0f
        }

        // ── 呼吸 ──────────────────────────────────────────────────────
        if (appearance.pulse) {
            val base = appearance.avatarScale
            // ⚠️ **呼吸的 X 缩放要带上朝向符号**：这个动画器独占 `SCALE_X`，
            //   直接给正值会把转身（`scaleX = -s`）覆盖掉 —— 表现为"呼吸一开，
            //   宠物立刻转回正面，而且再也不转身"。
            val sx = if (appearance.flipOnTurn && facingRight) -base else base
            val changed = pulseAnimator == null || pulseBase != sx
            if (changed) {
                pulseAnimator?.cancel()
                pulseAnimator = ObjectAnimator.ofFloat(iv, View.SCALE_X, sx, sx * 1.12f).apply {
                    this.duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                // 两个属性（X/Y）必须同步，否则会拉成椭圆。共用一个进度源最简单：
                // 再建一个只驱动 SCALE_Y 的、时长与相位完全一致的动画。
                pulseAnimatorY?.cancel()
                pulseAnimatorY = ObjectAnimator.ofFloat(iv, View.SCALE_Y, base, base * 1.12f).apply {
                    this.duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                // 视频层用同一组参数再建一对，保证两个内容层呼吸节奏一致
                (videoHost as? View)?.let { vh ->
                    pulseVideoX?.cancel()
                    pulseVideoY?.cancel()
                    pulseVideoX = ObjectAnimator.ofFloat(vh, View.SCALE_X, sx, sx * 1.12f).apply {
                        this.duration = 700
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                    pulseVideoY = ObjectAnimator.ofFloat(vh, View.SCALE_Y, base, base * 1.12f).apply {
                        this.duration = 700
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
                pulseBase = sx
                pulsing = true
            }
        } else if (pulsing) {
            pulseAnimator?.cancel()
            pulseAnimatorY?.cancel()
            pulseVideoX?.cancel()
            pulseVideoY?.cancel()
            pulseAnimator = null
            pulseAnimatorY = null
            pulseVideoX = null
            pulseVideoY = null
            pulseBase = 0f
            pulsing = false
            // ⚠️ 用 [facingScaleX] 而不是 `avatarScale`：这里在"关掉呼吸"时把缩放
            //   复位，直接写 avatarScale 会把朝向一起抹掉 —— 表现为"关掉呼吸后
            //   宠物突然转回正面"。
            iv.scaleX = facingScaleX()
            iv.scaleY = appearance.avatarScale
            (videoHost as? View)?.let { vh ->
                vh.scaleX = facingScaleX()
                vh.scaleY = appearance.avatarScale
            }
        }
    }

    /**
     * 球的底：形状 + 底色/渐变 + 描边，全部由 [appearance] 决定。
     *
     * ⚠️ `shape:"path"` 时返回 [BubbleShapeDrawable]（任意 SVG 形状），否则是
     *   原来那三种枚举形状的 [GradientDrawable]。两条分支的**描边与渐变语义
     *   必须一致**，否则 AI 把形状从 `circle` 改成 `path` 时颜色会变样。
     */
    private fun ballBackground(): Drawable {
        val c1 = colorOr(appearance.bg, DEFAULT_BALL_BG)
        val c2 = if (appearance.bg2.isNotEmpty()) colorOr(appearance.bg2, c1) else null
        val bw = if (appearance.borderWidth > 0) dp(appearance.borderWidth) else dp(1)
        val bc = if (appearance.border.isNotEmpty()) colorOr(appearance.border, DEFAULT_BORDER) else DEFAULT_BORDER

        // 任意形状：交给 SVG path 那条路。
        if (appearance.shape == BubbleAppearance.SHAPE_PATH && appearance.shapePath.isNotEmpty()) {
            return BubbleShapeDrawable(
                pathData = appearance.shapePath,
                viewBox = appearance.shapeViewBox,
                fillStart = c1,
                fillEnd = c2,
                strokeColor = bc,
                strokeWidth = bw.toFloat(),
            )
        }

        val d = GradientDrawable()
        when (appearance.shape) {
            BubbleAppearance.SHAPE_CIRCLE -> d.shape = GradientDrawable.OVAL
            BubbleAppearance.SHAPE_SQUARE -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = 0f
            }
            else -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = dp(appearance.cornerRadius).toFloat()
            }
        }
        if (c2 != null) {
            d.colors = intArrayOf(c1, c2)
            d.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        } else {
            d.setColor(c1)
        }
        d.setStroke(bw, bc)
        return d
    }

    /** 贴边薄片的底：窄圆角条，颜色跟随球的底色。 */
    /**
     * 贴边态当前应使用的宽度（dp）。
     *
     * 显示图片时用配置值；`edgeImage == "none"`（纯色薄片）时退回老的
     * [EDGE_WIDTH_DP]，因为那是"一条缝"的语义，与"能点到的探头"不是一回事。
     */
    private fun edgeWidthDp(): Int = if (usesEdgeImage()) {
        appearance.edgeWidth.coerceIn(
            BubbleAppearance.MIN_EDGE_WIDTH,
            BubbleAppearance.MAX_EDGE_WIDTH,
        )
    } else {
        EDGE_WIDTH_DP
    }

    /** 贴边态当前是否要显示图片（而非纯色薄片）。 */
    private fun usesEdgeImage(): Boolean = when (appearance.edgeImage) {
        BubbleAppearance.EDGE_IMAGE_NONE -> false
        BubbleAppearance.EDGE_IMAGE_FILE -> File(
            context.filesDir,
            BubbleAppearance.EDGE_IMAGE_FILE,
        ).let { it.isFile && it.length() > 0L }
        else -> true   // "default" 或 data URI
    }

    /**
     * 把贴边图铺到 [edgeImageView] 上。
     *
     * 三种来源：内置 drawable（默认）、落盘的自定义图、内联 data URI。
     * 纯色薄片模式下清空图片并让 `edgeView` 的背景露出来。
     *
     * ⚠️ 每帧都调用 `setImageBitmap` 会很浪费（`applyAppearanceToViews` 在状态
     *   刷新时也会跑），所以**按来源指纹去重**：指纹没变就直接返回。
     */
    private fun applyEdgeImage() {
        val iv = edgeImageView ?: return
        val src = appearance.edgeImage
        // ⚠️ **指纹里必须带 `edgeSide`。**
        //
        //   内置探头形象是「从右边探出头」画的（人物靠右、留白在左）。贴到
        //   屏幕左边时必须水平镜像，否则人物背对屏幕边缘 —— 真机反馈为
        //   「只能适配右边，贴左边很怪异」。
        //
        //   不把方向放进指纹的话，从右边换到左边时指纹没变，会直接 return，
        //   镜像永远不会重算。
        val fingerprint = "$src|${edgeWidthDp()}|${appearance.edgeHeight}|$edgeSide"
        if (fingerprint == edgeImageKey && iv.drawable != null) {
            iv.alpha = appearance.edgeOpacity.coerceIn(0f, 1f)
            iv.scaleX = if (edgeSide < 0) -1f else 1f
            return
        }
        edgeImageKey = fingerprint

        if (!usesEdgeImage()) {
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            return
        }
        iv.visibility = View.VISIBLE
        iv.alpha = appearance.edgeOpacity.coerceIn(0f, 1f)

        // ⚠️ 只认落盘文件与内置两种来源。内联 data URI **在 merge 阶段就已经被
        //   [BubbleAppearance.writeEdgeImage] 换成文件标记了**（见那里的注释），
        //   所以这里不需要再解码 —— 与 [appearance.avatar] 的处理完全一致。
        //   万一真拿到 data URI（解析场景没落盘），退回内置图即可，不影响可用性。
        if (src == BubbleAppearance.EDGE_IMAGE_FILE) {
            val bmp = BubbleAppearance.readEdgeImage(context, dp(edgeWidthDp()))
            if (bmp != null) {
                iv.setImageBitmap(bmp)
            } else {
                // 文件没了（用户清数据）⇒ 退回内置，不留空白
                iv.setImageResource(R.drawable.bubble_edge_default)
            }
        } else {
            iv.setImageResource(R.drawable.bubble_edge_default)
        }
        // 镜像：贴左边时水平翻转，让「探头」朝向屏幕内侧。
        //
        // ⚠️ 用 `scaleX = -1` 而不是把 bitmap 翻转一份：后者每次切边都要重新
        //   解码一张图（`edgeWidthDp()` 变了还要重采样），而 scaleX 是渲染层
        //   的一次变换，零分配。`pivotX` 默认是 view 中心，镜像后位置不变。
        iv.scaleX = if (edgeSide < 0) -1f else 1f
    }

    private fun edgeBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(3).toFloat()
        setColor(colorOr(appearance.bg, DEFAULT_BALL_BG))
        setStroke(dp(1), if (appearance.border.isNotEmpty()) colorOr(appearance.border, DEFAULT_BORDER) else DEFAULT_BORDER)
    }

    /** 面板的底：圆角 + 底色 + 淡描边。 */
    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        val bgColor = colorOr(appearance.panelBg, DEFAULT_PANEL_BG)
        setColor(bgColor)
        // ⚠️ 底色全透明时**不能描边**：没有底色的情况下，那圈 1dp 灰线会孤零零
        //   浮在屏幕上，看起来像"一个空框"，比不做还难看。判断看 alpha。
        if (android.graphics.Color.alpha(bgColor) == 0) {
            setStroke(0, android.graphics.Color.TRANSPARENT)
        } else {
            setStroke(dp(1), DEFAULT_BORDER)
        }
    }

    /**
     * 面板内的文字与强调色。
     *
     * ⚠️ 正文色要**逐处设**：`panelAction` / `panelTitle` 等是独立 TextView，
     *   没有统一的父级可以设 textColor。漏一处就会出现"面板换了深色底、
     *   标题还是黑的"这种半截效果。
     */
    private fun applyPanelColors() {
        val text = colorOr(appearance.panelText, DEFAULT_TEXT)
        val accent = colorOr(appearance.panelAccent, DEFAULT_ACCENT)
        // ⚠️ 面板**底色全透明**时，文字直接压在用户的壁纸/桌面内容上，深浅完全不可控。
        //   给文字加一层浅色描边（白晕），保证深色文字在深壁纸上也读得出来；
        //   有底色时清掉阴影，避免正常面板上出现一圈脏边。
        val translucentPanel = android.graphics.Color.alpha(
            colorOr(appearance.panelBg, DEFAULT_PANEL_BG),
        ) == 0
        val glow = if (translucentPanel) 0xCCFFFFFF.toInt() else 0
        panelAction?.let {
            it.setTextColor(text)
            it.setShadowLayer(if (translucentPanel) 3f else 0f, 0f, 0f, glow)
        }
        panelTitle?.let {
            it.setTextColor(text)
            it.setShadowLayer(if (translucentPanel) 3f else 0f, 0f, 0f, glow)
        }
        panelInput?.setTextColor(text)
        panelSend?.let { s ->
            s.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(accent)
            }
        }
        // 转录区已渲染的行也要跟着换色 —— 否则插件改配色后要等下一次转录变化才生效。
        //
        // ⚠️ 必须连 `renderedRoles` 一起清掉：增量分支是"role 序列一致就复用旧行、
        //   只改文本"，而换配色改的正是**样式**。只清指纹的话这里会走复用，
        //   颜色永远换不过来。
        transcriptFingerprint = ""
        renderedRoles = emptyList()
        if (expanded) renderTranscript()
    }

    /** 颜色串 → int；空串或非法时退回 [fallback]。 */
    private fun colorOr(value: String, fallback: Int): Int {
        if (value.isEmpty()) return fallback
        return try {
            Color.parseColor(value)
        } catch (t: Throwable) {
            fallback
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 收起态的状态气泡
    // ══════════════════════════════════════════════════════════════════

    /**
     * 显示/更新球上方的状态标签。
     *
     * 传空串即隐藏 —— 空闲且没有动作时不该在屏幕上留一个空壳。
     *
     * ⚠️ **只在收起态显示**：展开态的面板里已经有同一份信息，再挂一个标签
     *   会重复，而且面板比球宽得多，标签会横在面板上方很怪。
     *   贴边态同理（薄片只有 6dp 宽，标签会把它整个盖住）。
     *
     * @param autoHideMs 覆盖外观配置里的停留时长；null = 用外观配置。
     *   0 = 常驻（AI 主动推送用得上：「这条别消失」）。
     */
    private fun showStatusBubble(
        text: String,
        running: Boolean,
        autoHideMs: Int? = null,
        showImage: Boolean = true,
    ) {
        // ⚠️ 有图时**允许文字为空**：电脑端桌面宠物头顶经常只有一张表情包、
        //   没有台词。若仍按"文字为空就不显示"处理，配了图却没配文案的用法
        //   会整个气泡都不出现 —— 那正好是最常见的用法（只想要表情包）。
        val imgName = if (showImage) statusImagePath else ""
        val hasImage = imgName.isNotEmpty() && BubbleStatusImages.available(context, imgName)
        if ((text.isEmpty() && !hasImage) || expanded || edgeCollapsed) {
            hideStatusBubble()
            return
        }

        if (statusRoot == null) {
            val v = LayoutInflater.from(context).inflate(R.layout.bubble_status, null)
            statusRoot = v
            statusDot = v.findViewById(R.id.statusDot)
            statusText = v.findViewById(R.id.statusText)
            statusImage = v.findViewById(R.id.statusImage)
            // 宽度上限靠 maxWidth 约束 TextView，而不是给 window 定死宽度 ——
            // 短文案要能收紧，长文案才截断。
            statusText?.maxWidth = statusMaxWidthPx
            // 点一下等于点球：展开面板。用户看到状态自然会想去点它。
            v.setOnClickListener { expand() }
            try {
                wm.addView(v, statusParams())
            } catch (t: Throwable) {
                Log.w(TAG, "添加状态气泡失败: ${t.message}")
                statusRoot = null
                return
            }
            // ⚠️ 量出真实尺寸前先藏起来，否则会先按"宽度 0"居中一次、量完再跳到
            //   正确位置 —— 用户看到的是标签从球中心"弹"到左边。
            v.alpha = 0f
            // 尺寸变化时重新摆位：文字变长/变短会改变标签宽度，宽度变了居中位置
            // 也得跟着变，否则标签会歪向一边（或探出屏幕）。
            //
            // ⚠️ 防重入：positionStatusBubble 会改 layoutParams，可能再触发一次
            //   layout 回调 —— 不加这个守卫就是无限递归（ANR）。
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!positioning) positionStatusBubble()
            }
            v.post {
                positionStatusBubble()
                v.animate().alpha(1f).setDuration(120).start()
            }
            // 刚创建时 statusKey 是空串，下面必然走到「内容变了」分支，计时器由此启动。
        }

        // ⚠️ 内容**真的变了**才重置自动消失计时器。
        //
        // 这正是用户要的语义：「工具调用状态横条没有变化时 两秒后自动消失」。
        // 状态是每 5 秒（外加 DOM 变化）推一次的，如果每次推送都重置计时器，
        // 横条就会永远挂着 —— 那正是改之前的行为，也是用户抱怨的点。
        //
        // key 里带上时长：AI 推一条"常驻"的消息时内容可能与引擎状态一致，
        // 不带时长的话会被判成"没变"而沿用旧的自动消失计时器。
        // key 里也带上图名：换状态换了张图但文案恰好相同时，图必须跟着换。
        val key = "$text\u0000$running\u0000$autoHideMs\u0000$imgName"
        val changed = key != statusKey
        if (changed) {
            statusKey = key
            applyStatusImage(if (hasImage) imgName else "")
            // ⚠️ 只有图、没有文字时把文字行收起来：否则气泡底部会留一行空的高度
            //   （TextView 空串仍占一行），看起来像图下面贴了块白条。
            statusTextRow?.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            statusText?.text = text
            paintDot(statusDot, running, small = true)
            scheduleStatusAutoHide(autoHideMs)
        }
        positionStatusBubble()
    }

    /**
     * 把状态配图贴到气泡顶部（传空串即收起）。
     *
     * ⚠️ **尺寸在代码里设而不是写死布局**：`statusImageSizeDp` 是配置项，
     *   而且它同时约束宽和高 —— 素材宽高比不一，用 `maxWidth/maxHeight` +
     *   `adjustViewBounds` 让长边贴合，短边自适应，不会把图拉变形。
     */
    private fun applyStatusImage(name: String) {
        val iv = statusImage ?: return
        if (name.isEmpty()) {
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            return
        }
        val side = dp(appearance.statusImageSizeDp.coerceIn(32, BubbleAppearance.MAX_STATUS_IMAGE_DP))
        val bmp = BubbleStatusImages.read(context, name, side)
        if (bmp == null) {
            // 文件被清了（用户清数据 / 超出上限被 prune 掉）—— 收起而不是留白。
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            return
        }
        iv.maxWidth = side
        iv.maxHeight = side
        iv.setImageBitmap(bmp)
        iv.visibility = View.VISIBLE
    }

    /**
     * 排一次「内容无变化就自动消失」的计时。
     *
     * ⚠️ 只在内容变化时调用（见 [showStatusBubble]）。`<= 0` 表示常驻，
     *   此时不排计时器。
     */
    private fun scheduleStatusAutoHide(override: Int? = null) {
        statusHandler.removeCallbacks(statusHideRunnable)
        val ms = override ?: appearance.statusAutoHideMs
        if (ms <= 0) return
        statusHandler.postDelayed(statusHideRunnable, ms.toLong())
    }

    /**
     * 状态横条自动消失。
     *
     * ⚠️ 做成字段而不是每次 new：`removeCallbacks` 要靠同一个实例才能取消。
     *
     * ⚠️ 消失时**一并清掉 AI 推送标记**：横条都看不见了，这次推送就算结束。
     *   不清的话下一次引擎状态刷新（每几秒一次）会把它重新显示出来，
     *   用户看到的是"这条消息消失又冒出来、消失又冒出来"。
     */
    private val statusHideRunnable = Runnable {
        if (statusRoot == null) return@Runnable
        // 推送已经展示完了 —— 标记与它自己的清理计时器都该撤掉。
        statusHandler.removeCallbacks(pushedClearRunnable)
        pushedStatus = ""
        pushedSticky = false
        pushedDurationMs = 0L
        // 淡出而不是瞬移：横条就在用户视野边缘，突然消失会让人以为界面出问题了。
        statusRoot?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction {
            hideStatusBubble()
        }?.start() ?: hideStatusBubble()
    }

    private fun hideStatusBubble() {
        statusHandler.removeCallbacks(statusHideRunnable)
        val v = statusRoot ?: return
        statusRoot = null
        statusDot = null
        statusText = null
        statusTextRow = null
        // ⚠️ 位图要显式断开：气泡是**反复创建/销毁**的（每次状态变化后自动消失），
        //   留着引用会让每张状态图都钉在堆里，几十张之后就是 OOM。
        statusImage?.setImageDrawable(null)
        statusImage = null
        statusKey = ""
        runCatching { wm.removeViewImmediate(v) }
    }

    /**
     * 状态气泡的窗口参数。
     *
     * ⚠️ **不能加 `FLAG_NOT_TOUCHABLE`** —— 加了它就点不动了，而"点状态标签
     *   展开面板"是用户看到状态后最自然的下一步。代价是这一小块区域会挡住
     *   下层应用，但标签本身很小，且它只显示在有进展可看的时候。
     *
     * ⚠️ 保留 `FLAG_NOT_FOCUSABLE`：它只是显示，不该抢走下层应用的按键焦点。
     */
    private fun statusParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * 把状态气泡摆到球的**正上方**。
     *
     * ⚠️ 必须跟着球一起移动（拖动、吸附动画里都要调），否则球跑了标签留在原地。
     *
     * 水平方向：与球居中对齐；贴边时会被夹进屏幕内（球在最左/最右时，
     * 居中对齐会让标签探出屏幕）。
     */
    private fun positionStatusBubble() {
        val v = statusRoot ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        positioning = true
        try {
            val dm = context.resources.displayMetrics
            val circleW = circleWidth()

            // 标签还没量过尺寸时（刚 addView）先按屏幕内夹一次，量完会再调一次。
            val labelW = v.width.takeIf { it > 0 } ?: 0
            val centered = curX + (circleW - labelW) / 2
            p.x = centered.coerceIn(0, (dm.widthPixels - labelW).coerceAtLeast(0))

            // 竖直方向：默认贴在球上方。上方空间不够（球贴着顶部）时改放球下方 ——
            // 否则标签会被状态栏挡住或直接跑出屏幕。
            val labelH = v.height.takeIf { it > 0 } ?: 0
            val above = curY - statusGapPx - labelH
            p.y = if (labelH > 0 && above < dp(TOP_MARGIN_DP)) {
                curY + circleHeight() + statusGapPx
            } else {
                above
            }
            runCatching { wm.updateViewLayout(v, p) }
        } finally {
            positioning = false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 余额徽标
    // ══════════════════════════════════════════════════════════════════

    /**
     * 刷新余额数据。由插件经 [BalanceCommandBridge] 或本类自己的定时器调。
     *
     * @param snap 新快照；`null` 表示只重绘当前数据（配置改了就靠这个重排）。
     */
    fun applyBalanceSnapshot(snap: BalanceSnapshot?) {
        if (snap != null) {
            val oldCost = balanceLastCost
            balanceCurrent = snap
            balanceLastCost = snap.costLast
            if (snap.costLast.compareTo(oldCost) != 0 && appearance.balanceBadge.showCostFlash) {
                balanceRoot?.flash()
            }
        }
        renderBalanceBadge()
    }

    /**
     * 给用户一个"点到了"的即时反馈（点在球上时调）。
     *
     * ## 为什么需要
     *
     * 用户开了「点球刷新」后点球，反馈原话是「我点球，他也没有什么信息弹出来」。
     * 根因是：余额**没变**的时候，整个流程走完界面**一动不动** —— 用户完全无从
     * 判断是自己没点到、还是刷新了但数字没变、还是功能坏了。
     *
     * 所以这里不等数据，先让徽标脉冲一下：哪怕余额一分没变，用户也能看到
     * "这一下被接收了"。真实的数字更新随后由 [applyBalanceSnapshot] 接上。
     *
     * ⚠️ 徽标没显示（未启用/未登录）时**什么都不做**，不能因为没徽标就去弹窗 ——
     *   那会变成一个用户没要求的提示框。
     */
    fun pulseBalanceBadge() {
        balanceRoot?.flash()
    }

    /** 取当前快照（README/工具读回用）。 */
    fun balanceSnapshotJson(): String = (balanceCurrent ?: BalanceSnapshot()).toJson().toString()

    /**
     * 创建/更新/销毁徽标窗口。
     *
     * 三种状态要分清（漏一种就会出现"关了还看得见"或"开了看不见"）：
     *
     * | 条件 | 动作 |
     * |---|---|
     * | 未启用 / 无数据 / 文字为空 | 移除窗口 |
     * | 已启用且有数据、窗口不存在 | 创建 |
     * | 已启用且有数据、窗口已存在 | 只重排 + 换文字 |
     */
    private fun renderBalanceBadge() {
        val badge = appearance.balanceBadge
        val v = root ?: run {
            removeBalanceBadge()
            return
        }
        val text = balanceCurrent?.render(badge.template) ?: ""

        // 未启用、或（要求空闲隐藏 且 这次没有花费）→ 移除。
        val idleHidden = badge.hideWhenIdle &&
            (balanceCurrent?.costLast?.compareTo(java.math.BigDecimal.ZERO) ?: 0) <= 0

        if (!badge.enabled || text.isEmpty() || idleHidden || !attached) {
            removeBalanceBadge()
            return
        }

        var view = balanceRoot
        if (view == null) {
            view = BalanceBadgeView(context)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                // ⚠️ 必须 NOT_TOUCHABLE：徽标只是展示，任何触摸都该穿透到球或下层应用。
                //   不加的话它会吃掉球边缘那片区域的拖拽。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            val added = runCatching { wm.addView(view, p) }.isSuccess
            if (!added) {
                Log.w(TAG, "余额徽标窗口添加失败（已忽略）")
                return
            }
            balanceRoot = view
        }

        view.render(badge, text)
        balanceLastText = text
        positionBalanceBadge()
    }

    /**
     * 把徽标摆到球旁边。
     *
     * ⚠️ 尺寸可能还没量出来（刚 addView 那一帧宽高是 0）。这时**先按 wrap_content
     *   让它自己量**，用 `post` 在下一帧重排一次 —— 否则第一帧会算成左上角，
     *   表现为"徽标闪一下才到正确位置"。
     */
    private fun positionBalanceBadge() {
        val v = balanceRoot ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        val badge = appearance.balanceBadge
        val dm = context.resources.displayMetrics

        val bw = v.width
        val bh = v.height
        if (bw <= 0 || bh <= 0) {
            v.post { positionBalanceBadge() }
            return
        }

        val (x, y) = v.applyAnchor(
            badge = badge,
            bubbleX = curX,
            bubbleY = curY,
            bubbleW = circleWidth(),
            bubbleH = circleHeight(),
            badgeW = bw,
            badgeH = bh,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
        )
        p.x = x
        p.y = y
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 移除徽标窗口（幂等）。 */
    private fun removeBalanceBadge() {
        val v = balanceRoot ?: return
        balanceRoot = null
        balanceLastText = ""
        runCatching { wm.removeView(v) }
    }

    /** 球的当前宽度（量过就用实测值，否则退回配置里的尺寸）。 */
    private fun circleWidth(): Int = root?.width?.takeIf { !expanded && !edgeCollapsed && it > 0 }
        ?: boxW()

    /** 球的当前高度（同上）。 */
    private fun circleHeight(): Int = root?.height?.takeIf { !expanded && !edgeCollapsed && it > 0 }
        ?: boxH()

    /**
     * 画布（= 主体框）的像素宽 / 高。
     *
     * ⚠️ **所有几何都走这两个**，不要在各处自己 `dp(appearance.size)` —— 上一版
     *   就是有八处各算各的尺寸，加一个字段要追八个调用点，漏一个就出现
     *   「球是新的、走动范围还是旧的」这类错位。
     *
     * 取值来源见 [BubbleAppearance.boxPx]：显式配了就用配的，没配就按当前
     * **主体素材**的长宽比自适应（正方形素材 ⇒ 老行为）。
     */
    private fun boxW(): Int = BubbleAppearance.boxPx(context, appearance, frameAction).first

    /** 画布像素高，见 [boxW]。 */
    private fun boxH(): Int = BubbleAppearance.boxPx(context, appearance, frameAction).second

    /**
     * 内容层解码用的**请求边长**：取画布长边。
     *
     * 内容层是 `fitCenter` 铺满画布的，只给短边会让长边那一轴解码不足而糊。
     */
    private fun boxReq(): Int = maxOf(boxW(), boxH())

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        // 初始按**收起态**配：不抢焦点。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * 按形态切换窗口的焦点标志。**输入法能不能弹出来完全取决于这里。**
     *
     * ## 之前为什么弹不出键盘
     *
     * 原实现固定带 `FLAG_ALT_FOCUSABLE_IM`，并注释成"平时不抢焦点、点输入框才聚焦"
     * —— **那个理解是反的**。该标志的语义是"把 `FLAG_NOT_FOCUSABLE` 对输入法的
     * 含义**取反**"，即：未设 `NOT_FOCUSABLE` 而设了它，窗口就表现为
     * **不需要与输入法交互**，系统因此不会为它弹出键盘。真机表现就是
     * 「点输入框毫无反应」。
     *
     * ## 正确组合
     *
     * | 形态 | 标志 | 效果 |
     * |---|---|---|
     * | 收起 / 贴边 | `NOT_FOCUSABLE` | 不抢焦点，下层应用照常收按键，也不弹键盘 |
     * | 展开 | 去掉 `NOT_FOCUSABLE` | 可聚焦 ⇒ 点输入框能拉起键盘 |
     *
     * ⚠️ 两态都要保留 `FLAG_NOT_TOUCH_MODAL`：它让窗口**之外**的触摸照常传给
     *   下层应用。去掉的话，气泡一显示就会把整个屏幕的触摸都吃掉。
     *
     * ⚠️ 展开态**不能**再设 `FLAG_ALT_FOCUSABLE_IM` —— 那正是键盘弹不出来的原因。
     *
     * @param focusable 是否允许这个窗口获得焦点（展开态为 true）。
     */
    private fun applyFocusFlags(focusable: Boolean) {
        val v = root ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        p.flags = if (focusable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** API 26+ 用 TYPE_APPLICATION_OVERLAY；更低版本（本项目 minSdk 26）用不到旧值。 */
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ══════════════════════════════════════════════════════════════════
    // 拖动 + 点击 + 边缘吸附 + 拖出屏幕贴边
    // ══════════════════════════════════════════════════════════════════

    /**
     * 给一个 View 装「拖动 + 点击」手势。
     *
     * @param target 手势载体（球的 circleView）。
     * @param onTap 未移动就松手时的动作。
     * @param allowEdgeCollapse 松手时是否允许因"拖出屏幕"而进入贴边态。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch(
        target: View,
        onTap: () -> Unit = { expand() },
        allowEdgeCollapse: Boolean = true,
    ) {
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWinX = curX
                    downWinY = curY
                    rawX = curX
                    rawY = curY
                    dragging = true
                    moved = false
                    downAt = ev.eventTime
                    // 抓住飞行的球：立刻停下并清掉轨迹，否则松手时会用"上一段飞行"
                    // 的残留速度把它又甩出去。
                    stopThrow()
                    // 自己走动的动画也要停：它每一帧都在写 `curX`，不停下来会和
                    // 手指的位移互相覆盖，表现为"球跟手一跳一跳的"。
                    stopWalk()
                    trail.clear()
                    trail.add(ev.rawX, ev.rawY, ev.eventTime)
                    // 长按计时：只有配了 `click` 池时才有意义（那时单击被改成
                    // "逗宠物"，展开改走长按）。没配池时下面不排这个 runnable。
                    scheduleLongPress()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > tapSlop || abs(dy) > tapSlop)) moved = true
                    if (!moved) return@setOnTouchListener true
                    cancelLongPress()
                    // ⚠️ 位移量累加到**按下时的 window 坐标**上，不能直接用 rawX/rawY
                    rawX = downWinX + dx.roundToInt()
                    rawY = downWinY + dy.roundToInt()
                    // 轨迹采样用**手指位置**（rawX/rawY 是手指的屏幕坐标），不是
                    // 窗口坐标 —— 估速要的是"手甩多快"，与窗口偏移无关。
                    trail.add(ev.rawX, ev.rawY, ev.eventTime)
                    // 拖拽姿势：**只在真的拖起来之后**切，否则轻点一下也会闪一下
                    // "被拎起来"的动作。
                    if (!draggingPose) {
                        draggingPose = true
                        showDragPose()
                    }
                    val (nx, ny) = clampInto(rawX, rawY)
                    moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = moved
                    val heldMs = ev.eventTime - downAt
                    dragging = false
                    cancelLongPress()
                    if (draggingPose) {
                        draggingPose = false
                        // ⚠️ **先清掉拖拽姿势再重抽**：此刻演的是 `drag` 池那个
                        //   "被拎起来"的动作，不清掉会继续演它 —— 宠物已经被放下了
                        //   却还在挣扎。清空后交回状态映射（或 idle 时重抽随机链）。
                        clearManualAction()
                        if (runState == BubbleAppearance.RUN_IDLE) rollNextChainAction()
                        applyActionLayer(boxReq())
                    }
                    if (!wasDrag) {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            // 单击 = 请求刷新余额（若用户在徽标设置里开了「点击刷新」）。
                            //
                            // ⚠️ **无条件自增**，不在这里判断开关：开关是**页面侧**的事
                            //   （它自己决定要不要轮询这个计数）。原生这边只负责"记下
                            //   用户点了球"，判断留给知道配置的那一侧 —— 否则同一个开关
                            //   要在两处各读一遍 SharedPreferences，还容易漂。
                            // ⚠️ 双击也是两次 ACTION_UP，因此会自增两次。这是**有意的**：
                            //   页面侧有 `tapRefreshMinIntervalSec` 兜底，连点不会打穿接口，
                            //   而"双击刷新"这个用户诉求只需要"两次点击都能触发刷新"。
                            runCatching { MainActivity.bumpTapSeq() }
                            // ⚠️ 配了 `click` 池 → 单击是"逗宠物"；长按才展开。
                            //   没配 → 单击照旧展开（老行为，零影响）。
                            val hasClickPool = !appearance.pools[BubbleAppearance.POOL_CLICK].isNullOrEmpty()
                            // ⚠️⚠️ **开了「点球刷新」时，单击只刷新余额，不展开面板。**
                            //
                            // 真机反馈原话：「我开了之后，我点球，它还是展开我们的那个对话小窗」。
                            // 根因就是这里：`bumpTapSeq()` 只是"记了一笔点击"，但紧接着
                            // `onTap()` 照样把面板拉出来了 —— 用户的注意力全在面板上，
                            // 完全看不出余额刚刷新过，等于这个开关白开。
                            //
                            // 语义上也不该并存：用户特意打开「点球刷新」，表达的就是
                            // "点球这个手势归刷新用"。展开面板仍有**长按**这个入口
                            // （见下面的 `LONG_PRESS_MS` 分支），不会失去能力。
                            val tapRefreshOnly = appearance.balanceBadge.refreshOnTap
                            if (hasClickPool && heldMs < LONG_PRESS_MS) {
                                playClickPose()
                            } else if (!hasClickPool) {
                                if (tapRefreshOnly && heldMs < LONG_PRESS_MS) {
                                    // 只刷新：`bumpTapSeq` 已经在上面记了这一笔，
                                    // 页面侧轮询到就会重新拉余额并回推徽标。
                                    Log.i(DshApplication.TAG, "单击球 = 刷新余额（不展开面板）")
                                    // 先给一个"点到了"的可见反馈，不等数据回来。
                                    pulseBalanceBadge()
                                    playClickPose()
                                } else {
                                    onTap()
                                }
                            }
                        }
                    } else {
                        onTouchUp(allowEdgeCollapse)
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 拖动松手后的落位。
     *
     * 三种结局，**按这个顺序**判：
     * 1. **甩出去了**（松手速度超过死区）→ 抛掷飞行（[startThrow]），四壁反弹、
     *    落地弹跳，最后**停在哪就留在哪**。这是"宠物"的手感。
     * 2. **拖出了屏幕**（越界量超过 [EDGE_OVERSHOOT_DP]）→ 贴边折叠（[enterEdge]）。
     * 3. 否则**留在松手的位置**，不吸附。
     *
     * ⚠️ **③ 原来是 `snapToEdge()`（强制吸到左右边缘），这是错的。**
     *   电脑版压根没有"吸附边缘"这个概念：宠物扔到哪就待在哪，屏幕正中间也行。
     *   强制吸附的后果是"球永远只能贴两边"，用户**没法把它放在屏幕中间** ——
     *   而悬浮球本来就是"我想放哪就放哪"的东西。
     *   真机反馈原话：「它不像电脑一样可以放在手机屏幕上的任何一个位置，
     *   而是会自动回落到屏幕的两边」。
     *
     * ⚠️ 但**吸附仍然保留一个入口**：`edgeSnap` 是 AI 可配的字段，把它设为 true
     *   就恢复老行为（贴边更省屏幕，是不少人的偏好）。默认 false = 学电脑。
     *
     * ⚠️ **抛掷必须排在贴边之前**：一次"往外甩"同时满足"速度大"和"越界多"，
     *   而用户甩出去时想要的是看它飞、弹、停，不是立刻变成一条缝。反过来的话
     *   甩的动作永远触发贴边，抛掷形同虚设。
     *
     * ⚠️ 判定必须用 [rawX]（未夹取）而不是 [curX]（已夹取）。夹取会把越界量抹掉，
     *   用 curX 判的结果永远是"没越界"，贴边永远不触发。
     */
    private fun onTouchUp(allowEdgeCollapse: Boolean) {
        val dm = context.resources.displayMetrics
        val w = root?.width?.takeIf { it > 0 } ?: boxW()
        val overshootLeft = -rawX
        val overshootRight = rawX + w - dm.widthPixels
        val overshoot = maxOf(overshootLeft, overshootRight)

        // ── ① 抛掷：手甩得够快就飞出去 ──────────────────────────────────
        if (appearance.throwEnabled) {
            // ⚠️ **时间基准必须是 `uptimeMillis()`，不能是 `currentTimeMillis()`。**
            //   轨迹点的时刻来自 `ev.eventTime`，那是 `SystemClock.uptimeMillis()`
            //   基准（开机以来毫秒数，通常几千万）；而 `currentTimeMillis()` 是
            //   墙钟（约 1.7e12）。两者相减永远是个天文数字，
            //   `now - point.t <= RELEASE_WINDOW_MS` 恒为假 ⇒ 过滤后一个点都不剩
            //   ⇒ 恒返回 null ⇒ **抛掷永远不触发**。
            //   症状是"怎么甩都只是慢速落位"，而代码看起来完全正确。
            val v = physics.estimateThrow(trail.snapshot(), android.os.SystemClock.uptimeMillis())
            if (v != null) {
                startThrow(v.first, v.second)
                return
            }
        }
        trail.clear()

        // ── ② 拖出屏幕 → 贴边折叠 ──────────────────────────────────────
        // ⚠️ **这一条不再受 `edgeSnap` 管**：把球拖出屏幕是个**明确的手势意图**
        //   （"我要它变成一条缝"），与"松手后被自动吸到边上"是两回事。
        //   原来两者共用一个开关，关掉吸附就会连带废掉贴边能力 —— 那是回归。
        if (allowEdgeCollapse && overshoot >= dp(EDGE_OVERSHOOT_DP)) {
            enterEdge(if (overshootLeft > overshootRight) -1 else 1)
            return
        }
        // ── ③ 就留在松手处（`edgeSnap` 打开时才吸附）────────────────────
        if (appearance.edgeSnap) {
            snapToEdge()
            return
        }
        // 位置落盘：下次启动回到用户放的地方（`snapToEdge` 里也做这件事）。
        persistPosition()
        // 用户可能把它拖到了下方危险区 —— 那里才需要探键盘。
        startImeWatching()
    }

    /** 把当前位置写进配置（用户拖到哪，下次启动就回到哪）。 */
    private fun persistPosition() {
        prefs.edit().putInt(KEY_X, curX).putInt(KEY_Y, curY).apply()
    }

    // ══════════════════════════════════════════════════════════════════
    // 抛掷物理：飞、弹、滚、停、挤压
    // ══════════════════════════════════════════════════════════════════

    /**
     * 开始一段抛掷飞行。
     *
     * 用 `postOnAnimation` 逐帧推进而不是 `ViewPropertyAnimator`：后者只能走预设
     * 曲线，而这里的轨迹依赖**每次撞墙/落地的实时反弹**，是算出来的不是插出来的。
     *
     * ⚠️ 与 [snapToEdge] 同理，动画的是 `layoutParams.x/y`（`moveTo`），
     *   不是 View 的 `translationX/Y` —— 悬浮窗的位置由 WindowManager 决定，
     *   两者会打架（表现为"飞一下又闪回原位"）。
     *
     * @param floorYOverride 本次飞行的**地面高度**（窗口左上角 y 的上界）。
     *   `null` = 用默认值（屏高 - 球高 - 底部留白 - imeHeightPx）。
     *
     *   ⚠️ 键盘顶飞必须传「键盘危险线」，否则球飞上去之后会**原路掉回来** ——
     *   抛掷是纯抛物线，上升和下落对称，落点就是出发点。真机反馈原话：
     *   「它冲上去之后，它后面会重新掉到键盘区域」。把地面抬到危险线上方，
     *   球就只会在这个新地面上弹几下、停在可见区，再也回不到键盘底下。
     */
    private fun startThrow(vx: Float, vy: Float, floorYOverride: Float? = null) {
        val v = root ?: return
        stopThrow()

        throwState = BubblePhysics.State(curX.toFloat(), curY.toFloat(), vx, vy)
        throwLastFrameNs = System.nanoTime()
        Log.i(TAG, "悬浮球抛掷: v=(${vx.roundToInt()}, ${vy.roundToInt()}) px/s")

        val tick = object : Runnable {
            override fun run() {
                val st = throwState ?: return
                // ⚠️ **贴边态绝不能继续跑物理。**
                //
                //   贴边时窗口尺寸从「球」变成了「一条探头」，而物理循环用的是
                //   `v.width/v.height`（此刻已是贴边条的尺寸）。继续下落会有两个后果：
                //   ① 一条探头贴片受重力往下滑，最后停在屏幕底部；
                //   ② 地面判据按贴边条高度算，看起来就是"吸附的图片掉下来了"。
                //
                //   真机反馈原话：「宠物在吸附状态时会由于重力感应掉下来，
                //   并且是以吸附的图片形式掉下来」。所以贴边时直接终止抛掷。
                if (edgeCollapsed) {
                    stopThrow()
                    return
                }
                val now = System.nanoTime()
                val dt = ((now - throwLastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                throwLastFrameNs = now

                val w = v.width.takeIf { it > 0 } ?: boxW()
                val h = v.height.takeIf { it > 0 } ?: boxH()
                val dm = context.resources.displayMetrics
                val minY = dp(TOP_MARGIN_DP).toFloat()
                // 地面有两个来源，优先级：调用方显式指定的落点（键盘顶飞传进来的
                // `floorYOverride`）> 键盘避让算出的地面 `maxWindowY(h)`。
                //
                // ⚠️ 两者不冲突：`maxWindowY` 在无障碍看到键盘时就是键盘顶，而顶飞
                //   的 override 是「安全线上方」—— 顶飞那一刻球还在键盘底下，两个
                //   地面都高于它，谁先谁后不影响结论，但 override 必须优先，否则
                //   这次抛掷会落在键盘顶上而不是用户要的安全线。
                val floor = (floorYOverride ?: maxWindowY(h).toFloat()).coerceAtLeast(minY)
                // ⚠️ 球此刻**还压在键盘底下、正在往上飞**（被弹开的第一段）时，
                //   这一帧绝不能套地面判据：物理层会把"y 大于地面"当成落地，
                //   立刻把球夹到地面并翻转速度 —— 弹射会变成"原地抖一下"。
                //   判据是「在地面之下且速度朝上」，越过地面之后自动恢复常规地面。
                val risingOutOfFloor = st.y > floor && st.vy < 0f

                val step = physics.step(
                    st, dt,
                    minX = 0f,
                    minY = minY,
                    maxX = (dm.widthPixels - w).coerceAtLeast(0).toFloat(),
                    maxY = if (risingOutOfFloor) Float.POSITIVE_INFINITY else floor,
                )

                moveTo(st.x.roundToInt(), st.y.roundToInt())

                if (step.landed) {
                    // 落地挤压：只压 `avatarView`（内容层），不压窗口 ——
                    // 压窗口会改变 layoutParams 尺寸，把"球的位置"也一起改了。
                    playSquash(physics.squashScale(step.impactSpeed))
                }

                if (step.resting) {
                    throwState = null
                    throwRunnable = null
                    // ⚠️ **停稳后留在落点，不吸附边缘。** 电脑版扔完宠物就停在
                    //   落地处，哪怕屏幕正中间。这里原来无条件 `snapToEdge()`，
                    //   后果是"扔出去的球弹几下之后又被吸回左右两边" —— 与用户
                    //   要的"扔出去在屏幕里反弹"完全相悖（真机反馈原话：
                    //   「它不像电脑一样可以放在手机屏幕上的任何一个位置」）。
                    //   `edgeSnap` 打开时才回到老行为。
                    if (appearance.edgeSnap) snapToEdge() else persistPosition()
                    // 落点可能进了下方危险区（也可能离开了）—— 重新评估要不要探键盘。
                    startImeWatching()
                    return
                }
                v.postOnAnimation(this)
            }
        }
        throwRunnable = tick
        v.postOnAnimation(tick)
    }

    /** 停掉抛掷（幂等）。 */
    private fun stopThrow() {
        throwRunnable?.let { root?.removeCallbacks(it) }
        throwRunnable = null
        throwState = null
    }

    // ══════════════════════════════════════════════════════════════════
    // 晃动甩飞
    // ══════════════════════════════════════════════════════════════════

    /**
     * 起晃动检测。**加速度计是普通传感器，零权限**，所以这里不需要任何授权判断 ——
     * 与键盘顶飞完全不同（那个必须有特权通道）。
     */
    private fun startShakeDetection() {
        if (!appearance.shakeEnabled) {
            Log.i(TAG, "晃动甩飞已关闭（appearance.shakeEnabled=false）")
            return
        }
        if (!shakeDetector.isAvailable()) {
            Log.w(TAG, "本机没有加速度计，晃动甩飞不可用")
            return
        }
        shakeDetector.onShake = { vx, vy ->
            // 只在**收起态**甩。展开态时面板是个大窗口，把它甩飞既没有视觉意义，
            // 也会让正在打字的人莫名其妙。
            // ⚠️ 贴边态也不能甩：那时候窗口是一条探头，甩飞会变成
            //   「一张贴边图飞出去」，与抛掷同样的毛病。
            if (attached && !expanded && !edgeCollapsed) {
                startThrow(vx, vy)
            }
        }
        shakeDetector.start()
    }

    private fun stopShakeDetection() {
        shakeDetector.stop()
    }

    // ══════════════════════════════════════════════════════════════════
    // 键盘顶飞
    // ══════════════════════════════════════════════════════════════════

    /**
     * 起键盘探针。
     *
     * ⚠️ 前提是 `appearance.imeFlingEnabled` —— 而它已经在
     * `BubbleAppearance.merge()` 里被特权闸门强制置 false 过（没有 Shizuku/root
     * 时读不到 `dumpsys input_method`，开着也不可能工作）。
     *
     * ## 只在「有遮挡风险」时才探
     *
     * `dumpsys` 单次 95–150 ms，2 秒一轮也占 5%–7% CPU。球停在上半屏时键盘根本
     * 盖不到它，那时探测纯属白烧电 —— 所以这里先算位置，**不在危险区就直接不启动**。
     * 球被拖到下方 / 抛掷落在下方时会重新调本方法（见 [persistPosition] 与
     * `startThrow` 的停稳分支）。
     */
    private fun startImeWatching() {
        if (!appearance.imeFlingEnabled) {
            Log.i(TAG, "键盘顶飞未启用（无特权通道或用户关闭）")
            return
        }
        // ⚠️ **两套检测严格互斥**，见 `KeyboardAvoid.Source`：
        //   无障碍在跑时它已经能事件驱动地拿到键盘真实边界，再让 `dumpsys`
        //   轮询只会打架 —— 无障碍说"收起了"、ADB 那条晚 2 秒还认为弹着，
        //   球的落点会在两个结论之间来回跳。
        if (KeyboardAvoid.source(context) == KeyboardAvoid.Source.A11Y) {
            Log.i(TAG, "键盘探针不启动：无障碍哨兵已在提供键盘状态（更快，无需特权）")
            imeProbe.stop()
            return
        }
        if (!imeProbeWired) {
            imeProbeWired = true
            imeProbe.onShown = { onImeShown() }
        }
        if (!inImeRiskZone()) {
            // 球在安全区：没必要探，停掉省电。
            imeProbe.stop()
            return
        }
        // 立刻探一拍：球可能已经在下方停着、而键盘早就弹着了。
        // ⚠️ 这条日志是**唯一的「探针真的在跑」证据**。正常轮询不打印任何东西，
        //   出问题时光看日志会以为功能没启动 —— 排查先看这一行在不在。
        Log.i(TAG, "键盘探针启动（球在危险区 y=$curY，间隔 ${ImeProbe.POLL_INTERVAL_MS}ms）")
        imeProbe.start(immediate = true)
    }

    /** 球当前是否落在「键盘会盖住」的下方危险区。 */
    private fun inImeRiskZone(): Boolean {
        val dm = context.resources.displayMetrics
        val h = root?.height?.takeIf { it > 0 } ?: boxH()
        val riskTop = (dm.heightPixels * (1f - IME_RISK_RATIO)).roundToInt()
        return curY + h > riskTop
    }

    private fun stopImeWatching() {
        imeProbe.stop()
    }

    /**
     * 键盘**刚从无到有**了。
     *
     * 只在「球收起且落在键盘会盖住的下方区域」时才飞 —— 球在上半屏时飞它一下
     * 反而是干扰。
     */
    private fun onImeShown() {
        if (!attached || expanded) return
        if (!appearance.imeFlingEnabled) return

        val dm = context.resources.displayMetrics
        val h = root?.height?.takeIf { it > 0 } ?: boxH()
        // 键盘大约占屏幕下方 [IME_RISK_RATIO]；球底边越过这条线就算会被盖住。
        val riskTop = (dm.heightPixels * (1f - IME_RISK_RATIO)).roundToInt()
        if (curY + h <= riskTop) {
            // 球在安全区，不用动。
            return
        }

        // ── 落点：把「地面」抬到安全线上方 ─────────────────────────────
        //
        // ⚠️ 这是**关键**。不传的话，球飞上去会原路掉回来（抛物线对称），
        //   用户看到的是「弹一下又躲回键盘底下」，等于没修。真机反馈三条里的
        //   第 3 条就是这个。
        //
        // 取 `riskTop - h`：球**底边**停在安全线上（而不是球顶），这样整个球
        // 都在键盘上方，视觉上才是"逃出去了"。
        val floorY = (riskTop - h).toFloat().coerceAtLeast(dp(TOP_MARGIN_DP).toFloat())

        // 往上甩：横向给一点随机，避免每次都笔直上冲显得死板。
        val vx = (-1..1).random() * SHAKE_RANDOM_VX
        Log.i(TAG, "键盘弹出且球在下方（y=$curY, 危险线=$riskTop, 落点上限=${floorY.roundToInt()}），顶飞")
        startThrow(vx.toFloat(), -IME_FLING_SPEED, floorYOverride = floorY)
    }

    /**
     * Q 弹挤压：竖直压扁再回弹（含过冲）。
     *
     * ⚠️ **与 dsh-pet 逐字一致：只压 `scaleY`，不撑宽 `scaleX`，且锚点在底部。**
     *   电脑版的实现是 `el.style.transform = 'scaleY(s)'` 配 `transformOrigin: 'bottom'`
     *   （见 `src/client/pet.ts` 的 `startSquash`）—— 脚不动、头往下压，观感就是
     *   "被捏扁了"。若额外把 X 撑宽，会变成"横向鼓起来"，那与电脑版**不是**同一个效果。
     *
     * ⚠️ **必须作用在"当前可见的内容层"上，不能只压 [avatarView]。** 内容层优先是
     *   视频（WebView），此时 avatarView 是 `GONE` —— 只压它等于什么都没发生，
     *   症状正是"点了没有压扁感"。所以两个视图都压：可见的那个生效，隐藏的无害。
     *
     * ⚠️ 锚点要设成**底部**（`pivotY = height`）：默认锚点在中心，压下去会变成
     *   "上下一起缩"，宠物像悬在半空；锚在底部才是"踩在地上被压"。
     */
    private fun playSquash(depth: Float) {
        val targets = listOfNotNull(avatarView, videoHost as? View).filter { it.visibility == View.VISIBLE }
        if (targets.isEmpty()) return
        val base = appearance.avatarScale
        val d = depth.coerceIn(0f, 1f)
        squashAnim?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = BubblePhysics.SQUASH_MS
        // ⚠️ 不给插值器：曲线自己带 ease-in 下压 + easeOutBack 回弹过冲，
        //   再叠一层系统插值器会把过冲抹平（那正是"Q 弹"的来源）。
        anim.interpolator = null
        anim.addUpdateListener { a ->
            val s = physics.squashCurve(a.animatedFraction, d)
            for (v in targets) {
                if (v.pivotY != v.height.toFloat()) v.pivotY = v.height.toFloat()
                v.scaleY = base * s
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (squashAnim !== animation) return
                squashAnim = null
                // 收尾必须回到基准值：过冲段的 1.12 若留在视图上，
                // 宠物会一直比正常高一点。
                for (v in targets) v.scaleY = base
            }
        })
        squashAnim = anim
        anim.start()
    }

    private fun moveTo(x: Int, y: Int) {
        curX = x
        curY = y
        val v = root ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.x = x
        p.y = y
        runCatching { wm.updateViewLayout(v, p) }
        // 状态标签跟着球走 —— 漏了这行的话，拖动时球跑了、标签留在原地。
        positionStatusBubble()
        // 余额徽标同理。⚠️ 这里是**所有位置变化的唯一入口**（拖动、飞行、走动、
        // 吸附动画都最终走 moveTo），所以只挂这一处就能让徽标全程跟住。
        positionBalanceBadge()
    }

    /** 夹进可见区域（考虑状态栏与底部手势条）。 */
    private fun clampInto(x: Int, y: Int): Pair<Int, Int> {
        val v = root
        val w = v?.width?.takeIf { it > 0 } ?: boxW()
        val h = v?.height?.takeIf { it > 0 } ?: boxH()
        return clampInto(x, y, w, h)
    }

    /**
     * 按**指定尺寸**夹进可见区域。
     *
     * ⚠️ 需要这个重载是因为切换形态时 `root` 的尺寸还是旧的那个：收起瞬间布局尚未
     *   重测，`root.width` 仍是展开态的 900 —— 拿它算夹取范围会把球留在面板左上角。
     */
    private fun clampInto(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val minY = dp(TOP_MARGIN_DP)
        // 底边与物理循环共用同一个判据（maxWindowY）：键盘可见时是键盘顶，
        // 否则是屏幕底减去 [imeHeightPx]。两边各写一份会让"拖动"与"松手"打架。
        val maxY = maxWindowY(h).coerceAtLeast(minY)
        val nx = x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        val ny = y.coerceIn(minY, maxY)
        return nx to ny
    }

    /**
     * 松手后吸附到最近的左右边缘。
     *
     * 带一段短动画（120ms、8 帧左右）而不是瞬移：瞬移会让人以为"位置丢了"。
     * 用 Handler 逐帧而不是 ViewPropertyAnimator —— 后者动画的是 View 的
     * translationX/Y，而悬浮窗的位置由 WindowManager.LayoutParams 决定，
     * 两者会打架（松手瞬间闪回原位）。
     */
    private fun snapToEdge() {
        val dm = context.resources.displayMetrics
        val v = root ?: return
        val w = v.width.takeIf { it > 0 } ?: boxW()
        val targetX = if (curX + w / 2 < dm.widthPixels / 2) dp(EDGE_MARGIN_DP)
        else dm.widthPixels - w - dp(EDGE_MARGIN_DP)
        val (_, ty) = clampInto(targetX, curY)
        animateTo(targetX, ty)
        prefs.edit().putInt(KEY_X, targetX).putInt(KEY_Y, ty).apply()
    }

    private fun animateTo(toX: Int, toY: Int) {
        val fromX = curX
        val fromY = curY
        val steps = 9
        val handler = Handler(Looper.getMainLooper())
        var i = 1
        val tick = object : Runnable {
            override fun run() {
                val t = i.toFloat() / steps
                // ease-out：起步快、收尾稳，像"被边缘吸过去"
                val e = 1f - (1f - t) * (1f - t)
                moveTo(
                    (fromX + (toX - fromX) * e).roundToInt(),
                    (fromY + (toY - fromY) * e).roundToInt(),
                )
                if (i++ < steps) handler.postDelayed(this, 14)
                else moveTo(toX, toY)
            }
        }
        handler.post(tick)
    }

    // ══════════════════════════════════════════════════════════════════
    // 贴边折叠态
    // ══════════════════════════════════════════════════════════════════

    /**
     * 进入贴边态：球与面板都收起来，只在 [side] 那一侧留一条薄片。
     *
     * 触发条件是「用户把球往屏幕外拖」（见 [onTouchUp]），不是「拖到边缘」——
     * 后者是原有的吸附行为，球仍然完整可见。
     *
     * ⚠️ 位置必须**贴住屏幕边**（x = 0 或 screenW - edgeW），不能沿用球的坐标：
     *   贴边态比球窄，沿用球的 x 会让它浮在半空中。
     */
    private fun enterEdge(side: Int) {
        val dm = context.resources.displayMetrics
        edgeSide = side
        expanded = false
        edgeCollapsed = true
        // ⚠️ 进贴边态必须**立刻掐掉正在跑的抛掷**。
        //   只靠物理 tick 里那个 `if (edgeCollapsed) return` 还不够：
        //   tick 是 `postOnAnimation` 排的，可能还有一帧在队列里，那一帧会用
        //   贴边条的尺寸算一次位置，把刚摆好的贴边位置冲掉。
        stopThrow()
        hideStatusBubble()
        applyFocusFlags(focusable = false)
        panelInput?.let {
            it.setText("")
            it.clearFocus()
        }
        panelView?.visibility = View.GONE
        circleView?.visibility = View.GONE
        edgeView?.visibility = View.VISIBLE

        val edgeW = dp(edgeWidthDp())
        val edgeH = if (appearance.edgeHeight > 0) dp(appearance.edgeHeight) else boxH()
        val x = if (side < 0) 0 else (dm.widthPixels - edgeW)
        // 竖直位置：`edgeAnchor` 给了固定值就用它（探头每次都在同一高度），
        // 否则沿用球被拖出去时的 y —— 用户把球拖到哪，它就贴在哪。
        val y = if (appearance.edgeAnchor >= 0f) {
            val usable = (dm.heightPixels - edgeH).coerceAtLeast(0)
            (usable * appearance.edgeAnchor.coerceIn(0f, 1f)).roundToInt()
        } else {
            curY
        }
        val (nx, ny) = clampInto(x, y, edgeW, edgeH)
        moveTo(nx, ny)
        // ⚠️ **必须重新应用一次贴边图。**
        //
        //   [applyEdgeImage] 只在 `applyAppearanceToViews` 里被调用，而进贴边态
        //   不走那条路。从右边换到左边时方向变了，不重算就会沿用上一次的镜像
        //   （表现为「贴哪边都朝同一个方向」）。
        //   指纹里带了 `edgeSide`，所以这里重复调用是廉价的：方向没变直接 return。
        applyEdgeImage()
        prefs.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply()
        Log.i(TAG, "气泡已贴边折叠: side=$side x=$nx y=$ny w=${edgeWidthDp()}dp h=${edgeH}px")
    }

    /**
     * 退出贴边态，弹回完整球。
     *
     * ⚠️ 位置按 [edgeSide] 算，不是按贴边态当前坐标 —— 贴边态贴左边时它的中心
     *   就在屏幕最左，直接用它的 x 会让球有一半在屏幕外。
     */
    private fun exitEdge() {
        if (!edgeCollapsed) return
        val dm = context.resources.displayMetrics
        edgeCollapsed = false
        edgeView?.visibility = View.GONE
        circleView?.visibility = View.VISIBLE

        val sidePx = boxW()
        val sidePxH = boxH()
        val x = if (edgeSide < 0) dp(EDGE_MARGIN_DP) else dm.widthPixels - sidePx - dp(EDGE_MARGIN_DP)
        val (nx, ny) = clampInto(x, curY, sidePx, sidePxH)
        moveTo(nx, ny)
        prefs.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply()
        Log.i(TAG, "气泡已从贴边态弹出: side=$edgeSide x=$nx y=$ny")
    }

    /** 贴边薄片的触摸：只处理点击（拖动它没有意义 —— 它已经贴在边上了）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun installEdgeTouch(target: View) {
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWinX = curX
                    downWinY = curY
                    rawX = curX
                    rawY = curY
                    dragging = true
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > tapSlop || abs(dy) > tapSlop)) moved = true
                    if (!moved) return@setOnTouchListener true
                    rawX = downWinX + dx.roundToInt()
                    rawY = downWinY + dy.roundToInt()
                    val (nx, ny) = clampInto(rawX, rawY)
                    moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    // ⚠️⚠️ **UP 与 CANCEL 必须一视同仁地退出贴边态**（用户反馈
                    //   「悬浮窗躲进屏幕边框出不来，点击没反应，概率性」的根因）。
                    //
                    // 原实现在「未拖动 + ACTION_CANCEL」这一支上**什么都不做**：
                    //
                    //     if (!wasDrag) {
                    //         if (ev.actionMasked == MotionEvent.ACTION_UP) exitEdge()
                    //     } else { exitEdge() }
                    //
                    // 而薄片宽 6dp 且 `EDGE_MARGIN_DP = 0`（紧贴屏幕最边缘，见
                    // `enterEdge` 里硬编码的 x = 0 / widthPixels - edgeW），正好落在
                    // 系统的**边缘手势返回热区**里。手势一旦被系统识别，触摸会被
                    // 抢走并转成 ACTION_CANCEL —— 于是这个分支被命中，薄片永远
                    // 弹不回来。用户看到的就是「点了没反应」，而「概率性」正是
                    // 因为是否被手势抢走取决于 ROM 的手势设置与手指落点。
                    //
                    // 语义上也该退出：CANCEL 的含义是「这次触摸被中断了」，用户
                    // 的意图无从判断；而此刻**唯一安全的选择就是把球弹回完整形态**
                    // —— 球比 6dp 薄片好点得多，用户下一手必然能操作到。反过来
                    // （维持贴边态）才是把用户困住的唯一路径。
                    //
                    // ⚠️ 顺带解决了另一条同类路径：手指按下后滑出这 6dp 的边界时，
                    //   Android 同样发 CANCEL（原实现同样吞掉）。
                    exitEdge()
                    true
                }

                else -> false
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 展开 / 收起
    // ══════════════════════════════════════════════════════════════════

    private fun expand() {
        if (expanded) return
        // ⚠️ **展开前先记住收起态的位置。**
        //
        // 展开时为了把 260dp 宽的面板夹进屏幕，`curX` 会被改掉（贴右边时
        // 从 912 变成 180）。收起时如果只做一次 clamp，球就会停在**面板的左上角**
        // 而不是展开前的位置 —— 用户看到的是"点一下收起，球跑别处去了"。
        preExpandX = curX
        preExpandY = curY
        // 贴边态点开时先弹回完整球，否则收起会回到一条缝。
        if (edgeCollapsed) {
            edgeCollapsed = false
            edgeView?.visibility = View.GONE
        }
        expanded = true
        circleView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
        // 展开态才允许聚焦 —— 否则点输入框弹不出键盘（见 applyFocusFlags）。
        applyFocusFlags(focusable = true)
        // 展开态面板里已有同一份信息，收起态那个标签要让位。
        hideStatusBubble()
        lastStatus?.let { applyStatus(it) }
        // 转录在球形态下是"脏"的（没渲染），展开时补一次。
        if (transcriptDirty) renderTranscript()
        // 展开后重新夹一次：面板比圆大得多，原来贴边的位置会露到屏幕外
        root?.post {
            val (nx, ny) = clampInto(curX, curY)
            moveTo(nx, ny)
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        // 收起态不抢焦点：让下层应用照常收按键，也顺手收掉键盘。
        applyFocusFlags(focusable = false)
        panelInput?.let {
            it.setText("")
            it.clearFocus()
        }
        panelView?.visibility = View.GONE
        circleView?.visibility = View.VISIBLE
        // 回到收起态，状态标签重新露面（内容与之前相同，所以会重新计时 2 秒）。
        statusKey = ""
        lastStatus?.let { refreshStatusBubble(it) }
        // 回到展开前的位置（不是面板左上角）。
        //
        // ⚠️ 这里**不能**用 `clampInto(curX, curY)`：那个重载读的是 `root` 当前尺寸，
        //   而此刻布局还没重测完，读到的可能仍是展开态的 300×500，算出来的夹取范围
        //   是错的。收起态尺寸是恒定的（画布尺寸），直接按它算。
        root?.post {
            val side = boxW()
            val sideH = boxH()
            val (nx, ny) = clampInto(preExpandX, preExpandY, side, sideH)
            moveTo(nx, ny)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 面板拖动 + 缩放
    // ══════════════════════════════════════════════════════════════════

    /**
     * 面板拖动：手势载体是顶栏。
     *
     * ⚠️ 复用 [installTouch] 而不是另写一套：拖动逻辑（绝对坐标累加、点击/拖动
     *   判别、夹取）完全一样，只有"点击"的语义不同（顶栏点一下没有动作）。
     */
    private fun installPanelDrag(header: View) {
        installTouch(header, onTap = { /* 顶栏点一下不做任何事 */ }, allowEdgeCollapse = false)
    }

    /**
     * 面板缩放：拖底部手柄。
     *
     * 竖直方向改**转录区高度**（面板其余部分是固定高度），水平方向改面板宽度。
     *
     * ⚠️ 只改转录区高度、不改面板总高：面板是 `wrap_content`，改内容高度它自己会
     *   长高。反过来（直接给面板定高）会让输入框与顶栏被挤掉 —— 用户拖一下就把
     *   发送按钮拖没了，那是灾难。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installPanelResize(grip: View) {
        grip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWidth = panelView?.width ?: dp(appearance.panelWidth)
                    downHeight = panelScroll?.height ?: dp(200)
                    resizing = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!resizing) return@setOnTouchListener false
                    val dm = context.resources.displayMetrics
                    val dx = (ev.rawX - downRawX).roundToInt()
                    val dy = (ev.rawY - downRawY).roundToInt()

                    // 宽度：夹在 [最小宽, 屏幕宽] 之内
                    val maxW = dm.widthPixels
                    val newW = (downWidth + dx).coerceIn(dp(PANEL_MIN_WIDTH_DP), maxW)
                    panelView?.let { p ->
                        (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                            if (lp.width != newW) {
                                lp.width = newW
                                p.layoutParams = lp
                            }
                        }
                    }

                    // 高度：只改转录区
                    val maxH = (dm.heightPixels - dp(220)).coerceAtLeast(dp(TRANSCRIPT_MIN_DP))
                    val newH = (downHeight + dy).coerceIn(dp(TRANSCRIPT_MIN_DP), maxH)
                    panelScroll?.let { s ->
                        (s.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            if (lp.height != newH) {
                                lp.height = newH
                                s.layoutParams = lp
                            }
                        }
                    }
                    // 面板变宽/变高后可能露出屏幕，拖完前先夹一次位置
                    val (nx, ny) = clampInto(curX, curY)
                    if (nx != curX || ny != curY) moveTo(nx, ny)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!resizing) return@setOnTouchListener false
                    resizing = false
                    persistPanelSize()
                    true
                }

                else -> false
            }
        }
    }

    /** 把当前面板宽度与转录区高度落盘（下次开还是这个尺寸）。 */
    private fun persistPanelSize() {
        val w = panelView?.width ?: 0
        val h = panelScroll?.height ?: 0
        prefs.edit()
            .putInt(KEY_PANEL_W, if (w > 0) w else 0)
            .putInt(KEY_TRANSCRIPT_H, if (h > 0) h else 0)
            .apply()
        // 顺手把「面板总高」写回外观配置：插件读 bubbleAppearance() 时能看到用户
        // 手动调过的大小，而不是它当初设的那个值。
        val total = panelView?.height ?: 0
        if (total > 0) {
            appearance = appearance.copy(panelHeight = (total / density).roundToInt())
            BubbleAppearance.save(context, appearance)
        }
        Log.i(TAG, "面板尺寸已保存: ${w}px × ${h}px（转录区）")
    }

    /** 恢复上次保存的面板尺寸。 */
    private fun restorePanelSize() {
        val dm = context.resources.displayMetrics
        prefs.getInt(KEY_PANEL_W, 0).takeIf { it > 0 }?.let { w ->
            panelView?.let { p ->
                (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = w.coerceIn(dp(PANEL_MIN_WIDTH_DP), dm.widthPixels)
                    p.layoutParams = lp
                }
            }
        }
        prefs.getInt(KEY_TRANSCRIPT_H, 0).takeIf { it > 0 }?.let { h ->
            panelScroll?.let { s ->
                (s.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.height = h
                    s.layoutParams = lp
                }
            }
        }
        // 插件设过 panelHeight（且用户没手动调过）时以插件为准。
        if (prefs.getInt(KEY_TRANSCRIPT_H, 0) <= 0 && appearance.panelHeight > 0) {
            panelView?.post { applyConfiguredPanelHeight() }
        }
    }

    /**
     * 按插件配置的 `panelHeight`（面板**总高**）反推转录区高度。
     *
     * ⚠️ 必须 post 到布局之后：固定部分（顶栏 + 标题 + 输入行 + 手柄）的实际高度
     *   只有量过才知道，配置里给的是总高。差值就是转录区该占的高度。
     */
    private fun applyConfiguredPanelHeight() {
        val total = appearance.effectivePanelHeight(
            context.resources.displayMetrics.heightPixels, density,
        )
        if (total <= 0) return
        val p = panelView ?: return
        val s = panelScroll ?: return
        val fixed = p.height - s.height
        if (fixed <= 0) return
        val target = (total - fixed).coerceAtLeast(dp(TRANSCRIPT_MIN_DP))
        (s.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.height = target
            s.layoutParams = lp
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 面板交互
    // ══════════════════════════════════════════════════════════════════

    private fun installPanelActions(view: View) {
        // 「打开」：回到 App（气泡会在 onStart 里被收起）
        view.findViewById<TextView>(R.id.panelOpenApp).setOnClickListener {
            openApp?.invoke()
        }

        // 「收起」：退回圆形
        view.findViewById<TextView>(R.id.panelCollapse).setOnClickListener { collapse() }

        // 「发送」：把输入框内容投给页面里的官方 composer
        view.findViewById<TextView>(R.id.panelSend).setOnClickListener { doSend() }

        // 「停止」：中断当前这一轮。页面侧会先验主按钮此刻是不是停止键 ——
        // 盲点它会把用户的草稿发出去。
        view.findViewById<TextView>(R.id.panelStop).setOnClickListener { doStop() }

        // 回车也发送。多行内容靠换行符一次性送进去（手机上打不出 Shift+Enter）。
        panelInput?.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            if (isSend) {
                doSend()
                true
            } else false
        }

        // ⚠️ **点输入框必须主动拉一次输入法。**
        //
        // 悬浮窗不是 Activity，系统不会替它自动弹键盘：窗口拿到焦点 ≠ 输入法会
        // 出现。真机实测（华为 CDY-TN90 / Android 10）：只设可聚焦标志的话，
        // 点输入框有光标、能收到按键事件，但键盘始终不弹。
        //
        // 所以显式调 `showSoftInput`。放在 `post` 里是为了等窗口真正拿到焦点 ——
        // 展开动画/updateViewLayout 之后焦点才生效，同步调会静默失败。
        panelInput?.setOnFocusChangeListener { _, hasFocus ->
            // 聚焦 ⇒ 键盘要来，先给面板腾出空间（否则输入框被键盘盖住，用户看不见
            // 自己在打什么）；失焦 ⇒ 键盘要走，把空间还回去。高度用 MainActivity
            // 学到的真实值，取不到就按屏高的 40% 估一个 —— 宁可多留一点，
            // 也不能让输入框藏在键盘底下。
            applyImeHeight(if (hasFocus) keyboardHeightPx() else 0)
            if (hasFocus) {
                panelInput?.post { showInputMethod() }
            }
        }

        // 点一下输入框本身也要触发上面那条 —— 已经聚焦时再点不会触发
        // onFocusChange，但用户很可能就是"再点一次想唤出键盘"。
        panelInput?.setOnClickListener { showInputMethod() }
    }

    /**
     * 拉一次输入法（幂等；任何一步不可用都安静返回）。
     *
     * ⚠️ **这里每一个 `?` 都不是多余的，去掉任意一个都会崩主线程。**
     *
     * 真机现场（`20261014-083111-uncaught-exception.txt`，某小米机型 /
     * Android 16，`thread: main`）：
     *
     * ```
     * java.lang.NullPointerException: Attempt to invoke virtual method
     *   'android.view.ViewRootImpl android.view.View.getViewRootImpl()'
     *   on a null object reference
     *   at android.view.inputmethod.InputMethodManager.showSoftInput(…:2558)
     *   at com.dsh.android.FloatingBubble.installPanelActions$lambda$…
     * ```
     *
     * **根因**：`showSoftInput(View, int)` 的第一个参数在 Java 侧没有 `@NonNull`
     * 标注 ⇒ Kotlin 看到的是**平台类型 `View!`** ⇒ 传 null **编译期不报错**。
     * 于是「展开态被收起 / 面板被销毁（`panelInput = null`）」与 `post{}` 回调
     * 之间那几毫秒的竞争，就把一个 null 送进了系统方法，在**主线程**上炸掉。
     *
     * 而 `panelInput?.post { … }` 那个安全调用**只保证了「谁去 post」不为空，
     * 不保证「回调跑的时候」还不为空** —— 这正是一眼看去以为已经防住了的陷阱。
     *
     * 所以取参数必须用**局部变量 + 空判断**重取一次，不能用捕获时的那个字段。
     */
    private fun showInputMethod() {
        val input = panelInput ?: return
        // 视图没挂到窗口上时（收起动画、已 detach）showSoftInput 同样会走到
        // getViewRootImpl()，所以先确认它真的还在窗口里。
        if (input.windowToken == null) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        runCatching { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
            .onFailure { Log.w(TAG, "唤起输入法失败（面板可能已收起）: ${it.javaClass.simpleName}") }
    }

    private fun doSend() {
        val input = panelInput ?: return
        val text = input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        val fn = sendText
        if (fn == null) {
            toast("发送通道未就绪")
            return
        }
        val ok = try {
            fn(text)
        } catch (t: Throwable) {
            Log.w(TAG, "气泡发送失败: ${t.message}")
            false
        }
        if (ok) {
            input.setText("")
            toast("已发送")
        } else {
            toast("发送失败，请打开 App")
        }
    }

    private fun toast(msg: String) {
        runCatching { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /**
     * 中断当前这一轮。
     *
     * ⚠️ 只回报"已投递"：真正的执行结果由页面决定（它可能发现主按钮此刻不是
     *   停止键而拒绝）。日志里会有 `STOPPED` / `NOT_RUNNING` 可查。
     */
    private fun doStop() {
        val fn = stopRun
        if (fn == null) {
            toast("停止通道未就绪")
            return
        }
        val ok = try {
            fn()
        } catch (t: Throwable) {
            Log.w(TAG, "气泡停止失败: ${t.message}")
            false
        }
        toast(if (ok) "已请求中断" else "中断失败，请打开 App")
    }

    // ══════════════════════════════════════════════════════════════════
    // 状态渲染
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把一份状态快照刷到气泡上。
     *
     * ⚠️ 全程 try/catch：气泡是锦上添花，任何渲染异常都不能影响引擎与主界面。
     */
    private fun applyStatus(status: EngineStatus) {
        lastStatus = status
        // ⚠️ 先推状态、再换动作：换动作会重启轮播（一次 I/O + 一次解码），
        //   而状态横条是用户立刻能看到的东西。顺序反了会让横条慢一拍。
        syncRunState(status)
        try {
            paintDot(dotView, status.running)
            paintDot(panelDot, status.running, small = true)
            // ⚠️ 贴边态的状态点**默认不显示**（见 [applyEdgeImage] 里的可见性）。
            //   它居中压在探头人物脸上，真机反馈「挡到角色了」。
            //   只有在用户显式 `showDot: true` 时才画。
            if (appearance.showDot) paintDot(edgeDot, status.running, small = true)
            refreshStatusBubble(status)

            // 「停止」只在 AI 真在跑时出现。常驻的话用户会去点它 ——
            // 而空闲时那个位置的主按钮是「发送消息」，点下去会把草稿发出去。
            panelStop?.visibility = if (status.running) View.VISIBLE else View.GONE

            val action = when {
                status.text.isNotEmpty() -> status.text
                status.running -> "思考中…"
                else -> "空闲"
            }
            panelAction?.text = action

            panelTitle?.let {
                if (status.title.isNotEmpty()) {
                    it.text = status.title
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.GONE
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "刷新气泡状态失败: ${t.message}")
        }
    }

    /**
     * 按一份状态决定收起态标签显示什么。
     *
     * **空闲且没有动作时不显示** —— 一个写着「空闲」的标签对用户毫无价值，
     * 反而常驻在屏幕上碍事。只在真有进展可看时才冒出来。
     *
     * ⚠️ 正文（`body`）刻意不放进标签：它是整段助手消息，几行字根本装不下，
     *   截断后只剩一句没头没尾的话。标签只放**动作**（"正在运行命令 · sleep 10"
     *   这类短句），要看正文就展开面板。
     */
    private fun refreshStatusBubble(status: EngineStatus) {
        // AI 主动推的话优先。引擎状态每几秒就推一次，不给它让路的话
        // 推送会在下一帧就被冲掉 —— 那这个能力等于不存在。
        val pushed = pushedStatus
        if (pushed.isNotEmpty()) {
            showStatusBubble(pushed, status.running, pushedAutoHideMs(), showImage = false)
            return
        }
        // AI 配的状态文案排第二：它是**AI 特意为这个状态写的台词**，
        // 比引擎报来的原始动作串更贴宠物的人设。
        //
        // ⚠️ 判据是「文案**或**配图有一个配了」，不是只看文案：配了状态图就等于
        //   AI 声明"这个状态我自己表达"。此时若还往下走引擎那一路，气泡里会变成
        //   「表情包 + 正在运行命令 · sleep 10」，两种语气混在一起。
        //   只配图不配文案正是最常见的用法（只想要头顶的表情包），必须走得通。
        if (workStatusText.isNotEmpty() || statusImagePath.isNotEmpty()) {
            showStatusBubble(workStatusText, status.running)
            return
        }
        val text = when {
            status.text.isNotEmpty() -> status.text
            status.running -> "思考中…"
            // 空闲时若还有正文（上一轮的结论），也值得露一句 —— 但只取首行，
            // 且限长，否则标签会变成一大块。
            status.body.isNotEmpty() -> firstLine(status.body)
            else -> ""
        }
        showStatusBubble(text, status.running)
    }

    // ══════════════════════════════════════════════════════════════════
    // AI 主动推送状态消息
    // ══════════════════════════════════════════════════════════════════

    /**
     * 让 AI 往球上方的状态气泡推一条消息。
     *
     * 与引擎状态（[applyStatus]）是**两条独立的通道**：引擎状态是"页面里发生了什么"
     * 的只读镜像，这个是 AI 主动要说的话。推送期间前者让位（见 [refreshStatusBubble]）。
     *
     * @param text 要显示的文案；传空串即取消推送、立刻回到引擎状态。
     * @param durationMs 停留时长（ms）；≤0 表示用外观配置里的 `statusAutoHideMs`。
     * @param sticky 常驻，不自动消失（`durationMs` 被忽略）。
     */
    fun pushStatus(text: String, durationMs: Long, sticky: Boolean) {
        statusHandler.removeCallbacks(pushedClearRunnable)
        val trimmed = text.trim().take(200)
        if (trimmed.isEmpty()) {
            pushedStatus = ""
            pushedSticky = false
            pushedDurationMs = 0L
            lastStatus?.let { refreshStatusBubble(it) }
            return
        }
        pushedStatus = trimmed
        pushedSticky = sticky
        pushedDurationMs = if (durationMs > 0) durationMs else 0L
        if (!sticky) {
            // 时长未知（durationMs ≤ 0）时不排清除：交给 statusAutoHideMs 那套
            // 自动消失逻辑收尾，免得推送已经消失了推送标记还挂着。
            if (pushedDurationMs > 0) {
                statusHandler.postDelayed(pushedClearRunnable, pushedDurationMs + STATUS_PUSH_GRACE_MS)
            }
        }
        // 用当前状态刷新一次：这样即使引擎状态没变（key 相同），推送也能立刻上屏。
        // ⚠️ 推送是"AI 主动说的话"，所以**不带状态配图**（showImage=false）：
        //   配图描述的是运行状态（想事情 / 干活 / 出错），而一条自由文案与当前
        //   状态没有关系 —— 挂上当前状态的图会表达错意思。要图就在配置里配
        //   `statusImages`，让状态自己带图。
        lastStatus?.let { refreshStatusBubble(it) } ?: run {
            statusKey = ""
            showStatusBubble(pushedStatus, false, pushedAutoHideMs(), showImage = false)
        }
    }

    /** 推送该停留多久（ms）；0 = 常驻（不自动消失）。 */
    private fun pushedAutoHideMs(): Int = when {
        pushedSticky -> 0
        pushedDurationMs > 0 -> pushedDurationMs.toInt()
        else -> appearance.statusAutoHideMs
    }

    /** 推送到期：清掉推送标记并回到引擎状态。 */
    private val pushedClearRunnable = Runnable {
        pushedStatus = ""
        pushedSticky = false
        pushedDurationMs = 0L
        // ⚠️ 先清 key 再刷新：否则内容相同的引擎状态会被 key 判成"没变"而不上屏，
        //   横条就一直空着（自动消失已经把它隐藏了，但状态其实还在变）。
        statusKey = ""
        lastStatus?.let { refreshStatusBubble(it) }
    }

    /** 取首行并限长。标签是单行小标签，多行内容会把球顶得老高。 */
    private fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= statusMaxChars) line else line.take(statusMaxChars - 1) + "…"
    }

    /**
     * 状态点：在跑=实心绿，空闲=空心灰。
     *
     * ## 大点带一圈白边（只有收起态的头像气泡需要）
     *
     * 头像的头发是深蓝色，灰/绿的圆点直接贴上去对比度很低、看不清。垫一圈白边
     * 把它从背景里"抠"出来 —— 与系统通知角标同一种手法。
     *
     * ⚠️ 白边用 `LayerDrawable` 套两层 Oval 实现，**不用 `<layer-list>` 资源**：
     *   这里本来就要动态改色，做成资源反而要多传参数。白边层是外层（稍大），
     *   颜色层是内层，靠 `setLayerInset` 缩进去。
     *
     * ⚠️ 只换 background，**绝不碰 layoutParams**：`dotView` 在 FrameLayout 里、
     *   `panelDot`/`statusDot`/`edgeDot` 在 LinearLayout/FrameLayout 里，
     *   两者的 LayoutParams 类型不同，强行赋一个 FrameLayout.LayoutParams 会
     *   `ClassCastException`。尺寸由 XML 与 [applyAppearanceToViews] 管。
     */
    private fun paintDot(v: View?, running: Boolean, small: Boolean = false) {
        if (v == null) return
        // 小点（4~8dp）用 1dp 描边，大点（14dp）用 2dp —— 同样 2dp 在 8dp 的圆上
        // 会糊成一坨，看不出是空心。
        val strokePx = if (small) dp(1) else dp(2)
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (running) {
                setColor(colorOr(appearance.panelAccent, DEFAULT_RUNNING))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, 0xFFB9BFC7.toInt())
            }
        }
        if (small) {
            // 面板/薄片里的小点直接贴在浅色底上，不需要白边
            v.background = fill
            return
        }
        val border = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        val inset = dp(2)
        v.background = LayerDrawable(arrayOf(border, fill)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 会话转录渲染（展开面板的核心内容）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 收到一份新的转录。
     *
     * ⚠️ 球形态下**不渲染**，只置脏标记 —— 面板不可见时重建几十个 View 是白费，
     *   而转录在 AI 跑动时每几秒就会推一次。展开时（[expand]）会补渲染。
     */
    private fun applyTranscript(transcript: ChatTranscript) {
        lastTranscript = transcript
        transcriptDirty = true
        if (expanded) renderTranscript()
    }

    /**
     * 把转录渲染成消息行列表。
     *
     * ## 为什么要按指纹跳过
     *
     * 状态通道每 5 秒推一次，转录通道在 DOM 变化时推得更密。如果每次都清空重建
     * 整个 View 树，用户会看到消息在闪、滚动位置也守不住。指纹相同就直接返回。
     *
     * ## 滚动位置
     *
     * 重建后如果用户**本来就在底部**，自动滚到底（流式输出时这是他要的）；
     * 如果他往上翻了在看历史，就**不要**动 —— 强行拽回底部是最恼人的行为。
     */
    private fun renderTranscript() {
        val container = panelTranscript ?: return
        val t = lastTranscript ?: return
        transcriptDirty = false

        val fp = t.items.joinToString("\u0001") { "${it.role}|${it.running}|${it.text}" }
        if (fp == transcriptFingerprint) return
        transcriptFingerprint = fp

        val scroll = panelScroll
        // 判定"重建前是否贴着底部"，重建后再决定要不要跟到底
        val wasAtBottom = scroll?.let { s ->
            val child = s.getChildAt(0)
            child == null || s.scrollY + s.height >= child.height - dp(24)
        } ?: true

        // ── 增量更新 ──
        //
        // 流式输出期间这条路径每秒要走好几次（页面侧节流 250ms），全量
        // `removeAllViews()` + 重建会让整块内容闪一下，正好把"流式"的观感毁掉。
        // 所以：**前缀 role 序列一致就复用已有 view，只改文本**；role 序列变了
        // （用户插话、条目整体前移）才退回全量重建。
        //
        // 复用之所以安全：同一个 role ⇒ 同一套样式（对齐 / 底色 / 字号），
        // 复用时不需要也不应该重设样式。
        val reuseCount = if (
            t.items.isNotEmpty() &&
            container.childCount == renderedRoles.size &&
            renderedRoles.size <= t.items.size &&
            t.items.take(renderedRoles.size).map { it.role } == renderedRoles
        ) renderedRoles.size else -1

        if (reuseCount < 0) {
            container.removeAllViews()
            renderedRoles = emptyList()
        }

        val inflater = LayoutInflater.from(context)
        for (i in t.items.indices) {
            val item = t.items[i]
            if (i < reuseCount) {
                updateItemText(container.getChildAt(i), item)
            } else {
                container.addView(renderItem(inflater, item))
            }
        }
        if (t.items.isEmpty() && container.childCount == 0) {
            container.addView(
                makeEmptyHint(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        renderedRoles = t.items.map { it.role }

        if (wasAtBottom) {
            scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 复用已有行时只更新文本（见 [renderTranscript] 的增量分支）。
     *
     * ⚠️ 标签走 [roleLabel]，与 [renderItem] 同源 —— 两处各写一份的话，
     *   改文案时必然漂移成"新建的行和复用的行长得不一样"。
     */
    private fun updateItemText(row: View, item: ChatTranscript.Item) {
        val roleView = row.findViewById<TextView>(R.id.msgRole) ?: return
        val bodyView = row.findViewById<TextView>(R.id.msgBody) ?: return
        if (bodyView.text?.toString() != item.text) bodyView.text = item.text
        val label = roleLabel(item)
        if (roleView.text?.toString() != label) roleView.text = label
    }

    /** 一条消息的角色标签。用户消息不显示（蓝色气泡本身就是标志）。 */
    private fun roleLabel(item: ChatTranscript.Item): String = when (item.role) {
        ChatTranscript.Role.TOOL -> if (item.running) "工具调用 · 进行中" else "工具调用"
        ChatTranscript.Role.ERROR -> "错误"
        ChatTranscript.Role.REASONING -> if (item.running) "AI 思考 · 进行中" else "AI 思考"
        ChatTranscript.Role.ASSISTANT -> if (item.running) "AI · 正在输出" else "AI"
        else -> item.role
    }

    /** 转录为空时的提示。空面板会让人以为界面坏了。 */
    private fun makeEmptyHint(): TextView = TextView(context).apply {
        text = "（这个会话还没有内容）"
        textSize = 12f
        setTextColor(colorOr(appearance.panelText, DEFAULT_TEXT) and 0x00FFFFFF or 0x66000000)
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
    }

    /**
     * 渲染一条消息。
     *
     * 五种角色的排版：
     *
     * | role | 对齐 | 底色 | 文字 | 角色标签 |
     * |---|---|---|---|---|
     * | user | 右 | 强调色 | 白 | 不显示（蓝泡本身就是标志） |
     * | assistant | 左 | 白 | 深 | 「AI」 |
     * | tool | 左 | 浅灰 | 深灰 | 「工具调用」+ 运行中标记 |
     * | error | 左 | 浅红 | 深红 | 「错误」 |
     * | other | 左 | 白 | 深 | 原始 role |
     *
     * ⚠️ 左右对齐靠**行容器 gravity**，不是 margin：两者共用同一个 XML 模板，
     *   只有 gravity 与背景不同。
     * ⚠️ 正文 **不设 maxLines**：这里就是要看完整内容，行数多靠外层 ScrollView 滚。
     */
    private fun renderItem(inflater: LayoutInflater, item: ChatTranscript.Item): View {
        val row = inflater.inflate(R.layout.bubble_msg, panelTranscript, false) as LinearLayout
        val roleView = row.findViewById<TextView>(R.id.msgRole)
        val bodyView = row.findViewById<TextView>(R.id.msgBody)

        val accent = colorOr(appearance.panelAccent, DEFAULT_ACCENT)
        val textColor = colorOr(appearance.panelText, DEFAULT_TEXT)

        // 气泡宽度上限：面板宽度的 84%，留出左右各自的边距 —— 否则用户消息
        // （右对齐）会一直顶到面板最左边，跟助手消息糊在一起分不清。
        val panelW = panelView?.width?.takeIf { it > 0 }
            ?: appearance.effectivePanelWidth(context.resources.displayMetrics.widthPixels, density)
        val maxBubble = (panelW * 0.84f).roundToInt()

        when (item.role) {
            ChatTranscript.Role.USER -> {
                row.gravity = Gravity.END
                roleView.visibility = View.GONE
                bodyView.background = bubbleShape(accent, dp(14))
                bodyView.setTextColor(Color.WHITE)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.TOOL -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFEDEFF2.toInt(), dp(10))
                bodyView.setTextColor(0xFF3A4048.toInt())
                bodyView.maxWidth = maxBubble
                bodyView.textSize = 11.5f
                bodyView.text = item.text
            }

            ChatTranscript.Role.ERROR -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFFE5484D.toInt())
                bodyView.background = bubbleShape(0xFFFDECEC.toInt(), dp(10))
                bodyView.setTextColor(0xFF8A1F22.toInt())
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.ASSISTANT -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFFFFFFF.toInt(), dp(12))
                bodyView.setTextColor(textColor)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            ChatTranscript.Role.REASONING -> {
                // 思考：灰底 + 深灰字 + 小一号，与正文（白底深字）一眼可分。
                // 不隐藏 —— 用户明确要求「微博里能看到的回复内容全部都要有」。
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = roleLabel(item)
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFF2F3F5.toInt(), dp(10))
                bodyView.setTextColor(0xFF5A6068.toInt())
                bodyView.textSize = 12f
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }

            else -> {
                row.gravity = Gravity.START
                roleView.visibility = View.VISIBLE
                roleView.text = item.role
                roleView.setTextColor(0xFF8A9099.toInt())
                bodyView.background = bubbleShape(0xFFFFFFFF.toInt(), dp(12))
                bodyView.setTextColor(textColor)
                bodyView.maxWidth = maxBubble
                bodyView.text = item.text
            }
        }
        return row
    }

    /** 消息气泡的底：纯色 + 圆角。 */
    private fun bubbleShape(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
    }

    val isShown: Boolean get() = attached
}
