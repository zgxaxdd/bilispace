package com.naaammme.bbspace.core.designsystem.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 玻璃用户设置模型（终版方案 3.1）：三档材质 + 统一强度。
 * OFF = 纯半透明 + 细描边；FROSTED = 磨砂（默认）；LIQUID = 液态玻璃
 * （miuix 背板 blur + AGSL 边缘折射 + Fresnel 高光边 + 饱和度补偿）。
 * [strength] 为全玻璃表面统一强度倍率（0.5–2.0，设置页可调）：
 * 缩放模糊半径与折射量，让 Dock/顶栏/胶囊/角标/播放控件一起变强变弱。
 * minSdk 33 后 LIQUID 无需任何版本降级。
 */
@Immutable
data class GlassConfig(
    val material: GlassMaterial = GlassMaterial.FROSTED,
    val strength: Float = 1f
)

enum class GlassMaterial {
    OFF,
    FROSTED,
    LIQUID
}

val LocalGlassConfig = staticCompositionLocalOf { GlassConfig() }
