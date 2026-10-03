package com.naaammme.bbspace.infra.player

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.naaammme.bbspace.core.model.PlaybackProgress
import kotlinx.coroutines.flow.StateFlow

enum class DecoderMode {
    Hard,
    Soft
}

data class PlayerConfig(
    val minBufferMs: Int = 2_000,
    val maxBufferMs: Int = 15_000,
    val playBufferMs: Int = 250,
    val rebufferMs: Int = 500,
    val backBufferMs: Int = 2_000,
    val decoderMode: DecoderMode = DecoderMode.Hard,
    val decoderFallback: Boolean = true
)

interface PlayerEngine {
    val player: StateFlow<Player?>
    val currentSource: StateFlow<EngineSource?>
    val playbackState: StateFlow<PlayerPlaybackState>
    val playbackProgress: StateFlow<PlaybackProgress>

    fun updateConfig(config: PlayerConfig)
    suspend fun setSource(
        source: EngineSource,
        startPositionMs: Long? = null,
        playWhenReady: Boolean = true,
        metadata: MediaMetadata? = null
    )
    fun play()
    fun pause()
    fun setSpeed(speed: Float)

    /**
     * 设置循环模式；[repeatOne] 为 true 表示单视频循环。
     *
     * 与 [updateConfig] 分开是有意的：改循环不应触发播放器重建（重建会打断正在播放的视频）。
     */
    fun setRepeatMode(repeatOne: Boolean)

    /**
     * 精确 seek（媒体3 默认 `SeekParameters.DEFAULT`）。
     *
     * 用途：**需要落到确切位置**的 seek —— 续播/恢复、SponsorBlock 跳段、
     * 自动片尾跳转、进度复位。这些都不能被吸附到关键帧（见 [SeekParametersPolicy]）。
     */
    fun seekTo(positionMs: Long)

    /**
     * **用户主动** seek（PB-02）：只由"进度条拖拽松手"这类入口调用，实现走关键帧吸附
     * （`SeekParameters.CLOSEST_SYNC`）以获得更快的落点。
     *
     * ⚠️ **不要**把它接到内部跳转上（SponsorBlock 跳段 / 自动片尾 / 续播）：
     * 那些需要精确边界，且续播位置会被写回历史记录。判据与理由见 [SeekParametersPolicy]。
     */
    fun seekUserInitiated(positionMs: Long)

    fun setMediaMetadata(metadata: MediaMetadata)
    fun stopForReuse(resetPosition: Boolean = true)
    fun release()
}
