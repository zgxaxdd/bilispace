package com.naaammme.bbspace.feature.favorite.folderdetail

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.model.FavoriteContentTarget
import com.naaammme.bbspace.feature.favorite.FavoriteLoading
import com.naaammme.bbspace.feature.favorite.item.FavoriteContentList
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteFolderDetailScreen(
    onBack: () -> Unit,
    fid: Long,
    onOpenContent: (FavoriteContentTarget) -> Unit,
    viewModel: FavoriteFolderDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.msg.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            state.selectionMode -> "已选 ${state.selectedKeys.size}"
                            state.totalCount > 0 -> "收藏夹 ${state.totalCount}"
                            else -> "收藏夹"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(),
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.exitSelection()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (state.selectionMode) {
                                Icons.Default.Close
                            } else {
                                Icons.AutoMirrored.Filled.ArrowBack
                            },
                            contentDescription = if (state.selectionMode) "退出多选" else "返回"
                        )
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        TextButton(modifier = Modifier.appElasticPress(), onClick = viewModel::selectAll) {
                            Text("全选")
                        }
                        TextButton(modifier = Modifier.appElasticPress(),
                            onClick = viewModel::deleteSelected,
                            enabled = state.selectedKeys.isNotEmpty() && !state.submitting
                        ) {
                            Text(
                                text = if (state.submitting) "移除中…" else "移除",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        BiliPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    FavoriteLoading(modifier = Modifier.fillMaxSize())
                }

                state.errorMessage != null && state.items.isEmpty() -> {
                    StateMessageCard(
                        text = state.errorMessage.orEmpty().ifBlank { "加载收藏失败" },
                        modifier = Modifier.fillMaxSize(),
                        isError = true,
                        actionText = "重试",
                        onAction = viewModel::refresh
                    )
                }

                state.items.isEmpty() -> {
                    StateMessageCard(
                        text = if (state.hasInvalid) "收藏夹暂无可用视频" else "收藏夹暂无内容",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    FavoriteContentList(
                        items = state.items,
                        isLoadingMore = state.isLoadingMore,
                        errorMessage = state.errorMessage,
                        errorOnLoadMore = state.errorOnLoadMore,
                        canLoadMore = state.canLoadMore,
                        onLoadMore = viewModel::loadMore,
                        onRetry = if (state.errorOnLoadMore) viewModel::loadMore else viewModel::refresh,
                        onOpenContent = onOpenContent,
                        listState = listState,
                        selectionMode = state.selectionMode,
                        selectedKeys = state.selectedKeys,
                        onItemLongPress = viewModel::enterSelection,
                        onToggleSelect = viewModel::toggleSelect
                    )
                }
            }
        }
    }
}
