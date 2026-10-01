package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载层三态判据（SW-34 验收）：优先级、可见性、错误态、粒子宽度档。
 */
class SwipeLoadPhasePolicyTest {

    @Test
    fun `failure wins over loading states`() {
        // 失败优先：即便还在"等首帧"，也要走错误态（错误不能不可见）
        assertEquals(
            SwipeLoadPhase.Error,
            SwipeLoadPhasePolicy.resolve(waitingFirstFrame = true, failed = true, hasCover = true)
        )
        assertEquals(
            SwipeLoadPhase.Error,
            SwipeLoadPhasePolicy.resolve(waitingFirstFrame = false, failed = true, hasCover = false)
        )
    }

    @Test
    fun `loading splits by cover availability`() {
        assertEquals(
            SwipeLoadPhase.LoadingWithCover,
            SwipeLoadPhasePolicy.resolve(waitingFirstFrame = true, failed = false, hasCover = true)
        )
        assertEquals(
            SwipeLoadPhase.LoadingWithoutCover,
            SwipeLoadPhasePolicy.resolve(waitingFirstFrame = true, failed = false, hasCover = false)
        )
    }

    @Test
    fun `hidden when neither waiting nor failed`() {
        assertEquals(
            SwipeLoadPhase.Hidden,
            SwipeLoadPhasePolicy.resolve(waitingFirstFrame = false, failed = false, hasCover = true)
        )
    }

    @Test
    fun `visibility and error helpers match phases`() {
        assertFalse(SwipeLoadPhasePolicy.isVeilVisible(SwipeLoadPhase.Hidden))
        assertTrue(SwipeLoadPhasePolicy.isVeilVisible(SwipeLoadPhase.LoadingWithCover))
        assertTrue(SwipeLoadPhasePolicy.isVeilVisible(SwipeLoadPhase.LoadingWithoutCover))
        assertTrue("错误态也长在加载层里", SwipeLoadPhasePolicy.isVeilVisible(SwipeLoadPhase.Error))
        assertTrue(SwipeLoadPhasePolicy.isError(SwipeLoadPhase.Error))
        assertFalse(SwipeLoadPhasePolicy.isError(SwipeLoadPhase.LoadingWithCover))
    }

    @Test
    fun `particle width picks the smaller tier when a cover is behind it`() {
        // 有封面：走小一档（封面是主角）
        assertEquals(
            0.4f,
            SwipeLoadPhasePolicy.particleWidthFraction(
                SwipeLoadPhase.LoadingWithCover, coverFraction = 0.4f, plainFraction = 0.7f
            ),
            1e-6f
        )
        // 无封面：粒子是唯一内容，用大档
        assertEquals(
            0.7f,
            SwipeLoadPhasePolicy.particleWidthFraction(
                SwipeLoadPhase.LoadingWithoutCover, coverFraction = 0.4f, plainFraction = 0.7f
            ),
            1e-6f
        )
    }
}
