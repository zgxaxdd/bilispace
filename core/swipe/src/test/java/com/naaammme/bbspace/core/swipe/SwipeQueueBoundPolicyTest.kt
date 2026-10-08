package com.naaammme.bbspace.core.swipe

import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连播队列条数上限的守卫（报告 P1-8③ / §2.1.7）。
 *
 * 两条都必须成立：
 * 1. 上限**真的生效**（否则队列无界，去重的逐对比较会随会话时长线性变慢）；
 * 2. **绝不截断当前页及其之前**（`VerticalPager` 以页号为身份，头部截断会让用户
 *    正在看的那一页变成别的视频——比"队列长"严重得多的事故）。
 */
class SwipeQueueBoundPolicyTest {

    private val targetStub = VideoTarget.Ugc(aid = 1L, cid = 1L, bvid = "BV1")

    private fun entry(key: String) = SwipeEntry(
        target = targetStub,
        bvid = key,
        title = key,
        cover = "",
        author = null,
        durationText = null
    )

    private fun entries(n: Int) = (1..n).map { entry("BV$it") }

    @Test
    fun underLimitIsUntouched() {
        val list = entries(10)
        assertSame(
            "没到上限时必须原样返回（不要产生新列表，避免无谓的 pager 失效）",
            list,
            SwipeQueueBoundPolicy.bound(list, currentPage = 0, maxEntries = 200)
        )
    }

    @Test
    fun tailIsTrimmedAtLimit() {
        val bounded = SwipeQueueBoundPolicy.bound(entries(500), currentPage = 10, maxEntries = 200)
        assertEquals(200, bounded.size)
        assertEquals("头部必须保留（保住页号语义）", "BV1", bounded.first().bvid)
        assertEquals("截的是尾部", "BV200", bounded.last().bvid)
    }

    @Test
    fun currentPageAndEverythingBeforeItSurvives() {
        // 极端：当前页已经超过上限 —— 此时保留条数必须是 currentPage+1，
        // 否则 pager 的 currentPage 会越界。
        val bounded = SwipeQueueBoundPolicy.bound(entries(500), currentPage = 300, maxEntries = 200)
        assertEquals(301, bounded.size)
        assertEquals("BV301", bounded.last().bvid)
        assertTrue("当前页（下标 300）必须还在", bounded.size > 300)
    }

    @Test
    fun zeroOrNegativeLimitDisablesBounding() {
        val list = entries(500)
        assertSame(list, SwipeQueueBoundPolicy.bound(list, currentPage = 0, maxEntries = 0))
    }
}
