package com.naaammme.bbspace.feature.video.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 捏合缩放状态机的纯数学/钳制单测（JVM，不依赖 Compose 运行时框架）。
 */
class PlayerZoomStateTest {

    private val w = 1000f
    private val h = 600f
    private val epsilon = 0.01f

    private fun screenToContent(sx: Float, sy: Float, s: Float, ox: Float, oy: Float): Offset {
        val cx = w / 2f
        val cy = h / 2f
        return Offset((sx - cx - ox) / s + cx, (sy - cy - oy) / s + cy)
    }

    private fun contentToScreen(c: Offset, s: Float, ox: Float, oy: Float): Offset {
        val cx = w / 2f
        val cy = h / 2f
        return Offset((c.x - cx) * s + cx + ox, (c.y - cy) * s + cy + oy)
    }

    @Test
    fun `scale clamps to range`() {
        val state = PlayerZoomState()
        state.applyTransform(zoomChange = 10f, pan = Offset.Zero, centroid = Offset(w / 2f, h / 2f), width = w, height = h)
        assertEquals(PlayerZoomState.MAX_SCALE, state.scale, epsilon)
        state.applyTransform(zoomChange = 0.01f, pan = Offset.Zero, centroid = Offset(w / 2f, h / 2f), width = w, height = h)
        assertEquals(PlayerZoomState.MIN_SCALE, state.scale, epsilon)
    }

    @Test
    fun `content under centroid stays fixed after zoom`() {
        val state = PlayerZoomState()
        // 质心偏离中心：验证锚点数学
        val centroid = Offset(200f, 150f)
        val contentBefore = screenToContent(centroid.x, centroid.y, state.scale, state.offsetX, state.offsetY)
        state.applyTransform(zoomChange = 2.5f, pan = Offset.Zero, centroid = centroid, width = w, height = h)
        val screenAfter = contentToScreen(contentBefore, state.scale, state.offsetX, state.offsetY)
        assertEquals(centroid.x, screenAfter.x, 1f)
        assertEquals(centroid.y, screenAfter.y, 1f)
    }

    @Test
    fun `pan clamps within viewport so no black edge`() {
        val state = PlayerZoomState()
        state.applyTransform(zoomChange = 2f, pan = Offset.Zero, centroid = Offset(w / 2f, h / 2f), width = w, height = h)
        state.applyTransform(zoomChange = 1f, pan = Offset(99_999f, 99_999f), centroid = Offset(w / 2f, h / 2f), width = w, height = h)
        // 2x 时最大允许偏移 = (2-1)*size/2
        assertEquals((2f - 1f) * w / 2f, state.offsetX, epsilon)
        assertEquals((2f - 1f) * h / 2f, state.offsetY, epsilon)
        // 左缘不露黑边：内容左边界 <= 视口左边界
        val leftContent = screenToContent(0f, h / 2f, state.scale, state.offsetX, state.offsetY)
        assertTrue("content left edge should cover viewport", leftContent.x <= 0f)
    }

    @Test
    fun `zoom back to 1x zeroes offsets and clears isZoomed`() {
        val state = PlayerZoomState()
        state.applyTransform(zoomChange = 3f, pan = Offset.Zero, centroid = Offset(100f, 100f), width = w, height = h)
        assertTrue(state.isZoomed)
        state.applyTransform(zoomChange = 1f / 3f, pan = Offset.Zero, centroid = Offset(w / 2f, h / 2f), width = w, height = h)
        assertEquals(PlayerZoomState.MIN_SCALE, state.scale, epsilon)
        assertEquals(0f, state.offsetX, 0f)
        assertEquals(0f, state.offsetY, 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `reset clears everything`() {
        val state = PlayerZoomState()
        state.applyTransform(zoomChange = 2f, pan = Offset.Zero, centroid = Offset(300f, 300f), width = w, height = h)
        state.reset()
        assertEquals(1f, state.scale, epsilon)
        assertEquals(0f, state.offsetX, 0f)
        assertEquals(0f, state.offsetY, 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `invalid inputs are ignored`() {
        val state = PlayerZoomState()
        state.applyTransform(zoomChange = Float.NaN, pan = Offset.Zero, centroid = Offset.Zero, width = w, height = h)
        state.applyTransform(zoomChange = 0f, pan = Offset.Zero, centroid = Offset.Zero, width = w, height = h)
        state.applyTransform(zoomChange = 2f, pan = Offset.Zero, centroid = Offset.Zero, width = 0f, height = 0f)
        assertEquals(1f, state.scale, epsilon)
    }
}
