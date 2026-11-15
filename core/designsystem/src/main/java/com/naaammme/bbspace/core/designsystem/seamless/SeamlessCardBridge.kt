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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.naaammme.bbspace.core.common.log.DiagnosticLog

/**
 * Compose 卡片与 View 体系无缝动画之间的桥接状态，持有充当过渡锚点的宿主 View。
 * [OplusViewSeamlessCompat.registerOpenTransition] 需要一个 android.view.View
 * 作为过渡起点，Compose 卡片本身没有对应 View，因此由 [SeamlessAnchor] 提供。
 */
class SeamlessAnchorState {
    // 供跨模块调用方（feature 层卡片）在点击瞬间读取；写路径只属于 SeamlessAnchor
    var anchor: View? = null

    /**
     * 锚点所在 Compose 节点的 [LayoutCoordinates]（**活引用**，不是快照）。
     *
     * ## 为什么必须存坐标对象而不是存矩形（这是「OPPO 动画错位」的根因修法）
     *
     * 全局拖拽反馈（[com.naaammme.bbspace.core.designsystem.motion.appElasticPress]）
     * 的形变是 `Modifier.graphicsLayer` —— **绘制期**变换：它改变像素落在屏幕上的位置，
     * 但**不改布局**。于是同一个"锚点现在长在哪"有两个互相矛盾的答案：
     *
     * | 取法 | 相位 | 按压/拖拽形变中 |
     * |---|---|---|
     * | `View.getLocationInWindow()` / `getLocationOnScreen()` | 布局期 | 返回**未变形**矩形 |
     * | `LayoutCoordinates.boundsInWindow()` | 绘制期链（含 layer） | 返回**变形后**矩形 |
     *
     * OPPO 无缝框架拿的是前者（它只认 View）。所以用户按住卡片（卡片正在放大 2%、
     * 并被手指拖着偏移若干像素）时点开视频，框架按"未变形矩形"起飞 —— 而屏幕上
     * 那张卡片其实已经在别处、另一个大小。这就是「无缝动画从错误的位置/大小开始」
     * 的机制性来源，且排行页最明显（卡片在瀑布流里、点击前常有一次按压回弹未走完）。
     *
     * 修法不是"关掉拖拽反馈"，而是把**真实显示矩形**显式喂给框架：官方
     * `BUNDLE_RECT` 的语义正是「view 在屏幕上显示的区域」，用于框架算错区域时兜底。
     * 传它属于**按文档正确使用**，不是绕路。
     *
     * ## 为什么不能存 `Rect`
     *
     * `onGloballyPositioned` 只在布局/位置变化时回调，而 graphicsLayer 的缩放平移
     * **不触发布局**。所以在回调里把 `boundsInWindow()` 存成 `Rect` 会永远拿到
     * 上一次布局时的（未变形）值 —— 等于没修。存 [LayoutCoordinates] 引用则可以
     * 在**点击那一刻**现场求值，此时读到的就是当前帧的变形结果。
     *
     * 读写都只在 UI 线程（布局回调与点击处理器）。
     */
    var layoutCoordinates: LayoutCoordinates? = null

    /**
     * 锚点**当前实际显示**的屏幕矩形（px，含祖先 `graphicsLayer` 的缩放/平移）。
     *
     * 坐标系对齐：`boundsInWindow()` 给的是窗口坐标，而官方 `BUNDLE_RECT` 要屏幕坐标。
     * 两者相差一个窗口原点，用锚点 View 自己的 `getLocationOnScreen - getLocationInWindow`
     * 求出该偏移再平移即可（同一窗口内该偏移是常量，无需遍历 View 树）。
     *
     * 未 attach / 尚未布局 / 尺寸非法时返回 null，调用方回退旧的 `screenRect()`。
     */
    fun displayRectPx(): android.graphics.Rect? {
        val view = anchor ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val coords = layoutCoordinates ?: return null
        val inWindow = runCatching { coords.boundsInWindow() }.getOrNull() ?: return null
        if (inWindow.width <= 0f || inWindow.height <= 0f) return null
        val loc = IntArray(2)
        val locWindow = IntArray(2)
        view.getLocationOnScreen(loc)
        view.getLocationInWindow(locWindow)
        val dx = (loc[0] - locWindow[0]).toFloat()
        val dy = (loc[1] - locWindow[1]).toFloat()
        return android.graphics.Rect(
            (inWindow.left + dx).toInt(),
            (inWindow.top + dy).toInt(),
            (inWindow.right + dx).toInt(),
            (inWindow.bottom + dy).toInt()
        )
    }
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
        // 上报锚点在 Compose 树里的**布局坐标对象**（活引用），供点击瞬间求真实显示矩形。
        // 必须用 onGloballyPositioned 而不是 AndroidView 的 update：要的是 Compose
        // 侧 LayoutCoordinates（它能穿过 graphicsLayer 给出变形后的窗口矩形），
        // 而 View 自己的 getLocationInWindow 只反映布局结果。详见
        // [SeamlessAnchorState.displayRectPx] 的相位对照表。
        modifier = modifier.onGloballyPositioned { state.layoutCoordinates = it },
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
            // 坐标对象同样要摘：它持有 LayoutCoordinates（内含节点引用），
            // 锚点已离开窗口还留着会让后续点击读到一个失效节点。
            state.layoutCoordinates = null
            // **锚点离开窗口时必须摘掉过渡装饰**（2026-10-12 审查补）。
            //
            // 装饰是画在锚点自己的 `background`/`foreground` 上的一张**冻结快照**
            // （整卡像素）。清理它的正规路径有两条，而两条都可能走不到：
            // · `onEnd(isOpen=false)` —— 实测在本机固件上**从不送达**；
            // · `scheduleAnchorCleanup` 的兜底协程 —— 它以 `SeamlessTransitionState.closing`
            //   为启动条件，而 `closing` 只在 `markClosing()`（返回/页内导航收起）里置位。
            //   若详情页**未经这两个收起方法**就消失（系统回收、从最近任务划掉、
            //   OEM 清栈），`closing` 永远不为真 ⇒ 协程一直等到 60s 超时才退出。
            //
            // 更糟的是卡片锚点在 `fullCardOverlay` 里位于内容**之上**（`BiliCardSurface`
            // 先画 Column 内容、再画 overlay），而锚点铺满整卡 ⇒ 残留的冻结快照会
            // **永久盖住整张卡片**（封面 + 标题全被一张旧图遮死），且用户无法自愈。
            //
            // 这里以"View 被回收"为确定性时机摘掉装饰：onRelease 必然发生，且此刻
            // 该锚点已不可能再参与任何过渡（系统取不到未 attach 的锚点）。清理是幂等的
            // （置 null），与上述两条路径重复执行也无害。
            it.foreground = null
            it.background = null
            // 诊断：锚点 View 被回收的时机。系统的打开与返回**都**要用这个 View：
            // 返回动画开始时若取不到已 attach 的锚点，会直接放弃 morph 退回 alpha
            // 交叉淡出（`view not attach to window or error, do alpha anim`）。
            // 实测曾因详情页过早 `capsuleState.deactivate()` 卸载小横条，在这里打出
            // `wasAttached=false`，是"返回没有无缝感"的直接证据，故保留这条日志。
            DiagnosticLog.i(
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
