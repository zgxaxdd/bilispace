package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

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
    val thumbTravel = 20.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) thumbTravel else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "iosSwitchThumb"
    )
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
                .offset(x = thumbOffset)
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
    Canvas(modifier.size(width = 9.dp, height = 16.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        val midX = if (pointingRight) w * 0.42f else w * 0.58f
        val top = Offset(midX, h * 0.12f)
        val mid = Offset(if (pointingRight) w * 0.88f else w * 0.12f, h * 0.5f)
        val bottom = Offset(midX, h * 0.88f)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(top.x, top.y)
            lineTo(mid.x, mid.y)
            lineTo(bottom.x, bottom.y)
        }
        drawPath(path, color = color, style = stroke)
    }
}
