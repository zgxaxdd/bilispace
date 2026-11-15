package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * W2 批次 1：**Miuix 渲染层**的 Card 原语（iOS 档唯一出口）。
 *
 * ## production 消费者（**别再写"零消费者"**）
 * `StateMessageCard` 用它渲染全站的三态卡片（**78 处调用点**）—— 这是本原语最早、也是最大的
 * 消费者。2026-10-18 有两轮代理在报告里写成"全仓零生产消费者"，据此否决了在 im/space 的长列表里
 * 使用它（那个**结论本身是对的**：长列表怀疑的是 squircle 裁剪 + `PressFeedbackType.Sink` 的滚动
 * 开销，需真机实测），但**理由写错了**。正确表述是："已在三态卡片上验证，长列表待真机评估"。
 *
 * ## 形状语言（`d-ios8` 大圆角 + squircle）
 * 圆角默认 `BiliRadius.card`（20dp），以 `cornerRadius` 传给 miuix —— miuix 的 Card
 * **内部用 squircle（超椭圆）绘制**，不是普通圆角矩形。这正是"现代 iOS 卡片"与
 * Material 卡片观感差异的来源：超椭圆在 20dp 半径上四角过渡更连续，不会出现
 * 圆角与直边的"接缝折点"。
 *
 * ## 层级（与 BRIEF §5 的三级语言一致）
 * - 卡片底 = 角色 `card`（= 主题 `surfaceContainerLow`，无重阴影，靠色阶分层）；
 * - 内容色 = 角色 `primaryLabel`；
 * - 阴影默认 [Dp.Hairline]（0dp 语义），**不引入 Material elevation 的投影**。
 *
 * ## 按压
 * miuix 的 `PressFeedbackType.Sink`：按下时整卡轻微下沉（miuix 原生反馈），
 * 与上一轮定的"按压 = 叠色 + 0.97 缩放"同族但由库内实现 —— 本仓不再为它新增
 * `Modifier.composed` 或 `animate*AsState`（守卫棘轮已满）。
 */
@Composable
fun AppMiuixCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = BiliRadius.card,
    containerColor: Color = biliColorRoles().card,
    contentColor: Color = biliColorRoles().primaryLabel,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.defaultColors(
        color = containerColor,
        contentColor = contentColor
    )
    val bridged: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(
            LocalContentColor provides MiuixLocalContentColor.current
        ) {
            content()
        }
    }
    if (onClick != null || onLongClick != null) {
        // 光斑裁剪形状按本卡真实圆角，且只在圆角变化时重建 Shape
        //（组合体内联 `RoundedCornerShape(...)` 每次重组分配一份，见
        // ListAllocationStructureTest.shapeConstantsAreNeverBuiltInsideComposables）。
        val pressHighlightShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
        MiuixCard(
            // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
            //
            // **只在可点分支挂**：不可点的卡片没有按压目标，挂了只会让静态容器
            // 也参与一次 pointerInput 命中测试（白付一份节点与状态）。
            //
            // `highlightShape` 传本卡真实圆角：默认值 `RoundedCornerShape(50)` 是
            // **百分比 50%** 的胶囊形，套在 20dp 圆角大卡上会把光斑裁成椭圆。
            // 与 miuix 自带的 `PressFeedbackType.Sink`（整卡下沉）并存：
            // 一个管"按下时的光斑 + 跟手形变"，一个管"整卡层级下沉"，两者都不消费手势。
            modifier = modifier.appElasticPress(highlightShape = pressHighlightShape),
            cornerRadius = cornerRadius,
            colors = colors,
            pressFeedbackType = PressFeedbackType.Sink,
            onClick = onClick,
            onLongPress = onLongClick,
            content = bridged
        )
    } else {
        MiuixCard(
            modifier = modifier,
            cornerRadius = cornerRadius,
            colors = colors,
            content = bridged
        )
    }
}
