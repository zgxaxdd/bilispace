package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults

/**
 * W2 批次 1：**Miuix 渲染层**的 Switch（iOS 档唯一出口）。
 *
 * ## 颜色与既有 `IOSSwitch` 逐项对应（换实现不换观感）
 * | 部位 | 既有 IOSSwitch | 本原语 |
 * |---|---|---|
 * | 开轨道 | `primary` | 角色 `accent` |
 * | 关轨道 | `surfaceContainerHighest` | 角色 `cardElevated` |
 * | 滑块 | 纯白 | 两种态都 `Color.White` |
 * | 禁用 | 整件 `alpha = 0.4f` | 递给 miuix 的 disabled 轨道/滑块色（中性灰） |
 *
 * 上一轮真机验过的开关观感因此不变，变的只是**绘制实现**（miuix 的轨道形变/滑块
 * 弹簧曲线是库内动画，不占本仓 `animate*AsState` 棘轮）。
 */
@Composable
fun AppMiuixSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val roles = biliColorRoles()
    MiuixSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
            // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
            //
            // 挂在**整件**上、而不是单独挂在拇指上：开关是一块 51×31 的整体，
            // 只让拇指形变会读作"滑块掉了"，整件形变才是"这个开关被按到了"。
            // 拇指的位移/弹簧仍由 miuix 库内动画负责，两者互不干涉。
            //
            // 门控自然成立：`appElasticPress` 不消费手势，miuix 的横向拖动
            // 会把 `change.isConsumed` 置真，此时本形变立即停手（只保留按压缩放）。
            .appElasticPress(),
        enabled = enabled && onCheckedChange != null,
        colors = SwitchDefaults.switchColors(
            checkedTrackColor = roles.accent,
            uncheckedTrackColor = roles.cardElevated,
            checkedThumbColor = Color.White,
            uncheckedThumbColor = Color.White,
            disabledCheckedTrackColor = roles.accent.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = roles.cardElevated,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.87f),
            disabledUncheckedThumbColor = Color.White.copy(alpha = 0.87f)
        )
    )
}
