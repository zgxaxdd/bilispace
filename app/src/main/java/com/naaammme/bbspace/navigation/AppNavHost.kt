package com.naaammme.bbspace.navigation

import android.os.Bundle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.naaammme.bbspace.core.designsystem.component.roundScreenSafePadding
import com.naaammme.bbspace.core.designsystem.seamless.LocalBottomNavOcclusionRatio
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.glass.GlassSurfaceType
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassHazeState
import com.naaammme.bbspace.core.designsystem.glass.glassEffect
import com.naaammme.bbspace.core.designsystem.glass.isGlassActive
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.ThemeConfig
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.theme.buildNavTransitions
import com.naaammme.bbspace.core.model.FavoriteContentTarget
import com.naaammme.bbspace.core.model.HistoryTarget
import com.naaammme.bbspace.core.model.ImSessionItem
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.naaammme.bbspace.core.model.VideoSrc
import com.naaammme.bbspace.core.model.VideoTarget

import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.core.model.WebLinkTarget
import com.naaammme.bbspace.feature.dynamic.DynamicScreen
import com.naaammme.bbspace.feature.dynamic.navigation.dynamicDetailScreen
import com.naaammme.bbspace.feature.dynamic.navigation.navigateToDynamicDetail
import com.naaammme.bbspace.feature.auth.navigation.ACCOUNT_ROUTE
import com.naaammme.bbspace.feature.auth.navigation.LOGIN_ROUTE
import com.naaammme.bbspace.feature.auth.navigation.SMS_LOGIN_ROUTE
import com.naaammme.bbspace.feature.auth.navigation.accountScreen
import com.naaammme.bbspace.feature.auth.navigation.loginScreen
import com.naaammme.bbspace.feature.auth.navigation.smsLoginScreen
import com.naaammme.bbspace.feature.bbspace.navigation.bbSpaceScreen
import com.naaammme.bbspace.feature.bbspace.navigation.navigateToBbSpace
import com.naaammme.bbspace.feature.download.navigation.downloadScreen
import com.naaammme.bbspace.feature.download.navigation.navigateToDownload
import com.naaammme.bbspace.feature.favorite.navigation.favoriteScreen
import com.naaammme.bbspace.feature.favorite.navigation.navigateToFavorite
import com.naaammme.bbspace.feature.favorite.navigation.navigateToFavoriteFolder
import com.naaammme.bbspace.feature.history.navigation.historyScreen
import com.naaammme.bbspace.feature.history.navigation.historySearchScreen
import com.naaammme.bbspace.feature.history.navigation.navigateToHistory
import com.naaammme.bbspace.feature.history.navigation.navigateToHistorySearch
import com.naaammme.bbspace.feature.history.navigation.navigateToWatchLater
import com.naaammme.bbspace.feature.history.navigation.watchLaterScreen
import com.naaammme.bbspace.feature.home.HomeScreen
import com.naaammme.bbspace.feature.im.ImScreen
import com.naaammme.bbspace.feature.im.navigation.imConversationScreen
import com.naaammme.bbspace.feature.im.navigation.imMsgFeedScreen
import com.naaammme.bbspace.feature.im.navigation.navigateToImConversation
import com.naaammme.bbspace.feature.im.navigation.navigateToMsgFeed
import com.naaammme.bbspace.feature.listen.navigation.listenDetailScreen
import com.naaammme.bbspace.feature.listen.navigation.navigateToListenDetail
import com.naaammme.bbspace.feature.live.LiveViewModel
import com.naaammme.bbspace.feature.search.navigation.navigateToSearch
import com.naaammme.bbspace.feature.search.navigation.searchScreen
import com.naaammme.bbspace.feature.space.navigation.navigateToSpace
import com.naaammme.bbspace.feature.space.navigation.spaceScreen
import com.naaammme.bbspace.feature.space.navigation.spaceRelationScreen
import com.naaammme.bbspace.feature.space.navigation.navigateToSpaceRelation
import com.naaammme.bbspace.feature.home.interest.InterestScreen
import com.naaammme.bbspace.feature.settings.SettingsViewModel
import com.naaammme.bbspace.feature.settings.navigation.HOME_INTEREST_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.SETTINGS_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.settingsScreen
import com.naaammme.bbspace.feature.download.DownloadViewModel
import com.naaammme.bbspace.feature.user.UserScreen
import com.naaammme.bbspace.feature.user.UserDest
import com.naaammme.bbspace.feature.user.UserViewModel
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.playback.PlaybackHost
import com.naaammme.bbspace.playback.PlaybackHostMode
import com.naaammme.bbspace.playback.PlaybackHostViewModel
import com.naaammme.bbspace.playback.capsule.CapsuleIndicator
import com.naaammme.bbspace.playback.capsule.CapsuleIndicatorState

