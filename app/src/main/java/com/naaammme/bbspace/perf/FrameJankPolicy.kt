package com.naaammme.bbspace.perf

import kotlin.math.max

/**
 * 掉帧/卡顿的**纯判据**（W7，2026-10-17 夜第二轮）。
 *
 * ## 为什么需要它
 * 本仓能回答"播放链路"的帧情况（`PlaybackWatch` 每 2s 报丢帧/解码器换代），但**没有一个
 * 东西能回答"界面滑动卡不卡"**：`FrameBudgetLintTest` 只是静态扫写法，
 * 交接书里写的"本仓零帧率测量能力"说的就是这件事。
 *
 * 这里用一个**每帧只做整数运算**的采样器补上：分桶统计帧间隔，10s 出一行。
 * 不做 `System.nanoTime` 之外的系统调用、不分配对象、不新建线程
 * （Choreographer 回调跑在主线程）。
 *
 * ## 阈值口径（写死是有意的）
 * - 名义帧间隔按 **16.67ms**（60Hz）算；本机是 120Hz 时"名义"偏大，
 *   于是**只可能低估掉帧、不会虚报** —— 宁可少报也不误报（误报会让这条读数失去信任）。
 * - **jank**：帧间隔 > 32ms（≈ 两帧预算，业内常用口径）；
 * - **freeze**：帧间隔 > 700ms（ANR 前兆档，Google 的 "slow frame" 报告口径同一量级）。
 */
object FrameJankPolicy {

    /** 名义帧间隔（60Hz）。 */
    const val NOMINAL_FRAME_MS = 16.67f

    /** 掉帧阈值：超过 2 帧预算。 */
    const val JANK_FRAME_MS = 32L

    /** 冻结阈值：ANR 前兆档。 */
    const val FREEZE_FRAME_MS = 700L

    /** 上报周期：10s 一行（够看出趋势，又不刷屏）。 */
    const val REPORT_INTERVAL_MS = 10_000L

    /**
     * 直方图桶的上界（毫秒）。下标即桶号；最后一桶是"≥ 上界"。
     * 分桶是为了在**零分配**的前提下算 p95；桶越细越准，但内存/代码都变大，8 档够用。
     */
    val BUCKETS_MS = intArrayOf(16, 32, 50, 100, 300, 700, 1500)

    /** 把一帧间隔映射到桶号（0..BUCKETS_MS.size）。 */
    fun bucketOf(frameMs: Long): Int {
        if (frameMs < 0L) return 0
        for (index in BUCKETS_MS.indices) {
            if (frameMs <= BUCKETS_MS[index]) return index
        }
        return BUCKETS_MS.size
    }

    /** 从直方图求**分位数的桶上界**（近似值；用于报"p95 落在哪一档"）。 */
    fun percentileMs(counts: IntArray, total: Int, percentile: Int): Long {
        if (total <= 0) return 0L
        val target = max(1, total * percentile / 100)
        var seen = 0
        for (index in counts.indices) {
            seen += counts[index]
            if (seen >= target) {
                return if (index < BUCKETS_MS.size) BUCKETS_MS[index].toLong() else BUCKETS_MS.last().toLong()
            }
        }
        return BUCKETS_MS.last().toLong()
    }

    /** 一行读数。字段顺序固定，便于 `adb logcat -s FrameDiag` 与前后对比。 */
    fun format(
        elapsedMs: Long,
        frames: Int,
        jankFrames: Int,
        freezeFrames: Int,
        worstFrameMs: Long,
        p95Ms: Long,
        gcCountDelta: Long,
        gcTimeDeltaMs: Long
    ): String {
        val seconds = (elapsedMs / 1000L).coerceAtLeast(1L)
        val fpsX10 = if (frames <= 0) 0 else (frames * 10_000L / (elapsedMs.coerceAtLeast(1L)))
        val fps = fpsX10 / 10
        val frac = fpsX10 % 10
        val jankPercent = if (frames <= 0) 0 else (jankFrames * 100 / frames)
        return "frame[$seconds s] fps=$fps.$frac frames=$frames " +
            "jank=$jankFrames($jankPercent%) freeze=$freezeFrames " +
            "p95=${p95Ms}ms worst=${worstFrameMs}ms " +
            "gc=$gcCountDelta/${gcTimeDeltaMs}ms"
    }
}
