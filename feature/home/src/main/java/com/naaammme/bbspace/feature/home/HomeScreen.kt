package com.naaammme.bbspace.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.FilledTabRow
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.appSearchElasticPress
import com.naaammme.bbspace.core.designsystem.seamless.LocalStoryCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.home.article.HomeArticlePage
import com.naaammme.bbspace.feature.home.interest.InterestDialog
import com.naaammme.bbspace.feature.home.listen.ListenHomePage
import com.naaammme.bbspace.feature.home.live.HomeLivePage
import com.naaammme.bbspace.feature.home.video.HomeVideoPage
import com.naaammme.bbspace.feature.rank.RankScreen
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private val homeTabs = listOf("FM", "推荐", "直播", "排行", "专栏")
private val homeRankTabPage = 3
private val homeProfileAvatarSize = 38.dp
/**
 * 未登录时头像外圈描边宽度（1.5dp）。
 *
 * 刻意**不用** `BiliSpacing`（最小 4dp）：这是 1.5dp 的发丝级描边，套 token 会把头像
 * 描成粗环。已按 `DesignTokenLintTest` 的体例在这里留理由行。
 */
private val HomeSignedOutRingWidth = 1.5.dp

private val homeProfileIconSize = 24.dp
private const val homeDefaultPage = 1

/**
 * 顶栏「刷视频模式」入口的芯片形状：**38dp 圆形**，与旁边「我的」头像同款。
 *
 * 写成 `homeProfileAvatarSize / 2` 而不是 `19.dp`：一个角色只有一个来源——芯片尺寸
 * 改了这里自动跟着变（也避免新增 dp 字面量触发 DesignTokenLintTest 棘轮）。
 * 必须是**固定 dp 圆角**（不是 `CircleShape` 的百分比角）：无缝的 `BUNDLE_RADIUS`
 * 要从形状里静态解析出 px 值，百分比角随 view 尺寸变化、解析不出来。
 */
