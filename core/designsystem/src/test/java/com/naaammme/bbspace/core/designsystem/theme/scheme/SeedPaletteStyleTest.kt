package com.naaammme.bbspace.core.designsystem.theme.scheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 风格枚举：数量、启用语义、持久化容错（上游 `SeedPaletteStyle` 的契约）。 */
class SeedPaletteStyleTest {

    @Test
    fun enumKeepsUpstreamNamesAndOrder() {
        assertTrue(SeedPaletteStyle.entries.size >= 22)
        assertEquals(SeedPaletteStyle.Standard, SeedPaletteStyle.entries.first())
        assertEquals(SeedPaletteStyle.Chroma, SeedPaletteStyle.entries.last())
        assertEquals(
            listOf(
                "Standard", "TonalSpot", "Fidelity", "Monochrome", "Neutral", "Vibrant",
                "Expressive", "Content", "Rainbow", "FruitSalad", "Material", "Material3Legacy",
                "Soft", "Vivid", "VividSurfaces", "HighContrast", "UltraContrast", "Jolly",
                "VividBackground", "OneHue", "CandyPop", "Chroma",
            ),
            SeedPaletteStyle.entries.map { it.name }
        )
        assertTrue("label 与枚举名一致（上游同义）", SeedPaletteStyle.entries.all { it.label == it.name })
    }

    @Test
    fun onlyStandardIsDisabled() {
        assertFalse(SeedPaletteStyle.Standard.enabled)
        assertTrue(SeedPaletteStyle.entries.filterNot { it == SeedPaletteStyle.Standard }.all { it.enabled })
    }

    @Test
    fun dynamicAndFixedStylesPartitionTheEnabledEntries() {
        val dynamic = SeedPaletteStyle.entries.filter { it.isDynamic }
        val fixed = SeedPaletteStyle.entries.filter { it.hasFixedTones }
        assertEquals(9, dynamic.size)
        assertEquals(12, fixed.size)
        assertEquals(
            "动态 + 固定 + Standard 必须正好覆盖全部条目（互斥且无遗漏）",
            SeedPaletteStyle.entries.size,
            dynamic.size + fixed.size + 1
        )
        assertTrue(dynamic.intersect(fixed.toSet()).isEmpty())
    }

    @Test
    fun storedValuesFallBackToStandardInsteadOfCrashing() {
        assertEquals(SeedPaletteStyle.Chroma, SeedPaletteStyle.fromStored("Chroma"))
        assertEquals(SeedPaletteStyle.Standard, SeedPaletteStyle.fromStored("UnknownStyle"))
        assertEquals(SeedPaletteStyle.Standard, SeedPaletteStyle.fromStored(null))
        assertEquals(SeedPaletteStyle.Standard, SeedPaletteStyle.fromStored(""))
    }

    @Test
    fun unknownStyleHasNoFixedTonesAndNoModernSpec() {
        assertNull(SeedTones.forStyle(SeedPaletteStyle.Standard, false))
        assertFalse(supportsSeedSpec2025(SeedPaletteStyle.Standard))
    }
}