private const val MAIN_ROUTE = "main"
private val topLevelNavEdgePadding = 16.dp
private val topLevelNavGap = 8.dp
private val topLevelNavScrollThreshold = 72.dp
@Composable
fun AppNavHost(
    themeConfig: ThemeConfig = ThemeConfig(),
    appLink: WebLinkTarget? = null,
    onAppLinkConsumed: () -> Unit = {}
) {
    val rootNavController = rememberNavController()
    val roundSafePadding = roundScreenSafePadding(themeConfig.roundScreenSafePaddingScale)
    var currentTab by rememberSaveable { mutableStateOf(TopLevelRoute.HOME) }
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val playbackHostViewModel: PlaybackHostViewModel = hiltViewModel()
    val videoViewModel: VideoViewModel = hiltViewModel()
    val liveViewModel: LiveViewModel = hiltViewModel()
    val hostMode = playbackHostViewModel.hostMode
    var forcedDismissMode by remember { mutableStateOf<PlaybackHostMode?>(null) }
    val playbackMode = when {
        hostMode != PlaybackHostMode.Expanded -> hostMode
        forcedDismissMode != null -> forcedDismissMode!!
        else -> hostMode
    }
    val closeVideoHost: () -> Unit = {
        playbackHostViewModel.close()
    }
    val dismissPlaybackHost: () -> Unit = {
        // 胶囊等价替换（方案 2.3）：视频目标 → 激活胶囊；直播/空目标 → 原关停
        if (!playbackHostViewModel.capsuleState.activateForVideoTarget()) {
            when (playbackHostViewModel.currentTarget.value) {
                is StreamPlaybackTarget.Video -> closeVideoHost()
                is StreamPlaybackTarget.Live, null -> playbackHostViewModel.close()
            }
        }
    }
    val collapseExpandedPlayback = {
        if (hostMode == PlaybackHostMode.Expanded) {
            // 胶囊承接播放（仅视频）；直播/空目标直接 Hidden
            if (!playbackHostViewModel.capsuleState.activateForVideoTarget()) {
                playbackHostViewModel.close()
            }
            forcedDismissMode = PlaybackHostMode.Hidden
        }
    }
    val openSpaceFromVideo: (SpaceRoute) -> Unit = { route ->
        collapseExpandedPlayback()
        dismissPlaybackHost()
        rootNavController.navigateToSpace(route)
    }
    val openDownloadFromVideo: () -> Unit = {
        collapseExpandedPlayback()
        dismissPlaybackHost()
        rootNavController.navigateToDownload()
    }
    val goHomeFromVideo: () -> Unit = {
        collapseExpandedPlayback()
        dismissPlaybackHost()
        rootNavController.popBackStack(MAIN_ROUTE, false)
        currentTab = TopLevelRoute.HOME
    }
    val context = LocalContext.current
    val openVideoActivity: (VideoTarget, String?, Boolean, Bundle?) -> Unit =
        { target, coverUrl, coverLarge, bundle ->
            val intent = videoDetailIntent(
                context = context,
                target = target,
                coverUrl = coverUrl,
                coverLarge = coverLarge,
                fromSeamless = bundle != null
            )
            if (bundle != null) {
                context.startActivity(intent, bundle)
            } else {
                context.startActivity(intent)
            }
        }

    // 统一路由：支持无缝动画的设备（ColorOS 16.1+）且开关开启时一律走视频详情
    // Activity——有锚点（首页卡片）时 bundle 非空、封面矩形无缝展开；无锚点
    // （深链/搜索/历史等）用系统普通过渡；开关关闭或设备不支持时维持覆盖层路径。
    // 开关值经进程内 State 同步（设置面板改动即时生效，无需重启）。
    val seamlessEnabledState = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        playbackHostViewModel.seamlessTransitionEnabled.collect { seamlessEnabledState.value = it }
    }
    val openVideoRoute: (VideoTarget, String?, Boolean, Bundle?) -> Unit =
        { target, coverUrl, coverLarge, bundle ->
            if (OplusViewSeamlessCompat.isVersionSupported() && seamlessEnabledState.value) {
                // 点击预热：取流与窗口/无缝动画并行，详情侧 openRoot 幂等接管
                playbackHostViewModel.warmOpen(target)
                openVideoActivity(target, coverUrl, coverLarge, bundle)
            } else {
                playbackHostViewModel.capsuleState.deactivate()
                playbackHostViewModel.expand()
                videoViewModel.openRoot(target)
            }
        }
    val openVideo: (VideoTarget) -> Unit = { target ->
        openVideoRoute(target, null, false, null)
    }
    val openVideoSeamless: (VideoTarget, String, Boolean, Bundle) -> Unit =
        { target, coverUrl, coverLarge, bundle ->
            openVideoRoute(target, coverUrl, coverLarge, bundle)
        }
    // 共享无缝卡片执行器：任意页面的视频卡片经 LocalSeamlessCardOpener 接入；
    // 开关关闭时 tryOpen 直接返回 false，卡片侧自动退回普通打开。
    // cleanupScope 驱动锚点过渡位图的兜底清理（系统 onEnd 回调缺失时防残留）。
    val seamlessCleanupScope = rememberCoroutineScope()
    val seamlessCardOpener = remember(seamlessCleanupScope) {
        SeamlessCardOpener(
            route = { target, coverUrl, coverLarge, bundle ->
                openVideoSeamless(target, coverUrl ?: "", coverLarge, bundle)
            },
            cleanupScope = seamlessCleanupScope,
            enabled = { seamlessEnabledState.value }
        )
    }
    // 底部悬浮导航栏遮挡屏幕的比例：由 MainTabsScaffold 实测高度写入，再经
    // LocalBottomNavOcclusionRatio 下发给各卡片页，供无缝动画 BUNDLE_LIST_COVER 使用
    // （底部被 Tab 栏/FAB 盖住的区域，返回动画末端会做 alpha 渐隐）。
    var bottomNavOcclusionRatio by remember { mutableFloatStateOf(0f) }
    val openLive: (LiveRoute) -> Unit = { route ->
        liveViewModel.openRoute(route)
        playbackHostViewModel.openLive(route)
        playbackHostViewModel.expand()
    }
    val openHistoryTarget: (HistoryTarget?) -> Unit = { target ->
        when (target) {
            is HistoryTarget.Video -> openVideo(target.target)
            is HistoryTarget.Live -> openLive(target.route)
            is HistoryTarget.Article -> rootNavController.navigateToDynamicDetail(target.opusId,1)
            null -> Unit
        }
    }
    val openListenDetail: (Long, Int, Long, String, String, String) -> Unit = {
        oid,
        itemType,
        subId,
        title,
        author,
        cover ->
        playbackHostViewModel.close()
        rootNavController.navigateToListenDetail(oid, itemType, subId, title, author, cover)
    }
    val openArticle: (String, Int) -> Unit = { opusId, opusType ->
        // 打开专栏：视频播放继续 → 胶囊承接；直播/无目标 → 原关停
        if (!playbackHostViewModel.capsuleState.activateForVideoTarget()) {
            playbackHostViewModel.close()
        }
        rootNavController.navigateToDynamicDetail(opusId, opusType)
    }
    val transitions = remember(themeConfig.transitionStyle, themeConfig.animationSpeed) {
        buildNavTransitions<NavBackStackEntry>(
            themeConfig.transitionStyle,
            themeConfig.animationSpeed
        )
    }

    LaunchedEffect(hostMode, forcedDismissMode) {
        if (forcedDismissMode != null && hostMode != PlaybackHostMode.Expanded) {
            forcedDismissMode = null
        }
    }

    // 进程存活但 MainActivity 被系统重建（后台活动实例回收等）时，覆盖层路径的本地
    // 覆盖层展开时以会话目标校正本地页内导航栈：MainActivity 被系统重建后栈为空、
    // 恢复路径展开覆盖层时栈可能停留在旧视频——两者都重建为会话当前目标
    // （openVideoInternal 对同一 target 短路返回，不会打断播放）。
    // Activity 化路径的恢复由 MainActivity.tryRestoreVideoPage 负责。
    LaunchedEffect(hostMode) {
        if (hostMode == PlaybackHostMode.Expanded) {
            val sessionTarget =
                (playbackHostViewModel.currentTarget.value as? StreamPlaybackTarget.Video)?.target
            if (sessionTarget != null && videoViewModel.activeTarget != sessionTarget) {
                videoViewModel.openRoot(sessionTarget)
            }
        }
    }

    // 视频详情 Activity 的跨 Activity 导航请求：详情页内「回首页 / UP 主空间 /
    // 查看缓存 / 开始下载」先投递再 finish，这里复用覆盖层时代的既有逻辑执行。
    // 请求由 Channel 排队，MainActivity 不可见时投递会在回到前台后继续处理。
    LaunchedEffect(Unit) {
        playbackHostViewModel.videoDetailNavBus.requests.collect { request ->
            when (request) {
                is VideoDetailNavBus.NavRequest.GoHome -> goHomeFromVideo()
                is VideoDetailNavBus.NavRequest.OpenSpace -> openSpaceFromVideo(request.route)
                is VideoDetailNavBus.NavRequest.OpenDownloadCache -> openDownloadFromVideo()
                is VideoDetailNavBus.NavRequest.StartDownload ->
                    downloadViewModel.enqueueDownload(request.request)
            }
        }
    }

    LaunchedEffect(appLink) {
        when (val target = appLink ?: return@LaunchedEffect) {
            is WebLinkTarget.ToVideo -> openVideo(target.target)
            is WebLinkTarget.ToSpace -> rootNavController.navigateToSpace(
                SpaceRoute(mid = target.mid)
            )
            is WebLinkTarget.ToLive -> openLive(
                LiveRoute(roomId = target.roomId)
            )
            is WebLinkTarget.External,
            is WebLinkTarget.Stay -> Unit
        }
        onAppLinkConsumed()
    }

    CompositionLocalProvider(
        LocalSeamlessCardOpener provides seamlessCardOpener,
        LocalBottomNavOcclusionRatio provides bottomNavOcclusionRatio
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            modifier = Modifier
                .fillMaxSize()
                .padding(roundSafePadding),
            navController = rootNavController,
            startDestination = MAIN_ROUTE,
            enterTransition = { transitions.enter(this) },
            exitTransition = { transitions.exit(this) },
            popEnterTransition = { transitions.popEnter(this) },
            popExitTransition = { transitions.popExit(this) }
        ) {
            composable(MAIN_ROUTE) {
                MainTabsScaffold(
                    currentTab = currentTab,
                    onTabChange = { currentTab = it },
                    capsuleState = playbackHostViewModel.capsuleState,
                    onCapsuleOpen = {
                        (playbackHostViewModel.capsuleState.sessionState.value.target
                            as? StreamPlaybackTarget.Video)?.let { openVideo(it.target) }
                    },
                    onCapsuleTogglePlay = playbackHostViewModel::togglePlayPause,
                    onBottomNavOcclusionChanged = { bottomNavOcclusionRatio = it },
                    onNavigateToSearch = { rootNavController.navigateToSearch() },
                    onNavigateToSettings = { rootNavController.navigate(SETTINGS_ROUTE) },
                    onNavigateToAccount = { rootNavController.navigate(ACCOUNT_ROUTE) },
                    onNavigateToBbSpace = { rootNavController.navigateToBbSpace() },
                    onNavigateFromUser = { dest ->
                        when (dest) {
                            UserDest.History -> rootNavController.navigateToHistory()
                            UserDest.Favorite -> rootNavController.navigateToFavorite()
                            UserDest.WatchLater -> rootNavController.navigateToWatchLater()
                        }
                    },
                    onNavigateToDownload = { rootNavController.navigateToDownload() },
                    onNavigateToVideo = openVideo,
                    onNavigateToSpace = rootNavController::navigateToSpace,
                    onNavigateToLive = openLive,
                    onNavigateToArticle = openArticle,
                    onNavigateToDynamicDetail = rootNavController::navigateToDynamicDetail,
                    onNavigateToListenDetail = openListenDetail,
                    onNavigateToMsgFeed = { rootNavController.navigateToMsgFeed() },
                    onNavigateToImConversation = { item ->
                        rootNavController.navigateToImConversation(item)
                    }
                )
            }

            loginScreen(
                onLoginSuccess = { rootNavController.popBackStack() },
                onBack = { rootNavController.popBackStack() },
                onSwitchToSms = {
                    rootNavController.navigate(SMS_LOGIN_ROUTE) {
                        popUpTo(LOGIN_ROUTE) { inclusive = true }
                    }
                }
            )

            smsLoginScreen(
                onLoginSuccess = { rootNavController.popBackStack() },
                onBack = { rootNavController.popBackStack() },
                onSwitchToQr = {
                    rootNavController.navigate(LOGIN_ROUTE) {
                        popUpTo(SMS_LOGIN_ROUTE) { inclusive = true }
                    }
                }
            )

            accountScreen(
                onBack = { rootNavController.popBackStack() },
                onAddAccount = { rootNavController.navigate(SMS_LOGIN_ROUTE) },
                onSwitched = { rootNavController.popBackStack() }
            )

            bbSpaceScreen(
                navController = rootNavController,
                onOpenSpace = rootNavController::navigateToSpace,
                onOpenVideoDetail = openVideo,
                onOpenDynamicDetail = rootNavController::navigateToDynamicDetail,
                onOpenLiveDetail = openLive
            )
            settingsScreen(rootNavController)
            searchScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenSpace = rootNavController::navigateToSpace,
                onOpenVideo = openVideo
            )
            historyScreen(
                onBack = { rootNavController.popBackStack() },
                onSearch = { tab -> rootNavController.navigateToHistorySearch(tab) },
                onOpenHistoryTarget = openHistoryTarget
            )
            historySearchScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenHistoryTarget = openHistoryTarget
            )
            watchLaterScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenVideo = openVideo
            )
            favoriteScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenContent = { target ->
                    when (target) {
                        is FavoriteContentTarget.Video -> openVideo(target.target)
                        is FavoriteContentTarget.DynamicDetail -> {
                            rootNavController.navigateToDynamicDetail(target.opusId)
                        }
                    }
                },
                onOpenFolder = { fid ->
                    rootNavController.navigateToFavoriteFolder(fid)
                }
            )
            composable(HOME_INTEREST_ROUTE) {
                InterestScreen(onBack = { rootNavController.popBackStack() })
            }
            spaceScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenVideo = openVideo,
                onOpenDynamic = { opusId ->
                    rootNavController.navigateToDynamicDetail(opusId)
                },
                onOpenLive = openLive,
                onOpenIm = { mid, name, avatar ->
                    rootNavController.navigateToImConversation(
                        ImSessionItem(
                            key = "space:$mid",
                            talkerId = mid,
                            sessionType = 1,
                            name = name,
                            avatar = avatar
                        )
                    )
                },
                onOpenRelation = { vmid, initialTab ->
                    rootNavController.navigateToSpaceRelation(vmid, initialTab)
                }
            )
            spaceRelationScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenSpace = rootNavController::navigateToSpace
            )
            downloadScreen(
                navController = rootNavController,
                onBack = { rootNavController.popBackStack() },
                viewModel = downloadViewModel,
                closePlaybackHost = closeVideoHost
            )

            dynamicDetailScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenSpace = { route ->
                    rootNavController.popBackStack()
                    rootNavController.navigateToSpace(route)
                }
            )

            listenDetailScreen(
                onBack = { rootNavController.popBackStack() }
            )

            imConversationScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenSpace = rootNavController::navigateToSpace,
                onOpenVideo = openVideo
            )

            imMsgFeedScreen(
                onBack = { rootNavController.popBackStack() },
                onOpenSpace = rootNavController::navigateToSpace,
                onOpenVideoDetail = openVideo,
                onOpenDynamicDetail = rootNavController::navigateToDynamicDetail,
                onOpenLiveDetail = openLive
            )
        }

        PlaybackHost(
            mode = playbackMode,
            playbackHostViewModel = playbackHostViewModel,
            onDismissExpanded = dismissPlaybackHost,
            onGoHome = goHomeFromVideo,
            onOpenSpace = openSpaceFromVideo,
            onOpenDownloadCache = openDownloadFromVideo,
            onStartDownload = downloadViewModel::enqueueDownload,
            videoViewModel = videoViewModel,
            liveViewModel = liveViewModel
        )
        }
    }
}

