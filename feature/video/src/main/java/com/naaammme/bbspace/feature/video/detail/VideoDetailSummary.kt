package com.naaammme.bbspace.feature.video.detail

/**
 * 详情摘要区：标题 / UP 主行 / 信息行 / 操作胶囊与各类 chip。
 * 由 VideoDetailPage.kt 拆分而来（纯搬运，未改行为）。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.copyTextOnLongPress
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoDetail
import com.naaammme.bbspace.core.model.VideoOwner
import com.naaammme.bbspace.feature.video.VideoInteractionState

/**
 * 详情页简介内容卡（[CapsuleCard]）形状：与全 app 卡片同一来源（[BiliRadius.card]），
 * 不随 CornerStyle 档位漂移。iOS 档下与原 extraLarge 等值（20dp），MATERIAL 档从
 * 32dp 收敛进统一的大圆角语言。
 */
private val DetailContentCardShape = RoundedCornerShape(BiliRadius.card)

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
    val anim = LocalAnimations.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 简介/标签展开折叠 300ms（long）Emphasized，随内容高度平滑过渡
            .animateContentSize(
                animationSpec = tween(durationMillis = anim.long, easing = anim.emphasized)
            ),
        // 版式节奏：标题 → 作者 → 统计 → 操作 → 简介，逐级递进。
        // 用 md(12dp) 而不是更松的间距，是为了在"信息少"与"不拥挤"之间取平衡；
        // 标题与作者之间额外收紧（靠 TitleBlock 内部 6dp），让它们读作一组。
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
    ) {
        // ── 标题块 ────────────────────────────────────────────────────────
        // 标题与作者是一组信息（"这是谁的什么视频"），因此内部间距比组间更小。
        // AV/BV 号不再单独占 chip：它们是需要时才要的技术标识，收进长按复制，
        // 视觉上让位给内容本身（旧版 7 个 chip 并排是信息密度过高的主因）。
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
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
        }

        // ── 统计行 ────────────────────────────────────────────────────────
        // 一行读完：播放 · 弹幕 · 评论 · 时间。用 `·` 分隔的纯文本而非 chip，
        // 同样的信息量，视觉重量降低一个量级。
        StatLine(
            detail = detail,
            ids = ids,
            onOpenComments = onOpenComments
        )

        ActionCapsule(
            interaction = interaction,
            onAction = onAction,
            onDownloadClick = onDownloadClick
        )

        // ── 简介 ──────────────────────────────────────────────────────────
        // 默认折叠，整行可点展开（不再用一个独立 chip 占位）。
        if (detail.desc.isNotBlank()) {
            DescBlock(
                desc = detail.desc,
                expanded = descOn,
                onToggle = onToggleDesc
            )
        }

        // ── 标签 ──────────────────────────────────────────────────────────
        if (detail.tags.isNotEmpty()) {
            TagBlock(
                tags = detail.tags,
                expanded = tagOn,
                onToggle = onToggleTag
            )
        }
    }
}

/**
 * 统计行：播放 · 弹幕 · 评论 · 发布时间，单行小字。
 *
 * 设计取舍：旧版把每项做成独立 chip（还有 AV/BV 号），一屏 7 个胶囊，
 * 视觉噪声盖过了内容。这里压成一行 `·` 分隔的文本——**信息一条不少，
 * 但视觉重量降一个量级**。评论可点（进入评论区），其余不可点。
 */
@Composable
private fun StatLine(
    detail: VideoDetail,
    ids: ResolvedVideoIds,
    onOpenComments: () -> Unit
) {
    val parts = remember(detail.stat, detail.pubTs) {
        buildList {
            detail.stat?.let { stat ->
                stat.view.takeIf(String::isNotBlank)?.let { add("$it 播放") }
                stat.danmaku.takeIf(String::isNotBlank)?.let { add("$it 弹幕") }
            }
            detail.pubTs?.let { add(formatCompactPubTime(it)) }
        }
    }
    if (parts.isEmpty() && detail.stat == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = parts.joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        // 评论单独可点：它是唯一有"跳转"语义的统计项，用主色区分可点性
        detail.stat?.reply?.takeIf(String::isNotBlank)?.let { reply ->
            Text(
                text = "  ·  ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$reply 评论",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onOpenComments)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 简介块：折叠时最多 2 行 + "展开"提示；展开后全文 + "收起"。
 *
 * 整块可点（旧版是一个独立的 ToggleChip），点击区域更大、更符合直觉。
 */
@Composable
private fun DescBlock(
    desc: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
    ) {
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 标签块：折叠时只显示一行标签；展开后换行铺满。
 *
 * 用 `maxLines = 1` + FlowRow 的天然换行能力实现——折叠态靠"单行"约束，
 * 展开态放开。比单独做一个"展开标签"按钮更省视觉位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagBlock(
    tags: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
            // 折叠态只允许一行：FlowRow 会按 maxLines 截断，多余的收进"展开"
            maxLines = if (expanded) Int.MAX_VALUE else 1
        ) {
            tags.forEach { tag ->
                SoftChip(tag)
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
        // 简介内容卡与全 app 卡片同一语言（BiliRadius.card=20）；此前
        // shapes.extraLarge 随 CornerStyle 档位在 20~32dp 漂移
        shape = DetailContentCardShape,
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
/**
 * 统计行用的**紧凑**发布时间。
 *
 * 为什么不用 [formatPubTime]（`yyyy-MM-dd HH:mm`，16 字符）：统计行是一整行
 * `播放 · 弹幕 · 时间 · 评论`，16 字符的时间会把整行撑爆、末尾被 Ellipsis 截断
 * （实测截图里时间显示成 `2026-09-14 13:...`，同时把"评论"挤出屏幕）。
 *
 * 格式选择与项目内既有范式（`CommentRepository.formatReplyTime`）一致：
 * 近期用相对时间（信息量更高、更短），久远用 `yyyy-MM-dd`（8~10 字符）。
 */
internal fun formatCompactPubTime(ts: Long): String {
    val timeMs = ts * 1000
    val now = System.currentTimeMillis()
    val diff = now - timeMs
    if (diff < 0) return DateFormat.format("yyyy-MM-dd", timeMs).toString()
    return when {
        diff < MINUTE_MS -> "刚刚"
        diff < HOUR_MS -> "${diff / MINUTE_MS}分钟前"
        diff < DAY_MS -> "${diff / HOUR_MS}小时前"
        diff < 2 * DAY_MS -> "昨天"
        diff < 30 * DAY_MS -> "${diff / DAY_MS}天前"
        else -> DateFormat.format("yyyy-MM-dd", timeMs).toString()
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

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
    // A3：选中态颜色 150ms 渐变 + 图标 spring 弹跳（0.85→1.0，一次回弹）
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 150),
        label = "actionChipTint"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 150),
        label = "actionChipText"
    )
    val iconScale = remember { Animatable(1f) }
    var lastSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != lastSelected) {
            lastSelected = selected
            iconScale.snapTo(0.85f)
            iconScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
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
            // 触控目标下限**显式声明**。按内容算这一列本来就有约 56dp
            // （图标 22 + 间隙 2 + labelMedium 行高约 16，再加上下各 8 的内边距），
            // 所以这条在现状下不改变任何像素 —— 它把"碰巧够高"变成"保证够高"：
            // 日后有人去掉文字行、或把图标改小，56dp 会不知不觉掉到 48 以下，
            // 而 Material 规范要求的 48dp 触控目标没有任何编译期检查会拦住它。
            .defaultMinSize(minHeight = 48.dp)
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
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                }
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
