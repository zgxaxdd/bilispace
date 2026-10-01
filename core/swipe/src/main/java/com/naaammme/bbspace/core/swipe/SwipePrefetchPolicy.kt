package com.naaammme.bbspace.core.swipe

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

    /**
     * 蜂窝网络下要不要**顺带预热详情**（BiliPai `a4b8c17f8` 新增的那一层闸门）。
     *
     * 上游把「CDN 预取」与「下一页头部预取」都加了 Wi-Fi 门控 —— 即使
     * [prefetchCount] 在"用户开着预取开关"时允许蜂窝预 1 条，
     * **头部/详情级别的重预热**仍然直接跳过。理由照搬到 bbspace：
     * 一次 `fetchVideoDetail` 是**完整的 gRPC 详情响应**（分P、剧集、推荐位、UP 信息），
     * 比 playurl 贵一个量级，而它服务的是"用户还没滑到的那一页"的左下角标题。
     * 预热的收益在 WiFi 下几乎免费，在蜂窝下就是拿用户的流量换一次"翻过去不空一拍"。
     *
     * 注意这只关掉**预热**：真正开播时详情照常请求（走 `StreamPlaybackSessionImpl`
     * 的并发路径），所以蜂窝下的功能面没有任何收缩。
     */
    fun shouldWarmDetail(isUnmetered: Boolean): Boolean = isUnmetered

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
