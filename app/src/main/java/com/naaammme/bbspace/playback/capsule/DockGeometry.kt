package com.naaammme.bbspace.playback.capsule

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 底栏几何快照（终版方案 4.3）：供胶囊融合动画计算"沉入底栏"的落点。
 * 由 TopLevelFloatingNavigation 的 onGloballyPositioned 追加写入（不改动既有
 * onOcclusionChanged 逻辑），经 [LocalDockGeometry] 下发。
 */
@Immutable
data class DockGeometry(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val cornerRadiusPx: Float,
    val containerColor: Color,
    /** 滚动隐藏态：隐藏时胶囊不融合，退回普通平移淡出 */
    val visible: Boolean
)

val LocalDockGeometry = staticCompositionLocalOf<DockGeometry?> { null }
