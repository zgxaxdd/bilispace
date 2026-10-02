package com.naaammme.bbspace.core.playback

import android.os.SystemClock
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.model.PlayReportParams
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.PlayerSessionState
import com.naaammme.bbspace.infra.crypto.BiliSessionId
import com.naaammme.bbspace.infra.crypto.DeviceIdentity
import com.naaammme.bbspace.infra.player.PlayerPlaybackState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PlaybackReporter @Inject constructor(
    private val authProvider: AuthProvider,
    private val deviceIdentity: DeviceIdentity,
    private val playerSettings: AppSettings,
    private val localHistoryRecorder: LocalPlaybackHistoryRecorder,
    private val remoteReporter: RemotePlaybackReporter
) {
    private val mu = Mutex()
    private var pageOwnerId = 0L
    private var pagePolarisId = ""
    private var ctx: PlaybackReportSession? = null

    // ── PT-04：周期心搏（30s） ──────────────────────────────────────────────

    /**
     * 心搏循环的宿主作用域。@Singleton 与进程同寿命，**刻意不 cancel**：
     * 循环的退出条件由判据承担（暂停 / 后台 / 会话结束），见
     * [PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat] 与 [heartbeatLoopJob] 的取消点。
     */
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 宿主前后台信号（app 层 `PlaybackHostViewModel` 下发；默认前台）。 */
    @Volatile
    private var isHostInBackground = false

    /** tick 时刻的最新播放位置（onPlaybackState 每次刷新，稳态播放约 0.5s 一新）。 */
    @Volatile
    private var lastKnownPositionMs = 0L

    private var heartbeatLoopJob: Job? = null

    /** PT-04：宿主前后台信号入口（app 层生命周期调用；默认值 = 前台）。 */
    fun setHostBackground(inBackground: Boolean) {
        isHostInBackground = inBackground
    }

    /**
     * tick 决策收口在文件级 [playbackHeartbeatTickDecision]（纯函数，JVM 可测）；
     * 循环本体只做"睡周期 → 读状态 → 问决策 → 发包"。
     */
    private fun startHeartbeatLoop(active: PlaybackReportSession) {
        heartbeatLoopJob?.cancel()
        heartbeatLoopJob = heartbeatScope.launch {
            var lastReported: PlaybackHeartbeatPolicy.PlaybackHeartbeatSnapshot? = null
            while (isActive) {
                delay(PlaybackHeartbeatPolicy.PLAYBACK_HEARTBEAT_INTERVAL_MS)
                val current = mu.withLock {
                    ctx?.takeIf { it.sessionId == active.sessionId && !it.heartbeatEnded }
                } ?: return@launch
                val positionMs = lastKnownPositionMs
                val snapshot = playbackHeartbeatTickDecision(
                    isPlaying = current.lastIsPlaying,
                    isInBackground = isHostInBackground,
                    aid = current.report.aid,
                    cid = current.report.cid,
                    positionMs = positionMs,
                    playedTimeMs = current.playedTimeMs,
                    nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
                    heartbeatEnded = current.heartbeatEnded,
                    lastReported = lastReported
                ) ?: continue
                remoteReporter.heartbeatProgress(current, positionMs)
                lastReported = snapshot
            }
        }
    }

    fun bindOwner(who: Long) {
        if (pageOwnerId == who) return
        pageOwnerId = who
        pagePolarisId = BiliSessionId.polarisAction()
    }

    suspend fun startSession(
        request: PlaybackRequest,
        state: PlayerSessionState,
        report: PlayReportParams,
        startPositionMs: Long
    ) {
        val source = state.playbackSource ?: return
        val quality = state.currentStream?.quality ?: DEFAULT_QN
        val startSec = msToSec(startPositionMs)
        val nowElapsed = SystemClock.elapsedRealtime()
        val reportEnabled = playerSettings.state.first().playback.reportPlayback
        mu.withLock {
            val active = ctx
            if (
                active != null &&
                active.request == request &&
                active.source == source &&
                active.report == report
            ) {
                return@withLock
            }
            ctx = PlaybackReportSession(
                uid = authProvider.mid,
                request = request,
                source = source,
                report = report,
                sessionId = BiliSessionId.view(deviceIdentity.buvid),
                polarisActionId = pagePolarisId.ifBlank(BiliSessionId::polarisAction),
                reportEnabled = reportEnabled,
                playbackHistoryStartTs = nowSec(),
                startElapsedMs = nowElapsed,
                lastSampleElapsedMs = nowElapsed,
                quality = quality,
                lastProgressSec = startSec,
                maxProgressSec = startSec
            )
        }
    }

    suspend fun onPlaybackState(
        state: PlayerSessionState,
        playbackState: PlayerPlaybackState,
        progress: PlaybackProgress
    ) {
        lastKnownPositionMs = progress.positionMs
        val current = mu.withLock {
            val active = ctx ?: return@withLock null
            if (active.source != state.playbackSource) return@withLock null
            val prevPlayWhenReady = active.lastPlayWhenReady
            val prevPlaybackState = active.lastPlaybackState
            active.sync(progress.positionMs, state)
            active.lastIsPlaying = playbackState.isPlaying
            active.lastPlayWhenReady = playbackState.playWhenReady
            active.lastPlaybackState = playbackState.playbackState
            val firstFrame = !active.heartbeatStarted && playbackState.firstFrameSeq > 0L
            val startPlaybackHistory = !active.playbackHistoryStarted && playbackState.firstFrameSeq > 0L
            if (firstFrame) {
                active.heartbeatStarted = true
            }
            if (firstFrame || startPlaybackHistory) {
                active.playbackHistoryStarted = true
            }
            SnapshotEdge(
                ctx = active.copy(),
                firstFrame = firstFrame,
                paused = prevPlayWhenReady &&
                        !playbackState.playWhenReady &&
                        playbackState.playbackState == PlaybackState.Ready,
                ended = !active.finalized &&
                        playbackState.playbackState == PlaybackState.Ended &&
                        prevPlaybackState != PlaybackState.Ended,
                startPlaybackHistory = startPlaybackHistory
            )
        }
        current ?: return

        if (current.firstFrame) {
            startHeartbeat(current.ctx)
            startPlaybackHistory(current.ctx, progress.positionMs)
        } else if (current.startPlaybackHistory) {
            startPlaybackHistory(current.ctx, progress.positionMs)
        }

        if (current.paused && current.ctx.playbackHistoryStarted) {
            reportPlaybackHistory(current.ctx, progress.positionMs, completePlayback = false)
        }
        if (current.ended && current.ctx.playbackHistoryStarted) {
            reportPlaybackHistory(current.ctx, progress.positionMs, completePlayback = true)
            endHeartbeat(current.ctx)
            mu.withLock {
                ctx = ctx?.takeIf { it.sessionId != current.ctx.sessionId } ?: ctx?.copy(finalized = true)
            }
        }
    }

    suspend fun finishSession(
        playbackState: PlayerPlaybackState,
        progress: PlaybackProgress
    ) {
        val active = detachSession(
            playbackState = playbackState.playbackState,
            positionMs = progress.positionMs
        ) ?: return
        finalizeSession(active)
    }

    internal suspend fun detachSession(
        playbackState: PlaybackState,
        positionMs: Long
    ): DetachedPlaybackReport? {
        return mu.withLock {
            val current = ctx ?: return@withLock null
            current.sync(positionMs, null)
            current.lastPlaybackState = playbackState
            val detached = DetachedPlaybackReport(
                session = current.copy(),
                playbackState = playbackState,
                positionMs = positionMs
            )
            if (ctx?.sessionId == current.sessionId) {
                ctx = null
            }
            detached
        }
    }

    internal suspend fun finalizeSession(report: DetachedPlaybackReport) {
        val active = report.session
        if (!active.playbackHistoryStarted) return
        if (!active.finalized) {
            val completePlayback = active.isComplete(
                positionMs = report.positionMs,
                playbackState = report.playbackState,
                allowEndedOnly = false
            )
            recordAndReportPlaybackHistory(
                active = active,
                positionMs = report.positionMs,
                completePlayback = completePlayback
            )
            if (active.heartbeatStarted && !active.heartbeatEnded) {
                remoteReporter.endHeartbeat(active)
                // PT-04：旁路收口同样取消心搏循环（detached 会话不再有周期包）
                heartbeatLoopJob?.cancel()
                heartbeatLoopJob = null
            }
        }
    }

    private suspend fun startHeartbeat(active: PlaybackReportSession) {
        val ts = remoteReporter.startHeartbeat(active)
        mu.withLock {
            val current = ctx ?: return@withLock
            if (current.sessionId != active.sessionId) return@withLock
            ctx = current.copy(heartbeatStartTs = ts)
        }
        // PT-04：首包发出后启动 30s 周期循环（同会话幂等：换会话先取消旧循环）
        startHeartbeatLoop(active)
    }

    private suspend fun endHeartbeat(active: PlaybackReportSession) {
        if (active.heartbeatEnded) return
        // PT-04：会话结束 → 心搏循环必须取消（验收④；结构断言见
        // PlaybackHeartbeatPolicyTest.endHeartbeatCancelsTheLoop）
        heartbeatLoopJob?.cancel()
        heartbeatLoopJob = null
        remoteReporter.endHeartbeat(active)
        mu.withLock {
            val current = ctx ?: return@withLock
            if (current.sessionId != active.sessionId) return@withLock
            ctx = current.copy(heartbeatEnded = true, finalized = true)
        }
    }

    private suspend fun startPlaybackHistory(
        active: PlaybackReportSession,
        positionMs: Long
    ) {
        reportPlaybackHistory(active, positionMs, completePlayback = false)
    }

    private suspend fun reportPlaybackHistory(
        active: PlaybackReportSession,
        positionMs: Long,
        completePlayback: Boolean
    ) {
        recordAndReportPlaybackHistory(
            active = active,
            positionMs = positionMs,
            completePlayback = completePlayback
        )
    }

    private suspend fun recordAndReportPlaybackHistory(
        active: PlaybackReportSession,
        positionMs: Long,
        completePlayback: Boolean
    ) {
        localHistoryRecorder.record(active, positionMs)
        remoteReporter.reportPlaybackHistory(active, positionMs, completePlayback)
    }

    private data class SnapshotEdge(
        val ctx: PlaybackReportSession,
        val firstFrame: Boolean,
        val paused: Boolean,
        val ended: Boolean,
        val startPlaybackHistory: Boolean
    )

    private companion object {
        const val DEFAULT_QN = 64
    }
}

