package com.naaammme.bbspace.core.designsystem.theme.scheme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置持久化编解码的往返 + 容错契约（纯 JVM）。
 *
 * `AppSettings.buildThemeConfig` 与三个 `update*` setter 都调用本文件的函数，
 * 所以这里穷举一遍就等于覆盖了"存进去再读出来"的数据契约（DataStore I/O 本身
 * 不在这里，属于框架行为）。
 */
class SeedSettingsCodecTest {

    @Test
    fun `palette style round trips through its name`() {
        for (style in SeedPaletteStyle.entries) {
            assertEquals(
                "22 风格的 name 必须原样往返：$style",
                style,
                SeedSettingsCodec.decodeSeedPaletteStyle(SeedSettingsCodec.encodeSeedPaletteStyle(style))
            )
        }
    }

    @Test
    fun `unknown or missing palette style falls back to standard`() {
        assertEquals(SeedPaletteStyle.Standard, SeedSettingsCodec.decodeSeedPaletteStyle(null))
        assertEquals(SeedPaletteStyle.Standard, SeedSettingsCodec.decodeSeedPaletteStyle(""))
        assertEquals(SeedPaletteStyle.Standard, SeedSettingsCodec.decodeSeedPaletteStyle("NoSuchStyle"))
        // 旧持久化值/手改值不得让主题流抛异常（这正是 runCatching 时代的教训）
        assertEquals(SeedPaletteStyle.Standard, SeedSettingsCodec.decodeSeedPaletteStyle("tonalspot"))
    }

    @Test
    fun `color spec round trips and falls back to 2021`() {
        for (spec in SeedColorSpec.entries) {
            assertEquals(
                spec,
                SeedSettingsCodec.decodeSeedColorSpec(SeedSettingsCodec.encodeSeedColorSpec(spec))
            )
        }
        assertEquals(SeedColorSpec.SPEC_2021, SeedSettingsCodec.decodeSeedColorSpec(null))
        assertEquals(SeedColorSpec.SPEC_2021, SeedSettingsCodec.decodeSeedColorSpec("SPEC_2024"))
    }

    @Test
    fun `contrast level round trips and falls back to default`() {
        for (level in SeedContrastLevel.entries) {
            assertEquals(
                level,
                SeedSettingsCodec.decodeSeedContrastLevel(
                    SeedSettingsCodec.encodeSeedContrastLevel(level)
                )
            )
        }
        assertEquals(SeedContrastLevel.Default, SeedSettingsCodec.decodeSeedContrastLevel(null))
        assertEquals(SeedContrastLevel.Default, SeedSettingsCodec.decodeSeedContrastLevel("Ultra"))
        // 数值口径：与 material-kolor 的 Contrast 一致（Reduced −1 / Default 0 / Medium 0.5 / High 1）
        assertEquals(-1.0, SeedContrastLevel.Reduced.level, 0.0)
        assertEquals(0.0, SeedContrastLevel.Default.level, 0.0)
        assertEquals(0.5, SeedContrastLevel.Medium.level, 0.0)
        assertEquals(1.0, SeedContrastLevel.High.level, 0.0)
    }
}
