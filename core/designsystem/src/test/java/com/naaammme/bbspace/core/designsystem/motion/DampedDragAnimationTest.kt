package com.naaammme.bbspace.core.designsystem.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DampedDragAnimation` 四个纯判据的 JVM 单测。
 *
 * 钉住的约定：
 * 1. **跟手**：位移按阻尼系数缩放，正负对称，系数钳在 [0,1]；
 * 2. **橡皮筋**：边界内原样、越界按 28% 压缩且方向不变、仍然是单调的（拉得越远越远）；
 * 3. **投影**：`current + velocity × 窗口`，非法输入不产生 NaN；
 * 4. **回弹目标**：按格距吸附、钳在边界内、无格点（lattice=0）时归位到 0；
 * 5. 所有函数对 `NaN/Infinity` 兜底 —— 一旦漏进 `Animatable` 会钉死动画。
 */
class DampedDragAnimationTest {

    @Test
    fun `follow offset applies resistance and stays symmetric`() {
        assertEquals(
            "默认阻尼 0.55：100px 手指位移 ⇒ 55px 视觉位移",
            55f,
            DampedDragAnimation.followOffset(100f),
            0.001f
        )
        assertEquals(
            "负方向必须对称",
            -55f,
            DampedDragAnimation.followOffset(-100f),
            0.001f
        )
        assertEquals(
            "阻尼系数 1.0 = 完全跟手（测试桩，不是默认值）",
            100f,
            DampedDragAnimation.followOffset(100f, resistance = 1f),
            0.001f
        )
    }

    @Test
    fun `follow offset clamps resistance and rejects non finite input`() {
        assertEquals(
            "resistance 超过 1 视为 1（不许放大位移）",
            100f,
            DampedDragAnimation.followOffset(100f, resistance = 2f),
            0.001f
        )
        assertEquals(
            "负 resistance 视为 0（完全不动）",
            0f,
            DampedDragAnimation.followOffset(100f, resistance = -1f),
            0.001f
        )
        assertEquals(
            "NaN 位移必须兜底为 0（NaN 进 Animatable 会钉死动画）",
            0f,
            DampedDragAnimation.followOffset(Float.NaN),
            0.001f
        )
    }

    @Test
    fun `rubber band keeps in-bounds offset untouched`() {
        assertEquals(120f, DampedDragAnimation.rubberBandOffset(120f, 300f), 0.001f)
        assertEquals(-120f, DampedDragAnimation.rubberBandOffset(-120f, 300f), 0.001f)
        assertEquals(
            "恰好等于边界：原样",
            300f,
            DampedDragAnimation.rubberBandOffset(300f, 300f),
            0.001f
        )
    }

    @Test
    fun `rubber band compresses out-of-bounds and keeps monotonic`() {
        val atBound = DampedDragAnimation.rubberBandOffset(300f, 300f)
        val over = DampedDragAnimation.rubberBandOffset(400f, 300f)
        val wayOver = DampedDragAnimation.rubberBandOffset(800f, 300f)
        assertEquals(
            "越界 100px 只多算 28px（300 + 100×0.28）",
            328f,
            over,
            0.001f
        )
        assertTrue("越界后必须比边界更远（手感上「还能拉一点」）", over > atBound)
        assertTrue("继续往外拉必须继续更远（单调）", wayOver > over)
        assertTrue(
            "压缩后必须小于原始位移（橡皮筋要「沉」）",
            wayOver < 800f
        )
        assertEquals(
            "负方向同样压缩",
            -328f,
            DampedDragAnimation.rubberBandOffset(-400f, 300f),
            0.001f
        )
    }

    @Test
    fun `rubber band without range collapses to zero`() {
        assertEquals(
            "maxOffset<=0 表示没有可拖范围",
            0f,
            DampedDragAnimation.rubberBandOffset(100f, 0f),
            0.001f
        )
        assertEquals(0f, DampedDragAnimation.rubberBandOffset(Float.NaN, 300f), 0.001f)
        assertEquals(0f, DampedDragAnimation.rubberBandOffset(100f, Float.NaN), 0.001f)
    }

    @Test
    fun `projected offset adds velocity over the projection window`() {
        assertEquals(
            "100px + 1000px/s × 0.12s = 220px",
            220f,
            DampedDragAnimation.projectedOffset(100f, 1000f),
            0.001f
        )
        assertEquals(
            "负速度反向投影",
            -20f,
            DampedDragAnimation.projectedOffset(100f, -1000f),
            0.001f
        )
        assertEquals(
            "非法输入兜底为 0",
            0f,
            DampedDragAnimation.projectedOffset(Float.NaN, 1000f),
            0.001f
        )
    }

    @Test
    fun `release target springs back to zero when there is no lattice`() {
        assertEquals(
            "lattice=0（把手类交互）⇒ 松手归位到 0",
            0f,
            DampedDragAnimation.releaseTarget(
                currentPx = 137f,
                velocityPxPerSecond = 0f,
                boundsPx = -300f..300f,
                latticeStepPx = 0f
            ),
            0.001f
        )
    }

    @Test
    fun `release target snaps to nearest lattice point without fling`() {
        val bounds = -300f..300f
        assertEquals(
            "40px 距 0 更近 ⇒ 回 0",
            0f,
            DampedDragAnimation.releaseTarget(
                currentPx = 40f,
                velocityPxPerSecond = 0f,
                boundsPx = bounds,
                latticeStepPx = 100f
            ),
            0.001f
        )
        assertEquals(
            "60px 距 100 更近 ⇒ 吸附到 100",
            100f,
            DampedDragAnimation.releaseTarget(
                currentPx = 60f,
                velocityPxPerSecond = 0f,
                boundsPx = bounds,
                latticeStepPx = 100f
            ),
            0.001f
        )
    }

    @Test
    fun `release target uses projection when fling velocity is reached`() {
        assertEquals(
            "60px + 1000px/s×0.12 = 180px ⇒ 吸附到 200",
            200f,
            DampedDragAnimation.releaseTarget(
                currentPx = 60f,
                velocityPxPerSecond = 1000f,
                boundsPx = -300f..300f,
                latticeStepPx = 100f
            ),
            0.001f
        )
        assertEquals(
            "低于阈值只看当前位置（420 是阈值，419 不投影）",
            100f,
            DampedDragAnimation.releaseTarget(
                currentPx = 60f,
                velocityPxPerSecond = 419f,
                boundsPx = -300f..300f,
                latticeStepPx = 100f
            ),
            0.001f
        )
    }

    @Test
    fun `release target never escapes bounds`() {
        assertEquals(
            "向右甩到底也只能停在右边界",
            300f,
            DampedDragAnimation.releaseTarget(
                currentPx = 290f,
                velocityPxPerSecond = 5000f,
                boundsPx = -300f..300f,
                latticeStepPx = 100f
            ),
            0.001f
        )
        assertEquals(
            "向左甩到底也只能停在左边界",
            -300f,
            DampedDragAnimation.releaseTarget(
                currentPx = -290f,
                velocityPxPerSecond = -5000f,
                boundsPx = -300f..300f,
                latticeStepPx = 100f
            ),
            0.001f
        )
    }

    @Test
    fun `release target tolerates inverted bounds and non finite values`() {
        assertEquals(
            "边界写反了也要正确钳制（300..-300 与 -300..300 等价）",
            100f,
            DampedDragAnimation.releaseTarget(
                currentPx = 100f,
                velocityPxPerSecond = 0f,
                boundsPx = 300f..-300f,
                latticeStepPx = 100f
            ),
            0.001f
        )
        assertEquals(
            "NaN 当前值兜底后仍落在合法格点",
            0f,
            DampedDragAnimation.releaseTarget(
                currentPx = Float.NaN,
                velocityPxPerSecond = 0f,
                boundsPx = -300f..300f,
                latticeStepPx = 100f
            ),
            0.001f
        )
        assertEquals(
            "NaN 边界属于非法输入 ⇒ 返回 0",
            0f,
            DampedDragAnimation.releaseTarget(
                currentPx = 100f,
                velocityPxPerSecond = 0f,
                boundsPx = Float.NaN..Float.NaN,
                latticeStepPx = 100f
            ),
            0.001f
        )
    }
}
