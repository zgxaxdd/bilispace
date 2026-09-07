package com.naaammme.bbspace.feature.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.favorite.FavoriteRepository
import com.naaammme.bbspace.core.model.FavoriteContentCursor
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
class FavoriteViewModel @Inject constructor(
    private val repo: FavoriteRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        FavoriteUiState(
            content = FavoriteContentUiState(isLoading = true)
        )
    )
    val uiState: StateFlow<FavoriteUiState> = _uiState.asStateFlow()

    private val _sortState = MutableStateFlow(FolderSortUiState())
    val sortState: StateFlow<FolderSortUiState> = _sortState.asStateFlow()

    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val msg: SharedFlow<String> = _msg.asSharedFlow()

    private var contentJob: Job? = null
    private var folderJob: Job? = null

    init {
        loadContent(reset = true, refreshing = false)
    }

    fun selectTab(tab: FavoriteTab) {
        val state = _uiState.value
        if (state.tab == tab) return
        val shouldLoad = when (tab) {
            FavoriteTab.CONTENT -> state.content.items.isEmpty()
            FavoriteTab.FOLDER -> state.folder.folders.isEmpty()
        }
        _uiState.update { it.copy(tab = tab) }
        if (!shouldLoad) return
        when (tab) {
            FavoriteTab.CONTENT -> loadContent(reset = true, refreshing = false)
            FavoriteTab.FOLDER -> loadFolders(refreshing = false)
        }
    }

    fun refresh() {
        when (_uiState.value.tab) {
            FavoriteTab.CONTENT -> {
                contentJob?.cancel()
                loadContent(reset = true, refreshing = true)
            }

            FavoriteTab.FOLDER -> {
                folderJob?.cancel()
                loadFolders(refreshing = true)
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value.content
        if (!state.canLoadMore) return
        loadContent(reset = false, refreshing = false)
    }

    private fun loadFolders(refreshing: Boolean) {
        if (folderJob?.isActive == true) return
        folderJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    folder = it.folder.copy(
                        isLoading = !refreshing && it.folder.folders.isEmpty(),
                        isRefreshing = refreshing,
                        errorMessage = null
                    )
                )
            }
            runCatching {
                repo.fetchMyFavorites()
            }.onSuccess { page ->
                _uiState.update {
                    it.copy(
                        folder = it.folder.copy(
                            folders = page.folders,
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null
                        )
                    )
                }
            }.onFailure { e ->
                if (!isActive) return@launch
                Logger.e(TAG, e) { "加载收藏夹失败" }
                _uiState.update {
                    it.copy(
                        folder = it.folder.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = e.message ?: "加载收藏失败"
                        )
                    )
                }
            }
        }
    }

    private fun loadContent(
        reset: Boolean,
        refreshing: Boolean
    ) {
        if (contentJob?.isActive == true) return
        val reqCursor = if (reset) FavoriteContentCursor() else _uiState.value.content.cursor
        contentJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    content = it.content.copy(
                        isLoading = !refreshing && reset && it.content.items.isEmpty(),
                        isRefreshing = refreshing,
                        isLoadingMore = !reset,
                        errorMessage = null,
                        errorOnLoadMore = false
                    )
                )
            }
            runCatching {
                repo.fetchFavoriteContents(reqCursor)
            }.onSuccess { page ->
                _uiState.update {
                    it.copy(
                        content = it.content.copy(
                            items = if (reset) page.items else it.content.items + page.items,
                            cursor = page.cursor,
                            hasMore = page.hasMore,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            errorMessage = null,
                            errorOnLoadMore = false
                        )
                    )
                }
            }.onFailure { e ->
                if (!isActive) return@launch
                Logger.e(TAG, e) { "加载全部收藏失败" }
                _uiState.update {
                    it.copy(
                        content = it.content.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            errorMessage = e.message ?: "加载收藏失败",
                            errorOnLoadMore = !reset
                        )
                    )
                }
            }
        }
    }

    // ===== 收藏夹管理 =====

    /** 新建收藏夹（privacy：0=公开，1=私有） */
    fun createFolder(title: String, privacy: Int) {
        viewModelScope.launch {
            runCatching { repo.createFolder(title, privacy) }.fold(
                onSuccess = {
                    _msg.tryEmit("收藏夹已创建")
                    loadFolders(refreshing = true)
                },
                onFailure = { e -> _msg.tryEmit(e.message ?: "新建收藏夹失败") }
            )
        }
    }

    /** 重命名收藏夹 */
    fun renameFolder(fid: Long, title: String, privacy: Int) {
        viewModelScope.launch {
            runCatching { repo.editFolder(fid, title, privacy) }.fold(
                onSuccess = {
                    _msg.tryEmit("收藏夹已更新")
                    loadFolders(refreshing = true)
                },
                onFailure = { e -> _msg.tryEmit(e.message ?: "修改收藏夹失败") }
            )
        }
    }

    /** 删除收藏夹 */
    fun deleteFolder(fid: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteFolder(fid) }.fold(
                onSuccess = {
                    _msg.tryEmit("收藏夹已删除")
                    loadFolders(refreshing = true)
                },
                onFailure = { e -> _msg.tryEmit(e.message ?: "删除收藏夹失败") }
            )
        }
    }

    /** 打开排序弹层：加载用户创建的收藏夹（list-all，第一个为默认收藏夹） */
    fun openFolderSort() {
        _sortState.value = FolderSortUiState(visible = true, loading = true)
        viewModelScope.launch {
            runCatching { repo.fetchCreatedFoldersWithState(rid = 0L, type = 0) }.fold(
                onSuccess = { folders ->
                    _sortState.value = FolderSortUiState(visible = true, entries = folders)
                },
                onFailure = { e ->
                    _sortState.value = FolderSortUiState()
                    _msg.tryEmit(e.message ?: "获取收藏夹失败")
                }
            )
        }
    }

    /** 上移/下移排序项 */
    fun moveSortEntry(fid: Long, offset: Int) {
        _sortState.update { state ->
            val index = state.entries.indexOfFirst { it.fid == fid }
            if (index < 0) return@update state
            val target = (index + offset).coerceIn(0, state.entries.lastIndex)
            if (target == index) return@update state
            val mutable = state.entries.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(target, item)
            state.copy(entries = mutable)
        }
    }

    /** 保存排序 */
    fun submitFolderSort() {
        val state = _sortState.value
        if (state.submitting || state.loading) return
        val orderedIds = state.entries.map { it.fid }
        if (orderedIds.isEmpty()) return
        _sortState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            runCatching { repo.sortFolders(orderedIds) }.fold(
                onSuccess = {
                    _sortState.value = FolderSortUiState()
                    _msg.tryEmit("排序完成")
                    loadFolders(refreshing = true)
                },
                onFailure = { e ->
                    _sortState.update { it.copy(submitting = false) }
                    _msg.tryEmit(e.message ?: "排序失败")
                }
            )
        }
    }

    fun dismissFolderSort() {
        _sortState.update { it.copy(visible = false) }
    }

    private companion object {
        const val TAG = "FavoriteViewModel"
    }
}
