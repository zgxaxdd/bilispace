package com.naaammme.bbspace.infra.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.network.NetworkTypeProvider
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.infra.player.cdn.CDN_PREFETCH_FRONTIER_STEP_MS
import com.naaammme.bbspace.infra.player.cdn.CdnDashPrefetchRequest
import com.naaammme.bbspace.infra.player.cdn.CdnDashSegmentPrefetcher
import com.naaammme.bbspace.infra.player.cdn.CdnTransferRuntime
import com.naaammme.bbspace.infra.player.cdn.resolveCdnPrefetchSegmentCount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Singleton
class Media3PlayerEngine @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    /** PB-03：播放字节缓存（两条数据源链都在最外层挂它）。 */
    private val playbackMediaCache: PlaybackMediaCache,
      /** 闭环修复（2026-10-06）：换线/索引读取的**真实结果**回传给健康度。 */
      lineOutcomeListener: CdnLineOutcomeListener,
    /**
     * 预取的网络判据（对齐 BiliPai `scheduleCdnDashPrefetch` 里的 `NetworkUtils.isWifi`）：
     * 蜂窝网下**不做**后台预取 —— 跳一次就预取一批分段是"流量偷跑"反馈的主要来源。
     */
    private val networkTypeProvider: NetworkTypeProvider
) : PlayerEngine {

    private val appContext = context.applicationContext
    private val videoOkHttpClient = okHttpClient
    private val singleFileDashMediaSourceFactory = SingleFileDashMediaSourceFactory(
        appContext = appContext,
        okHttpClient = okHttpClient,
        playbackMediaCache = playbackMediaCache,
        lineOutcomeListener = lineOutcomeListener,
        // 会话层记账的目标 host：换线后立刻切到真正在供数的那条
        servingHostListener = CdnServingHostListener { host -> servingHost = host }
    )

    /** [currentServingHost] 的存储；由数据源链在真正 open 成功时写入。 */
    @Volatile
    private var servingHost: String? = null

    // ---- A2：网络恢复后自动补一次重建（见 NetworkReturnRetryPolicy）----
    /** 断网时被跳过的那次重建，等着网络回来兑现。 */
    @Volatile
    private var pendingOfflineRetry = false
    /** 一次会话只补一次（绝不允许退化成新的重试风暴）。 */
    @Volatile
    private var offlineRetryUsed = false

    private val connectivityManager: android.net.ConnectivityManager? =
        appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager

    /**
     * 网络恢复回调：只做"把断网时落下的那次重建补上"，且**先去抖再判五条**。
     *
     * 五条判据见 [NetworkReturnRetryPolicy]（待办 / 未用过 / 网络真的可用 / 用户仍想播 / 当前没在播）。
     * 补的时候走上层同一条恢复路径 [recoverSession]，所以重建节流与重试预算都照旧生效。
     */
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runtimeScope.launch {
                kotlinx.coroutines.delay(NetworkReturnRetryPolicy.DEBOUNCE_MS)
                val player = exoPlayer ?: return@launch
                val shouldRetry = NetworkReturnRetryPolicy.shouldRetryOnNetworkReturn(
                    pendingOfflineRetry = pendingOfflineRetry,
                    retryUsed = offlineRetryUsed,
                    networkAvailable = networkTypeProvider.isNetworkAvailable(),
                    playWhenReady = player.playWhenReady,
                    isPlaying = player.isPlaying
                )
                if (!shouldRetry) return@launch
                offlineRetryUsed = true
                pendingOfflineRetry = false
                DiagnosticLog.i(TAG) { "network returned: retry the rebuild skipped while offline" }
                recoverSession("network_returned", null)
            }
        }
    }

    init {
        // 只关心"网络回来了"这一件事；系统偶发不支持时静默降级（不影响其它功能）。
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    /**
     * 重挂"网络恢复补重建"回调（幂等）。
     *
     * ## 为什么必须有这个补挂点（2026-10-07 审查发现的缺陷）
     * [release] 会把回调摘掉（见那边的 `unregisterNetworkCallback`），而本引擎是
     * **单例**、注册只发生在上面那个 `init` 里 —— 于是**第一次 `release()` 之后**，
     * 「断网时跳过的重建，等网络回来补一次」这条恢复路径在本进程内**永久失效**
     * （返回关页、划掉通知、切直播都会释放）。而 `release()` 之后再起播必然要
     * [buildPlayer] ⇒ 在三条建实例路径的唯一出口补挂即可。
     *
     * 先摘后挂是幂等的：未注册时 `unregisterNetworkCallback` 抛
     * `IllegalArgumentException`，被 `runCatching` 吞掉（与既有的静默降级一致）。
     */
    private fun ensureNetworkCallback() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
    }

    /**
     * DASH 分段预取器（源 `CdnDashSegmentPrefetcher`，逐字移植）。
     *
     * 移植时它是**孤儿**（全仓无构造点 ⇒ 严格 Range 读 `CdnRangeRequest` 也一起冻结）；
     * 这里把它接上：READY 且缓冲足够厚时调度一次，见 [scheduleCdnDashPrefetch]。
     */
    private val cdnSegmentPrefetcher = CdnDashSegmentPrefetcher(
        context = appContext,
        client = okHttpClient,
        playbackMediaCache = playbackMediaCache
    )
    private var cdnPrefetchJob: Job? = null

    /** 上一次真正发起预取时的"已缓冲前沿"（毫秒）；换源/跳转时重置。 */
    private var cdnPrefetchFrontierMs = -1L

    /** 当前会话的预取计划（建源时由 [SingleFileDashMediaSourceFactory] 交出；非 DASH 源为 null）。 */
    @Volatile
    private var cdnDashPrefetchPlan: CdnDashPrefetchPlan? = null

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player.asStateFlow()
    private val _currentSource = MutableStateFlow<EngineSource?>(null)
    override val currentSource: StateFlow<EngineSource?> = _currentSource.asStateFlow()
    private val _playbackState = MutableStateFlow(PlayerPlaybackState())
    override val playbackState: StateFlow<PlayerPlaybackState> = _playbackState.asStateFlow()
    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    override val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playerConfig = PlayerConfig()
    // 循环播放是播放器级属性，buildPlayer() 不会带上它：必须与 playerConfig 一样
    // 作为期望状态保存在引擎里，并在每个新建实例上重新施加（懒创建/配置重建/错误恢复）。
    private var desiredRepeatOne = false
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private var firstFrameSeq = 0L
    private var lastEventsPlaybackState = Player.STATE_IDLE
    private var lastEventsIsPlaying = false
    private var progressJob: Job? = null

    // 会话代数：每次系统级错误重建 +1。监听器绑定创建时的代数，
    // 旧实例的回调（如 release 竞态产生的错误）按代数甄别后丢弃。
    private var rebuildGen = 0L
    private val rebuildThrottle = RebuildThrottle()

    /**
     * 网络/解码类错误的**重试计数**（BiliPai `maxRetries = 3` 的同值）。
     *
     * 只有"重建后**稳定播放**满 [RECOVERY_SETTLE_MS]"才清零（见 [updatePlaybackProgress] 与
     * [isRecoverySettled]）：这样"偶发一次网络抖动"不会累积成"重试预算用尽"。
     * **不能用"出帧"当成功判据** —— 断网时缓存里的一帧会让预算每次都被清零，
     * 表现为每 ~3.3 秒重建一次（2026-10-06 真机实测 55 秒 28 次）。
     * 真正的次数上限还有 `rebuildThrottle`（60 秒窗口内最多 3 次重建）兜底，两道闸门互不替代。
     */
    private var networkRetryCount = 0

    /**
     * 最近一次"重建后首个视频帧"的单调时刻（-1 = 当前会话还没出帧）。
     *
     * **"出帧"不等于"恢复成功"**：真机实测（2026-10-06，断网）显示，断网后
     * `PlaybackMediaCache` 里的缓存仍能供出 1 帧，若在那一刻就清零预算，引擎会
     * 每 ~3.3 秒重建一次 ExoPlayer（55 秒 28 次），两道闸门形同虚设。
     * 现在只有"出帧后又稳定播放 [RECOVERY_SETTLE_MS]"才清零（判定见
     * [isRecoverySettled]，落点在 [updatePlaybackProgress]）。
     */
    private var recoveryFirstFrameAtMs = -1L

    /** 上次给 `CdnTransferRuntime` 续期路由的时间（单调时钟，见 [refreshCdnRoutesIfStale]）。 */
    private var lastCdnRouteRefreshAtMs = 0L

    /** [sampleNetworkActivity] 的采样状态：CDN 字节计数 + 最近一次"字节在涨"的时刻。 */
    private var lastNetworkBytes = 0L
    private var lastNetworkActivityAtMs = 0L

    // ---- 起播超时（FirstFrameTimeout 事件的信号源，见 FirstFrameTimeoutPolicy）----
    /** 本次开播时刻（单调钟）；<=0 表示还没有开播。 */
    private var playbackStartAtMs = -1L
    /** 本次开播是否已上报过超时（一次开播只报一次）。 */
    private var firstFrameTimeoutReported = false
    /** 超时计数器（只增不减）：会话层按增量记 `CdnHealthPolicy.Event.FirstFrameTimeout`。 */
    private var firstFrameTimeoutSeq = 0L

    private fun newPlayerListener(boundGen: Long): Player.Listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason.isSeekDiscontinuity()) {
                updatePlaybackState { it.copy(seekEventSeq = it.seekEventSeq + 1L) }
                // 跳转后旧 frontier 的预取已无意义：先取消（源仓库在 BUFFERING 时也取消预取任务），
                // 并重置前沿记录 —— 向前跳后要按新前沿重新预取，向后跳则不能被旧值挡住。
                cancelCdnDashPrefetch()
                cdnPrefetchFrontierMs = -1L
            }
            updatePlaybackProgress()
            // 跳转落在已缓冲区间时不会触发 STATE_BUFFERING，READY 也不变 —— 这里补一次调度，
            // 否则"拖动后预取再也不会启动"。
            if (reason.isSeekDiscontinuity() && exoPlayer?.playbackState == Player.STATE_READY) {
                maybeScheduleCdnDashPrefetch()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (boundGen != rebuildGen) {
                // 已被更新的会话接管，本回调来自被替换的旧实例，忽略
                return
            }
            Logger.e(TAG, error) {
                "player error code=${error.errorCodeName} msg=${error.message} " +
                        "videoDec=$videoDecoderName audioDec=$audioDecoderName"
            }
            // 恢复决策**移植自 BiliPai**（`PlayerErrorRecoveryPolicy.decidePlayerErrorRecovery`）：
            // 修复前这里只判"是不是系统级可恢复错误"，IO/网络类错误**直接报错不重试**；
            // 现在按源的分类走：
            //  · 网络类（连接失败/超时/HTTP 状态/文件不存在）→ 在重试预算内重建会话重试；
            //  · 解码类（错误码命中，或 message/cause 里含 decoder/mediacodec/renderer）→ 重建；
            //  · 其余确定性错误 → 只报错。
            // `SWITCH_CDN` 这条路**不在这里**：本仓的线路切换在数据层
            // （`SingleFileDashMediaSourceFactory.buildCdnCandidates` + CdnFailoverDataSource
            // 按候选表依次尝试），引擎层再切一次会与它打架。故 hasCdnAlternatives 恒 false，
            // 决策自然落到 RETRY_NETWORK / GIVE_UP。
            // 又出错了 ⇒ 作废"稳定期"计时：上一次出帧不算恢复成功，预算必须重新挣。
            recoveryFirstFrameAtMs = -1L
            val action = decidePlayerErrorRecovery(
                errorCode = error.errorCode,
                hasCdnAlternatives = false,
                retryCount = networkRetryCount,
                maxRetries = MAX_NETWORK_RETRIES,
                cdnSwitchCount = 0,
                maxCdnSwitches = 0,
                isDecoderLikeFailure = isDecoderLikeFailure(
                    error.message,
                    error.cause?.javaClass?.name
                )
            )
            when {
                action == PlayerErrorRecoveryAction.GIVE_UP ->
                    updatePlaybackState { it.copy(errorMessage = error.message) }

                PlayerErrorPolicy.isRecoverable(error.errorCode) -> {
                    // 系统级资源故障（解码器被回收/AudioTrack 失败…）：重建即可，不占网络重试预算
                    recoverSession(error.errorCodeName, error.message)
                }

                isNetworkPlaybackError(error.errorCode) &&
                    !networkTypeProvider.isNetworkAvailable() -> {
                    // **断网时不为网络错误重建**：没有网络可读，重建只会让"缓存里的一帧"
                    // 反复冒充恢复成功（真机实测 55s / 28 次重建的活锁）。直接落错误态，
                    // 交给 UI 的自动/手动重试与网络恢复后的正常重开路径。
                    Logger.w(TAG) {
                        "network error while offline: skip rebuild code=${error.errorCodeName} " +
                            "retryCount=$networkRetryCount"
                    }
                    // ……但**网络恢复时要自动补一次**（2026-10-08 产品决策 A2）：
                    // 上面那次"该重建却没重建"记成待办，等 ConnectivityManager 回调来兑现。
                    pendingOfflineRetry = true
                    updatePlaybackState { it.copy(errorMessage = error.message) }
                }

                else -> {
                    // 网络类或解码类：走**有预算**的重试（预算只在"稳定播放满 RECOVERY_SETTLE_MS"后清零）
                    if (rebuildThrottle.tryAcquire(android.os.SystemClock.elapsedRealtime())) {
                        networkRetryCount++
                        recoverSession(error.errorCodeName, error.message)
                    } else {
                        // 防抖窗口内已重建太多次：确定性故障，不再重建
                        updatePlaybackState { it.copy(errorMessage = error.message) }
                    }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            if (boundGen != rebuildGen) return
            firstFrameSeq += 1L
            // **出帧只是"恢复候选"**，不是"恢复成功"：缓存里的一帧同样会触发本回调。
            // 真机实测（2026-10-06 断网）：在这里直接 `rebuildThrottle.reset()` 会让
            // "每 3 秒重建一次"的活锁永远刹不住。改为记下出帧时刻，
            // 由 [updatePlaybackProgress] 在"确实稳定播放了 RECOVERY_SETTLE_MS"后清零预算。
            recoveryFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()
            updatePlaybackState()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val state = player.playbackState
            val playing = player.isPlaying
            if (state != lastEventsPlaybackState || playing != lastEventsIsPlaying) {
                val stateChanged = state != lastEventsPlaybackState
                lastEventsPlaybackState = state
                lastEventsIsPlaying = playing
                updatePlaybackState()
                updateProgressPolling()
                if (stateChanged) {
                    // 与源仓库 `onPlaybackStateChanged` 同口径：READY 才预取，缓冲/空闲/结束一律取消。
                    when (state) {
                        Player.STATE_READY -> maybeScheduleCdnDashPrefetch()
                        Player.STATE_BUFFERING,
                        Player.STATE_IDLE,
                        Player.STATE_ENDED -> cancelCdnDashPrefetch()
                    }
                }
            }
        }
    }
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            videoDecoderName = decoderName
            updatePlaybackState()
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            audioDecoderName = decoderName
            updatePlaybackState()
        }

        override fun onVideoDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String
        ) {
            if (videoDecoderName == decoderName) {
                videoDecoderName = null
                updatePlaybackState()
            }
        }

        override fun onAudioDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String
        ) {
            if (audioDecoderName == decoderName) {
                audioDecoderName = null
                updatePlaybackState()
            }
        }
    }
    private var exoPlayer: ExoPlayer? = null

    override fun updateConfig(config: PlayerConfig) {
        val next = normalizeConfig(config)
        if (next == playerConfig && exoPlayer != null) return

        val prev = exoPlayer
        stopProgressPolling()
        // 两个 CDN 开关是"活的"（源仓库 `CdnRegionPlugin` 也是改设置即对当前会话生效）：
        // · parallel 关 → `CdnPlaybackDataSource.read()` 当场降级成单连接（不打断播放、路由表保留）；
        // · 预取关 → 停掉后台预取任务。
        CdnTransferRuntime.configure(active = prev != null, parallel = next.cdnParallelDownload)
        if (!next.cdnAdaptivePrefetch) cancelCdnDashPrefetch()
        playerConfig = next
        resetRuntimeState()
        _currentSource.value = null
        val nextPlayer = buildPlayer(appContext, next)
        exoPlayer = nextPlayer
        _player.value = nextPlayer
        resetPlaybackFlows()
        // 视图可能仍绑定旧实例（如小窗未及重组摘除）：先标记释放并摘除全部绑定，
        // 再 release。此后视图层对旧实例的任何迟到 bind 请求都是 no-op（见 binder 所有权规则）
        prev?.let { player ->
            PlayerViewTargetBinder.onPlayerReleased(player)
            player.release()
            logReleasedRetention("rebuild")
        }
    }

    /**
     * 诊断用（仅 debug 输出）：[PlayerViewTargetBinder] 内部 `released` 是
     * `IdentityHashMap<Player, Unit>`，只写不删 —— 这是刻意设计（见 binder 类注释第 3 条），
     * 但意味着每个被释放的 player 会被强引用驻留。此处量化驻留规模，便于判断是否值得收敛。
     */
    private fun logReleasedRetention(where: String) {
        Logger.d(TAG) {
            "retained released players after $where = " +
                PlayerViewTargetBinder.releasedCountForDiagnostics()
        }
    }

    override suspend fun setSource(
        source: EngineSource,
        startPositionMs: Long?,
        playWhenReady: Boolean,
        metadata: MediaMetadata?
    ) {
        _currentSource.value = source
        try {
            val player = ensurePlayer()
            val itemMetadata = metadata ?: player.currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
            firstFrameSeq = 0L
            // 起播计时从这里开始（FirstFrameTimeoutPolicy 的 startedAtMs）：
            // 与"用户点开视频"同一时刻，而不是等第一帧回调。
            playbackStartAtMs = android.os.SystemClock.elapsedRealtime()
            firstFrameTimeoutReported = false
            val mediaSource = buildMediaSource(source, itemMetadata)
            player.setMediaSource(mediaSource)
            if (startPositionMs != null && startPositionMs > 0) {
                // PB-02：续播/恢复**必须精确**（吸附后的粗值会被写回历史记录）。
                // 这里显式断言 DEFAULT，而不是"假设上一步没设过 CLOSEST_SYNC"。
                runCatching { player.setSeekParameters(SeekParametersPolicy.forPreciseSeek()) }
                player.seekTo(startPositionMs.coerceAtLeast(0L))
            }
            player.playWhenReady = playWhenReady
            player.prepare()
            // PB-03：播放中禁止清理磁盘缓存（抽掉正在读的文件会中断播放）
            playbackMediaCache.markSourceActive(true)
            updatePlaybackState { it.copy(errorMessage = null) }
            updatePlaybackProgress()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.e(TAG, t) { "set source failed type=${source::class.simpleName} msg=${t.message}" }
            updatePlaybackState { it.copy(errorMessage = t.message ?: "加载媒体源失败") }
            updatePlaybackProgress()
            throw t
        }
    }

    override fun play() {
        val player = ensurePlayer()
        // ExoPlayer 语义：处于 STATE_ENDED 时单纯 play() 不会重播，必须先把位置移回起点。
        // 否则「播完后点中央播放键」表现为点击无反应（按钮在，但画面不动）。
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        }
        player.play()
    }

    override fun pause() {
        val player = exoPlayer ?: return
        player.pause()
    }

    override fun setRepeatMode(repeatOne: Boolean) {
        // 与 playerConfig 同为「期望状态」：必须先记住，再应用到当前实例。
        // 播放器是懒创建的（ensurePlayer），设置可能在实例存在之前就到达
        // （例如冷启动时订阅立刻发出持久化的值），此时只记状态、建实例时再施加，
        // 否则该次设置会被静默丢弃且永远不补。
        desiredRepeatOne = repeatOne
        Logger.d(TAG) {
            "setRepeatMode desiredRepeatOne=" + repeatOne + " playerExists=" + (exoPlayer != null)
        }
        exoPlayer?.let { applyRepeatMode(it) }
    }

    private fun applyRepeatMode(player: ExoPlayer) {
        player.repeatMode = if (desiredRepeatOne) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    override fun setSpeed(speed: Float) {
        val player = ensurePlayer()
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3f))
        updatePlaybackState()
    }

    override fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        // PB-02：**每次精确 seek 前重新断言** DEFAULT。媒体3 的 setSeekParameters 是保持状态
        // （不是单次参数）：上一次用户拖拽设下的 CLOSEST_SYNC 会残留，不重新断言，
        // 下一次 SponsorBlock 跳段/续播就会被吸附到关键帧。
        runCatching { player.setSeekParameters(SeekParametersPolicy.forPreciseSeek()) }
        player.seekTo(positionMs.coerceAtLeast(0L))
        updatePlaybackProgress()
    }

    /**
     * PB-02：用户主动 seek（进度条拖拽松手）走关键帧吸附，换取更快的落点。
     *
     * 成对设置：`CLOSEST_SYNC` → `seekTo` → 恢复 `DEFAULT`。恢复放在同一次调用里，
     * 是为了不让"用户拖拽"这个意图泄漏给后续的内部 seek；而 [seekTo] 侧还会在每次
     * 精确 seek 前再断言一次 DEFAULT —— 两道保险：无论吸附是在 seek 消息内生效，
     * 还是被推迟到后续的 doSomeWork 才生效，精确路径都不会被污染。
     */
    override fun seekUserInitiated(positionMs: Long) {
        val player = exoPlayer ?: return
        runCatching { player.setSeekParameters(SeekParametersPolicy.forUserSeek()) }
        player.seekTo(positionMs.coerceAtLeast(0L))
        runCatching { player.setSeekParameters(SeekParametersPolicy.forPreciseSeek()) }
        updatePlaybackProgress()
    }

    override fun setMediaMetadata(metadata: MediaMetadata) {
        val player = exoPlayer ?: return
        val current = player.currentMediaItem ?: return
        val next = current.buildUpon()
            .setMediaMetadata(metadata)
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, next)
    }

    override fun stopForReuse(resetPosition: Boolean) {
        stopProgressPolling()
        cancelCdnDashPrefetch()
        // 会话结束：CDN 传输运行时归零（源 `CdnRegionPlugin.onPlaybackStop` → `configure(false,false)`）。
        // 路由表在下次 `create()` 按新候选表重建，不会留下上一支视频的线路。
        CdnTransferRuntime.configure(active = false, parallel = false)
        cdnDashPrefetchPlan = null
        cdnPrefetchFrontierMs = -1L
        // PB-03：媒体源已停 → 允许清理磁盘缓存
        playbackMediaCache.markSourceActive(false)
        val player = exoPlayer ?: run {
            resetRuntimeState()
            _currentSource.value = null
            resetPlaybackFlows()
            return
        }
        resetRuntimeState()
        _currentSource.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        if (resetPosition) {
            player.seekTo(0)
        }
        resetPlaybackFlows()
    }

    override fun release() {
        stopProgressPolling()
        cancelCdnDashPrefetch()
        // 网络回调必须摘掉：引擎是单例、回调会一直持有它
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        cdnDashPrefetchPlan = null
        cdnPrefetchFrontierMs = -1L
        CdnTransferRuntime.configure(active = false, parallel = false)
        // PB-03：释放后没有在用的媒体源
        playbackMediaCache.markSourceActive(false)
        val player = exoPlayer ?: return
        resetRuntimeState()
        exoPlayer = null
        _player.value = null
        _currentSource.value = null
        resetPlaybackFlows()
        // StateFlow 先置空（UI/服务的后续快照不再指向该实例），再标记释放并摘除
        // 仍绑定的视图，最后 release：视图层永远只会见到存活实例或 null
        PlayerViewTargetBinder.onPlayerReleased(player)
        player.release()
        logReleasedRetention("release")
    }

    private fun buildPlayer(context: Context, config: PlayerConfig): ExoPlayer {
        // release() 会摘掉网络回调，这里补挂（否则第一次释放后"网络回来补重建"永久失效）
        ensureNetworkCallback()
        val renderersFactory = DefaultRenderersFactory(context)
            // .forceDisableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(config.decoderFallback)
            .setMediaCodecSelector(buildCodecSelector(config))

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(buildLoadControl(config))
            // 后台播放依赖 PlaybackService 前台服务，网络唤醒锁避免息屏后停流
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                // 新建实例一律带上的期望状态（buildPlayer 是三条建实例路径的唯一出口）
                applyRepeatMode(this)
                addListener(newPlayerListener(rebuildGen))
                addAnalyticsListener(analyticsListener)
            }
    }

    override fun setCdnTransferOptions(parallelDownload: Boolean, adaptivePrefetch: Boolean) {
        if (playerConfig.cdnParallelDownload == parallelDownload &&
            playerConfig.cdnAdaptivePrefetch == adaptivePrefetch
        ) {
            return
        }
        playerConfig = playerConfig.copy(
            cdnParallelDownload = parallelDownload,
            cdnAdaptivePrefetch = adaptivePrefetch
        )
        // active 跟着"有没有播放器"走：没有播放器时清空路由表（下次建源重新注册）。
        CdnTransferRuntime.configure(active = exoPlayer != null, parallel = parallelDownload)
        if (!adaptivePrefetch) cancelCdnDashPrefetch()
        Logger.d(TAG) {
            "cdn options parallel=$parallelDownload prefetch=$adaptivePrefetch playerExists=${exoPlayer != null}"
        }
    }

    /**
     * 系统级错误后的会话重建：记录当前源与播放进度/意图 → 建新 ExoPlayer 替换旧实例
     * （PlaybackService 经 playerEngine.player.collect 自动重绑 MediaSession）→
     * 复用现有 [setSource] 从原位置恢复。防抖 [RebuildThrottle] 限 60s 内 3 次，
     * 超限落回普通错误态（UI 已有展示）。仅由 playerListener（主线程）调用。
     */
    private fun recoverSession(errorCodeName: String, detail: String?) {
        val source = _currentSource.value
        val player = exoPlayer
        if (source == null || player == null) {
            updatePlaybackState { it.copy(errorMessage = detail) }
            return
        }
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        if (!rebuildThrottle.tryAcquire(android.os.SystemClock.elapsedRealtime())) {
            Logger.w(TAG) { "recover abandoned: rebuild limit hit code=$errorCodeName pos=$positionMs" }
            updatePlaybackState { it.copy(errorMessage = detail) }
            return
        }
        Logger.w(TAG) { "recovering from system error code=$errorCodeName pos=$positionMs" }
        stopProgressPolling()
        // 先推进代数再建新实例：新 listener 绑定新代数，旧实例 release 竞态的回调被甄别丢弃
        rebuildGen += 1L
        val nextPlayer = buildPlayer(appContext, playerConfig)
        exoPlayer = nextPlayer
        _player.value = nextPlayer
        resetRuntimeState()
        resetPlaybackFlows()
        // 释放旧实例前先标记释放并摘除仍绑定的视图（同 release() 的所有权顺序）
        PlayerViewTargetBinder.onPlayerReleased(player)
        player.release()
        logReleasedRetention("recover")
        runtimeScope.launch {
            try {
                setSource(source, positionMs, playWhenReady)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Logger.e(TAG, t) { "session recovery failed code=$errorCodeName msg=${t.message}" }
                updatePlaybackState { it.copy(errorMessage = t.message ?: detail) }
            }
        }
    }

    private fun buildCodecSelector(config: PlayerConfig): MediaCodecSelector {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            when (config.decoderMode) {
                DecoderMode.Hard -> {
                    val infos = MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    infos.sortedBy { it.softwareOnly }
                }

                DecoderMode.Soft -> MediaCodecSelector.PREFER_SOFTWARE
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val player = buildPlayer(appContext, playerConfig)
        exoPlayer = player
        _player.value = player
        resetPlaybackFlows()
        return player
    }

    private fun buildLoadControl(config: PlayerConfig): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.minBufferMs,
                config.maxBufferMs,
                config.playBufferMs,
                config.rebufferMs
            )
            .setBackBuffer(config.backBufferMs, true)
            .build()
    }

    private fun normalizeConfig(config: PlayerConfig): PlayerConfig {
        val playMs = max(config.playBufferMs, 0)
        val rebufMs = max(config.rebufferMs, 0)
        val minBufMs = max(config.minBufferMs, max(playMs, rebufMs))
        val maxBufMs = max(config.maxBufferMs, minBufMs)
        return config.copy(
            minBufferMs = minBufMs,
            maxBufferMs = maxBufMs,
            playBufferMs = playMs,
            rebufferMs = rebufMs,
            backBufferMs = max(config.backBufferMs, 0)
        )
    }

    private suspend fun buildMediaSource(
        source: EngineSource,
        metadata: MediaMetadata
    ): MediaSource {
        // 预取计划只属于"本次建源"：换源时先清空，避免拿上一支视频的 sidx 去预取。
        cdnDashPrefetchPlan = null
        cdnPrefetchFrontierMs = -1L
        return when (source) {
            is EngineSource.LiveFlv -> {
                val mediaSourceFactory = buildProgressiveMediaSourceFactory(source)
                val item = mediaItem(source.url, metadata)
                    .buildUpon()
                    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                    .build()
                mediaSourceFactory.createMediaSource(item)
            }

            is EngineSource.LocalMerged -> {
                val mediaSourceFactory = buildProgressiveMediaSourceFactory(source)
                val video = mediaSourceFactory.createMediaSource(mediaItem(source.videoUrl, metadata))
                if (source.audioUrl.isNullOrBlank()) {
                    video
                } else {
                    val audio = mediaSourceFactory.createMediaSource(mediaItem(source.audioUrl, metadata))
                    MergingMediaSource(true, video, audio)
                }
            }

            is EngineSource.SingleFileDash -> {
                val build = singleFileDashMediaSourceFactory.create(
                    source = source,
                    metadata = metadata,
                    // 并行分片开关（设置项）→ 传输运行时；关掉即单连接顺序读
                    cdnParallel = playerConfig.cdnParallelDownload
                )
                // sidx 的绝对区间只有建源时才知道 → 连同媒体源一起交给预取调度
                cdnDashPrefetchPlan = build.prefetch
                // 起播这一刻的"网络证据"：索引走了网络 ⇒ 起播这段必然走网络，
                // 此时会话层记 BUFFERING 才算到线上（索引命中缓存 ⇒ 不种这个信号）。
                // 见 `CdnHealthPolicy.shouldRecordSessionEvent` 与会话层 `recordCdnHealth`。
                if (build.indexFromNetwork) markNetworkActivity()
                build.mediaSource
            }

            is EngineSource.Progressive -> {
                val mediaSourceFactory = buildProgressiveMediaSourceFactory(source)
                if (source.segments.size == 1) {
                    mediaSourceFactory.createMediaSource(
                        mediaItem(source.segments.first().url, metadata)
                    )
                } else {
                    val builder = ConcatenatingMediaSource2.Builder()
                    source.segments.forEach { segment ->
                        builder.add(
                            mediaSourceFactory.createMediaSource(mediaItem(segment.url, metadata)),
                            segment.durationMs ?: C.TIME_UNSET
                        )
                    }
                    builder.build()
                }
            }
        }
    }

    private suspend fun buildProgressiveMediaSourceFactory(source: EngineSource): ProgressiveMediaSource.Factory {
        val requestSpec = source.toPlaybackRequestSpec()
        val upstreamFactory = OkHttpDataSource.Factory(videoOkHttpClient)
            .setUserAgent(requestSpec.userAgent)
        if (requestSpec.headers.isNotEmpty()) {
            upstreamFactory.setDefaultRequestProperties(requestSpec.headers)
        }
        // 直播 FLV 有主备线路；VOD 分段/本地文件仅单一线路，不启用切换
        val candidatesByUrl = when (source) {
            is EngineSource.LiveFlv ->
                mapOf(source.url to (listOf(source.url) + source.backupUrls).distinct())
            else -> emptyMap()
        }
        // PB-03：缓存包在**换线工厂之外**（换线发生在 CdnFailoverDataSource 内部的 URI 改写；
        // 套在内侧会让主/备线路各存一份同一分片）。file:// 由 CacheKeyFactory 返回 null 绕过。
        val failover = CdnFailoverDataSourceFactory(
            DefaultDataSource.Factory(appContext, upstreamFactory),
            candidatesByUrl
        )
        // PB-03：只有**点播**才包缓存（直播是流式边缘、file:// 是本地文件，都不该进缓存）。
        // 闸门判据见 PlaybackCacheGate；这里只按结论选工厂。
        val dataSourceFactory = if (PlaybackCacheGate.shouldCache(PlaybackCacheGate.kindOf(source))) {
            playbackMediaCache.wrapForPlayback(failover)
        } else {
            failover
        }
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            // CDN 偶发 403/超时配合 CdnFailoverDataSource 换线重试，重试次数过少会直接报错
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))
    }

    private fun mediaItem(uri: String, metadata: MediaMetadata): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun updatePlaybackState(
        transform: (PlayerPlaybackState) -> PlayerPlaybackState = { it }
    ) {
        val player = exoPlayer
        _playbackState.update { state ->
            transform(
                state.copy(
                    isPlaying = player?.isPlaying ?: false,
                    playWhenReady = player?.playWhenReady ?: false,
                    playbackState = (player?.playbackState ?: Player.STATE_IDLE).toPlaybackState(),
                    speed = player?.playbackParameters?.speed ?: 1f,
                    videoWidth = player?.videoSize?.width ?: 0,
                    videoHeight = player?.videoSize?.height ?: 0,
                    firstFrameSeq = firstFrameSeq,
                    videoDecoderName = videoDecoderName,
                    audioDecoderName = audioDecoderName,
                    firstFrameTimeoutSeq = firstFrameTimeoutSeq
                )
            )
        }
    }

    private fun updatePlaybackProgress() {
        val player = exoPlayer
        _playbackProgress.value = PlaybackProgress(
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            bufferedPositionMs = player?.bufferedPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = player?.duration?.takeIf { it > 0L } ?: 0L
        )
        // **07 复核报告 P1-A**：把真实缓冲/码率喂给 CDN 传输运行时 —— 这是自适应并发的**唯一输入**。
        // 逐字对照 BiliPai `VideoPlaybackViewModel.startPluginCheck()`（~500ms 轮询，
        // `playback(bufferedPosition - currentPosition, video+audio bitrate)`），本仓的 ~500ms
        // 轮询就是 [updateProgressPolling]，语义点相同。
        // 不喂的后果：`CdnTransferRuntime.snapshot.bufferMs` 恒为 0 ⇒ `resolveCdnConnections`
        // 永远落到 `< 5_000 → 3` 分支（并发恒 3、不再随缓冲回落），起播半窗口与"停滞救援"
        // 判据也恒真 —— 移植过来的自适应部分是**静默失效**的。
        if (player != null && CdnTransferRuntime.enabled) {
            CdnTransferRuntime.playback(
                (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
                (player.videoFormat?.bitrate?.coerceAtLeast(0)?.toLong() ?: 0L) +
                    (player.audioFormat?.bitrate?.coerceAtLeast(0)?.toLong() ?: 0L)
            )
        }
        // 重建后的"稳定期"判据：出帧只是候选，必须再稳定播放满 [RECOVERY_SETTLE_MS] 才清零预算。
        // 放这里的原因：进度轮询只在"正在播放且 READY"时运行（见 [updateProgressPolling]），
        // 所以能走到这段代码本身就是"确实在播"的证据 —— 缓存里出一帧就断的会话到不了这里。
        val firstFrameAt = recoveryFirstFrameAtMs
        if (firstFrameAt > 0L &&
            isRecoverySettled(android.os.SystemClock.elapsedRealtime() - firstFrameAt)
        ) {
            recoveryFirstFrameAtMs = -1L
            rebuildThrottle.reset()
            networkRetryCount = 0
        }
        refreshCdnRoutesIfStale()
        sampleNetworkActivity()
        checkFirstFrameTimeout()
        // 预取的周期性复查：与上面的"喂缓冲/码率"同一处（同为 500ms 的进度轮询），
        // 也和源仓库 `startPluginCheck()` 同语义点 —— 缓冲够厚的那一刻只有这里能发现。
        maybeScheduleCdnDashPrefetch()
    }

    private fun updateProgressPolling() {
        val player = exoPlayer
        val shouldPoll = player != null &&
            lastEventsIsPlaying &&
            lastEventsPlaybackState == Player.STATE_READY
        if (!shouldPoll) {
            stopProgressPolling()
            return
        }
        if (progressJob?.isActive == true) return
        progressJob?.cancel()
        progressJob = runtimeScope.launch {
            while (isActive) {
                delay(500)
                updatePlaybackProgress()
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * DASH 分段自适应预取的调度（语义对照 BiliPai
     * `VideoPlaybackViewModel.scheduleCdnDashPrefetch()`）。
     *
     * 四道闸门**缺一不可**（少任何一道都会变成"静默失效"）：
     * 1. 设置项 [PlayerConfig.cdnAdaptivePrefetch]（源 `plugin.isAdaptivePrefetchEnabled()`）；
     * 2. 当前源是单文件 DASH 且有预取计划（其它源没有 sidx，无从预取）；
     * 3. 缓冲 ≥ `CDN_PREFETCH_SAFE_BUFFER_MS`：直接复用 `resolveCdnPrefetchSegmentCount`
     *    的返回值（缓冲薄时它是 0，等于"不预取"），与真正执行的段数判据**同一处**，
     *    不会出现"闸门放行、策略却返回 0"的空转；
     * 4. Wi‑Fi/以太网（源 `NetworkUtils.isWifi(context)`）：蜂窝下不预取 —— 这是源仓库
     *    "流量机制太激进"反馈的修复点，不能省。
     *
     * 取消由播放器状态驱动（BUFFERING/IDLE/ENDED、seek、关设置、stop/release），
     * 与"操作由 caller 拥有、可取消"的设计一致（取消会立刻打断在途 socket，
     * 见 `PlaybackMediaCache.prefetchRange`）。
     *
     * ⚠️ **必须在播放中周期性复查**（见 [maybeScheduleCdnDashPrefetch]）：闸门 3 的条件
     * （缓冲 ≥15s）在 `STATE_READY` 那一刻**通常还不成立**（起播只等 `playBufferMs`）——
     * 只在 READY 判一次就等于"永远不预取"。这是真机验证时抓到的实际缺陷，
     * 源仓库的复查点同样在 ~500ms 轮询里。
     *
     * @return 确实发起了预取任务则为 true（调用方据此记录前沿，避免重复读 sidx）。
     */
    private fun scheduleCdnDashPrefetch(): Boolean {
        cdnPrefetchJob?.cancel()
        cdnPrefetchJob = null
        if (!playerConfig.cdnAdaptivePrefetch) return false
        val plan = cdnDashPrefetchPlan ?: return false
        val player = exoPlayer ?: return false
        if (player.playbackState != Player.STATE_READY) return false
        val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        val targetSegments = resolveCdnPrefetchSegmentCount(bufferedMs)
        if (targetSegments <= 0) return false
        if (!networkTypeProvider.isUnmetered()) return false
        val request = CdnDashPrefetchRequest(
            candidates = plan.candidates,
            indexRange = plan.indexRange,
            trackCacheKey = plan.cacheKey,
            bufferedDurationMs = bufferedMs,
            frontierPositionMs = player.bufferedPosition.coerceAtLeast(0L)
        )
        cdnPrefetchJob = runtimeScope.launch {
            val result = cdnSegmentPrefetcher.prefetch(request)
            DiagnosticLog.i(TAG) {
                "cdn prefetch plan=$targetSegments planned=${result.plannedSegments} " +
                    "cached=${result.cachedSegments} hosts=${result.selectedHosts.size} buf=${bufferedMs}ms"
            }
        }
        return true
    }

    /**
     * [PlayerEngine.isNetworkActiveWithin] 的实现：靠 `CdnTransferRuntime` 的字节计数采样。
     *
     * 为什么用"字节计数在涨"而不是"缓冲区在涨"：缓冲区在**缓存命中**时也会涨（数据来自磁盘），
     * 那正是我们要区分的场景。字节计数只在 CDN 传输层真的取回字节时才涨（缓存命中不经过它）。
     *
     * 采样点放在 500ms 进度轮询里（[sampleNetworkActivity]），避免每读一次都取 StateFlow 快照。
     */
    override fun isNetworkActiveWithin(windowMs: Long): Boolean {
        // 非 DASH 链没有 CDN 传输层 ⇒ 拿不到信号 ⇒ 按老口径照记（不许把记账整体关掉）。
        if (_currentSource.value !is EngineSource.SingleFileDash) return true
        if (!CdnTransferRuntime.enabled) return true
        val now = android.os.SystemClock.elapsedRealtime()
        return now - lastNetworkActivityAtMs <= windowMs
    }

    /** 比对 CDN 字节计数：涨了就刷新"最近一次真实网络读"的时刻。 */
    private fun sampleNetworkActivity() {
        val bytes = CdnTransferRuntime.state.value.networkBytes
        if (bytes > lastNetworkBytes) {
            lastNetworkBytes = bytes
            lastNetworkActivityAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    /** [PlayerEngine.currentServingHost]：拿不到（非 DASH / 还没 open 过）时返回 null。 */
    override fun currentServingHost(): String? = servingHost?.takeIf(String::isNotBlank)

    /** 显式标记"此刻正在走网络"（用于起播时索引 MISS 的那一下，见 `setSource`）。 */    private fun markNetworkActivity() {
        lastNetworkBytes = CdnTransferRuntime.state.value.networkBytes
        lastNetworkActivityAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * 起播超时检查（500ms 轮询里调，见 [FirstFrameTimeoutPolicy]）。
     *
     * 这是 `CdnHealthPolicy.Event.FirstFrameTimeout`（−180 分）**唯一的信号源** ——
     * 在此之前该事件在真机上从不产生，导致"起播总是很慢的线路"永远不会被降权。
     */
    private fun checkFirstFrameTimeout() {
        val player = exoPlayer ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val timedOut = FirstFrameTimeoutPolicy.isTimedOut(
            nowMs = now,
            startedAtMs = playbackStartAtMs,
            firstFrameRendered = recoveryFirstFrameAtMs > 0L,
            playWhenReady = player.playWhenReady,
            // 只有"还在等数据"才算线路问题：READY 却不出帧是渲染/画面绑定的事（真机补出来的判据）。
            buffering = player.playbackState == Player.STATE_BUFFERING,
            alreadyReported = firstFrameTimeoutReported
        )
        if (!timedOut) return
        firstFrameTimeoutReported = true
        firstFrameTimeoutSeq += 1L
        // 取证：量产包开着"现场诊断日志"时可以看到"哪一次开播、等了多久"。
        DiagnosticLog.w(TAG) {
            "first frame timeout after ${now - playbackStartAtMs}ms " +
                "state=${player.playbackState} host=${currentServingHost().orEmpty()}"
        }
        updatePlaybackState()
    }

    /**
     * 播放中的**周期性复查**（由 500ms 进度轮询调用，与源仓库 `startPluginCheck()` 同语义点）。     *
     * - 有任务在跑 → 不重复调度；
     * - 已缓冲前沿没有比上次预取时前进 [CDN_PREFETCH_FRONTIER_STEP_MS] → 不重复调度
     *   （否则每 500ms 都要重读一次 sidx，等于拿流量换重复劳动）；
     * - 闸门不过（缓冲还薄 / 非 Wi‑Fi / 设置关）→ 什么都不做，下一个 tick 再试。
     */
    private fun maybeScheduleCdnDashPrefetch() {
        if (cdnPrefetchJob?.isActive == true) return
        val player = exoPlayer ?: return
        val frontierMs = player.bufferedPosition.coerceAtLeast(0L)
        if (frontierMs - cdnPrefetchFrontierMs < CDN_PREFETCH_FRONTIER_STEP_MS) return
        if (scheduleCdnDashPrefetch()) cdnPrefetchFrontierMs = frontierMs
    }

    private fun cancelCdnDashPrefetch() {
        cdnPrefetchJob?.cancel()
        cdnPrefetchJob = null
    }

    private fun resetPlaybackFlows() {
        _playbackState.value = PlayerPlaybackState()
        _playbackProgress.value = PlaybackProgress()
    }

    /**
     * 长会话的**路由续期**（R-15 真机 soak 抓到的静默降级）。
     *
     * `CdnTransferRuntime` 的路由表是"本次 playurl 给出的合法候选 URL"授权，条目有效期
     * [CDN_ROUTE_TTL_MS]（10 分钟，源仓库同口径：防止过期授权被长期沿用）。但本仓一次播放可以
     * 持续几小时，而 `register()` 只在**起播**时调用一次 ⇒ 10 分钟后 `candidates()` 恒空 ⇒
     * `CdnPlaybackDataSource` 记 `monitored=false / parallel=false` ⇒ **并行下载静默降级成单连接**
     * （2026-10-06 15 分钟 soak 实测：23:23 起播，23:38 首次读到未缓存区间时 `cands=0`）。
     *
     * 修法：在 500ms 进度轮询里每 [CDN_ROUTE_REFRESH_MS]（5 分钟，**小于** 有效期）用
     * **当前播放源自己的候选**续期一次 —— 不引入任何新 URL，授权面不变。
     */
    private fun refreshCdnRoutesIfStale() {
        if (!CdnTransferRuntime.enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCdnRouteRefreshAtMs < CDN_ROUTE_REFRESH_MS) return
        val source = _currentSource.value as? EngineSource.SingleFileDash ?: return
        lastCdnRouteRefreshAtMs = now
        val video = listOfNotNull(source.videoUrl) + source.videoBackupUrls
        val audio = listOfNotNull(source.audioUrl) + source.audioBackupUrls
        CdnTransferRuntime.register(video = video, audio = audio)
        // 取证：长会话里"续期到底有没有发生"必须能从日志判定（否则只能靠 10 分钟后 cands 是否变 0 猜）。
        DiagnosticLog.i("CdnRoutes") {
            "refresh video=${video.size} audio=${audio.size} atElapsedMs=$now"
        }
    }

    private fun resetRuntimeState() {
        firstFrameSeq = 0L
        videoDecoderName = null
        audioDecoderName = null
        lastEventsPlaybackState = Player.STATE_IDLE
        lastEventsIsPlaying = false
        // 新会话还没出帧 ⇒ 稳定期计时作废（否则会把上一个会话的出帧时刻算进来）
        recoveryFirstFrameAtMs = -1L
        // 起播超时计时与一次性闸门同样按会话重置（计数器保留，会话层按增量消费）
        playbackStartAtMs = -1L
        firstFrameTimeoutReported = false
        // 供数 host 属于上一个会话，清掉（会话层会退回"选中那条"）
        servingHost = null
        // 网络恢复重试按**会话**计数：新会话允许再补一次
        pendingOfflineRetry = false
        offlineRetryUsed = false
    }

    private fun Int.toPlaybackState(): PlaybackState {
        return when (this) {
            Player.STATE_BUFFERING -> PlaybackState.Buffering
            Player.STATE_READY -> PlaybackState.Ready
            Player.STATE_ENDED -> PlaybackState.Ended
            else -> PlaybackState.Idle
        }
    }

    private fun Int.isSeekDiscontinuity(): Boolean {
        return this == Player.DISCONTINUITY_REASON_SEEK ||
            this == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    }

    private companion object {
        const val TAG = "Media3Player"

        /** 与源（BiliPai `maxRetries = 3`）一致：网络类错误最多重试 3 次。 */
        const val MAX_NETWORK_RETRIES = 3

        /**
         * 路由续期间隔：必须**小于** [CDN_ROUTE_TTL_MS]（10 分钟），留足余量。
         * 5 分钟一次 = 一场 3 小时的播放多写 ~36 次 map，可以忽略；换来的是并行能力不掉线。
         */
        const val CDN_ROUTE_REFRESH_MS = 5 * 60_000L
    }
}
