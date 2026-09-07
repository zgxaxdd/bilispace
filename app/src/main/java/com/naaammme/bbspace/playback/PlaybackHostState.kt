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
     *
     * 声音修复：进入本方法时立即 [StreamPlaybackSession.pause]——pause 不释放
     * surface，TextureView 保留最后一帧供缩回动画使用，画面不黑；而声音即刻停止，
     * 消除"退出后还响最长 1.5s"的延迟。
     */
    fun closeAfterReturnAnimation() {
        _hostMode.value = PlaybackHostMode.Hidden
        playbackSession.pause()
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

    /**
     * 用户主动收起视频（返回/页内导航）时标记，供 [shouldRestoreVideoPage] 区分
     * 「用户关闭」与「系统清栈销毁详情 Activity」。
     */
    fun markVideoClosedByUser() {
        userClosedVideoAt = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * 自愈判定：MainActivity 回到前台时，若仍有活动视频目标、宿主处于 Hidden
     * （详情 Activity 路径）且用户并未主动关闭（最近 3s 内无收起操作），
     * 说明详情 Activity 被系统以任何方式移出（如 singleTask 清栈/后台回收），
     * 应重新拉起视频页。覆盖层路径（mode==Expanded/Mini）恒返回 false。
     */
    fun shouldRestoreVideoPage(): Boolean {
        val target = playbackSession.currentTarget.value as? StreamPlaybackTarget.Video ?: return false
        if (_hostMode.value != PlaybackHostMode.Hidden) return false
        if (android.os.SystemClock.elapsedRealtime() - userClosedVideoAt < 3_000L) return false
        return true
    }

    private var pendingCloseJob: Job? = null
    private var userClosedVideoAt = 0L
}
