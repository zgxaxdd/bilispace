package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首帧解剖时间线（N4）的纯逻辑测。
 *
 * 时钟是注入的，所以五个点的推进、缺失点的 `-`、一次性闸门都能确定性断言 ——
 * 这行日志的**唯一用途是被脚本解析**，格式错了整个测量就作废，值得钉住。
 */
class FirstFrameTimelineTest {

    /** 可控时钟：`at` 是注入给时间线的读数，测试自己推进。 */
    private class FakeClock(var now: Long = 1_000L) {
        val at: () -> Long get() = { now }
        fun advance(delta: Long) {
            now += delta
        }
    }

    @Test
    fun completeLineReportsEveryStageAsDeltaFromT0() {
        val clock = FakeClock(98_213L)
        val timeline = FirstFrameTimeline(clock.at)

        timeline.begin("SingleFileDash")            // t0 = 98213
        clock.advance(118)
        timeline.sourceReady(indexFromNetwork = true)   // t1
        clock.advance(142)
        timeline.decoderInitialized("OMX.google.avc.decoder", initializationDurationMs = 97L) // t2
        clock.advance(15)
        timeline.surfaceReady(width = 1080, height = 1920) // t3
        clock.advance(137)
        timeline.firstFrameRendered()               // t4 = 总 412

        val line = timeline.completeLine()
        assertEquals(
            "first-frame kind=SingleFileDash gen=1 total=412 t0abs=98213 t1=118 t2=260 " +
                "t3=275 t4=412 index=NET codec=OMX.google.avc.decoder decoderInit=97",
            line
        )
    }

    @Test
    fun stagesThatNeverHappenedPrintDashInsteadOfZero() {
        val clock = FakeClock(500L)
        val timeline = FirstFrameTimeline(clock.at)

        timeline.begin("Progressive")
        clock.advance(50)
        timeline.sourceReady(indexFromNetwork = null)
        clock.advance(30)
        timeline.firstFrameRendered()

        val line = timeline.completeLine().orEmpty()
        // t2/t3 没发生 ⇒ 必须是 `-`：写成 0 会被解析成"第 0 毫秒就好了"。
        assertTrue("缺失的 t2 必须打 `-`，实际：$line", line.contains(" t2=-"))
        assertTrue("缺失的 t3 必须打 `-`，实际：$line", line.contains(" t3=-"))
        assertTrue("t1 有值：$line", line.contains(" t1=50"))
        // 非 DASH 源没有索引网络证据 ⇒ 与"命中缓存"区分开（CACHE 是 false，- 是 null）。
        assertTrue("index 要区分 null 与 false：$line", line.contains("index=-"))
    }

    @Test
    fun indexFlagDistinguishesCacheHitFromUnknown() {
        fun lineWith(indexFromNetwork: Boolean?): String {
            val timeline = FirstFrameTimeline { 5L }
            timeline.begin("SingleFileDash")
            timeline.sourceReady(indexFromNetwork)
            timeline.firstFrameRendered()
            return timeline.completeLine().orEmpty()
        }
        assertTrue(lineWith(true).contains("index=NET"))
        assertTrue(lineWith(false).contains("index=CACHE"))
        assertTrue(lineWith(null).contains("index=-"))
    }

    @Test
    fun completeLineIsGivenOnlyOncePerPlayback() {
        val timeline = FirstFrameTimeline { 5L }
        timeline.begin("SingleFileDash")
        timeline.sourceReady(indexFromNetwork = false)
        timeline.firstFrameRendered()

        assertEquals(true, timeline.completeLine() != null)
        // Media3 在重建 surface / 恢复之后会**再报一次** onRenderedFirstFrame，
        // 那次不该产出第二行（否则一次开播出两行，脚本会把它们当成两次开播统计）。
        assertNull(timeline.completeLine())
    }

    @Test
    fun partialLineAndCompleteLineDoNotConsumeEachOther() {
        val clock = FakeClock(10_000L)
        val timeline = FirstFrameTimeline(clock.at)
        timeline.begin("SingleFileDash")
        clock.advance(90)
        timeline.sourceReady(indexFromNetwork = true)

        val partial = timeline.partialLine("timeout")
        assertTrue("超时要给出残缺行", partial != null)
        assertTrue("残缺行要标 reason：$partial", partial!!.contains("reason=timeout"))
        assertTrue("还没出帧 ⇒ total 与 t4 都是 `-`：$partial", partial.contains(" t4=-"))
        assertTrue("残缺行只给一次：", timeline.partialLine("timeout") == null)

        // 超时之后真的出帧了 ⇒ 完整行仍然要出（两条信息都要，谁也不消耗谁）。
        clock.advance(3_000)
        timeline.firstFrameRendered()
        val complete = timeline.completeLine()
        assertTrue("超时之后出的完整行不能丢：$complete", complete != null)
        assertTrue("完整行不带 reason：$complete", !complete!!.contains("reason="))
    }

