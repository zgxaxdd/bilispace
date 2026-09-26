package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.naaammme.bbspace.core.designsystem.theme.BiliColorRoles
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import kotlinx.coroutines.delay

/**
 * iOS 动作面板（iOS 升级阶段 C，方案 §2-G4；规格对照 HIG 与
 * compose-cupertino 的 CupertinoActionSheet 实测常量）：
 * - 底部贴边、左右侧距 8dp、分组圆角 14dp；
 * - 动作行高 56dp、文字居中；破坏性动作用 systemRed（角色色 [BiliColorRoles.destructive]）；
 * - 取消按钮**独立成块**、粗体，与动作组之间留 8dp 间隙；
 * - 标题/说明位于动作组上方同宽卡片，说明在上、与标题间距 6dp；
 * - 入场 slide-up + fade（emphasized 300ms），退场 150ms EaseIn（与
 *   compose-cupertino 的 sheetAnimation 同参数）；
 * - [com.naaammme.bbspace.core.designsystem.theme.BiliAnimations.reduceMotion]
 *   语义由调用方的 [com.naaammme.bbspace.core.designsystem.theme.LocalAnimations]
 *   派生： reduceMotion 时入场退场退化为 160ms 纯 fade。
 *
 * 层级流：[actions] 里的动作可携带子动作（[IOSActionSheetAction.children]），
 * 点击后在同一弹层内切换为子动作列表（iOS 惯例的二级动作面板），顶部显示返回。
 * MATERIAL 档不使用本组件（调用方保留原弹层）。
 */
data class IOSActionSheetAction(
    val title: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
    /** 子动作：非空时点击本项不关闭，而是切换到子面板。 */
    val children: List<IOSActionSheetAction> = emptyList()
)

/** 分组圆角：Apple action sheet 分组 14dp（介于 groupedList 16 与胶囊之间）。 */
private val ActionGroupRadius = 14.dp
/** compose-cupertino ActionSheetButtonHeight。 */
private val ActionRowHeight = 56.dp
/** compose-cupertino ActionSheetSidePadding。 */
private val SidePadding = 8.dp

@Composable
fun IOSActionSheet(
    onDismiss: () -> Unit,
    actions: List<IOSActionSheetAction>,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    cancelLabel: String = "取消"
) {
    // 退场动画需要"先播完再真关闭"：shown=false → 等退场时长 → onDismiss
    var shown by remember { mutableStateOf(false) }
    // 子面板栈：非空时显示最后一级的动作列表
    var subStack by remember { mutableStateOf<List<Pair<String, List<IOSActionSheetAction>>>>(emptyList()) }
    LaunchedEffect(Unit) { shown = true }

    val closing = { ->
        shown = false
    }
    LaunchedEffect(shown) {
        if (!shown) {
            delay(160)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = closing,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        val roles = biliColorRoles()
        val container = MaterialTheme.colorScheme.surfaceContainerLow
        // reduceMotion（动效速度=关）：位移/缩放类退化为纯 fade（方案 3.2.5）
        val anim = LocalAnimations.current
        val enter: EnterTransition =
            if (anim.reduceMotion) fadeIn(tween(160))
            else slideInVertically(tween(300)) { it } + fadeIn(tween(200))
        val exit: ExitTransition =
            if (anim.reduceMotion) fadeOut(tween(160))
            else slideOutVertically(tween(150)) { it } + fadeOut(tween(150))
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { closing() }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = SidePadding, vertical = SidePadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(visible = shown, enter = enter, exit = exit) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val current = subStack.lastOrNull()
                        if (current != null) {
                            // 二级面板：顶部返回条 + 子动作
                            ActionGroupCard(container = container, shape = RoundedCornerShape(ActionGroupRadius)) {
                                ActionRow(
                                    text = "返回${current.first}",
                                    color = MaterialTheme.colorScheme.primary,
                                    bold = true,
                                    onClick = { subStack = subStack.dropLast(1) }
                                )
                                Hairline(roles.separator)
                                SubActionRows(
                                    children = current.second,
                                    roles = roles,
                                    onChild = { child ->
                                        if (child.children.isEmpty()) {
                                            closing()
                                            child.onClick()
                                        } else {
                                            subStack = subStack + (child.title to child.children)
                                        }
                                    }
                                )
                            }
                        } else {
                            if (title != null || message != null) {
                                ActionGroupCard(container = container, shape = RoundedCornerShape(ActionGroupRadius)) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.md),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        title?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = roles.secondaryLabel,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        message?.let {
                                            Spacer(Modifier.height(BiliSpacing.xsPlus))
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = roles.secondaryLabel,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                            ActionGroupCard(container = container, shape = RoundedCornerShape(ActionGroupRadius)) {
                                actions.forEachIndexed { index, action ->
                                    if (index > 0) Hairline(roles.separator)
                                    ActionRow(
                                        text = action.title,
                                        color = if (action.destructive) roles.destructive else MaterialTheme.colorScheme.primary,
                                        bold = false,
                                        enabled = action.enabled,
                                        onClick = {
                                            if (action.children.isEmpty()) {
                                                closing()
                                                action.onClick()
                                            } else {
                                                subStack = subStack + (action.title to action.children)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(SidePadding))
                        // 取消独立块（iOS 惯例）：粗体、独立圆角卡
                        ActionGroupCard(container = container, shape = RoundedCornerShape(ActionGroupRadius)) {
                            ActionRow(
                                text = cancelLabel,
                                color = MaterialTheme.colorScheme.primary,
                                bold = true,
                                onClick = closing
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SubActionRows(
    children: List<IOSActionSheetAction>,
    roles: BiliColorRoles,
    onChild: (IOSActionSheetAction) -> Unit
) {
    children.forEachIndexed { index, child ->
        if (index > 0) Hairline(roles.separator)
        ActionRow(
            text = child.title,
            color = if (child.destructive) roles.destructive else MaterialTheme.colorScheme.primary,
            bold = false,
            enabled = child.enabled,
            onClick = { onChild(child) }
        )
    }
}

@Composable
private fun ActionGroupCard(
    container: Color,
    shape: RoundedCornerShape,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 500.dp)
            .fillMaxWidth()
            .background(container, shape)
    ) {
        content()
    }
}

@Composable
private fun ActionRow(
    text: String,
    color: Color,
    bold: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ActionRowHeight)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = BiliSpacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = color.copy(alpha = alpha),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Hairline(color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(horizontal = 0.5.dp)
            .background(color)
    )
}
