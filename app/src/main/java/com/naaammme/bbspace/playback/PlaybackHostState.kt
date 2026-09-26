package com.naaammme.bbspace.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    /**
     * 进程级作用域，**刻意没有 cancel()**（BACKLOG ST-15 定为设计约束）：
     * 本类是 @Singleton，活到进程结束，进程死时协程自然结束；给它加一个 close()
     * 没有任何合法调用时机（Android 不提供"进程即将销毁"的可靠回调），只会变成
     * 一段没人调用的死代码。
     *
     * **约束**：这个 scope 不许被改成 Activity / ViewModel 作用域 —— 那才会真的漏。
     * 同理，任何往本类添加的长驻协程都必须能容忍活到进程结束。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 进程内当前播放目标；与播放器同生命周期（见 StreamPlaybackSessionImpl）。 */
    val currentTarget: StateFlow<StreamPlaybackTarget?> = playbackSession.currentTarget

    private val _hostMode = mutableStateOf(PlaybackHostMode.Hidden)
    val hostMode: PlaybackHostMode
        get() = _hostMode.value

    /**
     * VideoDetailActivity 实例是否存在于返回栈中（onCreate → onDestroy 的真实存活性）。
     *
     * Compose 可观察：联动底栏的小横条**必须**在详情页存活期间保持组合（见
     * [com.naaammme.bbspace.playback.capsule.LinkedNowPlayingRow] 的无缝锚点）——
     * 锚点 View 一旦 detach，系统的返回动画取不到锚点就会放弃 morph 退回 alpha
     * 交叉淡出（`view not attach to window or error, do alpha anim`）。
     * 因此这里不能再用 @Volatile 普通字段：底栏要按它重组来决定"只隐藏、不卸载"。
     */
    var videoDetailAlive: Boolean by mutableStateOf(false)
        private set

    /** 用户主动收起视频后置位（返回/页内导航/关闭）；重新打开视频时复位。诊断日志读取。 */
    @Volatile
    var isUserClosedVideo: Boolean = false
        private set

    /**
     * 「等下一个安全前台时刻再问通知权限」的意图（BACKLOG ST-23）。
     *
     * 原先这个布尔量是 MainActivity 的字段：后台播放场景下 Activity 可以先被销毁
     * （进程靠 PlaybackService 活着），于是标志随实例一起消失，这一次用户永远不会被问。
     * 挪到进程级单例就活得过 Activity 重建。
     *
     * **只存布尔量**：权限弹窗只能由 Activity 的 launcher 发起，所以这里刻意不保存
     * 任何 Activity/回调引用 —— 那正是 ST-03 修掉的"单例拖住已销毁 Activity"的形状。
     */
    @Volatile
    var notificationPromptPending: Boolean = false

    init {
        // 目标消失即收起（悬浮胶囊激活与 hostMode 无关，目标消失必然无处可回）。
        scope.launch {
            currentTarget.collect { target ->
                if (target == null) {
                    _hostMode.value = PlaybackHostMode.Hidden
                }
            }
        }
    }

    fun expand() {
        onPlaybackUiStarted?.invoke()
        cancelPendingClose()
        isUserClosedVideo = false
        _hostMode.value = PlaybackHostMode.Expanded
    }

    fun close() {
        cancelPendingClose()
        isUserClosedVideo = true
        _hostMode.value = PlaybackHostMode.Hidden
        playbackSession.close()
    }

    /**
     * 收起视频页但**保留播放会话**（胶囊/小横条承接播放）。
     *
     * 与 [close] 的唯一区别是不关停播放；与"什么都不做"的区别是它**真的收起页面**。
     * 这两件事此前被混为一谈：`CapsuleIndicatorState.activateForVideoTarget()` 只回答
     * "是否由胶囊接管播放"（目标是 Video 即返回 true），调用方却把 true 当成"收起已完成"，
     * 于是视频目标下返回键整条被吞、hostMode 永远停在 Expanded（真机复现：后台回收详情页后
     * 由自愈路径展开覆盖层，返回键失效、无法回到推荐页）。
     *
     * [isUserClosedVideo] 必须置位：胶囊接管后 `currentTarget` 仍是 Video，而覆盖层路径下
     * `videoDetailAlive` 恒为 false —— 不标记"用户主动收起"，`MainActivity.tryRestoreVideoPage`
     * 的自愈逻辑会在下一次 onResume/onWindowFocusChanged 把页面重新拉起，形成
     * 「返回 → 自愈 → 再拉起」的死循环。
     */
    fun collapseToCapsule() {
        cancelPendingClose()
        isUserClosedVideo = true
        _hostMode.value = PlaybackHostMode.Hidden
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
        isUserClosedVideo = true
        _hostMode.value = PlaybackHostMode.Hidden
        playbackSession.pause()
        pendingCloseJob?.cancel()
        pendingCloseJob = scope.launch {
            SeamlessTransitionState.awaitReturnEnd()
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
        isUserClosedVideo = true
    }

    /** 详情 Activity 生命周期上报：实例创建（onCreate）。 */
    fun onVideoDetailStarted() {
        onPlaybackUiStarted?.invoke()
        videoDetailAlive = true
    }

    /**
     * 播放 UI 开始展示（覆盖层 [expand] / 详情 Activity 创建）时的一次性回调。
     * 供 MainActivity 挂「首次开始播放」级副作用（如通知权限请求）；
     * 两条打开路径都会经过，多次触发由调用方自行去重。
     */
    var onPlaybackUiStarted: (() -> Unit)? = null

    /** 详情 Activity 生命周期上报：实例销毁（onDestroy）。 */
    fun onVideoDetailStopped() {
        videoDetailAlive = false
    }

    /** 无缝打开路径的点击预热：视为「用户正在打开视频」，复位关闭标记。 */
    fun markVideoOpened() {
        isUserClosedVideo = false
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
        if (isUserClosedVideo) return false
        return true
    }

    private var pendingCloseJob: Job? = null
}
