package com.naaammme.bbspace.core.designsystem.motion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.naaammme.bbspace.core.designsystem.motion.gl.ThanosEffectView
import com.naaammme.bbspace.core.designsystem.motion.gl.isThanosEffectSupported
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// ==================== iOS 风格抖动效果系统 ====================

object DissolveAnimationManager {
    private val _isAnyCardDissolving = mutableStateOf(false)
    val isAnyCardDissolving: State<Boolean> = _isAnyCardDissolving
    
    private val _dissolvingCardId = mutableStateOf<String?>(null)
    val dissolvingCardId: State<String?> = _dissolvingCardId
    
    fun startDissolving(cardId: String) {
        _dissolvingCardId.value = cardId
        _isAnyCardDissolving.value = true
    }
    
    fun stopDissolving() {
        _dissolvingCardId.value = null
        _isAnyCardDissolving.value = false
    }
}

enum class DissolveAnimationPreset {
    CLASSIC,
    TELEGRAM_FAST
}

internal fun shouldWrapWithDissolveAnimation(isDissolving: Boolean): Boolean {
    return isDissolving
}

internal fun shouldJiggleOnDissolve(
    enabled: Boolean,
    isAnyCardDissolving: Boolean,
    dissolvingCardId: String?,
    cardId: String,
    isCurrentCardDissolving: Boolean
): Boolean {
    if (!enabled || isCurrentCardDissolving) return false
    return isAnyCardDissolving && dissolvingCardId != cardId
}

internal fun shouldPublishGlobalDissolveState(
    publishGlobalState: Boolean
): Boolean {
    return publishGlobalState
}

internal fun shouldCreateDissolveBitmap(
    width: Int,
    height: Int
): Boolean {
    return width > 0 && height > 0
}

internal fun shouldDispatchDissolveCompletion(
    hasCompletedCurrentDissolve: Boolean
): Boolean {
    return !hasCompletedCurrentDissolve
}

@Composable
fun Modifier.jiggleOnDissolve(
    cardId: String,
    enabled: Boolean = true,
    isCurrentCardDissolving: Boolean = false
): Modifier {
    // 🚀 [性能优化] 提前检查是否需要抖动，避免不必要的状态读取和动画创建
    if (!enabled) return this
    
    val isDissolving by DissolveAnimationManager.isAnyCardDissolving
    val dissolvingId by DissolveAnimationManager.dissolvingCardId
    val shouldJiggle = shouldJiggleOnDissolve(
        enabled = enabled,
        isAnyCardDissolving = isDissolving,
        dissolvingCardId = dissolvingId,
        cardId = cardId,
        isCurrentCardDissolving = isCurrentCardDissolving
    )
    
    // 🚀 [关键优化] 不抖动时直接返回，不创建任何动画对象
    if (!shouldJiggle) return this
    
    val infiniteTransition = rememberInfiniteTransition(label = "jiggle")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jiggleRotation"
    )
    
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(60, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jiggleOffset"
    )
    
    return this.graphicsLayer {
        rotationZ = rotation
        translationX = offsetX
    }
}

// ==================== Telegram / NagramX ThanosEffect ====================

