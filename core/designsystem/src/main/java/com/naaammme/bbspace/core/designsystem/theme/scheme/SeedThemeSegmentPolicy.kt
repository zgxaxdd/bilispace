package com.naaammme.bbspace.core.designsystem.theme.scheme

import com.naaammme.bbspace.core.designsystem.theme.ThemeMode

/**
 * 外观设置里「主题 / 色彩风格 / 色彩标准 / 对比度」四个分段的**选项与文案**（纯函数）。
 *
 * 上游来源：BiliPai `feature/settings/AppearanceThemeSegmentPolicy.kt`。该文件混了三类东西：
 * 1. **纯文案/选项**（本文件全部搬走）；
 * 2. app 专属枚举（AppIconStyle / AppListItemStyle / AppLanguage / Md3ColorSource）；
 * 3. MaterialKolor 库枚举（PaletteStyle / ColorSpec / Contrast）。
 *
 * B 方案（不新增依赖）下只搬 1；2、3 分别留在设置层/将来的 HCT 批次里（差异点）。
 * `AppSegmentOption` 也换成本仓的 [SeedSegmentOption] —— designsystem 不该依赖 feature 的 UI 类型。
 */
data class SeedSegmentOption<T>(
    val value: T,
    val label: String,
    val description: String? = null
)

/**
 * 主题模式三档。顺序与上游一致：**跟随系统 → 浅色 → 深色**。
 *
 * 上游枚举叫 `FOLLOW_SYSTEM`；本仓既有枚举是 [ThemeMode.SYSTEM]（值同义），这里直接消费，
 * 不另造一套 —— 设置页将来接线时不用做第二层映射。
 */
fun resolveThemeModeSegmentOptions(
    followSystemLabel: String = "跟随系统",
    lightLabel: String = "浅色",
    darkLabel: String = "深色"
): List<SeedSegmentOption<ThemeMode>> = listOf(
    SeedSegmentOption(ThemeMode.SYSTEM, followSystemLabel),
    SeedSegmentOption(ThemeMode.LIGHT, lightLabel),
    SeedSegmentOption(ThemeMode.DARK, darkLabel),
)

/**
 * 22 个种子风格的选项。
 *
 * 排序与上游一致：把 10 个"Material 兼容"风格按固定顺序放前面（Standard/TonalSpot/Neutral/
 * Vibrant/Expressive/Rainbow/FruitSalad/Monochrome/Fidelity/Content），其余按枚举声明序追加。
 * 这样老用户下拉里常选的那几个位置不漂移。
 */
fun resolveSeedPaletteStyleOptions(): List<SeedSegmentOption<SeedPaletteStyle>> {
    val material = listOf(
        SeedPaletteStyle.Standard, SeedPaletteStyle.TonalSpot, SeedPaletteStyle.Neutral,
        SeedPaletteStyle.Vibrant, SeedPaletteStyle.Expressive, SeedPaletteStyle.Rainbow,
        SeedPaletteStyle.FruitSalad, SeedPaletteStyle.Monochrome, SeedPaletteStyle.Fidelity,
        SeedPaletteStyle.Content,
    )
    return (material + SeedPaletteStyle.entries.filterNot { it in material }).map { style ->
        SeedSegmentOption(style, style.label, resolveSeedPaletteStyleDescription(style))
    }
}

