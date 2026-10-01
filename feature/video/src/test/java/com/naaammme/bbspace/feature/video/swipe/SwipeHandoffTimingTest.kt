package com.naaammme.bbspace.feature.video.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷视频「封面 → 画面」交接的时序判据。
 *
 * 这条时序没有可声称的帧率数字（无设备），但它的**形状**是可以机器判定的：
 * 暗层必须在内容淡化之前退完、交叉淡化不得比暗层退场更快、动效开关必须真的生效。
 * 这三条一旦回退，真机上就是同一个"翻页闪一下黑"，而代码看起来仍然"有动画"。
 */
class SwipeHandoffTimingTest {

    // 默认令牌档（createAnimations(ON) 的实际值）：short=100 / medium=200
    private val default = SwipeHandoffTiming.resolve(shortMs = 100, mediumMs = 200, reduceMotion = false)

    @Test
    fun crossFadeIsSlowerThanChromeExitSoTheMidpointIsNeverDarkerThanBothEnds() {
        // 整层一起淡的问题就出在"暗层和内容同速退场"。分两段之后，暗层退完内容才开始走。
        assertTrue(
            "封面交叉淡化必须比暗层退场慢，否则暗层又被带进了交接中段：$default",
            default.coverFadeMs > default.chromeFadeMs
        )
        assertEquals(100, default.chromeFadeMs)
        assertEquals(200, default.coverFadeMs)
    }

    @Test
    fun coverHoldsBeforeTheDissolveToStabiliseTheJustRenderedFrame() {
        // 停留 = 短档的一半：够刚出帧的画面稳住，又不至于读成"卡在封面上"。
        assertEquals(50, default.coverHoldMs)
        assertTrue(default.coverHoldMs < default.coverFadeMs)
    }

    @Test
    fun wholeHandoffStaysShortEnoughToReadAsOneMotion() {
        // 交接不是一段"动画"，是一次翻页的落定。超过半秒就会读成"上一个还挂着"。
        assertTrue(
            "整段交接 ${default.totalMs}ms 过长（chrome ${default.chromeFadeMs} + " +
                "hold ${default.coverHoldMs} + fade ${default.coverFadeMs}）",
            default.totalMs in 1..500
        )
    }

    @Test
    fun settleScaleIsAppliedOnlyWhenMotionIsOn() {
        assertEquals(SwipeHandoffTiming.SETTLE_SCALE, default.settleScale, 0f)
        // 2% 量级：再大就会被读成变焦而不是落定
        assertTrue(default.settleScale > 1f && default.settleScale <= 1.05f)

        val still = SwipeHandoffTiming.resolve(shortMs = 100, mediumMs = 200, reduceMotion = true)
        assertEquals("动效关闭时不做缩放（缩放是有时长的过程，不可控）", 1f, still.settleScale, 0f)
    }

    @Test
    fun reduceMotionKeepsTheOrderButDropsTheDurations() {
        val still = SwipeHandoffTiming.resolve(shortMs = 100, mediumMs = 200, reduceMotion = true)
        assertEquals(1, still.chromeFadeMs)
        assertEquals(0, still.coverHoldMs)
        assertEquals(1, still.coverFadeMs)
        // 1ms 而不是 0：与 createAnimations 的既有约定一致，避开时长为 0 的边界
        assertTrue(still.totalMs >= 2)
    }

    @Test
    fun durationsFollowTheAnimationSpeedTokens() {
        // 用户的「动效速度」设置通过令牌传进来：令牌放大，交接必须跟着变慢（不能写死）
        val slow = SwipeHandoffTiming.resolve(shortMs = 200, mediumMs = 400, reduceMotion = false)
        assertTrue(slow.totalMs > default.totalMs)
        assertEquals(200, slow.chromeFadeMs)
        assertEquals(100, slow.coverHoldMs)
        assertEquals(400, slow.coverFadeMs)
    }

    @Test
    fun zeroSizedTokensProduceNoNegativeDurations() {
        val edge = SwipeHandoffTiming.resolve(shortMs = 0, mediumMs = 0, reduceMotion = false)
        assertTrue(edge.chromeFadeMs >= 0 && edge.coverFadeMs >= 0 && edge.coverHoldMs >= 0)
        assertEquals(0, edge.totalMs)
        // 负数令牌（不该出现，但要钉住"不会算出负时长"）
        val negative = SwipeHandoffTiming.resolve(shortMs = -50, mediumMs = -10, reduceMotion = false)
        assertEquals(0, negative.totalMs)
    }
}
