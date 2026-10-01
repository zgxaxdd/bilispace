package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑马灯判据（SW-32 验收：仅当文案超宽才滚动；reduceMotion 不滚；节拍有界）。
 */
class SwipeMarqueePolicyTest {

    @Test
    fun `marquee only when text overflows container`() {
        assertTrue(SwipeMarqueePolicy.shouldMarquee(320f, 200f, reduceMotion = false))
        // 不超宽：不滚（短名字不该动）
        assertFalse(SwipeMarqueePolicy.shouldMarquee(180f, 200f, reduceMotion = false))
        assertFalse(SwipeMarqueePolicy.shouldMarquee(200f, 200f, reduceMotion = false))
        // 降级：不滚
        assertFalse(SwipeMarqueePolicy.shouldMarquee(320f, 200f, reduceMotion = true))
        // 异常输入：不滚
        assertFalse(SwipeMarqueePolicy.shouldMarquee(Float.NaN, 200f, reduceMotion = false))
        assertFalse(SwipeMarqueePolicy.shouldMarquee(320f, 0f, reduceMotion = false))
        assertFalse(SwipeMarqueePolicy.shouldMarquee(0f, 200f, reduceMotion = false))
    }

    @Test
    fun `travel distance equals overflow and is never negative`() {
        assertEquals(120f, SwipeMarqueePolicy.travelDistancePx(320f, 200f), 1e-4f)
        assertEquals(0f, SwipeMarqueePolicy.travelDistancePx(150f, 200f), 1e-4f)
        assertEquals(0f, SwipeMarqueePolicy.travelDistancePx(Float.NaN, 200f), 1e-4f)
    }

    @Test
    fun `travel duration is speed derived and clamped`() {
        // 112px / 28px per sec = 4s
        assertEquals(4_000L, SwipeMarqueePolicy.travelDurationMs(112f))
        // 极短距离仍有下限（不至于"闪一下"）
        assertEquals(SwipeMarqueePolicy.MIN_TRAVEL_MS, SwipeMarqueePolicy.travelDurationMs(1f))
        // 极长距离有上限（长名字也不至于滚半分钟）
        assertEquals(SwipeMarqueePolicy.MAX_TRAVEL_MS, SwipeMarqueePolicy.travelDurationMs(10_000f))
        assertEquals(SwipeMarqueePolicy.MIN_TRAVEL_MS, SwipeMarqueePolicy.travelDurationMs(0f))
    }

    @Test
    fun `full cycle is hold plus travel twice`() {
        val distance = 112f
        val travel = SwipeMarqueePolicy.travelDurationMs(distance)
        assertEquals(
            SwipeMarqueePolicy.HOLD_MS * 2 + travel * 2,
            SwipeMarqueePolicy.fullCycleMs(distance)
        )
        // 循环时长有下界，避免"没停稳就回程"的抽动感
        assertTrue(SwipeMarqueePolicy.fullCycleMs(1f) >= SwipeMarqueePolicy.HOLD_MS * 2 + 1_200L)
    }
}
