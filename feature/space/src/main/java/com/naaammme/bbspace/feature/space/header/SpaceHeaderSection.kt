package com.naaammme.bbspace.feature.space.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImageDialog
import com.naaammme.bbspace.core.designsystem.theme.asBold
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.SelectableText
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import com.naaammme.bbspace.feature.space.SpaceHeaderUiState
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

/** VIP 角标底盘（文件级常量，同上）。 */
private val SpaceVipBadgeShape = RoundedCornerShape(BiliRadius.badge)

/** 统计/标签 chip 底盘（文件级常量，同上）。 */
private val SpaceChipShape = RoundedCornerShape(BiliRadius.groupedList)


internal fun LazyListScope.spaceHeaderSection(
    state: SpaceHeaderUiState,
    onOpenFollowings: () -> Unit,
    onOpenFollowers: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleBlock: () -> Unit
) {
    state.bannerUrl?.let { banner ->
        item(
            key = "header_banner",
            contentType = "banner"
        ) {
            BannerCard(imageUrl = banner)
        }
    }

    item(
        key = "header_profile",
        contentType = "profile"
    ) {
        ProfileCard(
            state = state,
            onOpenFollowings = onOpenFollowings,
            onOpenFollowers = onOpenFollowers,
            onToggleFollow = onToggleFollow,
            onToggleBlock = onToggleBlock
        )
    }
}

