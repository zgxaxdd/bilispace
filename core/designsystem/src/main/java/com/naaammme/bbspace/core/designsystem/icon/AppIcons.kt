package com.naaammme.bbspace.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义图标规范：
 * - 视口 24dp（位图型特殊图标可放大 viewport 数值，但尺寸语义不变）；
 * - 线性图标统一 2dp 描边、圆角端点（stroke cap/round join）；
 * - 颜色一律由调用处 `Icon(tint = ...)` 控制：ImageVector 的 path 必须给一个 fill
 *   占位值（否则不渲染），该占位会被 tint 整体覆盖，因此这里不表达任何语义色；
 * - 新增图标请遵循以上规范。
 */
object AppIcons {
    private val placeholderFill = SolidColor(Color.Black)

    private fun icon(name: String, vw: Float = 24f, vh: Float = 24f, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, vw, vh).apply(build).build()

    val Pause: ImageVector by lazy {
        icon("Pause", 256f, 256f) {
            path(fill = placeholderFill) {
                moveTo(168f, 40f)
                horizontalLineTo(192f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 208f, y1 = 56f)
                verticalLineTo(200f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 192f, y1 = 216f)
                horizontalLineTo(168f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 152f, y1 = 200f)
                verticalLineTo(56f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 168f, y1 = 40f)
                close()
            }
            path(fill = placeholderFill) {
                moveTo(64f, 40f)
                horizontalLineTo(88f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 104f, y1 = 56f)
                verticalLineTo(200f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 88f, y1 = 216f)
                horizontalLineTo(64f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 48f, y1 = 200f)
                verticalLineTo(56f)
                arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 64f, y1 = 40f)
                close()
            }
        }
    }

}
