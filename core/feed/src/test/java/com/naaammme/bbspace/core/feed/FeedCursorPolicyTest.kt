package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.settings.FeedApiType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PT-10 V6：游标推进（纯 JVM，方案 §6）。
 *
 * 这是全方案唯一"容易静默错"的地方 —— 错的表现是翻页重复 / 跳页。
 * 三条不变式：
 * 1. WEB 档连续翻页 `webPage` 单调 +1，且 `appIdx` 恒为 0（不串用两套语义）；
 * 2. MOBILE 档 `appIdx` 按列表项推进、`webPage` 恒为 0（与历史行为一致）；
 * 3. MERGED 档两套游标**各自独立**推进（web 页序号 +1，app 游标跟随列表末项）。
 */
class FeedCursorPolicyTest {

    private val initial = FeedCursor.Initial

    @Test
    fun webPagesAdvanceMonotonically() {
        var cursor = FeedCursorPolicy.refreshCursor(FeedApiType.WEB, appIdx = 999L)
        assertEquals("WEB 刷新必须回到第 0 页，且不带 app 游标", FeedCursor(0L, 0), cursor)

        val pages = mutableListOf<Int>()
        repeat(3) {
            cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.WEB, cursor, appIdx = 12345L)
            pages += cursor.webPage
            assertEquals("WEB 档不得携带 app 游标", 0L, cursor.appIdx)
        }
        assertEquals(listOf(1, 2, 3), pages)
    }

    @Test
    fun webRefreshResetsPageToZero() {
        val atPage4 = FeedCursor(appIdx = 0L, webPage = 4)
        val refreshed = FeedCursorPolicy.refreshCursor(FeedApiType.WEB, appIdx = 555L)
        assertEquals(0, refreshed.webPage)
        assertEquals("重置与上一游标无关，不串页", FeedCursor(0L, 0), refreshed)
        assertEquals("确认上一游标没被就地改写", 4, atPage4.webPage)
    }

    @Test
    fun mobileKeepsHistoricalIndexSemantics() {
        val refresh = FeedCursorPolicy.refreshCursor(FeedApiType.MOBILE, appIdx = 120L)
        assertEquals(FeedCursor(appIdx = 120L, webPage = 0), refresh)
        val more = FeedCursorPolicy.loadMoreCursor(FeedApiType.MOBILE, refresh, appIdx = 240L)
        assertEquals(FeedCursor(appIdx = 240L, webPage = 0), more)
    }

    @Test
    fun mergedAdvancesBothCursorsIndependently() {
        var cursor = FeedCursorPolicy.refreshCursor(FeedApiType.MERGED, appIdx = 0L)
        assertEquals(FeedCursor(0L, 0), cursor)
        cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.MERGED, cursor, appIdx = 100L)
        assertEquals(FeedCursor(appIdx = 100L, webPage = 1), cursor)
        cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.MERGED, cursor, appIdx = 200L)
        assertEquals(FeedCursor(appIdx = 200L, webPage = 2), cursor)
        // app 游标来回变动不影响 web 页序号推进
        cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.MERGED, cursor, appIdx = 100L)
        assertEquals(FeedCursor(appIdx = 100L, webPage = 3), cursor)
    }

    @Test
    fun initialCursorIsZeroed() {
        assertEquals(FeedCursor(0L, 0), initial)
    }
}
