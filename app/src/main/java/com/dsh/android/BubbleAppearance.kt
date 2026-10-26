package com.dsh.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 悬浮球的**外观与行为配置** —— 插件改悬浮窗的唯一接缝。
 *
 * ## 为什么要有这个类
 *
 * 目标是「让 AI 改悬浮窗，像改自己的 web 界面一样简单」。DSH 的插件体系里，
 * 任何插件都能在页面里拿到 `window.DSHAndroid`，于是：
 *
 * ```js
 * // 一个插件想在设置页里给悬浮球换皮肤，只需这一行
 * window.DSHAndroid.bubbleApply(JSON.stringify({ shape: 'squircle', size: 72, bg: '#7C4DFF' }))
 * ```
 *
 * 原生侧收到后立即应用并落盘，**重启 App 后依然生效**（下次启动从
 * SharedPreferences 读回）。
 *
 * ## 字段一览（全部可选，缺省即保持当前值）
 *
 * | 字段 | 类型 | 语义 |
 * |---|---|---|
 * ## 分层模型（这是「AI 想改什么都能改」的关键）
 *
 * 球由**三个独立的层**叠成，每层各有自己的透明度与开关 —— 而不是原来那种
 * 「一个底 + 一张图」的耦合写法：
 *
 * ```
 *   ┌─ 形状层   shape / shapePath / bg / bg2 / border / bgOpacity
 *   │    ↑ 可以是一条任意 SVG path（星形、心形、logo 轮廓），也可以是圆/方/圆角
 *   ├─ 内容层   avatar / frames / avatarOpacity / avatarScale
 *   │    ↑ 可以是内置头像、自定义图片、**轮播多帧**、**精灵图切片**，或整层去掉
 *   └─ 动效层   spin / pulse
 * ```
 *
 * 三层的透明度**相乘**关系：[opacity] 是整体，[bgOpacity] 只压背景，
 * [avatarOpacity] 只压内容。所以「背景透明但图片不透明」= `bgOpacity:0` +
 * `avatarOpacity:1`，这正是用户点名要的那一种。
 *
 * ## 字段一览（全部可选，缺省即保持当前值）
 *
 * | 字段 | 类型 | 语义 |
 * |---|---|---|
 * | `state` | string | `expanded` / `collapsed` / `hidden` / `auto` —— 直接改形态 |
 * | `shape` | string | `circle` / `square` / `rounded` / **`path`**（用 `shapePath`） |
 * | `shapePath` | string | SVG path 的 `d`，如 `M50 0 L61 35 L98 35 L68 57 L79 91 L50 70 L21 91 L32 57 L2 35 L39 35 Z` |
 * | `shapeViewBox` | string | `shapePath` 的坐标系，默认 `0 0 100 100` |
 * | `size` | int(dp) | 球的**边长**，24~320。方形画布时它是宽，也是高 |
 * | `boxWidth` | int(dp) | **画布宽**，0 = 跟随 `size`（正方形）。16~400 |
 * | `boxHeight` | int(dp) | **画布高**，0 = 跟随 `size`（正方形）。16~400 |
 * | `showDot` | bool | **已废弃**，恒不显示。状态改用头顶消息气泡表达 |
 * | `cornerRadius` | int(dp) | `rounded` 形状的圆角 |
 * | `opacity` | float | **整体**不透明度 0.05~1.0 |
 * | `bgOpacity` | float | **只压背景层** 0~1（0 = 背景全透明，露出下层应用） |
 * | `avatarOpacity` | float | **只压内容层** 0~1 |
 * | `avatarScale` | float | 内容层相对球的缩放 0.2~2.0（>1 会溢出球，由球裁剪） |
 * | `bg` | `#RRGGBB` | 球的底色（`avatar:'none'` 时才看得见） |
 * | `bg2` | `#RRGGBB` | 与 `bg` 组成竖直渐变；不填则纯色 |
 * | `border` | `#RRGGBB` | 描边色 |
 * | `borderWidth` | int(dp) | 描边宽度 0~8 |
 * | `avatar` | string | `default` / `none` / `data:image/…;base64,…` |
 * | `frames` | string[] | **轮播帧**：每项一个 data URI。传 `[]` 清空轮播 |
 * | `frameIntervalMs` | int | 每帧停留时长（ms）20~60000；被 `frameDurations` 覆盖 |
 * | `frameDurations` | int[] | 逐帧停留时长；长度不足的帧用 `frameIntervalMs` |
 * | `spriteRows` / `spriteCols` | int | **精灵图网格**：每帧是同一张大图的第 N 格 |
 * | `frameLoop` | int | 轮播循环次数，-1 = 无限 |
 * | `spin` | int | 每秒旋转角度（度），正数顺时针，0 = 不转 |
 * | `pulse` | bool | 呼吸缩放动画 |
 * | `panelWidth` | int(dp) | 展开面板宽 180~（上限 = 屏幕宽 - 16） |
 * | `panelHeight` | int(dp) | 展开面板高 160~（上限 = 屏幕高 - 96） |
 * | `panelBg` | `#RRGGBB` | 面板底色 |
 * | `panelText` | `#RRGGBB` | 面板正文色 |
 * | `panelAccent` | `#RRGGBB` | 面板强调色（发送按钮、用户气泡） |
 * | `statusAutoHideMs` | int | 状态横条**内容无变化**多久后自动消失，0 = 不自动消失 |
 * | `edgeSnap` | bool | 是否启用「拖出屏幕即贴边折叠」 |
 * | `throwEnabled` | bool | 是否启用「甩出去会飞、会弹、落地会挤压」的抛掷物理 |
 * | `imeFlingEnabled` | bool | 是否启用「键盘弹出时球从下方飞上去」（拿不到 shell 身份时自动降级为几何避让） |
 * | `shakeEnabled` | bool | 是否启用「快速晃动手机 ⇒ 球被甩飞」 |
 *
 * ## 三条设计约束
 *
 * ① **非法值退回默认，未知字段忽略。** 插件写错一个字段不该让悬浮球消失 ——
 *    那是最糟的失败形态（用户看不到任何东西，也没有报错）。
 * ② **图片走 base64 data URI**，因为页面侧拿不到文件系统路径。为防有人塞一张
 *    10MB 的图进来，[MAX_AVATAR_BASE64] 卡住长度，超了直接忽略并记日志。
 * ③ **帧集不进 SharedPreferences**（那是几十张图的体量），只存元信息；
 *    帧字节由 [BubbleFrames] 落在 `filesDir/bubble-frames/` 下。见 [save]。
 */
