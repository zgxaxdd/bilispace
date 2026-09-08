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
 * 播放宿主（视频详情覆盖层 / 迷你播放器）的进程级状态源，同时是「视频页恢复」
 * 契约的唯一裁决者。
 *
 * 视频详情在支持无缝动画的设备上运行在独立 [com.naaammme.bbspace.video.VideoDetailActivity]
 * 中（压在 MainActivity 上方）。该实例随时可能被系统移出：OEM 清栈、后台活动实例
 * 回收、通知点击的 CLEAR_TOP、长时后台后的任务重置……移出后用户只应看到
 * MainActivity 的主页，而播放会话仍在（进程级单例 + 前台服务）。
 *
 * 恢复判定使用**真实存活性**而非猜测：VideoDetailActivity 在 onCreate/onDestroy
 * 上报 [onVideoDetailStarted] / [onVideoDetailStopped]，「详情页是否还在」由此
 * 标志回答；「是否用户主动关闭」由显式的 [markVideoClosedByUser]（返回/页内导航/
 * 关闭）回答。此前基于 hostMode 推断 + 3 秒时间窗的启发式在多种系统行为下失效，
 * 全部废弃。
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

    /** VideoDetailActivity 实例是否存在于返回栈中（onCreate → onDestroy 的真实存活性）。 */
    @Volatile
    var videoDetailAlive: Boolean = false
        private set

    /** 用户主动收起视频后置位（返回/页内导航/关闭）；重新打开视频时复位。 */
    @Volatile
    private var userClosedVideo = false

    init {
        // 目标消失即收起；迷你不可用（设置关闭）时不得停留在 Mini。
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
        userClosedVideo = false
        _hostMode.value = PlaybackHostMode.Expanded
    }

    fun minimize() {
        if (!miniPlayerAvailable.value) return
        if (currentTarget.value == null) return
        _hostMode.value = PlaybackHostMode.Mini
    }

    fun close() {
        cancelPendingClose()
        userClosedVideo = true
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
        userClosedVideo = true
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

    /** 用户主动收起视频（返回键/页内导航）时由 VideoDetailActivity 调用。 */
    fun markVideoClosedByUser() {
        userClosedVideo = true
    }

    /** 详情 Activity 生命周期上报：实例创建（onCreate）。 */
    fun onVideoDetailStarted() {
        videoDetailAlive = true
    }

    /** 详情 Activity 生命周期上报：实例销毁（onDestroy）。 */
    fun onVideoDetailStopped() {
        videoDetailAlive = false
    }

    /** 无缝打开路径的点击预热：视为「用户正在打开视频」，复位关闭标记。 */
    fun markVideoOpened() {
        userClosedVideo = false
    }

    /**
     * 视频页恢复判定（MainActivity 回到前台时评估）：播放会话仍持有视频目标、
     * 详情 Activity 实例确已不存在、宿主处于 Hidden（Activity 路径；覆盖层/迷你
     * 路径由自身组合恢复）且并非用户主动关闭 → 系统移出了详情页，应重新拉起。
     */
    fun shouldRestoreVideoPage(): Boolean {
        if (videoDetailAlive) return false
        if (currentTarget.value !is StreamPlaybackTarget.Video) return false
        if (_hostMode.value != PlaybackHostMode.Hidden) return false
        if (userClosedVideo) return false
        return true
    }

    /** 会话当前标题栏/通知使用的封面（恢复视频页时作为垫底图传入，避免黑屏闪变）。 */
    val sessionCoverUrl: String?
        get() = playbackSession.sessionState.value.cover

    private var pendingCloseJob: Job? = null
}
