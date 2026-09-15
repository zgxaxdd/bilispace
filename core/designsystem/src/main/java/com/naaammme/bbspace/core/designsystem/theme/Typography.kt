package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// tnum：表格数字（等宽数字），用于计数/时长等数字文本，避免数值刷新时宽度抖动；
// 仅作用于承载数字的样式，中文字形不受影响，不引入任何字体资源。
//
// 关于字距（letterSpacing）：以下取值按**中文横排**校准。
// Material 默认值（如 bodyLarge 0.5sp / bodyMedium 0.25sp）是为拉丁字母设计的，
// 中文没有"词间空格"的概念，照搬会被撑得很散、显得松散不精致。因此正文与标签类
// 收紧到 ~0.1sp；胶囊里的小标签（labelSmall）继续收到 0.2sp 是为了保持一点呼吸感。
// 同时给西文/数字开启等宽对齐（"tnum" + "cv11"），让时长、播放量、弹幕数在列表中
// 纵向对齐、刷新时不跳宽——这是"精致感"最容易见效的一处。
private const val TABULAR_NUMS = "tnum"

private val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        // 22sp 粗体在中文里会显得笨重（汉字本身笔画就密），改 Medium 更接近现代客户端的标题观感
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    )
)

fun createTypography(fontScale: Float): Typography {
    if (fontScale == 1.0f) return BaseTypography

    return Typography(
        displayLarge = BaseTypography.displayLarge.copy(fontSize = BaseTypography.displayLarge.fontSize * fontScale),
        displayMedium = BaseTypography.displayMedium.copy(fontSize = BaseTypography.displayMedium.fontSize * fontScale),
        displaySmall = BaseTypography.displaySmall.copy(fontSize = BaseTypography.displaySmall.fontSize * fontScale),
        headlineLarge = BaseTypography.headlineLarge.copy(fontSize = BaseTypography.headlineLarge.fontSize * fontScale),
        headlineMedium = BaseTypography.headlineMedium.copy(fontSize = BaseTypography.headlineMedium.fontSize * fontScale),
        headlineSmall = BaseTypography.headlineSmall.copy(fontSize = BaseTypography.headlineSmall.fontSize * fontScale),
        titleLarge = BaseTypography.titleLarge.copy(fontSize = BaseTypography.titleLarge.fontSize * fontScale),
        titleMedium = BaseTypography.titleMedium.copy(fontSize = BaseTypography.titleMedium.fontSize * fontScale),
        titleSmall = BaseTypography.titleSmall.copy(fontSize = BaseTypography.titleSmall.fontSize * fontScale),
        bodyLarge = BaseTypography.bodyLarge.copy(fontSize = BaseTypography.bodyLarge.fontSize * fontScale),
        bodyMedium = BaseTypography.bodyMedium.copy(fontSize = BaseTypography.bodyMedium.fontSize * fontScale),
        bodySmall = BaseTypography.bodySmall.copy(fontSize = BaseTypography.bodySmall.fontSize * fontScale),
        labelLarge = BaseTypography.labelLarge.copy(fontSize = BaseTypography.labelLarge.fontSize * fontScale),
        labelMedium = BaseTypography.labelMedium.copy(fontSize = BaseTypography.labelMedium.fontSize * fontScale),
        labelSmall = BaseTypography.labelSmall.copy(fontSize = BaseTypography.labelSmall.fontSize * fontScale)
    )
}

