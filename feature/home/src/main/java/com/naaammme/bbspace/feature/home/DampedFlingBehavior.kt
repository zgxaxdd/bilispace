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
 * 阻尼更轻、滑行更丝滑的滚动行为：
 * - 初速度不折减（×1.0），甩动跟手；
 * - 指数衰减摩擦系数 0.8（默认 1.0），惯性滑行更长更顺滑，减速曲线柔和无生硬感。
 */
class DampedFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>,
    private val velocityScale: Float = 1f
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val scaledVelocity = initialVelocity * velocityScale
        if (abs(scaledVelocity) < 1f) return 0f
        var velocityLeft = scaledVelocity
        var lastValue = 0f
        var isRunning = true
        val animatable = Animatable(0f)
        animatable.animateDecay(scaledVelocity, flingDecay) {
            if (!isRunning) {
                return@animateDecay
            }
            val delta = value - lastValue
            val consumed = scrollBy(delta)
            lastValue = value
            velocityLeft = velocity
            if (abs(consumed - delta) > 0.5f) {
                isRunning = false
            }
        }
        return if (velocityLeft == scaledVelocity) scaledVelocity else velocityLeft
    }
}

/**
 * 首页推荐流专用滚动行为。
 */
@Composable
fun rememberHomeFlingBehavior(): FlingBehavior {
    return remember {
        DampedFlingBehavior(
            flingDecay = exponentialDecay(frictionMultiplier = 0.8f),
            velocityScale = 1f
        )
    }
}
