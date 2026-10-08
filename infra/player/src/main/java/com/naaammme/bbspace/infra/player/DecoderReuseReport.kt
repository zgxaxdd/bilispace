package com.naaammme.bbspace.infra.player

/**
 * 「解码器能不能复用」的**可读化**（R5 的观测部分，也是 D1b / N1 的验收信号）。
 *
 * ## 为什么值得单独一个纯对象
 *
 * Media3 在视频输入格式变化时会给出 `DecoderReuseEvaluation`：用了哪个解码器、
 * 结论是"复用/重建"、以及**为什么不能复用**。这三个量就是"切页要不要重新起解码器"的
 * 直接答案，但它们是一堆 int：
 * - `result` 是 0..3 的枚举；
 * - `discardReasons` 是 **17 个 bit 的掩码**（`MIME_TYPE_CHANGED`、`VIDEO_RESOLUTION_CHANGED`、
 *   `RESOURCE_LIMIT`…）。
 * 不落成字面量，日志里就是一行 `result=0 discardReasons=512` —— 没人能读，也没脚本能判定。
 *
 * 判据本身在 Media3（本仓不改），本对象**只做映射**：纯函数、JVM 可测，
 * 并且配一条测试把这里写下的数值与 `androidx.media3.exoplayer.DecoderReuseEvaluation`
 * 的真实常量比对（防"升级 Media3 后 bit 位含义漂移、日志开始说谎"）。
 *
 * 调用点在 `Media3PlayerEngine` 的 `AnalyticsListener.onVideoInputFormatChanged`，
 * 只在**格式变化**那一刻触发（稳态播放零成本）。
 */
internal object DecoderReuseReport {

    // ---- `DecoderReuseEvaluation.REUSE_RESULT_*` 的同值镜像（有测试比对，漂移即红）----
    const val RESULT_NO = 0
    const val RESULT_YES_WITH_FLUSH = 1
    const val RESULT_YES_WITH_RECONFIGURATION = 2
    const val RESULT_YES_WITHOUT_RECONFIGURATION = 3

    // ---- `DecoderReuseEvaluation.DISCARD_REASON_*` 的同值镜像 ----
    const val REASON_REUSE_NOT_IMPLEMENTED = 1 shl 0
    const val REASON_WORKAROUND = 1 shl 1
    const val REASON_APP_OVERRIDE = 1 shl 2
    const val REASON_MIME_TYPE_CHANGED = 1 shl 3
    const val REASON_OPERATING_RATE_CHANGED = 1 shl 4
    const val REASON_INITIALIZATION_DATA_CHANGED = 1 shl 5
    const val REASON_MAX_INPUT_SIZE_EXCEEDED = 1 shl 6
    const val REASON_DRM_SESSION_CHANGED = 1 shl 7
    const val REASON_VIDEO_MAX_RESOLUTION_EXCEEDED = 1 shl 8
    const val REASON_VIDEO_RESOLUTION_CHANGED = 1 shl 9
    const val REASON_VIDEO_ROTATION_CHANGED = 1 shl 10
    const val REASON_VIDEO_COLOR_INFO_CHANGED = 1 shl 11
    const val REASON_AUDIO_CHANNEL_COUNT_CHANGED = 1 shl 12
    const val REASON_AUDIO_SAMPLE_RATE_CHANGED = 1 shl 13
    const val REASON_AUDIO_ENCODING_CHANGED = 1 shl 14
    const val REASON_AUDIO_BYPASS_POSSIBLE = 1 shl 15
    const val REASON_VIDEO_FRAME_RATE_CHANGED = 1 shl 16

