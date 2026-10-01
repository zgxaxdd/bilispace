package com.naaammme.bbspace.core.swipe.source

import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.swipe.SwipeEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多源编排的纯判据（SW-05）：cost 升序 → diversity 降序的消费顺序、
 * 蜂窝（计费网络）降级、空批跳过。网络请求本身在源实现里（本测试用假源）。
 */
class SwipeSourceRegistryTest {

    private class FakeSource(
        override val id: String,
        override val cost: SwipeSourceCost,
        override val diversity: Float = 1f,
        private val batches: List<List<SwipeEntry>>,
    ) : SwipeSource {
        private var call = 0
        override suspend fun next(cursor: SwipeCursor, limit: Int): SwipeSourceBatch {
            val entries = batches.getOrNull(call++) ?: emptyList()
            return SwipeSourceBatch(
                sourceId = id,
                entries = entries,
                nextCursor = if (entries.isEmpty()) null else cursor.copy(idx = cursor.idx + 1)
            )
        }
    }

    private fun entry(aid: Long): SwipeEntry = SwipeEntry(
        target = VideoTarget.Ugc(aid = aid, cid = aid),
        bvid = "",
        title = "t$aid",
        cover = "c",
        author = null,
        durationText = null
    )

    @Test
    fun `available orders by cost then diversity`() {
        val registry = SwipeSourceRegistry(
            listOf(
                FakeSource("heavy", SwipeSourceCost.Heavy, diversity = 0.9f, batches = emptyList()),
                FakeSource("light-b", SwipeSourceCost.Light, diversity = 0.4f, batches = emptyList()),
                FakeSource("light-a", SwipeSourceCost.Light, diversity = 0.8f, batches = emptyList()),
                FakeSource("offline", SwipeSourceCost.Offline, diversity = 0.1f, batches = emptyList())
            )
        )
        val order = registry.available(isUnmetered = true).map { it.id }
        assertEquals(listOf("offline", "light-a", "light-b", "heavy"), order)
    }

    @Test
    fun `cellular drops heavy sources`() {
        val registry = SwipeSourceRegistry(
            listOf(
                FakeSource("light", SwipeSourceCost.Light, batches = emptyList()),
                FakeSource("heavy", SwipeSourceCost.Heavy, batches = emptyList())
            )
        )
        assertEquals(listOf("light", "heavy"), registry.available(isUnmetered = true).map { it.id })
        // 计费网络：Heavy 被剔除
        assertEquals(listOf("light"), registry.available(isUnmetered = false).map { it.id })
    }

    @Test
    fun `nextBatch skips empty batches and advances cursor of the producing source`() = runTest {
        val registry = SwipeSourceRegistry(
            listOf(
                FakeSource("first", SwipeSourceCost.Offline, batches = listOf(emptyList())),
                FakeSource(
                    "second",
                    SwipeSourceCost.Light,
                    batches = listOf(listOf(entry(1), entry(2)))
                )
            )
        )
        val batch = registry.nextBatch(
            cursor = SwipeCursor(sourceId = "first", idx = 3),
            limit = 8,
            isUnmetered = true
        )
        assertNotNull(batch)
        assertEquals("second", batch?.sourceId)
        assertEquals(2, batch?.entries?.size)
        assertEquals(4L, batch?.nextCursor?.idx)
    }

    @Test
    fun `nextBatch returns null when every source is dry`() = runTest {
        val registry = SwipeSourceRegistry(
            listOf(
                FakeSource("a", SwipeSourceCost.Light, batches = listOf(emptyList())),
                FakeSource("b", SwipeSourceCost.Light, batches = listOf(emptyList()))
            )
        )
        assertNull(registry.nextBatch(SwipeCursor(), limit = 8, isUnmetered = true))
    }

    @Test
    fun `cellular with only heavy sources yields nothing`() = runTest {
        val registry = SwipeSourceRegistry(
            listOf(FakeSource("heavy", SwipeSourceCost.Heavy, batches = listOf(listOf(entry(1)))))
        )
        assertNull(registry.nextBatch(SwipeCursor(), limit = 8, isUnmetered = false))
        assertTrue(registry.available(isUnmetered = true).isNotEmpty())
    }
}
