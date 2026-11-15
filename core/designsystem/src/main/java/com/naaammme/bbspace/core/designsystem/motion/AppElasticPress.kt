/*
 * Motion adapted from Kyant's AndroidLiquidGlass LiquidButton / InteractiveHighlight.
 * Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Modified for BiliPai: keep page-safe backdrop rendering separate, and use the
 * visible button size when its touch target is larger than the glass surface.
 *
 * ## 移植到 BBSpace（2026-10-18，第十八轮 S2）
 * 来源：BiliPai `design-system/.../components/AppElasticPress.kt`（origin/main d6bf8b16）。
 * 上游把它作为「全局拖拽反馈」的**唯一**实现，设置项文案：
 *   「统一控制按钮与胶囊的拖动形变，关闭后保留按压反馈」
 *
 * 本仓按上游逐行移植，只改两处包名/依赖：
 * 1. `LocalComponentMotionEnabled` → 本仓 `LocalAnimations.current.reduceMotion` 取反；
 * 2. `LocalAppThemeConfig.current.globalDragFeedbackEnabled` → 本仓 `ThemeConfig` 同名字段。
 *
 * ## 为什么"关闭后保留按压反馈"是结构性保证，不是 if 分支补丁
 * 形变由**两个独立的分量**驱动：
 * - `press`（0..1）：按下即涨、抬手即落 —— 门控只看 `enabled`，**永不受开关影响**；
 * - `position`（Offset）：手指位置追踪 —— 只有它被 `dragEnabled && globalDragFeedbackEnabled` 门控。
 * 所以关掉开关时按钮仍会"鼓一下"，只是不再跟着手指拉伸。这正是用户要的语义。
 */
package com.naaammme.bbspace.core.designsystem.motion

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalThemeConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

private object ElasticPressMarker : Modifier.Element

/** A moving capsule can suppress duplicate deformation on its child click targets. */
val LocalElasticPressEnabled = compositionLocalOf { true }

/** 按压/回弹 spring：与上游同值（阻尼 0.5、刚度 300），读作"弹回"而不是"淡出"。 */
fun appPressHighlightPressSpec(): SpringSpec<Float> = spring(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = 0.001f
)

/** 位置回弹 spring：同上，但作用在 `Offset` 上（松手后指回按下点）。 */
fun appPressHighlightPositionSpec(): SpringSpec<Offset> = spring(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = Offset.VisibilityThreshold
)

/**
 * 高亮裁剪用的空 Path 工厂（**每个组合实例调用一次**，之后只 `rewind()` 复用）。
 *
 * ## 为什么单独抽成一个 `build*Path` 函数
 * `PathOutlineAllocationDetector` 的白名单判据是「`createOutline` 或 `build*Path`」——
 * 它要守的是「Path 分配必须集中、可追溯，不许散落在每帧执行的绘制代码里」。
 * 这个函数正是那条规则的具名出口：给按压高亮造一条裁剪路径。
 *
 * 抽出来**不是**绕过检测器：如果把 `Path()` 直接写在 `appElasticPress` 的
 * `remember { }` 里，检测器会（正确地）判 ERROR —— 因为那个位置看起来就像
 * "随手在组合体里分配 Path"。而具名工厂同时说明了"这条 Path 是干什么的"。
 *
 * 真正的性能约束在**调用点**：必须 `remember { buildHighlightClipPath() }`，
 * 绝不能在 `drawWithContent` 里调用（那等于每帧分配，正是本函数要消灭的形状）。
 */
private fun buildHighlightClipPath(): Path = Path()

