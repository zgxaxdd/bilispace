package com.naaammme.bbspace.feature.favorite.item

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.LoadMoreGrowthGuard
import com.naaammme.bbspace.core.designsystem.component.ProvideSkeletonBreath
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.VideoListCardSkeleton
import com.naaammme.bbspace.core.designsystem.component.rememberSkeletonBreathOrNull
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.FavoriteContentItem
import com.naaammme.bbspace.core.model.FavoriteContentTarget
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun FavoriteContentList(
    items: List<FavoriteContentItem>,
    isLoadingMore: Boolean,
    errorMessage: String?,
    errorOnLoadMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenContent: (FavoriteContentTarget) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onItemLongPress: ((FavoriteContentItem) -> Unit)? = null,
    onToggleSelect: ((FavoriteContentItem) -> Unit)? = null
) {
    val shouldLoadMore by rememberUpdatedState(canLoadMore)
    val hasLoadMoreError by rememberUpdatedState(errorOnLoadMore)

    // AP-10：增长守卫 —— snapshotFlow 只对 (total, last) 变化发射，风暴面小，
    // 但"在尾部小幅滚动逐次触发"仍是同一缺陷家族：只有列表确实变长才放行下一次。
    val loadMoreGuard = remember { LoadMoreGrowthGuard() }
    LaunchedEffect(items.size) {
        loadMoreGuard.onItemLineChanged(items.size)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to last
        }
            .map { (total, last) ->
                shouldLoadMore &&
                    !hasLoadMoreError &&
                    total > 0 &&
                    last >= total - LOAD_MORE_TRIGGER_OFFSET
            }
            .distinctUntilChanged()
            .collect { should ->
                loadMoreGuard.onVisibility(should, busy = false)
                if (should && loadMoreGuard.tryAcquire()) {
                    onLoadMore()
                }
            }
    }

    // AP-15：呼吸时钟只在"加载更多骨架存在"时创建（骨架区消失即随组合销毁，
    // 不给常驻列表留无限动画）；动效关闭时为 null，骨架完全静止。
    val loadMoreBreath = if (isLoadingMore) rememberSkeletonBreathOrNull() else null

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = BiliSpacing.md,
            vertical = BiliSpacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        items(
            items = items,
            key = { it.key },
            contentType = { it.otype }
        ) { item ->
            // A10：移除收藏条目时淡出+位移回弹收拢
            Box(modifier = Modifier.animateItem()) {
                FavoriteContentCard(
                    item = item,
                    onOpenContent = onOpenContent,
                    selectionMode = selectionMode,
                    selected = item.key in selectedKeys,
                    onItemLongPress = onItemLongPress,
                    onToggleSelect = onToggleSelect
                )
            }
        }

        if (isLoadingMore) {
            items(
                count = LOAD_MORE_SKELETON_COUNT,
                key = { index -> "favorite_content_loading_$index" },
                contentType = { "loading" }
            ) {
                // AP-15：注入共享呼吸 —— N 个骨架一个时钟
                ProvideSkeletonBreath(breath = loadMoreBreath) {
                    VideoListCardSkeleton()
                }
            }
        }

        if (errorMessage != null && errorOnLoadMore) {
            item(
                key = "favorite_content_error",
                contentType = "error"
            ) {
                StateMessageCard(
                    text = errorMessage.ifBlank { "加载收藏失败" },
                    isError = true,
                    actionText = "重试",
                    onAction = onRetry
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FavoriteContentCard(
    item: FavoriteContentItem,
    onOpenContent: (FavoriteContentTarget) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onItemLongPress: ((FavoriteContentItem) -> Unit)?,
    onToggleSelect: ((FavoriteContentItem) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val cardModifier = modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
    val target = item.target
    val clickModifier = when {
        selectionMode && onToggleSelect != null -> Modifier.clickable { onToggleSelect(item) }
        onItemLongPress != null -> Modifier.combinedClickable(
            onClick = { target?.let(onOpenContent) },
            onLongClick = { onItemLongPress(item) }
        )

        target != null -> Modifier.clickable { onOpenContent(target) }
        else -> Modifier
    }
    // 全局拖拽反馈：**只有"普通点击打开"这一支**挂跟手形变。
    // - 选择模式：整卡是单选目标，跟手位移会让多选读起来像拖拽（且与 Checkbox 抢焦点）；
    // - 长按模式：`dragEnabled = false` 只保留按压；
    // - 无 target：不可点，不挂。
    val elasticModifier = when {
        selectionMode && onToggleSelect != null -> Modifier.appElasticPress(dragEnabled = false)
        onItemLongPress != null -> Modifier.appElasticPress(dragEnabled = false)
        target != null -> Modifier.appElasticPress()
        else -> Modifier
    }
    Card(
        modifier = cardModifier.then(elasticModifier).then(clickModifier),
        colors = colors
    ) {
        FavoriteContent(
            item = item,
            selectionBadge = if (selectionMode) selected else null
        )
    }
}

@Composable
private fun FavoriteContent(
    item: FavoriteContentItem,
    modifier: Modifier = Modifier,
    selectionBadge: Boolean? = null
) {
    val meta = buildMetaLine(item)
    val ownerName = item.ownerName?.takeIf { it.isNotBlank() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(BiliSpacing.smPlus),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        CoverImage(
            url = item.cover,
            contentDescription = null,
            modifier = Modifier
                .weight(0.38f)
                .aspectRatio(16f / 10f)
        ) {
            item.playbackDesc?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(BiliSpacing.xsPlus)
                        .background(Color.Black.copy(alpha = 0.56f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            ownerName?.let { name ->
                Text(
                    text = name,
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
        }
        selectionBadge?.let { checked ->
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}

private fun buildMetaLine(item: FavoriteContentItem): String {
    return buildList {
        item.typeDesc?.takeIf { it.isNotBlank() }?.let(::add)
        item.viewText?.takeIf { it.isNotBlank() }?.let { add("播放 $it") }
        item.danmakuText?.takeIf { it.isNotBlank() }?.let { add("弹幕 $it") }
        if (item.isInvalid) add("已失效")
    }.joinToString(" · ")
}

private const val LOAD_MORE_SKELETON_COUNT = 2
private const val LOAD_MORE_TRIGGER_OFFSET = 3
