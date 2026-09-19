package com.naaammme.bbspace.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.designsystem.glass.GlassConfig
import com.naaammme.bbspace.core.designsystem.glass.GlassGuardPolicy
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassConfig
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassGuard
import com.naaammme.bbspace.core.designsystem.glass.rememberGlassGuardTracker
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 当前界面风格预设。组件层用它做 iOS / Material 双形态分支（分组列表、开关、
 * 分段控件、Dock 等）；经 [BiliTheme] 下发，默认 MATERIAL 保证独立预览可用。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MATERIAL }

private val MonochromeDarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFF2A2A2A),
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF202020),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFCCCCCC),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF262626),
    onTertiaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFD0D0D0),
    surfaceTint = Color.White,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFF404040),
    scrim = Color.Black,
    surfaceBright = Color(0xFF161616),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color.Black,
    surfaceDim = Color.Black,
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

private val MonochromeLightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = Color.Black,
    inversePrimary = Color(0xFF3A3A3A),
    secondary = Color(0xFF2E2E2E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF4A4A4A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E6E6),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF505050),
    surfaceTint = Color.Black,
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color.White,
    outline = Color(0xFF707070),
    outlineVariant = Color(0xFFC8C8C8),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFEDEDED),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainerLowest = Color.White,
    surfaceDim = Color(0xFFF3F3F3),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

@Composable
fun BiliTheme(
    config: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit
) {
    val darkTheme = when (config.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val baseDensity = LocalDensity.current
    val uiScale = config.uiScale

    val colorScheme = remember(
        context,
        darkTheme,
        config.uiStyle,
        config.seedColor,
        config.useDynamicColor,
        config.paletteStyle,
        config.swapBaseColors,
        config.isPureBlack
    ) {
        when (config.uiStyle) {
            // iOS：固定语义色板 + tint（seedColor）覆盖 primary。
            // 动态取色 / 调色板 / 纯黑在 iOS 模式下不参与（设置页置灰，值保留）。
            UiStyle.IOS -> iOSColorScheme(darkTheme, config.seedColor)
            UiStyle.MATERIAL -> {
                val base = when {
                    config.paletteStyle == PaletteStyle.MONOCHROME -> createMonochromeColorScheme(darkTheme)
                    config.useDynamicColor -> {
                        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    }
                    else -> createSeedColorScheme(config.seedColor, darkTheme)
                }
                val styled = if (config.paletteStyle == PaletteStyle.MONOCHROME) base
                else applyPaletteStyle(base, config.paletteStyle, darkTheme)
                val neutralized = applyNeutralBackgroundScheme(styled, darkTheme, config.isPureBlack)
                if (config.swapBaseColors) applyBaseColorSwap(neutralized) else neutralized
            }
        }
    }

    val density = remember(baseDensity, uiScale) {
        Density(
            density = baseDensity.density * uiScale,
            fontScale = baseDensity.fontScale / uiScale
        )
    }
    val typography = remember(config.uiStyle, config.fontScale) {
        when (config.uiStyle) {
            UiStyle.IOS -> iOSTypography(config.fontScale)
            UiStyle.MATERIAL -> createTypography(config.fontScale)
        }
    }
    val shapes = remember(config.uiStyle, config.cornerStyle) {
        when (config.uiStyle) {
            // iOS 圆角是固定档，不随 cornerStyle 漂移（该维度 iOS 下置灰）
            UiStyle.IOS -> iOSShapes()
            UiStyle.MATERIAL -> buildShapes(config.cornerStyle)
        }
    }

    CompositionLocalProvider(LocalDensity provides density) {
    // 帧率守卫：JankStats 窗口统计，jank 连续超标时玻璃整体降级（只失效玻璃叶子）
    val glassGuardTracker = rememberGlassGuardTracker()
    LaunchedEffect(glassGuardTracker) {
        while (true) {
            delay(com.naaammme.bbspace.core.designsystem.glass.GlassGuardTracker.WINDOW_MS)
            glassGuardTracker.tick(System.currentTimeMillis())
        }
    }

    CompositionLocalProvider(
        LocalUiStyle provides config.uiStyle,
        LocalGlassConfig provides GlassConfig(config.glassEnabled, config.glassStyle),
        LocalGlassGuard provides glassGuardTracker.policy
    ) {
        ProvideAnimations(config.animationSpeed) {
            ProvidePullRefresh(config.pullRefreshDistanceDp) {
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = typography,
                    shapes = shapes
                ) {
                    // M3 的 LocalContentColor 默认值是 Color.Black；没有 Scaffold/Surface
                    // 提供内容的宿主（如 VideoDetailActivity）里，未显式指定颜色的文字
                    // 会渲染成黑字——深色主题下压深底不可见。在主题根部统一提供
                    // onBackground，保证所有页面默认文字颜色随主题走。
                    CompositionLocalProvider(
                        LocalContentColor provides colorScheme.onBackground
                    ) {
                        content()
                    }
                }
            }
        }
    }
    }
}

