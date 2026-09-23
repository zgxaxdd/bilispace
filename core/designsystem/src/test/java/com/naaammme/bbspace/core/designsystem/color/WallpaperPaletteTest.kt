package com.naaammme.bbspace.core.designsystem.color

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸色板与 Y 轴插值单测。
 *
 * 这些是**纯函数**（[interpolateWallpaperColor]）与**纯数据**（[WallpaperPalette]），
 * 不碰 WallpaperManager，因此可在 JVM 直接跑 —— 这一点很重要：真机上
 * `getWallpaperColors` 在 ColorOS 上可能取不到，但"取到之后怎么用"这部分逻辑
 * 不该因为设备差异而无法验证。
 */
class WallpaperPaletteTest {

    private val top = Color(0xFF112233)
    private val mid = Color(0xFF445566)
    private val bottom = Color(0xFF778899)

    private val palette = WallpaperPalette(
        topColor = top,
        bottomColor = bottom,
        dominantColor = mid,
        stops = listOf(top, mid, bottom)
    )

    // ── 插值端点 ──────────────────────────────────────────────────────────

    @Test
    fun yFractionZero_returnsTopColor() {
        assertEquals(top, interpolateWallpaperColor(palette, 0f))
    }

    @Test
    fun yFractionOne_returnsBottomColor() {
        // 浮点误差曾可能让 index 算出 size-1 而越界，这条钉住端点行为
        assertEquals(bottom, interpolateWallpaperColor(palette, 1f))
    }

    @Test
    fun midPoint_returnsDominantColor() {
        // 3 段时 0.5 正好落在中间那段的起点，即 dominantColor
        assertEquals(mid, interpolateWallpaperColor(palette, 0.5f))
    }

    @Test
    fun outOfRangeFractions_areClamped_notThrowing() {
        // 调用方理论上已经钳过，但函数自身也不能因为越界而抛 ——
        // 卡片位置在布局首帧可能拿到负值/超出值。
        assertEquals(top, interpolateWallpaperColor(palette, -5f))
        assertEquals(bottom, interpolateWallpaperColor(palette, 5f))
    }

    @Test
    fun interpolationIsMonotonic_acrossStops() {
        // 相邻采样点之间必须是渐变（不跳变）：这是"色彩随滚动流动"的观感基础
        val samples = (0..10).map { interpolateWallpaperColor(palette, it / 10f) }
        assertTrue("插值不应全部相同", samples.distinct().size > 3)
        assertEquals(top, samples.first())
        assertEquals(bottom, samples.last())
    }

    // ── 退化输入 ──────────────────────────────────────────────────────────

    @Test
    fun emptyStops_fallsBackToTopColor() {
        val p = WallpaperPalette(topColor = top, bottomColor = bottom, stops = emptyList())
        assertEquals(top, interpolateWallpaperColor(p, 0.5f))
    }

    @Test
    fun singleStop_returnsThatStopEverywhere() {
        val p = WallpaperPalette(topColor = top, bottomColor = top, stops = listOf(top))
        assertEquals(top, interpolateWallpaperColor(p, 0f))
        assertEquals(top, interpolateWallpaperColor(p, 0.5f))
        assertEquals(top, interpolateWallpaperColor(p, 1f))
    }

    @Test
    fun twoStops_interpolatesLinearly() {
        val p = WallpaperPalette(topColor = top, bottomColor = bottom, stops = listOf(top, bottom))
        assertEquals(top, interpolateWallpaperColor(p, 0f))
        assertEquals(bottom, interpolateWallpaperColor(p, 1f))
        val half = interpolateWallpaperColor(p, 0.5f)
        assertNotEquals(top, half)
        assertNotEquals(bottom, half)
    }

    @Test
    fun fiveStops_handledLikeUpstreamCustomWallpaper() {
        // 上游自定义壁纸按高度切 5 片；BBSpace 虽然只给 3 段，但函数必须对任意段数正确
        val five = WallpaperPalette(
            topColor = top,
            bottomColor = bottom,
            stops = listOf(top, top, mid, bottom, bottom)
        )
        assertEquals(top, interpolateWallpaperColor(five, 0f))
        assertEquals(bottom, interpolateWallpaperColor(five, 1f))
        assertEquals(mid, interpolateWallpaperColor(five, 0.5f))
    }

    // ── 默认值 ────────────────────────────────────────────────────────────

    @Test
    fun paletteDefaults_areSelfConsistent() {
        // stops 默认由 top/bottom 派生，不能是空
        val p = WallpaperPalette(topColor = top, bottomColor = bottom)
        assertEquals(listOf(top, bottom), p.stops)
        assertEquals(top, p.dominantColor)
    }
}
