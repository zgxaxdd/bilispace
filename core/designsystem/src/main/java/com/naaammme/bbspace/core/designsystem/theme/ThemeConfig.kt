package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class ThemeConfig(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedColor: Color = Color(0xFFFB7299),
    val useDynamicColor: Boolean = true,
    val paletteStyle: PaletteStyle = PaletteStyle.TONAL_SPOT,
    val swapBaseColors: Boolean = false,
    val fontScale: Float = 1.0f,
    val uiScale: Float = 1.0f,
    val roundScreenSafePaddingScale: Float = DEFAULT_ROUND_SCREEN_SAFE_PADDING_SCALE,
    val pullRefreshDistanceDp: Float = DEFAULT_PULL_REFRESH_DISTANCE_DP,
    val animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,
    val transitionStyle: TransitionStyle = TransitionStyle.SHARED_AXIS_Z,
    val isPureBlack: Boolean = false,
    val preferredFrameRate: FrameRateMode = FrameRateMode.AUTO,
    // 默认圆角由 STANDARD 提到 ROUNDED：STANDARD 下卡片圆角仅 12dp（medium），
    // 观感偏方、显得陈旧；ROUNDED 给到 16dp / 大容器 24dp，与当下主流客户端
    // （BiliPai、PiliPala 等）的圆润卡片一致。
    // 注意：这是**默认值**，用户已在设置里选过圆角风格的不受影响。
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    // 界面风格预设：MATERIAL 维持现有 Material You 全链路（取色 / 调色板 / 圆角档
    // 全部生效）；IOS 切换为固定语义色板 + tint 覆盖 primary + 固定圆角档 + iOS
    // 排版阶梯，此时 useDynamicColor / paletteStyle / cornerStyle 不生效（设置页置灰）。
    // 默认 MATERIAL 是风险面决策：iOS 化覆盖全部 feature 模块，先作为可选项发布，
    // 真机走查干净后翻转默认值只改这一行（读取路径以 defaultThemeConfig 兜底）。
    val uiStyle: UiStyle = UiStyle.MATERIAL,
    // 液态玻璃材质包（挂在 iOS 风格上，非新风格维度）：MATERIAL 下不生效且置灰。
    // 默认关闭——玻璃只在白名单表面生效，先走查再默认。
    val glassEnabled: Boolean = false,
    val glassStyle: com.naaammme.bbspace.core.designsystem.glass.GlassStyle =
        com.naaammme.bbspace.core.designsystem.glass.GlassStyle.STABLE
)

const val DEFAULT_PULL_REFRESH_DISTANCE_DP = 80f
const val DEFAULT_ROUND_SCREEN_SAFE_PADDING_SCALE = 1.0f

enum class CornerStyle {
    SQUARE,
    STANDARD,
    ROUNDED,
    CIRCULAR
}

/**
 * 界面风格预设。MATERIAL 是默认与回退路径：切换到 IOS 后所有视觉 token 换源，
 * 但已有动画链路（无缝 morph、迷你播放器、导航过渡）不受影响。
 */
enum class UiStyle {
    MATERIAL,
    IOS
}

enum class TransitionStyle {
    NONE,
    SHARED_AXIS_X,
    SHARED_AXIS_Y,
    SHARED_AXIS_Z,
    FADE_THROUGH,
    SLIDE
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

enum class PaletteStyle {
    TONAL_SPOT,
    EXPRESSIVE,
    NEUTRAL,
    VIBRANT,
    MONOCHROME
}

enum class AnimationSpeed(val multiplier: Float) {
    OFF(0f),
    FAST(0.5f),
    NORMAL(1f),
    SLOW(1.5f)
}

enum class FrameRateMode(val value: Float) {
    AUTO(0f),
    RATE_60(60f),
    RATE_90(90f),
    RATE_120(120f),
    RATE_144(144f)
}

val PresetColors = listOf(
    Color(0xFFFB7299) to "哔哩粉",
    Color(0xFF00A1D6) to "哔哩蓝",
    Color(0xFFF44336) to "红色",
    Color(0xFFFF9800) to "橙色",
    Color(0xFFFFC107) to "琥珀",
    Color(0xFFFFEB3B) to "黄色",
    Color(0xFFCDDC39) to "酸橙",
    Color(0xFF8BC34A) to "浅绿",
    Color(0xFF4CAF50) to "绿色",
    Color(0xFF009688) to "青色",
    Color(0xFF00BCD4) to "蓝绿",
    Color(0xFF03A9F4) to "浅蓝",
    Color(0xFF2196F3) to "蓝色",
    Color(0xFF3F51B5) to "靛蓝",
    Color(0xFF9C27B0) to "紫色",
    Color(0xFF673AB7) to "深紫",
    Color(0xFF607D8B) to "蓝灰",
    Color(0xFF795548) to "棕色",
    Color(0xFF9E9E9E) to "灰色",
    Color(0xFF000000) to "黑色"
)
