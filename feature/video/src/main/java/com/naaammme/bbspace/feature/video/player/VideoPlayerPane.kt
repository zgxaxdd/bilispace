package com.naaammme.bbspace.feature.video.player

import android.media.AudioManager
import android.os.BatteryManager
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.R as Media3UiR
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.naaammme.bbspace.infra.player.danmaku.DanmakuLayer
import com.naaammme.bbspace.infra.player.danmaku.DanmakuOverlayState
import com.naaammme.bbspace.infra.player.danmaku.rememberDanmakuOverlayState
import com.naaammme.bbspace.infra.player.PlayerViewTargetBinder
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.feature.video.R
import com.naaammme.bbspace.feature.video.SponsorBlockUiState
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.formatPlaybackTime
import com.naaammme.bbspace.feature.video.formatSpeed
import com.naaammme.bbspace.feature.video.getAudioName
import com.naaammme.bbspace.feature.video.getQualityName
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt


internal enum class PlayerVideoResizeMode {
    Fit,
    Zoom,
    Fill
}

private enum class PlayerDialog {
    Quality,
    Audio,
    Speed
}

// —— 播放器浮层视觉规范 ——
// 浮层（顶栏/控制条/胶囊/手势气泡）直接叠在视频画面上，与主题色解耦：
// 统一「黑遮罩 + 白前景」语言，遮罩透明度按可读性校准，保证亮画面上白字仍清晰，
// 深/浅色主题下表现一致。改动时请维持整套 token 同步，勿在单处硬编码。

private val PlayerOverlayScrimTopEnd = Color.Black.copy(alpha = 0.66f)
private val PlayerOverlayScrimBottomEnd = Color.Black.copy(alpha = 0.76f)

/**
 * 控制条顶部渐隐的**中间步**。它不是"另一个遮罩"：控制条上缘用
 * 透明 → 本值 → [PlayerOverlayScrimBottomEnd] 的三段渐变，让控制条与画面衔接更柔和。
 * 单独成 token 是因为它是渐变曲线上的一个定点，与两端语义不同。
 */
private val PlayerOverlayScrimMid = Color.Black.copy(alpha = 0.26f)
private val PlayerPillScrim = Color.Black.copy(alpha = 0.30f)
private val PlayerPillBorder = Color.White.copy(alpha = 0.16f)
private val PlayerPillTextDim = Color.White.copy(alpha = 0.68f)
private val PlayerSecondaryText = Color.White.copy(alpha = 0.9f)

// —— 以下为本轮补全的 token ——
// 此前这些值以裸字面量散落在各处（方案 P1-4 指出"浮层只做了半程 token 化"）。
// 补全的意义：透明度的可读性校准值集中成组，后续统一调整不必再全局搜替换。
// 注意全部维持「黑遮罩 + 白前景」语言，**不要**改成主题色（本文件顶部约定）。

/** 大播放按钮的圆形底盘。比控制条遮罩更透（按钮叠在画面中央，过重会挡住内容）。 */
private val PlayerPlayButtonScrim = Color.Black.copy(alpha = 0.55f)

/** 大播放按钮的描边。比 [PlayerPillBorder] 略亮：圆形描边在亮画面上更易被"吃掉"。 */
private val PlayerPlayButtonBorder = Color.White.copy(alpha = 0.18f)

/** 手势气泡 / 提示胶囊的底盘（深底保证白字在任意画面上可读）。 */
private val PlayerToastScrim = Color.Black.copy(alpha = 0.72f)

/** 进度条当前轨道。 */
private val PlayerSliderActiveTrack = Color.White.copy(alpha = 0.24f)

/** 进度条未激活轨道。 */
private val PlayerSliderInactiveTrack = Color.White.copy(alpha = 0.24f)

/** 进度条停用态轨道。 */
private val PlayerSliderDisabledTrack = Color.White.copy(alpha = 0.1f)

/** 进度条停用态滑块。 */
private val PlayerSliderDisabledThumb = Color.White.copy(alpha = 0.24f)

/** 控制条按钮选中态底盘。 */
private val PlayerCtrlButtonOn = Color.White.copy(alpha = 0.16f)

/** 控制条按钮未选中态底盘。 */
private val PlayerCtrlButtonOff = Color.White.copy(alpha = 0.10f)

/** 控制条按钮未选中态前景（0.72 而非 0.5：停用也要在亮画面上保持可读）。 */
private val PlayerCtrlButtonTextOff = Color.White.copy(alpha = 0.72f)


