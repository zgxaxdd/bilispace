package com.naaammme.bbspace.feature.comment

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.designsystem.component.CommentCardSkeleton
import com.naaammme.bbspace.core.designsystem.component.CommentHeaderSkeleton
import com.naaammme.bbspace.core.designsystem.component.LoadMoreGrowthGuard
import com.naaammme.bbspace.core.designsystem.component.PreviewImage
import com.naaammme.bbspace.core.designsystem.component.ProvideSkeletonShimmer
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.PublishedRecord
import com.naaammme.bbspace.core.model.CommentSubjectTool
import com.naaammme.bbspace.core.model.CommentSort
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.CommentUser
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.PUBLISHED_RECORD_KIND_COMMENT
import com.naaammme.bbspace.core.model.PUBLISHED_RECORD_KIND_LIVE_DANMAKU
import com.naaammme.bbspace.core.model.PUBLISHED_RECORD_KIND_VIDEO_DANMAKU
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoSrc
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.feature.comment.component.CommentCard
import com.naaammme.bbspace.feature.comment.component.CommentReplyAction
import com.naaammme.bbspace.feature.comment.editor.CommentEditorFab
import com.naaammme.bbspace.feature.comment.editor.CommentEditorSheet
import com.naaammme.bbspace.feature.comment.thread.CommentThreadPane
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CommentPanel(
    subject: CommentSubject?,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    detailRecord: PublishedRecord? = null,
    onOpenSpace: (SpaceRoute) -> Unit = {},
    onOpenVideoDetail: (VideoTarget) -> Unit = {},
    onOpenDynamicDetail: (String) -> Unit = {},
    onOpenLiveDetail: (LiveRoute) -> Unit = {},
    onDismissDetail: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    threadListState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(
        horizontal = BiliSpacing.lg,
        vertical = BiliSpacing.md
    ),
    header: (@Composable () -> Unit)? = null,
    viewModel: CommentViewModel = hiltViewModel()
) {
    LaunchedEffect(isActive, subject, detailRecord?.key) {
        if (isActive) {
            if (detailRecord != null) {
                viewModel.bindDetail(detailRecord)
            } else if (subject != null) {
                viewModel.bind(subject)
            }
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 评论列表 / 楼层面板的动效时长与曲线统一走 token（上游 `76839cb22` 的"面板动效统一"
    // 在本仓的落点：插入位移、抽屉滑入、FAB 进出场同一条时间轴；速度档位与 reduceMotion 生效）。
    val anim = LocalAnimations.current
    val isDetailMode = detailRecord != null || (subject == null && uiState.threadPane != null)
    val layoutDirection = LocalLayoutDirection.current
    val replyThread = uiState.threadPane
    val isInitLoading = !isDetailMode && subject != null && uiState.loading && uiState.items.isEmpty()
    // A14：首屏骨架槽位按**视口高度**推导（上游 `resolveVideoCommentSkeletonSlotCount` 同判据）。
    // 此前是写死的 4 条：小屏铺不满、大屏不够铺，数据到达时列表被撑长 → 位移。
    //
    // 用本组件**实测高度**而不是 `LocalConfiguration.screenHeightDp`：评论区经常只占
    // 屏幕下半（竖屏连播的悬浮卡片）或右侧一栏，拿整屏高度会系统性多算一倍以上。
    // `onSizeChanged` 只在尺寸变化时回调，不进每帧路径。
    var viewportHeightDp by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val skeletonSlotCount = remember(viewportHeightDp) {
        resolveCommentSkeletonSlotCount(viewportHeightDp)
    }
    // A15：本屏此刻是否有骨架在场（首屏骨架 **或** 翻页骨架）—— 容器级微光时钟的门控条件。
    // 两者都不在场时 `ProvideSkeletonShimmer` 直接不 provide，`SkeletonBlock` 走静止分支，
    // 不留一条永不停止的无限动画给常驻列表。
    val hasSkeletonOnScreen = isInitLoading || uiState.loadingMore
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val scope = rememberCoroutineScope()
    val listContentPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(layoutDirection),
        bottom = contentPadding.calculateBottomPadding() + COMMENT_FAB_SPACE
    )

    var fabVisible by remember { mutableStateOf(true) }
    val shouldLoadMore by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            !isDetailMode &&
                uiState.threadPane == null &&
                uiState.hasMore &&
                !uiState.loading &&
                !uiState.loadingMore &&
                uiState.loadMoreError.isNullOrBlank() &&
                uiState.items.isNotEmpty() &&
                total > 0 &&
                last >= total - 3
        }
    }

    // AP-10：增长守卫 —— 谓词含 !uiState.loadingMore，effect 会随加载翻转重启；
    // 空页/全重复页（列表不增长）必须由守卫挡住。
    val loadMoreGuard = remember { LoadMoreGrowthGuard() }
    LaunchedEffect(uiState.items.size) {
        loadMoreGuard.onItemLineChanged(uiState.items.size)
    }

    LaunchedEffect(shouldLoadMore, uiState.loading, uiState.loadingMore) {
        loadMoreGuard.onVisibility(shouldLoadMore, busy = uiState.loading || uiState.loadingMore)
        if (shouldLoadMore && loadMoreGuard.tryAcquire()) {
            viewModel.loadMore()
        }
    }

    LaunchedEffect(replyThread != null) {
        fabVisible = true
    }

    LaunchedEffect(listState, threadListState, replyThread != null) {
        val activeListState = if (replyThread != null) threadListState else listState
        var lastPos = activeListState.firstVisibleItemIndex to activeListState.firstVisibleItemScrollOffset
        snapshotFlow {
            activeListState.firstVisibleItemIndex to activeListState.firstVisibleItemScrollOffset
        }
            .collectLatest { index ->
                if (index != lastPos) {
                    fabVisible = index.first < lastPos.first ||
                        (index.first == lastPos.first && index.second <= lastPos.second)
                    lastPos = index
                }
            }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.msg.collectLatest { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = replyThread != null && !isDetailMode) {
        viewModel.closeReplyThread()
    }

    val routeSubject = uiState.subject ?: subject
    val onReplyAction: (CommentReplyAction) -> Unit = remember(
        routeSubject,
        detailRecord?.key,
        onOpenSpace,
        onOpenLiveDetail,
        onOpenVideoDetail,
        onOpenDynamicDetail
    ) {
        { action ->
            when (action) {
                is CommentReplyAction.Check -> viewModel.checkReply(action.rpid)
                is CommentReplyAction.Translate -> viewModel.translateReply(action.rpid)
                is CommentReplyAction.Delete -> viewModel.deleteReply(action.reply)
                is CommentReplyAction.Reply -> viewModel.replyTo(action.reply)
                is CommentReplyAction.Like -> viewModel.likeReply(action.reply)
                is CommentReplyAction.Dislike -> viewModel.dislikeReply(action.reply)
                is CommentReplyAction.Share -> {
                    val subj = routeSubject
                    if (subj != null) {
                        val url = if (subj.type == CommentSubjectTool.TYPE_DYNAMIC) {
                            ShareUtils.dynamicUrl(subj.oid.toString())
                        } else {
                            ShareUtils.commentUrl(null, subj.oid, action.reply.rpid)
                        }
                        ShareUtils.shareText(context, "评论分享", url)
                    }
                }
                is CommentReplyAction.OpenReplies -> viewModel.openReplyThread(action.reply)
                is CommentReplyAction.OpenUser -> {
                    action.user.toSpaceRoute(routeSubject)?.let(onOpenSpace)
                }
                is CommentReplyAction.OpenOriginalContent -> {
                    val oid = detailRecord?.targetId ?: routeSubject?.oid ?: 0L
                    val type = detailRecord?.targetType ?: routeSubject?.type ?: 0
                    val kind = detailRecord?.kind
                    if (oid > 0L) {
                        when {
                            kind == PUBLISHED_RECORD_KIND_LIVE_DANMAKU -> {
                                onOpenLiveDetail(LiveRoute(roomId = oid))
                            }
                            kind == PUBLISHED_RECORD_KIND_VIDEO_DANMAKU ||
                            (kind == PUBLISHED_RECORD_KIND_COMMENT || kind == null) && type == CommentSubjectTool.TYPE_VIDEO -> {
                                onOpenVideoDetail(
                                    VideoTarget.Ugc(
                                        aid = oid,
                                        cid = 0L,
                                        src = VideoTargetTool.default()
                                    )
                                )
                            }
                            else -> onOpenDynamicDetail(oid.toString())
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // A14：量出本组件的可见高度（dp），供骨架槽位数推导。
            // `onSizeChanged` 只在尺寸变化时回调 —— 不进每帧路径，也不触发重组循环
            // （写回的是 Int，值不变时 `mutableStateOf` 不通知）。
            .onSizeChanged { size ->
                val heightDp = with(density) { size.height.toDp().value.toInt() }
                if (heightDp != viewportHeightDp) viewportHeightDp = heightDp
            }
    ) {
        if (isDetailMode) {
            if (replyThread != null) {
                CommentThreadPane(
                    state = replyThread,
                    listState = threadListState,
                    currentMid = uiState.currentMid,
                    isReplyBusy = viewModel.isReplyBusy,
                    onReplyAction = onReplyAction,
                    onDismiss = onDismissDetail,
                    onToggleSort = viewModel::toggleReplyThreadSort,
                    onLoadMore = viewModel::loadMoreReplyThread,
                    bottomPadding = COMMENT_FAB_SPACE,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // A15：骨架微光**容器级**提供一份共享时钟，并按「本屏此刻有没有骨架」门控。
            //
            // 缺陷形态：`CommentCardSkeleton` 自带幂等 provider，但那是"没人提供时自己起一条
            // 无限动画"。骨架铺在**真实列表**里（就地替换、`animateItem`），首屏 3~8 槽
            // 各起一条 `rememberInfiniteTransition`，正是 `SkeletonShimmerStructureTest`
            // 说的"调用点数不变、实例数失控"盲区。容器级 provider 让全部槽位共用一条（N → 1）。
            //
            // 门控条件覆盖**两种骨架态**（首屏 + 翻页），不只是首屏：
            // 翻页那 2 条骨架同样走 `items(count = …)` + 单卡骨架，不覆盖的话它们仍是
            // 各起一条（2 → 2，没有收敛）。用「任一骨架在场」作条件，两种态都收敛到 1 条，
            // 且骨架一消失就随组合销毁（不留待机空转的无限动画）。
            //
            // ⚠️ 与上游的**有意分歧**：上游 `d424d70a7` 的 `rememberStaticSkeletonBlockColor()`
            // 主张骨架**完全不呼吸**（理由原文：「呼吸中的灰块与随后的内容淡入叠加，观感上
            // 就是闪」）。本仓**不采纳**这条 —— 呼吸是 `SkeletonBlock` 的既定设计语言
            // （见其 KDoc：旧版斜向扫光在浅色主题下看不见，呼吸只改一个 alpha、更省），
            // 且本仓既有范式 `AdaptiveMediaGrid.kt:177` 就是「首屏骨架态才开微光」。
            // 这里只做**收敛**（N → 1）与**门控**（无骨架即不跑），不动观感。
            // 上游那条"静态色"若日后要采纳，是独立的一次设计决策，不要混在这次里。
            ProvideSkeletonShimmer(enabled = hasSkeletonOnScreen) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = listContentPadding,
                // 评论行之间的分组靠留白（12dp = BiliSpacing.md），不再靠加粗的卡片边界
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
            ) {
                if (header != null) {
                    item(key = "panel_header", contentType = "panel_header") {
                        header()
                    }
                }

                item(
                    key = "comment_header",
                    contentType = "header"
                ) {
                    if (isInitLoading) {
                        CommentHeaderSkeleton()
                    } else {
                        CommentHeader(
                            state = uiState,
                            onToggleSort = {
                                val next = if (uiState.sort == CommentSort.HOT) {
                                    CommentSort.TIME
                                } else {
                                    CommentSort.HOT
                                }
                                viewModel.selectSort(next)
                            }
                        )
                    }
                }

                when {
                    subject == null -> {
                        item(
                            key = "comment_empty_subject",
                            contentType = "state"
                        ) {
                            StateMessageCard(
                                text = "打开视频或动态后再来看看",
                                title = "暂无评论信息"
                            )
                        }
                    }

                    isInitLoading -> {
                        items(
                            count = skeletonSlotCount,
                            key = { index -> "comment_skeleton_$index" },
                            contentType = { "reply_skeleton" }
                        ) {
                            // A7：加载态条目淡入/位移，内容替换骨架时平滑过渡。
                            // 规格改走 LocalAnimations token（默认 spring 常量不在我们代码里，
                            // 速度档位/无障碍设置作用不到；这里显式统一到全局动效语言）。
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(
                                        durationMillis = anim.medium,
                                        easing = anim.emphasized
                                    ),
                                    placementSpec = tween(
                                        durationMillis = anim.long,
                                        easing = anim.emphasized
                                    ),
                                    fadeOutSpec = tween(
                                        durationMillis = anim.short,
                                        easing = anim.standard
                                    )
                                )
                            ) {
                                CommentCardSkeleton()
                            }
                        }
                    }

                    !uiState.error.isNullOrBlank() && uiState.items.isEmpty() -> {
                        item(
                            key = "comment_error",
                            contentType = "state"
                        ) {
                            StateMessageCard(
                                text = uiState.error.orEmpty(),
                                title = "评论加载失败",
                                isError = true
                            )
                        }
                    }

                    uiState.items.isEmpty() -> {
                        item(
                            key = "comment_no_data",
                            contentType = "state"
                        ) {
                            StateMessageCard(
                                text = "来说点什么吧",
                                title = "还没有评论"
                            )
                        }
                    }

                    else -> {
                        items(
                            items = uiState.items,
                            key = { it.rpid },
                            contentType = { "reply" }
                        ) { reply ->
                            // A7：评论条目淡入+位移 spring，替代整段 Crossfade
                            // （状态分支在同一 LazyColumn 内，Crossfade 会重建列表宿主、丢滚动位置）
                            // 新评论插入时按"淡入 medium + 让位 long"滑动到位，与全局动效语言一致。
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(
                                        durationMillis = anim.medium,
                                        easing = anim.emphasized
                                    ),
                                    placementSpec = tween(
                                        durationMillis = anim.long,
                                        easing = anim.emphasized
                                    ),
                                    fadeOutSpec = tween(
                                        durationMillis = anim.short,
                                        easing = anim.standard
                                    )
                                )
                            ) {
                                CommentCard(
                                    reply = reply,
                                    currentMid = uiState.currentMid,
                                    isReplyBusy = viewModel.isReplyBusy,
                                    onAction = onReplyAction
                                )
                            }
                        }
                    }
                }

                if (uiState.loadingMore) {
                    items(
                        count = LOAD_MORE_SKELETON_COUNT,
                        key = { index -> "comment_loading_more_$index" },
                        contentType = { "reply_skeleton" }
                    ) {
                        CommentCardSkeleton()
                    }
                } else if (!uiState.loadMoreError.isNullOrBlank()) {
                    item(
                        key = "comment_load_more_error",
                        contentType = "footer"
                    ) {
                        StateMessageCard(
                            text = uiState.loadMoreError.orEmpty(),
                            title = "加载失败",
                            isError = true
                        )
                    }
                } else if (!uiState.hasMore && uiState.items.isNotEmpty()) {
                    item(
                        key = "comment_end",
                        contentType = "footer"
                    ) {
                        StateMessageCard(
                            text = uiState.endText
                                ?: if (uiState.sort == CommentSort.HOT) {
                                    "热门评论已展示完"
                                } else {
                                    "没有更多评论"
                                }
                        )
                    }
                }
            }
            }
            AnimatedContent(
                targetState = replyThread,
                contentKey = { it != null },
                transitionSpec = {
                    // 楼层面板开合与 FAB 进出场统一到同一组 token 曲线（上游 76839cb22 的
                    // "面板动效统一"）：进场 emphasized、退场 standard，时长取 medium/short。
                    (
                        slideInHorizontally(
                            animationSpec = tween(
                                durationMillis = anim.medium,
                                easing = anim.emphasized
                            )
                        ) { fullWidth -> fullWidth } +
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = anim.medium,
                                    easing = anim.standard
                                )
                            )
                        ).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = anim.medium,
                                easing = anim.standard
                            )
                        ) { fullWidth -> fullWidth } +
                            fadeOut(
                                animationSpec = tween(
                                    durationMillis = anim.short,
                                    easing = anim.standard
                                )
                            )
                    )
                },
                label = "comment_thread_pane"
            ) { threadPane ->
                if (threadPane != null) {
                    CommentThreadPane(
                        state = threadPane,
                        listState = threadListState,
                        currentMid = uiState.currentMid,
                        isReplyBusy = viewModel.isReplyBusy,
                        onReplyAction = onReplyAction,
                        onDismiss = viewModel::closeReplyThread,
                        onToggleSort = viewModel::toggleReplyThreadSort,
                        onLoadMore = viewModel::loadMoreReplyThread,
                        bottomPadding = COMMENT_FAB_SPACE,
                        modifier = Modifier.fillMaxSize(),
                        isDetailMode = false
                    )
                }
            }
        }
        CommentEditorLayer(
            hasSubject = uiState.subject != null,
            currentMid = uiState.currentMid,
            isReplyThreadOpen = replyThread != null,
            fabVisible = fabVisible,
            fabEndPadding = contentPadding.calculateEndPadding(layoutDirection),
            fabBottomPadding = contentPadding.calculateBottomPadding() + BiliSpacing.lg,
            viewModel = viewModel
        )
    }
    uiState.replyCheckDialogText?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReplyCheckDialog,
            title = { Text("评论检查") },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::dismissReplyCheckDialog,
                    // 全局拖拽反馈（第 34 轮 T2）：对话框动作（浮层小件）。
                    modifier = Modifier.appElasticPress()
                ) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
