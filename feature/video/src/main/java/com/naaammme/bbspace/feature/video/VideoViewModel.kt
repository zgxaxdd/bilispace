package com.naaammme.bbspace.feature.video

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.network.NetworkTypeProvider
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.feed.FeedRepository
import com.naaammme.bbspace.core.feed.StoryFeedRepository
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.VideoPlaybackController
import com.naaammme.bbspace.core.playback.VideoPlayerRepository
import com.naaammme.bbspace.core.playback.repository.PlayUrlCacheMetrics
import com.naaammme.bbspace.core.favorite.FavoriteRepository
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.CommentSubjectTool
import com.naaammme.bbspace.core.model.DanmakuConfig
import com.naaammme.bbspace.core.model.FavoriteFolder
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.PlayerBufferProfile
import com.naaammme.bbspace.core.model.PlayerRepeatMode
import com.naaammme.bbspace.core.model.VideoCdnMode
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadMeta
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.core.model.VideoStat
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoUserState
import com.naaammme.bbspace.core.model.isSameEntry
import com.naaammme.bbspace.core.model.toPlayableParams
import com.naaammme.bbspace.core.sponsorblock.SbActionType
import com.naaammme.bbspace.core.sponsorblock.SbSegment
import com.naaammme.bbspace.core.sponsorblock.SbSkipType
import com.naaammme.bbspace.core.sponsorblock.SponsorBlockClient
import com.naaammme.bbspace.core.video.VideoActionRepository
import com.naaammme.bbspace.core.video.VideoDetailRepository
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.SwipeErrorPolicy
import com.naaammme.bbspace.core.swipe.SwipePrefetchPolicy
import com.naaammme.bbspace.core.swipe.SwipeQueueTail
import com.naaammme.bbspace.core.swipe.appendSwipeEntries
import com.naaammme.bbspace.core.swipe.model.SwipeId
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeEventType
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeHub
import com.naaammme.bbspace.core.swipe.qoe.SwipeQoeSnapshot
import com.naaammme.bbspace.core.swipe.session.SwipeEntryVia
import com.naaammme.bbspace.core.swipe.session.SwipeSessionController
import com.naaammme.bbspace.core.swipe.session.SwipeSessionState
import com.naaammme.bbspace.core.swipe.session.isSnapshotWorthy
import com.naaammme.bbspace.core.swipe.session.parseSwipeSessionSnapshot
import com.naaammme.bbspace.core.swipe.session.swipeSessionSnapshotJson
import com.naaammme.bbspace.core.swipe.selectSwipeRefillFallbackTarget
import com.naaammme.bbspace.core.swipe.shouldFetchNextRefillPage
import com.naaammme.bbspace.core.swipe.shouldRunSwipeRefillFallback
import com.naaammme.bbspace.core.swipe.source.SwipeCursor
import com.naaammme.bbspace.core.swipe.source.SwipeSourceRegistry
import com.naaammme.bbspace.core.swipe.swipeEntriesFromRelates
import com.naaammme.bbspace.feature.video.swipesource.HomeFeedSwipeSource
import com.naaammme.bbspace.feature.video.swipesource.StoryFeedSwipeSource
import com.naaammme.bbspace.feature.video.swipesource.SwipeSeenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 空降助手运行时状态：当前视频的片段、跳过进度与待展示的交互元素。
 */
data class SponsorBlockUiState(
    val enabled: Boolean = false,
    val segments: List<SbSegment> = emptyList(),
    val categoryTypes: Map<String, Int> = emptyMap(),
    val toastEnabled: Boolean = true,
    val markersEnabled: Boolean = true,
    val skippedUuids: Set<String> = emptySet(),
    val dismissedUuids: Set<String> = emptySet(),
    val manualSkip: SbSegment? = null,
    val poiJump: SbSegment? = null,
    val toastMessage: String? = null
)

/**
 * 详情页互动区状态：计数 = 服务器值 + 本地增量，userState 为乐观更新后的结果
 */
data class VideoInteractionState(
    val stat: VideoStat? = null,
    val userState: VideoUserState? = null,
    val likeDelta: Long = 0,
    val coinDelta: Long = 0,
    val favDelta: Long = 0,
    val busy: Boolean = false
)

/**
 * 收藏夹选择面板状态
 */
data class FavFolderSheetState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val folders: List<FavoriteFolder> = emptyList(),
    val initialFavedIds: Set<Long> = emptySet(),
    val selectedIds: Set<Long> = emptySet(),
    val dismissRequest: Boolean = false
)

/**
 * 互动区（点赞/投币/收藏计数与按钮态）的重置去重键。
 * 刻意包含 cid / epId：见 [VideoViewModel] 里 map 的注释。
 */
private data class InteractionResetKey(
    val aid: Long,
    val cid: Long,
    val epId: Long,
    val stat: VideoStat?,
    val userState: VideoUserState?
)

