package com.naaammme.bbspace.core.playback.repository

import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.PlayBizInfo
import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlayableParams
import com.naaammme.bbspace.core.model.VideoRequestIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-01：`PlayUrlCache` 的**按键前缀失效**能力。
 *
 * ## 这条能力解决的真实缺陷
 * 重试的语义是"再取一次流"，但取流入口是缓存优先、TTL 90s，而自动重试的退避是
 * 1s / 3s —— 重试必然命中缓存、拿回同一份失效 URL（URL 失效 / 403 / 404 / 签名过期
 * 这类失败结构性地不可能成功）。所以重试前必须失效该视频的缓存。
 *
 * ## 为什么用前缀
 * 键里带 `flavor`（HDR/编码/试看/forceHost 摘要）→ 只删"精确键"会在"用户切了清晰度后
 * 重试"时失效不干净。前缀取 `aid#` 覆盖同一支视频的全部 flavor。
 */
class PlayUrlCacheInvalidationTest {

    private var now = 0L
    private val cache = PlayUrlCache(clock = { now })

    private fun request(aid: Long, cid: Long = 7L) = PlaybackRequest(
        playable = PlayableParams(
            ids = VideoRequestIds(aid = aid, bvid = "BV$aid", cid = cid),
            biz = PlayBizInfo(PlayBiz.UGC),
        )
    )

    private fun source() = PlaybackSource(
        biz = PlayBiz.UGC,
        durationMs = 1_000L,
        streams = emptyList(),
        audios = emptyList(),
        qualityOptions = emptyList(),
        resumePositionMs = null,
        isPreview = false,
        supportProject = false,
        supplementType = null,
    )

    private val hdr = PlayUrlCache.flavorOf(enableHdrAnd8k = true, preferredCodec = 2, needTrial = false, forceHost = 0)
    private val sdr = PlayUrlCache.flavorOf(enableHdrAnd8k = false, preferredCodec = 2, needTrial = false, forceHost = 0)

    /** 验收 1：TTL 内 put 后按前缀失效 → get 必须为 null。 */
    @Test
    fun invalidateByPrefixDropsFreshEntry() {
        val target = request(aid = 100L)
        val key = PlayUrlCache.keyOf(target, sdr)
        cache.put(key, source())
        now = 1_000L
        assertNotNull("前提：TTL 内本可以命中", cache.get(key))

        val removed = cache.invalidateByPrefix(PlayUrlCache.prefixOf(target))
        assertEquals("应当删掉 1 条", 1, removed)
        assertNull("失效后必须拿不到（重试才能真的重新取流）", cache.get(key))
        assertEquals(0, cache.size())
        assertTrue("TTL 内也不该再命中", now < PlayUrlCache.TTL_MS)
    }

    /** 前缀覆盖**所有 flavor**：切了清晰度再重试也要失效干净。 */
    @Test
    fun invalidateByPrefixCoversEveryFlavor() {
        val target = request(aid = 100L)
        cache.put(PlayUrlCache.keyOf(target, hdr), source())
        cache.put(PlayUrlCache.keyOf(target, sdr), source())

        val removed = cache.invalidateByPrefix(PlayUrlCache.prefixOf(target))
        assertEquals("同一视频的两个 flavor 都要删", 2, removed)
        assertNull(cache.get(PlayUrlCache.keyOf(target, hdr)))
        assertNull(cache.get(PlayUrlCache.keyOf(target, sdr)))
    }

    /** 验收 2：前缀不匹配的条目**不被误删**。 */
    @Test
    fun invalidateByPrefixKeepsOtherVideos() {
        val victim = request(aid = 100L)
        val neighbor = request(aid = 101L)
        val victimKey = PlayUrlCache.keyOf(victim, sdr)
        val neighborKey = PlayUrlCache.keyOf(neighbor, sdr)
        cache.put(victimKey, source())
        cache.put(neighborKey, source())

        val removed = cache.invalidateByPrefix(PlayUrlCache.prefixOf(victim))
        assertEquals(1, removed)
        assertNull(cache.get(victimKey))
        assertNotNull("相邻页的预取结果不能被误删（否则连播起播白预热）", cache.get(neighborKey))
    }

    /** 同 aid 不同 cid（同一支视频的多个分 P）会被一起失效 —— 这是刻意的：前缀就是 `aid#`。 */
    @Test
    fun invalidateByPrefixCoversAllCidsOfTheSameAid() {
        val p1 = request(aid = 100L, cid = 7L)
        val p2 = request(aid = 100L, cid = 8L)
        cache.put(PlayUrlCache.keyOf(p1, sdr), source())
        cache.put(PlayUrlCache.keyOf(p2, sdr), source())

        assertEquals(2, cache.invalidateByPrefix(PlayUrlCache.prefixOf(p1)))
        assertEquals(0, cache.size())
    }

    @Test
    fun invalidateByPrefixOnMissReturnsZeroAndChangesNothing() {
        val key = PlayUrlCache.keyOf(request(aid = 100L), sdr)
        cache.put(key, source())
        assertEquals(0, cache.invalidateByPrefix("999#"))
        assertNotNull(cache.get(key))
    }

    /**
     * **单一真值源**：`prefixOf` 必须真的是 `keyOf` 产出键的前缀。
     *
     * 这一条防的是"有人改了 [PlayUrlCache.keyOf] 的拼接顺序却忘了改 prefixOf"——
     * 那时前缀会静默失配（`invalidateByPrefix` 返回 0），**缺陷回到原样且无任何报错**。
     */
    @Test
    fun prefixIsDerivedFromTheSameKeyLayout() {
        val request = request(aid = 12345L, cid = 678L)
        val key = PlayUrlCache.keyOf(request, hdr)
        assertTrue(
            "prefixOf 必须是 keyOf 的前缀（key=$key prefix=${PlayUrlCache.prefixOf(request)}）",
            key.startsWith(PlayUrlCache.prefixOf(request))
        )
        assertEquals("12345#", PlayUrlCache.prefixOf(request))
    }

    /**
     * 验收 7（默认行为不变）：TTL / 容量是刻意的值，PB-01 **不动它们**。
     *
     * 任务书 §5 已判定"调大 TTL"是假改进（会长时间缓存陈旧观看进度）→ 这里把两个常量
     * 钉住，防止后续有人顺手改。
     */
    @Test
    fun ttlAndCapacityAreUnchanged() {
        assertEquals(90_000L, PlayUrlCache.TTL_MS)
        assertEquals(16, PlayUrlCache.MAX_ENTRIES)
    }
}