/** 每个风格一行中文含义（与上游文案逐字一致）。 */
fun resolveSeedPaletteStyleDescription(style: SeedPaletteStyle): String = when (style) {
    SeedPaletteStyle.Standard -> "沿用已设置的色彩风格、标准与对比度"
    SeedPaletteStyle.TonalSpot -> "默认风格，柔和低彩度，最贴近 Material 3 观感"
    SeedPaletteStyle.Neutral -> "接近灰阶，只保留很少彩度"
    SeedPaletteStyle.Vibrant -> "提高主色彩度，副色与第三色色相按风格变化"
    SeedPaletteStyle.Expressive -> "主色色相刻意偏离种子色，配色更有表现力"
    SeedPaletteStyle.Rainbow -> "保留种子色色相，彩度固定，背景接近灰阶"
    SeedPaletteStyle.FruitSalad -> "主色与副色色相都偏离种子色，第三色保留原色相"
    SeedPaletteStyle.Monochrome -> "全灰阶，完全不带彩度"
    SeedPaletteStyle.Fidelity -> "调色板贴近种子色，第三色使用互补色"
    SeedPaletteStyle.Content -> "主副色贴近种子色，第三色使用相邻色"
    SeedPaletteStyle.Material -> "固定低彩度与标准色调，柔和的 Material 3 配色"
    SeedPaletteStyle.Material3Legacy -> "经典 Material 3，主色彩度较高，中性表面染色较少"
    SeedPaletteStyle.Soft -> "降低主色、副色与第三色彩度，整体更柔和"
    SeedPaletteStyle.Vivid -> "加深浅色主色并提高彩度，保留鲜明的种子色特征"
    SeedPaletteStyle.VividSurfaces -> "鲜明主色搭配更明显的表面染色与文字层次"
    SeedPaletteStyle.HighContrast -> "提高彩度与部分角色对比，强化文字和容器区分"
    SeedPaletteStyle.UltraContrast -> "更深的浅色主色与更亮的深色文字，进一步强化对比"
    SeedPaletteStyle.Jolly -> "主副色更鲜艳，明亮副色搭配活泼的第三色"
    SeedPaletteStyle.VividBackground -> "生动表面配色搭配更明亮的浅色背景和更深的暗色背景"
    SeedPaletteStyle.OneHue -> "主色、副色与第三色共用一个色相，以彩度和深浅区分"
    SeedPaletteStyle.CandyPop -> "明亮鲜艳的糖果配色，文字前景自动补足可读对比"
    SeedPaletteStyle.Chroma -> "主副色彩度跟随种子色，保留原色个性"
}

/** 对比度四档（顺序与上游一致：Reduced → Default → Medium → High）。 */
fun resolveColorContrastOptions(): List<SeedSegmentOption<SeedContrastLevel>> =
    listOf(
        SeedContrastLevel.Reduced,
        SeedContrastLevel.Default,
        SeedContrastLevel.Medium,
        SeedContrastLevel.High,
    ).map { contrast ->
        SeedSegmentOption(
            value = contrast,
            label = contrast.name,
            description = resolveColorContrastDescription(contrast),
        )
    }

fun resolveColorContrastDescription(contrast: SeedContrastLevel): String = when (contrast) {
    SeedContrastLevel.Reduced -> "低于默认对比，配色更柔"
    SeedContrastLevel.Default -> "Material 3 默认对比度"
    SeedContrastLevel.Medium -> "高于默认对比，文字更清晰"
    SeedContrastLevel.High -> "最高对比，接近无障碍高对比主题"
}

/** 当前对比度的显示文案（`当前 High：…`，与上游 subtitle 同格式）。 */
fun resolveColorContrastSubtitle(contrast: SeedContrastLevel): String =
    "当前 ${contrast.name}：${resolveColorContrastDescription(contrast)}"

/** 色彩标准选项：新标准只在支持它的风格上出现（上游 `resolveColorSpecOptions` 同义）。 */
fun resolveColorSpecOptions(
    seedStyle: SeedPaletteStyle = SeedPaletteStyle.Standard,
): List<SeedSegmentOption<SeedColorSpec>> = listOf(
    SeedColorSpec.SPEC_2021,
    SeedColorSpec.SPEC_2025,
).filter { it != SeedColorSpec.SPEC_2025 || seedStyle == SeedPaletteStyle.Standard || supportsSeedSpec2025(seedStyle) }
    .map { spec ->
        SeedSegmentOption(
            value = spec,
            label = spec.name,
            description = resolveColorSpecDescription(spec),
        )
    }

fun resolveColorSpecDescription(spec: SeedColorSpec): String = when (spec) {
    SeedColorSpec.SPEC_2021 -> "旧版 Material 3 配色规则，兼容传统色彩风格"
    SeedColorSpec.SPEC_2025 -> "新版 Material 3 配色规则，更新色板、容器与对比度映射"
}

fun resolveColorSpecSubtitle(spec: SeedColorSpec): String =
    "当前 ${spec.name}：${resolveColorSpecDescription(spec)}"
