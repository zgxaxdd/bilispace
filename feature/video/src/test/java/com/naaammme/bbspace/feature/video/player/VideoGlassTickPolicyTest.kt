package com.naaammme.bbspace.feature.video.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 玻璃背板重录节流（R2）的纯判据。 */
class VideoGlassTickPolicyTest {

    private val interval = VideoGlassTickPolicy.TARGET_INTERVAL_NS
    private val frame60 = 16_666_667L   // 60Hz 一帧

    @Test
    fun firstFrameAfterActivationAlwaysRecords() {
        // 没有基线（lastTickNanos < 0）时必须录：否则控制条刚出现的那一段时间玻璃是静态底。
        assertTrue(VideoGlassTickPolicy.shouldRecord(frameTimeNanos = 1_000_000L, lastTickNanos = -1L))
    }

    @Test
    fun framesInsideTheIntervalAreNotRecorded() {
        val t0 = 10_000_000L
        assertTrue(VideoGlassTickPolicy.shouldRecord(t0, -1L))
        // 60Hz 面板：下一帧（+16.7ms）没到 33.3ms 间隔 ⇒ 不录。
        assertFalse(VideoGlassTickPolicy.shouldRecord(t0 + frame60, t0))
        assertTrue(VideoGlassTickPolicy.shouldRecord(t0 + interval, t0))
    }

    @Test
    fun throttleIsExpressedInTimeNotFrameCounts() {
        // 同一段真实时间里：120Hz 面板每帧 8.33ms，只有每 4 帧才录一次 ——
        // 与 60Hz 面板"每 2 帧录一次"的成本**相同**。按帧数节流就做不到这一点。
        val t0 = 0L
        val frame120 = 8_333_334L
        assertFalse(VideoGlassTickPolicy.shouldRecord(t0 + frame120, t0))
        assertFalse(VideoGlassTickPolicy.shouldRecord(t0 + 2 * frame120, t0))
        assertFalse(VideoGlassTickPolicy.shouldRecord(t0 + 3 * frame120, t0))
        assertTrue(VideoGlassTickPolicy.shouldRecord(t0 + 4 * frame120, t0))
    }

    @Test
    fun runningTargetKeepsCadenceAcrossFrames() {
        // 模拟 60Hz 跑 1 秒：记录次数应该≈30 而不是 60（这是这次改动的全部收益所在）。
        var last = -1L
        var recorded = 0
        var now = 0L
        while (now < 1_000_000_000L) {
            if (VideoGlassTickPolicy.shouldRecord(now, last)) {
                last = now
                recorded++
            }
            now += frame60
        }
        assertTrue("1 秒内重录次数应落在 30 附近，实测 $recorded", recorded in 29..31)
    }

    @Test
    fun clockGoingBackwardsRecordsInsteadOfStallingForever() {
        // 帧时间戳倒退（OEM/重置）绝不能让"永远不满足间隔"变成"再也不重录"——
        // 那会把控制条底下的玻璃冻成一张静态图，比多录几帧严重得多。
        assertTrue(VideoGlassTickPolicy.shouldRecord(frameTimeNanos = 1_000L, lastTickNanos = 9_000_000L))
        assertTrue(VideoGlassTickPolicy.shouldRecord(frameTimeNanos = 5_000L, lastTickNanos = 5_000L))
    }

    @Test
    fun targetIntervalIsThirtyHertz() {
        // 常量本身也是判据：改成 16_666_667 就等于把节流关掉，而消费端一行都不用动。
        assertTrue(
            "间隔必须明显大于 60Hz 的一帧（否则与逐帧重录无异）：$interval",
            interval > 20_000_000L
        )
        assertTrue("也不要低到肉眼可见（<10Hz）：$interval", interval < 100_000_000L)
    }
}
