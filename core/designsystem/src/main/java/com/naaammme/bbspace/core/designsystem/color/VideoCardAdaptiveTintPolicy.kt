package com.naaammme.bbspace.core.designsystem.color

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 推荐页卡片「动态取色」策略（移植自 BiliPai 上游 `VideoCardAdaptiveTintPolicy`）。
 *
 * 全部是**纯函数**：输入颜色与开关，输出颜色与透明度。不依赖 Compose 运行时、
 * 不读 CompositionLocal，因此可以在 JVM 单测里逐个断言（上游同样配了 352 行测试）。
 *
 * 上游有两路取色来源：
 * - **壁纸调色板**（跟随系统壁纸，按滚动 Y 轴插值）；
 * - **封面代表色**（[VideoCardCoverColorStore] 提取）。
 *
 * BBSpace 目前只移植**封面代表色**这一路（壁纸那路依赖 WallpaperManager 全链路，
 * 单独一期做）。因此策略函数保留了 `wallpaperTintEnabled` 形参但调用方传 false，
 * 这样将来接壁纸时不需要改签名与测试。
 */

/**
 * 卡片底部信息区的动态着色决策结果。
 *
 * @param containerColor 卡片容器底色（半透明，透出背后的模糊）
 * @param coverGlowAlpha 封面边缘微光强度；0 = 不画
 * @param borderColor 描边色（与封面同调，构筑毛玻璃棱镜边）
 */
@Immutable
data class VideoCardAmbientDrawSpec(
    val containerColor: Color,
    val coverGlowAlpha: Float,
    val borderColor: Color
)

/**
 * 是否该用封面取色。
 *
 * 壁纸取色开启时，壁纸是唯一来源，封面色只作为**无壁纸时的回退**
 * （上游注释原文："壁纸是卡片动态取色的唯一来源时，封面色只作为无壁纸时的回退"）。
 */
internal fun shouldUseCoverTintForCard(
    wallpaperTintEnabled: Boolean,
    coverTint: Color?,
): Boolean = !wallpaperTintEnabled && coverTint != null && coverTint.alpha > 0f

/**
 * 综合决策卡片底部组件的着色方案。
 *
 * 关键质感取舍（照搬上游，勿自行调参）：
 * - 容器是**半透明**的（深色 0.38 / 浅色 0.34，省流量档放宽到 0.65/0.70）——
 *   要"直接透出背后的高斯模糊"，同时保留饱和色彩，避免发白发灰的厚重白雾；
 * - 封面色与默认底色按比例混合（深色 0.85 / 浅色 0.75），不是直接铺满，
 *   否则卡片会变成一块纯色板、失去玻璃感；
 * - 微光与描边只在**确实有封面色**时才有强度，否则会凭空出现无来源的光。
 */
fun resolveVideoCardAmbientDrawSpec(
    coverTint: Color?,
    isDarkTheme: Boolean,
    defaultContainerColor: Color,
    defaultBorderColor: Color,
    wallpaperTintEnabled: Boolean = false,
    isDataSaverActive: Boolean = false,
    frostedGlassEnabled: Boolean = true
): VideoCardAmbientDrawSpec {
    if (!frostedGlassEnabled || (!wallpaperTintEnabled && coverTint == null)) {
        return VideoCardAmbientDrawSpec(
            containerColor = defaultContainerColor,
            coverGlowAlpha = 0f,
            borderColor = defaultBorderColor
        )
    }

    val hasValidCoverTint = shouldUseCoverTintForCard(
        wallpaperTintEnabled = wallpaperTintEnabled,
        coverTint = coverTint,
    )

    val glassTransparency = if (isDarkTheme) {
        if (isDataSaverActive) 0.65f else 0.38f
    } else {
        if (isDataSaverActive) 0.70f else 0.34f
    }

    val baseColor = if (hasValidCoverTint) {
        val blendFraction = if (isDarkTheme) 0.85f else 0.75f
        lerp(defaultContainerColor, coverTint!!, blendFraction).copy(alpha = glassTransparency)
    } else {
        // 无壁纸且无封面：保持中性玻璃表面，不要沿用上一张封面的颜色
        defaultContainerColor.copy(alpha = glassTransparency)
    }

    val glowAlpha = if (hasValidCoverTint) {
        if (isDarkTheme) 0.48f else 0.38f
    } else {
        0f
    }

    val finalBorderColor = if (hasValidCoverTint) {
        val borderBlendFraction = if (isDarkTheme) 0.50f else 0.40f
        val borderAlpha = if (isDarkTheme) 0.30f else 0.45f
        val baseBorder = Color.White.copy(alpha = borderAlpha)
        lerp(baseBorder, coverTint!!, borderBlendFraction).copy(alpha = borderAlpha)
    } else {
        if (isDarkTheme) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.40f)
    }

    return VideoCardAmbientDrawSpec(
        containerColor = baseColor,
        coverGlowAlpha = glowAlpha,
        borderColor = finalBorderColor
    )
}

/**
 * 卡片文字色是否需要在「浅色主题 + 深色封面」时翻转成白色。
 *
 * 这是动态取色的**可读性兜底**：浅色主题下若封面是深色（夜景、黑底），
 * 卡片被染深后原来的深色正文会失去对比度。判据用封面色的相对亮度阈值
 * （0.38，上游实测取值），而不是"看起来深不深"。
 */
@Immutable
data class VideoCardAdaptiveContentColors(
    val titleColor: Color,
    val subtitleColor: Color,
    val isDarkSurface: Boolean,
)

fun resolveVideoCardAdaptiveContentColors(
    coverTint: Color?,
    isDarkTheme: Boolean,
    defaultOnSurface: Color,
    defaultOnSurfaceVariant: Color,
    wallpaperTintEnabled: Boolean = false,
    dynamicTintEnabled: Boolean = true,
): VideoCardAdaptiveContentColors {
    if (!dynamicTintEnabled) {
        return VideoCardAdaptiveContentColors(
            titleColor = defaultOnSurface,
            subtitleColor = defaultOnSurfaceVariant,
            isDarkSurface = isDarkTheme
        )
    }

    val isDarkSurface = if (isDarkTheme) {
        true
    } else {
        val hasValidCover = shouldUseCoverTintForCard(
            wallpaperTintEnabled = wallpaperTintEnabled,
            coverTint = coverTint,
        )
        hasValidCover && coverTint!!.luminance() < 0.38f
    }

    return if (isDarkSurface && !isDarkTheme) {
        VideoCardAdaptiveContentColors(
            titleColor = Color.White,
            subtitleColor = Color.White.copy(alpha = 0.78f),
            isDarkSurface = true
        )
    } else {
        VideoCardAdaptiveContentColors(
            titleColor = defaultOnSurface,
            subtitleColor = defaultOnSurfaceVariant,
            isDarkSurface = isDarkSurface
        )
    }
}

/**
 * 卡片动态取色总开关（设置页可关）。默认 true：这是推荐页的观感特性，
 * 关掉后退回中性玻璃表面。
 */
val LocalHomeCardDynamicTintEnabled = staticCompositionLocalOf { true }
