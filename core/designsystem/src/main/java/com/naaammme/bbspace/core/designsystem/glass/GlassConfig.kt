package com.naaammme.bbspace.core.designsystem.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 玻璃用户设置模型（方案 1.3）：玻璃是挂在 iOS 风格上的**材质包**，不是新的
 * 风格维度。MATERIAL 下不生效并在设置页置灰。
 */
@Immutable
data class GlassConfig(
    val enabled: Boolean = false,
    val style: GlassStyle = GlassStyle.STABLE
)

/** 强度档：STABLE 稳态省电（低等级模糊），RICH 更接近完整液态玻璃观感。 */
enum class GlassStyle {
    STABLE,
    RICH
}

val LocalGlassConfig = staticCompositionLocalOf { GlassConfig() }
