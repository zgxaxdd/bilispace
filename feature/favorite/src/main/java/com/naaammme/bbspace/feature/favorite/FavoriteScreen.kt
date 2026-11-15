package com.naaammme.bbspace.feature.favorite

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.VideoListSkeletonList
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.FavoriteContentTarget
import com.naaammme.bbspace.core.model.FavoriteFolder
import com.naaammme.bbspace.feature.favorite.folder.FavoriteFolderList
import com.naaammme.bbspace.feature.favorite.item.FavoriteContentList
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.component.BiliTouchTargets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteScreen(
    onBack: () -> Unit,
    onOpenContent: (FavoriteContentTarget) -> Unit,
    onOpenFolder: (Long) -> Unit,
    viewModel: FavoriteViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sortState by viewModel.sortState.collectAsStateWithLifecycle()
    val contentListState = rememberLazyListState()
    val folderListState = rememberLazyListState()
    val context = LocalContext.current

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FavoriteFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<FavoriteFolder?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.msg.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("收藏") },
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(), onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (state.tab == FavoriteTab.FOLDER) {
                        // ## 视觉对齐（task-9 C1：同一顶栏内可点件等高）
                        // 左侧返回钮是 IconButton（48dp 命中区），M3 TextButton 默认 40dp ——
                        // 同一行里文字钮矮 8dp。统一到 `BiliTouchTargets.comfortable`
                        //（= 48dp，由 BiliSpacing 推导，零新增 dp 字面量）。
                        // 两颗按钮共用同一个高度取值，彼此也必然等高。
                        val actionHeight = Modifier.defaultMinSize(
                            minHeight = BiliTouchTargets.comfortable
                        )
                        TextButton(
                            onClick = { showCreate = true },
                            modifier = actionHeight.appElasticPress()
                        ) {
                            Text("新建")
                        }
                        TextButton(
                            onClick = viewModel::openFolderSort,
                            modifier = actionHeight.appElasticPress()
                        ) {
                            Text("排序")
                        }
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
                tabs = FavoriteTab.entries.map { it.title },
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> viewModel.selectTab(FavoriteTab.entries[index]) },
                modifier = Modifier
            )

            BiliPullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f)
            ) {
                FavoriteBody(
                    state = state,
                    contentListState = contentListState,
                    folderListState = folderListState,
                    onOpenContent = onOpenContent,
                    onOpenFolder = onOpenFolder,
                    onRetry = viewModel::refresh,
                    onLoadMore = viewModel::loadMore,
                    onRenameFolder = { renameTarget = it },
                    onDeleteFolder = { deleteTarget = it }
                )
            }
        }
    }

    if (showCreate) {
        FolderEditDialog(
            title = "新建收藏夹",
            initialTitle = "",
            initialPrivate = false,
            confirmText = "创建",
            onDismiss = { showCreate = false },
            onConfirm = { name, privacy ->
                showCreate = false
                viewModel.createFolder(name, privacy)
            }
        )
    }

    renameTarget?.let { folder ->
        FolderEditDialog(
            title = "重命名收藏夹",
            initialTitle = folder.title,
            initialPrivate = folder.isPrivate,
            confirmText = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name, privacy ->
                renameTarget = null
                viewModel.renameFolder(folder.fid, name, privacy)
            }
        )
    }

    deleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除收藏夹") },
            text = { Text("确认删除「${folder.title}」吗？其中的 ${folder.mediaCount} 个内容将一并移除。") },
            confirmButton = {
                TextButton(modifier = Modifier.appElasticPress(),
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteFolder(folder.fid)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.appElasticPress(), onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (sortState.visible) {
        FolderSortDialog(
            state = sortState,
            onMove = viewModel::moveSortEntry,
            onConfirm = viewModel::submitFolderSort,
            onDismiss = viewModel::dismissFolderSort
        )
    }
}

@Composable
private fun FolderEditDialog(
    title: String,
    initialTitle: String,
    initialPrivate: Boolean,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initialTitle) }
    var privateFolder by remember { mutableStateOf(initialPrivate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("收藏夹标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                ) {
                    FilterChip(modifier = Modifier.appElasticPress(),
                        selected = !privateFolder,
                        onClick = { privateFolder = false },
                        label = { Text("公开") }
                    )
                    FilterChip(modifier = Modifier.appElasticPress(),
                        selected = privateFolder,
                        onClick = { privateFolder = true },
                        label = { Text("私有") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(),
                onClick = { onConfirm(name.trim(), if (privateFolder) 1 else 0) },
                enabled = name.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun FolderSortDialog(
    state: FolderSortUiState,
    onMove: (Long, Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏夹排序") },
        text = {
            when {
                state.loading -> {
                    Text(
                        text = "加载中…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = BiliSpacing.xl)
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = state.entries,
                            key = { it.fid }
                        ) { folder ->
                            val index = state.entries.indexOfFirst { it.fid == folder.fid }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = folder.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = BiliSpacing.sm)
                                )
                                IconButton(modifier = Modifier.appElasticPress(),
                                    onClick = { onMove(folder.fid, -1) },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "上移"
                                    )
                                }
                                IconButton(modifier = Modifier.appElasticPress(),
                                    onClick = { onMove(folder.fid, 1) },
                                    enabled = index < state.entries.lastIndex
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "下移"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onConfirm, enabled = !state.submitting && !state.loading) {
                Text(if (state.submitting) "保存中…" else "完成")
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.appElasticPress(), onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun FavoriteBody(
    state: FavoriteUiState,
    contentListState: LazyListState,
    folderListState: LazyListState,
    onOpenContent: (FavoriteContentTarget) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRenameFolder: (FavoriteFolder) -> Unit,
    onDeleteFolder: (FavoriteFolder) -> Unit
) {
    val content = state.content
    val folder = state.folder
    val hasCurrentItems = when (state.tab) {
        FavoriteTab.CONTENT -> content.items.isNotEmpty()
        FavoriteTab.FOLDER -> folder.folders.isNotEmpty()
    }
    when {
        ((state.tab == FavoriteTab.CONTENT && content.isLoading) ||
            (state.tab == FavoriteTab.FOLDER && folder.isLoading)) && !hasCurrentItems -> {
            FavoriteLoading(modifier = Modifier.fillMaxSize())
        }

        ((state.tab == FavoriteTab.CONTENT && content.errorMessage != null) ||
            (state.tab == FavoriteTab.FOLDER && folder.errorMessage != null)) && !hasCurrentItems -> {
            StateMessageCard(
                text = when (state.tab) {
                    FavoriteTab.CONTENT -> content.errorMessage.orEmpty()
                    FavoriteTab.FOLDER -> folder.errorMessage.orEmpty()
                }.ifBlank { "加载收藏失败" },
                modifier = Modifier.fillMaxSize(),
                isError = true,
                actionText = "重试",
                onAction = onRetry
            )
        }

        !hasCurrentItems -> {
            StateMessageCard(
                text = when (state.tab) {
                    FavoriteTab.CONTENT -> "暂无收藏内容"
                    FavoriteTab.FOLDER -> "暂无收藏夹"
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        state.tab == FavoriteTab.CONTENT -> {
            FavoriteContentList(
                items = content.items,
                isLoadingMore = content.isLoadingMore,
                errorMessage = content.errorMessage,
                errorOnLoadMore = content.errorOnLoadMore,
                canLoadMore = content.canLoadMore,
                onLoadMore = onLoadMore,
                onRetry = if (content.errorOnLoadMore) onLoadMore else onRetry,
                onOpenContent = onOpenContent,
                listState = contentListState
            )
        }

        state.tab == FavoriteTab.FOLDER -> {
            FavoriteFolderList(
                folders = folder.folders,
                listState = folderListState,
                onOpenFolder = onOpenFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder
            )
        }
    }
}

@Composable
fun FavoriteLoading(modifier: Modifier = Modifier) {
    VideoListSkeletonList(
        count = LOADING_COUNT,
        modifier = modifier,
        keyPrefix = "favorite_loading"
    )
}

private const val LOADING_COUNT = 8
