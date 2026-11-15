package com.naaammme.bbspace.infra.player.cdn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CDN 连接"降级 → 有界恢复"判据的单测（纯逻辑）。
 *
 * 背景：`CdnPlaybackDataSource.read()` 窗口失败时把 `parallel` 置 false，修复前**本会话
 * 永久单连接**；而 `resolveCdnConnections` 的救援分支是"缓冲 < 5s → 3 连接"，于是
 * 缓冲掉到 5 秒以下时救援永远进不去 ⇒ 用户看到"停在 5 秒的缓冲上"。
 *
 * 这里钉住：冷却未过不重升、缓冲不够厚不重升、非 Wi-Fi/设置关/运行时停止都不重升；
 * 三者同时满足才允许重升。
 */
class CdnConnectionPolicyTest {

    private fun shouldRestore(
        nowMs: Long = 60_000L,
        disabledAtMs: Long = 10_000L,
        bufferMs: Long = 20_000L,
        parallelAllowed: Boolean = true,
        wifi: Boolean = true
    ): Boolean = CdnConnectionPolicy.shouldRestoreParallel(
        nowMs = nowMs,
        disabledAtMs = disabledAtMs,
        bufferMs = bufferMs,
        parallelAllowed = parallelAllowed,
        wifi = wifi
    )

    @Test
    fun `restores only after the cooldown has elapsed`() {
        val disabledAt = 10_000L
        val cooldown = CdnConnectionPolicy.PARALLEL_RETRY_COOLDOWN_MS
        assertFalse(
            "冷却差 1ms 都不许重升（否则在坏网络上反复抖动）",
            shouldRestore(nowMs = disabledAt - 1 + cooldown, disabledAtMs = disabledAt)
        )
        assertTrue(
            "冷却恰好走满 → 允许重升",
            shouldRestore(nowMs = disabledAt + cooldown, disabledAtMs = disabledAt)
        )
    }

    @Test
    fun `restores only when buffered far enough ahead`() {
        val disabledAt = 10_000L
        val readyAt = disabledAt + CdnConnectionPolicy.PARALLEL_RETRY_COOLDOWN_MS
        assertFalse(
            "缓冲 9999ms 还不够厚：重升只会再失败一次",
            shouldRestore(nowMs = readyAt, disabledAtMs = disabledAt, bufferMs = 9_999L)
        )
        assertTrue(
            "缓冲回到 10000ms → 允许重升",
            shouldRestore(nowMs = readyAt, disabledAtMs = disabledAt, bufferMs = 10_000L)
        )
    }

    @Test
    fun `never restores without a prior fallback`() {
        assertFalse(
            "没降级过（disabledAt=0）就不存在重升",
            shouldRestore(disabledAtMs = 0L)
        )
    }

    @Test
    fun `respects the parallel switch and wifi-only rule`() {
        val disabledAt = 10_000L
        val readyAt = disabledAt + CdnConnectionPolicy.PARALLEL_RETRY_COOLDOWN_MS
        assertFalse(
            "设置里关掉并行 / 会话已停 → 不许重升",
            shouldRestore(nowMs = readyAt, disabledAtMs = disabledAt, parallelAllowed = false)
        )
        assertFalse(
            "蜂窝网不跑并行（流量口径不变）",
            shouldRestore(nowMs = readyAt, disabledAtMs = disabledAt, wifi = false)
        )
    }

    @Test
    fun `rescue branch exists in the connection policy`() {
        // 重升的目标就是让这条救援分支重新可达：缓冲薄时必须加并发抢时间。
        // 若这里失败，说明 `resolveCdnConnections` 的 <5s 分支被改了 —— 重升就没意义了。
        org.junit.Assert.assertEquals(
            "缓冲 < 5s 必须升到 3 连接（救援分支）",
            3,
            resolveCdnConnections(bufferMs = 4_999L, speedBps = 0L, bitrateBps = 0L, limited = false)
        )
        org.junit.Assert.assertEquals(
            "缓冲 ≥ 30s 回落 1 连接",
            1,
            resolveCdnConnections(bufferMs = 30_000L, speedBps = 0L, bitrateBps = 0L, limited = false)
        )
    }

    @Test
    fun `window slot wait is bounded and short`() {
        assertTrue(
            "等待槽位必须有界且短（在 loader 线程上等，不能影响播放）",
            CdnConnectionPolicy.WINDOW_SLOT_WAIT_MS in 1L..1_000L
        )
    }
}
