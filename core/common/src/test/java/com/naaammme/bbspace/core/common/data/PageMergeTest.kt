package com.naaammme.bbspace.core.common.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergePagesUnique] 的状态测（AP-11 ③：给定含重叠页的 fake 数据，拼接后 key 唯一）。
 *
 * 这条测试保护的是一个**崩溃类**缺陷：LazyColumn 遇到重复 key 会直接抛
 * IllegalArgumentException，且只在"翻页窗口重叠 + 用户滚到接缝"这个时序上发作，
 * 真机上极难复现。去重逻辑因此必须是纯函数、必须被这张表钉住。
 */
class PageMergeTest {

    private data class Row(val key: Long, val label: String)

    private val keyOf: (Row) -> Any = Row::key

    @Test
    fun overlappingPagesMergeWithUniqueKeys() {
        val page1 = listOf(Row(1, "a"), Row(2, "b"), Row(3, "c"))
        val page2 = listOf(Row(2, "b-dup"), Row(3, "c-dup"), Row(4, "d"))
        val merged = mergePagesUnique(page1, page2, reset = false, keyOf = keyOf)
        assertEquals(
            "重叠页拼接后 key 必须唯一（LazyColumn 重复 key = 崩溃）",
            listOf(1L, 2L, 3L, 4L),
            merged.map(Row::key)
        )
        assertEquals(
            "重复条目保留先到的一份（current 侧）",
            "b",
            merged.first { it.key == 2L }.label
        )
    }

    @Test
    fun resetReplacesAndDeduplicatesIncoming() {
        val incoming = listOf(Row(1, "a"), Row(1, "a-dup"), Row(2, "b"))
        val merged = mergePagesUnique(listOf(Row(9, "stale")), incoming, reset = true, keyOf = keyOf)
        assertEquals(listOf(1L, 2L), merged.map(Row::key))
    }

    @Test
    fun emptySidesShortCircuit() {
        val current = listOf(Row(1, "a"))
        assertEquals(current, mergePagesUnique(current, emptyList(), reset = false, keyOf = keyOf))
        assertEquals(
            "current 为空时以 incoming 为准且仍需去重",
            listOf(1L, 2L),
            mergePagesUnique(emptyList(), listOf(Row(1, "a"), Row(1, "a-dup"), Row(2, "b")), reset = false, keyOf = keyOf)
                .map(Row::key)
        )
    }
}
