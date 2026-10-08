package com.naaammme.bbspace.core.model.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能选线的纯策略验收。
 *
 * 数值点**逐条对照 BiliPai 的既有测试**（`CdnRegionPolicyTest.kt` /
 * `CdnTransferPolicyTest.kt` / `PlaybackStallRecoveryPolicyTest.kt`），
 * 这样"移植对不对"有客观判据，而不是"看起来像"。
 */
class CdnSmartSelectionTest {

    private val hour = 60L * 60L * 1_000L

    // ── CdnHealthPolicy ────────────────────────────────────────────────────

    @Test
    fun unobservedHostScoresNeutralBaseline() {
        assertEquals(
            "没有任何观测的线路必须是中性分（0 分会让它永远排不上）",
            CdnHealthPolicy.BASELINE_SCORE,
            CdnHealthPolicy.score(null)
        )
        assertEquals(
            CdnHealthPolicy.BASELINE_SCORE,
            CdnHealthPolicy.score(CdnHealthPolicy.Health(host = "h"))
        )
    }

    /** 对照 BiliPai `CdnRegionPolicyTest.kt:302-330` 的两个手算值。 */
    @Test
    fun scoreMatchesUpstreamHandComputedValues() {
        val fast = CdnHealthPolicy.Health(
            host = "fast.bilivideo.com",
            readyCount = 3,
            probeLatencyMs = 320L,
            probeSpeedKbps = 5_120L
        )
        // 1000 + 5120/128=40 + 3*25=75 − 320/20=16
        assertEquals(1_099, CdnHealthPolicy.score(fast))

        val laggy = CdnHealthPolicy.Health(
            host = "laggy.bilivideo.com",
            bufferingCount = 4,
            playbackErrorCount = 1,
            probeLatencyMs = 80L,
            probeSpeedKbps = 1_024L
        )
        // 1000 + 1024/128=8 + 0 − 80/20=4 − (220 + 4*55=220)
        assertEquals(564, CdnHealthPolicy.score(laggy))
    }

    @Test
    fun errorPenaltiesAndCreditsAreWeightedAsDocumented() {
        val base = CdnHealthPolicy.Health(host = "h")
        assertEquals(820, CdnHealthPolicy.score(CdnHealthPolicy.record(base, CdnHealthPolicy.Event.FirstFrameTimeout, 1L)))
        assertEquals(780, CdnHealthPolicy.score(CdnHealthPolicy.record(base, CdnHealthPolicy.Event.PlayerError, 1L)))
        assertEquals(945, CdnHealthPolicy.score(CdnHealthPolicy.record(base, CdnHealthPolicy.Event.Buffering, 1L)))
        assertEquals(1_025, CdnHealthPolicy.score(CdnHealthPolicy.record(base, CdnHealthPolicy.Event.PlaybackReady, 1L)))
    }

    /**
     * 「惩罚必须有证据」：会话层的 `Buffering` 只在 **本次会话刚发生过真实网络读** 时才记。
     *
     * 18 个断言点就是这张真值表：
     * - `Buffering` 是**惩罚**（−55/次，可累计且无上限）⇒ 无网络证据时**必须不记**；
     * - `PlaybackReady` 是**奖励**且封顶（`READY_CREDIT_CAP`）⇒"选它播成功了"本身算弱证据，照记；
     * - 其余事件不由会话层按 host 汇总（`LineOpenFailure` 由传输层按真实 URL 记）⇒ 不拦。
     */
    @Test
    fun sessionBufferingRequiresNetworkEvidence() {
        assertTrue(
            "有网络证据 ⇒ 缓冲惩罚照记（这是真实线路问题的信号）",
            CdnHealthPolicy.shouldRecordSessionEvent(CdnHealthPolicy.Event.Buffering, networkActive = true)
        )
        assertFalse(
            "无网络证据（纯缓存重播）⇒ 不许记缓冲惩罚 —— " +
                "实测会给某条没出力的线白扣 −220 分并显示「易缓冲」",
            CdnHealthPolicy.shouldRecordSessionEvent(CdnHealthPolicy.Event.Buffering, networkActive = false)
        )
        assertTrue(
            "起播超时同样是惩罚 ⇒ 有证据才记（R-19 起它才真的会上报）",
            CdnHealthPolicy.shouldRecordSessionEvent(CdnHealthPolicy.Event.FirstFrameTimeout, networkActive = true)
        )
        assertFalse(
            "无网络证据时的「起播慢」是播放器/解码的事，不许算到线路上",
            CdnHealthPolicy.shouldRecordSessionEvent(CdnHealthPolicy.Event.FirstFrameTimeout, networkActive = false)
        )
        for (event in listOf(
            CdnHealthPolicy.Event.PlaybackReady,
            CdnHealthPolicy.Event.LineOpenFailure,
            CdnHealthPolicy.Event.PlayerError,
            CdnHealthPolicy.Event.AudioTrackMissing
        )) {
            assertTrue(
                "$event 不该被网络证据判据拦住（奖励/传输层事件）",
                CdnHealthPolicy.shouldRecordSessionEvent(event, networkActive = false)
            )
        }
    }

