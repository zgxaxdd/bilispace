package com.naaammme.bbspace.core.swipe

/**
 * 会话内**可拖进度条**的纯判据（任务书 SW-15）。
 *
 * 背景：常驻细进度条原先只是指示器（守卫明确"不许 pointerInput/clickable，它不能吃掉手势"）。
 * 任务书 B-3 把它列为差距：竖屏连播里用户想拖到某处却没有任何入口。
 * 本次反转该不变式，但用三条硬约束把风险压到最低：
 * 1. **热区限定**：只有落在这条细线周围 [HOT_ZONE_FRACTION] 屏高带内的手势才参与判定；
 * 2. **只认横向**：判据只服务横向拖动（纵向永远让位给翻页 pager——调用方用
 *    横向 drag 检测器实现，纵向事件根本不消费）；
 * 3. **拖动中暂停自动翻页**（[isScrubbing] 由 UI 状态喂进
 *    [SwipeAutoAdvancePolicy.shouldAutoAdvanceToNextSwipePage]）。
 *
 * 纯函数 + 调用方给钟：可 JVM 单测（热区边界、位置映射与夹取、提交节流）。
 */
object SwipeSeekPolicy {

    /** 热区带高：细线视觉高度只有屏高的 0.35%，但触摸带给到 6%（包住 ≥48dp 目标）。 */
    const val HOT_ZONE_FRACTION = 0.06f

    /**
     * 触摸点是否落在进度条热区内（屏底部往上 [HOT_ZONE_FRACTION] 的带；坐标为屏高比例 0..1）。
     * 越界/非法值一律 false（宁可不出 seek，不与翻页抢手势）。
     */
    fun isInHotZone(
        touchYFraction: Float,
        hotZoneFraction: Float = HOT_ZONE_FRACTION,
    ): Boolean {
        if (touchYFraction < 0f || touchYFraction > 1f) return false
        if (hotZoneFraction <= 0f || hotZoneFraction > 1f) return false
        return touchYFraction >= 1f - hotZoneFraction
    }

    /**
     * 横向位置（0..1）→ 目标播放位置（毫秒，夹在 [0, durationMs]）。
     * 时长未就绪（<= 0）返回 null（不生成 seek 目标）。
     */
    fun positionForFraction(fraction: Float, durationMs: Long): Long? {
        if (durationMs <= 0L) return null
        if (fraction.isNaN()) return null
        return (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /**
     * 拖动中的**提交节流**：拖动期间只更新本地位置，拖手才真提交（任务书要求④）。
     * 另外给一条保险：拖动中若距上次本地刷新不足 [LOCAL_UPDATE_MIN_INTERVAL_MS]，
     * 连本地位置都不必重画（高刷屏上逐像素重画是纯浪费）。
     */
    fun shouldCommitOnRelease(isScrubbing: Boolean): Boolean = isScrubbing

    /** 拖动中的本地位置刷新最小间隔。 */
    const val LOCAL_UPDATE_MIN_INTERVAL_MS = 60L

    fun shouldRefreshLocalPosition(nowMs: Long, lastRefreshMs: Long?): Boolean {
        val last = lastRefreshMs ?: return true
        val delta = nowMs - last
        return delta < 0L || delta >= LOCAL_UPDATE_MIN_INTERVAL_MS
    }

    /** 拖动中显示的时间气泡文案（当前目标位置 mm:ss）；时长未知返回 "--:--"。 */
    fun bubbleText(positionMs: Long, durationMs: Long): String {
        fun fmt(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0L)) / 1000
            val minutes = totalSec / 60
            val seconds = totalSec % 60
            return "%d:%02d".format(minutes, seconds)
        }
        if (durationMs <= 0L) return "--:--"
        return fmt(positionMs) + " / " + fmt(durationMs)
    }
}