private fun createSeedColorScheme(seedColor: Color, isDark: Boolean): ColorScheme {
    val seed = seedColor.tone(saturationScale = 0.82f, value = 0.92f)
    val primary = seed.tone(value = if (isDark) 0.82f else 0.64f)
    val primaryContainer = seed.tone(
        saturationScale = if (isDark) 0.45f else 0.24f,
        value = if (isDark) 0.26f else 0.92f
    )
    val inversePrimary = seed.tone(value = 0.42f)
    val secondary = seed.tone(
        hueShift = 24f,
        saturationScale = if (isDark) 0.48f else 0.20f,
        value = if (isDark) 0.78f else 0.50f
    )
    val secondaryContainer = seed.tone(
        hueShift = 24f,
        saturationScale = if (isDark) 0.24f else 0.20f,
        value = if (isDark) 0.22f else 0.94f
    )
    val tertiary = seed.tone(
        hueShift = -24f,
        saturationScale = if (isDark) 0.48f else 0.20f,
        value = if (isDark) 0.78f else 0.50f
    )
    val tertiaryContainer = seed.tone(
        hueShift = -24f,
        saturationScale = if (isDark) 0.24f else 0.20f,
        value = if (isDark) 0.22f else 0.94f
    )
    val background = seed.tone(
        saturationScale = if (isDark) 0.06f else 0.08f,
        value = if (isDark) 0.08f else 0.97f
    )
    val surface = seed.tone(
        saturationScale = if (isDark) 0.06f else 0.08f,
        value = if (isDark) 0.10f else 0.98f
    )
    val surfaceTint = primary
    val surfaceVariant = if (isDark) Color(0xFF1C1C1C) else Color(0xFFF0F0F0)
    val onSurfaceVariant = if (isDark) Color(0xFFD0D0D0) else Color(0xFF505050)
    val scrim = seed.tone(saturationScale = 0.04f, value = if (isDark) 0.04f else 0.10f)
    val surfaceBright = if (isDark) seed.tone(saturationScale = 0.06f, value = 0.16f) else Color.White
    val surfaceContainer = seed.tone(
        saturationScale = 0.06f,
        value = if (isDark) 0.12f else 0.96f
    )
    val surfaceContainerHigh = seed.tone(
        saturationScale = 0.06f,
        value = if (isDark) 0.16f else 0.94f
    )
    val surfaceContainerHighest = seed.tone(
        saturationScale = 0.06f,
        value = if (isDark) 0.20f else 0.92f
    )
    val surfaceContainerLow = seed.tone(
        saturationScale = 0.06f,
        value = if (isDark) 0.08f else 0.98f
    )
    val surfaceContainerLowest = if (isDark) seed.tone(saturationScale = 0.04f, value = 0.05f) else Color.White
    val surfaceDim = seed.tone(
        saturationScale = 0.05f,
        value = if (isDark) 0.06f else 0.94f
    )
    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.Black,
            primaryContainer = primaryContainer,
            onPrimaryContainer = Color.White,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = Color.Black,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = Color.White,
            tertiary = tertiary,
            onTertiary = Color.Black,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = Color.White,
            background = background,
            onBackground = Color.White,
            surface = surface,
            onSurface = Color.White,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            outline = Color(0xFF808080),
            outlineVariant = Color(0xFF404040),
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceDim = surfaceDim,
            error = Color(0xFFCF6679),
            onError = Color(0xFF000000),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = Color.Black,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = Color.Black,
            tertiary = tertiary,
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = Color.Black,
            background = background,
            onBackground = Color.Black,
            surface = surface,
            onSurface = Color.Black,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = Color(0xFF1A1A1A),
            inverseOnSurface = Color.White,
            outline = Color(0xFF707070),
            outlineVariant = Color(0xFFC8C8C8),
            scrim = scrim,
            surfaceBright = Color.White,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceDim = surfaceDim,
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B)
        )
    }
}

