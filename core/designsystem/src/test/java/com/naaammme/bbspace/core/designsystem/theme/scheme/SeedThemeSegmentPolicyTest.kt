package com.naaammme.bbspace.core.designsystem.theme.scheme

import com.naaammme.bbspace.core.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置四个分段的选项/文案。
 *
 * 改编自上游 `AppearanceThemeSegmentPolicyTest` 的**可移植子集**：主题模式、色彩风格、
 * 色彩标准、对比度。上游里依赖 app 专属枚举的段（AppIconStyle / AppListItemStyle /
 * AppLanguage / Md3ColorSource）与依赖 MaterialKolor 枚举的段不在 B 范围内。
 */
class SeedThemeSegmentPolicyTest {

    @Test
    fun themeModeOptionsKeepExpectedOrderAndUseProvidedLabels() {
        val options = resolveThemeModeSegmentOptions(
            followSystemLabel = "Follow System",
            lightLabel = "Light",
            darkLabel = "Dark"
        )
        assertEquals(3, options.size)
        assertEquals(ThemeMode.SYSTEM, options[0].value)
        assertEquals("Follow System", options[0].label)
        assertEquals(ThemeMode.LIGHT, options[1].value)
        assertEquals("Light", options[1].label)
        assertEquals(ThemeMode.DARK, options[2].value)
        assertEquals("Dark", options[2].label)
    }

    @Test
    fun themeModeOptionsDefaultToChineseLabels() {
        val options = resolveThemeModeSegmentOptions()
        assertEquals(listOf("跟随系统", "浅色", "深色"), options.map { it.label })
    }

    @Test
    fun seedPaletteOptionsExposeAllStylesWithMaterialFirstAndStableOrder() {
        val options = resolveSeedPaletteStyleOptions()
        assertEquals(SeedPaletteStyle.entries.size, options.size)
        assertEquals(
            listOf(
                SeedPaletteStyle.Standard, SeedPaletteStyle.TonalSpot, SeedPaletteStyle.Neutral,
                SeedPaletteStyle.Vibrant, SeedPaletteStyle.Expressive, SeedPaletteStyle.Rainbow,
                SeedPaletteStyle.FruitSalad, SeedPaletteStyle.Monochrome,
                SeedPaletteStyle.Fidelity, SeedPaletteStyle.Content,
            ),
            options.take(10).map { it.value }
        )
        assertEquals(
            "选项值必须覆盖全部风格且不重复",
            SeedPaletteStyle.entries.toSet(),
            options.map { it.value }.toSet()
        )
        assertEquals(options.size, options.map { it.value }.size)
    }

    @Test
    fun seedPaletteOptionsHaveLabelAndDescription() {
        for (option in resolveSeedPaletteStyleOptions()) {
            assertEquals(option.value.label, option.label)
            assertTrue("${option.value} 缺少说明", option.description?.isNotBlank() == true)
        }
        // 抽查三条与上游逐字一致的文案。
        assertTrue(
            resolveSeedPaletteStyleDescription(SeedPaletteStyle.Standard).contains("沿用")
        )
        assertTrue(
            resolveSeedPaletteStyleDescription(SeedPaletteStyle.TonalSpot).contains("Material 3")
        )
        assertTrue(
            resolveSeedPaletteStyleDescription(SeedPaletteStyle.Chroma).contains("彩度")
        )
    }

    @Test
    fun contrastOptionsCoverReducedDefaultMediumHighInOrder() {
        val options = resolveColorContrastOptions()
        assertEquals(
            listOf(
                SeedContrastLevel.Reduced,
                SeedContrastLevel.Default,
                SeedContrastLevel.Medium,
                SeedContrastLevel.High,
            ),
            options.map { it.value }
        )
        assertTrue(options.all { it.label == it.value.name })
        assertTrue(options.all { it.description?.isNotBlank() == true })
        assertTrue(resolveColorContrastSubtitle(SeedContrastLevel.High).startsWith("当前 High："))
    }

    @Test
    fun specSubtitleSpellsOutNameAndMeaning() {
        val subtitle = resolveColorSpecSubtitle(SeedColorSpec.SPEC_2025)
        assertTrue(subtitle.startsWith("当前 SPEC_2025："))
        assertTrue(subtitle.contains(resolveColorSpecDescription(SeedColorSpec.SPEC_2025)))
    }
}
