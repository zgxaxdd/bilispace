package com.naaammme.bbspace.core.designsystem.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MotionTierPolicy` 的纯逻辑单测（无 Android、无 Compose 运行时）。
 *
 * 钉住的约定：
 * 1. 边界：3072MB **是**低端（`<=`）、3073MB 起中端；6144MB 起高端；
 * 2. 用户关动效 / 系统低内存标记 ⇒ 一律 Low（用户意图优先于设备性能）；
 * 3. 读不到内存（0MB）⇒ 保守 Medium，不许"默认满血"；
 * 4. 三档的系数必须**严格单调**（0 < 0.6 < 1）—— 这是分档有意义的前提；
 * 5. 低档不启用常驻形变（骨架/按压），中高档启用。
 */
class MotionTierPolicyTest {

    private fun resolve(
        isLowRamDevice: Boolean = false,
        totalRamMb: Int = 8192,
        animationEnabled: Boolean = true
    ): DeviceMotionTier = MotionTierPolicy.resolve(
        isLowRamDevice = isLowRamDevice,
        totalRamMb = totalRamMb,
        animationEnabled = animationEnabled
    )

    @Test
    fun `ram boundary is low at 3072 and medium at 3073`() {
        assertEquals("3072MB 是低端（含边界）", DeviceMotionTier.Low, resolve(totalRamMb = 3072))
        assertEquals("3073MB 起是中端", DeviceMotionTier.Medium, resolve(totalRamMb = 3073))
        assertEquals("2048MB 是低端", DeviceMotionTier.Low, resolve(totalRamMb = 2048))
    }

    @Test
    fun `ram boundary is medium below 6144 and high at 6144`() {
        assertEquals("6143MB 仍是中端", DeviceMotionTier.Medium, resolve(totalRamMb = 6143))
        assertEquals("6144MB 起是高端（含边界）", DeviceMotionTier.High, resolve(totalRamMb = 6144))
        assertEquals("12288MB 是高端", DeviceMotionTier.High, resolve(totalRamMb = 12288))
    }

    @Test
    fun `user animation switch wins over device class`() {
        assertEquals(
            "关动效 ⇒ Low（即便 16GB 设备）",
            DeviceMotionTier.Low,
            resolve(totalRamMb = 16384, animationEnabled = false)
        )
    }

    @Test
    fun `low ram device flag wins over ram reading`() {
        assertEquals(
            "系统标记为低内存 ⇒ Low（即便读数很大）",
            DeviceMotionTier.Low,
            resolve(isLowRamDevice = true, totalRamMb = 16384)
        )
    }

    @Test
    fun `unknown ram falls back to medium not high`() {
        assertEquals(
            "读不到内存（0MB）必须保守取中档，不许默认满血",
            DeviceMotionTier.Medium,
            resolve(totalRamMb = 0)
        )
    }

    @Test
    fun `motion scale is strictly monotonic across tiers`() {
        val low = MotionTierPolicy.motionScale(DeviceMotionTier.Low)
        val medium = MotionTierPolicy.motionScale(DeviceMotionTier.Medium)
        val high = MotionTierPolicy.motionScale(DeviceMotionTier.High)
        assertEquals("Low 的动效系数必须是 0（完全静止）", 0f, low, 0f)
        assertTrue("中档必须大于低档（$medium vs $low）", medium > low)
        assertTrue("高档必须大于中档（$high vs $medium）", high > medium)
        assertEquals("高档系数是 1", 1f, high, 0f)
    }

    @Test
    fun `glass cap forbids low tier and caps medium`() {
        assertEquals("低端不许挂玻璃面", 0f, MotionTierPolicy.glassStrengthCap(DeviceMotionTier.Low), 0f)
        assertEquals(
            "中端玻璃强度封顶 0.6",
            0.6f,
            MotionTierPolicy.glassStrengthCap(DeviceMotionTier.Medium),
            0f
        )
        assertEquals("高端 1.0", 1f, MotionTierPolicy.glassStrengthCap(DeviceMotionTier.High), 0f)
    }

    @Test
    fun `ambient motion is disabled only on low tier`() {
        assertFalse("Low 档不启用常驻形变", MotionTierPolicy.enablesAmbientMotion(DeviceMotionTier.Low))
        assertTrue("Medium 档启用", MotionTierPolicy.enablesAmbientMotion(DeviceMotionTier.Medium))
        assertTrue("High 档启用", MotionTierPolicy.enablesAmbientMotion(DeviceMotionTier.High))
    }

    @Test
    fun `breath cycle slows down on medium tier`() {
        assertEquals(
            "低/高两档保持基准周期（低档根本不呼吸）",
            1f,
            MotionTierPolicy.breathCycleMultiplier(DeviceMotionTier.Low),
            0f
        )
        assertTrue(
            "中端机呼吸周期必须变长（更低的重绘频率）",
            MotionTierPolicy.breathCycleMultiplier(DeviceMotionTier.Medium) > 1f
        )
        assertEquals(
            "高档保持基准周期",
            1f,
            MotionTierPolicy.breathCycleMultiplier(DeviceMotionTier.High),
            0f
        )
    }
}
