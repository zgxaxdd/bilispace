package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.TabRow as MiuixTabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults

/**
 * W2 批次 1：**Miuix 渲染层**的 SegmentedControl（iOS 档唯一出口）。
 *
 * ## 与 Material TabRow 的区别（为什么值得换）
 * miuix 的 TabRow 把选中指示器画在**整块轨道内部**，切换时指示器按库内 spring
 * 位移；Material 的 TabRow 是"每个 Tab 自己一块底"。前者读作"一件东西的两个档位"
 * （iOS segmented 的语义），后者读作"两颗按钮"。本仓 iOS 档此前是自绘的
 * `IOSSegmented`（9dp 圆角 + 滑块位移）—— 本原语是它的 miuix 版本，高度对齐
 * 现有 iOS 口径（32dp 行高 / 半高圆角），不引入新尺寸。
 *
 * ## 颜色（单色体系，全部走角色层）
 * 轨道 = `secondaryLabel` 12%（与 Material 分支的 iOS segmented 同源）；
 * 选中滑块 = 深色主题取 `cardElevated`、浅色主题取 `card`（"底色上浮一档"）；
 * 文字 = 选中 `primaryLabel` / 未选 `secondaryLabel`。
 *
 * ## 为什么 `enabled` 不进 miuix 参数
 * miuix `TabRow` 没有 enabled 参数：禁用语义由本层在 `onTabSelected` 里拦掉。
 * 这样调用方 API 与 Material 分支保持一致，删掉/加上禁用不会改变布局。
 */
@Composable
fun AppMiuixSegmentedControl(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * 轨道底色是否交给调用方画（第 35 轮，2026-10-19）。
     *
     * miuix 的 `cornerRadius` 只裁**选中滑块**，轨道是它内部 Surface 整行铺的直角带
     * （真机实测：首页那条 FM/推荐/… 通栏灰带没有圆角）。
     * 需要圆角轨道的调用方（`FilledTabRow`）在外层 `clip` + `background` 自己画，
     * 这里把轨道底色设为透明，避免两层叠出色差。
     */
    transparentTrack: Boolean = false
) {
    if (tabs.isEmpty()) return
    val roles = biliColorRoles()
    val dark = roles.canvas.luminance() < 0.5f
    val trackColor = if (transparentTrack) {
        androidx.compose.ui.graphics.Color.Transparent
    } else {
        roles.secondaryLabel.copy(alpha = 0.12f)
    }
    val thumbColor = if (dark) roles.cardElevated else roles.card
    val colors = TabRowDefaults.tabRowColors(
        backgroundColor = trackColor,
        contentColor = roles.secondaryLabel,
        selectedBackgroundColor = thumbColor,
        selectedContentColor = roles.primaryLabel
    )
    MiuixTabRow(
        tabs = tabs,
        selectedTabIndex = selectedIndex.coerceIn(0, tabs.lastIndex),
        onTabSelected = { index ->
            if (enabled) onSelect(index)
        },
        modifier = modifier,
        colors = colors,
        // 非滚动场景：宽度交给父容器（0 / Infinity = miuix 的"按可用宽度均分"）
        minWidth = Dp.Hairline,
        maxWidth = Dp.Infinity,
        // 行高沿用现有 iOS segmented 的 32dp；圆角 = 半高 ⇒ 全圆端（token 推导，
        // 不写 16.dp：DesignTokenLintTest 的 dp 棘轮只许降）。
        height = BiliSpacing.xxxl,
        cornerRadius = BiliSpacing.xxxl / 2,
        itemSpacing = Dp.Hairline,
        listState = null
    )
}