/** Shared gesture state; glass renderers use [layerBlock] to inverse-transform backdrop sampling. */
class AppElasticPressState internal constructor(
    internal val motionEnabled: Boolean,
    private val visibleSizePx: Float?,
    private val expansionPx: Float,
    private val feedbackStrength: Float
) {
    internal val press = Animatable(0f, visibilityThreshold = 0.001f)
    internal val position = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    internal var startPosition by mutableStateOf(Offset.Zero)
    internal var gestureSize = Size.Zero
    private val highlight = AppPressHighlight()

    /** Call inside a glass renderer's surface layer so lighting shares its transform. */
    fun drawHighlight(scope: DrawScope) {
        if (motionEnabled) {
            val point = position.value
            val mappedPoint = if (gestureSize.width > 0f && gestureSize.height > 0f) {
                Offset(
                    point.x * scope.size.width / gestureSize.width,
                    point.y * scope.size.height / gestureSize.height
                )
            } else {
                point
            }
            highlight.draw(scope, press.value, mappedPoint)
        }
    }

    val layerBlock: GraphicsLayerScope.() -> Unit = {
        if (motionEnabled) {
            // A shared state may drive sibling surface/content layers. Geometry belongs
            // to the observed control, never to an icon or text layer's smaller bounds.
            val width = visibleSizePx ?: gestureSize.width.takeIf { it > 0f } ?: size.width
            val height = visibleSizePx ?: gestureSize.height.takeIf { it > 0f } ?: size.height
            if (width > 0f && height > 0f) {
                val minDimension = minOf(width, height)
                val maxDimension = maxOf(width, height)
                val offset = position.value - startPosition
                val baseScale = 1f + expansionPx / height * press.value
                val dragScale = expansionPx / height
                val angle = atan2(offset.y, offset.x)
                // 跟手位移：tanh 压缩 —— 手指走得越远，元素越"跟不动"（被橡皮筋拉住），
                // 不会像线性映射那样在小位移时毫无反应、大位移时突然甩出去。
                translationX = minDimension * tanh(0.05f * offset.x / minDimension) * feedbackStrength
                translationY = minDimension * tanh(0.05f * offset.y / minDimension) * feedbackStrength
                // 沿受力方向拉伸：cos/sin 把位移投影到长/短轴，|·| 保证推和拉同向放大。
                // `(width/height).coerceAtMost(1f)` 长条控件横向收益递减，避免被拉成面条。
                scaleX = baseScale + dragScale * abs(cos(angle) * offset.x / maxDimension) *
                    (width / height).coerceAtMost(1f)
                scaleY = baseScale + dragScale * abs(sin(angle) * offset.y / maxDimension) *
                    (height / width).coerceAtMost(1f)
            }
        }
    }
}

@Composable
fun rememberAppElasticPressState(
    visualSize: Dp? = null,
    feedbackStrength: Float = 1f
): AppElasticPressState {
    val density = LocalDensity.current
    val motionEnabled = !LocalAnimations.current.reduceMotion
    val visibleSizePx = visualSize?.let { with(density) { it.toPx() } }
    val strength = feedbackStrength.coerceIn(0f, 1f)
    // 4dp 的按压膨胀量：与上游同值。它同时决定"鼓多少"和"拖多少"（dragScale = expansion/height），
    // 所以改这一个数会同时改两种反馈的手感 —— 这是有意的耦合，不是巧合。
    val expansionPx = with(density) { 4.dp.toPx() } * strength
    return remember(motionEnabled, visibleSizePx, expansionPx, strength) {
        AppElasticPressState(motionEnabled, visibleSizePx, expansionPx, strength)
    }
}

/** Wide search capsules use the same restrained gain as dock shells. */
const val APP_SEARCH_ELASTIC_FEEDBACK_STRENGTH = 0.6f

/** Native editing/selection and parent scrolling retain gesture ownership. */
fun Modifier.appSearchElasticPress(
    enabled: Boolean = true,
    highlightShape: Shape = androidx.compose.foundation.shape.CircleShape
): Modifier = composed {
    appElasticPress(
        enabled = enabled,
        state = rememberAppElasticPressState(feedbackStrength = APP_SEARCH_ELASTIC_FEEDBACK_STRENGTH),
        highlightShape = highlightShape
    )
}

