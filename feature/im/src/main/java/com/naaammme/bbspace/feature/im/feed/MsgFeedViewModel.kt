package com.naaammme.bbspace.feature.im.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.model.MsgFeedFilter
import com.naaammme.bbspace.core.model.im.MsgFeedItem

import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.im.ImRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MsgFeedViewModel @Inject constructor(
    private val imRepo: ImRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MsgFeedUiState())
    val uiState: StateFlow<MsgFeedUiState> = _uiState.asStateFlow()

    private var reqId = 0L

    fun changeFilter(filterType: MsgFeedFilter) {
        if (_uiState.value.filterType == filterType) return
        // 审查 D5：切换筛选必须失效在途请求（reqId 递增）并复位 loadingMore——
        // 否则 (a) 在途 loadMore 未结束时 refresh 被 loading 守卫挡掉，新筛选空列表
        // 无加载指示；(b) 旧筛选响应因 reqId 未变而穿过守卫，把旧 items+cursor 写进
        // 新筛选状态（load 的 update 块内另有 filterType 双保险）。
        reqId += 1L
        _uiState.update {
            it.copy(
                filterType = filterType,
                items = emptyList(),
                cursor = null,
                isLoadingMore = false,
                canLoadMore = false,
                loadMoreError = null
            )
        }
        refresh(forceLoading = true)
    }

    fun initLoad() {
        if (_uiState.value.items.isNotEmpty()) return
        refresh(forceLoading = true)
    }

    fun refresh(forceLoading: Boolean = false) {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || state.isLoadingMore) return
        reqId += 1L
        _uiState.update {
            it.copy(
                isLoading = forceLoading || it.items.isEmpty(),
                isRefreshing = !forceLoading && it.items.isNotEmpty(),
                isLoadingMore = false,
                errorMessage = null,
                loadMoreError = null
            )
        }
        viewModelScope.launch {
            load(reset = true)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.canLoadMore || state.isLoading || state.isRefreshing || state.isLoadingMore) return
        reqId += 1L
        _uiState.update {
            it.copy(
                isLoadingMore = true,
                loadMoreError = null
            )
        }
        viewModelScope.launch {
            load(reset = false)
        }
    }

    private suspend fun load(reset: Boolean) {
        val callId = reqId
        val state = _uiState.value
        try {
            val page = imRepo.fetchMsgFeedList(
                filterType = state.filterType,
                cursor = if (reset) null else state.cursor
            )
            if (callId != reqId) return
            _uiState.update { it ->
                // 审查 D5 双保险：响应在途期间筛选可能又被切走（reqId 相同的窗口内），
                // 写回前校验身份，旧筛选的数据不进新筛选的列表
                if (it.filterType != state.filterType) return@update it
                // 按 msgId 去重：服务端翻页窗口可能返回重复消息，重复 key 会让 LazyColumn 崩溃
                val newItems = if (reset) {
                    page.msgCards.distinctBy(MsgFeedItem::msgId)
                } else {
                    (it.items + page.msgCards).distinctBy(MsgFeedItem::msgId)
                }
                val newCursor = page.cursor
                it.copy(
                    items = newItems,
                    cursor = newCursor,
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    canLoadMore = newCursor != null && !newCursor.isEnd,
                    errorMessage = null,
                    loadMoreError = null
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (callId != reqId) return
            val msg = e.message.takeIf { !it.isNullOrBlank() } ?: if (reset) "加载失败" else "加载更多失败"
            Logger.e("MsgFeedViewModel", e) { msg }
            _uiState.update {
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
}
