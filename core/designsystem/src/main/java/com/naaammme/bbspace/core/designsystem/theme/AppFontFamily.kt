package com.naaammme.bbspace.core.designsystem.theme

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

// 这里原先还有一份 `materialTypography()`（"默认阶梯 + 应用字体 + 标题 SemiBold"），
// 全仓零调用，已随 BACKLOG UI-08 删除。MATERIAL 档的排版本体在 `Typography.kt`
// 的 `BaseTypography` / `createTypography(fontScale)`，它同样带 AppFontFamily，
// 但标题字重刻意停在 **Medium**（不是那份草稿的 SemiBold）。
// 以后要调标题粗细请改 BaseTypography 那一处 —— 再开一份平行排版就会出现
// "两套字阶各自演化"那种没人能审计的状态。
