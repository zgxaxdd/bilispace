package com.naaammme.bbspace.feature.video.detail

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.BottomSheetDefaults
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.VideoDetailInfoSkeleton
import com.naaammme.bbspace.core.designsystem.component.VideoRelateCardSkeleton
import com.naaammme.bbspace.core.designsystem.component.copyTextOnLongPress
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.QualityOption
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.SpaceRouteTool
import com.naaammme.bbspace.core.model.VideoDetail
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.VideoOwner
import com.naaammme.bbspace.core.model.VideoPagePart
import com.naaammme.bbspace.core.model.VideoRelate
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoSeason
import com.naaammme.bbspace.core.model.VideoSeasonEpisode
import com.naaammme.bbspace.core.model.VideoStat
import com.naaammme.bbspace.feature.comment.CommentPanel
import com.naaammme.bbspace.feature.video.FavFolderSheetState
import com.naaammme.bbspace.feature.video.VideoInteractionState
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.formatDuration
import kotlinx.coroutines.launch
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun VideoDetailPage(
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel,
    detail: VideoDetail?,
    ids: ResolvedVideoIds,
    biz: PlayBiz,
    detailLoading: Boolean,
    detailError: String?,
    commentSubject: CommentSubject?,
    contentHorizontalPad: Dp,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onDownloadClick: () -> Unit,
    onOpenEpisode: (VideoTarget) -> Unit,
    onSwitchPage: (Long) -> Unit
) {
    val aidKey = ids.aid.takeIf { it > 0L }
    val curCid = ids.cid.takeIf { it > 0L }
    var descOn by rememberSaveable(aidKey) { mutableStateOf(false) }
    var tagOn by rememberSaveable(aidKey) { mutableStateOf(false) }
    var sheet by remember(aidKey) { mutableStateOf<DetailSheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detailListState = remember(aidKey) { LazyListState() }
    val commentListState = remember(aidKey) { LazyListState() }
    val commentThreadListState = remember(aidKey) { LazyListState() }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val interaction by viewModel.interaction.collectAsStateWithLifecycle()
    val favSheetState by viewModel.favSheet.collectAsStateWithLifecycle()

    val handleAction: (VideoDetailUiAction) -> Unit = { action ->
        when (action) {
            VideoDetailUiAction.Like -> viewModel.toggleLike()
            VideoDetailUiAction.Triple -> viewModel.triple()
            VideoDetailUiAction.Coin -> sheet = DetailSheet.Coin
            VideoDetailUiAction.Favorite -> {
                viewModel.openFavSheet()
                sheet = DetailSheet.Fav
            }
            VideoDetailUiAction.Share -> {
                val pgcEp = ids.epId.takeIf { it > 0L }
                val url = if (biz == PlayBiz.PGC && pgcEp != null) {
                    ShareUtils.pgcUrl(pgcEp)
                } else {
                    val pageIndex = detail?.pages
                        ?.indexOfFirst { it.cid == ids.cid }
                        ?.takeIf { it >= 0 }
                    ShareUtils.videoUrl(ids.bvid, ids.aid, (pageIndex ?: -1) + 1)
                }
                ShareUtils.shareText(context, detail?.title, url)
            }
        }
    }

    LaunchedEffect(aidKey) {
        pagerState.scrollToPage(0)
    }

    fun closeSheet(afterClose: (() -> Unit)? = null) {
        scope.launch {
            if (sheetState.isVisible) {
                sheetState.hide()
            }
            sheet = null
            afterClose?.invoke()
        }
    }

    LaunchedEffect(favSheetState.dismissRequest) {
        if (favSheetState.dismissRequest) {
            viewModel.consumeFavDismiss()
            closeSheet()
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 0,
        modifier = modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> DetailPageContent(
                modifier = Modifier.fillMaxSize(),
                detail = detail,
                ids = ids,
                detailLoading = detailLoading,
                detailError = detailError,
                horizontalPad = contentHorizontalPad,
                infoListState = detailListState,
                descOn = descOn,
                tagOn = tagOn,
                onToggleDesc = { descOn = !descOn },
                onToggleTag = { tagOn = !tagOn },
                onSeasonClick = { sheet = detail?.season?.let { DetailSheet.Season(it, curCid) } },
                onPageClick = {
                    sheet = detail?.pages
                        ?.takeIf { it.size > 1 }
                        ?.let { DetailSheet.Page(it, curCid) }
                },
                onOpenComments = {
                    scope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                },
                onOpenVideo = onOpenVideo,
                onOpenSpace = onOpenSpace,
                onDownloadClick = onDownloadClick,
                onAction = handleAction,
                interaction = interaction
            )

            else -> {
                CommentPanel(
                    subject = commentSubject,
                    isActive = pagerState.currentPage == page,
                    onOpenSpace = onOpenSpace,
                    modifier = Modifier.fillMaxSize(),
                    listState = commentListState,
                    threadListState = commentThreadListState,
                    contentPadding = PaddingValues(
                        start = contentHorizontalPad,
                        top = BiliSpacing.md,
                        end = contentHorizontalPad,
                        bottom = BiliSpacing.xl
                    )
                )
            }
        }
    }

    sheet?.let { activeSheet ->
        ModalBottomSheet(
            // iOS：弹层顶部圆角 28dp，只圆上两角（见 BiliRadius.sheetTop）
            shape = if (LocalUiStyle.current == UiStyle.IOS) {
                BiliRadius.topRounded(BiliRadius.sheetTop)
            } else {
                BottomSheetDefaults.ExpandedShape
            },
            scrimColor = Color.Black.copy(alpha = 0.40f),
            onDismissRequest = { closeSheet() },
            sheetState = sheetState
        ) {
            when (activeSheet) {
                is DetailSheet.Season -> {
                    SeasonSheetContent(
                        season = activeSheet.season,
                        curCid = activeSheet.curCid,
                        onOpenEpisode = { route ->
                            closeSheet {
                                onOpenEpisode(route)
                            }
                        }
                    )
                }

                is DetailSheet.Page -> {
                    PageSheetContent(
                        pages = activeSheet.pages,
                        curCid = activeSheet.curCid,
                        onSwitchPage = { cid ->
                            closeSheet {
                                onSwitchPage(cid)
                            }
                        }
                    )
                }

                is DetailSheet.Coin -> {
                    CoinSheetContent(
                        coinBalance = viewModel.coinBalance(),
                        copyright = detail?.copyright,
                        userCoinCount = interaction.userState?.coinCount ?: 0,
                        busy = interaction.busy,
                        onSubmit = { multiply, withLike ->
                            viewModel.submitCoin(multiply, withLike)
                        }
                    )
                }

                is DetailSheet.Fav -> {
                    FavFolderSheetContent(
                        state = favSheetState,
                        onToggle = viewModel::toggleFavFolder,
                        onCreateFolder = viewModel::createFolderInSheet,
                        onSubmit = viewModel::submitFav
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPageContent(
    modifier: Modifier,
    detail: VideoDetail?,
    ids: ResolvedVideoIds,
    detailLoading: Boolean,
    detailError: String?,
    horizontalPad: Dp,
    infoListState: LazyListState,
    descOn: Boolean,
    tagOn: Boolean,
    onToggleDesc: () -> Unit,
    onToggleTag: () -> Unit,
    onSeasonClick: () -> Unit,
    onPageClick: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onDownloadClick: () -> Unit,
    onAction: (VideoDetailUiAction) -> Unit,
    interaction: VideoInteractionState
) {
    val itemMod = remember(horizontalPad) {
        if (horizontalPad > 0.dp) Modifier.padding(horizontal = horizontalPad) else Modifier
    }
    val infoTopPad = remember(horizontalPad) {
        if (horizontalPad > 0.dp) 16.dp else 0.dp
    }

    LazyColumn(
        state = infoListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = infoTopPad, bottom = BiliSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.lg)
    ) {
        detailItems(
            detail = detail,
            ids = ids,
            detailLoading = detailLoading,
            detailError = detailError,
            itemMod = itemMod,
            descOn = descOn,
            tagOn = tagOn,
            onToggleDesc = onToggleDesc,
            onToggleTag = onToggleTag,
            onSeasonClick = onSeasonClick,
            onPageClick = onPageClick,
            onOpenVideo = onOpenVideo,
            onOpenSpace = onOpenSpace,
            onDownloadClick = onDownloadClick,
            onOpenComments = onOpenComments,
            onAction = onAction,
            interaction = interaction
        )
    }
}

private fun LazyListScope.detailItems(
    detail: VideoDetail?,
    ids: ResolvedVideoIds,
    detailLoading: Boolean,
    detailError: String?,
    itemMod: Modifier,
    descOn: Boolean,
    tagOn: Boolean,
    onToggleDesc: () -> Unit,
    onToggleTag: () -> Unit,
    onSeasonClick: () -> Unit,
    onPageClick: () -> Unit,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onDownloadClick: () -> Unit,
    onOpenComments: () -> Unit,
    onAction: (VideoDetailUiAction) -> Unit,
    interaction: VideoInteractionState
) {
    val curCid = ids.cid.takeIf { it > 0L }
    when {
        // 有详情就渲染（可能是上次缓存的先上屏版本，网络详情到达后原地刷新）；
        // 骨架屏只在完全没有数据时出现——消除"已有数据仍整块骨架"的空转
        detail != null -> {
            item(
                key = "summary",
                contentType = "summary"
            ) {
                VideoSummarySection(
                    detail = detail,
                    ids = ids,
                    descOn = descOn,
                    tagOn = tagOn,
                    onToggleDesc = onToggleDesc,
                    onToggleTag = onToggleTag,
                    onOpenSpace = onOpenSpace,
                    onDownloadClick = onDownloadClick,
                    onOpenComments = onOpenComments,
                    onAction = onAction,
                    interaction = interaction,
                    modifier = itemMod
                )
            }

            detail.season?.let { season ->
                item(
                    key = "season_entry",
                    contentType = "season_entry"
                ) {
                    SeasonEntryCard(
                        season = season,
                        curCid = curCid,
                        onClick = onSeasonClick,
                        modifier = itemMod
                    )
                }
            }

            if (detail.pages.size > 1) {
                item(
                    key = "page_entry",
                    contentType = "page_entry"
                ) {
                    PageEntryCard(
                        pages = detail.pages,
                        curCid = curCid,
                        onClick = onPageClick,
                        modifier = itemMod
                    )
                }
            }

            if (detail.relates.isNotEmpty()) {
                items(
                    items = detail.relates,
                    key = { "${it.target.aid}_${it.target.cid}" },
                    contentType = { "relate" }
                ) { relate ->
                    RelateRow(
                        relate = relate,
                        onOpenVideo = onOpenVideo,
                        modifier = itemMod
                    )
                }
            }
        }

        detailLoading -> {
            item(
                key = "detail_loading_summary",
                contentType = "skeleton"
            ) {
                VideoDetailInfoSkeleton(modifier = itemMod)
            }
            items(
                count = DETAIL_RELATE_SKELETON_COUNT,
                key = { index -> "detail_loading_relate_$index" },
                contentType = { "skeleton" }
            ) {
                VideoRelateCardSkeleton(modifier = itemMod)
            }
        }

        !detailError.isNullOrBlank() -> {
            item(
                key = "detail_error",
                contentType = "state"
            ) {
                StateMessageCard(
                    text = detailError,
                    modifier = itemMod,
                    isError = true
                )
            }
        }

        // 兜底：**没有详情、也没在报错**时显示骨架，而不是"暂无简介信息"。
        //
        // 为什么这条兜底是必要的（原来这里是空状态卡，导致打开瞬间看不到骨架）：
        // `detailLoading` 由 `StreamPlaybackSessionImpl` 在 `openVideo()` 里设置，
        // 而 `openRoot()` 是在 `LaunchedEffect` 里调用的 —— **首帧**时
        // `detail == null && detailLoading == false`，于是 when 落到最后一个分支，
        // 用户看到的是"暂无简介信息"卡片（或整片空白），而不是骨架。
        // 语义上"没有数据且没有错误"就等于"还在加载"，所以这里就该给骨架。
        else -> {
            item(
                key = "detail_loading_summary",
                contentType = "skeleton"
            ) {
                VideoDetailInfoSkeleton(modifier = itemMod)
            }
            items(
                count = DETAIL_RELATE_SKELETON_COUNT,
                key = { index -> "detail_loading_relate_$index" },
                contentType = { "skeleton" }
            ) {
                VideoRelateCardSkeleton(modifier = itemMod)
            }
        }
    }
}

@Composable
internal fun QualityOptionItem(
    option: QualityOption,
    isSelected: Boolean,
    onClick: (() -> Unit)? = null
) {
    val rowMod = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowMod.padding(BiliSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (option.needVip) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "大会员",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            option.limit?.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

internal fun VideoDetail.toSpaceRouteOrNull(fromViewAid: Long?): SpaceRoute? {
    val owner = owner ?: return null
    if (owner.mid <= 0L && owner.name.isBlank()) return null
    return SpaceRoute(
        mid = owner.mid,
        name = owner.name.takeIf(String::isNotBlank),
        from = SpaceRouteTool.FROM_DEFAULT,
        fromViewAid = fromViewAid
    )
}

private const val DETAIL_RELATE_SKELETON_COUNT = 2

private sealed interface DetailSheet {
    data class Season(
        val season: VideoSeason,
        val curCid: Long?
    ) : DetailSheet

    data class Page(
        val pages: List<VideoPagePart>,
        val curCid: Long?
    ) : DetailSheet

    data object Coin : DetailSheet

    data object Fav : DetailSheet
}

internal sealed interface VideoDetailUiAction {
    data object Like : VideoDetailUiAction

    data object Triple : VideoDetailUiAction

    data object Coin : VideoDetailUiAction

    data object Favorite : VideoDetailUiAction

    data object Share : VideoDetailUiAction
}

@Immutable
internal data class PageSheetUi(
    val items: List<PageSheetItem>,
    val indexByCid: Map<Long, Int>
)

@Immutable
internal data class PageSheetItem(
    val cid: Long,
    val title: String,
    val subTitle: String?
)
