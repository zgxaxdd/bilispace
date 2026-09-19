package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * iOS 排版阶梯（方案 2.6 节），映射到 M3 槽位供现有组件直接消费：
 *
 * | M3 槽位        | iOS 样式              | 规格          |
 * |----------------|-----------------------|---------------|
 * | displayLarge   | Large Title（大标题） | 34sp Bold     |
 * | displayMedium  | Title 1               | 28sp Bold     |
 * | displaySmall   | Title 2（区块标题）   | 22sp Bold     |
 * | headlineLarge  | Title 3（卡片标题）   | 20sp Semibold |
 * | headlineMedium | Headline              | 17sp Semibold |
 * | headlineSmall  | —                     | 16sp Semibold |
 * | titleLarge     | 页面 / 折叠态导航标题 | 17sp Semibold |
 * | titleMedium    | 强调列表文字          | 16sp Semibold |
 * | bodyLarge      | Body（正文 / 评论）   | 17sp Regular  |
 * | bodyMedium     | Subheadline（副文字） | 15sp Regular  |
 * | bodySmall      | Footnote（辅助信息）  | 13sp Regular  |
 * | labelMedium    | Caption 1（时间戳）   | 12sp Regular  |
 * | labelSmall     | Caption 2             | 11sp Regular  |
 *
 * 保留既有两项校准：中文横排 letterSpacing 收紧（正文 ~0.1sp / 标签 0.2sp），
 * 数字承载样式开 tnum 等宽（时长、播放量纵向对齐）。不引入任何字体资源，
 * FontFamily.Default 在中文环境下即系统字，无需 SF Pro。
 */
private val IOS_BASE_TYPOGRAPHY = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
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
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    )
)

/** iOS 模式字体工厂；[fontScale] 缩放逻辑与 Material 路径共用 [scaleTypography]。 */
fun iOSTypography(fontScale: Float): Typography = scaleTypography(IOS_BASE_TYPOGRAPHY, fontScale)
