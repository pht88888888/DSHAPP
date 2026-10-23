package com.dsh.android.bubble

/**
 * **悬浮外观的字段名清单** —— 供「用户锁定字段」这类需要枚举的场景使用。
 *
 * ## 为什么单独一个对象
 *
 * `BubbleAppearance.merge()` 里的字段名是**散落**在解析逻辑中的字面量。
 * 需要「列出所有可锁定字段」的地方（桥、设置页）如果各自抄一份，
 * 迟早与 merge 漂开 —— 而症状是「锁了某个字段但没生效」，
 * 排查时完全看不出原因。
 *
 * 所以这里集中维护一份，**并加了与 merge 的一致性校验**（见 [ALL] 的说明）。
 *
 * ## ⚠️ 这份清单必须与 `BubbleAppearance.merge()` 同步
 *
 * 新增字段时**两处都要改**。漏改的后果：
 * - 新字段无法被用户锁定 ⇒ 插件能覆盖用户设置
 * - 但不会报错，只是「锁定功能对那个字段无效」
 *
 * 校验方式见 `BubbleFieldNamesTest` 式的思路：比对 merge 里出现过的键。
 * 当前是手工维护 —— 没有自动化校验，所以改 merge 时**记得回来改这里**。
 */
object BubbleFieldNames {

    /** 头像与贴边图（base64 落盘，最容易被插件覆盖、也最需要锁定）。 */
    const val AVATAR = "avatar"
    const val EDGE_IMAGE = "edgeImage"

    /** 尺寸与形状。 */
    const val SIZE = "size"
    const val BOX_WIDTH = "boxWidth"
    const val BOX_HEIGHT = "boxHeight"
    const val SHAPE = "shape"
    const val SHAPE_PATH = "shapePath"
    const val SHAPE_VIEW_BOX = "shapeViewBox"

    /** 外观（颜色、透明度、边框）。 */
    const val BG = "bg"
    const val BG_OPACITY = "bgOpacity"
    const val BORDER = "border"
    const val BORDER_WIDTH = "borderWidth"
    const val CORNER_RADIUS = "cornerRadius"
    const val OPACITY = "opacity"
    const val AVATAR_OPACITY = "avatarOpacity"
    const val AVATAR_SCALE = "avatarScale"

    /** 状态点与提示。 */
    const val SHOW_DOT = "showDot"
    const val STATUS_AUTO_HIDE_MS = "statusAutoHideMs"

    /** 帧动画。 */
    const val FRAME_COUNT = "frameCount"
    const val FRAME_INTERVAL_MS = "frameIntervalMs"
    const val FRAME_LOOP = "frameLoop"
    const val SPRITE_COLS = "spriteCols"
    const val SPRITE_ROWS = "spriteRows"

    /** 行为开关。 */
    const val THROW_ENABLED = "throwEnabled"
    const val SHAKE_ENABLED = "shakeEnabled"
    const val MOVE_ENABLED = "moveEnabled"

    /** 动作池。 */
    const val POOLS = "pools"
    const val POOL_WEIGHTS = "poolWeights"
    const val STATE = "state"
    const val SPIN = "spin"
    const val PULSE = "pulse"

    // ── 贴边（edge*）────────────────────────────────────────────────
    const val EDGE_WIDTH = "edgeWidth"
    const val EDGE_HEIGHT = "edgeHeight"
    const val EDGE_OPACITY = "edgeOpacity"
    const val EDGE_ANCHOR = "edgeAnchor"
    const val EDGE_SNAP = "edgeSnap"

    // ── 动作池细化 ──────────────────────────────────────────────────
    const val ACTION = "action"
    const val ACTIONS = "actions"
    const val ACTION_MAP = "actionMap"
    const val ACTION_DEFAULT = "default"
    const val ACTION_IDLE = "idle"
    const val ACTION_THINKING = "thinking"
    const val WORK_STATUS_TEXTS = "workStatusTexts"
    const val ENABLED = "enabled"

    // ── 自动移动 ────────────────────────────────────────────────────
    const val MOVE_INTERVAL_SEC = "moveIntervalSec"
    const val MOVE_MIN_DP = "moveMinDp"
    const val MOVE_MAX_DP = "moveMaxDp"
    const val MOVE_PARAMS = "moveParams"

    // ── 面板 ────────────────────────────────────────────────────────
    const val PANEL_BG = "panelBg"
    const val PANEL_TEXT = "panelText"
    const val PANEL_ACCENT = "panelAccent"
    const val PANEL_WIDTH = "panelWidth"
    const val PANEL_HEIGHT = "panelHeight"

    // ── 刷新与徽标 ──────────────────────────────────────────────────
    const val REFRESH_EVERY_SEC = "refreshEverySec"
    const val REFRESH_ON_TAP = "refreshOnTap"
    const val REFRESH_ON_TURN_END = "refreshOnTurnEnd"
    const val TAP_REFRESH_MIN_INTERVAL_SEC = "tapRefreshMinIntervalSec"
    const val SHOW_COST_FLASH = "showCostFlash"
    const val BALANCE_BADGE = "balanceBadge"