private data class PaletteParams(
    val secondaryHue: Float,
    val tertiaryHue: Float,
    val primaryContainerSat: Float,
    val accentContainerSat: Float,
    val inversePrimaryValue: Float,
    val inversePrimarySat: Float = 1f,
)

private val PALETTE_PARAMS = mapOf(
    PaletteStyle.TONAL_SPOT to PaletteParams(
        secondaryHue = 24f, tertiaryHue = -24f,
        primaryContainerSat = 0.45f, accentContainerSat = 0.32f,
        inversePrimaryValue = 0.42f
    ),
    PaletteStyle.EXPRESSIVE to PaletteParams(
        secondaryHue = 72f, tertiaryHue = -72f,
        primaryContainerSat = 0.40f, accentContainerSat = 0.28f,
        inversePrimaryValue = 0.46f
    ),
    PaletteStyle.NEUTRAL to PaletteParams(
        secondaryHue = 0f, tertiaryHue = 0f,
        primaryContainerSat = 0.26f, accentContainerSat = 0.18f,
        inversePrimaryValue = 0.42f, inversePrimarySat = 0.28f
    ),
    PaletteStyle.VIBRANT to PaletteParams(
        secondaryHue = 32f, tertiaryHue = -32f,
        primaryContainerSat = 0.70f, accentContainerSat = 0.40f,
        inversePrimaryValue = 0.50f
    ),
)

