package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-04：起播头部预热的判据与区间（纯函数）。
 *
 * 三条要守的：
 * 1. **仅 Wi-Fi 预热**（蜂窝下 1.5MiB 是真实流量，而"下一页"用户未必真会滑过去）；
 * 2. 区间是上游口径（视频 1.5MiB / 音频 256KiB）；
 * 3. 缺 URL 不产生空目标（避免拿空串去开连接）。
 */
class PlaybackHeadPrewarmPolicyTest {

    @Test
    fun prewarmsOnlyOnUnmeteredNetwork() {
        assertTrue(PlaybackHeadPrewarmPolicy.shouldPrewarm(isUnmetered = true))
        assertFalse(
            "蜂窝下不预热（1.5MiB 真实下载，用户未必滑到那一页）",
            PlaybackHeadPrewarmPolicy.shouldPrewarm(isUnmetered = false)
        )
    }

    @Test
    fun rangesMatchUpstream() {
        assertEquals("视频头部 1.5 MiB（上游 PortraitVideoLoadPolicy.kt:35）", 1_572_864L, PlaybackHeadPrewarmPolicy.VIDEO_HEAD_BYTES)
        assertEquals("音频头部 256 KiB（上游同处）", 262_144L, PlaybackHeadPrewarmPolicy.AUDIO_HEAD_BYTES)
    }

    @Test
    fun targetsAreVideoThenAudio() {
        val targets = PlaybackHeadPrewarmPolicy.targets(
            videoUrl = "https://a/v.m4s",
            audioUrl = "https://a/a.m4s",
            isUnmetered = true
        )
        assertEquals(2, targets.size)
        assertEquals("先视频（起播更常卡在视频上）", "https://a/v.m4s", targets[0].url)
        assertEquals(PlaybackHeadPrewarmPolicy.VIDEO_HEAD_BYTES, targets[0].lengthBytes)
        assertEquals("https://a/a.m4s", targets[1].url)
        assertEquals(PlaybackHeadPrewarmPolicy.AUDIO_HEAD_BYTES, targets[1].lengthBytes)
    }

    @Test
    fun cellularProducesNoTargets() {
        assertTrue(
            "蜂窝下目标表必须为空（门控在**判据里**，不靠调用方记得判）",
            PlaybackHeadPrewarmPolicy.targets("https://a/v.m4s", "https://a/a.m4s", isUnmetered = false).isEmpty()
        )
    }

    @Test
    fun missingUrlsAreSkipped() {
        assertTrue(PlaybackHeadPrewarmPolicy.targets(null, null, true).isEmpty())
        assertTrue(
            "空串不该产生目标（否则会拿空 URL 去开连接）",
            PlaybackHeadPrewarmPolicy.targets("  ", "", true).isEmpty()
        )
        val onlyAudio = PlaybackHeadPrewarmPolicy.targets(null, "https://a/a.m4s", true)
        assertEquals(1, onlyAudio.size)
        assertEquals(PlaybackHeadPrewarmPolicy.AUDIO_HEAD_BYTES, onlyAudio.single().lengthBytes)
    }
}
