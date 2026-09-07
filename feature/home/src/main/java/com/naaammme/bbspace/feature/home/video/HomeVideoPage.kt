package com.naaammme.bbspace.feature.home.video

import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naaammme.bbspace.core.designsystem.component.AdaptiveMediaGrid
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.SkeletonBlock
import com.naaammme.bbspace.core.designsystem.component.rememberAdaptiveGridColumnCount
import com.naaammme.bbspace.core.designsystem.seamless.CoverExpandOrigin
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.designsystem.seamless.coverExpandTrack
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.ThreePointItem
import com.naaammme.bbspace.core.model.ThreePointReason
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.home.rememberHomeFlingBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeVideoPage(
    items: List<FeedItem>,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    errorMessage: String?,
    toastMessage: String,
    dislikedReasons: Map<String, String>,
    singleColumn: Boolean = false,
    topContentPadding: Dp = 0.dp,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenDynamic: (String) -> Unit,
    onDislike: (FeedItem, ThreePointReason) -> Unit,
    onCancelDislike: (FeedItem) -> Unit,
    onToastShown: () -> Unit,
    // 程序化滚动也会派发嵌套滚动事件，跳回顶部后需通知外层复位顶栏偏移
    onScrollToTop: () -> Unit = {}
) {
    val context = LocalContext.current
    val gridState = rememberLazyStaggeredGridState()
    val homeFling = rememberHomeFlingBehavior()
    var wasRefreshing by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(toastMessage, context) {
        if (toastMessage.isNotEmpty()) {
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            onToastShown()
        }
    }
    // 只以 isRefreshing 为 key：以 items 为 key 会让每次 loadMore 追加都重启本 effect
    LaunchedEffect(isRefreshing) {
        if (wasRefreshing && !isRefreshing && currentItems.isNotEmpty()) {
            gridState.scrollToItem(0)
            // scrollToItem(0) 会派发一次巨大的负向嵌套滚动位移，
            // 若不复位，外层顶栏偏移会被顶到上限 → 顶栏一打开就是隐藏的。
            onScrollToTop()
        }
        wasRefreshing = isRefreshing
    }
    // 官方推荐：返回无缝动画进行中用户开始滑动列表时立即结束动画（让 leash 跟随
    // 列表滚动而非停留在旧位置）。只在返回阶段放行——打开动画期间手指带出的轻微
    // 滚动若触发中断，会把刚启动的打开动画杀掉（系统退回默认窗口动画）。
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && SeamlessTransitionState.returnRunning) {
                OplusViewSeamlessCompat.finishCurrentAnimation()
            }
        }
    }
    // 返回动画末端遮挡渐隐参数：顶栏（含状态栏）遮挡区占屏比；
    // 底部悬浮栏高度在 app 层不可得，bottom 传 0（不做底部渐隐），口径见接入文档
    val density = LocalDensity.current
    val screenHpx = LocalWindowInfo.current.containerSize.height
    val listCover = remember(topContentPadding, screenHpx) {
        if (screenHpx <= 0) {
            null
        } else {
            val topRatio = with(density) { topContentPadding.toPx() } / screenHpx
            topRatio.coerceIn(0f, 1f) to 0f
        }
    }
    AdaptiveMediaGrid(
        items = items,
        isRefreshing = isRefreshing,
        isLoadingMore = isLoadingMore,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        columns = if (singleColumn) 1 else rememberAdaptiveGridColumnCount(),
        flingBehavior = homeFling,
        contentPadding = PaddingValues(
            start = 4.dp,
            top = topContentPadding,
            end = 4.dp,
            bottom = 4.dp
        ),
        errorMessage = errorMessage,
        key = { _, item -> item.actionKey() },
        contentType = { _, item -> item.cardType },
        loadingContent = {
            FeedCardSkeleton(singleColumn = singleColumn)
        }
    ) { item ->
        FeedCard(
            item = item,
            singleColumn = singleColumn,
            listCover = listCover,
            onOpenSpace = onOpenSpace,
            dislikedReason = dislikedReasons[item.actionKey()],
            onDislike = onDislike,
            onCancelDislike = onCancelDislike,
            onClick = {
                if (item.cardGoto == "dynamic") {
                    onOpenDynamic(item.param)
                } else {
                    item.liveRoute?.let(onOpenLive)
                        ?: item.target?.let(onOpenVideo)
                }
            }
        )
    }
}

