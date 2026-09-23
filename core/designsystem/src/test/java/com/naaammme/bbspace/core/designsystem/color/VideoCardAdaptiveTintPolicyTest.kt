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

    // ── 染色强度的数值守卫 ────────────────────────────────────────────────
    //
    // 背景：这组参数被改过两轮，方向相反，而现有 15 条单测**只钉关系不钉数值** ——
    // 所以两轮改动都没让测试报警，问题只能等用户肉眼发现。下面的断言就是补这个缺口。
    //
    // 判据选「色偏 × alpha」而不是直接钉 0.68 / 0.28 这些字面量，有两个原因：
    // 1. 它测的是**用户实际看到的东西**（颜色偏离背景多少、又有多不透明）；
    // 2. 它对"换个封面色"不敏感，因此不会把染色强度与色彩细节耦合在一起 ——
    //    后者才是脆弱的钉法（改个测试用色就要改期望值）。
    //
    // 阈值来历（用本文件的 coverBlue = 0xFF1E5AA8 实测）：
    // - 上游原值（浅 0.75×0.34）→ 0.224；深（0.85×0.38）→ 0.284
    // - `d70fcce0` 下调后（浅 0.58×0.20）→ 0.102；深（0.72×0.28）→ 0.171
    // - 本次折中（浅 0.68×0.28）→ 0.168；深（0.80×0.34）→ 0.239
    // 取 0.15 为下限：**能挡住下调到 0.102 的那次**，又给折中值留出余量。
    // 换言之，若有人再把强度压到"用户读作没上色"，这条会红。

    /** 容器色相对默认底色的最大通道偏移（0..1）。lerp 是逐通道线性，故它正比于混色比例。 */
    private fun channelDelta(a: Color, b: Color): Float = maxOf(
        kotlin.math.abs(a.red - b.red),
        kotlin.math.abs(a.green - b.green),
        kotlin.math.abs(a.blue - b.blue)
    )

    /**
     * 染色可见度 = 色偏 × alpha。等价于 `混色比例 × 通道差 × 透明度`，
     * 即"这层颜色落在背景上，最终让背景偏离了多少"。
     */
    private fun tintVisibility(container: Color, defaultContainer: Color): Float =
        channelDelta(container, defaultContainer) * container.alpha

    @Test
    fun tintVisibility_isNotTooFaint_inLightTheme() {
        val spec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = false,
            defaultContainerColor = light, defaultBorderColor = neutralBorder
        )
        val visibility = tintVisibility(spec.containerColor, light)
        assertTrue(
            "浅色主题染色可见度只有 $visibility，低于下限 0.15 —— " +
                "用户会把「太淡」读成「没取到色」。历史上 0.58×0.20 那版就是 0.102，" +
                "正是这个问题的起因。",
            visibility >= 0.15f
        )
    }

    @Test
    fun tintVisibility_isNotTooFaint_inDarkTheme() {
        val spec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder
        )
        val visibility = tintVisibility(spec.containerColor, dark)
        assertTrue(
            "深色主题染色可见度只有 $visibility，低于下限 0.15",
            visibility >= 0.15f
        )
    }

    @Test
    fun tintVisibility_isNotOverpowering() {
        // 反向守卫：也不能压成"纯色板"。上限取 0.45 —— 上游最激进的档位是 0.284，
        // 留足余量，只用来挡住"把 blend 拉到 1.0 且 alpha 拉到 0.6"这类极端改动。
        val lightSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = false,
            defaultContainerColor = light, defaultBorderColor = neutralBorder
        )
        val darkSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder
        )
        assertTrue(
            "浅色染色过强（${tintVisibility(lightSpec.containerColor, light)}），会退化成纯色板",
            tintVisibility(lightSpec.containerColor, light) <= 0.45f
        )
        assertTrue(
            "深色染色过强（${tintVisibility(darkSpec.containerColor, dark)}），会退化成纯色板",
            tintVisibility(darkSpec.containerColor, dark) <= 0.45f
        )
    }

    @Test
    fun glowAlpha_matchesContainerTintOrder() {
        // 微光与底色是同一套强度语言：底色染得越重，边缘光也该越强。
        // 这条防止"只调了底色忘了光晕"的半吊子改动。
        val lightSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = false,
            defaultContainerColor = light, defaultBorderColor = neutralBorder
        )
        val darkSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder
        )
        assertTrue("浅色光晕偏低：${lightSpec.coverGlowAlpha}", lightSpec.coverGlowAlpha >= 0.25f)
        assertTrue("深色光晕偏低：${darkSpec.coverGlowAlpha}", darkSpec.coverGlowAlpha >= 0.35f)
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
