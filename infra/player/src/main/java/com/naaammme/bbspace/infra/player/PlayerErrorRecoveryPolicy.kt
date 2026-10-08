package com.naaammme.bbspace.infra.player

import androidx.media3.common.PlaybackException

enum class PlayerErrorRecoveryAction {
    FALLBACK_PREMIUM_AUDIO,
    SWITCH_CDN,
    RETRY_NETWORK,
    RETRY_DECODER_FALLBACK,
    RETRY_NON_NETWORK,
    GIVE_UP
}

fun decidePlayerErrorRecovery(
    errorCode: Int,
    hasCdnAlternatives: Boolean,
    retryCount: Int,
    maxRetries: Int,
    cdnSwitchCount: Int,
    maxCdnSwitches: Int,
    isDecoderLikeFailure: Boolean,
    isPremiumAudioFailure: Boolean = false
): PlayerErrorRecoveryAction {
    if (isPremiumAudioFailure) {
        return PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO
    }
    return if (isNetworkPlaybackError(errorCode)) {
        when {
            hasCdnAlternatives && cdnSwitchCount < maxCdnSwitches -> PlayerErrorRecoveryAction.SWITCH_CDN
            retryCount < maxRetries -> PlayerErrorRecoveryAction.RETRY_NETWORK
            else -> PlayerErrorRecoveryAction.GIVE_UP
        }
    } else {
        when {
            isDecoderLikeFailure && retryCount < 2 -> PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK
            retryCount < 1 -> PlayerErrorRecoveryAction.RETRY_NON_NETWORK
            else -> PlayerErrorRecoveryAction.GIVE_UP
        }
    }
}

fun isNetworkPlaybackError(errorCode: Int): Boolean {
    return errorCode in listOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
    )
}

fun isDecoderLikeFailure(
    errorMessage: String?,
    causeClassName: String?
): Boolean {
    val message = errorMessage.orEmpty().lowercase()
    val cause = causeClassName.orEmpty().lowercase()
    return message.contains("decoder") ||
        message.contains("mediacodec") ||
        message.contains("renderer") ||
        cause.contains("decoder") ||
        cause.contains("mediacodec") ||
        cause.contains("renderer")
}

/**
 * 重建后的会话需要**稳定播放**多久，才算"恢复成功"（可以清空重试预算）。
 *
 * ## 为什么不能用"出帧"当成功判据（真机实测的活锁）
 *
 * 2026-10-06 真机（PLC110）断网验证：断网后 `PlaybackMediaCache` 里的缓存**仍能供出 1 帧**，
 * 于是 `onRenderedFirstFrame` 每次都把 `rebuildThrottle` 与 `networkRetryCount` 清零 ⇒
 * 引擎每 ~3.3 秒重建一次 ExoPlayer（**55 秒内 28 次**，`retained released players` 涨到 16），
 * 两道预算闸门（3 次/60s、最多 3 次重试）形同虚设，纯烧 CPU/内存/电。
 *
 * 现在口径：出帧只是**候选**，必须再稳定播放满 [RECOVERY_SETTLE_MS] 才清零。
 * 判据放在 ~500ms 进度轮询里，而该轮询只在"正在播放且 READY"时运行 ——
 * 也就是说"能走到这里"本身就是"确实在播"的证据（缓存里出一帧就断的会话走不到）。
 *
 * 取值 10s：比一次 ExoPlayer 内部重试退避（1~5s）× 一轮长，又不至于让"抖一下就好的网络"
 * 白等太久。同值判定见 `PlayerRecoverySpinGuardTest`。
 */
const val RECOVERY_SETTLE_MS = 10_000L

/** [elapsedSinceFirstFrameMs] = 重建后首个视频帧到现在经过的毫秒数。 */
fun isRecoverySettled(
    elapsedSinceFirstFrameMs: Long,
    settleMs: Long = RECOVERY_SETTLE_MS
): Boolean = elapsedSinceFirstFrameMs >= settleMs
