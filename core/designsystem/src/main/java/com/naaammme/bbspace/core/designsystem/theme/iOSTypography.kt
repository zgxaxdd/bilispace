package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * iOS 排版阶梯（方案 2.6 节），映射到 M3 槽位供现有组件直接消费：
 *
 * | M3 槽位        | iOS 样式              | 规格           |
 * |----------------|-----------------------|----------------|
 * | displayLarge   | Large Title（大标题） | 34sp Heavy     |
 * | displayMedium  | Title 1               | 28sp Bold      |
 * | displaySmall   | Title 2（区块标题）   | 22sp Bold      |
 * | headlineLarge  | Title 3（卡片标题）   | 20sp Bold      |
 * | headlineMedium | Headline              | 17sp Bold      |
 * | headlineSmall  | —                     | 16sp SemiBold  |
 * | titleLarge     | 页面 / 折叠态导航标题 | 17sp SemiBold  |
 * | titleMedium    | 强调列表文字          | 16sp SemiBold  |
 * | bodyLarge      | Body（正文 / 评论）   | 17sp Regular   |
 * | bodyMedium     | Subheadline（副文字） | 15sp Regular   |
 * | bodySmall      | Footnote（辅助信息）  | 13sp Light     |
 * | labelLarge     | 卡片标题 / 主标签     | 13sp Bold      |
 * | labelMedium    | Caption 1（时间戳）   | 12sp Medium    |
 * | labelSmall     | Caption 2             | 11sp Light     |
 *
 * **字重梯度是这套阶梯的核心，不是附带属性**：此前的档位几乎全部挤在
 * Regular(400)~SemiBold(600) 之间，正文与辅助文字**同为 Regular**，
 * 卡片标题与页面标题**同为 SemiBold** —— 视觉上只剩"大小"一个维度在区分层级，
 * 界面因此读起来平、没有活力（用户反馈「字重没有做」）。
 * 现在把梯度拉开到 **Light(300) → Regular(400) → Medium(500) → SemiBold(600)
 * → Bold(700) → Heavy(900)**：大标题更重、辅助文字更轻，层级一眼可辨。
 *
 * 设备侧已确认支持全量字重（PLC110 / ColorOS 16）：
 * - 拉丁：`RobotoFlex-Regular.ttf` 是可变态字体，`fonts.xml` 暴露 wght 100–900；
 * - 中文：`NotoSansCJK-Regular.ttc` 通过 `wght` 轴同样暴露 100–900。
 * 所以 Light / Heavy 在这台机器上是真实渲染的，不是被吸附到 Regular/Bold。
 * （若换到只提供 Regular/Bold 的机型，300 会吸附 400、900 会吸附 700，层级仍在。）
 *
 * 保留既有两项校准：中文横排 letterSpacing 收紧（正文 ~0.1sp / 标签 0.2sp），
 * 数字承载样式开 tnum 等宽（时长、播放量纵向对齐）。不引入任何字体资源，
 * FontFamily.Default 在中文环境下即系统字，无需 SF Pro。
 */
private val IOS_BASE_TYPOGRAPHY = Typography(
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        // iOS Large Title 用 Bold（HIG 规格）；ExtraBold 是自行加码，
        // 中文系统字 900 档在低端机型容易挤压行高（方案 §7.1 复核项）
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    )
)

/** iOS 模式字体工厂；[fontScale] 缩放逻辑与 Material 路径共用 [scaleTypography]。 */
fun iOSTypography(fontScale: Float): Typography = scaleTypography(IOS_BASE_TYPOGRAPHY, fontScale)
