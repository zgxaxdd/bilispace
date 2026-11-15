package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedColorSpec
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedColorSchemeFactory
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedContrastLevel
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedPaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 配色接线的**默认零变化**守卫（纯函数层，无 Compose/Context）。
 *
 * 守卫的逻辑链：
 * 1. `SeedColorSchemeFactory.create(Standard)` **必须返回 null** —— 这是 `Theme.kt`
 *    回落旧实现的**结构性前提**；
 * 2. [resolveMaterialSeedScheme] 在 Standard 下返回的 scheme，必须与
 *    [createSeedColorScheme] **逐槽相等**（本文件逐字段对照）；
 * 3. Standard 下新增的三个维度（spec / contrast / pureBlack）**不得影响产出**
 *    —— 保证"老用户什么都没设置时，观感与接线前逐值一致"；
 * 4. 非 Standard 风格必须与旧实现**不同**（否则接线是空转，风格设置没有效果）。
 *
 * 说明：MONOCHROME / 动态取色 / iOS 三条路径根本不进 [resolveMaterialSeedScheme]，
 * 它们由 `BiliTheme` 里各自的分支处理，本文件不覆盖（那三条路径代码未改）。
 */
class SeedThemeWiringTest {

    private val seeds = listOf(
        Color(0xFFFB7299), // 默认哔哩粉
        Color(0xFF00A1D6), // 哔哩蓝
        Color(0xFF4CAF50) // 绿色
    )

    /** 逐槽比较并给出可读的失败信息（ColorScheme 不是 data class）。 */
    private fun assertSchemeEquals(expected: ColorScheme, actual: ColorScheme, what: String) {
        fun check(slot: String, e: Color, a: Color) =
            assertEquals("$what 的 $slot 必须逐值一致", e, a)

        check("primary", expected.primary, actual.primary)
        check("onPrimary", expected.onPrimary, actual.onPrimary)
        check("primaryContainer", expected.primaryContainer, actual.primaryContainer)
        check("onPrimaryContainer", expected.onPrimaryContainer, actual.onPrimaryContainer)
        check("inversePrimary", expected.inversePrimary, actual.inversePrimary)
        check("secondary", expected.secondary, actual.secondary)
        check("onSecondary", expected.onSecondary, actual.onSecondary)
        check("secondaryContainer", expected.secondaryContainer, actual.secondaryContainer)
        check("onSecondaryContainer", expected.onSecondaryContainer, actual.onSecondaryContainer)
        check("tertiary", expected.tertiary, actual.tertiary)
        check("onTertiary", expected.onTertiary, actual.onTertiary)
        check("tertiaryContainer", expected.tertiaryContainer, actual.tertiaryContainer)
        check("onTertiaryContainer", expected.onTertiaryContainer, actual.onTertiaryContainer)
        check("background", expected.background, actual.background)
        check("onBackground", expected.onBackground, actual.onBackground)
        check("surface", expected.surface, actual.surface)
        check("onSurface", expected.onSurface, actual.onSurface)
        check("surfaceVariant", expected.surfaceVariant, actual.surfaceVariant)
        check("onSurfaceVariant", expected.onSurfaceVariant, actual.onSurfaceVariant)
        check("surfaceTint", expected.surfaceTint, actual.surfaceTint)
        check("inverseSurface", expected.inverseSurface, actual.inverseSurface)
        check("inverseOnSurface", expected.inverseOnSurface, actual.inverseOnSurface)
        check("outline", expected.outline, actual.outline)
        check("outlineVariant", expected.outlineVariant, actual.outlineVariant)
        check("scrim", expected.scrim, actual.scrim)
        check("surfaceBright", expected.surfaceBright, actual.surfaceBright)
        check("surfaceDim", expected.surfaceDim, actual.surfaceDim)
        check("surfaceContainerLowest", expected.surfaceContainerLowest, actual.surfaceContainerLowest)
        check("surfaceContainerLow", expected.surfaceContainerLow, actual.surfaceContainerLow)
        check("surfaceContainer", expected.surfaceContainer, actual.surfaceContainer)
        check("surfaceContainerHigh", expected.surfaceContainerHigh, actual.surfaceContainerHigh)
        check("surfaceContainerHighest", expected.surfaceContainerHighest, actual.surfaceContainerHighest)
        check("error", expected.error, actual.error)
        check("onError", expected.onError, actual.onError)
        check("errorContainer", expected.errorContainer, actual.errorContainer)
        check("onErrorContainer", expected.onErrorContainer, actual.onErrorContainer)
    }

