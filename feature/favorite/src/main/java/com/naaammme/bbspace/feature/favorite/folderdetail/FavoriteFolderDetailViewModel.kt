package com.naaammme.bbspace.feature.favorite.folderdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.favorite.FavoriteRepository
import com.naaammme.bbspace.core.model.FavoriteContentItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class FavoriteFolderDetailViewModel @Inject constructor(
    private val repo: FavoriteRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val fid = savedStateHandle.get<Long>(FID_ARG) ?: 0L

    private val _uiState = MutableStateFlow(FavoriteFolderDetailUiState(isLoading = true))
    val uiState: StateFlow<FavoriteFolderDetailUiState> = _uiState.asStateFlow()

    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val msg: SharedFlow<String> = _msg.asSharedFlow()

    private var loadJob: Job? = null

    init {
        if (fid > 0L) {
            loadPage(reset = true, refreshing = false)
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = "收藏夹不存在"
                )
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadPage(reset = true, refreshing = true)
    }

    fun loadMore() {
        if (!_uiState.value.canLoadMore) return
        loadPage(reset = false, refreshing = false)
    }

    private fun loadPage(
        reset: Boolean,
        refreshing: Boolean
    ) {
        if (fid <= 0L || loadJob?.isActive == true) return
        val reqPage = if (reset) 1 else _uiState.value.page + 1
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !refreshing && reset && it.items.isEmpty(),
                    isRefreshing = refreshing,
                    isLoadingMore = !reset,
                    errorMessage = null,
                    errorOnLoadMore = false
                )
            }
            runCatching {
                repo.fetchFavoriteFolderContents(fid = fid, page = reqPage)
            }.onSuccess { page ->
                _uiState.update {
                    // 按 key 去重，避免翻页窗口重叠导致列表重复 key 崩溃
                    it.copy(
                        items = if (reset) {
                            page.items.distinctBy(FavoriteContentItem::key)
                        } else {
                            (it.items + page.items).distinctBy(FavoriteContentItem::key)
                        },
                        page = reqPage,
                        totalCount = page.totalCount,
                        hasInvalid = page.hasInvalid,
                        hasMore = page.hasMore,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = null,
                        errorOnLoadMore = false
                    )
                }
            }.onFailure { e ->
                if (!isActive) return@launch
                Logger.e(TAG, e) { "加载收藏夹内容失败 fid=$fid page=$reqPage" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = e.message ?: "加载收藏夹失败",
                        errorOnLoadMore = !reset
                    )
                }
            }
        }
    }

    // ===== 多选删除 =====

    /** 长按内容进入多选模式并选中该项 */
    fun enterSelection(item: FavoriteContentItem) {
        if (item.isInvalid) return
        _uiState.update {
            it.copy(
                selectionMode = true,
                selectedKeys = it.selectedKeys + item.key
            )
        }
    }

    fun toggleSelect(item: FavoriteContentItem) {
        if (item.isInvalid) return
        _uiState.update {
            it.copy(
                selectedKeys = if (item.key in it.selectedKeys) {
                    it.selectedKeys - item.key
                } else {
                    it.selectedKeys + item.key
                }
            )
        }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(selectedKeys = it.items.filterNot { item -> item.isInvalid }.map { item -> item.key }.toSet())
        }
    }

    fun exitSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedKeys = emptySet()) }
    }

    /** 从收藏夹移除选中的内容 */
    fun deleteSelected() {
        val state = _uiState.value
        if (state.submitting || state.selectedKeys.isEmpty()) return
        val resources = state.items
            .filter { it.key in state.selectedKeys && !it.isInvalid }
            .map { "${it.oid}:${it.otype}" }
        if (resources.isEmpty()) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            runCatching { repo.deleteFolderContents(fid, resources) }.fold(
                onSuccess = {
                    _msg.tryEmit("已移除 ${resources.size} 个内容")
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            selectionMode = false,
                            selectedKeys = emptySet()
                        )
                    }
                    loadPage(reset = true, refreshing = false)
                },
                onFailure = { e ->
                    Logger.e(TAG, e) { "移除收藏内容失败 fid=$fid count=${resources.size}" }
                    _uiState.update { it.copy(submitting = false) }
                    _msg.tryEmit(e.message ?: "移除失败")
                }
            )
        }
    }

    private companion object {
        const val TAG = "FavoriteFolderDetailVm"
        const val FID_ARG = "fid"
    }
}
