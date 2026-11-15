package com.naaammme.bbspace.core.designsystem.theme.scheme

import androidx.compose.ui.graphics.Color

/**
 * 扩展种子风格的**预设种子色**（纯数据）。
 *
 * 上游来源：BiliPai `core/theme/SeedPresetColors.kt`。与"持久化的 ThemeColors 索引"解耦：
 * 这份列表只服务"从预设里挑一个种子色"，索引变化不影响已保存的主题。
 *
 * 19 色 / 19 名，一一对应（顺序即展示顺序）。名称是中文，与上游逐字一致。
 *
 * ## 与 `ThemeConfig.PresetColors` 的关系（2026-10-18 去重结论：**不合并**）
 * 两份列表**不是重复数据**：`PresetColors` 是"持久化的主题色下标索引"
 * （20 项、短名、含哔哩粉/蓝与黑），本列表是"种子预设"（19 项、长名、含默认绿、
 * 无黑），且粉色红通道不同（`FF7299` vs `FB7299`）。合并任一份都会改掉一侧的
 * 持久化语义或测试契约（`SeedPresetColorsTest` 钉住 19 色与首尾值）。详见
 * `docs/2026-10-18-B组-未接线符号接线步骤.md` §4。
 */
internal val SeedPresetColors: List<Color> = listOf(
    Color(0xFF5CB67B), Color(0xFFFF7299), Color(0xFFF44336), Color(0xFFFF9800),
    Color(0xFFFFC107), Color(0xFFFFEB3B), Color(0xFFCDDC39), Color(0xFF8BC34A),
    Color(0xFF4CAF50), Color(0xFF009688), Color(0xFF00BCD4), Color(0xFF03A9F4),
    Color(0xFF2196F3), Color(0xFF3F51B5), Color(0xFF9C27B0), Color(0xFF673AB7),
    Color(0xFF607D8B), Color(0xFF795548), Color(0xFF9E9E9E),
)

/** 与 [SeedPresetColors] 按下标一一对应的中文名（上游 `SeedPresetColorNames`）。 */
internal val SeedPresetColorNames: List<String> = listOf(
    "默认绿", "粉红色", "红色", "橙色", "琥珀色", "黄色", "酸橙色", "浅绿色", "绿色",
    "青色", "蓝绿色", "浅蓝色", "蓝色", "靛蓝色", "紫色", "深紫色", "蓝灰色", "棕色", "灰色",
)
