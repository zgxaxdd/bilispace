package com.naaammme.bbspace.feature.space.relation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.theme.asBold
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.BiliPullToRefreshBox
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import com.naaammme.bbspace.core.model.RelationUser
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 关系页标签底盘（文件级常量，同上）。 */
private val RelationTagShape = RoundedCornerShape(BiliRadius.badge)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelationScreen(
    onBack: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    viewModel: RelationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val followingsListState = rememberLazyListState()
    val fansListState = rememberLazyListState()
    val listState = if (state.selectedTab == 1) fansListState else followingsListState

    val currentTabState = rememberUpdatedState(state.currentTabState)
    LaunchedEffect(listState) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to last
        }
            .distinctUntilChanged()
            .filter { (total, last) ->
                val tab = currentTabState.value
                tab.hasMore && !tab.isLoading && !tab.isRefreshing &&
                        total > 0 &&
                        last >= total - 3
            }
            .collect { viewModel.loadMore() }
    }

    val onUserClick = remember(onOpenSpace) {
        { user: RelationUser ->
            onOpenSpace(SpaceRoute(mid = user.mid, name = user.uname))
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = "关系列表") },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            // 全局拖拽反馈（第 34 轮 T2）：关系列表页顶栏返回钮。
                            // 同屏 `FilledTabRow` 已由原语自带反馈，两者手感一致。
                            modifier = Modifier.appElasticPress()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
                FilledTabRow(
                    tabs = listOf("关注", "粉丝"),
                    selectedIndex = state.selectedTab,
                    onSelect = { viewModel.switchTab(it) },
                    modifier = Modifier
                )
            }
        }
    ) { padding ->
        val currentTab = state.currentTabState
        BiliPullToRefreshBox(
            isRefreshing = currentTab.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (currentTab.users.isEmpty() && !currentTab.isRefreshing && !currentTab.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // 空态/错误态统一走 StateMessageCard（UI-04 出口）。
                    // 原实现是裸 Box+Text，**没有重试按钮**（重试靠外层 BiliPullToRefreshBox
                    // 下拉刷新），因此这里不补 actionText/onAction —— 不新增交互，只统一渲染。
                    StateMessageCard(
                        text = currentTab.error ?: "暂无数据",
                        isError = currentTab.error != null
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
                ) {
                    items(currentTab.users, key = { it.mid }) { user ->
                        RelationUserCard(
                            user = user,
                            onUserClick = onUserClick
                        )
                    }

                    if (currentTab.isLoading) {
                        item(key = "loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = BiliSpacing.md),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationUserCard(
    user: RelationUser,
    onUserClick: (RelationUser) -> Unit
) {
    BiliCardSurface(
        onClick = { onUserClick(user) },
        modifier = Modifier.fillMaxWidth(),
        shape = SpaceCardShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                url = user.face,
                contentDescription = user.uname,
                modifier = Modifier.size(50.dp)
            )
            Spacer(modifier = Modifier.width(BiliSpacing.lg))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                ) {
                    val nameColor = if (user.isVip) {
                        Color(0xFFFB7299)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = user.uname,
                        style = MaterialTheme.typography.titleMedium.asBold(),
                        color = nameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    user.vipLabel?.let { label ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RelationTagShape
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                // VIP 标内边距刻意不用 token：BiliSpacing 最小是 xs=4dp，
                                // 会把 1dp 竖内边距的徽标高撑变形（保留字面量并在此说明）。
                                modifier = Modifier.padding(horizontal = BiliSpacing.xs, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (user.sign.isNotBlank()) {
                    Text(
                        text = user.sign,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private val SpaceCardShape = RoundedCornerShape(BiliRadius.card)
