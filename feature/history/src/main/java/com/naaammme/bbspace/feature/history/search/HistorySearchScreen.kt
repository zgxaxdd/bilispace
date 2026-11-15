package com.naaammme.bbspace.feature.history.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.SearchCapsuleField
import com.naaammme.bbspace.core.designsystem.component.ProvideSkeletonBreath
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.component.VideoListCardSkeleton
import com.naaammme.bbspace.core.designsystem.component.rememberSkeletonBreathOrNull
import com.naaammme.bbspace.core.model.HistoryTarget
import com.naaammme.bbspace.feature.history.component.HistoryItemCard
import com.naaammme.bbspace.feature.history.component.HistoryListLoading
import com.naaammme.bbspace.feature.history.component.LOAD_MORE_SKELETON_COUNT
import com.naaammme.bbspace.feature.history.component.LoadMoreTrigger
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySearchScreen(
    onBack: () -> Unit,
    onOpenHistoryTarget: (HistoryTarget?) -> Unit,
    viewModel: HistorySearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LoadMoreTrigger(
        listState = listState,
        canLoadMore = { state.canLoadMore },
        isLoadingMore = { state.isLoadingMore },
        hasError = { state.errorMessage != null },
        onLoadMore = viewModel::loadMore
    )

    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val keyboardOptions = remember {
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = true,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search
        )
    }
    val submitSearch = remember(focusManager, keyboard, viewModel) {
        {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            viewModel.submitSearch()
        }
    }
    val keyboardActions = remember(submitSearch) {
        KeyboardActions(onSearch = { submitSearch() })
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = {
                    SearchCapsuleField(
                        value = state.input,
                        onValueChange = viewModel::updateKeyword,
                        placeholder = "搜索历史记录",
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions
                    )
                },
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(),
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            onBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(modifier = Modifier.appElasticPress(),
                        onClick = submitSearch
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    HistoryListLoading(
                        skeletonPrefix = "history_search_skeleton",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                state.errorMessage != null && state.items.isEmpty() -> {
                    StateMessageCard(
                        text = state.errorMessage.orEmpty().ifBlank { "搜索历史记录失败" },
                        modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg),
                        isError = true,
                        actionText = "重试",
                        onAction = viewModel::submitSearch
                    )
                }

                state.query.isBlank() -> {
                    StateMessageCard(
                        text = "输入关键词搜索历史记录",
                        modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg)
                    )
                }

                state.items.isEmpty() -> {
                    StateMessageCard(
                        text = "没有找到相关历史记录",
                        modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg)
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
                            HistoryItemCard(
                                item = item,
                                onClick = { onOpenHistoryTarget(item.target) }
                            )
                        }

                        if (state.isLoadingMore) {
                            items(
                                count = LOAD_MORE_SKELETON_COUNT,
                                key = { index -> "history_search_loading_$index" },
                                contentType = { "loading" }
                            ) {
                                // AP-15：注入共享呼吸 —— N 个骨架一个时钟
                                ProvideSkeletonBreath(breath = loadMoreBreath) {
                                    VideoListCardSkeleton()
                                }
                            }
                        }

                        if (state.errorMessage != null && state.items.isNotEmpty()) {
                            item(key = "history_search_error", contentType = "error") {
                                StateMessageCard(
                                    text = state.errorMessage.orEmpty().ifBlank { "搜索历史记录失败" },
                                    isError = true,
                                    actionText = "重试",
                                    onAction = viewModel::loadMore
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
