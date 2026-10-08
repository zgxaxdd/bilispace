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

/**
 * @param systemReduceMotion 系统级「减弱/移除动效」（`ANIMATOR_DURATION_SCALE == 0`）。
 *   移植自 BiliPai 的 `readSystemReduceMotion`（见 [rememberSystemReduceMotion]）。
 *
 *   修复前本仓**只有 `ForegroundEnterBox` 一处**读了系统动画缩放，其余全部设计系统组件
 *   只认 App 内的「动效速度」设置 —— 也就是说：用户在系统里开了「移除动画」，
 *   bbspace 的粒子消散、转场、骨架呼吸照旧播。这与无障碍设置冲突。
 *
 *   **两处任一为真即降级**：用户表达"不要动画"的意图，App 内设置与系统设置等价。
 *   只动 `reduceMotion` 标志，**不动时长** —— 时长口径保持由 App 设置单独决定，
 *   免得一次改动同时改变所有动画的节奏（那是另一个话题）。
 */
fun createAnimations(speed: AnimationSpeed, systemReduceMotion: Boolean = false): BiliAnimations {
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
        reduceMotion = m == 0f || systemReduceMotion
    )
}

@Composable
fun ProvideAnimations(
    speed: AnimationSpeed,
    content: @Composable () -> Unit
) {
    // 系统「移除动画」实时跟随（ContentObserver），与 App 内设置取或
    val systemReduceMotion = rememberSystemReduceMotion()
    val anim = remember(speed, systemReduceMotion) {
        createAnimations(speed, systemReduceMotion)
    }
    CompositionLocalProvider(
        LocalAnimations provides anim,
        content = content
    )
}
