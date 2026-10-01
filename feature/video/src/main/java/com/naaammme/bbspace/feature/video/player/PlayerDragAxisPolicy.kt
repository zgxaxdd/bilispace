package com.naaammme.bbspace.feature.video.player

import kotlin.math.abs

/**
 * 播放器手势的**轴仲裁**纯策略（可 JVM 单测）。
 *
 * ## 为什么要有宽限期（真机症状：斜着滑时进度条乱跳 / 详情横滑切页被吞）
 * 旧判据是"位移一过 touchSlop 就比 `abs(x) > abs(y)`"。手指落下的自然抖动往往先给出
 * 几像素的竖向位移，于是在**手势开头**就把轴向锁死：
 * 一次本意横滑的手势被判成竖向 → 亮度/音量接管或直接 None，且锁轴之后不可逆。
 *
 * BiliPai 上游正是这么修的（`5bb8728c0`，`core/ui/common/VerticalPriorityPagerGesture.kt:39-78`）：
 * 横向要**明显主导**（比值 [HORIZONTAL_DOMINANCE_RATIO]）才立刻锁横向；否则再给一段
 * 意图宽限（[AMBIGUOUS_SLOP_MULTIPLIER] × slop），超过宽限仍不构成横向主导才归竖向。
 * 竖向与斜向共用同一条宽限距离 —— 这就是"斜着滑不再被吞"的那一半。
 *
 * ⚠️ 与上游的一处**刻意不同**：上游那段是给"外层 pager vs 内层列表"用的，未决期间它不接管；
 * bbspace 这里未决期间也**不许消费事件**（见 `VideoGestureController` 的接线注释）——
 * 否则全屏连播里上下滑的起手会被播放器吃掉，比现状更糟。
 */
enum class PlayerDragIntent {
    /** 还看不出用户要横还是要竖：保持等待，既不锁轴也不消费。 */
    UNDECIDED,

    /** 明显横向主导 → 进度 seek。 */
    HORIZONTAL,

    /** 过宽限仍非横向主导 → 竖向（亮度/音量，或在连播会话里让位给 pager）。 */
    VERTICAL
}

object PlayerDragAxisPolicy {

    /** 横向"明显主导"的比值门槛，与上游同值（1.5）。 */
    const val HORIZONTAL_DOMINANCE_RATIO = 1.5f

    /** 歧义宽限 = 该倍数 × touchSlop，与上游同值（1.5）。 */
    const val AMBIGUOUS_SLOP_MULTIPLIER = 1.5f

    /**
     * @param totalX / totalY 自按下以来的**累计**位移（px，屏幕坐标，符号按原样传）
     * @param touchSlop 系统触摸阈值（`viewConfiguration.touchSlop`）
     * @param ambiguousSlopMultiplier 竖向/斜向的意图宽限倍数；传 `1f` 即**取消宽限**
     *   （位移一过 slop 就直接定方向，等于旧判据的时间点）。调用方按场景决定：
     *   连播会话给 [AMBIGUOUS_SLOP_MULTIPLIER]，详情页给 `1f`（理由见接线处注释）。
     */
    fun resolveIntent(
        totalX: Float,
        totalY: Float,
        touchSlop: Float,
        ambiguousSlopMultiplier: Float = AMBIGUOUS_SLOP_MULTIPLIER,
    ): PlayerDragIntent {
        val slop = touchSlop.coerceAtLeast(0f)
        val distanceSquared = totalX * totalX + totalY * totalY
        // 未过系统 slop：什么都不判（与旧行为一致，也不消费）
        if (distanceSquared == 0f || distanceSquared < slop * slop) return PlayerDragIntent.UNDECIDED

        val horizontal = abs(totalX)
        val vertical = abs(totalY)
        if (horizontal >= vertical * HORIZONTAL_DOMINANCE_RATIO.coerceAtLeast(1f)) {
            // 横向主导且已越过 slop → 立即锁横向（seek 不该被宽限拖慢半拍）
            return if (horizontal >= slop) PlayerDragIntent.HORIZONTAL else PlayerDragIntent.UNDECIDED
        }
        val ambiguousSlop = slop * ambiguousSlopMultiplier.coerceAtLeast(1f)
        return if (distanceSquared < ambiguousSlop * ambiguousSlop) {
            PlayerDragIntent.UNDECIDED
        } else {
            PlayerDragIntent.VERTICAL
        }
    }
}
