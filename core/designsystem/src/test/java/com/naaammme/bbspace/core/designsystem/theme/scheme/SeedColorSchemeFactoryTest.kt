package com.naaammme.bbspace.core.designsystem.theme.scheme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * `SeedColorSchemeFactory` 的 JVM 单测（引擎验收；接线见 `SeedThemeWiringTest`）。
 *
 * ## 断言口径（刻意不写"与上游逐值一致"）
 * 本仓无法证实上游的完整管线（上游还有 `SeedToneResolver`/`SeedDynamicRules`/
 * `SeedTextContrastPolicy` 三个内部实现），所以：
 * - **固定表 12 风格**：只断言"输出由本仓 `SeedTones` 表推导" —— 把 scheme 的 primary
 *   反解成 HCT tone，必须等于该风格/明暗档的 `primaryTone`（同一张表 ⇒ 同一 tone）；
 * - **dynamic 9 风格**：只断言**结构不变式**（明暗两档都非空且不同、primary tone 落在
 *   合理区间、onPrimary/primary 对比度 ≥ 4.5:1、纯黑只在深色生效）；
 * - **Standard / 未知风格**：返回 null（保守回退 = "沿用既有管线"）。
 */
class SeedColorSchemeFactoryTest {

    private val seed = Color(0xFFFB7299)

    private val fixedStyles = SeedPaletteStyle.entries.filter { it.hasFixedTones }
    private val dynamicStyles = SeedPaletteStyle.entries.filter { it.isDynamic }

    private fun create(
        style: SeedPaletteStyle,
        isDark: Boolean,
        pureBlack: Boolean = false
    ): ColorScheme = SeedColorSchemeFactory.create(
        seedColor = seed,
        isDark = isDark,
        style = style,
        pureBlack = pureBlack
    ) ?: error("style=$style isDark=$isDark 应产出 scheme")

    private fun hctTone(color: Color): Double = Hct.fromInt(color.toArgb()).tone

    private fun channelLuminance(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `standard style returns null so the caller keeps the old pipeline`() {
        assertNull(
            "Standard 不是一种种子方案：必须返回 null，让调用方沿用既有 createSeedColorScheme",
            SeedColorSchemeFactory.create(seed, isDark = false, style = SeedPaletteStyle.Standard)
        )
    }

    @Test
    fun `unknown stored value falls back to standard and therefore null`() {
        assertEquals(
            "未知/旧持久化值必须回落 Standard",
            SeedPaletteStyle.Standard,
            SeedPaletteStyle.fromStored("NoSuchStyle")
        )
        assertNull(
            "回落 Standard 后工厂返回 null（不崩、不造出奇怪配色）",
            SeedColorSchemeFactory.create(seed, isDark = true, style = SeedPaletteStyle.fromStored(null))
        )
    }

    @Test
    fun `every fixed style derives primary from its own tone table`() {
        assertEquals("固定表风格应有 12 个", 12, fixedStyles.size)
        for (style in fixedStyles) {
            for (isDark in listOf(false, true)) {
                val tones = SeedTones.forStyle(style, isDark)
                assertNotNull("$style/$isDark 应有固定表", tones)
                val scheme = create(style, isDark)
                assertEquals(
                    "$style/$isDark 的 primary 必须由表里的 primaryTone(${tones!!.primaryTone}) 推导",
                    tones.primaryTone.toDouble(),
                    hctTone(scheme.primary),
                    1.0
                )
                assertEquals(
                    "$style/$isDark 的 background 与 surface 同表（M3 口径）",
                    tones.surfaceTone.toDouble(),
                    hctTone(scheme.background),
                    1.0
                )
            }
        }
    }

    @Test
    fun `fixed styles are not all the same palette`() {
        val primaries = fixedStyles.map { create(it, isDark = false).primary }.distinct()
        assertTrue(
            "12 个固定风格不应产出同一套 primary —— 表若串了会立刻塌成一个值",
            primaries.size >= 3
        )
    }

    @Test
    fun `dynamic styles produce usable light and dark schemes`() {
        assertEquals("dynamic 风格应有 9 个", 9, dynamicStyles.size)
        for (style in dynamicStyles) {
            val light = create(style, isDark = false)
            val dark = create(style, isDark = true)
            assertTrue(
                "$style 浅色 primary 必须比深色更暗（tone 有区分）",
                hctTone(light.primary) < hctTone(dark.primary)
            )
            assertTrue(
                "$style 浅色 primary tone 应在暗端（实际 ${hctTone(light.primary)}）",
                hctTone(light.primary) in 0.0..65.0
            )
            assertTrue(
                "$style 深色 primary tone 应在亮端（实际 ${hctTone(dark.primary)}）",
                hctTone(dark.primary) in 65.0..100.0
            )
        }
    }

    @Test
    fun `dynamic onPrimary meets the text contrast baseline`() {
        for (style in dynamicStyles) {
            for (isDark in listOf(false, true)) {
                val scheme = create(style, isDark)
                val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
                assertTrue(
                    "$style/${if (isDark) "dark" else "light"} 的 onPrimary/primary 对比度" +
                        "只有 ${"%.2f".format(ratio)}:1，低于 4.5:1 正文基线",
                    ratio >= 4.5
                )
            }
        }
    }

    @Test
    fun `pure black only affects dark schemes`() {
        val style = SeedPaletteStyle.Material
        val darkPure = create(style, isDark = true, pureBlack = true)
        assertEquals(
            "深色+纯黑 ⇒ background 必须落到纯黑",
            Color.Black,
            darkPure.background
        )
        val lightPure = create(style, isDark = false, pureBlack = true)
        val lightNormal = create(style, isDark = false, pureBlack = false)
        assertEquals(
            "纯黑开关在浅色档必须完全无效",
            lightNormal.background,
            lightPure.background
        )
    }
}
