package com.naaammme.bbspace.feature.video

import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.media3.common.util.UnstableApi
import androidx.compose.material3.BottomSheetDefaults
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlaybackStream
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadOption
import com.naaammme.bbspace.core.model.VideoDownloadOptions
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.feature.video.detail.VideoDetailPage
import com.naaammme.bbspace.feature.video.player.VideoPlayerPane
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

internal val speedOps = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/**
 * 详情遮罩在「打开动画播完」之后愿意等待视频首帧的时长。
 *
 * 真机实测（PLC110 / ColorOS 16.0）：首帧就绪相对 beginOpen 为 616~847ms，而打开动画本身
 * 约 610ms——也就是说动画结束时视频通常还没出画面，旧逻辑会把**已经组合好的**详情内容
 * 一直盖到首帧为止（实测遮罩驻留 639~892ms）。此处只保留一小段宽限用于吸收抖动，
 * 超时即放行详情内容；播放器区域的封面垫底仍按首帧淡出，视频画面本身不受影响。
 *
 * 取 120ms：足以覆盖首帧紧随动画结束的情形，又不会让用户对着空背景干等。
 */
private const val MASK_FIRST_FRAME_GRACE_MS = 120L

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Composable
fun VideoScreen(
    onBack: () -> Unit,
    onGoHome: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenDownloadCache: () -> Unit,
    onStartDownload: (VideoDownloadRequest) -> Unit,
    viewModel: VideoViewModel,
    hostExpanded: Boolean = true,
    // 详情页封面垫底（仅视频详情 Activity 传入；覆盖层路径保持 null 零改动）：
    // 铺在播放器区域（与播放器容器同边界同圆角），首帧渲染后交叉淡出。
    // 不做全屏铺底——系统无缝动画的 leash 终态交给真实页面接管，避免长时间"预览图铺屏"。
    coverBackdropUrl: String? = null,
    coverBackdropLarge: Boolean = false
) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    val act = LocalActivity.current
    val context = LocalContext.current
    // 播放器容器在窗口坐标系中的矩形与收尾圆角（垫底形变终点），三个布局分支各自上报
    var coverBackdropFrame by remember { mutableStateOf<BackdropFrame?>(null) }

    // 互动动作（点赞/投币/收藏/三连）结果提示
    LaunchedEffect(viewModel) {
        viewModel.msg.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpandedWidth = windowSizeClass.isWidthAtLeastBreakpoint(840)
    val themeUsesDarkSystemBarIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    var isFull by rememberSaveable { mutableStateOf(false) }
    var downloadSheetOn by rememberSaveable { mutableStateOf(false) }
    val fullOn = hostExpanded && isFull
    // Activity 无缝路径：详情本体**立即组合**（打开动画期间被纯背景色遮罩盖住，
    // 首帧组合在动画内完成），动画结束（或 1.2s 超时兜底）后遮罩直接淡出——
    // 简介与详情随视频画面同步出现，不再有「动画结束→组合→再淡入」的串行等待。
    // 遮罩只铺主题背景色，不画封面：封面连续性由播放器区域的 CoverSettleBackdrop
    // 负责，详情区再画一份会露出重复的预览图。详情区域 Box 恒占位、遮罩是覆盖层
    // ——播放器与布局尺寸全程不变。以视频 ids（aid/cid）为键，避免连续打开
    // 同源封面视频时错误复用上次的遮罩状态。
    val anim = LocalAnimations.current
    val seamlessIdsKey = videoState.ids
    var detailMaskOn by remember(seamlessIdsKey) {
        mutableStateOf(coverBackdropUrl != null && SeamlessTransitionState.openRunning)
    }
    // 遮罩放行：打开动画播完后，等「播放器首帧」或「宽限期」二者先到者。
    // - 首帧先到（常见）：画面与详情同步出现，最理想。
    // - 宽限期先到（起播慢，真机实测首帧 616~847ms）：放行**早已组合好的**详情内容，
    //   不再让纯色遮罩继续盖着可读内容。代价是详情区可能先露骨架/占位再被真实数据替换，
    //   这是有意的取舍：可读内容优先于等待视频就绪。播放器区域的封面垫底
    //   （CoverSettleBackdrop）仍独立守在首帧才淡出，视频画面本身不受影响。
    // 幂等放行：effect 以 ids 为键，详情数据到达（cid 补全）会重跑本块，遮罩已放行时不再重复动作。
    LaunchedEffect(seamlessIdsKey) {
        if (coverBackdropUrl == null) return@LaunchedEffect
        if (!SeamlessTransitionState.openRunning) {
            detailMaskOn = false
            return@LaunchedEffect
        }
        SeamlessTransitionState.awaitOpenAnimDone()
        // 已经出首帧 → 立即放行；否则最多再等宽限期。withTimeoutOrNull 比手写轮询精确：
        // 首帧一到就返回，不必等满宽限期，也不会像 while(delay) 那样产生空转。
        if (!videoState.hasRenderedFirstFrame) {
            withTimeoutOrNull(MASK_FIRST_FRAME_GRACE_MS) {
                snapshotFlow { videoState.hasRenderedFirstFrame }.first { it }
            }
        }
        if (!detailMaskOn) return@LaunchedEffect
        detailMaskOn = false
    }
    val isPortraitVideo = remember(videoState.currentStream) {
        val stream = videoState.currentStream ?: return@remember false
        (stream.width ?: 0) in 1..<(stream.height ?: 0)
    }
    val openTarget = remember(viewModel) {
        { target: VideoTarget -> viewModel.openTarget(target) }
    }
    val switchPage = remember(viewModel) {
        { cid: Long -> viewModel.switchPage(cid) }
    }
    val switchEpisode = remember(viewModel) {
        { target: VideoTarget -> viewModel.switchEpisode(target) }
    }
    val downloadClick = remember { { downloadSheetOn = true } }
    val handleBack = {
        if (fullOn) {
            isFull = false
        } else if (!viewModel.popPage()) {
            onBack()
        } else {
            Unit
        }
    }

    if (hostExpanded) {
        BackHandler {
            handleBack()
        }
    }

    LaunchedEffect(hostExpanded) {
        if (!hostExpanded) {
            downloadSheetOn = false
        }
    }

    if (act != null) {
        val win = act.window
        val ctrl = remember(win) { WindowInsetsControllerCompat(win, win.decorView) }
        val restoreLightSystemBars by rememberUpdatedState(themeUsesDarkSystemBarIcons)

        LaunchedEffect(ctrl, fullOn, isExpandedWidth, themeUsesDarkSystemBarIcons) {
            ctrl.isAppearanceLightStatusBars =
                !fullOn && isExpandedWidth && themeUsesDarkSystemBarIcons
            ctrl.isAppearanceLightNavigationBars = !fullOn && themeUsesDarkSystemBarIcons

            if (fullOn) {
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        DisposableEffect(ctrl) {
            onDispose {
                ctrl.isAppearanceLightStatusBars = restoreLightSystemBars
                ctrl.isAppearanceLightNavigationBars = restoreLightSystemBars
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(act, fullOn, settingsState.playback.autoRotateFullscreen, isPortraitVideo) {
        act?.requestedOrientation = if (
            fullOn &&
            settingsState.playback.autoRotateFullscreen &&
            !isPortraitVideo
        ) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val playerTopPad = 16.dp
        val playerGap = 16.dp
        val compactVideoH = maxWidth * (9f / 16f)
        val compactPlayerSpaceH = statusTop + compactVideoH
        val expandedContentW = (maxWidth - (playerTopPad * 2) - playerGap).coerceAtLeast(0.dp)
        val expandedPlayerW = expandedContentW * 0.54f
        val expandedPlayerH = (maxHeight - statusTop - (playerTopPad * 2)).coerceAtLeast(0.dp)

        when {
            !hostExpanded || fullOn -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            coverBackdropFrame = BackdropFrame(it.boundsInWindow(), RectangleShape)
                        }
                ) {
                    VideoPlayerPane(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                        videoTitle = videoState.detail?.title,
                        isFull = fullOn,
                        onToggleFull = { isFull = !isFull },
                        onBackClick = handleBack,
                        onGoHome = onGoHome
                    )
                }
            }

            isExpandedWidth -> {
                val expandedPlayerShape = RoundedCornerShape(BiliRadius.player)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(playerTopPad),
                    horizontalArrangement = Arrangement.spacedBy(playerGap),
                    verticalAlignment = androidx.compose.ui.Alignment.Top
                ) {
                    VideoPlayerPane(
                        modifier = Modifier
                            .width(expandedPlayerW)
                            .height(expandedPlayerH)
                            .clip(expandedPlayerShape)
                            .onGloballyPositioned {
                                coverBackdropFrame = BackdropFrame(
                                    it.boundsInWindow(),
                                    expandedPlayerShape
                                )
                            },
                        viewModel = viewModel,
                        videoTitle = videoState.detail?.title,
                        isFull = false,
                        onToggleFull = { isFull = true },
                        onBackClick = handleBack,
                        onGoHome = onGoHome
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        VideoDetailPage(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel,
                            detail = videoState.detail,
                            ids = videoState.ids,
                            biz = videoState.biz,
                            detailLoading = videoState.detailLoading,
                            detailError = videoState.detailError,
                            commentSubject = viewModel.commentSubject,
                            contentHorizontalPad = 0.dp,
                            onOpenVideo = openTarget,
                            onOpenSpace = onOpenSpace,
                            onDownloadClick = downloadClick,
                            onOpenEpisode = switchEpisode,
                            onSwitchPage = switchPage
                        )
                        SeamlessDetailMask(visible = detailMaskOn, fadeMillis = anim.short)
                    }
                }
            }

            else -> {
                val compactPlayerShape = RoundedCornerShape(
                    bottomStart = BiliRadius.playerCompact,
                    bottomEnd = BiliRadius.playerCompact
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(compactPlayerSpaceH)
                            // 先裁剪再画背景：底部圆角才能裁到黑色底块本身（垫底形变终点同此形状）
                            .clip(compactPlayerShape)
                            .background(Color.Black)
                            .onGloballyPositioned {
                                coverBackdropFrame = BackdropFrame(
                                    it.boundsInWindow(),
                                    compactPlayerShape
                                )
                            }
                    ) {
                        VideoPlayerPane(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .height(compactVideoH),
                            viewModel = viewModel,
                            videoTitle = videoState.detail?.title,
                            isFull = false,
                            onToggleFull = { isFull = true },
                            onBackClick = handleBack,
                            onGoHome = onGoHome
                        )
                    }
                    // 竖排布局背景由外层 Column 统一绘制，详情区不再重复铺一层（减一层全屏 overdraw）
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        VideoDetailPage(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel,
                            detail = videoState.detail,
                            ids = videoState.ids,
                            biz = videoState.biz,
                            detailLoading = videoState.detailLoading,
                            detailError = videoState.detailError,
                            commentSubject = viewModel.commentSubject,
                            contentHorizontalPad = 16.dp,
                            onOpenVideo = openTarget,
                            onOpenSpace = onOpenSpace,
                            onDownloadClick = downloadClick,
                            onOpenEpisode = switchEpisode,
                            onSwitchPage = switchPage
                        )
                        SeamlessDetailMask(visible = detailMaskOn, fadeMillis = anim.short)
                    }
                }
            }
        }

        // 封面垫底：绘制在最上层但无点击语义（触摸穿透到下方内容），
        // 铺在播放器区域内，首帧渲染后淡出露出视频。
        coverBackdropUrl?.let { url ->
            CoverSettleBackdrop(
                coverUrl = url,
                coverLarge = coverBackdropLarge,
                frame = coverBackdropFrame,
                firstFrameRendered = videoState.hasRenderedFirstFrame
            )
        }
    }

    if (hostExpanded && downloadSheetOn) {
        val sheetVideoQuality = videoState.currentStream?.quality ?: 80
        val sheetAudioQuality = videoState.currentAudio?.id ?: 0
        DownloadTaskSheet(
            currentVideoQuality = sheetVideoQuality,
            currentAudioQuality = sheetAudioQuality,
            canStartDownload = viewModel.currentDownloadRequest(
                kind = VideoDownloadKind.VIDEO,
                videoQuality = sheetVideoQuality,
                audioQuality = sheetAudioQuality
            ) != null,
            onDismiss = { downloadSheetOn = false },
            onOpenCache = {
                downloadSheetOn = false
                onOpenDownloadCache()
            },
            onStart = { kind, videoQuality, audioQuality ->
                val request = viewModel.currentDownloadRequest(
                    kind = kind,
                    videoQuality = videoQuality,
                    audioQuality = audioQuality
                ) ?: error("下载参数无效")
                onStartDownload(request)
            }
        )
    }
}

