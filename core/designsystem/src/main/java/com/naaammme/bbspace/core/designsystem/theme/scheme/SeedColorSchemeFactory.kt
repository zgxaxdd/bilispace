package com.naaammme.bbspace.core.designsystem.theme.scheme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle as KolorPaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Cam16
import com.materialkolor.hct.Hct
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 22 个种子风格 → `ColorScheme` 的**生成器**（`theme/scheme/` 的出口）。
 *
 * ## 接线状态（2026-10-18 晚：已接线，默认零变化）
 * `Theme.kt` 的 `MATERIAL` 分支在 **`seedPaletteStyle != Standard`** 时调用本对象，
 * `Standard` 返回 null 由调用方回落到旧的 `createSeedColorScheme`（36 槽逐槽相等的断言
 * 见 `SeedThemeWiringTest`）。非 Standard 时**跳过 `applyPaletteStyle`**（否则 HCT 被二次
 * 调色）；`applyNeutralBackgroundScheme` / `applyBaseColorSwap` 保留（用户显式开关）。
 *
 * ⚠️ 历史注记（勿删）：本对象最初按"C 方案：只交付引擎"落地，注释里写的"一行未改"
 * 已经**过期**——接线是下一批同一夜完成的。留这段是为了让后来者知道"为什么它曾有
 * 一段时间没有消费者"。
 *
 * ## 两条产出路径（与上游同构）
 * - **12 个固定表风格**：用本仓 `SeedTones.forStyle(style, isDark)` 的 tone/chroma 表，
 *   直接 `Hct.from(hue, chroma, tone)` 出色。表来自上游 `flex_seed_scheme 5.0.1`
 *   （本仓 `SeedTones.kt` 逐值搬运），所以这批的颜色是"由同一张表推导"。
 * - **9 个 dynamic 风格**：本仓不便搬上游那套 `SeedToneResolver` + `SeedDynamicRules`
 *   内部子类（见下），因此走 material-kolor **公开入口** `dynamicColorScheme(..., SPEC_2021)`；
 *   它内部就是 MCU 的 Scheme* + 2021 规则，是本仓能拿到的同源实现。
 *
 * ## 为什么不搬上游 `SeedToneResolver` / `SeedDynamicRules` / `SeedTextContrastPolicy`
 * 三件共约 254 行，其中 `SeedDynamicRules` 继承 material-kolor 的 `ColorSpec2021` 并
 * 覆盖 6 个方法（`surface()/onPrimaryContainer()/secondaryContainer()/onTertiaryContainer()/
 * onErrorContainer()` 等）。那是**库内部实现细节**（`ColorSpec2021` 的方法签名随版本
 * 变动），照搬到本仓等于把版本耦合搬进来；而 `dynamicColorScheme(...)` 是库承诺的
 * 公开面，语义等价（同库同版本、同 SPEC_2021）。**因此本轮不写"与上游逐值一致"的
 * 断言**（无法在本仓证伪），dynamic 只断言结构不变式（见 `SeedColorSchemeFactoryTest`）。
 *
 * ## `Standard` / 非法风格 ⇒ 返回 null（保守回退）
 * `null` 的语义是：**"本生成器不接管，调用方沿用既有管线"**。`Theme.kt` 现有
 * `else -> createSeedColorScheme(...)` 就是既有管线；下一轮接线时只需
 * `SeedColorSchemeFactory.create(...) ?: createSeedColorScheme(...)`。
 * `SeedPaletteStyle.fromStored(null/未知)` 已回落 Standard ⇒ 旧配置/坏值都不会崩。
 */
internal object SeedColorSchemeFactory {

    /**
     * 生成一个种子风格方案。
     *
     * @param style 22 风格之一；`Standard` 返回 null
     * @param pureBlack 深色 + 纯黑 OLED：按上游同款公式压暗（仅深色生效）
     * @param colorSpec 色彩标准。**只对 9 个 dynamic 风格生效**；固定表风格用的是
     *   本仓 `SeedTones` 的 2021 表，传 2025 也会被 `resolveSeedPaletteSpec` 回落
     *   2021（与上游同一条回落规则），因此固定风格的产出与 `colorSpec` 无关。
     * @param contrastLevel 对比度档。**同样只对 dynamic 风格生效**（映射到 MCU 的
     *   `contrastLevel`：Reduced −1.0 / Default 0.0 / Medium 0.5 / High 1.0）。
     * @return ColorScheme；`Standard`/未知/取不到固定表时返回 null
     */
    fun create(
        seedColor: Color,
        isDark: Boolean,
        style: SeedPaletteStyle,
        pureBlack: Boolean = false,
        colorSpec: SeedColorSpec = SeedColorSpec.SPEC_2021,
        contrastLevel: SeedContrastLevel = SeedContrastLevel.Default
    ): ColorScheme? {
        // Standard = "沿用既有管线"，不是一种种子方案
        if (!style.enabled) return null
        val base = if (style.isDynamic) {
            dynamicSeedColorScheme(seedColor, isDark, style, colorSpec, contrastLevel)
        } else {
            val tones = SeedTones.forStyle(style, isDark) ?: return null
            fixedSeedColorScheme(seedColor, isDark, tones)
        }
        return if (isDark && pureBlack) darkenSeedColorScheme(base) else base
    }

