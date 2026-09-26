package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

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
    /**
     * 铺满**整张卡片**的覆盖层槽位（画在内容之上、Surface 之内）。
     *
     * 为什么需要它：卡片内容装在 `Column` 里（因为要用 `Modifier.weight`），
     * 而 `Modifier.matchParentSize()` 是 `BoxScope` 限定修饰符——放在 Column 里
     * 会被当成真实布局子项。想让某个元素覆盖整卡（例如 OPPO 无缝动画的锚点
     * 需要"封面 + 简介"整个矩形作为起飞区域），就必须有一个 Box 层的槽位。
     *
     * 传 `null` 时零开销：不会多套一层 Box。
     */
    fullCardOverlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // 纯黑深色下的发丝描边（仅默认不透明容器色生效，见函数文档）
    val hairline = biliDarkHairlineBorder(containerColor)
    val clippedModifier = modifier.clip(shape)
    // 只有需要覆盖层时才多套一层 Box，避免给所有卡片白加一层布局节点
    val body: @Composable () -> Unit = {
        if (fullCardOverlay != null) {
            Box {
                Column(content = content)
                fullCardOverlay()
            }
        } else {
            Column(content = content)
        }
    }
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
        // 不再按帧重组卡片 scope（Modifier.scale(pressScale) 会在组合期读值，
        // 每个 feed/排行卡片的按压回弹都会按帧重组整张卡片）
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
                contentColor = contentColor,
                border = hairline
            ) {
                body()
            }
        } else {
            Surface(
                onClick = onClick ?: {},
                enabled = enabled,
                modifier = pressModifier,
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                border = hairline,
                interactionSource = interactionSource
            ) {
                body()
            }
        }
    } else {
        Surface(
            modifier = clippedModifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = hairline
        ) {
            body()
        }
    }
}

/**
 * 纯黑深色下的卡片发丝描边（iOS 深色增强 2026-09-26）。
 *
 * 为什么需要：iOS 深色的底是纯黑（#000）、卡片是 #1C1C1E——两档都很暗且中间
 * 没有过渡，大面积信息流读作"一整块黑"（用户反馈"纯黑太单调"）。Telegram /
 * YouTube / X 的 AMOLED 主题普遍用 6–8% 白的 1px 轮廓把卡片从底色上"托"出来，
 * 本仓沿用该手法：只在 iOS 档 + 深色 + 纯黑底（背景恰好是 Color.Black）+
 * 卡片为默认不透明容器色时生效；玻璃卡（透明容器）与非纯黑底不描边。
 */
@Composable
internal fun biliDarkHairlineBorder(containerColor: Color): androidx.compose.foundation.BorderStroke? {
    if (LocalUiStyle.current != UiStyle.IOS) return null
    val bg = MaterialTheme.colorScheme.background
    if (bg.luminance() >= 0.06f) return null // 浅色不描边
    if (containerColor == Color.Transparent || containerColor == Color.Unspecified) return null
    if (containerColor != MaterialTheme.colorScheme.surfaceContainerLow &&
        containerColor != MaterialTheme.colorScheme.surfaceContainer
    ) return null
    // 纯黑底描边稍强（7%），染色炭底稍弱（5%）——炭底本身与卡片已有明度差
    val alpha = if (bg == Color.Black) 0.07f else 0.05f
    return androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = alpha))
}

