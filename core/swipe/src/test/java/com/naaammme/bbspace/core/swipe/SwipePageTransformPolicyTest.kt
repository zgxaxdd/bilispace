package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页动效纯策略（SW-10 验收：offset → scale/alpha 单调、边界、reduceMotion）。
 * 幅度纪律也在这里锁死：缩放 ≤2%（与交接落定同量级）、不透明度有下限。
 */
class SwipePageTransformPolicyTest {

    @Test
    fun `scale and alpha degrade monotonically with distance`() {
        val center = SwipePageTransformPolicy.transformFor(0f)
        val quarter = SwipePageTransformPolicy.transformFor(0.25f)
        val half = SwipePageTransformPolicy.transformFor(0.5f)
        val full = SwipePageTransformPolicy.transformFor(1f)

        // 正中：无变换
        assertEquals(1f, center.scale, 1e-6f)
        assertEquals(1f, center.alpha, 1e-6f)
        // 单调：越远越小
        assertTrue(quarter.scale < center.scale && half.scale < quarter.scale && full.scale < half.scale)
        assertTrue(quarter.alpha < center.alpha && half.alpha < quarter.alpha && full.alpha < half.alpha)
        // 幅度纪律：缩放差 ≤2%；alpha 不低于下限
        assertTrue(1f - full.scale <= 0.02f + 1e-6f)
        assertEquals(SwipePageTransformPolicy.MIN_ALPHA, full.alpha, 1e-6f)
    }

    @Test
    fun `transform is symmetric for both directions`() {
        val up = SwipePageTransformPolicy.transformFor(-0.4f)
        val down = SwipePageTransformPolicy.transformFor(0.4f)
        assertEquals(up, down)
    }

    @Test
    fun `out-of-range and NaN are clamped`() {
        assertEquals(SwipePageTransformPolicy.transformFor(-1f), SwipePageTransformPolicy.transformFor(-3f))
        assertEquals(SwipePageTransformPolicy.transformFor(1f), SwipePageTransformPolicy.transformFor(2.5f))
        assertEquals(SwipePageTransformPolicy.Transform.None, SwipePageTransformPolicy.transformFor(Float.NaN))
    }

    @Test
    fun `reduceMotion degrades to no transform`() {
        assertEquals(
            SwipePageTransformPolicy.Transform.None,
            SwipePageTransformPolicy.transformFor(0.8f, reduceMotion = true)
        )
        assertEquals(
            SwipePageTransformPolicy.Transform.None,
            SwipePageTransformPolicy.transformWithOverScroll(0.5f, rawOverScrollPx = 200f, reduceMotion = true)
        )
    }

    @Test
    fun `over scroll is damped and capped`() {
        // 阻尼折减
        assertEquals(
            100f * SwipePageTransformPolicy.OVERSCROLL_DAMPING,
            SwipePageTransformPolicy.overScrollTranslation(100f),
            1e-4f
        )
        // 封顶（正负都封）
        assertEquals(
            SwipePageTransformPolicy.MAX_OVERSCROLL_PX,
            SwipePageTransformPolicy.overScrollTranslation(10_000f),
            1e-4f
        )
        assertEquals(
            -SwipePageTransformPolicy.MAX_OVERSCROLL_PX,
            SwipePageTransformPolicy.overScrollTranslation(-10_000f),
            1e-4f
        )
        // 非法输入不产生位移
        assertEquals(0f, SwipePageTransformPolicy.overScrollTranslation(Float.NaN), 1e-6f)
        assertEquals(0f, SwipePageTransformPolicy.overScrollTranslation(100f, damping = 0f), 1e-6f)
    }

    @Test
    fun `transform with over scroll combines both`() {
        val t = SwipePageTransformPolicy.transformWithOverScroll(
            offsetFraction = 0.5f,
            rawOverScrollPx = 40f
        )
        assertEquals(SwipePageTransformPolicy.transformFor(0.5f).scale, t.scale, 1e-6f)
        assertTrue(t.translationYPx != 0f)
    }
}