internal val LocalVideoResizeModeState = compositionLocalOf<MutableState<PlayerVideoResizeMode>> {
    error("Missing player video resize mode state")
}

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Composable
internal fun VideoPlayerPane(
    modifier: Modifier,
    viewModel: VideoViewModel,
    videoTitle: String?,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val state by viewModel.videoState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    var activeDialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var showPlaybackSheet by remember { mutableStateOf(false) }
    var showCtrl by remember { mutableStateOf(false) }
    val zoomState = remember { PlayerZoomState() }
    val videoResizeMode = rememberSaveable { mutableStateOf(PlayerVideoResizeMode.Fit) }
    val topMetaText = remember(showCtrl, isFull) {
        if (showCtrl && isFull) {
            readPlayerTopMetaText(context, timeFmt)
        } else {
            null
        }
    }
    val danmakuOn = settingsState.danmaku.enabled
    val danmakuOverlayState = if (danmakuOn) {
        rememberDanmakuOverlayState(
            initialConfig = settingsState.danmaku,
            initialPositionMs = viewModel.playbackProgress.value.positionMs,
            initialIsPlaying = state.isPlaying,
            initialSpeed = state.speed
        )
    } else {
        null
    }
    val videoAspect = remember(state.currentStream) {
        val width = state.currentStream?.width?.takeIf { it > 0 } ?: return@remember null
        val height = state.currentStream?.height?.takeIf { it > 0 } ?: return@remember null
        width.toFloat() / height.toFloat()
    }
    val resizeMode = remember(isFull, videoResizeMode.value) {
        if (!isFull) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            when (videoResizeMode.value) {
                PlayerVideoResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                PlayerVideoResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                PlayerVideoResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
    }
    val playerView = remember(context) {
        // TextureView（而非默认 SurfaceView）：Compose 圆角裁剪才能作用到视频画面
        (LayoutInflater.from(context).inflate(R.layout.view_bili_player, null, false) as PlayerView)
            .apply {
                useController = false
                setEnableComposeSurfaceSyncWorkaround(true)
                setKeepContentOnPlayerReset(true)
            }
    }
    var lastWarmAspect by remember(playerView) { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.playWhenReady) {
        playerView.keepScreenOn = state.playWhenReady
    }

    // 捏合缩放重置时机：退出全屏、切换视频（ids 变化）、切换清晰度/分P/剧集（currentStream 变化）
    LaunchedEffect(isFull) {
        if (!isFull) zoomState.reset()
    }
    LaunchedEffect(state.ids, state.currentStream) {
        zoomState.reset()
    }

    DisposableEffect(owner, playerView, player) {
        val lifecycle = owner.lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            PlayerViewTargetBinder.bind(playerView, player)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> PlayerViewTargetBinder.bind(playerView, player)
                Lifecycle.Event.ON_STOP -> PlayerViewTargetBinder.unbind(playerView)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            PlayerViewTargetBinder.unbind(playerView)
        }
    }

    CompositionLocalProvider(LocalVideoResizeModeState provides videoResizeMode) {
        // 非全屏时播放窗口 16dp 圆角；全屏保持全屏无边角
        val playerCornerShape = if (isFull) {
            RectangleShape
        } else {
            RoundedCornerShape(16.dp)
        }
        Box(
            modifier = modifier
                .clip(playerCornerShape)
                .background(Color.Black)
        ) {
            // 捏合缩放只作用于视频画面本体：graphicsLayer 挂在 AndroidView 上，
            // 播放器控件（顶栏/控制条/中央按钮）与弹幕层不跟随缩放；
            // Box 的 clip 在外层保证放大内容裁剪在播放视口内
            AndroidView(
                factory = { playerView },
                update = { view ->
                    val content = view.findViewById<AspectRatioFrameLayout>(Media3UiR.id.exo_content_frame)
                    if (content != null) {
                        content.resizeMode = resizeMode
                        if (lastWarmAspect != videoAspect) {
                            content.setAspectRatio(videoAspect ?: 0f)
                            lastWarmAspect = videoAspect
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomState.scale
                        scaleY = zoomState.scale
                        translationX = zoomState.offsetX
                        translationY = zoomState.offsetY
                    }
            )

            if (danmakuOverlayState != null) {
                VideoDanmakuLayer(
                    viewModel = viewModel,
                    state = state,
                    settingsState = settingsState,
                    danmakuOverlayState = danmakuOverlayState,
                    playerView = playerView
                )
            }

            VideoPlayerOverlay(
                viewModel = viewModel,
                state = state,
                player = player,
                settingsState = settingsState,
                isFull = isFull,
                zoomState = zoomState,
                showCtrl = showCtrl,
                activeDialog = activeDialog,
                showPlaybackSheet = showPlaybackSheet,
                onShowCtrlChange = { showCtrl = it },
                onShowA = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Audio
                },
                onShowQ = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Quality
                },
                onShowSp = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Speed
                },
                onToggleFull = {
                    showCtrl = true
                    onToggleFull()
                }
            )

            if (showCtrl) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    PlayerOverlayScrimTopEnd,
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                        if (!isFull) {
                            IconButton(onClick = onGoHome) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "首页",
                                    tint = Color.White
                                )
                            }
                        }
                        if (isFull) {
                            Column(modifier = Modifier.weight(1f)) {
                                videoTitle?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                topMetaText?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PlayerSecondaryText,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 全屏捏合放大后的快捷还原入口
                        if (isFull && zoomState.isZoomed) {
                            PlayerPillChip(
                                text = "还原",
                                showDot = false,
                                dimmed = false,
                                onClick = { zoomState.reset() }
                            )
                        }
                        PlayerPillChip(
                            text = if (danmakuOn) "弹幕" else "弹幕关",
                            showDot = danmakuOn,
                            dimmed = !danmakuOn,
                            onClick = {
                                showCtrl = true
                                viewModel.updateDanmaku(
                                    settingsState.danmaku.copy(enabled = !settingsState.danmaku.enabled)
                                )
                            }
                        )
                        IconButton(
                            onClick = {
                                showCtrl = true
                                showPlaybackSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多信息",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            if (isFull && showPlaybackSheet) {
                VideoPlaybackSidebar(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = { showPlaybackSheet = false },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxSize()
                )
            }
        }

        if (activeDialog == PlayerDialog.Quality) {
            val src = state.playbackSource
            if (src != null) {
                QualitySelectionDialog(
                    options = src.qualityOptions,
                    curQuality = state.currentStream?.quality,
                    onDismiss = { activeDialog = null },
                    onSelect = { quality ->
                        viewModel.switchQuality(quality)
                        activeDialog = null
                    }
                )
            }
        }

        if (activeDialog == PlayerDialog.Audio) {
            val src = state.playbackSource
            if (src != null) {
                AudioSelectionDialog(
                    audios = src.audios,
                    curAudioId = state.currentAudio?.id,
                    onDismiss = { activeDialog = null },
                    onSelect = { audioId ->
                        viewModel.switchAudio(audioId)
                        activeDialog = null
                    }
                )
            }
        }

        if (activeDialog == PlayerDialog.Speed) {
            SpeedSelectionDialog(
                curSpeed = state.speed,
                onDismiss = { activeDialog = null },
                onSelect = { speed ->
                    viewModel.setSpeed(speed)
                    activeDialog = null
                }
            )
        }

        if (!isFull && showPlaybackSheet) {
            VideoPlaybackSheet(
                state = state,
                viewModel = viewModel,
                limitUnderPlayer = true,
                onDismiss = { showPlaybackSheet = false }
            )
        }
    }
}

