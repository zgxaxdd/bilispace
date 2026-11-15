package com.naaammme.bbspace.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.component.ProvideSkeletonBreath
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.VideoListCardSkeleton
import com.naaammme.bbspace.core.designsystem.component.rememberSkeletonBreathOrNull
import com.naaammme.bbspace.core.model.HistoryTab
import com.naaammme.bbspace.core.model.HistoryTarget
import com.naaammme.bbspace.feature.history.component.HistoryItemCard
import com.naaammme.bbspace.feature.history.component.HistoryListLoading
import com.naaammme.bbspace.feature.history.component.LOAD_MORE_SKELETON_COUNT
import com.naaammme.bbspace.feature.history.component.LoadMoreTrigger
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onSearch: (HistoryTab) -> Unit,
    onOpenHistoryTarget: (HistoryTarget?) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LoadMoreTrigger(
        listState = listState,
        canLoadMore = { state.canLoadMore },
        isLoadingMore = { state.isLoadingMore },
        hasError = { state.errorMessage != null },
        onLoadMore = viewModel::loadMore
    )

    LaunchedEffect(state.tab) {
        val needScrollTop = listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        if (needScrollTop && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(0)
        }
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("历史记录") },
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(), onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(modifier = Modifier.appElasticPress(), onClick = { onSearch(state.tab) }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索历史记录"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FilledTabRow(
                tabs = HistoryTab.entries.map { it.title },
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> viewModel.selectTab(HistoryTab.entries[index]) },
                modifier = Modifier
            )

            BiliPullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    state.isLoading && state.items.isEmpty() -> {
                        HistoryListLoading(
                            skeletonPrefix = "history_skeleton",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    state.errorMessage != null && state.items.isEmpty() -> {
                        StateMessageCard(
                            text = state.errorMessage.orEmpty().ifBlank { "加载历史记录失败" },
                            modifier = Modifier.fillMaxSize(),
                            isError = true,
                            actionText = "重试",
                            onAction = viewModel::refresh
                        )
                    }

                    state.items.isEmpty() -> {
                        StateMessageCard(
                            text = "暂无${state.tab.title}历史",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        // AP-15：呼吸时钟只在"加载更多骨架存在"时创建（骨架区消失即随组合
                        // 销毁，不给常驻列表留无限动画）；动效关闭时为 null，骨架完全静止。
                        val loadMoreBreath = if (state.isLoadingMore) rememberSkeletonBreathOrNull() else null
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = BiliSpacing.md,
                                vertical = BiliSpacing.md
                            ),
                            verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
                        ) {
                            items(
                                items = state.items,
                                key = { it.key },
                                contentType = { it.type }
                            ) { item ->
                                // A10：删除历史条目时淡出+位移回弹收拢
                                Box(modifier = Modifier.animateItem()) {
                                    HistoryItemCard(
                                        item = item,
                                        onClick = { onOpenHistoryTarget(item.target) }
                                    )
                                }
                            }

                            if (state.isLoadingMore) {
                                items(
                                    count = LOAD_MORE_SKELETON_COUNT,
                                    key = { index -> "history_loading_$index" },
                                    contentType = { "loading" }
                                ) {
                                    // AP-15：注入共享呼吸 —— N 个骨架一个时钟
                                    ProvideSkeletonBreath(breath = loadMoreBreath) {
                                        VideoListCardSkeleton()
                                    }
                                }
                            }

                            if (state.errorMessage != null && state.items.isNotEmpty()) {
                                item(
                                    key = "history_error",
                                    contentType = "error"
                                ) {
                                    StateMessageCard(
                                        text = state.errorMessage.orEmpty().ifBlank { "加载历史记录失败" },
                                        isError = true,
                                        actionText = "重试",
                                        onAction = if (state.errorOnLoadMore) {
                                            viewModel::loadMore
                                        } else {
                                            viewModel::refresh
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
