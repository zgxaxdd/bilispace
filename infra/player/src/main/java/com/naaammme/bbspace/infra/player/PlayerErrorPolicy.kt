package com.naaammme.bbspace.infra.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/**
 * 播放器系统级错误恢复策略（纯逻辑判定，可 JVM 单测）。
 *
 * 判定原则：解码器被系统回收（decoder reclaimed / init failed）、解码运行中失败、
 * AudioTrack 初始化/写入失败、视频帧处理失败、DRM 系统级错误等运行时资源类故障，
 * 重建 ExoPlayer 会话即可恢复；IO/解析/能力不支持/授权等业务性错误是确定性的，
 * 重建无益，维持现状只报错。
 *
 * 常量引用编译期内联，不引入运行时依赖；值随 media3 版本演进自动正确。
 */
@UnstableApi
internal object PlayerErrorPolicy {

    /**
     * @param errorCode [PlaybackException.errorCode]（调用方传入 int，便于测试注入）
     */
    fun isRecoverable(errorCode: Int): Boolean = errorCode in RECOVERABLE_CODES

    private val RECOVERABLE_CODES: Set<Int> = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
    )
}

/**
 * 会话重建防抖：滑动窗口 [windowMs] 内最多允许 [maxRebuilds] 次重建，
 * 超限拒绝（防止确定性故障导致无限重建循环）。时间由调用方注入便于测试。
 */
internal class RebuildThrottle(
    private val maxRebuilds: Int = DEFAULT_MAX_REBUILDS,
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    private val timestamps = ArrayDeque<Long>()

    /** 尝试占用一次重建额度；[nowMs] 用 SystemClock.elapsedRealtime 单调时钟 */
    fun tryAcquire(nowMs: Long): Boolean {
        while (timestamps.isNotEmpty() && nowMs - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxRebuilds) return false
        timestamps.addLast(nowMs)
        return true
    }

    /** 恢复成功（重建后首帧渲染）后清空计数 */
    fun reset() {
        timestamps.clear()
    }

    companion object {
        const val DEFAULT_MAX_REBUILDS = 3
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}
