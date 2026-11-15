package com.naaammme.bbspace.core.designsystem.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LiquidIndicatorMotion` + `LiquidIndicatorVelocity` 的纯逻辑单测。
 *
 * 钉住的约定：
 * 1. 速度换算：`px/s ÷ itemWidth`，宽度非法时为 0；
 * 2. 速度形变系数钳在 ±20%，正速度压扁/拉长、零速度回到 1；
 * 3. bloom 曲线在整页处为 0、相邻页中间为 1（正弦，两端导数为 0）；
 * 4. 吸附判定只在过阈值时为真；档位吸附**一次最多跨一档**且钳在 [0, count-1]；
 * 5. 速度弹簧：单调收敛、`snapTo` 立刻到位、NaN 目标被忽略、0 纳秒不推进。
 */
class LiquidIndicatorMotionTest {

    // ── LiquidIndicatorMotion ──────────────────────────────────────────────

    @Test
    fun `items per second divides by item width`() {
        assertEquals(5f, LiquidIndicatorMotion.itemsPerSecond(500f, 100f), 0.001f)
        assertEquals(
            "宽度 0 不许除零",
            0f,
            LiquidIndicatorMotion.itemsPerSecond(500f, 0f),
            0.001f
        )
        assertEquals(
            "NaN 速度兜底为 0",
            0f,
            LiquidIndicatorMotion.itemsPerSecond(Float.NaN, 100f),
            0.001f
        )
    }

    @Test
    fun `velocity ratio is clamped to twenty percent`() {
        assertEquals(0.2f, LiquidIndicatorMotion.clampVelocityRatio(0.5f), 0.001f)
        assertEquals(-0.2f, LiquidIndicatorMotion.clampVelocityRatio(-0.5f), 0.001f)
        assertEquals(
            "区间内原样返回",
            0.1f,
            LiquidIndicatorMotion.clampVelocityRatio(0.1f),
            0.001f
        )
        assertEquals(0f, LiquidIndicatorMotion.clampVelocityRatio(Float.NaN), 0.001f)
    }

    @Test
    fun `pager press progress is zero at whole pages and one between them`() {
        assertEquals("整页 0 处无 bloom", 0f, LiquidIndicatorMotion.pagerPressProgress(0f), 0.0001f)
        assertEquals(
            "相邻页中间 bloom 到 1",
            1f,
            LiquidIndicatorMotion.pagerPressProgress(0.5f),
            0.0001f
        )
        assertEquals("下一页整页回到 0", 0f, LiquidIndicatorMotion.pagerPressProgress(1f), 0.0001f)
        assertEquals(
            "跨页后按小数部分算（1.5 ⇒ 同 0.5）",
            1f,
            LiquidIndicatorMotion.pagerPressProgress(1.5f),
            0.0001f
        )
        assertEquals(
            "正弦曲线：0.25 处约 0.707",
            0.7071f,
            LiquidIndicatorMotion.pagerPressProgress(0.25f),
            0.001f
        )
        assertEquals(
            "NaN 兜底为 0",
            0f,
            LiquidIndicatorMotion.pagerPressProgress(Float.NaN),
            0.0001f
        )
    }

    @Test
    fun `velocity deformation flattens on positive velocity and clamps`() {
        assertEquals(
            "零速度不形变",
            1f,
            LiquidIndicatorMotion.scaleX(1f, 0f),
            0.001f
        )
        assertTrue(
            "正速度必须让 X 变大（压扁）",
            LiquidIndicatorMotion.scaleX(1f, 100f) > 1f
        )
        assertEquals(
            "速度给到极限也只放大到 1/(1-0.2)=1.25 倍",
            1.25f,
            LiquidIndicatorMotion.scaleX(1f, 100_000f),
            0.001f
        )
        assertTrue(
            "正速度必须让 Y 变小（拉长）",
            LiquidIndicatorMotion.scaleY(1f, 100_000f) < 1f
        )
        assertEquals(
            "Y 的下限 = 1-0.2 = 0.8",
            0.8f,
            LiquidIndicatorMotion.scaleY(1f, 100_000f),
            0.001f
        )
        assertEquals(
            "NaN 速度不形变",
            1f,
            LiquidIndicatorMotion.scaleX(1f, Float.NaN),
            0.001f
        )
    }

    @Test
    fun `snap decision requires reaching the threshold`() {
        assertTrue(
            "恰好到达阈值就算扔出去（含等号）",
            LiquidIndicatorMotion.shouldSnap(420f)
        )
        assertFalse("差 1px/s 都不算", LiquidIndicatorMotion.shouldSnap(419f))
        assertTrue("负方向同样算（取绝对值）", LiquidIndicatorMotion.shouldSnap(-420f))
        assertFalse("NaN 不算", LiquidIndicatorMotion.shouldSnap(Float.NaN))
    }

    @Test
    fun `snap index never moves more than the step limit`() {
        assertEquals(
            "投影到 2.4 档、松手在 0 档、最多跨 1 档 ⇒ 只到 1",
            1,
            LiquidIndicatorMotion.snapIndex(projected = 2.4f, base = 0, maxStep = 1, itemCount = 5)
        )
        assertEquals(
            "投影到 -5 档、松手在 1 档 ⇒ 只到 0",
            0,
            LiquidIndicatorMotion.snapIndex(projected = -5f, base = 1, maxStep = 1, itemCount = 5)
        )
        assertEquals(
            "四舍五入到最近档（2.6 ⇒ 3）",
            3,
            LiquidIndicatorMotion.snapIndex(projected = 2.6f, base = 2, maxStep = 1, itemCount = 5)
        )
        assertEquals(
            "count=0 时返回 0（空列表不许越界）",
            0,
            LiquidIndicatorMotion.snapIndex(projected = 1f, base = 0, maxStep = 1, itemCount = 0)
        )
        assertEquals(
            "NaN 投影 ⇒ 退回松手档位",
            2,
            LiquidIndicatorMotion.snapIndex(projected = Float.NaN, base = 2, maxStep = 1, itemCount = 5)
        )
        assertEquals(
            "base 越界也要钳回来",
            4,
            LiquidIndicatorMotion.snapIndex(projected = 99f, base = 99, maxStep = 1, itemCount = 5)
        )
    }

    @Test
    fun `spring specs carry the upstream tuning`() {
        val position = LiquidIndicatorMotion.positionSpec()
        assertEquals("位置 spring 无回弹（阻尼 1.0）", 1f, position.dampingRatio, 0.001f)
        assertEquals("位置 spring 收得干脆", 1000f, position.stiffness, 0.001f)
        val velocity = LiquidIndicatorMotion.velocitySpec()
        assertEquals("速度 spring 允许回弹", 0.5f, velocity.dampingRatio, 0.001f)
        assertEquals("速度 spring 较软", 300f, velocity.stiffness, 0.001f)
    }

    // ── LiquidIndicatorVelocity ───────────────────────────────────────────

    @Test
    fun `snap to puts value exactly on target and settles`() {
        val velocity = LiquidIndicatorVelocity()
        velocity.snapTo(0.75f)
        assertEquals(0.75f, velocity.value, 0.0001f)
        assertTrue("snapTo 后必须判定为已收敛", velocity.isSettled())
    }

    @Test
    fun `advance without elapsed time does not move`() {
        val velocity = LiquidIndicatorVelocity()
        velocity.snapTo(0f)
        velocity.updateTarget(1f)
        assertEquals(
            "0 纳秒不许推进（避免除零/无效帧）",
            0f,
            velocity.advance(0L),
            0.0001f
        )
    }

    @Test
    fun `spring converges toward target with bounded overshoot`() {
        val velocity = LiquidIndicatorVelocity()
        velocity.snapTo(0f)
        velocity.updateTarget(1f)
        var previous = 0f
        var increasingSteps = 0
        // 200 帧 × 16ms = 3.2s，足够让 300 stiffness 的 spring 收敛
        repeat(200) {
            val next = velocity.advance(16_000_000L)
            // 速度 spring 的阻尼是 0.5（欠阻尼）⇒ **允许回弹**，但必须被钳在安全范围。
            // 这是与位置 spring（阻尼 1.0、无回弹）刻意的分工：形变值要一点"液态回弹"，
            // 位置不能回弹（回弹读作"没对准"）。
            assertTrue(
                "欠阻尼 spring 的回弹必须有界（当前 $next，允许到 1.3）",
                next <= 1.3f
            )
            if (next >= previous) increasingSteps++
            previous = next
        }
        assertEquals("必须收敛到目标", 1f, velocity.value, 0.01f)
        assertTrue(
            "收敛前应单调上升（$increasingSteps 帧上升；末段允许回弹回落几帧）",
            increasingSteps >= 180
        )
        assertTrue("收敛后 isSettled 为真", velocity.isSettled())
    }

    @Test
    fun `non finite target is ignored`() {
        val velocity = LiquidIndicatorVelocity()
        velocity.updateTarget(0.5f)
        velocity.updateTarget(Float.NaN)
        assertEquals(
            "NaN 目标必须被忽略（否则 spring 永远收敛不了）",
            0.5f,
            velocity.currentTarget(),
            0.0001f
        )
    }

    @Test
    fun `freeze keeps the current value`() {
        val velocity = LiquidIndicatorVelocity()
        velocity.snapTo(0.3f)
        velocity.freeze()
        assertEquals(0.3f, velocity.value, 0.0001f)
        assertEquals(0.3f, velocity.currentTarget(), 0.0001f)
    }
}
