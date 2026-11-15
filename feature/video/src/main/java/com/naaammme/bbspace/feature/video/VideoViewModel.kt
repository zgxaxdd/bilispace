package com.naaammme.bbspace.feature.video

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.error.UserFacingError
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.common.network.NetworkTypeProvider
import com.naaammme.bbspace.core.common.relation.UpBlocker
import com.naaammme.bbspace.core.settings.blocked.BlockedUpStore
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.feed.FeedCursor
import com.naaammme.bbspace.core.feed.FeedDislikeRepository
import com.naaammme.bbspace.core.feed.FeedRepository
import com.naaammme.bbspace.core.feed.StoryFeedRepository
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.settings.FeedApiType
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
import com.naaammme.bbspace.core.model.ThreePointReason
import com.naaammme.bbspace.core.model.ThreePointReasonKind
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
import com.naaammme.bbspace.core.swipe.source.SwipeSourceRegistry
import com.naaammme.bbspace.core.swipe.swipeEntriesFromRelates
import com.naaammme.bbspace.feature.video.player.PlaybackPageOwnership
import com.naaammme.bbspace.feature.video.player.playbackPageTargetOf
import com.naaammme.bbspace.feature.video.player.shouldReclaimVideoPlaybackSession
import com.naaammme.bbspace.feature.video.swipesource.HomeFeedSwipeSource
import com.naaammme.bbspace.feature.video.swipesource.StoryFeedSwipeSource
import com.naaammme.bbspace.feature.video.swipesource.SwipeFeedCursorPolicy
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
 * 详情页互动区状态：计数 = 服务器值 + 本地增量，userState 为乐观更新后的结果。
 *
 * ⚠️ **`userState == null` 表示「未知」，不是「未点赞 / 0 币 / 未收藏」**（A16）。
 *
 * `req_user` 只在登录态下才出现在详情回包里（[com.naaammme.bbspace.core.video.VideoDetailRepository]
 * 的 `mapUserState` 在 `!reply.hasReqUser()` 时返回 null）。
 *
 * **读侧**：把未知折叠成默认值会在**未登录**时把三个按钮画成「未选中」。⚠️ 注意口径
 * （已核对 `VideoDetailRepository.mapReply`）：`stat` 与 `userState` 是在**同一个**
 * `VideoDetail(...)` 构造里一起赋值的（`:217` / `:225`），所以不存在「stat 先到、
 * userState 后到」的中间帧——不要把这条读侧问题描述成"首帧翻转"。
 *
 * **写侧（真正严重的那一处）**：点赞是**取反**操作，`!(liked ?: false)` 在未知时恒为
 * true，于是「状态还没确认时点一下赞」会真的发出 `setLike(true)`。若服务端本来已赞，
 * 用户这一下本意是取消，实际却变成重复点赞。这条与登录与否无关，只要 `req_user` 缺席
 * 就可能命中（未登录、或服务端未回该字段）。
 *
 * ⇒ 纪律：读侧走下面这几个**可空**访问器渲染中性态；写侧一律先过 [likeStatusKnown]。
 * 上游对应实现见 BiliPai `a614d2eba`（`VideoEngagementUiState.likeStatusKnown`）。
 */
