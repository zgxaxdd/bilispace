package com.naaammme.bbspace.core.swipe

/**
 * 翻页跟手与**出页动效**的纯策略（任务书 SW-10）。
 *
 * ## 现状与目标
 * `VerticalPager` 本身已是 1:1 跟手（无需改造）；缺的是"带走感"：
 * 出页/入页除了位移之外还有**轻微缩放与淡出**，以及**越界阻尼**
 * （滑到最后一支还继续上滑时，画面不该硬顶住）。两条都由本策略给数值，
 * 调用方只在 `graphicsLayer` 里读（零重组、不进 draw 之外）。
 *
 * ## 幅度纪律（任务书 SW-10 风险项）
 * 用户对"晃"很敏感：缩放幅度与 `SwipeHandoffTiming.SETTLE_SCALE`（2%）同量级，
 * 淡出最多到 [MIN_ALPHA]；`reduceMotion`（无障碍"关闭动画"）下退化为**无变换**。
 */
object SwipePageTransformPolicy {

    /** 页面离位最大缩放差（与交接落定缩放同量级：2% 以内不会被读作"变焦"）。 */
    const val MAX_SCALE_DELTA = SwipeHandoffTiming.SETTLE_SCALE - 1f

    /** 离位最远处的最低不透明度（仍要看得清是"同一支视频的另一帧"）。 */
    const val MIN_ALPHA = 0.72f

    /** 越界位移的阻尼系数（手指走 1px，画面只走 0.35px）。 */
    const val OVERSCROLL_DAMPING = 0.35f

    /** 越界位移上限（像素）：到顶后不再跟手，避免"整屏被推走"。 */
    const val MAX_OVERSCROLL_PX = 48f

    /** 一页的绘制变换。 */
    data class Transform(
        val scale: Float,
        val alpha: Float,
        /** 越界时的纵向位移（像素，只由 [overScrollTranslation] 产生）。 */
        val translationYPx: Float = 0f,
    ) {
        companion object {
            val None = Transform(scale = 1f, alpha = 1f)
        }
    }

    /**
     * 由"离落定位置的距离"算绘制变换。
     *
     * @param offsetFraction `pager` 页面偏移：0 = 正中；±1 = 整页离位（更大值夹取）。
     * @param reduceMotion 无障碍降级：返回 [Transform.None]（顺序不动、只去动画）。
     */
    fun transformFor(offsetFraction: Float, reduceMotion: Boolean = false): Transform {
        if (reduceMotion) return Transform.None
        if (offsetFraction.isNaN()) return Transform.None
        val distance = kotlin.math.abs(offsetFraction).coerceIn(0f, 1f)
        return Transform(
            scale = 1f - MAX_SCALE_DELTA * distance,
            alpha = 1f - (1f - MIN_ALPHA) * distance
        )
    }

    /**
     * 越界位移（SW-10 的"阻尼"）：把手指多走的位移按 [OVERSCROLL_DAMPING] 折减并封顶。
     * 纯函数：调用方负责把"越界原始位移"喂进来（由 nestedScroll 的未消费纵向位移得到），
     * 以及松手后让它回到 0。
     */
    fun overScrollTranslation(
        rawOverScrollPx: Float,
        damping: Float = OVERSCROLL_DAMPING,
        maxPx: Float = MAX_OVERSCROLL_PX,
    ): Float {
        if (rawOverScrollPx.isNaN() || damping <= 0f || maxPx <= 0f) return 0f
        return (rawOverScrollPx * damping).coerceIn(-maxPx, maxPx)
    }

    /** 由"原始越界位移 + 阻尼"合成一页的最终变换（页内偏移与越界一起喂）。 */
    fun transformWithOverScroll(
        offsetFraction: Float,
        rawOverScrollPx: Float,
        reduceMotion: Boolean = false,
    ): Transform {
        val base = transformFor(offsetFraction, reduceMotion)
        if (reduceMotion) return base
        return base.copy(translationYPx = overScrollTranslation(rawOverScrollPx))
    }
}
