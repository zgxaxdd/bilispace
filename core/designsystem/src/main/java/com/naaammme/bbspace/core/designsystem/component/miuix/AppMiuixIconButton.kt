package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor

/**
 * 纯图标按钮的最小命中区：**44dp**（iOS HIG 的触控下限，也是本仓浮层控件的口径）。
 *
 * 用 token 相加表达（`xxxl + md` = 32 + 12），不为 44 新增一个间距标度 ——
 * `DesignTokenLintTest` 的 dp 棘轮只许降，直接写 `44.dp` 会污染总量。
 */
private val AppMiuixMinTouchSize = BiliSpacing.xxxl + BiliSpacing.md

/**
 *
 * > production 消费者（2026-10-18 批 3 起）：10 个设置子页的顶栏返回钮 + `BiliTopBar` 的 iOS 分支。
 * W2 批次 1：**Miuix 渲染层**的图标按钮原语（iOS 档唯一出口）。
 *
 * ## 为什么需要一层（而不是直接用 miuix IconButton）
 * 1. **contentColor 桥接**：miuix 的图标按钮把内容色放在自己的 CompositionLocal 里，
 *    本层同时桥接 Material 与 miuix 两个 LocalContentColor —— 槽位里放 Material
 *    `Icon`（本仓全部图标来自 Material Icons）时颜色才跟得上底盘。
 * 2. **命中区**：44dp 的 `defaultMinSize` 在这里统一给，调用方不需要各写一遍
 *    （播放器/顶栏/设置页三处的图标钮从此同尺寸）。
 * 3. 圆角取 `BiliRadius.playerControl`（17dp）：与动作按钮、胶囊同一套圆端语言。
 *
 * 图标本身的字形尺寸由调用方给（`Modifier.size(...)`），本层只负责可点区域。
 */
@Composable
fun AppMiuixIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = biliColorRoles().secondaryLabel,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        // 槽位内的 Material/pure-Compose 内容
        LocalContentColor provides contentColor,
        // miuix 自己的内容色（IconButton 内部可能读它）
        MiuixLocalContentColor provides contentColor
    ) {
        MiuixIconButton(
            onClick = onClick,
            modifier = modifier
                // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
                //
                // 接在这里而不是逐个 feature 接：本原语是 10 个设置子页顶栏 + `BiliTopBar`
                // iOS 分支 + 各对话框的唯一图标钮出口 ⇒ 一行覆盖全部消费方，
                // 天然满足用户「接入到软件中的每一处」的要求。
                //
                // 不传 `visualSize`：可见形状由调用方槽位决定，观察到的就是按钮实际
                // 边界（44dp 命中区），形变以此为几何基准。
                //
                // `appElasticPress` 自带 `foldIn` 去重：即便调用方也挂了一次，
                // 同一 modifier 链上只生效一份（上游同一机制）。
                .appElasticPress()
                .defaultMinSize(
                    minWidth = AppMiuixMinTouchSize,
                    minHeight = AppMiuixMinTouchSize
                ),
            enabled = enabled,
            backgroundColor = containerColor,
            cornerRadius = BiliRadius.playerControl,
            content = content
        )
    }
}
