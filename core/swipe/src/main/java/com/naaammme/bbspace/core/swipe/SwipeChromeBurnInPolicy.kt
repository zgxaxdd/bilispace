package com.naaammme.bbspace.core.swipe

/**
 * 会话内**常驻控件**的防烧屏策略（用户要求，2026-10-01）。
 *
 * 常驻控件（右侧互动栏、底部信息层、常驻细进度条）在连播里位置几乎不动，
 * OLED 上长时间静止会烧屏。两条正交的手段：
 * 1. **静置变暗**：无交互 [IDLE_DIM_AFTER_MS] 后把常驻层整体压到 [DIM_ALPHA]
 *    （不隐藏——短视频里"控件消失"会让用户以为坏了，见用户反馈"消失再出现"），
 *    任何翻页/交互立即回满；
 * 2. **缓慢像素漂移**：每 [DRIFT_PERIOD_MS] 在三档位移（0 / ±[DRIFT_STEP_PX]）之间
 *    换一格，x/y 取不同相位形成 3×3 游走——单次位移在视觉阈值以下（不可感），
 *    但足以让像素不长期压在同一个发光点上。
 *
 * 纯函数 + 调用方给钟：可 JVM 单测（阈值边界、相位确定性、负数/时钟回拨防御）。
 */
object SwipeChromeBurnInPolicy {

    /** 无交互多久后开始变暗。 */
    const val IDLE_DIM_AFTER_MS = 15_000L

    /** 静置时的亮度（不隐藏：控件"消失"是另一类体验问题）。 */
    const val DIM_ALPHA = 0.55f

    /** 漂移换格周期。 */
    const val DRIFT_PERIOD_MS = 60_000L

    /** 单档位移（像素）：低于视觉阈值、高于 OLED 像素尺寸。 */
    const val DRIFT_STEP_PX = 2f

    fun isIdle(
        nowMs: Long,
        lastInteractionMs: Long,
        idleAfterMs: Long = IDLE_DIM_AFTER_MS,
    ): Boolean {
        val idle = nowMs - lastInteractionMs
        // 时钟回拨（负差值）按"刚交互过"处理，不误判成静置
        return idle >= idleAfterMs && idle >= 0L
    }

    /** 常驻层目标透明度：静置 [DIM_ALPHA]，否则 1。 */
    fun chromeAlpha(
        nowMs: Long,
        lastInteractionMs: Long,
        idleAfterMs: Long = IDLE_DIM_AFTER_MS,
    ): Float = if (isIdle(nowMs, lastInteractionMs, idleAfterMs)) DIM_ALPHA else 1f

    /**
     * 漂移位移（像素），x/y 各取三档相位之一：
     * `x 相位 = cycle % 3`、`y 相位 = (cycle / 3) % 3`，cycle = now / period（向下取整）。
     * 输出恒在 `[-DRIFT_STEP_PX, +DRIFT_STEP_PX]`；同一 [DRIFT_PERIOD_MS] 内恒定（不会逐帧抖动）。
     */
    fun driftOffsetPx(
        nowMs: Long,
        periodMs: Long = DRIFT_PERIOD_MS,
        stepPx: Float = DRIFT_STEP_PX,
    ): Pair<Float, Float> {
        if (periodMs <= 0L) return 0f to 0f
        val cycle = Math.floorDiv(nowMs.coerceAtLeast(0L), periodMs)
        return slot(cycle % 3) * stepPx to slot((cycle / 3) % 3) * stepPx
    }

    /** 三档序列：0 → -1 → +1（乘积后即 0 / -step / +step）。 */
    private fun slot(index: Long): Int = when (index) {
        1L -> -1
        2L -> 1
        else -> 0
    }
}
