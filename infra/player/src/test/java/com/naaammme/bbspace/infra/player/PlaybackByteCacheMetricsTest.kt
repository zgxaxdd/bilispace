package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 字节缓存计数（N2）。
 *
 * 这里唯一会写错、且写错之后**只在生产上表现**的一条是：
 * `onCachedBytesRead` 给的是"自上次回调以来"的增量（Media3 通知后自己清零），
 * 把它当累计值赋值进去，命中率就会永远等于"最后一次读取的大小"。
 */
class PlaybackByteCacheMetricsTest {

    @Before
    fun resetCounters() {
        PlaybackByteCacheMetrics.reset()
    }

    @Test
    fun cachedBytesAccumulateRatherThanOverwrite() {
        PlaybackByteCacheMetrics.recordCachedBytesRead(1_000L)
        PlaybackByteCacheMetrics.recordCachedBytesRead(2_500L)
        assertEquals(3_500L, PlaybackByteCacheMetrics.cachedBytes())
    }

    @Test
    fun nonPositiveReadsDoNotMoveTheCounter() {
        // Media3 只在 totalCachedBytesRead > 0 时才回调，但计数器的契约应当自洽：
        // 0 与负值都不许让它"看起来在涨"。
        PlaybackByteCacheMetrics.recordCachedBytesRead(500L)
        PlaybackByteCacheMetrics.recordCachedBytesRead(0L)
        PlaybackByteCacheMetrics.recordCachedBytesRead(-1L)
        assertEquals(500L, PlaybackByteCacheMetrics.cachedBytes())
    }

    @Test
    fun ignoredReadsAreCountedSeparatelyFromHits() {
        // "压根没走缓存"与"读了没命中"是两件事，合并会把"闸门关掉缓存"误读成"缓存没用"。
        PlaybackByteCacheMetrics.recordCacheIgnored()
        PlaybackByteCacheMetrics.recordCacheIgnored()
        PlaybackByteCacheMetrics.recordCachedBytesRead(10L)
        assertEquals(2L, PlaybackByteCacheMetrics.ignoredCount())
        assertEquals(10L, PlaybackByteCacheMetrics.cachedBytes())
    }

    @Test
    fun resetClearsBothCounters() {
        PlaybackByteCacheMetrics.recordCachedBytesRead(1L)
        PlaybackByteCacheMetrics.recordCacheIgnored()
        PlaybackByteCacheMetrics.reset()
        assertEquals(0L, PlaybackByteCacheMetrics.cachedBytes())
        assertEquals(0L, PlaybackByteCacheMetrics.ignoredCount())
    }
}
