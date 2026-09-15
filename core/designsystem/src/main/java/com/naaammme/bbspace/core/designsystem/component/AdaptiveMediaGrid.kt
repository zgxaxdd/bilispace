package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberAdaptiveGridColumnCount(
    compact: Int = 2,
    medium: Int = 3,
    expanded: Int = 4
): Int {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
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
    horizontalSpacing: Dp = 6.dp,
    verticalSpacing: Dp = 6.dp,
    flingBehavior: FlingBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = 4.dp,
        top = 0.dp,
        end = 4.dp,
        bottom = 4.dp
    ),
    key: (index: Int, item: T) -> Any = { index, _ -> index },
    contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    loadingContent: @Composable LazyStaggeredGridItemScope.() -> Unit,
    headerContent: (@Composable LazyStaggeredGridItemScope.() -> Unit)? = null,
    emptyContent: (@Composable LazyStaggeredGridItemScope.() -> Unit)? = null,
    errorContent: @Composable LazyStaggeredGridItemScope.(String) -> Unit = { msg ->
        DefaultGridError(msg)
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

@Composable
private fun DefaultGridError(message: String) {
    Text(
        text = "加载失败: $message",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun DefaultGridLoadMore() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}
