/*
 * Adapted from flex_seed_scheme 5.0.1, Copyright 2022-2025 Mike Rydstrom.
 * BSD-3-Clause.
 *
 * 上游来源：BiliPai `origin/main:app/src/main/java/com/android/purebilibili/core/theme/SeedTones.kt`
 * （406 行 → 本文件；tone/chroma 表逐值搬运）。
 */
package com.naaammme.bbspace.core.designsystem.theme.scheme

/**
 * 固定色彩风格的 **tone / chroma 参数表**（纯数据，不含任何颜色计算）。
 *
 * ## 这是什么
 * Material 3 的一套配色 = 若干"角色 → tone（0..100 明度）"映射。动态风格（TonalSpot
 * 等）的 tone 由 Material Color Utilities 的约束求解器按对比度算出；而**固定风格**
 * （Material / Material3Legacy / Soft / Vivid / … / Chroma）直接用一张写死的表。
 * 本文件就是后者 —— 它不 import 任何 HCT/CAM16，所以可以在无新依赖的前提下单独单测。
 *
 * ## 与上游的差异
 * 1. 上游 `forStyle(style, isDark)` 对 dynamic/Standard 调 `error(...)`（它们走 HCT scheme，
 *    本就不该问固定表）；本仓改返回 **null** —— 纯查询函数用可空返回表达"没有这张表"，
 *    调用方不必用异常控制流，单测也能直接断言。
 * 2. 字段与默认值、light/dark 两套基础表、12 个固定风格的覆盖值**逐值一致**（上游
 *    SeedTones.kt:1-434），改值时请对照上游 diff。
 *
 * ## 为什么 Dynamic 风格没有表
 * 动态风格的 tone 依赖种子色色相与对比度档（`SeedToneResolver` + MCU），固定表无法表达。
 * 本仓若要真正出色，还需要 HCT（见 `docs` 报告里的接线方案），本文件只保证"表"这一半。
 */
