package com.naaammme.bbspace.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.AdaptiveMediaGrid
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.seamless.CoverExpandOrigin
import com.naaammme.bbspace.core.designsystem.seamless.LocalBottomNavOcclusionRatio
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.coverExpandSourceFade
import com.naaammme.bbspace.core.designsystem.seamless.coverExpandTrack
import com.naaammme.bbspace.core.designsystem.seamless.listCoverRatio
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.withFontSize
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.core.model.rank.RankEntry
import com.naaammme.bbspace.core.model.rank.RankPgcItem
import com.naaammme.bbspace.core.model.rank.RankSeriesEntry
import com.naaammme.bbspace.core.model.rank.RankType
import com.naaammme.bbspace.core.model.rank.RankVideoItem

/**
 * 首页「排行」页：热门/分区排行榜（UGC rid）、PGC 季度排行、每周必看、入站必刷。
 * 分区表与接口对照 PiliPlus pages/rank 与 lib/http/video.dart。
 * 类型选择行作为网格 header 随内容滚动（与推荐页顶栏行为一致，不固定悬浮）。
 */
@Composable
fun RankScreen(
    isActive: Boolean,
    topContentPadding: Dp,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    viewModel: RankViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyStaggeredGridState()
    // 无缝返回动画的遮挡渐隐比例（顶部悬浮顶栏 + 底部悬浮导航栏），与推荐页同口径
    val density = LocalDensity.current
    val screenHpx = LocalWindowInfo.current.containerSize.height
    val bottomNavRatio = LocalBottomNavOcclusionRatio.current
    // 与推荐页同口径：遮挡比延迟到点击时才算，理由见 HomeVideoPage 的同名注释
    // （组合期读会在 Dock 合并动画期间按帧重组；且遮挡比是异步实测的，remember 住
    //   会把首帧的 0f 缓存成永久值）。
    val listCover: () -> Pair<Float, Float>? = {
        listCoverRatio(
            topOcclusionPx = with(density) { topContentPadding.roundToPx() },
            screenHeightPx = screenHpx,
            bottomOcclusionRatio = bottomNavRatio()
        )?.let { it.top to it.bottom }
    }

    AdaptiveMediaGrid(
        items = state.entries,
        isRefreshing = state.isRefreshing || (state.isLoading && state.entries.isEmpty()),
        isLoadingMore = state.isLoadingMore,
        // 必须把错误接进网格：不传时网格视为无错误，失败一律渲染成 emptyContent
        // 的"暂无内容"——请求被风控/断网时用户只看到空态，无从重试也无法得知原因。
        errorMessage = state.errorMessage,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        loadMoreEnabled = state.canLoadMore,
        // 尾部提前预取：与推荐页一致，距末尾 4 条就取下一页（该网格有 header 项，
        // 可见下标整体偏移 1，阈值取 4 即"还剩约 5 条"时触发）
        loadMoreThreshold = 4,
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        key = { _, item -> item.key },        contentType = { _, item -> item::class.simpleName },
        contentPadding = PaddingValues(
            start = 4.dp,
            top = topContentPadding,
            end = 4.dp,
            bottom = 4.dp
        ),
        headerContent = {
            RankSelectors(
                state = state,
                onSelectType = viewModel::select,
                onSelectSeries = viewModel::selectSeries
            )
        },
        emptyContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                RankSelectors(
                    state = state,
                    onSelectType = viewModel::select,
                    onSelectSeries = viewModel::selectSeries
                )
                StateMessageCard(
                    text = "暂无内容",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        },
        errorContent = { message ->
            // 列表为空时的错误 = 整页失败：保留选择行以便切换分区重试；
            // 列表非空时的错误 = 加载更多失败：仅展示错误卡片
            if (state.entries.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RankSelectors(
                        state = state,
                        onSelectType = viewModel::select,
                        onSelectSeries = viewModel::selectSeries
                    )
                    StateMessageCard(
                        text = message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        isError = true
                    )
                }
            } else {
                StateMessageCard(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    isError = true
                )
            }
        },
        loadingContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            )
        }
    ) { entry ->
        when (entry) {
            is RankEntry.Video -> RankVideoCard(
                item = entry.item,
                listCover = listCover,
                onOpenVideo = onOpenVideo,
                onOpenSpace = onOpenSpace
            )

            is RankEntry.Pgc -> RankPgcCard(
                item = entry.item,
                onOpenVideo = onOpenVideo
            )
        }
    }
}

