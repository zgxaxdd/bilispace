package com.naaammme.bbspace.core.designsystem.color

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片动态取色策略单测（移植自 BiliPai 上游 `VideoCardAdaptiveTintPolicyTest`）。
 *
 * 这些是**纯函数**，不依赖 Compose 运行时，可在 JVM 直接断言 —— 这正是把策略
 * 从卡片组件里抽出来的意义：取色的观感取舍（透明度、混合比例、对比度阈值）
 * 一旦被改动，这里会立刻失败，而不是等到肉眼发现"卡片发灰了"。
 *
 * 注：`Color.luminance()` 走 android.graphics，本地 JVM 单测靠
 * `unitTests.isReturnDefaultValues = true` 返回 0；因此涉及亮度的断言只覆盖
 * "是否走对分支"，不断言具体数值。颜色相等性断言不受影响（Color 是 inline value class）。
 */
class VideoCardAdaptiveTintPolicyTest {

    private val dark = Color(0xFF121212)
    private val light = Color(0xFFF5F5F5)
    private val coverBlue = Color(0xFF1E5AA8)
    private val neutralBorder = Color.White.copy(alpha = 0.40f)

    // ── shouldUseCoverTintForCard ──────────────────────────────────────────

    @Test
    fun coverTint_isIgnored_whenWallpaperTintEnabled() {
        // 壁纸取色开启时壁纸是唯一来源，封面色不得参与（上游明确语义）
        assertFalse(
            shouldUseCoverTintForCard(wallpaperTintEnabled = true, coverTint = coverBlue)
        )
    }

    @Test
    fun coverTint_isUsed_whenWallpaperTintDisabled() {
        assertTrue(
            shouldUseCoverTintForCard(wallpaperTintEnabled = false, coverTint = coverBlue)
        )
    }

    @Test
    fun coverTint_nullOrTransparent_isNotUsed() {
        assertFalse(shouldUseCoverTintForCard(false, null))
        assertFalse(shouldUseCoverTintForCard(false, Color.Transparent))
    }

    // ── resolveVideoCardAmbientDrawSpec ───────────────────────────────────

    @Test
    fun drawSpec_returnsDefaults_whenNoCoverTint() {
        val spec = resolveVideoCardAmbientDrawSpec(
            coverTint = null,
            isDarkTheme = true,
            defaultContainerColor = dark,
            defaultBorderColor = neutralBorder
        )
        // 无封面色时必须完全退化：底色原样、无微光、描边原样
        assertEquals(dark, spec.containerColor)
        assertEquals(0f, spec.coverGlowAlpha, 0f)
        assertEquals(neutralBorder, spec.borderColor)
    }

