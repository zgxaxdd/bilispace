package com.naaammme.bbspace.core.designsystem.glass.liquid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LiquidGlassTuningPolicy` 的 JVM 单测。
 *
 * 最重要的三条：
 * 1. **默认值逐值等于现状**（`defaultsMatchCurrentRendering`）—— 这一层是"参数化"，
 *    不是"换观感"；默认路径任何一个数变了，都是一个可见回归；
 * 2. **强度只缩放模糊与折射**（现状口径），不缩放染色/饱和 —— 否则"强度 2"会比
 *    现在更暗更艳；
 * 3. **cap=0 必须整体归零、reduceMotion 必须去掉形变类**（折射/色散），
 *    但保留静态的模糊/染色/饱和。
 */
class LiquidGlassTuningPolicyTest {

    private fun resolve(
        strength: Float = LiquidGlassTuningPolicy.DefaultStrength,
        progress: Float = LiquidGlassTuningPolicy.BalancedProgress,
        isDarkTheme: Boolean = false,
        reduceMotion: Boolean = false,
        cap: Float = 1f
    ): LiquidGlassTuning = LiquidGlassTuningPolicy.resolve(
        strength = strength,
        progress = progress,
        isDarkTheme = isDarkTheme,
        reduceMotion = reduceMotion,
        glassStrengthCap = cap
    )

    @Test
    fun `defaults match current rendering`() {
        val light = resolve(isDarkTheme = false)
        // 现状：blur(4f*s) / lens(24f*s, 24f*s) / vibrancy(1.5f) / alpha 0.40×0.95
        assertEquals("默认模糊半径必须还是 4dp", 4f, light.blurRadiusDp, 0.0001f)
        assertEquals("默认折射高度必须还是 24dp", 24f, light.refractionHeightDp, 0.0001f)
        assertEquals("默认折射强度必须还是 24dp", 24f, light.refractionAmountDp, 0.0001f)
        assertEquals("默认饱和度必须还是 1.5", 1.5f, light.saturation, 0.0001f)
        assertEquals(
            "浅色染色 alpha 必须还是 0.40×0.95=0.38",
            0.38f,
            light.surfaceAlpha,
            0.0001f
        )
        assertEquals("现状无色散", 0f, light.chromaticAberration, 0f)
        assertEquals("现状无 scrim", 0f, light.scrimAlpha, 0f)

        val dark = resolve(isDarkTheme = true)
        assertEquals(
            "深色染色 alpha 必须还是 0.40×0.75=0.30",
            0.30f,
            dark.surfaceAlpha,
            0.0001f
        )
        assertEquals("深色只改染色系数，模糊不变", 4f, dark.blurRadiusDp, 0.0001f)
    }

    @Test
    fun `strength scales blur and refraction like the old formula`() {
        val half = resolve(strength = 0.5f)
        assertEquals("strength 0.5 ⇒ 模糊 2dp（= 4×0.5）", 2f, half.blurRadiusDp, 0.0001f)
        assertEquals("strength 0.5 ⇒ 折射 12dp（= 24×0.5）", 12f, half.refractionHeightDp, 0.0001f)
        val double = resolve(strength = 2f)
        assertEquals("strength 2 ⇒ 模糊 8dp（= 4×2）", 8f, double.blurRadiusDp, 0.0001f)
        assertEquals("strength 2 ⇒ 折射 48dp（= 24×2）", 48f, double.refractionAmountDp, 0.0001f)
    }

    @Test
    fun `strength must not change alpha or saturation`() {
        val baseline = resolve()
        val strong = resolve(strength = 2f)
        assertEquals(
            "强度不许改染色 alpha（现状口径：strength 只缩放模糊与折射）",
            baseline.surfaceAlpha,
            strong.surfaceAlpha,
            0f
        )
        assertEquals(
            "强度不许改饱和度（同上）",
            baseline.saturation,
            strong.saturation,
            0f
        )
    }

    @Test
    fun `zero cap disables the whole glass`() {
        val off = resolve(cap = 0f)
        assertEquals("cap=0 ⇒ 不模糊", 0f, off.blurRadiusDp, 0f)
        assertEquals("cap=0 ⇒ 不折射", 0f, off.refractionHeightDp, 0f)
        assertEquals("cap=0 ⇒ 不折射", 0f, off.refractionAmountDp, 0f)
        assertEquals("cap=0 ⇒ 不染色", 0f, off.surfaceAlpha, 0f)
        assertEquals("cap=0 ⇒ 无 scrim", 0f, off.scrimAlpha, 0f)
    }

    @Test
    fun `reduce motion drops deformation but keeps static parts`() {
        val static = resolve(reduceMotion = true)
        assertEquals("关动效 ⇒ 折射归零（形变类）", 0f, static.refractionHeightDp, 0f)
        assertEquals("关动效 ⇒ 折射归零（形变类）", 0f, static.refractionAmountDp, 0f)
        assertEquals("关动效 ⇒ 色散归零", 0f, static.chromaticAberration, 0f)
        assertEquals("关动效 ⇒ 模糊保留（静态）", 4f, static.blurRadiusDp, 0.0001f)
        assertEquals("关动效 ⇒ 染色保留（静态）", 0.38f, static.surfaceAlpha, 0.0001f)
        assertEquals("关动效 ⇒ 饱和度保留（静态）", 1.5f, static.saturation, 0.0001f)
    }

    @Test
    fun `progress is monotonic in the expected direction`() {
        val clear = resolve(progress = 0f)
        val balanced = resolve(progress = LiquidGlassTuningPolicy.BalancedProgress)
        val frosted = resolve(progress = 1f)
        assertTrue(
            "模糊随进度不减（${clear.blurRadiusDp} → ${frosted.blurRadiusDp}）",
            frosted.blurRadiusDp >= balanced.blurRadiusDp &&
                balanced.blurRadiusDp >= clear.blurRadiusDp
        )
        assertTrue(
            "折射随进度不增（${clear.refractionHeightDp} → ${frosted.refractionHeightDp}）",
            frosted.refractionHeightDp <= balanced.refractionHeightDp &&
                balanced.refractionHeightDp <= clear.refractionHeightDp
        )
        assertTrue(
            "饱和度随进度不增（磨砂更「灰」）",
            frosted.saturation <= balanced.saturation
        )
        assertTrue(
            "染色随进度不减（磨砂更实）",
            frosted.surfaceAlpha >= balanced.surfaceAlpha
        )
        assertTrue(
            "scrim 随进度不减（默认 0，磨砂端才出现）",
            frosted.scrimAlpha >= balanced.scrimAlpha
        )
    }

    @Test
    fun `frosted endpoint uses the upstream anchors`() {
        val frosted = resolve(progress = 1f)
        assertEquals("磨砂端模糊 10dp", 10f, frosted.blurRadiusDp, 0.0001f)
        assertEquals("磨砂端折射 8dp", 8f, frosted.refractionHeightDp, 0.0001f)
        assertEquals("磨砂端饱和 1.24", 1.24f, frosted.saturation, 0.0001f)
        assertEquals(
            "磨砂端染色 0.54×0.95",
            0.54f * 0.95f,
            frosted.surfaceAlpha,
            0.0001f
        )
        assertEquals("磨砂端 scrim 0.10", 0.10f, frosted.scrimAlpha, 0.0001f)
    }

    @Test
    fun `out of range and non finite inputs clamp to safe values`() {
        assertEquals(
            "strength 超上限钳到 2（= 模糊 8）",
            8f,
            resolve(strength = 99f).blurRadiusDp,
            0.0001f
        )
        assertEquals(
            "strength 低于下限钳到 0.5（= 模糊 2）",
            2f,
            resolve(strength = 0.1f).blurRadiusDp,
            0.0001f
        )
        val nanStrength = resolve(strength = Float.NaN)
        assertEquals("NaN 强度回落默认值", 4f, nanStrength.blurRadiusDp, 0.0001f)
        val nanProgress = resolve(progress = Float.NaN)
        assertEquals(
            "NaN 进度回落 balanced（= 现状值）",
            4f,
            nanProgress.blurRadiusDp,
            0.0001f
        )
        assertEquals(
            "NaN cap 按 1 处理",
            4f,
            resolve(cap = Float.NaN).blurRadiusDp,
            0.0001f
        )
        assertEquals(
            "progress 超界钳到 1",
            10f,
            resolve(progress = 9f).blurRadiusDp,
            0.0001f
        )
    }

    @Test
    fun `alpha and scrim stay inside zero one`() {
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (dark in listOf(false, true)) {
                val tuning = resolve(progress = progress, isDarkTheme = dark)
                assertTrue(
                    "alpha 必须在 0..1（progress=$progress dark=$dark 得 ${tuning.surfaceAlpha}）",
                    tuning.surfaceAlpha in 0f..1f
                )
                assertTrue(
                    "scrim 必须在 0..1（${tuning.scrimAlpha}）",
                    tuning.scrimAlpha in 0f..1f
                )
            }
        }
    }
}