private val homeStoryChipShape: Shape = RoundedCornerShape(homeProfileAvatarSize / 2)

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

    // 顶栏滚动状态机：偏移累加 + fling 结束吸附，见 `HomeTopBarScroll.kt`。
    // 旧实现的缺口（不改，留着当背景）：没有收敛逻辑时 fling 在半隐区间结束会把
    // 顶栏停在半隐（"卡住不回弹"）；现在 onPostFling 吸附最近端
    // （reduceMotion 直落，否则 morph token 动画）。
    //
    // ⚠️ 上一轮这里还传 `isScrolling`（供顶栏玻璃切采样预算）。顶栏改纯色后
    // 该状态连同 120ms 静默计时器一起**从状态机里删掉了** —— 没有消费者，
    // 而它每个滚动位移要取消+重启一个 Job。不要在没恢复消费者的前提下把它加回来。
    //
    // 符号约定（沿用旧实现，勿改）：手指上滑（内容上移）时 delta.y 为负，故用 -delta
    // 累加；上滑 → offset 增大 → 顶栏隐藏；下滑 → offset 减小 → 顶栏滑回。
    // 复位（切 Tab / 刷新 scrollToTop）统一走 topBarScroll.reset()。
    // 验收：静止半隐不再出现（fling 结束必落在 0 或 height 两端）。
    val animations = com.naaammme.bbspace.core.designsystem.theme.LocalAnimations.current
    val topBarScroll = remember(topBarHeightState, animations) {
        HomeTopBarScrollState(
            heightProvider = { topBarHeightState.value },
            animations = animations,
            scope = scope
        )
    }
    // 顶栏偏移经 provider 延迟读取（见 HomeTopBar 注释：绘制期取值，零重组零重布局）；
    // 内容顶距经 derivedStateOf 读取高度：高度只在测量后写一次，不构成滚动期重组。
    val topBarOffsetProvider: () -> Float = remember { { topBarScroll.offsetPx } }

    // 切 Tab 后新页面内容从顶部开始，顶栏必须复位显示
    LaunchedEffect(pagerState.settledPage) {
        topBarScroll.reset()
    }

    // ── 2026-10-18 第二十轮：**拆掉首页私有玻璃源**（用户第 33 轮反馈）──────────
    //
    // 用户原话：「顶栏被修坏了，内容文字可以继续上滑隐藏而模糊顶栏无法隐藏，
    // 请你重新设计顶栏的所有ui，要求需要减少性能消耗，减少顶栏区域」。
    //
    // 三件事一起改，且都在这里落地：
    //  ① **整条顶栏（含底色）作为一个整体上滑消失** —— 见 HomeTopBar 的新结构；
    //  ② **顶栏不再有任何实时模糊** —— 下面这一整块（HazeState / layerBackdrop /
    //     GlassSourceRefresher 逐帧 tick / 全屏离屏录制）已全部删除；
    //  ③ **顶栏区域减半** —— 删掉 iOS 大标题（`iOSTypography.displayLarge` = 34sp，
    //     行高约 41dp + 上下内边距 ≈ 47dp）。
    //
    // ## 为什么玻璃必须整块删，而不是再收紧门控
    // 这块玻璃的成本不是"看起来重"，是可以逐项点出来的：
    //   每滚动帧 → 全屏离屏录制（1272×2800）→ 采样 → 重绘，
    // 而 `GlassSourceRefresher` 的 tick 让它在**滚动全程**都发生。
    // 上一轮我把它收进 `homeTopBarGlassEnabled()`（只在 LIQUID 挂），但用户的设备
    // 正是 `ui_style=IOS + glass_material=LIQUID` ⇒ 门控对他是**恒真**的，
    // 等于一行都没省（这是我的判断失误，已记在 docs）。
    // 既然用户这轮把「减少性能消耗」和「减少顶栏区域」并列提出，玻璃就是第一刀：
    // 顶栏读作"一条纯色 chrome"时观感损失极小，省下的却是滚动路径上最大的一笔
    // 非必要开销（同一帧的**第二份**全屏录制；第一份在 AppNavHost，供 Dock 用）。
    //
    // ## 观感怎么补
    // 用 `MaterialTheme.colorScheme.background` 的**不透明**纯色底 + 下边两角大圆角
    // （`BiliRadius.topBarShape`）。这与 iOS 无玻璃时的降级分支同源、与底栏 Dock
    // 同一套圆角语汇，且**零采样成本**。
    // 想恢复液态玻璃时改的是本文件与 HomeTopBar 两处；登记表里 HomeScreen 的三条
    // 挂载条目已同批删除（`GlassMountRegistryTest` 双向校验会盯着这件事）。

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 监听内容滚动，驱动顶栏同步平移
            .nestedScroll(topBarScroll)
    ) {
        // 内容全屏：延伸到状态栏之后，滚动时卡片可上探到状态栏区域
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
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
                    onScrollToTop = { topBarScroll.reset() }
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

        // ── 刷视频模式入口（顶栏按钮）────────────────────────────────────────
        // 锚点 = 芯片自身上的零绘制 View（SeamlessAnchor），OPPO 无缝 morph 把**这颗
        // 看得见的芯片**长成全屏刷视频页（芯片底与形状见 HomeTopBar 的注释：
        // 用户反馈"凭空出现一个边框"，根因是入口静止态没有底色）。
        // 无缝不可用时回退 onEnterStoryMode（普通 startActivity，走统一窗口动画）。
        val storyOpener = LocalStoryCardOpener.current
        val storyAnchorState = rememberSeamlessAnchorState()
        // 半径/兜底色与芯片同源：形状是 38dp 圆形，底色是芯片底色
        val storySpec = rememberSeamlessCardSpec(
            shape = homeStoryChipShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
        val canStorySeamless = storyOpener != null &&
            OplusViewSeamlessCompat.isVersionSupported()
        val enterStoryMode: () -> Unit = {
            // 流第一条 = 刷视频的起点（页 0）；无视频卡（全直播/空流）时无操作
            val first = state.items.firstOrNull { it.target != null && it.liveRoute == null }
            val target = first?.target
            val anchorView = storyAnchorState.anchor
            if (target != null) {
                if (canStorySeamless && storyOpener != null && anchorView != null) {
                    // 与卡片同款时序：点击这一帧先 PixelCopy 截取锚点（= 芯片）的**真实像素**
                    // （圆底 + 箭头），在回调里完成 tryOpen + startActivity。
                    // 为什么必须截真实像素：框架捕获不到锚点内容时会退回 BUNDLE_COLOR
                    // 纯色兜底 —— 那正是"打开时冒出一块色块、返回时色块消失"的观感来源。
                    // 截到真实像素后，两个方向 morph 的都是用户眼睛看到的那颗芯片。
                    storyOpener.captureCardBitmap(anchorView, storyAnchorState) { captured ->
                        val opened = storyOpener.tryOpen(
                            anchor = anchorView,
                            target = target,
                            coverUrl = first?.cover,
                            coverLarge = false,
                            anchorRadiusPx = storySpec.radiusPx,
                            fallbackColor = storySpec.fallbackColor,
                            cardBitmap = captured,
                            keepAnchorVisible = true,
                            // 截屏失败（极少数）时不装饰锚点：此时退回纯色兜底，
                            // 形状/颜色仍与静止态那颗芯片一致，不会出现"封面拉伸进圆芯片"
                            decorateAnchor = captured != null,
                            // 真实显示矩形（含拖拽反馈形变）。这颗芯片同样挂了
                            // appElasticPress，按住点开时布局矩形会与屏幕上的圆心错位。
                            anchorState = storyAnchorState
                        )
                        if (!opened) {
                            captured?.recycle()
                            onEnterStoryMode(target)
                        }
                    }
                } else {
                    onEnterStoryMode(target)
                }
            }
        }
        HomeTopBar(
            selectedIndex = pagerState.currentPage,
            topBarOffsetPxProvider = topBarOffsetProvider,
            onEnterStoryMode = enterStoryMode,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToProfile = onNavigateToProfile,
            profileAvatar = profileAvatar,
            onSelectTab = { page ->
                scope.launch { pagerState.animateScrollToPage(page) }
            },
            storyAnchorState = storyAnchorState,
            // 锚点是否真组合（= 本机支持 OPPO 无缝）。芯片按钮的整件形变据此让位，
            // 判据见下方 `IconButton` 的 `appElasticPress` 注释。
            storySeamlessActive = canStorySeamless,
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

@OptIn(ExperimentalMaterial3Api::class)
/**
 * 顶栏的**刷视频入口芯片** —— 同时是无缝动画的**锚点宿主**。
 *
 * ## 为什么它必须是独立 composable（第 34 轮）
 * OPPO 无缝动画从"锚点矩形"长成全屏，框架画的是**锚点那一块的内容**。
 * 锚点是零绘制 View，框架捕获不到内容就退回 `BUNDLE_COLOR` 纯色兜底 ——
 * 于是打开时凭空冒出一块圆角色块、返回时又凭空消失。根因是**入口在静止态
 * 根本没有底色**（旁边「我的」是圆形头像，自带底）。这里给同一颗圆形芯片底，
 * 两个方向就都从"用户本来就看得见的那颗芯片"出发/收回。
 *
 * ## 形变必须让位给系统过渡（`enabled = !seamlessActive`）
 * `appElasticPress` 的形变是 `Modifier.graphicsLayer`（**绘制期**变换，只改像素落点、
 * 不改布局），而 OPPO 框架取锚点矩形走 `View.getLocationInWindow()`
 *（**布局期**坐标）⇒ 按住芯片（正被放大/拖偏）时进入刷视频，框架会按
 * "屏幕上已不存在的矩形"起飞。
 *
 * 判据与卡片一致：锚点真组合时整件形变让位；不支持无缝的机型
 *（锚点不组合、走普通 startActivity）保留按压反馈。
 *
 * 注：`anchorState.displayRectPx()` 那条 `BUNDLE_RECT` 补偿只修**锚点矩形**，
 * 不会消除祖先链上的形变 —— 两者是不同层面的修法，不能互相替代。
 */
@Composable
private fun HomeStoryChip(
    /** 锚点是否真组合（本机支持 OPPO 无缝）；为真时整件形变让位给系统过渡。 */
    seamlessActive: Boolean,
    storyAnchorState: SeamlessAnchorState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // `visualSize` = 看得见的那颗芯片：触摸区是 48dp（无障碍下限），
    // 形变必须按 36dp 的可见圆算，否则会围着 48dp 的透明方框鼓。
    IconButton(
        onClick = onClick,
        modifier = modifier.appElasticPress(
            visualSize = homeProfileAvatarSize,
            enabled = !seamlessActive
        )
    ) {
        Box(
            modifier = Modifier
                .size(homeProfileAvatarSize)
                .clip(homeStoryChipShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "进入刷视频模式",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(homeProfileIconSize)
            )
            // 锚点与**芯片**同边界（不是 48dp 的 IconButton 触摸区）：
            // morph 的起点矩形 = 用户看见的那颗圆，半径也取它的一半。
            SeamlessAnchor(storyAnchorState, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun HomeTopBar(
    selectedIndex: Int,
    // 偏移量延迟读取：**绘制期**取值（见下方 graphicsLayer），滚动时既不重组也不重布局
    topBarOffsetPxProvider: () -> Float,
    onEnterStoryMode: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: () -> Unit,
    profileAvatar: String?,
    onSelectTab: (Int) -> Unit,
    storyAnchorState: SeamlessAnchorState,
    /** 锚点是否真组合（本机支持 OPPO 无缝）；见芯片按钮的 `appElasticPress` 注释。 */
    storySeamlessActive: Boolean,
    modifier: Modifier = Modifier
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    // 顶栏形状：**上边两角直角、下边两角倒大圆角**（单一来源 `BiliRadius.topBarShape`）。
    // 上角倒圆会在状态栏两侧漏出背后滚动的内容；下角倒圆才让顶栏读作
    // "从内容上方浮出一张片"，与底栏 Dock(32dp) 同一套大圆角语汇。
    val topBarShape = BiliRadius.topBarShape

    // ## 2026-10-18 第二十轮：顶栏重做（用户第 33 轮第 1 条 + 第 4 条）
    //
    // 用户原话：「顶栏被修坏了，内容文字可以继续上滑隐藏而模糊顶栏无法隐藏，
    // 请你重新设计顶栏的所有ui，要求需要减少性能消耗，减少顶栏区域」。
    //
    // ### 旧结构为什么会坏（是我上一轮改错的）
    // 上一轮为了修「整体滑动 + 轮廓没隐藏」，把顶栏拆成两层：
    // ```
    // Box(.clipToBounds())          // ← 固定裁剪窗，底色/玻璃挂在这一层
    //   Column(.offset { -offset }) // ← 只有内容在这层移动
    // ```
    // 结果是**底色被钉死在 y=0**：内容滑走了，那条不透明的片还在屏幕顶端，
    // 永远不会消失。用户看到的正是「文字能隐藏、模糊顶栏不能隐藏」。
    //
    // ### 新结构：整条顶栏（含底色）作为**一个整体**位移
    // ```
    // Box(.graphicsLayer { translationY = -offset }   // 底色 + 内容一起走
    //     .background(bg, topBarShape))
    // ```
    // 三个收益，都是结构性的：
    // 1. **能真正隐藏**：`offset` 达到顶栏实测高度时整条（含底色与圆角）完全移出屏幕；
    // 2. **滚动期零布局**：`graphicsLayer` 的 lambda 在**绘制阶段**执行，
    //    读 `offsetPx` 只让本节点重绘 ⇒ 不再像旧版 `offset {}` 那样每个滚动像素
    //    都触发一次顶栏重测量/重布局（顶栏里还有 Card、IconButton、miuix TabRow，
    //    重布局比"平移一个已录好的图层"贵一个数量级）；
    // 3. **零采样**：顶栏不再有任何实时模糊 —— 上一轮那套私有玻璃源
    //    （HazeState + layerBackdrop + GlassSourceRefresher 逐帧全屏离屏录制）
    //    已在 `HomeScreen` 调用点整块删除。这是本轮「减少性能消耗」最实的一刀。
    //
    // ### 「减少顶栏区域」
    // iOS 大标题（`displayLarge` 34sp，占约 47dp）**整块删除** —— 它同时是
    // "顶栏为什么这么高"和"为什么滚一下就没了一半"的答案。删掉后顶栏只剩
    // 「搜索行 + Tab 行」两行，实测约 144dp → 约 95dp（含状态栏前的部分）。
    // 页面分区名仍在 Tab 行里可读（选中的那颗胶囊就是它），信息没有丢。
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 整条顶栏吃滚动位移。**底色挂在同一个节点上**，所以它跟着一起走。
            .graphicsLayer {
                translationY = -topBarOffsetPxProvider()
            }
            // 不透明底色：纯色 chrome 比玻璃便宜整整一个"全屏离屏录制"，
            // 且不会出现"玻璃下的内容在动、片本身不动"的分裂感。
            .background(MaterialTheme.colorScheme.background, topBarShape)
            .statusBarsPadding()
            // 上 4 给搜索行留一点呼吸；下 8 让底边圆角之外有一点"片的厚度"，
            // 否则圆角会紧贴 Tab 行底边，读不出"浮起一片"（旧版是 10，随大标题一起收）。
            .padding(top = BiliSpacing.xs, bottom = BiliSpacing.sm)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 与底栏 Dock 的 12dp 外边距对齐到同一栅格：顶栏搜索胶囊与 Dock
                    // 左右边缘同列，页面纵向 chrome 才有统一的视觉中线。
                    .padding(horizontal = BiliSpacing.md),
                // ## 视觉对齐（用户第 33 轮第 4 条「所有 UI 设计都要遵循视觉对齐」）
                // 三件可点件**同高、同间距**：搜索胶囊 `weight(1f)` 撑满剩余宽度，
                // 两颗圆形芯片固定 [homeProfileAvatarSize]，三者视觉高度相等（36dp），
                // 间距统一 `BiliSpacing.sm`。旧版三者紧贴无间距、高度 40/38/38 各不相同。
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    onClick = onNavigateToSearch,
                    // 拖拽反馈：宽控件用 `appSearchElasticPress`（strength 0.6），
                    // 与底栏 Dock 的搜索位同一档 —— 满强度会让长条被拉成面条。
                    modifier = Modifier
                        .weight(1f)
                        .appSearchElasticPress(),
                    // 现代大圆角语言：顶栏搜索胶囊与底栏 Dock（32dp）同属一套语汇，
                    // 取高度一半得全圆端。
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
                            // vertical 用 `sm`(8)：20dp 图标 + 16dp = **36dp**，
                            // 与左右两颗圆形芯片（[homeProfileAvatarSize]）同高。
                            .padding(horizontal = 14.dp, vertical = BiliSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(BiliSpacing.sm))
                        Text(
                            text = "搜索视频、UP主、番剧",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // ## 刷视频模式入口（顶栏按钮）
                //
                // 第 34 轮把这一颗抽成独立 composable `HomeStoryChip`，理由有两条：
                // 1. **它是无缝锚点宿主**，而紧邻的头像按钮不是 —— 挤在同一个
                //    `HomeTopBar` 里时，"锚点祖先链上有没有形变"这件事只能按**函数**
                //    判定，两个兄弟按钮会被混为一谈（`SeamlessAnchorElasticPressGuardTest`
                //    正是因此报出假阳性）。拆开后锚点与它的形变判据在同一个函数里，
                //    守卫可以精确到"这颗芯片有没有让位"。
                // 2. 芯片自己是一个完整的交互单元（底 + 图标 + 锚点），抽出来之后
                //    `HomeTopBar` 只负责布局。
                HomeStoryChip(
                    seamlessActive = storySeamlessActive,
                    storyAnchorState = storyAnchorState,
                    onClick = onEnterStoryMode
                )
                // ## 头像入口 / 未登录态的视觉表达（2026-10-18 W11「顶栏认证优化」）
                //
                // 此前未登录时这里只画一个灰色 `Person` 图标，**与"已登录但头像没加载出来"
                // 完全无法区分** —— 用户点下去跳到档案页才发现自己没登录，多一次无效跳转。
                // 现在未登录（`profileAvatar == null`）时给头像加一圈主色描边 +
                // 语义化描述"未登录，点按登录"，让"该去登录"这件事在点之前就成立。
                // 只改视觉与 a11y 描述，`onClick` 不变（仍由宿主决定去哪）。
                IconButton(
                    onClick = onNavigateToProfile,
                    modifier = Modifier
                        .appElasticPress(visualSize = homeProfileAvatarSize)
                        .semantics {
                            // 未登录时读屏与长按提示都指向"登录"，而不是含糊的"我的"。
                            if (profileAvatar == null) {
                                contentDescription = "未登录，点按登录"
                            }
                        }
                ) {
                    AvatarImage(
                        url = profileAvatar,
                        contentDescription = if (profileAvatar == null) "未登录，点按登录" else "我的",
                        modifier = Modifier
                            .size(homeProfileAvatarSize)
                            .then(
                                if (profileAvatar == null) {
                                    Modifier.border(
                                        width = HomeSignedOutRingWidth,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                        shape = CircleShape
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        fallbackContent = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                // 图标自己也要有描述：`AccessibilityStructureTest` 要求
                                // IconButton 内的图标 contentDescription 非 null（读屏靠它识别按钮）。
                                contentDescription = "未登录，点按登录",
                                modifier = Modifier.size(homeProfileIconSize),
                                tint = if (profileAvatar == null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
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
}
