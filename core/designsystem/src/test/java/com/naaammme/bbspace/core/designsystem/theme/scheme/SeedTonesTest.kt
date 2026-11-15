package com.naaammme.bbspace.core.designsystem.theme.scheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定风格 tone 表（上游 `SeedTones.kt` 的可移植部分）。
 *
 * 这些值不是"随便定"的：它们决定固定风格（Material/HighContrast/…）每个角色的明度，
 * 任何一格抄错都会让某个主题的某个角色变色，而只看"能编译"完全发现不了 —— 所以逐值钉。
 */
class SeedTonesTest {

    @Test
    fun lightBaseTableMatchesUpstream() {
        val light = SeedTones.forStyle(SeedPaletteStyle.Material, isDark = false)!!
        // 基础表（Material 只覆盖 chroma，不覆盖 tone）
        assertEquals(40, light.primaryTone)
        assertEquals(100, light.onPrimaryTone)
        assertEquals(90, light.primaryContainerTone)
        assertEquals(10, light.onPrimaryContainerTone)
        assertEquals(98, light.surfaceTone)
        assertEquals(87, light.surfaceDimTone)
        assertEquals(98, light.surfaceBrightTone)
        assertEquals(100, light.surfaceContainerLowestTone)
        assertEquals(96, light.surfaceContainerLowTone)
        assertEquals(94, light.surfaceContainerTone)
        assertEquals(92, light.surfaceContainerHighTone)
        assertEquals(90, light.surfaceContainerHighestTone)
        assertEquals(10, light.onSurfaceTone)
        assertEquals(30, light.onSurfaceVariantTone)
        assertEquals(20, light.inverseSurfaceTone)
        assertEquals(95, light.onInverseSurfaceTone)
        assertEquals(80, light.inversePrimaryTone)
        assertEquals(40, light.surfaceTintTone)
        assertEquals(50, light.outlineTone)
        assertEquals(80, light.outlineVariantTone)
        assertEquals(0, light.shadowTone)
        assertEquals(0, light.scrimTone)
    }

    @Test
    fun darkBaseTableMatchesUpstream() {
        val dark = SeedTones.forStyle(SeedPaletteStyle.Material, isDark = true)!!
        assertEquals(80, dark.primaryTone)
        assertEquals(20, dark.onPrimaryTone)
        assertEquals(30, dark.primaryContainerTone)
        assertEquals(90, dark.onPrimaryContainerTone)
        assertEquals(6, dark.surfaceTone)
        assertEquals(6, dark.surfaceDimTone)
        assertEquals(24, dark.surfaceBrightTone)
        assertEquals(4, dark.surfaceContainerLowestTone)
        assertEquals(10, dark.surfaceContainerLowTone)
        assertEquals(12, dark.surfaceContainerTone)
        assertEquals(17, dark.surfaceContainerHighTone)
        assertEquals(22, dark.surfaceContainerHighestTone)
        assertEquals(90, dark.onSurfaceTone)
        assertEquals(80, dark.onSurfaceVariantTone)
        assertEquals(90, dark.inverseSurfaceTone)
        assertEquals(20, dark.onInverseSurfaceTone)
        assertEquals(40, dark.inversePrimaryTone)
        assertEquals(80, dark.surfaceTintTone)
        assertEquals(60, dark.outlineTone)
        assertEquals(30, dark.outlineVariantTone)
    }

    @Test
    fun materialStyleUsesFixedLowChromaAndHctHue() {
        for (dark in listOf(false, true)) {
            val tones = SeedTones.forStyle(SeedPaletteStyle.Material, dark)!!
            assertEquals(36.0, tones.primaryChroma!!, 1e-9)
            assertEquals(36.0, tones.primaryMinChroma!!, 1e-9)
            assertEquals(16.0, tones.secondaryChroma!!, 1e-9)
            assertEquals(24.0, tones.tertiaryChroma!!, 1e-9)
            // 上游 Material 风格刻意关掉 CAM16（用 HCT 色相/彩度）——这是它与其余风格的差异点。
            assertTrue("Material 风格必须 useCam16=false", !tones.useCam16)
        }
    }

