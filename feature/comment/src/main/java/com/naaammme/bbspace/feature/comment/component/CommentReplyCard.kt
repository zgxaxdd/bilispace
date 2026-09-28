package com.naaammme.bbspace.feature.comment.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImageGrid
import com.naaammme.bbspace.core.model.CommentReply
import com.naaammme.bbspace.core.model.CommentUser
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

internal sealed interface CommentReplyAction {
    data class Check(val rpid: Long) : CommentReplyAction
    data class Translate(val rpid: Long) : CommentReplyAction
    data class Delete(val reply: CommentReply) : CommentReplyAction
    data class Reply(val reply: CommentReply) : CommentReplyAction
    data class OpenReplies(val reply: CommentReply) : CommentReplyAction
    data class OpenUser(val user: CommentUser) : CommentReplyAction
    data class OpenOriginalContent(val reply: CommentReply) : CommentReplyAction
    data class Like(val reply: CommentReply) : CommentReplyAction
    data class Dislike(val reply: CommentReply) : CommentReplyAction
    data class Share(val reply: CommentReply) : CommentReplyAction
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentCard(
    reply: CommentReply,
    currentMid: Long,
    busyReplyIds: Set<Long>,
    onAction: (CommentReplyAction) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            ReplyBody(
                reply = reply,
                currentMid = currentMid,
                busyReplyIds = busyReplyIds,
                onAction = onAction,
                modifier = Modifier.padding(BiliSpacing.lg)
            )

            if (reply.replyCount > 0L) {
                HorizontalDivider()
                TextButton(
                    onClick = { onAction(CommentReplyAction.OpenReplies(reply)) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(
                        text = reply.replyEntryText
                            ?.takeIf(String::isNotBlank)
                            ?: "查看 ${reply.replyCount} 条回复",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BiliSpacing.sm, vertical = BiliSpacing.xs)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThreadReplyCard(
    reply: CommentReply,
    currentMid: Long,
    busyReplyIds: Set<Long>,
    onAction: (CommentReplyAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        // 楼中楼回复卡：groupedList(16) 固定——卡中嵌卡用小一号的同族圆角，
        // 不随 CornerStyle 漂移（iOS 档下与原 shapes.large 等值）
        shape = RoundedCornerShape(BiliRadius.groupedList),
        modifier = modifier.fillMaxWidth()
    ) {
        ReplyBody(
            reply = reply,
            currentMid = currentMid,
            busyReplyIds = busyReplyIds,
            onAction = onAction,
            modifier = Modifier.padding(horizontal = BiliSpacing.md, vertical = 10.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyBody(
    reply: CommentReply,
    currentMid: Long,
    busyReplyIds: Set<Long>,
    onAction: (CommentReplyAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val previewImages = remember(reply.pictures) {
        reply.pictures.map { picture ->
            PreviewImage(
                url = picture.url,
                width = picture.width,
                height = picture.height
            )
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            onClick = { onAction(CommentReplyAction.OpenUser(reply.user)) },
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            AvatarImage(
                url = reply.user.face,
                contentDescription = reply.user.name,
                modifier = Modifier.fillMaxSize(),
                fallbackContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                fallbackContent = {
                    Text(
                        text = reply.user.name.take(1).ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        modifier = Modifier.clickable {
                            onAction(CommentReplyAction.OpenUser(reply.user))
                        },
                        text = reply.user.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                    ) {
                        reply.user.level?.let { level ->
                            MiniChip("Lv.$level")
                        }
                        reply.user.vipLabel?.takeIf(String::isNotBlank)?.let { vip ->
                            MiniChip(vip)
                        }
                        reply.user.medal?.let { medal ->
                            MiniChip(
                                if (medal.level > 0) {
                                    "${medal.name} ${medal.level}"
                                } else {
                                    medal.name
                                }
                            )
                        }
                    }
                }
                reply.topLabel?.let { label ->
                    MiniChip(label)
                }
            }

            ReplyMessage(reply)

            if (previewImages.isNotEmpty()) {
                PreviewImageGrid(
                    images = previewImages
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isSelf = currentMid > 0L && reply.user.mid == currentMid
                Row(
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = reply.timeText.ifBlank { "刚刚" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    reply.locationText.takeIf(String::isNotBlank)?.let { location ->
                        Text(
                            text = location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val rateEnabled = reply.rpid !in busyReplyIds
                    ReplyRateButton(
                        contentDescription = "点踩",
                        selected = reply.disliked,
                        enabled = rateEnabled,
                        rotate = true,
                        onClick = { onAction(CommentReplyAction.Dislike(reply)) }
                    )
                    ReplyRateButton(
                        contentDescription = "点赞",
                        selected = reply.liked,
                        enabled = rateEnabled,
                        count = reply.likeCount,
                        onClick = { onAction(CommentReplyAction.Like(reply)) }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { onAction(CommentReplyAction.Reply(reply)) }) {
                    Text("回复")
                }
                ReplyMenuButton(
                    busy = reply.rpid in busyReplyIds,
                    showCheck = isSelf,
                    canDelete = isSelf,
                    onCheck = { onAction(CommentReplyAction.Check(reply.rpid)) },
                    onTranslate = { onAction(CommentReplyAction.Translate(reply.rpid)) },
                    onDelete = { onAction(CommentReplyAction.Delete(reply)) },
                    onShare = { onAction(CommentReplyAction.Share(reply)) }
                )
            }
        }
    }
}

@Composable
private fun ReplyMessage(reply: CommentReply) {
    val message = reply.message.takeIf(String::isNotBlank)
    val translated = reply.translatedMessage?.takeIf(String::isNotBlank)
    if (message == null && translated == null) return
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        reply.parentName?.takeIf(String::isNotBlank)?.let { name ->
            Text(
                text = "回复 @$name",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        message?.let {
            CommentRichText(
                text = it,
                emotes = reply.emotes,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        translated?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReplyMenuButton(
    busy: Boolean,
    showCheck: Boolean,
    canDelete: Boolean,
    onCheck: () -> Unit,
    onTranslate: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var show by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { show = true },
            enabled = !busy
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(
            expanded = show,
            onDismissRequest = { show = false }
        ) {
            if (showCheck) {
                DropdownMenuItem(
                    text = { Text("评论检查") },
                    onClick = {
                        show = false
                        onCheck()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("评论翻译") },
                onClick = {
                    show = false
                    onTranslate()
                }
            )
            DropdownMenuItem(
                text = { Text("分享评论") },
                onClick = {
                    show = false
                    onShare()
                }
            )
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text("删除评论") },
                    onClick = {
                        show = false
                        confirmDelete = true
                    }
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除评论") },
            text = { Text("确认删除这条评论吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ReplyRateButton(
    contentDescription: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    rotate: Boolean = false,
    count: Long? = null
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ThumbUp,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(15.dp)
                .rotate(if (rotate) 180f else 0f)
        )
        count?.let { value ->
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                modifier = Modifier.padding(start = BiliSpacing.xs)
            )
        }
    }
}

@Composable
private fun MiniChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = BiliSpacing.sm, vertical = BiliSpacing.xs),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
