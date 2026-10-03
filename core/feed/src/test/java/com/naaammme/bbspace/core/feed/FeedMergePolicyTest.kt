package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-10 V7：MERGED 档的交错合并（纯 JVM，方案 §6）。
 *
 * 语义逐字移植上游 `HomeFeedMergePolicy.mergeFeeds`：app 在前、按索引交替、
 * 按视频去重、一方耗尽后追加剩余。交错顺序写死在这里，是因为"web 全部在前"
 * 会让首页前几屏全是网页风格内容（方案 §4.5）。
 */
class FeedMergePolicyTest {

    private fun appItem(aid: Long, bvid: String? = null): FeedItem = feedItem(aid, bvid)
    private fun webItem(aid: Long, bvid: String? = null): FeedItem = feedItem(aid, bvid, web = true)

    private fun feedItem(aid: Long, bvid: String?, web: Boolean = false) = FeedItem(
        cardType = if (web) WebFeedMapper.WEB_CARD_TYPE else "small_cover_v2",
        cardGoto = "av",
        goto = "av",
        param = aid.toString(),
        uri = "bilibili://video/$aid",
        title = "t$aid",
        cover = "",
        coverLeftText1 = null,
        coverLeftText2 = null,
        coverRightText = null,
        idx = if (web) 0L else aid,
        target = VideoTarget.Ugc(aid = aid, cid = 0L, bvid = bvid),
        liveRoute = null,
        descButton = null,
        rcmdReason = null,
        args = null,
        threePointV2 = null,
        dislikeContext = null
    )

    @Test
    fun interleavesAppFirstThenWeb() {
        val app = listOf(appItem(1), appItem(2), appItem(3))
        val web = listOf(webItem(11), webItem(12))
        val merged = FeedMergePolicy.mergeFeeds(web = web, app = app)
        assertEquals(listOf(1L, 11L, 2L, 12L, 3L), merged.map { (it.target as VideoTarget.Ugc).aid })
    }

    @Test
    fun dedupesByBvidAcrossSources() {
        val app = listOf(appItem(1, "BV_A"), appItem(2, "BV_B"))
        val web = listOf(webItem(99, "BV_B"), webItem(100, "BV_C"))
        val merged = FeedMergePolicy.mergeFeeds(web = web, app = app)
        // 交错顺序：app1(BV_A) → web99(BV_B) → app2(BV_B, 重复丢弃) → web100(BV_C)
        assertEquals(
            "同 bvid 只保留首次出现；app 半边在交错里先到，所以保留 app 的那条",
            listOf(1L, 99L, 100L),
            merged.map { (it.target as VideoTarget.Ugc).aid }
        )
    }

    @Test
    fun dedupesByAidWhenBvidMissing() {
        val app = listOf(appItem(5))
        val web = listOf(webItem(5))
        val merged = FeedMergePolicy.mergeFeeds(web = web, app = app)
        assertEquals(1, merged.size)
        assertEquals("保留 app 侧条目（交错顺序 app 在前）", "small_cover_v2", merged.single().cardType)
    }

    @Test
    fun singleSideFailureReturnsSurvivingSide() {
        val app = listOf(appItem(1), appItem(2))
        // 模拟 web 半边失败：调用方传入空列表
        assertEquals(app, FeedMergePolicy.mergeFeeds(web = emptyList(), app = app))
        val web = listOf(webItem(7))
        assertEquals(web, FeedMergePolicy.mergeFeeds(web = web, app = emptyList()))
    }

    @Test
    fun bothEmptyYieldsEmpty() {
        assertTrue(FeedMergePolicy.mergeFeeds(web = emptyList(), app = emptyList()).isEmpty())
    }

    @Test
    fun resultContainsNoDuplicateKeys() {
        val app = listOf(appItem(1, "BV_A"), appItem(2, "BV_B"), appItem(3, "BV_C"))
        val web = listOf(webItem(1, "BV_A"), webItem(4, "BV_D"), webItem(2, "BV_B"))
        val merged = FeedMergePolicy.mergeFeeds(web = web, app = app)
        val keys = merged.map(FeedMergePolicy::keyOf)
        assertEquals("合并结果不允许有重复 key", keys.size, keys.toSet().size)
        assertEquals(
            "bvid 优先做 key：结果含两边的 bvid",
            setOf("BV_A", "BV_B", "BV_C", "BV_D"),
            merged.mapNotNull { (it.target as VideoTarget.Ugc).bvid }.toSet()
        )
    }

    @Test
    fun keyPrefersBvidThenAidThenParam() {
        assertEquals("bvid:BV_A", FeedMergePolicy.keyOf(appItem(1, "BV_A")))
        assertEquals("aid:2", FeedMergePolicy.keyOf(appItem(2)))
        val noTarget = feedItem(3, null).copy(target = null)
        assertEquals("param:3", FeedMergePolicy.keyOf(noTarget))
    }
}
