package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡顿阶梯恢复策略的单测（纯逻辑，时间注入）。
 *
 * 钉住的约定：
 * 1. 正常播放（位置/缓冲都在前进）**永远不触发**；
 * 2. 阈值边界：静默 7999ms 不动、8000ms 才动第一刀；
 * 3. 阶梯 4 档每档只触发一次，档间有冷却，走完不再重复（防"在同一档上反复 flush"的活锁）；
 * 4. 一旦出现进展（位置前进 / 缓冲前进 / seek）阶梯**复位**；
 * 5. 用户暂停（playWhenReady=false）时看门狗解除，恢复播放后重新计时。
 */
class StallRecoveryPolicyTest {

    private fun StallRecoveryPolicy.tick(
        nowMs: Long,
        positionMs: Long = 1_000L,
        bufferedMs: Long = 6_000L,
        playing: Boolean = true
    ): StallRecoveryPolicy.Action = sample(nowMs, positionMs, bufferedMs, playing)

    @Test
    fun `normal playback never triggers`() {
        val policy = StallRecoveryPolicy()
        var t = 0L
        repeat(40) { i ->
            t = i * 500L
            // 位置每 500ms 前进 500ms，缓冲始终领先 5s。
            assertEquals(
                "正常播放不许触发恢复动作（t=$t）",
                StallRecoveryPolicy.Action.NONE,
                policy.tick(t, positionMs = i * 500L, bufferedMs = i * 500L + 5_000L)
            )
        }
    }