@Composable
private fun BannerCard(imageUrl: String) {
    // 不可点：BiliCardSurface 的按压缩放只在传了 onClick/onLongClick 时才挂
    // （见其内部 `if (onClick != null || onLongClick != null)`），这里两个都不传
    // ⇒ 两张头部卡（Banner / Profile）都没有任何缩放反馈（本轮 lead 条件 1）。
    BiliCardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = SpaceCardShape
    ) {
        BiliAsyncImage(
            url = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 6f),
            contentScale = ContentScale.Crop
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    state: SpaceHeaderUiState,
    onOpenFollowings: () -> Unit,
    onOpenFollowers: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleBlock: () -> Unit
) {
    val profile = state.profile
    val faceUrl = profile.face
    var showAvatarPreview by remember { mutableStateOf(false) }
    var pendingRelationAction by remember { mutableStateOf<RelationConfirmAction?>(null) }

    if (showAvatarPreview && !faceUrl.isNullOrBlank()) {
        PreviewImageDialog(
            images = listOf(PreviewImage(url = faceUrl)),
            startIdx = 0,
            onDismiss = { showAvatarPreview = false }
        )
    }

    pendingRelationAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingRelationAction = null },
            title = { Text(if (action == RelationConfirmAction.BLOCK) "拉黑用户" else "取消关注") },
            text = { Text(if (action == RelationConfirmAction.BLOCK) "确定要拉黑TA吗？" else "确定要取消关注TA吗？") },
            dismissButton = {
                TextButton(
                    onClick = { pendingRelationAction = null },
                    // 全局拖拽反馈（第 34 轮 T2）：关注/拉黑确认框的两个动作（浮层小件）。
                    modifier = Modifier.appElasticPress()
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRelationAction = null
                        when (action) {
                            RelationConfirmAction.UNFOLLOW -> onToggleFollow()
                            RelationConfirmAction.BLOCK -> onToggleBlock()
                        }
                    },
                    modifier = Modifier.appElasticPress()
                ) {
                    Text(if (action == RelationConfirmAction.BLOCK) "拉黑" else "取消关注")
                }
            }
        )
    }

    // 不可点：BiliCardSurface 的按压缩放只在传了 onClick/onLongClick 时才挂
    // （见其内部 `if (onClick != null || onLongClick != null)`），这里两个都不传
    // ⇒ 两张头部卡（Banner / Profile）都没有任何缩放反馈（本轮 lead 条件 1）。
    BiliCardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = SpaceCardShape
    ) {
        Column(
            modifier = Modifier.padding(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarImage(
                    url = faceUrl,
                    contentDescription = profile.name,
                    modifier = Modifier
                        .size(72.dp)
                        // 全局拖拽反馈（第 34 轮 T2）：头像点开大图（`showAvatarPreview`）
                        // 是独立交互，不参与任何无缝过渡。`enabled` 透传 —— 没脸图时
                        // 这颗本来点不动，形变也不该出现。
                        .appElasticPress(enabled = !faceUrl.isNullOrBlank())
                        .clickable(enabled = !faceUrl.isNullOrBlank()) {
                            showAvatarPreview = true
                        },
                    fallbackContent = {
                        Text(
                            text = profile.name.take(1),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
                ) {
                    SelectableText(
                        text = profile.name,
                        style = MaterialTheme.typography.titleLarge.asBold()
                    )
                    SelectableText(
                        text = "UID ${profile.mid}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectableText(
                            text = "Lv${profile.level}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        profile.vipLabel?.let { vipLabel ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = SpaceVipBadgeShape
                            ) {
                                Text(
                                    text = vipLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    // 角标内边距刻意不用 token：BiliSpacing 最小是 xs=4dp，
                                    // 会把 12dp 高的角标撑变形（保留字面量并在此说明）。
                                    modifier = Modifier.padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (profile.sign.isNotBlank()) {
                SelectableText(
                    text = profile.sign,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                SpaceStatChip("粉丝", profile.fansCount.toString(), onClick = onOpenFollowers)
                SpaceStatChip("关注", profile.followingCount.toString(), onClick = onOpenFollowings)
                if (profile.likeCount > 0L) {
                    SpaceStatChip("获赞", profile.likeCount.toString())
                }
                SpaceStatChip("视频", profile.videoCount.toString())
                if (profile.articleCount > 0) {
                    SpaceStatChip("图文", profile.articleCount.toString())
                }
                if (profile.seasonCount > 0) {
                    SpaceStatChip("合集", profile.seasonCount.toString())
                }
                if (profile.seriesCount > 0) {
                    SpaceStatChip("系列", profile.seriesCount.toString())
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                profile.tags.forEach { tag ->
                    TagChip(text = tag)
                }
                
                val isFollowed = profile.relation == 1
                val followText = when {
                    isFollowed && profile.guestRelation == 1 -> "互相关注"
                    isFollowed -> "已关注"
                    profile.guestRelation == 1 -> "已被关注,回关"
                    else -> "关注"
                }
                if (!state.isSelf) {
                    val isBlocked = profile.relation == -1

                    if (!isBlocked) {
                        val buttonColors = if (!isFollowed) {
                            androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        } else {
                            androidx.compose.material3.ButtonDefaults.buttonColors()
                        }
                        androidx.compose.material3.Button(
                            onClick = {
                                if (isFollowed) {
                                    pendingRelationAction = RelationConfirmAction.UNFOLLOW
                                } else {
                                    onToggleFollow()
                                }
                            },
                            enabled = state.isLogin,
                            colors = buttonColors,
                            contentPadding = PaddingValues(horizontal = BiliSpacing.md, vertical = BiliSpacing.xs),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(text = followText, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    val blockText = when {
                        isBlocked && profile.guestRelation == -1 -> "互相拉黑"
                        isBlocked -> "取消拉黑"
                        profile.guestRelation == -1 -> "已被拉黑,回拉"
                        else -> "拉黑"
                    }
                    val blockButtonColors = if (!isBlocked) {
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.buttonColors()
                    }

                    androidx.compose.material3.Button(
                        onClick = {
                            if (isBlocked) {
                                onToggleBlock()
                            } else {
                                pendingRelationAction = RelationConfirmAction.BLOCK
                            }
                        },
                        enabled = state.isLogin,
                        colors = blockButtonColors,
                        contentPadding = PaddingValues(horizontal = BiliSpacing.md, vertical = BiliSpacing.xs),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(text = blockText, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private enum class RelationConfirmAction { UNFOLLOW, BLOCK }

@Composable
private fun SpaceStatChip(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    val shape = SpaceChipShape
    Surface(
        modifier = if (onClick != null) {
            Modifier
                .clip(shape)
                // 全局拖拽反馈（第 34 轮 T2）：粉丝/关注等**统计 chip** 是卡片内的
                // 独立小件（本文件无 SeamlessAnchor，两张头卡也刻意不传 onClick）。
                // 只有可点支挂（`onClick != null`），不可点时连按压状态都不该有。
                .appElasticPress(highlightShape = shape)
                .clickable(onClick = onClick)
        } else {
            Modifier
        },
        color = MaterialTheme.colorScheme.surface,
        shape = shape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BiliSpacing.smPlus, vertical = BiliSpacing.xsPlus),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectableText(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectableText(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TagChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = SpaceChipShape
    ) {
        SelectableText(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = BiliSpacing.smPlus, vertical = BiliSpacing.xsPlus)
        )
    }
}

private val SpaceCardShape = RoundedCornerShape(BiliRadius.card)
