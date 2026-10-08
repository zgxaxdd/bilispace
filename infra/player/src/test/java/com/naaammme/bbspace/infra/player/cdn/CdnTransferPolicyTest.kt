package com.naaammme.bbspace.infra.player.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CdnTransferPolicy] 的 JVM 测试（对齐 BiliPai 同名判据的语义边界）。
 *
 * 这些判据此前在本仓**完全不存在**（原实现只有"串行 open 失败换一条"），
 * 所以每条都要把"边界/非法输入"钉住 —— 它们是并行分片正确性的地基：
 * 切错一段 = 拉回错位的字节 = 花屏或解码失败 ✓
 */
class CdnTransferPolicyTest {

    // ---------- splitCdnRange ----------

    @Test
    fun splitCoversExactlyTheRequestedRangeWithoutGapsOrOverlap() {
        val chunks = splitCdnRange(start = 1000L, length = 100_000L, connections = 4)
        assertEquals(4, chunks.size)
        assertEquals("首片起点必须是请求起点", 1000L, chunks.first().start)
        assertEquals("末片终点必须是请求终点（1000 + 100000 - 1）", 100_999L, chunks.last().endInclusive)
        chunks.zipWithNext().forEach { (a, b) ->
            assertEquals("段间不许有缝", a.endInclusive + 1, b.start)
        }
        assertEquals(
            "各段长度之和必须等于请求长度",
            100_000L,
            chunks.sumOf { it.length }
        )
    }

    @Test
    fun splitHonoursLimitsAndDegenerateInputs() {
        assertTrue("负起点返回空", splitCdnRange(-1, 100, 4).isEmpty())
        assertTrue("零长度返回空", splitCdnRange(0, 0, 4).isEmpty())
        assertTrue("长度溢出返回空", splitCdnRange(Long.MAX_VALUE - 5, 100, 4).isEmpty())
        assertEquals(
            "并发数被夹到上限 4",
            4,
            splitCdnRange(0, 10_000, 99).size
        )
        assertEquals(
            "并发数最小是 1",
            1,
            splitCdnRange(0, 10_000, 0).size
        )
        assertEquals(
            "长度小于并发数时只切出 length 片（每片至少 1 字节）",
            3,
            splitCdnRange(0, 3, 4).size
        )
    }

    // ---------- parseCdnContentRange / isExactCdnRange ----------

    @Test
    fun parsesWellFormedContentRangeOnly() {
        val parsed = parseCdnContentRange("bytes 100-199/5000")
        assertEquals(CdnContentRange(100, 199, 5000), parsed)
        assertEquals("允许大小写", CdnContentRange(0, 1, 3), parseCdnContentRange("BYTES 0-1/3"))
        assertEquals("前后空白无害", CdnContentRange(0, 1, 3), parseCdnContentRange("  bytes 0-1/3  "))
        assertEquals("缺 total 不合法", null, parseCdnContentRange("bytes 0-1/*"))
        assertEquals("end<start 不合法", null, parseCdnContentRange("bytes 5-1/10"))
        assertEquals("total 必须大于 end", null, parseCdnContentRange("bytes 0-10/10"))
        assertEquals("非 bytes 前缀不合法", null, parseCdnContentRange("items 0-1/3"))
        assertEquals("null 不合法", null, parseCdnContentRange(null))
        assertEquals("空串不合法", null, parseCdnContentRange(""))
    }

    @Test
    fun exactRangeRequiresStatus206AndMatchingBounds() {
        val range = CdnByteRange(100, 199)
        assertTrue("206 + 边界一致 = 精确", isExactCdnRange(206, "bytes 100-199/5000", range))
        assertFalse(
            "**200 全量响应必须拒绝** —— CDN 忽略 Range 时会把整个文件当一片发回来",
            isExactCdnRange(200, "bytes 100-199/5000", range)
        )
        assertFalse(
            "起点不一致（服务端给了更大的区间）必须拒绝，否则字节错位",
            isExactCdnRange(206, "bytes 0-199/5000", range)
        )
        assertFalse(
            "终点不一致同样拒绝",
            isExactCdnRange(206, "bytes 100-4999/5000", range)
        )
        assertFalse("没有 Content-Range 拒绝", isExactCdnRange(206, null, range))
    }

    // ---------- resolveCdnConnections ----------

    @Test
    fun parallelPolicyMatchesTheSourceSemantics() {
        assertEquals("计量网络只用 1 条", 1, resolveCdnConnections(1000, 10_000_000, 2_000_000, limited = true))
        assertEquals("缓冲很厚（≥30s）不急，1 条", 1, resolveCdnConnections(30_000, 10_000_000, 2_000_000, limited = false))
        assertEquals("缓冲很薄（<5s）抢时间，3 条", 3, resolveCdnConnections(4_999, 10_000_000, 2_000_000, limited = false))
        assertEquals(
            "速率追不上码率（speed*8 < bitrate）也要抢，3 条",
            3,
            resolveCdnConnections(10_000, speedBps = 100_000, bitrateBps = 2_000_000, limited = false)
        )
        assertEquals("其余情况 2 条", 2, resolveCdnConnections(10_000, 10_000_000, 2_000_000, limited = false))
        assertEquals(
            "码率未知（0）时不判「追不上」，走 2 条",
            2,
            resolveCdnConnections(10_000, speedBps = 1, bitrateBps = 0, limited = false)
        )
    }

    @Test
    fun byteRangeLengthIsInclusiveAndNeverNegative() {
        assertEquals(100L, CdnByteRange(100, 199).length)
        assertEquals(1L, CdnByteRange(7, 7).length)
        assertEquals("倒置区间长度为 0（不是负数）", 0L, CdnByteRange(199, 100).length)
    }
}
