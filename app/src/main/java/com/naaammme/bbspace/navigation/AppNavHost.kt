package com.naaammme.bbspace.navigation

import android.os.Bundle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
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
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.designsystem.component.topSolidProgressiveFade
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.glass.GlassSourceRefresher
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassConfig
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassHazeState
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassRefresher
import com.naaammme.bbspace.core.designsystem.glass.LocalLiquidBackdrop
import com.naaammme.bbspace.core.designsystem.glass.glassEffect
import com.naaammme.bbspace.core.designsystem.glass.isGlassActive
import com.naaammme.bbspace.core.designsystem.glass.rememberChromeBackdropSource
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
import com.naaammme.bbspace.playback.capsule.CapsuleIndicatorState
import com.naaammme.bbspace.playback.capsule.DockNavInnerPadding
import com.naaammme.bbspace.playback.capsule.DockNavItemWidth
import com.naaammme.bbspace.playback.capsule.LinkedBottomDock
import com.naaammme.bbspace.playback.capsule.DockVerticalPadding
import com.naaammme.bbspace.playback.capsule.LinkedDockState
import com.naaammme.bbspace.playback.capsule.LinkedNowPlayingRow

private const val MAIN_ROUTE = "main"
private val topLevelNavEdgePadding = 22.dp
private val topLevelNavScrollThreshold = 72.dp