    @Test
    fun `standard style returns null so theme falls back to the legacy pipeline`() {
        assertNull(
            "Standard 必须返回 null：Theme.kt 靠它回落 createSeedColorScheme（接线前同一次调用）",
            SeedColorSchemeFactory.create(Color(0xFFFB7299), isDark = false, style = SeedPaletteStyle.Standard)
        )
    }

    @Test
    fun `standard wiring reproduces the legacy scheme slot by slot`() {
        for (seed in seeds) {
            for (isDark in listOf(false, true)) {
                val legacy = createSeedColorScheme(seed, isDark)
                val wired = resolveMaterialSeedScheme(
                    seedColor = seed,
                    isDark = isDark,
                    seedPaletteStyle = SeedPaletteStyle.Standard,
                    seedColorSpec = SeedColorSpec.SPEC_2021,
                    seedContrastLevel = SeedContrastLevel.Default,
                    pureBlack = false
                )
                assertSchemeEquals(legacy, wired, "Standard seed=$seed isDark=$isDark")
            }
        }
    }

    @Test
    fun `standard ignores spec contrast and pure black`() {
        for (isDark in listOf(false, true)) {
            val legacy = createSeedColorScheme(Color(0xFFFB7299), isDark)
            val exotic = resolveMaterialSeedScheme(
                seedColor = Color(0xFFFB7299),
                isDark = isDark,
                seedPaletteStyle = SeedPaletteStyle.Standard,
                // 老用户配置里这些字段是默认值；即便被写成别的值，Standard 也不该改变观感
                seedColorSpec = SeedColorSpec.SPEC_2025,
                seedContrastLevel = SeedContrastLevel.High,
                pureBlack = true
            )
            assertSchemeEquals(
                legacy,
                exotic,
                "Standard 下 spec/contrast/pureBlack 不得影响产出（isDark=$isDark）"
            )
        }
    }

    @Test
    fun `non standard style actually changes the palette`() {
        for (isDark in listOf(false, true)) {
            val legacy = createSeedColorScheme(Color(0xFFFB7299), isDark)
            val styled = resolveMaterialSeedScheme(
                seedColor = Color(0xFFFB7299),
                isDark = isDark,
                seedPaletteStyle = SeedPaletteStyle.TonalSpot,
                seedColorSpec = SeedColorSpec.SPEC_2021,
                seedContrastLevel = SeedContrastLevel.Default,
                pureBlack = false
            )
            assertNotEquals(
                "非 Standard 必须真的换配色（否则设置项是空转）",
                legacy.primary,
                styled.primary
            )
            assertNotEquals(
                "非 Standard 的 surface 也由 HCT 决定（不再是旧手写值）",
                legacy.surface,
                styled.surface
            )
        }
    }

    @Test
    fun `pure black only applies to non standard dark schemes`() {
        val standardDark = resolveMaterialSeedScheme(
            seedColor = Color(0xFFFB7299),
            isDark = true,
            seedPaletteStyle = SeedPaletteStyle.Standard,
            seedColorSpec = SeedColorSpec.SPEC_2021,
            seedContrastLevel = SeedContrastLevel.Default,
            pureBlack = true
        )
        assertEquals(
            "Standard 路径不消费 pureBlack（后处理层负责纯黑，行为与接线前一致）",
            createSeedColorScheme(Color(0xFFFB7299), true).background,
            standardDark.background
        )
        val hctDark = resolveMaterialSeedScheme(
            seedColor = Color(0xFFFB7299),
            isDark = true,
            seedPaletteStyle = SeedPaletteStyle.TonalSpot,
            seedColorSpec = SeedColorSpec.SPEC_2021,
            seedContrastLevel = SeedContrastLevel.Default,
            pureBlack = true
        )
        assertEquals(
            "非 Standard + 深色 + 纯黑：工厂内先落纯黑（后处理层随后仍会再套一次，结果一致）",
            Color.Black,
            hctDark.background
        )
    }
}
