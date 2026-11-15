package com.naaammme.bbspace.core.designsystem.theme.scheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 色彩标准支持集与回落口径（上游 `PaletteSpecPolicy` 的纯逻辑部分）。 */
class SeedColorSpecTest {

    @Test
    fun onlyFourDynamicStylesSupportSpec2025() {
        val supported = SeedPaletteStyle.entries.filter { supportsSeedSpec2025(it) }.toSet()
        assertEquals(
            setOf(
                SeedPaletteStyle.TonalSpot,
                SeedPaletteStyle.Neutral,
                SeedPaletteStyle.Vibrant,
                SeedPaletteStyle.Expressive,
            ),
            supported
        )
        // 固定表风格一律不支持 2025（它们的 tone 表就是 2021 口径）。
        val fixed = SeedPaletteStyle.entries.filter { it.hasFixedTones }
        assertTrue(fixed.none { supportsSeedSpec2025(it) })
        assertFalse(supportsSeedSpec2025(SeedPaletteStyle.Standard))
    }

    @Test
    fun requestedSpecFallsBackInsteadOfFailing() {
        for (style in SeedPaletteStyle.entries) {
            for (requested in SeedColorSpec.entries) {
                val resolved = resolveSeedPaletteSpec(style, requested)
                val expected = if (supportsSeedSpec2025(style)) requested else SeedColorSpec.SPEC_2021
                assertEquals("$style requested=$requested", expected, resolved)
                if (!supportsSeedSpec2025(style)) {
                    assertEquals(
                        "不支持 2025 的风格必须回落 2021，而不是把偏好丢掉",
                        SeedColorSpec.SPEC_2021,
                        resolved
                    )
                }
            }
        }
    }

    @Test
    fun specOptionsHide2025ForUnsupportedFixedStyles() {
        assertEquals(
            listOf(SeedColorSpec.SPEC_2021),
            resolveColorSpecOptions(SeedPaletteStyle.Material).map { it.value }
        )
        assertEquals(
            listOf(SeedColorSpec.SPEC_2021, SeedColorSpec.SPEC_2025),
            resolveColorSpecOptions(SeedPaletteStyle.TonalSpot).map { it.value }
        )
        // 默认（Standard/未启用种子风格，等价上游默认 PaletteStyle=TonalSpot）两档都展示。
        assertEquals(
            listOf(SeedColorSpec.SPEC_2021, SeedColorSpec.SPEC_2025),
            resolveColorSpecOptions().map { it.value }
        )
    }

    @Test
    fun specOptionsAndSubtitlesAreDisplayReady() {
        for (option in resolveColorSpecOptions(SeedPaletteStyle.TonalSpot)) {
            assertEquals(option.value.name, option.label)
            assertTrue(option.description?.isNotBlank() == true)
        }
        for (spec in SeedColorSpec.entries) {
            assertTrue(resolveColorSpecDescription(spec).isNotBlank())
            assertTrue(
                resolveColorSpecSubtitle(spec).startsWith("当前 ${spec.name}：")
            )
        }
        assertTrue(SeedColorSpec.SPEC_2025.isModern)
        assertFalse(SeedColorSpec.SPEC_2021.isModern)
    }
}
