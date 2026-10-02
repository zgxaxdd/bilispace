package com.naaammme.bbspace.feature.comment

import androidx.compose.runtime.Immutable
import com.naaammme.bbspace.core.model.COMMENT_FILTER_ALL
import com.naaammme.bbspace.core.model.CommentFilterTag
import com.naaammme.bbspace.core.model.CommentReply
import com.naaammme.bbspace.core.model.CommentSort
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.feature.comment.thread.CommentThreadState

@Immutable
data class CommentUiState(
    val subject: CommentSubject? = null,
    val currentMid: Long = 0L,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    // AP-19：busyReplyIds 已从 UiState 拆出（Set 是不稳定类型，放这里会让每次动作
    // 触发两轮全可见卡片重组）—— 现住在 CommentViewModel.busyReplyIds（snapshot state），
    // 卡片经 CommentViewModel.isReplyBusy + derivedStateOf "自己查自己"。
    val error: String? = null,
    val loadMoreError: String? = null,
    val title: String = "评论",
    val count: Long = 0L,
    val sort: CommentSort = CommentSort.HOT,
    val canSwitchSort: Boolean = true,
    val filterTags: List<CommentFilterTag> = emptyList(),
    val selectedFilter: String = COMMENT_FILTER_ALL,
    val items: List<CommentReply> = emptyList(),
    val threadPane: CommentThreadState? = null,
    val replyCheckDialogText: String? = null,
    val nextOffset: String? = null,
    val endText: String? = null
) {
    val hasMore: Boolean
        get() = nextOffset != null
}
