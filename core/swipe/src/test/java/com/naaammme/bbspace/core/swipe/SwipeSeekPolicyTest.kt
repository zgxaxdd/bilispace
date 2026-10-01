package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可拖进度条判据（SW-15 验收项）：热区边界、位置映射与夹取、提交语义、
 * 本地刷新节流、时间气泡文案。
 */
class SwipeSeekPolicyTest {

    @Test
    fun `hot zone covers the bottom band only`() {
        // 屏底 6% 带内：命中
        assertTrue(SwipeSeekPolicy.isInHotZone(0.99f))
        assertTrue(SwipeSeekPolicy.isInHotZone(0.95f))
        assertTrue(SwipeSeekPolicy.isInHotZone(1f - SwipeSeekPolicy.HOT_ZONE_FRACTION))
        // 带外（屏幕中部/上部）：不命中——不与翻页抢手势
        assertFalse(SwipeSeekPolicy.isInHotZone(0.93f))
        assertFalse(SwipeSeekPolicy.isInHotZone(0.5f))
        // 非法值：不命中
        assertFalse(SwipeSeekPolicy.isInHotZone(-0.01f))
        assertFalse(SwipeSeekPolicy.isInHotZone(1.01f))
        assertFalse(SwipeSeekPolicy.isInHotZone(0.5f, hotZoneFraction = 0f))
        assertFalse(SwipeSeekPolicy.isInHotZone(0.5f, hotZoneFraction = 2f))
    }

    @Test
    fun `position maps fraction to ms and clamps`() {
        assertEquals(0L, SwipeSeekPolicy.positionForFraction(0f, 120_000L))
        assertEquals(60_000L, SwipeSeekPolicy.positionForFraction(0.5f, 120_000L))
        assertEquals(120_000L, SwipeSeekPolicy.positionForFraction(1f, 120_000L))
        // 超界夹取（拖动出屏也不越界）
        assertEquals(120_000L, SwipeSeekPolicy.positionForFraction(1.4f, 120_000L))
        assertEquals(0L, SwipeSeekPolicy.positionForFraction(-0.4f, 120_000L))
        // 时长未就绪：不给目标（宁可不出 seek）
        assertNull(SwipeSeekPolicy.positionForFraction(0.5f, 0L))
        assertNull(SwipeSeekPolicy.positionForFraction(Float.NaN, 120_000L))
    }

    @Test
    fun `only release commits the seek`() {
        // 拖动中不提交（任务书要求④：拖动结束才提交）
        assertFalse(SwipeSeekPolicy.shouldCommitOnRelease(isScrubbing = false))
        assertTrue(SwipeSeekPolicy.shouldCommitOnRelease(isScrubbing = true))
    }

    @Test
    fun `local position refresh is throttled but first call always passes`() {
        assertTrue(SwipeSeekPolicy.shouldRefreshLocalPosition(nowMs = 1_000L, lastRefreshMs = null))
        assertFalse(
            SwipeSeekPolicy.shouldRefreshLocalPosition(
                nowMs = 1_030L, lastRefreshMs = 1_000L
            )
        )
        assertTrue(
            SwipeSeekPolicy.shouldRefreshLocalPosition(
                nowMs = 1_000L + SwipeSeekPolicy.LOCAL_UPDATE_MIN_INTERVAL_MS,
                lastRefreshMs = 1_000L
            )
        )
        // 时钟回拨：放行（宁可多刷一次，别卡住不动）
        assertTrue(SwipeSeekPolicy.shouldRefreshLocalPosition(nowMs = 900L, lastRefreshMs = 1_000L))
    }

    @Test
    fun `bubble text formats and handles unknown duration`() {
        assertEquals("1:05 / 2:00", SwipeSeekPolicy.bubbleText(65_000L, 120_000L))
        assertEquals("0:00 / 0:30", SwipeSeekPolicy.bubbleText(0L, 30_000L))
        // 时长未知：给占位而不是错数字
        assertEquals("--:--", SwipeSeekPolicy.bubbleText(65_000L, 0L))
        // 负数位置按 0 处理（拖动出屏的边界）
        assertEquals("0:00 / 0:30", SwipeSeekPolicy.bubbleText(-500L, 30_000L))
    }

    @Test
    fun `scrubbing suppresses auto advance`() {
        // 拖动中：即便其它条件全满足也不翻页（SW-15：别把用户的操作打断并丢弃）
        assertFalse(
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = true,
                repeatOne = false,
                isLastPage = false,
                alreadyAdvancedForThisVideo = false,
                isScrubbing = true
            )
        )
        assertTrue(
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = true,
                repeatOne = false,
                isLastPage = false,
                alreadyAdvancedForThisVideo = false,
                isScrubbing = false
            )
        )
    }
}
