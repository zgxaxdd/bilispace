@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.naaammme.bbspace.infra.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 系统级错误恢复判定与防抖窗口的单测。
 * 常量均为编译期内联 int，JVM 下无需加载 PlaybackException。
 */
class PlayerErrorPolicyTest {

    @Test
    fun `system resource errors are recoverable`() {
        val codes = intArrayOf(
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
        codes.forEach {
            assertTrue("0x${it.toString(16)} should recover", PlayerErrorPolicy.isRecoverable(it))
        }
    }

    @Test
    fun `io parsing and capability errors do not recover`() {
        val codes = intArrayOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            0
        )
        codes.forEach {
            assertFalse("0x${it.toString(16)} must not recover", PlayerErrorPolicy.isRecoverable(it))
        }
    }

    @Test
    fun `throttle allows max rebuilds inside window then rejects`() {
        val throttle = RebuildThrottle(maxRebuilds = 3, windowMs = 60_000)
        assertTrue(throttle.tryAcquire(1_000))
        assertTrue(throttle.tryAcquire(2_000))
        assertTrue(throttle.tryAcquire(3_000))
        assertFalse(throttle.tryAcquire(4_000))
        // 60s 后最早记录滑出窗口
        assertTrue(throttle.tryAcquire(61_500))
    }

    @Test
    fun `throttle reset frees the window`() {
        val throttle = RebuildThrottle(maxRebuilds = 2, windowMs = 60_000)
        assertTrue(throttle.tryAcquire(0))
        assertTrue(throttle.tryAcquire(1))
        assertFalse(throttle.tryAcquire(2))
        throttle.reset()
        assertTrue(throttle.tryAcquire(3))
    }
}