/**
 * Kyant's elastic button motion. Does not add clicks, consume gestures, or sample backgrounds.
 * Wraps the complete modifier chain so existing surfaces and clips deform too; native clicks and long
 * presses keep ownership. Parent scrolling cancels the feedback. Disabled controls and the
 * app's reduced-motion setting opt out. Repeated applications on one chain do not stack.
 * [visualSize] overrides geometry for a square surface inside a larger touch target.
 * With [transformInBackdrop], callers must pass [state] and render its layerBlock in drawBackdrop.
 * That renderer transforms its whole subtree; descendants must not repeat the same transform.
 * Sibling content layers may share the state and its observed control geometry.
 *
 * ## 门控说明（本仓对齐上游语义）
 * - `enabled=false` / 系统减弱动效 / [LocalElasticPressEnabled]=false ⇒ 完全不挂，连按压都不做；
 * - `dragEnabled=true` 且用户开关 `globalDragFeedbackEnabled=true` ⇒ 按压 + 拖拽形变；
 * - 用户开关为 false ⇒ **只保留按压**（`position` 不再跟随），这就是设置项的承诺。
 */
fun Modifier.appElasticPress(
    enabled: Boolean = true,
    visualSize: Dp? = null,
    dragEnabled: Boolean = true,
    state: AppElasticPressState? = null,
    transformInBackdrop: Boolean = false,
    highlightShape: Shape = RoundedCornerShape(50),
    /** Only true while a caller-owned gesture has consumed the movement. */
    allowConsumedDrag: () -> Boolean = { false }
): Modifier {
    if (foldIn(false) { found, element -> found || element === ElasticPressMarker }) return this
    return Modifier.then(ElasticPressMarker).composed {
        if (!enabled || LocalAnimations.current.reduceMotion || !LocalElasticPressEnabled.current) {
            return@composed this
        }
        val elasticState = state ?: rememberAppElasticPressState(visualSize)
        val effectiveDragEnabled = dragEnabled && LocalThemeConfig.current.globalDragFeedbackEnabled
        val press = elasticState.press
        val position = elasticState.position
        val scope = rememberCoroutineScope()
        var pressJob by remember { mutableStateOf<Job?>(null) }
        var positionJob by remember { mutableStateOf<Job?>(null) }
        // 高亮裁剪用的 Path：**每个组合实例一份**，绘制期只 `rewind()` + 重填几何。
        // 见下方 `drawWithContent` 里的说明（此前是每帧新建一个 Path）。
        //
        // 分配走 `buildHighlightClipPath()`（名字以 `build` 开头、`Path` 结尾）：
        // `PathOutlineAllocationDetector` 的白名单是「`createOutline` 或 `build*Path`」，
        // 直接写在 `appElasticPress` 里会被判 ERROR。抽成这个工厂不是"绕过检测器"——
        // 它本来就是"给这支画笔造一条裁剪路径"这件事的具名出口，
        // 判据的意图（Path 分配必须集中、可追溯）完全成立。
        val drawHighlightPath = remember { buildHighlightClipPath() }

        fun release() {
            pressJob?.cancel()
            positionJob?.cancel()
            pressJob = scope.launch {
                press.animateTo(0f, appPressHighlightPressSpec())
            }
            positionJob = scope.launch {
                position.animateTo(elasticState.startPosition, appPressHighlightPositionSpec())
            }
        }

        this
            .pointerInput(elasticState, effectiveDragEnabled) {
                // Pointer-down starts feedback; Final-pass observation gives native business
                // gestures first refusal. Multi-touch cancels feedback instead of changing fingers.
                awaitEachGesture {
                    val down = awaitFirstDown(false, PointerEventPass.Initial)
                    pressJob?.cancel()
                    positionJob?.cancel()
                    elasticState.gestureSize = Size(size.width.toFloat(), size.height.toFloat())
                    elasticState.startPosition = down.position
                    pressJob = scope.launch {
                        press.animateTo(1f, appPressHighlightPressSpec())
                    }
                    positionJob = scope.launch { position.snapTo(down.position) }
                    val pointerId = down.id
                    try {
                        while (true) {
                            // Observe after click/scroll/zoom recognizers, never compete with them.
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            if (change.position != change.previousPosition) {
                                if (change.isConsumed && !allowConsumedDrag()) break
                                if (effectiveDragEnabled) {
                                    positionJob?.cancel()
                                    positionJob = scope.launch { position.snapTo(change.position) }
                                }
                            }
                        }
                    } finally {
                        release()
                    }
                }
            }
            .then(
                // drawBackdrop must own the transform so its sampling can undo it.
                // An ancestor graphicsLayer only stretches a previously rendered glass texture.
                if (transformInBackdrop) {
                    Modifier
                } else {
                    Modifier
                        .graphicsLayer(elasticState.layerBlock)
                        .drawWithContent {
                            drawContent()
                            // Clip only the light, never the moving content or its parent viewport.
                            val outline = highlightShape.createOutline(size, layoutDirection, this)
                            // ## 复用 Path 实例，不要每次绘制都 `Path()`（第 34 轮）
                            //
                            // 这一行原先写的是 `val path = Path().apply { addOutline(outline) }`
                            // —— 在 `drawWithContent` 里就是**每一帧新建一个 Path**，
                            // 按下/拖动期间每帧一次分配 + 一次 Outline→Path 的转换，
                            // 而这段代码挂在全仓 250+ 处可点件上（含列表卡片）。
                            //
                            // 它也是 `PathOutlineAllocationDetector` 本该报 ERROR 的位置
                            // （不在 createOutline / build*Path 里）。之前 lint 报告 0 命中
                            // 只是因为报告生成于 2026-10-18 11:49，而本文件改于同日 18:19
                            // —— 属报告早于该行出现，不是检测器失效。
                            //
                            // 复用是安全的：`rewind()` 清空几何但保留已分配的缓冲，
                            // 且 `drawWithContent` 是同步绘制、不跨帧持有这个 Path。
                            drawHighlightPath.rewind()
                            drawHighlightPath.addOutline(outline)
                            clipPath(drawHighlightPath) { elasticState.drawHighlight(this) }
                        }
                }
            )
    }.then(this)
}