/** 类型选择行 + 每周必看期数行（仅每周必看类型时出现） */
@Composable
private fun RankSelectors(
    state: RankUiState,
    onSelectType: (RankType) -> Unit,
    onSelectSeries: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RankType.entries.forEach { type ->
                val selectedNow = type == state.type
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (selectedNow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.clickable { onSelectType(type) }
                ) {
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedNow) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        if (state.type == RankType.SERIES && state.seriesList.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.seriesList.forEach { entry ->
                    val selectedNow = entry.number == state.seriesNumber
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (selectedNow) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier.clickable { onSelectSeries(entry.number) }
                    ) {
                        Text(
                            text = entry.name.ifBlank { "第${entry.number}期" },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selectedNow) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankVideoCard(
    item: RankVideoItem,
    listCover: () -> Pair<Float, Float>? = { null },
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit
) {
    val target = remember(item.aid, item.cid, item.bvid) {
        VideoTarget.Ugc(
            aid = item.aid,
            cid = item.cid,
            bvid = item.bvid,
            src = VideoTargetTool.default()
        )
    }
    val spaceRoute = remember(item.ownerMid) {
        item.ownerMid.takeIf { it > 0L }?.let { SpaceRoute(mid = it, name = item.ownerName) }
    }
    // 与首页 FeedCard 相同的无缝动画接入：注册成功走共享执行器，失败降级幽灵。
    // 版本门禁前置到组合期：不支持的设备不为每张卡实例化锚点 View
    val anchorState = rememberSeamlessAnchorState()
    val seamlessSpec = rememberSeamlessCardSpec(
        shape = RoundedCornerShape(BiliRadius.card),
        containerColor = MaterialTheme.colorScheme.surface
    )
    val seamlessOpener = LocalSeamlessCardOpener.current
    val canSeamless = seamlessOpener != null && OplusViewSeamlessCompat.isVersionSupported()

    BiliCardSurface(
        onClick = {
            if (
                canSeamless &&
                seamlessOpener.tryOpen(
                    anchor = anchorState.anchor,
                    target = target,
                    coverUrl = item.cover,
                    coverLarge = false,
                    anchorRadiusPx = seamlessSpec.radiusPx,
                    listCover = listCover()
                )
            ) {
                return@BiliCardSurface
            }
            CoverExpandOrigin.record(target = target, coverUrl = item.cover, coverLarge = false)
            onOpenVideo(target)
        },
        shape = MaterialTheme.shapes.large,
        containerColor = Color.Transparent,
        // 回退 morph 期间源卡片随幽灵进度淡出（系统无缝路径恒 alpha=1，见该函数注释）
        modifier = Modifier.fillMaxWidth().coverExpandSourceFade(target)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .coverExpandTrack(target)
        ) {
            // 锚点在封面内容之下（z 序）：过渡位图不能盖住封面上的时长角标
            if (canSeamless) {
                SeamlessAnchor(anchorState, Modifier.matchParentSize())
            }
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                shape = RoundedCornerShape(BiliRadius.card),
                coverLarge = false,
                coverFourThree = true,
                modifier = Modifier.matchParentSize()
            ) {
                item.durationSec.takeIf { it > 0L }?.let { duration ->
                    RankCoverBadge(
                        text = formatRankDuration(duration),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.withFontSize(14.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(formatRankCount(item.view))
                    append(" 播放 · ")
                    append(formatRankCount(item.danmaku))
                    append(" 弹幕")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (item.ownerName.isNotBlank()) {
                Text(
                    text = item.ownerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (spaceRoute != null) {
                                Modifier.clickable { onOpenSpace(spaceRoute) }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun RankPgcCard(
    item: RankPgcItem,
    onOpenVideo: (VideoTarget) -> Unit
) {
    val target = remember(item.seasonId, item.epId) {
        if ((item.seasonId ?: 0L) > 0L || (item.epId ?: 0L) > 0L) {
            VideoTarget.Pgc(
                epId = item.epId ?: 0L,
                seasonId = item.seasonId,
                src = VideoTargetTool.default()
            )
        } else {
            null
        }
    }
    val anchorState = rememberSeamlessAnchorState()
    val seamlessSpec = rememberSeamlessCardSpec(
        shape = RoundedCornerShape(BiliRadius.card),
        containerColor = MaterialTheme.colorScheme.surface
    )
    val seamlessOpener = LocalSeamlessCardOpener.current

    BiliCardSurface(
        onClick = {
            val currentTarget = target ?: return@BiliCardSurface
            if (
                seamlessOpener != null &&
                seamlessOpener.tryOpen(
                    anchor = anchorState.anchor,
                    target = currentTarget,
                    coverUrl = item.cover,
                    coverLarge = false,
                    anchorRadiusPx = seamlessSpec.radiusPx
                )
            ) {
                return@BiliCardSurface
            }
            CoverExpandOrigin.record(target = currentTarget, coverUrl = item.cover, coverLarge = false)
            onOpenVideo(currentTarget)
        },
        enabled = target != null,
        shape = MaterialTheme.shapes.large,
        containerColor = Color.Transparent,
        // 回退 morph 期间源卡片随幽灵进度淡出（系统无缝路径恒 alpha=1，见该函数注释）
        modifier = Modifier.fillMaxWidth().coverExpandSourceFade(target)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        ) {
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                shape = RoundedCornerShape(BiliRadius.card),
                coverLarge = false,
                coverFourThree = true,
                modifier = Modifier.matchParentSize()
            ) {
                item.indexShow?.let { indexShow ->
                    RankCoverBadge(
                        text = indexShow,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.withFontSize(14.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(formatRankCount(item.follow))
                    append(" 追番 · ")
                    append(formatRankCount(item.view))
                    append(" 播放")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun RankCoverBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.62f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatRankDuration(durationSec: Long): String {
    val totalSec = durationSec.coerceAtLeast(0L)
    val hours = totalSec / 3_600
    val minutes = (totalSec % 3_600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatRankCount(count: Long): String {
    return when {
        count >= 100_000_000L -> {
            val value = count / 100_000_000.0
            trimRankNumber(value) + "亿"
        }

        count >= 10_000L -> {
            val value = count / 10_000.0
            trimRankNumber(value) + "万"
        }

        else -> count.toString()
    }
}

private fun trimRankNumber(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}
