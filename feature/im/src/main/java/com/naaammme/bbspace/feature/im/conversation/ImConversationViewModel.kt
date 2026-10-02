package com.naaammme.bbspace.feature.im.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.data.mergePagesUnique
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.im.ImRepository
import com.naaammme.bbspace.core.model.ImConversationPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.naaammme.bbspace.core.common.error.UserFacingError

@HiltViewModel
class ImConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val imRepo: ImRepository
) : ViewModel() {

    private val talkerId = savedStateHandle.get<Long>("talkerId") ?: 0L
    private val sessionType = savedStateHandle.get<Int>("sessionType") ?: 0
    private val routeTitle = savedStateHandle.get<String>("title").orEmpty()
    private val routeAvatar = savedStateHandle.get<String>("avatar")?.takeIf(String::isNotBlank)

    private val _uiState = MutableStateFlow(ImConversationUiState())
    val uiState: StateFlow<ImConversationUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = ImConversationUiState(
            title = routeTitle,
            avatar = routeAvatar
        )
        refresh()
    }

    fun refresh() {
        val state = _uiState.value
        if (state.isRefreshing || state.isLoadingMore) return
        _uiState.update {
            it.copy(
                isLoading = it.messages.isEmpty(),
                isRefreshing = it.messages.isNotEmpty()
            )
        }
        viewModelScope.launch {
            try {
                val page = imRepo.fetchConversation(
                    talkerId = talkerId,
                    sessionType = sessionType
                )
                applyConversation(page)
                reportAck(page.messages.maxOfOrNull { it.seqNo })
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // 原来是 `catch (_: Throwable)`：无日志、无提示 —— 弱网下打开会话
                // 表现为"转圈停住 + 列表空白 + 什么都没有"，且复现时查不到任何线索。
                // 同文件的 sendMessage 做得完全正确（Logger.e + UserFacingError 上屏），
                // 这里补齐成一致的处理。
                Logger.e(TAG, t) { "fetchConversation failed talkerId=$talkerId" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        loadErrorMessage = UserFacingError.messageOf(t)
                    )
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMoreHistory) return
        val beforeSeqNo = state.messages.lastOrNull()?.seqNo ?: return
        _uiState.update {
            it.copy(
                isLoadingMore = true
            )
        }
        viewModelScope.launch {
            try {
                val page = imRepo.fetchOlderMessages(
                    talkerId = talkerId,
                    sessionType = sessionType,
                    beforeSeqNo = beforeSeqNo
                )
                applyOlderMessages(page)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // 翻页失败原先是静默的（只关掉 loading），用户看到"滑到底就不动了"。
                Logger.e(TAG, t) { "fetchOlderMessages failed beforeSeqNo=$beforeSeqNo" }
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadErrorMessage = UserFacingError.messageOf(t)
                    )
                }
            }
        }
    }

    fun clearSendError() {
        _uiState.update { it.copy(sendErrorMessage = null) }
    }

    fun sendMessage(text: String) {
        val state = _uiState.value
        if (state.isSending || text.isBlank()) return
        if (talkerId <= 0L || sessionType <= 0) {
            _uiState.update { it.copy(sendErrorMessage = "会话信息无效") }
            return
        }
        _uiState.update { it.copy(isSending = true, sendErrorMessage = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                imRepo.sendConversationMessage(
                    talkerId = talkerId,
                    sessionType = sessionType,
                    text = text
                )
            }
            result.fold(
                onSuccess = { message ->
                    _uiState.update { cur ->
                        cur.copy(
                            messages = listOf(message) + cur.messages,
                            isSending = false,
                            sendErrorMessage = null,
                            lastSentMessageKey = message.key
                        )
                    }
                },
                onFailure = { err ->
                    Logger.e(TAG, err) {
                        "send im message failed talkerId=$talkerId sessionType=$sessionType"
                    }
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            sendErrorMessage = UserFacingError.messageOf(err)
                        )
                    }
                }
            )
        }
    }

    private fun reportAck(seqNo: Long?) {
        val ackSeqNo = seqNo?.takeIf { it > 0L } ?: return
        viewModelScope.launch {
            // 已读回执是 **best-effort**：失败不该影响任何东西，更不能崩。
            //
            // 这里原本没有 try/catch，而 `ImRepository.updateAck` 内部走
            // `BiliGrpcClient.call`，那是 **throw 语义**（不是返回 Result）。
            // `viewModelScope` 是 SupervisorJob + Main.immediate，未捕获异常会冒到
            // 全局处理器；项目没有装 CoroutineExceptionHandler，BiliApplication 的
            // 处理器只是记日志后转交默认处理器 —— 即照样崩溃。
            // 触发路径很普通：弱网/断网下打开一个未读会话。
            try {
                imRepo.updateAck(
                    talkerId = talkerId,
                    sessionType = sessionType,
                    ackSeqNo = ackSeqNo
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Logger.w(TAG) { "updateAck failed talkerId=$talkerId ack=$ackSeqNo: ${t.message}" }
            }
        }
    }

    private fun applyConversation(
        page: ImConversationPage
    ) {
        _uiState.update { state ->
            val messages = page.messages
            state.copy(
                title = state.title.ifBlank { routeTitle },
                avatar = state.avatar ?: routeAvatar,
                hasMoreHistory = page.hasMoreHistory,
                messages = messages,
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false
            )
        }
    }

    private fun applyOlderMessages(page: ImConversationPage) {
        _uiState.update { state ->
            // AP-11：翻页窗口重叠时服务端会回放同 key 消息，重复 key 会让 LazyColumn
            // 崩溃（范式：ImViewModel.mergeSessions / HistoryViewModel 的 distinctBy）
            val messages = mergePagesUnique(state.messages, page.messages, reset = false) { msg -> msg.key }
            state.copy(
                hasMoreHistory = page.hasMoreHistory,
                messages = messages,
                isLoadingMore = false
            )
        }
    }

    private companion object {
        const val TAG = "ImConversationViewModel"
    }
}