    @Test
    fun drawSpec_returnsDefaults_whenFrostedGlassDisabled() {
        val spec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue,
            isDarkTheme = true,
            defaultContainerColor = dark,
            defaultBorderColor = neutralBorder,
            frostedGlassEnabled = false
        )
        assertEquals(dark, spec.containerColor)
        assertEquals(0f, spec.coverGlowAlpha, 0f)
    }

    @Test
    fun drawSpec_tintsContainer_andAddsGlow_whenCoverTintPresent() {
        val spec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue,
            isDarkTheme = true,
            defaultContainerColor = dark,
            defaultBorderColor = neutralBorder
        )
        // 容器被染成封面色与底色的混合，且**必须半透明**（要透出背后模糊）
        assertNotEquals(dark, spec.containerColor)
        assertTrue("染色底必须半透明", spec.containerColor.alpha < 1f)
        // 有封面色时才有微光
        assertTrue(spec.coverGlowAlpha > 0f)
    }

    @Test
    fun drawSpec_glowIsStrongerInDarkTheme() {
        val darkSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder
        )
        val lightSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = false,
            defaultContainerColor = light, defaultBorderColor = neutralBorder
        )
        // 深色主题下微光更亮（0.48 vs 0.38）——深底上需要更强才有存在感
        assertTrue(darkSpec.coverGlowAlpha > lightSpec.coverGlowAlpha)
    }

    @Test
    fun drawSpec_dataSaver_reducesTransparency() {
        val normal = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder,
            isDataSaverActive = false
        )
        val saver = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder,
            isDataSaverActive = true
        )
        // 省流量档把 alpha 调高（0.38 → 0.65）：少一层模糊合成的算力开销
        assertTrue(saver.containerColor.alpha > normal.containerColor.alpha)
    }

    @Test
    fun drawSpec_borderIsNeverFullyOpaque() {
        val spec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = false,
            defaultContainerColor = light, defaultBorderColor = neutralBorder
        )
        // 描边是"棱镜边"不是实线，必须保留透明度
        assertTrue(spec.borderColor.alpha < 1f)
        assertTrue(spec.borderColor.alpha > 0f)
    }

    // ── resolveVideoCardAdaptiveContentColors ─────────────────────────────

    private val onSurface = Color(0xFF1A1A1A)
    private val onSurfaceVariant = Color(0xFF666666)

    @Test
    fun contentColors_areDefaults_whenTintDisabled() {
        val colors = resolveVideoCardAdaptiveContentColors(
            coverTint = coverBlue, isDarkTheme = false,
            defaultOnSurface = onSurface, defaultOnSurfaceVariant = onSurfaceVariant,
            dynamicTintEnabled = false
        )
        assertEquals(onSurface, colors.titleColor)
        assertEquals(onSurfaceVariant, colors.subtitleColor)
        assertFalse(colors.isDarkSurface)
    }

    @Test
    fun contentColors_areDefaults_inDarkTheme() {
        // 深色主题下本来就是浅字，无需翻转
        val colors = resolveVideoCardAdaptiveContentColors(
            coverTint = coverBlue, isDarkTheme = true,
            defaultOnSurface = onSurface, defaultOnSurfaceVariant = onSurfaceVariant
        )
        assertEquals(onSurface, colors.titleColor)
        assertTrue(colors.isDarkSurface)
    }

    @Test
    fun contentColors_areDefaults_whenNoCoverTint_inLightTheme() {
        val colors = resolveVideoCardAdaptiveContentColors(
            coverTint = null, isDarkTheme = false,
            defaultOnSurface = onSurface, defaultOnSurfaceVariant = onSurfaceVariant
        )
        assertEquals(onSurface, colors.titleColor)
        assertFalse(colors.isDarkSurface)
    }

    // ── resolveRepresentativeSwatch（面积优先，不是饱和度优先）─────────────

    @Test
    fun representativeSwatch_picksLargestPopulation_notMostSaturated() {
        // 这是本功能观感的关键：小面积字幕色（高饱和）不得压过主体画面
        val big = swatch(0xFF3A6EA5.toInt(), population = 9000)   // 主体蓝，面积大
        val small = swatch(0xFFFF0000.toInt(), population = 200)   // 字幕红，面积小
        val picked = VideoCardCoverColorStore.resolveRepresentativeSwatch(listOf(small, big))
        assertEquals(big, picked)
    }

    @Test
    fun representativeSwatch_fallsBackToUnfiltered_whenAllUsefulColorsFiltered() {
        // 全近黑/近白/近灰时不能返回 null——宁可染平庸色也不要无色可染
        val nearBlack = swatch(0xFF050505.toInt(), population = 5000)
        val picked = VideoCardCoverColorStore.resolveRepresentativeSwatch(listOf(nearBlack))
        assertEquals(nearBlack, picked)
    }

    @Test
    fun representativeSwatch_returnsNull_forEmptyInput() {
        assertEquals(null, VideoCardCoverColorStore.resolveRepresentativeSwatch(emptyList()))
    }

    /** 构造 Palette.Swatch：色值 + 面积。Swatch 无公开构造器之外的依赖，直接 new。 */
    private fun swatch(rgb: Int, population: Int) =
        androidx.palette.graphics.Palette.Swatch(rgb, population)
}
