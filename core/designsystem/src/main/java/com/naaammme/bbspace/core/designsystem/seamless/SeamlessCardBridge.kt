package com.naaammme.bbspace.core.designsystem.seamless

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
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
 * 注意：锚点会给每个卡片增加一个真实 View 节点，仅在「打开目标为跨 Activity 跳转」的
 * 卡片上使用（`canSeamless` 门禁把不支持无缝的设备挡在组合期之外）。
 * 视频详情自 ColorOS 无缝路径起是独立 Activity（`VideoDetailActivity`），
 * 覆盖层 Path 仅在设备不支持无缝动画时启用（详见 AppNavHost 的路由分支）。
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
        onRelease = {
            if (state.anchor === it) state.anchor = null
            // 诊断：锚点 View 被回收的时机。系统的打开与返回**都**要用这个 View：
            // 返回动画开始时若取不到已 attach 的锚点，会直接放弃 morph 退回 alpha
            // 交叉淡出（`view not attach to window or error, do alpha anim`）。
            // 实测曾因详情页过早 `capsuleState.deactivate()` 卸载小横条，在这里打出
            // `wasAttached=false`，是"返回没有无缝感"的直接证据，故保留这条日志。
            android.util.Log.i(
                "OplusViewSeamless",
                "anchor released (wasAttached=${it.isAttachedToWindow})"
            )
        },
    )
}

/** 过渡动画所需的卡片视觉参数，全部由主题 token 解析得出，不允许硬编码。 */
data class SeamlessCardSpec(
    val radiusPx: Float,
    val fallbackColor: Int,
)

/**
 * 把卡片内容录进 [GraphicsLayer]。
 *
 * ⚠️ **不要挂在列表卡片上**（性能红线，实测踩过）：
 * `GraphicsLayer.record` 每个绘制帧都会执行，等于**每张可见卡片每帧多一次离屏渲染**。
 * 实测挂到首页卡片后 jank 从 1.27% 涨到 4.06%、`Number Slow issue draw commands`
 * 从 9 涨到 31 —— 这是典型的"逐帧重录"反模式。
 *
 * 目前保留此 API 仅作参考/非列表场景使用；卡片的无缝预览改用
 * [SeamlessVideoCardOpener.captureCardBitmap]，只在**点击瞬间**截取一次。
 */
@Deprecated("挂在列表项上会逐帧离屏渲染，性能红线。卡片请用 captureCardBitmap（点击时截一次）。")
fun Modifier.recordCardLayer(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record {
        this@drawWithContent.drawContent()
    }
    drawContent()
}

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
