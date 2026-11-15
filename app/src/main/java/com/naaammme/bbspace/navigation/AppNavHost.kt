package com.naaammme.bbspace.navigation

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.naaammme.bbspace.core.designsystem.component.roundScreenSafePadding
import com.naaammme.bbspace.core.designsystem.seamless.LocalBottomNavOcclusionRatio
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.LocalStoryCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessCardOpener
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
import com.naaammme.bbspace.core.designsystem.motion.rememberDampedDragState
import com.naaammme.bbspace.core.designsystem.motion.dampedHorizontalDrag
import com.naaammme.bbspace.core.designsystem.motion.LiquidIndicatorMotion
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
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
import com.naaammme.bbspace.core.model.VideoTarget

import com.naaammme.bbspace.core.model.WebLinkTarget
import com.naaammme.bbspace.core.common.log.DiagnosticLog
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
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MAIN_ROUTE = "main"
private val topLevelNavEdgePadding = 22.dp
private val topLevelNavScrollThreshold = 72.dp

// 联动底栏（官方规格）：滚动切态阈值 24dp（Dock 内边距见 LinkedBottomDock 的 Dock*Padding）
private val linkedDockScrollThreshold = 24.dp
@Composable
fun AppNavHost(
    themeConfig: ThemeConfig = ThemeConfig(),
    appLink: AppLinkRequest? = null,
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
    val closeVideoHost: () -> Unit = {
        playbackHostViewModel.close()
    }
    /**
     * 收起展开的播放页（返回键 / 页内导航共用）。
     *
     * 关键区分：`capsuleState.activateForVideoTarget()` 只回答"是否由胶囊接管播放"
     * （目标是 Video 即返回 true），**不等于页面已收起**。早先把它的返回值当成收起完成，
     * 于是视频目标下 `closeVideoHost()` 成了不可达分支、返回键被整条吞掉 —— 真机复现：
     * 切后台几分钟后系统回收 VideoDetailActivity，回前台由自愈路径展开覆盖层，
     * 此后无论如何按返回都回不到推荐页。现在两条路径都**显式收起**。
     */
    val dismissPlaybackHost: () -> Unit = {
        if (playbackHostViewModel.capsuleState.activateForVideoTarget()) {
            // 胶囊承接播放：收起页面、保留会话；collapseToCapsule 内部会标记
            // 「用户主动收起」，阻止 MainActivity 的自愈逻辑把页面再拉起来。
            playbackHostViewModel.collapseToCapsule()
        } else {
            when (playbackHostViewModel.currentTarget.value) {
                is StreamPlaybackTarget.Video -> closeVideoHost()
                is StreamPlaybackTarget.Live, null -> playbackHostViewModel.close()
            }
        }
    }
    val collapseExpandedPlayback = {
        if (hostMode == PlaybackHostMode.Expanded) {
            // 胶囊承接播放（仅视频）；直播/空目标直接关停。两种情况都必须真的收起页面
            // —— 此前用局部 forcedDismissMode 假装收起，而 hostMode 永远停在 Expanded，
            // 导致该 hack 无法复位：之后点小横条再 expand() 也是空操作，视频页打不开。
            if (playbackHostViewModel.capsuleState.activateForVideoTarget()) {
                playbackHostViewModel.collapseToCapsule()
            } else {
                playbackHostViewModel.close()
            }
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
    // ── 刷视频模式（首页顶栏入口）────────────────────────────────────────────
    // 与卡片路径同链路，但 intent 带 EXTRA_STORY_MODE：详情 Activity 直接以
    // 全屏上下滑呈现（story 流为队列数据源）。
    val openStoryActivity: (VideoTarget, String?, Boolean, Bundle?) -> Unit =
        { target, coverUrl, coverLarge, bundle ->
            val intent = videoDetailIntent(
                context = context,
                target = target,
                coverUrl = coverUrl,
                coverLarge = coverLarge,
                fromSeamless = bundle != null,
                storyMode = true
            )
            if (bundle != null) {
                context.startActivity(intent, bundle)
            } else {
                context.startActivity(intent)
            }
        }
    val openStoryRoute: (VideoTarget, String?, Boolean, Bundle?) -> Unit =
        { target, coverUrl, coverLarge, bundle ->
            if (OplusViewSeamlessCompat.isVersionSupported() && seamlessEnabledState.value) {
                playbackHostViewModel.warmOpen(target)
                openStoryActivity(target, coverUrl, coverLarge, bundle)
            } else {
                // 不支持无缝的设备：降级为普通详情打开（用户可再下滑进全屏刷）
                playbackHostViewModel.capsuleState.deactivate()
                playbackHostViewModel.expand()
                videoViewModel.openRoot(target)
            }
        }
    val openStorySeamless: (VideoTarget, String, Boolean, Bundle) -> Unit =
        { target, coverUrl, coverLarge, bundle ->
            openStoryRoute(target, coverUrl, coverLarge, bundle)
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
    // 刷视频模式的独立执行器：route 打开带 EXTRA_STORY_MODE 的 intent，
    // 其余（锚点装饰/PixelCopy/清理）与卡片执行器同一套契约
    val storyCardOpener = remember(seamlessCleanupScope) {
        SeamlessCardOpener(
            route = { target, coverUrl, coverLarge, bundle ->
                openStorySeamless(target, coverUrl ?: "", coverLarge, bundle)
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
    // 诊断：这一版 NavHost 实际装配的过渡（真机反馈"退出动画调不动"时，先看这里）
    // Q-3：原先是裸 `android.util.Log.i`（release 常开），已统一收口到 DiagnosticLog
    // （由「设置 → 性能 → 现场诊断日志」控制；debug 包始终可见）。
    LaunchedEffect(themeConfig.transitionStyle, themeConfig.animationSpeed) {
        DiagnosticLog.i(
            "NavTransitions",
            "installed style=${themeConfig.transitionStyle} speed=${themeConfig.animationSpeed}"
        )
    }

    // （原 forcedDismissMode 一次性 hack 已删除：它靠"假装收起"绕过 hostMode，
    //  但 hostMode 不离开 Expanded 就永不复位，反而把小横条重新展开也挡死了。
    //  现在收起一律走 PlaybackHostState.collapseToCapsule/close，hostMode 是唯一真值。）

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
        val request = appLink ?: return@LaunchedEffect
        when (val target = request.target) {
            is WebLinkTarget.ToVideo -> openVideo(target.target)
            is WebLinkTarget.ToSpace -> rootNavController.navigateToSpace(
                SpaceRoute(mid = target.mid)
            )
            is WebLinkTarget.ToLive -> openLive(
                LiveRoute(roomId = target.roomId)
            )
            // F-2：专栏 / 图文动态链接进原生内容页（复用 openArticle：播放继续 → 胶囊承接）
            is WebLinkTarget.ToDynamic -> openArticle(target.opusId.toString(), target.opusType)
            // L-1：本应用声明接管、但站内路由表达不出来的链接 —— 交回系统（排除自己），
            // 不能再像修复前那样和 `Stay` 一起被丢掉（用户看到的是"应用装死"）。
            is WebLinkTarget.External -> context.openExternalLink(request.url)
            is WebLinkTarget.Stay -> Unit
        }
        onAppLinkConsumed()
    }

    CompositionLocalProvider(
        LocalSeamlessCardOpener provides seamlessCardOpener,
        LocalStoryCardOpener provides storyCardOpener,
        LocalBottomNavOcclusionRatio provides { bottomNavOcclusionRatio }
    ) {
        // ── 返回键：应用自己兜住"回到上一层" ──────────────────────────────────
        //
        // ## 为什么必须自己注册（真机故障根因，2026-09-28）
        //
        // `navigation-compose` 升到 **2.10.1** 后，NavHost 的返回处理从
        // `OnBackPressedDispatcher` 换成了 `androidx.navigationevent`
        // （javap 实证：2.5.1 的 `NavHostKt` 里有 `setOnBackPressedDispatcher` /
        // `enableOnBackPressed`；2.10.1 里改成 `NavHostEventHandler :
        // NavigationEventHandler`，启用判据是它自己那条
        // `composeNavigator.backStack.size > 1`，且经 `LifecycleStartEffect` 写入、
        // `onStopOrDispose` 复位）。
        //
        // 而 activity 1.13.0 侧：`OnBackInvokedInput.updateBackInvokedCallbackState()`
        // 只在**存在 enabled handler** 时才 `registerOnBackInvokedCallback`。两者叠加
        // 的结果是：只要没有 enabled handler，平台就反注册系统回调、回到遗留路径
        // `Activity.onBackPressed()` → `finishAfterTransition()` —— **整个 Activity 被关掉**。
        //
        // 用户看到的就是：「退出动画一直是那个缩放动画、调过渡动画不生效」——
        // 那不是应用内转场，而是**系统关闭任务的窗口动画**；打开走
        // NavHost 的 enterTransition、退出走系统，两者永远不可能对称。
        //
        // ## 为什么注册在 NavHost **之前**
        //
        // navigationevent 的处理器 Deque 用 `addFirst` 插入、`resolveEnabledHandler`
        // 取"第一个 enabled"（javap 实证）→ **后注册者优先级更高**。放在 NavHost
        // 之前，优先级天然是：
        //   1. 目的地内部的 BackHandler（评论面板 / 搜索 / 直播等，后注册）—— 它们要的
        //      "先关面板再弹栈"语义完全不受影响；
        //   2. NavHost 自己的 `NavHostEventHandler`（带预测式返回的跟手预览）；
        //   3. 本处理器 —— 只在 2 失效时兜底（生命周期竞态 / 进程恢复后
        //      `ComposeNavigator.backStack` 与 NavController 失步）。
        // 放在 NavHost 之后会把它顶到最高优先级，抢掉预测式预览与面板优先级。
        val rootBackStack by rootNavController.currentBackStack.collectAsStateWithLifecycle()
        val canPopRoot by remember(rootBackStack) {
            // 与 `NavController.updateOnBackPressedCallbackEnabled()` 同判据：
            // 只数真实目的地，NavGraph 根不算（否则停在首页时会把返回键吃掉、
            // 用户退不出 App）。
            derivedStateOf { rootBackStack.count { it.destination !is NavGraph } > 1 }
        }
        BackHandler(enabled = canPopRoot) {
            rootNavController.popBackStack()
        }
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
            popExitTransition = { transitions.popExit(this) },
            // 预测式返回（手势跟手预览）也走同一套 pop 过渡：不接这两个参数时
            // 2.10.1 会退回它内置的默认值，既与设置风格不一致、也让"返回到底走没走
            // NavHost"这件事在日志上不可见（排查时踩过）。
            // 与上面四个参数同为**带接收者的 lambda**，多出来的那一个是 swipeEdge。
            predictivePopEnterTransition = { _ -> transitions.popEnter(this) },
            predictivePopExitTransition = { _ -> transitions.popExit(this) }
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
                    onEnterStoryMode = { target -> openStoryRoute(target, null, false, null) },
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
            mode = hostMode,
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
    onEnterStoryMode: (VideoTarget) -> Unit,
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
        //
        // FL-08 结论（**不改，只留结论**）：Crossfade 期间"双树并存"的成本已被核对
        // 可接受 —— HorizontalPager 默认 beyondViewportPageCount = 0（各页按
        // LaunchedEffect(isActive) 懒加载），切 Tab 时**不会**同时组合两个 Tab 的
        // 完整内容；200ms 窗口内实际并存的是离场树的最后状态与入场树的首帧骨架。
        // 换成 AnimatedContent 只改过渡实现、不减组合量，还要重验滚动状态衔接 → 不做。
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
                        onOpenListenItem = onNavigateToListenDetail,
                        onEnterStoryMode = onEnterStoryMode
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
    // Dock 的**展开态**几何缓存：[0]=展开态整体高度、[1]=展开态 chrome 高度、
    // [2]=上次实测时的窗口高度。
    //
    // 用普通 FloatArray 而不是 mutableStateOf —— 这两个值只在布局回调里读写，
    // 一旦被组合观察反而就退化成我们要修的"每帧写 → 每帧重组"。
    val expandedDockGeometryPx = remember { floatArrayOf(0f, 0f, 0f) }
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
            .onSizeChanged { size ->
                // 收起位移取**展开态**高度，不是当前高度。
                //
                // Dock 与播放胶囊合并时布局高度每帧都在变
                // （LinkedDockPolicy：height = top + barHeight，
                //   top = (barHeight + verticalGap) * (1 - merge)），
                // 于是原来的"每帧直接赋值"= 每帧写一次 mutableFloatStateOf →
                // 每帧重算下面 animateFloatAsState 的 targetValue → 280ms 里
                // 每帧重组一次本 composable 的宿主 scope。
                // 取历史最大值既是展开态高度（merge 只会让它变小），又让写入收敛到
                // 稳态零写入。位移偏大是安全的：多平移一段只会更彻底地离开屏幕。
                val height = size.height.toFloat()
                if (height > expandedDockGeometryPx[0]) {
                    expandedDockGeometryPx[0] = height
                    hiddenDistancePx = height
                }
            }
            // 实测悬浮导航栏遮挡屏幕的比例，供无缝动画 BUNDLE_LIST_COVER 使用。
            // 遮挡高度就是 chrome 自身那一段（外层尺寸另含 Dock 的上下内边距）。
            //
            // **只在"读到更大的 chrome 高度"或"窗口高度变了"时下发**：
            // 合并动画期间 `coords.size.height` 每帧都在变小，按当前值写就变成
            // 每帧一次 onOcclusionChanged → 每帧一次组合状态写入。历史最大值
            // 就是展开态遮挡高度（merge 只会缩小它），所以取最大值既正确又让写入收敛。
            // 原注释说的"常驻遮挡区恒定"只对**隐藏**动画成立（那是 graphicsLayer
            // 平移、布局不变），对合并动画不成立 —— 布局高度确实在变。
            .onGloballyPositioned { coords ->
                val windowHeight = coords.parentLayoutCoordinates
                    ?.size?.height ?: return@onGloballyPositioned
                if (windowHeight <= 0) return@onGloballyPositioned
                val chromeHeightPx = (coords.size.height - dockVerticalPaddingPx * 2f).coerceAtLeast(0f)
                val windowChanged = windowHeight.toFloat() != expandedDockGeometryPx[2]
                if (!windowChanged && chromeHeightPx <= expandedDockGeometryPx[1]) {
                    return@onGloballyPositioned
                }
                expandedDockGeometryPx[2] = windowHeight.toFloat()
                expandedDockGeometryPx[1] = chromeHeightPx
                onOcclusionChanged((chromeHeightPx / windowHeight).coerceIn(0f, 1f))
            }
            .graphicsLayer {
                // 动画值在图层块内延迟读取：隐藏/显示过渡每帧只更新图层属性，
                // 不按帧重组调用方（组合期读值 → 每个滚动 tick 都重组一次）
                translationY = animatedOffsetPx
            },
        navigationContent = {
            if (isIOS) {
                // iOS 分段导航：**滑动指示器 + 横向拖动切 Tab**（2026-10-18 W6 重做）。
                //
                // ## 为什么推翻"静态胶囊"的旧决定
                // 旧实现去掉滑动指示器有两条理由，现在都成立地解决了：
                // ① "指示器与图标两套坐标容易错位（每格错位 4dp）" —— 旧实现是每个 Tab 各自
                //    画一层底色、再用 offset 去凑；新实现把指示器放在**与图标同一个 Row 起点**
                //    上，x = index * itemSize + innerPad，**和图标格共用同一套测量**，不存在第二套几何；
                // ② "切换时的位移读作抖一下" —— 现在位移曲线来自 `LiquidIndicatorMotion.positionSpec()`
                //    （spring、临界阻尼），并叠加"拖动距离越大越拉长"的液滴形变，读作"有惯性"而不是"抖动"。
                // 用户明确要求这一项（"实现 bilipai 的控件拖拽动画"），所以重做而不是永久搁置。
                //
                // ## 手势
                // 底栏整块接 `dampedHorizontalDrag`：只消费**水平**位移（纵向留给列表滚动），
                // 拖动时指示器阻尼跟手（0.55）并越界橡皮筋，松手按 lattice 吸附到最近一档
                // **并直接切 Tab**（不是等动画结束再看落在哪 —— 那样晚一帧且会在过路格位上误触发）。
                val entries = TopLevelRoute.entries
                val itemSize = DockNavItemWidth
                val innerPad = DockNavInnerPadding
                val density = LocalDensity.current
                val animations = LocalAnimations.current
                // 跟手时直接 snapTo 绝对位置需要协程作用域（snapTo 是 suspend）。
                val dockScope = rememberCoroutineScope()
                val selectedIndex = entries.indexOf(currentTab).coerceAtLeast(0)
                val pitchPx = with(density) { itemSize.toPx() }
                val innerPadPx = with(density) { innerPad.toPx() }
                val basePx = pitchPx * selectedIndex
                // 边界**非对称**：首档不许往左拖（min=0）、尾档不许往右拖（max=0）、
                // 中间档两侧各 1 格（一次最多跨一档，防"一甩到底"）。
                val dragMinPx = if (selectedIndex == 0) 0f else -pitchPx
                val dragMaxPx = if (selectedIndex == entries.lastIndex) 0f else pitchPx
                val dragState = rememberDampedDragState(
                    minOffsetPx = dragMinPx,
                    maxOffsetPx = dragMaxPx,
                    latticeStepPx = pitchPx
                )
                // 这次 Tab 变化是"拖出来的"还是"点出来的"。
                var switchedByDrag by remember { mutableStateOf(false) }
                //
                // ## ⚠️ 唯一状态源：指示器位置 = `indicatorX`（含拖拽残余）
                //
                // 早期实现是「基点 Animatable + 拖拽残余 Animatable」在 `offset{}` 里**相加**。
                // 那是错的：两者各自 `scope.launch` 异步更新，切页时基点 snapTo、残余清零
                // 不在同一帧生效 ⇒ **必然出现一帧不同步的跳变**。这就是用户看到的"错位/bug"。
                //
                // 现在只保留一个 `indicatorX`，它是**绝对位置**（px）：
                // - 点 Tab：spring 到目标位置；
                // - 拖拽：手指位移直接 snapTo 到绝对位置（跟手）；
                // - 松手：spring 到吸附位置（带过冲回弹）；
                // - 切页：目标位置换成新档位，**同一个 Animatable** 自然滑过去 —— 不可能错位。
                val indicatorX = remember { Animatable(basePx) }
                LaunchedEffect(basePx, animations.reduceMotion) {
                    // 拖拽切页时手指已经把指示器带到了新位置附近，直接 spring 收尾即可；
                    // 点 Tab 时从旧位置 spring 过去。两者都走同一个 Animatable。
                    if (animations.reduceMotion) {
                        indicatorX.snapTo(basePx)
                    } else {
                        indicatorX.animateTo(
                            targetValue = basePx,
                            animationSpec = if (switchedByDrag) {
                                LiquidIndicatorMotion.releaseSpec()
                            } else {
                                LiquidIndicatorMotion.positionSpec()
                            }
                        )
                    }
                    switchedByDrag = false
                }
                Box(
                    modifier = Modifier
                        .padding(innerPad)
                        .dampedHorizontalDrag(
                            state = dragState,
                            // 基准位置每帧现算：切页后 selectedIndex 变了，基准跟着变。
                            basePositionPx = { pitchPx * (entries.indexOf(currentTab).coerceAtLeast(0)) },
                            // 跟手：**直接 snapTo 绝对位置**（单一状态源 ⇒ 结构上不可能错位）。
                            onAbsolutePosition = { absolutePx ->
                                dockScope.launch { indicatorX.snapTo(absolutePx) }
                            },
                            onSettle = { absolutePx ->
                                // 吸附位置落在哪一档？
                                val next = (absolutePx / pitchPx).roundToInt()
                                    .coerceIn(0, entries.lastIndex)
                                if (next != selectedIndex) {
                                    // 切页：让 basePx 的 LaunchedEffect 用 releaseSpec 收尾
                                    // （带过冲回弹），这里只负责触发导航。
                                    switchedByDrag = true
                                    onTabChange(entries[next])
                                    true
                                } else {
                                    // 没换档：spring 回原档位（带弹性晃动）。
                                    dockScope.launch {
                                        indicatorX.animateTo(
                                            targetValue = absolutePx,
                                            animationSpec = LiquidIndicatorMotion.releaseSpec()
                                        )
                                    }
                                    true
                                }
                            }
                        )
                ) {
                    // 指示器：在 `offset { }`（布局期）与 `graphicsLayer { }`（绘制期）读动画值，
                    // 拖动中每帧只改变换、不重组底栏。
                    //
                    // ## 几何（错位的根因就在这两行）
                    // 图标格是 `itemSize`(48dp) 宽，而指示器只有 `itemSize - innerPad*2`(40dp)。
                    // 它要在格内居中 ⇒ x 必须额外加 `innerPad`，即
                    //   x = index * pitch + innerPad + drag
                    // 旧实现只写了 `index * pitch + drag` ⇒ **每格向左偏 4dp**，
                    // 这正是文件上方旧注释警告过的"每格错位 4dp"。现在补上。
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = (indicatorX.value + innerPadPx).roundToInt(),
                                    y = innerPadPx.roundToInt()
                                )
                            }
                            .graphicsLayer {
                                // ① 按压膨胀：手指压上去时元素"被挤出来"（1 → 1.18），
                                //    与本仓按钮族的"缩小"相反 —— 液态物件的物理语言。
                                //    膨胀进度由 `dragState.pressProgress` 驱动：**按下即开始**
                                //    （onPressDown 在 awaitFirstDown 后立刻调用），抬手即收回
                                //    （onPressUp 在 finally 里调用，任何退出路径都覆盖）。
                                // ② 拖拽拉伸：按**受力方向**形变 —— 横向拉长、纵向同步变细。
                                //    旧实现只放大 X、Y 跟着一起等比放大，视觉上是"整体变大"
                                //    而不是"被拉长"，用户要的拉伸感因此不存在。
                                //    形变公式收在 `LiquidIndicatorMotion.dragStretch()`（纯函数、可单测）。
                                val press = dragState.pressProgress.value
                                val pressScale = 1f + (LiquidIndicatorMotion.PressedScale - 1f) * press
                                // 偏离档位基准的距离，以"一格"为单位归一到 0..1。
                                val dragRatio = (
                                    (indicatorX.value - pitchPx * selectedIndex).absoluteValue / pitchPx
                                    ).coerceIn(0f, 1f)
                                val (stretchX, squashY) = LiquidIndicatorMotion.dragStretch(dragRatio)
                                scaleX = pressScale * stretchX
                                scaleY = pressScale * squashY
                            }
                            .size(itemSize - innerPad * 2)
                            .clip(RoundedCornerShape(BiliRadius.dockChip))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    )
                    Row {
                        entries.forEach { tab ->
                            val selected = tab == currentTab
                            Box(
                                modifier = Modifier
                                    .size(itemSize)
                                    // 全局拖拽反馈（上游 BiliPai `AppElasticPress`）：
                                    // 按下即鼓起，按住左右拖动时图标跟着手指沿受力方向拉伸。
                                    //
                                    // ## 为什么挂在**图标格**而不是整条指示器上
                                    // 指示器自己已有 `dampedHorizontalDrag` 的液态形变
                                    // （按压膨胀 + 横向拉伸，见上方 graphicsLayer），
                                    // 两套形变叠在一起会互相放大成"果冻乱抖"。
                                    // 这里只给**被按的那一格图标**加反馈，两套动效分工：
                                    // 指示器 = 液态块被拖动；图标格 = 手指压到的那个按钮。
                                    //
                                    // `dragEnabled` 保持默认 true：它会被用户级开关
                                    // `globalDragFeedbackEnabled` 再门控一次（关闭后只剩按压）。
                                    .appElasticPress()
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
        nowPlayingVisible = true,
                    nowPlayingDissolving = capsuleState.dissolving
    )
}

@Composable
private fun TopLevelFloatingNavigationItem(
    tab: TopLevelRoute,
    selected: Boolean,
    onClick: () -> Unit
) {
    // 全局拖拽反馈（第 34 轮 T2）：浮条导航的每颗图标都是独立可点件。
    //
    // 这里与「液态 dock」分支（同一文件 `dock` 段的 `.appElasticPress()`）同口径：
    // 液态块的形变属于指示器，图标格的形变属于按钮，两套各管各的，不叠在同一元素上。
    if (selected) {
        FilledIconButton(onClick = onClick, modifier = Modifier.appElasticPress()) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label
            )
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.appElasticPress()) {
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
