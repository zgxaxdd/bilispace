package com.naaammme.bbspace.core.designsystem.theme

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 色板工具：HSV 调 tone 与线性混色 mix。
 *
 * 此前是 [Theme.kt] 内的 `private` 扩展——Kotlin 的 private 是文件级可见性，
 * 独立文件（如 iOS 色板）访问不到。抽到这里改为 internal 后，Material 与 iOS
 * 两条取色链路共用同一套实现；函数体保持原样，Material 路径的视觉不变。
 */
internal fun Color.tone(
    hueShift: Float = 0f,
    saturationScale: Float = 1f,
    value: Float
): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
    hsv[1] = (hsv[1] * saturationScale).coerceIn(0f, 1f)
    hsv[2] = value.coerceIn(0f, 1f)
    return Color(AndroidColor.HSVToColor(hsv))
}

internal fun Color.mix(other: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = red * (1f - t) + other.red * t,
        green = green * (1f - t) + other.green * t,
        blue = blue * (1f - t) + other.blue * t,
        alpha = alpha * (1f - t) + other.alpha * t
    )
}
