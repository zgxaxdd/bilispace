package com.naaammme.bbspace.core.designsystem.theme.scheme

/**
 * 种子配色三维度的**持久化编解码**（纯函数，JVM 可测）。
 *
 * 为什么要单独一层：`AppSettings`（core:settings）与设计系统分属不同模块，
 * 而"未知/旧值回落默认、永不抛异常"是**设置持久化的公共契约** ——
 * 写在 DataStore 调用点里就只能靠真机验证；抽成纯函数后可在 JVM 单测里穷举
 * 22×2×3 个枚举值并断言往返。
 *
 * 编码口径：一律存 `enum.name`（字符串）。解码口径：
 * - `null`（键不存在）⇒ 默认值；
 * - 未知字符串（跨版本残留 / 手改 DataStore）⇒ 默认值，**不抛异常**；
 * - `SeedPaletteStyle` 额外走 `fromStored`（它自带"改名后回落 Standard"的容错）。
 */
object SeedSettingsCodec {

    fun encodeSeedPaletteStyle(style: SeedPaletteStyle): String = style.name

    fun decodeSeedPaletteStyle(stored: String?): SeedPaletteStyle =
        stored?.let { SeedPaletteStyle.fromStored(it) } ?: SeedPaletteStyle.Standard

    fun encodeSeedColorSpec(spec: SeedColorSpec): String = spec.name

    fun decodeSeedColorSpec(stored: String?): SeedColorSpec =
        stored?.let { value -> SeedColorSpec.entries.firstOrNull { it.name == value } }
            ?: SeedColorSpec.SPEC_2021

    fun encodeSeedContrastLevel(level: SeedContrastLevel): String = level.name

    fun decodeSeedContrastLevel(stored: String?): SeedContrastLevel =
        stored?.let { value -> SeedContrastLevel.entries.firstOrNull { it.name == value } }
            ?: SeedContrastLevel.Default
}
