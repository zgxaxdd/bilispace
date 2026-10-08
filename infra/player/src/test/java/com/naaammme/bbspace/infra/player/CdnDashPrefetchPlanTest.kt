package com.naaammme.bbspace.infra.player

import com.naaammme.bbspace.infra.player.cdn.CDN_WINDOW_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DASH 分段预取计划（[buildCdnDashPrefetchPlan]）的 JVM 测试。
 *
 * 这三条判据是"预取白下"的高发点，而且**全是静默失败**（没有异常、没有日志）：
 * 1. **缓存键**：写进去的键必须与播放侧 `PlaybackCacheKeyFactory` 算出的键**逐字相同**
 *    （`media:<path>`）。照抄源仓库的 `bili-cdn-track:<hash>` 就没命中了；
 * 2. **索引区间**：sidx 的绝对起点 + 长度 → `Range: bytes=start-end`，
 *    `end = start + length - 1`（差一位就会让 `readExactCdnRange` 的精确校验失败）；
 * 3. **不合法就返回 null**（宁可预取不做，也不能拿错区间去发请求）。
 */
class CdnDashPrefetchPlanTest {

    private fun plan(
        candidates: List<String>,
        indexStart: Long,
        indexLength: Long,
        fallbackUrl: String = ""
    ) = buildCdnDashPrefetchPlan(
        candidates = candidates,
        indexStart = indexStart,
        indexLength = indexLength,
        fallbackUrl = fallbackUrl
    )

    @Test
    fun cacheKeyMatchesThePlaybackSideKeyFactory() {
        val url = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/567.m4s?deadline=1&sign=abc"
        val built = plan(listOf(url), indexStart = 200_000L, indexLength = 1_024L)
        assertEquals(
            "预取写入的缓存键必须与播放读取用的键一致（丢 host/scheme/query 的 media:<path>）",
            playbackCacheKeyForPath("/upgcxcode/12/34/567.m4s", url),
            built?.cacheKey
        )
        assertEquals("media:/upgcxcode/12/34/567.m4s", built?.cacheKey)
    }

    @Test
    fun indexRangeIsInclusiveAndExact() {
        val built = plan(listOf("https://a.bilivideo.com/x.m4s"), indexStart = 91_536L, indexLength = 240L)
        assertEquals("起点照抄 sidx 绝对偏移", 91_536L, built?.indexRange?.start)
        assertEquals("终点 = 起点 + 长度 - 1（闭区间）", 91_775L, built?.indexRange?.endInclusive)
        assertEquals(240L, built?.indexRange?.length)
    }

    @Test
    fun candidatesAreTrimmedDeduplicatedAndPrimaryFirst() {
        val built = plan(
            candidates = listOf("  ", "https://a.bilivideo.com/x.m4s?sign=1", "https://b.bilivideo.com/x.m4s?sign=2", "https://a.bilivideo.com/x.m4s?sign=1"),
            indexStart = 10L,
            indexLength = 8L
        )
        assertEquals(2, built?.candidates?.size)
        assertEquals("主线路保持在首位（预取赢家从它开始排）", "https://a.bilivideo.com/x.m4s?sign=1", built?.candidates?.first())
    }

    @Test
    fun fallsBackToTheSourceUrlWhenCandidatesAreMissing() {
        val built = plan(emptyList(), indexStart = 4L, indexLength = 4L, fallbackUrl = "https://c.bilivideo.com/y.m4s")
        assertEquals(listOf("https://c.bilivideo.com/y.m4s"), built?.candidates)
        assertEquals("media:/y.m4s", built?.cacheKey)
    }

    @Test
    fun rejectsUnusableIndexInputs() {
        assertNull("没有 sidx 长度 ⇒ 不预取", plan(listOf("https://a.bilivideo.com/x.m4s"), 0L, 0L))
        assertNull("负起点 ⇒ 不预取", plan(listOf("https://a.bilivideo.com/x.m4s"), -1L, 8L))
        assertNull(
            "超过单窗口上限（1MiB）⇒ 不预取（readExactCdnRange 会直接拒绝）",
            plan(listOf("https://a.bilivideo.com/x.m4s"), 0L, CDN_WINDOW_BYTES + 1L)
        )
        assertNull("连兜底 URL 都没有 ⇒ 不预取", plan(emptyList(), 0L, 8L, fallbackUrl = "  "))
        assertTrue("合法输入必须给出计划", plan(listOf("https://a.bilivideo.com/x.m4s"), 0L, 8L) != null)
    }
}
