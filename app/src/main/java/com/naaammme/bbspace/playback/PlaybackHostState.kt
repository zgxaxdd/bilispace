package com.naaammme.bbspace.playback

import androidx.compose.runtime.mutableStateOf
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放宿主（视频详情覆盖层 / 迷你播放器）的进程级状态源。
 *
 * 视频详情在支持的设备上迁移到独立 [com.naaammme.bbspace.video.VideoDetailActivity] 后，
 * MainActivity 与详情 Activity 必须共享同一份 hostMode / miniPlayerAvailable：
 * 详情页收起时写 Mini/Hidden，返回 MainActivity 后由 [com.naaammme.bbspace.playback.PlaybackHost]
 * 据此决定迷你播放器是否出现。Compose snapshot state 可跨 Activity 组合读取，
 * 流派生状态用进程级协程作用域维持（语义与原 PlaybackHostViewModel 完全一致）。
 */
@Singleton
class PlaybackHostState @Inject constructor(
    private val playbackSession: StreamPlaybackSession,
    playerSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 进程内当前播放目标；与播放器同生命周期（见 StreamPlaybackSessionImpl）。 */
    val currentTarget: StateFlow<StreamPlaybackTarget?> = playbackSession.currentTarget

    private val _hostMode = mutableStateOf(PlaybackHostMode.Hidden)
    val hostMode: PlaybackHostMode
        get() = _hostMode.value

    val miniPlayerAvailable: StateFlow<Boolean> = playerSettings.state
        .map { it.playback.inAppMiniPlayer }
        .combine(playbackSession.currentTarget) { enabled, target ->
            enabled && target != null
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    init {
        // 与原 PlaybackHostViewModel.init 一致：目标消失即收起；
        // 迷你不可用（设置关闭）时不得停留在 Mini。
        scope.launch {
            combine(currentTarget, miniPlayerAvailable) { target, enabled ->
                target to enabled
            }.collect { (target, enabled) ->
                if (target == null) {
                    _hostMode.value = PlaybackHostMode.Hidden
                    return@collect
                }
                if (_hostMode.value == PlaybackHostMode.Mini && !enabled) {
                    _hostMode.value = PlaybackHostMode.Hidden
                }
            }
        }
    }

    fun expand() {
        cancelPendingClose()
        _hostMode.value = PlaybackHostMode.Expanded
    }

    fun minimize() {
        if (!miniPlayerAvailable.value) return
        if (currentTarget.value == null) return
        _hostMode.value = PlaybackHostMode.Mini
    }

    fun close() {
        cancelPendingClose()
        _hostMode.value = PlaybackHostMode.Hidden
        playbackSession.close()
    }

    /**
     * 无缝返回路径专用收起：Hidden 立即生效（状态正确），但关停播放推迟到系统
     * 返回动画结束后——close 会释放播放 surface，提前调用会让缩回动画中的画面变黑。
     * 回调丢失时由 [SeamlessTransitionState.awaitReturnEnd] 的超时兜底；期间重新打开
     * 视频由 [cancelPendingClose] 取消，避免误杀新会话。
     */
    fun closeAfterReturnAnimation() {
        _hostMode.value = PlaybackHostMode.Hidden
        pendingCloseJob?.cancel()
        pendingCloseJob = scope.launch {
            SeamlessTransitionState.awaitReturnEnd(maxWaitMs = 1_500)
            playbackSession.close()
        }
    }

    /** 取消尚未执行的延迟关停（重新打开视频/详情页时调用）。 */
    fun cancelPendingClose() {
        pendingCloseJob?.cancel()
        pendingCloseJob = null
    }

    private var pendingCloseJob: Job? = null
}
