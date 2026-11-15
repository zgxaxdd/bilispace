package com.naaammme.bbspace.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.CapsuleListItem
import com.naaammme.bbspace.core.designsystem.component.IOSSwitch
import com.naaammme.bbspace.core.designsystem.component.IOSChevron
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import androidx.compose.foundation.shape.RoundedCornerShape
@Composable
fun SettingCategory(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape = SettingCategoryShape,
    containerColor: Color? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    CapsuleListItem(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor ?: if (isIOS) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        leadingContent = if (icon != null) {
            {
                // ## 2026-10-18 第十九轮：图标容器回到**圆形**（用户明确要求）
                //
                // 演变：48dp 实心彩圆（Material 2 语汇，用户说"太老"）
                //   → 裸图标无容器（上一版）→ **36dp 浅色圆底 + 同色系图标**（本版）。
                //
                // 用户这一轮的指令是「设置页的 ui 风格也是同道理，做成圆形的风格」，
                // 与播放页"全部圆钮"是同一个诉求：**全站圆形语汇统一**。
                // 所以不是回到旧的大色块，而是取它的"圆"、去掉它的"重"：
                // - 直径 48 → **36dp**（省 12dp 横向空间，文字起点 52dp）；
                // - 底色从 `surfaceContainerHighest`（有厚度）→ **图标色 @ 12%**（无厚度）；
                // - 图标改为同色系 `iconTint`（保持语义色，不变成一堆灰图标）。
                //
                // 这样与播放页 34dp 圆钮、底栏 40dp 指示器同属一套圆形语汇。
                Box(
                    modifier = Modifier
                        .size(SettingIconCircleSize)
                        .background(
                            color = iconTint.copy(alpha = SettingIconCircleFillAlpha),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint
                    )
                }
            }
        } else {
            null
        },
        // iOS 分组行尾 chevron（tertiaryLabel 语义），Material 保持无
        trailingContent = if (isIOS) {
            {
                IOSChevron()
            }
        } else {
            null
        },
        onClick = onClick
    )
}

/**
 * 设置项图标的**圆形容器直径**（36dp）。
 *
 * 取值理由：
 * - 与播放页 34dp 圆钮、底栏 40dp 指示器同量级 ⇒ 全站圆形语汇一致；
 * - 比旧的 48dp 色块小 12dp，文字起点从 64dp 提到 52dp，一屏可多放 1~2 行；
 * - 内嵌 24dp 图标后四周留 6dp 呼吸，圆不会显得挤。
 */
private val SettingIconCircleSize = 36.dp

/**
 * 圆形图标的**底色透明度**（12%）。
 *
 * 只做"淡淡的色片"提示语义色，不做 Material 2 的实心色块：
 * 12% 叠在卡片底色上几乎只比周围亮一点点，扫视时能分辨"这是哪一类设置"，
 * 又不会像一排彩色按钮那样抢注意力。
 */
private const val SettingIconCircleFillAlpha = 0.12f

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
            }
            if (isIOS) {
                IOSSwitch(
                    checked = checked,
                    onCheckedChange = if (enabled) onCheckedChange else null
                )
            } else {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled
                )
            }
        }
    }
}

// 分组卡形状：此前默认参数 `MaterialTheme.shapes.medium`（随 CornerStyle 漂移，且在
// 调用点求值 ⇒ 每次调用新建 Shape）。提成文件级常量后默认参数只引用同一个实例。
private val SettingCategoryShape = RoundedCornerShape(BiliRadius.card)
