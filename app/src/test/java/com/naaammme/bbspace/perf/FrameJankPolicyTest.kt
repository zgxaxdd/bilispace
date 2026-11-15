package com.naaammme.bbspace.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameJankPolicy] 的纯判据单测（W7）。
 *
 * 钉住四件事：
 * 1. **分桶边界**：16/32/50/100/300/700/1500 的桶归属不能错（错一格 p95 就会报错量级）；
 * 2. **分位数**：空样本返回 0；单样本点击中其桶；p95 落在"累计计数首次 ≥ 目标"的桶；
 * 3. **格式化口径**：fps 用整数运算拼成 `xx.y`（避免 `String.format` 分配）；
 * 4. **异常输入**：负帧间隔不许崩、不许算成超大桶。
 */
class FrameJankPolicyTest {

    @Test
    fun `bucket boundaries are inclusive upper bounds`() {
        assertEquals(0, FrameJankPolicy.bucketOf(0))
        assertEquals(0, FrameJankPolicy.bucketOf(16))
        assertEquals(1, FrameJankPolicy.bucketOf(17))
        assertEquals(1, FrameJankPolicy.bucketOf(32))
        assertEquals(2, FrameJankPolicy.bucketOf(33))
        assertEquals(3, FrameJankPolicy.bucketOf(100))
        assertEquals(4, FrameJankPolicy.bucketOf(101))
        assertEquals(5, FrameJankPolicy.bucketOf(700))
        assertEquals(6, FrameJankPolicy.bucketOf(701))
        // 超过最后一档 ⇒ 落到"溢出桶"（下标 = 桶数）
        assertEquals(FrameJankPolicy.BUCKETS_MS.size, FrameJankPolicy.bucketOf(1501))
        assertEquals(FrameJankPolicy.BUCKETS_MS.size, FrameJankPolicy.bucketOf(Long.MAX_VALUE / 2))
    }

    @Test
    fun `negative interval does not crash and stays in the first bucket`() {
        assertEquals(0, FrameJankPolicy.bucketOf(-1))
    }

    @Test
    fun `percentile returns zero for empty sample`() {
        val counts = IntArray(FrameJankPolicy.BUCKETS_MS.size + 1)
        assertEquals(0L, FrameJankPolicy.percentileMs(counts, total = 0, percentile = 95))
    }

    @Test
    fun `p95 lands on the bucket that first reaches the target count`() {
        val counts = IntArray(FrameJankPolicy.BUCKETS_MS.size + 1)
        // 90 帧落在 ≤16ms 桶，10 帧落在 ≤50ms 桶（下标 2）⇒ 100 帧。
        counts[0] = 90
        counts[2] = 10
        assertEquals(
            "p95 目标 = 95 帧，累计到 ≤50ms 桶才达到 ⇒ 返回该桶上界 50",
            50L,
            FrameJankPolicy.percentileMs(counts, total = 100, percentile = 95)
        )
        // 全部落在首个桶 ⇒ p95 就是 16ms。
        val allFast = IntArray(FrameJankPolicy.BUCKETS_MS.size + 1).also { it[0] = 120 }
        assertEquals(16L, FrameJankPolicy.percentileMs(allFast, total = 120, percentile = 95))
    }

    @Test
    fun `format uses integer math and keeps field order`() {
        // 10s / 600 帧 = 60.0 fps
        val line = FrameJankPolicy.format(
            elapsedMs = 10_000L,
            frames = 600,
            jankFrames = 6,
            freezeFrames = 0,
            worstFrameMs = 120L,
            p95Ms = 50L,
            gcCountDelta = 3,
            gcTimeDeltaMs = 21
        )
        assertTrue("必须以窗口开头", line.startsWith("frame[10 s]"))
        assertTrue("fps 口径：600 帧 / 10s = 60.0", line.contains("fps=60.0"))
        assertTrue("帧数", line.contains("frames=600"))
        assertTrue("掉帧数与占比", line.contains("jank=6(1%)"))
        assertTrue("冻结帧", line.contains("freeze=0"))
        assertTrue("p95/worst", line.contains("p95=50ms worst=120ms"))
        assertTrue("GC 联动字段（与 MemoryDiag 对得上）", line.contains("gc=3/21ms"))
    }

    @Test
    fun `format is safe on a zero-length window`() {
        val line = FrameJankPolicy.format(
            elapsedMs = 0L,
            frames = 0,
            jankFrames = 0,
            freezeFrames = 0,
            worstFrameMs = 0L,
            p95Ms = 0L,
            gcCountDelta = 0L,
            gcTimeDeltaMs = 0L
        )
        assertTrue("窗口秒数至少 1，避免除零", line.startsWith("frame[1 s]"))
        assertTrue("无帧时 fps 报 0.0", line.contains("fps=0.0"))
    }
}