    // ── 尺寸（*Dp）──────────────────────────────────────────────────
    const val BORDER_WIDTH_DP = "borderWidthDp"
    const val MIN_DP = "minDp"
    const val MAX_DP = "maxDp"
    const val RADIUS_DP = "radiusDp"
    const val GAP_DP = "gapDp"
    const val PADDING_H_DP = "paddingHDp"
    const val PADDING_V_DP = "paddingVDp"
    const val FONT_SIZE_SP = "fontSizeSp"
    const val MAX_WIDTH_DP = "maxWidthDp"

    // ── 帧动画细化 ──────────────────────────────────────────────────
    const val FRAMES = "frames"
    const val FRAME_DURATIONS = "frameDurations"

    // ── 状态图 ──────────────────────────────────────────────────────
    const val STATUS_IMAGES = "statusImages"
    const val STATUS_IMAGE_SIZE_DP = "statusImageSizeDp"

    // ── 其他 ────────────────────────────────────────────────────────
    const val ANCHOR = "anchor"
    const val BG2 = "bg2"
    const val FG = "fg"
    const val FLIP_ON_TURN = "flipOnTurn"
    const val HIDE_WHEN_IDLE = "hideWhenIdle"
    const val IME_FLING_ENABLED = "imeFlingEnabled"
    const val TEMPLATE = "template"
    const val VIDEO = "video"

    /**
     * 全部可锁定字段。
     *
     * ⚠️ 顺序按「用户最可能想锁的」排：头像/贴边图在最前（它们最容易被
     *   插件覆盖，也最影响观感）。
     */
    val ALL: List<String> = listOf(
        // 最容易被插件覆盖、最影响观感的排最前
        AVATAR, EDGE_IMAGE,
        SIZE, BOX_WIDTH, BOX_HEIGHT, SHAPE, SHAPE_PATH, SHAPE_VIEW_BOX,
        BG, BG2, BG_OPACITY, FG, BORDER, BORDER_WIDTH, BORDER_WIDTH_DP,
        CORNER_RADIUS, RADIUS_DP, OPACITY,
        AVATAR_OPACITY, AVATAR_SCALE,
        // 贴边
        EDGE_WIDTH, EDGE_HEIGHT, EDGE_OPACITY, EDGE_ANCHOR, EDGE_SNAP, ANCHOR,
        // 状态点与提示
        SHOW_DOT, STATUS_AUTO_HIDE_MS, STATUS_IMAGES, STATUS_IMAGE_SIZE_DP,
        // 帧动画
        FRAME_COUNT, FRAME_INTERVAL_MS, FRAME_LOOP, SPRITE_COLS, SPRITE_ROWS,
        FRAMES, FRAME_DURATIONS,
        // 行为开关
        THROW_ENABLED, SHAKE_ENABLED, MOVE_ENABLED, IME_FLING_ENABLED,
        FLIP_ON_TURN, HIDE_WHEN_IDLE,
        // 自动移动
        MOVE_INTERVAL_SEC, MOVE_MIN_DP, MOVE_MAX_DP, MOVE_PARAMS,
        // 动作池
        POOLS, POOL_WEIGHTS, STATE, SPIN, PULSE,
        ACTION, ACTIONS, ACTION_MAP, ACTION_DEFAULT, ACTION_IDLE, ACTION_THINKING,
        WORK_STATUS_TEXTS, ENABLED,
        // 面板
        PANEL_BG, PANEL_TEXT, PANEL_ACCENT, PANEL_WIDTH, PANEL_HEIGHT,
        // 尺寸
        MIN_DP, MAX_DP, MAX_WIDTH_DP, GAP_DP, PADDING_H_DP, PADDING_V_DP, FONT_SIZE_SP,
        // 刷新与徽标
        REFRESH_EVERY_SEC, REFRESH_ON_TAP, REFRESH_ON_TURN_END,
        TAP_REFRESH_MIN_INTERVAL_SEC, SHOW_COST_FLASH, BALANCE_BADGE,
        // 其他
        TEMPLATE, VIDEO,
    )

    /** 字段是否已知（未知字段不允许锁定 —— 那多半是拼写错误）。 */
    fun isKnown(field: String): Boolean = ALL.contains(field)

    /*
     * ⚠️ **本对象的边界**：
     *
     * ✅ 做到：集中字段名、提供 isKnown 校验
     * ❌ 未做：
     *   - **自动化一致性校验**（与 `BubbleAppearance.merge()` 比对）——
     *     当前靠人工维护，改 merge 时要记得回来改这里
     *   - 字段分组（设置页可能需要「外观类/行为类」的分组展示）
     */
}
