package com.naaammme.bbspace.feature.video.detail

/**
 * 详情摘要区：标题 / UP 主行 / 信息行 / 操作胶囊与各类 chip。
 * 由 VideoDetailPage.kt 拆分而来（纯搬运，未改行为）。
 */

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
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
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberAppElasticPressState

/**
 * 详情页简介内容卡（[CapsuleCard]）形状：与全 app 卡片同一来源（[BiliRadius.card]），
 * 不随 CornerStyle 档位漂移。iOS 档下与原 extraLarge 等值（20dp），MATERIAL 档从
 * 32dp 收敛进统一的大圆角语言。
 */
private val DetailContentCardShape = RoundedCornerShape(BiliRadius.card)

/**
 * 标签 / 「展开全文」等**小胶囊**形状（16dp，[BiliRadius.groupedList]）。
 *
 * 此前两处各写 `MaterialTheme.shapes.extraLarge`，会随 CornerStyle 在 20~32dp 漂移；
 * 标签高约 24dp，16dp 已接近全圆端 —— 与排行/评论页的 chip 同一套圆角语言。
 * 文件级常量而非内联 `shape = RoundedCornerShape(...)`：后者会被
 * `ListAllocationStructureTest` 的具名实参棘轮计数，且每次组合重造一份。
 */
private val DetailChipShape = RoundedCornerShape(BiliRadius.groupedList)

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
        Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)) {
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
            onDownloadClick = onDownloadClick,
            // 计数动效的身份键：切视频时重置"已消费的本地操作请求"，
            // 避免上一支视频的未消费请求让新视频的首个数字凭空动一下。
            identity = ids.aid,
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
            // 只有统计项存在时才补分隔符：否则行首会孤零零挂一个 "·"
            if (parts.isNotEmpty()) {
                Text(
                    text = "  ·  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$reply 评论",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    // 全局拖拽反馈（第 34 轮 T2）："> N 评论"跳转小件。
                    // `highlightShape` 与 `clip(shapes.small)` 同源。
                    .appElasticPress(highlightShape = MaterialTheme.shapes.small)
                    .clickable(onClick = onOpenComments)
                    .padding(horizontal = BiliSpacing.xs, vertical = 2.dp)
            )
        }
    }
}

/**
 * 简介块：折叠时最多 2 行 + "展开"提示；展开后全文 + "收起"。
 *
 * 整块可点（旧版是一个独立的 ToggleChip），点击区域更大、更符合直觉。
 *
 * ## 2026-10-11 排版重做（用户反馈「简介部分内容排版不好看，不符合现代人审美」）
 * 旧版是「两行灰正文 + 一行主色小字『展开』」直接裸排在页面上：正文与"展开"之间只有
 * 4dp，且两者**同字号不同色**，读起来像"正文最后多了一行字"，看不出那是一个可点的动作；
 * 折叠态与展开态之间也没有任何可读的边界。
 *
 * 现代做法（B 站 / YouTube / 抖音的简介区一致）：**正文与动作分开**——
 * 动作给一枚**右对齐的胶囊**（主色底 + 主色字 + 箭头），与正文之间有明确的一段呼吸；
 * 它自己就能被认出"这里可以点"。同时正文的 `lineHeight` 交给主题的 bodyMedium
 * （不再挤在默认行高上），折叠态才读得出"这是被截断的"。
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
            // 全局拖拽反馈（第 34 轮 T2）：简介整块可点（展开/收起）。
            // 整宽 ⇒ 0.6 克制档；`highlightShape` 与 `clip` 同源。
            .appElasticPress(
                state = rememberAppElasticPressState(feedbackStrength = 0.6f),
                highlightShape = MaterialTheme.shapes.large
            )
            .clickable(onClick = onToggle)
            // 上边距把「正文」与上方统计/操作胶囊拉开：简介是**另一段内容**，
            // 不再是紧贴在操作栏下面的一行灰字。
            .padding(top = BiliSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        // 动作胶囊：右对齐（与上方 ActionCapsule 的等分排布形成"这是一条独立动作"的对比），
        // 主色淡底 + 主色字 + 展开/收起箭头 —— 三样都是"可点"的通用语言。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            DescToggleChip(expanded = expanded)
        }
    }
}

/**
 * 「展开 / 收起」动作胶囊。
 *
 * 为什么不用 [SoftChip]：那是**标签**的语言（中性灰底、无箭头、不可点），
 * 拿它当动作入口会与旁边的 `#话题` 标签撞语义（真机上两者本来就并排出现在同一屏）。
 * 这里用主色淡底 + 主色字 + 方向箭头，一眼区分"标签"与"动作"。
 *
 * 尺寸全部走 token（`BiliSpacing` + `MaterialTheme.shapes`），不新增 dp 字面量
 * （`DesignTokenLintTest` 的棘轮）。
 */
