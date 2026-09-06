package com.naaammme.bbspace.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.LivePlaybackController
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.navigation.VideoDetailNavBus
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
    playerSettings: AppSettings,
    private val hostState: PlaybackHostState,
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

    // hostMode / miniPlayerAvailable / expand / minimize / close 的唯一状态源是
    // PlaybackHostState（进程级单例）：视频详情迁到独立 Activity 后两窗口共享，
    // 这里仅做转发，保持原公开属性名与签名，调用方零改动。
    val hostMode: PlaybackHostMode
        get() = hostState.hostMode
    val miniPlayerAvailable: StateFlow<Boolean>
        get() = hostState.miniPlayerAvailable

    private var shouldResumeAfterBackgroundPause = false

    fun expand() {
        hostState.expand()
    }

    fun minimize() {
        hostState.minimize()
    }

    fun openLive(route: LiveRoute) {
        viewModelScope.launch {
            livePlaybackController.openLive(route)
        }
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