@Composable
private fun MainTabsScaffold(
    currentTab: TopLevelRoute,
    onTabChange: (TopLevelRoute) -> Unit,
    capsuleState: CapsuleIndicatorState,
    onCapsuleOpen: () -> Unit,
    onCapsuleTogglePlay: () -> Unit,
    onBottomNavOcclusionChanged: (Float) -> Unit = {},
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToBbSpace: () -> Unit,
    onNavigateFromUser: (UserDest) -> Unit,
    onNavigateToDownload: () -> Unit,
    onNavigateToVideo: (VideoTarget) -> Unit,
    onNavigateToSpace: (SpaceRoute) -> Unit,
    onNavigateToLive: (LiveRoute) -> Unit,
    onNavigateToArticle: (String, Int) -> Unit,
    onNavigateToDynamicDetail: (String) -> Unit,
    onNavigateToListenDetail: (Long, Int, Long, String, String, String) -> Unit,
    onNavigateToMsgFeed: () -> Unit,
    onNavigateToImConversation: (ImSessionItem) -> Unit
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val userViewModel: UserViewModel = hiltViewModel()
    val userState by userViewModel.uiState.collectAsStateWithLifecycle()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val useStartAlignedBottomToolbar = windowSizeClass.isWidthAtLeastBreakpoint(600)
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val fixBottomBar by settingsViewModel.fixBottomBar.collectAsStateWithLifecycle()
    val navVisibilityController = rememberTopLevelNavVisibilityController(fixBottomBar)
    val anim = LocalAnimations.current

    // 玻璃背景源：页面内容（含各 Tab 页）作为 hazeSource，Dock / 胶囊在其上采样
    val glassState = remember { HazeState() }
    val glassOn = isGlassActive(glassState)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(navVisibilityController.connection)
    ) {
        // A1：主 Tab 内容切换 Crossfade（200ms Standard）。各 Tab 状态仍由
        // rememberSaveableStateHolder 保留，过渡期间新旧页面短暂并存。
        Crossfade(
            targetState = currentTab,
            modifier = if (glassOn) Modifier.hazeSource(glassState) else Modifier,
            animationSpec = tween(durationMillis = anim.medium, easing = anim.standard),
            label = "mainTabCrossfade"
        ) { tab ->
            saveableStateHolder.SaveableStateProvider(tab.route) {
                when (tab) {
                    TopLevelRoute.HOME -> HomeScreen(
                        onNavigateToSearch = onNavigateToSearch,
                        onNavigateToProfile = { onTabChange(TopLevelRoute.PROFILE) },
                        profileAvatar = userState.user?.avatar,
                        onOpenVideo = onNavigateToVideo,
                        onOpenSpace = onNavigateToSpace,
                        onOpenLive = onNavigateToLive,
                        onOpenDynamic = onNavigateToDynamicDetail,
                        onOpenArticle = onNavigateToArticle,
                        onOpenListenItem = onNavigateToListenDetail
                    )
                    TopLevelRoute.DYNAMIC -> DynamicScreen(
                        onOpenVideo = onNavigateToVideo,
                        onOpenSpace = onNavigateToSpace,
                        onOpenLive = onNavigateToLive,
                        onOpenDynamic = onNavigateToDynamicDetail
                    )
                    TopLevelRoute.MESSAGE -> ImScreen(
                        onOpenConversation = onNavigateToImConversation,
                        onOpenMsgFeed = onNavigateToMsgFeed
                    )
                    TopLevelRoute.PROFILE -> UserScreen(
                        onNavigateToAccount = onNavigateToAccount,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToBbSpace = onNavigateToBbSpace,
                        onNavigate = onNavigateFromUser,
                        onNavigateToDownload = onNavigateToDownload,
                        onOpenSpace = onNavigateToSpace
                    )
                }
            }
        }

        // 悬浮播放胶囊：内容层之后、底栏之前（z 序低于底栏，方案 2.8）
        CompositionLocalProvider(LocalGlassHazeState provides glassState) {
            CapsuleIndicator(
                capsuleState = capsuleState,
                onOpen = onCapsuleOpen,
                onTogglePlay = onCapsuleTogglePlay,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
                    .zIndex(0.5f)
            )
        }

        TopLevelFloatingNavigation(
            modifier = Modifier
                .align(
                    if (useStartAlignedBottomToolbar) Alignment.BottomStart else Alignment.BottomCenter
                )
                .padding(
                    start = if (useStartAlignedBottomToolbar) topLevelNavEdgePadding else 0.dp,
                    bottom = topLevelNavEdgePadding
                )
                .zIndex(1f),
            currentTab = currentTab,
            visibilityController = navVisibilityController,
            onTabChange = onTabChange,
            onNavigateToSearch = onNavigateToSearch,
            onOcclusionChanged = onBottomNavOcclusionChanged
        )
    }
}

