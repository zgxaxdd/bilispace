package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.settings.FeedApiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-10 V6：游标推进（纯 JVM，方案 §6）。
 *
 * 这是全方案唯一"容易静默错"的地方 —— 错的表现是翻页重复 / 跳页。
 * 三条不变式：
 * 1. WEB 档连续翻页 `webPage` 单调 +1，且 `appIdx` **跟随列表末项**（不串用两套语义
 *    = 取流只看 webPage；但 app 游标必须可推进，见下）；
 * 2. MOBILE 档 `appIdx` 按列表项推进、`webPage` 恒为 0（与历史行为一致）；
 * 3. MERGED 档两套游标**各自独立**推进（web 页序号 +1，app 游标跟随列表末项）。
 *
 * ## B-3 / L-2：为什么不变式 1 的措辞在 2026-10-04 变了
 * 原口径是"WEB 档 `appIdx` 恒为 0"，而那正是缺陷本身：WBI 密钥取不到时
 * `fetchWebFeed` 回退 `fetchMobile(cursor.appIdx)`，恒 0 ⇒ 每次"加载更多"都拉
 * app 协议第 0 页 ⇒ 分页原地踏步。修复后 app 游标跟着列表末项走，且由
 * [webFallbackAdvancesAppCursor] 钉住"回退不会原地踏步"。
 */
class FeedCursorPolicyTest {

    private val initial = FeedCursor.Initial

    @Test
    fun webPagesAdvanceMonotonically() {
        var cursor = FeedCursorPolicy.refreshCursor(FeedApiType.WEB, appIdx = 999L)
        assertEquals("WEB 刷新必须回到第 0 页", FeedCursor(0L, 0), cursor)

        val pages = mutableListOf<Int>()
        repeat(3) {
            cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.WEB, cursor, appIdx = 12345L)
            pages += cursor.webPage
            assertEquals("WEB 翻页必须带上 app 游标（WBI 失败回退用）", 12345L, cursor.appIdx)
        }
        assertEquals(listOf(1, 2, 3), pages)
    }

    /**
     * B-3/L-2 的回归钉：模拟"WEB 档 + WBI 一直失败"——
     * 第二次"加载更多"用的 app 游标必须**不等于**第一次（否则就是原地踏步）。
     */
    @Test
    fun webFallbackAdvancesAppCursor() {
        var cursor = FeedCursorPolicy.refreshCursor(FeedApiType.WEB, appIdx = 0L)
        // 第一次回退：列表尾部还是 web 条目（FeedItem.idx 恒 0）→ app 第 0 页，
        // 这一页回来的 app 条目自带真实 idx（取 391 作样本）。
        cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.WEB, cursor, appIdx = 0L)
        val firstFallbackIdx = cursor.appIdx
        cursor = FeedCursorPolicy.loadMoreCursor(FeedApiType.WEB, cursor, appIdx = 391L)
        assertTrue(
            "WEB 档回退 MOBILE 时 app 游标必须推进：第一次=$firstFallbackIdx 第二次=${cursor.appIdx}",
            cursor.appIdx != firstFallbackIdx
        )
        assertEquals(391L, cursor.appIdx)
        assertEquals(2, cursor.webPage)
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