    /** 音轨缺失与播放器报错**共用**一个计数器（BiliPai 的既有行为，测试锁定）。 */    @Test
    fun audioTrackMissingSharesThePlayerErrorCounter() {
        val h = CdnHealthPolicy.Health(host = "h")
            .let { CdnHealthPolicy.record(it, CdnHealthPolicy.Event.FirstFrameTimeout, 1L) }
            .let { CdnHealthPolicy.record(it, CdnHealthPolicy.Event.AudioTrackMissing, 2L) }
            .let { CdnHealthPolicy.record(it, CdnHealthPolicy.Event.PlayerError, 3L) }
        assertEquals(1, h.firstFrameTimeoutCount)
        assertEquals(2, h.playbackErrorCount)
    }

    /** 对照 BiliPai `CdnTransferPolicyTest.kt:54-67`（1 小时 = 2 个衰减周期）。 */
    @Test
    fun decayHalvesCountsPerThirtyMinutePeriod() {
        val old = CdnHealthPolicy.Health(
            host = "h",
            bufferingCount = 8,
            playbackErrorCount = 4,
            readyCount = 8,
            probeLatencyMs = 100L,
            probeSpeedKbps = 10_000L,
            lastProbeAtMs = 1L,
            lastUpdatedAtMs = 3_600_001L,
            windowStartedAtMs = 1L
        )
        val decaved = CdnHealthPolicy.decay(old, 3_600_001L)
        assertEquals(2, decaved.bufferingCount)
        assertEquals(1, decaved.playbackErrorCount)
        assertEquals(2, decaved.readyCount)
        assertNull("探测值超过 2 分钟保鲜期必须作废", decaved.probeSpeedKbps)
        assertNull(decaved.probeLatencyMs)
        assertEquals("同一时刻重复折现必须幂等", decaved, CdnHealthPolicy.decay(decaved, 3_600_001L))
    }

    @Test
    fun decayIsBoundedAndDoesNotGoNegative() {
        val h = CdnHealthPolicy.Health(host = "h", bufferingCount = 3, lastUpdatedAtMs = 1L)
        val far = CdnHealthPolicy.decay(h, 1L + 400L * hour)
        assertEquals("折现周期封顶，不会因为离线很久就算成负数", 0, far.bufferingCount)
    }

    @Test
    fun probeResultIsReplacedNotAccumulated() {
        val first = CdnHealthPolicy.recordProbe(
            CdnHealthPolicy.Health(host = "h"), latencyMs = 120L, speedKbps = 3_000L,
            success = true, nowMs = 1_000L
        )
        val second = CdnHealthPolicy.recordProbe(
            first, latencyMs = 400L, speedKbps = 900L, success = true, nowMs = 2_000L
        )
        assertEquals(400L, second.probeLatencyMs)
        assertEquals(900L, second.probeSpeedKbps)

        val failed = CdnHealthPolicy.recordProbe(
            second, latencyMs = null, speedKbps = null, success = false, nowMs = 3_000L
        )
        assertNull("探测失败必须清掉上次的好成绩，否则它会继续吃旧分", failed.probeLatencyMs)
        assertNull(failed.probeSpeedKbps)
        assertEquals(1, failed.playbackErrorCount)
        assertEquals("失败也要进冷却，否则会疯狂重试", 3_000L, failed.lastProbeAtMs)
    }

