package com.naaammme.bbspace.feature.space.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessCardCoverAnchor
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessVideoCardState
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceVideo
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.space.SpaceArchiveUiState
import com.naaammme.bbspace.feature.space.SpaceDynamicUiState
import com.naaammme.bbspace.feature.space.SpaceSection
import com.naaammme.bbspace.feature.space.dynamic.spaceDynamicSection
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import java.util.Locale

/** 视频卡时长角标底盘（文件级常量：形状不许在组合期新建 —— `ListAllocationStructureTest` 有守卫）。 */
private val SpaceDurationBadgeShape = RoundedCornerShape(BiliRadius.badge)


internal fun LazyListScope.spaceArchiveSection(
    state: SpaceArchiveUiState,
    videoCount: Int,
    dynamics: SpaceDynamicUiState,
    section: SpaceSection,
    onOpenVideo: (VideoTarget) -> Unit,
    onSelectOrder: (String) -> Unit,
    onSelectSection: (SpaceSection) -> Unit,
    onOpenDynamic: (String) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit
) {
    item(
        key = "space_section_bar",
        contentType = "section_bar"
    ) {
        val selectedIndex = if (section == SpaceSection.VIDEO) 0 else 1
        FilledTabRow(
            tabs = listOf("视频", "动态"),
            selectedIndex = selectedIndex,
            onSelect = { index ->
                onSelectSection(if (index == 0) SpaceSection.VIDEO else SpaceSection.DYNAMIC)
            }
        )
    }

    when (section) {
        SpaceSection.VIDEO -> videoSection(
            state = state,
            videoCount = videoCount,
            onOpenVideo = onOpenVideo,
            onSelectOrder = onSelectOrder,
            onRetryLoadMore = onLoadMore
        )
        SpaceSection.DYNAMIC -> spaceDynamicSection(
            state = dynamics,
            onOpenDynamic = onOpenDynamic,
            onOpenLive = onOpenLive,
            onOpenVideo = onOpenVideo,
            onRetry = onRefresh,
            onLoadMore = onLoadMore
        )
    }
}