// 联动底栏（官方规格）：滚动切态阈值 24dp（Dock 内边距见 LinkedBottomDock 的 Dock*Padding）
private val linkedDockScrollThreshold = 24.dp
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
                    },
                    // 详情页存活 → 小横条只隐藏不卸载（保留无缝返回动画的锚点）
                    detailAlive = playbackHostViewModel.videoDetailAlive
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
    onNavigateToImConversation: (ImSessionItem) -> Unit,
    detailAlive: Boolean = false
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val userViewModel: UserViewModel = hiltViewModel()
    val userState by userViewModel.uiState.collectAsStateWithLifecycle()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useStartAlignedBottomToolbar = windowSizeClass.isWidthAtLeastBreakpoint(600)
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val fixBottomBar by settingsViewModel.fixBottomBar.collectAsStateWithLifecycle()
    val topProgressiveFade by settingsViewModel.topProgressiveFade.collectAsStateWithLifecycle()
    // 播放会话激活期间固定 Dock：下滑只驱动胶囊融合，不再与 Dock 自动隐藏抢戏
    val navVisibilityController = rememberTopLevelNavVisibilityController(fixBottomBar || capsuleState.active)
    // 详情页存活期间，小横条只**隐藏**、不卸载：它是无缝返回动画的锚点宿主。
    // 卸载会让锚点 View detach，系统返回时取不到锚点 → 退回 alpha 交叉淡出
    // （实测日志 `anchor released (wasAttached=false)` + `do alpha anim`）。
    // 值由 AppNavHost 的 composable 作用域传入（见 MainTabsScaffold 的 detailAlive 参数）。
    val anim = LocalAnimations.current

    // 玻璃背景源：页面内容（含各 Tab 页）作为 hazeSource，Dock / 胶囊在其上采样。
    val glassState = remember { HazeState() }
    val glassOn = isGlassActive(glassState)
    // 官方液态玻璃（BiliPai 同款）：miuix ChromeBackdropSource 录制主 Tab 内容，
    // Dock / 胶囊 drawBackdrop 采样（blur + 边缘折射 + vibrancy + 高光）。
    val backdropSource = rememberChromeBackdropSource()
    val liquidGlassOn = LocalGlassConfig.current.material == GlassMaterial.LIQUID && glassOn
    // 两条玻璃链路**互斥地**录屏，不要同时挂上（T1：拆逐帧全屏重录链的一半）。
    //
    // `glassEffect` 在 LIQUID 且有背板时走 `liquidGlassSurface(backdrop)` 后**直接
    // return**，根本不碰 haze（见 GlassEffect.kt 的 LIQUID 分支）。也就是说默认
    // LIQUID 档下这里的 `hazeSource` 每天都在白录一整屏 1272×2800 —— 而且 Crossfade
    // 是全 App 的根，这一份比首页顶栏自己那份影响面更大（四个 Tab 都吃）。
    //
    // 为什么还留着 HazeState 而不是整个删掉：
    // - FROSTED 档必须有人录 haze 源，这是它唯一的采样路径；
    // - `isGlassActive(state)` 要求 state != null 才承认玻璃生效，所以对象本身
    //   要照常 create + provide，只是**不再往它上面挂录制器**。
    val hazeGlassOn = glassOn && !liquidGlassOn
    // 玻璃源重录触发器：录制节点只在自身重绘时重录，而信息流列表滚动不令其重绘，
    // 滚动衔接处玻璃会采样到上一帧（骨架屏/新卡片更替最明显；FM/直播页内容平稳
    // 故不可见）。对拖拽+惯性逐帧计 tick：Crossfade 的 drawWithContent 读它强制
    // 源重录；Dock/胶囊消费端经 LocalGlassRefresher 读同一 tick 强制重采样。
    val glassRefresher = remember { GlassSourceRefresher() }
    // 联动底栏（任务 1）：滚动只负责**判定态**，不逐帧驱动进度。官方策略是
    // 24dp 累积阈值 + 方向反转清零 + 仅首页生效，切态后由 updateTransition 一次
    // 成型（280ms morph tween）——逐帧 snapTo 的旧耦合方案正是"跟手但生硬"的原因。
    val dockThresholdPx = with(LocalDensity.current) { linkedDockScrollThreshold.toPx() }
    val dockState = remember(dockThresholdPx) { LinkedDockState(dockThresholdPx) }
    // nestedScroll 的 connection 是 remember{} 出来的普通对象，直接闭包读组合期变量
    // 会永远拿到首帧值，故这三个输入走 rememberUpdatedState。
    val isHomeTabState = rememberUpdatedState(currentTab == TopLevelRoute.HOME)
    val hasAudioState = rememberUpdatedState(capsuleState.active)
    val chromeScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) dockState.onScrollStarted()
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed.y == 0f && available.y == 0f) return Offset.Zero
                glassRefresher.tick()
                if (!isHomeTabState.value) {
                    dockState.clearAccumulation()
                    return Offset.Zero
                }
                dockState.onScrollDelta(
                    // 符号约定：官方喂给 accumulator 的是 `LocalHomeScrollOffset` 的**偏移增量**
                    // （`currentOffset - scrollDeltaY`），BiliPai 的全局偏移与 BBSpace 相反——
                    // 下滑偏移增大为正。nestedScroll 给的 `consumed/available` 是**滚轮增量**：
                    // 手指上滑（内容向下翻、导航收起）为负。直接喂进去会整体反向
                    // （上滑反而展开、下滑反而合并），故取负对齐官方「上滑收起 / 下滑展开」。
                    delta = -(if (consumed.y != 0f) consumed.y else available.y),
                    hasAudio = hasAudioState.value,
                    // 到顶仍继续下拉 = 列表吃不下这段负增量（官方 `offset <= 0 && delta < 0`）
                    atTopPullDown = source == NestedScrollSource.UserInput &&
                        consumed.y == 0f &&
                        available.y < 0f
                )
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                dockState.onScrollStopped()
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(navVisibilityController.connection)
            .nestedScroll(glassRefresher.nestedScrollConnection)
            .nestedScroll(chromeScrollConnection)
    ) {
        // A1：主 Tab 内容切换 Crossfade（200ms Standard）。各 Tab 状态仍由
        // rememberSaveableStateHolder 保留，过渡期间新旧页面短暂并存。
        Crossfade(
            targetState = currentTab,
            modifier = Modifier
                .drawWithContent {
                    // 读滚动 tick：绘制期读状态，滚动帧触发本节点重绘（重录玻璃源）
                    glassRefresher.frame
                    drawContent()
                }
                .then(if (liquidGlassOn) backdropSource.modifier else Modifier)
                .then(if (hazeGlassOn) Modifier.hazeSource(glassState) else Modifier)
                // 页面底色必须在录制链**内**：双列卡片文字区无底色（透明），底色若画在
                // 外层 Box，背板里文字区就是空像素，玻璃采样后渲染成黑色（封面不透明
                // 所以正常——这正是"图片正常、简介发黑"的根因）。
                .background(MaterialTheme.colorScheme.background)
                // 顶部渐进消隐（同步 BiliPai 上游 2d987c4）：内容滚入顶栏时按 Telegram
                // 5 阶非线性 alpha 阶梯融进底色，消除线性渐变的马赫带切面感。
                //
                // **玻璃顶栏生效时不再叠这一层**：顶栏玻璃自身就提供"内容在它下方消隐"
                // 的观感，再压一层不透明纯色，等于在内容上方放一块 132dp 高的白板
                // （浅色主题底色近白，实测白带下沿到 y=462px，比顶栏本体还高）——
                // 用户反馈的"白色遮罩好丑"就是它。玻璃链路里这一层还会被录进背板，
                // 让顶栏采样到一片纯色而不是真实内容。
                .topSolidProgressiveFade(
                    surfaceColor = MaterialTheme.colorScheme.background,
                    fadeHeight = 96.dp,
                    enabled = topProgressiveFade && !glassOn,
                    // 非玻璃档也留一点透气：顶栏自身底色已提供不透明区，
                    // 遮罩只需要负责"接缝处的过渡"，不需要自己变成一块实心色板。
                    peakAlpha = 0.85f
                ),
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

        // 悬浮播放胶囊 + 悬浮底栏：内容层之后（z 序：胶囊 0.5 < 底栏 1）。
        //
        // 修复：LocalGlassHazeState 必须同时覆盖底栏。此前 Provider 只包住胶囊，
        // 而 TopLevelFloatingNavigation 在其作用域之外 —— 底栏内
        // `LocalGlassHazeState.current` 读到 null，导致 isGlassActive(null)=false、
        // dockColor 走不透明分支、glassEffect(state=null) 首行即 return。
        // 结果：底栏玻璃完全不生效（本轮"没效果"的直接原因）。
        CompositionLocalProvider(
            LocalGlassHazeState provides glassState,
            LocalLiquidBackdrop provides backdropSource.backdrop,
            LocalGlassRefresher provides glassRefresher
        ) {
            TopLevelFloatingNavigation(
                modifier = Modifier
                    .align(
                        if (useStartAlignedBottomToolbar) Alignment.BottomStart else Alignment.BottomCenter
                    )
                    // Dock 自带官方 12/8dp 内边距，这里只在平板左对齐工具栏时留启动侧边距
                    .then(
                        if (useStartAlignedBottomToolbar) {
                            Modifier.padding(start = topLevelNavEdgePadding)
                        } else {
                            Modifier
                        }
                    )
                    .zIndex(1f),
                currentTab = currentTab,
                dockState = dockState,
                visibilityController = navVisibilityController,
                capsuleState = capsuleState,
                onTabChange = onTabChange,
                onCapsuleOpen = onCapsuleOpen,
                onCapsuleTogglePlay = onCapsuleTogglePlay,
                onNavigateToSearch = onNavigateToSearch,
                onOcclusionChanged = onBottomNavOcclusionChanged,
                detailAlive = detailAlive
            )
        }
    }
}

