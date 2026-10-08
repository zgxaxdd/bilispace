package com.naaammme.bbspace.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕分段窗口策略的验收 —— 逐字移植自 BiliPai
 * `feature/video/danmaku/DanmakuSegmentWindowPolicy.kt`。
 *
 * **源仓库没有给这个文件配套测试**，所以断言按源码分支逐条写。
 * 常量对齐本仓既有实现（[VOD_DANMAKU_SEGMENT_DURATION_MS] = 360_000）。
 */
class DanmakuSegmentWindowPolicyTest {

    private val seg = VOD_DANMAKU_SEGMENT_DURATION_MS

    @Test
    fun segmentIndexIsOneBasedAndClampsNegatives() {
        assertEquals(1, segmentIndexForPosition(0L))
        assertEquals(1, segmentIndexForPosition(seg - 1))
        assertEquals(2, segmentIndexForPosition(seg))
        assertEquals(3, segmentIndexForPosition(seg * 2 + 5))
        assertEquals("负数位置按 0 处理（源 coerceAtLeast(0)）", 1, segmentIndexForPosition(-9999L))
    }

    @Test
    fun windowIsAnchorPlusMinusOne() {
        // 中段：完整三段（`seg * 3` 落在第 **4** 段，因为段号 1 起算）
        assertEquals(listOf(3, 4, 5), segmentWindowForPosition(seg * 3, totalSegments = 10))
    }

    @Test
    fun windowIsClippedAtBothEnds() {
        assertEquals("开头不许出现 0 段", listOf(1, 2), segmentWindowForPosition(0L, totalSegments = 10))
        assertEquals(
            "结尾不许越界",
            listOf(9, 10),
            segmentWindowForPosition(seg * 9 + 1, totalSegments = 10)
        )
        assertEquals(
            "单段视频只有它自己",
            listOf(1),
            segmentWindowForPosition(seg * 3, totalSegments = 1)
        )
    }

    @Test
    fun totalSegmentsIsSanitized() {
        assertEquals("totalSegments<=0 时按 1 处理", listOf(1), segmentWindowForPosition(seg * 5, totalSegments = 0))
        assertEquals(listOf(1), segmentWindowForPosition(seg * 5, totalSegments = -3))
    }

    @Test
    fun replaceOnlyWhenWindowActuallyDiffers() {
        val pos = seg * 3
        assertFalse(shouldReplaceDanmakuWindow(listOf(3, 4, 5), pos, 10))
        assertFalse("顺序无关，集合相同就不换", shouldReplaceDanmakuWindow(listOf(5, 3, 4), pos, 10))
        assertTrue("少一段就要换", shouldReplaceDanmakuWindow(listOf(3, 4), pos, 10))
        assertTrue("跨段后要换", shouldReplaceDanmakuWindow(listOf(3, 4, 5), seg * 4, 10))
    }

    @Test
    fun requestIsSkippedWhenWindowAlreadyActive() {
        val pos = seg * 3
        assertFalse(
            "窗口已在显示且没有缺失段 → 不发请求",
            shouldRequestDanmakuWindow(listOf(3, 4, 5), emptyList(), requestInFlight = false, pos, 10)
        )
        assertTrue(
            "有缺失段时即使窗口相同也要补请求",
            shouldRequestDanmakuWindow(
                listOf(3, 4, 5), emptyList(), requestInFlight = false, pos, 10, hasMissingSegments = true
            )
        )
    }

    /** 源注释对应的语义：**换成别的窗口**时必须重新发，即使已有请求在途。 */
    @Test
    fun inFlightRequestDoesNotBlockADifferentWindow() {
        val pos = seg * 3
        assertFalse(
            "同一窗口已有请求在途 → 不重复发",
            shouldRequestDanmakuWindow(listOf(9), listOf(3, 4, 5), requestInFlight = true, pos, 10)
        )
        assertTrue(
            "在途的是别的窗口 → 必须重新发",
            shouldRequestDanmakuWindow(listOf(9), listOf(1, 9, 10), requestInFlight = true, pos, 10)
        )
    }

    @Test
    fun pendingWindowIsCancelledOnlyWhenItIsADifferentWindow() {
        val pos = seg * 3
        assertFalse("没有在途请求就没什么可取消", shouldCancelPendingDanmakuWindow(emptyList(), pos, 10))
        assertFalse(
            "在途的就是目标窗口 → 不许取消（那正是要等的结果）",
            shouldCancelPendingDanmakuWindow(listOf(3, 4, 5), pos, 10)
        )
        assertTrue(
            "用户拖回显示中的窗口 → 在途的别窗口请求必须取消",
            shouldCancelPendingDanmakuWindow(listOf(5, 6, 7), pos, 10)
        )
    }

    /** 窗口与本仓既有分段换算必须一致（不出现第二份真相）。 */
    @Test
    fun matchesTheRepositoriesExistingSegmentMath() {
        for (ms in listOf(0L, 1L, seg - 1, seg, seg * 7 + 123, seg * 100)) {
            assertEquals(
                "位置 $ms 的段号必须与既有 toDanmakuWindowId() 一致",
                ms.toDanmakuWindowId().toInt(),
                segmentIndexForPosition(ms)
            )
        }
    }
}
