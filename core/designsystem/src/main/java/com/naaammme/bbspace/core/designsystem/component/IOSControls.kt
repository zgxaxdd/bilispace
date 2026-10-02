package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import kotlin.math.roundToInt

/**
 * iOS 风格开关（方案 2.3）：51×31dp 轨道、27dp 圆形拇指带细微投影、
 * 开启态取 tint（primary），状态切换走 spring。
 * 仅 iOS 界面风格下使用；Material 模式继续用 M3 Switch。
 */
@Composable
fun IOSSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val trackOn = MaterialTheme.colorScheme.primary
    val trackOff = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumbColor = Color.White
    val density = LocalDensity.current
    // 用 px（Float）而不是 Dp 驱动动画：Animatable 要 VectorConverter，Float 的是现成的
    // （CoverExpand 的 progress 同款），且顺带去掉动画期间每帧一个新的 Dp 对象。
    val thumbTravelPx = remember(density) { with(density) { 20.dp.roundToPx() } }.toFloat()
    // **初值必须取当前状态，不能写 0f**。这是 animateXAsState → Animatable 换写法时
    // 最容易踩的坑：animateDpAsState 在首次组合时是**直接吸附**到 targetValue 的
    // （不播动画），而 Animatable 会**从初值动画过去**。写成 0f 的话，设置页里那些
    // "默认就是开"的开关会在首帧把滑块从左滑到右 —— 一屏开关同时滑一遍。
    // 下面 LaunchedEffect 首次也会跑，但目标值与初值相同，animateTo 立即完成。
    val thumbOffsetPx = remember { Animatable(if (checked) thumbTravelPx else 0f) }
    LaunchedEffect(checked, thumbTravelPx) {
        thumbOffsetPx.animateTo(
            targetValue = if (checked) thumbTravelPx else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(51.dp)
            .height(31.dp)
            .alpha(if (onCheckedChange == null) 0.4f else 1f)
            .clip(RoundedCornerShape(50))
            .background(if (checked) trackOn else trackOff)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = onCheckedChange != null,
                role = Role.Switch
            ) {
                onCheckedChange?.invoke(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                // 布局期读偏移：`offset(x = )` 读的是组合期的动画值，开关每次切换的
                // spring 过程都会按帧重组这个 Box。改成 `offset { }` 后每帧只改变换。
                .offset { IntOffset(thumbOffsetPx.value.roundToInt(), 0) }
                .size(27.dp)
                .shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                .background(thumbColor, CircleShape)
        )
    }
}

/**
 * iOS 返回 / 列表行尾 chevron（">"）。细线圆头，默认取 outline
 * （iOS tertiaryLabel 语义色）。返回箭头比 M3 ArrowBack 更细更轻。
 */
@Composable
fun IOSChevron(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
    pointingRight: Boolean = true
) {
    Canvas(
        modifier.size(width = 9.dp, height = 16.dp).drawWithCache {
            // AP-29 ③：原来 `Stroke(...)` 在绘制 lambda 里 —— 每帧一次分配
            //（同文件注释自称"每帧一次分配"却仍在新建）。改 drawWithCache 后只在
            // 密度（dp→px）变化时重建；drawLine 本身仍无分配。
            val strokeWidth = 2.dp.toPx()
            val w = size.width
            val h = size.height
            val midX = if (pointingRight) w * 0.42f else w * 0.58f
            val tipX = if (pointingRight) w * 0.88f else w * 0.12f
            onDrawBehind {
                // 两段线代替 Path：chevron 就是两条直线，drawPath 需要先 new 一个 Path
                // （绘制 lambda 每帧执行一次 → 每帧一次分配）。drawLine 无分配。
                drawLine(
                    color = color,
                    start = Offset(midX, h * 0.12f),
                    end = Offset(tipX, h * 0.5f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(tipX, h * 0.5f),
                    end = Offset(midX, h * 0.88f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        },
        // 绘制全部发生在 drawWithCache 的 onDrawBehind 里，Canvas 自身的 onDraw 留空
        onDraw = {}
    )
}