/** 封面垫底形变的目标帧：播放器容器在窗口坐标系中的矩形与收尾圆角形状。 */
private data class BackdropFrame(val rect: Rect, val endShape: Shape)

/** [BackdropFrame.endShape] 的四角圆角半径（px），供形变插值。 */
private data class BackdropCornerPx(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float
)

private fun Shape.cornerPxOrNull(density: Density): BackdropCornerPx? {
    val corners = this as? CornerBasedShape ?: return null
    return BackdropCornerPx(
        topStart = corners.topStart.toPx(Size.Unspecified, density),
        topEnd = corners.topEnd.toPx(Size.Unspecified, density),
        bottomEnd = corners.bottomEnd.toPx(Size.Unspecified, density),
        bottomStart = corners.bottomStart.toPx(Size.Unspecified, density)
    )
}

/**
 * 详情区打开遮罩：系统无缝打开动画期间盖住详情区域（不露骨架/占位闪变），
 * 动画结束后按主题 token 时长淡出。遮罩只铺主题背景色——系统无缝动画把封面
 * 展开到**播放器区域**，详情区若再绘制一份同图封面，就会在播放器下方露出一块
 * 重复的预览图；封面连续性仅由播放器区域的 CoverSettleBackdrop 负责。
 */
@Composable
private fun SeamlessDetailMask(
    visible: Boolean,
    fadeMillis: Int
) {
    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = fadeOut(tween(durationMillis = fadeMillis))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}

