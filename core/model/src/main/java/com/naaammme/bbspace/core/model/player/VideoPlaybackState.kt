package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

enum class PlaybackState {
    Idle,
    Buffering,
    Ready,
    Ended
}

@Immutable
data class VideoPlaybackState(
    val biz: PlayBiz,
    val ids: ResolvedVideoIds = ResolvedVideoIds(),
    val detail: VideoDetail? = null,
    /**
     * 详情是否仍在加载。**默认 true**，不是 false。
     *
     * 为什么默认 true：详情页的语义是"打开就要去取详情"，所以"还没拿到"就是加载中。
     * 若默认 false，会出现下面这条真实链路：
     * 1. 用户点击卡片 → 系统开始 OPPO 无缝 morph（约 600ms）；
     * 2. 详情 Activity 首帧组合，此时 `detail == null` 且 `detailLoading == false`
     *    （`openRoot()` 挂在 `LaunchedEffect` 里，还没跑）；
     * 3. 详情区的 `when` 落到"无数据且无错误"分支 → **整片空白**；
     * 4. 直到协程真正开始取详情，`detailLoading` 才变 true。
     * 结果就是"动画期间是白的、动画结束才出现内容"。
     *
     * 默认 true 后，第 2 步首帧即命中骨架分支，骨架与 morph 同步出现。
     * 注意 `StreamPlaybackSessionImpl` 在两处显式设置它：
     * 取详情前 `detailLoading = reuseDetail == null`（有缓存复用时不显示骨架），
     * 取到后 `detailLoading = false` 与真实 `detail` 一起落地。
     */
    val detailLoading: Boolean = true,
    val detailError: String? = null,
    val isPreparing: Boolean = false,
    val playbackSource: PlaybackSource? = null,
    val currentStream: PlaybackStream? = null,
    val currentAudio: PlaybackAudio? = null,
    val error: PlaybackError? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val hasRenderedFirstFrame: Boolean = false,
    val seekEventId: Long = 0L,
    val playerError: String? = null
)