internal data class SeedTones(
    val primaryTone: Int = 40,
    val onPrimaryTone: Int = 100,
    val primaryContainerTone: Int = 90,
    val onPrimaryContainerTone: Int = 10,
    val primaryFixedTone: Int = 90,
    val primaryFixedDimTone: Int = 80,
    val onPrimaryFixedTone: Int = 10,
    val onPrimaryFixedVariantTone: Int = 30,
    val secondaryTone: Int = 40,
    val onSecondaryTone: Int = 100,
    val secondaryContainerTone: Int = 90,
    val onSecondaryContainerTone: Int = 10,
    val secondaryFixedTone: Int = 90,
    val secondaryFixedDimTone: Int = 80,
    val onSecondaryFixedTone: Int = 10,
    val onSecondaryFixedVariantTone: Int = 30,
    val tertiaryTone: Int = 40,
    val onTertiaryTone: Int = 100,
    val tertiaryContainerTone: Int = 90,
    val onTertiaryContainerTone: Int = 10,
    val tertiaryFixedTone: Int = 90,
    val tertiaryFixedDimTone: Int = 80,
    val onTertiaryFixedTone: Int = 10,
    val onTertiaryFixedVariantTone: Int = 30,
    val errorTone: Int = 40,
    val onErrorTone: Int = 100,
    val errorContainerTone: Int = 90,
    val onErrorContainerTone: Int = 10,
    val surfaceTone: Int = 98,
    val surfaceDimTone: Int = 87,
    val surfaceBrightTone: Int = 98,
    val surfaceContainerLowestTone: Int = 100,
    val surfaceContainerLowTone: Int = 96,
    val surfaceContainerTone: Int = 94,
    val surfaceContainerHighTone: Int = 92,
    val surfaceContainerHighestTone: Int = 90,
    val onSurfaceTone: Int = 10,
    val onSurfaceVariantTone: Int = 30,
    val inverseSurfaceTone: Int = 20,
    val onInverseSurfaceTone: Int = 95,
    val inversePrimaryTone: Int = 80,
    val surfaceTintTone: Int = 40,
    val outlineTone: Int = 50,
    val outlineVariantTone: Int = 80,
    val shadowTone: Int = 0,
    val scrimTone: Int = 0,
    val primaryChroma: Double? = null,
    val primaryMinChroma: Double? = null,
    val secondaryChroma: Double? = null,
    val secondaryMinChroma: Double? = null,
    val tertiaryChroma: Double? = null,
    val tertiaryMinChroma: Double? = null,
    val tertiaryHueRotation: Double? = null,
    val errorChroma: Double? = null,
    val errorMinChroma: Double? = null,
    val neutralChroma: Double? = 6.0,
    val neutralMinChroma: Double? = null,
    val neutralVariantChroma: Double? = 8.0,
    val neutralVariantMinChroma: Double? = null,
    val useCam16: Boolean = true,
) {
    companion object {
        private val light = SeedTones()

        private val dark = SeedTones(
            primaryTone = 80,
            onPrimaryTone = 20,
            primaryContainerTone = 30,
            onPrimaryContainerTone = 90,
            secondaryTone = 80,
            onSecondaryTone = 20,
            secondaryContainerTone = 30,
            onSecondaryContainerTone = 90,
            tertiaryTone = 80,
            onTertiaryTone = 20,
            tertiaryContainerTone = 30,
            onTertiaryContainerTone = 90,
            errorTone = 80,
            onErrorTone = 20,
            errorContainerTone = 30,
            onErrorContainerTone = 90,
            surfaceTone = 6,
            surfaceDimTone = 6,
            surfaceBrightTone = 24,
            surfaceContainerLowestTone = 4,
            surfaceContainerLowTone = 10,
            surfaceContainerTone = 12,
            surfaceContainerHighTone = 17,
            surfaceContainerHighestTone = 22,
            onSurfaceTone = 90,
            onSurfaceVariantTone = 80,
            inverseSurfaceTone = 90,
            onInverseSurfaceTone = 20,
            inversePrimaryTone = 40,
            surfaceTintTone = 80,
            outlineTone = 60,
            outlineVariantTone = 30,
        )

        /**
         * 固定风格的 tone 表：`null` = 该风格没有固定表（Standard / dynamic 风格走 HCT）。
         *
         * 上游对这两种情况调 `error()`；本仓返回 null（见类 KDoc 差异点 1）。
         */
        fun forStyle(style: SeedPaletteStyle, isDark: Boolean): SeedTones? {
            val base = if (isDark) dark else light
            return when (style) {
                SeedPaletteStyle.Material -> base.copy(
                    primaryChroma = 36.0,
                    primaryMinChroma = 36.0,
                    secondaryChroma = 16.0,
                    tertiaryChroma = 24.0,
                    useCam16 = false,
                )

                SeedPaletteStyle.Material3Legacy -> if (isDark) base.copy(
                    surfaceTone = 10,
                    primaryChroma = 48.0,
                    primaryMinChroma = 48.0,
                    secondaryChroma = 16.0,
                    tertiaryChroma = 24.0,
                    neutralChroma = 4.0,
                ) else base.copy(
                    surfaceTone = 99,
                    primaryChroma = 48.0,
                    primaryMinChroma = 48.0,
                    secondaryChroma = 16.0,
                    tertiaryChroma = 24.0,
                    neutralChroma = 4.0,
                )

                SeedPaletteStyle.Soft -> base.copy(
                    primaryChroma = 30.0,
                    primaryMinChroma = 0.0,
                    secondaryChroma = 14.0,
                    tertiaryChroma = 20.0,
                )

                SeedPaletteStyle.Vivid -> if (isDark) base.copy(
                    onPrimaryTone = 10,
                    primaryContainerTone = 20,
                    primaryMinChroma = 50.0,
                ) else base.copy(
                    primaryTone = 30,
                    surfaceTintTone = 30,
                    surfaceTone = 98,
                    primaryMinChroma = 50.0,
                )

                SeedPaletteStyle.VividSurfaces -> if (isDark) base.copy(
                    onPrimaryTone = 10,
                    onSecondaryTone = 10,
                    onTertiaryTone = 10,
                    primaryContainerTone = 20,
                    onSurfaceVariantTone = 95,
                    inverseSurfaceTone = 95,
                    primaryMinChroma = 50.0,
                    neutralChroma = 5.0,
                    neutralVariantChroma = 10.0,
                ) else base.copy(
                    primaryTone = 30,
                    onPrimaryTone = 98,
                    onSecondaryTone = 98,
                    onTertiaryTone = 98,
                    onErrorTone = 98,
                    onSurfaceVariantTone = 20,
                    inverseSurfaceTone = 30,
                    surfaceTintTone = 30,
                    primaryMinChroma = 50.0,
                    neutralChroma = 5.0,
                    neutralVariantChroma = 10.0,
                )

                SeedPaletteStyle.HighContrast -> if (isDark) base.copy(
                    onPrimaryTone = 10,
                    onSecondaryTone = 10,
                    onTertiaryTone = 10,
                    onErrorTone = 10,
                    primaryContainerTone = 20,
                    secondaryContainerTone = 20,
                    tertiaryContainerTone = 20,
                    errorContainerTone = 20,
                    onErrorContainerTone = 90,
                    surfaceTone = 4,
                    onSurfaceTone = 96,
                    surfaceContainerLowestTone = 0,
                    surfaceContainerLowTone = 6,
                    primaryMinChroma = 65.0,
                    secondaryMinChroma = 55.0,
                    tertiaryMinChroma = 55.0,
                ) else base.copy(
                    primaryTone = 30,
                    tertiaryTone = 30,
                    tertiaryContainerTone = 95,
                    errorContainerTone = 95,
                    surfaceTintTone = 30,
                    surfaceTone = 99,
                    primaryMinChroma = 65.0,
                    secondaryMinChroma = 55.0,
                    tertiaryMinChroma = 55.0,
                )

                SeedPaletteStyle.UltraContrast -> if (isDark) base.copy(
                    primaryTone = 90,
                    onPrimaryTone = 2,
                    onPrimaryContainerTone = 98,
                    secondaryTone = 95,
                    onSecondaryTone = 2,
                    onSecondaryContainerTone = 98,
                    tertiaryTone = 95,
                    onTertiaryTone = 2,
                    onTertiaryContainerTone = 98,
                    onErrorTone = 2,
                    onErrorContainerTone = 98,
                    surfaceTone = 2,
                    surfaceContainerLowestTone = 0,
                    surfaceContainerLowTone = 6,
                    onSurfaceTone = 99,
                    onSurfaceVariantTone = 95,
                    onInverseSurfaceTone = 10,
                    outlineTone = 80,
                    outlineVariantTone = 50,
                    surfaceTintTone = 95,
                    primaryMinChroma = 60.0,
                    secondaryMinChroma = 70.0,
                    tertiaryMinChroma = 65.0,
                    neutralChroma = 3.0,
                    neutralVariantChroma = 6.0,
                ) else base.copy(
                    primaryTone = 20,
                    onPrimaryContainerTone = 5,
                    onSecondaryContainerTone = 5,
                    tertiaryTone = 30,
                    tertiaryContainerTone = 95,
                    onTertiaryContainerTone = 5,
                    errorContainerTone = 95,
                    onErrorContainerTone = 5,
                    surfaceTone = 100,
                    surfaceContainerLowTone = 98,
                    surfaceContainerTone = 96,
                    onSurfaceTone = 0,
                    onSurfaceVariantTone = 6,
                    onInverseSurfaceTone = 99,
                    inversePrimaryTone = 90,
                    outlineTone = 40,
                    outlineVariantTone = 70,
                    surfaceTintTone = 30,
                    primaryMinChroma = 60.0,
                    secondaryMinChroma = 70.0,
                    tertiaryMinChroma = 65.0,
                    neutralChroma = 3.0,
                    neutralVariantChroma = 6.0,
                )

                SeedPaletteStyle.Jolly -> if (isDark) base.copy(
                    onPrimaryTone = 10,
                    secondaryTone = 90,
                    onSecondaryTone = 10,
                    secondaryContainerTone = 20,
                    onTertiaryTone = 10,
                    onErrorTone = 10,
                    tertiaryChroma = 40.0,
                    primaryMinChroma = 55.0,
                    secondaryMinChroma = 40.0,
                    neutralChroma = 6.0,
                    neutralVariantChroma = 10.0,
                ) else base.copy(
                    primaryTone = 30,
                    onPrimaryTone = 99,
                    secondaryTone = 50,
                    onSecondaryTone = 99,
                    secondaryContainerTone = 95,
                    onTertiaryTone = 99,
                    onErrorTone = 99,
                    surfaceTintTone = 30,
                    tertiaryChroma = 40.0,
                    primaryMinChroma = 55.0,
                    secondaryMinChroma = 40.0,
                    neutralChroma = 6.0,
                    neutralVariantChroma = 10.0,
                )

                SeedPaletteStyle.VividBackground -> if (isDark) base.copy(
                    onPrimaryTone = 10,
                    onSecondaryTone = 10,
                    onTertiaryTone = 10,
                    primaryContainerTone = 20,
                    surfaceTone = 5,
                    onSurfaceVariantTone = 95,
                    inverseSurfaceTone = 95,
                    primaryMinChroma = 50.0,
                    neutralChroma = 5.0,
                    neutralVariantChroma = 10.0,
                ) else base.copy(
                    primaryTone = 30,
                    onPrimaryTone = 98,
                    onSecondaryTone = 98,
                    onTertiaryTone = 98,
                    onErrorTone = 98,
                    surfaceTone = 100,
                    onSurfaceVariantTone = 20,
                    inverseSurfaceTone = 30,
                    surfaceTintTone = 30,
                    primaryMinChroma = 50.0,
                    neutralChroma = 5.0,
                    neutralVariantChroma = 10.0,
                )

                SeedPaletteStyle.OneHue -> if (isDark) base.copy(
                    tertiaryTone = 90,
                    tertiaryContainerTone = 40,
                    onTertiaryContainerTone = 95,
                    primaryMinChroma = 55.0,
                    secondaryChroma = 26.0,
                    tertiaryChroma = 36.0,
                    tertiaryHueRotation = 0.0,
                ) else base.copy(
                    secondaryContainerTone = 95,
                    tertiaryTone = 30,
                    tertiaryContainerTone = 80,
                    primaryMinChroma = 55.0,
                    secondaryChroma = 26.0,
                    tertiaryChroma = 36.0,
                    tertiaryHueRotation = 0.0,
                )

                SeedPaletteStyle.CandyPop -> if (isDark) base.copy(
                    primaryTone = 80,
                    onPrimaryTone = 12,
                    primaryContainerTone = 40,
                    onPrimaryContainerTone = 97,
                    secondaryTone = 70,
                    onSecondaryTone = 4,
                    secondaryContainerTone = 50,
                    onSecondaryContainerTone = 96,
                    tertiaryTone = 87,
                    onTertiaryTone = 5,
                    onTertiaryContainerTone = 92,
                    onErrorTone = 6,
                    onErrorContainerTone = 95,
                    surfaceTone = 6,
                    onSurfaceTone = 95,
                    onSurfaceVariantTone = 90,
                    onInverseSurfaceTone = 10,
                    outlineTone = 60,
                    outlineVariantTone = 40,
                    surfaceTintTone = 95,
                    primaryMinChroma = 60.0,
                    secondaryMinChroma = 44.0,
                    tertiaryMinChroma = 50.0,
                    neutralChroma = 2.0,
                    neutralVariantChroma = 4.0,
                ) else base.copy(
                    primaryTone = 40,
                    primaryContainerTone = 80,
                    onPrimaryContainerTone = 4,
                    secondaryTone = 60,
                    secondaryContainerTone = 92,
                    onSecondaryContainerTone = 10,
                    tertiaryTone = 50,
                    tertiaryContainerTone = 95,
                    onTertiaryContainerTone = 6,
                    surfaceTone = 100,
                    onSurfaceTone = 6,
                    onSurfaceVariantTone = 10,
                    onInverseSurfaceTone = 98,
                    inversePrimaryTone = 90,
                    outlineTone = 30,
                    outlineVariantTone = 70,
                    surfaceTintTone = 30,
                    primaryMinChroma = 60.0,
                    secondaryMinChroma = 44.0,
                    tertiaryMinChroma = 50.0,
                    neutralChroma = 2.0,
                    neutralVariantChroma = 4.0,
                )

                SeedPaletteStyle.Chroma -> if (isDark) base.copy(
                    primaryTone = 80,
                    onPrimaryTone = 12,
                    primaryContainerTone = 40,
                    onPrimaryContainerTone = 97,
                    secondaryTone = 70,
                    onSecondaryTone = 4,
                    secondaryContainerTone = 50,
                    onSecondaryContainerTone = 96,
                    tertiaryTone = 87,
                    onTertiaryTone = 5,
                    onTertiaryContainerTone = 92,
                    onErrorTone = 6,
                    onErrorContainerTone = 95,
                    surfaceTone = 4,
                    surfaceContainerLowestTone = 2,
                    surfaceContainerLowTone = 6,
                    onSurfaceTone = 95,
                    onSurfaceVariantTone = 90,
                    onInverseSurfaceTone = 10,
                    outlineTone = 60,
                    outlineVariantTone = 40,
                    surfaceTintTone = 95,
                    primaryMinChroma = 0.0,
                    secondaryMinChroma = 0.0,
                    tertiaryMinChroma = 0.0,
                    neutralChroma = 3.0,
                    neutralVariantChroma = 6.0,
                ) else base.copy(
                    primaryTone = 40,
                    primaryContainerTone = 80,
                    onPrimaryContainerTone = 4,
                    secondaryTone = 50,
                    secondaryContainerTone = 92,
                    onSecondaryContainerTone = 10,
                    tertiaryTone = 50,
                    tertiaryContainerTone = 95,
                    onTertiaryContainerTone = 6,
                    surfaceTone = 99,
                    onSurfaceTone = 4,
                    onSurfaceVariantTone = 10,
                    onInverseSurfaceTone = 98,
                    inversePrimaryTone = 90,
                    outlineTone = 30,
                    outlineVariantTone = 70,
                    surfaceTintTone = 30,
                    primaryMinChroma = 0.0,
                    secondaryMinChroma = 0.0,
                    tertiaryMinChroma = 0.0,
                    neutralChroma = 2.0,
                    neutralVariantChroma = 4.0,
                )

                else -> null
            }
        }
    }
}
