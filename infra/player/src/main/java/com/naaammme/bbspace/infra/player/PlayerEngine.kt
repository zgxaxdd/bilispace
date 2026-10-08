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
    val decoderFallback: Boolean = true,
    /**
     * CDN 并行分片下载开关（对齐 BiliPai `parallelDownloadEnabled`）。
     * 由会话层从设置流灌入，引擎在 `setSource`/`updateConfig` 时应用；默认开。
     */
    val cdnParallelDownload: Boolean = true,
    /**
     * DASH 分段自适应预取开关（对齐 BiliPai `prefetchEnabled`）。
     * 只决定"要不要调度预取"，段数/网络判据在 `resolveCdnPrefetchSegmentCount` 与 Wi‑Fi 闸门里。
     */
    val cdnAdaptivePrefetch: Boolean = true
)

interface PlayerEngine {
    val player: StateFlow<Player?>
    val currentSource: StateFlow<EngineSource?>
    val playbackState: StateFlow<PlayerPlaybackState>
    val playbackProgress: StateFlow<PlaybackProgress>

    fun updateConfig(config: PlayerConfig)

    /**
     * **CDN 传输开关的活配置**（并行分片下载 / 自适应预取）。
     *
     * 为什么不能走 [updateConfig]：那里一旦发现配置变化就**重建 ExoPlayer**
     * （`buildPlayer` + release 旧实例），改个 CDN 开关不该打断正在播的视频。
     * 这两个开关只影响数据源与后台预取，因此单列一个方法原地生效：
     * - 关并行 → `parallelEnabled = false`，`CdnPlaybackDataSource` 在下一个窗口降级为单连接；
     * - 关预取 → 取消进行中的预取任务。
     */
    fun setCdnTransferOptions(parallelDownload: Boolean, adaptivePrefetch: Boolean)

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

    /**
     * 最近 [windowMs] 内是否**真的从网络读到过字节**。
     *
     * 用途：会话层记账的"惩罚必须有证据"判据（见 `CdnHealthPolicy.shouldRecordSessionEvent`）——
     * 字节缓存命中时播放不走 CDN 传输层，此时把 BUFFERING 算到某条线头上是错的。
     *
     * 语义与降级：
     * - 只有 DASH 链经过 CDN 传输层（`CdnTransferRuntime` 才有字节计数）⇒ 只有它能给出真信号；
     * - 其它链（Progressive / 本地 / 直播）拿不到该信号 ⇒ 返回 **true**（照老口径记），
     *   绝不允许因为"测不到"而把记账整体关掉。
     */
    fun isNetworkActiveWithin(windowMs: Long = NETWORK_ACTIVE_WINDOW_MS): Boolean

    /**
     * **此刻真正在供数的 CDN host**（换线之后就是备用线路的那条），拿不到时返回 null。
     *
     * 用途：会话层记账的目标 host —— 原来只能记"开播时选中的那条"，
     * 换线之后会把好线的功劳记到坏线头上（见 [CdnServingHostListener]）。
     */
    fun currentServingHost(): String?

    fun stopForReuse(resetPosition: Boolean = true)
    fun release()

    companion object {
        /** 网络活动回溯窗口：`CdnTransferRuntime` 的字节计数由 500ms 进度轮询采样。 */
        const val NETWORK_ACTIVE_WINDOW_MS = 5_000L
    }
}