@Composable
private fun TopLevelFloatingNavigation(
    modifier: Modifier = Modifier,
    currentTab: TopLevelRoute,
    visibilityController: TopLevelNavVisibilityController,
    onTabChange: (TopLevelRoute) -> Unit,
    onNavigateToSearch: () -> Unit,
    onOcclusionChanged: (Float) -> Unit = {}
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    val toolbarShape = if (isIOS) {
        // iOS：Dock 圆角刻意由 32dp（extraLarge@ROUNDED）收紧至 28dp（方案 2.5）
        RoundedCornerShape(BiliRadius.dock)
    } else {
        MaterialTheme.shapes.extraLarge
    }
    val anim = LocalAnimations.current
    val glassState = LocalGlassHazeState.current
    val glassOn = isGlassActive(glassState)
    val edgePaddingPx = with(LocalDensity.current) { topLevelNavEdgePadding.toPx() }
    var hiddenDistancePx by remember { mutableFloatStateOf(0f) }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = if (visibilityController.hidden) hiddenDistancePx + edgePaddingPx else 0f,
        animationSpec = tween(durationMillis = anim.medium, easing = anim.standard),
        label = "top level nav offset"
    )
    val animatedModifier = modifier
        .onSizeChanged { size ->
            hiddenDistancePx = size.height.toFloat()
        }
        // 实测悬浮导航栏遮挡屏幕的比例，供无缝动画 BUNDLE_LIST_COVER 使用。
        // 遮挡高度就是该悬浮栏自身高度（隐藏动画只是 graphicsLayer 平移，布局位置不变），
        // 因此这里读到的是常驻遮挡区，正合返回动画末端做渐隐所需。
        .onGloballyPositioned { coords ->
            val windowHeight = coords.parentLayoutCoordinates
                ?.size?.height ?: return@onGloballyPositioned
            if (windowHeight <= 0) return@onGloballyPositioned
            onOcclusionChanged(
                (coords.size.height.toFloat() / windowHeight).coerceIn(0f, 1f)
            )
        }
        .graphicsLayer {
            // 动画值在图层块内延迟读取：隐藏/显示过渡每帧只更新图层属性，
            // 不按帧重组调用方（此前组合期读值 → 每个滚动 tick 都重组一次）
            translationY = animatedOffsetPx
        }
    val tabs: @Composable () -> Unit = {
        if (isIOS) {
            // iOS：Segmented 胶囊滑块 + 阻尼回弹（方案 3.2 #1，对标 BiliPai BottomBar）
            val entries = TopLevelRoute.entries
            val selectedIndex = entries.indexOf(currentTab).coerceAtLeast(0)
            val itemSize = 48.dp
            Box {
                val pillX by animateDpAsState(
                    targetValue = itemSize * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "dockPillX"
                )
                Box(
                    modifier = Modifier
                        .offset(x = pillX + 4.dp)
                        .padding(4.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
                Row(modifier = Modifier.padding(4.dp)) {
                    entries.forEach { tab ->
                        val selected = tab == currentTab
                        Box(
                            modifier = Modifier
                                .size(itemSize)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTabChange(tab) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        } else {
            TopLevelRoute.entries.forEach { tab ->
                TopLevelFloatingNavigationItem(
                    tab = tab,
                    selected = currentTab == tab,
                    onClick = { onTabChange(tab) }
                )
            }
        }
    }
    val searchFab: @Composable () -> Unit = {
        FloatingActionButton(
            onClick = onNavigateToSearch,
            containerColor = if (isIOS) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
            contentColor = if (isIOS) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索"
            )
        }
    }

    Row(
        modifier = animatedModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // iOS：纯半透明色 + 细描边 + 轻环境阴影（不做模糊，方案 2.5）；
        // Material 维持 surfaceContainer 实色、无描边。
        val dockColor = when {
            // 玻璃生效：Haze 染色接管，容器底透明
            glassOn -> Color.Transparent
            isIOS -> {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                if (dark) {
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.90f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f)
                }
            }
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
        val dockModifier = when {
            isIOS && glassOn -> Modifier.glassEffect(
                state = glassState,
                surfaceType = GlassSurfaceType.BOTTOM_BAR,
                shape = toolbarShape
            )
            isIOS -> {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val borderColor = if (dark) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)
                }
                Modifier
                    .shadow(
                        elevation = 3.dp,
                        shape = toolbarShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.30f)
                    )
                    .border(1.dp, borderColor, toolbarShape)
            }
            else -> Modifier
        }
        Surface(
            modifier = dockModifier,
            shape = toolbarShape,
            color = dockColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), content = { tabs() })
        }
        Spacer(modifier = Modifier.width(topLevelNavGap))
        searchFab()
    }
}