    @Test
    fun `threshold boundary is eight seconds of no progress`() {
        val policy = StallRecoveryPolicy()
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(0L))
        assertEquals(
            "7999ms 无进展还不够格（阈值是 8000）",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(7_999L - 0L)
        )
        assertEquals(
            "8000ms 无进展 → 第一刀（原地 seek flush）",
            StallRecoveryPolicy.Action.FLUSH,
            policy.tick(8_000L)
        )
    }

    @Test
    fun `ladder fires each stage exactly once with cooldown`() {
        val policy = StallRecoveryPolicy()
        policy.tick(0L)
        assertEquals(StallRecoveryPolicy.Action.FLUSH, policy.tick(8_000L))
        // 冷却 4s 未过：不许在同一档上连续动手。
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(11_999L))
        assertEquals(StallRecoveryPolicy.Action.SEEK_TO_SELF, policy.tick(12_000L))
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(15_999L))
        assertEquals(StallRecoveryPolicy.Action.REBUILD, policy.tick(16_000L))
        assertEquals(StallRecoveryPolicy.Action.REPORT_ERROR, policy.tick(20_000L))
        // 已报告过：同一段卡死里不再重复（直到出现进展才复位）。
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(24_000L))
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(30_000L))
        assertEquals("走完阶梯后档位停在终态", StallRecoveryPolicy.STAGE_DONE, policy.stage())
    }

    @Test
    fun `progress resets the ladder`() {
        val policy = StallRecoveryPolicy()
        policy.tick(0L)
        assertEquals(StallRecoveryPolicy.Action.FLUSH, policy.tick(8_000L))
        // 缓冲恢复增长 = 有进展 ⇒ 阶梯复位。
        assertEquals(
            StallRecoveryPolicy.Action.NONE,
            policy.tick(9_000L, bufferedMs = 7_000L)
        )
        assertEquals("复位后重新计时", StallRecoveryPolicy.Action.NONE, policy.tick(16_999L))
        assertEquals(
            "再次静默满 8s 从第一刀重来（不是直接跳到第二档）",
            StallRecoveryPolicy.Action.FLUSH,
            policy.tick(17_000L)
        )
    }

    @Test
    fun `pause disarms the watchdog`() {
        val policy = StallRecoveryPolicy()
        policy.tick(0L)
        assertEquals(
            "暂停不是卡死，不许动手",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(9_000L, playing = false)
        )
        // 恢复播放：重新建立基线，再数 8s。
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(10_000L))
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(17_999L))
        assertEquals(StallRecoveryPolicy.Action.FLUSH, policy.tick(18_000L))
    }

    @Test
    fun `backward seek re-baselines buffered position`() {
        // 修复前判据只看"bufferedMs 是否大于历史最大值"：向后 seek 后缓冲必然低于旧基线，
        // 哪怕缓冲正在正常增长也会被误判成"卡死"。这里钉住 seek 会重置基线。
        val policy = StallRecoveryPolicy()
        policy.tick(0L, positionMs = 100_000L, bufferedMs = 130_000L)
        assertEquals(
            "向后 seek 本身是进展",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(500L, positionMs = 20_000L, bufferedMs = 40_000L)
        )
        assertEquals(
            "缓冲从 40k 涨到 45k（仍低于旧基线 130k）也必须算进展",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(5_500L, positionMs = 20_000L, bufferedMs = 45_000L)
        )
        assertEquals(StallRecoveryPolicy.Action.NONE, policy.tick(13_499L, positionMs = 20_000L, bufferedMs = 45_000L))
        assertEquals(
            "从最后一次真实进展（5500ms）数满 8s 才动第一刀",
            StallRecoveryPolicy.Action.FLUSH,
            policy.tick(13_500L, positionMs = 20_000L, bufferedMs = 45_000L)
        )
    }

    @Test
    fun `msSinceAdvance tracks the silence window`() {
        val policy = StallRecoveryPolicy()
        assertEquals("没有基线时静默时长为 0", 0L, policy.msSinceAdvance(5_000L))
        policy.tick(10_000L)
        assertEquals(0L, policy.msSinceAdvance(10_000L))
        assertEquals(3_000L, policy.msSinceAdvance(13_000L))
    }

    /**
     * 缓冲**回退**不是进展，但必须把基线一起下调（评审 A4 的盲区）。
     *
     * 回退的真实来源是我们自己那一刀：`FLUSH`/`SEEK_TO_SELF` 都做原地 seek，
     * ExoPlayer 会丢掉前景缓冲 ⇒ `bufferedPosition` 掉下来。若还拿"回退前的旧高位"
     * 当基线，接下来的充值期会被判成"仍无进展"，冷却一到就烧下一档 —— 阶梯被一段
     * 正常恢复烧穿。
     */
    @Test
    fun `buffer regression lowers the baseline instead of faking progress`() {
        val policy = StallRecoveryPolicy()
        policy.tick(0L, positionMs = 10_000L, bufferedMs = 30_000L)
        assertEquals(
            "缓冲回退不算进展（不许重置停滞计时）",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(1_000L, positionMs = 10_000L, bufferedMs = 12_000L)
        )
        assertEquals(
            "回退后的增长（13k，仍低于旧基线 30k）必须重新算进展",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(2_000L, positionMs = 10_000L, bufferedMs = 13_000L)
        )
        assertEquals(
            "从最后一次真实进展（2000ms）数满 8s 才动第一刀",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(9_999L, positionMs = 10_000L, bufferedMs = 13_000L)
        )
        assertEquals(
            StallRecoveryPolicy.Action.FLUSH,
            policy.tick(10_000L, positionMs = 10_000L, bufferedMs = 13_000L)
        )
    }

    /**
     * 阶梯自己那一刀（REBUILD）重建会话后**保留档位**（评审 A1，确定性缺陷）。
     *
     * `recoverSession` 会 `resetRuntimeState()`：若连阶梯一起清零，同一段卡死会
     * `FLUSH→SEEK→REBUILD→清零→FLUSH→SEEK→REBUILD…`，直到 `RebuildThrottle`
     * （60s/3 次）拒绝，约 74s 才落错误态，期间烧掉 3 次整会话重建。
     */
    @Test
    fun `session rebuilt keeps the ladder stage`() {
        val policy = StallRecoveryPolicy()
        policy.tick(0L)
        assertEquals(StallRecoveryPolicy.Action.FLUSH, policy.tick(8_000L))
        assertEquals(StallRecoveryPolicy.Action.SEEK_TO_SELF, policy.tick(12_000L))
        assertEquals(StallRecoveryPolicy.Action.REBUILD, policy.tick(16_000L))
        // 引擎在 REBUILD 后重建会话：只作废读数基线，保留档位。
        policy.onSessionRebuilt()
        assertEquals(
            "新 player 实例要重新建立基线（第一拍不动作）",
            StallRecoveryPolicy.Action.NONE,
            policy.tick(16_500L, positionMs = 0L, bufferedMs = 2_000L)
        )
        assertEquals(
            "重建后仍无进展必须直接升到 REPORT，不许把阶梯清零重烧 3 次重建",
            StallRecoveryPolicy.Action.REPORT_ERROR,
            policy.tick(24_500L, positionMs = 0L, bufferedMs = 2_000L)
        )
        assertEquals(StallRecoveryPolicy.STAGE_DONE, policy.stage())
    }
}
