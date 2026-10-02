package com.naaammme.bbspace.feature.comment

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.auth.AuthRepository
import com.naaammme.bbspace.core.comment.CommentRepository
import com.naaammme.bbspace.core.comment.ImageAttachmentHandler
import com.naaammme.bbspace.core.model.COMMENT_FILTER_ALL
import com.naaammme.bbspace.core.model.CommentPage
import com.naaammme.bbspace.core.model.CommentReply
import com.naaammme.bbspace.core.model.CommentSort
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.CommentUser
import com.naaammme.bbspace.core.model.PublishedRecord
import com.naaammme.bbspace.feature.comment.editor.COMMENT_EDITOR_MAX_IMAGE_COUNT
import com.naaammme.bbspace.feature.comment.editor.CommentEditorState
import com.naaammme.bbspace.feature.comment.editor.CommentEditorTarget
import com.naaammme.bbspace.feature.comment.thread.CommentThreadState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.naaammme.bbspace.core.common.error.UserFacingError

@HiltViewModel
class CommentViewModel @Inject constructor(
    private val imageHandler: ImageAttachmentHandler,
    private val repo: CommentRepository,
    authRepo: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState = _uiState.asStateFlow()
    private val _editorState = MutableStateFlow(CommentEditorState())
    val editorState = _editorState.asStateFlow()
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val msg = _msg.asSharedFlow()

    /**
     * AP-19：进行中的操作（点赞/翻译/删除等）rpid 集合。
     *
     * 从 [CommentUiState] 拆出来放进 snapshot state 的原因：Set 是**不稳定类型**，
     * 作为 UiState 字段时每次动作都换一个新集合 → 全可见卡片相等性失败 →
     * 每个动作触发两轮全可见卡片重组。现在卡片经 [isReplyBusy]（**终身一个实例**
     * 的稳定闭包）+ 卡片内 derivedStateOf 自查，只有自身 busy 翻转的那张卡重组。
     */
    private val busyReplyIds = mutableStateOf<Set<Long>>(emptySet())

    /** 卡片侧"自己查自己"的唯一入口；闭包实例稳定，可安全作为组合参数。 */
    val isReplyBusy: (Long) -> Boolean = { rpid -> rpid in busyReplyIds.value }

    private var reqId = 0L

    /**
     * 楼中楼请求代数（审查 D4）：主列表有 reqId 守卫，楼中楼路径原本只有
     * subject/线程身份匹配——同线程的"排序切换"与"在途翻页"两个请求都能穿过，
     * 响应乱序时旧代数的 append 会 merge 进新列表并把 sort/nextOffset 回写成旧值。
     * 发起时递增并捕获，写回前比对，代数不符即丢弃。
     */
    private var threadReqId = 0L

    init {
        viewModelScope.launch {
            authRepo.currentMidFlow.collectLatest { mid ->
                _uiState.update { cur ->
                    cur.copy(currentMid = mid)
                }
            }
        }
    }

    fun bind(subject: CommentSubject?) {
        val state = _uiState.value
        if (state.subject == subject) return
        val currentMid = state.currentMid
        if (subject == null) {
            reqId += 1L
            _uiState.value = CommentUiState(currentMid = currentMid)
            _editorState.value = CommentEditorState()
            return
        }
        if (state.subject != null) {
            _editorState.update {
                it.copy(
                    visible = false,
                    loading = false,
                    target = CommentEditorTarget()
                )
            }
        }
        refresh(
            subject = subject,
            sort = CommentSort.HOT,
            filter = COMMENT_FILTER_ALL
        )
    }

    fun bindDetail(record: PublishedRecord?) {
        val currentMid = _uiState.value.currentMid
        if (record == null) {
            reqId += 1L
            _uiState.value = CommentUiState(currentMid = currentMid)
            _editorState.value = CommentEditorState()
            return
        }
        // 从发布记录构造评论参数：targetId/type 定位评论区，rootId 定位回复线程
        val subject = CommentSubject(oid = record.targetId, type = record.targetType)
        val rootRpid = record.rootId.takeIf { it > 0L } ?: record.itemId
        reqId += 1L
        _editorState.value = CommentEditorState()
        _uiState.value = CommentUiState(
            subject = subject,
            currentMid = currentMid,
            threadPane = CommentThreadState(
                title = "评论详情",
                rootRpid = rootRpid,
                root = CommentReply(
                    rpid = record.itemId,
                    message = record.content,
                    timeText = "",
                    user = CommentUser(
                        mid = record.senderMid,
                        name = record.senderName,
                        face = record.senderAvatar
                    )
                ),
                sort = CommentSort.HOT,
                loading = true,
                highlightRpid = record.itemId
            )
        )
        fetchReplyThread(
            subject = subject,
            rootRpid = rootRpid,
            rpid = record.itemId,
            sort = CommentSort.HOT,
            offset = "",
            append = false
        )
    }

    fun selectSort(sort: CommentSort) {
        val state = _uiState.value
        val subject = state.subject ?: return
        if (state.sort == sort && state.items.isNotEmpty()) return
        refresh(
            subject = subject,
            sort = sort,
            filter = state.selectedFilter
        )
    }

    fun loadMore() {
        val state = _uiState.value
        val subject = state.subject ?: return
        val nextOffset = state.nextOffset ?: return
        if (state.loading || state.loadingMore) return
        val callId = reqId
        _uiState.update {
            it.copy(
                loadingMore = true,
                loadMoreError = null
            )
        }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.fetchMainPage(
                    subject = subject,
                    sort = state.sort,
                    filterTag = state.selectedFilter,
                    offset = nextOffset
                )
            }
            if (callId != reqId) return@launch
            result.fold(
                onSuccess = { page ->
                    _uiState.update { cur ->
                        cur.copy(
                            loading = false,
                            loadingMore = false,
                            error = null,
                            loadMoreError = null,
                            title = page.title,
                            count = page.count,
                            sort = page.sort,
                            canSwitchSort = page.canSwitchSort,
                            filterTags = page.filterTags,
                            selectedFilter = page.selectedFilter,
                            items = mergeReplies(cur.items, page.items),
                            nextOffset = page.nextOffset,
                            endText = page.endText
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            loadingMore = false,
                            loadMoreError = UserFacingError.messageOf(err)
                        )
                    }
                }
            )
        }
    }

    fun openReplyThread(reply: CommentReply) {
        val state = _uiState.value
        val subject = state.subject ?: return
        val sort = CommentSort.HOT
        _uiState.update {
            it.copy(
                threadPane = CommentThreadState(
                    rootRpid = reply.rpid,
                    root = reply,
                    count = reply.replyCount,
                    sort = sort,
                    loading = true,
                    items = emptyList()
                )
            )
        }
        fetchReplyThread(
            subject = subject,
            rootRpid = reply.rpid,
            rpid = reply.rpid,
            sort = sort,
            offset = "",
            append = false
        )
    }

    fun closeReplyThread() {
        _uiState.update {
            it.copy(threadPane = null)
        }
    }

    fun loadMoreReplyThread() {
        val state = _uiState.value
        val subject = state.subject ?: return
        val thread = state.threadPane ?: return
        val nextOffset = thread.nextOffset ?: return
        if (thread.loading || thread.loadingMore) return
        _uiState.update {
            it.copy(
                threadPane = thread.copy(
                    loadingMore = true,
                    loadMoreError = null
                )
            )
        }
        fetchReplyThread(
            subject = subject,
            rootRpid = thread.rootRpid,
            rpid = thread.root.rpid,
            sort = thread.sort,
            offset = nextOffset,
            append = true
        )
    }

    fun toggleReplyThreadSort() {
        val state = _uiState.value
        val subject = state.subject ?: return
        val thread = state.threadPane ?: return
        if (!thread.canSwitchSort) return
        val nextSort = if (thread.sort == CommentSort.HOT) {
            CommentSort.TIME
        } else {
            CommentSort.HOT
        }
        _uiState.update {
            it.copy(
                threadPane = thread.copy(
                    sort = nextSort,
                    loading = true,
                    loadingMore = false,
                    error = null,
                    loadMoreError = null,
                    items = emptyList(),
                    nextOffset = null
                )
            )
        }
        fetchReplyThread(
            subject = subject,
            rootRpid = thread.rootRpid,
            rpid = thread.root.rpid,
            sort = nextSort,
            offset = "",
            append = false
        )
    }

    fun translateReply(rpid: Long) {
        val state = _uiState.value
        val subject = state.subject ?: return
        if (isReplyBusy(rpid)) return
        busyReplyIds.value = busyReplyIds.value + rpid
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.fetchTranslatedReply(subject, rpid)
            }
            if (_uiState.value.subject != subject) return@launch
            busyReplyIds.value = busyReplyIds.value - rpid
            result.fold(
                onSuccess = { translated ->
                    when {
                        translated == null -> _msg.tryEmit("评论已不存在")
                        translated.isBlank() -> _msg.tryEmit("切换语言为英文以支持中文翻译") // TODO:支持动态 x-bili-locale-bin, 从而实现汉译英汉,英译日等
                        else -> {
                            _uiState.update { cur ->
                                cur.copy(
                                    items = updateTranslatedReplies(cur.items, rpid, translated),
                                    threadPane = cur.threadPane?.let { thread ->
                                        thread.copy(
                                            root = if (thread.root.rpid == rpid) {
                                                thread.root.copy(translatedMessage = translated)
                                            } else {
                                                thread.root
                                            },
                                            items = updateTranslatedReplies(
                                                thread.items,
                                                rpid,
                                                translated
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                },
                onFailure = { err ->
                    _msg.tryEmit(err.message ?: "评论翻译失败")
                }
            )
        }
    }

    fun checkReply(rpid: Long) {
        val state = _uiState.value
        val subject = state.subject ?: return
        if (isReplyBusy(rpid)) return
        busyReplyIds.value = busyReplyIds.value + rpid
        _uiState.update { it.copy(replyCheckDialogText = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.fetchReplyInfo(rpid)
            }
            if (_uiState.value.subject != subject) return@launch
            busyReplyIds.value = busyReplyIds.value - rpid
            result.fold(
                onSuccess = { reply ->
                    _uiState.update {
                        it.copy(
                            replyCheckDialogText = if (reply == null) {
                                "评论不可见"
                            } else {
                                "评论正常"
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _msg.tryEmit(err.message ?: "评论检查失败")
                }
            )
        }
    }

    fun deleteReply(reply: CommentReply) {
        val state = _uiState.value
        val subject = state.subject ?: return
        if (isReplyBusy(reply.rpid)) return
        busyReplyIds.value = busyReplyIds.value + reply.rpid
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.deleteReply(subject, reply.rpid)
            }
            if (_uiState.value.subject != subject) return@launch
            busyReplyIds.value = busyReplyIds.value - reply.rpid
            result.fold(
                onSuccess = {
                    _uiState.update { cur -> removeDeletedReply(cur, reply) }
                    _msg.tryEmit("评论已删除")
                },
                onFailure = { err ->
                    _msg.tryEmit(err.message ?: "删除评论失败")
                }
            )
        }
    }

    /** 评论点赞/取消点赞 */
    fun likeReply(reply: CommentReply) {
        val wantLike = !reply.liked
        toggleReplyRate(
            reply = reply,
            repoCall = { subject -> repo.likeReply(subject, reply.rpid, wantLike) },
            successText = if (wantLike) "点赞成功" else "已取消点赞",
            failureText = "点赞失败",
            transform = { target ->
                val delta = when {
                    wantLike && !target.liked -> 1L
                    !wantLike && target.liked -> -1L
                    else -> 0L
                }
                target.copy(
                    liked = wantLike,
                    disliked = if (wantLike) false else target.disliked,
                    likeCount = (target.likeCount + delta).coerceAtLeast(0L)
                )
            }
        )
    }

    /** 评论点踩/取消点踩（与点赞互斥，点踩会取消已点赞） */
    fun dislikeReply(reply: CommentReply) {
        val wantHate = !reply.disliked
        toggleReplyRate(
            reply = reply,
            repoCall = { subject -> repo.hateReply(subject, reply.rpid, wantHate) },
            successText = if (wantHate) "已点踩" else "已取消点踩",
            failureText = "点踩失败",
            transform = { target ->
                val delta = if (wantHate && target.liked) -1L else 0L
                target.copy(
                    disliked = wantHate,
                    liked = if (wantHate) false else target.liked,
                    likeCount = (target.likeCount + delta).coerceAtLeast(0L)
                )
            }
        )
    }

    private fun toggleReplyRate(
        reply: CommentReply,
        repoCall: suspend (CommentSubject) -> Unit,
        successText: String,
        failureText: String,
        transform: (CommentReply) -> CommentReply
    ) {
        val state = _uiState.value
        val subject = state.subject ?: return
        if (state.currentMid <= 0L) {
            _msg.tryEmit("请先登录")
            return
        }
        if (isReplyBusy(reply.rpid)) return
        busyReplyIds.value = busyReplyIds.value + reply.rpid
        viewModelScope.launch {
            val result = runCatchingCancellable { repoCall(subject) }
            if (_uiState.value.subject != subject) return@launch
            busyReplyIds.value = busyReplyIds.value - reply.rpid
            result.fold(
                onSuccess = {
                    _uiState.update { cur -> updateReplyRate(cur, reply.rpid, transform) }
                    _msg.tryEmit(successText)
                },
                onFailure = { err ->
                    _msg.tryEmit(err.message ?: failureText)
                }
            )
        }
    }

    private fun updateReplyRate(
        state: CommentUiState,
        rpid: Long,
        transform: (CommentReply) -> CommentReply
    ): CommentUiState {
        val thread = state.threadPane
        val nextItems = state.items.map { item ->
            if (item.rpid == rpid) transform(item) else item
        }
        if (thread == null) {
            return state.copy(items = nextItems)
        }
        return state.copy(
            items = nextItems,
            threadPane = thread.copy(
                root = if (thread.root.rpid == rpid) transform(thread.root) else thread.root,
                items = thread.items.map { item ->
                    if (item.rpid == rpid) transform(item) else item
                }
            )
        )
    }

    fun openEditor() {
        val state = _uiState.value
        if (state.subject == null) return
        if (state.currentMid <= 0L) {
            _msg.tryEmit("请先登录")
            return
        }
        showEditor(
            target = state.threadPane?.let { thread ->
                CommentEditorTarget(
                    rootRpid = thread.rootRpid,
                    parentRpid = thread.root.rpid,
                    parentName = thread.root.user.name.ifBlank { null }
                )
            } ?: CommentEditorTarget()
        )
    }

    fun replyTo(reply: CommentReply) {
        val state = _uiState.value
        if (state.subject == null) return
        if (state.currentMid <= 0L) {
            _msg.tryEmit("请先登录")
            return
        }
        showEditor(
            target = replyTarget(state, reply)
        )
    }

    fun dismissEditor() {
        val editor = _editorState.value
        if (!editor.visible) return
        // 不再以 loading 拒绝：ModalBottomSheet 在下拉/返回时已自行隐藏并回调
        // onDismissRequest（见 CommentEditorSheet 的注释），此刻 visible 必须复位，
        // 否则隐藏的 popup 壳驻留组合树拦截全屏输入（僵尸弹窗）。提交中的请求
        // 照常完成并经 toast/列表刷新反馈。
        _editorState.update {
            it.copy(
                visible = false,
                target = CommentEditorTarget()
            )
        }
    }

    fun dismissReplyCheckDialog() {
        if (_uiState.value.replyCheckDialogText == null) return
        _uiState.update {
            it.copy(replyCheckDialogText = null)
        }
    }

    fun updateEditorInput(value: String) {
        _editorState.update {
            it.copy(input = value)
        }
    }

    fun addImages(uris: List<Uri>) {
        _editorState.update {
            it.copy(selectedImageUris = (it.selectedImageUris + uris).distinct().take(COMMENT_EDITOR_MAX_IMAGE_COUNT))
        }
    }

    fun removeImage(index: Int) {
        _editorState.update {
            it.copy(selectedImageUris = it.selectedImageUris.toMutableList().apply {
                if (index in indices) removeAt(index)
            })
        }
    }

    fun submitEditor() {
        val state = _uiState.value
        val subject = state.subject ?: return
        if (state.currentMid <= 0L) {
            _msg.tryEmit("请先登录")
            return
        }
        val editor = _editorState.value
        if (editor.loading || editor.uploading) return
        val text = editor.input.trim()
        if (text.isBlank()) {
            _msg.tryEmit("评论内容不能为空")
            return
        }
        val callId = reqId
        val target = editor.target
        val sort = state.threadPane
            ?.takeIf { it.rootRpid == target.rootRpid && target.rootRpid > 0L }
            ?.sort
            ?: state.sort
        _editorState.update {
            it.copy(loading = true)
        }
        viewModelScope.launch {
            val picturesJson = if (editor.selectedImageUris.isNotEmpty()) {
                _editorState.update { it.copy(uploading = true) }
                val result = runCatchingCancellable {
                    val pics = mutableListOf<JSONObject>()
                    editor.selectedImageUris.forEachIndexed { index, uri ->
                        val prepared = imageHandler.prepare(uri)
                        val resp = repo.uploadImage(prepared.bytes, index, prepared.mimeType)
                        val data = resp.getJSONObject("data")
                        pics.add(JSONObject().apply {
                            put("ai_gen_pic", data.optInt("ai_gen_pic", 0))
                            put("img_height", data.getInt("image_height"))
                            put("img_size", data.getDouble("img_size"))
                            put("img_src", data.getString("image_url"))
                            put("img_width", data.getInt("image_width"))
                        })
                    }
                    JSONArray(pics).toString().replace("\\/", "/")
                }
                _editorState.update { it.copy(uploading = false) }
                if (callId != reqId || _uiState.value.subject != subject) return@launch
                result.fold(
                    onSuccess = { json -> json },
                    onFailure = { err ->
                        _editorState.update { it.copy(loading = false) }
                        _msg.tryEmit(err.message ?: "图片上传失败")
                        return@launch
                    }
                )
            } else {
                null
            }
            val publishResult = runCatchingCancellable {
                repo.publishReply(
                    subject = subject,
                    message = text,
                    rootRpid = target.rootRpid,
                    parentRpid = target.parentRpid,
                    sort = sort,
                    pictures = picturesJson
                )
            }
            if (callId != reqId || _uiState.value.subject != subject) return@launch
            publishResult.fold(
                onSuccess = { published ->
                    _editorState.value = CommentEditorState()
                    _uiState.update {
                        insertPublishedReply(
                            state = it,
                            target = target,
                            reply = published
                        )
                    }
                    _msg.tryEmit(if (target.isReply) "回复已发送" else "评论已发送")
                },
                onFailure = { err ->
                    _editorState.update {
                        it.copy(loading = false)
                    }
                    _msg.tryEmit(err.message ?: "发送评论失败")
                }
            )
        }
    }

    private fun refresh(
        subject: CommentSubject,
        sort: CommentSort,
        filter: String
    ) {
        val callId = reqId + 1L
        reqId = callId
        val currentMid = _uiState.value.currentMid
        _uiState.value = CommentUiState(
            subject = subject,
            currentMid = currentMid,
            loading = true,
            sort = sort,
            selectedFilter = filter
        )
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.fetchMainPage(
                    subject = subject,
                    sort = sort,
                    filterTag = filter
                )
            }
            if (callId != reqId) return@launch
            result.fold(
                onSuccess = { page ->
                    _uiState.value = page.toUiState(currentMid = _uiState.value.currentMid)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = UserFacingError.messageOf(err)
                        )
                    }
                }
            )
        }
    }

    private fun fetchReplyThread(
        subject: CommentSubject,
        rootRpid: Long,
        rpid: Long,
        sort: CommentSort,
        offset: String,
        append: Boolean
    ) {
        threadReqId += 1L
        val callId = threadReqId
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.fetchReplyDetail(
                    subject = subject,
                    rootRpid = rootRpid,
                    rpid = rpid,
                    sort = sort,
                    offset = offset
                )
            }
            if (_uiState.value.subject != subject) return@launch
            if (callId != threadReqId) return@launch
            result.fold(
                onSuccess = { page ->
                    _uiState.update { cur ->
                        val thread = cur.threadPane
                            ?.takeIf { it.rootRpid == rootRpid && it.root.rpid == rpid }
                            ?: return@update cur
                        val items = if (append) {
                            mergeReplies(thread.items, page.items)
                        } else {
                            val curMap = thread.items.associateBy { it.rpid }
                            page.items.map { reply -> reply.copy(translatedMessage = curMap[reply.rpid]?.translatedMessage ?: reply.translatedMessage) }
                        }
                        cur.copy(
                            threadPane = thread.copy(
                                root = page.root.copy(translatedMessage = thread.root.translatedMessage ?: page.root.translatedMessage),
                                count = page.count,
                                sort = page.sort,
                                canSwitchSort = page.canSwitchSort,
                                loading = false,
                                loadingMore = false,
                                error = null,
                                loadMoreError = null,
                                items = items,
                                nextOffset = page.nextOffset
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (callId != threadReqId) return@fold
                    val msg = err.message ?: if (append) {
                        "加载更多回复失败"
                    } else {
                        "加载回复失败"
                    }
                    _uiState.update { cur ->
                        val thread = cur.threadPane
                            ?.takeIf { it.rootRpid == rootRpid && it.root.rpid == rpid }
                            ?: return@update cur
                        cur.copy(
                            threadPane = if (append) {
                                thread.copy(
                                    loading = false,
                                    loadingMore = false,
                                    loadMoreError = msg
                                )
                            } else {
                                thread.copy(
                                    loading = false,
                                    loadingMore = false,
                                    error = msg
                                )
                            }
                        )
                    }
                }
            )
        }
    }

    private fun showEditor(target: CommentEditorTarget) {
        _editorState.update {
            if (it.loading) return@update it
            it.copy(
                visible = true,
                target = target
            )
        }
    }

    private fun replyTarget(
        state: CommentUiState,
        reply: CommentReply
    ): CommentEditorTarget {
        val thread = state.threadPane
        return if (thread == null || thread.root.rpid == reply.rpid) {
            CommentEditorTarget(
                rootRpid = thread?.rootRpid ?: reply.rpid,
                parentRpid = reply.rpid,
                parentName = reply.user.name.ifBlank { null }
            )
        } else {
            CommentEditorTarget(
                rootRpid = thread.rootRpid,
                parentRpid = reply.rpid,
                parentName = reply.user.name.ifBlank { null }
            )
        }
    }

    private fun CommentPage.toUiState(currentMid: Long): CommentUiState {
        return CommentUiState(
            subject = subject,
            currentMid = currentMid,
            loading = false,
            title = title,
            count = count,
            sort = sort,
            canSwitchSort = canSwitchSort,
            filterTags = filterTags,
            selectedFilter = selectedFilter,
            items = items,
            nextOffset = nextOffset,
            endText = endText
        )
    }

        private fun mergeReplies(
        current: List<CommentReply>,
        append: List<CommentReply>
    ): List<CommentReply> {
        if (current.isEmpty()) return append
        if (append.isEmpty()) return current
        val items = LinkedHashMap<Long, CommentReply>(current.size + append.size)
        current.forEach { reply -> items[reply.rpid] = reply }
        append.forEach { reply ->
            val cur = items[reply.rpid]
            items[reply.rpid] = if (cur == null) {
                reply
            } else {
                reply.copy(translatedMessage = cur.translatedMessage ?: reply.translatedMessage)
            }
        }
        return items.values.toList()
    }

    private fun updateTranslatedReplies(
        items: List<CommentReply>,
        rpid: Long,
        translated: String
    ): List<CommentReply> {
        if (items.isEmpty()) return items
        var changed = false
        val next = items.map { reply ->
            if (reply.rpid == rpid) {
                changed = true
                reply.copy(translatedMessage = translated)
            } else {
                reply
            }
        }
        return if (changed) next else items
    }

    private fun insertPublishedReply(
        state: CommentUiState,
        target: CommentEditorTarget,
        reply: CommentReply
    ): CommentUiState {
        val nextCount = state.count + 1L
        if (!target.isReply) {
            val insertIndex = state.items.indexOfFirst { it.topLabel == null }
                .let { index -> if (index >= 0) index else state.items.size }
            return state.copy(
                count = nextCount,
                items = state.items.toMutableList().apply {
                    add(insertIndex, reply)
                }
            )
        }

        val thread = state.threadPane
        if (thread != null && thread.rootRpid == target.rootRpid) {
            val nextRoot = thread.root.copy(
                replyCount = thread.root.replyCount + 1L,
                replyEntryText = null
            )
            val insertIndex = if (target.parentRpid == target.rootRpid) {
                0
            } else {
                (thread.items.indexOfFirst { it.rpid == target.parentRpid } + 1).coerceAtLeast(0)
            }
            return state.copy(
                count = nextCount,
                items = state.items.map { item ->
                    if (item.rpid == thread.rootRpid) {
                        nextRoot
                    } else {
                        item
                    }
                },
                threadPane = thread.copy(
                    root = nextRoot,
                    count = thread.count + 1L,
                    items = thread.items.toMutableList().apply {
                        add(insertIndex.coerceAtMost(size), reply)
                    }
                )
            )
        }

        return state.copy(
            count = nextCount,
            items = state.items.map { item ->
                if (item.rpid == target.rootRpid) {
                    item.copy(
                        replyCount = item.replyCount + 1L,
                        replyEntryText = null
                    )
                } else {
                    item
                }
            }
        )
    }

    private fun removeDeletedReply(
        state: CommentUiState,
        reply: CommentReply
    ): CommentUiState {
        val thread = state.threadPane
        if (thread != null && thread.root.rpid != reply.rpid) {
            val nextThreadItems = thread.items.filterNot { it.rpid == reply.rpid }
            if (nextThreadItems.size != thread.items.size) {
                val nextRoot = thread.root.copy(
                    replyCount = (thread.root.replyCount - 1L).coerceAtLeast(0L),
                    replyEntryText = null
                )
                return state.copy(
                    count = (state.count - 1L).coerceAtLeast(0L),
                    items = state.items.map { item ->
                        if (item.rpid == thread.rootRpid) {
                            nextRoot
                        } else {
                            item
                        }
                    },
                    threadPane = thread.copy(
                        root = nextRoot,
                        count = (thread.count - 1L).coerceAtLeast(0L),
                        items = nextThreadItems
                    )
                )
            }
        }

        val nextItems = state.items.filterNot { it.rpid == reply.rpid }
        val removedFromMain = nextItems.size != state.items.size
        val isThreadRoot = thread?.root?.rpid == reply.rpid
        if (!removedFromMain && !isThreadRoot) return state
        return state.copy(
            count = (state.count - 1L).coerceAtLeast(0L),
            items = nextItems,
            threadPane = if (isThreadRoot) null else thread
        )
    }
}
