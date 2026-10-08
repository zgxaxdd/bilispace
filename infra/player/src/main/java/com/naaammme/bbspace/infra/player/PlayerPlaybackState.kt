package com.naaammme.bbspace.infra.player

import com.naaammme.bbspace.core.model.PlaybackState

data class PlayerPlaybackState(
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val firstFrameSeq: Long = 0L,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val seekEventSeq: Long = 0L,
    val errorMessage: String? = null,
    /**
     * 「起播超时」计数器（只增不减）：**每次**由于"开播后 [FirstFrameTimeoutPolicy.TIMEOUT_MS]
     * 还没出画面"而上报时 +1。会话层据此把 `CdnHealthPolicy.Event.FirstFrameTimeout`（−180 分）
     * 记到当前线路上 —— 在此之前该事件在真机上**从不产生**（判据死信号）。
     */
    val firstFrameTimeoutSeq: Long = 0L
)
