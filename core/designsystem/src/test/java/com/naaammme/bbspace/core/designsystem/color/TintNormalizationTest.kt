package com.naaammme.bbspace.core.designsystem.color

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取色"整理"与"合成"两步的单测。
 *
 * 这两步是「卡片上色好不好看」的全部数学，且都是纯函数 —— 所以能在 JVM 上逐条钉住。
 * 背景（真机实测 21 个真实封面色样本）：提取色的相对亮度中位 **0.051**、61% 低于 0.10；
 * HSV 饱和度中位 40%、47% 低于 30%。也就是说**大部分封面主色又暗又闷**，
 * 不整理就直接混进浅色卡片底，结果是一片灰 —— 这正是用户报的"很多卡片没有取色"。
 *
 * 注：`Color.luminance()` 走 android.graphics，JVM 单测里靠
 * `unitTests.isReturnDefaultValues = true` 返回 **0**。因此凡涉及亮度的分支，
 * 在 JVM 上**恒走"需要提亮"那一路** —— 下面的断言是按这个前提写的，
 * 不假装能测"已经够亮就不动"（那条只能靠真机或 Robolectric）。
 */
class TintNormalizationTest {

    private val white = Color.White

    // ── compositeTintOverBase：把半透明染色合成到不透明底上 ─────────────────

    @Test
    fun composite_resultIsOpaque() {
        // 关键不变量：合成结果必须**不透明**。否则盖到卡片上会透出页面背景，
        // 颜色偏冷偏淡 —— 这正是原先"染色不可见"的原因之一。
        val out = compositeTintOverBase(Color(0x40FF0000), white)
        assertEquals(1f, out.alpha, 0.0001f)
    }

    @Test
    fun composite_fullyOpaqueInput_passesThrough() {
        val opaque = Color(0xFF3366CC)
        assertEquals(opaque, compositeTintOverBase(opaque, white))
    }

    @Test
    fun composite_fullyTransparentInput_returnsBase() {
        assertEquals(white, compositeTintOverBase(Color.Transparent, white))
    }

    @Test
    fun composite_blendsPerChannel_srgbLinear() {
        // 50% 黑叠在白上 = 中灰（逐通道 sRGB 线性插值，与 Android 绘制半透明图层一致）
        val out = compositeTintOverBase(Color(0x80000000), white)
        assertEquals(0.5f, out.red, 0.01f)
        assertEquals(0.5f, out.green, 0.01f)
        assertEquals(0.5f, out.blue, 0.01f)
    }

    @Test
    fun composite_lowAlphaStaysCloseToBase() {
        // 浅色主题的 alpha 是 0.28 —— 结果应该"看得出底色的偏移，但仍是浅色"
        val tinted = Color(0x47FF0000) // alpha 0.28 的红
        val out = compositeTintOverBase(tinted, white)
        assertTrue("应仍偏亮（浅色卡片）", out.red > 0.5f)
        assertTrue("红通道应高于绿通道（有染色）", out.red > out.green)
    }

    // ── normalizeTintColor：明度归一化 + 饱和度下限 ─────────────────────────

    @Test
    fun normalize_darkColor_getsLifted() {
        // JVM 下 luminance() 恒为 0 → 必走提亮分支。
        // 断言：提亮后最大通道显著大于原值（原 #080820 最大通道 0.125）
        val dark = Color(0xFF080820)
        val out = normalizeTintColor(dark, isDarkTheme = false)
        val before = maxOf(dark.red, dark.green, dark.blue)
        val after = maxOf(out.red, out.green, out.blue)
        assertTrue("深色应被提亮：before=$before after=$after", after > before)
    }

    @Test
    fun normalize_preservesHue() {
        // 色相由"哪个通道最大 + 次大通道的相对位置"决定，等比缩放不该改变它。
        // 用蓝 > 绿 > 红的深蓝做样本。
        val dark = Color(0xFF101828)
        val out = normalizeTintColor(dark, isDarkTheme = false)
        assertTrue("蓝应仍是最大通道", out.blue >= out.green && out.green >= out.red)
    }