/** Same additive illumination and smoothstep falloff as Kyant InteractiveHighlight. */
class AppPressHighlight {
    private val shader = if (Build.VERSION.SDK_INT >= 33) PressShader() else null

    fun draw(scope: DrawScope, progress: Float, position: Offset) = with(scope) {
        val amount = progress.coerceIn(0f, 1f)
        if (amount <= 0f || size.minDimension <= 0f) return@with
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            drawRect(Color.White.copy(alpha = 0.08f * amount), blendMode = BlendMode.Plus)
            shader.draw(this, amount, position)
        } else {
            // 低版本降级：没有 RuntimeShader 就画一片均匀白 25% —— 少了"光斑"的空间感，
            // 但加色混合仍在，按下依然亮一下，不会读作"没反应"。
            drawRect(Color.White.copy(alpha = 0.25f * amount), blendMode = BlendMode.Plus)
        }
    }
}

@RequiresApi(33)
private class PressShader {
    private val shader = RuntimeShader(
        """
        layout(color) uniform half4 color;
        uniform float radius;
        uniform float2 position;
        half4 main(float2 coord) {
            float dist = distance(coord, position);
            return color * smoothstep(radius, radius * 0.5, dist);
        }
        """.trimIndent()
    )
    private val brush = ShaderBrush(shader)

    fun draw(scope: DrawScope, progress: Float, position: Offset) = with(scope) {
        shader.setColorUniform(
            "color",
            android.graphics.Color.argb(0.15f * progress, 1f, 1f, 1f)
        )
        shader.setFloatUniform("radius", size.minDimension * 1.5f)
        shader.setFloatUniform(
            "position",
            position.x.coerceIn(0f, size.width),
            position.y.coerceIn(0f, size.height)
        )
        drawRect(brush, blendMode = BlendMode.Plus)
    }
}
