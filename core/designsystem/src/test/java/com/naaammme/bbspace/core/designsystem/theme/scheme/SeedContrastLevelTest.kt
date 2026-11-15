package com.naaammme.bbspace.core.designsystem.theme.scheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对比度档：**数值必须与 MaterialKolor 4.1.1 逐位一致**（否则将来接 HCT 时同一档位
 * 会算出不同色板），以及越界/非数输入的吸附口径。
 */
class SeedContrastLevelTest {

    @Test
    fun levelsMatchMaterialKolorConstants() {
        assertEquals(-1.0, SeedContrastLevel.Reduced.level, 1e-9)
        assertEquals(0.0, SeedContrastLevel.Default.level, 1e-9)
        assertEquals(0.5, SeedContrastLevel.Medium.level, 1e-9)
        assertEquals(1.0, SeedContrastLevel.High.level, 1e-9)
        assertEquals(
            "展示顺序必须与上游一致：Reduced → Default → Medium → High",
            listOf(
                SeedContrastLevel.Reduced,
                SeedContrastLevel.Default,
                SeedContrastLevel.Medium,
                SeedContrastLevel.High,
            ),
            SeedContrastLevel.entries.toList()
        )
    }

    @Test
    fun outOfRangeLevelsSnapToNearestEdgeInsteadOfExtrapolating() {
        assertEquals(SeedContrastLevel.High, SeedContrastLevel.fromLevel(2.0))
        assertEquals(SeedContrastLevel.High, SeedContrastLevel.fromLevel(100.0))
        assertEquals(SeedContrastLevel.Reduced, SeedContrastLevel.fromLevel(-2.0))
        assertEquals(SeedContrastLevel.Reduced, SeedContrastLevel.fromLevel(-100.0))
    }

    @Test
    fun exactAndInBetweenLevelsResolvePredictably() {
        assertEquals(SeedContrastLevel.Default, SeedContrastLevel.fromLevel(0.0))
        assertEquals(SeedContrastLevel.Medium, SeedContrastLevel.fromLevel(0.5))
        assertEquals(SeedContrastLevel.High, SeedContrastLevel.fromLevel(1.0))
        assertEquals(SeedContrastLevel.Default, SeedContrastLevel.fromLevel(0.2))
        assertEquals(SeedContrastLevel.Medium, SeedContrastLevel.fromLevel(0.7))
        assertEquals(SeedContrastLevel.Reduced, SeedContrastLevel.fromLevel(-0.7))
        // 等距（-0.5 距 Reduced 与 Default 各 0.5）→ 取更接近 0 的 Default，避免把用户推向极端档。
        assertEquals(SeedContrastLevel.Default, SeedContrastLevel.fromLevel(-0.5))
    }

    @Test
    fun nonFiniteInputFallsBackToDefault() {
        assertEquals(SeedContrastLevel.Default, SeedContrastLevel.fromLevel(Double.NaN))
    }

    @Test
    fun everyLevelHasNonBlankDescriptionAndSubtitle() {
        for (level in SeedContrastLevel.entries) {
            assertTrue("$level 缺少说明", resolveColorContrastDescription(level).isNotBlank())
            assertTrue(
                "$level subtitle 格式必须为 `当前 X：…`",
                resolveColorContrastSubtitle(level).startsWith("当前 ${level.name}：")
            )
        }
    }
}
