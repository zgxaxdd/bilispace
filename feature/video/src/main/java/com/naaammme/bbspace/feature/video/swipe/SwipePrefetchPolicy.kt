package com.naaammme.bbspace.feature.video.swipe

/**
 * 竖屏连播「相邻页预取」的**纯策略**（可 JVM 单测）。
 *
 * 对照 BiliPai（`PortraitVideoLoadPolicy.kt:331-400`）：
 * - 拖动跨过 **25%** 偏移就预取目标页（`PORTRAIT_SWIPE_PREFETCH_OFFSET_THRESHOLD`）；
 * - 停稳后按网络与开关预取 1~3 页（`resolvePortraitPlayUrlPreloadCount`）。
 *
 * bbspace 只搬这两条**判据**，不搬字节级 head 预热（那需要 media3 `SimpleCache`，
 * 属独立立项，见 `docs/ai-loop/analysis-C10-playback.md`）。
 */
object SwipePrefetchPolicy {

    /** 拖动偏移阈值：与 BiliPai 同值。跨过它说明用户"明确要往那一页去"。 */
    const val DRAG_OFFSET_THRESHOLD = 0.25f

    /**
     * 停稳后预取几页。参数与 BiliPai 一一对应：
     * - `prefetchEnabled`：设置里的"预加载下一个视频"（默认开）；
     * - `isUnmetered`：WiFi/以太网；
     * - `available`：后面还剩几页可预。
     *
     * 蜂窝网络下只有**用户开着预取开关**才预 1 条（默认行为 = 蜂窝不预取）。
     */
    fun prefetchCount(prefetchEnabled: Boolean, isUnmetered: Boolean, available: Int): Int {
        if (available <= 0) return 0
        val limit = when {
            isUnmetered && prefetchEnabled -> 3
            isUnmetered -> 2
            prefetchEnabled -> 1
            else -> 0
        }
        return minOf(available, limit)
    }

    /** 停稳页往后数 [count] 页（只向后，不向前：回看是少数路径，靠缓存自然命中）。 */
    fun targetPages(page: Int, totalPages: Int, count: Int): List<Int> {
        if (count <= 0 || totalPages <= 0 || page < 0) return emptyList()
        return (1..count).map { page + it }.filter { it in 0 until totalPages }
    }

    /**
     * 拖动中的预取目标页。
     *
     * `PagerState.currentPageOffsetFraction` 的符号约定（与 BiliPai 一致，别改错）：
     * **负值 = 正在往下翻（下一页）**、正值 = 正在往上翻（上一页），范围约 ±0.5。
     *
     * @return 需要预取的页号；没有（未跨阈值/越界）返回 null。
     */
    fun dragTargetPage(
        currentPage: Int,
        offsetFraction: Float,
        lastPageIndex: Int,
        threshold: Float = DRAG_OFFSET_THRESHOLD,
    ): Int? = when {
        offsetFraction <= -threshold -> (currentPage + 1).takeIf { it <= lastPageIndex }
        offsetFraction >= threshold -> (currentPage - 1).takeIf { it >= 0 }
        else -> null
    }
}
