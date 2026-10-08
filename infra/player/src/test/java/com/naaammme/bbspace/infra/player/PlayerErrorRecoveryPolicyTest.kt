package com.naaammme.bbspace.infra.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放错误恢复决策的验收。
 *
 * 被测实现**逐字移植自 BiliPai**（`core-player/.../PlayerErrorRecoveryPolicy.kt`）。
 * 需要说明的是：**源仓库没有给这个文件配套测试**（全仓搜不到），
 * 所以这份断言表是按**源码的分支逐条**写出来的，等于把源行为钉进本仓。
 */
class PlayerErrorRecoveryPolicyTest {

    private fun decide(
        errorCode: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        hasCdnAlternatives: Boolean = false,
        retryCount: Int = 0,
        maxRetries: Int = 3,
        cdnSwitchCount: Int = 0,
        maxCdnSwitches: Int = 0,
        isDecoderLike: Boolean = false,
        isPremiumAudioFailure: Boolean = false
    ) = decidePlayerErrorRecovery(
        errorCode = errorCode,
        hasCdnAlternatives = hasCdnAlternatives,
        retryCount = retryCount,
        maxRetries = maxRetries,
        cdnSwitchCount = cdnSwitchCount,
        maxCdnSwitches = maxCdnSwitches,
        isDecoderLikeFailure = isDecoderLike,
        isPremiumAudioFailure = isPremiumAudioFailure
    )

    @Test
    fun premiumAudioFailureShortCircuitsEverything() {
        assertEquals(
            PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO,
            decide(isPremiumAudioFailure = true, isDecoderLike = true)
        )
    }

    @Test
    fun networkErrorsPreferCdnSwitchThenRetryThenGiveUp() {
        assertEquals(
            "有备用线路且没切够 → 先换线",
            PlayerErrorRecoveryAction.SWITCH_CDN,
            decide(hasCdnAlternatives = true, cdnSwitchCount = 0, maxCdnSwitches = 2)
        )
        assertEquals(
            "换线次数用尽 → 退到重试",
            PlayerErrorRecoveryAction.RETRY_NETWORK,
            decide(hasCdnAlternatives = true, cdnSwitchCount = 2, maxCdnSwitches = 2)
        )
        assertEquals(
            "重试也用尽 → 放弃",
            PlayerErrorRecoveryAction.GIVE_UP,
            decide(retryCount = 3, maxRetries = 3)
        )
    }

    @Test
    fun decoderLikeFailuresGetTheirOwnRetryBudget() {
        assertEquals(
            PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK,
            decide(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                isDecoderLike = true,
                retryCount = 1
            )
        )
        assertEquals(
            "解码类只给 2 次（源里是硬编码的 2，与 maxRetries 无关）",
            PlayerErrorRecoveryAction.GIVE_UP,
            decide(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                isDecoderLike = true,
                retryCount = 2,
                maxRetries = 9
            )
        )
    }

    @Test
    fun nonNetworkNonDecoderErrorsGetASingleRetry() {
        assertEquals(
            PlayerErrorRecoveryAction.RETRY_NON_NETWORK,
            decide(errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, retryCount = 0)
        )
        assertEquals(
            PlayerErrorRecoveryAction.GIVE_UP,
            decide(errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, retryCount = 1)
        )
    }

    @Test
    fun networkErrorCodesAreTheFiveIoVariants() {
        val network = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        network.forEach { assertTrue("0x${it.toString(16)} 应判为网络错误", isNetworkPlaybackError(it)) }
        assertFalse(
            "解析类不是网络错误",
            isNetworkPlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        )
    }

    /** 解码类判定看 message/cause 关键字（媒体码给出通用错误码时靠它兜底）。 */
    @Test
    fun decoderLikeDetectionLooksAtMessageAndCause() {
        assertTrue(isDecoderLikeFailure("Decoder init failed", null))
        assertTrue(isDecoderLikeFailure(null, "android.media.MediaCodec\$CodecException"))
        assertTrue(isDecoderLikeFailure("video renderer error", null))
        assertFalse("普通 IO 错误不该被误判为解码类", isDecoderLikeFailure("connection reset", "java.io.IOException"))
        assertFalse(isDecoderLikeFailure(null, null))
    }

    /**
     * "恢复成功"的判据是**稳定播放**而非"出帧"。
     *
     * 真机实测（2026-10-06 断网）：缓存里的一帧会让"出帧即成功"的清零逻辑每次都生效，
     * 引擎因此每 ~3.3 秒重建一次（55 秒 28 次）。这里的边界就是那道闸门。
     */
    @Test
    fun recoveryIsSettledOnlyAfterTheSettleWindow() {
        assertFalse("刚出帧不算稳定", isRecoverySettled(elapsedSinceFirstFrameMs = 0L))
        assertFalse(
            "缓存里撑出来的几秒不算稳定（真机实测的自旋周期就是 ~3.3s）",
            isRecoverySettled(elapsedSinceFirstFrameMs = 3_300L)
        )
        assertFalse(
            "差 1ms 也不清零（边界必须严格）",
            isRecoverySettled(elapsedSinceFirstFrameMs = RECOVERY_SETTLE_MS - 1L)
        )
        assertTrue(
            "满稳定期才认定恢复成功",
            isRecoverySettled(elapsedSinceFirstFrameMs = RECOVERY_SETTLE_MS)
        )
        assertTrue(
            "远超稳定期当然算成功",
            isRecoverySettled(elapsedSinceFirstFrameMs = RECOVERY_SETTLE_MS * 5L)
        )
        assertEquals("稳定期取值 10s", 10_000L, RECOVERY_SETTLE_MS)
    }
}
