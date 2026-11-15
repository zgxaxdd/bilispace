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
    //
    // 2026-10-16：公式已抽到 [ContrastMath]（同包，调用点保持不变）。
    // 抽出的理由是"只留一份实现" —— 同一天里同一组公式被两套算法各算了一遍
    // （4.76:1 vs 2.10:1，后者漏了"合成后再线性化"），把数学收到一处可以从结构上
    // 避免那种争议重现。本类的 8 条断言、阈值、棘轮值**一律未动**。

    private fun chan(c: Color): DoubleArray = ContrastMath.chan(c)

    private fun contrast(a: DoubleArray, b: DoubleArray): Double = ContrastMath.contrast(a, b)

    /** 前景（可能半透明）落在背景上的对比度。 */
    private fun ratioOn(fg: Color, bg: Color): Double = ContrastMath.ratioOn(fg, bg)

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
                        "低于次要文字下限 3.0:1（浅色已提至 AA，见下方棘轮记录）。",
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
        // 棘轮上调 3.12 → 4.5（2026-09-26）：浅色 onSurfaceVariant 0x99 → 0xBC（AA），
        // 用户反馈"浅色下设置页部分文字看不清"后拍板，旧 3.1~3.4:1 不再使用
        val floor = 4.5
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

    // ── 语义角色色审计（iOS 升级阶段 B，BiliColorRoles）─────────────────────
    //
    // BiliColorRoles 是组件层的角色真值源；它与 iOSColorScheme 的关系必须钉死，
    // 否则两处取值会漂移（角色层改了 scheme 忘改、或反之）。

    /** secondaryLabel 必须与 onSurfaceVariant 同值（Apple secondaryLabel 的单一来源）。 */
    @Test
    fun iosSecondaryLabelMatchesScheme() {
        assertTrue(iosColorRoles(dark = false).secondaryLabel == light.onSurfaceVariant)
        assertTrue(iosColorRoles(dark = true).secondaryLabel == dark.onSurfaceVariant)
    }

    /** separator 必须与 outlineVariant 同值（同一根 hairline 语义，不许两套灰）。 */
    @Test
    fun iosSeparatorMatchesScheme() {
        assertTrue(iosColorRoles(dark = false).separator == light.outlineVariant)
        assertTrue(iosColorRoles(dark = true).separator == dark.outlineVariant)
    }

    /**
     * 三级文字（占位/时间戳）下限 1.6:1。
     *
     * Apple tertiaryLabel（30% 灰）合成到本仓 grouped 背景 F2F2F7 上实测 1.71:1
     * （Apple 的比例是按纯白算的，灰底上会再低一档）。它承载的本来就是"最弱信息"，
     * 不按正文/次要档要求；地板取实测值下取整——它的角色语义就是"存在但退后"，
     * 若要"可读性优先"请改用 secondaryLabel 而不是抬这个值的 alpha（那会破坏
     * 与 Apple 的取值对齐）。分档依据：WCAG 对 placeholder/低强调文本按大字号
     * 以下豁免，这里只防"退化到看不见"。
     */
    @Test
    fun iosTertiaryLabelMeetsFloor() {
        for ((name, scheme) in listOf("浅色" to light, "深色" to dark)) {
            val roles = iosColorRoles(dark = name == "深色")
            for ((bgName, bg) in textBackgrounds(scheme)) {
                val r = ratioOn(roles.tertiaryLabel, bg)
                assertTrue(
                    "$name tertiaryLabel 坐在 $bgName 上只有 ${"%.2f".format(r)}:1，低于三级文字地板 1.6:1",
                    r >= 1.6
                )
            }
        }
    }

    /** 破坏性红（systemRed）在卡片底色上必须可读（动作面板/警告框按钮的承载面）。 */
    @Test
    fun iosDestructiveMeetsFloor() {
        val cards = listOf(
            "浅色 surfaceContainerLow" to light.surfaceContainerLow,
            "深色 surfaceContainerLow" to dark.surfaceContainerLow
        )
        for ((name, bg) in cards) {
            val roles = iosColorRoles(dark = name.startsWith("深"))
            val r = ratioOn(roles.destructive, bg)
            assertTrue("$name destructive 坐在卡片底上只有 ${"%.2f".format(r)}:1，低于 3.0:1", r >= 3.0)
        }
    }

    /** AA 预案常量必须真的达到 4.5:1（切不切换等设计拍板，但数值本身必须有效）。 */
    @Test
    fun secondaryLabelAAVariantActuallyMeetsAA() {
        val lightAA = Color(SecondaryLabelAAVariants.LIGHT)
        val darkAA = Color(SecondaryLabelAAVariants.DARK)
        assertTrue("浅色 AA 变体在 background 上未达 4.5:1", ratioOn(lightAA, light.background) >= 4.5)
        assertTrue("深色 AA 变体在 background 上未达 4.5:1", ratioOn(darkAA, dark.background) >= 4.5)
    }
}
