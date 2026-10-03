package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-03：缓存键策略与缓存闸门（纯逻辑，无 Android 依赖）。
 *
 * 两条判据各自防一类静默失效：
 * - **键丢 host/scheme/query** → 换线/CDN 模式切换后仍命中同一份字节（漏了 host 就"换线即缓存全废"）；
 * - **闸门按源的种类** → 直播与本地文件不进缓存。
 *
 * 键的断言直接打纯内核 [playbackCacheKeyForPath]（`DataSpec` 需要 `android.net.Uri`，
 * 在 JVM 单测里是桩）；`CacheKeyFactory.buildCacheKey` 只是它的薄适配。
 */
class PlaybackCacheKeyFactoryTest {

    @Test
    fun samePathDifferentHostAndQueryShareOneKey() {
        val path = "/upgcxcode/12/34/114514-1-30080.m4s"
        assertEquals(
            "键只看 path：换 host / 换签名必须命中同一个键（否则换线即重复下载 + 命中率归零）",
            "media:$path",
            playbackCacheKeyForPath(path, "https://upos-sz-mirrorcos.bilivideo.com$path")
        )
        assertEquals(
            "同一个 path 在不同 host / query 下必须得到同一个键",
            playbackCacheKeyForPath(path, "https://upos-sz-mirrorcos.bilivideo.com$path"),
            playbackCacheKeyForPath(path, "https://upos-hz-mirrorakam.akamaized.net$path?deadline=9&sign=zzz")
        )
    }

    @Test
    fun differentPathsHaveDifferentKeys() {
        val video = playbackCacheKeyForPath("/upgcxcode/12/34/114514-1-30080.m4s", "")
        val audio = playbackCacheKeyForPath("/upgcxcode/12/34/114514-1-30280.m4s", "")
        assertNotEquals("不同流（视频/音频）必须是不同的键", video, audio)
    }

    @Test
    fun missingPathFallsBackToFullUri() {
        assertEquals("media:https://a/x", playbackCacheKeyForPath(null, "https://a/x"))
        assertEquals("media:https://a/x", playbackCacheKeyForPath("", "https://a/x"))
    }

    // ===== 闸门 =====

    @Test
    fun onlyVodIsCached() {
        assertTrue("VOD DASH 要缓存（主要收益来源）", PlaybackCacheGate.shouldCache(PlaybackCacheGate.Kind.VOD_DASH))
        assertTrue("VOD 单文件要缓存", PlaybackCacheGate.shouldCache(PlaybackCacheGate.Kind.VOD_PROGRESSIVE))
        assertFalse(
            "**直播不缓存**：流式边缘，缓存下来的是过期分片，而直播本来也不会被回看",
            PlaybackCacheGate.shouldCache(PlaybackCacheGate.Kind.LIVE)
        )
        assertFalse(
            "file://（离线合并）不缓存：读的是本地文件，没有缓存语义；任务书也明确要求挡住",
            PlaybackCacheGate.shouldCache(PlaybackCacheGate.Kind.LOCAL_FILE)
        )
    }

    @Test
    fun urlSchemeGateRejectsNonHttp() {
        assertTrue(PlaybackCacheGate.isCacheableUrl("https://a.bilivideo.com/x.m4s"))
        assertTrue(PlaybackCacheGate.isCacheableUrl("HTTP://a.bilivideo.com/x.m4s"))
        assertFalse(PlaybackCacheGate.isCacheableUrl("file:///storage/emulated/0/x.m4s"))
        assertFalse(PlaybackCacheGate.isCacheableUrl("content://media/x"))
    }

    @Test
    fun kindMappingMatchesEngineSourceTypes() {
        assertEquals(
            "DASH 源 → VOD_DASH",
            PlaybackCacheGate.Kind.VOD_DASH,
            PlaybackCacheGate.kindOf(
                EngineSource.SingleFileDash(videoUrl = "https://a/x.m4s", durationMs = 1_000L)
            )
        )
        assertEquals(
            "LocalMerged（file://）→ LOCAL_FILE（不缓存）",
            PlaybackCacheGate.Kind.LOCAL_FILE,
            PlaybackCacheGate.kindOf(EngineSource.LocalMerged(videoUrl = "file:///data/x.mp4"))
        )
        assertEquals(
            "直播 → LIVE（不缓存）",
            PlaybackCacheGate.Kind.LIVE,
            PlaybackCacheGate.kindOf(EngineSource.LiveFlv(url = "https://live/x.flv"))
        )
        assertEquals(
            "VOD 单文件 → VOD_PROGRESSIVE（缓存）",
            PlaybackCacheGate.Kind.VOD_PROGRESSIVE,
            PlaybackCacheGate.kindOf(EngineSource.Progressive(listOf(EngineSource.ProgressiveSegment("https://a/x.mp4", 1_000L))))
        )
    }
}
