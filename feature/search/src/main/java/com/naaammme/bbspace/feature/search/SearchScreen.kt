package com.naaammme.bbspace.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.component.LoadMoreGrowthGuard
import com.naaammme.bbspace.core.designsystem.component.ProvideSkeletonBreath
import com.naaammme.bbspace.core.designsystem.component.rememberSkeletonBreathOrNull
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.VideoListCardSkeleton
import com.naaammme.bbspace.core.designsystem.component.VideoListSkeletonList
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import com.naaammme.bbspace.core.model.SearchFilter
import com.naaammme.bbspace.core.model.SearchTime
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.search.result.SearchAuthorCard
import com.naaammme.bbspace.feature.search.filter.SearchFiltersSheet
import com.naaammme.bbspace.feature.search.history.SearchHistoryPanel
import com.naaammme.bbspace.feature.search.result.SearchCard
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenVideo: (VideoTarget) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val authors by viewModel.authors.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val histories by viewModel.histories.collectAsStateWithLifecycle()
    val historyOrder by viewModel.currentHistoryOrder.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val hasItems = authors.isNotEmpty() || videos.isNotEmpty()
    val itemCount = authors.size + videos.size
    val sortFilter = viewModel.filters.firstOrNull { it.key == SORT_KEY }
    val filters = viewModel.filters.filterNot { it.key == SORT_KEY }
    val hasActiveExtraFilter = filters.any { viewModel.selectedOf(it.key).isNotEmpty() } || viewModel.time.isActive
    val handleBack = {
        if (!viewModel.consumeBack()) {
            onBack()
        }
    }

    val shouldLoadMore by remember(
        listState,
        itemCount,
        viewModel.canLoadMore
    ) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            viewModel.canLoadMore &&
                    !viewModel.isLoading &&
                    !viewModel.isLoadingMore &&
                    itemCount > 0 &&
                    last >= itemCount - 3
        }
    }

    // AP-10：增长守卫 —— 谓词里的 !isLoadingMore 随加载翻转，effect 会跟着重启；
    // 服务端返回空页/全重复页（next 非空、列表不增长）时若不设防就是无界请求风暴。
    // 守卫保证"只有列表确实变长才允许下一次预取"（范式出处 AdaptiveMediaGrid）。
    val loadMoreGuard = remember { LoadMoreGrowthGuard() }
    LaunchedEffect(itemCount) {
        loadMoreGuard.onItemLineChanged(itemCount)
    }

    BackHandler(onBack = handleBack)

    LaunchedEffect(shouldLoadMore, viewModel.isLoading, viewModel.isLoadingMore) {
        loadMoreGuard.onVisibility(shouldLoadMore, busy = viewModel.isLoading || viewModel.isLoadingMore)
        if (shouldLoadMore && loadMoreGuard.tryAcquire()) {
            viewModel.loadMore()
        }
    }

    CollapsingTopBarScaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { scrollBehavior ->
            SearchTopBar(
                text = viewModel.input,
                autoFocus = viewModel.keyword.isBlank() && viewModel.input.isBlank(),
                onTextChange = viewModel::updateInput,
                onBack = handleBack,
                onSearch = {
                    viewModel.submitSearch()
                },
                onOpenSpace = { uid ->
                    keyboard?.hide()
                    onOpenSpace(SpaceRoute(mid = uid))
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            sortFilter?.takeIf { it.ops.size > 1 }?.let { filter ->
                val trailing: (@Composable RowScope.() -> Unit)? =
                    if (filters.isNotEmpty()) {
                        {
                            SearchFilterAction(
                                filters = filters,
                                sortSelected = viewModel.selectedOf(SORT_KEY),
                                selectedMap = buildSelectedMap(filters, viewModel),
                                time = viewModel.time,
                                active = hasActiveExtraFilter,
                                onDismissKeyboard = { keyboard?.hide() },
                                onApply = viewModel::applyFilters
                            )
                        }
                    } else {
                        null
                    }
                SearchSortRow(
                    filter = filter,
                    selected = viewModel.selectedOf(filter.key),
                    trailing = trailing,
                    onSelect = { params ->
                        keyboard?.hide()
                        viewModel.applyFilter(filter.key, params)
                    }
                )
            }
            if (sortFilter == null && filters.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SearchFilterAction(
                        filters = filters,
                        sortSelected = viewModel.selectedOf(SORT_KEY),
                        selectedMap = buildSelectedMap(filters, viewModel),
                        time = viewModel.time,
                        active = hasActiveExtraFilter,
                        onDismissKeyboard = { keyboard?.hide() },
                        onApply = viewModel::applyFilters
                    )
                }
            }

            // A6：状态切换（历史→加载→错误→空→结果）Crossfade，200ms Standard
            val anim = LocalAnimations.current
            val stateKey = when {
                viewModel.isLoading && !hasItems -> "loading"
                viewModel.errorMessage != null && !hasItems -> "error"
                viewModel.keyword.isBlank() -> "history"
                !hasItems -> "empty"
                else -> "results"
            }
            Crossfade(
                targetState = stateKey,
                animationSpec = tween(durationMillis = anim.medium, easing = anim.standard),
                label = "searchStateCrossfade"
            ) { key ->
                when (key) {
                    "loading" -> SearchLoadingList()

                    "error" -> {
                        StateMessageCard(
                            text = viewModel.errorMessage.orEmpty().ifBlank { "搜索失败" },
                            modifier = Modifier.fillMaxWidth().padding(BiliSpacing.lg),
                            isError = true,
                            actionText = "重试",
                            onAction = {
                                keyboard?.hide()
                                viewModel.submitSearch(recordHistory = false)
                            }
                        )
                    }

                    "history" -> {
                        if (histories.isNotEmpty()) {
                            SearchHistoryPanel(
                                histories = histories,
                                order = historyOrder,
                                onToggleOrder = viewModel::toggleHistoryOrder,
                                onSearch = { keyword ->
                                    keyboard?.hide()
                                    viewModel.submitSearch(keyword)
                                },
                                onDelete = viewModel::deleteHistory
                            )
                        }
                    }

                    "empty" -> StateMessageCard(
                        text = "没有找到结果",
                        modifier = Modifier.fillMaxSize().padding(BiliSpacing.lg)
                    )

                    else -> {
                        // AP-15：呼吸时钟只在"加载更多骨架存在"时创建（骨架区消失即随组合
                        // 销毁，不给常驻列表留无限动画）；动效关闭时为 null，骨架完全静止。
                        val loadMoreBreath = if (viewModel.isLoadingMore) rememberSkeletonBreathOrNull() else null
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // 页边距/行距走 token（原 12/10dp 字面量）：与历史面板、
                            // 其它列表页同一套密度口径，改一处全站同步。
                            contentPadding = PaddingValues(
                                horizontal = BiliSpacing.md,
                                vertical = BiliSpacing.md
                            ),
                            verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
                        ) {
                            // 分组小标题（排版层）：作者段/视频段此前是**连续无标题**的两段，
                            // 用户读到视频卡时不知道上面那一块是什么。这里只加名称行，
                            // 不改任何查询/分页/点击逻辑；标题作为 LazyColumn item 存在，
                            // 分页判据 `last >= itemCount - 3` 的 item 空间同步平移，
                            // 触发时机与原来逐项等价。
                            if (authors.isNotEmpty()) {
                                item(key = "search_header_authors", contentType = "header") {
                                    Text(
                                        text = "作者",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = biliColorRoles().groupHeader,
                                        modifier = Modifier.padding(
                                            start = BiliSpacing.xs,
                                            top = BiliSpacing.xs,
                                            bottom = BiliSpacing.xs
                                        )
                                    )
                                }
                            }
                            items(
                                items = authors,
                                key = { it.mid },
                                contentType = { "author" }
                            ) { author ->
                                SearchAuthorCard(
                                    author = author,
                                    onClick = { onOpenSpace(SpaceRoute(mid = author.mid, name = author.name)) }
                                )
                            }

                            if (videos.isNotEmpty()) {
                                item(key = "search_header_videos", contentType = "header") {
                                    Text(
                                        text = "视频",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = biliColorRoles().groupHeader,
                                        modifier = Modifier.padding(
                                            start = BiliSpacing.xs,
                                            top = BiliSpacing.md,
                                            bottom = BiliSpacing.xs
                                        )
                                    )
                                }
                            }
                            items(
                                items = videos,
                                key = { "${it.aid}_${it.cid}" },
                                contentType = { "video" }
                            ) { video ->
                                SearchCard(
                                    video = video,
                                    onClick = { onOpenVideo(video.target) }
                                )
                            }

                            if (viewModel.isLoadingMore) {
                                items(
                                    count = LOAD_MORE_SKELETON_COUNT,
                                    key = { index -> "loading_$index" },
                                    contentType = { "skeleton" }
                                ) {
                                    // AP-15：注入共享呼吸 —— N 个骨架一个时钟
                                    ProvideSkeletonBreath(breath = loadMoreBreath) {
                                        VideoListCardSkeleton()
                                    }
                                }
                            }

                            if (viewModel.errorMessage != null && itemCount > 0) {
                                item {
                                    StateMessageCard(
                                        text = viewModel.errorMessage.orEmpty().ifBlank { "搜索失败" },
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
}

@Composable
private fun SearchSortRow(
    filter: SearchFilter,
    selected: Set<String>,
    trailing: (@Composable RowScope.() -> Unit)?,
    onSelect: (Set<String>) -> Unit
) {
    val selectedIndex = remember(filter, selected) {
        val pickedIndex = filter.ops.indexOfFirst { op ->
            if (selected.isEmpty()) op.isDefault else op.param in selected
        }
        if (pickedIndex >= 0) pickedIndex else 0
    }
    FilledTabRow(
        tabs = filter.ops.map { it.label },
        selectedIndex = selectedIndex,
        onSelect = { index ->
            val op = filter.ops[index]
            onSelect(if (op.isDefault) emptySet() else setOf(op.param))
        },
        modifier = Modifier,
        trailing = trailing
    )
}

@Composable
private fun SearchFilterAction(
    filters: List<SearchFilter>,
    sortSelected: Set<String>,
    selectedMap: Map<String, Set<String>>,
    time: SearchTime,
    active: Boolean,
    onDismissKeyboard: () -> Unit,
    onApply: (Map<String, Set<String>>, SearchTime) -> Unit
) {
    var showFilterSheet by remember { mutableStateOf(false) }

    SearchFilterButton(
        active = active,
        onClick = {
            onDismissKeyboard()
            showFilterSheet = true
        }
    )

    if (showFilterSheet) {
        SearchFiltersSheet(
            filters = filters,
            selectedMap = selectedMap,
            time = time,
            onDismiss = { showFilterSheet = false },
            onApply = { picked, pickedTime ->
                val nextSel = buildMap {
                    if (sortSelected.isNotEmpty()) {
                        put(SORT_KEY, sortSelected)
                    }
                    picked.forEach { (key, value) ->
                        if (value.isEmpty()) return@forEach
                        put(key, value)
                    }
                }
                onApply(nextSel, pickedTime)
                showFilterSheet = false
            }
        )
    }
}

@Composable
private fun SearchFilterButton(
    active: Boolean,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.appElasticPress(), 
        onClick = onClick,
        shape = SearchFilterButtonShape,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "筛选",
            // 命中区：图标 24 + 上下各 12 = 48dp（原 7dp 内边距只有 38dp，
            // 低于 48dp 触控下限）；左右同样取 12dp，圆角用分组卡 token。
            modifier = Modifier.padding(
                horizontal = BiliSpacing.md,
                vertical = BiliSpacing.md
            ),
            tint = if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun SearchLoadingList(modifier: Modifier = Modifier) {
    VideoListSkeletonList(
        count = INIT_SKELETON_COUNT,
        modifier = modifier,
        keyPrefix = "skeleton"
    )
}

private fun buildSelectedMap(
    filters: List<SearchFilter>,
    vm: SearchViewModel
): Map<String, Set<String>> {
    return buildMap {
        filters.forEach { filter ->
            val picked = vm.selectedOf(filter.key)
            if (picked.isEmpty()) return@forEach
            put(filter.key, picked)
        }
    }
}

private const val INIT_SKELETON_COUNT = 8
private const val LOAD_MORE_SKELETON_COUNT = 2
private const val SORT_KEY = "sort"

/**
 * 筛选按钮形状：分组卡圆角 token（16dp），与历史词 chip、其它分组容器同一语言。
 * 文件级常量而非内联 `shape = RoundedCornerShape(...)`：后者会被
 * `ListAllocationStructureTest` 的具名实参棘轮计数，且每次组合重造一份。
 */
private val SearchFilterButtonShape = RoundedCornerShape(BiliRadius.groupedList)
