package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏连播「相邻页预取」判据。
 *
 * 对照 BiliPai `PortraitVideoLoadPolicy.kt:331-400`：
 * 拖动 25% 偏移预取目标页；停稳按"WiFi+开关=3 / WiFi=2 / 开关=1 / 否则 0"预取。
 * 这两个数字直接决定"上滑之后要不要再等一个 gRPC 往返"，改错了真机上就是明显的"卡一下"。
 */
class SwipePrefetchPolicyTest {

    @Test
    fun prefetchCountMatrixMatchesBiliPai() {
        // WiFi + 开关：预 3（只受可用页数限制）
        assertEquals(3, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = true, isUnmetered = true, available = 10))
        // WiFi、关闭开关：仍预 2（WiFi 不吃流量，属于"白给的加速"）
        assertEquals(2, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = false, isUnmetered = true, available = 10))
        // 蜂窝 + 开关：预 1
        assertEquals(1, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = true, isUnmetered = false, available = 10))
        // 蜂窝 + 关闭开关：不预（默认不偷跑流量）
        assertEquals(0, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = false, isUnmetered = false, available = 10))
    }

    @Test
    fun prefetchCountNeverExceedsAvailable() {
        assertEquals(1, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = true, isUnmetered = true, available = 1))
        assertEquals(0, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = true, isUnmetered = true, available = 0))
        assertEquals(0, SwipePrefetchPolicy.prefetchCount(prefetchEnabled = true, isUnmetered = true, available = -5))
    }

    @Test
    fun targetPagesOnlyLookForwardAndStayInRange() {
        assertEquals(listOf(3, 4, 5), SwipePrefetchPolicy.targetPages(page = 2, totalPages = 10, count = 3))
        // 末尾截断：只剩 1 页可预
        assertEquals(listOf(9), SwipePrefetchPolicy.targetPages(page = 8, totalPages = 10, count = 3))
        // 已在最后一页：没有可预的
        assertEquals(emptyList<Int>(), SwipePrefetchPolicy.targetPages(page = 9, totalPages = 10, count = 3))
        assertEquals(emptyList<Int>(), SwipePrefetchPolicy.targetPages(page = 0, totalPages = 1, count = 3))
        assertEquals(emptyList<Int>(), SwipePrefetchPolicy.targetPages(page = 0, totalPages = 10, count = 0))
    }

    @Test
    fun dragTargetPageUsesComposeOffsetSign() {
        // 负偏移 = 往下翻（下一页）；正值 = 往上翻（上一页）——与 BiliPai 同一约定
        assertEquals(3, SwipePrefetchPolicy.dragTargetPage(currentPage = 2, offsetFraction = -0.3f, lastPageIndex = 9))
        assertEquals(1, SwipePrefetchPolicy.dragTargetPage(currentPage = 2, offsetFraction = 0.3f, lastPageIndex = 9))
        // 没跨过阈值：不预（手指只是轻轻动了一下）
        assertNull(SwipePrefetchPolicy.dragTargetPage(currentPage = 2, offsetFraction = -0.1f, lastPageIndex = 9))
        assertNull(SwipePrefetchPolicy.dragTargetPage(currentPage = 2, offsetFraction = 0.24f, lastPageIndex = 9))
    }

    @Test
    fun dragTargetPageClampsAtEdges() {
        // 第一页再往上翻：没有上一页
        assertNull(SwipePrefetchPolicy.dragTargetPage(currentPage = 0, offsetFraction = 0.4f, lastPageIndex = 9))
        // 最后一页再往下翻：没有下一页
        assertNull(SwipePrefetchPolicy.dragTargetPage(currentPage = 9, offsetFraction = -0.4f, lastPageIndex = 9))
    }

    @Test
    fun thresholdIsTheBiliPaiValue() {
        assertTrue(
            "拖动预取阈值必须与 BiliPai 同为 0.25：改大 = 预取太晚（等于没预取），" +
                "改小 = 手指刚碰屏幕就发请求（浪费流量）。",
            SwipePrefetchPolicy.DRAG_OFFSET_THRESHOLD == 0.25f
        )
    }

    // ── 蜂窝下的详情预热闸门（2026-10-01，对照上游 `a4b8c17f8`）──────────────────

    @Test
    fun detailWarmIsSkippedOnCellularWhateverThePrefetchSwitchSays() {
        // WiFi：热（一次完整 gRPC 详情换"翻过去标题不空一拍"，白给的加速）
        assertTrue(SwipePrefetchPolicy.shouldWarmDetail(isUnmetered = true))
        // 蜂窝：**不热**。注意这与 prefetchCount 是两层判据 ——
        // 即便用户开着预取开关、蜂窝也允许预 1 条 playurl，重预热仍然跳过。
        assertFalse(SwipePrefetchPolicy.shouldWarmDetail(isUnmetered = false))
    }

    @Test
    fun cellularGateDoesNotAlsoKillPlayUrlPrefetch() {
        // 闸门不许顺手把 playurl 预取也关掉：那是"上滑之后起播快不快"的那一条，
        // 便宜得多，且已由 prefetchCount 的开关语义覆盖（蜂窝+开=1、蜂窝+关=0）。
        assertEquals(1, SwipePrefetchPolicy.prefetchCount(true, isUnmetered = false, available = 5))
        assertEquals(0, SwipePrefetchPolicy.prefetchCount(false, isUnmetered = false, available = 5))
    }
}