private fun applyPaletteStyle(
    base: ColorScheme,
    style: PaletteStyle,
    isDark: Boolean
): ColorScheme {
    val p = PALETTE_PARAMS[style]!!
    val primary = base.primary
    val primarySat = when (style) {
        PaletteStyle.TONAL_SPOT -> if (isDark) 0.60f else 0.72f
        PaletteStyle.EXPRESSIVE -> if (isDark) 0.62f else 0.68f
        PaletteStyle.NEUTRAL -> if (isDark) 0.32f else 0.36f
        PaletteStyle.VIBRANT -> 1.00f
        else -> 0f
    }
    val secondarySat = when (style) {
        PaletteStyle.TONAL_SPOT -> if (isDark) 0.32f else 0.28f
        PaletteStyle.EXPRESSIVE -> if (isDark) 0.56f else 0.62f
        PaletteStyle.NEUTRAL -> if (isDark) 0.22f else 0.24f
        PaletteStyle.VIBRANT -> 0.92f
        else -> 0f
    }
    val tertiarySat = when (style) {
        PaletteStyle.TONAL_SPOT -> if (isDark) 0.32f else 0.28f
        PaletteStyle.EXPRESSIVE -> if (isDark) 0.54f else 0.60f
        PaletteStyle.NEUTRAL -> if (isDark) 0.20f else 0.22f
        PaletteStyle.VIBRANT -> 0.90f
        else -> 0f
    }
    val primaryValue = if (isDark) 0.82f else 0.64f
    val containerValue = if (isDark) 0.26f else 0.92f
    val accentValue = if (isDark) 0.78f else 0.50f
    val accentContainerValue = if (isDark) 0.22f else 0.94f
    val surfaceVariant = when (style) {
        PaletteStyle.EXPRESSIVE -> base.surfaceVariant.mix(base.background, 0.18f)
        PaletteStyle.NEUTRAL -> if (isDark) Color(0xFF1C1C1C) else Color(0xFFF0F0F0)
        PaletteStyle.VIBRANT -> base.surfaceVariant.mix(primary, 0.12f)
        else -> base.surfaceVariant
    }
    val onSurfaceVariant = when (style) {
        PaletteStyle.NEUTRAL -> if (isDark) Color(0xFFD0D0D0) else Color(0xFF505050)
        else -> base.onSurfaceVariant
    }

    return base.copy(
        primary = primary.tone(value = primaryValue, saturationScale = primarySat),
        onPrimary = if (isDark) Color.Black else Color.White,
        primaryContainer = primary.tone(value = containerValue, saturationScale = p.primaryContainerSat),
        onPrimaryContainer = if (isDark) Color.White else Color.Black,
        inversePrimary = primary.tone(value = p.inversePrimaryValue, saturationScale = p.inversePrimarySat),
        secondary = primary.tone(hueShift = p.secondaryHue, saturationScale = secondarySat, value = accentValue),
        onSecondary = if (isDark) Color.Black else Color.White,
        secondaryContainer = primary.tone(
            hueShift = p.secondaryHue,
            saturationScale = p.accentContainerSat,
            value = accentContainerValue
        ),
        onSecondaryContainer = if (isDark) Color.White else Color.Black,
        tertiary = primary.tone(hueShift = p.tertiaryHue, saturationScale = tertiarySat, value = accentValue),
        onTertiary = if (isDark) Color.Black else Color.White,
        tertiaryContainer = primary.tone(
            hueShift = p.tertiaryHue,
            saturationScale = p.accentContainerSat,
            value = accentContainerValue
        ),
        onTertiaryContainer = if (isDark) Color.White else Color.Black,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary.tone(value = primaryValue, saturationScale = primarySat)
    )
}

private fun applyNeutralBackgroundScheme(
    base: ColorScheme,
    isDark: Boolean,
    usePureBackground: Boolean
): ColorScheme {
    if (!usePureBackground) {
        return base
    }
    return if (isDark) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF161616),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF181818),
            surfaceContainerHighest = Color(0xFF202020)
        )
    } else {
        base.copy(
            background = Color.White,
            surface = Color.White,
            surfaceDim = Color(0xFFF5F5F5),
            surfaceBright = Color.White,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFAFA),
            surfaceContainer = Color(0xFFF7F7F7),
            surfaceContainerHigh = Color(0xFFF2F2F2),
            surfaceContainerHighest = Color(0xFFEDEDED)
        )
    }
}

private fun createMonochromeColorScheme(isDark: Boolean): ColorScheme {
    return if (isDark) MonochromeDarkColorScheme else MonochromeLightColorScheme
}

private fun applyBaseColorSwap(base: ColorScheme): ColorScheme {
    return base.copy(
        background = base.secondaryContainer,
        onBackground = base.onSecondaryContainer,
        surface = base.secondaryContainer,
        onSurface = base.onSecondaryContainer,
        surfaceDim = base.secondaryContainer,
        surfaceBright = base.secondaryContainer,
        surfaceContainerLowest = base.background,
        surfaceContainerLow = base.background,
        surfaceContainer = base.background,
        surfaceContainerHigh = base.background,
        surfaceContainerHighest = base.background,
        surfaceVariant = base.background,
        onSurfaceVariant = base.onBackground,
        surfaceTint = base.onSecondaryContainer,
        inverseSurface = base.secondaryContainer,
        inverseOnSurface = base.onSecondaryContainer,
        secondaryContainer = base.background,
        onSecondaryContainer = base.onBackground
    )
}

// Color.tone / Color.mix 已抽至 ColorUtils.kt（internal）：iOS 色板需要复用同一套
// HSV 派生工具，且 Kotlin 的文件级 private 无法跨文件访问。函数体未改动，
// 本文件处于同一包，调用点无需 import。
