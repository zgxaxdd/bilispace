package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations

/**
 * 列表/内容块的**首次出现**入场动效：轻微上移 + 淡入。
 *
 * 为什么需要：
 * - 项目已有完整动效 token（[LocalAnimations]：时长随用户「动画速度」设置缩放，含
 *   emphasized/standard 缓动），但全仓只有 5 处在用，绝大多数界面是「瞬间出现」，
 *   观感偏生硬。
 * - 与其在各页面各写一套 `animateFloatAsState`，不如提供这一个 token 驱动的入口，
 *   让所有入场动效共用同一时长/缓动，避免「每页动画快慢不一」。
 *
 * 关键取舍（性能优先，不动滚动流畅度）：
 * - **只在首次组合时播一次**，之后直出。LazyGrid 滚出屏幕会销毁条目、滚回来会重播，
 *   所以调用方**必须**用 [enabled] 把动效限制在「首屏那批」；否则快速滚动时每行都在
 *   做动画，正是掉帧的典型来源。
 * - 用 `layout` 做位移而不是 `offset`：位移只影响摆放、不改测量结果，动画过程中不会
 *   触发重新测量。
 * - 时长/缓动取自 [LocalAnimations]：用户把动画速度设为「关闭」时时长退化为 1ms，
 *   等价于直出，无需额外分支。
 *
 * @param enabled 是否允许播放入场动效。滚动容器里请用「索引 < N」控制，例如
 *   `enabled = index < 10`。
 * @param initialOffsetPx 起始上移距离（px）。默认 18px，看得见但不夸张。
 */
@Composable
fun AnimatedItemAppearance(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    initialOffsetPx: Float = 18f,
    content: @Composable () -> Unit
) {
    val anim = LocalAnimations.current
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (enabled) entered = true
    }

    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = anim.medium, easing = anim.emphasized),
        label = "itemAppearance"
    )

    Box(
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            // 位移只作用于摆放：不改变测量尺寸，因此不会引起重新测量
            val shift = ((1f - progress) * initialOffsetPx).toInt()
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, shift)
            }
        }.alpha(progress)
    ) {
        content()
    }
}
