package com.naaammme.bbspace.feature.dynamic

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.model.DynamicItem
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.dynamic.feed.DynamicFeed

private const val DYNAMIC_MAX_IMAGE_COUNT = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicScreen(
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenDynamic: (String) -> Unit,
    viewModel: DynamicViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showPublish by remember { mutableStateOf(false) }
    var repostTarget by remember { mutableStateOf<DynamicItem?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.msg.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val shouldLoadMore by remember(
        state.items.size,
        state.canLoadMore,
        state.isLoadingMore,
        state.loadMoreError,
        listState
    ) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.canLoadMore &&
                state.loadMoreError.isNullOrBlank() &&
                total > 0 &&
                last >= total - LOAD_MORE_TRIGGER_OFFSET
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("动态") }
            )
        },
        floatingActionButton = {
            if (state.isLoggedIn) {
                ExtendedFloatingActionButton(
                    onClick = { showPublish = true },
                    icon = {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                    },
                    text = { Text("发布动态") }
                )
            }
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
                !state.isLoggedIn -> {
                    StateMessageCard(
                        text = "请先登录后查看动态",
                        modifier = Modifier.fillMaxSize().padding(24.dp)
                    )
                }

                state.isLoading && state.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.errorMessage != null && state.items.isEmpty() -> {
                    StateMessageCard(
                        text = state.errorMessage.orEmpty().ifBlank { "加载动态失败" },
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        isError = true
                    )
                }

                state.items.isEmpty() -> {
                    StateMessageCard(
                        text = "暂无动态",
                        modifier = Modifier.fillMaxSize().padding(24.dp)
                    )
                }

                else -> {
                    DynamicFeed(
                        upList = state.upList,
                        items = state.items,
                        listState = listState,
                        isLoadingMore = state.isLoadingMore,
                        errorMessage = state.errorMessage,
                        loadMoreError = state.loadMoreError,
                        onRetryRefresh = viewModel::refresh,
                        onRetryLoadMore = viewModel::loadMore,
                        onOpenVideo = onOpenVideo,
                        onOpenSpace = onOpenSpace,
                        onOpenLive = onOpenLive,
                        onOpenDynamic = onOpenDynamic,
                        onLike = viewModel::toggleLike,
                        onRepost = { repostTarget = it },
                        onShare = { item ->
                            ShareUtils.shareText(
                                context,
                                item.author?.name,
                                ShareUtils.dynamicUrl(item.id)
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showPublish) {
        DynamicPublishSheet(
            onDismiss = { showPublish = false },
            onPublish = { text, uris, onDone ->
                viewModel.publishDynamic(text, uris) { success ->
                    onDone(success)
                }
            }
        )
    }

    repostTarget?.let { item ->
        RepostDialog(
            item = item,
            onDismiss = { repostTarget = null },
            onRepost = { text, onDone ->
                viewModel.repostDynamic(item, text) { success ->
                    onDone(success)
                    if (success) {
                        repostTarget = null
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicPublishSheet(
    onDismiss: () -> Unit,
    onPublish: (String, List<Uri>, (Boolean) -> Unit) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var publishing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = DYNAMIC_MAX_IMAGE_COUNT)
    ) { uris ->
        if (uris.isNotEmpty()) {
            images = uris.take(DYNAMIC_MAX_IMAGE_COUNT)
        }
    }

    ModalBottomSheet(
        // iOS：弹层顶部圆角 20dp（方案 2.3 / BiliRadius.sheetTop 语义）
        shape = if (LocalUiStyle.current == UiStyle.IOS) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        } else {
            BottomSheetDefaults.ExpandedShape
        },
        scrimColor = Color.Black.copy(alpha = 0.40f),
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("发布动态", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("分享点什么…") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images, key = { it.toString() }) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(MaterialTheme.shapes.medium)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = images.size < DYNAMIC_MAX_IMAGE_COUNT
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Text("选图 ${images.size}/$DYNAMIC_MAX_IMAGE_COUNT")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (publishing) return@Button
                        publishing = true
                        onPublish(text, images) { success ->
                            publishing = false
                            if (success) {
                                onDismiss()
                            }
                        }
                    },
                    enabled = !publishing && (text.isNotBlank() || images.isNotEmpty())
                ) {
                    if (publishing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("发布")
                    }
                }
            }
        }
    }
}

@Composable
private fun RepostDialog(
    item: DynamicItem,
    onDismiss: () -> Unit,
    onRepost: (String, (Boolean) -> Unit) -> Unit
) {
    var text by remember { mutableStateOf("转发动态") }
    var reposting by remember { mutableStateOf(false) }
    val sourceName = item.author?.name.orEmpty()
    AlertDialog(
        onDismissRequest = { if (!reposting) onDismiss() },
        title = { Text("转发动态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "@$sourceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("添加转发理由") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (reposting) return@TextButton
                    reposting = true
                    onRepost(text.trim()) { reposting = false }
                },
                enabled = !reposting
            ) {
                Text(if (reposting) "转发中…" else "转发")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !reposting) {
                Text("取消")
            }
        }
    )
}

private const val LOAD_MORE_TRIGGER_OFFSET = 4
