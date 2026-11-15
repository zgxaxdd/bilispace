/*
 * Adapted from flex_seed_scheme 5.0.1, Copyright 2022-2025 Mike Rydstrom.
 * BSD-3-Clause.
 *
 * 上游来源：BiliPai `origin/main:app/src/main/java/com/android/purebilibili/core/theme/SeedPaletteStyle.kt`
 */
package com.naaammme.bbspace.core.designsystem.theme.scheme

/**
 * HCT 种子配色体系的**风格枚举**（纯数据，无 HCT 依赖）。
 *
 * 分三类：
 * - [Standard]：沿用本仓既有配色管线（不启用种子风格）；
 * - dynamic（`isDynamic = true`）：由 MaterialKolor / MCU 的 HCT scheme 计算，9 种；
 * - 固定表（`isDynamic = false`）：用 [SeedTones.forStyle] 的写死 tone 表，12 种。
 *
 * 与上游逐条一致（名称、顺序、label、isDynamic、[enabled]、[fromStored]）。持久化只存
 * `name`：枚举改名会让旧值回落到 [Standard]（上游同款容错）。
 */
enum class SeedPaletteStyle(val label: String, val isDynamic: Boolean = false) {
    Standard("Standard"),
    TonalSpot("TonalSpot", true),
    Fidelity("Fidelity", true),
    Monochrome("Monochrome", true),
    Neutral("Neutral", true),
    Vibrant("Vibrant", true),
    Expressive("Expressive", true),
    Content("Content", true),
    Rainbow("Rainbow", true),
    FruitSalad("FruitSalad", true),
    Material("Material", false),
    Material3Legacy("Material3Legacy", false),
    Soft("Soft", false),
    Vivid("Vivid", false),
    VividSurfaces("VividSurfaces", false),
    HighContrast("HighContrast", false),
    UltraContrast("UltraContrast", false),
    Jolly("Jolly", false),
    VividBackground("VividBackground", false),
    OneHue("OneHue", false),
    CandyPop("CandyPop", false),
    Chroma("Chroma", false),
    ;

    /** [Standard] 之外的风格才启用种子管线（上游同义）。 */
    val enabled: Boolean get() = this != Standard

    /** 是否有固定 tone 表（与 [SeedTones.forStyle] 返回 null 的集合互补）。 */
    val hasFixedTones: Boolean get() = !isDynamic && this != Standard

    companion object {
        /** 持久化值 → 枚举：未知/旧值一律回落 [Standard]（不崩、不丢用户设置）。 */
        fun fromStored(value: String?): SeedPaletteStyle =
            entries.firstOrNull { it.name == value } ?: Standard
    }
}
