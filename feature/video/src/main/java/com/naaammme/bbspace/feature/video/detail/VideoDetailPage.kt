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
import androidx.compose.foundation.shape.RoundedCornerShape
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

        else -> {
            item(
                key = "detail_empty",
                contentType = "state"
            ) {
                StateMessageCard(
                    text = "暂无简介信息",
                    modifier = itemMod
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoSummarySection(
    detail: VideoDetail,
    ids: ResolvedVideoIds,
    modifier: Modifier = Modifier,
    descOn: Boolean,
    tagOn: Boolean,
    onToggleDesc: () -> Unit,
    onToggleTag: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onDownloadClick: () -> Unit,
    onOpenComments: () -> Unit,
    onAction: (VideoDetailUiAction) -> Unit,
    interaction: VideoInteractionState
) {
    val spaceRoute = detail.toSpaceRouteOrNull(ids.aid.takeIf { it > 0L })
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
    ) {
        // 标题直接落在背景上，配 UP 行/操作栏形成层次，弱化"卡片套卡片"
        Text(
            text = detail.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.copyTextOnLongPress(detail.title, "标题")
        )
        detail.owner?.let { owner ->
            OwnerRow(
                owner = owner,
                onClick = spaceRoute?.let { route ->
                    { onOpenSpace(route) }
                }
            )
        }
        InfoMetaRow(
            detail = detail,
            ids = ids,
            descOn = descOn,
            tagOn = tagOn,
            onToggleDesc = onToggleDesc,
            onToggleTag = onToggleTag,
            onOpenComments = onOpenComments
        )
        ActionCapsule(
            interaction = interaction,
            onAction = onAction,
            onDownloadClick = onDownloadClick
        )
        if (descOn && detail.desc.isNotBlank()) {
            Text(
                text = detail.desc,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.copyTextOnLongPress(detail.desc, "简介")
            )
        }
        if (tagOn && detail.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                detail.tags.forEach { tag ->
                    SoftChip(tag)
                }
            }
        }
    }
}

@Composable
private fun OwnerRow(
    owner: VideoOwner,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val metaText = listOfNotNull(
        owner.fansText?.takeIf(String::isNotBlank),
        owner.arcCountText?.takeIf(String::isNotBlank)
    ).joinToString(" · ")
    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        owner.face?.takeIf(String::isNotBlank)?.let { face ->
            BiliAsyncImage(
                url = face,
                contentDescription = owner.name,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = owner.name,
                style = MaterialTheme.typography.titleSmall
            )
            if (metaText.isNotBlank()) {
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoMetaRow(
    detail: VideoDetail,
    ids: ResolvedVideoIds,
    descOn: Boolean,
    tagOn: Boolean,
    onToggleDesc: () -> Unit,
    onToggleTag: () -> Unit,
    onOpenComments: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        ids.aid.takeIf { it > 0L }?.let { SoftChip("AV$it", onLongPressLabel = "AV号") }
        ids.bvid?.takeIf(String::isNotBlank)?.let { SoftChip(it, onLongPressLabel = "BV号") }
        detail.pubTs?.let { ts ->
            SoftChip(formatPubTime(ts))
        }
        detail.stat?.let { stat ->
            SoftChip("${stat.view} 播放")
            SoftChip("${stat.danmaku} 弹幕")
            SoftChip(
                text = "${stat.reply} 评论",
                onClick = onOpenComments
            )
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        if (detail.desc.isNotBlank()) {
            ToggleChip(
                text = if (descOn) "收起简介" else "展开简介",
                onClick = onToggleDesc
            )
        }
        if (detail.tags.isNotEmpty()) {
            ToggleChip(
                text = if (tagOn) "收起标签" else "展开标签",
                onClick = onToggleTag
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionCapsule(
    interaction: VideoInteractionState,
    modifier: Modifier = Modifier,
    onAction: (VideoDetailUiAction) -> Unit,
    onDownloadClick: () -> Unit
) {
    val stat = interaction.stat
    val userState = interaction.userState
    val liked = userState?.liked == true
    val coinated = (userState?.coinCount ?: 0) > 0
    val faved = userState?.faved == true
    CapsuleCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (stat != null) {
                ActionChip(
                    label = "点赞",
                    value = actionCount(stat.likeValue, interaction.likeDelta),
                    icon = Icons.Default.ThumbUp,
                    selected = liked,
                    onClick = { onAction(VideoDetailUiAction.Like) },
                    onLongClick = { onAction(VideoDetailUiAction.Triple) }
                )
                ActionChip(
                    label = "投币",
                    value = actionCount(stat.coinValue, interaction.coinDelta),
                    icon = Icons.Default.Paid,
                    selected = coinated,
                    onClick = { onAction(VideoDetailUiAction.Coin) }
                )
                ActionChip(
                    label = "收藏",
                    value = actionCount(stat.favValue, interaction.favDelta),
                    icon = if (faved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    selected = faved,
                    onClick = { onAction(VideoDetailUiAction.Favorite) }
                )
                ActionChip(
                    label = "分享",
                    value = stat.share,
                    icon = Icons.Default.Share,
                    selected = false,
                    onClick = { onAction(VideoDetailUiAction.Share) }
                )
            }
            ActionChip(
                label = "下载",
                value = null,
                icon = Icons.Default.Download,
                selected = false,
                onClick = onDownloadClick
            )
        }
    }
}

private fun actionCount(base: Long, delta: Long): String = (base + delta).toString()

@Composable
private fun CapsuleCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.xs)
        ) {
            content()
        }
    }
}

@Composable
private fun SoftChip(
    text: String,
    onClick: (() -> Unit)? = null,
    onLongPressLabel: String? = null
) {
    val modifier = when {
        onLongPressLabel != null -> Modifier.copyTextOnLongPress(text, onLongPressLabel)
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ToggleChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.sm)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ActionChip(
    label: String,
    value: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val iconTint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val clickableModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )

        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    // 等分竖排：图标在上、数值在下，选中态以主题主色点亮
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.large)
            .then(clickableModifier)
            .padding(vertical = BiliSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = value ?: label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SeasonEntryCard(
    season: VideoSeason,
    curCid: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (title, subTitle, countText) = seasonEntryText(season, curCid)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "合集列表",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subTitle.isNotBlank()) {
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PageEntryCard(
    pages: List<VideoPagePart>,
    curCid: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (title, subTitle, countText) = remember(pages, curCid) {
        pageEntryText(pages, curCid)
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "分P列表",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subTitle.isNotBlank()) {
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SeasonSheetContent(
    season: VideoSeason,
    curCid: Long?,
    onOpenEpisode: (VideoTarget) -> Unit
) {
    val initIdx = remember(season, curCid) { seasonSheetIndex(season, curCid) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initIdx)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        item(
            key = "season_title",
            contentType = "title"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)) {
                Text(
                    text = season.title,
                    style = MaterialTheme.typography.titleLarge
                )
                season.subTitle?.takeIf(String::isNotBlank)?.let { subTitle ->
                    Text(
                        text = subTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        season.sections.forEachIndexed { secIdx, sec ->
            item(
                key = "sec_$secIdx",
                contentType = "section"
            ) {
                if (secIdx > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = BiliSpacing.xs))
                }
                if (sec.title.isNotBlank()) {
                    Text(
                        text = sec.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = BiliSpacing.xs)
                    )
                }
            }

            items(
                items = sec.eps,
                key = { "ep_${it.cid}_${it.title}" },
                contentType = { "episode" }
            ) { ep ->
                SeasonEpisodeRow(
                    ep = ep,
                    selected = ep.cid == curCid,
                    onClick = {
                        if (ep.cid != curCid) {
                            onOpenEpisode(ep.target)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PageSheetContent(
    pages: List<VideoPagePart>,
    curCid: Long?,
    onSwitchPage: (Long) -> Unit
) {
    val pageSheetUi = remember(pages) { buildPageSheetUi(pages) }
    val initIdx = remember(pages, curCid) { pageSheetIndex(pageSheetUi, curCid) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initIdx)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        item(
            key = "page_title",
            contentType = "title"
        ) {
            Text(
                text = "分P列表",
                style = MaterialTheme.typography.titleLarge
            )
        }

        items(
            items = pageSheetUi.items,
            key = { page -> "page_${page.cid}" },
            contentType = { "page" }
        ) { page ->
            PageSheetRow(
                title = page.title,
                subTitle = page.subTitle,
                selected = page.cid == curCid,
                onClick = {
                    if (page.cid != curCid) {
                        onSwitchPage(page.cid)
                    }
                }
            )
        }
    }
}

@Composable
private fun SeasonEpisodeRow(
    ep: VideoSeasonEpisode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowMod = if (selected) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }

    Row(
        modifier = rowMod.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        ep.cover?.takeIf(String::isNotBlank)?.let { cover ->
            BiliAsyncImage(
                url = cover,
                contentDescription = ep.title,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 10f)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    CurBadge()
                }
            }
            ep.subTitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = BiliSpacing.xs))
        }
    }
}

@Composable
private fun PageSheetRow(
    title: String,
    subTitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowMod = if (selected) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }

    Column(
        modifier = rowMod.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                CurBadge()
            }
        }
        subTitle?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = BiliSpacing.xs))
    }
}

@Composable
private fun RelateRow(
    relate: VideoRelate,
    onOpenVideo: (VideoTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val meta = listOfNotNull(
        relate.viewText?.let { "$it 播放" },
        relate.danmakuText?.let { "$it 弹幕" },
        relate.durationText
    ).joinToString(" · ")

    Card(
        onClick = { onOpenVideo(relate.target) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            verticalAlignment = Alignment.Top
        ) {
            BiliAsyncImage(
                url = relate.cover,
                contentDescription = relate.title,
                modifier = Modifier
                    .weight(0.38f)
                    .aspectRatio(16f / 10f)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier.weight(0.62f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = relate.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                relate.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                relate.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CurBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = "当前播放",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

private fun seasonEntryText(
    season: VideoSeason,
    curCid: Long?
): Triple<String, String, String> {
    val curEp = season.sections
        .asSequence()
        .flatMap { it.eps.asSequence() }
        .firstOrNull { it.cid == curCid }
    return Triple(
        curEp?.title ?: season.title,
        curEp?.subTitle.orEmpty().ifBlank { season.subTitle.orEmpty() },
        "${season.sections.sumOf { it.eps.size }} 个视频"
    )
}

private fun pageEntryText(
    pages: List<VideoPagePart>,
    curCid: Long?
): Triple<String, String, String> {
    val curIdx = curCid?.let { pages.indexOfFirst { it.cid == curCid } } ?: -1
    val curPage = pages.getOrNull(curIdx)
    return Triple(
        if (curPage != null) buildPageTitle(curIdx, curPage.part) else "查看分P列表",
        curPage?.durationSec?.takeIf { it > 0L }?.let { formatDuration(it * 1000) }.orEmpty(),
        "${pages.size} 个分 P"
    )
}

private fun seasonSheetIndex(season: VideoSeason, curCid: Long?): Int {
    if (curCid == null) return 0
    var secItemIdx = 1
    season.sections.forEach { sec ->
        val epIdx = sec.eps.indexOfFirst { it.cid == curCid }
        if (epIdx >= 0) {
            return secItemIdx + 1 + epIdx
        }
        secItemIdx += 1 + sec.eps.size
    }
    return 0
}

private fun pageSheetIndex(
    pageSheetUi: PageSheetUi,
    curCid: Long?
): Int {
    val pageIdx = curCid?.let { targetCid -> pageSheetUi.indexByCid[targetCid] } ?: -1
    return if (pageIdx >= 0) pageIdx + 1 else 0
}

private fun buildPageSheetUi(pages: List<VideoPagePart>): PageSheetUi {
    val items = ArrayList<PageSheetItem>(pages.size)
    val indexByCid = HashMap<Long, Int>(pages.size)
    pages.forEachIndexed { index, page ->
        items += PageSheetItem(
            cid = page.cid,
            title = buildPageTitle(index, page.part),
            subTitle = page.durationSec.takeIf { it > 0L }?.let { formatDuration(it * 1000) }
        )
        indexByCid[page.cid] = index
    }
    return PageSheetUi(
        items = items,
        indexByCid = indexByCid
    )
}

private fun buildPageTitle(index: Int, part: String): String {
    return "P${index + 1} $part"
}

private fun formatPubTime(ts: Long): String {
    return DateFormat.format("yyyy-MM-dd HH:mm", ts * 1000).toString()
}

private fun VideoDetail.toSpaceRouteOrNull(fromViewAid: Long?): SpaceRoute? {
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

@Composable
private fun CoinSheetContent(
    coinBalance: Double?,
    copyright: Int?,
    userCoinCount: Int,
    busy: Boolean,
    onSubmit: (Int, Boolean) -> Unit
) {
    var multiply by remember { mutableStateOf(1) }
    var withLike by remember { mutableStateOf(true) }
    // 转载视频与已投过币的视频最多再投 1 枚；投满 2 枚后不能再投
    val reachedLimit = userCoinCount >= 2 || (copyright == 2 && userCoinCount >= 1)
    val maxCoin = if (copyright == 2 || userCoinCount >= 1) 1 else 2

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("投币", style = MaterialTheme.typography.titleLarge)
        if (reachedLimit) {
            Text(
                text = "达到投币上限啦~",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                FilterChip(
                    selected = multiply == 1,
                    onClick = { multiply = 1 },
                    label = { Text("1 枚") }
                )
                FilterChip(
                    selected = multiply == 2,
                    onClick = { multiply = 2 },
                    enabled = maxCoin == 2,
                    label = { Text("2 枚") }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                Checkbox(checked = withLike, onCheckedChange = { withLike = it })
                Text("同时点赞", style = MaterialTheme.typography.bodyMedium)
            }
        }
        coinBalance?.let { balance ->
            Text(
                text = "硬币余额：$balance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!reachedLimit) {
            Button(
                onClick = { onSubmit(multiply, withLike) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "投币中…" else "确认投币")
            }
        }
    }
}

@Composable
private fun FavFolderSheetContent(
    state: FavFolderSheetState,
    onToggle: (Long) -> Unit,
    onCreateFolder: (String, Int) -> Unit,
    onSubmit: () -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var privateFolder by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("收藏", style = MaterialTheme.typography.titleLarge)
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.folders.isEmpty() -> {
                StateMessageCard(text = "暂无收藏夹，新建一个吧")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(
                        items = state.folders,
                        key = { it.fid }
                    ) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(folder.fid) }
                                .padding(vertical = BiliSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = folder.fid in state.selectedIds,
                                onCheckedChange = { onToggle(folder.fid) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "${folder.mediaCount} 个内容",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { showCreate = true }) {
            Text("＋ 新建收藏夹")
        }
        Button(
            onClick = onSubmit,
            enabled = !state.submitting && !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.submitting) "保存中…" else "完成")
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建收藏夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text("收藏夹标题") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                        FilterChip(
                            selected = !privateFolder,
                            onClick = { privateFolder = false },
                            label = { Text("公开") }
                        )
                        FilterChip(
                            selected = privateFolder,
                            onClick = { privateFolder = true },
                            label = { Text("私有") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = newTitle.trim()
                        if (title.isNotEmpty()) {
                            onCreateFolder(title, if (privateFolder) 1 else 0)
                            newTitle = ""
                            privateFolder = false
                            showCreate = false
                        }
                    },
                    enabled = newTitle.isNotBlank()
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Immutable
private data class PageSheetUi(
    val items: List<PageSheetItem>,
    val indexByCid: Map<Long, Int>
)

@Immutable
private data class PageSheetItem(
    val cid: Long,
    val title: String,
    val subTitle: String?
)
