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
 * 默认返回 0f：未接线（或测不到高度）时不传底部比例，行为与从前一致。
 *
 * ## 为什么下发的是 `() -> Float` 而不是 `Float`
 *
 * 下发 `Float` 时，**提供端**必须在组合期读那个 mutableState 才能 `provides` 它，
 * 而提供端是 AppNavHost 自己的 scope —— 底部遮挡比一旦被写一次，整棵主框架树
 * （四个 Tab 的内容 + Dock + 胶囊）就跟着重组一遍。实测这个写入发生在
 * Dock 与播放胶囊的合并动画期间，于是 280ms 里每帧重组一次整树。
 *
 * 改成 provider 后，提供端只造一个 lambda、**不读值**，组合期不登记观察；
 * 读取推迟到消费者真正需要它的那一刻（点击卡片发起无缝动画时）。
 */
val LocalBottomNavOcclusionRatio = compositionLocalOf<() -> Float> { { 0f } }

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