@Composable
fun DissolvableVideoCard(
    isDissolving: Boolean,
    onDissolveComplete: () -> Unit,
    modifier: Modifier = Modifier,
    cardId: String = "",
    preset: DissolveAnimationPreset = DissolveAnimationPreset.CLASSIC,
    collapseAfterDissolve: Boolean = true,
    publishGlobalDissolveState: Boolean = true,
    keepInvisibleAfterDissolve: Boolean = false,
    reflowDuringFinalTail: Boolean = false,
    onReflowStarted: () -> Unit = {},
    /**
     * 粒子首帧出现、本体该让位时的回调（**bbspace 扩展**，源实现没有也不需要）。
     *
     * 本体隐藏靠的是 `Modifier.graphicsLayer { alpha = 0f }`（源实现同样写法），
     * 而 **Compose 的 graphicsLayer 管不到 `AndroidView`**：子树里若有 AndroidView
     * （本仓小横条的壳 = `SeamlessAnchor`），它会**原样留在屏幕上**，
     * 表现为"文字碎成粒子云、横条本体没碎"（用户实测反馈）。
     * 源仓库的卡片是纯 Compose，所以它不需要这个回调；本仓需要一个出口让调用方
     * 把那些 View 自己关掉（`view.alpha = 0f`）。
     */
    onContentHidden: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val completionHandler = remember { Handler(Looper.getMainLooper()) }
    val contentLayer = rememberGraphicsLayer()
    var cardSize by remember(cardId) { mutableStateOf(IntSize.Zero) }
    var cardWindowBounds by remember(cardId) { mutableStateOf<RectF?>(null) }
    var hasRecordedContent by remember(cardId) { mutableStateOf(false) }
    var shouldCollapse by remember(cardId) { mutableStateOf(false) }
    var keepContentHidden by remember(cardId) { mutableStateOf(false) }
    var hasCompletedCurrentDissolve by remember(cardId) { mutableStateOf(false) }
    var effectView by remember(cardId) { mutableStateOf<ThanosEffectView?>(null) }
    val latestOnComplete by rememberUpdatedState(onDissolveComplete)
    val latestOnReflowStarted by rememberUpdatedState(onReflowStarted)
    val latestIsDissolving by rememberUpdatedState(isDissolving)
    // 组合期读一次（`LocalAnimations.current` 不能在 LaunchedEffect 里读）；
    // 值来自第 4 轮接线的系统「移除动画」+ App 内「动效速度」。
    val reduceMotion = LocalAnimations.current.reduceMotion
    val publishGlobalState = shouldPublishGlobalDissolveState(publishGlobalDissolveState)

    fun dispatchCompletionOnce() {
        if (!shouldDispatchDissolveCompletion(hasCompletedCurrentDissolve)) return
        hasCompletedCurrentDissolve = true
        if (publishGlobalState && DissolveAnimationManager.dissolvingCardId.value == cardId) {
            DissolveAnimationManager.stopDissolving()
        }
        latestOnComplete()
    }

    fun beginCollapse() {
        if (hasCompletedCurrentDissolve || shouldCollapse) return
        keepContentHidden = true
        shouldCollapse = true
        latestOnReflowStarted()
    }

    fun finishEffect() {
        effectView?.dispose()
        effectView = null
        if (hasCompletedCurrentDissolve || shouldCollapse) return
        if (collapseAfterDissolve) {
            beginCollapse()
        } else {
            keepContentHidden = keepInvisibleAfterDissolve
            dispatchCompletionOnce()
        }
    }

    val collapseSpec: AnimationSpec<Float> = remember(preset, reflowDuringFinalTail) {
        when (preset) {
            DissolveAnimationPreset.CLASSIC -> spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            )
            DissolveAnimationPreset.TELEGRAM_FAST -> if (reflowDuringFinalTail) {
                tween(240, easing = LinearOutSlowInEasing)
            } else {
                tween(135, easing = FastOutSlowInEasing)
            }
        }
    }
    val heightMultiplier = animateFloatAsState(
        targetValue = if (shouldCollapse) 0f else 1f,
        animationSpec = collapseSpec,
        label = "heightCollapse",
        finishedListener = {
            if (shouldCollapse && collapseAfterDissolve) dispatchCompletionOnce()
        }
    )

    LaunchedEffect(isDissolving, cardId) {
        if (!isDissolving) {
            effectView?.dispose()
            effectView = null
            shouldCollapse = false
            keepContentHidden = false
            return@LaunchedEffect
        }
        hasCompletedCurrentDissolve = false
        // ── 移植时**有意偏离源实现**的一处（无障碍）────────────────────────────
        // 源（BiliPai 的 ParticleDissolveEffect.kt）**没有检查 reduceMotion**：
        // 用户在系统里打开「移除动画」后，粒子照旧播。本仓第 4 轮已把系统开关
        // （ANIMATOR_DURATION_SCALE）与 App 内「动效速度」一起接进
        // `LocalAnimations.reduceMotion` —— 这里必须消费它，否则那处接线对消散无效。
        //
        // 降级方式：不起粒子，**直接走完成路径**（该删的还是删、该关的还是关），
        // 由既有的 collapse 分支交付回调。
        if (reduceMotion) {
            shouldCollapse = collapseAfterDissolve
            keepContentHidden = true
            if (!collapseAfterDissolve) dispatchCompletionOnce()
            return@LaunchedEffect
        }
        val window = findWindow(context)
        if (window == null || !isThanosEffectSupported(context)) {
            finishEffect()
            return@LaunchedEffect
        }
        val ready = withTimeoutOrNull(500L) {
            snapshotFlow { hasRecordedContent && cardWindowBounds != null }
                .first { it }
        }
        if (ready != true) {
            finishEffect()
            return@LaunchedEffect
        }
        val bounds = cardWindowBounds
        if (bounds == null) {
            finishEffect()
            return@LaunchedEffect
        }
        // ── 捕获策略：**优先 PixelCopy**（抓"屏幕上真实可见的那一块"）────────────────
        // 上游用的是 `View.draw(Canvas)` —— 直接绘制 View 子树，子 View 画的东西都在里面。
        // 移植时改成了 `contentLayer.toImageBitmap()`，但**Compose 图层快照抓不到
        // AndroidView 绘制的内容**：小横条的壳由 `SeamlessAnchor`（一个 AndroidView）渲染，
        // 于是只有 Compose 画的封面/文字碎掉、壳还留在原地
        //（用户实测反馈原话：「bbspace 现在是文字碎成粒子云」）。
        //
        // PixelCopy 抓的是**窗口合成后的真实像素**，AndroidView / Surface 一律包含在内，
        // 与上游 `View.draw` 的意图等价且更可靠（不依赖子 View 是否走 Canvas）。
        // 失败则退回图层快照 —— 降级但不报错、不卡流程。
        val bitmap: Bitmap? = withTimeoutOrNull(500L) { captureCardBitmap(window, bounds) }
            ?: try {
                withTimeoutOrNull(500L) {
                    contentLayer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                DiagnosticLog.w("ThanosEffect", "Cannot capture video card", error)
                null
            }
        if (bitmap == null) {
            finishEffect()
            return@LaunchedEffect
        }
        effectView = ThanosEffectView.attach(
            window = window,
            bitmap = bitmap,
            windowBounds = bounds,
            onFirstFrame = {
                keepContentHidden = true
                // 让调用方把 graphicsLayer alpha 管不到的 AndroidView 一并关掉
                onContentHidden?.invoke()
                if (publishGlobalState && cardId.isNotEmpty()) {
                    DissolveAnimationManager.startDissolving(cardId)
                }
            },
            onComplete = { finishEffect() },
            onFinalTail = {
                if (reflowDuringFinalTail && collapseAfterDissolve) beginCollapse()
            },
        )
        if (effectView == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishEffect()
            return@LaunchedEffect
        }
        // Covers missing SurfaceTexture callbacks/device failures without a stuck removal.
        // Normal upstream lifetime is (1.5 + 0.9) / 1.15, about 2.09 seconds.
        delay(5000L)
        if (!hasCompletedCurrentDissolve && !shouldCollapse) finishEffect()
    }

    DisposableEffect(cardId) {
        onDispose {
            effectView?.dispose()
            effectView = null
            // Lazy layouts dispose offscreen items during measurement. Mutating their
            // item provider here invalidates the grid's in-flight index/Lookahead data.
            // A queued callback also survives cancellation of this item's effect scope.
            if (latestIsDissolving) {
                completionHandler.post { dispatchCompletionOnce() }
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { if (!shouldCollapse) cardSize = it }
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                cardWindowBounds = RectF(
                    position.x, position.y,
                    position.x + coordinates.size.width,
                    position.y + coordinates.size.height,
                )
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = if (shouldCollapse) {
                    (cardSize.height * heightMultiplier.value).roundToInt().coerceAtLeast(0)
                } else {
                    placeable.height
                }
                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            }
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = if (keepContentHidden) 0f else 1f }
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(contentLayer)
                    if (!hasRecordedContent && shouldCreateDissolveBitmap(size.width.toInt(), size.height.toInt())) {
                        hasRecordedContent = true
                    }
                }
        ) { content() }
    }
}

