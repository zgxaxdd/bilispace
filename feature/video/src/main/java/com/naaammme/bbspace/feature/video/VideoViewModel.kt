package com.naaammme.bbspace.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.naaammme.bbspace.core.auth.AuthStore
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        // 换视频时以服务器返回的状态重置互动区
        viewModelScope.launch {
            videoState
                .map { Triple(it.ids.aid, it.detail?.stat, it.detail?.userState) }
                .distinctUntilChanged()
                .collect { (_, stat, userState) ->
                    _interaction.value = VideoInteractionState(stat = stat, userState = userState)
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

    fun updateInAppMiniPlayer(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setInAppMiniPlayer(enabled)
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
    fun toggleLike() = withInteraction { state, aid ->
        val target = !(state.userState?.liked ?: false)
        runCatching { actionRepository.setLike(aid, target) }.fold(
            onSuccess = { toast ->
                _interaction.update {
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
    fun submitCoin(multiply: Int, withLike: Boolean) = withInteraction { state, aid ->
        val coinCount = state.userState?.coinCount ?: 0
        if (coinCount + multiply > 2) {
            _msg.tryEmit("达到投币上限啦~")
            return@withInteraction
        }
        val likedBefore = state.userState?.liked ?: false
        runCatching { actionRepository.addCoin(aid, multiply, withLike) }.fold(
            onSuccess = {
                _interaction.update {
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
            },
            onFailure = { emitActionError(it, "投币失败") }
        )
    }

    /**
     * 一键三连：Web 端点一次完成；失败时降级为逐项执行，单项失败不阻断其余项
     */
    fun triple() = withInteraction { _, aid ->
        val message = try {
            val result = actionRepository.triple(aid, videoState.value.ids.bvid)
            markTripleResult(like = result.like, coin = result.coin, fav = result.fav)
            "三连成功"
        } catch (error: Exception) {
            tripleFallback(aid)
        }
        _msg.tryEmit(message)
    }

    fun openFavSheet() {
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            return
        }
        _favSheet.value = FavFolderSheetState(loading = true)
        viewModelScope.launch {
            val (rid, type) = favRidType()
            runCatching { favoriteRepository.fetchCreatedFoldersWithState(rid, type) }.fold(
                onSuccess = { folders ->
                    val favedIds = folders.filter { it.favState == 1 }.map { it.fid }.toSet()
                    _favSheet.value = FavFolderSheetState(
                        folders = folders,
                        initialFavedIds = favedIds,
                        selectedIds = favedIds
                    )
                },
                onFailure = { error ->
                    _favSheet.value = FavFolderSheetState()
                    emitActionError(error, "获取收藏夹失败")
                }
            )
        }
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
            runCatching { favoriteRepository.createFolder(title, privacy) }.fold(
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
        viewModelScope.launch {
            val (rid, type) = favRidType()
            runCatching { favoriteRepository.dealFav(rid, type, addIds, delIds) }.fold(
                onSuccess = {
                    val favedNow = sheet.selectedIds.isNotEmpty()
                    val favedBefore = sheet.initialFavedIds.isNotEmpty()
                    _interaction.update {
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

    /** 硬币余额（来自缓存的账号信息，可能为空） */
    fun coinBalance(): Double? = authStore.getUserInfo()?.coins

    private fun withInteraction(action: suspend (VideoInteractionState, Long) -> Unit) {
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            return
        }
        val aid = videoState.value.ids.aid
        if (aid <= 0L) return
        if (_interaction.value.busy) {
            _msg.tryEmit("操作处理中，请稍候")
            return
        }
        _interaction.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                action(_interaction.value, aid)
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

    private fun markTripleResult(like: Boolean, coin: Boolean, fav: Boolean) {
        _interaction.update {
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
    private suspend fun tripleFallback(aid: Long): String {
        val state = _interaction.value
        val alreadyAll = (state.userState?.liked ?: false) &&
            (state.userState?.coinCount ?: 0) > 0 &&
            (state.userState?.faved ?: false)
        if (alreadyAll) return "已完成三连"

        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (!(state.userState?.liked ?: false)) {
            runCatching { actionRepository.setLike(aid, true) }
                .onSuccess {
                    done += "点赞"
                    markTripleResult(like = true, coin = false, fav = false)
                }
                .onFailure { failed += "点赞" }
        }
        if ((state.userState?.coinCount ?: 0) == 0) {
            runCatching { actionRepository.addCoin(aid, 1, selectLike = false) }
                .onSuccess {
                    done += "投币"
                    markTripleResult(like = false, coin = true, fav = false)
                }
                .onFailure { failed += "投币" }
        }
        if (!(state.userState?.faved ?: false)) {
            runCatching {
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
