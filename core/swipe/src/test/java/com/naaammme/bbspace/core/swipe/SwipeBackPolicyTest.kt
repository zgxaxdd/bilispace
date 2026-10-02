package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话内 back 优先级判据（用户反馈「看完评论顺手侧滑 → 直接退出到推荐页」的修复依据）：
 * 面板开着只关面板、刚关过面板吞一次、其余放行。
 */
class SwipeBackPolicyTest {

    @Test
    fun `open sheet takes priority over everything`() {
        assertEquals(
            SwipeBackPolicy.Action.CloseSheet,
            SwipeBackPolicy.resolve(anySheetOpen = true, inSheetCloseGrace = true)
        )
        assertEquals(
            SwipeBackPolicy.Action.CloseSheet,
            SwipeBackPolicy.resolve(anySheetOpen = true, inSheetCloseGrace = false)
        )
    }

    @Test
    fun `grace window swallows the habitual second swipe`() {
        assertEquals(
            SwipeBackPolicy.Action.Swallow,
            SwipeBackPolicy.resolve(anySheetOpen = false, inSheetCloseGrace = true)
        )
    }

    @Test
    fun `otherwise passes through to the host`() {
        assertEquals(
            SwipeBackPolicy.Action.PassThrough,
            SwipeBackPolicy.resolve(anySheetOpen = false, inSheetCloseGrace = false)
        )
        // 宽限窗口是"短"的：否则连正常退出都会被吞
        assertEquals(400L, SwipeBackPolicy.SHEET_CLOSE_GRACE_MS)
    }
}