    // ── 固定表风格（12 个）─────────────────────────────────────────────────
    //
    // 逐行对应上游 `fixedSeedColorScheme`：同一个调色板组（primary/secondary/…）
    // 共用 hue/chroma，色相旋转与最小彩度都在 SeedTones 表里；error 固定 hue 25。
    private fun fixedSeedColorScheme(
        seed: Color,
        isDark: Boolean,
        tones: SeedTones
    ): ColorScheme {
        val hct = Hct.fromInt(seed.toArgb())
        val cam = Cam16.fromInt(seed.toArgb())
        val hue = if (tones.useCam16) cam.hue else hct.hue
        val chroma = if (tones.useCam16) cam.chroma else hct.chroma
        fun color(group: String, tone: Int): Color {
            val (paletteHue, paletteChroma) = when (group) {
                "primary" -> hue to max(tones.primaryMinChroma ?: 48.0, tones.primaryChroma ?: chroma)
                "secondary" -> hue to max(tones.secondaryMinChroma ?: 0.0, tones.secondaryChroma ?: chroma)
                "tertiary" -> ((hue + (tones.tertiaryHueRotation ?: 60.0)) % 360.0 + 360.0) % 360.0 to
                    max(tones.tertiaryMinChroma ?: 0.0, tones.tertiaryChroma ?: chroma)
                "neutral" -> hue to max(tones.neutralMinChroma ?: 0.0, tones.neutralChroma ?: chroma)
                "neutralVariant" -> hue to max(tones.neutralVariantMinChroma ?: 0.0, tones.neutralVariantChroma ?: chroma)
                // error 的色相不走种子（M3 固定红），彩度走表
                "error" -> 25.0 to max(tones.errorMinChroma ?: 0.0, tones.errorChroma ?: 84.0)
                else -> error("Unknown tonal palette")
            }
            // 直接按 HCT 求值（而不是 TonalPalette.tone）：上游注释指出新版
            // TonalPalette 对 tone>=99 有黄色平均特例，与参考表不一致。
            return Color(Hct.from(paletteHue, paletteChroma, tone.toDouble()).toInt())
        }
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = color("primary", tones.primaryTone),
            onPrimary = color("primary", tones.onPrimaryTone),
            primaryContainer = color("primary", tones.primaryContainerTone),
            onPrimaryContainer = color("primary", tones.onPrimaryContainerTone),
            primaryFixed = color("primary", tones.primaryFixedTone),
            primaryFixedDim = color("primary", tones.primaryFixedDimTone),
            onPrimaryFixed = color("primary", tones.onPrimaryFixedTone),
            onPrimaryFixedVariant = color("primary", tones.onPrimaryFixedVariantTone),
            secondary = color("secondary", tones.secondaryTone),
            onSecondary = color("secondary", tones.onSecondaryTone),
            secondaryContainer = color("secondary", tones.secondaryContainerTone),
            onSecondaryContainer = color("secondary", tones.onSecondaryContainerTone),
            secondaryFixed = color("secondary", tones.secondaryFixedTone),
            secondaryFixedDim = color("secondary", tones.secondaryFixedDimTone),
            onSecondaryFixed = color("secondary", tones.onSecondaryFixedTone),
            onSecondaryFixedVariant = color("secondary", tones.onSecondaryFixedVariantTone),
            tertiary = color("tertiary", tones.tertiaryTone),
            onTertiary = color("tertiary", tones.onTertiaryTone),
            tertiaryContainer = color("tertiary", tones.tertiaryContainerTone),
            onTertiaryContainer = color("tertiary", tones.onTertiaryContainerTone),
            tertiaryFixed = color("tertiary", tones.tertiaryFixedTone),
            tertiaryFixedDim = color("tertiary", tones.tertiaryFixedDimTone),
            onTertiaryFixed = color("tertiary", tones.onTertiaryFixedTone),
            onTertiaryFixedVariant = color("tertiary", tones.onTertiaryFixedVariantTone),
            error = color("error", tones.errorTone),
            onError = color("error", tones.onErrorTone),
            errorContainer = color("error", tones.errorContainerTone),
            onErrorContainer = color("error", tones.onErrorContainerTone),
            surface = color("neutral", tones.surfaceTone),
            surfaceDim = color("neutral", tones.surfaceDimTone),
            surfaceBright = color("neutral", tones.surfaceBrightTone),
            surfaceContainerLowest = color("neutral", tones.surfaceContainerLowestTone),
            surfaceContainerLow = color("neutral", tones.surfaceContainerLowTone),
            surfaceContainer = color("neutral", tones.surfaceContainerTone),
            surfaceContainerHigh = color("neutral", tones.surfaceContainerHighTone),
            surfaceContainerHighest = color("neutral", tones.surfaceContainerHighestTone),
            onSurface = color("neutral", tones.onSurfaceTone),
            onSurfaceVariant = color("neutralVariant", tones.onSurfaceVariantTone),
            outline = color("neutralVariant", tones.outlineTone),
            outlineVariant = color("neutralVariant", tones.outlineVariantTone),
            scrim = color("neutral", tones.scrimTone),
            inverseSurface = color("neutral", tones.inverseSurfaceTone),
            inverseOnSurface = color("neutral", tones.onInverseSurfaceTone),
            inversePrimary = color("primary", tones.inversePrimaryTone),
            surfaceTint = color("primary", tones.surfaceTintTone),
            background = color("neutral", tones.surfaceTone),
            onBackground = color("neutral", tones.onSurfaceTone),
            surfaceVariant = color("neutral", tones.surfaceContainerHighestTone),
        )
    }

    // ── dynamic 风格（9 个）───────────────────────────────────────────────
    private fun dynamicSeedColorScheme(
        seedColor: Color,
        isDark: Boolean,
        style: SeedPaletteStyle,
        colorSpec: SeedColorSpec,
        contrastLevel: SeedContrastLevel
    ): ColorScheme {
        // 2025 只对 4 个风格存在；其余回落 2021（与上游 `resolveSeedPaletteSpec`
        // 同一条规则，设置页据此提示"已保留偏好"）。
        val effectiveSpec = resolveSeedPaletteSpec(style, colorSpec)
        return dynamicColorScheme(
            seedColor = seedColor,
            isDark = isDark,
            style = style.toKolorStyle(),
            contrastLevel = contrastLevel.level,
            specVersion = effectiveSpec.toKolorSpec()
        )
    }

    private fun SeedColorSpec.toKolorSpec(): ColorSpec.SpecVersion = when (this) {
        SeedColorSpec.SPEC_2021 -> ColorSpec.SpecVersion.SPEC_2021
        SeedColorSpec.SPEC_2025 -> ColorSpec.SpecVersion.SPEC_2025
    }

    private fun SeedPaletteStyle.toKolorStyle(): KolorPaletteStyle = when (this) {
        SeedPaletteStyle.TonalSpot -> KolorPaletteStyle.TonalSpot
        SeedPaletteStyle.Fidelity -> KolorPaletteStyle.Fidelity
        SeedPaletteStyle.Monochrome -> KolorPaletteStyle.Monochrome
        SeedPaletteStyle.Neutral -> KolorPaletteStyle.Neutral
        SeedPaletteStyle.Vibrant -> KolorPaletteStyle.Vibrant
        SeedPaletteStyle.Expressive -> KolorPaletteStyle.Expressive
        SeedPaletteStyle.Content -> KolorPaletteStyle.Content
        SeedPaletteStyle.Rainbow -> KolorPaletteStyle.Rainbow
        SeedPaletteStyle.FruitSalad -> KolorPaletteStyle.FruitSalad
        // 其余（Standard / 12 个固定表风格）不会走到这里：create() 已分流
        else -> error("Not a dynamic seed style: $this")
    }
}

