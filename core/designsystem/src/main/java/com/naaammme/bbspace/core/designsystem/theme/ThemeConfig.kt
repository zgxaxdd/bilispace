package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedColorSpec
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedContrastLevel
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedPaletteStyle

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
    // 默认左右滑动（SHARED_AXIS_X）：新装/清数据后的开箱转场。
    // 此前默认 SHARED_AXIS_Z（缩放），开箱第一眼就是"整页缩放"，观感突兀且与
    // iOS 大圆角语言不搭；左右滑动与系统返回手势方向一致，是更安全的默认。
    val transitionStyle: TransitionStyle = TransitionStyle.SHARED_AXIS_X,
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
    // 液态玻璃材质包（挂在 iOS 风格上，非新风格维度）：三档 OFF/FROSTED/LIQUID，
    // 默认 FROSTED（磨砂）。MATERIAL 下不生效且置灰。
    val glassMaterial: com.naaammme.bbspace.core.designsystem.glass.GlassMaterial =
        com.naaammme.bbspace.core.designsystem.glass.GlassMaterial.FROSTED,
    // 液态玻璃统一强度倍率（0.5–2.0）：缩放全部玻璃表面的模糊与折射
    val glassStrength: Float = 1f,
    // ── 种子配色维度（上游 22 风格 HCT 体系；默认值 = 现状）──────────────────
    //
    // 三个字段默认都落在"沿用既有管线"的一档：Standard / SPEC_2021 / Default。
    // `createSeedColorScheme(...)` 的旧实现仍是 Standard 的产出（工厂对 Standard 返回
    // null，由 `Theme.kt` 回落旧实现），所以**默认观感逐值不变**。
    // 仅当用户显式选了非 Standard 风格时，才由 SeedColorSchemeFactory 用 HCT 产出
    // （并跳过 applyPaletteStyle 这一层后处理，避免二次调色）。
    /** 种子风格（22 档，见 SeedPaletteStyle）；Standard = 沿用既有手写配色管线。 */
    val seedPaletteStyle: SeedPaletteStyle = SeedPaletteStyle.Standard,
    /** 色彩标准（2021 / 2025）；2025 只对 4 个 dynamic 风格生效，其余回落 2021。 */
    val seedColorSpec: SeedColorSpec = SeedColorSpec.SPEC_2021,
    /** 对比度档（Reduced / Default / Medium / High），映射到 MCU 的 contrastLevel。 */
    val seedContrastLevel: SeedContrastLevel = SeedContrastLevel.Default,
    /**
     * **全局拖拽反馈**（上游 BiliPai 同名功能，2026-10-17 新增）。
     *
     * 上游设置项文案逐字为：「统一控制按钮与胶囊的拖动形变，关闭后保留按压反馈」。
     * 语义拆开就是本字段的两个消费面：
     * - **开**：按下时按钮/胶囊鼓起，且跟随手指位移**沿受力方向**拉伸 + 跟手平移；
     * - **关**：**仍会鼓起**（按压反馈不受影响），只是不再跟随手指拉伸 ——
     *   因为形变由 `press` 与 `position` 两个独立分量驱动，本字段只门控后者
     *   （见 `motion/AppElasticPress.kt` 的 `effectiveDragEnabled`）。
     *
     * 默认 **true**：与上游一致，也是用户第一次装上就该看到的手感。
     */
    val globalDragFeedbackEnabled: Boolean = true
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

/**
 * 持久化主题色的**下标索引**（20 项，含哔哩粉/哔哩蓝与黑色；名称是短名）。
 *
 * ⚠️ 顺序即已保存主题的下标契约，**不要重排**。它与
 * `theme/scheme/SeedPresetColors`（19 项种子预设）内容不同、契约不同，**不合并**：
 * 去重结论与差异对照见 `docs/2026-10-18-B组-未接线符号接线步骤.md` §4。
 */
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
