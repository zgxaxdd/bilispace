package com.naaammme.bbspace.feature.home.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.AdaptiveMediaGrid
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.UpListRow
import com.naaammme.bbspace.core.designsystem.component.VideoGridCardSkeleton
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.LiveRecommendItem
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

/**
 * 直播卡容器：与推荐页 `FeedCardShape` / 排行 `RankCardShape` 同源
 * （[BiliRadius.card]），整卡大圆角。直播 tab 是三个内容 tab 里最后一个
 * 收口的——此前用 M3 `Card`（medium，ROUNDED 16 / iOS 12 且随档位漂移），
 * 与旁边两个 tab 的卡片语言脱节（用户反馈"直播没适配"）。
 */
private val LiveCardShape = RoundedCornerShape(BiliRadius.card)

@Composable
fun HomeLivePage(
    isActive: Boolean,
    topContentPadding: Dp = 0.dp,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    viewModel: HomeLiveViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    LaunchedEffect(isActive) {
        if (isActive) viewModel.ensureLoaded()
    }
    AdaptiveMediaGrid(
        items = state.items,
        isRefreshing = state.isRefreshing,
        isLoadingMore = state.isLoadingMore,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
        errorMessage = state.errorMessage,
        loadMoreEnabled = isActive,
        contentPadding = PaddingValues(
            start = BiliSpacing.xs,
            top = topContentPadding,
            end = BiliSpacing.xs,
            bottom = BiliSpacing.xs
        ),
        key = { _, item -> item.actionKey() },
        loadingContent = {
            VideoGridCardSkeleton()
        },
        headerContent = state.upList?.let { upList ->
            {
                UpListRow(
                    title = upList.title,
                    items = upList.items,
                    key = { it.uid },
                    name = { it.name },
                    face = { it.face },
                    onClick = { item ->
                        onOpenLive(item.route)
                    }
                )
            }
        },
        emptyContent = {
            StateMessageCard(
                title = "暂无直播推荐",
                text = state.errorMessage ?: "下拉试试重新获取",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp)
            )
        }
    ) { item ->
        LiveRecommendCard(
            item = item,
            onClick = { onOpenLive(item.route) },
            onOpenSpace = onOpenSpace
        )
    }
}

private fun LiveRecommendItem.actionKey(): String {
    return "${roomId}_${sessionId.orEmpty()}"
}

@Composable
private fun LiveRecommendCard(
    item: LiveRecommendItem,
    onClick: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit
) {
    // M3 Card → BiliCardSurface：与推荐/排行卡同容器（按压反馈、iOS 发丝描边、
    // 统一大圆角），整卡底色让封面与信息区读作一张卡片。
    // 封面 shape 留空由外层 clip 收口：封面满宽在卡顶，顶部两角被外层圆角裁切，
    // 底部直角接信息区——与推荐页「上圆下直」的封面/信息区分半结构同观感。
    BiliCardSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = LiveCardShape
    ) {
        CoverImage(
            url = item.cover,
            contentDescription = item.title,
            shape = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
        ) {
            item.onlineText?.let { text ->
                val onlineBgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                Text(
                    text = text,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(BiliSpacing.xs)
                        .background(color = onlineBgColor, shape = LiveBadgeShape)
                        .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = BiliSpacing.smPlus, vertical = BiliSpacing.sm)) {
            val spaceRoute = remember(item.ownerMid, item.ownerName) {
                item.ownerMid?.let { mid ->
                    SpaceRoute(
                        mid = mid,
                        name = item.ownerName
                    )
                }
            }
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )

            item.ownerName?.let { ownerName ->
                Spacer(modifier = Modifier.height(BiliSpacing.xs))
                Text(
                    text = ownerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (spaceRoute == null) {
                        Modifier
                    } else {
                        // 全局拖拽反馈（第 34 轮 T2）：**UP 主名文字**是卡片内的独立
                        // 小件（点进空间）。卡片容器本身走 `BiliCardSurface` 原语
                        // （它的弹性反馈由原语统一挂，且那条路径与无缝锚点相关，
                        // 按铁律不能在这里叠加）；这一处是文字叶子的 clickable，安全。
                        Modifier
                            .appElasticPress()
                            .clickable { onOpenSpace(spaceRoute) }
                    }
                )
            }

            item.areaName?.let { areaName ->
                Spacer(modifier = Modifier.height(BiliSpacing.xs))
                val areaBgColor = MaterialTheme.colorScheme.secondaryContainer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = areaName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(color = areaBgColor, shape = LiveBadgeShape)
                            .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 人气 / 分区小标：与封面角标同一角色（[BiliRadius.badge]），此前 shapes.extraSmall 随档位漂移。 */
private val LiveBadgeShape = RoundedCornerShape(BiliRadius.badge)
