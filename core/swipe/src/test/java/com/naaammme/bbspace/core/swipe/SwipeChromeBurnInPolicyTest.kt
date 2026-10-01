package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防烧屏策略不变式：静置阈值边界、变暗幅度、漂移的有界性/确定性/周期性、
 * 时钟回拨防御。这些数字直接决定"会不会烧屏"与"会不会被用户看见在抖"。
 */
class SwipeChromeBurnInPolicyTest {

    @Test
    fun `idle boundary is inclusive and clock rollback counts as active`() {
        val last = 1_000L
        assertFalse(SwipeChromeBurnInPolicy.isIdle(last, last))
        // 差一秒不满阈值：不判静置
        assertFalse(
            SwipeChromeBurnInPolicy.isIdle(
                last + SwipeChromeBurnInPolicy.IDLE_DIM_AFTER_MS - 1, last
            )
        )
        // 正好到阈值：判静置（含边界）
        assertTrue(
            SwipeChromeBurnInPolicy.isIdle(
                last + SwipeChromeBurnInPolicy.IDLE_DIM_AFTER_MS, last
            )
        )
        // 时钟回拨（now < last）：按"刚交互过"，不误判
        assertFalse(SwipeChromeBurnInPolicy.isIdle(last - 5_000L, last))
    }

    @Test
    fun `alpha dims only when idle and never below threshold value`() {
        val last = 0L
        assertEquals(1f, SwipeChromeBurnInPolicy.chromeAlpha(10_000L, last), 1e-6f)
        assertEquals(
            SwipeChromeBurnInPolicy.DIM_ALPHA,
            SwipeChromeBurnInPolicy.chromeAlpha(SwipeChromeBurnInPolicy.IDLE_DIM_AFTER_MS, last),
            1e-6f
        )
        // 用户要求"防烧屏"但不隐藏：变暗幅度必须仍然看得见
        assertTrue(SwipeChromeBurnInPolicy.DIM_ALPHA in 0.4f..0.8f)
    }

    @Test
    fun `drift is bounded deterministic and changes once per period`() {
        val period = SwipeChromeBurnInPolicy.DRIFT_PERIOD_MS
        val step = SwipeChromeBurnInPolicy.DRIFT_STEP_PX

        // 同一周期内恒定（不会逐帧抖）
        val mid = period / 2
        assertEquals(
            SwipeChromeBurnInPolicy.driftOffsetPx(mid),
            SwipeChromeBurnInPolicy.driftOffsetPx(mid + 1)
        )
        // 有界：任一时刻的位移不超过一档
        (0L..12L).forEach { cycle ->
            val (x, y) = SwipeChromeBurnInPolicy.driftOffsetPx(cycle * period + 1)
            assertTrue("x 越界 at cycle=$cycle", x >= -step && x <= step)
            assertTrue("y 越界 at cycle=$cycle", y >= -step && y <= step)
        }
        // 周期是 9（3×3 相位）后回到原点：确定性且不长期停在同一格
        val start = SwipeChromeBurnInPolicy.driftOffsetPx(0L)
        assertEquals(0f to 0f, start)
        val afterCycle = SwipeChromeBurnInPolicy.driftOffsetPx(9L * period + 1)
        assertEquals(start, afterCycle)
        // 一个周期内确实有位移发生过（否则防烧屏无效）
        val moved = (1L..8L).any { SwipeChromeBurnInPolicy.driftOffsetPx(it * period + 1) != start }
        assertTrue("漂移从未发生", moved)
    }

    @Test
    fun `drift tolerates degenerate inputs`() {
        assertEquals(0f to 0f, SwipeChromeBurnInPolicy.driftOffsetPx(nowMs = 0L, periodMs = 0L))
        assertEquals(0f to 0f, SwipeChromeBurnInPolicy.driftOffsetPx(nowMs = -5L))
    }
}