@Composable
private fun DescToggleChip(expanded: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = DetailChipShape
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = BiliSpacing.md,
                vertical = BiliSpacing.xsPlus
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Text(
                text = if (expanded) "收起" else "展开全文",
                style = MaterialTheme.typography.labelMedium
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowUp
                } else {
                    Icons.Outlined.KeyboardArrowDown
                },
                contentDescription = null,
                modifier = Modifier.size(BiliSpacing.lg)
            )
        }
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
                // 全局拖拽反馈（第 34 轮 T2）：标签块整块可点（展开/收起）。
                // 用 0.6 克制档 —— FlowRow 里是一排互斥标签，满强度会互相挤。
                .appElasticPress(
                    state = rememberAppElasticPressState(feedbackStrength = 0.6f),
                    highlightShape = MaterialTheme.shapes.large
                )
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
            // 全局拖拽反馈（第 34 轮 T2）：UP 主信息行（整行可点进空间）。
            // 整行宽 ⇒ 0.6 克制档；`highlightShape` 与 `clip` 同源。
            .appElasticPress(
                state = rememberAppElasticPressState(feedbackStrength = 0.6f),
                highlightShape = MaterialTheme.shapes.large
            )
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
    onDownloadClick: () -> Unit,
    /** 身份键（aid）：切视频时重置计数动效状态。 */
    identity: Any? = Unit
) {
    val stat = interaction.stat
    val userState = interaction.userState
    // 本地操作请求计数：只有这里 arm 过的变化才做数字弹跳（首次组合 / 后台刷新不动）。
    // 上游把它放在共享 ViewModel（详情 + 竖屏互动栏共用）；本仓本轮写域只到 detail 侧，
    // 播放器内侧栏未接线（见报告"未移植到播放器侧"）。
    val countRequests = rememberVideoActionCountRequests()
    // A16：三个位都是**可空**真值源 —— `null` = 未确认（未登录 / 服务端没回 req_user），
    // 不是「未点赞 / 0 币 / 未收藏」。折叠成 false 会把未知画成"未选中"。
    val liked = userState?.liked
    val coinated = userState?.coinCount?.let { it > 0 }
    val faved = userState?.faved
    CapsuleCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (stat != null) {
                ActionChip(
                    label = "点赞",
                    value = actionCount(stat.likeValue, interaction.likeDelta),
                    countValue = stat.likeValue + interaction.likeDelta,
                    countRequest = countRequests.like,
                    identity = identity,
                    // 动作图标统一线性；**只有选中态才用实心**（与播放页同一条图标纪律）
                    icon = if (liked == true) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    selected = liked == true,
                    // 未知态：中性占位（同尺寸、不可点），而不是画成"未选中"再翻面
                    enabled = liked != null,
                    onClick = {
                        countRequests.requestLike()
                        onAction(VideoDetailUiAction.Like)
                    },
                    onLongClick = {
                        // 一键三连：三个计数都会变化 ⇒ 三个请求一起 arm（上游同义）。
                        countRequests.requestTriple()
                        onAction(VideoDetailUiAction.Triple)
                    }
                )
                ActionChip(
                    label = "投币",
                    value = actionCount(stat.coinValue, interaction.coinDelta),
                    countValue = stat.coinValue + interaction.coinDelta,
                    countRequest = countRequests.coin,
                    identity = identity,
                    icon = if (coinated == true) Icons.Filled.Paid else Icons.Outlined.Paid,
                    selected = coinated == true,
                    // 投币是**单向**写（只加币），未知不会发错请求 ⇒ 保持可点
                    onClick = {
                        countRequests.requestCoin()
                        onAction(VideoDetailUiAction.Coin)
                    }
                )
                ActionChip(
                    label = "收藏",
                    value = actionCount(stat.favValue, interaction.favDelta),
                    countValue = stat.favValue + interaction.favDelta,
                    countRequest = countRequests.favorite,
                    identity = identity,
                    icon = if (faved == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    selected = faved == true,
                    // 收藏走显式 addIds/delIds（不是取反），未知时最坏是重复收藏 ⇒ 保持可点
                    onClick = {
                        countRequests.requestFavorite()
                        onAction(VideoDetailUiAction.Favorite)
                    }
                )
                ActionChip(
                    label = "分享",
                    value = stat.share,
                    icon = Icons.Outlined.Share,
                    selected = false,
                    onClick = { onAction(VideoDetailUiAction.Share) }
                )
            }
            ActionChip(
                label = "下载",
                value = null,
                icon = Icons.Outlined.Download,
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
        // 长按复制：按铁律**不挂跟手形变**（长按意图会被跟手位移读成拖拽）。
        // 这里连按压都不挂 —— `copyTextOnLongPress` 自带 interactionSource，
        // 再叠一层只按压会让"按住等复制"的手感与其它 chip 不一致却又不跟手，
        // 反而更像坏了。保持原样，报告里单列。
        onLongPressLabel != null -> Modifier.copyTextOnLongPress(text, onLongPressLabel)
        onClick != null -> Modifier
            // 全局拖拽反馈（第 34 轮 T2）：可点的 SoftChip（标签/分区等独立小件）。
            // `highlightShape` 与 `DetailChipShape` 同源，光斑裁在 chip 圆角内。
            .appElasticPress(highlightShape = DetailChipShape)
            .clickable(onClick = onClick)
        else -> Modifier
    }
    // 2026-10-11：底色从 `surfaceContainerHighest` 换成 `surfaceContainerHigh` + 一层描边。
    // 前者是**容器色阶里最重的一档**（用来表达"嵌套最深"），拿它画一排标签会与页面底色
    // 只差一点点明度，真机上读成"一片灰饼"；而 `outlineVariant` 的发丝描边给出了标签的
    // 边界感 —— 现代标签语言的通行做法是"浅底 + 细边"，不靠色差。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = DetailChipShape,
        border = BorderStroke(
            width = BiliSpacing.xs / 8f,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = BiliSpacing.smPlus,
                vertical = BiliSpacing.xsPlus
            )
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
    onLongClick: (() -> Unit)? = null,
    /**
     * A16：状态未确认时传 `false` —— 按钮**留在原位**（同尺寸占位，不隐藏、不移位），
     * 但不可点、并且视觉上退成中性灰。
     *
     * 为什么是"同尺寸中性占位"而不是隐藏：隐藏会让整行 4 个 chip 的 `weight(1f)`
     * 重新分配宽度，状态到达时其它按钮会**横向跳一下**。上游 `a614d2eba` 的修法
     * 明确是「同尺寸中性占位 + 暂时禁用」，不是隐藏。
     */
    enabled: Boolean = true,
    /** 原始计数（未格式化）：用于识别"文本没变但计数真的变了"，并让请求被正确消费。 */
    countValue: Long? = null,
    /** 该动作的本地请求计数（[VideoActionCountRequests] 对应字段）；0 表示不 arm。 */
    countRequest: Int = 0,
    /** 身份键（aid）：切视频时重置计数动效状态。 */
    identity: Any? = Unit
) {
    // A3：选中态颜色渐变 + 图标 spring 弹跳（0.85→1.0，一次回弹）。
    // 时长走 token（此前两处都硬编码 150ms）——动效速度档位与 reduceMotion 必须能作用到它们。
    val actionAnim = LocalAnimations.current
    val iconTint by animateColorAsState(
        targetValue = when {
            // A16：未确认 → 中性灰（既不"点亮"也不"未选中"），让用户一眼看出"还说不准"
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = actionAnim.short),
        label = "actionChipTint"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = actionAnim.short),
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
        // A16：未确认时不挂任何点击/长按处理器。热区与尺寸全部保留（同尺寸占位），
        // 所以状态到达时这一行不会横跳；区别只在"点不动 + 灰一档"。
        !enabled -> Modifier
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )

        // ⚠️ 第 34 轮 T2：这里**刻意不挂** appElasticPress。
        // 本操作栏图标已经有自己的按压动效（下面的 `iconScale` Animatable：
        // 按下放大、松开回弹，见 `graphicsLayer { scaleX = iconScale.value }`）。
        // 再叠一层弹性形变 = 双抖（与 Switch/Slider 同一条判据）。
        // ⇒ 这一行**已经有**拖拽反馈语义，只是实现方式不同，不是"漏接"。
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
        VideoActionCountText(
            text = value ?: label,
            request = countRequest,
            countValue = countValue,
            identity = identity,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
