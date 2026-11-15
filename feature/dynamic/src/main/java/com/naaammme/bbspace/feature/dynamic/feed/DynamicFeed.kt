package com.naaammme.bbspace.feature.dynamic.feed

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.UpListRow
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.DynamicBody
import com.naaammme.bbspace.core.model.DynamicImage
import com.naaammme.bbspace.core.model.DynamicItem
import com.naaammme.bbspace.core.model.DynamicUpList
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget

@Composable
fun DynamicFeed(
    upList: DynamicUpList?,
    items: List<DynamicItem>,
    listState: LazyListState,
    isLoadingMore: Boolean,
    errorMessage: String?,
    loadMoreError: String?,
    onRetryRefresh: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenDynamic: (String) -> Unit,
    onLike: (DynamicItem) -> Unit,
    onRepost: (DynamicItem) -> Unit,
    onShare: (DynamicItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = BiliSpacing.md,
            vertical = BiliSpacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        upList?.let { data ->
            item(
                key = "dynamic_up_list",
                contentType = "dynamic_up_list"
            ) {
                UpListRow(
                    title = data.title,
                    items = data.items,
                    key = { it.uid },
                    name = { it.name },
                    face = { it.face },
                    onClick = { item ->
                        onOpenSpace(SpaceRoute(mid = item.uid, name = item.name))
                    }
                )
            }
        }

        items(
            items = items,
            key = { it.id },
            contentType = { it.type }
        ) { item ->
            DynamicCard(
                item = item,
                onOpenVideo = onOpenVideo,
                onOpenSpace = onOpenSpace,
                onOpenLive = onOpenLive,
                onOpenDynamic = onOpenDynamic,
                onLike = onLike,
                onRepost = onRepost,
                onShare = onShare
            )
        }

        if (isLoadingMore) {
            item(
                key = "dynamic_loading_more",
                contentType = "loading"
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = BiliSpacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (errorMessage != null && items.isNotEmpty()) {
            item(
                key = "dynamic_error",
                contentType = "error"
            ) {
                StateMessageCard(
                    text = errorMessage.ifBlank { "加载动态失败" },
                    isError = true,
                    actionText = "点击重试",
                    onAction = onRetryRefresh
                )
            }
        }

        if (!isLoadingMore && loadMoreError != null) {
            item(
                key = "dynamic_load_more_error",
                contentType = "error"
            ) {
                StateMessageCard(
                    text = loadMoreError.ifBlank { "加载动态失败" },
                    isError = true,
                    actionText = "点击重试加载更多",
                    onAction = onRetryLoadMore
                )
            }
        }
    }
}

@Composable
private fun DynamicCard(
    item: DynamicItem,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenDynamic: (String) -> Unit,
    onLike: (DynamicItem) -> Unit,
    onRepost: (DynamicItem) -> Unit,
    onShare: (DynamicItem) -> Unit
) {
    val liveRoute = item.liveRoute
    val videoTarget = item.videoTarget
    val onClick = {
        when {
            liveRoute != null -> onOpenLive(liveRoute)
            videoTarget != null -> onOpenVideo(videoTarget)
            else -> onOpenDynamic(item.id)
        }
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().appElasticPress(),
        shape = DynamicCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        DynamicCardContent(
            item = item,
            onOpenSpace = onOpenSpace,
            onLike = onLike,
            onRepost = onRepost,
            onOpenDynamic = onOpenDynamic,
            onShare = onShare
        )
    }
}

@Composable
private fun DynamicCardContent(
    item: DynamicItem,
    onOpenSpace: (SpaceRoute) -> Unit,
    onLike: (DynamicItem) -> Unit,
    onRepost: (DynamicItem) -> Unit,
    onOpenDynamic: (String) -> Unit,
    onShare: (DynamicItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        DynamicHeader(item = item, onOpenSpace = onOpenSpace)
        DynamicBodyContent(item = item)
        item.stats?.let { stats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs),
                    modifier = Modifier
                        .clip(DynamicStatChipShape)
                        .clickable { onLike(item) }
                        .padding(horizontal = BiliSpacing.xsPlus, vertical = BiliSpacing.xs)
                ) {
                    // A9：点赞选中态颜色 150ms 渐变 + 图标 spring 弹跳（同 A3 规格）
                    val likedTint by animateColorAsState(
                        targetValue = if (stats.liked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = LocalAnimations.current.short),
                        label = "dynamicLikeTint"
                    )
                    val likeScale = remember { Animatable(1f) }
                    var lastLiked by remember { mutableStateOf(stats.liked) }
                    LaunchedEffect(stats.liked) {
                        if (stats.liked != lastLiked) {
                            lastLiked = stats.liked
                            likeScale.snapTo(0.85f)
                            likeScale.animateTo(
                                1f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "点赞",
                        tint = likedTint,
                        modifier = Modifier
                            .size(15.dp)
                            .graphicsLayer {
                                scaleX = likeScale.value
                                scaleY = likeScale.value
                            }
                    )
                    Text(
                        text = stats.like.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = likedTint
                    )
                }
                RepostStatText(stats.repost, onClick = { onRepost(item) })
                ReplyStatText(stats.reply, onClick = { onOpenDynamic(item.id) })
                ShareStatText(onClick = { onShare(item) })
            }
        }
    }
}

@Composable
private fun RepostStatText(count: Long, onClick: () -> Unit) {
    Text(
        text = "转发 $count",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(DynamicStatChipShape)
            // 全局拖拽反馈（三颗统计芯片共用同一写法，见 DynamicStatChipShape 注释）：
            // 位置在 `clickable` 之前，形变与光斑覆盖整颗芯片。
            .appElasticPress(highlightShape = DynamicStatChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = BiliSpacing.xsPlus, vertical = BiliSpacing.xs)
    )
}

@Composable
private fun ShareStatText(onClick: () -> Unit) {
    Text(
        text = "分享",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(DynamicStatChipShape)
            .appElasticPress(highlightShape = DynamicStatChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = BiliSpacing.xsPlus, vertical = BiliSpacing.xs)
    )
}

@Composable
private fun ReplyStatText(count: Long, onClick: () -> Unit) {
    Text(
        text = "评论 $count",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(DynamicStatChipShape)
            .appElasticPress(highlightShape = DynamicStatChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = BiliSpacing.xsPlus, vertical = BiliSpacing.xs)
    )
}

@Composable
private fun DynamicHeader(
    item: DynamicItem,
    onOpenSpace: (SpaceRoute) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            url = item.author?.avatar,
            contentDescription = item.author?.name ?: "用户",
            modifier = Modifier.size(42.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            val route = item.spaceRoute
            Text(
                text = item.author?.name ?: "动态",
                style = MaterialTheme.typography.titleSmall,
                modifier = if (route == null) {
                    Modifier
                } else {
                    // F-5：作者名是"打开空间"的可点文本，补 Role 与动作说明。
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "打开 UP 主空间"
                    ) { onOpenSpace(route) }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(item.publishedText, item.author?.pubLocation)
                .joinToString(" · ")
                .ifBlank { item.type }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DynamicBodyContent(item: DynamicItem) {
    when (val body = item.body) {
        is DynamicBody.Text -> {
            DynamicText(body.text)
        }

        is DynamicBody.Draw -> {
            body.text?.let { text ->
                DynamicText(text)
            }
            if (body.images.isNotEmpty()) {
                DynamicImageRow(body.images)
            }
        }

        is DynamicBody.Archive -> {
            body.text?.let { text ->
                DynamicText(text)
            }
            DynamicMediaCard(
                title = body.title,
                subTitle = body.subTitle,
                cover = body.cover,
                badge = body.badge
            )
        }

        is DynamicBody.Article -> {
            body.text?.let { text ->
                DynamicText(text)
            }
            DynamicMediaCard(
                title = body.title,
                subTitle = body.subTitle,
                cover = body.cover,
                badge = null
            )
        }

        is DynamicBody.Live -> {
            body.text?.let { text ->
                DynamicText(text)
            }
            DynamicMediaCard(
                title = body.title,
                subTitle = body.subTitle,
                cover = body.cover,
                badge = body.badge
            )
        }

        is DynamicBody.Forward -> {
            body.text?.let { text ->
                DynamicText(text)
            }
            body.origin?.let { origin ->
                // 嵌套在 surfaceContainerLow 卡片内的引用块，用 High 表达层级；
                // 圆角走分组 token（此前 shapes.medium 随 CornerStyle 在 12~16 漂移）。
                val forwardBgColor = MaterialTheme.colorScheme.surfaceContainerHigh
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color = forwardBgColor, shape = DynamicForwardShape)
                        .padding(BiliSpacing.smPlus),
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
                ) {
                    origin.authorName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    origin.bodyText?.let { text ->
                        DynamicText(text)
                    }
                    if (!origin.title.isNullOrBlank() || !origin.cover.isNullOrBlank()) {
                        DynamicMediaCard(
                            title = origin.title.orEmpty(),
                            subTitle = null,
                            cover = origin.cover,
                            badge = origin.badge
                        )
                    }
                }
            }
        }

        is DynamicBody.Unknown -> {
            body.text?.let { text ->
                DynamicText(text)
            }
        }
    }
}

@Composable
private fun DynamicMediaCard(
    title: String,
    subTitle: String?,
    cover: String?,
    badge: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        cover?.let {
            CoverImage(
                url = it,
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 10f),
                shape = DynamicMediaShape
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            badge?.let {
                val badgeBgColor = MaterialTheme.colorScheme.secondaryContainer
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(badgeBgColor, DynamicBadgeShape)
                        .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DynamicImageRow(images: List<DynamicImage>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
        items(
            items = images,
            key = { it.url }
        ) { image ->
            CoverImage(
                url = image.url,
                modifier = Modifier
                    .width(140.dp)
                    .aspectRatio(image.displayAspectRatio()),
                shape = DynamicMediaShape
            )
        }
    }
}

@Composable
private fun DynamicText(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 8,
        overflow = TextOverflow.Ellipsis
    )
}

private fun DynamicImage.displayAspectRatio(): Float {
    return if (width > 0 && height > 0) {
        (width.toFloat() / height.toFloat()).coerceIn(0.72f, 1.5f)
    } else {
        1f
    }
}

/** 动态卡圆角：全站卡片 token（20dp），不再用会随 CornerStyle 漂移的默认 shapes.medium。 */
private val DynamicCardShape = RoundedCornerShape(BiliRadius.card)

/** 统计动作 chip（点赞/转发/评论/分享）圆角：分组 token（16dp）。 */
private val DynamicStatChipShape = RoundedCornerShape(BiliRadius.groupedList)

/** 转发引用块圆角：分组 token（16dp）。 */
private val DynamicForwardShape = RoundedCornerShape(BiliRadius.groupedList)

/** 媒体封面/九宫格圆角：badge token（8dp），与正文卡的大圆角形成层级。 */
private val DynamicMediaShape = RoundedCornerShape(BiliRadius.badge)

/** 角标（"新视频"等）圆角：badge token（8dp）。 */
private val DynamicBadgeShape = RoundedCornerShape(BiliRadius.badge)

