package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf

@Immutable
data class BiliAnimations(
    val short: Int,
    val medium: Int,
    val long: Int,
    val extraLong: Int,
    // iOS morph 缓动族（方案 3.2 #2，移植自 BiliPai iosMorphTween 280ms）：
    // 供胶囊 / 底栏滑块 / 弹层共用，与既有档位并存而非替换。
    val morph: Int,
    // [morph] 时长配套的曲线：BiliPai `iosMorphTween` 用的是 Apple/SwiftUI
    // easeInOut 单位曲线（0.42,0,0.58,1），与 Material 的 emphasized 不是同一条。
    val morphEasing: Easing,
    val emphasized: Easing,
    val standard: Easing,
    // 无障碍降级（方案 3.2.5）：AnimationSpeed.OFF 时为 true，位移/形变类动画
    // 退化为 160ms 纯 fade
    val reduceMotion: Boolean = false
)

private val IosEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

val LocalAnimations = compositionLocalOf {
    BiliAnimations(
        short = 100,
        medium = 200,
        long = 300,
        extraLong = 400,
        morph = 280,
        morphEasing = IosEaseInOut,
        emphasized = EmphasizedEasing,
        standard = StandardEasing
    )
}

private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val StandardEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

fun createAnimations(speed: AnimationSpeed): BiliAnimations {
    val m = speed.multiplier
    // OFF 时用 1ms 而不是 0，避免 Compose 动画边界问题
    val factor = if (m == 0f) 0.001f else m
    return BiliAnimations(
        short = (100 * factor).toInt().coerceAtLeast(1),
        medium = (200 * factor).toInt().coerceAtLeast(1),
        long = (300 * factor).toInt().coerceAtLeast(1),
        extraLong = (400 * factor).toInt().coerceAtLeast(1),
        morph = (280 * factor).toInt().coerceAtLeast(1),
        morphEasing = IosEaseInOut,
        emphasized = EmphasizedEasing,
        standard = StandardEasing,
        reduceMotion = m == 0f
    )
}

@Composable
fun ProvideAnimations(
    speed: AnimationSpeed,
    content: @Composable () -> Unit
) {
    val anim = remember(speed) { createAnimations(speed) }
    CompositionLocalProvider(
        LocalAnimations provides anim,
        content = content
    )
}
