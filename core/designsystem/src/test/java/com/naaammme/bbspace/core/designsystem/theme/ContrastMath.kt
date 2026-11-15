package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * WCAG 2.x 对比度的**纯数学**（无 Compose 依赖，除入参 [Color]）。
 *
 * ## 为什么单独成一支
 * 这套公式原先只作为 `ColorContrastTest` 的 private 助手存在。2026-10-16 的
 * "渐变停靠"争议里，同一个量被两套算法各算了一遍（4.76:1 vs 2.10:1），根因是
 * **一方漏了"合成后再线性化"这一步**。把公式收到一处、只留一份实现，是让那种
 * 争议在结构上不可能再发生 —— 要算对比度就只有这一条路。
 *
 * ## 顺序不能颠倒（这正是那次争议的核心）
 * 半透明混光发生在 **sRGB 域**（Android 绘制管线就是这么做的），而 WCAG 的相对
 * 亮度必须对**最终显示的 sRGB 值**做线性化。所以正确顺序是：
 *
 * ```
 * 1) 合成：在 sRGB 域做 SrcOver
 * 2) 线性化：对合成后的 sRGB 值做反伽马
 * 3) 比值：(L_亮 + 0.05) / (L_暗 + 0.05)
 * ```
 *
 * **把 `1 - alpha` 直接当线性亮度**（跳过第 1 步的合成语义、拿 alpha 当衰减）
 * 会得到约**一半**的比值：`alpha=0.55` 时正确值 4.76:1、漏步值 2.10:1。
 * 这不是"另一个口径"，是漏了一步。
 *
 * ## 阈值分档
 * WCAG 分档本身分角色，混用会得到一个既不能指导决策、又容易为了变绿而被偷偷
 * 放宽的数：正文 AA 1.4.3 = 4.5:1；大字号/图形档 = 3.0:1。调用方自行按角色取档。
 *
 * 与 [ColorContrastTest] 的关系：那边是**色板审计**（断言各语义角色达标），本文件
 * 只提供算术。审计的 8 条断言、阈值、棘轮值都不因本文件抽出而改变。
 */
internal object ContrastMath {

    /** 拆出 0–255 的整型通道（[Color] 的通道是 0–1 Float，这里统一成 0–255 便于读）。 */
    fun chan(c: Color): DoubleArray =
        doubleArrayOf(c.red * 255.0, c.green * 255.0, c.blue * 255.0)

    fun alpha(c: Color): Double = c.alpha.toDouble()

    /** SrcOver 合成：前景按自身 alpha 叠到不透明背景上。 */
    fun composeOver(fg: Color, bg: Color): DoubleArray {
        val a = alpha(fg)
        val f = chan(fg)
        val b = chan(bg)
        return DoubleArray(3) { i -> a * f[i] + (1.0 - a) * b[i] }
    }

    /** WCAG 2.x 相对亮度（sRGB 反伽马后按 0.2126/0.7152/0.0722 加权）。 */
    fun relativeLuminance(rgb: DoubleArray): Double {
        fun lin(v: Double): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    fun contrast(a: DoubleArray, b: DoubleArray): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }

    /** 前景（可能半透明）落在背景上的对比度。 */
    fun ratioOn(fg: Color, bg: Color): Double = contrast(composeOver(fg, bg), chan(bg))

    // ── 下面两支服务于"黑遮罩压在白画面上"这一最坏情况 ────────────────────────
    //
    // 播放页浮层是可读性最脆的地方：底下可能是**纯白画面**（最坏情况），
    // 压一层黑色遮罩（不透明度 `alpha`），白字坐在上面。这两支把该情形的
    // 对比度算出来，供渐变停靠判据（`PlayerOverlayScrimContrastTest`）使用。

    /**
     * 黑遮罩（不透明度 [scrimAlpha]）压在最坏情况**纯白画面**上，与白字组成的对比度。
     *
     * - 合成（sRGB 域）：`comp = (1 - alpha) * 255`
     * - 文字自身也先按 [textAlpha] 合成到该底上（0.92 的常态字会算出略低的比值）
     *
     * @param textAlpha 文字不透明度；纯白 = 1.0。**用 1.0 以外的值时，结论只能按
     *   「UI 组件级」对待，不是正文 AA** —— 见判据类 KDoc。
     */
    fun whiteTextOnBlackScrimOverWhiteFrame(scrimAlpha: Double, textAlpha: Double = 1.0): Double {
        val a = scrimAlpha.coerceIn(0.0, 1.0)
        val bg = doubleArrayOf((1.0 - a) * 255.0, (1.0 - a) * 255.0, (1.0 - a) * 255.0)
        val t = textAlpha.coerceIn(0.0, 1.0)
        val text = doubleArrayOf(255.0 * t + bg[0] * (1.0 - t), 255.0 * t + bg[1] * (1.0 - t), 255.0 * t + bg[2] * (1.0 - t))
        return contrast(text, bg)
    }

    /**
     * 反解：白字要在最坏情况白画面上达到 [target] 对比度，所需的最小黑遮罩不透明度。
     *
     * 用于**校准阈值本身**而不是拍一个数：正文 AA 4.5:1 对应 ≈0.5347，
     * 向上取整留余量即判据里用的 0.535。
     */
    fun minScrimAlphaForWhiteText(target: Double): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(200) {
            val mid = (lo + hi) / 2.0
            if (whiteTextOnBlackScrimOverWhiteFrame(mid) < target) lo = mid else hi = mid
        }
        return hi
    }
}
