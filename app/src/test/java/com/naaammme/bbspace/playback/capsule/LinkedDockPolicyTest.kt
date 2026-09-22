package com.naaammme.bbspace.playback.capsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 联动底栏策略与几何的纯逻辑单测（对应 BiliPai `LinkedDockPolicy` 规格）。
 * 全部用像素常量，不依赖 Compose / 密度。
 */
class LinkedDockPolicyTest {

    @Test
    fun accumulateDockScroll_resetsOnDirectionReversal() {
        assertEquals(30f, accumulateDockScroll(20f, 10f))
        // 方向反转：丢弃历史累积，只保留本帧
        assertEquals(-25f, accumulateDockScroll(30f, -25f))
    }

    @Test
    fun accumulator_mergesOnlyWithAudioAtThreshold() {
        val accumulator = LinkedDockScrollAccumulator(thresholdPx = 100f).apply { scrolling = true }
        assertNull(accumulator.onScrollDelta(60f, hasAudio = true, LinkedDockPhase.Expanded, false))
        assertEquals(
            LinkedDockPhase.Playback,
            accumulator.onScrollDelta(60f, hasAudio = true, LinkedDockPhase.Expanded, false)
        )
        // 切换后累积清零，同方向再走 60 不足以再次切态
        assertNull(accumulator.onScrollDelta(60f, hasAudio = true, LinkedDockPhase.Playback, false))
    }

    @Test
    fun accumulator_noAudioNeverMerges() {
        val accumulator = LinkedDockScrollAccumulator(thresholdPx = 100f).apply { scrolling = true }
        assertNull(accumulator.onScrollDelta(500f, hasAudio = false, LinkedDockPhase.Expanded, false))
    }

    @Test
    fun accumulator_expandsOnUpScrollOrPullAtTop() {
        val accumulator = LinkedDockScrollAccumulator(thresholdPx = 100f).apply { scrolling = true }
        assertEquals(
            LinkedDockPhase.Expanded,
            accumulator.onScrollDelta(-120f, hasAudio = true, LinkedDockPhase.Playback, false)
        )
        val sameButAtTop = LinkedDockScrollAccumulator(thresholdPx = 100f).apply { scrolling = true }
        assertEquals(
            LinkedDockPhase.Expanded,
            sameButAtTop.onScrollDelta(-1f, hasAudio = true, LinkedDockPhase.Playback, atTopPullDown = true)
        )
    }

    @Test
    fun accumulator_ignoresScrollingWhenIdle() {
        val accumulator = LinkedDockScrollAccumulator(thresholdPx = 100f)
        assertNull(accumulator.onScrollDelta(500f, hasAudio = true, LinkedDockPhase.Expanded, false))
    }

    @Test
    fun geometry_expandedKeepsAudioOnItsOwnRow() {
        val geometry = resolveLinkedDockGeometry(
            width = 1000,
            button = 100,
            barHeight = 120,
            gap = 20,
            hasAudio = true,
            searchEnabled = true,
            mergeProgress = 0f,
            searchProgress = 0f,
            verticalGap = 10
        )
        assertEquals(130, geometry.top)
        assertEquals(250, geometry.height)
        assertEquals(1000, geometry.audioWidth)
        assertEquals(0, geometry.audioX)
        assertEquals(100, geometry.searchWidth)
    }

    @Test
    fun geometry_mergedMovesAudioIntoNavigationRow() {
        val geometry = resolveLinkedDockGeometry(
            width = 1000,
            button = 100,
            barHeight = 120,
            gap = 20,
            hasAudio = true,
            searchEnabled = true,
            mergeProgress = 1f,
            searchProgress = 0f,
            verticalGap = 10
        )
        assertEquals(0, geometry.top)
        assertEquals(120, geometry.height)
        // 760 + 120 + 100 + 两道 gap = 1000：横条夹在首页图标位与搜索位之间
        assertEquals(760, geometry.audioWidth)
        assertEquals(120, geometry.audioX)
        assertEquals(0, geometry.audioY)
    }