data class VideoInteractionState(
    val stat: VideoStat? = null,
    val userState: VideoUserState? = null,
    val likeDelta: Long = 0,
    val coinDelta: Long = 0,
    val favDelta: Long = 0,
    val busy: Boolean = false,
    /**
     * **点赞位**是否已由服务器确认（详情回包的 `req_user` 到达过，或点赞/三连写成功过）。
     *
     * 为什么只针对点赞位、而不是笼统的「状态已知」：点赞是**取反**操作，它的写目标是
     * `!liked`，所以「未知」在写侧无法折算成任何安全默认值。投币/收藏都是**单向**
     * 写（`addCoin` 只加、`dealFav` 用显式 addIds/delIds），未知时最坏是"多加一币"，
     * 且上游 `doCoin` 也只门了 `alsoLike && !likeStatusKnown` —— 保持一致。
     *
     * **必须是显式字段，不能写成 `userState != null` 的派生属性**：本文件有若干乐观
     * 写回点写作 `(it.userState ?: VideoUserState()).copy(...)` —— 一旦在未知态下走到，
     * 就会**凭空造出一个 `userState`**，派生写法会随之把门打开，A16 的写回缺陷就从
     * 另一条路径复现。显式字段只在真值源落地处赋值，乐观写回一律 `copy` 透传，
     * 无法被伪造。
     *
     * 上游对应物：BiliPai `a614d2eba` 的 `VideoEngagementUiState.likeStatusKnown`。
     */
    val likeStatusKnown: Boolean = false
) {
    /**
     * 点赞态：`true` 已赞 / `false` 未赞 / **`null` 未确认**。
     *
     * 为什么由 [likeStatusKnown] 把门、而不是直接回 `userState?.liked`：收藏/投币的
     * 乐观写回点会把 `userState` 从 null **造成**非 null（`(it.userState ?: VideoUserState())`），
     * 那样造出来的 `liked` 恒为 false，读侧就会把「未确认」画成「未点赞」——正是 A16
     * 要消掉的那种错觉。让同一个字段同时管住读与写，才不会有第二条缝。
     */
    val liked: Boolean? get() = if (likeStatusKnown) userState?.liked else null

    /** 投币数；`null` = 未知。投币是单向写，未确认时读侧仍可显示。 */
    val coinCount: Int? get() = userState?.coinCount

    /** 收藏态；`null` = 未知。收藏是显式 addIds/delIds，未确认时读侧仍可显示。 */
    val faved: Boolean? get() = userState?.faved
}

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
    val userState: VideoUserState?,
    /** A16：点赞位是否已确认。**必须进键**，见 init 里的注释。 */
    val likeStatusKnown: Boolean
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
    /** SW-24：会话内「不感兴趣」的负反馈通道（与首页同一套仓储）。 */
    private val feedDislikeRepository: FeedDislikeRepository,
    /** 跨会话「已推记忆」（SW-05）：推荐流/story 流给过的视频不再进新会话。 */
    private val seenStore: SwipeSeenStore,
    /** PT-06：刷视频「拉黑该 UP」——本地名单 + 既有 gRPC 拉黑能力（经 UpBlocker 接口）。 */
    private val blockedUpStore: BlockedUpStore,
    private val upBlocker: UpBlocker,
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

    /**
     * 「省电模式」：视频节点用 SurfaceView（见 [AppSettings.playerSurfaceView]）。
     *
     * ⚠️ 类型是 `Boolean?`，`null` = **设置还没从 DataStore 读出来**（首帧）。
     * 为什么不能给个 false 兜底：那样播放页会先按 TextureView 建一个 PlayerView 并绑定播放器，
     * 等设置到达再建第二个（SurfaceView）—— 真机实测**第二次创建的 surface 不会被创建**，
     * 症状就是"首次进入黑屏、全屏往返重建后才正常"（2026-10-07，`PlayerSurface` 诊断日志：
     * useSurfaceView 先 false 后 true，且 SurfaceHolder 从未 created）。
     * ⇒ 播放页必须等这个值非空后再建视图（只创建一次）。
     */
    val playerSurfaceView: StateFlow<Boolean?> = playerSettings.playerSurfaceView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                    //
                    // A16：`likeStatusKnown` 也必须进键。`userState` 已经是键的一部分，看似
                    // 冗余，但它与 `likeStatusKnown` **不同源**——乐观写回点会把 `userState`
                    // 从 null 造成非 null（`(it.userState ?: VideoUserState())`），那种变化
                    // 不该被当成"真值到达"。真值到达（`req_user` 落地）是
                    // `userState: null → 非 null` 且 `likeStatusKnown: false → true`，
                    // 少了这一位，这次跳变就可能被 `distinctUntilChanged` 吃掉。
                    InteractionResetKey(
                        aid = it.ids.aid,
                        cid = it.ids.cid,
                        epId = it.ids.epId,
                        stat = it.detail?.stat,
                        userState = it.detail?.userState,
                        likeStatusKnown = it.detail?.userState != null
                    )
                }
                .distinctUntilChanged()
                .collect { key ->
                    _interaction.value = VideoInteractionState(
                        stat = key.stat,
                        userState = key.userState,
                        likeStatusKnown = key.likeStatusKnown
                    )
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
        // #892 上游 shouldReclaimVideoPlaybackSession 的本仓用法（B 级适配）：
        // 只有"会话确实不是这一页"时才重新认领。此前无条件 openVideo —— 会话已经
        // 停在父页时（子页打开失败 / 已被打断）会白白重走一次取流，画面回到
        // preparing，用户读作"从相关推荐返回后卡一下"。
        //
        // 被盖住的页面不许抢会话的上游判据（isVisible）在这里恒为 true：本方法
        // 只由当前可见页的返回键调用；"被盖住"的语义在本仓由页栈本身表达。
        val activePlayer = player.value
        val state = videoState.value
        val pageIds = nextTarget.toPlayableParams().ids
        val shouldReclaim = activePlayer == null || shouldReclaimVideoPlaybackSession(
            isVisible = true,
            isSessionActive = state.playbackSource != null || state.isPreparing,
            currentPlayer = activePlayer,
            // bvid 统一用 orEmpty() 比较：上游这里传可空值，但本仓 ids.bvid 在 PGC/
            // 兜底路径上会是 null，而 target 侧是 ""，直接比会把"同一支视频"误判成不同。
            currentBvid = state.ids.bvid.orEmpty(),
            currentCid = state.ids.cid,
            pagePlayer = activePlayer,
            pageBvid = pageIds.bvid.orEmpty(),
            pageCid = pageIds.cid,
        )
        if (shouldReclaim) playbackController.openVideo(nextTarget)
        return true
    }

    /**
     * 页面生命周期对播放器的引用计数（#892 / 上游 `40690bd19` 的
     * `PlaybackPageOwnership` 移植）。
     *
     * 本仓播放器由进程级 `StreamPlaybackSession` 单例持有，页面**没有**释放权
     * （见 `PlaybackPageOwnership` 的差异表），因此这里记的不是"谁负责释放"，
     * 而是"**哪些页面实例仍在引用这台播放器**"：已销毁页面的迟到协程不许再改写
     * 会话身份 —— 与上游 `syncCurrentVideoInfo(expectedPlayer)` 是同一条纪律。
     */
    private val pageOwnership = PlaybackPageOwnership()

    fun retainPlaybackPage(player: Player) {
        pageOwnership.retain(player)
    }

    fun releasePlaybackPage(player: Player) {
        pageOwnership.release(player)
    }

    fun isPlaybackPageRetained(player: Player): Boolean = pageOwnership.isRetained(player)

    /**
     * 可见页面**认领会话身份**（#892 用户症状的本仓落点）。
     *
     * ## 病灶（本仓形态，与上游不同构但同一症状）
     * 无缝路径打开详情时 `AppNavHost.openVideoRoute` 只调
     * `PlaybackHostViewModel.warmOpen(target)`（直接对进程级 session `openVideo`），
     * **不经过**本 VM 的 `openRoot` —— 于是 MainActivity 的这个 VM 页内导航栈恒为空。
     * 从胶囊/覆盖层展开播放器时（会话仍在播某支视频），页面没有"页身份"：
     * 1. 点相关推荐 → `openTarget` 走 `current == null` 分支，栈变成 `[子视频]`，
     *    父视频**没有被记进栈** ⇒ 返回时 `popPage()` 返回 false，直接把整个播放器
     *    关掉（用户读到"原视频没了 / 返回后卡死"）；
     * 2. 全屏上下滑 → `openSwipeTarget` 的"栈非空"判据直接 early-return ⇒ 滑了没反应。
     *
     * ## 修法
     * 页面首次组合时，若栈为空且会话里确有视频，就把**会话身份认领为页身份**
     * （上游"可见页面重新认领会话"的同一不变量；本仓认领的是页身份，因为播放器
     * 本来就只有一台）。
     *
     * **只在栈为空时动作**：栈非空说明页面自己知道在播什么，晚到的认领会覆盖
     * 用户刚建立的导航关系（`popPage` 的父页必须留在栈里）。
     * **只在本页仍持有播放器时动作**：页面已销毁时不许改写会话身份。
     */
    fun adoptPlaybackSessionPage() {
        if (_targetStack.value.isNotEmpty()) return
        val activePlayer = player.value ?: return
        if (!pageOwnership.isRetained(activePlayer)) return
        val state = videoState.value
        val pageTarget = playbackPageTargetOf(biz = state.biz, ids = state.ids) ?: return
        _targetStack.value = listOf(pageTarget)
        DiagnosticLog.i(
            "PlaybackPage",
            "adopt: aid=${state.ids.aid} cid=${state.ids.cid} biz=${state.biz.name}"
        )
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
        DiagnosticLog.i(
            "PortraitSwipe",
            "session: anchor=$bvid relates=${detail.relates.size} queue=${seedQueue.size} " +
                "bvidFilled=${seedQueue.count { it.bvid.isNotEmpty() }}"
        )
    }

    /** 退出全屏时清会话：下次进入全屏重新锚定当时的视频（见 VideoScreen 的接线的判据）。 */
    fun clearSwipeSession() {
        swipeSession.exit()
        lastPrefetchRequest = null
        lastDisliked = null
        _swipeDislikeUndoable.value = false
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

    /**
     * 刷视频接力的**来源自持游标**（SX-04 / 决策 D-1）。
     *
     * 与首页同一套 [FeedCursor]：app 协议推进 [FeedCursor.appIdx]、web 协议推进
     * [FeedCursor.webPage]；到底走哪一路由 [FeedRepository.fetchFeed] 按设置里的
     * **推荐来源**分派，推进规则收在 `SwipeFeedCursorPolicy`（纯函数、可纯 JVM 单测）。
     * 换来源时**必须整体重置**——两套分页空间不能混用（否则重复 / 跳页）。
     */
    private var swipeFeedCursor = FeedCursor.Initial

    /** 推荐来源的最近一次观察值（SX-04）：只用于"换来源 → 重置接力游标"的变化检测。 */
    private var swipeFeedSource = FeedApiType.MOBILE

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
                DiagnosticLog.i(
                    "PortraitSwipe",
                    "restore: page=${snap.page} queue=${snap.queue.size} via=${snap.via}"
                )
            }
        // SX-04（决策 D-1）：刷视频接力跟随"推荐来源"设置。换来源时必须**整体重置**接力游标
        // —— app 与 web 的翻页语义不同（见 FeedCursor 的 KDoc），旧游标在新档位下会重复/跳页。
        // 写法与 HomeViewModel 对齐：首次发射 = 当前落盘值，不触发多余的重置与刷新。
        viewModelScope.launch {
            var firstEmission = true
            playerSettings.feedApiType.collect { source ->
                if (firstEmission) {
                    firstEmission = false
                    swipeFeedSource = source
                    return@collect
                }
                if (source == swipeFeedSource) return@collect
                swipeFeedSource = source
                swipeFeedCursor = FeedCursor.Initial
                DiagnosticLog.i("PortraitSwipe", "feedSource: switch=$source cursor=reset")
            }
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
            // 起步 = 新会话 = 游标整体重置（app / web 两套分页空间都从头开始，SX-04）。
            swipeFeedCursor = FeedCursor.Initial
            val batch = registry.nextBatch(
                cursor = SwipeFeedCursorPolicy.initial(HomeFeedSwipeSource.ID),
                limit = SwipeSourceRegistry.REFILL_LIMIT,
                isUnmetered = true
            )
            if (batch?.sourceId == HomeFeedSwipeSource.ID) {
                swipeFeedCursor = SwipeFeedCursorPolicy.toFeedCursorOrInitial(batch.nextCursor)
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
            DiagnosticLog.i(
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
            try {
                val isUnmetered = networkTypeProvider.isUnmetered()
                val dedupEnabled = playerSettings.state.first().playback.crossSessionDedup
                // 补货只走首页推荐流（Light，蜂窝可用）：Registry 的蜂窝降级判据在这里生效；
                // story（Heavy）不进补货源集——它是起步兜底，不是接力源。
                val registry = SwipeSourceRegistry(listOf(HomeFeedSwipeSource(feedRepository)))
                while (shouldFetchNextRefillPage(attempts = attempts, appended = appended)) {
                    val page = swipeFeedCursor
                    val batch = registry.nextBatch(
                        cursor = SwipeFeedCursorPolicy.toSwipeCursor(page, HomeFeedSwipeSource.ID),
                        limit = SwipeSourceRegistry.REFILL_LIMIT,
                        isUnmetered = isUnmetered
                    ) ?: break
                    val next = batch.nextCursor ?: break
                    swipeFeedCursor = SwipeFeedCursorPolicy.toFeedCursor(next)
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
                    DiagnosticLog.i(
                        "PortraitSwipe",
                        // SX-04：把两套游标都打出来，真机核对"游标单调递增、不反复拉同一页"
                        // （L4-122 的取证口径：app/idx 与 web/page 各自递增）。
                        "more: page=${page.appIdx}/${page.webPage} items=${batch.entries.size} fresh=${fresh.size} " +
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
                        DiagnosticLog.i(
                            "PortraitSwipe",
                            "more fallback: tail=${tail.legacyKey()} added=$added queue=${swipeSession.queue.value.size}"
                        )
                    }
                }
                DiagnosticLog.i(
                    "PortraitSwipe",
                    "more done: attempts=$attempts appended=$appended queue=${swipeSession.queue.value.size}"
                )
                // 提示状态：补到东西就回到 Ready（不打扰），一条没补到就是"到尽头了"
                swipeSession.refillResult(appended = appended)
                SwipeQoeHub.record(SwipeQoeEventType.Refill, durationMs = appended.toLong())
            } catch (e: CancellationException) {
                // 取消不是失败：VM 作用域被取消时原样上抛（同仓口径）。
                throw e
            } catch (e: Exception) {
                // F-8：原先这里没有 try/catch，复位写在正常路径末尾 —— 任何非取消异常都会让
                // ①补货永久失效（下次进来被 `if (loadingMoreSwipe) return` 直接挡掉）
                // ②会话状态永久停在 Extending（队尾常驻"加载中"）③异常直接冒到全局未捕获处理器。
                // 能触发的不是网络失败（取流侧 runCatchingCancellable 兜住了），而是
                // DataStore/binder 一类低概率异常。
                Logger.e(TAG, e) { "刷视频补货失败 appended=$appended" }
                swipeSession.refillResult(appended = 0)
            } finally {
                // 唯一复位点：必须在 finally
                loadingMoreSwipe = false
            }
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
        // D11：页序拼法只有一处（`SwipeSessionController.pages()`）。锚点未发布时它返回
        // 空列表 —— 下面 `available = pages.size - page - 1` 自然为负、`prefetchCount` 返 0，
        // 与旧写法 `val anchor = ... ?: return` 同拍。
        val pages = swipeSession.pages()
        if (pages.isEmpty()) return
        // D6：**先做范围校验再起协程**。此前这里不校验 `page`，而调用点是
        // `onPrefetch(pagerState.currentPage)` 与 `dragTargetPage(...)` —— pager 在队列收缩
        //（`SwipeQueueBoundPolicy` 截尾、「不感兴趣」退队）的那一拍仍可能报出**越界页号**，
        // 于是 `available` 变负、`targetPages` 返回空，一次预取白跑，还会污染
        // `lastPrefetchRequest`（下一次"口径是否失效"的判据随之失真）。越界直接返回：
        // 不发请求、不记账、不写台账。
        if (page < 0 || page >= pages.size) return
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
            DiagnosticLog.i(
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
                    DiagnosticLog.i(
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
            DiagnosticLog.i("PortraitSwipe", "prefetched: key=$key")
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
        DiagnosticLog.i(
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
     * `openVideo` 内部会自增 `openId`，等于一次全新的会话。
     *
     * ⚠️ **PB-01 修正**：只自增 `openId` **并不等于全新的取流** —— 取流入口
     * （`VideoPlayerRepositoryImpl.fetchPlaybackSource`）是**缓存优先**，而自动重试的退避
     * 是 1s / 3s、缓存 TTL 是 90s，于是重试必然命中缓存、拿回**同一份失效 URL**
     * （URL 失效 / 403 / 404 / 签名过期 这类失败结构性地不可能成功）。
     * 所以这里在 `openVideo` **之前**先失效该视频的播放地址缓存
     * （[VideoPlayerRepository.invalidatePlayUrl]，按 `aid#` 前缀覆盖所有 flavor，
     * 用户切了清晰度再重试也生效）。代价是多一次 gRPC 往返 —— 这正是修复的目的。
     */
    /**
     * 重试当前页（veil 里那个"重试"按钮，SW-13）：
     * - **去抖**：连点间隔 < 800ms 直接忽略（防连点把引擎打爆）；
     * - 状态回 `Ready`（状态机不变式 2），重开播放（`openVideo` 自增 openId = 全新一次取流）。
     */
    fun retryCurrentPlayback() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (SwipeErrorPolicy.isManualRetryDebounced(now, lastManualRetryMs)) {
            DiagnosticLog.i("PortraitSwipe", "retry ignored: debounced")
            return
        }
        lastManualRetryMs = now
        retryCurrentPlaybackInternal(fromAuto = false)
    }

    private fun retryCurrentPlaybackInternal(fromAuto: Boolean) {
        val target = _targetStack.value.lastOrNull() ?: return
        DiagnosticLog.i("PortraitSwipe", "retry: target=$target auto=$fromAuto")
        // PB-01：**必须先失效缓存再 openVideo**。顺序反了（放在 openVideo 之后）等于没做 ——
        // 取流已经发生、缓存里的失效 URL 也已经被重新写回，重试照旧拿旧地址。
        // 手动与自动重试共用本函数，故两条路径都被覆盖。
        playbackVideoRepository.invalidatePlayUrl(target.toPlayableParams().getResolveParams())
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

    // ── SW-24：会话内「不感兴趣」（含撤回）──────────────────────────────────────
    /** 上一次被"不感兴趣"摘掉的条目 + 原队列下标（撤回用；随会话 exit 清空）。 */
    private var lastDisliked: Pair<SwipeEntry, Int>? = null

    /** 当前是否可撤回（UI 据此显示「撤回」入口）。 */
    val canUndoSwipeDislike: Boolean get() = lastDisliked != null

    /**
     * 提交「不感兴趣」：上报负反馈 → 从队列摘掉 → 补货 → （当前页被摘时页号不变 =
     * 自然前进一支，见 [SwipeDislikeRemovalPolicy]）。
     *
     * 只有 feed 源带 `dislikeContext` 的条目才有这个入口（relate/story 源没有通道，
     * 不做点了没反应的假按钮——UI 侧按 null 隐藏）。
     *
     * @return true = 已受理（UI 可以关面板）
     */
    fun submitSwipeDislike(entry: SwipeEntry, currentPage: Int): Boolean {
        val context = entry.dislikeContext ?: return false
        // 理由：优先用卡上给的第一个 DISLIKE 理由（与首页同一份数据源）；
        // 拿不到就退到内建的"不感兴趣"（id=1，feed 接口的通用不感兴趣）
        val reason = entry.dislikeReasons.firstOrNull { it.kind == ThreePointReasonKind.DISLIKE }
            ?: ThreePointReason(
                id = DEFAULT_DISLIKE_REASON_ID,
                name = "不感兴趣",
                toast = "已减少此类内容",
                extra = null,
                kind = ThreePointReasonKind.DISLIKE
            )
        val index = swipeSession.queueIndexOf(entry)
        viewModelScope.launch {
            runCatchingCancellable { feedDislikeRepository.dislike(context, reason) }
                .onSuccess { result ->
                    val removed = swipeSession.removeQueueEntry(entry, currentPage)
                    if (removed) {
                        lastDisliked = entry to index.coerceAtLeast(0)
                        _swipeDislikeUndoable.value = true
                        // 摘掉一条就补一条（互动闭环：队列不该因为负反馈变薄）
                        loadMoreSwipeQueue()
                    }
                    _msg.tryEmit(result.toast)
                }
                .onFailure { e ->
                    Logger.e(TAG, e as? Exception) { "会话内不感兴趣失败" }
                    _msg.tryEmit(UserFacingError.messageOf(e))
                }
        }
        return true
    }

    /**
     * PT-06：刷视频「拉黑该 UP」。**本地先生效、再异步同步，失败不回滚本地**——
     * 名单（[blockedUpStore]）立即可见/可撤销；gRPC（[upBlocker] → 既有
     * `SpaceRepository.modifyBlacklist`）失败只记日志，不打断用户。
     * `mid <= 0` 的来源（story/relate 无 ownerMid）直接忽略——不做假入口。
     */
    fun blockSwipeUp(mid: Long, name: String? = null) {
        if (mid <= 0L) return
        viewModelScope.launch {
            // F-6：名单满 200 时最早的记录会被移除 —— 必须让用户看见，否则那条记录
            // 既不在名单里（无法撤销）又会重新出现在推荐流里。
            if (blockedUpStore.add(mid, name)) {
                _msg.tryEmit("拉黑名单已满 ${BlockedUpStore.CAP} 条，最早的记录已移除")
            }
            runCatchingCancellable { upBlocker.modifyBlacklist(mid, isBlack = true) }
                .onSuccess {
                    blockedUpStore.markSynced(mid)
                    Logger.d(TAG) { "拉黑已同步 mid=$mid" }
                }
                .onFailure { e ->
                    Logger.w(TAG) { "拉黑同步失败 mid=$mid msg=${e.message}（本地保留）" }
                }
        }
    }

    /** PT-06：撤销拉黑（本地名单移除 + 服务端解除）。 */
    fun unblockSwipeUp(mid: Long) {
        if (mid <= 0L) return
        viewModelScope.launch {
            blockedUpStore.remove(mid)
            runCatchingCancellable { upBlocker.modifyBlacklist(mid, isBlack = false) }
                .onFailure { e ->
                    Logger.w(TAG) { "解除拉黑失败 mid=$mid msg=${e.message}" }
                }
        }
    }

    /** 撤回「不感兴趣」：取消上报 + 把条目插回原位置。 */
    fun undoSwipeDislike() {
        val (entry, index) = lastDisliked ?: return
        val context = entry.dislikeContext ?: return
        viewModelScope.launch {
            runCatchingCancellable { feedDislikeRepository.cancelDislike(context) }
                .onSuccess { result ->
                    swipeSession.restoreQueueEntry(index, entry)
                    lastDisliked = null
                    _swipeDislikeUndoable.value = false
                    _msg.tryEmit(result.toast)
                }
                .onFailure { e ->
                    Logger.e(TAG, e as? Exception) { "撤回不感兴趣失败" }
                    _msg.tryEmit(UserFacingError.messageOf(e))
                }
        }
    }

    /** 撤回入口的可见性（会话内提示条/面板用）。 */
    private val _swipeDislikeUndoable = MutableStateFlow(false)
    val swipeDislikeUndoable: StateFlow<Boolean> = _swipeDislikeUndoable.asStateFlow()

    /** 弹幕总开关（SW-24 的"弹幕开关"入口；按已有设置写回，SW-27 再做竖屏作用域）。 */
    fun toggleDanmaku() {
        viewModelScope.launch {
            val current = playerSettings.state.first().danmaku
            playerSettings.setDanmaku(current.copy(enabled = !current.enabled))
        }
    }

    fun togglePlayPause() {
        if (videoState.value.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun switchQuality(quality: Int) {
        // **面板列的不是"能切的"**（2026-10-07 审查）：清晰度面板来自 `qnPanel.qnItems`
        // （含大会员/杜比等），而切换只在 `streamList` 解析出的 `streams` 里找；
        // 找不到就会话层直接 `return`，用户点一下"毫无反应也没有提示"。
        // 这里先判一次可切换性，给一次可见反馈（`msg` 由 VideoScreen 收成 snackbar）。
        val source = videoState.value.playbackSource
        if (source != null && source.streams.none { it.quality == quality }) {
            _msg.tryEmit("该清晰度当前不可用")
            return
        }
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

    /** CDN 并行分片下载开关（对齐 BiliPai `CdnRegionPlugin` 的「并发下载」）。 */
    fun updateCdnParallelDownload(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setCdnParallelDownload(enabled)
        }
    }

    /** DASH 分段自适应预取开关（对齐 BiliPai `CdnRegionPlugin` 的「自适应预取」）。 */
    fun updateCdnAdaptivePrefetch(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setCdnAdaptivePrefetch(enabled)
        }
    }

    /**
     * 精确 seek —— **内部跳转专用**：SponsorBlock 跳恰饭/片头片尾、自动片尾跳转
     * 都走这里（它们需要精确落到片段边界；被吸附到关键帧会重新落进片段内，
     * 触发一次重入）。用户拖进度条请用 [seekUserInitiated]。
     */
    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    /**
     * **用户主动** seek（进度条拖拽松手，PB-02）→ 走关键帧吸附，落点更快。
     *
     * 语义移植自上游 BiliPai（`VideoPlayerState.kt:1026,1072-1075` 的
     * `if (seekFastEnabled) CLOSEST_SYNC else DEFAULT`），但**只作用于用户主动 seek**：
     * 上游是"设置项开关 → 所有 seek 都吸附"，而 bbspace 的 SponsorBlock 跳段与续播
     * 共用同一个入口，全局吸附会让跳段失效并污染续播位置（见
     * `infra/player/SeekParametersPolicy` 的分档表）。
     */
    fun seekUserInitiated(positionMs: Long) {
        playbackController.seekUserInitiated(positionMs)
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

    /**
     * 跨会话「已推去重」（SW-05，默认开）。
     *
     * `PlayerPlaybackPrefs.crossSessionDedup` 此前是**只有读取方、没有写入方**的孤儿键：
     * `SwipeSeenMemory` 与 [prefetchSwipeQueue] 都认真读了它，但整个 App 没有任何 UI
     * 能改它 —— 等于"出厂值锁死 + 设置项不存在"（第 35 轮静态分析发现的死接口之一）。
     * 现在补上设置页的开关（`VideoPlaybackSheet`），读写两侧才闭环。
     */
    fun updateSwipeCrossSessionDedup(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setSwipeCrossSessionDedup(enabled)
        }
    }

    /** 刷视频模式防烧屏（默认开）：见 `PlayerPlaybackPrefs.burnInGuard` / `SwipeChromeBurnInPolicy`。 */
    fun updateSwipeBurnInGuard(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setSwipeBurnInGuard(enabled)
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

    /**
     * 点赞/取消点赞。
     *
     * **A16 修复（2026-10-13）**：点赞是**取反**操作，原写法
     * `val target = !(state.userState?.liked ?: false)` 在状态未知时 `?: false`
     * 恒折叠成「未点赞」⇒ `target` 恒为 true ⇒ **无条件发出 `setLike(true)`**。
     * 若服务端本来已赞，用户这一下本意是取消，实际却变成重复点赞（唯一一处
     * 「发错请求」的缺陷）。未知时必须拒绝并说明，不许猜。
     * 上游同款守卫：`a614d2eba` 的 `if (!state.likeStatusKnown) { emitMessage(...); return }`。
     */
    fun toggleLike() = withInteraction { state, aid, interactionTarget ->
        if (!state.likeStatusKnown) {
            _msg.tryEmit("点赞状态尚未确认，请稍后重试")
            return@withInteraction
        }
        val target = !(state.liked ?: false)
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
                        likeDelta = it.likeDelta + if (target) 1 else -1,
                        // 写成功 = 服务器已确认这一位（上游 doCoin/triple 的
                        // `likeStatusKnown = current.likeStatusKnown || 写成功` 同一口径）。
                        // 门在入口已把未知态挡掉了，所以这里通常是恒真；保留它是为了
                        // 覆盖"挂起期间互动区被重置成未知、但 ids 未变"这一窗口。
                        likeStatusKnown = true
                    )
                }
                _msg.tryEmit(toast.ifBlank { if (target) "点赞成功" else "已取消点赞" })
            },
            onFailure = { emitActionError(it, "点赞失败") }
        )
    }

    /** 投币，multiply 只能为 1 或 2，withLike 表示同时点赞 */
    fun submitCoin(multiply: Int, withLike: Boolean) = withInteraction { state, aid, interactionTarget ->
        // A16：`withLike` 会把「已赞」一并写进本地态，而未知态推不出这个位。
        // 不整块拒绝——用户仍可只投币（上游 a614d2eba 同款口径）。
        if (withLike && !state.likeStatusKnown) {
            _msg.tryEmit("点赞状态尚未确认，请取消附带点赞后重试")
            return@withInteraction
        }
        val coinCount = state.coinCount ?: 0
        if (coinCount + multiply > 2) {
            _msg.tryEmit("达到投币上限啦~")
            return@withInteraction
        }
        val likedBefore = state.liked ?: false
        runCatchingCancellable { actionRepository.addCoin(aid, multiply, withLike) }.fold(
            onSuccess = {
                updateInteractionIfCurrent(interactionTarget) {
                    it.copy(
                        userState = (it.userState ?: VideoUserState()).copy(
                            coinCount = coinCount + multiply,
                            liked = likedBefore || withLike
                        ),
                        coinDelta = it.coinDelta + multiply,
                        likeDelta = it.likeDelta + (if (withLike && !likedBefore) 1 else 0),
                        // A16：`withLike` 写成功即点赞位已确认（上游 doCoin 的
                        // `likeStatusKnown = current.likeStatusKnown || alsoLike` 同口径）。
                        likeStatusKnown = it.likeStatusKnown || withLike
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
     *
     * **A16**：三连同样以「点赞态」为输入——Web 端点自行判断、而 [tripleFallback]
     * 的降级分支直接发 `setLike(aid, true)`。未知态下必须拒绝（上游
     * `doTripleAction` 的 `if (!state.likeStatusKnown)` 同一口径）。
     */
    fun triple() = withInteraction { state, aid, interactionTarget ->
        if (!state.likeStatusKnown) {
            _msg.tryEmit("点赞状态尚未确认，请稍后重试")
            return@withInteraction
        }
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
                favDelta = it.favDelta + (if (fav && !user.faved) 1L else 0L),
                // A16：点赞写成功即该位已确认（上游 doTripleAction 的
                // `likeStatusKnown = current.likeStatusKnown || result.likeSuccess` 同口径）。
                // 入口已门过 likeStatusKnown，这里是幂等的补写，覆盖挂起期间被重置的窗口。
                likeStatusKnown = it.likeStatusKnown || like
            )
        }
    }

    /**
     * Web 三连失败后的降级：逐项执行，单项失败不阻断其余项。
     *
     * A16：本方法以 `state.liked` 决定要不要补发 `setLike(aid, true)` —— 未知态下
     * `?: false` 会折叠成"未点赞"从而**多发一次点赞**。入口 [triple] 已按
     * `likeStatusKnown` 整块拒绝，所以走到这里时 `liked` 一定非空；下面统一用
     * `?: false` 兜底只是防御性写法（`?:` 在此处语义上不可达），不承担判断。
     */
    private suspend fun tripleFallback(aid: Long, interactionTarget: InteractionTarget): String {
        val state = _interaction.value
        val likedBefore = state.liked ?: false
        val coinBefore = state.coinCount ?: 0
        val favedBefore = state.faved ?: false
        val alreadyAll = likedBefore && coinBefore > 0 && favedBefore
        if (alreadyAll) return "已完成三连"

        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (!likedBefore) {
            runCatchingCancellable { actionRepository.setLike(aid, true) }
                .onSuccess {
                    done += "点赞"
                    markTripleResult(like = true, coin = false, fav = false, target = interactionTarget)
                }
                .onFailure { failed += "点赞" }
        }
        if (coinBefore == 0) {
            runCatchingCancellable { actionRepository.addCoin(aid, 1, selectLike = false) }
                .onSuccess {
                    done += "投币"
                    markTripleResult(like = false, coin = true, fav = false)
                }
                .onFailure { failed += "投币" }
        }
        if (!favedBefore) {
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

        /** SW-24：feed 接口的通用"不感兴趣"理由 id（卡上没给 reasons 时的兜底）。 */
        const val DEFAULT_DISLIKE_REASON_ID = 1
        const val TAG = "VideoViewModel"
    }
}
