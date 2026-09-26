package com.naaammme.bbspace.playback.capsule

import androidx.compose.runtime.mutableStateOf
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    // 注意初始化顺序：此字段必须先于下方 init 块——init 内的 StateFlow collect
    // 会在构造期间（Main.immediate 同步派发）立即发射当前值并写 _active，
    // 放在 _active 声明之前就是 NPE（ParcelableSnapshotMutableState 未初始化）。
    private val _active = mutableStateOf(false)

    /**
     * 进程级作用域，无 cancel() —— 与 `PlaybackHostState.scope` 同一决定与同一理由
     * （ST-15 定为设计约束，不是遗漏）：@Singleton 没有"进程即将销毁"的可靠回调，
     * 加 close() 只会得到一段没人调用的死代码。**不许把它改成 Activity / ViewModel 作用域。**
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 审查 D7：active 只被 deactivate()/dismissAndStop() 复位，而会话可被
        // 三类外部路径关闭——直播打开（openLive 不经过 deactivate）、通知栏划掉
        // （PlaybackService.onNotificationCancelled → session.close）、上滑清任务
        // （onTaskRemoved）。此后小横条以空会话僵在 Dock 上（标题空、点开无效）。
        // 规则收敛为：会话目标不是视频（含空/直播）时 active 必然复位——与
        // activateForVideoTarget 只对视频激活的语义互为镜像。
        scope.launch {
            playbackSession.currentTarget.collect { target ->
                if (target !is StreamPlaybackTarget.Video) {
                    _active.value = false
                }
            }
        }
    }

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
