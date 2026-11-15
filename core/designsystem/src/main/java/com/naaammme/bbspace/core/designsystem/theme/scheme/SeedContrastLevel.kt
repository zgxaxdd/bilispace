package com.naaammme.bbspace.core.designsystem.theme.scheme

/**
 * 对比度档（纯枚举，**不依赖 MaterialKolor**）。
 *
 * ## 数值从哪里来（不是拍的）
 * 上游用 `com.materialkolor.Contrast`（MaterialKolor 4.1.1）。其字节码里的构造值经
 * `javap -c` 核实为：`Default = 0.0`、`Medium = 0.5`、`High = 1.0`、`Reduced = -1.0`
 * （`material-kolor-api.jar` → `com/materialkolor/Contrast.class` 的 `<clinit>`）。
 * 本枚举保存同一组数值，将来接 HCT 时可直接作为 MCU 的 `contrastLevel` 参数。
 *
 * ## 为什么单独一个类型
 * 「对比度档」是设置页/配色管线的公共词汇，但它不该把第三方库拖进 designsystem 的
 * 公共 API；本仓在 B 方案（不新增依赖）下用它承接上游的 Contrast 语义。
 * 展示顺序与上游一致：**Reduced → Default → Medium → High**。
 */
enum class SeedContrastLevel(val level: Double) {
    Reduced(-1.0),
    Default(0.0),
    Medium(0.5),
    High(1.0),
    ;

    companion object {
        /**
         * 任意数值 → 最近的合法档（用于反序列化/上游旧值/未来滑杆）。
         *
         * 边界口径：
         * - `NaN` → [Default]（不抛异常：配色不该因为一个坏值崩掉界面）；
         * - 等距（如 -0.5，距 Reduced 与 Default 各 0.5）→ 取**更接近 0** 的一档
         *   （语义是"不要因为四舍五入把用户推到极端对比度"）；
         * - 越界（如 ±2.0）→ 两端档位（吸附，不外推）。
         */
        fun fromLevel(level: Double): SeedContrastLevel {
            if (level.isNaN()) return Default
            return entries.minWith(
                compareBy<SeedContrastLevel> { kotlin.math.abs(it.level - level) }
                    .thenBy { kotlin.math.abs(it.level) }
            )
        }
    }
}