    @Test
    fun normalize_doesNotChangeDarkTheme() {
        // 深色主题卡片底色本来就暗，深色封面混上去是可见的；提亮反而会发灰。
        val dark = Color(0xFF080820)
        assertEquals(dark, normalizeTintColor(dark, isDarkTheme = true))
    }

    @Test
    fun normalize_keepsAlpha() {
        val dark = Color(0xFF080820)
        val out = normalizeTintColor(dark, isDarkTheme = false)
        assertEquals(dark.alpha, out.alpha, 0.0001f)
    }

    @Test
    fun normalize_pureBlack_fallsBackToNeutralWithoutCrash() {
        // 纯黑没有色相信息可保留；不能崩、也不能返回 NaN
        val out = normalizeTintColor(Color.Black, isDarkTheme = false)
        assertTrue("不应是 NaN", !out.red.isNaN() && !out.green.isNaN() && !out.blue.isNaN())
        assertEquals(1f, out.alpha, 0.0001f)
    }

    // ── boostSaturationIfNeeded：饱和度下限 ─────────────────────────────────

    /** HSV 饱和度（max-min）/max，与实现同一定义。 */
    private fun saturation(c: Color): Float {
        val maxC = maxOf(c.red, c.green, c.blue)
        if (maxC <= 0f) return 0f
        return (maxC - minOf(c.red, c.green, c.blue)) / maxC
    }

    @Test
    fun boost_lowSaturation_isRaisedToFloor() {
        // #202820 饱和度约 0.20，低于 0.30 的下限
        val muted = Color(0xFF202820)
        assertTrue("样本本身应低饱和", saturation(muted) < 0.30f)
        val out = boostSaturationIfNeeded(muted)
        assertTrue("应被补到下限以上：${saturation(out)}", saturation(out) >= 0.29f)
    }

    @Test
    fun boost_alreadySaturated_isUnchanged() {
        // #D05018 饱和度约 0.88，远高于下限 —— 不该被改动（否则"本来很艳的封面会更艳"）
        val vivid = Color(0xFFD05018)
        assertTrue("样本本身应高饱和", saturation(vivid) > 0.30f)
        assertEquals(vivid, boostSaturationIfNeeded(vivid))
    }

    @Test
    fun boost_preservesValue() {
        // 补饱和只动非最大通道，最大通道不变 → HSV 的 V 不变
        val muted = Color(0xFF202820)
        val out = boostSaturationIfNeeded(muted)
        assertEquals(
            maxOf(muted.red, muted.green, muted.blue),
            maxOf(out.red, out.green, out.blue),
            0.0001f
        )
    }

    @Test
    fun boost_pureGray_isUnchanged() {
        // 纯灰没有色相可保。强行给灰色指定色相会得到"随机偏色"，比灰更糟。
        val gray = Color(0xFF808080)
        assertEquals(gray, boostSaturationIfNeeded(gray))
    }

    @Test
    fun boost_black_doesNotCrash() {
        val out = boostSaturationIfNeeded(Color.Black)
        assertTrue(!out.red.isNaN())
    }

    // ── 端到端：深色封面最终应"看得出颜色" ──────────────────────────────────

    @Test
    fun endToEnd_darkCover_producesVisibleTintOnWhiteCard() {
        // 这是本功能的核心验收：一个又暗又闷的封面主色，经过
        // normalize → 混入白底 0.68 → 合成，最终应是一个**可辨认的浅色**，
        // 而不是与白底无差别的灰。
        val darkCover = Color(0xFF080820)
        val normalized = normalizeTintColor(darkCover, isDarkTheme = false)
        val blended = androidx.compose.ui.graphics.lerp(white, normalized, 0.68f)
            .copy(alpha = 0.28f)
        val final = compositeTintOverBase(blended, white)

        val sat = saturation(final)
        assertTrue(
            "深色封面最终饱和度只有 $sat（颜色 ${final.value.toString(16)}）—— " +
                "用户会把这种结果读成「没取到色」。",
            sat > 0.05f
        )
        assertNotEquals("最终色不应等于白底", white, final)
        assertEquals("最终必须是实色", 1f, final.alpha, 0.0001f)
    }
}
