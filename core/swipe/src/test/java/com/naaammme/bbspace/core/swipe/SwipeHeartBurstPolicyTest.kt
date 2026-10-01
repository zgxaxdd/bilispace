package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 爱心粒子纯策略（SW-30 验收：粒子数与位置由种子决定、边界安全）。
 */
class SwipeHeartBurstPolicyTest {

    @Test
    fun `same seed yields same particles and different seeds differ`() {
        val a = SwipeHeartBurstPolicy.particles(seed = 42L)
        val b = SwipeHeartBurstPolicy.particles(seed = 42L)
        val c = SwipeHeartBurstPolicy.particles(seed = 43L)
        assertEquals(SwipeHeartBurstPolicy.PARTICLE_COUNT, a.size)
        assertEquals("同种子必须完全一致（可复现）", a, b)
        assertTrue("不同种子应给出不同分布", a != c)
        assertEquals(emptyList<SwipeHeartBurstPolicy.Particle>(), SwipeHeartBurstPolicy.particles(1L, count = 0))
    }

    @Test
    fun `particles are bounded and spread across the full circle`() {
        val particles = SwipeHeartBurstPolicy.particles(seed = 7L, count = 12)
        particles.forEach { p ->
            assertTrue("角度越界", p.angleDeg >= 0f && p.angleDeg < 720f)
            assertTrue("距离越界", p.distanceFraction in 0f..SwipeHeartBurstPolicy.MAX_DISTANCE_FRACTION)
            assertTrue("尺度越界", p.scale in 0.5f..1.2f)
            assertTrue("延迟越界", p.delayFraction in 0f..0.35f)
        }
        // 覆盖到上/下/左/右四个象限（不是挤在一侧）
        fun quadrant(deg: Float): Int = (((deg % 360f) + 360f) % 360f / 90f).toInt()
        assertTrue(particles.map { quadrant(it.angleDeg) }.toSet().size >= 3)
    }

    @Test
    fun `offset starts at origin during delay then expands monotonically`() {
        val p = SwipeHeartBurstPolicy.Particle(
            angleDeg = 0f, distanceFraction = 0.2f, scale = 1f, delayFraction = 0.3f
        )
        // 延迟期内：原地
        assertEquals(0f to 0f, SwipeHeartBurstPolicy.particleOffset(p, 0f))
        assertEquals(0f to 0f, SwipeHeartBurstPolicy.particleOffset(p, 0.3f))
        // 之后：沿角度方向单调外扩（0 度 = 向右、y 向上）
        val (x1, y1) = SwipeHeartBurstPolicy.particleOffset(p, 0.65f)
        val (x2, y2) = SwipeHeartBurstPolicy.particleOffset(p, 1f)
        assertTrue(x1 > 0f && x2 > x1)
        assertEquals(0f, y1, 1e-5f)
        assertEquals(0f, y2, 1e-5f)
        // 终点等于最大位移
        assertEquals(p.distanceFraction, x2, 1e-5f)
    }

    @Test
    fun `alpha envelope fades in then out and is bounded`() {
        assertEquals(0f, SwipeHeartBurstPolicy.particleAlpha(0f), 1e-6f)
        assertEquals(1f, SwipeHeartBurstPolicy.particleAlpha(0.4f), 1e-6f)
        assertEquals(0f, SwipeHeartBurstPolicy.particleAlpha(1f), 1e-6f)
        (0..20).forEach { i ->
            val a = SwipeHeartBurstPolicy.particleAlpha(i / 20f)
            assertTrue("alpha 越界 @$i", a in 0f..1f)
        }
    }

    @Test
    fun `main heart pulses once and ends at rest`() {
        val start = SwipeHeartBurstPolicy.mainHeartScale(0f)
        val peak = SwipeHeartBurstPolicy.mainHeartScale(0.35f)
        val end = SwipeHeartBurstPolicy.mainHeartScale(1f)
        assertTrue("起手应已有可见尺寸（不从 0 冒出来）", start in 0.3f..0.5f)
        assertEquals(SwipeHeartBurstPolicy.PEAK_SCALE, peak, 1e-4f)
        assertEquals(1f, end, 1e-4f)
        // 单调冲高后单调回落
        assertTrue(SwipeHeartBurstPolicy.mainHeartScale(0.2f) < peak)
        assertTrue(SwipeHeartBurstPolicy.mainHeartScale(0.7f) < peak)
        // 完成判据
        assertFalse(SwipeHeartBurstPolicy.isDone(0.99f))
        assertTrue(SwipeHeartBurstPolicy.isDone(1f))
        // 主心尾部淡出
        assertEquals(1f, SwipeHeartBurstPolicy.mainHeartAlpha(0.5f), 1e-6f)
        assertEquals(0f, SwipeHeartBurstPolicy.mainHeartAlpha(1f), 1e-6f)
    }
}
