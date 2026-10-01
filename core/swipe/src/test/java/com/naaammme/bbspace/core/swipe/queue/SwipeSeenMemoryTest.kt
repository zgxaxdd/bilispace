package com.naaammme.bbspace.core.swipe.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨会话「已推记忆」的纯状态不变式（SW-05，决策 D7）：
 * TTL 过期、上限淘汰（最旧先出）、同键刷新、编解码往返、坏输入兜底。
 */
class SwipeSeenMemoryTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `remember then contains within ttl`() {
        var memory = SwipeSeenMemory.empty()
        memory = memory.remember(keys = listOf("BV1", "BV2"), nowMs = 0L)
        assertTrue(memory.contains("BV1", nowMs = day))
        assertTrue(memory.contains("BV2", nowMs = 6L * day))
        // 7 天后过期
        assertFalse(memory.contains("BV1", nowMs = 8L * day))
    }

    @Test
    fun `same key refreshes timestamp instead of duplicating`() {
        var memory = SwipeSeenMemory.empty()
        memory = memory.remember(listOf("BV1"), nowMs = 0L)
        memory = memory.remember(listOf("BV1"), nowMs = 3L * day)
        assertEquals(1, memory.records.size)
        assertTrue(memory.contains("BV1", nowMs = 6L * day))
    }

    @Test
    fun `limit evicts oldest first`() {
        var memory = SwipeSeenMemory.empty()
        val keys = (1L..10L).map { "BV$it" }
        memory = memory.remember(keys, nowMs = 0L, limit = 3)
        // 最老的三条被挤出，只剩最新的三条（records 内部按"最新在前"排序）
        assertEquals(setOf("BV8", "BV9", "BV10"), memory.records.map { it.key }.toSet())
        // 上限随 TTL 语义工作：新会话推了新的，旧的按 seenAt 排序先出
        memory = memory.remember(listOf("BV11"), nowMs = day, limit = 3)
        assertEquals(setOf("BV9", "BV10", "BV11"), memory.records.map { it.key }.toSet())
    }

    @Test
    fun `expired records are dropped on remember`() {
        var memory = SwipeSeenMemory.empty()
        memory = memory.remember(listOf("BV1"), nowMs = 0L)
        // 8 天后再 remember：旧记录已过期，直接被清
        memory = memory.remember(listOf("BV2"), nowMs = 8L * day)
        assertEquals(listOf("BV2"), memory.records.map { it.key })
    }

    @Test
    fun `blank keys never take effect`() {
        var memory = SwipeSeenMemory.empty()
        memory = memory.remember(listOf("", "  ", "BV1"), nowMs = 0L)
        assertEquals(listOf("BV1"), memory.records.map { it.key })
        assertFalse(memory.contains("", nowMs = 0L))
    }

    @Test
    fun `json round trip preserves records`() {
        val memory = SwipeSeenMemory.empty()
            .remember(listOf("BV1", "aid:117339261503379"), nowMs = 1_000L)
        val parsed = SwipeSeenMemory.fromJson(memory.toJson())
        assertEquals(memory.records, parsed.records)
        assertTrue(parsed.contains("BV1", nowMs = 1_000L))
    }

    @Test
    fun `garbage json degrades to empty memory`() {
        assertEquals(0, SwipeSeenMemory.fromJson("not json").records.size)
        assertEquals(0, SwipeSeenMemory.fromJson("""{"records":"oops"}""").records.size)
        // 残缺记录被丢，完好记录保留
        val rescued = SwipeSeenMemory.fromJson(
            """{"records":[{"k":"BV1","t":100},{"t":200},{"k":"","t":300}]}"""
        )
        assertEquals(listOf("BV1"), rescued.records.map { it.key })
    }

    @Test
    fun `anySeen is an any over contains`() {
        val memory = SwipeSeenMemory.empty().remember(listOf("BV1"), nowMs = 0L)
        assertTrue(memory.anySeen(listOf("BV9", "BV1"), nowMs = day))
        assertFalse(memory.anySeen(listOf("BV9", "BV8"), nowMs = day))
    }
}
