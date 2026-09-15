package com.naaammme.bbspace.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * 首页推荐流的手感滚动行为。
 *
 * 设计目标是**明确偏轻**：甩动跟手、惯性走得更远、收尾平滑，而不是"加阻力"。
 *
 * - [velocityScale] 1.25：进入衰减动画的初速度放大 25%，甩动更"滑"；
 * - 指数衰减摩擦系数 [HOME_FLING_FRICTION] 0.5（Compose 默认 1.0）：摩擦越小、滑行越远。
 *
 * 为什么不用 Compose 默认的 [androidx.compose.animation.core.splineBasedDecay]：
 * 默认衰减在小屏上约 1800px/s 初速度只滑 ~400px（半张单列卡），对"高信息密度瀑布流"
 * 体感偏重；指数衰减在同样初速度下可达 3000px+。
 *
 * 关键正确性：`ScrollScope.scrollBy` 只保证"最多消费传入的位移"，列表到边界（或滚动被
 * 外部接管）时会**少消费**。此时必须立即结束衰减——否则衰减动画会继续推进、反复尝试
 * 滚动（边界附近微动/抖动），并把错误的 velocity 返回给上级 scrollable，破坏嵌套滚动
 * （顶栏跟随、下拉刷新都挂在这条链路上）。
 */
class DampedFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>,
    private val velocityScale: Float = HOME_FLING_VELOCITY_SCALE
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val scaledVelocity = initialVelocity * velocityScale
        if (abs(scaledVelocity) < 1f) return 0f
        var velocityLeft = scaledVelocity
        var lastValue = 0f
        // 置位后 animateDecay 的下一帧立即结束（block 先于下一次取值执行）
        var stopped = false
        val animatable = Animatable(0f)
        animatable.animateDecay(scaledVelocity, flingDecay) {
            if (stopped) return@animateDecay
            velocityLeft = velocity
            val delta = value - lastValue
            if (delta == 0f) return@animateDecay
            val consumed = scrollBy(delta)
            if (consumed != delta) {
                // 到边界/被接管：结束衰减，不把未消费位移继续叠上去
                stopped = true
                return@animateDecay
            }
            lastValue = value
        }
        return velocityLeft
    }
}

/** 进入衰减动画的初速度倍率（>1 更滑）。 */
private const val HOME_FLING_VELOCITY_SCALE = 1.25f

/** 指数衰减摩擦系数（<1 滑得更远；Compose 默认 1.0）。 */
private const val HOME_FLING_FRICTION = 0.5f

/**
 * 首页推荐流专用滚动行为。
 */
@Composable
fun rememberHomeFlingBehavior(): FlingBehavior {
    return remember {
        DampedFlingBehavior(
            flingDecay = exponentialDecay(frictionMultiplier = HOME_FLING_FRICTION),
            velocityScale = HOME_FLING_VELOCITY_SCALE
        )
    }
}
