package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深浅色对比度审计（任务书 T5 第 4 条）。
 *
 * ## 为什么这件事不需要设备
 *
 * WCAG 2.1 的对比度是**纯算术**：相对亮度 + 一个比值。色值就在 [iOSColorScheme] 里，
 * 所以"正文在它的真实背景上够不够对比"这个问题可以在 JVM 上给出确定答案。
 *
 * ## 一个容易做错、做错了就整条测试白写的点：**半透明必须先合成**
 *
 * `onSurfaceVariant` 不是实色，是 `0x99EBEBF5` / `0x993C3C43` —— alpha 0x99 = 60%。
 * 直接拿 RGB 去算对比度会**高估**（拿的是未合成的原色，比落在背景上的实际颜色更亮/更暗）。
 * 所以先按 SrcOver 把前景合成到背景上，再算比值。
 *
 * 合成空间用 **sRGB 逐通道线性插值**，而不是先转线性光再插值：这与 Android 绘制
 * 半透明图层时实际做的事一致，测的就是用户真正看到的像素。用线性光空间插值会让
 * 浅色主题的算出来的比值偏乐观，那种"通过"是自欺欺人。
 *
 * ## 阈值分档（不是一刀切 4.5）
 *
 * WCAG 分档本身就是分角色的，混用会得到一个既不能指导决策、又容易为了变绿而
 * 被偷偷放宽的数：
 * - **正文角色**（AA 1.4.3 的 4.5:1）：`onSurface` / `onBackground` 承载的正文。
 * - **次要文字角色**（AA 1.4.6 大字号档的 3:1 为下限）：`onSurfaceVariant`。
 *   它在 iOS 语义里就是 secondary label，**实测浅色 3.13–3.44:1 不满足正文档**，
 *   这是刻意对齐 iOS 的结果（Apple 自己的 systemGray `#8E8E93` 在纯白上是 3.26:1）。
 *   本文件把它钉在 3.0 下限上并写明代价，见 [secondaryTextMeetsLargeTextFloor]。
 */
class ColorContrastTest {

    // ── WCAG 计算 ──────────────────────────────────────────────────────────

    /** 拆出 0–255 的整型通道（[Color] 的通道是 0–1 Float，这里统一成 0–255 便于读）。 */
    private fun chan(c: Color): DoubleArray =
        doubleArrayOf(c.red * 255.0, c.green * 255.0, c.blue * 255.0)

    private fun alpha(c: Color): Double = c.alpha.toDouble()

    /** SrcOver 合成：前景按自身 alpha 叠到不透明背景上。 */
    private fun composeOver(fg: Color, bg: Color): DoubleArray {
        val a = alpha(fg)
        val f = chan(fg)
        val b = chan(bg)
        return DoubleArray(3) { i -> a * f[i] + (1.0 - a) * b[i] }
    }

    /** WCAG 2.x 相对亮度（sRGB 反伽马后按 0.2126/0.7152/0.0722 加权）。 */
    private fun relativeLuminance(rgb: DoubleArray): Double {
        fun lin(v: Double): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    private fun contrast(a: DoubleArray, b: DoubleArray): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }

    /** 前景（可能半透明）落在背景上的对比度。 */
    private fun ratioOn(fg: Color, bg: Color): Double = contrast(composeOver(fg, bg), chan(bg))

    // ── 被测色板 ───────────────────────────────────────────────────────────
    //
    // tint 只影响 primary/secondary/container 系列这些**派生**槽位，本文件断言的
    // 全部是常量槽位（onSurface / onSurfaceVariant / surface 系列），所以任何 tint
    // 都得到同样结论。取 B 站粉只是为了让调用与 IOSColorSchemeTest 一致。
    private val tint = Color(0xFFFB7299)
    private val light = iOSColorScheme(darkTheme = false, tint = tint)
    private val dark = iOSColorScheme(darkTheme = true, tint = tint)

