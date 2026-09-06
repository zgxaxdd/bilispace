package com.naaammme.bbspace.core.designsystem.seamless

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose 卡片与 View 体系无缝动画之间的桥接状态，持有充当过渡锚点的宿主 View。
 * [OplusViewSeamlessCompat.registerOpenTransition] 需要一个 android.view.View
 * 作为过渡起点，Compose 卡片本身没有对应 View，因此由 [SeamlessAnchor] 提供。
 */
class SeamlessAnchorState {
    // 供跨模块调用方（feature 层卡片）在点击瞬间读取；写路径只属于 SeamlessAnchor
    var anchor: View? = null
}

@Composable
fun rememberSeamlessAnchorState(): SeamlessAnchorState = remember { SeamlessAnchorState() }

/**
 * 在卡片边界内叠加一个与内容同尺寸、零绘制的透明 View 锚点。
 *
 * 用法：把卡片内容包进 Box，让锚点与内容同边界：
 *
 * ```
 * val anchorState = rememberSeamlessAnchorState()
 * Box {
 *     SeamlessAnchor(anchorState, Modifier.matchParentSize())
 *     // ...卡片内容（封面 / 标题等）
 * }
 * ```
 *
 * 点击打开目标 Activity 时，把 [SeamlessAnchorState.anchor] 交给
 * [OplusViewSeamlessCompat.registerOpenTransition]，并以返回的 Bundle 调用
 * `startActivity(intent, bundle)`（不支持时走普通 startActivity 分支）。
 *
 * 注意：锚点会给每个卡片增加一个真实 View 节点，仅在打开目标为跨 Activity 跳转、
 * 且确认启用无缝动画的卡片上使用；当前 BBSpace 视频详情是 Compose 覆盖层，
 * 激活条件与接线位置见 docs/2026-09-06-BBSpace-OPPO无缝动画接入方案.md。
 */
@Composable
fun SeamlessAnchor(state: SeamlessAnchorState, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { state.anchor = it },
        onRelease = { if (state.anchor === it) state.anchor = null },
    )
}

/** 过渡动画所需的卡片视觉参数，全部由主题 token 解析得出，不允许硬编码。 */
data class SeamlessCardSpec(
    val radiusPx: Float,
    val fallbackColor: Int,
)

/**
 * 由圆角 token 与卡片底色解析过渡参数。默认值对齐媒体卡片：
 * 圆角取 [MaterialTheme.shapes.large]（FeedCard 实际使用的 shape），
 * 底色取 [MaterialTheme.colorScheme.surfaceContainerLow]（BiliCardSurface 默认底色）。
 *
 * 仅支持固定 dp 圆角的 [CornerBasedShape]（项目 buildShapes 产出的即为此类）；
 * 百分比圆角会随 view 实际尺寸变化，无法静态解析，遇此情况按 0 圆角处理。
 */
@Composable
fun rememberSeamlessCardSpec(
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
): SeamlessCardSpec {
    val density = LocalDensity.current
    return remember(shape, containerColor, density) {
        val corner = (shape as? CornerBasedShape)?.topStart
        SeamlessCardSpec(
            radiusPx = corner?.toPx(Size.Unspecified, density) ?: 0f,
            fallbackColor = containerColor.toArgb(),
        )
    }
}
