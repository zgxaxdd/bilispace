package com.naaammme.bbspace.core.playback.repository

import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlayBiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放地址进程内 LRU 的两条硬约束：
 * 1. **过期**：playurl 带 `deadline`、响应用户进度回填 `resumePositionMs` —— 过期命中
 *    比不命中更糟（拿旧 URL 直接播失败 / 进度跳回旧位置），所以必须整条丢弃；
 * 2. **LRU 上限**：连播会话最多也就"当前 + 预取 3 + 回看"，缓存不能无限长。
 */
class PlayUrlCacheTest {

    private var now = 0L
    private val cache = PlayUrlCache(clock = { now })

    private fun source(title: String) = PlaybackSource(
        biz = PlayBiz.UGC,
        durationMs = 1000L,
        streams = emptyList(),
        audios = emptyList(),
        qualityOptions = emptyList(),
        resumePositionMs = null,
        isPreview = false,
        supportProject = false,
        supplementType = null,
    ).let { it }

    @Test
    fun returnsFreshEntry() {
        cache.put("k1", source("a"))
        now = 1_000L
        assertNotNull(cache.get("k1"))
    }

    @Test
    fun dropsExpiredEntry() {
        cache.put("k1", source("a"))
        now = PlayUrlCache.TTL_MS + 1
        assertNull("过期条目必须整条丢弃（旧 URL/旧进度都不该再用）", cache.get("k1"))
        assertEquals("过期的条目应当被真正移除，不占 LRU 名额", 0, cache.size())
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        repeat(PlayUrlCache.MAX_ENTRIES) { index -> cache.put("k$index", source("s$index")) }
        assertEquals(PlayUrlCache.MAX_ENTRIES, cache.size())
        // 摸一下 k0，让它变成"最近使用"
        assertNotNull(cache.get("k0"))
        // 再塞一条 → 最久未用的 k1 被淘汰
        cache.put("knew", source("new"))
        assertNull(cache.get("k1"))
        assertNotNull(cache.get("k0"))
    }

    @Test
    fun keyIncludesIdentityAndRequestFlavor() {
        val ids = com.naaammme.bbspace.core.model.VideoRequestIds(aid = 1L, bvid = "BV1", cid = 2L)
        val request = com.naaammme.bbspace.core.model.PlaybackRequest(
            playable = com.naaammme.bbspace.core.model.PlayableParams(
                ids = ids,
                biz = com.naaammme.bbspace.core.model.PlayBizInfo(PlayBiz.UGC),
            )
        )
        val hdr = PlayUrlCache.flavorOf(enableHdrAnd8k = true, preferredCodec = 2, needTrial = false, forceHost = 0)
        val noHdr = PlayUrlCache.flavorOf(enableHdrAnd8k = false, preferredCodec = 2, needTrial = false, forceHost = 0)
        val keyA = PlayUrlCache.keyOf(request, hdr)
        val keyB = PlayUrlCache.keyOf(request, noHdr)
        assertTrue("同一视频同一设置 → 同键（预取才能被开播命中）", keyA == PlayUrlCache.keyOf(request, hdr))
        assertTrue(
            "影响响应内容的设置变了必须换键：否则会命中「缺 HDR/8K 流」的旧结果",
            keyA != keyB
        )
    }

    @Test
    fun ttlIsDeliberatelyShorterThanBiliPai() {
        assertTrue(
            "TTL 必须明显短于 BiliPai 的 10 分钟：我们要的是「预取窗口内有效」，" +
                "太长会让重新打开同一支视频时用到陈旧的 resumePositionMs。",
            PlayUrlCache.TTL_MS in 30_000L..180_000L
        )
    }
}