private fun LazyListScope.videoSection(
    state: SpaceArchiveUiState,
    videoCount: Int,
    onOpenVideo: (VideoTarget) -> Unit,
    onSelectOrder: (String) -> Unit,
    onRetryLoadMore: () -> Unit
) {
    item(
        key = "archive_toolbar",
        contentType = "toolbar"
    ) {
        val orderIndex = state.orders.indexOfFirst { it.value == state.selectedOrder }
            .takeIf { it >= 0 }
            ?: 0
        val order = state.orders.getOrNull(orderIndex)
        val nextOrder = state.orders
            .takeIf { it.size > 1 }
            ?.get((orderIndex + 1) % state.orders.size)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${videoCount.coerceAtLeast(state.videos.size)} 个视频",
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(
                onClick = { nextOrder?.let { onSelectOrder(it.value) } },
                enabled = nextOrder != null,
                // 全局拖拽反馈（第 34 轮 T2）：**这一颗可以挂** ——
                // 本文件虽然有 `SeamlessCardCoverAnchor`，但锚点在 `SpaceVideoCard`
                // 的 `BiliCardSurface` 卡片容器里（卡片本身的弹性反馈由该原语负责，
                // 按铁律不能重复挂也无法在此挂）；这里是**工具条上的排序按钮**，
                // 与视频卡既不共祖先也不参与过渡几何，屏幕像素与
                // `getLocationInWindow` 之间不存在被 graphicsLayer 扭过的关系。
                modifier = Modifier.appElasticPress(enabled = nextOrder != null)
            ) {
                Text(
                    text = order?.title ?: "排序",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    state.message?.let { message ->
        item(key = "archive_error", contentType = "state") {
            StateMessageCard(text = message, isError = true)
        }
    }

    when {
        // AP-16：首屏加载态 —— videos 空且正在刷新时，原实现落进 else 的
        // items(emptyList())，首屏"空一片"。统一走 StateMessageCard（UI-04 出口），
        // 三态优先级保持 错误（上方 message item）> 空 > 加载。
        state.videos.isEmpty() && state.isRefreshing && state.message.isNullOrBlank() -> {
            item(key = "archive_loading", contentType = "state") {
                StateMessageCard(text = "加载中…")
            }
        }
        state.showEmpty && !state.isRefreshing -> {
            item(key = "archive_empty", contentType = "state") {
                StateMessageCard(text = "这个空间还没有公开视频")
            }
        }
        else -> {
            items(
                items = state.videos,
                key = { "${it.aid}_${it.cid}" },
                contentType = { "video" }
            ) { video ->
                // A8：分区切换（视频↔动态）条目淡入+位移过渡
                Box(modifier = Modifier.animateItem()) {
                    SpaceVideoCard(
                        video = video,
                        onClick = { onOpenVideo(video.target) }
                    )
                }
            }
        }
    }

    when {
        state.loadMoreError != null -> {
            item(key = "archive_load_more_error", contentType = "state") {
                StateMessageCard(
                    text = state.loadMoreError,
                    isError = true,
                    actionText = "重试",
                    onAction = onRetryLoadMore
                )
            }
        }
        !state.hasMore && state.videos.isNotEmpty() -> {
            item(key = "archive_end", contentType = "state") {
                StateMessageCard(text = "没有更多视频了")
            }
        }
    }
}

@Composable
private fun SpaceVideoCard(
    video: SpaceVideo,
    onClick: () -> Unit
) {
    // 无缝打开：注册成功由系统接管过渡，失败走 onClick 普通打开
    val seamless = rememberSeamlessVideoCardState()
    val seamlessRadiusPx = rememberSeamlessCardSpec(shape = SpaceCardShape).radiusPx
    val meta = remember(video.author, video.categoryName, video.publishTimeText) {
        listOfNotNull(video.author, video.categoryName, video.publishTimeText).joinToString(" · ")
    }
    val durationText = remember(video.durationSec) {
        video.durationSec.takeIf { it > 0L }?.let(::formatDuration)
    }
    val stats = remember(video.viewText, video.danmakuText, durationText) {
        listOfNotNull(
            video.viewText.takeIf(String::isNotBlank)?.let { "$it 播放" },
            video.danmakuText?.takeIf(String::isNotBlank)?.let { "$it 弹幕" },
            durationText
        ).joinToString(" · ")
    }

    BiliCardSurface(
        onClick = {
            if (!seamless.tryOpen(video.target, video.cover, true, seamlessRadiusPx)) {
                onClick()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = SpaceCardShape,
        // 本卡内的 `SeamlessCardCoverAnchor`（见下方 CoverImage）是系统过渡的锚点宿主。
        // `appElasticPress` 是绘制期 graphicsLayer，而 OPPO 框架按布局期
        // `getLocationInWindow` 取锚点矩形 ⇒ 按住卡片点开视频时动画会从"屏幕上
        // 已不存在的矩形"起飞。锚点真组合时整卡形变必须让位；
        // 非 OPPO 机型（`active=false`）锚点不组合、走普通打开，保留按压反馈。
        // 详见 `BiliCardSurface.elasticPressEnabled` 的 KDoc。
        elasticPressEnabled = !seamless.active
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.smPlus),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
        ) {
            CoverImage(
                url = video.cover,
                contentDescription = video.title,
                modifier = Modifier
                    .weight(0.38f)
                    .aspectRatio(16f / 10f)
            ) {
                SeamlessCardCoverAnchor(seamless, video.target, Modifier.matchParentSize())
                if (durationText != null) {
                    Text(
                        text = durationText,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(BiliSpacing.xsPlus)
                            .background(
                                color = Color.Black.copy(alpha = 0.56f),
                                shape = SpaceDurationBadgeShape
                            )
                            // 角标内边距刻意不用 token：BiliSpacing 最小是 xs=4dp，
                            // 会把 12dp 高的角标撑变形（保留字面量并在此说明）。
                            .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
            Column(
                modifier = Modifier.weight(0.62f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (stats.isNotBlank()) {
                    Text(
                        text = stats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationSec: Long): String {
    val minute = durationSec / 60
    val second = durationSec % 60
    val hour = minute / 60
    return if (hour > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hour, minute % 60, second)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minute, second)
    }
}

private val SpaceCardShape = RoundedCornerShape(BiliRadius.card)
