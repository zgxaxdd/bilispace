package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.naaammme.bbspace.core.designsystem.R

/**
 * 应用字体：Latin/数字用 [Inter](https://github.com/rsms/inter)（SIL OFL，
 * 许可全文见 core/designsystem/INTER-OFL-LICENSE.txt），中文回退系统字体
 * （Compose FontFamily 的缺字回退链；OPPO 机型中文为 OPPO Sans 系）——
 * 拉丁与数字获得一致的现代观感，中文零体积成本。
 *
 * 只内嵌 4 个静态字重（Regular/Medium/SemiBold/Bold，各 ~620KB）：
 * 排版阶梯用到的字重都落在其上；Light/ExtraBold 由系统就近匹配（300→400、800→700）。
 * 加载方式为 res/font 资源内嵌：首帧即可用、无网络依赖、无字体闪烁（FOUT）。
 */
val AppFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Material 风格排版：默认阶梯 + 应用字体 + 标题层级加粗。 */
fun materialTypography(): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = AppFontFamily),
        displayMedium = displayMedium.copy(fontFamily = AppFontFamily),
        displaySmall = displaySmall.copy(fontFamily = AppFontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = AppFontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = AppFontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = AppFontFamily),
        // 标题层级加粗：M3 默认 titleLarge/titleMedium 为 Regular/Medium，
        // 与正文的区分度不足（用户反馈"重要性高的标题加粗"）
        titleLarge = titleLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = AppFontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = AppFontFamily),
        bodySmall = bodySmall.copy(fontFamily = AppFontFamily),
        labelLarge = labelLarge.copy(fontFamily = AppFontFamily),
        labelMedium = labelMedium.copy(fontFamily = AppFontFamily),
        labelSmall = labelSmall.copy(fontFamily = AppFontFamily)
    )
}
