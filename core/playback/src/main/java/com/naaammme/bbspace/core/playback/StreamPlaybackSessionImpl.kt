package com.naaammme.bbspace.core.playback

import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.video.VideoDetailRepository
import com.naaammme.bbspace.core.danmaku.VodDanmakuRepository
import com.naaammme.bbspace.core.history.PlaybackHistoryRepository
import com.naaammme.bbspace.core.live.LiveRepository
import com.naaammme.bbspace.core.playback.LivePlaybackController
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.core.playback.VideoPlaybackController
import com.naaammme.bbspace.core.playback.VideoPlayerRepository
import com.naaammme.bbspace.core.model.DanmakuSessionState
import com.naaammme.bbspace.core.model.LivePlaybackError
import com.naaammme.bbspace.core.model.LivePlaybackViewState
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.PlayReportParams
import com.naaammme.bbspace.core.model.PlaybackAudio
import com.naaammme.bbspace.core.model.PlaybackControlMode
import com.naaammme.bbspace.core.model.PlaybackError
import com.naaammme.bbspace.core.model.PlaybackHistory
import com.naaammme.bbspace.core.model.PlaybackHistoryKey
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlaybackStream
import com.naaammme.bbspace.core.model.PlayerRepeatMode
import com.naaammme.bbspace.core.model.PlayerSessionState
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.StreamPlaybackSessionState
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import com.naaammme.bbspace.core.model.player.CdnSmartSelectionPolicy
import com.naaammme.bbspace.core.model.VideoCdnMode
import com.naaammme.bbspace.core.model.mergePlaybackUrls
import com.naaammme.bbspace.core.model.orderPlaybackUrlsForMode
import com.naaammme.bbspace.core.model.selectPlaybackCdn
import com.naaammme.bbspace.core.model.toReportParams
import com.naaammme.bbspace.core.model.toPlayableParams
import com.naaammme.bbspace.infra.player.DecoderMode
import com.naaammme.bbspace.infra.player.EngineSource
import com.naaammme.bbspace.infra.player.PlayerPlaybackState
import com.naaammme.bbspace.infra.player.PlayerConfig
import com.naaammme.bbspace.infra.player.PlayerEngine
import com.naaammme.bbspace.infra.player.cdn.CdnRegionPlugin
import com.naaammme.bbspace.infra.player.cdn.CdnTransferRuntime
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class StreamPlaybackSessionImpl @Inject constructor(
    private val videoRepository: VideoPlayerRepository,
    private val detailRepository: VideoDetailRepository,
    danmakuRepository: VodDanmakuRepository,
    private val appSettings: AppSettings,
    private val playerSettings: AppSettings,
    private val reporter: PlaybackReporter,
    private val authProvider: AuthProvider,
    private val playbackHistoryRepo: PlaybackHistoryRepository,
    private val liveRepository: LiveRepository,
    private val playerEngine: PlayerEngine,
    private val arbiter: PlayerEngineArbiter,
    private val cdnHealthStore: CdnHealthStore
) : StreamPlaybackSession, VideoPlaybackController, LivePlaybackController {

    //  engine
    override val player: StateFlow<Player?> = playerEngine.player
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // danmaku（取段以函数注入：源 VodDanmakuRepository 是 final class，测试用假实现走同一条链路）
    private val danmakuSession = VodDanmakuSession(
        scope = runtimeScope,
        fetchSegment = danmakuRepository::fetchSegment
    )
    override val danmakuState: StateFlow<DanmakuSessionState> = danmakuSession.state

    // vod state
    private val vodSession = MutableStateFlow(PlayerSessionState(biz = PlayBiz.UGC))
    private val _videoState = MutableStateFlow(VideoPlaybackState(biz = PlayBiz.UGC))
    override val videoState: StateFlow<VideoPlaybackState> = _videoState.asStateFlow()
    override val playbackProgress: StateFlow<PlaybackProgress> = playerEngine.playbackProgress
    private val prepMu = Mutex()
    private val openId = AtomicLong(0L)
    private var lastDiscontinuitySeq = 0L

    /** 已消费的「起播超时」计数（只增不减的事件流，见 [recordCdnHealth]）。 */
    private var lastFirstFrameTimeoutSeq = 0L
    private var nextPlayWhenReady = true
    private var currentVideoCdnMode = VideoCdnMode.Backup2

    /**
     * 本次开播的线路健康度快照（智能选线用）。
     *
     * 为什么缓存成字段而不是每次现取：`buildVideoEngineSource` 是**非挂起**函数，
     * 而取快照要过 [CdnHealthStore] 的互斥锁。开播时刷新一次即可 ——
     * 一次开播只选一次线路，中途不需要重算。
     */
    @Volatile
    private var cdnHealthSnapshot: Map<String, CdnHealthPolicy.Health> = emptyMap()

    /**
     * 本次开播的**区域/自定义线路插件**配置（W4，默认关闭）。
     *
     * 与 [cdnHealthSnapshot] 同一生命周期：开播时从设置读一次。插件只改"候选内容与顺序"
     * （见 [CdnRegionPlugin]），不碰并行槽位/降级恢复 —— 那两条归数据源与引擎。
     */
    @Volatile
    private var cdnRegionConfig: CdnRegionPlugin.Config = CdnRegionPlugin.Config.Disabled

    /** 当前主线路的 host（健康度回写按它记账）。 */
    @Volatile
    private var currentVideoHost: String = ""

    // live state
    private val _liveState = MutableStateFlow(LivePlaybackViewState())
    override val liveState: StateFlow<LivePlaybackViewState> = _liveState.asStateFlow()

    // session state
    private val _currentTarget = MutableStateFlow<StreamPlaybackTarget?>(null)
    override val currentTarget: StateFlow<StreamPlaybackTarget?> = _currentTarget.asStateFlow()
    private val _sessionState = MutableStateFlow(StreamPlaybackSessionState())
    override val sessionState: StateFlow<StreamPlaybackSessionState> = _sessionState.asStateFlow()

    init {
        runtimeScope.launch {
            playerEngine.playbackState.collect { state ->
                when (_currentTarget.value) {
                    is StreamPlaybackTarget.Video -> {
                        recordCdnHealth(state)
                        onVideoPlayerState(state)
                        reporter.onPlaybackState(
                            state = vodSession.value,
                            playbackState = state,
                            progress = currentPlaybackProgress()
                        )
                    }
                    is StreamPlaybackTarget.Live -> onLivePlayerState(state)
                    null -> {}
                }
            }
        }
        runtimeScope.launch {
            playerEngine.playbackProgress.collect { progress ->
                if (_currentTarget.value is StreamPlaybackTarget.Video) {
                    danmakuSession.onProgress(progress.positionMs)
                    // L4-101：进度脉搏转给上报器做**周期性本地历史检查点**。
                    // 挂这条流（约 0.5s 一跳）而不是 playbackState（只在状态变化时发）——
                    // 后者在稳态播放期间不触发，检查点等于没有。
                    reporter.onPlaybackProgress(progress.positionMs)
                }
            }
        }
        runtimeScope.launch {
            // 循环播放：设置变化即作用到当前播放器（含「先开循环、再开视频」的情形）。
            // 走设置流而不是直接调引擎，是为了让改设置与持久化保持同一条链路；
            // 若在视频加载前就订阅，flow 会立刻发一次当前值，因此无需额外初始化调用。
            playerSettings.state
                .map { it.playback.repeatMode }
                .distinctUntilChanged()
                .collect { mode ->
                    playerEngine.setRepeatMode(mode == PlayerRepeatMode.One)
                }
        }
        runtimeScope.launch {
            playerSettings.state.map { it.danmaku.enabled }.collect { enabled ->
                if (_currentTarget.value !is StreamPlaybackTarget.Video) return@collect
                if (!enabled) {
                    danmakuSession.clear()
                    return@collect
                }
                syncDanmakuSource(vodSession.value)
            }
        }
        runtimeScope.launch {
            playerSettings.state.map { it.playback.videoCdnMode }.collect { mode ->
                if (currentVideoCdnMode == mode) return@collect
                currentVideoCdnMode = mode
                if (_currentTarget.value !is StreamPlaybackTarget.Video) return@collect
                val state = vodSession.value
                if (state.playbackSource == null || state.currentStream == null) return@collect
                applyVideoSelection(state)
            }
        }
        runtimeScope.launch {
            // CDN 并行/预取开关：**不重建播放器**的活配置。
            // 为什么单列一条流：`updateConfig` 一旦发现配置变化就会重建 ExoPlayer，
            // 而"改个 CDN 开关"不该打断正在播的视频（源仓库改设置也是即时生效、不断流）。
            // flow 订阅即发一次当前值 ⇒ "先改设置、再开播放"同样覆盖。
            playerSettings.state
                .map { it.playback.cdnParallelDownload to it.playback.cdnAdaptivePrefetch }
                .distinctUntilChanged()
                .collect { (parallelDownload, adaptivePrefetch) ->
                    playerEngine.setCdnTransferOptions(
                        parallelDownload = parallelDownload,
                        adaptivePrefetch = adaptivePrefetch
                    )
                }
        }
    }

    // StreamPlaybackSession: lifecycle
    override suspend fun prepare() {
        prepMu.withLock {
            val config = withContext(Dispatchers.IO) { buildPlayerConfig() }
            withContext(Dispatchers.Main.immediate) { playerEngine.updateConfig(config) }
        }
    }

    override fun openVideo(target: VideoTarget) {
        runtimeScope.launch {
            if (_currentTarget.value is StreamPlaybackTarget.Live) {
                finishLivePlayback(releasePlayer = false)
            }
            openVideoInternal(target)
        }
    }

    override suspend fun openLive(
        route: LiveRoute,
        preferredQuality: Int,
        reportEntry: Boolean
    ) {
        val live = _currentTarget.value as? StreamPlaybackTarget.Live
        if (
            live?.route?.roomId == route.roomId &&
            (preferredQuality <= 0 || _liveState.value.playbackSource?.currentQn == preferredQuality) &&
            _liveState.value.error == null &&
            (_liveState.value.playbackSource != null || _liveState.value.isPreparing)
        ) {
            _currentTarget.value = StreamPlaybackTarget.Live(route)
            syncSessionState()
            return
        }

        if (_currentTarget.value is StreamPlaybackTarget.Video) {
            finishVideoPlayback(invalidateOpen = true, releasePlayer = false)
        }
        _currentTarget.value = StreamPlaybackTarget.Live(route)
        _liveState.value = LivePlaybackViewState(isPreparing = true)
        syncSessionState()

        try {
            prepare()
            val source = liveRepository.fetchPlaybackSource(route.roomId, preferredQuality)
            // 先占再写（与离线播放控制器共用这台播放器，见 PlayerEngineArbiter）
            arbiter.claim(EngineHolder.STREAM)
            playerEngine.setSource(
                source = EngineSource.LiveFlv(
                    url = source.primaryUrl,
                    backupUrls = source.backupUrls
                ),
                playWhenReady = true,
                metadata = MediaMetadata.Builder()
                    .setTitle(route.title?.takeIf(String::isNotBlank))
                    .setArtist(source.currentDescription.takeIf(String::isNotBlank))
                    .setArtworkUri(route.cover?.takeIf(String::isNotBlank)?.let(android.net.Uri::parse))
                    .build()
            )
            _liveState.value = _liveState.value.copy(
                isPreparing = false,
                playbackSource = source,
                error = null
            )
            syncSessionState()
            if (reportEntry) {
                reportRoomEntryAction(roomId = route.roomId, jumpFrom = route.jumpFrom)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Logger.e(TAG, t) {
                "load live source failed roomId=${route.roomId} qn=$preferredQuality msg=${t.message}"
            }
            _liveState.value = _liveState.value.copy(
                isPreparing = false,
                error = t.toLiveError(),
                playbackSource = _liveState.value.playbackSource.takeIf { it?.roomId == route.roomId }
            )
            syncSessionState()
        }
    }

    override fun close() {
        runtimeScope.launch {
            when (_currentTarget.value) {
                is StreamPlaybackTarget.Video -> finishVideoPlayback(invalidateOpen = true, releasePlayer = true)
                is StreamPlaybackTarget.Live -> finishLivePlayback(releasePlayer = true)
                null -> {}
            }
        }
    }

    // StreamPlaybackSession: playback control
    override fun play() {
        if (_currentTarget.value is StreamPlaybackTarget.Video) {
            nextPlayWhenReady = true
        }
        playerEngine.play()
    }

    override fun pause() {
        if (_currentTarget.value is StreamPlaybackTarget.Video) {
            nextPlayWhenReady = false
        }
        playerEngine.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        playerEngine.seekTo(positionMs)
        danmakuSession.seekTo(positionMs)
    }

    /**
     * PB-02：用户主动 seek（进度条拖拽松手）→ 引擎走关键帧吸附。
     * 弹幕会话仍按**精确位置**同步（弹幕按 6 分钟窗口取，与画面吸附到哪一帧无关，
     * 且吸附后的位置本来就在用户目标附近）。续播/跳段**不走这里**。
     */
    override fun seekUserInitiated(positionMs: Long) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        playerEngine.seekUserInitiated(positionMs)
        danmakuSession.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        playerEngine.setSpeed(speed)
    }

    // StreamPlaybackSession
    override fun switchVideoQuality(quality: Int) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        val state = vodSession.value
        val source = state.playbackSource ?: return
        val stream = source.streams.firstOrNull { it.quality == quality } ?: return
        val audio = selectAudio(stream, source.audios, state.currentAudio?.id ?: 0)
        applyVideoSelection(state = state.copy(currentStream = stream, currentAudio = audio))
    }

    override fun switchVideoAudio(audioId: Int) {
        if (_currentTarget.value !is StreamPlaybackTarget.Video) return
        val state = vodSession.value
        val source = state.playbackSource ?: return
        val audio = source.audios.firstOrNull { it.id == audioId } ?: return
        applyVideoSelection(state = state.copy(currentAudio = audio))
    }

    override fun switchLiveQuality(quality: Int) {
        val route = (_currentTarget.value as? StreamPlaybackTarget.Live)?.route ?: return
        if (_liveState.value.playbackSource?.currentQn == quality) return
        runtimeScope.launch {
            openLive(route = route, preferredQuality = quality, reportEntry = false)
        }
    }

    // vod: open & state
    private suspend fun openVideoInternal(
        target: VideoTarget,
        seekToMs: Long? = null,
        preferredQuality: Int? = null,
        controlMode: PlaybackControlMode = PlaybackControlMode.Default
    ) {
        val request = target.toPlayableParams().getResolveParams(
            seekToMs = seekToMs,
            preferredQuality = preferredQuality,
            controlMode = controlMode
        )
        val currentVideoTarget = (_currentTarget.value as? StreamPlaybackTarget.Video)?.target
        val currentState = vodSession.value
        if (
            currentVideoTarget == target &&
            currentState.error == null &&
            (currentState.playbackSource != null || currentState.isPreparing)
        ) {
            _currentTarget.value = StreamPlaybackTarget.Video(target)
            // 快速退出再进入：closeAfterReturnAnimation 已 pause（保画面停声），
            // 同会话短路返回时必须恢复播放，否则画面停留在暂停态
            if (!playerEngine.playbackState.value.playWhenReady) {
                nextPlayWhenReady = true
                playerEngine.play()
            }
            syncSessionState()
            return
        }
        val reuseDetail = currentState.takeIf {
            currentVideoTarget is VideoTarget.Ugc &&
                target is VideoTarget.Ugc &&
                currentVideoTarget.aid > 0L &&
                currentVideoTarget.aid == target.aid
        }

        val token = openId.incrementAndGet()
        nextPlayWhenReady = true
        reporter.bindOwner(token)
        // 上次取到的详情先上屏（标题/简介/UP 主/相关视频即时可见），网络详情到达后
        // 原地刷新——消除"整个简介区先骨架屏再等一个网络往返"的感知延迟
        val cachedDetail = if (reuseDetail == null) {
            detailRepository.peekCached(request.ids)?.detail
        } else {
            null
        }
        val initState = PlayerSessionState(
            request = request,
            biz = reuseDetail?.biz ?: request.playable.biz.biz,
            ids = ResolvedVideoIds(
                aid = request.ids.aid,
                cid = request.ids.cid,
                epId = request.ids.epId,
                seasonId = request.ids.seasonId,
                bvid = request.ids.bvid
            ),
            detail = reuseDetail?.detail ?: cachedDetail,
            detailLoading = reuseDetail == null,
            isPreparing = true
        )
        finishVideoPlayback(
            invalidateOpen = false,
            releasePlayer = false,
            nextTarget = target,
            nextState = initState,
            finalizeReportAsync = true
        )

        try {
            coroutineScope {
                val prepareJob = async { prepare() }
                val sourceJob = async { videoRepository.fetchPlaybackSource(request) }
                // 详情（标题/简介/UP主）与取流并行请求：此前串行在 setSource 之后，
                // 简介要多等一整个网络往返才出现（详情区明显延迟的主因之一）。
                // 应用时序不变——仍等 setSource 完成后落地（setMediaMetadata 需要媒体项就绪）。
                val detailJob = if (reuseDetail == null) {
                    async(Dispatchers.IO) {
                        runCatchingCancellable {
                            detailRepository.fetchVideoDetail(
                                ids = request.ids,
                                src = target.src
                            )
                        }
                    }
                } else {
                    null
                }
                val localResumeJob = async(Dispatchers.IO) { readLocalResumeIfResolvable(request) }
                val qualityJob = async {
                    request.preferredQuality ?: appSettings.defaultVideoQuality.first()
                }
                val audioJob = async { appSettings.defaultAudioQuality.first() }

                prepareJob.await()
                val source = sourceJob.await()
                val preferredQualityValue = qualityJob.await()
                val preferredAudioId = audioJob.await()
                val localResume = localResumeJob.await()
                if (openId.get() != token) return@coroutineScope

                val stream = source.streams.firstOrNull { it.quality == preferredQualityValue }
                    ?: source.streams.firstOrNull()
                val audio = selectAudio(stream, source.audios, preferredAudioId)
                val playbackPrefs = playerSettings.state.first().playback
                val cdnMode = playbackPrefs.videoCdnMode
                currentVideoCdnMode = cdnMode
                // W4：区域/自定义线路插件（设置项 → 配置快照；默认全关，关时候选与排序不变）
                cdnRegionConfig = CdnRegionPlugin.Config(
                    enabled = playbackPrefs.cdnRegionEnabled,
                    strictCustomCdn = playbackPrefs.cdnStrictCustomCdn,
                    regionHosts = playbackPrefs.cdnRegionHosts,
                    customRules = playbackPrefs.cdnCustomRules
                )
                // 智能选线：开播前取一次健康度快照（已按当前时间折现）。
                // 非 Smart 档也会取 —— 成本是一次 map 拷贝，但换来"切到智能档立刻生效"，
                // 不必重开播放。
                cdnHealthSnapshot = cdnHealthStore.snapshot()
                val engineSource = buildVideoEngineSource(
                    stream = stream,
                    audio = audio,
                    cdnMode = cdnMode,
                    durationMs = source.durationMs
                )
                    ?: throw NoPlayableStreamException("暂无可用播放流")
                val startMs = resolveStartMs(request, source, localResume)
                if (openId.get() != token) return@coroutineScope

                val playbackState = vodSession.value.copy(
                    playbackSource = source,
                    currentStream = stream,
                    currentAudio = audio,
                    error = null
                )
                vodSession.value = playbackState.copy(isPreparing = true)
                arbiter.claim(EngineHolder.STREAM)
                playerEngine.setSource(
                    source = engineSource,
                    startPositionMs = startMs,
                    playWhenReady = nextPlayWhenReady
                )
                if (openId.get() != token) return@coroutineScope

                vodSession.value = playbackState.copy(isPreparing = false)
                refreshVideoState()
                if (reuseDetail != null) {
                    runtimeScope.launch {
                        startReportIfReady(vodSession.value)
                        syncDanmakuSource(vodSession.value)
                    }
                    return@coroutineScope
                }

                val detailResult = detailJob?.await()?.getOrElse { error ->
                    if (error is CancellationException) throw error
                    if (openId.get() != token) return@coroutineScope
                    val currentTarget = (_currentTarget.value as? StreamPlaybackTarget.Video)?.target
                    if (currentTarget != target) return@coroutineScope
                    vodSession.value = vodSession.value.copy(
                        detail = null,
                        detailLoading = false,
                        detailError = error.message ?: "加载视频详情失败"
                    )
                    refreshVideoState()
                    return@coroutineScope
                } ?: return@coroutineScope
                if (openId.get() != token) return@coroutineScope
                val currentTarget = (_currentTarget.value as? StreamPlaybackTarget.Video)?.target
                if (currentTarget != target) return@coroutineScope
                applyDetailResult(detailResult)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                if (openId.get() == token && vodSession.value.playbackSource == null) {
                    nextPlayWhenReady = true
                    _currentTarget.value = null
                    vodSession.value = PlayerSessionState(biz = PlayBiz.UGC)
                    _videoState.value = VideoPlaybackState(biz = PlayBiz.UGC)
                    danmakuSession.clear()
                    syncSessionState()
                }
                throw t
            }
            if (openId.get() != token) return

            nextPlayWhenReady = true
            Logger.e(TAG, t) {
                "load playback source failed biz=${request.playable.biz.biz} " +
                    "aid=${request.ids.aid} cid=${request.ids.cid} " +
                    "epId=${request.ids.epId} seasonId=${request.ids.seasonId} " +
                    "q=${request.preferredQuality} msg=${t.message}"
            }
            vodSession.value = vodSession.value.copy(
                isPreparing = false,
                error = when (t) {
                    is NoPlayableStreamException -> PlaybackError.NoPlayableStream(
                        t.message ?: "暂无可用播放流"
                    )
                    else -> PlaybackError.RequestFailed(
                        t.message ?: "Failed to load playback source",
                        t
                    )
                }
            )
            refreshVideoState()
        }
    }

    private suspend fun finishVideoPlayback(
        invalidateOpen: Boolean,
        releasePlayer: Boolean,
        nextTarget: VideoTarget? = null,
        nextState: PlayerSessionState? = null,
        finalizeReportAsync: Boolean = false
    ) {
        val playbackState = playerEngine.playbackState.value
        val progress = currentPlaybackProgress()
        val hadVideo = vodSession.value.playbackSource != null
        val hasEngineMedia = playerEngine.currentSource.value != null ||
            player.value?.currentMediaItem != null
        if (invalidateOpen) openId.incrementAndGet()
        _currentTarget.value = nextTarget?.let { StreamPlaybackTarget.Video(it) }
        nextPlayWhenReady = true
        vodSession.value = nextState ?: PlayerSessionState(biz = PlayBiz.UGC)
        _videoState.value = nextState?.toVideoPlaybackState(
            state = playerEngine.playbackState.value,
            prev = _videoState.value,
            isNewSeekEvent = false
        ) ?: VideoPlaybackState(biz = PlayBiz.UGC)
        if (nextState?.playbackSource == null) {
            danmakuSession.clear()
        }
        syncSessionState()
        // 引擎所有权仲裁（ST-07）：缓存播放可能已经接管这台播放器，
        // 非占用者既不能 release 也不能 stopForReuse，否则别人的在播会话被凭空拆掉。
        if (hasEngineMedia) {
            when {
                releasePlayer -> if (arbiter.release(EngineHolder.STREAM)) playerEngine.release()
                arbiter.isHolder(EngineHolder.STREAM) -> playerEngine.stopForReuse(resetPosition = true)
            }
        }

        val detached = if (hadVideo) {
            reporter.detachSession(
                playbackState = playbackState.playbackState,
                positionMs = progress.positionMs
            )
        } else {
            null
        }
        detached ?: return
        if (!finalizeReportAsync) {
            reporter.finalizeSession(detached)
            return
        }
        runtimeScope.launch(Dispatchers.IO) {
            reporter.finalizeSession(detached)
        }
    }

    private fun currentPlaybackProgress(): PlaybackProgress {
        val currentPlayer = player.value
        val progress = playerEngine.playbackProgress.value
        return PlaybackProgress(
            positionMs = currentPlayer?.currentPosition?.coerceAtLeast(0L)
                ?: progress.positionMs,
            bufferedPositionMs = currentPlayer?.bufferedPosition?.coerceAtLeast(0L)
                ?: progress.bufferedPositionMs,
            durationMs = currentPlayer?.duration?.takeIf { it > 0L } ?: progress.durationMs
        )
    }

    private fun currentPlayWhenReady(): Boolean {
        return player.value?.playWhenReady ?: playerEngine.playbackState.value.playWhenReady
    }

    private fun currentPlaybackPositionMs(): Long {
        return currentPlaybackProgress().positionMs
    }

    private fun applyDetailResult(detailResult: com.naaammme.bbspace.core.model.VideoDetailResult) {
        vodSession.value = vodSession.value.copy(
            biz = detailResult.biz,
            ids = detailResult.ids,
            detail = detailResult.detail,
            detailLoading = false,
            detailError = null
        )
        playerEngine.setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(detailResult.detail.title.takeIf(String::isNotBlank))
                .setArtist(detailResult.detail.owner?.name?.takeIf(String::isNotBlank))
                .setArtworkUri(
                    detailResult.detail.cover?.takeIf(String::isNotBlank)?.let(android.net.Uri::parse)
                )
                .build()
        )
        refreshVideoState()
        runtimeScope.launch {
            startReportIfReady(vodSession.value)
            syncDanmakuSource(vodSession.value)
        }
    }

    private fun applyVideoSelection(
        state: PlayerSessionState
    ) {
        val source = buildVideoEngineSource(
            stream = state.currentStream,
            audio = state.currentAudio,
            cdnMode = currentVideoCdnMode,
            durationMs = state.playbackSource?.durationMs
        ) ?: return
        // 记录调用时的 open token：若期间用户打开了新视频，本次过期的
        // setSource 不应覆盖新会话（setSource 内含网络 IO，存在挂起窗口）
        val token = openId.get()
        runtimeScope.launch {
            try {
                if (openId.get() != token) return@launch
                arbiter.claim(EngineHolder.STREAM)
                playerEngine.setSource(
                    source,
                    currentPlaybackPositionMs(),
                    currentPlayWhenReady()
                )
                if (openId.get() != token) return@launch
                vodSession.value = state
                refreshVideoState()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (openId.get() != token) return@launch
                Logger.e(TAG, t) { "apply video selection failed msg=${t.message}" }
                refreshVideoState()
            }
        }
    }

    // vod: stream selection helpers
    private fun selectAudio(
        stream: PlaybackStream?,
        audios: List<PlaybackAudio>,
        preferredId: Int
    ): PlaybackAudio? {
        if (audios.isEmpty()) return null
        val linkedId = (stream as? PlaybackStream.Dash)?.audioId
        return audios.firstOrNull { it.id == preferredId && preferredId > 0 }
            ?: audios.firstOrNull { it.id == linkedId }
            ?: audios.firstOrNull()
    }

    private fun buildVideoEngineSource(
        stream: PlaybackStream?,
        audio: PlaybackAudio?,
        cdnMode: VideoCdnMode,
        durationMs: Long?
    ): EngineSource? {
        return when (stream) {
            is PlaybackStream.Dash -> {
                val cdn = selectPlaybackCdn(stream, audio, cdnMode, cdnHealthSnapshot) ?: return null
                currentVideoHost = CdnSmartSelectionPolicy.hostOf(cdn.videoUrl)
                // 备用线路的顺序同样按模式处理：**首元素是已选主线路，其后是其余备选**。
                // 智能档下这一条尤其重要 —— 引擎"打开失败依次切换"的次序本身就成了智能序，
                // 不需要另造一套切换机制。
                val videoOrder = orderPlaybackUrlsForMode(
                    urls = mergePlaybackUrls(stream.videoUrl, stream.videoBackupUrls),
                    selected = cdn.videoUrl,
                // 闭环修复：不传 healthByHost 时默认 emptyMap()，备用线路顺序永远走「无观测」分支
                healthByHost = cdnHealthSnapshot,
                    mode = cdnMode
                )
                val audioOrder = cdn.audioUrl?.let { selected ->
                    orderPlaybackUrlsForMode(
                        urls = mergePlaybackUrls(audio?.url, audio?.backupUrls.orEmpty()),
                        selected = selected,
                    // 音频同理（同一处修复）
                    healthByHost = cdnHealthSnapshot,
                        mode = cdnMode
                    )
                } ?: emptyList()
                // ---- W4：区域 / 自定义线路插件（**只改候选内容与顺序**）----
                // 关闭时 applyOrderedUrls 逐元素返回入参 ⇒ 与既有行为逐字节一致；
                // 打开时按"自定义 > 属地优选 > 原始"重排/改写 host（stub 语义见 CdnRegionPolicy）。
                // 它不碰并行槽位/降级/卡顿恢复 —— 那些预算在数据源与引擎里，插件无权越过。
                val regionConfig = cdnRegionConfig
                val pluginVideoOrder = CdnRegionPlugin.applyOrderedUrls(
                    originalUrls = videoOrder,
                    config = regionConfig,
                    healthByHost = cdnHealthSnapshot
                )
                val pluginAudioOrder = CdnRegionPlugin.applyOrderedUrls(
                    originalUrls = audioOrder,
                    config = regionConfig,
                    healthByHost = cdnHealthSnapshot
                )
                val pluginVideoUrl = pluginVideoOrder.firstOrNull() ?: cdn.videoUrl
                val pluginAudioUrl = pluginAudioOrder.firstOrNull() ?: cdn.audioUrl
                // ---- W4 收口（2026-10-18）：把**真正下发的**候选表发布给诊断面板 ----
                // 面板读的是 `CdnTransferRuntime.state`，若让它自己按设置重算一遍，
                // 显示的是"预测顺序"而不是"下发顺序" —— 那种诊断会说谎。
                // 这里复用插件的 diagnostics（纯列表变换、不抛业务异常），只在开播时写一次。
                // ⚠️ **不要**用裸 `runCatching` 包这一段：本文件在 suspend 链路上，
                // `VideoLogicGuardTest.cancellationSwallowingIsClosed` 会红（裸 runCatching
                // 会吞掉协程取消）。本来也不该兜——纯变换没有可恢复的失败面。
                val regionDiagnostics = CdnRegionPlugin.diagnostics(
                    originalUrls = videoOrder,
                    config = regionConfig,
                    healthByHost = cdnHealthSnapshot
                )
                CdnTransferRuntime.noteRegionSelection(
                    enabled = regionDiagnostics.enabled,
                    hosts = regionDiagnostics.orderedHosts,
                    sources = regionDiagnostics.sourceLabels
                )
                // 插件可以改写主线路（属地/自定义命中时首元素已不是服务端原选）⇒ 记账跟着实际主线路走。
                currentVideoHost = CdnSmartSelectionPolicy.hostOf(pluginVideoUrl)
                // 现场取证（选线闭环的**唯一**可判定证据）：
                // 服务端原始次序 vs 服务端没有的健康度快照下选出来的那条。
                // 没有这行，"智能选线到底有没有用健康度"在真机上就只剩"结果对不对"的猜测
                // —— 2026-10-06 真机 A/B 正是靠它证明了"被重罚的 host 会被排到后面"。
                DiagnosticLog.i("BbCdnSelect") {
                    val server = mergePlaybackUrls(stream.videoUrl, stream.videoBackupUrls)
                        .joinToString(",") { CdnSmartSelectionPolicy.hostOf(it) }
                    val ranked = pluginVideoOrder.joinToString(",") { CdnSmartSelectionPolicy.hostOf(it) }
                    "mode=$cdnMode observations=${cdnHealthSnapshot.size} " +
                        "region=${if (regionConfig.enabled && regionConfig.hasRewrites) "on" else "off"} " +
                        "server=[$server] ranked=[$ranked] chosen=${CdnSmartSelectionPolicy.hostOf(pluginVideoUrl)}"
                }
                EngineSource.SingleFileDash(
                    videoUrl = pluginVideoUrl,
                    audioUrl = pluginAudioUrl,
                    videoSampleMimeType = resolveVideoSampleMimeType(stream.codecId),
                    audioSampleMimeType = audio?.let { resolveAudioSampleMimeType(it.id) },
                    durationMs = durationMs,
                    // 首元素为已选主线路，其后为其余备用线路，打开失败时依次切换
                    videoBackupUrls = pluginVideoOrder,
                    audioBackupUrls = pluginAudioOrder
                )
            }

            is PlaybackStream.Progressive -> {
                EngineSource.Progressive(
                    segments = stream.segments.map {
                        EngineSource.ProgressiveSegment(it.url, it.durationMs)
                    }
                )
            }

            null -> null
        }
    }

    // B站 codec/audio id 映射为 Media3 sampleMimeType
    // TODO: 未来需要为 eac3 提供软解 ?
    private fun resolveVideoSampleMimeType(codecId: Int): String = when (codecId) {
        12 -> MimeTypes.VIDEO_H265
        13 -> MimeTypes.VIDEO_AV1
        else -> MimeTypes.VIDEO_H264
    }

    private fun resolveAudioSampleMimeType(audioId: Int): String = when (audioId) {
        30250 -> MimeTypes.AUDIO_E_AC3
        30251 -> MimeTypes.AUDIO_FLAC
        else -> MimeTypes.AUDIO_AAC
    }

    // vod: config & history
    private suspend fun buildPlayerConfig(): PlayerConfig {
        val settings = playerSettings.state.first()
        val buffer = settings.buffer.profile
        val playback = settings.playback
        return PlayerConfig(
            minBufferMs = buffer.minBufferMs,
            maxBufferMs = buffer.maxBufferMs,
            playBufferMs = buffer.playBufferMs,
            rebufferMs = buffer.rebufferMs,
            backBufferMs = buffer.backBufferMs,
            decoderMode = if (playback.preferSoftwareDecode) {
                DecoderMode.Soft
            } else {
                DecoderMode.Hard
            },
            decoderFallback = playback.decoderFallback,
            // CDN 并行/预取开关（对齐 BiliPai CdnRegionPlugin 的两个开关；由设置流 → PlayerConfig → 引擎）
            cdnParallelDownload = playback.cdnParallelDownload,
            cdnAdaptivePrefetch = playback.cdnAdaptivePrefetch
        )
    }

    private suspend fun resolveStartMs(
        request: PlaybackRequest,
        source: PlaybackSource,
        prefetchedLocal: PlaybackHistory?
    ): Long {
        // PT-03：判据收口到 PlaybackResumePolicy（单一真值源）——
        // 来源优先级不变（显式 seek > 本地 > 服务端），新增上游 95% 重播规则与钳位。
        val explicit = request.seekToMs
        if (explicit != null) {
            return PlaybackResumePolicy.resolveResumePosition(
                explicitMs = explicit,
                localMs = null,
                localFinished = false,
                serverMs = null,
                durationMs = source.durationMs
            )
        }
        val report = request.toInitialReportParams()
            ?: return PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null,
                localMs = null,
                localFinished = false,
                serverMs = source.resumePositionMs,
                durationMs = source.durationMs
            )
        val key = PlaybackHistoryKey.video(report)
        val local = when {
            prefetchedLocal?.key == key -> prefetchedLocal
            else -> playbackHistoryRepo.getVideo(authProvider.mid, key)
        }
        return PlaybackResumePolicy.resolveResumePosition(
            explicitMs = null,
            localMs = local?.progressMs,
            localFinished = local?.finished ?: false,
            serverMs = source.resumePositionMs,
            durationMs = source.durationMs
        )
    }

    private suspend fun readLocalResumeIfResolvable(request: PlaybackRequest): PlaybackHistory? {
        if (request.seekToMs != null) return null
        val report = request.toInitialReportParams() ?: return null
        val key = PlaybackHistoryKey.video(report)
        return playbackHistoryRepo.getVideo(authProvider.mid, key)
    }

    private fun PlaybackRequest.toInitialReportParams(): PlayReportParams? {
        val reqIds = ids
        val resolved = ResolvedVideoIds(
            aid = reqIds.aid,
            cid = reqIds.cid,
            epId = reqIds.epId,
            seasonId = reqIds.seasonId,
            bvid = reqIds.bvid
        )
        if (!resolved.danmakuReady) return null
        return resolved.toReportParams(
            biz = playable.biz.biz,
            subType = playable.biz.subType
        )
    }

    private suspend fun startReportIfReady(state: PlayerSessionState) {
        if (state.detail == null) return
        val request = state.request ?: return
        val ids = state.ids.takeIf { it.danmakuReady } ?: return
        if (state.playbackSource == null) return
        val biz = state.biz
        reporter.startSession(
            request = request,
            state = state,
            report = ids.toReportParams(
                biz = biz,
                subType = request.playable.biz.subType
            ),
            startPositionMs = currentPlaybackPositionMs()
        )
        reporter.onPlaybackState(
            state = vodSession.value,
            playbackState = playerEngine.playbackState.value,
            progress = currentPlaybackProgress()
        )
    }

    private suspend fun syncDanmakuSource(state: PlayerSessionState) {
        DiagnosticLog.i(
            "BbDanmaku",
            "syncDanmakuSource detail=${state.detail != null} idsReady=${state.ids.danmakuReady} " +
                "source=${state.playbackSource != null}"
        )
        if (state.detail == null) return
        val ids = state.ids.takeIf { it.danmakuReady } ?: return
        val source = state.playbackSource ?: return
        if (!playerSettings.state.first().danmaku.enabled) return
        DiagnosticLog.i(
            "BbDanmaku",
            "syncDanmakuSource -> setSource aid=${ids.aid} cid=${ids.cid} durationMs=${source.durationMs}"
        )
        danmakuSession.setSource(ids, source.durationMs)
    }

    // PT-03：原 canResume（3s 尾规则）与 COMPLETE_THRESHOLD_MS 已删除——
    // 判据统一收口到 PlaybackResumePolicy（isWatchedThrough / resolveResumePosition），
    // 结构断言见 PlaybackResumePolicyTest.constantLivesInExactlyOnePlace。

    // vod: state mapping
    private fun refreshVideoState(isNewSeekEvent: Boolean = false) {
        _videoState.value = vodSession.value.toVideoPlaybackState(
            state = playerEngine.playbackState.value,
            prev = _videoState.value,
            isNewSeekEvent = isNewSeekEvent
        )
        syncSessionState()
    }

    private fun PlayerSessionState.toVideoPlaybackState(
        state: PlayerPlaybackState,
        prev: VideoPlaybackState,
        isNewSeekEvent: Boolean
    ): VideoPlaybackState {
        return VideoPlaybackState(
            biz = biz,
            ids = ids,
            detail = detail,
            detailLoading = detailLoading,
            detailError = detailError,
            isPreparing = isPreparing,
            playbackSource = playbackSource,
            currentStream = currentStream,
            currentAudio = currentAudio,
            error = error,
            isPlaying = state.isPlaying,
            playWhenReady = state.playWhenReady,
            playbackState = state.playbackState,
            speed = state.speed,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            videoDecoderName = state.videoDecoderName,
            audioDecoderName = state.audioDecoderName,
            hasRenderedFirstFrame = state.firstFrameSeq > 0L,
            seekEventId = if (isNewSeekEvent) prev.seekEventId + 1L else prev.seekEventId,
            playerError = state.errorMessage
        )
    }

    /**
     * 把播放状态折算成**线路健康度事件**（智能选线的输入）。
     *
     * 口径（如实）：
     * - `Ready` → 出画面（加分）；`Buffering` → 卡顿（减分）；
     * - **`PlayerError` 没接**：本仓的 `PlaybackState` 只有 Idle/Buffering/Ready/Ended，
     *   没有错误态；引擎的失败切换发生在播放源内部，不上抛到这里。所以"报错扣分"
     *   这条维度**目前拿不到信号**，`CdnHealthPolicy.PlayerError` 保留但暂无人调用 ——
     *   要接它得先让引擎把"实际用了哪条线路、失败了几次"暴露出来（需要改
     *   `SingleFileDashMediaSourceFactory` 的切换回调），属后续工作，不在这里假装接上。
     * - 记账对象是**本次开播选中的主线路 host**；引擎内部切到备用线路后，这里记不到
     *   （同上，需要引擎回传实际使用的 URL）。
     * - **`Buffering` 追加了一条前置判据**（2026-10-07 缓存审查）：只有本次会话**刚发生过
     *   真实网络读**时才记 —— 字节缓存命中时播放不走 CDN 传输层，那种"卡顿"与该线路无关
     *   （实测纯缓存重播会给某条线白扣 −220 分并显示"易缓冲"）。
     */
    private fun recordCdnHealth(state: PlayerPlaybackState) {
        when (state.playbackState) {
            PlaybackState.Ready -> recordHealthEvent(CdnHealthPolicy.Event.PlaybackReady)
            PlaybackState.Buffering -> recordHealthEvent(CdnHealthPolicy.Event.Buffering)
            PlaybackState.Idle, PlaybackState.Ended -> Unit
        }
        // 起播超时（`FirstFrameTimeout`，−180 分）：引擎在 500ms 轮询里判、计数只增不减，
        // 这里按增量消费一次。在此之前这条判据在真机上**从不产生**（定义在策略里没人调）。
        if (state.firstFrameTimeoutSeq != lastFirstFrameTimeoutSeq) {
            lastFirstFrameTimeoutSeq = state.firstFrameTimeoutSeq
            recordHealthEvent(CdnHealthPolicy.Event.FirstFrameTimeout)
        }
    }

    /**
     * 把一次事件记到"**此刻真正在供数的** host"（拿不到就退回开播选中的那条），
     * 并先过"惩罚必须有网络证据"的判据。
     *
     * 两个口径都是 2026-10-08 的修正（见 `docs/port-analysis/12-播放链路与缓存策略交互审查.md`）：
     * - **目标 host**：换线之后不再把好线的功劳记到原来那条头上（[PlayerEngine.currentServingHost]）；
     * - **证据闸门**：缓存命中时播放不走 CDN 层，此时"进入缓冲/起播慢"与线路无关，不许扣分。
     */
    private fun recordHealthEvent(event: CdnHealthPolicy.Event) {
        val host = playerEngine.currentServingHost()?.takeIf(String::isNotBlank) ?: currentVideoHost
        if (host.isBlank()) return
        if (!CdnHealthPolicy.shouldRecordSessionEvent(
                event = event,
                networkActive = playerEngine.isNetworkActiveWithin()
            )
        ) {
            return
        }
        runtimeScope.launch { cdnHealthStore.record(host, event) }
    }

    private fun onVideoPlayerState(state: PlayerPlaybackState) {
        val isNewSeekEvent = state.seekEventSeq != lastDiscontinuitySeq
        lastDiscontinuitySeq = state.seekEventSeq
        refreshVideoState(isNewSeekEvent = isNewSeekEvent)
    }

    // live: internals 
    private fun onLivePlayerState(state: PlayerPlaybackState) {
        _liveState.value = _liveState.value.copy(
            isPlaying = state.isPlaying,
            playWhenReady = state.playWhenReady,
            playbackState = state.playbackState,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            hasRenderedFirstFrame = state.firstFrameSeq > 0L,
            playerError = state.errorMessage
        )
        syncSessionState()
    }

    private fun finishLivePlayback(releasePlayer: Boolean) {
        val hadLive = _liveState.value.playbackSource != null
        _currentTarget.value = null
        _liveState.value = LivePlaybackViewState()
        if (hadLive) {
            // 同 finishVideoPlayback：直播收尾只在仍是占用者时才动引擎（ST-07）。
            when {
                releasePlayer -> if (arbiter.release(EngineHolder.STREAM)) playerEngine.release()
                arbiter.isHolder(EngineHolder.STREAM) -> playerEngine.stopForReuse(resetPosition = true)
            }
        }
        syncSessionState()
    }

    // session state
    private fun syncSessionState() {
        val mediaMetadata = player.value?.currentMediaItem?.mediaMetadata
        _sessionState.value = when (val target = _currentTarget.value) {
            is StreamPlaybackTarget.Video -> {
                val state = _videoState.value
                StreamPlaybackSessionState(
                    target = target,
                    isPreparing = state.isPreparing,
                    isPlaying = state.isPlaying,
                    playWhenReady = state.playWhenReady,
                    playbackState = state.playbackState,
                    title = state.detail?.title?.takeIf(String::isNotBlank)
                        ?: mediaMetadata?.title?.toString().orEmpty(),
                    subtitle = state.detail?.owner?.name?.takeIf(String::isNotBlank)
                        ?: mediaMetadata?.artist?.toString()?.takeIf(String::isNotBlank),
                    cover = state.detail?.cover?.takeIf(String::isNotBlank)
                        ?: mediaMetadata?.artworkUri?.toString()?.takeIf(String::isNotBlank),
                    videoWidth = state.videoWidth,
                    videoHeight = state.videoHeight,
                    hasRenderedFirstFrame = state.hasRenderedFirstFrame,
                    playerError = state.playerError
                )
            }

            is StreamPlaybackTarget.Live -> {
                val state = _liveState.value
                StreamPlaybackSessionState(
                    target = target,
                    isPreparing = state.isPreparing,
                    isPlaying = state.isPlaying,
                    playWhenReady = state.playWhenReady,
                    playbackState = state.playbackState,
                    title = target.route.title?.takeIf(String::isNotBlank)
                        ?: mediaMetadata?.title?.toString().orEmpty(),
                    subtitle = target.route.ownerName?.takeIf(String::isNotBlank)
                        ?: mediaMetadata?.artist?.toString()?.takeIf(String::isNotBlank),
                    cover = target.route.cover?.takeIf(String::isNotBlank)
                        ?: mediaMetadata?.artworkUri?.toString()?.takeIf(String::isNotBlank),
                    videoWidth = state.videoWidth,
                    videoHeight = state.videoHeight,
                    hasRenderedFirstFrame = state.hasRenderedFirstFrame,
                    playerError = state.playerError
                )
            }

            null -> StreamPlaybackSessionState()
        }
    }

    private suspend fun reportRoomEntryAction(roomId: Long, jumpFrom: Int) {
        // 见 RemotePlaybackReporter 的同款注释：包挂起调用不能吞掉取消。
        runCatchingCancellable {
            liveRepository.reportRoomEntryAction(roomId = roomId, jumpFrom = jumpFrom)
        }.onFailure { error ->
            Logger.w(TAG) {
                "report room entry failed roomId=$roomId jumpFrom=$jumpFrom msg=${error.message}"
            }
        }
    }

    private fun Throwable.toLiveError(): LivePlaybackError {
        val msg = message ?: "直播取流失败"
        return when (this) {
            is IllegalStateException -> LivePlaybackError.NoPlayableStream(msg)
            else -> LivePlaybackError.RequestFailed(msg, this)
        }
    }

    private companion object {
        const val TAG = "StreamPlayback"
    }
}

private class NoPlayableStreamException(
    message: String
) : IllegalStateException(message)
