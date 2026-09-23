package com.naaammme.bbspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.error.UserFacingError
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.feed.FeedDislikeRepository
import com.naaammme.bbspace.core.feed.PageActionTracker
import com.naaammme.bbspace.core.feed.FeedRepository
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.ThreePointReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 列表尾部剩余条目数阈值：进入该范围就提前取下一页。
 * 此前只在 `gridState.canScrollForward == false`（即已滑到底）时才请求，
 * 用户每次都要在底部看转圈；按条目数提前取页后，滑到底时新内容已就位。
 */
const val LOAD_MORE_THRESHOLD = 6

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val feedRepo: FeedRepository,
    private val feedDislikeRepo: FeedDislikeRepository,
    private val appSettings: AppSettings,
    private val pageActionTracker: PageActionTracker
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"

        /**
         * "列表还没填满一屏就自动补一页"的次数上限。
         * 首屏只有一屏多一点内容时用户必须手动划到底才会触发下一页，观感是
         * "每次就刷出来这几个"；这里在首屏加载/刷新后自动补一页。
         * 上限同时兜住异常服务端：若持续返回重复卡片，去重后条目不增长
         * （stagnantLoads 累计），补拉随即停止，不会请求风暴。
         */
        private const val AUTO_FILL_MAX = 2

        /** 连续多少次取页「没有新增条目」后停止自动补拉。 */
        private const val MAX_STAGNANT_LOADS = 2
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    /** 首页推荐流单列大图布局（预览图更大），默认开启。 */
    val homeSingleColumn = appSettings.homeFeedSingleColumn.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        true
    )

    fun toggleHomeSingleColumn() {
        viewModelScope.launch {
            appSettings.setHomeFeedSingleColumn(!homeSingleColumn.value)
        }
    }

    private var flush = 0

    /** 本轮列表（首屏/刷新后）已执行的自动补页次数。 */
    private var autoFillCount = 0

    /**
     * 连续「取页成功但去重后条目没有增长」的次数。服务端异常时（重复下发同一批卡片）
     * 它会连续累加，自动补拉随之停止——自动补页绝不能变成无条件循环请求。
     */
    private var stagnantLoads = 0

    /** 列表还没填满一屏时的自动补页是否还有额度。 */
    val canAutoFillMore: Boolean
        get() = autoFillCount < AUTO_FILL_MAX && stagnantLoads < MAX_STAGNANT_LOADS

    fun refreshPageAction() {
        pageActionTracker.refresh()
    }

    /** 新一轮列表开始：自动补页额度与停滞计数复位。 */
    private fun resetPagingGuards() {
        autoFillCount = 0
        stagnantLoads = 0
    }

    init {
        viewModelScope.launch {
            feedRepo.toastFlow.collect { toast ->
                if (toast.hasToast) {
                    _uiState.update { it.copy(toastMessage = toast.message) }
                }
            }
        }
        loadInitial()
    }

    private fun loadInitial() {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                flush = 0
                resetPagingGuards()
                val feed = feedRepo.fetchFeed(idx = 0L, pull = true, flush = flush)
                _uiState.update { it.copy(items = feed.items) }
                flush++
                if (feed.interestChoose != null) {
                    val done = appSettings.interestDone.first()
                    if (!done) {
                        _uiState.update { it.copy(interestChoose = feed.interestChoose) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingError.logAndMessage(TAG, e, "加载首页失败"))
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
                // 首屏内容不足一屏时先补一页，避免"打开就只有几张卡"的观感
                autoFillIfNeeded()
            }
        }
    }

    /**
     * 首屏/刷新后的自动补页：列表长度还不到 [LOAD_MORE_THRESHOLD] 条时再取一页
     * （最多 [AUTO_FILL_MAX] 次）。手动滑到底仍由列表的提前预取阈值负责。
     */
    private fun autoFillIfNeeded() {
        if (!canAutoFillMore) return
        if (_uiState.value.items.size > LOAD_MORE_THRESHOLD) return
        autoFillCount++
        loadMore()
    }

    fun dismissInterest() {
        _uiState.update { it.copy(interestChoose = null) }
    }

    fun submitInterest(interestId: Int, interestResult: String, interestPosIds: String) {
        viewModelScope.launch {
            try {
                appSettings.markInterestDone()
                _uiState.update {
                    it.copy(
                        interestChoose = null,
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                val feed = feedRepo.fetchFeedWithInterest(
                    idx = 0L, pull = true, flush = flush,
                    interestId = interestId,
                    interestResult = interestResult,
                    interestPosIds = interestPosIds
                )
                _uiState.update { it.copy(items = feed.items) }
                flush++
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingError.logAndMessage(TAG, e, "提交兴趣失败"))
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                val currentTopIdx = _uiState.value.items.firstOrNull()?.idx ?: 0L
                flush = 0
                resetPagingGuards()
                val feed = feedRepo.fetchFeed(idx = currentTopIdx, pull = true, flush = flush)
                if (feed.items.isNotEmpty()) {
                    val merged = withContext(Dispatchers.Default) {
                        // 按 (goto|param|idx) 去重：服务端偶发重复下发卡片，
                        // 重复 key 会让 LazyStaggeredGrid 直接抛异常崩溃
                        (feed.items + _uiState.value.items).distinctBy { it.actionKey }
                    }
                    _uiState.update { it.copy(items = merged) }
                    flush++
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingError.logAndMessage(TAG, e, "刷新失败"))
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
                // 刷新结果太少时同样补页（服务端忽略 ps 或返回条数偏少）
                autoFillIfNeeded()
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isRefreshing) return
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingMore = true) }
                val lastIdx = _uiState.value.items.lastOrNull()?.idx ?: 0L
                val feed = feedRepo.fetchFeed(idx = lastIdx, pull = false, flush = flush)
                if (feed.items.isNotEmpty()) {
                    val before = _uiState.value.items
                    val merged = withContext(Dispatchers.Default) {
                        // 追加时同样去重，保留已有条目在前
                        (before + feed.items).distinctBy { it.actionKey }
                    }
                    _uiState.update { it.copy(items = merged) }
                    flush++
                    // 去重后条目没增长 → 服务端在下发重复卡片，累计停滞次数
                    if (merged.size > before.size) {
                        stagnantLoads = 0
                    } else {
                        stagnantLoads++
                    }
                } else {
                    stagnantLoads++
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingError.logAndMessage(TAG, e, "加载更多失败"))
                }
                stagnantLoads++
            } finally {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun submitDislike(item: FeedItem, reason: ThreePointReason) {
        val context = item.dislikeContext ?: run {
            _uiState.update { it.copy(toastMessage = "当前卡片暂不支持此操作") }
            return
        }
        val itemKey = item.actionKey
        viewModelScope.launch {
            runCatching { feedDislikeRepo.dislike(context, reason) }
                .onSuccess { result ->
                    _uiState.update { state ->
                        state.copy(
                            dislikedReasons = state.dislikedReasons + (itemKey to reason.name),
                            toastMessage = result.toast
                        )
                    }
                }
                .onFailure { e ->
                    Logger.e(TAG, e as? Exception) { "提交不感兴趣失败" }
                    _uiState.update { state ->
                        state.copy(
                            toastMessage = UserFacingError.messageOf(e)
                        )
                    }
                }
        }
    }

    fun cancelDislike(item: FeedItem) {
        val context = item.dislikeContext ?: run {
            _uiState.update { it.copy(toastMessage = "当前卡片暂不支持此操作") }
            return
        }
        val itemKey = item.actionKey
        viewModelScope.launch {
            runCatching { feedDislikeRepo.cancelDislike(context) }
                .onSuccess { result ->
                    _uiState.update { state ->
                        state.copy(
                            dislikedReasons = state.dislikedReasons - itemKey,
                            toastMessage = result.toast
                        )
                    }
                }
                .onFailure { e ->
                    Logger.e(TAG, e as? Exception) { "撤回不感兴趣失败" }
                    _uiState.update { state ->
                        state.copy(
                            toastMessage = UserFacingError.messageOf(e)
                        )
                    }
                }
        }
    }

    fun consumeToast() {
        if (_uiState.value.toastMessage.isEmpty()) return
        _uiState.update { it.copy(toastMessage = "") }
    }
}