    @Test
    fun statusLabelFollowsTheDocumentedPriority() {
        val base = CdnHealthPolicy.Health(host = "h")
        assertEquals("未检测", CdnHealthPolicy.statusLabel(null))
        assertEquals("未检测", CdnHealthPolicy.statusLabel(base))
        assertEquals(
            "不稳定",
            CdnHealthPolicy.statusLabel(
                base.copy(playbackErrorCount = 1, firstFrameTimeoutCount = 1, lastUpdatedAtMs = 1L)
            )
        )
        assertEquals("易缓冲", CdnHealthPolicy.statusLabel(base.copy(bufferingCount = 3, lastUpdatedAtMs = 1L)))
        assertEquals(
            "速度较好",
            CdnHealthPolicy.statusLabel(
                base.copy(bufferingCount = 1, probeSpeedKbps = 4_096L, lastUpdatedAtMs = 1L)
            )
        )
        assertEquals(
            "延迟较低",
            CdnHealthPolicy.statusLabel(
                base.copy(readyCount = 1, probeLatencyMs = 42L, lastUpdatedAtMs = 10_000L)
            )
        )
        assertEquals("可用", CdnHealthPolicy.statusLabel(base.copy(readyCount = 2, lastUpdatedAtMs = 1L)))
    }

    @Test
    fun neverProbedHostIsImmediatelyProbeable() {
        assertTrue(
            "从未探测过的线路不该被判成冷却中（BiliPai 那个 10 分钟误判已修）",
            CdnHealthPolicy.canProbe(null, nowMs = 1_000L)
        )
        val probed = CdnHealthPolicy.Health(host = "h", lastProbeAtMs = 9_500L)
        assertFalse(CdnHealthPolicy.canProbe(probed, nowMs = 10_000L, cooldownMs = 1_000L))
        assertEquals(
            500L,
            CdnHealthPolicy.probeCooldownRemainingMs(probed, nowMs = 10_000L, cooldownMs = 1_000L)
        )
    }

    // ── CdnSmartSelectionPolicy ────────────────────────────────────────────

    @Test
    fun orderingWithoutObservationsKeepsServerOrder() {
        val urls = listOf(
            "https://a.bilivideo.com/1.m4s",
            "https://b.bilivideo.com/2.m4s",
            "https://c.bilivideo.com/3.m4s"
        )
        assertEquals(
            "都没观测过时，服务端顺序就是唯一线索，必须原样保留",
            urls,
            CdnSmartSelectionPolicy.orderUrlsByHealth(urls, emptyMap())
        )
    }

    /** 对照 BiliPai `CdnRegionPolicyTest.kt:308-330`：烂线路即使排在前面也要被翻下去。 */
    @Test
    fun healthOrderingOvertakesServerOrder() {
        val bad = "https://laggy.bilivideo.com/v.m4s"
        val good = "https://fast.bilivideo.com/v.m4s"
        val health = mapOf(
            "laggy.bilivideo.com" to CdnHealthPolicy.Health(
                host = "laggy.bilivideo.com",
                bufferingCount = 4,
                playbackErrorCount = 1,
                probeSpeedKbps = 1_024L,
                probeLatencyMs = 80L
            ),
            "fast.bilivideo.com" to CdnHealthPolicy.Health(
                host = "fast.bilivideo.com",
                readyCount = 3,
                probeSpeedKbps = 5_120L,
                probeLatencyMs = 320L
            )
        )
        assertEquals(listOf(good, bad), CdnSmartSelectionPolicy.orderUrlsByHealth(listOf(bad, good), health))
    }

    @Test
    fun duplicateUrlsCollapseToTheFirstOccurrence() {
        val url = "https://a.bilivideo.com/1.m4s"
        assertEquals(
            listOf(url),
            CdnSmartSelectionPolicy.orderUrlsByHealth(listOf(url, url, url), emptyMap())
        )
    }