@Composable
private fun FeedCard(
    item: FeedItem,
    singleColumn: Boolean = false,
    listCover: Pair<Float, Float>? = null,
    onOpenSpace: (SpaceRoute) -> Unit,
    dislikedReason: String?,
    onDislike: (FeedItem, ThreePointReason) -> Unit,
    onCancelDislike: (FeedItem) -> Unit,
    onClick: () -> Unit
) {
    val isDisliked = dislikedReason != null
    val isDynamic = item.cardGoto == "dynamic"
    val canOpen = !isDisliked && (item.target != null || item.liveRoute != null || isDynamic)
    var showMoreMenu by remember(item) { mutableStateOf(false) }
    val containerColor = if (singleColumn) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        // 双列参考 PiliPlus-Max：无卡片底色，封面与文字直接落在页面背景上
        Color.Transparent
    }
    // 无缝动画参数全部由主题 token 解析：圆角与封面 shape 一致（单列随卡片
    // MaterialTheme.shapes.large，双列固定 12dp），兜底色即卡片所在区域的底色
    val seamlessSpec = if (singleColumn) {
        rememberSeamlessCardSpec()
    } else {
        rememberSeamlessCardSpec(
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
    val anchorState = rememberSeamlessAnchorState()
    val seamlessOpener = LocalSeamlessCardOpener.current
    val canSeamless = seamlessOpener != null &&
        item.target != null &&
        item.liveRoute == null

    BiliCardSurface(
        onClick = {
            val target = item.target
            if (
                target != null &&
                canSeamless &&
                // 共享无缝执行器：锚点零绘制（框架取景/绘制真实封面像素）、
                // 注册失败返回 false 走降级（详见 SeamlessCardOpener）
                seamlessOpener.tryOpen(
                    anchor = anchorState.anchor,
                    target = target,
                    coverUrl = item.cover,
                    coverLarge = singleColumn,
                    anchorRadiusPx = seamlessSpec.radiusPx,
                    listCover = listCover
                )
            ) {
                // 无缝注册成功：起飞矩形即封面，不再快照幽灵来源
                return@BiliCardSurface
            }
            // 降级路径：快照来源封面，供详情覆盖层做封面展开 morph；直播卡不参与
            if (item.liveRoute == null) {
                CoverExpandOrigin.record(
                    target = item.target,
                    coverUrl = item.cover,
                    coverLarge = singleColumn
                )
            }
            onClick()
        },
        onLongClick = {
            if (!isDisliked && !item.threePointV2.isNullOrEmpty()) {
                showMoreMenu = true
            }
        },
        enabled = canOpen,
        shape = MaterialTheme.shapes.large,
        containerColor = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 无缝动画锚点：与封面同边界的零绘制透明 View，起飞矩形即封面自身
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (singleColumn) 16f / 9f else 4f / 3f)
                // 封面节点上报窗口坐标：幽灵从封面本身起飞，与所见像素连续
                .coverExpandTrack(item.target.takeIf { item.liveRoute == null })
        ) {
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                shape = if (singleColumn) null else RoundedCornerShape(12.dp),
                coverLarge = singleColumn,
                coverFourThree = !singleColumn,
                modifier = Modifier.matchParentSize()
            ) {
                // 封面左下：时长/竖屏等标记；右下：播放量（保留官方角标风格）
                if (dislikedReason != null) {
                    DislikedOverlay(
                        reason = dislikedReason,
                        onUndo = { onCancelDislike(item) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    item.coverLeftText1?.let { text ->
                        CoverBadge(
                            text = text,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(BiliSpacing.sm)
                        )
                    }
                    item.coverRightText?.let { text ->
                        CoverBadge(
                            text = text,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(BiliSpacing.sm)
                        )
                    }
                }
            }
            if (canSeamless) {
                SeamlessAnchor(anchorState, Modifier.matchParentSize())
            }
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            val spaceRoute = remember(item.args, item.target) {
                item.args?.let { args ->
                    if (args.upId <= 0L && args.upName.isNullOrBlank()) {
                        null
                    } else {
                        SpaceRoute(
                            mid = args.upId,
                            name = args.upName,
                            fromViewAid = args.aid.takeIf { it > 0L }
                                ?: (item.target as? VideoTarget.Ugc)?.aid?.takeIf { it > 0L }
                        )
                    }
                }
            }
            Text(
                text = item.title,
                // 双列压缩标题为 1 行，给图片更多空间；单列大图保留 2 行
                maxLines = if (singleColumn) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                val upName = item.descButton?.text ?: item.args?.upName ?: ""
                if (upName.isNotEmpty()) {
                    // 首页 feed 未下发 UP 头像，直接以名字呈现，更简洁
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = upName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (spaceRoute == null || isDisliked) {
                                Modifier
                            } else {
                                Modifier.clickable { onOpenSpace(spaceRoute) }
                            }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // 竖屏等标签：放在 UP 名行最右侧，节省卡片纵向空间
                item.rcmdReason?.takeIf { it.text.isNotEmpty() }?.let { reason ->
                    Text(
                        text = reason.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    if (showMoreMenu && !item.threePointV2.isNullOrEmpty()) {
        MoreMenuDialog(
            item = item,
            items = item.threePointV2.orEmpty(),
            onDislike = onDislike,
            onDismiss = { showMoreMenu = false }
        )
    }
}

@Composable
private fun CoverBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.62f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun FeedCardSkeleton(singleColumn: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (singleColumn) 16f / 9f else 4f / 3f),
            shape = RoundedCornerShape(12.dp)
        )
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(14.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
        }
    }
}

@Composable
private fun MoreMenuDialog(
    item: FeedItem,
    items: List<ThreePointItem>,
    onDislike: (FeedItem, ThreePointReason) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, menuItem ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(menuItem.title, style = MaterialTheme.typography.bodyLarge)
                    }
                    val options = menuItem.reasons.orEmpty() + menuItem.feedbacks.orEmpty()
                    if (options.isNotEmpty()) {
                        options.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { reason ->
                                    TextButton(
                                        onClick = {
                                            onDismiss()
                                            onDislike(item, reason)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            reason.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DislikedOverlay(
    reason: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onUndo) {
                Text("撤回")
            }
        }
    }
}

private fun FeedItem.actionKey(): String {
    return "$goto|$param|$idx"
}
