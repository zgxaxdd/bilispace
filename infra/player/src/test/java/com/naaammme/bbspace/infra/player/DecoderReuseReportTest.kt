package com.naaammme.bbspace.infra.player

import androidx.media3.exoplayer.DecoderReuseEvaluation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码器复用评估的可读化（R5）纯逻辑测。
 *
 * 关键的一条是 [media3ConstantsHaveNotDrifted]：本对象的数值表是**从 Media3 抄来的镜像**，
 * 一旦升级 Media3 后 bit 位或枚举值变了，日志会开始说谎（"YES_WITH_FLUSH" 其实是不复用），
 * 而这在任何功能测试里都看不出来。抄来的东西必须自己钉住出处。
 */
@Suppress("UnsafeOptInUsageError")
class DecoderReuseReportTest {

    @Test
    fun media3ConstantsHaveNotDrifted() {
        assertEquals(DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseReport.RESULT_NO)
        assertEquals(
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH,
            DecoderReuseReport.RESULT_YES_WITH_FLUSH
        )
        assertEquals(
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION,
            DecoderReuseReport.RESULT_YES_WITH_RECONFIGURATION
        )
        assertEquals(
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
            DecoderReuseReport.RESULT_YES_WITHOUT_RECONFIGURATION
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED,
            DecoderReuseReport.REASON_REUSE_NOT_IMPLEMENTED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND,
            DecoderReuseReport.REASON_WORKAROUND
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
            DecoderReuseReport.REASON_APP_OVERRIDE
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED,
            DecoderReuseReport.REASON_MIME_TYPE_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_OPERATING_RATE_CHANGED,
            DecoderReuseReport.REASON_OPERATING_RATE_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED,
            DecoderReuseReport.REASON_INITIALIZATION_DATA_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED,
            DecoderReuseReport.REASON_MAX_INPUT_SIZE_EXCEEDED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED,
            DecoderReuseReport.REASON_DRM_SESSION_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_VIDEO_MAX_RESOLUTION_EXCEEDED,
            DecoderReuseReport.REASON_VIDEO_MAX_RESOLUTION_EXCEEDED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED,
            DecoderReuseReport.REASON_VIDEO_RESOLUTION_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_VIDEO_ROTATION_CHANGED,
            DecoderReuseReport.REASON_VIDEO_ROTATION_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED,
            DecoderReuseReport.REASON_VIDEO_COLOR_INFO_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED,
            DecoderReuseReport.REASON_AUDIO_CHANNEL_COUNT_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED,
            DecoderReuseReport.REASON_AUDIO_SAMPLE_RATE_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_AUDIO_ENCODING_CHANGED,
            DecoderReuseReport.REASON_AUDIO_ENCODING_CHANGED
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_AUDIO_BYPASS_POSSIBLE,
            DecoderReuseReport.REASON_AUDIO_BYPASS_POSSIBLE
        )
        assertEquals(
            DecoderReuseEvaluation.DISCARD_REASON_VIDEO_FRAME_RATE_CHANGED,
            DecoderReuseReport.REASON_VIDEO_FRAME_RATE_CHANGED
        )
    }

    @Test
    fun resultNamesCoverEveryValue() {
        assertEquals("NO", DecoderReuseReport.resultName(DecoderReuseReport.RESULT_NO))
        assertEquals("YES_WITH_FLUSH", DecoderReuseReport.resultName(DecoderReuseReport.RESULT_YES_WITH_FLUSH))
        assertEquals(
            "YES_WITH_RECONFIGURATION",
            DecoderReuseReport.resultName(DecoderReuseReport.RESULT_YES_WITH_RECONFIGURATION)
        )
        assertEquals(
            "YES_WITHOUT_RECONFIGURATION",
            DecoderReuseReport.resultName(DecoderReuseReport.RESULT_YES_WITHOUT_RECONFIGURATION)
        )
        // 越界值不许静默变成 "NO"（那会把"看不懂"伪装成"结论"）。
        assertEquals("UNKNOWN(9)", DecoderReuseReport.resultName(9))
    }