/**
 * 详情页封面垫底：铺在播放器区域（容器同边界同圆角），视频首帧渲染后交叉淡出；
 * 流迟迟未就绪时超时兜底淡出（最长驻留 2.5s）。变体与来源卡片一致，直接命中
 * 内存缓存位图，无占位闪烁。位置经 layout 延迟读取，帧间零重组。
 */
@Composable
private fun CoverSettleBackdrop(
    coverUrl: String,
    coverLarge: Boolean,
    frame: BackdropFrame?,
    firstFrameRendered: Boolean
) {
    var dismissed by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(1f) }
    val fadeSpec = tween<Float>(durationMillis = LocalAnimations.current.short)

    LaunchedEffect(firstFrameRendered) {
        if (firstFrameRendered && !dismissed) {
            alpha.animateTo(0f, fadeSpec)
            dismissed = true
        }
    }
    LaunchedEffect(Unit) {
        delay(2500)
        if (!dismissed) {
            alpha.animateTo(0f, fadeSpec)
            dismissed = true
        }
    }

    if (dismissed || frame == null) return
    val density = LocalDensity.current
    val cornerPx = remember(frame, density) { frame.endShape.cornerPxOrNull(density) }
    if (cornerPx == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .backdropBounds(frame.rect)
                .graphicsLayer {
                    this.alpha = alpha.value
                    shape = RoundedCornerShape(
                        topStart = CornerSize(cornerPx.topStart),
                        topEnd = CornerSize(cornerPx.topEnd),
                        bottomEnd = CornerSize(cornerPx.bottomEnd),
                        bottomStart = CornerSize(cornerPx.bottomStart)
                    )
                    clip = true
                }
                .background(Color.Black)
        ) {
            BiliAsyncImage(
                url = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                variant = if (coverLarge) {
                    BiliImageVariant.CardCoverLarge
                } else {
                    BiliImageVariant.CardCover43
                },
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 垫底节点按 [rect]（窗口坐标）测量并摆放自身内容；
 * 尺寸与位置都在布局阶段读取，不触发重组。
 */
private fun Modifier.backdropBounds(rect: Rect): Modifier = layout { measurable, _ ->
    val w = rect.width.roundToInt().coerceAtLeast(1)
    val h = rect.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskSheet(
    currentVideoQuality: Int,
    currentAudioQuality: Int,
    canStartDownload: Boolean,
    onDismiss: () -> Unit,
    onOpenCache: () -> Unit,
    onStart: (VideoDownloadKind, Int, Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by rememberSaveable { mutableStateOf(VideoDownloadKind.VIDEO) }
    var videoQuality by rememberSaveable {
        mutableIntStateOf(currentVideoQuality.takeIf { it > 0 } ?: 80)
    }
    var audioQuality by rememberSaveable {
        mutableIntStateOf(currentAudioQuality.takeIf { it > 0 } ?: 0)
    }

    ModalBottomSheet(
        // iOS：弹层顶部圆角 20dp（方案 2.3 / BiliRadius.sheetTop 语义）
        shape = if (LocalUiStyle.current == UiStyle.IOS) {
            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        } else {
            BottomSheetDefaults.ExpandedShape
        },
        scrimColor = Color.Black.copy(alpha = 0.40f),
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("下载", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == VideoDownloadKind.VIDEO,
                    onClick = { kind = VideoDownloadKind.VIDEO },
                    label = { Text("下载视频") }
                )
                FilterChip(
                    selected = kind == VideoDownloadKind.AUDIO,
                    onClick = { kind = VideoDownloadKind.AUDIO },
                    label = { Text("下载音频") }
                )
            }
            if (kind == VideoDownloadKind.VIDEO) {
                DownloadOptionGroup(
                    title = "画质",
                    options = VideoDownloadOptions.videoQualities,
                    selected = videoQuality,
                    onSelect = { videoQuality = it }
                )
            }
            DownloadOptionGroup(
                title = "音质",
                options = VideoDownloadOptions.audioQualities,
                selected = audioQuality,
                onSelect = { audioQuality = it }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenCache,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看缓存")
                }
                Button(
                    onClick = { onStart(kind, videoQuality, audioQuality) },
                    enabled = canStartDownload,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (kind == VideoDownloadKind.VIDEO) "下载视频" else "下载音频")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadOptionGroup(
    title: String,
    options: List<VideoDownloadOption>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.value == selected,
                    onClick = { onSelect(option.value) },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val min = sec / 60
    val hour = min / 60
    return if (hour > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hour, min % 60, sec % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", min, sec % 60)
    }
}

internal fun formatPlaybackTime(
    posMs: Long,
    durMs: Long
): String {
    return "${formatDuration(posMs)} / ${formatDuration(durMs)}"
}

internal fun formatSpeed(speed: Float): String {
    val num = if (speed % 1f == 0f) {
        String.format(Locale.ROOT, "%.1f", speed)
    } else {
        String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "${num}x"
}

internal fun getCodecName(codecId: Int): String {
    return when (codecId) {
        7 -> "AVC/H.264"
        12 -> "HEVC/H.265"
        13 -> "AV1"
        else -> "未知 $codecId"
    }
}

internal fun getAudioName(
    audioId: Int,
    short: Boolean = false
): String {
    return when (audioId) {
        30216 -> "64K"
        30232 -> "132K"
        30280 -> "192K"
        30250 -> if (short) "杜比" else "杜比全景声"
        30251 -> if (short) "无损" else "Hi-Res 无损"
        else -> if (short) "音频" else "音频 $audioId"
    }
}

internal fun getQualityName(
    source: PlaybackSource?,
    stream: PlaybackStream?
): String {
    source ?: return "画质"
    stream ?: return "画质"
    val option = source.qualityOptions.firstOrNull { it.quality == stream.quality }
    val label = option?.displayDescription?.takeIf(String::isNotBlank)
        ?: option?.description?.takeIf(String::isNotBlank)
        ?: stream.description.takeIf(String::isNotBlank)
        ?: "画质"
    return label.substringBefore(' ').ifBlank { label }
}


