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
 * 深色（2026-09-26 重设计）：默认为 **seed 染色的炭色阶梯**——底/卡/容器/浮层
 * 四层以 tint 色相上 4–10% 低饱和染色，层间连续可辨，解决"纯黑单调"（用户反馈）；
 * `isPureBlack=true` 保留纯黑底（OLED），容器层仍带染色。卡片描边（BiliCardSurface
 * 的 7% 白发丝）仅在纯黑底时启用。参考：Telegram #17212B、Discord #313338、
 * Spotify #121212、PiliPlus darkenTheme。
 */
fun iOSColorScheme(
    darkTheme: Boolean,
    tint: Color,
    isPureBlack: Boolean = false
): ColorScheme {
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
            val darkBg = if (isPureBlack) {
                Color.Black
            } else {
                Color(0xFF0B0C11).mix(tint, 0.04f)
            }
            val darkCard = Color(0xFF17181E).mix(tint, 0.06f)
            val darkContainer = Color(0xFF1D1F27).mix(tint, 0.07f)
            val darkContainerHigh = Color(0xFF262836).mix(tint, 0.08f)
            val darkContainerHighest = Color(0xFF313344).mix(tint, 0.09f)
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
            // 有色深色（iOS 深色增强 2026-09-26）：现代深色的通行做法是不取纯黑，
            // 而是用带品牌色相的低饱和炭色分层（Telegram #17212B / Discord #313338 /
            // Spotify #121212；PiliPlus darkenTheme 同构）。以 tint 色相给各层上
            // 4–10% 染色，形成 底 → 卡 → 容器 → 浮层 的连续阶梯，解决"纯黑单调"。
            // isPureBlack=true 保留纯黑底给 OLED 党（容器层仍带染色）。
            background = darkBg,
            onBackground = Color.White,
            surface = darkBg,
            onSurface = Color.White,
            surfaceVariant = darkContainer,
            onSurfaceVariant = Color(0x99EBEBF5),
            // surfaceTint 取 surface：iOS 无色调高程概念，tonalElevation 不应带出染色
            surfaceTint = darkBg,
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            outline = Color(0x4DEBEBF5),
            outlineVariant = Color(0xA6545458),
            scrim = Color.Black,
            surfaceBright = darkContainerHigh,
            surfaceDim = if (isPureBlack) Color.Black else Color(0xFF07080B),
            surfaceContainerLowest = if (isPureBlack) Color.Black else Color(0xFF07080B),
            surfaceContainerLow = darkCard,
            surfaceContainer = darkContainer,
            surfaceContainerHigh = darkContainerHigh,
            surfaceContainerHighest = darkContainerHighest,
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
            // 浅色次要文字 AA（最暗容器底上 ≥5:1）：0x99 旧值仅 3.1~3.4:1，设置页
            // 副标题读不清（用户反馈 2026-09-26；0xBC 在最暗容器底只有 4.38:1，故取 0xC8）
            onSurfaceVariant = Color(0xC83C3C43),
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

// 这里曾有 `IosDarkLiftedBackground = 0xFF0A0A0B`（"非纯黑档的抬升底"），
// 深色改 seed 染色炭色之后它再无任何引用，随 BACKLOG UI-10 删除。
// 深色分层现在由 iOSColorScheme 的染色阶梯负责，别再回来加一个"平行的一档"。

/** 深色卡片底：iOS secondarySystemGroupedBackground #1C1C1E。 */
private val IosDarkSurfaceCard = Color(0xFF1C1C1E)
