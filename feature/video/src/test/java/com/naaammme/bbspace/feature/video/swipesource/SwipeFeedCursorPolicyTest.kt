package com.naaammme.bbspace.feature.video.swipesource

import com.naaammme.bbspace.core.feed.FeedCursor
import com.naaammme.bbspace.core.swipe.source.SwipeCursor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SX-04 / 决策 D-1：刷视频接力的**来源自持游标**推进规则（纯 JVM，不碰网络）。
 *
 * 三条要点：
 * ① MOBILE 档零回归 —— 推进值与改动前 `SwipeCursor(sourceId = id, idx = idx + 1)` 逐字等价；
 * ② WEB 档**不靠 idx 翻页** —— 由 `webPage` 单调 +1 驱动。这条正是上一会话"接力恒走 app 协议"
 *    那条偏离要规避的静默死循环（`idx=0` 反复拉同一页）的反面证据；
 * ③ 换来源整体重置 —— 两套分页空间不混用（由 VM 的 collector 负责，见
 *    `FeedSourceDispatchGuardTest.swipeRelayFollowsFeedSourceWithSourceOwnedCursor`）。
 */
class SwipeFeedCursorPolicyTest {

    @Test
    fun initialCursorStartsBothPaginationSpacesAtZero() {
        val cursor = SwipeFeedCursorPolicy.initial("home-feed")
        assertEquals("home-feed", cursor.sourceId)
        assertEquals(0L, cursor.idx)
        assertEquals(0, cursor.webPage)
    }

    @Test
    fun mobileAdvanceIsVerbatimTheOldIdxPlusOne() {
        // 旧实现（改动前 HomeFeedSwipeSource.next）：
        //   nextCursor = SwipeCursor(sourceId = id, idx = idx + 1)
        for (start in listOf(0L, 1L, 7L, 123L)) {
            val next = advanceRoundTrip(SwipeCursor(sourceId = "home-feed", idx = start))
            assertEquals("MOBILE 档 idx 推进必须与旧实现逐字等价", start + 1L, next.idx)
            assertEquals("home-feed", next.sourceId)
        }
    }

    @Test
    fun negativeIdxIsClampedLikeTheOldImplementation() {
        assertEquals(0L, SwipeFeedCursorPolicy.toFeedCursor(SwipeCursor(idx = -5L)).appIdx)
        assertEquals(0, SwipeFeedCursorPolicy.toFeedCursor(SwipeCursor(webPage = -3)).webPage)
        // 旧实现 `cursor.idx.coerceAtLeast(0L)` 之后 +1 -> 1
        val advanced = SwipeFeedCursorPolicy.advance(
            SwipeFeedCursorPolicy.toFeedCursor(SwipeCursor(idx = -5L))
        )
        assertEquals(1L, advanced.appIdx)
    }

    @Test
    fun webPageAdvancesMonotonicallySoWebNeverRerequestsTheSamePage() {
        var cursor = SwipeFeedCursorPolicy.initial("home-feed")
        val requestedPages = mutableListOf<Int>()
        repeat(6) {
            val feed = SwipeFeedCursorPolicy.toFeedCursor(cursor)
            requestedPages += feed.webPage
            cursor = SwipeFeedCursorPolicy.toSwipeCursor(
                SwipeFeedCursorPolicy.advance(feed),
                "home-feed"
            )
        }
        // 连续 6 页请求的页序号严格递增：任何"反复拉同一页"都会在这里红。
        assertEquals(listOf(0, 1, 2, 3, 4, 5), requestedPages)
        assertEquals(6, requestedPages.distinct().size)
    }

    @Test
    fun mergedAdvancesBothPaginationSpaces() {
        val advanced = SwipeFeedCursorPolicy.advance(
            SwipeFeedCursorPolicy.toFeedCursor(SwipeFeedCursorPolicy.initial("home-feed"))
        )
        assertEquals(1L, advanced.appIdx)
        assertEquals(1, advanced.webPage)
    }

    @Test
    fun nullNextCursorFallsBackToInitial() {
        assertEquals(FeedCursor.Initial, SwipeFeedCursorPolicy.toFeedCursorOrInitial(null))
        assertEquals(
            FeedCursor(appIdx = 3L, webPage = 2),
            SwipeFeedCursorPolicy.toFeedCursorOrInitial(SwipeCursor(idx = 3L, webPage = 2))
        )
    }

    @Test
    fun feedCursorRoundTripsThroughSwipeCursor() {
        val feed = FeedCursor(appIdx = 42L, webPage = 7)
        val back = SwipeFeedCursorPolicy.toFeedCursor(
            SwipeFeedCursorPolicy.toSwipeCursor(feed, "home-feed")
        )
        assertEquals(feed, back)
    }

    private fun advanceRoundTrip(cursor: SwipeCursor): SwipeCursor {
        val feed = SwipeFeedCursorPolicy.toFeedCursor(cursor)
        return SwipeFeedCursorPolicy.toSwipeCursor(SwipeFeedCursorPolicy.advance(feed), "home-feed")
    }
}
