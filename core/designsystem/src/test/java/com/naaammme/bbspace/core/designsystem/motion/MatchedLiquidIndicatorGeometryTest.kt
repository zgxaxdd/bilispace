package com.naaammme.bbspace.core.designsystem.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MatchedLiquidIndicatorGeometry` 的 JVM 单测。
 *
 * 钉住的约定：
 * 1. 指示器高度 = 壳高 × 56/64（参考比例恒定），非法壳高为 0；
 * 2. 按压缩放 = 78/56，且**物理高度封顶 78**（大容器不涨破、小容器按比例缩）；
 * 3. 角半径 = 半高（两端全圆）；
 * 4. 插值函数端点精确、进度钳在 [0,1]、NaN 进度退回起点；
 * 5. 档位中心 = `index × pitch + width/2`，负索引为 0。
 */
class MatchedLiquidIndicatorGeometryTest {

    @Test
    fun `rest height keeps the reference ratio`() {
        assertEquals(
            "参考壳高 64dp ⇒ 指示器 56dp",
            56f,
            MatchedLiquidIndicatorGeometry.indicatorHeightDp(64f),
            0.001f
        )
        assertEquals(
            "双倍壳高 ⇒ 双倍指示器（比例恒定）",
            112f,
            MatchedLiquidIndicatorGeometry.indicatorHeightDp(128f),
            0.001f
        )
        assertEquals(
            "非法壳高（0）⇒ 0（调用方据此跳过绘制）",
            0f,
            MatchedLiquidIndicatorGeometry.indicatorHeightDp(0f),
            0.001f
        )
        assertEquals(
            "NaN 壳高 ⇒ 0",
            0f,
            MatchedLiquidIndicatorGeometry.indicatorHeightDp(Float.NaN),
            0.001f
        )
    }

    @Test
    fun `pressed scale is the reference bloom on the reference shell`() {
        assertEquals(
            "参考壳高下按压缩放 = 78/56",
            78f / 56f,
            MatchedLiquidIndicatorGeometry.pressedScale(64f, 56f),
            0.001f
        )
        assertEquals(
            "小容器按同一比例缩（32dp 壳 + 28dp 指示器仍是 78/56）",
            78f / 56f,
            MatchedLiquidIndicatorGeometry.pressedScale(32f, 28f),
            0.001f
        )
    }

    @Test
    fun `pressed height never blooms past the reference cap for oversized shells`() {
        val geometry = MatchedLiquidIndicatorGeometry.resolve(shellHeightDp = 128f)
        assertEquals(
            "128dp 壳的静止指示器 = 112dp",
            112f,
            geometry.indicatorHeightDp,
            0.001f
        )
        assertEquals(
            "静止高度已超过 78dp ⇒ 不再放大（缩放回落到 1）",
            1f,
            geometry.pressedScale,
            0.001f
        )
        assertTrue(
            "按压态的物理高度不许超过「静止高度与参考上限的较大者」" +
                "（当前 ${geometry.pressedHeightDp}dp）——大容器不涨成过大的泡",
            geometry.pressedHeightDp <= maxOf(geometry.indicatorHeightDp, 78f) + 0.001f
        )
    }

    @Test
    fun `pressed height blooms from rest to the reference cap on the reference shell`() {
        val geometry = MatchedLiquidIndicatorGeometry.resolve(shellHeightDp = 64f)
        assertEquals("参考壳高下静止 56dp", 56f, geometry.indicatorHeightDp, 0.001f)
        assertEquals(
            "参考壳高下按压到 78dp（上游同款 bloom）",
            78f,
            geometry.pressedHeightDp,
            0.001f
        )
    }

    @Test
    fun `corner radius is half the indicator height`() {
        assertEquals(
            "56dp 高的指示器 ⇒ 28dp 半径（两端全圆）",
            28f,
            MatchedLiquidIndicatorGeometry.cornerRadiusDp(56f),
            0.001f
        )
        assertEquals(
            "非法高度 ⇒ 0 半径",
            0f,
            MatchedLiquidIndicatorGeometry.cornerRadiusDp(0f),
            0.001f
        )
    }

    @Test
    fun `interpolations hit their endpoints exactly`() {
        assertEquals(
            "进度 0 ⇒ 起点半径",
            28f,
            MatchedLiquidIndicatorGeometry.lerpCornerRadiusDp(28f, 14f, 0f),
            0.001f
        )
        assertEquals(
            "进度 1 ⇒ 终点半径",
            14f,
            MatchedLiquidIndicatorGeometry.lerpCornerRadiusDp(28f, 14f, 1f),
            0.001f
        )
        assertEquals(
            "进度 0.5 ⇒ 中点",
            21f,
            MatchedLiquidIndicatorGeometry.lerpCornerRadiusDp(28f, 14f, 0.5f),
            0.001f
        )
        assertEquals(
            "位移插值 0.25",
            25f,
            MatchedLiquidIndicatorGeometry.lerpOffsetPx(0f, 100f, 0.25f),
            0.001f
        )
        assertEquals(
            "宽度插值端点",
            80f,
            MatchedLiquidIndicatorGeometry.lerpWidthPx(80f, 120f, 0f),
            0.001f
        )
    }

    @Test
    fun `progress is clamped and non finite falls back to start`() {
        assertEquals(
            "进度越界钳到 1",
            14f,
            MatchedLiquidIndicatorGeometry.lerpCornerRadiusDp(28f, 14f, 5f),
            0.001f
        )
        assertEquals(
            "进度越界钳到 0（-3 ⇒ 0）",
            0f,
            MatchedLiquidIndicatorGeometry.clampProgress(-3f),
            0.001f
        )
        assertEquals(
            "NaN 进度退回起点（不许把 NaN 传进绘制）",
            28f,
            MatchedLiquidIndicatorGeometry.lerpCornerRadiusDp(28f, 14f, Float.NaN),
            0.001f
        )
        assertEquals(
            "NaN 位移插值退回起点",
            10f,
            MatchedLiquidIndicatorGeometry.lerpOffsetPx(10f, 20f, Float.NaN),
            0.001f
        )
    }

    @Test
    fun `item center maps index to pixels`() {
        assertEquals(
            "第 2 档：2×48 + 40/2 = 116",
            116f,
            MatchedLiquidIndicatorGeometry.itemCenterPx(index = 2, itemWidthPx = 40f, pitchPx = 48f),
            0.001f
        )
        assertEquals(
            "负索引兜底为 0",
            0f,
            MatchedLiquidIndicatorGeometry.itemCenterPx(index = -1, itemWidthPx = 40f, pitchPx = 48f),
            0.001f
        )
    }

    @Test
    fun `round height avoids sub pixel sizes`() {
        assertEquals(
            "64dp 壳 ⇒ 56dp 整",
            56,
            MatchedLiquidIndicatorGeometry.roundHeightDp(64f)
        )
    }
}