@Composable
private fun TopLevelFloatingNavigation(
    modifier: Modifier = Modifier,
    currentTab: TopLevelRoute,
    dockState: LinkedDockState,
    visibilityController: TopLevelNavVisibilityController,
    capsuleState: CapsuleIndicatorState,
    onTabChange: (TopLevelRoute) -> Unit,
    onCapsuleOpen: () -> Unit,
    onCapsuleTogglePlay: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOcclusionChanged: (Float) -> Unit = {},
    detailAlive: Boolean = false
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    val anim = LocalAnimations.current
    val dockVerticalPaddingPx = with(LocalDensity.current) { DockVerticalPadding.toPx() }
    var hiddenDistancePx by remember { mutableFloatStateOf(0f) }
    val animatedOffsetPx by animateFloatAsState(
        targetValue = if (visibilityController.hidden) hiddenDistancePx else 0f,
        animationSpec = tween(durationMillis = anim.medium, easing = anim.standard),
        label = "top level nav offset"
    )
    // 内容槽位：官方以 `nowPlayingContent != null` 作为 hasAudio，无会话时整个
    // 横条子节点不参与组合（也就不会有零宽 Surface 采样）。
    val nowPlayingContent: (@Composable (Modifier, Float, Boolean) -> Unit)? = if (capsuleState.active) {
        { contentModifier, merge, layoutStable ->
            LinkedNowPlayingRow(
                capsuleState = capsuleState,
                merge = merge,
                layoutStable = layoutStable,
                onOpen = onCapsuleOpen,
                onTogglePlay = onCapsuleTogglePlay,
                modifier = contentModifier
            )
        }
    } else {
        null
    }
    val firstTab = TopLevelRoute.entries.first()

    LinkedBottomDock(
        dockState = dockState,
        isHomeTab = currentTab == TopLevelRoute.HOME,
        searchEnabled = true,
        firstItemIcon = firstTab.icon,
        firstItemLabel = firstTab.label,
        navigationItemCount = TopLevelRoute.entries.size,
        onSearchClick = onNavigateToSearch,
        isScrolling = visibilityController.hidden,
        modifier = modifier
            .onSizeChanged { hiddenDistancePx = it.height.toFloat() }
            // 实测悬浮导航栏遮挡屏幕的比例，供无缝动画 BUNDLE_LIST_COVER 使用。
            // 遮挡高度就是 chrome 自身那一段（外层尺寸另含 Dock 的上下内边距）；
            // 隐藏动画只是 graphicsLayer 平移，布局位置不变，因此常驻遮挡区恒定。
            .onGloballyPositioned { coords ->
                val windowHeight = coords.parentLayoutCoordinates
                    ?.size?.height ?: return@onGloballyPositioned
                if (windowHeight <= 0) return@onGloballyPositioned
                val chromeHeightPx = (coords.size.height - dockVerticalPaddingPx * 2f).coerceAtLeast(0f)
                onOcclusionChanged(
                    (chromeHeightPx / windowHeight).coerceIn(0f, 1f)
                )
            }
            .graphicsLayer {
                // 动画值在图层块内延迟读取：隐藏/显示过渡每帧只更新图层属性，
                // 不按帧重组调用方（组合期读值 → 每个滚动 tick 都重组一次）
                translationY = animatedOffsetPx
            },
        navigationContent = {
            if (isIOS) {
                // iOS 分段导航：**静态选中胶囊**，不再用滑动的"圈圈"指示器。
                //
                // 为什么去掉滑动指示器：它需要"指示器几何"与"图标几何"两套坐标严丝合缝，
                // 稍有偏差就不同心（此前 offset 与 padding 叠加导致每格错位 4dp），
                // 且切换时的位移/回弹在底栏这个尺度上读作"抖一下"。它带来的信息量
                // （当前在哪个 Tab）完全可以由**选中项自身的底色 + 更重的字重**表达，
                // 不需要一个独立移动的图层。去掉后：零错位可能、零跳动、层级反而更清楚。
                val entries = TopLevelRoute.entries
                val itemSize = DockNavItemWidth
                val innerPad = DockNavInnerPadding
                Row(modifier = Modifier.padding(innerPad)) {
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
                            // 选中底色：与 Dock 同语汇的大圆角胶囊，只覆盖图标本体
                            // （inset 4dp），不占满整格，视觉上更轻。
                            Box(
                                modifier = Modifier
                                    .size(itemSize - innerPad * 2)
                                    .clip(RoundedCornerShape(BiliRadius.dockChip))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    ),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TopLevelRoute.entries.forEach { tab ->
                        TopLevelFloatingNavigationItem(
                            tab = tab,
                            selected = currentTab == tab,
                            onClick = { onTabChange(tab) }
                        )
                    }
                }
            }
        },
        nowPlayingContent = nowPlayingContent,
        // 小横条**全程保持渲染**（详情页打开期间也不隐藏）。
        //
        // 为什么不能隐藏：系统 morph 的是锚点这块 View 的**实时像素**。锚点是小横条
        // 内一个零绘制透明 View，一旦小横条内容被 alpha 隐藏，系统在返回动画里就取到
        // 一块空 View——表现为"缩回途中是黑的，然后内容闪一下、跳一下"。
        // 打开方向没这个问题，因为那时锚点被 SeamlessCardOpener 用封面位图装饰过。
        // 详情页是全屏不透明 Activity，小横条在其背后本来看不见，保持渲染零副作用。
        nowPlayingVisible = true
    )
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
