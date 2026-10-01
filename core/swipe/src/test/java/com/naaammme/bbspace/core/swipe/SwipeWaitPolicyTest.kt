package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * veil 退场策略四类路径（SW-11 验收）：首帧早到 / 首帧晚到 / 失败 / 中途切页。
 * 默认 minVisibleMs=0 时全部退化为"首帧即撤"——行为零变化的编译期保证。
 */
class SwipeWaitPolicyTest {

    @Test
    fun `first frame arrives early - top up to min visible`() {
        // veil 展示了 100ms，min=400：还差 300ms（只补足，不把 min 重算一遍）
        assertEquals(
            300L,
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = true,
                visibleSinceMs = 0L,
                nowMs = 100L,
                minVisibleMs = 400L
            )
        )
        // 临界：正好 min → 立即撤
        assertEquals(
            0L,
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = true,
                visibleSinceMs = 0L,
                nowMs = 400L,
                minVisibleMs = 400L
            )
        )
    }

    @Test
    fun `first frame arrives late - retire immediately`() {
        // 慢网：veil 已展示 1.2s 才出帧（min=400）→ 已等待 ≥ min → 0
        assertEquals(
            0L,
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = true,
                visibleSinceMs = 0L,
                nowMs = 1_200L,
                minVisibleMs = 400L
            )
        )
    }

    @Test
    fun `failure keeps the veil - error state renders inside it`() {
        // 失败不撤 veil（历史回归防线）：错误态 + 重试按钮就在 veil 里，
        // 撤掉就是"纯黑 + 大暂停键"。retireDelayMs 必须返回 null（不撤），
        // 无论 min 是多少、等待了多久。
        assertNull(
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = false,
                visibleSinceMs = 0L,
                nowMs = 10L,
                minVisibleMs = 400L,
                playbackFailed = true
            )
        )
        assertNull(
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = true,
                visibleSinceMs = 0L,
                nowMs = 5_000L,
                minVisibleMs = 400L,
                playbackFailed = true
            )
        )
    }

    @Test
    fun `page switch resets the wait window`() {
        // 中途切页：调用方把 visibleSinceMs 换成新页的起点——上一页的已等待时长
        // 不得结转（上一页等了 350ms、新页刚展示 50ms → 仍要补 350ms）
        assertEquals(
            350L,
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = true,
                visibleSinceMs = 1_000L,
                nowMs = 1_050L,
                minVisibleMs = 400L
            )
        )
        // 不在等待态（无起点）：不撤（null 由调用方按"没在等"处理）
        assertNull(
            SwipeWaitPolicy.retireDelayMs(
                firstFrameRendered = true,
                visibleSinceMs = null,
                nowMs = 0L,
                minVisibleMs = 400L
            )
        )
    }

    @Test
    fun `default min of zero degenerates to retire-on-first-frame`() {
        // 编译期默认（MIN_VEIL_VISIBLE_MS_DEFAULT = 0）：任何情况下首帧即撤 = 现状行为
        assertEquals(0L, SwipeWaitPolicy.MIN_VEIL_VISIBLE_MS_DEFAULT)
        assertEquals(0L, SwipeWaitPolicy.retireDelayMs(firstFrameRendered = true, visibleSinceMs = 0L, nowMs = 0L))
        // 首帧未到仍然不撤
        assertNull(SwipeWaitPolicy.retireDelayMs(firstFrameRendered = false, visibleSinceMs = 0L, nowMs = 0L))
    }
}
