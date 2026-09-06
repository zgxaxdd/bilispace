package com.naaammme.bbspace.feature.dynamic

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.comment.CommentRepository
import com.naaammme.bbspace.core.comment.ImageAttachmentHandler
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.auth.AuthRepository
import com.naaammme.bbspace.core.dynamic.DynamicRepository
import com.naaammme.bbspace.core.model.DynamicCursor
import com.naaammme.bbspace.core.model.DynamicItem
import com.naaammme.bbspace.core.model.DynamicPicUpload
import com.naaammme.bbspace.core.model.DynamicRefresh
import com.naaammme.bbspace.core.model.DynamicStats
import com.naaammme.bbspace.core.model.VideoTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DynamicViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val repo: DynamicRepository,
    private val commentRepo: CommentRepository,
    private val imageHandler: ImageAttachmentHandler,
    private val authProvider: AuthProvider
) : ViewModel() {

    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val msg: SharedFlow<String> = _msg.asSharedFlow()

    private val likeBusyIds = mutableSetOf<String>()

    private val _uiState = MutableStateFlow(
        DynamicUiState(isLoggedIn = authRepo.currentMidFlow.value > 0)
    )
    val uiState: StateFlow<DynamicUiState> = _uiState.asStateFlow()
    private var authVersion = 0L

    init {
        viewModelScope.launch {
            authRepo.currentMidFlow.collect { mid ->
                authVersion += 1L
                if (mid > 0) {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = true,
                            errorMessage = null,
                            loadMoreError = null
                        )
                    }
                    if (_uiState.value.items.isNotEmpty()) return@collect
                    refresh()
                } else {
                    _uiState.value = DynamicUiState(isLoggedIn = false)
                }
            }
        }
    }

    fun refresh() {
        val state = _uiState.value
        if (!state.isLoggedIn || state.isLoading || state.isRefreshing || state.isLoadingMore) return
        val hasItems = state.items.isNotEmpty()
        _uiState.update {
            it.copy(
                isLoading = !hasItems,
                isRefreshing = hasItems,
                isLoadingMore = false,
                errorMessage = null,
                loadMoreError = null
            )
        }
        viewModelScope.launch {
            load(refresh = DynamicRefresh.NEW, reset = true)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.canLoadMore) return
        _uiState.update {
            it.copy(
                isLoadingMore = true,
                loadMoreError = null
            )
        }
        viewModelScope.launch {
            load(refresh = DynamicRefresh.HISTORY, reset = false)
        }
    }

    private suspend fun load(
        refresh: DynamicRefresh,
        reset: Boolean
    ) {
        val state = _uiState.value
        val version = authVersion
        try {
            val page = repo.fetchAll(
                cursor = if (reset) DEFAULT_CURSOR else state.cursor,
                refresh = refresh
            )
            _uiState.update {
                if (version != authVersion || !it.isLoggedIn) return@update it
                it.copy(
                    upList = page.upList ?: it.upList,
                    items = if (reset) page.items else it.items.mergePage(page.items),
                    cursor = page.cursor,
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasMore = page.hasMore,
                    errorMessage = null,
                    loadMoreError = null
                )
            }
        } catch (e: Exception) {
            if (version != authVersion) return
            Logger.e(TAG, e) { "加载动态失败" }
            _uiState.update {
                if (!it.isLoggedIn) return@update it
                val msg = e.message ?: if (reset) "加载动态失败" else "加载更多动态失败"
                if (reset) {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = msg
                    )
                } else {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        loadMoreError = msg
                    )
                }
            }
        }
    }

    private fun List<DynamicItem>.mergePage(
        incoming: List<DynamicItem>
    ): List<DynamicItem> {
        if (isEmpty()) return incoming
        if (incoming.isEmpty()) return this
        val seen = HashSet<String>(size + incoming.size)
        return buildList(size + incoming.size) {
            for (item in this@mergePage) {
                if (seen.add(item.id)) add(item)
            }
            for (item in incoming) {
                if (seen.add(item.id)) add(item)
            }
        }
    }

    // ===== 动态互动（点赞/转发/发布） =====

    /** 动态点赞/取消点赞 */
    fun toggleLike(item: DynamicItem) {
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            return
        }
        val stats = item.stats ?: return
        if (item.id in likeBusyIds) return
        val target = !stats.liked
        likeBusyIds += item.id
        viewModelScope.launch {
            val result = runCatching { repo.thumbDyn(item.id, target) }
            likeBusyIds -= item.id
            result.fold(
                onSuccess = {
                    updateItemStats(item.id) { s ->
                        s.copy(
                            liked = target,
                            like = (s.like + if (target) 1L else -1L).coerceAtLeast(0L)
                        )
                    }
                    _msg.tryEmit(if (target) "点赞成功" else "已取消点赞")
                },
                onFailure = { e -> _msg.tryEmit(e.message ?: "点赞失败") }
            )
        }
    }

    /** 转发动态/视频，onDone(true) 表示成功可关闭对话框 */
    fun repostDynamic(item: DynamicItem, text: String, onDone: (Boolean) -> Unit) {
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            onDone(false)
            return
        }
        // 仅普通视频动态走 revs_id 转发（DYN_TYPE_AV=8），其余一律按 dyn_id 转发
        val aid = (item.videoTarget as? VideoTarget.Ugc)?.aid ?: 0L
        viewModelScope.launch {
            val result = runCatching {
                if (aid > 0L) {
                    repo.createDyn(
                        mid = authProvider.mid,
                        text = text,
                        repostRid = aid,
                        repostDynType = item.dynType
                    )
                } else {
                    repo.createDyn(mid = authProvider.mid, text = text, repostDynId = item.id)
                }
            }
            result.fold(
                onSuccess = {
                    updateItemStats(item.id) { s -> s.copy(repost = s.repost + 1L) }
                    _msg.tryEmit("转发成功")
                    onDone(true)
                },
                onFailure = { e ->
                    _msg.tryEmit(e.message ?: "转发失败")
                    onDone(false)
                }
            )
        }
    }

    /** 发布纯文本/图片动态，onDone(true) 表示成功可关闭面板 */
    fun publishDynamic(text: String, imageUris: List<Uri>, onDone: (Boolean) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageUris.isEmpty()) {
            _msg.tryEmit("内容不能为空")
            onDone(false)
            return
        }
        if (!loggedIn()) {
            _msg.tryEmit("请先登录")
            onDone(false)
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                val pics = imageUris.mapIndexed { index, uri ->
                    val prepared = imageHandler.prepare(uri)
                    val json = commentRepo.uploadImage(prepared.bytes, index, prepared.mimeType)
                    val data = json.getJSONObject("data")
                    DynamicPicUpload(
                        url = data.getString("image_url"),
                        width = data.getInt("image_width"),
                        height = data.getInt("image_height"),
                        sizeBytes = data.getDouble("img_size").toLong()
                    )
                }
                repo.createDyn(mid = authProvider.mid, text = trimmed, pics = pics)
            }
            result.fold(
                onSuccess = {
                    _msg.tryEmit("发布成功")
                    onDone(true)
                },
                onFailure = { e ->
                    Logger.e(TAG, e) { "发布动态失败" }
                    _msg.tryEmit(e.message ?: "发布失败")
                    onDone(false)
                }
            )
        }
    }

    private fun loggedIn(): Boolean = authProvider.mid > 0L

    private fun updateItemStats(id: String, transform: (DynamicStats) -> DynamicStats) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == id) {
                        item.copy(stats = item.stats?.let(transform))
                    } else {
                        item
                    }
                }
            )
        }
    }

    private companion object {
        const val TAG = "DynamicViewModel"
        val DEFAULT_CURSOR = DynamicCursor()
    }
}
