package com.naaammme.bbspace.playback.capsule

import androidx.compose.runtime.mutableStateOf
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.infra.player.PlayerEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放会话（联动底栏小横条内容源）的进程级状态机。两态：Hidden / Active。
 *
 * 只读会话与引擎的 StateFlow，**不持有 player、不创建 PlayerView、不触发网络**，
 * 因此不存在旧小窗的 PlayerView 抢绑竞态，详情页收起动画期间可立即出现。
 *
 * 合并进度不在这里——那属于视图层：由 `LinkedDockPhase` 经 `updateTransition`
 * 驱动（见 [LinkedBottomDock]），本类型只提供 `active`（= 官方 `hasAudio`）与内容快照。
 *
 * 与被删除的 `minimize()` 分支语义等价（方案 0.7 推论）：小窗启用时播放本来就
 * 不停，激活横条后播放同样继续；直播退出不进横条（走原有 close 路径）。
 */
@Singleton
class CapsuleIndicatorState @Inject constructor(
    private val playbackSession: StreamPlaybackSession,
    playerEngine: PlayerEngine
) {
    /** 会话快照：标题 / UP 名 / 封面 / 播放态（含 PlaybackState.Ended 自然结束）。 */
    val sessionState = playbackSession.sessionState

    /** 播放进度（positionMs / durationMs），引擎 StateFlow 直供，零轮询。 */
    val progress = playerEngine.playbackProgress

    private val _active = mutableStateOf(false)

    /** 横条是否激活（Hidden=false）；等价于官方 Dock 的 `hasAudio`。 */
    val active: Boolean
        get() = _active.value

    /**
     * 详情页收起路径的等价替换：`currentTarget is Video ? activate() : 原关停分支`。
     * 仅视频目标激活横条；直播 / 空目标返回 false，由调用方走原有关停。
     */
    fun activateForVideoTarget(): Boolean {
        if (playbackSession.currentTarget.value !is StreamPlaybackTarget.Video) return false
        _active.value = true
        return true
    }

    /** 进入新视频详情时隐藏（新详情接管播放展示）。 */
    fun deactivate() {
        _active.value = false
    }

    /** × 关闭 / 滑动移除 / 自然结束：立即停播并释放会话。 */
    fun dismissAndStop() {
        _active.value = false
        playbackSession.close()
    }
}
