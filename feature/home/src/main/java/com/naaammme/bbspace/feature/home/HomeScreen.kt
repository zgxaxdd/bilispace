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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.glass.GlassSurfaceType
import com.naaammme.bbspace.core.designsystem.glass.GlassSourceRefresher
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassConfig
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassHazeState
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassRefresher
import com.naaammme.bbspace.core.designsystem.glass.LocalLiquidBackdrop
import com.naaammme.bbspace.core.designsystem.glass.glassEffect
import com.naaammme.bbspace.core.designsystem.glass.glassFrameRefresh
import com.naaammme.bbspace.core.designsystem.glass.isGlassActive
import com.naaammme.bbspace.core.designsystem.seamless.LocalStoryCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.listen.ListenItem
import com.naaammme.bbspace.feature.home.article.HomeArticlePage
import com.naaammme.bbspace.feature.home.interest.InterestDialog
import com.naaammme.bbspace.feature.home.listen.ListenHomePage
import com.naaammme.bbspace.feature.home.live.HomeLivePage
import com.naaammme.bbspace.feature.home.video.HomeVideoPage
import com.naaammme.bbspace.feature.rank.RankScreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val homeTabs = listOf("FM", "推荐", "直播", "排行", "专栏")
private val homeRankTabPage = 3
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
    onEnterStoryMode: (VideoTarget) -> Unit = {},
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

    // 顶栏占据的最大高度（px）。用 State 持有两处读取都拿到最新值：
    // - 滚动回调（组合之外）用它做隐藏上限；
    // - derivedStateOf 把它折算成内容顶距 Dp。
    // 此前是组合期局部变量，被 remember 的连接器闭包捕获成首帧的 0，顶栏隐藏上限
    // 长期为 0（顶部滚动时顶栏无法完整隐藏）。
    val topBarHeightState = remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val topBarHeightDp by remember(density) {
        derivedStateOf { with(density) { topBarHeightState.value.toDp() } }
    }

    // 顶栏随内容滚动 1:1 同步上滑。
    // Compose 嵌套滚动约定：手指上滑（内容上移、浏览下方内容）时滚动位移 delta.y 为负
    // （与 M3 EnterAlwaysScrollBehavior 的 heightOffset += available.y 同一符号体系）。
    // 因此用 -delta 累加：上滑 → offset 增大 → 顶栏上移隐藏；下滑 → offset 减小 → 顶栏滑回。
    var topBarOffsetPx by remember { mutableStateOf(0f) }
    val topBarScrollConnection = remember(topBarHeightState) {
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
                    // 取整到像素再写：offset 本身按 IntOffset 消费，亚像素变化不产生任何可见差异，
                    // 却会让每个滚动 tick 都触发一次状态写入与 offset 块重布局。取整后大部分
                    // tick 的值与上一次相同，mutableStateOf 的结构相等判断直接短路。
                    val next = (topBarOffsetPx - delta)
                        .coerceIn(0f, topBarHeightState.value.toFloat())
                        .let { kotlin.math.round(it) }
                    if (next != topBarOffsetPx) {
                        topBarOffsetPx = next
                    }
                }
                return Offset.Zero
            }
        }
    }
    // 顶栏偏移经 provider 延迟读取（见 HomeTopBar 注释）；
    // 内容顶距经 derivedStateOf 读取高度：高度只在测量后写一次，不构成滚动期重组。
    val topBarOffsetProvider = remember { { topBarOffsetPx } }

    // 切 Tab 后新页面内容从顶部开始，顶栏必须复位显示
    LaunchedEffect(pagerState.settledPage) {
        topBarOffsetPx = 0f
    }

    // 首页玻璃：独立 HazeState，源 = Pager 内容（顶栏不在源内，避免自采样鬼影）。
    // 与主框架同理：录制节点与顶栏玻璃都不随列表滚动自动重录/重采样，需要
    // GlassSourceRefresher 逐帧 tick（滚动衔接处残影修复，见 GlassRefresher.kt）。
    val homeGlassState = remember { HazeState() }
    val homeGlassOn = isGlassActive(homeGlassState)
    // 顶栏官方液态：Pager 内容录进 miuix 背板（顶栏是悬浮兄弟节点，无自采样），
    // 由 LocalLiquidBackdrop 下发给顶栏的 glassEffect（LIQUID 档走 drawBackdrop）
    val homeLiquidBackdrop = rememberLayerBackdrop()
    val homeLiquidOn = LocalGlassConfig.current.material == GlassMaterial.LIQUID && homeGlassOn
    // ── 拆掉"同一帧两遍全屏离屏录制"（任务书 T1）────────────────────────────
    // 本页在 AppNavHost 的 Crossfade **里面**，而那个 Crossfade 已经把同一块屏幕
    // 录进了主框架的 haze 源与 miuix 背板（供 Dock / 胶囊采样）。首页再为了自己的
    // 顶栏录一份，等于同一片像素每帧录两遍。
    //
    // 不能直接复用主框架那份：主框架的源把**首页顶栏也包进去了**，顶栏采样含自身的
    // 背板会自采样鬼影（这正是当初建私有源的理由）。所以这里改为**只录消费端真正
    // 会用到的那一种**：
    //   LIQUID 档 → glassEffect 走 `liquidGlassSurface(backdrop)` 后**直接 return**，
    //              根本不碰 haze（见 GlassEffect.kt 的 LIQUID 分支）；
    //   FROSTED 档 → 没有背板路径，才需要 hazeSource。
    // 于是默认 LIQUID 档下，hazeSource 这一路是纯粹的白录 —— 关掉后每滚动帧少一次
    // 1272×2800 的全屏离屏录制。保留 homeGlassState 本身仍然要 provide 给顶栏：
    // `isGlassActive(state)` 要求 state != null 才承认玻璃生效。
    val homeHazeOn = homeGlassOn && !homeLiquidOn
    // tick 门控：顶栏**完全滚出屏幕**后，首页这套私有玻璃源就没有任何可见消费者了
    // （顶栏是唯一消费者），继续逐帧 tick 只是让全屏重录白跑。滚动中顶栏正是隐藏的
    // 常态，所以这一刀砍掉的是滚动路径上大部分的第二份录制。
    // 读的是 snapshot state 而非组合期值：nestedScroll 回调里读不会触发重组，
    // 顶栏滑回来的那一帧门控自动打开、立即恢复重录（最多落后一次分发，肉眼不可见）。
    val homeGlassRefresher = remember {
        GlassSourceRefresher { topBarOffsetPx < topBarHeightState.value }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 监听内容滚动，驱动顶栏同步平移
            .nestedScroll(topBarScrollConnection)
            .nestedScroll(homeGlassRefresher.nestedScrollConnection)
    ) {
        // 内容全屏：延伸到状态栏之后，滚动时卡片可上探到状态栏区域
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    // 读滚动 tick：滚动帧强制本节点重绘（重录 haze 源）
                    homeGlassRefresher.frame
                    drawContent()
                }
                .then(if (homeHazeOn) Modifier.hazeSource(homeGlassState) else Modifier)
                .then(if (homeLiquidOn) Modifier.layerBackdrop(homeLiquidBackdrop) else Modifier)
                // 底色画进录制链内（理由同 AppNavHost Crossfade：文字区透明不能进背板）
                .background(MaterialTheme.colorScheme.background)
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
                    onRetry = viewModel::refresh,
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

                homeRankTabPage -> RankScreen(
                    isActive = pagerState.currentPage == page,
                    topContentPadding = topBarHeightDp,
                    onOpenVideo = onOpenVideo,
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

        // 透明悬浮顶栏：随内容滚动同步上滑，完全滚出后隐藏。
        // 偏移量以 provider 延迟读取：滚动期间每个滚动 tick 只触发顶栏 offset 块重布局，
        // 不再重组 HomeScreen 整个 scope（含 HorizontalPager 分支与全部子项参数比对）。
        CompositionLocalProvider(
            LocalGlassHazeState provides homeGlassState,
            LocalGlassRefresher provides homeGlassRefresher,
            LocalLiquidBackdrop provides homeLiquidBackdrop
        ) {
        // 顶栏"是否正在滚动"必须用 derivedStateOf 收成**布尔**再进组合。
        //
        // 直接写 `isScrolling = topBarOffsetPxProvider() > 0f` 会在 HomeTopBar 的
        // 组合期读取 `topBarOffsetPx`（Float state）——它每个滚动像素都变（快滑时=每帧），
        // 于是 HomeTopBar 的整个 restart scope 每帧重组一次，连带每次重组都重新执行
        // `glassEffect(...)` 这个 @Composable modifier 工厂（LIQUID 档下会让 miuix
        // 丢弃并重建玻璃绘制缓存）。
        //
        // 这恰好把本文件 :310 的设计意图抵消了：那里刻意用 provider 延迟到布局期读，
        // 就是为了"滚动时只重布局、不重组"。derivedStateOf 让组合期只订阅布尔，
        // 只有"开始滚动/停止滚动"这一次翻转才失效。
        val topBarScrolling by remember {
            derivedStateOf { topBarOffsetProvider() > 0f }
        }
        // ── 刷视频模式入口（顶栏按钮）────────────────────────────────────────
        // 锚点 = 按钮自身上的零绘制 View（SeamlessAnchor），OPPO 无缝 morph 把按钮
        // 位置长成全屏刷视频页；decorateAnchor=false + fallbackColor=按钮底色，
        // 两方向都是体面的纯色 morph（封面拉伸失真在 48dp 锚点上不可读，弃用）。
        // 无缝不可用时回退 onEnterStoryMode（普通 startActivity，走统一窗口动画）。
        val storyOpener = LocalStoryCardOpener.current
        val storyAnchorState = rememberSeamlessAnchorState()
        val storySpec = rememberSeamlessCardSpec(
            shape = RoundedCornerShape(BiliRadius.dockChip),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
        val canStorySeamless = storyOpener != null &&
            OplusViewSeamlessCompat.isVersionSupported()
        val enterStoryMode: () -> Unit = {
            // 流第一条 = 刷视频的起点（页 0）；无视频卡（全直播/空流）时无操作
            val first = state.items.firstOrNull { it.target != null && it.liveRoute == null }
            val target = first?.target
            if (target != null) {
                val opened = if (canStorySeamless && storyOpener != null && storyAnchorState.anchor != null) {
                    storyOpener.tryOpen(
                        anchor = storyAnchorState.anchor,
                        target = target,
                        coverUrl = first.cover,
                        coverLarge = false,
                        anchorRadiusPx = storySpec.radiusPx,
                        fallbackColor = storySpec.fallbackColor,
                        decorateAnchor = false
                    )
                } else {
                    false
                }
                if (!opened) {
                    onEnterStoryMode(target)
                }
            }
        }
        HomeTopBar(
            selectedIndex = pagerState.currentPage,
            topBarOffsetPxProvider = topBarOffsetProvider,
            isScrolling = topBarScrolling,
            onEnterStoryMode = enterStoryMode,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToProfile = onNavigateToProfile,
            profileAvatar = profileAvatar,
            onSelectTab = { page ->
                scope.launch { pagerState.animateScrollToPage(page) }
            },
            storyAnchorState = storyAnchorState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { size ->
                    // 只取最大高度：折叠时高度变小不回缩内容顶距
                    if (size.height > topBarHeightState.value) {
                        topBarHeightState.value = size.height
                    }
                }
        )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    selectedIndex: Int,
    // 偏移量延迟读取：offset 块内取值，滚动时只重布局顶栏、不重组上游 scope
    topBarOffsetPxProvider: () -> Float,
    // 是否正在滚动。由调用方用 derivedStateOf 收成布尔后传入 —— 不要在组合期直接读
    // 像素级 offset state（详见调用点注释）。
    isScrolling: Boolean,
    onEnterStoryMode: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: () -> Unit,
    profileAvatar: String?,
    onSelectTab: (Int) -> Unit,
    storyAnchorState: SeamlessAnchorState,
    modifier: Modifier = Modifier
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val topGlassState = LocalGlassHazeState.current
    val topGlassOn = isGlassActive(topGlassState)
    // 顶栏玻璃形状：**上边两角保持直角、下边两角倒大圆角**。
    // 上角倒圆会在状态栏两侧漏出背后滚动的内容；下角倒圆才让顶栏读作
    // "从内容上方浮出的一张玻璃片"，与底栏 Dock(32dp) 同一套大圆角语汇。
    // 形状由 `BiliRadius.topBarShape` 单一来源提供，且**必须传 CornerBasedShape**：
    // LIQUID 档 `liquidGlassSurface` 的 `lens()` 折射依赖角半径，传 0dp 直角
    // 会让折射沿整个矩形边"平推"，正是用户看到的"顶栏下方液态模糊有点奇怪"。
    val topBarShape = BiliRadius.topBarShape
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .offset { IntOffset(0, -topBarOffsetPxProvider().roundToInt()) }
            // iOS：玻璃 / 半透明底放在 offset 之后、statusBarsPadding 之前——
            // 既随顶栏 1:1 滑动，又铺满含状态栏的整条（走查发现的两个层级问题）
            .then(
                when {
                    isIOS && topGlassOn -> Modifier
                        .glassFrameRefresh(LocalGlassRefresher.current)
                        .glassEffect(
                            state = topGlassState,
                            surfaceType = GlassSurfaceType.HEADER,
                            shape = topBarShape,
                            isScrolling = isScrolling
                        )
                    isIOS -> Modifier.background(
                        MaterialTheme.colorScheme.background.copy(
                            alpha = if (dark) 0.85f else 0.92f
                        ),
                        topBarShape
                    )
                    else -> Modifier
                }
            )
            .statusBarsPadding()
            // 上 4dp 给大标题留呼吸；下 10dp 让玻璃片在下角圆角之外留出"玻璃厚度"，
            // 否则圆角会紧贴 Tab 行底边，读不出"浮起一片"的感觉。
            .padding(top = 4.dp, bottom = 10.dp)
    ) {
        if (isIOS) {
            // iOS 大标题：当前分区名，随顶栏 1:1 滚动收起
            Text(
                text = homeTabs[selectedIndex.coerceIn(0, homeTabs.lastIndex)],
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 与底栏 Dock 的 12dp 外边距对齐到同一栅格：顶栏搜索胶囊与 Dock
                // 左右边缘同列，页面纵向 chrome 才有统一的视觉中线。
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                onClick = onNavigateToSearch,
                modifier = Modifier.weight(1f),
                // 现代大圆角语言：顶栏搜索胶囊与底栏 Dock（32dp）同属一套语汇，
                // 取高度一半得全圆端。此前 iOS 用 10dp、Material 用
                // `MaterialTheme.shapes.large`（随 CornerStyle 漂移），同一顶栏
                // 在不同设置下圆角不一致，且与底栏大圆角割裂。
                shape = RoundedCornerShape(BiliRadius.searchPill),
                colors = CardDefaults.cardColors(
                    if (isIOS) {
                        // iOS 搜索胶囊：onSurfaceVariant@12%（≈ #767680 12%，方案 2.3）
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
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
            // 刷视频模式入口（原「单/双列布局切换」按钮）：OPPO 无缝 morph 从按钮
            // 长成全屏刷视频页； SeamlessAnchor 是铺在按钮上的零绘制锚点 View
            // （must 在内容 z 序之下，与首页卡片的锚点同款）。
            Box {
                IconButton(onClick = onEnterStoryMode) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "进入刷视频模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SeamlessAnchor(storyAnchorState, Modifier.matchParentSize())
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
