package com.naaammme.bbspace.core.designsystem.component

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import kotlinx.coroutines.launch

/**
 * 回前台入场动画（ColorOS 设计语言：内容轻微放大 + 透明度淡入，强调曲线）。
 *
 * ## 2026-10-12 重做（用户反馈「每次软件挂后台一段时间，打开软件会闪」）
 *
 * 上一版在回前台时先 `alpha.snapTo(0f)` + `scale.snapTo(0.96f)`，再用 300ms 淡回 1f。
 * **`snapTo` 是瞬跳、不是过渡**：系统刚把窗口摆回屏幕的那一帧，整棵 Compose 树立刻
 * 变成全透明、缩到 96%，然后再长回来。这一瞬"整屏内容消失"就是用户看到的"闪"——
 * 它**不是 UI 重建**，而是这段动画本身的起点。
 *
 * 三处叠加在一起才让它读成"闪"：
 * 1. **起点是硬编码的 0**（全透明）。取 0 的结果是"先消失、再出现"，而不是"浮现"；
 * 2. **整棵树参与**：`graphicsLayer` 包住全部 content，连各页面自己铺的底色一起淡出，
 *    所以那一帧真的什么都没有；
 * 3. **本仓 `Theme.Bbspace` 的 `android:windowBackground` 是 `@null`**（见
 *    `app/src/main/res/values/themes.xml`）。窗口没有兜底底色 ⇒ 内容透明时露出的不是
 *    "另一种颜色"，而是未定义像素。这一条决定了**任何 alpha<1 都必须有垫底**。
 *
 * 这一版：
 * - 起点改为**不透明度 [FOREGROUND_ENTER_ALPHA_FROM]（0.88）**、缩放
 *   [FOREGROUND_ENTER_SCALE_FROM]（0.99）—— 幅度小到"读得出动感、读不出闪烁"；
 * - 动画层外面**垫一层主题背景色**（不参与动画），这样 alpha<1 时透出的是页面底色
 *   而不是未定义像素，且与内容自身底色同源，观感是"轻轻浮现"而非"闪一下"；
 * - 时长用 `anim.medium`（200ms）而不是 `long`（300ms）：回前台是**被动恢复**，
 *   不该比主动导航（300ms）还慢。
 *
 * 触发判据仍是 ProcessLifecycleOwner 的 ON_START：它只在「整个应用回到前台」时
 * 派发。详情 Activity 与 MainActivity 之间的切换不会让进程离开前台，因此不会
 * 误触发（Activity 自己的 ON_RESUME 做不到这一点）。冷启动没有前置 ON_STOP，
 * 不动画——冷启动已有系统窗口动画。
 *
 * 尊重系统「移除动画/动画时长缩放」无障碍设置（scale=0 时直接呈现，不动画），
 * 同时遵循应用内动画速度设置（动画速度设为关闭时不播放）。
 */
@Composable
fun ForegroundEnterBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val anim = LocalAnimations.current
    val context = LocalContext.current
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    var enterGen by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        var backgrounded = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgrounded = true
                Lifecycle.Event.ON_START -> {
                    if (backgrounded && animatorDurationScale(context) > 0f) {
                        enterGen++
                    }
                    backgrounded = false
                }

                else -> Unit
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enterGen, anim.medium, anim.reduceMotion) {
        // `reduceMotion` 必须一起判：`anim.medium <= 1` 只在"动效速度=关"时成立，
        // 而系统级「移除动画」只把 reduceMotion 置真、**不动时长**
        // （见 `createAnimations`）。上一版漏了这条 ⇒ 在系统里开了"移除动画"的
        // 用户回前台照样被闪一下，而这恰恰是无障碍设置要避免的事。
        if (enterGen == 0 || anim.medium <= 1 || anim.reduceMotion) return@LaunchedEffect
        alpha.snapTo(FOREGROUND_ENTER_ALPHA_FROM)
        scale.snapTo(FOREGROUND_ENTER_SCALE_FROM)
        // 回前台入场：中等时长 + 强调曲线，与导航转场同一语言（见 OPPO 动效规范）
        val spec = tween<Float>(durationMillis = anim.medium, easing = anim.emphasized)
        launch { alpha.animateTo(1f, spec) }
        scale.animateTo(1f, spec)
    }

    // 外层只负责**垫底**（不参与动画）：见 KDoc 第 3 条 —— 窗口 background 是 @null，
    // 没有这层垫底时 alpha<1 会露出未定义像素。
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
            content = content
        )
    }
}

/**
 * 回前台入场的起点：**不是 0**。
 *
 * 0.88 / 0.99 这组值的目标是"读得出动感、读不出闪烁"：任何一帧都还能看清内容。
 * 取 0 会变成"整屏消失再出现"（用户报的"闪"），取 1 又等于没有动效。
 */
private const val FOREGROUND_ENTER_ALPHA_FROM = 0.88f
private const val FOREGROUND_ENTER_SCALE_FROM = 0.99f

private fun animatorDurationScale(context: android.content.Context): Float {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)
}
