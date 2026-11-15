package com.naaammme.bbspace.core.designsystem.theme.scheme

/**
 * Material 3 色彩标准（2021 / 2025），纯枚举，**不依赖 MaterialKolor**。
 *
 * 上游 `PaletteSpecPolicy.kt` 用 `com.materialkolor.dynamiccolor.ColorSpec.SpecVersion`
 * 表达同一件事；本仓在 B 方案（不新增依赖）下移植其**纯逻辑**：
 * - 只有 4 个 dynamic 风格（TonalSpot / Neutral / Vibrant / Expressive）支持 SPEC_2025；
 * - 其余风格（含固定表风格与其余 dynamic 风格）一律回落 SPEC_2021。
 *
 * 注意：本文件只回答"该用哪套标准"，**不负责出色** —— 真正把 tone 变成颜色需要
 * HCT/CAM16（见 SeedTones 的 KDoc 与接线方案）。
 */
enum class SeedColorSpec {
    SPEC_2021,
    SPEC_2025,
    ;

    /** 是否支持新版（2025）色板与角色映射。 */
    val isModern: Boolean get() = this == SPEC_2025
}

/**
 * 哪些种子风格支持 SPEC_2025（上游 `PaletteSpecPolicy.supportsMaterialSpec2025` 的同义集合，
 * 只是把库枚举换成本仓的 [SeedPaletteStyle]）。
 *
 * 依据：MaterialKolor 4.1.1 `DynamicScheme` 只为这 4 个风格提供 2025 变体。
 */
fun supportsSeedSpec2025(style: SeedPaletteStyle): Boolean = when (style) {
    SeedPaletteStyle.TonalSpot,
    SeedPaletteStyle.Neutral,
    SeedPaletteStyle.Vibrant,
    SeedPaletteStyle.Expressive -> true

    else -> false
}

/**
 * 用户请求的标准 → **实际可用**的标准（上游 `resolveSeedPaletteSpec` 同义）。
 *
 * 不支持 2025 的风格回落 2021，而不是报错：用户偏好保留在设置里，切到兼容风格后自动恢复
 * （上游设置页的 subtitle 就是按这个语义写"已保留偏好"）。
 */
fun resolveSeedPaletteSpec(
    style: SeedPaletteStyle,
    requested: SeedColorSpec,
): SeedColorSpec = if (supportsSeedSpec2025(style)) requested else SeedColorSpec.SPEC_2021
