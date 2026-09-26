package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 主题基建的色板语义断言（方案 5.1 P0 / 5.2 DoD 第 8 条）。
 *
 * 只断言**固定常量槽位**：tint 派生槽（primary / primaryContainer 等）经
 * Color.tone() 走 android.graphics HSV，在 JVM 单测桩下不产生真实色值，
 * 其正确性由真机走查覆盖。
 */
class IOSColorSchemeTest {

    // ===== 硬性规则：surface == background（页面底色，非卡片色）=====

    @Test
    fun `iOS 浅色 surface 等于 background 且为 grouped 灰底`() {
        val scheme = iOSColorScheme(darkTheme = false, tint = Color(0xFFFB7299))
        assertEquals(scheme.background, scheme.surface)
        assertEquals(Color(0xFFF2F2F7), scheme.surface)
    }

    @Test
    fun `iOS 深色 surface 等于 background 且分纯黑与染色炭底两档`() {
        // isPureBlack=true：纯黑 OLED 底
        val pure = iOSColorScheme(darkTheme = true, tint = Color(0xFFFB7299), isPureBlack = true)
        assertEquals(pure.background, pure.surface)
        assertEquals(Color.Black, pure.surface)
        // isPureBlack=false（默认）：seed 染色的炭底——现代深色（Telegram/Discord/Spotify
        // 的通行做法），底不再取纯黑，解决"纯黑单调"（用户反馈 2026-09-26）
        val tinted = iOSColorScheme(darkTheme = true, tint = Color(0xFFFB7299))
        assertEquals(tinted.background, tinted.surface)
        assertTrue("染色炭底应比纯黑亮（有可感知的层底）", tinted.surface != Color.Black)
        assertTrue("染色炭底亮度应低于卡片色", tinted.surface.luminance() < tinted.surfaceContainerLow.luminance())
    }

    // ===== 灰底白卡分层：卡片白只给 surfaceContainerLow =====

    @Test
    fun `iOS 浅色卡片底为纯白而页面底为灰`() {
        val scheme = iOSColorScheme(darkTheme = false, tint = Color(0xFFFB7299))
        assertEquals(Color.White, scheme.surfaceContainerLow)
        assertTrue(scheme.surfaceContainerLow != scheme.surface)
    }

    @Test
    fun `iOS 深色卡片底为 seed 染色炭色且随 isPureBlack 只改底色`() {
        // 深色重设计（2026-09-26）：卡片/容器进入 seed 染色炭色阶梯（4–10% tint），
        // 两档 isPureBlack 的卡片色一致（只有底色不同）
        val tint = Color(0xFFFB7299)
        val scheme = iOSColorScheme(darkTheme = true, tint = tint)
        val pure = iOSColorScheme(darkTheme = true, tint = tint, isPureBlack = true)
        assertEquals(scheme.surfaceContainerLow, pure.surfaceContainerLow)
        assertEquals(scheme.surfaceContainerHigh, pure.surfaceContainerHigh)
        // 染色必须存在：卡片底不再等于 Apple 原始 #1C1C1E
        assertTrue(
            "卡片底应带 seed 染色（不等于原始 #1C1C1E）",
            scheme.surfaceContainerLow != Color(0xFF1C1C1E)
        )
        // 仍是暗色：亮度显著低于 onSurface 白
        assertTrue(scheme.surfaceContainerLow.luminance() < 0.1f)
    }

    // ===== 文字与分隔线（iOS label / separator 语义）=====

    @Test
    fun `iOS 深色正文为白浅色为黑`() {
        assertEquals(Color.White, iOSColorScheme(darkTheme = true, tint = Color(0xFFFB7299)).onSurface)
        assertEquals(Color.Black, iOSColorScheme(darkTheme = false, tint = Color(0xFFFB7299)).onSurface)
    }

    @Test
    fun `iOS 分隔线语义色 outlineVariant 非纯灰常量而是半透明黑`() {
        val light = iOSColorScheme(darkTheme = false, tint = Color(0xFFFB7299))
        assertEquals(Color(0x4A3C3C43), light.outlineVariant)
        val dark = iOSColorScheme(darkTheme = true, tint = Color(0xFFFB7299))
        assertEquals(Color(0xA6545458), dark.outlineVariant)
    }

    // ===== 固定圆角档（不随 CornerStyle 漂移）=====

    @Test
    fun `iOSShapes 固定档 8-10-12-16-20`() {
        val shapes = iOSShapes()
        assertEquals(8f, (shapes.extraSmall as RoundedCornerShape).cornerTopStartPx())
        assertEquals(10f, (shapes.small as RoundedCornerShape).cornerTopStartPx())
        assertEquals(12f, (shapes.medium as RoundedCornerShape).cornerTopStartPx())
        assertEquals(16f, (shapes.large as RoundedCornerShape).cornerTopStartPx())
        assertEquals(20f, (shapes.extraLarge as RoundedCornerShape).cornerTopStartPx())
    }

    // ===== 无缝 morph 不变量：card 与 player 必须同值（DoD 第 9 条）=====

    @Test
    fun `BiliRadius card 与 player 同值`() {
        // 第 86 行是**不变量**：无缝 morph 两端圆角同值才不跳，这条永远不能松。
        assertEquals(BiliRadius.card, BiliRadius.player)
        // 第 89 行是**快照**：把当前取值钉住，让改动必须是自觉的。
        // 16 → 20（2026-09-22）：真机截图对比后判定 16dp 在 density 560 下弧长偏短、
        // 整屏仍偏"方"，与 Dock 32 / 弹层 28 也拉不出半径阶梯。改值请连同这条一起改。
        assertEquals(20.dp, BiliRadius.card)
    }

    // ===== iOS 排版阶梯 =====

    @Test
    fun `iOSTypography 大标题 34sp 且随 fontScale 缩放`() {
        assertEquals(34.sp, iOSTypography(1.0f).displayLarge.fontSize)
        assertEquals(17.sp, iOSTypography(1.0f).titleLarge.fontSize)
        assertEquals((34 * 1.2f).sp, iOSTypography(1.2f).displayLarge.fontSize)
    }

    // ===== Material 路径零改动抽查 =====

    @Test
    fun `Material 默认圆角档不受 iOS 工厂影响`() {
        val rounded = buildShapes(CornerStyle.ROUNDED)
        assertEquals(16f, (rounded.medium as RoundedCornerShape).cornerTopStartPx())
        assertEquals(24f, (rounded.large as RoundedCornerShape).cornerTopStartPx())
    }

    /** dp 圆角在 1x 密度下的像素值；DpCornerSize 不依赖容器尺寸，传任意 Size 即可。 */
    private fun RoundedCornerShape.cornerTopStartPx(): Float =
        topStart.toPx(Size(100f, 100f), Density(1f))
}
