package com.naaammme.bbspace.feature.video.detail

/**
 * 详情摘要区：标题 / UP 主行 / 信息行 / 操作胶囊与各类 chip。
 * 由 VideoDetailPage.kt 拆分而来（纯搬运，未改行为）。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.copyTextOnLongPress
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoDetail
import com.naaammme.bbspace.core.model.VideoOwner
import com.naaammme.bbspace.feature.video.VideoInteractionState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoSummarySection(
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
internal fun OwnerRow(
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
internal fun InfoMetaRow(
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
internal fun ActionCapsule(
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
internal fun actionCount(base: Long, delta: Long): String = (base + delta).toString()
@Composable
internal fun CapsuleCard(
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
internal fun SoftChip(
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
internal fun ToggleChip(
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
internal fun RowScope.ActionChip(
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