/** 双击点赞爱心动效：在点击位置放大、上浮并淡出 */

@Composable
private fun DoubleTapLikeHeart(
    position: Offset,
    token: Long,
    modifier: Modifier = Modifier
) {
    if (token == 0L) return
    var visible by remember(token) { mutableStateOf(true) }
    val scale = remember(token) { Animatable(0.4f) }
    val alpha = remember(token) { Animatable(1f) }
    val rise = remember(token) { Animatable(0f) }
    LaunchedEffect(token) {
        scale.animateTo(1.2f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
        rise.animateTo(1f, tween(durationMillis = 420))
        alpha.animateTo(0f, tween(durationMillis = 200))
        visible = false
    }
    if (!visible) return
    Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = "点赞",
        tint = LikeHeartColor,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                translationX = position.x - 28.dp.toPx()
                translationY = position.y - 28.dp.toPx() - rise.value * 56.dp.toPx()
            }
            .size(56.dp)
    )
}

private val LikeHeartColor = Color(0xFFFF6B8B)

@UnstableApi
@Composable
private fun VideoDanmakuLayer(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    settingsState: PlayerSettingsState,
    danmakuOverlayState: DanmakuOverlayState,
    playerView: PlayerView
) {
    val danmakuState by viewModel.danmakuState.collectAsStateWithLifecycle()

    DanmakuLayer(
        playerView = playerView,
        overlayState = danmakuOverlayState,
        danmakuState = danmakuState,
        danmakuConfig = settingsState.danmaku,
        positionMs = viewModel.playbackProgress.value.positionMs,
        isPlaying = state.isPlaying,
        speed = state.speed,
        seekEventId = state.seekEventId,
        hasSource = state.playbackSource != null
    )
}

