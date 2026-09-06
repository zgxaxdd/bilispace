package com.naaammme.bbspace.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.listen.ListenItem
import com.naaammme.bbspace.feature.home.article.HomeArticlePage
import com.naaammme.bbspace.feature.home.interest.InterestDialog
import com.naaammme.bbspace.feature.home.listen.ListenHomePage
import com.naaammme.bbspace.feature.home.live.HomeLivePage
import com.naaammme.bbspace.feature.home.video.HomeVideoPage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val homeTabs = listOf("FM", "推荐", "直播", "专栏")
private val homeProfileAvatarSize = 38.dp
private val homeProfileIconSize = 24.dp
private const val homeDefaultPage = 1

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    profileAvatar: String? = null,
    onOpenVideo: (VideoTarget) -> Unit = {},
    onOpenSpace: (SpaceRoute) -> Unit = {},
    onOpenLive: (LiveRoute) -> Unit = {},
    onOpenDynamic: (String) -> Unit = {},
    onOpenArticle: (String, Int) -> Unit = { _, _ -> },
    onOpenListenItem: (Long, Int, Long, String, String, String) -> Unit = { _, _, _, _, _, _ -> },
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val singleColumn = viewModel.homeSingleColumn.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) {
        viewModel.refreshPageAction()
    }
    val pagerState = rememberPagerState(initialPage = homeDefaultPage, pageCount = { homeTabs.size })
    val scope = rememberCoroutineScope()

    state.interestChoose?.let { interestChoose ->
        InterestDialog(
            data = interestChoose,
            onDismiss = viewModel::dismissInterest,
            onConfirm = { id, result, posIds -> viewModel.submitInterest(id, result, posIds) }
        )
    }

    var topBarHeightPx by remember { mutableStateOf(0) }
    val topBarHeightDp = with(LocalDensity.current) { topBarHeightPx.toDp() }

    // 顶栏随内容滚动 1:1 同步上滑。
    // Compose 嵌套滚动约定：手指上滑（内容上移、浏览下方内容）时滚动位移 delta.y 为负
    // （与 M3 EnterAlwaysScrollBehavior 的 heightOffset += available.y 同一符号体系）。
    // 因此用 -delta 累加：上滑 → offset 增大 → 顶栏上移隐藏；下滑 → offset 减小 → 顶栏滑回。
    var topBarOffsetPx by remember { mutableStateOf(0f) }
    val topBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // consumed = 子布局实际消费掉的位移；available = 子布局未消费的余量
                // （例如已滚到列表顶部后继续下拉的 over-scroll）。
                // 两者相加 = 本次真实滚动位移：既保证顶栏与内容 1:1 同步，
                // 又保证列表到顶后继续下拉仍能把顶栏拉回来。
                val delta = consumed.y + available.y
                if (delta != 0f) {
                    topBarOffsetPx = (topBarOffsetPx - delta)
                        .coerceIn(0f, topBarHeightPx.toFloat())
                }
                return Offset.Zero
            }
        }
    }

    // 切 Tab 后新页面内容从顶部开始，顶栏必须复位显示
    LaunchedEffect(pagerState.settledPage) {
        topBarOffsetPx = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 监听内容滚动，驱动顶栏同步平移
            .nestedScroll(topBarScrollConnection)
    ) {
        // 内容全屏：延伸到状态栏之后，滚动时卡片可上探到状态栏区域
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ListenHomePage(
                    isActive = pagerState.currentPage == page,
                    topContentPadding = topBarHeightDp,
                    onItemClick = { item ->
                        onOpenListenItem(
                            item.oid,
                            item.itemType,
                            item.subId,
                            item.title,
                            item.author,
                            item.cover
                        )
                    }
                )

                1 -> HomeVideoPage(
                    items = state.items,
                    isRefreshing = state.isRefreshing,
                    isLoadingMore = state.isLoadingMore,
                    errorMessage = state.errorMessage,
                    toastMessage = state.toastMessage,
                    dislikedReasons = state.dislikedReasons,
                    singleColumn = singleColumn,
                    topContentPadding = topBarHeightDp,
                    onRefresh = viewModel::refresh,
                    onLoadMore = viewModel::loadMore,
                    onOpenVideo = onOpenVideo,
                    onOpenSpace = onOpenSpace,
                    onOpenLive = onOpenLive,
                    onOpenDynamic = onOpenDynamic,
                    onDislike = viewModel::submitDislike,
                    onCancelDislike = viewModel::cancelDislike,
                    onToastShown = viewModel::consumeToast,
                    // 刷新后程序化 scrollToItem(0) 也会派发嵌套滚动事件，
                    // 会把 offset 顶到上限导致顶栏一打开就隐藏，这里强制复位。
                    onScrollToTop = { topBarOffsetPx = 0f }
                )

                2 -> HomeLivePage(
                    isActive = pagerState.currentPage == page,
                    topContentPadding = topBarHeightDp,
                    onOpenLive = onOpenLive,
                    onOpenSpace = onOpenSpace
                )

                else -> HomeArticlePage(
                    isActive = pagerState.currentPage == page,
                    topContentPadding = topBarHeightDp,
                    onOpenArticle = onOpenArticle,
                    onOpenSpace = onOpenSpace
                )
            }
        }

        // 透明悬浮顶栏：随内容滚动同步上滑，完全滚出后隐藏
        HomeTopBar(
            selectedIndex = pagerState.currentPage,
            singleColumn = singleColumn,
            topBarOffsetPx = topBarOffsetPx,
            onToggleSingleColumn = viewModel::toggleHomeSingleColumn,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToProfile = onNavigateToProfile,
            profileAvatar = profileAvatar,
            onSelectTab = { page ->
                scope.launch { pagerState.animateScrollToPage(page) }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { size ->
                    // 只取最大高度：折叠时高度变小不回缩内容顶距
                    if (size.height > topBarHeightPx) topBarHeightPx = size.height
                }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    selectedIndex: Int,
    singleColumn: Boolean,
    topBarOffsetPx: Float,
    onToggleSingleColumn: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: () -> Unit,
    profileAvatar: String?,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .clipToBounds()
            .offset { IntOffset(0, -topBarOffsetPx.roundToInt()) }
            .padding(top = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                onClick = onNavigateToSearch,
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "搜索视频、UP主、番剧",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onToggleSingleColumn) {
                Icon(
                    imageVector = if (singleColumn) {
                        Icons.Filled.Menu
                    } else {
                        Icons.AutoMirrored.Filled.List
                    },
                    contentDescription = if (singleColumn) "切换为双列布局" else "切换为单列大图",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNavigateToProfile) {
                AvatarImage(
                    url = profileAvatar,
                    contentDescription = "我的",
                    modifier = Modifier.size(homeProfileAvatarSize),
                    fallbackContent = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "我的",
                            modifier = Modifier.size(homeProfileIconSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
        FilledTabRow(
            tabs = homeTabs,
            selectedIndex = selectedIndex,
            onSelect = onSelectTab,
            modifier = Modifier
        )
    }
}
