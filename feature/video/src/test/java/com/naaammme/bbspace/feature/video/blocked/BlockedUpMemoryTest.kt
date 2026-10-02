package com.naaammme.bbspace.feature.video.blocked

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地拉黑名单的纯内核（PT-06 验收①的纯 JVM 部分）：
 * 契约 = **本地先生效、再异步同步、失败不回滚本地**——
 * [BlockedUpMemory.add] 立即入名单（synced=false），只有显式 [BlockedUpMemory.markSynced]
 * 才翻同步标记；名单容量 200（最旧先出）。
 */
class BlockedUpMemoryTest {

    @Test
    fun addIsLocalFirstAndUnsynced() {
        val memory = BlockedUpMemory().add(100L, nowMs = 1_000L)
        assertTrue(memory.contains(100L))
        assertFalse("本地先入，此时必然未同步", memory.entries.single().synced)
    }

    @Test
    fun addIsIdempotent() {
        val memory = BlockedUpMemory().add(100L, 1_000L).add(100L, 2_000L)
        assertEquals(1, memory.entries.size)
        assertEquals("同 mid 保留首条（最早的拉黑时间）", 1_000L, memory.entries.single().blockedAtMs)
    }

    /** 验收⑤的守卫前提：mid <= 0 不入名单（story/relate 源无 ownerMid）。 */
    @Test
    fun nonPositiveMidIgnored() {
        assertTrue(BlockedUpMemory().add(0L, 1_000L).entries.isEmpty())
        assertTrue(BlockedUpMemory().add(-5L, 1_000L).entries.isEmpty())
    }

    @Test
    fun markSyncedOnlyFlipsRecordedEntries() {
        val memory = BlockedUpMemory().add(100L, 1_000L).markSynced(100L)
        assertTrue(memory.entries.single().synced)
        val untouched = memory.markSynced(999L)
        assertEquals("标记未记录的 mid 不产生变化", 1, untouched.entries.size)
    }

    /** 失败不回滚本地：add 之后没有 markSynced，名单依然在（下轮可见）。 */
    @Test
    fun syncFailureKeepsLocalEntry() {
        val memory = BlockedUpMemory().add(100L, 1_000L)
        // 模拟同步失败：不调用 markSynced，名单不变
        assertTrue(memory.contains(100L))
        assertFalse(memory.entries.single().synced)
    }

    @Test
    fun removeReversesLocalEntry() {
        val memory = BlockedUpMemory().add(100L, 1_000L).remove(100L)
        assertFalse(memory.contains(100L))
    }

    /** 容量 200：超出时最旧先出（与 SwipeSeenMemory 同款口径）。 */
    @Test
    fun capEvictsOldestFirst() {
        var memory = BlockedUpMemory()
        repeat(200) { i -> memory = memory.add(i.toLong() + 1L, nowMs = i.toLong()) }
        memory = memory.add(999L, nowMs = 9_999L)
        assertEquals(200, memory.entries.size)
        assertFalse("最旧的 1 号应被淘汰", memory.contains(1L))
        assertTrue("最新加入的 999 应在名单里", memory.contains(999L))
    }
}
