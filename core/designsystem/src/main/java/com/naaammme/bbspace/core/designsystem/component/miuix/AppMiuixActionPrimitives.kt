package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor

/**
 * 动作按钮的**语义档位**（三级语言的第一级）。
 *
 * 刻意**不加第四个档**：本仓的"三级层级"是
 * L1 主操作（圆底+亮）/ L2 档位（连体胶囊）/ L3 只读信息（无底+降级字重）。
 * 动作按钮只承担 L1；L2 由 [AppMiuixSegmentedControl] 表达，L3 不是按钮。
 */
enum class AppMiuixActionTone {
    /** 主操作：实心强调色（一屏最多一个）。 */
    Primary,

    /** 次操作：中性底。 */
    Secondary,

    /** 破坏性操作：危险填充（删除/退出登录）。 */
    Danger
}

/**
 *
 * > production 消费者（2026-10-18 批 3 起）：隐私设置「缓存管理」三个动作（Primary/Secondary）、
 * > 设置页「恢复默认设置」确认钮（`AppMiuixActionTone.Danger`）。
 * W2 批次 1：**Miuix 渲染层**的动作按钮原语（iOS 档唯一出口）。
 *
 * ## 尺寸
 * - 高/宽下限与内边距取 miuix `ButtonDefaults`（48dp 触控下限由库保证，本仓不重写）；
 * - 圆角取 `BiliRadius.playerControl`（17dp = 控件高度一半 ⇒ 全圆端），与播放器
 *   控件、顶栏胶囊同一套"圆端"语言 —— 不再另起一个按钮圆角。
 *
 * ## 颜色（只从角色层取，三种来源）
 * Primary = 角色 `accent`/`onAccent`；Secondary = miuix 中性档；
 * Danger = 角色 `destructive`/`onDangerContainer`。
 * **不接受调用方传色**：传色就会长出第四种按钮，层级语言立刻失效。
 */
@Composable
fun AppMiuixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: AppMiuixActionTone = AppMiuixActionTone.Primary
) {
    val roles = biliColorRoles()
    val colors = when (tone) {
        AppMiuixActionTone.Primary -> ButtonDefaults.buttonColorsPrimary().copy(
            color = roles.accent,
            contentColor = roles.onAccent
        )
        AppMiuixActionTone.Secondary -> ButtonDefaults.buttonColors()
        AppMiuixActionTone.Danger -> ButtonDefaults.buttonColors().copy(
            color = roles.destructive,
            contentColor = roles.onDangerContainer
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    MiuixButton(
        onClick = onClick,
        // 全局拖拽反馈（2026-10-18 第十九轮，原语层接入）：本原语是设置页
        // 缓存管理三个动作 + 「恢复默认设置」确认钮的唯一出口。
        // 动作按钮是**横向长方形**，`appElasticPress` 的 `(width/height).coerceAtMost(1f)`
        // 会让横向拉伸收益递减（避免长条被拉成面条），这正是它要的形态。
        modifier = modifier.appElasticPress(),
        enabled = enabled,
        colors = colors,
        insideMargin = ButtonDefaults.InsideMargin,
        minHeight = ButtonDefaults.MinHeight,
        minWidth = ButtonDefaults.MinWidth,
        cornerRadius = BiliRadius.playerControl,
        interactionSource = interactionSource,
        content = {
            // 文字色**必须**取 miuix 的 LocalContentColor：三种 tone 的
            // contentColor 由上面按底色配好（Primary 是 onAccent、Danger 是
            // onDangerContainer），在这里写死任何一个都会让另外两档失去对比度。
            AppMiuixText(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MiuixLocalContentColor.current
            )
        }
    )
}

/**
 * 只读文本动作（表内动作，如"重试""展开"）：无实心底、强调色文字。
 *
 * 与 [AppMiuixButton] 分开是因为它在层级上不是 L1 主操作 —— 放进卡片里不能长成
 * 一块反白按钮，否则一屏会出现多个"主操作"。
 */
@Composable
fun AppMiuixTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    MiuixTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = ButtonDefaults.MinHeight,
        minWidth = ButtonDefaults.MinWidth,
        cornerRadius = BiliRadius.playerControl,
        colors = ButtonDefaults.textButtonColorsPrimary(),
        insideMargin = ButtonDefaults.InsideMargin,
        textStyle = MaterialTheme.typography.labelLarge
    )
}
