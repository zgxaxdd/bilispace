package com.naaammme.bbspace.core.designsystem.seamless

import androidx.compose.runtime.compositionLocalOf

/**
 * 底部悬浮导航栏遮挡屏幕的比例（0f~1f），由 App 层在根组合提供。
 *
 * 用途：OPPO View 无缝动画的 `BUNDLE_LIST_COVER` 需要应用自己算出
 * 「瀑布流列表上下被遮挡区域占屏幕的比例」，系统据此在返回动画末端对遮挡区做 alpha 渐隐。
 * 顶部遮挡（状态栏 + 悬浮顶栏）各页面自己知道；**底部**遮挡来自覆盖在所有一级页面之上的
 * 悬浮 Tab 栏 + 搜索 FAB —— 只由 App 层测得，因此经这个 CompositionLocal 下发，
 * 页面读取后连同自己的顶部比例一起传给 `SeamlessCardOpener.tryOpen(listCover = ...)`。
 *
 * 默认 0f：未接线（或测不到高度）时不传底部比例，行为与从前一致。
 */
val LocalBottomNavOcclusionRatio = compositionLocalOf { 0f }

/**
 * 卡片所在网格的上下遮挡比例（供 `BUNDLE_LIST_COVER` 使用）：
 * [top] = 顶部遮挡区占屏比，[bottom] = 底部遮挡区占屏比。
 */
data class ListCoverRatio(val top: Float, val bottom: Float)

/**
 * 用顶部遮挡高度（px）与屏幕高度（px）算出 `BUNDLE_LIST_COVER` 需要的比例对，
 * 底部比例取 App 层下发的 [LocalBottomNavOcclusionRatio]。
 *
 * 任一输入不可用时返回 null（不传该字段，系统退回默认行为）。
 */
fun listCoverRatio(
    topOcclusionPx: Int,
    screenHeightPx: Int,
    bottomOcclusionRatio: Float = 0f
): ListCoverRatio? {
    if (screenHeightPx <= 0) return null
    val top = (topOcclusionPx.toFloat() / screenHeightPx).coerceIn(0f, 1f)
    val bottom = bottomOcclusionRatio.coerceIn(0f, 1f)
    if (top <= 0f && bottom <= 0f) return null
    return ListCoverRatio(top = top, bottom = bottom)
}
