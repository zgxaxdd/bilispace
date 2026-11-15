package com.naaammme.bbspace.core.designsystem.glass.liquid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LiquidGlassReadabilityPolicy` 的**迟滞**与 `LiquidGlassReadabilityState` 的注入/保守值单测。
 *
 * 钉住的约定（0.58 / 0.42 双阈值）：
 * 1. 首次判定：`>= 0.5` 取深色前景，`< 0.5` 取浅色；
 * 2. 浅色态下 `0.5`、`0.55` **不切换**，`0.6`（> 0.58）才切深色；
 * 3. 深色态下 `0.43` 不切回，`0.41`（< 0.42）才切回浅色；
 * 4. 采样为空/NaN 时保持上一次；从未采到过 ⇒ 保守取浅色（白字）；
 * 5. 采样器注入后由 [LiquidGlassReadabilityState.refresh] 拉取，`reset` 清历史。
 */
class LiquidGlassReadabilityPolicyTest {

    private fun tone(
        previous: LiquidGlassForegroundTone?,
        luminance: Float?
    ): LiquidGlassForegroundTone =
        LiquidGlassReadabilityPolicy.resolveTone(previous, luminance)

    @Test
    fun `first decision uses the midpoint threshold`() {
        assertEquals(
            "首次判定：0.5 恰好到中点 ⇒ 深色前景",
            LiquidGlassForegroundTone.Dark,
            tone(null, 0.5f)
        )
        assertEquals(
            "首次判定：0.49 ⇒ 浅色前景",
            LiquidGlassForegroundTone.Light,
            tone(null, 0.49f)
        )
    }

    @Test
    fun `light tone does not flip inside the dead band`() {
        assertEquals(
            "0.5 在死区内 ⇒ 保持浅色（不能一闪一亮）",
            LiquidGlassForegroundTone.Light,
            tone(LiquidGlassForegroundTone.Light, 0.5f)
        )
        assertEquals(
            "0.55 仍在死区内（< 0.58）⇒ 保持浅色",
            LiquidGlassForegroundTone.Light,
            tone(LiquidGlassForegroundTone.Light, 0.55f)
        )
        assertEquals(
            "0.6 > 0.58 ⇒ 切深色（背景确实亮了，白字开始糊）",
            LiquidGlassForegroundTone.Dark,
            tone(LiquidGlassForegroundTone.Light, 0.6f)
        )
    }

    @Test
    fun `dark tone only flips below the lower threshold`() {
        assertEquals(
            "0.43 > 0.42 ⇒ 仍保持深色",
            LiquidGlassForegroundTone.Dark,
            tone(LiquidGlassForegroundTone.Dark, 0.43f)
        )
        assertEquals(
            "0.41 < 0.42 ⇒ 切回浅色",
            LiquidGlassForegroundTone.Light,
            tone(LiquidGlassForegroundTone.Dark, 0.41f)
        )
    }

    @Test
    fun `thresholds are exclusive at the exact boundary`() {
        assertEquals(
            "恰好 0.58 不切（判据是 > 0.58）",
            LiquidGlassForegroundTone.Light,
            tone(LiquidGlassForegroundTone.Light, 0.58f)
        )
        assertEquals(
            "0.59 切深色",
            LiquidGlassForegroundTone.Dark,
            tone(LiquidGlassForegroundTone.Light, 0.59f)
        )
        assertEquals(
            "恰好 0.42 不切（判据是 < 0.42）",
            LiquidGlassForegroundTone.Dark,
            tone(LiquidGlassForegroundTone.Dark, 0.42f)
        )
        assertEquals(
            "0.419 切浅色",
            LiquidGlassForegroundTone.Light,
            tone(LiquidGlassForegroundTone.Dark, 0.419f)
        )
    }

    @Test
    fun `empty sample keeps previous or falls back conservative light`() {
        assertEquals(
            "没采到 + 没历史 ⇒ 保守取浅色（白字）",
            LiquidGlassForegroundTone.Light,
            tone(null, null)
        )
        assertEquals(
            "没采到 + 历史为深色 ⇒ 保持深色（不许因一次失败闪烁）",
            LiquidGlassForegroundTone.Dark,
            tone(LiquidGlassForegroundTone.Dark, null)
        )
        assertEquals(
            "NaN 采样等同于没采到",
            LiquidGlassForegroundTone.Dark,
            tone(LiquidGlassForegroundTone.Dark, Float.NaN)
        )
    }

    @Test
    fun `out of range luminance is clamped`() {
        assertEquals(
            "亮度 5 按 1 处理 ⇒ 深色",
            LiquidGlassForegroundTone.Dark,
            tone(null, 5f)
        )
        assertEquals(
            "亮度 -1 按 0 处理 ⇒ 浅色",
            LiquidGlassForegroundTone.Light,
            tone(null, -1f)
        )
    }

    @Test
    fun `state follows the injected sampler with hysteresis`() {
        var sample: Float? = 0.4f
        val state = LiquidGlassReadabilityState(sampler = { sample })
        assertFalse("还没采样时不算已判定", state.hasResolved)
        assertEquals(
            "首次 0.4 ⇒ 浅色",
            LiquidGlassForegroundTone.Light,
            state.refresh()
        )
        assertTrue("有效采样后 hasResolved", state.hasResolved)

        sample = 0.55f
        assertEquals("0.55 不切换", LiquidGlassForegroundTone.Light, state.refresh())
        sample = 0.6f
        assertEquals("0.6 切换深色", LiquidGlassForegroundTone.Dark, state.refresh())
        sample = 0.41f
        assertEquals("0.41 切回浅色", LiquidGlassForegroundTone.Light, state.refresh())
    }

    @Test
    fun `state keeps last valid luminance and survives empty samples`() {
        var sample: Float? = 0.7f
        val state = LiquidGlassReadabilityState(sampler = { sample })
        state.refresh()
        assertEquals("lastLuminance 记录有效采样", 0.7f, state.lastLuminance!!, 0.0001f)
        sample = null
        assertEquals(
            "采样器返回 null ⇒ 保持现有色调",
            LiquidGlassForegroundTone.Dark,
            state.refresh()
        )
        assertEquals("lastLuminance 不被 null 覆盖", 0.7f, state.lastLuminance!!, 0.0001f)
    }

    @Test
    fun `reset clears history and returns to conservative light`() {
        val state = LiquidGlassReadabilityState(sampler = { 0.9f })
        state.refresh()
        assertEquals(LiquidGlassForegroundTone.Dark, state.tone)
        state.reset()
        assertFalse("reset 后回到未判定", state.hasResolved)
        assertNull("reset 后没有亮度历史", state.lastLuminance)
        assertEquals(
            "reset 后未采样时是保守浅色",
            LiquidGlassForegroundTone.Light,
            state.tone
        )
    }

    @Test
    fun `state without sampler is inert and conservative`() {
        val state = LiquidGlassReadabilityState(sampler = null)
        assertEquals(
            "没有采样器也要给出保守值（浅色），不能崩",
            LiquidGlassForegroundTone.Light,
            state.refresh()
        )
        assertFalse("没采样器 ⇒ 永远不算已判定", state.hasResolved)
    }
}