@HiltViewModel
class VideoViewModel @Inject constructor(
    /** 会话快照的持久化（进程重建恢复，SW-02 不变式 4）。 */
    private val savedStateHandle: SavedStateHandle,
    private val playbackController: VideoPlaybackController,
    private val playerSettings: AppSettings,
    private val sponsorBlockClient: SponsorBlockClient,
    private val actionRepository: VideoActionRepository,
    private val favoriteRepository: FavoriteRepository,
    private val authStore: AuthStore,
    private val storyFeedRepository: StoryFeedRepository,
    private val feedRepository: FeedRepository,
    /** 相邻页预取：与正常开播共用同一份 playurl LRU（见 [VideoPlayerRepository.preloadPlayUrl]）。 */
    private val playbackVideoRepository: VideoPlayerRepository,
    /** 补货兜底：拉队尾那一支的详情，用它的相关推荐补队列。 */
    private val detailRepository: VideoDetailRepository,
    /** 跨会话「已推记忆」（SW-05）：推荐流/story 流给过的视频不再进新会话。 */
    private val seenStore: SwipeSeenStore,
    /** 预取条数按网络类型决定（WiFi 才多预）。 */
    private val networkTypeProvider: NetworkTypeProvider
) : ViewModel() {

    private val _targetStack = MutableStateFlow<List<VideoTarget>>(emptyList())

    // ===== 竖屏全屏「上下滑切换视频」（BiliPai 上游同款功能的 bbspace 化）=====
    // 会话状态的**单一真值源**是 [SwipeSessionController]（core:swipe，SW-02）：
    // 锚点/队列/补货尾态/两个幂等键（自动翻页、预取去重）全部归它管，exit 一并清空。
    // 本 VM 只负责"何时喂数据"（网络、取流、翻页回调）与"把状态暴露给 UI"。
    private val swipeSession = SwipeSessionController()
    val swipeAnchor: StateFlow<SwipeEntry?> = swipeSession.anchor
    val swipeQueue: StateFlow<List<SwipeEntry>> = swipeSession.queue

    /** 队列尾部状态（补货中 / 到尽头）：驱动连播页底部的提示。 */
    val swipeTail: StateFlow<SwipeQueueTail> = swipeSession.tail

    /** 播完自动翻页的幂等键（SW-02 从 pager 的 remember 提升到会话状态机，随 exit 清空）。 */
    val swipeLastAutoAdvancedKey: StateFlow<String?> = swipeSession.lastAutoAdvancedKey

    val player: StateFlow<Player?> = playbackController.player
    val videoState: StateFlow<VideoPlaybackState> = playbackController.videoState
    val playbackProgress: StateFlow<PlaybackProgress> = playbackController.playbackProgress
    val settingsState = playerSettings.state

    /**
     * 播放页控件模糊开关（设置 → 外观设计）。浮层组件不持有 ViewModel，由
     * [com.naaammme.bbspace.feature.video.player.VideoPlayerPane] 收集后经
     * CompositionLocal 下发到叶子控件。
     */
    val playerControlsBlur: StateFlow<Boolean> = playerSettings.playerControlsBlur
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * 播放页控件玻璃材质（磨砂 / 液态），设置里独立于全局材质。
     * 同 [playerControlsBlur]，经 CompositionLocal 下发到浮层叶子控件。
     */
    val playerControlsMaterial: StateFlow<GlassMaterial> = playerSettings.playerControlsMaterial
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlassMaterial.FROSTED)

    // ===== 空降助手 =====
    private val _sbState = MutableStateFlow(SponsorBlockUiState())
    val sbState: StateFlow<SponsorBlockUiState> = _sbState.asStateFlow()

    // ===== 视频互动（点赞/投币/收藏/三连） =====
    private val _interaction = MutableStateFlow(VideoInteractionState())
    val interaction: StateFlow<VideoInteractionState> = _interaction.asStateFlow()

    private val _favSheet = MutableStateFlow(FavFolderSheetState())
    val favSheet: StateFlow<FavFolderSheetState> = _favSheet.asStateFlow()

    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val msg: SharedFlow<String> = _msg.asSharedFlow()

    init {
        // 换视频 / 换分P / 换选集时以服务器返回的状态重置互动区
        viewModelScope.launch {
            videoState
                .map {
                    // 去重键必须带上 cid / epId：同一视频切分P、切选集时 detail 往往不变，
                    // 只用 Triple(aid, stat, userState) 去重会让上一分P的**乐观 delta**
                    // （点赞/投币后的计数偏移）残留到新分P上，表现为新分P计数凭空多一。
                    InteractionResetKey(
                        aid = it.ids.aid,
                        cid = it.ids.cid,
                        epId = it.ids.epId,
                        stat = it.detail?.stat,
                        userState = it.detail?.userState
                    )
                }
                .distinctUntilChanged()
                .collect { key ->
                    _interaction.value = VideoInteractionState(stat = key.stat, userState = key.userState)
                }
        }
    }

    init {
        // 滑动队列接力：每切到一个视频，其详情自带的 relevant 推荐经去重/多样性
        // 过滤后追加进队列（appendSwipeEntries 幂等，同一批 relates 重复到达无害）。
        // 合并统一走会话状态机（会话未开启 / story 流在途时锚点未发布 → 状态机内部 no-op）。
        viewModelScope.launch {
            videoState
                .map { it.detail }
                .distinctUntilChanged()
                .collect { detail ->
                    detail ?: return@collect
                    swipeSession.mergeRelates(swipeEntriesFromRelates(detail.relates))
                }
        }
    }

    init {
        // 会话快照落盘（进程重建恢复用）：只在可恢复状态（Ready/粘滞补货态）写，
        // 会话退出（Idle）即清除。单一写入点，避免各处"忘了更新快照"。
        // Bootstrapping / PlaybackFailed 不动既有快照：前者无可恢复队列，
        // 后者用户重试或退出前快照仍有效。
        viewModelScope.launch {
            swipeSession.state.collect { state ->
                when {
                    state is SwipeSessionState.Idle ->
                        savedStateHandle.remove<String>(KEY_SWIPE_SESSION_SNAPSHOT)
                    state.isSnapshotWorthy() -> {
                        val json = swipeSession.snapshot()?.let(::swipeSessionSnapshotJson)
                        if (json != null) {
                            savedStateHandle[KEY_SWIPE_SESSION_SNAPSHOT] = json
                        } else {
                            savedStateHandle.remove<String>(KEY_SWIPE_SESSION_SNAPSHOT)
                        }
                    }
                }
            }
        }
    }

    init {
        // 视频（bvid+cid）或开关变化时重新拉取片段
        viewModelScope.launch {
            combine(
                playerSettings.sponsorBlockEnabled,
                playerSettings.sponsorBlockCategoryTypes,
                videoState.map { Pair(it.ids.bvid, it.ids.cid) }.distinctUntilChanged()
            ) { enabled, types, ids -> Triple(enabled, types, ids) }
                .collectLatest { (enabled, types, ids) ->
                    val bvid = ids.first
                    val cid = ids.second
                    if (!enabled || bvid.isNullOrBlank() || cid <= 0L) {
                        _sbState.value = SponsorBlockUiState(enabled = enabled, categoryTypes = types)
                        return@collectLatest
                    }
                    _sbState.value = SponsorBlockUiState(enabled = true, categoryTypes = types)
                    val segments = sponsorBlockClient.getSegments(bvid, cid)
                        .getOrDefault(emptyList())
                        .filter { it.cid == cid && it.actionType != SbActionType.FULL }
                    _sbState.update { it.copy(segments = segments) }
                }
        }
        // 提示与标记开关
        viewModelScope.launch {
            combine(
                playerSettings.sponsorBlockToast,
                playerSettings.sponsorBlockMarkers
            ) { toast, markers -> toast to markers }
                .collect { (toast, markers) ->
                    _sbState.update { it.copy(toastEnabled = toast, markersEnabled = markers) }
                }
        }
        // 播放进度调度
        viewModelScope.launch {
            playbackProgress.collect { progress ->
                evaluateSponsorBlock(progress.positionMs)
            }
        }
    }

    private fun SbSegment.skipTypeFor(types: Map<String, Int>): SbSkipType =
        types[category.wire]
            ?.let { ordinal -> SbSkipType.entries.getOrNull(ordinal) }
            ?: category.defaultSkipType

    private fun evaluateSponsorBlock(positionMs: Long) {
        val state = _sbState.value
        if (!state.enabled || state.segments.isEmpty()) return
        val types = state.categoryTypes
        var autoEndMs = -1L
        val autoUuids = mutableListOf<String>()
        var firstAuto: SbSegment? = null
        var manual: SbSegment? = null
        var poi: SbSegment? = null
        for (seg in state.segments) {
            when (seg.skipTypeFor(types)) {
                SbSkipType.AUTO_SKIP -> if (seg.containsMs(positionMs) && seg.uuid !in state.skippedUuids) {
                    if (firstAuto == null) firstAuto = seg
                    autoUuids += seg.uuid
                    if (seg.endMs > autoEndMs) autoEndMs = seg.endMs
                }

                SbSkipType.MANUAL_SKIP -> if (seg.uuid !in state.dismissedUuids) {
                    when (seg.actionType) {
                        SbActionType.POI -> if (positionMs >= seg.startMs - 5_000L && positionMs <= seg.endMs + 5_000L && poi == null) {
                            poi = seg
                        }

                        SbActionType.SKIP -> if (seg.containsMs(positionMs)) {
                            val current = manual
                            if (current == null || seg.startMs < current.startMs) manual = seg
                        }

                        else -> Unit
                    }
                }

                SbSkipType.SHOW_OVERLAY -> {
                    // **刻意不跳**，但也要刻意写出来：这一档原本靠最后的 `else` 兜住，
                    // 于是"仅显示"与"禁用"在行为上完全等价（审查报告点出的正是这个）。
                    //
                    // 复核后的事实：进度条上的片段标记由 `VideoPlayerPane` 的 `sbMarks`
                    // 画（`sbState.segments.map { ... }`，受 `markersEnabled` 单独控制），
                    // **与 skipType 无关** —— 所以"仅显示"想要的效果其实已经存在。
                    // 这一档真正缺的是"它与 DISABLED 应有不同的下游语义"这个产品定义
                    // （例如 DISABLED 连标记都不画），那要改 sbMarks 的过滤规则，
                    // 属设计决策、不在本轮盲改。这里显式列出，是为了让这个分支不再
                    // 是一个"看起来像漏写"的隐式 fall-through。
                }

                else -> Unit
            }
        }
        if (autoEndMs > 0L && firstAuto != null) {
            seekTo(autoEndMs)
            _sbState.update {
                it.copy(
                    skippedUuids = it.skippedUuids + autoUuids,
                    toastMessage = if (it.toastEnabled) {
                        "已自动跳过${firstAuto.category.shortTitle}片段"
                    } else {
                        it.toastMessage
                    }
                )
            }
            viewModelScope.launch {
                autoUuids.forEach { sponsorBlockClient.viewedSegment(it) }
            }
            return
        }
        if (manual?.uuid != state.manualSkip?.uuid || poi?.uuid != state.poiJump?.uuid) {
            _sbState.update { it.copy(manualSkip = manual, poiJump = poi) }
        }
    }

    /** 手动跳过：跳到当前提示片段的结尾。 */
    fun sbSkipManual() {
        val state = _sbState.value
        val seg = state.manualSkip ?: return
        seekTo(seg.endMs)
        _sbState.update {
            it.copy(
                manualSkip = null,
                skippedUuids = it.skippedUuids + seg.uuid,
                toastMessage = if (it.toastEnabled) "已跳过${seg.category.shortTitle}片段" else it.toastMessage
            )
        }
        viewModelScope.launch { sponsorBlockClient.viewedSegment(seg.uuid) }
    }

    /** 关闭本次手动跳过提示（该片段本次不再提示）。 */
    fun sbDismissManual() {
        val seg = _sbState.value.manualSkip ?: return
        _sbState.update {
            it.copy(
                dismissedUuids = it.dismissedUuids + seg.uuid,
                manualSkip = null
            )
        }
    }

    /** 跳到精彩时刻起点。 */
    fun sbJumpPoi() {
        val seg = _sbState.value.poiJump ?: return
        seekTo(seg.startMs)
        _sbState.update { it.copy(poiJump = null) }
    }

    fun consumeSbToast() {
        if (_sbState.value.toastMessage == null) return
        _sbState.update { it.copy(toastMessage = null) }
    }

    val commentSubject: CommentSubject?
        get() {
            val src = currentTarget()?.src ?: return null
            val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return null
            return CommentSubjectTool.video(aid, src)
        }

    internal val danmakuState = playbackController.danmakuState

    fun openRoot(target: VideoTarget) {
        _targetStack.value = listOf(target)
        playbackController.openVideo(target)
    }

    /** 当前页内导航栈顶目标；null 表示本 ViewModel 尚未打开过视频（如 Activity 重建后）。 */
    val activeTarget: VideoTarget?
        get() = _targetStack.value.lastOrNull()

    fun openTarget(target: VideoTarget) {
        val current = currentTarget()
        if (current == target) return
        _targetStack.value = when {
            current == null -> listOf(target)
            current.isSameEntry(target) -> _targetStack.value.dropLast(1) + target
            else -> _targetStack.value + target
        }
        playbackController.openVideo(target)
    }

    fun popPage(): Boolean {
        val stack = _targetStack.value
        if (stack.size <= 1) return false
        val nextStack = stack.dropLast(1)
        val nextTarget = nextStack.last()
        _targetStack.value = nextStack
        playbackController.openVideo(nextTarget)
        return true
    }

    /**
     * 进入全屏时开启一个滑动会话：页 0 锚定当前视频，队列从其相关推荐起步。
     * 之后每次切视频，新详情的 relates 由 init 的监听接力并入。
     *
     * 锚点 target **取栈顶的 activeTarget**（带完整 aid/cid/src），只有栈顶缺失时
     * 才用 ids 兜底构造——翻回页 0 会经 [openSwipeTarget] 重新 openVideo，bvid-only
     * 的裸 target 会让详情解析多走一次兜底（实测语义不变但链路变长）。
     * 快速开退全屏时重复调用是幂等的（anchor/queue 整体重置）。
     */
    fun beginSwipeSession() {
        val detail = videoState.value.detail ?: return
        val ids = videoState.value.ids
        val bvid = ids.bvid.orEmpty().trim()
        if (bvid.isEmpty()) return
        val anchorTarget = activeTarget as? VideoTarget.Ugc
            ?: VideoTarget.Ugc(aid = ids.aid, cid = ids.cid, bvid = bvid)
        val seed = SwipeEntry(
            target = anchorTarget,
            bvid = bvid,
            title = detail.title,
            cover = detail.cover.orEmpty(),
            author = detail.owner?.name,
            durationText = null,
            ownerMid = detail.owner?.mid?.takeIf { it > 0L },
            durationSec = detail.durationSec,
            dimension = detail.dimension
        )
        val seedQueue = appendSwipeEntries(
            existing = emptyList(),
            incoming = swipeEntriesFromRelates(detail.relates),
            anchorKey = anchorTarget.bvid.orEmpty().ifEmpty { "aid:${anchorTarget.aid}" }
        )
        swipeSession.enterReady(
            seed = seed,
            via = SwipeEntryVia.DetailPullDown,
            seedQueue = seedQueue
        )
        // QoE（SW-06）：只记类型与来源标签，不含标题/UP/账号（§4.8 隐私口径）
        SwipeQoeHub.record(SwipeQoeEventType.Enter, source = "detail")
        android.util.Log.i(
            "PortraitSwipe",
            "session: anchor=$bvid relates=${detail.relates.size} queue=${seedQueue.size} " +
                "bvidFilled=${seedQueue.count { it.bvid.isNotEmpty() }}"
        )
    }

    /** 退出全屏时清会话：下次进入全屏重新锚定当时的视频（见 VideoScreen 的接线的判据）。 */
    fun clearSwipeSession() {
        swipeSession.exit()
        lastPrefetchRequest = null
        SwipeQoeHub.record(SwipeQoeEventType.Exit)
    }

    init {
        // playurl 缓存的命中/未命中 → QoE 具名计数器（SW-12）：诊断页要能读到
        // "预取到底有没有省掉往返"。出口是单例（后注册覆盖先注册），多 VM 场景无重复计数。
        PlayUrlCacheMetrics.setListener { hit ->
            SwipeQoeHub.recordCounter(
                if (hit) SwipeQoeSnapshot.COUNTER_PLAYURL_HIT
                else SwipeQoeSnapshot.COUNTER_PLAYURL_MISS
            )
        }
    }

    // ===== 刷视频模式（首页顶栏入口 → Story 全屏，BiliPai StoryScreen 同款）=====

    private var storyBootstrapStarted = false

    /** 首页推荐流的分页游标（SW-05 起由 SwipeSourceBatch.nextCursor 回填）。 */
    private var storyFeedCursor = 0L
    private var loadingMoreSwipe = false

    init {
        // 进程重建恢复（SW-02 不变式 4）：SavedStateHandle 里有会话快照 → 只灌状态。
        // 不重放任何起步网络（fetchFeed / story / relates 都不跑；storyBootstrapStarted
        // 一并置位，Activity 重建时重入的 bootstrapStorySession 因此成为 no-op）。
        // 当前这支的取流由 Activity 的 openRoot 正常发起——与"点开任何一支视频"同一条
        // 请求，不在"不重放网络"的口径内（播放器没有流就没有画面）。
        savedStateHandle.get<String>(KEY_SWIPE_SESSION_SNAPSHOT)
            ?.let(::parseSwipeSessionSnapshot)
            ?.let { snap ->
                swipeSession.restore(page = snap.page, anchor = snap.anchor, queue = snap.queue)
                if (snap.via == SwipeEntryVia.StoryFeed) {
                    storyBootstrapStarted = true
                }
                android.util.Log.i(
                    "PortraitSwipe",
                    "restore: page=${snap.page} queue=${snap.queue.size} via=${snap.via}"
                )
            }
    }

    /**
     * 刷视频模式（story）会话的启动：以入口视频为页 0 锚点，竖屏故事流
     * （`x/v2/feed/index/story`，BiliPai 上游同款接口）作为后续队列。
     * 与详情全屏路径的 [beginSwipeSession]（relates 接力起步）互斥——story 模式下
     * VideoScreen 的会话接线会跳过 beginSwipeSession，锚点全权由本方法负责；
     * 流到达后**强制覆盖**队列（story 流优先），之后的翻页接力回到统一的
     * relates merge + [loadMoreSwipeQueue] 补货。
     * 流失败时锚点仍设为入口视频（降级为"单视频全屏"，返回可重进）。
     */
    fun bootstrapStorySession(seed: VideoTarget) {
        if (storyBootstrapStarted) return
        storyBootstrapStarted = true
        val seedUgc = seed as? VideoTarget.Ugc ?: return
        val seedBvid = seedUgc.bvid.orEmpty().trim()
        if (seedBvid.isEmpty() && seedUgc.aid <= 0L) return
        // 先进 Bootstrapping：种子随身记入状态机，但锚点流**不发布**（保持 null）——
        // VideoScreen 的 pager 启用判据依赖"锚点非空"，流在途期间加载层照旧盖住首屏
        // （行为零变化红线；SW-13 之后由状态派生 UI）。
        swipeSession.enterBootstrapping(
            seed = SwipeEntry(
                target = seedUgc,
                bvid = seedBvid,
                title = "",
                cover = "",
                author = null,
                durationText = null
            ),
            via = SwipeEntryVia.StoryFeed
        )
        viewModelScope.launch {
            // ── 起步批次的数据源：**对齐 BiliPai + 多源编排（SW-05）** ────────────
            // Registry 按 cost 升序问源：首页推荐流（Light）优先，拿不到才退 story
            // 竖屏流（Heavy 兜底）——与旧版"首页流起步、story 兜底"完全同序。
            // isUnmetered 传 true：起步**必须尽力有内容**，两个源都是单请求，
            // 蜂窝门控作用在补货/预取的量上，不在起步这一问上（与旧行为一致）。
            val registry = SwipeSourceRegistry(
                listOf(
                    HomeFeedSwipeSource(feedRepository),
                    StoryFeedSwipeSource(
                        storyFeedRepository = storyFeedRepository,
                        seedAid = seedUgc.aid,
                        seedBvid = seedBvid
                    )
                )
            )
            val batch = registry.nextBatch(
                cursor = SwipeCursor(sourceId = HomeFeedSwipeSource.ID, idx = 0),
                limit = SwipeSourceRegistry.REFILL_LIMIT,
                isUnmetered = true
            )
            if (batch?.sourceId == HomeFeedSwipeSource.ID) {
                storyFeedCursor = batch.nextCursor?.idx ?: 0L
            }
            // 跨会话已推去重（D7，默认开）：给过的不再推；remember 在入队时记
            val dedupEnabled = playerSettings.state.first().playback.crossSessionDedup
            val incoming = if (dedupEnabled) {
                seenStore.filterFresh(batch?.entries.orEmpty())
            } else {
                batch?.entries.orEmpty()
            }
            if (dedupEnabled) {
                seenStore.remember(incoming)
            }
            // 流到达（含失败传空 → Ready(0,0) 降级单视频）。锚点的标题/封面查找
            // （流内同视频条目优先）在状态机 seedArrived 内做，语义与旧版一致。
            swipeSession.seedArrived(incoming)
            // QoE（SW-06）：来源标签 = 命中的数据源 id（home-feed / story）
            SwipeQoeHub.record(SwipeQoeEventType.Enter, source = batch?.sourceId ?: "none")
            android.util.Log.i(
                "PortraitSwipe",
                "bootstrap: source=${batch?.sourceId} raw=${batch?.entries?.size} " +
                    "fresh=${incoming.size} seeded=${swipeSession.queue.value.size}"
            )
        }
    }

    /**
     * 队列补货：翻到倒数第 [com.naaammme.bbspace.core.swipe.SWIPE_EXTEND_THRESHOLD]
     * 页时由 pager 触发，接力首页推荐流（`fetchFeed(idx)` 分页，BiliPai StoryViewModel
     * 的 loadMoreStories 同款数据源）。详情全屏与 story 两种会话共用同一队列。
     *
     * ## 为什么是"重试凑满"而不是"拉一页就完"（对照 BiliPai，本轮最大体验差距）
     * 单页推荐流里**大量条目会被去重/UP 多样性挡掉**（实测一页 10 条只进 2~4 条）。
     * BiliPai 的做法是：竖屏模式最多拉 3 页、直到凑够 8 条"已验证"的候选
     * （`PortraitVideoPager.kt:1355-1406`）。只拉一页时队列很容易见底 ——
     * 用户滑到尽头就"没有下一个视频了"，这是当前最高频的体验断点。
     *
     * 判据抽成纯函数 [resolveSwipeRefillPlan]，可单测。
     * 失败允许下一次触发重试（游标不前进）；防抖：进行中忽略。
     */
    fun loadMoreSwipeQueue() {
        if (loadingMoreSwipe) return
        loadingMoreSwipe = true
        swipeSession.refillStarted()
        viewModelScope.launch {
            var attempts = 0
            var appended = 0
            val isUnmetered = networkTypeProvider.isUnmetered()
            val dedupEnabled = playerSettings.state.first().playback.crossSessionDedup
            // 补货只走首页推荐流（Light，蜂窝可用）：Registry 的蜂窝降级判据在这里生效；
            // story（Heavy）不进补货源集——它是起步兜底，不是接力源。
            val registry = SwipeSourceRegistry(listOf(HomeFeedSwipeSource(feedRepository)))
            while (shouldFetchNextRefillPage(attempts = attempts, appended = appended)) {
                val page = storyFeedCursor
                val batch = registry.nextBatch(
                    cursor = SwipeCursor(sourceId = HomeFeedSwipeSource.ID, idx = page),
                    limit = SwipeSourceRegistry.REFILL_LIMIT,
                    isUnmetered = isUnmetered
                ) ?: break
                storyFeedCursor = batch.nextCursor?.idx ?: break
                attempts += 1
                val fresh = if (dedupEnabled) {
                    seenStore.filterFresh(batch.entries)
                } else {
                    batch.entries
                }
                val before = swipeSession.queue.value.size
                swipeSession.mergeRelates(fresh)
                if (dedupEnabled) {
                    seenStore.remember(fresh)
                }
                val added = swipeSession.queue.value.size - before
                appended += added
                android.util.Log.i(
                    "PortraitSwipe",
                    "more: page=$page items=${batch.entries.size} fresh=${fresh.size} " +
                        "added=$added attempts=$attempts queue=${swipeSession.queue.value.size}"
                )
            }
            // 兜底（BiliPai 用 getRelatedVideos 补，这里零新接口）：
            // 推荐流拉满 3 页仍凑不满时，取队尾那一支的详情 relates 再补一轮 ——
            // 队尾正是用户下一个会滑到的位置，把它后面补厚才不会"滑到最后一条就没了"。
            if (shouldRunSwipeRefillFallback(appended = appended)) {
                val tail = selectSwipeRefillFallbackTarget(swipeSession.queue.value)
                if (tail != null) {
                    val added = appendSwipeRefillFallback(tail)
                    appended += added
                    android.util.Log.i(
                        "PortraitSwipe",
                        "more fallback: tail=${tail.legacyKey()} added=$added queue=${swipeSession.queue.value.size}"
                    )
                }
            }
            android.util.Log.i(
                "PortraitSwipe",
                "more done: attempts=$attempts appended=$appended queue=${swipeSession.queue.value.size}"
            )
            // 提示状态：补到东西就回到 Ready（不打扰），一条没补到就是"到尽头了"
            swipeSession.refillResult(appended = appended)
            SwipeQoeHub.record(SwipeQoeEventType.Refill, durationMs = appended.toLong())
            loadingMoreSwipe = false
        }
    }

    /**
     * 兜底补货：拉某一支的详情，把它自带的相关推荐并进队列。
     *
     * 详情会被 [VideoDetailRepository] 自己缓存，所以这次请求不算白花 ——
     * 用户真滑到队尾那一支时，详情/简介是直接命中缓存的。
     *
     * @return 实际净增的条数（全部被去重挡掉时是 0）。
     */
    private suspend fun appendSwipeRefillFallback(entry: SwipeEntry): Int {
        val params = entry.target.toPlayableParams()
        val result = runCatchingCancellable {
            detailRepository.fetchVideoDetail(ids = params.ids, src = params.src)
        }.getOrNull() ?: return 0
        val incoming = swipeEntriesFromRelates(result.detail.relates)
        if (incoming.isEmpty()) return 0
        val before = swipeSession.queue.value.size
        swipeSession.mergeRelates(incoming)
        return swipeSession.queue.value.size - before
    }

    /**
     * 相邻页预取（对照 BiliPai 的"拖动 0.25 偏移 / 停稳后 1~3 条"）。
     *
     * 只搬运**判据与触发时机**：预取走 [VideoPlayerRepository.preloadPlayUrl]，
     * 与正常开播共用同一份进程内 playurl LRU —— 于是"上滑之后画面来得快不快"取决于
     * 这一步有没有提前把地址取回来，而不是再等一个 gRPC 往返。
     * 同一次预取还会热**详情**（标题/UP/简介先落地，见 [prefetchEntry]）。
     *
     * - 条数由 [SwipePrefetchPolicy.prefetchCount] 决定（WiFi+开关 3 / WiFi 2 /
     *   蜂窝+开关 1 / 否则 0）；
     * - 同一支视频在一次会话里只预取一次（会话状态机的预取去重表，随 exit 清空），
     *   避免反复刷同一页时重复请求；
     * - **取消语义（SW-12）**：上一次请求若已失效（回滑 / 切网 / 队列收缩），
     *   本次按新口径重算（[SwipePrefetchPolicy.isPrefetchRequestStale]），不再按旧口径补发；
     * - 失败静默（预取是尽力而为）。
     */
    fun prefetchSwipeQueue(page: Int) {
        val anchor = swipeSession.anchor.value ?: return
        val pages = listOf(anchor) + swipeSession.queue.value
        viewModelScope.launch {
            val prefetchEnabled = playerSettings.state.first().playback.prefetchVideo
            val isUnmetered = networkTypeProvider.isUnmetered()
            // 本次请求签名：网络状态只读这一处，后续判据/计数/台账全部复用它
            // （守卫 cellularGateSkipsDetailWarmButNotPlayUrl 锁的就是"一次预取只读一次网络"）。
            val request = SwipePrefetchPolicy.PrefetchRequest(
                page = page,
                isUnmetered = isUnmetered,
                pagesSize = pages.size
            )
            // 取消判据（SW-12）：上一次的预取口径是否已失效——失效就按新口径重算
            // （页数收缩会让目标索引位移、切网会让预算口径翻转、回滑会让"向后预"落空）。
            val previous = lastPrefetchRequest
            val stale = previous != null && SwipePrefetchPolicy.isPrefetchRequestStale(
                previous = previous,
                currentPage = page,
                currentUnmetered = request.isUnmetered,
                pagesSizeNow = pages.size
            )
            if (stale) {
                SwipeQoeHub.recordCounter("prefetch_cancelled")
            }
            val count = SwipePrefetchPolicy.prefetchCount(
                prefetchEnabled = prefetchEnabled,
                isUnmetered = request.isUnmetered,
                available = pages.size - page - 1
            )
            if (count <= 0) return@launch
            lastPrefetchRequest = request
            SwipeQoeHub.record(SwipeQoeEventType.Prefetch, durationMs = count.toLong())
            // 蜂窝下跳过"详情级别"的重预热（判据与理由见 SwipePrefetchPolicy）。
            // 与 count 同一次读网络，不在循环里反复问系统 —— 一次滑动里结论不会变。
            val warmDetail = SwipePrefetchPolicy.shouldWarmDetail(isUnmetered)
            android.util.Log.i(
                "PortraitSwipe",
                "prefetch want=$count page=$page pages=${pages.size} prefetchEnabled=$prefetchEnabled " +
                    "unmetered=$isUnmetered detailWarm=$warmDetail stale=${stale}"
            )
            SwipePrefetchPolicy.targetPages(page, pages.size, count).forEach { index ->
                val entry = pages.getOrNull(index) ?: return@forEach
                prefetchEntry(entry, warmDetail = warmDetail)
            }
        }
    }

    /** 上一次真正发起的预取请求（SW-12 取消判据的输入；随会话 exit 清空）。 */
    private var lastPrefetchRequest: SwipePrefetchPolicy.PrefetchRequest? = null

    // ── 失败态（SW-13）：分类 + 重试策略 ────────────────────────────────────────
    /** 当前页的失败类型（null = 没有失败）；UI 的 veil 文案读它。 */
    private val _swipeErrorKind = MutableStateFlow<SwipeErrorPolicy.Kind?>(null)
    val swipeErrorKind: StateFlow<SwipeErrorPolicy.Kind?> = _swipeErrorKind.asStateFlow()

    /** 本页已经自动重试过几次（成功出帧/换页即清零）。 */
    private var swipeAutoRetryCount = 0

    /** 上一次手动重试的时刻（去抖；见 SwipeErrorPolicy.isManualRetryDebounced）。 */
    private var lastManualRetryMs: Long? = null

    init {
        // 引擎错误 → 会话失败态（分类 + QoE）+ 一次退避自动重试。
        // 为什么只在"会话仍在"时接：详情页的单视频失败有自己的错误面板，不走这里。
        viewModelScope.launch {
            videoState
                .map { it.error }
                .distinctUntilChanged()
                .collect { error ->
                    if (error == null) {
                        _swipeErrorKind.value = null
                        return@collect
                    }
                    if (swipeSession.anchor.value == null) return@collect
                    val ids = videoState.value.ids
                    // 分类只看稳定的错误串：RequestFailed 带 message（+ cause 的类名），
                    // NoPlayableStream 是"取到了但没有可播流"（取流类）。
                    val rawMessage = when (error) {
                        is com.naaammme.bbspace.core.model.PlaybackError.RequestFailed ->
                            listOfNotNull(error.message, error.cause?.javaClass?.simpleName).joinToString(" ")
                        is com.naaammme.bbspace.core.model.PlaybackError.NoPlayableStream ->
                            "no playable stream ${error.message}"
                    }
                    val kind = SwipeErrorPolicy.classify(
                        errorMessage = rawMessage,
                        networkAvailable = networkTypeProvider.isNetworkAvailable()
                    )
                    _swipeErrorKind.value = kind
                    swipeSession.playbackFailed(
                        id = SwipeId.of(ids.bvid, ids.aid),
                        retryable = true
                    )
                    SwipeQoeHub.record(SwipeQoeEventType.PlayError)
                    val delayMs = SwipeErrorPolicy.autoRetryDelayMs(swipeAutoRetryCount, kind)
                    android.util.Log.i(
                        "PortraitSwipe",
                        "playError: kind=$kind autoRetryIn=${delayMs ?: "no"} attempts=$swipeAutoRetryCount"
                    )
                    if (delayMs != null) {
                        swipeAutoRetryCount += 1
                        delay(delayMs)
                        retryCurrentPlaybackInternal(fromAuto = true)
                    }
                }
        }
        // 出帧即认为这一页好了：清失败类型与自动重试计数（新的一页重新开始计数）
        viewModelScope.launch {
            videoState
                .map { it.hasRenderedFirstFrame }
                .distinctUntilChanged()
                .collect { rendered ->
                    if (rendered) {
                        swipeAutoRetryCount = 0
                        _swipeErrorKind.value = null
                    }
                }
        }
    }

    private fun prefetchEntry(entry: SwipeEntry, warmDetail: Boolean) {
        val target = entry.target
        if (target.aid <= 0L || target.cid <= 0L) return
        val key = "${target.aid}#${target.cid}"
        if (!swipeSession.markPrefetched(key)) return
        viewModelScope.launch {
            val params = target.toPlayableParams()
            runCatchingCancellable {
                playbackVideoRepository.preloadPlayUrl(params.getResolveParams())
            }
            // 顺带把**详情**也热了：连播页左下角的标题/UP 直接取 `videoState.detail`，
            // 而详情是开播时才并发请求的 —— 不预热就会"翻过去先空一拍再补上标题"。
            // 预热后 `StreamPlaybackSessionImpl` 那条 `reuseDetail` 短路会命中，
            // 连"取详情"这次请求都省了（详情仓库自己有 LRU）。失败一律吞掉。
            // **蜂窝下不做**（warmDetail 由 SwipePrefetchPolicy.shouldWarmDetail 决定）：
            // 这是一整份 gRPC 详情响应，比 playurl 贵一个量级，换来的是"没滑到的那一页
            // 标题不空一拍"—— 上游 `a4b8c17f8` 对头部预热加的正是同一层 Wi-Fi 门控。
            if (warmDetail) {
                runCatchingCancellable {
                    detailRepository.fetchVideoDetail(ids = params.ids, src = params.src)
                }
            }
            android.util.Log.i("PortraitSwipe", "prefetched: key=$key")
        }
    }

    /**
     * 上下滑切换：**替换栈顶**而不是压栈（openTarget 的语义）——刷视频是同一
     * 浏览会话内的连续浏览，返回键应当退出详情而不是逐个回放刷过的视频
     * （与 B 站官方/BiliPai 的行为一致）。同条目重复调用直接忽略。
     */
    fun openSwipeTarget(entry: SwipeEntry) {
        val stack = _targetStack.value
        if (stack.isEmpty()) return
        val current = stack.last()
        // 同条目短路：**类型化身份**（SW-03，aid 优先、bvid 兜底语义在 SwipeId.matches
        // 内置，与 SwipeEntry.isCurrentVideo 同一判据）。relate 卡的 bvid 是取流后才在
        // ids 里被补齐的，纯 bvid 字符串比较会把"同一视频"误判成不同目标 → 白白重开
        // 一次播放（画面从头发起、进度丢失）。
        if (current is VideoTarget.Ugc) {
            val same = entry.id.matches(SwipeId.of(current.bvid, current.aid))
            if (same) return
        }
        android.util.Log.i(
            "PortraitSwipe",
            "open: key=${entry.legacyKey()} aid=${entry.target.aid} cid=${entry.target.cid} " +
                "bvid=${entry.bvid} from=${(current as? VideoTarget.Ugc)?.aid}"
        )
        _targetStack.value = stack.dropLast(1) + entry.target
        playbackController.openVideo(entry.target)
    }

    /**
     * 取流/解码失败后的重试（连播页面的"重试"按钮）。
     *
     * 走 `openVideo(当前目标)` 而不是 `openSwipeTarget(...)`：后者对"同一支视频"会短路
     * （那是**切页**语义，防重复开播），重试要的正是"再开一次"——
     * `openVideo` 内部会自增 `openId`，等于一次全新的取流尝试。
     */
    /**
     * 重试当前页（veil 里那个"重试"按钮，SW-13）：
     * - **去抖**：连点间隔 < 800ms 直接忽略（防连点把引擎打爆）；
     * - 状态回 `Ready`（状态机不变式 2），重开播放（`openVideo` 自增 openId = 全新一次取流）。
     */
    fun retryCurrentPlayback() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (SwipeErrorPolicy.isManualRetryDebounced(now, lastManualRetryMs)) {
            android.util.Log.i("PortraitSwipe", "retry ignored: debounced")
            return
        }
        lastManualRetryMs = now
        retryCurrentPlaybackInternal(fromAuto = false)
    }

    private fun retryCurrentPlaybackInternal(fromAuto: Boolean) {
        val target = _targetStack.value.lastOrNull() ?: return
        android.util.Log.i("PortraitSwipe", "retry: target=$target auto=$fromAuto")
        swipeSession.retry() // PlaybackFailed → Ready（状态机不变式 2）
        playbackController.openVideo(target)
    }

    /** 翻页落定回执（pager 的 currentPage collect 调用）：状态机记账 Ready.page。 */
    fun onSwipePageChanged(page: Int) {
        swipeSession.pageChanged(page)
        SwipeQoeHub.record(SwipeQoeEventType.Page)
    }

    init {
        // QoE 首帧事件（SW-06）：hasRenderedFirstFrame 的**上升沿**记一条 FirstFrame，
        // 与最近一条 Page/Enter 配对算页→帧耗时。放在 VM 而不是 pager：
        // pager 的粘滞等待写法被守卫锁死（swipeWaitsFirstFrameBehindLoadingVeil），
        // 不为埋点去动那三行。
        viewModelScope.launch {
            videoState
                .map { it.hasRenderedFirstFrame }
                .distinctUntilChanged()
                .collect { rendered ->
                    if (rendered) SwipeQoeHub.record(SwipeQoeEventType.FirstFrame)
                }
        }
    }

    /** 播完自动翻页回执（幂等键记账，随会话 exit 清空）。 */
    fun noteSwipeAutoAdvanced(key: String) {
        swipeSession.noteAutoAdvanced(key)
    }

    fun togglePlayPause() {
        if (videoState.value.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun switchQuality(quality: Int) {
        playbackController.switchVideoQuality(quality)
    }

    fun switchAudio(audioId: Int) {
        playbackController.switchVideoAudio(audioId)
    }

    fun updateVideoCdnMode(mode: VideoCdnMode) {
        viewModelScope.launch {
            playerSettings.setVideoCdnMode(mode)
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        playbackController.setSpeed(speed)
    }

    fun updateBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setBackgroundPlayback(enabled)
        }
    }

    fun updateSeamlessTransition(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setSeamlessTransition(enabled)
        }
    }

    /** 竖屏连播预加载开关（默认开）：见 [SwipePrefetchPolicy] 的条数矩阵。 */
    fun updatePrefetchVideo(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setPrefetchVideo(enabled)
        }
    }

    /** 竖屏连播「播完自动翻下一支」（默认开）：判据见 [shouldAutoAdvanceToNextSwipePage]。 */
    fun updateSwipeAutoAdvance(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setSwipeAutoAdvance(enabled)
        }
    }

    fun updateReportPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setReportPlayback(enabled)
        }
    }

    fun updateBufferProfile(profile: PlayerBufferProfile) {
        viewModelScope.launch {
            playerSettings.setBufferProfile(profile)
        }
    }

    fun updatePreferSoftwareDecode(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setPreferSoftwareDecode(enabled)
        }
    }

    fun updateDecoderFallback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setDecoderFallback(enabled)
        }
    }

    fun updateAutoRotateFullscreen(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setAutoRotateFullscreen(enabled)
        }
    }

    fun updateGestureSpeed(speed: Float) {
        viewModelScope.launch {
            playerSettings.setGestureSpeed(speed)
        }
    }

    fun updateDoubleTapLike(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setDoubleTapLike(enabled)
        }
    }

    /**
     * 单视频循环播放开关。
     *
     * 只写设置；实际作用到播放器由 [com.naaammme.bbspace.core.playback.StreamPlaybackSessionImpl]
     * 订阅 `playback.repeatMode` 完成 —— 这样「先开开关、再开视频」也能生效（订阅时会立刻发一次当前值）。
     */
    fun updateRepeatMode(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setRepeatMode(
                if (enabled) PlayerRepeatMode.One else PlayerRepeatMode.Off
            )
        }
    }

    fun updateDanmaku(config: DanmakuConfig) {
        viewModelScope.launch {
            playerSettings.setDanmaku(config)
        }
    }

    fun switchPage(cid: Long) {
        val pageTarget = currentTarget() as? VideoTarget.Ugc ?: return
        val ids = videoState.value.ids
        if (ids.cid == cid) return
        if (ids.aid <= 0L || cid <= 0L) return
        val nextTarget = VideoTarget.Ugc(
            aid = ids.aid,
            cid = cid,
            bvid = ids.bvid,
            src = pageTarget.src
        )
        _targetStack.value = _targetStack.value.dropLast(1) + nextTarget
        playbackController.openVideo(nextTarget)
    }

    fun switchEpisode(target: VideoTarget) {
        val cur = currentTarget() ?: return
        if (cur == target) return
        _targetStack.value = _targetStack.value.dropLast(1) + target
        playbackController.openVideo(target)
    }

    fun currentDownloadRequest(
        kind: VideoDownloadKind,
        videoQuality: Int,
        audioQuality: Int
    ): VideoDownloadRequest? {
        val state = videoState.value
        state.detail ?: return null
        currentTarget() ?: return null
        val ids = state.ids
        if (!ids.hasAny) return null
        val meta = buildDownloadMeta()
        return VideoDownloadRequest(
            biz = state.biz,
            aid = ids.aid,
            cid = ids.cid,
            bvid = ids.bvid,
            epId = ids.epId,
            seasonId = ids.seasonId,
            kind = kind,
            videoQuality = videoQuality,
            audioQuality = audioQuality,
            meta = meta
        )
    }

    private fun buildDownloadMeta(): VideoDownloadMeta {
        val detail = videoState.value.detail
        val cid = videoState.value.ids.cid.takeIf { it > 0L }
        val part = detail?.pages?.firstOrNull { it.cid == cid }
        val title = detail?.let {
            listOfNotNull(
                it.title.takeIf(String::isNotBlank),
                part?.part?.takeIf(String::isNotBlank)
            ).joinToString(" - ").takeIf(String::isNotBlank)
        }
        return VideoDownloadMeta(
            title = title,
            cover = detail?.cover,
            ownerUid = detail?.owner?.mid?.takeIf { it > 0L },
            ownerName = detail?.owner?.name?.takeIf(String::isNotBlank)
        )
    }

    // ===== 互动动作 =====

    /** 点赞/取消点赞 */
    fun toggleLike() = withInteraction { state, aid, interactionTarget ->
        val target = !(state.userState?.liked ?: false)
        // 本文件里包挂起调用的 runCatching 一律用 runCatchingCancellable：
        // 标准库版本会把 CancellationException 也吞成 Result.failure，于是
        // "用户退出页面导致请求被取消"会走 onFailure 分支 → 弹一条
        // "点赞失败/收藏失败"的假错误提示，并在已清理的 ViewModel 上继续写状态；
        // 而 tripleFallback 那类"失败就补发剩下两个请求"的分支更会在死掉的 scope 上
        // 继续发请求。取消就该向上传播，不能与真实失败混为一谈。
        runCatchingCancellable { actionRepository.setLike(aid, target) }.fold(
            onSuccess = { toast ->
                updateInteractionIfCurrent(interactionTarget) {
                    it.copy(
                        userState = (it.userState ?: VideoUserState()).copy(liked = target),
                        likeDelta = it.likeDelta + if (target) 1 else -1
                    )
                }
                _msg.tryEmit(toast.ifBlank { if (target) "点赞成功" else "已取消点赞" })
            },
            onFailure = { emitActionError(it, "点赞失败") }
        )
    }

    /** 投币，multiply 只能为 1 或 2，withLike 表示同时点赞 */
    fun submitCoin(multiply: Int, withLike: Boolean) = withInteraction { state, aid, interactionTarget ->
        val coinCount = state.userState?.coinCount ?: 0
        if (coinCount + multiply > 2) {
            _msg.tryEmit("达到投币上限啦~")
            return@withInteraction
        }
        val likedBefore = state.userState?.liked ?: false
        runCatchingCancellable { actionRepository.addCoin(aid, multiply, withLike) }.fold(
            onSuccess = {
                updateInteractionIfCurrent(interactionTarget) {
                    it.copy(
                        userState = (it.userState ?: VideoUserState()).copy(
                            coinCount = coinCount + multiply,
                            liked = likedBefore || withLike
                        ),
                        coinDelta = it.coinDelta + multiply,
                        likeDelta = it.likeDelta + (if (withLike && !likedBefore) 1 else 0)
                    )
                }
                _msg.tryEmit("投币成功")
                // 余额真的变了，显式刷新（见 [coinBalance] 的注释：不再靠每次重组读盘）
                refreshCoinBalance()
            },
            onFailure = { emitActionError(it, "投币失败") }
        )
    }

    /**
     * 一键三连：Web 端点一次完成；失败时降级为逐项执行，单项失败不阻断其余项
     */
    fun triple() = withInteraction { _, aid, interactionTarget ->
        val message = try {
            val result = actionRepository.triple(aid, videoState.value.ids.bvid)
            markTripleResult(like = result.like, coin = result.coin, fav = result.fav, target = interactionTarget)
            "三连成功"
        } catch (error: Exception) {
            // 必须把降级结果赋值给 message：降级方法返回的是给用户看的提示文案
            // （"已完成三连" / "点赞成功，投币失败"…）。此前写成裸调用表达式，
            // catch 分支的类型被推导为 Unit 并装箱成 String("kotlin.Unit")，
            // 于是降级成功也会弹 "kotlin.Unit"，而 tripleFallback 的文案被丢弃。
            if (error is CancellationException) throw error
            tripleFallback(aid, interactionTarget)
        }
        _msg.tryEmit(message)
    }

    /**
     * 准备收藏夹面板数据，**返回是否真的可以打开**。
     *
     * 为什么要有返回值：调用方 `VideoDetailPage.handleAction` 原先是无条件两行
     * ```
     * viewModel.openFavSheet()
     * sheet = DetailSheet.Fav
     * ```
     * 而未登录分支只弹了 toast 就早退、**没有动 _favSheet**，于是 sheet 照样弹出来，
     * 渲染的是默认空状态 —— 用户看到的是"暂无收藏夹，新建一个吧"，
     * 而"请先登录"那条 toast 被盖在下面。把"能不能打开"作为返回值交回调用方，
     * 是唯一能让两边不再各说各话的写法。
     */
    fun openFavSheet(): Boolean {
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            return false
        }
        _favSheet.value = FavFolderSheetState(loading = true)
        viewModelScope.launch {
            // `favRidType()` 对不支持收藏的 biz 会 **throw**，必须留在 runCatching 里面。
            // 它在 runCatching 之外时，viewModelScope.launch 没有异常处理器 →
            // 未捕获协程异常 → 直接崩溃。openFavSheet() 入口只挡了"未登录"，
            // 没挡 aid<=0（对比 submitFav 有 `if (aid <= 0L) return`），
            // 所以课程/SCG 这类内容点收藏是会走到的。
            runCatchingCancellable {
                val (rid, type) = favRidType()
                favoriteRepository.fetchCreatedFoldersWithState(rid, type)
            }.fold(
                onSuccess = { folders ->
                    val favedIds = folders.filter { it.favState == 1 }.map { it.fid }.toSet()
                    _favSheet.value = FavFolderSheetState(
                        folders = folders,
                        initialFavedIds = favedIds,
                        selectedIds = favedIds
                    )
                },
                onFailure = { error ->
                    // 同一个理由：拉取失败时也不要留着一个空面板。置 dismissRequest
                    // 让既有的收起链路把它关掉，错误提示由 emitActionError 负责。
                    _favSheet.value = FavFolderSheetState(dismissRequest = true)
                    emitActionError(error, "获取收藏夹失败")
                }
            )
        }
        return true
    }

    fun toggleFavFolder(fid: Long) {
        _favSheet.update {
            it.copy(
                selectedIds = if (fid in it.selectedIds) it.selectedIds - fid else it.selectedIds + fid
            )
        }
    }

    /** 在收藏面板内新建收藏夹，成功后刷新面板列表 */
    fun createFolderInSheet(title: String, privacy: Int) {
        viewModelScope.launch {
            runCatchingCancellable { favoriteRepository.createFolder(title, privacy) }.fold(
                onSuccess = { openFavSheet() },
                onFailure = { error -> emitActionError(error, "新建收藏夹失败") }
            )
        }
    }

    fun submitFav() {
        val sheet = _favSheet.value
        if (sheet.submitting || sheet.loading) return
        val aid = videoState.value.ids.aid
        if (aid <= 0L) return
        val addIds = (sheet.selectedIds - sheet.initialFavedIds).toList()
        val delIds = (sheet.initialFavedIds - sheet.selectedIds).toList()
        if (addIds.isEmpty() && delIds.isEmpty()) {
            _favSheet.update { it.copy(dismissRequest = true) }
            return
        }
        _favSheet.update { it.copy(submitting = true) }
        val interactionTarget = interactionTargetOf()
        viewModelScope.launch {
            // 与 openFavSheet 同理：favRidType() 会 throw，必须在 runCatching 里面。
            // 这里虽然上游有 `if (aid <= 0L) return` 挡着、当前不可达，但依赖调用方
            // 的守卫来保证一个 throw 型工具函数不崩，是会在哪天改动中失效的约定。
            runCatchingCancellable {
                val (rid, type) = favRidType()
                favoriteRepository.dealFav(rid, type, addIds, delIds)
            }.fold(
                onSuccess = {
                    // **用提交前的快照算结果，不是重读当前 state**（本轮复核过，
                    // 审查报告建议的"`onSuccess` 内重读 `_favSheet.value`"是错的）：
                    // 服务端应用的是 addIds/delIds，而它们正是由这份快照算出来的，
                    // 所以"服务器现在的状态"就等于 `sheet.selectedIds`。
                    // 若改读实时 state，用户在请求期间改了勾选，就会把**从未发出去**的
                    // 选择当成已生效去更新收藏态与计数 —— 那才是真 bug。
                    val favedNow = sheet.selectedIds.isNotEmpty()
                    val favedBefore = sheet.initialFavedIds.isNotEmpty()
                    updateInteractionIfCurrent(interactionTarget) {
                        it.copy(
                            userState = (it.userState ?: VideoUserState()).copy(faved = favedNow),
                            favDelta = it.favDelta + when {
                                favedNow && !favedBefore -> 1L
                                !favedNow && favedBefore -> -1L
                                else -> 0L
                            }
                        )
                    }
                    _favSheet.update { it.copy(submitting = false, dismissRequest = true) }
                    _msg.tryEmit(if (favedNow) "收藏成功" else "已取消收藏")
                },
                onFailure = { error ->
                    _favSheet.update { it.copy(submitting = false) }
                    emitActionError(error, "收藏失败")
                }
            )
        }
    }

    fun consumeFavDismiss() {
        if (_favSheet.value.dismissRequest) {
            _favSheet.update { it.copy(dismissRequest = false) }
        }
    }

    /**
     * 硬币余额（来自缓存的账号信息，可能为空）。
     *
     * **必须是 StateFlow，不能是函数** —— 原先写成
     * `fun coinBalance(): Double? = authStore.getUserInfo()?.coins`，
     * 并被当作 composable 实参调用（`VideoDetailPage` 里 `coinBalance = viewModel.coinBalance()`）。
     * 而 `getUserInfo()` 走 SharedPreferences（进程内首次访问该文件会**同步读盘**）
     * 并解析 JSONObject，于是每次 `VideoDetailPage` 重组都在 UI 线程做一遍磁盘读。
     * 与上一轮修掉的 `UserViewModel` 是同一类问题（那次 StrictMode 实测 195ms）。
     *
     * 改成 StateFlow 后顺带解决了"什么时候刷新"：投币成功点显式 refresh，
     * 而不是靠每次重组都重读一遍磁盘来碰运气。
     */
    private val _coinBalance = MutableStateFlow<Double?>(null)
    val coinBalance: StateFlow<Double?> = _coinBalance.asStateFlow()

    private fun refreshCoinBalance() {
        viewModelScope.launch {
            _coinBalance.value = withContext(Dispatchers.IO) { authStore.getUserInfo()?.coins }
        }
    }

    init {
        refreshCoinBalance()
    }

    /** 互动写回的目标快照（审查 D3）：切视频/分P/选集后，迟到响应据此丢弃。 */
    private data class InteractionTarget(val aid: Long, val cid: Long, val epId: Long)

    private fun interactionTargetOf(): InteractionTarget? {
        val ids = videoState.value.ids
        if (ids.aid <= 0L) return null
        return InteractionTarget(aid = ids.aid, cid = ids.cid, epId = ids.epId)
    }

    /** 目标守卫写回：目标已变化（含分P/选集切换）时静默丢弃本次乐观结果。 */
    private fun updateInteractionIfCurrent(
        target: InteractionTarget?,
        transform: (VideoInteractionState) -> VideoInteractionState
    ) {
        if (target == null) {
            _interaction.update(transform)
            return
        }
        val ids = videoState.value.ids
        if (ids.aid != target.aid || ids.cid != target.cid || ids.epId != target.epId) return
        _interaction.update(transform)
    }

    private fun withInteraction(action: suspend (VideoInteractionState, Long, InteractionTarget) -> Unit) {
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            return
        }
        // 目标快照：挂起期间用户可能切视频/分P/选集（ids 变化会触发互动区整体重置），
        // 迟到的响应必须凭这份快照判断归属，否则 liked/delta 会串写到新目标上
        val interactionTarget = interactionTargetOf() ?: return
        val aid = interactionTarget.aid
        if (_interaction.value.busy) {
            _msg.tryEmit("操作处理中，请稍候")
            return
        }
        _interaction.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                action(_interaction.value, aid, interactionTarget)
            } finally {
                _interaction.update { it.copy(busy = false) }
            }
        }
    }

    private fun loggedIn(): Boolean =
        authStore.mid > 0L && authStore.accessToken.isNotBlank()

    /**
     * 收藏资源 (rid, type)：UGC=aid/2，PGC=epId/24，与 PiliPlus getFavRidType 一致；
     * 其余 biz（课程等）暂不支持收藏
     */
    private fun favRidType(): Pair<Long, Int> {
        val state = videoState.value
        return when (state.biz) {
            PlayBiz.PGC -> state.ids.epId.takeIf { it > 0L }?.let { it to 24 }
            else -> state.ids.aid.takeIf { it > 0L }?.let { it to 2 }
        } ?: throw Exception("该内容暂不支持收藏")
    }

    private fun emitActionError(error: Throwable, fallback: String) {
        _msg.tryEmit(error.message?.takeIf(String::isNotBlank) ?: fallback)
    }

    private fun markTripleResult(like: Boolean, coin: Boolean, fav: Boolean, target: InteractionTarget? = null) {
        updateInteractionIfCurrent(target) {
            val user = it.userState ?: VideoUserState()
            it.copy(
                userState = user.copy(
                    liked = user.liked || like,
                    coinCount = if (coin) (user.coinCount + 1).coerceAtMost(2) else user.coinCount,
                    faved = user.faved || fav
                ),
                likeDelta = it.likeDelta + (if (like && !user.liked) 1L else 0L),
                coinDelta = it.coinDelta + (if (coin && user.coinCount == 0) 1L else 0L),
                favDelta = it.favDelta + (if (fav && !user.faved) 1L else 0L)
            )
        }
    }

    /** Web 三连失败后的降级：逐项执行，单项失败不阻断其余项 */
    private suspend fun tripleFallback(aid: Long, interactionTarget: InteractionTarget): String {
        val state = _interaction.value
        val alreadyAll = (state.userState?.liked ?: false) &&
            (state.userState?.coinCount ?: 0) > 0 &&
            (state.userState?.faved ?: false)
        if (alreadyAll) return "已完成三连"

        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (!(state.userState?.liked ?: false)) {
            runCatchingCancellable { actionRepository.setLike(aid, true) }
                .onSuccess {
                    done += "点赞"
                    markTripleResult(like = true, coin = false, fav = false, target = interactionTarget)
                }
                .onFailure { failed += "点赞" }
        }
        if ((state.userState?.coinCount ?: 0) == 0) {
            runCatchingCancellable { actionRepository.addCoin(aid, 1, selectLike = false) }
                .onSuccess {
                    done += "投币"
                    markTripleResult(like = false, coin = true, fav = false)
                }
                .onFailure { failed += "投币" }
        }
        if (!(state.userState?.faved ?: false)) {
            runCatchingCancellable {
                val (rid, type) = favRidType()
                val folders = favoriteRepository.fetchCreatedFoldersWithState(rid, type)
                val defaultFolderId = folders.firstOrNull()?.fid
                    ?: throw Exception("没有可用的收藏夹")
                favoriteRepository.dealFav(rid, type, addIds = listOf(defaultFolderId), delIds = emptyList())
            }
                .onSuccess {
                    done += "收藏"
                    markTripleResult(like = false, coin = false, fav = true)
                }
                .onFailure { failed += "收藏" }
        }
        return when {
            failed.isEmpty() && done.isNotEmpty() -> "三连成功"
            done.isEmpty() -> "三连失败，请稍后重试"
            else -> "${done.joinToString("、")}成功，${failed.joinToString("、")}失败"
        }
    }

    private fun currentTarget(): VideoTarget? {
        return _targetStack.value.lastOrNull()
    }

    /**
     * VM 真正销毁时补记 QoE 的 Exit（真机实测发现：从 story 模式返回/还原时
     * `clearSwipeSession` 不会被调用——那条路径只在离开全屏时走——于是 Exit 恒为 0）。
     * 只在"会话仍在"时补记：正常退出全屏已记过一次（exit() 会把 anchor 清空，
     * 此处 snapshot() 即为 null，不会重复计数）；配置变更不触发 onCleared，不误记。
     */
    override fun onCleared() {
        if (swipeSession.snapshot() != null) {
            SwipeQoeHub.record(SwipeQoeEventType.Exit)
        }
        super.onCleared()
    }

    private companion object {
        /** 会话快照在 SavedStateHandle 里的键（进程重建恢复，SW-02）。 */
        const val KEY_SWIPE_SESSION_SNAPSHOT = "swipe.session.snapshot"
    }
}
