package com.naaammme.bbspace.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PT-10 V1：`FeedApiType` 是「推荐来源」的唯一取值约定。
 *
 * 最要紧的一条是**兜底必须是 MOBILE**：bbspace 的历史行为就是 app 协议，
 * 上游 BiliPai 的兜底是 WEB —— 直接照抄上游会让"脏值/缺省"落到 WEB，
 * 等于把默认行为改了。这条断言就是钉死这个差异（红线）。
 */
class FeedApiTypeTest {

    @Test
    fun valuesMatchUpstreamEncoding() {
        assertEquals(0, FeedApiType.WEB.value)
        assertEquals(1, FeedApiType.MOBILE.value)
        assertEquals(2, FeedApiType.MERGED.value)
    }

    @Test
    fun dirtyValuesFallBackToMobileNotWeb() {
        assertEquals(FeedApiType.MOBILE, FeedApiType.fromValue(-1))
        assertEquals(FeedApiType.MOBILE, FeedApiType.fromValue(99))
        assertEquals(FeedApiType.MOBILE, FeedApiType.fromValue(Int.MIN_VALUE))
        assertEquals(FeedApiType.MOBILE, FeedApiType.fromValue(Int.MAX_VALUE))
    }

    @Test
    fun knownValuesRoundTrip() {
        FeedApiType.entries.forEach { type ->
            assertEquals(type, FeedApiType.fromValue(type.value))
        }
    }

    @Test
    fun defaultFallbackIsMobileAndFromValueNeverThrows() {
        // 兜底常量本身就是 MOBILE
        assertEquals(FeedApiType.MOBILE, FeedApiType.fromValue(FeedApiType.MOBILE.value))
        assertEquals(FeedApiType.MOBILE, FeedApiType.fromValue(0x7fff_ffff))
    }
}