    @Test
    fun material3LegacyKeepsItsLegacySurfaceTones() {
        val light = SeedTones.forStyle(SeedPaletteStyle.Material3Legacy, isDark = false)!!
        val dark = SeedTones.forStyle(SeedPaletteStyle.Material3Legacy, isDark = true)!!
        assertEquals(99, light.surfaceTone)
        assertEquals(10, dark.surfaceTone)
        assertEquals(48.0, light.primaryChroma!!, 1e-9)
        assertEquals(4.0, light.neutralChroma!!, 1e-9)
    }

    @Test
    fun highContrastAndUltraContrastPushTowardsExtremes() {
        val hcDark = SeedTones.forStyle(SeedPaletteStyle.HighContrast, isDark = true)!!
        assertEquals(4, hcDark.surfaceTone)
        assertEquals(96, hcDark.onSurfaceTone)
        assertEquals(0, hcDark.surfaceContainerLowestTone)
        assertEquals(65.0, hcDark.primaryMinChroma!!, 1e-9)

        val ucLight = SeedTones.forStyle(SeedPaletteStyle.UltraContrast, isDark = false)!!
        assertEquals(100, ucLight.surfaceTone)
        assertEquals(0, ucLight.onSurfaceTone)
        assertEquals(6, ucLight.onSurfaceVariantTone)

        val ucDark = SeedTones.forStyle(SeedPaletteStyle.UltraContrast, isDark = true)!!
        assertEquals(2, ucDark.surfaceTone)
        assertEquals(99, ucDark.onSurfaceTone)
    }

    @Test
    fun oneHueKeepsTertiaryOnTheSameHue() {
        for (dark in listOf(false, true)) {
            val tones = SeedTones.forStyle(SeedPaletteStyle.OneHue, dark)!!
            assertEquals(0.0, tones.tertiaryHueRotation!!, 1e-9)
            assertEquals(26.0, tones.secondaryChroma!!, 1e-9)
            assertEquals(36.0, tones.tertiaryChroma!!, 1e-9)
        }
    }

    @Test
    fun fixedToneSetsAreExactlyTheTwelveFixedStyles() {
        val withTones = SeedPaletteStyle.entries.filter { SeedTones.forStyle(it, false) != null }
        val expected = SeedPaletteStyle.entries.filter { it.hasFixedTones }
        assertEquals(
            "有固定 tone 表的风格集合必须与 hasFixedTones 完全一致（12 个固定风格）",
            expected, withTones
        )
        assertEquals(12, withTones.size)
    }

    @Test
    fun standardAndDynamicStylesHaveNoFixedTable() {
        for (style in SeedPaletteStyle.entries) {
            val shouldBeNull = style == SeedPaletteStyle.Standard || style.isDynamic
            for (dark in listOf(false, true)) {
                val tones = SeedTones.forStyle(style, dark)
                if (shouldBeNull) {
                    assertNull("$style 不该有固定 tone 表（走 HCT scheme）", tones)
                } else {
                    assertNotNull("$style 应有固定 tone 表", tones)
                }
            }
        }
    }

