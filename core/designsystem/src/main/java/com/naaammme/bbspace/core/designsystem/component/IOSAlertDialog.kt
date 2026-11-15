package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliColorRoles
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import kotlinx.coroutines.delay

/**
 * iOS 警告框（iOS 升级阶段 C，方案 §2-G4；规格对照 HIG 与 compose-cupertino
 * 的 CupertinoAlertDialog 实测常量）：
 * - 居中卡片固定宽 **270dp**（CupertinoDialogsTokens.AlertDialogWidth）、圆角 14dp；
 * - 标题（17pt SemiBold）与说明（13pt，secondaryLabel）全部居中，间距 4dp；
 * - 按钮区**横向排布**，行高 44dp，按钮之间用 0.5dp 发丝分隔线（含横竖两条）；
 * - 破坏性动作用 systemRed（[BiliColorRoles.destructive]）；取消按钮粗体；
 * - 入场 scale 0.96→1 + fade（emphasized），退场 150ms fade；
 *   reduceMotion 时退化为纯 fade。
 *
 * MATERIAL 档不使用本组件（调用方保留 M3 AlertDialog）。
 */
@Composable
fun IOSAlertDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissLabel: String? = null,
    onDismissAction: (() -> Unit)? = null,
    destructive: Boolean = false
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val close = { shown = false }
    LaunchedEffect(shown) {
        if (!shown) {
            delay(150)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val roles = biliColorRoles()
        val anim = LocalAnimations.current
        val container = MaterialTheme.colorScheme.surfaceContainerHigh
        val confirmColor = if (destructive) roles.destructive else MaterialTheme.colorScheme.primary
        val enter: EnterTransition =
            if (anim.reduceMotion) fadeIn(tween(anim.short))
            else scaleIn(tween(anim.medium), initialScale = 0.96f) + fadeIn(tween(anim.short))
        val exit: ExitTransition = fadeOut(tween(anim.short))
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { close() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(visible = shown, enter = enter, exit = exit) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .background(container, RoundedCornerShape(14.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* 吞掉卡片内点击，避免透传到 scrim */ }
                        .padding(top = BiliSpacing.xl, bottom = BiliSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = BiliSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = roles.primaryLabel,
                            textAlign = TextAlign.Center
                        )
                        message?.let {
                            Spacer(Modifier.height(BiliSpacing.xs))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = roles.secondaryLabel,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(BiliSpacing.lg))
                    // 按钮区：iOS 惯例为横向排布 + 发丝分隔线（两按钮=左右对分，不叠底部行）
                    if (dismissLabel != null && onDismissAction != null) {
                        Box {
                            Row(modifier = Modifier.height(44.dp)) {
                                AlertButton(
                                    label = dismissLabel,
                                    color = roles.primaryLabel,
                                    bold = true,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        close()
                                        onDismissAction()
                                    }
                                )
                                AlertButton(
                                    label = confirmLabel,
                                    color = confirmColor,
                                    bold = false,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        close()
                                        onConfirm()
                                    }
                                )
                            }
                            VerticalHairline(roles.separator, Modifier.align(Alignment.Center))
                        }
                    } else {
                        HorizontalHairline(roles.separator)
                        AlertButton(
                            label = confirmLabel,
                            color = confirmColor,
                            bold = false,
                            onClick = {
                                close()
                                onConfirm()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertButton(
    label: String,
    color: Color,
    bold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
            //
            // 接在 `AlertButton` 上：警告框的**每一个动作项**（单确认 / 取消+确认
            // 两按钮）都经由本函数渲染，一处覆盖全部。位置在 `clickable` 之前，
            // 形变作用于整块 44dp 命中区而不是只让文字缩放。
            //
            // 高度 44dp 同时被 `IOSComponentStructureTest.alertSpec` 钉住
            //（`height(44.dp)`），本改动不动该值 —— 与 task-9 C 的"行内控件等高"一致。
            .appElasticPress()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
    }
}

@Composable
private fun HorizontalHairline(color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .height(0.5.dp)
            .background(color)
    )
}

@Composable
private fun VerticalHairline(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(0.5.dp)
            .height(44.dp)
            .background(color)
    )
}
