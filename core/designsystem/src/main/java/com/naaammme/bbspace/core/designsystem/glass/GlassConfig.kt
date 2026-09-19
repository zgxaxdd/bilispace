package com.naaammme.bbspace.core.designsystem.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 玻璃用户设置模型（终版方案 3.1）：三档材质。
 * OFF = iOS 定稿纯半透明 + 细描边；FROSTED = 磨砂（默认）；LIQUID = 液态玻璃
 * （Haze 模糊 + AGSL 折射 + Fresnel 高光边 + 外发光 + 饱和度补偿）。
 * minSdk 33 后 LIQUID 无需任何版本降级。
 */
@Immutable
data class GlassConfig(
    val material: GlassMaterial = GlassMaterial.FROSTED
)

enum class GlassMaterial {
    OFF,
    FROSTED,
    LIQUID
}

val LocalGlassConfig = staticCompositionLocalOf { GlassConfig() }