@Composable
private fun TopLevelFloatingNavigationItem(
    tab: TopLevelRoute,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        FilledIconButton(onClick = onClick) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label
            )
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Stable
private class TopLevelNavVisibilityController(
    thresholdPx: Float,
    initialHidden: Boolean = false,
    private val fixBottomBar: Boolean = false
) {
    private val triggerThresholdPx = thresholdPx
    private var accumulatedScroll = 0f

    var hidden by mutableStateOf(initialHidden)
        private set

    val connection = object : NestedScrollConnection {
        @Suppress("SameReturnValue")
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (fixBottomBar) return Offset.Zero
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y == 0f) return Offset.Zero
            if (kotlin.math.abs(available.y) < kotlin.math.abs(available.x)) return Offset.Zero
            updateVisibility(available.y)
            return Offset.Zero
        }
    }

    private fun updateVisibility(deltaY: Float) {
        if (deltaY == 0f) return
        if ((deltaY < 0f && accumulatedScroll > 0f) || (deltaY > 0f && accumulatedScroll < 0f)) {
            accumulatedScroll = 0f
        }
        accumulatedScroll += deltaY
        when {
            accumulatedScroll <= -triggerThresholdPx -> {
                if (!hidden) {
                    hidden = true
                }
                accumulatedScroll = 0f
            }
            accumulatedScroll >= triggerThresholdPx -> {
                if (hidden) {
                    hidden = false
                }
                accumulatedScroll = 0f
            }
        }
    }
}

@Composable
private fun rememberTopLevelNavVisibilityController(
    fixBottomBar: Boolean = false
): TopLevelNavVisibilityController {
    val thresholdPx = with(LocalDensity.current) { topLevelNavScrollThreshold.toPx() }
    return remember(thresholdPx, fixBottomBar) {
        TopLevelNavVisibilityController(thresholdPx = thresholdPx, fixBottomBar = fixBottomBar)
    }
}
