package com.naaammme.bbspace.playback

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition.Companion.None as ExitNone
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.naaammme.bbspace.core.designsystem.seamless.CoverExpandGhost
import com.naaammme.bbspace.core.designsystem.seamless.CoverExpandOrigin
import com.naaammme.bbspace.core.designsystem.seamless.sharedContentKey
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.feature.live.LiveScreen
import com.naaammme.bbspace.feature.live.LiveViewModel
import com.naaammme.bbspace.feature.video.VideoScreen
import com.naaammme.bbspace.feature.video.VideoViewModel

@OptIn(UnstableApi::class)
@Composable
fun PlaybackHost(
    mode: PlaybackHostMode,
    playbackHostViewModel: PlaybackHostViewModel,
    onDismissExpanded: () -> Unit,
    onGoHome: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenDownloadCache: () -> Unit,
    onStartDownload: (VideoDownloadRequest) -> Unit,
    videoViewModel: VideoViewModel,
    liveViewModel: LiveViewModel,
    modifier: Modifier = Modifier
) {
    val procOwner = remember { ProcessLifecycleOwner.get() }
    val target by playbackHostViewModel.currentTarget.collectAsStateWithLifecycle()
    val backgroundPlaybackEnabled by playbackHostViewModel.backgroundPlaybackEnabled.collectAsStateWithLifecycle()
    DisposableEffect(procOwner, target, backgroundPlaybackEnabled) {
        if (target == null || backgroundPlaybackEnabled) {
            return@DisposableEffect onDispose { }
        }
        val lifecycle = procOwner.lifecycle
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playbackHostViewModel.onEnterBackground()
                Lifecycle.Event.ON_START -> playbackHostViewModel.onReturnForeground()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
        }
    }
    val videoTarget = (target as? StreamPlaybackTarget.Video)?.target
    // 动效统一走主题 token（OPPO 动效规范：页面级入场 = 长时长 + 强调曲线）。
    // 详情本体在封面幽灵下方普通淡入；morph 运动由 CoverExpandGhost 承载。
    val anim = LocalAnimations.current
    val videoEnterSpec =
        fadeIn(animationSpec = tween(durationMillis = anim.long, easing = anim.emphasized))
    val enterSpec =
        slideInHorizontally(
            animationSpec = tween(durationMillis = anim.long, easing = anim.emphasized)
        ) { fullWidth -> fullWidth }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = videoTarget != null && mode == PlaybackHostMode.Expanded,
            enter = videoEnterSpec,
            exit = ExitNone
        ) {
            VideoScreen(
                onBack = onDismissExpanded,
                onGoHome = onGoHome,
                onOpenSpace = onOpenSpace,
                onOpenDownloadCache = onOpenDownloadCache,
                onStartDownload = onStartDownload,
                viewModel = videoViewModel,
                hostExpanded = true
            )
        }

        AnimatedVisibility(
            visible = target is StreamPlaybackTarget.Live && mode == PlaybackHostMode.Expanded,
            enter = enterSpec,
            exit = ExitNone
        ) {
            LiveScreen(
                onBack = onDismissExpanded,
                viewModel = liveViewModel,
                hostExpanded = true
            )
        }

        // 封面展开幽灵：仅当本次展开来自首页卡片点击且与当前视频匹配时出现，
        // morph 结束后自行淡出并清除来源记录；展开中断（返回）时立即清除。
        val expandRecord = CoverExpandOrigin.pending
        if (expandRecord != null &&
            mode == PlaybackHostMode.Expanded &&
            expandRecord.key == videoTarget?.sharedContentKey()
        ) {
            CoverExpandGhost(
                record = expandRecord,
                onFinished = { CoverExpandOrigin.clear() }
            )
        }
        LaunchedEffect(mode) {
            if (mode != PlaybackHostMode.Expanded) {
                CoverExpandOrigin.clear()
            }
        }

    }
}
