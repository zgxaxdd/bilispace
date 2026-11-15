package com.naaammme.bbspace.core.designsystem.motion

import androidx.compose.animation.core.FloatSpringSpec
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 指示器形变速度的弹簧驱动（W3 · A 级纯逻辑）。
 *
 * ## 上游对应物
 * `design-system/.../ui/animation/LiquidIndicatorVelocity.kt`：上游用
 * `withFrameNanos` 起一个协程 worker，逐帧把 spring 值推进到目标。
 *
 * ## 本仓为什么不搬帧循环
 * 1. 帧循环依赖 Compose 的 `MonotonicFrameClock`，**JVM 单测里没有时钟** ——
 *    搬过来就变成"只能靠真机看"的代码，违反本批"纯逻辑 + JVM 单测"的要求；
 * 2. 本仓的消费面（Dock 指示器）在 `app` 层，接线是下一轮的事。
 *
 * 因此这里只保留**可确定性推进**的内核：调用方给"这一帧过了多少纳秒"，
 * 它返回推进后的（值, 速度）。这样：
 * - 单测可以精确断言收敛/单调/吸附；
 * - 接线方既可以在 `withFrameNanos` 里驱动，也可以在 `Animatable` 的
 *   `updateBounds` 回调里驱动 —— 不绑死任何一种时钟。
 *
 * 状态语义与上游一致：`target` 是**期望值**，`value/rate` 是当前弹簧状态；
 * `snapTo` 是"立刻到位"（拖拽跟手阶段用它，避免弹簧拖后腿）。
 */
class LiquidIndicatorVelocity(
    private val spec: FloatSpringSpec = LiquidIndicatorMotion.velocitySpec()
) {

    /** 当前形变值（接线方通常把它当 `velocity` 喂给 [LiquidIndicatorMotion.scaleX]）。 */
    var value: Float = 0f
        private set

    /** 当前变化率（内部量；诊断与测试用）。 */
    var rate: Float = 0f
        private set

    private var target: Float = 0f

    /**
     * 更新期望值。非有限输入忽略 —— 一次 NaN 会让 spring 永远收敛不了
     * （上游注释里点过：速度形变是非对称的，残留会留下椭圆残影）。
     */
    fun updateTarget(next: Float) {
        if (!next.isFinite()) return
        target = next
    }

    /** 期望值（测试/诊断用）。 */
    fun currentTarget(): Float = target

    /**
     * 推进一帧。
     *
     * @param elapsedNanos 距上一帧的纳秒数（<=0 时原样返回当前值，不推进）
     * @return 推进后的 [value]
     */
    fun advance(elapsedNanos: Long): Float {
        if (elapsedNanos <= 0L || !value.isFinite() || !rate.isFinite()) return value
        val initialValue = value
        val initialRate = rate
        value = spec.getValueFromNanos(elapsedNanos, initialValue, target, initialRate)
        rate = spec.getVelocityFromNanos(elapsedNanos, initialValue, target, initialRate)
        if (isSettled()) {
            value = target
            rate = 0f
        }
        return value
    }

    /** 立刻到位（拖拽跟手阶段用）：同时把 rate 归零，避免松手后残留速度。 */
    fun snapTo(next: Float) {
        if (!next.isFinite()) return
        target = next
        value = next
        rate = 0f
    }

    /** 冻结在当前值（例如页面不可见时）。 */
    fun freeze() = snapTo(value)

    /** 是否已收敛（值与变化率都在容差内）。 */
    fun isSettled(): Boolean =
        abs(value - target) <= SettleDistance && abs(rate) <= settleRateThreshold

    private val settleRateThreshold: Float = SettleDistance * sqrt(spec.stiffness)

    private companion object {
        /** 收敛距离容差：0.01 ≈ 形变系数上的 1%，肉眼不可辨。 */
        const val SettleDistance = 0.01f
    }
}