    /** 表本身也用来生成"未知 bit"的名字，避免新增 bit 时静默丢掉。 */
    private val REASON_NAMES: List<Pair<Int, String>> = listOf(
        REASON_REUSE_NOT_IMPLEMENTED to "REUSE_NOT_IMPLEMENTED",
        REASON_WORKAROUND to "WORKAROUND",
        REASON_APP_OVERRIDE to "APP_OVERRIDE",
        REASON_MIME_TYPE_CHANGED to "MIME_TYPE_CHANGED",
        REASON_OPERATING_RATE_CHANGED to "OPERATING_RATE_CHANGED",
        REASON_INITIALIZATION_DATA_CHANGED to "INITIALIZATION_DATA_CHANGED",
        REASON_MAX_INPUT_SIZE_EXCEEDED to "MAX_INPUT_SIZE_EXCEEDED",
        REASON_DRM_SESSION_CHANGED to "DRM_SESSION_CHANGED",
        REASON_VIDEO_MAX_RESOLUTION_EXCEEDED to "VIDEO_MAX_RESOLUTION_EXCEEDED",
        REASON_VIDEO_RESOLUTION_CHANGED to "VIDEO_RESOLUTION_CHANGED",
        REASON_VIDEO_ROTATION_CHANGED to "VIDEO_ROTATION_CHANGED",
        REASON_VIDEO_COLOR_INFO_CHANGED to "VIDEO_COLOR_INFO_CHANGED",
        REASON_AUDIO_CHANNEL_COUNT_CHANGED to "AUDIO_CHANNEL_COUNT_CHANGED",
        REASON_AUDIO_SAMPLE_RATE_CHANGED to "AUDIO_SAMPLE_RATE_CHANGED",
        REASON_AUDIO_ENCODING_CHANGED to "AUDIO_ENCODING_CHANGED",
        REASON_AUDIO_BYPASS_POSSIBLE to "AUDIO_BYPASS_POSSIBLE",
        REASON_VIDEO_FRAME_RATE_CHANGED to "VIDEO_FRAME_RATE_CHANGED"
    )

    fun resultName(result: Int): String = when (result) {
        RESULT_NO -> "NO"
        RESULT_YES_WITH_FLUSH -> "YES_WITH_FLUSH"
        RESULT_YES_WITH_RECONFIGURATION -> "YES_WITH_RECONFIGURATION"
        RESULT_YES_WITHOUT_RECONFIGURATION -> "YES_WITHOUT_RECONFIGURATION"
        else -> "UNKNOWN($result)"
    }

    /**
     * 掩码 → `A|B`；0 给 `-`（而不是空串，空串在 `key=value` 里会被读成"字段缺失"）。
     *
     * 未知 bit 一律打 `BIT_<值>`：Media3 以后新增理由时这行日志**仍然完整**，
     * 而不是把"为什么没复用"的关键信息静默吃掉。
     */
    fun discardReasonNames(reasons: Int): String {
        if (reasons == 0) return "-"
        val parts = mutableListOf<String>()
        var rest = reasons
        REASON_NAMES.forEach { (bit, name) ->
            if (rest and bit != 0) {
                parts += name
                rest = rest and bit.inv()
            }
        }
        if (rest != 0) parts += "BIT_$rest"
        return parts.joinToString("|")
    }

    /**
     * 一行解剖。[evaluationPresent] = false 表示 Media3 给了 null
     * （"渲染器此前没有解码器"，即首支视频的首次配置）—— 这与"评估后决定不复用"是两件事，
     * 必须在日志里分得开，否则会把"冷启动"误读成"复用失败"。
     */
    fun line(
        evaluationPresent: Boolean,
        result: Int,
        discardReasons: Int,
        decoderName: String?,
        mimeType: String?,
        width: Int,
        height: Int
    ): String = buildString {
        append("decoder-reuse ")
        if (!evaluationPresent) {
            append("result=NO_PREVIOUS_DECODER")
        } else {
            append("result=").append(resultName(result))
            append(" discard=").append(discardReasonNames(discardReasons))
        }
        append(" decoder=").append(decoderName ?: "-")
        append(" format=").append(mimeType ?: "-")
        append(" size=").append(if (width > 0 && height > 0) "${width}x$height" else "-")
    }
}
