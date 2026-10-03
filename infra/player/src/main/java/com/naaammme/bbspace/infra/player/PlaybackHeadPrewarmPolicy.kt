package com.naaammme.bbspace.infra.player

/**
 * 起播头部预热的**判据与区间**（PB-04，纯函数，JVM 可测）。
 *
 * 语义移植自上游 `PortraitVideoLoadPolicy.kt:35-36`（1.5MiB 视频 + 256KiB 音频）
 * 与 `:409-439`（**仅 Wi-Fi**）。
 *
 * ## 为什么"仅 Wi-Fi"
 * 一次预热是 1.5MiB + 256KiB 的**真实下载**（预取是给"下一页"用的，用户在蜂窝下
 * 未必真会滑过去）。bbspace 已有同口径先例：`SwipePrefetchPolicy.shouldWarmDetail`
 * 把"一整份详情"挡在计量网络之外；头部预热比详情便宜，但性质相同 → 同一道闸门。
 *
 * ## 单一定义点
 * 两个大小常量只在这里定义（验收 5：不许第二处）。
 */
internal object PlaybackHeadPrewarmPolicy {

    /** 视频头部预热长度：1.5 MiB（上游 `PortraitVideoLoadPolicy.kt:35`）。 */
    const val VIDEO_HEAD_BYTES = 1_572_864L

    /** 音频头部预热长度：256 KiB（上游同处）。 */
    const val AUDIO_HEAD_BYTES = 262_144L

    /** 只在**非计量**网络（Wi-Fi/以太网）预热。 */
    fun shouldPrewarm(isUnmetered: Boolean): Boolean = isUnmetered

    /**
     * 预热目标列表：视频 + 音频各一段（各自可能缺省）。
     *
     * 顺序固定为 视频 → 音频：起播卡在视频上的概率更高（音频小得多），
     * 万一被取消也先拿到收益更大的那一段。
     */
    fun targets(videoUrl: String?, audioUrl: String?, isUnmetered: Boolean): List<PrewarmTarget> {
        if (!shouldPrewarm(isUnmetered)) return emptyList()
        return buildList {
            videoUrl?.takeIf(String::isNotBlank)?.let { add(PrewarmTarget(it, VIDEO_HEAD_BYTES)) }
            audioUrl?.takeIf(String::isNotBlank)?.let { add(PrewarmTarget(it, AUDIO_HEAD_BYTES)) }
        }
    }

    data class PrewarmTarget(val url: String, val lengthBytes: Long)
}