private fun BoxScope.CommentEditorLayer(
    hasSubject: Boolean,
    currentMid: Long,
    isReplyThreadOpen: Boolean,
    fabVisible: Boolean,
    fabEndPadding: androidx.compose.ui.unit.Dp,
    fabBottomPadding: androidx.compose.ui.unit.Dp,
    viewModel: CommentViewModel
) {
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    // FAB 进出场与楼层面板同一组 token：进场 emphasized 稍慢、退场 standard 稍快，
    // 不再用 AnimatedVisibility 的库默认规格（速度档位作用不到）。
    val fabAnim = LocalAnimations.current

    if (hasSubject) {
        AnimatedVisibility(
            visible = fabVisible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = fabEndPadding,
                    bottom = fabBottomPadding
                ),
            enter = fadeIn(
                animationSpec = tween(durationMillis = fabAnim.medium, easing = fabAnim.standard)
            ) + scaleIn(
                animationSpec = tween(durationMillis = fabAnim.medium, easing = fabAnim.emphasized),
                initialScale = 0.86f
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = fabAnim.short, easing = fabAnim.standard)
            ) + scaleOut(
                animationSpec = tween(durationMillis = fabAnim.short, easing = fabAnim.standard),
                targetScale = 0.86f
            )
        ) {
            CommentEditorFab(
                contentDescription = when {
                    currentMid <= 0L -> "登录后发评论"
                    isReplyThreadOpen -> "回复评论"
                    else -> "发表评论"
                },
                onClick = viewModel::openEditor
            )
        }
    }

    CommentEditorSheet(
        state = editorState,
        onDismiss = viewModel::dismissEditor,
        onValueChange = viewModel::updateEditorInput,
        onSubmit = { viewModel.submitEditor() },
        onAddImages = viewModel::addImages,
        onRemoveImage = viewModel::removeImage
    )
}