    /**
     * **用户显式指定线路时，健康度抢不走它的首位** —— 这是有意的产品契约
     * （对照 BiliPai `CdnRegionPolicyTest.kt:183-195`）。
     */
    @Test
    fun preferredCandidateWinsRegardlessOfHealth() {
        val preferred = CdnSmartSelectionPolicy.Candidate(
            url = "https://user-picked.bilivideo.com/v.m4s",
            source = CdnSmartSelectionPolicy.Source.Preferred
        )
        val original = CdnSmartSelectionPolicy.Candidate(
            url = "https://fast.bilivideo.com/v.m4s",
            source = CdnSmartSelectionPolicy.Source.Original
        )
        val health = mapOf(
            "user-picked.bilivideo.com" to CdnHealthPolicy.Health(
                host = "user-picked.bilivideo.com",
                playbackErrorCount = 3
            ),
            "fast.bilivideo.com" to CdnHealthPolicy.Health(
                host = "fast.bilivideo.com",
                readyCount = 10,
                probeSpeedKbps = 20_000L
            )
        )
        val relaxed = CdnSmartSelectionPolicy.orderCandidates(listOf(original, preferred), health)
        assertEquals("指定的线路排第一", preferred.url, relaxed.first().url)
        assertEquals("其余线路仍在后面兜底", original.url, relaxed.last().url)

        val strict = CdnSmartSelectionPolicy.orderCandidates(listOf(original, preferred), health, strictPreferred = true)
        assertEquals("严格模式只返回指定的线路", listOf(preferred.url), strict.map { it.url })

        val strictNoPreferred = CdnSmartSelectionPolicy.orderCandidates(
            listOf(original), health, strictPreferred = true
        )
        assertEquals(
            "严格模式下没有指定线路时必须完整回退，不能把播放锁死",
            listOf(original.url),
            strictNoPreferred.map { it.url }
        )
    }

    @Test
    fun hostExtractionIsCaseInsensitiveAndSafe() {
        assertEquals("a.bilivideo.com", CdnSmartSelectionPolicy.hostOf("https://A.BiliVideo.com/x.m4s?e=1"))
        assertEquals("", CdnSmartSelectionPolicy.hostOf("not a url"))
    }

    // ── 卡顿切换 ────────────────────────────────────────────────────────────

    private fun stall(
        buffering: Boolean = true,
        playWhenReady: Boolean = true,
        firstFrame: Boolean = true,
        forwardBufferMs: Long = 0L,
        current: Int = 0,
        count: Int = 3,
        attempted: Set<Int> = setOf(0),
        adaptive: Boolean = false
    ) = CdnSmartSelectionPolicy.resolveStallRecovery(
        buffering = buffering,
        playWhenReady = playWhenReady,
        firstFrameRendered = firstFrame,
        forwardBufferMs = forwardBufferMs,
        currentIndex = current,
        candidateCount = count,
        attemptedIndexes = attempted,
        adaptivePlayback = adaptive
    )

    @Test
    fun stalledPlaybackSwitchesToTheNextUntriedCdn() {
        assertTrue(stall().shouldSwitch)
        assertEquals(1, stall().nextIndex)
    }

    @Test
    fun eachCandidateIsSelectedAtMostOnce() {
        assertEquals(2, stall(current = 1, attempted = setOf(0, 1)).nextIndex)
        assertNull(
            "全部试过了就别再切（否则会在两条烂线路之间弹来弹去）",
            stall(current = 2, attempted = setOf(0, 1, 2)).nextIndex
        )
        assertEquals(
            "环形扫描要能绕回去试前面没试过的",
            0,
            stall(current = 2, attempted = setOf(1, 2)).nextIndex
        )
    }

    @Test
    fun everyGuardIsRequired() {
        assertNull("不缓冲不切", stall(buffering = false).nextIndex)
        assertNull("用户暂停不切", stall(playWhenReady = false).nextIndex)
        assertNull("首帧前不切（那是起播路径的事）", stall(firstFrame = false).nextIndex)
        assertNull("前面还有缓冲就不叫卡", stall(forwardBufferMs = 1L).nextIndex)
        assertNull("只有一条线无从可切", stall(count = 1).nextIndex)
        assertNull("自适应画质交给播放器做 ABR", stall(adaptive = true).nextIndex)
    }

    /** 对照 BiliPai 的既有测试名：AUTO 档留在 Media3，锁画质才换线。 */
    @Test
    fun adaptivePlaybackStaysWithMedia3WhileLockedQualityMaySwitch() {
        assertNull(stall(adaptive = true).nextIndex)
        assertEquals(1, stall(adaptive = false).nextIndex)
    }
}