    @Test
    fun everyFixedStyleUsesTheSameAccentChromaConstraintsInLightAndDark() {
        // ⚠️ 只钉"主色/副色/第三色"与 useCam16：它们在同风格的明暗分支里逐字相同。
        // **中性色 chroma 刻意可以不同**（上游 Chroma 风格：浅色 neutral=2.0 / 深色 3.0，
        // 为了在深色下让表面染色稍微可辨）—— 把它也钉成"两边相等"会与上游冲突。
        for (style in SeedPaletteStyle.entries.filter { it.hasFixedTones }) {
            val light = SeedTones.forStyle(style, false)!!
            val dark = SeedTones.forStyle(style, true)!!
            assertEquals("$style primaryChroma", light.primaryChroma, dark.primaryChroma)
            assertEquals("$style primaryMinChroma", light.primaryMinChroma, dark.primaryMinChroma)
            assertEquals("$style secondaryChroma", light.secondaryChroma, dark.secondaryChroma)
            assertEquals("$style secondaryMinChroma", light.secondaryMinChroma, dark.secondaryMinChroma)
            assertEquals("$style tertiaryChroma", light.tertiaryChroma, dark.tertiaryChroma)
            assertEquals("$style tertiaryMinChroma", light.tertiaryMinChroma, dark.tertiaryMinChroma)
            assertEquals("$style tertiaryHueRotation", light.tertiaryHueRotation, dark.tertiaryHueRotation)
            assertEquals("$style useCam16", light.useCam16, dark.useCam16)
        }
        // 中性色 chroma 的上游取值单独钉两处代表（浅/深不同是设计，不是漂移）。
        assertEquals(2.0, SeedTones.forStyle(SeedPaletteStyle.Chroma, false)!!.neutralChroma!!, 1e-9)
        assertEquals(3.0, SeedTones.forStyle(SeedPaletteStyle.Chroma, true)!!.neutralChroma!!, 1e-9)
        assertEquals(6.0, SeedTones.forStyle(SeedPaletteStyle.Material, false)!!.neutralChroma!!, 1e-9)
    }

    @Test
    fun extremeSeedsAreIrrelevantToTheTableButTonesStayInRange() {
        // tone 表的契约：所有 tone 必须落在 MCU 合法区间 0..100（含两端）——
        // 这保证将来接 HCT 时不会因为越界 tone 产出非法颜色（Hct.from 会 clamp，但那是兜底）。
        for (style in SeedPaletteStyle.entries.filter { it.hasFixedTones }) {
            for (dark in listOf(false, true)) {
                val tones = SeedTones.forStyle(style, dark)!!
                val allTones = listOf(
                    tones.primaryTone, tones.onPrimaryTone, tones.primaryContainerTone,
                    tones.onPrimaryContainerTone, tones.primaryFixedTone, tones.primaryFixedDimTone,
                    tones.onPrimaryFixedTone, tones.onPrimaryFixedVariantTone,
                    tones.secondaryTone, tones.onSecondaryTone, tones.secondaryContainerTone,
                    tones.onSecondaryContainerTone, tones.secondaryFixedTone, tones.secondaryFixedDimTone,
                    tones.onSecondaryFixedTone, tones.onSecondaryFixedVariantTone,
                    tones.tertiaryTone, tones.onTertiaryTone, tones.tertiaryContainerTone,
                    tones.onTertiaryContainerTone, tones.tertiaryFixedTone, tones.tertiaryFixedDimTone,
                    tones.onTertiaryFixedTone, tones.onTertiaryFixedVariantTone,
                    tones.errorTone, tones.onErrorTone, tones.errorContainerTone,
                    tones.onErrorContainerTone, tones.surfaceTone, tones.surfaceDimTone,
                    tones.surfaceBrightTone, tones.surfaceContainerLowestTone,
                    tones.surfaceContainerLowTone, tones.surfaceContainerTone,
                    tones.surfaceContainerHighTone, tones.surfaceContainerHighestTone,
                    tones.onSurfaceTone, tones.onSurfaceVariantTone, tones.inverseSurfaceTone,
                    tones.onInverseSurfaceTone, tones.inversePrimaryTone, tones.surfaceTintTone,
                    tones.outlineTone, tones.outlineVariantTone, tones.shadowTone, tones.scrimTone,
                )
                assertTrue("$style dark=$dark 有 tone 越界", allTones.all { it in 0..100 })
            }
        }
    }
}
