package com.naaammme.bbspace.core.designsystem.component

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
 * 回前台入场动画（ColorOS 设计语言：内容自 ~96% 缩放 + 透明度淡入，强调曲线）。
 *
 * 应用退到后台（后台播放下音频继续）再回到前台时，系统只是原样把窗口摆回来，
 * 没有任何视觉衔接——包一层 [ForegroundEnterBox] 后，真回前台的那次组合会播放
 * 一次性的 fade+scale 入场，与页面转场同一套时长/曲线 token（经 [LocalAnimations]）。
 *
 * 触发判据用 ProcessLifecycleOwner 的 ON_START：它只在「整个应用回到前台」时
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

    LaunchedEffect(enterGen, anim.medium) {
        if (enterGen == 0 || anim.medium <= 1) return@LaunchedEffect
        alpha.snapTo(0f)
        scale.snapTo(0.96f)
        // 页面级入场：长时长 + 强调曲线，与导航转场同一语言（见 OPPO 动效规范）
        val spec = tween<Float>(durationMillis = anim.long, easing = anim.emphasized)
        launch { alpha.animateTo(1f, spec) }
        scale.animateTo(1f, spec)
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value
            scaleY = scale.value
        },
        content = content
    )
}

private fun animatorDurationScale(context: android.content.Context): Float {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)
}
