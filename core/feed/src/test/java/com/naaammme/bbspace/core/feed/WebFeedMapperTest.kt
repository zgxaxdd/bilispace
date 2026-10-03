package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * PT-10 V4/V5：WEB 响应 → `FeedItem` 的逐字段映射（纯 JVM，方案 §6）。
 *
 * V5 单独钉「web item 无 dislikeContext」—— 这是 WEB 档的功能性限制，
 * 不是 bug；锁住它是为了防止后人"顺手补上"一个假 context 让"不感兴趣"看起来能用。
 */
class WebFeedMapperTest {

    private val fullItem = JSONObject(
        """
        {
          "id": 114514,
          "bvid": "BV1xx411c7mD",
          "cid": 998877,
          "goto": "av",
          "uri": "bilibili://video/114514",
          "pic": "//i0.hdslb.com/bfs/archive/cover.jpg",
          "title": "一个标题",
          "duration": 754,
          "pubdate": 1700000000,
          "owner": { "mid": 42, "name": "UP主", "face": "//i1.hdslb.com/face.jpg" },
          "stat": { "view": 12345, "like": 1, "danmaku": 2, "reply": 3 },
          "tid": 4,
          "tname": "游戏",
          "dimension": { "width": 1920, "height": 1080, "rotate": 0 }
        }
        """.trimIndent()
    )

    @Test
    fun mapsEveryDocumentedField() {
        val item = WebFeedMapper.mapItem(fullItem)
            ?: error("完整字段的 web item 不该被丢弃")

        assertEquals(WebFeedMapper.WEB_CARD_TYPE, item.cardType)
        assertEquals("av", item.cardGoto)
        assertEquals("av", item.goto)
        // id(=aid) → param：点击路由依赖它
        assertEquals("114514", item.param)
        assertEquals("一个标题", item.title)
        // 协议相对地址必须补成 https（任务书 §4.3 说 httpsImageUrl() 能处理，实际不能）
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", item.cover)
        // duration → 角标左（时长，与 rank 同格式）
        assertEquals("12:34", item.coverLeftText1)
        // stat.view → 角标右（播放量，用既有 x.x万 口径）
        assertEquals("1.2万", item.coverRightText)
        assertNull(item.coverLeftText2)
        // web 无 idx 游标 → 恒为 0（翻页由 FeedCursor.webPage 承担）
        assertEquals(0L, item.idx)
        assertNull(item.liveRoute)
        assertNull(item.rcmdReason)
        assertNull(item.threePointV2)
        assertEquals("UP主", item.descButton?.text)
        assertEquals(42L, item.args?.upId)
        assertEquals("UP主", item.args?.upName)
        assertEquals(4, item.args?.tid)
        assertEquals("游戏", item.args?.tname)
        assertEquals(114514L, item.args?.aid)

        val ugc = item.target as? VideoTarget.Ugc ?: error("web 视频卡必须是 Ugc target")
        assertEquals(114514L, ugc.aid)
        assertEquals(998877L, ugc.cid)
        assertEquals("BV1xx411c7mD", ugc.bvid)
    }

    @Test
    fun webItemCarriesNoDislikeContext() {
        val item = WebFeedMapper.mapItem(fullItem) ?: error("item 不该为空")
        assertNull(
            "V5：web item 没有三连点上下文 → dislikeContext 必须为 null，" +
                "否则首页会提供一个点了必然失败/串参数的「不感兴趣」。",
            item.dislikeContext
        )
    }

    @Test
    fun missingFieldsDoNotCrash() {
        val minimal = JSONObject("""{"id": 7, "title": "只有ID"}""")
        val item = WebFeedMapper.mapItem(minimal) ?: error("有 id 就该能映射")
        assertEquals("7", item.param)
        assertEquals("只有ID", item.title)
        assertEquals("", item.cover)
        assertNull(item.coverLeftText1)
        assertNull(item.coverRightText)
        assertNull(item.descButton)
        assertNull(item.dislikeContext)
        val ugc = item.target as? VideoTarget.Ugc ?: error("aid>0 应能构造 Ugc")
        assertEquals(7L, ugc.aid)
        assertEquals(0L, ugc.cid)
        assertNull(ugc.bvid)
    }

    @Test
    fun itemWithoutAnyIdentifierIsSkipped() {
        assertNull(WebFeedMapper.mapItem(JSONObject("""{"title":"没有 id 也没有 bvid"}""")))
    }

    @Test
    fun bvidOnlyItemIsKept() {
        val item = WebFeedMapper.mapItem(JSONObject("""{"bvid":"BV1xx411c7mD"}"""))
            ?: error("只有 bvid 也应保留（有 bvid 就能路由）")
        assertEquals("0", item.param)
        assertTrue(item.target == null)
    }

    @Test
    fun codeNonZeroThrowsInsteadOfSilentEmpty() {
        val json = JSONObject("""{"code":-412,"message":"请求被拦截"}""")
        val error = runCatching { WebFeedMapper.mapResponse(json) }.exceptionOrNull()
        assertTrue(
            "风控/限流（code != 0）必须抛错并带上 code，不允许静默返回空列表：" +
                "静默空列表会被当成「推荐流没内容」，排查时看不到任何线索。",
            error != null && error.message.orEmpty().contains("-412")
        )
    }

    @Test
    fun emptyDataYieldsEmptyList() {
        assertTrue(WebFeedMapper.mapResponse(JSONObject("""{"code":0}""")).isEmpty())
        assertTrue(WebFeedMapper.mapResponse(JSONObject("""{"code":0,"data":{}}""")).isEmpty())
    }

    @Test
    fun protocolRelativeUrlIsMadeAbsolute() {
        assertEquals("https://i0.hdslb.com/x.jpg", WebFeedMapper.normalizeWebUrl("//i0.hdslb.com/x.jpg"))
        assertEquals("https://i0.hdslb.com/x.jpg", WebFeedMapper.normalizeWebUrl("http://i0.hdslb.com/x.jpg"))
        assertEquals("https://i0.hdslb.com/x.jpg", WebFeedMapper.normalizeWebUrl("https://i0.hdslb.com/x.jpg"))
        assertEquals("", WebFeedMapper.normalizeWebUrl(""))
    }

    // ===== 角标格式化：与 feature/rank/RankScreen.kt 同口径 =====

    @Test
    fun durationFormatting() {
        assertNull(WebFeedMapper.formatDuration(0))
        assertNull(WebFeedMapper.formatDuration(-5))
        assertEquals("0:59", WebFeedMapper.formatDuration(59))
        assertEquals("1:00", WebFeedMapper.formatDuration(60))
        assertEquals("12:34", WebFeedMapper.formatDuration(754))
        assertEquals("1:00:00", WebFeedMapper.formatDuration(3_600))
        assertEquals("2:03:04", WebFeedMapper.formatDuration(7_384))
    }

    @Test
    fun viewCountFormattingMatchesRank() {
        assertNull(WebFeedMapper.formatViewCount(0))
        assertNull(WebFeedMapper.formatViewCount(-1))
        assertEquals("9999", WebFeedMapper.formatViewCount(9_999))
        assertEquals("1万", WebFeedMapper.formatViewCount(10_000))
        assertEquals("1.2万", WebFeedMapper.formatViewCount(12_345))
        assertEquals("10万", WebFeedMapper.formatViewCount(100_000))
        assertEquals("1亿", WebFeedMapper.formatViewCount(100_000_000))
        assertEquals("3.4亿", WebFeedMapper.formatViewCount(345_678_901))
    }
}
