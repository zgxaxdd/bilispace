package com.naaammme.bbspace.feature.comment.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ThumbUp
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImageGrid
import com.naaammme.bbspace.core.model.CommentReply
import com.naaammme.bbspace.core.model.CommentUser
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.appSearchElasticPress

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

// 卡片形状提到文件级：内联 `shape = RoundedCornerShape(...)` 会被
// ListAllocationStructureTest 的"具名实参构造 Shape"棘轮计数，且每次组合重造一份。
/** 评论卡：整卡 20dp 大圆角（与首页卡/排行卡同一 [BiliRadius.card] 语言）。 */
private val CommentCardShape = RoundedCornerShape(BiliRadius.card)

/** 楼中楼：卡中嵌卡用小一号的同族圆角（不随 CornerStyle 漂移）。 */
private val ThreadReplyCardShape = RoundedCornerShape(BiliRadius.groupedList)

/** 等级/VIP/勋章小胶囊：8dp 徽章圆角，与封面角标同族。 */
private val MiniChipShape = RoundedCornerShape(BiliRadius.badge)

/** 评论动作 chip（点赞/点踩/回复）圆角：分组 token（16dp），不再随 CornerStyle 漂移。 */
private val CommentActionChipShape = RoundedCornerShape(BiliRadius.groupedList)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentCard(
    reply: CommentReply,
    currentMid: Long,
    // AP-19：卡片"自己查自己"——稳定闭包 + 派生读，busy 集合变化只让**自身状态翻转的
    // 那张卡**重组，不再全可见卡片两轮重组（Set 是不稳定类型，换集合必失败相等性）。
    isReplyBusy: (Long) -> Boolean,
    onAction: (CommentReplyAction) -> Unit
) {
    Card(
        shape = CommentCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            ReplyBody(
                reply = reply,
                currentMid = currentMid,
                isReplyBusy = isReplyBusy,
                onAction = onAction,
                modifier = Modifier.padding(BiliSpacing.lg)
            )

            if (reply.replyCount > 0L) {
                // 极低对比 hairline + 左右内缩：分隔"正文"与"查看回复"两段，
                // 但读起来是留白分组而不是一条硬边框（默认 outlineVariant 全宽会压断卡片）
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = BiliSpacing.lg),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                TextButton(
                    onClick = { onAction(CommentReplyAction.OpenReplies(reply)) },
                    // 全局拖拽反馈（第 34 轮 T2）：本文件是**评论卡**（无 SeamlessAnchor、
                    // 不在播放器祖先链上），"查看 N 条回复"是卡片内的独立整宽入口。
                    // 用 `appSearchElasticPress()`（strength 0.6 宽控件档）：它是整行宽控件，
                    // 满强度按下时整行横移会读作"卡片在晃"。
                    modifier = Modifier
                        .fillMaxWidth()
                        .appSearchElasticPress(),
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
    // AP-19：同 CommentCard —— 稳定闭包 + 派生读。
    isReplyBusy: (Long) -> Boolean,
    onAction: (CommentReplyAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        // 楼中楼回复卡：卡中嵌卡用小一号的同族圆角（见文件级常量注释）
        shape = ThreadReplyCardShape,
        modifier = modifier.fillMaxWidth()
    ) {
        ReplyBody(
            reply = reply,
            currentMid = currentMid,
            isReplyBusy = isReplyBusy,
            onAction = onAction,
            modifier = Modifier.padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.smPlus)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyBody(
    reply: CommentReply,
    currentMid: Long,
    isReplyBusy: (Long) -> Boolean,
    onAction: (CommentReplyAction) -> Unit,
    modifier: Modifier = Modifier
) {
    // AP-19：派生读 —— derivedStateOf 做结构相等去重，只有本卡的 busy 翻转才失效本卡。
    val replyBusy by remember(reply.rpid) { derivedStateOf { isReplyBusy(reply.rpid) } }
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
        // 头像按下反馈（上游 `76839cb22` 评论动效统一的"可点元素按压"一部分）：
        // 按下缩到 0.92、松开回 1，时长/曲线走 LocalAnimations token；
        // 值只在 graphicsLayer 里读（绘制期），按压过程不按帧重组整条评论。
        // reduceMotion（App 速度档=关 / 系统移除动画）时直接吸附，不播放缩放。
        val avatarInteraction = remember { MutableInteractionSource() }
        val avatarPressed by avatarInteraction.collectIsPressedAsState()
        val avatarAnim = LocalAnimations.current
        val avatarScale = remember { Animatable(1f) }
        LaunchedEffect(avatarPressed, avatarAnim.reduceMotion) {
            val target = if (avatarPressed && !avatarAnim.reduceMotion) 0.92f else 1f
            if (avatarAnim.reduceMotion) {
                avatarScale.snapTo(1f)
            } else {
                avatarScale.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = avatarAnim.short,
                        easing = avatarAnim.standard
                    )
                )
            }
        }
        Surface(
            onClick = { onAction(CommentReplyAction.OpenUser(reply.user)) },
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = avatarScale.value
                    scaleY = avatarScale.value
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            interactionSource = avatarInteraction
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
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
                ) {
                    Text(
                        modifier = Modifier
                            // 全局拖拽反馈（第 34 轮 T2）：作者名是**独立可点文本**。
                            //
                            // ⚠️ 只挂这一处文字，**不挂**同行的头像 `Surface`：头像已经有
                            // 自己的按压缩放（下面 avatarScale 0.92），再叠一层弹性形变
                            // = 双抖（与 Switch/Slider 同一条判据）。
                            .appElasticPress()
                            .clickable {
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
                    val rateEnabled = !replyBusy
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
                TextButton(
                    onClick = { onAction(CommentReplyAction.Reply(reply)) },
                    // 全局拖拽反馈（第 34 轮 T2）：行尾"回复"是独立小件。
                    modifier = Modifier.appElasticPress()
                ) {
                    Text(
                        text = "回复",
                        // 行内动作用 labelMedium（12 Medium）而非 TextButton 默认的 13 Bold：
                        // 让"作者名 > 正文 > 动作"的顺序读得出来，回复不再是行里最重的一块
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReplyMenuButton(
                    busy = replyBusy,
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
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
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
            enabled = !busy,
            // 全局拖拽反馈（第 34 轮 T2）：行尾"更多"图标钮（本文件无无缝锚点）。
            modifier = Modifier.appElasticPress()
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
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
                    },
                    // 全局拖拽反馈：删除确认框的动作（浮层小件）。
                    modifier = Modifier.appElasticPress()
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.appElasticPress()
                ) {
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
            .clip(CommentActionChipShape)
            // 全局拖拽反馈（第 34 轮 T2）：点赞/点踩是**独立小 chip**，不参与过渡。
            // `highlightShape` 与 `clip(CommentActionChipShape)` 同源，光斑裁在 chip 圆角内。
            // `enabled` 透传：busy 时该 chip 不可点，形变也一并关掉（否则按下去有无反馈却不响应）。
            .appElasticPress(enabled = enabled, highlightShape = CommentActionChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        // 动作图标统一线性语言：只有"已点赞"这个选中态才用实心
        Icon(
            imageVector = if (selected) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
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
        // 等级/VIP/勋章胶囊：8dp 徽章圆角（原 shapes.extraLarge 随 CornerStyle 漂移）
        shape = MiniChipShape
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
