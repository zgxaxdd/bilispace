package com.naaammme.bbspace.feature.video.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列补货的「凑满」判据（对照 BiliPai `PortraitVideoPager.kt:1341-1412`）。
 *
 * 为什么值得单测：单页推荐流去重后往往只进 2~4 条，"拉一页就收工"会让队列很快见底
 * （用户滑到尽头 = "没有下一个视频了"）。这个循环的上界与目标值就是体验底线，
 * 写错会退化成"要么只拉一页、要么无限拉流烧流量"。
 */
class SwipeRefillPolicyTest {

    @Test
    fun keepsFetchingUntilTargetReached() {
        // 第一页只进 3 条 → 还要继续拉
        assertTrue(shouldFetchNextRefillPage(attempts = 1, appended = 3))
        // 第二页进到 6 条 → 还没到 8
        assertTrue(shouldFetchNextRefillPage(attempts = 2, appended = 6))
        // 已有 8 条 → 收工
        assertFalse(shouldFetchNextRefillPage(attempts = 1, appended = 8))
        assertFalse(shouldFetchNextRefillPage(attempts = 1, appended = 9))
    }

    @Test
    fun stopsAtMaxAttemptsEvenIfStillShort() {
        // 拉满 3 页还没凑够也必须停：否则"推荐流全是已看过的"会变成无限翻页
        assertFalse(shouldFetchNextRefillPage(attempts = 3, appended = 2))
        assertFalse(shouldFetchNextRefillPage(attempts = 4, appended = 0))
    }

    @Test
    fun firstAttemptAlwaysAllowedWhenQueueEmpty() {
        assertTrue(shouldFetchNextRefillPage(attempts = 0, appended = 0))
    }

    @Test
    fun rejectsNonsenseInput() {
        assertFalse(shouldFetchNextRefillPage(attempts = -1, appended = 0))
        assertFalse(shouldFetchNextRefillPage(attempts = 0, appended = -1))
    }

    @Test
    fun constantsMatchBiliPai() {
        assertEquals("最多拉 3 页", 3, SWIPE_REFILL_MAX_ATTEMPTS)
        assertEquals("目标净增 8 条", 8, SWIPE_REFILL_TARGET)
    }
}
