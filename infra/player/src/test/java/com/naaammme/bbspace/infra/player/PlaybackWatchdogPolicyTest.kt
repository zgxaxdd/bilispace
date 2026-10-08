package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 稳态观测的纯判据与格式化（R5 丢帧 + N2 命中率）。 */
class PlaybackWatchdogPolicyTest {

    @Test
    fun firstLineIsEmittedImmediatelyWhileLaterLinesAreRateLimited() {
        // 从没打过（lastReportAtMs < 0）时立刻出行：否则"这一段有没有丢帧"要白等 2 秒。
        assertTrue(PlaybackWatchdogPolicy.shouldReport(nowMs = 1_000L, lastReportAtMs = -1L, dirty = true))
        // 之后必须满间隔。
        assertFalse(
            PlaybackWatchdogPolicy.shouldReport(
                nowMs = 1_000L + PlaybackWatchdogPolicy.REPORT_INTERVAL_MS - 1,
                lastReportAtMs = 1_000L,
                dirty = true
            )
        )
        assertTrue(
            PlaybackWatchdogPolicy.shouldReport(
                nowMs = 1_000L + PlaybackWatchdogPolicy.REPORT_INTERVAL_MS,
                lastReportAtMs = 1_000L,
                dirty = true
            )
        )
    }

    @Test
    fun nothingNewMeansNothingPrinted() {
        // 静止播放（没有任何增量）连"满间隔"都不该出行 —— 否则 2 秒一行纯噪音。
        assertFalse(
            PlaybackWatchdogPolicy.shouldReport(
                nowMs = 100_000L,
                lastReportAtMs = 1_000L,
                dirty = false
            )
        )
    }

    @Test
    fun percentRoundsAndTreatsEmptySampleAsMissingNotZero() {
        assertEquals(-1, PlaybackWatchdogPolicy.percent(0L, 0L))
        assertEquals(0, PlaybackWatchdogPolicy.percent(0L, 100L))
        assertEquals(100, PlaybackWatchdogPolicy.percent(100L, 100L))
        // 四舍五入：2/3 = 66.7% → 67。
        assertEquals(67, PlaybackWatchdogPolicy.percent(2L, 3L))
        assertEquals("-", PlaybackWatchdogPolicy.percentText(-1))
        assertEquals("67%", PlaybackWatchdogPolicy.percentText(67))
    }

    @Test
    fun dropPercentExcludesDeliberatelySkippedFrames() {
        // 分母是"该出现的帧"= 丢的 + 渲染的。主动跳过（seek 等）算进去就会把
        // "用户拖了一下进度条"读成卡顿。
        assertEquals(5, PlaybackWatchdogPolicy.dropPercent(dropped = 1L, rendered = 19L))
        assertEquals(-1, PlaybackWatchdogPolicy.dropPercent(dropped = 0L, rendered = 0L))
    }

    @Test
    fun cacheHitPercentIsZeroWhenEverythingCameFromNetwork() {
        assertEquals(0, PlaybackWatchdogPolicy.cacheHitPercent(cachedBytes = 0L, networkBytes = 1_000L))
        assertEquals(80, PlaybackWatchdogPolicy.cacheHitPercent(cachedBytes = 4_000L, networkBytes = 1_000L))
        // 一个字节都没读（比如全片还在缓冲）⇒ 样本不足，不是 0% 命中。
        assertEquals(-1, PlaybackWatchdogPolicy.cacheHitPercent(cachedBytes = 0L, networkBytes = 0L))
    }

    @Test
    fun lineIsOneParsableRow() {
        val line = PlaybackWatchdogPolicy.line(
            dropped = 12L,
            rendered = 180L,
            skipped = 3L,
            decoderSwaps = 1L,
            maxConsecutiveDropped = 7L,
            cachedBytes = 1_258_291L,
            networkBytes = 307_200L,
            cacheIgnored = 0L,
            host = "xydcy1"
        )
        assertEquals(
            "play-watch dropped=12 rendered=180 drop=6% skipped=3 maxConsecDropped=7 swaps=1 " +
                "cached=1258291 net=307200 hit=80% ignored=0 host=xydcy1",
            line
        )
    }

    @Test
    fun lineUsesDashForMissingValues() {
        val allNetwork = PlaybackWatchdogPolicy.line(
            dropped = 0L,
            rendered = 0L,
            skipped = 0L,
            decoderSwaps = 0L,
            maxConsecutiveDropped = 0L,
            cachedBytes = 0L,
            networkBytes = 500L,
            cacheIgnored = 2L,
            host = null
        )
        // 一个字节都没从缓存读到、但确实走了网络 ⇒ 命中率是真实的 0%，不是"样本不足"。
        assertTrue(allNetwork.contains(" hit=0%"))
        // 没有任何帧被渲染也没丢帧 ⇒ 丢帧率必须打 `-`（0% 会被读成"完美不丢帧"）。
        assertTrue(allNetwork.contains(" drop=-"))
        // host 拿不到也必须是 `-`：空串会让字段看起来"存在但为空"。
        assertTrue(allNetwork.endsWith(" host=-"))

        val noSamples = PlaybackWatchdogPolicy.line(
            dropped = 0L,
            rendered = 0L,
            skipped = 0L,
            decoderSwaps = 0L,
            maxConsecutiveDropped = 0L,
            cachedBytes = 0L,
            networkBytes = 0L,
            cacheIgnored = 0L,
            host = ""
        )
        // 空串 host 与 null 同值处理（都打 `-`），否则 `host=` 后面跟空白会被解析器读成缺字段。
        assertTrue(noSamples.contains(" hit=-"))
        assertTrue(noSamples.endsWith(" host=-"))
    }
}
