package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults

/**
 *
 * > production 消费者（2026-10-18 批 3 起）：外观设置「字体大小」（9 档离散、松手提交）。
 * W2 批次 1：**Miuix 渲染层**的 Slider（iOS 档唯一出口）。
 *
 * ## 为什么值得换
 * miuix 的 Slider 自带**跟手阻尼 + 松手吸附 + 关键点**（本仓 `d-ios11` 的
 * "拖动跟手/回弹"规格正是从它那套语言来的）。这些动画在库内部实现，因此
 * **不占**本仓 `animate*AsState`（上限 10，已满）与 `tween/spring` 字面量
 * （上限 4，已满）两条棘轮。
 *
 * ## 颜色（单色体系，三处同源，全部走角色层）
 * 已播轨 = `accent`；未播轨 = `cardElevated`；滑块 = `card`。
 * 禁用态用同一组颜色降 alpha 表达（0.38 / 0.5 / 0.6 是 Material 禁用态
 * 的标准三档，不是新造的刻度）。
 *
 * ## 与播放器进度条的关系
 * 播放器控制条的进度条**仍是自绘**（它要叠加 SponsorBlock 片段色、拖动气泡、
 * 玻璃轨道），本轮不动它 —— 本原语服务的是设置页/弹幕设置这类标准滑杆。
 */
@Composable
fun AppMiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val roles = biliColorRoles()
    MiuixSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.sliderColors(
            foregroundColor = roles.accent,
            backgroundColor = roles.cardElevated,
            thumbColor = roles.card,
            disabledForegroundColor = roles.accent.copy(alpha = 0.38f),
            disabledBackgroundColor = roles.cardElevated.copy(alpha = 0.5f),
            disabledThumbColor = roles.card.copy(alpha = 0.6f)
        )
    )
}
