package com.naaammme.bbspace.core.playback

/**
 * 播放心搏判据（PT-04，语义移植自上游 BiliPai
 * `app/.../feature/video/playback/policy/PlaybackAncillaryTaskPolicy.kt:24-100`）。
 *
 * ## 与上游的差异（PR 必读）
 * - 上游用 `bvid(String)+cid(Long)` 判身份；bbspace 用 `aid/cid`（Long）。
 * - 上游的循环在 `VideoPlaybackViewModel`（`delay(30_000)`）；bbspace 的循环在
 *   `PlaybackReporter`（会话级单例），**判据全部收口在本文件**，循环本体只做
 *   "睡周期 → 读状态 → 问判据 → 发包"，不做任何决策。
 * - 上游 `isInBackground` 来自生命周期观察；bbspace 由宿主（`PlaybackHostViewModel`）
 *   经 `PlaybackReporter.setHostBackground` 下发——**默认模型**下进后台即暂停
 *   （`isPlaying=false` 双重兜底）；**后台播放开启**时宿主不下发该信号
 *   （用户显式选择后台续播，此时心搏继续——与上游"后台一律不发"的**有意差异**）。
 */
object PlaybackHeartbeatPolicy {

    /** 心搏周期：上游 `VideoPlaybackViewModel.kt:8940` 的固定 30s（单一真值源）。 */
    const val PLAYBACK_HEARTBEAT_INTERVAL_MS = 30_000L

    /**
     * 周期心搏是否应该发：在播 && 非后台 && 身份完备。
     * （上游 `shouldSendPlaybackHeartbeat:24-32` 同款；后台硬拦已复核为上游语义。）
     */
    fun shouldSendPlaybackHeartbeat(
        isPlaying: Boolean,
        isInBackground: Boolean,
        aid: Long,
        cid: Long
    ): Boolean {
        return isPlaying && !isInBackground && aid > 0L && cid > 0L
    }

    /** 首包判定（上游 `shouldSendInitialPlaybackHeartbeat:36` 同款形态）。 */
    fun shouldSendInitialPlaybackHeartbeat(
        isActivelyPlaying: Boolean,
        isInBackground: Boolean,
        aid: Long,
        cid: Long
    ): Boolean {
        return isActivelyPlaying && !isInBackground && aid > 0L && cid > 0L
    }

    /** 会话开始时间戳：沿用已有值，没有才取当前（上游 `:48` 同款）。 */
    fun resolvePlaybackHeartbeatSessionStartTsSec(
        existingStartTsSec: Long,
        nowEpochSec: Long
    ): Long {
        return if (existingStartTsSec > 0L) existingStartTsSec else nowEpochSec.coerceAtLeast(0L)
    }

    /** 心搏快照：播放位置（秒）+ 实际播放时长（秒，含活动段折算）。 */
    data class PlaybackHeartbeatSnapshot(
        val playedTimeSec: Long,
        val realPlayedTimeSec: Long
    )

    /** 快照计算（上游 `:59` 同款：活动段 = now − 活动段起点，负值钳 0）。 */
    fun resolvePlaybackHeartbeatSnapshot(
        currentPositionMs: Long,
        accumulatedPlayMs: Long,
        activePlayStartElapsedMs: Long?,
        nowElapsedMs: Long
    ): PlaybackHeartbeatSnapshot {
        val safeAccumulatedPlayMs = accumulatedPlayMs.coerceAtLeast(0L)
        val activePlayMs = if (activePlayStartElapsedMs != null && activePlayStartElapsedMs > 0L) {
            (nowElapsedMs - activePlayStartElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return PlaybackHeartbeatSnapshot(
            playedTimeSec = currentPositionMs.coerceAtLeast(0L) / 1000L,
            realPlayedTimeSec = (safeAccumulatedPlayMs + activePlayMs) / 1000L
        )
    }

    /**
     * 快照是否值得发：身份完备 && playedTime > 0 && 相对上次上报有进展
     * （上游 `:78` 同款——位置与真实时长任一前进即可）。
     */
    fun shouldFlushPlaybackHeartbeatSnapshot(
        aid: Long,
        cid: Long,
        snapshot: PlaybackHeartbeatSnapshot,
        lastReportedSnapshot: PlaybackHeartbeatSnapshot?
    ): Boolean {
        if (aid <= 0L || cid <= 0L || snapshot.playedTimeSec <= 0L) {
            return false
        }
        return lastReportedSnapshot == null ||
            snapshot.playedTimeSec > lastReportedSnapshot.playedTimeSec ||
            snapshot.realPlayedTimeSec > lastReportedSnapshot.realPlayedTimeSec
    }
}
