package com.naaammme.bbspace.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryDiagPolicy] 的纯判据单测（W5）。
 *
 * 钉住三件事：
 * 1. **什么时候报**：`onLowMemory` 与"前台/后台已吃紧"档必报；常规 `UI_HIDDEN` 只有
 *    图片缓存吃到上限 60% 才报（否则每次切后台都刷一行，日志失去信噪比）；
 * 2. **百分比口径**：上限为 0（缓存未初始化）时返回 0，不许除零、不许报 100%；
 * 3. **格式化字段顺序固定**：报告与前后对比都靠这一行 grep，字段顺序变了等于数据链断。
 */
class MemoryDiagPolicyTest {

    private fun snapshot(
        level: Int,
        reason: String = "test",
        heapUsed: Int = 64,
        heapMax: Int = 256,
        imgUsed: Int = 20,
        imgMax: Int = 100
    ) = MemorySnapshot(
        level = level,
        reason = reason,
        javaHeapUsedMb = heapUsed,
        javaHeapMaxMb = heapMax,
        javaHeapAllocatedMb = heapUsed + 8,
        imageCacheUsedMb = imgUsed,
        imageCacheMaxMb = imgMax,
        threadCount = 42,
        gcCount = 120,
        gcTimeMs = 900,
        blockingGcCount = 3,
        uptimeMs = 65_000
    )

    @Test
    fun `low memory always reports`() {
        assertTrue(
            "onLowMemory 是系统级内存紧张，任何情况下都要留一行",
            MemoryDiagPolicy.shouldReport(
                snapshot(MemoryDiagPolicy.LEVEL_LOW_MEMORY, imgUsed = 0)
            )
        )
    }

    @Test
    fun `foreground and background pressure levels report`() {
        for (level in listOf(10, 15, 40, 60, 80)) {
            assertTrue("level=$level 属于已吃紧档，应上报", MemoryDiagPolicy.shouldReport(snapshot(level)))
        }
    }

    @Test
    fun `ui hidden only reports when image cache is hot`() {
        // 图片缓存 20/100 = 20%：常规进后台，不报。
        assertFalse(
            "UI_HIDDEN 且在 60% 以下不该刷屏",
            MemoryDiagPolicy.shouldReport(snapshot(20, imgUsed = 20))
        )
        // 60/100 = 60%：达到阈值，报。
        assertTrue(
            "图片缓存吃到 60% 说明这一轮确实占用高，值得记一笔",
            MemoryDiagPolicy.shouldReport(snapshot(20, imgUsed = 60))
        )
        // 缓存上限未初始化（0）：百分比口径为 0，不报。
        assertFalse(
            "上限为 0 时不许除零，也不许被判成 100%",
            MemoryDiagPolicy.shouldReport(snapshot(20, imgUsed = 0, imgMax = 0))
        )
    }

    @Test
    fun `percentages never divide by zero`() {
        val zeroMax = snapshot(20, imgUsed = 0, imgMax = 0, heapUsed = 0, heapMax = 0)
        assertEquals(0, zeroMax.imageCachePercent)
        assertEquals(0, zeroMax.heapPercent)

        val normal = snapshot(20, heapUsed = 64, heapMax = 256, imgUsed = 20, imgMax = 100)
        assertEquals("图片缓存 20/100 = 20%", 20, normal.imageCachePercent)
        assertEquals("Java 堆 64/256 = 25%", 25, normal.heapPercent)
    }

    @Test
    fun `format keeps the field order the diagnostics rely on`() {
        val line = MemoryDiagPolicy.format(snapshot(15, reason = "级别过紧"))
        assertTrue("必须带 level", line.startsWith("mem[level=15 级别过紧]"))
        assertTrue("必须带堆占用", line.contains("heap=64/256MB(25%)"))
        assertTrue("必须带图片缓存", line.contains("img=20/100MB(20%)"))
        assertTrue("必须带线程数", line.contains("threads=42"))
        assertTrue("必须带 GC 次数/耗时", line.contains("gc=120/900ms"))
        assertTrue("必须带阻塞 GC", line.contains("blockingGc=3"))
        assertTrue("必须带进程存活时长", line.contains("uptime=65s"))
    }

    @Test
    fun `low memory level renders as a readable name`() {
        val line = MemoryDiagPolicy.format(snapshot(MemoryDiagPolicy.LEVEL_LOW_MEMORY))
        assertTrue("LOW_MEMORY 不该显示成 level=-1", line.startsWith("mem[LOW_MEMORY"))
    }
}
