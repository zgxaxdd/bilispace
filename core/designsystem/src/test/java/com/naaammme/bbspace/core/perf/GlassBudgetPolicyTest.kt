package com.naaammme.bbspace.core.perf

import com.naaammme.bbspace.core.designsystem.glass.GlassSurfaceType
import com.naaammme.bbspace.core.designsystem.glass.MotionTier
import com.naaammme.bbspace.core.designsystem.glass.minMotionTier
import com.naaammme.bbspace.core.designsystem.glass.motionTierOrderIsConservativeFirst
import com.naaammme.bbspace.core.designsystem.glass.resolveGlassBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃预算策略的状态测（AP-27 ②/④）。
 *
 * `resolveGlassBudget` 此前**零单测**（上游 BiliPai 有 `BlurBudgetPolicyTest`）——
 * 它是"降级时玻璃怎么变"的唯一裁决者，改错只会表现为"某些档位下观感跳变"，
 * 编译与真机都不一定抓得住。这里把表钉住：
 * 1. [motionTierOrderIsConservativeFirst]（GlassBudget KDoc 点名的那条断言）；
 * 2. 三档 × 各 surface 的等级/透明度/实时性；
 * 3. 滚动/转场**只停实时重录、不降模糊等级**（BiliPai Pulsing 教训）；
 * 4. forceLowBudget / Reduced 才强切 0 级。
 */
class GlassBudgetPolicyTest {

    /** AP-27 ④：`MotionTier` 的声明顺序必须是"保守在前"，移植时不许重排。 */
    @Test
    fun assertMotionTierOrderIsConservativeFirst() {
        assertTrue(
            "MotionTier 顺序必须是 Reduced < Normal < Enhanced（保守在前）：" +
                "${MotionTier.entries}",
            motionTierOrderIsConservativeFirst()
        )
        assertEquals(listOf(MotionTier.Reduced, MotionTier.Normal, MotionTier.Enhanced), MotionTier.entries.toList())
    }

    /** 保守档取 min：任一侧是 Reduced 就压到 Reduced（顺序即保守度）。 */
    @Test
    fun minMotionTierTakesTheConservativeOne() {
        assertEquals(MotionTier.Reduced, minMotionTier(MotionTier.Reduced, MotionTier.Enhanced))
        assertEquals(MotionTier.Normal, minMotionTier(MotionTier.Normal, MotionTier.Enhanced))
        assertEquals(MotionTier.Reduced, minMotionTier(MotionTier.Normal, MotionTier.Reduced))
    }

    @Test
    fun normalTierKeepsPerSurfaceLevels() {
        val header = budget(GlassSurfaceType.HEADER)
        assertEquals(2, header.maxBlurLevel)
        assertEquals(1.00f, header.backgroundAlphaMultiplier, 1e-6f)
        assertTrue("静止态允许实时重录", header.allowRealtime)

        val bottomBar = budget(GlassSurfaceType.BOTTOM_BAR)
        assertEquals(
            "AP-27：BOTTOM_BAR=2 是现状值（依据已按登记表重算，降回 1 属观感变更 → AP-T6）",
            2,
            bottomBar.maxBlurLevel
        )
        assertEquals(0.88f, bottomBar.backgroundAlphaMultiplier, 1e-6f)

        assertEquals(2, budget(GlassSurfaceType.DRAWER_OR_SHEET).maxBlurLevel)
        assertEquals(1, budget(GlassSurfaceType.OVERLAY).maxBlurLevel)
        assertEquals(1, budget(GlassSurfaceType.GENERIC).maxBlurLevel)
    }

    /** Reduced 与 forceLowBudget 都强切 0 级（调用方据此早退为纯底色）。 */
    @Test
    fun reducedAndForcedLowBudgetCollapseToZeroLevel() {
        val reduced = resolveGlassBudget(
            surfaceType = GlassSurfaceType.BOTTOM_BAR,
            motionTier = MotionTier.Reduced,
            isScrolling = false,
            isTransitionRunning = false
        )
        assertEquals(0, reduced.maxBlurLevel)
        assertFalse(reduced.allowRealtime)
        assertTrue("透明度按 0.9 收敛", reduced.backgroundAlphaMultiplier < 0.88f)

        val forced = resolveGlassBudget(
            surfaceType = GlassSurfaceType.BOTTOM_BAR,
            motionTier = MotionTier.Normal,
            isScrolling = false,
            isTransitionRunning = false,
            forceLowBudget = true
        )
        assertEquals(0, forced.maxBlurLevel)
        assertFalse(forced.allowRealtime)
    }

    /** Pulsing 纪律：滚动/转场只停实时重录，等级与透明度**恒定**。 */
    @Test
    fun scrollingStopsRealtimeButKeepsLevelAndAlpha() {
        val idle = budget(GlassSurfaceType.HEADER)
        val scrolling = resolveGlassBudget(
            surfaceType = GlassSurfaceType.HEADER,
            motionTier = MotionTier.Normal,
            isScrolling = true,
            isTransitionRunning = false
        )
        assertEquals("等级不许随滚动跳变", idle.maxBlurLevel, scrolling.maxBlurLevel)
        assertEquals("透明度不许随滚动跳变", idle.backgroundAlphaMultiplier, scrolling.backgroundAlphaMultiplier, 1e-6f)
        assertFalse(scrolling.allowRealtime)

        val transition = resolveGlassBudget(
            surfaceType = GlassSurfaceType.HEADER,
            motionTier = MotionTier.Normal,
            isScrolling = false,
            isTransitionRunning = true
        )
        assertFalse(transition.allowRealtime)
        assertEquals(idle.maxBlurLevel, transition.maxBlurLevel)
    }

    /** 透明度倍率被夹在 [0.70, 1.10]；等级被夹在 [0, 2]。 */
    @Test
    fun outputsAreClamped() {
        val b = budget(GlassSurfaceType.BOTTOM_BAR)
        assertTrue(b.backgroundAlphaMultiplier in 0.70f..1.10f)
        assertTrue(b.maxBlurLevel in 0..2)
    }

    private fun budget(surface: GlassSurfaceType) = resolveGlassBudget(
        surfaceType = surface,
        motionTier = MotionTier.Normal,
        isScrolling = false,
        isTransitionRunning = false
    )
}
