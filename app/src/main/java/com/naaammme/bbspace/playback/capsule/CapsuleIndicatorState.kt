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

    /**
     * 小横条**正被粒子消散接管**。
     *
     * 小横条的视觉由两层组成：Dock 统一绘制的玻璃壳（DockGlassSurface）与胶囊自身的
     * Compose 内容。消散组件只能藏掉后者 —— `graphicsLayer { alpha = 0 }` 管不到 Dock 那一层，
     * 也管不到 AndroidView 锚点与系统无缝覆盖层，于是"粒子散了、模糊壳还在"（用户实测）。
     * 由胶囊在**粒子首帧出现时**置位，Dock 据此同步淡掉玻璃壳，两边同一时刻消失。
     */
    private val _dissolving = mutableStateOf(false)
    val dissolving: Boolean
        get() = _dissolving.value

    fun beginDissolve() { _dissolving.value = true }

    fun endDissolve() { _dissolving.value = false }

    /** 进入新视频详情时隐藏（新详情接管播放展示）。 */
    fun deactivate() {
        _active.value = false
        _dissolving.value = false
    }

    /** × 关闭 / 滑动移除 / 自然结束：立即停播并释放会话。 */
    fun dismissAndStop() {
        _active.value = false
        _dissolving.value = false
        playbackSession.close()
    }

    /**
     * × 按下**当场**停播（用户反馈：「点关闭的叉叉后，视频没有立即关闭，有延迟」）。
     *
     * ## 为什么不能只靠 [dismissAndStop]
     * 关闭的语义是两件事：**停播**与**把横条本体从屏幕上送走**。原实现把两件事都压在
     * 消散回调 `onDissolveComplete` 上（照搬 BiliPai 的顺序：粒子首帧出现 → 隐藏本体 →
     * 消散完成再暂停并关闭）。粒子消散是 `1.5 + 0.9` 秒量级的动画（`THANOS_LONGEVITY`
     * / `THANOS_TAIL_SECONDS`，除以 `THANOS_TIME_SCALE` 后约 **2.09s**，另有 5s 兜底），
     * 于是用户按下 × 之后的整整两秒里**视频照常在播**（声音继续、画面继续动），
     * 只有横条自己在碎 —— 观感就是"点了没反应/关不掉"。
     *
     * 正确的分工是：**停播是交互的实质结果，必须当场发生**；粒子只是它的视觉收尾，
     * 该多长就多长。所以这里只做"停"（暂停播放器），真正的会话释放仍由
     * [dismissAndStop] 在消散结束时完成 —— 那时画面已经不在屏幕上了。
     *
     * 用 `pause()` 而不是 `close()`：`close()` 会立刻把 `currentTarget` 置空，
     * 而本类的 init 收集器据此把 `active` 翻假 ⇒ 整行离开组合 ⇒ 粒子会话随组件
     * 一起销毁（只剩半截动画）。停播与卸载分开，两个目标才都成立。
     */
    fun stopPlaybackNow() {
        playbackSession.pause()
    }
}
