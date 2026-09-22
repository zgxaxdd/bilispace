package com.naaammme.bbspace.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.LivePlaybackController
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.core.playback.VideoPlaybackController
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.navigation.VideoDetailNavBus
import com.naaammme.bbspace.playback.capsule.CapsuleIndicatorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackHostViewModel @Inject constructor(
    private val playbackSession: StreamPlaybackSession,
    private val livePlaybackController: LivePlaybackController,
    private val videoPlaybackController: VideoPlaybackController,
    playerSettings: AppSettings,
    private val hostState: PlaybackHostState,
    val capsuleState: CapsuleIndicatorState,
    val videoDetailNavBus: VideoDetailNavBus
) : ViewModel() {

    val player = playbackSession.player
    val currentTarget = playbackSession.currentTarget
    val sessionState = playbackSession.sessionState
    val backgroundPlaybackEnabled = playerSettings.state
        .map { it.playback.backgroundPlayback }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /** ColorOS 无缝过渡动画开关（进程内即时值，供路由与卡片执行器同步门控）。 */
    val seamlessTransitionEnabled = playerSettings.state
        .map { it.playback.seamlessTransition }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    // hostMode / expand / close 的唯一状态源是 PlaybackHostState（进程级单例）：
    // 视频详情迁到独立 Activity 后两窗口共享，这里仅做转发。
    val hostMode: PlaybackHostMode
        get() = hostState.hostMode

    /**
     * 详情页存活标志（Compose 可观察）。联动底栏按它决定小横条是**隐藏**还是
     * **卸载**——隐藏保留无缝返回动画的锚点，卸载会让返回退回 alpha 交叉淡出。
     */
    val videoDetailAlive: Boolean
        get() = hostState.videoDetailAlive

    private var shouldResumeAfterBackgroundPause = false

    fun expand() {
        hostState.expand()
    }

    fun openLive(route: LiveRoute) {
        viewModelScope.launch {
            livePlaybackController.openLive(route)
        }
    }

    /**
     * 无缝/普通过渡打开视频详情 Activity 前的点击预热：立即发起取流与 prepare，
     * 让网络往返与窗口动画重叠。session.openVideo 对同 target 幂等
     * （详情侧 openRoot 命中短路并恢复播放），hostMode 保持 Hidden 不弹小窗。
     * cancelPendingClose 必须在发起前调用：上一段的延迟关停若晚于本 open 落地
     * 会 invalidate 新会话。
     */
    fun warmOpen(target: VideoTarget) {
        hostState.cancelPendingClose()
        hostState.markVideoOpened()
        videoPlaybackController.openVideo(target)
    }

    fun togglePlayPause() {
        if (sessionState.value.isPlaying) {
            shouldResumeAfterBackgroundPause = false
            playbackSession.pause()
        } else {
            playbackSession.play()
        }
    }

    fun onEnterBackground() {
        if (!sessionState.value.playWhenReady) {
            shouldResumeAfterBackgroundPause = false
            return
        }
        shouldResumeAfterBackgroundPause = true
        playbackSession.pause()
    }

    fun onReturnForeground() {
        if (!shouldResumeAfterBackgroundPause) return
        shouldResumeAfterBackgroundPause = false
        if (currentTarget.value != null && !sessionState.value.playWhenReady) {
            playbackSession.play()
        }
    }

    fun close() {
        shouldResumeAfterBackgroundPause = false
        hostState.close()
    }
}
