package com.naaammme.bbspace.feature.video.swipe

import com.naaammme.bbspace.core.model.VideoTarget
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

    // ===== 兜底（拉满页数仍不足 → 用队尾候选的详情 relates 补）=====

    @Test
    fun fallbackRunsOnlyWhenStillShort() {
        assertTrue("一条没补到 → 必须走兜底", shouldRunSwipeRefillFallback(appended = 0))
        assertTrue("差一点 → 也走兜底", shouldRunSwipeRefillFallback(appended = 7))
        assertFalse("已凑满 → 不必再拉一次详情", shouldRunSwipeRefillFallback(appended = 8))
        assertFalse(shouldRunSwipeRefillFallback(appended = 20))
    }

    @Test
    fun fallbackTargetIsTheLastUsableEntry() {
        fun entry(bvid: String, aid: Long) = SwipeEntry(
            target = VideoTarget.Ugc(aid = aid, cid = 1L, bvid = bvid),
            bvid = bvid,
            title = "t$bvid",
            cover = "c$bvid",
            author = null,
            durationText = null
        )
        // 队尾是用户下一个会滑到的位置：补厚它后面的内容才有意义
        val queue = listOf(
            entry("BV1", 101L),
            entry("BV2", 0L), // 查不了（aid 缺失）→ 跳过
            entry("BV3", 103L),
            entry("", 0L) // 同样查不了
        )
        assertEquals("BV3", selectSwipeRefillFallbackTarget(queue)?.bvid)
        assertEquals(
            "全都没 aid → 没有可兜底的目标",
            null,
            selectSwipeRefillFallbackTarget(listOf(entry("", 0L)))
        )
        assertEquals(null, selectSwipeRefillFallbackTarget(emptyList()))
    }
}
