package com.naaammme.bbspace.core.designsystem.theme.scheme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设种子色（上游 `SeedPresetColors.kt`）：数量、顺序与"名字一一对应"是设置页的下标契约。 */
class SeedPresetColorsTest {

    @Test
    fun presetColorsMatchUpstreamValues() {
        assertEquals(19, SeedPresetColors.size)
        assertEquals(19, SeedPresetColorNames.size)
        assertEquals(Color(0xFF5CB67B), SeedPresetColors.first())
        assertEquals("默认绿", SeedPresetColorNames.first())
        assertEquals(Color(0xFFF44336), SeedPresetColors[2])
        assertEquals(Color(0xFF9E9E9E), SeedPresetColors.last())
    }

    @Test
    fun presetColorsHaveNoDuplicatesAndAreFullyOpaque() {
        assertEquals(SeedPresetColors.size, SeedPresetColors.toSet().size)
        assertTrue("预设色必须是不透明色（alpha=0xFF）", SeedPresetColors.all { it.alpha == 1f })
    }

    @Test
    fun namesAreNonBlankAndOneToOneWithColors() {
        for ((index, name) in SeedPresetColorNames.withIndex()) {
            assertTrue("第 $index 个预设名不能为空", name.isNotBlank())
        }
        assertEquals(
            "颜色与名字必须按下标一一对应",
            SeedPresetColors.size,
            SeedPresetColorNames.size
        )
    }
}
