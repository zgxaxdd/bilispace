package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * 间距 token，仅在新代码与 UI 改造中逐步采用，不要求全局替换既有内联 dp。
 *
 * 阶段 A（iOS 升级 2026-09-25）补齐 6/10dp 两档：此前它们是仅有的
 * "高频使用却没有 token"的间距值（6dp×111 处、10dp×80 处，见 DesignTokenLintTest
 * 的棘轮记录），栅格列距 / 芯片内边距等语义从此有了单一改动点。
 */
object BiliSpacing {
    val xs = 4.dp
    val xsPlus = 6.dp
    val sm = 8.dp
    val smPlus = 10.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}
