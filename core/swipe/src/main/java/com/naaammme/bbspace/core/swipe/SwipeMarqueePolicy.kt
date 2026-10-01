package com.naaammme.bbspace.core.swipe

/**
 * 单行文案的**跑马灯**判据与节拍（任务书 SW-32）。
 *
 * 用在哪：只用在**单行**且会超宽的地方（会话底部的 `@UP`）。两行标题不做跑马灯 ——
 * 折行后的滚动读起来更晕，且与"点标题展开全文"的交互打架（见 SW-21）。
 *
 * 为什么要单独抽策略：滚动是"永远在动"的东西，一不小心就变成"晃"与耗电：
 * - 只在**真的超宽**时滚（判据来自 `TextLayoutResult.hasVisualOverflow` + 这里）；
 * - 一次循环 = 停留 → 滚到末尾 → 再停留 → 回到起点（两端都有停顿，读得清）；
 * - `reduceMotion` 时**不滚**（无障碍纪律）；
 * - 用 `Animatable` 驱动（**不新增 `rememberInfiniteTransition`**，守帧预算棘轮）。
 */
object SwipeMarqueePolicy {

    /** 两端各停留多久（毫秒）：留给"看清开头/结尾"。 */
    const val HOLD_MS = 900L

    /** 滚动速度（像素/秒）：慢到能读。 */
    const val SPEED_PX_PER_SEC = 28f

    /** 单程时长夹取范围：太快读不清、太慢像没动。 */
    const val MIN_TRAVEL_MS = 600L
    const val MAX_TRAVEL_MS = 6_000L

    /** 是否需要滚动：文本比容器宽（且非空、非降级、非 NaN）。 */
    fun shouldMarquee(textWidthPx: Float, containerWidthPx: Float, reduceMotion: Boolean): Boolean {
        if (reduceMotion) return false
        if (textWidthPx.isNaN() || containerWidthPx.isNaN()) return false
        if (containerWidthPx <= 0f || textWidthPx <= 0f) return false
        return textWidthPx > containerWidthPx
    }

    /** 滚动距离（像素，正数）= 文本宽 - 容器宽；不超宽返回 0。 */
    fun travelDistancePx(textWidthPx: Float, containerWidthPx: Float): Float {
        val overflow = textWidthPx - containerWidthPx
        if (overflow <= 0f || overflow.isNaN()) return 0f
        return overflow
    }

    /** 单程滚动时长（毫秒）：由距离与速度决定并夹取。 */
    fun travelDurationMs(distancePx: Float): Long {
        if (distancePx <= 0f || distancePx.isNaN()) return MIN_TRAVEL_MS
        val ms = (distancePx / SPEED_PX_PER_SEC * 1000f).toLong()
        return ms.coerceIn(MIN_TRAVEL_MS, MAX_TRAVEL_MS)
    }

    /**
     * 一次完整循环的总时长（停留 → 单程 → 停留 → 回程）。
     * 回程用**同一时长**：匀速往返；快慢不一的往返会被读作"抽动"。
     */
    fun fullCycleMs(distancePx: Float): Long {
        val travel = travelDurationMs(distancePx)
        return HOLD_MS + travel + HOLD_MS + travel
    }
}