    @Test
    fun surfaceReadyIgnoresZeroSizeAndKeepsTheFirstNonZero() {
        val clock = FakeClock(1_000L)
        val timeline = FirstFrameTimeline(clock.at)
        timeline.begin("SingleFileDash")
        clock.advance(10)
        // Media3 用 (0,0) 表示"surface 被摘掉"，换绑/全屏往返都会再报 —— 都不能算就绪。
        timeline.surfaceReady(width = 0, height = 0)
        clock.advance(200)
        timeline.surfaceReady(width = 1080, height = 1920)   // t3 = +210
        clock.advance(50)
        timeline.surfaceReady(width = 720, height = 1280)    // 第二次非零：忽略
        timeline.firstFrameRendered()

        assertTrue(timeline.completeLine()!!.contains(" t3=210"))
    }

    @Test
    fun beginRebasesTheWholeTimeline() {
        val clock = FakeClock(1_000L)
        val timeline = FirstFrameTimeline(clock.at)
        timeline.begin("SingleFileDash")
        timeline.decoderInitialized("codec-A", initializationDurationMs = 40L)
        clock.advance(500)
        timeline.sourceReady(indexFromNetwork = false)
        assertEquals(1L, timeline.generation)

        clock.advance(10)
        timeline.begin("SingleFileDash")     // 下一次开播（切页/重建）
        assertEquals(2L, timeline.generation)
        clock.advance(20)
        timeline.firstFrameRendered()

        val line = timeline.completeLine().orEmpty()
        // 上一支视频的 t1/t2/codec 都不许漏进这一支（串页的解剖行比没有更坏）。
        assertTrue("必须归零重计：$line", line.contains(" t1=-"))
        assertTrue("codec 不能沿用上一支：$line", line.contains("codec=-"))
        assertTrue("decoderInit 不能沿用上一支：$line", line.contains("decoderInit=-"))
        assertTrue("gen 要带上：$line", line.contains("gen=2"))
    }

    @Test
    fun nothingIsEmittedBeforeFirstSetSource() {
        val timeline = FirstFrameTimeline { 7L }
        assertEquals(false, timeline.isActive)
        assertNull(timeline.completeLine())
        assertNull(timeline.partialLine("timeout"))
        // 没有 t0 时所有记录点都应当是 no-op（引擎刚构造好、还没有媒体源）。
        timeline.sourceReady(indexFromNetwork = true)
        timeline.decoderInitialized("codec", 1L)
        timeline.surfaceReady(100, 100)
        timeline.firstFrameRendered()
        assertEquals(false, timeline.isActive)
    }

    @Test
    fun decoderInitializationDurationIsCarriedIntoTheLine() {
        // N4 的动机之一：`initializationDurationMs` 这个参数在引擎里曾被接收后直接丢弃。
        // 这里钉住"时长必须出现在解剖行里"，缺失则等于白埋。
        val clock = FakeClock(1L)
        val timeline = FirstFrameTimeline(clock.at)
        timeline.begin("SingleFileDash")
        clock.advance(33)
        timeline.decoderInitialized("c2.vdk.avc.decoder", initializationDurationMs = 8L)
        timeline.firstFrameRendered()
        val line = timeline.completeLine().orEmpty()
        assertTrue("t2 是相对 t0 的等待：$line", line.contains(" t2=33"))
        assertTrue("解码器自己的初始化耗时要单独给：$line", line.contains("decoderInit=8"))
    }

    @Test
    fun monotonicClockZeroMeansNoTimelineStarted() {
        // 哨兵值口径与 `FirstFrameTimeoutPolicy.startedAtMs <= 0` 一致：`0` 表示"还没开播"，
        // 不是"第 0 毫秒开播"。改掉这条会让"未开播"与"立刻出帧"两种情况共用一行。
        val timeline = FirstFrameTimeline { 0L }
        timeline.begin("SingleFileDash")
        timeline.firstFrameRendered()
        assertEquals(false, timeline.isActive)
        assertNull(timeline.completeLine())
        assertNull(timeline.partialLine("timeout"))
    }
}
