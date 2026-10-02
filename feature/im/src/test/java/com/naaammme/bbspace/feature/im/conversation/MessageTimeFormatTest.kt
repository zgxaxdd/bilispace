package com.naaammme.bbspace.feature.im.conversation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IM 会话时间格式化的输出一致性测试（AP-20 ④）。
 *
 * 时间格式是**用户可见**的（AP-20 风险条目：改错=每条消息的时间都变样）。
 * 本测试把输出钉住：与"直接格式化"的参考实现逐条对照，并锁住边界
 * （非正时间戳 → 空串、跨日零点）。时区取宿主系统默认 —— 与生产实现同源，
 * 因此该测试同时验证"实现与参考不漂移"，不验证具体时区显示。
 */
class MessageTimeFormatTest {

    private val reference: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun expected(timestampSec: Long): String =
        reference.format(Instant.ofEpochSecond(timestampSec).atZone(ZoneId.systemDefault()))

    @Test
    fun matchesReferenceFormatForTypicalTimestamps() {
        // 覆盖同一天的多条消息与跨日/跨年边界附近的秒值
        val samples = listOf(
            1_700_000_000L,          // 2023-11-14 22:13:20 UTC
            1_700_006_399L,          // 当日 23:59:59 UTC
            1_700_006_400L,          // 次日 00:00:00 UTC（零点边界）
            1_631_234_560L,          // 2021-09-10（跨年样本）
            4_102_444_800L,          // 2100-01-01（远期）
            951_782_400L             // 2000-02-29（闰日）
        )
        samples.forEach { ts ->
            assertEquals("timestamp=$ts 的输出不得漂移", expected(ts), formatMessageTime(ts))
        }
    }

    @Test
    fun nonPositiveTimestampsRenderAsEmpty() {
        assertEquals("", formatMessageTime(0L))
        assertEquals("", formatMessageTime(-1L))
    }
}
