package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * 应用字体 = **系统自带字体**（用户要求，2026-10-07）。
 *
 * 此前内嵌 Inter 四个字重（res/font/inter_*.otf，共 ~2.4MB）只覆盖拉丁与数字，中文回落系统字体；
 * 改为完全跟随系统后：
 * 1. 任何机型/系统字体设置（OPPO Sans 等厂商字体、无障碍字体缩放）都与系统一致，
 *    中英混排也不再有"两种字族拼在一起"的字面差异；
 * 2. 省掉 4 个内嵌字重的体积（字体文件已一并删除，见 INTER-OFL-LICENSE.txt 说明）。
 *
 * 保留 [AppFontFamily] 这个别名而不让各处直接写 `FontFamily.Default`：全仓排版阶梯
 * （`Typography.kt` / `iOSTypography.kt`）只从这里取字族，将来要换字体仍然只改这一处。
 */
val AppFontFamily: FontFamily = FontFamily.Default
