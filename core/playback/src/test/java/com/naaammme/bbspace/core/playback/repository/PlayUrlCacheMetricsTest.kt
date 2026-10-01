package com.naaammme.bbspace.core.playback.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * playurl 缓存计数的出口不变式（SW-12）：命中/未命中累加、命中率口径、
 * 监听器只一个（避免多播重复计数）。计数器是全局单例 → 每个用例先 [PlayUrlCacheMetrics.reset]。
 */
class PlayUrlCacheMetricsTest {

    @Test
    fun `records hits and misses and computes rate`() {
        PlayUrlCacheMetrics.reset()
        assertNull("无样本时命中率必须是 null（样本不足是合法输出）", PlayUrlCacheMetrics.hitRate())

        repeat(3) { PlayUrlCacheMetrics.record(hit = true) }
        PlayUrlCacheMetrics.record(hit = false)
        assertEquals(3L, PlayUrlCacheMetrics.hits())
        assertEquals(1L, PlayUrlCacheMetrics.misses())
        assertEquals(0.75, PlayUrlCacheMetrics.hitRate()!!, 1e-9)

        PlayUrlCacheMetrics.reset()
        assertEquals(0L, PlayUrlCacheMetrics.hits())
        assertNull(PlayUrlCacheMetrics.hitRate())
    }

    @Test
    fun `listener receives every access and can be cleared`() {
        PlayUrlCacheMetrics.reset()
        val seen = mutableListOf<Boolean>()
        PlayUrlCacheMetrics.setListener { seen += it }
        PlayUrlCacheMetrics.record(hit = true)
        PlayUrlCacheMetrics.record(hit = false)
        assertEquals(listOf(true, false), seen)

        // 单例出口：后注册覆盖先注册（多播会重复计数）
        val second = mutableListOf<Boolean>()
        PlayUrlCacheMetrics.setListener { second += it }
        PlayUrlCacheMetrics.record(hit = true)
        assertEquals(listOf(true), second)
        assertEquals(2, seen.size)

        PlayUrlCacheMetrics.setListener(null)
        PlayUrlCacheMetrics.record(hit = true)
        assertTrue("解除后不再回调", second.size == 1)
        PlayUrlCacheMetrics.reset()
    }
}
