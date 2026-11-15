package com.naaammme.bbspace.feature.im

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.ImSessionItem
import com.naaammme.bbspace.feature.im.component.ImSessionCard
import kotlinx.coroutines.flow.distinctUntilChanged
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImScreen(
    onOpenConversation: (ImSessionItem) -> Unit,
    onOpenMsgFeed: () -> Unit = {},
    vm: ImViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("消息") },
                actions = {
                    IconButton(
                        onClick = onOpenMsgFeed,
                        // 全局拖拽反馈（第 34 轮 T2）：消息页顶栏"通知评论"图标钮。
                        modifier = Modifier.appElasticPress()
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "通知评论")
                    }
                }
            )
        }
    ) { padding ->
        BiliPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!state.isLoggedIn) {
                StateMessageCard(
                    text = "请先登录后查看消息",
                    modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg)
                )
                return@BiliPullToRefreshBox
            }

            if (state.sessions.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FilledTabRow(
                        tabs = state.tabs.map { it.title },
                        selectedIndex = state.tabs.indexOf(state.currentTab).coerceAtLeast(0),
                        onSelect = { index -> state.tabs.getOrNull(index)?.let(vm::selectTab) },
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                    when {
                        state.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        !state.errorMessage.isNullOrBlank() -> {
                            StateMessageCard(
                                text = state.errorMessage.orEmpty(),
                                modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg),
                                isError = true
                            )
                        }

                        else -> {
                            StateMessageCard(
                                text = "暂无消息",
                                modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg)
                            )
                        }
                    }
                }
                return@BiliPullToRefreshBox
            }

            val listState = rememberLazyListState()
            LaunchedEffect(
                listState,
                state.sessions.size,
                state.canLoadMore,
                state.isLoadingMore,
                state.loadMoreError
            ) {
                snapshotFlow {
                    val total = listState.layoutInfo.totalItemsCount
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    state.canLoadMore &&
                        state.loadMoreError.isNullOrBlank() &&
                        total > 0 &&
                        last >= total - 4
                }
                    .distinctUntilChanged()
                    .collect { shouldLoadMore ->
                        if (shouldLoadMore) vm.loadMore()
                    }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = BiliSpacing.md,
                    vertical = BiliSpacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
            ) {
                item(key = "im_tabs") {
                    FilledTabRow(
                        tabs = state.tabs.map { it.title },
                        selectedIndex = state.tabs.indexOf(state.currentTab).coerceAtLeast(0),
                        onSelect = { index -> state.tabs.getOrNull(index)?.let(vm::selectTab) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(
                    items = state.sessions,
                    key = { it.key }
                ) { item ->
                    ImSessionCard(
                        item = item,
                        onClick = if (item.talkerId != null && item.sessionType != null) {
                            { onOpenConversation(item) }
                        } else {
                            null
                        }
                    )
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    item(key = "im_error_footer") {
                        StateMessageCard(
                            text = state.errorMessage.orEmpty(),
                            modifier = Modifier.padding(vertical = BiliSpacing.sm),
                            isError = true
                        )
                    }
                }

                if (state.isLoadingMore) {
                    item(key = "im_loading_more") {
                        ImLoadingMore()
                    }
                } else if (!state.loadMoreError.isNullOrBlank()) {
                    item(key = "im_load_more_error") {
                        StateMessageCard(
                            text = state.loadMoreError.orEmpty(),
                            modifier = Modifier.padding(vertical = BiliSpacing.sm),
                            isError = true,
                            actionText = "重试",
                            onAction = vm::loadMore
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImLoadingMore(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = BiliSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