data class BubbleAppearance(
    val state: String = STATE_AUTO,
    val shape: String = SHAPE_CIRCLE,
    val shapePath: String = "",
    val shapeViewBox: String = BubbleShapeDrawable.DEFAULT_VIEW_BOX,
    val size: Int = 44,
    /**
     * **画布宽**，dp。0 = 自动（默认）。
     *
     * ## 语义（[boxHeight] 同）
     *
     * | 取值 | 含义 |
     * |---|---|
     * | `0`（默认） | **自动**：按当前主体素材的长宽比算，长边 = [size] |
     * | `16~400` | 就是这么多 dp |
     *
     * 只给宽不给高时，高由素材比例推出（反之亦然）；两个都给了就是那个矩形。
     *
     * ## 为什么是"画布长宽"而不是"主体尺寸"
     *
     * 用户的诉求是「背景框可以自定义分辨率，也就是长宽」，且**默认按主体素材
     * 自适应**。所以画布本身就是可长可短的矩形，**主体永远与画布同尺寸** ——
     * 不存在"主体比画布大"，也就不需要让子视图越出父边界（那正是上一版栽的地方：
     * 越界的子视图会被父容器裁掉，`clipChildren` / `clipToOutline` / `elevation`
     * 逐条试过都拦不住）。
     */
    val boxWidth: Int = 0,
    /** **画布高**，dp。0 = 自动。语义见 [boxWidth]。 */
    val boxHeight: Int = 0,
    /**
     * 是否显示状态点。
     *
     * ⚠️ **已废弃，恒为 false。**
     *
     * 原来这个点画在球右下角（[FloatingBubble] 的 `bubbleDot`）和贴边薄片
     * 正中（`edgeDot`），用来表示「AI 正在干活 / 空闲」。真机反馈的问题：
     *
     *  · 位置：贴边薄片那个点 `layout_gravity="center"`，正好压在探头人物
     *    的脸上；球上的点也随 `size * 0.25` 放大，球一大就变成压在角色身上
     *    的大白圈（用户截图反馈「桌宠状态点显示位置 bug」）。
     *  · 表达力：空心环/实心点的信息量远不如一句话。
     *
     * 现在改成**只用头顶的消息气泡**表达状态（[FloatingBubble.showStatusBubble]）：
     * AI 干完活会推一句话出来，用户不点就一直在，点一下还能展开面板。
     * 这比一个会挡脸的小圆点既清楚又不碍事。
     *
     * 字段保留只为**配置兼容**（旧配置里写 `showDot: true` 不再报错，但不再
     * 有任何视觉效果），不要再用它控制显示。
     */
    val showDot: Boolean = false,
    val cornerRadius: Int = 16,
    val opacity: Float = 1f,
    val bgOpacity: Float = 1f,
    val avatarOpacity: Float = 1f,
    val avatarScale: Float = 1f,
    val bg: String = "",
    val bg2: String = "",
    val border: String = "",
    val borderWidth: Int = 0,
    val avatar: String = AVATAR_DEFAULT,
    val frameCount: Int = 0,
    val frameIntervalMs: Int = 400,
    val spriteRows: Int = 0,
    val spriteCols: Int = 0,
    val frameLoop: Int = -1,
    /**
     * 当前动作名（桌面宠物用）。
     *
     * ⚠️ **空串 = 跟随 [actionMap] 自动切换**，非空 = 插件/脚本钦定这个动作。
     *   这正是「AI 直接让宠物做某个动作」与「让宠物跟着运行状态自己动」两种用法
     *   的分界：前者显式传 `action`，后者只配 `actionMap`。
     */
    val action: String = "",
    /**
     * 状态 → 动作名 的映射（桌面宠物的核心）。
     *
     * key 是**运行状态**（见 [STATE_IDLE] 等），value 是 [BubbleFrames] 里的动作名。
     * 只配关心的那几个即可，没配的状态退回 [BubbleFrames.DEFAULT_ACTION]。
     *
     * 例：`{"thinking":"think","working":"work","waiting":"wait"}`
     */
    val actionMap: Map<String, String> = emptyMap(),
    val spin: Int = 0,
    val pulse: Boolean = false,
    val panelWidth: Int = 260,
    val panelHeight: Int = 0,
    val panelBg: String = "",
    val panelText: String = "",
    val panelAccent: String = "",
    val statusAutoHideMs: Int = 2000,
    /**
     * 是否启用**边缘吸附**。
     *
     * 打开时球会被拉回最近的左右边缘、拖出屏幕会折叠成一条缝；
     * 关掉时球**松手在哪就待在哪**，屏幕正中间也行 —— 这是电脑版桌面宠物的行为。
     *
     * ⚠️ 默认 **false（不吸附）**，这是改过的。原来是 true，真机反馈是
     *   「它不像电脑一样可以放在手机屏幕上的任何一个位置，而是会自动回落到
     *   屏幕的两边」—— 吸附把"悬浮球想放哪就放哪"这件事直接抹掉了。
     *   要贴边省屏幕的人（把球当按钮用）显式设 true 即可。
     */
    val edgeSnap: Boolean = false,

    // ── 贴边态外观（拖出屏幕后那一条）─────────────────────────────────
    //
    // ## 为什么贴边态要能独立编辑
    //
    // 原实现是**写死的 6dp 纯色薄片**：宽度常量 `EDGE_WIDTH_DP`、背景由
    // `edgeBackground()` 现画、没有任何图片。真机反馈是「太小了，点不到」——
    // 6dp × 44dp 在 3x 屏上是 18×132 像素，手指落点几乎必然偏出去。
    //
    // 所以贴边态现在是一等公民：有自己的图片、自己的宽度、自己的可见高度，
    // 且全部走和悬浮球同一套下发通道（插件 `bubbleApply` / 模型 `bubble` 工具），
    // AI 可以直接改它。

    /**
     * 贴边态要显示的图片。
     *
     * | 取值 | 含义 |
     * |---|---|
     * | `"default"` | 内置的侧边探头形象（`bubble_edge_default`） |
     * | `"none"` | 不显示图片，退回纯色薄片（老行为） |
     * | `"custom"` | 用落盘的 `bubble-edge.bin`（见 [EDGE_IMAGE_FILE]） |
     * | `data:image/…;base64,…` | 直接内联 |
     *
     * ⚠️ 与 [avatar] 同一套陷阱：下发时是 data URI，落盘后变成文件标记。
     *   回读必须认文件标记，否则重启即丢、读-改-写会把图洗掉。
     */
    val edgeImage: String = EDGE_IMAGE_DEFAULT,

    /** 贴边态图片的**可见宽度**（dp）。默认 28：比原来的 6dp 宽 4.7 倍，能点到了。 */
    val edgeWidth: Int = DEFAULT_EDGE_WIDTH,

    /**
     * 贴边态图片的**可见高度**（dp）。0 = 自动（跟随球的画布高）。
     *
     * 图片按 `fitCenter` 铺在这个框里，所以长宽比不匹配时会有留白（透明）。
     */
    val edgeHeight: Int = 0,

    /** 贴边态图片不透明度（0–1）。与 [opacity] 相乘。 */
    val edgeOpacity: Float = 1f,

    /**
     * 贴边态在**竖直方向的位置比例**（0–1，相对可用高度）。
     *
     * `-1`（默认）= 沿用球被拖出去时的 y；`0` = 顶部，`1` = 底部。
     * 给固定值可以让探头形象每次都出现在同一高度（更像"从边里探出头"）。
     */
    val edgeAnchor: Float = -1f,

    /**
     * 是否启用**抛掷物理**：快速甩动后松手，球会飞出去、撞边反弹、落地挤压、滚停。
     *
     * 关掉时松手只有"吸附到最近边缘"与"拖出屏幕贴边"两种结局 —— 也就是加物理层
     * 之前的行为。默认**开**：那是"宠物"手感的核心，也是 dsh-pet 的默认。
     */
    val throwEnabled: Boolean = true,
    /**
     * 是否启用**键盘顶飞**：系统键盘弹出时，球从屏幕下方「嗖」地飞上去。
     *
     * ## 为什么需要这个
     *
     * 悬浮窗拿不到 IME insets，键盘弹起时球**不会被系统顶开**，停在屏幕下方的球
     * 会被键盘整块盖住 —— 用户既看不见它、也点不到它。真机反馈原话：
     * 「有时候宠物在屏幕比较偏下的地方，然后用户点开键盘弹出的时候就会被遮挡」。
     *
     * ## 触发源（为什么不是零成本）
     *
     * 唯一可靠的键盘状态来源是 `dumpsys input_method` 的 `mInputShown`，它需要
     * **uid 2000 / Shizuku** 才能读（[AppShellExecutor.needsShellUid] 已含 `dumpsys`）。
     * 单次耗时 95–150 ms，所以**不能高频轮询**：只在球停稳、且屏幕上确实有键盘
     * 风险时才查，间隔 [IME_POLL_INTERVAL_MS]。
     *
     * ⚠️ 拿不到 shell 身份时**自动降级**为纯几何避让（见 [imeFallbackSafeZone]）：
     *   球停稳后若落在下方危险区就直接弹开，不依赖任何权限。这样没有 adb 授权的
     *   用户也不会被键盘盖住，只是少了「键盘一弹就飞」的戏剧感。
     */
    val imeFlingEnabled: Boolean = true,
    /**
     * 是否启用**晃动甩飞**：快速晃动手机时，球像被手甩出去一样飞、弹、滚停。
     *
     * ## 为什么不需要权限
     *
     * `TYPE_ACCELEROMETER` 是普通传感器，装了就可用，不像相机/定位要运行时授权。
     *
     * ## 与 [throwEnabled] 的关系
     *
     * [throwEnabled] 管的是「**手甩球**松手后飞不飞」；本字段管的是「**晃手机**能不能
     * 让球飞」。两个独立：想要「只能手甩、晃手机没反应」就关掉这个。
     *
     * ⚠️ 阈值必须压在「明显是故意甩」之上 —— 走路、放桌上、打字都有读数。纯看代码
     *   定不了，见 [SHAKE_THRESHOLD] 的注释。
     */
    val shakeEnabled: Boolean = true,
    /**
     * **余额徽标**：贴在球旁边常驻显示的一个小标签。
     *
     * ## 为什么要做成一个独立的可配置对象
     *
     * 需求是「AI 自己决定显示在哪、以什么形式显示」，所以**不能**把位置/配色
     * 写死在某处常量里 —— 那样 AI 只能开关，不能设计。整个 [BalanceBadge]
     * 一次性下发、一次性落盘，AI 改任何一项都不影响别的。
     *
     * ⚠️ 与 `bg`/`border` 这类**颜色字段为空 = 跟随主题**不同，徽标里的颜色
     *   留空同样表示「跟随主题」—— 但要**逐个**判断，不做整体推断。
     */
    val balanceBadge: BalanceBadge = BalanceBadge(),
    /**
     * **具名动作池**：一次配一组动作，让宠物自己抽着播。
     *
     * key 是池名（`[a-z0-9_-]`，≤[MAX_POOL_NAME_CHARS] 字符），value 是动作名列表。
     * 三个池名有**约定语义**，原生会主动用它们：
     *
     * - `idle`   —— 待机池。宠物闲下来时从这里随机抽一个播（随机链的主体）。
     * - `click`  —— 点击回应池。**配了它就等于把「单击」改成「逗宠物」**：
     *               单击播这里的动作而**不再展开面板**，展开改走**长按**。
     *               不配时单击照旧展开面板 —— 老用户零影响。
     * - `drag`   —— 拖拽姿势池。手指按住球拖动时切到这里的动作（如"被拎起来"）。
     * - `move`   —— 走动池。宠物自己挪位置时播这里的动作（需 [moveEnabled]）。
     *
     * 其余池名（`celebrate`、`sleep`…）不会被原生自动触发，但**对 AI 有意义**：
     * 它可以 `actionMap` 指过来、或用 `bubble` 工具的 `action` 直接点名播。
     *
     * ⚠️ 池里**不存在于磁盘的动作名会被跳过**（素材没传/传失败），所以配错了
     *   不会让宠物卡住，只是那个动作永远抽不到。
     */
    val pools: Map<String, List<String>> = emptyMap(),
    /**
     * 随机链里各池的**相对权重**（整数，0 = 该池不参与随机链）。
     *
     * 只对 `idle` 这一个池有实际作用：宠物播完一个动作后，按这里的权重决定
     * 是再抽一个 idle 动作、还是回到最朴素的待机。没配 `idle` 权重时按
     * [DEFAULT_IDLE_WEIGHT] 处理。
     *
     * ⚠️ 与 dsh-pet 的 `animationWeights` 是同一个思路，但**刻意做得更小**：
     *   那边有 5 个大类（小动作/玩耍/吃什么/时节/文字）各带权重，因为它的素材
     *   有 106 个。我们把它压成"池 + 权重"两层，AI 用 `pools` 自己分类即可，
     *   不必学一套固定的分类学。
     */
    val poolWeights: Map<String, Int> = emptyMap(),
    /**
     * **运行状态气泡文案**：按 [RUN_STATES] 的顺序分档，每档可给多句备选。
     *
     * 第 0 项对 idle、第 1 项对 thinking、第 2 项 working、第 3 项 waiting、
     * 第 4 项 error。每档内**随机抽一句**显示（想固定就只写一句）。
     *
     * 例：`[["在发呆"],["正在想"],["正在干活"]]`
     *
     * ⚠️ 显示位置与既有的状态横条是**同一个**（球上方那条）—— 这是刻意的：
     *   状态文字本来就该显示在那里，另开一个位置会让球上叠两个气泡。
     *   没配这一项时照旧显示引擎报来的状态文本（零影响）。
     */
    val workStatusTexts: List<List<String>> = emptyList(),
    /**
     * 是否让宠物**自己走动**（闲下来时每隔一段时间横向挪一段）。
     *
     * 默认**关**：球会自己跑位对"当个按钮用"的用户是打扰，而那是当前绝大多数
     * 用法。要宠物感就打开它。
     */
    val moveEnabled: Boolean = false,
    /** 自己走动的间隔（秒），10~3600。实际会在 [moveIntervalSec] 的 0.5~1.5 倍间随机。 */
    val moveIntervalSec: Int = 20,
    /** 单次走动的**最短**距离（dp），8~400。 */
    val moveMinDp: Int = 24,
    /** 单次走动的**最长**距离（dp），8~400。 */
    val moveMaxDp: Int = 80,
    /**
     * **状态配图**：按 [RUN_STATES] 的顺序分档，每档可给多张图（随机抽一张）。
     *
     * 这就是电脑端"头顶冒出表情包"的那个能力 —— dsh-pet 的 `memes`。每项是一个
     * 图片路径（设备上的绝对路径，**推荐**）或 `data:image/…;base64,…`。
     *
     * ⚠️ 用**路径**而不是 base64：一张表情包 100~300 KB，base64 要涨 33%，
     *   而十几张图一起进 SharedPreferences 会把它撑爆。原生侧读路径，图片字节
     *   落在 `filesDir/bubble-status-images/`，配置里只留文件名。
     *
     * 例：`[["/sdcard/可爱.png"],["/sdcard/思考.png"],["/sdcard/忙碌.png"]]`
     */
    val statusImages: List<List<String>> = emptyList(),
    /**
     * 状态配图的显示边长（dp），32~[MAX_STATUS_IMAGE_DP]。
     *
     * ⚠️ 配图与状态横条**同时显示**：横条在上、图在横条上方。两者是叠加关系
     *   而不是替换关系（电脑端也是这样：那句话与那张图一起出现）。
     */
    val statusImageSizeDp: Int = DEFAULT_STATUS_IMAGE_DP,
    /**
     * 转向时是否翻转宠物的水平朝向。
     *
     * 宠物横向走动改变方向（往左走 ↔ 往右走）时，`turn` 池的动作播完会把
     * 内容层水平镜像一次 —— 否则宠物会一直朝着同一个方向"倒着走"。
     *
     * 默认**开**，因为素材本身是朝一个方向的，不翻就是倒着走。
     * 若素材自己带双向动画（不该翻），设 false。
     */
    val flipOnTurn: Boolean = true,
    /**
     * **逐动作的走动参数**：动作名 → 该动作自己的位移/停顿参数。
     *
     * 电脑端的 `moves.actions[].params` 就是干这个的：不同走动动画步幅不一样
     * （"螃蟹走路"横着挪得远、"原地漂浮踏步"几乎不挪），共用一个全局距离会很怪。
     * 这里每一项都可以覆盖 [moveMinDp] / [moveMaxDp]。
     *
     * 键是**动作名**（与 [pools] 里的元素同一套命名），不是池名。
     *
     * 例：`{"float_step":{"minDp":12,"maxDp":40},"crab_walk":{"minDp":60,"maxDp":160}}`
     */
    val moveParams: Map<String, MoveParams> = emptyMap(),
) {

    /**
     * 单个走动动作的距离覆盖值。
     *
     * ⚠️ 两个字段都可空：`null` = 用全局的 [moveMinDp] / [moveMaxDp]。
     *   "没配"与"配成 0"必须能区分 —— 0 dp 的位移是"原地踏步"（合法用法），
     *   而没配是要跟随全局默认，两者语义不同。
     */
    data class MoveParams(val minDp: Int? = null, val maxDp: Int? = null)

    companion object {
        private const val TAG = DshApplication.TAG

        /** 形态：跟随 App 前后台自动决定（默认）。 */
        const val STATE_AUTO = "auto"

        /** 形态：强制展开面板。 */
        const val STATE_EXPANDED = "expanded"

        /** 形态：强制收起成球。 */
        const val STATE_COLLAPSED = "collapsed"

        /** 形态：强制隐藏（连球都不显示）。 */
        const val STATE_HIDDEN = "hidden"

        // ── 运行状态（桌面宠物的动作选择依据）───────────────────────────
        //
        // ⚠️ 这几个是**运行状态**，与上面的 `state`（窗口形态）是两回事：
        //   一个是「球长什么样」，一个是「AI 在干什么」。
        //
        // ⚠️ 现在是 **7 个**：前 5 个是初版就有的，`result` / `success` 是后加的
        //   （对齐 dsh-pet 的档位）。加状态确实有代价 —— 每多一个，插件就多一处
        //   要维护的映射，映射错了用户看到的是「宠物在做错的动作」，比少一个难查。
        //   所以新增时守两条：① 只在体感上真的能区分时才加（`working` 与 `result`
        //   是"在跑"与"刚跑完"，宠物能表达这个差别）；② 必须**追加在末尾**，
        //   因为 `workStatusTexts` / `statusImages` 是按下标取值的。

        /** 空闲：没在跑，等你输入。 */
        const val RUN_IDLE = "idle"

        /** 思考：模型在生成（还没有工具在跑）。 */
        const val RUN_THINKING = "thinking"

        /** 干活：有工具/命令正在执行。 */
        const val RUN_WORKING = "working"

        /** 等待：等用户确认、等输入、等外部事件。 */
        const val RUN_WAITING = "waiting"

        /** 出错：上一轮失败了。 */
        const val RUN_ERROR = "error"

        /**
         * 整理：工具刚跑完、模型在消化结果（dsh-pet 的 `result` 档）。
         *
         * ⚠️ 与 [RUN_WORKING] 的区别是"手上有没有活在跑"：干活是工具执行**中**，
         *   整理是工具**已返回**。把它单列出来是因为这两个状态在体感上明显不同
         *   （"忙"与"刚忙完"），而宠物能表达出这个差别。
         */
        const val RUN_RESULT = "result"

        /**
         * 成功：上一轮顺利收工（dsh-pet 的 `success` 档）。
         *
         * ⚠️ 这是一个**瞬时**状态：它由 `turn/end` 带 `completed` 触发，随即回到
         *   idle。宠物在这段时间里播"庆祝"类动作 —— 这正是电脑端"干完活了会雀跃"
         *   那个手感。
         */
        const val RUN_SUCCESS = "success"

        /**
         * 全部运行状态。用于校验 [actionMap] / [statePools] 的 key。
         *
         * ⚠️ **顺序即 `workStatusTexts` 的档位下标，且新状态一律追加在末尾。**
         *   中途插入会让所有既有配置的档位整体错位（AI 配的第 3 档会突然变成
         *   另一个状态的文案），而那种错位**不会报错**，只会显示错的话。
         *   所以 [RUN_RESULT] / [RUN_SUCCESS] 挂在最后两位，代价是顺序不是
         *   语义上的自然序 —— 用文档说清，比静默错位强。
         */
        val RUN_STATES = listOf(
            RUN_IDLE, RUN_THINKING, RUN_WORKING, RUN_WAITING, RUN_ERROR,
            RUN_RESULT, RUN_SUCCESS,
        )

        // ── 具名动作池（桌面宠物）─────────────────────────────────────
        //
        // ⚠️ 池名与**运行状态名共用一套字符规则**（`[a-z0-9_-]`），因为它们会
        //   一起出现在 `pools` / `poolWeights` / `actionMap` 三处配置里，规则
        //   不一致的话 AI 要记两套。上限也一致（24），理由相同。

        /** 待机池：闲下来时从这里随机抽一个播。 */
        const val POOL_IDLE = "idle"

        /** 点击回应池：配了它单击就是逗宠物（展开改走长按）。 */
        const val POOL_CLICK = "click"

        /** 拖拽姿势池：按住拖动时切到这里的动作。 */
        const val POOL_DRAG = "drag"

        /** 走动池：自己挪位置时播的动作。 */
        const val POOL_MOVE = "move"

        /**
         * 转向池：宠物换朝向时播的动作（dsh-pet 的 `turn`）。
         *
         * ⚠️ 与其余池不同，这里的动作有个**约定**：它播完会把宠物朝向翻转
         *   （dsh-pet 的注释写着"每一项都必须是『播完会翻转朝向』的动画"）。
         *   我们不移素材，所以由 [flipOnTurn] 决定要不要在播完后翻转 `scaleX`。
         */
        const val POOL_TURN = "turn"

        /**
         * 工作状态专属池：池名 = `work-<运行状态>`，如 `work-thinking`。
         *
         * ⚠️ 这是 `actionMap` 的**升级版**：`actionMap` 是"一个状态一个固定动作"，
         *   而这里是"一个状态一个**池**，每次进这个状态随机抽一个"。电脑端正是
         *   这么做的（`workStatus` 那六档各自都能配多个）。
         */
        const val POOL_WORK_PREFIX = "work-"

        /** 状态池的前缀：`state-<运行状态>`，与 [POOL_WORK_PREFIX] 等价，二选一即可。 */
        const val POOL_STATE_PREFIX = "state-"

        /** 池名/动作名的长度上限（与 `BubbleFrames` 的动作名一致）。 */
        const val MAX_POOL_NAME_CHARS = 24

        /** 一个池最多几个动作 —— 再多也抽不过来，还占配置体积。 */
        const val MAX_POOL_ACTIONS = 64

        /** 最多几个池 —— 与 `BubbleFrames.MAX_ACTIONS` 同量级。 */
        const val MAX_POOLS = 24

        /** `workStatusTexts` 最多几档（= [RUN_STATES] 的档数）。 */
        const val MAX_STATUS_TIERS = 7

        /** `statusImages` 最多几档（通常只配前几档）。 */
        const val MAX_STATUS_IMAGES = 7

        /**
         * 每档最多几张备选配图。
         *
         * ⚠️ 与 [MAX_STATUS_TEXTS_PER_TIER] **数值相同但语义无关**，所以分开命名：
         *   配图每张几十 KB、文案每句几十字节，将来一方要调大时不该被另一方绑住。
         */
        const val MAX_STATUS_IMAGES_PER_TIER = 8

        /** 状态配图的边长上限（dp）—— 再大就把球整个盖住了。 */
        const val MAX_STATUS_IMAGE_DP = 240

        /** 状态配图的默认边长（dp）。 */
        const val DEFAULT_STATUS_IMAGE_DP = 96

        /** 每档最多几句备选文案。 */
        const val MAX_STATUS_TEXTS_PER_TIER = 8

        /** 单句文案的长度上限（状态横条只有一行，长了被截断）。 */
        const val MAX_STATUS_TEXT_CHARS = 60

        /** 没配 `idle` 权重时用的默认值 —— 让它默认就有随机小动作。 */
        const val DEFAULT_IDLE_WEIGHT = 10

        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_SQUARE = "square"
        const val SHAPE_ROUNDED = "rounded"

        /**
         * 形状由 [shapePath] 那条 SVG path 描述 —— 任意形状。
         *
         * ⚠️ 这是「AI 想改什么形状都能改」的入口：圆/方/圆角只是三个预设，
         *   而 path 能画星形、心形、盾牌、云朵、任意 logo 轮廓。
         */
        const val SHAPE_PATH = "path"

        /** 用内置的头像图片（默认）。 */
        const val AVATAR_DEFAULT = "default"

        /** 不显示头像，露出底色 —— 想用纯色/渐变球时选它。 */
        const val AVATAR_NONE = "none"

        /** 头像 base64 长度上限（约 512KB 原始数据）。超了忽略并记日志。 */
        const val MAX_AVATAR_BASE64 = 700_000

        /** 自定义头像的落盘文件名。内容变了会整体覆盖。 */
        const val AVATAR_FILE = "bubble-avatar.bin"

        // ── 贴边态（拖出屏幕后那一条）───────────────────────────────────

        /** 贴边态用内置的侧边探头形象（默认）。 */
        const val EDGE_IMAGE_DEFAULT = "default"

        /** 贴边态不显示图片，退回纯色薄片（老行为）。 */
        const val EDGE_IMAGE_NONE = "none"

        /** 自定义贴边图落盘文件名。内容变了会整体覆盖。 */
        const val EDGE_IMAGE_FILE = "bubble-edge.bin"

        /** 贴边图 base64 上限。探头形象通常比头像大一点，给到 1MB 原始数据。 */
        const val MAX_EDGE_IMAGE_BASE64 = 1_400_000

        /**
         * 贴边态**可见宽度**默认值，dp。
         *
         * ⚠️ 原实现是写死的 6dp，真机反馈「太小了，点不到」——6dp × 44dp 在 3x 屏
         *   上只有 18×132 像素。28dp 是「明显变大但仍只占一小条」的折中，
         *   在 3x 屏上是 84px 宽，手指能稳稳点到。
         */
        const val DEFAULT_EDGE_WIDTH = 28

        /**
         * 球边长（`size`）的上限，dp。
         *
         * 曾是 160。桌宠素材（dsh-app-pet 的 640×360 webm）里人物只占画面宽度的
         * ~33%，要让它看起来有正常大小就得把画布开得很大 —— 160 不够用。
         * 320 在 3x 屏上是 960px，已经接近半屏宽，再大就会挡操作。
         *
         * ⚠️ 画布（`boxWidth`/`boxHeight`）的上限本来就是 400，比这里宽，
         *   所以不需要同步改。
         */
        const val MAX_SIZE_DP = 320

        /** 贴边态宽度下限 / 上限，dp。下限保证"能点到"，上限防止挡住半个屏幕。 */
        const val MIN_EDGE_WIDTH = 6
        const val MAX_EDGE_WIDTH = 120

        /** 贴边态高度上限，dp。0 表示跟随球高，这里只约束显式给值的情况。 */
        const val MAX_EDGE_HEIGHT = 400

        /**
         * SVG path 文本的长度上限。
         *
         * 一条 100dp 的球上画不出那么多细节，而每次解析都在主线程 —— 4000 字符
         * 已经足够描述很复杂的轮廓（对比：五角星的 path 约 100 字符）。
         */
        const val MAX_SHAPE_PATH_CHARS = 4000

        /** 配置落盘的 SharedPreferences 名。与位置共用一个文件，减少一次 I/O。 */
        const val PREFS = "dsh_bubble"

        /** 画布长宽的下限（dp）。再小就看不出主体了。 */
        const val MIN_BOX_DP = 16

        /** 画布长宽的上限（dp）。防插件传个 `99999` 把窗口撑爆。 */
        const val MAX_BOX_DP = 400

        /**
         * 按**当前主体素材**的长宽比算画布尺寸（px）。
         *
         * 这是 `boxWidth`/`boxHeight` 为 0（默认）时的取值来源 ——「默认的时候，
         * 它就是根据主体的那个素材，长宽来自适应」。
         *
         * 素材来源按**内容层的优先级**逐条试（与 [FloatingBubble.applyContentLayer]
         * 的顺序一致，否则会出现"显示的素材和量出来的素材不是同一个"）：
         *
         * ① 视频层（`bubble-videos/<动作>.webm`）—— 见下 ⚠️
         * ② 动作帧集 / 精灵图 → [BubbleFrames.frameSize]
         * ③ 自定义头像 → 落盘文件的解码边界
         * ④ 内置头像 `bubble_avatar` 的固有尺寸
         *
         * 长边取 `size`，短边按素材比例缩放，**但不超过 `size`** —— 这样
         * 正方形素材得到的就是原来的 `size × size`（老行为逐字不变）。
         *
         * ⚠️ **视频层不参与量取**：webm 的分辨率要 demux 才拿得到，`BubbleVideos`
         *   没有这个能力（也是刻意不加，那要把整个容器解析搬进来）。所以有视频素材
         *   时按**正方形**算 —— 用户若需要别的比例，显式给 `boxWidth`/`boxHeight`。
         */
        fun autoBox(context: Context, appearance: BubbleAppearance, action: String): Pair<Int, Int> {
            val density = context.resources.displayMetrics.density
            val long = (appearance.size * density).roundToInt().coerceAtLeast(1)
            val ratio = subjectRatio(context, appearance, action)
            // 长边恒等于 size，短边按比例 —— 于是"正方形素材 = 老行为"自动成立。
            val w = if (ratio != null && ratio < 1f) (long * ratio).roundToInt() else long
            val h = if (ratio != null && ratio > 1f) (long / ratio).roundToInt() else long
            return w.coerceAtLeast(1) to h.coerceAtLeast(1)
        }

        /** 主体素材的 `宽/高`；量不到返回 null（按正方形处理）。 */
        private fun subjectRatio(context: Context, appearance: BubbleAppearance, action: String): Float? {
            val (w, h) = subjectSize(context, appearance, action) ?: return null
            if (w <= 0 || h <= 0) return null
            return w.toFloat() / h.toFloat()
        }

        /** 主体素材的像素长宽；量不到返回 null。 */
        private fun subjectSize(context: Context, appearance: BubbleAppearance, action: String): Pair<Int, Int>? {
            // ② 帧集 / 精灵图：磁盘上有这个动作的帧就用它。
            if (BubbleFrames.available(context, action)) {
                BubbleFrames.frameSize(context, action)?.let { return it }
            }
            // ③ 自定义头像：只解边界，不解码整张图。
            if (appearance.usesCustomAvatar) {
                val f = File(context.filesDir, AVATAR_FILE)
                if (f.isFile && f.length() > 0L) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth to opts.outHeight
                }
            }
            // ④ 内置头像：按**当前密度的那一份**量固有尺寸。
            if (appearance.avatar != AVATAR_NONE) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(context.resources, R.drawable.bubble_avatar, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth to opts.outHeight
            }
            return null
        }

        /**
         * 画布的实际像素长宽（已解析「0 = 自动」）。
         *
         * ⚠️ **所有几何计算都必须走这里**，不能各处自己 `dp(appearance.size)` ——
         *   上一版就是因为有 8 处各算各的尺寸，改一个字段要追 8 个调用点。
         */
        fun boxPx(context: Context, appearance: BubbleAppearance, action: String): Pair<Int, Int> {
            val dm = context.resources.displayMetrics
            val auto = autoBox(context, appearance, action)
            val rawW = if (appearance.boxWidth > 0) appearance.boxWidth else 0
            val rawH = if (appearance.boxHeight > 0) appearance.boxHeight else 0
            // 两个都没配 → 完全走自动。
            if (rawW == 0 && rawH == 0) return auto

            // ⚠️ **`boxWidth`/`boxHeight` 是「相对 `size` 的比例基准」，不是绝对值。**
            //
            //   原实现直接用它们当最终 dp，于是只要两个都配了，`size` 就被彻底
            //   架空 —— 真机反馈「调节直径没反应」。桌宠预设正是这种情形
            //   （`size:160, boxWidth:160, boxHeight:142`）。
            //
            //   现在的语义：
            //     `size` = 画布的**长边**，`boxWidth`/`boxHeight` 只表达**长宽比**。
            //   于是拖直径滑块能整体缩放，而画布形状保持不变。
            //
            //   举例（长宽比 160:142）：
            //     size=160 → 160 x 142
            //     size=320 → 320 x 284
            //   这正是用户对「直径」的直觉。
            //
            //   ⚠️ 素材本身是正方形（没配 boxWidth/boxHeight）时仍走 [autoBox]，
            //     行为与以前完全一致，不影响存量配置。
            val longDp = appearance.size
            val boxLong = maxOf(rawW, rawH)
            if (boxLong <= 0) return auto

            // 按「配的那个当长边」求比例，缺的那一轴由比例推出。
            val wDp: Int
            val hDp: Int
            if (rawW > 0 && rawH > 0) {
                // 两个都给：等比缩放到长边 = size。
                val k = longDp.toFloat() / boxLong
                wDp = (rawW * k).roundToInt()
                hDp = (rawH * k).roundToInt()
            } else if (rawW > 0) {
                // 只给宽：宽就是 size，高按素材比例。
                val ratio = if (auto.first > 0) auto.second.toFloat() / auto.first else 1f
                wDp = longDp
                hDp = (wDp * ratio).roundToInt()
            } else {
                // 只给高：高就是 size，宽按素材比例。
                val ratio = if (auto.second > 0) auto.first.toFloat() / auto.second else 1f
                hDp = longDp
                wDp = (hDp * ratio).roundToInt()
            }
            return (wDp * dm.density).roundToInt().coerceAtLeast(1) to
                (hDp * dm.density).roundToInt().coerceAtLeast(1)
        }

        /** 整个配置对象的 key。 */
        const val KEY_APPEARANCE = "appearance"

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

        /** 精灵图网格（两者都 > 0 才有效）。 */
        fun spriteOf(a: BubbleAppearance): Pair<Int, Int>? =
            if (a.spriteRows > 0 && a.spriteCols > 0) a.spriteRows to a.spriteCols else null

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
         * 按 [RUN_STATES] 的顺序给出动作名，没配的用 [fallback]。
         *
         * 这是「哪个动作该播」的唯一判定入口 —— 原生自动切换与插件主动点名
         * 都从这里取，免得两处各写一份 if 链。
         *
         * ⚠️ **缺档时先回退到 `idle` 档，最后才用 [fallback]。** 这条回退链是必须的：
         *   宠物素材通常只配一两个动作（配 `idle` 是绝对常态），而 [fallback] 指向的是
         *   **内置静态头像**。缺档直接回退 fallback 的后果是 —— AI 一干活（状态切到
         *   `thinking`）宠物就"啪"地变成一张不动的头像，看起来像坏掉了。
         *   真机踩过：用户只配了 `idle`/`working`，一发消息球就变回静态默认图。
         */
        fun actionFor(a: BubbleAppearance, runState: String, fallback: String): String {
            val mapped = a.actionMap[runState]
            if (!mapped.isNullOrEmpty()) return mapped
            // 缺档：优先用 idle 的动作顶上（"宠物还在，只是没配这个状态"），
            // 而不是换成一个静态头像（"宠物不见了"）。
            if (runState != RUN_IDLE) {
                val idle = a.actionMap[RUN_IDLE]
                if (!idle.isNullOrEmpty()) return idle
            }
            return fallback
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
         * 取某个运行状态该显示的文案（没配返回 null）。
         *
         * ⚠️ 随机抽而不是取第一句：同一个状态反复出现时总显示同一句会像卡住。
         *   调用方**每次状态变化才调一次**（不是每帧），所以随机是安全的。
         */
        fun statusTextFor(a: BubbleAppearance, runState: String): String? {
            val idx = RUN_STATES.indexOf(runState)
            if (idx < 0 || idx >= a.workStatusTexts.size) return null
            val tier = a.workStatusTexts[idx]
            return if (tier.isEmpty()) null else tier.random()
        }

        /**
         * 状态专属池的**抽取入口**（抽一次，返回动作名或 null）。
         *
         * 与 [actionForState] 的区别：那个每次调用都抽（会变），这个专门给
         * "状态变化时抽一次并缓存"的调用方用。两处分开命名是为了让调用点一眼
         * 看出自己用的是哪一种 —— 混用就是"宠物动作乱跳"那个 bug。
         */
        fun statePoolPick(context: Context, a: BubbleAppearance, runState: String): String? {
            val pool = statePoolName(a, runState) ?: return null
            return pickFromPool(context, a, pool)
        }

        /**
         * 取某个运行状态该显示的**配图**（没配返回 null）。
         *
         * ⚠️ 与 [statusTextFor] 同理：随机抽一张，且调用方**每次状态变化才调一次**。
         *   "每次进这个状态换张图"正是电脑端的观感；每次渲染都抽会变成幻灯片乱闪。
         */
        fun statusImageFor(a: BubbleAppearance, runState: String): String? {
            val idx = RUN_STATES.indexOf(runState)
            if (idx < 0 || idx >= a.statusImages.size) return null
            val tier = a.statusImages[idx]
            return if (tier.isEmpty()) null else tier.random()
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
         * 某动作该用的走动距离区间（dp）。
         *
         * ⚠️ 覆盖值**允许倒置**（min > max）时自动交换：AI 写反了不该让走动
         *   直接失效（`random()` 在倒置区间上会抛异常或返回奇怪值）。
         */
        fun moveRangeFor(a: BubbleAppearance, action: String): Pair<Int, Int> {
            val p = a.moveParams[action]
            var lo = p?.minDp ?: a.moveMinDp
            var hi = p?.maxDp ?: a.moveMaxDp
            if (lo > hi) lo = hi.also { hi = lo }
            return lo to hi
        }

        /**
         * 某个运行状态该抽哪个池（没配返回 null）。
         *
         * 支持两个前缀，因为 AI 可能用任一种写法来描述"这个状态专用池"：
         * `work-thinking` 与 `state-thinking` 等价。两个都认，省得它猜。
         */
        fun statePoolName(a: BubbleAppearance, runState: String): String? {
            for (prefix in listOf(POOL_WORK_PREFIX, POOL_STATE_PREFIX)) {
                val name = prefix + runState
                if (!a.pools[name].isNullOrEmpty()) return name
            }
            return null
        }

        /**
         * 从池里随机抽一个**磁盘上真的存在**的动作名（没得抽返回 null）。
         *
         * ⚠️ 过滤掉不存在的动作是必须的：AI 可能先配池再传素材，中间任何一步
         *   失败都会留下"池里有名、磁盘上没素材"的状态，不过滤就会抽到一个
         *   播不出来的动作（症状是宠物突然不动了）。
         *
         * @param exclude 尽量避开的名字（通常是当前正在播的），池里还有别的就避开。
         */
        fun pickFromPool(
            context: Context,
            a: BubbleAppearance,
            pool: String,
            exclude: String = "",
        ): String? {
            val list = a.pools[pool] ?: return null
            val playable = list.filter {
                BubbleVideos.available(context, it) || BubbleFrames.available(context, it)
            }
            if (playable.isEmpty()) return null
            val rest = playable.filter { it != exclude }
            // 池里只有一个动作、且正是当前这个 —— 那就还抽它（重复播总好过不动）。
            return (if (rest.isNotEmpty()) rest else playable).random()
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

        /**
         * 解码 data URI 形式的贴边图并落盘。
         *
         * 与 [writeAvatar] 同构：先解码确认是有效位图再写，避免把垃圾字节写进去、
         * 下次启动才在 BitmapFactory 那里失败。
         */
        fun writeEdgeImage(context: Context, image: String): File? {
            if (!image.startsWith("data:image/")) return null
            val comma = image.indexOf(',')
            if (comma < 0) return null
            val bytes = try {
                Base64.decode(image.substring(comma + 1), Base64.DEFAULT)
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球贴边图 base64 解码失败: ${t.message}")
                return null
            }
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                Log.w(TAG, "悬浮球贴边图不是有效图片，已忽略")
                return null
            }
            return try {
                val f = File(context.filesDir, EDGE_IMAGE_FILE)
                f.writeBytes(bytes)
                Log.i(TAG, "已保存悬浮球贴边图（${bytes.size} 字节）")
                f
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球贴边图落盘失败: ${t.message}")
                null
            }
        }

        /**
         * 读回已落盘的自定义贴边图。
         *
         * 贴边态是**竖长条**（默认 28dp 宽），解码目标取宽度即可 —— 按长边采样会
         * 因为高度大而过度降采样，探头形象反而糊掉。
         */
        fun readEdgeImage(context: Context, targetWidthPx: Int): Bitmap? {
            val f = File(context.filesDir, EDGE_IMAGE_FILE)
            if (!f.isFile || f.length() == 0L) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                // ⚠️ 只按**宽度**判断是否够采样：竖长条的 outHeight 天然很大，
                //   两个维度都要大于阈值才采样的话，这里会几乎永远不降采样。
                while (bounds.outWidth / (sample * 2) >= targetWidthPx * 2) sample *= 2
                BitmapFactory.decodeFile(
                    f.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "读取悬浮球贴边图失败: ${t.message}")
                null
            }
        }

        /**
         * 解码 data URI 形式的头像并落盘。
         *
         * ⚠️ **落盘而不是只留内存**：插件换的头像要能在 App 重启后仍然生效，
         *   否则用户会觉得「AI 换的皮肤过一会自己变回去了」。
         *
         * @return 落盘后的文件；非 data URI 或解码失败时返回 null。
         */
        fun writeAvatar(context: Context, avatar: String): File? {
            if (!avatar.startsWith("data:image/")) return null
            val comma = avatar.indexOf(',')
            if (comma < 0) return null
            val b64 = avatar.substring(comma + 1)
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球自定义头像 base64 解码失败: ${t.message}")
                return null
            }
            // ⚠️ 先解码一次确认是有效位图再落盘：把一堆垃圾字节写进去、下次启动
            //   才在 BitmapFactory 那里失败，会让问题从"插件写错了"变成"重启后
            //   头像莫名其妙没了"，难查得多。
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                Log.w(TAG, "悬浮球自定义头像不是有效图片，已忽略")
                return null
            }
            return try {
                val f = File(context.filesDir, AVATAR_FILE)
                f.writeBytes(bytes)
                Log.i(TAG, "已保存悬浮球自定义头像（${bytes.size} 字节）")
                f
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮球自定义头像落盘失败: ${t.message}")
                null
            }
        }

        /**
         * 读回已落盘的自定义头像。
         *
         * ⚠️ 用 `inSampleSize` 采样：插件可能塞一张 4000×4000 的图，而球的显示
         *   尺寸最大 160dp（约 480px @xxhdpi）。不采样会白占十几 MB 堆。
         */
        fun readAvatar(context: Context, targetPx: Int): Bitmap? {
            val f = File(context.filesDir, AVATAR_FILE)
            if (!f.isFile || f.length() == 0L) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(f.absolutePath, opts)
            } catch (t: Throwable) {
                Log.w(TAG, "读取悬浮球自定义头像失败: ${t.message}")
                null
            }
        }

        /** 从 SharedPreferences 读回配置。缺失或损坏时返回默认值。 */
        fun load(context: Context): BubbleAppearance {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APPEARANCE, null) ?: return BubbleAppearance()
            return merge(raw, BubbleAppearance(), context)
        }

        /**
         * 落盘。**每次 apply 都写**：插件改完就该在重启后仍然生效。
         *
         * ⚠️ **这里漏一个字段 = 那个字段只在本次运行有效，重启即回默认。**
         *   表现是"AI 改的时候看得见，重开 App 就没了" —— 加字段时务必同步这里，
         *   [load] 走 [merge]，`merge` 认的键这里都得写出来。
         */
        fun save(context: Context, appearance: BubbleAppearance) {
            val json = JSONObject().apply {
                put("state", appearance.state)
                put("shape", appearance.shape)
                put("shapePath", appearance.shapePath)
                put("shapeViewBox", appearance.shapeViewBox)
                put("size", appearance.size)
                // ⚠️ `0`（自动）也要写：`load()` 走 `merge`，缺键时保留的是
                //   `BubbleAppearance()` 的默认值 —— 默认恰好也是 0，所以这里
                //   漏写不会立刻出错，但**改用非 0 默认值的那一刻就会静默回退**。
                put("boxWidth", appearance.boxWidth)
                put("boxHeight", appearance.boxHeight)
                put("showDot", appearance.showDot)
                put("cornerRadius", appearance.cornerRadius)
                put("opacity", appearance.opacity.toDouble())
                put("bgOpacity", appearance.bgOpacity.toDouble())
                put("avatarOpacity", appearance.avatarOpacity.toDouble())
                put("avatarScale", appearance.avatarScale.toDouble())
                put("bg", appearance.bg)
                put("bg2", appearance.bg2)
                put("border", appearance.border)
                put("borderWidth", appearance.borderWidth)
                // ⚠️ 头像**不落进配置 JSON**：base64 可能几百 KB，写进
                //   SharedPreferences 会让每次读写都带上它。图片单独落盘（见
                //   writeAvatar），配置里只留一个标记表示"用自定义头像"。
                put("avatar", if (appearance.avatar.startsWith("data:image/")) AVATAR_FILE else appearance.avatar)
                // ⚠️ 轮播帧同理，甚至更要紧：帧字节在 `filesDir/bubble-frames/`
                //   （见 [BubbleFrames]），这里只存"有几帧、怎么播"。
                //   `frameCount` 必须落盘，否则重启后 `load()` 以为没有帧，
                //   磁盘上那堆文件再没人引用。
                put("frameCount", appearance.frameCount)
                put("frameIntervalMs", appearance.frameIntervalMs)
                put("frameLoop", appearance.frameLoop)
                put("spriteRows", appearance.spriteRows)
                put("spriteCols", appearance.spriteCols)
                // ⚠️ 动作名与状态映射必须落盘，否则重启后宠物就只会播默认动作 ——
                //   而磁盘上那几套动作帧还在，表现为"重开 App 宠物就不动了"。
                //   动作名空串也要写：它是"自动跟随"这个状态本身。
                put("action", appearance.action)
                put(
                    "actionMap",
                    JSONObject().apply {
                        for ((k, v) in appearance.actionMap) put(k, v)
                    },
                )
                put("spin", appearance.spin)
                put("pulse", appearance.pulse)
                put("panelWidth", appearance.panelWidth)
                put("panelHeight", appearance.panelHeight)
                put("panelBg", appearance.panelBg)
                put("panelText", appearance.panelText)
                put("panelAccent", appearance.panelAccent)
                put("statusAutoHideMs", appearance.statusAutoHideMs)
                put("edgeSnap", appearance.edgeSnap)
                // ⚠️ 与 [avatar] 同理：data URI 不能直接落盘（几十万字符的 JSON），
                //   落盘时换成文件标记，字节另存 bubble-edge.bin。
                put(
                    "edgeImage",
                    if (appearance.edgeImage.startsWith("data:image/")) EDGE_IMAGE_FILE
                    else appearance.edgeImage,
                )
                put("edgeWidth", appearance.edgeWidth)
                put("edgeHeight", appearance.edgeHeight)
                put("edgeOpacity", appearance.edgeOpacity.toDouble())
                put("edgeAnchor", appearance.edgeAnchor.toDouble())
                put("throwEnabled", appearance.throwEnabled)
                put("imeFlingEnabled", appearance.imeFlingEnabled)
                put("shakeEnabled", appearance.shakeEnabled)
                // 余额徽标整包落盘。**逐字段写而不是塞一个嵌套 JSON 字符串**：
                // 读回时 `BalanceBadge.merge` 要按字段判"缺省即保持"，嵌套字符串
                // 会让"某个字段没写"与"某个字段是空串"彻底分不开。
                put(
                    "balanceBadge",
                    JSONObject().apply {
                        val bb = appearance.balanceBadge
                        put("enabled", bb.enabled)
                        put("anchor", bb.anchor)
                        put("gapDp", bb.gapDp)
                        put("bg", bb.bg)
                        put("fg", bb.fg)
                        put("border", bb.border)
                        put("borderWidthDp", bb.borderWidthDp)
                        put("radiusDp", bb.radiusDp)
                        put("fontSizeSp", bb.fontSizeSp)
                        put("paddingHDp", bb.paddingHDp)
                        put("paddingVDp", bb.paddingVDp)
                        put("maxWidthDp", bb.maxWidthDp)
                        put("template", bb.template)
                        put("showCostFlash", bb.showCostFlash)
                        put("hideWhenIdle", bb.hideWhenIdle)
                        put("refreshOnTurnEnd", bb.refreshOnTurnEnd)
                        put("refreshEverySec", bb.refreshEverySec)
                        put("refreshOnTap", bb.refreshOnTap)
                        put("tapRefreshMinIntervalSec", bb.tapRefreshMinIntervalSec)
                    },
                )
                // ⚠️ 池、权重、状态文案都必须落盘，否则重启后宠物只剩默认动作 ——
                //   而磁盘上那几十段素材还在，表现为"重开 App 宠物就不动了"。
                //   空对象/空数组也要写：它们正是"清空"这个状态本身。
                put(
                    "pools",
                    JSONObject().apply {
                        for ((k, v) in appearance.pools) put(k, org.json.JSONArray(v))
                    },
                )
                put(
                    "poolWeights",
                    JSONObject().apply {
                        for ((k, v) in appearance.poolWeights) put(k, v)
                    },
                )
                put(
                    "workStatusTexts",
                    org.json.JSONArray().apply {
                        for (tier in appearance.workStatusTexts) put(org.json.JSONArray(tier))
                    },
                )
                put("moveEnabled", appearance.moveEnabled)
                put("moveIntervalSec", appearance.moveIntervalSec)
                put("moveMinDp", appearance.moveMinDp)
                put("moveMaxDp", appearance.moveMaxDp)
                // ⚠️ 状态配图存的是**落盘后的文件名**（见 [BubbleStatusImages]），
                //   不是原始路径 —— 插件可能传一个 /sdcard 上的临时路径，
                //   那个文件随时会被清掉，而配图必须跨重启活着。
                put(
                    "statusImages",
                    org.json.JSONArray().apply {
                        for (tier in appearance.statusImages) put(org.json.JSONArray(tier))
                    },
                )
                put("statusImageSizeDp", appearance.statusImageSizeDp)
                put("flipOnTurn", appearance.flipOnTurn)
                put(
                    "moveParams",
                    JSONObject().apply {
                        for ((action, p) in appearance.moveParams) {
                            put(
                                action,
                                JSONObject().apply {
                                    // ⚠️ null 不写键（而不是写 0）：0 是"原地踏步"
                                    //   这个合法值，写 0 会把"跟随全局"改掉。
                                    p.minDp?.let { put("minDp", it) }
                                    p.maxDp?.let { put("maxDp", it) }
                                },
                            )
                        }
                    },
                )
            }.toString()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_APPEARANCE, json).apply()
        }
    }

    /** 是否是「用自定义头像」——配置里存的是文件名标记，不是 data URI。 */
    val usesCustomAvatar: Boolean get() = avatar == AVATAR_FILE

    /** 面板宽度的有效值（按屏幕宽夹一次）。 */
    fun effectivePanelWidth(screenW: Int, density: Float): Int {
        val px = (panelWidth * density).toInt()
        return px.coerceIn((180 * density).toInt(), (screenW - (16 * density).toInt()).coerceAtLeast((180 * density).toInt()))
    }

    /** 面板高度；0 表示按内容自适应。 */
    fun effectivePanelHeight(screenH: Int, density: Float): Int {
        if (panelHeight <= 0) return 0
        val px = (panelHeight * density).toInt()
        return px.coerceIn((160 * density).toInt(), (screenH - (96 * density).toInt()).coerceAtLeast((160 * density).toInt()))
    }
}
