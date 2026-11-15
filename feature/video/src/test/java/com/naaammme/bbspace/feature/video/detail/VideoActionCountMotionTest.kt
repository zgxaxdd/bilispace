package com.naaammme.bbspace.feature.video.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 互动计数动效判据（上游 `3eb92ea04` → `VideoActionCountMotionPolicyTest.kt` 逐条移植）。
 *
 * 为什么值得钉：这段判据决定"数字动不动"。判错两头的代价都不小 ——
 * 判松了，进详情页/服务端回填时三个数字一起跳（上游明确要消除的观感）；
 * 判紧了，用户点了赞却看不到任何反馈。
 */
class VideoActionCountMotionTest {

    @Test
    fun hydrationAndBackgroundRefreshDoNotAnimate() {
        // 首次组合 / 打开详情后服务端回填：没有任何本地请求 ⇒ 静止。
        assertFalse(shouldAnimateVideoActionCountChange("0", "120", 0, 0, false))
        // 同一次本地请求已被消费：后续后台刷新不再动。
        assertFalse(shouldAnimateVideoActionCountChange("120", "121", 1, 1, false))
    }

    @Test
    fun localCountChangeCanAnimateOnceIncludingZeroToOne() {
        assertTrue(shouldAnimateVideoActionCountChange("120", "121", 1, 0, false))
        // "点赞"是未拿到计数时的占位文本；从占位变数字也要有反馈。
        assertTrue(shouldAnimateVideoActionCountChange("点赞", "1", 1, 0, false))
        assertTrue(shouldAnimateVideoActionCountChange("1", "点赞", 2, 1, false))
        // 同一次请求不能动两次。
        assertFalse(shouldAnimateVideoActionCountChange("121", "122", 1, 1, false))
    }

    @Test
    fun formattedCountThatDidNotChangeAndPlainLabelsStayStill() {
        // 1.2万 → 1.2万：文本没变 ⇒ 不动（但原始计数变化仍会消费请求，见 VideoActionCountText）。
        assertFalse(shouldAnimateVideoActionCountChange("1.2万", "1.2万", 1, 0, false))
        // 纯文字状态（投币 → 已投）不含数字 ⇒ 不做数字弹跳。
        assertFalse(shouldAnimateVideoActionCountChange("投币", "已投", 1, 0, false))
    }

    @Test
    fun reducedMotionDisablesCountMovement() {
        assertFalse(shouldAnimateVideoActionCountChange("120", "121", 1, 0, true))
    }

    @Test
    fun armingFavoriteDoesNotArmLikeOrCoin() {
        val requests = VideoActionCountRequests()
        requests.requestFavorite()
        assertFalse(shouldAnimateVideoActionCountChange("10", "11", requests.like, 0, false))
        assertFalse(shouldAnimateVideoActionCountChange("10", "11", requests.coin, 0, false))
        assertTrue(shouldAnimateVideoActionCountChange("10", "11", requests.favorite, 0, false))
    }

    @Test
    fun tripleArmsAllThreeCounts() {
        val requests = VideoActionCountRequests()
        requests.requestTriple()
        assertEquals(1, requests.like)
        assertEquals(1, requests.coin)
        assertEquals(1, requests.favorite)
        assertTrue(shouldAnimateVideoActionCountChange("10", "11", requests.like, 0, false))
        assertTrue(shouldAnimateVideoActionCountChange("10", "11", requests.coin, 0, false))
        assertTrue(shouldAnimateVideoActionCountChange("10", "11", requests.favorite, 0, false))
    }
}
