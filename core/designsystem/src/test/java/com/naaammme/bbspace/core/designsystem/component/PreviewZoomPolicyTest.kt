package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片预览缩放的纯判据。
 *
 * 这些数值没有任何编译期检查、且**无设备时无法观测**（双击锚点偏了只会"感觉怪"），
 * 所以在 JVM 上把语义钉死：倍率循环、锚点不动、平移上限、非法输入兜底。
 */
class PreviewZoomPolicyTest {

    private val eps = 0.001f

    @Test
    fun doubleTapCyclesBetweenOneAndTarget() {
        assertEquals(PreviewZoomPolicy.DOUBLE_TAP_SCALE, PreviewZoomPolicy.nextScale(1f), eps)
        assertEquals(1f, PreviewZoomPolicy.nextScale(PreviewZoomPolicy.DOUBLE_TAP_SCALE), eps)
    }

    @Test
    fun doubleTapTreatsNearOneAsReset() {
        // 捏合松手常常停在 1.004 这种值：必须当作"已经复位"，否则双击会"再放大一档"。
        val nearOne = 1f + PreviewZoomPolicy.RESET_EPSILON / 2f
        assertEquals(PreviewZoomPolicy.DOUBLE_TAP_SCALE, PreviewZoomPolicy.nextScale(nearOne), eps)
        // 恰好等于阈值时仍算已放大（> 而非 >=）：阈值本身是"已放大"的边界。
        assertEquals(1f, PreviewZoomPolicy.nextScale(1f + PreviewZoomPolicy.RESET_EPSILON * 2f), eps)
    }

    @Test
    fun doubleTapTargetNeverExceedsMaxScale() {
        val target = PreviewZoomPolicy.nextScale(1f)
        assertTrue("双击目标不得越过捏合上限", target <= PreviewZoomPolicy.MAX_SCALE)
    }

    @Test
    fun doubleTapFallsBackToOneOnNonFiniteInput() {
        assertEquals(1f, PreviewZoomPolicy.nextScale(Float.NaN), eps)
        assertEquals(1f, PreviewZoomPolicy.nextScale(Float.POSITIVE_INFINITY), eps)
    }

    @Test
    fun anchorAtCenterScalesExistingOffsetWithTheZoom() {
        // 以中心为锚点放大 2.5 倍：原本偏离中心 (12,-8) 的内容点要按同倍率展开，
        // 才能让"屏幕中心那一点"保持不动（保持 offset 不变会让中心点漂走）。
        val out = PreviewZoomPolicy.anchoredOffset(
            currentOffset = Offset(12f, -8f),
            tapFromCenter = Offset.Zero,
            fromScale = 1f,
            toScale = 2.5f
        )
        assertEquals(30f, out.x, eps)
        assertEquals(-20f, out.y, eps)
    }

    @Test
    fun zoomingInAroundPointShiftsContentTowardsThatPoint() {
        // 手指点在中心右侧 100px：放大后内容要往左移（负 x），才能让该点留在手指下。
        val out = PreviewZoomPolicy.anchoredOffset(
            currentOffset = Offset.Zero,
            tapFromCenter = Offset(100f, 0f),
            fromScale = 1f,
            toScale = 2f
        )
        assertEquals(-100f, out.x, eps)
        assertEquals(0f, out.y, eps)
    }

    @Test
    fun anchorMathIsDirectionalAndNotJustAdditive() {
        // 非零起始 offset 下缩小 3x：结果**不是**"把 offset 反向加回去"的朴素补数。
        val out = PreviewZoomPolicy.anchoredOffset(
            currentOffset = Offset(-160f, 100f),
            tapFromCenter = Offset(80f, -50f),
            fromScale = 3f,
            toScale = 1f
        )
        assertEquals(0f, out.x, eps)
        assertEquals(0f, out.y, eps)
    }

    @Test
    fun zoomInThenOutAroundSamePointReturnsToOrigin() {
        val anchor = Offset(80f, -50f)
        val zoomed = PreviewZoomPolicy.anchoredOffset(Offset.Zero, anchor, 1f, 3f)
        val back = PreviewZoomPolicy.anchoredOffset(zoomed, anchor, 3f, 1f)
        assertEquals(0f, back.x, eps)
        assertEquals(0f, back.y, eps)
    }

    @Test
    fun anchoredOffsetIsInertOnIllegalScale() {
        val current = Offset(5f, 5f)
        assertEquals(
            current,
            PreviewZoomPolicy.anchoredOffset(current, Offset(10f, 10f), fromScale = 0f, toScale = 2f)
        )
        assertEquals(
            current,
            PreviewZoomPolicy.anchoredOffset(current, Offset(10f, 10f), fromScale = 1f, toScale = Float.NaN)
        )
    }

    @Test
    fun maxPanGrowsWithScaleAndVanishesAtOne() {
        assertEquals(Offset.Zero, PreviewZoomPolicy.maxPan(100f, 200f, 1f))
        assertEquals(Offset.Zero, PreviewZoomPolicy.maxPan(100f, 200f, 0.5f))
        val atTwo = PreviewZoomPolicy.maxPan(100f, 200f, 2f)
        assertEquals(50f, atTwo.x, eps)
        assertEquals(100f, atTwo.y, eps)
    }

    @Test
    fun maxPanIsZeroOnDegenerateContainer() {
        assertEquals(Offset.Zero, PreviewZoomPolicy.maxPan(0f, 200f, 3f))
        assertEquals(Offset.Zero, PreviewZoomPolicy.maxPan(Float.NaN, 200f, 3f))
        assertEquals(Offset.Zero, PreviewZoomPolicy.maxPan(100f, 200f, Float.NaN))
    }

    @Test
    fun clampPanBoundsBothAxes() {
        val clamped = PreviewZoomPolicy.clampPan(
            offset = Offset(999f, -999f),
            maxPan = Offset(50f, 40f)
        )
        assertEquals(50f, clamped.x, eps)
        assertEquals(-40f, clamped.y, eps)
    }

    @Test
    fun clampPanZeroesNonFiniteOffset() {
        assertEquals(Offset.Zero, PreviewZoomPolicy.clampPan(Offset(Float.NaN, 1f), Offset(10f, 10f)))
    }

    @Test
    fun clampPanWithZeroBoundsAlwaysReturnsZero() {
        // 缩回 1x 时上限为 0 ⇒ 任何残影都被清掉（否则复位后图会"偏一点"）。
        val out = PreviewZoomPolicy.clampPan(Offset(37f, -19f), PreviewZoomPolicy.maxPan(100f, 100f, 1f))
        assertEquals(Offset.Zero, out)
    }
}