@Composable
private fun CommentHeader(
    state: CommentUiState,
    onToggleSort: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            // 区块标题提到 titleMedium（16 SemiBold）：评论区标题是这一屏的**第二级**，
            // 下方每条评论的作者名是第三级（titleSmall 14 SemiBold），meta 再降一档。
            text = headerCount(state.count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (state.canSwitchSort) {
            SortSwitch(
                sort = state.sort,
                onClick = onToggleSort
            )
        } else {
            Text(
                text = sortText(state.sort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SortSwitch(
    sort: CommentSort,
    onClick: () -> Unit
) {
    Surface(
        // 排序是一个"可点的轻控件"，不再用 secondaryContainer（整屏里唯一一处彩色底），
        // 与排行页 chip 同一档：中性底 + labelMedium，选中与否靠文字色深浅。
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = SortSwitchShape,
        // 全局拖拽反馈（第 34 轮 T2）：排序 chip 是独立小件（切换热门/时间）。
        // `highlightShape` 与 `shape` 同源，光斑裁在 chip 圆角内。
        modifier = Modifier
            .appElasticPress(highlightShape = SortSwitchShape)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = sortText(sort),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                horizontal = BiliSpacing.md,
                vertical = BiliSpacing.xsPlus
            )
        )
    }
}

private fun sortText(sort: CommentSort): String {
    return when (sort) {
        CommentSort.HOT -> "热门"
        CommentSort.TIME -> "时间"
    }
}

private fun headerCount(count: Long): String {
    return if (count > 0L) {
        "$count 条评论"
    } else {
        "暂无评论"
    }
}

// A14（2026-10-13）：原 `INIT_SKELETON_COUNT = 4` 已删除 —— 首屏槽位数改由
// `resolveCommentSkeletonSlotCount(viewportHeightDp)` 按视口高度推导（见
// CommentSkeletonSlotPolicy.kt）。翻页骨架仍是固定 2 条：它只是"还有更多"的
// 提示，不承担"铺满首屏"的职责。
private const val LOAD_MORE_SKELETON_COUNT = 2
private val COMMENT_FAB_SPACE = 88.dp

/**
 * 排序切换胶囊的圆角。
 *
 * 文件级常量而非 `shape = RoundedCornerShape(...)` 内联：后者会被
 * `ListAllocationStructureTest` 的"具名实参构造 Shape"棘轮计数，且每次组合都新建一份。
 * 取 [BiliRadius.groupedList]（16dp）：控件实测高 28dp，半径已超过半高 → 全圆端胶囊，
 * 与排行页选择器同族（不再用随 CornerStyle 漂移的 `shapes.extraLarge`）。
 */
private val SortSwitchShape = RoundedCornerShape(BiliRadius.groupedList)
// K-7（2026-10-04 审查报告）：这里的 `COMMENT_TAG = "CommentPanel"` 全仓零引用
// （本文件用 `Logger.<级别>(TAG)` 的上层封装，tag 由别处给），已删除。

private fun CommentUser.toSpaceRoute(subject: CommentSubject?): SpaceRoute? {
    if (mid <= 0L && name.isBlank()) return null
    return SpaceRoute(
        mid = mid,
        name = name.takeIf(String::isNotBlank),
        fromViewAid = subject
            ?.takeIf { it.type == CommentSubjectTool.TYPE_VIDEO }
            ?.oid
            ?.takeIf { it > 0L }
    )
}
