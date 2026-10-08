package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「起播超时」判据的边界（R-19）。
 *
 * 这是 `CdnHealthPolicy.Event.FirstFrameTimeout`（−180 分）的**唯一信号源**：
 * 在此之前该事件在真机上从不产生（定义在策略里没人调），"起播总是很慢的线路"永远不会被降权。
 */
class FirstFrameTimeoutPolicyTest {

    private fun timedOut(
        nowMs: Long,
        startedAtMs: Long = STARTED_AT,
        firstFrameRendered: Boolean = false,
        playWhenReady: Boolean = true,
        buffering: Boolean = true,
        alreadyReported: Boolean = false
    ) = FirstFrameTimeoutPolicy.isTimedOut(
        nowMs = nowMs,
        startedAtMs = startedAtMs,
        firstFrameRendered = firstFrameRendered,
        playWhenReady = playWhenReady,
        buffering = buffering,
        alreadyReported = alreadyReported
    )

    @Test
    fun timesOutOnlyAfterTheThreshold() {
        assertFalse("刚开播 1ms：不判超时", timedOut(nowMs = STARTED_AT + 1L))
        assertFalse(
            "差 1ms 到阈值：不判（边界必须闭在 ≥）",
            timedOut(nowMs = STARTED_AT + FirstFrameTimeoutPolicy.TIMEOUT_MS - 1L)
        )
        assertTrue(
            "正好到阈值：判超时",
            timedOut(nowMs = STARTED_AT + FirstFrameTimeoutPolicy.TIMEOUT_MS)
        )
        assertTrue(
            "超过阈值：判超时",
            timedOut(nowMs = STARTED_AT + FirstFrameTimeoutPolicy.TIMEOUT_MS + 5_000L)
        )
    }

    @Test
    fun fiveConditionsMustAllHold() {
        val far = STARTED_AT + FirstFrameTimeoutPolicy.TIMEOUT_MS * 3
        assertFalse("已出画面 ⇒ 不算起播超时", timedOut(nowMs = far, firstFrameRendered = true))
        assertFalse("用户没点播放（暂停/未起播）⇒ 不算", timedOut(nowMs = far, playWhenReady = false))
        assertFalse(
            "已 READY（数据到了）却不出帧 ⇒ 是渲染/画面绑定的事，不许算到线路上 —— " +
                "真机实测断网恢复后重建就是这样（1 秒 READY、画面 10 秒后才出）",
            timedOut(nowMs = far, buffering = false)
        )
        assertFalse("还没有开播时刻（未 setSource，哨兵值 ≤0）⇒ 无从计时", timedOut(nowMs = far, startedAtMs = -1L))
        assertFalse("已经报过一次 ⇒ 不再重复（500ms 轮询会连报）", timedOut(nowMs = far, alreadyReported = true))
        assertTrue("五条都满足才判超时（此处：在播 + 未出帧 + 仍在缓冲 + 有过开播时刻 + 超阈值）", timedOut(nowMs = far))
    }

    private companion object {
        /** 任意非 0 的开播时刻（单调钟，真机上必然 > 0）。 */
        const val STARTED_AT = 1_000L
    }
}