    @Test
    fun discardReasonsAreExpandedAsMask() {
        assertEquals("-", DecoderReuseReport.discardReasonNames(0))
        assertEquals(
            "MIME_TYPE_CHANGED",
            DecoderReuseReport.discardReasonNames(DecoderReuseReport.REASON_MIME_TYPE_CHANGED)
        )
        assertEquals(
            "MIME_TYPE_CHANGED|VIDEO_RESOLUTION_CHANGED",
            DecoderReuseReport.discardReasonNames(
                DecoderReuseReport.REASON_MIME_TYPE_CHANGED or
                    DecoderReuseReport.REASON_VIDEO_RESOLUTION_CHANGED
            )
        )
    }

    @Test
    fun unknownBitsAreStillPrinted() {
        // Media3 以后新增理由时：已知部分照写，未知部分写 BIT_<值>，而不是整条丢掉。
        val mask = DecoderReuseReport.REASON_WORKAROUND or (1 shl 20)
        assertEquals("WORKAROUND|BIT_${1 shl 20}", DecoderReuseReport.discardReasonNames(mask))
    }

    @Test
    fun lineDistinguishesNoPreviousDecoderFromReuseFailure() {
        // evaluation == null 的含义是"渲染器此前没有解码器"（首支持配置），
        // 与"评估之后决定不复用"必须一眼分得开，否则冷启动会被误读成复用失败。
        val cold = DecoderReuseReport.line(
            evaluationPresent = false,
            result = DecoderReuseReport.RESULT_NO,
            discardReasons = 0,
            decoderName = null,
            mimeType = "video/avc",
            width = 1920,
            height = 1080
        )
        assertEquals(
            "decoder-reuse result=NO_PREVIOUS_DECODER decoder=- format=video/avc size=1920x1080",
            cold
        )

        val reused = DecoderReuseReport.line(
            evaluationPresent = true,
            result = DecoderReuseReport.RESULT_YES_WITH_FLUSH,
            discardReasons = 0,
            decoderName = "c2.vdk.avc.decoder",
            mimeType = "video/avc",
            width = 1080,
            height = 1920
        )
        assertEquals(
            "decoder-reuse result=YES_WITH_FLUSH discard=- decoder=c2.vdk.avc.decoder " +
                "format=video/avc size=1080x1920",
            reused
        )
    }

    @Test
    fun unknownGeometryIsNotReportedAsZero() {
        // Format.width/height 的"未知"是 -1（Format.NO_VALUE），不是 0；
        // 打成 `size=-1x-1` 会被解析器当成真实分辨率，所以必须归一进 `-`。
        val line = DecoderReuseReport.line(
            evaluationPresent = true,
            result = DecoderReuseReport.RESULT_NO,
            discardReasons = DecoderReuseReport.REASON_VIDEO_RESOLUTION_CHANGED,
            decoderName = "OMX.google.h264.decoder",
            mimeType = null,
            width = -1,
            height = -1
        )
        assertTrue("未知分辨率要打 `-`：$line", line.contains(" size=-"))
        assertTrue("未知 mime 要打 `-`：$line", line.contains(" format=-"))
        assertTrue(line.contains("discard=VIDEO_RESOLUTION_CHANGED"))
    }

    @Test
    fun zeroSizeSurfaceIsNotReportedAsRealResolution() {
        // Media3 用 (0,0) 表示"surface 被摘掉"，不是"0×0 的视频"。
        val line = DecoderReuseReport.line(
            evaluationPresent = true,
            result = DecoderReuseReport.RESULT_YES_WITHOUT_RECONFIGURATION,
            discardReasons = 0,
            decoderName = "c2.android.avc.decoder",
            mimeType = "video/avc",
            width = 0,
            height = 0
        )
        assertTrue(line.contains(" size=-"))
    }
}
