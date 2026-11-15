package com.naaammme.bbspace.feature.live

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.LiveRoomPanelState
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.feature.live.component.LivePlaybackBody
import com.naaammme.bbspace.feature.live.player.LivePlayerPane
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LiveScreen(
    onBack: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
    hostExpanded: Boolean = true
) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    val owner = LocalLifecycleOwner.current
    val act = LocalActivity.current
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    var isFull by rememberSaveable { mutableStateOf(false) }
    val fullOn = hostExpanded && isFull
    val isExpanded = hostExpanded && windowSizeClass.isWidthAtLeastBreakpoint(840) && !fullOn

    val toggleFull = { isFull = !isFull }
    val handleBack = {
        if (fullOn) {
            isFull = false
        } else {
            onBack()
        }
    }

    if (hostExpanded) {
        BackHandler(onBack = handleBack)
    }

    LaunchedEffect(hostExpanded) {
        if (!hostExpanded) {
            isFull = false
        }
    }

    DisposableEffect(owner, viewModel) {
        val lifecycle = owner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.ensureStarted()
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.ensureStarted()
        }
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(act, fullOn) {
        if (act == null) {
            onDispose { }
        } else {
            val win = act.window
            val ctrl = WindowInsetsControllerCompat(win, win.decorView)
            if (fullOn) {
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(act, fullOn, settingsState.playback.autoRotateFullscreen) {
        act?.requestedOrientation = if (fullOn && settingsState.playback.autoRotateFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val playerGap = BiliSpacing.lg
        val playerTopPad = BiliSpacing.lg
        val compactVideoH = maxWidth * (9f / 16f)
        val expandedContentW = (maxWidth - (playerTopPad * 2) - playerGap).coerceAtLeast(0.dp)
        val expandedPlayerW = expandedContentW * 0.54f
        val expandedPlayerH = (maxHeight - (playerTopPad * 2)).coerceAtLeast(0.dp)

        if (fullOn) {
            LivePlayerPane(
                route = route,
                player = player,
                playbackState = playbackState,
                roomSessionState = viewModel.roomSession,
                isFull = true,
                onToggleFull = toggleFull,
                onTogglePlay = viewModel::togglePlayPause,
                onToggleDanmaku = viewModel::setDanmakuEnabled,
                onRetry = viewModel::retry,
                onSwitchQuality = viewModel::switchQuality,
                settingsState = settingsState,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：自绘行取代 M3 TopAppBar——去掉 M3 自带的 surface 底色与 64dp
                // 高度，返回钮 + 房间数据直接铺在页面背景上，与首页/排行等吃页面
                // 背景的页内 chrome 同一语言（直播间此前是全 app 唯一还在用旧
                // TopAppBar 的页面，观感上"没适配"）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = handleBack,
                        // 全局拖拽反馈（第 34 轮 T2）：直播页顶栏返回钮。
                        //
                        // ⚠️ **只有这一颗**：本页的 `LivePlayerPane` 里有 Media3
                        // `PlayerView`（AndroidView），其祖先链上**一律不挂**
                        // `appElasticPress` —— graphicsLayer 会把视频画面推进离屏合成，
                        // 直接掉帧。返回钮在这个 Box 之外（它在 `Row` 里、与播放器面板
                        // 是兄弟），所以可以安全挂。
                        modifier = Modifier.appElasticPress()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    TopBarPanel(
                        // AP-18：两个 StateFlow 下推到唯一消费者（TopBarPanel 叶子）内收集。
                        // 原先顶层 collect 让每次人气/在线榜补丁（WATCHED_CHANGE 等
                        // 三种 cmd）都重组整个 LiveScreen —— 连带 LivePlayerPane 与
                        // 200 项消息 LazyColumn 的 item scope。
                        popularCount = viewModel.popularCount,
                        roomPanel = viewModel.roomPanel,
                        modifier = Modifier.padding(end = BiliSpacing.lg)
                    )
                }

                LivePlaybackBody(
                    route = route,
                    playbackState = playbackState,
                    roomSessionState = viewModel.roomSession,
                    player = player,
                    isExpanded = isExpanded,
                    playerSpaceWidth = expandedPlayerW,
                    playerSpaceHeight = if (isExpanded) expandedPlayerH else compactVideoH,
                    onToggleFull = toggleFull,
                    onTogglePlay = viewModel::togglePlayPause,
                    onToggleDanmaku = viewModel::setDanmakuEnabled,
                    onRetry = viewModel::retry,
                    onSwitchQuality = viewModel::switchQuality,
                    onSendDanmaku = viewModel::sendDanmaku,
                    settingsState = settingsState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TopBarPanel(
    // AP-18：改收 StateFlow 并在本叶子内收集 —— 补丁频率再高也只重组这个 Text。
    popularCount: StateFlow<Long>,
    roomPanel: StateFlow<LiveRoomPanelState>,
    modifier: Modifier = Modifier
) {
    val popular by popularCount.collectAsStateWithLifecycle()
    val panel by roomPanel.collectAsStateWithLifecycle()
    val watchedText = panel.watchedText
    val onlineRankText = panel.onlineRankText
    val rankChangedText = panel.rankChangedText
    val parts = listOfNotNull(
        popular.takeIf { it > 0L }?.let { "人气 $it" },
        watchedText?.takeIf(String::isNotBlank),
        onlineRankText?.takeIf(String::isNotBlank),
        rankChangedText?.takeIf(String::isNotBlank)
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
    )
}


