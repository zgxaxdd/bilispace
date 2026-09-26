package com.naaammme.bbspace.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.VideoPlaybackController
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
import com.naaammme.bbspace.core.sponsorblock.SbActionType
import com.naaammme.bbspace.core.sponsorblock.SbSegment
import com.naaammme.bbspace.core.sponsorblock.SbSkipType
import com.naaammme.bbspace.core.sponsorblock.SponsorBlockClient
import com.naaammme.bbspace.core.video.VideoActionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val playbackController: VideoPlaybackController,
    private val playerSettings: AppSettings,
    private val sponsorBlockClient: SponsorBlockClient,
    private val actionRepository: VideoActionRepository,
    private val favoriteRepository: FavoriteRepository,
    private val authStore: AuthStore
) : ViewModel() {

    private val _targetStack = MutableStateFlow<List<VideoTarget>>(emptyList())

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
}
