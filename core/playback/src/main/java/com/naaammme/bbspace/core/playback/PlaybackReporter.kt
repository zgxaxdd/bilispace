package com.naaammme.bbspace.core.playback

import android.os.SystemClock
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.Logger
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

    /** 上一次写本地历史检查点的时间（`elapsedRealtime`，见 [checkpointLocalHistory]）。 */
    private var lastHistoryCheckpointAt = 0L

    private var heartbeatLoopJob: Job? = null

    /**
     * 一次性后台任务的作用域（退出时落历史）。与 `heartbeatScope` 分开只为**可读性**：
     * 一个是循环、一个是一次性写入，混在一起以后不好判断"谁在跑"。
     */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * PT-04：宿主前后台信号入口（app 层生命周期调用；默认值 = 前台）。
     *
     * **L4-101 修复（2026-10-03 真机实测发现）**：进后台时顺手把**当前进度落本地历史**。
     *
     * 为什么必须有这一条：本地历史只在 ① 首帧 ② 暂停沿 ③ 播完沿 ④ 会话结束时写；
     * 而本机默认配置下**后台播放是开着的**（`PlaybackService` 前台保活），于是
     * "按返回键 / 回桌面"**既不暂停也不结束会话** → 进度一直停在**首帧写的 0:00**，
     * 直到用户主动暂停才补上。
     *
     * 实测证据（OPPO PLC110 / debug）：详情页播放 30s 后返回，历史里该视频仍是
     * `进度 00:00 / 17:44`；同一行在显式暂停后**立刻**变成 `600.1s / 1063.4s`
     * （与 logcat `AudioTrack: pause(...) prior state:STATE_ACTIVE` 同秒）。
     * 影响：用户"看一半就退出/切后台"，重启后续播丢失（回到 0:00）。
     *
     * 只写**本地**历史，不触发远端 `reportPlaybackHistory`：远端上报仍在暂停/播完沿做，
     * 避免每次切后台都多一次网络请求。
     */
    fun setHostBackground(inBackground: Boolean) {
        isHostInBackground = inBackground
        // 同一个信号接到历史包的 `scene` 字段（此前恒 "front"，后台/画中画被记成前台）。
        // 只转发**信号**，不改这里的落本地历史行为——不变式 2 的守卫（只写本地、
        // 不许在进后台时发远端上报）仍然成立：这里没有发起任何远端请求。
        remoteReporter.setHostInBackground(inBackground)
        if (inBackground) {
            flushLocalHistoryOnBackground()
        }
    }

    /**
     * 进度脉搏（L4-101 修复的**驱动源**）：由会话在 `playerEngine.playbackProgress` 的
     * 收集器里调用（稳态播放约 0.5s 一次）。
     *
     * 为什么不能用 [onPlaybackState] 驱动检查点：那个函数由 `playbackState` 这个
     * **StateFlow** 驱动，只在状态**变化**时发（起播/缓冲/暂停/播完），稳态播放期间一声不响
     * —— 真机实测把检查点放在那里时，播 25s 后返回，历史仍是 `0.0s`。
     *
     * 顺带把 [lastKnownPositionMs] 刷新得更及时（心搏上报的位置也吃这个值）。
     */
    fun onPlaybackProgress(positionMs: Long) {
        lastKnownPositionMs = positionMs
        checkpointLocalHistory(positionMs)
        // P1-2：会话中途的低频远端写回。**独立于** checkpointLocalHistory ——
        // 后者被守卫钉死为"只写本地"（PlaybackHistoryFlushGuardTest 不变式 4），
        // 这里是在它之外另开的一条**有节流**的通道。
        // 挂到 flushScope（IO）而不是就地做：判据要读/写会话快照，那是挂起操作；
        // 而 onPlaybackProgress 本身**不能**改成 suspend —— 既有守卫按
        // `fun onPlaybackProgress(` 这个签名前缀做括号扫描，改签名会让它红。
        flushScope.launch { syncRemoteProgressIfDue(positionMs) }
    }

    /**
     * P1-2：会话中途把进度补写回远端（低频 + 有实质增量才发）。
     *
     * 为什么需要：本地 5s 检查点只落本地，`finalizeSession` 只在正常退出/切集时跑。
     * 长视频中途被系统杀掉 / 断电 / 崩溃 → 远端永远停在起播点（用户重开后续播回 0:00）。
     *
     * 与既有守卫的关系（重要）：`PlaybackHistoryFlushGuardTest` 有两条不变式要求
     * 「检查点只写本地」「进后台不许发远端」。那条决策是**有意**做的（避免稳态每分钟
     * 多次网络请求），本方法不推翻它：
     * - 它**不在** `checkpointLocalHistory` / `flushLocalHistoryOnBackground` 里，
     *   所以两条守卫的括号扫描都不会命中它；
     * - 它自带 5 分钟间隔 + 60s 增量门控，请求量远低于"每分钟多次"；
     * - 后台一律不发（与周期心跳同一口径），后台的进度由既有的只写本地通道负责。
     *
     * ⚠️ **服务端限流未抓包确认**（报告 §3.7.2 的真机前置）。5 分钟是最保守的取值：
     * 1 小时视频最多多发 12 次；若日后抓包证明服务端不接受这个频率，改
     * [PlaybackHistoryReportPolicy.REMOTE_SYNC_INTERVAL_MS] 一处即可。
     */
    private suspend fun syncRemoteProgressIfDue(positionMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val snapshot = mu.withLock {
            val active = ctx ?: return@withLock null
            if (!active.playbackHistoryStarted || active.finalized) return@withLock null
            if (!PlaybackHistoryReportPolicy.shouldSyncRemoteProgress(
                    nowElapsedMs = now,
                    lastSyncElapsedMs = active.lastRemoteSyncElapsedMs,
                    positionMs = positionMs,
                    lastSyncedPositionMs = active.lastRemoteSyncedPositionMs,
                    inBackground = isHostInBackground
                )
            ) {
                return@withLock null
            }
            active.lastRemoteSyncElapsedMs = now
            active.lastRemoteSyncedPositionMs = positionMs
            active.lastReportedHistoryPositionMs = positionMs
            active.copy()
        } ?: return
        flushScope.launch {
            runCatchingCancellable {
                remoteReporter.reportPlaybackHistory(snapshot, positionMs, completePlayback = false)
            }.onFailure { Logger.w(TAG) { "中途远端写回失败 pos=$positionMs msg=${it.message}" } }
        }
    }

    private fun flushLocalHistoryOnBackground() {
        flushScope.launch {
            // 快照会话：进后台**瞬间**的位置才是"用户离开时看到的位置"
            val active = mu.withLock { ctx?.takeIf { it.playbackHistoryStarted }?.copy() } ?: return@launch
            val positionMs = lastKnownPositionMs
            runCatchingCancellable { localHistoryRecorder.record(active, positionMs) }
                .onFailure { Logger.w(TAG) { "退出时写本地历史失败 pos=$positionMs msg=${it.message}" } }
        }
    }

    /**
     * 周期检查点（L4-101）：播放中每 [HISTORY_CHECKPOINT_INTERVAL_MS] 把进度写一次**本地**历史。
     *
     * 只写本地（不发远端上报）、只在历史已开始且会话未终结时写、按时间节流
     * （[onPlaybackProgress] 约 0.5s 一次，不节流会变成每 0.5s 一次 Room 写）。
     * 落库在 [flushScope]（IO）上做，不占用状态管线的线程。
     */
    private fun checkpointLocalHistory(positionMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHistoryCheckpointAt < HISTORY_CHECKPOINT_INTERVAL_MS) return
        lastHistoryCheckpointAt = now
        flushScope.launch {
            val active = mu.withLock {
                ctx?.takeIf { it.playbackHistoryStarted && !it.finalized }?.copy()
            } ?: return@launch
            runCatchingCancellable { localHistoryRecorder.record(active, positionMs) }
                .onFailure { Logger.w(TAG) { "周期写本地历史失败 pos=$positionMs msg=${it.message}" } }
        }
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

        // L4-101：状态变化时也补一次检查点（起播/暂停/播完这些**变化**点本来就该落进度）。
        // 稳态播放靠 StreamPlaybackSessionImpl 的进度脉搏调 [onPlaybackProgress]。
        checkpointLocalHistory(progress.positionMs)

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
            // force=true：播完沿必须发（completePlayback=true 已经让它必发，这里显式表达意图）。
            reportPlaybackHistory(
                current.ctx,
                progress.positionMs,
                completePlayback = true,
                force = true
            )
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
        // force=true：起播沿必须发（它的作用是"证明看过"，与"有没有新进展"无关）。
        reportPlaybackHistory(active, positionMs, completePlayback = false, force = true)
    }

    private suspend fun reportPlaybackHistory(
        active: PlaybackReportSession,
        positionMs: Long,
        completePlayback: Boolean,
        force: Boolean = false
    ) {
        recordAndReportPlaybackHistory(
            active = active,
            positionMs = positionMs,
            completePlayback = completePlayback,
            force = force
        )
    }

    /**
     * @param force 起播沿 / 播完沿必须发；其余沿走 [PlaybackHistoryReportPolicy]
     *   的"有进展才发"门控（P2-2）。
     *
     * **本地记录不受门控影响**：`localHistoryRecorder.record` 每次都写。
     * 被挡掉的只是"位置没动过的重复远端请求"——本地历史是续播的真值源，
     * 拿它去省网络请求是错的。
     */
    private suspend fun recordAndReportPlaybackHistory(
        active: PlaybackReportSession,
        positionMs: Long,
        completePlayback: Boolean,
        force: Boolean = false
    ) {
        localHistoryRecorder.record(active, positionMs)
        val shouldSendRemote = force || completePlayback ||
            PlaybackHistoryReportPolicy.shouldReportHistoryProgress(
                positionMs = positionMs,
                lastReportedPositionMs = active.lastReportedHistoryPositionMs
            )
        if (!shouldSendRemote) {
            Logger.d(TAG) {
                "history report skipped (no progress) pos=$positionMs last=${active.lastReportedHistoryPositionMs}"
            }
            return
        }
        remoteReporter.reportPlaybackHistory(active, positionMs, completePlayback)
        mu.withLock {
            val current = ctx ?: return@withLock
            if (current.sessionId == active.sessionId) {
                current.lastReportedHistoryPositionMs = positionMs
            }
        }
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
        const val TAG = "PlaybackReporter"

        /**
         * 本地历史检查点间隔（L4-101）。5s 的取舍：
         * - 上界：即使用户"看一半直接退出/被杀"，最多丢 5s 进度（此前是"丢到 0:00"）；
         * - 下界：稳态播放约 12 次 Room upsert/分钟（单行、几十字节），相对已有的
         *   30s 心搏与暂停/播完上报可忽略。
         */
        const val HISTORY_CHECKPOINT_INTERVAL_MS = 5_000L
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
