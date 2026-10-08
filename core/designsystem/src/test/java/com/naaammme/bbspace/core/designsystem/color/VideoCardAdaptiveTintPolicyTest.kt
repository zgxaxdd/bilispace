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
        // 容器被染成封面色与底色的混合
        assertNotEquals(dark, spec.containerColor)
        // 染色色必须是**实色**。
        //
        // 旧断言是"必须半透明（要透出背后模糊）"，那是旧架构的假设 ——
        // 当时染色靠 `.copy(alpha = ...)`（那个 alpha 来自现已删除的 `glassTransparency`）
        // 叠在屏幕背景上，结果是**往白里兑了两次**、颜色被冲没（详见
        // VideoCardAdaptiveTintPolicy 里"往白里兑了两次"那段）。现在改为与不透明卡片
        // 底色一次混合，所以必须是实色 —— 这条断言正是防止有人把半透明叠回来。
        assertEquals(
            "染色色必须是实色；半透明会让颜色被屏幕背景二次稀释",
            1f,
            spec.containerColor.alpha,
            0.0001f
        )
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
    // 背景：这组参数被改过三轮，而现有单测**只钉关系不钉数值** ——
    // 所以每次改动都没让测试报警，问题只能等用户肉眼发现。下面的断言就是补这个缺口。
    //
    // 判据选「色偏 × alpha」而不是直接钉字面量，有两个原因：
    // 1. 它测的是**用户实际看到的东西**（颜色偏离背景多少、又有多不透明）；
    // 2. 它对"换个封面色"不敏感，因此不会把染色强度与色彩细节耦合在一起。
    //
    // ## 阈值来历（本文件 coverBlue = 0xFF1E5AA8 实测）
    //
    // | 版本 | 浅色 混色×透明 | 可见度 | 用户反馈 |
    // |---|---|---|---|
    // | 上游原值 | 0.75 × 0.34 | 0.224 | —— |
    // | `d70fcce0` 下调 | 0.58 × 0.20 | **0.102** | **"取色不对/没取色"** |
    // | 本次 | 0.85 × 0.70 | **0.53** | 颜色与预览图对应得上 |
    //
    // **下限 0.25**：能挡住 0.102 那一版（用户明确反馈"看不出颜色"的那次），
    // 又给上游原值 0.224 留一点余量 —— 即"上游那版其实也偏淡"。
    //
    // **上限 0.65**：不是"怕拼色块"，而是**文字可读性**的硬约束 ——
    // 卡片正文是黑字，底色压太暗就不达 WCAG AA 4.5:1。
    // 实测最暗的红色封面（`#59140d`）：
    //   0.85 × 0.70 → 对比度 5.1 ✅
    //   0.90 × 0.80 → 对比度 3.5 ❌
    // 所以上限设在这里，挡住"再往上加"的改动。

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
            "浅色主题染色可见度只有 $visibility，低于下限 0.25 —— " +
                "用户会把「太淡」读成「没取到色」，即简介区颜色与预览图对应不上。" +
                "历史上 0.58×0.20 那版就是 0.102，正是这个问题的起因。",
            visibility >= 0.25f
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
            "深色主题染色可见度只有 $visibility，低于下限 0.25",
            visibility >= 0.25f
        )
    }

    @Test
    fun tintVisibility_isNotOverpowering() {
        // 反向守卫。上限 0.65 的**依据是文字可读性**，不是"怕拼色块"：
        // 卡片正文是黑字，底色压太暗就不达 WCAG AA 4.5:1。
        // 实测最暗的红色封面（#59140d）：0.85×0.70 → 对比度 5.1 ✅；
        // 0.90×0.80 → 3.5 ❌。所以上限设在这里挡住"再往上加"。
        val lightSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = false,
            defaultContainerColor = light, defaultBorderColor = neutralBorder
        )
        val darkSpec = resolveVideoCardAmbientDrawSpec(
            coverTint = coverBlue, isDarkTheme = true,
            defaultContainerColor = dark, defaultBorderColor = neutralBorder
        )
        assertTrue(
            "浅色染色过强（${tintVisibility(lightSpec.containerColor, light)}）—— " +
                "会压低黑字对比度，低于 WCAG AA 4.5:1",
            tintVisibility(lightSpec.containerColor, light) <= 0.65f
        )
        assertTrue(
            "深色染色过强（${tintVisibility(darkSpec.containerColor, dark)}）",
            tintVisibility(darkSpec.containerColor, dark) <= 0.65f
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

    // ── resolveRepresentativeSwatch（面积主导，但"又大又灰/又大又黑"不选）─────

    @Test
    fun representativeSwatch_picksLargestPopulation_notMostSaturated() {
        // 上游的核心性质：小面积字幕色（高饱和）不得压过主体画面。
        // 主体蓝面积 9000（评分 5836）vs 字幕红面积 200（评分 200）—— 面积主导。
        val big = swatch(0xFF3A6EA5.toInt(), population = 9000)   // 主体蓝，面积大
        val small = swatch(0xFFFF0000.toInt(), population = 200)   // 字幕红，面积小
        val picked = VideoCardCoverColorStore.resolveRepresentativeSwatch(listOf(small, big))
        assertEquals(big, picked)
    }

    @Test
    fun representativeSwatch_prefersVividOverBiggerButGray() {
        // 真机实测场景（这是"简介区颜色与预览图对应不上"的根因）：
        // 封面里面积最大的那块是**低饱和的灰**，而人眼认定的主色是旁边稍小但鲜艳的棕。
        // 纯按面积会选灰 → 染出来是灰的；综合评分应选鲜艳的棕。
        val bigGray = swatch(0xFF747469.toInt(), population = 216)   // 灰褐 sat 9.5%
        val smallerVivid = swatch(0xFF866857.toInt(), population = 94) // 鲜艳棕 sat 35.1%
        val picked = VideoCardCoverColorStore.resolveRepresentativeSwatch(
            listOf(bigGray, smallerVivid)
        )
        assertEquals("应选鲜艳的棕而不是面积更大的灰", smallerVivid, picked)
    }

    @Test
    fun representativeSwatch_prefersUsableBrightness_overNearBlack() {
        // 另一个真机场景：面积最大的是**近乎纯黑**（明度 9.4%），混进白底只会得到灰。
        // 旁边稍小但明度可用的蓝才是能染的。
        val nearBlack = swatch(0xFF0A1318.toInt(), population = 185)  // val 9.4%
        val usableBlue = swatch(0xFF253747.toInt(), population = 159) // val 27.8%
        val picked = VideoCardCoverColorStore.resolveRepresentativeSwatch(
            listOf(nearBlack, usableBlue)
        )
        assertEquals("应选明度可用的蓝而不是面积更大的近黑", usableBlue, picked)
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

    // ── rgbToHsv：纯 Kotlin 实现，替代被 JVM stub 掉的 framework 版本 ────────

    @Test
    fun rgbToHsv_matchesKnownValues() {
        // 纯红：hue 0、sat 1、val 1
        val red = VideoCardCoverColorStore.rgbToHsv(0xFFFF0000.toInt())
        assertEquals(0f, red[0], 0.5f)
        assertEquals(1f, red[1], 0.001f)
        assertEquals(1f, red[2], 0.001f)
        // 纯绿：hue 120
        assertEquals(120f, VideoCardCoverColorStore.rgbToHsv(0xFF00FF00.toInt())[0], 0.5f)
        // 纯蓝：hue 240
        assertEquals(240f, VideoCardCoverColorStore.rgbToHsv(0xFF0000FF.toInt())[0], 0.5f)
    }

    @Test
    fun rgbToHsv_grayHasZeroSaturation() {
        val gray = VideoCardCoverColorStore.rgbToHsv(0xFF808080.toInt())
        assertEquals(0f, gray[1], 0.001f)
        // 0x80/255 ≈ 0.502
        assertEquals(0.502f, gray[2], 0.01f)
    }

    @Test
    fun rgbToHsv_isActuallyExecuted_notStubbed() {
        // 这条是**回归守卫**：原实现用 android.graphics.Color.colorToHSV，
        // 它在 JVM 单测里被 stub 成空实现 → hsv[] 恒为 0 → 依赖它的过滤分支静默走错路，
        // 而测试却"通过"。改用纯 Kotlin 实现后，这条断言必须能拿到真实值。
        // 若有人换回 framework 版本，这条会立刻红。
        val hsv = VideoCardCoverColorStore.rgbToHsv(0xFF3A6EA5.toInt())
        assertTrue(
            "hsv 三个分量全是 0，说明 HSV 转换没有被真正执行（framework 版本在 JVM 下是空实现）",
            hsv[0] != 0f || hsv[1] != 0f || hsv[2] != 0f
        )
        // 0x3A6EA5 是明显的蓝，饱和度与明度都不该是 0
        assertTrue("饱和度不应为 0：${hsv[1]}", hsv[1] > 0.5f)
        assertTrue("明度不应为 0：${hsv[2]}", hsv[2] > 0.5f)
    }

    /** 构造 Palette.Swatch：色值 + 面积。Swatch 无公开构造器之外的依赖，直接 new。 */
    private fun swatch(rgb: Int, population: Int) =
        androidx.palette.graphics.Palette.Swatch(rgb, population)
}
