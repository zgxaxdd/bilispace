package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳态观测窗口状态机（[PlaybackWatchdog]）。
 *
 * 这里钉的是**最容易写错的四件事**：读数倒退要夹紧、没变化不打行、打了行之后基线必须前移、
 * 被限频跳过的 tick 上的增量不能丢。
 */
class PlaybackWatchdogTest {

    private class Clock(var now: Long = 0L) {
        val at: () -> Long get() = { now }
        fun advance(delta: Long) {
            now += delta
        }
    }

    private fun sample(
        rendered: Long = 0L,
        dropped: Long = 0L,
        skipped: Long = 0L,
        inits: Long = 0L,
        cached: Long = 0L,
        network: Long = 0L,
        ignored: Long = 0L,
        host: String? = "host-a"
    ) = PlaybackWatchSample(
        renderedFrames = rendered,
        droppedFrames = dropped,
        skippedFrames = skipped,
        decoderInits = inits,
        maxConsecutiveDropped = 0L,
        cachedBytes = cached,
        networkBytes = network,
        cacheIgnored = ignored,
        host = host
    )

    @Test
    fun firstReadingAnchorsTheBaselineWithoutPrintingTheWholeProcessHistory() {
        val clock = Clock(100L)
        val watchdog = PlaybackWatchdog(clock.at)
        // 进程级累计值带着上一支视频的存量（缓存字节跨视频不清零），
        // 首行必须**只建立基线**，不能把历史累计当成这一窗口的量。
        val first = watchdog.report(sample(rendered = 5_000L, cached = 9_000_000L))
        assertNull(first)

        clock.advance(PlaybackWatchdogPolicy.REPORT_INTERVAL_MS)
        val second = watchdog.report(sample(rendered = 5_060L, cached = 9_001_000L))
        assertNotNull(second)
        assertTrue(second!!.contains(" rendered=60"))
        assertTrue(second.contains(" cached=1000"))
    }

    @Test
    fun windowIsDeltaBetweenReportsNotTheSumOfCallbacks() {
        val clock = Clock(0L)
        val watchdog = PlaybackWatchdog(clock.at)
        watchdog.report(sample(rendered = 100L, dropped = 10L, cached = 1_000L))

        // 第一次读数只建基线；第二次立刻就出行（从没打过 ⇒ 不等满间隔），
        // 这一行覆盖的是"起播后头 500ms"，正是想知道的那一段。
        clock.advance(500L)
        val first = watchdog.report(sample(rendered = 120L, dropped = 12L, cached = 1_500L))
        assertNotNull(first)
        assertTrue(first!!.contains(" rendered=20"))
        assertTrue(first.contains(" cached=500"))

        // 中间两个 tick 被限频跳过（增量仍在涨，但没到间隔）⇒ 下一个打印的行必须包含**全部**增量。
        clock.advance(500L)
        assertNull(watchdog.report(sample(rendered = 140L, dropped = 15L, cached = 2_000L)))
        clock.advance(500L)
        assertNull(watchdog.report(sample(rendered = 150L, dropped = 18L, cached = 2_000L)))
        clock.advance(1_000L)
        val line = watchdog.report(sample(rendered = 180L, dropped = 20L, cached = 2_500L))
        assertNotNull(line)
        assertTrue("窗口要覆盖被跳过的 tick：$line", line!!.contains(" rendered=60"))
        assertTrue(line.contains(" dropped=8"))
        assertTrue(line.contains(" cached=1000"))
    }

    @Test
    fun countersResetByDecoderReInitDoNotProduceNegativeDeltas() {
        val clock = Clock(0L)
        val watchdog = PlaybackWatchdog(clock.at)
        watchdog.report(sample(rendered = 900L, dropped = 30L, inits = 1L, cached = 5_000L))

        clock.advance(PlaybackWatchdogPolicy.REPORT_INTERVAL_MS)
        // 解码器重新初始化 ⇒ Media3 把 `DecoderCounters` 清零，读数**倒退**。
        val line = watchdog.report(sample(rendered = 5L, dropped = 0L, inits = 2L, cached = 5_400L))
        assertNotNull(line)
        // 倒退不能变成负数（`-5` 会被解析器读成合法值）；这一窗口应当显示"从零重新开始"。
        assertTrue("倒退必须夹到 0：$line", line!!.contains(" rendered=0"))
        assertTrue(line.contains(" dropped=0"))
        // 但"重新起了一个解码器"这件事本身必须看得见 —— 这正是复用/重建的判据。
        assertTrue(line.contains(" decInits=1"))
        // 基线跟着前移到新读数，下一个窗口才不会被旧值一直压着。
        clock.advance(PlaybackWatchdogPolicy.REPORT_INTERVAL_MS)
        val next = watchdog.report(sample(rendered = 45L, dropped = 0L, inits = 2L, cached = 5_400L))
        assertNotNull(next)
        assertTrue(next!!.contains(" rendered=40"))
        assertTrue(next.contains(" decInits=0"))
    }

    @Test
    fun idlePlaybackPrintsNothing() {
        val clock = Clock(0L)
        val watchdog = PlaybackWatchdog(clock.at)
        watchdog.report(sample(rendered = 10L, cached = 10L))
        // 完全静止（暂停、无字节流动）：满间隔也不打。
        clock.advance(60_000L)
        assertNull(watchdog.report(sample(rendered = 10L, cached = 10L)))
        // 一旦有变化就立刻补一行（不等新的间隔 —— 已经超时了）。
        clock.advance(500L)
        assertNotNull(watchdog.report(sample(rendered = 40L, cached = 10L)))
    }

    @Test
    fun maxConsecutiveDroppedIsTheCurrentPeakNotAnIncrement() {
        val clock = Clock(0L)
        val watchdog = PlaybackWatchdog(clock.at)
        watchdog.report(
            PlaybackWatchSample(
                renderedFrames = 0L,
                droppedFrames = 0L,
                skippedFrames = 0L,
                decoderInits = 0L,
                maxConsecutiveDropped = 9L,
                cachedBytes = 0L,
                networkBytes = 1L,
                cacheIgnored = 0L,
                host = null
            )
        )
        clock.advance(PlaybackWatchdogPolicy.REPORT_INTERVAL_MS)
        val line = watchdog.report(
            PlaybackWatchSample(
                renderedFrames = 30L,
                droppedFrames = 0L,
                skippedFrames = 0L,
                decoderInits = 0L,
                maxConsecutiveDropped = 9L,
                cachedBytes = 0L,
                networkBytes = 2L,
                cacheIgnored = 0L,
                host = null
            )
        )
        // 它是"最长连续丢帧"的当前水位：窗口里没再丢帧也照样保留，那才是这行最想告诉人的事。
        assertTrue(line!!.contains(" maxConsecDropped=9"))
        assertEquals(30L, line.substringAfter("rendered=").substringBefore(" ").toLong())
    }
}