/**
 * 深色 + 纯黑 OLED 的角色压暗（上游 `darkenSeedColorScheme` 的同义移植）。
 *
 * 在**编码 sRGB** 上按通道乘系数（与 `Color.lerp` 同口径，不是线性空间），
 * 与上游逐行一致：primary/secondary 系压 0.05~0.1、surface 系压 0.5、
 * `surfaceContainerHighest` 压 0.4，背景/表面直接落纯黑。
 */
internal fun darkenSeedColorScheme(scheme: ColorScheme): ColorScheme {
    fun Color.darken(amount: Double = 0.5): Color {
        val argb = toArgb()
        fun channel(shift: Int): Int = (((argb ushr shift) and 255) * (1.0 - amount)).roundToInt()
        return Color((argb and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0))
    }
    return scheme.copy(
        primary = scheme.primary.darken(0.1),
        onPrimary = scheme.onPrimary.darken(0.1),
        primaryContainer = scheme.primaryContainer.darken(0.1),
        onPrimaryContainer = scheme.onPrimaryContainer.darken(0.1),
        inversePrimary = scheme.inversePrimary.darken(0.1),
        secondary = scheme.secondary.darken(0.05),
        onSecondary = scheme.onSecondary.darken(0.05),
        secondaryContainer = scheme.secondaryContainer.darken(0.05),
        onSecondaryContainer = scheme.onSecondaryContainer.darken(0.05),
        error = scheme.error.darken(0.05),
        background = Color.Black,
        surface = Color.Black,
        onSurface = scheme.onSurface.darken(0.15),
        onBackground = scheme.onSurface.darken(0.15),
        surfaceTint = scheme.surfaceTint.darken(),
        inverseSurface = scheme.inverseSurface.darken(),
        inverseOnSurface = scheme.inverseOnSurface.darken(),
        surfaceContainer = scheme.surfaceContainer.darken(),
        surfaceContainerHigh = scheme.surfaceContainerHigh.darken(),
        surfaceContainerHighest = scheme.surfaceContainerHighest.darken(0.4),
        surfaceVariant = scheme.surfaceContainerHighest.darken(0.4),
    )
}
