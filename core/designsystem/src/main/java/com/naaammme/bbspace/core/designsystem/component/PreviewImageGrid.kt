package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import com.naaammme.bbspace.core.common.media.ImageSaver
import com.naaammme.bbspace.core.common.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import kotlinx.coroutines.Job

@Immutable
data class PreviewImage(
    val url: String,
    val width: Float = 0f,
    val height: Float = 0f
)

@Composable
fun PreviewImageGrid(
    images: List<PreviewImage>,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    var previewIdx by remember { mutableIntStateOf(-1) }
    val columns = remember(images.size) { previewGridColumns(images.size) }
    val rows = remember(images, columns) { images.withIndex().chunked(columns) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        rows.forEach { rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                rowImages.forEach { item ->
                    PreviewGridItem(
                        image = item.value,
                        single = images.size == 1,
                        onClick = { previewIdx = item.index },
                        modifier = if (images.size == 1) {
                            Modifier.fillMaxWidth(0.72f)
                        } else {
                            Modifier.weight(1f)
                        }
                    )
                }
                if (images.size > 1) {
                    repeat(columns - rowImages.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (previewIdx in images.indices) {
        PreviewImageDialog(
            images = images,
            startIdx = previewIdx,
            onDismiss = { previewIdx = -1 }
        )
    }
}

@Composable
private fun PreviewGridItem(
    image: PreviewImage,
    single: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ratio = remember(image.width, image.height, single) {
        if (single) {
            previewSingleImageRatio(image)
        } else {
            1f
        }
    }
    BiliAsyncImage(
        url = image.url,
        contentDescription = "图片",
        modifier = modifier
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.large)
            // 全局拖拽反馈（第 34 轮 T2）：九宫格缩略图是**独立小件**（点开大图），
            // 不参与任何无缝过渡（这里没有 SeamlessAnchor，预览走 Dialog 而非共享元素），
            // 因此可以安全挂形变。
            // `highlightShape` 与上面 `clip(shapes.large)` 同源：光斑裁在缩略图圆角内，
            // 默认 50% 会把方形缩略图裁成椭圆。
            .appElasticPress(highlightShape = MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        contentScale = ContentScale.Crop,
        variant = BiliImageVariant.PreviewThumb
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewImageDialog(
    images: List<PreviewImage>,
    startIdx: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = startIdx,
        pageCount = { images.size }
    )
    var currentScale by remember(startIdx) { mutableFloatStateOf(1f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.96f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = currentScale <= 1.01f,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    PreviewImagePage(
                        image = images[page],
                        active = pagerState.currentPage == page,
                        onDismiss = onDismiss,
                        onScaleChange = { scale ->
                            if (pagerState.currentPage == page) {
                                currentScale = scale
                            }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.sm)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        // 全局拖拽反馈：全屏预览的浮层动作（关闭/保存），不参与过渡。
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .appElasticPress()
                    ) {
                        Text(
                            text = "关闭",
                            color = Color.White
                        )
                    }
                    Text(
                        text = "${pagerState.currentPage + 1}/${images.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    TextButton(
                        onClick = {
                            val currentImage = images.getOrNull(pagerState.currentPage)
                            if (currentImage != null) {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ImageSaver.saveUrl(context, currentImage.url) }
                                    }
                                    result.onSuccess {
                                        Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Logger.e("PreviewImageDialog", err as? Exception) { "save image failed url=${currentImage.url}" }
                                        Toast.makeText(context, err.message ?: "保存图片失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        // 全局拖拽反馈：与"关闭"同一条判据（浮层动作、不参与过渡）。
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .appElasticPress()
                    ) {
                        Text(
                            text = "保存",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewImagePage(
    image: PreviewImage,
    active: Boolean,
    onDismiss: () -> Unit,
    onScaleChange: (Float) -> Unit
) {
    val density = LocalDensity.current
    val animations = LocalAnimations.current
    val scope = rememberCoroutineScope()
    var scale by remember(image.url) { mutableFloatStateOf(1f) }
    var offset by remember(image.url) { mutableStateOf(Offset.Zero) }
    // 容器尺寸（双击缩放的锚点与平移上限都要它）。用 `onSizeChanged` 而不是
    // `BoxWithConstraints` 的 maxWidth/maxHeight：后者是**约束**，在有 padding 的
    // 链路里与指针坐标不在同一坐标系，锚点会偏。
    var containerSize by remember(image.url) { mutableStateOf(IntSize.Zero) }
    // 双击缩放动画的任务句柄：连点两次时先取消上一次，避免两个 animate 同时写 scale。
    var zoomJob by remember(image.url) { mutableStateOf<Job?>(null) }

    LaunchedEffect(active) {
        if (!active) {
            zoomJob?.cancel()
            zoomJob = null
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale, active) {
        if (active) {
            onScaleChange(scale)
        }
    }

    /**
     * 以 [tap]（相对容器左上角的点击位置）为锚点，把缩放动画到 [targetScale]。
     *
     * `tap == Offset.Zero` 表示"没有锚点"（复位）：此时锚点系数为 0，平移只会被
     * `clampPan` 收拢到 0，不会把图甩到一边。
     */
    fun animateZoomTo(targetScale: Float, tap: Offset) {
        val fromScale = scale
        if (!targetScale.isFinite() || kotlin.math.abs(targetScale - fromScale) < PreviewZoomPolicy.RESET_EPSILON) return
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val anchor = if (tap == Offset.Zero) Offset.Zero else tap - center
        val fromOffset = offset
        zoomJob?.cancel()
        zoomJob = scope.launch {
            animate(
                initialValue = fromScale,
                targetValue = targetScale,
                animationSpec = tween(
                    durationMillis = animations.morph,
                    easing = animations.morphEasing
                )
            ) { value, _ ->
                scale = value
                offset = PreviewZoomPolicy.clampPan(
                    offset = PreviewZoomPolicy.anchoredOffset(
                        currentOffset = fromOffset,
                        tapFromCenter = anchor,
                        fromScale = fromScale,
                        toScale = value
                    ),
                    maxPan = PreviewZoomPolicy.maxPan(
                        containerWidthPx = containerSize.width.toFloat(),
                        containerHeightPx = containerSize.height.toFloat(),
                        scale = value
                    )
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val tfState = rememberTransformableState { _, zoomChange, panChange, _ ->
            // 手势接管：停掉进行中的双击缩放，否则两个源会同时写 scale。
            zoomJob?.cancel()
            val nextScale = (scale * zoomChange).coerceIn(1f, PreviewZoomPolicy.MAX_SCALE)
            scale = nextScale
            offset = if (nextScale <= 1f) {
                Offset.Zero
            } else {
                PreviewZoomPolicy.clampPan(
                    offset = offset + panChange,
                    maxPan = PreviewZoomPolicy.maxPan(widthPx, heightPx, nextScale)
                )
            }
        }

        BiliAsyncImage(
            url = image.url,
            contentDescription = "图片预览",
            modifier = Modifier
                .fillMaxSize()
                .padding(BiliSpacing.xxl)
                .onSizeChanged { containerSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                // 单击：已放大 → 复位；未放大 → 关闭预览。
                // 双击：1x ⇄ 2.5x（**以点击点为锚点**，放大时手指下的那一点不动）。
                // 用 `detectTapGestures` 而不是 `clickable`：`clickable` 无法区分双击，
                // 结果是"双击 ≈ 两次单击" —— 第一次就把预览关掉了（真实 bug）。
                // 代价是单击要等双击超时（约 300ms）才生效，这是双击语义的固有成本。
                .pointerInput(image.url) {
                    detectTapGestures(
                        onTap = {
                            if (scale > 1f + PreviewZoomPolicy.RESET_EPSILON) {
                                animateZoomTo(targetScale = 1f, tap = Offset.Zero)
                            } else {
                                onDismiss()
                            }
                        },
                        onDoubleTap = { tap ->
                            animateZoomTo(
                                targetScale = PreviewZoomPolicy.nextScale(scale),
                                tap = tap
                            )
                        }
                    )
                }
                .transformable(
                    state = tfState,
                    canPan = { scale > 1f }
                ),
            contentScale = ContentScale.Fit,
            variant = BiliImageVariant.PreviewThumb
        )
    }
}

private fun previewGridColumns(count: Int): Int {
    return when (count) {
        1 -> 1
        2, 4 -> 2
        else -> 3
    }
}

private fun previewSingleImageRatio(image: PreviewImage): Float {
    return if (image.width > 0f && image.height > 0f) {
        (image.width / image.height).coerceIn(0.75f, 1.8f)
    } else {
        4f / 3f
    }
}
