package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * iOS 风格固定语义色板（方案 2.4 / 3.3 节）。
 *
 * 与 Material 链路的本质差异：Material You 从 seed 派生全套色调分层；iOS 是
 * **固定语义色板 + tint 覆盖 primary**——灰阶与文字色是 Apple 的常量，只有强调色
 * 跟随 [tint]（默认哔哩粉 #FB7299，用户仍可从预设 20 色改）。
 *
 * **硬性规则：`surface` 必须等于 `background`（页面底色），不能放卡片色。**
 * 项目里 `colorScheme.surface` 的使用处是真正的页面背景（首页 / 排行 / 搜索 /
 * IM 输入区 / Space 头部等），M3 语义里 surface 就是页面底色。iOS 的"灰底白卡"
 * 分层是：页面 #F2F2F7（浅色）、卡片白只给 `surfaceContainerLow`——
 * `BiliCardSurface` 默认容器色正是它，卡片照样是白的。若把白色给 surface，
 * 上述页面会整体变白，与卡片失去对比，分层即被破坏。
 *
 * 深色 tint 提亮：对 seed 做 value → 1f、饱和 ×0.85（HSV 派生复用 [Color.tone]），
 * 使默认粉得到 #FF8CAD 一级的暗底可读色（对比度约 9.6:1，≥4.5:1 达标）。
 *
 * `isPureBlack` 在 iOS 模式下不参与：深色体系本身即纯黑背景，卡片保持 #1C1C1E
 * （对比度 1.23:1 是 Apple 真实取值，iOS 靠 separator 与内容分层；再压暗卡片
 * 只会让纯黑模式下卡片更难分辨）。
 */
fun iOSColorScheme(darkTheme: Boolean, tint: Color): ColorScheme {
    // 色相分化：此前 primary / secondary / secondaryContainer / primaryContainer
    // **全部是同一个 tint**，界面上所有强调元素同色同调 —— 这正是用户反馈的
    // "颜色平淡、没有活力"的根因：没有第二色彩维度，也没有 tonal 层次。
    // 现在把 secondary 绕色相环转 210°（≈ 互补偏冷），与粉调主色形成对冲；
    // tertiary 保留 iOS 系统绿作第三点。用 [tone] 的 hueShift 一步完成，
    // 不引入新的色板常量，用户换 tint 时 secondary 仍自动跟随。
    val secondarySeed = tint.tone(hueShift = 210f, saturationScale = 0.90f, value = 0.95f)
    // 容器色：此前 tint.mix(White, 0.85f) 只留 15% 色料，几乎看不出是"染色容器"。
    // 收到 0.78，让 primaryContainer 真正读作一块淡彩色底。
    val tintContainerMix = 0.78f

    return if (darkTheme) {
        val brightTint = tint.tone(saturationScale = 0.85f, value = 1f)
        val secondary = secondarySeed.tone(saturationScale = 0.80f, value = 1f)
        darkColorScheme(
            primary = brightTint,
            onPrimary = Color.Black,
            primaryContainer = brightTint.mix(IosDarkSurfaceCard, 0.72f),
            onPrimaryContainer = brightTint,
            inversePrimary = tint,
            secondary = secondary,
            onSecondary = Color.Black,
            secondaryContainer = secondary.mix(IosDarkSurfaceCard, 0.72f),
            onSecondaryContainer = secondary,
            tertiary = Color(0xFF30D158),
            onTertiary = Color.Black,
            tertiaryContainer = Color(0xFF30D158).mix(IosDarkSurfaceCard, 0.72f),
            onTertiaryContainer = Color(0xFF30D158),
            background = IosDarkGroupedBackground,
            onBackground = Color.White,
            surface = IosDarkGroupedBackground,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = Color(0x99EBEBF5),
            // surfaceTint 取 surface：iOS 无色调高程概念，tonalElevation 不应带出染色
            surfaceTint = IosDarkGroupedBackground,
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            outline = Color(0x4DEBEBF5),
            outlineVariant = Color(0xA6545458),
            scrim = Color.Black,
            surfaceBright = Color(0xFF2C2C2E),
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = IosDarkSurfaceCard,
            surfaceContainer = Color(0xFF2C2C2E),
            surfaceContainerHigh = Color(0xFF3A3A3C),
            surfaceContainerHighest = Color(0xFF48484A),
            error = Color(0xFFFF453A),
            onError = Color.Black,
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC)
        )
    } else {
        val secondary = secondarySeed.tone(saturationScale = 0.85f, value = 0.62f)
        lightColorScheme(
            primary = tint,
            onPrimary = Color.White,
            primaryContainer = tint.mix(Color.White, tintContainerMix),
            onPrimaryContainer = tint.tone(value = 0.42f),
            inversePrimary = tint.tone(value = 0.80f),
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondary.mix(Color.White, tintContainerMix),
            onSecondaryContainer = secondary.tone(value = 0.38f),
            tertiary = Color(0xFF34C759),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFF34C759).mix(Color.White, tintContainerMix),
            onTertiaryContainer = Color(0xFF248A3D),
            background = IosLightGroupedBackground,
            onBackground = Color.Black,
            surface = IosLightGroupedBackground,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFE5E5EA),
            onSurfaceVariant = Color(0x993C3C43),
            surfaceTint = IosLightGroupedBackground,
            inverseSurface = Color(0xFF1C1C1E),
            inverseOnSurface = Color.White,
            outline = Color(0x4D3C3C43),
            outlineVariant = Color(0x4A3C3C43),
            scrim = Color.Black,
            surfaceBright = Color.White,
            surfaceDim = Color(0xFFD1D1D6),
            surfaceContainerLowest = Color.White,
            // 卡片 / 分组底色只在这里：surfaceContainerLow（BiliCardSurface 默认容器色）
            surfaceContainerLow = Color.White,
            surfaceContainer = IosLightGroupedBackground,
            surfaceContainerHigh = Color(0xFFE5E5EA),
            surfaceContainerHighest = Color(0xFFE5E5EA),
            error = Color(0xFFFF3B30),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B)
        )
    }
}

/** 浅色页面底色：iOS systemGroupedBackground。 */
private val IosLightGroupedBackground = Color(0xFFF2F2F7)

/** 深色页面底色：iOS 纯黑（深色 grouped 语义）。 */
private val IosDarkGroupedBackground = Color.Black

/** 深色卡片底：iOS secondarySystemGroupedBackground #1C1C1E。 */
private val IosDarkSurfaceCard = Color(0xFF1C1C1E)
