package com.naaammme.bbspace.core.playback

import android.os.SystemClock
import com.naaammme.bbspace.core.model.PlayReportParams
import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.PlayerSessionState
import kotlin.math.max

internal data class PlaybackReportSession(
    val uid: Long,
    val request: PlaybackRequest,
    val source: PlaybackSource,
    val report: PlayReportParams,
    val sessionId: String,
    val polarisActionId: String,
    val reportEnabled: Boolean,
    val playbackHistoryStartTs: Long,
    val startElapsedMs: Long,
    var lastSampleElapsedMs: Long,
    var quality: Int,
    var lastProgressSec: Int,
    var maxProgressSec: Int,
    var totalTimeMs: Long = 0L,
    var playedTimeMs: Long = 0L,
    var actualPlayedTimeMs: Long = 0L,
    var pausedTimeMs: Long = 0L,
    var heartbeatStartTs: Long = 0L,
    var heartbeatStarted: Boolean = false,
    var heartbeatEnded: Boolean = false,
    var playbackHistoryStarted: Boolean = false,
    var finalized: Boolean = false,
    var lastIsPlaying: Boolean = false,
    var lastPlayWhenReady: Boolean = false,
    var lastPlaybackState: PlaybackState = PlaybackState.Idle,
    /**
     * P2-2：本会话**上一次真正发出去**的历史位置（远端与本地共用同一条去重口径）。
     * 用于挡住"暂停沿重复上报"（位置没动就不发）。
     */
    var lastReportedHistoryPositionMs: Long = 0L,
    /** P1-2：上一次会话中途远端写回的时刻（`elapsedRealtime`）与当时的进度。 */
    var lastRemoteSyncElapsedMs: Long = 0L,
    var lastRemoteSyncedPositionMs: Long = 0L
)

internal fun PlaybackReportSession.sync(
    positionMs: Long,
    state: PlayerSessionState?
) {
    val nowElapsed = SystemClock.elapsedRealtime()
    val deltaMs = (nowElapsed - lastSampleElapsedMs).coerceAtLeast(0L)
    if (deltaMs > 0L) {
        when {
            lastIsPlaying -> {
                playedTimeMs += deltaMs
                actualPlayedTimeMs += deltaMs
            }

            heartbeatStarted && lastPlaybackState == PlaybackState.Ready -> {
                pausedTimeMs += deltaMs
            }
        }
    }
    totalTimeMs = (nowElapsed - startElapsedMs).coerceAtLeast(0L)
    lastProgressSec = msToSec(positionMs)
    maxProgressSec = max(maxProgressSec, lastProgressSec)
    lastSampleElapsedMs = nowElapsed
    state?.currentStream?.quality?.takeIf { it > 0 }?.let { quality = it }
}

internal fun PlaybackReportSession.durationSec(): Int {
    return msToSec(source.durationMs).coerceAtLeast(0)
}

internal fun progressSec(
    positionMs: Long,
    completePlayback: Boolean
): Int {
    if (completePlayback) return -1
    return msToSec(positionMs).coerceAtLeast(0)
}

internal fun PlaybackReportSession.isComplete(
    positionMs: Long,
    playbackState: PlaybackState,
    allowEndedOnly: Boolean
): Boolean {
    if (source.isPreview) return false
    if (playbackState == PlaybackState.Ended) return true
    if (allowEndedOnly) return false
    // PT-03：阈值收敛到 PlaybackResumePolicy.COMPLETE_TAIL_MS（原本文件的同名
    // private 常量已删除）。上报侧语义刻意保持"只看 3s 尾"不看 95%——
    // 改上报语义会改变发往 B 站的 payload，属网络行为变化，不在移植范围内。
    return PlaybackResumePolicy.isWithinTail(positionMs, source.durationMs)
}

internal fun msToSec(value: Long): Int {
    return (value.coerceAtLeast(0L) / 1000L).toInt()
}

internal fun nowSec(): Long {
    return System.currentTimeMillis() / 1000L
}
