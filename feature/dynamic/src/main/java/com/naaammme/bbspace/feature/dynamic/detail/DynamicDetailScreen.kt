package com.naaammme.bbspace.feature.dynamic.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImage
import com.naaammme.bbspace.core.designsystem.component.PreviewImageGrid
import com.naaammme.bbspace.core.designsystem.component.SelectableText
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.DynamicDetail
import com.naaammme.bbspace.core.model.DynamicDetailAuthor
import com.naaammme.bbspace.core.model.DynamicDetailParagraph
import com.naaammme.bbspace.core.model.DynamicForwardItem
import com.naaammme.bbspace.core.model.DynamicImage
import com.naaammme.bbspace.core.model.DynamicStats
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.feature.comment.CommentPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicDetailScreen(
    onBack: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    isExpanded: Boolean = false,
    viewModel: DynamicDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.msg.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("动态详情") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(), onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(modifier = Modifier.appElasticPress(),
                        onClick = {
                            ShareUtils.shareText(
                                context,
                                "动态",
                                ShareUtils.dynamicUrl(viewModel.opusId)
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "分享动态"
                        )
                    }
                }
            )
        }
    ) { padding ->
        val detail = state.detail
        when {
            state.errorMessage != null && detail == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(BiliSpacing.xxl),
                    contentAlignment = Alignment.Center
                ) {
                    StateMessageCard(
                        text = state.errorMessage.orEmpty().ifBlank { "加载失败" },
                        isError = true,
                        actionText = "点击重试",
                        onAction = viewModel::retry
                    )
                }
            }

            // AP-16：首屏加载态 —— VM 一直有 isLoading 但屏幕零引用，详情未到且
            // 无错误时整页空白。统一走 StateMessageCard（UI-04 出口），
            // 三态优先级保持 错误 > 加载 > 内容。
            state.isLoading && detail == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(BiliSpacing.xxl),
                    contentAlignment = Alignment.Center
                ) {
                    StateMessageCard(text = "加载中…")
                }
            }

            detail != null -> {
                val commentSubject = if (detail.replyBizId > 0L && detail.replyBizType > 0L) {
                    CommentSubject(
                        oid = detail.replyBizId,
                        type = detail.replyBizType
                    )
                } else {
                    null
                }
                if (isExpanded && commentSubject != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = BiliSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.lg)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            detailContentItems(detail, onLike = viewModel::toggleLike)
                        }
                        CommentPanel(
                            subject = commentSubject,
                            onOpenSpace = onOpenSpace,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(
                                horizontal = BiliSpacing.lg,
                                vertical = BiliSpacing.md
                            )
                        )
                    }
                } else if (commentSubject != null) {
                    CommentPanel(
                        subject = commentSubject,
                        onOpenSpace = onOpenSpace,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(
                            horizontal = BiliSpacing.lg,
                            vertical = BiliSpacing.md
                        ),
                        header = {
                            DetailContent(detail, onLike = viewModel::toggleLike)
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                    ) {
                        DetailContent(detail, onLike = viewModel::toggleLike)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(detail: DynamicDetail, onLike: (() -> Unit)?) {
    DynamicDetailHeader(
        author = detail.author,
        modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md)
    )
    detail.paragraphs.forEach { paragraph ->
        DynamicDetailParagraphItem(paragraph)
    }
    detail.forwardOrigin?.let { forwardOrigin ->
        DynamicForwardOriginCard(
            origin = forwardOrigin,
            modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.sm)
        )
    }
    detail.stats?.let { stats ->
        DynamicDetailStats(stats, onLike)
    }
}

@Composable
private fun DynamicDetailHeader(
    author: DynamicDetailAuthor,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                url = author.face,
                contentDescription = author.name,
                modifier = Modifier.size(44.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = author.name.ifBlank { "用户" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                author.pubTime?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DynamicDetailParagraphItem(paragraph: DynamicDetailParagraph) {
    when (paragraph.type) {
        DynamicDetailParagraph.TYPE_TEXT -> {
            paragraph.text?.let { text ->
                SelectableText(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.xsPlus)
                )
            }
        }
        DynamicDetailParagraph.TYPE_PICTURES -> {
            if (paragraph.images.isNotEmpty()) {
                DynamicDetailImageGrid(
                    images = paragraph.images,
                    modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.xsPlus)
                )
            }
        }
    }
}

@Composable
private fun DynamicDetailImageGrid(images: List<DynamicImage>, modifier: Modifier = Modifier) {
    val previewImages = remember(images) {
        images.map { image ->
            PreviewImage(
                url = image.url,
                width = image.width.toFloat(),
                height = image.height.toFloat()
            )
        }
    }
    PreviewImageGrid(
        images = previewImages,
        modifier = modifier
    )
}

@Composable
private fun DynamicForwardOriginCard(
    origin: DynamicForwardItem,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, DynamicDetailForwardShape)
            .padding(BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
    ) {
        origin.authorName?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        origin.bodyText?.let {
            SelectableText(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!origin.title.isNullOrBlank() || !origin.cover.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().padding(top = BiliSpacing.xs), horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)) {
                origin.cover?.let {
                    CoverImage(
                        url = it,
                        modifier = Modifier.width(132.dp).aspectRatio(1.6f),
                        shape = DynamicDetailMediaShape
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)) {
                    Text(origin.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    origin.badge?.let { badge ->
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, DynamicDetailBadgeShape)
                                .padding(horizontal = BiliSpacing.xsPlus, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicDetailStats(stats: DynamicStats, onLike: (() -> Unit)?) {
    val text = remember(stats) {
        buildString {
            if (stats.repost > 0) append("转发 ${stats.repost}")
            if (stats.reply > 0) {
                if (isNotEmpty()) append("  ")
                append("评论 ${stats.reply}")
            }
            if (onLike == null && stats.like > 0) {
                if (isNotEmpty()) append("  ")
                append("点赞 ${stats.like}")
            }
        }
    }
    if (onLike == null) {
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md)
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs),
            modifier = Modifier
                .clip(DynamicDetailStatChipShape)
                // 全局拖拽反馈：点赞是**有状态的整颗芯片**，按压反馈必须与动态卡片
                // 本身一致（卡片已由 BiliCardSurface 原语继承）。
                // `highlightShape` 传 `DynamicDetailStatChipShape`，光斑裁在胶囊内。
                .appElasticPress(highlightShape = DynamicDetailStatChipShape)
                .clickable(onClick = onLike)
                .padding(horizontal = BiliSpacing.xsPlus, vertical = BiliSpacing.xs)
        ) {
            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = "点赞",
                tint = if (stats.liked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = stats.like.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (stats.liked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun LazyListScope.detailContentItems(detail: DynamicDetail, onLike: (() -> Unit)?) {
    item(key = "detail_author", contentType = "author") {
        DynamicDetailHeader(
            author = detail.author,
            modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md)
        )
    }

    itemsIndexed(
        items = detail.paragraphs,
        key = { index, _ -> "detail_para_$index" },
        contentType = { _, para -> para.type }
    ) { _, paragraph ->
        DynamicDetailParagraphItem(paragraph)
    }

    detail.forwardOrigin?.let { forwardOrigin ->
        item(key = "detail_forward_origin", contentType = "forward_origin") {
            DynamicForwardOriginCard(
                origin = forwardOrigin,
                modifier = Modifier.padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.sm)
            )
        }
    }

    detail.stats?.let { stats ->
        item(key = "detail_stats", contentType = "stats") {
            DynamicDetailStats(stats, onLike)
        }
    }
}

// K-7（2026-10-04 审查报告）：这里原有 `DYNAMIC_DETAIL_TAG = "DynamicDetailScreen"`，
// 全仓零引用（日志 tag 由调用层的 Logger 封装给），已删除。

/** 转发引用块圆角：分组 token（16dp），不再随 CornerStyle 漂移。 */
private val DynamicDetailForwardShape = RoundedCornerShape(BiliRadius.groupedList)

/** 引用块内封面圆角：badge token（8dp）。 */
private val DynamicDetailMediaShape = RoundedCornerShape(BiliRadius.badge)

/** 角标圆角：badge token（8dp）。 */
private val DynamicDetailBadgeShape = RoundedCornerShape(BiliRadius.badge)

/** 点赞 chip 圆角：分组 token（16dp）。 */
private val DynamicDetailStatChipShape = RoundedCornerShape(BiliRadius.groupedList)