@Composable
private fun VideoPlayerOverlay(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    settingsState: PlayerSettingsState,
    isFull: Boolean,
    zoomState: PlayerZoomState,
    showCtrl: Boolean,
    activeDialog: PlayerDialog?,
    showPlaybackSheet: Boolean,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onToggleFull: () -> Unit
) {
    val context = LocalContext.current
    val gestureState = remember { VideoGestureState() }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    var dragStartVolumeFrac by remember { mutableFloatStateOf(0f) }
    var speedBeforeGesture by remember { mutableFloatStateOf(1f) }
    var lastGestureBrightness by remember { mutableFloatStateOf(Float.NaN) }
    var lastGestureVolumeFrac by remember { mutableFloatStateOf(Float.NaN) }
    var heartToken by remember { mutableLongStateOf(0L) }
    var heartPos by remember { mutableStateOf(Offset.Zero) }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val act = LocalActivity.current

    LaunchedEffect(
        showCtrl,
        state.isPlaying,
        dragMs,
        gestureState.dragType,
        gestureState.showSpeedBadge,
        activeDialog,
        showPlaybackSheet
    ) {
        if (
            showCtrl &&
            state.isPlaying &&
            dragMs == null &&
            gestureState.dragType == DragType.None &&
            !gestureState.showSpeedBadge &&
            activeDialog == null &&
            !showPlaybackSheet
        ) {
            delay(2_000)
            onShowCtrlChange(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .videoGestures(
                    state = gestureState,
                    onToggleControls = { onShowCtrlChange(!showCtrl) },
                    onTogglePlay = viewModel::togglePlayPause,
                    onSeekTo = viewModel::seekTo,
                    onStartSpeedUp = {
                        speedBeforeGesture = state.speed
                        val speed = settingsState.playback.gestureSpeed
                        viewModel.setSpeed(speed)
                        formatSpeed(speed)
                    },
                    onStopSpeedUp = { viewModel.setSpeed(speedBeforeGesture) },
                    onBrightnessDelta = { deltaFrac ->
                        val next = (dragStartBrightness + deltaFrac).coerceIn(0.01f, 1f)
                        if (abs(next - lastGestureBrightness) >= 0.02f || lastGestureBrightness.isNaN()) {
                            adjustWindowBrightness(act, next)
                            lastGestureBrightness = next
                        }
                        next
                    },
                    onVolumeDelta = { deltaFrac ->
                        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                        if (maxVol <= 0) {
                            0f
                        } else {
                            val next = (dragStartVolumeFrac + deltaFrac).coerceIn(0f, 1f)
                            if (abs(next - lastGestureVolumeFrac) >= (1f / maxVol.toFloat()) || lastGestureVolumeFrac.isNaN()) {
                                val newVol = (next * maxVol).roundToInt()
                                adjustStreamVolume(audioManager, newVol)
                                lastGestureVolumeFrac = next
                            }
                            next
                        }
                    },
                    onDragStart = { dragType ->
                        when (dragType) {
                            DragType.Brightness -> {
                                dragStartBrightness = act?.window?.attributes?.screenBrightness
                                    ?.takeIf { it in 0f..1f }
                                    ?.coerceIn(0.01f, 1f)
                                    ?: 0.5f
                                lastGestureBrightness = Float.NaN
                            }
                            DragType.Volume -> {
                                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                                val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                dragStartVolumeFrac = if (maxVol > 0) {
                                    curVol.toFloat() / maxVol.toFloat()
                                } else {
                                    0f
                                }
                                lastGestureVolumeFrac = Float.NaN
                            }
                            else -> Unit
                        }
                    },
                    onDragEnd = { },
                    doubleTapLike = { settingsState.playback.doubleTapLike },
                    onDoubleTapLike = { pos ->
                        heartPos = pos
                        heartToken++
                        viewModel.toggleLike()
                    },
                    isPlaying = { state.isPlaying },
                    positionMs = {
                        val progress = viewModel.playbackProgress.value
                        dragMs
                            ?: gestureState.dragSeekPosMs
                            ?: progress.positionMs
                    },
                    durationMs = {
                        val progress = viewModel.playbackProgress.value
                        player?.duration
                            ?.takeIf { it > 0L }
                            ?: progress.durationMs.takeIf { it > 0L }
                            ?: state.playbackSource?.durationMs?.coerceAtLeast(0L)
                            ?: 0L
                    },
                    zoomActive = { zoomState.isZoomed }
                )
                // 链内側：Main pass 先于 videoGestures；双指/已放大时接管，其余放行
                .playerPinchZoom(enabled = isFull, state = zoomState)
        )

        VideoGestureFeedback(gestureState)

        DoubleTapLikeHeart(
            position = heartPos,
            token = heartToken,
            modifier = Modifier.fillMaxSize()
        )

        // 暂停时显示中央大播放键（现代播放器通用交互）
        if (!state.isPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(color = PlayerPlayButtonScrim, shape = CircleShape)
                    .border(1.dp, PlayerPlayButtonBorder, CircleShape)
                    .clickable { viewModel.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // 空降助手：手动跳过 / 精彩时刻按钮与跳过提示
        val sbState by viewModel.sbState.collectAsStateWithLifecycle()
        LaunchedEffect(sbState.toastMessage) {
            sbState.toastMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                viewModel.consumeSbToast()
            }
        }
        SponsorBlockOverlay(
            state = sbState,
            showCtrl = showCtrl,
            onSkip = viewModel::sbSkipManual,
            onDismiss = viewModel::sbDismissManual,
            onJumpPoi = viewModel::sbJumpPoi,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        if (showCtrl) {
            PlayerCtrlBarHost(
                viewModel = viewModel,
                state = state,
                player = player,
                isFull = isFull,
                dragMs = { dragMs },
                gestureSeekMs = gestureState.dragSeekPosMs,
                onDragMsChange = { dragMs = it },
                onShowCtrlChange = onShowCtrlChange,
                onShowA = onShowA,
                onShowQ = onShowQ,
                onShowSp = onShowSp,
                onToggleFull = onToggleFull,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun PlayerCtrlBarHost(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    isFull: Boolean,
    dragMs: () -> Long?,
    gestureSeekMs: Long?,
    onDragMsChange: (Long?) -> Unit,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onToggleFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val durationMs = player?.duration
        ?.takeIf { it > 0L }
        ?: progress.durationMs.takeIf { it > 0L }
        ?: state.playbackSource?.durationMs?.coerceAtLeast(0L)
        ?: 0L
    val dragSeekMs = dragMs()
    val barMs = dragSeekMs ?: gestureSeekMs ?: progress.positionMs
    val sliderVal = if (durationMs > 0L) {
        (barMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sbState by viewModel.sbState.collectAsStateWithLifecycle()
    val sbMarks = remember(sbState, durationMs) {
        if (!sbState.markersEnabled || durationMs <= 0L) {
            emptyList()
        } else {
            sbState.segments.map { seg ->
                val start = (seg.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val rawEnd = if (seg.endMs > seg.startMs) {
                    seg.endMs.toFloat() / durationMs
                } else {
                    (seg.startMs + 500L).toFloat() / durationMs
                }
                SbMark(start, rawEnd.coerceIn(0f, 1f), Color(seg.category.colorArgb))
            }
        }
    }

    PlayerCtrlBar(
        playText = if (state.isPlaying) "暂停" else "播放",
        timeText = formatPlaybackTime(barMs, durationMs),
        audioText = state.currentAudio?.let { getAudioName(it.id, short = true) } ?: "音频",
        qualityText = getQualityName(state.playbackSource, state.currentStream),
        speedText = formatSpeed(state.speed),
        fullText = if (isFull) "还原" else "全屏",
        sliderVal = sliderVal,
        sliderOn = durationMs > 0L,
        marks = sbMarks,
        audioOn = (state.playbackSource?.audios?.size ?: 0) > 1,
        qualityOn = (state.playbackSource?.qualityOptions?.size ?: 0) > 1,
        onTogglePlay = viewModel::togglePlayPause,
        onAudioClick = onShowA,
        onQualityClick = onShowQ,
        onSpeedClick = onShowSp,
        onFullClick = onToggleFull,
        onSeekChange = { frac ->
            onShowCtrlChange(true)
            onDragMsChange((durationMs * frac).toLong())
        },
        onSeekDone = {
            val next = dragMs() ?: return@PlayerCtrlBar
            viewModel.seekTo(next)
            onDragMsChange(null)
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerCtrlBar(
    playText: String,
    timeText: String,
    audioText: String,
    qualityText: String,
    speedText: String,
    fullText: String,
    sliderVal: Float,
    sliderOn: Boolean,
    marks: List<SbMark> = emptyList(),
    audioOn: Boolean,
    qualityOn: Boolean,
    onTogglePlay: () -> Unit,
    onAudioClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onFullClick: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // 全出血遮罩：边缘与视频完全对齐，圆角由外层裁剪跟随
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        PlayerOverlayScrimMid,
                        PlayerOverlayScrimBottomEnd
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Slider(
                value = sliderVal,
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekDone,
                enabled = sliderOn,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = PlayerSliderInactiveTrack,
                    disabledThumbColor = PlayerSliderDisabledThumb,
                    disabledActiveTrackColor = PlayerPillBorder,
                    disabledInactiveTrackColor = PlayerSliderDisabledTrack
                ),
                track = { sliderState ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (marks.isNotEmpty()) {
                            // 空降片段标记：细条叠在视频进度条上（垂直居中）
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val markerHeight = 5.dp.toPx()
                                val radius = CornerRadius(markerHeight / 2f)
                                marks.forEach { mark ->
                                    val left = size.width * mark.start
                                    val right = (size.width * mark.end).coerceAtLeast(left + 2.dp.toPx())
                                    drawRoundRect(
                                        color = mark.color,
                                        topLeft = Offset(left, (size.height - markerHeight) / 2f),
                                        size = Size(right - left, markerHeight),
                                        cornerRadius = radius
                                    )
                                }
                            }
                        }
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color.White,
                                inactiveTrackColor = PlayerSliderInactiveTrack,
                                disabledActiveTrackColor = PlayerPillBorder,
                                disabledInactiveTrackColor = PlayerSliderDisabledTrack
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CtrlBtn(
                    text = playText,
                    on = true,
                    onClick = onTogglePlay,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timeText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 64.dp)
                )
                CtrlBtn(
                    text = audioText,
                    on = audioOn,
                    onClick = onAudioClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = qualityText,
                    on = qualityOn,
                    onClick = onQualityClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = speedText,
                    on = true,
                    onClick = onSpeedClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = fullText,
                    on = true,
                    onClick = onFullClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CtrlBtn(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 停用态前景用 0.72 白而非 0.5：停用也要在亮画面上保持可读
    val bg = if (on) PlayerCtrlButtonOn else PlayerCtrlButtonOff
    val fg = if (on) {
        Color.White
    } else {
        PlayerCtrlButtonTextOff
    }

    Box(
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .clickable(enabled = on, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 播放器顶栏悬浮胶囊：统一「弹幕开关 / 缩放还原」等轻操作的视觉语言。
 * 半透明黑底 + 白字（[dimmed] 时降低字重）+ 细描边；[showDot] 用小白点表达开启态。
 */

@Composable
private fun PlayerPillChip(
    text: String,
    showDot: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .clip(pillShape)
            .background(PlayerPillScrim)
            .border(1.dp, PlayerPillBorder, pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (dimmed) PlayerPillTextDim else Color.White,
                maxLines = 1
            )
        }
    }
}

private fun readPlayerTopMetaText(
    context: android.content.Context,
    timeFmt: java.text.DateFormat
): String {
    val time = timeFmt.format(System.currentTimeMillis())
    val battery = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    return if (battery != null) "$time  ${battery}%" else time
}

/** 空降片段在标记条上的位置与颜色。 */

private data class SbMark(
    val start: Float,
    val end: Float,
    val color: Color
)

/**
 * 空降助手浮层：手动跳过按钮条与「跳到精彩时刻」。
 */

@Composable
private fun SponsorBlockOverlay(
    state: SponsorBlockUiState,
    showCtrl: Boolean,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onJumpPoi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manual = state.manualSkip
    val poi = state.poiJump
    if (manual == null && poi == null) return
    Column(
        modifier = modifier
            .padding(end = 12.dp, bottom = if (showCtrl) 88.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (manual != null) {
            Row(
                modifier = Modifier
                    .background(
                        color = PlayerToastScrim,
                        shape = RoundedCornerShape(20.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "跳过${manual.category.shortTitle}片段",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable(onClick = onSkip)
                        .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    tint = PlayerSecondaryText,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onDismiss)
                        .padding(5.dp)
                )
            }
        }
        if (poi != null) {
            Text(
                text = "跳到精彩时刻",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(
                        color = PlayerToastScrim,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable(onClick = onJumpPoi)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}
