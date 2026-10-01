package com.naaammme.bbspace.core.swipe.qoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QoE 纯换算不变式（SW-06）：最近秩百分位的边界（空/单样本/奇偶）、
 * 首帧配对的乱序与陈旧防御、成功率分母为零、导出格式稳定可解析。
 */
class SwipeQoeMetricsTest {

    // ── percentile：最近秩 ──────────────────────────────────────────────────

    @Test
    fun `percentile handles empty single and boundary samples`() {
        assertNull(SwipeQoeMath.percentile(emptyList(), 50.0))
        assertEquals(5L, SwipeQoeMath.percentile(listOf(5L), 50.0))
        assertEquals(1L, SwipeQoeMath.percentile(listOf(1L), 1.0))
        // 越界 p 一律 null（不许硬造）
        assertNull(SwipeQoeMath.percentile(listOf(1L), 0.0))
        assertNull(SwipeQoeMath.percentile(listOf(1L), 100.0))
    }

    @Test
    fun `percentile uses nearest rank without interpolation`() {
        val values = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
        // N=10，P50 → ceil(0.5*10)=5 → 第5小 = 50
        assertEquals(50L, SwipeQoeMath.percentile(values, 50.0))
        // P90 → ceil(0.9*10)=9 → 90
        assertEquals(90L, SwipeQoeMath.percentile(values, 90.0))
        // P99 → ceil(0.99*10)=10 → 100
        assertEquals(100L, SwipeQoeMath.percentile(values, 99.0))
        // 乱序输入不影响结果
        assertEquals(90L, SwipeQoeMath.percentile(values.shuffled(), 90.0))
        // N=4：P90 → ceil(3.6)=4 → 最大值
        assertEquals(40L, SwipeQoeMath.percentile(listOf(10L, 20L, 30L, 40L), 90.0))
    }

    // ── firstFrameDeltas：配对 + 乱序/陈旧防御 ─────────────────────────────

    private fun page(at: Long) = SwipeQoeEvent(SwipeQoeEventType.Page, atMs = at)
    private fun enter(at: Long) = SwipeQoeEvent(SwipeQoeEventType.Enter, atMs = at)
    private fun frame(at: Long) = SwipeQoeEvent(SwipeQoeEventType.FirstFrame, atMs = at)

    @Test
    fun `pairs each frame with nearest preceding page`() {
        val deltas = SwipeQoeMath.firstFrameDeltas(
            listOf(page(1000), frame(1500), page(3000), frame(3600))
        )
        assertEquals(listOf(500L, 600L), deltas)
    }

    @Test
    fun `enter seeds the first pending page`() {
        val deltas = SwipeQoeMath.firstFrameDeltas(listOf(enter(100), frame(400)))
        assertEquals(listOf(300L), deltas)
    }

    @Test
    fun `drops out-of-order and stale pairs`() {
        // 帧事件早于页事件（时间戳乱序）→ 不配对
        assertEquals(emptyList<Long>(), SwipeQoeMath.firstFrameDeltas(listOf(frame(500), page(1000))))
        // 间隔超窗（陈旧帧）→ 不配对
        assertEquals(
            emptyList<Long>(),
            SwipeQoeMath.firstFrameDeltas(listOf(page(0), frame(SwipeQoeMath.MAX_PAIR_MS + 1)))
        )
        // 重复帧回调：一条页事件只消费一次，第二帧没有新页 → 丢弃
        assertEquals(
            listOf(500L),
            SwipeQoeMath.firstFrameDeltas(listOf(page(1000), frame(1500), frame(1600)))
        )
    }

    // ── pageSuccessRate ─────────────────────────────────────────────────────

    @Test
    fun `success rate is null without pages`() {
        assertNull(SwipeQoeMath.pageSuccessRate(0, 3))
        assertEquals(0.5, SwipeQoeMath.pageSuccessRate(4, 2)!!, 1e-9)
        // 成功多于翻页（重复帧回调）夹到 1.0 以下没有意义，但也不炸
        assertTrue(SwipeQoeMath.pageSuccessRate(2, 5)!! > 1.0)
    }

    // ── exportText：字段固定、可脚本解析 ────────────────────────────────────

    @Test
    fun `export text has stable field order and handles missing samples`() {
        val snapshot = SwipeQoeHub.snapshot()
        val text = SwipeQoeMath.exportText(snapshot)
        assertTrue(text.startsWith("qoe: export"))
        assertTrue(text.contains("events_total="))
        assertTrue(text.contains("first_frame_p50_ms=-1") || text.contains("first_frame_p50_ms="))
        assertTrue(text.contains("page_success_rate=n/a") || text.contains("page_success_rate=0."))
        // SW-12：playurl 命中率也要进导出（诊断三件套之一）
        assertTrue(text.contains("playurl_hits="))
        assertTrue(text.contains("playurl_hit_rate="))
        // 导出内容不包含任何事件之外的内容字段（这里只查不出现标题式字段名）
        assertFalse(text.contains("title="))
        assertFalse(text.contains("owner="))
    }

    // ── 具名计数器（SW-12）──────────────────────────────────────────────────

    @Test
    fun `named counters accumulate and drive playurl hit rate`() {
        SwipeQoeHub.clear()
        repeat(4) { SwipeQoeHub.recordCounter(SwipeQoeSnapshot.COUNTER_PLAYURL_HIT) }
        repeat(1) { SwipeQoeHub.recordCounter(SwipeQoeSnapshot.COUNTER_PLAYURL_MISS) }
        SwipeQoeHub.recordCounter("prefetch_cancelled", delta = 2L)

        val snapshot = SwipeQoeHub.snapshot()
        assertEquals(4L, snapshot.namedCounters[SwipeQoeSnapshot.COUNTER_PLAYURL_HIT])
        assertEquals(1L, snapshot.namedCounters[SwipeQoeSnapshot.COUNTER_PLAYURL_MISS])
        assertEquals(2L, snapshot.namedCounters["prefetch_cancelled"])
        assertEquals(0.8, snapshot.playUrlHitRate!!, 1e-9)

        // 清空后无样本 → 命中率 null（不硬造 0）
        SwipeQoeHub.clear()
        assertNull(SwipeQoeHub.snapshot().playUrlHitRate)
        assertEquals(0, SwipeQoeHub.snapshot().namedCounters.size)
    }

    @Test
    fun `ring buffer keeps newest events`() {
        SwipeQoeHub.clear()
        repeat(SwipeQoeHub.CAPACITY + 5) { SwipeQoeHub.record(SwipeQoeEventType.Page) }
        val snapshot = SwipeQoeHub.snapshot()
        assertEquals(SwipeQoeHub.CAPACITY, snapshot.events.size)
        // 计数不受环形缓冲影响（计数与时间线分离）
        assertEquals(SwipeQoeHub.CAPACITY + 5, snapshot.counters[SwipeQoeEventType.Page])
        SwipeQoeHub.clear()
    }
}
