package com.naaammme.bbspace.core.model.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「换线/开流失败」纳入健康度后的**性质测试**（2026-10-06 闭环修复）。
 *
 * ## 为什么需要这一条事件、以及为什么惩罚必须轻 + 封顶
 *
 * 闭环修复前，换线发生在 `CdnFailoverDataSource` 内部，**结果不回写健康度**：
 * - 坏线路永远不被降权（下次还会被排到前面）；
 * - `PlaybackReady` 的"加分"也永远只加给**选中的**那条，而不是**实际服务**的那条。
 *
 * 修复后传输层会逐条上报。但**不能用 `PLAYER_ERROR_PENALTY`（220）**：
 * 换线是"逐条试"的，一次播放可能同时失败好几条线 —— 重惩罚会让**所有**线路一起
 * 变成负分，选线直接失效。所以这里是独立的 −40，并且**计数封顶**。
 */
class CdnLineFailureHealthTest {

    private val host = "upos-sz-mirrorhw.bilivideo.com"

    private fun fail(times: Int, now: Long = 1_000L): CdnHealthPolicy.Health {
        var health = CdnHealthPolicy.Health(host = host, windowStartedAtMs = now)
        repeat(times) {
            health = CdnHealthPolicy.record(health, CdnHealthPolicy.Event.LineOpenFailure, now)
        }
        return health
    }

    @Test
    fun lineOpenFailureIsRecordedSeparatelyFromPlayerError() {
        val health = fail(1)
        assertEquals(1, health.lineOpenFailureCount)
        assertEquals(
            "不能污染 playbackErrorCount —— 语义不同（整支失败 vs 这条线没通）",
            0,
            health.playbackErrorCount
        )
    }

    @Test
    fun eachFailureCostsTheModestPenalty() {
        val baseline = CdnHealthPolicy.score(CdnHealthPolicy.Health(host = host))
        val one = CdnHealthPolicy.score(fail(1))
        assertEquals(
            "一次换线失败应恰好扣 ${CdnHealthPolicy.LINE_OPEN_FAILURE_PENALTY} 分",
            baseline - CdnHealthPolicy.LINE_OPEN_FAILURE_PENALTY,
            one
        )
        assertTrue(
            "而且必须比一次播放器报错轻得多（否则换线试几条就把所有线路打到负分）",
            baseline - one < CdnHealthPolicy.PLAYER_ERROR_PENALTY
        )
    }

    /** **关键性质**：反复失败不能把分数无限拉低（否则线路永无翻身之日）。 */
    @Test
    fun failurePenaltyIsCapped() {
        val atCap = CdnHealthPolicy.score(fail(CdnHealthPolicy.LINE_OPEN_FAILURE_CAP))
        val wayPastCap = CdnHealthPolicy.score(fail(CdnHealthPolicy.LINE_OPEN_FAILURE_CAP * 50))
        assertEquals("超过封顶后的分数必须停止下降", atCap, wayPastCap)
        val baseline = CdnHealthPolicy.score(CdnHealthPolicy.Health(host = host))
        assertEquals(
            "封顶惩罚 = CAP × 单次惩罚",
            baseline - CdnHealthPolicy.LINE_OPEN_FAILURE_CAP * CdnHealthPolicy.LINE_OPEN_FAILURE_PENALTY,
            atCap
        )
    }

    /** 折现必须覆盖新计数器 —— 否则"半小时前的失败"会永久挂着（与既有口径不一致）。 */
    @Test
    fun decayAlsoDecaysTheNewCounter() {
        val now = 1_000_000L
        val health = fail(8, now)
        val decayed = CdnHealthPolicy.decay(health, now + CdnHealthPolicy.DECAY_PERIOD_MS * 2)
        assertTrue(
            "两次折现周期后应显著减少（实际 ${decayed.lineOpenFailureCount}）",
            decayed.lineOpenFailureCount < health.lineOpenFailureCount
        )
    }

    /** 成功仍然只加分、且不因失败而失效（回归）。 */
    @Test
    fun successStillCreditsAfterFailures() {
        var health = fail(2)
        health = CdnHealthPolicy.record(health, CdnHealthPolicy.Event.PlaybackReady, 2_000L)
        assertEquals(1, health.readyCount)
        assertEquals(2, health.lineOpenFailureCount)
    }
}