    @Test
    fun geometry_withoutAudioIsSingleRow() {
        val geometry = resolveLinkedDockGeometry(
            width = 1000,
            button = 100,
            barHeight = 120,
            gap = 20,
            hasAudio = false,
            searchEnabled = true,
            mergeProgress = 0f,
            searchProgress = 0f
        )
        assertEquals(0, geometry.top)
        assertEquals(120, geometry.height)
        assertEquals(0, geometry.audioWidth)
    }

    @Test
    fun navigationClusterIsCenteredAndSearchGlidesToRightEdge() {
        val navigationX = resolveLinkedDockNavigationX(
            maximumWidth = 1000,
            navigationWidth = 300,
            button = 100,
            gap = 20,
            searchEnabled = true
        )
        assertEquals(290, navigationX)
        assertEquals(
            610,
            resolveLinkedDockSearchX(1000, 300, 100, 100, 20, mergeProgress = 0f, searchProgress = 0f)
        )
        assertEquals(
            900,
            resolveLinkedDockSearchX(1000, 300, 100, 100, 20, mergeProgress = 1f, searchProgress = 0f)
        )
    }

    @Test
    fun navigationWidthReservesSearchSlot() {
        // 4 × 48 + 2 × 4 = 200（未超宽时按内容定宽）
        assertEquals(
            200,
            resolveLinkedDockNavigationWidth(
                maximumWidth = 1000,
                itemCount = 4,
                itemWidth = 48,
                innerPadding = 4,
                button = 56,
                gap = 8,
                searchEnabled = true
            )
        )
        // 窄容器：给搜索位让出 button + gap 后被压缩（456-64=392 仍容得下 200）
        assertEquals(
            200,
            resolveLinkedDockNavigationWidth(
                maximumWidth = 456,
                itemCount = 4,
                itemWidth = 48,
                innerPadding = 4,
                button = 56,
                gap = 8,
                searchEnabled = true
            )
        )
        assertEquals(
            86,
            resolveLinkedDockNavigationWidth(
                maximumWidth = 150,
                itemCount = 4,
                itemWidth = 48,
                innerPadding = 4,
                button = 56,
                gap = 8,
                searchEnabled = true
            )
        )
    }

    @Test
    fun accumulator_signContract_downScrollMerges_upScrollExpands() {
        // 官方口径：delta = 滚动偏移增量，下滑为正。回归用例锁死符号方向——
        // nestedScroll 的 consumed/available 是滚轮增量（上滑为负），调用方必须取负后再喂。
        val downScroll = LinkedDockScrollAccumulator(thresholdPx = 100f).apply { scrolling = true }
        assertNull(downScroll.onScrollDelta(+60f, hasAudio = true, LinkedDockPhase.Expanded, false))
        assertEquals(
            LinkedDockPhase.Playback,
            downScroll.onScrollDelta(+60f, hasAudio = true, LinkedDockPhase.Expanded, false)
        )
        // 合并态继续下滑不重复切态
        assertNull(downScroll.onScrollDelta(+60f, hasAudio = true, LinkedDockPhase.Playback, false))

        // 上滑（负）到阈值 → 展开
        val upScroll = LinkedDockScrollAccumulator(thresholdPx = 100f).apply { scrolling = true }
        assertNull(upScroll.onScrollDelta(-60f, hasAudio = true, LinkedDockPhase.Playback, false))
        assertEquals(
            LinkedDockPhase.Expanded,
            upScroll.onScrollDelta(-60f, hasAudio = true, LinkedDockPhase.Playback, false)
        )
        // 合并态继续上滑不重复切态
        assertNull(upScroll.onScrollDelta(-60f, hasAudio = true, LinkedDockPhase.Expanded, false))
    }

    @Test
    fun audioLossFallsBackToExpanded_andNonHomeTabsRest() {
        assertEquals(
            LinkedDockPhase.Expanded,
            resolveLinkedDockPhaseOnAudioChange(LinkedDockPhase.Playback, hasAudio = false)
        )
        assertEquals(
            LinkedDockPhase.Playback,
            resolveLinkedDockPhaseOnAudioChange(LinkedDockPhase.Playback, hasAudio = true)
        )
        assertEquals(
            LinkedDockPhase.Expanded,
            resolveLinkedDockPhaseForTab(isHomeTab = false, currentPhase = LinkedDockPhase.Playback)
        )
    }
}
