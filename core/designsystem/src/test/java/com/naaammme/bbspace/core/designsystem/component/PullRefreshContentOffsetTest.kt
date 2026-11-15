package com.naaammme.bbspace.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉刷新内容位移的**边界证明**（BiliPai `B8-b` 移植）。
 *
 * 这个函数的正确性完全取决于"与指示器同源"这一条，所以断言也钉在**几何关系**上：
 * 指示器底边落在 `distanceFraction * indicatorThresholdPx`，本函数返回的位移必须
 * 与它一致（差一个恒定净空）。
 */
class PullRefreshContentOffsetTest {

    private val thresholdPx = 80f

    @Test
    fun `未下拉时不产生位移`() {
        // 静息态必须是精确的 0，不能是 0.0001 —— 否则整页永远偏移一个亚像素，
        // 在对比度敏感的浅色主题上会读成"内容有一条毛边"。
        assertEquals(
            0f,
            resolvePullContentOffsetPx(
                distanceFraction = 0f,
                indicatorThresholdPx = thresholdPx,
                gapPx = 12f
            ),
            0f
        )
    }

    @Test
    fun `位移跟随指示器底边_同一个基准`() {
        // 这是与上游的核心差异点：上游用固定的 PositionalThreshold(80dp)，
        // 本仓必须用调用方传进来的 indicatorThresholdPx，否则用户把刷新距离
        // 调到 40dp 时内容会跑到指示器下面去（见函数 KDoc）。
        val fraction = 0.5f
        val offset = resolvePullContentOffsetPx(
            distanceFraction = fraction,
            indicatorThresholdPx = 40f,
            gapPx = 0f
        )
        // 指示器底边 = 0.5 * 40 = 20
        assertEquals(20f, offset, 1e-4f)
    }

    @Test
    fun `净空只算到 1_0 超出部分不再叠加`() {
        // 超过阈值后（fraction > 1）继续拉，内容**不再多出一个净空**，
        // 只是跟着指示器走。不夹的话回弹阶段内容会被越推越远、像被甩出去。
        val gap = 12f
        val atOne = resolvePullContentOffsetPx(1f, thresholdPx, gap)
        val atTwo = resolvePullContentOffsetPx(2f, thresholdPx, gap)
        assertEquals(thresholdPx * 1f + gap, atOne, 1e-4f)
        assertEquals(thresholdPx * 2f + gap, atTwo, 1e-4f)
    }

    @Test
    fun `回弹到 0 时精确归零`() {
        // 释放后状态机会把 fraction 动画回 0；归零必须精确，否则残留位移。
        val midway = resolvePullContentOffsetPx(0.4f, thresholdPx, 12f)
        assertTrue("中途应有位移", midway > 0f)
        assertEquals(0f, resolvePullContentOffsetPx(0f, thresholdPx, 12f), 0f)
    }

    @Test
    fun `负的进度被夹到 0_不产生向上位移`() {
        // 惯性回弹期间 fraction 可能瞬时为负（overscroll）。若直接相乘，
        // 内容会**向上**位移、把顶部内容推到屏幕外。
        assertEquals(
            0f,
            resolvePullContentOffsetPx(-0.3f, thresholdPx, 12f),
            0f
        )
    }

    @Test
    fun `位移随进度单调不减`() {
        var previous = -1f
        for (tenth in 0..30) {
            val offset = resolvePullContentOffsetPx(tenth / 10f, thresholdPx, 12f)
            assertTrue("fraction=${tenth / 10f} 时应单调不减", offset >= previous)
            previous = offset
        }
    }
}
