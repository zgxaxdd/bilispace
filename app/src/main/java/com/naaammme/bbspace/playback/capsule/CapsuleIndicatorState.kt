package com.naaammme.bbspace.playback.capsule

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateOf
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.infra.player.PlayerEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮播放胶囊的进程级状态机（方案 2.3）。两态：Hidden / Active。
 *
 * 胶囊是"回程入口 + 位置指示"，**不持有 player、不创建 PlayerView、不触发网络**
 * ——只读会话与引擎的 StateFlow（0.8 节），因此不存在旧小窗的 PlayerView 抢绑
 * 竞态，详情页收起动画期间可立即出现。
 *
 * 与被删除的 `minimize()` 分支语义等价（方案 0.7 推论）：小窗启用时播放本来就
 * 不停，激活胶囊后播放同样继续；直播退出不进胶囊（走原有 close 路径）。
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

    /**
     * 融合动画进度（1=完全沉入底栏，0=常态悬浮）。Animatable 提升到进程级状态：
     * 胶囊 UI 驱动它，底栏在 graphicsLayer 内读值做形变（层属性，零重组）。
     */
    val sinkAnim = Animatable(1f)

    private val _active = mutableStateOf(false)

    /** 胶囊是否激活（Hidden=false）。 */
    val active: Boolean
        get() = _active.value

    /**
     * 详情页收起路径的等价替换：`currentTarget is Video ? activate() : 原关停分支`。
     * 仅视频目标激活胶囊；直播 / 空目标返回 false，由调用方走原有关停。
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