@Composable
fun MaybeDissolvableVideoCard(
    isDissolving: Boolean,
    onDissolveComplete: () -> Unit,
    modifier: Modifier = Modifier,
    cardId: String = "",
    preset: DissolveAnimationPreset = DissolveAnimationPreset.CLASSIC,
    collapseAfterDissolve: Boolean = true,
    publishGlobalDissolveState: Boolean = true,
    keepInvisibleAfterDissolve: Boolean = false,
    reflowDuringFinalTail: Boolean = false,
    onReflowStarted: () -> Unit = {},
    preserveContentLayerWhenIdle: Boolean = false,
    /**
     * 粒子首帧出现、本体该让位时回调（bbspace 扩展，源实现没有）。
     *
     * **为什么必须有它**：本体隐藏是靠 `Modifier.graphicsLayer { alpha = 0f }`，
     * 而 **Compose 的 graphicsLayer 管不到 `AndroidView`** —— 子树里若有 AndroidView
     * （本仓小横条的壳 = `SeamlessAnchor`），它会**原样留在屏幕上**，
     * 表现为"文字碎成粒子云、横条本体没碎"（用户实测反馈）。
     * 这个回调让调用方自己去把那些 View 关掉（`view.alpha = 0f`）。
     */
    onContentHidden: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (shouldWrapWithDissolveAnimation(isDissolving)) {
        DissolvableVideoCard(
            isDissolving = true,
            onDissolveComplete = onDissolveComplete,
            modifier = modifier,
            cardId = cardId,
            preset = preset,
            collapseAfterDissolve = collapseAfterDissolve,
            publishGlobalDissolveState = publishGlobalDissolveState,
            keepInvisibleAfterDissolve = keepInvisibleAfterDissolve,
            reflowDuringFinalTail = reflowDuringFinalTail,
            onReflowStarted = onReflowStarted,
            onContentHidden = onContentHidden,
            content = content
        )
    } else {
        Box(modifier = modifier) {
            if (preserveContentLayerWhenIdle) {
                // SharedBounds records its source relative to the card's existing graphics layer.
                // Keep the same idle hierarchy as DissolvableVideoCard for transition-enabled
                // cards, without restoring its per-frame size/window coordinate tracking.
                Box(modifier = Modifier.alpha(1f)) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

private fun findWindow(context: Context): Window? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 抓取卡片在**窗口坐标系**下的真实像素（含 AndroidView / Surface）。
 *
 * 为什么不只用 Compose 图层快照：`GraphicsLayer.record` 只记录 **Compose 的绘制命令**，
 * 子树里的 `AndroidView`（本仓小横条的壳 = `SeamlessAnchor`）由 Android 视图系统绘制，
 * 不进图层快照 —— 结果是"只有文字碎、壳还在"。PixelCopy 抓窗口合成结果，不受此限制。
 *
 * 失败（窗口不可见 / 尺寸非法 / API 拒绝）返回 null，由调用方退回图层快照。
 */
private suspend fun captureCardBitmap(window: Window, bounds: RectF): Bitmap? {
    val left = bounds.left.roundToInt().coerceAtLeast(0)
    val top = bounds.top.roundToInt().coerceAtLeast(0)
    val width = bounds.width().roundToInt()
    val height = bounds.height().roundToInt()
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val result = withTimeoutOrNull(500L) {
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    Rect(left, top, left + width, top + height),
                    bitmap,
                    { copyResult: Int ->
                        if (continuation.isActive) continuation.resume(copyResult == PixelCopy.SUCCESS)
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (error: IllegalArgumentException) {
                // 矩形跑到窗口外等情况：当作失败，走图层快照兜底
                DiagnosticLog.w("ThanosEffect", "PixelCopy rejected the card rect", error)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }
    if (result != true) {
        bitmap.recycle()
        return null
    }
    return bitmap
}
