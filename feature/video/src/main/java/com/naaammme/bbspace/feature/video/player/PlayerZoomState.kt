package com.naaammme.bbspace.feature.video.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * 全屏双指捏合缩放状态：scale/offset 以 mutableFloatStateOf 驱动，
 * 由播放区域 Box 的 graphicsLayer 消费（RenderNode 变换，不重建 SurfaceTexture，
 * 缩放帧零重组）。仅全屏启用；scale==1 时行为与原播放器完全一致。
 */
@Stable
class PlayerZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /**
     * 是否处于放大态（scale>1）。仅在跨越 1x 边界时写状态：composition 读它
     * （如「还原」按钮显隐）不会跟随捏合逐帧重组，scale 才是逐帧变化量，
     * 只允许 graphicsLayer 块读取（仅更新 RenderNode，不重组）。
     */
    var isZoomed by mutableStateOf(false)
        private set

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        if (isZoomed) isZoomed = false
    }

    /**
     * 应用一次捏合/平移变换。锚点取双指质心：质心下的内容点在缩放前后保持不动。
     * 公式：offset' = (centroid - center) * (1 - s'/s) + offset * (s'/s) + pan。
     * offset 按 (s-1)*size/2 钳制，放大态下内容始终覆盖视口，不露黑边。
     */
    fun applyTransform(zoomChange: Float, pan: Offset, centroid: Offset, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (!zoomChange.isFinite() || zoomChange <= 0f) return
        val previous = scale
        val next = (previous * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == previous && pan == Offset.Zero) return
        val centerX = width / 2f
        val centerY = height / 2f
        val ratio = next / previous
        val anchorX = centroid.x - centerX
        val anchorY = centroid.y - centerY
        val nextOffsetX = anchorX * (1f - ratio) + offsetX * ratio + pan.x
        val nextOffsetY = anchorY * (1f - ratio) + offsetY * ratio + pan.y
        scale = next
        if (next <= MIN_SCALE + 1e-4f) {
            // 缩回 1x 时回正，避免残留亚像素偏移
            offsetX = 0f
            offsetY = 0f
            if (isZoomed) isZoomed = false
        } else {
            val maxX = (next - 1f) * width / 2f
            val maxY = (next - 1f) * height / 2f
            offsetX = nextOffsetX.coerceIn(-maxX, maxX)
            offsetY = nextOffsetY.coerceIn(-maxY, maxY)
            if (!isZoomed) isZoomed = true
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}

/**
 * 捏合缩放手势（挂在 videoGestures 同一个 overlay Box 上、位于其链内側：
 * Main pass 先于 videoGestures 收到事件）。仲裁规则：
 * - 双指（≥2 按压）：接管缩放+平移并 consume，单指状态机靠双指守卫退出；
 * - 单指且 scale>1：接管平移（consume 移动事件）；
 * - 单指且 scale==1：不消费任何事件，完全让位给 videoGestures。
 */
fun Modifier.playerPinchZoom(
    enabled: Boolean,
    state: PlayerZoomState
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false) ?: return@awaitEachGesture
        var zooming = false
        var singleTracking = false
        var prevDistance = 0f
        var prevCentroid = down.position
        var prevSingleX = down.position.x
        var prevSingleY = down.position.y
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val a = pressed[0]
                val b = pressed[1]
                val distance = hypot(
                    (a.position.x - b.position.x).toDouble(),
                    (a.position.y - b.position.y).toDouble()
                ).toFloat()
                val centroid = Offset(
                    (a.position.x + b.position.x) / 2f,
                    (a.position.y + b.position.y) / 2f
                )
                if (prevDistance > 0f) {
                    val pan = centroid - prevCentroid
                    state.applyTransform(
                        zoomChange = distance / prevDistance,
                        pan = pan,
                        centroid = centroid,
                        width = width,
                        height = height
                    )
                    event.changes.forEach { it.consume() }
                }
                zooming = true
                singleTracking = false
                prevDistance = distance
                prevCentroid = centroid
            } else {
                val change = pressed[0]
                if (state.isZoomed) {
                    // 放大态单指 = 平移（含从捏合抬剩一指的续接）
                    if (!singleTracking) {
                        singleTracking = true
                    } else {
                        val deltaX = change.position.x - prevSingleX
                        val deltaY = change.position.y - prevSingleY
                        if (deltaX != 0f || deltaY != 0f) {
                            state.applyTransform(
                                zoomChange = 1f,
                                pan = Offset(deltaX, deltaY),
                                centroid = change.position,
                                width = width,
                                height = height
                            )
                            change.consume()
                        }
                    }
                }
                prevSingleX = change.position.x
                prevSingleY = change.position.y
                if (!state.isZoomed) zooming = false
            }
        }
    }
}