internal data class DetachedPlaybackReport(
    val session: PlaybackReportSession,
    val playbackState: PlaybackState,
    val positionMs: Long
)

/**
 * PT-04：一拍心搏的**决策内核**（纯函数，文件级以便 JVM 单测直调真代码——
 * `PlaybackReporter` 构造期有 Android 依赖，实例化不可测）。
 * 返回非 null = 应该发的快照；null = 本拍跳过（暂停 / 后台 / 身份缺失 / 无进展 / 已结束）。
 */
internal fun playbackHeartbeatTickDecision(
    isPlaying: Boolean,
    isInBackground: Boolean,
    aid: Long,
    cid: Long,
    positionMs: Long,
    playedTimeMs: Long,
    nowElapsedMs: Long,
    heartbeatEnded: Boolean,
    lastReported: PlaybackHeartbeatPolicy.PlaybackHeartbeatSnapshot?
): PlaybackHeartbeatPolicy.PlaybackHeartbeatSnapshot? {
    if (heartbeatEnded) return null
    if (!PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat(isPlaying, isInBackground, aid, cid)) {
        return null
    }
    val snapshot = PlaybackHeartbeatPolicy.resolvePlaybackHeartbeatSnapshot(
        currentPositionMs = positionMs,
        accumulatedPlayMs = playedTimeMs,
        activePlayStartElapsedMs = null,
        nowElapsedMs = nowElapsedMs
    )
    if (!PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, cid, snapshot, lastReported)) {
        return null
    }
    return snapshot
}
