package com.naaammme.bbspace.core.swipe.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类型化身份键不变式（SW-03）。核心契约只有一条：
 * **页身份判断的结论，"取流前"与"取流后"必须一致**（C7 真机事故的教训）。
 */
class SwipeIdTest {

    // ── of() 的构造映射 ─────────────────────────────────────────────────────

    @Test
    fun `of maps both av bv and blank correctly`() {
        assertEquals(SwipeId.Both(2L, "BV1"), SwipeId.of(" BV1 ", 2L))
        assertEquals(SwipeId.Av(2L), SwipeId.of(null, 2L))
        assertEquals(SwipeId.Av(2L), SwipeId.of("  ", 2L))
        assertEquals(SwipeId.Bv("BV1"), SwipeId.of("BV1", 0L))
        // 两侧全缺：Bv("") 与任何身份都 matches=false（不炸、不误配）
        assertEquals(SwipeId.Bv(""), SwipeId.of(null, 0L))
    }

    // ── matches：aid 优先（C7 修复点的类型化固化）──────────────────────────

    @Test
    fun `aid wins when both sides have it regardless of bvid availability`() {
        // 建队时 aid-only ↔ 取流后 bvid 补齐：同一视频
        assertTrue(SwipeId.Av(117_339_261_503_379L).matches(SwipeId.Both(117_339_261_503_379L, "BV1Kyas6wEuz")))
        assertTrue(SwipeId.Both(117_339_261_503_379L, "BV1Kyas6wEuz").matches(SwipeId.Av(117_339_261_503_379L)))
        // aid 冲突时以 aid 为准（bvid 相同也判否——数据异常时宁可翻页也别播错视频）
        assertFalse(SwipeId.Both(1L, "BV1").matches(SwipeId.Both(2L, "BV1")))
        assertTrue(SwipeId.Both(1L, "BV1").matches(SwipeId.Both(1L, "BV1")))
    }

    @Test
    fun `bvid fallback only when aid is missing on either side`() {
        assertTrue(SwipeId.Bv("BV1").matches(SwipeId.Both(0L, "BV1")))
        assertTrue(SwipeId.Both(5L, "BV1").matches(SwipeId.Bv("BV1")))
        // 单侧 aid 存在、另一侧只有 bvid：不可比 → false（宁可翻页，不可错配）
        assertFalse(SwipeId.Av(5L).matches(SwipeId.Bv("BV1")))
        assertFalse(SwipeId.Bv("BV1").matches(SwipeId.Av(5L)))
        // 空白 bvid 不参与匹配
        assertFalse(SwipeId.Bv("").matches(SwipeId.Bv("")))
        assertFalse(SwipeId.Bv("BV1").matches(SwipeId.Bv("  ")))
        // trim 归一
        assertTrue(SwipeId.Bv(" BV1 ").matches(SwipeId.Bv("BV1")))
    }

    @Test
    fun `null never matches`() {
        assertFalse(SwipeId.Av(1L).matches(null))
        assertFalse(SwipeId.Bv("BV1").matches(null))
    }

    // ── Media 复合键：同 aid 异 cid 不是同一目标（SW-41 多P 的前置语义）──────

    @Test
    fun `media identity is bvid plus cid`() {
        assertTrue(SwipeId.Media("BV1", 10L).matches(SwipeId.Media("BV1", 10L)))
        assertFalse(SwipeId.Media("BV1", 10L).matches(SwipeId.Media("BV1", 11L)))
        assertFalse(SwipeId.Media("BV1", 10L).matches(SwipeId.Media("BV2", 10L)))
        // Media 不与 Ugc 型身份互配：复合键是"具体到分P"的身份
        assertFalse(SwipeId.Media("BV1", 10L).matches(SwipeId.Both(1L, "BV1")))
        assertFalse(SwipeId.Both(1L, "BV1").matches(SwipeId.Media("BV1", 10L)))
    }
}
