package com.naaammme.bbspace.feature.im.conversation

import androidx.compose.runtime.Immutable
import com.naaammme.bbspace.core.model.ImMessage

@Immutable
data class ImConversationUiState(
    val title: String = "",
    val avatar: String? = null,
    val messages: List<ImMessage> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSending: Boolean = false,
    val sendErrorMessage: String? = null,
    /**
     * 拉取（首屏 / 刷新 / 翻页）失败的用户可读文案。
     *
     * 此前拉取失败是**完全静默**的：只把 isLoading/isLoadingMore 关掉，既不记录日志
     * 也不上屏 —— 弱网下打开会话表现为"转圈停住 + 列表空白"，翻页失败表现为
     * "滑到底就不动了"，且复现时日志里查不到任何线索。与 [sendErrorMessage]
     * （发送失败，一直有正确提示）对齐。
     */
    val loadErrorMessage: String? = null,
    val lastSentMessageKey: Long? = null
)
