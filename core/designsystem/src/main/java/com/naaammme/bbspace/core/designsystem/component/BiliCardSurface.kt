package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 统一的媒体卡片容器：固定底色、按主题形状裁剪内容（封面圆角跟随卡片）。
 * 可点击时带轻微按压缩放（0.97）反馈，松手弹性回弹。
 * 传入 [onLongClick] 时，点击与长按均由 combinedClickable 统一处理（与 Surface 点击不叠加）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BiliCardSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    val clippedModifier = modifier.clip(shape)
    if (onClick != null || onLongClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed) 0.97f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "cardPressScale"
        )
        // 缩放值在 graphicsLayer 块内延迟读取：按压动画的每一帧只更新图层属性，
        // 不再按帧重组卡片 scope（此前 Modifier.scale(pressScale) 在组合期读值，
        // 每个 feed/排行卡片的按压反馈都会按帧重组）
        val pressModifier = clippedModifier.graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        if (onLongClick != null) {
            // 长按场景：点击/长按都由 combinedClickable 处理，Surface 仅作容器
            Surface(
                modifier = pressModifier.combinedClickable(
                    enabled = enabled,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                    interactionSource = interactionSource
                ),
                shape = shape,
                color = containerColor,
                contentColor = contentColor
            ) {
                Column(content = content)
            }
        } else {
            Surface(
                onClick = onClick ?: {},
                enabled = enabled,
                modifier = pressModifier,
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                interactionSource = interactionSource
            ) {
                Column(content = content)
            }
        }
    } else {
        Surface(
            modifier = clippedModifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor
        ) {
            Column(content = content)
        }
    }
}
