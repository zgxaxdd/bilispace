package com.naaammme.bbspace.core.playback

import androidx.media3.common.Player
import com.naaammme.bbspace.core.model.DanmakuSessionState
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.core.model.VideoTarget
import kotlinx.coroutines.flow.StateFlow

interface VideoPlaybackController {
    val player: StateFlow<Player?>
    val videoState: StateFlow<VideoPlaybackState>
    val playbackProgress: StateFlow<PlaybackProgress>
    val danmakuState: StateFlow<DanmakuSessionState>

    fun openVideo(target: VideoTarget)
    fun play()
    fun pause()

    /**
     * 精确 seek：续播/恢复、SponsorBlock 跳段、自动片尾跳转都必须用它
     * （需要落到确切位置，且续播位置会被写回历史）。实现走媒体3 的 `DEFAULT` 参数。
     */
    fun seekTo(positionMs: Long)

    /**
     * **用户主动** seek（PB-02）：只由"进度条拖拽松手"这类入口调用 → 走关键帧吸附、落点更快。
     *
     * 判据与"为什么不能全都走关键帧"见 `infra/player/SeekParametersPolicy`。
     */
    fun seekUserInitiated(positionMs: Long)

    fun setSpeed(speed: Float)
    fun switchVideoQuality(quality: Int)
    fun switchVideoAudio(audioId: Int)
}
