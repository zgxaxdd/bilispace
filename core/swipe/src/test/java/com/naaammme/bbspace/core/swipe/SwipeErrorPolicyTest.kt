package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败分类与重试策略（SW-13 验收项）：四类分类的判据、自动重试的"该与不该"、
 * 退避序列与上限、手动重试去抖（含时钟回拨）。
 */
class SwipeErrorPolicyTest {

    @Test
    fun `classify separates network stream decode and unknown`() {
        assertEquals(
            SwipeErrorPolicy.Kind.Network,
            SwipeErrorPolicy.classify("java.net.UnknownHostException: Unable to resolve host")
        )
        assertEquals(
            SwipeErrorPolicy.Kind.Network,
            SwipeErrorPolicy.classify("任何错误", networkAvailable = false)
        )
        assertEquals(
            SwipeErrorPolicy.Kind.Stream,
            SwipeErrorPolicy.classify("grpc status 503: playurl unavailable")
        )
        assertEquals(
            SwipeErrorPolicy.Kind.Decode,
            SwipeErrorPolicy.classify("MediaCodec decoder init failed")
        )
        assertEquals(SwipeErrorPolicy.Kind.Unknown, SwipeErrorPolicy.classify(null))
        assertEquals(SwipeErrorPolicy.Kind.Unknown, SwipeErrorPolicy.classify("说不清的错误"))
    }

    @Test
    fun `decode wins over generic stream markers`() {
        // "codec ... 500" 这类混合串要按解码处理（重试同址大概率仍失败）
        assertEquals(
            SwipeErrorPolicy.Kind.Decode,
            SwipeErrorPolicy.classify("MediaCodec error 500 on surface")
        )
    }

    @Test
    fun `auto retry only for stream-like failures and respects cap and backoff`() {
        // 取流失败：退避 1s → 3s，第三次不再自动
        assertEquals(1_000L, SwipeErrorPolicy.autoRetryDelayMs(0, SwipeErrorPolicy.Kind.Stream))
        assertEquals(3_000L, SwipeErrorPolicy.autoRetryDelayMs(1, SwipeErrorPolicy.Kind.Stream))
        assertNull(SwipeErrorPolicy.autoRetryDelayMs(2, SwipeErrorPolicy.Kind.Stream))
        // 未知类：同样值得自动重试（多为服务侧抖动），走同一退避
        assertEquals(1_000L, SwipeErrorPolicy.autoRetryDelayMs(0, SwipeErrorPolicy.Kind.Unknown))
        // 网络类/解码类：不自动（等网络回来 / 同址重试无意义）
        assertNull(SwipeErrorPolicy.autoRetryDelayMs(0, SwipeErrorPolicy.Kind.Network))
        assertNull(SwipeErrorPolicy.autoRetryDelayMs(0, SwipeErrorPolicy.Kind.Decode))
        // 负次数防御
        assertNull(SwipeErrorPolicy.autoRetryDelayMs(-1, SwipeErrorPolicy.Kind.Stream))
    }

    @Test
    fun `manual retry is debounced and tolerates clock rollback`() {
        assertFalse(SwipeErrorPolicy.isManualRetryDebounced(nowMs = 1_000L, lastRetryMs = null))
        // 间隔不足：挡
        assertTrue(SwipeErrorPolicy.isManualRetryDebounced(nowMs = 1_500L, lastRetryMs = 1_000L))
        // 够间隔：放行
        assertFalse(SwipeErrorPolicy.isManualRetryDebounced(nowMs = 2_000L, lastRetryMs = 1_000L))
        // 时钟回拨：按"刚点过"挡掉（宁愿多挡一次，别把引擎打爆）
        assertTrue(SwipeErrorPolicy.isManualRetryDebounced(nowMs = 500L, lastRetryMs = 1_000L))
    }

    @Test
    fun `messages are human and distinct per kind`() {
        val network = SwipeErrorPolicy.messageOf(SwipeErrorPolicy.Kind.Network)
        val decode = SwipeErrorPolicy.messageOf(SwipeErrorPolicy.Kind.Decode)
        val stream = SwipeErrorPolicy.messageOf(SwipeErrorPolicy.Kind.Stream)
        // 网络类要给出"检查网络"的行动指引
        assertTrue(network.contains("网络"))
        // 解码类要给出"换一支"的出口
        assertTrue(decode.contains("换一支"))
        // 三类文案互不相同（分类对用户可见）
        assertEquals(3, setOf(network, decode, stream).size)
    }
}