    /**
     * 文字**真正会坐在上面**的背景。
     *
     * 刻意不含 `surfaceDim` / `surfaceBright`：它们是高程/遮罩用途的色，不承载文字。
     * 把不承载文字的颜色拉进断言只会制造"看着很全面其实无意义"的数字。
     * （附带结论：浅色 `onSurfaceVariant` 落在 surfaceDim `#D1D1D6` 上是 2.88:1，
     * 连次要文字档都不过 —— 但那条组合在代码里没有使用路径。）
     */
    private fun textBackgrounds(scheme: androidx.compose.material3.ColorScheme) = listOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainerLow(卡片底)" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh(分段控件底)" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest(Dock/滑块底)" to scheme.surfaceContainerHighest
    )

    // ── 断言 ───────────────────────────────────────────────────────────────

    /** 正文角色必须满足 WCAG AA 1.4.3（4.5:1）。 */
    @Test
    fun bodyTextMeetsWcagAA() {
        for ((name, scheme) in listOf("浅色" to light, "深色" to dark)) {
            for ((role, color) in listOf("onSurface" to scheme.onSurface, "onBackground" to scheme.onBackground)) {
                for ((bgName, bg) in textBackgrounds(scheme)) {
                    val r = ratioOn(color, bg)
                    assertTrue(
                        "$name 正文角色 $role 坐在 $bgName 上只有 ${"%.2f".format(r)}:1，" +
                            "低于 WCAG AA 要求的 4.5:1。",
                        r >= 4.5
                    )
                }
            }
        }
    }

    /**
     * 次要文字角色的下限设为 3.0:1（大字号/图形档），**不是** 4.5:1 —— 理由见类注释。
     *
     * 现状实测：浅色 3.13–3.44:1、深色 3.95–6.36:1。
     * 浅色这一档**不满足正文文字的 AA**，是刻意贴 iOS secondary label 的代价
     * （Apple systemGray 在白底上 3.26:1）。因此：
     * - 本断言保证它不会**继续恶化**到 3:1 以下；
     * - "把次要文字用成正文（例如卡片副标题、评论正文）是否合规"是一个
     *   **产品/设计决策**，不是能靠改断言解决的问题。若要修：把浅色
     *   `onSurfaceVariant` 的 alpha 从 0x99(0.60) 提到约 0xB8(0.72)，
     *   纯白底上即达 4.72:1；深色侧提到约 0xAD(0.68) 可在
     *   surfaceContainerHighest 上达 4.58:1。这会让全站次要文字明显变深，
     *   需要真机走查观感，故本任务不擅自改。
     */
    @Test
    fun secondaryTextMeetsLargeTextFloor() {
        for ((name, scheme) in listOf("浅色" to light, "深色" to dark)) {
            for ((bgName, bg) in textBackgrounds(scheme)) {
                val r = ratioOn(scheme.onSurfaceVariant, bg)
                assertTrue(
                    "$name onSurfaceVariant 坐在 $bgName 上只有 ${"%.2f".format(r)}:1，" +
                        "低于已冻结的次要文字下限 3.0:1（现状 3.13~6.36，见本方法注释）。",
                    r >= 3.0
                )
            }
        }
    }

    /**
     * 次要文字**不得悄悄变差**：把当前实测最小值钉住。
     *
     * 与上一条的分工：3.0 是"绝不能穿"的底线，这里是"不许往下走"的棘轮。
     * 制定日实测浅色最小 3.13（落在 surfaceContainerHigh）。只许升不许降 ——
     * 等哪天设计确认要把次要文字提到 AA 4.5，这个数会被一起推上去。
     */
    @Test
    fun secondaryTextFloorIsRatcheted() {
        val minLight = textBackgrounds(light).minOf { (_, bg) -> ratioOn(light.onSurfaceVariant, bg) }
        val floor = 3.12
        assertTrue(
            "浅色 onSurfaceVariant 的最小对比度现在是 ${"%.2f".format(minLight)}:1，" +
                "低于棘轮下限 $floor:1。这个值只许升：往下走意味着在让本就不足 AA 的" +
                "次要文字更难读。若这次改动是**故意**加深/减淡了它，请连同下限与上面" +
                "两个测试的注释一起更新并写明理由。",
            minLight >= floor
        )
    }

    /**
     * 自检：半透明合成这一步真的有在做。
     *
     * 如果哪天有人把 [ratioOn] 改成直接拿 `chan(fg)` 算（跳过合成），所有断言会
     * **集体变好**从而继续全绿 —— 那等于整套审计失效。这条测试就是为了让这种
     * "改坏成假绿"必须被看见。
     */
    @Test
    fun alphaCompositingIsActuallyApplied() {
        val translucent = Color(0x993C3C43) // 浅色 onSurfaceVariant
        val white = Color(0xFFFFFFFF)
        val composited = ratioOn(translucent, white)
        val naive = contrast(chan(translucent), chan(white))
        assertTrue(
            "onSurfaceVariant(60% 透明) 合成后对比度 $composited 与未合成原色算出的 " +
                "$naive 差距过小，说明 SrcOver 合成没真的生效（审计会变成假绿）。",
            naive - composited > 5.0
        )
        assertTrue("合成后的实测值应约等于 3.44:1", composited in 3.3..3.6)
    }
}
