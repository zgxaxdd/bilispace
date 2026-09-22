package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberAdaptiveGridColumnCount(
    compact: Int = 2,
    medium: Int = 3,
    expanded: Int = 4
): Int {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        windowSizeClass.isWidthAtLeastBreakpoint(840) -> expanded
        windowSizeClass.isWidthAtLeastBreakpoint(600) -> medium
        else -> compact
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AdaptiveMediaGrid(
    items: List<T>,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState? = null,
    errorMessage: String? = null,
    /**
     * 错误态的就地重试回调。传 null 时默认错误卡不显示重试按钮
     * （调用方若自有整页重试入口，可不传，避免出现两个重试入口）。
     */
    onRetry: (() -> Unit)? = null,
    loadMoreEnabled: Boolean = true,
    /**
     * 尾部预取阈值（条目数）：最后一张可见卡的索引进入「距列表末尾 [loadMoreThreshold]
     * 条」时就请求下一页，而不是等滚到底。
     */
    loadMoreThreshold: Int = 0,
    /**
     * 列表还没填满一屏（且列表一屏都填不满）时，是否允许自动补一页。
     * 调用方（HomeViewModel）自带次数与停滞双重上限，不会变成循环请求。
     */
    autoFillWhenNotScrollable: Boolean = false,
    columns: Int = rememberAdaptiveGridColumnCount(),
    loadingPlaceholderCount: Int = 10,
    // 栅格间距：6 → 10/12（2026-09-22 真机截图对比）。
    // 6dp 是"能塞下就塞下"的密度取向，整屏看过去卡片彼此贴太近、封面色彩直接相邻，
    // 读作拥挤；现代信息流的呼吸感来自**行距大于列距**（横向 10dp 保证双列不散、
    // 纵向 12dp 让每张卡与下一张切开）。这两个值是全局默认，排行/搜索/动态页同享，
    // 所以改这里等于一次性统一全站列表节奏。
    horizontalSpacing: Dp = 10.dp,
    verticalSpacing: Dp = 12.dp,
    flingBehavior: FlingBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(
        // 4 → 10/12：屏幕边距必须**大于**列间距（10dp），否则左右两列会被读成
        // "贴边溢出"，而不是"在页边距里排布"。底部留 12dp 给最后一行收口。
        start = 10.dp,
        top = 0.dp,
        end = 10.dp,
        bottom = 12.dp
    ),
    key: (index: Int, item: T) -> Any = { index, _ -> index },
    contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    loadingContent: @Composable LazyStaggeredGridItemScope.() -> Unit,
    headerContent: (@Composable LazyStaggeredGridItemScope.() -> Unit)? = null,
    emptyContent: (@Composable LazyStaggeredGridItemScope.() -> Unit)? = null,
    errorContent: @Composable LazyStaggeredGridItemScope.(String) -> Unit = { msg ->
        DefaultGridError(msg, onRetry)
    },
    loadMoreContent: @Composable LazyStaggeredGridItemScope.() -> Unit = {
        DefaultGridLoadMore()
    },
    itemContent: @Composable LazyStaggeredGridItemScope.(item: T) -> Unit
) {
    val gridState = state ?: rememberLazyStaggeredGridState()
    val effectiveFling = flingBehavior ?: ScrollableDefaults.flingBehavior()
    val currentItems by rememberUpdatedState(items)
    // 两种「该取下一页」的时机：
    // 1. 尾部预取——最后一张可见卡的下标已进入距列表末尾 loadMoreThreshold 条的范围
    //    （注意：本组件可选地插入 header/error/loading 等前置项，可见项下标会整体偏移，
    //    这里只用它做"距末尾还差多少条"的比较，不当作 items 下标使用）；
    // 2. 首屏填充——列表一屏都填不满（canScrollForward=false）时补一页，
    //    由调用方的额度上限保证不会无限请求。
    val shouldLoadMore by remember(gridState, loadMoreEnabled, loadMoreThreshold, autoFillWhenNotScrollable) {
        derivedStateOf {
            @Suppress("UNCHECKED_CAST")
            val list = currentItems as List<T>
            if (!loadMoreEnabled || list.isEmpty()) return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            // 一屏内容还没渲染出来（lastVisible = -1，布局刚起步）时不要预取，
            // 否则首帧就会白跑一次请求
            if (lastVisible < 0) return@derivedStateOf false
            val tailReached = lastVisible >= list.size - 1 - loadMoreThreshold
            val viewportUnfilled = autoFillWhenNotScrollable && !gridState.canScrollForward
            tailReached || viewportUnfilled
        }
    }

    // 一次取页「尝试」只用一次：见下方 LaunchedEffect 的增长守卫。
    var loadMoreAttempted by remember(gridState) { mutableStateOf(false) }
    val itemCount = items.size

    // 增长复位：列表**确实变长**（上一次取页成功）时复位守卫，允许下一次预取。
    //
    // 为什么必须单独有这一段：下面那个 effect 在「if (loadMoreAttempted) return」处会
    // **提前返回**，因此它自己无法复位守卫；原先唯一的复位途径是 shouldLoadMore 变
    // false——也就是必须「滑离尾部」。但预取阈值只有 6 条，新一页进来后用户通常仍在
    // 末尾 6 条内，shouldLoadMore 持续为 true → 守卫永远为 true → **滑到一定程度就再也
    // 加载不出新内容**（实测表现：向上滑一点再快速下滑才能继续，因为那一下手动把
    // shouldLoadMore 拉成 false 又拉回 true）。
    //
    // 仍然保留"防请求风暴"能力：只有 itemCount 变长才复位。服务端返回空页 / 全是重复
    // 条目时列表不增长，守卫保持 true，不会反复发请求（原设计意图不受影响）。
    LaunchedEffect(itemCount) {
        if (itemCount > 0) loadMoreAttempted = false
    }

    LaunchedEffect(shouldLoadMore, loadMoreEnabled, isRefreshing, isLoadingMore, itemCount) {
        if (!loadMoreEnabled || !shouldLoadMore || isRefreshing || isLoadingMore) {
            if (!shouldLoadMore) loadMoreAttempted = false
            return@LaunchedEffect
        }
        // 增长守卫：命中预取阈值后只请求一次，直到列表**确实变长**才允许下一次。
        // 否则「服务端返回重复卡片 / 返回空页」时条目数不变、可见尾部始终在阈值内，
        // effect 会随 isLoadingMore 的 true→false 反复重启并持续发请求（请求风暴）。
        // 正常路径不受影响：取到新条目 → itemCount 变化 → 守卫复位、继续预取。
        if (loadMoreAttempted) return@LaunchedEffect
        loadMoreAttempted = true
        onLoadMore()
    }

    BiliPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalItemSpacing = verticalSpacing,
            contentPadding = contentPadding,
            flingBehavior = effectiveFling
        ) {
            when {
                items.isEmpty() && isRefreshing -> {
                    items(
                        count = loadingPlaceholderCount,
                        key = { index -> "loading_$index" },
                        contentType = { "loading" }
                    ) {
                        loadingContent()
                    }
                }

                items.isEmpty() -> {
                    when {
                        emptyContent != null -> {
                            item(
                                key = "empty",
                                span = StaggeredGridItemSpan.FullLine,
                                contentType = "empty"
                            ) {
                                emptyContent()
                            }
                        }

                        !errorMessage.isNullOrBlank() -> {
                            val err = errorMessage.orEmpty()
                            item(
                                key = "error",
                                span = StaggeredGridItemSpan.FullLine,
                                contentType = "error"
                            ) {
                                errorContent(err)
                            }
                        }
                    }
                }

                else -> {
                    if (!errorMessage.isNullOrBlank()) {
                        val err = errorMessage.orEmpty()
                        item(
                            key = "error",
                            contentType = "error"
                        ) {
                            errorContent(err)
                        }
                    }

                    if (headerContent != null) {
                        item(
                            key = "header",
                            contentType = "header",
                            span = StaggeredGridItemSpan.FullLine
                        ) {
                            headerContent()
                        }
                    }

                    items(
                        count = items.size,
                        key = { index -> key(index, items[index]) },
                        contentType = { index -> contentType(index, items[index]) }
                    ) { index ->
                        itemContent(items[index])
                    }

                    if (isLoadingMore) {
                        item(
                            key = "loading_more",
                            contentType = "loading_more"
                        ) {
                            loadMoreContent()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网格错误态默认出口。
 *
 * 与全项目其余位置保持一致：空/错误态统一走 [StateMessageCard]（排行榜等页面已是
 * 显式传入该卡片）。此前这里是裸露的 `Text("加载失败: $message")` —— 同一类错误在
 * 不同页面呈现不同形态，且直接把原始 message 暴露在界面上。
 *
 * [onRetry] 非空时给一个就地重试入口：网络类失败最常见的用户动作就是重试，
 * 此前必须先退回再进来。
 */
@Composable
private fun DefaultGridError(message: String, onRetry: (() -> Unit)? = null) {
    StateMessageCard(
        text = message,
        isError = true,
        actionText = if (onRetry != null) "重试" else null,
        onAction = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    )
}

/**
 * 翻页加载指示器（骨架卡 + 微光）。
 *
 * 原先是一个居中的小号转圈。改为骨架卡的好处：
 * - 加载中"预演"即将出现的卡片形状，而不是凭空冒出一个 spinner，观感更连贯；
 * - 骨架自带微光（VideoGridCardSkeleton 内部有 ProvideSkeletonShimmer），
 *   与首屏骨架的加载语言统一。
 *
 * 注意：首页开启提前预取（LOAD_MORE_THRESHOLD=6），网络正常时下一页常在用户滑到
 * 底之前就回来了，此处来不及显示——这是**预期**的（没有等待就不该有加载动画）。
 * 网络较慢时才会看到这段骨架微光。
 */
@Composable
private fun DefaultGridLoadMore() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        VideoGridCardSkeleton()
    }
}
