package com.naaammme.bbspace.core.rank

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RankMappers 纯函数单测：字段映射对照 PiliPlus HotVideoItemModel / PgcRankItemModel。
 * JVM 下 org.json 由 testImplementation("org.json:json") 提供真实实现。
 */
class RankMappersTest {

    @Test
    fun `video item maps all fields`() {
        val json = JSONObject(
            """
            {
              "aid": 123, "bvid": "BV1xx", "cid": 456,
              "title": "标题", "pic": "http://i0.hdslb.com/x.jpg",
              "duration": 210, "tname": "动画",
              "owner": {"mid": 789, "name": "UP", "face": "http://face"},
              "stat": {"view": 100000, "danmaku": 500}
            }
            """
        )
        val item = mapRankVideo(json)!!
        assertEquals(123L, item.aid)
        assertEquals("BV1xx", item.bvid)
        assertEquals(456L, item.cid)
        assertEquals("标题", item.title)
        // http -> https 升级
        assertEquals("https://i0.hdslb.com/x.jpg", item.cover)
        assertEquals(210L, item.durationSec)
        assertEquals("动画", item.tname)
        assertEquals(789L, item.ownerMid)
        assertEquals("UP", item.ownerName)
        assertEquals(100000L, item.view)
        assertEquals(500L, item.danmaku)
    }

    @Test
    fun `video item falls back to danmu field`() {
        val json = JSONObject("""{"aid":1,"stat":{"view":9,"danmu":42}}""")
        val item = mapRankVideo(json)!!
        assertEquals(42L, item.danmaku)
    }

    @Test
    fun `redirect entries without aid are dropped`() {
        val json = JSONObject("""{"title":"pgc","redirect_url":"https://www.bilibili.com/bangumi/play/ss1"}""")
        assertNull(mapRankVideo(json))
    }

    @Test
    fun `blank optional fields become null`() {
        val json = JSONObject("""{"aid":1,"bvid":"","tname":"","pic":""}""")
        val item = mapRankVideo(json)!!
        assertNull(item.bvid)
        assertNull(item.tname)
        assertNull(item.cover)
    }

    @Test
    fun `video list skips invalid entries`() {
        val list = JSONArray(
            """[{"aid":1,"title":"a"},{"no_aid":1},{"aid":2,"title":"b"}]"""
        )
        val mapped = mapRankVideoList(list)
        assertEquals(2, mapped.size)
        assertEquals(listOf(1L, 2L), mapped.map { it.aid })
    }

    @Test
    fun `pgc item parses season and ep id from url`() {
        val json = JSONObject(
            """
            {
              "title": "番剧", "cover": "https://cover",
              "url": "https://www.bilibili.com/bangumi/play/ss33436",
              "new_ep": {"index_show": "连载至第5话"},
              "stat": {"follow": 100, "view": 200}
            }
            """
        )
        val item = mapRankPgc(json)!!
        assertEquals(33436L, item.seasonId)
        assertNull(item.epId)
        assertEquals("连载至第5话", item.indexShow)
        assertEquals(100L, item.follow)
        assertEquals(200L, item.view)
    }

    @Test
    fun `pgc item parses ep url`() {
        val json = JSONObject("""{"title":"t","url":"https://www.bilibili.com/bangumi/play/ep789"}""")
        val item = mapRankPgc(json)!!
        assertEquals(789L, item.epId)
        assertNull(item.seasonId)
    }

    @Test
    fun `pgc item without title is dropped`() {
        assertNull(mapRankPgc(JSONObject("""{"url":"x"}""")))
    }

    @Test
    fun `pgc list handles null`() {
        assertEquals(0, mapRankPgcList(null).size)
    }
}
